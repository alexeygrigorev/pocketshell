package com.pocketshell.app.sessions

import android.util.Log
import com.pocketshell.app.projects.EnginesGateway
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.TmuxRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject

interface HostTmuxSessionsGateway {
    suspend fun listSessions(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): HostTmuxSessionListResult

    /**
     * Issue #463: list sessions using ONLY the warm live `-CC` control
     * client for [host]. Never opens a fresh SSH connection. Returns
     * `null` when there is no live client for the host (so the caller can
     * keep the previously-known list rather than blocking on a handshake).
     * This is the data source for the in-session project switcher — using
     * it instead of [listSessions] is what keeps the switch "instant".
     */
    suspend fun listSessionsFromLiveClient(
        host: HostEntity,
        keyPath: String,
    ): HostTmuxSessionListResult?
}

class SshHostTmuxSessionsGateway internal constructor(
    private val parser: HostTmuxSessionListParser,
    private val activeTmuxClients: ActiveTmuxClients,
    private val sshLeaseManager: SshLeaseManager,
    private val leaseBlockTimeoutMs: Long,
    private val liveEnumTimeoutMs: Long,
    private val enginesGateway: EnginesGateway? = null,
) : HostTmuxSessionsGateway {
    constructor(
        parser: HostTmuxSessionListParser,
        activeTmuxClients: ActiveTmuxClients,
    ) : this(
        parser = parser,
        activeTmuxClients = activeTmuxClients,
        sshLeaseManager = defaultLeaseManager(),
        leaseBlockTimeoutMs = LEASE_BLOCK_TIMEOUT_MS,
        liveEnumTimeoutMs = LIVE_ENUM_TIMEOUT_MS,
    )

    /** Compatibility seam for direct callers that predate engine-family lookup. */
    constructor(
        parser: HostTmuxSessionListParser,
        activeTmuxClients: ActiveTmuxClients,
        sshLeaseManager: SshLeaseManager,
    ) : this(
        parser = parser,
        activeTmuxClients = activeTmuxClients,
        sshLeaseManager = sshLeaseManager,
        leaseBlockTimeoutMs = LEASE_BLOCK_TIMEOUT_MS,
        liveEnumTimeoutMs = LIVE_ENUM_TIMEOUT_MS,
        enginesGateway = null,
    )

    @Inject
    constructor(
        parser: HostTmuxSessionListParser,
        activeTmuxClients: ActiveTmuxClients,
        sshLeaseManager: SshLeaseManager,
        enginesGateway: EnginesGateway,
    ) : this(
        parser = parser,
        activeTmuxClients = activeTmuxClients,
        sshLeaseManager = sshLeaseManager,
        leaseBlockTimeoutMs = LEASE_BLOCK_TIMEOUT_MS,
        liveEnumTimeoutMs = LIVE_ENUM_TIMEOUT_MS,
        enginesGateway = enginesGateway,
    )

    override suspend fun listSessions(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): HostTmuxSessionListResult {
        listSessionsFromLiveClient(host, keyPath)?.let { return it }

        SshOpenTelemetry.record(
            source = SSH_SOURCE_SESSION_PICKER_LIST,
            host = host.hostname,
            port = host.port,
            user = host.username,
        )
        return LeaseSessionExec.withSession(
            leaseManager = sshLeaseManager,
            target = host.toLeaseSessionTarget(keyPath, passphrase),
            blockTimeoutMs = leaseBlockTimeoutMs,
        ) { session ->
            val tmux = session.exec(pathAware(LIST_SESSIONS_COMMAND))
            when {
                tmux.exitCode == 0 -> HostTmuxSessionListResult.Sessions(
                    parser.parseTmuxListSessions(tmux.stdout) { rawId ->
                        enginesGateway?.familyForRawId(host.id, rawId)
                    },
                )
                tmux.exitCode == 127 || tmux.stderr.contains("not found", ignoreCase = true) ->
                    HostTmuxSessionListResult.ToolUnavailable
                tmux.stderr.contains("no server running", ignoreCase = true) ->
                    HostTmuxSessionListResult.Sessions(emptyList())
                else -> HostTmuxSessionListResult.Failed(
                    tmux.stderr.ifBlank { tmux.stdout }.ifBlank { "tmux exited ${tmux.exitCode}" },
                )
            }
        }.fold(
            onSuccess = { it },
            onFailure = { error -> HostTmuxSessionListResult.ConnectFailed(error) },
        )
    }

    private fun HostEntity.toLeaseSessionTarget(
        keyPath: String,
        passphrase: CharArray?,
    ): LeaseSessionTarget =
        LeaseSessionTarget(
            hostId = id,
            hostname = hostname,
            port = port,
            username = username,
            keyPath = keyPath,
            passphrase = passphrase,
        )

    private fun pathAware(command: String): String =
        ReposRemoteSource.pathAwareCommand(command)

    override suspend fun listSessionsFromLiveClient(
        host: HostEntity,
        keyPath: String,
    ): HostTmuxSessionListResult? {
        val entry = activeTmuxClients.clients.value[host.id]
            ?.takeIf { it.matches(host, keyPath) }
            ?.takeUnless { it.client.disconnected.value }
            ?: return null
        return try {
            val response = withTimeoutOrNull(liveEnumTimeoutMs) {
                entry.client.sendCommand(LIVE_LIST_SESSIONS_COMMAND)
            } ?: run {
                Log.w(
                    LOG_TAG,
                    "live -CC session picker enumeration wedged >${liveEnumTimeoutMs}ms; " +
                        "falling through to bounded SSH-lease enumeration.",
                )
                return null
            }
            if (response.isError) {
                val message = response.output.joinToString("\n")
                if (message.contains("no server running", ignoreCase = true)) {
                    HostTmuxSessionListResult.Sessions(emptyList())
                } else {
                    null
                }
            } else {
                HostTmuxSessionListResult.Sessions(
                    parser.parseTmuxListSessions(
                        response.output.joinToString(separator = "\n"),
                    ) { rawId -> enginesGateway?.familyForRawId(host.id, rawId) },
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    private fun ActiveTmuxClients.Entry.matches(host: HostEntity, keyPath: String): Boolean =
        hostname == host.hostname &&
            port == host.port &&
            username == host.username &&
            this.keyPath == keyPath

    internal companion object {
        const val LOG_TAG: String = "HostTmuxSessions"

        /**
         * The cold (no warm `-CC` client) session-picker enumeration.
         *
         * Issue #1944: picker navigation must carry a correlated tmux
         * generation. A name-only proxy row is unsafe after runtime-cache
         * eviction, so query id + created directly.
         *
         * Issue #2160: `tmux -u`. `#{session_name}` is free-form — the
         * maintainer routinely names sessions in Russian — and a tmux client
         * that is not in UTF-8 mode replaces every non-printable-ASCII
         * character it prints with `_`. On a host whose sshd hands the SSH
         * exec channel no locale (a container, Alpine/BusyBox, a hardened
         * sshd with no `AcceptEnv`/PAM locale) a bare read returns `______`
         * for such a name, and the picker can no longer target the session it
         * is showing. Same command shape, same exec lane, same byte class as
         * [com.pocketshell.app.projects.SshFolderListGateway.LIST_SESSIONS_COMMAND].
         * See [com.pocketshell.core.tmux.TmuxRead].
         */
        const val LIST_SESSIONS_COMMAND: String =
            "${TmuxRead.CLIENT} list-sessions -F " +
                "'#{session_id}::#{session_name}::#{session_created}::" +
                "#{session_activity}::#{session_attached}::#{@ps_agent_kind}::#{session_path}'"

        const val LEASE_BLOCK_TIMEOUT_MS: Long = 3_500L

        const val LIVE_ENUM_TIMEOUT_MS: Long = 3_500L

        fun defaultLeaseManager(): SshLeaseManager =
            SshLeaseManager(
                connector = SshLeaseConnector { target ->
                    DefaultSshLeaseConnector().connect(target)
                },
            )

        // Issue #463: append `#{session_path}` so the warm live-client list
        // carries each session's working directory and the in-session
        // project switcher can group sessions by project/folder without a
        // second SSH connect.
        const val LIVE_LIST_SESSIONS_COMMAND: String =
                "list-sessions -F " +
                "'#{session_id}::#{session_name}::#{session_created}::#{session_activity}::" +
                "#{session_attached}::#{@ps_agent_kind}::#{session_path}'"
    }
}
