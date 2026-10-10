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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

    private const val UPLOAD_DEBOUNCE_MS = 2_500L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val syncMutex = Mutex()
    private var initJob: Job? = null
    private var uploadJob: Job? = null

    /** Ya se leyó la nube para [syncedUserId]; recién entonces se permite subir. */
    @Volatile
    private var initialSyncDone = false
    @Volatile
    private var syncedUserId: String? = null
    /** Último documento conocido de la nube (clave de proveedor -> datos). */
    @Volatile
    private var remoteSnapshot: Map<String, Any?> = emptyMap()

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

        // Se llama desde el arranque y el login: solo descarga una vez por usuario y sesión.
        if (syncedUserId != userId) {
            initialSyncDone = false
            remoteSnapshot = emptyMap()
        }
        if (!initialSyncDone && initJob?.isActive != true) {
            Log.i(TAG, "🚀 Iniciando sincronización de nube para usuario: $userId")
            initJob = scope.launch { downloadAndApply(appContext, userId) }
        }
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

    // ------------------------------------------------------------------------------------
    // Descarga (nube -> local)
    // ------------------------------------------------------------------------------------

    /**
     * Descarga el documento del usuario y lo fusiona con lo local (gana el registro más reciente).
     * Devuelve true si la nube respondió (con datos o vacía), false si no se pudo leer.
     */
    private suspend fun downloadAndApply(context: Context, userId: String): Boolean = syncMutex.withLock {
        if (initialSyncDone && syncedUserId == userId) return@withLock true
        try {
            isApplyingRemote = true
            val decryptedUrl = decryptData(ENCRYPTED_SYNC_URL)
            if (decryptedUrl.isEmpty()) return@withLock false

            val connection = URL("$decryptedUrl?u=$userId").openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 8000
            connection.readTimeout = 8000

            if (connection.responseCode != 200) {
                Log.e(TAG, "❌ Descarga de nube: HTTP ${connection.responseCode}")
                return@withLock false
            }

            val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
            val cloudData = parseCloudDocument(responseStr)
            if (cloudData == null) {
                Log.e(TAG, "❌ Respuesta de nube no válida")
                return@withLock false
            }

            if (cloudData.isNotEmpty()) {
                applyRemoteToLocal(context, cloudData)
                Log.i(TAG, "✅ Datos de la nube descargados y fusionados (${cloudData.size} proveedores).")
            } else {
                Log.i(TAG, "☁️ Nube vacía para este usuario.")
            }
            remoteSnapshot = cloudData
            syncedUserId = userId
            initialSyncDone = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error descargando datos de nube: ${e.message}")
            false
        } finally {
            isApplyingRemote = false
        }
    }

    /** null = respuesta inválida; vacío = el usuario aún no tiene datos en la nube. */
    internal fun parseCloudDocument(responseStr: String): Map<String, Any?>? {
        val trimmed = responseStr.trim()
        if (trimmed.isEmpty()) return emptyMap()
        return try {
            val root = com.google.gson.JsonParser.parseString(trimmed)
            if (!root.isJsonObject) return null
            val obj = root.asJsonObject
            if (obj.get("status")?.takeIf { it.isJsonPrimitive }?.asString == "empty") return emptyMap()
            val type = object : TypeToken<Map<String, Any?>>() {}.type
            gson.fromJson<Map<String, Any?>>(obj, type)
                .filterValues { it is Map<*, *> }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------------------------
    // Subida (local -> nube)
    // ------------------------------------------------------------------------------------

    /** Programa una subida agrupando cambios seguidos (p. ej. el progreso de reproducción). */
    fun syncLocalToCloud(context: Context) {
        // Si se está aplicando la nube, la subida espera al mutex y sube el estado ya fusionado.
        val appContext = context.applicationContext
        getActiveUserId(appContext) ?: return

        uploadJob?.cancel()
        uploadJob = scope.launch {
            delay(UPLOAD_DEBOUNCE_MS)
            uploadNow(appContext)
        }
    }

    /** Sincronización manual (Ajustes): sube ya y devuelve si funcionó. */
    suspend fun syncNow(context: Context): Boolean = withContext(Dispatchers.IO) {
        uploadJob?.cancel()
        uploadNow(context.applicationContext)
    }

    private suspend fun uploadNow(context: Context): Boolean {
        val userId = getActiveUserId(context) ?: return false

        // Nunca subir antes de haber leído la nube: en un dispositivo nuevo borraría datos de otros.
        if (!(initialSyncDone && syncedUserId == userId) && !downloadAndApply(context, userId)) {
            Log.w(TAG, "⚠️ Subida pospuesta: no se pudo leer la nube todavía.")
            return false
        }

        return syncMutex.withLock {
            try {
                // Se parte del último documento de la nube para conservar proveedores
                // que no existen en este dispositivo.
                val exportData = remoteSnapshot.toMutableMap()

                existingProviders(context).forEach { provider ->
                    withDatabase(context, provider) { db ->
                        val movies = db.movieDao().getAll().filter { it.hasUserData() }
                        val shows = db.tvShowDao().getAllForBackup().filter { it.hasUserData() }
                        val episodes = db.episodeDao().getAllForBackup().filter { it.hasUserData() }

                        val providerData = mutableMapOf<String, Any>()
                        if (movies.isNotEmpty()) providerData["movies"] = movies.associate { it.id to objectToMap(it) }
                        if (shows.isNotEmpty()) providerData["tv_shows"] = shows.associate { it.id to objectToMap(it) }
                        if (episodes.isNotEmpty()) providerData["episodes"] = episodes.associate { it.id to objectToMap(it) }

                        // Un proveedor sin datos se envía vacío para reflejar lo que se quitó.
                        exportData[safeName(provider.name)] = providerData
                    }
                }

                val hasAnyData = exportData.values.any { (it as? Map<*, *>)?.isNotEmpty() == true }
                if (!hasAnyData && remoteSnapshot.isEmpty()) {
                    Log.d(TAG, "Nada que subir.")
                    return@withLock true
                }

                val jsonPayload = gson.toJson(exportData)
                val decryptedUrl = decryptData(ENCRYPTED_SYNC_URL)
                if (decryptedUrl.isEmpty()) return@withLock false

                val connection = URL("$decryptedUrl?u=$userId").openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true

                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(jsonPayload)
                    writer.flush()
                }

                val ok = connection.responseCode == 200
                if (ok) {
                    remoteSnapshot = exportData
                    Log.i(TAG, "✅ Favoritos e historial sincronizados a la nube.")
                } else {
                    Log.e(TAG, "❌ Error al subir. Código: ${connection.responseCode}")
                }
                ok
            } catch (e: Exception) {
                Log.e(TAG, "❌ Error empaquetando/subiendo datos: ${e.message}")
                false
            }
        }
    }

    // ------------------------------------------------------------------------------------
    // Fusión
    // ------------------------------------------------------------------------------------

    private fun applyRemoteToLocal(context: Context, cloudData: Map<String, Any?>) {
        var dataChanged = false
        val providersByKey = allProviders().distinctBy { it.name }.associateBy { safeName(it.name) }

        cloudData.forEach { (key, value) ->
            // Se aplican todos los proveedores de la nube, existan o no en este dispositivo.
            val provider = providersByKey[key] ?: return@forEach
            val providerData = value as? Map<*, *> ?: return@forEach

            val newMovies = mutableListOf<Movie>()
            val newShows = mutableListOf<TvShow>()
            val newEpisodes = mutableListOf<Episode>()

            try {
                withDatabase(context, provider) { db ->
                    db.runInTransaction {
                        (providerData["movies"] as? Map<*, *>)?.values?.forEach { data ->
                            val remote = mapToObject(data, Movie::class.java) ?: return@forEach
                            if (shouldApply(remote.updatedAt(), remote.hasUserData(), db.movieDao().getById(remote.id)?.let { it.updatedAt() to it.hasUserData() })) {
                                db.movieDao().insert(remote)
                                newMovies.add(remote)
                            }
                        }
                        (providerData["tv_shows"] as? Map<*, *>)?.values?.forEach { data ->
                            val remote = mapToObject(data, TvShow::class.java) ?: return@forEach
                            if (shouldApply(remote.updatedAt(), remote.hasUserData(), db.tvShowDao().getById(remote.id)?.let { it.updatedAt() to it.hasUserData() })) {
                                db.tvShowDao().insert(remote)
                                newShows.add(remote)
                            }
                        }
                        (providerData["episodes"] as? Map<*, *>)?.values?.forEach { data ->
                            val remote = mapToObject(data, Episode::class.java) ?: return@forEach
                            if (shouldApply(remote.updatedAt(), remote.hasUserData(), db.episodeDao().getById(remote.id)?.let { it.updatedAt() to it.hasUserData() })) {
                                db.episodeDao().insert(remote)
                                newEpisodes.add(remote)
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error aplicando datos de nube para ${provider.name}: ${e.message}")
            }

            if (newMovies.isNotEmpty()) UserDataCache.writeMovies(context, provider, newMovies)
            if (newShows.isNotEmpty()) UserDataCache.writeTvShows(context, provider, newShows)
            if (newEpisodes.isNotEmpty()) UserDataCache.writeEpisodes(context, provider, newEpisodes)
            if (newMovies.isNotEmpty() || newShows.isNotEmpty() || newEpisodes.isNotEmpty()) dataChanged = true
        }

        if (dataChanged) {
            CoroutineScope(Dispatchers.Main).launch {
                ProviderChangeNotifier.notifyProviderChanged()
            }
        }
    }

    /** Gana el más reciente; si empatan, el que tenga datos del usuario. */
    private fun shouldApply(remoteUpdatedAt: Long, remoteHasData: Boolean, local: Pair<Long, Boolean>?): Boolean {
        if (local == null) return true
        val (localUpdatedAt, localHasData) = local
        return remoteUpdatedAt > localUpdatedAt || (remoteUpdatedAt == localUpdatedAt && remoteHasData && !localHasData)
    }

    private fun Movie.updatedAt(): Long = maxOf(
        watchHistory?.lastEngagementTimeUtcMillis ?: 0L,
        favoritedAtMillis ?: 0L,
        lastPlayedAtMillis ?: 0L,
        watchedDate?.timeInMillis ?: 0L,
    )

    private fun TvShow.updatedAt(): Long = maxOf(favoritedAtMillis ?: 0L, lastPlayedAtMillis ?: 0L)

    private fun Episode.updatedAt(): Long = maxOf(
        watchHistory?.lastEngagementTimeUtcMillis ?: 0L,
        watchedDate?.timeInMillis ?: 0L,
    )

    private fun Movie.hasUserData() = isFavorite || isWatched || watchHistory != null || lastPlayedAtMillis != null
    private fun TvShow.hasUserData() = isFavorite || !isWatching || lastPlayedAtMillis != null
    private fun Episode.hasUserData() = isWatched || watchHistory != null

    /** Usa la base activa si es la del proveedor actual (refresca la UI); si no, abre y cierra otra. */
    private inline fun <T> withDatabase(context: Context, provider: Provider, block: (AppDatabase) -> T): T {
        val isCurrent = UserPreferences.currentProvider?.name == provider.name
        val db = if (isCurrent) AppDatabase.getInstance(context) else AppDatabase.getInstanceForProvider(provider.name, context)
        return try {
            block(db)
        } finally {
            if (!isCurrent) runCatching { db.close() }
        }
    }

    private fun safeName(name: String) = name.replace("[^a-zA-Z0-9]".toRegex(), "_")

    internal fun objectToMap(obj: Any): Map<String, Any?> {
        val json = gson.toJson(obj)
        val type = object : TypeToken<Map<String, Any?>>() {}.type
        return gson.fromJson(json, type)
    }

    internal fun <T> mapToObject(data: Any?, clazz: Class<T>): T? {
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