package com.example.talktoai.connection

import android.content.Context
import android.content.SharedPreferences
import com.example.talktoai.dsh.connection.Credential
import com.example.talktoai.dsh.connection.CredentialStore
import com.example.talktoai.dsh.observability.DshLogger
import com.example.talktoai.dsh.observability.NoopLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android 端凭据存储（设计 §6 + §9）。
 *
 * 实现策略：
 *   - 优先通过反射使用 [androidx.security.crypto.EncryptedSharedPreferences]（KeyStore 保护）
 *   - 不可用时（依赖缺失 / ProGuard 误删）回退到 [PlainSharedPreferencesCredentialStore]
 *
 * 使用反射而非硬依赖的原因：
 *   - 当前项目 build.gradle.kts 未引入 androidx.security-crypto；P0-1 阶段保持依赖最小集
 *   - 当未来生产环境决定引入时，只需在 build.gradle.kts 加依赖即可，调用方式不变
 *
 * 安全约束：
 *   - 凭据序列化只使用结构化字段（deviceId / pairId / issuedAtMs / expiresAtMs），不含明文 token
 *   - 调用 [clear] 会从存储里删除 key
 *   - 凭据从不出现在日志里
 */
class SecureCredentialStore(
    context: Context,
    private val logger: DshLogger = NoopLogger,
) : CredentialStore {

    private val appContext = context.applicationContext

    private val securePrefs: SharedPreferences? by lazy {
        runCatching {
            createEncryptedPrefs(appContext)
        }.getOrElse { e ->
            logger.warn(TAG, com.example.talktoai.dsh.observability.TraceContext.EMPTY,
                "EncryptedSharedPreferences unavailable: ${e.message}; falling back to plain")
            null
        }
    }

    private val fallback: CredentialStore by lazy {
        PlainSharedPreferencesCredentialStore(appContext)
    }

    override suspend fun read(): Credential? = withContext(Dispatchers.IO) {
        val p = securePrefs
        if (p != null) {
            val raw = p.getString(KEY_CRED, null) ?: return@withContext null
            decode(raw)
        } else {
            fallback.read()
        }
    }

    override suspend fun write(credential: Credential) {
        withContext(Dispatchers.IO) {
            val p = securePrefs
            if (p != null) {
                p.edit().putString(KEY_CRED, encode(credential)).apply()
            } else {
                fallback.write(credential)
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            val p = securePrefs
            if (p != null) {
                p.edit().remove(KEY_CRED).apply()
            } else {
                fallback.clear()
            }
        }
    }

    /**
     * 反射创建 [androidx.security.crypto.EncryptedSharedPreferences]。
     * 当 androidx.security-crypto 未在 classpath 时抛 NoClassDefFoundError → 调用方降级。
     */
    private fun createEncryptedPrefs(ctx: Context): SharedPreferences {
        val masterKeyCls = Class.forName("androidx.security.crypto.MasterKey")
        val masterKeyBuilderCls = Class.forName("androidx.security.crypto.MasterKey\$Builder")
        val keySchemeCls = Class.forName("androidx.security.crypto.MasterKey\$KeyScheme")
        val aes256Gcm = keySchemeCls.getField("AES256_GCM").get(null)

        val masterKey = masterKeyBuilderCls.getConstructor(Context::class.java)
            .newInstance(ctx)
            .let { builder ->
                masterKeyBuilderCls.getMethod("setKeyScheme", keySchemeCls).invoke(builder, aes256Gcm)
                masterKeyBuilderCls.getMethod("build").invoke(builder)
            }

        val espCls = Class.forName("androidx.security.crypto.EncryptedSharedPreferences")
        val prefKeyCls = Class.forName("androidx.security.crypto.EncryptedSharedPreferences\$PrefKeyEncryptionScheme")
        val prefValueCls = Class.forName("androidx.security.crypto.EncryptedSharedPreferences\$PrefValueEncryptionScheme")
        val aes256Siv = prefKeyCls.getField("AES256_SIV").get(null)
        val aes256GcmVal = prefValueCls.getField("AES256_GCM").get(null)

        val createMethod = espCls.getMethod(
            "create",
            Context::class.java,
            String::class.java,
            masterKeyCls,
            prefKeyCls,
            prefValueCls,
        )
        return createMethod.invoke(null, ctx, PREF_FILE, masterKey, aes256Siv, aes256GcmVal) as SharedPreferences
    }

    private fun encode(c: Credential): String = when (c) {
        is Credential.LoopbackToken -> "loop|${c.deviceId}|${c.issuedAtMs}"
        is Credential.DevToken -> "dev|${c.deviceId}|${c.issuedAtMs}"
        is Credential.PairedLanToken ->
            "lan|${c.deviceId}|${c.pairId}|${c.issuedAtMs}|${c.expiresAtMs}"
    }

    private fun decode(raw: String): Credential? {
        val parts = raw.split("|")
        return runCatching {
            when (parts.getOrNull(0)) {
                "loop" -> Credential.LoopbackToken(
                    deviceId = parts[1],
                    issuedAtMs = parts[2].toLong(),
                )
                "dev" -> Credential.DevToken(
                    deviceId = parts[1],
                    issuedAtMs = parts[2].toLong(),
                )
                "lan" -> Credential.PairedLanToken(
                    deviceId = parts[1],
                    pairId = parts[2],
                    issuedAtMs = parts[3].toLong(),
                    expiresAtMs = parts[4].toLong(),
                )
                else -> null
            }
        }.getOrNull()
    }

    companion object {
        private const val TAG = "SecureCredStore"
        private const val PREF_FILE = "dsh_secure_cred_v1"
        private const val KEY_CRED = "cred"
    }
}

/**
 * 降级实现：明文 SharedPreferences。设计 §6 注明"开发/调试通道可临时使用；
 * 正式发布应当强制使用 [SecureCredentialStore] 走 EncryptedSharedPreferences"。
 */
class PlainSharedPreferencesCredentialStore(context: Context) : CredentialStore {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)

    override suspend fun read(): Credential? = withContext(Dispatchers.IO) {
        val raw = prefs.getString(KEY_CRED, null) ?: return@withContext null
        decode(raw)
    }

    override suspend fun write(credential: Credential) {
        withContext(Dispatchers.IO) {
            prefs.edit().putString(KEY_CRED, encode(credential)).apply()
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            prefs.edit().remove(KEY_CRED).apply()
        }
    }

    private fun encode(c: Credential): String = when (c) {
        is Credential.LoopbackToken -> "loop|${c.deviceId}|${c.issuedAtMs}"
        is Credential.DevToken -> "dev|${c.deviceId}|${c.issuedAtMs}"
        is Credential.PairedLanToken ->
            "lan|${c.deviceId}|${c.pairId}|${c.issuedAtMs}|${c.expiresAtMs}"
    }

    private fun decode(raw: String): Credential? {
        val parts = raw.split("|")
        return runCatching {
            when (parts.getOrNull(0)) {
                "loop" -> Credential.LoopbackToken(
                    deviceId = parts[1],
                    issuedAtMs = parts[2].toLong(),
                )
                "dev" -> Credential.DevToken(
                    deviceId = parts[1],
                    issuedAtMs = parts[2].toLong(),
                )
                "lan" -> Credential.PairedLanToken(
                    deviceId = parts[1],
                    pairId = parts[2],
                    issuedAtMs = parts[3].toLong(),
                    expiresAtMs = parts[4].toLong(),
                )
                else -> null
            }
        }.getOrNull()
    }

    companion object {
        private const val PREF_FILE = "dsh_plain_cred_v1"
        private const val KEY_CRED = "cred"
    }
}
