package com.streamflixreborn.streamflix.sync

import org.json.JSONArray

/** Lee las URLs de canales que envía el panel (arreglo JSON o texto con una URL por línea). */
object PanelChannelConfig {

    fun lines(value: Any?): List<String> {
        val raw = when (value) {
            is JSONArray -> (0 until value.length()).map { value.optString(it) }
            is String -> value.split('\n', '\r', ',', ' ')
            else -> emptyList()
        }
        return raw.map { it.trim() }
            .filter { it.startsWith("http://", true) || it.startsWith("https://", true) }
            .distinct()
    }
}
