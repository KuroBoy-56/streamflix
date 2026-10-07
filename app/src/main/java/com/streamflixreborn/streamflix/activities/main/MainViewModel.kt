package com.streamflixreborn.streamflix.activities.main

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.utils.InAppUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import com.streamflixreborn.streamflix.utils.UserPreferences
import java.io.File

class MainViewModel : ViewModel() {

    private val _state = MutableStateFlow<State>(State.CheckingUpdate)
    val state: Flow<State> = _state

    sealed class State {
        data object CheckingUpdate : State()
        // Reemplazamos los moldes de GitHub por los nuestros
        data class SuccessCheckingUpdate(val newReleases: List<InAppUpdater.DummyRelease>, val asset: InAppUpdater.DummyAsset) : State()

        data object DownloadingUpdate : State()
        data class SuccessDownloadingUpdate(val apk: File) : State()

        data object InstallingUpdate : State()

        data class FailedUpdate(val error: Exception) : State()
    }

    fun checkUpdate() = viewModelScope.launch(Dispatchers.IO) {
        if (!UserPreferences.updateCheckEnabled) return@launch
        _state.emit(State.CheckingUpdate)

        try {
            // Usamos el contexto global de tu StreamFlixApp para no romper MainActivity
            val context = StreamFlixApp.instance.applicationContext
            val newReleases = InAppUpdater.getNewReleases(context)
            if (newReleases.isEmpty()) return@launch

            // Simplemente buscamos el archivo .apk que enviamos desde el InAppUpdater
            val asset = newReleases.first().assets.firstOrNull { it.name.endsWith(".apk") }
                ?: throw Exception("Can't find update APK")

            _state.emit(State.SuccessCheckingUpdate(newReleases, asset))
        } catch (e: Exception) {
            Log.e("MainViewModel", "checkUpdate: ", e)
            _state.emit(State.FailedUpdate(e))
        }
    }

    fun downloadUpdate(
        context: Context,
        asset: InAppUpdater.DummyAsset, // Molde actualizado
    ) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.DownloadingUpdate)

        try {
            val apk = InAppUpdater.downloadApk(context, asset)
            _state.emit(State.SuccessDownloadingUpdate(apk))
        } catch (e: Exception) {
            Log.e("MainViewModel", "downloadUpdate: ", e)
            _state.emit(State.FailedUpdate(e))
        }
    }

    fun installUpdate(
        context: Context,
        apk: File,
    ) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.InstallingUpdate)

        try {
            InAppUpdater.installApk(context, Uri.fromFile(apk))
        } catch (e: Exception) {
            Log.e("MainViewModel", "installUpdate: ", e)
            _state.emit(State.FailedUpdate(e))
        }
    }
}