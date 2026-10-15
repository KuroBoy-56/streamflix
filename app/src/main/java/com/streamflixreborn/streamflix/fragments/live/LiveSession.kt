package com.streamflixreborn.streamflix.fragments.live

import android.content.Context
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.streamflixreborn.streamflix.R
import android.os.Bundle
import com.streamflixreborn.streamflix.models.Category
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.Video
import com.streamflixreborn.streamflix.providers.PanelM3uProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.AssignedProviders
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp

/**
 * Sesión de la sección "En Vivo": mientras el usuario navega por ella (canal, temporada,
 * reproductor) el proveedor activo es el de canales en vivo; al volver a cualquier otra
 * sección principal se restaura el proveedor del inicio.
 */
object LiveSession {

    fun enter(context: Context): Provider? {
        val provider = AssignedProviders.currentLiveProvider(context) ?: return null
        UserPreferences.sessionProviderOverride = provider
        return provider
    }

    fun select(provider: Provider) {
        UserPreferences.liveProviderName = provider.name
        UserPreferences.sessionProviderOverride = provider
    }

    fun exit() {
        UserPreferences.sessionProviderOverride = null
    }

    // ---------------------------------------------------------------------------------------
    // Cambio de canal desde el reproductor (TV estilo TiviMate / celular con panel lateral)
    // ---------------------------------------------------------------------------------------

    @Volatile private var categories: List<Category> = emptyList()
    @Volatile private var categoriesProvider: String? = null

    /** Categorías que se ven en "En Vivo" (para cambiar de canal sin salir del reproductor). */
    fun remember(provider: Provider, list: List<Category>) {
        categoriesProvider = provider.name
        categories = list.filter { c -> c.list.any { it is TvShow } }
    }

    fun categories(provider: Provider?): List<Category> =
        if (provider != null && provider.name == categoriesProvider) categories else emptyList()

    /** Todos los canales de una categoría (las listas del panel se cargan completas). */
    suspend fun channels(provider: Provider, category: Category): List<TvShow> {
        val shown = category.list.filterIsInstance<TvShow>()
        if (provider !is PanelM3uProvider) return shown
        return runCatching { provider.groupShows(category.name) }.getOrNull()?.ifEmpty { null } ?: shown
    }

    // Categoría y canales completos del último cambio de canal en el reproductor: las filas de
    // "En Vivo" muestran solo los primeros canales y el elegido puede no estar entre ellos.
    @Volatile private var lastCategory: Category? = null
    @Volatile private var lastChannels: List<TvShow> = emptyList()

    fun rememberSelection(category: Category, channels: List<TvShow>) {
        lastCategory = category
        lastChannels = channels
    }

    fun categoryOf(provider: Provider?, channelId: String): Category? =
        lastCategory?.takeIf { provider?.name == categoriesProvider && lastChannels.any { it.id == channelId } }
            ?: categories(provider).firstOrNull { c -> c.list.any { (it as? TvShow)?.id == channelId } }

    /** Canales ya conocidos de la categoría (la lista completa si se eligió desde el reproductor). */
    fun knownChannels(category: Category): List<TvShow> =
        if (lastCategory?.name == category.name && lastChannels.isNotEmpty()) lastChannels
        else category.list.filterIsInstance<TvShow>()

    /** Argumentos del reproductor para un canal (igual que al abrirlo desde la lista). */
    fun playerArgs(show: TvShow): Bundle = Bundle().apply {
        putString("id", show.id)
        putString("title", show.title)
        putString("subtitle", show.title)
        putSerializable("videoType", videoType(show))
    }

    fun videoType(show: TvShow) = Video.Type.Episode(
        id = show.id,
        number = 1,
        title = show.title,
        poster = show.poster,
        overview = show.overview,
        tvShow = Video.Type.Episode.TvShow(
            id = show.id,
            title = show.title,
            poster = show.poster,
            banner = show.banner,
            releaseDate = null,
            imdbId = show.imdbId,
        ),
        season = Video.Type.Episode.Season(number = 1, title = "Live"),
    )

    /** Crea los chips del selector de proveedores en vivo (si hay más de uno). */
    fun bindProviderChips(
        container: LinearLayout,
        providers: List<Provider>,
        selected: Provider?,
        onSelected: (Provider) -> Unit,
    ) {
        container.removeAllViews()
        val context = container.context
        providers.forEach { provider ->
            container.addView(TextView(context).apply {
                text = provider.name
                isSelected = provider.name == selected?.name
                isFocusable = true
                isClickable = true
                gravity = Gravity.CENTER
                textSize = 13f
                setTextColor(ContextCompat.getColorStateList(context, R.color.live_provider_chip_text))
                background = ContextCompat.getDrawable(context, R.drawable.bg_live_provider_chip)
                setPadding(16.dp(context), 8.dp(context), 16.dp(context), 8.dp(context))
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = 8.dp(context) }
                setOnClickListener {
                    for (i in 0 until container.childCount) container.getChildAt(i).isSelected = false
                    isSelected = true
                    onSelected(provider)
                }
            })
        }
    }
}
