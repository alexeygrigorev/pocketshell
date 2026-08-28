package com.pocketshell.app.projects

import android.util.Log
import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.app.sessions.HostTmuxSessionRow
import com.pocketshell.app.sessions.launchTargetCollisionMessage
import com.pocketshell.app.sessions.remoteStartDirectoryExistsCommand
import com.pocketshell.app.sessions.startDirectoryMissingMessage
import com.pocketshell.app.tmux.TmuxSessionGeneration
import com.pocketshell.app.ssh.BoundedSessionExec
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
import com.pocketshell.core.tmux.TmuxRead
import com.pocketshell.core.tmux.TmuxTarget
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.model.parseAgentStateUpdatedAtEpochSec
import com.pocketshell.uikit.model.sessionAgentKindFromOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
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
 * `agentKind` is the session's classification. Epic #821: a session WE
 * launched carries a recorded `@ps_agent_kind` ([recordedKind]) which is the
 * SOLE authority for its kind — no detection round-trip at all. A FOREIGN
 * session (no recorded option) gets a single host-side ONE-SHOT guess via the
 * `pocketshell agents kind` daemon RPC (cgroup-scope classification), surfaced
 * as "we think it's X — confirm/pick". The old output-parsing detector
 * (`detectForPanes`) is hard-cut (D22).
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
    /**
     * Epic #821 Workstream A: the agent kind PocketShell recorded for this
     * session at launch, read back from the host-side `@ps_agent_kind` tmux
     * user option via the `list-sessions` enumeration. `null` when the
     * option is absent (a session we did not launch, i.e. a foreign
     * session). When non-null this is the AUTHORITATIVE kind — it wins over
     * output-parsing detection, which can no longer disagree with what the
     * session was actually launched as.
     */
    val recordedKind: SessionAgentKind? = null,
    /**
     * Exact raw host-side `@ps_agent_kind` id. It is intentionally separate
     * from [recordedKind]: custom registry ids can map to a closed rendering
     * family without being rewritten or lost in the client state.
     */
    val recordedKindId: String? = null,
    /**
     * Issue #858: the human label of the non-default profile this session
     * was launched with, read back from the host-side `@ps_agent_profile`
     * tmux user option (written by the `pocketshell agent` wrapper at launch,
     * same launch-time-recordable dimension as [recordedKind]). For example
     * `"Claude (Z.AI)"` for a z.ai-routed Claude session, so the tree can
     * distinguish it from a default Anthropic Claude. `null` when the option
     * is absent — a default Claude, a non-profiled launch, OR a legacy /
     * pre-#858 session whose tmux server never had the option set — so a
     * default session shows the plain kind with no spurious profile chip.
     */
    val recordedProfile: String? = null,
    /**
     * Issue #1237: the raw host-side `@ps_agent_state` tmux user-option value
     * (`idle` / `waiting_for_input` / `working`), written best-effort by the
     * generated agent stop/idle hook handlers (PR #1373). `null` when the option
     * is absent/empty (a foreign / never-hooked session, or a legacy tmux server)
     * — surfaced as [com.pocketshell.uikit.model.SessionAgentState.Unknown]
     * (no chip). Resolved to a [com.pocketshell.uikit.model.SessionAgentState]
     * with [agentStateUpdatedAt] + [lastActivity] at the entry-mapping boundary.
     */
    val agentStateRaw: String? = null,
    /**
     * Issue #1237: the `@ps_agent_state_updated_at` epoch-seconds timestamp of the
     * last [agentStateRaw] write. Compared against [lastActivity] to drop a
     * recorded resting state that has gone stale (the session produced output
     * after the hook fired). `null` when the host did not record a timestamp
     * (older host CLI) or the option is absent.
     */
    val agentStateUpdatedAt: Long? = null,
    /**
     * Issue #899: tmux's stable session id (`$N`) for this live tmux server.
     * It is carried with [sessionCreated] so later slices can derive a durable
     * app identity without relying on the folder-derived [sessionName].
     * `null` for legacy rows that predate the added column.
     */
    val tmuxSessionId: String? = null,
    /**
     * Issue #899: tmux `#{session_created}` epoch seconds. This field exists
     * on legacy rows too; a durable identity requires both this value and
     * [tmuxSessionId].
     */
    val sessionCreated: Long? = null,
    /** `tmux` or `aplexer`. Default keeps existing tmux-only rows unchanged. */
    val sessionManager: String = "tmux",
    val aplexerId: String? = null,
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
    /**
     * Epic #821 slice A2: the active pane's `#{pane_pid}`. Used ONLY for the
     * foreign-session one-shot kind guess (`pocketshell agents kind` daemon
     * RPC) — sessions we launched read their kind from `@ps_agent_kind` and
     * never consult the pid. 0 / null when the probe path predates the column.
     */
    val panePid: Long? = null,
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
 * Raised when a folder-list SSH-exec command (for example `tmux
 * list-sessions` or `tmux new-session`) connects and authenticates fine but its
 * output read never reaches EOF within [SshFolderListGateway.EXEC_READ_TIMEOUT_MS].
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
    "Remote tmux command read did not complete within " +
        "${timeoutMs}ms (connect+auth succeeded; the exec output never " +
        "reached EOF). Command: $command",
)

/**
 * What a create call's requested session name MEANS — issue #1820.
 *
 * Session-name uniqueness used to be decided on the CLIENT, by subtracting a
 * UI-cached session list (`existingNames`) from the derived base name, and was
 * then executed with an idempotent create (`tmuxctl create-detached` /
 * `tmux new-session -A -d`). That made correctness depend on a snapshot the
 * client cannot trust: on 2026-07-28 the in-session picker published `Ready`
 * with a list that OMITTED the very session the app was attached to over
 * `-CC`, so the "second session in this folder" derived the colliding base
 * name and the create failed with `open terminal failed: not a terminal`. The
 * user tapped Create and got an error instead of a session.
 *
 * (That error, not a silent no-op, is what a colliding create actually does:
 * `tmux new-session -A -d -s <taken>` turns into an ATTACH — `-d` is not the
 * "detach others" flag, `-D` is — and an attach over an SSH exec has no tty.
 * Much of the surrounding prose predates that finding and still calls the
 * collision idempotent; it is not.)
 *
 * The uniqueness decision therefore belongs to the HOST, at create time, on
 * the very session that performs the create — the only place that knows the
 * live truth. This enum is how a caller states which of the two genuinely
 * different intents it has, instead of every caller re-deriving a name from
 * whatever list it happens to hold.
 */
enum class SessionNamePolicy {
    /**
     * "Give me a NEW session; this name is just the base." The gateway resolves
     * the smallest free `-2`/`-3`… variant against the host's live session list
     * immediately before creating, so a stale, empty, or wrong client-side list
     * can no longer produce a collision. Every "+ New session" picker path uses
     * this.
     */
    UniqueOnHost,

    /**
     * "Create/attach EXACTLY this session." No disambiguation — the caller
     * names a specific session it means, so the idempotent attach-or-create
     * semantics are the point (e.g. recreating a session that went away under
     * the name the user is recovering).
     */
    ExactName,
}

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
 *    #{session_activity}\t#{session_attached}\t#{@ps_agent_kind}\t
 *    #{session_path}'` — the `@ps_agent_kind` user option (epic #821) is
 *    the agent kind PocketShell recorded at launch; when present it is the
 *    authoritative kind, overriding the detection probe below.
 *  - `tmux list-panes -a -F '#{session_name}\t#{window_index}\t
 *    #{window_name}\t#{window_active}\t#{pane_active}\t
 *    #{pane_current_path}\t#{pane_tty}\t#{pane_current_command}\t
 *    #{window_id}\t#{pane_pid}'` so the active window's active-pane cwd +
 *    TTY + foreground command supersede `session_path` when they disagree,
 *    while every window's active pane remains available for compact
 *    metadata. `#{pane_pid}` (epic #821 A2) feeds the foreign-session guess.
 *  - Kind classification (epic #821, hard-cut D22): a recorded
 *    `@ps_agent_kind` is the SOLE authority for a session WE launched (no
 *    round-trip). FOREIGN sessions (no recorded option) get a single
 *    host-side ONE-SHOT guess via the `pocketshell agents kind` daemon RPC
 *    ([AgentKindRemoteSource]) — one exec for the whole list, the daemon
 *    classifying each pane's cgroup scope by `#{pane_pid}`. The old
 *    output-parsing `detectForPanes` heuristic is DELETED. Sessions whose
 *    active pane the daemon does not name an agent for fall back to the
 *    affirmative-shell-aware [SessionAgentKind] resolution.
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
     * [namePolicy] states what [sessionName] MEANS — a base name that must
     * become a genuinely new session ([SessionNamePolicy.UniqueOnHost]) or the
     * exact session the caller intends ([SessionNamePolicy.ExactName]). See
     * [SessionNamePolicy]; it is deliberately required at every call site so a
     * new caller has to state its intent rather than inherit a default.
     *
     * Issue #1928: returns a [SessionCreateOutcome], not a bare name. A create
     * has two halves and the optional launch half can fail on its own, so the
     * result must distinguish full success ([SessionCreateOutcome.Created]),
     * create failure (`Result.failure`) and PARTIAL success
     * ([SessionCreateOutcome.LaunchFailed] — the tmux session exists, the
     * requested agent did not start). The outcome carries the RESOLVED session
     * name in both states, which for [SessionNamePolicy.UniqueOnHost] may carry
     * a `-2`/`-3` suffix the caller did not ask for.
     */
    suspend fun createSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        cwd: String,
        startCommand: String?,
        namePolicy: SessionNamePolicy,
    ): Result<SessionCreateOutcome>

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
     * Issue #883: kill ONE tmux WINDOW (`<sessionName>:<windowIndex>`) on the
     * remote via an SSH-exec `tmux kill-window` over the same fresh lease
     * [killSession] uses. The folder/session tree models each tmux window as
     * its own `[wN]` row, but "Stop session" used to always run
     * `kill-session`, taking the WHOLE session (every window) down. This is the
     * window-aware Stop: it removes only the targeted window. tmux destroys the
     * session itself when its last window closes, so stopping the only window
     * still ends the session (the common single-window case is unchanged).
     *
     * Verified-teardown contract (mirrors [killSession], #518/#655/#464): the
     * kill runs over the fresh gateway lease and is confirmed before success is
     * reported. A success carries [WindowKillOutcome.sessionSurvived]:
     *   - `false` — the kill closed the last window and tmux auto-destroyed the
     *     session (`tmux has-session` now fails). The caller drops the whole
     *     session row, exactly like a [killSession].
     *   - `true`  — sibling window(s) remain: the session is still present AND
     *     the targeted `windowIndex` is gone from `tmux list-windows`. The
     *     caller drops only the killed window row; siblings + the session stay.
     * A failure is returned when the session is still present and the targeted
     * window is STILL listed (the kill did not land) so the tree keeps the
     * still-live row.
     *
     * The default keeps unrelated fake gateways honest without forcing them to
     * learn window kills; production overrides it below.
     */
    suspend fun killWindow(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        windowIndex: Int,
    ): Result<WindowKillOutcome> = Result.failure(
        UnsupportedOperationException("Window kill is not available."),
    )

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
        expectedGeneration: TmuxSessionGeneration,
    ): Result<Unit> = Result.failure(UnsupportedOperationException("Session rename is not available."))

    /**
     * Epic #821 Slice 1: manually classify [sessionName] by writing the durable
     * host-side `@ps_agent_kind` tmux user option to [kind] over a warm SSH
     * lease (via [ManualKindWriter]). Used by both the "unknown → pick" path
     * (a foreign session with no recorded kind) and the "change kind" action
     * (rewrite the recorded kind of an already-classified session). The written
     * value is the SAME option `record_agent_kind` writes at launch, so it
     * reads back through the unchanged enumeration path
     * ([FolderSessionRow.recordedKind]) and survives reconnect — no extra
     * cache, one source of truth.
     *
     * The default keeps test fakes honest without forcing every unrelated fake
     * gateway to learn the write; production overrides it below.
     */
    suspend fun setRecordedKind(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        kind: SessionAgentKind,
    ): Result<Unit> = Result.failure(
        UnsupportedOperationException("Setting the recorded session kind is not available."),
    )
}

data class FolderImportPayload(
    val remoteName: String,
    val length: Long?,
    val openStream: () -> InputStream?,
    val cleanup: () -> Unit = {},
)

/**
 * Issue #883: result of a verified [FolderListGateway.killWindow].
 *
 * @property sessionSurvived `true` when sibling window(s) remain (the session
 *   is still present after the window kill); `false` when the killed window was
 *   the session's last and tmux auto-destroyed the session. The caller uses
 *   this to decide whether to drop just the window row or the whole session.
 */
data class WindowKillOutcome(
    val sessionSurvived: Boolean,
)

class SshFolderListGateway internal constructor(
    private val reposRemoteSource: ReposRemoteSource,
    private val activeTmuxClients: ActiveTmuxClients,
    private val sshLeaseManager: SshLeaseManager,
    private val sessionListParser: HostTmuxSessionListParser,
    // Issue #470/#1036: bound on a single folder-list SSH exec read. Defaults
    // to [EXEC_READ_TIMEOUT_MS] in production; the unit test overrides it to a
    // small deterministic value so the wedge/healthy split can be asserted on a
    // real dispatcher without virtual-vs-real time racing. Kept off the @Inject
    // constructor below so Hilt never has to provide a raw Long.
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
    /** Last-known-good engine families for raw @ps_agent_kind ids. */
    private val enginesGateway: EnginesGateway? = null,
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
        enginesGateway: EnginesGateway,
    ) : this(
        reposRemoteSource = reposRemoteSource,
        activeTmuxClients = activeTmuxClients,
        sshLeaseManager = sshLeaseManager,
        sessionListParser = sessionListParser,
        execReadTimeoutMs = EXEC_READ_TIMEOUT_MS,
        enginesGateway = enginesGateway,
    )

    constructor() : this(
        reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
        activeTmuxClients = ActiveTmuxClients(),
        sshLeaseManager = SshLeaseManager(
            connector = SshLeaseConnector { target ->
                com.pocketshell.core.ssh.DefaultSshLeaseConnector().connect(target)
            },
        ),
        sessionListParser = HostTmuxSessionListParser(),
        execReadTimeoutMs = EXEC_READ_TIMEOUT_MS,
        enginesGateway = null,
    )

    /** Compatibility seam for direct tests and non-Hilt callers. */
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
        enginesGateway = null,
    )

    // Epic #821 slice A2 (hard-cut, D22): the output-parsing kind detector
    // (`AgentConversationRepository.detectForPanes`) is DELETED. Sessions WE
    // launched are classified solely by the recorded `@ps_agent_kind`
    // ([FolderSessionRow.recordedKind]); FOREIGN sessions (no recorded kind)
    // get a single one-shot host-side guess via the `pocketshell agents kind`
    // daemon RPC ([AgentKindRemoteSource]) — "we think it's X", which the user
    // can confirm/pick. No per-attach output parsing remains in the list path.
    private val agentKindRemoteSource = com.pocketshell.app.agents.AgentKindRemoteSource()
    private val landingProbeOwner = FolderListLandingProbeOwner(reposRemoteSource)

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
        // chained, see [TmuxClient.sendChainedCommands]) avoids re-running
        // list-sessions/list-panes/agent-detect over the SSH lease.
        //
        // Issue #2377 (hard cut, D22): the live-client rows are NOT the whole
        // truth and this path used to `return` them as if they were whenever no
        // watched roots existed. A `-CC` control client is attached to exactly
        // ONE tmux server, so its `list-sessions` sees only that server's
        // socket. On the maintainer's host — 7 tmuxctl-managed sessions across 7
        // separate `tmuxctl-*` sockets plus 3 aplexer-managed ones — the app
        // attached to the session it had just created on the default socket and
        // rendered "1 active · 0 idle · 1 session" against a host that
        // `pocketshell sessions list --json` reported as 10. That is the same
        // default-socket-only enumeration #2348 explicitly forbade; the #2348
        // fix only ever covered the no-live-client branch below.
        //
        // So the live client keeps owning the RICH per-window/pane metadata for
        // the server it is attached to, but the tmuxctl+aplexer enumerator is
        // the authority for the SET of sessions, on every branch. That costs one
        // bounded exec on the (pooled, already-warm) lease — the same exec the
        // no-live-client branch already pays — and correctness outranks skipping
        // it.
        val liveRows = FolderListLiveClientEnumerator.enumerate(
            activeTmuxClients = activeTmuxClients,
            host = host,
            keyPath = keyPath,
            liveEnumTimeoutMs = liveEnumTimeoutMs,
            familyForRawId = { rawId -> enginesGateway?.familyForRawId(host.id, rawId) },
        )

        return try {
            withLeaseSession(
                host = host,
                keyPath = keyPath,
                passphrase = passphrase,
            ) { session ->
                // Issue #1876: finish the small required landing batch before
                // starting any best-effort channel on the shared mobile
                // transport. Optional decoration, kind annotation, and port
                // discovery then overlap under the unchanged 12-second bound.
                supervisorScope {
                    if (liveRows != null) {
                        // Live client already enumerated the sessions/panes in
                        // one control-mode round-trip, so the lease only carries
                        // the watched-root expansion — NOT a second
                        // list-sessions/list-panes pair. The foreign-kind guess
                        // still runs over the lease so the chips do not regress
                        // just because a control client is attached (the control
                        // channel can't run the host-wide scan it needs), and it
                        // is INDEPENDENT of the expansion, so the two overlap.
                        //
                        // Issue #2377: the tmuxctl+aplexer enumerator starts
                        // FIRST, alongside the required landing batch, exactly
                        // like the no-live-client branch — a serial extra hop is
                        // what blew the 12s mobile bound in #2348.
                        val enumeratorDeferred = async { fetchPocketshellEnumerator(session) }
                        val required = landingProbeOwner.executeRequired(
                            watchedRoots = watchedRoots,
                            includeEnumeration = false,
                            exec = { command -> session.execBounded(pathAware(command)) },
                        )
                        val ports = async { PortScanner.scan(session) }
                        val expansion = async {
                            val decorated = landingProbeOwner.executeOptional(
                                watchedRoots = watchedRoots,
                                required = required,
                                exec = { command -> session.execBounded(pathAware(command)) },
                            )
                            landingProbeOwner.buildWatchedRootExpansion(
                                host,
                                watchedRoots,
                                decorated,
                            )
                        }
                        val annotated = annotateAgentKinds(session, liveRows)
                        val probes = ReconcileSideProbes(
                            expansion = expansion,
                            ports = ports,
                        )
                        // Issue #2377: union the host-wide tmuxctl+aplexer
                        // enumerator over the single-socket live-client rows,
                        // with the enumerator as AUTHORITY — identical to the
                        // no-live-client branch. The live rows stay the overlay
                        // that contributes cwd/windows/kind for the sessions on
                        // the attached server.
                        val enumerator = enumeratorDeferred.await()
                        if (enumerator is FolderListPocketshellEnumerator.Fetch.Unavailable) {
                            return@supervisorScope enumeratorUnavailableResult()
                        }
                        probes.sessions(
                            FolderListPocketshellEnumerator.unionFolderSessionRows(
                                enumerator.rows,
                                annotated,
                            ),
                        )
                    } else {
                        // Issue #2348: start the tmuxctl+aplexer JSON enumerator
                        // alongside the required landing batch. A serial extra hop
                        // after list-sessions is what blew the 12s mobile bound.
                        val enumeratorDeferred = async { fetchPocketshellEnumerator(session) }
                        val required = landingProbeOwner.executeRequired(
                            watchedRoots = watchedRoots,
                            includeEnumeration = true,
                            exec = { command -> session.execBounded(pathAware(command)) },
                        )
                        val ports = async { PortScanner.scan(session) }
                        val expansion = async {
                            val decorated = landingProbeOwner.executeOptional(
                                watchedRoots = watchedRoots,
                                required = required,
                                exec = { command -> session.execBounded(pathAware(command)) },
                            )
                            landingProbeOwner.buildWatchedRootExpansion(
                                host,
                                watchedRoots,
                                decorated,
                            )
                        }
                        val probes = ReconcileSideProbes(
                            expansion = expansion,
                            ports = ports,
                        )
                        listSessionsFromNativeOrPocketshell(
                            session = session,
                            listSessions = required.listSessions,
                            listPanes = required.listPanes,
                            familyForRawId = { rawId ->
                                enginesGateway?.familyForRawId(host.id, rawId)
                            },
                            probes = probes,
                            pocketshellEnumerator = { enumeratorDeferred.await() },
                        )
                    }
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
    private suspend fun SshSession.execBounded(command: String): ExecResult {
        val result = BoundedSessionExec.execBounded(
            session = this,
            command = command,
            timeoutMs = execReadTimeoutMs,
            dispatcher = Dispatchers.IO,
            callerSite = TRAIL_CALLER_SITE,
        )
        if (result != null) return result
        Log.w(
            PROBE_LOG_TAG,
            "folder-list SSH exec read made no progress within ${execReadTimeoutMs}ms; " +
                "ABANDONING the exec + surfacing a bounded, retryable failure. The SHARED " +
                "lease transport stays UP — recovery is the refcount-aware evictIdle below " +
                "(issue #1641). cmd=${command.takeLast(48)}",
        )
        // Issue #1641: this used to `close()` the SHARED per-host lease
        // transport here, on the premise that "cancellation alone cannot
        // interrupt the in-flight blocking readBytes()". That premise is STALE
        // (since #1567 the read is interruptible and bounded channel-locally),
        // and the close was catastrophic: it tore down the transport the live
        // tmux `-CC` reader rides, whose read then threw SSHException — an
        // uncredited #1610 storm entry trigger, fired by an exec merely being
        // SLOW. It also bypassed the #758 refcount guard 20 lines below, so it
        // could yank a transport an ACTIVE session VM still held.
        //
        // Instead: surface the retryable timeout. [isStaleChannelSymptom] now
        // covers it, so `runLeaseAttempt` heals via `evictIdle` — which closes
        // the corpse ONLY when no live consumer holds it, and retries once on a
        // fresh lease. That preserves recovery for a genuinely dead transport
        // while never killing a slow-but-alive one.
        throw FolderListExecTimeoutException(command, execReadTimeoutMs)
    }

    private suspend fun <T> withLeaseSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        leasePurpose: String? = null,
        block: suspend (SshSession) -> T,
    ): Result<T> {
        val leaseTarget = host.toSshLeaseTarget(keyPath, passphrase, leasePurpose)
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
            // Issue #758 (test-only, inert in production — forcedStaleChannelSymptoms
            // defaults to 0): deterministically simulate one transient poll-time
            // stale-channel symptom over the shared lease so the eviction path
            // below runs against a lease an active session VM holds. The thrown
            // message matches [isChannelOpenFailure] so it is treated identically
            // to a real stale channel.
            if (forcedStaleChannelSymptoms.get() > 0 &&
                forcedStaleChannelSymptoms.getAndDecrement() > 0
            ) {
                throw IllegalStateException(
                    "open failed: injected stale-channel symptom (issue #758 test hook)",
                )
            }
            Result.success(block(lease.session))
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            // Issue #465/#665/#680: an "open failed" / dead-transport / "SSH
            // session is not connected" probe failure should EVICT a *genuinely
            // idle* pooled lease, not just release it back. A transport stuck
            // refusing channels (or one whose `isConnected` lies) still gets
            // handed back by the pool, so without eviction every folder-tree
            // poll would re-surface the same dead-end. Evicting it makes the
            // heal retry / next poll open a fresh transport that recovers the
            // tree.
            poisonedTransport = isStaleChannelSymptom(t)
            Result.failure(t)
        } finally {
            withContext(NonCancellable) {
                lease.release()
                if (poisonedTransport) {
                    // Issue #758: refcount-aware eviction. The picker poll
                    // shares the SAME `SshLeaseKey` an active TmuxSessionViewModel
                    // rides. An unconditional `disconnect` here force-closed that
                    // shared transport out from under the live session, so opening
                    // a different session after backing out to the picker fell to
                    // the COLD path (Connecting overlay + fresh handshake). After
                    // our own `lease.release()` above, `evictIdle` is a NO-OP while
                    // the session VM still holds the lease (refCount > 0) and only
                    // closes a transport NO live consumer holds. A transport an
                    // active session still owns is healed by the VM's own
                    // stale-lease path on its next attach, never torn down here.
                    runCatching { sshLeaseManager.evictIdle(leaseTarget.leaseKey) }
                }
            }
        }
    }


    private fun HostEntity.toSshLeaseTarget(
        keyPath: String,
        passphrase: CharArray?,
        leasePurpose: String? = null,
    ): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = hostname,
                port = port,
                user = username,
                credentialId = buildCredentialId(id, keyPath, leasePurpose),
                knownHostsId = "accept-all",
            ),
            key = SshKey.Path(File(keyPath)),
            passphrase = passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        )

    internal suspend fun listSessionsFromNativeOrPocketshell(
        session: SshSession,
        listSessions: ExecResult,
        // Issue #692/#1876: the `list-panes` half is fetched in the SAME
        // sectioned landing-probe exec as `list-sessions` and handed in here,
        // so this method never issues a second serial probe.
        // Null preserves the old behaviour for callers (tests) that only have a
        // list-sessions result — those re-fetch panes on demand.
        listPanes: ExecResult? = null,
        // Issue #1876: the watched-root expansion + port scan, already RUNNING
        // concurrently with the enumeration. Tests that
        // exercise only the tmux-vs-pocketshell fallback branching pass their
        // own probes via [serialSideProbes].
        familyForRawId: (String?) -> SessionAgentKind? = { null },
        probes: ReconcileSideProbes,
        pocketshellEnumerator: (suspend () -> FolderListPocketshellEnumerator.Fetch)? = null,
    ): FolderListResult {
        val loadEnumerator: suspend () -> FolderListPocketshellEnumerator.Fetch =
            pocketshellEnumerator ?: { fetchPocketshellEnumerator(session) }
        return when {
            listSessions.exitCode == 127 ||
                listSessions.stderr.contains("not found", ignoreCase = true) ->
                listSessionsWithFolderFromPocketshell(
                    session,
                    probes,
                    familyForRawId,
                    loadEnumerator(),
                ) ?: FolderListResult.ToolUnavailable
            listSessions.isTmuxServerAbsent() ->
                listSessionsWithFolderFromPocketshell(
                    session,
                    probes,
                    familyForRawId,
                    loadEnumerator(),
                ) ?: probes.sessions(emptyList())
            listSessions.exitCode != 0 ->
                listSessionsWithFolderFromPocketshell(
                    session,
                    probes,
                    familyForRawId,
                    loadEnumerator(),
                ) ?: FolderListResult.Failed(
                    listSessions.stderr.ifBlank { listSessions.stdout }
                        .ifBlank { "tmux exited ${listSessions.exitCode}" },
                )
            else -> {
                val baseRows = parseListSessionsRows(
                    stdout = listSessions.stdout,
                    familyForRawId = familyForRawId,
                )
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

                // Epic #821 (hard-cut D22): recorded `@ps_agent_kind` is the
                // sole kind authority; foreign sessions get the one-shot daemon
                // guess. No output-parsing detection on this list path.
                val annotated = annotateAgentKinds(session, merged)
                // Bare `tmux list-sessions` only sees the default socket (the
                // 3 leftover sessions). tmuxctl/t walks every tmuxctl-* socket.
                // Union the pocketshell enumerator so the phone matches the
                // terminal, and fold in aplexer rows as a second manager.
                val enumerator = loadEnumerator()
                // Issue #2377: an enumerator that could not RUN is not "no extra
                // sessions". Publishing the default-socket rows here would be a
                // confidently-wrong undercount; surface a retryable failure so
                // the view model keeps the last good tree instead.
                if (enumerator is FolderListPocketshellEnumerator.Fetch.Unavailable) {
                    return enumeratorUnavailableResult()
                }
                probes.sessions(
                    FolderListPocketshellEnumerator.unionFolderSessionRows(
                        enumerator.rows,
                        annotated,
                    ),
                )
            }
        }
    }

    /**
     * Issue #1876 test seam: build a [ReconcileSideProbes] for the
     * fallback-branch unit tests that call
     * [listSessionsFromNativeOrPocketshell] directly. It runs the SAME batched
     * landing probe + port scan production runs, just without the enumeration
     * half (those tests supply their own `listSessions` result).
     */
    internal suspend fun serialSideProbes(
        session: SshSession,
        host: HostEntity,
        watchedRoots: List<ProjectRootEntity>,
    ): ReconcileSideProbes = coroutineScope {
        val required = landingProbeOwner.executeRequired(
            watchedRoots = watchedRoots,
            includeEnumeration = false,
            exec = { command -> session.execBounded(pathAware(command)) },
        )
        ReconcileSideProbes(
            expansion = async {
                val decorated = landingProbeOwner.executeOptional(
                    watchedRoots = watchedRoots,
                    required = required,
                    exec = { command -> session.execBounded(pathAware(command)) },
                )
                landingProbeOwner.buildWatchedRootExpansion(host, watchedRoots, decorated)
            },
            ports = async { runCatching { PortScanner.scan(session) }.getOrDefault(emptyList()) },
        )
    }

    /**
     * Issue #2377: the host session enumerator could not be read this poll.
     *
     * The tempting alternative — publish whatever narrower enumeration we do
     * have (default-socket `tmux list-sessions`, or a live `-CC` client bound to
     * one tmuxctl socket) — is exactly the reported defect: the phone confidently
     * showed 1 of the host's 10 sessions. A [FolderListResult.Failed] is the
     * honest answer: on a screen that already has a tree the view model keeps it
     * and shows the refresh-failed message (`preserveReadyOnRefresh`), and on a
     * cold load it renders the retryable panel. Either way the user is never told
     * a wrong count is the truth.
     */
    private fun enumeratorUnavailableResult(): FolderListResult.Failed =
        FolderListResult.Failed(ENUMERATOR_UNAVAILABLE_MESSAGE)

    private suspend fun fetchPocketshellEnumerator(
        session: SshSession,
    ): FolderListPocketshellEnumerator.Fetch =
        FolderListPocketshellEnumerator.fetch(
            parser = sessionListParser,
            exec = { command -> session.execBounded(command) },
            jsonCommand = pathAware(FolderListPocketshellEnumerator.JSON_EXEC_BODY),
            humanCommand = pathAware(POCKETSHELL_SESSIONS_COMMAND),
        )

    private suspend fun listSessionsWithFolderFromPocketshell(
        session: SshSession,
        probes: ReconcileSideProbes,
        familyForRawId: (String?) -> SessionAgentKind? = { null },
        enumerator: FolderListPocketshellEnumerator.Fetch? = null,
    ): FolderListResult? {
        val fetched = enumerator ?: fetchPocketshellEnumerator(session)
        when (fetched) {
            is FolderListPocketshellEnumerator.Fetch.Json,
            FolderListPocketshellEnumerator.Fetch.Empty,
            -> return probes.sessions(fetched.rows)
            // Issue #2377: the enumerator is the ONLY source on this branch
            // (native tmux is missing / errored), so an unreadable enumerator
            // must not render as "this host has no sessions".
            FolderListPocketshellEnumerator.Fetch.Unavailable ->
                return enumeratorUnavailableResult()
            FolderListPocketshellEnumerator.Fetch.Failed -> return null
            is FolderListPocketshellEnumerator.Fetch.Human -> Unit
        }
        // Older hosts only have the human table. One bounded raw-tmux
        // enrichment is the only extra attempt, and only on that fallback —
        // JSON already owns `--json` (0.4.45) and must not spend a second hop.
        val structured = try {
            session.execBounded(pathAware(POCKETSHELL_SESSIONS_TMUX_COMMAND))
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        val structuredRows = structured
            ?.takeIf { it.exitCode == 0 }
            ?.let {
                parsePocketshellSessionsTmuxRows(
                    stdout = it.stdout,
                    familyForRawId = familyForRawId,
                    parser = sessionListParser,
                )
            }
        return probes.sessions(
            mergePocketshellSessionRows(fetched.rows, structuredRows),
        )
    }

    // Issue #2378: one classifier, shared with the launch path's reason text —
    // see [TmuxSocketSweep.isServerAbsentOutput].
    private fun ExecResult.isTmuxServerAbsent(): Boolean =
        TmuxSocketSweep.isServerAbsentOutput("$stdout\n$stderr")

    override suspend fun createSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        cwd: String,
        startCommand: String?,
        namePolicy: SessionNamePolicy,
    ): Result<SessionCreateOutcome> {
        return withLeaseSession(host, keyPath, passphrase) { session ->
            createSessionOnSession(
                session = session,
                sessionName = sessionName,
                cwd = cwd,
                startCommand = startCommand,
                namePolicy = namePolicy,
            )
        }
    }

    /**
     * Issue #726: route session creation through tmuxctl's memory-capped
     * `create-detached` verb so the session (and the agent the start command
     * launches inside it) runs in a cgroup-v2 `systemd-run --user` scope under
     * `robust.slice` with a per-session `MemoryMax`. A runaway agent is then
     * OOM-killed inside its own scope instead of taking the shared tmux server
     * (and the whole agent team) down — the cascade that motivated this issue.
     *
     * `create-detached` is detach-only (PocketShell attaches over its own
     * `-CC` transport afterwards) and idempotent: if the named session already
     * exists it is a no-op success, preserving the attach-or-create (`-A`)
     * semantics the raw `new-session -A -d` provided. The per-session `--mem`
     * is resolved by tmuxctl from config (`.robust-tmux` / `robust.toml`), so
     * it is NOT hard-coded here — a repo can self-declare its budget.
     *
     * Two-layer graceful fallback (creation never fails on any host):
     *  1. tmuxctl cannot run `create-detached` — the binary is absent OR an
     *     older tmuxctl lacks the verb — the capped command's capability probe
     *     exits with the [TMUXCTL_UNSUPPORTED_EXIT_CODE] sentinel, and we fall
     *     back to the raw `tmux new-session -A -d` byte-identical to the
     *     pre-#726 behaviour. A GENUINE create-detached runtime error (tmux or
     *     systemd-run failing) returns its own non-zero code and is surfaced,
     *     never swallowed as "missing".
     *  2. tmuxctl present but the host lacks `systemd-run`/cgroup v2 — tmuxctl
     *     itself creates an UNCAPPED `new-session -d` and exits 0 (handled
     *     inside the verb, no client change needed).
     *
     * Exposed as `internal` so the create + both fallback layers are covered by
     * JVM tests driving a fake [SshSession] (see FolderListGatewayFallbackTest).
     *
     * ## Issue #1928: the create half throws, the LAUNCH half never does
     *
     * Everything up to and including the tmux create still fails loudly: on
     * those paths nothing exists on the host, so a thrown `RuntimeException`
     * that surfaces as `Result.failure` is the honest answer. From the moment
     * the session EXISTS the contract flips — the session is the user's and we
     * keep it (issue #1928 non-goal: never kill a created session because its
     * optional launch failed), so every launch-half failure is reported as
     * [SessionCreateOutcome.LaunchFailed] instead.
     *
     * That covers all three ways the launch half can fail, which used to be
     * mis-reported in two different directions:
     *  - a NON-ZERO `send-keys` (pane target gone between create and send) was
     *    DISCARDED entirely and reported as full success — the reported defect;
     *  - the #759 outdated-host pre-flight and a bounded-exec timeout THREW,
     *    reporting "couldn't create session" while an orphan session sat on the
     *    host. The timeout was worse than cosmetic: `FolderListExecTimeoutException`
     *    is a stale-channel symptom, so `withLeaseSession` retried the WHOLE
     *    block on a fresh lease and created a SECOND session.
     */
    internal suspend fun createSessionOnSession(
        session: SshSession,
        sessionName: String,
        cwd: String,
        startCommand: String?,
        namePolicy: SessionNamePolicy,
    ): SessionCreateOutcome {
        if (session.execBounded(remoteStartDirectoryExistsCommand(cwd)).exitCode != 0) {
            throw RuntimeException(
                startDirectoryMissingMessage(
                    sessionName = sessionName,
                    startDirectory = cwd,
                ),
            )
        }
        // Issue #1820: for a "give me a NEW session" create, resolve the free
        // name HERE — on the host, on the very SshSession that is about to
        // create — instead of trusting a client-side list. See [SessionNamePolicy].
        val resolvedName = when (namePolicy) {
            SessionNamePolicy.ExactName -> sessionName
            SessionNamePolicy.UniqueOnHost -> resolveFreeSessionName(session, sessionName)
        }
        val quotedName = shellQuote(resolvedName)
        val quotedCwd = shellQuote(cwd)
        // Issue #976: routing-safety guard for a LAUNCH create (startCommand
        // set). The create commands (`create-detached` / `new-session -A`) are
        // intentionally idempotent — re-picking the same folder ATTACHES to the
        // already-open same-named session rather than erroring (#642/#429). That
        // idempotency is correct for a plain re-pick, but it is the misroute trap
        // for an agent/shell LAUNCH: session names are a pure path-prefix shared
        // by agent AND shell (#642), so a new Codex launch in a dir that already
        // has an open Claude session derives the SAME name, the idempotent create
        // is a no-op REUSE of the live session, and `send-keys -t '<name>'` types
        // the launch line straight into the currently-attached pane (the
        // maintainer's #976 report).
        //
        // Since #1820 the [SessionNamePolicy.UniqueOnHost] resolution above
        // normally makes this unreachable — the name it picked was free on the
        // host moments earlier. It STAYS as the last line of defence, because it
        // is the only check that covers the two cases the resolver cannot: an
        // [SessionNamePolicy.ExactName] caller, and a resolver whose probe failed
        // and fell back to the requested base. Typing into a pre-existing
        // (possibly current) pane is exactly the #968-class misroute we refuse, so
        // surface a clear error rather than silently leaking keystrokes. A plain
        // shell/no-launch create keeps its idempotent attach-or-create semantics.
        //
        // Issue #2378: the guard now asks EVERY tmux socket, not just the
        // default one — tmuxctl runs one server per session, so a live
        // same-named session usually sits where a bare `tmux has-session`
        // cannot see it. See [TmuxSocketSweep].
        val alreadyLive = when (locateSessionSocket(session, resolvedName)) {
            is SessionSocket.Located -> true
            SessionSocket.Absent -> false
            // Sweep unusable on this host: degrade to the pre-#2378 probe.
            SessionSocket.Unknown -> session.execBounded(
                pathAware("tmux has-session -t ${shellQuote(TmuxTarget.session(resolvedName))}"),
            ).exitCode == 0
        }
        if (startCommand != null && alreadyLive) {
            throw RuntimeException(launchTargetCollisionMessage(resolvedName))
        }
        if (alreadyLive) {
            // Issue #2378: a no-launch create of an ALREADY live name is the
            // idempotent attach-or-create case (#642/#429) — answer with the
            // existing session. Creating anyway targets the DEFAULT socket
            // (where both the `create-detached` fallback layer and the raw
            // `new-session -A -d` land) and produces a second, distinct
            // same-named session on another socket: the reported orphan.
            return SessionCreateOutcome.Created(resolvedName)
        }
        val createResult = session.execBounded(
            pathAware(cappedCreateSessionCommand(quotedName, quotedCwd)),
        )
        if (createResult.exitCode == TMUXCTL_UNSUPPORTED_EXIT_CODE) {
            // Layer 1: tmuxctl is absent OR too old to know `create-detached`.
            // Fall back to the pre-#726 raw capped-less create so the user still
            // gets a session (just without the memory cap).
            val fallback = session.execBounded(
                pathAware(fallbackCreateSessionCommand(quotedName, quotedCwd)),
            )
            if (fallback.exitCode != 0) {
                throw RuntimeException(
                    fallback.stderr.trim().ifBlank {
                        "tmux session create failed with exit code ${fallback.exitCode}."
                    },
                )
            }
        } else if (createResult.exitCode != 0) {
            // tmuxctl ran the real `create-detached` verb and it genuinely
            // failed (tmux/systemd-run runtime error). Surface it — do NOT
            // silently fall back, or the user loses the memory cap without
            // knowing why.
            throw RuntimeException(
                createResult.stderr.trim().ifBlank {
                    "tmuxctl create-detached failed with exit code ${createResult.exitCode}."
                },
            )
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
            val failure = launchStartCommand(session, resolvedName, startCommand)
            if (failure != null) {
                // Issue #1928: the session EXISTS. Log the resolved name and the
                // host's reason (never the launch command — it can carry a
                // profile / provider argument) and hand the caller the partial
                // outcome so the user is told the truth.
                Log.w(
                    PROBE_LOG_TAG,
                    "session-create-launch-failed name=$resolvedName reason=$failure",
                )
                return SessionCreateOutcome.LaunchFailed(resolvedName, failure)
            }
        }
        return SessionCreateOutcome.Created(resolvedName)
    }

    /**
     * Issue #1928: run the post-create agent launch and REPORT its outcome —
     * `null` when the agent really started, otherwise the host's reason.
     *
     * Nothing in here throws (except cancellation): by the time it runs the
     * tmux session already exists, and a thrown failure would be reported to
     * the user as "couldn't create session" for a session that is sitting right
     * there. See the [createSessionOnSession] KDoc for the three failure shapes
     * this collapses into one honest answer.
     */
    private suspend fun launchStartCommand(
        session: SshSession,
        resolvedName: String,
        startCommand: String,
    ): String? {
        // Issue #759: an agent launch types the SHORT server-side wrapper
        // line `pocketshell agent <kind> --dir …`. The `agent` subcommand
        // only exists in pocketshell >= 0.3.34; on an OUTDATED host Click
        // answers `No such command 'agent'`. Because the line is typed into
        // a DETACHED pane via send-keys, that raw Click error would scroll
        // past inside the pane and the user would just see a dead session
        // with no idea why. So for agent launches we PRE-FLIGHT the same
        // warm lease session (D21 — no new connection) with
        // `pocketshell agent --help`: if the subcommand is missing we report
        // the actionable update hint as the launch failure and never type the
        // doomed line.
        try {
            if (AgentLaunchVersionCheck.isAgentLaunchCommand(startCommand)) {
                agentSubcommandUnavailableHint(session)?.let { return it }
            }
            // Issue #2378: type the launch into the server the session is
            // ACTUALLY on. A bare `tmux send-keys` talks to the default socket,
            // while a session created through `tmuxctl create-detached` lives on
            // its own `tmuxctl-<name>` socket — so the launch failed with
            // `no server running on /tmp/tmux-1000/default` while the session
            // sat healthy on another server. The sweep runs AFTER the create
            // (the socket does not exist before it) and picks the client;
            // Unknown falls back to the bare pre-#2378 form.
            val location = locateSessionSocket(session, resolvedName)
            val tmuxClient = (location as? SessionSocket.Located)?.tmuxClient ?: "tmux"
            // Issue #1820: EXACT pane target. A bare `-t <name>` prefix-matches,
            // so with `<name>-2` alive and `<name>` gone the launch line would be
            // typed into the NEIGHBOUR's pane — the #976 misroute, one line below
            // the guard that exists to prevent it. `=<name>:` is the exact form
            // for a pane target (see [TmuxTarget]).
            val sent = session.execBounded(
                pathAware(
                    "$tmuxClient send-keys -t ${shellQuote(TmuxTarget.pane(resolvedName))} " +
                        "${shellQuote(startCommand)} Enter",
                ),
            )
            if (sent.exitCode == 0) return null
            val hostReason = sent.stderr.trim().ifBlank { sent.stdout.trim() }
                .ifBlank { "tmux send-keys exited ${sent.exitCode}" }
            // Issue #2378: report the REAL cause — when the sweep proved no
            // socket holds this session, say that, not tmux's misleading
            // default-socket `no server running`.
            return if (location is SessionSocket.Absent) {
                TmuxSocketSweep.launchTargetMissingDetail(resolvedName, hostReason)
            } else {
                hostReason
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Throwable) {
            return error.message?.trim()?.ifBlank { null }
                ?: error.javaClass.simpleName
        }
    }

    /** Issue #1820/#2378: see [resolveFreeSessionNameOnHost]. */
    private suspend fun resolveFreeSessionName(
        session: SshSession,
        requestedName: String,
    ): String = resolveFreeSessionNameOnHost(
        requestedName = requestedName,
        exec = session.boundedExec(),
        enumeratedNames = { enumeratedSessionNames(session) },
    )

    /** Issue #2378: the aplexer half of the taken-name union — see above. */
    private suspend fun enumeratedSessionNames(session: SshSession): Set<String> {
        val enumerated = try {
            fetchPocketshellEnumerator(session)
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            return emptySet()
        }
        return enumerated.rows.mapNotNull { it.sessionName.trim().ifBlank { null } }.toSet()
    }

    /** Issue #2378: see [TmuxSocketSweep.locateSession]. */
    private suspend fun locateSessionSocket(
        session: SshSession,
        sessionName: String,
    ): SessionSocket = TmuxSocketSweep.locateSession(
        exec = session.boundedExec(),
        quotedName = shellQuote(sessionName),
    )

    /** The PATH-aware bounded exec [TmuxSocketSweep] probes run through. */
    private fun SshSession.boundedExec(): suspend (String) -> ExecResult =
        { command -> execBounded(pathAware(command)) }

    /**
     * Issue #759: pre-flight version guard for an agent launch. Probes the host
     * (over the already-warm lease [session]) for the `pocketshell agent`
     * subcommand; if it is missing — the host's `pocketshell` predates 0.3.34 —
     * returns the actionable "update pocketshell on the host" hint instead of
     * letting the cryptic `No such command 'agent'` Click error scroll past
     * inside the detached pane.
     *
     * Returns `null` when the probe succeeds (current host), i.e. the launch may
     * proceed. The version is fetched best-effort only when the probe shows a
     * mismatch, so the hint can name the concrete installed version; a
     * probe/version failure to fetch never blocks a healthy launch.
     *
     * Issue #1928 hard-cut (D22): this used to THROW the hint, which surfaced to
     * the user as "couldn't create session" even though the session had already
     * been created one step earlier. The hint is now the launch-failure reason
     * of a [SessionCreateOutcome.LaunchFailed] — same words, honest accounting.
     */
    private suspend fun agentSubcommandUnavailableHint(session: SshSession): String? {
        val probe = session.execBounded(pathAware(AgentLaunchVersionCheck.AGENT_PROBE_COMMAND))
        if (!AgentLaunchVersionCheck.isAgentSubcommandMissing(
                stdout = probe.stdout,
                stderr = probe.stderr,
                exitCode = probe.exitCode,
            )
        ) {
            return null
        }
        // Outdated host: best-effort fetch of the installed version so the hint
        // can be concrete ("this host's pocketshell is 0.3.33").
        val installedVersion = runCatching {
            val version = session.execBounded(pathAware(AgentLaunchVersionCheck.VERSION_PROBE_COMMAND))
            AgentLaunchVersionCheck.parseReportedVersion(
                version.stdout.ifBlank { version.stderr },
            )
        }.getOrNull()
        return AgentLaunchVersionCheck.mapLaunchFailureToHint(
            stdout = probe.stdout,
            stderr = probe.stderr,
            exitCode = probe.exitCode,
            installedVersion = installedVersion,
        ) ?: AgentLaunchVersionCheck.outdatedHint(installedVersion)
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
            // Issue #1820: EXACT session target on BOTH the kill and its
            // verify. A bare `-t <name>` prefix-matches, so with `<name>` and
            // `<name>-2` both alive the kill lands correctly but the verify
            // prefix-matches the surviving `<name>-2`, reports "still running",
            // and the row never leaves the tree — closed issue #168 returning.
            // Since #1820 the `<base>` + `<base>-2` pair is the ROUTINE result
            // of a second same-folder create, so this is reliably wrong for the
            // older sibling rather than a rare edge.
            val quotedName = shellQuote(TmuxTarget.session(target))
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

    override suspend fun killWindow(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        windowIndex: Int,
    ): Result<WindowKillOutcome> {
        val target = sessionName.trim()
        if (target.isEmpty()) {
            return Result.failure(IllegalArgumentException("No session to stop."))
        }
        if (windowIndex < 0) {
            return Result.failure(IllegalArgumentException("Invalid window index."))
        }
        return withLeaseSession(host, keyPath, passphrase) { session ->
            // Issue #1820: EXACT targets throughout. A bare `-t <session>:<i>`
            // prefix-matches the SESSION half, so with `<name>` gone and
            // `<name>-2` alive `kill-window -t '<name>:0'` destroys the
            // NEIGHBOUR's window (reproduced on tmux 3.4 — it took the whole
            // sibling session down with it). The `has-session`/`list-windows`
            // verifies below would then report on the neighbour too.
            val quotedName = shellQuote(TmuxTarget.session(target))
            // Issue #883: target the specific window of the session by index.
            // The colon target `<session>:<index>` is a tmux window target, so
            // kill-window removes ONLY that window. We single-quote the whole
            // `=session:index` so a session name with shell metacharacters is
            // safe (the index is a plain integer).
            val quotedWindow = shellQuote(TmuxTarget.window(target, windowIndex))
            session.exec(pathAware("tmux kill-window -t $quotedWindow"))
            // Authoritative check. tmux destroys the session when its LAST
            // window closes, so the kill "succeeded" in two distinct shapes:
            //   * the session is GONE (last window closed) — has-session fails;
            //   * the session is STILL present but the targeted window index is
            //     no longer listed (a sibling window survived).
            // It FAILED only when the session is present AND the targeted index
            // is still listed — surface a failure so the tree keeps the row.
            val hasSession = session.exec(
                pathAware("tmux has-session -t $quotedName"),
            )
            if (hasSession.exitCode != 0) {
                // Last window closed → tmux auto-destroyed the session.
                WindowKillOutcome(sessionSurvived = false)
            } else {
                val listWindows = session.exec(
                    pathAware(
                        // Issue #2160: `-u` — this listing is PARSED (the
                        // remaining window indices decide whether the kill
                        // succeeded), so it must not be read through a tmux
                        // client that sanitises what it prints. See [TmuxRead].
                        "${TmuxRead.CLIENT} list-windows -t $quotedName -F '#{window_index}'",
                    ),
                )
                val remainingIndices = listWindows.stdout
                    .lineSequence()
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .toList()
                if (remainingIndices.contains(windowIndex.toString())) {
                    throw RuntimeException(
                        "tmux window '$target:$windowIndex' is still running.",
                    )
                }
                WindowKillOutcome(sessionSurvived = true)
            }
        }
    }

    override suspend fun renameSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        oldName: String,
        newName: String,
        expectedGeneration: TmuxSessionGeneration,
    ): Result<Unit> = renameSessionWithGeneration(
        host = host,
        keyPath = keyPath,
        passphrase = passphrase,
        oldName = oldName,
        newName = newName,
        expectedGeneration = expectedGeneration,
        withLeaseSession = { targetHost, targetKeyPath, targetPassphrase, block ->
            withLeaseSession(
                targetHost,
                targetKeyPath,
                targetPassphrase,
                block = block,
            )
        },
        pathAware = ::pathAware,
        shellQuote = ::shellQuote,
    )

    override suspend fun setRecordedKind(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        kind: SessionAgentKind,
    ): Result<Unit> {
        val target = sessionName.trim()
        if (target.isEmpty()) {
            return Result.failure(IllegalArgumentException("No session to classify."))
        }
        return withLeaseSession(host, keyPath, passphrase) { session ->
            ManualKindWriter.write(
                session = session,
                sessionName = target,
                kind = kind,
            )
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
        return try {
            withLeaseSession(
                host = host,
                keyPath = keyPath,
                passphrase = passphrase,
                leasePurpose = LEASE_PURPOSE_IMPORT,
            ) { session ->
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
        } finally {
            payload.cleanup()
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
        val foreignGuess = runCatching {
            guessForeignAgentKinds(session = session, rows = rows)
        }.getOrDefault(emptyMap())
        return rows.map { row ->
            // Epic #821: a recorded `@ps_agent_kind` is the SOLE authority for a
            // session WE launched — no detection round-trip at all. FOREIGN
            // sessions (no recorded option) get the one-shot host-side daemon
            // guess ([guessForeignAgentKinds]); when the daemon does not name an
            // agent the chip falls back to the affirmative-shell-aware
            // [resolveUndetectedKind].
            val recorded = row.recordedKind
            val windows = row.windows.map { window ->
                val key = WindowProbeKey(row.sessionName, window.index)
                window.copy(
                    agentKind = recorded
                        ?: foreignGuess[key]
                        ?: resolveUndetectedKind(window.command),
                )
            }
            val sessionKind = recorded
                ?: row.windows
                    .firstOrNull { it.active }
                    ?.let { foreignGuess[WindowProbeKey(row.sessionName, it.index)] }
                ?: row.windows
                    .asSequence()
                    .mapNotNull { foreignGuess[WindowProbeKey(row.sessionName, it.index)] }
                    .firstOrNull()
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
     * Epic #821 slice A2 (hard-cut, D22): classify FOREIGN session windows (no
     * recorded `@ps_agent_kind`) with the host-side ONE-SHOT daemon guess
     * (`pocketshell agents kind` / `agents.kind_for_panes`). Only foreign rows
     * are probed — recorded rows already know their kind and are skipped, so a
     * project list of sessions-we-launched issues ZERO kind round-trips. Each
     * foreign window's active pane is sent as `(WindowProbeKey, pane_pid)`; the
     * daemon resolves the pid's cgroup scope and returns the agent kind. A
     * `none`/`unknown`/absent verdict leaves the window out of the map so the
     * caller falls back to [resolveUndetectedKind]. One host-side exec for the
     * whole list (the CLI batches every pane in one RPC). Best-effort: any
     * failure yields an empty map and the rows degrade to the undetected
     * fallback exactly as before.
     */
    private suspend fun guessForeignAgentKinds(
        session: com.pocketshell.core.ssh.SshSession,
        rows: List<FolderSessionRow>,
    ): Map<WindowProbeKey, SessionAgentKind> {
        val probeKeys = mutableMapOf<String, WindowProbeKey>()
        val panes = rows
            // Recorded sessions are the sole authority for their kind — never
            // probed (AC: recorded sessions have zero kind round-trips).
            .filter { it.recordedKindId == null }
            .flatMap { row ->
                row.windows.mapNotNull { window ->
                    val panePid = window.panePid?.takeIf { it > 0L } ?: return@mapNotNull null
                    val key = WindowProbeKey(row.sessionName, window.index)
                    val probeKey = key.asProbeKey()
                    probeKeys[probeKey] = key
                    com.pocketshell.app.agents.AgentKindRemoteSource.PaneRef(
                        paneId = probeKey,
                        panePid = panePid,
                    )
                }
            }
        if (panes.isEmpty()) return emptyMap()
        val verdicts = agentKindRemoteSource.classify(session = session, panes = panes)
        return verdicts.mapNotNull { (probeKey, verdict) ->
            val key = probeKeys[probeKey] ?: return@mapNotNull null
            val kind = verdict.kind?.toSessionAgentKind() ?: return@mapNotNull null
            key to kind
        }.toMap()
    }

    private fun AgentKind.toSessionAgentKind(): SessionAgentKind =
        when (this) {
            AgentKind.ClaudeCode -> SessionAgentKind.Claude
            AgentKind.Codex -> SessionAgentKind.Codex
            AgentKind.OpenCode -> SessionAgentKind.OpenCode
            AgentKind.GrokBuild -> SessionAgentKind.Grok
        }


    private data class WindowProbeKey(
        val sessionName: String,
        val windowIndex: Int?,
    ) {
        fun asProbeKey(): String = "$sessionName$FIELD_SEP${windowIndex ?: "active"}"
    }

    private fun pathAware(command: String): String =
        ReposRemoteSource.pathAwareCommand(command)

    private fun shellQuote(value: String): String = shellQuoteValue(value)

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

    internal companion object {
        /**
         * Logcat-grep tag for bounded folder-list SSH exec reads. Emitted only
         * when an exec trips [execBounded] and the gateway surfaces a bounded
         * failure instead of hanging.
         */
        const val PROBE_LOG_TAG: String = "PsFolderProbe"

        internal const val LEASE_PURPOSE_IMPORT: String = "folder-import"

        internal fun buildCredentialId(
            hostId: Long,
            keyPath: String,
            leasePurpose: String?,
        ): String {
            val base = "$hostId:$keyPath"
            val purpose = leasePurpose?.trim()?.takeIf { it.isNotEmpty() } ?: return base
            return "$base|purpose=$purpose"
        }

        /**
         * Issue #758 — DETERMINISTIC test injection of a poll-time stale-channel
         * symptom. The back→open-B reconnect bug only reproduces when the picker
         * poll over the shared SSH lease hits a transient stale-channel/EOF
         * symptom and the gateway evicts the lease the active session VM holds.
         * On a healthy `agents:2222` link that symptom is timing-dependent, so a
         * per-PR CI journey test can't rely on poll luck (the research's risk #2).
         * This counter lets the test ARM exactly N forced symptoms: while > 0,
         * the NEXT lease-path enumeration [runLeaseAttempt] throws ONE
         * stale-channel symptom (decrementing the counter) so the production
         * eviction path runs against the shared lease — exactly the bug.
         *
         * Production default is 0 → completely inert (no behaviour change). Only
         * the #758 androidTest arms it. Kept as an [AtomicInteger] so the test's
         * instrumentation thread and the gateway's poll coroutine see a coherent
         * value without extra synchronisation.
         */
        @JvmField
        val forcedStaleChannelSymptoms: java.util.concurrent.atomic.AtomicInteger =
            java.util.concurrent.atomic.AtomicInteger(0)

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
         * Upper bound on a single session-enumeration SSH-exec probe read.
         * #1876 keeps this existing bound; batching removes serial round trips
         * instead of moving the timeout cliff.
         */
        const val EXEC_READ_TIMEOUT_MS: Long = 3_500L

        /** Stable, non-PII attribution token for the cause trail (#1641). */
        const val TRAIL_CALLER_SITE: String = "folder_list_probe"

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
         * already-bounded SSH-lease enumeration ([execBounded]) instead.
         *
         * #1876 keeps this existing fall-through bound unchanged.
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

        /**
         * Issue #2160: `tmux -u` — the exec form of the enumeration reads FOUR
         * user options (`@ps_agent_kind`, `@ps_agent_profile`, `@ps_agent_state`,
         * `@ps_agent_state_updated_at`) plus `session_path`. A tmux client
         * without a UTF-8 locale sanitises every byte it prints, so on a host
         * whose sshd exports no locale (a container, Alpine/BusyBox, hardened
         * sshd) each non-printable byte and each multi-byte UTF-8 sequence in
         * those fields comes back as `_`. See [com.pocketshell.core.tmux.TmuxRead].
         */
        const val LIST_SESSIONS_COMMAND: String =
            "${TmuxRead.CLIENT} list-sessions -F " +
                "'#{session_name}$FIELD_SEP#{session_id}$FIELD_SEP#{session_created}$FIELD_SEP" +
                "#{session_activity}$FIELD_SEP#{session_attached}$FIELD_SEP" +
                "#{@ps_agent_kind}$FIELD_SEP#{@ps_agent_profile}$FIELD_SEP" +
                "#{@ps_agent_state}$FIELD_SEP#{@ps_agent_state_updated_at}$FIELD_SEP#{session_path}'"

        /** Issue #2160: `tmux -u` — see [LIST_SESSIONS_COMMAND]. */
        const val LIST_PANES_COMMAND: String =
            "${TmuxRead.CLIENT} list-panes -a -F " +
                "'#{session_name}$FIELD_SEP#{window_index}$FIELD_SEP#{window_name}$FIELD_SEP" +
                "#{window_active}$FIELD_SEP#{pane_active}$FIELD_SEP" +
                "#{pane_current_path}$FIELD_SEP#{pane_tty}$FIELD_SEP#{pane_current_command}" +
                "$FIELD_SEP#{window_id}$FIELD_SEP#{pane_pid}'"

        /**
         * Issue #726: sentinel exit code the [cappedCreateSessionCommand]
         * capability probe emits when this host's tmuxctl CANNOT run
         * `create-detached` — either because the binary is absent OR because an
         * older (pre-#726) tmuxctl predates the verb. It is the single signal
         * the create path uses to fall back to the raw uncapped
         * `tmux new-session -A -d` (layer-1 fallback).
         *
         * It is deliberately a sentinel the probe assigns explicitly (not a
         * value the real `create-detached` verb can produce), so a GENUINE
         * runtime failure of `create-detached` — tmux or `systemd-run` erroring
         * — returns its OWN non-zero code and is surfaced to the user rather
         * than being mistaken for "tmuxctl missing" and silently downgraded to
         * an uncapped session. `97` is outside the ranges tmux/systemd-run use
         * and outside the 0–2/64/126/127 codes shells and typer emit for
         * "missing binary"/"usage error"/"no such command".
         */
        const val TMUXCTL_UNSUPPORTED_EXIT_CODE: Int = 97

        /**
         * Issue #726: the memory-capped session-create command. Routes the
         * detached create through tmuxctl's `create-detached <name> -c <cwd>`
         * verb so the session shell is wrapped in a `systemd-run --user --scope`
         * with a per-session `MemoryMax` under `robust.slice`. The per-session
         * `--mem` is intentionally NOT passed here — tmuxctl resolves it from
         * config (`.robust-tmux` in the cwd/git-root, then `robust.toml`,
         * then its built-in default), so a repo can self-declare its budget
         * without an app change.
         *
         * The command is a small POSIX-sh wrapper that first PROBES whether
         * this host's tmuxctl can actually run `create-detached`, and only then
         * invokes it:
         *  - `command -v tmuxctl` fails -> binary absent -> exit the
         *    [TMUXCTL_UNSUPPORTED_EXIT_CODE] sentinel so the client falls back.
         *  - `tmuxctl create-detached --help` fails -> an OLDER tmuxctl that
         *    lacks the verb (typer exits 2 for an unknown command; the old
         *    fixture stub exited 64) -> same sentinel, fall back. `--help` is a
         *    side-effect-free capability check.
         *  - both succeed -> run the real `create-detached`; its exit code
         *    (0 on success/idempotent no-op, non-zero on a genuine
         *    tmux/systemd-run error) is what the client sees and acts on.
         *
         * This makes the "fall back" signal robust to BOTH binary-absent and
         * verb-absent hosts while never swallowing a real create failure.
         *
         * Issue #1170: the FINAL real `create-detached` invocation runs with the
         * daemon's inherited standard fds pointed AWAY from the SSH exec channel
         * (`</dev/null >/dev/null 2>"$errfile"`). tmuxctl starts the detached
         * tmux SERVER under a `systemd-run --user --scope` wrapper that — unlike
         * a plain `tmux new-session -d`, which double-forks and re-opens /dev/null
         * — keeps the spawned server attached to the scope and lets it INHERIT
         * the caller's stdout/stderr. When those are the SSH exec channel (as they
         * are for [execBounded]'s blocking read), the daemon holds the channel
         * open, the read never reaches EOF, and the create FALSE-fails on the
         * [EXEC_READ_TIMEOUT_MS] bound even though the session was created — after
         * which the agent-launch never runs and the session is left a bare shell.
         * Redirecting the create's own stdin/stdout to /dev/null (so the inherited
         * server fds are /dev/null, not the channel) lets the exec reach EOF as
         * soon as tmuxctl returns. tmuxctl's OWN stderr is captured to a temp file
         * and echoed back to the channel AFTERWARDS (`cat "$errfile" >&2`), so a
         * GENUINE create error (tmux / systemd-run failure) is still surfaced to
         * the client with its real message — the redirect fixes the false hang
         * without masking real failures. The create's exit status is preserved via
         * `$?` and re-exited so the client's exit-code checks are unchanged.
         * (`command -v` / `--help` probes stay as-is — they spawn no daemon.)
         *
         * [quotedName] and [quotedCwd] must already be shell-quoted by the
         * caller (via [shellQuoteValue]).
         */
        internal fun cappedCreateSessionCommand(quotedName: String, quotedCwd: String): String =
            "command -v tmuxctl >/dev/null 2>&1 || exit $TMUXCTL_UNSUPPORTED_EXIT_CODE; " +
                "tmuxctl create-detached --help >/dev/null 2>&1 || " +
                "exit $TMUXCTL_UNSUPPORTED_EXIT_CODE; " +
                "__ps_cd_err=\$(mktemp 2>/dev/null || printf %s \"\${TMPDIR:-/tmp}/ps-create-detached.\$\$\"); " +
                "tmuxctl create-detached $quotedName -c $quotedCwd " +
                "</dev/null >/dev/null 2>\"\$__ps_cd_err\"; " +
                "__ps_cd_rc=\$?; " +
                "[ -s \"\$__ps_cd_err\" ] && cat \"\$__ps_cd_err\" >&2; " +
                "rm -f \"\$__ps_cd_err\"; " +
                "exit \$__ps_cd_rc"

        /**
         * Issue #726: the pre-#726 raw, uncapped fallback create — used only
         * when this host's tmuxctl cannot run `create-detached`
         * ([TMUXCTL_UNSUPPORTED_EXIT_CODE]). Byte-identical to the historical
         * command:
         *  - `-A` so an existing session with the same name attaches rather than
         *    failing (idempotent for the user — re-picking "Create" never errors).
         *  - `-d` so the session is detached on the server (the app attaches via
         *    `tmux -CC` after navigation).
         *
         * [quotedName] and [quotedCwd] must already be shell-quoted by the
         * caller (via [shellQuoteValue]).
         */
        internal fun fallbackCreateSessionCommand(quotedName: String, quotedCwd: String): String =
            "tmux new-session -A -d -s $quotedName -c $quotedCwd"

        // Issue #2378 (hard cut, D22): the host-side `<base>`/`<base>-2`… walk
        // (`freeSessionNameCommand`, #1820) is GONE — it probed the DEFAULT
        // socket only, blind to tmuxctl's per-session servers. See
        // [TmuxSocketSweep] and [resolveFreeSessionName].

        const val POCKETSHELL_SESSIONS_COMMAND: String = "pocketshell sessions list --by activity"
        const val POCKETSHELL_SESSIONS_JSON_COMMAND: String = "pocketshell sessions list --json"

        /**
         * Issue #2377: user-visible reason for refusing to publish a session
         * list we know is incomplete.
         */
        const val ENUMERATOR_UNAVAILABLE_MESSAGE: String =
            "Couldn't read the host session list (pocketshell sessions list did not respond). " +
                "Not showing a partial list."
        /**
         * Optional raw-id enrichment for the *human-table* old-host fallback
         * only. JSON success must not issue this second hop (#2348). The seven
         * fields match the parser's identity-bearing shape and keep the exact
         * `@ps_agent_kind` value.
         */
        const val POCKETSHELL_SESSIONS_TMUX_FORMAT: String =
            "'#{session_id}$FIELD_SEP#{session_name}$FIELD_SEP#{session_created}" +
                "$FIELD_SEP#{session_activity}$FIELD_SEP#{session_attached}" +
                "$FIELD_SEP#{@ps_agent_kind}$FIELD_SEP#{session_path}'"
        const val POCKETSHELL_SESSIONS_TMUX_COMMAND: String =
            "${TmuxRead.CLIENT} list-sessions -F $POCKETSHELL_SESSIONS_TMUX_FORMAT"
        const val POCKETSHELL_PROJECT_HISTORY_COMMAND: String =
            "pocketshell logs tail --kind agent --json -n 200"

        /**
         * The `-CC`-form of [LIST_SESSIONS_COMMAND] (no leading `tmux`). It is
         * sent EITHER over the exec lane ([com.pocketshell.core.tmux.TmuxClient.listPanesViaExec]
         * / `sendLifecycleViaExec`, which prefix it with the locale-proof client)
         * OR as a control-mode command over the attached `tmux -CC` client.
         *
         * Issue #2160: the control-mode leg is NOT locale-proof — the flag would
         * have to move to the `-CC` attach itself, which changes the app's
         * control-client command line (an identity oracle keys on it) and is
         * connection-core surface. Measured, that leg is not a live hole: the four
         * user options it expands carry only printable ASCII today
         * (`@ps_agent_kind` claude/codex/opencode/shell, `@ps_agent_state`
         * idle/waiting_for_input/working, `@ps_agent_state_updated_at` ISO-8601,
         * `@ps_agent_profile` an ASCII label). Only a NON-ASCII profile label or
         * project path on a host with no UTF-8 locale would surface it, so it is
         * tracked as **issue #2175** rather than folded in here.
         */
        const val CONTROL_LIST_SESSIONS_COMMAND: String =
            "list-sessions -F " +
                "'#{session_name}$FIELD_SEP#{session_id}$FIELD_SEP#{session_created}$FIELD_SEP" +
                "#{session_activity}$FIELD_SEP#{session_attached}$FIELD_SEP" +
                "#{@ps_agent_kind}$FIELD_SEP#{@ps_agent_profile}$FIELD_SEP" +
                "#{@ps_agent_state}$FIELD_SEP#{@ps_agent_state_updated_at}$FIELD_SEP#{session_path}'"

        const val CONTROL_LIST_PANES_COMMAND: String =
            "list-panes -a -F " +
                "'#{session_name}$FIELD_SEP#{window_index}$FIELD_SEP#{window_name}$FIELD_SEP" +
                "#{window_active}$FIELD_SEP#{pane_active}$FIELD_SEP" +
                "#{pane_current_path}$FIELD_SEP#{pane_tty}$FIELD_SEP#{pane_current_command}" +
                "$FIELD_SEP#{window_id}$FIELD_SEP#{pane_pid}'"

        /**
         * Parse the delimiter-separated `list-sessions` output into
         * [FolderSessionRow]s. Each current line carries eight fields:
         * `session_name`, `session_id`, `session_created`, `session_activity`,
         * `session_attached`, `@ps_agent_kind`, `@ps_agent_profile`,
         * `session_path`. Blank cwd
         * surfaces as `null` so the view model can route the row to the
         * "Untracked" group. The `@ps_agent_kind` user option (epic #821
         * Workstream A) is the kind PocketShell recorded at launch; when
         * present it is the authoritative agent kind for the session,
         * read back here with no output-parsing detection. A blank value
         * means the session was not launched by us (a foreign session):
         * [FolderSessionRow.recordedKind] stays `null` and the row keeps the
         * Shell default so the existing detection probe (still running in
         * Slice A1) fills it as before. (Slice B will repurpose the blank
         * case for the foreign-session one-shot guess.)
         */
        internal fun parseListSessionsRows(
            stdout: String,
            familyForRawId: (String?) -> SessionAgentKind? = { null },
        ): List<FolderSessionRow> =
            stdout.lineSequence()
                .mapNotNull { line -> parseRow(line, familyForRawId) }
                .toList()

        internal fun parsePocketshellSessionsRows(
            stdout: String,
            parser: HostTmuxSessionListParser = HostTmuxSessionListParser(),
        ): List<FolderSessionRow> =
            parser.parsePocketshellSessionsList(stdout).map {
                with(FolderListPocketshellEnumerator) { it.toFolderSessionRow() }
            }

        internal fun parsePocketshellSessionsTmuxRows(
            stdout: String,
            familyForRawId: (String?) -> SessionAgentKind? = { null },
            parser: HostTmuxSessionListParser = HostTmuxSessionListParser(),
        ): List<FolderSessionRow> =
            parser.parseTmuxListSessions(stdout, familyForRawId)
                .map { with(FolderListPocketshellEnumerator) { it.toFolderSessionRow() } }

        /**
         * Keep the human list authoritative for ordering and compatibility,
         * while overlaying any structured raw-id metadata that arrived for the
         * same session. Structured-only rows are appended so a newer host can
         * still recover a row if its human renderer omitted it.
         */
        private fun mergePocketshellSessionRows(
            humanRows: List<FolderSessionRow>,
            structuredRows: List<FolderSessionRow>?,
        ): List<FolderSessionRow> {
            if (structuredRows.isNullOrEmpty()) return humanRows
            val structuredByName = structuredRows.associateBy { it.sessionName }
            val merged = humanRows.map { human ->
                val structured = structuredByName[human.sessionName] ?: return@map human
                human.copy(
                    lastActivity = structured.lastActivity ?: human.lastActivity,
                    attached = structured.attached,
                    cwd = structured.cwd ?: human.cwd,
                    agentKind = structured.agentKind,
                    recordedKind = structured.recordedKind,
                    recordedKindId = structured.recordedKindId,
                    agentStateRaw = structured.agentStateRaw ?: human.agentStateRaw,
                    agentStateUpdatedAt = structured.agentStateUpdatedAt ?: human.agentStateUpdatedAt,
                    tmuxSessionId = structured.tmuxSessionId ?: human.tmuxSessionId,
                    sessionCreated = structured.sessionCreated ?: human.sessionCreated,
                )
            }
            val humanNames = humanRows.mapTo(HashSet()) { it.sessionName }
            return merged + structuredRows.filter { it.sessionName !in humanNames }
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

        private fun parseRow(
            line: String,
            familyForRawId: (String?) -> SessionAgentKind?,
        ): FolderSessionRow? {
            if (line.isBlank()) return null
            // Field order (#899 + epic #821 + #858 + #1237): name, session_id,
            // created, activity, attached, @ps_agent_kind, @ps_agent_profile,
            // @ps_agent_state, @ps_agent_state_updated_at, session_path. Use the
            // current 10-column parse only when column 2 has tmux's `$N`
            // session-id shape; legacy rows put session_created there, and must
            // keep the old 7-column limit so a path containing the rare `::`
            // literal still parses. The rightmost column (session_path) absorbs
            // any trailing separators. `@ps_agent_kind` (claude/codex/opencode),
            // `@ps_agent_profile` (host-authored label), `@ps_agent_state`
            // (idle/waiting_for_input/working, controlled — issue #1237), and
            // `@ps_agent_state_updated_at` (epoch int) never contain `::`, so
            // they sit safely at their fixed columns before the path.
            //
            // A legacy / pre-#1237 tmux server has no `@ps_agent_state` /
            // `@ps_agent_state_updated_at` option set: tmux expands them to
            // empty strings (NOT missing columns), so the field count is
            // unchanged and the state parses as Unknown — no crash, no chip.
            val hasCurrentIdentityColumn =
                line.split(FIELD_SEP, limit = 3).getOrNull(1)?.trim()?.firstOrNull() == '$'
            val parts = line.split(FIELD_SEP, limit = if (hasCurrentIdentityColumn) 10 else 7)
            if (parts.size < 4) return null
            val name = parts[0].trim()
            if (name.isEmpty()) return null
            val hasIdentityColumns = hasCurrentIdentityColumn && parts.size >= 10
            val sessionIdIndex = if (hasIdentityColumns) 1 else null
            val createdIndex = if (hasIdentityColumns) 2 else 1
            val activityIndex = if (hasIdentityColumns) 3 else 2
            val attachedIndex = if (hasIdentityColumns) 4 else 3
            val kindIndex = if (hasIdentityColumns) 5 else 4
            val created = parts.getOrNull(createdIndex)?.trim()?.toLongOrNull()
            val tmuxSessionId = sessionIdIndex
                ?.let { parts.getOrNull(it)?.trim()?.ifBlank { null } }
            val recordedKindId = parts.getOrNull(kindIndex)
                ?.trim()
                ?.ifBlank { null }
            val recordedKind = recordedKindFromOption(recordedKindId, familyForRawId)
            // The current 10-field format puts @ps_agent_profile at index 6,
            // @ps_agent_state at 7, @ps_agent_state_updated_at at 8, and the
            // path at index 9. The previous 7-field format had profile at
            // index 5 and path at index 6 (no state columns). A stale 6-field
            // cache row (pre-#858) has NO profile column — its index 5 IS the
            // path. Distinguish by part count so an old row never misreads its
            // path as a profile.
            val hasProfileColumn = hasIdentityColumns || parts.size >= 7
            val recordedProfile =
                if (hasIdentityColumns) {
                    parts.getOrNull(6)?.trim()?.ifBlank { null }
                } else if (hasProfileColumn) {
                    parts.getOrNull(5)?.trim()?.ifBlank { null }
                } else {
                    null
                }
            // Issue #1237: agent-state columns exist ONLY on the current
            // identity-column format. On legacy rows they are absent → Unknown
            // (no chip), which is the correct "absent, not wrong" behaviour.
            val agentStateRaw =
                if (hasIdentityColumns) parts.getOrNull(7)?.trim()?.ifBlank { null } else null
            // Issue #1570: the host hook writes @ps_agent_state_updated_at as an
            // ISO-8601 string (datetime.isoformat()), not an epoch int — a bare
            // toLongOrNull() returned null, disabling the staleness rule. Parse
            // both shapes so a working agent's stale idle is correctly demoted.
            val agentStateUpdatedAt =
                if (hasIdentityColumns) parseAgentStateUpdatedAtEpochSec(parts.getOrNull(8)) else null
            val sessionPath =
                (if (hasIdentityColumns) {
                    parts.getOrNull(9)
                } else if (hasProfileColumn) {
                    parts.getOrNull(6)
                } else {
                    parts.getOrNull(5)
                })
                    ?.trim()?.ifBlank { null }
            return FolderSessionRow(
                sessionName = name,
                lastActivity = parts.getOrNull(activityIndex)?.trim()?.toLongOrNull(),
                attached = (parts.getOrNull(attachedIndex)?.trim()?.toLongOrNull() ?: 0L) > 0L,
                cwd = sessionPath,
                // The recorded `@ps_agent_kind` (epic #821) is authoritative
                // when present. When absent, default to Shell; the gateway
                // overrides that for sessions where the detection probe finds
                // a match (additive — detection still runs for foreign /
                // pre-#821 sessions).
                agentKind = recordedKind ?: SessionAgentKind.Shell,
                recordedKind = recordedKind,
                recordedKindId = recordedKindId,
                // Issue #858: a non-default profile label (e.g. z.ai Claude);
                // null for default / non-profiled / legacy sessions.
                recordedProfile = recordedProfile,
                // Issue #1237: raw agent-state option + its timestamp, resolved
                // to a chip state at the entry-mapping boundary (which also has
                // session_activity for staleness).
                agentStateRaw = agentStateRaw,
                agentStateUpdatedAt = agentStateUpdatedAt,
                tmuxSessionId = tmuxSessionId,
                sessionCreated = created,
            )
        }

        /**
         * Map a host-side `@ps_agent_kind` user-option value (written by the
         * `pocketshell agent` wrapper at launch, epic #821) to a
         * [SessionAgentKind]. Returns `null` for a blank/absent option (a
         * session we did not launch) or an unrecognised value, so the caller
         * falls back to detection / the Shell default rather than mislabeling
         * the session.
         */
        internal fun recordedKindFromOption(
            raw: String?,
            familyForRawId: (String?) -> SessionAgentKind? = { null },
        ): SessionAgentKind? {
            // The ui-kit mapping remains the single inverse for built-in ids.
            // Unknown nonblank ids are still recorded and become Unknown unless
            // the last engine-registry read supplies their declared family.
            val clean = raw?.trim()?.ifBlank { null } ?: return null
            return sessionAgentKindFromOption(clean)
                ?: familyForRawId(clean)
                ?: SessionAgentKind.Unknown
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
                // limit=10 so `#{pane_pid}` (the trailing 10th field, epic #821
                // A2) and `#{window_id}` (9th, #653) are captured separately from
                // `pane_current_command` (8th). Both trailing columns are
                // structured (`@N` / an integer) and never contain `::`.
                val parts = line.split(FIELD_SEP, limit = 10)
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
                    panePid = parts.getOrNull(9)?.trim()?.toLongOrNull(),
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

internal fun ExecResult.isPocketshellLogsMissing(): Boolean {
    if (exitCode == 127) return true
    val output = "$stderr\n$stdout"
    return output.contains("No such command 'logs'", ignoreCase = true) ||
        output.contains("No such command \"logs\"", ignoreCase = true)
}
