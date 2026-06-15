package com.homeos.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat

/**
 * Handles all notification orchestration.
 * Configures separate notification channels and manages wake locks
 * to wake up Kiosk tablet displays when critical events (smoke, doorbell) trigger.
 */
class NotificationOrchestrator(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    companion object {
        const val CHANNEL_CRITICAL = "aura_channel_critical"
        const val CHANNEL_WARNING = "aura_channel_warning"
        const val CHANNEL_INFO = "aura_channel_info"
        const val CHANNEL_SERVICE = "aura_channel_service"
        
        const val NOTIFICATION_SERVICE_ID = 9999
    }

    init {
        createNotificationChannels()
    }

    /**
     * Set up Oreo+ Notification Channels with distinct priorities and alerts.
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 1. Silent Foreground Keepalive Channel
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = context.getString(R.string.channel_service_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // 2. Info / Broadcast channel
            val infoChannel = NotificationChannel(
                CHANNEL_INFO,
                context.getString(R.string.channel_info_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_info_desc)
            }
            notificationManager.createNotificationChannel(infoChannel)

            // 3. Warning Channel (Battery Low, Node Offline)
            val warningChannel = NotificationChannel(
                CHANNEL_WARNING,
                context.getString(R.string.channel_warning_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_warning_desc)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(warningChannel)

            // 4. Critical Alert Channel (Doorbell Ring, Smoke Detector, Leak Sensor)
            val criticalChannel = NotificationChannel(
                CHANNEL_CRITICAL,
                context.getString(R.string.channel_critical_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = context.getString(R.string.channel_critical_desc)
                enableLights(true)
                lightColor = Color.RED
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500, 200, 500, 200, 500)
                
                // Assign a highly distinct alarm sound
                val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                setSound(alarmSound, audioAttributes)
                
                // Allow bypassing Do Not Disturb (DND)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setBypassDnd(true)
                }
            }
            notificationManager.createNotificationChannel(criticalChannel)
        }
    }

    /**
     * Wakes the screen and fires a high-priority system notification.
     */
    fun triggerAlertNotification(
        id: String,
        title: String,
        message: String,
        severity: String,
        cameraEntityId: String? = null
    ) {
        Log.i("AuraOrchestrator", "Received notification request: $title [$severity]")

        // 1. If CRITICAL and screen is off, request WakeLock to turn screen ON immediately
        if (severity.equals("CRITICAL", ignoreCase = true)) {
            wakeScreen()
        }

        // 2. Prepare intent to open MainActivity when user taps the notification
        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("ALERT_ID", id)
            putExtra("OPEN_CAMERA_ENTITY", cameraEntityId)
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // 3. Resolve channel & styling based on severity
        val channelId = when (severity.uppercase()) {
            "CRITICAL" -> CHANNEL_CRITICAL
            "WARNING" -> CHANNEL_WARNING
            else -> CHANNEL_INFO
        }

        val builder = NotificationCompat.Builder(context, channelId)
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setContentTitle(title)
            .setContentText(message)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(
                if (severity.uppercase() == "CRITICAL") NotificationCompat.PRIORITY_MAX 
                else NotificationCompat.PRIORITY_DEFAULT
            )
            .setCategory(
                if (severity.uppercase() == "CRITICAL") NotificationCompat.CATEGORY_ALARM 
                else NotificationCompat.CATEGORY_MESSAGE
            )

        if (severity.uppercase() == "CRITICAL") {
            builder.setFullScreenIntent(pendingIntent, true) // Force popup on top of current screen
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // Make visible on lockscreen
        }

        notificationManager.notify(id.hashCode(), builder.build())
    }

    /**
     * Maps a HomeOS notification payload to a native alert.
     * Backend shape: { id, level, message, entityId, source, read, createdAt }
     */
    fun triggerFromHomeOsPayload(payload: org.json.JSONObject) {
        val id = payload.optString("id", System.currentTimeMillis().toString())
        val level = payload.optString("level", "info")
        val message = payload.optString("message", "")
        val source = payload.optString("source", "homeos")
        val entityId = payload.optString("entityId", "").takeIf { it.isNotEmpty() }

        val severity = mapLevelToSeverity(level)
        val title = mapSourceToTitle(source, level)
        val cameraEntity = entityId?.takeIf { it.startsWith("camera.") }

        triggerAlertNotification(id, title, message, severity, cameraEntity)
    }

    /**
     * Maps push payload key-value data (FCM/HMS) to a native alert.
     * Supports HomeOS fields (level, entityId) and legacy keys (severity, cameraEntityId).
     */
    fun triggerFromPushData(data: Map<String, String>) {
        val id = data["id"] ?: System.currentTimeMillis().toString()
        val message = data["message"] ?: data["body"] ?: "A critical alert has been dispatched."
        val level = data["level"] ?: data["severity"] ?: "info"
        val source = data["source"] ?: "push"
        val entityId = data["entityId"] ?: data["cameraEntityId"]

        val severity = mapLevelToSeverity(level)
        val title = data["title"] ?: mapSourceToTitle(source, level)
        val cameraEntity = entityId?.takeIf { it.startsWith("camera.") }

        triggerAlertNotification(id, title, message, severity, cameraEntity)
    }

    private fun mapLevelToSeverity(level: String): String = when (level.lowercase()) {
        "danger", "critical" -> "CRITICAL"
        "warn", "warning" -> "WARNING"
        else -> "INFO"
    }

    private fun mapSourceToTitle(source: String, level: String): String = when (source) {
        "security-panel", "anomaly-detector" -> "安防告警"
        "device-monitor" -> "设备告警"
        "alert-rule" -> "规则告警"
        else -> when (level.lowercase()) {
            "danger", "critical" -> "紧急通知"
            "warn", "warning" -> "警告通知"
            else -> "HomeOS 通知"
        }
    }

    /**
     * Cancels an active system notification.
     */
    fun cancelNotification(id: String) {
        notificationManager.cancel(id.hashCode())
    }

    /**
     * Point-of-entry to forcefully point-light the tablet screen (WakeLock).
     */
    private fun wakeScreen() {
        try {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "HomeOS:EmergencyWakeScreen"
            )
            // Acquire wake lock for a brief period to turn the screen on, then release immediately
            wakeLock.acquire(30000L)
            // ON_AFTER_RELEASE keeps the screen on briefly after release, so we release right away
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            } catch (e: Exception) {
                Log.w("AuraOrchestrator", "WakeLock release failed (may already be released): ${e.message}")
            }
            Log.d("AuraOrchestrator", "WakeLock acquired and released. Screen turned on.")
        } catch (e: Exception) {
            Log.e("AuraOrchestrator", "Failed to acquire screen WakeLock: ${e.message}")
        }
    }
}
