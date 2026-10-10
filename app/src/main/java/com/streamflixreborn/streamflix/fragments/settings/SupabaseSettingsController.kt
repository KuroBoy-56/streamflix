package com.streamflixreborn.streamflix.fragments.settings

import androidx.fragment.app.Fragment
import androidx.lifecycle.LifecycleCoroutineScope
import androidx.preference.Preference

object SupabaseSettingsController {
    fun bind(
        fragment: Fragment,
        scope: LifecycleCoroutineScope,
        findPreference: (String) -> Preference?,
    ) {
        // Ocultamos los elementos visuales de la antigua configuración de Supabase
        findPreference("supabase_instructions")?.isVisible = false
        findPreference("supabase_copy_sql")?.isVisible = false
        findPreference("supabase_open_sql")?.isVisible = false

        findPreference("supabase_url")?.isVisible = false
        findPreference("supabase_public_key")?.isVisible = false

        // Vinculamos la gestión al controlador de cuentas del Gateway (sincronización JSON)
        CloudAccountSettingsController.bind(fragment, scope, findPreference)
    }
}