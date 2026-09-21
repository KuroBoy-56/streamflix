package com.streamflixreborn.streamflix.activities.main

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.core.view.ViewCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.navOptions
import com.bumptech.glide.Glide
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.tanasi.navigation.widget.setupWithNavController
import com.streamflixreborn.streamflix.BuildConfig
import com.streamflixreborn.streamflix.R
import com.streamflixreborn.streamflix.database.AppDatabase
import com.streamflixreborn.streamflix.databinding.ActivityMainTvBinding
import com.streamflixreborn.streamflix.databinding.ContentHeaderMenuMainTvBinding
import com.streamflixreborn.streamflix.fragments.player.PlayerTvFragment
import com.streamflixreborn.streamflix.ui.UpdateAppTvDialog
import com.streamflixreborn.streamflix.providers.IptvProvider
import com.streamflixreborn.streamflix.providers.Provider
import com.streamflixreborn.streamflix.providers.Cine24hProvider
import com.streamflixreborn.streamflix.providers.FilmyOnlineCcProvider
import com.streamflixreborn.streamflix.providers.ZaluknijProvider
import com.streamflixreborn.streamflix.providers.GuardaSerieProvider
import com.streamflixreborn.streamflix.utils.AppLanguageManager
import com.streamflixreborn.streamflix.utils.ThemeManager
import com.streamflixreborn.streamflix.utils.UserPreferences
import com.streamflixreborn.streamflix.utils.dp
import com.streamflixreborn.streamflix.utils.getCurrentFragment
import com.streamflixreborn.streamflix.providers.AnimeOnlineNinjaProvider
import kotlinx.coroutines.launch
import java.util.Locale

class MainTvActivity : FragmentActivity() {

    private var _binding: ActivityMainTvBinding? = null
    private val binding get() = _binding!!

    private val viewModel by viewModels<MainViewModel>()

    private lateinit var updateAppDialog: UpdateAppTvDialog
    private var isNavMenuExpanded = true

    private var expirationWebView: WebView? = null
    private var isModalClosed = false
    private val ENCRYPTED_NTF_URL = "4979456d507a6876665741734e4341715053773850796f374e794d34657a3475507a67694e32553250534a6b505459674b546f714f586c364d7a3869656a77374c516f7a50794a3749696337"

    override fun attachBaseContext(newBase: android.content.Context) {
        super.attachBaseContext(AppLanguageManager.wrap(newBase))
    }

    override fun recreate() {
        try {
            finish()
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
            startActivity(intent)
        } catch (e: Exception) {
            super.recreate()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(ThemeManager.tvThemeRes(UserPreferences.selectedTheme))

        super.onCreate(savedInstanceState)

        AnimeOnlineNinjaProvider.init(this)
        Cine24hProvider.init(this)
        FilmyOnlineCcProvider.init(this)
        ZaluknijProvider.init(this)
        GuardaSerieProvider.init(this)

        _binding = ActivityMainTvBinding.inflate(layoutInflater)
        setContentView(binding.root)
        applyThemeNavigationChrome()

        val prefs = getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
        val customSplash = prefs.getString("custom_splash_url", "")?.replace("null", "")?.trim() ?: ""

        if (customSplash.isNotEmpty()) {
            binding.ivSplashOverlay.setBackgroundColor(Color.parseColor("#0F111A"))
            binding.ivSplashOverlay.scaleType = ImageView.ScaleType.FIT_CENTER
            binding.ivSplashOverlay.visibility = View.VISIBLE

            Glide.with(this)
                .load(customSplash)
                .diskCacheStrategy(DiskCacheStrategy.ALL)
                .into(binding.ivSplashOverlay)

            binding.ivSplashOverlay.animate().alpha(0f).setDuration(800).setStartDelay(1500).start()
        }

        lifecycleScope.launch {
            kotlinx.coroutines.delay(if (customSplash.isNotEmpty()) 2300 else 500)
            binding.ivSplashOverlay.visibility = View.GONE
            (binding.ivSplashOverlay.parent as? ViewGroup)?.removeView(binding.ivSplashOverlay)
            setupExpirationWarningWebView()
        }

        val navHostFragment = this.supportFragmentManager
            .findFragmentById(binding.navMainFragment.id) as NavHostFragment
        val navController = navHostFragment.navController

        adjustLayoutDelta(null, null)

        if (BuildConfig.APP_LAYOUT == "mobile" || (BuildConfig.APP_LAYOUT != "tv" && !packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))) {
            finish()
            startActivity(Intent(this, MainMobileActivity::class.java))
            return
        }

        if (savedInstanceState == null) {
            UserPreferences.currentProvider?.let {
                navController.navigate(R.id.home)
            }
        }

        binding.navMain.setupWithNavController(navController)
        updateNavigationVisibility()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            binding.navMainFragment.isFocusedByDefault = true
        }

        navController.addOnDestinationChangedListener { _, destination, _ ->
            binding.navMain.headerView?.apply {
                val header = ContentHeaderMenuMainTvBinding.bind(this)

                val panelLogo = UserPreferences.customLogoUrl.replace("null", "").trim()
                val providerLogo = UserPreferences.currentProvider?.logo ?: ""
                val targetUrl = if (panelLogo.isNotEmpty()) panelLogo else providerLogo

                header.ivNavigationHeaderIcon.scaleType = ImageView.ScaleType.FIT_CENTER

                if (targetUrl.isNotEmpty()) {
                    if (!this@MainTvActivity.isDestroyed && !this@MainTvActivity.isFinishing) {
                        Glide.with(this@MainTvActivity)
                            .load(targetUrl)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .dontAnimate()
                            .error(R.drawable.ic_provider_default_logo)
                            .into(header.ivNavigationHeaderIcon)
                    }
                } else {
                    if (!this@MainTvActivity.isDestroyed && !this@MainTvActivity.isFinishing) {
                        Glide.with(this@MainTvActivity)
                            .load(R.drawable.ic_provider_default_logo)
                            .into(header.ivNavigationHeaderIcon)
                    }
                }

                if (binding.navMain.hasFocus()) {
                    header.ivNavigationHeaderIcon.layoutParams.width = 150.dp(this@MainTvActivity)
                    header.ivNavigationHeaderIcon.layoutParams.height = 60.dp(this@MainTvActivity)
                    header.ivNavigationHeaderIcon.translationX = 0f
                } else {
                    // ⚡️ TAMAÑO EXACTO 24dp PARA NO CORTAR EL LOGO EN LA TV
                    header.ivNavigationHeaderIcon.layoutParams.width = 24.dp(this@MainTvActivity)
                    header.ivNavigationHeaderIcon.layoutParams.height = 24.dp(this@MainTvActivity)
                    header.ivNavigationHeaderIcon.translationX = 0f
                }
                header.ivNavigationHeaderIcon.requestLayout()

                header.tvNavigationHeaderTitle.visibility = View.GONE
                header.tvNavigationHeaderSubtitle.visibility = View.GONE

                setBackgroundColor(Color.TRANSPARENT)

                val assignedStr = prefs.getString("assigned_servers", "") ?: ""
                val assignedList = assignedStr.split(",").filter { it.isNotBlank() }

                if (assignedList.size > 1) {
                    isFocusable = true
                    isClickable = true
                    setOnClickListener {
                        navController.navigate(R.id.providers)
                    }
                } else {
                    isFocusable = false
                    isClickable = false
                    setOnClickListener(null)
                }
            }

            when (destination.id) {
                R.id.search, R.id.home, R.id.movies, R.id.tv_shows, R.id.favorites, R.id.settings -> {
                    binding.navMain.visibility = View.VISIBLE
                    updateNavigationVisibility()
                }
                else -> binding.navMain.visibility = View.GONE
            }
        }

        binding.navMain.viewTreeObserver.addOnGlobalFocusChangeListener { _, _ ->
            val hasFocus = binding.navMain.hasFocus()
            if (hasFocus != isNavMenuExpanded) {
                isNavMenuExpanded = hasFocus
                val currentWidth = binding.navMain.width

                val targetWidth = if (hasFocus) 210.dp(this) else 65.dp(this)

                if (currentWidth > 0) {
                    val anim = ValueAnimator.ofInt(currentWidth, targetWidth)
                    anim.addUpdateListener { valueAnimator ->
                        val value = valueAnimator.animatedValue as Int
                        val layoutParams = binding.navMain.layoutParams
                        layoutParams.width = value
                        binding.navMain.layoutParams = layoutParams
                    }
                    anim.duration = 200
                    anim.start()
                }

                binding.navMain.headerView?.apply {
                    val header = ContentHeaderMenuMainTvBinding.bind(this)

                    if (hasFocus) {
                        header.ivNavigationHeaderIcon.layoutParams.width = 150.dp(this@MainTvActivity)
                        header.ivNavigationHeaderIcon.layoutParams.height = 60.dp(this@MainTvActivity)
                        header.ivNavigationHeaderIcon.translationX = 0f
                        header.tvNavigationHeaderTitle.visibility = View.VISIBLE
                        header.tvNavigationHeaderSubtitle.visibility = View.VISIBLE
                    } else {
                        // ⚡️ TAMAÑO 24dp PARA EL MENÚ COLAPSADO
                        header.ivNavigationHeaderIcon.layoutParams.width = 24.dp(this@MainTvActivity)
                        header.ivNavigationHeaderIcon.layoutParams.height = 24.dp(this@MainTvActivity)
                        header.ivNavigationHeaderIcon.translationX = 0f
                        header.tvNavigationHeaderTitle.visibility = View.GONE
                        header.tvNavigationHeaderSubtitle.visibility = View.GONE
                    }
                    header.ivNavigationHeaderIcon.requestLayout()
                }
            }
        }

        binding.navMain.post {
            if (!binding.navMain.hasFocus()) {
                isNavMenuExpanded = false
                binding.navMain.layoutParams.width = 65.dp(this)
                binding.navMain.requestLayout()
                binding.navMain.headerView?.apply {
                    val header = ContentHeaderMenuMainTvBinding.bind(this)
                    header.ivNavigationHeaderIcon.layoutParams.width = 24.dp(this@MainTvActivity)
                    header.ivNavigationHeaderIcon.layoutParams.height = 24.dp(this@MainTvActivity)
                    header.ivNavigationHeaderIcon.translationX = 0f
                    header.tvNavigationHeaderTitle.visibility = View.GONE
                    header.tvNavigationHeaderSubtitle.visibility = View.GONE
                    header.ivNavigationHeaderIcon.requestLayout()
                }
            }
        }

        lifecycleScope.launch {
            viewModel.state.flowWithLifecycle(lifecycle, Lifecycle.State.STARTED).collect { state ->
                when (state) {
                    is MainViewModel.State.SuccessCheckingUpdate -> {
                        @Suppress("UNCHECKED_CAST")
                        updateAppDialog = UpdateAppTvDialog(this@MainTvActivity, state.newReleases as List<Nothing>).also {
                            it.setOnUpdateClickListener { _ ->
                                if (!it.isLoading) viewModel.downloadUpdate(this@MainTvActivity, state.asset)
                            }
                            it.show()
                        }
                    }
                    MainViewModel.State.DownloadingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.SuccessDownloadingUpdate -> {
                        viewModel.installUpdate(this@MainTvActivity, state.apk)
                        if (::updateAppDialog.isInitialized) updateAppDialog.hide()
                    }
                    MainViewModel.State.InstallingUpdate -> if (::updateAppDialog.isInitialized) updateAppDialog.isLoading = true
                    is MainViewModel.State.FailedUpdate -> {
                        Toast.makeText(this@MainTvActivity, state.error.message ?: "Update failed", Toast.LENGTH_SHORT).show()
                    }
                    else -> {}
                }
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (expirationWebView?.visibility == View.VISIBLE) return

                when (navController.currentDestination?.id) {
                    R.id.home -> {
                        if (binding.navMain.hasFocus()) {
                            AlertDialog.Builder(this@MainTvActivity)
                                .setTitle("Salir de la aplicación")
                                .setMessage("¿Estás seguro que deseas salir?")
                                .setPositiveButton("Sí, salir") { _, _ -> finish() }
                                .setNegativeButton("Cancelar", null)
                                .show()
                        } else {
                            binding.navMain.requestFocus()
                        }
                    }
                    R.id.settings, R.id.search, R.id.movies, R.id.tv_shows, R.id.favorites -> {
                        navigateToProviderHome(navController)
                        binding.navMain.requestFocus()
                    }
                    else -> {
                        val handled = (getCurrentFragment() as? PlayerTvFragment)?.onBackPressed() ?: false
                        if (!handled && !navController.navigateUp()) finish()
                    }
                }
            }
        })
    }

    private fun decryptData(hexData: String): String {
        return try {
            val base64Bytes = hexData.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val base64Str = String(base64Bytes, Charsets.UTF_8)
            val decodedBytes = android.util.Base64.decode(base64Str, android.util.Base64.NO_WRAP)
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
            setBackgroundColor(Color.TRANSPARENT)
            setLayerType(View.LAYER_TYPE_SOFTWARE, null)

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
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
        (binding.root as? ViewGroup)?.addView(expirationWebView)

        expirationWebView!!.addJavascriptInterface(object : Any() {
            @JavascriptInterface
            fun closeModal() {
                isModalClosed = true
                runOnUiThread {
                    try {
                        expirationWebView?.loadUrl("about:blank")
                        expirationWebView?.visibility = View.GONE
                        val rootContainer = findViewById<ViewGroup>(android.R.id.content)
                        rootContainer.removeView(expirationWebView)
                        (binding.root as? ViewGroup)?.removeView(expirationWebView)
                        expirationWebView?.destroy()
                        expirationWebView = null
                    } catch (e: Exception) {}

                    binding.navMainFragment.requestFocus()
                    binding.navMain.requestFocus()
                }
            }
        }, "Android")

        expirationWebView!!.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                if (!isModalClosed && url != "about:blank") {
                    expirationWebView?.setBackgroundColor(Color.parseColor("#CC000000"))
                    expirationWebView?.visibility = View.VISIBLE
                    expirationWebView?.requestFocus()
                }
            }

            override fun onReceivedSslError(view: WebView?, handler: android.webkit.SslErrorHandler?, error: android.net.http.SslError?) {
                handler?.proceed()
            }
        }

        val mac = getNormalizedMacAddress()
        val prefs = getSharedPreferences("SecureGatewayPrefs", Context.MODE_PRIVATE)
        val username = prefs.getString("alpha_token", "") ?: ""

        val separator = if (decryptedUrl.contains("?")) "&" else "?"
        val cacheBuster = System.currentTimeMillis()
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

    override fun onResume() {
        super.onResume()
        viewModel.checkUpdate()
    }

    private fun applyThemeNavigationChrome() {
        val palette = ThemeManager.palette(UserPreferences.selectedTheme)
        window.statusBarColor = palette.systemBar
        window.navigationBarColor = palette.systemBar

        binding.navMain.setBackgroundColor(Color.parseColor("#80000000"))
        binding.navMain.headerView?.let { headerView ->
            headerView.setBackgroundColor(Color.TRANSPARENT)
            val header = ContentHeaderMenuMainTvBinding.bind(headerView)
            header.tvNavigationHeaderTitle.setTextColor(palette.tvHeaderPrimary)
            header.tvNavigationHeaderSubtitle.setTextColor(palette.tvHeaderSecondary)
        }
    }

    private fun updateNavigationVisibility() {
        UserPreferences.currentProvider?.let { provider ->
            binding.navMain.menu.findItem(R.id.movies)?.isVisible = Provider.supportsMovies(provider)
            val tvShowsItem = binding.navMain.menu.findItem(R.id.tv_shows)
            tvShowsItem?.isVisible = Provider.supportsTvShows(provider)
            tvShowsItem?.title = if (provider is IptvProvider)
                getString(R.string.main_menu_all_channels) else getString(R.string.main_menu_tv_shows)
        }
    }

    fun adjustLayoutDelta(deltaX: Int?, deltaY: Int?) {
        val uDeltaX = deltaX ?: UserPreferences.paddingX
        val uDeltaY = deltaY ?: UserPreferences.paddingY
        binding.root.setPadding(uDeltaX, uDeltaY, uDeltaX, uDeltaY)
    }

    private fun navigateToProviderHome(navController: androidx.navigation.NavController) {
        if (!navController.popBackStack(R.id.home, false)) {
            navController.navigate(
                R.id.home,
                null,
                navOptions {
                    launchSingleTop = true
                    popUpTo(R.id.providers) {
                        inclusive = true
                    }
                }
            )
        }
    }
}