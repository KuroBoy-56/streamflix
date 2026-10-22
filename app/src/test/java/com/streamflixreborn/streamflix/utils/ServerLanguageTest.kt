package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.models.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ServerLanguageTest {

    @Test
    fun detectsLanguageFromProviderServerNames() {
        assertEquals(ServerLanguage.LATINO, ServerLanguage.of("Streamwish | Latino | HD"))
        assertEquals(ServerLanguage.LATINO, ServerLanguage.of("Voe [LAT]"))
        assertEquals(ServerLanguage.CASTELLANO, ServerLanguage.of("Filemoon | Spanish | HD"))
        assertEquals(ServerLanguage.SUBTITULADO, ServerLanguage.of("Doodstream sub"))
        assertEquals(ServerLanguage.INGLES, ServerLanguage.of("Vidhide | English | HD"))
        assertNull(ServerLanguage.of("Server 1"))
        assertNull(ServerLanguage.of("Fembed en HD"))
    }

    @Test
    fun latinoGoesFirstKeepingTheRestInOrder() {
        val servers = listOf("A | English", "B | Spanish", "C", "D | Latino", "E [LAT]").map { Video.Server(id = it, name = it) }
        assertEquals(listOf("D | Latino", "E [LAT]", "B | Spanish", "C", "A | English"), ServerLanguage.latinoFirst(servers).map { it.name })
    }
}
