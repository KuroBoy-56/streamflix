package com.streamflixreborn.streamflix.utils

import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.TMDb3.w1280
import com.streamflixreborn.streamflix.utils.TMDb3.w780
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * Mejora portadas, fondos y sinopsis de cualquier proveedor VOD con TMDb, usando la clave
 * enviada por el panel del distribuidor. No cambia ids ni títulos del proveedor: solo el arte
 * (posters w780, fondos w1280) y la descripción cuando TMDb la tiene.
 */
object TmdbArtwork {

    private const val TAG = "TmdbArtwork"
    private val cache = ConcurrentHashMap<String, Result>()
    private val limiter = Semaphore(6)

    private sealed class Result {
        data class Found(val artwork: TmdbUtils.Artwork) : Result()
        data object NotFound : Result()
    }

    fun isAvailable(provider: Provider?): Boolean {
        if (provider == null || provider is IptvProvider || provider is TmdbProvider) return false
        if (!UserPreferences.enableTmdb) return false
        val hasKey = UserPreferences.tmdbApiKey.isNotBlank() ||
                BuildConfig.TMDB_API_KEY.let { it.isNotBlank() && it != "null" }
        return hasKey
    }

    /**
     * Para clasificar por género (Series/Películas) basta con tener alguna clave de TMDb,
     * aunque el panel no haya activado el arte de TMDb.
     */
    fun canClassify(provider: Provider?): Boolean {
        if (provider == null || provider is IptvProvider || provider is TmdbProvider) return false
        return UserPreferences.tmdbApiKey.isNotBlank() ||
                BuildConfig.TMDB_API_KEY.let { it.isNotBlank() && it != "null" }
    }

    /** Idioma de TMDb según el proveedor (español latino por defecto para "es"). */
    fun languageFor(provider: Provider?): String? = when (val lang = provider?.language) {
        null, "" -> null
        "es" -> "es-MX"
        "en" -> "en-US"
        else -> lang
    }

    /**
     * Devuelve copias enriquecidas (no modifica las originales, para que la UI detecte el cambio).
     */
    suspend fun enrichedCategories(provider: Provider?, categories: List<Category>): List<Category> {
        if (!isAvailable(provider)) return categories
        val copies = categories.map { category ->
            category.copy(list = category.list.map(::copyOf)).also {
                it.selectedIndex = category.selectedIndex
                it.itemSpacing = category.itemSpacing
            }
        }
        enrichInPlace(provider, copies.flatMap { it.list })
        return copies
    }

    /** Copias enriquecidas de una lista de resultados (películas/series). */
    suspend fun <T : AppAdapter.Item> enrichedItems(provider: Provider?, items: List<T>): List<T> {
        if (!isAvailable(provider)) return items
        @Suppress("UNCHECKED_CAST")
        val copies = items.map { copyOf(it) as T }
        return enrichInPlace(provider, copies)
    }

    private fun copyOf(item: AppAdapter.Item): AppAdapter.Item = when (item) {
        is Movie -> item.copy().merge(item)
        is TvShow -> item.copy().merge(item)
        is Episode -> item.copy(tvShow = item.tvShow?.let { it.copy().merge(it) }).merge(item)
        else -> item
    }

    /** Modifica los objetos recibidos (usar solo antes de emitirlos a la UI). */
    suspend fun <T : AppAdapter.Item> enrichInPlace(provider: Provider?, items: List<T>): List<T> {
        if (!isAvailable(provider)) return items
        val language = languageFor(provider)
        coroutineScope {
            items.distinct().map { item ->
                async {
                    limiter.withPermit {
                        runCatching {
                            when (item) {
                                is Movie -> enrich(item, language)
                                is TvShow -> enrich(item, language)
                                is Episode -> item.tvShow?.let { enrich(it, language) }
                                else -> Unit
                            }
                        }.onFailure { Log.w(TAG, "enrich: ${it.message}") }
                    }
                }
            }.awaitAll()
        }
        return items
    }

    suspend fun enrich(movie: Movie, language: String?) {
        val year = movie.released?.get(java.util.Calendar.YEAR)
        val artwork = lookup("movie", movie.title, year, language) {
            TmdbUtils.findMovieArtwork(movie.title, year, language)
        } ?: return
        apply(artwork, setPoster = { movie.poster = it }, setBanner = { movie.banner = it })
        artwork.overview?.takeIf { it.isNotBlank() }?.let { movie.overview = it }
        if ((movie.rating ?: 0.0) <= 0.0) artwork.rating?.takeIf { it > 0 }?.let { movie.rating = it }
    }

    suspend fun enrich(tvShow: TvShow, language: String?) {
        val year = tvShow.released?.get(java.util.Calendar.YEAR)
        val artwork = lookup("tv", tvShow.title, year, language) {
            TmdbUtils.findTvShowArtwork(tvShow.title, year, language)
        } ?: return
        apply(artwork, setPoster = { tvShow.poster = it }, setBanner = { tvShow.banner = it })
        artwork.overview?.takeIf { it.isNotBlank() }?.let { tvShow.overview = it }
        if ((tvShow.rating ?: 0.0) <= 0.0) artwork.rating?.takeIf { it > 0 }?.let { tvShow.rating = it }
    }

    private fun apply(
        artwork: TmdbUtils.Artwork,
        setPoster: (String) -> Unit,
        setBanner: (String) -> Unit,
    ) {
        artwork.posterPath?.takeIf { it.isNotBlank() }?.let { setPoster(it.w780) }
        (artwork.backdropPath ?: artwork.posterPath)?.takeIf { it.isNotBlank() }?.let { setBanner(it.w1280) }
    }

    private suspend fun lookup(
        type: String,
        title: String,
        year: Int?,
        language: String?,
        fetch: suspend () -> TmdbUtils.Artwork?,
    ): TmdbUtils.Artwork? {
        if (title.isBlank()) return null
        val key = "$type|${title.lowercase().trim()}|${year ?: ""}|${language ?: ""}"
        when (val cached = cache[key]) {
            is Result.Found -> return cached.artwork
            Result.NotFound -> return null
            null -> Unit
        }
        val artwork = fetch()
        cache[key] = artwork?.let { Result.Found(it) } ?: Result.NotFound
        return artwork
    }
}
