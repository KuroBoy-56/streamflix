package com.streamflixreborn.streamflix.utils

import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp

/**
 * Historial de estabilidad por proveedor (reproducciones logradas vs. fallidas).
 * Se usa para ordenar los proveedores de respaldo: primero el que más veces reprodujo bien.
 */
object ProviderStability {

    private const val PREFS = "provider_stability"

    private val prefs
        get() = runCatching {
            StreamFlixApp.instance.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        }.getOrNull()

    fun recordSuccess(providerName: String?) = bump(providerName, "ok")

    fun recordFailure(providerName: String?) = bump(providerName, "fail")

    /** Puntuación 0..1 con suavizado (un proveedor sin historial queda en 0.5). */
    fun score(providerName: String): Double {
        val p = prefs ?: return 0.5
        val ok = p.getInt("$providerName|ok", 0)
        val fail = p.getInt("$providerName|fail", 0)
        return (ok + 1.0) / (ok + fail + 2.0)
    }

    private fun bump(providerName: String?, kind: String) {
        if (providerName.isNullOrBlank()) return
        val p = prefs ?: return
        val key = "$providerName|$kind"
        // Se limita el historial para que un proveedor reparado pueda recuperar su puesto.
        val ok = p.getInt("$providerName|ok", 0)
        val fail = p.getInt("$providerName|fail", 0)
        val editor = p.edit()
        if (ok + fail >= 40) {
            editor.putInt("$providerName|ok", ok / 2).putInt("$providerName|fail", fail / 2)
        }
        editor.putInt(key, p.getInt(key, 0).let { if (ok + fail >= 40) it / 2 else it } + 1).apply()
    }
}
