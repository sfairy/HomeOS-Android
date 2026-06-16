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
 * 处理所有通知编排。
 * 配置独立的通知渠道并管理唤醒锁，
 * 在关键事件（烟雾、门铃）触发时唤醒 Kiosk 平板显示器。
 */
class NotificationOrchestrator(private val context: Context) {

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    companion object {
        const val CHANNEL_CRITICAL = "homeos_channel_critical"
        const val CHANNEL_WARNING = "homeos_channel_warning"
        const val CHANNEL_INFO = "homeos_channel_info"
        const val CHANNEL_SERVICE = "homeos_channel_service"
        
        const val NOTIFICATION_SERVICE_ID = 9999
    }

    init {
        createNotificationChannels()
    }

    /**
     * 设置 Oreo+ 通知渠道，具有不同的优先级和警报级别。
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // 1. 静默前台保持通道
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                context.getString(R.string.channel_service_name),
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = context.getString(R.string.channel_service_desc)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // 2. 信息/广播通道
            val infoChannel = NotificationChannel(
                CHANNEL_INFO,
                context.getString(R.string.channel_info_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = context.getString(R.string.channel_info_desc)
            }
            notificationManager.createNotificationChannel(infoChannel)

            // 3. 警告通道（电池电量低、节点离线）
            val warningChannel = NotificationChannel(
                CHANNEL_WARNING,
                context.getString(R.string.channel_warning_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.channel_warning_desc)
                enableVibration(true)
            }
            notificationManager.createNotificationChannel(warningChannel)

            // 4. 严重警报通道（门铃、烟雾探测器、漏水传感器）
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
                
                // 分配高度独特的警报声音
                val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                val audioAttributes = AudioAttributes.Builder()
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .build()
                setSound(alarmSound, audioAttributes)
                
                // 允许绕过请勿打扰（DND）模式
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    setBypassDnd(true)
                }
            }
            notificationManager.createNotificationChannel(criticalChannel)
        }
    }

    /**
     * 唤醒屏幕并触发高优先级系统通知。
     */
    fun triggerAlertNotification(
        id: String,
        title: String,
        message: String,
        severity: String,
        cameraEntityId: String? = null
    ) {
        Log.i("Orchestrator", "收到通知请求: $title [$severity]")

        // 1. 如果是严重警报且屏幕关闭，请求 WakeLock 立即打开屏幕
        if (severity.equals("CRITICAL", ignoreCase = true)) {
            wakeScreen()
        }

        // 2. 准备用户点击通知时打开 MainActivity 的意图
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

        // 3. 根据警报级别解析通道和样式
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
            builder.setFullScreenIntent(pendingIntent, true) // 强制在当前屏幕上方弹出
            builder.setVisibility(NotificationCompat.VISIBILITY_PUBLIC) // 在锁屏上可见
        }

        notificationManager.notify(id.hashCode(), builder.build())
    }

    /**
     * 将 HomeOS 通知载荷映射到原生警报。
     * 后端数据结构: { id, level, message, entityId, source, read, createdAt }
     */
    fun triggerFromHomeOSPayload(payload: org.json.JSONObject) {
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
     * 将推送载荷键值数据（FCM/HMS）映射到原生警报。
     * 支持 HomeOS 字段（level, entityId）和遗留键（severity, cameraEntityId）。
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
        "security-panel", "anomaly-detector" -> context.getString(R.string.alert_title_security)
        "device-monitor" -> context.getString(R.string.alert_title_device)
        "alert-rule" -> context.getString(R.string.alert_title_rule)
        else -> when (level.lowercase()) {
            "danger", "critical" -> context.getString(R.string.alert_title_emergency)
            "warn", "warning" -> context.getString(R.string.alert_title_warning)
            else -> context.getString(R.string.alert_title_homeos)
        }
    }

    /**
     * 取消活动的系统通知。
     */
    fun cancelNotification(id: String) {
        notificationManager.cancel(id.hashCode())
    }

    /**
     * 强制点亮平板屏幕的入口方法（使用 WakeLock）。
     */
    private fun wakeScreen() {
        try {
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                        PowerManager.ACQUIRE_CAUSES_WAKEUP or
                        PowerManager.ON_AFTER_RELEASE,
                "HomeOS:EmergencyWakeScreen"
            )
            // 获取唤醒锁一小段时间来打开屏幕，然后立即释放
            wakeLock.acquire(30000L)
            // ON_AFTER_RELEASE 标志会在释放后短暂保持屏幕开启，因此我们立即释放
            try {
                if (wakeLock.isHeld) {
                    wakeLock.release()
                }
            } catch (e: Exception) {
                Log.w("Orchestrator", "WakeLock 释放失败（可能已释放）: ${e.message}")
            }
            Log.d("Orchestrator", "WakeLock 获取并释放。屏幕已打开。")
        } catch (e: Exception) {
            Log.e("Orchestrator", "获取屏幕唤醒锁失败: ${e.message}")
        }
    }
}
