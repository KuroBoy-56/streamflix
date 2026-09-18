package com.streamflixreborn.streamflix.sync

import android.content.Context
import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.models.Episode
import com.streamflixreborn.streamflix.models.Movie
import com.streamflixreborn.streamflix.models.TvShow
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

object CloudSyncManager {
    private const val TAG = "BackendSync"
    private val gson = Gson()

    private const val ENCRYPTED_SYNC_URL = "4979456d507a6876665741734e4341715053773850796f374e794d34657a3475507a67694e32553250534a6b505459674b546f714f586c364d7a3869656945324a54594e4f6a67774943737149544e684f7a3069"

    private var lastObservedUserId: String? = null

    @Volatile
    var isApplyingRemote: Boolean = false
        private set

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
        } catch (e: Exception) {
            ""
        }
    }

    private fun getActiveUserId(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
        val alphaToken = prefs.getString("alpha_token", null)

        if (alphaToken.isNullOrEmpty()) {
            Log.w(TAG, "⚠️ No hay alpha_token. Usuario no logueado.")
            return null
        }
        return alphaToken.replace("[^a-zA-Z0-9]".toRegex(), "_")
    }

    suspend fun initialize(context: Context) {
        val appContext = context.applicationContext
        val userId = getActiveUserId(appContext) ?: return

        if (lastObservedUserId != userId) {
            lastObservedUserId = userId
            Log.i(TAG, "🚀 Iniciando sincronización JSON en la nube para: $userId")
            syncCloudToLocal(appContext, userId)
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
                        try {
                            applyRemoteToLocal(context, responseStr)
                            Log.i(TAG, "✅ Datos de la nube descargados y aplicados localmente con éxito.")
                        } catch (e: Exception) {
                            Log.e(TAG, "Error procesando JSON descargado: ${e.message}")
                        }
                    } else {
                        Log.d(TAG, "El usuario es nuevo o la nube está vacía.")
                    }
                } else {
                    Log.e(TAG, "Error conectando al servidor: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error descargando datos de la nube: ${e.message}")
            } finally {
                isApplyingRemote = false
            }
        }
    }

    fun syncLocalToCloud(context: Context) {
        // Evitamos subir datos si estamos descargando algo del servidor
        if (isApplyingRemote) {
            Log.d(TAG, "Omitiendo subida a la nube mientras se aplican datos remotos.")
            return
        }

        val appContext = context.applicationContext
        val userId = getActiveUserId(appContext) ?: return

        CoroutineScope(Dispatchers.IO).launch {
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

                        providerData["movies"] = movies.associate { it.id to objectToMap(it) }
                        providerData["tv_shows"] = shows.associate { it.id to objectToMap(it) }
                        providerData["episodes"] = episodes.associate { it.id to objectToMap(it) }

                        exportData[provider.name.replace("[^a-zA-Z0-9]".toRegex(), "_")] = providerData
                    }
                }

                // BLINDAJE REAL: Si no tienes favoritos ni historial, se cancela la subida para no blanquear el JSON de tu servidor.
                if (exportData.isEmpty()) {
                    Log.w(TAG, "⚠️ Base de datos local vacía. Subida abortada para proteger el JSON del servidor.")
                    return@launch
                }

                val jsonPayload = gson.toJson(exportData)
                val decryptedUrl = decryptData(ENCRYPTED_SYNC_URL)
                if (decryptedUrl.isEmpty()) return@launch

                val url = URL("$decryptedUrl?u=$userId")
                val connection = url.openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.doOutput = true

                OutputStreamWriter(connection.outputStream).use { writer ->
                    writer.write(jsonPayload)
                    writer.flush()
                }

                if (connection.responseCode == 200) {
                    Log.i(TAG, "✅ ¡Nube actualizada con éxito en tu servidor JSON! 🎉")
                } else {
                    Log.e(TAG, "❌ Fallo al subir a la nube. Código: ${connection.responseCode}")
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

                    db.runInTransaction {
                        (providerData["movies"] as? Map<*, *>)?.values?.forEach { movieData ->
                            mapToObject(movieData, Movie::class.java)?.let { db.movieDao().insert(it); dataChanged = true }
                        }
                        (providerData["tv_shows"] as? Map<*, *>)?.values?.forEach { showData ->
                            mapToObject(showData, TvShow::class.java)?.let { db.tvShowDao().insert(it); dataChanged = true }
                        }
                        (providerData["episodes"] as? Map<*, *>)?.values?.forEach { epData ->
                            mapToObject(epData, Episode::class.java)?.let { db.episodeDao().insert(it); dataChanged = true }
                        }
                    }
                }
            }

            if (dataChanged) {
                // Al importar los datos de la nube, forzamos a UserDataCache a actualizar su estado para reflejar los cambios
                CoroutineScope(Dispatchers.Main).launch {
                    com.streamflixreborn.streamflix.utils.UserDataCache.clearAll(context)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error aplicando datos de la nube a Room: ${e.message}")
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
        } catch (e: Exception) {
            null
        }
    }

    private fun existingProviders(context: Context): List<Provider> = allProviders()
        .distinctBy { it.name }
        .filter { provider ->
            context.getDatabasePath(AppDatabase.databaseNameFor(provider.name)).exists()
        }

    private fun allProviders(): List<Provider> = (Provider.providers.keys +
            listOf("it", "en", "es", "de", "fr").map(::TmdbProvider)).toList()
}