package com.homeos.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
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
    private val onRenderProcessGone: () -> Unit,
    private val urlRedirectHandler: (String) -> Boolean,
    private val fileChooserLauncher: (Intent) -> Unit
) {
    private var uploadMessage: android.webkit.ValueCallback<Array<Uri>>? = null
    private var nativeBridge: NativeBridge? = null
    private var isDestroying = false

    val webView: WebView get() = binding.webView

    @SuppressLint("SetJavaScriptEnabled")
    fun setup() {
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
            // 关键性能修复：尊重页面自带的 width=device-width 视口。
            // 前端（ScaledViewport/useScaling）已基于 1366 设计基准做整页等比缩放，
            // 这里再强制 width=物理分辨率 会让 devicePixelRatio≈1、按 ~6.8M CSS 像素布局，
            // 撑爆 WebView 瓦片显存（鸿蒙 ArkWeb 尤甚）→ 渲染进程 OOM/反复重建 → 严重卡顿。
            // useWideViewPort=true 使页面 device-width 视口生效，dpr 回归真实密度（清晰且省显存）。
            useWideViewPort = true
            loadWithOverviewMode = true
            textZoom = 100
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = false
            }
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        // setInitialScale(0)：按页面 device-width 视口自动计算初始缩放。
        binding.webView.setInitialScale(0)
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
                    fileChooserLauncher(intent)
                    true
                } catch (_: Exception) {
                    uploadMessage?.onReceiveValue(null)
                    uploadMessage = null
                    false
                }
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectInputFocusStabilizer(view)
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

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                // 内网 HTTPS 多为自签名证书；仅对私有地址放行，公网证书错误仍按默认拦截。
                val host = error?.url?.let {
                    runCatching { Uri.parse(it).host }.getOrNull()
                }
                if (host != null && HttpClientProvider.isPrivateHostName(host)) {
                    handler?.proceed()
                } else {
                    handler?.cancel()
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

            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                // 渲染进程崩溃（常见于大画布 OOM）。返回 true 接管处理，
                // 避免整个 App 进程被系统杀死，并通知宿主重建以自愈。
                val didCrash = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                    detail?.didCrash() == true
                android.util.Log.e(
                    "WebViewController",
                    "WebView 渲染进程终止（crash=$didCrash），正在触发自愈重建。"
                )
                if (!isDestroying) {
                    onRenderProcessGone()
                }
                return true
            }
        }
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
        isDestroying = true
        binding.webView.apply {
            stopLoading()
            removeJavascriptInterface("Native")
            webChromeClient = null
            webViewClient = WebViewClient()
            destroy()
        }
        nativeBridge = null
    }

    /**
     * 阻止 WebView/Chromium 在 input/select 获焦时自动滚动页面，
     * 避免 Pad 上点击输入框或下拉菜单后页面偏移且收起后无法复原。
     */
    private fun injectInputFocusStabilizer(view: WebView?) {
        if (view == null) return
        val script = """
            (function() {
              if (window.__homeosFocusStabilizer) return;
              window.__homeosFocusStabilizer = true;
              var saved = null;
              var formFocused = false;
              var selector = 'input,textarea,select,[contenteditable="true"],[contenteditable=true]';
              function capture() {
                saved = { x: window.scrollX || 0, y: window.scrollY || 0 };
              }
              function restore() {
                if (!saved) return;
                window.scrollTo(saved.x, saved.y);
              }
              function isFormControl(el) {
                if (!el || el.nodeType !== 1) return false;
                if (el.matches && el.matches(selector)) return true;
                var role = el.getAttribute && el.getAttribute('role');
                return role === 'combobox' || role === 'listbox' || role === 'textbox';
              }
              document.addEventListener('focusin', function(e) {
                if (!isFormControl(e.target)) return;
                if (!formFocused) capture();
                formFocused = true;
                requestAnimationFrame(restore);
                setTimeout(restore, 50);
                setTimeout(restore, 150);
              }, true);
              document.addEventListener('focusout', function() {
                setTimeout(function() {
                  var active = document.activeElement;
                  if (isFormControl(active)) return;
                  formFocused = false;
                  restore();
                  saved = null;
                }, 120);
              }, true);
              if (window.visualViewport) {
                window.visualViewport.addEventListener('resize', function() {
                  if (formFocused) requestAnimationFrame(restore);
                });
              }
            })();
        """.trimIndent()
        view.evaluateJavascript(script, null)
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
}
