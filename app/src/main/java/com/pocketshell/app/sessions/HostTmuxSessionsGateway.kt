package com.pocketshell.app.sessions

import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.CancellationException
import java.io.File
import javax.inject.Inject

interface HostTmuxSessionsGateway {
    suspend fun listSessions(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): HostTmuxSessionListResult
}

class SshHostTmuxSessionsGateway @Inject constructor(
    private val parser: HostTmuxSessionListParser,
    private val activeTmuxClients: ActiveTmuxClients,
) : HostTmuxSessionsGateway {
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
        val session = SshConnection.connect(
            host = host.hostname,
            port = host.port,
            user = host.username,
            key = SshKey.Path(File(keyPath)),
            passphrase = passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrElse { error ->
            // Issue #109: surface the throwable up to the view-model so
            // the user-facing summary path (HostConnectError) can run.
            // Concatenating `error.message` into the sheet body was what
            // produced the raw "ECONNREFUSED" stack trace.
            return HostTmuxSessionListResult.ConnectFailed(error)
        }

        return try {
            val pocketshell = session.exec(pathAware("pocketshell sessions list --by activity"))
            if (pocketshell.exitCode == 0) {
                return HostTmuxSessionListResult.Sessions(parser.parsePocketshellSessionsList(pocketshell.stdout))
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
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            HostTmuxSessionListResult.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
        } finally {
            session.close()
        }
    }

    private fun pathAware(command: String): String =
        ReposRemoteSource.pathAwareCommand(command)

    private suspend fun listSessionsFromLiveClient(
        host: HostEntity,
        keyPath: String,
    ): HostTmuxSessionListResult? {
        val entry = activeTmuxClients.clients.value[host.id]
            ?.takeIf { it.matches(host, keyPath) }
            ?.takeUnless { it.client.disconnected.value }
            ?: return null
        return try {
            val response = entry.client.sendCommand(LIVE_LIST_SESSIONS_COMMAND)
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
        const val LIVE_LIST_SESSIONS_COMMAND: String =
            "list-sessions -F '#{session_name}::#{session_created}::#{session_activity}::#{session_attached}'"
    }
}
