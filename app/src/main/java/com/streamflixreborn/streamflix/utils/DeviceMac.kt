package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.provider.Settings
import java.net.URLEncoder
import java.util.Locale

/** MAC del equipo tal como la registra el panel al iniciar sesión (ANDROID_ID en pares). */
object DeviceMac {
    fun get(context: Context): String {
        val rawId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "0000000000000000"
        val cleanId = rawId.replace("[^a-fA-F0-9]".toRegex(), "").padEnd(16, '0')
        return cleanId.substring(0, 16).chunked(2).joinToString(":").uppercase(Locale.getDefault())
    }

    /** Parámetros del aviso de vencimiento: MAC real y cuenta con la que se inició sesión. */
    fun noticeParams(context: Context): String {
        val user = context.getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE).getString("alpha_token", "").orEmpty()
        return "mac=${get(context)}&u=${URLEncoder.encode(user, "UTF-8")}"
    }
}
