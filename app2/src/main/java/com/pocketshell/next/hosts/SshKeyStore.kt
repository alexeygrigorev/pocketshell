package com.pocketshell.next.hosts

import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.SshKeyEntity
import java.io.File
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * Writes SSH private keys to app-private storage and registers them in
 * `ssh_keys` (rewrite task P-6).
 *
 * The split the schema already assumes: the DB row holds metadata only, and the
 * PEM lives on disk at [SshKeyEntity.privateKeyPath] — which is exactly what
 * [com.pocketshell.next.connect.RoomAuthSecretResolver] reads back at dial
 * time, so "generated a key" and "can authenticate with it" are the same fact.
 *
 * [keysDir] is injected rather than derived from a `Context` so this class has
 * no Android dependency at all and a test can point it at a temp folder;
 * production supplies `filesDir/ssh-keys`, the same location the old client
 * used, so a cutover install finds its keys where they already are.
 *
 * Two rules the flows above rely on:
 * - **Byte-identical keys are deduplicated** by [SshKeyMaterial.fingerprint].
 *   Importing the same QR twice reuses the row instead of writing a second copy
 *   of the same secret under a `-<suffix>` name.
 * - **Encrypted keys are rejected** ([EncryptedKeyUnsupportedException]).
 *   app2 has no unlock flow (biometric passphrase entry was cut from P-6), so
 *   accepting one would store a key that can only fail later, at connect time,
 *   with the user two screens away from the cause.
 */
class SshKeyStore(
    private val keysDir: File,
    private val sshKeyDao: SshKeyDao,
    private val dispatcher: CoroutineDispatcher,
) {

    /**
     * Register [pem] under [name]. Returns the persisted row — either a freshly
     * inserted one or the existing row for the same key material.
     *
     * @throws NotAPrivateKeyException when the text is not a private-key PEM.
     * @throws EncryptedKeyUnsupportedException when the key is passphrase-protected.
     */
    suspend fun importKey(name: String, pem: String): SshKeyEntity {
        val trimmed = pem.trim()
        if (!SshKeyMaterial.looksLikePrivateKey(trimmed)) throw NotAPrivateKeyException()
        if (SshKeyMaterial.isEncrypted(trimmed)) throw EncryptedKeyUnsupportedException()
        return persist(name, trimmed)
    }

    /**
     * Generate a fresh key pair on-device and register it. [name] defaults to a
     * timestamped label so generating several in a row stays readable in the
     * list.
     */
    suspend fun generateKey(
        name: String = "generated-${System.currentTimeMillis()}",
    ): SshKeyEntity {
        val pem = withContext(dispatcher) { SshKeyMaterial.generateRsaPrivateKeyPem() }
        return persist(name, pem.trim())
    }

    /** The `ssh_keys` row for [keyId], or `null` if it is gone. */
    suspend fun lookup(keyId: Long): SshKeyEntity? = sshKeyDao.getById(keyId)

    /** The PEM this row points at, or `null` if the file is gone. */
    suspend fun readPem(key: SshKeyEntity): String? = withContext(dispatcher) {
        File(key.privateKeyPath).takeIf { it.isFile }?.readText()
    }

    /**
     * Remove the key: the file first, then the row.
     *
     * That order matters. File-then-row means a failure leaves a row the user
     * can retry from; row-then-file would orphan the secret on disk with
     * nothing left pointing at it. Hosts referencing the key cascade-delete via
     * the FK on `hosts.keyId`, which is why the UI confirms first.
     */
    suspend fun deleteKey(key: SshKeyEntity) {
        withContext(dispatcher) {
            val file = File(key.privateKeyPath)
            if (file.exists() && !file.delete()) {
                throw IOException("Could not delete key file: ${file.absolutePath}")
            }
        }
        sshKeyDao.delete(key)
    }

    private suspend fun persist(name: String, pem: String): SshKeyEntity =
        withContext(dispatcher) {
            val fingerprint = SshKeyMaterial.fingerprint(pem)
            val existing = sshKeyDao.getByFingerprint(fingerprint)
            if (existing != null) {
                // The row survived but the file did not (an uninstall of the
                // file cache, a failed write). Re-materialise it rather than
                // handing back a row that cannot authenticate.
                val file = File(existing.privateKeyPath)
                if (!file.exists()) {
                    file.parentFile?.mkdirs()
                    writeKeyFile(file, pem)
                }
                return@withContext existing
            }

            keysDir.mkdirs()
            val target = uniqueFile(sanitiseName(name))
            writeKeyFile(target, pem)
            val row = SshKeyEntity(
                name = target.name,
                privateKeyPath = target.absolutePath,
                fingerprint = fingerprint,
                // Always false: importKey rejects encrypted material and
                // generateKey never produces any.
                hasPassphrase = false,
            )
            row.copy(id = sshKeyDao.insert(row))
        }

    private fun uniqueFile(safeName: String): File {
        val direct = File(keysDir, safeName)
        if (!direct.exists()) return direct
        return File(keysDir, "$safeName-${UUID.randomUUID().toString().take(SUFFIX_LENGTH)}")
    }

    /**
     * A key's display name doubles as its filename, and the name can come from a
     * scanned QR payload, so path separators are stripped — a payload naming its
     * key `../../databases/pocketshell.db` must not choose where the write
     * lands.
     */
    private fun sanitiseName(name: String): String = name
        .substringAfterLast('/')
        .substringAfterLast('\\')
        .trim()
        .ifBlank { "imported-key" }

    private fun writeKeyFile(target: File, pem: String) {
        target.writeText(pem, Charsets.UTF_8)
        // Best effort: app-private storage is already owner-only on Android,
        // this narrows it further and is a no-op where the filesystem refuses.
        runCatching {
            target.setReadable(false, false)
            target.setReadable(true, true)
            target.setWritable(false, false)
            target.setWritable(true, true)
        }
    }

    private companion object {
        const val SUFFIX_LENGTH = 8
    }
}

/** The supplied text is not a private-key PEM. */
class NotAPrivateKeyException : IOException(
    "That does not look like an SSH private key " +
        "(no -----BEGIN ... PRIVATE KEY----- block)",
)

/** The key is passphrase-protected and app2 has no unlock flow. */
class EncryptedKeyUnsupportedException : IOException(
    "That key is passphrase-protected. PocketShell cannot unlock encrypted " +
        "keys yet — import an unencrypted key, or generate one here.",
)
