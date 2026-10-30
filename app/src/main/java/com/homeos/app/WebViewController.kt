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
    /**
     * 最近若干条 console 异常（error / warning）。
     *
     * 舞台 iframe 内部的前端异常只会走 console.error，release 包既不能用
     * `chrome://inspect` 也拿不到 logcat；把它挂在自检结论后面，用户截图一次就能带全证据。
     */
    private val recentConsoleErrors = ArrayDeque<String>()
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
                val level = consoleMessage.messageLevel()
                // 只留异常类消息：正常日志会把证据挤出缓冲。
                if (level == ConsoleMessage.MessageLevel.ERROR ||
                    level == ConsoleMessage.MessageLevel.WARNING
                ) {
                    recentConsoleErrors.addLast(detail.take(220))
                    while (recentConsoleErrors.size > CONSOLE_BUFFER_SIZE) recentConsoleErrors.removeFirst()
                }
                when (level) {
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
        // 两次采样：8s 可能还没挂载舞台（展示页要等数据），第一次没看到宿主就不报，
        // 交给 20s 那次下结论。
        mainHandler.postDelayed({
            if (isDestroying) return@postDelayed
            runRenderDiagnostics(showToast = true, onlyOnProblem = true, silentWhenNoHost = true)
        }, 8000L)
        mainHandler.postDelayed({
            if (isDestroying) return@postDelayed
            runRenderDiagnostics(showToast = true, onlyOnProblem = true)
        }, 20000L)
    }

    /**
     * 主动跑一次 3D 自检并把结论弹成对话框。
     *
     * release 包无法用 `chrome://inspect` 调试，真机现场也常常拿不到 adb；两处入口：
     * 页面就绪 8 秒后自动跑一次（仅在有异常时弹），以及三指三连击打开的设置面板。
     * 结论必须覆盖到**舞台 iframe 内部** —— canvas 在 iframe 里，只看主框架永远查不到。
     */
    fun runRenderDiagnostics(
        showToast: Boolean = true,
        onlyOnProblem: Boolean = false,
        silentWhenNoHost: Boolean = false,
    ) {
        if (isDestroying) return
        val script = """
            (function () {
              function probeGl(win) {
                try {
                  var canvas = win.document.createElement('canvas');
                  var gl = canvas.getContext('webgl2');
                  if (!gl) return false;
                  var lose = gl.getExtension('WEBGL_lose_context');
                  lose && lose.loseContext();
                  return true;
                } catch (error) { return false; }
              }
              // 舞台 iframe 与宿主同源，可以直接读它内部的挂载状态与启动期错误。
              function readFrame(frame) {
                var out = { origin: 'cross-origin' };
                var doc = null;
                try { doc = frame.contentDocument; } catch (error) { doc = null; }
                if (!doc) return out;
                out.origin = 'same';
                var win = frame.contentWindow;
                var root = doc.documentElement;
                var preview = doc.querySelector('#preview-3d');
                var messageElement = doc.querySelector('#webgl-message');
                var canvas = doc.querySelector('#preview-3d canvas') || doc.querySelector('canvas');
                out.ready = root && root.dataset ? (root.dataset.homeosReady || '') : '';
                out.mounted = root && root.dataset ? (root.dataset.homeosMounted || '') : '';
                out.bodyClass = doc.body ? (doc.body.className || '').slice(0, 40) : '';
                out.appKids = doc.querySelector('#app') ? doc.querySelector('#app').children.length : -1;
                out.hasPreview = preview ? 1 : 0;
                out.previewKids = preview ? preview.children.length : -1;
                out.shell = doc.querySelector('.studio-shell') ? 1 : 0;
                out.canvases = doc.querySelectorAll('canvas').length;
                out.canvas = canvas ? (canvas.width + 'x' + canvas.height) : '';
                out.canvasCss = canvas
                  ? (Math.round(canvas.clientWidth) + 'x' + Math.round(canvas.clientHeight)) : '';
                out.msgHidden = messageElement ? String(messageElement.hidden) : '';
                out.msgText = messageElement
                  ? (messageElement.textContent || '').trim().slice(0, 40) : '';
                var overlay = doc.querySelector('#homeos-boot-overlay');
                out.overlay = overlay
                  ? (overlay.textContent || '').replace(/\s+/g, ' ').trim().slice(0, 70) : '';
                if (win) {
                  out.frameViewport = win.innerWidth + 'x' + win.innerHeight;
                  out.frameWebgl2 = probeGl(win);
                  out.wallProfile = String(win.__homeosWallRuntimeProfile || '');
                  var recorded = win.__hosBootErrors;
                  if (recorded && recorded.length) {
                    out.bootErrors = [];
                    for (var e = 0; e < recorded.length && e < 3; e++) out.bootErrors.push(recorded[e]);
                  }
                }
                return out;
              }
              var hosts = [];
              var nodes = document.querySelectorAll('.hb-interaction3d-host');
              for (var i = 0; i < nodes.length; i++) {
                var host = nodes[i];
                var rect = host.getBoundingClientRect();
                var record = {
                  hostClass: String(host.className || ''),
                  box: Math.round(rect.width) + 'x' + Math.round(rect.height),
                  access: host.dataset ? (host.dataset.access || '') : '',
                  loadingText: ''
                };
                var runtime = host.querySelector('.hb-interaction3d-runtime');
                if (runtime) record.runtimeClass = String(runtime.className || '');
                var loading = host.querySelector('.i3d-loading');
                if (loading) record.loadingText = (loading.textContent || '').trim().slice(0, 60);
                var frame = host.querySelector('iframe');
                if (frame) {
                  var frameRect = frame.getBoundingClientRect();
                  record.frameBox = Math.round(frameRect.width) + 'x' + Math.round(frameRect.height);
                  record.frameSrc = String(frame.getAttribute('src') || '').slice(0, 80);
                  var inner = readFrame(frame);
                  for (var key in inner) record[key] = inner[key];
                }
                hosts.push(record);
              }
              return JSON.stringify({
                visibility: document.visibilityState,
                webgl2: probeGl(window),
                dpr: window.devicePixelRatio,
                viewport: window.innerWidth + 'x' + window.innerHeight,
                origin: window.location.origin,
                iframes: document.querySelectorAll('iframe').length,
                hosts: hosts
              });
            })();
        """.trimIndent()
        binding.webView.evaluateJavascript(script) { result ->
            if (isDestroying) return@evaluateJavascript
            // 自检本身绝不能把墙屏带崩：解析/拼串任何异常都只放弃这次上报。
            runCatching {
                val raw = unquoteJsString(result ?: "")
                android.util.Log.i("HomeOS3D", "渲染自检 webView=${webViewVersionLabel()} $raw")
                val verdict = describeDiagnostics(raw)
                if (onlyOnProblem && verdict.startsWith(NORMAL_TEXT_PREFIX)) return@runCatching
                if (silentWhenNoHost && verdict.startsWith(NO_HOST_PREFIX)) return@runCatching
                if (showToast) presentDiagnostics(verdict)
            }.onFailure {
                android.util.Log.w("HomeOS3D", "自检上报失败", it)
            }
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
        val consoleTail = if (recentConsoleErrors.isEmpty()) {
            ""
        } else {
            "\n\n最近 console 异常：" + recentConsoleErrors.takeLast(3)
                .joinToString("\n") { "· $it" }
        }
        runCatching {
            android.app.AlertDialog.Builder(activity)
                .setTitle("3D 自检")
                .setMessage("$verdict\n\nWebView：${webViewVersionLabel()}$consoleTail")
                .setPositiveButton("知道了", null)
                .show()
        }.onFailure {
            android.widget.Toast.makeText(activity, verdict, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /**
     * 把自检 JSON 翻译成一句人能照做的话。
     *
     * 判据顺序按「谁能负责」排：先把前端真实异常抛出来的地方查完（bootErrors → 挂载 →
     * 渲染器），最后才轮到尺寸类问题 —— 若无 canvas，那从来不是「布局问题」。
     */
    private fun describeDiagnostics(rawResult: String?): String {
        val json = runCatching { org.json.JSONObject(rawResult ?: "") }.getOrNull()
            ?: return "3D 自检失败：拿不到页面状态。请把日志（HomeOS3D）发给开发者。"
        val hosts = json.optJSONArray("hosts")
        val hostCount = hosts?.length() ?: 0
        if (hostCount == 0) {
            return NO_HOST_PREFIX + "（iframe=${json.optInt("iframes", -1)}，" +
                "视口=${json.optString("viewport", "?")}）。与 GPU 无关。"
        }
        val host = hosts!!.optJSONObject(0)!!
        fun field(name: String, fallback: String = "无"): String =
            (host.optString(name, "") ?: "").ifEmpty { fallback }

        val frameBox = field("frameBox")
        val frameViewport = field("frameViewport")
        val dpr = json.optDouble("dpr", 1.0)
        val dataLine = "宿主=${
            field("box")
        }，iframe=$frameBox，舞台内视口=$frameViewport，dpr=$dpr，" +
            "宿主class=${field("hostClass")}"

        // ① 前端自己记录下来的最早错误 —— 唯一能直接指出根因的证据。
        val bootErrors = host.optJSONArray("bootErrors")
        if (bootErrors != null && bootErrors.length() > 0) {
            val parts = StringBuilder()
            for (i in 0 until minOf(bootErrors.length(), 3)) {
                val item = bootErrors.optJSONObject(i) ?: continue
                if (parts.isNotEmpty()) parts.append("\n")
                parts.append("[${
                    item.optString("kind", "?")
                }] ${item.optString("detail", "")} ${item.optString("extra", "")}".trim())
            }
            return "根因：舞台页面启动期报错 ——\n$parts"
        }
        if (field("origin") == "cross-origin") {
            return "根因：舞台与本页跨源（stage src=${field("frameSrc")}），" +
                "postMessage 会被来源校验丢弃。"
        }
        // ② Vue 都没挂上：说明舞台里的 JS 没跑起来（模块加载失败 / 求值期抛错）。
        if (host.optInt("hasPreview", 0) == 0) {
            return "根因：舞台页面没有渲染 StudioView —— #preview-3d 不存在。" +
                "说明 iframe 里的前端没有挂载。\n" +
                "ready=${field("ready")}，mounted=${field("mounted")}，" +
                "#app 子节点=${host.optInt("appKids", -1)}，studio-shell=${host.optInt("shell", -1)}，" +
                "启动遮罩=\"${field("overlay")}\"，body.class=${field("bodyClass")}\n$dataLine"
        }
        // ③ 挂载了但没有 canvas：渲染器从未构造成功（WebGL2 在 iframe 内可用时尤其可疑）。
        if (host.optInt("canvases", 0) == 0) {
            return "根因：StudioView 已挂载，但**整个舞台里没有任何 canvas** —— " +
                "3D 渲染器没构造成功。\n" +
                "舞台内 WebGL2=${host.optBoolean("frameWebgl2", false)}，" +
                "#webgl-message hidden=${field("msgHidden")}（\"无\" = 元素不存在），" +
                "wallProfile=\"${field("wallProfile")}\"，ready=${field("ready")}，" +
                "loading=\"${field("loadingText", "")}\"\n$dataLine"
        }
        val canvas = field("canvas")
        val canvasCss = field("canvasCss")
        if (canvas.startsWith("0x") || canvasCss.startsWith("0x")) {
            return "canvas 已创建但尺寸为 0：canvas=$canvas（CSS $canvasCss）。属于尺寸/可见性问题。\n" +
                dataLine
        }
        return NORMAL_TEXT_PREFIX + "（canvas=$canvas，CSS=$canvasCss，$dataLine）"
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

    companion object {
        /**
         * 自检结论里「一切正常」的前缀。
         *
         * 自动自检靠它判定是否需要打扰用户：只有异常才弹框。
         */
        private const val NORMAL_TEXT_PREFIX = "3D 自检未发现异常"
        private const val NO_HOST_PREFIX = "页面里没有 3D 宿主"
        private const val CONSOLE_BUFFER_SIZE = 20
    }
}
