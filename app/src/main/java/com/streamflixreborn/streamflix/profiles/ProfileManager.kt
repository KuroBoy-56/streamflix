package com.streamflixreborn.streamflix.profiles

import android.content.Context
import com.streamflixreborn.streamflix.StreamFlixApp
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.sync.CloudSyncManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Perfiles de la cuenta ("¿Quién está viendo?"). La cantidad la define el panel (dispositivos
 * de la cuenta, máximo 5). Cada perfil tiene su propia nube en el servidor
 * (api/userdata/<usuario>/<usuario>-00.json, -01 ...) y su propio almacenamiento local:
 * el principal (-00) usa las bases de siempre; los demás, bases con sufijo "_p01", "_p02"...
 */
object ProfileManager {

    const val MAIN_ID = "00"
    private const val PREFS = "SecureGatewayPrefs"
    private const val KEY_ACTIVE = "active_profile"
    private const val KEY_LIMIT = "profile_limit"
    private const val KEY_CACHE = "profiles_cache"

    data class Profile(val id: String, val name: String, val avatar: String, val pin: String) {
        val isMain get() = id == MAIN_ID
        val hasPin get() = pin.length == 4
    }

    data class Account(val limit: Int, val profiles: List<Profile>, val avatars: List<String>)

    private fun prefs(context: Context? = null) =
        (context ?: StreamFlixApp.instance).applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Volatile
    private var active: String? = null

    /** Perfil en uso ("00" = principal). */
    val activeId: String
        get() = active ?: (runCatching { prefs().getString(KEY_ACTIVE, MAIN_ID) }.getOrNull()
            ?.takeIf { it.matches(Regex("0[0-4]")) } ?: MAIN_ID).also { active = it }

    /** Sufijo del almacenamiento local del perfil activo ("" para el principal). */
    fun storageSuffix(): String = if (activeId == MAIN_ID) "" else "_p$activeId"

    /** Perfiles permitidos por la cuenta (1 = entra directo, sin pantalla de perfiles). */
    fun limit(context: Context? = null): Int = prefs(context).getInt(KEY_LIMIT, 1).coerceIn(1, 5)

    fun saveLimit(context: Context, limit: Int) {
        prefs(context).edit().putInt(KEY_LIMIT, limit.coerceIn(1, 5)).apply()
    }

    fun cachedAccount(context: Context): Account? =
        prefs(context).getString(KEY_CACHE, null)?.let { runCatching { parse(JSONObject(it)) }.getOrNull() }

    fun activeProfile(context: Context): Profile? = cachedAccount(context)?.profiles?.find { it.id == activeId }

    /**
     * Cambia de perfil: sube lo pendiente del perfil anterior y cierra sus bases para que
     * nada de un perfil se mezcle con otro. Luego hay que reiniciar la pantalla principal.
     */
    suspend fun switchTo(context: Context, id: String) {
        // Lo pendiente se sube a la nube del perfil que se deja (sin demorar si no hay internet).
        kotlinx.coroutines.withTimeoutOrNull(6_000) { runCatching { CloudSyncManager.syncNow(context) } }
        CloudSyncManager.reset()
        AppDatabase.resetInstance()
        active = id
        prefs(context).edit().putString(KEY_ACTIVE, id).commit()
    }

    /** Vuelve al principal (perfil eliminado u otra cuenta). Cierra las bases del perfil anterior. */
    fun resetToMain(context: Context) {
        CloudSyncManager.reset()
        AppDatabase.resetInstance()
        active = MAIN_ID
        prefs(context).edit().putString(KEY_ACTIVE, MAIN_ID).remove(KEY_CACHE).apply()
    }

    /**
     * El equipo guarda historial y favoritos de una sola cuenta. Si entra otra cuenta, se
     * borran los datos locales de la anterior (ya están en su nube) para no subirlos a la nueva.
     */
    suspend fun claimDevice(context: Context, username: String, freshLogin: Boolean) {
        val owners = context.applicationContext.getSharedPreferences("LocalDeviceOwner", Context.MODE_PRIVATE)
        val previous = owners.getString("owner", "").orEmpty()
        val sameAccount = previous.equals(username, ignoreCase = true)
        // La nube manda: al iniciar sesión en un equipo se borra lo local y se descarga la nube.
        val replace = when {
            previous.isNotEmpty() && !sameAccount -> true          // datos de otra cuenta
            !freshLogin -> false                                    // reapertura con la sesión guardada
            previous.isEmpty() -> true                              // datos locales de origen desconocido
            // Misma cuenta: primero se sube lo pendiente; sin internet se conserva lo local.
            else -> kotlinx.coroutines.withTimeoutOrNull(10_000) { runCatching { CloudSyncManager.syncNow(context) }.getOrDefault(false) } == true
        }
        if (replace) {
            wipeLocalUserData(context)
            resetToMain(context)
        }
        owners.edit().putString("owner", username).apply()
    }

    private fun wipeLocalUserData(context: Context) {
        CloudSyncManager.reset()
        AppDatabase.resetInstance()
        val bases = (com.streamflixreborn.streamflix.providers.Provider.providers.keys.map { it.name } +
                listOf("it", "en", "es", "de", "fr").map { com.streamflixreborn.streamflix.providers.TmdbProvider(it).name })
            .map(AppDatabase::sanitizeProviderName)
            .toSet()
        context.databaseList()
            .filter { it.endsWith(".db") && it.removeSuffix(".db").replace(Regex("_p0[1-4]$"), "") in bases }
            .forEach { context.deleteDatabase(it) }
        com.streamflixreborn.streamflix.utils.UserDataCache.clearAll(context)
        com.streamflixreborn.streamflix.utils.HomeCacheStore.clearAll(context)
    }

    // ---------------------------------------------------------------------------------------
    // API (api/profiles.php)
    // ---------------------------------------------------------------------------------------

    suspend fun load(context: Context): Account = call(context, null)

    suspend fun add(context: Context, name: String, avatar: String?): Account =
        call(context, JSONObject().put("action", "add").put("name", name).apply { avatar?.let { put("avatar", it) } })

    suspend fun edit(context: Context, id: String, name: String? = null, avatar: String? = null, pin: String? = null): Account =
        call(context, JSONObject().put("action", "edit").put("id", id).apply {
            name?.let { put("name", it) }
            avatar?.let { put("avatar", it) }
            pin?.let { put("pin", it) }
        })

    suspend fun delete(context: Context, id: String): Account =
        call(context, JSONObject().put("action", "delete").put("id", id))

    class ApiException(val code: String) : Exception(code)

    private suspend fun call(context: Context, body: JSONObject?): Account = withContext(Dispatchers.IO) {
        val endpoint = CloudSyncManager.endpoint("profiles.php")
        if (endpoint.isEmpty()) throw ApiException("endpoint")
        val gateway = prefs(context)
        val user = gateway.getString("alpha_token", null).orEmpty()
        val pass = gateway.getString("beta_token", null).orEmpty()
        if (user.isEmpty() || pass.isEmpty()) throw ApiException("auth")

        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.connectTimeout = 10_000
        connection.readTimeout = 10_000
        connection.setRequestProperty("X-Sync-User", URLEncoder.encode(user, "UTF-8"))
        connection.setRequestProperty("X-Sync-Pass", URLEncoder.encode(pass, "UTF-8"))
        if (body != null) {
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(body.toString()) }
        }
        val code = connection.responseCode
        val text = (if (code in 200..299) connection.inputStream else connection.errorStream)
            ?.bufferedReader()?.use { it.readText() }.orEmpty()
        val json = runCatching { JSONObject(text) }.getOrNull() ?: throw ApiException("http_$code")
        if (json.optString("status") != "success") throw ApiException(json.optString("message", "error"))

        val account = parse(json)
        gateway.edit()
            .putString(KEY_CACHE, json.toString())
            .putInt(KEY_LIMIT, account.limit)
            .apply()
        account
    }

    private fun parse(json: JSONObject): Account {
        val profiles = json.optJSONArray("profiles") ?: JSONArray()
        val avatars = json.optJSONArray("avatars") ?: JSONArray()
        return Account(
            limit = json.optInt("limit", 1).coerceIn(1, 5),
            profiles = (0 until profiles.length()).map { i ->
                val p = profiles.getJSONObject(i)
                Profile(
                    id = p.optString("id", MAIN_ID),
                    name = p.optString("name"),
                    avatar = p.optString("avatar"),
                    pin = p.optString("pin"),
                )
            },
            avatars = (0 until avatars.length()).map { avatars.getString(it) },
        )
    }
}
