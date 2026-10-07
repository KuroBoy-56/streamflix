package com.streamflixreborn.streamflix.fragments.providers

import android.app.Application
import android.content.Context
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.models.Provider as ModelProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Locale

// ⚡️ Cambiado a AndroidViewModel para poder leer la configuración de tu Gateway
class ProvidersViewModel(application: Application) : AndroidViewModel(application) {

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: Flow<State> = _state

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val providers: List<ModelProvider>) : State()
        data class FailedLoading(val error: Exception) : State()
    }

    init {
        getProviders(UserPreferences.providerLanguage)
    }

    fun getProviders(language: String? = null) = viewModelScope.launch(Dispatchers.IO) {
        _state.emit(State.Loading)

        try {
            val isFavoritesFilter = language == "favorites"
            val favorites = UserPreferences.favoriteProviders

            // ⚡️ LECTURA ESTRICTA: Solo sacar los servidores autorizados por tu Panel Web
            val prefs = getApplication<Application>().getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
            val assignedStr = prefs.getString("assigned_servers", "") ?: ""
            val assignedList = assignedStr.split(",").map { it.trim() }.filter { it.isNotEmpty() }

            val allProviders = Provider.providers.keys.toList()

            // Si el panel envió servidores, filtramos a la fuerza. Si no, muestra la base.
            val baseProviders = if (assignedList.isNotEmpty()) {
                allProviders.filter { assignedList.contains(it.name) }
            } else {
                allProviders
            }

            // ⚡️ SE ELIMINARON LOS TMDb EXTRAS PARA DEJAR UNA LISTA LIMPIA
            val finalProviders = baseProviders
                .filter {
                    if (isFavoritesFilter) {
                        favorites.contains(it.name)
                    } else {
                        language == null || it.language == language
                    }
                }

            val modelProviders = finalProviders.map {
                ModelProvider(
                    name = it.name,
                    logo = it.logo,
                    language = it.language,
                    provider = it,
                    isFavorite = favorites.contains(it.name)
                )
            }.sortedBy { it.name.lowercase(Locale.ROOT) }

            _state.emit(State.SuccessLoading(modelProviders))
        } catch (e: Exception) {
            Log.e("ProvidersViewModel", "getProviders: ", e)
            _state.emit(State.FailedLoading(e))
        }
    }
}