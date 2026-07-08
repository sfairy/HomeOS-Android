package com.homeos.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * 开机广播接收器：拦截系统启动完成事件
 * 在设备开机时自动重启后台 WebSocket 监控服务
 * 对于工业挂墙大屏设备至关重要，实现零人工干预自动启动
 */
class BootReceiver : BroadcastReceiver() {

    /**
     * 接收到广播时调用
     * @param context 上下文
     * @param intent 广播意图
     */
    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        // 检查是否为开机完成事件
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("BootReceiver", "捕获到设备开机完成事件。正在自动启动后台监控服务...")

            // 获取保存的配置信息
            val sharedPreferences = context.getSharedPreferences("HomeOSPreferences", Context.MODE_PRIVATE)
            
            // 严格检查：用户未接受隐私政策则不启动前台服务
            val privacyAccepted = sharedPreferences.getBoolean("privacy_accepted", false)
            if (!privacyAccepted) {
                Log.w("BootReceiver", "隐私政策尚未接受。取消开机自动启动服务。")
                return
            }

            // 严格检查：设备未配置则不启动服务
            val isConfigured = sharedPreferences.getBoolean("is_configured", false)
            if (!isConfigured) {
                Log.w("BootReceiver", "设备尚未配置。取消开机自动启动服务。")
                return
            }

            // 获取服务器地址和认证令牌
            val lanUrl = sharedPreferences.getString("server_lan_url", "http://10.0.0.90:8501") ?: "http://10.0.0.90:8501"
            val authToken = HomeOSAuth.loadAuthToken(context)
            val csrfToken = HomeOSAuth.loadCsrfToken(context)

            // 创建服务启动意图
            val serviceIntent = Intent(context, SocketService::class.java).apply {
                putExtra(SocketService.EXTRA_SERVER_URL, lanUrl)
                putExtra(SocketService.EXTRA_AUTH_TOKEN, authToken)
                putExtra(SocketService.EXTRA_CSRF_TOKEN, csrfToken)
                putExtra(SocketService.EXTRA_APP_FOREGROUND, false)
            }

            // 启动服务（根据 Android 版本选择不同方式）
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.i("BootReceiver", "SocketService 从 BootReceiver 启动成功。")
            } catch (e: Exception) {
                Log.e("BootReceiver", "开机启动服务失败：${e.message}")
            }
        }
    }
}