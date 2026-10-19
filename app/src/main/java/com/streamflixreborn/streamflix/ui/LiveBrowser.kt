package com.streamflixreborn.streamflix.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.fragments.live.LiveSession
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.PanelM3uProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.AssignedProviders
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient

/**
 * "En Vivo" estilo TiviMate: carpetas (categorías o países) | canales | vista previa.
 *
 * - Elegir un canal lo reproduce en la vista previa; elegirlo otra vez (o tocar la vista previa)
 *   la amplía con una animación hasta llenar la pantalla y abre el reproductor, donde se sigue
 *   cambiando de canal como antes.
 * - Pestañas "Live" (listas del panel unidas) y "FlixLive" (canales de los proveedores de la
 *   app), solo si existen las dos. Sin ninguna, la sección ni aparece en el menú.
 * - En "Live" se puede filtrar por países si las listas traen canales de 2 o más países.
 * - Celular en vertical: vista previa arriba y las dos columnas debajo.
 */
class LiveBrowser(
    private val fragment: Fragment,
    private val onRequestLoad: (Provider) -> Unit,
) {
    private val context: Context = fragment.requireContext()
    private val isTv = context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    /** TV o pantalla horizontal: tres columnas. Se recalcula al girar el teléfono. */
    private var wide = isTv || context.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    private val scope get() = fragment.viewLifecycleOwner.lifecycleScope

    // Al volver del reproductor el teléfono puede seguir en horizontal un instante: al cambiar
    // el tamaño se reacomodan las columnas (si no, la vista previa queda fuera de la pantalla).
    val view: FrameLayout = object : FrameLayout(context) {
        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val shouldBeWide = isTv || w > h
            if (w > 0 && h > 0 && shouldBeWide != wide) post { arrange(shouldBeWide) }
        }
    }
    private val folderColumn = FrameLayout(context)
    private val channelColumn = LinearLayout(context)
    private val bodyHolder = FrameLayout(context)

    private val panelProvider = AssignedProviders.panelM3u(context).firstOrNull()
    private val appProviders = AssignedProviders.appLive(context)

    private var provider: Provider? = null
    private var countries = false
    private var categories: List<Category> = emptyList()
    private var folders: List<PanelM3uProvider.Folder> = emptyList()
    private var folder: PanelM3uProvider.Folder? = null
    private var channels: List<TvShow> = emptyList()
    private var playing: TvShow? = null
    private var restoreChannelId: String? = LiveSession.browserChannelId
    private var folderJob: Job? = null
    private var channelJob: Job? = null
    private var previewJob: Job? = null
    private var expanding = false

    private val tabsRow = LinearLayout(context)
    private val chipsRow = LinearLayout(context)
    private val modeRow = LinearLayout(context)
    private val folderList = RecyclerView(context)
    private val channelList = RecyclerView(context)
    private val folderProgress = ProgressBar(context)
    private val channelTitle = TextView(context)
    private val channelMessage = TextView(context)
    private val previewBox = AspectFrame(context)
    private val playerView = LayoutInflater.from(context).inflate(R.layout.view_live_preview_player, previewBox, false) as PlayerView
    private val previewLogo = ImageView(context)
    private val previewProgress = ProgressBar(context)
    private val previewBadge = TextView(context)
    private val previewTitle = TextView(context)
    private val previewInfo = TextView(context)
    private val previewHint = TextView(context)
    // Guía de programación (solo TV): programa actual con su avance y el siguiente.
    private val epgBox = LinearLayout(context)
    private val epgNow = TextView(context)
    private val epgBar = ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
    private val epgNext = TextView(context)
    private var epgJob: Job? = null
    private var epgList: List<com.streamflixreborn.streamflix.utils.LiveEpg.Programme> = emptyList()
    private val epgTicker = object : Runnable {
        override fun run() {
            renderEpg()
            view.postDelayed(this, 60_000)
        }
    }
    private val folderAdapter = FolderAdapter()
    private val channelAdapter = ChannelAdapter()
    private val previewColumn = LinearLayout(context)

    private var player: ExoPlayer? = null
    private var httpFactory: OkHttpDataSource.Factory? = null
    private var pendingServers = mutableListOf<Video.Server>()

    init {
        view.setBackgroundColor(Color.parseColor("#0F111A"))
        view.clipChildren = false
        buildLayout()
    }

    // ---------------------------------------------------------------------------------------
    // Diseño
    // ---------------------------------------------------------------------------------------

    private fun buildLayout() {
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            setPadding(dp(if (isTv) 24 else 12), dp(if (isTv) 20 else 12), dp(if (isTv) 24 else 12), dp(if (isTv) 16 else 8))
        }
        view.addView(content, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // Barra superior: pestañas, filtro categorías/países y proveedores de FlixLive.
        val bar = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        tabsRow.orientation = LinearLayout.HORIZONTAL
        modeRow.orientation = LinearLayout.HORIZONTAL
        chipsRow.orientation = LinearLayout.HORIZONTAL
        bar.addView(tabsRow)
        bar.addView(modeRow)
        bar.addView(chipsRow)
        content.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            isFocusable = false
            addView(bar)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })

        // Carpetas
        folderList.layoutManager = LinearLayoutManager(context)
        folderList.adapter = folderAdapter
        // Sin animación de cambio: con ella la fila enfocada se reemplaza y en TV se pierde el foco.
        (folderList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        folderList.clipToPadding = false
        folderList.setPadding(0, 0, dp(6), dp(24))
        folderColumn.addView(folderList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        folderColumn.addView(folderProgress, FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER))

        // Canales
        channelColumn.orientation = LinearLayout.VERTICAL
        channelTitle.apply {
            setTextColor(Color.WHITE)
            textSize = if (isTv) 18f else 15f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(10), 0, dp(10), dp(8))
        }
        channelColumn.addView(channelTitle)
        val channelFrame = FrameLayout(context)
        channelList.layoutManager = LinearLayoutManager(context)
        channelList.adapter = channelAdapter
        (channelList.itemAnimator as? androidx.recyclerview.widget.SimpleItemAnimator)?.supportsChangeAnimations = false
        channelList.clipToPadding = false
        channelList.setPadding(0, 0, dp(6), dp(24))
        channelFrame.addView(channelList, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        channelMessage.apply {
            setTextColor(Color.parseColor("#B3FFFFFF"))
            textSize = 14f
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        channelFrame.addView(channelMessage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        channelColumn.addView(channelFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // Vista previa
        buildPreview()

        bodyHolder.clipChildren = false
        content.addView(bodyHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        arrange(wide)
    }

    /** Coloca las columnas: tres en fila (TV / horizontal) o vista previa arriba (vertical). */
    private fun arrange(threeColumns: Boolean) {
        wide = threeColumns
        listOf(folderColumn, channelColumn, previewColumn).forEach { (it.parent as? ViewGroup)?.removeView(it) }
        bodyHolder.removeAllViews()
        previewInfo.visibility = if (threeColumns) View.VISIBLE else View.GONE
        val fill = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        if (threeColumns) {
            val body = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; clipChildren = false }
            // TV: columnas un poco más angostas para que la vista previa sea más grande.
            body.addView(folderColumn, LinearLayout.LayoutParams(dp(if (isTv) 220 else 200), ViewGroup.LayoutParams.MATCH_PARENT))
            body.addView(divider(), LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(8), 0, dp(8), 0) })
            body.addView(channelColumn, LinearLayout.LayoutParams(dp(if (isTv) 310 else 280), ViewGroup.LayoutParams.MATCH_PARENT))
            body.addView(divider(), LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(8), 0, dp(if (isTv) 12 else 16), 0) })
            body.addView(previewColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
            bodyHolder.addView(body, fill)
        } else {
            val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; clipChildren = false }
            column.addView(previewColumn, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(10) })
            val row = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
            row.addView(folderColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.42f))
            row.addView(divider(), LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT).apply { setMargins(dp(6), 0, dp(6), 0) })
            row.addView(channelColumn, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 0.58f))
            column.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
            bodyHolder.addView(column, fill)
        }
    }

    private fun buildPreview() {
        previewColumn.orientation = LinearLayout.VERTICAL
        previewColumn.clipChildren = false
        previewBox.apply {
            clipChildren = false
            background = GradientDrawable().apply { setColor(Color.BLACK); cornerRadius = dp(12).toFloat() }
            clipToOutline = true
            isFocusable = true
            isClickable = true
            setOnClickListener { expand() }
            setOnFocusChangeListener { v, has -> v.animate().scaleX(if (has) 1.02f else 1f).scaleY(if (has) 1.02f else 1f).setDuration(150).start() }
        }
        playerView.alpha = 0f
        previewBox.addView(playerView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewLogo.scaleType = ImageView.ScaleType.FIT_CENTER
        previewLogo.setPadding(dp(40), dp(30), dp(40), dp(30))
        previewBox.addView(previewLogo, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        previewProgress.visibility = View.GONE
        previewBox.addView(previewProgress, FrameLayout.LayoutParams(dp(40), dp(40), Gravity.CENTER))
        previewBadge.apply {
            text = context.getString(R.string.live_now)
            setTextColor(Color.WHITE)
            textSize = 11f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), dp(3), dp(8), dp(3))
            background = GradientDrawable().apply { setColor(Color.parseColor("#E50914")); cornerRadius = dp(4).toFloat() }
            visibility = View.GONE
        }
        previewBox.addView(previewBadge, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })
        previewColumn.addView(previewBox, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        previewTitle.apply {
            setTextColor(Color.WHITE)
            textSize = if (isTv) 22f else 17f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(0, dp(12), 0, 0)
        }
        previewInfo.apply { setTextColor(Color.parseColor("#B3FFFFFF")); textSize = if (isTv) 15f else 13f; maxLines = 1 }
        previewHint.apply { setTextColor(Color.parseColor("#80FFFFFF")); textSize = if (isTv) 14f else 12f; setPadding(0, dp(6), 0, 0) }
        previewColumn.addView(previewTitle)
        previewColumn.addView(previewInfo)
        if (isTv) {
            epgBox.orientation = LinearLayout.VERTICAL
            epgBox.visibility = View.GONE
            epgBox.setPadding(0, dp(10), 0, 0)
            epgNow.apply { setTextColor(Color.WHITE); textSize = 16f; maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }
            epgBar.apply {
                max = 1000
                progressTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#E50914"))
                progressBackgroundTintList = android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))
            }
            epgNext.apply { setTextColor(Color.parseColor("#B3FFFFFF")); textSize = 14f; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }
            epgBox.addView(epgNow)
            epgBox.addView(epgBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(4)).apply { setMargins(0, dp(6), 0, dp(6)) })
            epgBox.addView(epgNext)
            previewColumn.addView(epgBox)
        }
        previewColumn.addView(previewHint)
        showPreviewIdle()
    }

    private fun divider() = View(context).apply { setBackgroundColor(Color.parseColor("#1FFFFFFF")) }

    private fun chip(text: String, selected: Boolean, onClick: () -> Unit) = TextView(context).apply {
        this.text = text
        isSelected = selected
        isFocusable = true
        isClickable = true
        gravity = Gravity.CENTER
        textSize = if (isTv) 15f else 13f
        typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        setTextColor(ContextCompat.getColorStateList(context, R.color.live_provider_chip_text))
        background = ContextCompat.getDrawable(context, R.drawable.bg_live_provider_chip)
        setPadding(dp(16), dp(8), dp(16), dp(8))
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(8) }
        setOnClickListener { onClick() }
    }

    // ---------------------------------------------------------------------------------------
    // Pestañas y proveedores
    // ---------------------------------------------------------------------------------------

    /** Arranca en lo último elegido; devuelve false si no hay canales de ningún tipo. */
    fun start(): Boolean {
        val all = listOfNotNull(panelProvider) + appProviders
        if (all.isEmpty()) return false
        val saved = LiveSession.browserProvider ?: UserPreferences.liveProviderName
        select(all.firstOrNull { it.name == saved } ?: all.first(), restoring = true)
        return true
    }

    private fun select(p: Provider, restoring: Boolean = false) {
        if (!restoring) restoreChannelId = null
        if (provider?.name != p.name) {
            categories = emptyList()
            if (!restoring || LiveSession.browserProvider != p.name) {
                LiveSession.browserFolder = null
                LiveSession.browserCountries = false
            }
        }
        provider = p
        countries = LiveSession.browserCountries && p is PanelM3uProvider
        LiveSession.select(p)
        LiveSession.browserProvider = p.name
        renderTabs()
        folders = emptyList()
        folderList.safely { folderAdapter.notifyDataSetChanged() }
        channels = emptyList()
        channelList.safely { channelAdapter.notifyDataSetChanged() }
        channelTitle.text = ""
        channelMessage.visibility = View.GONE
        folderProgress.visibility = View.VISIBLE
        onRequestLoad(p)
    }

    private fun renderTabs() {
        val p = provider ?: return
        val isPanel = p is PanelM3uProvider
        tabsRow.removeAllViews()
        // Pestañas con nombre solo si hay listas del panel y canales de la app a la vez.
        if (panelProvider != null && appProviders.isNotEmpty()) {
            tabsRow.addView(chip("Live", isPanel) { if (!isPanel) select(panelProvider) })
            tabsRow.addView(chip("FlixLive", !isPanel) {
                if (isPanel) select(appProviders.firstOrNull { it.name == UserPreferences.liveProviderName } ?: appProviders.first())
            })
            tabsRow.addView(View(context), LinearLayout.LayoutParams(dp(16), 1))
        } else {
            tabsRow.addView(TextView(context).apply {
                setText(R.string.main_menu_live)
                setTextColor(Color.WHITE)
                textSize = if (isTv) 24f else 20f
                typeface = Typeface.DEFAULT_BOLD
                setPadding(dp(4), 0, dp(20), 0)
            })
        }
        chipsRow.removeAllViews()
        if (!isPanel && appProviders.size > 1) {
            appProviders.forEach { ap -> chipsRow.addView(chip(ap.name, ap.name == p.name) { if (ap.name != provider?.name) select(ap) }) }
        }
    }

    private fun renderModes(available: Boolean) {
        modeRow.removeAllViews()
        if (!available) return
        modeRow.addView(chip(context.getString(R.string.live_mode_categories), !countries) { setCountries(false) })
        modeRow.addView(chip(context.getString(R.string.live_mode_countries), countries) { setCountries(true) })
        modeRow.addView(View(context), LinearLayout.LayoutParams(dp(16), 1))
    }

    private fun setCountries(value: Boolean) {
        if (countries == value) return
        countries = value
        LiveSession.browserCountries = value
        LiveSession.browserFolder = null
        scope.launch { refreshFolders(focus = false) }
    }

    // ---------------------------------------------------------------------------------------
    // Datos (los entrega el LiveViewModel del fragmento)
    // ---------------------------------------------------------------------------------------

    fun onCategories(p: Provider, list: List<Category>) {
        if (p.name != provider?.name) return
        val first = categories.isEmpty()
        categories = list
        folderJob?.cancel()
        folderJob = scope.launch { refreshFolders(focus = first) }
    }

    fun onFailed(p: Provider, message: String) {
        if (p.name != provider?.name || categories.isNotEmpty()) return
        folderProgress.visibility = View.GONE
        channelMessage.text = message.ifBlank { context.getString(R.string.live_empty) }
        channelMessage.visibility = View.VISIBLE
    }

    private suspend fun refreshFolders(focus: Boolean) {
        val p = provider ?: return
        val countryFolders = if (p is PanelM3uProvider) runCatching { p.countryFolders() }.getOrDefault(emptyList()) else emptyList()
        if (countryFolders.size < 2) countries = false
        renderModes(countryFolders.size >= 2)
        folders = when {
            countries -> countryFolders
            p is PanelM3uProvider -> runCatching { p.categoryFolders() }.getOrDefault(emptyList())
            else -> categories.map { PanelM3uProvider.Folder(it.name, it.list.size) }
        }
        folderProgress.visibility = View.GONE
        folderList.safely { folderAdapter.notifyDataSetChanged() }
        if (folders.isEmpty()) {
            channelMessage.text = context.getString(R.string.live_empty)
            channelMessage.visibility = View.VISIBLE
            return
        }
        val target = folders.firstOrNull { it.name == LiveSession.browserFolder } ?: folders.first()
        openFolder(target)
        val index = folders.indexOf(target)
        folderList.scrollToPosition(index)
        if (focus && isTv) folderList.post { folderList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }

    private fun openFolder(f: PanelM3uProvider.Folder) {
        val p = provider ?: return
        val old = folders.indexOf(folder)
        folder = f
        LiveSession.browserFolder = f.name
        folderList.safely {
            if (old >= 0) folderAdapter.notifyItemChanged(old)
            folderAdapter.notifyItemChanged(folders.indexOf(f))
        }
        channelTitle.text = f.name
        channelMessage.visibility = View.GONE
        channelJob?.cancel()
        channelJob = scope.launch {
            val list = runCatching {
                if (countries && p is PanelM3uProvider) p.countryShows(f.name)
                else LiveSession.channels(p, categoryOf(f))
            }.getOrDefault(emptyList())
            if (folder != f) return@launch
            channels = list
            channelList.safely { channelAdapter.notifyDataSetChanged() }
            channelList.scrollToPosition(0)
            // Animación suave al cambiar de carpeta.
            channelList.alpha = 0f
            channelList.translationX = dp(24).toFloat()
            channelList.animate().alpha(1f).translationX(0f).setDuration(220).setInterpolator(DecelerateInterpolator()).start()
            if (list.isEmpty()) {
                channelMessage.text = context.getString(R.string.live_empty)
                channelMessage.visibility = View.VISIBLE
            }
            // Al volver del reproductor se retoma el último canal en la vista previa.
            restoreChannelId?.let { id ->
                list.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.let { index ->
                    restoreChannelId = null
                    channelList.scrollToPosition(index)
                    preview(list[index])
                    if (isTv) channelList.post { channelList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
                }
            }
        }
    }

    private fun categoryOf(f: PanelM3uProvider.Folder) = categories.firstOrNull { it.name == f.name } ?: Category(name = f.name, list = emptyList())

    // ---------------------------------------------------------------------------------------
    // Vista previa
    // ---------------------------------------------------------------------------------------

    private fun onChannelClicked(show: TvShow) {
        if (playing?.id == show.id && player != null) expand() else preview(show)
    }

    private fun showPreviewIdle() {
        previewLogo.setImageDrawable(null)
        previewTitle.text = context.getString(R.string.live_preview_pick)
        previewInfo.text = ""
        previewHint.text = ""
        previewBadge.visibility = View.GONE
    }

    private fun preview(show: TvShow) {
        val p = provider ?: return
        val previous = playing
        playing = show
        LiveSession.browserChannelId = show.id
        channelList.safely {
            channels.indexOfFirst { it.id == previous?.id }.takeIf { it >= 0 }?.let(channelAdapter::notifyItemChanged)
            channels.indexOfFirst { it.id == show.id }.takeIf { it >= 0 }?.let(channelAdapter::notifyItemChanged)
        }
        previewTitle.text = show.title
        previewInfo.text = folder?.name.orEmpty()
        previewHint.text = context.getString(R.string.live_preview_hint)
        previewBadge.visibility = View.GONE
        previewProgress.visibility = View.VISIBLE
        // Cambio de canal con fundido: el logo aparece mientras carga la señal.
        if (previous?.id != show.id) {
            playerView.animate().alpha(0f).setDuration(150).start()
            previewLogo.alpha = 0f
            previewLogo.visibility = View.VISIBLE
            previewLogo.animate().alpha(1f).setDuration(200).start()
            Glide.with(previewLogo).load(show.poster).into(previewLogo)
        }
        player?.stop()
        loadEpg(show)
        previewJob?.cancel()
        previewJob = scope.launch {
            val servers = withContext(Dispatchers.IO) {
                runCatching { p.getServers(show.id, LiveSession.videoType(show)) }.getOrDefault(emptyList())
            }
            pendingServers = servers.toMutableList()
            playNextServer(show)
        }
    }

    /** TV: guía del canal (si su lista trae EPG). Se oculta si no hay. */
    private fun loadEpg(show: TvShow) {
        if (!isTv) return
        epgJob?.cancel()
        view.removeCallbacks(epgTicker)
        epgList = emptyList()
        epgBox.visibility = View.GONE
        val p = provider as? PanelM3uProvider ?: return
        epgJob = scope.launch {
            val list = runCatching { p.epgFor(show.id) }.getOrDefault(emptyList())
            if (playing?.id != show.id) return@launch
            epgList = list
            epgTicker.run()
        }
    }

    private fun renderEpg() {
        val (now, next) = com.streamflixreborn.streamflix.utils.LiveEpg.nowAndNext(epgList)
        if (now == null && next == null) {
            epgBox.visibility = View.GONE
            return
        }
        val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        if (now != null) {
            epgNow.text = "${clock.format(now.start)} – ${clock.format(now.stop)}  ${now.title}"
            val span = (now.stop - now.start).coerceAtLeast(1)
            epgBar.progress = ((System.currentTimeMillis() - now.start) * 1000 / span).toInt().coerceIn(0, 1000)
            epgBar.visibility = View.VISIBLE
            epgNow.visibility = View.VISIBLE
        } else {
            epgBar.visibility = View.GONE
            epgNow.visibility = View.GONE
        }
        epgNext.text = next?.let { "${context.getString(R.string.live_epg_next)}  ${clock.format(it.start)}  ${it.title}" }.orEmpty()
        epgNext.visibility = if (next != null) View.VISIBLE else View.GONE
        if (epgBox.visibility != View.VISIBLE) {
            epgBox.alpha = 0f
            epgBox.visibility = View.VISIBLE
            epgBox.animate().alpha(1f).setDuration(250).start()
        }
    }

    /** Prueba los servidores del canal en orden (la misma señal en otras líneas va después). */
    private suspend fun playNextServer(show: TvShow) {
        val p = provider ?: return
        while (pendingServers.isNotEmpty()) {
            val server = pendingServers.removeAt(0)
            val video = withContext(Dispatchers.IO) { runCatching { p.getVideo(server) }.getOrNull() } ?: continue
            if (video.source.isBlank() || playing?.id != show.id) continue
            play(video)
            return
        }
        if (playing?.id == show.id) {
            previewProgress.visibility = View.GONE
            previewHint.text = context.getString(R.string.live_preview_error)
        }
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun play(video: Video) {
        val exo = player ?: buildPlayer()
        httpFactory?.setDefaultRequestProperties(mapOf("User-Agent" to USER_AGENT) + (video.headers ?: emptyMap()))
        exo.setMediaItem(MediaItem.Builder().setUri(video.source.toUri()).setMimeType(video.type).build())
        exo.prepare()
        exo.playWhenReady = true
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun buildPlayer(): ExoPlayer {
        val http = OkHttpDataSource.Factory(OkHttpClient.Builder().dns(DnsResolver.doh).build())
        httpFactory = http
        val exo = ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, http)))
            .build()
        exo.addListener(object : Player.Listener {
            override fun onRenderedFirstFrame() {
                previewProgress.visibility = View.GONE
                previewBadge.visibility = View.VISIBLE
                playerView.animate().alpha(1f).setDuration(250).start()
                previewLogo.animate().alpha(0f).setDuration(250).withEndAction { previewLogo.visibility = View.INVISIBLE }.start()
            }

            override fun onPlayerError(error: PlaybackException) {
                val show = playing ?: return
                scope.launch { playNextServer(show) }
            }
        })
        playerView.player = exo
        player = exo
        return exo
    }

    /** La vista previa crece hasta llenar la pantalla y se abre el reproductor completo. */
    private fun expand() {
        val p = provider ?: return
        val show = playing ?: return
        if (expanding) return
        expanding = true
        LiveSession.select(p)
        folder?.let { LiveSession.rememberSelection(Category(name = it.name, list = channels), channels) }

        val boxLoc = IntArray(2).also(previewBox::getLocationInWindow)
        val rootLoc = IntArray(2).also(view::getLocationInWindow)
        previewBox.pivotX = 0f
        previewBox.pivotY = 0f
        previewColumn.translationZ = dp(24).toFloat()
        (previewColumn.parent as? View)?.translationZ = dp(24).toFloat()
        previewBox.animate()
            .scaleX(view.width / previewBox.width.toFloat().coerceAtLeast(1f))
            .scaleY(view.height / previewBox.height.toFloat().coerceAtLeast(1f))
            .translationX((rootLoc[0] - boxLoc[0]).toFloat())
            .translationY((rootLoc[1] - boxLoc[1]).toFloat())
            .setDuration(380)
            .setInterpolator(DecelerateInterpolator(1.6f))
            .withEndAction {
                if (!fragment.isAdded) return@withEndAction
                release()
                runCatching { fragment.findNavController().navigate(R.id.player, LiveSession.playerArgs(show)) }
            }
            .start()
        previewBadge.animate().alpha(0f).setDuration(150).start()
    }

    // ---------------------------------------------------------------------------------------
    // Ciclo de vida y teclas
    // ---------------------------------------------------------------------------------------

    fun onPause() { player?.pause() }

    fun onResume() { player?.play() }

    fun release() {
        previewJob?.cancel()
        epgJob?.cancel()
        view.removeCallbacks(epgTicker)
        playerView.player = null
        player?.release()
        player = null
    }

    /** Atrás en TV: desde canales o vista previa vuelve a las carpetas. */
    fun onBackPressed(): Boolean {
        if (folderList.hasFocus() || folders.isEmpty()) return false
        val index = folders.indexOf(folder).coerceAtLeast(0)
        folderList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() ?: folderList.requestFocus()
        return true
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    /** RecyclerView no admite cambios mientras acomoda sus filas: en ese caso se hacen justo después. */
    private fun RecyclerView.safely(block: () -> Unit) {
        if (isComputingLayout) post { if (isAttachedToWindow) block() } else block()
    }

    // ---------------------------------------------------------------------------------------
    // Listas
    // ---------------------------------------------------------------------------------------

    private fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        isFocusable = true
        isClickable = true
        background = ContextCompat.getDrawable(context, R.drawable.bg_live_row)
        setPadding(dp(12), dp(if (isTv) 11 else 10), dp(12), dp(if (isTv) 11 else 10))
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(3) }
    }

    private fun rowText(size: Float, bold: Boolean = false) = TextView(context).apply {
        textSize = size
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        setTextColor(ContextCompat.getColorStateList(context, R.color.live_row_text))
        if (bold) typeface = Typeface.DEFAULT_BOLD
        isDuplicateParentStateEnabled = true
    }

    private inner class FolderAdapter : RecyclerView.Adapter<FolderAdapter.Holder>() {
        inner class Holder(val root: LinearLayout, val name: TextView, val count: TextView) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val root = row()
            val name = rowText(if (isTv) 16f else 14f, bold = true)
            val count = rowText(if (isTv) 13f else 12f)
            root.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(count)
            return Holder(root, name, count)
        }

        override fun getItemCount() = folders.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val f = folders[position]
            holder.name.text = f.name
            holder.count.text = f.count.toString()
            holder.root.isActivated = f == folder
            holder.root.setOnClickListener { if (f != folder) openFolder(f) else focusChannels() }
            // En TV, al pasar por una carpeta se muestran sus canales (como TiviMate).
            // Fuera del acomodo de la lista (si no, RecyclerView cierra la app) y sin abrir cada
            // carpeta por la que solo se pasa rápido.
            holder.root.setOnFocusChangeListener { v, has ->
                if (has && isTv) v.postDelayed({ if (v.hasFocus() && f != folder && v.isAttachedToWindow) openFolder(f) }, 150)
            }
            holder.root.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) { focusChannels(); true } else false
            }
        }
    }

    private fun focusChannels() {
        val index = channels.indexOfFirst { it.id == playing?.id }.coerceAtLeast(0)
        channelList.scrollToPosition(index)
        channelList.post { channelList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() }
    }

    private inner class ChannelAdapter : RecyclerView.Adapter<ChannelAdapter.Holder>() {
        inner class Holder(val root: LinearLayout, val number: TextView, val logo: ImageView, val name: TextView, val live: TextView) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val root = row()
            val number = rowText(if (isTv) 13f else 12f).apply { minWidth = dp(30) }
            val logo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER; isDuplicateParentStateEnabled = true }
            val name = rowText(if (isTv) 16f else 14f)
            val live = TextView(context).apply {
                text = "●"
                setTextColor(Color.parseColor("#E50914"))
                textSize = 12f
                visibility = View.GONE
            }
            root.addView(number)
            root.addView(logo, LinearLayout.LayoutParams(dp(if (isTv) 48 else 40), dp(if (isTv) 32 else 28)).apply { marginEnd = dp(10) })
            root.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(live)
            return Holder(root, number, logo, name, live)
        }

        override fun getItemCount() = channels.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val show = channels[position]
            holder.number.text = (position + 1).toString()
            holder.name.text = show.title
            holder.root.isActivated = show.id == playing?.id
            holder.live.visibility = if (show.id == playing?.id) View.VISIBLE else View.GONE
            Glide.with(holder.logo).load(show.poster).into(holder.logo)
            holder.root.setOnClickListener { onChannelClicked(show) }
            holder.root.setOnKeyListener { _, keyCode, event ->
                if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT && playing != null) {
                    previewBox.requestFocus(); true
                } else false
            }
        }
    }

    /** Recuadro 16:9 de la vista previa. */
    private class AspectFrame(context: Context) : FrameLayout(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            val width = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(width * 9 / 16, MeasureSpec.EXACTLY))
        }
    }

    private companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    }
}
