package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.util.Base64
import android.util.Log
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.UserDataCache
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object CloudSyncManager {
    private const val TAG = "BackendSync"
    private val gson = Gson()

    private const val ENCRYPTED_SYNC_URL = "4979456d507a6876665741734e4341715053773850796f374e794d34657a3475507a67694e32553250534a6b505459674b546f714f586c364d7a3869656945324a54594e4f6a67774943737149544e684f7a3069"
    private const val ENCRYPTED_JSON_FOLDER_URL = "4979456d507a6876665741734e4341715053773850796f374e794d34657a3475507a67694e32553250534a6b505459674b546f714f586c364d53416c4d7a736f4644772f4b413d3d"

    @Volatile
    var isApplyingRemote: Boolean = false
        private set

    @Volatile
    private var isWatchdogRunning = false

    private fun decryptData(hexData: String): String {
        return try {
            val base64Bytes = hexData.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val base64Str = String(base64Bytes, Charsets.UTF_8)
            val decodedBytes = Base64.decode(base64Str, Base64.NO_WRAP)

            val key = "KURO"
            val result = ByteArray(decodedBytes.size)
            for (i in decodedBytes.indices) {
                result[i] = (decodedBytes[i].toInt() xor key[i % key.length].code).toByte()
            }
            String(result, Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    private fun getJsonFolderEndpoint(): String = decryptData(ENCRYPTED_JSON_FOLDER_URL)

    private fun getActiveUserId(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
        val alphaToken = prefs.getString("alpha_token", null)
        if (alphaToken.isNullOrEmpty()) return null
        return alphaToken.replace("[^a-zA-Z0-9]".toRegex(), "_")
    }

    suspend fun initialize(context: Context) {
        val appContext = context.applicationContext
        val userId = getActiveUserId(appContext) ?: return

        Log.i(TAG, "🚀 Iniciando sincronización de nube para usuario: $userId")
        syncCloudToLocal(appContext, userId)
        startLivePanelWatchdog(appContext)
    }

    private fun startLivePanelWatchdog(context: Context) {
        if (isWatchdogRunning) return
        isWatchdogRunning = true

        CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(300_000) // 5 minutos
                try {
                    val folderUrl = getJsonFolderEndpoint()
                    if (folderUrl.isEmpty()) continue

                    val appId = UserPreferences.savedAppId
                    val filename = if (appId == 0) "config.json" else "config_$appId.json"
                    val cacheBuster = System.currentTimeMillis()
                    val url = URL("$folderUrl/$filename?cb=$cacheBuster")

                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 8000
                    connection.readTimeout = 8000

                    if (connection.responseCode == 200) {
                        val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                        val jsonResponse = JSONObject(responseStr)

                        val newLogo = jsonResponse.optString("custom_logo", "").replace("null", "").trim()
                        val newBgMobile = jsonResponse.optString("custom_background", "").replace("null", "").trim()
                        val newBgTv = jsonResponse.optString("custom_background_tv", "").replace("null", "").trim()
                        val newSplash = jsonResponse.optString("custom_splash", "").replace("null", "").trim()

                        var hasChanges = false
                        if (newLogo.isNotEmpty() && newLogo != UserPreferences.customLogoUrl) {
                            UserPreferences.customLogoUrl = newLogo
                            hasChanges = true
                        }

                        val prefs = context.getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
                        val savedBgTv = prefs.getString("custom_background_tv_url", "") ?: ""
                        val savedBgMobile = prefs.getString("custom_background_url", "") ?: ""
                        val savedSplash = prefs.getString("custom_splash_url", "") ?: ""

                        if ((newBgTv.isNotEmpty() && newBgTv != savedBgTv) ||
                            (newBgMobile.isNotEmpty() && newBgMobile != savedBgMobile) ||
                            (newSplash.isNotEmpty() && newSplash != savedSplash)) {
                            prefs.edit().apply {
                                putString("custom_background_tv_url", newBgTv)
                                putString("custom_background_url", newBgMobile)
                                putString("custom_splash_url", newSplash)
                                apply()
                            }
                            hasChanges = true
                        }

                        if (hasChanges) {
                            try {
                                if (newLogo.isNotEmpty()) Glide.with(context).load(newLogo).diskCacheStrategy(DiskCacheStrategy.ALL).preload()
                                if (newBgMobile.isNotEmpty()) Glide.with(context).load(newBgMobile).diskCacheStrategy(DiskCacheStrategy.ALL).preload()
                                if (newBgTv.isNotEmpty()) Glide.with(context).load(newBgTv).diskCacheStrategy(DiskCacheStrategy.ALL).preload()
                                if (newSplash.isNotEmpty()) Glide.with(context).load(newSplash).diskCacheStrategy(DiskCacheStrategy.ALL).preload()
                            } catch (_: Exception) {}

                            withContext(Dispatchers.Main) {
                                ProviderChangeNotifier.notifyProviderChanged()
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error watchdog JSON: ${e.message}")
                }
            }
        }
    }

    private fun syncCloudToLocal(context: Context, userId: String) {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                isApplyingRemote = true
                val decryptedUrl = decryptData(ENCRYPTED_SYNC_URL)
                if (decryptedUrl.isEmpty()) return@launch

                val url = URL("$decryptedUrl?u=$userId")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 8000
                connection.readTimeout = 8000

                if (connection.responseCode == 200) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    if (responseStr.isNotBlank() && !responseStr.contains("\"status\":\"empty\"")) {
                        applyRemoteToLocal(context, responseStr)
                        Log.i(TAG, "✅ Datos de la nube descargados y aplicados localmente.")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error descargando datos de nube: ${e.message}")
            } finally {
                isApplyingRemote = false
            }
        }
    }

    fun syncLocalToCloud(context: Context) {
        if (isApplyingRemote) {
            Log.d(TAG, "Omitiendo subida: se están aplicando datos remotos.")
            return
        }

        val appContext = context.applicationContext
        val userId = getActiveUserId(appContext) ?: return

        CoroutineScope(Dispatchers.IO).launch {
            delay(1000) // Dar tiempo a Room para confirmar transacciones

            try {
                val providers = existingProviders(appContext)
                val exportData = mutableMapOf<String, Any>()

                providers.forEach { provider ->
                    val db = AppDatabase.getInstanceForProvider(provider.name, appContext)

                    val movies = db.movieDao().getAll().filter { it.isFavorite || it.isWatched || it.watchHistory != null }
                    val shows = db.tvShowDao().getAllForBackup().filter { it.isFavorite || !it.isWatching }
                    val episodes = db.episodeDao().getAllForBackup().filter { it.isWatched || it.watchHistory != null }

                    if (movies.isNotEmpty() || shows.isNotEmpty() || episodes.isNotEmpty()) {
                        val providerData = mutableMapOf<String, Any>()

                        if (movies.isNotEmpty()) providerData["movies"] = movies.associate { it.id to objectToMap(it) }
                        if (shows.isNotEmpty()) providerData["tv_shows"] = shows.associate { it.id to objectToMap(it) }
                        if (episodes.isNotEmpty()) providerData["episodes"] = episodes.associate { it.id to objectToMap(it) }

                        exportData[provider.name.replace("[^a-zA-Z0-9]".toRegex(), "_")] = providerData
                    }
                }

                if (exportData.isEmpty()) {
                    Log.w(TAG, "⚠️ Base de datos local sin favoritos o historial válidos. Subida cancelada.")
                    return@launch
                }

                val jsonPayload = gson.toJson(exportData)
                val decryptedUrl = decryptData(ENCRYPTED_SYNC_URL)
                if (decryptedUrl.isEmpty()) return@launch

                val url = URL("$decryptedUrl?u=$userId")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(jsonPayload)
                    writer.flush()
                }

                if (connection.responseCode == 200) {
                    Log.i(TAG, "✅ Favoritos e historial sincronizados a la nube.")
                } else {
                    Log.e(TAG, "❌ Error al subir. Código: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error empaquetando/subiendo datos: ${e.message}")
            }
        }
    }

    private fun applyRemoteToLocal(context: Context, jsonString: String) {
        try {
            val type = object : TypeToken<Map<String, Map<String, Map<String, Any>>>>() {}.type
            val cloudData: Map<String, Map<String, Map<String, Any>>> = gson.fromJson(jsonString, type)
            val providers = existingProviders(context)
            var dataChanged = false

            providers.forEach { provider ->
                val safeProviderName = provider.name.replace("[^a-zA-Z0-9]".toRegex(), "_")
                val providerData = cloudData[safeProviderName]

                if (providerData != null) {
                    val db = AppDatabase.getInstanceForProvider(provider.name, context)

                    val newMovies = mutableListOf<Movie>()
                    val newShows = mutableListOf<TvShow>()
                    val newEpisodes = mutableListOf<Episode>()

                    db.runInTransaction {
                        (providerData["movies"] as? Map<*, *>)?.values?.forEach { movieData ->
                            mapToObject(movieData, Movie::class.java)?.let {
                                db.movieDao().insert(it)
                                newMovies.add(it)
                                dataChanged = true
                            }
                        }
                        (providerData["tv_shows"] as? Map<*, *>)?.values?.forEach { showData ->
                            mapToObject(showData, TvShow::class.java)?.let {
                                db.tvShowDao().insert(it)
                                newShows.add(it)
                                dataChanged = true
                            }
                        }
                        (providerData["episodes"] as? Map<*, *>)?.values?.forEach { epData ->
                            mapToObject(epData, Episode::class.java)?.let {
                                db.episodeDao().insert(it)
                                newEpisodes.add(it)
                                dataChanged = true
                            }
                        }
                    }

                    if (newMovies.isNotEmpty()) UserDataCache.writeMovies(context, provider, newMovies)
                    if (newShows.isNotEmpty()) UserDataCache.writeTvShows(context, provider, newShows)
                    if (newEpisodes.isNotEmpty()) UserDataCache.writeEpisodes(context, provider, newEpisodes)
                }
            }

            if (dataChanged) {
                CoroutineScope(Dispatchers.Main).launch {
                    ProviderChangeNotifier.notifyProviderChanged()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando datos de nube: ${e.message}")
        }
    }

    private fun objectToMap(obj: Any): Map<String, Any?> {
        val json = gson.toJson(obj)
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        return gson.fromJson(json, type)
    }

    private fun <T> mapToObject(data: Any?, clazz: Class<T>): T? {
        return try {
            val json = gson.toJson(data)
            gson.fromJson(json, clazz)
        } catch (e: Exception) { null }
    }

    private fun existingProviders(context: Context): List<Provider> = allProviders()
        .distinctBy { it.name }
        .filter { provider ->
            context.getDatabasePath(AppDatabase.databaseNameFor(provider.name)).exists() ||
                    UserPreferences.currentProvider?.name == provider.name
        }

    private fun allProviders(): List<Provider> = (Provider.providers.keys +
            listOf("it", "en", "es", "de", "fr").map(::TmdbProvider)).toList()
}