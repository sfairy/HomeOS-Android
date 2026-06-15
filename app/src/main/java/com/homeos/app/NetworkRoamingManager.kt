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
import java.util.concurrent.Executors

/**
 * 实现智能路径漫游的自定义网络管理器。
 * 检测局域网服务器是否可达；如果可达，将流量路由到本地以实现亚毫秒级响应时间。
 * 否则回退到公网云端端点。
 */
class NetworkRoamingManager(private val context: Context) {

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    interface RoamingCallback {
        fun onRouteResolved(resolvedUrl: String, isLocal: Boolean)
    }

    /**
     * 探测本地网络地址以解析最佳 Web 根路径。
     */
    fun resolveOptimalRoute(lanUrl: String, wanUrl: String, callback: RoamingCallback) {
        if (!isNetworkConnected()) {
            callback.onRouteResolved(wanUrl, false) // 默认回退到公网/离线处理
            return
        }

        if (lanUrl.isEmpty()) {
            Log.d("Roaming", "局域网 URL 为空，直接路由到公网: $wanUrl")
            callback.onRouteResolved(wanUrl, false)
            return
        }

        Log.d("Roaming", "正在解析网络路径...")
        executor.execute {
            val isLanAvailable = testConnection(lanUrl, timeoutMs = 1500)
            mainHandler.post {
                if (isLanAvailable) {
                    Log.d("Roaming", "已解析到局域网: $lanUrl")
                    callback.onRouteResolved(lanUrl, true)
                } else {
                    Log.d("Roaming", "局域网连接超时或不可达。路由到公网: $wanUrl")
                    callback.onRouteResolved(wanUrl, false)
                }
            }
        }
    }

    /**
     * 确定物理设备是否连接到任何互联网/局域网。
     */
    private fun isNetworkConnected(): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val activeNetwork = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    /**
     * 执行轻量级 TCP 连接探测（HTTP HEAD 请求）以检查本地服务器是否在线。
     */
    private fun testConnection(urlString: String, timeoutMs: Int): Boolean {
        var connection: HttpURLConnection? = null
        return try {
            val url = URL(urlString)
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.requestMethod = "HEAD" // 保持请求载荷极低
            connection.setRequestProperty("User-Agent", HomeOSAuth.APP_USER_AGENT)
            connection.connect()
            
            // 200 到 399 之间的任何响应码都表示服务器健康
            val responseCode = connection.responseCode
            responseCode in 200..399
        } catch (e: IOException) {
            Log.w("Roaming", "连接 $urlString 失败: ${e.message}")
            false
        } finally {
            connection?.disconnect()
        }
    }
}
