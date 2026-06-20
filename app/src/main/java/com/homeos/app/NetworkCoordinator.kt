package com.homeos.app

import android.content.SharedPreferences
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
    private val sharedPreferences: SharedPreferences,
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

    fun routeAndLoadUrl() {
        if (!isAlive()) return
        isErrorState = false
        onHideOffline()
        onResumeBackgroundPolling()
        recoveryBackoffMs = INITIAL_RECOVERY_DELAY_MS

        binding.webView.visibility = View.INVISIBLE
        binding.loadingOverlay.visibility = View.VISIBLE
        binding.loadingOverlay.alpha = 1f
        binding.loadingText.text = getString(R.string.connecting)
        binding.subLoadingText.text = getString(R.string.checking_network_health)

        roamingManager.resolveOptimalRoute(getLanUrl(), getWanUrl()) { resolvedUrl, isLocal ->
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

            val authToken = HomeOSAuth.loadAuthToken(sharedPreferences)
            val csrfToken = HomeOSAuth.loadCsrfToken(sharedPreferences)

            if (authToken.isNotEmpty()) {
                executor.execute {
                    HomeOSAuth.injectCookies(resolvedUrl, authToken, csrfToken)
                    recoveryHandler.post {
                        if (isAlive()) loadActiveUrl(resolvedUrl)
                    }
                }
                return@resolveOptimalRoute
            }

            val savedUser = sharedPreferences.getString("auth_user", "") ?: ""
            val savedPass = sharedPreferences.getString("auth_pass", "") ?: ""
            if (savedUser.isNotEmpty() && savedPass.isNotEmpty()) {
                executor.execute {
                    val loginResult = HomeOSAuth.performLogin(resolvedUrl, savedUser, savedPass)
                    if (loginResult.success) {
                        sharedPreferences.edit().apply {
                            putString(HomeOSAuth.KEY_AUTH_TOKEN, loginResult.authToken)
                            putString(HomeOSAuth.KEY_CSRF_TOKEN, loginResult.csrfToken)
                            apply()
                        }
                        HomeOSAuth.injectCookies(
                            resolvedUrl,
                            loginResult.authToken,
                            loginResult.csrfToken
                        )
                    }
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
        roamingManager.resolveOptimalRoute(getLanUrl(), getWanUrl()) { resolvedUrl, _ ->
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
        routeAndLoadUrl()
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
