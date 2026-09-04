package com.example.talktoai.dsh.connection

enum class ConnectionTopology {
    LOCAL_PROCESS,
    USB_DEBUG,
    LAN,
    INTERNET,
}

sealed class Credential {
    data class LoopbackToken(val deviceId: String, val issuedAtMs: Long) : Credential()
    data class PairedLanToken(
        val deviceId: String,
        val pairId: String,
        val issuedAtMs: Long,
        val expiresAtMs: Long,
    ) : Credential()
    data class DevToken(val deviceId: String, val issuedAtMs: Long) : Credential()
}

interface CredentialStore {
    suspend fun read(): Credential?
    suspend fun write(credential: Credential)
    suspend fun clear()
}

interface DevicePairing {
    suspend fun startPairing(): PairingHandle
    suspend fun awaitPairing(handle: PairingHandle, timeoutMs: Long): PairingResult
    suspend fun revoke(deviceId: String)
}

data class PairingHandle(val pairingId: String, val shortCode: String, val expiresAtMs: Long)

sealed class PairingResult {
    data class Success(val credential: Credential) : PairingResult()
    data object Timeout : PairingResult()
    data class Failed(val reason: String) : PairingResult()
}

data class HostEndpoint(
    val topology: ConnectionTopology,
    val host: String,
    val port: Int,
    val useTls: Boolean,
    val credential: Credential?,
) {
    init {
        require(port in 1..65535) { "Invalid port: $port" }
        when (topology) {
            ConnectionTopology.LOCAL_PROCESS -> {
                require(host == "127.0.0.1" || host == "localhost" || host.startsWith("unix://")) {
                    "LOCAL_PROCESS must use loopback host"
                }
            }
            ConnectionTopology.USB_DEBUG -> {
                require(useTls || host == "127.0.0.1") { "USB_DEBUG requires loopback" }
            }
            ConnectionTopology.LAN -> {
                require(useTls) { "LAN must use TLS" }
                require(credential is Credential.PairedLanToken) { "LAN requires paired credential" }
            }
            ConnectionTopology.INTERNET -> error("INTERNET not supported in v1")
        }
    }
}
