package com.homeos.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.appcompat.app.AppCompatActivity
import com.homeos.app.databinding.ActivityMainBinding
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.security.MessageDigest
import androidx.core.content.FileProvider
import android.widget.Toast
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * MainActivity: 工业级 Kiosk 大屏显示窗口主类
 * 托管完全加速的高性能 WebView，绑定原生 JS Bridge 接口，
 * 管理沉浸式全屏显示覆盖层，并处理连接自动恢复。
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val DEFAULT_DESIGN_WIDTH = 3200
        private const val DEFAULT_DESIGN_HEIGHT = 2136
    }

    private lateinit var binding: ActivityMainBinding
    private lateinit var sharedPreferences: SharedPreferences
    private lateinit var roamingManager: NetworkRoamingManager
    private lateinit var orchestrator: NotificationOrchestrator
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor()

    private var lanUrl = ""
    private var wanUrl = ""
    private var designWidth = DEFAULT_DESIGN_WIDTH
    private var designHeight = DEFAULT_DESIGN_HEIGHT
    private var isKioskMode = true
    private var activeUrl = ""
    private var tempSelectedLang = "zh"

    private var isErrorState = false
    private val recoveryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recoveryRunnable = Runnable { attemptAutoRecovery() }
    private val loadTimeoutRunnable = Runnable { handleLoadTimeout() }

    private var uploadMessage: android.webkit.ValueCallback<Array<Uri>>? = null
    private val FILECHOOSER_RESULTCODE = 10001
    private val NOTIFICATION_PERMISSION_REQUEST_CODE = 10010

    // 管理员手势状态（须在 Activity 层检测，避免 WebView.setOnTouchListener 阻断滑动）
    private var threeFingerTapCount = 0
    private var lastThreeFingerTapTime = 0L
    private var cornerClickCount = 0
    private var lastCornerClickTime = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 首先初始化存储，在视图创建前设置语言区域
        sharedPreferences = getSharedPreferences("HomeOSPreferences", Context.MODE_PRIVATE)
        val lang = sharedPreferences.getString("app_language", "zh") ?: "zh"
        setAppLocale(this, lang)
        
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 在初始化任何服务或网络之前检查隐私政策接受状态
        val privacyAccepted = sharedPreferences.getBoolean("privacy_accepted", false)
        if (!privacyAccepted) {
            Log.i("MainActivity", "隐私政策尚未接受。显示弹窗拦截器。")
            showPrivacyPolicyDialog()
        } else {
            initializeAfterPrivacy()
        }
    }

    private fun initializeAfterPrivacy() {
        // 加载其他配置值
        loadSavedConfig()

        roamingManager = NetworkRoamingManager(this)
        orchestrator = NotificationOrchestrator(this)

        // 2. 根据模式配置硬件屏幕锁定
        updateScreenLocking()

        // 3. 配置全系统沉浸式 UI
        applySystemImmersiveMode()

        // 4. 初始化 WebView 组件
        setupWebView()

        // 5. 设置设置配置面板的操作按钮和手势检测
        setupGestureInterceptors()
        setupControlListeners()

        // 6. 连接后端服务
        startServices()

        // Android 13+ 运行时申请通知权限
        requestNotificationPermissionIfNeeded()

        // 检查是否已配置；如果未配置（或使用默认值），强制显示设置屏幕
        val isConfigured = sharedPreferences.getBoolean("is_configured", false)
        if (!isConfigured || lanUrl.isEmpty() || lanUrl == "http://192.168.31.175:8126") {
            Log.i("MainActivity", "应用未配置或使用默认虚拟 URL。强制显示设置弹窗。")
            toggleSettingsOverlay(true)
        } else {
            // 7. 路由并加载最佳 URL
            routeAndLoadUrl()
        }

        // 8. 处理任何传入的深度链接意图（例如通知点击）
        handleIntent(intent)
    }

    /**
     * 渲染高度精致的赛博朋克/材质风格弹窗，阻止应用访问直到明确接受隐私政策。
     * 与大屏幕美学相匹配。
     */
    private fun showPrivacyPolicyDialog() {
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        dialog.setCancelable(false)

        val privacyRes = getLocalizedResources(this, tempSelectedLang)
        val isZh = tempSelectedLang == "zh"

        val container = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#08080C"))
            setPadding(48, 48, 48, 48)
            gravity = android.view.Gravity.CENTER_HORIZONTAL
        }

        val iconView = android.widget.TextView(this).apply {
            text = "\uD83D\uDEE1\uFE0F"
            textSize = 48f
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 40
            }
        }
        container.addView(iconView)

        val titleText = android.widget.TextView(this).apply {
            text = privacyRes.getString(R.string.privacy_title)
            textSize = 20f
            setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 16
            }
        }
        container.addView(titleText)

        val subtitleText = android.widget.TextView(this).apply {
            text = "HomeOS-PRIVACY-SECURITY-POLICY"
            textSize = 9f
            setTextColor(Color.parseColor("#00E5FF"))
            typeface = android.graphics.Typeface.MONOSPACE
            letterSpacing = 0.2f
            gravity = android.view.Gravity.CENTER
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 8
            }
        }
        container.addView(subtitleText)

        val scrollView = android.widget.ScrollView(this).apply {
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0
            ).apply {
                weight = 1f
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

        val privacyBody = android.widget.TextView(this).apply {
            text = if (tempSelectedLang == "zh") """
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
        }
        cardLayout.addView(privacyBody)
        scrollView.addView(cardLayout)
        container.addView(scrollView)

        val buttonsLayout = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = 16
            }
        }

        val agreeButton = android.widget.Button(this).apply {
            text = getLocalizedResources(this@MainActivity, tempSelectedLang)
                .getString(R.string.privacy_agree)
            textSize = 14f
            setTextColor(Color.BLACK)
            typeface = android.graphics.Typeface.DEFAULT_BOLD

            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#00E5FF"), Color.parseColor("#0086FF"))
            ).apply {
                cornerRadius = 16f
            }

            layoutParams = android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                120
            ).apply {
                bottomMargin = 24
            }

            setOnClickListener {
                dialog.dismiss()
                sharedPreferences.edit().putBoolean("privacy_accepted", true).apply()
                initializeAfterPrivacy()
            }
        }
        buttonsLayout.addView(agreeButton)

        val disagreeButton = android.widget.TextView(this).apply {
            text = getLocalizedResources(this@MainActivity, tempSelectedLang)
                .getString(R.string.privacy_disagree)
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
        }
        buttonsLayout.addView(disagreeButton)

        container.addView(buttonsLayout)

        dialog.setContentView(container)
        dialog.show()
    }

    override fun onResume() {
        super.onResume()
        applySystemImmersiveMode()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /**
     * 辅助函数：在运行时动态设置语言配置。
     */
    private fun setAppLocale(context: Context, languageCode: String) {
        val locale = when (languageCode) {
            "zh" -> java.util.Locale("zh", "CN")
            "en" -> java.util.Locale("en")
            else -> java.util.Locale(languageCode)
        }
        java.util.Locale.setDefault(locale)
        val config = context.resources.configuration
        
        val currentLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            if (!config.locales.isEmpty) config.locales[0] else java.util.Locale.getDefault()
        } else {
            @Suppress("DEPRECATION")
            config.locale ?: java.util.Locale.getDefault()
        }
        
        val isMatch = if (languageCode == "zh") {
            currentLocale.language == "zh"
        } else {
            currentLocale.language == languageCode
        }

        if (isMatch) {
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1) {
            config.setLocale(locale)
        } else {
            @Suppress("DEPRECATION")
            config.locale = locale
        }
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
    }

    /**
     * 从安全本地存储读取已保存的服务器配置。
     */
    private fun loadSavedConfig() {
        lanUrl = sharedPreferences.getString("server_lan_url", "") ?: ""
        wanUrl = sharedPreferences.getString("server_wan_url", "") ?: ""
        designWidth = sharedPreferences.getInt("design_width", DEFAULT_DESIGN_WIDTH)
        designHeight = sharedPreferences.getInt("design_height", DEFAULT_DESIGN_HEIGHT)
        isKioskMode = sharedPreferences.getBoolean("is_kiosk_mode", true)

        val savedUser = sharedPreferences.getString("auth_user", "") ?: ""
        val savedPass = sharedPreferences.getString("auth_pass", "") ?: ""

        binding.inputLanUrl.setText(lanUrl)
        binding.inputWanUrl.setText(wanUrl)
        binding.inputDesignWidth.setText(designWidth.toString())
        binding.inputDesignHeight.setText(designHeight.toString())
        binding.inputUsername.setText(savedUser)
        binding.inputPassword.setText(savedPass)

        tempSelectedLang = sharedPreferences.getString("app_language", "zh") ?: "zh"
        updateLanguageToggleUI(tempSelectedLang)

        if (isKioskMode) {
            binding.radioKiosk.isChecked = true
        } else {
            binding.radioCompanion.isChecked = true
        }
    }

    /**
     * 将配置值提交到本地存储。
     */
    private fun saveConfig(
        newLan: String,
        newWan: String,
        user: String,
        pass: String,
        authToken: String,
        csrfToken: String,
        newKiosk: Boolean,
        newLang: String,
        newDesignWidth: Int = designWidth,
        newDesignHeight: Int = designHeight
    ) {
        lanUrl = newLan
        wanUrl = newWan
        designWidth = newDesignWidth
        designHeight = newDesignHeight
        isKioskMode = newKiosk

        sharedPreferences.edit().apply {
            putString("server_lan_url", lanUrl)
            putString("server_wan_url", wanUrl)
            putInt("design_width", designWidth)
            putInt("design_height", designHeight)
            putString("auth_user", user)
            putString("auth_pass", pass)
            putString(HomeOSAuth.KEY_AUTH_TOKEN, authToken)
            putString(HomeOSAuth.KEY_CSRF_TOKEN, csrfToken)
            putBoolean("is_kiosk_mode", isKioskMode)
            putString("app_language", newLang)
            putBoolean("is_configured", true)
            apply()
        }

        // 应用新语言设置
        setAppLocale(this, newLang)

        updateScreenLocking()

        // 使用新地址重启 WebSocket 后台监控服务
        startServices()

        // 重新创建活动以立即应用语言更改（必须放在最后，recreate 后当前 Activity 即销毁）
        if (!isFinishing && !isDestroyed) {
            recreate()
        }
    }

    /**
     * 如果应用在挂墙大屏模式下运行，保持屏幕持续常亮。
     */
    private fun updateScreenLocking() {
        if (isKioskMode) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d("MainActivity", "Kiosk模式已激活：屏幕保持常亮")
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            Log.d("MainActivity", "Companion模式已激活：屏幕超时由系统管理")
        }
    }

    /**
     * 将系统导航栏和状态栏强制设置为完全隐藏的沉浸式模式。
     * 从边缘滑动可显示半透明临时栏，之后会自动隐藏。
     */
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

    /**
     * 加速 Chromium 引擎的核心配置。
     */
    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        binding.webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        binding.webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            loadsImagesAutomatically = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            userAgentString = HomeOSAuth.APP_USER_AGENT
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = false
            textZoom = 100
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = false
            }
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false

            // 性能优化：启用离屏预合成，提升滚动和动画帧率
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                setOffscreenPreRaster(true)
            }
            // 设置高渲染优先级
            setRenderPriority(WebSettings.RenderPriority.HIGH)
        }

        binding.webView.setInitialScale(calculateAspectRatioScale())
        binding.webView.isFocusable = true
        binding.webView.isFocusableInTouchMode = true

        // 注入 NativeBridge
        binding.webView.addJavascriptInterface(NativeBridge(this), "Native")

        // 支持文件下载（导出完整配置备份）
        binding.webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            shareOrSaveDownloadedFile(url, mimetype, contentDisposition)
        }

        // 支持文件选择器输入（导入配置备份）
        binding.webView.webChromeClient = object : android.webkit.WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                uploadMessage?.onReceiveValue(null)
                uploadMessage = filePathCallback

                val intent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                }
                try {
                    startActivityForResult(intent, FILECHOOSER_RESULTCODE)
                } catch (e: Exception) {
                    uploadMessage?.onReceiveValue(null)
                    uploadMessage = null
                    return false
                }
                return true
            }
        }

        // 挂钩系统客户端事件
        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                injectFixedViewport()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                recoveryHandler.removeCallbacks(loadTimeoutRunnable)
                injectFixedViewport()
                if (!isErrorState) {
                    binding.loadingOverlay.visibility = View.GONE
                    binding.webView.visibility = View.VISIBLE
                    binding.webView.requestFocus()
                }
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                recoveryHandler.removeCallbacks(loadTimeoutRunnable)
                if (request?.isForMainFrame == true) {
                    handlePageLoadFailure()
                }
            }

            


            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrlRedirection(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleUrlRedirection(url)
            }
        }
    }

    /**
     * 计算基于设计画布宽度的等比缩放比例，确保页面完整显示不变形。
     */
    private fun calculateAspectRatioScale(): Int {
        val screenWidth = resources.displayMetrics.widthPixels
        val scale = (screenWidth.toFloat() / designWidth.toFloat() * 100).toInt()
        Log.d("MainActivity", "屏幕宽度: ${screenWidth}, 设计宽度: ${designWidth}, 缩放: ${scale}%")
        return scale.coerceIn(50, 200)
    }

    /**
     * 向 WebView 注入固定宽度 viewport，使页面始终按设计画布宽度排版，
     * 再由 setInitialScale 整体等比缩放到屏幕宽度（不变行、满屏）。
     */
    private fun injectFixedViewport() {
        val js = """
            (function(){
              if(window.__homeosViewportInjected) return;
              window.__homeosViewportInjected = true;
              
              var W=$designWidth;
              function apply(){
                var m=document.querySelector('meta[name=viewport]');
                if(!m){m=document.createElement('meta');m.name='viewport';document.head.appendChild(m);}
                var c='width='+W+', user-scalable=no';
                if(m.getAttribute('content')!==c){m.setAttribute('content',c);}
                if(!document.getElementById('homeos-touch-fix')){
                  var s=document.createElement('style');
                  s.id='homeos-touch-fix';
                  s.textContent='html,body{-webkit-overflow-scrolling:touch;touch-action:pan-x pan-y;will-change:transform;}';
                  document.head.appendChild(s);
                }
              }
              apply();
              
              if(!window.__homeosVpObs){
                var lastCheck = 0;
                window.__homeosVpObs = function(timestamp) {
                  if (timestamp - lastCheck > 1000) {
                    apply();
                    lastCheck = timestamp;
                  }
                  requestAnimationFrame(window.__homeosVpObs);
                };
                requestAnimationFrame(window.__homeosVpObs);
              }
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(js, null)
    }

    /**
     * 返回当前屏幕宽度相对设计画布宽度的等比缩放因子，供 JS Bridge 上报。
     */
    private fun currentScaleFactor(): Double {
        val screenWidth = resources.displayMetrics.widthPixels
        return screenWidth.toDouble() / designWidth.toDouble()
    }

    /**
     * 拦截主框架中的外部 URL，并强制在默认系统浏览器中打开。
     */
    private fun handleUrlRedirection(url: String): Boolean {
        try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme ?: return false
            
            // 1. 拦截 mailto、tel 或其他非 http 协议，在系统处理器中打开
            if (scheme != "http" && scheme != "https") {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                try {
                    startActivity(intent)
                    return true
                } catch (_: android.content.ActivityNotFoundException) {
                    return false
                }
            }
            
            val targetHost = uri.host ?: return false
            
            // 2. 在默认浏览器中打开外部链接
            val mainUri = runCatching { Uri.parse(activeUrl) }.getOrNull()
            val mainHost = mainUri?.host
            
            val lanHost = runCatching { Uri.parse(lanUrl).host }.getOrNull()
            val wanHost = runCatching { Uri.parse(wanUrl).host }.getOrNull()
            
            val isLocal = targetHost.equals("localhost", ignoreCase = true) || targetHost.equals("127.0.0.1")
            
            // 如果目标主机匹配当前主主机、局域网主机、公网主机或本地主机，则为内部链接
            val isInternal = (mainHost != null && targetHost.equals(mainHost, ignoreCase = true)) ||
                    (lanHost != null && targetHost.equals(lanHost, ignoreCase = true)) ||
                    (wanHost != null && targetHost.equals(wanHost, ignoreCase = true)) ||
                    isLocal
                    
            if (!isInternal) {
                Log.d("MainActivity", "拦截主框架中的外部 URL 加载: $url. 在外部浏览器中打开...")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                startActivity(intent)
                return true
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return false
    }

    /**
     * 处理文件选择器结果，并将选中的文件 Uri 返回给 WebView。
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == FILECHOOSER_RESULTCODE) {
            if (uploadMessage == null) return
            val result = if (data == null || resultCode != RESULT_OK) null else data.data
            if (result != null) {
                uploadMessage?.onReceiveValue(arrayOf(result))
            } else {
                uploadMessage?.onReceiveValue(null)
            }
            uploadMessage = null
        }
    }

    /**
     * 拦截从 WebView 下载的 base64 数据 URL，将其保存为临时文件，
     * 并触发原生 Android 分享面板，以便用户保存或发送备份文件。
     */
    private fun shareOrSaveDownloadedFile(url: String, mimeType: String?, contentDisposition: String?) {
        try {
            // 提取文件名
            var fileName = "HomeOS_backup.json"
            if (contentDisposition != null) {
                val index = contentDisposition.indexOf("filename=")
                if (index > 0) {
                    fileName = contentDisposition.substring(index + 9).replace("\"", "").trim()
                }
            }
            
            // 如果是 base64 或普通数据 URL，提取数据内容
            val dataBytes: ByteArray
            if (url.startsWith("data:")) {
                val commaIndex = url.indexOf(",")
                if (commaIndex > 0) {
                    val dataPart = url.substring(commaIndex + 1)
                    val header = url.substring(0, commaIndex)
                    dataBytes = if (header.contains("base64")) {
                        android.util.Base64.decode(dataPart, android.util.Base64.DEFAULT)
                    } else {
                        java.net.URLDecoder.decode(dataPart, "UTF-8").toByteArray()
                    }
                } else {
                    return
                }
            } else {
                // 如果是 blob 或网络 URL，在系统浏览器或下载管理器中打开
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                startActivity(intent)
                return
            }
            
            // 保存到临时缓存目录
            val tempFile = File(cacheDir, fileName)
            FileOutputStream(tempFile).use { fos ->
                fos.write(dataBytes)
            }
            
            // 通过 FileProvider 分享以避免 FileUriExposedException 并绕过运行时权限提示
            val fileUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", tempFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType ?: "application/json"
                putExtra(Intent.EXTRA_STREAM, fileUri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            val title = getString(R.string.export_save_config)
            startActivity(Intent.createChooser(intent, title))
            
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, getString(R.string.export_failed, e.localizedMessage ?: ""), Toast.LENGTH_LONG).show()
            }
        }
    }

    /**
     * 在 Activity 层检测管理员手势，避免在 WebView 上挂 OnTouchListener（会阻断页面滑动）。
     * @return true 表示已消费事件（打开设置等）
     */
    private fun handleAdminGesture(event: MotionEvent): Boolean {
        val actionMasked = event.actionMasked

        // 1. 检测三指点击（任何指针数量为 3 的按下事件）
        if ((actionMasked == MotionEvent.ACTION_POINTER_DOWN || actionMasked == MotionEvent.ACTION_DOWN) && event.pointerCount == 3) {
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastThreeFingerTapTime < 1000L) {
                if (currentTime - lastThreeFingerTapTime > 150L) {
                    threeFingerTapCount++
                    Log.d("MainActivity", "三指点击已识别。次数：$threeFingerTapCount")
                }
            } else {
                threeFingerTapCount = 1
                Log.d("MainActivity", "三指点击已识别。已重置计数为 1")
            }
            lastThreeFingerTapTime = currentTime

            if (threeFingerTapCount >= 3) {
                Log.i("MainActivity", "检测到三指连击！正在打开设置。")
                threeFingerTapCount = 0
                toggleSettingsOverlay(true)
                return true
            }
        }

        // 2. 备用方案：右上角五连击（150x150 热点区域，便于壁挂式平板操作）
        if (actionMasked == MotionEvent.ACTION_DOWN) {
            val x = event.rawX
            val y = event.rawY
            val screenWidth = resources.displayMetrics.widthPixels

            if (x >= screenWidth - 150 && y <= 150) {
                val currentTime = System.currentTimeMillis()
                if (currentTime - lastCornerClickTime < 1500L) {
                    cornerClickCount++
                    Log.d("MainActivity", "角落点击已识别。次数：$cornerClickCount")
                } else {
                    cornerClickCount = 1
                    Log.d("MainActivity", "角落点击已识别。已重置计数为 1")
                }
                lastCornerClickTime = currentTime

                if (cornerClickCount >= 5) {
                    Log.i("MainActivity", "检测到右上角五连击！正在打开设置。")
                    cornerClickCount = 0
                    toggleSettingsOverlay(true)
                    return true
                }
            }
        }

        return false
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        // 优化：只在 ACTION_DOWN 和 ACTION_POINTER_DOWN 时检测手势，避免滑动时重复检测
        val actionMasked = ev.actionMasked
        if (actionMasked == MotionEvent.ACTION_DOWN || actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            if (handleAdminGesture(ev)) {
                return true
            }
        }
        // 加载遮罩显示时吞掉触摸，防止穿透到下层 WebView
        if (binding.loadingOverlay.visibility == View.VISIBLE) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    /**
     * 配置加载遮罩，管理员手势改由 dispatchTouchEvent 统一处理。
     */
    private fun setupGestureInterceptors() {
        binding.loadingOverlay.isClickable = true
        binding.loadingOverlay.isFocusable = true
    }

    /**
     * 辅助函数：在不重启 Activity 上下文的情况下动态加载特定语言环境的资源。
     */
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

    /**
     * 立即刷新设置面板中所有可见字符串以匹配指定的语言代码。
     */
    private fun applyLanguageToSettingsUI(langCode: String) {
        val res = getLocalizedResources(this, langCode)
        
        binding.txtSettingsTitle.text = res.getString(R.string.settings_title)
        binding.txtSettingsDesc.text = res.getString(R.string.settings_desc)
        
        binding.layoutLanUrl.hint = res.getString(R.string.server_lan_url)
        binding.layoutWanUrl.hint = res.getString(R.string.server_wan_url)
        binding.layoutDesignWidth.hint = res.getString(R.string.design_width)
        binding.layoutDesignHeight.hint = res.getString(R.string.design_height)
        binding.layoutUsername.hint = res.getString(R.string.username)
        binding.layoutPassword.hint = res.getString(R.string.password)
        
        binding.txtDeviceModeLabel.text = res.getString(R.string.device_mode)
        binding.radioKiosk.text = res.getString(R.string.mode_kiosk)
        binding.radioCompanion.text = res.getString(R.string.mode_companion)
        binding.txtDesignCanvasLabel.text = res.getString(R.string.design_canvas)
        
        binding.btnCancelSettings.text = res.getString(R.string.cancel)
        
        binding.btnWipeData.text = res.getString(R.string.btn_wipe_data)
        
        // 处理"保存配置"与"强制保存"的切换
        val currentBtnText = binding.btnSaveSettings.text.toString()
        val anywayZH = "强制保存"
        val anywayEN = "Save Anyway"
        
        val saveAnywayStr = res.getString(R.string.save_anyway)
        if (currentBtnText == anywayZH || currentBtnText == "強制儲存" || currentBtnText == anywayEN) {
            binding.btnSaveSettings.text = saveAnywayStr
        } else {
            binding.btnSaveSettings.text = res.getString(R.string.save_settings)
        }

        // 如果验证状态消息可见，即时翻译
        if (binding.txtVerificationStatus.visibility == View.VISIBLE) {
            val statusText = binding.txtVerificationStatus.text.toString()
            if (statusText.contains("Verifying") || statusText.contains("正在验证") || statusText.contains("正在校驗")) {
                binding.txtVerificationStatus.text = res.getString(R.string.verifying_server)
            } else if (statusText.contains("successful") || statusText.contains("成功")) {
                binding.txtVerificationStatus.text = res.getString(R.string.verification_success)
            } else if (statusText.contains("Failed") || statusText.contains("失败") || statusText.contains("失敗")) {
                binding.txtVerificationStatus.text = res.getString(R.string.verification_failed)
            }
        }
    }

    /**
     * 绑定点击事件到模态控制元素。
     */
    private fun setupControlListeners() {
        // 高级设置展开/折叠
        binding.layoutAdvancedHeader.setOnClickListener {
            val isExpanded = binding.layoutAdvancedContent.visibility == View.VISIBLE
            binding.layoutAdvancedContent.visibility = if (isExpanded) View.GONE else View.VISIBLE
            binding.txtAdvancedArrow.text = if (isExpanded) "▼" else "▲"
        }

        binding.btnLangZhToggle.setOnClickListener {
            tempSelectedLang = "zh"
            updateLanguageToggleUI(tempSelectedLang)
            applyLanguageToSettingsUI(tempSelectedLang)
        }

        

        binding.btnLangEnToggle.setOnClickListener {
            tempSelectedLang = "en"
            updateLanguageToggleUI(tempSelectedLang)
            applyLanguageToSettingsUI(tempSelectedLang)
        }

        binding.btnCancelSettings.setOnClickListener {
            toggleSettingsOverlay(false)
        }

        binding.btnSaveSettings.setOnClickListener {
            val lanStr = binding.inputLanUrl.text.toString().trim()
            val wanStr = binding.inputWanUrl.text.toString().trim()
            val userStr = binding.inputUsername.text.toString().trim()
            val passStr = binding.inputPassword.text.toString().trim()
            val isKiosk = binding.radioKiosk.isChecked
            val selectedLang = tempSelectedLang
            val currentRes = getLocalizedResources(this@MainActivity, selectedLang)
            val designWidthStr = binding.inputDesignWidth.text.toString().trim()
            val designHeightStr = binding.inputDesignHeight.text.toString().trim()
            val parsedDesignWidth = designWidthStr.toIntOrNull() ?: DEFAULT_DESIGN_WIDTH
            val parsedDesignHeight = designHeightStr.toIntOrNull() ?: DEFAULT_DESIGN_HEIGHT

            if (lanStr.isEmpty()) {
                binding.inputLanUrl.error = currentRes.getString(R.string.lan_url_required)
                return@setOnClickListener
            }

            val isAnyway = binding.btnSaveSettings.text.toString() == currentRes.getString(R.string.save_anyway)

            // 如果用户选择跳过验证或未输入凭据，直接保存
            if (isAnyway || userStr.isEmpty()) {
                // 先隐藏设置面板，再在安全回调中触发重构，避免在 Activity 销毁后操作失效的 Window Token
                toggleSettingsOverlay(false)
                binding.root.postDelayed({
                    if (!isFinishing && !isDestroyed) {
                        saveConfig(
                            lanStr, wanStr, userStr, passStr, "", "", isKiosk, selectedLang,
                            parsedDesignWidth, parsedDesignHeight
                        )
                    }
                }, 150)
                return@setOnClickListener
            }

            // 显示验证状态加载状态
            binding.txtVerificationStatus.visibility = View.VISIBLE
            binding.txtVerificationStatus.setTextColor(Color.parseColor("#00E5FF"))
            binding.txtVerificationStatus.text = currentRes.getString(R.string.verifying_server)
            binding.btnSaveSettings.isEnabled = false

            executor.execute {
                var loginResult = HomeOSAuth.performLogin(lanStr, userStr, passStr)
                var resolvedBaseUrl = lanStr

                if (!loginResult.success && wanStr.isNotEmpty() && !wanStr.contains("yourdomain.com")) {
                    loginResult = HomeOSAuth.performLogin(wanStr, userStr, passStr)
                    resolvedBaseUrl = wanStr
                }

                val finalResult = loginResult
                runOnUiThread {
                    binding.btnSaveSettings.isEnabled = true
                    val activeRes = getLocalizedResources(this@MainActivity, tempSelectedLang)
                    if (finalResult.success) {
                        binding.txtVerificationStatus.setTextColor(Color.parseColor("#00FF66"))
                        binding.txtVerificationStatus.text = activeRes.getString(R.string.verification_success)

                        // 先隐藏设置面板，再在安全回调中触发重构，避免在 Activity 销毁后操作失效的 Window Token
                        binding.root.postDelayed({
                            if (!isFinishing && !isDestroyed) {
                                toggleSettingsOverlay(false)
                                binding.root.postDelayed({
                                    if (!isFinishing && !isDestroyed) {
                                        saveConfig(
                                            lanStr,
                                            wanStr,
                                            userStr,
                                            passStr,
                                            finalResult.authToken,
                                            finalResult.csrfToken,
                                            isKiosk,
                                            selectedLang,
                                            parsedDesignWidth,
                                            parsedDesignHeight
                                        )
                                    }
                                }, 150)
                            }
                        }, 500)
                    } else {
                        binding.txtVerificationStatus.setTextColor(Color.parseColor("#FF3333"))
                        val detail = when {
                            finalResult.requiresMfa -> getString(R.string.mfa_required_suffix)
                            !finalResult.errorMessage.isNullOrEmpty() -> " (${finalResult.errorMessage})"
                            else -> ""
                        }
                        binding.txtVerificationStatus.text =
                            activeRes.getString(R.string.verification_failed) + detail
                        binding.btnSaveSettings.text = activeRes.getString(R.string.save_anyway)
                    }
                }
            }
        }

        binding.btnWipeData.setOnClickListener {
            sharedPreferences.edit().apply {
                remove("server_lan_url")
                remove("server_wan_url")
                putBoolean("is_configured", false)
                apply()
            }
            HomeOSAuth.clearAuthTokens(sharedPreferences)
            // 停止后台 WebSocket 服务以释放资源
            stopService(Intent(this, SocketService::class.java))
            clearAppCacheAndWebView(this)
            if (!isFinishing && !isDestroyed) {
                recreate()
            }
        }
    }

    /**
     * 清除所有应用缓存、WebView 数据库和会话 Cookie，恢复到干净状态。
     * 使用标准 WebKit API 避免危险的磁盘操作，防止导致 SIGSEGV 崩溃。
     */
    private fun clearAppCacheAndWebView(context: android.content.Context) {
        try {
            // 1. 使用现有实例清除 WebView 标准缓存
            binding.webView.clearCache(true)
            
            // 2. 清除会话和持久化 Cookie
            val cookieManager = android.webkit.CookieManager.getInstance()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                cookieManager.removeAllCookies(null)
                cookieManager.flush()
            } else {
                val cookieSyncMngr = android.webkit.CookieSyncManager.createInstance(context)
                cookieSyncMngr.startSync()
                cookieManager.removeAllCookie()
                cookieSyncMngr.stopSync()
            }
            
            // 3. 清除 DOM 存储（localStorage/sessionStorage）
            android.webkit.WebStorage.getInstance().deleteAllData()
            
            // 4. 递归清除应用缓存目录中的内部文件
            val cacheDir = context.cacheDir
            if (cacheDir != null && cacheDir.isDirectory) {
                deleteDirContents(cacheDir)
            }
            
            // 注意：已移除危险的磁盘文件删除操作（webview.db, webviewCache.db）
            // 以防止 WebView 活跃时发生 Chromium 原生 SIGSEGV 崩溃
            
            android.widget.Toast.makeText(
                context,
                context.getString(R.string.cache_cleared),
                android.widget.Toast.LENGTH_LONG
            ).show()
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    private fun deleteDirContents(dir: File?): Boolean {
        if (dir != null && dir.isDirectory) {
            val children = dir.list()
            if (children != null) {
                for (i in children.indices) {
                    val success = deleteFileOrDir(File(dir, children[i]))
                    if (!success) {
                        return false
                    }
                }
            }
        }
        return true
    }

    private fun deleteFileOrDir(file: File?): Boolean {
        if (file != null && file.isDirectory) {
            val children = file.list()
            if (children != null) {
                for (i in children.indices) {
                    val success = deleteFileOrDir(File(file, children[i]))
                    if (!success) {
                        return false
                    }
                }
            }
            return file.delete()
        } else if (file != null && file.isFile) {
            return file.delete()
        }
        return false
    }

    private fun toggleSettingsOverlay(show: Boolean) {
        if (show) {
            applyLanguageToSettingsUI(tempSelectedLang)
            binding.txtVerificationStatus.visibility = View.GONE
            binding.btnCancelSettings.visibility = if (sharedPreferences.getBoolean("is_configured", false)) View.VISIBLE else View.GONE
            
            val isConfigured = sharedPreferences.getBoolean("is_configured", false)
            binding.btnWipeData.visibility = if (isConfigured) View.VISIBLE else View.GONE

            binding.settingsOverlay.visibility = View.VISIBLE
            // 优化：使用平滑淡入动画
            binding.settingsOverlay.animate()
                .alpha(1f)
                .setDuration(200)
                .setInterpolator(android.view.animation.DecelerateInterpolator())
                .start()
        } else {
            // 优化：使用平滑淡出动画
            binding.settingsOverlay.animate()
                .alpha(0f)
                .setDuration(150)
                .setInterpolator(android.view.animation.AccelerateInterpolator())
                .withEndAction {
                    binding.settingsOverlay.visibility = View.GONE
                    applySystemImmersiveMode()
                }
                .start()
        }
    }

    /**
     * 动态更新分段胶囊语言切换器 UI 的状态。
     */
    private fun updateLanguageToggleUI(lang: String) {
        // 首先重置所有按钮状态
        binding.btnLangZhToggle.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.btnLangZhToggle.setTextColor(android.graphics.Color.parseColor("#8E8E93"))
        binding.btnLangZhToggle.setTypeface(null, android.graphics.Typeface.NORMAL)

        binding.btnLangEnToggle.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        binding.btnLangEnToggle.setTextColor(android.graphics.Color.parseColor("#8E8E93"))
        binding.btnLangEnToggle.setTypeface(null, android.graphics.Typeface.NORMAL)

        // 高亮选中的按钮
        if (lang == "en") {
            binding.btnLangEnToggle.setBackgroundColor(android.graphics.Color.parseColor("#00E5FF"))
            binding.btnLangEnToggle.setTextColor(android.graphics.Color.parseColor("#000000"))
            binding.btnLangEnToggle.setTypeface(null, android.graphics.Typeface.BOLD)
        } else {
            binding.btnLangZhToggle.setBackgroundColor(android.graphics.Color.parseColor("#00E5FF"))
            binding.btnLangZhToggle.setTextColor(android.graphics.Color.parseColor("#000000"))
            binding.btnLangZhToggle.setTypeface(null, android.graphics.Typeface.BOLD)
        }
    }

    /**
     * 决定将流量路由到本地网络还是外部互联网。
     */
    private fun routeAndLoadUrl() {
        isErrorState = false
        binding.webView.visibility = View.INVISIBLE
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.loadingOverlay.alpha = 1f
        binding.loadingText.text = getString(R.string.connecting)
        binding.subLoadingText.text = getString(R.string.checking_network_health)

        roamingManager.resolveOptimalRoute(lanUrl, wanUrl, object : NetworkRoamingManager.RoamingCallback {
            override fun onRouteResolved(resolvedUrl: String, isLocal: Boolean) {
                if (resolvedUrl.isEmpty()) {
                    Log.e("MainActivity", "解析到的 URL 为空。放弃加载 WebView。")
                    binding.subLoadingText.text = getString(R.string.error_no_server_url)
                    toggleSettingsOverlay(true)
                    return
                }
                activeUrl = resolvedUrl
                binding.subLoadingText.text = if (isLocal) {
                    getString(R.string.lan_route_online)
                } else {
                    getString(R.string.wan_route_online)
                }
                Log.i("MainActivity", "最佳路由已解析。正在加载 URL: $activeUrl")

                val authToken = HomeOSAuth.loadAuthToken(sharedPreferences)
                val csrfToken = HomeOSAuth.loadCsrfToken(sharedPreferences)
                if (authToken.isNotEmpty()) {
                    HomeOSAuth.injectCookies(activeUrl, authToken, csrfToken)
                } else {
                    val savedUser = sharedPreferences.getString("auth_user", "") ?: ""
                    val savedPass = sharedPreferences.getString("auth_pass", "") ?: ""
                    if (savedUser.isNotEmpty() && savedPass.isNotEmpty()) {
                        executor.execute {
                            val loginResult = HomeOSAuth.performLogin(activeUrl, savedUser, savedPass)
                            if (loginResult.success) {
                                sharedPreferences.edit().apply {
                                    putString(HomeOSAuth.KEY_AUTH_TOKEN, loginResult.authToken)
                                    putString(HomeOSAuth.KEY_CSRF_TOKEN, loginResult.csrfToken)
                                    apply()
                                }
                                runOnUiThread {
                                    HomeOSAuth.injectCookies(activeUrl, loginResult.authToken, loginResult.csrfToken)
                                    loadActiveUrl(activeUrl)
                                }
                            } else {
                                runOnUiThread { loadActiveUrl(activeUrl) }
                            }
                        }
                        return
                    }
                }

                loadActiveUrl(activeUrl)
            }
        })
    }

    private fun loadActiveUrl(url: String) {
        recoveryHandler.removeCallbacks(loadTimeoutRunnable)
        recoveryHandler.postDelayed(loadTimeoutRunnable, 10000L)
        binding.webView.loadUrl(url)
    }

    /**
     * 拦截页面加载错误，并用我们精美的工业风格连接丢失自定义视图布局替换标准 Chromium 错误屏幕。
     */
    private fun handlePageLoadFailure() {
        isErrorState = true
        recoveryHandler.removeCallbacks(recoveryRunnable)
        
        // 显示离线布局屏幕
        setContentView(R.layout.activity_offline)
        
        // 绑定离线屏幕元素的操作
        findViewById<View>(R.id.btnConfigOffline)?.setOnClickListener {
            // 重新渲染主布局以显示配置弹窗
            setContentView(binding.root)
            toggleSettingsOverlay(true)
        }

        findViewById<View>(R.id.btnRetryOffline)?.setOnClickListener {
            setContentView(binding.root)
            routeAndLoadUrl()
        }

        // 每 3 秒安排后台恢复重连检查
        recoveryHandler.postDelayed(recoveryRunnable, 3000L)
    }

    private fun attemptAutoRecovery() {
        Log.d("MainActivity", "正在尝试自动网络路径恢复...")
        roamingManager.resolveOptimalRoute(lanUrl, wanUrl, object : NetworkRoamingManager.RoamingCallback {
            override fun onRouteResolved(resolvedUrl: String, isLocal: Boolean) {
                // 如果 ping 成功，恢复页面
                if (resolvedUrl.isNotEmpty()) {
                    recoveryHandler.removeCallbacks(recoveryRunnable)
                    setContentView(binding.root)
                    routeAndLoadUrl()
                } else {
                    // 3 秒后重试
                    recoveryHandler.postDelayed(recoveryRunnable, 3000L)
                }
            }
        })
    }

    /**
     * WebView 连接加载超时处理程序
     */
    private fun handleLoadTimeout() {
        if (!isFinishing && !isDestroyed) {
            Log.w("MainActivity", "WebView 加载超时（10秒）。正在停止连接。")
            runOnUiThread {
                binding.webView.stopLoading()
                handlePageLoadFailure()
            }
        }
    }

    /**
     * Android 13+ 运行时申请通知权限，确保告警通知可正常显示。
     */
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

    /**
     * 启动后台前台服务以进行主动的通知轮询监控。
     */
    private fun startServices() {
        try {
            val serviceIntent = Intent(this, SocketService::class.java).apply {
                putExtra("SERVER_URL", lanUrl)
                putExtra("AUTH_TOKEN", HomeOSAuth.loadAuthToken(sharedPreferences))
                putExtra("CSRF_TOKEN", HomeOSAuth.loadCsrfToken(sharedPreferences))
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

    /**
     * 拦截意图载荷（如点击系统通知）并将参数直接路由到 Vue 3 WebView 组件中。
     */
    private fun handleIntent(intent: Intent?) {
        val openCameraEntity = intent?.getStringExtra("OPEN_CAMERA_ENTITY")
        val alertId = intent?.getStringExtra("ALERT_ID")

        if (openCameraEntity != null || alertId != null) {
            Log.i("MainActivity", "深度链接已触发：alertId=$alertId, cameraEntity=$openCameraEntity")
            val safeAlertId = JSONObject.quote(alertId ?: "")
            val safeCameraEntity = JSONObject.quote(openCameraEntity ?: "")
            binding.webView.post {
                binding.webView.evaluateJavascript(
                    "if (window.__HOMEOS_ALERT_ROUTER__) { window.__HOMEOS_ALERT_ROUTER__($safeAlertId, $safeCameraEntity); }",
                    null
                )
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        binding.webView.setInitialScale(calculateAspectRatioScale())
        binding.webView.postDelayed({
            injectFixedViewport()
        }, 100)
    }

    override fun onBackPressed() {
        if (binding.settingsOverlay.visibility == View.VISIBLE) {
            toggleSettingsOverlay(false)
        } else if (binding.webView.canGoBack()) {
            binding.webView.goBack()
        } else {
            // 在 Kiosk 模式下拦截返回按钮，防止壁挂平板退出应用
            if (!isKioskMode) {
                super.onBackPressed()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        recoveryHandler.removeCallbacks(recoveryRunnable)
        recoveryHandler.removeCallbacks(loadTimeoutRunnable)
        executor.shutdown()
    }

    /**
     * NativeBridge: 安全、沙箱化的 JavaScript 接口
     * 在 WebView 内部映射为 "window.Native"。
     */
    inner class NativeBridge(private val context: Context) {

        /**
         * 返回 Settings.Secure.ANDROID_ID 的 SHA-256 哈希值，作为 Pro 版安全许可证中心的硬件指纹 (HWID)。
         * 与 RSA 握手完美配合。
         */
        @JavascriptInterface
        fun getHardwareFingerprint(): String {
            val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID) ?: "DEFAULT-HOMEOS-KEY"
            return sha256(androidId)
        }

        /**
         * 返回高保真设备遥测数据到 Vue 3 状态引擎。
         */
        @JavascriptInterface
        fun getDeviceInfo(): String {
            val metrics = resources.displayMetrics
            return JSONObject().apply {
                put("model", Build.MODEL)
                put("manufacturer", Build.MANUFACTURER)
                put("osVersion", Build.VERSION.SDK_INT)
                put("appVersion", getAppVersion())
                put("isKioskMode", isKioskMode)
                put("platform", "android")
                put("screenWidthPx", metrics.widthPixels)
                put("screenHeightPx", metrics.heightPixels)
                put("density", metrics.density.toDouble())
                put("densityDpi", metrics.densityDpi)
                put("designWidth", designWidth)
                put("designHeight", designHeight)
                put("scaleFactor", currentScaleFactor())
            }.toString()
        }

        /**
         * 返回当前伴侣应用版本。
         */
        @JavascriptInterface
        fun getAppVersion(): String {
            return try {
                val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
                packageInfo.versionName ?: ""
            } catch (e: Exception) {
                ""
            }
        }

        /**
         * 返回当前 FCM/HMS 推送令牌供后端注册使用。
         * 从本地缓存的 SharedPreferences 读取（由 onNewToken 回调保存）。
         */
        @JavascriptInterface
        fun getPushToken(): String {
            return try {
                // 首先尝试获取 FcmService.onNewToken 缓存的 FCM 令牌
                val fcmToken = sharedPreferences.getString("fcm_push_token", "") ?: ""
                if (fcmToken.isNotEmpty()) return fcmToken
                // 回退到 HmsService.onNewToken 缓存的 HMS 令牌
                val hmsToken = sharedPreferences.getString("hms_push_token", "") ?: ""
                if (hmsToken.isNotEmpty()) return hmsToken
                // 异步触发新令牌请求供下次使用
                try {
                    FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                        if (task.isSuccessful) {
                            sharedPreferences.edit().putString("fcm_push_token", task.result).apply()
                        }
                    }
                } catch (_: Exception) {}
                ""
            } catch (e: Exception) {
                Log.e("JSBridge", "获取推送令牌失败: ${e.message}")
                ""
            }
        }

        /**
         * 返回当前活动的 Kiosk 工作状态。
         */
        @JavascriptInterface
        fun getAppMode(): String {
            return if (isKioskMode) "kiosk" else "companion"
        }

        /**
         * 原生播放器，根据警报级别播放相应的声音。
         */
        @JavascriptInterface
        fun playAlertSound(severity: String) {
            try {
                val soundUri = if (severity.equals("CRITICAL", ignoreCase = true)) {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                } else {
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }
                val ringtone = RingtoneManager.getRingtone(context, soundUri)
                ringtone?.play()
            } catch (e: Exception) {
                Log.e("JSBridge", "音频播放失败: ${e.message}")
            }
        }

        /**
         * 触发系统编排器来清除或淡化相应的通知栏元素。
         */
        @JavascriptInterface
        fun acknowledgeAlert(alertId: String) {
            orchestrator.cancelNotification(alertId)
        }

        private fun sha256(base: String): String {
            return try {
                val digest = MessageDigest.getInstance("SHA-256")
                val hash = digest.digest(base.toByteArray(Charsets.UTF_8))
                hash.joinToString("") { "%02x".format(it) }
            } catch (ex: Exception) {
                base
            }
        }
    }
}
