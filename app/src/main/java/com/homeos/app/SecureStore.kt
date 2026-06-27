package com.homeos.app

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 敏感凭据（账号、密码、认证/CSRF 令牌）的加密存储。
 *
 * 使用 Android Keystore 支撑的 EncryptedSharedPreferences 落盘，
 * 取代此前明文写入 SharedPreferences 的做法（与隐私协议中“加密隔离存储”的承诺一致）。
 * 首次访问时会自动从旧的明文 "HomeOSPreferences" 迁移并清除明文残留。
 * 若设备 Keystore 异常导致加密初始化失败，则回退到普通存储以保证可用性（降级而非崩溃）。
 */
object SecureStore {

    private const val TAG = "SecureStore"
    private const val SECURE_FILE = "homeos_secure_store"
    private const val FALLBACK_FILE = "homeos_secure_fallback"
    private const val LEGACY_FILE = "HomeOSPreferences"
    private const val MIGRATION_FLAG = "secure_migrated_v1"

    private val SENSITIVE_KEYS = arrayOf(
        HomeOSAuth.KEY_AUTH_USER,
        HomeOSAuth.KEY_AUTH_PASS,
        HomeOSAuth.KEY_AUTH_TOKEN,
        HomeOSAuth.KEY_CSRF_TOKEN
    )

    @Volatile
    private var prefs: SharedPreferences? = null

    private fun store(context: Context): SharedPreferences {
        prefs?.let { return it }
        return synchronized(this) {
            prefs?.let { return it }
            val created = createPrefs(context.applicationContext)
            migrateIfNeeded(context.applicationContext, created)
            prefs = created
            created
        }
    }

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            buildEncrypted(context)
        } catch (e: Exception) {
            // 部分 ROM 的 Keystore 主密钥可能损坏，导致解密失败。
            // 先清除损坏的加密文件再重建一次（代价：旧密文丢失，需重新登录，由自动重登录兜底）。
            Log.e(TAG, "加密存储初始化失败，尝试清除后重建: ${e.message}")
            try {
                context.deleteSharedPreferences(SECURE_FILE)
                buildEncrypted(context)
            } catch (e2: Exception) {
                Log.e(TAG, "加密存储重建仍失败，回退到普通存储: ${e2.message}")
                context.getSharedPreferences(FALLBACK_FILE, Context.MODE_PRIVATE)
            }
        }
    }

    private fun buildEncrypted(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            context,
            SECURE_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun migrateIfNeeded(context: Context, secure: SharedPreferences) {
        if (secure.getBoolean(MIGRATION_FLAG, false)) return
        val legacy = context.getSharedPreferences(LEGACY_FILE, Context.MODE_PRIVATE)

        secure.edit().apply {
            for (key in SENSITIVE_KEYS) {
                val value = legacy.getString(key, null)
                if (!value.isNullOrEmpty()) putString(key, value)
            }
            putBoolean(MIGRATION_FLAG, true)
            apply()
        }

        // 清除明文存储中的敏感残留。
        legacy.edit().apply {
            for (key in SENSITIVE_KEYS) remove(key)
            apply()
        }
        Log.i(TAG, "敏感凭据已迁移至加密存储。")
    }

    fun getString(context: Context, key: String): String =
        store(context).getString(key, "") ?: ""

    fun putStrings(context: Context, values: Map<String, String>) {
        store(context).edit().apply {
            values.forEach { (k, v) -> putString(k, v) }
            apply()
        }
    }

    fun remove(context: Context, vararg keys: String) {
        store(context).edit().apply {
            keys.forEach { remove(it) }
            apply()
        }
    }
}
