package com.pocketshell.app.tmux

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import com.pocketshell.app.assistant.AppAssistantActions
import com.pocketshell.app.assistant.AssistantActions
import com.pocketshell.app.assistant.AssistantInstallId
import com.pocketshell.app.assistant.AssistantSshExecutor
import com.pocketshell.app.assistant.AssistantSshParams
import com.pocketshell.app.assistant.AssistantUiState
import com.pocketshell.app.assistant.ExecutorTraceSink
import com.pocketshell.app.assistant.RealAssistantSshExecutor
import com.pocketshell.app.assistant.SessionActionBridge
import com.pocketshell.app.assistant.SessionAssistantController
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.OPTIMISTIC_USER_MESSAGE_ID_PREFIX
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.session.reconcileAgentEvents
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.core.assistant.AssistantLlmClientFactory
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.DEFAULT_TMUX_START_DIRECTORY
import com.pocketshell.app.sessions.SSH_SOURCE_TMUX_CONNECT
import com.pocketshell.app.sessions.SshOpenTelemetry
import com.pocketshell.app.sessions.WarmSshConnections
import com.pocketshell.app.sessions.WarmSshTarget
import com.pocketshell.app.sessions.remoteStartDirectoryExists
import com.pocketshell.app.sessions.resolveTmuxSessionCreation
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.protocol.ControlEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

/**
 * Holds the live `tmux -CC` control channel for a single SSH host /
 * session-name pair, and surfaces the resulting list of panes as
 * Compose-friendly state.
 *
 * Per [D5](../../../../../../../../docs/decisions.md) and
 * [D6](../../../../../../../../docs/decisions.md): we render each tmux pane
 * as its own [com.pocketshell.core.terminal.ui.TerminalSurface] with swipe
 * navigation between panes — no tiled rendering. The view model is the
 * place that wires `tmux -CC`'s per-pane `%output` flow into per-pane
 * [TerminalSurfaceState] instances; the screen
 * ([TmuxSessionScreen]) is just a renderer.
 *
 * ## Lifecycle
 *
 * 1. The screen calls [connect] with the host triple + key path + tmux
 *    session name. We open an [SshSession] via [SshConnection.connect],
 *    build a [TmuxClient] via the injected [TmuxClientFactory], subscribe
 *    to its [TmuxClient.events] *before* calling [TmuxClient.connect] so we
 *    don't miss the opening events tmux fires on session attach.
 * 2. As [ControlEvent.WindowAdd] / [ControlEvent.WindowClose] /
 *    [ControlEvent.LayoutChange] arrive, we re-enumerate panes via
 *    `list-panes -t <session>` and reconcile [_panes]. New rows get a fresh
 *    [TerminalSurfaceState] wired to the pane's filtered output flow;
 *    closed rows are dropped (the bridge tears down with the state
 *    holder).
 * 3. [onCleared] tears the client down (which cancels its internal scope)
 *    and closes the SSH session.
 *
 * ## Why we re-query rather than parse the layout string
 *
 * `%layout-change @<windowId> <layout>` carries a packed layout descriptor
 * (e.g. `bf3d,80x24,0,0,1`) that does not include pane IDs — the trailing
 * integers are pane *indexes* within the window, not the `%N` identifiers
 * tmux uses everywhere else. `%window-add` carries a window ID only. To
 * map the user-visible panes back to the wire IDs we need a fresh
 * `list-panes` round-trip per change. This is one tmux command per layout
 * notification — cheap, predictable, and matches what iTerm2's `tmux -CC`
 * integration does in the same spot.
 *
 * ## Testability
 *
 * The SSH-connection path is awkward to fake (live network, key loading);
 * for unit tests we expose [attachClientForTest] which skips
 * [SshConnection.connect] / [TmuxClientFactory.create] and binds the view
 * model to a caller-supplied [TmuxClient] directly. Production code goes
 * through [connect].
 */
@HiltViewModel
public class TmuxSessionViewModel @Inject constructor(
    private val tmuxClientFactory: TmuxClientFactory,
    private val activeTmuxClients: ActiveTmuxClients,
    private val assistantClientFactory: AssistantLlmClientFactory? = null,
    private val hostDao: HostDao? = null,
    private val folderListGateway: FolderListGateway? = null,
    private val reposRemoteSource: ReposRemoteSource? = null,
    @ApplicationContext private val applicationContext: Context? = null,
    private val projectRootDao: ProjectRootDao? = null,
    private val warmSshConnections: WarmSshConnections = WarmSshConnections(),
) : ViewModel() {

    /** SSH executor seam for assistant tools; tests substitute it. */
    private var assistantSshExecutor: AssistantSshExecutor = RealAssistantSshExecutor()

    @androidx.annotation.VisibleForTesting
    internal fun setAssistantSshExecutor(executor: AssistantSshExecutor) {
        assistantSshExecutor = executor
    }

    private val _panes: MutableStateFlow<List<TmuxPaneState>> =
        MutableStateFlow(emptyList())

    /**
     * Snapshot of the panes the screen should render — ordered by tmux
     * window, then by tmux pane index within the window so swiping
     * left/right matches the in-window order the user would see on a
     * desktop tmux client.
     *
     * The list is rebuilt on every reconcile (`WindowAdd` /
     * `LayoutChange` / `WindowClose`); pane rows are reused by [paneId]
     * so the attached [TerminalSurfaceState] survives reconciles and the
     * emulator does not lose its scrollback.
     */
    public val panes: StateFlow<List<TmuxPaneState>> = _panes.asStateFlow()

    private val _agentConversations: MutableStateFlow<Map<String, AgentConversationUiState>> =
        MutableStateFlow(emptyMap())

    public val agentConversations: StateFlow<Map<String, AgentConversationUiState>> =
        _agentConversations.asStateFlow()

    private val _connectionStatus: MutableStateFlow<ConnectionStatus> =
        MutableStateFlow(ConnectionStatus.Idle)

    /** Coarse-grained status the screen surfaces above the terminal. */
    public val connectionStatus: StateFlow<ConnectionStatus> =
        _connectionStatus.asStateFlow()

    private var attachPanesReadyTimeoutMs: Long = ATTACH_PANES_READY_TIMEOUT_MS
    private var activeAttachMilestone: AttachMilestone? = null

    /**
     * Issue #188: one-shot user-facing message for a failed `kill-window`
     * lifecycle command. Mirrors the [killError] pattern that issue #168
     * established for the sessions dashboard so the user can tell a
     * silent transport / tmux failure apart from "kill happened but the
     * WindowStrip refresh hasn't caught up yet". The screen renders this
     * via a banner slot and clears it via [clearWindowKillError] once
     * the user dismisses it. Stays null while killing a window succeeds.
     */
    private val _windowKillError: MutableStateFlow<String?> = MutableStateFlow(null)
    public val windowKillError: StateFlow<String?> = _windowKillError.asStateFlow()

    // Issue #266: in-app action assistant — replaces the deleted
    // CommandPlanner voice path (D22 hard cut). Command-mode dictation lands
    // in [dictateToAssistant]; the controller owns the confirm-or-correct
    // loop. `run_command` targets the currently focused pane.
    private var assistantFocusedPaneId: String? = null

    private val _assistantNavRequests: MutableSharedFlow<AppDestination> =
        MutableSharedFlow(extraBufferCapacity = 4)

    internal val assistantNavRequests: SharedFlow<AppDestination> =
        _assistantNavRequests.asSharedFlow()

    private val assistant: SessionAssistantController =
        SessionAssistantController(scope = viewModelScope, sessionFactory = ::buildAssistantDeps)

    internal val assistantState: StateFlow<AssistantUiState> = assistant.state

    // Project roots for the connected host, mirrored from
    // [ProjectRootDao.getByHostId] so the voice planner request can carry
    // the same `projectRoots` the raw-SSH SessionViewModel sends. The flow
    // is rebound on every [connect] / [replaceClientForTest]; tearing the
    // connection down clears the list so a stale host's roots never bleed
    // into a fresh session.
    private val _projectRoots: MutableStateFlow<List<ProjectRootEntity>> =
        MutableStateFlow(emptyList())
    private var projectRootsJob: Job? = null

    private var sessionRef: SshSession? = null
    private var clientRef: TmuxClient? = null
    private var registeredHostId: Long? = null
    // Issue #235: hostId under which we registered application-scoped
    // lifecycle hooks in [activeTmuxClients]. This is tracked
    // SEPARATELY from [registeredHostId] because the client-entry
    // registration is recreated on every attach/detach cycle (the
    // lifecycle-driven auto-detach unregisters the client), but the
    // hook must survive across detach cycles so the `ON_START` fanout
    // can find a callback that calls back into THIS VM. Cleared
    // exclusively from [onCleared], which is the only place where the
    // VM truly goes away and the hook must be removed too.
    private var lifecycleHookHostId: Long? = null
    private var activeTarget: ConnectionTarget? = null
    private var connectingTarget: ConnectionTarget? = null
    private var connectJob: Job? = null
    private var eventsJob: Job? = null

    // Issue #145: whether [reconnect] would result in a new connect
    // attempt. The screen surfaces a Reconnect button only when this is
    // true; without a known target (e.g. the ViewModel was never opened)
    // the button is hidden so the user doesn't get a silent no-op tap.
    // The flow is updated whenever [activeTarget] / [connectingTarget]
    // changes via [refreshReconnectAvailability].
    private val _canReconnect: MutableStateFlow<Boolean> = MutableStateFlow(false)
    public val canReconnect: StateFlow<Boolean> = _canReconnect.asStateFlow()

    // Last on-screen terminal grid reported by Compose. It can arrive
    // before or after the tmux control client attaches; once both are
    // known, the VM reports this size through `refresh-client -C`.
    private var remoteColumns: Int = 0
    private var remoteRows: Int = 0
    private var appliedControlClientColumns: Int = 0
    private var appliedControlClientRows: Int = 0
    private var controlClientSizeGeneration: Long = 0L
    private var windowSizePolicyAppliedForAttach: Boolean = false
    private val agentRepository: AgentConversationRepository = AgentConversationRepository()
    private val paneAgentJobs: MutableMap<String, Job> = ConcurrentHashMap()
    // Issue #186: dedup key is (cwd, foreground-command, tty). Adding
    // tty here means a tmux re-attach that rotates a pane onto a new
    // `/dev/pts/N` is a fresh detection trigger — the previous shape
    // (cwd, command) would treat that as already-seen and skip the
    // round-trip, leaving the cached "no agent" verdict in place.
    private val paneAgentInputs: MutableMap<String, Triple<String, String, String>> = ConcurrentHashMap()
    private val paneInputQueues: MutableMap<String, TmuxPaneInputQueue> = ConcurrentHashMap()
    private val paneInputJobs: MutableMap<String, Job> = ConcurrentHashMap()

    // Bridge scope: a child of viewModelScope (parented via the
    // viewModelScope's Job) but with its own SupervisorJob so that a
    // producer-cancellation on one pane's TerminalSurfaceState (e.g. the
    // SharedFlow's collector failing) does not cascade into sibling panes.
    // Each TerminalSurfaceState.attachExternalProducer returns a Job
    // rooted in this scope; cancelling viewModelScope (via onCleared)
    // also cancels this scope's SupervisorJob through the parent link.
    private val bridgeScope = CoroutineScope(
        viewModelScope.coroutineContext +
            SupervisorJob(viewModelScope.coroutineContext[Job]),
    )

    // Reuse pane rows across reconciles so the attached TerminalSurfaceState
    // (and its emulator scrollback) survives layout-change events. Keyed by
    // pane ID; entries are removed when tmux drops the pane.
    private val paneRows: MutableMap<String, TmuxPaneState> = ConcurrentHashMap()

    // Track per-pane producer jobs so we cancel them when the pane goes
    // away. The jobs are children of bridgeScope; cancelling the parent
    // would also stop them, but we want to release the bridge cleanly
    // mid-lifecycle when a single pane closes.
    private val paneProducerJobs: MutableMap<String, Job> = ConcurrentHashMap()

    /**
     * Open the SSH transport, spawn `tmux -CC` against [sessionName], and
     * begin maintaining [panes].
     *
     * Idempotent for the same destination. If the hand-rolled navigator
     * reuses this ViewModel for a different host/session tuple, we tear
     * down the old control channel before opening the new one so a
     * dashboard row tap actually attaches to the requested tmux session.
     * [keyPath] is the resolved absolute path of the user's private key
     * on disk, the same way [com.pocketshell.app.session.SessionViewModel]
     * consumes it from the host picker.
     */
    public fun connect(
        hostId: Long,
        hostName: String,
        host: String,
        port: Int,
        user: String,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        startDirectory: String? = null,
        trigger: TmuxConnectTrigger = TmuxConnectTrigger.UserTap,
    ) {
        val target = ConnectionTarget(
            hostId = hostId,
            hostName = hostName,
            host = host,
            port = port,
            user = user,
            keyPath = keyPath,
            passphrase = passphrase,
            sessionName = sessionName,
            startDirectory = startDirectory,
        )
        if (connectJob?.isActive == true && connectingTarget == target) return
        if (_connectionStatus.value is ConnectionStatus.Connected && activeTarget == target) return

        val previousActiveTarget = activeTarget
        val previousSession = sessionRef
        val willFastSwitch = previousActiveTarget != null &&
            previousSession != null &&
            previousSession.isConnected &&
            isSameHost(previousActiveTarget, target) &&
            previousActiveTarget.sessionName != target.sessionName
        val effectiveTrigger = if (willFastSwitch) TmuxConnectTrigger.FastSwitch else trigger
        val generation = nextConnectGeneration()
        latestConnectIntent = ConnectIntent(
            target = target,
            trigger = effectiveTrigger,
            generation = generation,
        )

        // Issue #145: deterministic reconnect-loop signal. Every accepted
        // connect attempt increments a process-wide counter and emits a
        // log line that the connected disconnect+reconnect test greps for
        // from logcat. The two early-return guards above intentionally do
        // NOT emit — they are no-ops, not reconnect attempts.
        val attempt = TMUX_CONNECT_ATTEMPTS.incrementAndGet()
        StartupTiming.mark(
            "tmux-connect-attempt",
            "attempt" to attempt,
            "hostId" to hostId,
            "host" to host,
            "port" to port,
            "session" to target.sessionName,
            "trigger" to effectiveTrigger.logValue,
            "requestedTrigger" to trigger.logValue,
            "generation" to generation,
        )
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-connect-attempt count=$attempt trigger=${effectiveTrigger.logValue} " +
                "requestedTrigger=${trigger.logValue} generation=$generation " +
                targetLogFields(target),
        )

        // Issue #151: a tap on "Attach" for a different tmux session
        // re-enters `connect()` while the previous connection's event-loop
        // coroutine is still mid-processing a `ControlEvent`. The old
        // implementation fired `connectJob?.cancel()` and immediately ran
        // `closeCurrentConnection()` synchronously — the cancel signal had
        // not yet propagated, so `sessionRef?.close()` raced the still-live
        // event-loop coroutine and `SSHClient.disconnect()` threw
        // `TransportException [BY_APPLICATION] Disconnected` because the
        // transport was already half-down from the cancellation. That
        // crash was reproduced on the v0.2.7 maintainer device. The new shape
        // cancels-and-joins the prior connect job AND the event-loop job
        // inside a launched coroutine, so by the time we touch the SSH
        // transport the only thread interacting with it is us.
        val previousConnectJob = connectJob
        // Issue #178: snapshot the current active connection before we
        // flip [connectingTarget] / [_connectionStatus] so the in-flight
        // connect() coroutine can decide whether to do a full reconnect
        // (cross-host or no live SSH session) or a fast tmux-client swap
        // that reuses the existing SSH transport (same host, different
        // tmux session). The decision MUST be made off the previous
        // [activeTarget] — connectingTarget is the new target by the time
        // the coroutine runs.
        connectingTarget = target
        refreshReconnectAvailability()
        _connectionStatus.value = ConnectionStatus.Connecting(host, port, user)
        // Issue #257 (scope F): drop the previous session's panes from the
        // rendered list SYNCHRONOUSLY, before the teardown coroutine even
        // gets a turn. The actual per-pane producer/scrollback teardown
        // still happens inside [closeCurrentClientKeepSession] /
        // [closeCurrentConnectionAndJoin] under the #151 cancel-and-join
        // ordering (so the SSH transport is never touched while the event
        // loop is mid-write). But the screen collects [panes] directly, so
        // emptying it here means the user never sees a stale frame of the
        // session they are leaving: the moment they pick a different
        // session the viewport flips to the loading/empty state and then
        // to the new session, never lingering on the old content.
        //
        // We do NOT clear [paneRows] here — that map is the teardown
        // coroutine's responsibility (it has to detach each producer first)
        // and clearing it off-thread would race the event loop. Emptying
        // only the rendered StateFlow is race-free: it is the same flow the
        // teardown sets to empty a moment later.
        _panes.value = emptyList()
        connectJob = viewModelScope.launch {
            // First: drain any in-flight connect() coroutine. `cancelAndJoin`
            // sends cancel and suspends until the prior job actually exits.
            // We do this in `NonCancellable` so a fresh cancel on the new
            // job (e.g. another rapid connect()) does not interrupt the
            // join itself — that would leave us in the original race window.
            withContext(NonCancellable) {
                previousConnectJob?.cancelAndJoin()
                // Issue #178: when the new target is on the SAME host as
                // the previously-connected one and we still have a live
                // SSH session, take the fast-switch path: tear down the
                // tmux client only (closes the tmux -CC shell channel)
                // and reuse the existing [SshSession] for the new
                // [TmuxClient]. Skips the 2-5s SSH handshake the full
                // teardown path would otherwise re-do.
                //
                // Eligibility: same host triple (host, port, user, key
                // path) AND a live SSH session AND a different tmux
                // session name. We require a different session name so
                // we never burn a fast-switch on the no-op re-connect to
                // the same session — the [activeTarget == target]
                // early-return above already covers that case, but
                // keeping the check explicit here documents the
                // contract.
                if (willFastSwitch) {
                    // Fast path: keep the SSH session, swap the tmux
                    // client. No new handshake fires.
                    closeCurrentClientKeepSession()
                    runFastSessionSwitch(target, previousSession, attempt, effectiveTrigger)
                } else {
                    // Slow path: full teardown of both the tmux client
                    // and the SSH session before the fresh
                    // [SshConnection.connect] handshake.
                    closeCurrentConnectionAndJoin(preserveConnectingTarget = target)
                    runConnect(target, attempt, effectiveTrigger)
                }
            }
        }
    }

    /**
     * Issue #178: true when [previous] and [target] address the same SSH
     * endpoint (same host, port, user, key path). Same-host means the
     * underlying [SshSession] is reusable for an in-channel tmux client
     * swap. We deliberately exclude `passphrase` and `sessionName` —
     * passphrase is only used for the initial key load (the live session
     * is already authenticated), and the session name is the THING we're
     * switching.
     */
    private fun isSameHost(previous: ConnectionTarget, target: ConnectionTarget): Boolean =
        previous.host == target.host &&
            previous.port == target.port &&
            previous.user == target.user &&
            previous.keyPath == target.keyPath

    private fun ConnectionTarget.toWarmSshTarget(): WarmSshTarget =
        WarmSshTarget(
            hostId = hostId,
            hostname = host,
            port = port,
            username = user,
            keyPath = keyPath,
        )

    private fun sameSessionIdentity(left: ConnectionTarget, right: ConnectionTarget): Boolean =
        left.hostId == right.hostId &&
            left.host == right.host &&
            left.port == right.port &&
            left.user == right.user &&
            left.keyPath == right.keyPath &&
            left.sessionName == right.sessionName &&
            left.startDirectory == right.startDirectory

    private fun nextConnectGeneration(): Long {
        connectGeneration += 1L
        return connectGeneration
    }

    private fun targetLogFields(target: ConnectionTarget): String = buildString {
        append("hostId=")
        append(target.hostId)
        append(" host=")
        append(target.host)
        append(" port=")
        append(target.port)
        append(" user=")
        append(target.user)
        append(" session=")
        append(target.sessionName)
        target.startDirectory?.let {
            append(" startDirectory=")
            append(it)
        }
    }

    /**
     * Issue #145: explicit reconnect entry point used by the Compose
     * "Reconnect" affordance the screen renders when [connectionStatus]
     * is Failed. Delegates to [connect] with the last known
     * [activeTarget] (or [connectingTarget] if the failure happened
     * during the initial connect race). Marked public so tests can
     * drive the reconnect without going through the screen, and so the
     * screen's reconnect button has a single named seam to call.
     *
     * Returns `false` when there is no known target to reconnect to
     * (e.g. the ViewModel was never opened). The screen gates the
     * Reconnect button on [canReconnect] so the user never sees a tap
     * that silently no-ops; this return value is the secondary defence
     * for direct programmatic callers.
     */
    public fun reconnect(): Boolean {
        val target = activeTarget ?: connectingTarget ?: return false
        connect(
            hostId = target.hostId,
            hostName = target.hostName,
            host = target.host,
            port = target.port,
            user = target.user,
            keyPath = target.keyPath,
            passphrase = target.passphrase,
            sessionName = target.sessionName,
            startDirectory = target.startDirectory,
            trigger = TmuxConnectTrigger.Reconnect,
        )
        return true
    }

    private fun refreshReconnectAvailability() {
        _canReconnect.value = activeTarget != null || connectingTarget != null
    }

    /**
     * Issue #165: cancel an in-flight SSH/tmux connect attempt. Paired
     * with the 15s "Cancel" affordance the progress overlay on
     * [TmuxSessionScreen] surfaces once the handshake has been visibly
     * stalled. Cancels the [connectJob] coroutine (the in-flight SSH
     * handshake / tmux client setup throws [CancellationException] and
     * unwinds — the existing #151 join-on-cancel machinery already
     * guarantees the transport tear-down does not race the still-live
     * event loop). Flips [_connectionStatus] to
     * [ConnectionStatus.Failed] with a "Connect cancelled" message so
     * the screen renders a deterministic post-cancel state rather than
     * staying stuck on Connecting.
     *
     * Clears [connectingTarget] and refreshes [canReconnect] so the
     * screen's downstream affordances (Reconnect button, in-flight
     * lock) read off a consistent snapshot once cancel returns. The
     * [activeTarget] is left alone — if the user had a working session
     * before this connect attempt (e.g. a same-host session switch),
     * reconnect() can still fall back to it.
     *
     * No-op when the screen is not currently Connecting. Returns
     * `true` when a cancel actually fired so callers (the screen and
     * unit tests) can chain post-cancel behaviour without polling the
     * state flow.
     */
    public fun cancelConnect(): Boolean {
        val current = _connectionStatus.value
        if (current !is ConnectionStatus.Connecting) return false
        connectJob?.cancel()
        connectJob = null
        connectingTarget = null
        refreshReconnectAvailability()
        _connectionStatus.value = ConnectionStatus.Failed(
            "Connect cancelled by user.",
        )
        return true
    }

    // Issue #235: when [onAppBackgrounded] tears the `-CC` client down,
    // we stash the [activeTarget] so [onAppForegrounded] knows what to
    // re-attach to. Going through [activeTarget] for the reconnect path
    // would not work because [closeCurrentConnectionAndJoin] clears it.
    private var pendingReattach: PendingReattach? = null
    private var backgroundDetachJob: Job? = null
    private var foregroundReattachForTest: (() -> Unit)? = null
    private var latestConnectIntent: ConnectIntent? = null
    private var connectGeneration: Long = 0L

    // Issue #257: the in-flight fire-and-forget `detach-client` of the
    // PREVIOUS tmux client during a same-host session switch. The clean
    // detach (#215 contract) still happens, but it runs off the switch's
    // critical path so the new tmux session can attach on the shared SSH
    // transport without waiting up to [TmuxClient.detachCleanly]'s 1s
    // budget. Tracked so [onCleared] can let the framework cancel it with
    // the rest of [viewModelScope], and so unit tests can assert the
    // switch did not block on it.
    private var orphanDetachJob: Job? = null

    /**
     * Issue #235: drop the tmux `-CC` control client cleanly while the
     * app is in the background.
     *
     * Triggered by the application-scoped
     * [androidx.lifecycle.ProcessLifecycleOwner] `ON_STOP` event the
     * [com.pocketshell.app.App] installs. The tmux server-side session
     * stays alive — only this control client disconnects — so a
     * desktop client attaching to the same session while PocketShell is
     * in the background sees the window at its own viewport dimensions
     * rather than `min(phone, desktop)` (the maintainer-reported
     * symptom in the v0.2.8 dogfood pass).
     *
     * Idempotent: if there is no live client, this is a no-op. Bounded
     * by [TmuxClient.detachCleanly]'s default 1s timeout so a wedged
     * transport cannot stall the lifecycle transition.
     *
     * The [activeTarget] is stashed into [pendingReattachTarget] so
     * [onAppForegrounded] knows where to reconnect. The full teardown
     * runs via [closeCurrentConnectionAndJoin], which clears
     * [activeTarget] as part of releasing the SSH session; without the
     * stash the foreground hook would have no idea what to reattach
     * to.
     */
    public fun onAppBackgrounded() {
        val target = activeTarget ?: connectingTarget
        if (target == null) return
        if (clientRef == null && sessionRef == null) return
        val intent = latestConnectIntent
        pendingReattach = PendingReattach(
            target = target,
            generation = intent?.generation ?: connectGeneration,
            intendedTarget = intent?.target,
            intendedTrigger = intent?.trigger,
        )
        Log.i(
            ISSUE_235_LIFECYCLE_TAG,
            "tmux-detach-on-background generation=${intent?.generation ?: connectGeneration} " +
                targetLogFields(target) +
                intent?.target?.let { " intendedSession=${it.sessionName}" }.orEmpty() +
                intent?.trigger?.let { " intendedTrigger=${it.logValue}" }.orEmpty(),
        )
        if (backgroundDetachJob?.isActive == true) return
        val preserveConnectingTarget = connectingTarget?.takeIf { connecting ->
            !sameSessionIdentity(connecting, target) &&
                intent?.target?.let { sameSessionIdentity(it, connecting) } == true
        }
        val detachJob = viewModelScope.launch {
            // NonCancellable so a fresh lifecycle transition (e.g.
            // rapid foreground/background) cannot interrupt the detach
            // mid-write — the screen still needs the clean teardown to
            // happen even if the foreground hook is already firing
            // the reattach.
            withContext(NonCancellable) {
                closeCurrentConnectionAndJoin(
                    preserveConnectingTarget = preserveConnectingTarget,
                )
            }
        }
        backgroundDetachJob = detachJob
        detachJob.invokeOnCompletion {
            if (backgroundDetachJob == detachJob) {
                backgroundDetachJob = null
            }
        }
    }

    /**
     * Issue #235: re-attach to the tmux session we [detached][onAppBackgrounded]
     * when the app went to background.
     *
     * Triggered by the application-scoped
     * [androidx.lifecycle.ProcessLifecycleOwner] `ON_START` event. The
     * tmux session has stayed alive on the remote (we only tore the
     * `-CC` control client down), so attaching is a fresh
     * [SshConnection.connect][com.pocketshell.core.ssh.SshConnection.connect]
     * + `tmux -CC attach-session -t <name>` round-trip — exactly what
     * the user's first attach did.
     *
     * No-op when there is no [pendingReattachTarget] (e.g. the user
     * left the tmux screen via the back stack, or the manual Detach
     * button cleared it). Goes through the standard [connect] path so
     * the existing handshake instrumentation, project-roots binding,
     * and pane reconcile all fire identically to a cold attach.
     */
    public fun onAppForegrounded() {
        if (pendingReattach == null) {
            latestConnectIntent?.let { intent ->
                Log.i(
                    ISSUE_235_LIFECYCLE_TAG,
                    "tmux-reattach-on-foreground-skip reason=no-pending " +
                        "generation=${intent.generation} trigger=${intent.trigger.logValue} " +
                        targetLogFields(intent.target),
                )
            }
            return
        }
        val detachJob = backgroundDetachJob
        viewModelScope.launch {
            // If the user backgrounds and immediately foregrounds the
            // app, the lifecycle detach may still be inside
            // closeCurrentConnectionAndJoin(). Waiting here prevents a
            // reattach from being consumed by connect()'s still-connected
            // early return before teardown clears activeTarget/clientRef.
            detachJob?.join()
            val pending = pendingReattach ?: return@launch
            val target = pending.target
            val currentIntent = latestConnectIntent
            if (
                currentIntent != null &&
                currentIntent.generation >= pending.generation &&
                !sameSessionIdentity(currentIntent.target, target)
            ) {
                pendingReattach = null
                Log.i(
                    ISSUE_235_LIFECYCLE_TAG,
                    "tmux-reattach-on-foreground-skip reason=newer-intent " +
                        "detachedSession=${target.sessionName} " +
                        "intendedSession=${currentIntent.target.sessionName} " +
                        "detachedGeneration=${pending.generation} " +
                        "intendedGeneration=${currentIntent.generation} " +
                        "intendedTrigger=${currentIntent.trigger.logValue} " +
                        "detachedHostId=${target.hostId} intendedHostId=${currentIntent.target.hostId}",
                )
                return@launch
            }
            pendingReattach = null
            Log.i(
                ISSUE_235_LIFECYCLE_TAG,
                "tmux-reattach-on-foreground trigger=${TmuxConnectTrigger.LifecycleReattach.logValue} " +
                    "generation=${pending.generation} " +
                    targetLogFields(target),
            )
            foregroundReattachForTest?.invoke() ?: connect(
                hostId = target.hostId,
                hostName = target.hostName,
                host = target.host,
                port = target.port,
                user = target.user,
                keyPath = target.keyPath,
                passphrase = target.passphrase,
                sessionName = target.sessionName,
                startDirectory = target.startDirectory,
                trigger = TmuxConnectTrigger.LifecycleReattach,
            )
        }
    }

    /**
     * Issue #235: user-driven detach.
     *
     * Wired to the kebab menu's "Detach" item in [TmuxSessionScreen].
     * Tears the tmux `-CC` control client down (server-clean — uses
     * [closeCurrentConnectionAndJoin]'s `detach-client` round-trip)
     * and clears [pendingReattachTarget] so a subsequent app
     * background/foreground cycle does NOT reattach to the session
     * the user just explicitly walked away from. The screen pairs
     * this call with a navigate-back to the sessions dashboard.
     *
     * Sibling of [onAppBackgrounded] structurally — same teardown,
     * different reattach semantics. The split exists so the lifecycle
     * observer ("background, then come back") and the user-driven
     * "I'm done here for now" intent stay legibly distinct.
     */
    public fun detachAndExit() {
        // Clear pending reattach BEFORE the teardown so a racing
        // background event in the same instant (rare but observed in
        // QA when the user backgrounds the app during the detach
        // animation) does not re-seed the reattach.
        pendingReattach = null
        Log.i(
            ISSUE_235_LIFECYCLE_TAG,
            "tmux-detach-manual host=${activeTarget?.host} session=${activeTarget?.sessionName}",
        )
        viewModelScope.launch {
            withContext(NonCancellable) {
                closeCurrentConnectionAndJoin()
            }
        }
    }

    /**
     * Issue #235 test seam: drive the protected [onCleared]
     * lifecycle event without booting an Android lifecycle owner.
     * Mirrors the seam other ViewModel tests use for the same
     * purpose. Production callers do not touch this — the framework
     * invokes [onCleared] when the VM's owner is destroyed.
     */
    internal fun clearForTest() {
        onCleared()
    }

    internal fun hasPendingReattachForTest(): Boolean = pendingReattach != null

    internal fun connectingSessionNameForTest(): String? = connectingTarget?.sessionName

    public fun latestRestoreIntentSnapshot(): TmuxRestoreIntentSnapshot? =
        latestConnectIntent?.let { intent ->
            TmuxRestoreIntentSnapshot(
                hostId = intent.target.hostId,
                hostName = intent.target.hostName,
                hostname = intent.target.host,
                port = intent.target.port,
                username = intent.target.user,
                keyPath = intent.target.keyPath,
                sessionName = intent.target.sessionName,
                startDirectory = intent.target.startDirectory,
                trigger = intent.trigger,
                generation = intent.generation,
            )
        }

    internal fun setForegroundReattachForTest(handler: (() -> Unit)?) {
        foregroundReattachForTest = handler
    }

    /**
     * Issue #257 test seam: true while the previous tmux client's clean
     * `detach-client` from a same-host fast-switch is still draining in
     * the background (i.e. it was launched fire-and-forget rather than
     * awaited on the switch's critical path). Lets a unit test assert the
     * switch did not block on the old client's detach.
     */
    internal fun hasInFlightOrphanDetachForTest(): Boolean =
        orphanDetachJob?.isActive == true

    /**
     * Issue #235 test seam: drive [onAppBackgrounded] and wait for the
     * detach to fully complete. Production uses the fire-and-forget
     * variant so the lifecycle observer never blocks the main thread;
     * tests need to assert against post-detach state so they need a
     * deterministic join.
     */
    internal suspend fun onAppBackgroundedAndAwait() {
        onAppBackgrounded()
        backgroundDetachJob?.join()
    }

    /**
     * Issue #235: install the application-lifecycle hooks for [hostId]
     * once the view model has successfully attached a tmux client.
     *
     * Called from every [TmuxSessionViewModel.runConnect] /
     * [runFastSessionSwitch] / test seam right after
     * [activeTmuxClients.register], so each successful attach plugs
     * the same view model into the singleton process-lifecycle
     * observer wired in [com.pocketshell.app.App.onCreate].
     */
    private fun installLifecycleHooks(hostId: Long) {
        activeTmuxClients.registerLifecycleHooks(
            hostId = hostId,
            hooks = ActiveTmuxClients.LifecycleHooks(
                onBackground = { onAppBackgrounded() },
                onForeground = { onAppForegrounded() },
            ),
        )
        lifecycleHookHostId = hostId
    }

    private suspend fun runConnect(
        target: ConnectionTarget,
        attempt: Int,
        trigger: TmuxConnectTrigger,
        allowWarmSshReuse: Boolean = true,
    ) {
        val startedAtMs = SystemClock.elapsedRealtime()
        var reusedWarmSsh = false
        try {
            val warmSession = if (allowWarmSshReuse) {
                warmSshConnections.take(target.toWarmSshTarget())
            } else {
                null
            }
            val session = if (warmSession != null && warmSession.isConnected) {
                reusedWarmSsh = true
                StartupTiming.mark(
                    "tmux-warm-ssh-reused",
                    "attempt" to attempt,
                    "hostId" to target.hostId,
                    "host" to target.host,
                    "port" to target.port,
                    "session" to target.sessionName,
                    "trigger" to trigger.logValue,
                )
                Log.i(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-warm-ssh-reused trigger=${trigger.logValue} " +
                        "${targetLogFields(target)} attempt=$attempt",
                )
                warmSession
            } else {
                warmSession?.let { runCatching { it.close() } }
                // Issue #178: instrument the actual SSH handshake call so a
                // connected test (and humans reading logcat) can see whether
                // the slow path or the fast tmux-only swap path was taken.
                // The counter is process-wide so tests can snapshot it
                // before/after a session-switch and assert "no new
                // handshake" without grepping logcat. We log under the same
                // tag the slow-path test already greps for, with a distinct
                // event prefix so a `grep` matches either.
                val handshakeNumber = SSH_HANDSHAKE_ATTEMPTS.incrementAndGet()
                StartupTiming.mark(
                    "tmux-ssh-handshake",
                    "attempt" to attempt,
                    "handshake" to handshakeNumber,
                    "hostId" to target.hostId,
                    "host" to target.host,
                    "port" to target.port,
                    "session" to target.sessionName,
                    "trigger" to trigger.logValue,
                )
                Log.i(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-ssh-handshake count=$handshakeNumber trigger=${trigger.logValue} " +
                        "${targetLogFields(target)} " +
                        "attempt=$attempt",
                )
                SshOpenTelemetry.record(
                    source = SSH_SOURCE_TMUX_CONNECT,
                    host = target.host,
                    port = target.port,
                    user = target.user,
                )
                val key: SshKey = SshKey.Path(File(target.keyPath))
                val sessionResult = SshConnection.connect(
                    host = target.host,
                    port = target.port,
                    user = target.user,
                    key = key,
                    passphrase = target.passphrase?.copyOf(),
                    knownHosts = KnownHostsPolicy.AcceptAll,
                )
                sessionResult.getOrElse { e ->
                    failConnectAttempt(
                        target = target,
                        attempt = attempt,
                        startedAtMs = startedAtMs,
                        message = "connect failed: ${e.message}",
                        cause = e,
                        preserveReconnectTarget = false,
                    )
                    return
                }
            }
            StartupTiming.mark(
                "ssh-connected",
                "attempt" to attempt,
                "hostId" to target.hostId,
                "host" to target.host,
                "port" to target.port,
                "session" to target.sessionName,
            )
            logAttachMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                event = "ssh-connected",
                trigger = trigger,
            )
            sessionRef = session

            val client = tmuxClientFactory.create(
                session,
                sessionName = target.sessionName,
                startDirectory = target.startDirectory,
            )
            activeTarget = target
            refreshReconnectAvailability()
            attachClient(client)
            TmuxSessionLatencyTelemetry.record(
                name = "tmux_control_attach_count",
                durationMs = 1L,
                sessionName = target.sessionName,
                trigger = trigger,
            )
            client.connect()
            activeAttachMilestone = AttachMilestone(
                attempt = attempt,
                sessionName = target.sessionName,
                startedAtMs = startedAtMs,
                trigger = trigger,
            )
            StartupTiming.mark(
                "tmux-control-command-started",
                "attempt" to attempt,
                "hostId" to target.hostId,
                "host" to target.host,
                "port" to target.port,
                "session" to target.sessionName,
            )
            logAttachMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                event = "tmux-control-command-started",
                trigger = trigger,
            )
            activeTmuxClients.register(
                hostId = target.hostId,
                hostName = target.hostName,
                hostname = target.host,
                port = target.port,
                username = target.user,
                keyPath = target.keyPath,
                client = client,
                startDirectoryExists = { directory ->
                    remoteStartDirectoryExists(session, directory)
                },
            )
            registeredHostId = target.hostId
            installLifecycleHooks(target.hostId)
            bindProjectRootsForHost(target.hostId)

            awaitPanesReadyForAttach(
                target = target,
                attempt = attempt,
                startedAtMs = startedAtMs,
                trigger = trigger,
            )

            connectingTarget = null
            refreshReconnectAvailability()
            _connectionStatus.value = ConnectionStatus.Connected(
                target.host,
                target.port,
                target.user,
            )
            logAttachMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                event = "tmux-connect-ready",
                trigger = trigger,
            )
            maybeRefreshControlClientSize()
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            if (reusedWarmSsh && t is TmuxAttachPanesReadyException) {
                StartupTiming.mark(
                    "tmux-warm-ssh-retry-cold",
                    "attempt" to attempt,
                    "hostId" to target.hostId,
                    "host" to target.host,
                    "port" to target.port,
                    "session" to target.sessionName,
                    "trigger" to trigger.logValue,
                )
                Log.w(
                    ISSUE_145_RECONNECT_TAG,
                    attachMilestoneMessage(
                        attempt = attempt,
                        target = target,
                        startedAtMs = startedAtMs,
                        event = "tmux-warm-ssh-retry-cold",
                        trigger = trigger,
                        detail = "cause=${t.javaClass.simpleName}: ${t.message}",
                    ),
                    t,
                )
                withContext(NonCancellable) {
                    closeCurrentConnectionAndJoin(preserveConnectingTarget = target)
                }
                runConnect(
                    target = target,
                    attempt = attempt,
                    trigger = trigger,
                    allowWarmSshReuse = false,
                )
                return
            }
            failConnectAttempt(
                target = target,
                attempt = attempt,
                startedAtMs = startedAtMs,
                message = connectFailureMessage(t, target),
                cause = t,
                preserveReconnectTarget = t is TmuxAttachPanesReadyException,
            )
        }
    }

    /**
     * Issue #178: same-host fast-switch path. Builds a fresh [TmuxClient]
     * against the already-connected [session] (no new SSH handshake),
     * attaches it, and updates the registry / target state. Mirrors the
     * tmux-side wiring inside [runConnect] but skips the entire
     * [SshConnection.connect] round-trip and reuses [sessionRef].
     *
     * Pre-conditions: caller already ran
     * [closeCurrentClientKeepSession] so the previous tmux client and its
     * per-pane state are torn down and [clientRef] is null. The caller
     * also verified [session.isConnected]; we assert it here too because
     * the SSH socket may have died asynchronously between the eligibility
     * check and this call (rare but possible — the
     * [TmuxClient.disconnected] observer in [attachClient] catches the
     * common case, but a race window of a few ms is unavoidable).
     */
    private suspend fun runFastSessionSwitch(
        target: ConnectionTarget,
        session: SshSession,
        attempt: Int,
        trigger: TmuxConnectTrigger,
    ) {
        val startedAtMs = SystemClock.elapsedRealtime()
        try {
            if (!session.isConnected) {
                // The previously-live session died between the
                // eligibility check and now. Fall back to the full
                // teardown + reconnect path so the user still ends up on
                // the requested session instead of a Failed state.
                sessionRef = null
                runConnect(target, attempt, trigger)
                return
            }
            sessionRef = session
            val client = tmuxClientFactory.create(
                session,
                sessionName = target.sessionName,
                startDirectory = target.startDirectory,
            )
            activeTarget = target
            refreshReconnectAvailability()
            attachClient(client)
            TmuxSessionLatencyTelemetry.record(
                name = "tmux_control_attach_count",
                durationMs = 1L,
                sessionName = target.sessionName,
                trigger = trigger,
            )
            client.connect()
            activeAttachMilestone = AttachMilestone(
                attempt = attempt,
                sessionName = target.sessionName,
                startedAtMs = startedAtMs,
                trigger = trigger,
            )
            logAttachMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                event = "tmux-control-command-started",
                trigger = trigger,
            )
            activeTmuxClients.register(
                hostId = target.hostId,
                hostName = target.hostName,
                hostname = target.host,
                port = target.port,
                username = target.user,
                keyPath = target.keyPath,
                client = client,
                startDirectoryExists = { directory ->
                    remoteStartDirectoryExists(session, directory)
                },
            )
            registeredHostId = target.hostId
            installLifecycleHooks(target.hostId)
            bindProjectRootsForHost(target.hostId)

            awaitPanesReadyForAttach(
                target = target,
                attempt = attempt,
                startedAtMs = startedAtMs,
                trigger = trigger,
            )

            connectingTarget = null
            refreshReconnectAvailability()
            _connectionStatus.value = ConnectionStatus.Connected(
                target.host,
                target.port,
                target.user,
            )
            logAttachMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                event = "tmux-connect-ready",
                trigger = trigger,
            )
            maybeRefreshControlClientSize()
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-fast-switch trigger=${trigger.logValue} " + targetLogFields(target),
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            failConnectAttempt(
                target = target,
                attempt = attempt,
                startedAtMs = startedAtMs,
                message = connectFailureMessage(t, target),
                cause = t,
                preserveReconnectTarget = t is TmuxAttachPanesReadyException,
            )
        }
    }

    private suspend fun awaitPanesReadyForAttach(
        target: ConnectionTarget,
        attempt: Int,
        startedAtMs: Long,
        trigger: TmuxConnectTrigger,
    ) {
        logAttachMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = startedAtMs,
            event = "tmux-list-panes-start",
            trigger = trigger,
        )
        val result = withTimeoutOrNull<PaneReconcileResult.Ready>(attachPanesReadyTimeoutMs) {
            var ready: PaneReconcileResult.Ready? = null
            while (ready == null) {
                when (val reconcile = reconcilePanes()) {
                    is PaneReconcileResult.Ready -> {
                        if (reconcile.paneCount > 0) {
                            ready = reconcile
                        }
                    }
                    is PaneReconcileResult.Failed -> throw TmuxAttachPanesReadyException(
                        "Failed waiting for tmux panes from ${target.sessionName}: " +
                            (reconcile.cause.message ?: reconcile.cause.javaClass.simpleName) +
                            ". Tap Reconnect to retry.",
                        reconcile.cause,
                    )
                    PaneReconcileResult.NoClient -> throw TmuxAttachPanesReadyException(
                        "tmux client closed before panes were ready",
                    )
                }
                if (ready == null) {
                    delay(ATTACH_PANES_READY_RETRY_MS)
                }
            }
            ready
        } ?: throw TmuxAttachPanesReadyException(
            "Timed out waiting for tmux panes from ${target.sessionName}. " +
                "Tap Reconnect to retry.",
        )
        logAttachMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = startedAtMs,
            event = "tmux-control-mode-ready",
            trigger = trigger,
            detail = "paneCount=${result.paneCount}",
        )
        logAttachMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = startedAtMs,
            event = "tmux-panes-ready",
            trigger = trigger,
            detail = "paneCount=${result.paneCount}",
        )
    }

    private suspend fun failConnectAttempt(
        target: ConnectionTarget,
        attempt: Int,
        startedAtMs: Long,
        message: String,
        cause: Throwable,
        preserveReconnectTarget: Boolean,
    ) {
        Log.w(
            ISSUE_145_RECONNECT_TAG,
            attachMilestoneMessage(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                event = "tmux-connect-failed",
                trigger = latestConnectIntent?.takeIf {
                    sameSessionIdentity(it.target, target)
                }?.trigger ?: TmuxConnectTrigger.UserTap,
                detail = "cause=${cause.javaClass.simpleName}: ${cause.message}",
            ),
            cause,
        )
        withContext(NonCancellable) {
            closeCurrentConnectionAndJoin()
        }
        activeAttachMilestone = null
        activeTarget = null
        connectingTarget = if (preserveReconnectTarget) target else null
        refreshReconnectAvailability()
        _connectionStatus.value = ConnectionStatus.Failed(message)
    }

    private fun connectFailureMessage(t: Throwable, target: ConnectionTarget): String =
        if (t is TmuxAttachPanesReadyException) {
            val message = t.message ?: "Timed out waiting for tmux panes from ${target.sessionName}."
            if ("Tap Reconnect" in message) message else "$message Tap Reconnect to retry."
        } else {
            "error: ${t.javaClass.simpleName}: ${t.message}"
        }

    private fun logAttachMilestone(
        attempt: Int,
        target: ConnectionTarget,
        startedAtMs: Long,
        event: String,
        trigger: TmuxConnectTrigger,
        detail: String = "",
    ) {
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            attachMilestoneMessage(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                event = event,
                trigger = trigger,
                detail = detail,
            ),
        )
    }

    private fun attachMilestoneMessage(
        attempt: Int,
        target: ConnectionTarget,
        startedAtMs: Long,
        event: String,
        trigger: TmuxConnectTrigger,
        detail: String = "",
    ): String = buildString {
        append(event)
        append(" attempt=")
        append(attempt)
        append(" trigger=")
        append(trigger.logValue)
        append(' ')
        append(targetLogFields(target))
        append(" elapsedMs=")
        append(SystemClock.elapsedRealtime() - startedAtMs)
        if (detail.isNotBlank()) {
            append(' ')
            append(detail)
        }
    }

    private fun logFirstPaneOutput(event: ControlEvent.Output) {
        val milestone = activeAttachMilestone ?: return
        if (milestone.firstPaneOutputLogged) return
        milestone.firstPaneOutputLogged = true
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-first-pane-output attempt=${milestone.attempt} " +
                "trigger=${milestone.trigger.logValue} session=${milestone.sessionName} " +
                "pane=${event.paneId} bytes=${event.data.size} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - milestone.startedAtMs}",
        )
        TmuxSessionLatencyTelemetry.record(
            name = "first_visible_output",
            durationMs = SystemClock.elapsedRealtime() - milestone.startedAtMs,
            sessionName = milestone.sessionName,
            paneId = event.paneId,
            trigger = milestone.trigger,
            detail = "bytes=${event.data.size}",
        )
    }

    /**
     * Bind the view model to [client] and start the event-subscription
     * loop. Visible-for-test and visible to [runConnect]. The caller is
     * responsible for having already called [TmuxClient.connect] (in the
     * SSH path) OR for being a test that drives events synthetically.
     *
     * Subscribes to [TmuxClient.events] inside the bridge scope. The
     * subscription is launched before this method returns, so callers can
     * immediately call `client.connect()` and the opening events will be
     * caught by the buffered shared flow.
     */
    internal fun attachClient(client: TmuxClient) {
        resetControlClientSizeForAttach()
        clientRef = client
        // Cancel any previous subscription before re-binding (idempotency
        // for tests that swap clients on the same ViewModel instance).
        eventsJob?.cancel()
        val job = bridgeScope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                onControlEvent(event)
            }
        }
        eventsJob = job
        // Issue #173: observe the client's latched `disconnected`
        // StateFlow so we flip [_connectionStatus] to Failed when the
        // underlying [TmuxClient.readerLoop] exits (clean EOF, sshj
        // exception, or [TmuxClient.close]). The hot [TmuxClient.events]
        // SharedFlow does NOT signal end-of-stream when the reader dies,
        // so this is the only path that catches an OS-driven socket
        // tear-down (e.g. Android severing the TCP socket while the
        // app was backgrounded for a screenshot). The observer is
        // launched in the same `bridgeScope` so it is torn down by
        // `closeCurrentConnection*` along with the rest of the
        // per-connection coroutines.
        //
        // We capture [client] in the launched lambda so a later swap
        // (TmuxSessionViewModel.connect against a different host /
        // session) does not race: only the disconnected-signal of the
        // client we attached just now is acted on.
        bridgeScope.launch {
            client.disconnected.collect { dead ->
                if (!dead) return@collect
                val current = _connectionStatus.value
                if (current !is ConnectionStatus.Connected) return@collect
                // Only react if this is still THE active client. The
                // `connect()` race-recovery path attaches a fresh
                // [TmuxClient] and closes the old one inside the same
                // VM; the old client's disconnected signal must not
                // overwrite the new client's Connected status.
                if (clientRef !== client) return@collect
                // Issue #145: prefer the actionable phrasing — host
                // coordinates plus "Tap Reconnect to retry." — over the
                // bare diagnostic so the in-session disconnect band
                // tells the user both WHERE they were and WHAT to do
                // next. The screen renders a Reconnect button alongside
                // this message (see [FailedConnectionRow] in
                // [TmuxSessionScreen]) gated on [canReconnect]. Logged
                // under the dedicated tag so the connected
                // disconnect+reconnect test can correlate with the
                // connect-attempt counter.
                Log.w(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-mid-session-disconnect host=${current.host} " +
                        "port=${current.port} user=${current.user}",
                )
                _connectionStatus.value = ConnectionStatus.Failed(
                    "Disconnected from ${current.user}@${current.host}:${current.port}. " +
                        "Tap Reconnect to retry.",
                )
            }
        }
    }

    /**
     * Visible-for-test entry point: bind the view model to a
     * caller-supplied [TmuxClient] without the SSH connect / factory
     * path. Tests drive the client's [TmuxClient.events] flow and assert
     * against [panes].
     */
    internal fun attachClientForTest(client: TmuxClient) {
        attachClient(client)
        _connectionStatus.value = ConnectionStatus.Connected("test", 0, "test")
    }

    internal fun replaceClientForTest(
        hostId: Long,
        hostName: String,
        host: String,
        port: Int,
        user: String,
        keyPath: String,
        sessionName: String,
        client: TmuxClient,
        session: SshSession? = null,
    ) {
        val target = ConnectionTarget(
            hostId = hostId,
            hostName = hostName,
            host = host,
            port = port,
            user = user,
            keyPath = keyPath,
            passphrase = null,
            sessionName = sessionName,
            startDirectory = null,
        )
        closeCurrentConnection()
        // Issue #178: tests for the same-host fast-switch path need a
        // way to inject a live `SshSession` into the VM so a follow-up
        // `connect()` to the same host re-uses it instead of going
        // through the SSH-handshake path (which requires a real
        // network). Production callers go through `runConnect` /
        // `runFastSessionSwitch` — those wire the session ref
        // themselves.
        if (session != null) {
            sessionRef = session
        }
        attachClient(client)
        activeTmuxClients.register(
            hostId = hostId,
            hostName = hostName,
            hostname = host,
            port = port,
            username = user,
            keyPath = keyPath,
            client = client,
        )
        registeredHostId = hostId
        installLifecycleHooks(hostId)
        activeTarget = target
        bindProjectRootsForHost(hostId)
        _connectionStatus.value = ConnectionStatus.Connected(host, port, user)
        maybeRefreshControlClientSize()
    }

    /**
     * Issue #178 test seam: synchronously run the same-host fast-switch
     * code path against caller-supplied [session] + [newClient]. Mirrors
     * what production's connect() does when the eligibility check
     * passes, but skips the connectJob launch + suspended cancel-and-
     * join machinery so unit tests can assert against the resulting
     * state without driving the full coroutine state machine.
     *
     * Pre-conditions: the VM must already be "connected" to a target on
     * the same host (typically via [replaceClientForTest]) so the
     * eligibility check (`isSameHost(activeTarget, target)`) returns
     * true. The caller passes a fake [session] that is `isConnected =
     * true`.
     */
    internal suspend fun fastSwitchSessionForTest(
        hostId: Long,
        hostName: String,
        host: String,
        port: Int,
        user: String,
        keyPath: String,
        sessionName: String,
        client: TmuxClient,
        session: SshSession,
    ) {
        val target = ConnectionTarget(
            hostId = hostId,
            hostName = hostName,
            host = host,
            port = port,
            user = user,
            keyPath = keyPath,
            passphrase = null,
            sessionName = sessionName,
            startDirectory = null,
        )
        connectingTarget = target
        refreshReconnectAvailability()
        _connectionStatus.value = ConnectionStatus.Connecting(host, port, user)
        // Mirror production's ordering: tear down the previous tmux
        // client (keeping the session) before binding the new one.
        closeCurrentClientKeepSession()
        sessionRef = session
        // Inline a simplified runFastSessionSwitch: we cannot call the
        // real method directly because it pulls tmuxClientFactory in,
        // and the test fakes the [TmuxClient] outright.
        activeTarget = target
        refreshReconnectAvailability()
        attachClient(client)
        client.connect()
        activeTmuxClients.register(
            hostId = hostId,
            hostName = hostName,
            hostname = host,
            port = port,
            username = user,
            keyPath = keyPath,
            client = client,
        )
        registeredHostId = hostId
        installLifecycleHooks(hostId)
        bindProjectRootsForHost(hostId)
        connectingTarget = null
        refreshReconnectAvailability()
        _connectionStatus.value = ConnectionStatus.Connected(host, port, user)
        maybeRefreshControlClientSize()
    }

    internal fun setAttachPanesReadyTimeoutForTest(timeoutMs: Long) {
        attachPanesReadyTimeoutMs = timeoutMs
    }

    internal suspend fun attachClientWithReadinessForTest(
        hostId: Long,
        hostName: String,
        host: String,
        port: Int,
        user: String,
        keyPath: String,
        sessionName: String,
        client: TmuxClient,
    ) {
        val target = ConnectionTarget(
            hostId = hostId,
            hostName = hostName,
            host = host,
            port = port,
            user = user,
            keyPath = keyPath,
            passphrase = null,
            sessionName = sessionName,
            startDirectory = null,
        )
        val attempt = TMUX_CONNECT_ATTEMPTS.incrementAndGet()
        val startedAtMs = SystemClock.elapsedRealtime()
        val trigger = TmuxConnectTrigger.UserTap
        connectingTarget = target
        activeTarget = target
        refreshReconnectAvailability()
        _connectionStatus.value = ConnectionStatus.Connecting(host, port, user)
        attachClient(client)
        TmuxSessionLatencyTelemetry.record(
            name = "tmux_control_attach_count",
            durationMs = 1L,
            sessionName = sessionName,
            trigger = trigger,
        )
        client.connect()
        activeAttachMilestone = AttachMilestone(
            attempt = attempt,
            sessionName = sessionName,
            startedAtMs = startedAtMs,
            trigger = trigger,
        )
        try {
            awaitPanesReadyForAttach(target, attempt, startedAtMs, trigger)
            connectingTarget = null
            refreshReconnectAvailability()
            _connectionStatus.value = ConnectionStatus.Connected(host, port, user)
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            failConnectAttempt(
                target = target,
                attempt = attempt,
                startedAtMs = startedAtMs,
                message = connectFailureMessage(t, target),
                cause = t,
                preserveReconnectTarget = t is TmuxAttachPanesReadyException,
            )
        }
    }

    /**
     * Issue #165 test seam: stamp the ViewModel into [ConnectionStatus.Connecting]
     * with [connectJob] pointing at a caller-supplied [job] so unit tests can
     * exercise [cancelConnect] without running the full SSH handshake path.
     * Mirrors how the early lines of [connect] would flip the state if the
     * handshake actually fired.
     *
     * Returns the same [job] so the caller can inspect its `isCancelled`
     * post-cancel without storing the reference twice.
     */
    internal fun beginConnectingForTest(
        host: String,
        port: Int,
        user: String,
        sessionName: String = "test",
        job: Job,
    ): Job {
        connectingTarget = ConnectionTarget(
            hostId = 0L,
            hostName = "",
            host = host,
            port = port,
            user = user,
            keyPath = "",
            passphrase = null,
            sessionName = sessionName,
            startDirectory = null,
        ).also { target ->
            latestConnectIntent = ConnectIntent(
                target = target,
                trigger = TmuxConnectTrigger.UserTap,
                generation = nextConnectGeneration(),
            )
        }
        refreshReconnectAvailability()
        _connectionStatus.value = ConnectionStatus.Connecting(host, port, user)
        connectJob = job
        return job
    }

    /**
     * Issue #178 test seam: tells callers whether the eligibility
     * predicate would treat the supplied target as a same-host fast
     * switch against the current [activeTarget]. Returns false if
     * there is no active target or no live SSH session reference. Used
     * by unit tests to pin the predicate behaviour in isolation from
     * the rest of the connect() pipeline.
     */
    internal fun isFastSwitchEligibleForTest(
        host: String,
        port: Int,
        user: String,
        keyPath: String,
        sessionName: String,
    ): Boolean {
        val previous = activeTarget ?: return false
        val previousSession = sessionRef ?: return false
        if (!previousSession.isConnected) return false
        val target = ConnectionTarget(
            hostId = 0L,
            hostName = "",
            host = host,
            port = port,
            user = user,
            keyPath = keyPath,
            passphrase = null,
            sessionName = sessionName,
            startDirectory = null,
        )
        return isSameHost(previous, target) &&
            previous.sessionName != target.sessionName
    }

    /**
     * Subscribe [_projectRoots] to the DAO-backed roots for [hostId].
     * Idempotent: cancels any prior subscription before starting a new
     * one. When no DAO is wired (unit tests that don't care about roots),
     * leaves the flow empty.
     */
    private fun bindProjectRootsForHost(hostId: Long) {
        projectRootsJob?.cancel()
        _projectRoots.value = emptyList()
        val dao = projectRootDao ?: return
        projectRootsJob = viewModelScope.launch {
            dao.getByHostId(hostId).collectLatest { roots ->
                _projectRoots.value = roots
            }
        }
    }

    /**
     * Process one event from the bus.
     *
     * Per the issue body the structural events of interest are
     * [ControlEvent.WindowAdd] / [ControlEvent.WindowClose] /
     * [ControlEvent.LayoutChange]: each one triggers a session-scoped
     * `list-panes` round-trip that re-derives the pane list authoritatively. We do not
     * try to mutate [_panes] in-place from event payloads — see the
     * class-level docs for the rationale on why a round-trip is the
     * right call here.
     */
    private suspend fun onControlEvent(event: ControlEvent) {
        when (event) {
            is ControlEvent.WindowAdd,
            is ControlEvent.WindowClose,
            is ControlEvent.LayoutChange,
            -> reconcilePanes()
            is ControlEvent.Output -> logFirstPaneOutput(event)
            else -> Unit
        }
    }

    /**
     * Ask tmux for the current pane set and reconcile [_panes].
     *
     * Format string carries pane/window/session metadata plus command
     * context: `<pane-id> <window-id> <session-id> <session-name> ...`. We pick the
     * non-printable `\t` as the field separator because pane titles can
     * contain spaces but cannot contain literal tabs (tmux strips them).
     *
     * Errors from `list-panes` (e.g. the server torn down mid-request)
     * leave the existing pane list intact rather than wiping it — a
     * transient failure should not blank the UI.
     */
    private suspend fun reconcilePanes(): PaneReconcileResult {
        val client = clientRef ?: return PaneReconcileResult.NoClient
        val target = activeTarget
        val listPanesStartedAtMs = SystemClock.elapsedRealtime()
        val response = try {
            client.sendCommand(
                buildListPanesCommand(target),
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            return PaneReconcileResult.Failed(t)
        }
        TmuxSessionLatencyTelemetry.record(
            name = "list_panes",
            durationMs = SystemClock.elapsedRealtime() - listPanesStartedAtMs,
            sessionName = target?.sessionName,
            trigger = activeAttachMilestone?.trigger,
            detail = "isError=${response.isError}",
        )
        if (response.isError) {
            return PaneReconcileResult.Failed(
                TmuxAttachPanesReadyException(
                    "tmux list-panes failed: " +
                        response.output.joinToString(separator = " ").ifBlank { "unknown error" },
                ),
            )
        }

        val parsed: List<ParsedPane> = response.output.mapNotNull { parsePaneRow(it) }
        val newPanes = applyParsedPanes(parsed)
        preloadVisibleContentForNewPanes(newPanes)
        return PaneReconcileResult.Ready(_panes.value.size)
    }

    private fun buildListPanesCommand(target: ConnectionTarget?): String = buildString {
        // pane_index is appended last so we can sort within a window.
        // tmux can change index order on layout-rotate commands, so we
        // re-read it on every reconcile.
        //
        // Per #158: include `-s` so we list panes across every window
        // in the session, not only the current window.
        append("list-panes ")
        if (target != null) {
            append("-s -t '${escapeSingleQuoted(target.sessionName)}' ")
        }
        append("-F ")
        // Issue #186: append `#{pane_tty}` so per-pane agent detection
        // can scope its process scan to the pane's TTY.
        append("'#{pane_id}")
        append(LIST_PANES_FIELD_SEPARATOR)
        append("#{window_id}")
        append(LIST_PANES_FIELD_SEPARATOR)
        append("#{session_id}")
        append(LIST_PANES_FIELD_SEPARATOR)
        append("#{session_name}")
        append(LIST_PANES_FIELD_SEPARATOR)
        append("#{pane_title}")
        append(LIST_PANES_FIELD_SEPARATOR)
        append("#{pane_index}")
        append(LIST_PANES_FIELD_SEPARATOR)
        append("#{pane_current_path}")
        append(LIST_PANES_FIELD_SEPARATOR)
        append("#{pane_current_command}")
        append(LIST_PANES_FIELD_SEPARATOR)
        append("#{pane_tty}'")
    }

    /**
     * Visible-for-test seam: bypass tmux and apply a synthetic pane list
     * directly. Lets unit tests verify the per-pane
     * [TerminalSurfaceState] wiring without standing up the tmux command-
     * response loop.
     */
    internal fun applyParsedPanesForTest(parsed: List<ParsedPane>) {
        applyParsedPanes(parsed)
    }

    private fun applyParsedPanes(parsed: List<ParsedPane>): List<TmuxPaneState> {
        val client = clientRef
        val target = activeTarget
        val sorted = parsed
            .filter { pane -> target == null || pane.sessionName == target.sessionName }
            .sortedWith(compareBy({ it.windowId }, { it.paneIndex }, { it.paneId }))

        val nextById: MutableMap<String, TmuxPaneState> = LinkedHashMap()
        val newRows = mutableListOf<TmuxPaneState>()
        for (p in sorted) {
            val existing = paneRows[p.paneId]
            val row = if (existing != null) {
                // Reuse the existing TerminalSurfaceState so the emulator
                // and its scrollback survive the reconcile. Update the
                // immutable metadata if it changed.
                existing.copy(
                    windowId = p.windowId,
                    sessionId = p.sessionId,
                    title = p.title,
                    cwd = p.cwd,
                    currentCommand = p.currentCommand,
                    // Issue #186: refresh `paneTty` so per-pane agent
                    // detection always uses the current TTY (tmux can
                    // rotate panes between ptys on detach/reattach in
                    // rare cases).
                    paneTty = p.paneTty,
                )
            } else {
                val state = TerminalSurfaceState()
                // Wire the pane-filtered output flow into the new state's
                // emulator. The producer is launched in bridgeScope so it
                // outlives recomposition; cancelling the scope (via
                // onCleared) tears the bridge down cleanly.
                if (client != null) {
                    val job = state.attachExternalProducer(
                        scope = bridgeScope,
                        stdout = client.outputFor(p.paneId).map { it.data },
                        // tmux -CC has no per-pane PTY fd, so the terminal's
                        // input queue is bridged to tmux `send-keys`.
                        remoteStdin = inputSinkForPane(p.paneId),
                        suppressQueryResponses = true,
                    )
                    paneProducerJobs[p.paneId] = job
                }
                TmuxPaneState(
                    paneId = p.paneId,
                    windowId = p.windowId,
                    sessionId = p.sessionId,
                    title = p.title,
                    cwd = p.cwd,
                    currentCommand = p.currentCommand,
                    paneTty = p.paneTty,
                    terminalState = state,
                ).also { newRows += it }
            }
            nextById[p.paneId] = row
            startAgentDetectionForPane(row)
        }

        // Tear down panes that disappeared. Cancel the producer + detach
        // the bridge so the TerminalSurfaceState releases its emulator
        // reference cleanly.
        val gonePaneIds = paneRows.keys - nextById.keys
        for (paneId in gonePaneIds) {
            paneProducerJobs.remove(paneId)?.cancel()
            paneAgentJobs.remove(paneId)?.cancel()
            paneAgentInputs.remove(paneId)
            paneInputJobs.remove(paneId)?.cancel()
            paneInputQueues.remove(paneId)?.close()
            paneRows[paneId]?.terminalState?.detachExternalProducer()
            paneRows.remove(paneId)
        }
        _agentConversations.value = _agentConversations.value.filterKeys { it in nextById.keys }
        paneRows.putAll(nextById)
        _panes.value = nextById.values.toList()
        return newRows
    }

    private suspend fun preloadVisibleContentForNewPanes(newPanes: List<TmuxPaneState>) {
        val client = clientRef ?: return
        for (pane in newPanes) {
            val captureStartedAtMs = SystemClock.elapsedRealtime()
            val response = runCatching {
                client.sendCommand("capture-pane -p -e -S -200 -t ${pane.paneId}")
            }.getOrNull()
            TmuxSessionLatencyTelemetry.record(
                name = "capture_pane",
                durationMs = SystemClock.elapsedRealtime() - captureStartedAtMs,
                sessionName = activeTarget?.sessionName,
                paneId = pane.paneId,
                trigger = activeAttachMilestone?.trigger,
                detail = "success=${response != null && !response.isError}",
            )
            if (response == null || response.isError || response.output.isEmpty()) continue
            // Issue #259: ask tmux for the pane's true cursor position so the
            // seed can restore it after replaying the capture. Without this the
            // emulator's cursor lands below the captured content, and the
            // agent's next in-place status/spinner rewrite (`\r` + frame, no
            // re-home) paints on the wrong row — stranding the seeded frame
            // above the live one and mashing fragments of different frames
            // together (the reported garble). A missing/old/malformed reply
            // degrades to a seed with no explicit cursor restore.
            val cursorStartedAtMs = SystemClock.elapsedRealtime()
            val cursor = runCatching {
                client.sendCommand(
                    "display-message -p -t ${pane.paneId} '#{cursor_x},#{cursor_y}'",
                )
            }.getOrNull()
                .also { cursorResponse ->
                    TmuxSessionLatencyTelemetry.record(
                        name = "cursor_query",
                        durationMs = SystemClock.elapsedRealtime() - cursorStartedAtMs,
                        sessionName = activeTarget?.sessionName,
                        paneId = pane.paneId,
                        trigger = activeAttachMilestone?.trigger,
                        detail = "success=${cursorResponse != null && !cursorResponse.isError}",
                    )
                }
                ?.takeUnless { it.isError }
                ?.output
                ?.firstOrNull()
                .let { parseTmuxPaneCursor(it) }
            val appendStartedAtMs = SystemClock.elapsedRealtime()
            pane.terminalState.appendRemoteOutput(
                response.output.toTerminalViewportBytes(cursor),
            )
            TmuxSessionLatencyTelemetry.record(
                name = "terminal_output_append_to_buffer",
                durationMs = SystemClock.elapsedRealtime() - appendStartedAtMs,
                sessionName = activeTarget?.sessionName,
                paneId = pane.paneId,
                trigger = activeAttachMilestone?.trigger,
            )
        }
    }

    /**
     * Issue #186: resolve the agent kind for [windowId] from the
     * per-pane detection state, or null when no pane in that window has
     * an agent. The screen consumes this to gate the Conversation tab
     * on the **currently-visible** window's detection
     * state instead of the session-wide one — which is what made
     * v0.2.8 light up "Claude detected" on plain-shell windows that
     * just shared a cwd with the agent window.
     *
     * Returns null for a blank windowId so callers that read
     * `currentPane?.windowId` (potentially null between attach and the
     * first pane reconcile) get a deterministic null instead of a stale
     * sibling-window kind.
     *
     * Picks the kind from the **first** pane in the window with a
     * non-null detection, ordered by pane index per [_panes] (which
     * already sorts panes within a window by `pane_index`). Multiple
     * agents in a single window is rare in practice (tmux users almost
     * always one-agent-per-window); when it does happen, the lower
     * pane index wins so the tab shows a stable kind across reconciles.
     */
    public fun agentForWindow(windowId: String?): AgentKind? {
        if (windowId.isNullOrBlank()) return null
        val conversations = _agentConversations.value
        return _panes.value
            .asSequence()
            .filter { it.windowId == windowId }
            .mapNotNull { conversations[it.paneId]?.detection?.agent }
            .firstOrNull()
    }

    private fun startAgentDetectionForPane(pane: TmuxPaneState) {
        val session = sessionRef ?: return
        val cwd = pane.cwd.takeIf { it.isNotBlank() } ?: return
        val command = pane.currentCommand
        val tty = pane.paneTty
        // Issue #186: include the pane TTY in the dedup key so a tmux
        // re-attach that re-assigns the pane to a different `/dev/pts/N`
        // re-runs detection. Without the tty in the key, a pane that
        // moved between ttys would keep its stale "no agent" verdict
        // even after the agent CLI was started on the new tty.
        val input = Triple(cwd, command, tty)
        if (paneAgentInputs[pane.paneId] == input && paneAgentJobs[pane.paneId]?.isActive == true) return
        paneAgentJobs.remove(pane.paneId)?.cancel()
        paneAgentInputs[pane.paneId] = input
        val paneId = pane.paneId
        paneAgentJobs[paneId] = bridgeScope.launch {
            // Issue #186: run the per-pane detection path so a sibling
            // window's JSONL log cannot light up the Conversation tab
            // on a non-agent window that just shares a cwd. The legacy
            // session-scoped [detect] is intentionally NOT called here
            // — its host-wide process scan was the root cause of the
            // "Claude detected" misattribution reported in the v0.2.8
            // feedback.
            //
            // When `tty` is blank (older tmux that does not emit
            // `#{pane_tty}`, or a freshly-discovered pane between
            // bootstrap and the first list-panes round-trip), the
            // repository returns null — preserving the old behaviour
            // that "no signal = no detection" for this pane.
            val detection = runCatching {
                agentRepository.detectForPane(
                    session = session,
                    cwd = cwd,
                    paneTty = tty,
                    paneCommand = command,
                )
            }.getOrNull()
            if (detection == null) {
                // Issue #186: when a pane that previously had a
                // detection no longer does (the user exited Claude /
                // Codex / OpenCode, or the agent process died), clear
                // the per-pane conversation state so the Conversation
                // tab disappears for this window.
                clearAgentDetectionForPane(paneId)
                return@launch
            }
            startAgentConversationForPane(session, paneId, detection)
        }
    }

    /**
     * Issue #186: drop the per-pane agent conversation state when
     * detection comes back null. Mirrors the cleanup done in
     * [applyParsedPanes] for a pane that tmux removed, but applies to a
     * still-live pane whose agent just left.
     *
     * Cancels any active tail job and removes the conversation row from
     * [_agentConversations].
     */
    private fun clearAgentDetectionForPane(paneId: String) {
        val current = _agentConversations.value[paneId] ?: return
        if (current.detection == null) return
        _agentConversations.value = _agentConversations.value - paneId
    }

    private suspend fun startAgentConversationForPane(
        session: SshSession,
        paneId: String,
        detection: AgentDetection,
    ) {
        val lineCount = runCatching { agentRepository.lineCount(session, detection) }.getOrDefault(0L)
        val initialEvents = runCatching {
            agentRepository.readInitialEvents(session, detection)
        }.getOrDefault(emptyList())
        setAgentConversation(
            paneId,
            AgentConversationUiState(
                detection = detection,
                events = boundedDistinctEvents(initialEvents),
                selectedTab = SessionTab.Terminal,
            ),
        )
        // Issue #160: OpenCode now tails its JSONL via `session.tail`
        // identically to Claude and Codex. No more polling branch — the
        // tmux pane gets the same real-time refresh as the raw-SSH route.
        val followJob = agentRepository.tailEventsFromLine(session, detection, lineCount) { event ->
            appendAgentEvents(paneId, listOf(event))
        }
        if (followJob != null) {
            paneAgentJobs[paneId] = followJob
        }
    }

    /**
     * Issue #160: send [text] as a chat message into the agent that's
     * running in tmux pane [paneId].
     *
     * Inserts an optimistic [ConversationEvent.Message] with
     * [ConversationRole.User] into the conversation feed first so the
     * UI updates without waiting for the agent's JSONL to be appended
     * and tailed back. The text is then written into the pane via the
     * existing `send-keys` path with a trailing carriage return so the
     * agent reads it as a submitted prompt.
     *
     * No-op if [paneId] has no detected agent conversation (the user
     * shouldn't be able to reach this from the screen, but the
     * defensive check matches the rest of the public API).
     */
    public fun sendToAgentPane(paneId: String, text: String) {
        val payload = text.trim()
        if (payload.isEmpty()) return
        val current = _agentConversations.value[paneId] ?: return
        val detection = current.detection ?: return
        val optimistic = ConversationEvent.Message(
            // Issue #160 round 2: see [OPTIMISTIC_USER_MESSAGE_ID_PREFIX].
            id = "$OPTIMISTIC_USER_MESSAGE_ID_PREFIX${System.nanoTime()}",
            agent = detection.agent,
            atMillis = System.currentTimeMillis(),
            role = ConversationRole.User,
            text = payload,
        )
        appendAgentEvents(paneId, listOf(optimistic))
        writeInputToPane(paneId, (payload + "\r").toByteArray(Charsets.UTF_8))
    }

    public fun selectSessionTab(paneId: String, tab: SessionTab) {
        val current = _agentConversations.value[paneId] ?: return
        if (tab == SessionTab.Conversation && current.detection == null) return
        setAgentConversation(paneId, current.copy(selectedTab = tab))
    }

    /**
     * Issue #154 (acceptance criterion #5): hoist the per-pane
     * conversation search query into the ViewModel so it survives
     * Terminal ↔ Conversation tab switches (the previous local
     * `remember` lost the query on every tab flip). Bound to the
     * search field's `onValueChange` inside [TmuxConversationPane].
     */
    public fun setAgentSearchQuery(paneId: String, query: String) {
        val current = _agentConversations.value[paneId] ?: return
        if (current.searchQuery == query) return
        setAgentConversation(paneId, current.copy(searchQuery = query))
    }

    private fun appendAgentEvents(paneId: String, events: List<ConversationEvent>) {
        if (events.isEmpty()) return
        val current = _agentConversations.value[paneId] ?: return
        setAgentConversation(paneId, current.copy(events = boundedDistinctEvents(current.events + events)))
    }

    private fun setAgentConversation(paneId: String, state: AgentConversationUiState) {
        _agentConversations.value = _agentConversations.value + (paneId to state)
    }

    /**
     * Test seam: synthesize a freshly-detected agent conversation on
     * [paneId] without going through the production SSH + JSONL path.
     * The state mirrors what [startAgentConversationForPane] produces:
     * initial events with the Terminal tab selected. Issue #282 removed
     * the tmux detection popup, so this no longer seeds hint state.
     */
    internal fun startAgentConversationForTest(
        paneId: String,
        detection: AgentDetection,
        initialEvents: List<ConversationEvent> = emptyList(),
    ) {
        setAgentConversation(
            paneId,
            AgentConversationUiState(
                detection = detection,
                events = boundedDistinctEvents(initialEvents),
                selectedTab = SessionTab.Terminal,
            ),
        )
    }

    /**
     * Issue #179 test seam: replay an append of new JSONL events for
     * [paneId] without going through the production tail loop. Mirrors
     * what [agentRepository.tailEventsFromLine] would do — i.e. invokes
     * the same internal [appendAgentEvents] so the dedup + bounding
     * pass runs identically.
     */
    internal fun appendAgentEventsForTest(paneId: String, events: List<ConversationEvent>) {
        appendAgentEvents(paneId, events)
    }

    /**
     * Issue #186 test seam: replay the "agent left this pane" path
     * that production calls when a subsequent [detectForPane] returns
     * null. Drives the same internal [clearAgentDetectionForPane] so
     * tests can assert the lock + conversation row clearing without
     * standing up a real SSH session.
     */
    internal fun clearAgentDetectionForPaneForTest(paneId: String) {
        clearAgentDetectionForPane(paneId)
    }

    /**
     * Send a single key payload to [paneId] via `send-keys`.
     *
     * tmux's `send-keys` understands literal strings (passed as a single
     * argument) and a vocabulary of named keys (`Enter`, `Tab`, `Escape`,
     * `Up`, `Down`, ...). We forward the bytes as a literal single-quoted
     * argument with embedded quotes doubled — the simplest encoding that
     * round-trips arbitrary printable input. Callers that need a named
     * key (e.g. arrows) should pass it through [sendNamedKey] instead.
     *
     * Per-pane I/O does NOT go through the SSH shell — `tmux -CC` does
     * not expose a per-pane writable fd on the control channel. The
     * canonical and only supported route to "type into a pane" through
     * the control protocol is `send-keys`, verified by
     * `TmuxClientIntegrationTest` against the test container.
     */
    public fun writeInputToPane(paneId: String, bytes: ByteArray) {
        if (bytes.isEmpty()) return
        val client = clientRef ?: return
        bridgeScope.launch {
            runCatching {
                sendInputBytesToPane(client, paneId, bytes)
            }
        }
    }

    internal fun sendControlInputToPane(paneId: String, byte: Int, repeatCount: Int = 1) {
        if (byte !in 0x00..0x1F || repeatCount <= 0) return
        val client = clientRef ?: return
        val bytes = ByteArray(repeatCount) { byte.toByte() }
        bridgeScope.launch {
            runCatching {
                sendRawInputBytes(client, paneId, bytes)
            }
        }
    }

    /**
     * Voice Command-mode (and typed) action-assistant entry point (issue
     * #266). Replaces the deleted `planVoiceCommand`: the transcript drives
     * the agent loop, which inspects state and performs actions through
     * tools, gating mutating actions behind confirm-or-correct. `run_command`
     * targets [focusedPaneId] — the pane the user is currently looking at.
     */
    public fun dictateToAssistant(transcript: String, focusedPaneId: String? = null) {
        assistantFocusedPaneId = focusedPaneId
        assistant.start(transcript)
    }

    /** Confirm the pending mutating candidate. */
    public fun confirmAssistantAction() = assistant.confirm()

    /** Reject the candidate and supply a [correction] (voice or typed). */
    public fun correctAssistantAction(correction: String) = assistant.correct(correction)

    /** Cancel the pending mutating candidate. */
    public fun cancelAssistantAction() = assistant.cancel()

    /** Dismiss the assistant surface and reset to idle. */
    public fun dismissAssistant() = assistant.dismiss()

    private fun buildAssistantDeps(): SessionAssistantController.AssistantRunDeps? {
        val client = assistantClientFactory?.create() ?: return null
        val dao = hostDao ?: return null
        val gateway = folderListGateway ?: return null
        val repos = reposRemoteSource ?: return null
        val context = applicationContext ?: return null
        val target = activeTarget ?: return null

        val focusedPaneId = assistantFocusedPaneId
        val focusedPane = focusedPaneId?.let { paneRows[it] }

        val bridge = object : SessionActionBridge {
            override fun activeHostName(): String? =
                target.hostName.takeIf { it.isNotBlank() } ?: target.host
            override fun activeCwd(): String? = focusedPane?.cwd?.takeIf { it.isNotBlank() }
            override fun activeSessionName(): String? = target.sessionName
            override fun currentScreenLabel(): String =
                "tmux session ${target.sessionName} on ${target.hostName}"
            override fun sendCommand(command: String) {
                val pane = focusedPaneId ?: return
                val payload = command + "\r"
                writeInputToPane(pane, payload.toByteArray(Charsets.UTF_8))
            }
            override fun navigate(destination: AppDestination) {
                _assistantNavRequests.tryEmit(destination)
            }
        }

        val hostName = target.hostName.takeIf { it.isNotBlank() } ?: target.host
        fun paramsForActive(): AssistantSshParams = AssistantSshParams(
            hostId = target.hostId,
            hostName = hostName,
            hostname = target.host,
            port = target.port,
            username = target.user,
            keyPath = target.keyPath,
            passphrase = target.passphrase,
        )

        val actions: AssistantActions = AppAssistantActions(
            bridge = bridge,
            hostDao = dao,
            folderListGateway = gateway,
            reposRemoteSource = repos,
            sshExecutor = assistantSshExecutor,
            resolveParams = { name -> paramsForActive().takeIf { it.hostName == name } },
            activeParams = ::paramsForActive,
        )

        return SessionAssistantController.AssistantRunDeps(
            client = client,
            actions = actions,
            traceSink = ExecutorTraceSink(assistantSshExecutor) { paramsForActive() },
            installId = AssistantInstallId.get(context),
            sessionId = target.sessionName,
        )
    }

    private fun inputSinkForPane(paneId: String): OutputStream {
        val queue = paneInputQueues.getOrPut(paneId) {
            TmuxPaneInputQueue(
                maxPendingBytes = TMUX_INPUT_MAX_PENDING_BYTES,
                maxBatchBytes = TMUX_INPUT_MAX_BATCH_BYTES,
            ).also { newQueue ->
                paneInputJobs[paneId] = bridgeScope.launch {
                    while (true) {
                        val batch = newQueue.takeBatch() ?: break
                        val client = clientRef ?: continue
                        val sendStartedNs = System.nanoTime()
                        runCatching { sendInputBytesToPane(client, paneId, batch.bytes) }
                            .onSuccess {
                                newQueue.recordSent(batch, System.nanoTime() - sendStartedNs)
                            }
                    }
                }
            }
        }
        return TmuxPaneInputStream(queue)
    }

    private suspend fun sendInputBytesToPane(
        client: TmuxClient,
        paneId: String,
        bytes: ByteArray,
    ) {
        // Issue #243: replies generated by the local terminal emulator
        // already contain the exact ESC-prefixed bytes the remote program
        // queried for (OSC colors, device attributes, etc.). Passing them
        // through inputTokens turns ESC into a tmux named key and leaks the
        // printable tail into the pane. Keep user input on the existing
        // paths, but preserve complete terminal reports byte-for-byte.
        if (isTerminalGeneratedResponse(bytes)) {
            sendRawInputBytes(client, paneId, bytes)
            return
        }

        // Issue #209: multi-line input (containing `\n` or `\r\n`) goes
        // through tmux's bracketed-paste route so the foreground program
        // (Claude Code CLI, modern bash, zsh, vim, …) treats the entire
        // block as ONE pasted prompt instead of submitting line-by-line.
        //
        // Why this matters: the previous shape split the input on `\n`
        // and emitted a `send-keys ... Enter` named-key per line break.
        // Claude Code CLI interprets each Enter as "submit this prompt",
        // so a multi-paragraph dictation transcript landed as N
        // independent messages — the bug the maintainer found in daily use
        // on 2026-05-27.
        //
        // Bracketed-paste mode is a terminal protocol: the program tells
        // its terminal "I want to know when text is pasted" via DECSET
        // 2004 (`\e[?2004h`), and the terminal then frames pastes with
        // `\e[200~` / `\e[201~`. Inside the markers, readline-based CLIs
        // (Claude Code, modern shells with `enable-bracketed-paste` on,
        // vim, …) treat embedded newlines as literal content, not as
        // submit signals. If a program does not enable bracketed paste
        // (handful of niche TUIs, plain `cat`), the markers appear as
        // literal text — acceptable degradation per #209's design.
        //
        // tmux exposes literal byte injection in two flavours:
        //   - `send-keys -l '<text>'` writes the UTF-8 bytes of the
        //     argument. tmux's command parser terminates commands at
        //     `\n`, so we cannot embed a literal newline inside the
        //     argument — and that's exactly the byte we need to send
        //     for the paste body.
        //   - `send-keys -H <hex pairs>` writes the bytes named by the
        //     space-separated hex pairs. This lets us include 0x0A
        //     freely. We use this path for the entire bracketed-paste
        //     block (prefix + body + suffix) so the whole thing reaches
        //     the pane PTY as one contiguous byte sequence — tmux does
        //     not buffer between two `send-keys` calls, but using one
        //     command minimises the chance that the program reads a
        //     partial block.
        //
        // Single-line input still goes through the existing
        // [inputTokens] path so named keys (arrows, Escape, Tab,
        // BSpace) keep round-tripping as named keys — bracketed paste
        // would just confuse the receiving program for a one-liner.
        if (containsLineBreak(bytes)) {
            sendBracketedPaste(client, paneId, bytes)
            return
        }
        for (token in inputTokens(bytes)) {
            when (token) {
                is TmuxInputToken.Literal -> {
                    if (token.text.isNotEmpty()) {
                        client.sendCommand("send-keys -l -t $paneId -- '${escapeSingleQuoted(token.text)}'")
                    }
                }
                is TmuxInputToken.NamedKey -> {
                    client.sendCommand("send-keys -t $paneId ${token.name}")
                }
            }
        }
    }

    private suspend fun sendRawInputBytes(
        client: TmuxClient,
        paneId: String,
        bytes: ByteArray,
    ) {
        val hex = buildTmuxHex(bytes)
        if (hex.isEmpty()) return
        client.sendCommand("send-keys -H -t $paneId $hex")
    }

    /**
     * Issue #209: send [bytes] to [paneId] as a single bracketed-paste
     * block via `send-keys -H`.
     *
     * The hex payload is `1b 5b 32 30 30 7e` (`\e[200~`) + the UTF-8
     * bytes of the input + `1b 5b 32 30 31 7e` (`\e[201~`). `\r\n`
     * pairs are normalised to `\n` so the inner content uses LF only;
     * lone `\r` bytes are passed through (they are not paragraph
     * separators in dictation transcripts and we have no reason to
     * mangle them).
     *
     * Visible-for-test so the unit suite can exercise the encoding
     * without going through the suspending sendInputBytesToPane path.
     */
    private suspend fun sendBracketedPaste(
        client: TmuxClient,
        paneId: String,
        bytes: ByteArray,
    ) {
        val hex = buildBracketedPasteHex(bytes)
        if (hex.isEmpty()) return
        client.sendCommand("send-keys -H -t $paneId $hex")
    }

    private fun inputTokens(bytes: ByteArray): List<TmuxInputToken> {
        val text = String(bytes, Charsets.UTF_8)
        val tokens = mutableListOf<TmuxInputToken>()
        val literal = StringBuilder()

        fun flushLiteral() {
            if (literal.isNotEmpty()) {
                tokens += TmuxInputToken.Literal(literal.toString())
                literal.clear()
            }
        }

        var index = 0
        while (index < text.length) {
            val ch = text[index]
            when (ch) {
                '\r', '\n' -> {
                    flushLiteral()
                    tokens += TmuxInputToken.NamedKey("Enter")
                    if (ch == '\r' && text.getOrNull(index + 1) == '\n') index += 1
                }
                '\t' -> {
                    flushLiteral()
                    tokens += TmuxInputToken.NamedKey("Tab")
                }
                '\b', '\u007f' -> {
                    flushLiteral()
                    tokens += TmuxInputToken.NamedKey("BSpace")
                }
                '\u001b' -> {
                    flushLiteral()
                    val mapped = when {
                        text.startsWith("\u001b[A", index) -> "Up" to 2
                        text.startsWith("\u001b[B", index) -> "Down" to 2
                        text.startsWith("\u001b[C", index) -> "Right" to 2
                        text.startsWith("\u001b[D", index) -> "Left" to 2
                        else -> "Escape" to 0
                    }
                    tokens += TmuxInputToken.NamedKey(mapped.first)
                    index += mapped.second
                }
                else -> literal.append(ch)
            }
            index += 1
        }
        flushLiteral()
        return tokens
    }

    /**
     * Send a tmux named key to [paneId]. Used for keys without a literal
     * byte representation that round-trips through `send-keys` (the
     * vocabulary tmux understands here is `Enter`, `Tab`, `Escape`, `BSpace`,
     * `Up`, `Down`, `Left`, `Right`, plus the `C-<letter>` /
     * `M-<letter>` modifier prefixes for Ctrl / Alt).
     */
    public fun sendNamedKey(paneId: String, key: String) {
        val client = clientRef ?: return
        bridgeScope.launch {
            runCatching {
                client.sendCommand("send-keys -t $paneId $key")
            }
        }
    }

    /**
     * Cache the on-screen [TerminalView] grid dimensions reported by
     * Compose and report it to tmux's control-mode client.
     *
     * Issue #285 hard-cuts the manual prompt flow: the first non-zero
     * phone grid after attach, and every real grid change after that,
     * is sent through `refresh-client -C <cols>x<rows>`. A repeated
     * call with the same dimensions is a no-op so Compose layout churn
     * does not spam tmux.
     */
    public fun resizeRemotePty(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        if (columns == remoteColumns && rows == remoteRows) return
        remoteColumns = columns
        remoteRows = rows
        maybeRefreshControlClientSize()
    }

    private fun maybeRefreshControlClientSize() {
        val client = clientRef ?: return
        val target = activeTarget ?: return
        val cols = remoteColumns
        val rows = remoteRows
        if (cols <= 0 || rows <= 0) return
        if (cols == appliedControlClientColumns && rows == appliedControlClientRows) return
        val generation = ++controlClientSizeGeneration
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            controlClientSizeMessage(
                event = "tmux-client-size-known",
                target = target,
                columns = cols,
                rows = rows,
            ),
        )
        bridgeScope.launch {
            val shouldApplyPolicy = !windowSizePolicyAppliedForAttach
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                controlClientSizeMessage(
                    event = "tmux-refresh-client-size-start",
                    target = target,
                    columns = cols,
                    rows = rows,
                    detail = "setWindowSizeLatest=$shouldApplyPolicy",
                ),
            )
            val policyResult = if (shouldApplyPolicy) {
                runCatching { client.setWindowSizeLatest(target.sessionName) }
            } else {
                Result.success(null)
            }
            val refreshResult = runCatching { client.refreshClientSize(cols, rows) }
            if (clientRef !== client || activeTarget != target) return@launch
            if (controlClientSizeGeneration != generation) return@launch
            if (remoteColumns != cols || remoteRows != rows) return@launch
            val policyResponse = policyResult.getOrNull()
            if (policyResult.isFailure || policyResponse?.isError == true) {
                Log.w(
                    ISSUE_145_RECONNECT_TAG,
                    controlClientSizeMessage(
                        event = "tmux-window-size-policy-error",
                        target = target,
                        columns = cols,
                        rows = rows,
                        detail = tmuxCommandErrorDetail(policyResult.exceptionOrNull(), policyResponse),
                    ),
                )
            } else if (policyResponse != null) {
                windowSizePolicyAppliedForAttach = true
            }
            val refreshResponse = refreshResult.getOrNull()
            if (refreshResult.isFailure || refreshResponse?.isError == true) {
                Log.w(
                    ISSUE_145_RECONNECT_TAG,
                    controlClientSizeMessage(
                        event = "tmux-refresh-client-size-error",
                        target = target,
                        columns = cols,
                        rows = rows,
                        detail = tmuxCommandErrorDetail(refreshResult.exceptionOrNull(), refreshResponse),
                    ),
                )
                return@launch
            }
            appliedControlClientColumns = cols
            appliedControlClientRows = rows
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                controlClientSizeMessage(
                    event = "tmux-refresh-client-size-ok",
                    target = target,
                    columns = cols,
                    rows = rows,
                ),
            )
        }
    }

    private fun resetControlClientSizeForAttach() {
        controlClientSizeGeneration += 1
        appliedControlClientColumns = 0
        appliedControlClientRows = 0
        windowSizePolicyAppliedForAttach = false
    }

    private fun controlClientSizeMessage(
        event: String,
        target: ConnectionTarget,
        columns: Int,
        rows: Int,
        detail: String = "",
    ): String = buildString {
        append(event)
        append(" host=")
        append(target.host)
        append(" port=")
        append(target.port)
        append(" user=")
        append(target.user)
        append(" session=")
        append(target.sessionName)
        append(" cols=")
        append(columns)
        append(" rows=")
        append(rows)
        activeAttachMilestone?.let { milestone ->
            append(" attempt=")
            append(milestone.attempt)
            append(" elapsedMs=")
            append(SystemClock.elapsedRealtime() - milestone.startedAtMs)
        }
        if (detail.isNotBlank()) {
            append(' ')
            append(detail)
        }
    }

    private fun tmuxCommandErrorDetail(
        error: Throwable?,
        response: CommandResponse?,
    ): String =
        error?.let { "error=${it.javaClass.simpleName}:${it.message.orEmpty()}" }
            ?: response?.output
                ?.joinToString(separator = " ")
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let { "tmuxError=$it" }
            ?: "tmuxError=unknown"

    public fun createSession(
        name: String,
        startDirectory: String = DEFAULT_TMUX_START_DIRECTORY,
    ) {
        val creation = resolveTmuxSessionCreation(
            rawName = name,
            rawStartDirectory = startDirectory,
        )
        sendLifecycleCommand(
            "new-session -d -s '${escapeSingleQuoted(creation.sessionName)}' " +
                "-c '${escapeSingleQuoted(creation.startDirectory)}'",
        )
    }

    public fun renameCurrentSession(newName: String) {
        val target = activeTarget?.sessionName ?: return
        val trimmed = newName.trim()
        if (trimmed.isEmpty()) return
        sendLifecycleCommand(
            "rename-session -t '${escapeSingleQuoted(target)}' '${escapeSingleQuoted(trimmed)}'",
        )
    }

    public fun killCurrentSession() {
        val target = activeTarget?.sessionName ?: return
        sendLifecycleCommand("kill-session -t '${escapeSingleQuoted(target)}'")
    }

    public fun newWindow() {
        val target = activeTarget?.sessionName ?: return
        sendLifecycleCommand("new-window -t '${escapeSingleQuoted(target)}'")
    }

    public fun selectWindow(windowId: String) {
        if (windowId.isBlank()) return
        sendLifecycleCommand("select-window -t $windowId")
    }

    public fun renameWindow(windowId: String, newName: String) {
        val trimmed = newName.trim()
        if (windowId.isBlank() || trimmed.isEmpty()) return
        sendLifecycleCommand("rename-window -t $windowId '${escapeSingleQuoted(trimmed)}'")
    }

    /**
     * Kill the tmux window identified by [windowId] (e.g. `@5`) and let the
     * reconcile path refresh the WindowStrip once tmux acknowledges the
     * death.
     *
     * ## Why this looks different from [killCurrentSession] / [renameWindow]
     *
     * Per issue #188, the original implementation wrapped `sendCommand`
     * inside [sendLifecycleCommand]'s plain [runCatching] and never refreshed
     * after the kill. That gave the user two visible failure modes:
     *
     *  1. **Silent failures.** A transport error (closed client, dropped
     *     channel) or a tmux `%error` (window already gone, wrong target
     *     syntax, …) was swallowed — the user kept seeing the kill-target
     *     window pill in the WindowStrip with no indication of why their tap
     *     had no effect ("Kill window … but then it will not close it").
     *  2. **No deterministic refresh.** Even on a successful kill the
     *     WindowStrip relied on the `%window-close` event eventually
     *     reaching [onControlEvent] → [reconcilePanes]. If the event fired
     *     between our launch yielding and our subscriber installing — a
     *     race on a hot SharedFlow — we'd miss it entirely.
     *
     * The fix mirrors issue #168 for `kill-session`:
     *
     *  - Install an UNDISPATCHED subscriber on [TmuxClient.events] for the
     *    matching [ControlEvent.WindowClose] BEFORE issuing the kill, so we
     *    can never miss the notification.
     *  - On transport throw or tmux `%error` response, surface a
     *    user-facing [windowKillError] message and SKIP the refresh — a
     *    refresh would just re-render the still-alive window and look like
     *    the bug from #188.
     *  - On a successful kill, await the `%window-close` event (with a 2s
     *    fallback so a degenerate tmux can never wedge the UI) and then
     *    force a [reconcilePanes] round-trip even though the event-handler
     *    would have done one too — belt-and-braces against the
     *    "subscription installed but event already past" edge case under
     *    SharedFlow buffer pressure.
     *
     * Debug logging (`windowId` + `client.hashCode()`) covers the
     * "wrong client" hypothesis the same way it did for #168, so a future
     * triage of "kill silently does nothing" can grep logcat and confirm
     * the same client instance issued the kill and saw the event.
     */
    public fun killWindow(windowId: String) {
        if (windowId.isBlank()) return
        val client = clientRef ?: return
        val label = labelFor(windowId)
        bridgeScope.launch {
            val clientHash = System.identityHashCode(client)
            Log.i(
                ISSUE_188_DIAG_TAG,
                "kill-window-start windowId=$windowId label=$label clientHash=$clientHash",
            )

            // Subscribe to the deterministic post-kill notification BEFORE
            // sending the command so we don't miss the event in the race
            // window between sendCommand returning and our collector
            // installing. `events` is a hot SharedFlow — late subscription
            // would drop the notification we care about.
            //
            // `start = UNDISPATCHED` guarantees the inner coroutine runs
            // up to its first suspension point (the `events.first { … }`
            // collector install) before this launch call returns — same
            // pattern as the issue #168 kill-session fix.
            val eventDeferred = bridgeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(KILL_WINDOW_EVENT_WAIT_MS) {
                    client.events.first { event ->
                        event is ControlEvent.WindowClose && event.windowId == windowId
                    }
                }
                Unit
            }

            val sendResult = runCatching {
                client.sendCommand("kill-window -t $windowId")
            }
            val response = sendResult.getOrNull()
            val transportFailure = sendResult.exceptionOrNull()
            if (transportFailure != null) {
                eventDeferred.cancel()
                Log.w(
                    ISSUE_188_DIAG_TAG,
                    "kill-window-transport-failed windowId=$windowId label=$label " +
                        "clientHash=$clientHash err=${transportFailure.javaClass.simpleName}: " +
                        transportFailure.message,
                )
                _windowKillError.value = "Couldn't close $label: " +
                    (transportFailure.message ?: "transport error")
                return@launch
            }
            if (response != null && response.isError) {
                eventDeferred.cancel()
                val detail = response.output.joinToString(separator = " ").trim()
                Log.w(
                    ISSUE_188_DIAG_TAG,
                    "kill-window-tmux-error windowId=$windowId label=$label " +
                        "clientHash=$clientHash detail=$detail",
                )
                _windowKillError.value = "Couldn't close $label" +
                    if (detail.isNotEmpty()) ": $detail" else ""
                return@launch
            }

            // Wait up to KILL_WINDOW_EVENT_WAIT_MS for tmux's
            // `%window-close` notification. The reconcile path
            // (onControlEvent → reconcilePanes) will already run from the
            // event-loop side, but we also kick off an explicit
            // reconcilePanes() afterwards: SharedFlow with replay=0 means a
            // pathological scheduling order could deliver the event to
            // *this* collector while skipping the production
            // [eventsJob] subscriber, and we want the WindowStrip to
            // refresh deterministically either way.
            eventDeferred.join()
            Log.i(
                ISSUE_188_DIAG_TAG,
                "kill-window-refresh windowId=$windowId label=$label clientHash=$clientHash",
            )
            reconcilePanes()
        }
    }

    /**
     * Issue #188: clear the kill-window error banner. Wired to the
     * screen's banner dismiss action.
     */
    public fun clearWindowKillError() {
        _windowKillError.value = null
    }

    /**
     * Issue #188: derive the human-readable label for [windowId] using the
     * same 1-based indexing rule [com.pocketshell.app.tmux.toWindowSummaries]
     * applies, so the error banner reads as "Window 2" rather than
     * the raw tmux `@5`. Falls back to the raw id when the panes list
     * doesn't yet know about [windowId] (e.g. kill issued before the
     * first reconcile completed).
     */
    private fun labelFor(windowId: String): String {
        val ordered = _panes.value
            .map { it.windowId }
            .distinct()
        val index = ordered.indexOf(windowId)
        return if (index >= 0) "Window ${index + 1}" else windowId
    }

    private fun sendLifecycleCommand(command: String) {
        val client = clientRef ?: return
        bridgeScope.launch {
            runCatching {
                client.sendCommand(command)
            }
        }
    }

    /**
     * Translate a [com.pocketshell.app.session.SessionViewModel]-style
     * key-bar label (`Esc`, `Tab`, `‹`, `⌃`, ...) into a tmux `send-keys`
     * named-key argument, then dispatch it.
     *
     * Mirrors the byte-level mapping in `SessionViewModel.unmodifiedBytesFor`
     * — we map to tmux's named-key vocabulary rather than the literal
     * escape sequence because `send-keys` understands them directly and
     * because tmux owns the per-pane terminfo, which means letting tmux
     * choose the cursor-key encoding is more correct than us baking
     * ESC[A in here.
     */
    public fun onKeyBarKey(paneId: String, label: String) {
        val named = when (label) {
            "Esc" -> "Escape"
            "Tab" -> "Tab"
            "Ctrl-C" -> {
                sendControlInputToPane(paneId, CtrlCByte)
                null
            }
            "Ctrl-D" -> {
                sendControlInputToPane(paneId, CtrlDByte)
                null
            }
            "‹", "Left" -> "Left"
            "⌃", "Up" -> "Up"
            "⌄", "Down" -> "Down"
            "›", "Right" -> "Right"
            else -> null
        }
        if (named != null) {
            sendNamedKey(paneId, named)
        }
    }

    /** Issue #285 test seam: snapshot the latest phone grid reported by Compose. */
    internal fun remoteDimensionsForTest(): Pair<Int, Int> = remoteColumns to remoteRows

    /**
     * Quote a string for inclusion inside single quotes in a tmux command
     * line. tmux's command parser uses POSIX-shell-ish single quoting:
     * everything between the outer pair of `'...'` is literal except the
     * `'` character itself, which must be closed and re-opened
     * (`'\''`). We replace single quotes with the close-escape-open
     * sequence and leave everything else alone.
     */
    internal fun escapeSingleQuoted(input: String): String =
        input.replace("'", "'\\''")

    /**
     * Issue #209 test seam: drive the bracketed-paste hex builder
     * without going through the suspending tmux client. Returns the
     * exact hex payload that [sendBracketedPaste] would pass to
     * `send-keys -H`. Test-only.
     */
    internal fun buildBracketedPasteHexForTest(bytes: ByteArray): String =
        buildBracketedPasteHex(bytes)

    /**
     * Issue #209 test seam: predicate the production [sendInputBytesToPane]
     * uses to decide between the named-key path and the bracketed-paste
     * path. Test-only.
     */
    internal fun containsLineBreakForTest(bytes: ByteArray): Boolean =
        containsLineBreak(bytes)

    internal fun tmuxInputMetricsForTest(paneId: String): TmuxInputStressMetrics? =
        paneInputQueues[paneId]?.snapshot()

    internal fun tmuxInputSinkForTest(paneId: String): OutputStream =
        inputSinkForPane(paneId)

    internal fun tmuxInputCapacityBytesForTest(): Int = TMUX_INPUT_MAX_PENDING_BYTES

    override fun onCleared() {
        connectJob?.cancel()
        connectJob = null
        assistant.dismiss()
        closeCurrentConnection()
        // Issue #235: remove the application-scoped lifecycle hooks
        // installed during the first attach. The hooks survive across
        // [closeCurrentConnection*] teardown cycles (otherwise the
        // auto-detach on background would unregister the hook before
        // the foreground hook fires the reattach); the VM going away
        // is the only signal we use to drop them.
        lifecycleHookHostId?.let { activeTmuxClients.unregisterLifecycleHooks(it) }
        lifecycleHookHostId = null
        // bridgeScope is parented to viewModelScope, so its SupervisorJob
        // tears down automatically when viewModelScope cancels post-super
        // call. Explicit cancellation here is redundant — leaving it to
        // the framework keeps the teardown path single-sourced.
        super.onCleared()
    }

    /**
     * Suspending teardown of the current SSH/tmux connection.
     *
     * Issue #151: the previous synchronous `closeCurrentConnection()` was
     * called from `connect()` while the event-loop coroutine subscribed to
     * `TmuxClient.events` was still alive (and frequently mid-processing a
     * `ControlEvent`). Tearing down the SSH transport from one thread while
     * another was mid-write produced `TransportException [BY_APPLICATION]`
     * crashes on real devices. This entry point `cancelAndJoin`s the
     * event-loop job FIRST, so by the time we touch `clientRef.close()` /
     * `sessionRef.close()` no other coroutine is talking to the transport.
     *
     * Must be called from a coroutine that owns whatever
     * exception-propagation policy the caller needs (the production caller
     * wraps this in `NonCancellable`; tests may not need to).
     */
    private suspend fun closeCurrentConnectionAndJoin(
        preserveConnectingTarget: ConnectionTarget? = null,
    ) {
        // Issue #257: drain any background detach left in flight by a
        // prior same-host fast-switch before we tear the rest down, so a
        // full teardown (background-detach / cross-host reconnect) never
        // races a lingering `detach-client` from the previous switch.
        orphanDetachJob?.cancelAndJoin()
        orphanDetachJob = null
        eventsJob?.cancelAndJoin()
        eventsJob = null
        projectRootsJob?.cancelAndJoin()
        projectRootsJob = null
        _projectRoots.value = emptyList()
        val producerJobsToJoin = paneProducerJobs.values.toList()
        val agentJobsToJoin = paneAgentJobs.values.toList()
        val inputJobsToJoin = paneInputJobs.values.toList()
        for (job in producerJobsToJoin) job.cancelAndJoin()
        for (job in agentJobsToJoin) job.cancelAndJoin()
        for (job in inputJobsToJoin) job.cancelAndJoin()
        for ((_, queue) in paneInputQueues) {
            queue.close()
        }
        paneAgentJobs.clear()
        paneAgentInputs.clear()
        paneInputJobs.clear()
        paneInputQueues.clear()
        _agentConversations.value = emptyMap()
        paneProducerJobs.clear()
        for ((_, row) in paneRows) {
            runCatching { row.terminalState.detachExternalProducer() }
        }
        paneRows.clear()
        _panes.value = emptyList()
        // Issue #215: ask tmux to detach this `-CC` control client before
        // we drop the SSH transport. Without the detach round-trip,
        // tmux keeps the client listed in `tmux list-clients` until it
        // independently observes the socket die — which on Android (a
        // backgrounded app + a phone-side TCP close) can lag long
        // enough that another client (`tmux attach` from a laptop)
        // attaches alongside the orphan and finds the session in a
        // state where input is being routed to the dead control
        // client. [TmuxClient.detachCleanly] sends `detach-client`,
        // waits for tmux to close the control channel, then calls
        // [TmuxClient.close] unconditionally so a wedged server cannot
        // block the teardown. The `runCatching` is belt-and-suspenders
        // — the implementation already swallows transport drops mid-
        // detach.
        runCatching { clientRef?.detachCleanly() }
        clientRef = null
        registeredHostId?.let { activeTmuxClients.unregister(it) }
        registeredHostId = null
        // `RealSshSession.close()` now swallows the already-disconnected
        // `BY_APPLICATION` TransportException (#151), but we keep the
        // `runCatching` here too because the session may be a non-real
        // implementation in tests.
        runCatching { sessionRef?.close() }
        sessionRef = null
        activeTarget = null
        connectingTarget = connectingTarget?.takeIf { current ->
            preserveConnectingTarget?.let { sameSessionIdentity(current, it) } == true
        }
        refreshReconnectAvailability()
        activeAttachMilestone = null
        // A fresh attach must capture a fresh phone grid and re-run the
        // size-mismatch check.
        remoteColumns = 0
        remoteRows = 0
        resetControlClientSizeForAttach()
    }

    /**
     * Issue #178: tear down everything tied to the **tmux client** but
     * keep the underlying [SshSession] alive so the fast-switch path can
     * reuse it for a new [TmuxClient].
     *
     * Mirrors [closeCurrentConnectionAndJoin] structurally — same
     * cancel-and-join ordering for the event loop and per-pane jobs to
     * preserve the issue #151 race-fix — but stops short of
     * `sessionRef.close()`. We deliberately ALSO leave [sessionRef]
     * populated; the caller ([runFastSessionSwitch]) consumes it
     * directly. We DO clear [activeTarget] / [registeredHostId] /
     * `remoteColumns`/`remoteRows` because the new tmux client will
     * register a fresh entry, take ownership of the next resize, and
     * needs a clean slate — same lifecycle reset the slow path uses.
     *
     * Bridge / pane / agent / input scratch state is cleared identically
     * to the slow path: the new tmux session will report its own panes
     * via `%window-add` and the bootstrap `list-panes`, so retaining
     * stale per-pane jobs from the previous session would only leak
     * coroutines onto the now-dead pane IDs.
     */
    private suspend fun closeCurrentClientKeepSession() {
        eventsJob?.cancelAndJoin()
        eventsJob = null
        projectRootsJob?.cancelAndJoin()
        projectRootsJob = null
        _projectRoots.value = emptyList()
        val producerJobsToJoin = paneProducerJobs.values.toList()
        val agentJobsToJoin = paneAgentJobs.values.toList()
        val inputJobsToJoin = paneInputJobs.values.toList()
        for (job in producerJobsToJoin) job.cancelAndJoin()
        for (job in agentJobsToJoin) job.cancelAndJoin()
        for (job in inputJobsToJoin) job.cancelAndJoin()
        for ((_, queue) in paneInputQueues) {
            queue.close()
        }
        paneAgentJobs.clear()
        paneAgentInputs.clear()
        paneInputJobs.clear()
        paneInputQueues.clear()
        _agentConversations.value = emptyMap()
        paneProducerJobs.clear()
        for ((_, row) in paneRows) {
            runCatching { row.terminalState.detachExternalProducer() }
        }
        paneRows.clear()
        _panes.value = emptyList()
        // Close the tmux -CC channel. This tears down only the SSH
        // shell channel inside the live [SshSession] — the session
        // itself stays open for the next [TmuxClient].
        //
        // Issue #215: the same orphan-control-client problem the slow
        // teardown path fixes applies here too — when a same-host
        // session switch tears down the previous tmux `-CC` channel
        // without notifying tmux, the previous client lingers in
        // `tmux list-clients` for the previous session until tmux
        // notices the SSH shell channel close on its own. Sending
        // `detach-client` first removes that delay window and matches
        // the slow path's contract for "PocketShell promises to leave
        // a clean server when it tears a tmux client down".
        //
        // Issue #257 (perf): the previous version `await`ed
        // [TmuxClient.detachCleanly] here, putting up to a 1s
        // `detach-client` + reader-EOF round-trip directly on the
        // session-switch critical path — the new session could not
        // start attaching until the OLD client had finished detaching.
        // Each tmux `-CC` client owns its own SSH shell channel
        // ([SshSession.startShell] opens a fresh channel and closing one
        // leaves the parent session + sibling channels alive), so the
        // new client's [TmuxClient.connect] can open its channel
        // immediately while the old client's clean detach drains in the
        // background. We launch the detach fire-and-forget on
        // [viewModelScope] (cancelled with the VM in [onCleared]) and
        // null [clientRef] right away so [runFastSessionSwitch] proceeds
        // without waiting. The #215 clean-server contract is preserved —
        // the detach still runs, just not blocking the user's switch.
        val previousClient = clientRef
        clientRef = null
        orphanDetachJob?.cancel()
        orphanDetachJob = previousClient?.let { client ->
            viewModelScope.launch {
                withContext(NonCancellable) {
                    runCatching { client.detachCleanly() }
                }
            }
        }
        registeredHostId?.let { activeTmuxClients.unregister(it) }
        registeredHostId = null
        // Intentionally NOT touching [sessionRef] — that is the
        // contract of this method. The caller will pass the same
        // session to the next tmux client.
        activeTarget = null
        activeAttachMilestone = null
        // Keep [connectingTarget] — connect() set it to the new
        // target before invoking us; clearing it here would lose the
        // intent.
        // A fresh attach must capture a fresh phone grid and re-run the
        // size-mismatch check.
        remoteColumns = 0
        remoteRows = 0
        resetControlClientSizeForAttach()
    }

    /**
     * Non-suspending teardown used by `onCleared()` and the synchronous
     * test-replacement seam. Cancels the event-loop coroutine (fire and
     * forget) and then tears the rest of the state down sync. Production
     * callers that need the cancel-and-join ordering — most importantly
     * `connect()`, which raced the event loop on real devices (#151) —
     * must use [closeCurrentConnectionAndJoin] instead.
     *
     * Issue #215: this path is what runs when the user finishes the
     * activity (back press, system-driven destroy) and the ViewModel's
     * `onCleared()` fires on the Main thread. Without an active detach
     * the tmux `-CC` control client stays registered server-side until
     * tmux independently notices the SSH socket drop, which on Android
     * is racy (Activity-finish → SSH transport close → kernel FIN can
     * take several seconds). Other clients attaching in the gap (e.g.
     * a laptop running `tmux attach`) see a session with a still-active
     * orphan client and have their input routed to a dead pipe.
     *
     * To keep the AutoCloseable-style `close()` contract while still
     * notifying tmux server-side, we send `detach-client` via a brief
     * `runBlocking(Dispatchers.IO)` hop — same pattern
     * [com.pocketshell.core.ssh.RealSshSession.close] uses for its
     * `SSH_MSG_DISCONNECT` write. The blocking call is bounded by
     * [SYNC_DETACH_TIMEOUT_MS] so a wedged socket cannot stall the
     * activity teardown beyond a fraction of a second.
     */
    private fun closeCurrentConnection() {
        // Issue #257: cancel any in-flight background detach from a prior
        // fast-switch. This path runs from [onCleared] (and the sync test
        // seam), where [viewModelScope] is about to be cancelled anyway;
        // the explicit cancel keeps the state field tidy.
        orphanDetachJob?.cancel()
        orphanDetachJob = null
        eventsJob?.cancel()
        eventsJob = null
        projectRootsJob?.cancel()
        projectRootsJob = null
        _projectRoots.value = emptyList()
        activeAttachMilestone = null
        for ((_, job) in paneProducerJobs) {
            job.cancel()
        }
        for ((_, job) in paneAgentJobs) {
            job.cancel()
        }
        for ((_, job) in paneInputJobs) {
            job.cancel()
        }
        for ((_, queue) in paneInputQueues) {
            queue.close()
        }
        paneAgentJobs.clear()
        paneAgentInputs.clear()
        paneInputJobs.clear()
        paneInputQueues.clear()
        _agentConversations.value = emptyMap()
        paneProducerJobs.clear()
        for ((_, row) in paneRows) {
            runCatching { row.terminalState.detachExternalProducer() }
        }
        paneRows.clear()
        _panes.value = emptyList()
        // Issue #215: run `detach-client` synchronously over an IO
        // worker before the local close. The `runBlocking` hop matches
        // [RealSshSession.close]'s pattern for non-suspending lifecycle
        // teardown that needs a brief network round-trip. The timeout
        // ceiling keeps the activity-destroy / onCleared path bounded
        // — losing the wire mid-detach falls through to the immediate
        // [TmuxClient.close] below.
        val toDetach = clientRef
        if (toDetach != null) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(SYNC_DETACH_TIMEOUT_MS) {
                        toDetach.detachCleanly(timeoutMs = SYNC_DETACH_TIMEOUT_MS)
                    }
                }
            }
        }
        // [detachCleanly] already invokes [close]; the call below is a
        // belt-and-suspenders no-op when detachCleanly ran successfully
        // and the real teardown path when the runBlocking hop above
        // failed or was a no-op (clientRef was null).
        runCatching { clientRef?.close() }
        clientRef = null
        registeredHostId?.let { activeTmuxClients.unregister(it) }
        registeredHostId = null
        runCatching { sessionRef?.close() }
        sessionRef = null
        activeTarget = null
        connectingTarget = null
        // Issue #145: a sync teardown (onCleared / test-replacement seam)
        // clears the reconnect availability flag so the screen never
        // re-renders the Reconnect button against a stale target.
        refreshReconnectAvailability()
        // A fresh attach must capture a fresh phone grid and re-run the
        // size-mismatch check.
        remoteColumns = 0
        remoteRows = 0
        resetControlClientSizeForAttach()
    }

    /**
     * Parse one row from `list-panes -F ...` output into a
     * [ParsedPane]. Returns null if the row is malformed — we tolerate a
     * trailing blank line or a tmux version that surfaces fewer fields
     * than the format string requested.
     */
    private fun parsePaneRow(line: String): ParsedPane? {
        val parts = if (LIST_PANES_FIELD_SEPARATOR in line) {
            line.split(LIST_PANES_FIELD_SEPARATOR)
        } else {
            line.split('\t')
        }
        if (parts.size < 5) return null
        val paneId = parts[0].takeIf { it.startsWith("%") } ?: return null
        val windowId = parts[1].takeIf { it.startsWith("@") } ?: return null
        val sessionId = parts[2].takeIf { it.startsWith("$") } ?: return null
        val hasSessionName = parts.size >= 6
        val sessionName = if (hasSessionName) parts[3] else ""
        val title = if (hasSessionName) parts[4] else parts[3]
        val paneIndex = parts[if (hasSessionName) 5 else 4].trim().toIntOrNull() ?: 0
        return ParsedPane(
            paneId = paneId,
            windowId = windowId,
            sessionId = sessionId,
            title = title,
            paneIndex = paneIndex,
            cwd = parts.getOrNull(if (hasSessionName) 6 else 5).orEmpty(),
            currentCommand = parts.getOrNull(if (hasSessionName) 7 else 6).orEmpty(),
            // Issue #186: `#{pane_tty}` is the 9th (or 8th, without
            // session_name) field in the format string above. Older
            // tmux versions that omit the field simply return empty,
            // in which case per-pane agent detection skips this pane
            // (see [detectForPane]) rather than fall back to a
            // host-wide scan.
            paneTty = parts.getOrNull(if (hasSessionName) 8 else 7).orEmpty(),
            sessionName = sessionName,
        )
    }

    /**
     * Internal value type used by the reconcile path. Visible to tests so
     * they can drive [applyParsedPanesForTest] without round-tripping the
     * format string.
     */
    internal data class ParsedPane(
        val paneId: String,
        val windowId: String,
        val sessionId: String,
        val title: String,
        val paneIndex: Int,
        val cwd: String = "",
        val currentCommand: String = "",
        // Issue #186: per-pane TTY captured from `#{pane_tty}` so
        // detection can scope its process scan to a specific pane.
        // Default is empty (older tmux / unit tests that don't care).
        val paneTty: String = "",
        val sessionName: String = "",
    )

    private data class ConnectionTarget(
        val hostId: Long,
        val hostName: String,
        val host: String,
        val port: Int,
        val user: String,
        val keyPath: String,
        val passphrase: CharArray?,
        val sessionName: String,
        val startDirectory: String?,
    )

    private data class ConnectIntent(
        val target: ConnectionTarget,
        val trigger: TmuxConnectTrigger,
        val generation: Long,
    )

    private data class PendingReattach(
        val target: ConnectionTarget,
        val generation: Long,
        val intendedTarget: ConnectionTarget?,
        val intendedTrigger: TmuxConnectTrigger?,
    )

    private data class AttachMilestone(
        val attempt: Int,
        val sessionName: String,
        val startedAtMs: Long,
        val trigger: TmuxConnectTrigger,
        var firstPaneOutputLogged: Boolean = false,
    )

    private sealed interface PaneReconcileResult {
        data class Ready(val paneCount: Int) : PaneReconcileResult
        data class Failed(val cause: Throwable) : PaneReconcileResult
        data object NoClient : PaneReconcileResult
    }

    private class TmuxAttachPanesReadyException(
        message: String,
        cause: Throwable? = null,
    ) : RuntimeException(message, cause)

    private sealed interface TmuxInputToken {
        data class Literal(val text: String) : TmuxInputToken
        data class NamedKey(val name: String) : TmuxInputToken
    }

    private class TmuxPaneInputStream(
        private val queue: TmuxPaneInputQueue,
    ) : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            queue.write(buffer, offset, length)
        }

        override fun close() {
            queue.close()
        }
    }

    /** Coarse-grained connection state. Mirrors `SessionViewModel.ConnectionStatus`. */
    public sealed interface ConnectionStatus {
        public object Idle : ConnectionStatus
        public data class Connecting(val host: String, val port: Int, val user: String) :
            ConnectionStatus
        public data class Connected(val host: String, val port: Int, val user: String) :
            ConnectionStatus
        public data class Failed(val message: String) : ConnectionStatus
    }

}

private fun boundedDistinctEvents(events: List<ConversationEvent>): List<ConversationEvent> =
    reconcileAgentEvents(events, maxEvents = MaxAgentEvents)

internal data class TmuxInputStressMetrics(
    val totalEnqueuedBytes: Long,
    val totalSentBytes: Long,
    val maxPendingBytes: Int,
    val maxPendingChunks: Int,
    val maxBatchBytes: Int,
    val maxBatchChunks: Int,
    val sentBatchCount: Long,
    val maxSendLatencyMs: Double,
)

private data class TmuxPaneInputSegment(
    val bytes: ByteArray,
    val enqueuedAtNs: Long,
)

private data class TmuxPaneInputBatch(
    val bytes: ByteArray,
    val chunks: Int,
    val firstEnqueuedAtNs: Long,
)

private class TmuxPaneInputQueue(
    private val maxPendingBytes: Int,
    private val maxBatchBytes: Int,
) {
    private val channel = Channel<TmuxPaneInputSegment>(TMUX_INPUT_MAX_PENDING_CHUNKS)
    private val lock = Any()
    private var pendingBytes: Int = 0
    private var totalEnqueuedBytes: Long = 0L
    private var totalSentBytes: Long = 0L
    private var maxObservedPendingBytes: Int = 0
    private var maxObservedPendingChunks: Int = 0
    private var maxObservedBatchBytes: Int = 0
    private var maxObservedBatchChunks: Int = 0
    private var sentBatchCount: Long = 0L
    private var maxObservedSendLatencyNs: Long = 0L

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        var written = 0
        while (written < length) {
            val chunkLength = minOf(maxPendingBytes, TMUX_INPUT_CHUNK_BYTES, length - written)
            val copy = buffer.copyOfRange(offset + written, offset + written + chunkLength)
            enqueue(copy)
            written += chunkLength
        }
    }

    suspend fun takeBatch(): TmuxPaneInputBatch? {
        val first = channel.receiveCatching().getOrNull() ?: return null
        onDequeued(first.bytes.size)
        val out = java.io.ByteArrayOutputStream(maxBatchBytes)
        out.write(first.bytes)
        var chunks = 1
        val firstEnqueuedAtNs = first.enqueuedAtNs

        while (out.size() + TMUX_INPUT_CHUNK_BYTES <= maxBatchBytes) {
            val next = channel.tryReceive().getOrNull() ?: break
            onDequeued(next.bytes.size)
            out.write(next.bytes)
            chunks += 1
        }
        return TmuxPaneInputBatch(
            bytes = out.toByteArray(),
            chunks = chunks,
            firstEnqueuedAtNs = firstEnqueuedAtNs,
        )
    }

    fun recordSent(batch: TmuxPaneInputBatch, @Suppress("UNUSED_PARAMETER") sendDurationNs: Long) = synchronized(lock) {
        totalSentBytes += batch.bytes.size.toLong()
        sentBatchCount += 1
        maxObservedBatchBytes = maxOf(maxObservedBatchBytes, batch.bytes.size)
        maxObservedBatchChunks = maxOf(maxObservedBatchChunks, batch.chunks)
        val latencyNs = System.nanoTime() - batch.firstEnqueuedAtNs
        maxObservedSendLatencyNs = maxOf(maxObservedSendLatencyNs, latencyNs)
    }

    fun snapshot(): TmuxInputStressMetrics = synchronized(lock) {
        TmuxInputStressMetrics(
            totalEnqueuedBytes = totalEnqueuedBytes,
            totalSentBytes = totalSentBytes,
            maxPendingBytes = maxObservedPendingBytes,
            maxPendingChunks = maxObservedPendingChunks,
            maxBatchBytes = maxObservedBatchBytes,
            maxBatchChunks = maxObservedBatchChunks,
            sentBatchCount = sentBatchCount,
            maxSendLatencyMs = maxObservedSendLatencyNs.toDouble() / 1_000_000.0,
        )
    }

    fun close() {
        channel.close()
    }

    private fun enqueue(bytes: ByteArray) {
        val segment = TmuxPaneInputSegment(bytes, System.nanoTime())
        synchronized(lock) {
            pendingBytes += bytes.size
            totalEnqueuedBytes += bytes.size.toLong()
            maxObservedPendingBytes = maxOf(maxObservedPendingBytes, pendingBytes)
            maxObservedPendingChunks = maxOf(
                maxObservedPendingChunks,
                (pendingBytes + maxBatchBytes - 1) / maxBatchBytes,
            )
        }
        val result = channel.trySendBlocking(segment)
        if (result.isFailure) {
            onDequeued(bytes.size)
            throw IOException("tmux pane input queue is closed")
        }
    }

    private fun onDequeued(bytes: Int) = synchronized(lock) {
        pendingBytes -= bytes
    }

}

/**
 * Issue #209: true when [bytes] contains a `\n` (0x0A) byte. Used by
 * [TmuxSessionViewModel.sendInputBytesToPane] to gate the bracketed-
 * paste wrapping path. We only check for LF — a lone `\r` (0x0D) is not
 * a paragraph separator in dictation transcripts, and the
 * input-tokenisation path already routes both `\r` and `\n` to a tmux
 * `Enter` named key when they appear in single-line input.
 */
internal fun containsLineBreak(bytes: ByteArray): Boolean {
    for (b in bytes) if (b == 0x0A.toByte()) return true
    return false
}

/**
 * Issue #209: build the hex payload for a tmux `send-keys -H` call that
 * delivers [bytes] wrapped in bracketed-paste markers.
 *
 * The output is a space-separated list of two-character lowercase hex
 * pairs (e.g. `1b 5b 32 30 30 7e 61 0a 62 1b 5b 32 30 31 7e` for
 * `<ESC>[200~a\nb<ESC>[201~`). The prefix is the 6 bytes of `\e[200~`,
 * the suffix is the 6 bytes of `\e[201~`, and the body is the UTF-8
 * representation of the input with `\r\n` pairs normalised to `\n`.
 * Returns an empty string for empty input (the caller skips the
 * `send-keys -H` invocation entirely in that case).
 *
 * Normalisation of `\r\n` -> `\n` keeps the receiving program from
 * seeing a doubled paragraph break when the source platform happens to
 * emit Windows line endings (Whisper transcripts on Android use `\n`;
 * the normalisation is defensive against shares from other apps).
 */
internal fun buildBracketedPasteHex(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val normalised = normaliseLineEndingsForPaste(bytes)
    val builder = StringBuilder(2 * (PASTE_START.size + normalised.size + PASTE_END.size) + 32)
    appendHex(builder, PASTE_START)
    appendHex(builder, normalised)
    appendHex(builder, PASTE_END)
    return builder.toString()
}

internal fun buildTmuxHex(bytes: ByteArray): String {
    if (bytes.isEmpty()) return ""
    val builder = StringBuilder(3 * bytes.size)
    appendHex(builder, bytes)
    return builder.toString()
}

/**
 * Issue #243: true when [bytes] contains only complete terminal emulator
 * report sequences that should be forwarded to the remote pane as raw
 * bytes. These are local replies to remote terminal queries, not typed
 * user text: OSC color/title reports, DCS capability reports, CSI
 * device/status/window reports, and SGR mouse reports.
 */
internal fun isTerminalGeneratedResponse(bytes: ByteArray): Boolean {
    if (bytes.isEmpty()) return false
    var index = 0
    while (index < bytes.size) {
        val next = consumeTerminalResponse(bytes, index)
        if (next <= index) return false
        index = next
    }
    return true
}

private fun consumeTerminalResponse(bytes: ByteArray, start: Int): Int {
    if (bytes.getOrNull(start) != ESC) return -1
    return when (bytes.getOrNull(start + 1)) {
        CSI -> consumeCsiTerminalResponse(bytes, start + 2)
        OSC -> consumeOscTerminalResponse(bytes, start + 2)
        DCS -> consumeDcsTerminalResponse(bytes, start + 2)
        else -> -1
    }
}

private fun consumeCsiTerminalResponse(bytes: ByteArray, start: Int): Int {
    var index = start
    val prefix = when (bytes.getOrNull(index)) {
        QUESTION, GREATER_THAN, LESS_THAN -> {
            val value = bytes[index]
            index += 1
            value
        }
        else -> null
    }
    var sawDigit = false
    while (index < bytes.size) {
        val b = bytes[index]
        when {
            isDigitByte(b) -> {
                sawDigit = true
                index += 1
            }
            b == SEMICOLON -> index += 1
            b == DOLLAR -> {
                return if (bytes.getOrNull(index + 1) == LOWER_Y && sawDigit) index + 2 else -1
            }
            isCsiTerminalResponseFinal(prefix, b, sawDigit) -> return index + 1
            else -> return -1
        }
    }
    return -1
}

private fun isCsiTerminalResponseFinal(prefix: Byte?, final: Byte, sawDigit: Boolean): Boolean =
    when (final) {
        LOWER_C -> sawDigit && (prefix == QUESTION || prefix == GREATER_THAN)
        LOWER_N, UPPER_R, LOWER_T -> sawDigit && prefix == null
        UPPER_M, LOWER_M -> sawDigit && prefix == LESS_THAN
        else -> false
    }

private fun consumeOscTerminalResponse(bytes: ByteArray, start: Int): Int {
    val end = findStringTerminator(bytes, start)
    if (end <= start) return -1
    return if (isKnownTerminalOscResponse(bytes, start, end)) {
        terminatorEnd(bytes, end)
    } else {
        -1
    }
}

private fun isKnownTerminalOscResponse(bytes: ByteArray, start: Int, end: Int): Boolean {
    if (bytes.getOrNull(start) == UPPER_L || bytes.getOrNull(start) == LOWER_L) return true

    var index = start
    var sawDigit = false
    while (index < end && isDigitByte(bytes[index])) {
        sawDigit = true
        index += 1
    }
    if (!sawDigit || bytes.getOrNull(index) != SEMICOLON) return false
    index += 1

    val rgbPrefix = byteArrayOf(LOWER_R, LOWER_G, LOWER_B, COLON)
    if (index + rgbPrefix.size > end) return false
    for (offset in rgbPrefix.indices) {
        if (bytes[index + offset] != rgbPrefix[offset]) return false
    }
    return true
}

private fun consumeDcsTerminalResponse(bytes: ByteArray, start: Int): Int {
    val end = findStringTerminator(bytes, start)
    if (end <= start) return -1
    return if (isKnownTerminalDcsResponse(bytes, start, end)) {
        terminatorEnd(bytes, end)
    } else {
        -1
    }
}

private fun isKnownTerminalDcsResponse(bytes: ByteArray, start: Int, end: Int): Boolean {
    if (end - start < 3) return false
    return (bytes[start] == DIGIT_ONE && bytes[start + 1] == DOLLAR && bytes[start + 2] == LOWER_R) ||
        ((bytes[start] == DIGIT_ZERO || bytes[start] == DIGIT_ONE) &&
            bytes[start + 1] == PLUS &&
            bytes[start + 2] == LOWER_R)
}

private fun findStringTerminator(bytes: ByteArray, start: Int): Int {
    var index = start
    while (index < bytes.size) {
        when (bytes[index]) {
            BEL -> return index
            ESC -> {
                if (bytes.getOrNull(index + 1) == BACKSLASH) return index
            }
        }
        index += 1
    }
    return -1
}

private fun terminatorEnd(bytes: ByteArray, terminatorStart: Int): Int =
    if (bytes[terminatorStart] == BEL) terminatorStart + 1 else terminatorStart + 2

private fun isDigitByte(byte: Byte): Boolean = byte >= DIGIT_ZERO && byte <= DIGIT_NINE

private fun normaliseLineEndingsForPaste(bytes: ByteArray): ByteArray {
    // Fast path: no `\r` at all.
    var sawCr = false
    for (b in bytes) if (b == 0x0D.toByte()) { sawCr = true; break }
    if (!sawCr) return bytes
    val out = java.io.ByteArrayOutputStream(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val b = bytes[i]
        if (b == 0x0D.toByte() && i + 1 < bytes.size && bytes[i + 1] == 0x0A.toByte()) {
            // \r\n -> \n
            out.write(0x0A)
            i += 2
        } else {
            out.write(b.toInt() and 0xFF)
            i += 1
        }
    }
    return out.toByteArray()
}

private fun appendHex(builder: StringBuilder, bytes: ByteArray) {
    for (b in bytes) {
        if (builder.isNotEmpty() && builder.last() != ' ') builder.append(' ')
        val v = b.toInt() and 0xFF
        builder.append(HEX_DIGITS[(v ushr 4) and 0xF])
        builder.append(HEX_DIGITS[v and 0xF])
    }
}

private val PASTE_START: ByteArray = byteArrayOf(0x1B, 0x5B, 0x32, 0x30, 0x30, 0x7E)
private val PASTE_END: ByteArray = byteArrayOf(0x1B, 0x5B, 0x32, 0x30, 0x31, 0x7E)
private val HEX_DIGITS: CharArray = "0123456789abcdef".toCharArray()
private val ESC: Byte = 0x1B
private val BEL: Byte = 0x07
private val CSI: Byte = '['.code.toByte()
private val OSC: Byte = ']'.code.toByte()
private val DCS: Byte = 'P'.code.toByte()
private val BACKSLASH: Byte = '\\'.code.toByte()
private val QUESTION: Byte = '?'.code.toByte()
private val GREATER_THAN: Byte = '>'.code.toByte()
private val LESS_THAN: Byte = '<'.code.toByte()
private val SEMICOLON: Byte = ';'.code.toByte()
private val COLON: Byte = ':'.code.toByte()
private val DOLLAR: Byte = '$'.code.toByte()
private val PLUS: Byte = '+'.code.toByte()
private val DIGIT_ZERO: Byte = '0'.code.toByte()
private val DIGIT_ONE: Byte = '1'.code.toByte()
private val DIGIT_NINE: Byte = '9'.code.toByte()
private val LOWER_B: Byte = 'b'.code.toByte()
private val LOWER_C: Byte = 'c'.code.toByte()
private val LOWER_G: Byte = 'g'.code.toByte()
private val LOWER_L: Byte = 'l'.code.toByte()
private val LOWER_M: Byte = 'm'.code.toByte()
private val LOWER_N: Byte = 'n'.code.toByte()
private val LOWER_R: Byte = 'r'.code.toByte()
private val LOWER_T: Byte = 't'.code.toByte()
private val LOWER_Y: Byte = 'y'.code.toByte()
private val UPPER_L: Byte = 'L'.code.toByte()
private val UPPER_M: Byte = 'M'.code.toByte()
private val UPPER_R: Byte = 'R'.code.toByte()

/**
 * Issue #259: the live cursor position of a pane, in the pane's *visible*
 * (viewport) coordinate space, 0-based, as reported by tmux
 * `display-message -p '#{cursor_x},#{cursor_y}'`.
 *
 * This is the piece of state `capture-pane` alone cannot give us. A
 * `capture-pane` snapshot flattens the rendered grid to text but drops the
 * cursor's row/column. When we seed a freshly-attached pane with the snapshot
 * and then let live `%output` flow in, the agent's status/spinner line rewrites
 * itself *in place* with a bare carriage return (`\r` to column 0 of the
 * **current cursor row**) — it does NOT re-home the cursor first. So if the
 * seed leaves the emulator's cursor on the wrong row (e.g. the row *below* the
 * spinner, which is what a trailing newline does), the next live frame paints
 * on that wrong row and the seeded final frame stays put above it: two spinner
 * frames coexist, fragments of different frames mash together — the exact #259
 * garble (`gthinkingwithout`, two `Beboppin…` rows). Restoring the true cursor
 * after the seed makes the next live rewrite land on the same row tmux has it,
 * so the live frame cleanly overwrites the seeded one.
 */
internal data class TmuxPaneCursor(val column: Int, val row: Int)

/**
 * Parse a tmux `display-message -p '#{cursor_x},#{cursor_y}'` reply line
 * (e.g. `0,2`) into a [TmuxPaneCursor]. Returns null when the reply is
 * missing, malformed, or carries negative coordinates — callers fall back to
 * seeding without an explicit cursor restore.
 */
internal fun parseTmuxPaneCursor(reply: String?): TmuxPaneCursor? {
    val parts = reply?.trim()?.split(',') ?: return null
    if (parts.size != 2) return null
    val column = parts[0].trim().toIntOrNull() ?: return null
    val row = parts[1].trim().toIntOrNull() ?: return null
    if (column < 0 || row < 0) return null
    return TmuxPaneCursor(column = column, row = row)
}

/**
 * Issue #259: build the byte stream that seeds a freshly-attached pane's
 * emulator from a `capture-pane -p -e -S -200` snapshot.
 *
 * Three things matter for the seed to render cleanly and match the live pane:
 *
 *  1. **No forced trailing newline.** The old builder appended a final `\r\n`,
 *     which scrolled the captured content up one row and parked the cursor on
 *     the row *below* the last captured line. The agent's in-place spinner
 *     rewrite (`\r` + frame, no re-home) then painted one row too low, leaving
 *     the seeded frame stranded above the live frame. We replay the lines
 *     joined by `\r\n` with **no** terminating newline.
 *  2. **Reset SGR at the seed boundary.** `capture-pane -e` emits each cell's
 *     colour but does not guarantee a closing reset, so an unterminated colour
 *     run could bleed past the seed into live output. We emit `ESC[0m` after
 *     the last captured line (which keeps the captured content's own colours)
 *     and before moving the cursor.
 *  3. **Restore the true cursor.** When [cursor] is known we emit a
 *     viewport-absolute cursor-position (`ESC[<row+1>;<col+1>H`, 1-based) so
 *     the emulator's cursor sits exactly where tmux has it. `CSI H` targets the
 *     visible screen, so it is correct regardless of how much scrollback the
 *     capture replayed into history. When [cursor] is null (older tmux, or the
 *     query failed) we leave the cursor at the end of the replay — still better
 *     than the old below-content placement.
 */
private fun List<String>.toTerminalViewportBytes(cursor: TmuxPaneCursor? = null): ByteArray {
    val lines = this
    val text = buildString {
        append("\u001b[H\u001b[2J")
        lines.forEachIndexed { index, line ->
            if (index > 0) append("\r\n")
            append(line)
        }
        // Close any open SGR run from the captured cells so a colour started
        // on the last captured line cannot bleed into subsequent live output.
        append("\u001b[0m")
        if (cursor != null) {
            // CSI <row>;<col> H is 1-based; tmux reports 0-based coordinates.
            append("\u001b[${cursor.row + 1};${cursor.column + 1}H")
        }
    }
    return text.toByteArray(Charsets.UTF_8)
}

private const val MaxAgentEvents: Int = 500
internal const val CtrlCByte: Int = 0x03
internal const val CtrlDByte: Int = 0x04

/**
 * Issue #215: ceiling on the synchronous `detach-client` round-trip the
 * non-suspending [TmuxSessionViewModel] teardown path runs during
 * `onCleared()` and the test-replacement seam. Bound the activity
 * destroy on Main thread so a wedged transport cannot stall the user's
 * back-press / app-finish journey.
 *
 * 600ms is well above the sub-50ms tmux takes on a healthy localhost /
 * Docker server (the only target where the detach can actually land
 * cleanly) and small enough that a SIGKILL on the sshd worker — the
 * pathological worst case — adds only a perceptible-but-bounded
 * teardown pause rather than an apparent app freeze.
 */
internal const val SYNC_DETACH_TIMEOUT_MS: Long = 600L
internal const val ATTACH_PANES_READY_TIMEOUT_MS: Long = 30_000L
internal const val ATTACH_PANES_READY_RETRY_MS: Long = 100L
internal const val LIST_PANES_FIELD_SEPARATOR: String = "|PS|"
internal const val TMUX_INPUT_MAX_PENDING_BYTES: Int = 64 * 1024
internal const val TMUX_INPUT_MAX_BATCH_BYTES: Int = 4 * 1024
internal const val TMUX_INPUT_CHUNK_BYTES: Int = 512
internal const val TMUX_INPUT_MAX_PENDING_CHUNKS: Int =
    TMUX_INPUT_MAX_PENDING_BYTES / TMUX_INPUT_CHUNK_BYTES - 1

/**
 * Issue #145: logcat tag used by the disconnect observer + connect-attempt
 * counter. Kept short enough to satisfy `Log.isLoggable`'s 23-character
 * tag limit on older Android versions while still being grep-able from a
 * dumped logcat. The connected reconnect test searches for this tag and
 * counts `tmux-connect-attempt` lines to assert no reconnect-loop
 * thrash.
 */
internal const val ISSUE_145_RECONNECT_TAG: String = "PsTmuxReconnect"

/**
 * Issue #235: logcat tag for the auto-detach-on-background +
 * reattach-on-foreground lifecycle journey, plus the manual Detach
 * button. Same 23-character `Log.isLoggable` cap as the other
 * ViewModel tags. The connected `TmuxDetachOnBackgroundE2eTest` greps
 * for this so the assertion path can correlate the lifecycle event the
 * test drives with the actual ViewModel detach landing.
 */
internal const val ISSUE_235_LIFECYCLE_TAG: String = "PsTmuxLifecycle"

/**
 * Issue #188: logcat tag for kill-window diagnostics. Mirrors the
 * `issue168-kill` tag the dashboard side uses for `kill-session`. Kept
 * under the 23-character Log.isLoggable cap.
 */
internal const val ISSUE_188_DIAG_TAG: String = "issue188-killwindow"

public enum class TmuxConnectTrigger(public val logValue: String) {
    UserTap("user-tap"),
    LifecycleReattach("lifecycle-reattach"),
    ColdRestore("cold-restore"),
    FastSwitch("fast-switch"),
    Reconnect("reconnect"),
}

public data class TmuxRestoreIntentSnapshot(
    val hostId: Long,
    val hostName: String,
    val hostname: String,
    val port: Int,
    val username: String,
    val keyPath: String,
    val sessionName: String,
    val startDirectory: String?,
    val trigger: TmuxConnectTrigger,
    val generation: Long,
)

/**
 * Issue #188: max time we wait for tmux to emit the post-kill
 * `%window-close` notification before falling back to an unconditional
 * reconcile. 2s mirrors the acceptance criterion in the issue body —
 * tmux's actual window-cleanup latency on a healthy localhost / Docker
 * server is sub-100ms.
 */
internal const val KILL_WINDOW_EVENT_WAIT_MS: Long = 2_000L

/**
 * Issue #145: process-wide monotonic counter of `connect()` calls that
 * actually progress to a new attempt (the idempotent early-returns do
 * not increment). The connected reconnect test snapshots this value
 * before and after the test body to assert exactly one reconnect attempt
 * per disconnect, anchored to the test's lifetime rather than to a
 * shared on-device logcat buffer that can roll over.
 *
 * The counter is internal because callers should never key behaviour
 * off it; it is purely a test signal.
 */
internal val TMUX_CONNECT_ATTEMPTS: AtomicInteger = AtomicInteger(0)

/**
 * Issue #178: process-wide monotonic counter of **SSH handshakes** the
 * ViewModel actually performed (i.e. trips through
 * [com.pocketshell.core.ssh.SshConnection.connect]). Distinct from
 * [TMUX_CONNECT_ATTEMPTS], which counts logical connect() invocations:
 * a same-host session switch increments TMUX_CONNECT_ATTEMPTS but does
 * NOT increment this counter because no new SSH handshake fires — the
 * existing transport is reused.
 *
 * The connected `TmuxSessionSwitchSameHostReusesSshE2eTest` snapshots
 * this value before the second attach and asserts the delta is zero,
 * which is the structural assertion behind acceptance criterion "Same-
 * host session switching does NOT open a new SSH socket".
 *
 * Internal because callers should never key behaviour off it; it is
 * purely a test signal.
 */
internal val SSH_HANDSHAKE_ATTEMPTS: AtomicInteger = AtomicInteger(0)
