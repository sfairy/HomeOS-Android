package com.homeos.app

import android.content.Context
import android.webkit.CookieManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * HomeOS 后端认证工具类
 * 服务器使用 HttpOnly Cookie 认证方式（auth_token + csrf_token），而非 JSON access_token
 */
data class HomeOSLoginResult(
    val success: Boolean,           // 登录是否成功
    val authToken: String = "",     // 认证令牌
    val csrfToken: String = "",     // CSRF 令牌
    val username: String = "",      // 用户名
    val role: String = "",          // 用户角色
    val requiresMfa: Boolean = false, // 是否需要 MFA 验证
    val errorMessage: String? = null // 错误信息
)

object HomeOSAuth {
    const val KEY_AUTH_TOKEN = "auth_token"  // 认证令牌存储键名
    const val KEY_CSRF_TOKEN = "csrf_token"  // CSRF 令牌存储键名
    const val KEY_AUTH_USER = "auth_user"    // 账号存储键名
    const val KEY_AUTH_PASS = "auth_pass"    // 密码存储键名
    const val APP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"  // 桌面版 User-Agent，确保服务端返回桌面布局而非手机版
    private const val COOKIE_MAX_AGE_SECONDS = 60 * 60 * 24 * 30  // 持久化 Cookie 有效期（30 天）
    private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

    /**
     * 执行登录请求
     * @param baseUrl 服务器基础地址
     * @param user 用户名
     * @param pass 密码
     * @return 登录结果对象
     */
    fun performLogin(baseUrl: String, user: String, pass: String): HomeOSLoginResult {
        return try {
            val cleanUrl = baseUrl.trimEnd('/')
            val jsonParam = JSONObject().apply {
                put("username", user)
                put("password", pass)
            }
            val request = Request.Builder()
                .url("$cleanUrl/api/v1/auth/login")
                .header("User-Agent", APP_USER_AGENT)
                .post(jsonParam.toString().toRequestBody(JSON_MEDIA_TYPE))
                .build()

            val client = HttpClientProvider.withTimeouts(connectMs = 5000, readMs = 5000)
            client.newCall(request).execute().use { response ->
                val body = response.body?.string() ?: ""

                if (!response.isSuccessful) {
                    return HomeOSLoginResult(success = false, errorMessage = "HTTP ${response.code}")
                }

                val jsonObj = runCatching { JSONObject(body) }.getOrNull()

                if (jsonObj?.optBoolean("requiresMfa", false) == true) {
                    return HomeOSLoginResult(
                        success = false,
                        requiresMfa = true,
                        username = jsonObj.optString("username", user),
                        errorMessage = "需要 MFA 验证"
                    )
                }

                val (authToken, csrfToken) = extractCookies(response.headers("Set-Cookie"))
                if (authToken.isEmpty()) {
                    return HomeOSLoginResult(success = false, errorMessage = "缺少 auth_token cookie")
                }

                HomeOSLoginResult(
                    success = true,
                    authToken = authToken,
                    csrfToken = csrfToken,
                    username = jsonObj?.optString("username", user) ?: user,
                    role = jsonObj?.optString("role", "") ?: ""
                )
            }
        } catch (e: Exception) {
            HomeOSLoginResult(success = false, errorMessage = e.message)
        }
    }

    /**
     * 将认证 Cookie 注入到 WebView
     * @param baseUrl 服务器基础地址
     * @param authToken 认证令牌
     * @param csrfToken CSRF 令牌
     */
    fun injectCookies(baseUrl: String, authToken: String, csrfToken: String) {
        if (authToken.isEmpty()) return
        
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        // 设置 Max-Age 使其成为持久化 Cookie，应用进程重启后 WebView 仍能保留会话，
        // 避免每次冷启动都退回到登录页。
        cookieManager.setCookie(baseUrl, "auth_token=$authToken; Path=/; Max-Age=$COOKIE_MAX_AGE_SECONDS; HttpOnly")

        if (csrfToken.isNotEmpty()) {
            cookieManager.setCookie(baseUrl, "csrf_token=$csrfToken; Path=/; Max-Age=$COOKIE_MAX_AGE_SECONDS")
        }
        
        cookieManager.flush()
    }

    /**
     * 清除所有认证凭据（令牌 + 账号密码）。
     */
    fun clearAuthTokens(context: Context) {
        SecureStore.remove(context, KEY_AUTH_TOKEN, KEY_CSRF_TOKEN, KEY_AUTH_USER, KEY_AUTH_PASS)
    }

    /** 加载认证令牌（来自加密存储）。 */
    fun loadAuthToken(context: Context): String =
        SecureStore.getString(context, KEY_AUTH_TOKEN)

    /** 加载 CSRF 令牌（来自加密存储）。 */
    fun loadCsrfToken(context: Context): String =
        SecureStore.getString(context, KEY_CSRF_TOKEN)

    /** 加载已保存的账号。 */
    fun loadUsername(context: Context): String =
        SecureStore.getString(context, KEY_AUTH_USER)

    /** 加载已保存的密码。 */
    fun loadPassword(context: Context): String =
        SecureStore.getString(context, KEY_AUTH_PASS)

    /** 保存账号与密码到加密存储。 */
    fun saveCredentials(context: Context, user: String, pass: String) {
        SecureStore.putStrings(context, mapOf(KEY_AUTH_USER to user, KEY_AUTH_PASS to pass))
    }

    /** 保存认证/CSRF 令牌到加密存储。 */
    fun saveTokens(context: Context, authToken: String, csrfToken: String) {
        SecureStore.putStrings(context, mapOf(KEY_AUTH_TOKEN to authToken, KEY_CSRF_TOKEN to csrfToken))
    }

    /**
     * 构建 Cookie 请求头
     * @param authToken 认证令牌
     * @param csrfToken CSRF 令牌（可选）
     * @return Cookie 头字符串
     */
    fun buildCookieHeader(authToken: String, csrfToken: String = ""): String {
        val parts = mutableListOf<String>()
        if (authToken.isNotEmpty()) parts.add("auth_token=$authToken")
        if (csrfToken.isNotEmpty()) parts.add("csrf_token=$csrfToken")
        return parts.joinToString("; ")
    }

    /**
     * 从 Set-Cookie 响应头列表中提取认证/CSRF 令牌。
     * @return Pair(authToken, csrfToken)
     */
    private fun extractCookies(setCookieValues: List<String>): Pair<String, String> {
        var authToken = ""
        var csrfToken = ""
        for (value in setCookieValues) {
            when {
                value.startsWith("auth_token=") -> authToken = parseCookieValue(value)
                value.startsWith("csrf_token=") -> csrfToken = parseCookieValue(value)
            }
        }
        return authToken to csrfToken
    }

    /**
     * 解析 Cookie 值
     * @param setCookie Set-Cookie 头内容
     * @return Cookie 值
     */
    private fun parseCookieValue(setCookie: String): String {
        val semicolon = setCookie.indexOf(';')
        val end = if (semicolon >= 0) semicolon else setCookie.length
        val equals = setCookie.indexOf('=')
        if (equals < 0 || equals >= end) return ""
        return setCookie.substring(equals + 1, end).trim()
    }
}