package com.pocketshell.next.hosts

import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.dao.SshKeyDao
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Turns a scanned / pasted `pocketshell.ssh-import.v1` payload into rows
 * (rewrite task P-6).
 *
 * The whole import policy lives here rather than in a ViewModel, because it is
 * the same policy for every entry point — the camera scanner, and a QR image
 * picked from storage when the camera is unavailable — and because it is the
 * part worth testing without a UI.
 *
 * Decoding is [SshImportPayloadCodec]'s job; this class owns what happens
 * afterwards:
 * - a `privateKey` payload has its key material persisted through
 *   [SshKeyStore] (deduplicated by fingerprint, encrypted keys refused);
 * - a `keyRef` payload resolves the named key locally and fails clearly if the
 *   device does not have it, instead of writing a host that cannot dial;
 * - an inbound host matching an existing `(hostname, port, username)` is
 *   reported as [ImportOutcome.AlreadyPresent] rather than silently duplicated
 *   or silently overwritten. The old client raised a three-way
 *   overwrite/skip/add-new dialog here; a re-scan of a host you already have is
 *   the overwhelmingly common case and "you already have it" is the answer for
 *   all of them, so app2 does not carry the dialog.
 */
class HostImporter(
    private val hostDao: HostDao,
    private val sshKeyDao: SshKeyDao,
    private val keyStore: SshKeyStore,
    private val dispatcher: CoroutineDispatcher,
) {

    /**
     * Import a raw payload — either the bare JSON, or a single-part
     * [QrChunkCodec] envelope around it.
     *
     * A multi-part envelope is refused with an instruction rather than an
     * error: only the scanner can accumulate parts, so a single chunk arriving
     * through any other path is a user mistake, not a corrupt payload.
     */
    suspend fun import(raw: String): ImportOutcome = withContext(dispatcher) {
        val payload = raw.trim()
        val json = if (QrChunkCodec.isEnvelope(payload)) {
            val part = QrChunkCodec.decodePart(payload).getOrElse {
                return@withContext ImportOutcome.Failed(it.message ?: "Could not decode QR envelope")
            }
            if (part.total != 1) {
                return@withContext ImportOutcome.Failed(
                    "This is part ${part.part} of ${part.total}. " +
                        "Use Scan QR so every part can be combined.",
                )
            }
            String(part.chunk, Charsets.UTF_8)
        } else {
            payload
        }

        val config = SshImportPayloadCodec.decode(json).getOrElse {
            return@withContext ImportOutcome.Failed(it.message ?: "Could not read the shared host")
        }

        val keyId = when (val auth = config.auth) {
            is SshImportAuth.PrivateKey ->
                runCatching { keyStore.importKey(auth.name, auth.privateKeyPem) }
                    .getOrElse {
                        return@withContext ImportOutcome.Failed(
                            it.message ?: "Could not import the SSH key",
                        )
                    }.id

            is SshImportAuth.KeyReference -> sshKeyDao.getByName(auth.name)?.id
                ?: return@withContext ImportOutcome.Failed(
                    "Add the SSH key named \"${auth.name}\" before importing this host",
                )
        }

        val existing = hostDao.getAll().first().firstOrNull {
            it.hostname.equals(config.host, ignoreCase = true) &&
                it.port == config.port &&
                it.username == config.username
        }
        if (existing != null) return@withContext ImportOutcome.AlreadyPresent(existing.name)

        val hostId = hostDao.insert(
            HostEntity(
                name = config.name,
                hostname = config.host,
                port = config.port,
                username = config.username,
                keyId = keyId,
                enabled = false,
            ),
        )
        ImportOutcome.Imported(name = config.name, hostId = hostId)
    }
}

/** What an import attempt did. Every branch carries what the user should be told. */
sealed interface ImportOutcome {

    /** A new host row was written. */
    data class Imported(val name: String, val hostId: Long) : ImportOutcome

    /** The same `user@host:port` is already configured; nothing was written. */
    data class AlreadyPresent(val name: String) : ImportOutcome

    /** Nothing was written. [message] is user-facing. */
    data class Failed(val message: String) : ImportOutcome
}
