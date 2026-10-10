package com.streamflixreborn.streamflix.fragments.live

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.AssignedProviders
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp

/**
 * Sesión de la sección "En Vivo": mientras el usuario navega por ella (canal, temporada,
 * reproductor) el proveedor activo es el de canales en vivo; al volver a cualquier otra
 * sección principal se restaura el proveedor del inicio.
 */
object LiveSession {

    fun enter(context: Context): Provider? {
        val provider = AssignedProviders.currentLiveProvider(context) ?: return null
        UserPreferences.sessionProviderOverride = provider
        return provider
    }

    fun select(provider: Provider) {
        UserPreferences.liveProviderName = provider.name
        UserPreferences.sessionProviderOverride = provider
    }

    fun exit() {
        UserPreferences.sessionProviderOverride = null
    }

    /** Crea los chips del selector de proveedores en vivo (si hay más de uno). */
    fun bindProviderChips(
        container: LinearLayout,
        providers: List<Provider>,
        selected: Provider?,
        onSelected: (Provider) -> Unit,
    ) {
        container.removeAllViews()
        val context = container.context
        providers.forEach { provider ->
            container.addView(TextView(context).apply {
                text = provider.name
                isSelected = provider.name == selected?.name
                isFocusable = true
                isClickable = true
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(ContextCompat.getColorStateList(context, R.color.live_provider_chip_text))
                background = ContextCompat.getDrawable(context, R.drawable.bg_live_provider_chip)
                setPadding(16.dp(context), 8.dp(context), 16.dp(context), 8.dp(context))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = 8.dp(context) }
                setOnClickListener {
                    for (i in 0 until container.childCount) container.getChildAt(i).isSelected = false
                    isSelected = true
                    onSelected(provider)
                }
            })
        }
    }
}
