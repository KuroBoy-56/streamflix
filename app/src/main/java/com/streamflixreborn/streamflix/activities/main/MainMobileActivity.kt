package com.streamflixreborn.streamflix.activities.main

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import androidx.navigation.ui.setupWithNavController
import com.bumptech.glide.Glide
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.activities.tools.BypassWebViewActivity
import com.streamflixreborn.streamflix.databinding.ActivityMainMobileBinding
import com.streamflixreborn.streamflix.fragments.player.PlayerMobileFragment
import com.streamflixreborn.streamflix.providers.Cine24hProvider
import com.streamflixreborn.streamflix.providers.FilmyOnlineCcProvider
import com.streamflixreborn.streamflix.providers.GuardaSerieProvider
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.ZaluknijProvider
import com.streamflixreborn.streamflix.ui.UpdateAppMobileDialog
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.ProviderChangeNotifier
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.providers.AnimeOnlineNinjaProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import java.util.Locale
import kotlin.coroutines.resume

class MainMobileActivity : FragmentActivity() {

    private companion object {
        const val RESOLVER_TIMEOUT_MS = 12_000L
    }

    private data class ResolverPayload(val url: String)

    private var _binding: ActivityMainMobileBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<MainViewModel>()
    private val resolverWebSocketClient by lazy { OkHttpClient() }
    private val bypassWebViewLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val wsUrl = pendingWs
        val token = pendingToken
        val cookies = result.data?.getStringExtra(BypassWebViewActivity.EXTRA_COOKIE_HEADER)?.trim()

        clearResolverState()

        if (result.resultCode != Activity.RESULT_OK || wsUrl.isNullOrBlank() || token.isNullOrBlank()) {
            return@registerForActivityResult
        }

        lifecycleScope.launch {
            sendWebSocketDone(wsUrl, token, cookies)
            showPostBypassCloseDialog()
        }
    }

    private var pendingWs: String? = null
    private var pendingToken: String? = null
    private var updateAppDialog: UpdateAppMobileDialog? = null

    private var splashOverlay: ImageView? = null
    private var expirationWebView: WebView? = null
    private var isModalClosed = false

    private val ENCRYPTED_NTF_URL = "4979456d507a6876665741734e4341715053773850796f374e794d34657a3475507a67694e32553250534a6b505459674b546f714f586c364d7a3869656a77374c516f7a50794a3749696337"

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.mobileThemeRes(UserPreferences.selectedTheme))
        super.onCreate(savedInstanceState)

        AnimeOnlineNinjaProvider.init(this)
        Cine24hProvider.init(this)
        FilmyOnlineCcProvider.init(this)
        GuardaSerieProvider.init(this)
        ZaluknijProvider.init(this)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar

        _binding = ActivityMainMobileBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemeNavigationChrome()

        // --- SPLASH DINÁMICO ---
        val prefs = getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
        val customSplash = prefs.getString("custom_splash_url", "") ?: ""
        val rootFrame = findViewById<ViewGroup>(android.R.id.content)

        if (customSplash.isNotEmpty()) {
            splashOverlay = ImageView(this).apply {
                setBackgroundColor(Color.parseColor("#0F111A"))
                scaleType = ImageView.ScaleType.FIT_CENTER
            }
            val splashParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            ViewCompat.setElevation(splashOverlay!!, 2000f)
            rootFrame.addView(splashOverlay, splashParams)
            Glide.with(this).load(customSplash).into(splashOverlay!!)

            lifecycleScope.launch {
                kotlinx.coroutines.delay(1800)
                splashOverlay?.animate()?.alpha(0f)?.setDuration(600)?.withEndAction {
                    try { (splashOverlay?.parent as? ViewGroup)?.removeView(splashOverlay) } catch(e: Exception){}
                    splashOverlay = null

                    // ⚡️ Solo se llama una vez tras el splash en el Activity maestro
                    setupExpirationWarningWebView()
                }
            }
        } else {
            setupExpirationWarningWebView()
        }

        ViewCompat.setOnApplyWindowInsetsListener(binding.mainContent) { view, windowInsets ->
            val insets = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val navHostFragment = supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
            val currentFragment = navHostFragment?.childFragmentManager?.primaryNavigationFragment

            val isPlayer = currentFragment is PlayerMobileFragment
            val isBottomNavVisible = binding.bnvMain.visibility == View.VISIBLE

            val bottomPadding = if (isPlayer || isBottomNavVisible) 0 else insets.bottom
            val topPadding = if (isPlayer) 0 else insets.top

            view.setPadding(insets.left, topPadding, insets.right, bottomPadding)
            windowInsets
        }

        updateImmersiveMode()

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as NavHostFragment
        val navController = navHost.navController

        if (BuildConfig.APP_LAYOUT == "tv" || (BuildConfig.APP_LAYOUT != "mobile" && packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))) {
            finish()
            startActivity(Intent(this, MainTvActivity::class.java))
            return
        }

        if (savedInstanceState == null) {
            UserPreferences.currentProvider?.let {
                navController.navigate(R.id.home, null, navOptions { launchSingleTop = true; popUpTo(R.id.providers) { inclusive = true } })
            }
        }

        viewModel.checkUpdate()
        binding.bnvMain.setupWithNavController(navController)
        binding.btnMainSearch.setOnClickListener {
            if (navController.currentDestination?.id != R.id.search) navController.navigate(R.id.search)
        }
        updateNavigationVisibility()
        updateBottomNavigationVisibility(navController.currentDestination?.id)

        navController.addOnDestinationChangedListener { _, destination, _ ->
            updateNavigationVisibility(destination.id)
            updateBottomNavigationVisibility(destination.id)
            binding.mainContent.post { binding.mainContent.requestApplyInsets() }
        }

        lifecycleScope.launch {
            ProviderChangeNotifier.providerChangeFlow.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { updateNavigationVisibility(navController.currentDestination?.id) }
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> showUpdateDialog(state)
                    MainViewModel.State.DownloadingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainMobileActivity, state.apk)
                        dismissUpdateDialog()
                    }
                    MainViewModel.State.InstallingUpdate -> updateAppDialog?.isLoading = true
                    is MainViewModel.State.FailedUpdate -> {
                        updateAppDialog?.isLoading = false
                        Toast.makeText(this@MainMobileActivity, state.error.message ?: "Update failed", Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Impide al usuario regresar o salir si el aviso de expiración está tapando la pantalla
                if (expirationWebView?.visibility == View.VISIBLE) return

                val handled = (getCurrentFragment() as? PlayerMobileFragment)?.onBackPressed() ?: false
                if (handled) return

                val currentDestinationId = navController.currentDestination?.id
                if (currentDestinationId == R.id.settings) { navigateToProviderHome(navController); return }
                if (UserPreferences.currentProvider != null && currentDestinationId == R.id.home) { closeTask(); return }
                if (UserPreferences.currentProvider != null && isTopLevelProviderDestination(currentDestinationId)) { navigateToProviderHome(navController); return }

                if (!navController.navigateUp()) finish()
            }
        })

        if (savedInstanceState == null) handleIntent(intent)
    }

    // =========================================================================
    // LÓGICA WEBVIEW EXCLUSIVA DE EXPIRACIÓN (UNA SOLA VEZ AL ABRIR)
    // =========================================================================
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

    private fun getNormalizedMacAddress(): String {
        val rawId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "0000000000000000"
        val cleanId = rawId.replace("[^a-fA-F0-9]".toRegex(), "").padEnd(16, '0')
        return cleanId.substring(0, 16).chunked(2).joinToString(":").uppercase(Locale.getDefault())
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupExpirationWarningWebView() {
        val decryptedUrl = decryptData(ENCRYPTED_NTF_URL)
        if (decryptedUrl.isEmpty()) return

        isModalClosed = false

        expirationWebView = WebView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
            // ⚡️ Nace 100% transparente para que no cause pantalla negra mientras PHP decide qué hacer
            setBackgroundColor(Color.TRANSPARENT)

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE // Fuera caché
            settings.userAgentString = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/114.0.0.0 Mobile Safari/537.36"

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }

            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
            webChromeClient = WebChromeClient()
        }

        ViewCompat.setElevation(expirationWebView!!, 4000f)

        expirationWebView!!.addJavascriptInterface(object : Any() {
            @JavascriptInterface
            fun closeModal() {
                isModalClosed = true
                runOnUiThread {
                    try {
                        expirationWebView?.visibility = View.GONE
                        (expirationWebView?.parent as? ViewGroup)?.removeView(expirationWebView)
                        expirationWebView?.destroy()
                        expirationWebView = null
                    } catch (e: Exception) {}
                }
            }
        }, "Android")

        expirationWebView!!.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!isModalClosed) {
                    // Si PHP no mandó la orden de cerrar, es porque toca oscurecer y mostrar la alerta
                    expirationWebView?.setBackgroundColor(Color.parseColor("#CC000000"))
                    expirationWebView?.visibility = View.VISIBLE
                    expirationWebView?.requestFocus()
                }
            }
            override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                handler?.proceed()
            }
        }

        val rootContent = findViewById<ViewGroup>(android.R.id.content)
        rootContent.addView(expirationWebView)

        val mac = getNormalizedMacAddress()
        val prefs = getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
        val username = prefs.getString("alpha_token", "") ?: ""

        val separator = if (decryptedUrl.contains("?")) "&" else "?"
        val cacheBuster = System.currentTimeMillis() // Rompedor de caché de Cloudflare definitivo
        val targetUrl = "$decryptedUrl${separator}mac=$mac&user=$username&cb=$cacheBuster"

        expirationWebView!!.clearCache(true)
        expirationWebView!!.loadUrl(targetUrl)
    }

    override fun onDestroy() {
        try {
            expirationWebView?.let {
                (it.parent as? ViewGroup)?.removeView(it)
                it.destroy()
            }
        } catch (e: Exception) {}
        _binding = null
        super.onDestroy()
    }

    // =========================================================================

    override fun onResume() { super.onResume(); viewModel.checkUpdate() }

    private fun clearResolverState() { pendingWs = null; pendingToken = null }

    private fun showUpdateDialog(state: MainViewModel.State.SuccessCheckingUpdate) {
        if (isFinishing || isDestroyed) return
        dismissUpdateDialog()
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Actualización Disponible")
            .setMessage("Nueva versión ${state.newReleases.firstOrNull()?.tagName}\n\n${state.newReleases.firstOrNull()?.body}")
            .setCancelable(false)
            .setPositiveButton("Actualizar") { _, _ -> viewModel.downloadUpdate(this@MainMobileActivity, state.asset) }
            .setNegativeButton("Ignorar", null).show()
    }

    private fun dismissUpdateDialog() { updateAppDialog?.takeIf { it.isShowing }?.dismiss(); updateAppDialog = null }

    private fun updateBottomNavigationVisibility(destinationId: Int?) {
        val showBottomNav = UserPreferences.currentProvider != null && isTopLevelProviderDestination(destinationId)
        binding.bnvMain.visibility = if (showBottomNav) View.VISIBLE else View.GONE
        binding.btnMainSearch.visibility = if (UserPreferences.currentProvider != null && isTopLevelProviderDestination(destinationId) && destinationId != R.id.search) View.VISIBLE else View.GONE
    }

    private fun updateNavigationVisibility(currentDestinationId: Int? = null) {
        val provider = UserPreferences.currentProvider ?: return
        val supportsMovies = Provider.supportsMovies(provider)
        val supportsTvShows = Provider.supportsTvShows(provider)

        binding.bnvMain.menu.findItem(R.id.movies)?.isVisible = supportsMovies
        binding.bnvMain.menu.findItem(R.id.tv_shows)?.apply {
            isVisible = supportsTvShows
            title = if (provider is IptvProvider) getString(R.string.main_menu_all_channels) else getString(R.string.main_menu_tv_shows)
        }

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_main_fragment) as? NavHostFragment
        val navController = navHost?.navController ?: return
        when {
            currentDestinationId == R.id.movies && !supportsMovies -> navController.navigate(R.id.tv_shows)
            currentDestinationId == R.id.tv_shows && !supportsTvShows -> navController.navigate(R.id.home)
        }
    }

    private fun isTopLevelProviderDestination(destinationId: Int?): Boolean { return destinationId in setOf(R.id.search, R.id.home, R.id.movies, R.id.tv_shows, R.id.favorites, R.id.settings) }

    private fun navigateToProviderHome(navController: androidx.navigation.NavController) {
        if (!navController.popBackStack(R.id.home, false)) { navController.navigate(R.id.home, null, navOptions { launchSingleTop = true; popUpTo(R.id.providers) { inclusive = true } }) }
    }

    private fun closeTask() { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) finishAndRemoveTask() else finishAffinity() }

    private suspend fun requestResolverPayload(wsUrl: String, token: String): ResolverPayload? =
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(RESOLVER_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val request = Request.Builder().url(wsUrl).build()
                    val socket = resolverWebSocketClient.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { webSocket.send("resolve:$token") }
                        override fun onMessage(webSocket: WebSocket, text: String) {
                            when {
                                text.startsWith("payload:") -> {
                                    val payload = text.substringAfter("payload:").trim()
                                    val parsed = runCatching { val json = JSONObject(payload); ResolverPayload(url = json.optString("url")) }.getOrNull()
                                    if (continuation.isActive) continuation.resume(parsed?.takeUnless { it.url.isBlank() || it.url.equals("null", ignoreCase = true) })
                                    webSocket.close(1000, null)
                                }
                                text.startsWith("url:") -> {
                                    val url = text.substringAfter("url:").trim()
                                    if (continuation.isActive) continuation.resume(url.takeUnless { it.isEmpty() || it.equals("null", ignoreCase = true) }?.let { ResolverPayload(url = it) })
                                    webSocket.close(1000, null)
                                }
                                text.startsWith("error:") -> { if (continuation.isActive) continuation.resume(null); webSocket.close(1000, null) }
                            }
                        }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (continuation.isActive) continuation.resume(null) }
                    })
                    continuation.invokeOnCancellation { socket.cancel() }
                }
            }
        }

    private suspend fun sendWebSocketDone(wsUrl: String, token: String, cookies: String?) {
        withContext(Dispatchers.IO) {
            withTimeoutOrNull(RESOLVER_TIMEOUT_MS) {
                suspendCancellableCoroutine { continuation ->
                    val request = Request.Builder().url(wsUrl).build()
                    val socket = resolverWebSocketClient.newWebSocket(request, object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) {
                            val encodedCookies = cookies?.takeIf { it.isNotBlank() }?.let { java.util.Base64.getEncoder().encodeToString(it.toByteArray(Charsets.UTF_8)) }
                            val message = if (encodedCookies.isNullOrBlank()) "done:$token" else "done:$token:$encodedCookies"
                            webSocket.send(message)
                        }
                        override fun onMessage(webSocket: WebSocket, text: String) { if (text == "ack:$token" && continuation.isActive) { continuation.resume(Unit); webSocket.close(1000, null) } }
                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { if (continuation.isActive) continuation.resume(Unit) }
                    })
                    continuation.invokeOnCancellation { socket.cancel() }
                }
            }
        }
    }

    private fun handleIntent(intent: Intent): Boolean {
        val data = intent.data ?: return false
        if (data.scheme == "streamflix" && data.host == "resolve") {
            val ws = data.getQueryParameter("ws") ?: return false
            val token = data.getQueryParameter("token") ?: return false
            resolve(ws, token)
            return true
        }
        return false
    }

    private fun resolve(ws: String, token: String) {
        pendingWs = ws; pendingToken = token
        lifecycleScope.launch {
            val payload = requestResolverPayload(ws, token)
            if (payload == null) { showResolverConnectionErrorDialog(ws, token); return@launch }
            bypassWebViewLauncher.launch(Intent(this@MainMobileActivity, BypassWebViewActivity::class.java).putExtra(BypassWebViewActivity.EXTRA_URL, payload.url))
        }
    }

    private fun showResolverConnectionErrorDialog(ws: String, token: String) {
        if (isFinishing || isDestroyed) return
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.app_name).setMessage("Unable to reach the TV bypass websocket. Retry?")
            .setPositiveButton("Retry") { _, _ -> resolve(ws, token) }.setNegativeButton(android.R.string.cancel) { _, _ -> clearResolverState() }
            .setOnCancelListener { clearResolverState() }.show()
    }

    private fun showPostBypassCloseDialog() {
        if (isFinishing || isDestroyed) return
        androidx.appcompat.app.AlertDialog.Builder(this).setTitle(R.string.app_name).setMessage("Bypass completed. Do you want to close the app?")
            .setPositiveButton("Close app") { _, _ -> closeTask() }.setNegativeButton("Keep open", null).setOnCancelListener(null).show()
    }

    override fun onUserLeaveHint() { super.onUserLeaveHint(); (getCurrentFragment() as? PlayerMobileFragment)?.onUserLeaveHint() }

    fun updateImmersiveMode() {
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        if (UserPreferences.immersiveMode) controller.hide(WindowInsetsCompat.Type.systemBars()) else controller.show(WindowInsetsCompat.Type.systemBars())
    }

    private fun applyThemeNavigationChrome() {
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        val navColors = ColorStateList(arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()), intArrayOf(palette.mobileNavActive, palette.mobileNavInactive))
        binding.bnvMain.setBackgroundColor(palette.mobileNavBackground)
        binding.bnvMain.itemIconTintList = navColors
        binding.bnvMain.itemTextColor = navColors
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar
        WindowInsetsControllerCompat(window, window.decorView).apply { isAppearanceLightStatusBars = false; isAppearanceLightNavigationBars = false }
    }
}