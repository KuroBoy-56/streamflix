package com.streamflixreborn.streamflix.utils

import android.content.Context
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.profiles.ProfileManager
import com.streamflixreborn.streamflix.profiles.ProfilesActivity

/**
 * Textos del panel en Ajustes: la nota del administrador/dueño reemplaza el nombre del
 * proveedor (los proveedores no se muestran en la app) y la opción "Cambiar perfil".
 */
object PanelBranding {

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(AssignedProviders.PREFS_NAME, Context.MODE_PRIVATE)

    fun note(context: Context): String = prefs(context).getString("settings_note", "").orEmpty().trim()

    fun brand(context: Context): String? = prefs(context).getString("brand_name", "").orEmpty().trim().ifEmpty { null }

    fun bindProfilePreference(fragment: PreferenceFragmentCompat) {
        val context = fragment.requireContext()
        val pref = fragment.findPreference<Preference>("p_settings_profile") ?: return
        val visible = ProfileManager.limit(context) >= 2
        pref.isVisible = visible
        if (!visible) return
        pref.summary = ProfileManager.activeProfile(context)?.name?.let { context.getString(R.string.profiles_current, it) }
        pref.setOnPreferenceClickListener {
            ProfilesActivity.start(context)
            true
        }
    }

    /** Resumen de "Ajustes del proveedor": la nota del panel, o la marca, sin nombre de proveedor. */
    fun providerSummary(context: Context): String =
        note(context).ifEmpty {
            brand(context)?.let { context.getString(R.string.settings_screen_provider_summary_with_name, it) }
                ?: context.getString(R.string.settings_screen_provider_summary)
        }
}
