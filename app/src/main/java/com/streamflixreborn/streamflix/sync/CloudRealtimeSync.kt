package com.streamflixreborn.streamflix.sync

import android.content.Context

object CloudRealtimeSync {
    suspend fun start(context: Context, userId: String) {
        // Manejado por CloudSyncManager (servidor JSON)
    }

    suspend fun stop() {
        // Manejado por CloudSyncManager (servidor JSON)
    }
}