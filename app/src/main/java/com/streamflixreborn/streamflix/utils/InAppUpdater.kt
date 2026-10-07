package com.streamflixreborn.streamflix.utils

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Base64
import androidx.core.content.FileProvider
import com.streamflixreborn.streamflix.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
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

    suspend fun downloadApk(context: Context, asset: DummyAsset): File {
        context.cacheDir.listFiles()
            ?.filter { it.extension == "apk" }
            ?.forEach { it.deleteOnExit() }

        val apk = withContext(Dispatchers.IO) {
            File.createTempFile(
                "${File(asset.name).nameWithoutExtension}-",
                ".${File(asset.name).extension}"
            )
        }

        withContext(Dispatchers.IO) {
            URL(asset.browserDownloadUrl).openStream()
        }.use { input ->
            FileOutputStream(apk).use { output -> input.copyTo(output) }
        }

        return apk
    }

    fun installApk(context: Context, uri: Uri) {
        val intent = Intent(Intent.ACTION_VIEW).also { intent ->
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) // Agregado para TV / Background
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
            intent.data = FileProvider.getUriForFile(
                context,
                BuildConfig.APPLICATION_ID + ".provider",
                File(uri.path!!)
            )
        }
        context.startActivity(intent)
    }
}