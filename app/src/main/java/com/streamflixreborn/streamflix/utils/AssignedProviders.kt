package com.streamflixreborn.streamflix.utils

import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider

/**
 * Fuente única de verdad para los proveedores asignados desde el panel web
 * (SharedPreferences "SecureGatewayPrefs" -> "assigned_servers").
 *
 * - [all]: proveedores asignados (si el panel no envió ninguno, todos los de la app).
 * - [vod]: proveedores de películas/series (inicio).
 * - [live]: proveedores con canales en vivo (sección "En Vivo").
 *
 * Cuando hay proveedores de ambos tipos, los canales en vivo se muestran solo en su
 * propia sección y no se mezclan con el inicio.
 */
object AssignedProviders {

    const val PREFS_NAME = "SecureGatewayPrefs"
    const val KEY_ASSIGNED_SERVERS = "assigned_servers"

    private fun context(context: Context? = null): Context =
        (context ?: StreamFlixApp.instance).applicationContext

    fun names(context: Context? = null): List<String> =
        context(context)
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ASSIGNED_SERVERS, "")
            .orEmpty()
            .split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    fun save(context: Context, names: List<String>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ASSIGNED_SERVERS, names.joinToString(","))
            .commit()
    }

    /** Resuelve nombres del panel a proveedores (sin distinguir mayúsculas), en el orden del panel. */
    fun resolve(names: List<String>): List<Provider> {
        val available = Provider.providers.keys.toList()
        return names
            .mapNotNull { name -> available.find { it.name.equals(name, ignoreCase = true) } }
            .distinctBy { it.name }
    }

    fun hasPanelAssignment(context: Context? = null): Boolean = names(context).isNotEmpty()

    fun all(context: Context? = null): List<Provider> {
        val names = names(context)
        return if (names.isEmpty()) Provider.providers.keys.toList() else resolve(names)
    }

    fun vod(context: Context? = null): List<Provider> = all(context).filterNot { it is IptvProvider }

    fun live(context: Context? = null): List<Provider> = all(context).filter { it is IptvProvider }

    /** La sección "En Vivo" solo aparece si el panel asignó canales en vivo y también contenido VOD. */
    fun hasLiveSection(context: Context? = null): Boolean {
        if (!hasPanelAssignment(context)) return false
        val all = all(context)
        return all.any { it is IptvProvider } && all.any { it !is IptvProvider }
    }

    /** Proveedores elegibles para el inicio (los de canales en vivo quedan en su sección). */
    fun homeProviders(context: Context? = null): List<Provider> =
        if (hasLiveSection(context)) vod(context) else all(context)

    /** Si hay más de un proveedor para el inicio, se puede abrir el selector. */
    fun canSwitchHomeProvider(context: Context? = null): Boolean =
        hasPanelAssignment(context) && homeProviders(context).size > 1

    /** Proveedor en vivo activo para la sección "En Vivo". */
    fun currentLiveProvider(context: Context? = null): Provider? {
        val live = live(context)
        val saved = UserPreferences.liveProviderName
        return live.find { it.name == saved } ?: live.firstOrNull()
    }

    /**
     * Asegura que el proveedor del inicio sea válido según el panel:
     * - Si solo hay uno, se asigna directamente.
     * - Si el actual ya no está asignado (o es de canales en vivo con sección propia), se limpia/corrige.
     */
    fun normalizeCurrentProvider(context: Context? = null) {
        if (!hasPanelAssignment(context)) return
        val homeProviders = homeProviders(context)
        val current = UserPreferences.storedProvider
        when {
            homeProviders.size == 1 && current != homeProviders.first() ->
                UserPreferences.currentProvider = homeProviders.first()
            current != null && homeProviders.none { it.name == current.name } ->
                UserPreferences.currentProvider = if (hasLiveSection(context)) homeProviders.firstOrNull() else null
        }
    }
}
