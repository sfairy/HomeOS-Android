package com.homeos.app

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * 接收 Firebase Cloud Messaging (FCM) 推送通知的服务。
 * 在后台运行（即使应用完全关闭或深度睡眠），
 * 为关键警报（如室外门铃或紧急事件）唤醒设备。
 */
class FcmService : FirebaseMessagingService() {

    private val orchestrator by lazy { NotificationOrchestrator(this) }

    /**
     * 收到新的 FCM 消息时触发。
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        Log.d("FcmService", "收到来自发送者的 FCM 消息: ${remoteMessage.from}")

        // 1. 处理自定义数据载荷（我们的 NestJS 后端发送键值结构数据）
        if (remoteMessage.data.isNotEmpty()) {
            try {
                Log.i("FcmService", "FCM 数据载荷: ${remoteMessage.data}")
                orchestrator.triggerFromPushData(remoteMessage.data)
            } catch (e: Exception) {
                Log.e("FcmService", "解析 FCM 数据载荷失败: ${e.message}")
            }
        }

        // 2. 如果存在标准云通知载荷则处理
        remoteMessage.notification?.let {
            Log.d("FcmService", "FCM 通知标题: ${it.title}, 内容: ${it.body}")
            val title = it.title ?: getString(R.string.push_cloud_update)
            val message = it.body ?: ""
            // 默认将标准通知载荷发送到 INFO 通道
            orchestrator.triggerAlertNotification(
                System.currentTimeMillis().toString(),
                title,
                message,
                "INFO"
            )
        }
    }

    /**
     * 当 Firebase 颁发新的注册令牌时触发（如应用安装、更新、清除数据）。
     * 应将此令牌上传到后端服务器，以便将设备与警报关联。
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.i("FcmService", "生成了新的 FCM 注册令牌: $token")
        
        // 将令牌本地保存，以便通过 JS Bridge 的 window.Native.getPushToken() 暴露
        val sharedPreferences = getSharedPreferences("HomeOSPreferences", MODE_PRIVATE)
        sharedPreferences.edit().putString("fcm_push_token", token).apply()
        
        // Vue 3 客户端将自动调用 window.Native.getPushToken()
        // 并通过 POST /api/v1/device/register 向后端注册
    }
}
