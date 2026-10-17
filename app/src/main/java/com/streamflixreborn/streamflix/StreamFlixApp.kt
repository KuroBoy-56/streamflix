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
import android.provider.Settings
import android.util.Log
import java.security.MessageDigest
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
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
import com.streamflixreborn.streamflix.utils.InAppUpdater
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class StreamFlixApp : Application() {
    companion object {
        lateinit var instance: StreamFlixApp
            private set

        @Volatile
        var currentActivity: Activity? = null
            private set

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
        verifyAppSignature()

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            private var started = 0
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) { started++ }
            override fun onActivityResumed(activity: Activity) { currentActivity = activity }
            override fun onActivityPaused(activity: Activity) { if (currentActivity === activity) currentActivity = null }
            override fun onActivityStopped(activity: Activity) {
                started = (started - 1).coerceAtLeast(0)
                // App en segundo plano: ahora sí se cambia el icono/nombre a la marca del distribuidor.
                if (started == 0) com.streamflixreborn.streamflix.utils.LauncherBrand.applyPending(this@StreamFlixApp)
            }
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

        // Al abrir y luego cada 30 min: una línea vencida, bloqueada o eliminada se corta aunque
        // la app quede abierta (TV encendida días).
        applicationScope.launch {
            while (true) {
                runSecurityChecks()
                kotlinx.coroutines.delay(30 * 60 * 1000L)
            }
        }

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
        com.streamflixreborn.streamflix.utils.ErrorReporter.init(
            this,
            decryptData(ENCRYPTED_CRASH_URL),
            runCatching { getString(R.string.app_name) }.getOrDefault("Streamflix")
        )
        val defaultUncaughtExceptionHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            try {
                com.streamflixreborn.streamflix.utils.ErrorReporter.reportCrash(exception)
            } catch (_: Throwable) {
            } finally {
                defaultUncaughtExceptionHandler?.uncaughtException(thread, exception)
            }
        }
    }

    // OFUSCACIÓN DE GRADO EMPRESARIAL - Protege la llave de extracción y la lógica
    private fun decryptData(payload: String): String {
        return try {
            val b = ByteArray(payload.length / 2)
            for (i in b.indices) {
                b[i] = ((Character.digit(payload[i * 2], 16) shl 4) + Character.digit(payload[i * 2 + 1], 16)).toByte()
            }
            val d = android.util.Base64.decode(String(b, Charsets.UTF_8), android.util.Base64.NO_WRAP)
            val k = byteArrayOf((150 shr 1).toByte(), (170 shr 1).toByte(), (164 shr 1).toByte(), (158 shr 1).toByte())
            val r = ByteArray(d.size)
            for (i in d.indices) r[i] = (d[i].toInt() xor k[i % 4].toInt()).toByte()
            String(r, Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    private fun getNormalizedMacAddress(): String {
        val rawId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "0000000000000000"
        val cleanId = rawId.replace("[^a-fA-F0-9]".toRegex(), "").padEnd(16, '0')
        return cleanId.substring(0, 16).chunked(2).joinToString(":").uppercase(Locale.getDefault())
    }

    private fun generateSecurityToken(username: String, mac: String): String {
        try {
            val secretKey = "kuropanchi950125"
            val sdf = SimpleDateFormat("yyyy-MM-dd-HH", Locale.US)
            sdf.timeZone = TimeZone.getTimeZone("America/Panama")
            val currentHourDate = sdf.format(Date())

            val stringToHash = "$username$mac$currentHourDate$secretKey"
            val bytes = MessageDigest.getInstance("SHA-256").digest(stringToHash.toByteArray(Charsets.UTF_8))
            return bytes.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            return ""
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyAppSignature() {
        if (BuildConfig.DEBUG) return
        try {
            val packageInfo = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            packageInfo.signatures?.let { signatures ->
                for (signature in signatures) {
                    val md = MessageDigest.getInstance("SHA-256")
                    md.update(signature.toByteArray())
                    val currentHash = android.util.Base64.encodeToString(md.digest(), android.util.Base64.NO_WRAP).trim()
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

        val hardwareMac = getNormalizedMacAddress()
        val securityToken = generateSecurityToken(alpha, hardwareMac)

        val lastCheck = prefs.getLong("last_online_check", System.currentTimeMillis())
        val sevenDaysMillis = 7L * 24 * 60 * 60 * 1000

        applicationScope.launch(Dispatchers.IO) {
            try {
                val securityUrl = decryptData(ENCRYPTED_URL)
                if (securityUrl.isEmpty()) {
                    handleOfflineMode(lastCheck, sevenDaysMillis)
                    return@launch
                }

                val params = "?u=${URLEncoder.encode(alpha, "UTF-8")}&p=${URLEncoder.encode(beta, "UTF-8")}&v=${BuildConfig.VERSION_CODE}&mac=${URLEncoder.encode(hardwareMac, "UTF-8")}&token=${URLEncoder.encode(securityToken, "UTF-8")}"
                val targetUrl = URL(securityUrl + params)
                val connection = targetUrl.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 6000
                connection.readTimeout = 6000

                if (connection.responseCode == 200) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val json = JSONObject(responseStr)

                    val status = json.optString("status")
                    val isAuthFailed = json.optJSONObject("user_info")?.optInt("auth", 1) == 0

                    if (status == "banned" || isAuthFailed) {
                        forceLogoutAndKill()
                    } else {
                        prefs.edit().putLong("last_online_check", System.currentTimeMillis()).apply()
                        if (json.optBoolean("update_required", false)) {
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

    /**
     * Actualización obligatoria: se descarga dentro de la app con barra de porcentaje y al
     * terminar se abre el instalador (antes abría el navegador).
     */
    @Volatile private var updateDialogShown = false

    private suspend fun showMandatoryUpdateDialog(downloadUrl: String, notes: String) {
        withContext(Dispatchers.Main) {
            kotlinx.coroutines.delay(1500)
            if (updateDialogShown) return@withContext // la revisión de cada 30 min no repite el aviso
            val activity = currentActivity ?: return@withContext
            updateDialogShown = true
            val pad = (20 * activity.resources.displayMetrics.density).toInt()
            val message = android.widget.TextView(activity).apply {
                text = activity.getString(R.string.update_required_message) + if (notes.isBlank()) "" else "\n\n$notes"
                textSize = 15f
            }
            val progress = android.widget.ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 100
                visibility = android.view.View.GONE
            }
            val status = android.widget.TextView(activity).apply {
                textSize = 14f
                visibility = android.view.View.GONE
            }
            val content = android.widget.LinearLayout(activity).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                setPadding(pad, pad / 2, pad, 0)
                addView(message)
                addView(progress, android.widget.LinearLayout.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = pad })
                addView(status)
            }
            val dialog = AlertDialog.Builder(activity)
                .setTitle(R.string.update_required_title)
                .setView(content)
                .setCancelable(false)
                .setPositiveButton(R.string.update_download, null)
                .create()
            var apk: java.io.File? = null
            dialog.setOnShowListener {
                val button = dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)
                button.setOnClickListener {
                    // Ya descargado: solo se abre el instalador (p. ej. tras dar el permiso).
                    apk?.let { file ->
                        if (!InAppUpdater.installApkFile(activity.applicationContext, file)) status.setText(R.string.update_allow_install)
                        return@setOnClickListener
                    }
                    button.isEnabled = false
                    progress.visibility = android.view.View.VISIBLE
                    progress.isIndeterminate = true
                    status.visibility = android.view.View.VISIBLE
                    status.text = activity.getString(R.string.update_downloading, 0)
                    applicationScope.launch(Dispatchers.Main) {
                        try {
                            val file = InAppUpdater.downloadApk(activity, downloadUrl) { percent ->
                                if (percent >= 0) {
                                    progress.isIndeterminate = false
                                    progress.progress = percent
                                    status.text = activity.getString(R.string.update_downloading, percent)
                                }
                            }
                            apk = file
                            progress.isIndeterminate = false
                            progress.progress = 100
                            status.setText(R.string.update_ready)
                            button.setText(R.string.update_install)
                            button.isEnabled = true
                            if (!InAppUpdater.installApkFile(activity.applicationContext, file)) status.setText(R.string.update_allow_install)
                        } catch (e: Exception) {
                            com.streamflixreborn.streamflix.utils.ErrorReporter.report("Update.Descarga", e.message, e)
                            progress.visibility = android.view.View.GONE
                            status.setText(R.string.update_failed)
                            button.setText(R.string.update_retry)
                            button.isEnabled = true
                        }
                    }
                }
            }
            dialog.show()
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