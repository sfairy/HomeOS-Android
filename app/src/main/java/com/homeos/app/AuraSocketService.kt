package com.homeos.app

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * 前台服务：用于轮询 HomeOS 通知 API 作为后台告警的备用方案
 * 替代旧版 Socket.IO 客户端（与 msgpack + Cookie-auth 网关不兼容）
 */
class AuraSocketService : Service() {

    private var serverUrl = "http://10.0.0.90:8501"  // 服务器地址
    private var authToken = ""                        // 认证令牌
    private var csrfToken = ""                        // CSRF 令牌
    private lateinit var orchestrator: NotificationOrchestrator  // 通知编排器
    private val executor = Executors.newSingleThreadExecutor()    // 单线程执行器
    private val pollHandler = Handler(Looper.getMainLooper())     // 轮询处理器
    private val seenNotificationIds = mutableSetOf<String>()      // 已处理的通知 ID 集合
    private var pollingStarted = false                 // 轮询是否已启动

    /**
     * 轮询任务：定期获取通知并处理
     */
    private val pollRunnable = object : Runnable {
        override fun run() {
            pollNotifications()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    /**
     * 服务创建时调用
     */
    override fun onCreate() {
        super.onCreate()
        orchestrator = NotificationOrchestrator(this)
        loadSeenNotificationIds()
        Log.i(TAG, "HomeOS 后台监控服务已创建。")
    }

    /**
     * 服务启动时调用
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 从 Intent 中获取参数
        intent?.getStringExtra("SERVER_URL")?.takeIf { it.isNotBlank() }?.let { serverUrl = it.trimEnd('/') }
        intent?.getStringExtra("AUTH_TOKEN")?.let { authToken = it }
        intent?.getStringExtra("CSRF_TOKEN")?.let { csrfToken = it }

        // 创建前台通知并启动前台服务
        val notification = createForegroundNotification()
        startForeground(NotificationOrchestrator.NOTIFICATION_SERVICE_ID, notification)

        // 开始轮询
        startPolling()

        return START_STICKY
    }

    /**
     * 开始轮询
     */
    private fun startPolling() {
        if (pollingStarted) return
        pollingStarted = true
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
    }

    /**
     * 停止轮询
     */
    private fun stopPolling() {
        pollingStarted = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    /**
     * 轮询通知 API
     */
    private fun pollNotifications() {
        // 检查必要参数
        if (serverUrl.isBlank() || authToken.isBlank()) {
            Log.d(TAG, "跳过轮询：缺少服务器地址或认证令牌。")
            return
        }

        // 在后台线程执行网络请求
        executor.execute {
            try {
                val url = URL("$serverUrl/api/v1/notifications?limit=20")
                val connection = url.openConnection() as HttpURLConnection
                
                // 设置连接参数
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", HomeOsAuth.APP_USER_AGENT)
                connection.setRequestProperty(
                    "Cookie",
                    HomeOsAuth.buildCookieHeader(authToken, csrfToken)
                )

                // 检查响应状态码
                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    Log.w(TAG, "通知轮询失败：HTTP $responseCode")
                    connection.disconnect()
                    return@execute
                }

                // 读取响应体并处理
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                handleNotificationPayload(body)
            } catch (e: Exception) {
                Log.w(TAG, "通知轮询错误：${e.message}")
            }
        }
    }

    /**
     * 处理通知响应数据
     */
    private fun handleNotificationPayload(body: String) {
        // 解析 JSON 数组
        val notifications = runCatching { JSONArray(body) }.getOrNull() ?: return
        val newlySeen = mutableListOf<String>()
        val isInitialSeed = seenNotificationIds.isEmpty()  // 是否为首次加载

        // 遍历所有通知
        for (index in 0 until notifications.length()) {
            val item = notifications.optJSONObject(index) ?: continue
            val id = item.optString("id", "")
            if (id.isEmpty()) continue

            // 跳过已处理的通知
            if (seenNotificationIds.contains(id)) continue

            // 非首次加载时，处理未读通知
            if (!isInitialSeed) {
                val isUnread = !item.optBoolean("read", false)
                if (isUnread) {
                    orchestrator.triggerFromHomeOsPayload(item)
                }
            }

            newlySeen.add(id)
        }

        // 保存新处理的通知 ID
        if (newlySeen.isNotEmpty()) {
            seenNotificationIds.addAll(newlySeen)
            persistSeenNotificationIds()
            if (isInitialSeed) {
                Log.i(TAG, "已初始化 ${newlySeen.size} 条现有通知，不触发提醒。")
            }
        }
    }

    /**
     * 加载已处理的通知 ID 集合
     */
    private fun loadSeenNotificationIds() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        seenNotificationIds.clear()
        prefs.getStringSet(KEY_SEEN_IDS, emptySet())?.let { seenNotificationIds.addAll(it) }
    }

    /**
     * 持久化已处理的通知 ID 集合
     */
    private fun persistSeenNotificationIds() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_SEEN_IDS, seenNotificationIds.toSet()).apply()
    }

    /**
     * 创建前台服务通知
     */
    private fun createForegroundNotification(): Notification {
        val intent = Intent(this, MainActivity::class.java)
        val pendingIntent = android.app.PendingIntent.getActivity(
            this,
            0,
            intent,
            android.app.PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NotificationOrchestrator.CHANNEL_SERVICE)
            .setContentTitle(getString(R.string.notification_monitor_title))
            .setContentText(getString(R.string.notification_monitor_text))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    /**
     * 绑定服务时调用（本服务不支持绑定）
     */
    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 服务销毁时调用
     */
    override fun onDestroy() {
        stopPolling()
        executor.shutdownNow()
        super.onDestroy()
        Log.i(TAG, "HomeOS 后台监控服务已销毁。")
    }

    companion object {
        private const val TAG = "AuraSocketService"           // 日志标签
        private const val PREFS_NAME = "HomeOSPreferences"    // 偏好设置名称
        private const val KEY_SEEN_IDS = "seen_notification_ids"  // 已处理通知 ID 键名
        private const val POLL_INTERVAL_MS = 15_000L          // 轮询间隔（15秒）
    }
}