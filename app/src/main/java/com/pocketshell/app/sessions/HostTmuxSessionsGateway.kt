package com.pocketshell.app.sessions

import android.util.Log
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.storage.entity.HostEntity
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

    @Inject
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
            val pocketshell = session.exec(pathAware("pocketshell sessions list --by activity"))
            if (pocketshell.exitCode == 0) {
                return@withSession HostTmuxSessionListResult.Sessions(
                    parser.parsePocketshellSessionsList(pocketshell.stdout),
                )
            }

            val tmux = session.exec(
                pathAware(
                    // Keep this in the same structured wire shape as
                    // the live-client dashboard poller; the shared
                    // parser also accepts tab and fallback variants.
                    "tmux list-sessions -F '#{session_name}::#{session_created}::#{session_activity}::#{session_attached}'",
                ),
            )
            when {
                tmux.exitCode == 0 -> HostTmuxSessionListResult.Sessions(parser.parseTmuxListSessions(tmux.stdout))
                tmux.exitCode == 127 || tmux.stderr.contains("not found", ignoreCase = true) ->
                    HostTmuxSessionListResult.ToolUnavailable
                tmux.stderr.contains("no server running", ignoreCase = true) ->
                    HostTmuxSessionListResult.Sessions(emptyList())
                pocketshell.exitCode == 127 || pocketshell.stderr.contains("not found", ignoreCase = true) ->
                    HostTmuxSessionListResult.ToolUnavailable
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
                    parser.parseTmuxListSessions(response.output.joinToString(separator = "\n")),
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

    private companion object {
        const val LOG_TAG: String = "HostTmuxSessions"

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
                "'#{session_name}::#{session_created}::#{session_activity}::" +
                "#{session_attached}::#{session_path}'"
    }
}
