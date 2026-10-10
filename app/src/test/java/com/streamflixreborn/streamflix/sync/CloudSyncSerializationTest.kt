package com.streamflixreborn.streamflix.sync

import com.google.gson.Gson
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.Season
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.models.WatchItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * Verifica el ciclo completo que hace la nube: objeto -> mapa -> JSON subido ->
 * JSON descargado -> objeto, sin perder favoritos, progreso ni fechas.
 */
class CloudSyncSerializationTest {

    private fun roundTrip(providerKey: String, section: String, id: String, obj: Any): Any? {
        val document = mapOf(providerKey to mapOf(section to mapOf(id to CloudSyncManager.objectToMap(obj))))
        val uploaded = Gson().toJson(document)
        val parsed = CloudSyncManager.parseCloudDocument(uploaded)
        assertNotNull(parsed)
        val providerData = parsed!![providerKey] as Map<*, *>
        return (providerData[section] as Map<*, *>)[id]
    }

    @Test
    fun movie_keepsFavoriteAndProgress() {
        val movie = Movie(id = "https://site/pelicula/1", title = "Película", released = "2020-05-01", poster = "p.jpg").apply {
            isFavorite = true
            favoritedAtMillis = 1_791_648_700_123L
            lastPlayedAtMillis = 1_791_648_799_001L
            watchHistory = WatchItem.WatchHistory(1_791_648_700_999L, 3_723_456L, 7_200_000L)
        }

        val data = roundTrip("CineCalidad", "movies", movie.id, movie)
        val restored = CloudSyncManager.mapToObject(data, Movie::class.java)!!

        assertEquals(movie.id, restored.id)
        assertEquals(movie.title, restored.title)
        assertTrue(restored.isFavorite)
        assertEquals(1_791_648_700_123L, restored.favoritedAtMillis)
        assertEquals(1_791_648_799_001L, restored.lastPlayedAtMillis)
        assertEquals(movie.watchHistory, restored.watchHistory)
        assertEquals(2020, restored.released?.get(Calendar.YEAR))
    }

    @Test
    fun tvShow_keepsWatchingStateAndLastPlayed() {
        val tvShow = TvShow(id = "serie-1", title = "Serie").apply {
            isFavorite = false
            isWatching = false
            lastPlayedAtMillis = 1_791_000_000_000L
            lastPlayedEpisodeId = "ep-3"
        }

        val restored = CloudSyncManager.mapToObject(roundTrip("Pluto_TV_MX", "tv_shows", tvShow.id, tvShow), TvShow::class.java)!!

        assertEquals("serie-1", restored.id)
        assertEquals(false, restored.isWatching)
        assertEquals(1_791_000_000_000L, restored.lastPlayedAtMillis)
        assertEquals("ep-3", restored.lastPlayedEpisodeId)
    }

    @Test
    fun episode_keepsNumbersAndHistory() {
        val episode = Episode(id = "ep-3", number = 3, title = "Capítulo 3").apply {
            tvShow = TvShow("serie-1", "")
            season = Season("serie-1-2", 2)
            isWatched = true
            watchHistory = WatchItem.WatchHistory(1_791_648_700_999L, 61_000L, 2_400_000L)
        }

        val restored = CloudSyncManager.mapToObject(roundTrip("SoloLatino", "episodes", episode.id, episode), Episode::class.java)!!

        assertEquals(3, restored.number)
        assertTrue(restored.isWatched)
        assertEquals(episode.watchHistory, restored.watchHistory)
        assertEquals("serie-1", restored.tvShow?.id)
        assertEquals("serie-1-2", restored.season?.id)
    }

    @Test
    fun emptyAndInvalidResponses() {
        assertEquals(emptyMap<String, Any?>(), CloudSyncManager.parseCloudDocument("""{"status":"empty","data":[]}"""))
        assertEquals(emptyMap<String, Any?>(), CloudSyncManager.parseCloudDocument(""))
        assertNull(CloudSyncManager.parseCloudDocument("<html>403 Forbidden</html>"))
        assertEquals(emptyMap<String, Any?>(), CloudSyncManager.parseCloudDocument("{}"))
    }
}
