package com.streamflixreborn.streamflix.fragments.genre

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Genre
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.ParentalControlUtils
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class GenreViewModel(private val id: String, database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    
    init {
        // Listen for provider changes and reload data
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getGenre(id)
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.genre.shows
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.movieDao().getByIds(movies.map { it.id }))
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        },
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val tvShows = state.genre.shows
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.tvShowDao().getByIds(tvShows.map { it.id }))
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, moviesDb, tvShowsDb ->
        when (state) {
            is State.SuccessLoading -> {
                val moviesById = moviesDb.associateBy { it.id }
                val tvShowsById = tvShowsDb.associateBy { it.id }
                State.SuccessLoading(
                    genre = state.genre.copy(
                        shows = state.genre.shows.map { item ->
                            when (item) {
                                is Movie -> moviesById[item.id]
                                    ?.takeIf { !item.isSame(it) }
                                    ?.let { item.copy().merge(it) }
                                    ?: item
                                is TvShow -> tvShowsById[item.id]
                                    ?.takeIf { !item.isSame(it) }
                                    ?.let { item.copy().merge(it) }
                                    ?: item
                            }
                        }
                    ),
                    hasMore = state.hasMore
                )
            }
            else -> state
        }
    }.flowOn(Dispatchers.IO)

    private var page = 1

    sealed class State {
        data object Loading : State()
        data object LoadingMore : State()
        data class SuccessLoading(val genre: Genre, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getGenre(id)
    }


    fun getGenre(id: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val genre = loadGenrePage(id, 1).let {
                it.copy(shows = ParentalControlUtils.filterShows(it.shows))
            }

            page = 1

            _state.emit(State.SuccessLoading(genre, true))
        } catch (e: Exception) {
            Log.e("GenreViewModel", "getGenre: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMoreGenreShows() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            _state.emit(State.LoadingMore)

            try {
                val genre = loadGenrePage(id, page + 1).let {
                    it.copy(shows = ParentalControlUtils.filterShows(it.shows))
                }

                page += 1

                _state.emit(
                    State.SuccessLoading(
                        genre = Genre(
                            id = genre.id,
                            name = genre.name,

                            shows = currentState.genre.shows + genre.shows,
                        ),
                        hasMore = genre.shows.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("GenreViewModel", "loadMoreGenreShows: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }

    /** Además de los géneros del proveedor, admite "todas las películas/series" (fila "Todas"). */
    private suspend fun loadGenrePage(id: String, page: Int): Genre {
        val provider = UserPreferences.currentProvider!!
        return when (id) {
            ALL_MOVIES_ID -> Genre(id, "", shows = provider.getMovies(page))
            ALL_TV_SHOWS_ID -> Genre(id, "", shows = provider.getTvShows(page))
            else -> when {
                id.startsWith(TMDB_PREFIX) -> {
                    val (kind, genreId) = id.removePrefix(TMDB_PREFIX).split(":").let { it[0] to it[1].toInt() }
                    val shows = com.streamflixreborn.streamflix.utils.TmdbHomeSections
                        .discoverOnProvider(provider, kind == "m", genreId, page)
                        .filterIsInstance<com.streamflixreborn.streamflix.models.Show>()
                    Genre(id, "", shows = shows)
                }
                id.startsWith(MOVIES_ONLY_PREFIX) -> provider.getGenre(id.removePrefix(MOVIES_ONLY_PREFIX), page)
                    .let { it.copy(shows = it.shows.filterIsInstance<com.streamflixreborn.streamflix.models.Movie>()) }
                id.startsWith(TV_SHOWS_ONLY_PREFIX) -> provider.getGenre(id.removePrefix(TV_SHOWS_ONLY_PREFIX), page)
                    .let { it.copy(shows = it.shows.filterIsInstance<com.streamflixreborn.streamflix.models.TvShow>()) }
                else -> provider.getGenre(id, page)
            }
        }
    }

    companion object {
        const val ALL_MOVIES_ID = "__all_movies__"
        const val ALL_TV_SHOWS_ID = "__all_tv_shows__"
        const val MOVIES_ONLY_PREFIX = "__movies__:"
        const val TV_SHOWS_ONLY_PREFIX = "__tvshows__:"
        const val TMDB_PREFIX = "__tmdb__:"

        /** "Ver todo" de un género de TMDb (filtrado a lo que tiene el proveedor). */
        fun tmdbGenreId(tmdbGenreId: Int, movies: Boolean) = "$TMDB_PREFIX${if (movies) "m" else "tv"}:$tmdbGenreId"

        /** Ids que usan las filas de Películas/Series para abrir solo un tipo de contenido. */
        fun genreIdFor(rawGenreId: String, movies: Boolean) =
            (if (movies) MOVIES_ONLY_PREFIX else TV_SHOWS_ONLY_PREFIX) + rawGenreId
    }
}
