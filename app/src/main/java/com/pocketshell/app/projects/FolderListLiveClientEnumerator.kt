package com.pocketshell.app.projects

import android.util.Log
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Issue #692: the folder list's session/pane enumeration served off the live
 * `tmux -CC` control client instead of a fresh SSH lease.
 *
 * Split out of [SshFolderListGateway] in issue #2377 (the file sits on a
 * downward-only size ratchet). The behaviour is unchanged; what DID change is
 * the caller: a `-CC` client is attached to exactly ONE tmux server, so these
 * rows are the metadata overlay for that one socket, never the host's session
 * SET. [SshFolderListGateway.listSessionsWithFolder] unions the tmuxctl+aplexer
 * enumerator over them.
 */
internal object FolderListLiveClientEnumerator {
    /**
     * Issue #692: enumerate session + pane rows from the live `-CC` control
     * client in ONE batched control-mode round-trip.
     *
     * Returns null when no matching live client is connected (the caller then
     * opens an SSH lease) and when the enumeration errors (so the lease path
     * can produce an accurate error). An EMPTY list is a valid result — it
     * means a connected client with `no server running` (all sessions gone),
     * which is distinct from "no client". The two probes (`list-sessions`,
     * `list-panes`) are chained via [TmuxClient.sendChainedCommands] so the
     * already-open channel pays a single wire round-trip instead of two
     * serial commands.
     */
    suspend fun enumerate(
        activeTmuxClients: ActiveTmuxClients,
        host: HostEntity,
        keyPath: String,
        liveEnumTimeoutMs: Long,
        familyForRawId: (String?) -> SessionAgentKind?,
    ): List<FolderSessionRow>? {
        val entry = activeTmuxClients.clients.value[host.id]
            ?.takeIf { it.matches(host, keyPath) }
            ?.takeUnless { it.client.disconnected.value }
            ?: return null
        return try {
            // Issue #702: bound the live-client enumeration so a wedged shared
            // `-CC` control channel (single-flight mutex held by never-releasing
            // in-session traffic) can't pin the picker. On timeout we return
            // null and the caller falls through to the bounded SSH-lease
            // enumeration (execBounded). `sendChainedCommands` itself also
            // self-bounds its acquire (#702); this is the gateway-side defence.
            val responses = withTimeoutOrNull(liveEnumTimeoutMs) {
                entry.client.sendChainedCommands(
                    listOf(
                        SshFolderListGateway.CONTROL_LIST_SESSIONS_COMMAND,
                        SshFolderListGateway.CONTROL_LIST_PANES_COMMAND,
                    ),
                )
            } ?: run {
                Log.w(
                    SshFolderListGateway.PROBE_LOG_TAG,
                    "live -CC enumeration wedged >${liveEnumTimeoutMs}ms; " +
                        "falling through to bounded SSH-lease enumeration.",
                )
                return null
            }
            val listSessions = responses.getOrNull(0) ?: return null
            val listPanes = responses.getOrNull(1)
            when {
                listSessions.isError &&
                    listSessions.output.joinToString("\n").contains("no server running", ignoreCase = true) ->
                    emptyList()
                listSessions.isError -> null
                else -> {
                    val baseRows = SshFolderListGateway.parseListSessionsRows(
                        stdout = listSessions.output.joinToString(separator = "\n"),
                        familyForRawId = familyForRawId,
                    )
                    val windowRows = if (listPanes != null && !listPanes.isError) {
                        SshFolderListGateway.parseSessionWindowRows(listPanes.output.joinToString("\n"))
                    } else {
                        emptyList()
                    }
                    val paneRows = SshFolderListGateway.activePaneRowsBySession(windowRows)
                    val windowsBySession = windowRows.groupBy { it.sessionName }

                    baseRows.map { row ->
                        val pane = paneRows[row.sessionName]
                        // Epic #821: a recorded `@ps_agent_kind` (read back in
                        // parseRow from CONTROL_LIST_SESSIONS_COMMAND) is the
                        // authoritative kind for a session WE launched. This
                        // live path runs no detector, so the recorded kind is
                        // the only positive agent signal here for our sessions.
                        val recorded = row.recordedKind
                        val windows = windowsBySession[row.sessionName].orEmpty().map { window ->
                            // Issue #716: the live-client path runs NO detector
                            // (the control channel can't host the ps/candidate
                            // scan), so resolve the raw kind affirmative-shell-
                            // aware — an interactive-shell pane is a confirmed
                            // Shell, anything else is presumed-agent Probing.
                            // NEVER emit a raw `Shell` for an undetected window:
                            // a false `Shell` would downgrade a sticky agent in
                            // the maintained tree (#716). A recorded kind
                            // (#821) wins.
                            window.copy(
                                agentKind = recorded ?: SshFolderListGateway.resolveUndetectedKind(window.command),
                            )
                        }
                        row.copy(
                            cwd = pane?.cwd ?: row.cwd,
                            agentKind = recorded ?: SshFolderListGateway.resolveUndetectedKind(
                                (windows.firstOrNull { it.active } ?: windows.firstOrNull())?.command,
                            ),
                            windows = windows,
                        )
                    }
                }
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
}
