package com.pocketshell.app.tmux

import android.content.Context
import android.net.Uri
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
import com.pocketshell.app.assistant.FolderCandidate
import com.pocketshell.app.assistant.RealAssistantSshExecutor
import com.pocketshell.app.assistant.SessionActionBridge
import com.pocketshell.app.assistant.SessionAssistantController
import com.pocketshell.app.composer.PromptAttachmentStager
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.OPTIMISTIC_USER_MESSAGE_ID_PREFIX
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.session.canRetryAgentStream
import com.pocketshell.app.session.markOptimisticFailed
import com.pocketshell.app.session.reconcileAgentEvents
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.core.assistant.AssistantLlmClientFactory
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.uikit.model.KeyModifierState
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.DEFAULT_TMUX_START_DIRECTORY
import com.pocketshell.app.sessions.SSH_SOURCE_TMUX_CONNECT
import com.pocketshell.app.sessions.SshOpenTelemetry
import com.pocketshell.app.sessions.remoteStartDirectoryExists
import com.pocketshell.app.sessions.resolveTmuxSessionCreation
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.portfwd.PortScanner
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
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
import kotlinx.coroutines.currentCoroutineContext
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
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
 *    session name. We acquire an [SshLease] via [SshLeaseManager],
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
 *    and releases the SSH lease.
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
 * SSH lease acquisition / [TmuxClientFactory.create] and binds the view
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
    private val runtimeCache: TmuxSessionRuntimeCache = TmuxSessionRuntimeCache(),
    private val sshLeaseManager: SshLeaseManager = SshLeaseManager(
        connector = SshLeaseConnector { target ->
            com.pocketshell.core.ssh.DefaultSshLeaseConnector().connect(target)
        },
    ),
    // Issue #464: process-scoped fan-out so a confirmed Kill session from
    // this screen tells the folder/session tree to drop the dead row.
    // Nullable default keeps the existing unit-test constructors working
    // without supplying the singleton.
    private val sessionLifecycleSignals: SessionLifecycleSignals? = null,
    // Issue #526: source of the user-configurable agent-submit Enter delay.
    // The composer/agent send path types the message text, waits this delay,
    // then sends the submit Enter as a separate `send-keys` so a fast Enter
    // doesn't race ahead of the agent TUI's paste ingestion. Nullable default
    // keeps the existing unit-test constructors working without the singleton;
    // when absent the send path falls back to the built-in defaults.
    private val settingsRepository: com.pocketshell.app.settings.SettingsRepository? = null,
) : ViewModel() {

    /**
     * Issue #526: test override for the configured agent-submit Enter delay
     * (ms). Unit tests build the view model without the
     * [com.pocketshell.app.settings.SettingsRepository] singleton, so this
     * seam lets a test pin the delay the send path waits before pressing the
     * submit Enter and assert the text → delay → Enter ordering with virtual
     * time. Null means "fall back to the repository / built-in default".
     */
    @Volatile
    private var agentSubmitEnterDelayMsOverrideForTest: Int? = null

    @androidx.annotation.VisibleForTesting
    internal fun setAgentSubmitEnterDelayForTest(delayMs: Int?) {
        agentSubmitEnterDelayMsOverrideForTest = delayMs
    }

    /** SSH executor seam for assistant tools; tests substitute it. */
    private var assistantSshExecutor: AssistantSshExecutor = RealAssistantSshExecutor()

    @androidx.annotation.VisibleForTesting
    internal fun setAssistantSshExecutor(executor: AssistantSshExecutor) {
        assistantSshExecutor = executor
    }

    private var tmuxClientFactoryOverride:
        ((SshSession, String, String?) -> TmuxClient)? = null

    @androidx.annotation.VisibleForTesting
    internal fun setTmuxClientFactoryForTest(
        factory: (SshSession, String, String?) -> TmuxClient,
    ) {
        tmuxClientFactoryOverride = factory
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
    private var autoReconnectDelaysMs: List<Long> = DEFAULT_AUTO_RECONNECT_DELAYS_MS

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

    /**
     * Issue #458: sticky state of the key bar's `Ctrl` modifier. When armed
     * ([KeyModifierState.OneShot] or [KeyModifierState.Locked]) the next
     * regular key tapped in the bar is sent as a Ctrl-chord control byte
     * (e.g. `Ctrl` then `C` → `0x03`). [KeyModifierState.OneShot] clears
     * after one chord; [KeyModifierState.Locked] persists until tapped
     * again. The ui-kit [com.pocketshell.uikit.components.KeyBar] owns the
     * tap FSM and mirrors changes here via [onKeyBarModifierState]; this
     * flow drives the screen's accent treatment + the chord decision in
     * [onKeyBarKey]. No tmux command is sent on a modifier tap — it only
     * decorates the next key, so there is no terminal redraw.
     */
    private val _ctrlModifierState: MutableStateFlow<KeyModifierState> =
        MutableStateFlow(KeyModifierState.Off)
    public val ctrlModifierState: StateFlow<KeyModifierState> = _ctrlModifierState.asStateFlow()

    /**
     * Issue #448 (epic #432 slice C): a newly-listening remote port the
     * session detected (regex over terminal output) AND confirmed via a
     * single `ss` scan. Non-null means the screen should render the
     * non-blocking "forward it?" overlay. Cleared by
     * [dismissDetectedPort] / [acceptDetectedPort]. Only ever set while
     * the session is foregrounded — detection rides the same per-pane
     * output flow the terminal consumes, which is only collected while
     * attached (foreground). There is no timer and no poll loop, so this
     * adds no background work (D21).
     */
    private val _detectedPort: MutableStateFlow<Int?> = MutableStateFlow(null)
    public val detectedPort: StateFlow<Int?> = _detectedPort.asStateFlow()

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
    private var leaseRef: SshLease? = null
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
    private var autoReconnectJob: Job? = null
    private var appActive: Boolean = true
    private var eventsJob: Job? = null
    private var disconnectedJob: Job? = null

    // Issue #440: the cause of the most recent failed connect attempt, set
    // by [failConnectAttempt] and consulted by [scheduleAutoReconnect]. The
    // auto-retry loop walks back through delays on a transient transport
    // drop (a network blip the maintainer wants recovered without a tap),
    // but a *non-retryable* failure — bad credentials, an unknown host,
    // a missing key file — will never fix itself by waiting and retrying.
    // For those we short-circuit the backoff loop immediately and fall back
    // to the manual Reconnect affordance so the user isn't watching four
    // doomed attempts tick by. Reset to null at the start of each fresh
    // connect attempt so a stale cause never leaks into a later decision.
    private var lastConnectFailureCause: Throwable? = null

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
    // Issue #495: remember which tmux windows are agent windows (and which
    // agent + the user's last tab choice) keyed by stable
    // host/session/window identity, so a reconnect restores the
    // Conversation tab immediately instead of bouncing the user to Terminal
    // while live re-detection round-trips. See [AgentSessionMemory].
    private val agentSessionMemory: AgentSessionMemory = AgentSessionMemory()
    private val paneAgentJobs: MutableMap<String, Job> = ConcurrentHashMap()
    private val paneAgentTailGenerations: MutableMap<String, Long> = ConcurrentHashMap()
    private val nextAgentTailGeneration: AtomicInteger = AtomicInteger(0)
    // Issue #186: dedup key is (cwd, foreground-command, tty). Adding
    // tty here means a tmux re-attach that rotates a pane onto a new
    // `/dev/pts/N` is a fresh detection trigger — the previous shape
    // (cwd, command) would treat that as already-seen and skip the
    // round-trip, leaving the cached "no agent" verdict in place.
    private val paneAgentInputs: MutableMap<String, Triple<String, String, String>> = ConcurrentHashMap()
    // Issue #554: count consecutive null detections for a pane whose window
    // has a remembered agent status (the #495 optimistic seed). On reconnect,
    // live detection can transiently read "no agent" before the agent's JSONL
    // log / process is observable on the fresh reattach. Forgetting + dropping
    // the seeded Conversation UI on that FIRST transient null is exactly the
    // "we forget it's an agent and bounce to plain shell" regression. We keep
    // the seeded agent UI and re-confirm; only after
    // [AGENT_EXIT_CONFIRMATIONS] consecutive nulls do we treat the agent as
    // genuinely exited and reconcile it away.
    private val paneAgentNullDetections: MutableMap<String, Int> = ConcurrentHashMap()
    private val paneInputQueues: MutableMap<String, TmuxPaneInputQueue> = ConcurrentHashMap()
    private val paneInputJobs: MutableMap<String, Job> = ConcurrentHashMap()

    // Issue #448 (epic #432 slice C): session-scoped new-port detection.
    // [portDetector] owns the regex trigger + de-dup (dismissed /
    // forwarded / already-listening ports are never re-offered). One
    // collector per pane scans the same shared output flow the terminal
    // consumes; the jobs are tracked so they tear down with the pane.
    // Scoped to this view model instance (one per tmux session screen);
    // de-dup is retained across same-host warm switches so a parked-then-
    // restored runtime never re-prompts a port already handled.
    private val portDetector: PortDetector = PortDetector()
    private val panePortDetectorJobs: MutableMap<String, Job> = ConcurrentHashMap()

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

    // Issue #423: track recent terminal-surface recovery attempts per pane.
    // A single IME/resize/render exception recovers transparently by
    // recreating the surface, but if the surface keeps failing inside a
    // short window we are in a recovery storm (the failure described in the
    // issue: opening the keyboard after a long dictated prompt makes the
    // terminal "start redrawing, then freeze and never return"). When that
    // happens we stop re-attaching the broken surface and flip the pane to
    // an actionable error state instead of thrashing the recovery path.
    // The SSH/tmux transport stays untouched the whole time.
    private val paneSurfaceRecoveryTimestamps: MutableMap<String, ArrayDeque<Long>> =
        ConcurrentHashMap()

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
        pausedAutoReconnect = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
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
        val fastSwitchStartedAtMs = if (willFastSwitch) SystemClock.elapsedRealtime() else 0L
        if (willFastSwitch) {
            recordWarmSwitchMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = fastSwitchStartedAtMs,
                name = "warm_switch_start",
                trigger = effectiveTrigger,
            )
        }

        if (tryActivateCachedRuntime(target, attempt, effectiveTrigger, fastSwitchStartedAtMs)) {
            return
        }
        cancelTmuxSessionPrewarm()

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
        // Issue #437 (slice A): a same-host switch reuses the already-warm
        // SSH transport — the only thing happening is a new `-CC` control
        // client attach over the live channel. Gating the viewport on
        // [Connecting] (and blanking the panes below) made the user stare
        // at a full-screen "Connecting" overlay even though no SSH
        // handshake is occurring, which felt like a fresh reconnect. For
        // these switches we instead enter [Switching]: the previous frame
        // (or a cached snapshot) stays painted and the full-screen overlay
        // is suppressed, while [runFastSessionSwitch] spawns the new client
        // in the background and flips to [Connected] once panes are ready.
        // The full-screen [Connecting] overlay is reserved for a genuine
        // first-connect to a host.
        _connectionStatus.value = if (willFastSwitch) {
            ConnectionStatus.Switching(host, port, user)
        } else {
            ConnectionStatus.Connecting(host, port, user)
        }
        // Issue #257 (scope F): drop the previous session's panes from the
        // rendered list SYNCHRONOUSLY, before the teardown coroutine even
        // gets a turn. The actual per-pane producer/scrollback teardown
        // still happens inside [deactivateCurrentRuntimeToCache] /
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
        //
        // Issue #437 (slice A): for a same-host [Switching], we deliberately
        // do NOT blank the rendered panes here. Keeping the previous frame
        // painted (it is replaced atomically when the new client's panes
        // reconcile in [runFastSessionSwitch]) is what removes the
        // perceived "Connecting" blank — exactly mirroring the cache-hit
        // path, which also keeps a frame on screen and reconciles in the
        // background. Input stays gated because [Switching] is not
        // [Connected].
        if (!willFastSwitch) {
            _panes.value = emptyList()
        }
        if (willFastSwitch) {
            recordWarmSwitchMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = fastSwitchStartedAtMs,
                name = "warm_switch_selected_session_state",
                trigger = effectiveTrigger,
                detail = "paneCount=0",
            )
        }
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
                    // Fast path: keep the SSH session and keep the
                    // previous tmux/UI runtime warm in the cache. If the
                    // user switches back, activation is a pointer swap
                    // rather than a fresh tmux attach.
                    //
                    // Issue #437 (slice A): snapshot the rendered frame
                    // BEFORE parking the leaving runtime so the viewport
                    // never blanks during a same-host switch.
                    // [deactivateCurrentRuntimeToCache] clears [_panes] as
                    // part of detaching the leaving session's producers;
                    // we immediately re-publish the snapshot so the user
                    // keeps seeing content (the previous frame) until
                    // [runFastSessionSwitch]'s pane reconcile atomically
                    // swaps in the new session's panes. This mirrors the
                    // cache-hit path, which also keeps a frame painted and
                    // reconciles in the background, and removes the
                    // perceived full-screen "Connecting" blank. Input stays
                    // gated for the whole window because the status is
                    // [Switching], not [Connected].
                    val previousFrame = _panes.value
                    deactivateCurrentRuntimeToCache()
                    if (previousFrame.isNotEmpty()) {
                        _panes.value = previousFrame
                    }
                    runFastSessionSwitch(target, attempt, effectiveTrigger, fastSwitchStartedAtMs)
                } else {
                    // Slow path: full teardown of both the tmux client
                    // and the SSH session before the fresh
                    // SSH lease acquisition.
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
        previous.hostId == target.hostId &&
            previous.host == target.host &&
            previous.port == target.port &&
            previous.user == target.user &&
            previous.keyPath == target.keyPath

    private fun sameSessionIdentity(left: ConnectionTarget, right: ConnectionTarget): Boolean =
        left.hostId == right.hostId &&
            left.host == right.host &&
            left.port == right.port &&
            left.user == right.user &&
            left.keyPath == right.keyPath &&
            left.sessionName == right.sessionName &&
            left.startDirectory == right.startDirectory

    private fun createTmuxClient(
        session: SshSession,
        sessionName: String,
        startDirectory: String?,
    ): TmuxClient =
        tmuxClientFactoryOverride?.invoke(session, sessionName, startDirectory)
            ?: tmuxClientFactory.create(
                session,
                sessionName = sessionName,
                startDirectory = startDirectory,
            )

    private fun isCurrentRuntime(guard: RuntimeRefreshGuard): Boolean {
        val currentTarget = activeTarget ?: return false
        return connectGeneration == guard.generation &&
            clientRef === guard.client &&
            sameSessionIdentity(currentTarget, guard.target)
    }

    private fun ConnectionTarget.toRuntimeKey(): TmuxRuntimeKey =
        TmuxRuntimeKey(
            hostId = hostId,
            hostname = host,
            port = port,
            username = user,
            keyPath = keyPath,
            sessionName = sessionName,
        )

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
        pausedAutoReconnect = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
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

    @androidx.annotation.VisibleForTesting
    internal fun setAutoReconnectDelaysForTest(delaysMs: List<Long>) {
        autoReconnectDelaysMs = delaysMs
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
        if (current !is ConnectionStatus.Connecting && current !is ConnectionStatus.Reconnecting) return false
        pausedAutoReconnect = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
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
    private var pausedAutoReconnect: PausedAutoReconnect? = null
    private var backgroundDetachJob: Job? = null
    private var sessionPrewarmJob: Job? = null
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
        appActive = false
        val reconnecting = _connectionStatus.value as? ConnectionStatus.Reconnecting
        if (reconnecting != null) {
            autoReconnectJob?.cancel()
            autoReconnectJob = null
            val target = activeTarget ?: connectingTarget
            if (target != null) {
                pausedAutoReconnect = PausedAutoReconnect(
                    target = target,
                    reason = reconnecting.reason,
                )
                activeTarget = target
                connectingTarget = target
                refreshReconnectAvailability()
            }
            _connectionStatus.value = ConnectionStatus.Failed(
                "${reconnecting.reason} Auto reconnect paused while PocketShell is in the background.",
            )
            return
        }
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
        appActive = true
        if (pendingReattach == null) {
            val paused = pausedAutoReconnect
            if (paused != null) {
                resumePausedAutoReconnect(paused)
                return
            }
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

    private fun resumePausedAutoReconnect(paused: PausedAutoReconnect) {
        pausedAutoReconnect = null
        val target = paused.target
        if (_connectionStatus.value is ConnectionStatus.Connected && activeTarget == target) return
        Log.i(
            ISSUE_235_LIFECYCLE_TAG,
            "tmux-auto-reconnect-resume-on-foreground " +
                targetLogFields(target) +
                " reason=${paused.reason}",
        )
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
            trigger = TmuxConnectTrigger.AutoReconnect,
        )
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
        pausedAutoReconnect = null
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

    internal fun activeSessionNameForTest(): String? = activeTarget?.sessionName

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
     * Best-effort background warmup for same-host session-switch targets.
     *
     * The caller invokes this only after the switcher/drawer has rendered a
     * Ready list. Work is bounded to the first two non-current sessions from
     * that activity-ordered list and populates [runtimeCache] without touching
     * the visible foreground runtime.
     */
    public fun prewarmLikelySwitchTargets(sessionNames: List<String>) {
        val baseTarget = activeTarget ?: return
        val foregroundSession = sessionRef?.takeIf { it.isConnected } ?: return
        val targets = sessionNames
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != baseTarget.sessionName }
            .distinct()
            .map { sessionName -> baseTarget.copy(sessionName = sessionName, startDirectory = null) }
            .filterNot { runtimeCache.contains(it.toRuntimeKey()) }
            .take(TMUX_SESSION_PREWARM_MAX_TARGETS)
            .toList()
        if (targets.isEmpty()) return

        sessionPrewarmJob?.cancel()
        sessionPrewarmJob = bridgeScope.launch {
            for (target in targets) {
                if (!isActive) return@launch
                if (activeTarget?.let { isSameHost(it, target) } != true) return@launch
                if (runtimeCache.contains(target.toRuntimeKey())) continue
                prewarmRuntime(target, foregroundSession)
            }
        }
    }

    public fun cancelTmuxSessionPrewarm() {
        sessionPrewarmJob?.cancel()
        sessionPrewarmJob = null
    }

    private suspend fun prewarmRuntime(
        target: ConnectionTarget,
        foregroundSession: SshSession,
    ) {
        var lease: SshLease? = null
        var client: TmuxClient? = null
        var paneRuntime: PrewarmedPaneRuntime? = null
        try {
            lease = sshLeaseManager.acquire(target.toSshLeaseTarget()).getOrThrow()
            val session = lease.session
            if (!session.isConnected) return
            client = createTmuxClient(session, target.sessionName, target.startDirectory)
            client.connect()
            val panes = buildPrewarmedPaneRuntime(target, client).also { paneRuntime = it }
            if (!currentCoroutineContext().isActive || !session.isConnected) return
            if (activeTarget?.let { isSameHost(it, target) } != true) return
            if (foregroundSession !== sessionRef && sessionRef != session) return
            val runtime = CachedTmuxRuntime(
                key = target.toRuntimeKey(),
                hostName = target.hostName,
                startDirectory = target.startDirectory,
                lease = lease,
                session = session,
                client = client,
                panes = panes.panes,
                paneRows = panes.paneRows,
                paneProducerJobs = panes.paneProducerJobs,
                paneInputQueues = panes.paneInputQueues,
                paneInputJobs = panes.paneInputJobs,
                paneAgentJobs = emptyMap(),
                paneAgentInputs = emptyMap(),
                agentConversations = emptyMap(),
                remoteColumns = remoteColumns,
                remoteRows = remoteRows,
            )
            lease = null
            client = null
            paneRuntime = null
            closeCachedRuntimesAsync(runtimeCache.put(runtime))
            TmuxSessionLatencyTelemetry.record(
                name = "runtime_prewarm_ready",
                durationMs = 1L,
                sessionName = target.sessionName,
                trigger = TmuxConnectTrigger.FastSwitch,
                detail = "paneCount=${panes.panes.size}",
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            TmuxSessionLatencyTelemetry.record(
                name = "runtime_prewarm_failed",
                durationMs = 1L,
                sessionName = target.sessionName,
                trigger = TmuxConnectTrigger.FastSwitch,
                detail = t.javaClass.simpleName,
            )
        } finally {
            if (client != null || lease != null) {
                withContext(NonCancellable) {
                    paneRuntime?.closePartialPrewarm()
                    runCatching { client?.detachCleanly() }
                    runCatching { lease?.release() }
                }
            }
        }
    }

    private suspend fun buildPrewarmedPaneRuntime(
        target: ConnectionTarget,
        client: TmuxClient,
    ): PrewarmedPaneRuntime {
        val response = client.sendCommand(buildListPanesCommand(target))
        if (response.isError) {
            throw TmuxAttachPanesReadyException(
                "tmux list-panes failed during prewarm: " +
                    response.output.joinToString(separator = " ").ifBlank { "unknown error" },
            )
        }
        val paneInputQueues = LinkedHashMap<String, TmuxPaneInputQueue>()
        val paneInputJobs = LinkedHashMap<String, Job>()
        val paneProducerJobs = LinkedHashMap<String, Job>()
        val paneRows = LinkedHashMap<String, TmuxPaneState>()
        try {
            val parsed = response.output
                .mapNotNull { parsePaneRow(it) }
                .filter { it.sessionName == target.sessionName }
                .sortedWith(compareBy({ it.windowId }, { it.paneIndex }, { it.paneId }))
            for (pane in parsed) {
                val state = TerminalSurfaceState()
                val producerJob = state.attachExternalProducer(
                    scope = bridgeScope,
                    stdout = client.outputFor(pane.paneId).map { it.data },
                    remoteStdin = inputSinkForPane(
                        paneId = pane.paneId,
                        client = client,
                        queues = paneInputQueues,
                        jobs = paneInputJobs,
                    ),
                    suppressQueryResponses = true,
                    // Issue #468: buffer live %output behind the seed gate
                    // until seedPrewarmedPane applies the capture-pane snapshot,
                    // so the snapshot cannot race the live stream.
                    awaitSeed = true,
                )
                val row = TmuxPaneState(
                    paneId = pane.paneId,
                    windowId = pane.windowId,
                    sessionId = pane.sessionId,
                    title = pane.title,
                    cwd = pane.cwd,
                    currentCommand = pane.currentCommand,
                    paneTty = pane.paneTty,
                    terminalState = state,
                )
                paneRows[pane.paneId] = row
                paneProducerJobs[pane.paneId] = producerJob
                // Issue #448 (epic #432 slice C): also tap the prewarmed
                // pane's shared output flow for new-port detection.
                startPortDetectionForPane(paneId = pane.paneId, client = client)
                seedPrewarmedPane(client, row)
            }
            return PrewarmedPaneRuntime(
                panes = paneRows.values.toList(),
                paneRows = paneRows,
                paneProducerJobs = paneProducerJobs,
                paneInputQueues = paneInputQueues,
                paneInputJobs = paneInputJobs,
            )
        } catch (t: Throwable) {
            PrewarmedPaneRuntime(
                panes = paneRows.values.toList(),
                paneRows = paneRows,
                paneProducerJobs = paneProducerJobs,
                paneInputQueues = paneInputQueues,
                paneInputJobs = paneInputJobs,
            ).closePartialPrewarm()
            throw t
        }
    }

    private suspend fun seedPrewarmedPane(client: TmuxClient, pane: TmuxPaneState) {
        // Issue #468: the live %output producer is gated behind the seed.
        // appendRemoteOutput opens the gate when a snapshot lands; if the
        // capture fails (or anything throws) we must still open the gate so
        // buffered live output is flushed in order rather than swallowed.
        var seeded = false
        try {
            val capture = client.sendCommand("capture-pane -p -e -S -200 -t ${pane.paneId}")
            if (capture.isError || capture.output.isEmpty()) return
            val cursor = runCatching {
                client.sendCommand("display-message -p -t ${pane.paneId} '#{cursor_x},#{cursor_y}'")
            }.getOrNull()
                ?.takeUnless { it.isError }
                ?.output
                ?.firstOrNull()
                .let { parseTmuxPaneCursor(it) }
            pane.terminalState.appendRemoteOutput(capture.output.toTerminalViewportBytes(cursor))
            seeded = true
        } finally {
            if (!seeded) pane.terminalState.openSeedGateWithoutSeed()
        }
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
                onNetworkChanged = { reason -> onNetworkChanged(reason) },
            ),
        )
        lifecycleHookHostId = hostId
    }

    /**
     * Issue #548: when Android reports a validated default-network change
     * while the terminal is foregrounded, reconnect immediately instead of
     * waiting for sshj's reader to discover that the old TCP path died.
     */
    public fun onNetworkChanged(reason: String) {
        if (!appActive) return
        if (autoReconnectJob?.isActive == true) return
        val current = _connectionStatus.value
        if (current !is ConnectionStatus.Connected) return
        val target = activeTarget ?: connectingTarget ?: return
        if (clientRef == null && sessionRef == null) return
        val reconnectReason = "Network changed; reconnecting ${current.user}@${current.host}:${current.port}."
        Log.i(
            ISSUE_548_NETWORK_TAG,
            "tmux-network-proactive-reconnect reason=$reason " +
                targetLogFields(target),
        )
        registeredHostId?.let { activeTmuxClients.unregister(it) }
        registeredHostId = null
        scheduleAutoReconnect(
            target = target,
            reason = reconnectReason,
            trigger = TmuxConnectTrigger.NetworkReconnect,
        )
    }

    private fun tryActivateCachedRuntime(
        target: ConnectionTarget,
        attempt: Int,
        trigger: TmuxConnectTrigger,
        visibleSwitchStartedAtMs: Long,
    ): Boolean {
        val activation = runtimeCache.activate(target.toRuntimeKey())
        closeCachedRuntimesAsync(activation.evicted)
        val cached = activation.runtime ?: run {
            val startedAtMs = visibleSwitchStartedAtMs.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtime()
            recordWarmSwitchMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                name = "warm_switch_runtime_cache_miss",
                trigger = trigger,
                detail = "source=runtime_cache",
            )
            return false
        }
        if (cached.client.disconnected.value || cached.session?.isConnected == false) {
            val startedAtMs = visibleSwitchStartedAtMs.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtime()
            recordWarmSwitchMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                name = "warm_switch_runtime_cache_miss",
                trigger = trigger,
                detail = "source=runtime_cache reason=disconnected",
            )
            viewModelScope.launch {
                cached.closeCachedRuntime()
            }
            return false
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        val milestoneStartedAtMs = visibleSwitchStartedAtMs.takeIf { it > 0L } ?: startedAtMs
        val generation = latestConnectIntent?.takeIf {
            sameSessionIdentity(it.target, target)
        }?.generation ?: connectGeneration
        deactivateCurrentRuntimeToCache()
        restoreCachedRuntime(target, cached)
        StartupTiming.mark(
            "tmux-runtime-cache-hit",
            "attempt" to attempt,
            "hostId" to target.hostId,
            "host" to target.host,
            "port" to target.port,
            "session" to target.sessionName,
            "trigger" to trigger.logValue,
        )
        TmuxSessionLatencyTelemetry.record(
            name = "runtime_cache_activate",
            durationMs = SystemClock.elapsedRealtime() - startedAtMs,
            sessionName = target.sessionName,
            trigger = trigger,
        )
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-runtime-cache-hit trigger=${trigger.logValue} " +
                "attempt=$attempt " + targetLogFields(target),
        )
        logAttachMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = startedAtMs,
            event = "tmux-runtime-cache-ready",
            trigger = trigger,
            detail = "paneCount=${cached.panes.size}",
        )
        recordWarmSwitchMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = milestoneStartedAtMs,
            name = "warm_switch_selected_session_state",
            trigger = trigger,
            detail = "paneCount=${cached.panes.size} source=runtime_cache",
        )
        recordWarmSwitchMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = milestoneStartedAtMs,
            name = "warm_switch_panes_ready",
            trigger = trigger,
            detail = "paneCount=${cached.panes.size} source=runtime_cache",
        )
        recordWarmSwitchMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = milestoneStartedAtMs,
            name = "warm_switch_connect_ready",
            trigger = trigger,
            detail = "paneCount=${cached.panes.size} source=runtime_cache",
        )
        recordWarmSwitchMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = milestoneStartedAtMs,
            name = "warm_switch_first_cached_frame",
            trigger = trigger,
            detail = "paneCount=${cached.panes.size} source=runtime_cache cacheHit=true",
        )
        launchCachedRuntimeRemoteRefresh(
            target = target,
            client = cached.client,
            generation = generation,
            attempt = attempt,
            startedAtMs = milestoneStartedAtMs,
            trigger = trigger,
        )
        return true
    }

    private fun deactivateCurrentRuntimeToCache() {
        val target = activeTarget ?: return
        val client = clientRef ?: return
        eventsJob?.cancel()
        eventsJob = null
        disconnectedJob?.cancel()
        disconnectedJob = null
        projectRootsJob?.cancel()
        projectRootsJob = null
        val runtime = CachedTmuxRuntime(
            key = target.toRuntimeKey(),
            hostName = target.hostName,
            startDirectory = target.startDirectory,
            lease = leaseRef,
            session = sessionRef,
            client = client,
            panes = _panes.value.ifEmpty { paneRows.values.toList() },
            paneRows = paneRows.toMap(),
            paneProducerJobs = paneProducerJobs.toMap(),
            paneInputQueues = paneInputQueues.toMap(),
            paneInputJobs = paneInputJobs.toMap(),
            paneAgentJobs = paneAgentJobs.toMap(),
            paneAgentInputs = paneAgentInputs.toMap(),
            agentConversations = _agentConversations.value,
            remoteColumns = remoteColumns,
            remoteRows = remoteRows,
        )
        closeCachedRuntimesAsync(runtimeCache.put(runtime))
        leaseRef = null
        sessionRef = null
        clientRef = null
        paneRows.clear()
        paneProducerJobs.clear()
        paneInputQueues.clear()
        paneInputJobs.clear()
        paneSurfaceRecoveryTimestamps.clear()
        paneAgentJobs.values.forEach { it.cancel() }
        paneAgentJobs.clear()
        paneAgentTailGenerations.clear()
        paneAgentInputs.clear()
        paneAgentNullDetections.clear()
        clearAgentConversations()
        // Issue #448: drop any pending forward overlay when this runtime
        // is parked to the cache — it belongs to the view we're leaving.
        // The detector's session-scoped de-dup is intentionally retained
        // so a restored same-host runtime doesn't re-prompt ports already
        // dismissed/forwarded.
        _detectedPort.value = null
        _panes.value = emptyList()
        registeredHostId?.let { activeTmuxClients.unregister(it) }
        registeredHostId = null
        activeTarget = null
        activeAttachMilestone = null
    }

    private fun closeCachedRuntimesAsync(runtimes: List<CachedTmuxRuntime>) {
        if (runtimes.isEmpty()) return
        viewModelScope.launch {
            runtimes.forEach { runtime ->
                runtime.closeCachedRuntime()
            }
        }
    }

    private fun restoreCachedRuntime(
        target: ConnectionTarget,
        runtime: CachedTmuxRuntime,
    ) {
        clientRef = runtime.client
        leaseRef = runtime.lease
        sessionRef = runtime.session
        activeTarget = target
        connectingTarget = null
        activeAttachMilestone = null
        paneRows.clear()
        paneRows.putAll(runtime.paneRows)
        paneProducerJobs.clear()
        paneProducerJobs.putAll(runtime.paneProducerJobs)
        paneInputQueues.clear()
        paneInputQueues.putAll(runtime.paneInputQueues)
        paneInputJobs.clear()
        paneInputJobs.putAll(runtime.paneInputJobs)
        paneSurfaceRecoveryTimestamps.clear()
        paneAgentJobs.clear()
        paneAgentTailGenerations.clear()
        paneAgentInputs.clear()
        paneAgentNullDetections.clear()
        paneAgentInputs.putAll(runtime.paneAgentInputs)
        replaceAgentConversations(runtime.agentConversations)
        remoteColumns = runtime.remoteColumns
        remoteRows = runtime.remoteRows
        resetControlClientSizeForAttach()
        bindClientObservers(runtime.client)
        _panes.value = runtime.panes
        restartAgentConversationsForRestoredRuntime(runtime)
        activeTmuxClients.register(
            hostId = target.hostId,
            hostName = target.hostName,
            hostname = target.host,
            port = target.port,
            username = target.user,
            keyPath = target.keyPath,
            client = runtime.client,
            startDirectoryExists = runtime.session?.let { session ->
                { directory -> remoteStartDirectoryExists(session, directory) }
            },
        )
        registeredHostId = target.hostId
        installLifecycleHooks(target.hostId)
        bindProjectRootsForHost(target.hostId)
        refreshReconnectAvailability()
        _connectionStatus.value = ConnectionStatus.Connected(
            target.host,
            target.port,
            target.user,
        )
    }

    private fun launchCachedRuntimeRemoteRefresh(
        target: ConnectionTarget,
        client: TmuxClient,
        generation: Long,
        attempt: Int,
        startedAtMs: Long,
        trigger: TmuxConnectTrigger,
    ) {
        val refresh = RuntimeRefreshGuard(
            generation = generation,
            target = target,
            client = client,
        )
        bridgeScope.launch {
            delay(CACHED_RUNTIME_REMOTE_REFRESH_DELAY_MS)
            if (!isCurrentRuntime(refresh)) return@launch
            logAttachMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                event = "tmux-runtime-cache-refresh-start",
                trigger = trigger,
            )
            reconcilePanes(refresh)
            if (!isCurrentRuntime(refresh)) return@launch
            maybeRefreshControlClientSize()
            recordWarmSwitchMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                name = "warm_switch_remote_refresh_complete",
                trigger = trigger,
                detail = "source=runtime_cache",
            )
        }
    }

    private suspend fun acquireLeaseForTmux(
        target: ConnectionTarget,
        attempt: Int,
        trigger: TmuxConnectTrigger,
        startedAtMs: Long,
        preferReuseLog: Boolean = false,
    ): SshLease? {
        val leaseTarget = target.toSshLeaseTarget()
        val leaseResult = sshLeaseManager.acquire(leaseTarget)
        val lease = leaseResult.getOrElse { e ->
            failConnectAttempt(
                target = target,
                attempt = attempt,
                startedAtMs = startedAtMs,
                message = "connect failed: ${e.message}",
                cause = e,
                preserveReconnectTarget = false,
            )
            return null
        }
        if (preferReuseLog || !lease.isNewConnection) {
            StartupTiming.mark(
                "tmux-ssh-lease-reused",
                "attempt" to attempt,
                "hostId" to target.hostId,
                "host" to target.host,
                "port" to target.port,
                "session" to target.sessionName,
                "trigger" to trigger.logValue,
            )
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-ssh-lease-reused trigger=${trigger.logValue} " +
                    "${targetLogFields(target)} attempt=$attempt",
            )
        } else {
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
                    "${targetLogFields(target)} attempt=$attempt",
            )
            SshOpenTelemetry.record(
                source = SSH_SOURCE_TMUX_CONNECT,
                host = target.host,
                port = target.port,
                user = target.user,
            )
        }
        return lease
    }

    private fun ConnectionTarget.toSshLeaseTarget(): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = host,
                port = port,
                user = user,
                credentialId = "$hostId:$keyPath",
                knownHostsId = "accept-all",
            ),
            key = SshKey.Path(File(keyPath)),
            passphrase = passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        )

    private suspend fun runConnect(
        target: ConnectionTarget,
        attempt: Int,
        trigger: TmuxConnectTrigger,
    ) {
        val startedAtMs = SystemClock.elapsedRealtime()
        // Issue #440: a fresh attempt starts with no recorded failure so a
        // stale cause from a previous attempt never influences the retry
        // decision. [failConnectAttempt] re-populates it if this one fails.
        lastConnectFailureCause = null
        try {
            val lease = acquireLeaseForTmux(target, attempt, trigger, startedAtMs)
                ?: return
            val session = lease.session
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
            leaseRef = lease
            sessionRef = session

            val client = createTmuxClient(session, target.sessionName, target.startDirectory)
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
            // Issue #553: tmux `-CC` does not re-emit existing pane content on
            // a fresh control-client attach — only subsequent `%output`
            // deltas. Re-seed every visible pane from a `capture-pane`
            // snapshot now that the handshake is complete, so EVERY reconnect
            // (manual Reconnect AND the #444 auto-reconnect path both reach
            // here via [runConnect]) repaints the full prior screen instead of
            // leaving it blank with only the live timer/spinner painting
            // against black. [preloadVisibleContentForNewPanes] already seeds
            // the freshly-created pane rows; this is the safety net for panes
            // reused across a racing reconcile and the authoritative
            // post-attach repaint.
            reseedAllVisiblePanes(
                RuntimeRefreshGuard(
                    generation = connectGeneration,
                    target = target,
                    client = client,
                ),
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

    /**
     * Issue #178: same-host fast-switch path. Builds a fresh [TmuxClient]
     * against the already-connected [session] (no new SSH handshake),
     * attaches it, and updates the registry / target state. Mirrors the
     * tmux-side wiring inside [runConnect] but skips the entire
     * raw SSH connect round-trip and reuses a lease from [SshLeaseManager].
     *
     * Pre-condition: caller already moved the previous tmux/UI runtime
     * into [runtimeCache] via [deactivateCurrentRuntimeToCache], so the
     * cached runtime owns its own lease and [clientRef] is null.
     */
    private suspend fun runFastSessionSwitch(
        target: ConnectionTarget,
        attempt: Int,
        trigger: TmuxConnectTrigger,
        startedAtMs: Long,
    ) {
        // Issue #440: clear any prior failure so the retry decision keys off
        // this attempt only (mirrors [runConnect]).
        lastConnectFailureCause = null
        try {
            val lease = acquireLeaseForTmux(
                target = target,
                attempt = attempt,
                trigger = trigger,
                startedAtMs = startedAtMs,
                preferReuseLog = true,
            ) ?: return
            val session = lease.session
            if (!session.isConnected) {
                // The previously-live session died between the
                // eligibility check and now. Fall back to the full
                // teardown + reconnect path so the user still ends up on
                // the requested session instead of a Failed state (#178).
                //
                // Issue #437 (slice A): this is no longer a warm switch —
                // we are doing a genuine SSH reconnect — so escalate the
                // UI from [Switching] to the full-screen [Connecting]
                // overlay and drop the now-stale previous frame. The
                // overlay is correct here: the user really is waiting on a
                // fresh handshake, not a same-host control-client swap.
                withContext(NonCancellable) {
                    runCatching { lease.release() }
                }
                sessionRef = null
                _connectionStatus.value = ConnectionStatus.Connecting(
                    target.host,
                    target.port,
                    target.user,
                )
                _panes.value = emptyList()
                runConnect(target, attempt, trigger)
                return
            }
            leaseRef = lease
            sessionRef = session
            val client = createTmuxClient(session, target.sessionName, target.startDirectory)
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
            recordWarmSwitchMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                name = "warm_switch_tmux_shell_attached",
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
            recordWarmSwitchMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                name = "warm_switch_connect_ready",
                trigger = trigger,
                detail = "paneCount=${_panes.value.size}",
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
        recordWarmSwitchMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = startedAtMs,
            name = "warm_switch_panes_ready",
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
        // Issue #440: remember why this attempt failed so the auto-retry
        // loop can tell a transient transport drop (worth retrying with
        // backoff) apart from a non-retryable config error (bad auth,
        // unknown host, missing key) that should fall straight back to the
        // manual Reconnect affordance.
        lastConnectFailureCause = cause
        // Issue #465: an "open failed" — the pooled SSH transport is alive but
        // refuses to open the new `tmux -CC` channel/shell — is the dead-end
        // the maintainer hit: every Reconnect reused the SAME poisoned
        // transport (the lease pool reuses any session that still reports
        // `isConnected`), so `client.connect()` threw "open failed" forever and
        // the only escape was a force-close. Evict the pooled lease here so the
        // NEXT acquire opens a fresh transport that can open the channel, and
        // force-preserve the reconnect target so the Reconnect affordance has
        // something to retry even when this was the very first connect.
        val openFailed = isChannelOpenFailure(cause)
        if (openFailed) {
            withContext(NonCancellable) {
                runCatching { sshLeaseManager.disconnect(target.toSshLeaseTarget().leaseKey) }
            }
            Log.w(
                ISSUE_145_RECONNECT_TAG,
                "tmux-connect-open-failed evicted poisoned lease so Reconnect opens fresh; " +
                    "${targetLogFields(target)} attempt=$attempt",
            )
        }
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
        connectingTarget = if (preserveReconnectTarget || openFailed) target else null
        refreshReconnectAvailability()
        _connectionStatus.value = ConnectionStatus.Failed(message)
    }

    /**
     * Issue #465: true when [cause] is a channel/shell "open failed" against an
     * otherwise-live SSH transport. This is the case where the pooled
     * connection must be EVICTED (not merely released back to the pool) so the
     * next [reconnect] opens a fresh transport — a transport stuck refusing new
     * channels never self-heals on its own because it still reports
     * `isConnected`, so the lease pool would keep handing it back.
     *
     * Matched on the message text rather than an exception type because the
     * failure surfaces as a [com.pocketshell.core.tmux.TmuxClientException]
     * wrapping the sshj `ConnectionException` whose message is the bare
     * "open failed" string. We walk the cause chain so a deeper wrap still
     * matches.
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
     * Issue #440: true when [cause] describes a connect failure that will
     * never succeed by waiting and retrying — bad credentials, an unknown
     * host, or a missing private key. Auto-reconnect short-circuits to the
     * manual Reconnect affordance for these instead of burning the whole
     * backoff schedule.
     *
     * We classify by walking the cause chain and matching the wrapped
     * exception's simple class name. [com.pocketshell.core.ssh.SshException]
     * preserves the original sshj / java.net cause on [Throwable.cause], so
     * an authentication or DNS failure surfaces a recognisable type a few
     * links down. Matching on the name (rather than importing the sshj
     * hierarchy into the app module) keeps this classification self-
     * contained and resilient to the exact wrapper depth.
     *
     * Deliberately NOT treated as non-retryable: connection refused,
     * connect timeouts, and generic transport drops. Those are exactly the
     * transient network blips the maintainer wants recovered automatically
     * (a host briefly unreachable while rebooting, Wi-Fi handover, etc.), so
     * they stay on the retry-with-backoff path.
     */
    private fun isNonRetryableConnectFailure(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val simpleName = current.javaClass.simpleName
            if (simpleName in NON_RETRYABLE_FAILURE_CLASS_NAMES) return true
            // The key-not-found case is surfaced as a plain IOException with a
            // descriptive message (see [SshConnection]); a literal type match
            // would be too broad (IOException also covers transient socket
            // tear-downs), so we narrow it on the message text.
            val message = current.message
            if (message != null && message.contains(MISSING_KEY_MESSAGE_FRAGMENT, ignoreCase = true)) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Issue #440: a short, user-facing reason for a non-retryable connect
     * failure, used in the [ConnectionStatus.Failed] message that replaces
     * the backoff loop. Keeps the band actionable ("authentication failed —
     * check your key") instead of leaking the raw exception type.
     */
    private fun nonRetryableReason(cause: Throwable?): String {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            when (current.javaClass.simpleName) {
                "UserAuthException" -> return "authentication failed"
                "UnknownHostException" -> return "host could not be resolved"
            }
            if (current.message?.contains(MISSING_KEY_MESSAGE_FRAGMENT, ignoreCase = true) == true) {
                return "private key file not found"
            }
            current = current.cause
        }
        return "connection cannot be retried"
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

    private fun recordWarmSwitchMilestone(
        attempt: Int,
        target: ConnectionTarget,
        startedAtMs: Long,
        name: String,
        trigger: TmuxConnectTrigger,
        paneId: String? = null,
        detail: String = "",
    ) {
        if (trigger != TmuxConnectTrigger.FastSwitch) return
        val elapsedMs = SystemClock.elapsedRealtime() - startedAtMs
        val eventDetail = buildString {
            append("attempt=").append(attempt)
            if (detail.isNotBlank()) append(' ').append(detail)
        }
        TmuxSessionLatencyTelemetry.record(
            name = name,
            durationMs = elapsedMs,
            sessionName = target.sessionName,
            paneId = paneId,
            trigger = trigger,
            detail = eventDetail,
        )
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-$name attempt=$attempt trigger=${trigger.logValue} " +
                targetLogFields(target) +
                " elapsedMs=$elapsedMs" +
                if (paneId != null) " pane=$paneId" else "" +
                if (detail.isNotBlank()) " $detail" else "",
        )
    }

    private fun recordWarmSwitchMilestone(
        milestone: AttachMilestone,
        name: String,
        paneId: String? = null,
        detail: String = "",
    ) {
        val target = activeTarget ?: return
        recordWarmSwitchMilestone(
            attempt = milestone.attempt,
            target = target,
            startedAtMs = milestone.startedAtMs,
            name = name,
            trigger = milestone.trigger,
            paneId = paneId,
            detail = detail,
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
        bindClientObservers(client)
    }

    private fun bindClientObservers(client: TmuxClient) {
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
        disconnectedJob?.cancel()
        disconnectedJob = bridgeScope.launch {
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
                registeredHostId?.let { activeTmuxClients.unregister(it) }
                registeredHostId = null
                val reason = "Disconnected from ${current.user}@${current.host}:${current.port}. " +
                    "Tap Reconnect to retry."
                val target = activeTarget ?: connectingTarget
                if (target == null) {
                    _connectionStatus.value = ConnectionStatus.Failed(reason)
                } else {
                    scheduleAutoReconnect(target, reason)
                }
            }
        }
    }

    private fun scheduleAutoReconnect(
        target: ConnectionTarget,
        reason: String,
        trigger: TmuxConnectTrigger = TmuxConnectTrigger.AutoReconnect,
    ) {
        if (!appActive) {
            activeTarget = target
            connectingTarget = null
            refreshReconnectAvailability()
            _connectionStatus.value = ConnectionStatus.Failed(reason)
            return
        }
        if (autoReconnectJob?.isActive == true) return
        activeTarget = target
        connectingTarget = null
        refreshReconnectAvailability()
        val delays = autoReconnectDelaysMs.ifEmpty { listOf(0L) }
        autoReconnectJob = viewModelScope.launch {
            for ((index, delayMs) in delays.withIndex()) {
                val generation = nextConnectGeneration()
                latestConnectIntent = ConnectIntent(
                    target = target,
                    trigger = trigger,
                    generation = generation,
                )
                _connectionStatus.value = ConnectionStatus.Reconnecting(
                    host = target.host,
                    port = target.port,
                    user = target.user,
                    attempt = index + 1,
                    maxAttempts = delays.size,
                    retryDelayMs = delayMs,
                    reason = reason,
                )
                if (delayMs > 0) delay(delayMs)
                if (!appActive) return@launch
                val attempt = TMUX_CONNECT_ATTEMPTS.incrementAndGet()
                StartupTiming.mark(
                    "tmux-connect-attempt",
                    "attempt" to attempt,
                    "hostId" to target.hostId,
                    "host" to target.host,
                    "port" to target.port,
                    "session" to target.sessionName,
                    "trigger" to trigger.logValue,
                    "requestedTrigger" to trigger.logValue,
                    "generation" to generation,
                )
                Log.i(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-connect-attempt count=$attempt trigger=${trigger.logValue} " +
                        "requestedTrigger=${trigger.logValue} generation=$generation " +
                        targetLogFields(target),
                )
                withContext(NonCancellable) {
                    closeCurrentConnectionAndJoin(preserveConnectingTarget = target)
                }
                connectingTarget = target
                refreshReconnectAvailability()
                runConnect(target, attempt, trigger)
                if (_connectionStatus.value is ConnectionStatus.Connected) {
                    autoReconnectJob = null
                    return@launch
                }
                // Issue #440: a non-retryable failure (bad auth, unknown
                // host, missing key) will not be fixed by waiting and
                // retrying, so abandon the remaining backoff steps and fall
                // straight back to the manual Reconnect affordance. The
                // user fixes the credential/host and taps Reconnect; we do
                // not make them watch three more doomed attempts.
                val failureCause = lastConnectFailureCause
                if (isNonRetryableConnectFailure(failureCause)) {
                    Log.w(
                        ISSUE_145_RECONNECT_TAG,
                        "tmux-auto-reconnect-abort reason=non-retryable " +
                            "cause=${failureCause?.javaClass?.simpleName} " +
                            "attempt=${index + 1} " + targetLogFields(target),
                    )
                    connectingTarget = target
                    refreshReconnectAvailability()
                    _connectionStatus.value = ConnectionStatus.Failed(
                        "$reason Auto reconnect stopped: ${nonRetryableReason(failureCause)}. " +
                            "Tap Reconnect to retry.",
                    )
                    autoReconnectJob = null
                    return@launch
                }
            }
            connectingTarget = target
            refreshReconnectAvailability()
            _connectionStatus.value = ConnectionStatus.Failed(
                "$reason Auto reconnect failed after ${delays.size} attempts.",
            )
            autoReconnectJob = null
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

    internal fun attachSessionForAgentRetryForTest(session: SshSession) {
        sessionRef = session
        activeTarget = ConnectionTarget(
            hostId = 0L,
            hostName = "test",
            host = "test",
            port = 0,
            user = "test",
            keyPath = "",
            passphrase = null,
            sessionName = "test",
            startDirectory = null,
        )
        refreshReconnectAvailability()
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
        refreshReconnectAvailability()
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
        startedAtMs: Long = SystemClock.elapsedRealtime(),
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
        recordWarmSwitchMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = startedAtMs,
            name = "warm_switch_start",
            trigger = TmuxConnectTrigger.FastSwitch,
        )
        connectingTarget = target
        refreshReconnectAvailability()
        // Issue #437 (slice A): mirror production — a same-host fast switch
        // enters [Switching] (inline indicator, previous frame preserved),
        // NOT the blanking full-screen [Connecting] overlay.
        _connectionStatus.value = ConnectionStatus.Switching(host, port, user)
        recordWarmSwitchMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = startedAtMs,
            name = "warm_switch_selected_session_state",
            trigger = TmuxConnectTrigger.FastSwitch,
            detail = "paneCount=${_panes.value.size}",
        )
        // Mirror production's ordering: deactivate the previous tmux/UI
        // runtime into the warm cache before binding the new one. Snapshot
        // the rendered frame first so it stays painted across the
        // deactivation (#437 slice A: no blank during a same-host switch).
        val previousFrame = _panes.value
        deactivateCurrentRuntimeToCache()
        if (previousFrame.isNotEmpty()) {
            _panes.value = previousFrame
        }
        sessionRef = session
        // Inline a simplified runFastSessionSwitch: we cannot call the
        // real method directly because it pulls tmuxClientFactory in,
        // and the test fakes the [TmuxClient] outright.
        activeTarget = target
        refreshReconnectAvailability()
        attachClient(client)
        client.connect()
        activeAttachMilestone = AttachMilestone(
            attempt = attempt,
            sessionName = sessionName,
            startedAtMs = startedAtMs,
            trigger = TmuxConnectTrigger.FastSwitch,
        )
        recordWarmSwitchMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = startedAtMs,
            name = "warm_switch_tmux_shell_attached",
            trigger = TmuxConnectTrigger.FastSwitch,
        )
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
        recordWarmSwitchMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = startedAtMs,
            name = "warm_switch_connect_ready",
            trigger = TmuxConnectTrigger.FastSwitch,
            detail = "paneCount=${_panes.value.size}",
        )
        maybeRefreshControlClientSize()
    }

    internal fun setAttachPanesReadyTimeoutForTest(timeoutMs: Long) {
        attachPanesReadyTimeoutMs = timeoutMs
    }

    internal suspend fun acquireLeaseForTmuxForTest(
        hostId: Long,
        hostName: String,
        host: String,
        port: Int,
        user: String,
        keyPath: String,
        sessionName: String,
    ): SshLease? {
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
        return acquireLeaseForTmux(
            target = target,
            attempt = TMUX_CONNECT_ATTEMPTS.incrementAndGet(),
            trigger = TmuxConnectTrigger.UserTap,
            startedAtMs = SystemClock.elapsedRealtime(),
        )
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
        trigger: TmuxConnectTrigger = TmuxConnectTrigger.UserTap,
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
        recordWarmSwitchMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = startedAtMs,
            name = "warm_switch_start",
            trigger = trigger,
        )
        connectingTarget = target
        activeTarget = target
        refreshReconnectAvailability()
        _connectionStatus.value = ConnectionStatus.Connecting(host, port, user)
        recordWarmSwitchMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = startedAtMs,
            name = "warm_switch_selected_session_state",
            trigger = trigger,
            detail = "paneCount=${_panes.value.size}",
        )
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
        recordWarmSwitchMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = startedAtMs,
            name = "warm_switch_tmux_shell_attached",
            trigger = trigger,
        )
        try {
            awaitPanesReadyForAttach(target, attempt, startedAtMs, trigger)
            connectingTarget = null
            refreshReconnectAvailability()
            _connectionStatus.value = ConnectionStatus.Connected(host, port, user)
            recordWarmSwitchMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                name = "warm_switch_connect_ready",
                trigger = trigger,
                detail = "paneCount=${_panes.value.size}",
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
        hostId: Long? = null,
    ): Boolean {
        val previous = activeTarget ?: return false
        val previousSession = sessionRef ?: return false
        if (!previousSession.isConnected) return false
        val target = ConnectionTarget(
            hostId = hostId ?: previous.hostId,
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
    private suspend fun reconcilePanes(
        refreshGuard: RuntimeRefreshGuard? = null,
    ): PaneReconcileResult {
        val client = clientRef ?: return PaneReconcileResult.NoClient
        val target = activeTarget
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) {
            return PaneReconcileResult.NoClient
        }
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
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) {
            return PaneReconcileResult.NoClient
        }

        val parsed: List<ParsedPane> = response.output.mapNotNull { parsePaneRow(it) }
        if (refreshGuard != null && parsed.isEmpty() && _panes.value.isNotEmpty()) {
            return PaneReconcileResult.Ready(_panes.value.size)
        }
        activeAttachMilestone?.let { milestone ->
            if (!milestone.firstPaneListReadyLogged) {
                milestone.firstPaneListReadyLogged = true
                recordWarmSwitchMilestone(
                    milestone = milestone,
                    name = "warm_switch_pane_list_ready",
                    detail = "paneCount=${parsed.size}",
                )
            }
        }
        val newPanes = applyParsedPanes(parsed, refreshGuard)
        preloadVisibleContentForNewPanes(newPanes, refreshGuard)
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

    private fun applyParsedPanes(
        parsed: List<ParsedPane>,
        refreshGuard: RuntimeRefreshGuard? = null,
    ): List<TmuxPaneState> {
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return emptyList()
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
                    attachTerminalProducerForPane(paneId = p.paneId, state = state, client = client)
                    activeAttachMilestone?.let { milestone ->
                        if (!milestone.firstTerminalBridgeLogged) {
                            milestone.firstTerminalBridgeLogged = true
                            recordWarmSwitchMilestone(
                                milestone = milestone,
                                name = "warm_switch_terminal_bridge_ready",
                                paneId = p.paneId,
                            )
                        }
                    }
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
            // Issue #495: before live detection round-trips, seed the
            // window's remembered agent status so a reconnect restores the
            // Conversation tab immediately (and re-selects it if the user
            // was on Conversation). The seed reads windowId off the
            // freshly-built `row` directly, so it does not depend on
            // paneRows being repopulated yet.
            seedAgentConversationFromMemory(row)
            startAgentDetectionForPane(row, refreshGuard)
        }
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return emptyList()

        // Tear down panes that disappeared. Cancel the producer + detach
        // the bridge so the TerminalSurfaceState releases its emulator
        // reference cleanly.
        val gonePaneIds = paneRows.keys - nextById.keys
        for (paneId in gonePaneIds) {
            paneProducerJobs.remove(paneId)?.cancel()
            // Issue #448: stop the new-port detector for a removed pane.
            panePortDetectorJobs.remove(paneId)?.cancel()
            paneAgentJobs.remove(paneId)?.cancel()
            paneAgentTailGenerations.remove(paneId)
            paneAgentInputs.remove(paneId)
            paneAgentNullDetections.remove(paneId)
            paneInputJobs.remove(paneId)?.cancel()
            paneInputQueues.remove(paneId)?.close()
            paneSurfaceRecoveryTimestamps.remove(paneId)
            paneRows[paneId]?.terminalState?.detachExternalProducer()
            paneRows.remove(paneId)
        }
        filterAgentConversationsToPaneIds(nextById.keys)
        paneRows.putAll(nextById)
        _panes.value = nextById.values.toList()
        return newRows
    }

    private fun attachTerminalProducerForPane(
        paneId: String,
        state: TerminalSurfaceState,
        client: TmuxClient,
    ) {
        runCatching {
            state.attachExternalProducer(
                scope = bridgeScope,
                stdout = client.outputFor(paneId).map { it.data },
                // tmux -CC has no per-pane PTY fd, so the terminal's
                // input queue is bridged to tmux `send-keys`.
                remoteStdin = inputSinkForPane(paneId),
                suppressQueryResponses = true,
                // Issue #468: buffer live %output behind the seed gate until
                // preloadVisibleContentForNewPanes applies the capture-pane
                // snapshot, so the snapshot's ESC[2J clear cannot wipe live
                // deltas and strand frames. The gate opens when the seed lands
                // (or via the seed-failure fallback below).
                awaitSeed = true,
            )
        }.onSuccess { job ->
            paneProducerJobs[paneId] = job
            // Issue #448 (epic #432 slice C): tap the same shared output
            // flow for new-port detection whenever a pane's producer is
            // (re)attached — cold attach, warm switch, surface recreate.
            startPortDetectionForPane(paneId = paneId, client = client)
        }.onFailure { cause ->
            Log.w(
                ISSUE_145_RECONNECT_TAG,
                "tmux-terminal-producer-attach-failed pane=$paneId",
                cause,
            )
        }
    }

    /**
     * Issue #448 (epic #432 slice C): start the new-port detector for a
     * pane. A parallel collector on the same hot [TmuxClient.outputFor]
     * flow the terminal already consumes (it is a `MutableSharedFlow`, so
     * a second collector does not disturb the terminal producer). Each
     * decoded chunk is fed to [portDetector]; a regex candidate is
     * confirmed with a single `ss` scan over the live SSH session before
     * the overlay is surfaced. No timer, no poll — purely event-driven
     * and foreground-gated (the flow is only collected while attached).
     */
    private fun startPortDetectionForPane(paneId: String, client: TmuxClient) {
        if (panePortDetectorJobs[paneId]?.isActive == true) return
        panePortDetectorJobs[paneId]?.cancel()
        panePortDetectorJobs[paneId] = bridgeScope.launch {
            client.outputFor(paneId).collect { event ->
                if (!appActive) return@collect
                val text = runCatching { String(event.data, Charsets.UTF_8) }.getOrNull()
                    ?: return@collect
                val candidates = portDetector.scan(text)
                for (candidate in candidates) {
                    confirmAndSurfaceDetectedPort(candidate.port)
                }
            }
        }
    }

    /**
     * Issue #448: confirm a regex candidate is actually in `LISTEN` via a
     * single [PortScanner.scan] over the session's SSH transport, then —
     * if confirmed and not already resolved — surface the overlay. A
     * candidate that is not actually listening (echoed/old URL) is
     * released so a later real bind of the same port can still be offered.
     */
    private suspend fun confirmAndSurfaceDetectedPort(port: Int) {
        // Don't replace an overlay the user hasn't acted on yet.
        if (_detectedPort.value != null) {
            portDetector.confirmFailed(port)
            return
        }
        val session = sessionRef ?: run {
            portDetector.confirmFailed(port)
            return
        }
        val listening = runCatching { PortScanner.scan(session) }
            .getOrDefault(emptyList())
            .any { it.port == port }
        if (!appActive) {
            // Went to background mid-confirm — don't pop an overlay the
            // user can't see; release so it can re-fire next foreground.
            portDetector.confirmFailed(port)
            return
        }
        if (!listening) {
            portDetector.confirmFailed(port)
            return
        }
        if (portDetector.confirmed(port) && _detectedPort.value == null) {
            _detectedPort.value = port
        }
    }

    /**
     * Issue #448: user dismissed (or auto-dismissed) the forward overlay.
     * Suppress re-prompting this port for the session and clear the state.
     */
    public fun dismissDetectedPort() {
        _detectedPort.value?.let { portDetector.dismissed(it) }
        _detectedPort.value = null
    }

    /**
     * Issue #448: user accepted the overlay. Suppress re-prompting this
     * port for the session, clear the overlay, and return the port so the
     * screen can navigate to the prefilled port-forward panel
     * (#447 `AppDestination.PortForwardPanel.prefillRemotePort`). Returns
     * null if the overlay was already cleared.
     */
    public fun acceptDetectedPort(): Int? {
        val port = _detectedPort.value ?: return null
        portDetector.forwarded(port)
        _detectedPort.value = null
        return port
    }

    /**
     * Recover local terminal-view state without touching the SSH/tmux
     * transport. IME, resize, render, and local input bridge failures can
     * leave the embedded Termux view or its bridge in a bad state while the
     * control client is still alive; those failures must not flip
     * [ConnectionStatus] or enter the reconnect loop.
     */
    public fun reportTerminalSurfaceFailure(paneId: String, cause: Throwable) {
        val existing = paneRows[paneId] ?: return
        // Already in the actionable error state — do nothing. The user
        // recovers via [recreateTerminalSurface] (the "Recreate terminal"
        // control). Re-running the recovery here would just resume the
        // storm we already stopped.
        if (existing.surfaceError) return

        // Issue #423: count how many surface failures landed inside the
        // sliding window. Repeated failures in a short burst are a recovery
        // storm — the symptom the maintainer hit when opening the keyboard
        // after a long dictated Codex prompt: the surface redraws, fails,
        // gets recreated, fails again, and the terminal never settles.
        val now = SystemClock.elapsedRealtime()
        val timestamps = paneSurfaceRecoveryTimestamps.getOrPut(paneId) { ArrayDeque() }
        synchronized(timestamps) {
            timestamps.addLast(now)
            while (timestamps.isNotEmpty() && now - timestamps.first() > SURFACE_RECOVERY_WINDOW_MS) {
                timestamps.removeFirst()
            }
        }
        val recentFailures = synchronized(timestamps) { timestamps.size }

        Log.w(
            ISSUE_145_RECONNECT_TAG,
            "tmux-terminal-surface-recover pane=$paneId status=${_connectionStatus.value} " +
                "recentFailures=$recentFailures",
            cause,
        )

        paneProducerJobs.remove(paneId)?.cancel()
        paneInputJobs.remove(paneId)?.cancel()
        paneInputQueues.remove(paneId)?.close()
        runCatching { existing.terminalState.detachExternalProducer() }

        if (recentFailures >= SURFACE_RECOVERY_STORM_THRESHOLD) {
            // Recovery storm: stop re-attaching the broken surface. Flip the
            // pane to an actionable error state. Crucially we do NOT touch
            // the SSH/tmux transport — the control client stays connected so
            // the user can keep navigating, switch panes, and recreate this
            // surface on demand.
            Log.w(
                ISSUE_145_RECONNECT_TAG,
                "tmux-terminal-surface-error pane=$paneId recentFailures=$recentFailures " +
                    "thresholdMs=$SURFACE_RECOVERY_WINDOW_MS",
            )
            val errored = existing.copy(surfaceError = true)
            paneRows[paneId] = errored
            _panes.update { rows ->
                rows.map { row -> if (row.paneId == paneId) errored else row }
            }
            return
        }

        val replacementState = TerminalSurfaceState()
        val client = clientRef
        if (client != null && !client.disconnected.value) {
            attachTerminalProducerForPane(
                paneId = paneId,
                state = replacementState,
                client = client,
            )
        }
        val recovered = existing.copy(terminalState = replacementState, surfaceError = false)
        paneRows[paneId] = recovered
        _panes.update { rows ->
            rows.map { row -> if (row.paneId == paneId) recovered else row }
        }
        reseedRecoveredSurface(recovered, client)
    }

    /**
     * Issue #468: re-seed a freshly re-attached (recovered) terminal surface
     * from a `capture-pane` snapshot so it self-recovers to the correct grid
     * without a reconnect, and open its seed gate so buffered live `%output`
     * flushes in order. The producer was attached with [awaitSeed] = true, so
     * without this the recovered surface would stay blank with live output
     * buffered behind a never-opened gate.
     */
    private fun reseedRecoveredSurface(pane: TmuxPaneState, client: TmuxClient?) {
        if (client != null && !client.disconnected.value) {
            bridgeScope.launch { seedPrewarmedPane(client, pane) }
        } else {
            pane.terminalState.openSeedGateWithoutSeed()
        }
    }

    /**
     * Issue #423: user-driven recovery from the terminal-surface error
     * state (the "Recreate terminal" control). Clears the storm counter,
     * builds a fresh [TerminalSurfaceState], and re-attaches it to the
     * still-live tmux client. This is the SSH-safe alternative to a
     * reconnect: the transport is untouched, only the local Termux view is
     * rebuilt. No-op if the pane is gone or was never in the error state.
     */
    public fun recreateTerminalSurface(paneId: String) {
        val existing = paneRows[paneId] ?: return
        paneSurfaceRecoveryTimestamps.remove(paneId)
        paneProducerJobs.remove(paneId)?.cancel()
        paneInputJobs.remove(paneId)?.cancel()
        paneInputQueues.remove(paneId)?.close()
        runCatching { existing.terminalState.detachExternalProducer() }

        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-terminal-surface-recreate pane=$paneId status=${_connectionStatus.value}",
        )

        val replacementState = TerminalSurfaceState()
        val client = clientRef
        if (client != null && !client.disconnected.value) {
            attachTerminalProducerForPane(
                paneId = paneId,
                state = replacementState,
                client = client,
            )
        }
        val recovered = existing.copy(terminalState = replacementState, surfaceError = false)
        paneRows[paneId] = recovered
        _panes.update { rows ->
            rows.map { row -> if (row.paneId == paneId) recovered else row }
        }
        reseedRecoveredSurface(recovered, client)
    }

    @androidx.annotation.VisibleForTesting
    internal fun reportTerminalSurfaceFailureForTest(paneId: String, cause: Throwable) {
        reportTerminalSurfaceFailure(paneId, cause)
    }

    private suspend fun preloadVisibleContentForNewPanes(
        newPanes: List<TmuxPaneState>,
        refreshGuard: RuntimeRefreshGuard? = null,
    ) {
        // Issue #468: each pane's live %output producer was attached with the
        // seed gate closed (awaitSeed). appendRemoteOutput opens the gate when
        // a snapshot lands; any pane we bail on (capture failed, runtime
        // superseded, exception) must still have its gate opened so buffered
        // live output flushes in order rather than being swallowed.
        val seededPaneIds = HashSet<String>()
        val client = clientRef ?: run {
            for (pane in newPanes) pane.terminalState.openSeedGateWithoutSeed()
            return
        }
        try {
            for (pane in newPanes) {
                if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return
                if (seedPaneFromCapture(client, pane, refreshGuard, recordMilestone = true)) {
                    seededPaneIds.add(pane.paneId)
                }
            }
        } finally {
            // Issue #468: open the gate for every pane that did not get a seed
            // applied (capture failed/empty, runtime superseded mid-loop, or an
            // exception), so buffered live output is flushed in order instead
            // of being swallowed.
            for (pane in newPanes) {
                if (pane.paneId !in seededPaneIds) {
                    pane.terminalState.openSeedGateWithoutSeed()
                }
            }
        }
    }

    /**
     * Issue #553: re-seed every visible pane's current content after the
     * reattach handshake completes.
     *
     * tmux `-CC` control mode does NOT re-emit a pane's existing content when
     * a fresh control client attaches — it only streams the *subsequent*
     * incremental `%output` deltas. (Verified against tmux: a reattach yields
     * only the new `\033[..H` rewrites, never the static frame that was
     * already on screen, and `refresh-client` does not force a re-dump.) So
     * after a reconnect the local emulator is empty and only the live deltas
     * (e.g. an agent's per-second status/timer line) paint against black — the
     * exact "blank except a timer" symptom the maintainer reported.
     *
     * [preloadVisibleContentForNewPanes] already seeds panes that are *new* in
     * a reconcile. This method closes the gap for panes that are *reused*
     * across a reconcile (a `%layout-change` round-trip racing the first
     * post-attach reconcile, or any reattach that keeps the [TmuxPaneState]
     * rows): it re-captures and re-applies the snapshot for the panes
     * currently rendered, so EVERY reconnect repaints the full pane content
     * rather than leaving it blank. Idempotent and cheap: the snapshot's
     * leading `ESC[2J` clear means a redundant re-seed cleanly replaces the
     * same frame in place instead of duplicating it.
     */
    private suspend fun reseedAllVisiblePanes(refreshGuard: RuntimeRefreshGuard? = null) {
        val client = clientRef ?: return
        val panes = paneRows.values.toList()
        for (pane in panes) {
            if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return
            // [seedPaneFromCapture] feeds the snapshot through
            // [TerminalSurfaceState.appendRemoteOutput], which is safe to call
            // on an already-open gate (it is a feed + an open no-op), so a pane
            // that was already seeded as "new" simply gets its current frame
            // re-painted in place.
            seedPaneFromCapture(client, pane, refreshGuard, recordMilestone = false)
        }
    }

    /**
     * Capture a single pane's current content with `capture-pane -p -e` and
     * feed it (with the restored cursor) into the pane's emulator, restoring
     * the visible screen + colors after a tmux reattach. Returns true when a
     * snapshot was actually applied. Shared by the new-pane preload
     * ([preloadVisibleContentForNewPanes]) and the all-visible-pane reseed
     * ([reseedAllVisiblePanes]).
     */
    private suspend fun seedPaneFromCapture(
        client: TmuxClient,
        pane: TmuxPaneState,
        refreshGuard: RuntimeRefreshGuard?,
        recordMilestone: Boolean,
    ): Boolean {
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
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return false
        if (response == null || response.isError || response.output.isEmpty()) return false
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
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return false
        val appendStartedAtMs = SystemClock.elapsedRealtime()
        pane.terminalState.appendRemoteOutput(
            response.output.toTerminalViewportBytes(cursor),
        )
        if (recordMilestone) {
            activeAttachMilestone?.let { milestone ->
                if (!milestone.firstCaptureReadyLogged) {
                    milestone.firstCaptureReadyLogged = true
                    recordWarmSwitchMilestone(
                        milestone = milestone,
                        name = "warm_switch_terminal_capture_ready",
                        paneId = pane.paneId,
                        detail = "lines=${response.output.size}",
                    )
                }
            }
        }
        TmuxSessionLatencyTelemetry.record(
            name = "terminal_output_append_to_buffer",
            durationMs = SystemClock.elapsedRealtime() - appendStartedAtMs,
            sessionName = activeTarget?.sessionName,
            paneId = pane.paneId,
            trigger = activeAttachMilestone?.trigger,
        )
        return true
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

    /**
     * Issue #495: persist the current agent verdict + tab choice for the
     * pane's window into [agentSessionMemory], keyed by stable
     * host/session/window identity. Called whenever a pane's conversation
     * state lands a detection or the user flips its tab, so a later
     * reconnect can seed the window immediately. No-op when there is no
     * detection (nothing worth remembering) or the window/target identity
     * is not yet known.
     */
    private fun rememberAgentStatusForPane(paneId: String) {
        val target = activeTarget ?: return
        val windowId = paneRows[paneId]?.windowId ?: return
        if (windowId.isBlank()) return
        val state = _agentConversations.value[paneId] ?: return
        val detection = state.detection ?: return
        agentSessionMemory.remember(
            hostId = target.hostId,
            sessionName = target.sessionName,
            windowId = windowId,
            detection = detection,
            wasOnConversation = state.selectedTab == SessionTab.Conversation,
        )
    }

    /**
     * Issue #495: drop the remembered agent status for the pane's window
     * after live detection has confirmed the window no longer hosts an
     * agent (the agent exited). Reconcile, don't trust forever — this stops
     * a phantom Conversation tab from reappearing on a later reconnect.
     */
    private fun forgetAgentStatusForPane(paneId: String) {
        val target = activeTarget ?: return
        val windowId = paneRows[paneId]?.windowId ?: return
        if (windowId.isBlank()) return
        agentSessionMemory.forget(
            hostId = target.hostId,
            sessionName = target.sessionName,
            windowId = windowId,
        )
    }

    /**
     * Issue #495: seed [_agentConversations] for a freshly-created pane from
     * the remembered agent status for its window, so the Conversation tab is
     * available — and, if the user was on it before the reconnect,
     * re-selected — immediately on reattach, before live re-detection
     * completes its SSH round-trip. Live detection
     * ([startAgentDetectionForPane]) then confirms/refines this seed and
     * [clearAgentDetectionForPane] reconciles it away if the agent has
     * actually exited.
     *
     * Only seeds when there is no existing conversation row for the pane
     * (a fresh attach), so it never clobbers live state mid-session.
     */
    private fun seedAgentConversationFromMemory(pane: TmuxPaneState) {
        val target = activeTarget ?: return
        if (pane.windowId.isBlank()) return
        if (_agentConversations.value.containsKey(pane.paneId)) return
        val remembered = agentSessionMemory.recall(
            hostId = target.hostId,
            sessionName = target.sessionName,
            windowId = pane.windowId,
        ) ?: return
        setAgentConversation(
            pane.paneId,
            AgentConversationUiState(
                detection = remembered.detection,
                events = emptyList(),
                selectedTab = if (remembered.wasOnConversation) {
                    SessionTab.Conversation
                } else {
                    SessionTab.Terminal
                },
                syncStatus = AgentConversationSyncStatus.Live,
            ),
        )
    }

    private fun startAgentDetectionForPane(
        pane: TmuxPaneState,
        refreshGuard: RuntimeRefreshGuard? = null,
    ) {
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
        paneAgentTailGenerations.remove(pane.paneId)
        paneAgentInputs[pane.paneId] = input
        val paneId = pane.paneId
        val guard = refreshGuard ?: RuntimeRefreshGuard(
            generation = connectGeneration,
            target = activeTarget ?: return,
            client = clientRef ?: return,
        )
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
            if (!isCurrentRuntime(guard)) return@launch
            if (detection == null) {
                handleNullAgentDetection(pane, guard)
                return@launch
            }
            // A real detection cancels any in-flight exit-confirmation count.
            paneAgentNullDetections.remove(paneId)
            startAgentConversationForPane(session, paneId, detection, guard)
        }
    }

    /**
     * Issue #554: reconcile a null agent detection for [pane].
     *
     * A window we restored optimistically from [agentSessionMemory] (the #495
     * seed) must NOT be torn back down to a plain shell on the FIRST null
     * detection after a reattach — live detection routinely reads "no agent"
     * for a beat while the agent's JSONL log / process becomes observable on
     * the fresh connection. Downgrading there is the "we forget it's an agent
     * and bounce to plain-shell-then-back" regression the maintainer reported.
     * So when the window has a remembered agent status, keep the seeded agent
     * UI and re-confirm; only treat the agent as genuinely exited after
     * [AGENT_EXIT_CONFIRMATIONS] consecutive nulls.
     *
     * Returns true when the agent was treated as genuinely exited (the row was
     * cleared), false when the downgrade was deferred and a re-check scheduled.
     */
    private fun handleNullAgentDetection(pane: TmuxPaneState, guard: RuntimeRefreshGuard): Boolean {
        val paneId = pane.paneId
        if (shouldDeferAgentDowngrade(paneId)) {
            val nulls = (paneAgentNullDetections[paneId] ?: 0) + 1
            paneAgentNullDetections[paneId] = nulls
            if (nulls < AGENT_EXIT_CONFIRMATIONS) {
                scheduleAgentDetectionRecheck(pane, guard)
                return false
            }
        }
        // Issue #186: when a pane that previously had a detection no longer
        // does (the user exited Claude / Codex / OpenCode, or the agent
        // process died), clear the per-pane conversation state so the
        // Conversation tab disappears for this window.
        paneAgentNullDetections.remove(paneId)
        clearAgentDetectionForPane(paneId)
        return true
    }

    /**
     * Issue #554: true when a null detection for [paneId] should be deferred
     * rather than immediately downgrading the pane to a plain shell — i.e. the
     * pane's window has a remembered agent status (so we optimistically showed
     * the agent UI on reattach) AND the pane currently still shows an agent
     * detection. In that state a transient null after a reconnect is almost
     * always a detection-not-yet-warm race, not a genuine agent exit, so we
     * hold the agent UI and re-confirm before tearing it down.
     */
    private fun shouldDeferAgentDowngrade(paneId: String): Boolean {
        val target = activeTarget ?: return false
        val windowId = paneRows[paneId]?.windowId ?: return false
        if (windowId.isBlank()) return false
        val remembered = agentSessionMemory.recall(
            hostId = target.hostId,
            sessionName = target.sessionName,
            windowId = windowId,
        ) ?: return false
        // Only defer while the seeded agent UI is actually still showing; once
        // detection has confirmed an exit and the row is gone, there is
        // nothing to protect.
        return remembered.detection != null &&
            _agentConversations.value[paneId]?.detection != null
    }

    /**
     * Issue #554: re-run per-pane agent detection after a short delay to
     * confirm a (possibly transient) null verdict before downgrading a
     * remembered agent window. Forces a fresh round-trip by clearing the
     * detection de-dup input for the pane.
     */
    private fun scheduleAgentDetectionRecheck(pane: TmuxPaneState, guard: RuntimeRefreshGuard) {
        val paneId = pane.paneId
        paneAgentJobs.remove(paneId)?.cancel()
        paneAgentJobs[paneId] = bridgeScope.launch {
            delay(AGENT_EXIT_RECHECK_DELAY_MS)
            if (!isCurrentRuntime(guard)) return@launch
            // Clear the de-dup key so [startAgentDetectionForPane] does not
            // short-circuit the re-detection as already-seen.
            paneAgentInputs.remove(paneId)
            val current = paneRows[paneId] ?: return@launch
            startAgentDetectionForPane(current, guard)
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
        // Issue #495: live detection says this window no longer hosts an
        // agent (the user exited Claude/Codex/OpenCode). Reconcile the
        // remembered status so a later reconnect does not resurrect a
        // phantom Conversation tab. Forget BEFORE dropping the row so the
        // window lookup still resolves.
        forgetAgentStatusForPane(paneId)
        updateAgentConversation(paneId) { current ->
            if (current.detection == null) current else null
        }
    }

    private suspend fun startAgentConversationForPane(
        session: SshSession,
        paneId: String,
        detection: AgentDetection,
        refreshGuard: RuntimeRefreshGuard? = null,
    ) {
        val lineCount = runCatching { agentRepository.lineCount(session, detection) }.getOrDefault(0L)
        val initialEvents = runCatching {
            agentRepository.readInitialEvents(session, detection)
        }.getOrDefault(emptyList())
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return
        markAgentTailLive(paneId, detection, initialEvents)
        // Issue #160: OpenCode now tails its JSONL via `session.tail`
        // identically to Claude and Codex. No more polling branch — the
        // tmux pane gets the same real-time refresh as the raw-SSH route.
        val followJob = agentRepository.tailEventsFromLine(session, detection, lineCount) { event ->
            appendAgentEvents(paneId, listOf(event))
        }
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) {
            followJob?.cancel()
            return
        }
        if (followJob != null) {
            val tailGeneration = nextAgentTailGeneration(paneId)
            paneAgentJobs[paneId] = followJob
            paneAgentTailGenerations[paneId] = tailGeneration
            followJob.invokeOnCompletion { cause ->
                if (cause is CancellationException) return@invokeOnCompletion
                bridgeScope.launch {
                    markAgentTailStopped(paneId, detection, followJob, tailGeneration, cause)
                }
            }
        } else {
            markAgentTailUnavailable(paneId, detection)
        }
    }

    private fun restartAgentConversationsForRestoredRuntime(runtime: CachedTmuxRuntime) {
        val session = runtime.session ?: return
        val guard = RuntimeRefreshGuard(
            generation = connectGeneration,
            target = activeTarget ?: return,
            client = clientRef ?: return,
        )
        runtime.agentConversations.forEach { (paneId, state) ->
            val detection = state.detection ?: return@forEach
            paneAgentJobs.remove(paneId)?.cancel()
            paneAgentTailGenerations.remove(paneId)
            paneAgentJobs[paneId] = bridgeScope.launch {
                restartAgentConversationTailForPane(
                    session = session,
                    paneId = paneId,
                    detection = detection,
                    restored = state,
                    refreshGuard = guard,
                )
            }
        }
    }

    private suspend fun restartAgentConversationTailForPane(
        session: SshSession,
        paneId: String,
        detection: AgentDetection,
        restored: AgentConversationUiState,
        refreshGuard: RuntimeRefreshGuard,
    ) {
        val lineCount = runCatching { agentRepository.lineCount(session, detection) }.getOrDefault(0L)
        val initialEvents = runCatching {
            agentRepository.readInitialEvents(session, detection)
        }.getOrDefault(restored.events)
        if (!isCurrentRuntime(refreshGuard)) return
        markRestoredAgentTailLive(paneId, detection, initialEvents)
        val followJob = agentRepository.tailEventsFromLine(session, detection, lineCount) { event ->
            appendAgentEvents(paneId, listOf(event))
        }
        if (!isCurrentRuntime(refreshGuard)) {
            followJob?.cancel()
            return
        }
        if (followJob != null) {
            val tailGeneration = nextAgentTailGeneration(paneId)
            paneAgentJobs[paneId] = followJob
            paneAgentTailGenerations[paneId] = tailGeneration
            followJob.invokeOnCompletion { cause ->
                if (cause is CancellationException) return@invokeOnCompletion
                bridgeScope.launch {
                    markAgentTailStopped(paneId, detection, followJob, tailGeneration, cause)
                }
            }
        } else {
            markAgentTailUnavailable(paneId, detection)
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
        bridgeScope.launch {
            sendToAgentPaneResult(paneId, text)
        }
    }

    public suspend fun stagePromptAttachments(uris: List<Uri>): Result<List<String>> {
        val context = applicationContext
            ?: return Result.failure(IllegalStateException("Attachment staging unavailable."))
        // Issue #451: the system file picker backgrounds the app while the
        // user selects a file. Attach now behaves like Send: on a
        // not-currently-live session it lazily connects-then-uploads instead
        // of hard-failing "No live SSH session" the instant we return. #450
        // keeps the terminal SSH session alive for a bounded grace window
        // (~60s) so the common quick round-trip returns to a still-live
        // session and the upload just works. If the round-trip outran the
        // grace window (or the OS killed the socket), Attach kicks the same
        // connect-on-action primitive Send uses ([reconnect], driven by
        // [activeTarget]) and awaits the live session before uploading — the
        // draft is preserved if the (re)connect never lands within the bound.
        val session = awaitLiveSessionForAttachment()
            ?: return Result.failure(IllegalStateException("No live SSH session for attachment upload."))
        val target = activeTarget
        val scopeKey = when (target) {
            null -> "tmux-session"
            else -> "host-${target.hostId}-${target.sessionName}"
        }
        return PromptAttachmentStager(
            resolver = context.contentResolver,
            cacheDir = context.cacheDir,
        ).stage(session, scopeKey, uris)
    }

    /**
     * Issue #451: return the live terminal [SshSession] for an attachment
     * upload, lazily connecting-then-awaiting the connection the way the
     * Send path does when the session is not currently live.
     *
     * Fast path: if the session is already live ([sessionRef] connected and
     * [connectionStatus] is [ConnectionStatus.Connected]), return it
     * immediately — the #450 grace window means this is the common case for
     * a quick picker round-trip.
     *
     * Slow path (connect-on-action, mirroring Send): if the session is not
     * live (the picker round-trip outran the grace window, teardown fired,
     * or the OS killed the backgrounded socket), kick the same connect
     * primitive Send relies on. [reconnect] re-dials [activeTarget] /
     * [connectingTarget] in tmux `-CC` control mode; it returns false (no
     * known target) without throwing, so calling it unconditionally is safe.
     * Then poll [connectionStatus] / [sessionRef] for the bounded interval;
     * as soon as the status flips back to [ConnectionStatus.Connected] with a
     * connected [sessionRef], return it. On timeout return null so the caller
     * surfaces the "No live SSH session" error with the draft preserved.
     *
     * This is foreground-only and bounded — no scheduler, no background work.
     */
    private suspend fun awaitLiveSessionForAttachment(): SshSession? {
        liveSessionForAttachmentOrNull()?.let { return it }
        // Connect-on-action: drive a (re)connect like Send does. No-op when a
        // connect is already in flight, so it just falls through to the wait.
        reconnect()
        return withTimeoutOrNull(ATTACH_SESSION_WAIT_TIMEOUT_MS) {
            while (currentCoroutineContext().isActive) {
                liveSessionForAttachmentOrNull()?.let { return@withTimeoutOrNull it }
                delay(ATTACH_SESSION_WAIT_POLL_MS)
            }
            null
        }
    }

    private fun liveSessionForAttachmentOrNull(): SshSession? {
        if (_connectionStatus.value !is ConnectionStatus.Connected) return null
        return sessionRef?.takeIf { it.isConnected }
    }

    internal suspend fun sendToAgentPaneResult(paneId: String, text: String): Result<Unit> {
        val payload = text.trim()
        if (payload.isEmpty()) return Result.success(Unit)
        val current = _agentConversations.value[paneId]
            ?: return Result.failure(IllegalStateException("No agent conversation for pane $paneId."))
        val detection = current.detection
            ?: return Result.failure(IllegalStateException("No detected agent for pane $paneId."))
        // Issue #494: insert the optimistic pending turn FIRST — before any
        // delivery attempt — so the Conversation tab shows the user's own
        // message the instant they hit Send, not after the JSONL round-trip.
        // The turn starts as [MessageSendState.Pending] ("sending…") and is
        // reconciled away when the real transcript entry arrives via the
        // tail. If the send can't be delivered the turn flips to
        // [MessageSendState.Failed] (with a retry affordance) so the user's
        // text is never silently lost.
        val optimisticId = "$OPTIMISTIC_USER_MESSAGE_ID_PREFIX${System.nanoTime()}"
        val optimistic = ConversationEvent.Message(
            // Issue #160 round 2: see [OPTIMISTIC_USER_MESSAGE_ID_PREFIX].
            id = optimisticId,
            agent = detection.agent,
            atMillis = System.currentTimeMillis(),
            role = ConversationRole.User,
            text = payload,
            sendState = com.pocketshell.core.agents.MessageSendState.Pending,
        )
        appendAgentEvents(paneId, listOf(optimistic))
        if (_connectionStatus.value !is ConnectionStatus.Connected) {
            markOptimisticSendFailed(paneId, optimisticId)
            return Result.failure(IllegalStateException("Session is disconnected."))
        }
        val result = sendAgentPayloadToPaneResult(paneId, payload, detection.agent)
        if (result.isFailure) {
            markOptimisticSendFailed(paneId, optimisticId)
        }
        return result
    }

    /**
     * Issue #494: flip the optimistic user turn [optimisticId] in pane
     * [paneId] to [com.pocketshell.core.agents.MessageSendState.Failed].
     */
    private fun markOptimisticSendFailed(paneId: String, optimisticId: String) {
        updateAgentConversation(paneId) { current ->
            current.copy(events = current.events.markOptimisticFailed(optimisticId))
        }
    }

    /**
     * Issue #494: retry a previously-failed optimistic user turn in pane
     * [paneId]. Drops the failed placeholder and re-sends its text, so there
     * is no double-send (the failed turn is removed before the new send).
     */
    public fun retryFailedAgentSend(paneId: String, optimisticId: String) {
        val failed = _agentConversations.value[paneId]?.events
            ?.filterIsInstance<ConversationEvent.Message>()
            ?.firstOrNull {
                it.id == optimisticId &&
                    it.sendState == com.pocketshell.core.agents.MessageSendState.Failed
            } ?: return
        updateAgentConversation(paneId) { current ->
            current.copy(events = current.events.filterNot { it.id == optimisticId })
        }
        bridgeScope.launch {
            sendToAgentPaneResult(paneId, failed.text)
        }
    }

    internal suspend fun sendAgentPayloadToPaneResult(
        paneId: String,
        payload: String,
        agent: AgentKind,
    ): Result<Unit> {
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        val client = clientRef
            ?: return Result.failure(IllegalStateException("No active tmux client for pane input."))
        if (client.disconnected.value) {
            return Result.failure(IllegalStateException("Tmux client is disconnected."))
        }
        return runCatching {
            if (payloadBytes.size > TMUX_PASTE_BODY_CHUNK_BYTES || containsLineBreak(payloadBytes)) {
                sendBracketedPaste(client, paneId, payloadBytes)
            } else if (payload.isNotEmpty()) {
                client.sendCommand("send-keys -l -t $paneId -- '${escapeSingleQuoted(payload)}'")
                    .throwIfTmuxError("type agent input into pane $paneId")
            }
            delayBeforeAgentSubmit(agent)
            client.sendCommand("send-keys -t $paneId Enter")
                .throwIfTmuxError("submit pasted agent input")
        }
    }

    /**
     * Issue #526: wait between typing the composer's message text into the
     * agent pane and pressing the submit Enter, so the Enter never races
     * ahead of the agent TUI finishing its paste ingestion (which left the
     * message sitting unsent until the user pressed Enter manually).
     *
     * The wait is the user-configurable
     * [com.pocketshell.app.settings.AppSettings.agentSubmitEnterDelayMs]
     * (Settings → Terminal, default 150ms). Codex keeps a known-needed floor
     * of [CODEX_AGENT_SUBMIT_DELAY_MS] so lowering the global delay can't
     * regress the Codex TUI that motivated the original delay; the effective
     * Codex wait is `max(configured, Codex floor)`.
     *
     * A zero effective delay sends the Enter back-to-back (the pre-#526
     * behaviour) without a spurious `delay(0)` suspension.
     */
    private suspend fun delayBeforeAgentSubmit(agent: AgentKind) {
        val configured = agentSubmitEnterDelayMsOverrideForTest
            ?: settingsRepository?.settings?.value?.agentSubmitEnterDelayMs
            ?: com.pocketshell.app.settings.AppSettings.DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS
        val effective = if (agent == AgentKind.Codex) {
            maxOf(configured.toLong(), CODEX_AGENT_SUBMIT_DELAY_MS)
        } else {
            configured.toLong()
        }
        if (effective > 0L) {
            delay(effective)
        }
    }

    public fun selectSessionTab(paneId: String, tab: SessionTab) {
        updateAgentConversation(paneId) { current ->
            if (tab == SessionTab.Conversation && current.detection == null) {
                current
            } else {
                current.copy(selectedTab = tab)
            }
        }
        // Issue #495: remember the tab choice keyed by window so a reconnect
        // puts the user back on whichever tab they were on.
        rememberAgentStatusForPane(paneId)
    }

    /**
     * Issue #154 (acceptance criterion #5): hoist the per-pane
     * conversation search query into the ViewModel so it survives
     * Terminal ↔ Conversation tab switches (the previous local
     * `remember` lost the query on every tab flip). Bound to the
     * search field's `onValueChange` inside [TmuxConversationPane].
     */
    public fun setAgentSearchQuery(paneId: String, query: String) {
        updateAgentConversation(paneId) { current ->
            if (current.searchQuery == query) current else current.copy(searchQuery = query)
        }
    }

    public fun retryAgentConversationStreamForPane(paneId: String): Boolean {
        val session = sessionRef?.takeIf { it.isConnected } ?: return false
        if (_connectionStatus.value !is ConnectionStatus.Connected) return false
        val pane = paneRows[paneId] ?: return false
        val guard = RuntimeRefreshGuard(
            generation = connectGeneration,
            target = activeTarget ?: return false,
            client = clientRef ?: return false,
        )
        val currentDetection = markAgentConversationRetrying(paneId) ?: return false
        paneAgentJobs.remove(paneId)?.cancel()
        paneAgentTailGenerations.remove(paneId)
        paneAgentJobs[paneId] = bridgeScope.launch {
            retryAgentConversationForPane(
                session = session,
                pane = pane,
                currentDetection = currentDetection,
                refreshGuard = guard,
            )
        }
        return true
    }

    private suspend fun retryAgentConversationForPane(
        session: SshSession,
        pane: TmuxPaneState,
        currentDetection: AgentDetection,
        refreshGuard: RuntimeRefreshGuard,
    ) {
        val detection = runCatching {
            agentRepository.detectForPane(
                session = session,
                cwd = pane.cwd,
                paneTty = pane.paneTty,
                paneCommand = pane.currentCommand,
            )
        }.getOrNull()
        if (!isCurrentRuntime(refreshGuard)) return
        if (detection == null) {
            markAgentConversationSyncStatus(
                paneId = pane.paneId,
                detection = currentDetection,
                syncStatus = AgentConversationSyncStatus.LogUnavailable,
            )
            return
        }
        startAgentConversationForPane(session, pane.paneId, detection, refreshGuard)
    }

    private fun appendAgentEvents(paneId: String, events: List<ConversationEvent>) {
        if (events.isEmpty()) return
        updateAgentConversation(paneId) { current ->
            current.copy(events = boundedDistinctEvents(current.events + events))
        }
    }

    private fun markAgentTailStopped(
        paneId: String,
        detection: AgentDetection,
        tailJob: Job,
        tailGeneration: Long,
        cause: Throwable?,
    ) {
        if (!isCurrentAgentTail(paneId, tailJob, tailGeneration)) return
        val nextStatus = if (cause == null) {
            AgentConversationSyncStatus.Stale
        } else {
            AgentConversationSyncStatus.LogUnavailable
        }
        markAgentConversationSyncStatus(paneId, detection, nextStatus)
    }

    private fun markAgentTailUnavailable(paneId: String, detection: AgentDetection) {
        markAgentConversationSyncStatus(
            paneId = paneId,
            detection = detection,
            syncStatus = AgentConversationSyncStatus.LogUnavailable,
        )
    }

    private fun markAgentConversationSyncStatus(
        paneId: String,
        detection: AgentDetection,
        syncStatus: AgentConversationSyncStatus,
    ) {
        updateAgentConversation(paneId) { current ->
            if (current.detection != detection || current.syncStatus == syncStatus) {
                current
            } else {
                current.copy(syncStatus = syncStatus)
            }
        }
    }

    private fun markAgentConversationRetrying(paneId: String): AgentDetection? {
        var retryDetection: AgentDetection? = null
        updateAgentConversation(paneId) { current ->
            val detection = current.detection
            if (detection == null || !current.syncStatus.canRetryAgentStream) {
                current
            } else {
                retryDetection = detection
                current.copy(syncStatus = AgentConversationSyncStatus.Retrying)
            }
        }
        return retryDetection
    }

    private fun markRestoredAgentTailLive(
        paneId: String,
        detection: AgentDetection,
        fallbackEvents: List<ConversationEvent>,
    ) {
        markAgentTailLive(paneId, detection, fallbackEvents, preserveDifferentDetection = true)
    }

    private fun markAgentTailLive(
        paneId: String,
        detection: AgentDetection,
        initialEvents: List<ConversationEvent>,
        preserveDifferentDetection: Boolean = false,
    ) {
        _agentConversations.update { conversations ->
            val current = conversations[paneId]
            val updated = when {
                current == null -> AgentConversationUiState(
                    detection = detection,
                    events = boundedDistinctEvents(initialEvents),
                    selectedTab = SessionTab.Terminal,
                    syncStatus = AgentConversationSyncStatus.Live,
                )
                current.detection != detection && preserveDifferentDetection -> current
                // Issue #495: when live detection refines the SAME agent on
                // the SAME log for this window (only confidence/sessionId
                // drifted — e.g. a seeded reconnect verdict promoted from
                // RecentFile to ProcessConfirmed), keep the user's selected
                // tab. The previous unconditional reset-to-Terminal here
                // bounced a user who was in Conversation back to Terminal on
                // every reconnect, which is the bug this issue fixes.
                current.detection != detection && sameAgentSource(current.detection, detection) ->
                    current.copy(
                        detection = detection,
                        events = boundedDistinctEvents(current.events + initialEvents),
                        syncStatus = AgentConversationSyncStatus.Live,
                    )
                current.detection != detection -> AgentConversationUiState(
                    detection = detection,
                    events = boundedDistinctEvents(initialEvents),
                    selectedTab = SessionTab.Terminal,
                    syncStatus = AgentConversationSyncStatus.Live,
                )
                else -> current.copy(
                    events = boundedDistinctEvents(current.events + initialEvents),
                    syncStatus = AgentConversationSyncStatus.Live,
                )
            }
            if (updated == current) conversations else conversations + (paneId to updated)
        }
        rememberAgentStatusForPane(paneId)
    }

    /**
     * Issue #495: two detections describe the same live agent session when
     * the agent kind and the log source path match. Confidence and
     * sessionId can drift between a seeded reconnect verdict and the live
     * re-detection without meaning "a different agent" — treating that as a
     * new agent would discard the user's tab choice.
     */
    private fun sameAgentSource(left: AgentDetection?, right: AgentDetection?): Boolean =
        left != null &&
            right != null &&
            left.agent == right.agent &&
            left.sourcePath == right.sourcePath

    private fun setAgentConversation(paneId: String, state: AgentConversationUiState) {
        _agentConversations.update { it + (paneId to state) }
    }

    private fun updateAgentConversation(
        paneId: String,
        transform: (AgentConversationUiState) -> AgentConversationUiState?,
    ) {
        _agentConversations.update { conversations ->
            val current = conversations[paneId] ?: return@update conversations
            val updated = transform(current) ?: return@update conversations - paneId
            if (updated == current) conversations else conversations + (paneId to updated)
        }
    }

    private fun replaceAgentConversations(conversations: Map<String, AgentConversationUiState>) {
        _agentConversations.value = conversations
    }

    private fun clearAgentConversations() {
        _agentConversations.value = emptyMap()
    }

    private fun filterAgentConversationsToPaneIds(paneIds: Set<String>) {
        _agentConversations.update { conversations ->
            conversations.filterKeys { it in paneIds }
        }
    }

    private fun nextAgentTailGeneration(paneId: String): Long =
        nextAgentTailGeneration.incrementAndGet().toLong().also { generation ->
            paneAgentTailGenerations[paneId] = generation
        }

    private fun isCurrentAgentTail(
        paneId: String,
        tailJob: Job,
        tailGeneration: Long,
    ): Boolean =
        paneAgentJobs[paneId] === tailJob &&
            paneAgentTailGenerations[paneId] == tailGeneration

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
                syncStatus = AgentConversationSyncStatus.Live,
            ),
        )
    }

    internal fun startAgentTailForTest(
        paneId: String,
        session: SshSession,
        detection: AgentDetection,
        fromLineExclusive: Long,
    ): Job? {
        val job = agentRepository.tailEventsFromLine(session, detection, fromLineExclusive) { event ->
            appendAgentEvents(paneId, listOf(event))
        }
        if (job != null) {
            val tailGeneration = nextAgentTailGeneration(paneId)
            paneAgentJobs[paneId] = job
            paneAgentTailGenerations[paneId] = tailGeneration
            job.invokeOnCompletion { cause ->
                if (cause is CancellationException) return@invokeOnCompletion
                bridgeScope.launch {
                    markAgentTailStopped(paneId, detection, job, tailGeneration, cause)
                }
            }
        } else {
            markAgentTailUnavailable(paneId, detection)
        }
        return job
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
     * Issue #554 test seam: seed [agentSessionMemory] without the SSH/JSONL
     * round-trip so tests can assert the optimistic-reconnect reconciliation.
     */
    internal fun rememberAgentForTest(
        windowId: String,
        detection: AgentDetection,
        wasOnConversation: Boolean,
    ) {
        val target = activeTarget ?: return
        agentSessionMemory.remember(
            hostId = target.hostId,
            sessionName = target.sessionName,
            windowId = windowId,
            detection = detection,
            wasOnConversation = wasOnConversation,
        )
    }

    /**
     * Issue #554 test seam: drive the null-detection reconciliation directly
     * (the path [startAgentDetectionForPane] takes when live detection comes
     * back null) without standing up the SSH/JSONL detection round-trip.
     * Returns true when the agent was treated as exited (row cleared), false
     * when the downgrade was deferred for confirmation.
     */
    internal fun handleNullAgentDetectionForTest(paneId: String): Boolean {
        val pane = paneRows[paneId] ?: return true
        val guard = RuntimeRefreshGuard(
            generation = connectGeneration,
            target = activeTarget ?: return true,
            client = clientRef ?: return true,
        )
        return handleNullAgentDetection(pane, guard)
    }

    /**
     * Issue #495 test seam: drive the live-detection landing path
     * ([markAgentTailLive]) so tests can assert that a same-agent refinement
     * preserves the user's selected tab (and remembers the window status)
     * without standing up the SSH/JSONL detection round-trip.
     */
    internal fun markAgentTailLiveForTest(
        paneId: String,
        detection: AgentDetection,
        initialEvents: List<ConversationEvent> = emptyList(),
    ) {
        markAgentTailLive(paneId, detection, initialEvents)
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
            writeInputToPaneResult(client, paneId, bytes)
        }
    }

    internal suspend fun writeInputToPaneResult(paneId: String, bytes: ByteArray): Result<Unit> {
        if (bytes.isEmpty()) return Result.success(Unit)
        if (_connectionStatus.value !is ConnectionStatus.Connected) {
            return Result.failure(IllegalStateException("Session is disconnected."))
        }
        val client = clientRef
            ?: return Result.failure(IllegalStateException("No active tmux client for pane input."))
        if (client.disconnected.value) {
            return Result.failure(IllegalStateException("Tmux client is disconnected."))
        }
        return writeInputToPaneResult(client, paneId, bytes)
    }

    private suspend fun writeInputToPaneResult(
        client: TmuxClient,
        paneId: String,
        bytes: ByteArray,
    ): Result<Unit> = runCatching {
        sendInputBytesToPane(client, paneId, bytes)
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

    /** Pick [candidate] in the "which folder?" disambiguation chooser. */
    internal fun chooseAssistantFolder(candidate: FolderCandidate) = assistant.choose(candidate)

    /** Dismiss the "which folder?" chooser without picking. */
    public fun cancelAssistantChoice() = assistant.cancelChoice()

    /** Retry the last assistant request after a retryable model failure. */
    public fun retryAssistantAction() = assistant.retry()

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
            override suspend fun sendCommand(command: String): Result<Unit> {
                val pane = focusedPaneId
                    ?: return Result.failure(IllegalStateException("No focused pane for assistant command."))
                val payload = command + "\r"
                return writeInputToPaneResult(pane, payload.toByteArray(Charsets.UTF_8))
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
        return inputSinkForPane(
            paneId = paneId,
            client = null,
            queues = paneInputQueues,
            jobs = paneInputJobs,
        )
    }

    private fun inputSinkForPane(
        paneId: String,
        client: TmuxClient?,
        queues: MutableMap<String, TmuxPaneInputQueue>,
        jobs: MutableMap<String, Job>,
    ): OutputStream {
        val queue = queues.getOrPut(paneId) {
            TmuxPaneInputQueue(
                maxPendingBytes = TMUX_INPUT_MAX_PENDING_BYTES,
                maxBatchBytes = TMUX_INPUT_MAX_BATCH_BYTES,
            ).also { newQueue ->
                jobs[paneId] = bridgeScope.launch {
                    while (true) {
                        val batch = newQueue.takeBatch() ?: break
                        val targetClient = client ?: clientRef ?: continue
                        val sendStartedNs = System.nanoTime()
                        runCatching { sendInputBytesToPane(targetClient, paneId, batch.bytes) }
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
        //     freely. We keep each control-mode command bounded by
        //     sending one paste-start marker, a sequence of small body
        //     chunks, and one paste-end marker.
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
     * Issue #209 / #398: send [bytes] to [paneId] as a bracketed-paste
     * block via bounded `send-keys -H` commands.
     *
     * The hex payload is `1b 5b 32 30 30 7e` (`\e[200~`) + the UTF-8
     * bytes of the input + `1b 5b 32 30 31 7e` (`\e[201~`). `\r\n`
     * pairs are normalised to `\n` so the inner content uses LF only;
     * lone `\r` bytes are passed through (they are not paragraph
     * separators in dictation transcripts and we have no reason to
     * mangle them).
     *
     * If any chunk fails, either by throwing or by tmux returning
     * `%error`, the exception propagates to the caller so composer
     * surfaces can keep the unsent draft.
     */
    private suspend fun sendBracketedPaste(
        client: TmuxClient,
        paneId: String,
        bytes: ByteArray,
    ) {
        if (bytes.isEmpty()) return
        for (hex in buildBracketedPasteHexChunks(bytes)) {
            client.sendCommand("send-keys -H -t $paneId $hex")
                .throwIfTmuxError("paste chunk into pane $paneId")
        }
    }

    private fun CommandResponse.throwIfTmuxError(action: String) {
        if (!isError) return
        val detail = output.joinToString(separator = " ").trim()
        throw IllegalStateException(
            "tmux rejected $action${if (detail.isNotEmpty()) ": $detail" else ""}",
        )
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
        val attachGeneration = connectGeneration
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
            if (connectGeneration != attachGeneration) return@launch
            val currentTarget = activeTarget
            if (clientRef !== client || currentTarget == null || !sameSessionIdentity(currentTarget, target)) {
                return@launch
            }
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
            activeAttachMilestone?.let { milestone ->
                if (!milestone.firstRemoteRefreshLogged) {
                    milestone.firstRemoteRefreshLogged = true
                    recordWarmSwitchMilestone(
                        milestone = milestone,
                        name = "warm_switch_remote_refresh_complete",
                        detail = "cols=$cols rows=$rows",
                    )
                }
            }
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

    /**
     * Kill the session this screen is attached to (session dropdown →
     * Kill session → confirm) and, on a confirmed kill, broadcast it via
     * [sessionLifecycleSignals] so the folder/session tree drops the dead
     * row promptly instead of waiting for its next foreground re-probe
     * (issue #464).
     *
     * Mirrors the dashboard's #168 confirmed-kill pattern: subscribe to the
     * post-kill `%sessions-changed` / `%session-changed` notification BEFORE
     * sending so the hot-SharedFlow event can't be missed, then only emit
     * the lifecycle signal when tmux acknowledged the kill (transport OK +
     * no `%error`). A failed kill never broadcasts, so the tree keeps the
     * still-live row and its own re-probe stays authoritative.
     */
    public fun killCurrentSession() {
        val current = activeTarget ?: return
        val target = current.sessionName
        val client = clientRef
        if (client == null) {
            // No live control client to confirm against — fall back to the
            // best-effort send and still signal so the tree reconciles.
            sendLifecycleCommand("kill-session -t '${escapeSingleQuoted(target)}'")
            sessionLifecycleSignals?.emitKilled(current.hostId, target)
            return
        }
        bridgeScope.launch {
            val clientHash = System.identityHashCode(client)
            Log.i(
                ISSUE_464_KILL_TAG,
                "kill-session-start host=${current.hostId} name=$target clientHash=$clientHash",
            )
            val eventDeferred = bridgeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                withTimeoutOrNull(KILL_SESSION_EVENT_WAIT_MS) {
                    client.events.first { event ->
                        event is ControlEvent.SessionsChanged ||
                            (event is ControlEvent.SessionChanged && event.name == target)
                    }
                }
                Unit
            }

            val sendResult = runCatching {
                client.sendCommand("kill-session -t '${escapeSingleQuoted(target)}'")
            }
            val response = sendResult.getOrNull()
            val transportFailure = sendResult.exceptionOrNull()
            if (transportFailure != null) {
                eventDeferred.cancel()
                Log.w(
                    ISSUE_464_KILL_TAG,
                    "kill-session-transport-failed host=${current.hostId} name=$target " +
                        "clientHash=$clientHash err=${transportFailure.javaClass.simpleName}: " +
                        transportFailure.message,
                )
                return@launch
            }
            if (response != null && response.isError) {
                eventDeferred.cancel()
                val detail = response.output.joinToString(separator = " ").trim()
                Log.w(
                    ISSUE_464_KILL_TAG,
                    "kill-session-tmux-error host=${current.hostId} name=$target " +
                        "clientHash=$clientHash detail=$detail",
                )
                return@launch
            }

            eventDeferred.join()
            Log.i(
                ISSUE_464_KILL_TAG,
                "kill-session-signal host=${current.hostId} name=$target clientHash=$clientHash",
            )
            sessionLifecycleSignals?.emitKilled(current.hostId, target)
        }
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
        // Issue #458: if the `Ctrl` modifier is armed, the next regular key
        // is sent as a Ctrl-chord control byte instead of its plain key. We
        // consume the armed state first so a single chord lands and a
        // one-shot arm clears. This routes through the same
        // [sendControlInputToPane] (`send-keys -H`) overlay path the
        // dedicated `^C`/`^D` keys use, so no terminal resize/redraw is
        // triggered. Locked stays armed for repeated chords.
        val ctrlArmed = _ctrlModifierState.value != KeyModifierState.Off
        if (ctrlArmed) {
            consumeOneShotCtrl()
            // Ctrl + ASCII letter → the classic control byte (0x01..0x1A)
            // via the `send-keys -H` overlay path.
            val chordByte = ctrlByteForLetter(label)
            if (chordByte != null) {
                sendControlInputToPane(paneId, chordByte)
                return
            }
            // Ctrl + a named key tmux understands (arrows → word navigation,
            // Tab, Esc) → tmux's own `C-<key>` chord syntax, which lets tmux
            // emit the correct terminfo encoding (e.g. ESC[1;5C for
            // Ctrl+Right). Still a `send-keys` control-channel command, so no
            // resize/redraw.
            val chordNamed = ctrlChordNamedKeyFor(label)
            if (chordNamed != null) {
                sendNamedKey(paneId, chordNamed)
                return
            }
            // Nothing chordable for this label (e.g. an already-Ctrl combo);
            // fall through and send the plain key, matching
            // SessionViewModel.applyCtrl's unmodified fallback.
        }

        val named = when (label) {
            "Esc" -> "Escape"
            "Tab" -> "Tab"
            // Issue #527: the dedicated Enter/Return key. Submits a
            // newline/CR to the pane (runs the typed or pending line) via
            // the tmux named `Enter` key on the `send-keys` control channel
            // — no terminal resize or redraw, like Esc/Tab.
            "⏎", "Enter" -> "Enter"
            // Curated one-tap control combos (issue #458). Each maps
            // directly to its control byte via the `send-keys -H` overlay
            // path — no resize, no redraw.
            "^C", "Ctrl-C" -> {
                sendControlInputToPane(paneId, CtrlCByte)
                null
            }
            "^D", "Ctrl-D" -> {
                sendControlInputToPane(paneId, CtrlDByte)
                null
            }
            "^Z", "Ctrl-Z" -> {
                sendControlInputToPane(paneId, CtrlZByte)
                null
            }
            "^O", "Ctrl-O" -> {
                sendControlInputToPane(paneId, CtrlOByte)
                null
            }
            "^X", "Ctrl-X" -> {
                sendControlInputToPane(paneId, CtrlXByte)
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

    /**
     * Issue #458: map a bar label to tmux's `C-<key>` chord named-key when a
     * Ctrl chord with that key has a meaningful terminal encoding. Arrows
     * become word-navigation (`C-Left`/`C-Right`), Tab/Esc pass through as
     * `C-Tab`/`C-Escape`. Letters are handled separately via the raw control
     * byte path. Returns `null` when there is no useful chord (the caller
     * falls back to the plain key).
     */
    private fun ctrlChordNamedKeyFor(label: String): String? = when (label) {
        "‹", "Left" -> "C-Left"
        "⌃", "Up" -> "C-Up"
        "⌄", "Down" -> "C-Down"
        "›", "Right" -> "C-Right"
        "Tab" -> "C-Tab"
        "Esc" -> "C-Escape"
        else -> null
    }

    /**
     * Issue #458: mirror the ui-kit [com.pocketshell.uikit.components.KeyBar]
     * sticky-state changes for the `Ctrl` modifier into [ctrlModifierState].
     * The bar owns the tap FSM (tap = one-shot arm, double-tap = lock,
     * tap-while-armed = disarm); we keep a screen-visible copy so the next
     * key tap can chord and the strip can render the armed accent.
     */
    public fun onKeyBarModifierState(label: String, state: KeyModifierState) {
        if (label == "Ctrl") {
            _ctrlModifierState.value = state
        }
    }

    /**
     * Clear a one-shot armed `Ctrl` after a chord fires. A locked `Ctrl`
     * survives so the user can fire several chords in a row; only an
     * explicit tap on the `Ctrl` key disarms it (handled by the ui-kit
     * FSM, mirrored back through [onKeyBarModifierState]).
     */
    private fun consumeOneShotCtrl() {
        if (_ctrlModifierState.value == KeyModifierState.OneShot) {
            _ctrlModifierState.value = KeyModifierState.Off
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
        Log.w(
            ISSUE_235_LIFECYCLE_TAG,
            "tmux-viewmodel-cleared " +
                "status=${_connectionStatus.value::class.simpleName} " +
                "activeTarget=${activeTarget?.let { targetLogFields(it) } ?: "none"} " +
                "connectingTarget=${connectingTarget?.let { targetLogFields(it) } ?: "none"} " +
                "hasClient=${clientRef != null} " +
                "clientDisconnected=${clientRef?.disconnected?.value} " +
                "hasSession=${sessionRef != null} " +
                "sessionConnected=${sessionRef?.isConnected} " +
                "hasLease=${leaseRef != null} " +
                "appActive=$appActive " +
                "pendingReattach=${pendingReattach != null} " +
                "backgroundDetachActive=${backgroundDetachJob?.isActive == true}",
        )
        cancelTmuxSessionPrewarm()
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

    private suspend fun releaseCurrentLeaseOrCloseRawSession() {
        val lease = leaseRef
        if (lease != null) {
            withContext(NonCancellable) {
                runCatching { lease.release() }
            }
        } else {
            runCatching { sessionRef?.close() }
        }
    }

    private fun releaseCurrentLeaseOrCloseRawSessionBlocking() {
        val lease = leaseRef
        if (lease != null) {
            runCatching {
                runBlocking(Dispatchers.IO) {
                    withTimeoutOrNull(SYNC_DETACH_TIMEOUT_MS) {
                        lease.release()
                    }
                }
            }
        } else {
            runCatching { sessionRef?.close() }
        }
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
        val closingHostId = activeTarget?.hostId ?: registeredHostId
        // Issue #257: drain any background detach left in flight by a
        // prior same-host fast-switch before we tear the rest down, so a
        // full teardown (background-detach / cross-host reconnect) never
        // races a lingering `detach-client` from the previous switch.
        orphanDetachJob?.cancelAndJoin()
        orphanDetachJob = null
        sessionPrewarmJob?.cancelAndJoin()
        sessionPrewarmJob = null
        eventsJob?.cancelAndJoin()
        eventsJob = null
        disconnectedJob?.cancelAndJoin()
        disconnectedJob = null
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
        paneAgentTailGenerations.clear()
        paneAgentInputs.clear()
        paneAgentNullDetections.clear()
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
        closingHostId?.let { hostId ->
            runtimeCache.removeHost(hostId).forEach { cached ->
                cached.closeCachedRuntime()
            }
        }
        releaseCurrentLeaseOrCloseRawSession()
        sessionRef = null
        leaseRef = null
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
        disconnectedJob?.cancelAndJoin()
        disconnectedJob = null
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
        paneAgentTailGenerations.clear()
        paneAgentInputs.clear()
        paneAgentNullDetections.clear()
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
        val closingHostId = activeTarget?.hostId ?: registeredHostId
        // Issue #257: cancel any in-flight background detach from a prior
        // fast-switch. This path runs from [onCleared] (and the sync test
        // seam), where [viewModelScope] is about to be cancelled anyway;
        // the explicit cancel keeps the state field tidy.
        orphanDetachJob?.cancel()
        orphanDetachJob = null
        eventsJob?.cancel()
        eventsJob = null
        disconnectedJob?.cancel()
        disconnectedJob = null
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
        paneAgentTailGenerations.clear()
        paneAgentInputs.clear()
        paneAgentNullDetections.clear()
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
        closingHostId?.let { hostId ->
            val cached = runtimeCache.removeHost(hostId)
            if (cached.isNotEmpty()) {
                runCatching {
                    runBlocking(Dispatchers.IO) {
                        cached.forEach { runtime ->
                            withTimeoutOrNull(SYNC_DETACH_TIMEOUT_MS) {
                                runtime.closeCachedRuntime()
                            }
                        }
                    }
                }
            }
        }
        releaseCurrentLeaseOrCloseRawSessionBlocking()
        sessionRef = null
        leaseRef = null
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

    private data class PausedAutoReconnect(
        val target: ConnectionTarget,
        val reason: String,
    )

    private data class RuntimeRefreshGuard(
        val generation: Long,
        val target: ConnectionTarget,
        val client: TmuxClient,
    )

    private data class AttachMilestone(
        val attempt: Int,
        val sessionName: String,
        val startedAtMs: Long,
        val trigger: TmuxConnectTrigger,
        var firstPaneOutputLogged: Boolean = false,
        var firstPaneListReadyLogged: Boolean = false,
        var firstTerminalBridgeLogged: Boolean = false,
        var firstCaptureReadyLogged: Boolean = false,
        var firstRemoteRefreshLogged: Boolean = false,
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

        /**
         * Issue #437 (slice A): a same-host tmux session switch where the
         * SSH transport is already warm (reused via the #364/#368 lease
         * manager). Unlike [Connecting] — which is a genuine first-connect
         * to a host and warrants the full-screen progress overlay — a
         * [Switching] state keeps the previous (or cached) terminal frame
         * on screen and spawns the new `-CC` control client in the
         * background, mirroring the cache-hit "first cached frame → remote
         * refresh" path. The full-screen "Connecting" overlay must NOT
         * appear for [Switching]; the user should never see a blank
         * "Connecting" screen on a same-host session switch.
         *
         * Input stays gated (treated as not-live) until the new control
         * client is attached and we flip to [Connected], so keystrokes are
         * never written into a half-attached pane.
         */
        public data class Switching(val host: String, val port: Int, val user: String) :
            ConnectionStatus
        public data class Connected(val host: String, val port: Int, val user: String) :
            ConnectionStatus
        public data class Reconnecting(
            val host: String,
            val port: Int,
            val user: String,
            val attempt: Int,
            val maxAttempts: Int,
            val retryDelayMs: Long,
            val reason: String,
        ) : ConnectionStatus
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

internal data class TmuxPaneInputSegment(
    val bytes: ByteArray,
    val enqueuedAtNs: Long,
)

internal data class TmuxPaneInputBatch(
    val bytes: ByteArray,
    val chunks: Int,
    val firstEnqueuedAtNs: Long,
)

private data class PrewarmedPaneRuntime(
    val panes: List<TmuxPaneState>,
    val paneRows: Map<String, TmuxPaneState>,
    val paneProducerJobs: Map<String, Job>,
    val paneInputQueues: Map<String, TmuxPaneInputQueue>,
    val paneInputJobs: Map<String, Job>,
)

private suspend fun PrewarmedPaneRuntime.closePartialPrewarm() {
    paneProducerJobs.values.forEach { it.cancelAndJoin() }
    paneInputJobs.values.forEach { it.cancelAndJoin() }
    paneInputQueues.values.forEach { it.close() }
    panes.forEach { pane ->
        runCatching { pane.terminalState.detachExternalProducer() }
    }
}

internal class TmuxPaneInputQueue(
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

internal fun buildBracketedPasteHexChunks(
    bytes: ByteArray,
    bodyChunkBytes: Int = TMUX_PASTE_BODY_CHUNK_BYTES,
): List<String> {
    if (bytes.isEmpty()) return emptyList()
    val chunkSize = bodyChunkBytes.coerceAtLeast(1)
    val normalised = normaliseLineEndingsForPaste(bytes)
    val chunks = ArrayList<String>((normalised.size + chunkSize - 1) / chunkSize + 2)
    chunks += buildTmuxHex(PASTE_START)
    var offset = 0
    while (offset < normalised.size) {
        val length = minOf(chunkSize, normalised.size - offset)
        chunks += buildTmuxHex(normalised, offset, length)
        offset += length
    }
    chunks += buildTmuxHex(PASTE_END)
    return chunks
}

internal fun buildTmuxHex(bytes: ByteArray): String =
    buildTmuxHex(bytes, 0, bytes.size)

private fun buildTmuxHex(bytes: ByteArray, offset: Int, length: Int): String {
    if (length <= 0) return ""
    val builder = StringBuilder(3 * length)
    appendHex(builder, bytes, offset, length)
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

private fun appendHex(
    builder: StringBuilder,
    bytes: ByteArray,
    offset: Int = 0,
    length: Int = bytes.size,
) {
    val end = minOf(bytes.size, offset + length)
    for (index in offset until end) {
        val b = bytes[index]
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
internal const val CtrlZByte: Int = 0x1A
internal const val CtrlOByte: Int = 0x0F
internal const val CtrlXByte: Int = 0x18

/**
 * Issue #458: classic terminal Ctrl-letter encoding. `Ctrl` + an ASCII
 * letter `a`..`z` / `A`..`Z` maps to control bytes `0x01`..`0x1A`
 * (`Ctrl+A` → `0x01`, `Ctrl+C` → `0x03`, `Ctrl+Z` → `0x1A`). This mirrors
 * `SessionViewModel.applyCtrl` so the tmux Ctrl-modifier mode produces the
 * same wire bytes as the soft-key route. Returns `null` for anything that
 * is not a single ASCII letter — the caller falls back to the unmodified
 * key (the terminal has no canonical Ctrl-Tab / Ctrl-arrow encoding here).
 */
internal fun ctrlByteForLetter(label: String): Int? {
    if (label.length != 1) return null
    val ch = label[0]
    val upper = when (ch) {
        in 'a'..'z' -> ch - ('a' - 'A')
        in 'A'..'Z' -> ch
        else -> return null
    }
    return upper.code - 'A'.code + 1
}

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
internal const val CODEX_AGENT_SUBMIT_DELAY_MS: Long = 250L
internal const val ATTACH_PANES_READY_TIMEOUT_MS: Long = 30_000L
internal const val ATTACH_PANES_READY_RETRY_MS: Long = 100L

/**
 * Issue #451: how long [TmuxSessionViewModel.stagePromptAttachments] waits
 * for the terminal SSH session to come back live before failing the
 * attachment upload. Attach connects-on-action like Send: when the file
 * picker round-trip outran the #450 grace window (or the OS killed the
 * socket) it kicks [TmuxSessionViewModel.reconnect] and waits here for the
 * tmux `-CC` re-attach — including the SSH handshake — to land. The
 * auto-reconnect backoff chain spans several seconds of delays alone, so the
 * bound covers a full re-dial plus handshake headroom. Bounded and
 * foreground-only — no background work.
 */
internal const val ATTACH_SESSION_WAIT_TIMEOUT_MS: Long = 30_000L
internal const val ATTACH_SESSION_WAIT_POLL_MS: Long = 100L
internal const val CACHED_RUNTIME_REMOTE_REFRESH_DELAY_MS: Long = 1L
internal const val TMUX_SESSION_PREWARM_MAX_TARGETS: Int = 2
internal const val LIST_PANES_FIELD_SEPARATOR: String = "|PS|"
internal const val TMUX_INPUT_MAX_PENDING_BYTES: Int = 64 * 1024
internal const val TMUX_INPUT_MAX_BATCH_BYTES: Int = 4 * 1024
internal const val TMUX_INPUT_CHUNK_BYTES: Int = 512
internal const val TMUX_PASTE_BODY_CHUNK_BYTES: Int = 1024
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

internal const val ISSUE_548_NETWORK_TAG: String = "PsTmuxNetwork"

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

/**
 * Issue #464: logcat tag for the confirmed kill-session path on the
 * per-session screen. Mirrors the dashboard's `issue168-kill` so a triage
 * of "killed session lingered in the tree" can correlate the kill with the
 * lifecycle signal that drops the folder-tree row. Under the 23-character
 * `Log.isLoggable` cap.
 */
internal const val ISSUE_464_KILL_TAG: String = "issue464-killsession"

/**
 * Issue #464: max time we wait for tmux's post-kill `%sessions-changed` /
 * `%session-changed` notification before emitting the lifecycle signal
 * anyway. 2s mirrors the dashboard's #168 window; tmux's real session
 * teardown latency on Docker / localhost is sub-100ms.
 */
internal const val KILL_SESSION_EVENT_WAIT_MS: Long = 2_000L

private val DEFAULT_AUTO_RECONNECT_DELAYS_MS: List<Long> = listOf(0L, 1_000L, 2_000L, 5_000L)

/**
 * Issue #440: simple class names of connect-failure causes that retrying
 * cannot fix — authentication rejection and DNS resolution failure. Matched
 * against the cause chain of a failed connect attempt (see
 * [TmuxSessionViewModel.isNonRetryableConnectFailure]). When one of these is
 * the failure, auto-reconnect stops immediately and surfaces the manual
 * Reconnect affordance rather than exhausting the backoff schedule.
 *
 * `UserAuthException` is sshj's authentication failure; `UnknownHostException`
 * is `java.net`'s DNS / unresolved-host signal. Both are config-level
 * problems the user must fix, not transient blips.
 */
private val NON_RETRYABLE_FAILURE_CLASS_NAMES: Set<String> = setOf(
    "UserAuthException",
    "UnknownHostException",
)

/**
 * Issue #440: substring that identifies the "private key file not found"
 * IOException raised by [com.pocketshell.core.ssh.SshConnection]. A missing
 * key is a non-retryable config error; matching on the message keeps the
 * generic IOException type (which also covers transient socket drops) on the
 * retryable path.
 */
private const val MISSING_KEY_MESSAGE_FRAGMENT: String = "Private key file not found"

/**
 * Issue #423: a terminal surface that fails this many times within
 * [SURFACE_RECOVERY_WINDOW_MS] is treated as a recovery storm rather than
 * a transient hiccup. At that point we stop silently re-attaching the
 * broken surface (which thrashes the emulator and looks like a freeze) and
 * flip the pane to an actionable error state with a "Recreate terminal"
 * control. Three transparent recoveries inside the window are tolerated;
 * the fourth trips the error state.
 */
internal const val SURFACE_RECOVERY_STORM_THRESHOLD: Int = 4

/** Sliding window for [SURFACE_RECOVERY_STORM_THRESHOLD]. */
internal const val SURFACE_RECOVERY_WINDOW_MS: Long = 4_000L

/**
 * Issue #554: how many consecutive null agent detections a remembered agent
 * window must produce before its optimistic seed is reconciled away. The first
 * null right after a reattach is almost always a detection-not-yet-warm race,
 * so we require a confirming re-detection before dropping the agent UI.
 */
internal const val AGENT_EXIT_CONFIRMATIONS: Int = 2

/** Delay before the issue #554 agent-exit confirmation re-detection. */
internal const val AGENT_EXIT_RECHECK_DELAY_MS: Long = 1_200L

public enum class TmuxConnectTrigger(public val logValue: String) {
    UserTap("user-tap"),
    LifecycleReattach("lifecycle-reattach"),
    ColdRestore("cold-restore"),
    FastSwitch("fast-switch"),
    Reconnect("reconnect"),
    AutoReconnect("auto-reconnect"),
    NetworkReconnect("network-reconnect"),
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
