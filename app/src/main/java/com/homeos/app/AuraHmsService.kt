package com.homeos.app

import android.util.Log
import com.huawei.hms.push.HmsMessageService
import com.huawei.hms.push.RemoteMessage

/**
 * Service that receives Huawei Mobile Services (HMS) Push Kit notifications.
 * Serves as the primary push notifications provider for devices operating
 * in mainland China (Huawei, Honor, HarmonyOS 3/4) without Google Services.
 */
class AuraHmsService : HmsMessageService() {

    private val orchestrator by lazy { NotificationOrchestrator(this) }

    /**
     * Triggered when a new HMS message is received from Huawei Cloud.
     */
    override fun onMessageReceived(message: RemoteMessage?) {
        super.onMessageReceived(message)
        if (message == null) return

        Log.d("AuraHmsService", "Received incoming HMS message from: ${message.from}")

        // 1. Process custom data payloads mapped by our back-end
        try {
            val data = message.dataOfMap
            if (data.isNotEmpty()) {
                Log.i("AuraHmsService", "HMS Data Payload: $data")
                orchestrator.triggerFromPushData(data.mapValues { it.value?.toString() ?: "" })
            }
        } catch (e: Exception) {
            Log.e("AuraHmsService", "Failed to parse HMS data payload: ${e.message}")
        }

        // 2. Process notifications
        message.notification?.let {
            Log.d("AuraHmsService", "HMS Notification Body: ${it.body}")
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
     * Triggered when HMS Core allocates a new push registration Token.
     */
    override fun onNewToken(token: String?) {
        super.onNewToken(token)
        if (token == null) return

        Log.i("AuraHmsService", "Generated new HMS Registration Token: $token")

        // Save the token locally to expose it via our JS Bridge's window.AuraNative.getPushToken()
        val sharedPreferences = getSharedPreferences("HomeOSPreferences", MODE_PRIVATE)
        sharedPreferences.edit().putString("hms_push_token", token).apply()
    }
}
