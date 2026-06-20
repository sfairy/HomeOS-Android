package com.homeos.app

import android.content.SharedPreferences

/**
 * NativeBridge 所需 Activity 状态的抽象，避免强引用 Activity。
 */
interface NativeBridgeHost {
    val designWidth: Int
    val designHeight: Int
    val isKioskMode: Boolean
    fun currentScaleFactor(): Double
    fun getAppSharedPreferences(): SharedPreferences
    fun getNotificationOrchestrator(): NotificationOrchestrator
}
