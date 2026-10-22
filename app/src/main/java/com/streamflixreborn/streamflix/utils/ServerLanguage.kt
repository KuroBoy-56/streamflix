package com.streamflixreborn.streamflix.utils

import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.models.Video

/**
 * Idioma de un servidor según el nombre que le da el proveedor ("Streamwish | Latino | HD",
 * "Voe [LAT]", "Filemoon sub"). Se muestra como "Servidor 1 · Latino" sin el nombre del sitio,
 * igual que en Roku y iPhone; el español latino va primero.
 */
enum class ServerLanguage(val label: Int) {
    LATINO(R.string.server_lang_latino),
    CASTELLANO(R.string.server_lang_castellano),
    SUBTITULADO(R.string.server_lang_subtitulado),
    INGLES(R.string.server_lang_ingles);

    companion object {
        private val latino = Regex("""latino|latam|\blat\b""")
        private val castellano = Regex("""castellano|spanish|español|espanol|\bcast\b|\besp\b""")
        private val subtitulado = Regex("""subtitulado|\bsubs?\b|vose""")
        private val ingles = Regex("""english|ingl[eé]s|\beng\b""")

        fun of(serverName: String): ServerLanguage? {
            val name = serverName.lowercase()
            return when {
                latino.containsMatchIn(name) -> LATINO
                castellano.containsMatchIn(name) -> CASTELLANO
                subtitulado.containsMatchIn(name) -> SUBTITULADO
                ingles.containsMatchIn(name) -> INGLES
                else -> null
            }
        }

        /** Latino, castellano, sin idioma indicado, subtitulado e inglés; el resto del orden se respeta. */
        fun latinoFirst(servers: List<Video.Server>): List<Video.Server> = servers.sortedBy {
            when (of(it.name)) {
                LATINO -> 0
                CASTELLANO -> 1
                null -> 2
                SUBTITULADO -> 3
                INGLES -> 4
            }
        }
    }
}
