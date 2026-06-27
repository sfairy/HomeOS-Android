package com.homeos.app

import okhttp3.ConnectionPool
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

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

    /** 基于共享客户端派生指定超时的客户端（共享连接池/调度器）。 */
    fun withTimeouts(connectMs: Long, readMs: Long, callMs: Long = connectMs + readMs): OkHttpClient =
        base.newBuilder()
            .connectTimeout(connectMs, TimeUnit.MILLISECONDS)
            .readTimeout(readMs, TimeUnit.MILLISECONDS)
            .writeTimeout(readMs, TimeUnit.MILLISECONDS)
            .callTimeout(callMs, TimeUnit.MILLISECONDS)
            .build()
}
