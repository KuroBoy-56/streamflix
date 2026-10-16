package com.streamflixreborn.streamflix.utils

import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.PanelLiveProvider
import com.streamflixreborn.streamflix.providers.PanelM3uProvider
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

    /** "Live": líneas de canales del panel y URL personal del usuario, si hay alguna URL. */
    fun panelM3u(context: Context? = null): List<Provider> =
        listOf(PanelLiveProvider).filter { it.isConfigured(context) }

    /** Canales de los proveedores de la app (sección "FlixLive"). */
    fun appLive(context: Context? = null): List<Provider> = live(context).filterNot { it is PanelM3uProvider }

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

    /**
     * Proveedor por defecto elegido en el panel al crear el usuario. Si está caído (no cargó el
     * inicio hace poco), el siguiente permitido que funcione.
     */
    fun defaultProvider(context: Context? = null): Provider? {
        val home = homeProviders(context)
        val name = context(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_DEFAULT_PROVIDER, "").orEmpty()
        val preferred = resolve(listOf(name)).firstOrNull { p -> home.any { it.name == p.name } }
            ?: home.firstOrNull()
        if (preferred == null || !isDown(preferred.name, context)) return preferred
        return home.firstOrNull { !isDown(it.name, context) } ?: preferred
    }

    // ---------------------------------------------------------------------------------------
    // Proveedores caídos
    // ---------------------------------------------------------------------------------------

    private const val KEY_DOWN = "providers_down"
    /** Tras este tiempo se vuelve a probar el proveedor por defecto. */
    private const val DOWN_TTL_MS = 3 * 60 * 60 * 1000L

    private fun downMap(context: Context?): org.json.JSONObject = runCatching {
        org.json.JSONObject(context(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(KEY_DOWN, "{}").orEmpty())
    }.getOrDefault(org.json.JSONObject())

    fun isDown(name: String, context: Context? = null): Boolean =
        System.currentTimeMillis() - downMap(context).optLong(name, 0L) < DOWN_TTL_MS

    private fun setDown(name: String, down: Boolean, context: Context?) {
        val map = downMap(context)
        if (down) map.put(name, System.currentTimeMillis()) else map.remove(name)
        context(context).getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().putString(KEY_DOWN, map.toString()).apply()
    }

    /** Nombre con el que el panel conoce al proveedor (para marcarlo en rojo allí). */
    fun panelName(provider: Provider, context: Context? = null): String =
        names(context).firstOrNull { n -> resolve(listOf(n)).any { it.name == provider.name } } ?: provider.name

    /**
     * El proveedor del inicio no cargó (inicio, películas o series): se reporta al panel, se
     * marca caído por unas horas y el inicio pasa al siguiente proveedor permitido.
     * Devuelve true si se cambió de proveedor (la pantalla se recarga sola).
     */
    fun onHomeProviderFailed(provider: Provider, where: String, error: Throwable?, context: Context? = null): Boolean {
        if (provider is IptvProvider || UserPreferences.sessionProviderOverride != null) return false
        if (UserPreferences.currentProvider?.name != provider.name) return false
        val panel = panelName(provider, context)
        ErrorReporter.report("Proveedor.Caido", "$panel no carga ($where): ${error?.message.orEmpty()}".take(500), error, provider = panel)
        setDown(provider.name, true, context)
        val next = homeProviders(context).firstOrNull { it.name != provider.name && !isDown(it.name, context) } ?: return false
        UserPreferences.currentProvider = next
        return true
    }

    /** El proveedor volvió a cargar: deja de estar en rojo en el panel. */
    fun onHomeProviderLoaded(provider: Provider, context: Context? = null) {
        if (provider is IptvProvider || !downMap(context).has(provider.name)) return
        setDown(provider.name, false, context)
        ErrorReporter.report("Proveedor.OK", "${panelName(provider, context)} volvió a cargar", provider = panelName(provider, context))
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
