package com.streamflixreborn.streamflix.providers

import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.TMDb3
import com.streamflixreborn.streamflix.utils.TMDb3.original
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Edición Sports: el catálogo de TMDb (español) limitado a deportes. Los servidores de video se
 * buscan igual que en TMDb (es), entre todos los proveedores de la app.
 * Las películas y series se eligen por palabras clave de TMDb (ids verificados en themoviedb.org).
 */
class SportsProvider : TmdbProvider("es") {

    /** Un deporte: título de la fila e ids de palabras clave de TMDb. */
    data class Sport(val key: String, val title: String, val keywords: List<Int>)

    companion object {
        /** Único proveedor de películas y series de la edición Sports. */
        val INSTANCE by lazy { SportsProvider() }

        val SPORTS = listOf(
            Sport("futbol", "⚽ Fútbol", listOf(13042, 1477)),
            Sport("combate", "🥊 Boxeo, MMA y lucha", listOf(209476, 2792, 9917, 384605, 271585, 354756, 5970)),
            Sport("motor", "🏎️ Fórmula 1 y automovilismo", listOf(233981, 242128, 830)),
            Sport("basquet", "🏀 Baloncesto", listOf(6496, 370659)),
            Sport("americano", "🏈 Fútbol americano", listOf(579)),
            Sport("beisbol", "⚾ Béisbol", listOf(1480)),
            Sport("raqueta", "🎾 Tenis y golf", listOf(1488, 2702)),
            Sport("mas", "🏅 Más deportes", listOf(6483, 7912, 180491, 5349, 5605, 155759, 5719, 2070, 166939)),
            Sport("docs", "🎬 Documentales deportivos", listOf(159290)),
        )
        private val GENERAL = listOf(6075, 333328, 274126)

        /** Todas las palabras clave deportivas. */
        val ALL_KEYWORDS: Set<Int> = (SPORTS.flatMap { it.keywords } + GENERAL).toSet()

        /** Respaldo por texto (títulos sin palabras clave en TMDb). */
        private val TEXT = Regex(
            "\\b(f[uú]tbol|soccer|boxeo|boxing|boxeador|ufc|mma|lucha libre|wrestling|nba|nfl|baloncesto|basketball|b[eé]isbol|baseball|tenis|tennis|golf|f[oó]rmula ?1|\\bf1\\b|nascar|rally|ciclismo|olimp|mundial|world cup|deport|sport|atleta|athlete|surf|hockey|rugby|marat[oó]n)",
            RegexOption.IGNORE_CASE,
        )
    }

    private val keywordCache = ConcurrentHashMap<String, Boolean>()

    private fun TMDb3.Movie.toMovie() = Movie(
        id = id.toString(), title = title, overview = overview, released = releaseDate,
        rating = voteAverage.toDouble(), poster = posterPath?.w500, banner = backdropPath?.original,
    )

    private fun TMDb3.Tv.toTvShow() = TvShow(
        id = id.toString(), title = name, overview = overview, released = firstAirDate,
        rating = voteAverage.toDouble(), poster = posterPath?.w500, banner = backdropPath?.original,
    )

    private fun ids(keywords: Collection<Int>) = TMDb3.Params.WithBuilder<TMDb3.Keyword.KeywordId>(keywords.joinToString("|"))

    private suspend fun movies(keywords: Collection<Int>, page: Int, genre: String? = null): List<Movie> = runCatching {
        TMDb3.Discover.movie(
            page = page, language = language, withKeywords = ids(keywords),
            withGenres = genre?.let { TMDb3.Params.WithBuilder(it) },
        ).results.map { it.toMovie() }
    }.getOrDefault(emptyList())

    private suspend fun tvShows(keywords: Collection<Int>, page: Int, genre: String? = null): List<TvShow> = runCatching {
        TMDb3.Discover.tv(
            page = page, language = language, withKeywords = ids(keywords),
            withGenres = genre?.let { TMDb3.Params.WithBuilder(it) },
        ).results.map { it.toTvShow() }
    }.getOrDefault(emptyList())

    private fun <T> List<T>.mix(other: List<T>): List<T> = sequence {
        val a = iterator()
        val b = other.iterator()
        while (a.hasNext() || b.hasNext()) {
            if (a.hasNext()) yield(a.next())
            if (b.hasNext()) yield(b.next())
        }
    }.toList()

    /** true si el título tiene alguna palabra clave deportiva en TMDb (o, sin ellas, texto deportivo). */
    private suspend fun isSports(show: Show): Boolean {
        val key = when (show) {
            is Movie -> "m" + show.id
            is TvShow -> "t" + show.id
        }
        keywordCache[key]?.let { return it }
        val keywords = runCatching {
            when (show) {
                is Movie -> TMDb3.Keywords.movie(show.id.toInt())
                is TvShow -> TMDb3.Keywords.tv(show.id.toInt())
            }
        }.getOrNull()
        val text = when (show) {
            is Movie -> show.title + " " + show.overview.orEmpty()
            is TvShow -> show.title + " " + show.overview.orEmpty()
        }
        val result = keywords?.any { it in ALL_KEYWORDS } == true || TEXT.containsMatchIn(text)
        keywordCache[key] = result
        return result
    }

    private suspend fun <T : Show> List<T>.onlySports(): List<T> = coroutineScope {
        map { show -> async { show.takeIf { isSports(it) } } }.awaitAll().filterNotNull()
    }

    override suspend fun getHome(): List<Category> = coroutineScope {
        val rows = SPORTS.map { sport ->
            async { Category(sport.title, movies(sport.keywords, 1).mix(tvShows(sport.keywords, 1))) }
        }
        val popular = async { movies(ALL_KEYWORDS, 1).mix(tvShows(ALL_KEYWORDS, 1)) }
        val series = async { tvShows(ALL_KEYWORDS, 1) + tvShows(ALL_KEYWORDS, 2) }

        val featured = popular.await().filter { show ->
            when (show) {
                is Movie -> !show.banner.isNullOrEmpty()
                is TvShow -> !show.banner.isNullOrEmpty()
            }
        }.take(5)

        listOf(
            Category(Category.FEATURED, featured),
            Category("🔥 Lo más visto en deportes", popular.await()),
            Category("📺 Series deportivas", series.await()),
        ) + rows.awaitAll()
    }.filter { it.list.isNotEmpty() }

    override suspend fun getMovies(page: Int): List<Movie> = movies(ALL_KEYWORDS, page)

    override suspend fun getTvShows(page: Int): List<TvShow> = tvShows(ALL_KEYWORDS, page)

    /** Sin texto: los deportes como "géneros". Con texto: búsqueda de TMDb dejando solo lo deportivo. */
    override suspend fun search(query: String, page: Int): List<AppAdapter.Item> {
        if (query.isEmpty()) return SPORTS.map { Genre(id = "sport:" + it.key, name = it.title) }
        val shows = super.search(query, page).filterIsInstance<Show>()
        return shows.onlySports().map { it as AppAdapter.Item }
    }

    override suspend fun getGenre(id: String, page: Int): Genre {
        val sport = SPORTS.firstOrNull { "sport:" + it.key == id }
        return if (sport != null) {
            Genre(id = id, name = sport.title, shows = movies(sport.keywords, page).mix(tvShows(sport.keywords, page)))
        } else {
            // Género de TMDb (Drama, Comedia...): solo sus títulos deportivos.
            Genre(id = id, name = "", shows = movies(ALL_KEYWORDS, page, id).mix(tvShows(ALL_KEYWORDS, page, id)))
        }
    }

    override suspend fun getMovie(id: String): Movie {
        val movie = super.getMovie(id)
        return movie.copy(recommendations = movie.recommendations.onlySports())
    }

    override suspend fun getTvShow(id: String): TvShow {
        val tvShow = super.getTvShow(id)
        return tvShow.copy(recommendations = tvShow.recommendations.onlySports())
    }

    override suspend fun getPeople(id: String, page: Int): People {
        val people = super.getPeople(id, page)
        return people.copy(filmography = people.filmography.onlySports())
    }
}
