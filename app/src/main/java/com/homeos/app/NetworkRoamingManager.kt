package com.homeos.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
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

    private var lastUsedLocal = false
    private var lastCheckTime = 0L
    private val cacheValidDuration = 30_000L

    fun resolveOptimalRoute(
        lanUrl: String,
        wanUrl: String,
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

        val now = System.currentTimeMillis()
        if (lastUsedLocal && (now - lastCheckTime) < cacheValidDuration) {
            Log.d(TAG, "使用缓存的局域网路径: $lanUrl")
            deliverCallback(generation, lanUrl, true, callback)
            return
        }

        Log.d(TAG, "正在解析网络路径...")
        executor.execute {
            val isLanAvailable = testConnection(lanUrl, timeoutMs = 500)
            mainHandler.post {
                if (generation != callbackGeneration.get()) return@post
                lastCheckTime = System.currentTimeMillis()
                if (isLanAvailable) {
                    Log.d(TAG, "已解析到局域网: $lanUrl")
                    lastUsedLocal = true
                    callback(lanUrl, true)
                } else {
                    Log.d(TAG, "局域网连接超时或不可达。路由到公网: $wanUrl")
                    lastUsedLocal = false
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
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "HEAD"
            connection.setRequestProperty("User-Agent", HomeOSAuth.APP_USER_AGENT)
            connection.connect()
            connection.responseCode in 200..399
        } catch (e: IOException) {
            Log.w(TAG, "连接 $urlString 失败: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }

    companion object {
        private const val TAG = "Roaming"
    }
}
