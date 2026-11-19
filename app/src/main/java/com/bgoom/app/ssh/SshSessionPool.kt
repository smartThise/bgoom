package com.bgoom.app.ssh

import com.bgoom.app.data.DeviceStore
import com.bgoom.app.data.SshDevice
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.PublicKey
import java.util.concurrent.ConcurrentHashMap

class HostKeyMismatchException(
    val host: String,
    val expectedFingerprint: String,
    val actualFingerprint: String,
) : IOException("主机 $host 指纹不匹配！预期 $expectedFingerprint，实际 $actualFingerprint")

/**
 * Persistent keepalive SSH session pool per device.
 * Eliminates repeated TCP/SSH handshakes so notes & clipboard sync in <100ms.
 * Strictly enforces public-key authentication only (no password auth ever attempted).
 */
class SshSessionPool(private val deviceStore: DeviceStore) {

    private data class PooledConnection(
        val client: SSHClient,
        val hostFingerprint: String,
        val configSignature: String,
    )

    private val mutexMap = ConcurrentHashMap<String, Mutex>()
    private val pool = ConcurrentHashMap<String, PooledConnection>()

    private fun mutexFor(deviceId: String): Mutex =
        mutexMap.computeIfAbsent(deviceId) { Mutex() }

    private fun signatureOf(device: SshDevice): String =
        "${device.host}:${device.port}:${device.username}:${device.trustedHostFingerprint}:${device.keyFingerprint}"

    suspend fun <T> withClient(
        device: SshDevice,
        block: (client: SSHClient, hostFingerprint: String) -> T,
    ): T = mutexFor(device.id).withLock {
        val existing = pool[device.id]
        val sig = signatureOf(device)
        if (existing != null && existing.configSignature == sig && existing.client.isConnected && existing.client.isAuthenticated) {
            try {
                return@withLock block(existing.client, existing.hostFingerprint)
            } catch (e: IOException) {
                // Pooled connection may have gone stale after network switch; reconnect cleanly once
                closeQuietly(existing.client)
                pool.remove(device.id)
            }
        }

        val fresh = connectFresh(device)
        pool[device.id] = fresh
        try {
            block(fresh.client, fresh.hostFingerprint)
        } catch (e: IOException) {
            closeQuietly(fresh.client)
            pool.remove(device.id)
            throw e
        }
    }

    fun disconnect(deviceId: String) {
        pool.remove(deviceId)?.let { closeQuietly(it.client) }
    }

    fun disconnectAll() {
        val keys = pool.keys.toList()
        for (k in keys) disconnect(k)
    }

    fun isConnected(deviceId: String): Boolean {
        val conn = pool[deviceId] ?: return false
        return conn.client.isConnected && conn.client.isAuthenticated
    }

    private fun connectFresh(device: SshDevice): PooledConnection {
        val privateKey = deviceStore.getPrivateKey(device.id)
            ?: throw IllegalStateException("设备「${device.name}」尚未配置 SSH 私钥（严禁密码登录）")
        val passphrase = deviceStore.getPassphrase(device.id)

        val client = SSHClient()
        client.connectTimeout = 10_000
        client.timeout = 20_000

        var observedFingerprint = ""
        val expectedFp = device.trustedHostFingerprint?.takeIf { it.isNotBlank() }

        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val buf = Buffer.PlainBuffer()
                buf.putPublicKey(key)
                val fp = SshKeyManager.sha256Fingerprint(buf.compactData)
                observedFingerprint = fp
                if (expectedFp == null) {
                    // TOFU: Trust on first use & pin immediately
                    return true
                }
                if (expectedFp != fp) {
                    throw HostKeyMismatchException(hostname, expectedFp, fp)
                }
                return true
            }

            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })

        try {
            client.connect(device.host.trim(), device.port)
            val keyProvider = SshKeyManager.loadKeyProvider(client, privateKey, passphrase)
            // STRICTLY public-key auth only — authPassword is never called anywhere in BGOOM
            client.authPublickey(device.username, keyProvider)
            client.connection.keepAlive.keepAliveInterval = 20

            if (expectedFp == null && observedFingerprint.isNotBlank()) {
                deviceStore.updateTrustedHostFingerprint(device.id, observedFingerprint)
            }
            return PooledConnection(
                client = client,
                hostFingerprint = observedFingerprint.ifBlank { expectedFp ?: "" },
                configSignature = signatureOf(device.copy(trustedHostFingerprint = observedFingerprint.ifBlank { expectedFp })),
            )
        } catch (t: Throwable) {
            closeQuietly(client)
            throw t
        }
    }

    private fun closeQuietly(client: SSHClient) {
        runCatching { client.disconnect() }
        runCatching { client.close() }
    }

    companion object {
        /**
         * Diagnostic error explainer incorporating real-world mobile proxy/DDNS lessons.
         */
        fun diagnoseError(t: Throwable, device: SshDevice): String {
            val chain = generateSequence(t) { it.cause }.toList()
            val fullMsg = chain.joinToString(" | ") { "${it.javaClass.simpleName}: ${it.message}" }

            val mismatch = chain.filterIsInstance<HostKeyMismatchException>().firstOrNull()
            if (mismatch != null) {
                return "主机指纹已变更（疑似重装或中间人攻击）：预期 ${mismatch.expectedFingerprint}，实际 ${mismatch.actualFingerprint}。如确认设备重装，请在设备编辑页重置指纹。"
            }
            if (chain.any { it is UnknownHostException }) {
                return "域名解析失败（${device.host}），请检查手机网络或 DDNS 记录。"
            }
            if (chain.any { it is SocketTimeoutException || it is ConnectException }) {
                return "无法连接到 ${device.host}:${device.port}，请检查目标设备是否在线、端口转发及防火墙。"
            }
            if (chain.any { it is EOFException } ||
                fullMsg.contains("Connection reset", ignoreCase = true) ||
                fullMsg.contains("closed before authentication", ignoreCase = true)
            ) {
                return "认证前连接被中断（并非公钥错误）。若手机开启了代理/VPN，请将 ${device.host} 加入直连（DIRECT）规则后重试。"
            }
            if (chain.any { it is UserAuthException }) {
                return "公钥认证被拒绝（用户 ${device.username}）。请确认私钥口令正确，且对应公钥已写入远端 ~/.ssh/authorized_keys。"
            }
            if (fullMsg.contains("passphrase", ignoreCase = true) || fullMsg.contains("decrypt", ignoreCase = true)) {
                return "私钥解密失败，请检查私钥口令是否正确。"
            }
            return t.message ?: fullMsg
        }
    }
}
