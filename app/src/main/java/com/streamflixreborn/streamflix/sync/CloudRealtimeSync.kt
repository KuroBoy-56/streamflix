package com.streamflixreborn.streamflix.sync

import android.content.Context

object CloudRealtimeSync {
    suspend fun start(context: Context, userId: String) {
        // Manejado por Firebase en CloudSyncManager
    }

    suspend fun stop() {
        // Manejado por Firebase en CloudSyncManager
    }
}