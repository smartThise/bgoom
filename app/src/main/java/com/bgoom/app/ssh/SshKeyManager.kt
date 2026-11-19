package com.bgoom.app.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.keyprovider.KeyProvider
import net.schmizz.sshj.userauth.password.PasswordUtils
import org.bouncycastle.crypto.generators.Ed25519KeyPairGenerator
import org.bouncycastle.crypto.params.Ed25519KeyGenerationParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.util.OpenSSHPrivateKeyUtil
import org.bouncycastle.crypto.util.OpenSSHPublicKeyUtil
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

data class GeneratedKeyPair(
    val privateKeyOpenSsh: String,
    val publicKeyAuthorizedLine: String,
    val fingerprintSha256: String,
)

data class ValidatedKeyInfo(
    val keyType: String,
    val fingerprintSha256: String,
    val requiresPassphrase: Boolean,
)

/**
 * Generates standard OpenSSH Ed25519 keypairs and validates imported SSH private keys.
 * Enforces key-only SSH authentication — password login is intentionally unsupported.
 */
object SshKeyManager {

    fun generateEd25519KeyPair(comment: String = "bgoom@android"): GeneratedKeyPair {
        val random = SecureRandom()
        val gen = Ed25519KeyPairGenerator()
        gen.init(Ed25519KeyGenerationParameters(random))
        val pair = gen.generateKeyPair()
        val priv = pair.private as Ed25519PrivateKeyParameters
        val pub = pair.public as Ed25519PublicKeyParameters

        // Encode standard OpenSSH public key wire blob ("ssh-ed25519" + 32-byte pubkey)
        val pubWire = OpenSSHPublicKeyUtil.encodePublicKey(pub)
        val pubBase64 = Base64.getEncoder().encodeToString(pubWire)
        val authorizedLine = "ssh-ed25519 $pubBase64 $comment"

        // Encode standard OpenSSH private key ("openssh-key-v1\0" format)
        val privBytes = OpenSSHPrivateKeyUtil.encodePrivateKey(priv)
        val privPem = wrapPem("OPENSSH PRIVATE KEY", privBytes)

        val fp = sha256Fingerprint(pubWire)
        return GeneratedKeyPair(
            privateKeyOpenSsh = privPem,
            publicKeyAuthorizedLine = authorizedLine,
            fingerprintSha256 = fp,
        )
    }

    /**
     * Validate an imported private key (OpenSSH Ed25519/RSA/ECDSA or PEM) with optional passphrase.
     */
    fun validatePrivateKey(privateKeyText: String, passphrase: String?): Result<ValidatedKeyInfo> {
        val trimmed = privateKeyText.trim()
        if (trimmed.isEmpty()) {
            return Result.failure(IllegalArgumentException("私钥内容不能为空（严禁使用密码登录）"))
        }
        if (!trimmed.contains("PRIVATE KEY")) {
            return Result.failure(IllegalArgumentException("未检测到标准 SSH 私钥头（-----BEGIN ... PRIVATE KEY-----）"))
        }
        return runCatching {
            val client = SSHClient()
            val provider: KeyProvider = loadKeyProvider(client, trimmed, passphrase)
            val pubKey = provider.public
            val keyType = provider.type.toString()
            val wire = encodePublicKeyForFingerprint(pubKey)
            ValidatedKeyInfo(
                keyType = keyType,
                fingerprintSha256 = sha256Fingerprint(wire),
                requiresPassphrase = !passphrase.isNullOrEmpty(),
            )
        }
    }

    fun loadKeyProvider(
        client: SSHClient,
        privateKeyText: String,
        passphrase: String?,
    ): KeyProvider {
        val pf = if (!passphrase.isNullOrEmpty()) {
            PasswordUtils.createOneOff(passphrase.toCharArray())
        } else {
            null
        }
        return client.loadKeys(privateKeyText.trim(), null, pf)
    }

    fun sha256Fingerprint(wireBytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(wireBytes)
        val b64 = Base64.getEncoder().withoutPadding().encodeToString(digest)
        return "SHA256:$b64"
    }

    private fun encodePublicKeyForFingerprint(pubKey: java.security.PublicKey): ByteArray {
        val buf = net.schmizz.sshj.common.Buffer.PlainBuffer()
        buf.putPublicKey(pubKey)
        return buf.compactData
    }

    private fun wrapPem(label: String, derOrRaw: ByteArray): String {
        val b64 = Base64.getEncoder().encodeToString(derOrRaw)
        val sb = StringBuilder()
        sb.append("-----BEGIN ").append(label).append("-----\n")
        var i = 0
        while (i < b64.length) {
            val end = minOf(i + 70, b64.length)
            sb.append(b64, i, end).append('\n')
            i = end
        }
        sb.append("-----END ").append(label).append("-----\n")
        return sb.toString()
    }
}
