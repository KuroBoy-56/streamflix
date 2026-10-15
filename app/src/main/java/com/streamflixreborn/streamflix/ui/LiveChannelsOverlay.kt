package com.streamflixreborn.streamflix.ui

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.fragments.live.LiveSession
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Lista de canales sobre el reproductor en vivo.
 * - TV (estilo TiviMate): categorías a la izquierda y canales al lado; ▲/▼ cambian de canal
 *   sin abrir la lista, OK o ◄ la abren.
 * - Celular: panel lateral casi transparente con los canales de la categoría actual; se muestra
 *   junto con los controles del reproductor.
 */
class LiveChannelsOverlay(
    context: Context,
    private val isTv: Boolean,
    private val provider: Provider,
    private val currentId: String,
    private val scope: LifecycleCoroutineScope,
    private val onPick: (TvShow) -> Unit,
) {
    val view: FrameLayout = FrameLayout(context)

    private val categories: List<Category> = LiveSession.categories(provider)
    private var category: Category? = LiveSession.categoryOf(provider, currentId)
    private var channels: List<TvShow> = category?.list?.filterIsInstance<TvShow>().orEmpty()
    private var loadJob: Job? = null

    private val title = TextView(context)
    private val channelList = RecyclerView(context)
    private val categoryList = RecyclerView(context)
    private val channelAdapter = ChannelAdapter()

    /** Celular: al tocar/deslizar la lista se mantienen visibles los controles. */
    var onInteraction: (() -> Unit)? = null

    /** Hay lista solo si el canal se abrió desde la sección "En Vivo". */
    val isAvailable: Boolean get() = category != null
    val isShowing: Boolean get() = view.visibility == View.VISIBLE

    init {
        view.visibility = View.GONE
        view.isClickable = false
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            isClickable = true
            background = if (isTv) {
                GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(Color.parseColor("#F20B0B0F"), Color.parseColor("#D90B0B0F")))
            } else {
                GradientDrawable().apply { setColor(Color.parseColor("#26000000")); cornerRadius = dp(12).toFloat() }
            }
        }

        if (isTv) {
            categoryList.layoutManager = LinearLayoutManager(context)
            categoryList.adapter = CategoryAdapter()
            categoryList.setPadding(0, dp(24), 0, dp(24))
            categoryList.clipToPadding = false
            panel.addView(categoryList, LinearLayout.LayoutParams(dp(230), ViewGroup.LayoutParams.MATCH_PARENT))
            panel.addView(View(context).apply { setBackgroundColor(Color.parseColor("#22FFFFFF")) },
                LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
        }

        val column = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        title.apply {
            setTextColor(Color.WHITE)
            textSize = if (isTv) 18f else 15f
            typeface = Typeface.DEFAULT_BOLD
            setShadowLayer(4f, 0f, 1f, Color.BLACK)
            setPadding(dp(16), dp(if (isTv) 24 else 12), dp(16), dp(8))
            text = category?.name ?: context.getString(R.string.live_channels_title)
        }
        column.addView(title)
        channelList.layoutManager = LinearLayoutManager(context)
        channelList.adapter = channelAdapter
        channelList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                onInteraction?.invoke()
                return false
            }
        })
        channelList.clipToPadding = false
        channelList.setPadding(0, 0, 0, dp(16))
        column.addView(channelList, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        panel.addView(column, LinearLayout.LayoutParams(dp(if (isTv) 400 else 260), ViewGroup.LayoutParams.MATCH_PARENT))

        view.addView(panel, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            if (isTv) Gravity.START else Gravity.END,
        ).apply { if (!isTv) setMargins(0, dp(56), dp(8), dp(72)) })

        // La categoría completa (las filas de "En Vivo" muestran solo los primeros canales).
        category?.let { loadChannels(it, focusCurrent = false) }
    }

    fun show() {
        if (!isAvailable) return
        view.visibility = View.VISIBLE
        val index = channels.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        channelList.scrollToPosition(index)
        if (isTv) {
            channelList.post { channelList.findViewHolderForAdapterPosition(index)?.itemView?.requestFocus() ?: channelList.requestFocus() }
        }
    }

    fun hide() {
        view.visibility = View.GONE
    }

    /** ▲/▼ en TV: canal anterior/siguiente de la misma categoría. */
    fun zap(delta: Int): Boolean {
        if (channels.size < 2) return false
        val index = channels.indexOfFirst { it.id == currentId }
        if (index < 0) return false
        onPick(channels[(index + delta + channels.size) % channels.size])
        return true
    }

    private fun loadChannels(target: Category, focusCurrent: Boolean) {
        loadJob?.cancel()
        loadJob = scope.launch {
            val list = LiveSession.channels(provider, target)
            if (category?.name != target.name) return@launch
            channels = list
            channelAdapter.notifyDataSetChanged()
            if (focusCurrent) show()
        }
    }

    private fun selectCategory(target: Category) {
        if (category?.name == target.name) return
        category = target
        title.text = target.name
        channels = target.list.filterIsInstance<TvShow>()
        channelAdapter.notifyDataSetChanged()
        channelList.scrollToPosition(0)
        loadChannels(target, focusCurrent = false)
    }

    // ---------------------------------------------------------------------------------------

    private inner class ChannelAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = channels.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val c = parent.context
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                isFocusable = true
                isClickable = true
                setPadding(dp(12), dp(if (isTv) 8 else 6), dp(12), dp(if (isTv) 8 else 6))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            row.addView(TextView(c).apply {
                setTextColor(Color.parseColor("#B3FFFFFF"))
                textSize = if (isTv) 14f else 12f
                gravity = Gravity.END
                setShadowLayer(3f, 0f, 1f, Color.BLACK)
            }, LinearLayout.LayoutParams(dp(34), ViewGroup.LayoutParams.WRAP_CONTENT))
            row.addView(ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                LinearLayout.LayoutParams(dp(if (isTv) 56 else 40), dp(if (isTv) 36 else 28)).apply { setMargins(dp(10), 0, dp(10), 0) })
            row.addView(TextView(c).apply {
                setTextColor(Color.WHITE)
                textSize = if (isTv) 16f else 14f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setShadowLayer(4f, 0f, 1f, Color.BLACK)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            return object : RecyclerView.ViewHolder(row) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val show = channels[position]
            val row = holder.itemView as LinearLayout
            val playing = show.id == currentId
            (row.getChildAt(0) as TextView).text = (position + 1).toString()
            Glide.with(row).load(show.poster).into(row.getChildAt(1) as ImageView)
            (row.getChildAt(2) as TextView).apply {
                text = show.title
                typeface = if (playing) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                setTextColor(if (playing) Color.parseColor("#FF5252") else Color.WHITE)
            }
            fun paint(focused: Boolean) {
                row.background = when {
                    focused -> rounded(Color.parseColor("#40FFFFFF"))
                    playing -> rounded(Color.parseColor("#26E50914"))
                    else -> null
                }
            }
            paint(row.hasFocus())
            row.setOnFocusChangeListener { _, f -> paint(f) }
            row.setOnClickListener { if (!playing) onPick(show) else hide() }
        }
    }

    private inner class CategoryAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = categories.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            object : RecyclerView.ViewHolder(TextView(parent.context).apply {
                setTextColor(Color.parseColor("#CCFFFFFF"))
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                isFocusable = true
                isClickable = true
                setPadding(dp(18), dp(10), dp(12), dp(10))
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }) {}

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = categories[position]
            (holder.itemView as TextView).apply {
                text = item.name
                fun paint(focused: Boolean) {
                    val selected = item.name == category?.name
                    background = if (focused) rounded(Color.parseColor("#40FFFFFF")) else null
                    setTextColor(if (selected) Color.WHITE else Color.parseColor("#99FFFFFF"))
                    typeface = if (selected) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                }
                paint(hasFocus())
                setOnFocusChangeListener { _, f ->
                    if (f) selectCategory(item)
                    paint(f)
                    // La categoría marcada antes deja de estar en negrita (sin quitarle el foco a nadie).
                    if (f) for (i in 0 until categoryList.childCount) {
                        val other = categoryList.getChildAt(i) as? TextView ?: continue
                        if (other !== this) {
                            other.typeface = Typeface.DEFAULT
                            other.setTextColor(Color.parseColor("#99FFFFFF"))
                        }
                    }
                }
                setOnClickListener { selectCategory(item); channelList.post { channelList.getChildAt(0)?.requestFocus() } }
            }
        }
    }

    private fun rounded(color: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(6).toFloat() }

    private fun dp(value: Int) = (value * view.resources.displayMetrics.density).toInt()
}
