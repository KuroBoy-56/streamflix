package com.streamflixreborn.streamflix.activities.sync

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.main.MainMobileActivity
import com.streamflixreborn.streamflix.activities.main.MainTvActivity
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.sync.CloudSyncManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

class SyncGatewayActivity : AppCompatActivity() {

    private lateinit var logoGateway: ImageView
    private lateinit var inputAlpha: EditText
    private lateinit var inputBeta: EditText
    private lateinit var btnSync: Button
    private lateinit var progressSync: ProgressBar
    private lateinit var txtStatus: TextView

    private var isPasswordVisible = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync_gateway)

        logoGateway = findViewById(R.id.logoGateway)
        inputAlpha = findViewById(R.id.inputAlpha)
        inputBeta = findViewById(R.id.inputBeta)
        btnSync = findViewById(R.id.btnSync)
        progressSync = findViewById(R.id.progressSync)
        txtStatus = findViewById(R.id.txtStatus)

        setupEyeToggle()

        // --- MAGIA DE LA MARCA BLANCA ONLINE (LOGIN) ---
        val savedLogo = UserPreferences.customLogoUrl
        if (savedLogo.isNotEmpty()) {
            logoGateway.visibility = View.VISIBLE
            Glide.with(this)
                .load(savedLogo)
                .error(R.mipmap.ic_launcher) // Si el link falla, pone el icono normal
                .fitCenter()
                .into(logoGateway)
        }
        // ------------------------------------------------

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
                showStatus("Fields cannot be empty")
                return@setOnClickListener
            }

            executeGatewayHandshake(alphaVal, betaVal, isAutoLogin = false)
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
            if (event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_DPAD_RIGHT) {
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

    private fun getSecureEndpoint(): String {
        val encryptedHex = "6148523063484D364C79396E59584A6C646E6C75634746755A57787A4C6D7868644731776543356A6232307661475276596D3934646A4976595842704C325A736158687359585266595842704C6E426F63413D3D"
        return try {
            val base64Bytes = encryptedHex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val base64Str = String(base64Bytes, Charsets.UTF_8)
            String(Base64.decode(base64Str, Base64.NO_WRAP), Charsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun getNormalizedMacAddress(): String {
        val rawId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "0000000000000000"
        val cleanId = rawId.replace("[^a-fA-F0-9]".toRegex(), "").padEnd(16, '0')
        return cleanId.substring(0, 16).chunked(2).joinToString(":").uppercase(Locale.getDefault())
    }

    // --- CAMBIO DINÁMICO DE ICONO Y NOMBRE (LAUNCHER) ---
    private fun updateLauncherIcon(appId: Int) {
        try {
            val pm = packageManager
            val baseAlias = "$packageName.activities.sync.SyncGatewayActivity"
            val master = ComponentName(this, "$baseAlias.Master")
            val admin1 = ComponentName(this, "$baseAlias.Admin12345")
            val admin2 = ComponentName(this, "$baseAlias.Admin98765")

            val target = when(appId) {
                12345 -> admin1
                98765 -> admin2
                else -> master
            }

            if (pm.getComponentEnabledSetting(target) != PackageManager.COMPONENT_ENABLED_STATE_ENABLED) {
                pm.setComponentEnabledSetting(master, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(admin1, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
                pm.setComponentEnabledSetting(admin2, PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)

                pm.setComponentEnabledSetting(target, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    // ----------------------------------------------------

    private fun executeGatewayHandshake(alpha: String, beta: String, isAutoLogin: Boolean) {
        setLoadingState(true)

        val hardwareMac = getNormalizedMacAddress()
        val endpointUrl = getSecureEndpoint()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val params = "username=${URLEncoder.encode(alpha, "UTF-8")}&password=${URLEncoder.encode(beta, "UTF-8")}&mac=${URLEncoder.encode(hardwareMac, "UTF-8")}"
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

                            // --- ATRAPAR LOGO ONLINE, APP_ID Y TMDB_API_KEY ---
                            val customLogoUrl = jsonResponse.optString("custom_logo", "")
                            val tmdbApiKey = jsonResponse.optString("tmdb_api_key", "")
                            val appId = jsonResponse.optInt("app_id", 0)

                            UserPreferences.customLogoUrl = customLogoUrl
                            UserPreferences.savedAppId = appId

                            if (tmdbApiKey.isNotEmpty()) {
                                UserPreferences.tmdbApiKey = tmdbApiKey
                                UserPreferences.enableTmdb = true // Forzamos activar el enriquecimiento
                            } else {
                                UserPreferences.enableTmdb = false // Si el admin no puso API, apagamos TMDb
                            }

                            // Activamos el Alias correspondiente para cambiar icono
                            updateLauncherIcon(appId)
                            // ---------------------------------------------------

                            saveSecureCredentials(alpha, beta)

                            if (namesList.isNotEmpty()) {
                                val currentProv = UserPreferences.currentProvider
                                if (currentProv == null || !namesList.contains(currentProv.name)) {
                                    val providerMatch = Provider.providers.keys.find { it.name.equals(namesList[0], ignoreCase = true) }
                                    if (providerMatch != null) {
                                        UserPreferences.currentProvider = providerMatch
                                    }
                                }
                            }

                            // ⚡️ ¡AQUÍ ESTÁ LA MAGIA! FORZAMOS DESCARGA ANTES DE ENTRAR
                            // Como ya guardamos las credenciales, initialize() funcionará y poblará la BD local.
                            CoroutineScope(Dispatchers.IO).launch {
                                CloudSyncManager.initialize(this@SyncGatewayActivity)

                                // Esperamos a que la descarga y actualización de base de datos se complete (aprox 1.5 a 2 segundos)
                                kotlinx.coroutines.delay(1800)

                                withContext(Dispatchers.Main) {
                                    routeToMainInterface()
                                }
                            }

                        } else {
                            val errorMsg = jsonResponse.optJSONObject("user_info")?.optString("status", "Auth failed") ?: "Auth error"
                            showStatus(errorMsg)
                            setLoadingState(false)
                            if (isAutoLogin) clearSecureCredentials()
                        }
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        showStatus("Gateway Unreachable: ${connection.responseCode}")
                        setLoadingState(false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showStatus("Connection Error: ${e.message}")
                    setLoadingState(false)
                }
            }
        }
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

    private fun routeToMainInterface() {
        val isTv = packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
        val intent = if (isTv) {
            Intent(this, MainTvActivity::class.java)
        } else {
            Intent(this, MainMobileActivity::class.java)
        }
        startActivity(intent)
        finish()
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