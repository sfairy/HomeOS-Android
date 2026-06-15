package com.homeos.app

import android.content.SharedPreferences
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

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
    const val APP_USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/125.0.0.0 Safari/537.36"  // 桌面版 User-Agent，确保服务端返回桌面布局而非手机版

    /**
     * 执行登录请求
     * @param baseUrl 服务器基础地址
     * @param user 用户名
     * @param pass 密码
     * @return 登录结果对象
     */
    fun performLogin(baseUrl: String, user: String, pass: String): HomeOSLoginResult {
        var connection: HttpURLConnection? = null
        return try {
            // 清理 URL 末尾的斜杠
            val cleanUrl = baseUrl.trimEnd('/')
            val url = URL("$cleanUrl/api/v1/auth/login")
            connection = url.openConnection() as HttpURLConnection
            
            // 设置连接超时时间
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", APP_USER_AGENT)
            connection.doOutput = true

            // 构建登录请求 JSON 参数
            val jsonParam = JSONObject().apply {
                put("username", user)
                put("password", pass)
            }

            // 发送请求
            connection.outputStream.use { os ->
                val bytes = jsonParam.toString().toByteArray(Charsets.UTF_8)
                os.write(bytes)
            }

            // 处理响应
            val responseCode = connection.responseCode
            val body = readResponseBody(connection, responseCode)

            // 检查 HTTP 状态码
            if (responseCode !in 200..299) {
                return HomeOSLoginResult(
                    success = false,
                    errorMessage = "HTTP $responseCode"
                )
            }

            // 解析 JSON 响应
            val jsonObj = runCatching { JSONObject(body) }.getOrNull()
            
            // 检查是否需要 MFA 验证
            if (jsonObj?.optBoolean("requiresMfa", false) == true) {
                return HomeOSLoginResult(
                    success = false,
                    requiresMfa = true,
                    username = jsonObj.optString("username", user),
                    errorMessage = "需要 MFA 验证"
                )
            }

            // 从响应头提取 Cookie
            val (authToken, csrfToken) = extractCookiesFromConnection(connection)
            if (authToken.isEmpty()) {
                return HomeOSLoginResult(
                    success = false,
                    errorMessage = "缺少 auth_token cookie"
                )
            }

            // 返回成功结果
            HomeOSLoginResult(
                success = true,
                authToken = authToken,
                csrfToken = csrfToken,
                username = jsonObj?.optString("username", user) ?: user,
                role = jsonObj?.optString("role", "") ?: ""
            )
        } catch (e: Exception) {
            // 捕获异常并返回失败结果
            HomeOSLoginResult(success = false, errorMessage = e.message)
        } finally {
            // 确保关闭连接
            connection?.disconnect()
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
        cookieManager.setCookie(baseUrl, "auth_token=$authToken; Path=/; HttpOnly")
        
        if (csrfToken.isNotEmpty()) {
            cookieManager.setCookie(baseUrl, "csrf_token=$csrfToken; Path=/")
        }
        
        cookieManager.flush()
    }

    /**
     * 保存认证令牌到 SharedPreferences
     * @param prefs SharedPreferences 实例
     * @param authToken 认证令牌
     * @param csrfToken CSRF 令牌
     * @param user 用户名
     * @param pass 密码
     */
    fun saveAuthTokens(
        prefs: SharedPreferences,
        authToken: String,
        csrfToken: String,
        user: String,
        pass: String
    ) {
        prefs.edit().apply {
            putString(KEY_AUTH_TOKEN, authToken)
            putString(KEY_CSRF_TOKEN, csrfToken)
            putString("auth_user", user)
            putString("auth_pass", pass)
            apply()
        }
    }

    /**
     * 清除所有认证令牌
     * @param prefs SharedPreferences 实例
     */
    fun clearAuthTokens(prefs: SharedPreferences) {
        prefs.edit().apply {
            remove(KEY_AUTH_TOKEN)
            remove(KEY_CSRF_TOKEN)
            remove("auth_user")
            remove("auth_pass")
            apply()
        }
    }

    /**
     * 加载认证令牌
     * @param prefs SharedPreferences 实例
     * @return 认证令牌字符串
     */
    fun loadAuthToken(prefs: SharedPreferences): String =
        prefs.getString(KEY_AUTH_TOKEN, "") ?: ""

    /**
     * 加载 CSRF 令牌
     * @param prefs SharedPreferences 实例
     * @return CSRF 令牌字符串
     */
    fun loadCsrfToken(prefs: SharedPreferences): String =
        prefs.getString(KEY_CSRF_TOKEN, "") ?: ""

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
     * 读取响应体内容
     * @param connection HTTP 连接
     * @param responseCode 响应状态码
     * @return 响应体字符串
     */
    private fun readResponseBody(connection: HttpURLConnection, responseCode: Int): String {
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return ""
        return stream.bufferedReader().use { it.readText() }
    }

    /**
     * 从 HTTP 连接中提取 Cookie
     * @param connection HTTP 连接
     * @return Pair(authToken, csrfToken)
     */
    private fun extractCookiesFromConnection(connection: HttpURLConnection): Pair<String, String> {
        var authToken = ""
        var csrfToken = ""
        var index = 0
        
        // 遍历所有响应头
        while (true) {
            val key = connection.getHeaderFieldKey(index) ?: break
            val value = connection.getHeaderField(index) ?: ""
            
            // 查找 Set-Cookie 头
            if (key.equals("Set-Cookie", ignoreCase = true)) {
                when {
                    value.startsWith("auth_token=") ->
                        authToken = parseCookieValue(value)
                    value.startsWith("csrf_token=") ->
                        csrfToken = parseCookieValue(value)
                }
            }
            index++
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