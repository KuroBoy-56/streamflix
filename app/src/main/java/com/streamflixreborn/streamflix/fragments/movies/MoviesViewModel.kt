package com.streamflixreborn.streamflix.fragments.movies

import com.streamflixreborn.streamflix.utils.HomeCacheStore
import kotlinx.coroutines.withTimeoutOrNull
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

        val rowsProvider = UserPreferences.currentProvider
        val appContext = StreamFlixApp.instance.applicationContext
        val allTitle = StreamFlixApp.instance.getString(R.string.movies_all_row)

        // 1) Filas guardadas de la vez anterior: se muestran al instante.
        val cached = rowsProvider?.let {
            runCatching { HomeCacheStore.read(appContext, it, ROWS_CACHE) }.getOrNull()
        }?.filter { it.list.isNotEmpty() }
        if (!cached.isNullOrEmpty()) _state.emit(State.SuccessLoadingRows(cached))

        // 2) Filas por género (Acción, Terror, Suspenso...) con "Ver todo".
        if (rowsProvider != null) {
            val rows = runCatching {
                CatalogRows.build(provider = rowsProvider, movies = true, allTitle = allTitle)
                    ?: withTimeoutOrNull(45_000) { CatalogRows.buildFromTmdb(rowsProvider, true, allTitle) }
            }.onFailure {
                Log.e("MoviesViewModel", "rows: ", it)
                com.streamflixreborn.streamflix.utils.ErrorReporter.report("MoviesViewModel.Rows", it.message, it)
            }.getOrNull()
            if (rows != null) {
                _state.emit(State.SuccessLoadingRows(rows.categories))
                val finalRows = if (TmdbArtwork.isAvailable(rowsProvider)) {
                    TmdbArtwork.enrichedCategories(rowsProvider, rows.categories).also {
                        if (_state.value is State.SuccessLoadingRows) _state.emit(State.SuccessLoadingRows(it))
                    }
                } else rows.categories
                runCatching { HomeCacheStore.write(appContext, rowsProvider, finalRows, ROWS_CACHE) }
                return@launch
            }
            if (!cached.isNullOrEmpty()) return@launch
        }

        // 3) Sin géneros posibles: grilla clásica.
        try {
            val movies = ParentalControlUtils.filterItems(
                UserPreferences.currentProvider!!.getMovies()
            ).filterIsInstance<Movie>()

            page = 1

            _state.emit(State.SuccessLoading(movies, movies.isNotEmpty()))

            // Arte y sinopsis en alta calidad desde TMDb (clave del panel).
            val provider = UserPreferences.currentProvider
            if (TmdbArtwork.isAvailable(provider)) {
                val enriched = TmdbArtwork.enrichedItems(provider, movies)
                if (page == 1 && _state.value is State.SuccessLoading) _state.emit(State.SuccessLoading(enriched, enriched.isNotEmpty()))
            }
        } catch (e: Exception) {
            Log.e("MoviesViewModel", "getMovies: ", e)
            // Proveedor caído: la sección se dibuja con el siguiente proveedor permitido.
            UserPreferences.currentProvider?.let {
                if (com.streamflixreborn.streamflix.utils.AssignedProviders.onHomeProviderFailed(it, "Películas", e)) return@launch
            }
            com.streamflixreborn.streamflix.utils.ErrorReporter.report("MoviesViewModel.FailedLoading", e.message, e)
            _state.emit(State.FailedLoading(e))
        }
    }

    private companion object {
        const val ROWS_CACHE = "movie_rows"
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
                com.streamflixreborn.streamflix.utils.ErrorReporter.report("MoviesViewModel.FailedLoading", e.message, e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
