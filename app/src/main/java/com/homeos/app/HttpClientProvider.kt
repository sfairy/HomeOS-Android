package com.homeos.app

import okhttp3.ConnectionPool
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 全局共享的 OkHttpClient，复用连接池/线程池，避免每次请求新建连接。
 * 不同调用所需的超时通过 [withTimeouts] 基于共享实例派生（仍复用底层连接池）。
 */
object HttpClientProvider {

    private val base: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(4, 5, TimeUnit.MINUTES))
            .retryOnConnectionFailure(true)
            .build()
    }

    /**
     * 信任全部证书的 TrustManager，仅用于内网/私有地址（自签名证书场景）。
     * 公网流量不会走这里，仍使用系统默认校验，避免削弱安全性。
     */
    private val trustAllManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    /**
     * 信任自签名证书的客户端（仅供内网 HTTPS 使用）。
     * 复用 [base] 的连接池/调度器，仅替换 SSL 校验与主机名校验。
     */
    private val insecureBase: OkHttpClient by lazy {
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(trustAllManager), SecureRandom())
        }
        base.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustAllManager)
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    /**
     * 基于共享客户端派生指定超时的客户端（共享连接池/调度器）。
     * @param trustSelfSigned 为 true 时信任自签名/不受信证书，仅应在内网私有地址时开启。
     */
    fun withTimeouts(
        connectMs: Long,
        readMs: Long,
        callMs: Long = connectMs + readMs,
        trustSelfSigned: Boolean = false
    ): OkHttpClient =
        (if (trustSelfSigned) insecureBase else base).newBuilder()
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readMs, TimeUnit.MILLISECONDS)
            .callTimeout(callMs, TimeUnit.MILLISECONDS)
            .build()

    /** 解析 URL 后判断其主机是否为内网/私有地址。 */
    fun isPrivateHost(url: String): Boolean {
        val host = url.toHttpUrlOrNull()?.host ?: return false
        return isPrivateHostName(host)
    }

    /**
     * 判断主机名/IP 是否属于内网/私有范围：
     * RFC1918 私有段、回环、链路本地、CGNAT、localhost、单段主机名及常见内网后缀。
     */
    fun isPrivateHostName(host: String): Boolean {
        val h = host.lowercase().trim().trimEnd('.')
        if (h.isEmpty()) return false
        if (h == "localhost") return true
        if (h == "::1") return true

        parseIpv4(h)?.let { return isPrivateIpv4(it) }

        // IPv6 回环/唯一本地地址(ULA fc00::/7)/链路本地(fe80::/10)
        if (h.startsWith("fe8") || h.startsWith("fe9") || h.startsWith("fea") ||
            h.startsWith("feb") || h.startsWith("fc") || h.startsWith("fd")
        ) {
            return true
        }

        // 无点单段主机名（如 "homeos"），通常为内网设备
        if (!h.contains('.')) return true

        return h.endsWith(".local") || h.endsWith(".lan") || h.endsWith(".home") ||
            h.endsWith(".internal") || h.endsWith(".intranet") || h.endsWith(".localdomain")
    }

    private fun parseIpv4(host: String): IntArray? {
        val parts = host.split('.')
        if (parts.size != 4) return null
        val result = IntArray(4)
        for (i in parts.indices) {
            val value = parts[i].toIntOrNull() ?: return null
            if (value !in 0..255) return null
            result[i] = value
        }
        return result
    }

    private fun isPrivateIpv4(parts: IntArray): Boolean {
        val a = parts[0]
        val b = parts[1]
        return when {
            a == 10 -> true                 // 10.0.0.0/8
            a == 127 -> true                // 回环
            a == 169 && b == 254 -> true    // 链路本地 169.254.0.0/16
            a == 172 && b in 16..31 -> true // 172.16.0.0/12
            a == 192 && b == 168 -> true    // 192.168.0.0/16
            a == 100 && b in 64..127 -> true // CGNAT 100.64.0.0/10（如 Tailscale）
            else -> false
        }
    }
}
