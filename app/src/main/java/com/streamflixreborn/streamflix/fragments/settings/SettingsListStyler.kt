package com.streamflixreborn.streamflix.fragments.settings

import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceGroupAdapter
import androidx.recyclerview.widget.RecyclerView
import com.streamflixreborn.streamflix.R

internal object SettingsListStyler {
    private const val IOS_SECONDARY = 0xFF8E8E93.toInt()
    private const val IOS_SEPARATOR = 0xFF38383A.toInt()
    private const val IOS_ICON = 0xFFC7C7CC.toInt()
    private const val ROKU_IDLE = 0xFFB4B9C8.toInt()
    private const val ROKU_SUMMARY = 0xFF8A8F9E.toInt()
    private const val ROKU_SECTION = 0xFF8E8E93.toInt()


    fun attach(root: View, isTv: Boolean) {
        val recyclerView = findRecyclerView(root) ?: return
        if (recyclerView.getTag(R.id.settings_list_styler_tag) == true) return

        val backgroundColor = resolveThemeColor(root, R.attr.app_background_color, 0xFF181818.toInt())
        root.setBackgroundColor(backgroundColor)
        recyclerView.setTag(R.id.settings_list_styler_tag, true)
        recyclerView.clipToPadding = false
        recyclerView.setBackgroundColor(backgroundColor)
        recyclerView.setPadding(
            0,
            recyclerView.context.dp(if (isTv) 18 else 10),
            0,
            recyclerView.context.dp(if (isTv) 28 else 18),
        )

        recyclerView.addOnChildAttachStateChangeListener(object : RecyclerView.OnChildAttachStateChangeListener {
            override fun onChildViewAttachedToWindow(view: View) {
                styleRow(view, isTv)
            }

            override fun onChildViewDetachedFromWindow(view: View) = Unit
        })

        recyclerView.adapter?.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() = restyle()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int) = restyle()
            override fun onItemRangeChanged(positionStart: Int, itemCount: Int, payload: Any?) = restyle()
            override fun onItemRangeInserted(positionStart: Int, itemCount: Int) = restyle()
            override fun onItemRangeRemoved(positionStart: Int, itemCount: Int) = restyle()
            override fun onItemRangeMoved(fromPosition: Int, toPosition: Int, itemCount: Int) = restyle()

            private fun restyle() {
                recyclerView.post {
                    styleVisibleRows(recyclerView, isTv)
                }
            }
        })

        recyclerView.post {
            styleVisibleRows(recyclerView, isTv)
        }
    }

    private fun styleVisibleRows(recyclerView: RecyclerView, isTv: Boolean) {
        for (index in 0 until recyclerView.childCount) {
            styleRow(recyclerView.getChildAt(index), isTv)
        }
    }

    private fun styleRow(view: View, isTv: Boolean) {
        if (isTv) styleRokuRow(view) else styleGroupedRow(view)
    }

    /**
     * TV: igual que el menú de Roku. Texto gris que se vuelve blanco con un fondo blanco
     * translúcido redondeado al enfocar; sin flechas ni bordes. Las secciones, en gris pequeño.
     */
    @SuppressLint("RestrictedApi")
    private fun styleRokuRow(view: View) {
        val recyclerView = view.parent as? RecyclerView ?: return
        val adapter = recyclerView.adapter as? PreferenceGroupAdapter ?: return
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val title = view.findViewById<TextView>(android.R.id.title) ?: return
        val summary = view.findViewById<TextView>(android.R.id.summary)
        val context = view.context
        val layoutParams = view.layoutParams as? ViewGroup.MarginLayoutParams

        if (adapter.getItem(position) is PreferenceCategory) {
            layoutParams?.setMargins(0, context.dp(12), 0, context.dp(2))
            view.layoutParams = layoutParams
            view.background = null
            view.setPadding(context.dp(14), 0, context.dp(10), 0)
            title.setTextColor(ROKU_SECTION)
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            title.isAllCaps = true
            return
        }

        layoutParams?.setMargins(0, context.dp(2), 0, context.dp(2))
        view.layoutParams = layoutParams
        view.setBackgroundResource(R.drawable.bg_settings_row_tv)
        view.minimumHeight = context.dp(36)
        view.setPadding(context.dp(14), context.dp(6), context.dp(10), context.dp(6))
        view.findViewById<View>(R.id.settings_chevron)?.visibility = View.GONE
        view.findViewById<ImageView>(android.R.id.icon)?.imageTintList = ColorStateList.valueOf(ROKU_IDLE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        title.typeface = Typeface.DEFAULT
        title.isAllCaps = false
        summary?.apply {
            setTextColor(ROKU_SUMMARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            maxLines = 2
        }
        fun paint(focused: Boolean) = title.setTextColor(if (focused) Color.WHITE else ROKU_IDLE)
        paint(view.hasFocus())
        view.setOnFocusChangeListener { _, focused -> paint(focused) }
    }

    /**
     * Celular: lista agrupada como los Ajustes de iPhone. Las opciones seguidas forman un bloque
     * redondeado con separadores finos y cada sección lleva su título pequeño en gris; sin rojo.
     */
    @SuppressLint("RestrictedApi")
    private fun styleGroupedRow(view: View) {
        val recyclerView = view.parent as? RecyclerView ?: return
        val adapter = recyclerView.adapter as? PreferenceGroupAdapter ?: return
        val position = recyclerView.getChildAdapterPosition(view)
        if (position == RecyclerView.NO_POSITION) return
        val title = view.findViewById<TextView>(android.R.id.title) ?: return
        val summary = view.findViewById<TextView>(android.R.id.summary)
        val icon = view.findViewById<ImageView>(android.R.id.icon)
        val layoutParams = view.layoutParams as? ViewGroup.MarginLayoutParams
        val context = view.context

        if (adapter.getItem(position) is PreferenceCategory) {
            layoutParams?.setMargins(context.dp(20), context.dp(18), context.dp(20), context.dp(6))
            view.layoutParams = layoutParams
            view.background = null
            view.minimumHeight = 0
            view.setPadding(context.dp(12), 0, context.dp(12), 0)
            title.setTextColor(IOS_SECONDARY)
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            title.typeface = Typeface.DEFAULT
            title.isAllCaps = true
            title.letterSpacing = 0.04f
            return
        }

        fun isCell(index: Int) = index in 0 until adapter.itemCount && adapter.getItem(index) !is PreferenceCategory
        val first = !isCell(position - 1)
        val last = !isCell(position + 1)
        val surfaceColor = resolveThemeColor(view, R.attr.app_background_color, 0xFF181818.toInt())
        val cellColor = ColorUtils.blendARGB(surfaceColor, Color.WHITE, 0.08f)
        val pressedColor = ColorUtils.blendARGB(surfaceColor, Color.WHITE, 0.16f)
        val radius = context.dp(12).toFloat()
        val radii = floatArrayOf(
            if (first) radius else 0f, if (first) radius else 0f, if (first) radius else 0f, if (first) radius else 0f,
            if (last) radius else 0f, if (last) radius else 0f, if (last) radius else 0f, if (last) radius else 0f,
        )
        fun cell(color: Int): Drawable {
            val fill = GradientDrawable().apply { cornerRadii = radii; setColor(color) }
            if (last) return fill
            // Separador fino abajo: la capa de fondo asoma 1 px bajo la celda.
            val line = GradientDrawable().apply { cornerRadii = radii; setColor(IOS_SEPARATOR) }
            return LayerDrawable(arrayOf(line, fill)).apply { setLayerInset(1, 0, 0, 0, 1) }
        }

        layoutParams?.setMargins(context.dp(16), if (first) context.dp(4) else 0, context.dp(16), if (last) context.dp(4) else 0)
        view.layoutParams = layoutParams
        view.background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), cell(pressedColor))
            addState(intArrayOf(), cell(cellColor))
        }
        view.minimumHeight = context.dp(52)
        view.setPadding(context.dp(16), context.dp(12), context.dp(12), context.dp(12))

        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        title.typeface = Typeface.DEFAULT
        title.isAllCaps = false
        title.letterSpacing = 0f
        summary?.apply {
            visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
            setTextColor(IOS_SECONDARY)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxLines = 2
        }
        icon?.imageTintList = ColorStateList.valueOf(IOS_ICON)
    }



    private fun resolveThemeColor(view: View, attr: Int, fallback: Int): Int {
        val typedValue = android.util.TypedValue()
        return if (view.context.theme.resolveAttribute(attr, typedValue, true)) {
            if (typedValue.resourceId != 0) {
                androidx.core.content.ContextCompat.getColor(view.context, typedValue.resourceId)
            } else {
                typedValue.data
            }
        } else {
            fallback
        }
    }

    private fun findRecyclerView(view: View): RecyclerView? {
        if (view is RecyclerView) return view
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                findRecyclerView(view.getChildAt(index))?.let { return it }
            }
        }
        return null
    }

    private fun android.content.Context.dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
