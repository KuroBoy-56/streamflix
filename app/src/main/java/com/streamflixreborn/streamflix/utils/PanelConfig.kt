package com.streamflixreborn.streamflix.utils

/**
 * Dominio del panel (edición Sports). Está a la vista a propósito, en un solo lugar: para cambiarlo
 * en un APK ya compilado basta editar la cadena de BASE_URL en smali/.../utils/PanelConfig.smali.
 */
object PanelConfig {
    @JvmField
    var BASE_URL: String = "https://garevynpanels.latmpx.com/hdoboxv2/"

    fun api(file: String): String = BASE_URL.trimEnd('/') + "/api/" + file
}
