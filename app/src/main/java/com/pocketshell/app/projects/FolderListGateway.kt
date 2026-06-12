package com.pocketshell.app.projects

import android.util.Log
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.repos.ReposListResult
import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.app.sessions.remoteStartDirectoryExists
import com.pocketshell.app.sessions.startDirectoryMissingMessage
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.portfwd.PortScanner
import com.pocketshell.core.portfwd.RemotePort
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import javax.inject.Inject

/**
 * One row returned by [FolderListGateway.listSessionsWithFolder] — the
 * minimal data shape the folder-grouping logic needs.
 *
 * `cwd` is the active pane's `pane_current_path` when available, falling
 * back to the session's `session_path` if the pane probe failed or the
 * session has no active pane. Both can be null (very old tmux, or a
 * session created without `-c`) — the view model surfaces those under
 * an "Untracked" group.
 *
 * `agentKind` is the LIVE detection state. Issue #252: the gateway
 * delegates to the exact same detector the Conversation view uses
 * ([com.pocketshell.app.session.AgentConversationRepository]), via the
 * batched [com.pocketshell.app.session.AgentConversationRepository.detectForPanes]
 * — every session is classified from a CONSTANT 2 host-wide SSH
 * round-trips (one candidate enumeration across all cwds, one host-wide
 * `ps`), scoped per pane by cwd + TTY + foreground command from
 * `tmux list-panes -a`. That guarantees the session-list chip and the
 * Conversation tab agree by construction (they previously drifted: the
 * list kept a forked candidate-enumeration heuristic that predated
 * #183/#186/#236 and so labelled live Claude Code / Codex / OpenCode
 * sessions `Shell`) WITHOUT the ~2N sequential round-trips a per-session
 * `detectForPane` loop would cost on a multi-session list.
 *
 * Issue #716 (default-optimistic + sticky agent-ness): a missing agent
 * detection is NO LONGER assumed to mean "plain shell" — detection is
 * sometimes slow or wrong right after attach/switch, and most sessions
 * are agents. The fallback is now [SessionAgentKind.Probing]
 * ("presumed-agent / detecting") so the composer stays available during
 * the uncertain window. [SessionAgentKind.Shell] is reserved for an
 * AFFIRMATIVE shell verdict ONLY: a completed probe whose pane foreground
 * command is an interactive shell (`bash`/`zsh`/`fish`/`sh`) with no agent
 * match (see [isAffirmativeShellCommand]). A "no agent match because the
 * probe has not finished / saw no interactive-shell command" maps to
 * `Probing`, never `Shell` — so a real agent is never downgraded by an
 * incomplete probe.
 */
data class FolderSessionRow(
    val sessionName: String,
    val lastActivity: Long?,
    val attached: Boolean,
    val cwd: String?,
    val agentKind: SessionAgentKind = SessionAgentKind.Shell,
    val windows: List<FolderSessionWindowRow> = emptyList(),
)

/**
 * Compact per-window metadata for a tmux session. The folder list uses
 * one active pane per tmux window to expose enough identity for
 * multi-window sessions without becoming a window manager.
 */
data class FolderSessionWindowRow(
    val sessionName: String,
    val index: Int?,
    val name: String?,
    val active: Boolean,
    val cwd: String?,
    val tty: String?,
    val command: String?,
    val agentKind: SessionAgentKind = SessionAgentKind.Shell,
    /**
     * Issue #653: the stable tmux window id (`@N`) for this window. This is the
     * id tmux reports in `%window-close @<id>` on the live `-CC` control stream,
     * so threading it from `list-panes` (`#{window_id}`) into the maintained tree
     * lets a single window-close prune exactly that window node by id — the
     * window index is NOT stable across closes (tmux renumbers), so it cannot key
     * the prune. `null` when the probe path predates the id column (e.g. an older
     * cached row).
     */
    val windowId: String? = null,
)

/**
 * Result of a single folder-list probe against one host. Mirrors the
 * shape of [com.pocketshell.app.sessions.HostTmuxSessionListResult] so
 * the view model can render the same "Loading / Ready / Failed /
 * ConnectError" affordances as the existing host picker, but with
 * `cwd`-bearing rows.
 */
sealed interface FolderListResult {
    data class Sessions(
        val rows: List<FolderSessionRow>,
        val projectFoldersByRoot: Map<String, List<String>> = emptyMap(),
        val historyProjectFoldersByRoot: Map<String, List<String>> = emptyMap(),
        val resolvedWatchedRootPaths: Map<String, String> = emptyMap(),
        val discoveredPorts: List<RemotePort> = emptyList(),
    ) : FolderListResult
    data object ToolUnavailable : FolderListResult
    data class Failed(val message: String) : FolderListResult
    data class ConnectFailed(val cause: Throwable) : FolderListResult
}

/**
 * Raised when a session-enumeration SSH-exec probe (e.g. `tmux
 * list-sessions`) connects and authenticates fine but its output read
 * never reaches EOF within [SshFolderListGateway.EXEC_READ_TIMEOUT_MS].
 *
 * Issue #470: this is the robustness contract for the enumeration probe.
 * A connect can succeed (`destination=FolderList`) and then the post-
 * connect `session.exec(LIST_SESSIONS_COMMAND)` read can block silently —
 * `SshSession.exec` reads to EOF with a plain blocking JDK stream, so a
 * wedged channel (e.g. emulator↔Docker SLIRP back-pressure on heavier
 * output) would leave the folder screen stuck in `Loading` with no
 * exception and no `ConnectError` panel, defeating the retry-once
 * readiness gate. A wedged read MUST instead surface a bounded failure:
 * the gateway converts this into [FolderListResult.ConnectFailed], which
 * the view model renders as the retryable `FOLDER_LIST_ERROR_TAG` panel so
 * the user (and the readiness gate) can retry on a fresh probe. On timeout
 * the wedged [SshSession] is also closed so no orphaned exec channel/IO
 * thread outlives the failed probe (see [SshFolderListGateway.execBounded]).
 *
 * (In the #470 multi-run AVD repro the enumeration read itself completed
 * in ~10ms and this bound never tripped — the picker stall was a separate
 * test-side issue, not an SSH-exec wedge. This bound is kept as defensive
 * cover so a future genuine read wedge degrades to a bounded retry, not a
 * silent hang.)
 */
class FolderListExecTimeoutException(
    command: String,
    timeoutMs: Long,
) : RuntimeException(
    "tmux/session-enumeration probe read did not complete within " +
        "${timeoutMs}ms (connect+auth succeeded; the exec output never " +
        "reached EOF). Command: $command",
)

/**
 * Gateway used by [FolderListViewModel] to fetch session rows with
 * `pane_current_path` / `session_path` metadata.
 *
 * Kept separate from
 * [com.pocketshell.app.sessions.HostTmuxSessionsGateway] so issue #171
 * lands without touching the picker-sheet wire shape. The picker
 * gateway's existing call sites (dashboard, share-target paste-to-
 * session, deep links) stay on the cwd-blind contract; the folder
 * screen owns the cwd-aware probe end-to-end.
 *
 * Wire shape (per host poll):
 *
 *  - `tmux list-sessions -F '#{session_name}\t#{session_created}\t
 *    #{session_activity}\t#{session_attached}\t#{session_path}'`
 *  - `tmux list-panes -a -F '#{session_name}\t#{window_index}\t
 *    #{window_name}\t#{window_active}\t#{pane_active}\t
 *    #{pane_current_path}\t#{pane_tty}\t#{pane_current_command}'` so
 *    the active window's active-pane cwd + TTY + foreground command
 *    supersede `session_path` when they disagree, while every window's
 *    active pane remains available for compact metadata.
 *  - Agent detection probe (issue #252): one batched
 *    `AgentConversationRepository.detectForPanes` call for the whole
 *    list — a CONSTANT 2 host-wide round-trips (candidate enumeration
 *    across every cwd + one host-wide `ps`), each session then classified
 *    in-memory scoped to its active pane's cwd, TTY, and foreground
 *    command. This is the identical detector the Conversation view uses,
 *    so the session-list chip and the Conversation tab can never
 *    disagree, and the load does not scale with the session count.
 *    Sessions whose active pane has no live agent stay on
 *    [SessionAgentKind.Shell].
 *
 * If any of the secondary probes fail (no active panes, exec error)
 * the gateway falls back to the `session_path` value alone — the folder
 * grouping degrades gracefully rather than going blank.
 */
interface FolderListGateway {
    suspend fun listSessionsWithFolder(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        watchedRoots: List<ProjectRootEntity> = emptyList(),
    ): FolderListResult

    /**
     * Create a new tmux session in [cwd] and optionally launch
     * [startCommand] inside it via `send-keys`. Used by the
     * [SessionTypePickerSheet] confirm path so an "Agent" choice
     * auto-runs the chosen CLI as the new pane's first command.
     *
     * Returns the resolved session name (sometimes munged by tmux when
     * the requested name collides) or null on failure.
     */
    suspend fun createSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        cwd: String,
        startCommand: String?,
    ): Result<String>

    suspend fun createEmptyProject(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        parentPath: String,
        folderName: String,
    ): Result<String>

    suspend fun importFile(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        folderPath: String,
        payload: FolderImportPayload,
    ): Result<String>

    /**
     * Kill the tmux session named [sessionName] on the remote via an
     * SSH-exec `tmux kill-session` — issue #518.
     *
     * This is the host-detail-tree kill path. Unlike the in-session kill
     * ([com.pocketshell.app.tmux.TmuxSessionViewModel.killCurrentSession])
     * and the sessions-dashboard kill, the folder/session tree never holds
     * an attached `tmux -CC` control client, so the kill runs as a one-shot
     * exec over the same SSH-lease path the gateway already uses for
     * `tmux new-session` / `list-sessions`.
     *
     * Returns success only when the session is no longer present after the
     * kill (verified with `tmux has-session`), so a failed kill never
     * reports success and the caller keeps the still-live row. Killing an
     * already-absent session is treated as success (idempotent — the user's
     * intent is satisfied).
     */
    suspend fun killSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
    ): Result<Unit>

    /**
     * Rename a tmux session on the remote host. The default keeps existing
     * test fakes honest without forcing every unrelated fake gateway to learn
     * rename behavior; production overrides it below.
     */
    suspend fun renameSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        oldName: String,
        newName: String,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Session rename is not available."))
}

data class FolderImportPayload(
    val remoteName: String,
    val length: Long?,
    val openStream: () -> InputStream?,
)

class SshFolderListGateway internal constructor(
    private val reposRemoteSource: ReposRemoteSource,
    private val activeTmuxClients: ActiveTmuxClients,
    private val sshLeaseManager: SshLeaseManager,
    private val sessionListParser: HostTmuxSessionListParser,
    // Issue #470: bound on a single session-enumeration exec read. Defaults
    // to [EXEC_READ_TIMEOUT_MS] in production; the unit test overrides it to
    // a small deterministic value so the wedge/healthy split can be asserted
    // on a real dispatcher without virtual-vs-real time racing. Kept off the
    // @Inject constructor below so Hilt never has to provide a raw Long.
    private val execReadTimeoutMs: Long,
    // Issue #702: bound on the LIVE `-CC` client enumeration round-trip. The
    // live path (listSessionRowsFromLiveClient) serves the picker enumeration
    // off the already-open shared `-CC` control channel, which serializes on
    // ONE single-flight mutex against the in-session terminal's own control
    // traffic. If a holder never releases, the enumeration parks forever and
    // pins the picker in `Loading` (no SSH socket, no PsFolderProbe). Even
    // though TmuxClient.sendChainedCommands now self-bounds its acquire (#702),
    // we keep a defence-in-depth bound HERE so the live path can never out-wait
    // the bounded SSH-lease fall-through. On timeout we return null → the caller
    // dials the already-bounded lease enumeration instead of stalling. Defaults
    // to [LIVE_ENUM_TIMEOUT_MS]; the unit test overrides it to a small value.
    private val liveEnumTimeoutMs: Long = LIVE_ENUM_TIMEOUT_MS,
) : FolderListGateway {

    // Hilt entry point. The injectable surface is unchanged from before
    // issue #470; the read-timeout bound defaults to [EXEC_READ_TIMEOUT_MS]
    // and is only overridden by the gateway's own unit test.
    @Inject
    constructor(
        reposRemoteSource: ReposRemoteSource,
        activeTmuxClients: ActiveTmuxClients,
        sshLeaseManager: SshLeaseManager = SshLeaseManager(
            connector = SshLeaseConnector { target ->
                com.pocketshell.core.ssh.DefaultSshLeaseConnector().connect(target)
            },
        ),
        sessionListParser: HostTmuxSessionListParser = HostTmuxSessionListParser(),
    ) : this(
        reposRemoteSource = reposRemoteSource,
        activeTmuxClients = activeTmuxClients,
        sshLeaseManager = sshLeaseManager,
        sessionListParser = sessionListParser,
        execReadTimeoutMs = EXEC_READ_TIMEOUT_MS,
    )

    constructor() : this(
        ReposRemoteSource(ReposJsonParser()),
        ActiveTmuxClients(),
        SshLeaseManager(
            connector = SshLeaseConnector { target ->
                com.pocketshell.core.ssh.DefaultSshLeaseConnector().connect(target)
            },
        ),
        HostTmuxSessionListParser(),
    )

    // Issue #252: reuse the SAME detection logic the Conversation view
    // uses instead of maintaining a forked candidate-enumeration +
    // process-scan heuristic here. The list path previously hard-coded a
    // stale copy of the detection shell that predated #183 (Codex/OpenCode
    // candidate enumeration), #186 (per-pane TTY-scoped process scan),
    // OpenCode SQLite detection, and #236 (120-minute freshness window).
    // That drift is exactly why a live Claude Code (and Codex/OpenCode)
    // session classified as `Shell` in the list while the Conversation
    // view rendered it correctly. Delegating to the batched
    // [AgentConversationRepository.detectForPanes] keeps the two paths in
    // lock-step by construction while collapsing the list-load to a
    // constant 2 host-wide SSH round-trips (vs. ~2N sequential ones).
    private val agentRepository = AgentConversationRepository()

    override suspend fun listSessionsWithFolder(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        watchedRoots: List<ProjectRootEntity>,
    ): FolderListResult {
        // Issue #692: reuse the live `-CC` control client for the
        // session/pane ENUMERATION whenever one is connected — even when
        // watched roots are configured. The enumeration is the picker-gating
        // probe (issue #470), and serving it from the already-open control
        // channel in ONE batched round-trip (`list-sessions` + `list-panes`
        // chained, see [TmuxClient.sendChainedCommands]) avoids dialing a
        // fresh multi-exec SSH lease and paying 2 serial exec round-trips on
        // every poll. When no watched roots exist this is the WHOLE result;
        // when roots exist we only open a lease for the (app-cached, optional)
        // watched-root expansion and merge it onto the live-client rows,
        // instead of re-running list-sessions/list-panes/agent-detect over the
        // lease.
        val liveRows = listSessionRowsFromLiveClient(host, keyPath)
        if (liveRows != null && watchedRoots.isEmpty()) {
            return FolderListResult.Sessions(rows = liveRows)
        }

        return try {
            withLeaseSession(
                host = host,
                keyPath = keyPath,
                passphrase = passphrase,
            ) { session ->
                if (liveRows != null) {
                    // Live client already enumerated the sessions/panes in one
                    // control-mode round-trip, so the lease is used for the
                    // watched-root expansion (project folders, history, ports)
                    // — NOT a second list-sessions/list-panes pair. Issue #252's
                    // per-session agent detection still runs over the lease and
                    // is merged onto the live-client rows so the chips do not
                    // regress just because a control client is attached (the
                    // control channel can't run the host-wide ps/candidate scan
                    // the detector needs).
                    val annotated = annotateAgentKinds(session, liveRows)
                    sessionsWithWatchedRootExpansion(
                        session = session,
                        host = host,
                        watchedRoots = watchedRoots,
                        rows = annotated,
                    )
                } else {
                    // No live client: batch `list-sessions` + `list-panes` into
                    // a single chained shell exec so the lease pays ONE
                    // enumeration round-trip instead of two serial ones.
                    val enumeration = execEnumeration(session)
                    listSessionsFromNativeOrPocketshell(
                        session = session,
                        host = host,
                        watchedRoots = watchedRoots,
                        listSessions = enumeration.listSessions,
                        listPanes = enumeration.listPanes,
                    )
                }
            }.fold(
                onSuccess = { it },
                onFailure = { error -> FolderListResult.ConnectFailed(error) },
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            FolderListResult.Failed("${t.javaClass.simpleName}: ${t.message ?: "unknown error"}")
        }
    }

    /**
     * Issue #692: run `list-sessions` + `list-panes` as ONE chained shell
     * exec over the lease, split the two sections apart, and surface each as
     * a separate [ExecResult]. tmux writes both blocks to stdout; we delimit
     * them with a unique marker line printed between the two commands so the
     * single round-trip can be parsed back into the two probes the native
     * path expects. The `list-panes` half is best-effort — a marker-less or
     * truncated read degrades to a blank panes section (the caller already
     * tolerates an empty panes result), never to a wrong session list.
     */
    private suspend fun execEnumeration(session: SshSession): LeaseEnumeration {
        val chained =
            "$LIST_SESSIONS_COMMAND ; printf '%s\\n' $ENUMERATION_MARKER ; $LIST_PANES_COMMAND"
        val result = session.execBounded(pathAware(chained))
        // A non-zero exit (tmux missing / no server) is reported through the
        // list-sessions half so the existing fallbacks (pocketshell, tmux
        // absent) still trigger. The marker split is applied to stdout only.
        val markerIndex = result.stdout.indexOf("\n$ENUMERATION_MARKER\n")
            .let { if (it >= 0) it else result.stdout.indexOf("$ENUMERATION_MARKER\n") }
        return if (markerIndex < 0) {
            // No marker: treat the whole stdout as the session list and skip
            // panes (degrade to session_path cwd). Preserves exit code/stderr
            // so the tmux-absent / pocketshell fallbacks still fire.
            LeaseEnumeration(
                listSessions = result,
                listPanes = ExecResult(stdout = "", stderr = "", exitCode = 0),
            )
        } else {
            val before = result.stdout.substring(0, markerIndex)
            val afterStart = result.stdout.indexOf('\n', markerIndex + 1)
            val after = if (afterStart >= 0) result.stdout.substring(afterStart + 1) else ""
            LeaseEnumeration(
                listSessions = ExecResult(
                    stdout = before,
                    stderr = result.stderr,
                    exitCode = result.exitCode,
                ),
                listPanes = ExecResult(stdout = after, stderr = "", exitCode = 0),
            )
        }
    }

    private data class LeaseEnumeration(
        val listSessions: ExecResult,
        val listPanes: ExecResult,
    )

    /**
     * Run a session-enumeration probe with a bounded read timeout.
     *
     * Issue #470: `SshSession.exec` reads its stdout/stderr to EOF with a
     * plain blocking JDK stream read (`Command.inputStream.readBytes()`).
     * A wedged channel (heavier seeded tmux state on a warm pooled
     * connection over the emulator SLIRP path) leaves that read blocked
     * indefinitely with no exception. A plain `withTimeout` directly around
     * the `suspend exec` would NOT fire while the read is blocked, because
     * the blocking `readBytes()` sits inside `exec`'s own
     * `withContext(Dispatchers.IO)` and never hits a cancellation/suspension
     * point — `withTimeout` only resumes the coroutine at a suspension
     * point, so it would wait for the read to return. So we run the exec in
     * a CHILD coroutine ([async]) and race it against the timeout via
     * [deferred.await], which IS a genuine suspension point that the
     * timeout's cancellation can interrupt even while the underlying read is
     * still wedged. When the timeout wins, the parent resumes immediately
     * and we surface a bounded [FolderListExecTimeoutException].
     *
     * IMPORTANT — cancellation cannot interrupt the in-flight blocking read.
     * `deferred.cancel()` only marks the coroutine cancelled; the
     * `readBytes()` JDK call is not interruptible, so the `exec`'s
     * `client.startSession().use { … }` block stays parked and its `finally`
     * (which closes the exec channel) never runs. Left to itself the
     * orphaned channel + IO thread would survive until the whole pooled
     * [SshSession] is torn down by lease idle-expiry — and a repeated wedge
     * before that expiry would pile up orphaned channels/threads on the one
     * pooled connection. To avoid that leak we CLOSE the session on timeout:
     * `close()` disconnects the underlying transport, which makes the parked
     * `readBytes()` throw and unparks the `use {}` finally so the channel is
     * freed. The lease pool self-heals — once the session reports
     * `!isConnected`, the next [SshLeaseManager.acquire] for this key opens a
     * fresh connection instead of handing back the dead one. We deliberately
     * close rather than relying on idle-expiry so no orphaned channel/thread
     * outlives the failed probe.
     *
     * The whole bounded operation runs inside `withContext(Dispatchers.IO)`
     * so the timeout timer and `deferred.await()` are serviced on a free IO
     * worker rather than the caller's dispatcher. On device the probe runs
     * from the main/Compose dispatcher, which can be busy rendering the
     * seeded full-screen terminal; if the timeout lived on that thread it
     * could be starved and never fire. Moving it to IO makes the bound
     * robust regardless of caller-thread load.
     *
     * Under `runTest` this `withContext(Dispatchers.IO)` hop escapes the
     * test scheduler onto a real dispatcher, so the timeout uses real time
     * there too — fine, because the fast unit fakes return in microseconds,
     * far under the bound, so no spurious timeout fires.
     *
     * On the healthy sub-second path this adds no meaningful latency:
     * `await()` completes long before the bound and the timeout coroutine
     * is cancelled. The bound is generous relative to the normal exec (tens
     * of ms) but tight relative to the old ~45s silent hang and below the
     * folder-list poll cadence so the timeout — not an external poll
     * restart — is what surfaces a wedged read.
     */
    private suspend fun SshSession.execBounded(command: String): ExecResult =
        withContext(Dispatchers.IO) {
            val deferred = async { exec(command) }
            withTimeoutOrNull(execReadTimeoutMs) { deferred.await() }
                ?: run {
                    Log.w(
                        PROBE_LOG_TAG,
                        "session-enumeration exec read wedged >${execReadTimeoutMs}ms; " +
                            "closing wedged session + surfacing retryable ConnectError. " +
                            "cmd=${command.takeLast(48)}",
                    )
                    // Stop awaiting the wedged read so this coroutine can
                    // resume, then CLOSE the session: cancellation alone can
                    // NOT interrupt the in-flight blocking `readBytes()`, so
                    // close() is what tears down the transport, unparks the
                    // read (it throws), and lets exec's `use {}` finally free
                    // the channel — no orphaned channel/thread leak. The
                    // pooled lease self-heals: a now-disconnected session is
                    // discarded and re-opened on the next acquire.
                    deferred.cancel()
                    withContext(NonCancellable) {
                        runCatching { close() }
                    }
                    throw FolderListExecTimeoutException(command, execReadTimeoutMs)
                }
        }

    private suspend fun <T> withLeaseSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        block: suspend (SshSession) -> T,
    ): Result<T> {
        val leaseTarget = host.toSshLeaseTarget(keyPath, passphrase)
        // Issue #680: a refresh probe over a pooled lease whose transport went
        // STALE between acquire and the exec (sshj's `isConnected` lies until
        // its keepalive trips, so `ensureConnected()` can throw "SSH session is
        // not connected" on a lease that was just handed back as alive) is a
        // FALSE NEGATIVE — the host is connectable (the user opens + uses a
        // session right after) but the folder screen surfaced a scary
        // persistent "Couldn't refresh sessions: SSH session is not connected"
        // banner. The #465/#665 eviction already evicted the corpse so the NEXT
        // poll recovered, but the CURRENT refresh still showed the alarming
        // error. So instead of only evicting + surfacing, we EVICT-AND-RETRY
        // ONCE on a fresh lease within the same refresh: a transient/stale-
        // channel symptom heals silently (no false banner) and only a GENUINE
        // disconnect — where the fresh connect or the retried exec also fails —
        // surfaces an accurate error.
        val firstAttempt = runLeaseAttempt(leaseTarget, block)
        val firstError = firstAttempt.exceptionOrNull()
        if (firstError == null || !isStaleChannelSymptom(firstError)) {
            return firstAttempt
        }
        // Heal: the eviction inside runLeaseAttempt already discarded the
        // poisoned transport, so this second acquire dials a FRESH connection.
        return runLeaseAttempt(leaseTarget, block)
    }

    /**
     * One lease acquire → block → release cycle. On a stale-channel/open-failed
     * symptom the poisoned lease is EVICTED (not just released) so the next
     * acquire — whether the in-refresh heal retry above or a later poll — opens
     * a fresh transport instead of re-grabbing the corpse.
     */
    private suspend fun <T> runLeaseAttempt(
        leaseTarget: SshLeaseTarget,
        block: suspend (SshSession) -> T,
    ): Result<T> {
        val lease = try {
            sshLeaseManager.acquire(leaseTarget)
                .getOrElse { return Result.failure(it) }
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            return Result.failure(t)
        }
        var poisonedTransport = false
        return try {
            Result.success(block(lease.session))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Issue #465/#665/#680: an "open failed" / dead-transport / "SSH
            // session is not connected" probe failure must EVICT the pooled
            // lease, not just release it back. A transport stuck refusing
            // channels (or one whose `isConnected` lies) still gets handed back
            // by the pool, so without eviction every folder-tree poll would
            // re-surface the same dead-end. Evicting it makes the heal retry /
            // next poll open a fresh transport that recovers the tree.
            poisonedTransport = isStaleChannelSymptom(t)
            Result.failure(t)
        } finally {
            withContext(NonCancellable) {
                lease.release()
                if (poisonedTransport) {
                    runCatching { sshLeaseManager.disconnect(leaseTarget.leaseKey) }
                }
            }
        }
    }

    /**
     * Issue #680: the family of transient probe failures that must HEAL +
     * RETRY on a fresh lease rather than surface a persistent "not connected"
     * error. Mirrors
     * [com.pocketshell.app.tmux.TmuxSessionViewModel.isStaleChannelSymptom]:
     *
     *  - [isChannelOpenFailure]: live transport refuses the exec channel.
     *  - [isTransportDisconnected]: sshj `TransportException` / `BY_APPLICATION`
     *    teardown of a silently-dead pooled transport.
     *  - [isSessionNotConnected]: `ensureConnected()` threw because the pooled
     *    lease's `isConnected` flipped false between acquire and exec — the
     *    exact false-negative #680 surfaced.
     */
    private fun isStaleChannelSymptom(cause: Throwable?): Boolean =
        isChannelOpenFailure(cause) ||
            isTransportDisconnected(cause) ||
            isSessionNotConnected(cause) ||
            isTransportEofDrop(cause)

    /**
     * Issue #711: true when [cause] is the transient transport-EOF family that
     * the dogfood report surfaced as a scary raw-command band — a pooled SSH
     * transport that died MID-EXEC, so sshj reports `Broken transport;
     * encountered EOF` (or a bare `encountered EOF`, a `broken pipe`, or a
     * `Failed to open exec channel for <command>` that wraps that EOF). This is
     * a TRANSIENT drop (the tree self-recovered on the next refresh), so it must
     * heal + retry on a fresh lease like every other [isStaleChannelSymptom],
     * NOT escape as a persistent error carrying the raw enumeration command.
     *
     * Matched on message text (walking the cause chain) so the app module need
     * not depend on the core/sshj exception hierarchy.
     */
    private fun isTransportEofDrop(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message
            if (message != null &&
                (
                    message.contains("encountered EOF", ignoreCase = true) ||
                        message.contains("Broken transport", ignoreCase = true) ||
                        message.contains("broken pipe", ignoreCase = true) ||
                        message.contains("Failed to open exec channel", ignoreCase = true) ||
                        message.contains("channel closed", ignoreCase = true) ||
                        message.contains("control channel closed", ignoreCase = true)
                    )
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Issue #680: true when [cause] is the "SSH session is not connected" probe
     * failure — `RealSshSession.ensureConnected()` throwing because the pooled
     * lease's `isConnected` (sshj `client.isConnected && isAuthenticated`)
     * flipped false between the lease acquire and the exec. This is the
     * transient stale-channel symptom the folder refresh surfaced as a scary
     * persistent banner; it must heal + retry on a fresh lease, not surface a
     * false "not connected" error while the host is actually connectable.
     *
     * Matched on message text (walking the cause chain) so the app module need
     * not depend on the core SSH exception hierarchy. Also covers the lower-
     * level "transport endpoint is not connected" socket text.
     */
    private fun isSessionNotConnected(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message
            if (message != null &&
                (
                    message.contains("SSH session is not connected", ignoreCase = true) ||
                        message.contains("transport endpoint is not connected", ignoreCase = true)
                    )
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Issue #465: true when [cause] is a channel/shell "open failed" against an
     * otherwise-live SSH transport — the case where the pooled connection must
     * be evicted so the next probe opens a fresh transport instead of reusing
     * the half-dead one forever.
     */
    private fun isChannelOpenFailure(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message
            if (message != null &&
                (
                    message.contains("open failed", ignoreCase = true) ||
                        message.contains("failed to open SSH shell", ignoreCase = true)
                    )
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Issue #665 / #636: true when [cause] is the transport-DEAD variant — the
     * pooled SSH transport silently died, so the folder-tree probe's exec fails
     * not with an "open failed" channel error but with a sshj
     * `net.schmizz.sshj.transport.TransportException` carrying disconnect reason
     * `BY_APPLICATION` ("Disconnected"). Same gap as
     * [com.pocketshell.app.tmux.TmuxSessionViewModel.isStaleChannelSymptom]:
     * without this the dead lease is released back (not evicted), the next poll
     * re-grabs the corpse, and the host-detail "open failed" dead-end never
     * recovers. Evicting it makes the next poll / Retry open a fresh transport.
     *
     * Matched on class simple name + reason/message text (no sshj compile-time
     * dep), walking the cause chain.
     */
    private fun isTransportDisconnected(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            if (current.javaClass.simpleName == "TransportException") {
                val reasonName = runCatching {
                    current!!.javaClass.getMethod("getDisconnectReason").invoke(current)?.toString()
                }.getOrNull()
                if (reasonName != null && reasonName.contains("BY_APPLICATION", ignoreCase = true)) {
                    return true
                }
                val message = current.message
                if (message != null &&
                    (
                        message.contains("BY_APPLICATION", ignoreCase = true) ||
                            message.contains("Disconnected", ignoreCase = true)
                        )
                ) {
                    return true
                }
            }
            current = current.cause
        }
        return false
    }

    private fun HostEntity.toSshLeaseTarget(
        keyPath: String,
        passphrase: CharArray?,
    ): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = hostname,
                port = port,
                user = username,
                credentialId = "$id:$keyPath",
                knownHostsId = "accept-all",
            ),
            key = SshKey.Path(File(keyPath)),
            passphrase = passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        )

    internal suspend fun listSessionsFromNativeOrPocketshell(
        session: SshSession,
        host: HostEntity,
        watchedRoots: List<ProjectRootEntity>,
        listSessions: ExecResult,
        // Issue #692: the `list-panes` half is fetched in the SAME chained
        // enumeration round-trip as `list-sessions` (see [execEnumeration]) and
        // handed in here, so this method never issues a second serial probe.
        // Null preserves the old behaviour for callers (tests) that only have a
        // list-sessions result — those re-fetch panes on demand.
        listPanes: ExecResult? = null,
    ): FolderListResult {
        return when {
            listSessions.exitCode == 127 ||
                listSessions.stderr.contains("not found", ignoreCase = true) ->
                listSessionsWithFolderFromPocketshell(session, host, watchedRoots)
                    ?: FolderListResult.ToolUnavailable
            listSessions.isTmuxServerAbsent() ->
                listSessionsWithFolderFromPocketshell(session, host, watchedRoots)
                    ?: sessionsWithWatchedRootExpansion(
                        session = session,
                        host = host,
                        watchedRoots = watchedRoots,
                        rows = emptyList(),
                    )
            listSessions.exitCode != 0 ->
                listSessionsWithFolderFromPocketshell(session, host, watchedRoots)
                    ?: FolderListResult.Failed(
                        listSessions.stderr.ifBlank { listSessions.stdout }
                            .ifBlank { "tmux exited ${listSessions.exitCode}" },
                    )
            else -> {
                val baseRows = parseListSessionsRows(listSessions.stdout)
                val windowRows = runCatching {
                    // Issue #692: prefer the list-panes section already fetched
                    // in the chained enumeration round-trip; only fall back to a
                    // separate probe when a caller passed null (legacy/tests).
                    val panes = listPanes
                        ?: session.execBounded(pathAware(LIST_PANES_COMMAND))
                    if (panes.exitCode == 0) parseSessionWindowRows(panes.stdout) else emptyList()
                }.getOrDefault(emptyList())
                val paneRows = activePaneRowsBySession(windowRows)
                val windowsBySession = windowRows.groupBy { it.sessionName }

                // Merge active-pane data into each session row first.
                val merged = baseRows.map { row ->
                    val pane = paneRows[row.sessionName]
                    val cwd = pane?.cwd ?: row.cwd
                    row.copy(cwd = cwd, windows = windowsBySession[row.sessionName].orEmpty())
                }

                // Issue #252: per-session agent detection delegated to
                // the Conversation view's detector
                // (AgentConversationRepository.detectForPane) so the
                // list chip and the Conversation tab agree.
                val annotated = annotateAgentKinds(session, merged)
                sessionsWithWatchedRootExpansion(
                    session = session,
                    host = host,
                    watchedRoots = watchedRoots,
                    rows = annotated,
                )
            }
        }
    }

    private suspend fun listSessionsWithFolderFromPocketshell(
        session: SshSession,
        host: HostEntity,
        watchedRoots: List<ProjectRootEntity>,
    ): FolderListResult.Sessions? {
        val pocketshell = session.execBounded(pathAware(POCKETSHELL_SESSIONS_COMMAND))
        if (pocketshell.exitCode != 0) {
            if (pocketshell.isTmuxServerAbsent()) {
                return sessionsWithWatchedRootExpansion(
                    session = session,
                    host = host,
                    watchedRoots = watchedRoots,
                    rows = emptyList(),
                )
            }
            return null
        }
        return sessionsWithWatchedRootExpansion(
            session = session,
            host = host,
            watchedRoots = watchedRoots,
            rows = parsePocketshellSessionsRows(pocketshell.stdout, sessionListParser),
        )
    }

    private fun ExecResult.isTmuxServerAbsent(): Boolean {
        val output = "$stdout\n$stderr"
        return output.contains("no server running", ignoreCase = true) ||
            (
                output.contains("error connecting to", ignoreCase = true) &&
                    output.contains("tmux-", ignoreCase = true) &&
                    output.contains("No such file or directory", ignoreCase = true)
                )
    }

    private suspend fun sessionsWithWatchedRootExpansion(
        session: SshSession,
        host: HostEntity,
        watchedRoots: List<ProjectRootEntity>,
        rows: List<FolderSessionRow>,
    ): FolderListResult.Sessions {
        val expansion = expandWatchedRootProjects(
            session = session,
            host = host,
            watchedRoots = watchedRoots,
        )
        val discoveredPorts = runCatching { PortScanner.scan(session) }.getOrDefault(emptyList())
        return FolderListResult.Sessions(
            rows = rows,
            projectFoldersByRoot = expansion.projectFoldersByRoot,
            historyProjectFoldersByRoot = expansion.historyProjectFoldersByRoot,
            resolvedWatchedRootPaths = expansion.resolvedWatchedRootPaths,
            discoveredPorts = discoveredPorts,
        )
    }

    private suspend fun expandWatchedRootProjects(
        session: SshSession,
        host: HostEntity,
        watchedRoots: List<ProjectRootEntity>,
    ): WatchedRootProjectExpansion {
        if (watchedRoots.isEmpty()) return WatchedRootProjectExpansion()
        val namespace = "${host.id}:${host.username}@${host.hostname}:${host.port}"
        val rootPaths = watchedRoots
            .mapNotNull { it.path.trim().takeIf { path -> path.isNotEmpty() } }
            .distinct()
        val remoteHome = if (rootPaths.any(::usesHomeShortcut)) remoteHomeDirectory(session) else null

        val projectFoldersByRoot = mutableMapOf<String, List<String>>()
        val historyProjectFoldersByRoot = mutableMapOf<String, List<String>>()
        val resolvedWatchedRootPaths = mutableMapOf<String, String>()
        val historyPaths = listProjectHistoryFromPocketshellLogs(session)
        for (rootPath in rootPaths) {
            val resolvedRootPath = expandRemoteHomeShortcut(rootPath, remoteHome)
            resolvedWatchedRootPaths[rootPath] = resolvedRootPath
            val paths = when (
                val result = reposRemoteSource.listLocalRoot(
                    session = session,
                    root = resolvedRootPath,
                    cacheNamespace = namespace,
                )
            ) {
                is ReposListResult.Success -> result.repos.mapNotNull { repo ->
                    repo.local?.path?.trim()?.takeIf { it.isNotEmpty() }
                }
                ReposListResult.ToolMissing,
                is ReposListResult.Failed,
                -> emptyList()
            }
            projectFoldersByRoot[rootPath] = paths.distinct()
            historyProjectFoldersByRoot[rootPath] = historyPaths
                .filter { pathWithinRoot(it, resolvedRootPath) }
                .map { projectPathUnderRoot(it, resolvedRootPath) }
                .distinct()
        }
        return WatchedRootProjectExpansion(
            projectFoldersByRoot = projectFoldersByRoot,
            historyProjectFoldersByRoot = historyProjectFoldersByRoot,
            resolvedWatchedRootPaths = resolvedWatchedRootPaths,
        )
    }

    private suspend fun listProjectHistoryFromPocketshellLogs(session: SshSession): List<String> {
        val result = session.execBounded(pathAware(POCKETSHELL_PROJECT_HISTORY_COMMAND))
        if (result.exitCode != 0 || result.isPocketshellLogsMissing()) return emptyList()
        return parsePocketshellProjectHistory(result.stdout)
    }

    private suspend fun remoteHomeDirectory(session: SshSession): String? {
        val result = session.execBounded(pathAware("printf '%s\\n' \"\$HOME\""))
        if (result.exitCode != 0) return null
        return result.stdout.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.isNotEmpty() }
    }

    private fun usesHomeShortcut(path: String): Boolean =
        path == "~" || path.startsWith("~/")

    private fun expandRemoteHomeShortcut(path: String, remoteHome: String?): String {
        val clean = path.trim().trimEnd('/').ifBlank { path.trim() }
        val home = remoteHome?.trimEnd('/')?.takeIf { it.isNotEmpty() }
        return when {
            home == null -> clean
            clean == "~" -> home
            clean.startsWith("~/") -> home + "/" + clean.removePrefix("~/")
            else -> clean
        }
    }

    private fun pathWithinRoot(path: String, root: String): Boolean {
        val cleanPath = canonicalRemotePath(path)
        val cleanRoot = canonicalRemotePath(root)
        return cleanPath == cleanRoot || cleanPath.startsWith(cleanRoot.trimEnd('/') + "/")
    }

    private fun projectPathUnderRoot(path: String, root: String): String {
        val cleanPath = canonicalRemotePath(path)
        val cleanRoot = canonicalRemotePath(root)
        if (cleanPath == cleanRoot) return cleanRoot
        val prefix = cleanRoot.trimEnd('/') + "/"
        val child = cleanPath.removePrefix(prefix).substringBefore('/').ifBlank { return cleanRoot }
        return prefix + child
    }

    private fun canonicalRemotePath(path: String): String {
        val clean = path.trim().trimEnd('/')
        return clean.ifEmpty { "/" }
    }

    override suspend fun createSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        cwd: String,
        startCommand: String?,
    ): Result<String> {
        return withLeaseSession(host, keyPath, passphrase) { session ->
            if (!remoteStartDirectoryExists(session, cwd)) {
                throw RuntimeException(
                    startDirectoryMissingMessage(
                        sessionName = sessionName,
                        startDirectory = cwd,
                    ),
                )
            }
            val quotedName = shellQuote(sessionName)
            val quotedCwd = shellQuote(cwd)
            // -A so an existing session with the same name attaches
            //    rather than failing (idempotent for the user — they
            //    can re-pick "Create" without seeing an error).
            // -d so the session is detached on the server (the app
            //    will attach via tmux -CC after navigation).
            val createResult = session.exec(
                pathAware("tmux new-session -A -d -s $quotedName -c $quotedCwd"),
            )
            if (createResult.exitCode != 0 && createResult.stderr.isNotBlank()) {
                throw RuntimeException(createResult.stderr.trim())
            }
            // Launch the start command via send-keys if requested. tmux's
            // `send-keys ... Enter` sequence pipes the literal command
            // followed by a carriage return — same shape used by the
            // existing voice + planner paths.
            //
            // Issue #703: for agents the start command is now the SHORT
            // server-side wrapper line `pocketshell agent <kind> --dir
            // '<dir>' …`. The wrapper itself merges the folder's
            // .env/.envrc (replacing the old `eval "$(pocketshell env
            // export …)"` prelude — hard-cut, D22), strips the provider
            // env vars for OpenCode, and suppresses each agent's first-run
            // modal so the agent is immediately usable. The app just types
            // the one short line verbatim.
            if (startCommand != null) {
                val quotedCommand = shellQuote(startCommand)
                session.exec(
                    pathAware("tmux send-keys -t $quotedName $quotedCommand Enter"),
                )
            }
            sessionName
        }
    }

    override suspend fun killSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
    ): Result<Unit> {
        val target = sessionName.trim()
        if (target.isEmpty()) {
            return Result.failure(IllegalArgumentException("No session to stop."))
        }
        return withLeaseSession(host, keyPath, passphrase) { session ->
            val quotedName = shellQuote(target)
            session.exec(pathAware("tmux kill-session -t $quotedName"))
            // Authoritative check: a kill "succeeded" only when the session
            // is genuinely gone. `tmux has-session` exits non-zero when the
            // session is absent, so exitCode != 0 == killed (or never
            // existed — idempotent success). A zero exit means the session
            // is still alive, so the kill did not land and we must surface a
            // failure so the tree keeps the still-live row.
            val hasSession = session.exec(
                pathAware("tmux has-session -t $quotedName"),
            )
            if (hasSession.exitCode == 0) {
                throw RuntimeException("tmux session '$target' is still running.")
            }
        }
    }

    override suspend fun renameSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        oldName: String,
        newName: String,
    ): Result<Unit> {
        val oldTarget = oldName.trim()
        val newTarget = newName.trim()
        if (oldTarget.isEmpty() || newTarget.isEmpty()) {
            return Result.failure(IllegalArgumentException("Enter a session name."))
        }
        if (oldTarget == newTarget) return Result.success(Unit)
        return withLeaseSession(host, keyPath, passphrase) { session ->
            val quotedOld = shellQuote(oldTarget)
            val quotedNew = shellQuote(newTarget)
            val rename = session.exec(pathAware("tmux rename-session -t $quotedOld $quotedNew"))
            if (rename.exitCode != 0) {
                throw RuntimeException(rename.stderr.ifBlank { rename.stdout }.trim())
            }
            val oldExists = session.exec(pathAware("tmux has-session -t $quotedOld"))
            val newExists = session.exec(pathAware("tmux has-session -t $quotedNew"))
            if (oldExists.exitCode == 0 || newExists.exitCode != 0) {
                throw RuntimeException("tmux session '$oldTarget' was not renamed to '$newTarget'.")
            }
        }
    }

    override suspend fun createEmptyProject(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        parentPath: String,
        folderName: String,
    ): Result<String> {
        val safeName = normaliseProjectFolderName(folderName)
            ?: return Result.failure(IllegalArgumentException("Enter a project folder name."))
        val child = childPath(parentPath, safeName)
        return withLeaseSession(host, keyPath, passphrase) { session ->
            val result = session.exec(pathAware("mkdir -p -- ${shellQuoteRemotePath(child)}"))
            if (result.exitCode == 0) {
                resolveRemoteDirectory(session, child).getOrDefault(child)
            } else {
                throw RuntimeException(result.stderr.ifBlank { result.stdout }.trim())
            }
        }
    }

    override suspend fun importFile(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        folderPath: String,
        payload: FolderImportPayload,
    ): Result<String> {
        return withLeaseSession(host, keyPath, passphrase) { session ->
            val mkdir = session.exec(pathAware("mkdir -p -- ${shellQuoteRemotePath(folderPath)}"))
            if (mkdir.exitCode != 0) {
                throw RuntimeException(mkdir.stderr.ifBlank { mkdir.stdout }.trim())
            }
            val resolvedFolderPath = resolveRemoteDirectory(session, folderPath)
                .getOrThrow()
            val remotePath = childPath(resolvedFolderPath, payload.remoteName)
            val input = payload.openStream()
                ?: throw RuntimeException("Couldn't read selected file.")
            input.use { stream ->
                session.uploadStream(
                    input = stream,
                    length = payload.length ?: -1L,
                    name = payload.remoteName,
                    remotePath = remotePath,
                )
            }
            remotePath
        }
    }

    /**
     * Issue #252 / #692: run the constant-cost per-window agent detection
     * over [session] and fold the result onto [rows]. Shared by the native
     * lease path and the live-client + watched-root path so the agent chips
     * are identical regardless of whether a `-CC` control client enumerated
     * the rows.
     *
     * Issue #716: when the detector produces NO match for a window, the
     * fallback is the AFFIRMATIVE-shell-aware [resolveUndetectedKind] — an
     * interactive-shell foreground command (`bash`/`zsh`/…) resolves to
     * [SessionAgentKind.Shell] (a confirmed shell), anything else resolves
     * to [SessionAgentKind.Probing] (presumed-agent / still detecting). A
     * detection failure (the whole probe threw) therefore degrades every
     * row to `Probing`, not `Shell`, so a transient detector error never
     * mislabels a real agent as a plain shell.
     */
    private suspend fun annotateAgentKinds(
        session: SshSession,
        rows: List<FolderSessionRow>,
    ): List<FolderSessionRow> {
        val agentKinds = runCatching {
            detectAgentKinds(session = session, rows = rows)
        }.getOrDefault(FolderAgentDetection())
        return rows.map { row ->
            val windows = row.windows.map { window ->
                val key = WindowProbeKey(row.sessionName, window.index)
                window.copy(
                    agentKind = agentKinds.windowKinds[key]
                        ?: resolveUndetectedKind(window.command),
                )
            }
            val sessionKind = agentKinds.sessionKinds[row.sessionName]
                ?: resolveUndetectedKind(
                    (windows.firstOrNull { it.active } ?: windows.firstOrNull())?.command,
                )
            row.copy(
                agentKind = sessionKind,
                windows = windows,
            )
        }
    }


    /**
     * Classify every session window's active pane by delegating to the
     * SAME detector the Conversation view uses. This keeps issue #252's
     * constant host-wide probe count while letting the project tree show
     * compact per-window agent hints for multi-window sessions.
     */
    private suspend fun detectAgentKinds(
        session: com.pocketshell.core.ssh.SshSession,
        rows: List<FolderSessionRow>,
    ): FolderAgentDetection {
        val probeKeys = mutableMapOf<String, WindowProbeKey>()
        val probes = rows.flatMap { row ->
            row.windows.mapNotNull { window ->
                val cwd = window.cwd?.trim()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val paneTty = window.tty?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val key = WindowProbeKey(row.sessionName, window.index)
                val probeKey = key.asProbeKey()
                probeKeys[probeKey] = key
                AgentConversationRepository.PaneProbe(
                    key = probeKey,
                    cwd = cwd,
                    paneTty = paneTty,
                    paneCommand = window.command.orEmpty(),
                )
            }
        }
        if (probes.isEmpty()) return FolderAgentDetection()

        val detections = agentRepository.detectForPanes(
            session = session,
            panes = probes,
        )
        // `detectForPanes` only returns matched panes, so `windowKinds` only
        // ever holds an AGENT kind (Claude/Codex/OpenCode), never Shell.
        val windowKinds = detections.mapNotNull { (probeKey, detection) ->
            val key = probeKeys[probeKey] ?: return@mapNotNull null
            key to detection.agent.toSessionAgentKind()
        }.toMap()
        // Issue #716: only publish a session kind when an agent was actually
        // detected (active window wins, else any window's agent). A session
        // with NO agent match is intentionally LEFT OUT of the map so
        // `annotateAgentKinds` falls through to `resolveUndetectedKind` —
        // affirmative-shell → Shell, otherwise → Probing. We must NOT default
        // an undetected session to Shell here (the #716 sticky/optimistic fix).
        val sessionKinds = rows.mapNotNull { row ->
            val activeWindowKind = row.windows
                .firstOrNull { it.active }
                ?.let { windowKinds[WindowProbeKey(row.sessionName, it.index)] }
            val anyAgentKind = row.windows
                .asSequence()
                .mapNotNull { windowKinds[WindowProbeKey(row.sessionName, it.index)] }
                .firstOrNull()
            val detected = activeWindowKind ?: anyAgentKind ?: return@mapNotNull null
            row.sessionName to detected
        }.toMap()
        return FolderAgentDetection(
            sessionKinds = sessionKinds,
            windowKinds = windowKinds,
        )
    }

    private fun AgentKind.toSessionAgentKind(): SessionAgentKind =
        when (this) {
            AgentKind.ClaudeCode -> SessionAgentKind.Claude
            AgentKind.Codex -> SessionAgentKind.Codex
            AgentKind.OpenCode -> SessionAgentKind.OpenCode
        }


    private data class FolderAgentDetection(
        val sessionKinds: Map<String, SessionAgentKind> = emptyMap(),
        val windowKinds: Map<WindowProbeKey, SessionAgentKind> = emptyMap(),
    )

    private data class WindowProbeKey(
        val sessionName: String,
        val windowIndex: Int?,
    ) {
        fun asProbeKey(): String = "$sessionName$FIELD_SEP${windowIndex ?: "active"}"
    }

    private fun pathAware(command: String): String =
        ReposRemoteSource.pathAwareCommand(command)

    private fun shellQuote(value: String): String = shellQuoteValue(value)

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
    private suspend fun listSessionRowsFromLiveClient(
        host: HostEntity,
        keyPath: String,
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
                    listOf(CONTROL_LIST_SESSIONS_COMMAND, CONTROL_LIST_PANES_COMMAND),
                )
            } ?: run {
                Log.w(
                    PROBE_LOG_TAG,
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
                    val baseRows = parseListSessionsRows(listSessions.output.joinToString(separator = "\n"))
                    val windowRows = if (listPanes != null && !listPanes.isError) {
                        parseSessionWindowRows(listPanes.output.joinToString("\n"))
                    } else {
                        emptyList()
                    }
                    val paneRows = activePaneRowsBySession(windowRows)
                    val windowsBySession = windowRows.groupBy { it.sessionName }

                    baseRows.map { row ->
                        val pane = paneRows[row.sessionName]
                        val windows = windowsBySession[row.sessionName].orEmpty().map { window ->
                            // Issue #716: the live-client path runs NO detector
                            // (the control channel can't host the ps/candidate
                            // scan), so resolve the raw kind affirmative-shell-
                            // aware — an interactive-shell pane is a confirmed
                            // Shell, anything else is presumed-agent Probing.
                            // NEVER emit a raw `Shell` for an undetected window:
                            // when watched roots are empty this path returns
                            // WITHOUT annotation and feeds the maintained tree
                            // directly, where a false `Shell` would downgrade a
                            // sticky agent (#716).
                            window.copy(agentKind = resolveUndetectedKind(window.command))
                        }
                        row.copy(
                            cwd = pane?.cwd ?: row.cwd,
                            agentKind = resolveUndetectedKind(
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

    private fun shellQuoteRemotePath(value: String): String =
        shellQuoteRemotePathValue(value)

    private suspend fun resolveRemoteDirectory(
        session: com.pocketshell.core.ssh.SshSession,
        path: String,
    ): Result<String> {
        val result = session.exec(pathAware("cd -- ${shellQuoteRemotePath(path)} && pwd -P"))
        return if (result.exitCode == 0) {
            Result.success(result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty())
        } else {
            Result.failure(RuntimeException(result.stderr.ifBlank { result.stdout }.trim()))
        }
    }

    /** Active-pane row carrying the per-session signals we use beyond cwd. */
    internal data class ActivePaneRow(
        val sessionName: String,
        val cwd: String?,
        val tty: String?,
        val command: String?,
        val windowIndex: Int? = null,
        val windowName: String? = null,
    )

    private data class WatchedRootProjectExpansion(
        val projectFoldersByRoot: Map<String, List<String>> = emptyMap(),
        val historyProjectFoldersByRoot: Map<String, List<String>> = emptyMap(),
        val resolvedWatchedRootPaths: Map<String, String> = emptyMap(),
    )

    internal companion object {
        /**
         * Logcat-grep tag for issue #470: emitted only when a
         * session-enumeration exec read trips its bounded timeout
         * ([execBounded]) and the gateway surfaces a retryable
         * `ConnectError` instead of hanging.
         */
        const val PROBE_LOG_TAG: String = "PsFolderProbe"

        /**
         * Issue #716: interactive-shell foreground commands that, when seen as
         * a pane's `#{pane_current_command}` with no agent match, constitute an
         * AFFIRMATIVE shell verdict ([isAffirmativeShellCommand]). Anything not
         * in this set (including a null/blank command) is treated as presumed-
         * agent / still-detecting (`Probing`), never downgraded to `Shell`.
         */
        val INTERACTIVE_SHELL_COMMANDS: Set<String> =
            setOf("bash", "zsh", "fish", "sh", "dash", "ksh", "tcsh", "csh")

        /**
         * Issue #716: an AFFIRMATIVE interactive-shell verdict. The pane's
         * `#{pane_current_command}` foreground command is one of the known
         * interactive shells, so the detector's "no agent match" is a confirmed
         * shell rather than an unfinished probe. This is the ONLY signal allowed
         * to downgrade a session to [SessionAgentKind.Shell]; a null/blank or
         * otherwise-unrecognised command is NOT affirmative (it stays Probing).
         * Matched on the command's basename, case-insensitively, with any
         * leading `-` (login-shell marker, e.g. `-bash`) stripped.
         */
        fun isAffirmativeShellCommand(command: String?): Boolean {
            val token = command?.trim()?.takeIf { it.isNotEmpty() } ?: return false
            val basename = token.substringAfterLast('/').removePrefix("-")
            return basename.lowercase() in INTERACTIVE_SHELL_COMMANDS
        }

        /**
         * Issue #716: resolve the agent kind for a pane/window the detector did
         * NOT match. An interactive-shell foreground command is an AFFIRMATIVE
         * shell verdict ([SessionAgentKind.Shell]); everything else — including
         * a null/blank command (the probe could not read it yet) or a non-shell
         * command we have simply not classified as an agent — is the presumed-
         * agent [SessionAgentKind.Probing]. NEVER downgrade to `Shell` on
         * absence of evidence: only a positively-seen interactive shell
         * confirms shell.
         */
        fun resolveUndetectedKind(command: String?): SessionAgentKind =
            if (isAffirmativeShellCommand(command)) {
                SessionAgentKind.Shell
            } else {
                SessionAgentKind.Probing
            }

        /**
         * Upper bound on a single session-enumeration SSH-exec probe read
         * ([execBounded]). The healthy `tmux list-sessions` /
         * `list-panes` / `pocketshell sessions` reads complete in tens of
         * milliseconds; this bound is generous enough to never trip a
         * slow-but-progressing read, yet tight enough that a fully wedged
         * read (issue #470's ~45s silent SLIRP hang) surfaces a retryable
         * `ConnectError` panel in a few seconds instead of leaving the
         * folder screen stuck in `Loading`.
         *
         * Kept small (3.5 s) so a fully wedged read surfaces a retryable
         * `ConnectError` panel in a few seconds rather than leaving the folder
         * screen stuck in `Loading`. (EPIC #679 replaced the legacy constant
         * 5 s discovery poll with an infrequent maintained-tree reconcile, so
         * there is no longer a tight poll cadence to stay below; this bound now
         * just protects the single in-flight reconcile probe.)
         */
        const val EXEC_READ_TIMEOUT_MS: Long = 3_500L

        /**
         * Issue #702: upper bound on the LIVE `-CC` client enumeration
         * round-trip ([listSessionRowsFromLiveClient]). The live path serves
         * the picker-gating session enumeration off the already-open shared
         * `-CC` control channel, which is strictly single-flight: every command
         * serializes on one `sendMutex`. When the picker enumerates while the
         * in-session terminal still holds that mutex (a Back-tap from a live
         * session, or a mid-attach/teardown window) and the holder never
         * releases, an UNBOUNDED enumeration would park forever and pin the
         * picker in `Loading` — zero new SSH sockets, no `PsFolderProbe`, the
         * #470 wedge signature. This bound makes the live path degrade to the
         * already-bounded SSH-lease enumeration ([execBounded]) instead. Sized
         * the same as [EXEC_READ_TIMEOUT_MS] so a healthy control round-trip
         * (tens of ms) is never tripped, yet a fully wedged channel surfaces the
         * lease fall-through within a few seconds.
         */
        const val LIVE_ENUM_TIMEOUT_MS: Long = 3_500L

        /**
         * Single-quote a value for safe interpolation into a POSIX shell
         * command (`'...'` with embedded single quotes escaped as
         * `'\''`). Used both for the `tmux send-keys` argument and the
         * `--dir` path inside the env-export prelude (issue #263), so a
         * folder path containing spaces, quotes, `;`, `$()`, etc. cannot
         * break out of its argument.
         */
        internal fun shellQuoteValue(value: String): String =
            "'" + value.replace("'", "'\\''") + "'"

        internal fun shellQuoteRemotePathValue(value: String): String {
            val trimmed = value.trim().ifBlank { "~" }
            return when {
                trimmed == "~" || trimmed == "\$HOME" -> "\$HOME"
                trimmed.startsWith("~/") -> "\$HOME/" + shellQuoteValue(trimmed.removePrefix("~/"))
                trimmed.startsWith("\$HOME/") -> "\$HOME/" + shellQuoteValue(trimmed.removePrefix("\$HOME/"))
                else -> shellQuoteValue(trimmed)
            }
        }

        internal fun normaliseProjectFolderName(value: String): String? {
            val trimmed = value.trim().trim('/')
            if (trimmed.isBlank()) return null
            if (trimmed == "." || trimmed == "..") return null
            if ('/' in trimmed || '\\' in trimmed) return null
            return trimmed
        }

        internal fun childPath(parentPath: String, childName: String): String {
            val parent = parentPath.trim().trimEnd('/')
            return if (parent.isEmpty() || parent == "/") "/$childName" else "$parent/$childName"
        }


        // tmux's `-F` format spec replaces tab bytes (0x09) in the
        // rendered output with `_` so a multi-field row delimited by
        // real tabs is mangled into a single column. The existing
        // dashboard wire shape (`SessionsDashboardViewModel.LIST_SESSIONS_COMMAND`)
        // dodges the same hazard by using `::` as a separator — tmux's
        // session names disallow colons (per tmux(1)'s "NAMES, WINDOWS,
        // AND PANES" section), so the delimiter is unambiguous on the
        // session-name column. Paths can technically contain colons on
        // exotic filesystems, but tmux's session_path is always the
        // realpath of an absolute directory — colons inside path
        // components are exceedingly rare and we accept the trade-off
        // (the path is the last column, so a stray `::` inside it would
        // be parsed verbatim including the colons; degraded but not
        // wrong).
        const val FIELD_SEP: String = "::"

        /**
         * Issue #692: delimiter line printed between the chained
         * `list-sessions` and `list-panes` output so the SINGLE enumeration
         * exec round-trip ([execEnumeration]) can be split back into the two
         * sections. Chosen to be unambiguous: it contains a `::` (the field
         * separator, which never starts a session row) plus a sentinel token
         * no tmux session name / path produces, so a stray match inside real
         * output is implausible.
         */
        const val ENUMERATION_MARKER: String = "__pocketshell_enum_$FIELD_SEP@@"

        const val LIST_SESSIONS_COMMAND: String =
            "tmux list-sessions -F " +
                "'#{session_name}$FIELD_SEP#{session_created}$FIELD_SEP" +
                "#{session_activity}$FIELD_SEP#{session_attached}$FIELD_SEP#{session_path}'"

        const val LIST_PANES_COMMAND: String =
            "tmux list-panes -a -F " +
                "'#{session_name}$FIELD_SEP#{window_index}$FIELD_SEP#{window_name}$FIELD_SEP" +
                "#{window_active}$FIELD_SEP#{pane_active}$FIELD_SEP" +
                "#{pane_current_path}$FIELD_SEP#{pane_tty}$FIELD_SEP#{pane_current_command}" +
                "$FIELD_SEP#{window_id}'"

        const val POCKETSHELL_SESSIONS_COMMAND: String = "pocketshell sessions list --by activity"
        const val POCKETSHELL_PROJECT_HISTORY_COMMAND: String =
            "pocketshell logs tail --kind agent --json -n 200"

        const val CONTROL_LIST_SESSIONS_COMMAND: String =
            "list-sessions -F " +
                "'#{session_name}$FIELD_SEP#{session_created}$FIELD_SEP" +
                "#{session_activity}$FIELD_SEP#{session_attached}$FIELD_SEP#{session_path}'"

        const val CONTROL_LIST_PANES_COMMAND: String =
            "list-panes -a -F " +
                "'#{session_name}$FIELD_SEP#{window_index}$FIELD_SEP#{window_name}$FIELD_SEP" +
                "#{window_active}$FIELD_SEP#{pane_active}$FIELD_SEP" +
                "#{pane_current_path}$FIELD_SEP#{pane_tty}$FIELD_SEP#{pane_current_command}" +
                "$FIELD_SEP#{window_id}'"

        /**
         * Parse the tab-delimited `list-sessions` output into
         * [FolderSessionRow]s. Each line carries five fields:
         * `session_name`, `session_created`, `session_activity`,
         * `session_attached`, `session_path`. Blank cwd surfaces as
         * `null` so the view model can route the row to the "Untracked"
         * group.
         */
        internal fun parseListSessionsRows(stdout: String): List<FolderSessionRow> =
            stdout.lineSequence()
                .mapNotNull(::parseRow)
                .toList()

        internal fun parsePocketshellSessionsRows(
            stdout: String,
            parser: HostTmuxSessionListParser = HostTmuxSessionListParser(),
        ): List<FolderSessionRow> =
            parser.parsePocketshellSessionsList(stdout).map { row ->
                FolderSessionRow(
                    sessionName = row.name,
                    lastActivity = row.lastActivity,
                    attached = row.attached,
                    cwd = null,
                    agentKind = SessionAgentKind.Shell,
                )
            }

        internal fun parsePocketshellProjectHistory(stdout: String): List<String> {
            val array = try {
                JSONArray(stdout)
            } catch (_: Throwable) {
                return emptyList()
            }
            val recentFirst = (array.length() - 1 downTo 0)
            val seen = linkedSetOf<String>()
            for (index in recentFirst) {
                val item = array.optJSONObject(index) ?: continue
                val cwd = item.stringOrNull("cwd")
                    ?: item.optJSONObject("detail")?.stringOrNull("cwd")
                    ?: item.stringOrNull("project_path")
                    ?: item.stringOrNull("worktree")
                    ?: item.optJSONObject("detail")?.stringOrNull("project_path")
                    ?: item.optJSONObject("detail")?.stringOrNull("worktree")
                    ?: continue
                val clean = cwd.trim().trimEnd('/').takeIf { it.isNotBlank() } ?: continue
                seen += clean.ifEmpty { "/" }
            }
            return seen.toList()
        }

        private fun parseRow(line: String): FolderSessionRow? {
            if (line.isBlank()) return null
            // limit=5 so a path containing the rare `::` literal still
            // parses (the rightmost column absorbs any trailing
            // separators).
            val parts = line.split(FIELD_SEP, limit = 5)
            if (parts.size < 4) return null
            val name = parts[0].trim()
            if (name.isEmpty()) return null
            val sessionPath = if (parts.size >= 5) parts[4].trim().ifBlank { null } else null
            return FolderSessionRow(
                sessionName = name,
                lastActivity = parts[2].trim().toLongOrNull(),
                attached = (parts[3].trim().toLongOrNull() ?: 0L) > 0L,
                cwd = sessionPath,
                // Default to Shell; the gateway will override this for
                // sessions where the agent detection probe finds a match.
                agentKind = SessionAgentKind.Shell,
            )
        }

        /**
         * Parse `list-panes -a` output into compact per-window rows. The
         * command emits one 8-field row per pane with window identity;
         * only the active pane in each window is kept.
         */
        internal fun parseSessionWindowRows(stdout: String): List<FolderSessionWindowRow> {
            val lines = stdout.lineSequence().filter { it.isNotBlank() }.toList()
            if (lines.isEmpty()) return emptyList()

            val rows = mutableListOf<FolderSessionWindowRow>()
            for (line in lines) {
                // limit=9 so `#{window_id}` (the trailing 9th field, #653) is
                // captured separately from `pane_current_command` (the 8th).
                // `pane_current_command` can itself contain the rare `::`
                // literal; with the id pinned to the LAST column the command
                // field absorbs any interior separators only when the id column
                // is absent (a pre-#653 cached row), so we read the id from the
                // last part and fall back to null when fewer than 9 parts.
                val parts = line.split(FIELD_SEP, limit = 9)
                if (parts.size < 8) continue
                val sessionName = parts[0].trim()
                if (sessionName.isEmpty()) continue
                val paneActive = (parts[4].trim().toLongOrNull() ?: 0L) > 0L
                if (!paneActive) continue
                rows += FolderSessionWindowRow(
                    sessionName = sessionName,
                    index = parts[1].trim().toIntOrNull(),
                    name = parts[2].trim().takeIf { it.isNotEmpty() },
                    active = (parts[3].trim().toLongOrNull() ?: 0L) > 0L,
                    cwd = parts[5].trim().takeIf { it.isNotEmpty() },
                    tty = parts[6].trim().takeIf { it.isNotEmpty() },
                    command = parts[7].trim().takeIf { it.isNotEmpty() },
                    windowId = parts.getOrNull(8)?.trim()?.takeIf { it.isNotEmpty() },
                )
            }
            return rows
        }

        internal fun activePaneRowsBySession(
            windows: List<FolderSessionWindowRow>,
        ): Map<String, ActivePaneRow> =
            windows
                .groupBy { it.sessionName }
                .mapValues { (_, rows) ->
                    val row = rows.firstOrNull { it.active } ?: rows.first()
                    ActivePaneRow(
                        sessionName = row.sessionName,
                        cwd = row.cwd,
                        tty = row.tty,
                        command = row.command,
                        windowIndex = row.index,
                        windowName = row.name,
                    )
                }

        /**
         * Parse `list-panes -a` output into a map from session name to
         * the active window's active-pane metadata.
         */
        internal fun parseActivePaneRows(stdout: String): Map<String, ActivePaneRow> =
            activePaneRowsBySession(parseSessionWindowRows(stdout))

        private fun JSONObject.stringOrNull(name: String): String? =
            when (val value = opt(name)) {
                null, JSONObject.NULL -> null
                is String -> value.takeIf { it.isNotBlank() }
                else -> null
            }
    }
}

private fun ExecResult.isPocketshellLogsMissing(): Boolean {
    if (exitCode == 127) return true
    val output = "$stderr\n$stdout"
    return output.contains("No such command 'logs'", ignoreCase = true) ||
        output.contains("No such command \"logs\"", ignoreCase = true)
}
