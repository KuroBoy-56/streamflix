package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.os.Build
import android.util.Log
import com.streamflixreborn.streamflix.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Envía al panel (api/log_error.php) cualquier error de la app: cierres inesperados y errores
 * no fatales (reproducción, proveedores, sincronización, inicio de sesión...).
 *
 * - Los reportes se guardan primero en una cola local: si no hay internet o la app se cierra,
 *   se envían en el próximo arranque.
 * - Errores idénticos se agrupan (máx. uno cada 10 min) para no saturar el panel.
 */
object ErrorReporter {

    private const val TAG = "ErrorReporter"
    private const val PREFS = "error_reporter"
    private const val KEY_QUEUE = "queue"
    private const val MAX_QUEUE = 50
    private const val THROTTLE_MS = 10 * 60 * 1000L

    private var appContext: Context? = null
    private var endpoint: String = ""
    private var appName: String = ""
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "error-reporter").apply { isDaemon = true } }
    private val lastSent = ConcurrentHashMap<String, Long>()

    fun init(context: Context, endpointUrl: String, name: String) {
        appContext = context.applicationContext
        endpoint = endpointUrl
        appName = name
        flushAsync()
    }

    /** Error no fatal. */
    fun report(type: String, message: String?, error: Throwable? = null) {
        val key = "$type|${message?.take(120)}|${error?.javaClass?.name}"
        val now = System.currentTimeMillis()
        val last = lastSent[key]
        if (last != null && now - last < THROTTLE_MS) return
        lastSent[key] = now
        enqueue(build(type, message ?: error?.message ?: "Sin mensaje", error))
        flushAsync()
    }

    /**
     * Cierre inesperado: se guarda de inmediato (síncrono, sin red) y se intenta enviar
     * unos segundos en otro hilo antes de dejar que la app se cierre.
     */
    fun reportCrash(error: Throwable) {
        enqueue(build("CRASH: ${error.javaClass.simpleName}", error.message ?: "Sin mensaje", error), commit = true)
        val sender = Thread { flush() }
        sender.start()
        runCatching { sender.join(3000) }
    }

    private fun build(type: String, message: String, error: Throwable?): JSONObject {
        val ctx = appContext
        val gateway = ctx?.getSharedPreferences(AssignedProviders.PREFS_NAME, Context.MODE_PRIVATE)
        val provider = runCatching { UserPreferences.currentProvider?.name }.getOrNull().orEmpty()
        val user = gateway?.getString("alpha_token", "").orEmpty()
        val appId = runCatching { UserPreferences.savedAppId }.getOrDefault(0)
        return JSONObject().apply {
            put("tipo", type.take(120))
            put("mensaje", message.take(2000))
            put("stacktrace", error?.let { Log.getStackTraceString(it).take(12000) }.orEmpty())
            put("dispositivo", "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})")
            put("version_app", BuildConfig.VERSION_NAME)
            put("app_name", appName)
            put("usuario", user)
            put("proveedor", provider)
            put("app_id", appId)
        }
    }

    @Synchronized
    private fun enqueue(item: JSONObject, commit: Boolean = false) {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        val queue = runCatching { JSONArray(prefs.getString(KEY_QUEUE, "[]")) }.getOrDefault(JSONArray())
        queue.put(item)
        while (queue.length() > MAX_QUEUE) queue.remove(0)
        val editor = prefs.edit().putString(KEY_QUEUE, queue.toString())
        if (commit) editor.commit() else editor.apply()
    }

    fun flushAsync() {
        executor.execute { flush() }
    }

    @Synchronized
    private fun flush() {
        val prefs = appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE) ?: return
        if (endpoint.isEmpty()) return
        val queue = runCatching { JSONArray(prefs.getString(KEY_QUEUE, "[]")) }.getOrDefault(JSONArray())
        if (queue.length() == 0) return
        val pending = JSONArray()
        for (i in 0 until queue.length()) {
            val item = queue.optJSONObject(i) ?: continue
            if (!send(item)) pending.put(item)
        }
        prefs.edit().putString(KEY_QUEUE, pending.toString()).commit()
    }

    private fun send(item: JSONObject): Boolean = try {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 8000
        connection.readTimeout = 8000
        connection.doOutput = true
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
        OutputStreamWriter(connection.outputStream, Charsets.UTF_8).use { it.write(item.toString()) }
        val code = connection.responseCode
        connection.disconnect()
        code in 200..299
    } catch (e: Exception) {
        Log.w(TAG, "No se pudo enviar el reporte: ${e.message}")
        false
    }
}
