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
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions
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
) {

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
            setItemSpacing(resources.getDimension(R.dimen.home_spacing).toInt())
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
    }

    private fun moveRow(delta: Int) {
        val target = rowIndex + delta
        if (target !in rows.indices || target == rowIndex) return

        isSwitchingRow = true
        // Mantener el foco dentro del inicio mientras se reemplazan las portadas.
        binding.vHeroFocusAnchor.isFocusable = true
        binding.vHeroFocusAnchor.requestFocus()

        val distance = 36.dp(binding.root.context).toFloat()
        val direction = if (delta > 0) -1f else 1f

        binding.llHeroRow.animate()
            .alpha(0f)
            .translationY(direction * distance)
            .setDuration(140)
            .withEndAction {
                rowIndex = target
                showRow(requestFocus = true)
                binding.llHeroRow.translationY = -direction * distance
                binding.llHeroRow.animate()
                    .alpha(1f)
                    .translationY(0f)
                    .setDuration(180)
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
        pendingHero = Runnable { bindHero(item, row) }.also { heroHandler.postDelayed(it, 180) }
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

        binding.ivHeroBackdrop.apply {
            // Los logos de canales en vivo se muestran completos a la derecha (no recortados).
            if (live) {
                scaleType = ImageView.ScaleType.FIT_END
                setPadding(0, 40.dp(context), 80.dp(context), 260.dp(context))
            } else {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setPadding(0, 0, 0, 0)
            }
        }

        Glide.with(fragment)
            .load(url)
            .transition(DrawableTransitionOptions.withCrossFade(250))
            .into(binding.ivHeroBackdrop)
    }

    private fun android.widget.TextView.setTextOrGone(value: String?) {
        text = value.orEmpty()
        visibility = if (value.isNullOrBlank()) View.GONE else View.VISIBLE
    }
}
