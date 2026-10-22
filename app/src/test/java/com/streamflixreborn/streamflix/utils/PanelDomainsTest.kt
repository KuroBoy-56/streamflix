package com.streamflixreborn.streamflix.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PanelDomainsTest {

    @Test
    fun panelDomainReplacesTheAppDomainWithOrWithoutWww() {
        val rewrites = PanelDomains.buildRewrites(mapOf("cuevana3" to "wv4.cuevana3.eu", "lacartoons" to "lacartoons.net"))
        assertEquals("wv4.cuevana3.eu", PanelDomains.newHostFor("wv3.cuevana3.eu", rewrites))
        assertEquals("lacartoons.net", PanelDomains.newHostFor("www.lacartoons.com", rewrites))
        assertEquals("lacartoons.net", PanelDomains.newHostFor("lacartoons.com", rewrites))
    }

    @Test
    fun otherHostsUnknownKeysAndSameDomainAreLeftAlone() {
        val rewrites = PanelDomains.buildRewrites(mapOf("noexiste" to "x.com", "cinecalidad" to "cinecalidad.am", "cine24h" to "cine24h.online"))
        assertNull(PanelDomains.newHostFor("image.tmdb.org", rewrites))
        assertNull(PanelDomains.newHostFor("cine24h.online", rewrites))
        assertEquals(emptyMap<String, String>(), rewrites)
    }
}
