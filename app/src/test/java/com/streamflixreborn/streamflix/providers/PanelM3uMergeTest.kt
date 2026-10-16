package com.streamflixreborn.streamflix.providers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PanelM3uMergeTest {

    private val p = PanelLiveProvider

    private val defaultLine = """
        #EXTM3U
        #EXTINF:-1 group-title="Deportes",ESPN 2 HD
        http://a/espn2
        #EXTINF:-1 group-title="Deportes",ESPN
        http://a/espn
        #EXTINF:-1 group-title="Deportes",ESPN 3
        http://a/espn3
        #EXTINF:-1 group-title="Noticias",CNN
        http://a/cnn
        #EXTINF:-1 group-title="Noticias",Milenio
        http://a/milenio
        #EXTINF:-1 group-title="Noticias",N+
        http://a/nmas
    """.trimIndent()

    private val otherLine = """
        #EXTM3U
        #EXTINF:-1 group-title="Sports",ESPN 2
        http://b/espn2
        #EXTINF:-1 group-title="Sports",ESPN 7
        http://b/espn7
        #EXTINF:-1 tvg-id="Canal5.mx" group-title="",Canal 5
        http://b/c5
        #EXTINF:-1 group-title="MX | Noticias",MX: Foro TV
        http://b/foro
        #EXTINF:-1 group-title="XXX",Adult
        http://b/x
    """.trimIndent()

    private fun merged() = p.merge(listOf(p.clean(p.parseM3u(defaultLine)), p.clean(p.parseM3u(otherLine))))

    @Test fun defaultLineWinsForSameChannel() {
        val espn2 = merged().filter { it.name.startsWith("ESPN 2") }
        assertEquals(1, espn2.size)
        assertEquals("http://a/espn2", espn2[0].url)
    }

    @Test fun missingChannelGoesToItsBrandCategory() {
        val espn7 = merged().single { it.name == "ESPN 7" }
        assertEquals("Deportes", espn7.group)
    }

    @Test fun countryPrefixIsDetectedAndRemoved() {
        val foro = merged().single { it.name == "Foro TV" }
        assertEquals("Noticias", foro.group)
        assertTrue(foro.country.endsWith("México"))
        assertTrue(merged().single { it.name == "Canal 5" }.country.endsWith("México"))
    }

    @Test fun adultChannelsAreDropped() {
        assertTrue(merged().none { it.name == "Adult" })
    }

    @Test fun groupInferredFromNameWhenMissing() {
        val ch = p.clean(p.parseM3u("#EXTINF:-1,Fox Sports 2\nhttp://c/fs2")).single()
        assertEquals("Deportes", ch.group)
    }
}
