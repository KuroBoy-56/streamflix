package com.streamflixreborn.streamflix.sync

import android.content.Context

object SupabaseProvider {
    val isConfigured: Boolean = false
    fun initialize(context: Context) {
        // Sin uso: la sincronización va al servidor JSON (CloudSyncManager)
    }
}