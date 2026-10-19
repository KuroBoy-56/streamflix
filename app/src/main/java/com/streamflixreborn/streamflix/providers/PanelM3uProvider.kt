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
import kotlinx.coroutines.launch
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.text.Normalizer
import java.util.concurrent.TimeUnit

/**
 * Canales en vivo de las listas M3U del panel, unidas en una sola sección ("Live"):
 * - Las "líneas de canales" del distribuidor (Logo App) y la URL personal del usuario.
 * - La línea por defecto (elegida en el panel para el usuario) manda: sus canales y categorías
 *   son la base. Las demás líneas solo suman los canales que la base no tiene, colocados en la
 *   categoría que corresponde (misma marca, mismo tipo de grupo o deducida por el nombre).
 * - Un canal que está en varias líneas se reproduce de la línea por defecto y, si falla, el
 *   reproductor pasa solo a la misma señal en otra línea.
 * - Cada canal lleva su país (tvg-country, tvg-id, prefijo "MX:" o el grupo) para filtrar.
 *
 * Se quitan entradas sin URL válida, duplicados y canales para adultos.
 */
abstract class PanelM3uProvider(
    override val name: String,
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
        val country: String = "",
        /** tvg-id: canal en la guía de programación (EPG) de la lista. */
        val epgId: String = "",
    )

    /** Carpeta del lado izquierdo de "En Vivo" (categoría o país) con su cantidad de canales. */
    data class Folder(val name: String, val count: Int)

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    @Volatile private var cache: List<Channel>? = null
    /** Mismo canal en otras líneas/listas (URLs distintas): respaldo si una no funciona. */
    @Volatile private var alternates: Map<String, List<Channel>> = emptyMap()
    @Volatile private var cacheKey: String = ""
    @Volatile private var cacheTime = 0L
    /** Guías de programación (url-tvg / x-tvg-url) que traen las listas. */
    @Volatile private var epgSources: List<String> = emptyList()
    private val parsedEpg = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** URLs de las listas: primero la línea por defecto del usuario, luego las demás y la personal. */
    fun sources(context: Context? = null): List<String> {
        val ctx = context ?: runCatching { StreamFlixApp.instance }.getOrNull() ?: return emptyList()
        val prefs = ctx.getSharedPreferences(AssignedProviders.PREFS_NAME, Context.MODE_PRIVATE)
        val urls = (prefs.getString(KEY_CHANNEL_LINES, "").orEmpty().lines() + prefs.getString(KEY_PERSONAL_URL, "").orEmpty().lines())
            .map { it.trim() }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .distinct()
        val preferred = prefs.getString(KEY_DEFAULT_LINE, "").orEmpty().trim()
        return if (preferred in urls) listOf(preferred) + (urls - preferred) else urls
    }

    fun isConfigured(context: Context? = null) = sources(context).isNotEmpty()

    /**
     * Canales con caché: en memoria y en el equipo (sobrevive al cerrar la app). Si hay copia
     * se devuelve al instante y, si tiene más de 30 min, se actualiza en segundo plano; solo la
     * primera vez (o si cambian las líneas del panel) se espera a la descarga.
     */
    private suspend fun channels(): List<Channel> {
        val urls = sources()
        val key = urls.joinToString("\n")
        val now = System.currentTimeMillis()
        cache?.takeIf { key == cacheKey }?.let { mem ->
            if (now - cacheTime >= CACHE_MS) refreshInBackground(urls, key)
            return mem
        }
        withContext(Dispatchers.IO) { readDisk(key) }?.let { disk ->
            if (now - cacheTime >= CACHE_MS) refreshInBackground(urls, key)
            return disk
        }
        return download(urls, key)
    }

    private val refreshScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var refreshJob: Job? = null

    private fun refreshInBackground(urls: List<String>, key: String) {
        if (refreshJob?.isActive == true) return
        refreshJob = refreshScope.launch { runCatching { download(urls, key) } }
    }

    private suspend fun download(urls: List<String>, key: String): List<Channel> {
        // Una lista por línea, en orden (la primera es la de por defecto).
        parsedEpg.clear()
        val lines = coroutineScope {
            urls.map { url -> async(Dispatchers.IO) { clean(fetch(url)) } }.awaitAll()
        }
        val merged = merge(lines)
        // Si todas las líneas fallan se conserva lo que había (no se borra la lista).
        if (merged.isNotEmpty() || cache == null || key != cacheKey) {
            alternates = lines.flatten()
                .distinctBy { it.url }
                .groupBy { channelKey(it.name) }
                .filter { (k, list) -> k.isNotEmpty() && list.size > 1 }
            cache = merged
            cacheKey = key
            cacheTime = System.currentTimeMillis()
            epgSources = parsedEpg.toList()
            if (merged.isNotEmpty()) withContext(Dispatchers.IO) { writeDisk() }
        }
        return cache.orEmpty()
    }

    // ---------------------------------------------------------------------------------------
    // Caché en el equipo: una línea por canal (campos separados por \u0001), rápido de leer
    // aunque la lista tenga miles de canales.
    // ---------------------------------------------------------------------------------------

    private fun diskFile(): File? = runCatching { File(StreamFlixApp.instance.filesDir, "live_cache_${name.lowercase()}.txt") }.getOrNull()

    private fun Channel.toLine(tag: String, extra: String = "") =
        listOf(tag, extra, name, url, logo, group, userAgent.orEmpty(), referer.orEmpty(), country, epgId)
            .joinToString(SEP) { it.replace('\n', ' ').replace('\r', ' ') }

    private fun writeDisk() = runCatching {
        val file = diskFile() ?: return@runCatching
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { out ->
            out.write("v2$SEP$cacheTime$SEP${cacheKey.hashCode()}$SEP${cacheKey.length}\n")
            epgSources.forEach { out.write("E$SEP$it\n") }
            cache.orEmpty().forEach { out.write(it.toLine("C")); out.write("\n") }
            alternates.forEach { (k, list) -> list.forEach { out.write(it.toLine("A", k)); out.write("\n") } }
        }
        tmp.renameTo(file)
    }.onFailure { Log.w(TAG, "$name: no se pudo guardar la caché: ${it.message}") }

    private fun readDisk(key: String): List<Channel>? = runCatching {
        val file = diskFile()?.takeIf { it.isFile } ?: return null
        val channels = ArrayList<Channel>()
        val alts = HashMap<String, MutableList<Channel>>()
        val epg = ArrayList<String>()
        var time = 0L
        file.bufferedReader().useLines { lines ->
            lines.forEachIndexed { i, line ->
                val f = line.split(SEP)
                if (i == 0) {
                    // Copia de otras líneas (el panel las cambió): no sirve.
                    if (f.getOrNull(0) != "v2" || f.getOrNull(2) != key.hashCode().toString() || f.getOrNull(3) != key.length.toString()) return null
                    time = f[1].toLongOrNull() ?: 0L
                    return@forEachIndexed
                }
                if (f[0] == "E") { f.getOrNull(1)?.let { epg += it }; return@forEachIndexed }
                if (f.size < 10) return@forEachIndexed
                val ch = Channel(f[2], f[3], f[4], f[5], f[6].ifEmpty { null }, f[7].ifEmpty { null }, f[8], f[9])
                if (f[0] == "C") channels += ch else alts.getOrPut(f[1]) { mutableListOf() } += ch
            }
        }
        if (channels.isEmpty()) return null
        cache = channels
        alternates = alts
        cacheKey = key
        cacheTime = time
        epgSources = epg
        channels
    }.getOrNull()

    /** Edición Sports: solo canales deportivos, cada uno en la carpeta de su marca (ESPN, DAZN...). */
    internal fun clean(list: List<Channel>): List<Channel> = list
        .filter { it.url.startsWith("http", true) && it.name.isNotBlank() }
        .filterNot { ch -> ADULT.containsMatchIn(normalize(ch.name)) || ADULT.containsMatchIn(normalize(ch.group)) }
        .map { it.copy(name = it.name.replace(Regex("\\s+"), " ").trim()) }
        .filter { isSportsChannel(it.name, it.group) }
        .map { it.copy(group = sportsBrand(it.name, it.group)) }
        .distinctBy { it.url }

    /**
     * La línea por defecto es la base; cada línea siguiente suma solo los canales que no están
     * (p. ej. "ESPN 7" si la base no lo tiene), en la categoría que les corresponde.
     */
    internal fun merge(lines: List<List<Channel>>): List<Channel> {
        val result = mutableListOf<Channel>()
        val seen = HashSet<String>()
        fun add(ch: Channel) {
            val k = channelKey(ch.name)
            if (k.isNotEmpty() && seen.add(k)) result += ch
        }
        lines.firstOrNull().orEmpty().forEach(::add)

        // Marca -> categoría de la base ("espn" -> "ESPN" o "Deportes"), si tiene 2+ canales.
        val brandGroups = result.groupBy { brand(it.name) }
            .filterKeys { it.length >= 2 }
            .mapValues { (_, list) -> list.groupingBy { it.group }.eachCount().maxByOrNull { it.value }?.takeIf { it.value >= 2 }?.key }
        val baseGroups = result.map { it.group }.distinct()

        lines.drop(1).forEach { line ->
            line.forEach { ch ->
                val group = brandGroups[brand(ch.name)]
                    ?: baseGroups.firstOrNull { it.equals(ch.group, ignoreCase = true) }
                    ?: ch.group
                add(ch.copy(group = group))
            }
        }

        // Los grupos con muy pocos canales se juntan en "Otros" para no llenar de carpetas.
        val small = result.groupingBy { it.group }.eachCount().filterValues { it < MIN_GROUP }.keys
        return result.map { if (it.group in small) it.copy(group = OTHERS) else it }
    }

    /** Primera palabra del nombre: "ESPN 2 HD" -> "espn", "Fox Sports 3" -> "fox". */
    private fun brand(name: String) = normalize(name).split(Regex("[^a-z0-9]+")).firstOrNull { it.isNotEmpty() }.orEmpty()

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

    internal fun parseM3u(raw: String): List<Channel> {
        val out = mutableListOf<Channel>()
        var name = ""; var logo = ""; var group = ""; var ua: String? = null; var referer: String? = null
        var tvgCountry = ""; var tvgId = ""
        for (line in raw.lineSequence()) {
            val t = line.trim()
            when {
                t.startsWith("#EXTM3U", true) -> ATTR_EPG.findAll(t).forEach { m ->
                    m.groupValues[1].split(',').map { it.trim() }.filter { it.startsWith("http", true) }.forEach { parsedEpg += it }
                }
                t.startsWith("#EXTINF", true) -> {
                    name = t.substringAfterLast(",").trim()
                    logo = ATTR_LOGO.find(t)?.groupValues?.get(1).orEmpty()
                    group = ATTR_GROUP.find(t)?.groupValues?.get(1).orEmpty()
                    ua = ATTR_UA.find(t)?.groupValues?.get(1)
                    referer = null
                    tvgCountry = ATTR_COUNTRY.find(t)?.groupValues?.get(1).orEmpty()
                    tvgId = ATTR_ID.find(t)?.groupValues?.get(1).orEmpty()
                }
                t.startsWith("#EXTGRP:", true) -> if (group.isBlank()) group = t.substringAfter(":").trim()
                t.startsWith("#EXTVLCOPT:http-user-agent=", true) -> ua = t.substringAfter("=").trim()
                t.startsWith("#EXTVLCOPT:http-referrer=", true) -> referer = t.substringAfter("=").trim()
                t.startsWith("http", true) -> {
                    if (name.isNotBlank()) out += withCountry(Channel(name, t, logo, group, ua, referer, epgId = tvgId.trim()), tvgCountry, tvgId)
                    name = ""; logo = ""; group = ""; ua = null; referer = null; tvgCountry = ""; tvgId = ""
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
        withCountry(Channel(title, url, "", "", null, null), "", "")
    }

    /** País del canal y nombre sin el prefijo de país ("MX: Canal 5" -> "Canal 5", México). */
    private fun withCountry(ch: Channel, tvgCountry: String, tvgId: String): Channel {
        var name = ch.name
        var code = tvgCountry.split(',', ';', ' ').map { it.trim().lowercase() }.firstOrNull { it in COUNTRIES }
            ?: Regex("\\.([a-z]{2})(@.*)?$").find(tvgId.lowercase())?.groupValues?.get(1)?.takeIf { it in COUNTRIES }
        PREFIX.find(name)?.let { m ->
            val c = alias(m.groupValues[1])
            if (c in COUNTRIES) {
                if (code == null) code = c
                name = name.substring(m.range.last + 1).trim().ifEmpty { name }
            }
        }
        if (code == null) code = SUFFIX.find(name)?.groupValues?.get(1)?.let(::alias)?.takeIf { it in COUNTRIES }
        if (code == null) {
            val g = normalize(ch.group)
            code = COUNTRY_WORDS.entries.firstOrNull { (word, _) -> Regex("\\b${Regex.escape(word)}\\b").containsMatchIn(g) }?.value
                ?: PREFIX.find(ch.group)?.groupValues?.get(1)?.let(::alias)?.takeIf { it in COUNTRIES }
        }
        return ch.copy(name = name, country = code?.let(::countryLabel).orEmpty())
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

    /** Carpetas por categoría (como se agrupan en "En Vivo"). */
    suspend fun categoryFolders(): List<Folder> = groups(channels()).map { (group, list) -> Folder(group, list.size) }

    /** Carpetas por país (solo canales con país detectado), de más a menos canales. */
    suspend fun countryFolders(): List<Folder> = channels()
        .filter { it.country.isNotEmpty() }
        .groupingBy { it.country }.eachCount()
        .map { (country, count) -> Folder(country, count) }
        .sortedByDescending { it.count }

    suspend fun countryShows(country: String): List<TvShow> = channels().filter { it.country == country }.map { it.toShow() }

    /** Guía del canal (si la lista trae EPG y el canal su tvg-id). */
    suspend fun epgFor(showId: String): List<com.streamflixreborn.streamflix.utils.LiveEpg.Programme> {
        val all = channels()
        val url = decode(showId)?.url ?: return emptyList()
        val epgId = all.firstOrNull { it.url == url }?.epgId?.takeIf { it.isNotEmpty() } ?: return emptyList()
        val ids = all.mapNotNullTo(HashSet()) { it.epgId.takeIf { id -> id.isNotEmpty() } }
        return com.streamflixreborn.streamflix.utils.LiveEpg.programmes(epgSources, ids)[epgId].orEmpty()
    }

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
        private const val OTHERS = "Otros deportes"
        private const val MIN_GROUP = 3
        private val ATTR_LOGO = Regex("""tvg-logo="([^"]*)"""", RegexOption.IGNORE_CASE)
        private val ATTR_GROUP = Regex("""group-title="([^"]*)"""", RegexOption.IGNORE_CASE)
        private val ATTR_UA = Regex("""http-user-agent="([^"]*)"""", RegexOption.IGNORE_CASE)
        private val ATTR_COUNTRY = Regex("""tvg-country="([^"]*)"""", RegexOption.IGNORE_CASE)
        private val ATTR_ID = Regex("""tvg-id="([^"]*)"""", RegexOption.IGNORE_CASE)
        private val ATTR_EPG = Regex("""(?:url-tvg|x-tvg-url)="([^"]*)"""", RegexOption.IGNORE_CASE)
        /** "MX: ", "|ES| ", "[AR] ", "USA - " al inicio del nombre o del grupo. */
        private val PREFIX = Regex("""^\s*[\[(|]?\s*([A-Za-z]{2,3})\s*[\])|:\-]+\s*""")
        /** "Canal 5 (MX)" al final del nombre. */
        private val SUFFIX = Regex("""\(([A-Za-z]{2,3})\)\s*$""")

        private val COUNTRIES = mapOf(
            "mx" to "México", "ar" to "Argentina", "co" to "Colombia", "cl" to "Chile", "pe" to "Perú",
            "ve" to "Venezuela", "ec" to "Ecuador", "uy" to "Uruguay", "py" to "Paraguay", "bo" to "Bolivia",
            "cr" to "Costa Rica", "pa" to "Panamá", "gt" to "Guatemala", "hn" to "Honduras", "sv" to "El Salvador",
            "ni" to "Nicaragua", "do" to "Rep. Dominicana", "pr" to "Puerto Rico", "cu" to "Cuba",
            "us" to "Estados Unidos", "ca" to "Canadá", "es" to "España", "pt" to "Portugal", "br" to "Brasil",
            "it" to "Italia", "fr" to "Francia", "de" to "Alemania", "gb" to "Reino Unido",
        )
        private fun alias(code: String) = when (val c = code.lowercase()) {
            "usa" -> "us"; "uk" -> "gb"; "mex" -> "mx"; "arg" -> "ar"; "col" -> "co"; "chi" -> "cl"; "esp" -> "es"
            else -> c
        }
        private val COUNTRY_WORDS = mapOf(
            "mexico" to "mx", "argentina" to "ar", "colombia" to "co", "chile" to "cl", "peru" to "pe",
            "venezuela" to "ve", "ecuador" to "ec", "uruguay" to "uy", "paraguay" to "py", "bolivia" to "bo",
            "costa rica" to "cr", "panama" to "pa", "guatemala" to "gt", "honduras" to "hn", "el salvador" to "sv",
            "nicaragua" to "ni", "dominicana" to "do", "puerto rico" to "pr", "cuba" to "cu",
            "estados unidos" to "us", "usa" to "us", "canada" to "ca", "espana" to "es", "spain" to "es",
            "portugal" to "pt", "brasil" to "br", "brazil" to "br", "italia" to "it", "italy" to "it",
            "francia" to "fr", "france" to "fr", "alemania" to "de", "germany" to "de", "reino unido" to "gb",
        )
        /** "🇲🇽 México" (bandera a partir del código del país). */
        private fun countryLabel(code: String): String {
            val flag = code.uppercase().map { Character.toChars(0x1F1E6 + (it - 'A')).concatToString() }.joinToString("")
            return "$flag ${COUNTRIES[code] ?: code.uppercase()}"
        }
        /** Marcas deportivas: carpeta -> palabras del nombre o del grupo del canal (sin acentos, minúsculas). */
        private val SPORTS_BRANDS = linkedMapOf(
            "ESPN" to listOf("espn"),
            "DAZN" to listOf("dazn"),
            "Sky Sports" to listOf("sky sport"),
            "beIN Sports" to listOf("bein"),
            "Fox Sports" to listOf("fox sport", "fox deportes"),
            "TUDN" to listOf("tudn"),
            "TyC Sports" to listOf("tyc"),
            "TNT Sports" to listOf("tnt sport"),
            "Win Sports" to listOf("win sport", "win+"),
            "Claro Sports" to listOf("claro sport"),
            "DirecTV Sports" to listOf("directv sport", "dsports", "dsport"),
            "Movistar Deportes" to listOf("movistar"),
            "LaLiga TV" to listOf("laliga", "la liga"),
            "Eurosport" to listOf("eurosport"),
            "Canal+ Sport" to listOf("canal+ sport", "canal plus sport", "canal+ foot"),
            "GolTV" to listOf("goltv", "gol tv"),
            "SporTV" to listOf("sportv"),
            "TSN y Sportsnet" to listOf("tsn", "sportsnet"),
            "NBA" to listOf("nba"),
            "NFL" to listOf("nfl"),
            "MLB" to listOf("mlb"),
            "NHL" to listOf("nhl"),
            "Fórmula 1 y Motor" to listOf("f1", "formula 1", "motogp", "moto gp", "nascar", "motor", "racing"),
            "UFC, Boxeo y Lucha" to listOf("ufc", "wwe", "aew", "boxing", "boxeo", "fight", "pelea", "ppv"),
            "Golf y Tenis" to listOf("golf", "tennis", "tenis"),
        )
        /** Palabras que delatan un canal o grupo deportivo aunque no sea de una marca conocida. */
        private val SPORTS_WORDS = Regex(
            "\\b(sports?|deportes?|deportiv[oa]s?|futbol|football|soccer|liga|league|copa|gol|olymp|olimp|basket|baloncesto|beisbol|baseball|hockey|rugby|cricket|volley|atletismo|ciclismo|vix|arena sport|polsat sport|setanta|premiere|super sport|supersport|star\\+|fanatiz)\\b",
        )

        private fun brandWords(text: String): String = " " + text.replace(Regex("[^a-z0-9+]+"), " ") + " "

        /** Marca deportiva del canal (por nombre y luego por grupo), o null. */
        private fun brandOf(text: String): String? {
            val t = brandWords(normalize(text))
            return SPORTS_BRANDS.entries.firstOrNull { (_, words) ->
                // Palabras cortas (nba, f1, tyc) completas; las largas también como inicio ("espn2").
                words.any { w -> t.contains(" $w") && (w.length > 3 || t.contains(" $w ")) }
            }?.key
        }

        internal fun isSportsChannel(name: String, group: String): Boolean =
            brandOf(name) != null || brandOf(group) != null ||
                SPORTS_WORDS.containsMatchIn(normalize(name)) || SPORTS_WORDS.containsMatchIn(normalize(group))

        internal fun sportsBrand(name: String, group: String): String = brandOf(name) ?: brandOf(group) ?: OTHERS

        private val ADULT = Regex("\\b(xxx|adult[oa]?s?|porno?|erotic[oa]?s?)\\b|18\\+|\\+18", RegexOption.IGNORE_CASE)

        private fun normalize(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}"), "")
            .trim()

        const val KEY_CHANNEL_LINES = "panel_channel_lines"
        const val KEY_PERSONAL_URL = "personal_provider_url"
        /** Línea de canales por defecto del usuario (elegida en el panel). */
        const val KEY_DEFAULT_LINE = "panel_default_channel_line"
    }
}

/** Sección "Live": líneas del panel + URL personal, unidas con la línea por defecto primero. */
object PanelLiveProvider : PanelM3uProvider("Live")
