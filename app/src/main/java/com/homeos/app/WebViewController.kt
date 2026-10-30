package com.homeos.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.webkit.ConsoleMessage
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
    private val mainHandler = android.os.Handler(android.os.Looper.getMainLooper())

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

        // 可调试包打开远程调试：真机 3D 空白时可用 chrome://inspect 直接看页面 console /
        // 网络请求 / WebGL 上下文，不必再靠猜。Release 包不受影响。
        if ((activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
            WebView.setWebContentsDebuggingEnabled(true)
        }

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

            /**
             * 页面 console 落到 logcat（tag: HomeOSWebView）。
             * 3D 舞台内部的前端报错都走 console.error，真机上看不到日志时这条是唯一线索。
             */
            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                consoleMessage ?: return false
                val detail = "${consoleMessage.message()} " +
                    "(${consoleMessage.sourceId()}:${consoleMessage.lineNumber()})"
                when (consoleMessage.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR ->
                        android.util.Log.e("HomeOSWebView", detail)
                    ConsoleMessage.MessageLevel.WARNING ->
                        android.util.Log.w("HomeOSWebView", detail)
                    else -> android.util.Log.i("HomeOSWebView", detail)
                }
                return true
            }
        }

        binding.webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                injectInputFocusStabilizer(view)
                onPageReady()
                scheduleRenderDiagnostics(view)
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
        mainHandler.removeCallbacksAndMessages(null)
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

    /**
     * 页面就绪后做一次 3D 渲染自检（logcat tag: HomeOS3D）。
     *
     * Android WebView 里「3D 区域整块空白、周边 UI 正常」有几种完全不同的成因：
     * 1) 设备/WebView 没有 WebGL2 → 前端 three.js 建不出 renderer；
     * 2) 3D 宿主停在「授权校验中 / 连接中断」→ 前端只渲染一个空占位层（无任何文字）；
     * 3) 舞台 iframe 里的 canvas 尺寸为 0 或被裁掉 → 画了也看不见。
     * 这里把这三类证据一次性打出来，真机日志即可定位，不用再靠猜。
     */
    private fun scheduleRenderDiagnostics(view: WebView?) {
        if (view == null) return
        mainHandler.postDelayed({
            if (isDestroying) return@postDelayed
            runRenderDiagnostics(showToast = false)
        }, 4000L)
    }

    /**
     * 主动跑一次 3D 自检并把结论弹成 Toast。
     *
     * release 包无法用 `chrome://inspect` 调试，真机现场也常常拿不到 adb；入口挂在
     * 三指三连击打开的设置面板上，用户点开就能用肉眼把结论读出来（截图即可反馈）。
     */
    fun runRenderDiagnostics(showToast: Boolean = true) {
        if (isDestroying) return
        val script = """
            (function () {
              function probeGl(name) {
                try {
                  var canvas = document.createElement('canvas');
                  var gl = canvas.getContext(name);
                  if (!gl) return false;
                  var lose = gl.getExtension('WEBGL_lose_context');
                  lose && lose.loseContext();
                  return true;
                } catch (error) { return false; }
              }
              var hosts = [];
              var nodes = document.querySelectorAll('.hb-interaction3d-host');
              for (var i = 0; i < nodes.length; i++) {
                var host = nodes[i];
                var rect = host.getBoundingClientRect();
                var canvas = host.querySelector('canvas');
                var runtime = host.querySelector('.hb-interaction3d-runtime');
                hosts.push({
                  access: host.dataset ? (host.dataset.access || '') : '',
                  styleState: host.dataset ? (host.dataset.styleState || '') : '',
                  runtime: runtime ? runtime.className : '',
                  canvas: canvas ? (canvas.width + 'x' + canvas.height) : '',
                  box: Math.round(rect.width) + 'x' + Math.round(rect.height),
                  text: (host.textContent || '').slice(0, 60)
                });
              }
              return JSON.stringify({
                visibility: document.visibilityState,
                webgl2: probeGl('webgl2'),
                webgl1: probeGl('webgl'),
                dpr: window.devicePixelRatio,
                viewport: window.innerWidth + 'x' + window.innerHeight,
                iframes: document.querySelectorAll('iframe').length,
                hosts: hosts
              });
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script) { result ->
            if (isDestroying) return@evaluateJavascript
            val raw = unquoteJsString(result ?: "")
            android.util.Log.i("HomeOS3D", "渲染自检 webView=${webViewVersionLabel()} $raw")
            if (!showToast) return@evaluateJavascript
            val verdict = describeDiagnostics(raw)
            presentDiagnostics(verdict)
        }
    }

    /**
     * 当前 WebView 实现包与版本号。
     *
     * App 把 UA 伪装成桌面 Chrome，页面里 `navigator.userAgent` 完全看不出真实内核版本；
     * 而「系统 WebView 太旧、只支持 WebGL1」正是浏览器正常、App 空白的最常见原因。
     * 这里直接取系统组件版本，作为唯一可信的判据。
     */
    private fun webViewVersionLabel(): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return "WebView(<8.0)"
        val pkg = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        return if (pkg == null) "WebView(未知)" else "${pkg.packageName}@${pkg.versionName}"
    }

    /**
     * 自检结论用对话框展示。
     *
     * Android 12 起 Toast 最多两行，长文案会被截断，现场排障必须能完整读到（并截图）。
     */
    private fun presentDiagnostics(verdict: String) {
        runCatching {
            android.app.AlertDialog.Builder(activity)
                .setTitle("3D 自检")
                .setMessage("$verdict\n\nWebView：${webViewVersionLabel()}")
                .setPositiveButton("知道了", null)
                .show()
        }.onFailure {
            android.widget.Toast.makeText(activity, verdict, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 把自检 JSON 翻译成一句人能照做的话。
     *
     * 判据顺序按「能不能靠用户自己解决」排：WebGL2 缺失和授权未通过是两类完全不同的处置，
     * 先报前者；再看宿主有没有画布；最后才报尺寸类问题。
     */
    private fun describeDiagnostics(rawResult: String?): String {
        val json = runCatching { org.json.JSONObject(rawResult ?: "") }.getOrNull()
            ?: return "3D 自检失败：拿不到页面状态。请把日志（HomeOS3D）发给开发者。"
        val webgl2 = json.optBoolean("webgl2", false)
        val webgl1 = json.optBoolean("webgl1", false)
        val viewport = json.optString("viewport", "?")
        val dpr = json.optDouble("dpr", 1.0)
        val iframes = json.optInt("iframes", -1)
        val hosts = json.optJSONArray("hosts")
        val hostCount = hosts?.length() ?: 0

        if (hostCount == 0) {
            return "3D 自检：页面里没有 3D 宿主（iframe=$iframes，视口=$viewport）。" +
                "可能是仪表盘本身没有 3D 组件，或前端没加载完。"
        }
        val first = hosts!!.optJSONObject(0)
        val canvas = first?.optString("canvas", "") ?: ""
        val box = first?.optString("box", "") ?: ""
        val access = first?.optString("access", "") ?: ""
        val runtime = first?.optString("runtime", "") ?: ""

        if (!webgl2) {
            return if (webgl1) {
                "根因：当前 WebView 只支持 WebGL1，没有 WebGL2。" +
                    "three.js 0.186 已弃用 WebGL1，所以 3D 一定空白。" +
                    "手机浏览器用的是自带内核，所以那边正常。" +
                    "请到应用商店更新「Android System WebView」或改用 Chrome 内核。"
            } else {
                "根因：当前 WebView 完全拿不到 WebGL 上下文。请检查系统「Android System WebView」是否需要更新。"
            }
        }
        if (access != "allowed") {
            return "3D 宿主授权状态 = ${access.ifEmpty { "空" }}（应为 allowed）。" +
                "这是授权接口 / 登录态问题，不是 GPU 问题。"
        }
        if (canvas.isEmpty() || canvas.startsWith("0x")) {
            return "3D 画布尺寸异常（canvas=$canvas，盒子=$box，runtime=$runtime）。" +
                "WebGL 可用、授权通过，但画布没被撑开 —— 属于布局/尺寸问题。"
        }
        return "3D 自检未发现异常（canvas=$canvas，盒子=$box，视口=$viewport，dpr=$dpr）。" +
            "若画面仍空白，请截图这条信息。"
    }

    /**
     * `evaluateJavascript` 回传的是 JSON 字符串字面量（带引号与转义），先还原成原始文本。
     */
    private fun unquoteJsString(raw: String): String {
        val trimmed = raw.trim()
        return runCatching {
            org.json.JSONTokener(trimmed).nextValue() as? String ?: trimmed
        }.getOrDefault(trimmed)
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
