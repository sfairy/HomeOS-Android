package com.homeos.app

import android.content.SharedPreferences
import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * HomeOS backend authentication helpers.
 * The server uses HttpOnly Cookie auth (auth_token + csrf_token), not JSON access_token.
 */
data class HomeOsLoginResult(
    val success: Boolean,
    val authToken: String = "",
    val csrfToken: String = "",
    val username: String = "",
    val role: String = "",
    val requiresMfa: Boolean = false,
    val errorMessage: String? = null
)

object HomeOsAuth {
    const val KEY_AUTH_TOKEN = "auth_token"
    const val KEY_CSRF_TOKEN = "csrf_token"
    const val APP_USER_AGENT = "HomeOSApp/2026.06.15.07 (Android; Mobile)"

    fun performLogin(baseUrl: String, user: String, pass: String): HomeOsLoginResult {
        var connection: HttpURLConnection? = null
        return try {
            val cleanUrl = baseUrl.trimEnd('/')
            val url = URL("$cleanUrl/api/v1/auth/login")
            connection = url.openConnection() as HttpURLConnection
            connection.connectTimeout = 5000
            connection.readTimeout = 5000
            connection.requestMethod = "POST"
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("User-Agent", APP_USER_AGENT)
            connection.doOutput = true

            val jsonParam = JSONObject().apply {
                put("username", user)
                put("password", pass)
            }

            connection.outputStream.use { os ->
                val bytes = jsonParam.toString().toByteArray(Charsets.UTF_8)
                os.write(bytes)
            }

            val responseCode = connection.responseCode
            val body = readResponseBody(connection, responseCode)

            if (responseCode !in 200..299) {
                return HomeOsLoginResult(
                    success = false,
                    errorMessage = "HTTP $responseCode"
                )
            }

            val jsonObj = runCatching { JSONObject(body) }.getOrNull()
            if (jsonObj?.optBoolean("requiresMfa", false) == true) {
                return HomeOsLoginResult(
                    success = false,
                    requiresMfa = true,
                    username = jsonObj.optString("username", user),
                    errorMessage = "MFA required"
                )
            }

            val (authToken, csrfToken) = extractCookiesFromConnection(connection)
            if (authToken.isEmpty()) {
                return HomeOsLoginResult(
                    success = false,
                    errorMessage = "Missing auth_token cookie"
                )
            }

            HomeOsLoginResult(
                success = true,
                authToken = authToken,
                csrfToken = csrfToken,
                username = jsonObj?.optString("username", user) ?: user,
                role = jsonObj?.optString("role", "") ?: ""
            )
        } catch (e: Exception) {
            HomeOsLoginResult(success = false, errorMessage = e.message)
        } finally {
            connection?.disconnect()
        }
    }

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

    fun clearAuthTokens(prefs: SharedPreferences) {
        prefs.edit().apply {
            remove(KEY_AUTH_TOKEN)
            remove(KEY_CSRF_TOKEN)
            remove("auth_user")
            remove("auth_pass")
            apply()
        }
    }

    fun loadAuthToken(prefs: SharedPreferences): String =
        prefs.getString(KEY_AUTH_TOKEN, "") ?: ""

    fun loadCsrfToken(prefs: SharedPreferences): String =
        prefs.getString(KEY_CSRF_TOKEN, "") ?: ""

    fun buildCookieHeader(authToken: String, csrfToken: String = ""): String {
        val parts = mutableListOf<String>()
        if (authToken.isNotEmpty()) parts.add("auth_token=$authToken")
        if (csrfToken.isNotEmpty()) parts.add("csrf_token=$csrfToken")
        return parts.joinToString("; ")
    }

    private fun readResponseBody(connection: HttpURLConnection, responseCode: Int): String {
        val stream = if (responseCode in 200..299) {
            connection.inputStream
        } else {
            connection.errorStream
        } ?: return ""
        return stream.bufferedReader().use { it.readText() }
    }

    private fun extractCookiesFromConnection(connection: HttpURLConnection): Pair<String, String> {
        var authToken = ""
        var csrfToken = ""
        var index = 0
        while (true) {
            val key = connection.getHeaderFieldKey(index) ?: break
            val value = connection.getHeaderField(index) ?: ""
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

    private fun parseCookieValue(setCookie: String): String {
        val semicolon = setCookie.indexOf(';')
        val end = if (semicolon >= 0) semicolon else setCookie.length
        val equals = setCookie.indexOf('=')
        if (equals < 0 || equals >= end) return ""
        return setCookie.substring(equals + 1, end).trim()
    }
}
