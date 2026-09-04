package com.example.talktoai

import android.content.Context
import com.example.talktoai.connection.AndroidDeviceIdProvider
import com.example.talktoai.connection.PlainSharedPreferencesCredentialStore
import com.example.talktoai.connection.SecureCredentialStore
import com.example.talktoai.dsh.client.DshAppContext
import com.example.talktoai.dsh.client.InMemoryFakeRemote
import com.example.talktoai.dsh.connection.Credential
import com.example.talktoai.dsh.connection.CredentialStore
import com.example.talktoai.dsh.connection.HostEndpoint
import com.example.talktoai.dsh.connection.ConnectionTopology
import com.example.talktoai.dsh.observability.DshLogger
import com.example.talktoai.dsh.observability.TraceContext
import java.util.concurrent.atomic.AtomicReference

/**
 * Native 侧 DshAppContext 容器（设计 §2.1）。
 *
 * 当前默认装配 [InMemoryFakeRemote]，便于在没有真实 DSH Host 的情况下演示 / 跑测；
 * 真实部署时只需把 [InMemoryFakeRemote] 替换为基于 Ktor/OkHttp 的 Web Remote 实现。
 *
 * 凭据 / deviceId 走 Android 端真实实现：
 *   - 优先 [SecureCredentialStore]（EncryptedSharedPreferences）
 *   - 不可用时降级到 [PlainSharedPreferencesCredentialStore]（仅 dev）
 */
object DshClientHolder {

    private val ctxRef = AtomicReference<DshAppContext?>(null)
    @Volatile private var appContextRef: Context? = null

    fun attachApplicationContext(context: Context) {
        appContextRef = context.applicationContext
    }

    fun getOrCreate(): DshAppContext {
        ctxRef.get()?.let { return it }
        synchronized(this) {
            ctxRef.get()?.let { return it }
            val ctx = appContextRef
                ?: error("DshClientHolder.attachApplicationContext() must be called from Application.onCreate")
            val deviceId = AndroidDeviceIdProvider(ctx).deviceId()
            val credentialStore: CredentialStore = runCatching {
                SecureCredentialStore(ctx)
            }.getOrElse { e ->
                android.util.Log.w("DshClientHolder",
                    "SecureCredentialStore unavailable, falling back to plain store: ${e.message}")
                PlainSharedPreferencesCredentialStore(ctx)
            }
            val app = DshAppContext(
                remote = InMemoryFakeRemote(),
                credentialStore = credentialStore,
                deviceId = deviceId,
                expectedHostVersion = "0.1.2-alpha.2",
                appVersion = "1.0.0",
                rawLogger = AndroidLogger,
            )
            ctxRef.set(app)
            return app
        }
    }

    /**
     * 暴露给 [KRBridgeModule] 的入口；当前 [endpoint] 暂为本地 loopback 占位，
     * 真实连接 / pairing 走 LAN/USB 流程（设计 §6）。
     */
    suspend fun ensureConnected(): DshAppContext {
        val ctx = getOrCreate()
        val ep = HostEndpoint(
            topology = ConnectionTopology.LOCAL_PROCESS,
            host = "127.0.0.1",
            port = 8000,
            useTls = false,
            credential = Credential.LoopbackToken(deviceIdFromContext(), 0L),
        )
        if (ctx.currentConnectionGeneration() == 0L) {
            ctx.connect(ep)
        }
        return ctx
    }

    private fun deviceIdFromContext(): String {
        val ctx = appContextRef ?: return "android-unknown"
        return AndroidDeviceIdProvider(ctx).deviceId()
    }
}

private object AndroidLogger : DshLogger {
    override fun debug(tag: String, ctx: TraceContext, message: String) {
        android.util.Log.d(tag, format(tag, ctx, message))
    }
    override fun info(tag: String, ctx: TraceContext, message: String) {
        android.util.Log.i(tag, format(tag, ctx, message))
    }
    override fun warn(tag: String, ctx: TraceContext, message: String, error: Throwable?) {
        android.util.Log.w(tag, format(tag, ctx, message), error)
    }
    override fun error(tag: String, ctx: TraceContext, message: String, error: Throwable?) {
        android.util.Log.e(tag, format(tag, ctx, message), error)
    }
    private fun format(tag: String, ctx: TraceContext, msg: String): String {
        val sid = ctx.sessionId?.let { "sid=$it" } ?: ""
        val rid = ctx.requestId?.let { " req=$it" } ?: ""
        val seq = ctx.seq?.let { " seq=$it" } ?: ""
        return "[gen=${ctx.connectionGeneration}]$sid$rid$seq $msg"
    }
}
