package com.streamflixreborn.streamflix.utils

import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.PanelChannelsProvider
import com.streamflixreborn.streamflix.providers.PanelM3uProvider
import com.streamflixreborn.streamflix.providers.PersonalProvider
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

    /** Clave de comparación: minúsculas, solo letras y números ("PlutoTv Mx" == "Pluto TV MX"). */
    private fun normalize(value: String): String =
        value.lowercase().replace(Regex("[^a-z0-9]"), "")

    /**
     * Nombres con los que el panel puede referirse a un proveedor: su nombre visible y el nombre
     * de su clase (p. ej. "TvporinternetHD" -> TvPorInternet2, "CineCity" -> MAGISTV,
     * "Vavoo de" -> Vavoo DE Live TV).
     */
    private fun matchKeys(provider: Provider): Set<String> {
        val className = provider.javaClass.simpleName.removeSuffix("Provider")
        return setOf(
            normalize(provider.name),
            normalize(className),
            normalize(className + provider.language),
        ).filter { it.isNotEmpty() }.toSet()
    }

    /** Resuelve nombres del panel a proveedores, en el orden del panel. */
    fun resolve(names: List<String>): List<Provider> {
        val available = Provider.providers.keys.toList()
        val keysByProvider = available.associateWith(::matchKeys)
        return names
            .flatMap { name ->
                val key = normalize(name)
                val exact = available.filter { it.name.equals(name, ignoreCase = true) }
                exact.ifEmpty { available.filter { key in keysByProvider.getValue(it) } }
            }
            .distinctBy { it.name }
    }

    fun hasPanelAssignment(context: Context? = null): Boolean =
        names(context).isNotEmpty() || panelM3u(context).isNotEmpty()

    /** "Canales" (líneas del panel) y "Personal Provider" (URL del usuario), si tienen URL. */
    fun panelM3u(context: Context? = null): List<Provider> =
        listOf(PanelChannelsProvider, PersonalProvider).filter { it.isConfigured(context) }

    fun all(context: Context? = null): List<Provider> {
        val names = names(context)
        val base = if (names.isEmpty() && panelM3u(context).isEmpty()) {
            Provider.providers.keys.toList()
        } else {
            resolve(names)
        }
        return base.filterNot { it is PanelM3uProvider } + panelM3u(context)
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

    /**
     * Ya no se elige servidor al entrar: con panel, la app entra directo con el proveedor por
     * defecto y los demás permitidos quedan en el reproductor (Ajustes > Proveedores).
     */
    @Suppress("UNUSED_PARAMETER")
    fun canSwitchHomeProvider(context: Context? = null): Boolean = false

    const val KEY_DEFAULT_PROVIDER = "default_provider"

    /** Proveedor por defecto elegido en el panel al crear el usuario. */
    fun defaultProvider(context: Context? = null): Provider? {
        val home = homeProviders(context)
        val name = context(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT_PROVIDER, "").orEmpty()
        return resolve(listOf(name)).firstOrNull { p -> home.any { it.name == p.name } }
            ?: home.firstOrNull()
    }

    /** Proveedor en vivo activo para la sección "En Vivo". */
    fun currentLiveProvider(context: Context? = null): Provider? {
        val live = live(context)
        val saved = UserPreferences.liveProviderName
        return live.find { it.name == saved } ?: live.firstOrNull()
    }

    /** El inicio siempre usa el proveedor por defecto del panel (o el primero permitido). */
    fun normalizeCurrentProvider(context: Context? = null) {
        if (!hasPanelAssignment(context)) return
        val target = defaultProvider(context) ?: return
        if (UserPreferences.storedProvider?.name != target.name) {
            UserPreferences.currentProvider = target
        }
    }
}
