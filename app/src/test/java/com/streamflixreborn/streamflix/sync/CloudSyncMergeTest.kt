package com.streamflixreborn.streamflix.sync

import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.WatchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fusión de tres vías: lo que otro equipo (Roku, iPhone) escribió no se pierde ni revive. */
class CloudSyncMergeTest {

    private fun movie(id: String, engagedAt: Long? = null, favoriteAt: Long? = null) = Movie(id = id, title = id).apply {
        if (engagedAt != null) watchHistory = WatchItem.WatchHistory(engagedAt, 60_000L, 600_000L)
        if (favoriteAt != null) {
            isFavorite = true
            favoritedAtMillis = favoriteAt
        }
    }

    private fun doc(vararg movies: Movie) = movies.associate { it.id to CloudSyncManager.objectToMap(it) }

    private fun merge(local: MutableMap<String, Movie>, remote: Map<*, *>, base: Map<*, *>?) =
        CloudSyncManager.mergeKind(CloudSyncManager.movieKind, remote, base, { local[it] }, { local[it.id] = it })

    @Test
    fun recordAddedByAnotherDevice_isApplied() {
        val local = mutableMapOf("a" to movie("a", engagedAt = 100))
        val changed = merge(local, doc(movie("a", engagedAt = 100), movie("roku", engagedAt = 200)), doc(movie("a", engagedAt = 100)))
        assertEquals(listOf("roku"), changed.map { it.id })
        assertEquals(200L, local["roku"]?.watchHistory?.lastEngagementTimeUtcMillis)
    }

    @Test
    fun newerProgressFromAnotherDevice_winsOverOlderLocal() {
        val local = mutableMapOf("a" to movie("a", engagedAt = 100))
        merge(local, doc(movie("a", engagedAt = 300)), doc(movie("a", engagedAt = 100)))
        assertEquals(300L, local["a"]?.watchHistory?.lastEngagementTimeUtcMillis)
    }

    @Test
    fun localRemoval_isNotRevivedByUnchangedCloud() {
        // Se quitó aquí de "Continuar viendo"; la nube aún lo tiene igual que la última vez.
        val local = mutableMapOf("a" to movie("a"))
        val changed = merge(local, doc(movie("a", engagedAt = 100)), doc(movie("a", engagedAt = 100)))
        assertTrue(changed.isEmpty())
        assertNull(local["a"]?.watchHistory)
    }

    @Test
    fun removalOnAnotherDevice_isApplied() {
        val local = mutableMapOf("a" to movie("a", favoriteAt = 100))
        val changed = merge(local, emptyMap<String, Any?>(), doc(movie("a", favoriteAt = 100)))
        assertEquals(1, changed.size)
        assertFalse(local["a"]!!.isFavorite)
    }

    @Test
    fun removalOnAnotherDevice_keepsNewerLocalChange() {
        val local = mutableMapOf("a" to movie("a", favoriteAt = 500))
        merge(local, emptyMap<String, Any?>(), doc(movie("a", favoriteAt = 100)))
        assertTrue(local["a"]!!.isFavorite)
    }

    @Test
    fun firstSync_newestWins() {
        val local = mutableMapOf("a" to movie("a", engagedAt = 500))
        merge(local, doc(movie("a", engagedAt = 100)), null)
        assertEquals(500L, local["a"]?.watchHistory?.lastEngagementTimeUtcMillis)
    }
}
