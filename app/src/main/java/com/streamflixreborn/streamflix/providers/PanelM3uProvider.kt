package com.streamflixreborn.streamflix.providers

import android.content.Context
import android.util.Base64
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.utils.AssignedProviders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Canales en vivo desde listas M3U configuradas en el panel.
 * - [PanelChannelsProvider]: "líneas de canales" del distribuidor (Ajustes del panel).
 * - [PersonalProvider]: URL personal asignada a cada usuario al crearlo.
 *
 * Las listas se filtran: se quitan entradas sin URL válida, duplicados, canales para adultos
 * y grupos vacíos; luego se agrupan por categoría (group-title).
 */
abstract class PanelM3uProvider(
    override val name: String,
    private val prefsKey: String,
) : IptvProvider {

    override val baseUrl = ""
    override val logo = ""
    override val language = "es"

    data class Channel(
        val name: String,
        val url: String,
        val logo: String,
        val group: String,
        val userAgent: String?,
        val referer: String?,
    )

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var cache: List<Channel>? = null
    /** Mismo canal en otras líneas/listas (URLs distintas): respaldo si una no funciona. */
    @Volatile private var alternates: Map<String, List<Channel>> = emptyMap()
    @Volatile private var cacheKey: String = ""
    @Volatile private var cacheTime = 0L

    /** URLs de listas configuradas (una por línea en el panel). */
    fun sources(context: Context? = null): List<String> {
        val ctx = context ?: runCatching { StreamFlixApp.instance }.getOrNull() ?: return emptyList()
        return ctx.getSharedPreferences(AssignedProviders.PREFS_NAME, Context.MODE_PRIVATE)
            .getString(prefsKey, "").orEmpty()
            .lines()
            .map { it.trim() }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .distinct()
    }

    fun isConfigured(context: Context? = null) = sources(context).isNotEmpty()

    private suspend fun channels(): List<Channel> {
        val urls = sources()
        val key = urls.joinToString("\n")
        val now = System.currentTimeMillis()
        cache?.let { if (key == cacheKey && now - cacheTime < CACHE_MS) return it }

        val loaded = coroutineScope {
            urls.map { url -> async(Dispatchers.IO) { fetch(url) } }.awaitAll().flatten()
        }
        val filtered = filter(loaded)
        if (filtered.isNotEmpty() || cache == null || key != cacheKey) {
            alternates = loaded
                .filter { it.url.startsWith("http", true) && it.name.isNotBlank() }
                .filterNot { ch -> ADULT.containsMatchIn(normalize(ch.name)) || ADULT.containsMatchIn(normalize(ch.group)) }
                .distinctBy { it.url }
                .groupBy { channelKey(it.name) }
                .filter { (key, list) -> key.isNotEmpty() && list.size > 1 }
            cache = filtered
            cacheKey = key
            cacheTime = now
        }
        return cache.orEmpty()
    }

    private suspend fun fetch(url: String): List<Channel> = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use emptyList()
                val body = response.body?.string().orEmpty()
                if (body.contains("#EXTINF")) parseM3u(body) else parsePlain(body)
            }
        }.onFailure { Log.w(TAG, "$name: $url -> ${it.message}") }.getOrDefault(emptyList())
    }

    private fun parseM3u(raw: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name = ""; var logo = ""; var group = ""; var ua: String? = null; var referer: String? = null
        for (line in raw.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("#EXTINF", true) -> {
                    name = t.substringAfterLast(",").trim()
                    logo = ATTR_LOGO.find(t)?.groupValues?.get(1).orEmpty()
                    group = ATTR_GROUP.find(t)?.groupValues?.get(1).orEmpty()
                    ua = ATTR_UA.find(t)?.groupValues?.get(1)
                    referer = null
                }
                t.startsWith("#EXTGRP:", true) -> if (group.isBlank()) group = t.substringAfter(":").trim()
                t.startsWith("#EXTVLCOPT:http-user-agent=", true) -> ua = t.substringAfter("=").trim()
                t.startsWith("#EXTVLCOPT:http-referrer=", true) -> referer = t.substringAfter("=").trim()
                t.startsWith("http", true) -> {
                    if (name.isNotBlank()) out += Channel(name, t, logo, group, ua, referer)
                    name = ""; logo = ""; group = ""; ua = null; referer = null
                }
            }
        }
        return out
    }

    /** Formato simple "Nombre,URL" o solo URL por línea. */
    private fun parsePlain(raw: String): List<Channel> = raw.lines().mapNotNull { line ->
        val t = line.trim()
        val url = Regex("https?://\\S+").find(t)?.value ?: return@mapNotNull null
        val title = t.substringBefore(url).trim().trimEnd(',', '|', ';').trim()
            .ifBlank { url.substringAfterLast('/').substringBefore('?').substringBeforeLast('.') }
        Channel(title, url, "", "", null, null)
    }

    private fun filter(list: List<Channel>): List<Channel> = list
        .filter { it.url.startsWith("http", true) && it.name.isNotBlank() }
        .filterNot { ch -> ADULT.containsMatchIn(normalize(ch.name)) || ADULT.containsMatchIn(normalize(ch.group)) }
        .map { it.copy(name = it.name.replace(Regex("\\s+"), " ").trim(), group = cleanGroup(it.group)) }
        .distinctBy { it.url }
        .distinctBy { normalize(it.name) + "|" + normalize(it.group) }
        .let { channels ->
            // Los grupos con muy pocos canales se juntan en "Otros" para no llenar de filas.
            val small = channels.groupingBy { it.group }.eachCount().filterValues { it < MIN_GROUP }.keys
            channels.map { if (it.group in small) it.copy(group = OTHERS) else it }
        }

    /** "Animation;Kids" -> "Animación"; "Undefined"/vacío -> "Otros". */
    private fun cleanGroup(raw: String): String {
        val first = raw.split(';', '|').map { it.trim() }.firstOrNull { it.isNotBlank() } ?: return OTHERS
        return GROUP_NAMES[first.lowercase()] ?: first.replaceFirstChar { it.titlecase() }
    }

    private fun encode(ch: Channel): String = Base64.encodeToString(
        listOf(ch.url, ch.name, ch.logo, ch.userAgent.orEmpty(), ch.referer.orEmpty()).joinToString(SEP).toByteArray(),
        Base64.NO_WRAP or Base64.URL_SAFE,
    )

    private fun decode(id: String): Channel? = runCatching {
        val parts = String(Base64.decode(id, Base64.NO_WRAP or Base64.URL_SAFE)).split(SEP)
        Channel(
            name = parts.getOrElse(1) { "" },
            url = parts[0],
            logo = parts.getOrElse(2) { "" },
            group = "",
            userAgent = parts.getOrNull(3)?.ifBlank { null },
            referer = parts.getOrNull(4)?.ifBlank { null },
        )
    }.getOrNull()?.takeIf { it.url.startsWith("http", true) }

    private fun Channel.toShow() = TvShow(id = encode(this), title = name, poster = logo, banner = logo)

    private fun groups(channels: List<Channel>): List<Pair<String, List<Channel>>> = channels
        .groupBy { it.group }
        .toList()
        .sortedWith(compareBy<Pair<String, List<Channel>>> { it.first == OTHERS }.thenByDescending { it.second.size })

    override suspend fun getHome(): List<Category> = groups(channels()).map { (group, list) ->
        Category(name = group, list = list.take(HOME_ROW).map { it.toShow() })
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (page > 1) return emptyList()
        val all = channels()
        if (query.isBlank()) return groups(all).map { (group, _) -> Genre(id = group, name = group) }
        val q = normalize(query)
        return all.filter { normalize(it.name).contains(q) }.take(100).map { it.toShow() }
    }

    /** Todos los canales de una categoría (para cambiar de canal desde el reproductor). */
    suspend fun groupShows(group: String): List<TvShow> = channels().filter { it.group == group }.map { it.toShow() }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val list = channels().filter { it.group == id }
        return Genre(id = id, name = id, shows = list.drop((page - 1) * PAGE).take(PAGE).map { it.toShow() })
    }

    override suspend fun getTvShows(page: Int): List<TvShow> =
        channels().drop((page - 1) * PAGE).take(PAGE).map { it.toShow() }

    override suspend fun getTvShow(id: String): TvShow {
        val ch = decode(id)
        return TvShow(
            id = id,
            title = ch?.name.orEmpty(),
            poster = ch?.logo,
            banner = ch?.logo,
            overview = name,
            seasons = listOf(Season(id = id, number = 1, title = "En Vivo")),
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> =
        listOf(Episode(id = seasonId, number = 1, title = "Ver en directo"))

    /**
     * La línea elegida primero y luego el mismo canal en las demás líneas: si una falla, el
     * reproductor pasa sola a la siguiente hasta encontrar una que funcione.
     */
    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        val ch = decode(id) ?: return listOf(Video.Server(id = id, name = "1"))
        runCatching { channels() }
        val others = alternates[channelKey(ch.name)].orEmpty().filter { it.url != ch.url }.map(::encode)
        return (listOf(id) + others).distinct().mapIndexed { i, serverId -> Video.Server(id = serverId, name = "${i + 1}") }
    }

    /** "ESPN HD", "ESPN (720p)", "espn" -> "espn". */
    private fun channelKey(name: String) = normalize(name)
        .replace(Regex("\\[.*?]|\\(.*?\\)"), " ")
        .replace(Regex("\\b(hd|fhd|uhd|sd|4k|1080p?|720p?|480p?|backup|alt)\\b"), " ")
        .replace(Regex("[^a-z0-9]+"), "")

    override suspend fun getVideo(server: Video.Server): Video {
        val ch = decode(server.id) ?: throw Exception("Canal no válido")
        val headers = buildMap {
            ch.userAgent?.let { put("User-Agent", it) }
            ch.referer?.let { put("Referer", it) }
        }
        return Video(source = ch.url, subtitles = emptyList(), headers = headers.ifEmpty { null })
    }

    override suspend fun getMovies(page: Int): List<Movie> = emptyList()
    override suspend fun getMovie(id: String): Movie = throw Exception("No disponible")
    override suspend fun getPeople(id: String, page: Int): People = throw Exception("No disponible")

    companion object {
        private const val TAG = "PanelM3uProvider"
        private const val CACHE_MS = 30 * 60 * 1000L
        private const val HOME_ROW = 30
        private const val PAGE = 60
        private const val SEP = "\u0001"
        private const val OTHERS = "Otros"
        private const val MIN_GROUP = 3
        private val GROUP_NAMES = mapOf(
            "undefined" to OTHERS, "general" to "General", "news" to "Noticias", "sports" to "Deportes",
            "movies" to "Películas", "series" to "Series", "music" to "Música", "entertainment" to "Entretenimiento",
            "kids" to "Infantil", "animation" to "Animación", "documentary" to "Documentales", "religious" to "Religión",
            "education" to "Educación", "culture" to "Cultura", "legislative" to "Gobierno", "lifestyle" to "Estilo de vida",
            "comedy" to "Comedia", "cooking" to "Cocina", "family" to "Familia", "travel" to "Viajes",
            "outdoor" to "Aire libre", "business" to "Negocios", "classic" to "Clásicos", "science" to "Ciencia",
            "weather" to "Clima", "shop" to "Compras", "auto" to "Motor", "relax" to "Relax", "public" to "Públicos",
        )
        private val ATTR_LOGO = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
        private val ATTR_GROUP = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
        private val ATTR_UA = Regex("""http-user-agent="([^"]*)"""", RegexOption.IGNORE_CASE)
        private val ADULT = Regex("\\b(xxx|adult[oa]?s?|porno?|erotic[oa]?s?)\\b|18\\+|\\+18", RegexOption.IGNORE_CASE)

        private fun normalize(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .trim()

        const val KEY_CHANNEL_LINES = "panel_channel_lines"
        const val KEY_PERSONAL_URL = "personal_provider_url"
    }
}

object PanelChannelsProvider : PanelM3uProvider("Canales", PanelM3uProvider.KEY_CHANNEL_LINES)

object PersonalProvider : PanelM3uProvider("Personal Provider", PanelM3uProvider.KEY_PERSONAL_URL)
