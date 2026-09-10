package com.pocketshell.next.sync

import com.pocketshell.core.storage.entity.HostEntity

/**
 * The sync policy: pull → absorb → assemble → push, with the conflict retry
 * (issue #2633). The Android counterpart of the desktop app's sync store.
 *
 * The passphrase is a PARAMETER on every call and is never held here, never
 * written to preferences, never sent anywhere. It exists for the length of one
 * pull or push and then it is the caller's (a ViewModel field cleared with the
 * screen). Losing it loses the stored blob — which the settings screen says
 * out loud, because there is no recovery: the server never had it.
 *
 * Nothing in this class ever sees a token either. [SyncApiClient] resolves one
 * from [GoogleAuth] per request; the repository only sees results.
 */
class SyncRepository(
    private val api: SyncApiClient,
    private val selection: SyncSelectionStore,
) {

    /** What a pull found. `Absent` is a fresh account, not an error. */
    sealed interface PullResult {
        data object Absent : PullResult
        data class Ok(val version: Int, val hosts: List<SyncHostEntry>) : PullResult
        data class Failed(val message: String) : PullResult
    }

    /** What a push did. `Conflict` survives three retries before it surfaces. */
    sealed interface PushResult {
        data class Ok(val version: Int, val uploaded: Int) : PushResult
        data class Conflict(val currentVersion: Int) : PushResult
        data class Failed(val message: String) : PushResult
    }

    /**
     * Pull the account's blob, decrypt it with [passphrase], and auto-tick the
     * aliases this device does not have locally — the self-healing rule that
     * stops a fresh device's first push from wiping the account.
     */
    suspend fun pull(passphrase: String, localHosts: List<HostEntity>): PullResult = try {
        when (val slot = api.pull()) {
            null -> PullResult.Absent
            else -> {
                val hosts = parseSyncPayload(SyncCrypto.decryptEnvelope(slot.data, passphrase))
                selection.addAll(
                    aliasesToAutoCheck(
                        remote = hosts,
                        checked = selection.selected.value,
                        localAliases = localHosts.map { it.name },
                    ),
                )
                PullResult.Ok(slot.version, hosts)
            }
        }
    } catch (e: Exception) {
        e.asPullFailure()
    }

    /**
     * Upload the ticked hosts. Reads the current version first (the conflict
     * base), then pushes; a 409 re-pulls, re-absorbs the account's aliases,
     * re-assembles and retries up to [MAX_CONFLICT_RETRIES] times. Each retry
     * absorbs only NEW aliases, so a retry cannot compound.
     */
    suspend fun push(passphrase: String, localHosts: List<HostEntity>): PushResult {
        var attempt = 0
        var lastConflict = 0
        while (attempt <= MAX_CONFLICT_RETRIES) {
            attempt += 1
            try {
                val slot = api.pull()
                val remote = slot?.let { pulled ->
                    parseSyncPayload(SyncCrypto.decryptEnvelope(pulled.data, passphrase))
                }.orEmpty()
                selection.addAll(
                    aliasesToAutoCheck(
                        remote = remote,
                        checked = selection.selected.value,
                        localAliases = localHosts.map { it.name },
                    ),
                )
                val assembled = assembleSyncSet(
                    local = localHosts.map(::toSyncHostEntry),
                    remote = remote,
                    checked = selection.selected.value,
                )
                val envelope = SyncCrypto.encryptToEnvelope(
                    serializeSyncPayload(assembled),
                    passphrase,
                )
                val version = api.push(envelope, slot?.version ?: 0)
                return PushResult.Ok(version, assembled.size)
            } catch (e: SyncConflictError) {
                lastConflict = e.currentVersion
                // Loop: another device wrote between our read and our write.
            } catch (e: Exception) {
                return e.asPushFailure()
            }
        }
        return PushResult.Conflict(lastConflict)
    }

    private fun Exception.asPullFailure(): PullResult = when (this) {
        is NotSignedInError -> PullResult.Failed(message ?: "not signed in")
        is SyncCryptoError -> PullResult.Failed(message ?: "could not decrypt the account blob")
        is SyncApiError -> PullResult.Failed(message ?: "sync failed")
        is SyncAuthError -> PullResult.Failed(message ?: "sign-in failed")
        else -> throw this
    }

    private fun Exception.asPushFailure(): PushResult = when (this) {
        is NotSignedInError -> PushResult.Failed(message ?: "not signed in")
        is SyncCryptoError -> PushResult.Failed(message ?: "could not encrypt the settings blob")
        is SyncApiError -> PushResult.Failed(message ?: "sync failed")
        is SyncAuthError -> PushResult.Failed(message ?: "sign-in failed")
        else -> throw this
    }

    companion object {
        const val MAX_CONFLICT_RETRIES: Int = 3

        /**
         * A saved host as it travels in the payload. Only connection metadata —
         * a private key is a file in app-private storage and never leaves it,
         * exactly as the desktop app never uploads an `IdentityFile`'s contents.
         */
        fun toSyncHostEntry(host: HostEntity): SyncHostEntry = SyncHostEntry(
            name = host.name.ifBlank { host.hostname },
            hostname = host.hostname,
            port = host.port,
            user = host.username,
        )
    }
}
