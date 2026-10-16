package com.streamflixreborn.streamflix.activities.sync

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.sync.CloudSyncManager
import com.streamflixreborn.streamflix.utils.AssignedProviders
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.security.MessageDigest

class SyncGatewayActivity : AppCompatActivity() {

    private lateinit var bgGateway: ImageView
    private lateinit var logoGateway: ImageView
    private lateinit var inputAlpha: EditText
    private lateinit var inputBeta: EditText
    private lateinit var btnSync: Button
    private lateinit var progressSync: ProgressBar
    private lateinit var txtStatus: TextView

    private var isPasswordVisible = false


    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(com.streamflixreborn.streamflix.utils.AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_gateway)

        bgGateway = findViewById(R.id.bgGateway)
        logoGateway = findViewById(R.id.logoGateway)
        inputAlpha = findViewById(R.id.inputAlpha)
        inputBeta = findViewById(R.id.inputBeta)
        btnSync = findViewById(R.id.btnSync)
        progressSync = findViewById(R.id.progressSync)
        txtStatus = findViewById(R.id.txtStatus)

        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)

        setupEyeToggle()
        adjustLayoutForTv()
        setupFocusListeners(isTv)

        // Logo y fondo guardados en el equipo (aparecen al instante, también sin internet).
        showBrandImages(isTv)

        val prefs = getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)

        val savedAlpha = prefs.getString("alpha_token", null)
        val savedBeta = prefs.getString("beta_token", null)

        if (!savedAlpha.isNullOrEmpty() && !savedBeta.isNullOrEmpty()) {
            inputAlpha.setText(savedAlpha)
            inputBeta.setText(savedBeta)
            executeGatewayHandshake(savedAlpha, savedBeta, isAutoLogin = true)
        }

        btnSync.setOnClickListener {
            val alphaVal = inputAlpha.text.toString().trim()
            val betaVal = inputBeta.text.toString().trim()

            if (alphaVal.isEmpty() || betaVal.isEmpty()) {
                showStatus(getString(R.string.login_empty_fields))
                return@setOnClickListener
            }

            executeGatewayHandshake(alphaVal, betaVal, isAutoLogin = false)
        }
    }

    private fun adjustLayoutForTv() {
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val loginScroll = findViewById<View>(R.id.loginScroll)
        val loginContainer = findViewById<View>(R.id.loginContainer)
        val params = loginScroll.layoutParams as ConstraintLayout.LayoutParams

        if (isTv) {
            params.horizontalBias = 0.9f
            loginScroll.layoutParams = params
            loginContainer.setBackgroundColor(Color.parseColor("#99000000"))
        } else {
            params.horizontalBias = 0.5f
            loginScroll.layoutParams = params
            loginContainer.setBackgroundColor(Color.parseColor("#991A1D29"))
        }
    }

    private fun setupFocusListeners(isTv: Boolean) {
        if (!isTv) return

        inputAlpha.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                inputAlpha.setBackgroundColor(Color.parseColor("#33FFFFFF"))
                inputAlpha.scaleX = 1.02f
                inputAlpha.scaleY = 1.02f
            } else {
                inputAlpha.setBackgroundColor(Color.parseColor("#0F111A"))
                inputAlpha.scaleX = 1.0f
                inputAlpha.scaleY = 1.0f
            }
        }

        inputBeta.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                inputBeta.setBackgroundColor(Color.parseColor("#33FFFFFF"))
                inputBeta.scaleX = 1.02f
                inputBeta.scaleY = 1.02f
            } else {
                inputBeta.setBackgroundColor(Color.parseColor("#0F111A"))
                inputBeta.scaleX = 1.0f
                inputBeta.scaleY = 1.0f
            }
        }

        btnSync.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                btnSync.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#FF5252"))
                btnSync.scaleX = 1.05f
                btnSync.scaleY = 1.05f
            } else {
                btnSync.backgroundTintList = ColorStateList.valueOf(Color.parseColor("#E50914"))
                btnSync.scaleX = 1.0f
                btnSync.scaleY = 1.0f
            }
        }
    }

    private fun setupEyeToggle() {
        updateEyeIcon()
        inputBeta.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_UP) {
                val drawableRight = 2
                val drawable = inputBeta.compoundDrawables[drawableRight]
                if (drawable != null && event.x >= (inputBeta.width - inputBeta.paddingRight - drawable.bounds.width() - 30)) {
                    isPasswordVisible = !isPasswordVisible
                    updateEyeIcon()
                    return@setOnTouchListener true
                }
            }
            false
        }
        inputBeta.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                isPasswordVisible = !isPasswordVisible
                updateEyeIcon()
                return@setOnKeyListener true
            }
            false
        }
    }

    private fun updateEyeIcon() {
        val drawable = ContextCompat.getDrawable(this, android.R.drawable.ic_menu_view)?.mutate()
        if (isPasswordVisible) {
            drawable?.alpha = 255
            inputBeta.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        } else {
            drawable?.alpha = 100
            inputBeta.inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        inputBeta.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null)
        inputBeta.setSelection(inputBeta.text.length)
    }

    // OFUSCACIÓN EMPRESARIAL PARA DECODIFICAR LAS URLs
    private fun decodeBase64Hex(payload: String): String {
        return try {
            val b = ByteArray(payload.length / 2)
            for (i in b.indices) {
                b[i] = ((Character.digit(payload[i * 2], 16) shl 4) + Character.digit(payload[i * 2 + 1], 16)).toByte()
            }
            String(android.util.Base64.decode(String(b, Charsets.UTF_8), android.util.Base64.NO_WRAP), Charsets.UTF_8)
        } catch (e: Exception) { "" }
    }

    private fun getSecureEndpoint(): String {
        return decodeBase64Hex("6148523063484D364C79396E59584A6C646E6C75634746755A57787A4C6D7868644731776543356A6232307661475276596D3934646A4976595842704C325A736158687359585266595842704C6E426F63413D3D")
    }

    private fun getNormalizedMacAddress(): String = com.streamflixreborn.streamflix.utils.DeviceMac.get(this)

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

    private fun executeGatewayHandshake(alpha: String, beta: String, isAutoLogin: Boolean) {
        setLoadingState(true)

        val hardwareMac = getNormalizedMacAddress()
        val endpointUrl = getSecureEndpoint()
        val securityToken = generateSecurityToken(alpha, hardwareMac)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val params = "username=${URLEncoder.encode(alpha, "UTF-8")}&password=${URLEncoder.encode(beta, "UTF-8")}&mac=${URLEncoder.encode(hardwareMac, "UTF-8")}&token=${URLEncoder.encode(securityToken, "UTF-8")}"
                val targetUrl = URL("$endpointUrl?$params")

                val connection = targetUrl.openConnection() as HttpURLConnection
                connection.requestMethod = "GET"
                connection.connectTimeout = 10000
                connection.readTimeout = 10000

                if (connection.responseCode == 200) {
                    val responseStr = connection.inputStream.bufferedReader().use { it.readText() }
                    val jsonResponse = JSONObject(responseStr)

                    withContext(Dispatchers.Main) {
                        if (jsonResponse.has("user_info") && jsonResponse.getJSONObject("user_info").optInt("auth", 0) == 1) {

                            val serversArray = jsonResponse.optJSONArray("assigned_servers")
                            val namesList = mutableListOf<String>()

                            if (serversArray != null) {
                                for (i in 0 until serversArray.length()) {
                                    val sName = serversArray.getJSONObject(i).optString("name", "")
                                    if (sName.isNotEmpty()) namesList.add(sName)
                                }
                            }

                            val channelLines = com.streamflixreborn.streamflix.sync.PanelChannelConfig.lines(jsonResponse.opt("channel_lines"))
                            val personalUrl = com.streamflixreborn.streamflix.sync.PanelChannelConfig.lines(jsonResponse.opt("personal_provider_url"))

                            if (namesList.isEmpty() && channelLines.isEmpty() && personalUrl.isEmpty()) {
                                showStatus(getString(R.string.login_no_servers))
                                setLoadingState(false)
                                if (isAutoLogin) clearSecureCredentials()
                                return@withContext
                            }

                            val customLogoUrl = jsonResponse.optString("custom_logo", "").replace("null", "").trim()
                            val customBgMobileUrl = jsonResponse.optString("custom_background", "").replace("null", "").trim()
                            val customBgTvUrl = jsonResponse.optString("custom_background_tv", "").replace("null", "").trim()
                            val customSplashUrl = jsonResponse.optString("custom_splash", "").replace("null", "").trim()
                            val tmdbApiKey = jsonResponse.optString("tmdb_api_key", "").replace("null", "").trim()
                            val appId = jsonResponse.optInt("app_id", 0)

                            UserPreferences.customLogoUrl = customLogoUrl
                            UserPreferences.savedAppId = appId

                            if (tmdbApiKey.isNotEmpty()) {
                                UserPreferences.tmdbApiKey = tmdbApiKey
                                UserPreferences.enableTmdb = true
                            } else {
                                UserPreferences.enableTmdb = false
                            }

                            getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE).edit().apply {
                                putString("custom_background_url", customBgMobileUrl)
                                putString("custom_background_tv_url", customBgTvUrl)
                                putString("custom_splash_url", customSplashUrl)
                                putString(AssignedProviders.KEY_ASSIGNED_SERVERS, namesList.joinToString(","))
                                putString("brand_name", jsonResponse.optString("app_name", "").replace("null", "").trim())
                                putString(AssignedProviders.KEY_DEFAULT_PROVIDER, jsonResponse.optString("default_provider", "").replace("null", "").trim())
                                putString(com.streamflixreborn.streamflix.providers.PanelM3uProvider.KEY_CHANNEL_LINES, channelLines.joinToString("\n"))
                                putString(com.streamflixreborn.streamflix.providers.PanelM3uProvider.KEY_PERSONAL_URL, personalUrl.joinToString("\n"))
                                putString(com.streamflixreborn.streamflix.providers.PanelM3uProvider.KEY_DEFAULT_LINE, jsonResponse.optString("default_channel_line", "").replace("null", "").trim())
                                putInt(CloudSyncManager.KEY_CHANNEL_LINES_FROM, jsonResponse.optInt("channel_lines_from", 0))
                                putString("settings_note", jsonResponse.optString("settings_note", "").replace("null", "").trim())
                                commit()
                            }
                            com.streamflixreborn.streamflix.profiles.ProfileManager.saveLimit(this@SyncGatewayActivity, jsonResponse.optInt("profile_limit", 1))
                            val accountName = jsonResponse.optJSONObject("user_info")?.optString("username").orEmpty().ifEmpty { alpha }

                            AssignedProviders.normalizeCurrentProvider(this@SyncGatewayActivity)

                            runCatching { showBrandImages(packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)) }
                            lifecycleScope.launch { com.streamflixreborn.streamflix.utils.BrandAssets.refresh(applicationContext) }

                            saveSecureCredentials(alpha, beta)

                            CoroutineScope(Dispatchers.IO).launch {
                                // Inicio de sesión en este equipo: se borra lo local y se descarga la nube.
                                com.streamflixreborn.streamflix.profiles.ProfileManager.claimDevice(
                                    this@SyncGatewayActivity, accountName, freshLogin = !isAutoLogin,
                                )
                                CloudSyncManager.initialize(this@SyncGatewayActivity)
                                kotlinx.coroutines.delay(1800)
                                withContext(Dispatchers.Main) {
                                    if (com.streamflixreborn.streamflix.StreamFlixApp.isUpdateRequired) {
                                        setLoadingState(false)
                                        showStatus(getString(R.string.login_update_pending))
                                        return@withContext
                                    }

                                    // El aviso de vencimiento sale en la pantalla principal (no aquí).
                                    confirmBrandThenRoute(appId)
                                }
                            }

                        } else {
                            val errorMsg = jsonResponse.optJSONObject("user_info")?.optString("message")?.takeIf { it.isNotBlank() } ?: getString(R.string.login_access_denied)
                            showStatus(errorMsg)
                            setLoadingState(false)
                            if (isAutoLogin) clearSecureCredentials()
                        }
                    }
                } else {
                    val code = connection.responseCode
                    com.streamflixreborn.streamflix.utils.ErrorReporter.report("Login.Servidor", "HTTP $code")
                    withContext(Dispatchers.Main) {
                        if (isAutoLogin && enterWithCachedSession()) return@withContext
                        showStatus(getString(R.string.login_server_unreachable, code.toString()))
                        setLoadingState(false)
                    }
                }
            } catch (e: Exception) {
                com.streamflixreborn.streamflix.utils.ErrorReporter.report("Login.Conexion", e.message, e)
                withContext(Dispatchers.Main) {
                    if (isAutoLogin && enterWithCachedSession()) return@withContext
                    showStatus(getString(R.string.login_connection_error))
                    setLoadingState(false)
                }
            }
        }
    }

    /**
     * Primera vez en una red con marca blanca: se avisa que el icono y el nombre cambiarán a la
     * marca del distribuidor; con "Entendido" se programa el cambio (al salir de la app).
     */
    private fun confirmBrandThenRoute(appId: Int) {
        val brand = com.streamflixreborn.streamflix.utils.LauncherBrand
        if (!brand.needsChange(this, appId)) {
            routeToMainInterface()
            return
        }
        if (appId == 0) {
            brand.schedule(this, 0) // vuelve al icono del dueño sin preguntar
            routeToMainInterface()
            return
        }
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle(R.string.brand_change_title)
            .setMessage(R.string.brand_change_message)
            .setCancelable(false)
            .setPositiveButton(R.string.brand_change_ok) { _, _ ->
                brand.schedule(this, appId)
                routeToMainInterface()
            }
            .show()
    }

    private fun enterWithCachedSession(): Boolean {
        if (!AssignedProviders.hasPanelAssignment(this)) return false
        AssignedProviders.normalizeCurrentProvider(this)
        routeToMainInterface()
        return true
    }

    private fun saveSecureCredentials(alpha: String, beta: String) {
        val prefs = getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("alpha_token", alpha)
            putString("beta_token", beta)
            apply()
        }
    }

    private fun clearSecureCredentials() {
        val prefs = getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    /**
     * Cuenta con 2 o más dispositivos: "¿Quién está viendo?". Con 1 dispositivo se entra
     * directo con el perfil principal.
     */
    private fun routeToMainInterface() {
        val profiles = com.streamflixreborn.streamflix.profiles.ProfileManager
        if (profiles.limit(this) >= 2) {
            com.streamflixreborn.streamflix.profiles.ProfilesActivity.start(this)
            finish()
            return
        }
        lifecycleScope.launch {
            profiles.switchTo(this@SyncGatewayActivity, profiles.MAIN_ID)
            CloudSyncManager.initialize(this@SyncGatewayActivity)
            com.streamflixreborn.streamflix.profiles.ProfilesActivity.openMain(this@SyncGatewayActivity)
            finish()
        }
    }

    private fun showBrandImages(isTv: Boolean) {
        val brand = com.streamflixreborn.streamflix.utils.BrandAssets
        if (brand.load(logoGateway, com.streamflixreborn.streamflix.utils.BrandAssets.Kind.LOGO) { it.error(R.mipmap.ic_launcher).fitCenter() }) {
            logoGateway.visibility = View.VISIBLE
        }
        brand.loginBackground(this, isTv)?.let { kind ->
            bgGateway.visibility = View.VISIBLE
            brand.load(bgGateway, kind) { it.centerCrop() }
        }
    }

    private fun setLoadingState(isLoading: Boolean) {
        if (isLoading) {
            progressSync.visibility = View.VISIBLE
            btnSync.visibility = View.INVISIBLE
            txtStatus.visibility = View.GONE
            inputAlpha.isEnabled = false
            inputBeta.isEnabled = false
        } else {
            progressSync.visibility = View.GONE
            btnSync.visibility = View.VISIBLE
            inputAlpha.isEnabled = true
            inputBeta.isEnabled = true
        }
    }

    private fun showStatus(message: String) {
        txtStatus.text = message
        txtStatus.visibility = View.VISIBLE
    }
}