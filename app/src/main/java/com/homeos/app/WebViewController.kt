package com.homeos.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import com.homeos.app.databinding.ActivityMainBinding

/**
 * WebView 初始化、生命周期、viewport 注入与缩放管理。
 */
class WebViewController(
    private val activity: Activity,
    private val binding: ActivityMainBinding,
    private val host: NativeBridgeHost,
    private val onDownload: (url: String, mimeType: String?, contentDisposition: String?) -> Unit,
    private val onMainFrameError: () -> Unit,
    private val onPageReady: () -> Unit,
    private val urlRedirectHandler: (String) -> Boolean,
    private val fileChooserLauncher: (Intent, Int) -> Unit,
    private val fileChooserRequestCode: Int
) {
    private var uploadMessage: android.webkit.ValueCallback<Array<Uri>>? = null
    private var nativeBridge: NativeBridge? = null
    private var designWidth = DEFAULT_DESIGN_WIDTH
    private var designHeight = DEFAULT_DESIGN_HEIGHT

    val webView: WebView get() = binding.webView

    @SuppressLint("SetJavaScriptEnabled")
    fun setup(designWidth: Int, designHeight: Int) {
        this.designWidth = designWidth
        this.designHeight = designHeight

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

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && shouldEnableOffscreenPreRaster()) {
                setOffscreenPreRaster(true)
            }
        }

        binding.webView.setInitialScale(100)
        binding.webView.isFocusable = true
        binding.webView.isFocusableInTouchMode = true

        nativeBridge = NativeBridge(activity, host)
        binding.webView.addJavascriptInterface(nativeBridge!!, "Native")

        binding.webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            onDownload(url, mimetype, contentDisposition)
        }

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
                return try {
                    fileChooserLauncher(intent, fileChooserRequestCode)
                    true
                } catch (_: Exception) {
                    uploadMessage?.onReceiveValue(null)
                    uploadMessage = null
                    false
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                resetViewportInjection()
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectViewportAndScale()
                onPageReady()
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    onMainFrameError()
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return urlRedirectHandler(url)
            }

            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return urlRedirectHandler(url)
            }
        }
    }

    fun updateDesignDimensions(designWidth: Int, designHeight: Int) {
        this.designWidth = designWidth
        this.designHeight = designHeight
        applyDesignScale()
    }

    fun applyDesignScale() {
        injectViewportAndScale()
    }

    fun onConfigurationChanged() {
        binding.webView.postDelayed({
            val sw = screenWidthPx()
            binding.webView.evaluateJavascript(
                ViewportInjector.buildScaleUpdateScript(designWidth, designHeight, sw),
                null
            )
        }, 100)
    }

    private fun screenWidthPx(): Int = activity.resources.displayMetrics.widthPixels

    private fun resetViewportInjection() {
        binding.webView.evaluateJavascript(ViewportInjector.buildResetScript(), null)
    }

    private fun injectViewportAndScale() {
        val sw = screenWidthPx()
        binding.webView.evaluateJavascript(
            ViewportInjector.buildInstallScript(designWidth, designHeight, sw),
            null
        )
    }

    fun pause() {
        binding.webView.onPause()
        binding.webView.pauseTimers()
    }

    fun resume() {
        binding.webView.onResume()
        binding.webView.resumeTimers()
    }

    fun destroy() {
        binding.webView.apply {
            stopLoading()
            removeJavascriptInterface("Native")
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        nativeBridge = null
    }

    fun handleFileChooserResult(resultCode: Int, data: Intent?) {
        if (uploadMessage == null) return
        val result = if (data == null || resultCode != Activity.RESULT_OK) null else data.data
        if (result != null) {
            uploadMessage?.onReceiveValue(arrayOf(result))
        } else {
            uploadMessage?.onReceiveValue(null)
        }
        uploadMessage = null
    }

    fun setHardwareLayerEnabled(enabled: Boolean) {
        binding.webView.setLayerType(
            if (enabled) View.LAYER_TYPE_HARDWARE else View.LAYER_TYPE_NONE,
            null
        )
    }

    private fun shouldEnableOffscreenPreRaster(): Boolean {
        val metrics = activity.resources.displayMetrics
        val activityManager = activity.getSystemService(Activity.ACTIVITY_SERVICE) as android.app.ActivityManager
        val memoryClassMb = activityManager.memoryClass
        return memoryClassMb >= 256 && metrics.widthPixels >= 2000
    }

    companion object {
        private const val DEFAULT_DESIGN_WIDTH = 3200
        private const val DEFAULT_DESIGN_HEIGHT = 2136
    }
}
