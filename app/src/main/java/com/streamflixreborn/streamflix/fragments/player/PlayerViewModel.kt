package com.streamflixreborn.streamflix.fragments.player

import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.CustomTabHelper
import com.streamflixreborn.streamflix.utils.EpisodeManager
import com.streamflixreborn.streamflix.utils.OpenSubtitles
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.format
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import com.streamflixreborn.streamflix.utils.SubDL

class PlayerViewModel(
    videoType: Video.Type,
    id: String,
) : ViewModel() {

    private val _state = MutableStateFlow<State>(State.LoadingServers)
    val state: Flow<State> = _state

    private val _subtitleState = MutableSharedFlow<SubtitleState>()
    val subtitleState: SharedFlow<SubtitleState> = _subtitleState

    /**
     * Proveedor elegido desde Ajustes > Proveedores. Si es null se usa el proveedor actual.
     * No cambia el proveedor del inicio: solo afecta a esta reproducción.
     */
    private var playbackProvider: Provider? = null

    val activeProvider: Provider?
        get() = playbackProvider ?: UserPreferences.currentProvider

    sealed class ProviderSwitchEvent {
        data class Searching(val providerName: String) : ProviderSwitchEvent()
        data class Switched(val providerName: String) : ProviderSwitchEvent()
        data class NotFound(val providerName: String) : ProviderSwitchEvent()
        /** Respaldo automático: el proveedor actual falló y se pasó a otro. */
        data class AutoFallback(val from: String, val to: String) : ProviderSwitchEvent()
    }

    /** Proveedores ya intentados en esta reproducción (para no repetir en el respaldo). */
    private val triedProviders = mutableSetOf<String>()
    private var fallbackJob: kotlinx.coroutines.Job? = null

    /**
     * Candidatos de respaldo: proveedores asignados por el panel (mismo tipo VOD / en vivo),
     * aún no intentados, ordenados por estabilidad.
     */
    private fun fallbackCandidates(): List<Provider> {
        val current = activeProvider ?: return emptyList()
        val isLive = current is com.streamflixreborn.streamflix.providers.IptvProvider
        if (isLive) return emptyList()
        triedProviders += current.name
        val assigned = com.streamflixreborn.streamflix.utils.AssignedProviders
        val pool = if (assigned.hasPanelAssignment()) {
            assigned.all()
        } else {
            // Sin panel: solo proveedores del mismo idioma, y pocos, para no demorar.
            Provider.providers.keys.filter { it.language == current.language }
        }
        return pool
            .filter { it !is com.streamflixreborn.streamflix.providers.IptvProvider }
            .filter { it.name !in triedProviders }
            .sortedByDescending { com.streamflixreborn.streamflix.utils.ProviderStability.score(it.name) }
            .let { if (assigned.hasPanelAssignment()) it else it.take(3) }
    }

    /**
     * Si el proveedor actual no logra reproducir, busca el mismo título en el siguiente
     * proveedor más estable y carga sus servidores. Devuelve false si no quedan proveedores.
     */
    fun tryNextProvider(): Boolean {
        if (fallbackJob?.isActive == true) return true
        val type = lastVideoType ?: return false
        val failed = activeProvider?.name
        com.streamflixreborn.streamflix.utils.ProviderStability.recordFailure(failed)
        val candidates = fallbackCandidates()
        if (candidates.isEmpty()) return false

        fallbackJob = viewModelScope.launch(Dispatchers.IO) {
            _state.emit(State.LoadingServers)
            for (target in candidates) {
                triedProviders += target.name
                _providerSwitchEvents.emit(ProviderSwitchEvent.Searching(target.name))
                val result = runCatching {
                    val (resolvedType, resolvedId) = findOnProvider(target, type) ?: return@runCatching null
                    val servers = target.getServers(resolvedId, resolvedType)
                    if (servers.isEmpty()) null else Triple(resolvedType, resolvedId, servers)
                }.onFailure { Log.w("PlayerViewModel", "fallback ${target.name}: ${it.message}") }
                    .getOrNull()
                if (result == null) {
                    com.streamflixreborn.streamflix.utils.ProviderStability.recordFailure(target.name)
                    continue
                }
                playbackProvider = target
                lastVideoType = result.first
                lastId = result.second
                _providerSwitchEvents.emit(ProviderSwitchEvent.AutoFallback(failed.orEmpty(), target.name))
                _state.emit(State.SuccessLoadingServers(result.third))
                return@launch
            }
            _state.emit(State.FailedLoadingServers(Exception("No se pudo reproducir en ningún proveedor")))
        }
        return true
    }

    private val _providerSwitchEvents = MutableSharedFlow<ProviderSwitchEvent>()
    val providerSwitchEvents: SharedFlow<ProviderSwitchEvent> = _providerSwitchEvents

    private val _playPreviousOrNextEpisode = MutableSharedFlow<Video.Type.Episode>()
    val playPreviousOrNextEpisode: SharedFlow<Video.Type.Episode> = _playPreviousOrNextEpisode
    init {
        getServers(videoType, id)
        getSubtitles(videoType)
    }

    fun playEpisode(direction: Direction) {
        val hasEpisode = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.hasPreviousEpisode()
            Direction.NEXT -> EpisodeManager.hasNextEpisode()
        }

        if (!hasEpisode) return

        val ep = when (direction) {
            Direction.PREVIOUS -> EpisodeManager.getPreviousEpisode()
            Direction.NEXT -> EpisodeManager.getNextEpisode()
        } ?: return

        val nextEpisode = Video.Type.Episode(
            id = ep.id,
            number = ep.number,
            title = ep.title,
            poster = ep.poster,
            overview = ep.overview,
            tvShow = Video.Type.Episode.TvShow(
                id = ep.tvShow.id,
                title = ep.tvShow.title,
                poster = ep.tvShow.poster,
                banner = ep.tvShow.banner,
                releaseDate = ep.tvShow.releaseDate,
                imdbId = ep.tvShow.imdbId
            ),
            season = Video.Type.Episode.Season(
                number = ep.season.number,
                title = ep.season.title
            )
        )

        playEpisode(nextEpisode)

        viewModelScope.launch {
            _playPreviousOrNextEpisode.emit(nextEpisode)
        }
    }

    enum class Direction { PREVIOUS, NEXT }
    fun playPreviousEpisode() =
        playEpisode(Direction.PREVIOUS)

    fun playNextEpisode() =
        playEpisode(Direction.NEXT)

    fun autoplayNextEpisode() {
        if (UserPreferences.autoplay) {
            playEpisode(Direction.NEXT)
        }
    }
    fun playEpisode(episode: Video.Type.Episode) {
        // Los siguientes episodios se resuelven con el proveedor del contenido original.
        playbackProvider = null
        triedProviders.clear()
        fallbackJob?.cancel()
        getServers(episode, episode.id)
        getSubtitles(episode)
    }

    private fun getServers(videoType: Video.Type, id: String) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca server per ID: $id")
        lastVideoType = videoType
        lastId = id
        _state.emit(State.LoadingServers)
        try {
            val servers = activeProvider!!.getServers(id, videoType)
            if (servers.isEmpty()) throw Exception("No servers found")
            
            // LOG POTENZIATO: Mostra tutti i server disponibili per il player
            Log.i("StreamFlixES", "[SERVERS LIST] -> Provider: ${activeProvider!!.name}")
            Log.i("StreamFlixES", "[SERVERS LIST] -> Found ${servers.size} servers: ${servers.joinToString { it.name }}")

            Log.d("PlayerViewModel", "Ricerca server completata: ${servers.size} server trovati")
            _state.emit(State.SuccessLoadingServers(servers))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore ricerca server: ", e)
            _state.emit(State.FailedLoadingServers(e))
        }
    }

    /**
     * Busca el mismo contenido en otro proveedor asignado por el panel y carga sus servidores.
     * Si no se encuentra, se mantiene la reproducción actual.
     */
    fun switchProvider(providerName: String) = viewModelScope.launch(Dispatchers.IO) {
        val type = lastVideoType ?: return@launch
        val target = Provider.findByName(providerName) ?: return@launch

        if (target.name == UserPreferences.currentProvider?.name) {
            playbackProvider = null
            _providerSwitchEvents.emit(ProviderSwitchEvent.Switched(target.name))
            lastId?.let { getServers(type, it) }
            return@launch
        }

        _providerSwitchEvents.emit(ProviderSwitchEvent.Searching(target.name))
        try {
            val (resolvedType, resolvedId) = findOnProvider(target, type)
                ?: throw Exception("Not found on ${target.name}")

            val servers = target.getServers(resolvedId, resolvedType)
            if (servers.isEmpty()) throw Exception("No servers found")

            playbackProvider = target
            lastVideoType = resolvedType
            lastId = resolvedId
            _providerSwitchEvents.emit(ProviderSwitchEvent.Switched(target.name))
            _state.emit(State.SuccessLoadingServers(servers))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "switchProvider(${target.name}): ", e)
            _providerSwitchEvents.emit(ProviderSwitchEvent.NotFound(target.name))
        }
    }

    private suspend fun findOnProvider(provider: Provider, type: Video.Type): Pair<Video.Type, String>? {
        fun normalize(value: String?) = value.orEmpty()
            .lowercase()
            .replace(Regex("\\(.*?\\)|\\[.*?]"), " ")
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()

        fun score(candidate: String, wanted: String): Int {
            val c = normalize(candidate)
            val w = normalize(wanted)
            return when {
                c.isEmpty() || w.isEmpty() -> 0
                c == w -> 3
                c.startsWith(w) || w.startsWith(c) -> 2
                c.contains(w) || w.contains(c) -> 1
                else -> 0
            }
        }

        return when (type) {
            is Video.Type.Movie -> {
                val movie = provider.search(type.title)
                    .filterIsInstance<Movie>()
                    .map { it to score(it.title, type.title) }
                    .filter { it.second > 0 }
                    .maxByOrNull { it.second }
                    ?.first
                    ?: return null
                type.copy(id = movie.id) to movie.id
            }

            is Video.Type.Episode -> {
                val match = provider.search(type.tvShow.title)
                    .filterIsInstance<TvShow>()
                    .map { it to score(it.title, type.tvShow.title) }
                    .filter { it.second > 0 }
                    .maxByOrNull { it.second }
                    ?.first
                    ?: return null

                val tvShow = provider.getTvShow(match.id)
                val season = tvShow.seasons.firstOrNull { it.number == type.season.number }
                    ?: tvShow.seasons.singleOrNull()
                    ?: return null
                val episodes = season.episodes.ifEmpty { provider.getEpisodesBySeason(season.id) }
                val episode = episodes.firstOrNull { it.number == type.number } ?: return null

                type.copy(
                    id = episode.id,
                    tvShow = type.tvShow.copy(id = tvShow.id),
                ) to episode.id
            }
        }
    }

    fun getVideo(server: Video.Server) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio estrazione video dal server: ${server.name}")
        _state.emit(State.LoadingVideo(server))
        try {
            val video = activeProvider!!.getVideo(server)
            if (video.source.isEmpty()) throw Exception("No source found")

            // LOGICA SOTTOTITOLI GLOBALE: 
            // Se il provider non ha già impostato un default (es. i "forced" in spagnolo),
            // allora proviamo ad attivare l'ultimo sottotitolo usato dall'utente.
            // MA: se siamo su un provider spagnolo e non ci sono forced, non dobbiamo attivare nulla.
            val currentProviderLang = activeProvider?.language ?: ""
            val hasDefaultAlready = video.subtitles.any { it.default }

            if (!hasDefaultAlready && currentProviderLang != "es") {
                if (!(video.useServerSubtitleSetting && UserPreferences.serverAutoSubtitlesDisabled)) {
                    video.subtitles
                        .firstOrNull { it.label.startsWith(UserPreferences.subtitleName ?: "") }
                        ?.default = true
		}
            }

            Log.d("PlayerViewModel", "Estrazione video completata con successo")
            com.streamflixreborn.streamflix.utils.ProviderStability.recordSuccess(activeProvider?.name)
            _state.emit(State.SuccessLoadingVideo(video, server))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore estrazione video: ", e)
            _state.emit(State.FailedLoadingVideo(e, server))
        }
    }

    fun getSubtitles(videoType: Video.Type) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio ricerca sottotitoli")
        _subtitleState.emit(SubtitleState.Loading)

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca OpenSubtitles")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        OpenSubtitles.search(
                            query = videoType.tvShow.title,
                            season = videoType.season.number,
                            episode = videoType.number,
                        )
                    }
                    is Video.Type.Movie -> {
                        OpenSubtitles.search(query = videoType.title)
                    }
                }.sortedWith(compareBy({ it.languageName }, { it.subDownloadsCnt }))
                
                Log.d("PlayerViewModel", "Ricerca OpenSubtitles completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessOpenSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore OpenSubtitles: ", e)
                _subtitleState.emit(SubtitleState.FailedOpenSubtitles(e))
            }
        }

        launch {
            try {
                Log.d("PlayerViewModel", "Inizio ricerca SubDL")
                val subtitles = when (videoType) {
                    is Video.Type.Episode -> {
                        SubDL.search(
                            filmName = videoType.tvShow.title,
                            seasonNumber = videoType.season.number,
                            episodeNumber = videoType.number,
                            type = "tv"
                        )
                    }
                    is Video.Type.Movie -> {
                        SubDL.search(
                            filmName = videoType.title,
                            type = "movie"
                        )
                    }
                }
                
                Log.d("PlayerViewModel", "Ricerca SubDL completata: ${subtitles.size} risultati")
                _subtitleState.emit(SubtitleState.SuccessSubDLSubtitles(subtitles))
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "Errore SubDL: ", e)
                _subtitleState.emit(SubtitleState.FailedSubDLSubtitles(e))
            }
        }
    }

    fun downloadSubtitle(subtitle: OpenSubtitles.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo OpenSubtitles: ${subtitle.subFileName}")
        _subtitleState.emit(SubtitleState.DownloadingOpenSubtitle)
        try {
            val uri = OpenSubtitles.download(subtitle)
            Log.d("PlayerViewModel", "Download OpenSubtitles completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingOpenSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download OpenSubtitles: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingOpenSubtitle(e, subtitle))
        }
    }

    fun downloadSubDLSubtitle(subtitle: SubDL.Subtitle) = viewModelScope.launch(Dispatchers.IO) {
        Log.d("PlayerViewModel", "Inizio download sottotitolo SubDL: ${subtitle.name}")
        _subtitleState.emit(SubtitleState.DownloadingSubDLSubtitle)
        try {
            val uri = SubDL.download(subtitle)
            Log.d("PlayerViewModel", "Download SubDL completato: $uri")
            _subtitleState.emit(SubtitleState.SuccessDownloadingSubDLSubtitle(subtitle, uri))
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "Errore download SubDL: ", e)
            _subtitleState.emit(SubtitleState.FailedDownloadingSubDLSubtitle(e, subtitle))
        }
    }

    sealed class State {
        data object LoadingServers : State()
        data class SuccessLoadingServers(val servers: List<Video.Server>) : State()
        data class FailedLoadingServers(val error: Exception) : State()
        data class LoadingVideo(val server: Video.Server) : State()
        data class SuccessLoadingVideo(val video: Video, val server: Video.Server) : State()
        data class FailedLoadingVideo(val error: Exception, val server: Video.Server) : State()
    }

    sealed class SubtitleState {
        data object Loading : SubtitleState()
        data class SuccessOpenSubtitles(val subtitles: List<OpenSubtitles.Subtitle>) : SubtitleState()
        data class FailedOpenSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingOpenSubtitle : SubtitleState()
        data class SuccessDownloadingOpenSubtitle(val subtitle: OpenSubtitles.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingOpenSubtitle(val error: Exception, val subtitle: OpenSubtitles.Subtitle) : SubtitleState()

        data class SuccessSubDLSubtitles(val subtitles: List<SubDL.Subtitle>) : SubtitleState()
        data class FailedSubDLSubtitles(val error: Exception) : SubtitleState()
        data object DownloadingSubDLSubtitle : SubtitleState()
        data class SuccessDownloadingSubDLSubtitle(val subtitle: SubDL.Subtitle, val uri: Uri) : SubtitleState()
        data class FailedDownloadingSubDLSubtitle(val error: Exception, val subtitle: SubDL.Subtitle) : SubtitleState()
    }
    private var lastVideoType: Video.Type? = null
    private var lastId: String? = null
    fun reloadServersAfterBypass() {
        val type = lastVideoType ?: return
        val id = lastId ?: return
        getServers(type, id)
    }
}
