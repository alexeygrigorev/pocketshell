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
 *   [SshKeyStore] (deduplicated by fingerprint, encrypted keys retained);
 * - a `keyRef` payload resolves the named key locally and fails clearly if the
 *   device does not have it, instead of writing a host that cannot dial;
 * - an inbound host matching an existing `(hostname, port, username)` is
 *   resolved by the explicit [DuplicateAction] chosen on the review screen;
 *   there is no silent overwrite or silent duplicate.
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
    suspend fun findExisting(config: SshImportConfig): ExistingHost? = withContext(dispatcher) {
        findExistingInDb(config)
    }

    /**
     * Import a reviewed payload. Direct callers default to [DuplicateAction.Skip]
     * for backwards-compatible safety; the QR review surface always supplies
     * the user's explicit choice before it calls this method.
     */
    suspend fun import(
        raw: String,
        duplicateAction: DuplicateAction = DuplicateAction.Skip,
    ): ImportOutcome = withContext(dispatcher) {
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

        val existing = findExistingInDb(config)
        if (existing != null && duplicateAction == DuplicateAction.Skip) {
            return@withContext ImportOutcome.AlreadyPresent(existing.name, existing.id)
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

        if (existing != null && duplicateAction == DuplicateAction.Replace) {
            val current = hostDao.getById(existing.id)
                ?: return@withContext ImportOutcome.Failed(
                    "The existing host disappeared while importing",
                )
            // This is an explicit replacement of the imported host-owned
            // fields. Preserve trust, tree identity, caches, and forwarding
            // settings because the endpoint tuple that identified the
            // duplicate did not change.
            hostDao.update(current.copy(name = config.name, keyId = keyId))
            return@withContext ImportOutcome.Replaced(config.name, existing.id)
        }

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

    private suspend fun findExistingInDb(config: SshImportConfig): ExistingHost? =
        hostDao.getAll().first().firstOrNull {
            it.hostname.equals(config.host, ignoreCase = true) &&
                it.port == config.port &&
                it.username == config.username
        }?.let { ExistingHost(it.id, it.name) }
}

/** The user's explicit decision when a QR matches `user@host:port`. */
enum class DuplicateAction {
    Replace,
    Skip,
    AddNew,
}

/** The existing row shown in the QR review decision. */
data class ExistingHost(val id: Long, val name: String)

/** What an import attempt did. Every branch carries what the user should be told. */
sealed interface ImportOutcome {

    /** A new host row was written. */
    data class Imported(val name: String, val hostId: Long) : ImportOutcome

    /** The same `user@host:port` is already configured; nothing was written. */
    data class AlreadyPresent(val name: String, val hostId: Long) : ImportOutcome

    /** An existing host row was replaced after an explicit user choice. */
    data class Replaced(val name: String, val hostId: Long) : ImportOutcome

    /** Nothing was written. [message] is user-facing. */
    data class Failed(val message: String) : ImportOutcome
}
