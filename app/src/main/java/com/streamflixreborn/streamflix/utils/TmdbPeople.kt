package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.People
import com.streamflixreborn.streamflix.models.Show
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.utils.TMDb3.w500
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.text.Normalizer

/**
 * Información del elenco desde TMDb (foto, biografía, nacimiento) para cualquier proveedor.
 * La filmografía mostrada se limita a títulos que el proveedor tiene, para que se puedan abrir.
 */
object TmdbPeople {

    private const val MAX_FILMOGRAPHY_LOOKUPS = 30

    data class Info(
        val tmdbId: Int,
        val name: String,
        val image: String?,
        val biography: String?,
        val placeOfBirth: String?,
        val birthday: String?,
        val deathday: String?,
        val credits: List<TmdbHomeSections.Candidate>,
    )

    suspend fun find(provider: Provider?, name: String): Info? {
        if (!TmdbArtwork.isAvailable(provider) || name.isBlank()) return null
        val language = TmdbArtwork.languageFor(provider)
        val wanted = normalize(name)

        val person = runCatching { TMDb3.Search.multi(name, language = language).results }
            .getOrDefault(emptyList())
            .filterIsInstance<TMDb3.Person>()
            .sortedWith(
                compareByDescending<TMDb3.Person> { normalize(it.name) == wanted }
                    .thenByDescending { it.knownForDepartment == TMDb3.Person.Department.ACTING }
                    .thenByDescending { it.popularity }
            )
            .firstOrNull { normalize(it.name) == wanted || normalize(it.name).contains(wanted) }
            ?: return null

        val detail = runCatching {
            TMDb3.People.details(
                personId = person.id,
                appendToResponse = listOf(TMDb3.Params.AppendToResponse.Person.COMBINED_CREDITS),
                language = language,
            )
        }.getOrNull() ?: return null

        // Si no hay biografía traducida, se usa la de inglés.
        val biography = detail.biography?.takeIf { it.isNotBlank() }
            ?: runCatching { TMDb3.People.details(person.id, language = "en-US").biography }.getOrNull()

        val credits = with(TmdbHomeSections) {
            detail.combinedCredits?.cast.orEmpty()
                .mapNotNull { item ->
                    when (item) {
                        is TMDb3.Movie -> item.toCandidate()
                        is TMDb3.Tv -> item.toCandidate()
                        else -> null
                    }
                }
                .sortedByDescending { it.rating }
        }

        return Info(
            tmdbId = detail.id,
            name = detail.name,
            image = detail.profilePath?.w500,
            biography = biography,
            placeOfBirth = detail.placeOfBirth,
            birthday = detail.birthday,
            deathday = detail.deathday,
            credits = credits,
        )
    }

    /** Une los datos del proveedor con los de TMDb (TMDb completa lo que falte). */
    fun merge(providerPeople: People?, info: Info, fallbackName: String): People = People(
        id = providerPeople?.id ?: info.tmdbId.toString(),
        name = providerPeople?.name?.takeIf { it.isNotBlank() } ?: info.name.ifBlank { fallbackName },
        image = providerPeople?.image?.takeIf { it.isNotBlank() } ?: info.image,
        biography = info.biography?.takeIf { it.isNotBlank() } ?: providerPeople?.biography,
        placeOfBirth = info.placeOfBirth ?: providerPeople?.placeOfBirth,
        birthday = info.birthday ?: providerPeople?.birthday?.format("yyyy-MM-dd"),
        deathday = info.deathday ?: providerPeople?.deathday?.format("yyyy-MM-dd"),
        filmography = providerPeople?.filmography.orEmpty(),
    )

    /** Filmografía de TMDb filtrada a lo que el proveedor tiene. */
    suspend fun filmographyOnProvider(provider: Provider, info: Info): List<Show> = coroutineScope {
        val limiter = Semaphore(4)
        info.credits.take(MAX_FILMOGRAPHY_LOOKUPS)
            .map { candidate -> async { limiter.withPermit { TmdbHomeSections.findOnProvider(provider, candidate) } } }
            .awaitAll()
            .filterIsInstance<Show>()
            .distinct()
    }

    private fun normalize(value: String) = Normalizer.normalize(value.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}"), "")
        .replace(Regex("[^a-z0-9]"), "")
}
