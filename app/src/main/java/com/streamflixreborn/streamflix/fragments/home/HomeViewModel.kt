package com.streamflixreborn.streamflix.fragments.home

import com.streamflixreborn.streamflix.utils.TmdbArtwork
import com.streamflixreborn.streamflix.utils.TmdbHomeSections
import android.util.Log
import com.streamflixreborn.streamflix.StreamFlixApp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.adapters.AppAdapter
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.AnimeOnlineNinjaProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.ui.UserDataNotifier
import com.streamflixreborn.streamflix.utils.HomeCacheStore
import com.streamflixreborn.streamflix.utils.ParentalControlUtils
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.UserDataCache
import com.streamflixreborn.streamflix.utils.UserDataCache.toCached
import com.streamflixreborn.streamflix.utils.UserDataCache.toEpisode
import com.streamflixreborn.streamflix.utils.UserDataCache.toMovie
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.combine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformLatest
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

class HomeViewModel(database: AppDatabase) : ViewModel() {

    private data class HomeHistory(
        val continueWatching: List<AppAdapter.Item>,
        val recentlyWatched: List<AppAdapter.Item>,
        val favoritesMovies: List<Movie>,
        val favoriteTvShows: List<TvShow>
    )

    private fun <T> preserveCacheOrder(
        cached: List<T>,
        incoming: List<T>,
        idOf: (T) -> String,
    ): List<T> {
        val incomingById = incoming.associateBy(idOf)
        val orderedExisting = cached.mapNotNull { cachedItem -> incomingById[idOf(cachedItem)] }
        val appendedNew = incoming.filter { incomingItem ->
            cached.none { cachedItem -> idOf(cachedItem) == idOf(incomingItem) }
        }
        return orderedExisting + appendedNew
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    private val continueWatchingTvShowCache = ConcurrentHashMap<String, TvShow>()
    private val continueWatchingSeasonEpisodesCache = ConcurrentHashMap<String, List<Episode>>()
    private val _userDataCache = MutableStateFlow<UserDataCache.UserData?>(null)
    private var currentProvider: Provider? = null

    @OptIn(ExperimentalCoroutinesApi::class)
    val state: Flow<State> = combine(
        _state,

        combine(
            // CONTINUE WATCHING - Cache-first (faster on slow DB devices), falls back to DB
            combine(
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingMovies.isNotEmpty()) {
                    emit(cache.continueWatchingMovies.map { it.toMovie() })
                } else {
                    emitAll(database.movieDao().getWatchingMovies())
                }
            }.flowOn(Dispatchers.IO),
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingEpisodes.isNotEmpty()) {
                    emit(cache.continueWatchingEpisodes.map { it.toEpisode() })
                } else {
                    emitAll(database.episodeDao().getWatchingEpisodes())
                }
            }.flowOn(Dispatchers.IO),
            _userDataCache.transformLatest { cache ->
                if (cache != null && cache.continueWatchingEpisodes.isNotEmpty()) {
                    emit(cache.continueWatchingEpisodes.map { it.toEpisode() })
                } else {
                    emitAll(database.episodeDao().getNextEpisodesToWatch())
                }
            }.flowOn(Dispatchers.IO),
            database.tvShowDao().getAll().flowOn(Dispatchers.IO),
        ) { watchingMovies, watchingEpisodes, watchNextEpisodes, tvShows ->

            val allEpisodes = (watchingEpisodes + watchNextEpisodes)
                .distinctBy { it.id }

            val seasonIds = allEpisodes.mapNotNull { it.season?.id }.distinct()

            val tvShowsMap = tvShows.associateBy { it.id }

            val seasonsMap = if (seasonIds.isEmpty()) {
                emptyMap()
            } else {
                database.seasonDao()
                    .getByIds(seasonIds)
                    .associateBy { it.id }
            }

            val enrichedEpisodes = enrichContinueWatchingEpisodes(
                episodes = allEpisodes.map { episode ->
                    episode.copy(
                        tvShow = episode.tvShow?.id?.let { tvShowsMap[it] } ?: episode.tvShow,
                        season = episode.season?.id?.let { seasonsMap[it] } ?: episode.season,
                    ).apply {
                        merge(episode)
                    }
                }
            )

            val orderIndex = buildMap<String, Int> {
                _userDataCache.value?.continueWatchingMovies?.forEachIndexed { index, cached ->
                    put("movie:${cached.id}", index)
                }
                _userDataCache.value?.continueWatchingEpisodes?.forEachIndexed { index, cached ->
                    put("episode:${cached.id}", index)
                }
            }

            (watchingMovies + enrichedEpisodes)
                .sortedByDescending { item ->
                    when (item) {
                        is Movie -> item.watchHistory?.lastEngagementTimeUtcMillis
                            ?: item.watchedDate?.timeInMillis
                            ?: 0L
                        is Episode -> item.watchHistory?.lastEngagementTimeUtcMillis
                            ?: item.watchedDate?.timeInMillis
                            ?: 0L
                        else -> 0L
                    }
                } as List<AppAdapter.Item>
            }.flowOn(Dispatchers.IO),

            // RECENTLY WATCHED - Recorded immediately when playback starts.
            combine(
                database.movieDao().getRecentlyWatched(),
                database.tvShowDao().getRecentlyWatched(),
            ) { movies, tvShows ->
                val episodeIds = tvShows.mapNotNull { it.lastPlayedEpisodeId }.distinct()
                val episodesById = if (episodeIds.isEmpty()) {
                    emptyMap()
                } else {
                    database.episodeDao().getByIds(episodeIds).associateBy { it.id }
                }

                val recentlyWatchedTvShows = tvShows.map { tvShow ->
                    tvShow.copy().apply {
                        merge(tvShow)
                        lastPlayedEpisode = lastPlayedEpisodeId?.let(episodesById::get)
                    }
                }

                (movies + recentlyWatchedTvShows)
                    .sortedByDescending { item ->
                        when (item) {
                            is Movie -> item.lastPlayedAtMillis ?: 0L
                            is TvShow -> item.lastPlayedAtMillis ?: 0L
                            else -> 0L
                        }
                    } as List<AppAdapter.Item>
            }.flowOn(Dispatchers.IO),
            
            // FAVORITE MOVIES
            database.movieDao().getFavorites().flowOn(Dispatchers.IO),
            
            // FAVORITE TV SHOWS
            database.tvShowDao().getFavorites().flowOn(Dispatchers.IO),

        ) { continueWatching, recentlyWatched, favoritesMovies, favoriteTvShows ->
            HomeHistory(continueWatching, recentlyWatched, favoritesMovies, favoriteTvShows)
        }.flowOn(Dispatchers.IO),

        // MOVIES DB
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val movies = state.categories
                        .flatMap { it.list }
                        .filterIsInstance<Movie>()
                    if (movies.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.movieDao().getByIds(movies.map { it.id }))
                    }
                }
                else -> emit(emptyList<Movie>())
            }
        }.flowOn(Dispatchers.IO),

        // TV SHOWS DB
        _state.transformLatest { state ->
            when (state) {
                is State.SuccessLoading -> {
                    val tvShows = state.categories
                        .flatMap { it.list }
                        .filterIsInstance<TvShow>()
                    if (tvShows.isEmpty()) {
                        emit(emptyList())
                    } else {
                        emitAll(database.tvShowDao().getByIds(tvShows.map { it.id }))
                    }
                }
                else -> emit(emptyList<TvShow>())
            }
        }.flowOn(Dispatchers.IO),

        ) { state, history, moviesDb, tvShowsDb ->

        when (state) {
            is State.SuccessLoading -> {

                val moviesMap = moviesDb.associateBy { it.id }
                val tvShowsMap = tvShowsDb.associateBy { it.id }

                fun mergeItem(item: AppAdapter.Item): AppAdapter.Item {
                    return when (item) {
                        is Movie -> moviesMap[item.id]
                            ?.takeIf { !item.isSame(it) }
                            ?.let { item.copy().merge(it) }
                            ?: item

                        is TvShow -> tvShowsMap[item.id]
                            ?.takeIf { !item.isSame(it) }
                            ?.let { item.copy().merge(it) }
                            ?: item

                        else -> item
                    }
                }

                val categories = ParentalControlUtils.filterCategories(listOfNotNull(

                    // FEATURED
                    state.categories
                        .find { it.name == Category.FEATURED }
                        ?.let { category ->
                            category.copy(
                                list = category.list.map(::mergeItem)
                            )
                        },

                    // CONTINUE WATCHING
                    Category(
                        name = Category.CONTINUE_WATCHING,
                        list = history.continueWatching
                            .filterNot(::isLiveChannel)
                            .sortedByDescending {
                                when (it) {
                                    is Episode -> it.watchHistory?.lastEngagementTimeUtcMillis
                                        ?: it.watchedDate?.timeInMillis
                                        ?: 0L

                                    is Movie -> it.watchHistory?.lastEngagementTimeUtcMillis
                                        ?: it.watchedDate?.timeInMillis
                                        ?: 0L

                                    else -> 0L
                                }
                            }
                            .distinctBy {
                                when (it) {
                                    is Episode -> it.tvShow?.id
                                    is Movie -> it.id
                                    else -> null
                                }
                            },
                    ),

                    Category(
                        name = Category.RECENTLY_WATCHED,
                        list = history.recentlyWatched.filterNot(::isLiveChannel),
                    ),

                    Category(
                        name = Category.FAVORITES,
                        list = (history.favoritesMovies + history.favoriteTvShows).sortedByDescending {
                            when (it) {
                                is Movie -> it.favoritedAtMillis ?: 0L
                                is TvShow -> it.favoritedAtMillis ?: 0L
                                else -> 0L
                            }
                        },
                    ),
                ) + state.categories
                    .filter { it.name != Category.FEATURED }
                    .map { category ->
                        category.copy(
                            list = category.list.map(::mergeItem)
                        )
                    })

                State.SuccessLoading(categories)
            }

            else -> state
        }
    }.flowOn(Dispatchers.IO)

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val categories: List<Category>) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        val initialProvider = UserPreferences.currentProvider
        if (initialProvider != null) {
            currentProvider = initialProvider
            loadUserDataCache(initialProvider)
        }
        viewModelScope.launch {
            ProviderChangeNotifier.providerChangeFlow.collect {
                getHome()
            }
        }

        viewModelScope.launch {
            UserDataNotifier.updates.collect {
                val provider = UserPreferences.currentProvider ?: return@collect
                loadUserDataCache(provider)
            }
        }
        getHome()
    }

    private suspend fun enrichContinueWatchingEpisodes(episodes: List<Episode>): List<Episode> = coroutineScope {
        val provider = UserPreferences.currentProvider ?: return@coroutineScope episodes

        episodes.map { episode ->
            async {
                val tvShowId = episode.tvShow?.id ?: return@async episode
                val resolvedTvShow = continueWatchingTvShowCache[tvShowId] ?: runCatching {
                    provider.getTvShow(tvShowId)
                }.getOrNull()?.also { fetchedTvShow ->
                    continueWatchingTvShowCache[tvShowId] = fetchedTvShow
                }

                val mergedTvShow = resolvedTvShow?.copy().apply {
                    this?.let { show ->
                        episode.tvShow?.let { existingTvShow -> show.merge(existingTvShow) }
                    }
                } ?: episode.tvShow

                val resolvedSeason = episode.season?.let { season ->
                    mergedTvShow?.seasons?.firstOrNull { it.id == season.id || it.number == season.number }
                        ?: season
                }

                val resolvedEpisode = if (UserPreferences.enableTmdb) {
                    val seasonId = resolvedSeason?.id
                        ?: episode.season?.id
                    seasonId?.let { key ->
                        continueWatchingSeasonEpisodesCache[key] ?: runCatching {
                            provider.getEpisodesBySeason(key)
                        }.getOrDefault(emptyList()).also { fetchedEpisodes ->
                            if (fetchedEpisodes.isNotEmpty()) {
                                continueWatchingSeasonEpisodesCache[key] = fetchedEpisodes
                            }
                        }
                    }?.firstOrNull { seasonEpisode ->
                        seasonEpisode.id == episode.id || seasonEpisode.number == episode.number
                    }
                } else {
                    null
                }

                episode.copy(
                    title = resolvedEpisode?.title ?: episode.title,
                    overview = resolvedEpisode?.overview ?: episode.overview,
                    poster = resolvedEpisode?.poster ?: episode.poster,
                    tvShow = mergedTvShow,
                    season = resolvedSeason,
                ).apply {
                    merge(episode)
                }
            }
        }.awaitAll()
    }

    fun getHome() = viewModelScope.launch(Dispatchers.IO) {
        val provider = UserPreferences.currentProvider ?: run {
            _state.emit(State.FailedLoading(IllegalStateException("No provider selected")))
            return@launch
        }


        currentProvider = provider
        val appContext = StreamFlixApp.instance.applicationContext
        val cachedCategories = HomeCacheStore.read(appContext, provider)
        val deferCachedHomeForClearance =
                provider === AnimeOnlineNinjaProvider &&
                        !AnimeOnlineNinjaProvider.hasCurrentClearanceCookie()
        if (!cachedCategories.isNullOrEmpty() && !deferCachedHomeForClearance) {
            _state.emit(State.SuccessLoading(cachedCategories))
        } else {
            _state.emit(State.Loading)
        }

        loadUserDataCache(provider)

        try {
            val titles = tmdbSectionTitles(appContext)
            // Secciones TMDb ya calculadas (de la caché) para que no parpadeen al refrescar.
            val previousSections = cachedCategories.orEmpty().filter { it.name in titles.all }
            val categories = provider.getHome()
            if (categories.none { it.list.isNotEmpty() }) throw Exception("Inicio vacío")
            com.streamflixreborn.streamflix.utils.AssignedProviders.onHomeProviderLoaded(provider, appContext)
            val withPrevious = withTmdbSections(categories, previousSections)
            HomeCacheStore.write(appContext, provider, withPrevious)
            _state.emit(State.SuccessLoading(withPrevious))

            if (TmdbArtwork.isAvailable(provider)) {
                // Arte y sinopsis en alta calidad desde TMDb (clave del panel); se muestra al llegar.
                val enriched = TmdbArtwork.enrichedCategories(provider, categories)
                if (currentProvider === provider && UserPreferences.currentProvider === provider) {
                    _state.emit(State.SuccessLoading(withTmdbSections(enriched, previousSections)))
                }

                // Lo más visto / tendencias / mejor valoradas de TMDb, solo con títulos que tenga el proveedor.
                // Se regeneran como máximo cada 6 h (mientras tanto se usan las de la caché).
                val lastBuild = UserPreferences.getProviderCache(provider, TMDB_SECTIONS_AT).toLongOrNull() ?: 0L
                val isFresh = previousSections.isNotEmpty() && System.currentTimeMillis() - lastBuild < TMDB_SECTIONS_TTL_MS
                val sections = if (isFresh) previousSections else runCatching { TmdbHomeSections.build(provider, titles) }
                    .onFailure { Log.e("HomeViewModel", "tmdb sections: ", it) }
                    .getOrDefault(emptyList())
                    .also { built ->
                        if (built.isNotEmpty()) UserPreferences.setProviderCache(provider, TMDB_SECTIONS_AT, System.currentTimeMillis().toString())
                    }
                    .ifEmpty { previousSections }
                if (currentProvider === provider && UserPreferences.currentProvider === provider) {
                    val finalCategories = withTmdbSections(enriched, sections)
                    HomeCacheStore.write(appContext, provider, finalCategories)
                    _state.emit(State.SuccessLoading(finalCategories))
                }
            }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "getHome: ", e)
            // Proveedor caído: el inicio se dibuja con el siguiente proveedor permitido.
            if (com.streamflixreborn.streamflix.utils.AssignedProviders.onHomeProviderFailed(provider, "Inicio", e, appContext)) return@launch
            if (cachedCategories.isNullOrEmpty()) {
                com.streamflixreborn.streamflix.utils.ErrorReporter.report("HomeViewModel.FailedLoading", e.message, e)
                _state.emit(State.FailedLoading(e))
            } else if (deferCachedHomeForClearance) {
                _state.emit(State.SuccessLoading(cachedCategories))
            }
        }
    }

    private fun loadUserDataCache(provider: Provider) {
        val appContext = StreamFlixApp.instance.applicationContext
        val cached = UserDataCache.read(appContext, provider)
        _userDataCache.value = cached

        viewModelScope.launch(Dispatchers.IO) {
            val db = AppDatabase.getInstance(appContext)
            val moviesDeferred = async { db.movieDao().getFavorites().first() }
            val tvShowsDeferred = async { db.tvShowDao().getFavorites().first() }
            val watchingMoviesDeferred = async { db.movieDao().getWatchingMovies().first() }
            val watchingEpisodesDeferred = async { db.episodeDao().getWatchingEpisodes().first() }

            val movies = moviesDeferred.await()
            val tvShows = tvShowsDeferred.await()
            val watchingMovies = watchingMoviesDeferred.await()
            val watchingEpisodes = watchingEpisodesDeferred.await()

            val newData = UserDataCache.UserData(
                favoritesMovies = preserveCacheOrder(
                    cached = cached?.favoritesMovies ?: emptyList(),
                    incoming = movies.filter { it.isFavorite }.map { it.toCached() },
                    idOf = { it.id },
                ),
                favoritesTvShows = preserveCacheOrder(
                    cached = cached?.favoritesTvShows ?: emptyList(),
                    incoming = tvShows.filter { it.isFavorite }.map { it.toCached() },
                    idOf = { it.id },
                ),
                continueWatchingMovies = preserveCacheOrder(
                    cached = cached?.continueWatchingMovies ?: emptyList(),
                    incoming = (movies + watchingMovies)
                        .filter { it.watchHistory != null }
                        .map { it.toCached() },
                    idOf = { it.id },
                ),
                continueWatchingEpisodes = preserveCacheOrder(
                    cached = cached?.continueWatchingEpisodes ?: emptyList(),
                    incoming = watchingEpisodes
                        .filter { it.watchHistory != null }
                        .map { it.toCached() },
                    idOf = { it.id },
                ),
            )

            UserDataCache.write(appContext, provider, newData)

            if (_userDataCache.value != newData) {
                _userDataCache.value = newData
            }
        }
    }

    private fun tmdbSectionTitles(context: android.content.Context) = TmdbHomeSections.Titles(
        trending = context.getString(com.streamflixreborn.streamflix.R.string.home_tmdb_trending),
        popularMovies = context.getString(com.streamflixreborn.streamflix.R.string.home_tmdb_popular_movies),
        topRatedMovies = context.getString(com.streamflixreborn.streamflix.R.string.home_tmdb_top_rated_movies),
        popularSeries = context.getString(com.streamflixreborn.streamflix.R.string.home_tmdb_popular_series),
        topRatedSeries = context.getString(com.streamflixreborn.streamflix.R.string.home_tmdb_top_rated_series),
    )

    /** Inserta las secciones TMDb justo después de "Destacados" (sin duplicarlas). */
    private fun withTmdbSections(categories: List<Category>, sections: List<Category>): List<Category> {
        if (sections.isEmpty()) return categories
        val names = sections.map { it.name }.toSet()
        val base = categories.filter { it.name !in names }
        val featuredIndex = base.indexOfFirst { it.name == Category.FEATURED }
        return if (featuredIndex >= 0) {
            base.take(featuredIndex + 1) + sections + base.drop(featuredIndex + 1)
        } else {
            sections + base
        }
    }

    companion object {
        private const val TMDB_SECTIONS_AT = "TMDB_SECTIONS_AT"
        private const val TMDB_SECTIONS_TTL_MS = 6 * 60 * 60 * 1000L
    }

    /**
     * Canal en vivo guardado por versiones anteriores: el "episodio" de un canal tiene el mismo id
     * que su programa (o la temporada "Live"). No va en "Continuar viendo" ni en vistos.
     */
    private fun isLiveChannel(item: AppAdapter.Item): Boolean = when (item) {
        is Episode -> item.id == item.tvShow?.id || item.season?.title == "Live"
        is TvShow -> item.lastPlayedEpisodeId == item.id
        else -> false
    }

}
