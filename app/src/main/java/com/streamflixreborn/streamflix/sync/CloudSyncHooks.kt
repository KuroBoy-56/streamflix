package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.util.Log
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider

object CloudSyncHooks {
    private const val TAG = "CloudSyncHooks"

    fun movie(context: Context, provider: Provider, id: String) {
        Log.d(TAG, "Hook disparado para película ID: $id")
        CloudSyncManager.syncLocalToCloud(context)
    }

    fun movie(context: Context, provider: Provider, movie: Movie) {
        Log.d(TAG, "Hook disparado para película: ${movie.title}")
        CloudSyncManager.syncLocalToCloud(context)
    }

    fun episode(context: Context, provider: Provider, id: String) {
        Log.d(TAG, "Hook disparado para episodio ID: $id")
        CloudSyncManager.syncLocalToCloud(context)
    }

    fun episode(context: Context, provider: Provider, episode: Episode) {
        Log.d(TAG, "Hook disparado para episodio: ${episode.id}")
        CloudSyncManager.syncLocalToCloud(context)
    }

    fun tvShow(context: Context, provider: Provider, id: String) {
        Log.d(TAG, "Hook disparado para serie ID: $id")
        CloudSyncManager.syncLocalToCloud(context)
    }

    fun tvShow(context: Context, provider: Provider, tvShow: TvShow) {
        Log.d(TAG, "Hook disparado para serie: ${tvShow.title}")
        CloudSyncManager.syncLocalToCloud(context)
    }

    fun tvShow(context: Context, tvShow: TvShow) {
        Log.d(TAG, "Hook disparado genérico para serie: ${tvShow.title}")
        CloudSyncManager.syncLocalToCloud(context)
    }
}