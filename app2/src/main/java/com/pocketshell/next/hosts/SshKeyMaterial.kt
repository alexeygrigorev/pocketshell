package com.pocketshell.next.hosts

import java.nio.ByteBuffer
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.util.Base64

/**
 * Pure private-key helpers: recognise a PEM, detect an encrypted one, hash one,
 * and generate a fresh one (rewrite task P-6).
 *
 * Deliberately free of Android and of Room — everything here is a function of a
 * `String`, so the key rules are unit-testable on the plain JVM and
 * [SshKeyStore] is left with nothing but IO. The logic is ported from the old
 * client's `SshKeyStorage`, which mixed the two.
 *
 * ## Passphrases
 *
 * app2 has no unlock flow: the biometric-gated passphrase prompt was cut from
 * P-6 by the maintainer, and
 * [com.pocketshell.next.connect.RoomAuthSecretResolver] refuses to hand sshj a
 * key it cannot decrypt. So an encrypted key is rejected at the door
 * ([isEncrypted]) rather than stored with `hasPassphrase = true` and failing
 * later at connect time, where the user has no way to act on it. Everything
 * this module writes has `hasPassphrase = false`.
 */
object SshKeyMaterial {

    /** Shape check: a PEM block whose type mentions a private key. */
    fun looksLikePrivateKey(content: String): Boolean {
        val trimmed = content.trim()
        return trimmed.startsWith("-----BEGIN") &&
            trimmed.contains("PRIVATE KEY") &&
            trimmed.lineSequence().any {
                it.trim().startsWith("-----END") && it.contains("PRIVATE KEY")
            }
    }

    /**
     * `true` when the PEM is passphrase-protected.
     *
     * Three encodings have to be recognised, because the header alone is not
     * enough for the modern one:
     * - classic PEM encryption (`Proc-Type: 4,ENCRYPTED` / `DEK-Info:`),
     * - PKCS#8 (`-----BEGIN ENCRYPTED PRIVATE KEY-----`),
     * - OpenSSH's own container, whose header is identical whether or not the
     *   key is encrypted — the cipher/KDF names inside the base64 body are the
     *   only signal, which is why the body is parsed here.
     */
    fun isEncrypted(content: String): Boolean {
        val lines = content.lineSequence().map { it.trim() }.toList()
        return lines.any { it == "Proc-Type: 4,ENCRYPTED" } ||
            lines.any { it.startsWith("DEK-Info:", ignoreCase = true) } ||
            lines.any { it == "-----BEGIN ENCRYPTED PRIVATE KEY-----" } ||
            isEncryptedOpenSshKey(lines)
    }

    /**
     * Content hash of the trimmed PEM, used to reuse an existing `ssh_keys` row
     * for a byte-identical key instead of writing a second copy of the same
     * secret to disk (re-importing the same QR twice is the normal case).
     */
    fun fingerprint(content: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(content.trim().toByteArray(Charsets.UTF_8))
        return "sha256:" + bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generate an RSA-3072 key pair and return the private half as PKCS#8 PEM —
     * the same shape `ssh-keygen -m PKCS8` emits, which sshj's key-file
     * autodetection reads.
     *
     * RSA rather than Ed25519 for the same reason the old client picked it:
     * `KeyPairGenerator` ships RSA on every Android image, while Ed25519 needs a
     * BouncyCastle provider registered on the platform security stack. 3072 bits
     * matches the current `ssh-keygen` default strength.
     */
    fun generateRsaPrivateKeyPem(): String {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(RSA_KEY_BITS)
        val encoded = generator.generateKeyPair().private.encoded // PKCS#8 for RSA
        val body = Base64.getEncoder().encodeToString(encoded).chunked(PEM_LINE_LENGTH)
        return buildString {
            appendLine("-----BEGIN PRIVATE KEY-----")
            body.forEach { appendLine(it) }
            appendLine("-----END PRIVATE KEY-----")
        }
    }

    private const val RSA_KEY_BITS = 3072
    private const val PEM_LINE_LENGTH = 64

    private fun isEncryptedOpenSshKey(lines: List<String>): Boolean {
        val begin = lines.indexOf("-----BEGIN OPENSSH PRIVATE KEY-----")
        val end = lines.indexOf("-----END OPENSSH PRIVATE KEY-----")
        if (begin < 0 || end <= begin) return false
        val body = lines.subList(begin + 1, end).joinToString("")
        val decoded = runCatching { Base64.getMimeDecoder().decode(body) }.getOrNull() ?: return false
        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        if (decoded.size < magic.size || !decoded.copyOfRange(0, magic.size).contentEquals(magic)) {
            return false
        }
        val cipher = decoded.readOpenSshString(magic.size) ?: return false
        val kdf = decoded.readOpenSshString(cipher.nextOffset) ?: return false
        // An unencrypted OpenSSH key names both as "none"; anything else means
        // the private section is wrapped in a passphrase-derived cipher.
        return cipher.value != "none" || kdf.value != "none"
    }

    private data class OpenSshString(val value: String, val nextOffset: Int)

    private fun ByteArray.readOpenSshString(offset: Int): OpenSshString? {
        if (offset < 0 || offset + 4 > size) return null
        val length = ByteBuffer.wrap(this, offset, 4).int
        if (length < 0 || offset + 4 + length > size) return null
        val start = offset + 4
        return OpenSshString(
            value = copyOfRange(start, start + length).toString(Charsets.US_ASCII),
            nextOffset = start + length,
        )
    }
}
