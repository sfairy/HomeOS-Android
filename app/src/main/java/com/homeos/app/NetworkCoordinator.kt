package com.homeos.app

import android.util.Log
import android.view.View
import com.homeos.app.databinding.ActivityMainBinding
import java.util.concurrent.Executor

/**
 * 网络路由、页面加载、离线恢复与超时管理。
 */
class NetworkCoordinator(
    private val binding: ActivityMainBinding,
    private val roamingManager: NetworkRoamingManager,
    private val executor: Executor,
    private val webViewController: WebViewController,
    private val isAlive: () -> Boolean,
    private val getLanUrl: () -> String,
    private val getWanUrl: () -> String,
    private val setActiveUrl: (String) -> Unit,
    private val onShowOffline: () -> Unit,
    private val onHideOffline: () -> Unit,
    private val onShowSettings: () -> Unit,
    private val onServiceUrlResolved: (String) -> Unit,
    private val onPauseBackgroundPolling: () -> Unit,
    private val onResumeBackgroundPolling: () -> Unit,
    private val getString: (Int) -> String
) {
    var isErrorState = false
        private set

    private val recoveryHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val recoveryRunnable = Runnable { attemptAutoRecovery() }
    private val loadTimeoutRunnable = Runnable { handleLoadTimeout() }

    private var recoveryBackoffMs = INITIAL_RECOVERY_DELAY_MS
    private var freshLoginDone = false

    private val context get() = binding.root.context

    /**
     * 配置（服务器地址/账号密码）变更后调用。
     * @param alreadyLoggedIn 设置页是否已用新配置成功登录（此时无需在加载时再次登录）。
     */
    fun resetLoginSession(alreadyLoggedIn: Boolean) {
        freshLoginDone = alreadyLoggedIn
    }

    fun routeAndLoadUrl(forceFresh: Boolean = false) {
        if (!isAlive()) return
        isErrorState = false
        onHideOffline()
        onResumeBackgroundPolling()
        recoveryBackoffMs = INITIAL_RECOVERY_DELAY_MS

        // 加载期间不要把 WebView 设成 INVISIBLE，只用不透明的 loadingOverlay 盖住它：
        // Android WebView 在 View 不可见时不会为页面建立合成器表面，页面里的 WebGL
        // （总览的 3D 户型舞台 iframe）会拿不到可用的 GL 上下文、或上下文刚建好就被丢弃，
        // 之后即使再设为 VISIBLE 也只剩一块空白画布 —— 而普通 DOM 界面照常显示，
        // 于是表现为「只有安卓 App 里 3D 不出来、周边 UI 都正常」。
        binding.webView.visibility = View.VISIBLE
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.loadingOverlay.alpha = 1f
        binding.loadingText.text = getString(R.string.connecting)
        binding.subLoadingText.text = getString(R.string.checking_network_health)

        roamingManager.resolveOptimalRoute(getLanUrl(), getWanUrl(), forceFresh) { resolvedUrl, isLocal ->
            if (!isAlive()) return@resolveOptimalRoute

            if (resolvedUrl.isEmpty()) {
                Log.e(TAG, "解析到的 URL 为空。放弃加载 WebView。")
                binding.subLoadingText.text = getString(R.string.error_no_server_url)
                onShowSettings()
                return@resolveOptimalRoute
            }

            setActiveUrl(resolvedUrl)
            onServiceUrlResolved(resolvedUrl)

            binding.subLoadingText.text = if (isLocal) {
                getString(R.string.lan_route_online)
            } else {
                getString(R.string.wan_route_online)
            }
            Log.i(TAG, "最佳路由已解析。正在加载 URL: $resolvedUrl")

            val authToken = HomeOSAuth.loadAuthToken(context)
            val csrfToken = HomeOSAuth.loadCsrfToken(context)
            val savedUser = HomeOSAuth.loadUsername(context)
            val savedPass = HomeOSAuth.loadPassword(context)

            // 本进程内仅在首次成功登录前用账号密码静默重登录一次，换取全新有效令牌；
            // 之后的断网恢复/重试直接复用令牌，避免每次恢复都打一次登录请求（省时、减轻服务端压力）。
            // 这样既修复了「冷启动需重新输入账号密码」，又不会在网络抖动时反复登录。
            if (savedUser.isNotEmpty() && savedPass.isNotEmpty() && !freshLoginDone) {
                executor.execute {
                    val loginResult = HomeOSAuth.performLogin(resolvedUrl, savedUser, savedPass)
                    when {
                        loginResult.success -> {
                            freshLoginDone = true
                            HomeOSAuth.saveTokens(
                                context,
                                loginResult.authToken,
                                loginResult.csrfToken
                            )
                            HomeOSAuth.injectCookies(
                                resolvedUrl,
                                loginResult.authToken,
                                loginResult.csrfToken
                            )
                            // 关键：用新令牌刷新后台轮询服务，否则后台仍用旧（可能已过期）令牌。
                            recoveryHandler.post {
                                if (isAlive()) onServiceUrlResolved(resolvedUrl)
                            }
                        }
                        // 重新登录失败（例如网络暂时不可用）时，回退到已存储的令牌。
                        authToken.isNotEmpty() ->
                            HomeOSAuth.injectCookies(resolvedUrl, authToken, csrfToken)
                    }
                    recoveryHandler.post {
                        if (isAlive()) loadActiveUrl(resolvedUrl)
                    }
                }
                return@resolveOptimalRoute
            }

            // 已登录过或仅有令牌：直接注入令牌后加载。
            if (authToken.isNotEmpty()) {
                executor.execute {
                    HomeOSAuth.injectCookies(resolvedUrl, authToken, csrfToken)
                    recoveryHandler.post {
                        if (isAlive()) loadActiveUrl(resolvedUrl)
                    }
                }
                return@resolveOptimalRoute
            }

            loadActiveUrl(resolvedUrl)
        }
    }

    fun loadActiveUrl(url: String) {
        if (!isAlive()) return
        recoveryHandler.removeCallbacks(loadTimeoutRunnable)
        recoveryHandler.postDelayed(loadTimeoutRunnable, LOAD_TIMEOUT_MS)
        webViewController.webView.loadUrl(url)
    }

    fun onPageReady() {
        if (!isAlive()) return
        recoveryHandler.removeCallbacks(loadTimeoutRunnable)
        if (!isErrorState) {
            binding.loadingOverlay.visibility = View.GONE
            binding.webView.visibility = View.VISIBLE
            binding.webView.requestFocus()
        }
    }

    fun handlePageLoadFailure() {
        if (!isAlive()) return
        isErrorState = true
        recoveryHandler.removeCallbacks(recoveryRunnable)
        recoveryHandler.removeCallbacks(loadTimeoutRunnable)
        onPauseBackgroundPolling()
        onShowOffline()
        scheduleRecovery()
    }

    private fun scheduleRecovery() {
        recoveryHandler.removeCallbacks(recoveryRunnable)
        recoveryHandler.postDelayed(recoveryRunnable, recoveryBackoffMs)
        recoveryBackoffMs = (recoveryBackoffMs * 2).coerceAtMost(MAX_RECOVERY_DELAY_MS)
    }

    fun attemptAutoRecovery() {
        if (!isAlive()) return
        Log.d(TAG, "正在尝试自动网络路径恢复（退避 ${recoveryBackoffMs}ms）...")
        roamingManager.resolveOptimalRoute(getLanUrl(), getWanUrl(), forceFresh = true) { resolvedUrl, _ ->
            if (!isAlive()) return@resolveOptimalRoute
            if (resolvedUrl.isNotEmpty()) {
                recoveryHandler.removeCallbacks(recoveryRunnable)
                recoveryBackoffMs = INITIAL_RECOVERY_DELAY_MS
                isErrorState = false
                onHideOffline()
                onResumeBackgroundPolling()
                routeAndLoadUrl()
            } else {
                scheduleRecovery()
            }
        }
    }

    fun retryNow() {
        recoveryHandler.removeCallbacks(recoveryRunnable)
        recoveryBackoffMs = INITIAL_RECOVERY_DELAY_MS
        isErrorState = false
        onHideOffline()
        onResumeBackgroundPolling()
        routeAndLoadUrl(forceFresh = true)
    }

    private fun handleLoadTimeout() {
        if (!isAlive()) return
        Log.w(TAG, "WebView 加载超时（10秒）。正在停止连接。")
        webViewController.webView.stopLoading()
        handlePageLoadFailure()
    }

    fun cancelAll() {
        recoveryHandler.removeCallbacks(recoveryRunnable)
        recoveryHandler.removeCallbacks(loadTimeoutRunnable)
        roamingManager.cancelPendingCallbacks()
    }

    companion object {
        private const val TAG = "NetworkCoordinator"
        private const val LOAD_TIMEOUT_MS = 10_000L
        private const val INITIAL_RECOVERY_DELAY_MS = 3_000L
        private const val MAX_RECOVERY_DELAY_MS = 30_000L
    }
}
