package com.streamflixreborn.streamflix.utils

import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import org.json.JSONObject

/**
 * Dominios de los sitios que el dueño cambia en el panel (Configuración > Dominios de
 * proveedores): si un sitio se muda, la app usa el nuevo sin actualizarse. Sin dominio en el
 * panel se usa el que trae la app ([DEFAULTS]). Roku e iPhone hacen lo mismo con sus sitios.
 *
 * Todas las conexiones de los proveedores pasan por [interceptor]: una petición al dominio de
 * la app se envía al dominio del panel (misma ruta), con Referer/Origin corregidos.
 */
object PanelDomains {
    private const val PREFS = "SecureGatewayPrefs"
    private const val KEY = "panel_provider_domains"

    /** Clave del panel (nombre sin espacios ni símbolos, en minúsculas) -> dominio de la app. */
    val DEFAULTS: Map<String, String> = mapOf(
        "sflix" to "sflix.to", "fanpelis" to "fanpelis.to", "serienstream" to "s.to",
        "streamingcommunity" to "streamingunity.cc", "animeworld" to "www.animeworld.ac", "mkissa" to "mkissa.to",
        "aniworld" to "aniworld.to", "ridomovies" to "ridomovies.su", "anikoto" to "anikototv.to",
        "wiflix" to "flemmix.team", "mstream" to "moflix-stream.xyz", "frenchanime" to "french-anime.com",
        "filmpalast" to "filmpalast.to", "poseidonhd2" to "www.poseidonhd2.co", "cuevana3" to "wv3.cuevana3.eu",
        "latanime" to "latanime.org", "doramasflix" to "doramasflix.in", "cinecalidad" to "www.cinecalidad.am",
        "seriesflix" to "seriesflixhd.pw", "seriesturcas" to "kaj.seriesturcastv.to", "flixlatam" to "flixlatam.com",
        "lacartoons" to "www.lacartoons.com", "animefenix" to "animefenix2.tv", "animeflv" to "www3.animeflv.net",
        "jkanime" to "jkanime.net", "tioanime" to "tioanime.com", "animeav1" to "animeav1.com",
        "animeonlineninja" to "ver.animeonline.ninja", "sololatino" to "sololatino.net", "cine24h" to "cine24h.online",
        "pelisplusto" to "pelisplus.to", "pelisflixhd" to "pelisflixhd1.online", "cablevisionhd" to "www.cablevisionhd.com",
        "altadefinizione01" to "altadefinizione-01.autos", "guardaflix" to "guardaplay.store", "cb01" to "cb01uno.wiki",
        "animeunity" to "www.animeunity.so", "animesaturn" to "www.animesaturn.net", "frenchstream" to "fs16.lol",
        "guardaserie" to "guardoserie.study", "einschalten" to "einschalten.in", "hdfilme" to "hdfilme.ceo",
        "megakino" to "megakino23.com", "filmyonlinecc" to "filmyonline.cc", "zaluknij" to "zaluknij.cc",
        "tvporinternethd" to "www.tvporinternet.org", "frembed" to "frembed.bar", "kidraz" to "www.kidraz.com",
        "frenchmanga" to "w16.french-manga.net", "tvlibrefutbol" to "www.librefutbol2.com", "cinehax" to "cinehax.com",
        "vavoo" to "vavoo.to",
    )

    private fun bare(host: String) = host.lowercase().removePrefix("www.")

    /** Dominio de la app (sin "www.") -> dominio del panel. Se rehace al guardar. */
    @Volatile
    private var rewrites: Map<String, String>? = null

    private fun panelDomains(context: Context): Map<String, String> {
        val json = runCatching { JSONObject(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "{}")!!) }
            .getOrDefault(JSONObject())
        return json.keys().asSequence().associateWith { json.optString(it).trim() }.filterValues { it.isNotEmpty() }
    }

    /** Dominios del panel por clave -> qué dominio de la app se cambia por cuál. */
    internal fun buildRewrites(panel: Map<String, String>): Map<String, String> = panel
        .mapNotNull { (key, host) -> DEFAULTS[key]?.let { bare(it) to host } }
        .filter { (old, new) -> old != bare(new) }
        .toMap()

    /** Dominio al que se envía una petición a [host], o null si no cambia. */
    internal fun newHostFor(host: String, rewrites: Map<String, String>): String? = rewrites[bare(host)]

    private fun rewrites(): Map<String, String> =
        rewrites ?: buildRewrites(panelDomains(StreamFlixApp.instance)).also { rewrites = it }

    fun get(key: String, default: String): String =
        panelDomains(StreamFlixApp.instance)[key] ?: default

    val interceptor = Interceptor { chain ->
        val request = chain.request()
        val oldHost = request.url.host
        val newHost = runCatching { newHostFor(oldHost, rewrites()) }.getOrNull()
        if (newHost == null) return@Interceptor chain.proceed(request)
        val builder = request.newBuilder().url(request.url.newBuilder().host(newHost).build())
        listOf("Referer", "Origin").forEach { header ->
            request.header(header)?.let { builder.header(header, it.replace(oldHost, newHost)) }
        }
        chain.proceed(builder.build())
    }

    /** OkHttpClient.Builder de los proveedores: ya trae el cambio de dominio del panel. */
    fun newBuilder(): OkHttpClient.Builder = OkHttpClient.Builder().addInterceptor(interceptor)

    /** [domains] es "provider_domains" del panel; null (panel antiguo) no cambia nada. */
    fun save(context: Context, domains: JSONObject?) {
        domains ?: return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, domains.toString()).apply()
        rewrites = null
        // Cuevana y Poseidon ya tenían su dominio en las preferencias (al cambiar se limpia su caché).
        UserPreferences.cuevanaDomain = domains.optString("cuevana3").trim()
        UserPreferences.poseidonDomain = domains.optString("poseidonhd2").trim()
    }
}
