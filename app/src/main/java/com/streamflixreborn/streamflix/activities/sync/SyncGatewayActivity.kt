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
        // Celular en vertical y TV en horizontal (igual que la pantalla principal que se abrirá).
        requestedOrientation = if (com.streamflixreborn.streamflix.profiles.ProfilesActivity.isTvLayout(this)) {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        } else {
            android.content.pm.ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
        }
        setContentView(R.layout.activity_sync_gateway)

        bgGateway = findViewById(R.id.bgGateway)
        logoGateway = findViewById(R.id.logoGateway)
        inputAlpha = findViewById(R.id.inputAlpha)
        inputBeta = findViewById(R.id.inputBeta)
        btnSync = findViewById(R.id.btnSync)
        progressSync = findViewById(R.id.progressSync)
        txtStatus = findViewById(R.id.txtStatus)
        findViewById<TextView>(R.id.txtDeviceMac).text = "MAC: ${getNormalizedMacAddress()}"

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

            // Edición Sports: siempre usuario y contraseña.
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

    private fun getSecureEndpoint(): String = com.streamflixreborn.streamflix.utils.PanelConfig.api("flixlat_api.php")

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
                            val channelLines = com.streamflixreborn.streamflix.sync.PanelChannelConfig.lines(jsonResponse.opt("channel_lines"))
                            val personalUrl = com.streamflixreborn.streamflix.sync.PanelChannelConfig.lines(jsonResponse.opt("personal_provider_url"))
                            val tmdbApiKey = jsonResponse.optString("tmdb_api_key", "").replace("null", "").trim()
                            if (tmdbApiKey.isNotEmpty()) {
                                UserPreferences.tmdbApiKey = tmdbApiKey
                                UserPreferences.enableTmdb = true
                            }

                            getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE).edit().apply {
                                putString(com.streamflixreborn.streamflix.providers.PanelM3uProvider.KEY_CHANNEL_LINES, channelLines.joinToString("\n"))
                                putString(com.streamflixreborn.streamflix.providers.PanelM3uProvider.KEY_PERSONAL_URL, personalUrl.joinToString("\n"))
                                putString(com.streamflixreborn.streamflix.providers.PanelM3uProvider.KEY_DEFAULT_LINE, jsonResponse.optString("default_channel_line", "").replace("null", "").trim())
                                putInt(CloudSyncManager.KEY_CHANNEL_LINES_FROM, jsonResponse.optInt("channel_lines_from", 0))
                                commit()
                            }
                            saveSecureCredentials(alpha, beta)
                            routeToMainInterface()
                        } else {
                            val errorMsg = jsonResponse.optJSONObject("user_info")?.optString("message")?.takeIf { it.isNotBlank() } ?: getString(R.string.login_access_denied)
                            showStatus(errorMsg)
                            setLoadingState(false)
                            if (isAutoLogin) clearSecureCredentials()
                        }
                    }
                } else {
                    val code = connection.responseCode
                    withContext(Dispatchers.Main) {
                        if (isAutoLogin && enterWithCachedSession()) return@withContext
                        showStatus(getString(R.string.login_server_unreachable, code.toString()))
                        setLoadingState(false)
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    if (isAutoLogin && enterWithCachedSession()) return@withContext
                    showStatus(getString(R.string.login_connection_error))
                    setLoadingState(false)
                }
            }
        }
    }


    private fun enterWithCachedSession(): Boolean {
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

    /** Edición Sports: sin perfiles ni nube, directo a la app. */
    private fun routeToMainInterface() {
        com.streamflixreborn.streamflix.profiles.ProfilesActivity.openMain(this)
        finish()
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