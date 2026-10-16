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
import com.streamflixreborn.streamflix.profiles.ProfileManager
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.TmdbProvider
import com.streamflixreborn.streamflix.utils.BrandAssets
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

    /** Otro archivo de la misma carpeta de la API (p. ej. "profiles.php"). */
    fun endpoint(file: String): String =
        decryptData(ENCRYPTED_SYNC_URL).takeIf { it.isNotEmpty() }?.substringBeforeLast('/')?.let { "$it/$file" }.orEmpty()

    /** Nube del perfil activo: ?u=<usuario>&p=<perfil>. */
    private fun syncUrl(userId: String, profileId: String): URL =
        URL("${decryptData(ENCRYPTED_SYNC_URL)}?u=$userId&p=$profileId")

    /** Al cambiar de perfil: se olvida lo leído de la nube y se cancela lo pendiente. */
    fun reset() {
        uploadJob?.cancel()
        initJob?.cancel()
        initialSyncDone = false
        syncedUserId = null
        remoteSnapshot = emptyMap()
    }

    /**
     * Credenciales del usuario en cabeceras para que sync_userdata.php verifique que el historial
     * le pertenece (evita que otro lea o sobrescriba su historial conociendo solo el usuario).
     */
    private fun addSyncAuth(context: Context, connection: HttpURLConnection) {
        val prefs = context.applicationContext.getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
        prefs.getString("alpha_token", null)?.takeIf { it.isNotEmpty() }?.let {
            connection.setRequestProperty("X-Sync-User", java.net.URLEncoder.encode(it, "UTF-8"))
        }
        prefs.getString("beta_token", null)?.takeIf { it.isNotEmpty() }?.let {
            connection.setRequestProperty("X-Sync-Pass", java.net.URLEncoder.encode(it, "UTF-8"))
        }
    }

    private fun getActiveUserId(context: Context): String? {
        val prefs = context.applicationContext.getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
        val alphaToken = prefs.getString("alpha_token", null)
        if (alphaToken.isNullOrEmpty()) return null
        return alphaToken.replace("[^a-zA-Z0-9]".toRegex(), "_")
    }

    /** Usuario + perfil: cada perfil tiene su propia nube. */
    private fun syncKey(userId: String, profileId: String) = "$userId-$profileId"

    suspend fun initialize(context: Context) {
        val appContext = context.applicationContext
        val userId = getActiveUserId(appContext) ?: return
        val key = syncKey(userId, ProfileManager.activeId)

        // Se llama desde el arranque y el login: solo descarga una vez por usuario, perfil y sesión.
        if (syncedUserId != key) {
            initialSyncDone = false
            remoteSnapshot = emptyMap()
        }
        if (!initialSyncDone && initJob?.isActive != true) {
            Log.i(TAG, "🚀 Iniciando sincronización de nube para usuario: $userId")
            initJob = scope.launch { downloadAndApply(appContext, userId, ProfileManager.activeId) }
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
                    if (applyPanelBranding(context)) {
                        withContext(Dispatchers.Main) { ProviderChangeNotifier.notifyProviderChanged() }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error watchdog JSON: ${e.message}")
                }
            }
        }
    }

    private fun fetchConfig(folderUrl: String, filename: String): JSONObject? = runCatching {
        val connection = URL("$folderUrl/$filename?cb=${System.currentTimeMillis()}").openConnection() as HttpURLConnection
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        if (connection.responseCode != 200) return@runCatching null
        JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
    }.getOrNull()

    /**
     * Logo, splash, fondos, marca, nota y líneas de canales del panel, igual que al iniciar
     * sesión: cada campo vacío del administrador se completa con el del dueño. Devuelve true si
     * algo cambió (las imágenes nuevas se guardan en el equipo).
     */
    private suspend fun applyPanelBranding(context: Context): Boolean {
        val folderUrl = getJsonFolderEndpoint()
        if (folderUrl.isEmpty()) return false
        val appId = UserPreferences.savedAppId
        val master = fetchConfig(folderUrl, "config.json")
        val own = if (appId != 0) fetchConfig(folderUrl, "config_$appId.json") else null
        // Si una de las dos no se pudo leer ahora, no se cambia nada (evita alternar marcas).
        if (master == null || (appId != 0 && own == null)) return false

        fun text(json: JSONObject?, key: String) = json?.optString(key, "").orEmpty().replace("null", "").trim()
        fun field(key: String) = text(own, key).ifEmpty { text(master, key) }
        fun lines(json: JSONObject?) = if (json?.has("channel_lines") == true) PanelChannelConfig.lines(json.opt("channel_lines")) else emptyList()

        val prefs = context.getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
        val values = mapOf(
            "custom_background_url" to field("custom_background"),
            "custom_background_tv_url" to field("custom_background_tv"),
            "custom_splash_url" to field("custom_splash"),
            "brand_name" to field("app_name"),
            "settings_note" to field("settings_note"),
            com.streamflixreborn.streamflix.providers.PanelM3uProvider.KEY_CHANNEL_LINES to
                    lines(own).ifEmpty { lines(master) }.joinToString("\n"),
        )
        var changed = false
        val editor = prefs.edit()
        values.forEach { (key, value) ->
            if (prefs.getString(key, "").orEmpty() != value) {
                editor.putString(key, value)
                changed = true
            }
        }
        editor.apply()
        val logo = field("custom_logo")
        if (logo != UserPreferences.customLogoUrl) {
            UserPreferences.customLogoUrl = logo
            changed = true
        }
        BrandAssets.refresh(context)
        if (changed) com.streamflixreborn.streamflix.utils.LauncherBrand.updateShortcut(context)
        return changed
    }

    // ------------------------------------------------------------------------------------
    // Descarga (nube -> local)
    // ------------------------------------------------------------------------------------

    /**
     * Descarga el documento del usuario y lo fusiona con lo local (gana el registro más reciente).
     * Devuelve true si la nube respondió (con datos o vacía), false si no se pudo leer.
     */
    private suspend fun downloadAndApply(context: Context, userId: String, profileId: String): Boolean = syncMutex.withLock {
        val key = syncKey(userId, profileId)
        if (initialSyncDone && syncedUserId == key) return@withLock true
        // Cambió el perfil mientras se esperaba: no se aplica la nube de otro perfil.
        if (profileId != ProfileManager.activeId) return@withLock false
        try {
            isApplyingRemote = true
            val decryptedUrl = decryptData(ENCRYPTED_SYNC_URL)
            if (decryptedUrl.isEmpty()) return@withLock false

            val connection = syncUrl(userId, profileId).openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            addSyncAuth(context, connection)
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
            syncedUserId = key
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
                .filter { (key, value) -> value is Map<*, *> && !key.startsWith("_") }
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
        val profileId = ProfileManager.activeId
        val key = syncKey(userId, profileId)

        // Nunca subir antes de haber leído la nube: en un dispositivo nuevo borraría datos de otros.
        if (!(initialSyncDone && syncedUserId == key) && !downloadAndApply(context, userId, profileId)) {
            Log.w(TAG, "⚠️ Subida pospuesta: no se pudo leer la nube todavía.")
            return false
        }

        return syncMutex.withLock {
            // El perfil cambió antes de tomar el turno: lo local ya es de otro perfil.
            if (profileId != ProfileManager.activeId || syncedUserId != key) return@withLock false
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

                val connection = syncUrl(userId, profileId).openConnection() as HttpURLConnection
                connection.requestMethod = "POST"
                addSyncAuth(context, connection)
                connection.setRequestProperty("Content-Type", "application/json")
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.doOutput = true

                OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { writer ->
                    writer.write(jsonPayload)
                    writer.flush()
                }

                // El servidor (sync_userdata.php) responde 200 también en errores: se revisa "status".
                val body = runCatching { connection.inputStream.bufferedReader().use { it.readText() } }.getOrDefault("")
                val serverStatus = runCatching {
                    com.google.gson.JsonParser.parseString(body).asJsonObject.get("status")?.asString
                }.getOrNull()
                val ok = connection.responseCode == 200 && (serverStatus == null || serverStatus == "success")
                if (!ok) Log.e(TAG, "❌ El servidor rechazó la subida: ${body.take(200)}")
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