package com.pocketshell.app.tmux

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
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
import com.pocketshell.app.conversation.ConversationDiagnostics
import com.pocketshell.app.composer.PromptAttachmentStager
import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.hasSameNetworkIdentityAs
import com.pocketshell.app.connectivity.networkDiagnosticFields
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.diagnostics.ReconnectCauseTrail
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.projects.ClaudeProfile
import com.pocketshell.app.projects.CodexProfile
import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.app.projects.ProfilesGateway
import com.pocketshell.app.projects.ProfilesResult
import com.pocketshell.app.projects.RemoteProfile
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
import com.pocketshell.app.tmux.connection.ConnectionControllerShadowBridge
import com.pocketshell.app.tmux.connection.ConnectionEffectDriver
import com.pocketshell.app.tmux.connection.ConnectionStatusProjection
import com.pocketshell.app.tmux.connection.CurrentClientTmuxPort
import com.pocketshell.app.tmux.connection.SshLeaseTransportPort
import com.pocketshell.app.tmux.connection.hostKeyFor
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.SessionId
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
import com.pocketshell.core.terminal.bridge.TerminalSeedGateOverflowException
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.terminal.input.BracketedPaste
import com.pocketshell.core.terminal.ui.TerminalRawInputPolicy
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
import com.pocketshell.core.tmux.TmuxSessionNotFoundException
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
import kotlinx.coroutines.CompletableDeferred
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
    private val agentSessionMemory: AgentSessionMemory = AgentSessionMemory(),
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
    // Issue #576: the agent-conversation tail/ingest repository. Defaulted so
    // production and existing unit-test constructors are unchanged, but
    // injectable so a burst-ingest test can wire a repository whose tail
    // drain runs on the test scheduler (deterministic batch coalescing).
    private val agentRepository: AgentConversationRepository = AgentConversationRepository(),
    // Issue #678: host-discovered agent profiles (issue #718) for the new-WINDOW
    // shell-vs-agent picker. Nullable default keeps existing unit-test
    // constructors working without the singleton; when absent the picker simply
    // shows no profile selector (the safe default-only behaviour).
    private val profilesGateway: ProfilesGateway? = null,
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

    private var terminalSurfaceStateFactory: () -> TerminalSurfaceState = { TerminalSurfaceState() }

    @androidx.annotation.VisibleForTesting
    internal fun setTerminalSurfaceStateFactoryForTest(factory: () -> TerminalSurfaceState) {
        terminalSurfaceStateFactory = factory
    }

    private fun newTerminalSurfaceState(): TerminalSurfaceState = terminalSurfaceStateFactory()

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

    /**
     * Issue #626: unified pane list that spans all open tmux sessions on the
     * same host. Combines the active session's [_panes] with panes from
     * cached (warm-switched) sessions in [runtimeCache], ordered as:
     * active-session windows, then cached-session-A windows, cached-session-B
     * windows, etc. The Screen uses this for a single HorizontalPager so the
     * user swipes seamlessly across sessions. Panes from non-active sessions
     * carry their session name in [TmuxPaneState.sessionId] so the pager can
     * detect session boundaries and trigger a warm switch when the user
     * settles on a different session's pane.
     */
    private val _unifiedPanes: MutableStateFlow<List<TmuxPaneState>> =
        MutableStateFlow(emptyList())
    public val unifiedPanes: StateFlow<List<TmuxPaneState>> = _unifiedPanes.asStateFlow()

    /**
     * Issue #626: one-shot signal emitted when the unified pager settles on
     * a pane belonging to a different tmux session. The Screen collects this
     * and calls `onReplaceTmuxSession` to trigger the warm switch.
     */
    private val _sessionSwitchRequest: MutableSharedFlow<String> =
        MutableSharedFlow(extraBufferCapacity = 4)
    public val sessionSwitchRequest: SharedFlow<String> =
        _sessionSwitchRequest.asSharedFlow()

    /**
     * Issue #666: one-shot signal that the session we tried to (re)attach to
     * no longer exists on the server — it was killed elsewhere while the app
     * was backgrounded. Emitted only on the attach-only cold-restore path, so
     * a gone session is never silently resurrected via `new-session -A`. The
     * Screen collects this and drops the user back to the host/session list
     * (and the app clears the persisted last-session snapshot) instead of
     * leaving them staring at a recreated, empty session. The payload is the
     * gone session's name so the UI can name it in the "that session ended"
     * surface.
     */
    private val _sessionEnded: MutableSharedFlow<String> =
        MutableSharedFlow(extraBufferCapacity = 1)
    public val sessionEnded: SharedFlow<String> =
        _sessionEnded.asSharedFlow()

    /**
     * Issue #626: called by the Screen when the unified pager settles on a
     * page. If the pane belongs to a different session, emit a
     * [sessionSwitchRequest] so the Screen triggers a warm switch.
     */
    public fun onUnifiedPageSettled(pageIndex: Int) {
        val pane = _unifiedPanes.value.getOrNull(pageIndex) ?: return
        val active = activeTarget ?: return
        // Check if this pane belongs to a different session
        val paneSessionName = sessionNameForUnifiedPane(pane)
        if (paneSessionName == null || paneSessionName == active.sessionName) return
        // Issue #661 / #634 / #636: suppress a settle-driven auto-switch that
        // would yank the user back to the session they are LEAVING while a
        // deliberate connect to a DIFFERENT session is still in flight. The
        // dogfood repro: tap session A from the host list; the unified pager
        // still carries the previous session C's panes and momentarily settles
        // on a C page before it realigns to A. Acting on that stale settle fired
        // a warm switch back to C — so after deliberately opening A the user was
        // looking at (and typing into) C's content (the wrong/stale-session +
        // content-bleed regression). [connectingTarget] is the user's CURRENT
        // deliberate destination (set synchronously in [connect] before any
        // coroutine runs); while a connect to a different session is in flight,
        // a settle to anything other than that destination is a stale-index
        // artifact and must be ignored. The deliberate tap always wins.
        val pendingTarget = connectingTarget
        if (connectJob?.isActive == true &&
            pendingTarget != null &&
            pendingTarget.sessionName != paneSessionName
        ) {
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-settle-switch-suppressed stale settle to '$paneSessionName' " +
                    "while connecting to '${pendingTarget.sessionName}' (active='${active.sessionName}')",
            )
            return
        }
        viewModelScope.launch {
            _sessionSwitchRequest.emit(paneSessionName)
        }
    }

    private val _agentConversations: MutableStateFlow<Map<String, AgentConversationUiState>> =
        MutableStateFlow(emptyMap())

    public val agentConversations: StateFlow<Map<String, AgentConversationUiState>> =
        _agentConversations.asStateFlow()

    private val _connectionStatus: MutableStateFlow<ConnectionStatus> =
        MutableStateFlow(ConnectionStatus.Idle)

    /** Coarse-grained status the screen surfaces above the terminal. */
    public val connectionStatus: StateFlow<ConnectionStatus> =
        _connectionStatus.asStateFlow()

    /**
     * EPIC #687 slice 1b: the VM-internal [ConnectionState] that [_connectionStatus]
     * is now a projection of. Tracked alongside [_connectionStatus] so the two move
     * together; every status emission goes through [setConnectionState], which sets
     * this and projects via [connectionStatusFor]. Slice 1c lets
     * `ConnectionController.state` feed this same field. Not exposed — purely
     * VM-internal source-of-truth for the view-facing [ConnectionStatus] facade.
     */
    private var _connectionState: ConnectionState = ConnectionState.Idle

    /**
     * EPIC #687: the `:shared:core-connection` `ConnectionController` the VM runs.
     * It is fed the lifecycle inputs the inline path dispatches
     * ([observeBackground]/[observeForeground]/[observeNetworkChanged]) PLUS every
     * inline connection transition mirrored from the single [setConnectionState]
     * choke point. Slice 1c-iv-a flipped the STATUS: the controller's state now
     * DRIVES [_connectionStatus] (the single status source, projected by
     * [projectStatusFromController]). Slice 1c-iv-b-B2 (#742) moved the controller's
     * TRANSPORT inputs (TransportLive / TransportDropped) onto the
     * [ConnectionEffectDriver]'s real port flows, deleting the inline mirror feeds.
     *
     * The warm-lease snapshot is the VM's existing [liveLeaseKeys] set, so the
     * controller's within-grace predicate reads the same warm signal the inline
     * fast-switch path already consults — without performing any transport IO.
     */
    /**
     * EPIC #687 slice 1c-iv-b-A2 (#739): the REAL [SshLeaseTransportPort] over the
     * VM's existing [sshLeaseManager]. This is the controller's `isWarm` source AND
     * the [ConnectionEffectDriver]'s real [TransportPort.transportEvents] input — NO
     * stub `emptyFlow`/fake-`isWarm` fork for the driver's inputs (D22). The
     * controller still reaches Live from the bridge's explicit real-feedback feeds,
     * so it performs no transport IO; `ensureLease`/`evictStale` are never invoked
     * in this observe-only slice (the inline `runConnect` remains the sole live IO).
     *
     * [leaseKeyFor] resolves a controller [HostKey] back to the lease target ONLY
     * for the suspend IO methods (unused here); it maps from the active/connecting
     * target, the same one [hostKeyFor] minted the [HostKey] from.
     */
    private val connectionTransportPort: SshLeaseTransportPort =
        SshLeaseTransportPort(
            leaseManager = sshLeaseManager,
            leaseKeyFor = { hostKey ->
                val target = (activeTarget ?: connectingTarget)
                    ?.takeIf { hostKeyFor(it.toSshLeaseTarget().leaseKey) == hostKey }
                    ?: error("no lease target for $hostKey (observe-only slice)")
                target.toSshLeaseTarget()
            },
        ).apply {
            warmSnapshot = { hostKey ->
                liveLeaseKeys.any { leaseKey -> hostKeyFor(leaseKey) == hostKey }
            }
        }

    /**
     * EPIC #687 slice 1c-iv-b-A2 (#739): the REAL [TmuxPort] over the VM's current
     * control client (swapped on every attach via [attachClient] →
     * [CurrentClientTmuxPort.setClient]). Its [TmuxPort.disconnected] oracle is the
     * [ConnectionEffectDriver]'s real transport-drop input — not a stub. Observe-only:
     * the driver collects [TmuxPort.disconnected] and never calls its IO methods.
     */
    private val connectionTmuxPort: CurrentClientTmuxPort =
        CurrentClientTmuxPort(
            activePaneIdFor = { sessionId -> sessionId.value },
            scrollbackLines = SEED_SCROLLBACK_LINES,
        )

    private val connectionControllerShadow: ConnectionControllerShadowBridge =
        ConnectionControllerShadowBridge(transport = connectionTransportPort)

    /**
     * EPIC #687 slice 1c-iv-b-A2 (#739) + slice 1c-iv-b-B1 (#738): the
     * [ConnectionEffectDriver], wired over the REAL adapters — it observes the shadow
     * [ConnectionController]'s [ConnectionController.state] transitions, the real
     * [SshLeaseTransportPort.transportEvents] lease edges, and the real
     * [CurrentClientTmuxPort.disconnected] transport-drop oracle.
     *
     * Slice B1 (#738) takes the FIRST effect off observe-only: the driver now OWNS
     * the clean BACKGROUND DETACH. On a transition INTO [ConnectionState.Backgrounded]
     * it fires [backgroundedEffect] = [launchBackgroundDetachTeardown], which runs the
     * EXISTING full teardown ([closeCurrentConnectionAndJoin] under `NonCancellable`)
     * — the same job the deleted inline `backgroundDetachJob` trigger launched, with
     * identical timing. The OPEN path (`runConnect`/`connectJob`) and the cold/warm
     * projection read STAY inline (deferred to a re-planned slice); the driver still
     * performs ZERO port IO for those. Started once, here, in [viewModelScope].
     */
    private val connectionEffectDriver: ConnectionEffectDriver =
        ConnectionEffectDriver(
            controller = connectionControllerShadow.connectionController,
            tmuxPort = connectionTmuxPort,
            transportPort = connectionTransportPort,
            scope = viewModelScope,
            backgroundedEffect = { launchBackgroundDetachTeardown() },
            // Slice 1c-iv-b-B2 (#742): after the driver submits a TransportLive /
            // TransportDropped from the real flows, re-project _connectionStatus from
            // the controller's (now real-flow-driven) state — the controller is the
            // single status source, exactly as the deleted mirror feeds re-projected.
            onControllerTransition = { projectStatusFromController() },
        ).also { it.start() }

    /**
     * The opaque [HostKey] / [SessionId] the shadow bridge keys on, derived from
     * the inline transition's active/connecting target. Returns null when there is
     * no target (Idle transition), in which case the bridge mirrors an Idle.
     */
    private fun shadowHostAndTarget(): Pair<HostKey?, SessionId?> {
        val target = activeTarget ?: connectingTarget ?: return (null to null)
        return (shadowHostKey(target) to shadowSessionId(target))
    }

    /**
     * EPIC #687 slice 1c-iv-prep: mint the shadow controller's [HostKey] through
     * the SAME [hostKeyFor] encoding the [liveLeaseKeys]-backed warm snapshot uses
     * (`hostKeyFor(leaseKey)`). Previously this path encoded the host as
     * `user@host:port/hostId` while the warm snapshot encoded it as
     * `user@host:port/credentialId/knownHostsId`, so the controller's `isWarm`
     * predicate was ALWAYS FALSE for a genuinely warm host. Routing both sides
     * through [hostKeyFor] off the one [ConnectionTarget.toSshLeaseTarget] lease
     * key aligns the encoding so `isWarm` returns true for a warm host.
     */
    private fun shadowHostKey(target: ConnectionTarget): HostKey =
        hostKeyFor(target.toSshLeaseTarget().leaseKey)

    private fun shadowSessionId(target: ConnectionTarget): SessionId =
        SessionId("${target.hostId}/${target.sessionName}")

    /**
     * EPIC #687 slice 1b: the single emission point — set the VM-internal
     * [ConnectionState] and project it to the view-facing [ConnectionStatus] via the
     * pure [connectionStatusFor] mapper. Replaces the scattered direct
     * `_connectionStatus.value = ...` writes so the status is always a projection of
     * an explicit state. Zero behavior change: the projected value is byte-identical
     * to the previous direct assignment.
     *
     * EPIC #687 slice 1c-iii: ALSO mirrors the transition into the OBSERVE-ONLY
     * shadow controller ([connectionControllerShadow]). This is pure observation —
     * the inline `_connectionStatus` write above is the sole source of truth; the
     * shadow controller's resulting state is collected for equivalence only and
     * drives nothing.
     */
    private fun setConnectionState(state: ConnectionState) {
        _connectionState = state
        // EPIC #687 slice 1c-iv-a (THE STATUS FLIP): the inline path NO LONGER
        // writes [_connectionStatus]. The view-facing status is now projected SOLELY
        // from the shadow [ConnectionController]'s state — the controller is the
        // single source of truth for what the user SEES. The inline EFFECT machinery
        // (reconnect jobs, generation counter, named coroutine jobs, reduceConnection
        // bodies) keeps running UNCHANGED; it just no longer owns the displayed
        // status. Inline effects that need to gate on "am I connected?" read the
        // VM-internal [inlineConnectionStatus] (a pure projection of [_connectionState]
        // — byte-identical to the status this method used to write), NOT the
        // controller-driven [_connectionStatus]. 1c-iv-b hard-cuts the inline
        // [_connectionState]/effect machinery once the controller drives effects too.
        observeConnectionTransitionInShadow(state)
        projectStatusFromController(state)
    }

    /**
     * EPIC #687 slice 1c-iv-a: the inline EFFECT machinery's own "current status"
     * view — a pure projection of the inline [_connectionState] through the same
     * [connectionStatusFor] mapper this VM has always used. Before the flip,
     * [_connectionStatus] was always exactly `connectionStatusFor(_connectionState)`,
     * so reading this is byte-identical to the old `_connectionStatus.value` reads:
     * the ~20 inline effect call sites that gate on `Connected`/`Reconnecting`/
     * `Failed` keep behaving identically while the DISPLAYED [_connectionStatus]
     * follows the controller (and diverges only on the two approved #685 paths).
     * This is NOT a second status SOURCE — nothing writes it; it is the inline
     * effect state read back. 1c-iv-b deletes it with the rest of the inline machinery.
     */
    private val inlineConnectionStatus: ConnectionStatus
        get() = connectionStatusFor(_connectionState)

    /**
     * EPIC #687 slice 1c-iv-a: project the shadow [ConnectionController]'s state onto
     * the view-facing [_connectionStatus] — the SINGLE status source. The coarse
     * SHAPE (Idle/Connecting/Switching/Connected/Reconnecting/Failed) comes from the
     * controller; the display PAYLOAD (host/port/user, reconnect attempt/reason, the
     * failure message) comes from the inline [state] the VM constructed for this
     * transition (the controller's core state carries opaque HostKey/SessionId, not
     * the host/port/user the view renders).
     *
     * The two approved #685 behavior changes fall out of the controller's state for
     * free: a recoverable drop leaves the controller in `Reattaching`/`Reconnecting`
     * (calm) rather than the inline `Unreachable`, so the user sees a calm
     * `Reconnecting` band, NOT the scary `Failed`/"Tap Reconnect" control-channel
     * band; and a within-grace foreground keeps the controller `Live`, so the user
     * sees `Connected` with no probe band. #720: a true `Unreachable` projects to a
     * [ConnectionStatus.Failed] whose curated message + calm tappable "Tap to
     * reconnect" band ([FailedConnectionRow]) replace the scary error text.
     */
    private fun projectStatusFromController(inlineState: ConnectionState) {
        _connectionStatus.value =
            connectionStatusForController(connectionControllerShadow.shadowState, inlineState)
    }

    /**
     * Re-project [_connectionStatus] from the controller after a NON-`setConnectionState`
     * bridge feed (a lifecycle event — background / foreground / network-change /
     * transport-drop, or the real transport-live / seed-landed feedback) that can
     * move the controller's state. Uses the current inline [_connectionState] as the
     * payload carrier. The controller is the single status source, so its state must
     * be re-projected whenever it can change — not only at the inline
     * [setConnectionState] choke point.
     */
    private fun projectStatusFromController() {
        projectStatusFromController(_connectionState)
    }

    /**
     * EPIC #687 slice 1c-iii: feed the inline transition to the shadow controller.
     * Observe-only — never mutates VM state, never reads the shadow state back.
     */
    private fun observeConnectionTransitionInShadow(state: ConnectionState) {
        val (host, target) = shadowHostAndTarget()
        val inlineName = when (state) {
            is ConnectionState.Idle -> "Idle"
            is ConnectionState.Connecting -> "Connecting"
            is ConnectionState.Attaching -> "Attaching"
            is ConnectionState.Live -> "Live"
            is ConnectionState.Backgrounded -> "Live"
            is ConnectionState.Reattaching -> "Reconnecting"
            is ConnectionState.Reconnecting -> "Reconnecting"
            is ConnectionState.Gone -> "Gone"
            is ConnectionState.Unreachable -> "Unreachable"
        }
        connectionControllerShadow.observeInlineTransition(inlineName, host, target)
    }

    /**
     * EPIC #687 slice 1c-iv-prep: feed the shadow controller the REAL
     * [com.pocketshell.core.connection.ConnectionEvent.SeedLanded] for a pane the
     * write-path just captured. Observe-only — never mutates VM state, never reads
     * the shadow state back. Keyed to the active/connecting target so a seed for
     * the session the shadow tracks promotes it (Attaching/Reattaching → Live);
     * the controller's own drop-by-id check ignores a seed for any other target.
     */
    private fun observeSeedLandedInShadow(paneId: String) {
        val target = activeTarget ?: connectingTarget ?: return
        connectionControllerShadow.observeSeedLanded(
            shadowHostKey(target),
            shadowSessionId(target),
            paneId,
        )
        // The seed may have promoted the controller Attaching/Reattaching → Live;
        // re-project the displayed status (the controller is the single source).
        projectStatusFromController()
    }

    /**
     * Issue #661: while a CROSS-session switch to a NOT-yet-cached target is in
     * flight, the screen must NEVER paint the leaving session's terminal frame —
     * not even for one Compose frame. #634 kept the previous frame painted to
     * avoid a "Connecting" blank, but the maintainer's refined preference is the
     * opposite for a cross-session switch: hide the terminal surface entirely
     * (show a compact "Attaching" loading indicator) and reveal ONLY once the
     * NEW session's panes are seeded from `capture-pane`.
     *
     * This flag is set SYNCHRONOUSLY (before any coroutine runs) at the start of
     * a cross-session fast-switch and cleared the instant the new session's
     * panes are revealed (status flips to [ConnectionStatus.Connected]). It is
     * NOT set on the runtime-cache-hit path: that path activates the TARGET
     * session's own cached frame instantly, which is correct content (the target,
     * never the leaving session), so there is nothing to hide.
     *
     * Keep-frame remains in force for a within-session reattach / warm reopen of
     * the SAME session — only the cross-session case blanks.
     */
    private val _switchHidesTerminal: MutableStateFlow<Boolean> =
        MutableStateFlow(false)
    public val switchHidesTerminal: StateFlow<Boolean> =
        _switchHidesTerminal.asStateFlow()

    private var attachPanesReadyTimeoutMs: Long = ATTACH_PANES_READY_TIMEOUT_MS
    private var activeAttachMilestone: AttachMilestone? = null

    /**
     * Issue #640: pane IDs already seeded (via [seedPaneFromCapture]) during
     * the current attach. The cold-open reveal uses this to skip the redundant
     * second full reseed for panes the preload pass already painted, so a fresh
     * connect pays exactly one capture per visible pane and only *reused*
     * (reattach) panes are re-captured. Reset at the start of each attach.
     */
    private val panesSeededThisAttach: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap())
    private var autoReconnectDelaysMs: List<Long> = DEFAULT_AUTO_RECONNECT_DELAYS_MS
    private var passiveDisconnectGraceMs: Long = PASSIVE_DISCONNECT_GRACE_MS
    private var silentReattachTimeoutMs: Long = PASSIVE_DISCONNECT_SILENT_REATTACH_TIMEOUT_MS

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
     * Issue #625: signal emitted after a new-window command creates a window.
     * Carries the window ID (e.g. `@5`) so the Screen can scroll the pager
     * to the matching pane. The Screen collects this flow and calls
     * [selectWindow] + scrolls the pager when a value arrives.
     */
    private val _windowSwitchRequest: MutableSharedFlow<String> =
        MutableSharedFlow(extraBufferCapacity = 4)
    public val windowSwitchRequest: SharedFlow<String> =
        _windowSwitchRequest.asSharedFlow()

    /**
     * Issue #678: host-discovered Claude / Codex agent profiles (issue #718)
     * for the new-WINDOW shell-vs-agent picker. The screen surfaces the per
     * engine profile selector only when a list has more than one entry, so an
     * empty list (no gateway, CLI missing, fetch failure) safely renders the
     * default-only picker. Mirrors
     * [com.pocketshell.app.projects.FolderListViewModel.claudeProfiles].
     */
    private val _claudeProfiles: MutableStateFlow<List<ClaudeProfile>> =
        MutableStateFlow(emptyList())
    public val claudeProfiles: StateFlow<List<ClaudeProfile>> =
        _claudeProfiles.asStateFlow()

    private val _codexProfiles: MutableStateFlow<List<CodexProfile>> =
        MutableStateFlow(emptyList())
    public val codexProfiles: StateFlow<List<CodexProfile>> =
        _codexProfiles.asStateFlow()

    /**
     * Issue #678: fetch the host-discovered agent profiles for the new-WINDOW
     * picker, the same way
     * [com.pocketshell.app.projects.FolderListViewModel.fetchProfiles] does for
     * the new-SESSION picker. Called by the screen when the user taps `+ window`
     * so the picker's per-engine profile selectors are populated. Any non
     * success result (no gateway in tests, CLI missing, connect/parse failure)
     * leaves the flows empty so the picker shows the default-only behaviour.
     */
    public fun loadAgentProfiles() {
        val gw = profilesGateway ?: run {
            _claudeProfiles.value = emptyList()
            _codexProfiles.value = emptyList()
            return
        }
        val target = activeTarget ?: connectingTarget ?: return
        val dao = hostDao ?: return
        viewModelScope.launch {
            val host = withContext(Dispatchers.IO) { dao.getById(target.hostId) }
                ?: return@launch
            val result = withContext(Dispatchers.IO) {
                gw.listProfiles(
                    host = host,
                    keyPath = target.keyPath,
                    passphrase = target.passphrase,
                )
            }
            // Ignore a stale result if the host changed while fetching.
            if ((activeTarget ?: connectingTarget)?.hostId != target.hostId) {
                return@launch
            }
            when (result) {
                is ProfilesResult.Profiles -> applyAgentProfiles(result.profiles)
                else -> {
                    _claudeProfiles.value = emptyList()
                    _codexProfiles.value = emptyList()
                }
            }
        }
    }

    private fun applyAgentProfiles(profiles: List<RemoteProfile>) {
        _claudeProfiles.value = profiles
            .filter { it.engine == RemoteProfile.ENGINE_CLAUDE }
            .map { ClaudeProfile(name = it.name, default = it.default) }
        _codexProfiles.value = profiles
            .filter { it.engine == RemoteProfile.ENGINE_CODEX }
            .map { CodexProfile(name = it.name, default = it.default) }
    }

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

    // Issue #634: a lock-free snapshot of the lease keys the pool currently
    // holds a live (Connected/Idle) transport for, kept in sync with
    // [SshLeaseManager.stateEvents]. The pool's authoritative `hasLiveLease`
    // probe is `suspend` (mutex-guarded), but [connect] needs to choose the
    // initial status SYNCHRONOUSLY — before the connect coroutine runs — so a
    // warm open never flashes the amber "Connecting" overlay even for one
    // frame. This set lets the synchronous flip pick the green [Switching]
    // ("Attaching") affordance for a warm open; the connect coroutine then
    // re-confirms with the authoritative `hasLiveLease` and downgrades to the
    // cold [Connecting] overlay if the lease turned out to be gone. Eventual
    // consistency is fine: it is only the initial UI hint, corrected within
    // milliseconds by the coroutine.
    private val liveLeaseKeys: MutableSet<SshLeaseKey> =
        java.util.concurrent.ConcurrentHashMap.newKeySet()

    init {
        viewModelScope.launch {
            sshLeaseManager.stateEvents.collect { event ->
                when (event.state) {
                    // Issue #620: a Connecting key (host detail's warm-lease
                    // handshake in flight) counts as warm for the synchronous
                    // open hint — a session open landing in this window
                    // coalesces onto the in-flight connect, so it must show the
                    // green Attaching affordance, not the cold Connecting overlay.
                    com.pocketshell.core.ssh.SshLeaseConnectionState.Connecting,
                    com.pocketshell.core.ssh.SshLeaseConnectionState.Connected,
                    com.pocketshell.core.ssh.SshLeaseConnectionState.Idle,
                    -> liveLeaseKeys.add(event.key)
                    com.pocketshell.core.ssh.SshLeaseConnectionState.Closed,
                    -> liveLeaseKeys.remove(event.key)
                }
                // EPIC #687 slice 1c-iv-b-B2 (#742): the controller's TransportLive
                // input is now driver-fed from the REAL [SshLeaseTransportPort.
                // transportEvents] `Up` edge (the same lease-`Connected` signal), so the
                // inline `observeTransportLiveInShadow` mirror feed is DELETED (D22
                // hard-cut). This collector retains ONLY its [liveLeaseKeys] warm-snapshot
                // bookkeeping above — it no longer feeds the controller.
            }
        }
    }

    private var clientRef: TmuxClient? = null
    private var clientRegistration: ActiveTmuxClients.Registration? = null
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
    private var lifecycleHookRegistration: ActiveTmuxClients.LifecycleRegistration? = null
    private var lifecycleHookHostId: Long? = null
    private var activeTarget: ConnectionTarget? = null
    private var connectingTarget: ConnectionTarget? = null
    private var connectJob: Job? = null
    private var autoReconnectJob: Job? = null
    private var appActive: Boolean = true
    private var screenStartedForCleared: Boolean = true
    private var eventsJob: Job? = null
    private var outputOverflowJob: Job? = null
    private var disconnectedJob: Job? = null
    private var passiveDisconnectGraceJob: Job? = null
    private var foregroundRuntimeProbeJob: Job? = null
    private var lifecycleReattachNetworkCoalesce: LifecycleReattachNetworkCoalesce? = null

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

    // Issue #621 / #634 / #636 (Slice 4): how many consecutive transparent
    // stale-lease auto-recoveries we have already kicked off for the current
    // connect chain. A warm SSH lease can be silently dead — its transport
    // keeps reporting `isConnected` until sshj's 60s keepalive trips — so the
    // first `tmux -CC` open / `list-panes` over it EOFs. Rather than stranding
    // the user on a Disconnected band + manual Reconnect, the open/switch
    // attach evicts the poisoned lease and re-dials a FRESH transport
    // transparently (via [scheduleAutoReconnect]). This counter caps how many
    // times we do that before concluding the host is genuinely dead and
    // falling back to the manual Reconnect affordance, so a permanently-broken
    // host cannot loop forever. Reset to 0 on a successful attach.
    private var staleLeaseAutoRecoverAttempts: Int = 0

    // Issue #145: whether [reconnect] would result in a new connect
    // attempt. The screen surfaces a Reconnect button only when this is
    // true; without a known target (e.g. the ViewModel was never opened)
    // the button is hidden so the user doesn't get a silent no-op tap.
    // The flow is updated whenever [activeTarget] / [connectingTarget]
    // changes via [refreshReconnectAvailability].
    private val _canReconnect: MutableStateFlow<Boolean> = MutableStateFlow(false)
    public val canReconnect: StateFlow<Boolean> = _canReconnect.asStateFlow()

    /**
     * Issue #628: the name of the previously-active tmux session on the
     * same host. Snapshotted from the current [activeTarget] right before
     * a connect that changes the session name. Null when there is no
     * previous session to toggle back to (initial connect, detach, host
     * change, or VM teardown). The UI uses this to render a one-tap toggle
     * chip and a long-press crumb shortcut.
     */
    private val _previousSessionName: MutableStateFlow<String?> = MutableStateFlow(null)
    public val previousSessionName: StateFlow<String?> = _previousSessionName.asStateFlow()

    // Last on-screen terminal grid reported by Compose. It can arrive
    // before or after the tmux control client attaches; once both are
    // known, the VM reports this size through `refresh-client -C`.
    private var remoteColumns: Int = 0
    private var remoteRows: Int = 0
    private var appliedControlClientColumns: Int = 0
    private var appliedControlClientRows: Int = 0
    private var controlClientSizeGeneration: Long = 0L
    private var windowSizePolicyAppliedForAttach: Boolean = false
    // Issue #495/#554: injected process-scoped memory of which tmux windows
    // are agent windows (and which agent + the user's last tab choice), keyed
    // by stable host/session/window identity. This lets a reconnect or VM
    // recreation restore the Conversation tab immediately while live
    // re-detection round-trips. See [AgentSessionMemory].
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
    private val scannedConversationPortEventKeys: MutableSet<String> =
        ConcurrentHashMap.newKeySet()

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
    private val paneOutputActivityJobs: MutableMap<String, Job> = ConcurrentHashMap()

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
        // Issue #666: internal guard so the ColdRestore preflight (below) can
        // re-enter [connect] once it has confirmed the session is still alive
        // without preflighting a second time. Never set by external callers.
        skipColdRestorePreflight: Boolean = false,
    ) {
        // Issue #666: a foreground cold-restore must NOT resurrect a session
        // killed elsewhere while the app was backgrounded. Before we attach —
        // and crucially BEFORE we can activate a stale warm/cached runtime
        // whose `-CC` channel would EOF and trigger an auto-reconnect that
        // recreates the session via `new-session -A` — probe `tmux has-session`
        // over the pooled transport. If the session is gone, drop to the list
        // instead of attaching anything. We only do this for ColdRestore (the
        // genuine resume-from-persisted-last-session path); every other trigger
        // is unchanged.
        if (trigger == TmuxConnectTrigger.ColdRestore && !skipColdRestorePreflight) {
            preflightColdRestore(
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
            return
        }
        val interruptedPassiveRecovery = passiveDisconnectGraceJob?.isActive == true
        passiveDisconnectGraceJob?.cancel()
        passiveDisconnectGraceJob = null
        pausedAutoReconnect = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        lifecycleReattachNetworkCoalesce = null
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
        if (
            !interruptedPassiveRecovery &&
            !shouldForceFreshLease(trigger) &&
            inlineConnectionStatus is ConnectionStatus.Connected &&
            activeTarget == target
        ) {
            return
        }

        val previousActiveTarget = activeTarget
        val previousSession = sessionRef
        val willFastSwitch = previousActiveTarget != null &&
            previousSession != null &&
            previousSession.isConnected &&
            isSameHost(previousActiveTarget, target) &&
            previousActiveTarget.sessionName != target.sessionName
        val effectiveTrigger = if (willFastSwitch) TmuxConnectTrigger.FastSwitch else trigger
        // Issue #628: snapshot the current session name as "previous" when
        // we are about to switch to a different session. This drives the
        // one-tap toggle chip and long-press crumb shortcut in the UI.
        // Must happen before any teardown that clears activeTarget.
        if (previousActiveTarget != null &&
            previousActiveTarget.sessionName != target.sessionName
        ) {
            _previousSessionName.value = previousActiveTarget.sessionName
        }
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
            "clientHash" to clientRef?.let { System.identityHashCode(it) },
        )
        DiagnosticEvents.record(
            "connection",
            when (effectiveTrigger) {
                TmuxConnectTrigger.Reconnect,
                TmuxConnectTrigger.AutoReconnect,
                TmuxConnectTrigger.NetworkReconnect,
                -> "reconnect_start"
                else -> "connect_start"
            },
            "attempt" to attempt,
            "hostId" to hostId,
            "host" to host,
            "port" to port,
            "user" to user,
            "session" to target.sessionName,
            "trigger" to effectiveTrigger.logValue,
            "requestedTrigger" to trigger.logValue,
            "generation" to generation,
            "previousClientHash" to clientRef?.let { System.identityHashCode(it) },
            *shortAppSwitchReconnectFields(
                trigger = effectiveTrigger,
                target = target,
                sourceCandidate = "connect_attempt",
            ),
        )
        ReconnectCauseTrail.record(
            stage = "connect_attempt",
            outcome = if (effectiveTrigger.isReconnectTrigger) "reconnect" else "connect",
            cause = "connect_invoked",
            trigger = effectiveTrigger.logValue,
            "requestedTrigger" to trigger.logValue,
            "attempt" to attempt,
            "hostId" to hostId,
            "generation" to generation,
            "previousClientPresent" to (clientRef != null),
            "willFastSwitch" to willFastSwitch,
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

        val cachedActivation = if (shouldForceFreshLease(effectiveTrigger)) {
            null
        } else {
            takeCachedRuntimeForActivation(
                target = target,
                attempt = attempt,
                trigger = effectiveTrigger,
                visibleSwitchStartedAtMs = fastSwitchStartedAtMs,
            )
        }
        if (cachedActivation != null) {
            connectJob = viewModelScope.launch {
                if (
                    tryActivateCachedRuntime(
                        target = target,
                        attempt = attempt,
                        trigger = effectiveTrigger,
                        visibleSwitchStartedAtMs = fastSwitchStartedAtMs,
                        cached = cachedActivation,
                        generation = generation,
                    )
                ) {
                    DiagnosticEvents.record(
                        "connection",
                        "connect_success",
                        "attempt" to attempt,
                        "hostId" to hostId,
                        "host" to host,
                        "port" to port,
                        "user" to user,
                        "session" to target.sessionName,
                        "trigger" to effectiveTrigger.logValue,
                        "source" to "runtime_cache",
                        "generation" to generation,
                        "clientHash" to clientRef?.let { System.identityHashCode(it) },
                        *shortAppSwitchReconnectFields(
                            trigger = effectiveTrigger,
                            target = target,
                            sourceCandidate = "runtime_cache",
                        ),
                    )
                    return@launch
                }
                cancelTmuxSessionPrewarm()
                closeCurrentConnectionAndJoin(preserveConnectingTarget = target)
                connectingTarget = target
                refreshReconnectAvailability()
                // Issue #634: warm-open aware even on the cached-activation
                // fallback (the runtime cache had an entry but could not be
                // pointer-swapped). If the host's SSH lease is still live, attach
                // instantly under the green [Switching] affordance rather than the
                // blanking [Connecting] overlay (see the slow-path branch below).
                // Issue #620: also warm when host detail's lease handshake is
                // still in flight — this open coalesces onto it, no second dial.
                val warmOpen = !shouldForceFreshLease(effectiveTrigger) &&
                    sshLeaseManager.hasLiveOrConnectingLease(target.toSshLeaseTarget().leaseKey)
                setConnectionState(
                    if (warmOpen) {
                        ConnectionState.Attaching(host, port, user)
                    } else {
                        ConnectionState.Connecting(host, port, user)
                    }
                )
                runConnect(target, attempt, effectiveTrigger, warmReveal = warmOpen)
            }
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
        //
        // Issue #634: a "warm open" — a first open of a session on a host whose
        // SSH lease pool already holds a live transport for this credential —
        // also reuses the warm transport (no handshake), so it too must show the
        // green [Switching] "Attaching" affordance, NOT the amber [Connecting]
        // overlay. We decide this SYNCHRONOUSLY off [liveLeaseKeys] so the warm
        // open never flashes the overlay even for one frame; the connect
        // coroutine re-confirms authoritatively and downgrades to [Connecting]
        // if the lease turned out to be gone. A reconnect / network-reattach
        // deliberately forces a fresh transport, so it is never a warm open.
        val warmOpenHint = !willFastSwitch &&
            !shouldForceFreshLease(effectiveTrigger) &&
            liveLeaseKeys.contains(target.toSshLeaseTarget().leaseKey)
        setConnectionState(
            if (willFastSwitch || warmOpenHint) {
                ConnectionState.Attaching(host, port, user)
            } else {
                ConnectionState.Connecting(host, port, user)
            }
        )
        // Issue #661: a cross-session fast switch must NOT paint the leaving
        // session's frame for even one Compose frame. Hide the terminal surface
        // SYNCHRONOUSLY (the screen shows a compact "Attaching" loading state
        // instead) and reveal only once the new session's panes are seeded. This
        // reverses #634's keep-frame for the cross-session case (see
        // [_switchHidesTerminal]). The runtime-cache-hit path returned earlier
        // above and is unaffected — it activates the target's own cached frame,
        // which is correct content, not the leaving session.
        //
        // CRITICAL ordering: raise the gate AND blank the rendered panes in the
        // SAME synchronous turn (before any coroutine runs). If the gate were
        // raised while [_panes] still held the leaving frame, the screen could
        // observe a single (hidden=true, previous-pane) state — the very flash
        // we are removing. The cached runtime still captures the leaving panes
        // via [deactivateCurrentRuntimeToCache]'s `paneRows` fallback (the
        // per-pane row map is untouched here), so blanking [_panes] now loses no
        // warm-cache content.
        if (willFastSwitch) {
            _switchHidesTerminal.value = true
            _panes.value = emptyList()
            rebuildUnifiedPanes()
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
            rebuildUnifiedPanes()
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
                    // Issue #661: do NOT re-publish the leaving session's
                    // frame here. #634 used to snapshot [_panes] before
                    // parking the leaving runtime and immediately re-publish
                    // it so the viewport never blanked — but that is exactly
                    // the "I can still see the previous session for a split
                    // second" flash the maintainer reported. We now blank the
                    // rendered panes (the producer teardown inside
                    // [deactivateCurrentRuntimeToCache] already clears them)
                    // and rely on [_switchHidesTerminal] (set synchronously
                    // above) to show a compact "Attaching" loading state until
                    // [runFastSessionSwitch]'s reconcile seeds and reveals the
                    // NEW session's panes. Input stays gated for the whole
                    // window because the status is [Switching], not
                    // [Connected].
                    closeCachedRuntimesAsync(deactivateCurrentRuntimeToCache())
                    _panes.value = emptyList()
                    rebuildUnifiedPanes()
                    runFastSessionSwitch(target, attempt, effectiveTrigger, fastSwitchStartedAtMs)
                } else {
                    // Slow path: full teardown of both the tmux client
                    // and the SSH session before the SSH lease acquisition.
                    closeCurrentConnectionAndJoin(preserveConnectingTarget = target)
                    // Issue #634: a "warm open" — a first open of a session on
                    // a host whose SSH lease pool already holds a live (idle or
                    // active) transport for this exact credential. This is the
                    // maintainer's dogfood case: connect a host, back out, then
                    // open one of its sessions. The pooled lease is reused (no
                    // SSH handshake) so it must NOT show the full-screen
                    // [Connecting] overlay / amber "Reconnecting" pill, nor be
                    // reveal-gated behind a full reseed. We attach instantly like
                    // a desktop `tmux attach`: flip to the green [Switching]
                    // "Attaching" affordance and reveal as soon as panes are
                    // seeded. A genuine COLD connect (no live pooled lease) keeps
                    // the [Connecting] overlay. We must NOT mistake a reconnect /
                    // network-reattach trigger for a warm open: those deliberately
                    // force a fresh transport ([shouldForceFreshLease]).
                    // Issue #620: a connect for this key being in flight (host
                    // detail's warm-lease handshake) ALSO counts as a warm open —
                    // the acquire below coalesces onto it, so no second SSH dial
                    // happens and the attach is instant.
                    val warmOpen = !shouldForceFreshLease(effectiveTrigger) &&
                        sshLeaseManager.hasLiveOrConnectingLease(target.toSshLeaseTarget().leaseKey)
                    if (warmOpen) {
                        recordWarmSwitchMilestone(
                            attempt = attempt,
                            target = target,
                            startedAtMs = fastSwitchStartedAtMs.takeIf { it != 0L }
                                ?: SystemClock.elapsedRealtime(),
                            name = "warm_open_live_lease",
                            trigger = effectiveTrigger,
                        )
                        // Confirm the green Switching affordance (the synchronous
                        // [warmOpenHint] above already chose it for a live pooled
                        // lease; re-assert here in case anything raced).
                        if (inlineConnectionStatus !is ConnectionStatus.Switching) {
                            setConnectionState(ConnectionState.Attaching(host, port, user))
                        }
                    } else if (inlineConnectionStatus is ConnectionStatus.Switching) {
                        // The synchronous hint said warm, but the authoritative
                        // pool probe found no live lease (it was evicted / closed
                        // between the hint and now). This is a genuine COLD dial:
                        // downgrade to the full-screen [Connecting] overlay and
                        // blank the (empty) pane area so the cold reveal path
                        // behaves exactly as a never-warm first connect.
                        setConnectionState(ConnectionState.Connecting(host, port, user))
                        _panes.value = emptyList()
                        rebuildUnifiedPanes()
                    }
                    runConnect(target, attempt, effectiveTrigger, warmReveal = warmOpen)
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

    private fun markSuccessfulAttachForNetworkCoalescing(
        target: ConnectionTarget,
        trigger: TmuxConnectTrigger,
    ) {
        // Issue #621 / #634 / #636 (Slice 4): a successful attach means the
        // stale-lease heal worked (or was never needed), so the budget resets
        // for the next connect chain.
        staleLeaseAutoRecoverAttempts = 0
        lifecycleReattachNetworkCoalesce =
            if (trigger == TmuxConnectTrigger.LifecycleReattach) {
                LifecycleReattachNetworkCoalesce(
                    target = target,
                    generation = connectGeneration,
                )
            } else {
                null
            }
    }

    /**
     * Issue #666: the most recent [createIfMissing] flag passed to
     * [createTmuxClient]. Exposed for tests because the test client factory
     * override deliberately keeps its 3-arg shape; this side-channel lets a
     * test assert that the cold-restore path requested attach-only semantics
     * without rewriting every existing override call site.
     */
    @Volatile
    private var lastCreateIfMissing: Boolean = true

    @androidx.annotation.VisibleForTesting
    internal fun lastCreateIfMissingForTest(): Boolean = lastCreateIfMissing

    private fun createTmuxClient(
        session: SshSession,
        sessionName: String,
        startDirectory: String?,
        // Issue #666: attach-OR-create (true) vs attach-only (false). Only the
        // genuine cold-restore path passes false so a session killed elsewhere
        // is not resurrected; every create/reconnect/switch path keeps true.
        createIfMissing: Boolean = true,
    ): TmuxClient {
        lastCreateIfMissing = createIfMissing
        return tmuxClientFactoryOverride?.invoke(session, sessionName, startDirectory)
            ?: tmuxClientFactory.create(
                session,
                sessionName = sessionName,
                startDirectory = startDirectory,
                createIfMissing = createIfMissing,
            )
    }

    /**
     * Issue #666: only a genuine foreground cold-restore attaches attach-only.
     * Every other trigger (explicit user tap/create, fast switch, all reconnect
     * variants, within-grace lifecycle reattach to a session we just had live)
     * keeps attach-OR-create so it can create or reattach as before — #634's
     * warm-open and the reconnect journeys are untouched.
     */
    private fun createIfMissingForTrigger(trigger: TmuxConnectTrigger): Boolean =
        trigger != TmuxConnectTrigger.ColdRestore

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
        if (activeTarget == null && connectingTarget == null) return false
        startReconnectForSend()
        return true
    }

    private fun startReconnectForSend(): Job? {
        pausedAutoReconnect = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        val target = activeTarget ?: connectingTarget ?: return null
        DiagnosticEvents.record(
            "action",
            "reconnect_tapped",
            "hostId" to target.hostId,
            "host" to target.host,
            "port" to target.port,
            "user" to target.user,
            "session" to target.sessionName,
            "trigger" to TmuxConnectTrigger.Reconnect.logValue,
            "generation" to connectGeneration,
            *shortAppSwitchReconnectFields(
                trigger = TmuxConnectTrigger.Reconnect,
                target = target,
                sourceCandidate = "manual_reconnect",
            ),
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
            trigger = TmuxConnectTrigger.Reconnect,
        )
        return connectJob
    }

    @androidx.annotation.VisibleForTesting
    internal fun setAutoReconnectDelaysForTest(delaysMs: List<Long>) {
        autoReconnectDelaysMs = delaysMs
    }

    @androidx.annotation.VisibleForTesting
    internal fun setPassiveDisconnectRecoveryForTest(
        graceMs: Long = passiveDisconnectGraceMs,
        silentReattachTimeoutMs: Long = this.silentReattachTimeoutMs,
    ) {
        passiveDisconnectGraceMs = graceMs
        this.silentReattachTimeoutMs = silentReattachTimeoutMs
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
        val current = inlineConnectionStatus
        if (current !is ConnectionStatus.Connecting && current !is ConnectionStatus.Reconnecting) return false
        pausedAutoReconnect = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        connectJob?.cancel()
        connectJob = null
        connectingTarget = null
        refreshReconnectAvailability()
        setConnectionState(
            ConnectionState.Unreachable("Connect cancelled by user."),
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

    // EPIC #687 slice 1c-iv-b-B1 (#738): the `preserveConnectingTarget` computed
    // SYNCHRONOUSLY by [detachForBackground] at background time, stashed for the
    // DRIVER-fired teardown ([launchBackgroundDetachTeardown]) to consume. The
    // value must be read at the same instant the old inline launch read it (before
    // any rapid foreground mutates `connectingTarget`), so the field captures it on
    // the background-decision turn rather than re-deriving it in the driver effect.
    private var pendingBackgroundDetachPreserveTarget: ConnectionTarget? = null
    private var sessionPrewarmJob: Job? = null
    private var foregroundReattachForTest: (() -> Unit)? = null
    private var processForegroundForClearedOverrideForTest: Boolean? = null
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
        foregroundRuntimeProbeJob?.cancel()
        foregroundRuntimeProbeJob = null
        // EPIC #687 slice 1c-iv-b-B1 (#738): run the inline SYNCHRONOUS bookkeeping
        // FIRST — [detachForBackground] sets `pendingReattach` and stashes
        // `pendingBackgroundDetachPreserveTarget`. THEN drive the controller to
        // Backgrounded, which fires the DRIVER's teardown effect
        // ([launchBackgroundDetachTeardown]). This ordering guarantees the bookkeeping
        // is in place before the driver launches the teardown — the driver collector
        // resumes off [observeBackground] (eagerly on the test main dispatcher; on the
        // next Main turn in production), so [detachForBackground] must have run first.
        // `pauseReconnectForBackground` (the no-client-detach branch) is unaffected:
        // the controller has no live host to enter Backgrounded, so no teardown fires.
        when (reduceConnection(ConnectionEvent.Background)) {
            ConnectionDecision.PauseReconnectForBackground ->
                pauseReconnectForBackground()
            ConnectionDecision.DetachForBackground ->
                detachForBackground()
            else -> Unit
        }
        // Feed the shadow controller the Background event — its transition INTO
        // Backgrounded is what fires the [ConnectionEffectDriver]'s clean-detach
        // teardown (the SOLE detach trigger now the inline launch is deleted).
        connectionControllerShadow.observeBackground()
        // The controller moved to Backgrounded (mapped → Connected, the inline
        // "keep prior status on background" behavior); re-project after the inline
        // effects ran (they read inlineConnectionStatus, unaffected by this).
        projectStatusFromController()
    }

    /**
     * EPIC #687 slice 1a: the [ConnectionDecision.PauseReconnectForBackground]
     * body — formerly inline at the top of [onAppBackgrounded]. Unchanged
     * behavior; only the decision moved to [reduceConnection].
     */
    private fun pauseReconnectForBackground() {
        val reconnecting = inlineConnectionStatus as? ConnectionStatus.Reconnecting ?: return
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        val target = activeTarget ?: connectingTarget
        recordAutoReconnectDecision(
            decision = "cancelled_due_to_background",
            target = target,
            trigger = latestConnectIntent?.trigger ?: TmuxConnectTrigger.AutoReconnect,
            reason = reconnecting.reason,
            cause = "app_background_lifecycle_pause",
            "attempt" to reconnecting.attempt,
            "maxAttempts" to reconnecting.maxAttempts,
            "retryDelayMs" to reconnecting.retryDelayMs,
        )
        if (target != null) {
            pausedAutoReconnect = PausedAutoReconnect(
                target = target,
                reason = reconnecting.reason,
            )
            activeTarget = target
            connectingTarget = target
            refreshReconnectAvailability()
        }
        setConnectionState(
            ConnectionState.Unreachable(
                "${reconnecting.reason} Auto reconnect paused while PocketShell is in the background.",
            ),
        )
    }

    /**
     * EPIC #687 slice 1a: the [ConnectionDecision.DetachForBackground] body —
     * formerly the lower half of [onAppBackgrounded]. The `target`/`clientRef||
     * sessionRef` guards already passed in [reduceBackground] but are kept here so
     * the side-effect body is self-contained and the field reads happen at the same
     * point in time as before.
     *
     * EPIC #687 slice 1c-iv-b-B1 (#738): this method keeps ONLY the SYNCHRONOUS
     * bookkeeping — `pendingReattach`, the detach telemetry, and stashing the
     * `preserveConnectingTarget` computed AT THIS INSTANT into
     * [pendingBackgroundDetachPreserveTarget]. It NO LONGER launches the teardown
     * job. The actual `closeCurrentConnectionAndJoin` teardown is now launched by
     * [launchBackgroundDetachTeardown], fired by the [ConnectionEffectDriver] on the
     * controller's transition INTO [ConnectionState.Backgrounded] (the inline
     * `backgroundDetachJob` *trigger* is deleted — D22 hard-cut). Keeping the
     * bookkeeping synchronous preserves the within-grace foreground reattach behavior
     * exactly: `onAppForegrounded` always sees `pendingReattach` set, identical to
     * before; only the teardown-job *launch* moved to the driver, with identical
     * `viewModelScope`/`NonCancellable` timing.
     */
    private fun detachForBackground() {
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
        DiagnosticEvents.record(
            "connection",
            "disconnect",
            "cause" to "background_teardown",
            "source" to "app_lifecycle",
            "trigger" to TmuxConnectTrigger.LifecycleReattach.logValue,
            "generation" to (intent?.generation ?: connectGeneration),
            "hostId" to target.hostId,
            "host" to target.host,
            "port" to target.port,
            "user" to target.user,
            "session" to target.sessionName,
            "clientHash" to clientRef?.let { System.identityHashCode(it) },
            *shortAppSwitchReconnectFields(
                trigger = TmuxConnectTrigger.LifecycleReattach,
                target = target,
                sourceCandidate = "background_teardown",
            ),
        )
        ReconnectCauseTrail.record(
            stage = "session_disconnect",
            outcome = "background_teardown",
            cause = "app_background_grace_elapsed",
            trigger = TmuxConnectTrigger.LifecycleReattach.logValue,
            "hostId" to target.hostId,
            "generation" to (intent?.generation ?: connectGeneration),
            "clientHash" to clientRef?.let { System.identityHashCode(it) },
            "hasPendingReattach" to true,
        )
        // Capture `preserveConnectingTarget` SYNCHRONOUSLY now (same instant the old
        // inline launch read it) so the driver-fired teardown uses the value from the
        // background turn, not a re-derivation after a possible rapid foreground.
        pendingBackgroundDetachPreserveTarget = connectingTarget?.takeIf { connecting ->
            !sameSessionIdentity(connecting, target) &&
                intent?.target?.let { sameSessionIdentity(it, connecting) } == true
        }
    }

    /**
     * EPIC #687 slice 1c-iv-b-B1 (#738): launch the clean background-detach teardown.
     * Fired by the [ConnectionEffectDriver] when the controller transitions INTO
     * [ConnectionState.Backgrounded] — the SOLE trigger now the inline
     * `backgroundDetachJob` launch (formerly the tail of [detachForBackground]) is
     * deleted. The teardown body is UNCHANGED from the inline one: the same
     * `viewModelScope.launch { withContext(NonCancellable) { closeCurrentConnectionAndJoin(...) } }`,
     * the same single-flight guard (`backgroundDetachJob?.isActive`), the same
     * `invokeOnCompletion` clear, the same `preserveConnectingTarget` — now read from
     * [pendingBackgroundDetachPreserveTarget] (stashed at background time). So the
     * teardown timing/behavior is byte-identical; only the trigger moved.
     */
    private fun launchBackgroundDetachTeardown() {
        if (backgroundDetachJob?.isActive == true) return
        val preserveConnectingTarget = pendingBackgroundDetachPreserveTarget
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
        // EPIC #687 slice 1c-iii: observe-only — the single grace predicate in the
        // shadow controller decides reattach-vs-reconnect; it drives nothing.
        connectionControllerShadow.observeForeground()
        when (reduceConnection(ConnectionEvent.Foreground)) {
            ConnectionDecision.ReplayPendingReattach ->
                replayPendingReattach()
            ConnectionDecision.ResumePausedReconnect ->
                pausedAutoReconnect?.let { resumePausedAutoReconnect(it) }
            ConnectionDecision.ProbeForeground ->
                probeCurrentRuntimeOnForegroundIfNeeded()
            else ->
                latestConnectIntent?.let { intent ->
                    Log.i(
                        ISSUE_235_LIFECYCLE_TAG,
                        "tmux-reattach-on-foreground-skip reason=no-pending " +
                            "generation=${intent.generation} trigger=${intent.trigger.logValue} " +
                            targetLogFields(intent.target),
                    )
                }
        }
        // The controller's single grace predicate decided reattach-vs-reconnect:
        // within grace (warm lease) it is Reattaching and the active-pane reseed will
        // land it back to Live → Connected (the approved #685 Bug-A divergence — NO
        // probe churn); beyond grace it is Reconnecting (matches inline). Re-project
        // after the inline effects ran.
        projectStatusFromController()
    }

    /**
     * EPIC #687 slice 1a: the [ConnectionDecision.ReplayPendingReattach] body —
     * formerly the second half of [onAppForegrounded]. Unchanged behavior; only
     * the entry decision moved to [reduceConnection].
     */
    private fun replayPendingReattach() {
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
            DiagnosticEvents.record(
                "connection",
                "foreground_reattach",
                "source" to "app_lifecycle",
                "trigger" to TmuxConnectTrigger.LifecycleReattach.logValue,
                "generation" to pending.generation,
                "hostId" to target.hostId,
                "host" to target.host,
                "port" to target.port,
                "user" to target.user,
                "session" to target.sessionName,
                *shortAppSwitchReconnectFields(
                    trigger = TmuxConnectTrigger.LifecycleReattach,
                    target = target,
                    sourceCandidate = "foreground_reattach",
                ),
            )
            ReconnectCauseTrail.record(
                stage = "foreground_reattach",
                outcome = "connect_requested",
                cause = "post_grace_foreground",
                trigger = TmuxConnectTrigger.LifecycleReattach.logValue,
                "hostId" to target.hostId,
                "generation" to pending.generation,
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

    private fun probeCurrentRuntimeOnForegroundIfNeeded(): Boolean {
        val current = inlineConnectionStatus
        if (current !is ConnectionStatus.Connected) return false
        val target = activeTarget ?: return false
        val client = clientRef ?: return false
        val session = sessionRef ?: return false
        if (foregroundRuntimeProbeJob?.isActive == true) return true
        val generation = connectGeneration
        val probeJob = viewModelScope.launch {
            var verdict = probeRuntimeControlChannel(client, session)
            // Issue #635 / #636 (Slice 1): a probe *timeout* on a still-live
            // socket means the link is slow/recovering, not dead. Retry once
            // after a short delay to give a still-recovering link a chance to
            // answer before we ride it through; we still never reconnect on a
            // timeout — sshj's 60s keepalive is the real death oracle.
            if (verdict == RuntimeHealthVerdict.TIMEOUT &&
                appActive &&
                connectGeneration == generation &&
                clientRef === client &&
                sessionRef === session
            ) {
                delay(RUNTIME_HEALTH_PROBE_RETRY_DELAY_MS)
                verdict = probeRuntimeControlChannel(client, session)
            }
            val healthy = verdict == RuntimeHealthVerdict.HEALTHY
            // A timeout is a ride-through: keep the live session and let the
            // SSH keepalive decide death. Only a confirmed dead transport
            // (disconnected / not-connected) or an explicit protocol/IO error
            // justifies tearing down and reconnecting.
            val rideThrough = verdict == RuntimeHealthVerdict.TIMEOUT
            ReconnectCauseTrail.record(
                stage = "tmux_probe_result",
                outcome = when {
                    healthy -> "healthy"
                    rideThrough -> "ride_through"
                    else -> "failed"
                },
                cause = "foreground_runtime_probe",
                trigger = TmuxConnectTrigger.LifecycleReattach.logValue,
                "hostId" to target.hostId,
                "generation" to generation,
                "clientHash" to System.identityHashCode(client),
                "failReason" to verdict.failReason,
            )
            if (healthy || rideThrough) {
                if (rideThrough) {
                    Log.i(
                        ISSUE_235_LIFECYCLE_TAG,
                        "tmux-foreground-runtime-probe-timeout ride-through " +
                            "generation=$generation " + targetLogFields(target),
                    )
                }
                return@launch
            }
            if (
                !appActive ||
                connectGeneration != generation ||
                clientRef !== client ||
                sessionRef !== session ||
                activeTarget?.let { sameSessionIdentity(it, target) } != true ||
                inlineConnectionStatus !is ConnectionStatus.Connected
            ) {
                ReconnectCauseTrail.record(
                    stage = "tmux_probe_result",
                    outcome = "failed_stale_skip",
                    cause = "foreground_runtime_probe_stale",
                    trigger = TmuxConnectTrigger.LifecycleReattach.logValue,
                    "hostId" to target.hostId,
                    "generation" to generation,
                    "clientStillCurrent" to (clientRef === client),
                    "sessionStillCurrent" to (sessionRef === session),
                    "appActive" to appActive,
                    "failReason" to verdict.failReason,
                )
                return@launch
            }
            Log.w(
                ISSUE_235_LIFECYCLE_TAG,
                "tmux-foreground-runtime-probe-failed reconnecting " +
                    "generation=$generation " + targetLogFields(target),
            )
            DiagnosticEvents.record(
                "connection",
                "foreground_runtime_probe_failed",
                "source" to "app_lifecycle",
                "trigger" to TmuxConnectTrigger.LifecycleReattach.logValue,
                "generation" to generation,
                "hostId" to target.hostId,
                "host" to target.host,
                "port" to target.port,
                "user" to target.user,
                "session" to target.sessionName,
                "clientHash" to System.identityHashCode(client),
                "failReason" to verdict.failReason,
                *shortAppSwitchReconnectFields(
                    trigger = TmuxConnectTrigger.LifecycleReattach,
                    target = target,
                    sourceCandidate = "foreground_runtime_probe",
                ),
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
                trigger = TmuxConnectTrigger.LifecycleReattach,
            )
        }
        foregroundRuntimeProbeJob = probeJob
        probeJob.invokeOnCompletion {
            if (foregroundRuntimeProbeJob == probeJob) {
                foregroundRuntimeProbeJob = null
            }
        }
        return true
    }

    /**
     * Activity-level visibility signal used only by [onCleared]'s runtime
     * parking decision. [ProcessLifecycleOwner] delays `ON_STOP`, so a
     * backgrounded Activity can destroy this ViewModel before the process
     * lifecycle state flips to stopped. The screen-level signal closes that
     * debounce hole while keeping explicit in-app navigation on the normal
     * foreground close path.
     */
    public fun onScreenStarted(sessionName: String) {
        screenStartedForCleared = true
        val paused = pausedAutoReconnect
        if (paused != null && pendingReattach == null) {
            if (paused.target.sessionName != sessionName) {
                // Issue #630: the paused reconnect targets a different session
                // than the one the screen is now composing for. This happens
                // when the user navigated back from session A to the host list
                // and then selected session B on the same host. Clear the stale
                // paused reconnect so it cannot race with the LaunchedEffect
                // that fires connect(sessionB).
                ReconnectCauseTrail.record(
                    stage = "onScreenStarted",
                    outcome = "cleared_stale_paused_reconnect",
                    cause = "session_mismatch",
                    trigger = TmuxConnectTrigger.AutoReconnect.logValue,
                    "pausedSession" to paused.target.sessionName,
                    "currentSession" to sessionName,
                )
                pausedAutoReconnect = null
            } else {
                resumePausedAutoReconnect(paused)
            }
        }
    }

    public fun onScreenStopped() {
        screenStartedForCleared = false
    }

    private fun resumePausedAutoReconnect(paused: PausedAutoReconnect) {
        pausedAutoReconnect = null
        val target = paused.target
        if (inlineConnectionStatus is ConnectionStatus.Connected && activeTarget == target) return
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

    internal fun setProcessForegroundForClearedForTest(isForeground: Boolean?) {
        processForegroundForClearedOverrideForTest = isForeground
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
            rebuildUnifiedPanes()
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
                .sortedWith(compareBy({ it.windowIndex ?: Int.MAX_VALUE }, { it.windowId }, { it.paneIndex }, { it.paneId }))
            for (pane in parsed) {
                val state = newTerminalSurfaceState()
                val row = TmuxPaneState(
                    paneId = pane.paneId,
                    windowId = pane.windowId,
                    windowIndex = pane.windowIndex,
                    sessionId = pane.sessionId,
                    title = pane.title,
                    cwd = pane.cwd,
                    currentCommand = pane.currentCommand,
                    paneTty = pane.paneTty,
                    inCopyMode = pane.inCopyMode,
                    terminalState = state,
                )
                paneRows[pane.paneId] = row
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
                    onTerminalFeedFailure = {
                        markPrewarmedPaneSurfaceError(
                            paneId = pane.paneId,
                            paneRows = paneRows,
                            paneProducerJobs = paneProducerJobs,
                            paneInputQueues = paneInputQueues,
                            paneInputJobs = paneInputJobs,
                        )
                    },
                )
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

    private fun markPrewarmedPaneSurfaceError(
        paneId: String,
        paneRows: MutableMap<String, TmuxPaneState>,
        paneProducerJobs: MutableMap<String, Job>,
        paneInputQueues: MutableMap<String, TmuxPaneInputQueue>,
        paneInputJobs: MutableMap<String, Job>,
    ) {
        val existing = paneRows[paneId] ?: return
        if (existing.surfaceError) return
        paneProducerJobs.remove(paneId)?.cancel()
        paneInputJobs.remove(paneId)?.cancel()
        paneInputQueues.remove(paneId)?.close()
        runCatching { existing.terminalState.detachExternalProducer() }
        paneRows[paneId] = existing.copy(surfaceError = true)
    }

    private suspend fun seedPrewarmedPane(client: TmuxClient, pane: TmuxPaneState) {
        // Issue #468: the live %output producer is gated behind the seed.
        // appendRemoteOutput opens the gate when a snapshot lands; if the
        // capture fails (or anything throws) we must still open the gate so
        // buffered live output is flushed in order rather than swallowed.
        var seeded = false
        try {
            // Issue #640: single-flight combined capture+cursor exchange (see
            // [TmuxClient.captureWithCursor]) shared with the cold-open seed
            // path so both pay one wire round-trip and restore the #259 cursor.
            val combined = runCatching {
                client.captureWithCursor(pane.paneId, scrollbackLines = SEED_SCROLLBACK_LINES)
            }.getOrNull() ?: return
            val capture = combined.capture
            if (capture.isError || capture.output.isEmpty()) return
            val cursor = parseTmuxPaneCursor(combined.cursorReply)
            pane.terminalState.appendRemoteOutput(
                capture.output.toTerminalViewportBytes(cursor),
            )
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
        unregisterLifecycleHooks()
        lifecycleHookRegistration = activeTmuxClients.registerLifecycleHooks(
            hostId = hostId,
            hooks = ActiveTmuxClients.LifecycleHooks(
                onBackground = { onAppBackgrounded() },
                onForeground = { onAppForegrounded() },
                onNetworkChanged = { change -> onNetworkChanged(change) },
            ),
        )
        lifecycleHookHostId = hostId
    }

    private fun unregisterCurrentClient() {
        clientRegistration?.let { activeTmuxClients.unregister(it) }
        clientRegistration = null
        registeredHostId = null
    }

    private fun unregisterLifecycleHooks() {
        lifecycleHookRegistration?.let { activeTmuxClients.unregisterLifecycleHooks(it) }
        lifecycleHookRegistration = null
        lifecycleHookHostId = null
    }

    /**
     * Issue #548: when Android reports a validated default-network change
     * while the terminal is foregrounded, reconnect immediately instead of
     * waiting for sshj's reader to discover that the old TCP path died.
     */
    public fun onNetworkChanged(change: TerminalNetworkChange) {
        val target = activeTarget ?: connectingTarget
        // EPIC #687 slice 1c-iii: observe-only — feed the shadow controller the
        // #548 validated-handoff signal it suppresses on (computed identically to
        // the inline reducer). It drives nothing.
        connectionControllerShadow.observeNetworkChanged(
            validatedHandoff = change.previousValidated?.let { previous ->
                !previous.hasSameNetworkIdentityAs(change.current)
            } ?: false,
        )
        when (reduceConnection(ConnectionEvent.NetworkChanged(change))) {
            ConnectionDecision.SuppressNetworkNotValidated ->
                if (target != null) suppressNetworkNotValidated(change, target)
            ConnectionDecision.SuppressNetworkCoalesced ->
                if (target != null) suppressNetworkCoalesced(change, target)
            ConnectionDecision.ScheduleNetworkReconnect ->
                if (target != null) scheduleNetworkReconnect(change, target)
            else -> Unit
        }
        // NOTE: the network-change reconnect/coalesce decision is INLINE EFFECT
        // machinery (the #548 suppression + the post-grace deferred-replay COALESCE)
        // that 1c-iv-b owns — it is NOT one of the two approved #685 display changes.
        // The controller's `onNetworkChanged` is intentionally more eager (a validated
        // handoff → Reconnecting) than the inline coalesce, so we do NOT project the
        // controller's network-change transition here: when the inline path actually
        // reconnects it goes through [setConnectionState] (which projects); when it
        // COALESCES (deferred replay after a fresh lifecycle attach) the display
        // correctly stays Connected. Driving the controller's eager reconnect here
        // would regress the coalesce. The bridge still observes the event (above) for
        // the 1c-iv-b effect flip.
    }

    /**
     * EPIC #687 slice 1a: the [ConnectionDecision.SuppressNetworkNotValidated]
     * body — formerly inline in [onNetworkChanged]. Unchanged behavior.
     */
    private fun suppressNetworkNotValidated(
        change: TerminalNetworkChange,
        target: ConnectionTarget,
    ) {
        val reason = change.reason
        val previousValidated = change.previousValidated
        run {
            Log.i(
                ISSUE_548_NETWORK_TAG,
                "tmux-network-proactive-reconnect-skip reason=$reason " +
                    "cause=no-real-validated-handoff " +
                    "previousValidated=${previousValidated?.logValue ?: "none"} " +
                    "current=${change.current.logValue} " +
                    targetLogFields(target),
            )
            DiagnosticEvents.record(
                "connection",
                "network_reconnect_skip",
                "source" to "network_observer",
                "trigger" to TmuxConnectTrigger.NetworkReconnect.logValue,
                "reason" to reason,
                "cause" to "no_real_validated_handoff",
                "classification" to "network_identity_unchanged",
                "reconnect" to false,
                "realValidatedIdentityChange" to false,
                "sequence" to change.sequence,
                "hostId" to target.hostId,
                "host" to target.host,
                "port" to target.port,
                "user" to target.user,
                "session" to target.sessionName,
                "generation" to connectGeneration,
                "attempt" to activeAttachMilestone?.attempt,
                "activeTrigger" to activeAttachMilestone?.trigger?.logValue,
                "deferredFromBackground" to change.deferredFromBackground,
                *change.networkDiagnosticFields(),
                *shortAppSwitchReconnectFields(
                    trigger = TmuxConnectTrigger.NetworkReconnect,
                    target = target,
                    sourceCandidate = "network_observer",
                ),
            )
            ReconnectCauseTrail.record(
                stage = "network_reconnect_decision",
                outcome = "suppress",
                cause = "no_real_validated_handoff",
                trigger = TmuxConnectTrigger.NetworkReconnect.logValue,
                "sequence" to change.sequence,
                "hostId" to target.hostId,
                "generation" to connectGeneration,
                "classification" to "network_identity_unchanged",
                "deferredFromBackground" to change.deferredFromBackground,
            )
        }
    }

    /**
     * EPIC #687 slice 1a: the [ConnectionDecision.SuppressNetworkCoalesced]
     * body — formerly inline in [onNetworkChanged]. Unchanged behavior. The
     * `realValidatedIdentityChange` flag is always `true` on this path (the
     * reducer reached here only past the validated-handoff gate), preserved as a
     * literal so the diagnostic field value is byte-identical.
     */
    private fun suppressNetworkCoalesced(
        change: TerminalNetworkChange,
        target: ConnectionTarget,
    ) {
        val reason = change.reason
        val realValidatedIdentityChange = true
        val lifecycleCoalesce = lifecycleReattachNetworkCoalesce ?: return
        run {
            lifecycleReattachNetworkCoalesce = null
            Log.i(
                ISSUE_548_NETWORK_TAG,
                "tmux-network-proactive-reconnect-skip reason=$reason " +
                    "cause=coalesced-with-lifecycle-reattach " +
                    "sequence=${change.sequence} generation=${lifecycleCoalesce.generation} " +
                    "deferredFromBackground=${change.deferredFromBackground} " +
                    targetLogFields(target),
            )
            DiagnosticEvents.record(
                "connection",
                "network_reconnect_skip",
                "source" to "network_observer",
                "trigger" to TmuxConnectTrigger.NetworkReconnect.logValue,
                "reason" to reason,
                "cause" to "coalesced_with_lifecycle_reattach",
                "classification" to "lifecycle_network_replay",
                "reconnect" to false,
                "realValidatedIdentityChange" to realValidatedIdentityChange,
                "sequence" to change.sequence,
                "generation" to lifecycleCoalesce.generation,
                "hostId" to target.hostId,
                "host" to target.host,
                "port" to target.port,
                "user" to target.user,
                "session" to target.sessionName,
                "deferredFromBackground" to change.deferredFromBackground,
                *change.networkDiagnosticFields(),
                *shortAppSwitchReconnectFields(
                    trigger = TmuxConnectTrigger.NetworkReconnect,
                    target = target,
                    sourceCandidate = "network_observer",
                ),
            )
            ReconnectCauseTrail.record(
                stage = "network_reconnect_decision",
                outcome = "suppress",
                cause = "coalesced_with_lifecycle_reattach",
                trigger = TmuxConnectTrigger.NetworkReconnect.logValue,
                "sequence" to change.sequence,
                "hostId" to target.hostId,
                "generation" to lifecycleCoalesce.generation,
                "classification" to "lifecycle_network_replay",
                "deferredFromBackground" to change.deferredFromBackground,
            )
        }
    }

    /**
     * EPIC #687 slice 1a: the [ConnectionDecision.ScheduleNetworkReconnect]
     * body — formerly the tail of [onNetworkChanged]. Unchanged behavior. The
     * `realValidatedIdentityChange` flag is always `true` on this path (only a
     * real validated handoff reaches here), preserved as a literal for the
     * diagnostic field. `current` is re-read; the reducer guaranteed Connected.
     */
    private fun scheduleNetworkReconnect(
        change: TerminalNetworkChange,
        target: ConnectionTarget,
    ) {
        val current = inlineConnectionStatus as? ConnectionStatus.Connected ?: return
        val reason = change.reason
        val realValidatedIdentityChange = true
        lifecycleReattachNetworkCoalesce = null
        val reconnectReason = "Network changed; reconnecting ${current.user}@${current.host}:${current.port}."
        Log.i(
            ISSUE_548_NETWORK_TAG,
            "tmux-network-proactive-reconnect reason=$reason " +
                targetLogFields(target),
        )
        DiagnosticEvents.record(
            "connection",
            "network_reconnect_start",
            "source" to "network_observer",
            "trigger" to TmuxConnectTrigger.NetworkReconnect.logValue,
            "reason" to reason,
            "classification" to "proactive_network_handoff",
            "reconnect" to true,
            "realValidatedIdentityChange" to realValidatedIdentityChange,
            "sequence" to change.sequence,
            "hostId" to target.hostId,
            "host" to target.host,
            "port" to target.port,
            "user" to target.user,
            "session" to target.sessionName,
            "generation" to connectGeneration,
            "attempt" to activeAttachMilestone?.attempt,
            "activeTrigger" to activeAttachMilestone?.trigger?.logValue,
            "clientHash" to clientRef?.let { System.identityHashCode(it) },
            "deferredFromBackground" to change.deferredFromBackground,
            *change.networkDiagnosticFields(),
            *shortAppSwitchReconnectFields(
                trigger = TmuxConnectTrigger.NetworkReconnect,
                target = target,
                sourceCandidate = "network_observer",
            ),
        )
        ReconnectCauseTrail.record(
            stage = "network_reconnect_decision",
            outcome = "schedule_reconnect",
            cause = "proactive_network_handoff",
            trigger = TmuxConnectTrigger.NetworkReconnect.logValue,
            "sequence" to change.sequence,
            "hostId" to target.hostId,
            "generation" to connectGeneration,
            "classification" to "proactive_network_handoff",
            "deferredFromBackground" to change.deferredFromBackground,
            "activeAttempt" to activeAttachMilestone?.attempt,
        )
        unregisterCurrentClient()
        scheduleAutoReconnect(
            target = target,
            reason = reconnectReason,
            trigger = TmuxConnectTrigger.NetworkReconnect,
        )
    }

    private fun takeCachedRuntimeForActivation(
        target: ConnectionTarget,
        attempt: Int,
        trigger: TmuxConnectTrigger,
        visibleSwitchStartedAtMs: Long,
    ): CachedTmuxRuntime? {
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
            return null
        }
        if (cached.client.disconnected.value || cached.session?.isConnected != true) {
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
            return null
        }
        return cached
    }

    private suspend fun tryActivateCachedRuntime(
        target: ConnectionTarget,
        attempt: Int,
        trigger: TmuxConnectTrigger,
        visibleSwitchStartedAtMs: Long,
        cached: CachedTmuxRuntime,
        generation: Long,
    ): Boolean {
        if (!cachedRuntimeControlChannelHealthy(cached)) {
            val startedAtMs = visibleSwitchStartedAtMs.takeIf { it > 0L }
                ?: SystemClock.elapsedRealtime()
            recordWarmSwitchMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                name = "warm_switch_runtime_cache_miss",
                trigger = trigger,
                detail = "source=runtime_cache reason=health_probe_failed",
            )
            runCatching { cached.client.close() }
            viewModelScope.launch {
                cached.closeCachedRuntime()
                cached.lease?.key?.let { key ->
                    runCatching { sshLeaseManager.disconnect(key) }
                }
            }
            return false
        }
        val startedAtMs = SystemClock.elapsedRealtime()
        val milestoneStartedAtMs = visibleSwitchStartedAtMs.takeIf { it > 0L } ?: startedAtMs
        closeCachedRuntimesAsync(deactivateCurrentRuntimeToCache())
        restoreCachedRuntime(
            target = target,
            runtime = cached,
            trigger = trigger,
        )
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

    private suspend fun cachedRuntimeControlChannelHealthy(runtime: CachedTmuxRuntime): Boolean {
        // Switch-path activation needs a fast verdict: a slow cached runtime
        // is treated as a miss so the connect path falls back to a fresh
        // attach. Here a timeout is a fall-back signal, unlike the
        // within-grace foreground probe which must ride it through.
        return probeRuntimeControlChannel(runtime.client, runtime.session) == RuntimeHealthVerdict.HEALTHY
    }

    /**
     * Probe the live tmux control channel and classify the result.
     *
     * Issue #635 / #636 (Slice 1): the within-grace foreground resume must
     * NOT treat a probe *timeout* as proof of death. `SshSession.isConnected`
     * only reflects whether the socket object is open — for a silently
     * dropped TCP path it stays `true` until sshj's keepalive miss-counter
     * trips (15s × 4 = 60s, deliberately matched to the 60s background
     * grace). A `display-message` round-trip that takes longer than the
     * 750ms probe budget on a still-`isConnected` transport means the link
     * is slow/recovering, not dead. Returning a distinct
     * [RuntimeHealthVerdict.TIMEOUT] lets the foreground probe ride that
     * through and defer the death verdict to sshj's keepalive oracle, while
     * the switch-path activation still falls back on any non-healthy result.
     */
    private suspend fun probeRuntimeControlChannel(
        client: TmuxClient,
        session: SshSession?,
    ): RuntimeHealthVerdict {
        if (client.disconnected.value) return RuntimeHealthVerdict.DISCONNECTED
        if (session?.isConnected != true) return RuntimeHealthVerdict.NOT_CONNECTED
        val outcome = runCatching {
            withTimeoutOrNull(RUNTIME_HEALTH_PROBE_TIMEOUT_MS) {
                client.sendBestEffortCommand("display-message -p '#{session_name}'")
            }
        }
        val response = outcome.getOrElse {
            // A thrown write/read failure (e.g. a TCP reset the SSH library
            // surfaced as an exception) is a genuine transport error, not a
            // ride-through-able slowness.
            return RuntimeHealthVerdict.ERROR
        } ?: return RuntimeHealthVerdict.TIMEOUT
        return if (response.isError) RuntimeHealthVerdict.ERROR else RuntimeHealthVerdict.HEALTHY
    }

    private fun deactivateCurrentRuntimeToCache(): List<CachedTmuxRuntime> {
        val target = activeTarget ?: return emptyList()
        val client = clientRef ?: return emptyList()
        eventsJob?.cancel()
        eventsJob = null
        outputOverflowJob?.cancel()
        outputOverflowJob = null
        passiveDisconnectGraceJob?.cancel()
        passiveDisconnectGraceJob = null
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
        val evicted = runtimeCache.put(runtime)
        leaseRef = null
        sessionRef = null
        clientRef = null
        paneRows.clear()
        paneProducerJobs.clear()
        paneOutputActivityJobs.values.forEach { it.cancel() }
        paneOutputActivityJobs.clear()
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
        rebuildUnifiedPanes()
        unregisterCurrentClient()
        activeTarget = null
        activeAttachMilestone = null
        return evicted
    }

    private fun rebindRestoredRuntimePaneJobsIfNeeded(
        client: TmuxClient,
        panes: List<TmuxPaneState>,
    ) {
        for (pane in panes) {
            val paneId = pane.paneId
            val producerActive = paneProducerJobs[paneId]?.isActive == true
            val inputActive = paneInputJobs[paneId]?.isActive == true
            if (producerActive && inputActive && pane.terminalState.isAttached) {
                startPaneOutputActivityForPane(paneId = paneId, client = client)
                startPortDetectionForPane(paneId = paneId, client = client)
                continue
            }
            paneProducerJobs.remove(paneId)?.cancel()
            paneOutputActivityJobs.remove(paneId)?.cancel()
            paneInputJobs.remove(paneId)?.cancel()
            paneInputQueues.remove(paneId)?.close()
            pane.terminalState.detachExternalProducer()
            attachTerminalProducerForPane(
                paneId = paneId,
                state = pane.terminalState,
                client = client,
            )
        }
    }

    private fun closeCachedRuntimesAsync(runtimes: List<CachedTmuxRuntime>) {
        if (runtimes.isEmpty()) return
        viewModelScope.launch {
            runtimes.forEach { runtime ->
                runtime.closeCachedRuntime()
            }
        }
    }

    private fun closeCachedRuntimesBlocking(runtimes: List<CachedTmuxRuntime>) {
        if (runtimes.isEmpty()) return
        // Issue #710: this runs on the MAIN thread (onCleared park-on-clear).
        // [closeCachedRuntime] already bounds its own suspending steps at
        // SYNC_DETACH_TIMEOUT_MS, but we add a belt-and-suspenders outer ceiling
        // so the main thread is GUARANTEED to return even if a future teardown
        // step regresses to unbounded. The outer budget scales with the runtime
        // count (each runtime gets its detach budget) so the normal one/two
        // runtime park stays fast.
        runCatching {
            runBlocking(Dispatchers.IO) {
                withTimeoutOrNull(SYNC_DETACH_TIMEOUT_MS * runtimes.size) {
                    runtimes.forEach { runtime ->
                        runtime.closeCachedRuntime(detachTimeoutMs = SYNC_DETACH_TIMEOUT_MS)
                    }
                }
            }
        }
    }

    private fun restoreCachedRuntime(
        target: ConnectionTarget,
        runtime: CachedTmuxRuntime,
        trigger: TmuxConnectTrigger,
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
        paneOutputActivityJobs.clear()
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
        rebindRestoredRuntimePaneJobsIfNeeded(runtime.client, runtime.panes)
        _panes.value = runtime.panes
        rebuildUnifiedPanes()
        restartAgentConversationsForRestoredRuntime(runtime)
        clientRegistration = activeTmuxClients.register(
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
        // Issue #661: the cache-hit path activates the TARGET session's own
        // cached frame (already published above), so there is never a leaving
        // frame to hide here; clear the gate defensively in case a prior
        // in-flight fast switch had set it.
        _switchHidesTerminal.value = false
        setConnectionState(
            ConnectionState.Live(
                target.host,
                target.port,
                target.user,
            ),
        )
        markSuccessfulAttachForNetworkCoalescing(target, trigger)
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
        val forceFreshLease = shouldForceFreshLease(trigger)
        val evictedIdleLease = if (forceFreshLease) {
            withContext(NonCancellable) {
                runtimeCache.removeLease(leaseTarget.leaseKey).forEach { cached ->
                    runCatching { cached.closeCachedRuntime() }
                }
                runCatching { sshLeaseManager.evictIdle(leaseTarget.leaseKey) }
                    .getOrDefault(false)
            }
        } else {
            false
        }
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
        if (forceFreshLease) {
            val sessionHash = System.identityHashCode(lease.session)
            DiagnosticEvents.record(
                "connection",
                "tmux_force_fresh_ssh_lease",
                "attempt" to attempt,
                "hostId" to target.hostId,
                "host" to target.host,
                "port" to target.port,
                "user" to target.user,
                "session" to target.sessionName,
                "trigger" to trigger.logValue,
                "evictedLease" to evictedIdleLease,
                "freshTransport" to lease.isNewConnection,
                "sshSessionHash" to sessionHash,
            )
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-force-fresh-ssh-lease trigger=${trigger.logValue} evictedLease=$evictedIdleLease " +
                    "freshTransport=${lease.isNewConnection} sshSessionHash=$sessionHash " +
                    "${targetLogFields(target)} attempt=$attempt",
            )
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

    private fun shouldForceFreshLease(trigger: TmuxConnectTrigger): Boolean =
        trigger == TmuxConnectTrigger.Reconnect ||
            trigger == TmuxConnectTrigger.LifecycleReattach ||
            trigger == TmuxConnectTrigger.NetworkReconnect

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
        // Issue #634: a "warm open" reuses an already-live pooled SSH lease,
        // so it attaches like a desktop `tmux attach` — instantly, under the
        // green [Switching] "Attaching" affordance — instead of the blanking
        // full-screen [Connecting] overlay a genuine cold connect shows. When
        // true we reveal as soon as every visible pane is seeded by
        // [awaitPanesReadyForAttach]'s [preloadVisibleContentForNewPanes],
        // skipping the extra reveal-gating reseed pass; when false this is a
        // cold connect and the [Connecting] overlay + full reseed-before-reveal
        // is preserved unchanged.
        warmReveal: Boolean = false,
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

            // Issue #666: on a genuine foreground cold-restore, attach attach-only
            // so a session killed elsewhere is NOT recreated. [client.connect]
            // throws [TmuxSessionNotFoundException] for a gone session, which the
            // catch below maps to "that session ended" + drop to the list.
            val client = createTmuxClient(
                session,
                target.sessionName,
                target.startDirectory,
                createIfMissing = createIfMissingForTrigger(trigger),
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
            clientRegistration = activeTmuxClients.register(
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

            // Issue #640: track per-attach seeding so the reveal can skip the
            // redundant second full reseed for panes the preload already
            // painted. Cleared here so a reconnect starts fresh.
            panesSeededThisAttach.clear()

            awaitPanesReadyForAttach(
                target = target,
                attempt = attempt,
                startedAtMs = startedAtMs,
                trigger = trigger,
            )

            maybeRefreshControlClientSize()
            // Issue #553/#640: tmux `-CC` does not re-emit existing pane content
            // on a fresh control-client attach — only subsequent `%output`
            // deltas. Re-seed every visible pane from a `capture-pane` snapshot
            // BEFORE revealing the Connected surface so the user never sees a
            // blank/partial grid with a lone live spinner painting against
            // black (#640 fragments-then-fill). [preloadVisibleContentForNewPanes]
            // already seeded the freshly-created pane rows during
            // [awaitPanesReadyForAttach]; [reseedAllVisiblePanes] now only
            // re-captures panes *reused* across a racing reconcile / reattach
            // (the #553 safety net) — it skips panes already seeded this attach,
            // so a cold open pays no duplicate capture pass.
            //
            // Issue #634: a warm open already seeded every freshly-created pane
            // via [preloadVisibleContentForNewPanes] during
            // [awaitPanesReadyForAttach] (all panes are new on a first open of
            // this session, so [panesSeededThisAttach] covers them) — the
            // [reseedAllVisiblePanes] pass below would be a pure no-op there. We
            // skip it to keep the warm reveal as tight as a fast switch; the
            // safety-net reseed still runs for the cold path where a racing
            // reconcile may have left a *reused* pane unseeded.
            if (!warmReveal) {
                reseedAllVisiblePanes(
                    RuntimeRefreshGuard(
                        generation = connectGeneration,
                        target = target,
                        client = client,
                    ),
                )
            }

            // Issue #640: reveal the Connected surface only AFTER every visible
            // pane has been seeded above, so the first frame the user sees is
            // the complete screen rather than a black/partial grid that fills in.
            connectingTarget = null
            refreshReconnectAvailability()
            val blankReseedGuard = RuntimeRefreshGuard(
                generation = connectGeneration,
                target = target,
                client = client,
            )
            // Issue #693/#661: NEVER reveal a black active pane on this cold/slow
            // path either (it is also the dead-lease fast-switch escalation
            // target). Gate the reveal on a non-empty active-pane seed; only
            // clear [_switchHidesTerminal] (the loading overlay) once the active
            // pane is non-blank, otherwise keep the calm "Attaching…" overlay and
            // hand off to [armConnectedBlankWatchdog].
            val activePaneSeeded = awaitActivePaneSeededOrLoading(blankReseedGuard)
            // Issue #661: clear any cross-session "hide terminal" gate at the
            // reveal so a fast switch that fell through to this slow/cold path
            // (e.g. a dead-lease escalation) still reveals the new session's
            // seeded panes rather than staying on the loading placeholder — but
            // only when the active pane actually has content (#693).
            _switchHidesTerminal.value = !activePaneSeeded
            setConnectionState(
                ConnectionState.Live(
                    target.host,
                    target.port,
                    target.user,
                ),
            )
            if (!activePaneSeeded) armConnectedBlankWatchdog(blankReseedGuard)
            markSuccessfulAttachForNetworkCoalescing(target, trigger)
            // Issue #662: black-pane safety net for the open/switch path even
            // when the phone grid happens to MATCH tmux's current size (so
            // [maybeRefreshControlClientSize] short-circuits and its
            // resize-completion blank-reseed never fires). A pane whose
            // attach-time `capture-pane` seed failed/timed out under real-network
            // latency would then stay black on a live connection with no resize
            // to heal it. Defer one blank-only re-seed so any still-black visible
            // pane is re-captured from tmux's grid; a no-op for panes that
            // already painted (the common case) and for the resize path (already
            // healed). Guarded so a superseded runtime never re-seeds.
            bridgeScope.launch {
                delay(BLANK_PANE_RESEED_DELAY_MS)
                if (!isCurrentRuntime(blankReseedGuard)) return@launch
                reseedBlankVisiblePanes(blankReseedGuard)
            }
            logAttachMilestone(
                attempt = attempt,
                target = target,
                startedAtMs = startedAtMs,
                event = "tmux-connect-ready",
                trigger = trigger,
            )
            DiagnosticEvents.record(
                "connection",
                when (trigger) {
                    TmuxConnectTrigger.Reconnect,
                    TmuxConnectTrigger.AutoReconnect,
                    TmuxConnectTrigger.NetworkReconnect,
                    -> "reconnect_success"
                    else -> "connect_success"
                },
                "attempt" to attempt,
                "hostId" to target.hostId,
                "host" to target.host,
                "port" to target.port,
                "user" to target.user,
                "session" to target.sessionName,
                "trigger" to trigger.logValue,
                "generation" to latestConnectIntent?.takeIf {
                    sameSessionIdentity(it.target, target)
                }?.generation,
                "clientHash" to System.identityHashCode(client),
                "elapsedMs" to (SystemClock.elapsedRealtime() - startedAtMs),
                *shortAppSwitchReconnectFields(
                    trigger = trigger,
                    target = target,
                    sourceCandidate = "connect_success",
                ),
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            // Issue #666: the cold-restore attach-only preflight found the
            // target session gone (killed elsewhere while backgrounded). Do
            // NOT route this through the failure/auto-reconnect machinery — a
            // reconnect would just re-run the same gone-session attach. Surface
            // "that session ended" and drop the user to the list instead of
            // resurrecting it.
            if (t is TmuxSessionNotFoundException) {
                failSessionEnded(target, attempt, startedAtMs, t)
            } else {
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
    }

    /**
     * Issue #666: terminal handling for an attach-only cold-restore whose
     * target tmux session no longer exists on the server. Unlike
     * [failConnectAttempt] this does NOT preserve a reconnect target, evict a
     * "poisoned" lease, or kick off auto-recovery — the session is simply gone,
     * so retrying the same attach is pointless and (with `new-session -A`) would
     * recreate it, which is exactly the bug. We tear the half-open connection
     * down, surface a [ConnectionStatus.Failed] "that session ended" message as
     * a fallback for any surface that does not navigate, and emit the one-shot
     * [sessionEnded] event so the Screen drops to the host/session list and the
     * app clears the persisted last-session snapshot.
     */
    private suspend fun failSessionEnded(
        target: ConnectionTarget,
        attempt: Int,
        startedAtMs: Long,
        cause: TmuxSessionNotFoundException,
    ) {
        lastConnectFailureCause = cause
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-restore-session-gone session=${target.sessionName} " +
                "${targetLogFields(target)} attempt=$attempt — dropping to list, not recreating",
        )
        DiagnosticEvents.record(
            "connection",
            "restore_session_ended",
            "attempt" to attempt,
            "hostId" to target.hostId,
            "host" to target.host,
            "port" to target.port,
            "user" to target.user,
            "session" to target.sessionName,
            "cause" to cause.javaClass.simpleName,
            "elapsedMs" to (SystemClock.elapsedRealtime() - startedAtMs),
        )
        withContext(NonCancellable) {
            closeCurrentConnectionAndJoin(
                cacheEviction = RuntimeCacheEviction.TargetRuntime(target.toRuntimeKey()),
            )
        }
        activeAttachMilestone = null
        activeTarget = null
        connectingTarget = null
        refreshReconnectAvailability()
        setConnectionState(
            ConnectionState.Unreachable("Session “${target.sessionName}” has ended."),
        )
        _sessionEnded.tryEmit(target.sessionName)
    }

    /**
     * Issue #666: cold-restore liveness gate. Runs as the [connectJob] for a
     * [TmuxConnectTrigger.ColdRestore] connect and probes `tmux has-session`
     * over the pooled SSH transport BEFORE any attach or warm/cached-runtime
     * activation.
     *
     * Why up front, not just in [runConnect]: the resurrection the maintainer
     * hit happens when the restore activates a STALE warm/cached runtime whose
     * `-CC` channel then EOFs (the session was killed), which kicks the
     * auto-reconnect path — and that path re-attaches with `new-session -A`,
     * recreating the session. Probing here, before we touch any cached runtime,
     * catches the gone session regardless of which attach path would follow.
     *
     * - Session GONE (`has-session` exits non-zero): surface "that session
     *   ended" and drop to the list via [failSessionEnded] — never attach,
     *   never recreate.
     * - Session ALIVE (`has-session` exits 0): re-enter [connect] with
     *   `skipColdRestorePreflight = true` so the normal (warm/cached or cold)
     *   ColdRestore attach proceeds unchanged — no #634/#177 regression.
     * - Probe could not run (no lease / exec error): fail OPEN — fall through
     *   to the normal attach so a transient transport hiccup never masquerades
     *   as "session ended". The attach-only [runConnect] preflight is the
     *   second line of defence there.
     */
    private fun preflightColdRestore(
        hostId: Long,
        hostName: String,
        host: String,
        port: Int,
        user: String,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
        startDirectory: String?,
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
        // Cancel any in-flight connect so the preflight owns the decision, then
        // gate every downstream attach behind it. Mirrors the teardown the
        // normal connect entry does for its early state.
        passiveDisconnectGraceJob?.cancel()
        passiveDisconnectGraceJob = null
        pausedAutoReconnect = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        connectingTarget = target
        refreshReconnectAvailability()
        setConnectionState(ConnectionState.Connecting(host, port, user))
        connectJob = viewModelScope.launch {
            val leaseTarget = target.toSshLeaseTarget()
            val lease = sshLeaseManager.acquire(leaseTarget).getOrNull()
            val proceed = {
                // Drop our own (still-active) preflight job + connectingTarget so
                // the re-entered connect is not swallowed by its
                // `connectJob.isActive && connectingTarget == target`
                // early-return guard.
                connectJob = null
                connectingTarget = null
                connect(
                    hostId = hostId,
                    hostName = hostName,
                    host = host,
                    port = port,
                    user = user,
                    keyPath = keyPath,
                    passphrase = passphrase?.copyOf(),
                    sessionName = sessionName,
                    startDirectory = startDirectory,
                    trigger = TmuxConnectTrigger.ColdRestore,
                    skipColdRestorePreflight = true,
                )
            }
            if (lease == null) {
                // No transport to probe with — fail open and let the normal
                // ColdRestore attach (and its attach-only runConnect preflight)
                // handle it.
                Log.i(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-cold-restore-preflight-no-lease session=$sessionName — failing open to normal attach",
                )
                proceed()
                return@launch
            }
            val probe = runCatching {
                lease.session.exec(
                    "tmux has-session -t '${escapeSingleQuoted(sessionName)}'",
                )
            }
            // Return the pooled transport so the subsequent attach can reuse it.
            runCatching { lease.release() }
            val result = probe.getOrNull()
            when {
                result == null -> {
                    // The probe exec itself failed (transport hiccup). Fail open.
                    Log.i(
                        ISSUE_145_RECONNECT_TAG,
                        "tmux-cold-restore-preflight-probe-error session=$sessionName " +
                            "cause=${probe.exceptionOrNull()?.javaClass?.simpleName} — failing open",
                    )
                    proceed()
                }
                result.exitCode != 0 -> {
                    // Session is GONE — do NOT attach or recreate it.
                    Log.i(
                        ISSUE_145_RECONNECT_TAG,
                        "tmux-cold-restore-session-gone session=$sessionName exit=${result.exitCode} " +
                            "— dropping to list, not recreating",
                    )
                    failSessionEnded(
                        target = target,
                        attempt = 0,
                        startedAtMs = SystemClock.elapsedRealtime(),
                        cause = TmuxSessionNotFoundException(sessionName),
                    )
                }
                else -> {
                    // Session is alive — proceed with the normal restore attach.
                    proceed()
                }
            }
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
                setConnectionState(
                    ConnectionState.Connecting(
                        target.host,
                        target.port,
                        target.user,
                    ),
                )
                _panes.value = emptyList()
                rebuildUnifiedPanes()
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
            clientRegistration = activeTmuxClients.register(
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
            // Issue #693/#661: NEVER reveal a black active pane. #661's O(1)
            // reveal gates on the active pane's single capture — a degraded
            // switch could otherwise reveal a BLACK active pane with a green dot
            // and no reconnecting band. Gate the reveal on a non-empty active-
            // pane seed (bounded retries); only clear [_switchHidesTerminal]
            // (the loading overlay) once the active pane is non-blank.
            val fastSwitchRevealGuard = RuntimeRefreshGuard(
                generation = connectGeneration,
                target = target,
                client = client,
            )
            val activePaneSeeded = awaitActivePaneSeededOrLoading(fastSwitchRevealGuard)
            // Issue #661: the NEW session's panes are seeded (reconciled in
            // [awaitPanesReadyForAttach]); reveal the terminal surface in the
            // SAME state mutation that flips to Connected so the first painted
            // frame is the new session's content — never a transient blank or
            // (worse) the leaving session's frame. When the active pane could
            // NOT be seeded non-blank within the gate's bound, keep the loading
            // overlay raised and hand off to [armConnectedBlankWatchdog] so the
            // user sees a calm "Attaching…" rather than a black Connected pane.
            _switchHidesTerminal.value = !activePaneSeeded
            setConnectionState(
                ConnectionState.Live(
                    target.host,
                    target.port,
                    target.user,
                ),
            )
            if (!activePaneSeeded) armConnectedBlankWatchdog(fastSwitchRevealGuard)
            markSuccessfulAttachForNetworkCoalescing(target, trigger)
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
            // Issue #621 / #634 / #636 (Slice 4): the fast-switch reuses the
            // warm SSH session directly. When that session is silently dead
            // (`session.isConnected` lies until sshj's 60s keepalive trips),
            // the `tmux -CC` open / `list-panes` EOFs here. Rather than
            // surfacing a Disconnected band + manual Reconnect, evict the
            // poisoned lease and fall back to the full [runConnect] path on a
            // FRESH transport — INLINE in this same coroutine so the heal is
            // transparent and never re-enters a failing connect job. The
            // budget cap keeps a genuinely-dead host from looping forever.
            if (
                isStaleChannelSymptom(t) &&
                staleLeaseAutoRecoverAttempts < STALE_LEASE_AUTO_RECOVER_MAX
            ) {
                staleLeaseAutoRecoverAttempts += 1
                Log.i(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-stale-lease-auto-recover fast-switch EOF -> fresh re-dial inline; " +
                        "attempt=$staleLeaseAutoRecoverAttempts/$STALE_LEASE_AUTO_RECOVER_MAX " +
                        "${targetLogFields(target)}",
                )
                ReconnectCauseTrail.record(
                    stage = "stale_lease_auto_recover",
                    outcome = "fast_switch_fresh_redial",
                    cause = "stale_lease_attach_eof",
                    trigger = trigger.logValue,
                    "attempt" to staleLeaseAutoRecoverAttempts,
                    "maxAttempts" to STALE_LEASE_AUTO_RECOVER_MAX,
                    "hostId" to target.hostId,
                    "failureClass" to t.javaClass.simpleName,
                )
                // Evict the poisoned pooled lease so the fresh re-dial cannot
                // re-grab the corpse, then tear the failed attach down fully
                // (cancel+join the event loop, per-pane, and disconnect-watcher
                // jobs) exactly like the slow [connect] path does before a
                // reconnect — leaking those jobs would leave stale collectors
                // on the dead client and can wedge the subsequent attach.
                // Closing the parked cached runtimes is dispatched async (as
                // the fast-switch's own leave path does) so joining a leaving
                // pane producer never blocks the re-dial.
                closeCachedRuntimesAsync(runtimeCache.removeHost(target.hostId))
                withContext(NonCancellable) {
                    runCatching { sshLeaseManager.disconnect(target.toSshLeaseTarget().leaseKey) }
                    closeCurrentConnectionAndJoin(
                        cacheEviction = RuntimeCacheEviction.TargetRuntime(target.toRuntimeKey()),
                    )
                }
                connectingTarget = target
                refreshReconnectAvailability()
                // Escalate from the keep-frame [Switching] to the full-screen
                // [Connecting] overlay: we are now doing a real fresh handshake.
                setConnectionState(
                    ConnectionState.Connecting(
                        target.host,
                        target.port,
                        target.user,
                    ),
                )
                _panes.value = emptyList()
                rebuildUnifiedPanes()
                // The poisoned pooled lease was disconnected above and the
                // cached runtimes were dropped, so the pool is empty: an
                // [AutoReconnect] dial (which does NOT re-run the synchronous
                // force-fresh cache cleanup) still opens a brand-new transport
                // without blocking on a leaving pane producer.
                runConnect(target, attempt, TmuxConnectTrigger.AutoReconnect)
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
        val staleChannelSymptom = isStaleChannelSymptom(cause)
        if (staleChannelSymptom) {
            withContext(NonCancellable) {
                runCatching { sshLeaseManager.disconnect(target.toSshLeaseTarget().leaseKey) }
            }
            Log.w(
                ISSUE_145_RECONNECT_TAG,
                "tmux-connect-stale-channel-symptom evicted poisoned lease so Reconnect opens fresh; " +
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
        val trigger = latestConnectIntent?.takeIf {
            sameSessionIdentity(it.target, target)
        }?.trigger ?: TmuxConnectTrigger.UserTap
        DiagnosticEvents.record(
            "connection",
            when (trigger) {
                TmuxConnectTrigger.Reconnect,
                TmuxConnectTrigger.AutoReconnect,
                TmuxConnectTrigger.NetworkReconnect,
                -> "reconnect_fail"
                else -> "connect_fail"
            },
            "attempt" to attempt,
            "hostId" to target.hostId,
            "host" to target.host,
            "port" to target.port,
            "user" to target.user,
            "session" to target.sessionName,
            "trigger" to trigger.logValue,
            "cause" to cause.javaClass.simpleName,
            "message" to cause.message,
            "elapsedMs" to (SystemClock.elapsedRealtime() - startedAtMs),
            *shortAppSwitchReconnectFields(
                trigger = trigger,
                target = target,
                sourceCandidate = "connect_fail",
            ),
        )
        withContext(NonCancellable) {
            closeCurrentConnectionAndJoin(
                cacheEviction = RuntimeCacheEviction.TargetRuntime(target.toRuntimeKey()),
            )
        }
        activeAttachMilestone = null
        activeTarget = null

        // Issue #621 / #634 / #636 (Slice 4): an open/switch attach that EOFs
        // on a silently-dead warm lease (`tmux -CC` open or `list-panes`) must
        // NOT strand the user on a Disconnected band + manual Reconnect. The
        // poisoned lease was already evicted above (staleChannelSymptom), so a
        // FRESH re-dial will attach to the live tmux session. Kick off the
        // existing auto-reconnect machinery transparently instead of surfacing
        // Failed, so the heal is invisible to the user.
        //
        // We only do this for the INITIAL user-facing open/switch (the
        // not-yet-a-reconnect triggers). When the trigger is already a
        // reconnect trigger, the [scheduleAutoReconnect] loop that invoked us
        // owns the retry/exhaust decision — re-entering it here would
        // double-drive the loop. The budget cap ([STALE_LEASE_AUTO_RECOVER_MAX])
        // ensures a genuinely-dead host (every fresh transport also EOFs) falls
        // back to the manual Reconnect affordance instead of looping forever.
        if (shouldTransparentlyRecoverStaleLease(staleChannelSymptom, trigger)) {
            staleLeaseAutoRecoverAttempts += 1
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-stale-lease-auto-recover transparent re-dial after stale attach EOF; " +
                    "attempt=$staleLeaseAutoRecoverAttempts/$STALE_LEASE_AUTO_RECOVER_MAX " +
                    "trigger=${trigger.logValue} ${targetLogFields(target)}",
            )
            ReconnectCauseTrail.record(
                stage = "stale_lease_auto_recover",
                outcome = "transparent_redial",
                cause = "stale_lease_attach_eof",
                trigger = trigger.logValue,
                "attempt" to staleLeaseAutoRecoverAttempts,
                "maxAttempts" to STALE_LEASE_AUTO_RECOVER_MAX,
                "hostId" to target.hostId,
                "failureClass" to cause.javaClass.simpleName,
            )
            // Show the Reconnecting band immediately so the surface is never a
            // Disconnected/Failed band, even for the brief window before the
            // fresh re-dial coroutine is dispatched. Deliberately leave
            // [connectingTarget] null so the recovery's [connect] is not
            // swallowed by its `connectJob?.isActive && connectingTarget ==
            // target` early-return guard while this failing job unwinds.
            connectingTarget = null
            refreshReconnectAvailability()
            // EPIC #687 slice 1b: this is the silent within-grace reattach, so it
            // maps to the controller-parity [ConnectionState.Reattaching] — which
            // projects to the SAME [ConnectionStatus.Reconnecting] band (the §1
            // `Reattaching/Reconnecting → Reconnecting` mapping), so the surfaced
            // status is byte-identical to before.
            setConnectionState(
                ConnectionState.Reattaching(
                    host = target.host,
                    port = target.port,
                    user = target.user,
                    attempt = 1,
                    maxAttempts = 1,
                    retryDelayMs = 0L,
                    reason = "Reattaching ${target.user}@${target.host}:${target.port}.",
                ),
            )
            // Drop any cached runtimes for this host asynchronously (as the
            // fast-switch leave path does) so the upcoming re-dial never blocks
            // joining a leaving pane producer, and the [AutoReconnect] dial does
            // not have to run the synchronous force-fresh cache cleanup.
            closeCachedRuntimesAsync(runtimeCache.removeHost(target.hostId))
            // Re-dial via the SAME entry point the manual Reconnect uses,
            // dispatched as a fresh top-level job so it runs AFTER this failing
            // connect job fully unwinds rather than re-entering it. The poisoned
            // pooled lease was already evicted above (staleChannelSymptom), so
            // the pool is empty and an [AutoReconnect] dial opens a brand-new
            // transport. Drop the reference to the job we are unwinding inside
            // so the recovery's [connect] does not block joining itself.
            val recoverTarget = target
            connectJob = null
            viewModelScope.launch {
                connect(
                    hostId = recoverTarget.hostId,
                    hostName = recoverTarget.hostName,
                    host = recoverTarget.host,
                    port = recoverTarget.port,
                    user = recoverTarget.user,
                    keyPath = recoverTarget.keyPath,
                    passphrase = recoverTarget.passphrase,
                    sessionName = recoverTarget.sessionName,
                    startDirectory = recoverTarget.startDirectory,
                    trigger = TmuxConnectTrigger.AutoReconnect,
                )
            }
            return
        }

        connectingTarget = if (preserveReconnectTarget || staleChannelSymptom) target else null
        refreshReconnectAvailability()
        // Issue #661: a failed switch must drop the cross-session "hide terminal"
        // gate so the Failed band is shown (not the loading placeholder).
        _switchHidesTerminal.value = false
        setConnectionState(ConnectionState.Unreachable(message))
    }

    /**
     * Issue #621 / #634 / #636 (Slice 4): decide whether to transparently
     * re-dial a fresh SSH transport after an open/switch attach EOF'd on a
     * silently-dead warm lease, instead of surfacing a Disconnected band.
     *
     * Conditions:
     *  - the failure is a stale-channel symptom (transport alive-but-dead, the
     *    `failConnectAttempt` caller already evicted the poisoned lease);
     *  - the app is foregrounded (a backgrounded app must not burn re-dials —
     *    [scheduleAutoReconnect] would pause anyway, but we avoid even starting);
     *  - auto-reconnect is enabled (the user hasn't disabled it / a test hasn't
     *    cleared the delays);
     *  - the trigger is an INITIAL open/switch, not already a reconnect trigger
     *    (those are owned by the in-flight [scheduleAutoReconnect] loop);
     *  - the per-chain budget has not been exhausted, so a genuinely-dead host
     *    cannot loop forever.
     */
    private fun shouldTransparentlyRecoverStaleLease(
        staleChannelSymptom: Boolean,
        trigger: TmuxConnectTrigger,
    ): Boolean =
        staleChannelSymptom &&
            appActive &&
            autoReconnectDelaysMs.isNotEmpty() &&
            !trigger.isReconnectTrigger &&
            staleLeaseAutoRecoverAttempts < STALE_LEASE_AUTO_RECOVER_MAX

    @androidx.annotation.VisibleForTesting
    internal fun isStaleChannelSymptom(cause: Throwable?): Boolean =
        isChannelOpenFailure(cause) ||
            isTmuxCommandTimeout(cause) ||
            isTmuxEofWriteFailure(cause) ||
            isTransportDisconnected(cause) ||
            isControlChannelClosed(cause)

    /**
     * Issue #685 (Bug B): true when [cause] is the "control channel closed"
     * variant the maintainer saw on a beyond-grace foreground reattach — the
     * pooled SSH transport died while backgrounded, so the reattach's
     * `list-panes` round-trip races the now-dead `-CC` control channel and the
     * reader reports `control channel closed before response` /
     * `control channel closed mid-command` (see
     * [com.pocketshell.core.tmux.TmuxClient]). The four older
     * [isStaleChannelSymptom] matchers ("open failed" / EOF-write /
     * command-timeout / transport-disconnect) did NOT cover this shape, so it
     * slipped straight to the scary `Failed` band with "Tap Reconnect to
     * retry." + a stuck "waiting for tmux panes…" spinner instead of healing.
     *
     * Treating it as a stale-channel symptom evicts the poisoned lease and
     * routes the beyond-grace return through the transparent re-dial
     * ([shouldTransparentlyRecoverStaleLease]): the user sees a calm
     * Reconnecting band and the panes reseed automatically — no manual tap, no
     * control-channel error text. A genuinely-dead host (every fresh transport
     * also fails) still falls back to the honest manual Reconnect band after the
     * [STALE_LEASE_AUTO_RECOVER_MAX] budget is spent.
     */
    private fun isControlChannelClosed(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message
            if (message != null &&
                message.contains("control channel closed", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * Issue #665 / #636: true when [cause] is the transport-DEAD variant of the
     * stale-lease symptom — the pooled SSH transport silently died (sshj's
     * `isConnected` lies until its keepalive trips), so the attach's `tmux -CC`
     * spawn fails not with an "open failed" channel error but with a
     * `net.schmizz.sshj.transport.TransportException` carrying disconnect reason
     * `BY_APPLICATION` ("Disconnected"). On the attach/switch path that surfaces
     * as `TmuxClientException("failed to spawn tmux -CC: Disconnected", <that
     * TransportException>)` (see [com.pocketshell.core.tmux.TmuxClient]).
     *
     * The merged #621 heal only matched "open failed" / EOF-write /
     * command-timeout, so this variant slipped through: the dead lease was never
     * evicted + re-dialled and the switch stranded on `status=Failed` showing the
     * PREVIOUS session's content (the v0.3.30 wrong/stale-session regression).
     *
     * Scoped tightly to the attach/spawn failure path so a deliberate
     * user-initiated disconnect is NOT auto-recovered: a `TransportException`
     * alone is not enough — it must be wrapped by the `tmux -CC` spawn failure
     * (matched on the "failed to spawn tmux -CC" message the attach throws) OR be
     * a sshj `TransportException` whose disconnect reason is `BY_APPLICATION`,
     * which is the dead-transport-during-attach shape and not how the explicit
     * close/detach reader path reports a user disconnect
     * ([com.pocketshell.core.tmux.TmuxDisconnectReason.ExplicitClose] /
     * `ExplicitDetach`, which never reach this connect-attempt cause chain).
     *
     * Matched on class simple name + message text (mirroring
     * [isNonRetryableConnectFailure]) so the app module need not import the sshj
     * transport hierarchy, walking the cause chain so a deeper wrap still
     * matches.
     */
    private fun isTransportDisconnected(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        var sawSpawnFailure = false
        while (current != null && seen.add(current)) {
            val message = current.message
            if (message != null &&
                message.contains("failed to spawn tmux -CC", ignoreCase = true)
            ) {
                sawSpawnFailure = true
            }
            if (isTransportDisconnectException(current)) {
                return true
            }
            // `failed to spawn tmux -CC: Disconnected` carries the disconnect
            // reason inline in the spawn-failure message even when the wrapped
            // TransportException's own message is bare; treat that as the
            // dead-transport attach symptom too.
            if (sawSpawnFailure &&
                message != null &&
                message.contains("Disconnected", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    /**
     * True when [throwable] is a sshj `TransportException`-family error reporting
     * a `BY_APPLICATION` / "Disconnected" teardown — the dead-transport shape an
     * attach hits when the pooled lease silently expired. Matched without
     * importing the sshj type: the class simple name plus the reason/message
     * text the exception carries.
     */
    private fun isTransportDisconnectException(throwable: Throwable): Boolean {
        if (throwable.javaClass.simpleName != "TransportException") return false
        // sshj's TransportException exposes a `getDisconnectReason()`; its name /
        // the exception message is `BY_APPLICATION` / "Disconnected" for the
        // application-initiated teardown that an attach over a silently-dead
        // lease surfaces. Read it reflectively to avoid an sshj compile-time dep.
        val reasonName = runCatching {
            throwable.javaClass.getMethod("getDisconnectReason").invoke(throwable)?.toString()
        }.getOrNull()
        if (reasonName != null && reasonName.contains("BY_APPLICATION", ignoreCase = true)) {
            return true
        }
        val message = throwable.message
        return message != null &&
            (
                message.contains("BY_APPLICATION", ignoreCase = true) ||
                    message.contains("Disconnected", ignoreCase = true)
                )
    }

    private fun isTmuxEofWriteFailure(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message
            if (
                message != null &&
                message.contains("failed to write tmux command", ignoreCase = true) &&
                (
                    message.contains("EOF", ignoreCase = true) ||
                        message.contains("closed", ignoreCase = true) ||
                        message.contains("broken pipe", ignoreCase = true)
                    )
            ) {
                return true
            }
            current = current.cause
        }
        return false
    }

    private fun isTmuxCommandTimeout(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        val seen = HashSet<Throwable>()
        while (current != null && seen.add(current)) {
            val message = current.message
            if (
                message != null &&
                message.contains("tmux", ignoreCase = true) &&
                message.contains("command", ignoreCase = true) &&
                message.contains("timed out", ignoreCase = true)
            ) {
                return true
            }
            current = current.cause
        }
        return false
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

    private fun shortAppSwitchReconnectFields(
        trigger: TmuxConnectTrigger?,
        target: ConnectionTarget? = activeTarget ?: connectingTarget,
        sourceCandidate: String? = null,
    ): Array<Pair<String, Any?>> =
        arrayOf(
            "issueContext" to "548_450_short_app_switch",
            "reconnectSourceCandidate" to reconnectSourceCandidate(trigger, sourceCandidate),
            "diagnosticSource" to sourceCandidate,
            "appActive" to appActive,
            "screenStarted" to screenStartedForCleared,
            "pendingReattach" to (pendingReattach != null),
            "pendingReattachGeneration" to pendingReattach?.generation,
            "backgroundDetachActive" to (backgroundDetachJob?.isActive == true),
            "passiveDisconnectGraceActive" to (passiveDisconnectGraceJob?.isActive == true),
            "autoReconnectActive" to (autoReconnectJob?.isActive == true),
            "connectionStatus" to _connectionStatus.value.javaClass.simpleName,
            "latestTrigger" to latestConnectIntent?.trigger?.logValue,
            "latestGeneration" to latestConnectIntent?.generation,
            "activeAttempt" to activeAttachMilestone?.attempt,
            "activeAttachTrigger" to activeAttachMilestone?.trigger?.logValue,
            "hasClient" to (clientRef != null),
            "clientDisconnected" to clientRef?.disconnected?.value,
            "hasSession" to (sessionRef != null),
            "sessionConnected" to sessionRef?.isConnected,
            "targetMatchesPendingReattach" to (
                target != null &&
                    pendingReattach?.let { sameSessionIdentity(it.target, target) } == true
                ),
        )

    private fun reconnectSourceCandidate(
        trigger: TmuxConnectTrigger?,
        sourceCandidate: String?,
    ): String =
        when {
            sourceCandidate == "terminal_surface" -> "terminal_ui_failure"
            sourceCandidate == "passive_disconnect" -> "tmux_eof_or_reader_disconnect"
            sourceCandidate == "background_teardown" -> "lifecycle_teardown"
            sourceCandidate == "foreground_reattach" -> "manual_or_foreground_reattach"
            sourceCandidate == "network_observer" -> "network_replay_or_handoff"
            trigger == TmuxConnectTrigger.LifecycleReattach -> "manual_or_foreground_reattach"
            trigger == TmuxConnectTrigger.NetworkReconnect -> "network_replay_or_handoff"
            trigger == TmuxConnectTrigger.Reconnect -> "manual_or_foreground_reattach"
            trigger == TmuxConnectTrigger.AutoReconnect -> "tmux_eof_or_reader_disconnect"
            else -> "none"
        }

    private fun recordAutoReconnectDecision(
        decision: String,
        target: ConnectionTarget?,
        trigger: TmuxConnectTrigger,
        reason: String,
        cause: String,
        vararg extraFields: Pair<String, Any?>,
    ) {
        DiagnosticEvents.record(
            "connection",
            "auto_reconnect_decision",
            "decision" to decision,
            "cause" to cause,
            "trigger" to trigger.logValue,
            "reason" to reason,
            "hostId" to target?.hostId,
            "host" to target?.host,
            "port" to target?.port,
            "user" to target?.user,
            "session" to target?.sessionName,
            "generation" to connectGeneration,
            *extraFields,
            *shortAppSwitchReconnectFields(
                trigger = trigger,
                target = target,
                sourceCandidate = cause,
            ),
        )
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
        // EPIC #687 slice 1c-iv-b-A2 (#739): re-point the observe-only effect
        // driver's real TmuxPort at the freshly attached client so its
        // `disconnected` oracle follows the live channel. Observe-only — this only
        // updates the flow the driver collects; it triggers no IO and no cutover.
        connectionTmuxPort.setClient(client)
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
        outputOverflowJob?.cancel()
        outputOverflowJob = bridgeScope.launch {
            client.outputBacklogOverflows.collect { overflow ->
                if (clientRef !== client) return@collect
                handleTerminalOutputBacklogOverflow(overflow)
            }
        }
        // Issue #173: observe the client's latched `disconnected`
        // StateFlow so we start bounded recovery when the underlying
        // [TmuxClient.readerLoop] exits (clean EOF, sshj
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
                val target = activeTarget ?: connectingTarget
                val disconnectEvent = disconnectEventOrFallback(client)
                DiagnosticEvents.record(
                    "connection",
                    "passive_disconnect",
                    "source" to "tmux_client_disconnected",
                    "classification" to "real_tmux_control_channel_closed",
                    "disconnectReason" to disconnectEvent.reason.logValue,
                    "disconnectSource" to disconnectEvent.source,
                    "disconnectIntent" to disconnectEvent.intent,
                    "commandKind" to disconnectEvent.commandKind,
                    "timeoutMode" to disconnectEvent.timeoutMode,
                    "exceptionClass" to disconnectEvent.exceptionClass,
                    "message" to disconnectEvent.message,
                    "hostId" to target?.hostId,
                    "host" to target?.host,
                    "port" to target?.port,
                    "user" to target?.user,
                    "session" to target?.sessionName,
                    "clientHash" to System.identityHashCode(client),
                    "generation" to connectGeneration,
                    "attempt" to activeAttachMilestone?.attempt,
                    "activeTrigger" to activeAttachMilestone?.trigger?.logValue,
                    "status" to _connectionStatus.value.javaClass.simpleName,
                    *shortAppSwitchReconnectFields(
                        trigger = TmuxConnectTrigger.AutoReconnect,
                        target = target,
                        sourceCandidate = "passive_disconnect",
                    ),
                )
                ReconnectCauseTrail.record(
                    stage = "session_disconnect",
                    outcome = "passive_disconnect",
                    cause = disconnectEvent.reason.logValue,
                    trigger = TmuxConnectTrigger.AutoReconnect.logValue,
                    "hostId" to target?.hostId,
                    "generation" to connectGeneration,
                    "attempt" to activeAttachMilestone?.attempt,
                    "clientHash" to System.identityHashCode(client),
                    "status" to _connectionStatus.value.javaClass.simpleName,
                    "disconnectSource" to disconnectEvent.source,
                    "disconnectIntent" to disconnectEvent.intent,
                )
                handlePassiveClientDisconnect(client, disconnectEvent)
            }
        }
    }

    private fun handlePassiveClientDisconnect(
        client: TmuxClient,
        disconnectEvent: TmuxDisconnectEvent = disconnectEventOrFallback(client),
    ) {
        val current = inlineConnectionStatus as? ConnectionStatus.Connected ?: return
        val target = activeTarget ?: connectingTarget
        val reason = passiveDisconnectMessage(current, disconnectEvent)
        // EPIC #687 slice 1c-iv-b-B2 (#742): the controller's TransportDropped input
        // is now driver-fed from the REAL [CurrentClientTmuxPort.disconnected] oracle
        // (the SAME `TmuxClient.disconnected` true-edge this passive-disconnect path
        // observes), so the inline `observeTransportDropped` mirror feed is DELETED
        // (D22 hard-cut). The inline effect machinery below is UNCHANGED — it still
        // owns the actual silent-reattach / reconnect IO until a later slice; only the
        // controller-feed moved to the driver.
        when (reduceConnection(ConnectionEvent.TransportDropped(client))) {
            ConnectionDecision.SkipPassiveInAppNavigation ->
                if (target != null) skipPassiveInAppNavigation(target)
            ConnectionDecision.PausePassiveUntilForeground ->
                if (target != null) pausePassiveUntilForeground(target, reason, disconnectEvent)
            ConnectionDecision.SilentReattachWithinGrace ->
                silentReattachWithinPassiveGrace(client, target, reason, disconnectEvent)
            else -> Unit
        }
        // APPROVED #685 divergence #1 (silent recovery): a recoverable live-channel
        // drop moves the controller Live → Reattaching, so the displayed status is a
        // CALM Reconnecting — NOT the scary inline Failed/"control channel closed/Tap
        // Reconnect" band. Only after the silent reconnect ladder truly exhausts does
        // the controller reach Unreachable (→ the #720 calm "Tap to reconnect").
        // Re-project after the inline effects ran (they read inlineConnectionStatus).
        projectStatusFromController()
    }

    /**
     * EPIC #687 slice 1a: the [ConnectionDecision.SkipPassiveInAppNavigation]
     * body (#630) — formerly inline in [handlePassiveClientDisconnect].
     * Unchanged behavior.
     */
    private fun skipPassiveInAppNavigation(target: ConnectionTarget) {
        ReconnectCauseTrail.record(
            stage = "handlePassiveClientDisconnect",
            outcome = "skipped_pause_in_app_navigation",
            cause = "different_session_target",
            trigger = TmuxConnectTrigger.AutoReconnect.logValue,
            "pausedSession" to target.sessionName,
            "intentSession" to latestConnectIntent?.target?.sessionName,
            "hasActiveConnectJob" to (connectJob?.isActive == true),
        )
    }

    /**
     * EPIC #687 slice 1a: the [ConnectionDecision.PausePassiveUntilForeground]
     * body — formerly inline in [handlePassiveClientDisconnect]. Unchanged
     * behavior.
     */
    private fun pausePassiveUntilForeground(
        target: ConnectionTarget,
        reason: String,
        disconnectEvent: TmuxDisconnectEvent,
    ) {
        passiveDisconnectGraceJob?.cancel()
        passiveDisconnectGraceJob = null
        pauseAutoReconnectUntilForeground(
            target = target,
            reason = reason,
            trigger = TmuxConnectTrigger.AutoReconnect,
            cause = "screen_stopped_passive_disconnect",
            diagnosticFields = arrayOf(
                "disconnectReason" to disconnectEvent.reason.logValue,
                "disconnectSource" to disconnectEvent.source,
                "disconnectIntent" to disconnectEvent.intent,
            ),
        )
    }

    /**
     * EPIC #687 slice 1a: the [ConnectionDecision.SilentReattachWithinGrace]
     * body — formerly the grace-job tail of [handlePassiveClientDisconnect].
     * Unchanged behavior.
     */
    private fun silentReattachWithinPassiveGrace(
        client: TmuxClient,
        target: ConnectionTarget?,
        reason: String,
        disconnectEvent: TmuxDisconnectEvent,
    ) {
        passiveDisconnectGraceJob?.cancel()
        val graceJob = viewModelScope.launch {
            val recovered = target != null && silentlyReattachWithinPassiveGrace(
                staleClient = client,
                target = target,
            )
            if (recovered) {
                passiveDisconnectGraceJob = null
                return@launch
            }
            passiveDisconnectGraceJob = null
            surfacePassiveDisconnect(
                client = client,
                reason = reason,
                target = target,
                disconnectEvent = disconnectEvent,
            )
        }
        passiveDisconnectGraceJob = graceJob
        graceJob.invokeOnCompletion {
            if (passiveDisconnectGraceJob == graceJob) {
                passiveDisconnectGraceJob = null
            }
        }
    }

    private suspend fun silentlyReattachWithinPassiveGrace(
        staleClient: TmuxClient,
        target: ConnectionTarget,
    ): Boolean {
        if (passiveDisconnectGraceMs <= 0L) return false
        val preferFreshTransport = leaseRef != null
        var transportReattachTried = false
        DiagnosticEvents.record(
            "connection",
            "silent_reattach_start",
            "source" to "passive_disconnect",
            "trigger" to TmuxConnectTrigger.AutoReconnect.logValue,
            "hostId" to target.hostId,
            "host" to target.host,
            "port" to target.port,
            "user" to target.user,
            "session" to target.sessionName,
            "clientHash" to System.identityHashCode(staleClient),
            "timeoutMs" to passiveDisconnectGraceMs,
            "preferFreshTransport" to preferFreshTransport,
            *shortAppSwitchReconnectFields(
                trigger = TmuxConnectTrigger.AutoReconnect,
                target = target,
                sourceCandidate = "passive_disconnect",
            ),
        )
        return withTimeoutOrNull<Boolean>(passiveDisconnectGraceMs) {
            while (true) {
                if (!appActive) return@withTimeoutOrNull false
                if (inlineConnectionStatus !is ConnectionStatus.Connected) return@withTimeoutOrNull false
                val currentClient = clientRef
                if (currentClient !== staleClient && currentClient?.disconnected?.value != true) {
                    return@withTimeoutOrNull true
                }
                val shouldProbeTransport = !transportReattachTried
                if (preferFreshTransport && shouldProbeTransport) {
                    transportReattachTried = true
                    if (silentlyReconnectTransportAfterPassiveDisconnect(
                            staleClient = staleClient,
                            target = target,
                            timeoutMs = silentReattachTimeoutMs.coerceAtLeast(1L),
                        )
                    ) {
                        return@withTimeoutOrNull true
                    }
                }
                if (silentlyReattachAfterPassiveDisconnect(
                        staleClient = staleClient,
                        target = target,
                        timeoutMs = silentReattachTimeoutMs.coerceAtLeast(1L),
                    )
                ) {
                    return@withTimeoutOrNull true
                }
                if (!preferFreshTransport && shouldProbeTransport) {
                    transportReattachTried = true
                    if (silentlyReconnectTransportAfterPassiveDisconnect(
                            staleClient = staleClient,
                            target = target,
                            timeoutMs = silentReattachTimeoutMs.coerceAtLeast(1L),
                        )
                    ) {
                        return@withTimeoutOrNull true
                    }
                }
                val retryDelayMs = PASSIVE_DISCONNECT_SILENT_REATTACH_RETRY_MS
                delay(retryDelayMs)
            }
            false
        }.also { recovered ->
            if (recovered != true) {
                DiagnosticEvents.record(
                    "connection",
                    "silent_reattach_fail",
                    "source" to "passive_disconnect",
                    "trigger" to TmuxConnectTrigger.AutoReconnect.logValue,
                    "hostId" to target.hostId,
                    "host" to target.host,
                    "port" to target.port,
                    "user" to target.user,
                    "session" to target.sessionName,
                    "clientHash" to System.identityHashCode(staleClient),
                    "cause" to "grace_elapsed",
                    *shortAppSwitchReconnectFields(
                        trigger = TmuxConnectTrigger.AutoReconnect,
                        target = target,
                        sourceCandidate = "passive_disconnect",
                    ),
                )
            }
        } == true
    }

    private suspend fun silentlyReattachAfterPassiveDisconnect(
        staleClient: TmuxClient,
        target: ConnectionTarget,
        timeoutMs: Long,
    ): Boolean {
        val session = sessionRef?.takeIf { it.isConnected } ?: return false
        val startedAtMs = SystemClock.elapsedRealtime()
        val replacement = createTmuxClient(session, target.sessionName, target.startDirectory)
        return try {
            val ready = withTimeoutOrNull(timeoutMs) {
                eventsJob?.cancelAndJoin()
                eventsJob = null
                outputOverflowJob?.cancelAndJoin()
                outputOverflowJob = null
                disconnectedJob?.cancelAndJoin()
                disconnectedJob = null
                runCatching { staleClient.close() }
                clientRef = replacement
                bindClientObservers(replacement)
                replacement.connect()
                activeAttachMilestone = AttachMilestone(
                    attempt = TMUX_CONNECT_ATTEMPTS.get(),
                    sessionName = target.sessionName,
                    startedAtMs = startedAtMs,
                    trigger = TmuxConnectTrigger.AutoReconnect,
                )
                awaitPanesReadyForAttach(
                    target = target,
                    attempt = TMUX_CONNECT_ATTEMPTS.get(),
                    startedAtMs = startedAtMs,
                    trigger = TmuxConnectTrigger.AutoReconnect,
                )
                rebindVisiblePaneProducersToClient(replacement)
                // Issue #693/#662: this is a fresh attach for the reused panes —
                // their old per-attach seed flags no longer apply, so clear them
                // and let [reseedAllVisiblePanes] re-capture every visible pane
                // with the new control client. [seedPaneFromCapture] now keeps
                // the last rendered frame on an empty capture (never repaints
                // black) and retries, so a degraded reconnect can't strand a
                // black pane.
                panesSeededThisAttach.clear()
                val reattachGuard = RuntimeRefreshGuard(
                    generation = connectGeneration,
                    target = target,
                    client = replacement,
                )
                reseedAllVisiblePanes(reattachGuard)
                // Issue #693/#662: wire the blank-net into the reconnect path
                // (it was only on the connect-reveal + resize paths before). Any
                // pane still blank after the reseed (its reconnect capture was
                // empty) is retried here, and a still-black active pane keeps the
                // calm loading overlay via the watchdog rather than painting
                // black on a live (green) reattached connection.
                reseedBlankVisiblePanes(reattachGuard)
                maybeRefreshControlClientSize()
                true
            } == true
            if (!ready) {
                if (clientRef === replacement) {
                    clientRef = staleClient
                }
                runCatching { replacement.close() }
                return false
            }
            clientRegistration = activeTmuxClients.register(
                hostId = target.hostId,
                hostName = target.hostName,
                hostname = target.host,
                port = target.port,
                username = target.user,
                keyPath = target.keyPath,
                client = replacement,
                startDirectoryExists = { directory ->
                    remoteStartDirectoryExists(session, directory)
                },
            )
            registeredHostId = target.hostId
            installLifecycleHooks(target.hostId)
            bindProjectRootsForHost(target.hostId)
            activeTarget = target
            connectingTarget = null
            refreshReconnectAvailability()
            setConnectionState(
                ConnectionState.Live(
                    target.host,
                    target.port,
                    target.user,
                ),
            )
            // Issue #693: never leave a reattached pane black on a live (green)
            // connection. If the active pane is still blank after the reconnect
            // reseed, keep re-seeding under the watchdog rather than showing a
            // black Connected pane.
            armConnectedBlankWatchdog(
                RuntimeRefreshGuard(
                    generation = connectGeneration,
                    target = target,
                    client = replacement,
                ),
            )
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-passive-disconnect-silent-reattach " +
                    targetLogFields(target) +
                    " elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
            )
            DiagnosticEvents.record(
                "connection",
                "reconnect_success",
                "hostId" to target.hostId,
                "host" to target.host,
                "port" to target.port,
                "user" to target.user,
                "session" to target.sessionName,
                "trigger" to TmuxConnectTrigger.AutoReconnect.logValue,
                "source" to "silent_reattach",
                "clientHash" to System.identityHashCode(replacement),
                "elapsedMs" to (SystemClock.elapsedRealtime() - startedAtMs),
                *shortAppSwitchReconnectFields(
                    trigger = TmuxConnectTrigger.AutoReconnect,
                    target = target,
                    sourceCandidate = "passive_disconnect",
                ),
            )
            true
        } catch (t: Throwable) {
            if (t is CancellationException) {
                if (clientRef === replacement) {
                    clientRef = staleClient
                }
                runCatching { replacement.close() }
                throw t
            }
            Log.w(
                ISSUE_145_RECONNECT_TAG,
                "tmux-passive-disconnect-silent-reattach-failed " +
                    "cause=${t.javaClass.simpleName}: ${t.message} " +
                    targetLogFields(target),
                t,
            )
            runCatching { replacement.close() }
            if (clientRef === replacement) {
                clientRef = staleClient
            }
            DiagnosticEvents.record(
                "connection",
                "reconnect_fail",
                "hostId" to target.hostId,
                "host" to target.host,
                "port" to target.port,
                "user" to target.user,
                "session" to target.sessionName,
                "trigger" to TmuxConnectTrigger.AutoReconnect.logValue,
                "source" to "silent_reattach",
                "cause" to t.javaClass.simpleName,
                "message" to t.message,
                "clientHash" to System.identityHashCode(replacement),
                "elapsedMs" to (SystemClock.elapsedRealtime() - startedAtMs),
                *shortAppSwitchReconnectFields(
                    trigger = TmuxConnectTrigger.AutoReconnect,
                    target = target,
                    sourceCandidate = "passive_disconnect",
                ),
            )
            false
        }
    }

    private suspend fun silentlyReconnectTransportAfterPassiveDisconnect(
        staleClient: TmuxClient,
        target: ConnectionTarget,
        timeoutMs: Long,
    ): Boolean {
        val startedAtMs = SystemClock.elapsedRealtime()
        var acquiredLease: SshLease? = null
        var replacement: TmuxClient? = null
        return try {
            val ready = withTimeoutOrNull(timeoutMs) {
                val leaseTarget = target.toSshLeaseTarget()
                withContext(NonCancellable) {
                    runCatching { sshLeaseManager.disconnect(leaseTarget.leaseKey) }
                }
                val lease = sshLeaseManager.acquire(leaseTarget).getOrThrow()
                acquiredLease = lease
                val session = lease.session
                val newClient = createTmuxClient(session, target.sessionName, target.startDirectory)
                replacement = newClient
                eventsJob?.cancelAndJoin()
                eventsJob = null
                outputOverflowJob?.cancelAndJoin()
                outputOverflowJob = null
                disconnectedJob?.cancelAndJoin()
                disconnectedJob = null
                runCatching { staleClient.close() }
                leaseRef = lease
                sessionRef = session
                clientRef = newClient
                bindClientObservers(newClient)
                newClient.connect()
                activeAttachMilestone = AttachMilestone(
                    attempt = TMUX_CONNECT_ATTEMPTS.get(),
                    sessionName = target.sessionName,
                    startedAtMs = startedAtMs,
                    trigger = TmuxConnectTrigger.AutoReconnect,
                )
                awaitPanesReadyForAttach(
                    target = target,
                    attempt = TMUX_CONNECT_ATTEMPTS.get(),
                    startedAtMs = startedAtMs,
                    trigger = TmuxConnectTrigger.AutoReconnect,
                )
                rebindVisiblePaneProducersToClient(newClient)
                // Issue #693/#662: fresh transport reattach — re-seed every
                // visible pane on the new client (clear the prior per-attach
                // flags first), then run the blank-net backstop so a degraded
                // fresh-transport reconnect never strands a black pane on a
                // live (green) connection.
                panesSeededThisAttach.clear()
                val transportReattachGuard = RuntimeRefreshGuard(
                    generation = connectGeneration,
                    target = target,
                    client = newClient,
                )
                reseedAllVisiblePanes(transportReattachGuard)
                reseedBlankVisiblePanes(transportReattachGuard)
                maybeRefreshControlClientSize()
                true
            } == true
            if (!ready) {
                val leaseTarget = target.toSshLeaseTarget()
                if (clientRef === replacement) {
                    clientRef = staleClient
                    sessionRef = null
                    leaseRef = null
                }
                runCatching { replacement?.close() }
                withContext(NonCancellable) {
                    runCatching { sshLeaseManager.disconnect(leaseTarget.leaseKey) }
                }
                DiagnosticEvents.record(
                    "connection",
                    "reconnect_fail",
                    "hostId" to target.hostId,
                    "host" to target.host,
                    "port" to target.port,
                    "user" to target.user,
                    "session" to target.sessionName,
                    "trigger" to TmuxConnectTrigger.AutoReconnect.logValue,
                    "source" to "silent_transport_reattach",
                    "cause" to "attach_not_ready",
                    "evictedLease" to true,
                    "clientHash" to replacement?.let { System.identityHashCode(it) },
                    "elapsedMs" to (SystemClock.elapsedRealtime() - startedAtMs),
                    *shortAppSwitchReconnectFields(
                        trigger = TmuxConnectTrigger.AutoReconnect,
                        target = target,
                        sourceCandidate = "passive_disconnect",
                    ),
                )
                return false
            }
            val lease = acquiredLease ?: return false
            val newClient = replacement ?: return false
            clientRegistration = activeTmuxClients.register(
                hostId = target.hostId,
                hostName = target.hostName,
                hostname = target.host,
                port = target.port,
                username = target.user,
                keyPath = target.keyPath,
                client = newClient,
                startDirectoryExists = { directory ->
                    remoteStartDirectoryExists(lease.session, directory)
                },
            )
            registeredHostId = target.hostId
            installLifecycleHooks(target.hostId)
            bindProjectRootsForHost(target.hostId)
            activeTarget = target
            connectingTarget = null
            refreshReconnectAvailability()
            setConnectionState(
                ConnectionState.Live(
                    target.host,
                    target.port,
                    target.user,
                ),
            )
            // Issue #693: never leave a reattached pane black on a live (green)
            // connection after a fresh-transport reconnect.
            armConnectedBlankWatchdog(
                RuntimeRefreshGuard(
                    generation = connectGeneration,
                    target = target,
                    client = newClient,
                ),
            )
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-passive-disconnect-silent-transport-reattach " +
                    targetLogFields(target) +
                    " elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
            )
            DiagnosticEvents.record(
                "connection",
                "reconnect_success",
                "hostId" to target.hostId,
                "host" to target.host,
                "port" to target.port,
                "user" to target.user,
                "session" to target.sessionName,
                "trigger" to TmuxConnectTrigger.AutoReconnect.logValue,
                "source" to "silent_transport_reattach",
                "clientHash" to System.identityHashCode(newClient),
                "elapsedMs" to (SystemClock.elapsedRealtime() - startedAtMs),
                *shortAppSwitchReconnectFields(
                    trigger = TmuxConnectTrigger.AutoReconnect,
                    target = target,
                    sourceCandidate = "passive_disconnect",
                ),
            )
            true
        } catch (t: Throwable) {
            if (t is CancellationException) {
                if (clientRef === replacement) {
                    clientRef = staleClient
                    sessionRef = null
                    leaseRef = null
                }
                runCatching { replacement?.close() }
                withContext(NonCancellable) {
                    val leaseTarget = target.toSshLeaseTarget()
                    runCatching { sshLeaseManager.disconnect(leaseTarget.leaseKey) }
                }
                throw t
            }
            Log.w(
                ISSUE_145_RECONNECT_TAG,
                "tmux-passive-disconnect-silent-transport-reattach-failed " +
                    "cause=${t.javaClass.simpleName}: ${t.message} " +
                    targetLogFields(target),
                t,
            )
            runCatching { replacement?.close() }
            withContext(NonCancellable) {
                val leaseTarget = target.toSshLeaseTarget()
                runCatching { sshLeaseManager.disconnect(leaseTarget.leaseKey) }
            }
            if (clientRef === replacement) {
                clientRef = staleClient
                sessionRef = null
                leaseRef = null
            }
            DiagnosticEvents.record(
                "connection",
                "reconnect_fail",
                "hostId" to target.hostId,
                "host" to target.host,
                "port" to target.port,
                "user" to target.user,
                "session" to target.sessionName,
                "trigger" to TmuxConnectTrigger.AutoReconnect.logValue,
                "source" to "silent_transport_reattach",
                "cause" to t.javaClass.simpleName,
                "message" to t.message,
                "evictedLease" to true,
                "clientHash" to replacement?.let { System.identityHashCode(it) },
                "elapsedMs" to (SystemClock.elapsedRealtime() - startedAtMs),
                *shortAppSwitchReconnectFields(
                    trigger = TmuxConnectTrigger.AutoReconnect,
                    target = target,
                    sourceCandidate = "passive_disconnect",
                ),
            )
            false
        }
    }

    private fun surfacePassiveDisconnect(
        client: TmuxClient,
        reason: String,
        target: ConnectionTarget?,
        disconnectEvent: TmuxDisconnectEvent = disconnectEventOrFallback(client),
    ) {
        if (inlineConnectionStatus !is ConnectionStatus.Connected) return
        if (clientRef !== client) return
        if (target != null && shouldAutoReconnectPassiveDisconnect(disconnectEvent)) {
            scheduleAutoReconnect(
                target = target,
                reason = passiveAutoReconnectMessage(disconnectEvent, target),
                trigger = TmuxConnectTrigger.AutoReconnect,
                diagnosticFields = arrayOf(
                    "disconnectReason" to disconnectEvent.reason.logValue,
                    "disconnectSource" to disconnectEvent.source,
                    "disconnectIntent" to disconnectEvent.intent,
                ),
            )
            return
        }
        val current = inlineConnectionStatus as? ConnectionStatus.Connected
        Log.w(
            ISSUE_145_RECONNECT_TAG,
            "tmux-mid-session-disconnect host=${current?.host ?: target?.host.orEmpty()} " +
                "port=${current?.port ?: target?.port ?: 0} " +
                "user=${current?.user ?: target?.user.orEmpty()}",
        )
        DiagnosticEvents.record(
            "connection",
            "disconnect",
            "cause" to "tmux_control_channel_closed",
            "disconnectReason" to disconnectEvent.reason.logValue,
            "disconnectSource" to disconnectEvent.source,
            "disconnectIntent" to disconnectEvent.intent,
            "commandKind" to disconnectEvent.commandKind,
            "timeoutMode" to disconnectEvent.timeoutMode,
            "exceptionClass" to disconnectEvent.exceptionClass,
            "message" to disconnectEvent.message,
            "source" to "passive_disconnect",
            "trigger" to TmuxConnectTrigger.AutoReconnect.logValue,
            "hostId" to target?.hostId,
            "host" to (current?.host ?: target?.host.orEmpty()),
            "port" to (current?.port ?: target?.port ?: 0),
            "user" to (current?.user ?: target?.user.orEmpty()),
            "session" to (target?.sessionName ?: "unknown"),
            "clientHash" to System.identityHashCode(client),
            "generation" to connectGeneration,
            "attempt" to activeAttachMilestone?.attempt,
            "activeTrigger" to activeAttachMilestone?.trigger?.logValue,
            *shortAppSwitchReconnectFields(
                trigger = TmuxConnectTrigger.AutoReconnect,
                target = target,
                sourceCandidate = "passive_disconnect",
            ),
        )
        recordAutoReconnectDecision(
            decision = "suppressed_manual_only",
            target = target,
            trigger = TmuxConnectTrigger.AutoReconnect,
            reason = reason,
            cause = "passive_disconnect",
            "disconnectReason" to disconnectEvent.reason.logValue,
            "disconnectSource" to disconnectEvent.source,
            "disconnectIntent" to disconnectEvent.intent,
        )
        unregisterCurrentClient()
        if (target != null) {
            activeTarget = target
            connectingTarget = null
            refreshReconnectAvailability()
        }
        setConnectionState(ConnectionState.Unreachable(reason))
    }

    private fun shouldAutoReconnectPassiveDisconnect(disconnectEvent: TmuxDisconnectEvent): Boolean =
        when (disconnectEvent.reason) {
            TmuxDisconnectReason.ReaderEof,
            TmuxDisconnectReason.ReaderException,
            TmuxDisconnectReason.CommandTimeout,
            -> true
            TmuxDisconnectReason.ExplicitClose,
            TmuxDisconnectReason.ExplicitDetach,
            TmuxDisconnectReason.Unknown,
            -> false
        }

    private fun passiveDisconnectMessage(
        current: ConnectionStatus.Connected,
        disconnectEvent: TmuxDisconnectEvent,
    ): String {
        val prefix = when (disconnectEvent.reason) {
            TmuxDisconnectReason.ReaderEof -> "Transport EOF"
            TmuxDisconnectReason.ReaderException -> "Transport read failed"
            TmuxDisconnectReason.CommandTimeout -> "Tmux command timed out"
            TmuxDisconnectReason.ExplicitClose -> "Connection closed locally"
            TmuxDisconnectReason.ExplicitDetach -> "Tmux client detached"
            TmuxDisconnectReason.Unknown -> "Disconnected"
        }
        return "$prefix from ${current.user}@${current.host}:${current.port}. Tap Reconnect to retry."
    }

    private fun passiveAutoReconnectMessage(
        disconnectEvent: TmuxDisconnectEvent,
        target: ConnectionTarget,
    ): String {
        val prefix = when (disconnectEvent.reason) {
            TmuxDisconnectReason.ReaderEof -> "Transport EOF"
            TmuxDisconnectReason.ReaderException -> "Transport read failed"
            TmuxDisconnectReason.CommandTimeout -> "Tmux command timed out"
            TmuxDisconnectReason.ExplicitClose -> "Connection closed locally"
            TmuxDisconnectReason.ExplicitDetach -> "Tmux client detached"
            TmuxDisconnectReason.Unknown -> "Disconnected"
        }
        return "$prefix from ${target.user}@${target.host}:${target.port}; reconnecting."
    }

    private fun disconnectEventOrFallback(client: TmuxClient): TmuxDisconnectEvent =
        client.disconnectEvent.value ?: TmuxDisconnectEvent(
            reason = TmuxDisconnectReason.Unknown,
            source = "boolean_disconnected",
            intent = "unknown",
        )

    private fun scheduleAutoReconnect(
        target: ConnectionTarget,
        reason: String,
        trigger: TmuxConnectTrigger = TmuxConnectTrigger.AutoReconnect,
        diagnosticFields: Array<out Pair<String, Any?>> = emptyArray(),
    ) {
        passiveDisconnectGraceJob?.cancel()
        passiveDisconnectGraceJob = null
        if (!appActive) {
            pauseAutoReconnectUntilForeground(
                target = target,
                reason = reason,
                trigger = trigger,
                cause = "app_background",
                diagnosticFields = diagnosticFields,
            )
            return
        }
        if (autoReconnectJob?.isActive == true) {
            recordAutoReconnectDecision(
                decision = "skipped",
                target = target,
                trigger = trigger,
                reason = reason,
                cause = "already_active",
                *diagnosticFields,
            )
            return
        }
        activeTarget = target
        connectingTarget = null
        refreshReconnectAvailability()
        val delays = autoReconnectDelaysMs.ifEmpty { listOf(0L) }
        recordAutoReconnectDecision(
            decision = "scheduled",
            target = target,
            trigger = trigger,
            reason = reason,
            cause = "retryable",
            "maxAttempts" to delays.size,
            "delaysMs" to delays.joinToString(","),
            *diagnosticFields,
        )
        autoReconnectJob = viewModelScope.launch {
            for ((index, delayMs) in delays.withIndex()) {
                val generation = nextConnectGeneration()
                latestConnectIntent = ConnectIntent(
                    target = target,
                    trigger = trigger,
                    generation = generation,
                )
                setConnectionState(
                    ConnectionState.Reconnecting(
                        host = target.host,
                        port = target.port,
                        user = target.user,
                        attempt = index + 1,
                        maxAttempts = delays.size,
                        retryDelayMs = delayMs,
                        reason = reason,
                    ),
                )
                DiagnosticEvents.record(
                    "connection",
                    "reconnect_start",
                    "attempt" to (index + 1),
                    "maxAttempts" to delays.size,
                    "retryDelayMs" to delayMs,
                    "hostId" to target.hostId,
                    "host" to target.host,
                    "port" to target.port,
                    "user" to target.user,
                    "session" to target.sessionName,
                    "trigger" to trigger.logValue,
                    "reason" to reason,
                    "generation" to generation,
                    *shortAppSwitchReconnectFields(
                        trigger = trigger,
                        target = target,
                        sourceCandidate = "auto_reconnect",
                    ),
                )
                ReconnectCauseTrail.record(
                    stage = "reconnect_attempt",
                    outcome = "scheduled",
                    cause = "auto_reconnect_loop",
                    trigger = trigger.logValue,
                    "attempt" to (index + 1),
                    "maxAttempts" to delays.size,
                    "retryDelayMs" to delayMs,
                    "hostId" to target.hostId,
                    "generation" to generation,
                )
                if (delayMs > 0) delay(delayMs)
                if (!appActive) {
                    recordAutoReconnectDecision(
                        decision = "cancelled_due_to_background",
                        target = target,
                        trigger = trigger,
                        reason = reason,
                        cause = "app_background_after_delay",
                        "attempt" to (index + 1),
                        "maxAttempts" to delays.size,
                    )
                    return@launch
                }
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
                DiagnosticEvents.record(
                    "connection",
                    "connect_start",
                    "attempt" to attempt,
                    "hostId" to target.hostId,
                    "host" to target.host,
                    "port" to target.port,
                    "user" to target.user,
                    "session" to target.sessionName,
                    "trigger" to trigger.logValue,
                    "generation" to generation,
                    "clientHash" to clientRef?.let { System.identityHashCode(it) },
                    *shortAppSwitchReconnectFields(
                        trigger = trigger,
                        target = target,
                        sourceCandidate = "auto_reconnect",
                    ),
                )
                ReconnectCauseTrail.record(
                    stage = "connect_attempt",
                    outcome = "reconnect",
                    cause = "auto_reconnect_loop",
                    trigger = trigger.logValue,
                    "attempt" to attempt,
                    "retryAttempt" to (index + 1),
                    "maxAttempts" to delays.size,
                    "hostId" to target.hostId,
                    "generation" to generation,
                    "previousClientPresent" to (clientRef != null),
                )
                withContext(NonCancellable) {
                    closeCurrentConnectionAndJoin(preserveConnectingTarget = target)
                }
                connectingTarget = target
                refreshReconnectAvailability()
                runConnect(target, attempt, trigger)
                if (inlineConnectionStatus is ConnectionStatus.Connected) {
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
                    setConnectionState(
                        ConnectionState.Unreachable(
                            "$reason Auto reconnect stopped: ${nonRetryableReason(failureCause)}. " +
                                "Tap Reconnect to retry.",
                        ),
                    )
                    recordAutoReconnectDecision(
                        decision = "suppressed_manual_only",
                        target = target,
                        trigger = trigger,
                        reason = reason,
                        cause = "non_retryable_connect_failure",
                        "failureClass" to failureCause?.javaClass?.simpleName,
                        "attempt" to (index + 1),
                        "maxAttempts" to delays.size,
                    )
                    autoReconnectJob = null
                    return@launch
                }
            }
            connectingTarget = target
            refreshReconnectAvailability()
            setConnectionState(
                ConnectionState.Unreachable(
                    "$reason Auto reconnect failed after ${delays.size} attempts.",
                ),
            )
            recordAutoReconnectDecision(
                decision = "exhausted",
                target = target,
                trigger = trigger,
                reason = reason,
                cause = "max_attempts",
                "maxAttempts" to delays.size,
            )
            autoReconnectJob = null
        }
    }

    private fun pauseAutoReconnectUntilForeground(
        target: ConnectionTarget,
        reason: String,
        trigger: TmuxConnectTrigger = TmuxConnectTrigger.AutoReconnect,
        cause: String = "app_background",
        diagnosticFields: Array<out Pair<String, Any?>> = emptyArray(),
    ) {
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        unregisterCurrentClient()
        activeTarget = target
        connectingTarget = null
        pausedAutoReconnect = PausedAutoReconnect(
            target = target,
            reason = reason,
        )
        refreshReconnectAvailability()
        recordAutoReconnectDecision(
            decision = "cancelled_due_to_background",
            target = target,
            trigger = trigger,
            reason = reason,
            cause = cause,
            *diagnosticFields,
        )
        setConnectionState(
            ConnectionState.Unreachable(
                "$reason Auto reconnect paused while PocketShell is in the background.",
            ),
        )
    }

    /**
     * Visible-for-test entry point: bind the view model to a
     * caller-supplied [TmuxClient] without the SSH connect / factory
     * path. Tests drive the client's [TmuxClient.events] flow and assert
     * against [panes].
     */
    internal fun attachClientForTest(client: TmuxClient) {
        attachClient(client)
        setConnectionState(ConnectionState.Live("test", 0, "test"))
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
        setConnectionState(ConnectionState.Live("test", 0, "test"))
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
        clientRegistration = activeTmuxClients.register(
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
        setConnectionState(ConnectionState.Live(host, port, user))
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
        // Issue #437 (slice A) / #661: mirror production — a same-host fast
        // switch enters [Switching] (inline indicator), NOT the blanking
        // full-screen [Connecting] overlay; and per #661 it HIDES the terminal
        // surface ([_switchHidesTerminal]) so the leaving frame is never painted
        // until the new session's panes are seeded.
        _switchHidesTerminal.value = true
        setConnectionState(ConnectionState.Attaching(host, port, user))
        recordWarmSwitchMilestone(
            attempt = attempt,
            target = target,
            startedAtMs = startedAtMs,
            name = "warm_switch_selected_session_state",
            trigger = TmuxConnectTrigger.FastSwitch,
            detail = "paneCount=${_panes.value.size}",
        )
        // Mirror production's ordering: deactivate the previous tmux/UI runtime
        // into the warm cache before binding the new one. Issue #661: do NOT
        // re-publish the leaving frame — blank the rendered panes and rely on
        // [_switchHidesTerminal] to show the loading state until the new
        // session's panes reconcile and reveal.
        closeCachedRuntimesAsync(deactivateCurrentRuntimeToCache())
        _panes.value = emptyList()
        rebuildUnifiedPanes()
        sessionRef = session
        // Inline a simplified runFastSessionSwitch: we cannot call the
        // real method directly because it pulls tmuxClientFactory in,
        // and the test fakes the [TmuxClient] outright.
        activeTarget = target
        // Issue #661: [deactivateCurrentRuntimeToCache] cleared [activeTarget]
        // (so the unified rebuild it triggered above could not see the host and
        // dropped the cached panes). Now that the new target is bound, rebuild
        // the unified list so it reflects the active session PLUS the just-cached
        // leaving session — mirroring production, where the post-attach
        // reconcile ([applyParsedPanes] -> [rebuildUnifiedPanes]) repopulates the
        // pager with a live [activeTarget]. The real path does this via
        // reconcile; the test helper fakes the client and never reconciles, so
        // it must rebuild here to keep the cross-session pager populated.
        rebuildUnifiedPanes()
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
        clientRegistration = activeTmuxClients.register(
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
        // Issue #661: reveal the new session's surface at the Connected flip.
        _switchHidesTerminal.value = false
        setConnectionState(ConnectionState.Live(host, port, user))
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
        trigger: TmuxConnectTrigger = TmuxConnectTrigger.UserTap,
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
            trigger = trigger,
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
        setConnectionState(ConnectionState.Connecting(host, port, user))
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
            // Issue #640: mirror runConnect ordering exactly so unit tests
            // exercise the real seed-before-reveal + skip-redundant-reseed path
            // (the test seam used to flip Connected immediately and never
            // reseed, hiding the production ordering it is supposed to verify).
            panesSeededThisAttach.clear()
            awaitPanesReadyForAttach(target, attempt, startedAtMs, trigger)
            reseedAllVisiblePanes()
            connectingTarget = null
            refreshReconnectAvailability()
            setConnectionState(ConnectionState.Live(host, port, user))
            markSuccessfulAttachForNetworkCoalescing(target, trigger)
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
        setConnectionState(ConnectionState.Connecting(host, port, user))
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
            is ControlEvent.PaneModeChanged,
            -> reconcilePanes()
            is ControlEvent.Output -> {
                logFirstPaneOutput(event)
            }
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
        append("#{window_index}")
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
        append("#{pane_tty}")
        append(LIST_PANES_FIELD_SEPARATOR)
        append("#{pane_in_mode}'")
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
            .sortedWith(compareBy({ it.windowIndex ?: Int.MAX_VALUE }, { it.windowId }, { it.paneIndex }, { it.paneId }))

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
                    windowIndex = p.windowIndex,
                    sessionId = p.sessionId,
                    title = p.title,
                    cwd = p.cwd,
                    currentCommand = p.currentCommand,
                    // Issue #186: refresh `paneTty` so per-pane agent
                    // detection always uses the current TTY (tmux can
                    // rotate panes between ptys on detach/reattach in
                    // rare cases).
                    paneTty = p.paneTty,
                    inCopyMode = p.inCopyMode,
                )
            } else {
                val state = newTerminalSurfaceState()
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
                    windowIndex = p.windowIndex,
                    sessionId = p.sessionId,
                    title = p.title,
                    cwd = p.cwd,
                    currentCommand = p.currentCommand,
                    paneTty = p.paneTty,
                    inCopyMode = p.inCopyMode,
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
            paneOutputActivityJobs.remove(paneId)?.cancel()
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
        rebuildUnifiedPanes()
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
                onTerminalFeedFailure = { cause ->
                    if (cause is TerminalSeedGateOverflowException) {
                        handleTerminalSeedGateOverflow(paneId, cause)
                    } else {
                        reportTerminalSurfaceFailure(paneId, cause)
                    }
                },
            )
        }.onSuccess { job ->
            paneProducerJobs[paneId] = job
            startPaneOutputActivityForPane(paneId = paneId, client = client)
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

    private fun rebindVisiblePaneProducersToClient(client: TmuxClient) {
        for (pane in paneRows.values) {
            val paneId = pane.paneId
            paneProducerJobs.remove(paneId)?.cancel()
            paneOutputActivityJobs.remove(paneId)?.cancel()
            panePortDetectorJobs.remove(paneId)?.cancel()
            paneInputJobs.remove(paneId)?.cancel()
            paneInputQueues.remove(paneId)?.close()
            attachTerminalProducerForPane(
                paneId = paneId,
                state = pane.terminalState,
                client = client,
            )
        }
    }

    private fun startPaneOutputActivityForPane(paneId: String, client: TmuxClient) {
        if (paneOutputActivityJobs[paneId]?.isActive == true) return
        paneOutputActivityJobs[paneId]?.cancel()
        paneOutputActivityJobs[paneId] = bridgeScope.launch {
            client.outputFor(paneId).collect { event ->
                recordVisiblePaneOutput(event)
            }
        }
    }

    private fun recordVisiblePaneOutput(event: ControlEvent.Output) {
        logFirstPaneOutput(event)
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

    private fun handleTerminalOutputBacklogOverflow(overflow: TmuxOutputBacklogOverflow) {
        val existing = paneRows[overflow.paneId] ?: return
        if (existing.surfaceError) return
        Log.w(
            ISSUE_145_RECONNECT_TAG,
            "tmux-terminal-output-backlog-overflow pane=${overflow.paneId} " +
                "droppedEvents=${overflow.droppedEvents} status=${_connectionStatus.value}",
        )
        DiagnosticEvents.record(
            "connection",
            "terminal_output_overflow",
            "pane" to overflow.paneId,
            "droppedEvents" to overflow.droppedEvents,
            "status" to _connectionStatus.value.javaClass.simpleName,
            "source" to "pane_output_backlog",
            "classification" to "local_terminal_renderer_backpressure",
            "reconnect" to false,
            "tmuxDisconnected" to clientRef?.disconnected?.value,
            "hostId" to activeTarget?.hostId,
            "host" to activeTarget?.host,
            "port" to activeTarget?.port,
            "user" to activeTarget?.user,
            "session" to activeTarget?.sessionName,
            "clientHash" to clientRef?.let { System.identityHashCode(it) },
            "generation" to connectGeneration,
            "attempt" to activeAttachMilestone?.attempt,
            "activeTrigger" to activeAttachMilestone?.trigger?.logValue,
        )

        paneProducerJobs.remove(overflow.paneId)?.cancel()
        paneOutputActivityJobs.remove(overflow.paneId)?.cancel()
        panePortDetectorJobs.remove(overflow.paneId)?.cancel()
        runCatching { existing.terminalState.detachExternalProducer() }

        val errored = existing.copy(surfaceError = true)
        paneRows[overflow.paneId] = errored
        _panes.update { rows ->
            rows.map { row -> if (row.paneId == overflow.paneId) errored else row }
        }
    }

    private fun handleTerminalSeedGateOverflow(
        paneId: String,
        overflow: TerminalSeedGateOverflowException,
    ) {
        val existing = paneRows[paneId] ?: return
        if (existing.surfaceError) return
        Log.w(
            ISSUE_145_RECONNECT_TAG,
            "tmux-terminal-seed-gate-overflow pane=$paneId " +
                "pendingBytes=${overflow.pendingBytes} incomingBytes=${overflow.incomingBytes} " +
                "maxBytes=${overflow.maxBytes} status=${_connectionStatus.value}",
            overflow,
        )
        DiagnosticEvents.record(
            "connection",
            "terminal_output_overflow",
            "pane" to paneId,
            "pendingBytes" to overflow.pendingBytes,
            "incomingBytes" to overflow.incomingBytes,
            "maxBytes" to overflow.maxBytes,
            "status" to _connectionStatus.value.javaClass.simpleName,
            "source" to "seed_gate_live_buffer",
            "classification" to "local_terminal_renderer_backpressure",
            "reconnect" to false,
            "tmuxDisconnected" to clientRef?.disconnected?.value,
            "hostId" to activeTarget?.hostId,
            "host" to activeTarget?.host,
            "port" to activeTarget?.port,
            "user" to activeTarget?.user,
            "session" to activeTarget?.sessionName,
            "clientHash" to clientRef?.let { System.identityHashCode(it) },
            "generation" to connectGeneration,
            "attempt" to activeAttachMilestone?.attempt,
            "activeTrigger" to activeAttachMilestone?.trigger?.logValue,
        )

        paneProducerJobs.remove(paneId)?.cancel()
        paneOutputActivityJobs.remove(paneId)?.cancel()
        panePortDetectorJobs.remove(paneId)?.cancel()
        paneInputJobs.remove(paneId)?.cancel()
        paneInputQueues.remove(paneId)?.close()
        runCatching { existing.terminalState.detachExternalProducer() }

        val errored = existing.copy(surfaceError = true)
        paneRows[paneId] = errored
        _panes.update { rows ->
            rows.map { row -> if (row.paneId == paneId) errored else row }
        }
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
        DiagnosticEvents.record(
            "connection",
            "terminal_surface_failure",
            "pane" to paneId,
            "cause" to cause.javaClass.simpleName,
            "message" to cause.message,
            "recentFailures" to recentFailures,
            "stormThreshold" to SURFACE_RECOVERY_STORM_THRESHOLD,
            "thresholdMs" to SURFACE_RECOVERY_WINDOW_MS,
            "willEnterSurfaceError" to (recentFailures >= SURFACE_RECOVERY_STORM_THRESHOLD),
            "classification" to "local_terminal_surface_failure",
            "decision" to if (recentFailures >= SURFACE_RECOVERY_STORM_THRESHOLD) {
                "surface_error"
            } else {
                "recreate_surface"
            },
            "reconnect" to false,
            "tmuxDisconnected" to clientRef?.disconnected?.value,
            "hostId" to activeTarget?.hostId,
            "host" to activeTarget?.host,
            "port" to activeTarget?.port,
            "user" to activeTarget?.user,
            "session" to activeTarget?.sessionName,
            "clientHash" to clientRef?.let { System.identityHashCode(it) },
            "generation" to connectGeneration,
            "attempt" to activeAttachMilestone?.attempt,
            "activeTrigger" to activeAttachMilestone?.trigger?.logValue,
            *shortAppSwitchReconnectFields(
                trigger = activeAttachMilestone?.trigger,
                sourceCandidate = "terminal_surface",
            ),
        )

        paneProducerJobs.remove(paneId)?.cancel()
        paneOutputActivityJobs.remove(paneId)?.cancel()
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

        val replacementState = newTerminalSurfaceState()
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
        paneOutputActivityJobs.remove(paneId)?.cancel()
        paneInputJobs.remove(paneId)?.cancel()
        paneInputQueues.remove(paneId)?.close()
        runCatching { existing.terminalState.detachExternalProducer() }

        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-terminal-surface-recreate pane=$paneId status=${_connectionStatus.value}",
        )

        val replacementState = newTerminalSurfaceState()
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

    /**
     * Issue #722 (characterization test seam): build a [RuntimeRefreshGuard]
     * pinned to the VM's CURRENT live runtime (generation + active target +
     * attached client), mirroring exactly how the production reseed/blank-
     * watchdog call sites construct theirs (e.g. `blankReseedGuard` at the
     * cold/slow reveal). Returns null when no client/target is attached, which
     * is itself a characterized state (the cluster no-ops without a runtime).
     *
     * Test-only; adds no production behavior. Lets a JVM characterization test
     * invoke the private reseed/watchdog cluster against a known runtime without
     * having to drive the full connect coroutine state machine.
     */
    @androidx.annotation.VisibleForTesting
    internal fun currentRuntimeGuardForTest(): RuntimeRefreshGuard? {
        val client = clientRef ?: return null
        val target = activeTarget ?: return null
        return RuntimeRefreshGuard(
            generation = connectGeneration,
            target = target,
            client = client,
        )
    }

    /**
     * Issue #722 (characterization test seam): build a [RuntimeRefreshGuard]
     * pinned to the live client/target but stamped with a SUPERSEDED generation
     * (current `connectGeneration - 1`), modelling a guard captured BEFORE a
     * switch/reconnect bumped the generation. [isCurrentRuntime] returns false
     * for it, so the reseed/watchdog cluster must abort against it — WITHOUT
     * disconnecting the live client (which would itself kick off the VM's
     * auto-reconnect machinery and is a different scenario). Returns null when
     * nothing is attached. Test-only; no production behavior.
     */
    @androidx.annotation.VisibleForTesting
    internal fun supersededRuntimeGuardForTest(): RuntimeRefreshGuard? {
        val client = clientRef ?: return null
        val target = activeTarget ?: return null
        return RuntimeRefreshGuard(
            generation = connectGeneration - 1L,
            target = target,
            client = client,
        )
    }

    /**
     * Issue #722 (characterization test seam): invoke [reseedBlankVisiblePanes]
     * directly against the supplied guard (typically from
     * [currentRuntimeGuardForTest]). A passthrough — no production logic.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun reseedBlankVisiblePanesForTest(guard: RuntimeRefreshGuard?) {
        reseedBlankVisiblePanes(guard)
    }

    /**
     * Issue #722 (characterization test seam): arm [armConnectedBlankWatchdog]
     * directly against the supplied guard. A passthrough — no production logic.
     */
    @androidx.annotation.VisibleForTesting
    internal fun armConnectedBlankWatchdogForTest(guard: RuntimeRefreshGuard) {
        armConnectedBlankWatchdog(guard)
    }

    /**
     * Issue #722 (characterization test seam): set the loading-overlay flag
     * [_switchHidesTerminal] so a test can recreate the exact post-reveal state
     * the blank watchdog is handed off into (overlay raised over a blank pane).
     * Touches only the overlay flag — NOT the connection-status machinery.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setSwitchHidesTerminalForTest(hidden: Boolean) {
        _switchHidesTerminal.value = hidden
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
        val client = clientRef ?: run {
            for (pane in newPanes) pane.terminalState.openSeedGateWithoutSeed()
            return
        }
        if (newPanes.isEmpty()) return

        // Issue #661 (switch latency, research-spike opt #3 + #1): only the
        // ACTIVE/visible pane needs to be seeded BEFORE the surface reveals.
        // tmux control mode is strictly one-command-at-a-time, so seeding every
        // pane serially before reveal makes the perceived switch latency scale
        // with pane/window count (a 3-window agent session paid ~N capture
        // round-trips before the user saw ANYTHING — the exact "switching feels
        // slow" the maintainer reported, and the window the stale-frame flash
        // used to live in).
        //
        // The active pane is the head of the reconciled list (panes are sorted
        // by window index, so the lowest-window pane is page 0 — what the screen
        // renders first). Seed it synchronously here so the #640 seed-before-
        // reveal contract still holds for the pane the user actually sees:
        // [awaitPanesReadyForAttach] only returns (and the caller reveals) after
        // this function returns, so the active pane's capture has landed before
        // the first painted frame.
        //
        // The remaining (off-screen / other-window) panes are background-seeded
        // in [bridgeScope] AFTER reveal — the user can't see them yet, and the
        // #662 [reseedVisiblePaneIfBlank] safety net (wired to every pager
        // settle) re-captures any that are still blank the instant the user tabs
        // to them. This collapses the perceived O(N) capture cost to O(1).
        val activePane = newPanes.first()
        val backgroundPanes = newPanes.drop(1)
        try {
            if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) {
                activePane.terminalState.openSeedGateWithoutSeed()
            } else if (!seedPaneFromCapture(client, activePane, refreshGuard, recordMilestone = true)) {
                // Issue #468: a failed/empty active-pane capture must still open
                // the seed gate so buffered live %output flushes in order.
                activePane.terminalState.openSeedGateWithoutSeed()
            }
        } catch (t: Throwable) {
            // Open the gate before propagating so the producer is never left
            // permanently buffering behind a closed seed gate.
            activePane.terminalState.openSeedGateWithoutSeed()
            throw t
        }

        if (backgroundPanes.isEmpty()) return
        // Background-seed the off-screen panes without blocking the reveal. Each
        // still manages its own seed gate (so buffered live output flushes in
        // order even if its capture fails), and the per-pane refresh guard keeps
        // a superseded runtime from re-seeding stale panes.
        bridgeScope.launch {
            for (pane in backgroundPanes) {
                if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) {
                    // Runtime superseded mid-flight: open the gates of the panes
                    // we did not reach so none stays stuck behind a closed gate.
                    pane.terminalState.openSeedGateWithoutSeed()
                    continue
                }
                val seeded = try {
                    seedPaneFromCapture(client, pane, refreshGuard, recordMilestone = false)
                } catch (t: Throwable) {
                    if (t is CancellationException) {
                        pane.terminalState.openSeedGateWithoutSeed()
                        throw t
                    }
                    false
                }
                if (!seeded) {
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
            // Issue #640: skip panes already seeded during this attach. On a
            // cold open every visible pane is freshly created and seeded by
            // [preloadVisibleContentForNewPanes], so this loop becomes a no-op
            // and the redundant second full reseed (one extra capture per pane)
            // is eliminated. The reseed still fires for panes *reused* across a
            // reconcile / reattach (the #553 case) — those were never added to
            // [panesSeededThisAttach] this attach, so they get their authoritative
            // post-attach repaint here.
            if (pane.paneId in panesSeededThisAttach) continue
            // [seedPaneFromCapture] feeds the snapshot through
            // [TerminalSurfaceState.appendRemoteOutput], which is safe to call
            // on an already-open gate (it is a feed + an open no-op), so a pane
            // that was already seeded as "new" simply gets its current frame
            // re-painted in place.
            seedPaneFromCapture(client, pane, refreshGuard, recordMilestone = false)
        }
    }

    /**
     * Issue #662: re-seed any visible pane whose local emulator is rendering a
     * BLANK (black) screen from a fresh `capture-pane`, so a live connection can
     * never strand the user on a black pane.
     *
     * The maintainer's dogfood symptom: a session with multiple windows where
     * EVERY window renders black (just a cursor at home), and switching windows
     * does not recover it. Root cause: a full-screen remote app (an agent / a
     * pager) paints its frame once and then stays IDLE — it emits no further
     * `%output`. Its ONLY on-screen content source after attach is the
     * attach-time `capture-pane` seed. If that seed never lands for a pane —
     * because the combined capture round-trip failed/timed out (real-network
     * latency, a slow agent capture), OR because the post-attach control-client
     * resize reflowed the pane AFTER the seed and the idle app emitted no fresh
     * redraw — the pane stays black. #634's warm reveal made this worse by
     * skipping the post-attach [reseedAllVisiblePanes] safety net for a warm
     * open, on the assumption every pane was already seeded by
     * [preloadVisibleContentForNewPanes]; a pane whose capture failed slips
     * through that assumption.
     *
     * tmux's grid still HOLDS the content for an idle app even after a reflow
     * (verified: `capture-pane` post-resize returns the content), so a fresh
     * capture restores it. This pass is the universal safety net: it runs after
     * the surface reveals (and after the control-client resize settles) and on
     * every window switch, re-capturing ONLY panes whose emulator is actually
     * blank — so it costs nothing for panes that already painted, and always
     * heals a black pane on a live connection.
     */
    private suspend fun reseedBlankVisiblePanes(refreshGuard: RuntimeRefreshGuard? = null) {
        val client = clientRef ?: return
        val panes = paneRows.values.toList()
        for (pane in panes) {
            if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return
            if (!pane.terminalState.visibleScreenIsBlank()) continue
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-blank-pane-reseed pane=${pane.paneId} window=${pane.windowId} " +
                    "session=${activeTarget?.sessionName} status=${_connectionStatus.value}",
            )
            seedPaneFromCapture(client, pane, refreshGuard, recordMilestone = false)
        }
    }

    /**
     * Issue #693/#661: the never-reveal-black gate. Before a connect/switch
     * reveal flips the surface to `Connected`, make sure the ACTIVE (visible,
     * page-0) pane actually has a non-blank seed. If the active pane's seed
     * came back empty on a degraded link, RETRY it (bounded) so the user never
     * sees a black active pane the instant the green dot lights up.
     *
     * #661 made this worse: its O(1) reveal gates on the active pane's single
     * capture, so a degraded switch could reveal a BLACK active pane with no
     * reconnecting band. This gate closes that hole: it returns true only when
     * the active pane is non-blank (safe to reveal as Connected), and false
     * when every retry still left it blank (the caller keeps the loading state
     * and hands off to [armConnectedBlankWatchdog], which keeps re-seeding under
     * a calm overlay rather than painting a black Connected pane).
     *
     * Returns true when there is no active pane to gate on (nothing to reveal
     * black) or when the active pane is/became non-blank.
     */
    private suspend fun awaitActivePaneSeededOrLoading(
        refreshGuard: RuntimeRefreshGuard?,
    ): Boolean {
        val client = clientRef ?: return true
        var attempt = 0
        while (true) {
            if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return true
            if (client.disconnected.value) return true
            val activePane = activeVisiblePane() ?: return true
            if (!activePane.terminalState.visibleScreenIsBlank()) return true
            // Active pane is blank: try to (re-)seed it before revealing.
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-reveal-gate-active-pane-blank pane=${activePane.paneId} " +
                    "attempt=$attempt session=${activeTarget?.sessionName}",
            )
            seedPaneFromCapture(client, activePane, refreshGuard, recordMilestone = false)
            if (!activePane.terminalState.visibleScreenIsBlank()) return true
            attempt += 1
            if (attempt >= ACTIVE_PANE_REVEAL_SEED_ATTEMPTS) {
                Log.w(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-reveal-gate-active-pane-still-blank pane=${activePane.paneId} " +
                        "after=$attempt session=${activeTarget?.sessionName} " +
                        "-> reveal deferred to blank watchdog",
                )
                return false
            }
            delay(ACTIVE_PANE_REVEAL_SEED_DELAY_MS)
        }
    }

    /**
     * The pane the user is actually looking at right now: the head of the
     * window-sorted [_panes] (page 0 of the pager). Falls back to the first
     * [paneRows] entry when [_panes] has not been published yet. Null when there
     * are no panes.
     */
    private fun activeVisiblePane(): TmuxPaneState? =
        _panes.value.firstOrNull() ?: paneRows.values.firstOrNull()

    /**
     * Issue #693: never leave a `Connected` pane black. When a reveal had to
     * fall through with a still-blank active pane (the link stayed degraded past
     * [awaitActivePaneSeededOrLoading]'s bound), arm a bounded watchdog that
     * keeps re-seeding any blank visible pane and only clears the loading
     * overlay once a real frame lands. This is the last line of defense behind
     * the reveal gate, the per-switch reseed, and the resize/defer nets: as long
     * as the channel is `Connected`, the user ends up on real content or a calm
     * "Attaching…" overlay — never a silent black pane.
     */
    private fun armConnectedBlankWatchdog(refreshGuard: RuntimeRefreshGuard) {
        bridgeScope.launch {
            var tick = 0
            while (tick < CONNECTED_BLANK_WATCHDOG_MAX_TICKS) {
                delay(CONNECTED_BLANK_WATCHDOG_TICK_MS)
                if (!isCurrentRuntime(refreshGuard)) return@launch
                val client = clientRef ?: return@launch
                if (client.disconnected.value) return@launch
                if (inlineConnectionStatus !is ConnectionStatus.Connected) return@launch
                val activePane = activeVisiblePane()
                if (activePane == null || !activePane.terminalState.visibleScreenIsBlank()) {
                    // A frame landed (seed or live %output) — drop the loading
                    // overlay and let the Connected surface show the content.
                    if (_switchHidesTerminal.value) _switchHidesTerminal.value = false
                    return@launch
                }
                Log.i(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-connected-blank-watchdog tick=$tick pane=${activePane.paneId} " +
                        "session=${activeTarget?.sessionName} -> re-seeding blank visible panes",
                )
                reseedBlankVisiblePanes(refreshGuard)
                if (!activePane.terminalState.visibleScreenIsBlank()) {
                    if (_switchHidesTerminal.value) _switchHidesTerminal.value = false
                    return@launch
                }
                tick += 1
            }
        }
    }

    /**
     * Issue #662: re-seed a single visible pane from `capture-pane` if its
     * emulator is rendering a blank (black) screen. Called when the user
     * switches to a window: tmux `-CC` never re-emits an idle window's existing
     * content, so a window that was never successfully seeded (or whose seed was
     * wiped by a reflow) would otherwise stay black no matter how many times the
     * user switches to it. A no-op when the pane already shows content.
     */
    public fun reseedVisiblePaneIfBlank(paneId: String) {
        val pane = paneRows[paneId] ?: return
        val client = clientRef ?: return
        if (client.disconnected.value) return
        if (!pane.terminalState.visibleScreenIsBlank()) return
        bridgeScope.launch {
            // Re-check inside the coroutine: the pane may have painted between
            // the synchronous guard and dispatch (a late `%output` landed).
            if (!pane.terminalState.visibleScreenIsBlank()) return@launch
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-blank-pane-reseed-on-switch pane=$paneId window=${pane.windowId} " +
                    "session=${activeTarget?.sessionName}",
            )
            seedPaneFromCapture(client, pane, refreshGuard = null, recordMilestone = false)
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
    /**
     * Issue #693/#662: seed a pane from `capture-pane`, RETRYING when the
     * capture comes back empty/error/null on a flaky link.
     *
     * The black-screen-while-connected bug: [seedPaneFromCaptureOnce] used to
     * be the only seed call, and it treats an empty/error/null capture as a
     * SILENT no-op (`return false` — no repaint, no keep-last, NO retry). On a
     * degraded-but-connected channel the attach-time capture can transiently
     * return empty; the idle full-screen agent/pager then emits no `%output`,
     * so the pane's emulator stays unpainted and the surface renders the
     * near-black background while status is `Connected` — the maintainer's
     * green-dot-but-black-pane screenshots (and the partial/orphaned-cell
     * variant, which is the SAME unseeded grid showing only a few live deltas).
     *
     * Retrying a transiently-empty capture (bounded, short backoff) lands the
     * frame the next time the link recovers. A persistently-empty capture means
     * tmux genuinely has nothing for that pane (a truly blank shell) — those
     * retries are cheap and stop after the bound. The caller keeps the last
     * rendered frame on a still-empty result (we never clear the emulator on a
     * failed capture), so a momentary drop never repaints black.
     */
    private suspend fun seedPaneFromCapture(
        client: TmuxClient,
        pane: TmuxPaneState,
        refreshGuard: RuntimeRefreshGuard?,
        recordMilestone: Boolean,
    ): Boolean {
        var attempt = 0
        while (true) {
            if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return false
            if (client.disconnected.value) return false
            if (seedPaneFromCaptureOnce(client, pane, refreshGuard, recordMilestone)) {
                return true
            }
            attempt += 1
            if (attempt >= SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS) return false
            // Short backoff so a flaky-link empty capture is re-tried after the
            // channel has a moment to recover, without stalling a genuinely
            // empty pane's reveal for long. The guard re-check at the top of the
            // loop aborts immediately if the runtime was superseded mid-wait.
            delay(SEED_CAPTURE_EMPTY_RETRY_DELAY_MS)
        }
    }

    private suspend fun seedPaneFromCaptureOnce(
        client: TmuxClient,
        pane: TmuxPaneState,
        refreshGuard: RuntimeRefreshGuard?,
        recordMilestone: Boolean,
    ): Boolean {
        // Issue #640: one single-flight round-trip that captures the pane AND
        // its cursor. tmux `-CC` control mode assigns each `;`-separated command
        // its OWN `%begin`/`%end` block, so the cursor cannot ride inside the
        // capture's block — [TmuxClient.captureWithCursor] therefore sends the
        // chained command and drains BOTH response blocks under a single
        // [sendMutex] acquisition, collapsing the previous two serial seed
        // round-trips into one wire exchange. The cursor restore (#259) is
        // preserved, not dropped.
        val captureStartedAtMs = SystemClock.elapsedRealtime()
        val combined = runCatching {
            client.captureWithCursor(pane.paneId, scrollbackLines = SEED_SCROLLBACK_LINES)
        }.getOrNull()
        val captureDurationMs = SystemClock.elapsedRealtime() - captureStartedAtMs
        val captureResponse = combined?.capture
        TmuxSessionLatencyTelemetry.record(
            name = "capture_pane",
            durationMs = captureDurationMs,
            sessionName = activeTarget?.sessionName,
            paneId = pane.paneId,
            trigger = activeAttachMilestone?.trigger,
            detail = "success=${captureResponse != null && !captureResponse.isError} folded_cursor=true",
        )
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return false
        if (captureResponse == null || captureResponse.isError || captureResponse.output.isEmpty()) {
            return false
        }
        // Issue #259/#640: the cursor reply arrived in the SAME single-flight
        // exchange as the capture (its own control-mode block, drained together),
        // so it no longer costs a second serial round-trip. Record a zero-cost
        // `cursor_query` leg (folded into the capture exchange) so the latency
        // artifact still proves the cursor restore is in place and the
        // round-trip reduction is visible.
        val cursor = parseTmuxPaneCursor(combined.cursorReply)
        TmuxSessionLatencyTelemetry.record(
            name = "cursor_query",
            durationMs = 0L,
            sessionName = activeTarget?.sessionName,
            paneId = pane.paneId,
            trigger = activeAttachMilestone?.trigger,
            detail = "folded_into_capture=true present=${cursor != null}",
        )
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return false
        val response = captureResponse
        val appendStartedAtMs = SystemClock.elapsedRealtime()
        try {
            pane.terminalState.appendRemoteOutput(
                response.output.toTerminalViewportBytes(cursor),
            )
        } catch (cause: Throwable) {
            if (cause is CancellationException) throw cause
            reportTerminalSurfaceFailure(pane.paneId, cause)
            return false
        }
        // Issue #640: record that this pane was seeded during the current
        // attach so the post-attach reveal can skip the redundant second full
        // reseed for panes already painted in this pass.
        panesSeededThisAttach.add(pane.paneId)
        // EPIC #687 slice 1c-iv-prep: feed the shadow controller the REAL
        // "seed/capture landed" signal at the EXISTING point a capture-pane lands
        // for a pane — a FIRE-AND-OBSERVE emit placed AFTER the panesSeededThisAttach
        // write above, so it adds no write-path control flow. Combined with the real
        // TransportLive feedback this is how the shadow controller reaches Live from
        // genuine signals (the active-pane landing promotes Attaching → Live).
        observeSeedLandedInShadow(pane.paneId)
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
        agentSessionMemory.recall(
            hostId = target.hostId,
            sessionName = target.sessionName,
            windowId = windowId,
        ) ?: return false
        // Only defer while the seeded agent UI is actually still showing; once
        // detection has confirmed an exit and the row is gone, there is
        // nothing to protect.
        return _agentConversations.value[paneId]?.detection != null
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
        val lineCount = recoverAgentConversationStartupRead(
            paneId = paneId,
            detection = detection,
            operation = "line_count",
            fallback = 0L,
        ) {
            agentRepository.lineCount(session, detection)
        }
        val initialEvents = recoverAgentConversationStartupRead(
            paneId = paneId,
            detection = detection,
            operation = "initial_read",
            fallback = emptyList(),
        ) {
            agentRepository.readInitialEvents(session, detection)
        }
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return
        markAgentTailLive(paneId, detection, initialEvents)
        // Issue #160: OpenCode now tails its JSONL via `session.tail`
        // identically to Claude and Codex. No more polling branch — the
        // tmux pane gets the same real-time refresh as the raw-SSH route.
        // Issue #576: use the batched/debounced tail so a Codex `/new`
        // (or any large JSONL replay) collapses into a handful of
        // reconcile + StateFlow-emit cycles instead of one per line.
        val followJob = agentRepository.tailEventsBatchedFromLine(session, detection, lineCount) { batch ->
            appendAgentEvents(paneId, batch)
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
        val lineCount = recoverAgentConversationStartupRead(
            paneId = paneId,
            detection = detection,
            operation = "restore_line_count",
            fallback = 0L,
        ) {
            agentRepository.lineCount(session, detection)
        }
        val initialEvents = recoverAgentConversationStartupRead(
            paneId = paneId,
            detection = detection,
            operation = "restore_initial_read",
            fallback = restored.events,
        ) {
            agentRepository.readInitialEvents(session, detection)
        }
        if (!isCurrentRuntime(refreshGuard)) return
        markRestoredAgentTailLive(paneId, detection, initialEvents)
        // Issue #576: batched/debounced tail (see startAgentConversationForPane).
        val followJob = agentRepository.tailEventsBatchedFromLine(session, detection, lineCount) { batch ->
            appendAgentEvents(paneId, batch)
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
        DiagnosticEvents.record("action", "tmux_attachment_stage_start", "count" to uris.size)
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
        val result = PromptAttachmentStager(
            resolver = context.contentResolver,
            cacheDir = context.cacheDir,
        ).stage(session, scopeKey, uris)
        result.fold(
            onSuccess = { paths ->
                DiagnosticEvents.record(
                    "action",
                    "tmux_attachment_stage_success",
                    "requestedCount" to uris.size,
                    "stagedCount" to paths.count { it.isNotBlank() },
                    "scope" to scopeKey,
                )
            },
            onFailure = { error ->
                DiagnosticEvents.record(
                    "action",
                    "tmux_attachment_stage_fail",
                    "requestedCount" to uris.size,
                    "scope" to scopeKey,
                    "cause" to error.javaClass.simpleName,
                    "message" to error.message,
                )
            },
        )
        return result
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
        if (inlineConnectionStatus !is ConnectionStatus.Connected) return null
        return sessionRef?.takeIf { it.isConnected }
    }

    private suspend fun awaitLiveTmuxClientForSend(): TmuxClient? {
        liveTmuxClientForSendOrNull()?.let { return it }
        if (
            inlineConnectionStatus is ConnectionStatus.Failed &&
            isNonRetryableConnectFailure(lastConnectFailureCause)
        ) {
            return null
        }
        when (inlineConnectionStatus) {
            is ConnectionStatus.Connecting,
            is ConnectionStatus.Reconnecting,
            is ConnectionStatus.Switching,
            -> Unit
            else -> {
                val reconnectJob = startReconnectForSend()
                    ?: return liveTmuxClientForSendOrNull()
                reconnectJob.join()
            }
        }
        return withTimeoutOrNull(SEND_SESSION_WAIT_TIMEOUT_MS) {
            while (currentCoroutineContext().isActive) {
                liveTmuxClientForSendOrNull()?.let { return@withTimeoutOrNull it }
                delay(SEND_SESSION_WAIT_POLL_MS)
            }
            null
        }
    }

    private fun liveTmuxClientForSendOrNull(): TmuxClient? {
        if (inlineConnectionStatus !is ConnectionStatus.Connected) return null
        return clientRef?.takeUnless { it.disconnected.value }
    }

    internal suspend fun sendToAgentPaneResult(paneId: String, text: String): Result<Unit> =
        sendToAgentPaneResult(
            paneId = paneId,
            text = text,
            keepFailedOptimisticOnDeliveryFailure = false,
        )

    private suspend fun sendToAgentPaneResult(
        paneId: String,
        text: String,
        keepFailedOptimisticOnDeliveryFailure: Boolean,
    ): Result<Unit> {
        val payload = text.trim()
        if (payload.isEmpty()) return Result.success(Unit)
        DiagnosticEvents.record(
            "action",
            "agent_prompt_send",
            "pane" to paneId,
            "textBytes" to payload.toByteArray(Charsets.UTF_8).size,
        )
        val current = _agentConversations.value[paneId]
            ?: return Result.failure(IllegalStateException("No agent conversation for pane $paneId."))
        val detection = current.detection
            ?: return Result.failure(IllegalStateException("No detected agent for pane $paneId."))
        // Issue #494: insert the optimistic pending turn FIRST — before any
        // delivery attempt — so the Conversation tab shows the user's own
        // message the instant they hit Send, not after the JSONL round-trip.
        // The turn starts as [MessageSendState.Pending] ("sending…") and is
        // reconciled away when the real transcript entry arrives via the tail.
        // If the unified composer send fails, PromptComposerSheet keeps the
        // draft, so we remove this temporary row to avoid showing the same text
        // twice. Retry taps have no draft fallback and opt into leaving a
        // failed row instead.
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
        if (awaitLiveTmuxClientForSend() == null) {
            return Result.failure(IllegalStateException("Session is disconnected."))
        }
        appendAgentEvents(paneId, listOf(optimistic))
        val result = sendAgentPayloadToPaneResult(paneId, payload, detection.agent)
        if (result.isFailure) {
            if (keepFailedOptimisticOnDeliveryFailure) {
                markOptimisticSendFailed(paneId, optimisticId)
            } else {
                removeOptimisticSend(paneId, optimisticId)
            }
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

    private fun removeOptimisticSend(paneId: String, optimisticId: String) {
        updateAgentConversation(paneId) { current ->
            current.copy(events = current.events.filterNot { it.id == optimisticId })
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
            val result = sendToAgentPaneResult(
                paneId = paneId,
                text = failed.text,
                keepFailedOptimisticOnDeliveryFailure = true,
            )
            if (result.isFailure) {
                val hasFailedRetryRow = _agentConversations.value[paneId]?.events
                    ?.filterIsInstance<ConversationEvent.Message>()
                    ?.any {
                        it.text == failed.text &&
                            it.role == ConversationRole.User &&
                            it.sendState == com.pocketshell.core.agents.MessageSendState.Failed
                    } == true
                if (!hasFailedRetryRow) {
                    appendAgentEvents(paneId, listOf(failed))
                }
            }
        }
    }

    internal suspend fun sendAgentPayloadToPaneResult(
        paneId: String,
        payload: String,
        agent: AgentKind,
    ): Result<Unit> {
        val client = awaitLiveTmuxClientForSend()
            ?: return Result.failure(IllegalStateException("Session is disconnected."))
        return sendAgentPayloadToPaneResult(client, paneId, payload, agent)
    }

    private suspend fun sendAgentPayloadToPaneResult(
        client: TmuxClient,
        paneId: String,
        payload: String,
        agent: AgentKind,
    ): Result<Unit> {
        val payloadBytes = payload.toByteArray(Charsets.UTF_8)
        if (client.disconnected.value) {
            return Result.failure(IllegalStateException("Tmux client is disconnected."))
        }
        return runCatching {
            ensurePaneAcceptsInput(client, paneId)
            if (payloadBytes.size > TMUX_PASTE_BODY_CHUNK_BYTES || BracketedPaste.containsLineBreak(payloadBytes)) {
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
        val before = _agentConversations.value[paneId] ?: return
        if (tab == SessionTab.Conversation && before.detection == null) return
        val changed = before.selectedTab != tab
        if (changed) {
            updateAgentConversation(paneId) { current ->
                if (tab == SessionTab.Conversation && current.detection == null) {
                    current
                } else {
                    current.copy(selectedTab = tab)
                }
            }
        }
        if (changed) {
            DiagnosticEvents.record(
                "action",
                "session_tab_select",
                "mode" to "tmux",
                "paneId" to paneId,
                "tab" to tab.name,
                "hasConversation" to (before.detection != null),
            )
            ConversationDiagnostics.recordTabSwitch(
                mode = "tmux",
                paneId = paneId,
                fromTab = before.selectedTab.name,
                toTab = tab.name,
                hasConversation = before.detection != null,
                eventCount = before.events.size,
                syncStatus = before.syncStatus.name,
            )
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
        val before = _agentConversations.value[paneId]?.searchQuery ?: return
        if (before == query) return
        updateAgentConversation(paneId) { current ->
            current.copy(searchQuery = query)
        }
        if (before.isEmpty() != query.isEmpty()) {
            DiagnosticEvents.record(
                "action",
                "conversation_search_query_changed",
                "mode" to "tmux",
                "paneId" to paneId,
                "empty" to query.isEmpty(),
                "queryBytes" to query.toByteArray(Charsets.UTF_8).size,
            )
        }
    }

    public fun retryAgentConversationStreamForPane(paneId: String): Boolean {
        val session = sessionRef?.takeIf { it.isConnected } ?: return false
        if (inlineConnectionStatus !is ConnectionStatus.Connected) return false
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
        scanAgentConversationEventsForPortOffers(paneId, events)
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
        recordAgentConversationTailStatus(
            paneId = paneId,
            detection = detection,
            status = AgentConversationSyncStatus.LogUnavailable,
            cause = null,
            reason = "tail_start_unavailable",
        )
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

    private suspend fun <T> recoverAgentConversationStartupRead(
        paneId: String,
        detection: AgentDetection,
        operation: String,
        fallback: T,
        block: suspend () -> T,
    ): T {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Exception) {
            recordAgentConversationTailStatus(
                paneId = paneId,
                detection = detection,
                status = AgentConversationSyncStatus.LogUnavailable,
                cause = t,
                reason = operation,
            )
            fallback
        }
    }

    private fun recordAgentConversationTailStatus(
        paneId: String,
        detection: AgentDetection,
        status: AgentConversationSyncStatus,
        cause: Throwable?,
        reason: String,
    ) {
        DiagnosticEvents.record(
            "recoverable",
            "tmux_agent_conversation_tail_status",
            "pane" to paneId,
            "agent" to detection.agent.name,
            "source" to detection.sourcePath,
            "status" to status.name,
            "reason" to reason,
            "cause" to cause?.javaClass?.simpleName,
            "message" to cause?.message,
        )
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
        scanAgentConversationEventsForPortOffers(paneId, initialEvents)
    }

    private fun scanAgentConversationEventsForPortOffers(
        paneId: String,
        events: List<ConversationEvent>,
    ) {
        if (!appActive || events.isEmpty()) return
        val candidates = LinkedHashSet<Int>()
        val scopeKey = activeTarget?.let { "${it.hostId}:${it.sessionName}" } ?: "no-target"
        for (event in events) {
            val text = event.portOfferText() ?: continue
            val key = "$scopeKey:$paneId:${event.id}:${text.hashCode()}:${text.length}"
            if (!scannedConversationPortEventKeys.add(key)) continue
            for (candidate in portDetector.scan("$text\n")) {
                candidates += candidate.port
            }
        }
        if (candidates.isEmpty()) return
        bridgeScope.launch {
            for (port in candidates) {
                confirmAndSurfaceDetectedPort(port)
            }
        }
    }

    private fun ConversationEvent.portOfferText(): String? = when (this) {
        is ConversationEvent.Message -> text.takeIf {
            role == ConversationRole.Assistant && it.isNotBlank()
        }
        is ConversationEvent.ToolResult -> output.takeIf { it.isNotBlank() }
        is ConversationEvent.SystemNote -> content.takeIf { it.isNotBlank() }
        is ConversationEvent.ToolCall -> null
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
        // Issue #576: batched/debounced tail (see startAgentConversationForPane).
        val job = agentRepository.tailEventsBatchedFromLine(session, detection, fromLineExclusive) { batch ->
            appendAgentEvents(paneId, batch)
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

    internal suspend fun startAgentConversationForPaneForTest(
        paneId: String,
        session: SshSession,
        detection: AgentDetection,
    ) {
        startAgentConversationForPane(session, paneId, detection)
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
        DiagnosticEvents.record("action", "pane_input", "pane" to paneId, "bytes" to bytes.size)
        val client = clientRef ?: return
        bridgeScope.launch {
            writeInputToPaneResult(client, paneId, bytes)
        }
    }

    internal suspend fun writeInputToPaneResult(paneId: String, bytes: ByteArray): Result<Unit> {
        if (bytes.isEmpty()) return Result.success(Unit)
        val client = awaitLiveTmuxClientForSend()
        if (client == null) {
            return Result.failure(IllegalStateException("Session is disconnected."))
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

    internal fun sendControlInputToPane(
        paneId: String,
        byte: Int,
        repeatCount: Int = 1,
        prepareSmartText: Boolean = true,
    ) {
        if (byte !in 0x00..0x1F || repeatCount <= 0) return
        DiagnosticEvents.record(
            "action",
            "pane_control_input",
            "pane" to paneId,
            "byte" to byte,
            "repeatCount" to repeatCount,
        )
        val client = clientRef ?: return
        if (prepareSmartText) {
            paneRows[paneId]?.terminalState?.prepareForRawTerminalInput(TerminalRawInputPolicy.ClearSmartText)
        }
        val bytes = ByteArray(repeatCount) { byte.toByte() }
        bridgeScope.launch {
            runCatching {
                ensurePaneAcceptsInput(client, paneId)
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
            override suspend fun sendPromptToSession(sessionName: String, prompt: String): Result<Unit> {
                if (sessionName != target.sessionName) {
                    return Result.failure(IllegalStateException("Session $sessionName is not active."))
                }
                val pane = focusedPaneId
                    ?: return Result.failure(IllegalStateException("No focused pane for assistant prompt."))
                return sendToAgentPaneResult(pane, prompt)
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
                        DiagnosticEvents.record(
                            "action",
                            "pane_input_batch",
                            "pane" to paneId,
                            "bytes" to batch.bytes.size,
                        )
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
        ensurePaneAcceptsInput(client, paneId)
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
        if (BracketedPaste.containsLineBreak(bytes)) {
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
        val hex = BracketedPaste.hex(bytes)
        if (hex.isEmpty()) return
        client.sendCommand("send-keys -H -t $paneId $hex")
    }

    private suspend fun ensurePaneAcceptsInput(client: TmuxClient, paneId: String) {
        if (paneRows[paneId]?.inCopyMode != true) return
        client.sendCommand("send-keys -X -t $paneId cancel")
            .throwIfTmuxError("exit copy-mode for pane $paneId")
        markPaneCopyMode(paneId, false)
    }

    private fun markPaneCopyMode(paneId: String, inCopyMode: Boolean) {
        val existing = paneRows[paneId] ?: return
        if (existing.inCopyMode == inCopyMode) return
        val updated = existing.copy(inCopyMode = inCopyMode)
        paneRows[paneId] = updated
        _panes.value = _panes.value.map { pane ->
            if (pane.paneId == paneId) updated else pane
        }
        rebuildUnifiedPanes()
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
        for (hex in BracketedPaste.hexChunks(bytes, TMUX_PASTE_BODY_CHUNK_BYTES)) {
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
                ensurePaneAcceptsInput(client, paneId)
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
            // Issue #662: the control-client resize just reflowed every visible
            // pane to the phone grid. For an IDLE full-screen remote app (agent /
            // pager) the reflow can leave the LOCAL emulator blank (the seeded
            // pre-resize frame is wiped and the idle app emits no fresh redraw),
            // which is the maintainer's every-window-black symptom. tmux's grid
            // still HOLDS the reflowed content, so re-seed ONLY the panes whose
            // emulator is now blank from a fresh `capture-pane`. Runs on every
            // attach path (cold, warm, fast-switch, reconnect) because all of
            // them flow through this resize-completion block; a no-op for panes
            // that already render content. Skipped if the runtime moved on.
            if (clientRef === client) {
                reseedBlankVisiblePanes(
                    RuntimeRefreshGuard(
                        generation = attachGeneration,
                        target = target,
                        client = client,
                    ),
                )
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
     * Stop (kill) the session this screen is attached to (session dropdown →
     * Stop session → confirm) and, on a confirmed kill, broadcast it via
     * [sessionLifecycleSignals] so the folder/session tree drops the dead
     * row promptly instead of waiting for its next foreground re-probe
     * (issue #464; terminology + bug fix #655).
     *
     * ## Why this no longer kills over the control channel (issue #655)
     *
     * The original implementation sent `kill-session` over the SAME
     * `tmux -CC` control client this screen is attached to, then waited for a
     * `%sessions-changed` / `%session-changed` notification before emitting
     * the lifecycle signal. Killing the session you are attached to tears the
     * control session down: the post-kill notification often never arrives
     * (the control connection is closing) and a closing connection surfaces as
     * a transport failure on `sendCommand`, which hit a `return@launch` branch
     * that NEVER emitted [sessionLifecycleSignals]. The result the maintainer
     * saw in v0.3.30: the row stayed on the tree and the session could be
     * reopened, because the in-session Stop raced its own teardown.
     *
     * The host-detail Stop (#518, [FolderListViewModel.killSession]) does NOT
     * have this problem — it kills over a fresh gateway SSH-exec lease that is
     * independent of any control channel and verifies the teardown with
     * `tmux has-session`. This method now routes through the EXACT same
     * verified gateway path ([FolderListGateway.killSession]) so the two Stop
     * flows share ONE implementation and cannot diverge again. Only on a
     * CONFIRMED kill (gateway verified the session is gone) do we broadcast the
     * lifecycle signal; a failed kill never broadcasts, so a still-live session
     * keeps its row. The screen navigates away via `onBack()` after calling
     * this, so the now-dead session's control client tears down through the
     * normal compose-disposal `onCleared()` path.
     *
     * The legacy control-channel send remains ONLY as a fallback for the narrow
     * unit-test constructors that build the view model without a gateway / host
     * DAO; production Hilt always injects both, so production always takes the
     * verified path.
     */
    public fun killCurrentSession() {
        val current = activeTarget ?: return
        val target = current.sessionName
        val gateway = folderListGateway
        val dao = hostDao
        if (gateway == null || dao == null) {
            // No gateway/host DAO (unit-test constructor) — best-effort send
            // over the control channel and still signal so the tree reconciles.
            sendLifecycleCommand("kill-session -t '${escapeSingleQuoted(target)}'")
            sessionLifecycleSignals?.emitKilled(current.hostId, target)
            return
        }
        bridgeScope.launch {
            Log.i(
                ISSUE_464_KILL_TAG,
                "stop-session-start host=${current.hostId} name=$target",
            )
            val host = withContext(Dispatchers.IO) { dao.getById(current.hostId) }
            if (host == null) {
                Log.w(
                    ISSUE_464_KILL_TAG,
                    "stop-session-host-missing host=${current.hostId} name=$target",
                )
                return@launch
            }
            val result = gateway.killSession(
                host = host,
                keyPath = current.keyPath,
                passphrase = current.passphrase,
                sessionName = target,
            )
            result.fold(
                onSuccess = {
                    // Gateway verified the session is gone on the remote:
                    // broadcast so the tree drops the row + reconciles.
                    Log.i(
                        ISSUE_464_KILL_TAG,
                        "stop-session-signal host=${current.hostId} name=$target",
                    )
                    sessionLifecycleSignals?.emitKilled(current.hostId, target)
                },
                onFailure = { error ->
                    Log.w(
                        ISSUE_464_KILL_TAG,
                        "stop-session-failed host=${current.hostId} name=$target " +
                            "err=${error.javaClass.simpleName}: ${error.message}",
                    )
                },
            )
        }
    }

    /**
     * Issue #625: create a new tmux window and auto-switch the terminal
     * viewport to it. Mirrors the deterministic event-wait pattern from
     * [killWindow]: subscribe to [ControlEvent.WindowAdd] BEFORE sending
     * `new-window` so the notification cannot be missed, then after the
     * event arrives reconcile the pane list and emit the new window ID
     * through [windowSwitchRequest] so the Screen scrolls the pager.
     *
     * On transport failure or tmux `%error` the command is silently
     * dropped (matching the pre-#625 fire-and-forget behaviour). A 2s
     * fallback timeout ensures the UI is never permanently wedged if
     * tmux never emits the event.
     */
    /**
     * Issue #678: create a new window, optionally rooted at [startDirectory]
     * and optionally launching an agent (or any) command in it via the same
     * shell-vs-agent picker the new-SESSION flow uses.
     *
     * - [startDirectory]: when non-blank, the window is created with
     *   `new-window -c '<dir>'` so the new pane's shell starts there. When
     *   null/blank the command keeps the original `new-window -t '<session>'`
     *   shape (a plain window in tmux's default cwd) — the unchanged shell
     *   pick and the bare overflow/long-press default.
     * - [startCommand]: when non-null, after the `%window-add` event lands we
     *   `send-keys` it into the freshly created window (targeted by its `@N`
     *   window ID so it can never race the previously-active window). For an
     *   agent pick this is the SHORT `pocketshell agent <kind> --dir '<dir>'
     *   …` wrapper line (issue #703) — identical to the create-session path in
     *   [com.pocketshell.app.projects.FolderListGateway.createSession].
     */
    public fun newWindow(
        startDirectory: String? = null,
        startCommand: String? = null,
    ) {
        val target = activeTarget?.sessionName ?: return
        val client = clientRef ?: return
        val cwd = startDirectory?.trim()?.takeIf { it.isNotBlank() }
        bridgeScope.launch {
            // Capture the WindowAdd event's window ID. Subscribe BEFORE
            // sending the command so we never miss the %window-add
            // notification (same rationale as killWindow).
            val capturedWindowId = CompletableDeferred<String?>()
            val eventJob = bridgeScope.launch(start = CoroutineStart.UNDISPATCHED) {
                val event = withTimeoutOrNull(NEW_WINDOW_EVENT_WAIT_MS) {
                    client.events.first { event ->
                        event is ControlEvent.WindowAdd
                    } as? ControlEvent.WindowAdd
                }
                capturedWindowId.complete(event?.windowId)
            }

            val newWindowCommand = buildString {
                append("new-window -t '${escapeSingleQuoted(target)}'")
                if (cwd != null) {
                    append(" -c '${escapeSingleQuoted(cwd)}'")
                }
            }
            val sendResult = runCatching {
                client.sendCommand(newWindowCommand)
            }
            if (sendResult.isFailure) {
                eventJob.cancel()
                return@launch
            }
            val response = sendResult.getOrNull()
            if (response != null && response.isError) {
                eventJob.cancel()
                return@launch
            }

            // Wait up to NEW_WINDOW_EVENT_WAIT_MS for tmux's
            // %window-add notification.
            eventJob.join()

            // The reconciled pane list now includes the new window.
            // Emit a switch request so the Screen can scroll the pager.
            val addedWindowId = capturedWindowId.await()

            // Issue #678: launch the agent (or shell start) command in the new
            // window. Target the specific @N window ID so the keys land in the
            // window we just made, not whatever the user is currently viewing.
            // Mirror the create-session send-keys shape (issue #703).
            if (startCommand != null && addedWindowId != null) {
                runCatching {
                    client.sendCommand(
                        "send-keys -t $addedWindowId " +
                            "'${escapeSingleQuoted(startCommand)}' Enter",
                    )
                }
            }

            reconcilePanes()

            if (addedWindowId != null) {
                _windowSwitchRequest.emit(addedWindowId)
            }
        }
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
        DiagnosticEvents.record(
            "action",
            "shortcut_sent",
            "mode" to "tmux",
            "paneId" to paneId,
            "key" to label,
            "ctrlArmed" to ctrlArmed,
        )
        if (ctrlArmed) {
            consumeOneShotCtrl()
            // Ctrl + ASCII letter → the classic control byte (0x01..0x1A)
            // via the `send-keys -H` overlay path.
            val chordByte = ctrlByteForLetter(label)
            if (chordByte != null) {
                paneRows[paneId]?.terminalState?.prepareForRawTerminalInput(TerminalRawInputPolicy.ClearSmartText)
                sendControlInputToPane(paneId, chordByte, prepareSmartText = false)
                return
            }
            // Ctrl + a named key tmux understands (arrows → word navigation,
            // Tab, Esc) → tmux's own `C-<key>` chord syntax, which lets tmux
            // emit the correct terminfo encoding (e.g. ESC[1;5C for
            // Ctrl+Right). Still a `send-keys` control-channel command, so no
            // resize/redraw.
            val chordNamed = ctrlChordNamedKeyFor(label)
            if (chordNamed != null) {
                paneRows[paneId]?.terminalState?.prepareForRawTerminalInput(TerminalRawInputPolicy.ClearSmartText)
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
            val policy = smartTextPolicyForKeyBar(label)
            val terminalState = paneRows[paneId]?.terminalState
            terminalState?.prepareForRawTerminalInput(policy)
            if (policy == TerminalRawInputPolicy.FlushSmartText && named == "Enter") {
                sendEnterAfterSmartTextFlush(paneId, terminalState)
            } else {
                sendNamedKey(paneId, named)
            }
        }
    }

    private fun sendEnterAfterSmartTextFlush(paneId: String, terminalState: TerminalSurfaceState?) {
        val enterBytes = byteArrayOf('\r'.code.toByte())
        if (terminalState?.isAttached == true) {
            terminalState.writeInput(enterBytes)
        } else {
            enqueueInputBytesToPane(paneId, enterBytes)
        }
    }

    private fun enqueueInputBytesToPane(paneId: String, bytes: ByteArray) {
        if (bytes.isEmpty() || clientRef == null) return
        runCatching {
            inputSinkForPane(paneId).write(bytes)
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

    private fun smartTextPolicyForKeyBar(label: String): TerminalRawInputPolicy =
        when (label) {
            "⏎", "Enter" -> TerminalRawInputPolicy.FlushSmartText
            else -> TerminalRawInputPolicy.ClearSmartText
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
        BracketedPaste.hexPayload(bytes)

    /**
     * Issue #209 test seam: predicate the production [sendInputBytesToPane]
     * uses to decide between the named-key path and the bracketed-paste
     * path. Test-only.
     */
    internal fun containsLineBreakForTest(bytes: ByteArray): Boolean =
        BracketedPaste.containsLineBreak(bytes)

    internal fun tmuxInputMetricsForTest(paneId: String): TmuxInputStressMetrics? =
        paneInputQueues[paneId]?.snapshot()

    internal fun tmuxInputSinkForTest(paneId: String): OutputStream =
        inputSinkForPane(paneId)

    internal fun tmuxInputCapacityBytesForTest(): Int = TMUX_INPUT_MAX_PENDING_BYTES

    override fun onCleared() {
        val processForeground = isProcessForegroundForCleared()
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
                "processForeground=$processForeground " +
                "pendingReattach=${pendingReattach != null} " +
                "backgroundDetachActive=${backgroundDetachJob?.isActive == true}",
        )
        cancelTmuxSessionPrewarm()
        // EPIC #687 slice 1c-iv-b-A2 (#739): stop the observe-only effect driver's
        // collectors. Idempotent; viewModelScope also cancels them, but stopping
        // explicitly keeps the inert driver tidy. No IO, no cutover.
        connectionEffectDriver.stop()
        connectionTmuxPort.setClient(null)
        connectJob?.cancel()
        connectJob = null
        assistant.dismiss()
        val parkedForBackgroundGrace = maybeParkRuntimeForBackgroundViewModelClear(
            processForeground = processForeground,
        )
        if (!parkedForBackgroundGrace) {
            closeCurrentConnection()
        }
        // Issue #235: remove the application-scoped lifecycle hooks
        // installed during the first attach. The hooks survive across
        // [closeCurrentConnection*] teardown cycles (otherwise the
        // auto-detach on background would unregister the hook before
        // the foreground hook fires the reattach); the VM going away
        // is the only signal we use to drop them.
        unregisterLifecycleHooks()
        // bridgeScope is parented to viewModelScope, so its SupervisorJob
        // tears down automatically when viewModelScope cancels post-super
        // call. Explicit cancellation here is redundant — leaving it to
        // the framework keeps the teardown path single-sourced.
        super.onCleared()
    }

    private fun isProcessForegroundForCleared(): Boolean =
        (processForegroundForClearedOverrideForTest ?: runCatching {
            ProcessLifecycleOwner.get()
                .lifecycle
                .currentState
                .isAtLeast(Lifecycle.State.STARTED)
        }.getOrDefault(appActive)) && screenStartedForCleared

    private fun maybeParkRuntimeForBackgroundViewModelClear(
        processForeground: Boolean,
    ): Boolean {
        if (processForeground) return false
        if (inlineConnectionStatus !is ConnectionStatus.Connected) return false
        val target = activeTarget ?: return false
        val client = clientRef ?: return false
        if (client.disconnected.value) return false
        if (sessionRef?.isConnected == false) return false
        Log.i(
            ISSUE_235_LIFECYCLE_TAG,
            "tmux-viewmodel-cleared-park-runtime " + targetLogFields(target),
        )
        closeCachedRuntimesBlocking(deactivateCurrentRuntimeToCache())
        return true
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
        cacheEviction: RuntimeCacheEviction = RuntimeCacheEviction.HostWide,
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
        passiveDisconnectGraceJob?.cancelAndJoin()
        passiveDisconnectGraceJob = null
        foregroundRuntimeProbeJob?.cancelAndJoin()
        foregroundRuntimeProbeJob = null
        eventsJob?.cancelAndJoin()
        eventsJob = null
        outputOverflowJob?.cancelAndJoin()
        outputOverflowJob = null
        disconnectedJob?.cancelAndJoin()
        disconnectedJob = null
        projectRootsJob?.cancelAndJoin()
        projectRootsJob = null
        _projectRoots.value = emptyList()
        val producerJobsToJoin = paneProducerJobs.values.toList()
        val outputActivityJobsToJoin = paneOutputActivityJobs.values.toList()
        val agentJobsToJoin = paneAgentJobs.values.toList()
        val inputJobsToJoin = paneInputJobs.values.toList()
        for (job in producerJobsToJoin) job.cancelAndJoin()
        for (job in outputActivityJobsToJoin) job.cancelAndJoin()
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
        paneOutputActivityJobs.clear()
        for ((_, row) in paneRows) {
            runCatching { row.terminalState.detachExternalProducer() }
        }
        paneRows.clear()
        _panes.value = emptyList()
        rebuildUnifiedPanes()
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
        unregisterCurrentClient()
        when (cacheEviction) {
            RuntimeCacheEviction.HostWide -> {
                closingHostId?.let { hostId ->
                    runtimeCache.removeHost(hostId).forEach { cached ->
                        cached.closeCachedRuntime()
                    }
                }
            }
            is RuntimeCacheEviction.TargetRuntime -> {
                runtimeCache.remove(cacheEviction.key)?.closeCachedRuntime()
            }
        }
        releaseCurrentLeaseOrCloseRawSession()
        sessionRef = null
        leaseRef = null
        activeTarget = null
        connectingTarget = connectingTarget?.takeIf { current ->
            preserveConnectingTarget?.let { sameSessionIdentity(current, it) } == true
        }
        // Issue #628: clear previous-session toggle state on full teardown
        // (detach, host change, reconnect failure).
        _previousSessionName.value = null
        refreshReconnectAvailability()
        activeAttachMilestone = null
        // A fresh attach must capture a fresh phone grid and re-run the
        // size-mismatch check.
        remoteColumns = 0
        remoteRows = 0
        resetControlClientSizeForAttach()
    }

    private sealed interface RuntimeCacheEviction {
        data object HostWide : RuntimeCacheEviction
        data class TargetRuntime(val key: TmuxRuntimeKey) : RuntimeCacheEviction
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
        outputOverflowJob?.cancelAndJoin()
        outputOverflowJob = null
        passiveDisconnectGraceJob?.cancelAndJoin()
        passiveDisconnectGraceJob = null
        disconnectedJob?.cancelAndJoin()
        disconnectedJob = null
        projectRootsJob?.cancelAndJoin()
        projectRootsJob = null
        _projectRoots.value = emptyList()
        val producerJobsToJoin = paneProducerJobs.values.toList()
        val outputActivityJobsToJoin = paneOutputActivityJobs.values.toList()
        val agentJobsToJoin = paneAgentJobs.values.toList()
        val inputJobsToJoin = paneInputJobs.values.toList()
        for (job in producerJobsToJoin) job.cancelAndJoin()
        for (job in outputActivityJobsToJoin) job.cancelAndJoin()
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
        paneOutputActivityJobs.clear()
        for ((_, row) in paneRows) {
            runCatching { row.terminalState.detachExternalProducer() }
        }
        paneRows.clear()
        _panes.value = emptyList()
        rebuildUnifiedPanes()
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
        unregisterCurrentClient()
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
        passiveDisconnectGraceJob?.cancel()
        passiveDisconnectGraceJob = null
        foregroundRuntimeProbeJob?.cancel()
        foregroundRuntimeProbeJob = null
        eventsJob?.cancel()
        eventsJob = null
        outputOverflowJob?.cancel()
        outputOverflowJob = null
        disconnectedJob?.cancel()
        disconnectedJob = null
        projectRootsJob?.cancel()
        projectRootsJob = null
        _projectRoots.value = emptyList()
        activeAttachMilestone = null
        for ((_, job) in paneProducerJobs) {
            job.cancel()
        }
        for ((_, job) in paneOutputActivityJobs) {
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
        paneOutputActivityJobs.clear()
        for ((_, row) in paneRows) {
            runCatching { row.terminalState.detachExternalProducer() }
        }
        paneRows.clear()
        _panes.value = emptyList()
        rebuildUnifiedPanes()
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
        unregisterCurrentClient()
        closingHostId?.let { hostId ->
            closeCachedRuntimesBlocking(runtimeCache.removeHost(hostId))
        }
        releaseCurrentLeaseOrCloseRawSessionBlocking()
        sessionRef = null
        leaseRef = null
        activeTarget = null
        connectingTarget = null
        // Issue #628: clear previous-session toggle state on full teardown
        // (detach, host change, VM cleared). The toggle is only meaningful
        // while a session is alive.
        _previousSessionName.value = null
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

    // ---- Issue #626: unified pane list helpers ----

    /**
     * Rebuild the unified pane list from the active session's panes plus
     * any cached (warm-switched) session panes on the same host.
     * Called whenever [_panes] changes or when a runtime is cached/evicted.
     */
    private fun rebuildUnifiedPanes() {
        val activePanes = _panes.value
        val active = activeTarget ?: run {
            _unifiedPanes.value = activePanes
            return
        }
        val hostId = active.hostId
        val cached = runtimeCache.cachedRuntimesForHost(hostId)
        if (cached.isEmpty()) {
            _unifiedPanes.value = activePanes
            return
        }
        // Build the unified list: active session first, then cached sessions
        // in cache-insertion order (most recent last, which matches the LRU
        // order of the LinkedHashMap backing the cache).
        //
        // Issue #681: dedup defensively. Two failure modes produced a phantom
        // extra pager page that, when settled on, mis-routed to a random
        // session:
        //  1. A key-DRIFTED twin of the ACTIVE session survives in the cache
        //     (activate() removes only the exact TmuxRuntimeKey match, while
        //     this session was parked under a slightly different key). Its
        //     panes are the SAME session the user is already on, so appending
        //     them creates a duplicate page. Exclude any cached runtime that
        //     resolves to the active session's identity (hostId + sessionName),
        //     not by full key — that is precisely what lets the twin slip past.
        //  2. The same paneId appearing in more than one cached runtime (or in
        //     both the active list and a cached one) — dedup by paneId so each
        //     real window/pane contributes exactly one page.
        val activeSessionName = active.sessionName
        val seenPaneIds = mutableSetOf<String>()
        val allPanes = mutableListOf<TmuxPaneState>()
        for (pane in activePanes) {
            if (seenPaneIds.add(pane.paneId)) {
                allPanes.add(pane)
            }
        }
        for (runtime in cached) {
            // Skip the drifted twin of the session we're already showing.
            if (runtime.key.sessionName == activeSessionName) continue
            for (pane in runtime.panes) {
                if (seenPaneIds.add(pane.paneId)) {
                    allPanes.add(pane)
                }
            }
        }
        _unifiedPanes.value = allPanes
    }

    /**
     * Derive the tmux session name for a pane in the unified list.
     * For the active session's panes, returns [activeTarget.sessionName].
     * For cached-session panes, looks up the cache entry by matching the
     * pane's sessionId against cached runtime keys.
     */
    internal fun sessionNameForUnifiedPane(pane: TmuxPaneState): String? {
        val active = activeTarget ?: return null
        // Active session panes all share the same sessionId from tmux
        if (_panes.value.any { it.paneId == pane.paneId }) {
            return active.sessionName
        }
        // Look up cached runtime by matching pane sessionId
        return cachedSessionNameForPane(pane)
    }

    /**
     * Look up the session name of a cached runtime that owns the given pane.
     */
    private fun cachedSessionNameForPane(pane: TmuxPaneState): String? {
        val active = activeTarget ?: return null
        val activeSessionName = active.sessionName
        return runtimeCache.cachedRuntimesForHost(active.hostId)
            // Issue #681: never resolve a pane to a key-drifted TWIN of the
            // ACTIVE session — that would emit a sessionSwitchRequest for a
            // session the user is already on (and, paired with the phantom
            // page, route to a foreign session). The active session's panes
            // are already handled by [sessionNameForUnifiedPane] before this
            // is reached; here we only resolve genuinely cached OTHER sessions.
            .filterNot { it.key.sessionName == activeSessionName }
            .firstOrNull { it.panes.any { p -> p.paneId == pane.paneId } }
            ?.key?.sessionName
    }

    /**
     * Check whether a pane in the unified list belongs to the active session.
     */
    internal fun isActiveSessionPane(pane: TmuxPaneState): Boolean {
        return _panes.value.any { it.paneId == pane.paneId }
    }

    // ---- End Issue #626 helpers ----

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
        val hasWindowIndex = parts.getOrNull(2)?.startsWith("$") == false
        val windowIndex = if (hasWindowIndex) parts[2].trim().toIntOrNull() else null
        val sessionIdIndex = if (hasWindowIndex) 3 else 2
        val sessionId = parts.getOrNull(sessionIdIndex)?.takeIf { it.startsWith("$") } ?: return null
        val hasSessionName = parts.size >= sessionIdIndex + 4
        val sessionNameIndex = sessionIdIndex + 1
        val titleIndex = if (hasSessionName) sessionIdIndex + 2 else sessionIdIndex + 1
        val paneIndexIndex = if (hasSessionName) sessionIdIndex + 3 else sessionIdIndex + 2
        val sessionName = if (hasSessionName) parts[sessionNameIndex] else ""
        val title = parts.getOrNull(titleIndex).orEmpty()
        val paneIndex = parts.getOrNull(paneIndexIndex)?.trim()?.toIntOrNull() ?: 0
        return ParsedPane(
            paneId = paneId,
            windowId = windowId,
            windowIndex = windowIndex,
            sessionId = sessionId,
            title = title,
            paneIndex = paneIndex,
            cwd = parts.getOrNull(paneIndexIndex + 1).orEmpty(),
            currentCommand = parts.getOrNull(paneIndexIndex + 2).orEmpty(),
            // Issue #186: `#{pane_tty}` is the 9th (or 8th, without
            // session_name) field in the format string above. Older
            // tmux versions that omit the field simply return empty,
            // in which case per-pane agent detection skips this pane
            // (see [detectForPane]) rather than fall back to a
            // host-wide scan.
            paneTty = parts.getOrNull(paneIndexIndex + 3).orEmpty(),
            inCopyMode = parseTmuxBoolean(parts.getOrNull(paneIndexIndex + 4)),
            sessionName = sessionName,
        )
    }

    private fun parseTmuxBoolean(raw: String?): Boolean =
        raw == "1" || raw.equals("true", ignoreCase = true)

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
        val windowIndex: Int? = null,
        val cwd: String = "",
        val currentCommand: String = "",
        // Issue #186: per-pane TTY captured from `#{pane_tty}` so
        // detection can scope its process scan to a specific pane.
        // Default is empty (older tmux / unit tests that don't care).
        val paneTty: String = "",
        // Issue #550: copied from `#{pane_in_mode}`. Defaults false for
        // older tests / tmux versions that do not expose the format field.
        val inCopyMode: Boolean = false,
        val sessionName: String = "",
    )

    // Issue #722: visibility widened from `private` to `internal` (no behavior
    // change) so it can appear as a property of the now-`internal`
    // [RuntimeRefreshGuard] carried opaquely across the characterization-test
    // seam boundary. Tests never name or construct it.
    internal data class ConnectionTarget(
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

    private data class LifecycleReattachNetworkCoalesce(
        val target: ConnectionTarget,
        val generation: Long,
    )

    private data class PausedAutoReconnect(
        val target: ConnectionTarget,
        val reason: String,
    )

    // Issue #722: visibility widened from `private` to `internal` (no behavior
    // change) so the characterization test seams [currentRuntimeGuardForTest],
    // [reseedBlankVisiblePanesForTest], and [armConnectedBlankWatchdogForTest]
    // can carry an opaque guard across the test boundary.
    internal data class RuntimeRefreshGuard(
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

    /**
     * EPIC #687 slice 1a — VM-internal connection-lifecycle decision seam.
     *
     * The connection-lifecycle DECISIONS used to be inlined at the top of each
     * lifecycle entry point ([onAppBackgrounded], [onAppForegrounded],
     * [onNetworkChanged], [handlePassiveClientDisconnect], and the
     * foreground-runtime-probe gate). This sealed event/decision pair plus the
     * single [reduceConnection] classifier consolidate those branch decisions
     * into ONE place. The vocabulary deliberately mirrors
     * `:shared:core-connection`'s `ConnectionController` (an event-in /
     * decision-out reducer) so slice 1c can swap the [reduceConnection] body for
     * a real `ConnectionController` call WITHOUT rewriting the call sites.
     *
     * This is a PURE classifier: it reads the current VM state and returns the
     * branch the caller must take. It does NOT mutate state, schedule jobs, or
     * touch [_connectionStatus] — the side-effect bodies stay at the call sites
     * (slice 1a reshapes control flow, it does not replace the mechanism). The
     * existing generation counter, grace clocks, and named jobs continue to back
     * those side effects; later slices delete them.
     */
    private sealed interface ConnectionEvent {
        /** Application moved to the background (ProcessLifecycle ON_STOP). */
        data object Background : ConnectionEvent

        /** Application moved to the foreground (ProcessLifecycle ON_START). */
        data object Foreground : ConnectionEvent

        /** A network identity change was observed by the terminal observer. */
        data class NetworkChanged(val change: TerminalNetworkChange) : ConnectionEvent

        /**
         * The active [TmuxClient.disconnected] latch flipped true — the control
         * channel closed under us (clean EOF, sshj exception, or close()).
         */
        data class TransportDropped(val client: TmuxClient) : ConnectionEvent
    }

    /**
     * The branch the lifecycle call site must take. Each variant names the
     * existing side-effect path; the call site dispatches on it and runs the
     * same code it ran inline before. (The richer `ConnectionState` surface that
     * `:shared:core-connection` models — Reattaching/Reconnecting/Gone/etc — is
     * mapped onto the existing [ConnectionStatus] writes inside those bodies,
     * which slice 1a leaves byte-identical.)
     */
    private sealed interface ConnectionDecision {
        /** No lifecycle action — the event is a no-op in the current state. */
        data object Ignore : ConnectionDecision

        // --- Background ---
        /**
         * A reconnect was in flight; pause it until foreground and surface the
         * "paused while backgrounded" [ConnectionStatus.Failed] band.
         */
        data object PauseReconnectForBackground : ConnectionDecision

        /** Detach the live `-CC` control client and stash a pending reattach. */
        data object DetachForBackground : ConnectionDecision

        // --- Foreground ---
        /** Resume an auto-reconnect that was paused while backgrounded. */
        data object ResumePausedReconnect : ConnectionDecision

        /** No pending reattach but a live session — probe the runtime channel. */
        data object ProbeForeground : ConnectionDecision

        /** Replay the [pendingReattach] stashed when we backgrounded. */
        data object ReplayPendingReattach : ConnectionDecision

        // --- NetworkChanged ---
        /** Schedule a proactive reconnect for a real validated network handoff. */
        data object ScheduleNetworkReconnect : ConnectionDecision

        /** Suppress: not a real validated handoff (#548). */
        data object SuppressNetworkNotValidated : ConnectionDecision

        /** Suppress: coalesced with an in-flight lifecycle reattach (#548). */
        data object SuppressNetworkCoalesced : ConnectionDecision

        // --- TransportDropped (passive disconnect) ---
        /**
         * The screen stopped for an in-app navigation to a DIFFERENT session
         * (#630) — skip the pause entirely so it cannot race the new connect.
         */
        data object SkipPassiveInAppNavigation : ConnectionDecision

        /**
         * The screen stopped (app background / explicit leave) — pause the
         * auto-reconnect until foreground rather than racing a grace reattach.
         */
        data object PausePassiveUntilForeground : ConnectionDecision

        /** Foreground passive disconnect — race the within-grace silent reattach. */
        data object SilentReattachWithinGrace : ConnectionDecision
    }

    /**
     * EPIC #687 slice 1a — the single decision entry point. Pure: reads VM state,
     * returns the branch. Slice 1c replaces the body with a `ConnectionController`
     * reduce, keeping these return values as the contract the call sites consume.
     */
    private fun reduceConnection(event: ConnectionEvent): ConnectionDecision =
        when (event) {
            is ConnectionEvent.Background -> reduceBackground()
            is ConnectionEvent.Foreground -> reduceForeground()
            is ConnectionEvent.NetworkChanged -> reduceNetworkChanged(event.change)
            is ConnectionEvent.TransportDropped -> reduceTransportDropped(event.client)
        }

    private fun reduceBackground(): ConnectionDecision {
        if (inlineConnectionStatus is ConnectionStatus.Reconnecting) {
            return ConnectionDecision.PauseReconnectForBackground
        }
        if (activeTarget == null && connectingTarget == null) return ConnectionDecision.Ignore
        if (clientRef == null && sessionRef == null) return ConnectionDecision.Ignore
        return ConnectionDecision.DetachForBackground
    }

    private fun reduceForeground(): ConnectionDecision {
        if (pendingReattach != null) return ConnectionDecision.ReplayPendingReattach
        if (pausedAutoReconnect != null) return ConnectionDecision.ResumePausedReconnect
        if (canProbeCurrentRuntimeOnForeground()) return ConnectionDecision.ProbeForeground
        return ConnectionDecision.Ignore
    }

    /**
     * The pure gate of [probeCurrentRuntimeOnForegroundIfNeeded]: are we in a
     * state where a foreground runtime probe is warranted? Mirrors that
     * function's early returns exactly so the probe behavior is unchanged — only
     * the decision is hoisted into the reducer.
     */
    private fun canProbeCurrentRuntimeOnForeground(): Boolean {
        if (inlineConnectionStatus !is ConnectionStatus.Connected) return false
        if (activeTarget == null) return false
        if (clientRef == null) return false
        if (sessionRef == null) return false
        return true
    }

    private fun reduceNetworkChanged(change: TerminalNetworkChange): ConnectionDecision {
        if (!appActive) return ConnectionDecision.Ignore
        if (autoReconnectJob?.isActive == true) return ConnectionDecision.Ignore
        if (inlineConnectionStatus !is ConnectionStatus.Connected) return ConnectionDecision.Ignore
        val target = activeTarget ?: connectingTarget ?: return ConnectionDecision.Ignore
        if (clientRef == null && sessionRef == null) return ConnectionDecision.Ignore
        val previousValidated = change.previousValidated
        val realValidatedIdentityChange = previousValidated != null &&
            !previousValidated.hasSameNetworkIdentityAs(change.current)
        if (!realValidatedIdentityChange) return ConnectionDecision.SuppressNetworkNotValidated
        val lifecycleCoalesce = lifecycleReattachNetworkCoalesce
        if (
            lifecycleCoalesce != null &&
            lifecycleCoalesce.generation == connectGeneration &&
            sameSessionIdentity(lifecycleCoalesce.target, target)
        ) {
            return ConnectionDecision.SuppressNetworkCoalesced
        }
        return ConnectionDecision.ScheduleNetworkReconnect
    }

    private fun reduceTransportDropped(client: TmuxClient): ConnectionDecision {
        if (inlineConnectionStatus !is ConnectionStatus.Connected) return ConnectionDecision.Ignore
        // Only react if this is still THE active client (a fresh connect may have
        // swapped in a new client whose Connected status this drop must not stomp).
        if (clientRef !== client) return ConnectionDecision.Ignore
        val target = activeTarget ?: connectingTarget
        if (target != null && !screenStartedForCleared) {
            val navigatingToDifferentSession = connectJob?.isActive == true ||
                latestConnectIntent?.let { it.target.sessionName != target.sessionName } == true
            return if (navigatingToDifferentSession) {
                ConnectionDecision.SkipPassiveInAppNavigation
            } else {
                ConnectionDecision.PausePassiveUntilForeground
            }
        }
        return ConnectionDecision.SilentReattachWithinGrace
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

    /**
     * EPIC #687 slice 1b: a VM-internal connection state whose vocabulary
     * mirrors `:shared:core-connection`'s `ConnectionController.ConnectionState`
     * (`Idle/Connecting/Attaching/Live/Backgrounded/Reattaching/Reconnecting/
     * Unreachable/Gone`), so slice 1c can let `ConnectionController.state` drive
     * [_connectionStatus] through the same [connectionStatusFor] mapper instead
     * of being a body-swap rewrite.
     *
     * The view-facing [ConnectionStatus] becomes a pure PROJECTION of this
     * state: every emission sets a [ConnectionState] and [connectionStatusFor]
     * maps it to the exact [ConnectionStatus] the suite pins. To keep slice 1b a
     * ZERO-behavior-change refactor (`ConnectionStatus` byte-identical to before),
     * each state variant carries the full payload its projected [ConnectionStatus]
     * needs. The unused-today variants ([Backgrounded], [Reattaching], [Gone]) are
     * defined for 1c parity with the controller enum but are never emitted yet, so
     * they change no current behavior.
     *
     * The §1 seam-table mapping (controller → status): `Connecting→Connecting`,
     * `Attaching→Switching`, `Live→Connected`, `Reattaching/Reconnecting→
     * Reconnecting`, `Unreachable→Failed`, `Gone→Failed`-equivalent, `Idle→Idle`.
     * Deliberately, slice 1b does NOT simplify the `Failed`/"Tap Reconnect" band
     * (that is the flagged slice-1c MIGRATE with maintainer sign-off); the mapper
     * reproduces today's `Failed`/`Connected`/etc. payloads exactly.
     */
    private sealed interface ConnectionState {
        data object Idle : ConnectionState

        data class Connecting(val host: String, val port: Int, val user: String) :
            ConnectionState

        /** Warm same-host switch / warm open — projects to [ConnectionStatus.Switching]. */
        data class Attaching(val host: String, val port: Int, val user: String) :
            ConnectionState

        /** Attached, input enabled — projects to [ConnectionStatus.Connected]. */
        data class Live(val host: String, val port: Int, val user: String) :
            ConnectionState

        /**
         * Controller-parity state for the backgrounded-but-warm window. Not emitted
         * by the VM today (background detach keeps the prior status), so it is never
         * mapped in practice in slice 1b; defined for 1c. Projects to the same
         * [ConnectionStatus] as [Live] (the only sane projection if it ever flows).
         */
        data class Backgrounded(val host: String, val port: Int, val user: String) :
            ConnectionState

        /**
         * Controller-parity silent in-grace reattach state. Not emitted by the VM
         * today (the within-grace reattach path emits [Reconnecting]); defined for
         * 1c. Projects to [ConnectionStatus.Reconnecting] like [Reconnecting].
         */
        data class Reattaching(
            val host: String,
            val port: Int,
            val user: String,
            val attempt: Int,
            val maxAttempts: Int,
            val retryDelayMs: Long,
            val reason: String,
        ) : ConnectionState

        data class Reconnecting(
            val host: String,
            val port: Int,
            val user: String,
            val attempt: Int,
            val maxAttempts: Int,
            val retryDelayMs: Long,
            val reason: String,
        ) : ConnectionState

        /**
         * Controller-parity "target session deleted elsewhere" state (#666). Not
         * emitted as a distinct status by the VM today; defined for 1c. Projects to
         * [ConnectionStatus.Failed] (the `Gone→Failed`-equivalent §1 mapping).
         */
        data class Gone(val message: String) : ConnectionState

        /** The honest error band — projects to [ConnectionStatus.Failed]. */
        data class Unreachable(val message: String) : ConnectionState
    }

    /**
     * EPIC #687 slice 1b: the PURE `ConnectionState → ConnectionStatus` projection
     * from the §1 seam-reconciliation table. Reproduces every currently-pinned
     * [ConnectionStatus] value BYTE-IDENTICALLY — the state variants carry the
     * exact payloads, so this is a 1:1 facade map with no behavior change. Slice 1c
     * will reuse this mapper to project `ConnectionController.state`.
     */
    private fun connectionStatusFor(state: ConnectionState): ConnectionStatus =
        when (state) {
            is ConnectionState.Idle -> ConnectionStatus.Idle
            is ConnectionState.Connecting ->
                ConnectionStatus.Connecting(state.host, state.port, state.user)
            is ConnectionState.Attaching ->
                ConnectionStatus.Switching(state.host, state.port, state.user)
            is ConnectionState.Live ->
                ConnectionStatus.Connected(state.host, state.port, state.user)
            is ConnectionState.Backgrounded ->
                ConnectionStatus.Connected(state.host, state.port, state.user)
            is ConnectionState.Reattaching ->
                ConnectionStatus.Reconnecting(
                    host = state.host,
                    port = state.port,
                    user = state.user,
                    attempt = state.attempt,
                    maxAttempts = state.maxAttempts,
                    retryDelayMs = state.retryDelayMs,
                    reason = state.reason,
                )
            is ConnectionState.Reconnecting ->
                ConnectionStatus.Reconnecting(
                    host = state.host,
                    port = state.port,
                    user = state.user,
                    attempt = state.attempt,
                    maxAttempts = state.maxAttempts,
                    retryDelayMs = state.retryDelayMs,
                    reason = state.reason,
                )
            is ConnectionState.Gone -> ConnectionStatus.Failed(state.message)
            is ConnectionState.Unreachable -> ConnectionStatus.Failed(state.message)
        }

    /**
     * EPIC #687 slice 1c-iv-a (THE STATUS FLIP): project the SHADOW
     * [ConnectionController]'s core [com.pocketshell.core.connection.ConnectionState]
     * onto the view-facing [ConnectionStatus] — the SINGLE source of the displayed
     * status. The coarse SHAPE comes from the controller; the display PAYLOAD
     * (host/port/user, reconnect attempt/reason, the failure message) is read from
     * the inline [inlineState] the VM just constructed for this transition, because
     * the controller's core state carries opaque [HostKey]/[SessionId], not the
     * host/port/user/attempt the view needs.
     *
     * §1 seam table: `Connecting→Connecting`, `Attaching→Switching`, `Live→Connected`,
     * `Backgrounded→Connected`, `Reattaching/Reconnecting→Reconnecting`,
     * `Gone/Unreachable→Failed`. The two approved #685 divergences are intrinsic to
     * the controller and surface here as the calmer status: a recoverable drop reads
     * `Reconnecting` (the controller is `Reattaching`/`Reconnecting`, not the inline
     * `Unreachable`), and a within-grace foreground reads `Connected` (the controller
     * stays `Live`). #720: a controller `Unreachable` projects to a
     * [ConnectionStatus.Failed] rendered by the calm tappable "Tap to reconnect"
     * [FailedConnectionRow] band — never raw `TransportException`/SSH text and never
     * "open the session again".
     */
    private fun connectionStatusForController(
        controllerState: com.pocketshell.core.connection.ConnectionState,
        inlineState: ConnectionState,
    ): ConnectionStatus =
        // The view-facing projection is the PURE seam
        // [ConnectionStatusProjection.project] (extracted for #728 and pinned by
        // `ConnectionStatusProjectionTest`). The VM supplies the seam's two pure
        // inputs that need VM context: the INLINE-projected display payload
        // (`connectionStatusFor(inlineState)`, a 1:1 map of the inline transition)
        // and the host/port/user the view renders (`hostPortUserFor`, which can fall
        // back to the active/connecting target for a payload-less terminal/idle case).
        // Everything else — the controller→status SHAPE truth-table and the two
        // approved #685 divergences — lives in the seam, byte-identical to the old
        // inline body.
        ConnectionStatusProjection.project(
            controllerState = controllerState,
            inlineStatus = connectionStatusFor(inlineState),
            hpu = hostPortUserFor(inlineState),
        )

    /** Best-effort host/port/user for the view, from the inline transition payload
     *  (the controller's core state does not carry it). Falls back to the active /
     *  connecting target, then to blanks for a payload-less Idle/Gone. */
    private fun hostPortUserFor(inlineState: ConnectionState): ConnectionStatusProjection.HostPortUser =
        when (inlineState) {
            is ConnectionState.Connecting ->
                ConnectionStatusProjection.HostPortUser(inlineState.host, inlineState.port, inlineState.user)
            is ConnectionState.Attaching ->
                ConnectionStatusProjection.HostPortUser(inlineState.host, inlineState.port, inlineState.user)
            is ConnectionState.Live ->
                ConnectionStatusProjection.HostPortUser(inlineState.host, inlineState.port, inlineState.user)
            is ConnectionState.Backgrounded ->
                ConnectionStatusProjection.HostPortUser(inlineState.host, inlineState.port, inlineState.user)
            is ConnectionState.Reattaching ->
                ConnectionStatusProjection.HostPortUser(inlineState.host, inlineState.port, inlineState.user)
            is ConnectionState.Reconnecting ->
                ConnectionStatusProjection.HostPortUser(inlineState.host, inlineState.port, inlineState.user)
            is ConnectionState.Idle,
            is ConnectionState.Gone,
            is ConnectionState.Unreachable -> {
                val target = activeTarget ?: connectingTarget
                if (target != null) {
                    ConnectionStatusProjection.HostPortUser(target.host, target.port, target.user)
                } else {
                    ConnectionStatusProjection.HostPortUser("", 0, "")
                }
            }
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
 * Issue #640: scrollback budget for the seed capture. The seed replays up to
 * this many lines of history so the freshly-attached emulator matches tmux's
 * current frame plus a little scrollback; widening it would only make the first
 * paint slower (per the #640 diagnosis), so it stays at the prior value.
 */
internal const val SEED_SCROLLBACK_LINES: Int = 200

/**
 * Issue #662: how long the post-reveal black-pane safety net waits before
 * deciding a visible pane is genuinely blank and re-seeding it. The wait lets
 * the first post-reveal Compose layout report the phone grid and the
 * control-client resize round-trip settle, so the re-seed captures tmux's
 * REFLOWED frame rather than the pre-resize one (and so a pane that paints from
 * a late `%output` within this window is never needlessly re-captured). Kept
 * short so a genuinely-black pane heals within a blink on a live connection.
 */
internal const val BLANK_PANE_RESEED_DELAY_MS: Long = 350L

/**
 * Issue #693/#662: how many times [seedPaneFromCapture] re-issues a
 * `capture-pane` when it comes back empty/error/null on a flaky link before
 * giving up for this pass. A transiently-empty capture on a degraded-but-
 * connected channel is the root of the green-dot-but-black-pane symptom; a few
 * cheap retries land the frame the next time the link recovers. The bound keeps
 * a genuinely-empty pane (a truly blank shell) from looping. Total seed attempts
 * = this value (the first attempt + this-minus-one retries).
 */
internal const val SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS: Int = 4

/**
 * Issue #693/#662: backoff between empty-capture re-tries inside
 * [seedPaneFromCapture]. Short so a flaky-link empty capture re-tries promptly
 * without stalling a genuinely-empty pane's reveal.
 */
internal const val SEED_CAPTURE_EMPTY_RETRY_DELAY_MS: Long = 120L

/**
 * Issue #693/#661: the never-reveal-black guard polls the active (visible) pane
 * for a non-blank seed before flipping to Connected. This is how many seed
 * retries it makes when the active pane is still blank at reveal time, so a
 * degraded switch shows the calm "Attaching…" loading state instead of a black
 * Connected pane.
 */
internal const val ACTIVE_PANE_REVEAL_SEED_ATTEMPTS: Int = 5

/**
 * Issue #693/#661: backoff between active-pane reveal-gate seed retries.
 */
internal const val ACTIVE_PANE_REVEAL_SEED_DELAY_MS: Long = 150L

/**
 * Issue #693: the never-black-while-connected watchdog re-checks the visible
 * pane this often after a reveal that had to fall through with a still-blank
 * active pane (e.g. the link stayed degraded past the reveal gate). Each tick
 * re-seeds any blank visible pane; once a frame lands the loading overlay is
 * cleared. Bounded by [CONNECTED_BLANK_WATCHDOG_MAX_TICKS].
 */
internal const val CONNECTED_BLANK_WATCHDOG_TICK_MS: Long = 500L

/**
 * Issue #693: upper bound on the never-black-while-connected watchdog ticks so
 * a genuinely-dead channel does not spin forever; the auto-reconnect path takes
 * over once the channel actually drops.
 */
internal const val CONNECTED_BLANK_WATCHDOG_MAX_TICKS: Int = 20

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
 * Issue #552 / #685 (Bug A): a passive tmux reader EOF during a brief foreground
 * network starvation is not enough proof that the user should see a disconnect
 * band. Hold the visible Connected frame while we try a silent same-SSH control
 * client reattach. A sustained outage still falls through to the existing
 * auto-reconnect path after this bounded foreground-only window.
 *
 * #685 ROOT CAUSE of the "lots of reconnects on stable Wi-Fi" complaint: there
 * used to be THREE disagreeing grace clocks — the SSH lease's 60s idle TTL
 * ([SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS]), sshj's 15s×4 keepalive (=60s),
 * and a stray 8s VM grace here. The 8s VM clock tore the held Connected frame
 * down at 8s, so a sub-minute background (or a brief blip on otherwise-stable
 * Wi-Fi) surfaced a needless reconnect even though the lease/keepalive would
 * have held the same transport live for a full 60s. We collapse to ONE
 * lease-anchored 60s window: the VM grace now DEFERS to the same 60s the lease
 * owns, so a background→foreground (or a transient blip) within 60s reattaches
 * with ZERO reconnect, and the death verdict for anything longer is left to the
 * single 60s lease/keepalive oracle. Keep this value in lockstep with
 * [SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS] — do NOT reintroduce a divergent
 * shorter VM clock.
 */
internal val PASSIVE_DISCONNECT_GRACE_MS: Long = SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS
internal const val PASSIVE_DISCONNECT_SILENT_REATTACH_TIMEOUT_MS: Long = 5_000L
internal const val PASSIVE_DISCONNECT_SILENT_REATTACH_RETRY_MS: Long = 250L

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
internal const val SEND_SESSION_WAIT_TIMEOUT_MS: Long = 30_000L
internal const val SEND_SESSION_WAIT_POLL_MS: Long = 100L
internal const val RUNTIME_HEALTH_PROBE_TIMEOUT_MS: Long = 750L

/**
 * Issue #635 / #636 (Slice 1): short delay between the first foreground
 * health probe and a single retry when the first probe *times out* on a
 * still-`isConnected` transport. Gives a slow/recovering link a moment to
 * answer before we ride it through. A retry timeout is still NOT death — the
 * within-grace resume keeps the live session and defers the death verdict to
 * sshj's 60s keepalive oracle.
 */
internal const val RUNTIME_HEALTH_PROBE_RETRY_DELAY_MS: Long = 250L
internal const val CACHED_RUNTIME_HEALTH_PROBE_TIMEOUT_MS: Long = RUNTIME_HEALTH_PROBE_TIMEOUT_MS

/**
 * Classification of a foreground tmux control-channel health probe.
 *
 * Issue #635 / #636 (Slice 1): the within-grace foreground resume must
 * distinguish a transient probe [TIMEOUT] (slow/recovering link — ride it
 * through, no reconnect) from a confirmed-dead transport ([DISCONNECTED] /
 * [NOT_CONNECTED]) or an explicit protocol/IO [ERROR] (genuine failure —
 * reconnect). The [failReason] tag is emitted on the `tmux_probe_result`
 * reconnect-cause trail so field logs can tell case-2 (timeout on a
 * recovering link) apart from case-3 (genuinely dead).
 */
internal enum class RuntimeHealthVerdict(val failReason: String) {
    HEALTHY("none"),
    DISCONNECTED("disconnected"),
    NOT_CONNECTED("not_connected"),
    TIMEOUT("timeout"),
    ERROR("error"),
}
internal const val CACHED_RUNTIME_REMOTE_REFRESH_DELAY_MS: Long = 1L
internal const val TMUX_SESSION_PREWARM_MAX_TARGETS: Int = 2
internal const val LIST_PANES_FIELD_SEPARATOR: String = "|PS|"
internal const val TMUX_INPUT_MAX_PENDING_BYTES: Int = 64 * 1024
internal const val TMUX_INPUT_MAX_BATCH_BYTES: Int = 4 * 1024
internal const val TMUX_INPUT_CHUNK_BYTES: Int = 512
internal const val TMUX_PASTE_BODY_CHUNK_BYTES: Int = BracketedPaste.BODY_CHUNK_BYTES
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

private val DEFAULT_AUTO_RECONNECT_DELAYS_MS: List<Long> = listOf(0L, 1_000L, 2_000L, 5_000L)

/**
 * Issue #621 / #634 / #636 (Slice 4): how many consecutive transparent
 * stale-lease re-dials an open/switch attach may trigger before concluding the
 * host is genuinely dead and surfacing the manual Reconnect affordance. A
 * silently-dead warm lease normally heals on the FIRST fresh transport, so a
 * small cap is enough; it exists only to stop a permanently-broken host from
 * looping forever (each fresh transport also EOFing).
 */
private const val STALE_LEASE_AUTO_RECOVER_MAX: Int = 2

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

private val TmuxConnectTrigger.isReconnectTrigger: Boolean
    get() = when (this) {
        TmuxConnectTrigger.Reconnect,
        TmuxConnectTrigger.AutoReconnect,
        TmuxConnectTrigger.NetworkReconnect,
        TmuxConnectTrigger.LifecycleReattach,
        -> true
        TmuxConnectTrigger.UserTap,
        TmuxConnectTrigger.ColdRestore,
        TmuxConnectTrigger.FastSwitch,
        -> false
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
 * Issue #625: how long [TmuxSessionViewModel.newWindow] waits for tmux's
 * `%window-add` notification before falling back to a best-effort reconcile
 * without emitting a switch request. 2s mirrors [KILL_WINDOW_EVENT_WAIT_MS]
 * — tmux's actual window-creation latency on a healthy server is sub-100ms.
 */
internal const val NEW_WINDOW_EVENT_WAIT_MS: Long = 2_000L

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
