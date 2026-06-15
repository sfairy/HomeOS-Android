package com.homeos.app

import android.util.Log
import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage

/**
 * 接收华为移动服务 (HMS) Push Kit 通知的服务。
 * 作为在中国大陆运行的设备（华为、荣耀、HarmonyOS 3/4）的主要推送通知提供商，
 * 这些设备没有 Google 服务。
 */
class HmsService : HmsMessageService() {

    private val orchestrator by lazy { NotificationOrchestrator(this) }

    /**
     * 从华为云收到新的 HMS 消息时触发。
     */
    override fun onMessageReceived(message: RemoteMessage?) {
        super.onMessageReceived(message)
        if (message == null) return

        Log.d("HmsService", "收到来自华为云的消息: ${message.from}")

        // 1. 处理后端映射的自定义数据载荷
        try {
            val data = message.dataOfMap
            if (data.isNotEmpty()) {
                Log.i("HmsService", "HMS 数据载荷: $data")
                orchestrator.triggerFromPushData(data.mapValues { it.value?.toString() ?: "" })
            }
        } catch (e: Exception) {
            Log.e("HmsService", "解析 HMS 数据载荷失败: ${e.message}")
        }

        // 2. 处理通知
        message.notification?.let {
            Log.d("HmsService", "HMS 通知内容: ${it.body}")
            val title = it.title ?: getString(R.string.push_hms_update)
            val body = it.body ?: ""
            orchestrator.triggerAlertNotification(
                System.currentTimeMillis().toString(),
                title,
                body,
                "INFO"
            )
        }
    }

    /**
     * 当 HMS Core 分配新的推送注册令牌时触发。
     */
    override fun onNewToken(token: String?) {
        super.onNewToken(token)
        if (token == null) return

        Log.i("HmsService", "生成了新的 HMS 注册令牌: $token")

        // 将令牌本地保存，以便通过 JS Bridge 的 window.Native.getPushToken() 暴露
        val sharedPreferences = getSharedPreferences("HomeOSPreferences", MODE_PRIVATE)
        sharedPreferences.edit().putString("hms_push_token", token).apply()
    }
}
