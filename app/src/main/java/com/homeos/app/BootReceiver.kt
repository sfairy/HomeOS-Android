package com.homeos.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log

/**
 * BroadcastReceiver that intercepts the system startup boot event.
 * Automatically restarts background Socket.IO monitoring services on device boot.
 * Critical for industrial, wall-mounted Kiosk tablets where zero manual operation is expected.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Log.i("AuraBootReceiver", "Device boot completed event captured. Auto-starting background monitors...")

            // Retrieve saved settings configuration to pass active server addresses
            val sharedPreferences = context.getSharedPreferences("HomeOSPreferences", Context.MODE_PRIVATE)
            
            // Strict check: if user has not accepted privacy policy yet, do not start foreground service
            val privacyAccepted = sharedPreferences.getBoolean("privacy_accepted", false)
            if (!privacyAccepted) {
                Log.w("AuraBootReceiver", "Privacy Policy not accepted yet. Aborting auto-start of services on boot.")
                return
            }

            val lanUrl = sharedPreferences.getString("server_lan_url", "http://10.0.0.90:8501") ?: "http://10.0.0.90:8501"
            val authToken = HomeOsAuth.loadAuthToken(sharedPreferences)
            val csrfToken = HomeOsAuth.loadCsrfToken(sharedPreferences)

            val serviceIntent = Intent(context, AuraSocketService::class.java).apply {
                putExtra("SERVER_URL", lanUrl)
                putExtra("AUTH_TOKEN", authToken)
                putExtra("CSRF_TOKEN", csrfToken)
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Log.i("AuraBootReceiver", "AuraSocketService launched successfully from BootReceiver.")
            } catch (e: Exception) {
                Log.e("AuraBootReceiver", "Failed to start service on boot: ${e.message}")
            }
        }
    }
}
