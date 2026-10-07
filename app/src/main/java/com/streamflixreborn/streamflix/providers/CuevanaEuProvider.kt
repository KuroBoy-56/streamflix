package com.streamflixreborn.streamflix.providers

import android.util.Log
import com.tanasi.retrofit_jsoup.converter.JsoupConverterFactory
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.extractors.Extractor
import com.streamflixreborn.streamflix.models.*
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import com.streamflixreborn.streamflix.utils.TmdbUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import okhttp3.Cache
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import retrofit2.Retrofit
import retrofit2.http.GET
import retrofit2.http.Url
import java.io.File
import java.util.concurrent.TimeUnit

object CuevanaEuProvider : Provider {

    override val name = "Cuevana 3"
    override val baseUrl: String get() = "https://${UserPreferences.cuevanaDomain}"
    override val language = "es"
    private const val TAG = "CuevanaEuProvider"

    private var _service: CuevanaEuService? = null
    private val service: CuevanaEuService
        get() {
            if (_service == null) {
                val retrofit = Retrofit.Builder()
                    .baseUrl("$baseUrl/")
                    .addConverterFactory(JsoupConverterFactory.create())
                    .client(client)
                    .build()
                _service = retrofit.create(CuevanaEuService::class.java)
            }
            return _service!!
        }

    private var _client: okhttp3.OkHttpClient? = null
    private val client: okhttp3.OkHttpClient
        get() {
            if (_client == null) {
                _client = getOkHttpClient()
            }
            return _client!!
        }

    private val json = Json { ignoreUnknownKeys = true }

    private fun getOkHttpClient(): okhttp3.OkHttpClient {
        val appCache = Cache(File("cacheDir", "okhttpcache"), 10 * 1024 * 1024)
        val clientBuilder = okhttp3.OkHttpClient.Builder()
            .cache(appCache)
            .readTimeout(30, TimeUnit.SECONDS)
            .connectTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36")
                    .header("Referer", baseUrl)
                    .build()
                chain.proceed(request)
            }
            .addInterceptor { chain ->
                val response = chain.proceed(chain.request())
                if (response.isRedirect) {
                    val location = response.header("Location")
                    if (!location.isNullOrEmpty()) {
                        val newHost = if (location.startsWith("http")) {
                            java.net.URL(location).host
                        } else {
                            null
                        }
                        if (!newHost.isNullOrEmpty() && newHost != UserPreferences.cuevanaDomain) {
                            Log.d(TAG, "Domain changed from ${UserPreferences.cuevanaDomain} to $newHost")
                            UserPreferences.cuevanaDomain = newHost
                            _service = null
                            _client = null
                        }
                    }
                }
                response
            }

        return clientBuilder.dns(DnsResolver.doh).build()
    }

    private interface CuevanaEuService {
        @GET
        suspend fun getPage(@Url url: String): Document
    }

    private fun extractNextData(html: String): JSONObject? {
        val scriptTag = "<script id=\"__NEXT_DATA__\" type=\"application/json\">"
        val startIndex = html.indexOf(scriptTag)
        if (startIndex == -1) return null
        val jsonStart = startIndex + scriptTag.length
        val jsonEnd = html.indexOf("</script>", jsonStart)
        if (jsonEnd == -1) return null
        val jsonText = html.substring(jsonStart, jsonEnd)
        return try {
            JSONObject(jsonText).optJSONObject("props")?.optJSONObject("pageProps")
        } catch (e: Exception) {
            Log.e(TAG, "Error parsing __NEXT_DATA__: ${e.message}")
            null
        }
    }

    private suspend fun fetchNextProps(url: String): JSONObject? = withContext(Dispatchers.IO) {
        try {
            val fullUrl = if (url.startsWith("http")) url else "$baseUrl/${url.trimStart('/')}"
            val request = Request.Builder().url(fullUrl).build()
            val html = client.newCall(request).execute().use { it.body?.string() } ?: return@withContext null
            extractNextData(html)
        } catch (e: Exception) {
            Log.e(TAG, "fetchNextProps error for $url: ${e.message}")
            null
        }
    }

    override suspend fun getHome(): List<Category> {
        return try {
            val pageProps = fetchNextProps("$baseUrl/")
            if (pageProps != null) {
                val categories = mutableListOf<Category>()

                fun parseItems(jsonArray: JSONArray?, isMovie: Boolean): List<AppAdapter.Item> {
                    if (jsonArray == null) return emptyList()
                    val items = mutableListOf<AppAdapter.Item>()
                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.optJSONObject(i) ?: continue
                        val slugObj = obj.optJSONObject("slug")
                        val slug = slugObj?.optString("name") ?: obj.optString("slug")
                        if (slug.isBlank()) continue

                        val titlesObj = obj.optJSONObject("titles")
                        val title = titlesObj?.optString("name") ?: obj.optString("title")
                        val imagesObj = obj.optJSONObject("images")
                        val poster = imagesObj?.optString("poster") ?: obj.optString("poster")
                        val banner = imagesObj?.optString("backdrop") ?: obj.optString("backdrop")

                        if (isMovie) {
                            items.add(
                                Movie(
                                    id = "ver-pelicula/$slug",
                                    title = title,
                                    poster = poster.takeIf { it.isNotBlank() },
                                    banner = banner.takeIf { it.isNotBlank() }
                                )
                            )
                        } else {
                            items.add(
                                TvShow(
                                    id = "ver-serie/$slug",
                                    title = title,
                                    poster = poster.takeIf { it.isNotBlank() },
                                    banner = banner.takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                    return items
                }

                val lastMovies = parseItems(pageProps.optJSONArray("tabLastMovies"), isMovie = true)
                if (lastMovies.isNotEmpty()) {
                    categories.add(Category("Últimas Películas", lastMovies))
                }

                val topMovies = parseItems(pageProps.optJSONArray("tabTopMovies"), isMovie = true)
                if (topMovies.isNotEmpty()) {
                    categories.add(Category("Películas Destacadas", topMovies))
                }

                val series = parseItems(pageProps.optJSONArray("series"), isMovie = false)
                if (series.isNotEmpty()) {
                    categories.add(Category("Series Populares", series))
                }

                val episodesArray = pageProps.optJSONArray("episodes")
                if (episodesArray != null && episodesArray.length() > 0) {
                    val eps = mutableListOf<Episode>()
                    for (i in 0 until episodesArray.length()) {
                        val epObj = episodesArray.optJSONObject(i) ?: continue
                        val title = epObj.optString("title")
                        val slugObj = epObj.optJSONObject("slug")
                        val showSlug = slugObj?.optString("name")
                        val seasonNum = slugObj?.optString("season") ?: epObj.optString("season")
                        val epNum = slugObj?.optString("episode") ?: epObj.optString("number")
                        val poster = epObj.optString("image")
                        val epId = "episodio/$showSlug-temporada-$seasonNum-episodio-$epNum"

                        eps.add(
                            Episode(
                                id = epId,
                                number = epNum.toIntOrNull() ?: (i + 1),
                                title = title,
                                poster = poster.takeIf { it.isNotBlank() },
                                season = Season(
                                    id = "",
                                    number = seasonNum.toIntOrNull() ?: 1,
                                    title = "Temporada $seasonNum"
                                )
                            )
                        )
                    }
                    if (eps.isNotEmpty()) {
                        categories.add(Category("Últimos Episodios", eps))
                    }
                }

                if (categories.isNotEmpty()) return categories
            }

            // Fallback: scrape document directly
            val document = service.getPage("$baseUrl/")
            val categories = mutableListOf<Category>()
            fun parseSection(selector: String, name: String): Category? {
                val items = document.select("$selector article").mapNotNull { article ->
                    val a = article.selectFirst("h2 a, a[href]") ?: return@mapNotNull null
                    val title = a.text().ifBlank { article.selectFirst("h2, h3")?.text().orEmpty() }
                    val href = a.attr("href")
                    val poster = (article.selectFirst("img")?.let {
                        it.attr("data-src").ifEmpty { it.attr("src") }.ifEmpty { it.attr("data-lazy-src") }
                    } ?: article.selectFirst(".backdrop")?.attr("style")?.let { style ->
                        Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.get(1)
                    })?.let { fixImageUrl(it) }

                    if (isMovieHref(href)) {
                        Movie(id = extractId(href), title = title, poster = poster)
                    } else if (isTvShowHref(href)) {
                        TvShow(id = extractId(href), title = title, poster = poster)
                    } else null
                }
                return if (items.isNotEmpty()) Category(name = name, list = items) else null
            }

            parseSection("#last-movies", "Últimas Películas")?.let { categories.add(it) }
            parseSection("#last-series", "Últimas Series")?.let { categories.add(it) }

            if (categories.isNotEmpty()) {
                categories
            } else {
                coroutineScope {
                    val movies = async { getMovies(1) }
                    val tvShows = async { getTvShows(1) }
                    listOfNotNull(
                        movies.await().takeIf { it.isNotEmpty() }?.let { Category("Últimas Películas", it.take(20)) },
                        tvShows.await().takeIf { it.isNotEmpty() }?.let { Category("Últimas Series", it.take(20)) },
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getHome error: ${e.message}", e)
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isBlank()) {
            return listOf(
                Genre("accion", "Acción"),
                Genre("aventura", "Aventura"),
                Genre("animacion", "Animación"),
                Genre("ciencia-ficcion", "Ciencia Ficción"),
                Genre("comedia", "Comedia"),
                Genre("crimen", "Crimen"),
                Genre("documental", "Documental"),
                Genre("drama", "Drama"),
                Genre("familia", "Familia"),
                Genre("fantasia", "Fantasía"),
                Genre("misterio", "Misterio"),
                Genre("romance", "Romance"),
                Genre("suspense", "Suspenso"),
                Genre("terror", "Terror"),
            )
        }

        try {
            val url = if (page == 1) "$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}"
            else "$baseUrl/search?q=${java.net.URLEncoder.encode(query, "UTF-8")}&page=$page"

            val pageProps = fetchNextProps(url)
            if (pageProps != null) {
                val moviesArr = pageProps.optJSONArray("movies") ?: pageProps.optJSONArray("results")
                if (moviesArr != null && moviesArr.length() > 0) {
                    val results = mutableListOf<AppAdapter.Item>()
                    for (i in 0 until moviesArr.length()) {
                        val obj = moviesArr.optJSONObject(i) ?: continue
                        val slugObj = obj.optJSONObject("slug")
                        val slug = slugObj?.optString("name") ?: obj.optString("slug")
                        val titlesObj = obj.optJSONObject("titles")
                        val title = titlesObj?.optString("name") ?: obj.optString("title")
                        val imagesObj = obj.optJSONObject("images")
                        val poster = imagesObj?.optString("poster") ?: obj.optString("poster")
                        val banner = imagesObj?.optString("backdrop") ?: obj.optString("backdrop")
                        val urlObj = obj.optJSONObject("url")
                        val fullSlug = urlObj?.optString("slug") ?: ""

                        if (fullSlug.contains("series") || fullSlug.contains("serie")) {
                            results.add(
                                TvShow(
                                    id = "ver-serie/$slug",
                                    title = title,
                                    poster = poster.takeIf { it.isNotBlank() },
                                    banner = banner.takeIf { it.isNotBlank() }
                                )
                            )
                        } else {
                            results.add(
                                Movie(
                                    id = "ver-pelicula/$slug",
                                    title = title,
                                    poster = poster.takeIf { it.isNotBlank() },
                                    banner = banner.takeIf { it.isNotBlank() }
                                )
                            )
                        }
                    }
                    if (results.isNotEmpty()) return results
                }
            }

            val document = service.getPage("$baseUrl/?s=$query")
            return parseArticles(document).mapNotNull { (article, anchor, href) ->
                val title = extractTitle(article, anchor)
                val poster = extractPoster(article)

                if (isMovieHref(href)) {
                    Movie(id = extractId(href), title = title, poster = poster)
                } else if (isTvShowHref(href)) {
                    TvShow(id = extractId(href), title = title, poster = poster)
                } else null
            }
        } catch (e: Exception) {
            Log.e(TAG, "search error: ${e.message}", e)
            return emptyList()
        }
    }

    override suspend fun getMovies(page: Int): List<Movie> {
        try {
            val url = if (page == 1) "$baseUrl/peliculas" else "$baseUrl/peliculas/page/$page"
            val pageProps = fetchNextProps(url)
            if (pageProps != null) {
                val moviesArr = pageProps.optJSONArray("movies")
                if (moviesArr != null && moviesArr.length() > 0) {
                    val movies = mutableListOf<Movie>()
                    for (i in 0 until moviesArr.length()) {
                        val obj = moviesArr.optJSONObject(i) ?: continue
                        val slugObj = obj.optJSONObject("slug")
                        val slug = slugObj?.optString("name") ?: obj.optString("slug")
                        val titlesObj = obj.optJSONObject("titles")
                        val title = titlesObj?.optString("name") ?: obj.optString("title")
                        val imagesObj = obj.optJSONObject("images")
                        val poster = imagesObj?.optString("poster") ?: obj.optString("poster")
                        val banner = imagesObj?.optString("backdrop") ?: obj.optString("backdrop")

                        movies.add(
                            Movie(
                                id = "ver-pelicula/$slug",
                                title = title,
                                poster = poster.takeIf { it.isNotBlank() },
                                banner = banner.takeIf { it.isNotBlank() }
                            )
                        )
                    }
                    if (movies.isNotEmpty()) return movies
                }
            }

            return loadFirstNonEmptyCatalog(
                listOf(
                    if (page == 1) "$baseUrl/peliculas/" else "$baseUrl/peliculas/page/$page/",
                    if (page == 1) "$baseUrl/pelicula/" else "$baseUrl/pelicula/page/$page/",
                )
            ) { document ->
                parseArticles(document).mapNotNull { (article, anchor, href) ->
                    if (!isMovieHref(href)) return@mapNotNull null
                    Movie(
                        id = extractId(href),
                        title = extractTitle(article, anchor),
                        poster = extractPoster(article)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getMovies error: ${e.message}", e)
            return emptyList()
        }
    }

    override suspend fun getTvShows(page: Int): List<TvShow> {
        try {
            val url = if (page == 1) "$baseUrl/series" else "$baseUrl/series/page/$page"
            val pageProps = fetchNextProps(url)
            if (pageProps != null) {
                val seriesArr = pageProps.optJSONArray("series") ?: pageProps.optJSONArray("movies")
                if (seriesArr != null && seriesArr.length() > 0) {
                    val tvShows = mutableListOf<TvShow>()
                    for (i in 0 until seriesArr.length()) {
                        val obj = seriesArr.optJSONObject(i) ?: continue
                        val slugObj = obj.optJSONObject("slug")
                        val slug = slugObj?.optString("name") ?: obj.optString("slug")
                        val titlesObj = obj.optJSONObject("titles")
                        val title = titlesObj?.optString("name") ?: obj.optString("title")
                        val imagesObj = obj.optJSONObject("images")
                        val poster = imagesObj?.optString("poster") ?: obj.optString("poster")
                        val banner = imagesObj?.optString("backdrop") ?: obj.optString("backdrop")

                        tvShows.add(
                            TvShow(
                                id = "ver-serie/$slug",
                                title = title,
                                poster = poster.takeIf { it.isNotBlank() },
                                banner = banner.takeIf { it.isNotBlank() }
                            )
                        )
                    }
                    if (tvShows.isNotEmpty()) return tvShows
                }
            }

            return loadFirstNonEmptyCatalog(
                listOf(
                    if (page == 1) "$baseUrl/series/" else "$baseUrl/series/page/$page/",
                    if (page == 1) "$baseUrl/serie/" else "$baseUrl/serie/page/$page/",
                )
            ) { document ->
                parseArticles(document).mapNotNull { (article, anchor, href) ->
                    if (!isTvShowHref(href)) return@mapNotNull null
                    TvShow(
                        id = extractId(href),
                        title = extractTitle(article, anchor),
                        poster = extractPoster(article)
                    )
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getTvShows error: ${e.message}", e)
            return emptyList()
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val slug = extractSlug(id)
        val url = "$baseUrl/ver-pelicula/$slug"
        val pageProps = fetchNextProps(url)
        val thisMovie = pageProps?.optJSONObject("thisMovie")

        if (thisMovie != null) {
            val titlesObj = thisMovie.optJSONObject("titles")
            val title = titlesObj?.optString("name") ?: thisMovie.optString("title")
            val overview = thisMovie.optString("overview")
            val releaseDate = thisMovie.optString("release_date")
            val duration = thisMovie.optInt("duration").takeIf { it > 0 } ?: thisMovie.optInt("runtime").takeIf { it > 0 }
            val rateObj = thisMovie.optJSONObject("rate")
            val rating = rateObj?.optDouble("average") ?: thisMovie.optDouble("vote_average")
            val imagesObj = thisMovie.optJSONObject("images")
            val poster = imagesObj?.optString("poster") ?: thisMovie.optString("poster")
            val banner = imagesObj?.optString("backdrop") ?: thisMovie.optString("backdrop")
            val tmdbId = thisMovie.optString("TMDbId").takeIf { it.isNotBlank() }

            val genresList = mutableListOf<Genre>()
            val genresArr = thisMovie.optJSONArray("genres")
            if (genresArr != null) {
                for (i in 0 until genresArr.length()) {
                    val g = genresArr.optJSONObject(i) ?: continue
                    val gName = g.optString("name")
                    val gSlug = g.optString("slug", gName.lowercase())
                    if (gName.isNotBlank()) genresList.add(Genre(id = gSlug, name = gName))
                }
            }

            val castList = mutableListOf<People>()
            val castArr = thisMovie.optJSONArray("cast")
            if (castArr != null) {
                for (i in 0 until castArr.length()) {
                    val c = castArr.optJSONObject(i) ?: continue
                    val cName = c.optString("name")
                    val cImg = c.optString("image")
                    if (cName.isNotBlank()) castList.add(People(id = cName, name = cName, image = cImg.takeIf { it.isNotBlank() }))
                }
            }

            if (!tmdbId.isNullOrEmpty()) {
                val tmdbMovie = try {
                    TmdbUtils.getMovie(title = title, year = extractYear(releaseDate), language = language)
                } catch (e: Exception) {
                    null
                }
                if (tmdbMovie != null) {
                    return tmdbMovie.copy(
                        id = id,
                        title = tmdbMovie.title.ifBlank { title },
                        overview = tmdbMovie.overview?.takeIf { it.isNotBlank() } ?: overview,
                        runtime = tmdbMovie.runtime ?: duration,
                        rating = tmdbMovie.rating ?: rating.takeIf { !it.isNaN() },
                        poster = tmdbMovie.poster ?: poster.takeIf { it.isNotBlank() },
                        banner = tmdbMovie.banner ?: banner.takeIf { it.isNotBlank() },
                        genres = if (tmdbMovie.genres.isNotEmpty()) tmdbMovie.genres else genresList,
                        cast = if (tmdbMovie.cast.isNotEmpty()) tmdbMovie.cast else castList,
                    )
                }
            }

            return Movie(
                id = id,
                title = title,
                overview = overview,
                released = releaseDate,
                runtime = duration,
                rating = rating.takeIf { !it.isNaN() },
                poster = poster.takeIf { it.isNotBlank() },
                banner = banner.takeIf { it.isNotBlank() },
                genres = genresList,
                cast = castList,
            )
        }

        // HTML Scraping fallback
        val document = service.getPage("$baseUrl/$id/")
        val title = document.selectFirst("h1")?.text() ?: ""
        val overview = document.selectFirst("div.entry p, div[data-read-more-text]")?.text() ?: ""
        val poster = document.selectFirst("div.self-start figure img, div.Image img")?.let {
            it.attr("data-src").ifEmpty { it.attr("src") }
        }?.let { fixImageUrl(it) }

        return Movie(
            id = id,
            title = title,
            overview = overview,
            poster = poster,
        )
    }

    override suspend fun getTvShow(id: String): TvShow {
        val slug = extractSlug(id)
        val url = "$baseUrl/ver-serie/$slug"
        val pageProps = fetchNextProps(url)
        val thisSerie = pageProps?.optJSONObject("thisSerie")

        if (thisSerie != null) {
            val titlesObj = thisSerie.optJSONObject("titles")
            val title = titlesObj?.optString("name") ?: thisSerie.optString("title")
            val overview = thisSerie.optString("overview")
            val releaseDate = thisSerie.optString("releaseDate")
            val rateObj = thisSerie.optJSONObject("rate")
            val rating = rateObj?.optDouble("average") ?: thisSerie.optDouble("vote_average")
            val imagesObj = thisSerie.optJSONObject("images")
            val poster = imagesObj?.optString("poster") ?: thisSerie.optString("poster")
            val banner = imagesObj?.optString("backdrop") ?: thisSerie.optString("backdrop")
            val tmdbId = thisSerie.optString("TMDbId").takeIf { it.isNotBlank() }

            val seasonsList = mutableListOf<Season>()
            val seasonsArr = thisSerie.optJSONArray("seasons")
            if (seasonsArr != null) {
                for (i in 0 until seasonsArr.length()) {
                    val sObj = seasonsArr.optJSONObject(i) ?: continue
                    val sNum = sObj.optInt("number", i + 1)
                    seasonsList.add(
                        Season(
                            id = "$id/temporada-$sNum",
                            number = sNum,
                            title = "Temporada $sNum"
                        )
                    )
                }
            }

            if (!tmdbId.isNullOrEmpty()) {
                val tmdbTv = try {
                    TmdbUtils.getTvShow(title = title, year = extractYear(releaseDate), language = language)
                } catch (e: Exception) {
                    null
                }
                if (tmdbTv != null) {
                    val mergedSeasons = seasonsList.map { s ->
                        val match = tmdbTv.seasons.find { it.number == s.number }
                        match?.copy(id = s.id) ?: s
                    }
                    return tmdbTv.copy(
                        id = id,
                        title = tmdbTv.title.ifBlank { title },
                        overview = tmdbTv.overview?.takeIf { it.isNotBlank() } ?: overview,
                        poster = tmdbTv.poster ?: poster.takeIf { it.isNotBlank() },
                        banner = tmdbTv.banner ?: banner.takeIf { it.isNotBlank() },
                        seasons = if (mergedSeasons.isNotEmpty()) mergedSeasons else tmdbTv.seasons,
                    )
                }
            }

            return TvShow(
                id = id,
                title = title,
                overview = overview,
                released = releaseDate,
                rating = rating.takeIf { !it.isNaN() },
                poster = poster.takeIf { it.isNotBlank() },
                banner = banner.takeIf { it.isNotBlank() },
                seasons = seasonsList,
            )
        }

        // HTML Fallback
        val document = service.getPage("$baseUrl/$id/")
        val title = document.selectFirst("h1")?.text() ?: ""
        val cuevanaSeasons = document.select("div.se-q, button[data-season], .se-nav li span, .se-c, #seasons .se-q").mapNotNull {
            val number = it.selectFirst(".se-t")?.text()?.toIntOrNull()
                ?: it.attr("data-season").toIntOrNull()
                ?: it.text().filter { it.isDigit() }.toIntOrNull()
                ?: return@mapNotNull null
            Season(
                id = "$id/temporada-$number",
                number = number,
                title = "Temporada $number"
            )
        }.distinctBy { it.number }.sortedBy { it.number }

        return TvShow(
            id = id,
            title = title,
            seasons = cuevanaSeasons
        )
    }

    override suspend fun getEpisodesBySeason(seasonId: String): List<Episode> {
        val seasonNumber = seasonId.substringAfterLast("-").toIntOrNull() ?: 1
        val seriesSlug = extractSlug(if (seasonId.contains("/temporada")) seasonId.substringBeforeLast("/temporada") else seasonId)

        try {
            val url = "$baseUrl/ver-serie/$seriesSlug"
            val pageProps = fetchNextProps(url)
            val thisSerie = pageProps?.optJSONObject("thisSerie")
            val seasonsArr = thisSerie?.optJSONArray("seasons")

            if (seasonsArr != null) {
                for (i in 0 until seasonsArr.length()) {
                    val sObj = seasonsArr.optJSONObject(i) ?: continue
                    val sNum = sObj.optInt("number")
                    if (sNum == seasonNumber) {
                        val epsArr = sObj.optJSONArray("episodes") ?: continue
                        val episodes = mutableListOf<Episode>()
                        for (j in 0 until epsArr.length()) {
                            val epObj = epsArr.optJSONObject(j) ?: continue
                            val epNum = epObj.optInt("number", j + 1)
                            val epTitle = epObj.optString("title")
                            val epImg = epObj.optString("image")
                            val epRelease = epObj.optString("releaseDate")
                            val epId = "episodio/$seriesSlug-temporada-$seasonNumber-episodio-$epNum"

                            episodes.add(
                                Episode(
                                    id = epId,
                                    number = epNum,
                                    title = epTitle.ifBlank { "Episodio $epNum" },
                                    poster = epImg.takeIf { it.isNotBlank() },
                                    released = epRelease.takeIf { it.isNotBlank() },
                                )
                            )
                        }
                        if (episodes.isNotEmpty()) return episodes
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getEpisodesBySeason Next.js error: ${e.message}", e)
        }

        return fallbackGetEpisodesBySeason(seasonId)
    }

    private suspend fun fallbackGetEpisodesBySeason(seasonId: String): List<Episode> {
        val seasonNumber = seasonId.substringAfterLast("-").toIntOrNull()
        val seriesId = if (seasonId.contains("/temporada")) seasonId.substringBeforeLast("/temporada") else seasonId

        val urlsToTry = listOf(
            "$baseUrl/${seasonId.trim('/')}/",
            "$baseUrl/${seriesId.trim('/')}/",
        )

        for (url in urlsToTry) {
            try {
                val document = service.getPage(url)
                val episodes = parseEpisodesFromDoc(document, seasonNumber)
                if (episodes.isNotEmpty()) return episodes
            } catch (e: Exception) {
            }
        }
        return emptyList()
    }

    private fun parseEpisodesFromDoc(document: Document, seasonNumber: Int?): List<Episode> {
        val seasonSection = if (seasonNumber != null) {
            document.select("div.se-c").firstOrNull {
                it.selectFirst(".se-q .se-t, .se-q")?.text()?.filter { it.isDigit() }?.toIntOrNull() == seasonNumber
            }
        } else null

        val episodeElements = seasonSection?.select("ul.episodios li, .episodios li, article")
            ?: document.select("#season-episodes article, ul.episodios li, .episodios li, article.episodio")

        return episodeElements.mapNotNull { element ->
            val a = element.selectFirst("h2 a, .episodiotitle a, a") ?: return@mapNotNull null
            val href = a.attr("href")
            if (href.isEmpty() || (!href.contains("/episodio/") && !href.contains("-temporada-"))) return@mapNotNull null

            val epTitle = a.text()
            val epNumberText = element.selectFirst("span.bg-main, .numerando, .ep-num")?.text() ?: ""
            val epNumber = epNumberText.filter { it.isDigit() }.toIntOrNull() ?: 0

            Episode(
                id = href.substringAfter(baseUrl).trim('/'),
                number = epNumber,
                title = epTitle,
                poster = element.selectFirst("img.poster, .episodioimage img, img")?.let {
                    it.attr("data-src").ifEmpty { it.attr("src") }
                }
            )
        }.distinctBy { it.id }
    }

    override suspend fun getServers(id: String, videoType: Video.Type): List<Video.Server> {
        try {
            val detailUrl = when (videoType) {
                is Video.Type.Movie -> {
                    val slug = extractSlug(id)
                    "$baseUrl/ver-pelicula/$slug"
                }
                is Video.Type.Episode -> {
                    val epId = if (id.startsWith("episodio/")) id else "episodio/${extractSlug(id)}"
                    "$baseUrl/$epId"
                }
            }

            val pageProps = fetchNextProps(detailUrl)
            val videosObj = when (videoType) {
                is Video.Type.Movie -> pageProps?.optJSONObject("thisMovie")?.optJSONObject("videos")
                is Video.Type.Episode -> pageProps?.optJSONObject("episode")?.optJSONObject("videos")
            }

            if (videosObj != null) {
                val servers = mutableListOf<Video.Server>()
                val langs = listOf("latino", "spanish", "english", "japanese")
                for (lang in langs) {
                    val langArray = videosObj.optJSONArray(lang) ?: continue
                    for (i in 0 until langArray.length()) {
                        val sObj = langArray.optJSONObject(i) ?: continue
                        val playerUrl = sObj.optString("result")
                        if (playerUrl.isBlank()) continue
                        val cyberlocker = sObj.optString("cyberlocker").ifBlank { "Server" }
                        val quality = sObj.optString("quality").ifBlank { "HD" }

                        val serverName = "$cyberlocker | ${lang.replaceFirstChar { it.uppercaseChar() }} | $quality"
                        servers.add(
                            Video.Server(
                                id = playerUrl,
                                name = serverName,
                                src = playerUrl
                            )
                        )
                    }
                }
                if (servers.isNotEmpty()) return servers.distinctBy { it.id }
            }

            // HTML Scraping fallback
            val document = service.getPage(detailUrl)
            val iframeUrls = document.select("iframe[src]").map { it.attr("src") }
            return iframeUrls.mapIndexed { idx, url ->
                Video.Server(
                    id = url,
                    name = "Server ${idx + 1}",
                    src = url
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "getServers error: ${e.message}", e)
            return emptyList()
        }
    }

    override suspend fun getVideo(server: Video.Server): Video {
        var finalUrl = server.src.ifBlank { server.id }
        if (finalUrl.contains("/player.php?")) {
            try {
                val html = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url(finalUrl).build()).execute().use { it.body?.string() }
                } ?: ""
                val iframeUrl = Regex("""<iframe[^>]+src="([^"]+)"""", RegexOption.IGNORE_CASE)
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)
                if (!iframeUrl.isNullOrBlank()) {
                    finalUrl = iframeUrl
                }
            } catch (e: Exception) {
                Log.e(TAG, "getVideo iframe unwrap error: ${e.message}")
            }
        }
        if (finalUrl.contains("app.mysync.mov/stream/")) {
            try {
                val html = withContext(Dispatchers.IO) {
                    client.newCall(Request.Builder().url(finalUrl).build()).execute().use { it.body?.string() }
                } ?: ""
                val redirectUrl = html.substringAfter("window.location.replace(\"", "").substringBefore("\"")
                if (redirectUrl.isNotEmpty()) {
                    finalUrl = redirectUrl
                }
            } catch (e: Exception) {
                Log.e(TAG, "getVideo redirect error: ${e.message}")
            }
        }
        return Extractor.extract(finalUrl, server)
    }

    override val logo: String get() = "https://wv3.cuevana3.eu/favicon.ico"

    private fun fixImageUrl(url: String): String? {
        if (url.isEmpty()) return null
        if (url.startsWith("data:image")) return null
        if (url.startsWith("http")) return url
        if (url.startsWith("//")) return "https:$url"
        if (url.startsWith("/thumbs/") || url.startsWith("/backdrops/") || url.startsWith("/logos/") || url.startsWith("/wp-content/")) {
            return "$baseUrl/${url.trimStart('/')}"
        }
        if (Regex("^/[A-Za-z0-9._-]+\\.(jpg|jpeg|png)$", RegexOption.IGNORE_CASE).matches(url)) {
            return url.original
        }
        return "$baseUrl/${url.trimStart('/')}"
    }

    private fun extractSlug(id: String): String {
        return id.trim('/').substringAfterLast("/")
    }

    private fun extractYear(value: String?): Int? {
        return Regex("(19|20)\\d{2}").find(value.orEmpty())?.value?.toIntOrNull()
    }

    private fun parseArticles(document: Document): List<Triple<Element, Element, String>> {
        return document.select("article.tooltip-content, article").mapNotNull { article ->
            val anchor = article.selectFirst("h2 a, a[href]") ?: return@mapNotNull null
            val href = anchor.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Triple(article, anchor, href)
        }
    }

    private fun extractTitle(article: Element, anchor: Element): String {
        return anchor.text()
            .ifBlank { article.selectFirst("h2, h3")?.text().orEmpty() }
            .ifBlank { article.selectFirst("img[alt]")?.attr("alt").orEmpty() }
            .trim()
    }

    private fun extractPoster(article: Element): String? {
        return (article.selectFirst("img")?.let {
            it.attr("data-src")
                .ifEmpty { it.attr("src") }
                .ifEmpty { it.attr("data-lazy-src") }
                .ifEmpty { it.attr("srcset").substringBefore(" ") }
        } ?: article.selectFirst(".backdrop")?.attr("style")?.let { style ->
            Regex("url\\(['\"]?(.*?)['\"]?\\)").find(style)?.groupValues?.get(1)
        })?.let { fixImageUrl(it) }
    }

    private fun isMovieHref(href: String): Boolean {
        return href.contains("/pelicula/") || href.contains("/peliculas/") || href.contains("/ver-pelicula/")
    }

    private fun isTvShowHref(href: String): Boolean {
        return href.contains("/serie/") || href.contains("/series/") ||
                href.contains("/anime/") || href.contains("/animes/") || href.contains("/ver-serie/")
    }

    private fun extractId(url: String): String {
        return url.substringAfter(baseUrl).trim('/')
    }

    private suspend fun <T> loadFirstNonEmptyCatalog(
        urls: List<String>,
        parser: (Document) -> List<T>,
    ): List<T> {
        urls.forEach { url ->
            val document = runCatching { service.getPage(url) }.getOrNull() ?: return@forEach
            val items = parser(document)
            if (items.isNotEmpty()) {
                return items
            }
        }
        return emptyList()
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        return try {
            val url = if (page == 1) "$baseUrl/genero/$id" else "$baseUrl/genero/$id/page/$page"
            val pageProps = fetchNextProps(url)
            val itemsArr = pageProps?.optJSONArray("movies") ?: pageProps?.optJSONArray("series")
            if (itemsArr != null && itemsArr.length() > 0) {
                val shows = mutableListOf<Show>()
                for (i in 0 until itemsArr.length()) {
                    val obj = itemsArr.optJSONObject(i) ?: continue
                    val slugObj = obj.optJSONObject("slug")
                    val slug = slugObj?.optString("name") ?: obj.optString("slug")
                    val titlesObj = obj.optJSONObject("titles")
                    val title = titlesObj?.optString("name") ?: obj.optString("title")
                    val imagesObj = obj.optJSONObject("images")
                    val poster = imagesObj?.optString("poster") ?: obj.optString("poster")
                    val urlObj = obj.optJSONObject("url")
                    val fullSlug = urlObj?.optString("slug") ?: ""

                    if (fullSlug.contains("series") || fullSlug.contains("serie")) {
                        shows.add(TvShow(id = "ver-serie/$slug", title = title, poster = poster.takeIf { it.isNotBlank() }))
                    } else {
                        shows.add(Movie(id = "ver-pelicula/$slug", title = title, poster = poster.takeIf { it.isNotBlank() }))
                    }
                }
                return Genre(id = id, name = id.replaceFirstChar { it.uppercaseChar() }, shows = shows)
            }

            val document = service.getPage(url)
            val shows = parseArticles(document).mapNotNull { (article, anchor, href) ->
                val poster = extractPoster(article)
                if (isMovieHref(href)) {
                    Movie(id = extractId(href), title = extractTitle(article, anchor), poster = poster)
                } else if (isTvShowHref(href)) {
                    TvShow(id = extractId(href), title = extractTitle(article, anchor), poster = poster)
                } else null
            }
            Genre(id = id, name = id.replaceFirstChar { it.uppercaseChar() }, shows = shows)
        } catch (e: Exception) {
            Genre(id = id, name = id.replaceFirstChar { it.uppercaseChar() }, shows = emptyList())
        }
    }

    override suspend fun getPeople(id: String, page: Int): People {
        throw Exception("Esta funzione non è disponibile nel provider Cuevana 3.")
    }
}
