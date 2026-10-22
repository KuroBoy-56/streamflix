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
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences

internal object SettingsListStyler {
    private const val IOS_SECONDARY = 0xFF8E8E93.toInt()
    private const val IOS_SEPARATOR = 0xFF38383A.toInt()
    private const val IOS_ICON = 0xFFC7C7CC.toInt()

    private data class DefaultRowStyle(
        val background: Drawable?,
        val minHeight: Int,
        val paddingLeft: Int,
        val paddingTop: Int,
        val paddingRight: Int,
        val paddingBottom: Int,
        val marginLeft: Int,
        val marginTop: Int,
        val marginRight: Int,
        val marginBottom: Int,
        val titleColor: Int,
        val titleSizePx: Float,
        val summaryColor: Int?,
        val summarySizePx: Float?,
    )

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
        if (!isTv) return styleGroupedRow(view)
        val title = view.findViewById<TextView>(android.R.id.title) ?: return
        val summary = view.findViewById<TextView>(android.R.id.summary)
        val icon = view.findViewById<ImageView>(android.R.id.icon)
        val layoutParams = view.layoutParams as? ViewGroup.MarginLayoutParams
        val context = view.context
        val defaults = (view.getTag(R.id.settings_row_defaults) as? DefaultRowStyle) ?: DefaultRowStyle(
            background = view.background,
            minHeight = view.minimumHeight,
            paddingLeft = view.paddingLeft,
            paddingTop = view.paddingTop,
            paddingRight = view.paddingRight,
            paddingBottom = view.paddingBottom,
            marginLeft = layoutParams?.leftMargin ?: 0,
            marginTop = layoutParams?.topMargin ?: 0,
            marginRight = layoutParams?.rightMargin ?: 0,
            marginBottom = layoutParams?.bottomMargin ?: 0,
            titleColor = title.currentTextColor,
            titleSizePx = title.textSize,
            summaryColor = summary?.currentTextColor,
            summarySizePx = summary?.textSize,
        ).also {
            view.setTag(R.id.settings_row_defaults, it)
        }
        val titleText = title.text?.toString().orEmpty()
        val hasChevron = view.findViewById<View>(R.id.settings_chevron) != null
        if (!hasChevron) {
            layoutParams?.setMargins(
                defaults.marginLeft,
                defaults.marginTop,
                defaults.marginRight,
                defaults.marginBottom,
            )
            view.layoutParams = layoutParams
            view.background = defaults.background
            view.minimumHeight = defaults.minHeight
            view.setPadding(
                defaults.paddingLeft,
                defaults.paddingTop,
                defaults.paddingRight,
                defaults.paddingBottom,
            )

            title.setTextColor(defaults.titleColor)
            title.setTextSize(TypedValue.COMPLEX_UNIT_PX, defaults.titleSizePx)
            title.typeface = Typeface.DEFAULT
            title.letterSpacing = 0f

            summary?.apply {
                visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
                defaults.summaryColor?.let(::setTextColor)
                defaults.summarySizePx?.let { setTextSize(TypedValue.COMPLEX_UNIT_PX, it) }
            }
            icon?.imageTintList = null
            return
        }

        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        val surfaceColor = resolveThemeColor(view, R.attr.app_background_color, 0xFF181818.toInt())
        val titleColor = palette.tvHeaderPrimary
        val summaryColor = palette.tvHeaderSecondary
        val accentColor = palette.mobileNavActive
        val rowBackgroundColor = ColorUtils.blendARGB(surfaceColor, titleColor, if (isTv) 0.09f else 0.07f)
        val rowBorderColor = ColorUtils.blendARGB(surfaceColor, summaryColor, 0.42f)
        val rowHighlightColor = ColorUtils.blendARGB(surfaceColor, accentColor, if (isTv) 0.22f else 0.18f)
        val rowHighlightBorderColor = ColorUtils.blendARGB(surfaceColor, accentColor, 0.62f)

        layoutParams?.setMargins(
            context.dp(if (isTv) 28 else 16),
            context.dp(if (isTv) 8 else 6),
            context.dp(if (isTv) 28 else 16),
            context.dp(if (isTv) 8 else 6),
        )
        view.layoutParams = layoutParams
        view.background = createRowBackground(
            view = view,
            isTv = isTv,
            defaultColor = rowBackgroundColor,
            defaultStrokeColor = rowBorderColor,
            activeColor = rowHighlightColor,
            activeStrokeColor = rowHighlightBorderColor,
        )
        view.minimumHeight = context.dp(if (isTv) 88 else 72)
        view.setPadding(
            context.dp(if (isTv) 28 else 20),
            context.dp(if (isTv) 18 else 16),
            context.dp(if (isTv) 28 else 20),
            context.dp(if (isTv) 18 else 16),
        )

        title.setTextColor(titleColor)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isTv) 20f else 16f)
        title.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        title.letterSpacing = 0f

        summary?.apply {
            setTextColor(summaryColor)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (isTv) 14f else 13f)
            maxLines = 2
        }

        icon?.drawable?.let {
            icon.imageTintList = ColorStateList.valueOf(accentColor)
        }
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

    private fun createRowBackground(
        view: View,
        isTv: Boolean,
        defaultColor: Int,
        defaultStrokeColor: Int,
        activeColor: Int,
        activeStrokeColor: Int,
    ): Drawable {
        val radiusDp = if (isTv) 22 else 18
        val defaultStrokeDp = 1
        val activeStrokeDp = if (isTv) 2 else 1

        return StateListDrawable().apply {
            if (isTv) {
                addState(
                    intArrayOf(android.R.attr.state_focused),
                    createRoundedRect(view, activeColor, activeStrokeColor, radiusDp, activeStrokeDp)
                )
                addState(
                    intArrayOf(android.R.attr.state_selected),
                    createRoundedRect(view, activeColor, activeStrokeColor, radiusDp, activeStrokeDp)
                )
            } else {
                addState(
                    intArrayOf(android.R.attr.state_pressed),
                    createRoundedRect(view, activeColor, activeStrokeColor, radiusDp, activeStrokeDp)
                )
            }
            addState(
                intArrayOf(),
                createRoundedRect(view, defaultColor, defaultStrokeColor, radiusDp, defaultStrokeDp)
            )
        }
    }

    private fun createRoundedRect(
        view: View,
        fillColor: Int,
        strokeColor: Int,
        radiusDp: Int,
        strokeDp: Int,
    ): Drawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = view.context.dp(radiusDp).toFloat()
        setColor(fillColor)
        setStroke(view.context.dp(strokeDp), strokeColor)
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
