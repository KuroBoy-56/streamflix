package com.streamflixreborn.streamflix.ui

import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.fragment.app.Fragment
import androidx.leanback.widget.OnChildViewHolderSelectedListener
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.databinding.LayoutHeroRowTvBinding
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.format
import java.util.Locale

/**
 * Interfaz de TV "hero + una sola fila":
 * - Arriba, el póster/fondo grande y la información del contenido enfocado.
 * - Abajo, una única fila horizontal de portadas.
 * - Arriba/abajo oculta la fila visible y muestra la categoría anterior/siguiente,
 *   dejando la portada siempre despejada.
 */
class HeroRowTvController(
    private val fragment: Fragment,
    private val binding: LayoutHeroRowTvBinding,
    private val isLiveContent: () -> Boolean = { false },
    /** Portadas compactas (Inicio, Películas, Series). En Vivo usa el tamaño normal. */
    private val compactRow: Boolean = true,
) {

    /** Tamaño de las portadas de la fila respecto al normal (un poco menos de la mitad). */
    private val cardScale = 0.45f
    /** El texto se reduce menos para que siga siendo legible en la TV. */
    private val cardTextScale = 0.7f
    private val scaledCards: MutableSet<View> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())
    private val smoothInterpolator = android.view.animation.PathInterpolator(0.4f, 0f, 0.2f, 1f)
    /** Capa del póster visible; la otra recibe la siguiente imagen y se funde encima. */
    private var frontBackdrop: ImageView = binding.ivHeroBackdrop

    val adapter = AppAdapter()

    private var rows: List<Category> = emptyList()
    private var rowIndex = 0
    private val selectedPositions = mutableMapOf<String, Int>()
    private var isSwitchingRow = false
    private var currentBackdrop: String? = null

    private val heroHandler = Handler(Looper.getMainLooper())
    private var pendingHero: Runnable? = null

    /** Nombre visible de una categoría (permite traducir nombres internos como FEATURED). */
    var rowTitle: (Category) -> String = { it.name }

    val hasRows: Boolean get() = rows.isNotEmpty()

    init {
        binding.hgvHeroRow.apply {
            adapter = this@HeroRowTvController.adapter.apply {
                stateRestorationPolicy = RecyclerView.Adapter.StateRestorationPolicy.PREVENT_WHEN_EMPTY
            }
            setRowHeight(ViewGroup.LayoutParams.WRAP_CONTENT)
            val spacing = resources.getDimension(R.dimen.home_spacing)
            setItemSpacing(spacing.toInt())

            // Movimiento fluido: sin animaciones de cambio de lista (evitan parpadeos al cambiar
            // de fila), tarjetas ya creadas en caché y desplazamiento suave.
            itemAnimator = null
            setHasFixedSize(true)
            setItemViewCacheSize(24)
            (layoutManager as? androidx.recyclerview.widget.LinearLayoutManager)?.initialPrefetchItemCount = 10
            if (compactRow) {
                addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) = shrinkCard(view)
                    override fun onChildViewDetachedFromWindow(view: View) = Unit
                })
            }
            isFocusable = true
            descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS

            setOnChildViewHolderSelectedListener(object : OnChildViewHolderSelectedListener() {
                override fun onChildViewHolderSelected(
                    parent: RecyclerView,
                    child: RecyclerView.ViewHolder?,
                    position: Int,
                    subposition: Int,
                ) {
                    if (isSwitchingRow) return
                    val row = rows.getOrNull(rowIndex) ?: return
                    selectedPositions[row.name] = position
                    row.list.getOrNull(position)?.let { scheduleHero(it, row) }
                }
            })

            setOnKeyInterceptListener { event ->
                if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyInterceptListener false
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                        // Siempre se consume: la fila es el único bloque enfocable abajo.
                        if (!isSwitchingRow) moveRow(+1)
                        true
                    }
                    KeyEvent.KEYCODE_DPAD_UP -> when {
                        isSwitchingRow -> true
                        rowIndex > 0 -> {
                            moveRow(-1); true
                        }
                        else -> false
                    }
                    else -> false
                }
            }
        }
    }

    fun submitRows(categories: List<Category>, requestFocus: Boolean = false) {
        val previousName = rows.getOrNull(rowIndex)?.name
        rows = categories.filter { it.list.isNotEmpty() }
        if (rows.isEmpty()) {
            adapter.submitList(emptyList())
            binding.llHeroRow.visibility = View.INVISIBLE
            return
        }
        binding.llHeroRow.visibility = View.VISIBLE
        rowIndex = rows.indexOfFirst { it.name == previousName }.takeIf { it >= 0 }
            ?: rowIndex.coerceIn(0, rows.lastIndex)
        showRow(requestFocus = requestFocus || binding.hgvHeroRow.hasFocus())
    }

    fun requestFocus() {
        binding.hgvHeroRow.requestFocus()
    }

    /** Vuelve a la primera fila (útil al pulsar Atrás). Devuelve true si cambió algo. */
    fun resetToFirstRow(): Boolean {
        if (rowIndex == 0 || rows.isEmpty() || isSwitchingRow) return false
        moveRow(-rowIndex)
        return true
    }

    fun release() {
        heroHandler.removeCallbacksAndMessages(null)
        binding.llHeroRow.animate().cancel()
        binding.ivHeroBackdropAlt.animate().cancel()
    }

    private fun moveRow(delta: Int) {
        val target = rowIndex + delta
        if (target !in rows.indices || target == rowIndex) return

        isSwitchingRow = true
        // Mantener el foco dentro del inicio mientras se reemplazan las portadas.
        binding.vHeroFocusAnchor.isFocusable = true
        binding.vHeroFocusAnchor.requestFocus()

        val distance = (if (compactRow) 20 else 36).dp(binding.root.context).toFloat()
        val direction = if (delta > 0) -1f else 1f

        binding.llHeroRow.animate()
            .alpha(0f)
            .translationY(direction * distance)
            .setDuration(120)
            .setInterpolator(smoothInterpolator)
            .withLayer()
            .withEndAction {
                rowIndex = target
                showRow(requestFocus = true)
                binding.llHeroRow.translationY = -direction * distance
                binding.llHeroRow.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(220)
                    .setInterpolator(smoothInterpolator)
                    .withLayer()
                    .withEndAction { isSwitchingRow = false }
                    .start()
            }
            .start()
    }

    private fun showRow(requestFocus: Boolean) {
        val row = rows.getOrNull(rowIndex) ?: return
        val context = binding.root.context

        binding.tvHeroRowTitle.text = rowTitle(row)
        binding.tvHeroRowPosition.text = String.format(Locale.ROOT, "%d / %d", rowIndex + 1, rows.size)
        binding.tvHeroRowNext.apply {
            val next = rows.getOrNull(rowIndex + 1)
            text = next?.let { "▼  " + context.getString(R.string.home_next_row, rowTitle(it)) }
            visibility = if (next != null) View.VISIBLE else View.INVISIBLE
        }

        adapter.submitList(row.list)

        val position = (selectedPositions[row.name] ?: 0).coerceIn(0, row.list.lastIndex)
        binding.hgvHeroRow.selectedPosition = position
        row.list.getOrNull(position)?.let { bindHero(it, row) }

        if (requestFocus) {
            focusRow(position, attempts = 3)
        } else {
            binding.vHeroFocusAnchor.isFocusable = false
        }
    }

    /** Enfoca la portada seleccionada; si la fila aún no está dibujada, reintenta en el siguiente frame. */
    private fun focusRow(position: Int, attempts: Int) {
        binding.hgvHeroRow.post {
            if (!fragment.isAdded) return@post
            binding.hgvHeroRow.setSelectedPosition(position)
            val focused = binding.hgvHeroRow.childCount > 0 && binding.hgvHeroRow.requestFocus()
            if (!focused && attempts > 0) {
                focusRow(position, attempts - 1)
            } else {
                binding.vHeroFocusAnchor.isFocusable = false
            }
        }
    }

    private fun scheduleHero(item: AppAdapter.Item, row: Category) {
        pendingHero?.let { heroHandler.removeCallbacks(it) }
        pendingHero = Runnable { bindHero(item, row) }.also { heroHandler.postDelayed(it, 120) }
        preloadNeighbours(row, row.list.indexOf(item))
    }

    private fun bindHero(item: AppAdapter.Item, row: Category) {
        if (!fragment.isAdded) return
        // La tarjeta "Ver todo" no cambia el póster grande.
        if (item is com.streamflixreborn.streamflix.models.Genre) return
        val context = binding.root.context
        val live = isLiveContent()

        var title: String? = null
        var overview: String? = null
        var backdrop: String? = null
        var type: String? = null
        var year: String? = null
        var quality: String? = null
        var rating: Double? = null

        when (item) {
            is Movie -> {
                title = item.title
                overview = item.overview
                backdrop = item.banner?.takeIf { it.isNotBlank() } ?: item.poster
                type = context.getString(R.string.movie_item_type)
                year = item.released?.format("yyyy")
                quality = item.quality
                rating = item.rating
            }
            is TvShow -> {
                title = item.title
                overview = item.overview
                backdrop = item.banner?.takeIf { it.isNotBlank() } ?: item.poster
                type = if (live) context.getString(R.string.main_menu_live) else context.getString(R.string.tv_show_item_type)
                year = if (live) null else item.released?.format("yyyy")
                quality = item.quality
                rating = item.rating
            }
            is Episode -> {
                val season = item.season?.number
                title = item.tvShow?.title ?: item.title
                overview = item.overview ?: item.tvShow?.overview
                backdrop = item.tvShow?.banner?.takeIf { it.isNotBlank() } ?: item.poster ?: item.tvShow?.poster
                type = when {
                    season != null && season != 0 -> context.getString(
                        R.string.tv_show_item_season_number_episode_number, season, item.number
                    )
                    else -> context.getString(R.string.tv_show_item_episode_number, item.number)
                } + (item.title?.takeIf { it.isNotBlank() }?.let { " · $it" } ?: "")
            }
        }

        binding.tvHeroCategory.text = rowTitle(row)
        binding.tvHeroTitle.text = title.orEmpty()
        binding.tvHeroOverview.apply {
            text = overview.orEmpty()
            visibility = if (overview.isNullOrBlank()) View.GONE else View.VISIBLE
        }
        binding.tvHeroType.setTextOrGone(type)
        binding.tvHeroYear.setTextOrGone(year)
        binding.tvHeroQuality.setTextOrGone(quality)
        binding.tvHeroRating.setTextOrGone(
            rating?.takeIf { it > 0 }?.let { "★ " + String.format(Locale.ROOT, "%.1f", it) }
        )

        loadBackdrop(backdrop, live)
    }

    private fun loadBackdrop(url: String?, live: Boolean) {
        if (url == currentBackdrop) return
        currentBackdrop = url
        if (!fragment.isAdded) return

        // Dos capas fijas: la de abajo (base) y la de arriba (top). La imagen nueva se carga en
        // la capa que no se ve y luego solo se anima la transparencia de la capa de arriba.
        // Así el cambio de póster es un fundido suave, sin pantallazo negro ni recálculos.
        val base = binding.ivHeroBackdrop
        val top = binding.ivHeroBackdropAlt
        val showingTop = frontBackdrop === top
        top.animate().cancel()
        top.alpha = if (showingTop) 1f else 0f
        base.alpha = 1f

        listOf(base, top).forEach { iv ->
            // Los logos de canales en vivo se muestran completos a la derecha (no recortados).
            if (live) {
                iv.scaleType = ImageView.ScaleType.FIT_END
                iv.setPadding(0, 32.dp(iv.context), 48.dp(iv.context), 32.dp(iv.context))
            } else {
                iv.scaleType = ImageView.ScaleType.CENTER_CROP
                iv.setPadding(0, 0, 0, 0)
            }
        }

        val target = if (showingTop) base else top
        Glide.with(fragment)
            .load(url)
            .dontAnimate()
            .listener(object : com.bumptech.glide.request.RequestListener<android.graphics.drawable.Drawable> {
                override fun onLoadFailed(
                    e: com.bumptech.glide.load.engine.GlideException?, model: Any?,
                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>, isFirstResource: Boolean,
                ): Boolean = false

                override fun onResourceReady(
                    resource: android.graphics.drawable.Drawable, model: Any,
                    target: com.bumptech.glide.request.target.Target<android.graphics.drawable.Drawable>?,
                    dataSource: com.bumptech.glide.load.DataSource, isFirstResource: Boolean,
                ): Boolean {
                    if (currentBackdrop != url) return false
                    top.post { crossfadeTo(showTop = !showingTop, url = url) }
                    return false
                }
            })
            .into(target)
    }

    private fun crossfadeTo(showTop: Boolean, url: String?) {
        if (!fragment.isAdded || currentBackdrop != url) return
        val base = binding.ivHeroBackdrop
        val top = binding.ivHeroBackdropAlt
        frontBackdrop = if (showTop) top else base
        top.animate()
            .alpha(if (showTop) 1f else 0f)
            .setDuration(320)
            .setInterpolator(smoothInterpolator)
            .withEndAction {
                // La capa que quedó oculta se libera (también evita ver logos viejos detrás en En Vivo).
                if (fragment.isAdded && currentBackdrop == url) {
                    runCatching { Glide.with(fragment).clear(if (showTop) base else top) }
                }
            }
            .start()
    }

    /** Precarga el fondo de las portadas vecinas para que el cambio de póster sea inmediato. */
    private fun preloadNeighbours(row: Category, position: Int) {
        if (position < 0 || !fragment.isAdded) return
        listOf(position - 1, position + 1, position + 2).forEach { i ->
            val url = when (val it = row.list.getOrNull(i)) {
                is Movie -> it.banner?.takeIf { b -> b.isNotBlank() } ?: it.poster
                is TvShow -> it.banner?.takeIf { b -> b.isNotBlank() } ?: it.poster
                is Episode -> it.tvShow?.banner?.takeIf { b -> b.isNotBlank() } ?: it.poster
                else -> null
            }
            if (!url.isNullOrBlank()) runCatching { Glide.with(fragment).load(url).preload() }
        }
    }

    /**
     * Reduce una tarjeta de la fila (solo dentro de esta fila; las tarjetas de búsqueda,
     * cuadrículas y otras pantallas mantienen su tamaño). Se hace una sola vez por tarjeta.
     */
    private fun shrinkCard(card: View) {
        // Las portadas ya vienen del tamaño de Roku (AppAdapter.rokuPosterSize): no se achican.
        if (card.getTag(R.id.roku_poster_card) == true) return
        if (!scaledCards.add(card)) return
        scaleView(card)
    }

    private fun scaleView(view: View) {
        view.layoutParams?.let { lp ->
            if (lp.width > 0) lp.width = (lp.width * cardScale).toInt()
            if (lp.height > 0) lp.height = (lp.height * cardScale).toInt()
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.setMargins(
                    (lp.leftMargin * cardScale).toInt(), (lp.topMargin * cardScale).toInt(),
                    (lp.rightMargin * cardScale).toInt(), (lp.bottomMargin * cardScale).toInt(),
                )
                lp.marginStart = (lp.marginStart * cardScale).toInt()
                lp.marginEnd = (lp.marginEnd * cardScale).toInt()
            }
            view.layoutParams = lp
        }
        view.setPaddingRelative(
            (view.paddingStart * cardScale).toInt(), (view.paddingTop * cardScale).toInt(),
            (view.paddingEnd * cardScale).toInt(), (view.paddingBottom * cardScale).toInt(),
        )
        if (view is android.widget.TextView) {
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, view.textSize * cardTextScale)
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) scaleView(view.getChildAt(i))
        }
    }

    private fun android.widget.TextView.setTextOrGone(value: String?) {
        text = value.orEmpty()
        visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
    }
}
