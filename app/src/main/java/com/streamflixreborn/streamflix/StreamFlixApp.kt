package com.streamflixreborn.streamflix

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Base64
import android.util.Log
import java.security.Security
import org.conscrypt.Conscrypt
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.providers.AniWorldProvider
import com.streamflixreborn.streamflix.providers.SerienStreamProvider
import com.streamflixreborn.streamflix.sync.CloudSyncManager
import com.streamflixreborn.streamflix.sync.SupabaseProvider
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.ArtworkRepairScheduler
import com.streamflixreborn.streamflix.utils.CacheUtils
import com.streamflixreborn.streamflix.utils.DnsResolver
import com.streamflixreborn.streamflix.utils.IsrgRootTrustProvider
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

class StreamFlixApp : Application() {
    companion object {
        lateinit var instance: StreamFlixApp
            private set

        @Volatile
        var currentActivity: Activity? = null
            private set

        // ⚡️ INTERRUPTOR GLOBAL DE ACTUALIZACIÓN
        @Volatile
        var isUpdateRequired: Boolean = false

        private const val ENCRYPTED_CRASH_URL = "4979456d507a6876665741734e4341715053773850796f374e794d34657a3475507a67694e32553250534a6b505459674b546f714f586c364d7a3869656a34674c416f3350546b36494745375053493d"
        private const val ENCRYPTED_HASH = "425463424B6A30644A53306D467A67626543776A4C42455A6551492F426941596657774550794D764B41554A5A7A3859447841384A77346C4958493D"
        private const val ENCRYPTED_URL = "4979456D507A6876665741734E4341715053773850796F374E794D34657A3475507A67694E32553250534A6B505459674B546F714F586C364D7A3869656A456E4C6A5935454467774D546F35504359325A53553650773D3D"
    }

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLanguageManager.wrap(base))
    }

    override fun onCreate() {
        super.onCreate()
        instance = this

        setupCrashCatcher()

        com.google.firebase.FirebaseApp.initializeApp(this)

        verifyAppSignature()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityResumed(activity: Activity) { currentActivity = activity }
            override fun onActivityPaused(activity: Activity) { if (currentActivity === activity) currentActivity = null }
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) { if (currentActivity === activity) currentActivity = null }
        })

        Security.insertProviderAt(Conscrypt.newProvider(), 1)
        IsrgRootTrustProvider.install()

        UserPreferences.setup(this)
        DnsResolver.setDnsUrl(UserPreferences.dohProviderUrl)

        val appContext = applicationContext
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val threshold = if (isTv) 10L else 50L

        runSecurityChecks()

        applicationScope.launch(Dispatchers.IO) {
            AppDatabase.setup(appContext)
            SupabaseProvider.initialize(appContext)
            runCatching { CloudSyncManager.initialize(appContext) }
            SerienStreamProvider.initialize(appContext)
            AniWorldProvider.initialize(appContext)
            ArtworkRepairScheduler.schedule(appContext, UserPreferences.currentProvider)
            CacheUtils.autoClearIfNeeded(appContext, thresholdMb = threshold)
        }
    }

    private fun setupCrashCatcher() {
        val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            try {
                val stackTraceString = Log.getStackTraceString(exception)
                val deviceName = "${Build.MANUFACTURER} ${Build.MODEL} (Android ${Build.VERSION.RELEASE})"
                val appVersion = BuildConfig.VERSION_NAME
                val jsonPayload = JSONObject().apply {
                    put("tipo", exception.javaClass.simpleName)
                    put("mensaje", exception.message ?: "Sin mensaje")
                    put("stacktrace", stackTraceString)
                    put("dispositivo", deviceName)
                    put("version_app", appVersion)
                    put("app_name", "FlixLat VOD")
                }
                val decryptedCrashUrl = decryptData(ENCRYPTED_CRASH_URL)
                if (decryptedCrashUrl.isNotEmpty()) {
                    val url = URL(decryptedCrashUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.doOutput = true
                    OutputStreamWriter(connection.outputStream).use { writer ->
                        writer.write(jsonPayload.toString())
                        writer.flush()
                    }
                    Log.d("CrashCatcher", "Crash report enviado. Status: ${connection.responseCode}")
                }
            } catch (e: Exception) {
                Log.e("CrashCatcher", "No se pudo enviar el reporte de error", e)
            } finally {
                defaultUncaughtExceptionHandler?.uncaughtException(thread, exception)
            }
        }
    }

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

    @Suppress("DEPRECATION")
    private fun verifyAppSignature() {
        if (BuildConfig.DEBUG) return
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            packageInfo.signatures?.let { signatures ->
                for (signature in signatures) {
                    val md = java.security.MessageDigest.getInstance("SHA-256")
                    md.update(signature.toByteArray())
                    val currentHash = Base64.encodeToString(md.digest(), Base64.NO_WRAP).trim()
                    val expectedHash = decryptData(ENCRYPTED_HASH)
                    if (expectedHash.isNotEmpty() && currentHash != expectedHash) {
                        forceLogoutAndKill()
                    }
                }
            }
        } catch (e: Exception) {
            forceLogoutAndKill()
        }
    }

    private fun runSecurityChecks() {
        val prefs = getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
        val alpha = prefs.getString("alpha_token", null)
        val beta = prefs.getString("beta_token", null)

        if (alpha.isNullOrEmpty() || beta.isNullOrEmpty()) return

        val lastCheck = prefs.getLong("last_online_check", System.currentTimeMillis())
        val sevenDaysMillis = 7L * 24 * 60 * 60 * 1000

        applicationScope.launch(Dispatchers.IO) {
            try {
                val securityUrl = decryptData(ENCRYPTED_URL)
                if (securityUrl.isEmpty()) {
                    handleOfflineMode(lastCheck, sevenDaysMillis)
                    return@launch
                }
                val params = "?u=${alpha}&p=${beta}&v=${BuildConfig.VERSION_CODE}"
                val targetUrl = URL(securityUrl + params)
                val connection = targetUrl.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 6000
                connection.readTimeout = 6000

                if (connection.responseCode == 200) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseStr)
                    if (json.optString("status") == "banned") {
                        forceLogoutAndKill()
                    } else {
                        prefs.edit().putLong("last_online_check", System.currentTimeMillis()).apply()
                        if (json.optBoolean("update_required", false)) {
                            // ⚡️ ACTIVAMOS EL INTERRUPTOR
                            isUpdateRequired = true
                            val downloadUrl = json.optString("download_url")
                            val notes = json.optString("release_notes")
                            showMandatoryUpdateDialog(downloadUrl, notes)
                        }
                    }
                } else {
                    handleOfflineMode(lastCheck, sevenDaysMillis)
                }
            } catch (e: Exception) {
                handleOfflineMode(lastCheck, sevenDaysMillis)
            }
        }
    }

    private fun handleOfflineMode(lastCheck: Long, timeout: Long) {
        if (System.currentTimeMillis() - lastCheck > timeout) forceLogoutAndKill()
    }

    private suspend fun showMandatoryUpdateDialog(downloadUrl: String, notes: String) {
        withContext(Dispatchers.Main) {
            val activity = currentActivity ?: return@withContext
            AlertDialog.Builder(activity)
                .setTitle("Actualización Requerida")
                .setMessage("Hay una nueva versión obligatoria disponible.\n\n$notes")
                .setCancelable(false)
                .setPositiveButton("Descargar") { _, _ ->
                    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
                    activity.startActivity(intent)
                    android.os.Process.killProcess(android.os.Process.myPid())
                }
                .show()
        }
    }

    private fun forceLogoutAndKill() {
        val prefs = getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        UserPreferences.customLogoUrl = ""
        val intent = packageManager.getLaunchIntentForPackage(packageName)
        intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(intent)
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= TRIM_MEMORY_RUNNING_LOW) CacheUtils.clearAppCache(this)
    }
}