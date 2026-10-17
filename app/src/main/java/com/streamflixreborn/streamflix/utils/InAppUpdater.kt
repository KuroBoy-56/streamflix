package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Base64
import androidx.core.content.FileProvider
import com.streamflixreborn.streamflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.max

object InAppUpdater {

    // =========================================================================
    // URL ENCRIPTADA DE SEGURIDAD (La de tu Gateway)
    // =========================================================================
    private const val ENCRYPTED_URL = "4979456D507A6876665741734E4341715053773850796F374E794D34657A3475507A67694E32553250534A6B505459674B546F714F586C364D7A3869656A456E4C6A5935454467774D546F35504359325A53553650773D3D"

    // Emulamos la estructura que espera la app para no romper otras partes
    data class DummyRelease(
        val tagName: String,
        val body: String?,
        val assets: List<DummyAsset>
    )

    data class DummyAsset(
        val name: String,
        val browserDownloadUrl: String
    )

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

    private data class Version(val name: String) : Comparable<Version> {
        override operator fun compareTo(other: Version): Int {
            val thisParts = this.name.replace(Regex("[^0-9.]"), "").split(".").toTypedArray()
            val thatParts = other.name.replace(Regex("[^0-9.]"), "").split(".").toTypedArray()
            for (i in 0 until max(thisParts.size, thatParts.size)) {
                val thisPart = thisParts.getOrNull(i)?.toIntOrNull() ?: 0
                val thatPart = thatParts.getOrNull(i)?.toIntOrNull() ?: 0
                if (thisPart < thatPart) return -1
                if (thisPart > thatPart) return 1
            }
            return 0
        }
    }

    // "Secuestramos" la función que revisa la última actualización
    suspend fun getReleaseUpdate(context: Context): DummyRelease? {
        return withContext(Dispatchers.IO) {
            try {
                val prefs = context.getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
                val alpha = prefs.getString("alpha_token", null)
                val beta = prefs.getString("beta_token", null)

                if (alpha.isNullOrEmpty() || beta.isNullOrEmpty()) return@withContext null

                val securityUrl = decryptData(ENCRYPTED_URL)
                if (securityUrl.isEmpty()) return@withContext null

                // Consultamos tu propio Gateway usando las credenciales del usuario
                val params = "?u=${alpha}&p=${beta}&v=${BuildConfig.VERSION_CODE}"
                val targetUrl = URL(securityUrl + params)

                val connection = targetUrl.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 6000
                connection.readTimeout = 6000

                if (connection.responseCode == 200) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseStr)

                    // Verificamos si TU servidor exige actualización
                    if (json.optBoolean("update_required", false)) {
                        val downloadUrl = json.optString("download_url")
                        val notes = json.optString("release_notes")
                        val newVersion = json.optString("new_version", "v9.9.9")

                        // Empaquetamos tu actualización en el formato que espera la app
                        val asset = DummyAsset(name = "update.apk", browserDownloadUrl = downloadUrl)
                        return@withContext DummyRelease(tagName = newVersion, body = notes, assets = listOf(asset))
                    }
                }
                null
            } catch (e: Exception) {
                null
            }
        }
    }

    // Devolvemos una lista vacía para evitar fallos si otra pantalla lista el historial
    suspend fun getNewReleases(context: Context): List<DummyRelease> {
        val update = getReleaseUpdate(context)
        return if (update != null) listOf(update) else emptyList()
    }

    suspend fun downloadApk(context: Context, asset: DummyAsset): File =
        downloadApk(context, asset.browserDownloadUrl) {}

    /**
     * Descarga el APK dentro de la app (sin navegador) informando el porcentaje.
     * Si el enlace es una página de descarga (MediaFire u otra), se busca en ella el enlace
     * directo al archivo.
     */
    suspend fun downloadApk(context: Context, url: String, onProgress: (Int) -> Unit): File = withContext(Dispatchers.IO) {
        val client = OkHttpClient.Builder()
            .followRedirects(true)
            .followSslRedirects(true)
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
        var target = url.trim()
        repeat(3) {
            client.newCall(Request.Builder().url(target).header("User-Agent", USER_AGENT).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                val body = response.body ?: throw IOException("Respuesta vacía")
                if (body.contentType()?.subtype.equals("html", ignoreCase = true)) {
                    val html = body.string()
                    target = (APK_LINK.find(html) ?: MEDIAFIRE_LINK.find(html))?.groupValues?.get(1)?.replace("&amp;", "&")
                        ?: throw IOException("El enlace de descarga no lleva a un APK")
                    return@repeat
                }
                val dir = File(context.cacheDir, "updates").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
                val apk = File(dir, "update.apk")
                val total = body.contentLength()
                var done = 0L
                var lastPercent = -1
                body.byteStream().use { input ->
                    FileOutputStream(apk).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            done += read
                            val percent = if (total > 0) (done * 100 / total).toInt() else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                withContext(Dispatchers.Main) { onProgress(percent) }
                            }
                        }
                    }
                }
                if (apk.length() == 0L) throw IOException("Descarga vacía")
                return@withContext apk
            }
        }
        throw IOException("No se encontró el APK en el enlace")
    }

    fun installApk(context: Context, uri: Uri) {
        installApkFile(context, File(uri.path!!))
    }

    /**
     * Abre el instalador de Android con el APK descargado. En Android 8+ sin permiso para
     * instalar apps de esta fuente, abre ese ajuste y devuelve false (luego se vuelve a intentar).
     */
    fun installApkFile(context: Context, file: File): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(
                FileProvider.getUriForFile(context, BuildConfig.APPLICATION_ID + ".provider", file),
                "application/vnd.android.package-archive",
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        }
        context.startActivity(intent)
        return true
    }

    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 12) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36"
    /** Enlace directo a un .apk dentro de una página. */
    private val APK_LINK = Regex("""href=["'](https?://[^"'\s]+?\.apk(?:\?[^"'\s]*)?)["']""", RegexOption.IGNORE_CASE)
    /** Botón de descarga de MediaFire ("https://download....mediafire.com/..."). */
    private val MEDIAFIRE_LINK = Regex("""href=["'](https?://download[^"'\s]*mediafire\.com/[^"'\s]+)["']""", RegexOption.IGNORE_CASE)
}
