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
        #EXTINF:-1 tvg-id="Tudn.mx" group-title="",TUDN
        http://b/tudn
        #EXTINF:-1 group-title="MX | Deportes",MX: Afizzionados
        http://b/afi
        #EXTINF:-1 group-title="XXX",Adult
        http://b/x
    """.trimIndent()

    private fun merged() = p.merge(listOf(p.clean(p.parseM3u(defaultLine)), p.clean(p.parseM3u(otherLine))))

    @Test fun defaultLineWinsForSameChannel() {
        val espn2 = merged().filter { it.name.startsWith("ESPN 2") }
        assertEquals(1, espn2.size)
        assertEquals("http://a/espn2", espn2[0].url)
    }

    @Test fun missingChannelGoesToItsBrandFolder() {
        val espn7 = merged().single { it.name == "ESPN 7" }
        assertEquals("ESPN", espn7.group)
    }

    @Test fun onlySportsChannelsAreKept() {
        val names = merged().map { it.name }
        assertTrue("CNN" !in names && "Milenio" !in names && "Canal 5" !in names)
    }

    @Test fun countryPrefixIsDetectedAndRemoved() {
        val afi = merged().single { it.name == "Afizzionados" }
        assertTrue(afi.country.endsWith("México"))
        assertTrue(merged().single { it.name == "TUDN" }.country.endsWith("México"))
    }

    @Test fun adultChannelsAreDropped() {
        assertTrue(merged().none { it.name == "Adult" })
    }

    @Test fun brandFolderFromName() {
        fun group(name: String, group: String = "") =
            p.clean(p.parseM3u("#EXTINF:-1 group-title=\"$group\",$name\nhttp://c/x")).single().group
        assertEquals("Fox Sports", group("Fox Sports 2"))
        assertEquals("DAZN", group("DAZN 1 HD", "Deportes"))
        assertEquals("Sky Sports", group("Sky Sports Premier League"))
        assertEquals("NBA", group("NBA TV"))
        assertEquals("TyC Sports", group("TyC Sports"))
        assertEquals("Otros deportes", group("Afizzionados", "Deportes"))
        assertEquals("ESPN", group("Canal 7", "ESPN Premium"))
    }

    @Test fun tvgIdIsKeptForEpg() {
        val ch = p.clean(p.parseM3u("#EXTM3U url-tvg=\"http://epg/guide.xml.gz\"\n#EXTINF:-1 tvg-id=\"ESPN.mx\" group-title=\"General\",ESPN\nhttp://c/5")).single()
        assertEquals("ESPN.mx", ch.epgId)
    }

    @Test fun nowAndNextProgramme() {
        val epg = com.streamflixreborn.streamflix.utils.LiveEpg
        val list = listOf(
            com.streamflixreborn.streamflix.utils.LiveEpg.Programme(0, 100, "A"),
            com.streamflixreborn.streamflix.utils.LiveEpg.Programme(100, 200, "B"),
            com.streamflixreborn.streamflix.utils.LiveEpg.Programme(200, 300, "C"),
        )
        assertEquals("B" to "C", epg.nowAndNext(list, 150).let { it.first?.title to it.second?.title })
        assertEquals(null to "A", epg.nowAndNext(list, -5).let { it.first?.title to it.second?.title })
        assertEquals(null to null, epg.nowAndNext(list, 400).let { it.first?.title to it.second?.title })
    }
}
