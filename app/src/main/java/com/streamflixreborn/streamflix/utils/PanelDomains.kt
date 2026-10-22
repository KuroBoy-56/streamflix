package com.streamflixreborn.streamflix.utils

import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp
import org.json.JSONObject

/**
 * Dominios de los sitios que el dueño cambia en el panel (Configuración > Dominios de
 * proveedores): si un sitio se muda, la app usa el nuevo sin actualizarse. Lo mismo en Roku e iPhone.
 */
object PanelDomains {
    private const val PREFS = "SecureGatewayPrefs"

    fun get(key: String, default: String): String =
        StreamFlixApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("domain_$key", null)?.takeIf { it.isNotBlank() } ?: default

    /** [domains] es "provider_domains" del panel; null (panel antiguo) no cambia nada. */
    fun save(context: Context, domains: JSONObject?) {
        domains ?: return
        val editor = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        listOf("cuevana3", "poseidonhd2", "cinecalidad").forEach { key ->
            val host = domains.optString(key).trim()
            if (host.isEmpty()) editor.remove("domain_$key") else editor.putString("domain_$key", host)
        }
        editor.apply()
        // Cuevana y Poseidon ya tenían su dominio en las preferencias (al cambiar se limpia su caché).
        UserPreferences.cuevanaDomain = domains.optString("cuevana3").trim()
        UserPreferences.poseidonDomain = domains.optString("poseidonhd2").trim()
    }
}
