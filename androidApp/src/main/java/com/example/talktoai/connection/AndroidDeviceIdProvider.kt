package com.example.talktoai.connection

import android.content.Context
import android.provider.Settings
import com.example.talktoai.dsh.observability.DshLogger
import com.example.talktoai.dsh.observability.NoopLogger
import java.util.UUID

/**
 * Android 设备 ID 提供器（设计 §6）。
 *
 * 要求：
 *   - 同一设备每次启动 ID 必须稳定，便于 Host 关联凭据
 *   - 不能用 IMEI / MAC 等不可重置的硬件标识（隐私）
 *   - 重装 App 时可重新生成（用 SharedPreferences 持久化随机 UUID）
 *
 * 实现：首次启动生成 UUIDv4，存入普通 SharedPreferences；后续启动读出。
 * 不放 KeyStore 是因为 deviceId 本身不是高敏感凭据；安全强度由后续连接凭据（[SecureCredentialStore]）承担。
 */
class AndroidDeviceIdProvider(
    context: Context,
    private val logger: DshLogger = NoopLogger,
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    fun deviceId(): String {
        prefs.getString(KEY, null)?.let { existing ->
            logger.debug(TAG, com.example.talktoai.dsh.observability.TraceContext.EMPTY,
                "deviceId loaded from prefs len=${existing.length}")
            return existing
        }
        val fresh = generateStableId()
        prefs.edit().putString(KEY, fresh).apply()
        logger.info(TAG, com.example.talktoai.dsh.observability.TraceContext.EMPTY,
            "deviceId generated and persisted")
        return fresh
    }

    fun regenerate(): String {
        val fresh = generateStableId()
        prefs.edit().putString(KEY, fresh).apply()
        logger.warn(TAG, com.example.talktoai.dsh.observability.TraceContext.EMPTY,
            "deviceId regenerated (operator action)")
        return fresh
    }

    /**
     * 使用 [Settings.Secure.ANDROID_ID] 作为种子生成稳定 ID。
     * 如果 ANDROID_ID 不可用（例如单元测试），回退到 UUIDv4。
     */
    private fun generateStableId(): String {
        return try {
            val androidId = Settings.Secure.ANDROID_ID
            if (androidId.isNullOrBlank()) {
                UUID.randomUUID().toString()
            } else {
                // 用 ANDROID_ID 作为命名空间生成 UUIDv5（确定性 + 跨启动稳定）
                val name = UUID.nameUUIDFromBytes(androidId.toByteArray())
                "and-${name}"
            }
        } catch (e: Throwable) {
            UUID.randomUUID().toString()
        }
    }

    companion object {
        private const val TAG = "DeviceIdProvider"
        private const val PREF_FILE = "dsh_device_id_v1"
        private const val KEY = "device_id"
    }
}
