package com.streamflixreborn.streamflix.fragments.movies

import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.CatalogRows
import com.streamflixreborn.streamflix.utils.TmdbArtwork
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Movie
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

class MoviesViewModel(database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    
    init {
        // Listen for provider changes and reload data
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getMovies()
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    if (state.movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.movieDao().getByIds(state.movies.map { it.id }))
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        },
    ) { state, moviesDb ->
        when (state) {
            is State.SuccessLoading -> {
                val moviesById = moviesDb.associateBy { it.id }
                State.SuccessLoading(
                    movies = state.movies.map { movie ->
                        moviesById[movie.id]
                            ?.takeIf { !movie.isSame(it) }
                            ?.let { movie.copy().merge(it) }
                            ?: movie
                    },
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
        data class SuccessLoading(val movies: List<Movie>, val hasMore: Boolean) : State()
        /** Filas por género (con "Ver todo"); si el proveedor no tiene géneros se usa la grilla. */
        data class SuccessLoadingRows(val categories: List<com.streamflixreborn.streamflix.models.Category>) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getMovies()
    }


    fun getMovies() = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        // Primero intenta las filas por género (Acción, Terror, Suspenso...) con "Ver todo".
        val rowsProvider = UserPreferences.currentProvider
        if (rowsProvider != null) {
            val rows = runCatching {
                CatalogRows.build(
                    provider = rowsProvider,
                    movies = true,
                    allTitle = StreamFlixApp.instance.getString(R.string.movies_all_row),
                )
            }.onFailure { Log.e("MoviesViewModel", "rows: ", it) }.getOrNull()
            if (rows != null) {
                _state.emit(State.SuccessLoadingRows(rows.categories))
                if (TmdbArtwork.isAvailable(rowsProvider)) {
                    val enriched = TmdbArtwork.enrichedCategories(rowsProvider, rows.categories)
                    if (_state.value is State.SuccessLoadingRows) _state.emit(State.SuccessLoadingRows(enriched))
                }
                return@launch
            }
        }

        try {
            val movies = ParentalControlUtils.filterItems(
                UserPreferences.currentProvider!!.getMovies()
            ).filterIsInstance<Movie>()

            page = 1

            _state.emit(State.SuccessLoading(movies, movies.isNotEmpty()))

            // El proveedor no clasifica por género: se muestran primero todos y luego filas de
            // géneros de TMDb con solo los títulos que tiene el proveedor.
            if (rowsProvider != null && TmdbArtwork.isAvailable(rowsProvider)) {
                val tmdbRows = runCatching {
                    CatalogRows.buildFromTmdb(
                        rowsProvider, true, StreamFlixApp.instance.getString(R.string.movies_all_row),
                    )
                }.getOrNull()
                if (tmdbRows != null && page == 1 && _state.value is State.SuccessLoading) {
                    _state.emit(State.SuccessLoadingRows(tmdbRows.categories))
                }
            }

            // Arte y sinopsis en alta calidad desde TMDb (clave del panel).
            val provider = UserPreferences.currentProvider
            if (TmdbArtwork.isAvailable(provider)) {
                val enriched = TmdbArtwork.enrichedItems(provider, movies)
                if (page == 1 && _state.value is State.SuccessLoading) _state.emit(State.SuccessLoading(enriched, enriched.isNotEmpty()))
            }
        } catch (e: Exception) {
            Log.e("MoviesViewModel", "getMovies: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMoreMovies() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            _state.emit(State.LoadingMore)

            try {
                val movies = ParentalControlUtils.filterItems(
                    UserPreferences.currentProvider!!.getMovies(page + 1)
                ).filterIsInstance<Movie>()

                page += 1

                val emitted = State.SuccessLoading(
                    movies = currentState.movies + movies,
                    hasMore = movies.isNotEmpty(),
                )
                _state.emit(emitted)

                val provider = UserPreferences.currentProvider
                if (TmdbArtwork.isAvailable(provider)) {
                    val enriched = TmdbArtwork.enrichedItems(provider, movies)
                    if (_state.value == emitted) {
                        _state.emit(emitted.copy(movies = currentState.movies + enriched))
                    }
                }
            } catch (e: Exception) {
                Log.e("MoviesViewModel", "loadMoreMovies: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
