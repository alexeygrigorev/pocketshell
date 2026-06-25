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
import com.pocketshell.app.cards.SessionCardsRemoteSource
import com.pocketshell.app.conversation.ConversationDiagnostics
import com.pocketshell.app.crash.CrashReporter
import com.pocketshell.app.composer.LocalAttachmentSidecarRef
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
import com.pocketshell.app.projects.ManualKindWriter
import com.pocketshell.app.projects.ProfilesResult
import com.pocketshell.app.projects.RemoteProfile
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.model.sessionAgentKindFromOption
import com.pocketshell.app.share.FilenameSanitiser
import com.pocketshell.app.share.ShareUploader
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.ConversationEventsWindow
import com.pocketshell.app.session.ConversationLoadState
import com.pocketshell.app.session.DEFAULT_RETRY_MESSAGE_BUDGET
import com.pocketshell.app.session.FIRST_PAINT_MESSAGE_BUDGET
import com.pocketshell.app.session.OLDER_PAGE_GROWTH_FACTOR
import com.pocketshell.app.session.OPTIMISTIC_USER_MESSAGE_ID_PREFIX
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.session.canRetryAgentStream
import com.pocketshell.app.session.markOptimisticFailed
import com.pocketshell.app.session.reconcileAgentEvents
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.app.tmux.connection.ConnectionManager
import com.pocketshell.app.tmux.connection.ConnectionEffectDriver
import com.pocketshell.app.tmux.connection.ConnectionStatusProjection
import com.pocketshell.app.tmux.connection.CurrentClientTmuxPort
import com.pocketshell.app.tmux.connection.debounceReconnectUi
import com.pocketshell.app.tmux.connection.GraceEffects
import com.pocketshell.app.tmux.connection.SshLeaseTransportPort
import com.pocketshell.app.tmux.connection.TmuxAttachEffects
import com.pocketshell.app.tmux.connection.TransportEffects
import com.pocketshell.app.tmux.connection.hostKeyFor
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionEvent as CoreConnectionEvent
import com.pocketshell.core.connection.ConnectionState as CoreConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.LivenessProbe
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.RevealStateMachine
import com.pocketshell.core.connection.Seed
import com.pocketshell.core.connection.SessionId
import com.pocketshell.core.assistant.AssistantLlmClientFactory
import com.pocketshell.core.storage.dao.HostDao
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
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.terminal.bridge.TerminalSeedGateOverflowException
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.terminal.input.BracketedPaste
import com.pocketshell.core.terminal.ui.TerminalRawInputPolicy
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.LayoutChangeCoalescer
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
import com.pocketshell.core.tmux.TmuxSessionNotFoundException
import com.pocketshell.core.tmux.protocol.ControlEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

internal fun sessionCardsTargetKey(
    hostId: Long,
    host: String,
    port: Int,
    user: String,
    keyPath: String,
    sessionName: String,
): String = buildString {
    append(hostId)
    append('|')
    append(port)
    append('|')
    appendKeyPart(host)
    append('|')
    appendKeyPart(user)
    append('|')
    appendKeyPart(keyPath)
    append('|')
    appendKeyPart(sessionName.trim())
}

private fun StringBuilder.appendKeyPart(value: String) {
    append(value.length)
    append(':')
    append(value)
}

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
    // Issue #898: the in-session kebab "+ New session" now opens the SAME rich
    // SessionTypePickerSheet the host/session-list screen uses. The sheet's
    // Profile selector is populated from the host-discovered agent profiles, so
    // this gateway is injected here exactly as it is in FolderListViewModel.
    // Nullable default keeps the existing unit-test constructors working without
    // the singleton; when absent the picker shows no profile selector (the safe
    // default-only behaviour, identical to the host screen with no gateway).
    private val profilesGateway: com.pocketshell.app.projects.ProfilesGateway? = null,
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
    // Epic #821 slice A2: the host-side one-shot kind guess for FOREIGN
    // sessions (no recorded `@ps_agent_kind`). Defaulted so existing unit-test
    // constructors are unchanged; injectable so a test can stub the guess.
    private val agentKindRemoteSource: com.pocketshell.app.agents.AgentKindRemoteSource =
        com.pocketshell.app.agents.AgentKindRemoteSource(),
    private val sessionCardsRemoteSource: SessionCardsRemoteSource = SessionCardsRemoteSource(),
) : ViewModel() {

    /**
     * Issue #576 (Slice A of #792): the dispatcher the structural pane
     * reconcile (`reconcilePanes`: list-panes + capture-pane round-trips +
     * `_panes`/recompose churn) runs on. Defaults to [Dispatchers.Default] so
     * a Codex `%layout-change` storm no longer head-of-line-blocks the UI
     * thread (the on-device ANR). NOT a constructor parameter so the Hilt
     * `@Inject` graph stays free of a bare `CoroutineDispatcher` binding; a
     * unit test pins it to its virtual-clock scheduler via
     * [setReconcileDispatcherForTest] before attaching a client.
     */
    private var reconcileDispatcher: CoroutineDispatcher = Dispatchers.Default

    @androidx.annotation.VisibleForTesting
    internal fun setReconcileDispatcherForTest(dispatcher: CoroutineDispatcher) {
        reconcileDispatcher = dispatcher
    }

    /**
     * Issue #877: the dispatcher the per-`%output` new-port detector
     * ([startPortDetectionForPane] → [PortDetector.scan]) runs its decode +
     * regex scan on. Defaults to a single-threaded view of [Dispatchers.Default]
     * (`limitedParallelism(1)`) so the UTF-8 decode
     * and the 7-regex `PortDetector.scan` pass over a 4 KB tail no longer
     * execute on the UI thread for every output chunk. An idle agent pane
     * still emits low-rate spinner/status frames, so this collector fired
     * continuously on Main and accumulated into a multi-frame stall (the
     * reported idle-session freeze). The scan is now hopped off-Main; only
     * the small state update for an actually-found port hops back to the
     * [bridgeScope] (Main) via [confirmAndSurfaceDetectedPort]. The detector
     * keeps mutable session state and is "single-threaded by contract", so a
     * SINGLE-threaded background dispatcher preserves that invariant — the
     * one collector serialises its scans on one background thread. NOT a
     * constructor parameter, for the same Hilt-graph reason as
     * [reconcileDispatcher]; a unit test pins it to a tracking/virtual-clock
     * dispatcher via [setPortDetectionDispatcherForTest].
     */
    private var portDetectionDispatcher: CoroutineDispatcher =
        Dispatchers.Default.limitedParallelism(1)

    @androidx.annotation.VisibleForTesting
    internal fun setPortDetectionDispatcherForTest(dispatcher: CoroutineDispatcher) {
        portDetectionDispatcher = dispatcher
    }

    /**
     * Session-card host CLI reads/writes run on real IO in production. Unit
     * tests pin this to the shared scheduler so card refresh assertions do not
     * race a background [Dispatchers.IO] continuation.
     */
    private var sessionCardsDispatcher: CoroutineDispatcher = Dispatchers.IO

    @androidx.annotation.VisibleForTesting
    internal fun setSessionCardsDispatcherForTest(dispatcher: CoroutineDispatcher) {
        sessionCardsDispatcher = dispatcher
    }

    /**
     * Issue #793: how long the Conversation tab stays in the
     * [ConversationLoadState.Loading] ("Loading conversation…") state before
     * the load watchdog flips it to [ConversationLoadState.Failed]. Bounds the
     * spinner so a never-completing first-paint read (e.g. the epic #792
     * transport flap) surfaces a clear terminal state instead of hanging
     * forever. NOT a constructor parameter so the Hilt `@Inject` graph stays
     * free of a bare `Long`; a unit test shrinks it via
     * [setConversationLoadTimeoutForTest] to assert the watchdog deterministically.
     */
    private var conversationLoadTimeoutMs: Long = CONVERSATION_LOAD_TIMEOUT_MS

    @androidx.annotation.VisibleForTesting
    internal fun setConversationLoadTimeoutForTest(timeoutMs: Long) {
        conversationLoadTimeoutMs = timeoutMs
    }

    /**
     * Issue #576 (Slice A of #792): the dispatcher the structural reconcile's
     * pane-state APPLY step runs on. The `list-panes`/`capture-pane` IO runs
     * off-main on [reconcileDispatcher], but the resulting `_panes` /
     * `paneRows` / recompose-driving StateFlow mutation MUST land back on the
     * single UI thread so concurrent reconciles (the off-main coalesced one and
     * the inline attach/refresh ones) can never interleave a torn read/write of
     * the pane state nor lose the last-write of a settled burst. Defaults to
     * [Dispatchers.Main.immediate] — the same thread `bridgeScope`/`_panes`
     * already publish on — so an apply that is already on Main runs inline (no
     * extra dispatch hop) while an apply arriving from the off-main reconcile
     * hops back to Main and serialises behind any in-flight apply. NOT a
     * constructor parameter, for the same Hilt-graph reason as
     * [reconcileDispatcher]; a unit test pins it to its virtual-clock Main via
     * [setReconcileApplyDispatcherForTest].
     */
    private var reconcileApplyDispatcher: CoroutineDispatcher = Dispatchers.Main.immediate

    @androidx.annotation.VisibleForTesting
    internal fun setReconcileApplyDispatcherForTest(dispatcher: CoroutineDispatcher) {
        reconcileApplyDispatcher = dispatcher
    }

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

    /**
     * Issue #869: monotonic millisecond clock used by the submit ack-gate to
     * (a) measure each `capture-pane` round-trip (the RTT addend) and (b) bound
     * the needle-miss FALLBACK floor. Defaults to the device monotonic clock.
     * Unit tests override it to read the `runTest` virtual scheduler time so the
     * RTT measurement is deterministic under virtual time — `SystemClock`
     * reads a constant 0 there, which would make every measured RTT zero and
     * the fallback-floor top-up impossible to assert.
     */
    @Volatile
    private var agentSubmitMonotonicMsForTest: (() -> Long)? = null

    @androidx.annotation.VisibleForTesting
    internal fun setAgentSubmitMonotonicClockForTest(clock: (() -> Long)?) {
        agentSubmitMonotonicMsForTest = clock
    }

    private fun agentSubmitNowMs(): Long =
        agentSubmitMonotonicMsForTest?.invoke() ?: SystemClock.elapsedRealtime()

    /**
     * Issue #869: test override for the submit ack-gate POLL window
     * ([AGENT_SUBMIT_ACK_TIMEOUT_MS]). Production polls the full 2s window, but
     * the needle-miss FALLBACK FLOOR is a SEPARATE invariant (Enter never fires
     * before `FALLBACK_FLOOR + measuredRtt`) that must hold even if the poll
     * window is shorter than the floor. A test sets a SHORT poll window so the
     * floor — not the incidental poll-loop duration — is the binding constraint,
     * proving the floor genuinely gates the Enter (red→green). Null ⇒ production
     * default.
     */
    @Volatile
    private var agentSubmitAckTimeoutMsOverrideForTest: Long? = null

    @androidx.annotation.VisibleForTesting
    internal fun setAgentSubmitAckTimeoutForTest(timeoutMs: Long?) {
        agentSubmitAckTimeoutMsOverrideForTest = timeoutMs
    }

    /**
     * Issue #818: test override for the configured open-time default agent
     * session view. Unit tests build the view model without the
     * [com.pocketshell.app.settings.SettingsRepository] singleton, so this
     * seam lets a test pin which tab a freshly-opened agent session lands on
     * and assert the open-time default (and prove that detection/refresh never
     * yanks an already-open session — the #815 line). Null means "fall back to
     * the repository / built-in default".
     */
    @Volatile
    private var defaultAgentSessionViewOverrideForTest:
        com.pocketshell.app.settings.DefaultAgentSessionView? = null

    @androidx.annotation.VisibleForTesting
    internal fun setDefaultAgentSessionViewForTest(
        view: com.pocketshell.app.settings.DefaultAgentSessionView?,
    ) {
        defaultAgentSessionViewOverrideForTest = view
    }

    /**
     * Issue #818: the tab a freshly-OPENED agent session lands on, resolved
     * from the user's [com.pocketshell.app.settings.AppSettings.defaultAgentSessionView]
     * setting (default Conversation — the black-screen cure). This is read ONLY
     * at open/initial-tab time (the fresh-row branch of [markAgentTailLive] and
     * the presumed-agent open path). It must NEVER be consulted on a
     * detection/refresh of an ALREADY-open session, or it would re-introduce
     * the #815 mid-session yank. A remembered/explicit per-session choice is
     * applied earlier by [seedAgentConversationFromMemory] and still wins.
     */
    private fun openTimeDefaultSessionTab(): SessionTab {
        val configured = defaultAgentSessionViewOverrideForTest
            ?: settingsRepository?.settings?.value?.defaultAgentSessionView
            ?: com.pocketshell.app.settings.AppSettings.DEFAULT_AGENT_SESSION_VIEW
        return when (configured) {
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation ->
                SessionTab.Conversation
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal ->
                SessionTab.Terminal
        }
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

    public data class SessionCardsUiState(
        val sessionName: String? = null,
        val targetKey: String? = null,
        val loading: Boolean = false,
        val feed: SessionCardsRemoteSource.Feed = SessionCardsRemoteSource.Feed.Empty,
    )

    private val _sessionCards: MutableStateFlow<SessionCardsUiState> =
        MutableStateFlow(SessionCardsUiState())
    public val sessionCards: StateFlow<SessionCardsUiState> = _sessionCards.asStateFlow()

    private val _connectionStatus: MutableStateFlow<ConnectionStatus> =
        MutableStateFlow(ConnectionStatus.Idle)

    /**
     * Coarse-grained status the screen surfaces above the terminal.
     *
     * This is the RAW projected status (the VM's reconnect-entry contract; pinned
     * by the VM test-suite). The view does NOT collect this directly for the
     * reconnect band — it collects [displayConnectionStatus], which debounces the
     * sub-1s reconnect flash (issue #876). Keeping this raw flow unchanged means
     * every reconnect-entry/grace test still observes the immediate projected
     * status with no debounce timing skew.
     */
    public val connectionStatus: StateFlow<ConnectionStatus> =
        _connectionStatus.asStateFlow()

    /**
     * Issue #876 — the DISPLAY status the screen's reconnect band collects.
     *
     * A PRESENTATION-LAYER debounce on the OBSERVED status: a momentary drop that
     * self-recovers within [RECONNECT_UI_DEBOUNCE_MS] never surfaces the
     * "Reconnecting" band (no flash); a sustained drop surfaces it exactly as
     * before. This only changes WHEN the UI shows the indicator — it does NOT
     * touch the frozen reconnect/lease/grace core (D28 / epic #792). Steady states
     * (Connecting/Switching/Connected/Failed/Idle) surface immediately. Eager
     * sharing keeps the band state continuous across the screen's
     * subscribe/unsubscribe churn (it re-collects on every recomposition).
     */
    public val displayConnectionStatus: StateFlow<ConnectionStatus> =
        _connectionStatus
            .debounceReconnectUi()
            .stateIn(
                scope = viewModelScope,
                started = SharingStarted.Eagerly,
                initialValue = ConnectionStatus.Idle,
            )

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
     * stub `emptyFlow`/fake-`isWarm` fork for the driver's inputs (D22). The transport IO
     * (lease acquire/evict, re-dial) runs through the [TransportEffects]/[GraceEffects] IO
     * bodies over [sshLeaseManager] directly, so the controller/driver only READ this port's
     * `isWarm` snapshot + `transportEvents` flow; `ensureLease`/`evictStale` on this adapter
     * are never invoked.
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
                    ?: error("no lease target for $hostKey")
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
     * [ConnectionEffectDriver]'s real transport-drop input — not a stub. The driver collects
     * [TmuxPort.disconnected] (to submit TransportDropped); the attach IO runs through the
     * [TmuxAttachEffects] body, so this adapter's IO methods are never invoked.
     */
    private val connectionTmuxPort: CurrentClientTmuxPort =
        CurrentClientTmuxPort(
            activePaneIdFor = { sessionId -> sessionId.value },
            scrollbackLines = SEED_SCROLLBACK_LINES,
        )

    /**
     * EPIC #792 Slice E — the connection FACADE. The single object the VM holds for the whole
     * connect/attach/reattach/grace/lease/reconnect surface: it owns the
     * `:shared:core-connection` [ConnectionController] (the SOLE connection-state authority) and
     * the effect classes the driver dispatches to. Replaces the deleted
     * `ConnectionControllerShadowBridge` — there is no shadow/mirror/dual-write remaining (D28).
     */
    private val connectionManager: ConnectionManager =
        ConnectionManager(transport = connectionTransportPort)

    /**
     * EPIC #792 Slice B — the GRACE IO owner. Consolidates the three background-grace
     * IO triggers (clean background detach, within-grace foreground reseed, within-grace
     * silent heal) into one dispatcher. The VM implements the [GraceEffects.GraceIo]
     * capability ([launchBackgroundDetachTeardown] / [launchForegroundReattachReseed] /
     * [launchForegroundHealWithinGrace]); the effect class owns the dispatch so the
     * driver seam AND the lifecycle entrypoints route through ONE owner — the inline twin
     * invocation is deleted (D22 hard-cut, no dual-write).
     */
    private val graceEffects: GraceEffects =
        GraceEffects(
            object : GraceEffects.GraceIo {
                override fun launchBackgroundDetachTeardown() =
                    this@TmuxSessionViewModel.launchBackgroundDetachTeardown()

                override fun launchForegroundReattachReseed() =
                    this@TmuxSessionViewModel.launchForegroundReattachReseed()

                override fun launchForegroundHealWithinGrace() =
                    this@TmuxSessionViewModel.launchForegroundHealWithinGrace()
            },
        )

    /**
     * EPIC #792 Slice B — the ATTACH / fast-SWITCH IO owner. The SOLE owner of the warm
     * same-host session-switch IO dispatch now the inline `runFastSessionSwitch` call is
     * deleted. The VM implements the [TmuxAttachEffects.TmuxAttachIo] capability (it just
     * runs the supplied thunk — the `runFastSessionSwitch` body closed over the connect()
     * caller's args); the effect class is the single dispatch owner (D22 hard-cut).
     */
    private val tmuxAttachEffects: TmuxAttachEffects =
        TmuxAttachEffects(
            object : TmuxAttachEffects.TmuxAttachIo {
                override suspend fun attach(
                    target: ConnectionTarget,
                    attempt: Int,
                    trigger: TmuxConnectTrigger,
                    startedAtMs: Long,
                ) = runFastSessionSwitch(target, attempt, trigger, startedAtMs)
            },
        )

    /**
     * EPIC #792 Slice C — the RECONNECT-LADDER IO owner (the #822 wedge surface). The SOLE
     * dispatcher of the reconnect IO now the inline `scheduleAutoReconnect` direct calls and
     * `startReconnectForSend` are deleted (D22 hard-cut, single reconnect entrypoint). The VM
     * implements the [TransportEffects.ReconnectIo] capability (it just runs the supplied
     * thunk — the former `scheduleAutoReconnect`/`startReconnectForSend` bodies closed over the
     * caller's args, with the #822 lease-eviction wedge fix inside the auto body). Every former
     * inline reconnect call routes through this one owner — the same entrypoint the future #823
     * pull-to-reconnect affordance (Slice D) will call, never a third writer.
     */
    private val transportEffects: TransportEffects =
        TransportEffects(
            object : TransportEffects.ReconnectIo {
                override fun runAutoReconnectLadder(body: () -> Unit) = body()

                override fun runManualReconnect(): TransportEffects.ManualReconnectResult =
                    TransportEffects.ManualReconnectResult(this@TmuxSessionViewModel.startReconnectForSendBody())
            },
        )

    /**
     * EPIC #792 Slice E: the [ConnectionEffectDriver], wired over the REAL adapters — it
     * observes the AUTHORITATIVE [ConnectionController]'s [ConnectionController.state]
     * transitions, the real [SshLeaseTransportPort.transportEvents] lease edges, and the real
     * [CurrentClientTmuxPort.disconnected] transport-drop oracle, and dispatches each effect to
     * the single-owner effect classes ([GraceEffects]/[TmuxAttachEffects]/[TransportEffects]).
     *
     * The driver OWNS the clean BACKGROUND DETACH: on a transition INTO
     * [ConnectionState.Backgrounded] it fires [backgroundedEffect] (→ the single [GraceEffects]
     * owner → [launchBackgroundDetachTeardown]), which runs the full teardown
     * ([closeCurrentConnectionAndJoin] under `NonCancellable`) — the same job the deleted inline
     * `backgroundDetachJob` trigger launched, with identical timing. The driver holds no
     * business logic itself (the Slice E thin-glue contract). Started once, here, in
     * [viewModelScope].
     */
    private val connectionEffectDriver: ConnectionEffectDriver =
        ConnectionEffectDriver(
            controller = connectionManager.connectionController,
            tmuxPort = connectionTmuxPort,
            transportPort = connectionTransportPort,
            scope = viewModelScope,
            // EPIC #766 Slice 2a: the controller's `-> Backgrounded` edge now DRIVES the
            // full background decision (the re-home of the inline `reduceConnection(Background)`
            // dispatch). [onControllerBackgrounded] selects pause-vs-detach via the
            // inline-equivalent [reduceBackground] predicate (the #685 trap) and, on the
            // detach arm, routes the clean-detach teardown through the single [GraceEffects]
            // owner ([graceEffects.onBackgrounded] -> launchBackgroundDetachTeardown).
            backgroundedEffect = { onControllerBackgrounded() },
            // Slice 1c-iv-c (#754): the driver OWNS the within-grace FOREGROUND reattach.
            // On the controller's Backgrounded→Reattaching edge (within grace + warm
            // lease) it fires the RESEED-ONLY body — re-promote Live + heal blank panes
            // over the still-warm `-CC` lease, NO connect(), NO "Attaching…" overlay.
            // The same body runs directly from `onAppForegrounded(resumedWithinGrace)`
            // in production (the controller does not reach that edge there because the
            // teardown — and thus the controller's Background — is deferred to
            // grace-elapsed); both paths route through the one [GraceEffects] owner.
            // The deleted inline `probeCurrentRuntimeOnForegroundIfNeeded →
            // connect(LifecycleReattach)` path is the superseded behavior (D22 hard-cut).
            // EPIC #792 Slice B: routes through the single [GraceEffects] owner.
            foregroundReattachEffect = { graceEffects.onForegroundReattachReseed() },
            // EPIC #766 Slice 2a: the controller's Backgrounded -> Reconnecting edge (the
            // BEYOND-grace foreground) now DRIVES the foreground arm dispatch (the re-home
            // of the inline `reduceConnection(Foreground)`). [onControllerForegrounded]
            // selects replay-vs-resume via the inline-equivalent [reduceForeground]
            // predicate (pendingReattach / pausedAutoReconnect) — the controller edge is
            // only the TRIGGER, not the divergent display-status gate.
            foregroundReconnectEffect = { onControllerForegrounded() },
            // Slice 1c-iv-b-B2 (#742): after the driver submits a TransportLive /
            // TransportDropped from the real flows, re-project _connectionStatus from
            // the controller's (now real-flow-driven) state — the controller is the
            // single status source, exactly as the deleted mirror feeds re-projected.
            onControllerTransition = { projectStatusFromController() },
            // EPIC #687 P2 (J1/#635): SINGLE GRACE OWNER. Under the NEW path, suppress the
            // driver's TransportDropped submission while the app is BACKGROUNDED — that
            // drop is inside the App-level background-grace window (#450), the SOLE grace
            // authority. Acting on it would collapse the controller to Unreachable while
            // backgrounded, so the next within-grace foreground returns to a disconnect
            // band (the #635 regression). Recovery is deferred to the within-grace
            // foreground heal ([launchForegroundHealWithinGrace]) — the single owner.
            suppressTransportDrops = { shouldSuppressTransportDropsForSingleGraceOwner() },
            // EPIC #766 Slice 2b: the driver now owns the passive transport-drop edge.
            // The typed client stream lets the VM reject a late old-client close BEFORE
            // the driver submits TransportDropped to the controller (#630), while still
            // keeping the submit itself inside the driver-owned path.
            controlChannelDrops = connectionTmuxPort.disconnectedClients,
            shouldSubmitControlChannelDrop = { client ->
                classifyPassiveTransportDrop(client) != ConnectionDecision.Ignore
            },
            controlChannelDroppedEffect = { client -> onControllerTransportDropped(client) },
        ).also { it.start() }

    /**
     * EPIC #792 Slice D (#822/V7a): the proactive mid-session drop detector.
     *
     * While the session is FOREGROUNDED + `Live` (and ONLY then — D21: no
     * background work, no probe while backgrounded/within-grace/reconnecting), it
     * pings the warm control channel every [LivenessProbeTestOverride] interval
     * (default [LivenessProbe.DEFAULT_INTERVAL_MS]). On a SUSTAINED probe failure
     * ([LivenessProbe.DEFAULT_FAILURE_THRESHOLD] consecutive failures) it fires
     * [LivenessProbe.ProbeIo.onProbeFailed] ([onLivenessProbeDeclaredDrop]).
     *
     * That handler drives BOTH ends of the fix without adding a second writer:
     *  - it submits [CoreConnectionEvent.TransportDropped] to the controller so the
     *    connection-lost indicator surfaces immediately (#822 detection), AND
     *  - it drives the SINGLE reconnect entrypoint — `scheduleAutoReconnect` →
     *    Slice C `TransportEffects`, with the #822 force-fresh-lease wedge fix —
     *    whose ladder body closes the dead `-CC` channel, evicts the poisoned warm
     *    lease, and re-dials a FRESH transport, so the SAME session auto-recovers
     *    with no switch dance (#822 wedge). The dead-client teardown happens INSIDE
     *    that ladder body (`closeCurrentConnectionAndJoin`); the probe handler does
     *    NOT call `client.close()` directly.
     *
     * So the probe adds exactly ONE new thing — proactive detection of a
     * silently-dead channel — and reuses the entire tested recovery path. It is
     * NEVER a second reconnect writer (D28). The drop is also submitted to the
     * controller directly here for an immediate indicator independent of the
     * driver's `disconnected` collection latency.
     */
    /**
     * The proactive drop detector. Constructed + started LAZILY on the FIRST client
     * attach ([ensureLivenessProbeStarted]) — NOT in the VM constructor. Lazy
     * construction is deliberate: it reads the [LivenessProbeTestOverride] timing
     * knobs at START time, by which point a connected emulator proof has already set
     * the (short, deterministic) window in `@Before`; building it in the constructor
     * would freeze the production defaults before the test override was installed
     * (and there is no point probing before any session is attached anyway).
     *
     * The probe loop runs in [viewModelScope] (Main): its gate-checks +
     * confirmed-drop effects touch Main-thread VM state (the controller,
     * `setConnectionState`, the per-connection coroutines), so it MUST share the
     * Main dispatcher in production + on the emulator. JVM unit tests
     * (`runTest` + the virtual-clock Main) would otherwise hang — the infinite
     * `delay(interval)` → `probe` → `delay` loop self-reschedules so
     * `advanceUntilIdle()` never idles — so those tests DISABLE the auto-start
     * ([LivenessProbeTestOverride.autoStartEnabled] = false) and drive the
     * detection→recovery path through the explicit seams
     * ([triggerLivenessProbeDropForTest]). The loop's deterministic timing is proven
     * by the pure [LivenessProbe] virtual-clock test + the connected emulator proof.
     */
    @Volatile
    private var livenessProbe: LivenessProbe? = null

    /** Build + start the [livenessProbe] once, on the first client attach. Idempotent. */
    private fun ensureLivenessProbeStarted() {
        if (livenessProbe != null) return
        if (!LivenessProbeTestOverride.autoStartEnabled) return
        val probe = LivenessProbe(
            io = object : LivenessProbe.ProbeIo {
                override fun shouldProbe(): Boolean = shouldRunLivenessProbe()

                override suspend fun probe(): Boolean = runLivenessProbePing()

                override fun onProbeFailed(consecutiveFailures: Int) =
                    onLivenessProbeDeclaredDrop(consecutiveFailures)
            },
            intervalMs = LivenessProbeTestOverride.intervalMs(),
            perProbeTimeoutMs = LivenessProbeTestOverride.perProbeTimeoutMs(),
            failureThreshold = LivenessProbeTestOverride.failureThreshold(),
            log = { line -> Log.i(LIVENESS_PROBE_TAG, line) },
        )
        livenessProbe = probe
        probe.start(viewModelScope)
    }

    /**
     * The probe gate (no-false-positive guard 1): probe ONLY when the session is
     * genuinely FOREGROUNDED + `Live` on the CURRENT, non-disconnected control
     * client. A backgrounded app, an in-grace detach, an in-flight
     * attach/reconnect (controller not `Live`), or no client all return false, so
     * the probe never competes with a reconnect, never trips while backgrounded,
     * and never fights the single grace owner.
     */
    private fun shouldRunLivenessProbe(): Boolean {
        val bg = isProcessBackgroundedForGraceOwner()
        val hasClient = clientRef != null
        val disconnected = clientRef?.disconnected?.value ?: true
        val ctrl = connectionManager.state
        val open = !bg && appActive && hasClient && !disconnected && ctrl is CoreConnectionState.Live
        if (!open) {
            Log.i(
                LIVENESS_PROBE_TAG,
                "gate closed bg=$bg appActive=$appActive hasClient=$hasClient " +
                    "disconnected=$disconnected ctrl=${ctrl::class.simpleName}",
            )
        }
        return open
    }

    /**
     * One liveness ping over the warm control channel. Best-effort + non-fatal:
     * [TmuxClient.probeLiveness] uses the drain-on-timeout path so a slow / busy
     * but HEALTHY channel is never torn down by the probe itself. Returns true if
     * the channel answered. The [LivenessProbe] wraps this in its own generous
     * per-probe timeout and N-consecutive-failure criterion.
     */
    private suspend fun runLivenessProbePing(): Boolean {
        // EPIC #792 Slice D — the per-PR synthetic-drop seam. When the test hook
        // is armed the ping reports DEAD regardless of the real channel, so the
        // silent-drop detection + recovery contract can be exercised
        // DETERMINISTICALLY on the plain `agents:2222` fixture (no toxiproxy
        // needed) — the same lever the toxiproxy half-open proof exercises on the
        // real wire. Production default is false (the real probe runs).
        if (forceLivenessProbeDeadForTest) return false
        val client = clientRef ?: return false
        return runCatching { client.probeLiveness() }.getOrDefault(false)
    }

    /**
     * EPIC #792 Slice D test seam: arm the synthetic silent-drop. While true,
     * [runLivenessProbePing] returns DEAD without touching the wire, so the
     * connected per-PR proof reproduces a silent mid-session drop on the
     * deterministic `agents:2222` fixture (no toxiproxy). The test clears it to
     * let the SAME session recover over the (real, healthy) channel. Production
     * never sets it (default false). `@Volatile` so the probe-loop coroutine sees
     * the flip from the instrumentation thread.
     */
    @Volatile
    internal var forceLivenessProbeDeadForTest: Boolean = false

    /** EPIC #792 Slice D test seam: drive one liveness ping synchronously from a
     *  test (returns alive/dead) so a unit/connected proof can assert the probe
     *  primitive without waiting on the periodic loop. */
    internal suspend fun runLivenessProbePingForTest(): Boolean = runLivenessProbePing()

    /** EPIC #792 Slice D test seam: invoke the confirmed-drop handler directly
     *  (the body the probe loop fires on a sustained failure) so a connected
     *  proof can drive detection→recovery without the wall-clock probe cadence. */
    internal fun triggerLivenessProbeDropForTest(consecutiveFailures: Int = 2) =
        onLivenessProbeDeclaredDrop(consecutiveFailures)

    /** EPIC #792 Slice D test seam: read the gate the probe loop consults. */
    internal fun shouldRunLivenessProbeForTest(): Boolean = shouldRunLivenessProbe()

    /**
     * EPIC #792 #833 test seam: simulate a SUSTAINED CLEAN OUTAGE (toxiproxy
     * `disableProxyFor` analogue) deterministically on the plain `agents:2222`
     * fixture. While armed, the silent-reattach grace loop's reconnect primitives
     * fail-fast as if the link were down (a clean FIN / connection-refused), so the
     * grace loop must KEEP re-dialling a fresh transport across the outage window.
     * Clearing it lets the next grace-loop re-dial succeed over the real healthy
     * channel — proving the SAME session auto-recovers WITHOUT the switch dance once
     * the link returns. Production never sets it (default false). `@Volatile` so the
     * grace-loop coroutine sees the flip from the instrumentation thread.
     */
    @Volatile
    internal var forceCleanOutageForTest: Boolean = false

    /**
     * EPIC #792 #833 test seam: fire the CLEAN passive-disconnect path directly —
     * the body the EOF oracle (`TmuxClient.disconnected` true-edge with a
     * [TmuxDisconnectReason.ReaderEof]) drives. This is the clean-drop analogue of
     * [triggerLivenessProbeDropForTest], letting a connected proof exercise the
     * sustained-clean-outage reattach ladder without waiting on a real reader EOF or
     * the toxiproxy proxy family. Returns false (no-op) if there is no current client.
     */
    internal fun triggerCleanPassiveDropForTest(): Boolean {
        val client = clientRef ?: return false
        if (classifyPassiveTransportDrop(client) == ConnectionDecision.Ignore) return false
        connectionManager.submit(
            CoreConnectionEvent.TransportDropped(reason = "test_clean_outage_seam"),
        )
        projectStatusFromController()
        handlePassiveClientDisconnect(
            client = client,
            disconnectEvent = TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ReaderEof,
                source = "test_clean_outage_seam",
                intent = "synthetic_clean_drop",
            ),
        )
        return true
    }

    /**
     * EPIC #792 #833 test seam: the identity hash of the CURRENT control client (or
     * null). A connected proof reads it before the synthetic clean drop and after
     * recovery to PROVE the resilient grace loop re-dialled a FRESH transport (the
     * recovered client is a different instance), not silently held the dead one.
     */
    internal fun currentClientIdentityForTest(): Int? =
        clientRef?.let { System.identityHashCode(it) }

    /**
     * Issue #895 (switch-while-black) test seam: force the inline connection
     * state to [ConnectionState.Attaching] so a regression test can drive a
     * passive drop while [inlineConnectionStatus] projects to
     * [ConnectionStatus.Switching]. This mirrors the window a same-host fast
     * switch holds (`runFastSessionSwitch`/`fastSwitchSessionForTest` set
     * `Attaching` before the `Live` flip). Used to pin that a drop during the
     * Switching window still surfaces an escapable band (no swallow gate).
     */
    internal fun forceAttachingStateForTest(host: String, port: Int, user: String) {
        setConnectionState(ConnectionState.Attaching(host, port, user))
    }

    /**
     * A sustained drop was confirmed by the probe. Submit `TransportDropped` to
     * the controller for an immediate connection-lost indicator, then drive the
     * SINGLE reconnect entrypoint (`scheduleAutoReconnect` → `TransportEffects`,
     * the Slice C force-fresh-lease ladder) DIRECTLY. The dead `-CC` client is
     * closed INSIDE that ladder body (`closeCurrentConnectionAndJoin`) — this
     * handler does NOT call `client.close()` itself. NEVER a second reconnect
     * writer (D28).
     */
    private fun onLivenessProbeDeclaredDrop(consecutiveFailures: Int) {
        val client = clientRef ?: return
        // Re-check the gate on the VM side too — a background/reconnect could have
        // raced in during the probe window (the controller may already be off Live).
        if (!shouldRunLivenessProbe()) return
        val target = activeTarget ?: connectingTarget
        Log.w(
            LIVENESS_PROBE_TAG,
            "liveness-probe declared silent drop consecutiveFailures=$consecutiveFailures " +
                "session=${target?.sessionName} host=${target?.host} " +
                "clientHash=${System.identityHashCode(client)} generation=$connectGeneration",
        )
        DiagnosticEvents.record(
            "connection",
            "liveness_probe_silent_drop",
            "source" to "liveness_probe",
            "classification" to "proactive_mid_session_drop_detected",
            "consecutiveFailures" to consecutiveFailures,
            "hostId" to target?.hostId,
            "host" to target?.host,
            "port" to target?.port,
            "user" to target?.user,
            "session" to target?.sessionName,
            "clientHash" to System.identityHashCode(client),
            "generation" to connectGeneration,
        )
        ReconnectCauseTrail.record(
            stage = "liveness_probe",
            outcome = "silent_drop_detected",
            cause = "probe_failure_threshold_reached",
            trigger = TmuxConnectTrigger.AutoReconnect.logValue,
            "session" to target?.sessionName,
            "consecutiveFailures" to consecutiveFailures,
            "generation" to connectGeneration,
        )
        // Immediate indicator: walk the controller Live -> Reattaching now, so the
        // connection-lost indicator (header pill / band) surfaces immediately —
        // this is the #822 DETECTION half (V7).
        connectionManager.submit(
            CoreConnectionEvent.TransportDropped(reason = "liveness_probe_silent_drop"),
        )
        projectStatusFromController()
        // RECOVERY half: drive the SINGLE reconnect entrypoint
        // (`scheduleAutoReconnect` → `TransportEffects`, Slice C, with the #822
        // force-fresh-lease wedge fix) DIRECTLY. A probe-confirmed drop is, by the
        // N-consecutive-failure criterion, ALREADY a confirmed sustained outage —
        // NOT a momentary blip — so it routes straight to the RESILIENT
        // auto-reconnect ladder (force-fresh lease + the full retry backoff that
        // rides through a transient outage), rather than the single-shot
        // silent-reattach-then-maybe-escalate path a passive EOF takes (which can
        // exhaust its one attempt inside the outage window and strand the session in
        // Reconnecting — the #822 wedge). The ladder body closes the dead `-CC`
        // channel + evicts the poisoned warm lease + re-dials a FRESH transport,
        // healing the SAME session with no switch dance. This is the SAME single
        // entrypoint #823's affordance calls — NOT a new reconnect writer (D28).
        if (target != null) {
            scheduleAutoReconnect(
                target = target,
                reason = "Liveness probe detected a silently-dead channel; reconnecting.",
                trigger = TmuxConnectTrigger.AutoReconnect,
                diagnosticFields = arrayOf(
                    "source" to "liveness_probe",
                    "consecutiveFailures" to consecutiveFailures,
                ),
            )
        }
    }

    /**
     * EPIC #687 P2 (J1/#635): the SINGLE-GRACE-OWNER suppression predicate the
     * [ConnectionEffectDriver] consults before submitting a `TransportDropped`. True
     * while the app is BACKGROUNDED.
     */
    private fun shouldSuppressTransportDropsForSingleGraceOwner(): Boolean {
        // The app is backgrounded at the PROCESS level (ProcessLifecycle below STARTED).
        // We deliberately do NOT use [appActive] here: within the App-level background
        // grace window the `-CC` connection is held and `onAppBackgrounded()` (which
        // flips `appActive`) is NEVER called, so `appActive` stays true across a
        // within-grace background. The ProcessLifecycle state is the true "is the user
        // looking at the app" signal that flips at `ON_STOP` regardless of grace, so it
        // correctly identifies the within-grace background where a `-CC` drop must be
        // deferred to the single grace owner.
        return isProcessBackgroundedForGraceOwner()
    }

    /**
     * EPIC #687 P2 (J1/#635): true when the app is backgrounded at the PROCESS level
     * ([ProcessLifecycleOwner] below STARTED) — the within-grace-or-beyond background
     * window during which the single grace owner (the App-level grace window) governs
     * connection recovery. Unlike [isProcessForegroundForCleared] this is NOT gated by
     * the screen-started flag: a backgrounded process is backgrounded even if the
     * session screen's `onStop` debounce has not yet fired. Falls back to `!appActive`
     * if the ProcessLifecycle read fails (defensive — never throws into the driver).
     */
    private fun isProcessBackgroundedForGraceOwner(): Boolean {
        // An explicit test override is authoritative.
        processForegroundForClearedOverrideForTest?.let { return !it }
        // ProcessLifecycle's `INITIALIZED` is the never-started JVM/unit-test default —
        // it is NOT a real background. Only a process that was started and has since
        // dropped below STARTED (a genuine `ON_STOP`) counts as backgrounded. In a JVM
        // unit test where `ProcessLifecycleOwner` is never driven, the state is
        // `INITIALIZED`, so this correctly reports "not backgrounded" and the inline
        // screen-stopped paths keep their behavior; on a real device after `ON_STOP`
        // the state is `CREATED` (< STARTED, but past INITIALIZED) → backgrounded.
        val state = runCatching {
            ProcessLifecycleOwner.get().lifecycle.currentState
        }.getOrNull() ?: return !appActive
        if (state == Lifecycle.State.INITIALIZED) return false
        return !state.isAtLeast(Lifecycle.State.STARTED)
    }

    /**
     * EPIC #687 Phase 1 (P1) — the session-screen identity/reveal reducer
     * ([RevealStateMachine]). The rendered screen state is a pure function of the
     * TARGET session id: the screen renders strictly from [revealState], so a
     * late / stale frame from the previous session can NEVER paint (the
     * wrong-session-on-switch fix, #686/#658).
     *
     * It is fed exactly three projection sources, all id-keyed:
     *  1. [RevealStateMachine.navigate] — the nav-route target `(targetId, name)`,
     *     supplied at every `connect()` so the header shows the target name
     *     immediately (and the reducer clears the leaving target's panes).
     *  2. [RevealStateMachine.onConnectionState] — the controller's
     *     [ConnectionController.state] (collected in [driveRevealStateMachine]),
     *     the single connection-lifecycle source.
     *  3. [RevealStateMachine.onSeed] — id-tagged active-pane captures fed at the
     *     EXISTING seed-landing point ([seedPaneFromCaptureOnce]).
     *
     * The machine itself owns NO transport / jobs / leases — only the projection.
     * The view renders strictly from [revealState] (the sole reveal source).
     */
    private val revealStateMachine: RevealStateMachine = RevealStateMachine()

    private val _revealState: MutableStateFlow<RevealState> =
        MutableStateFlow(RevealState.Idle)

    /**
     * EPIC #687 P1: the id-keyed reveal projection the session screen renders from
     * under the NEW connection path. Mirrors [RevealStateMachine.state].
     */
    public val revealState: StateFlow<RevealState> = _revealState.asStateFlow()

    /**
     * EPIC #687 P1: pump the controller's [ConnectionController.state] into the
     * [RevealStateMachine] and mirror the machine's [RevealState] onto
     * [_revealState]. The machine drops any state for a non-current target (the
     * drop-by-id rule), so a late lifecycle event from a superseded switch never
     * moves the reveal. Started once, here, in [viewModelScope].
     */
    private fun driveRevealStateMachine() {
        viewModelScope.launch {
            connectionManager.stateFlow.collect { state ->
                revealStateMachine.onConnectionState(state)
                _revealState.value = revealStateMachine.state.value
            }
        }
    }

    init {
        driveRevealStateMachine()
    }

    /**
     * EPIC #687 P1: announce the nav-route target to the [RevealStateMachine] so
     * the header shows the target name immediately and the leaving target's panes
     * are cleared. Called from [connect] before any coroutine runs, so the reveal
     * supersedes synchronously and the screen can never observe `(old panes, new
     * id)`.
     */
    private fun navigateRevealTo(target: ConnectionTarget) {
        revealStateMachine.navigate(controllerSessionId(target), target.sessionName)
        _revealState.value = revealStateMachine.state.value
    }

    /**
     * EPIC #687 P1: feed an id-tagged active-pane [Seed] to the
     * [RevealStateMachine] at the existing seed-landing point. The frame content
     * is the captured pane transcript; the machine reveals [RevealState.Live] only
     * for the CURRENT target and drops a seed for any superseded target (the AC-3
     * never-paint-a-non-target-seed invariant). [frame] non-empty drives the
     * reveal; empty keeps it loading (never-reveal-black).
     */
    private fun offerRevealSeed(paneId: String, frame: String) {
        val target = activeTarget ?: connectingTarget ?: return
        revealStateMachine.onSeed(
            Seed(targetId = controllerSessionId(target), paneId = paneId, frame = frame),
        )
        _revealState.value = revealStateMachine.state.value
    }

    /**
     * EPIC #687 P1: promote the reveal to [RevealState.Live] for the CURRENT target
     * at the inline "active pane revealed" moments — the points where the inline
     * path clears `_switchHidesTerminal` because the target's own pane is shown.
     * This covers the WARM-CACHE switch (and the fast-switch/cold reveal) where no
     * fresh `capture-pane` re-fires for an already-cached pane, so [offerRevealSeed]
     * alone would never land a seed and the NEW-path reveal gate would stay held
     * (the surface would never mount). Feeds the reveal machine a non-empty seed for
     * the target's active pane id; the machine drops it if the target is no longer
     * current (drop-by-id), so a superseded reveal never promotes the wrong session.
     * The seed FRAME is a non-empty sentinel — the screen renders the VM's own
     * `unifiedPanes` for the target, not the reveal machine's frame, so only the
     * Hold→Reveal gate matters here.
     */
    private fun promoteRevealLiveForActiveTarget() {
        val target = activeTarget ?: connectingTarget ?: return
        val activePaneId = _panes.value.firstOrNull()?.paneId ?: return
        revealStateMachine.onSeed(
            Seed(
                targetId = controllerSessionId(target),
                paneId = activePaneId,
                frame = REVEAL_LIVE_SENTINEL_FRAME,
            ),
        )
        _revealState.value = revealStateMachine.state.value
    }

    /**
     * The opaque [HostKey] / [SessionId] the [connectionManager] controller keys on, derived
     * from the inline transition's active/connecting target. Returns null when there is no
     * target (Idle transition).
     */
    private fun controllerHostAndTarget(): Pair<HostKey?, SessionId?> {
        val target = activeTarget ?: connectingTarget ?: return (null to null)
        return (controllerHostKey(target) to controllerSessionId(target))
    }

    /**
     * EPIC #687 slice 1c-iv-prep: mint the controller's [HostKey] through
     * the SAME [hostKeyFor] encoding the [liveLeaseKeys]-backed warm snapshot uses
     * (`hostKeyFor(leaseKey)`). Previously this path encoded the host as
     * `user@host:port/hostId` while the warm snapshot encoded it as
     * `user@host:port/credentialId/knownHostsId`, so the controller's `isWarm`
     * predicate was ALWAYS FALSE for a genuinely warm host. Routing both sides
     * through [hostKeyFor] off the one [ConnectionTarget.toSshLeaseTarget] lease
     * key aligns the encoding so `isWarm` returns true for a warm host.
     */
    private fun controllerHostKey(target: ConnectionTarget): HostKey =
        hostKeyFor(target.toSshLeaseTarget().leaseKey)

    private fun controllerSessionId(target: ConnectionTarget): SessionId =
        tmuxTargetSessionId(
            hostId = target.hostId,
            sessionName = target.sessionName,
            tmuxSessionId = target.tmuxSessionId,
            sessionCreated = target.sessionCreated,
        )

    /**
     * EPIC #687 slice 1b: the single emission point — set the VM-internal
     * [ConnectionState] and project it to the view-facing [ConnectionStatus] via the
     * pure [connectionStatusFor] mapper. Replaces the scattered direct
     * `_connectionStatus.value = ...` writes so the status is always a projection of
     * an explicit state. Zero behavior change: the projected value is byte-identical
     * to the previous direct assignment.
     *
     * EPIC #792 Slice E: ALSO drives the connection INTENT into the AUTHORITATIVE
     * [connectionManager] (the controller). The controller's state is the SOLE source of
     * the displayed status (no shadow/mirror remains — D28); this choke point is simply the
     * cheapest place to read which host/target the VM is connecting/switching to.
     */
    private fun setConnectionState(state: ConnectionState) {
        _connectionState = state
        // EPIC #687 slice 1c-iv-a (THE STATUS FLIP): the inline path NO LONGER
        // writes [_connectionStatus]. The view-facing status is now projected SOLELY
        // from the [ConnectionController]'s state — the controller is the
        // single source of truth for what the user SEES. The inline EFFECT machinery
        // (reconnect jobs, generation counter, named coroutine jobs, reduceConnection
        // bodies) keeps running UNCHANGED; it just no longer owns the displayed
        // status. Inline effects that need to gate on "am I connected?" read the
        // VM-internal [inlineConnectionStatus] (a pure projection of [_connectionState]
        // — byte-identical to the status this method used to write), NOT the
        // controller-driven [_connectionStatus]. 1c-iv-b hard-cuts the inline
        // [_connectionState]/effect machinery once the controller drives effects too.
        driveControllerIntent(state)
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
     * EPIC #687 slice 1c-iv-a: project the AUTHORITATIVE [ConnectionController]'s state onto
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
            connectionStatusForController(connectionManager.state, inlineState)
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
     * EPIC #687 slice 1c-iii / EPIC #792 Slice A: drive the controller's INTENT
     * directly from the inline transition. Previously this built an inline state NAME
     * string and mirrored it through `observeInlineTransition` (a string round-trip the
     * facade re-derived events from). Now it calls the [connectionManager]'s TYPED intent
     * entrypoints directly — the controller drives the intent state machine. This is NET-NEUTRAL with
     * the deleted string mirror: each inline state maps to the SAME controller event it
     * mirrored before (`Connecting`→Enter, warm `Attaching`→Switch/Enter, `Live`/
     * `Backgrounded`→reveal-Live, `Reattaching`/`Reconnecting`→drop-escalation,
     * `Gone`→TargetGone, `Unreachable`→exhaust-ladder, `Idle`→no-op).
     * Never mutates VM state, never reads the controller state back.
     */
    private fun driveControllerIntent(state: ConnectionState) {
        val (host, target) = controllerHostAndTarget()
        when (state) {
            is ConnectionState.Idle -> Unit // controller stays Idle; nothing to drive.
            is ConnectionState.Connecting ->
                if (host != null && target != null) connectionManager.enter(host, target)
            is ConnectionState.Attaching ->
                if (host != null && target != null) connectionManager.switchTo(host, target)
            // Backgrounded keeps the prior live surface in the inline VM — same as Live
            // (the deleted mirror mapped both to the "Live" reveal branch).
            is ConnectionState.Live,
            is ConnectionState.Backgrounded,
            ->
                if (host != null && target != null) connectionManager.revealLive(host, target)
            // Reattaching and Reconnecting both mirrored to the "Reconnecting"
            // drop-escalation branch.
            is ConnectionState.Reattaching,
            is ConnectionState.Reconnecting,
            ->
                if (host != null && target != null) {
                    connectionManager.escalateReconnecting(host, target)
                }
            is ConnectionState.Gone ->
                if (target != null) connectionManager.markGone(target)
            is ConnectionState.Unreachable -> connectionManager.escalateUnreachable()
        }
    }

    /**
     * EPIC #792 Slice E: feed the AUTHORITATIVE controller the REAL
     * [com.pocketshell.core.connection.ConnectionEvent.SeedLanded] for a pane the write-path
     * just captured. Keyed to the active/connecting target so a seed for the current session
     * promotes it (Attaching/Reattaching → Live); the controller's own drop-by-id check ignores
     * a seed for any other target. After it the displayed status is re-projected.
     */
    private fun feedControllerSeedLanded(paneId: String) {
        val target = activeTarget ?: connectingTarget ?: return
        connectionManager.observeSeedLanded(
            controllerHostKey(target),
            controllerSessionId(target),
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

    // Issue #886: the blank-watchdog tick bound, injectable so a unit test can
    // exhaust it quickly (the stuck-attach-reveal safety net). Defaults to the
    // production constant — production behavior is unchanged.
    private var connectedBlankWatchdogMaxTicks: Int = CONNECTED_BLANK_WATCHDOG_MAX_TICKS

    internal fun setConnectedBlankWatchdogMaxTicksForTest(maxTicks: Int) {
        connectedBlankWatchdogMaxTicks = maxTicks
    }

    // Issue #886: when false, [armConnectedBlankWatchdog] is suppressed entirely.
    // Used ONLY by the watchdog-in-isolation characterization tests that manually
    // arm the watchdog after connect(), so the connect()-auto-armed watchdog does
    // not run a SECOND, concurrent watchdog over the same blank pane during setup.
    // Always true in production.
    private var connectedBlankWatchdogAutoArmEnabled: Boolean = true

    internal fun setConnectedBlankWatchdogAutoArmEnabledForTest(enabled: Boolean) {
        connectedBlankWatchdogAutoArmEnabled = enabled
    }

    /**
     * Issue #640: pane IDs already seeded (via [seedPaneFromCapture]) during
     * the current attach. The cold-open reveal uses this to skip the redundant
     * second full reseed for panes the preload pass already painted, so a fresh
     * connect pays exactly one capture per visible pane and only *reused*
     * (reattach) panes are re-captured. Reset at the start of each attach.
     */
    private val panesSeededThisAttach: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * Issue #830: pane IDs whose attach-time seed `capture-pane` round-trip is
     * currently IN FLIGHT (marked the instant [seedPaneFromCapture] starts, before
     * the wire exchange, and cleared when it returns). [panesSeededThisAttach] only
     * becomes true AFTER the snapshot has been appended — too late to dedup a
     * CONCURRENT reseed net (the pager-settle [reseedVisiblePaneIfBlank] launches a
     * second capture in the window between the preload seed STARTING and its
     * snapshot landing, so both nets read `visibleScreenIsBlank()==true` and each
     * fires a redundant `capture-pane`). This in-flight set closes that race: a net
     * that sees a seed already in flight for the pane skips its own capture and lets
     * the in-flight seed paint the pane. Reset with [panesSeededThisAttach] at the
     * start of each attach.
     */
    private val panesSeedInFlightThisAttach: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap())
    private var autoReconnectDelaysMs: List<Long> = DEFAULT_AUTO_RECONNECT_DELAYS_MS
    private var passiveDisconnectGraceMs: Long = PASSIVE_DISCONNECT_GRACE_MS
    private var silentReattachTimeoutMs: Long = PASSIVE_DISCONNECT_SILENT_REATTACH_TIMEOUT_MS

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

    // Issue #576 (Slice A of #792): collapse a Codex `%layout-change` storm into
    // ~1 off-main reconcile per frame instead of N synchronous main-thread
    // reconciles. Re-created per client bind in [bindClientObservers]; the
    // structural-event branch of the collector offers into it rather than
    // calling `reconcilePanes()` inline.
    @Volatile
    private var layoutChangeCoalescer: LayoutChangeCoalescer? = null
    private var layoutCoalescerScope: CoroutineScope? = null

    private var outputOverflowJob: Job? = null
    private var disconnectedJob: Job? = null
    private var passiveDisconnectGraceJob: Job? = null
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
     * Epic #821 Slice 1: the RECORDED `@ps_agent_kind` of the active session,
     * read fresh from the host (the single source of truth — the tmux user
     * option), NOT a competing cache. `null` means the session carries no
     * recorded kind — i.e. it is FOREIGN / not-yet-classified, so the
     * in-session "classify" UI surfaces it as [SessionAgentKind.Unknown] and
     * offers the picker ("we don't know this session — choose"). When non-null
     * the value drives the "change kind" flow's pre-selection. Refreshed
     * on-demand (when the menu opens / a session connects) via
     * [refreshCurrentSessionRecordedKind] and after a successful
     * [setCurrentSessionKind] write, so it always reflects the host option.
     */
    private val _currentSessionRecordedKind: MutableStateFlow<SessionAgentKind?> =
        MutableStateFlow(null)
    public val currentSessionRecordedKind: StateFlow<SessionAgentKind?> =
        _currentSessionRecordedKind.asStateFlow()

    /**
     * Issue #898: the host-discovered Claude / Codex agent profiles, projected
     * for the in-session "+ New session" [com.pocketshell.app.projects.SessionTypePickerSheet]'s
     * Profile selector. These are the SAME flows [FolderListViewModel] exposes,
     * fetched the same way (over the warm session / a fresh lease) on demand via
     * [fetchProfilesForActiveSession] so the in-session rich sheet is identical
     * to the host-screen one. Empty (no gateway / CLI missing / fetch failure)
     * means the picker shows no profile selector — the safe default-only path.
     */
    private val _claudeProfiles: MutableStateFlow<List<ClaudeProfile>> =
        MutableStateFlow(emptyList())
    public val claudeProfiles: StateFlow<List<ClaudeProfile>> = _claudeProfiles.asStateFlow()
    private val _codexProfiles: MutableStateFlow<List<CodexProfile>> =
        MutableStateFlow(emptyList())
    public val codexProfiles: StateFlow<List<CodexProfile>> = _codexProfiles.asStateFlow()

    /**
     * Issue #894 (epic #821 "Slice C"): the durable per-session CONFIRMED-SHELL
     * verdict — the set of tmux session ids whose recorded `@ps_agent_kind` read
     * back as [SessionAgentKind.Shell]. This is the ONLY trustworthy
     * shell-vs-agent signal at this layer: it comes from the durable host-side
     * record (a session PocketShell launched as a plain shell, or one the user
     * manually classified as Shell), NOT from the unreliable "no agent matched →
     * assume shell" absence the detector returns.
     *
     * Why a separate set and not the conversation-open [sessionRecordedKindCache]:
     * that cache stores [AgentKind] (Claude/Codex/OpenCode only — there is no
     * `AgentKind.Shell`), so a recorded `shell` and a foreign/unknown session
     * both land as a `null` entry there and are indistinguishable. The
     * `@ps_agent_kind` read in [refreshCurrentSessionRecordedKind] resolves to
     * [SessionAgentKind.Shell] and is the one place that CAN tell them apart.
     *
     * Keyed by [TmuxPaneState.sessionId] (the `$N` token, always present),
     * matching the conversation-open cache key. Cleared with the other
     * per-runtime detection caches so a different session never inherits a stale
     * shell verdict.
     */
    private val confirmedShellSessionIds: MutableSet<String> =
        ConcurrentHashMap.newKeySet()

    /**
     * Issue #894: the set of CONFIRMED-SHELL pane ids, derived from
     * [confirmedShellSessionIds] over the current pane rows. Consumed by the
     * screen to source `confirmedShell` per visible pane (it collapses the
     * presumed-agent surface for a pane the tree already knows is a shell) and
     * by [seedPresumedAgentPlaceholder] to skip seeding the #878 "Loading
     * conversation…" placeholder on a genuine shell pane. Republished whenever
     * the pane set changes or a recorded-kind read resolves a shell/non-shell
     * verdict.
     */
    private val _confirmedShellPaneIds: MutableStateFlow<Set<String>> =
        MutableStateFlow(emptySet())
    public val confirmedShellPaneIds: StateFlow<Set<String>> =
        _confirmedShellPaneIds.asStateFlow()

    /**
     * Issue #858: the active session's recorded NON-default profile label
     * (e.g. `"Claude (Z.AI)"`), read fresh from the host-side
     * `@ps_agent_profile` tmux user option alongside the kind. `null` for a
     * default / non-profiled / legacy session (option absent). Refreshed by
     * [refreshCurrentSessionRecordedKind] so the "What is this session?" sheet
     * can show the provider/profile.
     */
    private val _currentSessionRecordedProfile: MutableStateFlow<String?> =
        MutableStateFlow(null)
    public val currentSessionRecordedProfile: StateFlow<String?> =
        _currentSessionRecordedProfile.asStateFlow()

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
    // Issue #817 (Rank-1 measurement): per-pane t0 for the FULL conversation-open
    // span — the elapsed-realtime stamped when agent detection BEGINS for the
    // pane (first SSH exec on the open path). The span ends at the
    // markAgentTailLive push in startAgentConversationForPane. Threading the t0
    // through this map (rather than a parameter) keeps the recorded-vs-foreign
    // detection branch and the existing call sites untouched, and the entry is
    // consumed-and-removed on emit so a retry/re-detect re-stamps a fresh start.
    private val paneConversationOpenStartedAtMs: MutableMap<String, Long> = ConcurrentHashMap()
    // Issue #828 (perf): per-tmux-session cache of the RECORDED `@ps_agent_kind`
    // (the #825 identity PocketShell stamps on sessions it launches). Keyed by
    // the tmux session id / target token (`pane.sessionId`). The open path runs
    // detection per-pane on EVERY reconcile, and `readRecordedAgentKind` is a
    // standalone SSH round-trip; without a cache that round-trip is paid on every
    // single open. The recorded kind is FIXED for the life of the session (the
    // launch wrapper writes it once and never rewrites it), so reading it more
    // than once per session is pure waste. We resolve it at most once per session
    // and reuse it for all panes + all later reconciles, so the steady-state
    // recorded-open path issues ZERO `readRecordedAgentKind` execs (AC: detection
    // chain ~0). [RecordedKindCacheEntry] distinguishes "not yet read" (absent)
    // from "read = foreign/null" (present-but-null) so a foreign session is not
    // re-probed on every reconcile either. Cleared with the other per-runtime
    // detection maps on park/restore/clear so a different session never inherits
    // a stale recorded kind.
    private val sessionRecordedKindCache: MutableMap<String, RecordedKindCacheEntry> =
        ConcurrentHashMap()
    // Epic #821 slice A2: per-tmux-session cache of the ONE-SHOT FOREIGN kind
    // guess (`pocketshell agents kind` daemon RPC) for sessions that carry no
    // recorded `@ps_agent_kind`. The RPC has no server-side TTL, so the "fire
    // ONCE per foreign session" discipline is the CLIENT's job: we guess on the
    // first sighting of a foreign session, cache the verdict here keyed by the
    // tmux session id, and never re-probe on later reconciles/switches. A user
    // confirm/pick then writes a durable `@ps_agent_kind` (ManualKindWriter) and
    // the session reads back as recorded — so the guess sticks exactly like a
    // recorded kind and is never re-run. A PRESENT entry whose [kind] is null
    // means "guessed = not an agent / unknown" (still one-shot — do not re-probe).
    // Cleared with the other per-runtime detection maps on park/restore/clear.
    private val sessionForeignKindGuessCache: MutableMap<String, ForeignKindGuessEntry> =
        ConcurrentHashMap()
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
    // Issue #793: per-pane conversation-load watchdog. Flips a never-completing
    // "Loading conversation…" state to Failed so the tab can't spin forever.
    private val conversationLoadWatchdogJobs: MutableMap<String, Job> = ConcurrentHashMap()
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

    // Issue #896: scope-level safety net for the SSH/tmux close/EOF cascade.
    //
    // The crash class: closing the attached/last session → the gateway kill
    // destroys tmux → the live `-CC` control client EOFs → that EOF fans out
    // to several `bridgeScope.launch {}` collectors (client.events →
    // onControlEvent, client.disconnected, per-pane output/port, agent tail /
    // detection / conversation watchdog) AT THE SAME MOMENT the scopes are
    // torn down. A SupervisorJob isolates SIBLING cancellation but does NOT
    // swallow a child's exception — so a single unguarded suspend-IO call that
    // throws (e.g. `SshException: SSH session is not connected`, the captured
    // June-8 specimen) against the already-dead transport propagates to
    // `Thread.defaultUncaughtExceptionHandler` → CrashReporter saves it then
    // re-delegates to the platform handler → PROCESS DEATH. (Proven specimen:
    // RealSshSession.ensureConnected → tail → AgentConversationRepository →
    // startAgentConversationForPane, thread main, StandaloneCoroutine{Cancelling}.)
    //
    // This handler converts such a teardown-race THROW into a logged,
    // recoverable event instead of a crash. It records to DiagnosticEvents AND
    // the CrashReporter store (so a swallowed throw stays VISIBLE — it never
    // becomes a silent black hole), then returns without re-throwing.
    //
    // CRITICAL (anti-#895-masking): this handler does NOT and CANNOT eat a
    // genuine transport DROP that must drive the connection lifecycle. A real
    // `-CC` drop arrives as a normal EMISSION on `client.disconnected`
    // (a latched StateFlow) → handlePassiveClientDisconnect →
    // reduceConnection(TransportDropped) → ConnectionController, which surfaces
    // the escapable Reconnecting/Reconnect band (#895's concern). A
    // CoroutineExceptionHandler is only ever invoked for an UNCAUGHT THROW; it
    // is never on the path of a normal flow emission, so the drop→band routing
    // is preserved by construction. The handler's sole job is to stop a
    // teardown-time throw from killing the process.
    //
    // CancellationException is intentionally untouched: kotlinx.coroutines never
    // routes CancellationException to a CoroutineExceptionHandler (it is normal
    // cooperative cancellation), so adding this handler does not change
    // cancellation semantics across the connection core (D28). The explicit
    // re-throw below is defensive belt-and-braces for that invariant.
    private val bridgeExceptionHandler = CoroutineExceptionHandler { context, throwable ->
        if (throwable is CancellationException) throw throwable
        onBridgeCoroutineFailure(context, throwable)
    }

    /**
     * Issue #896: handle (log + record, no re-throw) a teardown-race throw on
     * the close/EOF cascade. Exposed as a member so tests can observe the
     * safety net firing without crashing the test process (the #780 synthetic
     * model) — the load-bearing assertion is that an unguarded cascade throw
     * lands HERE, not in the thread's uncaught-exception handler.
     */
    internal fun onBridgeCoroutineFailure(
        context: kotlin.coroutines.CoroutineContext,
        throwable: Throwable,
    ) {
        Log.w(
            ISSUE_896_BRIDGE_SAFETY_NET_TAG,
            "Swallowed uncaught coroutine failure on the SSH/tmux close cascade " +
                "(process kept alive); recorded as a non-fatal report",
            throwable,
        )
        runCatching {
            DiagnosticEvents.record(
                "connection",
                "bridge_coroutine_uncaught",
                "source" to "bridge_exception_handler",
                "classification" to "teardown_race_throw_swallowed",
                "exceptionClass" to throwable.javaClass.name,
                "message" to throwable.message,
                "coroutineName" to context[kotlinx.coroutines.CoroutineName]?.name,
                "generation" to connectGeneration,
            )
        }
        // Persist a NON-FATAL crash report so the swallowed throw remains
        // visible in the in-app crash-reports list (anti-masking). Does not
        // re-delegate to the platform handler, so the process survives.
        runCatching { CrashReporter.recordNonFatal(applicationContext, throwable) }
        bridgeCoroutineFailureProbe?.invoke(throwable)
    }

    /**
     * Test-only probe (issue #896). When set, [onBridgeCoroutineFailure]
     * invokes it after recording, so an instrumentation/unit test can assert
     * the safety net caught the cascade throw (rather than the throw reaching
     * the thread's uncaught handler / killing the process). Null in production.
     */
    @get:androidx.annotation.VisibleForTesting
    @set:androidx.annotation.VisibleForTesting
    internal var bridgeCoroutineFailureProbe: ((Throwable) -> Unit)? = null

    // Bridge scope: a child of viewModelScope (parented via the
    // viewModelScope's Job) but with its own SupervisorJob so that a
    // producer-cancellation on one pane's TerminalSurfaceState (e.g. the
    // SharedFlow's collector failing) does not cascade into sibling panes.
    // Each TerminalSurfaceState.attachExternalProducer returns a Job
    // rooted in this scope; cancelling viewModelScope (via onCleared)
    // also cancels this scope's SupervisorJob through the parent link.
    // Issue #896: the bridgeExceptionHandler is part of the context so EVERY
    // bridgeScope.launch {} cascade collector (and child scopes derived from
    // bridgeScope.coroutineContext, e.g. the layout coalescer) inherits the
    // safety net — covering the whole crash CLASS, not one site.
    private val bridgeScope = CoroutineScope(
        viewModelScope.coroutineContext +
            SupervisorJob(viewModelScope.coroutineContext[Job]) +
            bridgeExceptionHandler,
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
        tmuxSessionId: String? = null,
        sessionCreated: Long? = null,
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
                tmuxSessionId = tmuxSessionId,
                sessionCreated = sessionCreated,
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
            tmuxSessionId = tmuxSessionId,
            sessionCreated = sessionCreated,
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
        val generation = nextConnectGeneration()
        latestConnectIntent = ConnectIntent(
            target = target,
            trigger = effectiveTrigger,
            generation = generation,
        )
        // EPIC #687 P1: announce the nav-route target to the reveal state machine
        // SYNCHRONOUSLY at the accepted-connect moment (before any coroutine runs).
        // This supersedes whatever was showing — the header flips to the target
        // name and the leaving target's panes are cleared — so under the NEW
        // connection path the screen can never paint `(old session's panes, new
        // target id)`, the wrong-session-on-switch race (#686/#658).
        navigateRevealTo(target)

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
                    // EPIC #792 Slice B: the warm fast-switch IO is now owned by the
                    // single [TmuxAttachEffects] dispatcher — the inline direct call is
                    // deleted (D22 hard-cut, no dual-write). The IO body (the former
                    // `runFastSessionSwitch`) runs at this SAME synchronous trigger point
                    // inside the connectJob critical section (the no-flash / switch-latency
                    // ordering is preserved); only the dispatch owner moved.
                    tmuxAttachEffects.runFastSwitch(
                        target = target,
                        attempt = attempt,
                        trigger = effectiveTrigger,
                        startedAtMs = fastSwitchStartedAtMs,
                    )
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

    private fun sameSessionIdentity(left: ConnectionTarget, right: ConnectionTarget): Boolean {
        if (!left.hasSameHostAndCredential(right)) return false
        val leftDurable = left.durableSessionKey()
        val rightDurable = right.durableSessionKey()
        if (leftDurable != null || rightDurable != null) {
            return leftDurable == rightDurable
        }
        return left.sessionName == right.sessionName &&
            left.startDirectory == right.startDirectory
    }

    private fun ConnectionTarget.hasSameHostAndCredential(other: ConnectionTarget): Boolean =
        hostId == other.hostId &&
            host == other.host &&
            port == other.port &&
            user == other.user &&
            keyPath == other.keyPath

    private fun ConnectionTarget.durableSessionKey(): String? =
        durableTmuxSessionKey(hostId, tmuxSessionId, sessionCreated)

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
            durableSessionKey = durableSessionKey(),
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
        // EPIC #792 Slice C: route through the single [TransportEffects] reconnect owner —
        // the inline direct call is deleted (D22 hard-cut, single reconnect entrypoint).
        transportEffects.onManualReconnect()
        return true
    }

    /**
     * EPIC #792 Slice C: the manual / send-triggered reconnect IO BODY — the body of the
     * former inline `startReconnectForSend`, now invoked ONLY through the single
     * [TransportEffects] owner (see [transportEffects]). Cancels any in-flight auto-ladder
     * and re-enters `connect(Reconnect)` (the force-fresh-lease trigger). Returns the
     * connect [Job] (or null when there is no target) so the send path can join it.
     */
    private fun startReconnectForSendBody(): Job? {
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
            tmuxSessionId = target.tmuxSessionId,
            sessionCreated = target.sessionCreated,
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
    // EPIC #792 Slice B: the `foregroundReattachReseedForTest` /
    // `foregroundHealWithinGraceForTest` dual-write override seams are DELETED — they
    // were never set (always null → always fell through to the real body), and the grace
    // IO is now dispatched through the single [graceEffects] owner (no dual-write, D22).
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
        // EPIC #766 Slice 2a: the BACKGROUND decision is now DRIVEN by the
        // [ConnectionController]'s `-> Backgrounded` EDGE (fired by the
        // [ConnectionEffectDriver]'s `backgroundedEffect`), NOT by the inline
        // [reduceConnection] classifier. Feeding the AUTHORITATIVE controller the
        // Background event is the SOLE trigger: its transition INTO Backgrounded fires
        // [onControllerBackgrounded], which runs the inline-equivalent pause-vs-detach
        // bookkeeping AND the clean-detach teardown ([launchBackgroundDetachTeardown]),
        // in that order. The collector resumes off [observeBackground] eagerly on the
        // test main dispatcher (so the bookkeeping is synchronous in tests, exactly as
        // before); on the next Main turn in production (harmless — the only readers of
        // `pendingReattach` are the much-later foreground lifecycle event, and the
        // teardown which runs AFTER the bookkeeping inside the same effect).
        //
        // The inline `reduceConnection(Background)` arm is no longer consulted (Slice
        // 2a); the #685 trap is avoided because [onControllerBackgrounded] re-applies
        // the inline-equivalent [reduceBackground] predicate (reading
        // `clientRef`/`sessionRef`/`inlineConnectionStatus`) to select the arm — the
        // controller edge is only the TRIGGER, not the divergent display-status gate.
        connectionManager.observeBackground()
        // The controller moved to Backgrounded (mapped → Connected, the inline
        // "keep prior status on background" behavior); re-project after the effect ran.
        projectStatusFromController()
    }

    /**
     * EPIC #766 Slice 2a — the controller-EDGE-driven BACKGROUND effect. Fired by the
     * [ConnectionEffectDriver] when the [ConnectionController] transitions INTO
     * [ConnectionState.Backgrounded]. This is the re-home of the former inline
     * `reduceConnection(Background)` dispatch: the controller edge is the TRIGGER, but
     * the pause-vs-detach SELECTION still runs through the inline-equivalent
     * [reduceBackground] predicate so behavior is byte-identical (the #685
     * non-byte-identical-predicate trap — the controller transitions to Backgrounded
     * whenever it holds a host, e.g. even from `Reconnecting`, but the inline predicate
     * also gates on `clientRef`/`sessionRef`, so the arm selection MUST re-read VM state
     * rather than trust the controller's display state).
     *
     * Ordering: the SELECTION ([detachForBackground] sets `pendingReattach` /
     * `pendingBackgroundDetachPreserveTarget`) runs FIRST, then the teardown
     * ([graceEffects.onBackgrounded] -> [launchBackgroundDetachTeardown], which reads
     * those fields). [reduceBackground] returning [ConnectionDecision.Ignore] (no
     * client/session) is the no-detach case — neither the bookkeeping nor the teardown
     * runs, matching the inline `else -> Unit` arm.
     */
    private fun onControllerBackgrounded() {
        when (reduceBackground()) {
            ConnectionDecision.PauseReconnectForBackground ->
                pauseReconnectForBackground()
            ConnectionDecision.DetachForBackground -> {
                detachForBackground()
                // The clean-detach teardown reads the bookkeeping just set above; run it
                // AFTER, through the single [GraceEffects] owner.
                graceEffects.onBackgrounded()
            }
            else -> Unit
        }
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
    public fun onAppForegrounded(resumedWithinGrace: Boolean = false) {
        appActive = true
        // EPIC #687 slice 1c-iv-c (#754): the within-grace foreground return is now
        // owned by the driver/controller as a RESEED-ONLY effect, gated on the
        // controller's grace predicate. Within grace the `-CC` control client was
        // NEVER torn down (the teardown is deferred to grace-elapsed), so the warm
        // lease is intact: we re-capture the active pane and let the existing
        // SeedLanded feedback promote the controller back to Live. We DO NOT run the
        // old inline `probeCurrentRuntimeOnForegroundIfNeeded → connect(LifecycleReattach)`
        // path, which raised `_switchHidesTerminal` (the "Attaching…" overlay) on any
        // confirmed-dead probe verdict even inside grace — the D21 violation #754 fixes.
        // The within-grace gate (`resumedWithinGrace && warm lease`) is exactly the
        // controller's `onForeground` predicate (`now < deadline && transport.isWarm`),
        // so the VM and the `ConnectionController` agree on the classification by
        // construction; the driver's `foregroundReattachEffect` seam fires the SAME
        // reseed body on the controller's Backgrounded→Reattaching edge (unit-tested in
        // `ConnectionEffectDriverTest`).
        if (resumedWithinGrace && canReseedWithinGraceForeground()) {
            // The `-CC` control client was NEVER torn down within grace (the controller
            // therefore stays Live in production — Background is only submitted at
            // grace-elapsed teardown, NOT here, so the #738 driver detach never fires).
            // Run the RESEED-ONLY effect directly: it re-promotes Live (a no-op
            // TransportLive on an already-Live controller) and heals blank panes over
            // the warm client. NO connect(), NO `_switchHidesTerminal` "Attaching…"
            // overlay — the D21 within-grace contract. The driver's
            // `foregroundReattachEffect` seam invokes this SAME body on the controller's
            // Backgrounded→Reattaching edge (the classification model, unit-tested in
            // `ConnectionEffectDriverTest`); both paths share the one reseed owner.
            // EPIC #792 Slice B: route through the single [GraceEffects] owner (the dead
            // `foregroundReattachReseedForTest` dual-write override is deleted).
            graceEffects.onForegroundReattachReseed()
            return
        }
        // EPIC #687 P2 (J1/#635): SINGLE GRACE OWNER. The App-level
        // background-grace window (#450) is the SOLE grace authority — when it reports
        // `resumedWithinGrace=true`, a within-grace foreground must stay CALM (no
        // Reconnecting/Disconnected/Connecting/Attaching band or overlay) EVEN WHEN the
        // `-CC` socket dropped while backgrounded (WiFi→cellular handoff / Doze). The
        // reseed-only fast path above declines in that case (the dropped socket killed
        // the warm lease / flipped the status off Connected / paused the passive
        // auto-reconnect), so without this branch the foreground would fall into the
        // reconnect ladder and paint the maintainer's scary band — the #635 regression.
        // Instead we SILENTLY heal the dropped channel within grace: re-open a fresh
        // `-CC` control client over a freshly-acquired lease and reseed, with NO band
        // and NO overlay. The inline passive-disconnect grace clock that fired while
        // backgrounded is disabled (see [handlePassiveClientDisconnect]), so
        // there is no competing second clock — this within-grace foreground heal is the
        // single owner of the within-grace recovery decision.
        if (resumedWithinGrace) {
            // EPIC #792 Slice B: route through the single [GraceEffects] owner (the dead
            // `foregroundHealWithinGraceForTest` dual-write override is deleted).
            graceEffects.onForegroundHealWithinGrace()
            return
        }
        // EPIC #766 Slice 2a: the FOREGROUND arm dispatch is now DRIVEN by the
        // [ConnectionController]'s foreground EDGE (fired by the [ConnectionEffectDriver]),
        // NOT by the inline [reduceConnection] classifier. Feeding the AUTHORITATIVE
        // controller the Foreground event is the SOLE trigger: beyond the App-level grace
        // (the only way this branch is reached — the within-grace fast paths returned
        // above) the App-grace teardown evicted the warm lease, so the controller's own
        // grace predicate is not-warm and it walks Backgrounded -> Reconnecting, firing
        // [onControllerForegrounded] which replays `pendingReattach` / resumes a
        // `pausedAutoReconnect` (selected via the inline-equivalent [reduceForeground]
        // predicate — the #685 trap: the controller edge is the trigger, the inline
        // predicate the gate). The inline `reduceConnection(Foreground)` arm is no longer
        // consulted (Slice 2a).
        connectionManager.observeForeground()
        dispatchPostGraceForegroundArmIfPending()
        // The controller's single grace predicate decided reattach-vs-reconnect:
        // within grace (warm lease) it is Reattaching and the active-pane reseed will
        // land it back to Live → Connected (the approved #685 Bug-A divergence — NO
        // probe churn); beyond grace it is Reconnecting (matches inline). Re-project
        // after the driver-fired effects ran.
        projectStatusFromController()
    }

    /**
     * App-level post-grace is authoritative for the VM-owned replay arm. Emulator #904
     * showed App grace expiring while the controller could still see a warm lease and
     * choose its Backgrounded→Reattaching reseed edge; after the App grace teardown there is
     * no control client left to reseed, so that edge cannot consume [pendingReattach]. The
     * controller still receives Foreground for state projection, but an already-stashed
     * replay/resume arm is dispatched from this lifecycle hook before it returns. If the
     * driver observes a Reconnecting edge too, [replayPendingReattach] has already consumed
     * [pendingReattach] and the duplicate callback is a no-op.
     */
    private fun dispatchPostGraceForegroundArmIfPending() {
        if (pendingReattach == null && pausedAutoReconnect == null) return
        onControllerForegrounded()
    }

    /**
     * EPIC #766 Slice 2a — the controller-EDGE-driven beyond-grace FOREGROUND effect.
     * Fired by the [ConnectionEffectDriver] when the [ConnectionController] transitions
     * [ConnectionState.Backgrounded] -> [ConnectionState.Reconnecting] (the beyond-grace
     * foreground return). This is the re-home of the former inline
     * `reduceConnection(Foreground)` dispatch: the controller edge is the TRIGGER, but
     * the replay-vs-resume SELECTION still runs through the inline-equivalent
     * [reduceForeground] predicate (`pendingReattach` / `pausedAutoReconnect`) so behavior
     * is byte-identical (the #685 non-byte-identical-predicate trap — the arm selection
     * MUST re-read VM state, not the controller's display state). The no-arm case
     * ([ConnectionDecision.Ignore]) logs the same "no-pending" skip the inline `else` arm
     * logged.
     */
    private fun onControllerForegrounded() {
        when (reduceForeground()) {
            ConnectionDecision.ReplayPendingReattach ->
                replayPendingReattach()
            ConnectionDecision.ResumePausedReconnect ->
                pausedAutoReconnect?.let { resumePausedAutoReconnect(it) }
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
    }

    /**
     * EPIC #687 slice 1c-iv-c (#754): the within-grace foreground gate. We can run
     * the RESEED-ONLY reattach (no `connect()`, no overlay) only when there is a live
     * `-CC` control client + session to re-capture against and a warm lease for the
     * active target — i.e. the connection was genuinely preserved across the brief
     * background. This is the VM-side mirror of the controller's `onForeground`
     * within-grace predicate (`transport.isWarm`); when it is false we fall through to
     * the normal foreground decision (a real reconnect path).
     */
    private fun canReseedWithinGraceForeground(): Boolean {
        if (inlineConnectionStatus !is ConnectionStatus.Connected) return false
        if (clientRef == null) return false
        if (sessionRef == null) return false
        val target = activeTarget ?: return false
        if (pendingReattach != null) return false
        if (pausedAutoReconnect != null) return false
        return liveLeaseKeys.contains(target.toSshLeaseTarget().leaseKey)
    }

    /**
     * EPIC #687 slice 1c-iv-c (#754): the RESEED-ONLY within-grace foreground reattach
     * body — the hard-cut replacement for the deleted inline
     * `probeCurrentRuntimeOnForegroundIfNeeded → connect(LifecycleReattach)` path. The
     * warm `-CC` control client is still attached (the teardown is deferred to
     * grace-elapsed), so there is NOTHING to reconnect: the emulator still holds the
     * live frame the channel streamed while attached. We promote the controller back
     * to Live (the channel is live) and run the existing blank-pane safety-net reseed
     * over the SAME live client so a pane that came back blank under a brief link blip
     * still heals — without a handshake. Crucially this NEVER calls `connect()` and
     * NEVER raises `_switchHidesTerminal`, so the user sees no "Attaching…" overlay and
     * no reconnect — the D21 within-grace contract.
     *
     * This is also the body the [ConnectionEffectDriver]'s `foregroundReattachEffect`
     * seam invokes on the controller's Backgrounded→Reattaching edge (unit-tested),
     * so there is a SINGLE owner of the within-grace reseed effect.
     */
    private fun launchForegroundReattachReseed() {
        val client = clientRef ?: return
        val target = activeTarget ?: return
        Log.i(
            ISSUE_235_LIFECYCLE_TAG,
            "tmux-foreground-reseed-within-grace generation=$connectGeneration " +
                targetLogFields(target),
        )
        ReconnectCauseTrail.record(
            stage = "foreground_reattach",
            outcome = "reseed_only",
            cause = "within_grace_foreground",
            trigger = TmuxConnectTrigger.LifecycleReattach.logValue,
            "hostId" to target.hostId,
            "generation" to connectGeneration,
            "clientHash" to System.identityHashCode(client),
        )
        // The control channel is still live — promote the controller Reattaching → Live
        // so the displayed status stays the calm `Connected` (no Reconnecting/overlay).
        connectionManager.observeForegroundReattachLive()
        projectStatusFromController()
        viewModelScope.launch {
            if (client.disconnected.value) return@launch
            val guard = RuntimeRefreshGuard(
                generation = connectGeneration,
                target = target,
                client = client,
            )
            // EPIC #687 P3 (J2/#553): id-tagged FULL-VIEWPORT reseed. A
            // within-grace reattach is an authoritative reseed point — tmux's grid
            // holds the FULL prior content, but the warm `-CC` client never re-emits
            // an idle pane's existing frame, so the emulator can be left
            // PARTIALLY blank (e.g. only a per-second timer line repainted while the
            // static viewport above it was wiped by a reflow during the blip). We
            // restore the FULL viewport keyed to the TARGET session id,
            // UNCONDITIONALLY (not gated on full-blank) — so the static content above
            // the live line is repainted from a fresh `capture-pane`. The reseed is
            // dropped if the runtime/target was superseded mid-flight (the guard),
            // so a late seed for a switched-away session can never paint.
            reseedActivePaneForReattach(guard)
        }
    }

    /**
     * Issue #892: the on-demand "Redraw" escape hatch. The user taps the kebab's
     * **Redraw** item to manually recover from a black/partial-black terminal (the
     * dogfood screenshot: ~100% black with only a lone stray cursor cell). This forces
     * a FULL-viewport reseed of the active pane over the WARM session — it REUSES the
     * exact #553/#879 full-reseed machinery ([reseedActivePaneForReattach] →
     * [seedPaneFromCapture] → [TerminalSurfaceState.appendRemoteOutput], which fires the
     * #721 `_fullRepaintRequests` full clear+repaint) and the other-pane blank net.
     *
     * D21/D28 contract (a warm-session reseed ONLY — NO reconnect, detach, or new lease):
     *  - It NEVER calls `connect()`, bumps [connectGeneration], evicts/acquires a lease,
     *    or touches the reconnect/grace state machine. It runs entirely over the
     *    already-live `clientRef` (`-CC` control channel) against the current runtime
     *    guard, exactly like the within-grace foreground reseed does.
     *  - It does NOT raise `_switchHidesTerminal` (no "Attaching…" overlay) and does NOT
     *    change [_connectionStatus] — the status stays the calm `Connected`.
     *
     * It differs from [reseedVisiblePaneIfBlank] (the #662 switch heal) in that it is
     * UNCONDITIONAL: the screenshot state is NOT `visibleScreenIsBlank()` (a stray cursor
     * cell is painted) and the pane is usually already in [panesSeededThisAttach], so the
     * blank-gated / freshly-seeded skips would no-op exactly when the user needs the
     * redraw. So Redraw passes `skipWhenFreshlySeeded = false` to force the recapture for
     * BOTH shell panes and idle agent/alt-screen panes (which never re-emit `%output` to
     * heal themselves).
     *
     * A no-op (returns having logged) when nothing is attached / the client is gone — the
     * kebab item is only reachable inside a live session, so that is the defensive guard.
     */
    public fun redrawActivePane() {
        val client = clientRef ?: run {
            Log.i(ISSUE_145_RECONNECT_TAG, "tmux-redraw-skip no-client")
            return
        }
        if (client.disconnected.value) {
            Log.i(ISSUE_145_RECONNECT_TAG, "tmux-redraw-skip client-disconnected")
            return
        }
        val target = activeTarget ?: run {
            Log.i(ISSUE_145_RECONNECT_TAG, "tmux-redraw-skip no-target")
            return
        }
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-redraw-active-pane generation=$connectGeneration " +
                "session=${target.sessionName} status=${_connectionStatus.value}",
        )
        ReconnectCauseTrail.record(
            stage = "manual_redraw",
            outcome = "reseed_only",
            cause = "user_redraw_request",
            trigger = TmuxConnectTrigger.LifecycleReattach.logValue,
            "hostId" to target.hostId,
            "generation" to connectGeneration,
            "clientHash" to System.identityHashCode(client),
        )
        viewModelScope.launch {
            if (client.disconnected.value) return@launch
            val guard = RuntimeRefreshGuard(
                generation = connectGeneration,
                target = target,
                client = client,
            )
            // UNCONDITIONAL full-viewport restore of the active pane + the blank net over
            // any OTHER visible pane. skipWhenFreshlySeeded=false forces the recapture even
            // for a pane already seeded this attach — the whole point of a manual Redraw.
            reseedActivePaneForReattach(guard, skipWhenFreshlySeeded = false)
        }
    }

    /**
     * EPIC #687 P3 (J2/#553): the id-tagged FULL-VIEWPORT reseed for a within-grace
     * reattach. Unlike [reseedBlankVisiblePanes] (which `continue`s on any pane that is
     * not `transcriptText.isBlank()`), this UNCONDITIONALLY re-captures the active
     * visible pane from a fresh `capture-pane` and feeds the full grid back into the
     * emulator — so a PARTIALLY-blank pane (one live line present, the static content
     * above it gone) is restored to the FULL prior viewport, not left "only a timer,
     * rest blank" (#553). [seedPaneFromCapture] → [TerminalSurfaceState.appendRemoteOutput]
     * repaints the whole grid (a full clear+restore), so the live line is preserved
     * within the restored frame, not duplicated.
     *
     * Keyed to the TARGET session id via the [RuntimeRefreshGuard] (generation + target
     * + client): the reseed aborts the instant the runtime is superseded (a switch /
     * fresh connect raced the within-grace foreground), so a late seed can never paint a
     * non-target session. The full-viewport restore is only meaningful for the pane the
     * user is actually looking at (page 0); the lazily-revealed background panes keep the
     * existing reveal-time blank-reseed safety net (#640/#662).
     */
    private suspend fun reseedActivePaneForReattach(
        refreshGuard: RuntimeRefreshGuard,
        skipWhenFreshlySeeded: Boolean = false,
    ) {
        val client = clientRef ?: return
        if (client.disconnected.value) return
        if (!isCurrentRuntime(refreshGuard)) return
        val activePane = activeVisiblePane() ?: return
        val partialBlank = activePane.terminalState.visibleScreenIsPartiallyBlank()
        val blank = activePane.terminalState.visibleScreenIsBlank()
        // Issue #830: the resize-completion caller flows through here on EVERY attach
        // path, including a fresh COLD OPEN where [preloadVisibleContentForNewPanes]
        // already captured this active pane THIS attach. On a cold open the
        // post-`refresh-client -C` reflow streams the new-grid pane content back as
        // live `%output`, so a non-blank, non-partial-blank active pane that was just
        // seeded this attach is already being repainted at the correct grid — a
        // second unconditional `capture-pane` here is the redundant cold-open reseed
        // the #640 single-capture contract forbids (extra round-trip + relayout =
        // flicker on a high-latency link). Skip the re-capture ONLY for that exact
        // case; a reattach/reflow on a REUSED pane (not seeded this attach) or a
        // blank / "one live line, rest blank" mis-wrapped pane (#553/#651/#717) still
        // gets the unconditional full-viewport restore below. The other-pane blank
        // backstop always runs.
        val freshlySeededAndPainted = skipWhenFreshlySeeded &&
            activePane.paneId in panesSeededThisAttach &&
            !blank &&
            !partialBlank
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-reattach-full-viewport-reseed pane=${activePane.paneId} " +
                "window=${activePane.windowId} session=${activeTarget?.sessionName} " +
                "partialBlank=$partialBlank freshlySeededAndPainted=$freshlySeededAndPainted " +
                "status=${_connectionStatus.value}",
        )
        if (!freshlySeededAndPainted) {
            // UNCONDITIONAL full-viewport restore of the active pane (id-tagged via the
            // guard) — NOT gated on `visibleScreenIsBlank()`, so a partial blank is healed.
            seedPaneFromCapture(client, activePane, refreshGuard, recordMilestone = false)
        }
        // Then run the existing blank-net backstop over any OTHER visible pane that came
        // back fully blank (a no-op for the active pane just restored above).
        reseedBlankVisiblePanes(refreshGuard)
    }

    /**
     * EPIC #687 P2 (J1/#635): the within-grace foreground SILENT heal of a `-CC` socket
     * that DROPPED while backgrounded (WiFi→cellular handoff / Doze). This is the
     * NEW-path single-grace-owner recovery: the App-level grace window (#450) reported
     * `resumedWithinGrace=true`, so the user must NOT see a reconnect band — but the
     * reseed-only fast path declined because the dropped socket killed the warm lease
     * (and the inline passive disconnect that fired while backgrounded paused the
     * auto-reconnect + set [ConnectionStatus.Unreachable]). So there is nothing live to
     * reseed: we re-open a fresh `-CC` control client over a freshly-acquired lease and
     * reseed the panes, SILENTLY — NO band, NO "Attaching…"/"Connecting" overlay. The
     * controller is moved Reattaching → Live, so the displayed status stays the calm
     * `Connected` throughout.
     *
     * This NEVER calls [connect] (which would raise the Connecting overlay / scary
     * band); it reuses the existing [silentlyReconnectTransportAfterPassiveDisconnect]
     * heal primitive — the same fresh-transport reattach the inline passive grace clock
     * used, but now driven by the single grace owner instead of a competing second
     * clock (the inline clock is disabled under NEW while backgrounded; see
     * [handlePassiveClientDisconnect]).
     */
    private fun launchForegroundHealWithinGrace() {
        val target = activeTarget ?: pausedAutoReconnect?.target ?: pendingReattach?.target
        if (target == null) {
            // No target to heal against — fall back to the normal foreground decision so
            // a genuinely orphaned foreground still does the right (non-grace) thing.
            // EPIC #766 Slice 2a: the foreground arm dispatch is driven by the controller
            // edge (the driver fires [onControllerForegrounded]); the inline
            // `reduceConnection(Foreground)` consultation is removed. By construction this
            // branch has NO `pendingReattach`/`pausedAutoReconnect` (the `target`-deriving
            // expression above already proved both null), so [reduceForeground] is `Ignore`
            // and the driver-fired effect is a no-op — exactly the prior inline `else`
            // behavior, just without re-consulting the classifier here.
            connectionManager.observeForeground()
            projectStatusFromController()
            return
        }
        // The passive disconnect that fired while backgrounded must NOT keep us in a
        // paused/Unreachable surface — the single grace owner is healing it now. Clear
        // the inline passive bookkeeping so it cannot race or paint a band.
        pausedAutoReconnect = null
        passiveDisconnectGraceJob?.cancel()
        passiveDisconnectGraceJob = null
        activeTarget = target
        connectingTarget = null
        Log.i(
            ISSUE_235_LIFECYCLE_TAG,
            "tmux-foreground-heal-within-grace generation=$connectGeneration " +
                targetLogFields(target),
        )
        ReconnectCauseTrail.record(
            stage = "foreground_reattach",
            outcome = "silent_heal_within_grace",
            cause = "within_grace_foreground_socket_drop",
            trigger = TmuxConnectTrigger.LifecycleReattach.logValue,
            "hostId" to target.hostId,
            "generation" to connectGeneration,
        )
        DiagnosticEvents.record(
            "connection",
            "foreground_reattach",
            "source" to "app_lifecycle",
            "outcome" to "silent_heal_within_grace",
            "trigger" to TmuxConnectTrigger.LifecycleReattach.logValue,
            "generation" to connectGeneration,
            "hostId" to target.hostId,
            "host" to target.host,
            "port" to target.port,
            "user" to target.user,
            "session" to target.sessionName,
        )
        // Keep the displayed status CALM while we heal: the within-grace foreground is a
        // ride-through, not a reconnect. Promote the controller back toward Live so the
        // header indicator never shows Reconnecting/Disconnected during the heal.
        connectionManager.observeForegroundReattachLive()
        projectStatusFromController()
        viewModelScope.launch {
            // Re-open a fresh `-CC` control client over a freshly-acquired lease and
            // reseed — SILENTLY (the primitive never raises the Connecting overlay or a
            // band; on success it sets [ConnectionStatus.Live] and reseeds every visible
            // pane). `clientRef` may be null (the passive path unregistered it); the
            // primitive's staleClient is nullable and used only for the close()/restore.
            val recovered = silentlyReconnectTransportAfterPassiveDisconnect(
                staleClient = clientRef,
                target = target,
                timeoutMs = passiveDisconnectGraceMs.coerceAtLeast(1L),
            )
            if (!recovered) {
                // The within-grace silent heal could not re-open the channel in time.
                // Fall back to the normal auto-reconnect ladder — still SILENT (no manual
                // "Tap Reconnect" band): [scheduleAutoReconnect] walks the calm
                // Reconnecting ladder. The single-grace-owner contract requires the
                // band-free within-grace ride-through when the heal succeeds; an honest
                // failure to re-reach the host still surfaces through the calm reconnect
                // path, not a scary disconnect band.
                scheduleAutoReconnect(
                    target = target,
                    reason = "Reattaching to ${target.user}@${target.host}:${target.port}.",
                    trigger = TmuxConnectTrigger.AutoReconnect,
                )
            }
        }
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
                tmuxSessionId = target.tmuxSessionId,
                sessionCreated = target.sessionCreated,
                trigger = TmuxConnectTrigger.LifecycleReattach,
            )
        }
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
            tmuxSessionId = target.tmuxSessionId,
            sessionCreated = target.sessionCreated,
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
                tmuxSessionId = intent.target.tmuxSessionId,
                sessionCreated = intent.target.sessionCreated,
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
                    panePid = pane.panePid,
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
                onForeground = { resumedWithinGrace -> onAppForegrounded(resumedWithinGrace) },
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
        // EPIC #792 Slice E: feed the AUTHORITATIVE controller the #548 validated-handoff
        // signal it suppresses on (computed identically to the inline reducer).
        connectionManager.observeNetworkChanged(
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
        conversationLoadWatchdogJobs.values.forEach { it.cancel() }
        conversationLoadWatchdogJobs.clear()
        paneAgentTailGenerations.clear()
        paneAgentInputs.clear()
        paneAgentNullDetections.clear()
        paneConversationOpenStartedAtMs.clear()
        sessionRecordedKindCache.clear()
        sessionForeignKindGuessCache.clear()
        // Issue #894 (Slice C): drop the durable confirmed-shell verdicts so a
        // different session never inherits a stale shell verdict.
        confirmedShellSessionIds.clear()
        _confirmedShellPaneIds.value = emptySet()
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
        // Issue #793: drop any pending load watchdogs from the prior runtime so
        // a restored, already-populated conversation row is not flipped to
        // Failed by a stale timer.
        conversationLoadWatchdogJobs.values.forEach { it.cancel() }
        conversationLoadWatchdogJobs.clear()
        paneAgentTailGenerations.clear()
        paneAgentInputs.clear()
        paneAgentNullDetections.clear()
        paneConversationOpenStartedAtMs.clear()
        sessionRecordedKindCache.clear()
        sessionForeignKindGuessCache.clear()
        // Issue #894 (Slice C): the parked runtime does not carry the durable
        // confirmed-shell verdict set; clear it so the restored session never
        // inherits a stale verdict. refreshCurrentSessionRecordedKind re-reads
        // the `@ps_agent_kind` on the restored connection and re-derives it.
        confirmedShellSessionIds.clear()
        _confirmedShellPaneIds.value = emptySet()
        paneAgentInputs.putAll(runtime.paneAgentInputs)
        replaceAgentConversations(runtime.agentConversations)
        remoteColumns = runtime.remoteColumns
        remoteRows = runtime.remoteRows
        resetControlClientSizeForAttach()
        connectionTmuxPort.setClient(runtime.client)
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
        // EPIC #687 P1: the warm cache-hit reveals the target's own pane WITHOUT a
        // fresh capture, so promote the reveal machine to Live for the target here
        // (otherwise the NEW-path reveal gate would stay held and the surface would
        // never mount on a switch back to a cached session).
        promoteRevealLiveForActiveTarget()
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
            trigger == TmuxConnectTrigger.NetworkReconnect ||
            // EPIC #792 Slice C (the #822 wedge fix): the AUTO-reconnect ladder must ALSO
            // force a fresh SSH lease. On a silent half-open drop sshj's `isConnected` lies
            // (it stays true until the ~60s keep-alive trips), so the lease pool's
            // `acquire()` REUSES the poisoned warm entry and every ladder attempt re-dials
            // the SAME dead socket — the #822 "Reconnecting(1/4) stuck ~45s, only recovers
            // via the switch-session dance" wedge (the switch dance recovered only because
            // re-entering connect() to another host eventually evicted the poisoned lease).
            // Forcing a fresh lease evicts the idle/poisoned entry first (acquireLeaseForTmux
            // → evictIdle), so the SAME session auto-recovers WITHOUT the switch dance.
            trigger == TmuxConnectTrigger.AutoReconnect

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
            // Issue #830: reset the in-flight seed tracker in lockstep.
            panesSeedInFlightThisAttach.clear()

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
            // EPIC #687 P1: when the active pane is seeded non-blank, the inline path
            // reveals the target's surface — promote the reveal machine to Live for
            // the target so the NEW-path reveal gate releases in lockstep.
            if (activePaneSeeded) promoteRevealLiveForActiveTarget()
            setConnectionState(
                ConnectionState.Live(
                    target.host,
                    target.port,
                    target.user,
                ),
            )
            if (!activePaneSeeded) {
                armConnectedBlankWatchdog(blankReseedGuard, surfaceErrorOnExhaustion = true)
            }
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
            // Issue #879: belt-and-suspenders for the BEYOND-GRACE reconnect
            // (`connect(LifecycleReattach)`). On that path the pane, its
            // [TerminalSurfaceState], and its [TerminalView] are all RE-CREATED:
            // [preloadVisibleContentForNewPanes] seeds the active pane (firing the
            // full-repaint signal) BEFORE the fresh surface reveals/binds its
            // collector. The `replay = 1` flow change closes that drop at the
            // render layer; this re-fire is the second safety net the #879
            // research called for — a buffer that is correctly seeded but
            // unpainted reads NON-blank, so the deferred [reseedBlankVisiblePanes]
            // backstop above SKIPS it (it only re-captures fully-blank panes). So
            // after the surface is revealed (collector definitely live), re-fire
            // the UNCONDITIONAL full-viewport reseed of the active pane keyed to
            // the target via the guard, which re-emits the full-repaint request to
            // the now-subscribed View. Restricted to the reconnect trigger so a
            // normal cold open / switch pays no extra capture. Dropped if the
            // runtime/target was superseded mid-flight (the guard).
            if (trigger == TmuxConnectTrigger.LifecycleReattach) {
                bridgeScope.launch {
                    if (!isCurrentRuntime(blankReseedGuard)) return@launch
                    reseedActivePaneForReattach(blankReseedGuard)
                }
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
        tmuxSessionId: String?,
        sessionCreated: Long?,
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
            tmuxSessionId = tmuxSessionId,
            sessionCreated = sessionCreated,
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
                    tmuxSessionId = tmuxSessionId,
                    sessionCreated = sessionCreated,
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
            // EPIC #687 P1: the fast-switch reveal shows the target's seeded pane —
            // promote the reveal machine to Live for the target so the NEW-path reveal
            // gate releases in the same mutation (never holds on a warm switch).
            if (activePaneSeeded) promoteRevealLiveForActiveTarget()
            setConnectionState(
                ConnectionState.Live(
                    target.host,
                    target.port,
                    target.user,
                ),
            )
            if (!activePaneSeeded) {
                armConnectedBlankWatchdog(fastSwitchRevealGuard, surfaceErrorOnExhaustion = true)
            }
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
                    tmuxSessionId = recoverTarget.tmuxSessionId,
                    sessionCreated = recoverTarget.sessionCreated,
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
        // EPIC #792 Slice E: re-point the effect driver's real TmuxPort at the freshly
        // attached client so its `disconnected` oracle follows the live channel — this
        // updates the flow the driver collects to submit TransportDropped from reality.
        connectionTmuxPort.setClient(client)
        bindClientObservers(client)
        // EPIC #792 Slice D: lazily start the proactive drop detector on the first
        // attach (reads the timing override now, after any test set it). Idempotent.
        ensureLivenessProbeStarted()
    }

    private fun bindClientObservers(client: TmuxClient) {
        // Cancel any previous subscription before re-binding (idempotency
        // for tests that swap clients on the same ViewModel instance).
        eventsJob?.cancel()

        // Issue #576 (Slice A of #792): stand up a fresh coalescer + its
        // off-main drain scope for this client. Structural control events
        // (`%layout-change`/`%window-add`/`%window-close`/`%pane-mode-changed`)
        // are OFFERED into it; a Codex `/new` storm of N of them collapses to
        // ~1 reconcile per frame on [reconcileDispatcher], so the UI thread is
        // no longer head-of-line-blocked behind N `list-panes`/`capture-pane`
        // round-trips (the ANR). `%output` and everything else still flow
        // through `onControlEvent` synchronously on the collector.
        layoutChangeCoalescer?.stop()
        layoutCoalescerScope?.cancel()
        // The coalescer drain loop gets its OWN child Job parented to
        // bridgeScope (NOT a bare `bridgeScope.coroutineContext`, which would
        // REUSE bridgeScope's Job — then `coalescerScope.cancel()` on the next
        // re-bind / teardown would cancel bridgeScope itself and tear down the
        // event collector AND the cached-runtime refresh launches). A child
        // SupervisorJob isolates the drain loop's lifecycle and dispatches it on
        // [reconcileDispatcher] (off-main in production).
        val coalescerScope = CoroutineScope(
            bridgeScope.coroutineContext +
                SupervisorJob(bridgeScope.coroutineContext[Job]) +
                reconcileDispatcher,
        )
        layoutCoalescerScope = coalescerScope
        val coalescer = LayoutChangeCoalescer(
            reconcile = {
                // Only reconcile while this client is still the active one;
                // a stale coalescer must not drive a reconcile for a torn-down
                // client (the bind cancels the prior scope, but guard anyway).
                if (clientRef === client) {
                    reconcilePanes()
                }
            },
            onReconcileError = { t ->
                Log.w(ISSUE_576_COALESCER_TAG, "Coalesced reconcilePanes failed", t)
            },
        )
        coalescer.start(coalescerScope)
        layoutChangeCoalescer = coalescer

        val job = bridgeScope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                // Structural events drive a `list-panes` reconcile; route them
                // through the coalescer (non-blocking offer) so a burst collapses
                // to ~1 off-main reconcile per frame. Everything else
                // (notably `%output`) keeps the existing synchronous path.
                if (LayoutChangeCoalescer.isStructural(event)) {
                    coalescer.offer(event)
                } else {
                    onControlEvent(event)
                }
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
                // Recovery is fired from [ConnectionEffectDriver.controlChannelDroppedEffect]
                // after the real current-client drop has moved the controller. This
                // per-client observer stays only as a stale-client breadcrumb. The
                // driver-owned handler records the canonical `passive_disconnect`
                // diagnostic so silent reattach cannot cancel this collector before
                // observability lands.
                val decision = classifyPassiveTransportDrop(client)
                if (decision == ConnectionDecision.Ignore) {
                    DiagnosticEvents.record(
                        "connection",
                        "passive_disconnect_ignored",
                        "source" to "tmux_client_disconnected",
                        "classification" to "stale_or_irrelevant_tmux_control_channel_closed",
                        "disconnectReason" to disconnectEvent.reason.logValue,
                        "disconnectSource" to disconnectEvent.source,
                        "disconnectIntent" to disconnectEvent.intent,
                        "hostId" to target?.hostId,
                        "host" to target?.host,
                        "port" to target?.port,
                        "user" to target?.user,
                        "session" to target?.sessionName,
                        "clientHash" to System.identityHashCode(client),
                        "generation" to connectGeneration,
                    )
                } else if (isProcessBackgroundedForGraceOwner()) {
                    recordPassiveDisconnectDiagnostic(client, target, disconnectEvent)
                }
            }
        }
    }

    private fun onControllerTransportDropped(client: TmuxClient) {
        handlePassiveClientDisconnect(client, disconnectEventOrFallback(client))
    }

    private fun recordPassiveDisconnectDiagnostic(
        client: TmuxClient,
        target: ConnectionTarget?,
        disconnectEvent: TmuxDisconnectEvent,
    ) {
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
    }

    private fun handlePassiveClientDisconnect(
        client: TmuxClient,
        disconnectEvent: TmuxDisconnectEvent = disconnectEventOrFallback(client),
    ) {
        // Issue #895 (#766 down-payment): the passive-disconnect handler is
        // STATUS-AGNOSTIC. The old `inlineConnectionStatus as? Connected ?: return`
        // gate SWALLOWED a drop that landed while the VM was `Switching`
        // (Attaching) — exactly the switch-while-black freeze (R1): no recovery
        // ran, no escapable band surfaced, the user was stuck on a black pane with
        // nothing tappable. A transport drop is real regardless of the display
        // status; the controller's `onTransportDropped` already walks `Attaching`/
        // `Live`/`Reattaching`/`Reconnecting` into the silent-heal ladder, and the
        // [classifyPassiveTransportDrop] self-gates the IO arm by client identity
        // (a stale-client drop is ignored there). The display status no longer
        // gates recovery — only the client-identity guard in [classifyPassiveTransportDrop]
        // does.
        val target = activeTarget ?: connectingTarget
        val reason = passiveDisconnectMessage(target, disconnectEvent)
        recordPassiveDisconnectDiagnostic(client, target, disconnectEvent)
        // EPIC #687 P2 (J1/#635): SINGLE GRACE OWNER. A passive
        // `-CC` drop that arrives while the app is BACKGROUNDED is, by construction,
        // inside the App-level background-grace window (#450) — that window is the SOLE
        // grace authority. Running the inline passive grace clock here
        // (the 60s silent-reattach loop OR pause→Unreachable) would be a SECOND,
        // competing grace clock — the blueprint's #1 risk and the literal cause of the
        // #635 spurious-band regression: it pauses the auto-reconnect + sets Unreachable,
        // which then makes the within-grace foreground gate decline and fall into the
        // reconnect ladder (the scary band). So we DISABLE the inline grace
        // path while backgrounded entirely: just record the drop and DEFER all recovery
        // to the single grace owner — the within-grace foreground heal
        // ([launchForegroundHealWithinGrace]) on the next foreground, which silently
        // re-opens the channel and reseeds with no band. (Beyond-grace teardown already
        // ran the clean detach; that foreground is `resumedWithinGrace=false` and takes
        // the normal reconnect path, unaffected.)
        //
        // NOTE: we gate on the PROCESS-backgrounded signal, NOT `appActive`: within the
        // App-level grace window the connection is held and `onAppBackgrounded()` (which
        // flips `appActive`) is never called, so `appActive` stays true across a
        // within-grace background. [isProcessBackgroundedForGraceOwner] flips at `ON_STOP`
        // regardless of grace, correctly identifying the within-grace background.
        if (isProcessBackgroundedForGraceOwner()) {
            DiagnosticEvents.record(
                "connection",
                "passive_disconnect_deferred_to_grace_owner",
                "source" to "tmux_client_disconnected",
                "cause" to "backgrounded_within_grace_single_owner",
                "trigger" to TmuxConnectTrigger.LifecycleReattach.logValue,
                "hostId" to target?.hostId,
                "host" to target?.host,
                "session" to target?.sessionName,
                "clientHash" to System.identityHashCode(client),
                "generation" to connectGeneration,
            )
            ReconnectCauseTrail.record(
                stage = "handlePassiveClientDisconnect",
                outcome = "deferred_to_grace_owner",
                cause = "backgrounded_new_path_single_owner",
                trigger = TmuxConnectTrigger.LifecycleReattach.logValue,
                "hostId" to target?.hostId,
                "generation" to connectGeneration,
                "clientHash" to System.identityHashCode(client),
            )
            return
        }
        // Classify the drop FIRST (status-agnostic — #895/#766). `Ignore` means the
        // dropped client is not the current one (a stale `-CC` close on a healthy
        // fast switch, the #635 spurious-band case) — for that we surface NOTHING.
        val decision = classifyPassiveTransportDrop(client)
        if (decision == ConnectionDecision.Ignore) {
            projectStatusFromController()
            return
        }
        // Issue #895/#766: the driver has already surfaced the ESCAPABLE state by
        // submitting the real current-client drop to the controller before invoking
        // this callback. This body now owns only the passive recovery IO under that
        // controller-owned band.
        projectStatusFromController()
        // The inline effect machinery still owns the actual silent-reattach /
        // reconnect IO until the #766 collapse (gated on the maintainer on-device
        // checkpoint); the controller-fed band above is the escapable state, this
        // drives the recovery underneath it.
        when (decision) {
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
        // Issue #896: this silent-reattach grace loop re-dials the transport and
        // re-reads the (possibly now-gone) session right on the kill→EOF cascade,
        // racing the scope teardown — exactly where an unguarded IO throw against
        // the dead transport would otherwise crash. It is a viewModelScope.launch
        // (not bridgeScope) so it doesn't inherit the scope-level net; attach the
        // same handler explicitly so a teardown-race throw here is swallowed +
        // recorded, never a process death.
        val graceJob = viewModelScope.launch(bridgeExceptionHandler) {
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
        // EPIC #792 #833: count fresh-transport reconnect attempts instead of a
        // one-shot latch. A SUSTAINED clean outage fails the first fresh-transport
        // attempt while the link is down, but the loop must keep RE-DIALLING a fresh
        // transport across the bounded grace window so the SAME session auto-recovers
        // the moment the link returns — WITHOUT the switch-session dance. The old
        // `transportReattachTried` latch tried the fresh transport exactly once, and
        // because a failed transport reconnect nulls `sessionRef`, every later
        // iteration could only call the warm reattach (which can no longer succeed) —
        // so the loop spun uselessly until the 60s grace elapsed, leaving the session
        // wedged. We bound the re-dials with the existing 60s grace `withTimeoutOrNull`
        // plus the per-attempt timeout + 250ms retry spacing (no hot loop / battery
        // drain), and never short-circuit a HEALTHY warm reattach (the #635/#553
        // within-grace warm path still runs first when no fresh transport is preferred).
        var transportReattachAttempts = 0
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
                // EPIC #792 #833: when a fresh transport is preferred, RE-DIAL it on
                // EVERY iteration (not once) so a sustained clean outage that fails the
                // first attempt keeps escalating into a retrying ladder — the SAME
                // session recovers as soon as the link returns, with no switch dance.
                // The grace `withTimeoutOrNull` + per-attempt timeout + 250ms spacing
                // bound the re-dials.
                if (preferFreshTransport) {
                    transportReattachAttempts += 1
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
                // No warm lease to prefer (preferFreshTransport=false): the warm
                // reattach above is the cheap within-grace path; if it can't recover
                // (the warm SSH session itself is gone) escalate to a fresh transport,
                // and keep RE-DIALLING that on every later iteration for the same
                // sustained-outage resilience as the preferred-transport branch.
                if (!preferFreshTransport) {
                    transportReattachAttempts += 1
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
                    // EPIC #792 #833: how many fresh-transport re-dials the resilient
                    // grace loop made across the outage window before grace elapsed.
                    "transportReattachAttempts" to transportReattachAttempts,
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
        // EPIC #792 #833 test seam: while a synthetic clean outage is armed, every
        // reattach fails as if the link were down — so the grace loop must keep
        // re-dialling until the outage clears (see [forceCleanOutageForTest]).
        if (forceCleanOutageForTest) return false
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
                connectionTmuxPort.setClient(replacement)
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
                panesSeedInFlightThisAttach.clear()
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
        // Nullable (EPIC #687 P2): the within-grace foreground heal may have no stale
        // client handle to close (the passive path already unregistered it). The
        // primitive uses [staleClient] only for the close() round-trip and the
        // restore-on-failure, both null-safe.
        staleClient: TmuxClient?,
        target: ConnectionTarget,
        timeoutMs: Long,
    ): Boolean {
        // EPIC #792 #833 test seam: while a synthetic clean outage is armed, the
        // fresh-transport re-dial fails as if the link were down (clean FIN /
        // connection-refused). It FAITHFULLY models the real failed transport
        // reconnect: it nulls the SSH session/lease (as the real `!ready`/catch paths
        // at the end of this function do), so the warm reattach can no longer
        // succeed — which is exactly what made the OLD one-shot latch wedge for the
        // rest of the grace window. The resilient loop must keep RE-DIALLING a fresh
        // transport until the outage clears (see [forceCleanOutageForTest]).
        // Production never arms it.
        if (forceCleanOutageForTest) {
            sessionRef = null
            leaseRef = null
            return false
        }
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
                runCatching { staleClient?.close() }
                leaseRef = lease
                sessionRef = session
                clientRef = newClient
                connectionTmuxPort.setClient(newClient)
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
                panesSeedInFlightThisAttach.clear()
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
        target: ConnectionTarget?,
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
        // The user/host/port comes from the target; for the degenerate target-less
        // path (the `attachClientForTest` seam) fall back to the inline Connected
        // payload so the message stays identical to the pre-#895 behavior.
        val connected = inlineConnectionStatus as? ConnectionStatus.Connected
        val user = target?.user ?: connected?.user.orEmpty()
        val host = target?.host ?: connected?.host.orEmpty()
        val port = target?.port ?: connected?.port ?: 0
        return "$prefix from $user@$host:$port. Tap Reconnect to retry."
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

    /**
     * EPIC #792 Slice C: the auto-reconnect ladder IO BODY — the body of the former inline
     * `scheduleAutoReconnect`, now invoked ONLY through the single [TransportEffects] owner
     * (see [transportEffects] / [scheduleAutoReconnect]). The #822 wedge fix lives in the
     * lease handling: the `AutoReconnect` trigger now forces a fresh SSH lease
     * ([shouldForceFreshLease]) so each attempt evicts the poisoned half-open warm lease and
     * the same session auto-recovers WITHOUT the switch-session dance.
     */
    private fun scheduleAutoReconnectBody(
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
        // Issue #896: the auto-reconnect ladder also re-dials the transport on
        // the death cascade; attach the safety-net handler so an unexpected
        // throw mid-ladder degrades to a recorded non-fatal instead of crashing.
        autoReconnectJob = viewModelScope.launch(bridgeExceptionHandler) {
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

    /**
     * EPIC #792 Slice C: the SINGLE-OWNER auto-reconnect dispatcher. Every former inline
     * `scheduleAutoReconnect(...)` call site (passive-disconnect surface, network-handoff
     * reconnect, within-grace heal fallback) calls this, which routes the [scheduleAutoReconnectBody]
     * IO through the single [TransportEffects] reconnect owner — there is no other reconnect
     * entrypoint (D28(4) single active path / D22 hard-cut). Keeps the readable named call at
     * each site while the dispatch flows through one owner.
     */
    private fun scheduleAutoReconnect(
        target: ConnectionTarget,
        reason: String,
        trigger: TmuxConnectTrigger = TmuxConnectTrigger.AutoReconnect,
        diagnosticFields: Array<out Pair<String, Any?>> = emptyArray(),
    ) {
        transportEffects.onAutoReconnect {
            scheduleAutoReconnectBody(
                target = target,
                reason = reason,
                trigger = trigger,
                diagnosticFields = diagnosticFields,
            )
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
            panesSeedInFlightThisAttach.clear()
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
     * Process one NON-structural event from the bus.
     *
     * Issue #576 (Slice A of #792): the structural events
     * ([ControlEvent.WindowAdd] / [ControlEvent.WindowClose] /
     * [ControlEvent.LayoutChange] / [ControlEvent.PaneModeChanged]) that each
     * trigger a session-scoped `list-panes` reconcile are NOT handled here —
     * the collector in [bindClientObservers] routes them through
     * [layoutChangeCoalescer] instead, so a Codex `%layout-change` storm
     * collapses to ~1 off-main reconcile per frame rather than N synchronous
     * main-thread reconciles (the ANR). [LayoutChangeCoalescer.isStructural]
     * is the single source of truth for that classification. This function
     * handles the remaining events (notably `%output` logging).
     */
    private suspend fun onControlEvent(event: ControlEvent) {
        when (event) {
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
        // Issue #576 (Slice A of #792): the `list-panes` round-trip runs on
        // WHATEVER dispatcher the caller is on. For the Codex `%layout-change`
        // storm — the ANR — that caller is the [LayoutChangeCoalescer]'s drain
        // loop, whose scope is dispatched on [reconcileDispatcher]
        // (`Dispatchers.Default` in production); so a coalesced reconcile's
        // `list-panes`/`capture-pane` round-trips already run OFF the Main event
        // collector, and the collector's non-blocking `offer` never
        // head-of-line-blocks behind the burst backlog. The direct callers
        // (attach / cached-runtime refresh) run on Main and keep `list-panes` on
        // Main inline, preserving their existing suspension-point semantics — we
        // do NOT add an inner `withContext(reconcileDispatcher)` hop here, which
        // would (a) be redundant for the off-main storm path and (b) re-dispatch
        // the on-Main direct callers and break their ordering. Off-main-ness is
        // a property of the COALESCER SCOPE, not of this round-trip.
        val listPanesStartedAtMs = SystemClock.elapsedRealtime()
        val response = try {
            client.sendCommand(buildListPanesCommand(target))
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
        // Issue #576 (Slice A of #792): apply the parsed list + run the
        // capture-pane seed back on the MAIN dispatcher (via [applyOnMain]) so
        // every `_panes`/`paneRows`/recompose mutation stays single-threaded —
        // the coalesced (off-main IO) reconcile and the attach/refresh
        // reconciles can never interleave a torn write into the pane state.
        // No mutex is required because all mutation funnels through one thread.
        return applyOnMain {
            // Re-check the runtime guard after the dispatcher hop: a newer
            // selection may have landed while the list-panes IO was in flight.
            if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) {
                return@applyOnMain PaneReconcileResult.NoClient
            }
            val newPanes = applyParsedPanes(parsed, refreshGuard)
            preloadVisibleContentForNewPanes(newPanes, refreshGuard)
            PaneReconcileResult.Ready(_panes.value.size)
        }
    }

    /**
     * Issue #576 (Slice A of #792): run [block] on [reconcileApplyDispatcher]
     * (Main). The structural reconcile's `list-panes`/`capture-pane` IO runs on
     * the caller's dispatcher — for the coalesced Codex-storm path that is the
     * coalescer scope's [reconcileDispatcher] (`Dispatchers.Default`, off-main);
     * the resulting `_panes`/`paneRows`/recompose mutation MUST then hop back to
     * the single Main thread so concurrent reconciles (the off-main coalesced
     * one and the inline attach/refresh ones) can never interleave a torn
     * read/write of the pane state nor lose a settled burst's last write.
     *
     * When the caller is ALREADY on [reconcileApplyDispatcher] (the
     * attach/cached-refresh paths run directly on Main; the unit suite pins both
     * dispatchers to Main) we run [block] INLINE — no `withContext`, hence no
     * re-dispatch hop. That keeps those callers' suspension-point/ordering
     * semantics byte-for-byte identical to the pre-slice synchronous path; only
     * the genuinely-off-main coalescer caller actually hops. Detected by
     * comparing the running coroutine's [ContinuationInterceptor] to the apply
     * dispatcher (the dispatcher IS the interceptor).
     */
    private suspend fun <T> applyOnMain(block: suspend () -> T): T {
        val current = coroutineContext[ContinuationInterceptor]
        return if (current === reconcileApplyDispatcher) {
            block()
        } else {
            withContext(reconcileApplyDispatcher) { block() }
        }
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
        append("#{pane_in_mode}")
        append(LIST_PANES_FIELD_SEPARATOR)
        // Epic #821 slice A2: `#{pane_pid}` feeds the foreign-session one-shot
        // kind guess (`pocketshell agents kind` / `agents.kind_for_panes`).
        // Appended LAST so older tmux that omit it leave every prior field's
        // index unchanged (the parser tolerates its absence -> panePid 0).
        append("#{pane_pid}'")
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
                    // Epic #821 slice A2: refresh the pane pid for the
                    // foreign-session kind guess (tmux can re-spawn a pane's
                    // foreground process across detach/reattach).
                    panePid = p.panePid,
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
                    panePid = p.panePid,
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
            // Issue #878: if seed-from-memory did NOT create a row (a fresh
            // pane with no remembered status), seed the #818 open-time default
            // tab NOW — at pane-add, BEFORE the detection SSH round-trip — so a
            // presumed-agent pane shows the Conversation "Loading…" placeholder
            // for the whole detection window instead of the black raw Terminal.
            // Gated on `current == null` inside, so a remembered/explicit
            // status (user opted into Terminal, or a prior Conversation row) is
            // NEVER overwritten (no #815 mid-session yank).
            seedPresumedAgentPlaceholder(row)
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
            conversationLoadWatchdogJobs.remove(paneId)?.cancel()
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
        // Issue #894 (Slice C): republish the per-pane confirmed-shell signal so
        // a pane added/removed by this reconcile picks up (or drops) its
        // session's durable shell verdict for the screen + seed gate.
        refreshConfirmedShellPaneIds()
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
                // Issue #877: the UTF-8 decode + 7-regex PortDetector.scan over
                // the 4 KB tail is the per-`%output` main-thread work that froze
                // an idle agent session. Run it on [portDetectionDispatcher]
                // (single-threaded background); only the small per-found-port
                // overlay confirm hops back to the bridge scope (Main).
                val candidates = scanOutputEventForPorts(event)
                for (candidate in candidates) {
                    confirmAndSurfaceDetectedPort(candidate.port)
                }
            }
        }
    }

    /**
     * Issue #877: decode one `%output` chunk and run [PortDetector.scan] over
     * it OFF the main thread, on [portDetectionDispatcher]. Returns the new
     * port candidates worth confirming (empty if the app is backgrounded, the
     * decode failed, or no new candidate matched). The detector's mutable
     * session state stays confined to the one single-threaded background
     * dispatcher, preserving its "single-threaded by contract" invariant.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun scanOutputEventForPorts(
        event: ControlEvent.Output,
    ): List<PortDetector.Candidate> = withContext(portDetectionDispatcher) {
        if (!appActive) return@withContext emptyList<PortDetector.Candidate>()
        val text = runCatching { String(event.data, Charsets.UTF_8) }.getOrNull()
            ?: return@withContext emptyList<PortDetector.Candidate>()
        portDetector.scan(text)
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
     * EPIC #687 slice 2 (#717) test seam: deterministically reproduce the
     * SAME-DIMENSION resize short-circuit of [maybeRefreshControlClientSize] —
     * the production branch where a composer/keyboard dismissal after a
     * voice-send resizes the grid back to dims ALREADY applied
     * (`cols == appliedControlClientColumns && rows == appliedControlClientRows`),
     * so no `refresh-client -C` wire op fires. On base `origin/main` that branch
     * returns blindly, leaving an active pane the IME transition wiped BLACK; the
     * fix runs a cheap active-pane heal there.
     *
     * This seam pins the applied control-client dims to the CURRENT phone grid
     * (so a `resizeRemotePty(sameCols, sameRows)` would hit the short-circuit) and
     * then drives the exact short-circuit branch via [maybeHealActivePaneOnNoOpResize].
     * A passthrough to the production branch — no test-only behavior. Returns false
     * when nothing is attached.
     */
    @androidx.annotation.VisibleForTesting
    internal fun triggerSameDimensionResizeHealForTest(): Boolean {
        val client = clientRef ?: return false
        val target = activeTarget ?: return false
        // Pin the applied dims to the current phone grid so this models a resize
        // to ALREADY-applied dims (the short-circuit precondition).
        appliedControlClientColumns = remoteColumns
        appliedControlClientRows = remoteRows
        maybeHealActivePaneOnNoOpResize(client, target)
        return true
    }

    /**
     * Issue #722 (characterization test seam): arm [armConnectedBlankWatchdog]
     * directly against the supplied guard. A passthrough — no production logic.
     */
    @androidx.annotation.VisibleForTesting
    internal fun armConnectedBlankWatchdogForTest(guard: RuntimeRefreshGuard) {
        // Issue #886: the manual seam arms the watchdog regardless of the
        // auto-arm flag (the flag only suppresses the connect()-auto-armed
        // watchdog so a test can drive ONE watchdog in isolation).
        val previousAutoArm = connectedBlankWatchdogAutoArmEnabled
        connectedBlankWatchdogAutoArmEnabled = true
        try {
            armConnectedBlankWatchdog(guard)
        } finally {
            connectedBlankWatchdogAutoArmEnabled = previousAutoArm
        }
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
            seedPaneFromCapture(
                client = client,
                pane = activePane,
                refreshGuard = refreshGuard,
                recordMilestone = false,
                maxAttempts = 1,
            )
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
    private fun armConnectedBlankWatchdog(
        refreshGuard: RuntimeRefreshGuard,
        // Issue #886: when true, an exhausted watchdog (the active pane never
        // produced a frame) surfaces a retryable error + Reconnect instead of
        // exiting silently. ONLY the COLD/SWITCH ATTACH reveal handoffs pass true
        // (the #886 infinite-"Attaching…" at OPEN). The reattach/reconnect reseed
        // nets pass false: the transport was just re-established healthy, so a
        // still-blank pane there keeps healing on later %output rather than being
        // declared a stuck attach.
        surfaceErrorOnExhaustion: Boolean = false,
    ) {
        if (!connectedBlankWatchdogAutoArmEnabled) return
        bridgeScope.launch {
            var tick = 0
            while (tick < connectedBlankWatchdogMaxTicks) {
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
            // Issue #886: the watchdog exhausted every tick and the active pane
            // is STILL blank — the attach reveal never completed (the seed
            // capture-pane is wedged behind a busy/streaming agent channel, so
            // it never lands and "Attaching…" would otherwise spin FOREVER). The
            // old code fell off the end here SILENTLY, leaving the reveal stuck
            // in Seeding with a green dot. For a COLD/SWITCH ATTACH reveal, surface
            // a retryable error + the #823 Reconnect affordance instead of an
            // infinite spinner. (Reattach/reconnect reseed nets pass
            // surfaceErrorOnExhaustion=false and keep the old silent exit — the
            // transport is healthy and later %output still heals the pane.)
            if (!surfaceErrorOnExhaustion) return@launch
            if (!isCurrentRuntime(refreshGuard)) return@launch
            if (clientRef?.disconnected?.value == true) return@launch
            if (inlineConnectionStatus !is ConnectionStatus.Connected) return@launch
            val stillBlank = activeVisiblePane()?.terminalState?.visibleScreenIsBlank() ?: false
            if (stillBlank) {
                failStuckAttachReveal()
            }
        }
    }

    /**
     * Issue #886: the attach reveal never produced a frame within the bounded
     * blank-watchdog window — surface a retryable error so the user is NEVER
     * left on an infinite "Attaching…" spinner. The control channel is up (green
     * dot) but the active pane's `capture-pane` seed is wedged (the #470/#835
     * enumeration-stall class on a streaming agent pane), so we drive the reveal
     * to the honest-error surface [RevealState.Error] + the #823 Reconnect
     * affordance by routing through the existing single-emitter [setConnectionState]
     * `Unreachable` path (the SAME surface the 30 s panes-ready timeout uses).
     * The current target is preserved as the reconnect target so the Reconnect
     * button + pull-to-reconnect have something to re-dial.
     */
    private fun failStuckAttachReveal() {
        val target = activeTarget ?: connectingTarget
        Log.w(
            ISSUE_145_RECONNECT_TAG,
            "tmux-attach-reveal-stuck: blank-watchdog exhausted " +
                "(${connectedBlankWatchdogMaxTicks} ticks x " +
                "${CONNECTED_BLANK_WATCHDOG_TICK_MS}ms) with a still-blank active " +
                "pane on a Connected channel — surfacing a retryable error + " +
                "Reconnect instead of an infinite Attaching spinner. " +
                "session=${target?.sessionName}",
        )
        DiagnosticEvents.record(
            "connection",
            "attach_reveal_stuck",
            "session" to target?.sessionName,
            "hostId" to target?.hostId,
            "maxTicks" to connectedBlankWatchdogMaxTicks,
            "tickMs" to CONNECTED_BLANK_WATCHDOG_TICK_MS,
        )
        // Preserve the target so Reconnect has something to re-dial, then drop
        // the loading overlay and drive the honest-error reveal surface.
        connectingTarget = target
        refreshReconnectAvailability()
        _switchHidesTerminal.value = false
        val where = target?.let { " ${it.user}@${it.host}:${it.port}" } ?: ""
        setConnectionState(
            ConnectionState.Unreachable(
                "Session attach stalled$where. Tap Reconnect to retry.",
            ),
        )
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
        // Issue #830: a pane already seeded THIS attach ([panesSeededThisAttach]),
        // or with a seed round-trip still IN FLIGHT
        // ([panesSeedInFlightThisAttach]), has its authoritative `capture-pane`
        // snapshot applied or arriving. The pager settles on the active pane the
        // instant the route swaps — in the narrow window between the preload seed
        // STARTING and its snapshot landing, so a `visibleScreenIsBlank()` read here
        // is a drain-timing artifact racing the in-flight seed, NOT a
        // genuinely-never-seeded window. Re-seeding then is the redundant cold-open
        // reseed the #640 single-capture contract forbids (a 2nd `capture-pane`
        // round-trip + relayout = visible flicker on a high-latency link). The #662
        // heal this watchdog exists for is a window with NO seed this attach (a
        // reused pane whose attach-time seed was missing/wiped), so the skip is
        // scoped to seeded / in-flight panes only and never suppresses a real
        // black-window heal.
        if (paneId in panesSeededThisAttach || paneId in panesSeedInFlightThisAttach) return
        if (!pane.terminalState.visibleScreenIsBlank()) return
        bridgeScope.launch {
            // Re-check inside the coroutine: the pane may have painted between
            // the synchronous guard and dispatch (a late `%output` landed), or its
            // attach-time seed may have started in the meantime.
            if (paneId in panesSeededThisAttach || paneId in panesSeedInFlightThisAttach) return@launch
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
        maxAttempts: Int = SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS,
    ): Boolean {
        // Issue #830: publish "a seed for this pane is in flight" BEFORE the first
        // round-trip so a concurrent reseed net (the pager-settle
        // [reseedVisiblePaneIfBlank]) dedups against the in-flight seed instead of
        // racing it into a second redundant `capture-pane`. Cleared in the finally
        // so the genuine #662 black-window heal still fires once no seed is pending.
        panesSeedInFlightThisAttach.add(pane.paneId)
        try {
            var attempt = 0
            while (true) {
                if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return false
                if (client.disconnected.value) return false
                if (seedPaneFromCaptureOnce(client, pane, refreshGuard, recordMilestone)) {
                    return true
                }
                attempt += 1
                if (attempt >= maxAttempts) return false
                // Short backoff so a flaky-link empty capture is re-tried after the
                // channel has a moment to recover, without stalling a genuinely
                // empty pane's reveal for long. The guard re-check at the top of the
                // loop aborts immediately if the runtime was superseded mid-wait.
                delay(SEED_CAPTURE_EMPTY_RETRY_DELAY_MS)
            }
        } finally {
            panesSeedInFlightThisAttach.remove(pane.paneId)
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
        // EPIC #792 Slice E: feed the AUTHORITATIVE controller the REAL "seed/capture
        // landed" signal at the EXISTING point a capture-pane lands for a pane — placed
        // AFTER the panesSeededThisAttach write above, so it adds no write-path control
        // flow. Combined with the real TransportLive feedback this is how the controller
        // reaches Live from genuine signals (the active-pane landing promotes Attaching → Live).
        feedControllerSeedLanded(pane.paneId)
        // EPIC #687 P1: feed the id-tagged active-pane seed to the reveal state
        // machine at the SAME landing point. The captured `output` is non-empty
        // here (guarded above), so this reveals RevealState.Live ONLY for the
        // CURRENT target; a seed for a superseded target is dropped by id (never
        // paints the wrong session). FIRE-AND-OBSERVE after the existing side
        // effects — no write-path control-flow change.
        offerRevealSeed(pane.paneId, response.output.joinToString(separator = "\n"))
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
            durableSessionKey = target.durableSessionKey(),
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
            durableSessionKey = target.durableSessionKey(),
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
            durableSessionKey = target.durableSessionKey(),
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

    /**
     * Issue #894 (Slice C): true when [sessionId]'s durable recorded
     * `@ps_agent_kind` is [SessionAgentKind.Shell] — a POSITIVELY confirmed
     * shell. A blank session id, an absent/foreign session, or any agent kind
     * returns false (presumed-agent stays available; the #878 cure is intact).
     */
    private fun isConfirmedShellSession(sessionId: String): Boolean {
        val key = sessionId.trim()
        if (key.isEmpty()) return false
        return confirmedShellSessionIds.contains(key)
    }

    /**
     * Issue #894 (Slice C): apply a durable recorded-kind verdict for a session.
     * When [isShell] is true the session is marked CONFIRMED-SHELL and any
     * auto-seeded #878 "Loading conversation…" placeholder still up on its panes
     * is dropped IMMEDIATELY (a confirmed shell never lingers on the wrong
     * surface — this also kills the first-open flash where the seed fired before
     * the recorded-kind read landed). When [isShell] is false the session is
     * un-marked (an agent / re-classified session must regain its agent surface).
     * Republishes [confirmedShellPaneIds] either way.
     */
    private fun applyRecordedShellVerdict(sessionId: String, isShell: Boolean) {
        val key = sessionId.trim()
        if (key.isEmpty()) return
        val changed = if (isShell) {
            confirmedShellSessionIds.add(key)
        } else {
            confirmedShellSessionIds.remove(key)
        }
        if (isShell) {
            // Drop any auto-seeded placeholder that raced ahead of this verdict.
            val shellPaneIds = paneRows.values
                .filter { it.sessionId.trim() == key }
                .map { it.paneId }
            for (paneId in shellPaneIds) {
                if (isAutoSeededPlaceholderUp(paneId)) {
                    conversationLoadWatchdogJobs.remove(paneId)?.cancel()
                    paneAgentNullDetections.remove(paneId)
                    clearAgentDetectionForPane(paneId)
                }
            }
        }
        if (changed || isShell) refreshConfirmedShellPaneIds()
    }

    /**
     * Issue #894 (Slice C): recompute [confirmedShellPaneIds] from the current
     * pane rows and the [confirmedShellSessionIds] verdict set. Called on pane
     * reconcile and after a recorded-kind verdict is applied.
     */
    private fun refreshConfirmedShellPaneIds() {
        val next = paneRows.values
            .filter { confirmedShellSessionIds.contains(it.sessionId.trim()) }
            .map { it.paneId }
            .toSet()
        if (_confirmedShellPaneIds.value != next) {
            _confirmedShellPaneIds.value = next
        }
    }

    /**
     * Issue #878: seed the #818 open-time default tab placeholder for a
     * FRESHLY-ADDED presumed-agent pane, BEFORE the detection SSH round-trip
     * lands. This closes the residual black-screen window: the #818
     * Conversation-default was previously applied only in
     * [markAgentTailLive]'s `current == null` branch, which runs only AFTER
     * detection (~0.3s cache-hit, ~0.95s+ cold/foreign). Meanwhile the raw
     * `TmuxTerminalPager` is always mounted, so a fresh agent open with no
     * remembered status showed the BLACK Terminal for the whole detection
     * latency. Seeding the detection-less Conversation placeholder row here
     * makes the screen paint [ConversationDetectingPlaceholder] (the "Loading
     * conversation…" placeholder + its watchdog) instead, for Codex, Claude,
     * AND a foreign/no-guess pane.
     *
     * Strictly gated on `current == null` (no existing row): a real,
     * REMEMBERED status — a user who opted into Terminal, or a prior
     * Conversation row — is NEVER overwritten (no #815 mid-session yank).
     * [seedAgentConversationFromMemory] runs first, so a remembered window
     * already created the row by the time we get here and we no-op.
     *
     * When the open-time default is Terminal (the user opted out of the
     * Conversation default), there is nothing to seed: the raw Terminal IS the
     * intended pre-detection view, so we leave the row absent and the pager
     * shows the Terminal as before.
     *
     * The placeholder carries [AgentConversationUiState.autoSeededPlaceholder]
     * so [clearAgentDetectionForPane] can drop it if the pane turns out to be a
     * genuine shell (null detection) — a row the user never deliberately opened
     * must not linger on "Loading…"/"Failed". A real detection lands through
     * [markAgentTailLive]'s `current != null` path, which preserves this seeded
     * tab and clears the flag.
     */
    private fun seedPresumedAgentPlaceholder(pane: TmuxPaneState) {
        if (pane.windowId.isBlank()) return
        // Only the open-time Conversation default needs the placeholder; the
        // Terminal default's pre-detection view IS the raw Terminal.
        if (openTimeDefaultSessionTab() != SessionTab.Conversation) return
        // Issue #894 (Slice C): a pane the durable tree already knows is a plain
        // SHELL (recorded `@ps_agent_kind=shell`) must NOT get the #878
        // "Loading conversation…" placeholder — that is the wrong-surface flash.
        // The agent black-screen cure (#878) is preserved: a presumed-agent /
        // foreign / not-yet-classified pane still seeds. Only a POSITIVELY
        // confirmed shell is skipped.
        if (isConfirmedShellSession(pane.sessionId)) return
        var seeded = false
        _agentConversations.update { conversations ->
            // current == null: never overwrite a remembered/explicit status
            // (the #815 no-yank line). A row already exists for any remembered
            // window (seed-from-memory) or a prior in-session choice.
            if (conversations.containsKey(pane.paneId)) return@update conversations
            seeded = true
            conversations + (pane.paneId to AgentConversationUiState(
                detection = null,
                events = emptyList(),
                selectedTab = SessionTab.Conversation,
                syncStatus = AgentConversationSyncStatus.Live,
                // Issue #793: the detecting placeholder is "Loading
                // conversation…" + an armed watchdog so a never-arriving
                // detection/read resolves to a clear Failed state, not an
                // infinite spinner.
                loadState = ConversationLoadState.Loading,
                // Issue #878: this is an AUTO-seed, not a user tap — drop it on
                // a confirmed-shell (null) detection.
                autoSeededPlaceholder = true,
            ))
        }
        if (seeded) armConversationLoadWatchdog(pane.paneId)
    }

    /**
     * Issue #828 (perf): box for the per-session recorded-kind cache so an
     * ABSENT map entry means "not yet read" and a PRESENT entry whose [kind] is
     * null means "read = foreign session (no `@ps_agent_kind`)". Without the box
     * a `null` map value would be indistinguishable from "no entry", so a
     * foreign session would be re-probed (a wasted `readRecordedAgentKind`
     * round-trip) on every reconcile — defeating the cache for exactly the
     * sessions that have no recorded kind to find.
     */
    private class RecordedKindCacheEntry(val kind: AgentKind?)

    /**
     * Epic #821 slice A2: box for the per-session ONE-SHOT FOREIGN kind guess.
     * An ABSENT map entry means "not yet guessed" (the guess may still fire);
     * a PRESENT entry whose [kind] is null means "guessed = not an agent (a
     * shell) or unknown" — either way the guess has already fired and must NOT
     * be re-run on later reconciles (the one-shot discipline). [kind] holds the
     * guessed engine when the daemon classified the pane as an agent.
     */
    private class ForeignKindGuessEntry(val kind: AgentKind?)

    /**
     * Epic #821 slice A2: resolve a FOREIGN session's agent kind via the
     * host-side ONE-SHOT daemon guess (`pocketshell agents kind`), cached per
     * tmux session id so it fires AT MOST ONCE per foreign session. Returns the
     * cached guess immediately on a hit; on a miss it sends the pane's
     * `(pane_id, pane_pid)` to the daemon, caches the verdict, and returns it.
     * A null result means the daemon did not classify the pane as an agent (a
     * shell / unknown / no pid / tool missing) — the caller treats that as "no
     * conversation for this pane" but does NOT re-probe.
     */
    private suspend fun resolveForeignKindGuess(
        session: SshSession,
        sessionTarget: String,
        pane: TmuxPaneState,
    ): AgentKind? {
        val key = sessionTarget.trim()
        sessionForeignKindGuessCache[key]?.let { return it.kind }
        val panePid = pane.panePid.takeIf { it > 0L }
        // No pid → cannot ask the daemon. Cache the (null) verdict so we don't
        // re-probe a pane that never carries a pid, preserving the one-shot rule.
        if (panePid == null) {
            sessionForeignKindGuessCache.putIfAbsent(key, ForeignKindGuessEntry(null))
            return null
        }
        val verdict = agentKindRemoteSource.classify(
            session = session,
            panes = listOf(
                com.pocketshell.app.agents.AgentKindRemoteSource.PaneRef(
                    paneId = pane.paneId,
                    panePid = panePid,
                ),
            ),
        )
        val guessed = verdict[pane.paneId]?.kind
        sessionForeignKindGuessCache.putIfAbsent(key, ForeignKindGuessEntry(guessed))
        return sessionForeignKindGuessCache[key]?.kind
    }

    /**
     * Epic #821 slice A2: resolve the Conversation [AgentDetection] for a
     * FOREIGN session (no recorded `@ps_agent_kind`). The OLD output-parsing
     * kind detector is hard-cut (D22). Instead:
     *  1. ask the host daemon ONCE for its kind guess
     *     ([resolveForeignKindGuess], cached per session id), and
     *  2. if it guessed an agent kind, resolve the transcript SOURCE for that
     *     KNOWN kind via the SAME recorded-source path
     *     ([AgentConversationRepository.detectRecordedSessionForPane]) the
     *     sessions-we-launched path uses — so the source-path resolution surface
     *     foreign Conversation needs is preserved, only the KIND-guessing is
     *     replaced.
     * Returns `null` when the daemon did not classify the pane as an agent (the
     * pane has no Conversation). The guess fires at most once per session; a
     * later user confirm/pick writes a durable recorded kind and this path is
     * never re-entered for that session.
     */
    private suspend fun resolveForeignSessionDetection(
        session: SshSession,
        sessionTarget: String,
        pane: TmuxPaneState,
        cwd: String,
        tty: String,
        command: String,
    ): AgentDetection? {
        val guessedKind = resolveForeignKindGuess(
            session = session,
            sessionTarget = sessionTarget,
            pane = pane,
        ) ?: return null
        return agentRepository.detectRecordedSessionForPane(
            session = session,
            cwd = cwd,
            paneTty = tty,
            paneCommand = command,
            recordedKind = guessedKind,
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
        val sessionTarget = pane.sessionId
        paneAgentJobs[paneId] = bridgeScope.launch {
            // Issue #817 (Rank-1 measurement): stamp the FULL conversation-open
            // t0 the instant detection begins (before the first SSH exec on the
            // open path), so the span captures the serial detection round-trips
            // — the real network-bound cost the localhost fixture hides. The
            // window-read-only span (conversation_open) is still recorded inside
            // startAgentConversationForPane; `full - window` is the detection
            // chain cost. Consumed-and-removed on emit (or on null detection).
            paneConversationOpenStartedAtMs[paneId] = SystemClock.elapsedRealtime()
            // Epic #821 slice #3 (#825): if PocketShell RECORDED this session's
            // agent kind at launch (`@ps_agent_kind`), bind the Conversation
            // source to that recorded identity — kind from the record, source
            // computed from `(recordedKind, sessionId, cwd)` — instead of
            // re-guessing the kind by output/mtime detection. This is what kills
            // the #807 / #819 / #820 mis-detected-source cluster for OUR
            // sessions (detection mis-guessing the kind or picking a busier
            // sibling rollout). A FOREIGN session (option absent) keeps the
            // detection path below unchanged.
            //
            // Issue #186: the per-pane detection path (foreign sessions) scopes
            // a sibling window's JSONL log so it cannot light up the
            // Conversation tab on a non-agent window that just shares a cwd. The
            // legacy session-scoped [detect] is intentionally NOT called here —
            // its host-wide process scan was the root cause of the "Claude
            // detected" misattribution reported in the v0.2.8 feedback.
            //
            // When `tty` is blank (older tmux that does not emit
            // `#{pane_tty}`, or a freshly-discovered pane between
            // bootstrap and the first list-panes round-trip), the
            // repository returns null — preserving the old behaviour
            // that "no signal = no detection" for this pane.
            //
            // Issue #828 (perf): two levers collapse the recorded-open chain.
            //  (1) Per-session cache: the recorded kind is fixed for the life of
            //      the session, so we resolve `@ps_agent_kind` at most ONCE per
            //      session id and reuse it for every later pane + reconcile — the
            //      steady-state open issues ZERO standalone `readRecordedAgentKind`
            //      execs (AC: detection chain ~0).
            //  (2) Single-round-trip first resolve: on a cache MISS we fold the
            //      `@ps_agent_kind` read INTO the candidate-enumeration exec
            //      ([resolveRecordedSessionOpen]) so even the FIRST open of a
            //      session pays one chain round-trip (kind+candidates) before the
            //      window read instead of two serial ones — the ~512 ms→~256 ms
            //      pre-window cut that lets the cold open clear the <0.3s gate.
            // Codex still needs its second `/proc/<pid>/fd` owned-rollout pass
            // (no session-id-in-path), so it falls through to the existing
            // recorded resolve. A FOREIGN session (no recorded kind) keeps the
            // unchanged foreign detection path.
            //  (3) Window fold: for a recorded CLAUDE cache-miss open, the
            //      single-round-trip resolve ALSO prefetches the first transcript
            //      window in that same exec, so the cold open is ONE SSH
            //      round-trip total (kind + source + window) — cold ≈ warm. The
            //      prefetched window is handed to [startAgentConversationForPane]
            //      so it skips its own window-read exec.
            val cachedKind = sessionRecordedKindCache[sessionTarget.trim()]
            var prefetchedWindow: ConversationEventsWindow? = null
            val probeResult = try {
                val detection = if (cachedKind != null) {
                    // Cache HIT — kind already known, no `@ps_agent_kind` exec.
                    val recordedKind = cachedKind.kind
                    if (recordedKind != null) {
                        agentRepository.detectRecordedSessionForPane(
                            session = session,
                            cwd = cwd,
                            paneTty = tty,
                            paneCommand = command,
                            recordedKind = recordedKind,
                        )
                    } else {
                        // Epic #821 slice A2: FOREIGN session (no recorded kind).
                        // Output-parsing kind detection is hard-cut (D22); ask the
                        // host daemon ONCE for its kind guess (cached per session
                        // id), then resolve the Conversation SOURCE for the guessed
                        // kind via the SAME recorded-source path. No guess → no
                        // agent → null detection (the guess does not re-fire).
                        resolveForeignSessionDetection(
                            session = session,
                            sessionTarget = sessionTarget,
                            pane = pane,
                            cwd = cwd,
                            tty = tty,
                            command = command,
                        )
                    }
                } else {
                    // Cache MISS — read the kind folded into the candidate
                    // enumeration (+ the Claude window), then cache the verdict.
                    val open = agentRepository.resolveRecordedSessionOpen(
                        session = session,
                        sessionTarget = sessionTarget,
                        cwd = cwd,
                        paneTty = tty,
                        paneCommand = command,
                    )
                    sessionRecordedKindCache.putIfAbsent(
                        sessionTarget.trim(),
                        RecordedKindCacheEntry(open.recordedKind),
                    )
                    when {
                        open.recordedKind == null ->
                            // Epic #821 slice A2: FOREIGN session — one-shot daemon
                            // kind guess + recorded-source resolution (see the
                            // cache-hit foreign arm above). No output parsing (D22).
                            resolveForeignSessionDetection(
                                session = session,
                                sessionTarget = sessionTarget,
                                pane = pane,
                                cwd = cwd,
                                tty = tty,
                                command = command,
                            )
                        open.needsCodexResolution ->
                            // Recorded Codex needs the second owned-rollout pass.
                            agentRepository.detectRecordedSessionForPane(
                                session = session,
                                cwd = cwd,
                                paneTty = tty,
                                paneCommand = command,
                                recordedKind = open.recordedKind,
                            )
                        // Recorded Claude/OpenCode: resolved in the one round-trip.
                        // Claude also carries the prefetched first window.
                        else -> {
                            prefetchedWindow = open.prefetchedWindow
                            open.detection
                        }
                    }
                }
                AgentDetectionProbeResult.Resolved(detection)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Exception) {
                AgentDetectionProbeResult.Unavailable(t)
            }
            if (!isCurrentRuntime(guard)) {
                // Issue #817: a stale runtime never reaches markAgentTailLive, so
                // drop the open t0 to avoid attributing the next pane's open to it.
                paneConversationOpenStartedAtMs.remove(paneId)
                return@launch
            }
            if (probeResult is AgentDetectionProbeResult.Unavailable) {
                // Issue #897: a failed/degraded SSH probe is not evidence that
                // the agent exited. Keep the remembered Conversation row and
                // retry instead of feeding the clean-null exit confirmation path.
                paneConversationOpenStartedAtMs.remove(paneId)
                handleUnavailableAgentDetection(pane, guard)
                return@launch
            }
            val detection = (probeResult as AgentDetectionProbeResult.Resolved).detection
            if (detection == null) {
                // Issue #817: no detection → no conversation open; clear the t0.
                paneConversationOpenStartedAtMs.remove(paneId)
                handleNullAgentDetection(pane, guard)
                return@launch
            }
            // A real detection cancels any in-flight exit-confirmation count.
            paneAgentNullDetections.remove(paneId)
            // Issue #828: hand the recorded-Claude prefetched window straight to
            // the conversation open so it skips its own window-read round-trip —
            // the cold open is then ONE SSH round-trip total.
            startAgentConversationForPane(
                session = session,
                paneId = paneId,
                detection = detection,
                refreshGuard = guard,
                prefetchedWindow = prefetchedWindow,
            )
        }
    }

    private sealed class AgentDetectionProbeResult {
        data class Resolved(val detection: AgentDetection?) : AgentDetectionProbeResult()
        data class Unavailable(val cause: Throwable) : AgentDetectionProbeResult()
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
        // Issue #878: while an AUTO-seeded detecting placeholder is up (the #818
        // black-screen cure painted at pane-add, before detection landed), a
        // FIRST transient null is almost always the detection-not-yet-warm race
        // — the agent's JSONL log / process is not observable on the fresh
        // attach for a beat. Dropping the placeholder on that first null would
        // FLASH the black raw Terminal during the very detection window #878
        // exists to bridge. So we hold the placeholder and re-confirm, exactly
        // like a remembered agent, and only tear it down after
        // [AGENT_EXIT_CONFIRMATIONS] consecutive nulls (a genuine shell pane).
        if (isAutoSeededPlaceholderUp(paneId)) {
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
     * Issue #897: a degraded/unavailable probe is not a clean "no agent"
     * verdict. It must not increment the exit confirmation counter, clear the
     * row, or forget [agentSessionMemory]; it only asks detection to retry once
     * the channel is usable again.
     */
    private fun handleUnavailableAgentDetection(pane: TmuxPaneState, guard: RuntimeRefreshGuard): Boolean {
        val paneId = pane.paneId
        paneAgentNullDetections.remove(paneId)
        val row = _agentConversations.value[paneId]
        if (row?.detection != null) {
            updateAgentConversation(paneId) { current ->
                if (current.syncStatus == AgentConversationSyncStatus.LogUnavailable) {
                    current
                } else {
                    current.copy(syncStatus = AgentConversationSyncStatus.LogUnavailable)
                }
            }
            scheduleAgentDetectionRecheck(pane, guard)
            return false
        }
        if (row?.autoSeededPlaceholder == true) {
            scheduleAgentDetectionRecheck(pane, guard)
            return false
        }
        return false
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
            durableSessionKey = target.durableSessionKey(),
        ) ?: return false
        // Only defer while the seeded agent UI is actually still showing; once
        // detection has confirmed an exit and the row is gone, there is
        // nothing to protect.
        return _agentConversations.value[paneId]?.detection != null
    }

    /**
     * Issue #878: true when an AUTO-seeded detecting placeholder is currently up
     * for [paneId] — a detection-less Conversation row seeded at pane-add (the
     * #818 black-screen cure) whose live detection has not yet landed. While
     * this is up, a transient null verdict must be re-confirmed (not acted on)
     * so the placeholder does not flash the black raw Terminal mid-detection.
     * False once a real detection lands (the flag is cleared) or the row is
     * gone.
     */
    private fun isAutoSeededPlaceholderUp(paneId: String): Boolean {
        val row = _agentConversations.value[paneId] ?: return false
        return row.autoSeededPlaceholder && row.detection == null
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
        var rowDropped = false
        updateAgentConversation(paneId) { current ->
            when {
                // A row that carries a real detection: the agent exited — drop
                // it so the Conversation tab disappears for this window.
                current.detection != null -> {
                    rowDropped = true
                    null
                }
                // Issue #878: an AUTO-seeded detection-less placeholder (the
                // #818 black-screen cure seeded at pane-add) whose detection
                // came back null is a genuine SHELL pane the user never
                // deliberately opened — drop it so it does not linger on
                // "Loading conversation…" → "Failed". This is the auto-seed's
                // own teardown; it is NOT a #815 yank (no user-chosen tab is
                // being changed — the user never picked this view).
                current.autoSeededPlaceholder -> {
                    rowDropped = true
                    null
                }
                // A USER-tapped detection-less placeholder (#778): the user
                // deliberately opened the Conversation surface, so KEEP it and
                // let its watchdog resolve "Loading…" to a clear Failed state.
                else -> current
            }
        }
        // Issue #793: a removed row has no surface to load into — stop its
        // watchdog. A KEPT (user-tapped #778) detection-less placeholder row
        // intentionally retains its watchdog so the "Loading conversation…"
        // state still resolves to a clear Failed terminal state.
        if (rowDropped) cancelConversationLoadWatchdog(paneId)
    }

    private suspend fun startAgentConversationForPane(
        session: SshSession,
        paneId: String,
        detection: AgentDetection,
        refreshGuard: RuntimeRefreshGuard? = null,
        // Issue #793: tail window size. The first-OPEN path uses the small
        // first-paint budget for a fast tail; the stream-RETRY path
        // ([retryAgentConversationForPane]) passes the user's currently-loaded
        // window size so a re-fetch does not silently shrink an already-paged
        // transcript back to the first-paint tail.
        maxMessages: Int = FIRST_PAINT_MESSAGE_BUDGET,
        // Issue #828 (perf): a window ALREADY fetched in the recorded-Claude
        // single-round-trip resolve. When present the window-read SSH exec is
        // skipped entirely — the cold open is ONE round-trip total (kind + source
        // + window), making it ≈ the warm switch. Null on every other path (the
        // standard window read runs).
        prefetchedWindow: ConversationEventsWindow? = null,
    ) {
        // Issue #793: a real detection landed for this pane; the transcript is
        // now actively loading. Move the row into the Loading state so the
        // Conversation tab shows "Loading conversation…" (not the old
        // "Waiting for agent…") and the detection-pending watchdog stops.
        markConversationLoading(paneId)
        // Issue #817 (slice 1): time the cold-open path from "agent detected,
        // first transcript read begins" to "first parsed events are live in the
        // UI state" (the markAgentTailLive below). This is the authoritative
        // conversation_open latency span; a connected test / logcat snapshots
        // it for the <0.3s gate.
        val openStartedAtMs = SystemClock.elapsedRealtime()
        // Issue #793: tail-first windowed read. Fetch only the most recent
        // [maxMessages] messages so the tail paints quickly instead of blocking
        // on the whole history. Older messages page in lazily on upward scroll
        // (loadOlderAgentConversationEvents). A read failure is surfaced as a
        // terminal Failed state — never an infinite spinner — via the explicit
        // catch below.
        //
        // Issue #817: the window read already yields the file's line count at
        // read time (ConversationEventsWindow.tailStartLine), which is exactly
        // the `fromLineExclusive` cursor the follow-tail needs. The cold-open
        // path therefore no longer pays a separate `lineCount` SSH round-trip
        // before the read — one fewer serial exec on every fresh open.
        var loadFailed = false
        val window = try {
            // Issue #828: a recorded-Claude open already fetched the first window
            // in the resolve exec — use it and skip the read round-trip.
            prefetchedWindow ?: agentRepository.readEventsWindow(
                session = session,
                detection = detection,
                maxMessages = maxMessages,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (t: Exception) {
            recordAgentConversationTailStatus(
                paneId = paneId,
                detection = detection,
                status = AgentConversationSyncStatus.LogUnavailable,
                cause = t,
                reason = "initial_window_read",
            )
            loadFailed = true
            ConversationEventsWindow(emptyList(), hasMoreOlder = false)
        }
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return
        val initialEvents = window.events
        val tailStartLine = window.tailStartLine
        val markLiveAtMs = SystemClock.elapsedRealtime()
        markAgentTailLive(paneId, detection, initialEvents)
        TmuxSessionLatencyTelemetry.record(
            name = CONVERSATION_OPEN_LATENCY_OPERATION,
            durationMs = markLiveAtMs - openStartedAtMs,
            sessionName = activeTarget?.sessionName,
            paneId = paneId,
            trigger = activeAttachMilestone?.trigger,
            detail = "agent=${detection.agent.name} events=${initialEvents.size} " +
                "failed=$loadFailed tail_start_line=$tailStartLine",
        )
        // Issue #817 (Rank-1 measurement): emit the FULL open span — detection
        // start → first events live — so the serial detection round-trips are
        // visible alongside the window-read-only span. `full - window` is the
        // detection-chain cost. The t0 is the stamp set in
        // startAgentDetectionForPane; absence means this open was driven by a
        // path that did not stamp one (e.g. a restore/refresh re-tail), in which
        // case we skip the full span rather than report a bogus number.
        paneConversationOpenStartedAtMs.remove(paneId)?.let { detectionStartedAtMs ->
            TmuxSessionLatencyTelemetry.record(
                name = CONVERSATION_OPEN_FULL_LATENCY_OPERATION,
                durationMs = markLiveAtMs - detectionStartedAtMs,
                sessionName = activeTarget?.sessionName,
                paneId = paneId,
                trigger = activeAttachMilestone?.trigger,
                detail = "agent=${detection.agent.name} events=${initialEvents.size} " +
                    "failed=$loadFailed window_read_ms=${markLiveAtMs - openStartedAtMs}",
            )
        }
        // Issue #793: record the terminal load outcome for the pane so the
        // screen renders the right state — Ready (feed), Empty (clear "no
        // events" terminal state), or Failed (clear error + retry).
        markConversationLoadOutcome(
            paneId = paneId,
            detection = detection,
            loadedEvents = initialEvents,
            hasMoreOlder = window.hasMoreOlder,
            failed = loadFailed,
        )
        // Issue #160: OpenCode now tails its JSONL via `session.tail`
        // identically to Claude and Codex. No more polling branch — the
        // tmux pane gets the same real-time refresh as the raw-SSH route.
        // Issue #576: use the batched/debounced tail so a Codex `/new`
        // (or any large JSONL replay) collapses into a handful of
        // reconcile + StateFlow-emit cycles instead of one per line.
        val followJob = agentRepository.tailEventsBatchedFromLine(session, detection, tailStartLine) { batch ->
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
        // Issue #793: tail-first windowed read on reconnect-restore too. On a
        // read failure we keep the previously-restored events (the reconnect
        // fallback) rather than blanking the pane.
        var restoreHasMoreOlder = restored.hasMoreOlderEvents
        val initialEvents = recoverAgentConversationStartupRead(
            paneId = paneId,
            detection = detection,
            operation = "restore_initial_read",
            fallback = restored.events,
        ) {
            agentRepository.readEventsWindow(
                session = session,
                detection = detection,
                maxMessages = FIRST_PAINT_MESSAGE_BUDGET,
            ).also { restoreHasMoreOlder = it.hasMoreOlder }.events
        }
        if (!isCurrentRuntime(refreshGuard)) return
        markRestoredAgentTailLive(paneId, detection, initialEvents)
        updateAgentConversation(paneId) { current ->
            current.copy(
                loadState = ConversationLoadState.Ready,
                hasMoreOlderEvents = restoreHasMoreOlder,
            )
        }
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

    public suspend fun uploadQueuedAttachmentSidecars(
        sidecars: List<LocalAttachmentSidecarRef>,
    ): Result<List<String>> {
        DiagnosticEvents.record("action", "tmux_outbound_sidecar_upload_start", "count" to sidecars.size)
        if (sidecars.isEmpty()) return Result.success(emptyList())
        val session = awaitLiveSessionForAttachment()
            ?: return Result.failure(IllegalStateException("No live SSH session for attachment upload."))
        if (!session.isConnected) {
            return Result.failure(SshException("SSH session is not connected"))
        }
        val target = activeTarget
        val scopeKey = when (target) {
            null -> "tmux-session"
            else -> "host-${target.hostId}-${target.sessionName}"
        }
        val safeScope = PromptAttachmentStager.safeScopeSegment(scopeKey)
        val remoteDir = "${PromptAttachmentStager.REMOTE_DIRECTORY}/$safeScope"
        val displayDir = "~/$remoteDir"
        val timestamp = ShareUploader.formatTimestamp(sidecars.minOf { it.createdAtMs })
        return try {
            val mkdir = session.exec("mkdir -p \"\$HOME/$remoteDir\"")
            if (mkdir.exitCode != 0) {
                val detail = mkdir.stderr.ifBlank { mkdir.stdout }.trim()
                return Result.failure(
                    SshException("Could not create attachment directory: ${detail.ifBlank { "mkdir failed" }}"),
                )
            }
            val uploadedPaths = sidecars.mapIndexed { index, ref ->
                val local = File(ref.localPath)
                if (!local.exists()) {
                    throw SshException("Queued attachment no longer exists: ${ref.displayName}")
                }
                val sanitised = FilenameSanitiser.sanitise(
                    ref.displayName,
                    defaultExtension = ShareUploader.extensionForMimeType(ref.mimeType),
                )
                val remoteName = PromptAttachmentStager.composeAttachmentName(timestamp, index, sanitised)
                val remotePath = "$remoteDir/$remoteName"
                session.uploadFile(local, remotePath)
                "$displayDir/$remoteName"
            }
            DiagnosticEvents.record(
                "action",
                "tmux_outbound_sidecar_upload_success",
                "count" to uploadedPaths.size,
                "scope" to scopeKey,
            )
            Result.success(uploadedPaths)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (t: Throwable) {
            DiagnosticEvents.record(
                "action",
                "tmux_outbound_sidecar_upload_fail",
                "count" to sidecars.size,
                "scope" to scopeKey,
                "cause" to t.javaClass.simpleName,
                "message" to t.message,
            )
            Result.failure(if (t is SshException) t else SshException("Attachment upload failed: ${t.message}", t))
        }
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
        // EPIC #687 slice 3 / #785: TRUST THE WITHIN-GRACE OWNER, DON'T REDIAL.
        //
        // The file picker (`OpenMultipleDocuments`) is a separate-process Activity,
        // so launching it backgrounds PocketShell (ProcessLifecycle `ON_STOP`) and
        // returning foregrounds it (`ON_START`). On a quick round-trip the App-level
        // background-grace window (#450, the SINGLE grace owner) holds the `-CC`
        // lease warm and the within-grace foreground heal
        // ([launchForegroundHealWithinGrace]) is ALREADY re-promoting the session
        // back to Live — silently, with no band. That heal is ASYNC, so the instant
        // this attach callback fires (on the same foreground return) the synchronous
        // [liveSessionForAttachmentOrNull] snapshot can momentarily read not-Connected
        // / a transiently-null `sessionRef` while the heal is mid-flight.
        //
        // Calling [reconnect] here in that window is the #785 bug: it fires a LOUD
        // `connect(trigger = Reconnect)` that raises the Connecting overlay + reseeds
        // the viewport (the blank-then-restore the maintainer reported), racing the
        // silent heal it should have trusted. So when the lease is still WARM (the
        // same [liveLeaseKeys] predicate [canReseedWithinGraceForeground] uses), we
        // do NOT redial — we just POLL for the heal to land. We only fall back to the
        // connect-on-action [reconnect] when the lease is genuinely COLD (grace
        // elapsed / socket truly dead), where a redial is the correct recovery. This
        // is the attach-flow alignment with the controller-owned reconnect ladder
        // (slice 3): the single grace owner / ladder decides reconnect, not a
        // connect-on-action snapshot reached around it.
        if (!isAttachmentLeaseWarm()) {
            // Cold lease: connect-on-action, drive a (re)connect like Send does.
            // No-op when a connect is already in flight, so it just falls through
            // to the wait.
            reconnect()
        }
        return withTimeoutOrNull(ATTACH_SESSION_WAIT_TIMEOUT_MS) {
            while (currentCoroutineContext().isActive) {
                liveSessionForAttachmentOrNull()?.let { return@withTimeoutOrNull it }
                // Re-check the lease each poll: a within-grace heal may complete OR
                // the grace window may elapse (lease goes cold) while we wait. If it
                // goes cold and no connect is in flight, kick the connect-on-action
                // recovery so a genuinely-dead link still resolves within the bound.
                if (!isAttachmentLeaseWarm() && !isConnectInFlight()) {
                    reconnect()
                }
                delay(ATTACH_SESSION_WAIT_POLL_MS)
            }
            null
        }
    }

    /**
     * EPIC #687 slice 3 / #785: is the active target's SSH lease still WARM — i.e.
     * the within-grace owner is holding the connection and a silent heal will bring
     * the session back without a redial? Mirrors the [liveLeaseKeys] warm-snapshot
     * predicate [canReseedWithinGraceForeground] uses, but deliberately does NOT
     * require a non-null `clientRef`/`sessionRef`: those are exactly what go
     * transiently null while the within-grace heal re-opens the `-CC` channel, and
     * gating on them would defeat the whole "trust the warm lease" fix. The lease
     * key membership is the authoritative "the transport is still leased / warm"
     * signal the single grace owner maintains.
     */
    private fun isAttachmentLeaseWarm(): Boolean {
        val target = activeTarget ?: connectingTarget ?: return false
        return liveLeaseKeys.contains(target.toSshLeaseTarget().leaseKey)
    }

    /**
     * EPIC #687 slice 3 / #785 (deterministic test seam): would the attachment wait
     * REDIAL (`reconnect()`) for the current state, or POLL the warm lease? This is the
     * exact gate `awaitLiveSessionForAttachment` consults: `true` only when the lease is
     * genuinely COLD. On the OLD (base) code the attach wait redialed UNCONDITIONALLY,
     * so this gate did not exist — the #785 bug. A JVM unit test drives the VM into a
     * warm-lease + transiently-not-Connected state and asserts this returns `false`
     * (poll, do not redial), the red→green discriminator for the fix.
     */
    @androidx.annotation.VisibleForTesting
    internal fun attachmentWaitWouldRedialForTest(): Boolean = !isAttachmentLeaseWarm()

    /**
     * EPIC #687 slice 3 / #785: true when a connect/reconnect attempt is already in
     * flight, so the attach wait must not kick another [reconnect] on top of it.
     */
    private fun isConnectInFlight(): Boolean =
        connectJob?.isActive == true ||
            autoReconnectJob?.isActive == true ||
            inlineConnectionStatus is ConnectionStatus.Connecting ||
            inlineConnectionStatus is ConnectionStatus.Reconnecting ||
            inlineConnectionStatus is ConnectionStatus.Switching

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
                // ISSUE #872 / #785 twin: TRUST THE WARM LEASE ON SEND, DON'T REDIAL.
                //
                // This is the un-applied twin of the #785 attachment fix
                // ([awaitLiveSessionForAttachment] above). The maintainer reported that
                // tapping Send on a STABLE wifi connection flashed "Reconnecting" for ~1s
                // and then "Retry" — and the staged attachment was gone. Root cause: this
                // wait read a SYNCHRONOUS "Connected right now?" snapshot
                // ([liveTmuxClientForSendOrNull] gates on [inlineConnectionStatus] +
                // [clientRef]) and, finding it TRANSIENTLY not-Connected (a within-grace
                // heal mid-flight, or the status momentarily off after a quick bg/fg
                // round-trip), UNCONDITIONALLY fired a fresh-lease `onManualReconnect()` —
                // a LOUD `connect(trigger = Reconnect)` that tore the transport (the
                // spurious flap) AND wiped the staged attachment.
                //
                // The fix mirrors slice-3: when the active target's SSH lease is still
                // WARM ([liveLeaseKeys] membership — the single grace owner is holding the
                // `-CC` connection and a silent heal is already re-promoting it), do NOT
                // redial. POLL for the live client to land instead (the loop below), so a
                // Send on a stable/warm connection reuses the live lease with no flap. We
                // only fall back to the connect-on-action reconnect when the lease is
                // genuinely COLD (grace elapsed / socket truly dead), where a redial is the
                // correct recovery.
                if (isSendLeaseWarm()) {
                    // Warm lease: POLL the silent heal (fall through to the wait below);
                    // do NOT fire a reconnect on a stable connection.
                } else {
                    // EPIC #792 Slice C: route through the single [TransportEffects] reconnect
                    // owner — the inline direct call is deleted (D22 hard-cut, one entrypoint).
                    val reconnectJob = transportEffects.onManualReconnect().job
                        ?: return liveTmuxClientForSendOrNull()
                    reconnectJob.join()
                }
            }
        }
        return withTimeoutOrNull(SEND_SESSION_WAIT_TIMEOUT_MS) {
            while (currentCoroutineContext().isActive) {
                liveTmuxClientForSendOrNull()?.let { return@withTimeoutOrNull it }
                // Re-check the lease each poll: a within-grace heal may complete OR the
                // grace window may elapse (lease goes cold) while we wait. If it goes cold
                // and no connect is in flight, kick the connect-on-action recovery so a
                // genuinely-dead link still resolves within the bound (mirrors the
                // attachment wait's cold-lease fallback).
                if (!isSendLeaseWarm() && !isConnectInFlight()) {
                    transportEffects.onManualReconnect()
                }
                delay(SEND_SESSION_WAIT_POLL_MS)
            }
            null
        }
    }

    /**
     * ISSUE #872 / #785 twin: is the active target's SSH lease still WARM on the Send
     * path — i.e. the within-grace owner is holding the connection and a silent heal
     * will bring the session back without a redial? Identical predicate to the
     * attachment path's [isAttachmentLeaseWarm] (the [liveLeaseKeys] warm snapshot the
     * single grace owner maintains), deliberately NOT requiring a non-null
     * `clientRef`/`sessionRef`: those are exactly what go transiently null while the
     * within-grace heal re-opens the `-CC` channel, and gating on them would defeat the
     * "trust the warm lease" fix.
     */
    private fun isSendLeaseWarm(): Boolean {
        val target = activeTarget ?: connectingTarget ?: return false
        return liveLeaseKeys.contains(target.toSshLeaseTarget().leaseKey)
    }

    /**
     * ISSUE #872 (deterministic test seam): would the Send wait REDIAL (a fresh-lease
     * `onManualReconnect()`) for the current state, or POLL the warm lease? This is the
     * exact gate `awaitLiveTmuxClientForSend` consults. On the OLD (base) code the Send
     * wait redialed UNCONDITIONALLY whenever the status was not already
     * Connecting/Reconnecting/Switching — the #872 spurious-flap bug. A JVM unit test
     * drives the VM into a warm-lease + transiently-not-Connected state and asserts this
     * returns `false` (poll, do not redial), the red→green discriminator for the fix.
     * The on-device journey is `SendNoReconnectE2eTest`.
     */
    @androidx.annotation.VisibleForTesting
    internal fun sendWaitWouldRedialForTest(): Boolean = !isSendLeaseWarm()

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
            awaitAgentPasteIngestedBeforeSubmit(client, paneId, payload, agent)
            client.sendCommand("send-keys -t $paneId Enter")
                .throwIfTmuxError("submit pasted agent input")
        }
    }

    /**
     * Issue #869: ack-gate the submit Enter on the pasted composer text actually
     * landing in the agent's input, instead of the pre-#869 blind fixed sleep
     * ([com.pocketshell.app.settings.AppSettings.agentSubmitEnterDelayMs]) that
     * was too short under real RTT. The maintainer's on-device symptom — "most
     * of the time when I click Send it's not really sending; I have to press
     * Enter after" — was an Enter that raced ahead of the agent TUI finishing
     * its paste ingestion, leaving the message sitting unsent in the input line.
     *
     * The gate works in two parts:
     *
     * 1. **Minimum floor (the #526 setting, kept tunable).** Honour the
     *    configured [com.pocketshell.app.settings.AppSettings.agentSubmitEnterDelayMs]
     *    (Codex's [CODEX_AGENT_SUBMIT_DELAY_MS] floor still applies) as the
     *    SHORTEST time before Enter. A zero/low configured value no longer means
     *    "race the Enter" — the ack poll below still waits for confirmation.
     *
     * 2. **Ack on the paste landing (the #869 correctness fix).** Poll
     *    `capture-pane -p` for the payload to appear in the pane's visible text.
     *    Press Enter the instant a capture confirms the paste is in. This is
     *    RTT-adaptive for free — each capture is a round-trip, so a high-latency
     *    host naturally waits longer for the confirming capture to return.
     *    Bounded by [AGENT_SUBMIT_ACK_TIMEOUT_MS]: if the payload never shows
     *    (an unrecognised TUI rendering, or `capture-pane` keeps failing) we
     *    fall back to pressing Enter anyway — never a hung Send.
     *
     * 3. **Hardened needle-miss fallback (#869, reviewer BLOCKED follow-up).**
     *    The fallback is the EXACT failure surface for the maintainer's report:
     *    if the needle never matched a real agent's reflowed input box, the
     *    pre-#869 path would press Enter after only the ~150ms floor — the very
     *    race that left messages unsent. So when the ack is NOT observed, the
     *    submit Enter is held to an ADEQUATE working floor instead:
     *    `max(minFloor, AGENT_SUBMIT_ACK_FALLBACK_FLOOR_MS + measuredRtt)`,
     *    where `measuredRtt` is the longest `capture-pane` round-trip observed
     *    during the polls (so a high-latency host waits proportionally longer).
     *    The worst case is therefore a WORKING delay, never the 150ms that
     *    caused the report. The best case is unchanged — an observed ack
     *    submits the instant ingestion is seen.
     */
    private suspend fun awaitAgentPasteIngestedBeforeSubmit(
        client: TmuxClient,
        paneId: String,
        payload: String,
        agent: AgentKind,
    ) {
        val configured = agentSubmitEnterDelayMsOverrideForTest
            ?: settingsRepository?.settings?.value?.agentSubmitEnterDelayMs
            ?: com.pocketshell.app.settings.AppSettings.DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS
        val minFloorMs = if (agent == AgentKind.Codex) {
            maxOf(configured.toLong(), CODEX_AGENT_SUBMIT_DELAY_MS)
        } else {
            configured.toLong()
        }

        val gateStartMs = agentSubmitNowMs()

        // An empty payload (e.g. a bare-Enter submit) has nothing to confirm —
        // just honour the floor and return so we never poll for a missing needle.
        val ackNeedle = agentSubmitAckNeedle(payload)
        if (ackNeedle == null) {
            if (minFloorMs > 0L) delay(minFloorMs)
            return
        }

        // Pre-floor: never submit before the configured/Codex floor even if the
        // capture confirms instantly (preserves the #526 tunable minimum).
        if (minFloorMs > 0L) delay(minFloorMs)

        // Bound the ack poll by iteration count rather than a wall clock so the
        // loop is deterministic under virtual time AND cannot spin forever (a
        // `SystemClock` reading is a no-op 0 under the unit-test default-values
        // runtime, which would make an elapsed-based bound never trip — #869
        // OOM). Each capture is itself a round-trip, so this stays RTT-adaptive.
        val ackTimeoutMs = agentSubmitAckTimeoutMsOverrideForTest ?: AGENT_SUBMIT_ACK_TIMEOUT_MS
        val maxPolls = (ackTimeoutMs / AGENT_SUBMIT_ACK_POLL_INTERVAL_MS)
            .toInt()
            .coerceAtLeast(1)
        var poll = 0
        // The longest single `capture-pane` round-trip seen so far — the RTT
        // addend for the hardened fallback floor (#869).
        var maxCaptureRttMs = 0L
        while (true) {
            if (client.disconnected.value) return
            val captureStartMs = agentSubmitNowMs()
            val visible = agentPaneShowsPayload(client, paneId, ackNeedle)
            maxCaptureRttMs = maxOf(maxCaptureRttMs, agentSubmitNowMs() - captureStartMs)
            if (visible) {
                // The paste is visible in the pane — the agent has ingested it.
                // Press Enter now (caller does the send-keys Enter).
                DiagnosticEvents.record(
                    "action",
                    "agent_submit_ack",
                    "pane" to paneId,
                    "result" to "ack_observed",
                    "polls" to poll,
                )
                return
            }
            poll += 1
            if (poll >= maxPolls) {
                // Hardened needle-miss fallback (#869): we could not confirm
                // ingestion. Do NOT degrade to the short floor that caused the
                // missed-submit. Hold the Enter until an ADEQUATE working floor
                // has elapsed since the gate started:
                //   max(minFloor, FALLBACK_FLOOR + maxCaptureRtt).
                val fallbackFloorMs = maxOf(
                    minFloorMs,
                    com.pocketshell.app.settings.AppSettings.AGENT_SUBMIT_ACK_FALLBACK_FLOOR_MS +
                        maxCaptureRttMs,
                )
                val elapsedMs = agentSubmitNowMs() - gateStartMs
                val remainingMs = fallbackFloorMs - elapsedMs
                if (remainingMs > 0L) delay(remainingMs)
                DiagnosticEvents.record(
                    "action",
                    "agent_submit_ack",
                    "pane" to paneId,
                    "result" to "fallback_floor",
                    "polls" to poll,
                    "fallbackFloorMs" to fallbackFloorMs,
                )
                return
            }
            delay(AGENT_SUBMIT_ACK_POLL_INTERVAL_MS)
        }
    }

    /**
     * Issue #869 (reviewer BLOCKED-G4 follow-up): derive the substring to look
     * for in the pane to confirm the composer paste landed. The on-device fixture
     * (#869 connected proof) showed that an agent input box does TWO things to a
     * long prompt that break a naive whole-line substring match:
     *
     *  1. **It reflows/wraps the line**, and `capture-pane -p` returns the
     *     wrapped rows; joining them inserts a separator at the wrap boundary —
     *     which can land MID-WORD (`...against the...` rendered as `against t`
     *     + `he new...`). A substring needle containing `against the` then misses.
     *  2. **The HEAD of a very long prompt scrolls off** the top of the visible
     *     viewport, so only the TAIL near the cursor is captured.
     *
     * Both are defeated by (a) stripping ALL whitespace from both needle and
     * visible text (so a wrap-boundary space can never split a token), and (b)
     * matching on the TAIL of the payload near the cursor (the part that stays
     * visible) — [AGENT_SUBMIT_ACK_NEEDLE_TAIL_CHARS] whitespace-stripped chars.
     * Returns null when there is nothing meaningful to confirm (blank payload).
     */
    private fun agentSubmitAckNeedle(payload: String): String? {
        val lastLine = payload
            .split('\n')
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?: return null
        // Strip ALL whitespace so a wrap-boundary separator can't split a token,
        // then take the tail near the cursor (the part that stays on-screen).
        val stripped = lastLine.replace(WHITESPACE_RUN_REGEX, "")
        if (stripped.isEmpty()) return null
        return stripped.takeLast(AGENT_SUBMIT_ACK_NEEDLE_TAIL_CHARS)
    }

    /**
     * Issue #869: `capture-pane -p` the pane and report whether [needle] is
     * present in its visible text. Both the visible text and the needle are
     * whitespace-STRIPPED (see [agentSubmitAckNeedle]) so a wrapped/reflowed
     * input box — whose join inserts a separator at the wrap boundary — still
     * matches. A failed/empty capture is reported as "not yet visible" so the
     * caller keeps polling within its bounded timeout. Best-effort: never throws
     * — a capture failure must not fail the send, only defer the Enter.
     */
    private suspend fun agentPaneShowsPayload(
        client: TmuxClient,
        paneId: String,
        needle: String,
    ): Boolean {
        val response = runCatching {
            client.sendBestEffortCommand("capture-pane -p -t $paneId")
        }.getOrNull() ?: return false
        if (response.isError) return false
        val visible = response.output.joinToString(separator = "")
            .replace(WHITESPACE_RUN_REGEX, "")
        return visible.contains(needle)
    }

    public fun selectSessionTab(paneId: String, tab: SessionTab) {
        // Issue #778: honour a Conversation tap on a presumed-agent pane even
        // when live detection has not landed yet (`detection == null`). The
        // Conversation tab is only ever drawn for a presumed-agent pane (#716),
        // so a tap reaching here is a deliberate user intent to view the agent
        // surface; swallowing it (the old `detection == null` early-return) left
        // the user stuck on Terminal during the slow-detection window — the
        // exact no-op #778 reports. We now record the intent as
        // `selectedTab = Conversation` on a detection-less row, and the screen
        // renders a "waiting for agent" placeholder until detection seeds the
        // real transcript. A Terminal tap is unconditional as before.
        //
        // The row may not exist yet for a freshly-attached presumed-agent pane
        // with no remembered status, so a Conversation tap seeds a placeholder
        // row (detection still null) rather than returning early. A Terminal tap
        // on a missing row is still a no-op (there is no agent surface to leave).
        // We only seed for a LIVE pane (one present in `paneRows`): a tap that
        // names a genuinely unknown pane id stays a no-op.
        // Issue #817 (Rank-1 measurement): time the warm tab-switch. t0 at the
        // method entry, recorded below only for a switch to Conversation that
        // lands on an already-loaded transcript (the warm-switch case the spike
        // predicted is already <0.3s). A tap on a missing/loading row is the
        // OPEN path and is covered by conversation_open_full instead.
        val switchStartedAtMs = SystemClock.elapsedRealtime()
        val existing = _agentConversations.value[paneId]
        if (existing == null) {
            if (tab != SessionTab.Conversation) return
            if (!paneRows.containsKey(paneId)) return
            setAgentConversation(
                paneId,
                AgentConversationUiState(
                    detection = null,
                    events = emptyList(),
                    selectedTab = SessionTab.Conversation,
                    syncStatus = AgentConversationSyncStatus.Live,
                    // Issue #793: the user opened the Conversation tab; the
                    // transcript is loading (or detection is still warming).
                    // Show "Loading conversation…" + arm the watchdog so a
                    // never-arriving detection/read can't spin forever.
                    loadState = ConversationLoadState.Loading,
                ),
            )
            armConversationLoadWatchdog(paneId)
            DiagnosticEvents.record(
                "action",
                "session_tab_select",
                "mode" to "tmux",
                "paneId" to paneId,
                "tab" to tab.name,
                "hasConversation" to false,
            )
            ConversationDiagnostics.recordTabSwitch(
                mode = "tmux",
                paneId = paneId,
                fromTab = SessionTab.Terminal.name,
                toTab = tab.name,
                hasConversation = false,
                eventCount = 0,
                syncStatus = AgentConversationSyncStatus.Live.name,
            )
            rememberAgentStatusForPane(paneId)
            return
        }
        val before = existing
        val changed = before.selectedTab != tab
        if (changed) {
            updateAgentConversation(paneId) { current ->
                current.copy(selectedTab = tab)
            }
        }
        // Issue #793: arm the load watchdog when the user opens the
        // Conversation tab on a row whose transcript has not loaded yet
        // (detection still warming, or a previously-Failed load the user is
        // re-opening). Leaving the tab stops the spinner bookkeeping.
        if (tab == SessionTab.Conversation) {
            if (before.loadState == ConversationLoadState.Loading ||
                (before.loadState == ConversationLoadState.Failed && before.events.isEmpty())
            ) {
                if (before.loadState != ConversationLoadState.Loading) {
                    updateAgentConversation(paneId) { current ->
                        current.copy(loadState = ConversationLoadState.Loading)
                    }
                }
                armConversationLoadWatchdog(paneId)
            }
        } else {
            cancelConversationLoadWatchdog(paneId)
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
        // Issue #817 (Rank-1 measurement): record the warm-switch span for a
        // switch TO Conversation that lands on an already-loaded transcript
        // (events present, not the loading/placeholder open path). This is the
        // pure-state-read switch the spike predicted is already <0.3s; the span
        // turns that prediction into an authoritative number.
        if (changed && tab == SessionTab.Conversation && before.events.isNotEmpty()) {
            TmuxSessionLatencyTelemetry.record(
                name = CONVERSATION_SWITCH_LATENCY_OPERATION,
                durationMs = SystemClock.elapsedRealtime() - switchStartedAtMs,
                sessionName = activeTarget?.sessionName,
                paneId = paneId,
                trigger = activeAttachMilestone?.trigger,
                detail = "from=${before.selectedTab.name} events=${before.events.size} " +
                    "agent=${before.detection?.agent?.name ?: "none"}",
            )
        }
        // Issue #495: remember the tab choice keyed by window so a reconnect
        // puts the user back on whichever tab they were on.
        rememberAgentStatusForPane(paneId)
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

    /**
     * Issue #793: retry the INITIAL transcript load after it ended in the
     * Failed terminal state (the "Loading conversation…" watchdog tripped, or
     * the first-paint read threw). Unlike [retryAgentConversationStreamForPane]
     * — which retries a Stale/LogUnavailable *stream* on an already-detected
     * pane — this re-arms the Loading state and re-runs detection + the
     * tail-first windowed read, so it also recovers a presumed-agent
     * placeholder whose detection never landed. No-op when there is no live
     * session/pane to load from.
     */
    public fun retryAgentConversationLoad(paneId: String): Boolean {
        val session = sessionRef?.takeIf { it.isConnected } ?: return false
        val pane = paneRows[paneId] ?: return false
        // Re-enter the Loading state (and re-arm the watchdog) before kicking a
        // fresh detection so the UI flips back to "Loading conversation…".
        markConversationLoading(paneId)
        DiagnosticEvents.record(
            "action",
            "tmux_agent_conversation_load_retry",
            "paneId" to paneId,
        )
        // Clear the detection de-dup key so startAgentDetectionForPane does not
        // short-circuit the re-detection as already-seen, then re-run the full
        // detect → window-read → tail path.
        paneAgentInputs.remove(paneId)
        startAgentDetectionForPane(pane)
        return true
    }

    private suspend fun retryAgentConversationForPane(
        session: SshSession,
        pane: TmuxPaneState,
        currentDetection: AgentDetection,
        refreshGuard: RuntimeRefreshGuard,
    ) {
        // Epic #821 slice A2: a stream RETRY re-resolves the SOURCE for a pane
        // whose kind is ALREADY KNOWN ([currentDetection.agent]) — never a fresh
        // kind guess. Output-parsing kind detection is hard-cut (D22); reuse the
        // recorded-source resolution scoped to the known kind so we re-bind the
        // same engine's transcript without re-classifying.
        val detection = runCatching {
            agentRepository.detectRecordedSessionForPane(
                session = session,
                cwd = pane.cwd,
                paneTty = pane.paneTty,
                paneCommand = pane.currentCommand,
                recordedKind = currentDetection.agent,
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
        // Issue #793: a stream RETRY re-fetches the transcript the user was
        // already viewing — keep at least the legacy default window so a
        // re-fetch never shrinks an already-loaded/paged conversation back to
        // the small first-paint tail. (The first-OPEN path keeps the small
        // tail-first budget.)
        val retryBudget = (_agentConversations.value[pane.paneId]?.events?.size ?: 0)
            .coerceAtLeast(DEFAULT_RETRY_MESSAGE_BUDGET)
        startAgentConversationForPane(session, pane.paneId, detection, refreshGuard, retryBudget)
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
                // A fresh POSITIVE agent detection landed on a pane with no
                // existing conversation row. This is the OPEN/initial-tab moment
                // for the agent session, so the new row lands on the user's
                // configured open-time default (#818) — Conversation by default
                // (the black-screen cure), Terminal if the user opted out. This
                // is NOT a mid-session yank: there is no existing row, so no tab
                // the user is currently viewing is being changed (the #815 line
                // is about detection/refresh on an ALREADY-open session, handled
                // by the `current != null` branches below, which preserve the
                // user's tab). A remembered/explicit per-session choice still
                // wins — `seedAgentConversationFromMemory` runs first and would
                // have created the row already if a remembered choice existed.
                current == null -> AgentConversationUiState(
                    detection = detection,
                    events = boundedDistinctEvents(initialEvents),
                    selectedTab = openTimeDefaultSessionTab(),
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
                // A DIFFERENT agent (no same-source continuity) took over this
                // pane's window. This is a detection/refresh on an ALREADY-open
                // session, NOT an open-time event, so it must NOT yank the user
                // onto another view in EITHER direction (#815): we PRESERVE the
                // tab the user is currently viewing rather than apply the
                // open-time default. (The open-time default only governs the
                // fresh-row branch above. Applying it here would yank a user on
                // Terminal onto Conversation on a mid-session takeover — exactly
                // the #815 regression.)
                current.detection != detection -> AgentConversationUiState(
                    detection = detection,
                    events = boundedDistinctEvents(initialEvents),
                    selectedTab = current.selectedTab,
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

    // ===================================================================
    // Issue #793: tail-first conversation load state machine.
    //
    // Two distinct full-screen states the Conversation tab can be in BEFORE a
    // transcript is rendered:
    //   - Loading  -> "Loading conversation…" (an existing transcript is being
    //     fetched; NOT "Waiting for agent…").
    //   - Failed / Empty -> a clear terminal state, never an infinite spinner.
    //
    // The load is driven through `loadState` on the per-pane
    // AgentConversationUiState row; a watchdog flips a never-arriving load to
    // Failed so a transport flap (epic #792, out of scope to fix here) can no
    // longer hang the tab forever.
    // ===================================================================

    /**
     * Move [paneId]'s EXISTING conversation row into the Loading state and
     * (re)arm the load watchdog so the Conversation tab shows
     * "Loading conversation…" while the first-paint read runs. No-op when no
     * row exists — the detection-pending placeholder row is seeded by
     * [selectSessionTab] (the presumed-agent path), and the active-load path
     * here must not conjure a phantom row that an immediate cancellation
     * (intentional switch/teardown) would then strand.
     */
    private fun markConversationLoading(paneId: String) {
        var rowExists = false
        updateAgentConversation(paneId) { current ->
            rowExists = true
            current.copy(loadState = ConversationLoadState.Loading)
        }
        if (rowExists) armConversationLoadWatchdog(paneId)
    }

    private fun armConversationLoadWatchdog(paneId: String) {
        conversationLoadWatchdogJobs.remove(paneId)?.cancel()
        conversationLoadWatchdogJobs[paneId] = bridgeScope.launch {
            delay(conversationLoadTimeoutMs)
            updateAgentConversation(paneId) { current ->
                if (current.loadState == ConversationLoadState.Loading) {
                    DiagnosticEvents.record(
                        "recoverable",
                        "tmux_agent_conversation_load_timeout",
                        "pane" to paneId,
                        "timeoutMs" to conversationLoadTimeoutMs,
                    )
                    current.copy(loadState = ConversationLoadState.Failed)
                } else {
                    current
                }
            }
        }
    }

    private fun cancelConversationLoadWatchdog(paneId: String) {
        conversationLoadWatchdogJobs.remove(paneId)?.cancel()
    }

    /**
     * Record the terminal outcome of the first-paint tail read for [paneId].
     * Disarms the load watchdog and sets `loadState` to Ready (feed has
     * content / will be live-tailed), Empty (read succeeded but the transcript
     * has no events), or Failed (the read could not complete). Also records
     * whether older messages remain to page in on upward scroll.
     */
    private fun markConversationLoadOutcome(
        paneId: String,
        detection: AgentDetection,
        loadedEvents: List<ConversationEvent>,
        hasMoreOlder: Boolean,
        failed: Boolean,
    ) {
        cancelConversationLoadWatchdog(paneId)
        updateAgentConversation(paneId) { current ->
            // Only own the load state for the row that still matches this
            // detection (a fast switch could have replaced it).
            if (current.detection != null && !sameAgentSource(current.detection, detection)) {
                return@updateAgentConversation current
            }
            val nextLoadState = when {
                failed -> ConversationLoadState.Failed
                current.events.isEmpty() && loadedEvents.isEmpty() -> ConversationLoadState.Empty
                else -> ConversationLoadState.Ready
            }
            current.copy(
                loadState = nextLoadState,
                hasMoreOlderEvents = hasMoreOlder && !failed,
                isPagingOlder = false,
            )
        }
    }

    /**
     * Issue #793: page OLDER messages into [paneId]'s window on upward scroll.
     * Re-reads a wider tail window (the current loaded message count grown by
     * [OLDER_PAGE_GROWTH_FACTOR], capped at [MaxAgentEvents]) and prepends the
     * older events the wider read surfaces — preserving the events already in
     * view so the pane can hold scroll position. No-op when there is nothing
     * older to load or a page is already in flight.
     */
    public fun loadOlderAgentConversationEvents(paneId: String) {
        val current = _agentConversations.value[paneId] ?: return
        if (!current.hasMoreOlderEvents || current.isPagingOlder) return
        val detection = current.detection ?: return
        val session = sessionRef ?: return
        val guard = RuntimeRefreshGuard(
            generation = connectGeneration,
            target = activeTarget ?: return,
            client = clientRef ?: return,
        )
        updateAgentConversation(paneId) { it.copy(isPagingOlder = true) }
        val widerBudget = (current.events.size * OLDER_PAGE_GROWTH_FACTOR)
            .coerceAtLeast(current.events.size + FIRST_PAINT_MESSAGE_BUDGET)
            .coerceAtMost(MaxAgentEvents)
        bridgeScope.launch {
            val window = try {
                agentRepository.readEventsWindow(
                    session = session,
                    detection = detection,
                    maxMessages = widerBudget,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                updateAgentConversation(paneId) { it.copy(isPagingOlder = false) }
                return@launch
            }
            if (!isCurrentRuntime(guard)) {
                updateAgentConversation(paneId) { it.copy(isPagingOlder = false) }
                return@launch
            }
            updateAgentConversation(paneId) { row ->
                // Merge the wider window with whatever is already loaded (live
                // tail may have appended newer turns meanwhile). reconcile
                // de-dups by id and preserves document order, so older events
                // from the wider read slot in ahead of the existing tail.
                val merged = boundedDistinctEvents(window.events + row.events)
                row.copy(
                    events = merged,
                    hasMoreOlderEvents = window.hasMoreOlder,
                    isPagingOlder = false,
                )
            }
        }
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
     * that production calls when a subsequent detection round returns
     * null. Drives the same internal [clearAgentDetectionForPane] so
     * tests can assert the lock + conversation row clearing without
     * standing up a real SSH session.
     */
    internal fun clearAgentDetectionForPaneForTest(paneId: String) {
        clearAgentDetectionForPane(paneId)
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
     * Issue #897 test seam: drive the degraded/unavailable detection branch
     * directly. Unlike [handleNullAgentDetectionForTest], this path represents
     * an untrustworthy probe and must never confirm agent exit.
     */
    internal fun handleUnavailableAgentDetectionForTest(paneId: String): Boolean {
        val pane = paneRows[paneId] ?: return false
        val guard = RuntimeRefreshGuard(
            generation = connectGeneration,
            target = activeTarget ?: return false,
            client = clientRef ?: return false,
        )
        return handleUnavailableAgentDetection(pane, guard)
    }

    /**
     * Issue #894 (Slice C) test seam: apply a durable recorded-kind shell
     * verdict for [sessionId] synchronously, mirroring what the
     * `@ps_agent_kind` read in [refreshCurrentSessionRecordedKind] does once it
     * resolves. Lets a JVM test set the confirmed-shell verdict deterministically
     * (without a live SSH `show-options` round-trip) before/after seeding to
     * prove the seed gate + the per-pane [confirmedShellPaneIds] signal.
     */
    @androidx.annotation.VisibleForTesting
    internal fun applyRecordedShellVerdictForTest(sessionId: String, isShell: Boolean) {
        applyRecordedShellVerdict(sessionId = sessionId, isShell = isShell)
    }

    /**
     * Issue #894 (Slice C) test seam: drive [seedPresumedAgentPlaceholder]
     * directly for the row registered under [paneId]. Mirrors the pane-add call
     * in [applyParsedPanes] so a test can assert the seed gate (a confirmed shell
     * is NOT seeded; a presumed-agent pane IS) without a full reconcile.
     */
    @androidx.annotation.VisibleForTesting
    internal fun seedPresumedAgentPlaceholderForTest(paneId: String) {
        val pane = paneRows[paneId] ?: return
        seedPresumedAgentPlaceholder(pane)
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
                        DiagnosticEvents.record(
                            "action",
                            "pane_input_batch",
                            "pane" to paneId,
                            "bytes" to batch.bytes.size,
                        )
                        runCatching { sendInputBytesToPane(targetClient, paneId, batch.bytes) }
                            .onSuccess {
                                newQueue.recordSent(batch)
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
        if (columns == remoteColumns && rows == remoteRows) {
            // Issue #717 (the missed-heal gate): a composer/keyboard-dismiss after a
            // voice-send fires `onTerminalSizeChanged` with the EXACT same grid the
            // last `onSizeChanged` already recorded (the dictation chrome mount/unmount
            // re-measures the TerminalView back to the same dims within one measure
            // pass). On origin/main this returned BLINDLY — before
            // `maybeRefreshControlClientSize` was ever called — so the active pane the
            // IME transition wiped stayed BLACK with only a stray cursor / lone live
            // line, exactly the maintainer's #717 black pane. The same-dimension
            // short-circuit inside `maybeRefreshControlClientSize` (the
            // `appliedControlClient*` branch) DOES heal, but a true-same-grid resize
            // never reached it because of this top-level early-return. So mirror that
            // branch here: run the cheap active-pane heal (a single `capture-pane`, NO
            // `refresh-client -C` wire op) when the active pane actually looks lost.
            // `maybeHealActivePaneOnNoOpResize` pre-checks blank/partial-blank, so a
            // normally-painted pane stays a no-op (a routine keyboard toggle of a good
            // pane costs nothing — preserving the #285 "Compose layout churn must not
            // spam tmux" intent). The remote tmux grid is authoritative, so the
            // re-capture restores the lost frame keyed to the target session id.
            val client = clientRef
            val target = activeTarget
            if (client != null && target != null) {
                maybeHealActivePaneOnNoOpResize(client, target)
            }
            return
        }
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
        if (cols == appliedControlClientColumns && rows == appliedControlClientRows) {
            // EPIC #687 slice 2 (#717): same-dimension short-circuit. A repeated
            // resize to dims already applied does NOT need another
            // `refresh-client -C` (the #285 intent: Compose layout churn must not
            // spam tmux). BUT a composer/keyboard dismissal after a voice-send can
            // resize to the SAME grid while the idle full-screen agent's redraw was
            // lost on the IME transition — leaving the active pane BLACK with no
            // wire op to heal it. So instead of returning blindly, run a cheap
            // active-pane heal (a single `capture-pane`, no `refresh-client`) when
            // the active pane is actually blank/suspect. A normally-painted pane is
            // a no-op (the pre-check below avoids a capture on every keyboard
            // toggle of an already-good pane). tmux's grid is authoritative, so the
            // re-capture restores the lost frame keyed to the target session id.
            maybeHealActivePaneOnNoOpResize(client, target)
            return
        }
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
            // still HOLDS the reflowed content, so re-seed the visible panes from
            // a fresh `capture-pane`. Runs on every attach path (cold, warm,
            // fast-switch, reconnect) because all of them flow through this
            // resize-completion block. Skipped if the runtime moved on.
            //
            // EPIC #687 slice 2 (#717/#651, absorbing #658's reflow-heal plan):
            // extend the P3 within-grace UNCONDITIONAL full-viewport reseed
            // ([reseedActivePaneForReattach]) to this reflow boundary instead of
            // the blank-ONLY [reseedBlankVisiblePanes]. Two render bugs share this
            // locus:
            //  * #717 (black pane after voice-send): composer/keyboard dismissal
            //    reflows the grid; an idle full-screen agent's redraw is lost and
            //    the active pane goes BLACK. The blank-net would heal a fully-blank
            //    pane here, but the same-dimension short-circuit at the top of
            //    [maybeRefreshControlClientSize] can skip this block entirely (see
            //    the active-pane heal added there).
            //  * #651 (garbled / mis-wrapped at wrong size): the post-reflow active
            //    pane is FULLY populated but wrapped at the stale width — NOT blank,
            //    so [reseedBlankVisiblePanes] `continue`s past it and the garble
            //    persists until a manual tmux refresh. tmux's grid is authoritative
            //    post-reflow, and [seedPaneFromCapture] -> [toTerminalViewportBytes]
            //    prepends `ESC[H ESC[2J` (full clear+repaint), so an unconditional
            //    active-pane re-capture authoritatively wipes the stale mis-wrapped
            //    rows and re-fits them to the new grid — no manual refresh needed.
            // [reseedActivePaneForReattach] does the unconditional active-pane
            // re-capture THEN runs the blank-net backstop over the other panes, so
            // background panes keep the #640/#662 reveal-time blank safety net.
            if (clientRef === client) {
                // Issue #830: on a cold open the active pane was already seeded this
                // attach by the preload pass and the post-`refresh-client` reflow
                // streams its new-grid content back as live `%output`, so skip the
                // redundant unconditional re-capture for a freshly-seeded, painted
                // pane (a reattach/reflow on a reused or blank/mis-wrapped pane still
                // re-captures — see [reseedActivePaneForReattach]).
                reseedActivePaneForReattach(
                    RuntimeRefreshGuard(
                        generation = attachGeneration,
                        target = target,
                        client = client,
                    ),
                    skipWhenFreshlySeeded = true,
                )
            }
        }
    }

    /**
     * EPIC #687 slice 2 (#717): heal the active pane on a same-dimension (no-op)
     * resize. A composer/keyboard dismissal after a voice-send can resize the
     * grid back to dims already applied — so [maybeRefreshControlClientSize]
     * short-circuits with no `refresh-client -C` — yet the IME transition lost
     * the idle full-screen agent's redraw and left the active pane BLACK. tmux's
     * server grid still holds the content, so re-capture the active pane from a
     * fresh `capture-pane` (which [seedPaneFromCapture] -> [toTerminalViewportBytes]
     * replays as a full clear+repaint) — but ONLY when the pane is actually
     * blank/suspect, so a routine keyboard toggle on an already-painted pane stays
     * a no-op (no capture). Keyed to the target session id via the runtime guard,
     * so a late heal can never paint a switched-away session. No `refresh-client`
     * is issued because the grid dims did not change.
     */
    private fun maybeHealActivePaneOnNoOpResize(client: TmuxClient, target: ConnectionTarget) {
        if (client.disconnected.value) return
        val activePane = activeVisiblePane() ?: return
        // Only pay for a capture when the active pane looks lost (fully blank or
        // the "one live line, rest blank" partial-blank). A normally-painted pane
        // needs no heal — this keeps the no-op resize cheap.
        val blank = activePane.terminalState.visibleScreenIsBlank()
        val partialBlank = activePane.terminalState.visibleScreenIsPartiallyBlank()
        if (!blank && !partialBlank) return
        val attachGeneration = connectGeneration
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-noop-resize-active-pane-heal pane=${activePane.paneId} " +
                "window=${activePane.windowId} session=${target.sessionName} " +
                "blank=$blank partialBlank=$partialBlank",
        )
        bridgeScope.launch {
            if (clientRef !== client) return@launch
            reseedActivePaneForReattach(
                RuntimeRefreshGuard(
                    generation = attachGeneration,
                    target = target,
                    client = client,
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

    /**
     * Issue #898: create a new tmux session from the in-session kebab's rich
     * [com.pocketshell.app.projects.SessionTypePickerSheet] — the SAME sheet the
     * host/session-list screen uses (Session type, Agent CLI, Skip permissions,
     * Profile). This routes through the EXACT same verified host-screen create
     * path ([FolderListGateway.createSession]) as
     * [com.pocketshell.app.projects.FolderListViewModel.createSession], so the
     * created session honours every chosen option identically: the picker
     * synthesises [startCommand] (`pocketshell agent <kind> --dir … [--profile …]
     * [--no-skip-permissions]`) and the gateway `send-keys`-launches it inside
     * the new pane. On success [onResolved] fires with the resolved session name
     * so the screen can attach to it via navigation.
     *
     * The legacy control-channel `new-session -d` send remains ONLY as a
     * fallback for the narrow unit-test constructors built without a gateway /
     * host DAO; production Hilt always injects both, so production always takes
     * the verified gateway path (same dual-path shape as [killCurrentSession]).
     */
    public fun createSession(
        name: String,
        cwd: String = DEFAULT_TMUX_START_DIRECTORY,
        startCommand: String? = null,
        chosenKind: SessionAgentKind? = null,
        onResolved: (sessionName: String) -> Unit = {},
    ) {
        val creation = resolveTmuxSessionCreation(
            rawName = name,
            rawStartDirectory = cwd,
        )
        val gateway = folderListGateway
        val dao = hostDao
        val current = activeTarget
        if (gateway == null || dao == null || current == null) {
            // No gateway/host DAO/active target (unit-test constructor) —
            // best-effort `new-session -d` over the control channel. This path
            // cannot launch a startCommand; it exists only so the legacy unit
            // tests keep exercising the name/cwd derivation.
            sendLifecycleCommand(
                "new-session -d -s '${escapeSingleQuoted(creation.sessionName)}' " +
                    "-c '${escapeSingleQuoted(creation.startDirectory)}'",
            )
            return
        }
        bridgeScope.launch {
            val host = withContext(Dispatchers.IO) { dao.getById(current.hostId) }
            if (host == null) {
                Log.w(
                    ISSUE_464_KILL_TAG,
                    "create-session-host-missing host=${current.hostId} name=${creation.sessionName}",
                )
                return@launch
            }
            val result = gateway.createSession(
                host = host,
                keyPath = current.keyPath,
                passphrase = current.passphrase,
                sessionName = creation.sessionName,
                cwd = creation.startDirectory,
                startCommand = startCommand,
            )
            result.fold(
                onSuccess = { resolvedName ->
                    Log.i(
                        ISSUE_464_KILL_TAG,
                        "create-session-ok host=${current.hostId} name=$resolvedName " +
                            "kind=${chosenKind ?: "shell"}",
                    )
                    onResolved(resolvedName)
                },
                onFailure = { error ->
                    Log.w(
                        ISSUE_464_KILL_TAG,
                        "create-session-failed host=${current.hostId} name=${creation.sessionName} " +
                            "err=${error.javaClass.simpleName}: ${error.message}",
                    )
                },
            )
        }
    }

    /**
     * Issue #898: fetch the host-discovered agent profiles for the active
     * session's host and project them onto [claudeProfiles] / [codexProfiles] so
     * the in-session "+ New session" rich sheet shows the SAME Profile selector
     * the host screen does. Mirrors [FolderListViewModel.fetchProfiles]. Called
     * when the picker is about to open. Any non-success result (no gateway, CLI
     * missing, connect/parse failure) leaves the flows empty so the picker shows
     * no profile selector — the safe default-only behaviour.
     */
    public fun fetchProfilesForActiveSession() {
        val gw = profilesGateway
        val dao = hostDao
        val current = activeTarget
        if (gw == null || dao == null || current == null) {
            _claudeProfiles.value = emptyList()
            _codexProfiles.value = emptyList()
            return
        }
        val hostId = current.hostId
        bridgeScope.launch {
            val host = withContext(Dispatchers.IO) { dao.getById(hostId) } ?: return@launch
            val result = withContext(Dispatchers.IO) {
                gw.listProfiles(
                    host = host,
                    keyPath = current.keyPath,
                    passphrase = current.passphrase,
                )
            }
            // Ignore a stale result if the active session changed while fetching.
            if (activeTarget?.hostId != hostId) return@launch
            when (result) {
                is ProfilesResult.Profiles -> applyProfiles(result.profiles)
                else -> {
                    _claudeProfiles.value = emptyList()
                    _codexProfiles.value = emptyList()
                }
            }
        }
    }

    private fun applyProfiles(profiles: List<RemoteProfile>) {
        _claudeProfiles.value = profiles
            .filter { it.engine == RemoteProfile.ENGINE_CLAUDE }
            .map { ClaudeProfile(name = it.name, default = it.default) }
        _codexProfiles.value = profiles
            .filter { it.engine == RemoteProfile.ENGINE_CODEX }
            .map { CodexProfile(name = it.name, default = it.default) }
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
    public fun killCurrentSession(windowIndex: Int? = null) {
        val current = activeTarget ?: return
        val target = current.sessionName
        val gateway = folderListGateway
        val dao = hostDao
        // Issue #883: the tree presents each tmux WINDOW as its own `[wN]` row,
        // so a "Stop session" on a window row must remove only THAT window
        // (`tmux kill-window -t '<session>:<index>'`) — not the whole session.
        // tmux auto-destroys the session when its last window closes, so a
        // single-window session still dies on Stop (the common case), while a
        // multi-window session keeps its other window(s). We resolve the stable
        // tmux window id (`@N`) of the targeted window now so a surviving-session
        // close can drop just that row by id. `windowIndex == null` (no window
        // info — e.g. an older probe that never tagged a window index) keeps the
        // whole-session kill below.
        val windowId = windowIndex?.let { idx ->
            _panes.value.firstOrNull { it.windowIndex == idx }?.windowId
        }
        if (gateway == null || dao == null) {
            // No gateway/host DAO (unit-test constructor) — best-effort send
            // over the control channel and still signal so the tree reconciles.
            if (windowIndex != null) {
                sendLifecycleCommand(
                    "kill-window -t '${escapeSingleQuoted("$target:$windowIndex")}'",
                )
                if (windowId != null) {
                    sessionLifecycleSignals?.emitWindowClosed(current.hostId, windowId)
                } else {
                    sessionLifecycleSignals?.emitKilled(current.hostId, target)
                }
            } else {
                sendLifecycleCommand("kill-session -t '${escapeSingleQuoted(target)}'")
                sessionLifecycleSignals?.emitKilled(current.hostId, target)
            }
            return
        }
        bridgeScope.launch {
            Log.i(
                ISSUE_464_KILL_TAG,
                "stop-session-start host=${current.hostId} name=$target window=$windowIndex",
            )
            val host = withContext(Dispatchers.IO) { dao.getById(current.hostId) }
            if (host == null) {
                Log.w(
                    ISSUE_464_KILL_TAG,
                    "stop-session-host-missing host=${current.hostId} name=$target",
                )
                return@launch
            }
            if (windowIndex != null) {
                // Issue #883: window-aware Stop — kill just the targeted window.
                val result = gateway.killWindow(
                    host = host,
                    keyPath = current.keyPath,
                    passphrase = current.passphrase,
                    sessionName = target,
                    windowIndex = windowIndex,
                )
                result.fold(
                    onSuccess = { outcome ->
                        if (outcome.sessionSurvived && windowId != null) {
                            // Sibling window(s) remain: drop only the killed
                            // window row by its stable tmux id.
                            Log.i(
                                ISSUE_464_KILL_TAG,
                                "stop-window-signal host=${current.hostId} name=$target " +
                                    "window=$windowIndex windowId=$windowId session-survived",
                            )
                            sessionLifecycleSignals?.emitWindowClosed(current.hostId, windowId)
                        } else {
                            // Last window closed → tmux destroyed the session
                            // (or we couldn't resolve the window id): drop the
                            // whole session row, same as a session kill.
                            Log.i(
                                ISSUE_464_KILL_TAG,
                                "stop-window-signal host=${current.hostId} name=$target " +
                                    "window=$windowIndex session-destroyed",
                            )
                            sessionLifecycleSignals?.emitKilled(current.hostId, target)
                        }
                    },
                    onFailure = { error ->
                        Log.w(
                            ISSUE_464_KILL_TAG,
                            "stop-window-failed host=${current.hostId} name=$target " +
                                "window=$windowIndex err=${error.javaClass.simpleName}: ${error.message}",
                        )
                    },
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
     * Epic #821 Slice 1: read the active session's RECORDED `@ps_agent_kind`
     * fresh from the host and publish it on [currentSessionRecordedKind]. The
     * host-side tmux user option is the single source of truth — there is no
     * client-side kind cache here (the conversation-open
     * [sessionRecordedKindCache] is a detection-perf cache, not the
     * classification authority, and cannot represent a manually-set Shell).
     *
     * Reads over the SAME warm SSH session the screen is already attached to
     * (D21 — no new connection). `null` (option absent → foreign /
     * not-yet-classified, or no warm session) leaves the UI to surface the
     * session as [SessionAgentKind.Unknown] and offer the picker.
     *
     * Called on-demand: when the more-menu opens and after a successful
     * [setCurrentSessionKind] write.
     */
    public fun refreshCurrentSessionRecordedKind() {
        val target = activeTarget?.sessionName?.trim()?.takeIf { it.isNotEmpty() } ?: run {
            _currentSessionRecordedKind.value = null
            _currentSessionRecordedProfile.value = null
            return
        }
        val session = sessionRef ?: return
        bridgeScope.launch {
            val raw = withContext(Dispatchers.IO) {
                runCatching {
                    session.exec(
                        "tmux show-options -v -t '${escapeSingleQuoted(target)}' " +
                            "@ps_agent_kind 2>/dev/null || true",
                    ).stdout
                }.getOrNull()
            }
            val recordedKind = sessionAgentKindFromOption(raw)
            _currentSessionRecordedKind.value = recordedKind
            // Issue #894 (Slice C): feed the durable shell verdict into the
            // per-pane confirmed-shell signal. The active session's panes share
            // one `sessionId` (`$N`); mark/un-mark it so the seed skips a
            // confirmed shell and the screen collapses its presumed-agent
            // surface. A recorded SHELL also drops any auto-seeded placeholder
            // that raced ahead of this read (the first-open flash). A null
            // (foreign/unknown) verdict is NOT a confirmed shell — leave it
            // presumed-agent so the #878 cure is intact.
            activeSessionId()?.let { sessionId ->
                applyRecordedShellVerdict(
                    sessionId = sessionId,
                    isShell = recordedKind == SessionAgentKind.Shell,
                )
            }
            // Issue #858: read the recorded profile over the SAME warm session
            // (D21 — no new connection) so the "What is this session?" sheet can
            // show the provider/profile. A blank/absent option (default /
            // non-profiled / legacy session) leaves the profile null.
            val rawProfile = withContext(Dispatchers.IO) {
                runCatching {
                    session.exec(
                        "tmux show-options -v -t '${escapeSingleQuoted(target)}' " +
                            "@ps_agent_profile 2>/dev/null || true",
                    ).stdout
                }.getOrNull()
            }
            _currentSessionRecordedProfile.value =
                rawProfile?.trim()?.takeIf { it.isNotEmpty() }
        }
    }

    public fun refreshActiveSessionCards(): Boolean {
        val active = activeTarget ?: run {
            _sessionCards.value = SessionCardsUiState()
            return false
        }
        val target = active.sessionName.trim().takeIf { it.isNotEmpty() } ?: run {
            _sessionCards.value = SessionCardsUiState()
            return false
        }
        val targetKey = active.sessionCardsTargetKey()
        val session = sessionRef?.takeIf { it.isConnected } ?: return false
        _sessionCards.value = _sessionCards.value.copy(
            sessionName = target,
            targetKey = targetKey,
            loading = true,
        )
        bridgeScope.launch {
            val feed = withContext(sessionCardsDispatcher) {
                sessionCardsRemoteSource.getCards(session, target)
            }
            if (activeTarget?.sessionCardsTargetKey() == targetKey) {
                _sessionCards.value = SessionCardsUiState(
                    sessionName = target,
                    targetKey = targetKey,
                    loading = false,
                    feed = feed,
                )
            }
        }
        return true
    }

    public fun toggleChecklistItem(
        cardId: String,
        itemId: String,
        checked: Boolean,
    ): Boolean {
        val active = activeTarget ?: return false
        val target = active.sessionName.trim().takeIf { it.isNotEmpty() } ?: return false
        val targetKey = active.sessionCardsTargetKey()
        val session = sessionRef?.takeIf { it.isConnected } ?: return false
        bridgeScope.launch {
            val ok = withContext(sessionCardsDispatcher) {
                sessionCardsRemoteSource.setChecklistItemChecked(
                    session = session,
                    tmuxSessionName = target,
                    cardId = cardId,
                    itemId = itemId,
                    checked = checked,
                )
            }
            if (ok && activeTarget?.sessionCardsTargetKey() == targetKey) {
                val feed = withContext(sessionCardsDispatcher) {
                    sessionCardsRemoteSource.getCards(session, target)
                }
                if (activeTarget?.sessionCardsTargetKey() == targetKey) {
                    _sessionCards.value = SessionCardsUiState(
                        sessionName = target,
                        targetKey = targetKey,
                        loading = false,
                        feed = feed,
                    )
                }
            }
        }
        return true
    }

    /**
     * Epic #821 Slice 1: manually classify the active session — the
     * "unknown → pick" and "change kind" actions both land here. Writes the
     * durable host-side `@ps_agent_kind` tmux user option via [ManualKindWriter]
     * over the warm session (D21 — no new connection), then re-reads it so
     * [currentSessionRecordedKind] reflects the host. The written option is the
     * SAME one `record_agent_kind` writes at launch, so it survives reconnect /
     * app restart and reads back through the unchanged tree enumeration — one
     * source of truth, no third cache.
     *
     * On a successful write the conversation-open [sessionRecordedKindCache] is
     * invalidated for this session so the next Conversation open re-resolves
     * the source from the NEW kind (the cache holds the old verdict and cannot
     * represent every kind, e.g. a Shell reclassification).
     */
    public fun setCurrentSessionKind(kind: SessionAgentKind) {
        val target = activeTarget?.sessionName?.trim()?.takeIf { it.isNotEmpty() } ?: return
        val session = sessionRef ?: return
        bridgeScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    ManualKindWriter.write(
                        session = session,
                        sessionName = target,
                        kind = kind,
                    )
                }
            }
            result.onSuccess {
                // Invalidate the detection-perf cache (keyed by tmux session id,
                // not name) so the next Conversation open re-resolves the source
                // from the freshly-recorded kind. It is a pure perf cache, so a
                // full clear is safe and avoids a key-by-name vs key-by-id miss.
                sessionRecordedKindCache.clear()
                sessionForeignKindGuessCache.clear()
                refreshCurrentSessionRecordedKind()
            }.onFailure { error ->
                Log.w(
                    ISSUE_464_KILL_TAG,
                    "set-session-kind-failed session=$target kind=$kind " +
                        "err=${error.javaClass.simpleName}: ${error.message}",
                )
            }
        }
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
        // Issue #784: every hotkey is now a DIRECT button (the lone `Ctrl`
        // modifier + its armed-chord FSM were removed — the maintainer's "lone
        // Ctrl does nothing useful" complaint). The label is mapped straight to
        // its control byte (`send-keys -H` overlay) or tmux named key below.
        DiagnosticEvents.record(
            "action",
            "shortcut_sent",
            "mode" to "tmux",
            "paneId" to paneId,
            "key" to label,
        )

        val named = when (label) {
            "Esc" -> "Escape"
            "Tab" -> "Tab"
            // Issue #893: ⇧Tab (back-tab / Shift+Tab). tmux's named key `BTab`
            // emits the back-tab escape sequence `ESC [ Z` (0x1b 0x5b 0x5a),
            // which Claude Code listens for to cycle its permission/plan mode
            // ("plan mode on (shift+tab to cycle)"). Routes through the same
            // named-key `send-keys -t <pane> BTab` path as Esc/Tab — no resize,
            // no redraw. Send-only (no inbound ESC[Z parsing).
            "⇧Tab" -> "BTab"
            // Issue #527: the dedicated Enter/Return key. Submits a
            // newline/CR to the pane (runs the typed or pending line) via
            // the tmux named `Enter` key on the `send-keys` control channel
            // — no terminal resize or redraw, like Esc/Tab.
            "⏎", "Enter" -> "Enter"
            // Curated one-tap control combos (issue #458 / #784). Each maps
            // directly to its control byte via the `send-keys -H` overlay
            // path — no resize, no redraw. Every `^X` label is audited here so
            // the visible label equals the byte sent (no dupes / mislabels):
            //   ^A=0x01  ^B=0x02  ^C=0x03  ^D=0x04  ^E=0x05
            //   ^L=0x0C  ^R=0x12  ^O=0x0F  ^X=0x18  ^Z=0x1A
            "^A", "Ctrl-A" -> {
                sendControlInputToPane(paneId, CtrlAByte)
                null
            }
            // Issue #677/#784: `^B` (tmux prefix / Claude Code "ctrl-b ctrl-b
            // to background"). Raw 0x02 straight through the control channel —
            // in `-CC` mode it is NOT consumed as an outer-tmux prefix.
            "^B", "Ctrl-B" -> {
                sendControlInputToPane(paneId, CtrlBByteValue)
                null
            }
            "^C", "Ctrl-C" -> {
                sendControlInputToPane(paneId, CtrlCByte)
                null
            }
            "^D", "Ctrl-D" -> {
                sendControlInputToPane(paneId, CtrlDByte)
                null
            }
            // Issue #787 (re-home from the deleted `/ commands` palette,
            // originally #453/#543): the DOUBLED interrupt/EOF chords. These are
            // distinct from a single `^C`/`^D` — Claude Code (and many REPLs)
            // treat the first `^C`/`^D` as "press again to interrupt/exit", so
            // the doubled byte is what actually stops the running agent / sends
            // EOF. Routed as two raw bytes (`repeatCount = 2`) on the same
            // `send-keys -H` overlay path.
            TmuxHotkeyInterruptX2Label -> {
                sendControlInputToPane(paneId, CtrlCByte, repeatCount = 2)
                null
            }
            TmuxHotkeyEofX2Label -> {
                sendControlInputToPane(paneId, CtrlDByte, repeatCount = 2)
                null
            }
            "^E", "Ctrl-E" -> {
                sendControlInputToPane(paneId, CtrlEByte)
                null
            }
            "^L", "Ctrl-L" -> {
                sendControlInputToPane(paneId, CtrlLByte)
                null
            }
            "^R", "Ctrl-R" -> {
                sendControlInputToPane(paneId, CtrlRByte)
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
            // Issue #784: clean arrow glyphs (← ↑ ↓ →) replace the old
            // hard-to-read `‹ ⌃ ⌄ ›`. Both label families route to tmux's
            // own cursor-key named keys so tmux owns the terminfo encoding.
            "←", "‹", "Left" -> "Left"
            "↑", "⌃", "Up" -> "Up"
            "↓", "⌄", "Down" -> "Down"
            "→", "›", "Right" -> "Right"
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

    private fun smartTextPolicyForKeyBar(label: String): TerminalRawInputPolicy =
        when (label) {
            "⏎", "Enter" -> TerminalRawInputPolicy.FlushSmartText
            else -> TerminalRawInputPolicy.ClearSmartText
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
        // EPIC #792 Slice E: stop the connection facade's effect-driver collectors.
        // Idempotent; viewModelScope also cancels them, but stopping explicitly keeps
        // the driver tidy.
        connectionEffectDriver.stop()
        // EPIC #792 Slice D: stop the proactive liveness probe. Idempotent;
        // viewModelScope also cancels its loop, but stopping explicitly keeps the
        // probe tidy and ensures no late ping fires against a torn-down client.
        livenessProbe?.stop()
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
        paneConversationOpenStartedAtMs.clear()
        sessionRecordedKindCache.clear()
        sessionForeignKindGuessCache.clear()
        // Issue #894 (Slice C): drop the durable confirmed-shell verdicts so a
        // different session never inherits a stale shell verdict.
        confirmedShellSessionIds.clear()
        _confirmedShellPaneIds.value = emptySet()
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
        paneConversationOpenStartedAtMs.clear()
        sessionRecordedKindCache.clear()
        sessionForeignKindGuessCache.clear()
        // Issue #894 (Slice C): drop the durable confirmed-shell verdicts so a
        // different session never inherits a stale shell verdict.
        confirmedShellSessionIds.clear()
        _confirmedShellPaneIds.value = emptySet()
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
        eventsJob?.cancel()
        eventsJob = null
        // Issue #576 (Slice A of #792): tear down the layout-change coalescer +
        // its off-main drain scope with the rest of the per-connection state.
        layoutChangeCoalescer?.stop()
        layoutChangeCoalescer = null
        layoutCoalescerScope?.cancel()
        layoutCoalescerScope = null
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
        paneConversationOpenStartedAtMs.clear()
        sessionRecordedKindCache.clear()
        sessionForeignKindGuessCache.clear()
        // Issue #894 (Slice C): drop the durable confirmed-shell verdicts so a
        // different session never inherits a stale shell verdict.
        confirmedShellSessionIds.clear()
        _confirmedShellPaneIds.value = emptySet()
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

    /**
     * Issue #894 (Slice C): the active session's tmux `sessionId` (`$N`), read
     * off the current pane rows (all active panes share it). Null when no pane
     * is attached yet. Used to key the durable confirmed-shell verdict so it
     * matches the conversation-open cache's `sessionId` key.
     */
    private fun activeSessionId(): String? =
        _panes.value.firstOrNull()?.sessionId?.trim()?.takeIf { it.isNotEmpty() }

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
            // Issue #186: `#{pane_tty}` scopes the recorded-source process
            // scan to this pane. Older tmux versions that omit the field
            // simply return empty, in which case per-pane source resolution
            // skips this pane rather than fall back to a host-wide scan.
            paneTty = parts.getOrNull(paneIndexIndex + 3).orEmpty(),
            inCopyMode = parseTmuxBoolean(parts.getOrNull(paneIndexIndex + 4)),
            // Epic #821 slice A2: `#{pane_pid}` is the LAST field. Older tmux
            // (or unit tests on the legacy format) omit it -> 0, and the
            // foreign-session kind guess simply skips the pane.
            panePid = parts.getOrNull(paneIndexIndex + 5)?.trim()?.toLongOrNull() ?: 0L,
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
        // Epic #821 slice A2: copied from `#{pane_pid}` for the foreign-session
        // one-shot kind guess. Defaults 0 for older tmux / tests.
        val panePid: Long = 0L,
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
        val tmuxSessionId: String? = null,
        val sessionCreated: Long? = null,
    )

    private fun ConnectionTarget.sessionCardsTargetKey(): String =
        sessionCardsTargetKey(
            hostId = hostId,
            host = host,
            port = port,
            user = user,
            keyPath = keyPath,
            sessionName = sessionName,
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
     * [onNetworkChanged], and the foreground-runtime-probe gate). This sealed
     * event/decision pair plus the single [reduceConnection] classifier consolidate those branch decisions
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
        // EPIC #687 slice 1c-iv-c (#754): the within-grace foreground (no pending
        // reattach, live session) is now handled BEFORE this reducer in
        // [onAppForegrounded] via the driver-owned RESEED-ONLY effect — the old
        // `ProbeForeground → probeCurrentRuntimeOnForegroundIfNeeded → connect(...)`
        // decision is DELETED (D22 hard-cut). A foreground that is NOT within grace
        // and has no pending reattach is a no-op here.
        return ConnectionDecision.Ignore
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

    private fun classifyPassiveTransportDrop(client: TmuxClient): ConnectionDecision {
        // Issue #895 (#766 down-payment): STATUS-AGNOSTIC. The old
        // `inlineConnectionStatus !is Connected -> Ignore` gate swallowed a drop
        // that landed during the `Switching` (Attaching) window — the R1
        // switch-while-black freeze. The real protection against acting on the
        // brief tmux `-CC` close of a NORMAL fast switch (the #635 spurious-band
        // risk) is the client-identity guard below: during a healthy switch the
        // old client's `disconnected` edge fires AFTER `clientRef` already points
        // at the new client, so it is correctly ignored. A drop on the CURRENT
        // client — whatever the display status — is a real transport loss and
        // must drive recovery, so it is no longer gated by the status. An
        // Idle/Gone session has no current client, so the identity guard below
        // returns Ignore for it; the controller also self-gates a drop from a
        // non-live-ish state, so no spurious band can surface there.
        //
        // Only react if this is still THE active client (a fresh connect may have
        // swapped in a new client whose state this drop must not stomp).
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

    fun recordSent(batch: TmuxPaneInputBatch) = synchronized(lock) {
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
 * EPIC #687 P1: a non-empty sentinel frame used to promote the reveal machine to
 * [com.pocketshell.core.connection.RevealState.Live] at the inline
 * "active pane revealed" moments (warm-cache / fast-switch reveal) where no fresh
 * `capture-pane` re-fires. The screen renders the VM's own panes, so the frame
 * content is irrelevant — only the non-emptiness (which flips the reveal gate from
 * Hold to Reveal for the current target) matters.
 */
private const val REVEAL_LIVE_SENTINEL_FRAME: String = "reveal-live"

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
internal const val CtrlAByte: Int = 0x01
internal const val CtrlBByteValue: Int = 0x02
internal const val CtrlCByte: Int = 0x03
internal const val CtrlDByte: Int = 0x04
internal const val CtrlEByte: Int = 0x05
internal const val CtrlLByte: Int = 0x0C
internal const val CtrlRByte: Int = 0x12
internal const val CtrlZByte: Int = 0x1A
internal const val CtrlOByte: Int = 0x0F
internal const val CtrlXByte: Int = 0x18

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

/**
 * Issue #869: ack-gate the submit Enter on the pasted composer text actually
 * landing in the agent's input — rather than a blind fixed sleep that is too
 * short under real RTT (the missed-submit the maintainer hit on-device:
 * "most of the time when I click Send it's not really sending").
 *
 * After typing the prompt into the agent pane we poll `capture-pane` and only
 * press Enter once the payload is visible in the pane (the agent has finished
 * ingesting the paste). The poll is RTT-adaptive by construction: each
 * `capture-pane` is itself a round-trip, so a high-latency host naturally
 * waits longer before the confirming capture returns.
 *
 * - [AGENT_SUBMIT_ACK_POLL_INTERVAL_MS] is the wait between capture polls.
 * - [AGENT_SUBMIT_ACK_TIMEOUT_MS] bounds the total wait so a TUI whose input
 *   rendering we can't recognise (or a `capture-pane` that keeps failing) can
 *   never deadlock Send — on timeout we fall back to pressing Enter anyway
 *   (best-effort), so the worst case is the pre-#869 blind behaviour, never a
 *   hung send. The Codex floor still applies as a MINIMUM wait so lowering the
 *   global delay can't regress the Codex TUI that motivated the original delay.
 */
internal const val AGENT_SUBMIT_ACK_POLL_INTERVAL_MS: Long = 40L
internal const val AGENT_SUBMIT_ACK_TIMEOUT_MS: Long = 2_000L

/**
 * Issue #869: how many whitespace-stripped TAIL characters of the pasted prompt
 * the ack needle matches against `capture-pane`. Matching the TAIL (the part of
 * the input near the cursor, which stays on-screen even when the head of a long
 * prompt scrolls off the visible viewport) — and stripping ALL whitespace from
 * both sides (so a wrap-boundary separator can never split a token) — makes the
 * ack survive a reflowed/wrapped agent input box, the on-device case the
 * connected proof exposed as a needle miss. 24 chars is specific enough to avoid
 * a false positive against unrelated prompt content while comfortably fitting in
 * the visible viewport near the cursor.
 */
internal const val AGENT_SUBMIT_ACK_NEEDLE_TAIL_CHARS: Int = 24

/**
 * Issue #869: a regex matching runs of whitespace. The ack needle + the visible
 * `capture-pane` text are both whitespace-STRIPPED with this so a wrapped agent
 * input box (whose row-join inserts a separator at the wrap boundary, possibly
 * mid-word) still matches the original prompt's tail.
 */
private val WHITESPACE_RUN_REGEX = Regex("\\s+")
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
 * Issue #576 (Slice A of #792): logcat tag for the layout-change coalescer
 * (Codex `%layout-change` burst → ~1 off-main reconcile per frame). Within the
 * 23-character `Log.isLoggable` cap.
 */
internal const val ISSUE_576_COALESCER_TAG: String = "PsTmuxCoalescer"

/**
 * Issue #896: logcat tag for the scope-level CoroutineExceptionHandler safety
 * net on the SSH/tmux close/EOF cascade. A teardown-race throw that lands here
 * is swallowed (process kept alive) + recorded as a non-fatal report; greppable
 * to confirm the net fired instead of a process death. Within the 23-char
 * `Log.isLoggable` cap.
 */
internal const val ISSUE_896_BRIDGE_SAFETY_NET_TAG: String = "PsTmuxBridgeSafety"

/**
 * EPIC #792 Slice D (#822/V7a): logcat tag for the proactive mid-session
 * liveness probe — the silent-drop detector. Greppable by the silent-drop
 * journey to correlate the probe's drop-declaration with the surfaced indicator.
 * Within the 23-character `Log.isLoggable` cap.
 */
internal const val LIVENESS_PROBE_TAG: String = "PsTmuxLiveness"

/**
 * EPIC #792 Slice D: test-only override for the [LivenessProbe]'s timing knobs,
 * the analogue of [com.pocketshell.app.BackgroundGraceTestOverride]. A connected
 * / emulator journey shortens the probe window deterministically WITHOUT
 * weakening any assertion or self-skipping — production keeps the
 * [LivenessProbe.DEFAULT_INTERVAL_MS] / DEFAULT_PER_PROBE_TIMEOUT_MS /
 * DEFAULT_FAILURE_THRESHOLD defaults. The override is read once when the VM
 * constructs its probe, so a proof sets it BEFORE launching the activity.
 */
internal object LivenessProbeTestOverride {
    @Volatile
    private var intervalMsOverride: Long? = null

    @Volatile
    private var perProbeTimeoutMsOverride: Long? = null

    @Volatile
    private var failureThresholdOverride: Int? = null

    /**
     * Whether a freshly-constructed VM auto-starts its probe loop. Production +
     * the connected emulator proof keep this TRUE (the loop runs on the real Main
     * looper). JVM unit tests set it FALSE: the probe's infinite periodic `delay`
     * loop on the virtual-clock Main would otherwise hang `runTest`'s
     * `advanceUntilIdle()`. Those tests drive the probe via the explicit VM seams
     * instead.
     */
    @Volatile
    var autoStartEnabled: Boolean = true

    fun setAutoStartEnabledForTest(enabled: Boolean) {
        autoStartEnabled = enabled
    }

    fun setForTest(intervalMs: Long?, perProbeTimeoutMs: Long?, failureThreshold: Int?) {
        require(intervalMs == null || intervalMs > 0) { "intervalMs must be > 0" }
        require(perProbeTimeoutMs == null || perProbeTimeoutMs > 0) {
            "perProbeTimeoutMs must be > 0"
        }
        require(failureThreshold == null || failureThreshold >= 1) {
            "failureThreshold must be >= 1"
        }
        intervalMsOverride = intervalMs
        perProbeTimeoutMsOverride = perProbeTimeoutMs
        failureThresholdOverride = failureThreshold
    }

    fun clear() {
        setForTest(null, null, null)
        autoStartEnabled = true
    }

    fun intervalMs(): Long = intervalMsOverride ?: LivenessProbe.DEFAULT_INTERVAL_MS

    fun perProbeTimeoutMs(): Long =
        perProbeTimeoutMsOverride ?: LivenessProbe.DEFAULT_PER_PROBE_TIMEOUT_MS

    fun failureThreshold(): Int =
        failureThresholdOverride ?: LivenessProbe.DEFAULT_FAILURE_THRESHOLD
}

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

/**
 * Issue #793: how long the Conversation tab spins on "Loading conversation…"
 * before the load watchdog flips it to a clear Failed terminal state. Sized to
 * comfortably cover a normal first-paint tail read over SSH while still
 * bounding a never-completing read (transport flap / unavailable log) so the
 * tab can never hang indefinitely.
 */
internal const val CONVERSATION_LOAD_TIMEOUT_MS: Long = 12_000L

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
    val tmuxSessionId: String? = null,
    val sessionCreated: Long? = null,
    val trigger: TmuxConnectTrigger,
    val generation: Long,
)

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
