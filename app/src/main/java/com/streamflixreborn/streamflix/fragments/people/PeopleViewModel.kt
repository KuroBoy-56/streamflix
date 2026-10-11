package com.streamflixreborn.streamflix.fragments.people

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.utils.TmdbPeople
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch

class PeopleViewModel(private val id: String, database: AppDatabase, private val name: String = "") : ViewModel() {

    private val _state = MutableStateFlow<State>(State.Loading)
    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.people.filmography
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
                    val tvShows = state.people.filmography
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
                    people = state.people.copy(
                        filmography = state.people.filmography.map { item ->
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
        data class SuccessLoading(val people: People, val hasMore: Boolean) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getPeople(id)
    }


    /** true cuando la filmografía viene de TMDb (filtrada al proveedor) y no hay más páginas. */
    private var tmdbFilmography = false

    fun getPeople(id: String) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        val provider = UserPreferences.currentProvider
        val providerResult = runCatching { provider!!.getPeople(id) }
            .onFailure { Log.e("PeopleViewModel", "getPeople: ", it) }
        val providerPeople = providerResult.getOrNull()
        page = 1

        // Biografía, foto y datos del actor desde TMDb (clave del panel).
        val info = runCatching { TmdbPeople.find(provider, providerPeople?.name?.ifBlank { null } ?: name) }.getOrNull()
        if (info == null) {
            providerResult.fold(
                onSuccess = { _state.emit(State.SuccessLoading(it, true)) },
                onFailure = { _state.emit(State.FailedLoading(it as? Exception ?: Exception(it))) },
            )
            return@launch
        }

        val merged = TmdbPeople.merge(providerPeople, info, name)
        _state.emit(State.SuccessLoading(merged, providerPeople?.filmography?.isNotEmpty() == true))

        // Si el proveedor no da filmografía, se usa la de TMDb con solo lo que el proveedor tiene.
        if (merged.filmography.isEmpty() && provider != null) {
            val shows = runCatching { TmdbPeople.filmographyOnProvider(provider, info) }.getOrDefault(emptyList())
            if (shows.isNotEmpty()) {
                tmdbFilmography = true
                _state.emit(State.SuccessLoading(merged.copy(filmography = shows), false))
            }
        }
    }

    fun loadMorePeopleFilmography() = viewModelScope.launch(Dispatchers.IO) {
        val currentState = _state.value
        if (currentState is State.SuccessLoading && !tmdbFilmography) {
            _state.emit(State.LoadingMore)

            try {
                val people = UserPreferences.currentProvider!!.getPeople(id, page + 1)

                page += 1

                _state.emit(
                    State.SuccessLoading(
                        people = currentState.people.copy(
                            filmography = currentState.people.filmography + people.filmography
                        ),
                        hasMore = people.filmography.isNotEmpty(),
                    )
                )
            } catch (e: Exception) {
                Log.e("PeopleViewModel", "loadMorePeopleFilmography: ", e)
                _state.emit(State.FailedLoading(e))
            }
        }
    }
}
