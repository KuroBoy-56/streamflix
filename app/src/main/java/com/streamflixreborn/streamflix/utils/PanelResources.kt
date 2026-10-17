package com.streamflixreborn.streamflix.utils

/**
 * Recursos externos (listas de PlutoTV / iptv-org, claves de extractores, video "sin señal")
 * servidos por el panel: la app solo llama al dominio propio, nunca a GitHub.
 */
object PanelResources {
    const val BASE = "https://garevynpanels.latmpx.com/hdoboxv2/api/recursos.php"
    const val URL = "$BASE?r="
    const val NO_SIGNAL_VIDEO = "${URL}sin_senal"
    const val VIDSRC_KEYS = "${URL}vidsrc_keys"
    const val CHILLX_KEYS = "${URL}chillx_keys"
}
