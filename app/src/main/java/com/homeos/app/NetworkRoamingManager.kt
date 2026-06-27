package com.homeos.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.Request
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 实现智能路径漫游的自定义网络管理器。
 * 检测局域网服务器是否可达；如果可达，将流量路由到本地以实现亚毫秒级响应时间。
 * 否则回退到公网云端端点。
 */
class NetworkRoamingManager(context: Context) {

    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val callbackGeneration = AtomicInteger(0)

    private var lastResolvedUrl = ""
    private var lastResolvedIsLocal = false
    private var lastCheckTime = 0L
    private var cachedLanUrl = ""
    private var cachedWanUrl = ""
    private val cacheValidDuration = 30_000L

    /**
     * 解析最佳路由。
     * @param forceFresh 为 true 时跳过缓存强制重新探测（用于离线恢复/用户手动重试）。
     */
    fun resolveOptimalRoute(
        lanUrl: String,
        wanUrl: String,
        forceFresh: Boolean = false,
        callback: (resolvedUrl: String, isLocal: Boolean) -> Unit
    ) {
        val generation = callbackGeneration.get()

        if (!isNetworkConnected()) {
            deliverCallback(generation, wanUrl, false, callback)
            return
        }

        if (lanUrl.isEmpty()) {
            Log.d(TAG, "局域网 URL 为空，直接路由到公网: $wanUrl")
            deliverCallback(generation, wanUrl, false, callback)
            return
        }

        // 正/负结果均缓存：在缓存窗口内，无论上次走的是 LAN 还是 WAN 都直接复用，
        // 避免每次路由都做一次 500ms 的 LAN 探测。恢复/重试场景用 forceFresh 跳过。
        // 注意：当传入的 lan/wan 地址与缓存时不同（用户改了服务器配置）时，缓存立即失效，
        // 否则会把旧服务器的解析结果错误地用到新配置上。
        val now = System.currentTimeMillis()
        val urlsUnchanged = lanUrl == cachedLanUrl && wanUrl == cachedWanUrl
        if (!forceFresh && urlsUnchanged && lastResolvedUrl.isNotEmpty() &&
            (now - lastCheckTime) < cacheValidDuration
        ) {
            Log.d(TAG, "使用缓存路由（isLocal=$lastResolvedIsLocal）: $lastResolvedUrl")
            deliverCallback(generation, lastResolvedUrl, lastResolvedIsLocal, callback)
            return
        }

        Log.d(TAG, "正在解析网络路径...")
        executor.execute {
            val isLanAvailable = testConnection(lanUrl, timeoutMs = 500)
            mainHandler.post {
                if (generation != callbackGeneration.get()) return@post
                lastCheckTime = System.currentTimeMillis()
                cachedLanUrl = lanUrl
                cachedWanUrl = wanUrl
                if (isLanAvailable) {
                    Log.d(TAG, "已解析到局域网: $lanUrl")
                    lastResolvedUrl = lanUrl
                    lastResolvedIsLocal = true
                    callback(lanUrl, true)
                } else {
                    Log.d(TAG, "局域网连接超时或不可达。路由到公网: $wanUrl")
                    lastResolvedUrl = wanUrl
                    lastResolvedIsLocal = false
                    callback(wanUrl, false)
                }
            }
        }
    }

    fun cancelPendingCallbacks() {
        callbackGeneration.incrementAndGet()
        mainHandler.removeCallbacksAndMessages(null)
    }

    fun shutdown() {
        cancelPendingCallbacks()
        executor.shutdownNow()
    }

    private fun deliverCallback(
        generation: Int,
        url: String,
        isLocal: Boolean,
        callback: (String, Boolean) -> Unit
    ) {
        if (generation != callbackGeneration.get()) return
        callback(url, isLocal)
    }

    private fun isNetworkConnected(): Boolean {
        val connectivityManager =
            appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    private fun testConnection(urlString: String, timeoutMs: Int): Boolean {
        return try {
            val request = Request.Builder()
                .url(urlString)
                .head()
                .header("User-Agent", HomeOSAuth.APP_USER_AGENT)
                .build()
            val client = HttpClientProvider.withTimeouts(
                connectMs = timeoutMs.toLong(),
                readMs = timeoutMs.toLong(),
                callMs = (timeoutMs * 2).toLong(),
                // 内网 HTTPS 自签名证书探测时若不放行会被判为不可达，从而误回退到公网。
                trustSelfSigned = HttpClientProvider.isPrivateHost(urlString)
            )
            client.newCall(request).execute().use { response ->
                response.code in 200..399
            }
        } catch (e: Exception) {
            Log.w(TAG, "连接 $urlString 失败: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "Roaming"
    }
}
