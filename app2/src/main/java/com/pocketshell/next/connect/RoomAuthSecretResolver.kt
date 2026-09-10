package com.pocketshell.next.connect

import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.transport.AuthSecretResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves the transport's credential *references* against the app's real
 * secret storage (rewrite task M-3): the Room `ssh_keys` metadata row plus the
 * private-key file it points at.
 *
 * The DB never holds key material — [com.pocketshell.core.storage.entity.SshKeyEntity]
 * stores `privateKeyPath` and a `hasPassphrase` flag, and the PEM itself lives
 * on disk in app-private storage.
 *
 * ## Credential handoff
 *
 * - **Passphrase-protected keys stay encrypted on disk.** The native unlock
 *   surface calls [rememberPassphrase] with a user-entered `CharArray`; the
 *   resolver returns a copy to sshj for one dial and [clearPassphrase] wipes
 *   its process-local copy when that attempt has completed. No passphrase is
 *   written to Room, a preference, or a log.
 * - **Password auth has no producer.** The `hosts` schema has a non-null
 *   `keyId` FK and no password column, so nothing can construct an
 *   [com.pocketshell.core.transport.AuthMaterial.Password]. [resolvePassword]
 *   therefore raises [PasswordAuthUnsupportedException]; supporting it is a
 *   schema change, not a resolver change.
 *
 * Every failure here is thrown, not returned: `RealHostConnectionFactory`
 * catches it and turns it into a `ConnectResult.Failed` carrying this
 * exception's message, so the message is user-facing.
 */
class RoomAuthSecretResolver(
    private val sshKeyDao: SshKeyDao,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AuthSecretResolver, SshKeyUnlocker {

    private val transientPassphrases = ConcurrentHashMap<Long, CharArray>()

    override suspend fun resolvePrivateKeyPem(keyId: Long): String = withContext(dispatcher) {
        val key = sshKeyDao.getById(keyId)
            ?: throw MissingSshKeyException("No SSH key row for id $keyId")

        val file = File(key.privateKeyPath)
        if (!file.isFile) {
            throw MissingSshKeyException(
                "SSH key \"${key.name}\" (id $keyId) is missing its private key file " +
                    "at ${key.privateKeyPath}",
            )
        }
        file.readText()
    }

    override suspend fun resolvePrivateKeyPassphrase(keyId: Long): CharArray? = withContext(dispatcher) {
        val key = sshKeyDao.getById(keyId)
            ?: throw MissingSshKeyException("No SSH key row for id $keyId")
        if (!key.hasPassphrase) return@withContext null

        transientPassphrases[keyId]?.copyOf()
            ?: throw PassphraseRequiredException(keyId, key.name)
    }

    override suspend fun resolvePassword(secretRef: String): CharArray =
        throw PasswordAuthUnsupportedException(secretRef)

    override fun rememberPassphrase(keyId: Long, value: CharArray) {
        val replacement = value.copyOf()
        transientPassphrases.put(keyId, replacement)?.fill('\u0000')
    }

    override fun copyPassphrase(keyId: Long): CharArray? =
        transientPassphrases[keyId]?.copyOf()

    override fun clearPassphrase(keyId: Long) {
        transientPassphrases.remove(keyId)?.fill('\u0000')
    }
}

/** The `ssh_keys` row, or the file it points at, is gone. */
class MissingSshKeyException(message: String) : IOException(message)

/**
 * The key is encrypted and the user has not supplied its passphrase for this
 * connection attempt. Typed so the connect flow can show a real passphrase
 * handoff rather than a generic authentication failure.
 */
class PassphraseRequiredException(
    val keyId: Long,
    val keyName: String,
) : IOException(
    "SSH key \"$keyName\" (id $keyId) is passphrase-protected; " +
        "unlock it before connecting",
)

/** Password authentication has no storage backing in the current schema. */
class PasswordAuthUnsupportedException(
    val secretRef: String,
) : IOException("Password authentication is not supported (secret ref \"$secretRef\")")
