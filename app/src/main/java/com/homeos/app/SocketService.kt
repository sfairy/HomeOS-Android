package com.homeos.app

import android.app.Notification
import android.app.Service
import android.content.Intent
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.Request
import org.json.JSONArray
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 前台服务：用于轮询 HomeOS 通知 API 作为后台告警的备用方案。
 */
class SocketService : Service() {

    private var serverUrl = "http://10.0.0.90:8501"
    private var authToken = ""
    private var csrfToken = ""
    private lateinit var orchestrator: NotificationOrchestrator
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()
    private val pollHandler = Handler(Looper.getMainLooper())
    private val seenNotificationIds = mutableSetOf<String>()
    private var pollingStarted = false
    private var isPolling = false
    private var isAppForeground = false
    private var pollingPaused = false
    private var seenIdsDirty = false

    private val persistRunnable = Runnable { flushSeenNotificationIds() }

    private val pollRunnable = Runnable { pollNotifications() }

    override fun onCreate() {
        super.onCreate()
        orchestrator = NotificationOrchestrator(this)
        loadSeenNotificationIds()
        isAppForeground = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .getBoolean(KEY_APP_FOREGROUND, false)
        Log.i(TAG, "HomeOS 后台监控服务已创建。")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra(EXTRA_SERVER_URL)?.takeIf { it.isNotBlank() }?.let {
            serverUrl = it.trimEnd('/')
        }
        intent?.getStringExtra(EXTRA_AUTH_TOKEN)?.let { authToken = it }
        intent?.getStringExtra(EXTRA_CSRF_TOKEN)?.let { csrfToken = it }

        if (intent?.hasExtra(EXTRA_APP_FOREGROUND) == true) {
            isAppForeground = intent.getBooleanExtra(EXTRA_APP_FOREGROUND, false)
            persistAppForeground(isAppForeground)
            applyForegroundPollingPolicy()
        }

        if (intent?.getBooleanExtra(EXTRA_PAUSE_POLLING, false) == true) {
            pollingPaused = true
            pollHandler.removeCallbacks(pollRunnable)
        } else if (intent?.getBooleanExtra(EXTRA_RESUME_POLLING, false) == true) {
            pollingPaused = false
            if (pollingStarted) {
                applyForegroundPollingPolicy()
            }
        }

        val notification = createForegroundNotification()
        startForeground(NotificationOrchestrator.NOTIFICATION_SERVICE_ID, notification)

        startPolling()

        return START_STICKY
    }

    private fun startPolling() {
        if (pollingStarted) return
        pollingStarted = true
        applyForegroundPollingPolicy()
    }

    private fun stopPolling() {
        pollingStarted = false
        pollingPaused = false
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.removeCallbacks(persistRunnable)
        flushSeenNotificationIds()
    }

    private fun currentPollIntervalMs(): Long {
        if (isAppForeground) return POLL_INTERVAL_FOREGROUND_MS
        return if (hasPushToken()) {
            POLL_INTERVAL_BACKGROUND_WITH_PUSH_MS
        } else {
            POLL_INTERVAL_BACKGROUND_MS
        }
    }

    /**
     * 前台时 WebView 已实时取数，停止原生 HTTP 轮询；仅在后台/熄屏时按间隔兜底。
     */
    private fun applyForegroundPollingPolicy() {
        pollHandler.removeCallbacks(pollRunnable)
        if (!pollingStarted || pollingPaused || isAppForeground) return
        pollHandler.postDelayed(pollRunnable, currentPollIntervalMs())
    }

    private fun hasPushToken(): Boolean {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val fcm = prefs.getString("fcm_push_token", "") ?: ""
        val hms = prefs.getString("hms_push_token", "") ?: ""
        return fcm.isNotEmpty() || hms.isNotEmpty()
    }

    private fun persistAppForeground(foreground: Boolean) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_APP_FOREGROUND, foreground)
            .apply()
    }

    private fun pollNotifications() {
        if (pollingPaused) {
            applyForegroundPollingPolicy()
            return
        }

        if (isAppForeground) {
            applyForegroundPollingPolicy()
            return
        }

        if (isPolling) {
            applyForegroundPollingPolicy()
            return
        }

        if (serverUrl.isBlank() || authToken.isBlank()) {
            Log.d(TAG, "跳过轮询：缺少服务器地址或认证令牌。")
            applyForegroundPollingPolicy()
            return
        }

        isPolling = true
        executor.execute {
            try {
                val request = Request.Builder()
                    .url("$serverUrl/api/v1/notifications?limit=20")
                    .get()
                    .header("User-Agent", HomeOSAuth.APP_USER_AGENT)
                    .header("Cookie", HomeOSAuth.buildCookieHeader(authToken, csrfToken))
                    .build()
                val client = HttpClientProvider.withTimeouts(connectMs = 8000, readMs = 8000)
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "通知轮询失败：HTTP ${response.code}")
                        return@use
                    }
                    val body = response.body?.string() ?: ""
                    handleNotificationPayload(body)
                }
            } catch (e: Exception) {
                Log.w(TAG, "通知轮询错误：${e.message}")
            } finally {
                isPolling = false
                pollHandler.post {
                    applyForegroundPollingPolicy()
                }
            }
        }
    }

    private fun handleNotificationPayload(body: String) {
        val notifications = runCatching { JSONArray(body) }.getOrNull() ?: return
        val newlySeen = mutableListOf<String>()
        val isInitialSeed = seenNotificationIds.isEmpty()

        for (index in 0 until notifications.length()) {
            val item = notifications.optJSONObject(index) ?: continue
            val id = item.optString("id", "")
            if (id.isEmpty()) continue
            if (seenNotificationIds.contains(id)) continue

            if (!isInitialSeed) {
                val isUnread = !item.optBoolean("read", false)
                if (isUnread) {
                    orchestrator.triggerFromHomeOSPayload(item)
                }
            }
            newlySeen.add(id)
        }

        if (newlySeen.isNotEmpty()) {
            seenNotificationIds.addAll(newlySeen)
            if (seenNotificationIds.size > MAX_SEEN_IDS) {
                val excess = seenNotificationIds.size - MAX_SEEN_IDS
                val iterator = seenNotificationIds.iterator()
                repeat(excess) {
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                    }
                }
            }
            schedulePersistSeenIds()
            if (isInitialSeed) {
                Log.i(TAG, "已初始化 ${newlySeen.size} 条现有通知，不触发提醒。")
            }
        }
    }

    private fun schedulePersistSeenIds() {
        seenIdsDirty = true
        pollHandler.removeCallbacks(persistRunnable)
        pollHandler.postDelayed(persistRunnable, PERSIST_DEBOUNCE_MS)
    }

    private fun flushSeenNotificationIds() {
        if (!seenIdsDirty) return
        seenIdsDirty = false
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_SEEN_IDS, seenNotificationIds.toSet()).apply()
    }

    private fun loadSeenNotificationIds() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        seenNotificationIds.clear()
        prefs.getStringSet(KEY_SEEN_IDS, emptySet())?.let { seenNotificationIds.addAll(it) }
    }

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

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopPolling()
        executor.shutdownNow()
        super.onDestroy()
        Log.i(TAG, "HomeOS 后台监控服务已销毁。")
    }

    companion object {
        private const val TAG = "SocketService"
        private const val PREFS_NAME = "HomeOSPreferences"
        private const val KEY_SEEN_IDS = "seen_notification_ids"
        // 前台时 WebView 已实时取数，原生轮询完全停止；
        // 后台时由 FCM/HMS 推送为主，轮询仅作兜底，间隔尽量放宽以省电。
        // 注意：后台间隔必须明显大于前台间隔。此前两者被设反（后台 30s 比前台 60s 更频繁），
        // 导致熄屏/后台时每 30s 从空闲态唤醒一次网络射频，是耗电异常的主因。
        private const val POLL_INTERVAL_FOREGROUND_MS = 60_000L
        private const val POLL_INTERVAL_BACKGROUND_MS = 600_000L
        private const val POLL_INTERVAL_BACKGROUND_WITH_PUSH_MS = 900_000L
        private const val PERSIST_DEBOUNCE_MS = 30_000L
        private const val MAX_SEEN_IDS = 500
        private const val KEY_APP_FOREGROUND = "app_in_foreground"

        const val EXTRA_SERVER_URL = "SERVER_URL"
        const val EXTRA_AUTH_TOKEN = "AUTH_TOKEN"
        const val EXTRA_CSRF_TOKEN = "CSRF_TOKEN"
        const val EXTRA_APP_FOREGROUND = "APP_FOREGROUND"
        const val EXTRA_PAUSE_POLLING = "PAUSE_POLLING"
        const val EXTRA_RESUME_POLLING = "RESUME_POLLING"
    }
}
