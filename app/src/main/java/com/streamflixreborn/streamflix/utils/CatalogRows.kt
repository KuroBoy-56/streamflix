package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.fragments.genre.GenreViewModel
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.Normalizer

/**
 * Filas de Películas/Series por género (Acción, Terror, Suspenso...): ~20 portadas por fila y una
 * tarjeta "Ver todo" al final que abre la grilla completa de esa categoría.
 * Solo se muestran géneros que el proveedor realmente tiene con contenido de ese tipo.
 */
object CatalogRows {

    private const val TAG = "CatalogRows"
    const val ROW_SIZE = 20
    private const val MIN_ROW_SIZE = 3
    private const val MAX_GENRES = 16

    /** Orden preferido de géneros; el resto va después en el orden del proveedor. */
    private val PRIORITY = listOf(
        "accion", "terror", "suspenso", "suspense", "thriller", "comedia", "drama", "animacion",
        "ciencia ficcion", "aventura", "romance", "crimen", "fantasia", "misterio", "familia",
        "documental", "belica", "guerra", "historia", "musica", "western", "anime",
    )

    data class Rows(
        val all: Category,
        val genres: List<Category>,
    ) {
        val categories: List<Category> get() = listOf(all) + genres
    }

    /**
     * @param allTitle título de la fila con todo el catálogo ("Todas las películas").
     * @return null si el proveedor no ofrece géneros (se usa la grilla clásica).
     */
    suspend fun build(provider: Provider, movies: Boolean, allTitle: String): Rows? = coroutineScope {
        val genresDeferred = async {
            runCatching { provider.search("", 1) }.getOrDefault(emptyList())
                .filterIsInstance<Genre>()
                .filter { it.id.isNotBlank() && it.name.isNotBlank() }
                .distinctBy { it.id }
        }
        val allItems = runCatching {
            if (movies) provider.getMovies(1) else provider.getTvShows(1)
        }.getOrDefault(emptyList())
            .let { ParentalControlUtils.filterItems(it) }

        val genres = genresDeferred.await()
        if (genres.isEmpty()) return@coroutineScope null

        val ordered = genres
            .sortedBy { genre -> PRIORITY.indexOfFirst { normalize(genre.name).startsWith(it) }.let { if (it < 0) Int.MAX_VALUE else it } }
            .take(MAX_GENRES)

        val limiter = Semaphore(4)
        val genreRows = ordered.map { genre ->
            async {
                limiter.withPermit {
                    runCatching {
                        val shows = provider.getGenre(genre.id, 1).shows
                        val items: List<AppAdapter.Item> = if (movies) {
                            shows.filterIsInstance<Movie>()
                        } else {
                            shows.filterIsInstance<TvShow>()
                        }
                        ParentalControlUtils.filterItems(items).distinct().take(ROW_SIZE)
                    }.onFailure { Log.w(TAG, "${provider.name}/${genre.name}: ${it.message}") }
                        .getOrDefault(emptyList())
                        .takeIf { it.size >= MIN_ROW_SIZE }
                        ?.let { items ->
                            Category(
                                name = genre.name.replaceFirstChar { it.titlecase() },
                                list = items + seeAll(GenreViewModel.genreIdFor(genre.id, movies), genre.name),
                            )
                        }
                }
            }
        }.awaitAll().filterNotNull()

        if (genreRows.isEmpty()) return@coroutineScope null

        val allId = if (movies) GenreViewModel.ALL_MOVIES_ID else GenreViewModel.ALL_TV_SHOWS_ID
        val allRow = Category(
            name = allTitle,
            list = allItems.take(ROW_SIZE) + seeAll(allId, allTitle),
        )
        Rows(allRow, genreRows)
    }

    /**
     * Respaldo cuando el proveedor no clasifica por género ese tipo de contenido: géneros de TMDb
     * con solo los títulos que el proveedor tiene. Puede tardar (busca cada título en el proveedor).
     */
    suspend fun buildFromTmdb(provider: Provider, movies: Boolean, allTitle: String): Rows? = coroutineScope {
        if (!TmdbArtwork.canClassify(provider)) return@coroutineScope null
        val allItems = runCatching { if (movies) provider.getMovies(1) else provider.getTvShows(1) }
            .getOrDefault(emptyList())
            .let { ParentalControlUtils.filterItems(it) }
        val genres = if (movies) TmdbHomeSections.MOVIE_GENRES else TmdbHomeSections.TV_GENRES
        val limiter = Semaphore(4)
        val rows = genres.map { (tmdbId, name) ->
            async {
                limiter.withPermit {
                    val items = runCatching { TmdbHomeSections.discoverOnProvider(provider, movies, tmdbId, 1) }
                        .getOrDefault(emptyList())
                        .let { ParentalControlUtils.filterItems(it) }
                        .take(ROW_SIZE)
                    if (items.size >= MIN_ROW_SIZE) {
                        Category(name, items + seeAll(GenreViewModel.tmdbGenreId(tmdbId, movies), name))
                    } else null
                }
            }
        }.awaitAll().filterNotNull()
        if (rows.isEmpty()) return@coroutineScope null
        val allId = if (movies) GenreViewModel.ALL_MOVIES_ID else GenreViewModel.ALL_TV_SHOWS_ID
        Rows(Category(allTitle, allItems.take(ROW_SIZE) + seeAll(allId, allTitle)), rows)
    }

    private fun seeAll(id: String, name: String) = Genre(id = id, name = name)

    fun isSeeAll(item: AppAdapter.Item) = item is Genre

    private fun normalize(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .replace("-", " ")
        .trim()
}
