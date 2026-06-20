package com.homeos.app

import android.content.Context
import android.media.RingtoneManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.webkit.JavascriptInterface
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.security.MessageDigest

/**
 * 安全、沙箱化的 JavaScript 接口，在 WebView 内部映射为 "window.Native"。
 * 使用 WeakReference 持有 Host，避免 Activity 泄漏。
 */
class NativeBridge(
    context: Context,
    host: NativeBridgeHost
) {
    private val appContext = context.applicationContext
    private val hostRef = WeakReference(host)

    @JavascriptInterface
    fun getHardwareFingerprint(): String {
        val androidId = Settings.Secure.getString(
            appContext.contentResolver,
            Settings.Secure.ANDROID_ID
        ) ?: "DEFAULT-HOMEOS-KEY"
        return sha256(androidId)
    }

    @JavascriptInterface
    fun getDeviceInfo(): String {
        val host = hostRef.get() ?: return "{}"
        val metrics = appContext.resources.displayMetrics
        return JSONObject().apply {
            put("model", Build.MODEL)
            put("manufacturer", Build.MANUFACTURER)
            put("osVersion", Build.VERSION.SDK_INT)
            put("appVersion", getAppVersion())
            put("isKioskMode", host.isKioskMode)
            put("platform", "android")
            put("screenWidthPx", metrics.widthPixels)
            put("screenHeightPx", metrics.heightPixels)
            put("density", metrics.density.toDouble())
            put("densityDpi", metrics.densityDpi)
            put("designWidth", host.designWidth)
            put("designHeight", host.designHeight)
            put("scaleFactor", host.currentScaleFactor())
        }.toString()
    }

    @JavascriptInterface
    fun getAppVersion(): String {
        return try {
            val packageInfo = appContext.packageManager.getPackageInfo(appContext.packageName, 0)
            packageInfo.versionName ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    @JavascriptInterface
    fun getPushToken(): String {
        val host = hostRef.get() ?: return ""
        return try {
            val prefs = host.getAppSharedPreferences()
            val fcmToken = prefs.getString("fcm_push_token", "") ?: ""
            if (fcmToken.isNotEmpty()) return fcmToken
            val hmsToken = prefs.getString("hms_push_token", "") ?: ""
            if (hmsToken.isNotEmpty()) return hmsToken
            try {
                FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        prefs.edit().putString("fcm_push_token", task.result).apply()
                    }
                }
            } catch (_: Exception) {
            }
            ""
        } catch (e: Exception) {
            Log.e("JSBridge", "获取推送令牌失败: ${e.message}")
            ""
        }
    }

    @JavascriptInterface
    fun getAppMode(): String {
        val host = hostRef.get() ?: return "companion"
        return if (host.isKioskMode) "kiosk" else "companion"
    }

    @JavascriptInterface
    fun playAlertSound(severity: String) {
        try {
            val soundUri = if (severity.equals("CRITICAL", ignoreCase = true)) {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
            } else {
                RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            }
            val ringtone = RingtoneManager.getRingtone(appContext, soundUri)
            ringtone?.play()
        } catch (e: Exception) {
            Log.e("JSBridge", "音频播放失败: ${e.message}")
        }
    }

    @JavascriptInterface
    fun acknowledgeAlert(alertId: String) {
        hostRef.get()?.getNotificationOrchestrator()?.cancelNotification(alertId)
    }

    private fun sha256(base: String): String {
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(base.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            base
        }
    }
}
