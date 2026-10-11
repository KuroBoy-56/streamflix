package com.streamflixreborn.streamflix.fragments.tv_shows

import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.CatalogRows
import com.streamflixreborn.streamflix.utils.TmdbArtwork
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.database.AppDatabase
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

class TvShowsViewModel(database: AppDatabase) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    
    init {
        // Listen for provider changes and reload data
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getTvShows()
            }
        }
    }
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    if (state.tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.tvShowDao().getByIds(state.tvShows.map { it.id }))
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        },
    ) { state, tvShowsDb ->
        when (state) {
            is State.SuccessLoading -> {
                val tvShowsById = tvShowsDb.associateBy { it.id }
                State.SuccessLoading(
                    tvShows = state.tvShows.map { tvShow ->
                        tvShowsById[tvShow.id]
                            ?.takeIf { !tvShow.isSame(it) }
                            ?.let { tvShow.copy().merge(it) }
                            ?: tvShow
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
        data class SuccessLoading(val tvShows: List<TvShow>, val hasMore: Boolean) : State()
        /** Filas por género (con "Ver todo"); si el proveedor no tiene géneros se usa la grilla. */
        data class SuccessLoadingRows(val categories: List<com.streamflixreborn.streamflix.models.Category>) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getTvShows()
    }


    fun getTvShows() = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        // Primero intenta las filas por género (Acción, Terror, Suspenso...) con "Ver todo".
        val rowsProvider = UserPreferences.currentProvider
        if (rowsProvider != null) {
            val rows = runCatching {
                CatalogRows.build(
                    provider = rowsProvider,
                    movies = false,
                    allTitle = StreamFlixApp.instance.getString(R.string.tv_shows_all_row),
                )
            }.onFailure { Log.e("TvShowsViewModel", "rows: ", it) }.getOrNull()
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
            val tvShows = ParentalControlUtils.filterItems(
                UserPreferences.currentProvider!!.getTvShows()
            ).filterIsInstance<TvShow>()

            page = 1

            _state.emit(State.SuccessLoading(tvShows, tvShows.isNotEmpty()))

            // El proveedor no clasifica por género: se muestran primero todos y luego filas de
            // géneros de TMDb con solo los títulos que tiene el proveedor.
            if (rowsProvider != null && TmdbArtwork.isAvailable(rowsProvider)) {
                val tmdbRows = runCatching {
                    CatalogRows.buildFromTmdb(
                        rowsProvider, false, StreamFlixApp.instance.getString(R.string.tv_shows_all_row),
                    )
                }.getOrNull()
                if (tmdbRows != null && page == 1 && _state.value is State.SuccessLoading) {
                    _state.emit(State.SuccessLoadingRows(tmdbRows.categories))
                }
            }

            // Arte y sinopsis en alta calidad desde TMDb (clave del panel).
            val provider = UserPreferences.currentProvider
            if (TmdbArtwork.isAvailable(provider)) {
                val enriched = TmdbArtwork.enrichedItems(provider, tvShows)
                if (page == 1 && _state.value is State.SuccessLoading) _state.emit(State.SuccessLoading(enriched, enriched.isNotEmpty()))
            }
        } catch (e: Exception) {
            Log.e("TvShowsViewModel", "getTvShows: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }

    fun loadMoreTvShows() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading) {
            _state.emit(State.LoadingMore)

            try {
                val tvShows = ParentalControlUtils.filterItems(
                    UserPreferences.currentProvider!!.getTvShows(page + 1)
                ).filterIsInstance<TvShow>()

                page += 1

                val emitted = State.SuccessLoading(
                    tvShows = currentState.tvShows + tvShows,
                    hasMore = tvShows.isNotEmpty(),
                )
                _state.emit(emitted)

                val provider = UserPreferences.currentProvider
                if (TmdbArtwork.isAvailable(provider)) {
                    val enriched = TmdbArtwork.enrichedItems(provider, tvShows)
                    if (_state.value == emitted) {
                        _state.emit(emitted.copy(tvShows = currentState.tvShows + enriched))
                    }
                }
            } catch (e: Exception) {
                Log.e("TvShowsViewModel", "loadMoreTvShows: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
