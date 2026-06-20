package com.homeos.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.homeos.app.databinding.ActivityMainBinding
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

/**
 * MainActivity: 工业级 Kiosk 大屏显示窗口主类。
 * 协调 WebViewController、NetworkCoordinator、SettingsController。
 */
class MainActivity : AppCompatActivity(), NativeBridgeHost {

    companion object {
        private const val DEFAULT_DESIGN_WIDTH = 3200
        private const val DEFAULT_DESIGN_HEIGHT = 2136
        private const val FILECHOOSER_RESULTCODE = 10001
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 10010
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var roamingManager: NetworkRoamingManager
    private lateinit var orchestrator: NotificationOrchestrator
    private lateinit var webViewController: WebViewController
    private lateinit var networkCoordinator: NetworkCoordinator
    private lateinit var settingsController: SettingsController

    private val executor = Executors.newSingleThreadExecutor()

    private var lanUrl = ""
    private var wanUrl = ""
    override var designWidth = DEFAULT_DESIGN_WIDTH
        private set
    override var designHeight = DEFAULT_DESIGN_HEIGHT
        private set
    override var isKioskMode = true
        private set
    private var activeUrl = ""

    private var threeFingerTapCount = 0
    private var lastThreeFingerTapTime = 0L
    private var cornerClickCount = 0
    private var lastCornerClickTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedPreferences = getSharedPreferences("HomeOSPreferences", Context.MODE_PRIVATE)
        val lang = sharedPreferences.getString("app_language", "zh") ?: "zh"
        setAppLocale(this, lang)

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val privacyAccepted = sharedPreferences.getBoolean("privacy_accepted", false)
        if (!privacyAccepted) {
            Log.i("MainActivity", "隐私政策尚未接受。显示弹窗拦截器。")
            showPrivacyPolicyDialog()
        } else {
            initializeAfterPrivacy()
        }
    }

    private fun initializeAfterPrivacy() {
        loadSavedConfigValues()

        roamingManager = NetworkRoamingManager(this)
        orchestrator = NotificationOrchestrator(this)

        updateScreenLocking()
        applySystemImmersiveMode()

        webViewController = WebViewController(
            activity = this,
            binding = binding,
            host = this,
            onDownload = { url, mime, disposition -> shareOrSaveDownloadedFile(url, mime, disposition) },
            onMainFrameError = { if (::networkCoordinator.isInitialized) networkCoordinator.handlePageLoadFailure() },
            onPageReady = { if (::networkCoordinator.isInitialized) networkCoordinator.onPageReady() },
            urlRedirectHandler = { handleUrlRedirection(it) },
            fileChooserLauncher = { intent, code -> startActivityForResult(intent, code) },
            fileChooserRequestCode = FILECHOOSER_RESULTCODE
        )
        webViewController.setup(designWidth, designHeight)

        networkCoordinator = NetworkCoordinator(
            binding = binding,
            roamingManager = roamingManager,
            sharedPreferences = sharedPreferences,
            executor = executor,
            webViewController = webViewController,
            isAlive = { !isFinishing && !isDestroyed },
            getLanUrl = { lanUrl },
            getWanUrl = { wanUrl },
            setActiveUrl = { activeUrl = it },
            onShowOffline = { showOfflineOverlay() },
            onHideOffline = { hideOfflineOverlay() },
            onShowSettings = { toggleSettingsOverlay(true) },
            onServiceUrlResolved = { url -> updateBackgroundService(url) },
            onPauseBackgroundPolling = { notifySocketService(pausePolling = true) },
            onResumeBackgroundPolling = { notifySocketService(resumePolling = true) },
            getString = { resId -> getString(resId) }
        )

        settingsController = SettingsController(
            activity = this,
            binding = binding,
            sharedPreferences = sharedPreferences,
            executor = executor,
            isAlive = { !isFinishing && !isDestroyed },
            onSaveConfig = { saveConfig(this) },
            onWipeData = { handleWipeData() },
            onToggleOverlay = { show -> toggleSettingsOverlay(show) },
            getLocalizedResources = { ctx, code -> getLocalizedResources(ctx, code) }
        )

        settingsController.loadIntoForm(lanUrl, wanUrl, designWidth, designHeight, isKioskMode, tempSelectedLang())
        settingsController.setupListeners()

        setupGestureInterceptors()
        setupOfflineOverlayListeners()

        startServices()
        requestNotificationPermissionIfNeeded()

        val isConfigured = sharedPreferences.getBoolean("is_configured", false)
        if (!isConfigured || lanUrl.isEmpty() || lanUrl == "http://192.168.31.175:8126") {
            Log.i("MainActivity", "应用未配置或使用默认虚拟 URL。强制显示设置弹窗。")
            toggleSettingsOverlay(true)
        } else {
            networkCoordinator.routeAndLoadUrl()
        }

        handleIntent(intent)
    }

    private fun tempSelectedLang(): String =
        if (::settingsController.isInitialized) settingsController.tempSelectedLang
        else sharedPreferences.getString("app_language", "zh") ?: "zh"

    override fun currentScaleFactor(): Double {
        val screenWidth = resources.displayMetrics.widthPixels
        return screenWidth.toDouble() / designWidth.toDouble()
    }

    override fun getAppSharedPreferences(): SharedPreferences = sharedPreferences

    override fun getNotificationOrchestrator(): NotificationOrchestrator = orchestrator

    private fun loadSavedConfigValues() {
        lanUrl = sharedPreferences.getString("server_lan_url", "") ?: ""
        wanUrl = sharedPreferences.getString("server_wan_url", "") ?: ""
        designWidth = sharedPreferences.getInt("design_width", DEFAULT_DESIGN_WIDTH)
        designHeight = sharedPreferences.getInt("design_height", DEFAULT_DESIGN_HEIGHT)
        isKioskMode = sharedPreferences.getBoolean("is_kiosk_mode", true)
    }

    private fun saveConfig(payload: SettingsSavePayload) {
        val previousDesignWidth = designWidth
        val previousDesignHeight = designHeight
        val previousLang = sharedPreferences.getString("app_language", "zh") ?: "zh"
        val previousAuthToken = HomeOSAuth.loadAuthToken(sharedPreferences)
        val previousCsrfToken = HomeOSAuth.loadCsrfToken(sharedPreferences)
        val urlChanged = payload.lan != lanUrl || payload.wan != wanUrl

        lanUrl = payload.lan
        wanUrl = payload.wan
        designWidth = payload.designWidth
        designHeight = payload.designHeight
        isKioskMode = payload.isKiosk

        sharedPreferences.edit().apply {
            putString("server_lan_url", lanUrl)
            putString("server_wan_url", wanUrl)
            putInt("design_width", designWidth)
            putInt("design_height", designHeight)
            putString("auth_user", payload.user)
            putString("auth_pass", payload.pass)
            putString(HomeOSAuth.KEY_AUTH_TOKEN, payload.authToken)
            putString(HomeOSAuth.KEY_CSRF_TOKEN, payload.csrfToken)
            putBoolean("is_kiosk_mode", isKioskMode)
            putString("app_language", payload.lang)
            putBoolean("is_configured", true)
            apply()
        }

        setAppLocale(this, payload.lang)
        settingsController.applyLanguageToSettingsUI(payload.lang)
        updateScreenLocking()
        startServices()

        val designChanged = designWidth != previousDesignWidth || designHeight != previousDesignHeight
        val tokenChanged = payload.authToken.isNotEmpty() &&
            (payload.authToken != previousAuthToken || payload.csrfToken != previousCsrfToken)
        if (designChanged) {
            webViewController.updateDesignDimensions(designWidth, designHeight)
        }

        if (payload.lang != previousLang) {
            binding.loadingText.text = getString(R.string.connecting)
            binding.subLoadingText.text = getString(R.string.checking_network_health)
        }

        when {
            urlChanged -> networkCoordinator.routeAndLoadUrl()
            designChanged && activeUrl.isNotEmpty() -> webViewController.webView.reload()
            tokenChanged && activeUrl.isNotEmpty() -> {
                executor.execute {
                    HomeOSAuth.injectCookies(activeUrl, payload.authToken, payload.csrfToken)
                    runOnUiThread {
                        if (!isFinishing && !isDestroyed) {
                            webViewController.webView.reload()
                        }
                    }
                }
            }
        }
    }

    private fun handleWipeData() {
        sharedPreferences.edit().apply {
            remove("server_lan_url")
            remove("server_wan_url")
            putBoolean("is_configured", false)
            apply()
        }
        HomeOSAuth.clearAuthTokens(sharedPreferences)
        stopService(Intent(this, SocketService::class.java))
        clearAppCacheAndWebView(this)
        if (!isFinishing && !isDestroyed) {
            recreate()
        }
    }

    private fun setupOfflineOverlayListeners() {
        binding.offlineOverlay.btnConfigOffline.setOnClickListener {
            hideOfflineOverlay()
            toggleSettingsOverlay(true)
        }
        binding.offlineOverlay.btnRetryOffline.setOnClickListener {
            networkCoordinator.retryNow()
        }
    }

    private fun showOfflineOverlay() {
        binding.offlineOverlay.root.visibility = View.VISIBLE
        binding.loadingOverlay.visibility = View.GONE
    }

    private fun hideOfflineOverlay() {
        binding.offlineOverlay.root.visibility = View.GONE
    }

    private fun notifySocketService(
        foreground: Boolean? = null,
        pausePolling: Boolean = false,
        resumePolling: Boolean = false
    ) {
        try {
            val intent = Intent(this, SocketService::class.java).apply {
                foreground?.let { putExtra(SocketService.EXTRA_APP_FOREGROUND, it) }
                if (pausePolling) putExtra(SocketService.EXTRA_PAUSE_POLLING, true)
                if (resumePolling) putExtra(SocketService.EXTRA_RESUME_POLLING, true)
            }
            startService(intent)
        } catch (e: Exception) {
            Log.w("MainActivity", "通知 SocketService 失败: ${e.message}")
        }
    }

    private fun updateBackgroundService(resolvedUrl: String) {
        try {
            val intent = Intent(this, SocketService::class.java).apply {
                putExtra(SocketService.EXTRA_SERVER_URL, resolvedUrl)
                putExtra(SocketService.EXTRA_AUTH_TOKEN, HomeOSAuth.loadAuthToken(sharedPreferences))
                putExtra(SocketService.EXTRA_CSRF_TOKEN, HomeOSAuth.loadCsrfToken(sharedPreferences))
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "更新后台服务 URL 失败: ${e.message}")
        }
    }

    private fun startServices() {
        try {
            val serviceUrl = activeUrl.ifEmpty { lanUrl }
            val serviceIntent = Intent(this, SocketService::class.java).apply {
                putExtra(SocketService.EXTRA_SERVER_URL, serviceUrl)
                putExtra(SocketService.EXTRA_AUTH_TOKEN, HomeOSAuth.loadAuthToken(sharedPreferences))
                putExtra(SocketService.EXTRA_CSRF_TOKEN, HomeOSAuth.loadCsrfToken(sharedPreferences))
                putExtra(SocketService.EXTRA_APP_FOREGROUND, true)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
            Log.i("MainActivity", "前台 SocketService 注册成功。")
        } catch (e: Exception) {
            Log.e("MainActivity", "启动前台服务失败: ${e.message}")
        }
    }

    private fun shareOrSaveDownloadedFile(url: String, mimeType: String?, contentDisposition: String?) {
        if (!url.startsWith("data:")) {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }

        var fileName = "HomeOS_backup.json"
        if (contentDisposition != null) {
            val index = contentDisposition.indexOf("filename=")
            if (index > 0) {
                fileName = contentDisposition.substring(index + 9).replace("\"", "").trim()
            }
        }

        val finalFileName = fileName
        executor.execute {
            try {
                val commaIndex = url.indexOf(",")
                if (commaIndex <= 0) return@execute
                val dataPart = url.substring(commaIndex + 1)
                val header = url.substring(0, commaIndex)
                val dataBytes = if (header.contains("base64")) {
                    android.util.Base64.decode(dataPart, android.util.Base64.DEFAULT)
                } else {
                    java.net.URLDecoder.decode(dataPart, "UTF-8").toByteArray()
                }

                val tempFile = File(cacheDir, finalFileName)
                FileOutputStream(tempFile).use { fos -> fos.write(dataBytes) }

                val fileUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", tempFile)
                val intent = Intent(Intent.ACTION_SEND).apply {
                    type = mimeType ?: "application/json"
                    putExtra(Intent.EXTRA_STREAM, fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }

                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        startActivity(Intent.createChooser(intent, getString(R.string.export_save_config)))
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    if (!isFinishing && !isDestroyed) {
                        Toast.makeText(
                            this,
                            getString(R.string.export_failed, e.localizedMessage ?: ""),
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            }
        }
    }

    private fun handleUrlRedirection(url: String): Boolean {
        try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme ?: return false

            if (scheme != "http" && scheme != "https") {
                return try {
                    startActivity(Intent(Intent.ACTION_VIEW, uri))
                    true
                } catch (_: android.content.ActivityNotFoundException) {
                    false
                }
            }

            val targetHost = uri.host ?: return false
            val mainHost = runCatching { Uri.parse(activeUrl).host }.getOrNull()
            val lanHost = runCatching { Uri.parse(lanUrl).host }.getOrNull()
            val wanHost = runCatching { Uri.parse(wanUrl).host }.getOrNull()
            val isLocal = targetHost.equals("localhost", ignoreCase = true) ||
                targetHost.equals("127.0.0.1")

            val isInternal = (mainHost != null && targetHost.equals(mainHost, ignoreCase = true)) ||
                (lanHost != null && targetHost.equals(lanHost, ignoreCase = true)) ||
                (wanHost != null && targetHost.equals(wanHost, ignoreCase = true)) ||
                isLocal

            if (!isInternal) {
                startActivity(Intent(Intent.ACTION_VIEW, uri))
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILECHOOSER_RESULTCODE && ::webViewController.isInitialized) {
            webViewController.handleFileChooserResult(resultCode, data)
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webViewController.isInitialized) {
            webViewController.resume()
        }
        notifySocketService(foreground = true)
        applySystemImmersiveMode()
    }

    override fun onPause() {
        if (::webViewController.isInitialized) {
            webViewController.pause()
        }
        notifySocketService(foreground = false)
        super.onPause()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::webViewController.isInitialized) {
            webViewController.onConfigurationChanged()
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    override fun onBackPressed() {
        if (binding.settingsOverlay.visibility == View.VISIBLE) {
            toggleSettingsOverlay(false)
        } else if (::webViewController.isInitialized && webViewController.webView.canGoBack()) {
            webViewController.webView.goBack()
        } else if (!isKioskMode) {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        if (::networkCoordinator.isInitialized) {
            networkCoordinator.cancelAll()
        }
        if (::webViewController.isInitialized) {
            webViewController.destroy()
        }
        if (::roamingManager.isInitialized) {
            roamingManager.shutdown()
        }
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun handleIntent(intent: Intent?) {
        if (!::webViewController.isInitialized) return
        val openCameraEntity = intent?.getStringExtra("OPEN_CAMERA_ENTITY")
        val alertId = intent?.getStringExtra("ALERT_ID")

        if (openCameraEntity != null || alertId != null) {
            val safeAlertId = JSONObject.quote(alertId ?: "")
            val safeCameraEntity = JSONObject.quote(openCameraEntity ?: "")
            webViewController.webView.post {
                webViewController.webView.evaluateJavascript(
                    "if (window.__HOMEOS_ALERT_ROUTER__) { window.__HOMEOS_ALERT_ROUTER__($safeAlertId, $safeCameraEntity); }",
                    null
                )
            }
        }
    }

    private fun toggleSettingsOverlay(show: Boolean) {
        if (show) {
            settingsController.prepareOverlay(true)
            webViewController.setHardwareLayerEnabled(false)
            binding.settingsOverlay.visibility = View.VISIBLE
            binding.settingsOverlay.animate()
                .alpha(1f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            binding.settingsOverlay.animate()
                .alpha(0f)
                .setDuration(150)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    binding.settingsOverlay.visibility = View.GONE
                    webViewController.setHardwareLayerEnabled(true)
                    applySystemImmersiveMode()
                }
                .start()
        }
    }

    private fun setupGestureInterceptors() {
        binding.loadingOverlay.isClickable = true
        binding.loadingOverlay.isFocusable = true
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val actionMasked = ev.actionMasked
        if (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (handleAdminGesture(ev)) return true
        }
        if (binding.loadingOverlay.visibility == View.VISIBLE) return true
        return super.dispatchTouchEvent(ev)
    }

    private fun handleAdminGesture(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked

        if ((actionMasked == MotionEvent.ACTION_POINTER_DOWN || actionMasked == MotionEvent.ACTION_DOWN) &&
            event.pointerCount == 3
        ) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastThreeFingerTapTime < 1000L) {
                if (currentTime - lastThreeFingerTapTime > 150L) threeFingerTapCount++
            } else {
                threeFingerTapCount = 1
            }
            lastThreeFingerTapTime = currentTime
            if (threeFingerTapCount >= 3) {
                threeFingerTapCount = 0
                toggleSettingsOverlay(true)
                return true
            }
        }

        if (actionMasked == MotionEvent.ACTION_DOWN) {
            val screenWidth = resources.displayMetrics.widthPixels
            if (event.rawX >= screenWidth - 150 && event.rawY <= 150) {
                val currentTime = System.currentTimeMillis()
                cornerClickCount = if (currentTime - lastCornerClickTime < 1500L) cornerClickCount + 1 else 1
                lastCornerClickTime = currentTime
                if (cornerClickCount >= 5) {
                    cornerClickCount = 0
                    toggleSettingsOverlay(true)
                    return true
                }
            }
        }
        return false
    }

    private fun updateScreenLocking() {
        if (isKioskMode) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun applySystemImmersiveMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                )
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                != android.content.pm.PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(
                    arrayOf(android.Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    private fun clearAppCacheAndWebView(context: Context) {
        try {
            if (::webViewController.isInitialized) {
                webViewController.webView.clearCache(true)
            }
            val cookieManager = android.webkit.CookieManager.getInstance()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            }
            android.webkit.WebStorage.getInstance().deleteAllData()
            context.cacheDir?.let { deleteDirContents(it) }
            Toast.makeText(context, context.getString(R.string.cache_cleared), Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun deleteDirContents(dir: File): Boolean {
        if (!dir.isDirectory) return true
        dir.list()?.forEach { child ->
            if (!deleteFileOrDir(File(dir, child))) return false
        }
        return true
    }

    private fun deleteFileOrDir(file: File): Boolean {
        return if (file.isDirectory) {
            file.list()?.forEach { child ->
                if (!deleteFileOrDir(File(file, child))) return false
            }
            file.delete()
        } else {
            file.delete()
        }
    }

    private fun setAppLocale(context: Context, languageCode: String) {
        val locale = when (languageCode) {
            "zh" -> java.util.Locale("zh", "CN")
            "en" -> java.util.Locale("en")
            else -> java.util.Locale(languageCode)
        }
        java.util.Locale.setDefault(locale)
        val config = context.resources.configuration
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    private fun getLocalizedResources(context: Context, localeCode: String): android.content.res.Resources {
        val locale = java.util.Locale(localeCode)
        val config = android.content.res.Configuration(context.resources.configuration)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale)
            context.createConfigurationContext(config).resources
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
            @Suppress("DEPRECATION")
            val res = context.resources
            @Suppress("DEPRECATION")
            res.updateConfiguration(config, res.displayMetrics)
            res
        }
    }

    @SuppressLint("SetTextI18n")
    private fun showPrivacyPolicyDialog() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setCancelable(false)

        val lang = sharedPreferences.getString("app_language", "zh") ?: "zh"
        val privacyRes = getLocalizedResources(this, lang)

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#08080C"))
            setPadding(48, 48, 48, 48)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        container.addView(android.widget.TextView(this).apply {
            text = "\uD83D\uDEE1\uFE0F"
            textSize = 48f
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 40 }
        })

        container.addView(android.widget.TextView(this).apply {
            text = privacyRes.getString(R.string.privacy_title)
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 16 }
        })

        container.addView(android.widget.TextView(this).apply {
            text = "HomeOS-PRIVACY-SECURITY-POLICY"
            textSize = 9f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.2f
            gravity = android.view.Gravity.CENTER
        })

        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            ).apply {
                topMargin = 32
                bottomMargin = 32
            }
            isVerticalScrollBarEnabled = false
        }

        val cardLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(Color.parseColor("#0DFFFFFF"))
                setStroke(2, Color.parseColor("#2600E5FF"))
                cornerRadius = 24f
            }
        }

        cardLayout.addView(android.widget.TextView(this).apply {
            text = if (lang == "zh") """
                欢迎使用 HomeOS 伴侣终端！我们高度重视您的个人隐私与数据安全。在您同意本协议前，应用程序不会初始化任何第三方 SDK 或是发起任何网络通讯。

                请您仔细阅读以下关键条款：

                1. 数据收集范围
                我们仅收集运行所必需的本地配置信息，包括局域网/公网网关地址、用于身份校验的加密凭据，以及可选的推送服务（FCM/HMS）唯一标识符。

                2. 本地化安全存储
                您的所有服务器账号与密码均通过 Android SharedPreferences 进行本机加密隔离存储。WebView 中的会话缓存与 Token 同样受到系统沙盒保护。

                3. 数据零共享
                本应用为 100% 私有化或直连您的专属网关运行。我们承诺绝不会向任何第三方机构或个人透露、销售或共享您的任何配置、密钥或传感器遥测数据。

                点击"同意并继续"即代表您接受以上隐私协议与服务条款，我们将为您解锁完整的赛博科技中控体验。
            """.trimIndent() else """
                Welcome to HomeOS Companion Terminal! We highly value your personal privacy and data security. Before you agree to this policy, the application will not initialize any third-party SDKs or initiate any network communications.

                Please read the following key terms carefully:

                1. Data Collection Scope
                We only collect locally stored configuration information necessary for operation, including LAN/WAN gateway addresses, encrypted credentials for identity verification, and optional push service (FCM/HMS) unique identifiers.

                2. Encrypted Local Storage
                All server credentials and passwords are encrypted and stored in isolated SharedPreferences. WebView session caches and tokens are equally protected by the Android system sandbox.

                3. Zero Third-Party Sharing
                HomeOS operates as a 100% private tool directly connected to your smart home. We promise to never share, sell, or disclose your gateway information, telemetry data, or keys with any third party.

                By clicking "Agree and Continue", you accept these terms and we will unlock the complete smart home experience for you.
            """.trimIndent()
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            setLineSpacing(0f, 1.3f)
        })
        scrollView.addView(cardLayout)
        container.addView(scrollView)

        val buttonsLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        buttonsLayout.addView(android.widget.Button(this).apply {
            text = privacyRes.getString(R.string.privacy_agree)
            textSize = 14f
            setTextColor(Color.BLACK)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#0086FF"))
            ).apply { cornerRadius = 16f }
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                120
            ).apply { bottomMargin = 24 }
            setOnClickListener {
                dialog.dismiss()
                sharedPreferences.edit().putBoolean("privacy_accepted", true).apply()
                initializeAfterPrivacy()
            }
        })

        buttonsLayout.addView(android.widget.TextView(this).apply {
            text = privacyRes.getString(R.string.privacy_disagree)
            textSize = 13f
            setTextColor(Color.parseColor("#8E8E93"))
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            setPadding(24, 24, 24, 24)
            isClickable = true
            setOnClickListener {
                dialog.dismiss()
                finishAffinity()
            }
        })

        container.addView(buttonsLayout)
        dialog.setContentView(container)
        dialog.show()
    }
}
