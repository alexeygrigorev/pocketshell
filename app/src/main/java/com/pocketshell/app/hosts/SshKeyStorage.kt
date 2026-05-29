package com.pocketshell.app.hosts

import android.content.Context
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.UUID

object SshKeyStorage {
    suspend fun persistKey(
        context: Context,
        sshKeyDao: SshKeyDao,
        name: String,
        content: String,
        hasPassphrase: Boolean = hasPrivateKeyPassphrase(content),
    ): SshKeyEntity {
        val trimmed = content.trim()
        require(looksLikePrivateKey(trimmed)) {
            "Payload does not contain a supported SSH private key"
        }

        return withContext(Dispatchers.IO) {
            val fingerprint = fingerprintFor(trimmed)
            val existing = sshKeyDao.getByFingerprint(fingerprint)
            if (existing != null) {
                val existingFile = File(existing.privateKeyPath)
                if (!existingFile.exists()) {
                    existingFile.parentFile?.mkdirs()
                    writePrivateKeyFile(existingFile, trimmed)
                }
                return@withContext existing
            }

            val safeName = name
                .substringAfterLast("/")
                .substringAfterLast("\\")
                .trim()
                .ifBlank { "imported-key" }
            val keyDir = File(context.filesDir, "ssh-keys")
            keyDir.mkdirs()
            val target = if (File(keyDir, safeName).exists()) {
                File(keyDir, "$safeName-${UUID.randomUUID().toString().take(8)}")
            } else {
                File(keyDir, safeName)
            }
            writePrivateKeyFile(target, trimmed)
            val entity = SshKeyEntity(
                name = target.name,
                privateKeyPath = target.absolutePath,
                fingerprint = fingerprint,
                hasPassphrase = hasPassphrase,
            )
            entity.copy(id = sshKeyDao.insert(entity))
        }
    }

    fun fingerprintFor(content: String): String {
        val bytes = MessageDigest.getInstance("SHA-256")
            .digest(content.trim().toByteArray(Charsets.UTF_8))
        return "sha256:${bytes.toHex()}"
    }

    fun looksLikePrivateKey(content: String): Boolean {
        val trimmed = content.trim()
        return trimmed.startsWith("-----BEGIN") &&
            trimmed.contains("PRIVATE KEY") &&
            trimmed.lineSequence().any { it.trim().startsWith("-----END") && it.contains("PRIVATE KEY") }
    }

    fun hasPrivateKeyPassphrase(content: String): Boolean {
        val lines = content.lineSequence().map { it.trim() }.toList()
        return lines.any { it == "Proc-Type: 4,ENCRYPTED" } ||
            lines.any { it.startsWith("DEK-Info:", ignoreCase = true) } ||
            lines.any { it == "-----BEGIN ENCRYPTED PRIVATE KEY-----" } ||
            hasEncryptedOpenSshPrivateKey(lines)
    }

    private fun hasEncryptedOpenSshPrivateKey(lines: List<String>): Boolean {
        val begin = lines.indexOf("-----BEGIN OPENSSH PRIVATE KEY-----")
        val end = lines.indexOf("-----END OPENSSH PRIVATE KEY-----")
        if (begin < 0 || end <= begin) return false
        val body = lines.subList(begin + 1, end).joinToString("")
        val decoded = runCatching {
            java.util.Base64.getMimeDecoder().decode(body)
        }.getOrNull() ?: return false
        val magic = "openssh-key-v1\u0000".toByteArray(Charsets.US_ASCII)
        if (decoded.size < magic.size || !decoded.copyOfRange(0, magic.size).contentEquals(magic)) {
            return false
        }
        var offset = magic.size
        val cipherName = decoded.readOpenSshString(offset) ?: return false
        offset = cipherName.nextOffset
        val kdfName = decoded.readOpenSshString(offset) ?: return false
        return cipherName.value != "none" || kdfName.value != "none"
    }

    private data class OpenSshString(val value: String, val nextOffset: Int)

    private fun ByteArray.readOpenSshString(offset: Int): OpenSshString? {
        if (offset < 0 || offset + 4 > size) return null
        val length = java.nio.ByteBuffer.wrap(this, offset, 4).int
        if (length < 0 || offset + 4 + length > size) return null
        val start = offset + 4
        return OpenSshString(
            value = copyOfRange(start, start + length).toString(Charsets.US_ASCII),
            nextOffset = start + length,
        )
    }

    private fun writePrivateKeyFile(target: File, content: String) {
        target.writeText(content, Charsets.UTF_8)
        runCatching {
            target.setReadable(false, false)
            target.setReadable(true, true)
            target.setWritable(false, false)
            target.setWritable(true, true)
        }
    }

    private fun ByteArray.toHex(): String {
        val digits = "0123456789abcdef"
        val chars = CharArray(size * 2)
        forEachIndexed { index, byte ->
            val value = byte.toInt() and 0xff
            chars[index * 2] = digits[value ushr 4]
            chars[index * 2 + 1] = digits[value and 0x0f]
        }
        return String(chars)
    }
}
