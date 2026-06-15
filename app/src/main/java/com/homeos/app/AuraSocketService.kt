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
 * Foreground service that polls HomeOS notifications API as a background alert fallback.
 * Replaces the legacy Socket.IO client, which is incompatible with the msgpack + Cookie-auth gateway.
 */
class AuraSocketService : Service() {

    private var serverUrl = "http://10.0.0.90:8501"
    private var authToken = ""
    private var csrfToken = ""
    private lateinit var orchestrator: NotificationOrchestrator
    private val executor = Executors.newSingleThreadExecutor()
    private val pollHandler = Handler(Looper.getMainLooper())
    private val seenNotificationIds = mutableSetOf<String>()
    private var pollingStarted = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            pollNotifications()
            pollHandler.postDelayed(this, POLL_INTERVAL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        orchestrator = NotificationOrchestrator(this)
        loadSeenNotificationIds()
        Log.i(TAG, "HomeOS background monitor service created.")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("SERVER_URL")?.takeIf { it.isNotBlank() }?.let { serverUrl = it.trimEnd('/') }
        intent?.getStringExtra("AUTH_TOKEN")?.let { authToken = it }
        intent?.getStringExtra("CSRF_TOKEN")?.let { csrfToken = it }

        val notification = createForegroundNotification()
        startForeground(NotificationOrchestrator.NOTIFICATION_SERVICE_ID, notification)

        startPolling()

        return START_STICKY
    }

    private fun startPolling() {
        if (pollingStarted) return
        pollingStarted = true
        pollHandler.removeCallbacks(pollRunnable)
        pollHandler.post(pollRunnable)
    }

    private fun stopPolling() {
        pollingStarted = false
        pollHandler.removeCallbacks(pollRunnable)
    }

    private fun pollNotifications() {
        if (serverUrl.isBlank() || authToken.isBlank()) {
            Log.d(TAG, "Skip poll: missing server URL or auth token.")
            return
        }

        executor.execute {
            try {
                val url = URL("$serverUrl/api/v1/notifications?limit=20")
                val connection = url.openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", HomeOsAuth.APP_USER_AGENT)
                connection.setRequestProperty(
                    "Cookie",
                    HomeOsAuth.buildCookieHeader(authToken, csrfToken)
                )

                val responseCode = connection.responseCode
                if (responseCode !in 200..299) {
                    Log.w(TAG, "Notification poll failed: HTTP $responseCode")
                    connection.disconnect()
                    return@execute
                }

                val body = connection.inputStream.bufferedReader().use { it.readText() }
                connection.disconnect()
                handleNotificationPayload(body)
            } catch (e: Exception) {
                Log.w(TAG, "Notification poll error: ${e.message}")
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
                    orchestrator.triggerFromHomeOsPayload(item)
                }
            }

            newlySeen.add(id)
        }

        if (newlySeen.isNotEmpty()) {
            seenNotificationIds.addAll(newlySeen)
            persistSeenNotificationIds()
            if (isInitialSeed) {
                Log.i(TAG, "Seeded ${newlySeen.size} existing notifications without alerting.")
            }
        }
    }

    private fun loadSeenNotificationIds() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        seenNotificationIds.clear()
        prefs.getStringSet(KEY_SEEN_IDS, emptySet())?.let { seenNotificationIds.addAll(it) }
    }

    private fun persistSeenNotificationIds() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putStringSet(KEY_SEEN_IDS, seenNotificationIds.toSet()).apply()
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
        Log.i(TAG, "HomeOS background monitor service destroyed.")
    }

    companion object {
        private const val TAG = "AuraSocketService"
        private const val PREFS_NAME = "HomeOSPreferences"
        private const val KEY_SEEN_IDS = "seen_notification_ids"
        private const val POLL_INTERVAL_MS = 15_000L
    }
}
