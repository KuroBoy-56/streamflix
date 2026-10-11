package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.TMDb3.w1280
import com.streamflixreborn.streamflix.utils.TMDb3.w780
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Secciones del Inicio basadas en TMDb ("Tendencias de hoy", "Películas más vistas",
 * "Mejor valoradas", "Series más vistas"...), mostrando SOLO los títulos que el proveedor
 * actual tiene (se buscan en el proveedor y se usa su id para reproducir).
 * Si una sección queda con pocos títulos disponibles, se descarta.
 */
object TmdbHomeSections {

    private const val TAG = "TmdbHomeSections"
    private const val PER_LIST = 20
    private const val MIN_MATCHES = 5

    data class Candidate(
        val isMovie: Boolean,
        val title: String,
        val originalTitle: String,
        val year: Int?,
        val posterPath: String?,
        val backdropPath: String?,
        val overview: String?,
        val rating: Double,
    )

    // Resultados de búsqueda por proveedor+título para no repetir peticiones.
    private val searchCache = ConcurrentHashMap<String, List<AppAdapter.Item>>()

    suspend fun build(provider: Provider, titles: Titles): List<Category> = coroutineScope {
        if (!TmdbArtwork.isAvailable(provider)) return@coroutineScope emptyList()
        val language = TmdbArtwork.languageFor(provider)
        val movies = Provider.supportsMovies(provider)
        val series = Provider.supportsTvShows(provider)

        val lists = buildList {
            add(titles.trending to async {
                runCatching {
                    TMDb3.Trending.all(TMDb3.Params.TimeWindow.DAY, language = language).results.mapNotNull { item ->
                        when (item) {
                            is TMDb3.Movie -> if (movies) item.toCandidate() else null
                            is TMDb3.Tv -> if (series) item.toCandidate() else null
                            else -> null
                        }
                    }
                }.getOrDefault(emptyList())
            })
            if (movies) {
                add(titles.popularMovies to async {
                    runCatching { TMDb3.MovieLists.popular(language = language).results.map { it.toCandidate() } }.getOrDefault(emptyList())
                })
                add(titles.topRatedMovies to async {
                    runCatching { TMDb3.MovieLists.topRated(language = language).results.map { it.toCandidate() } }.getOrDefault(emptyList())
                })
            }
            if (series) {
                add(titles.popularSeries to async {
                    runCatching { TMDb3.TvSeriesLists.popular(language = language).results.map { it.toCandidate() } }.getOrDefault(emptyList())
                })
                add(titles.topRatedSeries to async {
                    runCatching { TMDb3.TvSeriesLists.topRated(language = language).results.map { it.toCandidate() } }.getOrDefault(emptyList())
                })
            }
        }

        val limiter = Semaphore(4)
        lists.map { (title, deferred) ->
            async {
                val candidates = deferred.await().take(PER_LIST)
                val matched = candidates.map { candidate ->
                    async { limiter.withPermit { findOnProvider(provider, candidate) } }
                }.awaitAll().filterNotNull().distinct()
                if (matched.size >= MIN_MATCHES) Category(title, matched) else null
            }
        }.awaitAll().filterNotNull()
            .also { Log.i(TAG, "${provider.name}: ${it.joinToString { c -> "${c.name}=${c.list.size}" }}") }
    }

    data class Titles(
        val trending: String,
        val popularMovies: String,
        val topRatedMovies: String,
        val popularSeries: String,
        val topRatedSeries: String,
    ) {
        val all: Set<String> get() = setOf(trending, popularMovies, topRatedMovies, popularSeries, topRatedSeries)
    }

    internal suspend fun findOnProvider(provider: Provider, candidate: Candidate): AppAdapter.Item? {
        val key = "${provider.name}|${normalize(candidate.title)}"
        val results = searchCache[key] ?: runCatching { provider.search(candidate.title, 1) }
            .getOrDefault(emptyList())
            .also { searchCache[key] = it }

        val wanted = setOf(normalize(candidate.title), normalize(candidate.originalTitle)).filter { it.isNotEmpty() }
        val match = results.firstOrNull { item ->
            val (title, year) = when (item) {
                is Movie -> if (!candidate.isMovie) return@firstOrNull false else item.title to item.released?.get(java.util.Calendar.YEAR)
                is TvShow -> if (candidate.isMovie) return@firstOrNull false else item.title to item.released?.get(java.util.Calendar.YEAR)
                else -> return@firstOrNull false
            }
            val sameTitle = normalize(stripYear(title)) in wanted
            val yearOk = year == null || candidate.year == null || kotlin.math.abs(year - candidate.year) <= 1
            sameTitle && yearOk
        } ?: return null

        // Copia con el arte y la sinopsis de TMDb (el id sigue siendo el del proveedor).
        return when (match) {
            is Movie -> match.copy().merge(match).also { m ->
                candidate.posterPath?.let { m.poster = it.w780 }
                (candidate.backdropPath ?: candidate.posterPath)?.let { m.banner = it.w1280 }
                candidate.overview?.takeIf { it.isNotBlank() }?.let { m.overview = it }
                if ((m.rating ?: 0.0) <= 0.0 && candidate.rating > 0) m.rating = candidate.rating
            }
            is TvShow -> match.copy().merge(match).also { t ->
                candidate.posterPath?.let { t.poster = it.w780 }
                (candidate.backdropPath ?: candidate.posterPath)?.let { t.banner = it.w1280 }
                candidate.overview?.takeIf { it.isNotBlank() }?.let { t.overview = it }
                if ((t.rating ?: 0.0) <= 0.0 && candidate.rating > 0) t.rating = candidate.rating
            }
            else -> null
        }
    }

    internal fun TMDb3.Movie.toCandidate() = Candidate(
        isMovie = true,
        title = title,
        originalTitle = originalTitle,
        year = releaseDate?.take(4)?.toIntOrNull(),
        posterPath = posterPath,
        backdropPath = backdropPath,
        overview = overview,
        rating = voteAverage.toDouble(),
    )

    internal fun TMDb3.Tv.toCandidate() = Candidate(
        isMovie = false,
        title = name,
        originalTitle = originalName,
        year = firstAirDate?.take(4)?.toIntOrNull(),
        posterPath = posterPath,
        backdropPath = backdropPath,
        overview = overview,
        rating = voteAverage.toDouble(),
    )

    private fun stripYear(value: String) = value.replace(Regex("\\s*[\\(\\[]\\d{4}[\\)\\]]\\s*$"), "")

    private fun normalize(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .replace("&", "y")
        .replace(Regex("[^a-z0-9]"), "")

    /** Géneros de TMDb usados cuando el proveedor no clasifica (p. ej. series en Cuevana). */
    val TV_GENRES = listOf(
        10759 to "Acción y Aventura", 18 to "Drama", 80 to "Crimen", 35 to "Comedia",
        9648 to "Misterio", 10765 to "Ciencia ficción y Fantasía", 16 to "Animación",
        10751 to "Familia", 99 to "Documental", 10768 to "Bélica y Política",
    )
    val MOVIE_GENRES = listOf(
        28 to "Acción", 27 to "Terror", 53 to "Suspenso", 35 to "Comedia", 18 to "Drama",
        16 to "Animación", 878 to "Ciencia ficción", 12 to "Aventura", 10749 to "Romance",
        80 to "Crimen", 14 to "Fantasía", 10751 to "Familia",
    )

    /**
     * Títulos populares de un género de TMDb que el proveedor SÍ tiene (con su id del proveedor).
     */
    suspend fun discoverOnProvider(
        provider: Provider,
        movies: Boolean,
        tmdbGenreId: Int,
        page: Int,
    ): List<AppAdapter.Item> = coroutineScope {
        if (!TmdbArtwork.isAvailable(provider)) return@coroutineScope emptyList()
        val params = buildMap {
            put("with_genres", tmdbGenreId.toString())
            put("sort_by", "popularity.desc")
            put("vote_count.gte", "20")
            put("page", page.toString())
            TmdbArtwork.languageFor(provider)?.let { put("language", it) }
        }
        val candidates = runCatching {
            if (movies) TMDb3.Discover.movie(params).results.map { it.toCandidate() }
            else TMDb3.Discover.tv(params).results.map { it.toCandidate() }
        }.getOrDefault(emptyList())
        val limiter = Semaphore(4)
        candidates.map { c -> async { limiter.withPermit { findOnProvider(provider, c) } } }
            .awaitAll()
            .filterNotNull()
            .distinct()
    }
}
