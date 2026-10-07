package com.streamflixreborn.streamflix.sync

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class CloudSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        return Result.success()
    }
}