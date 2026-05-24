package com.pocketshell.app.sessions

import com.pocketshell.app.jobs.TmuxctlJobsRemoteSource
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
) : HostTmuxSessionsGateway {
    override suspend fun listSessions(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): HostTmuxSessionListResult {
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
            val tmuxctl = session.exec(pathAware("tmuxctl list --by activity"))
            if (tmuxctl.exitCode == 0) {
                return HostTmuxSessionListResult.Sessions(parser.parseTmuxctlList(tmuxctl.stdout))
            }

            val tmux = session.exec(
                pathAware(
                    "tmux list-sessions -F '#{session_name}\t#{session_created}\t#{session_activity}\t#{session_attached}'",
                ),
            )
            when {
                tmux.exitCode == 0 -> HostTmuxSessionListResult.Sessions(parser.parseTmuxListSessions(tmux.stdout))
                tmux.exitCode == 127 || tmux.stderr.contains("not found", ignoreCase = true) ->
                    HostTmuxSessionListResult.ToolUnavailable
                tmux.stderr.contains("no server running", ignoreCase = true) ->
                    HostTmuxSessionListResult.Sessions(emptyList())
                tmuxctl.exitCode == 127 || tmuxctl.stderr.contains("not found", ignoreCase = true) ->
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
        TmuxctlJobsRemoteSource.pathAwareCommand(command)
}
