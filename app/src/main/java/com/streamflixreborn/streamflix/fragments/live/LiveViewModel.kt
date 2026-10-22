package com.streamflixreborn.streamflix.fragments.live

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.HomeCacheStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Contenido de la sección "En Vivo": usa directamente el proveedor de canales en vivo,
 * sin tocar el proveedor ni la base de datos del inicio.
 */
class LiveViewModel : ViewModel() {

    sealed class State {
        data object Loading : State()
        data class SuccessLoading(val provider: Provider, val categories: List<Category>) : State()
        data class FailedLoading(val provider: Provider, val error: Exception) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state

    private var job: Job? = null
    private var loadedProvider: Provider? = null

    fun load(provider: Provider, force: Boolean = false) {
        if (!force && provider == loadedProvider && _state.value is State.SuccessLoading) return
        loadedProvider = provider

        job?.cancel()
        job = viewModelScope.launch(Dispatchers.IO) {
            val context = StreamFlixApp.instance.applicationContext
            val cached = HomeCacheStore.read(context, provider)?.let(::clean)
            if (!cached.isNullOrEmpty()) {
                LiveSession.remember(provider, cached)
                _state.emit(State.SuccessLoading(provider, cached))
            } else {
                _state.emit(State.Loading)
            }

            try {
                val categories = provider.getHome()
                HomeCacheStore.write(context, provider, categories)
                val cleaned = clean(categories)
                LiveSession.remember(provider, cleaned)
                _state.emit(State.SuccessLoading(provider, cleaned))
            } catch (e: Exception) {
                Log.e("LiveViewModel", "load(${provider.name}): ", e)
                if (cached.isNullOrEmpty()) _state.emit(State.FailedLoading(provider, e))
            }
        }
    }

    private fun clean(categories: List<Category>): List<Category> {
        val blacklisted = listOf("soporte", "ayuda", "support", "help", "telegram", "discord", "whatsapp")
        return categories.filter { category ->
            category.list.isNotEmpty() &&
                    category.name != Category.CONTINUE_WATCHING &&
                    category.name != Category.RECENTLY_WATCHED &&
                    category.name != Category.FAVORITES &&
                    !blacklisted.any { category.name.contains(it, ignoreCase = true) }
        }
    }
}
