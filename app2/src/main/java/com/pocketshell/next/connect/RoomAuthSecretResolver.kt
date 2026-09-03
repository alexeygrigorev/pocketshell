package com.pocketshell.next.connect

import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.transport.AuthSecretResolver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Resolves the transport's credential *references* against the app's real
 * secret storage (rewrite task M-3): the Room `ssh_keys` metadata row plus the
 * private-key file it points at.
 *
 * The DB never holds key material — [com.pocketshell.core.storage.entity.SshKeyEntity]
 * stores `privateKeyPath` and a `hasPassphrase` flag, and the PEM itself lives
 * on disk in app-private storage.
 *
 * ## Known limitations (both deliberate, both typed rather than silent)
 *
 * - **Passphrase-protected keys are not supported yet.** Decrypting one needs
 *   the biometric-gated unlock flow that arrives with rewrite task P-6. Until
 *   then a key with `hasPassphrase = true` raises
 *   [PassphraseRequiredException] instead of handing sshj a PEM it cannot
 *   load (which would surface as an opaque auth failure) or blocking on a
 *   prompt that does not exist.
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
) : AuthSecretResolver {

    override suspend fun resolvePrivateKeyPem(keyId: Long): String = withContext(dispatcher) {
        val key = sshKeyDao.getById(keyId)
            ?: throw MissingSshKeyException("No SSH key row for id $keyId")

        if (key.hasPassphrase) {
            throw PassphraseRequiredException(keyId, key.name)
        }

        val file = File(key.privateKeyPath)
        if (!file.isFile) {
            throw MissingSshKeyException(
                "SSH key \"${key.name}\" (id $keyId) is missing its private key file " +
                    "at ${key.privateKeyPath}",
            )
        }
        file.readText()
    }

    override suspend fun resolvePassword(secretRef: String): CharArray =
        throw PasswordAuthUnsupportedException(secretRef)
}

/** The `ssh_keys` row, or the file it points at, is gone. */
class MissingSshKeyException(message: String) : IOException(message)

/**
 * The key is encrypted and the app cannot unlock it yet (rewrite task P-6
 * brings the biometric-gated unlock). Typed so the connect flow can show a
 * "this key needs a passphrase" path rather than a generic auth failure.
 */
class PassphraseRequiredException(
    val keyId: Long,
    val keyName: String,
) : IOException(
    "SSH key \"$keyName\" (id $keyId) is passphrase-protected; " +
        "unlocking encrypted keys is not supported yet",
)

/** Password authentication has no storage backing in the current schema. */
class PasswordAuthUnsupportedException(
    val secretRef: String,
) : IOException("Password authentication is not supported (secret ref \"$secretRef\")")
