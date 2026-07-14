package com.pocketshell.app.tmux

import android.content.Context
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import com.pocketshell.app.AppTeardownScope
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
import com.pocketshell.uikit.model.KeyModifierState
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
import com.pocketshell.app.startup.StartupTiming
import com.pocketshell.app.tmux.connection.BackgroundArm
import com.pocketshell.app.tmux.connection.BackgroundEffects
import com.pocketshell.app.tmux.connection.selectBackgroundArm
import com.pocketshell.app.tmux.connection.ConnectionManager
import com.pocketshell.app.tmux.connection.ConnectionEffectDriver
import com.pocketshell.app.tmux.connection.ConnectionStatusProjection
import com.pocketshell.app.tmux.connection.CurrentClientTmuxPort
import com.pocketshell.app.tmux.connection.ForegroundReturnEffects
import com.pocketshell.app.tmux.connection.debounceReconnectUi
import com.pocketshell.app.tmux.connection.GraceEffects
import com.pocketshell.app.tmux.connection.NetworkChangeArm
import com.pocketshell.app.tmux.connection.NetworkChangeEffects
import com.pocketshell.app.tmux.connection.KEEPALIVE_DEATH_REDIAL_QUIET_RESET_MS
import com.pocketshell.app.tmux.connection.KeepaliveDeathRedialAmortizer
import com.pocketshell.app.tmux.connection.NetworkLossBandDebouncer
import com.pocketshell.app.tmux.connection.ParkedRuntimeHealthEffects
import com.pocketshell.app.tmux.connection.isSameIdentityNetworkRestore
import com.pocketshell.app.tmux.connection.recordNetworkRestoreReconnectStart
import com.pocketshell.app.tmux.connection.recordNetworkRestoreRideThrough
import com.pocketshell.app.tmux.connection.recordPassiveDisconnect
import com.pocketshell.app.tmux.connection.recordSilentReattachFail
import com.pocketshell.app.tmux.connection.recordSilentReattachStart
import com.pocketshell.app.tmux.connection.recordNetworkLossBandPainted
import com.pocketshell.app.tmux.connection.recordNetworkLossBandSuppressed
import com.pocketshell.app.tmux.connection.recordNetworkLossHold
import com.pocketshell.app.tmux.connection.PassiveDropArm
import com.pocketshell.app.tmux.connection.PassiveTransportDropEffects
import com.pocketshell.app.tmux.connection.preferFreshTransportForPassiveReattach
import com.pocketshell.app.tmux.connection.SshLeaseTransportPort
import com.pocketshell.app.tmux.connection.SuppressedDropDiagnostic
import com.pocketshell.app.tmux.connection.TmuxAttachEffects
import com.pocketshell.app.tmux.connection.TransportEffects
import com.pocketshell.app.tmux.connection.hostKeyFor
import com.pocketshell.app.tmux.connection.shouldReportValidatedHandoffToController
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.connection.ConnectionEvent as CoreConnectionEvent
import com.pocketshell.core.connection.ConnectionState as CoreConnectionState
import com.pocketshell.core.connection.HostKey
import com.pocketshell.core.connection.LivenessProbe
import com.pocketshell.core.connection.RevealState
import com.pocketshell.core.connection.RuntimeDeathCause
import com.pocketshell.core.connection.RuntimeHealthKey
import com.pocketshell.core.connection.RuntimeHealthLedger
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
import com.pocketshell.core.ssh.SshLeaseConnectCoalescedCancelException
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshSessionCloseCause
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
import com.pocketshell.core.tmux.TmuxServerDeadException
import com.pocketshell.core.tmux.TmuxSessionNotFoundException
import com.pocketshell.core.tmux.protocol.ControlEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
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
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
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
    // Issue #972: the always-on reconnect-cause flight recorder. Its
    // [DiagnosticRecorder.connectionLogJsonl] payload is mirrored to the host's
    // `~/.pocketshell/connection-log.jsonl` over the warm lease right after a
    // reconnect (the driver's `onTransportReconnected` edge), so the maintainer
    // can attribute a real-world drop in the in-app file viewer (no adb).
    // Nullable default keeps the existing unit-test constructors working without
    // the Hilt singleton; when absent the host mirror is simply skipped.
    private val diagnosticRecorder: com.pocketshell.app.diagnostics.DiagnosticRecorder? = null,
    // Issue #1587 (H3): injected @Singleton store, one lock (see outboundDeliveryLedgerFor).
    private val outboundQueueStore: com.pocketshell.app.composer.OutboundQueueStore? = null,
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
     * Issue #926: the dispatcher the attach/switch/reattach SEED + `list-panes`
     * BLOCKING control-channel round-trips run on. Defaults to [Dispatchers.IO]
     * so the `capture-pane` / `list-panes` IO NEVER parks the UI (`Main`) thread
     * — the freeze the maintainer hit on a wedged-but-alive `-CC` channel
     * (#895). The IO round-trip happens here off-Main; only the resulting
     * `_panes` / pane-emulator (`appendRemoteOutput`) / reveal mutation hops
     * BACK to Main (the Codex `%layout-change` coalescer already proves this
     * split is safe). NOT a constructor parameter, for the same Hilt-graph
     * reason as [reconcileDispatcher]; a unit test pins it to a DISTINCT,
     * separately-identifiable dispatcher via [setSeedIoDispatcherForTest] so the
     * "ran off Main" property is directly observable.
     */
    private var seedIoDispatcher: CoroutineDispatcher = Dispatchers.IO

    @androidx.annotation.VisibleForTesting
    internal fun setSeedIoDispatcherForTest(dispatcher: CoroutineDispatcher) {
        seedIoDispatcher = dispatcher
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
        sessionCardsRemoteSource.setExecDispatcherForTest(dispatcher)
    }

    /**
     * Issue #1085 (F2): the application-scoped coroutine the SLOW
     * connection-teardown IO ([closeCurrentConnection]'s tmux `detach-client`
     * round-trip, the cached-runtime closes, and the warm-lease refcount-- /
     * raw-socket close) is handed to so `onCleared()` returns IMMEDIATELY
     * instead of parking the Main thread. The previous synchronous
     * `runBlocking(Dispatchers.IO){ withTimeoutOrNull(...){ … } }` blocks
     * DEFEATED their own coroutine timeouts: the underlying close is a
     * non-suspending `AutoCloseable.close()` doing a nested `runBlocking` socket
     * close, and a coroutine cancel cannot interrupt a thread parked in nested
     * `runBlocking` — so on a wedged socket the real bound was the SUM of the
     * per-resource ceilings (shell + session + N cached runtimes), a
     * multi-second / ANR-class Main park. [AppTeardownScope] OUTLIVES this VM
     * and is never cancelled, so the teardown still runs to COMPLETION off-Main
     * (no leak). NOT a constructor parameter, for the same Hilt-graph reason as
     * [reconcileDispatcher]; a unit test pins it via [setTeardownScopeForTest]
     * so the off-Main hand-off is directly observable.
     */
    private var teardownScope: CoroutineScope = AppTeardownScope.scope

    @androidx.annotation.VisibleForTesting
    internal fun setTeardownScopeForTest(scope: CoroutineScope) {
        teardownScope = scope
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
     * Issue #926: the SHORT ceiling (ms) applied to each attach/switch/reattach
     * SEED `capture-pane` round-trip ([captureAndApplyPaneSnapshot] →
     * [TmuxClient.captureWithCursor]). Far below the full per-command
     * `commandTimeoutMs` (10 s) so a wedged-but-alive control channel makes the
     * seed surface a best-effort failure FAST and fall through to the blank
     * watchdog on the still-live transport, instead of the (now off-Main, but
     * still bounded) seed parking for the full 10 s.
     */
    private val seedCaptureTimeoutMs: Long = SEED_CAPTURE_TIMEOUT_MS

    /**
     * Issue #1206: backoff (ms) between bounded prewarm seed-recovery capture
     * retries ([schedulePrewarmSeedRecovery]). Defaults to the production value;
     * a unit test may shorten it via [setPrewarmSeedRetryBackoffForTest] so the
     * retry window doesn't need real wall-clock delay under a real-thread driver
     * (the virtual-clock `runTest` driver auto-advances it either way).
     */
    private var prewarmSeedRetryBackoffMs: Long = PREWARM_SEED_RETRY_BACKOFF_MS

    @androidx.annotation.VisibleForTesting
    internal fun setPrewarmSeedRetryBackoffForTest(backoffMs: Long) {
        prewarmSeedRetryBackoffMs = backoffMs
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

    // Issue #1577b: the pane's visible LOCAL render text the send path reads to
    // decide whether the payload is already on screen (the #1577 baseline cost-gate)
    // and to seed the ack gate's pre-paste needle baseline. A test override lets a
    // unit test reproduce the production state where the app IS rendering a Codex
    // `Goal blocked (/goal resume)` footer without wiring a full terminal producer.
    @androidx.annotation.VisibleForTesting
    internal val localRenderTextOverrideForTest: MutableMap<String, String> = mutableMapOf()

    private fun localRenderTextForPane(paneId: String): String =
        localRenderTextOverrideForTest[paneId]
            ?: paneRows[paneId]?.terminalState?.visibleScreenTextSnapshot().orEmpty()

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
     * Issue #976: a new-session LAUNCH that the gateway refused (e.g. the
     * derived name collides with an already-open session, so sending the launch
     * line would type it into the existing/current pane). The Screen collects
     * this to surface the failure to the user instead of letting the launch
     * silently no-op — the launch command is NEVER leaked into the current pane,
     * and the user is told why (mirrors #968's "fail visibly, never act against
     * the current target").
     */
    private val _sessionCreateError: MutableSharedFlow<String> =
        MutableSharedFlow(extraBufferCapacity = 1)
    public val sessionCreateError: SharedFlow<String> =
        _sessionCreateError.asSharedFlow()

    /**
     * Issue #989: one-shot user-visible feedback for the manual Redraw kebab item.
     * Before this, Redraw silently no-op'd (logged + returned) when there was no
     * live client / the client was disconnected / no active target — so to the
     * maintainer it read as "Redraw is broken" ("I clicked it three times and
     * nothing happened"). The Screen collects this and shows a Toast so the user
     * learns Redraw can't act right now (reconnecting), instead of staring at an
     * unchanged screen wondering if the feature works. Buffered (replay-less,
     * extra capacity) so a tap that happens while no collector is bound is not
     * lost.
     */
    private val _redrawFeedback: MutableSharedFlow<String> =
        MutableSharedFlow(extraBufferCapacity = 4)
    public val redrawFeedback: SharedFlow<String> =
        _redrawFeedback.asSharedFlow()

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
            // full background decision (the re-home of the inline background
            // dispatch). [onControllerBackgrounded] selects pause-vs-detach via the
            // connection-core [backgroundEffects] dispatcher (#1047 Slice 1) and, on the
            // detach arm, routes the clean-detach teardown through the single [GraceEffects]
            // owner ([graceEffects.onBackgrounded] -> launchBackgroundDetachTeardown).
            backgroundedEffect = { onControllerBackgrounded() },
            // Slice 1c-iv-c (#754): the driver OWNS the within-grace FOREGROUND reattach.
            // On the controller's Backgrounded→Reattaching edge (within grace + warm lease)
            // it fires the RESEED-ONLY body — re-promote Live + heal blank panes over the
            // still-warm `-CC` lease, NO connect(), NO "Attaching…" overlay.
            // The same body runs directly from `onAppForegrounded(resumedWithinGrace)`
            // in production (the controller does not reach that edge there because the
            // teardown — and thus the controller's Background — is deferred to
            // grace-elapsed); both paths route through the one [GraceEffects] owner (EPIC #792
            // Slice B). The deleted inline `probeCurrentRuntimeOnForegroundIfNeeded →
            // connect(LifecycleReattach)` path is the superseded behavior (D22 hard-cut).
            foregroundReattachEffect = { graceEffects.onForegroundReattachReseed() },
            // Issue #1545 (R1, extends #904): suppress the reattach-edge reseed while a
            // `pendingReattach` is outstanding — the pending replay owns recovery (see driver).
            hasPendingReattach = { pendingReattach != null },
            // EPIC #766 Slice 2a: the controller's Backgrounded -> Reconnecting edge (the
            // BEYOND-grace foreground) DRIVES the foreground arm dispatch. [onControllerForegrounded]
            // delegates to the connection-core [ForegroundReturnEffects] (EPIC #687 Slice 0 / #1047),
            // re-reading the live pendingReattach / pausedAutoReconnect payloads — the edge is only
            // the TRIGGER, not the display-status gate.
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
            onDropSuppressed = { diagnostic ->
                recordSuppressedDropDiagnostic(diagnostic)
            },
            // EPIC #766 Slice 2b: the driver now owns the passive transport-drop edge.
            // The typed client stream lets the VM reject a late old-client close BEFORE
            // the driver submits TransportDropped to the controller (#630), while still
            // keeping the submit itself inside the driver-owned path.
            controlChannelDrops = connectionTmuxPort.disconnectedClients,
            shouldSubmitControlChannelDrop = { client ->
                passiveTransportDropEffects.classify(client) != PassiveDropArm.Ignore
            },
            controlChannelDroppedEffect = { client -> onControllerTransportDropped(client) },
            // Issue #972: on the current host's lease coming back `Up` after a
            // reconnect, mirror the recorded reconnect-cause trail to the host's
            // `~/.pocketshell/connection-log.jsonl` over the now-warm lease so the
            // just-completed drop is attributable in the in-app file viewer (no
            // adb). Fail-soft + a blank trail no-ops, so it never perturbs the
            // live connection.
            onTransportReconnected = { mirrorConnectionLogToHost() },
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

                override fun transportProvenAliveRecently(): Boolean =
                    isTransportKeepAliveProvenAliveRecently()
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
     *
     * Issue #1193: [requireAnsweredRoundTrip] forwards to [TmuxClient.probeLiveness]
     * so a NETWORK-TRANSITION probe (the restore / handoff arms) demands an actual
     * answered round-trip over the (possibly new) path — the #927 reader-activity
     * fallback is NOT valid liveness evidence across a WiFi↔cellular handoff, since
     * those bytes crossed the OLD, now-dead socket. The steady-state periodic drop
     * probe leaves it `false` (keeps the #927 busy-vs-dead tolerance).
     */
    private suspend fun runLivenessProbePing(requireAnsweredRoundTrip: Boolean = false): Boolean {
        // EPIC #792 Slice D — the per-PR synthetic-drop seam. When the test hook
        // is armed the ping reports DEAD regardless of the real channel, so the
        // silent-drop detection + recovery contract can be exercised
        // DETERMINISTICALLY on the plain `agents:2222` fixture (no toxiproxy
        // needed) — the same lever the toxiproxy half-open proof exercises on the
        // real wire. Production default is false (the real probe runs).
        if (forceLivenessProbeDeadForTest) return false
        val client = clientRef ?: return false
        return runCatching { client.probeLiveness(requireAnsweredRoundTrip) }.getOrDefault(false)
    }

    /**
     * Issue #964 — the keepalive-coordination guard the [LivenessProbe] consults
     * before declaring a drop. Reports whether the always-on transport keepalive
     * ([com.pocketshell.core.ssh.TransportKeepAlive], #945) has seen inbound
     * transport activity within its ride-through window — i.e. the LINK is provably
     * alive even though the tmux control-channel probe is momentarily failing. When
     * true the probe DEFERS rather than force-redialing, so a slow-but-live link is
     * ridden through by the single keepalive budget instead of two competing ones.
     *
     * Reads the live [sessionRef] (the transport the warm lease holds). No session
     * → no keepalive signal → false, so the probe keeps its own authority exactly
     * as before whenever there is no live transport to defer to.
     */
    private fun isTransportKeepAliveProvenAliveRecently(): Boolean {
        // Test seam: a connected/unit proof can pin the keepalive "alive" state
        // (a live-but-slow link) without driving the real 90s ride-through window.
        // An EXPLICIT pin always wins (the #964 slow-but-live phase deliberately
        // sets it true WHILE the `-CC` dead-seam is armed).
        forceTransportProvenAliveForTest?.let { return it }
        // Issue #866 / #822: the synthetic SILENT-drop seam ([forceLivenessProbeDeadForTest])
        // models a genuine half-open link death — the dominant real #822 where BOTH the
        // tmux `-CC` channel AND the SSH transport keepalive die together. Without this,
        // arming only the `-CC` dead-seam on a healthy `agents:2222` fixture left the REAL
        // keepalive "proven alive", so the #982/#984 deferral suppressed the drop forever
        // and the connection-lost indicator never surfaced (the #822 detection contract
        // regressed to a no-op on the deterministic fixture). When the `-CC` dead-seam is
        // armed and no EXPLICIT keepalive verdict is pinned, the transport is NOT proven
        // alive either — a silent drop is a WHOLE-link death. Production-neutral: the seam
        // is test-only (always false in production), so this never changes real behaviour.
        if (forceLivenessProbeDeadForTest) return false
        val session = sessionRef ?: return false
        return runCatching { session.isTransportProvenAliveWithinKeepAliveWindow() }
            .getOrDefault(false)
    }

    /**
     * Issue #1568 (P0-2): vouch the SSH transport alive (connected + async-close not initiated;
     * #1222) before the ladder evicts the warm lease. A real death fails the vouch so the ladder
     * still escalates. Feeds [preferFreshTransportForPassiveReattach].
     */
    private fun transportVouchedAlive(): Boolean =
        sessionRef?.let { it.isConnected && !it.isCloseInitiated } == true

    /**
     * Issue #964 test seam: pin the keepalive-alive verdict the probe defers to.
     * `true` reproduces a LIVE-but-slow link (transport keepalive still riding
     * through) so the probe must NOT redial; `false` reproduces a genuinely dead
     * transport (keepalive gave up) so the probe is free to declare. `null`
     * (production default) reads the real [sessionRef] keepalive signal.
     * `@Volatile` so the probe-loop coroutine sees the flip from the test thread.
     */
    @Volatile
    internal var forceTransportProvenAliveForTest: Boolean? = null

    /** Issue #964 test seam: drive the keepalive-coordination guard synchronously. */
    internal fun isTransportKeepAliveProvenAliveRecentlyForTest(): Boolean =
        isTransportKeepAliveProvenAliveRecently()

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
     * Issue #1098 (item 3) test seam (#780 synthetic-injection model): when set, the
     * host is GENUINELY UNRECOVERABLE — every bounded reattach/reconnect attempt fails
     * for the whole window, modelling a host that is truly gone (sshd dead / port
     * blackholed / a network cut that stays cut past the reconnect ladder's bound). It
     * makes BOTH the silent-reattach grace-loop primitives ([silentlyReattachAfterPassiveDisconnect]
     * / [silentlyReconnectTransportAfterPassiveDisconnect]) AND the auto-reconnect ladder's
     * fresh-dial ([runConnect] on a reconnect trigger) fail-fast, so the bounded ladder
     * genuinely EXHAUSTS and the controller reaches the honest [ConnectionState.Unreachable]
     * → the visible "Disconnected from …" band.
     *
     * Unlike [forceCleanOutageForTest] (a RECOVERABLE outage that clears so the SAME
     * session auto-recovers silently — the items-1+2 calm ride-through), this seam stays
     * armed: it reproduces the maintainer's "frozen-but-live screen on a DEAD host"
     * symptom that a kill-the-worker-only fixture cannot, because there the sshd LISTENER
     * stays up so the fresh-transport re-dial recovers (the round-3 finding). The cold
     * OPEN (a non-reconnect trigger) is left alone so the test's initial attach still
     * succeeds. Production never sets it (default false). `@Volatile` so the grace-loop /
     * reconnect coroutines see the flip from the instrumentation thread.
     */
    @Volatile
    internal var forceUnrecoverableHostForTest: Boolean = false

    /**
     * Issue #959 test seam (#780 synthetic-injection model): when set, the
     * background teardown ([closeCurrentConnectionAndJoin]) closes the live `-CC`
     * client but PRESERVES the pane runtime — `paneRows`, their `TerminalSurfaceState`s,
     * the per-pane output producers, input queues + drain jobs, and the
     * `paneProducerClients` bindings — instead of clearing them. This deterministically
     * reproduces the on-device RACE the freeze needs: a pane that SURVIVES the teardown's
     * clear into the foreground `connect(LifecycleReattach)` reconcile, so the reconcile
     * takes the REUSE branch against a FRESH client while the producer/input are still
     * wired to the now-dead client. The clean teardown clears `paneRows` (no freeze), so
     * the journey can only enter the failing state by injecting it here — hard-fail
     * otherwise, never self-skip. Default false (production clears everything).
     */
    @Volatile
    internal var preservePaneRuntimeOnBackgroundTeardownForTest: Boolean = false

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
        if (passiveTransportDropEffects.classify(client) == PassiveDropArm.Ignore) return false
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
     * Issue #1072 test seam — own an attachment upload through the EXACT production
     * ownership line ([attachmentUploadJob] = a [viewModelScope] child) so a unit
     * proof can assert [closeCurrentConnectionAndJoin] cancel-and-joins it. The
     * composer path's own `stagePromptAttachments` needs an Android `contentResolver`
     * + `Uri`s (Robolectric-only), so this seam drives the SAME field + scope the
     * production upload uses, letting the JVM gate assert the teardown-cancels-upload
     * wiring (the Failure-2 root cause) deterministically.
     */
    internal fun startTrackedAttachmentUploadForTest(block: suspend () -> Unit): Job {
        val job = viewModelScope.async { block() }
        attachmentUploadJob = job
        return job
    }

    /** Issue #1072 test seam: is the tracked attachment upload still in flight? */
    internal fun attachmentUploadActiveForTest(): Boolean =
        attachmentUploadJob?.isActive == true

    /** Issue #1072 test seam: drive the production connection teardown directly so a
     *  unit proof can assert it cancels an in-flight attachment upload (the reconnect
     *  wedge's root cause) — the SAME `closeCurrentConnectionAndJoin` the reconnect
     *  ladder runs. */
    internal suspend fun closeCurrentConnectionAndJoinForTest() =
        closeCurrentConnectionAndJoin()

    /** Issue #1072 test seam: is a connect attempt's single-flight guard active? */
    internal fun connectJobActiveForTest(): Boolean = connectJob?.isActive == true

    @androidx.annotation.VisibleForTesting
    internal fun paneProducerClientIdentityForTest(paneId: String): Int? =
        paneProducerClients[paneId]?.let { System.identityHashCode(it) }

    /**
     * Issue #1205 test seam: is [paneId]'s live-output producer currently attached
     * and active? The overflow-recovery proof uses this to confirm the producer
     * was REATTACHED after a reseed (live %output resumes), not left dead.
     */
    @androidx.annotation.VisibleForTesting
    internal fun paneProducerActiveForTest(paneId: String): Boolean =
        paneProducerJobs[paneId]?.isActive == true

    /**
     * Issue #1205 test seam: is an overflow reseed-and-reattach recovery currently
     * in flight for [paneId]? The connected exhaustion journey polls this to know
     * a recovery cycle has fully COMPLETED (the in-flight slot released in its
     * `finally`) before tripping the next overflow, so each trip is counted against
     * [OVERFLOW_RECOVERY_MAX_ATTEMPTS] rather than de-duped as a still-in-flight
     * burst signal — making the budget exhaustion deterministic on-device.
     */
    @androidx.annotation.VisibleForTesting
    internal fun paneOverflowRecoveryInFlightForTest(paneId: String): Boolean =
        paneId in paneOverflowRecoveryInFlight

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
        // S6 (#1329): drive Attaching via the same-host Switch intent (retired Attaching arm).
        val (ctrlHost, ctrlTarget) = controllerHostAndTarget()
        if (ctrlHost != null && ctrlTarget != null) connectionManager.switchTo(ctrlHost, ctrlTarget)
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
        // Issue #1328 (S5): while the ladder is in flight the loop is the sole counter
        // advancer; each rung's `-CC` teardown flips the drop oracle, so the driver's
        // extra drops would double-advance + exhaust it early — suppress them (not `Up`).
        if (autoReconnectJob?.isActive == true) return true
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

    private fun recordSuppressedDropDiagnostic(diagnostic: SuppressedDropDiagnostic) {
        lastSuppressedDropDiagnostic = diagnostic
    }

    private fun suppressedDropDiagnosticFor(
        disconnectEvent: TmuxDisconnectEvent,
        source: String,
    ): SuppressedDropDiagnostic =
        SuppressedDropDiagnostic(
            cause = disconnectEvent.reason.logValue,
            fields = mapOf(
                "transportDropSource" to source,
                "disconnectReason" to disconnectEvent.reason.logValue,
                "disconnectSource" to disconnectEvent.source,
                "disconnectIntent" to disconnectEvent.intent,
                "commandKind" to disconnectEvent.commandKind,
                "timeoutMode" to disconnectEvent.timeoutMode,
                "exceptionClass" to disconnectEvent.exceptionClass,
                "message" to disconnectEvent.message,
            ),
        )

    private fun suppressedDropDiagnosticFields(
        diagnostic: SuppressedDropDiagnostic?,
    ): Array<Pair<String, Any?>> =
        diagnostic?.fields
            ?.map { (key, value) -> key to value }
            ?.toTypedArray()
            ?: emptyArray()

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

    /** Id-keyed session reveal projection; the screen renders strictly from [revealState]. */
    private val revealController: TmuxRevealController =
        TmuxRevealController(
            hostKeyForTarget = { target -> hostKeyFor(target.toSshLeaseTarget().leaseKey) },
        )

    public val revealState: StateFlow<RevealState> = revealController.state

    /** Pump the authoritative [ConnectionController.state] into the reveal projection. */
    private fun driveRevealStateMachine() {
        viewModelScope.launch {
            connectionManager.stateFlow.collect { state ->
                revealController.onConnectionState(state)
            }
        }
    }

    init {
        driveRevealStateMachine()
    }

    private fun navigateRevealTo(target: ConnectionTarget) {
        revealController.navigateTo(target)
    }

    private fun offerRevealSeed(paneId: String, frame: String) {
        val target = activeTarget ?: connectingTarget ?: return
        revealController.offerSeed(target, paneId, frame)
    }

    private fun promoteRevealLiveForActiveTarget() {
        val target = activeTarget ?: connectingTarget ?: return
        val activePaneId = _panes.value.firstOrNull()?.paneId ?: return
        revealController.promoteLive(target, activePaneId)
    }

    private fun controllerHostAndTarget(): Pair<HostKey?, SessionId?> {
        return revealController.hostAndSessionId(activeTarget, connectingTarget)
    }

    private fun controllerHostKey(target: ConnectionTarget): HostKey =
        revealController.hostKey(target)

    private fun controllerSessionId(target: ConnectionTarget): SessionId =
        revealController.sessionId(target)

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
        // (reconnect jobs, generation counter, named coroutine jobs, classifier
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
        val projected = connectionStatusForController(connectionManager.state, inlineState)
        _connectionStatus.value =
            if (withinGraceSilentHealInFlight && projected is ConnectionStatus.Reconnecting) {
                // Issue #1098 (item 4 / #635): the within-grace SILENT heal re-opens the
                // dropped `-CC` (controller `Reattaching`/`Reconnecting`), but a brief
                // background→foreground ride-through must read the CALM `Connected` — never
                // a Reconnecting bar / Connecting overlay. Hold `Connected` (the same
                // host/port/user payload) for the bounded heal; the heal job's completion
                // handler clears the flag, after which the live `Live` re-projects naturally
                // (success) or the normal calm-Reconnecting ladder shows (genuine failure).
                ConnectionStatus.Connected(projected.host, projected.port, projected.user)
            } else {
                projected
            }
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
     * EPIC #687 slice 1c-iii / EPIC #792 Slice A: drive the controller's INTENT.
     *
     * Roadmap slice **S6 (#1329)**: the OPEN / SWITCH / REVEAL arms are RETIRED — open/switch
     * SUBMIT `Enter`/`Switch` at the flow edges so the controller DECIDES the transition, and
     * the reveal edge submits `revealLive` at each `setConnectionState(Live)` (D28: controller
     * is authority, not a mirror). This drives ONLY the remaining reconnect/gone/unreachable arms.
     */
    private fun driveControllerIntent(state: ConnectionState) {
        val (host, target) = controllerHostAndTarget()
        when (state) {
            // S6 (#1329): open/switch/reveal are controller-decided at the flow edges;
            // these inline states are display-payload carriers only now.
            is ConnectionState.Idle,
            is ConnectionState.Connecting,
            is ConnectionState.Attaching,
            is ConnectionState.Live,
            is ConnectionState.Backgrounded,
            -> Unit
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

    /** S6 (#1329): OPEN intent — controller decides warm→Attaching / cold→Connecting. */
    private fun submitControllerOpen(target: ConnectionTarget) {
        connectionManager.enter(controllerHostKey(target), controllerSessionId(target))
    }

    /** S6 (#1329): same-host fast SWITCH intent (→ Attaching, no re-handshake). */
    private fun submitControllerSwitch(target: ConnectionTarget) {
        connectionManager.switchTo(controllerHostKey(target), controllerSessionId(target))
    }

    /** S6 (#1329): REVEAL intent at each `setConnectionState(Live)` edge (retired `Live` arm). */
    private fun revealControllerLive() {
        val (host, target) = controllerHostAndTarget()
        if (host != null && target != null) connectionManager.revealLive(host, target)
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

    // Issue #1326 (S3): the legacy switch-hides-terminal "hide the terminal
    // during a cross-session switch" flag is DELETED (D22 hard-cut). It was a
    // SECOND, unsynchronised source for the surface-hold decision alongside the
    // id-keyed [RevealStateMachine] — no production render read it (the screen
    // holds the terminal off [revealState] alone), so it was a dead write that
    // could only DIVERGE from the reveal machine. The single hold authority is now
    // the reveal machine -> [SessionSurfaceState].

    private var attachPanesReadyTimeoutMs: Long = ATTACH_PANES_READY_TIMEOUT_MS
    private var activeAttachMilestone: AttachMilestone? = null

    // Issue #886: the blank-watchdog tick bound for the stuck-attach-reveal
    // safety net.
    private val connectedBlankWatchdogMaxTicks: Int = CONNECTED_BLANK_WATCHDOG_MAX_TICKS

    // Issue #886: when false, [armConnectedBlankWatchdog] is suppressed entirely.
    // Used ONLY by the watchdog-in-isolation characterization tests that manually
    // arm the watchdog after connect(), so the connect()-auto-armed watchdog does
    // not run a SECOND, concurrent watchdog over the same blank pane during setup.
    // Always true in production.
    private var connectedBlankWatchdogAutoArmEnabled: Boolean = true

    internal fun setConnectedBlankWatchdogAutoArmEnabledForTest(enabled: Boolean) {
        connectedBlankWatchdogAutoArmEnabled = enabled
    }

    // Issue #966/#967: the steady-state stale-render watchdog tick bound,
    // injectable so a connected test can exhaust it quickly. Defaults to the
    // production constant — production behavior unchanged.
    private var staleRenderWatchdogMaxTicks: Int = STALE_RENDER_WATCHDOG_MAX_TICKS

    internal fun setStaleRenderWatchdogMaxTicksForTest(maxTicks: Int) {
        staleRenderWatchdogMaxTicks = maxTicks
    }

    // Issue #966/#967: when false, [armActivePaneStaleRenderWatchdog] is
    // suppressed entirely. Always true in production; a test seam toggles it.
    private var staleRenderWatchdogAutoArmEnabled: Boolean = true

    internal fun setStaleRenderWatchdogAutoArmEnabledForTest(enabled: Boolean) {
        staleRenderWatchdogAutoArmEnabled = enabled
    }

    // Issue #1166: the single active stale-render watchdog loop. Cancelled and
    // replaced on every (re-)arm so rapid A→B→A session switching can never
    // stack multiple concurrent 4s `capture-pane` loops (arm-dedup).
    private var staleRenderWatchdogJob: Job? = null

    @androidx.annotation.VisibleForTesting
    internal fun staleRenderWatchdogJobForTest(): Job? = staleRenderWatchdogJob

    // Issue #1166 (heal-latency fix): a WAKE signal so fresh `%output` on the
    // ACTIVE visible pane snaps a BACKED-OFF watchdog straight back to the hot
    // cadence instead of waiting out the current long (8s/16s) `delay(...)`.
    // Without this the back-off is purely poll-based: a redraw arriving ~1s into
    // a 16s backed-off window would not be captured/diffed/healed until the tick
    // ~15s later — a visible partial-black regression vs the ≤4s hot bound. The
    // watchdog loop RACES its backed-off delay against a receive on this channel;
    // [recordVisiblePaneOutput] fires it on every active-pane %output. CONFLATED
    // so a burst of output collapses to one pending wake and the streaming path's
    // trySend never suspends.
    private val staleRenderWatchdogWake = Channel<Unit>(Channel.CONFLATED)

    private val staleRenderSparseWakeBaselines = StaleRenderSparseWakeBaselines()

    // Issue #1166: a test override for [PowerManager.isInteractive] (screen-on).
    // Null → read the real PowerManager (or default on when no context). A JVM
    // test drives the foreground/screen gate deterministically without an AVD.
    private var screenInteractiveOverrideForTest: Boolean? = null

    @androidx.annotation.VisibleForTesting
    internal fun setScreenInteractiveForTest(interactive: Boolean?) {
        screenInteractiveOverrideForTest = interactive
    }

    // Issue #1166 test seam: set the active pane's last-`%output` wall-clock so a
    // JVM test can deterministically simulate "the pane streamed new output since
    // the last watchdog tick" (the back-off snap-to-hot signal) without depending
    // on the real elapsedRealtime clock, which the coroutine test scheduler does
    // not advance.
    @androidx.annotation.VisibleForTesting
    internal fun setPaneLastOutputAtMsForTest(paneId: String, atMs: Long) {
        paneLastOutputAtMs[paneId] = atMs
    }

    /**
     * Issue #640: pane IDs already seeded (via [healActivePaneIfStaleRender]) during
     * the current attach. The cold-open reveal uses this to skip the redundant
     * second full reseed for panes the preload pass already painted, so a fresh
     * connect pays exactly one capture per visible pane and only *reused*
     * (reattach) panes are re-captured. Reset at the start of each attach.
     */
    private val panesSeededThisAttach: MutableSet<String> =
        java.util.Collections.newSetFromMap(ConcurrentHashMap())

    /**
     * Issue #830: pane IDs whose attach-time seed `capture-pane` round-trip is
     * currently IN FLIGHT (marked the instant [healActivePaneIfStaleRender] starts, before
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

    /** Issue #1353 R3 — per-pane single-flight owner for the heal chokepoint (spike §3 race). */
    private val renderHealCoordinator = RenderHealCoordinator()
    private var autoReconnectDelaysMs: List<Long> = DEFAULT_AUTO_RECONNECT_DELAYS_MS
    private var passiveDisconnectGraceMs: Long = PASSIVE_DISCONNECT_GRACE_MS
    private var silentReattachTimeoutMs: Long = PASSIVE_DISCONNECT_SILENT_REATTACH_TIMEOUT_MS
    private var keepaliveDeathQuietResetMs: Long = KEEPALIVE_DEATH_REDIAL_QUIET_RESET_MS

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

    /**
     * Issue #1072 — the in-flight attachment-upload coroutine, OWNED by the VM so
     * a connection teardown can cancel it.
     *
     * The composer attach (`onStageAttachments`) used to run the upload directly in
     * the SCREEN's coroutine scope, so [closeCurrentConnectionAndJoin] (the reconnect
     * ladder's teardown) cancel-and-joined every OTHER job but NOT this one: a
     * large/slow upload stayed blocked in `output.write`/`command.join` on the dying
     * `-CC` session while the teardown drained/closed the same dispatcher, racing it.
     * That race could leave the single-flight `connectJob`/`autoReconnectJob` guards
     * stuck `isActive`, which then suppressed ALL subsequent auto + manual reconnects
     * until the app was restarted (the maintainer's "reconnect wedges" — #1072).
     *
     * Tracking the upload as a [viewModelScope] job lets the teardown deterministically
     * `cancelAndJoin` it BEFORE it drops the transport, so the reconnect ladder never
     * races a free-floating writer on the dead session.
     */
    private var attachmentUploadJob: Job? = null
    // Issue #938 (S2-3): `appActive` is written on Main (onStart/onStop) but
    // read off-Main from the port-detection dispatcher (e.g. [scanDecoded],
    // [confirmAndSurfaceDetectedPort]). @Volatile guarantees cross-thread
    // visibility so an off-Main read sees the latest background/foreground
    // flip instead of a stale cached value.
    @Volatile
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

    // Issue #1185: how many times the SELECTED session has transparently
    // re-dialled after its lease acquire was woken with a coalesced-connect
    // cancel (the create-then-switch supersede). The pool slot is already
    // cleared, so a re-dial normally becomes a fresh owner and connects on the
    // first retry; the cap only stops a pathological repeat (a host that keeps
    // getting its in-flight connect cancelled) from looping forever, at which
    // point the honest terminal error + working Retry surfaces. Reset to 0 on a
    // successful attach.
    private var coalescedCancelRedialAttempts: Int = 0

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
     * Issue #1158 (REOPENED chain #962→#975→#1057→#1158): the STICKY set of tmux
     * session ids whose visible pane has been observed on the ALTERNATE screen
     * buffer — the maintainer's real "agent launched directly inside an existing
     * shell" case. That session records `@ps_agent_kind=shell`, so the
     * confirmed-shell verdict is never cleared (live detection never binds for
     * node-wrapped Claude / Codex `/proc` / Z.AI transcript-path fleets), and
     * every other tab signal is false — the Conversation tab was gone for the
     * session's whole life. A full-screen agent TUI holds the alternate buffer for
     * its run, which the tmux SERVER reports via `#{alternate_on}` on every
     * `list-panes` reconcile ([latchAltBufferAgentsFromParsed]),
     * detection-INDEPENDENTLY. (The SERVER truth — NOT the CLIENT emulator's
     * `isAlternateBufferActive`, which stays false because the `-CC` capture-pane
     * seed replays screen TEXT onto the client's MAIN buffer and an idle agent
     * emits no fresh `?1049h` — is what makes this fire on the real path.)
     *
     * STICKY (latch): a session is only ever ADDED here, never removed within a
     * runtime — once the tab has been shown it stays for the session's life even
     * if the buffer later leaves alt-mode or detection drops, so a failed/again-
     * null detection can't collapse the tab back to Terminal-only. Cleared only
     * with the other per-runtime caches on park/restore/clear so a different
     * session never inherits a stale latch.
     *
     * POSITIVE signal: a genuine plain shell at a prompt (main buffer) never
     * enters this set, so the #894/#815 no-flap invariant holds.
     */
    private val altBufferAgentSessionIds: MutableSet<String> =
        ConcurrentHashMap.newKeySet()

    /**
     * Issue #1158: the per-pane projection of [altBufferAgentSessionIds] over the
     * current pane rows, consumed by the screen to force the Conversation tab
     * present on the visible pane. Mirrors [confirmedShellPaneIds]'s shape:
     * republished whenever the pane set changes or a new alt-buffer sighting
     * latches a session.
     */
    private val _altBufferAgentPaneIds: MutableStateFlow<Set<String>> =
        MutableStateFlow(emptySet())
    public val altBufferAgentPaneIds: StateFlow<Set<String>> =
        _altBufferAgentPaneIds.asStateFlow()

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
    // Issue #1169: the live on-screen viewport grid, fed ONLY by the real
    // Compose measure ([resizeRemotePty] / `onTerminalSizeChanged`). Unlike
    // remoteColumns/remoteRows — which [restoreCachedRuntime] OVERWRITES with a
    // parked (possibly stale/shrunk) value on a warm reattach — this survives
    // cached-runtime restores and session switches because the phone's physical
    // viewport is the same across every session on the device. It is the FLOOR
    // for any size sent to tmux: the tmux window (and therefore the alt-screen
    // agent TUI) must never be left SMALLER than what the emulator will actually
    // render, or the pane is drawn cut with black below (the maintainer's
    // Codex-cut symptom). SOFT_INPUT_ADJUST_NOTHING means the view pixel size
    // does NOT re-measure on a within-grace foreground return, so the cached
    // replay path never re-derives the full grid on its own — the floor does.
    private var liveMeasuredColumns: Int = 0
    private var liveMeasuredRows: Int = 0
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
    // a stale recorded kind/source. Issue #821 branch 1 carries the sibling
    // `@ps_agent_source` exact transcript identity through the same cache so
    // later opens/retries do not fall back to same-kind mtime selection.
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
    // Issue #942 (black-screen B2): the wall-clock (elapsedRealtime) of the last
    // `%output` chunk observed for a pane. A remembered/seeded agent whose grep
    // detection comes back EMPTY but whose `-CC` channel is still actively
    // streaming output is WEDGED-but-alive (the #470/#835 capture-behind-a-busy-
    // agent symptom), not exited — the empty grep raced the busy channel. Such a
    // `Resolved(null)` must NOT be counted toward [AGENT_EXIT_CONFIRMATIONS] (which
    // tears the Conversation row down to the raw black Terminal). #897 protected
    // the `Unavailable` (probe-threw) branch; this protects the still-streaming
    // `Resolved(null)` branch. A genuinely-exited agent stops emitting output, so
    // its empty grep arrives with NO recent activity and tears down correctly.
    private val paneLastOutputAtMs: MutableMap<String, Long> = ConcurrentHashMap()
    // Issue #1175: the wall-clock (elapsedRealtime) of the last time a `capture-pane`
    // seed successfully LANDED into this pane's emulator (stamped in
    // [captureAndApplyPaneSnapshot] at the same point [panesSeededThisAttach] records the
    // seed). An ABSENT entry means "no seed has ever landed for this pane" — the
    // discriminator between the `never_seeded` and `capture_empty` black-frame classes —
    // and its age (now - stamp) is the `msSinceLastSeed` fingerprint field on the
    // `black_frame_observed` diagnostic. Diagnostic accounting only; it never gates a
    // heal decision.
    private val paneLastSeedAtMs: MutableMap<String, Long> = ConcurrentHashMap()
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
    private val panePortDetectorClients: MutableMap<String, TmuxClient> = ConcurrentHashMap()
    private val panePortDetectorGenerations: MutableMap<String, Long> = ConcurrentHashMap()
    private val scannedConversationPortEventKeys: MutableSet<String> =
        ConcurrentHashMap.newKeySet()

    // Issue #896: scope-level safety net for the SSH/tmux close/EOF cascade.
    //
    // The crash class: closing the attached/last session → the gateway kill
    // destroys tmux → the live `-CC` control client EOFs → that EOF fans out
    // to several `bridgeScope.launch {}` collectors (client.events →
    // structural reconcile, client.disconnected, per-pane output/port, agent
    // tail / detection / conversation watchdog) AT THE SAME MOMENT the scopes are
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
    // the driver-owned current-client drop path → ConnectionController, which
    // surfaces the escapable Reconnecting/Reconnect band (#895's concern). A
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

    // Issue #935 R1 (#928 D2): crash-containment for the bare
    // `viewModelScope.launch {}` TEARDOWN/CLOSE/DETACH/RECOVERY sites.
    //
    // The #896 [bridgeExceptionHandler] net above is SCOPE-LOCAL — only
    // `bridgeScope.launch {}` (and child scopes derived from its context, e.g.
    // the layout coalescer) inherit it, plus two hand-retrofitted
    // `viewModelScope.launch(bridgeExceptionHandler)` sites (the grace and
    // auto-reconnect launches). But ~20 bare `viewModelScope.launch {}` sites do
    // NOT inherit it. The teardown/close/detach/recovery ones
    // (`closeCurrentConnectionAndJoin`, `closeCachedRuntime`, `detachCleanly`,
    // recovery re-`connect()`) issue SSH/tmux IO against a transport the close is
    // racing to tear down — the exact `SshException`/`TransportException`/
    // `EOFException` family the captured June-8 crash specimens come from. Today
    // those bodies are guarded by inner `runCatching`, so coverage is
    // BY-CONVENTION: a single unguarded throw (a future edit, a `cancelAndJoin`
    // rethrowing a non-cancellation completion cause, a path not yet wrapped)
    // propagates to `viewModelScope` (NO handler) →
    // `Thread.defaultUncaughtExceptionHandler` → PROCESS DEATH (the "always
    // restarting / it zoomed out then started over" class).
    //
    // [launchContainedTeardown] makes the containment BY-CONSTRUCTION: it routes
    // the teardown launch's context through the SAME [bridgeExceptionHandler], so
    // an uncaught throw is contained + diagnostically logged (DiagnosticEvents +
    // a non-fatal CrashReporter record — the swallowed throw stays VISIBLE)
    // instead of killing the process.
    //
    // ANTI-MASKING (the #896/#895 invariant, mirrored from the KDoc at the
    // `bridgeExceptionHandler` above): this does NOT and CANNOT eat a genuine
    // transport DROP that must drive reconnect. A real `-CC` drop arrives as a
    // normal `client.disconnected` EMISSION on a latched StateFlow →
    // the driver-owned current-client drop path, which surfaces the escapable
    // Reconnecting/Reconnect band. A
    // CoroutineExceptionHandler is only ever invoked for an UNCAUGHT THROW; it is
    // never on the path of a normal flow emission, so the drop→band routing is
    // preserved by construction. The handler's sole job is to stop a
    // teardown-time throw from killing the process — not to suppress lifecycle
    // signals.
    private fun launchContainedTeardown(
        block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit,
    ): Job =
        viewModelScope.launch(bridgeExceptionHandler) {
            // Issue #935 R1 (#780 synthetic-injection model): when a test arms
            // [teardownLaunchFailureForTest], throw at the body boundary so the
            // reproduction deterministically exercises an UNCAUGHT throw escaping
            // a bare teardown launch. Null (production) → no-op. This is the seam
            // that proves the containment is wired: WITHOUT the handler the throw
            // reaches the thread's uncaught handler (process death); WITH it the
            // throw lands in [bridgeExceptionHandler] and is recorded non-fatal.
            teardownLaunchFailureForTest?.let { throw it }
            block()
        }

    /**
     * Test-only synthetic-failure seam (issue #935 R1, #780 model). When set,
     * every [launchContainedTeardown] body throws this at its boundary, so a
     * unit test can prove a bare teardown/close/detach/recovery launch's uncaught
     * throw is CONTAINED by the scope-level net (lands in
     * [bridgeCoroutineFailureProbe]) instead of reaching the thread's
     * uncaught-exception handler (= process death on device). Null in production.
     */
    @get:androidx.annotation.VisibleForTesting
    @set:androidx.annotation.VisibleForTesting
    internal var teardownLaunchFailureForTest: Throwable? = null

    /** #1495 (Part 3, #780 model): inject a throw in the reseed→arm window; the finally fence must still re-arm. */
    internal var reseedArmWindowFailureForTest: Throwable? = null

    // Reuse pane rows across reconciles so the attached TerminalSurfaceState
    // (and its emulator scrollback) survives layout-change events. Keyed by
    // pane ID; entries are removed when tmux drops the pane.
    private val paneRows: MutableMap<String, TmuxPaneState> = ConcurrentHashMap()

    // Track per-pane producer jobs so we cancel them when the pane goes
    // away. The jobs are children of bridgeScope; cancelling the parent
    // would also stop them, but we want to release the bridge cleanly
    // mid-lifecycle when a single pane closes.
    private val paneProducerJobs: MutableMap<String, Job> = ConcurrentHashMap()
    // Issue #1206: background seed-recovery jobs for ACTIVE-runtime panes whose
    // seed capture came back empty/wedged (e.g. the surface-error recovery
    // reseed in [reseedRecoveredSurface]). The prewarm path tracks its own local
    // map per [PrewarmedPaneRuntime]; this is the active-runtime registry so the
    // retry/deferred-reseed is cancellable when the runtime is deactivated.
    private val paneSeedRecoveryJobs: MutableMap<String, Job> = ConcurrentHashMap()
    private val paneOutputActivityJobs: MutableMap<String, Job> = ConcurrentHashMap()
    // Issue #959: track the EXACT `-CC` client each pane's output producer +
    // input drain are currently bound to. A beyond-grace background→foreground
    // fires `connect(LifecycleReattach)` with a fresh SSH lease + fresh
    // [TmuxClient], but [applyParsedPanes]'s reuse branch keeps the EXISTING
    // [TmuxPaneState] (so the emulator scrollback survives) and copies metadata
    // only — it never re-bound the producer/input. Result: a reused pane stays
    // wired to the DEAD client (`outputFor(paneId)` never delivers new %output;
    // the stale buffer stays painted; key bar / IME bytes route nowhere) — the
    // "content on screen but frozen, no I/O" symptom. This map lets the reuse
    // branch detect the stale binding and re-bind to the live client (the
    // post-grace reattach has no `rebindVisiblePaneProducersToClient` call that
    // the silent passive-reattach paths rely on). Identity comparison only.
    private val paneProducerClients: MutableMap<String, TmuxClient> = ConcurrentHashMap()

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

    // Issue #1205: per-pane sliding window of backlog/seed-gate overflow recovery
    // attempts (reseed-and-reattach). Bounds a saturated channel to
    // [OVERFLOW_RECOVERY_MAX_ATTEMPTS] reseeds inside [OVERFLOW_RECOVERY_WINDOW_MS]
    // before the pane falls to the `surfaceError` card, so a burst that keeps
    // overflowing after each reseed cannot loop into a reseed storm.
    private val paneOverflowRecoveryTimestamps: MutableMap<String, ArrayDeque<Long>> =
        ConcurrentHashMap()

    // Issue #1205: panes with a reseed-and-reattach recovery currently in flight.
    // A single overflow BURST fires the overflow signal many times (once per
    // dropped frame); this de-dups the burst to ONE recovery per pane so we don't
    // launch a reseed per dropped event. Cleared when the recovery job completes.
    private val paneOverflowRecoveryInFlight: MutableSet<String> =
        ConcurrentHashMap.newKeySet()

    /**
     * Open the SSH transport, spawn `tmux -CC` against [sessionName], and
     * begin maintaining [panes].
     *
     * Idempotent for the same destination. If the hand-rolled navigator
     * reuses this ViewModel for a different host/session tuple, we tear
     * down the old control channel before opening the new one so a
     * dashboard row tap actually attaches to the requested tmux session.
     * [keyPath] is the resolved absolute path of the user's private key
     * on disk, the same way it is consumed from the host picker.
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
        // Issue #666 / #1155: internal guard so the existence preflight (below) can
        // re-enter [connect] once it has confirmed the session is still alive
        // without preflighting a second time. Never set by external callers.
        skipExistencePreflight: Boolean = false,
    ) {
        // Issue #666: a foreground cold-restore must NOT resurrect a session
        // killed elsewhere while the app was backgrounded. Before we attach —
        // and crucially BEFORE we can activate a stale warm/cached runtime
        // whose `-CC` channel would EOF and trigger an auto-reconnect that
        // recreates the session via `new-session -A` — probe `tmux has-session`
        // over the pooled transport. If the session is gone, drop to the list
        // (and broadcast the stale-session recreate prompt, #1155) instead of
        // attaching anything. ColdRestore always preflights (the genuine
        // resume-from-persisted-last-session path); [OpenExisting] preflights too,
        // but only for a genuine COLD open (handled below, after the warm-path
        // determination).
        if (trigger == TmuxConnectTrigger.ColdRestore && !skipExistencePreflight) {
            preflightSessionExistence(
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
                originalTrigger = TmuxConnectTrigger.ColdRestore,
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
        // Issue #1072: an EXPLICIT manual Reconnect must be able to PREEMPT an
        // in-flight connect to the same target, never be deduped into a no-op. If a
        // prior connect attempt ever wedged (the upload-teardown race, now fixed by
        // owning+cancelling the upload job), this same-target dedup would otherwise
        // suppress the user's Reconnect tap forever — exactly the "I have to restart
        // the app" report. The new connectJob below cancel-and-joins the previous
        // one, so preempting is clean. The dedup still holds for non-manual triggers
        // (rapid UserTap / network) so a healthy in-flight connect is not restarted.
        if (connectJob?.isActive == true &&
            connectingTarget == target &&
            trigger != TmuxConnectTrigger.Reconnect
        ) {
            return
        }
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
        // Issue #1155 (Part B): a NORMAL tap of a persisted session row
        // ([OpenExisting]) that is a genuine COLD open — NOT a fast-switch and with
        // NO warm cached runtime (either would already prove the session is alive)
        // — preflights `tmux has-session` before attaching. If the session is
        // CONFIRMED GONE, [preflightSessionExistence] routes to [failSessionEnded]
        // → the "create a new session in this folder?" recreate prompt instead of
        // silently resurrecting a fresh shell via `new-session -A`. A warm/fast open
        // skips the probe (no added latency to the maintainer's instant switch), and
        // any AMBIGUOUS probe (no lease / exec error) FAILS OPEN to the normal
        // attach — so a transient reconnect blip never triggers the prompt.
        if (trigger == TmuxConnectTrigger.OpenExisting &&
            !skipExistencePreflight &&
            !willFastSwitch &&
            !runtimeCache.contains(target.toRuntimeKey())
        ) {
            preflightSessionExistence(
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
                originalTrigger = TmuxConnectTrigger.OpenExisting,
            )
            return
        }
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

        // S6 (#1329): submit the open/switch event — the controller decides the transition.
        if (willFastSwitch) submitControllerSwitch(target) else submitControllerOpen(target)

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
        // the reveal-machine hold). The runtime-cache-hit path returned earlier
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
        // warm-cache content. (The terminal is held by the reveal machine, not a
        // separate flag — #1326.)
        if (willFastSwitch) {
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
                    // and rely on the reveal-machine hold (set synchronously
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

    private fun markSuccessfulAttachForNetworkCoalescing(
        target: ConnectionTarget,
        trigger: TmuxConnectTrigger,
    ) {
        // Issue #1185: a successful attach means the coalesced-cancel re-dial
        // worked (or was never needed), so its budget resets for the next chain.
        // (The #1537 hard-cut deleted the sibling stale-lease counter reset.)
        coalescedCancelRedialAttempts = 0
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

    /**
     * Issue #998: the most recent [probeServerLiveness] flag passed to
     * [createTmuxClient]. Exposed for tests because the test client-factory
     * override keeps its 3-arg shape (so it never sees the flag); this
     * side-channel lets a test assert the reconnect path requested the
     * server-death probe (`true`) while the explicit-new path did not (`false`).
     */
    @Volatile
    private var lastProbeServerLiveness: Boolean = false

    @androidx.annotation.VisibleForTesting
    internal fun lastProbeServerLivenessForTest(): Boolean = lastProbeServerLiveness

    private fun createTmuxClient(
        session: SshSession,
        sessionName: String,
        startDirectory: String?,
        // Issue #666: attach-OR-create (true) vs attach-only (false). Only the
        // genuine cold-restore path passes false so a session killed elsewhere
        // is not resurrected; every create/reconnect/switch path keeps true.
        createIfMissing: Boolean = true,
        // Issue #998: probe whether the tmux SERVER is alive before `new-session
        // -A`. True only on a reattach to an expected-existing session (a
        // reconnect / lifecycle / network-reconnect): a dead server then throws
        // [TmuxServerDeadException] so we drop to the list instead of silently
        // booting a fresh empty server. The explicit user "new session" intent
        // passes false — a brand-new session legitimately wants a fresh server.
        probeServerLiveness: Boolean = false,
    ): TmuxClient {
        lastCreateIfMissing = createIfMissing
        lastProbeServerLiveness = probeServerLiveness
        return tmuxClientFactoryOverride?.invoke(session, sessionName, startDirectory)
            ?: tmuxClientFactory.create(
                session,
                sessionName = sessionName,
                startDirectory = startDirectory,
                createIfMissing = createIfMissing,
                probeServerLiveness = probeServerLiveness,
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

    private fun currentRuntimeGuardForClient(client: TmuxClient): RuntimeRefreshGuard? {
        val target = activeTarget ?: return null
        return RuntimeRefreshGuard(
            generation = connectGeneration,
            target = target,
            client = client,
        )
    }

    private fun currentNetworkTransitionProbeGuard(target: ConnectionTarget): RuntimeRefreshGuard? {
        val client = clientRef ?: return null
        val currentTarget = activeTarget ?: connectingTarget ?: return null
        if (!sameSessionIdentity(currentTarget, target)) return null
        return RuntimeRefreshGuard(
            generation = connectGeneration,
            target = target,
            client = client,
        )
    }

    private fun isCurrentNetworkTransitionProbe(guard: RuntimeRefreshGuard): Boolean {
        val currentTarget = activeTarget ?: connectingTarget ?: return false
        return connectGeneration == guard.generation &&
            clientRef === guard.client &&
            sameSessionIdentity(currentTarget, guard.target)
    }

    private fun nextConnectGeneration(): Long {
        connectGeneration += 1L
        return connectGeneration
    }

    /**
     * Issue #145: explicit reconnect seam for the "Reconnect" affordance; routes
     * through the single [TransportEffects] owner (D22, EPIC #792 Slice C). Issue
     * #1574: when both live targets were nulled by a terminal generic failure but a
     * once-opened session is still showing, restore the retained [latestConnectIntent]
     * (never nulled) as the connecting target so the body re-dials in place, not a
     * dead-end (#1521). Confined to this explicit tap — the send-wait auto-redial
     * loop still stops on `no live target`, so a non-retryable failure never loops.
     * `false` only when nothing was ever opened (or the session is genuinely gone).
     */
    public fun reconnect(): Boolean {
        if (activeTarget == null && connectingTarget == null) {
            connectingTarget = latestConnectIntent?.target ?: return false
            refreshReconnectAvailability()
        }
        transportEffects.onManualReconnect()
        return true
    }

    /**
     * EPIC #792 Slice C: the manual / send-triggered reconnect IO BODY, invoked ONLY
     * through the single [TransportEffects] owner. Cancels any in-flight auto-ladder,
     * re-enters `connect(Reconnect)`, returns the connect [Job] (or null, no target).
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
        keepaliveDeathQuietResetMs: Long = this.keepaliveDeathQuietResetMs,
    ) {
        passiveDisconnectGraceMs = graceMs
        this.silentReattachTimeoutMs = silentReattachTimeoutMs
        this.keepaliveDeathQuietResetMs = keepaliveDeathQuietResetMs
    }

    private fun refreshReconnectAvailability() {
        // Issue #1574: a once-opened session ([latestConnectIntent]) stays reconnectable.
        _canReconnect.value =
            activeTarget != null || connectingTarget != null || latestConnectIntent != null
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
    private var lastSuppressedDropDiagnostic: SuppressedDropDiagnostic? = null

    // Issue #1098 (item 4 / #635): true while the SINGLE-GRACE-OWNER within-grace
    // SILENT heal of a dropped `-CC` socket is in flight. The heal MUST re-open the
    // transport (controller `Live -> Reattaching -> Live`), but the user must see the
    // CALM `Connected` ride-through with NO reconnect surface at all — no top
    // Reconnecting bar, no "Attaching…" overlay, no disconnect band. While set, the
    // displayed-status projection ([projectStatusFromController]) holds `Connected`
    // and the [RevealStateMachine] holds the live frame (see
    // [RevealStateMachine.setSilentHealInFlight]). Set + cleared together for the
    // bounded duration of [launchForegroundHealWithinGrace]'s heal job only, so an
    // unexpected (non-grace) foreground drop keeps its normal calm-Reconnecting band.
    private var withinGraceSilentHealInFlight: Boolean = false

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
        // inline background classifier. Feeding the AUTHORITATIVE controller the
        // Background event is the SOLE trigger: its transition INTO Backgrounded fires
        // [onControllerBackgrounded], which runs the inline-equivalent pause-vs-detach
        // bookkeeping AND the clean-detach teardown ([launchBackgroundDetachTeardown]),
        // in that order. The collector resumes off [observeBackground] eagerly on the
        // test main dispatcher (so the bookkeeping is synchronous in tests, exactly as
        // before); on the next Main turn in production (harmless — the only readers of
        // `pendingReattach` are the much-later foreground lifecycle event, and the
        // teardown which runs AFTER the bookkeeping inside the same effect).
        //
        // The inline background event arm is no longer consulted (Slice
        // 2a); the #685 trap is avoided because [onControllerBackgrounded] selects the arm
        // through the connection-core [backgroundEffects] dispatcher, whose injected
        // predicates re-read `clientRef`/`sessionRef`/`inlineConnectionStatus` — the
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
     * inline background dispatch: the controller edge is the TRIGGER, but
     * the pause-vs-detach SELECTION runs through the connection-core [backgroundEffects]
     * dispatcher so behavior is byte-identical (the #685 non-byte-identical-predicate trap —
     * the controller transitions to Backgrounded whenever it holds a host, e.g. even from
     * `Reconnecting`, but the [backgroundEffects] arm also gates on the injected
     * `hasLiveControlChannel` (`clientRef`/`sessionRef`) liveness, so the arm selection
     * re-reads VM state rather than trust the controller's display state).
     *
     * Ordering: the SELECTION ([detachForBackground] sets `pendingReattach` /
     * `pendingBackgroundDetachPreserveTarget`) runs FIRST, then the teardown
     * ([graceEffects.onBackgrounded] -> [launchBackgroundDetachTeardown], which reads
     * those fields). The [BackgroundArm.None] arm (no client/session, or no target) is the
     * no-detach case — neither the bookkeeping nor the teardown runs, matching the inline
     * `else -> Unit` arm.
     */
    private fun onControllerBackgrounded() {
        backgroundEffects.dispatch()
    }

    /**
     * EPIC #687 Slice 1 (#1047): the connection-core background-transition decision authority,
     * the hard-cut replacement for the deleted inline `reduceBackground()`. The predicates
     * re-read the VM's live state each dispatch (the #685 re-read trap): `isReconnecting`
     * reads `inlineConnectionStatus`, `hasTarget` reads `activeTarget`/`connectingTarget`, and
     * the INJECTED [BackgroundEffects.hasLiveControlChannel] port feeds the
     * `clientRef != null || sessionRef != null` liveness the controller's display state lacks
     * (a `Backgrounded` transition does not imply a live `-CC` channel). The arm bodies are the
     * VM's existing IO; the detach arm runs the [detachForBackground] bookkeeping FIRST then the
     * teardown through the single [GraceEffects] owner (identical order to the deleted inline
     * `when`); the no-arm case is the inline `else -> Unit` no-op.
     */
    private val backgroundEffects: BackgroundEffects =
        BackgroundEffects(
            isReconnecting = { inlineConnectionStatus is ConnectionStatus.Reconnecting },
            hasTarget = { activeTarget != null || connectingTarget != null },
            hasLiveControlChannel = { clientRef != null || sessionRef != null },
            pauseReconnectForBackground = { pauseReconnectForBackground() },
            detachForBackground = {
                detachForBackground()
                // The clean-detach teardown reads the bookkeeping just set above; run it
                // AFTER, through the single [GraceEffects] owner.
                graceEffects.onBackgrounded()
            },
        )

    /**
     * EPIC #687 Slice 2 (#1047): the connection-core PASSIVE-TRANSPORT-DROP authority — the
     * hard-cut replacement for the deleted inline `classifyPassiveTransportDrop()` selector
     * (D28 single active path; D22 hard-cut). All four passive-drop call sites (the driver's
     * [ConnectionEffectDriver] `shouldSubmitControlChannelDrop` stale-client gate, the
     * [triggerCleanPassiveDropForTest] seam, the stale-client breadcrumb observer, and the
     * real [handlePassiveClientDisconnect] dispatch) route their decision through
     * [PassiveTransportDropEffects.classify]. The predicates re-read the VM's live state at
     * call time (the #685 re-read trap: the live `clientRef` identity, the
     * `activeTarget`/`connectingTarget`, the in-app-navigation intent). The per-arm recovery
     * IO stays in [handlePassiveClientDisconnect] (it threads handler-local
     * `target`/`reason`/`disconnectEvent`, including the [triggerCleanPassiveDropForTest]
     * synthetic event); only the DECISION moved to the connection core.
     */
    private val passiveTransportDropEffects: PassiveTransportDropEffects =
        PassiveTransportDropEffects(
            isSelfInflictedClose = { client ->
                // Issue #1568 (P0-5): a self-inflicted close (ExplicitDetach/ExplicitClose/
                // `detach_or_replace`) is not a passive loss and must never re-arm recovery —
                // ignoring our own ExplicitClose breaks the #1562 dial→close→re-arm→dial storm.
                val disconnectEvent = client.disconnectEvent.value
                disconnectEvent?.reason == TmuxDisconnectReason.ExplicitDetach ||
                    disconnectEvent?.reason == TmuxDisconnectReason.ExplicitClose ||
                    disconnectEvent?.intent == "detach_or_replace"
            },
            isCurrentClient = { client -> clientRef === client },
            hasTarget = { (activeTarget ?: connectingTarget) != null },
            screenStartedForCleared = { screenStartedForCleared },
            navigatingToDifferentSession = {
                val target = activeTarget ?: connectingTarget
                connectJob?.isActive == true ||
                    (
                        target != null &&
                            latestConnectIntent?.let { it.target.sessionName != target.sessionName } == true
                    )
            },
        )

    /**
     * EPIC #687 slice 1a: the [BackgroundArm.PauseReconnect] body — formerly inline at the
     * top of [onAppBackgrounded]. Unchanged behavior; only the decision moved to the
     * connection-core [backgroundEffects] dispatcher (#1047 Slice 1).
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
     * EPIC #687 slice 1a: the [BackgroundArm.DetachForBackground] body —
     * formerly the lower half of [onAppBackgrounded]. The `target`/`clientRef||
     * sessionRef` guards already passed in the [backgroundEffects] selection
     * ([selectBackgroundArm]) but are kept here so the side-effect body is self-contained
     * and the field reads happen at the same point in time as before.
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
        // Issue #935 R1: contained — the background detach issues `detach-client`
        // + lease release against a transport the close is racing to tear down.
        val detachJob = launchContainedTeardown {
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
        // path, which raised the reveal-machine hold (the "Attaching…" overlay) on any
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
            // the warm client. NO connect(), NO the reveal-machine hold "Attaching…"
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
        // NOT by the inline foreground classifier. Feeding the AUTHORITATIVE
        // controller the Foreground event is the SOLE trigger: beyond the App-level grace
        // (the only way this branch is reached — the within-grace fast paths returned
        // above) the App-grace teardown evicted the warm lease, so the controller's own
        // grace predicate is not-warm and it walks Backgrounded -> Reconnecting, firing
        // [onControllerForegrounded] which replays `pendingReattach` / resumes a
        // `pausedAutoReconnect` (selected by the connection-core [ForegroundReturnEffects]
        // — EPIC #687 Slice 0 / #1047 — re-reading the live payloads per the #685 trap:
        // the controller edge is the trigger, the connection-core dispatcher the gate).
        // The inline foreground event arm is no longer consulted (Slice 2a).
        // Issue #1123 (bounded-grace D21 update): the #1021 "post-grace HELD foreground
        // probe" is removed — the indefinite session-hold that could leave a live `-CC`
        // client past grace with no pendingReattach no longer exists. Beyond grace the
        // teardown always ran, so there is always a pendingReattach/pausedAutoReconnect
        // to replay through the normal foreground arm below.
        connectionManager.observeForeground()
        val armed = dispatchPostGraceForegroundArmIfPending()
        if (!armed) {
            // Issue #1181: the beyond-grace foreground-resume onto a STILL-LIVE connection
            // with NOTHING pending to replay. This is the port-forward-pinned "always-on"
            // case (#1159 Part 3): the pin SUPPRESSED the bounded-grace teardown, so
            // `dispatchTmuxBackground()` never ran, `detachForBackground()` never set
            // `pendingReattach`, and the `-CC` control client stayed live across the
            // background. On a notification-tap foreground return beyond the grace deadline
            // `resumedWithinGrace` is false (the within-grace reseed fast paths above
            // declined) and the arm dispatch did NOTHING — so no code path drove a repaint.
            // The Termux surface may have been released while backgrounded (a surface-layer
            // black over an INTACT emulator model), and the stale-render heal oracle cannot
            // rescue it — it compares the model grid vs tmux and a surface-only black leaves
            // model == tmux. Force ONE unconditional full-viewport reseed of the active pane
            // over the warm client — the single repaint that was missing on this branch.
            reseedActivePaneOnLivePinnedForeground()
        }
        // The controller's single grace predicate decided reattach-vs-reconnect:
        // within grace (warm lease) it is Reattaching and the active-pane reseed will
        // land it back to Live → Connected (the approved #685 Bug-A divergence — NO
        // probe churn); beyond grace it is Reconnecting (matches inline). Re-project
        // after the driver-fired effects ran.
        projectStatusFromController()
    }

    /**
     * Issue #1181: heal the black terminal seen on a notification-tap foreground-resume onto
     * a STILL-LIVE (port-forward-pinned, #1159 Part 3) connection with no `pendingReattach`.
     *
     * This is the SOLE beyond-grace foreground path in the codebase that otherwise drives
     * ZERO repaint (see [onAppForegrounded]): within-grace reseeds via [graceEffects], and a
     * beyond-grace NON-pinned resume ran the teardown so [dispatchPostGraceForegroundArmIfPending]
     * replays `pendingReattach`/`pausedAutoReconnect`. Only the pinned beyond-grace resume
     * arrives here with a live client and nothing to arm — and the surface may be black over
     * an intact model, which no other mechanism repaints.
     *
     * We force ONE unconditional full-viewport reseed over the WARM `-CC` client, REUSING the
     * exact #553/#721/#892 reseed chokepoint ([reseedActivePaneForReattach] →
     * [healActivePaneIfStaleRender] → `_fullRepaintRequests` full clear+repaint) that manual Redraw
     * and the within-grace reattach already use. `skipWhenFreshlySeeded = false` FORCES the
     * recapture even though the pane is still in [panesSeededThisAttach] from the original
     * attach (the live client never re-attached) — otherwise the model-intact "already seeded,
     * not blank" state would skip and the surface would stay black. The [RuntimeRefreshGuard]
     * drops a late seed for a switched-away/superseded runtime.
     *
     * D21/D28 contract: NO reconnect, NO new lease, NO `connectGeneration` bump, NO
     * the reveal-machine hold "Attaching…" overlay, and NO new polling/timer (#1164) — a single
     * reseed on this one no-op branch, at most one `capture-pane` per pinned foreground return.
     * A no-op when nothing is attached / the client is gone (the beyond-grace non-pinned resume
     * that DID tear down never reaches here — it always has an arm to dispatch above).
     */
    private fun reseedActivePaneOnLivePinnedForeground() {
        val client = clientRef ?: return
        if (client.disconnected.value) return
        val target = activeTarget ?: return
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-foreground-live-pinned-reseed generation=$connectGeneration " +
                "session=${target.sessionName} status=${_connectionStatus.value}",
        )
        ReconnectCauseTrail.record(
            stage = "foreground_live_pinned",
            outcome = "reseed_only",
            cause = "post_grace_live_no_pending_foreground",
            trigger = TmuxConnectTrigger.LifecycleReattach.logValue,
            "hostId" to target.hostId,
            "generation" to connectGeneration,
            "clientHash" to System.identityHashCode(client),
        )
        // Issue #935 R1: contained — the reseed runs `capture-pane` IO against the warm
        // client; a teardown race here must not crash.
        launchContainedTeardown {
            if (client.disconnected.value) return@launchContainedTeardown
            val guard = RuntimeRefreshGuard(
                generation = connectGeneration,
                target = target,
                client = client,
            )
            // UNCONDITIONAL full-viewport restore of the active pane over the warm client.
            // skipWhenFreshlySeeded=false forces the recapture even for a pane already seeded
            // this attach — the live client never re-attached, so the pane stays in
            // panesSeededThisAttach and a skip would leave the surface black (#1181).
            // Issue #1295 + #1495(Part 3): re-arm the single lifetime net in a `finally` so a
            // cancel/teardown throw in the reseed→arm window can't leave a still-current runtime
            // watchdog-less. isCurrentRuntime no-ops a superseded runtime; arm-dedup keeps it singular.
            try {
                reseedActivePaneForReattach(guard, skipWhenFreshlySeeded = false)
                reseedArmWindowFailureForTest?.let { throw it }
            } finally {
                if (isCurrentRuntime(guard)) armActivePaneStaleRenderWatchdog(guard)
            }
        }
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
     *
     * Returns whether an arm was dispatched. Issue #1181: the caller reseeds the active pane
     * over the still-live client when this returns `false` (nothing pending) — the
     * port-forward-pinned beyond-grace resume that otherwise drives zero repaint.
     */
    private fun dispatchPostGraceForegroundArmIfPending(): Boolean {
        if (pendingReattach == null && pausedAutoReconnect == null) return false
        onControllerForegrounded()
        return true
    }

    /**
     * EPIC #766 Slice 2a — the controller-EDGE-driven beyond-grace FOREGROUND effect, fired
     * by the [ConnectionEffectDriver] on the [ConnectionController]'s Backgrounded ->
     * Reconnecting edge (the controller edge is the TRIGGER). EPIC #687 Slice 0 (#1047): a
     * thin delegate to the connection-core [foregroundReturnEffects] — the inline
     * `reduceForeground()` selector (the second decision authority D28 ends) is DELETED.
     */
    private fun onControllerForegrounded() {
        foregroundReturnEffects.dispatch()
    }

    /**
     * EPIC #687 Slice 0 (#1047): the connection-core foreground-return decision authority,
     * the hard-cut replacement for the deleted inline `reduceForeground()`. The payload
     * predicates re-read the VM's live `pendingReattach` / `pausedAutoReconnect` each
     * dispatch (the #685 re-read trap); the arm bodies are the VM's existing replay/resume
     * IO; the no-arm case preserves the prior inline `else` "no-pending" skip log.
     */
    private val foregroundReturnEffects: ForegroundReturnEffects =
        ForegroundReturnEffects(
            hasPendingReattach = { pendingReattach != null },
            hasPausedAutoReconnect = { pausedAutoReconnect != null },
            replayPendingReattach = { replayPendingReattach() },
            resumePausedAutoReconnect = { pausedAutoReconnect?.let { resumePausedAutoReconnect(it) } },
            onNoPendingArm = {
                latestConnectIntent?.let { intent ->
                    Log.i(
                        ISSUE_235_LIFECYCLE_TAG,
                        "tmux-reattach-on-foreground-skip reason=no-pending " +
                            "generation=${intent.generation} trigger=${intent.trigger.logValue} " +
                            targetLogFields(intent.target),
                    )
                }
            },
        )

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
        val client = clientRef ?: return false
        val session = sessionRef ?: return false
        if (client.disconnected.value) return false
        if (!session.isConnected) return false
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
     * NEVER raises the reveal-machine hold, so the user sees no "Attaching…" overlay and
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
        // Issue #935 R1: contained — a within-grace reattach reseed runs
        // `capture-pane` IO against a warm client that may be racing teardown.
        launchContainedTeardown {
            if (client.disconnected.value) return@launchContainedTeardown
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
            // Issue #1295 + #1495(Part 3): re-arm the single lifetime net (the sole post-reveal
            // net for an idle Claude pane) in a `finally` so a cancel/teardown throw in the
            // reseed→arm window can't leave a still-current runtime watchdog-less. isCurrentRuntime
            // no-ops a superseded runtime; arm-dedup keeps it singular.
            try {
                reseedActivePaneForReattach(guard)
                reseedArmWindowFailureForTest?.let { throw it }
            } finally {
                if (isCurrentRuntime(guard)) armActivePaneStaleRenderWatchdog(guard)
            }
        }
    }

    /**
     * Issue #892: the on-demand "Redraw" escape hatch. The user taps the kebab's
     * **Redraw** item to manually recover from a black/partial-black terminal (the
     * dogfood screenshot: ~100% black with only a lone stray cursor cell). This forces
     * a FULL-viewport reseed of the active pane over the WARM session — it REUSES the
     * exact #553/#879 full-reseed machinery ([reseedActivePaneForReattach] →
     * [healActivePaneIfStaleRender] → [TerminalSurfaceState.appendRemoteOutput], which fires the
     * #721 `_fullRepaintRequests` full clear+repaint) and the other-pane blank net.
     *
     * D21/D28 contract (a warm-session reseed ONLY — NO reconnect, detach, or new lease):
     *  - It NEVER calls `connect()`, bumps [connectGeneration], evicts/acquires a lease,
     *    or touches the reconnect/grace state machine. It runs entirely over the
     *    already-live `clientRef` (`-CC` control channel) against the current runtime
     *    guard, exactly like the within-grace foreground reseed does.
     *  - It does NOT raise the reveal-machine hold (no "Attaching…" overlay) and does NOT
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
            // Issue #989: surface the no-op so Redraw never silently does nothing.
            _redrawFeedback.tryEmit(REDRAW_UNAVAILABLE_MESSAGE)
            return
        }
        if (client.disconnected.value) {
            Log.i(ISSUE_145_RECONNECT_TAG, "tmux-redraw-skip client-disconnected")
            _redrawFeedback.tryEmit(REDRAW_UNAVAILABLE_MESSAGE)
            return
        }
        val target = activeTarget ?: run {
            Log.i(ISSUE_145_RECONNECT_TAG, "tmux-redraw-skip no-target")
            _redrawFeedback.tryEmit(REDRAW_UNAVAILABLE_MESSAGE)
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
        // Issue #935 R1: contained — the manual redraw reseeds via `capture-pane`
        // IO against the warm client; a teardown race here must not crash.
        launchContainedTeardown {
            if (client.disconnected.value) return@launchContainedTeardown
            val guard = RuntimeRefreshGuard(
                generation = connectGeneration,
                target = target,
                client = client,
            )
            // UNCONDITIONAL full-viewport restore of the active pane + the blank net over
            // any OTHER visible pane. skipWhenFreshlySeeded=false forces the recapture even
            // for a pane already seeded this attach — the whole point of a manual Redraw.
            reseedActivePaneForReattach(guard, skipWhenFreshlySeeded = false)
            // Issue #1203: the model reseed above recovers a MODEL-black pane (the grid
            // lost tmux's frame). But the maintainer's "Redraw doesn't work" report is a
            // SURFACE-only-black: the model grid already matches tmux, so the reseed
            // restores NOTHING and the surface stays black. Redraw is architecturally
            // incapable of recovering that with a model reseed alone. Fire an
            // UNCONDITIONAL surface force-repaint too (re-bind the View's emulator +
            // full-clip invalidate) so the manual escape hatch recovers BOTH the
            // model-black AND the surface-only-black classes — the whole point of Redraw
            // being the user's last-resort recovery. Cheap + idempotent: on a genuinely
            // healthy pane it is one extra full-clip invalidate, no round-trip.
            if (isCurrentRuntime(guard)) {
                activeVisiblePane()?.terminalState?.requestSurfaceRepaint()
            }
        }
    }

    /**
     * EPIC #687 P3 (J2/#553): the id-tagged FULL-VIEWPORT reseed for a within-grace
     * reattach. Unlike [reseedBlankVisiblePanes] (which `continue`s on any pane that is
     * not `transcriptText.isBlank()`), this UNCONDITIONALLY re-captures the active
     * visible pane from a fresh `capture-pane` and feeds the full grid back into the
     * emulator — so a PARTIALLY-blank pane (one live line present, the static content
     * above it gone) is restored to the FULL prior viewport, not left "only a timer,
     * rest blank" (#553). [healActivePaneIfStaleRender] → [TerminalSurfaceState.appendRemoteOutput]
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
            // Issue #1151: reseed PURELY from tmux's authoritative server-side grid via
            // `capture-pane` — NO keystroke is injected into the pane. #989 used to send a
            // `send-keys C-l` here to nudge an idle alt-screen agent to repaint, but that
            // byte (0x0C) is APPLICATION INPUT, not a rendering primitive: it reached the
            // agent CLI exactly as if the user pressed Ctrl+L, so Claude/GLM surfaced
            // "Ctrl+L is disabled while a task is in progress" on every switch /
            // foreground-return. In `-CC` control mode the tmux server holds the pane's
            // full grid independent of whether the app re-emits, so `capture-pane` returns
            // the current content directly; the retained non-destructive swap
            // ([captureWouldClearVisibleContent] in [captureAndApplyPaneSnapshot]) keeps the
            // last good frame if a capture is momentarily near-blank, so the reseed either
            // lands fresh authoritative content or keeps the prior frame — never black,
            // never a stray keystroke. This single chokepoint feeds manual Redraw, the
            // within-grace reattach, the no-op-resize heal, and the reflow completion.
            healActivePaneIfStaleRender(
                client,
                activePane,
                refreshGuard,
                force = true,
                recordMilestone = false,
            )
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
            // inline foreground consultation is removed. By construction this
            // branch has NO `pendingReattach`/`pausedAutoReconnect` (the `target`-deriving
            // expression above already proved both null), so the connection-core
            // [ForegroundReturnEffects] selects the `None` arm and the driver-fired effect
            // is a no-op — exactly the prior inline `else` behavior, just without
            // re-consulting the classifier here.
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
        val suppressedDropDiagnostic = lastSuppressedDropDiagnostic
        val originationCause =
            suppressedDropDiagnostic?.cause ?: "within_grace_foreground_socket_drop"
        val originationDiagnosticFields = suppressedDropDiagnosticFields(suppressedDropDiagnostic)
        lastSuppressedDropDiagnostic = null
        Log.i(
            ISSUE_235_LIFECYCLE_TAG,
            "tmux-foreground-heal-within-grace generation=$connectGeneration " +
                targetLogFields(target),
        )
        ReconnectCauseTrail.record(
            stage = "foreground_reattach",
            outcome = "silent_heal_within_grace",
            cause = originationCause,
            trigger = TmuxConnectTrigger.LifecycleReattach.logValue,
            "hostId" to target.hostId,
            "generation" to connectGeneration,
            *originationDiagnosticFields,
        )
        DiagnosticEvents.record(
            "connection",
            "foreground_reattach",
            "source" to "app_lifecycle",
            "outcome" to "silent_heal_within_grace",
            "cause" to originationCause,
            "trigger" to TmuxConnectTrigger.LifecycleReattach.logValue,
            "generation" to connectGeneration,
            "hostId" to target.hostId,
            "host" to target.host,
            "port" to target.port,
            "user" to target.user,
            "session" to target.sessionName,
            *originationDiagnosticFields,
        )
        // Keep the displayed status CALM while we heal: the within-grace foreground is a
        // ride-through, not a reconnect. Promote the controller back toward Live so the
        // header indicator never shows Reconnecting/Disconnected during the heal.
        connectionManager.observeForegroundReattachLive()
        // Issue #1098 (item 4 / #635): the within-grace silent heal MUST re-open the
        // dropped `-CC` transport, which walks the controller `Live -> Reattaching ->
        // Live`. Without this the [RevealStateMachine] would project that Reattaching as
        // [RevealState.Seeding] → the screen paints the full-surface "Attaching…" loading
        // overlay over the live frame (the spurious overlay #635/item-4 reproduces). Hold
        // the reveal at its current (live) frame for the bounded duration of the heal so
        // the ride-through stays INVISIBLE; cleared in the job's completion handler below.
        withinGraceSilentHealInFlight = true
        revealController.setSilentHealInFlight(true)
        projectStatusFromController()
        // Issue #935 R1: contained — the within-grace heal re-opens a fresh `-CC`
        // over a fresh lease; a teardown-race throw must not crash the process.
        val healJob = launchContainedTeardown {
            // Issue #1568 (P0-2): channel-dead-but-transport-warm MIDDLE rung — on a vouched-alive
            // transport recover the `-CC` channel over the LIVE transport first (no lease
            // eviction), else fall through to the lease-evicting fresh dial (a real death fails
            // the vouch, so it still escalates). `clientRef` may be null; the fresh dial is null-safe.
            val staleClient = clientRef
            val recovered =
                (
                    transportVouchedAlive() && staleClient != null &&
                        silentlyReattachAfterPassiveDisconnect(
                            staleClient = staleClient,
                            target = target,
                            timeoutMs = passiveDisconnectGraceMs.coerceAtLeast(1L),
                        )
                ) ||
                    silentlyReconnectTransportAfterPassiveDisconnect(
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
                    diagnosticFields = arrayOf(
                        "originationCause" to originationCause,
                        *originationDiagnosticFields,
                    ),
                )
            }
        }
        // Issue #1098 (item 4): release the silent-heal reveal hold once the heal
        // completes. On success the transport re-promoted the controller to Live, so the
        // reveal is already a live frame; on failure the calm auto-reconnect ladder takes
        // over and the next Reconnecting projection may show the normal loading surface.
        // Cleared in the completion handler (success, failure, OR a teardown-race cancel)
        // so the hold can never get stuck on, which would freeze the "Attaching…" overlay
        // suppression across an unrelated later attach.
        healJob.invokeOnCompletion {
            withinGraceSilentHealInFlight = false
            revealController.setSilentHealInFlight(false)
            // Re-project so a genuine heal FAILURE (the calm auto-reconnect ladder is now
            // running) surfaces its normal Reconnecting band the instant the hold lifts,
            // and a SUCCESS settles on the live `Connected` it already reached.
            projectStatusFromController()
        }
    }

    /**
     * EPIC #687 slice 1a: the foreground replay body — formerly the second half of
     * [onAppForegrounded]. Unchanged behavior; the entry decision now lives in the
     * connection-core [ForegroundReturnEffects] (EPIC #687 Slice 0 / #1047), which fires
     * this as its `ReplayPendingReattach` arm.
     */
    private fun replayPendingReattach() {
        val detachJob = backgroundDetachJob
        // Issue #935 R1: contained — replay joins the in-flight detach teardown
        // and then re-connects; a throw on that lifecycle path must not crash.
        launchContainedTeardown {
            // If the user backgrounds and immediately foregrounds the
            // app, the lifecycle detach may still be inside
            // closeCurrentConnectionAndJoin(). Waiting here prevents a
            // reattach from being consumed by connect()'s still-connected
            // early return before teardown clears activeTarget/clientRef.
            detachJob?.join()
            val pending = pendingReattach ?: return@launchContainedTeardown
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
                return@launchContainedTeardown
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
        // Issue #935 R1: contained — the manual detach runs the full
        // close-cascade (`detach-client` + lease release + cache eviction) IO
        // against a transport racing to tear down; a throw must not crash.
        launchContainedTeardown {
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

    /**
     * Issue #1355 test seam — the STRUCTURAL fix for the recurring
     * `TmuxSessionViewModelTest` coroutine-leak-across-test-boundary flake
     * (`IllegalStateException` at `TestMainDispatcher.kt:72`,
     * "Dispatchers.Main is used concurrently with setting it").
     *
     * ## Why the prior four point-fixes kept whack-a-moling
     *
     * The suite's `@After` drains three INJECTED collaborator scopes to
     * quiescence before the rule's `resetMain()` — `factoryScope` (#708),
     * `defaultTeardownScope` (#1085), and `agentTailScope` (#1168) — but it
     * never drained the VM's OWN coroutine roots. `viewModelScope` is cancelled
     * only by the framework's `ViewModel.clear()`; the test seam [clearForTest]
     * calls [onCleared] DIRECTLY, so `viewModelScope` and its child
     * [bridgeScope] are NOT cancelled by teardown. Any
     * `viewModelScope`/`bridgeScope` launch that hops to a REAL background
     * dispatcher (e.g. the IO-dispatcher `withContext{ dao.getById(...) }`
     * create-session / profiles fetches, or a `NonCancellable`
     * teardown/detach/lease-release launch routed through
     * [launchContainedTeardown]) therefore survives `@After` and, on
     * completion, re-dispatches its continuation onto `Dispatchers.Main` —
     * touching the test Main dispatcher — during the NEXT test's `setMain`.
     * That inter-test race is the flake; each prior fix chased ONE leaking
     * test's dispatcher instead of the shared root.
     *
     * ## What these two seams do
     *
     * The drain runs test-side (the test owns the shared
     * `TestCoroutineScheduler`). [cancelOwnScopesForTest] cancels every
     * `viewModelScope` child (which transitively cancels [bridgeScope]'s
     * `SupervisorJob`, itself a `viewModelScope` child, and every job under it);
     * [activeOwnScopeChildCountForTest] reports how many are not yet complete.
     *
     * The test's `@After` alternates `cancel → scheduler.runCurrent() →
     * Thread.sleep(1)` to a bounded deadline: `runCurrent()` (NEVER
     * `advanceUntilIdle` — the #1110 lesson: advancing the clock trips the #793
     * re-seed watchdog) drives the cancellation of the virtual-clock children
     * AND the Main re-dispatch of any child that hopped to a REAL background
     * dispatcher, while the `Thread.sleep(1)` yields wall-clock time for the
     * real IO thread to finish before the next drain. It HARD-FAILS if the count
     * never reaches zero — so a FUTURE leak (a new un-drained VM launch) is a
     * DETERMINISTIC red instead of an intermittent inter-class Main-set race.
     *
     * `viewModelScope`'s children are on the test Main dispatcher, so a plain
     * a plain blocking `join()` would deadlock (it never pumps the test
     * scheduler); the `runCurrent` pump is why the drain must be test-side.
     *
     * Test-only: production teardown runs through [onCleared] + the framework's
     * `viewModelScope` cancellation. Never called in production.
     */
    @androidx.annotation.VisibleForTesting
    internal fun cancelOwnScopesForTest() {
        viewModelScope.coroutineContext[Job]?.children?.forEach { it.cancel() }
    }

    /**
     * Issue #1355 test seam — count of this VM's `viewModelScope`/[bridgeScope]
     * children that are NOT yet complete (a cancelled child still running its
     * IO-dispatcher `withContext` / `NonCancellable` block reports
     * `!isCompleted`, so it is counted until the real hop finishes and its Main
     * re-dispatch has run). The `@After` drain loops until this is zero.
     */
    @androidx.annotation.VisibleForTesting
    internal fun activeOwnScopeChildCountForTest(): Int =
        viewModelScope.coroutineContext[Job]?.children?.count { !it.isCompleted } ?: 0

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
                // Issue #1206: carry the prewarm seed-recovery jobs into the
                // cache entry so a promoted-but-still-parked recovery job is
                // cancelled on cache eviction / deactivate rather than leaking
                // until whole-VM `bridgeScope` teardown.
                paneSeedRecoveryJobs = panes.paneSeedRecoveryJobs,
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
        // Issue #1316: the prewarm enumeration rides the same dedicated exec lane
        // as the attach reconcile, so a busy sibling session's `-CC` burst cannot
        // head-of-line-block a prewarm behind the shared control channel.
        val response = client.listPanesViaExec(
            buildListPanesCommand(target),
            timeoutMs = RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS,
        )
        if (response.isError) {
            throw TmuxAttachPanesReadyException(
                "tmux list-panes failed during prewarm: " +
                    response.output.joinToString(separator = " ").ifBlank { "unknown error" },
            )
        }
        val paneInputQueues = LinkedHashMap<String, TmuxPaneInputQueue>()
        val paneInputJobs = LinkedHashMap<String, Job>()
        val paneProducerJobs = LinkedHashMap<String, Job>()
        // Issue #1206: background seed-recovery jobs (bounded capture retry +
        // one deferred reseed on the first live %output) for panes whose FIRST
        // capture came back empty/error/timeout on a busy shared -CC channel.
        val paneSeedRecoveryJobs = LinkedHashMap<String, Job>()
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
                            paneSeedRecoveryJobs = paneSeedRecoveryJobs,
                        )
                    },
                )
                paneProducerJobs[pane.paneId] = producerJob
                // Issue #448 (epic #432 slice C): also tap the prewarmed
                // pane's shared output flow for new-port detection.
                startPortDetectionForPane(paneId = pane.paneId, client = client)
                seedPrewarmedPane(client, row, paneSeedRecoveryJobs)
            }
            return PrewarmedPaneRuntime(
                panes = paneRows.values.toList(),
                paneRows = paneRows,
                paneProducerJobs = paneProducerJobs,
                paneInputQueues = paneInputQueues,
                paneInputJobs = paneInputJobs,
                paneSeedRecoveryJobs = paneSeedRecoveryJobs,
            )
        } catch (t: Throwable) {
            PrewarmedPaneRuntime(
                panes = paneRows.values.toList(),
                paneRows = paneRows,
                paneProducerJobs = paneProducerJobs,
                paneInputQueues = paneInputQueues,
                paneInputJobs = paneInputJobs,
                paneSeedRecoveryJobs = paneSeedRecoveryJobs,
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
        paneSeedRecoveryJobs: MutableMap<String, Job>,
    ) {
        val existing = paneRows[paneId] ?: return
        if (existing.surfaceError) return
        paneProducerJobs.remove(paneId)?.cancel()
        paneInputJobs.remove(paneId)?.cancel()
        paneInputQueues.remove(paneId)?.close()
        // Issue #1206: the pane's feed failed — stop any background seed-recovery
        // retry/deferred-reseed too so it doesn't reseed a dead surface.
        paneSeedRecoveryJobs.remove(paneId)?.cancel()
        runCatching { existing.terminalState.detachExternalProducer() }
        paneRows[paneId] = existing.copy(surfaceError = true)
    }

    private suspend fun seedPrewarmedPane(
        client: TmuxClient,
        pane: TmuxPaneState,
        paneSeedRecoveryJobs: MutableMap<String, Job> = this.paneSeedRecoveryJobs,
    ) {
        // Issue #468: the live %output producer is gated behind the seed.
        // appendRemoteOutput opens the gate when a snapshot lands; if the
        // capture fails (or anything throws) we must still open the gate so
        // buffered live output is flushed in order rather than swallowed.
        val seeded = captureAndApplyPrewarmSeed(client, pane)
        if (!seeded) {
            // Open the gate immediately (do NOT hold live %output behind a
            // multi-second retry) and recover in the background.
            pane.terminalState.openSeedGateWithoutSeed()
            // Issue #1206: the FIRST capture came back empty/error/timeout on a
            // busy shared -CC channel (Claude floods at startup and can wedge the
            // capture acquire). Without recovery the model grid stays empty and
            // only future incremental %output paints → fragments-over-black. Retry
            // a bounded number of times with backoff, and failing that re-arm ONE
            // deferred reseed on the first live %output so the full grid is
            // recovered the moment the pane produces anything.
            schedulePrewarmSeedRecovery(client, pane, paneSeedRecoveryJobs)
        }
    }

    /**
     * Issue #640/#926: one combined capture+cursor seed attempt for a prewarmed
     * pane, run OFF Main on [seedIoDispatcher] with the short seed ceiling.
     * Returns true and applies the snapshot (firing the full-repaint) when the
     * capture returned content; false on empty/error/timeout/throw so the caller
     * can open the gate and schedule recovery (#1206).
     */
    private suspend fun captureAndApplyPrewarmSeed(
        client: TmuxClient,
        pane: TmuxPaneState,
    ): Boolean {
        // Issue #1206 (connected AC4 proof, #780 synthetic-injection): a busy
        // shared `-CC` channel can wedge/empty the FIRST `capture-pane` on a
        // fresh prewarmed pane even though the pane HAS content — the exact
        // non-happy state the happy real-agent workbench structurally cannot
        // enter. This seam lets a connected journey force the first seed attempt
        // to be TREATED as empty (deterministically, BEFORE the wire round-trip),
        // driving the retry/deferred-reseed recovery against a real pane.
        // [onSeedAttempt] is a diagnostic counter (how many times the prewarm
        // seed path ran). Production never arms the seam (default 0), so this is
        // a pure test hook.
        PrewarmSeedFaultTestOverride.onSeedAttempt()
        if (PrewarmSeedFaultTestOverride.consumeForcedEmpty(pane.paneId)) return false
        val combined = runCatching {
            withContext(seedIoDispatcher) {
                client.captureWithCursor(
                    pane.paneId,
                    scrollbackLines = SEED_SCROLLBACK_LINES,
                    timeoutMs = seedCaptureTimeoutMs,
                )
            }
        }.getOrNull() ?: return false
        val capture = combined.capture
        if (capture.isError || capture.output.isEmpty()) return false
        val cursor = parseTmuxPaneCursor(combined.cursorReply)
        pane.terminalState.appendRemoteOutput(
            capture.output.toTerminalViewportBytes(cursor),
        )
        return true
    }

    /**
     * Issue #1206: background recovery for a prewarmed pane whose first seed
     * capture came back empty/error/timeout. Runs on [bridgeScope] (never blocks
     * the prewarm loop; cancelled with the VM), retries the capture a bounded
     * number of times with backoff, and — if the capture stays wedged/empty for
     * the whole window — re-arms ONE deferred reseed that fires the moment the
     * first live %output lands (an idle pane emits nothing, so no wasted
     * capture; when Claude finally paints a cell, one fresh capture seeds the
     * full grid over the fragment-only screen).
     */
    private fun schedulePrewarmSeedRecovery(
        client: TmuxClient,
        pane: TmuxPaneState,
        paneSeedRecoveryJobs: MutableMap<String, Job>,
    ) {
        val paneId = pane.paneId
        paneSeedRecoveryJobs.remove(paneId)?.cancel()
        val job = bridgeScope.launch {
            // Bounded retry with backoff (≈PREWARM_SEED_RETRY_ATTEMPTS attempts
            // over ≈PREWARM_SEED_RETRY_BACKOFF_MS spacing).
            repeat(PREWARM_SEED_RETRY_ATTEMPTS) {
                delay(prewarmSeedRetryBackoffMs)
                if (captureAndApplyPrewarmSeed(client, pane)) return@launch
            }
            // Retry window exhausted while the capture stayed empty/wedged.
            // Re-arm ONE deferred reseed: wait for the first live %output, then
            // do a single fresh capture. The wait parks harmlessly for a truly
            // idle pane (nothing to reseed) and is torn down with bridgeScope.
            runCatching { client.outputFor(paneId).first() }
                .onSuccess { captureAndApplyPrewarmSeed(client, pane) }
        }
        // The map is a per-runtime cancellation registry only (torn down with the
        // runtime); a completed entry left behind is harmless (`cancel()` on a
        // finished job is a no-op). Deliberately NOT self-removed on completion so
        // a completion handler can't mutate the map while [closePartialPrewarm]
        // iterates it.
        paneSeedRecoveryJobs[paneId] = job
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
        //
        // Issue #981: the controller flips Live → Reconnecting on a validated handoff,
        // which drives the RevealStateMachine → the "Attaching…" overlay. That is a
        // USER-VISIBLE disconnect glitch even when the inline reducer rides the flip
        // through. So the controller signal MUST honor the SAME liveness gate: when the
        // old transport is proven alive we report NO handoff to the controller, keeping
        // it Live (no overlay), in lockstep with [NetworkChangeEffects].
        // Issue #997: the bare-loss / restore signal is ORTHOGONAL to the #548
        // validated-identity handoff — it must NOT be reported to the controller
        // as a `validatedHandoff` (that flag is the identity-change overlay path).
        // The loss/restore arms drive the UI via the loss-hold + the restore's
        // own `scheduleAutoReconnect` → `setConnectionState`.
        connectionManager.observeNetworkChanged(
            validatedHandoff = shouldReportValidatedHandoffToController(
                change = change,
                transportKeepAliveProvenAlive = { isTransportKeepAliveProvenAliveRecently() },
            ),
        )
        NetworkChangeEffects(
            appActive = { appActive },
            hasTarget = { target != null },
            hasClientOrSession = { clientRef != null || sessionRef != null },
            autoReconnectActive = { autoReconnectJob?.isActive == true },
            inlineConnected = { inlineConnectionStatus is ConnectionStatus.Connected },
            lifecycleCoalesces = {
                val lifecycleCoalesce = lifecycleReattachNetworkCoalesce
                lifecycleCoalesce != null &&
                    target != null &&
                    lifecycleCoalesce.generation == connectGeneration &&
                    sameSessionIdentity(lifecycleCoalesce.target, target)
            },
            transportKeepAliveProvenAlive = { isTransportKeepAliveProvenAliveRecently() },
            suppressNetworkNotValidated = {
                if (target != null) suppressNetworkNotValidated(it, target)
            },
            suppressNetworkCoalesced = {
                if (target != null) suppressNetworkCoalesced(it, target)
            },
            suppressNetworkTransportProvenAlive = {
                if (target != null) suppressNetworkTransportProvenAlive(it, target)
            },
            suppressNetworkLostTransportProvenAlive = {
                if (target != null) suppressNetworkLostTransportProvenAlive(it, target)
            },
            scheduleNetworkReconnect = {
                if (target != null) scheduleNetworkReconnect(it, target)
            },
            holdNetworkLost = {
                if (target != null) holdNetworkLost(it, target)
            },
            scheduleNetworkReconnectOnRestore = {
                if (target != null) scheduleNetworkReconnectOnRestore(it, target)
            },
        ).dispatch(change)
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
     * EPIC #687 slice 1a: the [NetworkChangeArm.SuppressNetworkNotValidated]
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
     * EPIC #687 slice 1a: the [NetworkChangeArm.SuppressNetworkCoalesced]
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
     * Issue #1078 / #1193: passive keepalive freshness is not enough across a real
     * validated handoff. Confirm the old control channel with one bounded answered
     * round-trip before riding through; otherwise redial promptly instead of leaving
     * the terminal Live-but-dead until the keepalive budget expires.
     */
    private fun suppressNetworkTransportProvenAlive(
        change: TerminalNetworkChange,
        target: ConnectionTarget,
    ) {
        val probeGuard = currentNetworkTransitionProbeGuard(target)
        viewModelScope.launch {
            // Issue #1193: this handoff probe is a NETWORK-TRANSITION probe, so it
            // requires an ANSWERED round-trip (like the restore arm). Recent reader
            // bytes crossed the OLD socket and are not evidence the new path is
            // alive — the #927 reader-activity fallback would let a dead post-handoff
            // cellular socket pass, exactly the residual §4 of the #928 spike.
            val alive = withTimeoutOrNull(RESTORE_LIVENESS_PROBE_BUDGET_MS) {
                runLivenessProbePing(requireAnsweredRoundTrip = true)
            } ?: false
            if (probeGuard != null && !isCurrentNetworkTransitionProbe(probeGuard)) {
                recordStaleNetworkTransitionProbe(change, target, "network_handoff_probe", probeGuard)
                return@launch
            }
            if (alive) {
                rideThroughNetworkHandoffProvenAlive(change, target)
            } else {
                // Issue #1078: passively proven alive but the bounded active probe
                // did NOT answer — the old socket is genuinely dead after the
                // handoff. Redial now rather than freezing Live for ~90s.
                scheduleNetworkReconnect(change, target)
            }
        }
    }

    /**
     * Issue #1078: the handoff RIDE-THROUGH arm — the bounded active probe
     * ([suppressNetworkTransportProvenAlive]) confirmed the old socket is still
     * alive after the validated handoff, so we do NOT redial (preserving the
     * #981/#974/#1058 spurious-drop win). Emits the same `network_reconnect_skip`
     * device trail as before, tagged `probeConfirmed=true` so a field log can tell
     * the #1078 probe-confirmed ride-through apart from a purely passive suppress.
     */
    private fun rideThroughNetworkHandoffProvenAlive(
        change: TerminalNetworkChange,
        target: ConnectionTarget,
    ) {
        val reason = change.reason
        val previousValidated = change.previousValidated
        run {
            Log.i(
                ISSUE_548_NETWORK_TAG,
                "tmux-network-proactive-reconnect-skip reason=$reason " +
                    "cause=transport-proven-alive " +
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
                "cause" to "transport_proven_alive",
                "classification" to "network_handoff_transport_alive",
                "reconnect" to false,
                // Issue #1078: this ride-through is now confirmed by a bounded
                // ACTIVE probe, not the passive keepalive timestamp alone.
                "probeConfirmed" to true,
                "realValidatedIdentityChange" to true,
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
                cause = "transport_proven_alive",
                trigger = TmuxConnectTrigger.NetworkReconnect.logValue,
                "sequence" to change.sequence,
                "hostId" to target.hostId,
                "generation" to connectGeneration,
                "classification" to "network_handoff_transport_alive",
                "deferredFromBackground" to change.deferredFromBackground,
            )
        }
    }

    /**
     * EPIC #687 slice 1a: the [NetworkChangeArm.ScheduleNetworkReconnect]
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

    /** Issue #1522: debounces + backs off the bare-loss band. */
    private val networkLossBandDebouncer = NetworkLossBandDebouncer(
        scope = viewModelScope,
        baseDebounceMs = NETWORK_LOSS_BAND_DEBOUNCE_MS,
        backoffLadderMs = { autoReconnectDelaysMs },
        quietResetMs = NETWORK_LOSS_BAND_BACKOFF_QUIET_RESET_MS,
        transportKeepAliveProvenAlive = { isTransportKeepAliveProvenAliveRecently() },
    )

    /**
     * Issue #928 (T6): cross-episode amortizer for the KEEPALIVE-DEATH redial —
     * the per-host idle-flap path outside the #1522 debouncer. Consulted by
     * [silentReattachWithinPassiveGrace] only when the dropped transport's close
     * cause is [SshSessionCloseCause.KeepaliveDead].
     */
    private val keepaliveDeathRedialAmortizer = KeepaliveDeathRedialAmortizer(
        scope = viewModelScope,
        backoffLadderMs = { autoReconnectDelaysMs },
        quietResetMs = { keepaliveDeathQuietResetMs },
    )

    // Issue #1537 (option b): the parked-runtime health ledger + edge subscriber.
    // A parked death edge marks the ledger Dead and calls [onParkedRuntimeDeath] to
    // evict the corpse before switch-back (the `stale_lease_attach_eof` flap becomes
    // a proactive calm heal); the residual silent-TCP race falls to the attach-EOF
    // fallback (single ladder).
    private val runtimeHealthLedger = RuntimeHealthLedger()

    private val parkedRuntimeHealthEffects = ParkedRuntimeHealthEffects(
        scope = viewModelScope,
        ledger = runtimeHealthLedger,
        leaseStateEvents = sshLeaseManager.stateEvents,
        onDeath = ::onParkedRuntimeDeath,
    )

    /**
     * Issue #1533: cross-episode amortization for the SAME-IDENTITY network-restore
     * redial (the "V2" busy-session flap) — the #928/#1522 debounce shape, distinct
     * instance ([keepaliveDeathRedialAmortizer] sibling) with honest field events.
     */
    private val networkRestoreRedialAmortizer = KeepaliveDeathRedialAmortizer(
        scope = viewModelScope,
        backoffLadderMs = { autoReconnectDelaysMs },
        quietResetMs = { keepaliveDeathQuietResetMs },
        episodeEventName = "network_restore_redial_amortized",
        episodeCause = "network_restored",
        episodeSource = "network_observer",
        episodeTrigger = TmuxConnectTrigger.NetworkReconnect.logValue,
        episodeStage = "network_restore_redial",
    )

    /**
     * Issue #997 / #1522: [NetworkChangeArm.HoldNetworkLost] — a bare loss the
     * keepalive could NOT vouch for. Hold the lease and DEBOUNCE the band (grace +
     * escalating backoff): cellular clears `NET_CAPABILITY_VALIDATED` for sub-second
     * windows constantly on a live link, so an immediate paint + restore is the
     * maintainer's flap. The band paints only if the loss OUTLASTS the grace and the
     * keepalive still cannot vouch; a blip that clears (restore → cancel) never flaps.
     */
    private fun holdNetworkLost(
        change: TerminalNetworkChange,
        target: ConnectionTarget,
    ) {
        recordNetworkLossHold(change, target, connectGeneration, debounced = true)
        networkLossBandDebouncer.schedule(
            onSuppressedByKeepAlive = {
                recordNetworkLossBandSuppressed(
                    change, target, connectGeneration, cause = "transport_proven_alive_debounced",
                )
            },
            onPaintBand = { graceMs ->
                recordNetworkLossBandPainted(change, target, connectGeneration, graceMs)
                setConnectionState(
                    ConnectionState.Reconnecting(
                        host = target.host,
                        port = target.port,
                        user = target.user,
                        attempt = 0,
                        maxAttempts = 0,
                        retryDelayMs = graceMs,
                        reason = "Network lost; waiting for it to come back.",
                    ),
                )
            },
        )
    }

    /**
     * Issue #1522 (H1): [NetworkChangeArm.SuppressNetworkLostTransportProvenAlive] —
     * a bare loss the keepalive already vouches for (not a real death). Hold the lease
     * (still record `network_loss_hold`), suppress the band (stays Live), drop pending.
     */
    private fun suppressNetworkLostTransportProvenAlive(
        change: TerminalNetworkChange,
        target: ConnectionTarget,
    ) {
        networkLossBandDebouncer.cancel()
        recordNetworkLossHold(change, target, connectGeneration, debounced = false)
        recordNetworkLossBandSuppressed(
            change, target, connectGeneration, cause = "transport_proven_alive",
        )
    }

    /**
     * Issue #997 / #1042 / #1193 / #1533: validation returned after a loss.
     *
     * Issue #1533 (the "V2" busy-session flap): a SAME-IDENTITY restore whose control
     * channel is STILL delivering bytes (a `%output` burst that parks the round-trip
     * probe past its 5s budget) is a provably-alive transport — ride through on that
     * #927 reader-activity vouch instead of tearing it down. Strict round-trip probing
     * stays scoped to an identity CHANGE (#1193: on a handoff the surviving bytes
     * crossed the OLD, now-dead socket). Otherwise probe; a dead socket redials via the
     * #997 fresh lease, and a same-identity redial is AMORTIZED so it doesn't hammer.
     */
    private fun scheduleNetworkReconnectOnRestore(
        change: TerminalNetworkChange,
        target: ConnectionTarget,
    ) {
        // Issue #1522: validation returned — drop any pending debounced band.
        networkLossBandDebouncer.cancel()
        val sameIdentity = change.isSameIdentityNetworkRestore()
        // Issue #1533: same-identity + live-but-busy (recent reader activity) rides
        // through WITHOUT the strict probe a `%output` burst would park past its budget.
        if (sameIdentity && isRestoreTransportProvenAliveByReaderActivity()) {
            rideThroughNetworkRestore(change, target, cause = "same_identity_reader_active")
            return
        }
        val probeGuard = currentNetworkTransitionProbeGuard(target)
        viewModelScope.launch {
            val alive = withTimeoutOrNull(RESTORE_LIVENESS_PROBE_BUDGET_MS) {
                runLivenessProbePing(requireAnsweredRoundTrip = true)
            } ?: false
            if (probeGuard != null && !isCurrentNetworkTransitionProbe(probeGuard)) {
                recordStaleNetworkTransitionProbe(change, target, "network_restore_probe", probeGuard)
                return@launch
            }
            if (alive) {
                rideThroughNetworkRestore(change, target, cause = "probe_answered")
            } else {
                // Issue #1533: amortize a real same-identity drop so a flapping host
                // widens its cadence; an identity handoff still redials promptly.
                if (sameIdentity) {
                    networkRestoreRedialAmortizer.awaitRedialGrace(target, connectGeneration)
                    // A newer reconnect generation may have superseded during the grace.
                    if (probeGuard != null && !isCurrentNetworkTransitionProbe(probeGuard)) {
                        recordStaleNetworkTransitionProbe(change, target, "network_restore_probe", probeGuard)
                        return@launch
                    }
                }
                forceFreshLeaseRestoreReconnect(change, target)
            }
        }
    }

    /**
     * Issue #1533: is the current control channel proven alive by RECENT reader
     * activity (#927)? A `%output` burst keeps advancing the reader's last-activity
     * clock even while a `refresh-client` reply is parked — proof the SAME socket is
     * alive. Valid ONLY on a same-identity restore (caller gates on that).
     */
    private fun isRestoreTransportProvenAliveByReaderActivity(): Boolean {
        val client = clientRef ?: return false
        return runCatching {
            client.millisSinceLastReaderActivity() <= client.readerActivityLivenessWindowMs
        }.getOrDefault(false)
    }

    private fun recordStaleNetworkTransitionProbe(
        change: TerminalNetworkChange,
        target: ConnectionTarget,
        source: String,
        guard: RuntimeRefreshGuard,
    ) {
        DiagnosticEvents.record(
            "connection",
            "network_transition_probe_stale",
            "source" to source,
            "trigger" to TmuxConnectTrigger.NetworkReconnect.logValue,
            "reason" to change.reason,
            "sequence" to change.sequence,
            "hostId" to target.hostId,
            "session" to target.sessionName,
            "generation" to guard.generation,
            "currentGeneration" to connectGeneration,
            "clientHash" to System.identityHashCode(guard.client),
            "currentClientHash" to clientRef?.let { System.identityHashCode(it) },
            "deferredFromBackground" to change.deferredFromBackground,
            *change.networkDiagnosticFields(),
        )
    }

    /**
     * Issue #1042 (cause #1): the restore RIDE-THROUGH arm. The existing transport
     * survived the loss (bounded probe answered, or #1533's same-identity reader-
     * activity vouch), so the `-CC` client was never torn down. We do NOT redial — we
     * clear the calm loss-hold band by flipping back to [ConnectionState.Live] (which
     * re-promotes the controller via `revealLive` and reopens the liveness-probe gate).
     * No fresh lease, no visible Reconnecting churn — exactly the spurious-reconnect this
     * issue kills. Records a `network_restore_ride_through` device trail so the
     * decision is visible in field logs (not mislabelled as a redial).
     */
    private fun rideThroughNetworkRestore(
        change: TerminalNetworkChange,
        target: ConnectionTarget,
        cause: String,
    ) {
        val reason = change.reason
        lifecycleReattachNetworkCoalesce = null
        Log.i(
            ISSUE_548_NETWORK_TAG,
            "tmux-network-restore-ride-through reason=$reason cause=$cause " +
                targetLogFields(target),
        )
        recordNetworkRestoreRideThrough(
            target = target,
            change = change,
            generation = connectGeneration,
            cause = cause,
            clientHash = clientRef?.let { System.identityHashCode(it) },
        )
        // Clear the loss-hold "reconnecting" band: the surviving transport is alive,
        // so flip back to Live (the -CC client was never torn down).
        revealControllerLive()
        setConnectionState(
            ConnectionState.Live(
                target.host,
                target.port,
                target.user,
            ),
        )
    }

    /**
     * Issue #997 / #1042: the restore FRESH-LEASE REDIAL arm — the #997 fallback for a
     * genuinely dead post-outage socket (the bounded probe did not answer). Drives a
     * FAST reconnect via the existing `scheduleAutoReconnect` ladder (D28: NO new
     * reconnect path). Runs ONLY past the liveness gate in
     * [scheduleNetworkReconnectOnRestore].
     */
    private fun forceFreshLeaseRestoreReconnect(
        change: TerminalNetworkChange,
        target: ConnectionTarget,
    ) {
        val reason = change.reason
        lifecycleReattachNetworkCoalesce = null
        val reconnectReason =
            "Network restored; reconnecting ${target.user}@${target.host}:${target.port}."
        Log.i(
            ISSUE_548_NETWORK_TAG,
            "tmux-network-restore-reconnect reason=$reason " +
                targetLogFields(target),
        )
        recordNetworkRestoreReconnectStart(
            target = target,
            change = change,
            generation = connectGeneration,
            clientHash = clientRef?.let { System.identityHashCode(it) },
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
            parkedRuntimeHealthEffects.onEvicted(cached.key.toHealthKey()) // #1537 unbind
            // Issue #935 R1: contained — closing a stale cached runtime issues
            // `detach-client`/close IO against a dead transport.
            launchContainedTeardown {
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
            parkedRuntimeHealthEffects.onEvicted(cached.key.toHealthKey()) // #1537 unbind
            runCatching { cached.client.close() }
            // Issue #935 R1: contained — closing the unhealthy cached runtime +
            // disconnecting its lease is teardown IO against a dead transport.
            launchContainedTeardown {
                cached.closeCachedRuntime()
                cached.lease?.key?.let { key ->
                    runCatching { sshLeaseManager.disconnect(key) }
                }
            }
            return false
        }
        parkedRuntimeHealthEffects.onActivated(cached.key.toHealthKey()) // #1537 activated
        val startedAtMs = SystemClock.elapsedRealtime()
        val milestoneStartedAtMs = visibleSwitchStartedAtMs.takeIf { it > 0L } ?: startedAtMs
        closeCachedRuntimesAsync(deactivateCurrentRuntimeToCache())
        restoreCachedRuntime(
            target = target,
            runtime = cached,
            trigger = trigger,
        )
        // Issue #1295: the WARM cached-runtime activation (a fast switch BACK to a cached
        // session — the A→B→A return) restores a LIVE-attached runtime but, unlike the
        // cold/fast-switch reveal (`:6545`/`:7041`), armed NO steady stale-render watchdog:
        // [launchCachedRuntimeRemoteRefresh] below only reconciles panes + refreshes size, it
        // never arms the lifetime net. An idle Claude pane on a warm-switched-back session that
        // later diverges would then have NO post-reveal recovery net and stay BLACK forever —
        // the exact #1295 unarmed-watchdog class. Arm the single lifetime net for the
        // reactivated runtime now (the cached active pane is already non-blank from its cached
        // frame, so the stale-render oracle can fire; arm-dedup keeps it singular, so the
        // switch can never stack a second loop for the reactivated runtime).
        val activationGuard = RuntimeRefreshGuard(
            generation = generation,
            target = target,
            client = cached.client,
        )
        if (isCurrentRuntime(activationGuard)) armActivePaneStaleRenderWatchdog(activationGuard)
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
        // Issue #1537 (option b): bind the parked runtime's liveness edges.
        parkedRuntimeHealthEffects.bindParked(
            key = runtime.key.toHealthKey(),
            client = runtime.client,
            leaseKey = runtime.lease?.key ?: target.toSshLeaseTarget().leaseKey,
        )
        leaseRef = null
        sessionRef = null
        clientRef = null
        paneRows.clear()
        paneProducerJobs.clear()
        // Issue #1206: cancel any active-runtime seed-recovery retry/deferred
        // reseed — the runtime is being parked; a fresh attach/restore re-seeds.
        paneSeedRecoveryJobs.values.forEach { it.cancel() }
        paneSeedRecoveryJobs.clear()
        // Issue #959: the parked panes' producers will be re-bound to a (new
        // or same) client on restore via [rebindRestoredRuntimePaneJobsIfNeeded];
        // drop the stale-client bindings so a restore re-binds rather than
        // assuming the cached client is still live.
        paneProducerClients.clear()
        paneOutputActivityJobs.values.forEach { it.cancel() }
        paneOutputActivityJobs.clear()
        paneInputQueues.clear()
        paneInputJobs.clear()
        panePortDetectorJobs.values.forEach { it.cancel() }
        panePortDetectorJobs.clear()
        panePortDetectorClients.clear()
        panePortDetectorGenerations.clear()
        paneSurfaceRecoveryTimestamps.clear()
        paneOverflowRecoveryTimestamps.clear()
        paneOverflowRecoveryInFlight.clear()
        paneAgentJobs.values.forEach { it.cancel() }
        paneAgentJobs.clear()
        conversationLoadWatchdogJobs.values.forEach { it.cancel() }
        conversationLoadWatchdogJobs.clear()
        paneAgentTailGenerations.clear()
        paneAgentInputs.clear()
        paneAgentNullDetections.clear()
        paneLastOutputAtMs.clear()
        paneLastSeedAtMs.clear()
        paneConversationOpenStartedAtMs.clear()
        sessionRecordedKindCache.clear()
        sessionForeignKindGuessCache.clear()
        // Issue #894 (Slice C): drop the durable confirmed-shell verdicts so a
        // different session never inherits a stale shell verdict.
        confirmedShellSessionIds.clear()
        _confirmedShellPaneIds.value = emptySet()
        // Issue #1158: drop the sticky alt-buffer agent latch with the other
        // per-runtime caches so a different session never inherits a stale verdict.
        altBufferAgentSessionIds.clear()
        _altBufferAgentPaneIds.value = emptySet()
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
                // Issue #959: the still-active producer is bound to this restored
                // client (the cached runtime carries its own client). Record the
                // binding so the next reconcile's reuse branch does NOT redundantly
                // re-bind a producer that is already live on the current client.
                paneProducerClients[paneId] = client
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
        // Issue #1537 (option b): cancel each evicted runtime's parked-health binding.
        runtimes.forEach { parkedRuntimeHealthEffects.onEvicted(it.key.toHealthKey()) }
        // Issue #935 R1: contained — async eviction of deactivated cached
        // runtimes is teardown IO that may race the transport drop.
        launchContainedTeardown {
            runtimes.forEach { runtime ->
                runtime.closeCachedRuntime()
            }
        }
    }

    // Issue #1537 (option b): parked-runtime death effect — delegates to the
    // extracted [handleParkedRuntimeDeath] (kept out of the god-object, #1047).
    private fun onParkedRuntimeDeath(
        key: RuntimeHealthKey,
        leaseKey: SshLeaseKey?,
        cause: RuntimeDeathCause,
    ) = handleParkedRuntimeDeath(
        key = key,
        leaseKey = leaseKey,
        cause = cause,
        runtimeCache = runtimeCache,
        foregroundLeaseKeys = setOfNotNull(
            activeTarget?.toSshLeaseTarget()?.leaseKey,
            connectingTarget?.toSshLeaseTarget()?.leaseKey,
        ),
        disconnectLease = { key2 ->
            withContext(NonCancellable) { runCatching { sshLeaseManager.disconnect(key2) } }
        },
        launchContained = { block -> launchContainedTeardown { block() } },
    )

    private suspend fun closeCachedRuntimesBounded(runtimes: List<CachedTmuxRuntime>) {
        if (runtimes.isEmpty()) return
        // Issue #1085 (F2): this is invoked from [deferConnectionTeardownOffMain]
        // on the application-scoped [teardownScope], NOT on the Main thread — the
        // previous `closeCachedRuntimesBlocking` ran `runBlocking(Dispatchers.IO)`
        // directly from `onCleared` (Main), and because each cached runtime's
        // close does a non-suspending nested-`runBlocking` socket close that a
        // coroutine timeout cannot interrupt, the real Main park was the SUM of
        // the N per-runtime ceilings (a multi-second / ANR-class freeze on a
        // wedged transport). [closeCachedRuntime] still bounds its own suspending
        // steps at SYNC_DETACH_TIMEOUT_MS; the outer ceiling here is a
        // belt-and-suspenders guard that scales with the runtime count.
        runCatching {
            withTimeoutOrNull(SYNC_DETACH_TIMEOUT_MS * runtimes.size) {
                runtimes.forEach { runtime ->
                    runtime.closeCachedRuntime(detachTimeoutMs = SYNC_DETACH_TIMEOUT_MS)
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
        // Issue #959: the cached client bindings are re-established below by
        // [rebindRestoredRuntimePaneJobsIfNeeded] (keep-or-rebind to the
        // restored client). Start empty so a reconcile that races the restore
        // never trusts a stale binding.
        paneProducerClients.clear()
        paneOutputActivityJobs.clear()
        panePortDetectorJobs.values.forEach { it.cancel() }
        panePortDetectorJobs.clear()
        panePortDetectorClients.clear()
        panePortDetectorGenerations.clear()
        paneInputQueues.clear()
        paneInputQueues.putAll(runtime.paneInputQueues)
        paneInputJobs.clear()
        paneInputJobs.putAll(runtime.paneInputJobs)
        // Issue #1206: a restored prewarmed runtime may still carry a parked
        // seed-recovery job (its capture stayed empty). Move it into the
        // active-runtime registry so it is cancelled on the next
        // [deactivateCurrentRuntimeToCache] (and the whole-VM teardown) rather
        // than leaking. Cancel any stale active entry first.
        paneSeedRecoveryJobs.values.forEach { it.cancel() }
        paneSeedRecoveryJobs.clear()
        paneSeedRecoveryJobs.putAll(runtime.paneSeedRecoveryJobs)
        paneSurfaceRecoveryTimestamps.clear()
        paneOverflowRecoveryTimestamps.clear()
        paneOverflowRecoveryInFlight.clear()
        paneAgentJobs.clear()
        // Issue #793: drop any pending load watchdogs from the prior runtime so
        // a restored, already-populated conversation row is not flipped to
        // Failed by a stale timer.
        conversationLoadWatchdogJobs.values.forEach { it.cancel() }
        conversationLoadWatchdogJobs.clear()
        paneAgentTailGenerations.clear()
        paneAgentInputs.clear()
        paneAgentNullDetections.clear()
        paneLastOutputAtMs.clear()
        paneLastSeedAtMs.clear()
        paneConversationOpenStartedAtMs.clear()
        sessionRecordedKindCache.clear()
        sessionForeignKindGuessCache.clear()
        // Issue #894 (Slice C): the parked runtime does not carry the durable
        // confirmed-shell verdict set; clear it so the restored session never
        // inherits a stale verdict. refreshCurrentSessionRecordedKind re-reads
        // the `@ps_agent_kind` on the restored connection and re-derives it.
        confirmedShellSessionIds.clear()
        _confirmedShellPaneIds.value = emptySet()
        // Issue #1158: drop the sticky alt-buffer agent latch with the other
        // per-runtime caches so a different session never inherits a stale verdict.
        altBufferAgentSessionIds.clear()
        _altBufferAgentPaneIds.value = emptySet()
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
        // EPIC #687 P1: the warm cache-hit reveals the target's own pane WITHOUT a
        // fresh capture, so promote the reveal machine to Live for the target here
        // (otherwise the NEW-path reveal gate would stay held and the surface would
        // never mount on a switch back to a cached session).
        promoteRevealLiveForActiveTarget()
        revealControllerLive()
        setConnectionState(
            ConnectionState.Live(
                target.host,
                target.port,
                target.user,
            ),
        )
        markSuccessfulAttachForNetworkCoalescing(target, trigger)
        // Issue #1083 (the ONE residual #874 black-screen structural gap): the
        // #874/#1004 dropped-Conversation-row re-seed only fired when the SCREEN
        // happened to re-call [refreshCurrentSessionRecordedKind] after a switch
        // (TmuxSessionScreen.kt). A cache-restore that does NOT trigger that
        // screen recomposition left a presumed-agent pane whose row was dropped
        // (the R3-B 2-null collapse — `restoreCachedRuntime` only restarts rows
        // that carried a live `detection`) falling through to the always-mounted
        // raw `TmuxTerminalPager` → the #807 black void, "very hard to force a
        // redraw". Drive the recorded-kind read from the RESTORE OPERATION itself
        // so the void close is coupled to the restore, not the screen lifecycle.
        //
        // This re-reads `@ps_agent_kind` over the just-restored warm session
        // (D21 — no new connection) and applies the verdict
        // ([applyRecordedShellVerdict]), which is the SINGLE re-seed point:
        //  - a NOT-shell (foreign / agent / re-classified) verdict RE-SEEDS the
        //    #878 Conversation placeholder for every presumed-agent pane that lost
        //    its row, closing the void; and
        //  - a confirmed-shell verdict does NOT re-seed (#894 no-flash invariant),
        //    so driving this from restore — BEFORE we know the kind — never flashes
        //    a genuine shell, because the re-seed runs AFTER the verdict resolves.
        // It is idempotent with the still-live screen-driven path: the screen may
        // also call [refreshCurrentSessionRecordedKind], but
        // [seedPresumedAgentPlaceholder] self-gates on `containsKey`, so a row
        // already (re-)seeded is never double-seeded. A failed read degrades to a
        // null (not-shell) verdict, which still re-seeds — the void is closed even
        // when the remote read cannot resolve.
        refreshCurrentSessionRecordedKind()
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
        lastSuppressedDropDiagnostic = null
        try {
            // Issue #1098 item 3 test seam: a GENUINELY-unrecoverable host fails every
            // fresh-dial of the auto-reconnect ladder (a reconnect trigger), so the
            // bounded ladder exhausts and the honest "Disconnected from …" band
            // surfaces — modelling sshd dead / port blackholed / a network cut that
            // stays cut. The cold OPEN (a non-reconnect trigger) is left alone so the
            // test's initial attach still succeeds. Production never arms it; the throw
            // routes through the same `catch` a real connection-refused does.
            if (forceUnrecoverableHostForTest && trigger.isReconnectTrigger) {
                throw IOException(
                    "synthetic unrecoverable host: connection refused (issue #1098 item 3 test seam)",
                )
            }
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
                // Issue #998: a reconnect/lifecycle/network reattach expects the
                // server to already be running, so probe for server-death and
                // refuse the silent `new-session -A` resurrection if it is gone.
                probeServerLiveness = trigger.isReconnectTrigger,
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
            // target). Gate the reveal on a non-empty active-pane seed, otherwise
            // keep the calm "Attaching…" hold and hand off to
            // [armConnectedBlankWatchdog].
            val activePaneSeeded = awaitActivePaneSeededOrLoading(blankReseedGuard)
            // EPIC #687 P1: when the active pane is seeded non-blank, the inline path
            // reveals the target's surface — promote the reveal machine to Live for
            // the target so the NEW-path reveal gate releases in lockstep.
            if (activePaneSeeded) promoteRevealLiveForActiveTarget()
            revealControllerLive()
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
            // Issue #966/#967: arm the steady-state stale-render watchdog for the
            // runtime's lifetime. The blank watchdog only catches a pane that is
            // black AT reveal; this net catches a pane that paints fine then LATER
            // goes black-with-fragments (a drop-induced stale grid on a live
            // transport — the #966 shape the blank oracle structurally misses).
            //
            // Issue #973 (v0.4.18 regression): arm it ONLY when the active pane
            // genuinely painted at reveal ([activePaneSeeded]). A still-blank
            // reveal is OWNED by [armConnectedBlankWatchdog] above — arming the
            // stale-render net there too would race the same blank pane with a
            // SECOND `capture-pane` loop (the #693/#661 never-reveal-black guard
            // counts an exact bound; the stray captures broke that invariant) and
            // the stale-render oracle ([visibleScreenDivergesFromCapture]) cannot
            // even fire on a blank pane. When the blank watchdog later recovers a
            // frame it arms the stale-render net itself, so the #966 lifetime net
            // is preserved for that path without the blank-pane double-capture.
            if (activePaneSeeded) armActivePaneStaleRenderWatchdog(blankReseedGuard)
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
            if (t is TmuxServerDeadException) {
                // Issue #998: the remote tmux SERVER is gone (host reboot / OOM /
                // kill-server). Every session vanished. Do NOT route this through
                // the auto-reconnect ladder — `new-session -A` would boot a fresh
                // empty server and silently resurrect a blank "Connected" session
                // (the data-loss-looking bug). Surface "the server restarted —
                // all sessions ended" and drop to the host/session list.
                failServerDied(target, attempt, startedAtMs, t)
            } else if (t is TmuxSessionNotFoundException) {
                failSessionEnded(target, attempt, startedAtMs, t)
            } else {
                failConnectAttempt(
                    target = target,
                    attempt = attempt,
                    startedAtMs = startedAtMs,
                    message = connectFailureMessage(t, target.sessionName),
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
        // Issue #1574: genuinely gone — clear the retained intent, no resurrection (#666).
        latestConnectIntent = null
        refreshReconnectAvailability()
        setConnectionState(
            ConnectionState.Unreachable("Session “${target.sessionName}” has ended."),
        )
        // Issue #1155 (Part B): this is the GENUINELY-GONE path — the attach failed
        // with a [TmuxSessionNotFoundException] (an absent tmux session), NOT a
        // transient reconnect blip (those go through [failConnectAttempt] and the
        // reconnect ladder, which never reach here). Broadcast the gone session +
        // its folder so the folder tree offers "create a new session in this
        // folder?" instead of a blank drop-to-list.
        sessionLifecycleSignals?.emitStaleSession(
            hostId = target.hostId,
            sessionName = target.sessionName,
            folderPath = target.startDirectory,
        )
        _sessionEnded.tryEmit(target.sessionName)
    }

    /**
     * Issue #998: terminal handling for a reattach that found the remote tmux
     * SERVER gone (host reboot / OOM / `kill-server`). Sibling of
     * [failSessionEnded] — the difference is the *scope* of the loss: a dead
     * server means EVERY session vanished, not one. Like [failSessionEnded] this
     * does NOT preserve a reconnect target, does NOT auto-reconnect, and does NOT
     * `new-session -A`-resurrect (which on a dead server boots a brand-new empty
     * server — exactly the silent-resurrection bug). We tear the half-open
     * connection down, evict the runtime lease, surface a server-death
     * [ConnectionState.Unreachable] message, and emit the one-shot [sessionEnded]
     * event so the Screen drops to the host/session list (which already renders
     * the empty `no server running` state correctly) and clears the persisted
     * last-session snapshot.
     */
    private suspend fun failServerDied(
        target: ConnectionTarget,
        attempt: Int,
        startedAtMs: Long,
        cause: TmuxServerDeadException,
    ) {
        lastConnectFailureCause = cause
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-server-died host=${target.host} session=${target.sessionName} " +
                "${targetLogFields(target)} attempt=$attempt — server gone, dropping to list, not recreating",
        )
        DiagnosticEvents.record(
            "connection",
            "reconnect_server_died",
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
        // Issue #1574: server gone — clear the retained intent (#998).
        latestConnectIntent = null
        refreshReconnectAvailability()
        setConnectionState(
            ConnectionState.Unreachable(
                "The tmux server on ${target.hostName} restarted — all sessions ended.",
            ),
        )
        _sessionEnded.tryEmit(target.sessionName)
    }

    /**
     * Issue #666 / #1155: session-existence liveness gate. Runs as the
     * [connectJob] for a [TmuxConnectTrigger.ColdRestore] resume OR a genuine
     * COLD [TmuxConnectTrigger.OpenExisting] tree-row tap, and probes `tmux
     * has-session` over the pooled SSH transport BEFORE any attach or
     * warm/cached-runtime activation.
     *
     * Why up front, not just in [runConnect]: the resurrection the maintainer
     * hit happens when the attach activates a STALE warm/cached runtime whose
     * `-CC` channel then EOFs (the session was killed), which kicks the
     * auto-reconnect path — and that path re-attaches with `new-session -A`,
     * recreating the session (as a bare shell). Probing here, before we touch
     * any cached runtime, catches the gone session regardless of which attach
     * path would follow.
     *
     * - Session GONE (`has-session` exits non-zero): surface "that session
     *   ended" and drop to the list via [failSessionEnded] — which also
     *   broadcasts the #1155 stale-session recreate prompt — never attach,
     *   never silently recreate.
     * - Session ALIVE (`has-session` exits 0): re-enter [connect] with
     *   `originalTrigger` + `skipExistencePreflight = true` so the normal
     *   (warm/cached or cold) attach proceeds unchanged — no #634/#177
     *   regression, and a normal OpenExisting tap attaches as before.
     * - Probe could not run (no lease / exec error): fail OPEN — fall through
     *   to the normal attach so a transient transport hiccup never masquerades
     *   as "session ended" (no spurious recreate prompt). The attach-only
     *   [runConnect] preflight is the second line of defence there.
     */
    private fun preflightSessionExistence(
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
        // Issue #1155: the trigger to RE-ENTER connect with once the session is
        // confirmed alive (ColdRestore for the resume path, OpenExisting for a
        // normal tree-row tap). The GONE branch is identical for both:
        // [failSessionEnded] drops to the list AND broadcasts the stale-session
        // recreate prompt.
        originalTrigger: TmuxConnectTrigger,
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
        submitControllerOpen(target)
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
                    trigger = originalTrigger,
                    skipExistencePreflight = true,
                )
            }
            if (lease == null) {
                // No transport to probe with — fail open and let the normal
                // attach (and its attach-only runConnect preflight) handle it.
                Log.i(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-existence-preflight-no-lease session=$sessionName " +
                        "trigger=${originalTrigger.logValue} — failing open to normal attach",
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
                    // The probe exec failed (transient hiccup, not confirmed-gone): fail
                    // open. Issue #1328 (#1321 §1b): a `transport is closed` probe = the
                    // reused warm lease died silently; evict it first so proceed() dials
                    // FRESH (else re-attaching that dead lease hard-`Failed` the reconnect).
                    val probeError = probe.exceptionOrNull()
                    val closedTransport = isStaleChannelSymptom(probeError)
                    if (closedTransport) {
                        withContext(NonCancellable) {
                            runCatching { sshLeaseManager.disconnect(leaseTarget.leaseKey) }
                        }
                    }
                    Log.i(
                        ISSUE_145_RECONNECT_TAG,
                        "tmux-existence-preflight-probe-error session=$sessionName " +
                            "trigger=${originalTrigger.logValue} " +
                            "cause=${probeError?.javaClass?.simpleName} " +
                            "closedTransport=$closedTransport — failing open (dial fresh)",
                    )
                    proceed()
                }
                result.exitCode != 0 -> {
                    // Session is CONFIRMED GONE — do NOT attach or recreate it.
                    Log.i(
                        ISSUE_145_RECONNECT_TAG,
                        "tmux-existence-session-gone session=$sessionName " +
                            "trigger=${originalTrigger.logValue} exit=${result.exitCode} " +
                            "— dropping to list + stale-session recreate prompt, not recreating",
                    )
                    failSessionEnded(
                        target = target,
                        attempt = 0,
                        startedAtMs = SystemClock.elapsedRealtime(),
                        cause = TmuxSessionNotFoundException(sessionName),
                    )
                }
                else -> {
                    // Session is alive — proceed with the normal attach for the
                    // original trigger (ColdRestore resume or OpenExisting tap).
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
        // Issue #1537 (option b): consult the parked-health ledger before attach.
        parkedRuntimeHealthEffects.consumeParkedDeath(target.toHealthKey())?.let { cause ->
            ReconnectCauseTrail.record(
                "parked_runtime_health", "switch_consult_dead", cause.name, trigger.logValue,
                "hostId" to target.hostId,
            )
        }
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
                submitControllerOpen(target)
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
            // pane seed (bounded retries).
            val fastSwitchRevealGuard = RuntimeRefreshGuard(
                generation = connectGeneration,
                target = target,
                client = client,
            )
            val activePaneSeeded = awaitActivePaneSeededOrLoading(fastSwitchRevealGuard)
            // EPIC #687 P1: the fast-switch reveal shows the target's seeded pane —
            // promote the reveal machine to Live for the target so the NEW-path reveal
            // gate releases in the same mutation (never holds on a warm switch).
            if (activePaneSeeded) promoteRevealLiveForActiveTarget()
            revealControllerLive()
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
            // Issue #966/#967: arm the steady-state stale-render watchdog on the
            // fast-switch reveal path too, so a pane that later goes
            // black-with-fragments on this runtime is healed against tmux's grid.
            //
            // Issue #973 (v0.4.18 regression): gate on [activePaneSeeded] — a
            // still-blank fast-switch reveal is owned by the blank watchdog armed
            // just above; arming the stale-render net on a blank pane would race a
            // second `capture-pane` loop and break the #693/#661 never-reveal-black
            // capture-count invariant. The blank watchdog arms this net once it
            // recovers a frame.
            if (activePaneSeeded) armActivePaneStaleRenderWatchdog(fastSwitchRevealGuard)
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
            // Issue #1537 (option b) — the silent-TCP-death FALLBACK: the switch
            // reused a silently-dead warm session (no parked-health edge fired in
            // the keepalive window), so the attach EOFs here. Route it into the
            // SINGLE #1328 ladder as a CALM hold (not the old jarring [Connecting]
            // overlay + cleared panes, not the deleted bespoke counter). The
            // [AutoReconnect] re-dial cannot re-enter this arm (loop protection).
            if (isStaleChannelSymptom(t) && !trigger.isReconnectTrigger) {
                Log.i(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-stale-lease-fallback fast-switch EOF -> single-ladder re-dial; " +
                        targetLogFields(target),
                )
                ReconnectCauseTrail.record(
                    stage = "stale_lease_auto_recover",
                    outcome = "fast_switch_fresh_redial",
                    cause = "stale_lease_attach_eof",
                    trigger = trigger.logValue,
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
                // Calm hold: keep the [Attaching]/[Switching] band (the reveal
                // machine holds the surface), so the silent-corpse fallback never
                // flashes the full-screen [Connecting] overlay + cleared panes.
                submitControllerSwitch(target)
                setConnectionState(
                    ConnectionState.Attaching(
                        target.host,
                        target.port,
                        target.user,
                    ),
                )
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
                message = connectFailureMessage(t, target.sessionName),
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
        // Issue #1185: the acquire that failed here may not be a genuine
        // unreachable at all — the SELECTED session's lease acquire may have
        // COALESCED (#620) onto an in-flight connect owned by a just-superseded
        // create/attach flow on the same host, and the user's own navigation
        // (create-new → immediately select an existing session) cancelled that
        // owner. The lease wakes the awaiter with a TYPED
        // [SshLeaseConnectCoalescedCancelException] (NOT a bare
        // CancellationException) precisely so this consumer can tell it apart
        // from an unreachable host and re-dial the selected session's OWN fresh
        // connect instead of stranding it on a Disconnected pill + "Attaching…"
        // spinner with no recovery.
        val coalescedCancel = isCoalescedConnectCancel(cause)
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

        // Issue #1185: a coalesced-connect cancel of the selected session's
        // superseded owner is NOT a terminal failure. The pool slot is already
        // cleared, so re-dial the SELECTED session's own fresh connect (which
        // becomes a new owner and dials cleanly) transparently — the user asked
        // for THIS session; a supersede of an unrelated flow's connect must never
        // strand it. Guarded so we never re-dial a session the user has since
        // navigated away from, and bounded so a pathological repeat cannot loop
        // forever (then the honest terminal error + working Retry surfaces).
        if (shouldRedialAfterCoalescedCancel(coalescedCancel, target)) {
            coalescedCancelRedialAttempts += 1
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-coalesced-cancel-redial selected session re-dials own connect after " +
                    "superseded-owner cancel; " +
                    "attempt=$coalescedCancelRedialAttempts/$COALESCED_CANCEL_REDIAL_MAX " +
                    "trigger=${trigger.logValue} ${targetLogFields(target)}",
            )
            ReconnectCauseTrail.record(
                stage = "coalesced_cancel_redial",
                outcome = "transparent_redial",
                cause = "connect_cancelled",
                trigger = trigger.logValue,
                "attempt" to coalescedCancelRedialAttempts,
                "maxAttempts" to COALESCED_CANCEL_REDIAL_MAX,
                "hostId" to target.hostId,
                "failureClass" to cause.javaClass.simpleName,
            )
            // Show a calm Reattaching band immediately so the surface is never a
            // Disconnected/Failed band (and never a stranded "Attaching…" spinner)
            // for the brief window before the fresh re-dial coroutine is
            // dispatched. Leave [connectingTarget] null so the recovery's
            // [connect] is not swallowed by its in-flight early-return guard while
            // this failing job unwinds.
            connectingTarget = null
            refreshReconnectAvailability()
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
            val recoverTarget = target
            connectJob = null
            launchContainedTeardown {
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

        // Issue #621 / #634 / #636 (Slice 4): an open/switch attach that EOFs
        // on a silently-dead warm lease (`tmux -CC` open or `list-panes`) must
        // NOT strand the user on a Disconnected band + manual Reconnect. The
        // poisoned lease was already evicted above (staleChannelSymptom), so a
        // FRESH re-dial will attach to the live tmux session. Kick off the
        // existing auto-reconnect machinery transparently instead of surfacing
        // Failed, so the heal is invisible to the user.
        //
        // Only for the INITIAL user-facing open/switch (not-yet-a-reconnect
        // triggers); a reconnect trigger is owned by the [scheduleAutoReconnect]
        // loop. Issue #1537 (option b, D22 hard-cut): loop protection is the
        // SINGLE ladder's own budget, not the deleted bespoke
        // `staleLeaseAutoRecoverAttempts` seam — the [AutoReconnect] re-dial
        // cannot re-enter this arm, so a genuinely-dead host falls through to the
        // honest terminal Disconnected band.
        if (
            staleChannelSymptom &&
            appActive &&
            autoReconnectDelaysMs.isNotEmpty() &&
            !trigger.isReconnectTrigger
        ) {
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-stale-lease-fallback transparent re-dial after stale attach EOF; " +
                    "trigger=${trigger.logValue} ${targetLogFields(target)}",
            )
            ReconnectCauseTrail.record(
                stage = "stale_lease_auto_recover",
                outcome = "transparent_redial",
                cause = "stale_lease_attach_eof",
                trigger = trigger.logValue,
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
            // Issue #935 R1: contained — the post-EOF recovery re-dial is a
            // top-level re-`connect()` on the close/recovery cascade; an uncaught
            // throw while the prior connect unwinds must not crash the process.
            launchContainedTeardown {
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

        // Issue #1185: a coalesced-connect cancel that reached the terminal
        // fallback (its bounded re-dial budget was exhausted) must still leave a
        // WORKING Retry — preserve the reconnect target so [canReconnect] is true
        // and the user is never dead-ended on a Disconnected pill with no
        // Reconnect affordance.
        connectingTarget =
            if (preserveReconnectTarget || staleChannelSymptom || coalescedCancel) target else null
        refreshReconnectAvailability()
        // Issue #1328 (S5): while the ladder ([autoReconnectJob]) is active, do NOT
        // terminalize — the loop + controller own advance-vs-exhaust (the deleted
        // over-exhaust guard masked this premature "Unreachable on rung 1").
        if (autoReconnectJob?.isActive == true) return
        setConnectionState(ConnectionState.Unreachable(message))
        // Issue #1185 (two-holder safety net): drive the reveal machine DIRECTLY
        // to a terminal error for THIS exact target instead of relying solely on
        // the ConnectionController's synthetic drop→Unreachable ladder to deliver a
        // matching-id edge to the reveal collector. This guarantees the reveal
        // surface ("Attaching…"/Seeding) and the status pill (Disconnected) can
        // never disagree — a terminal connect failure moves BOTH holders to an
        // honest error in lockstep, closing the #1185 contradictory Disconnected +
        // live-spinner surface. Drop-by-id inside the machine makes this a no-op if
        // the user has since navigated to another session.
        driveRevealTerminalError(target, cause)
    }

    /**
     * Issue #1185: decide whether to transparently re-dial the SELECTED session's
     * own connect after its lease acquire was woken with a coalesced-connect
     * cancel (a superseded owner on the same host was cancelled by the user's
     * create-new → immediately-select navigation).
     *
     * Conditions:
     *  - the failure is the typed [SshLeaseConnectCoalescedCancelException]
     *    ([coalescedCancel]) — never a genuine unreachable/auth/DNS error, which
     *    must still surface the honest terminal error (no infinite re-dial);
     *  - the app is foregrounded (a backgrounded app must not burn re-dials);
     *  - auto-reconnect is enabled (the user hasn't disabled it / a test hasn't
     *    cleared the delays);
     *  - the per-chain budget has not been exhausted, so a host whose in-flight
     *    connect keeps getting cancelled cannot loop forever;
     *  - the selected session is STILL the latest connect intent — if the user
     *    navigated away from it, a newer intent supersedes it and we must NOT
     *    re-dial a session the user abandoned (the top-2 risk the spike flagged).
     */
    @androidx.annotation.VisibleForTesting
    internal fun shouldRedialAfterCoalescedCancel(
        coalescedCancel: Boolean,
        target: ConnectionTarget,
    ): Boolean =
        coalescedCancel &&
            appActive &&
            autoReconnectDelaysMs.isNotEmpty() &&
            coalescedCancelRedialAttempts < COALESCED_CANCEL_REDIAL_MAX &&
            latestConnectIntent?.let { sameSessionIdentity(it.target, target) } == true

    /**
     * Issue #1185: true when [cause] is the typed coalesced-connect cancel the
     * lease raises when the awaiter's superseded owner was cancelled (#620
     * coalescing cleanup). Distinct from every [isStaleChannelSymptom] shape and
     * from a genuine unreachable, so the consumer re-dials the selected session
     * rather than misclassifying a user-supersede as a terminal failure.
     */
    @androidx.annotation.VisibleForTesting
    internal fun isCoalescedConnectCancel(cause: Throwable?): Boolean {
        var current: Throwable? = cause
        var guard = 0
        while (current != null && guard < 12) {
            if (current is SshLeaseConnectCoalescedCancelException) return true
            current = current.cause
            guard += 1
        }
        return false
    }

    /**
     * Issue #1185 (two-holder safety net): drive the [RevealStateMachine] to a
     * terminal honest error for [target] directly, so the reveal surface and the
     * status pill never disagree on a terminal connect failure. Drop-by-id inside
     * the machine makes this a no-op when the user has navigated to another
     * session.
     */
    private fun driveRevealTerminalError(target: ConnectionTarget, cause: Throwable?) {
        // Issue #1326 (S3): carry the TYPED failure reason (the single
        // [classifyFailure] classifier) to the reveal machine so the honest error
        // surface renders a curated sentence, never a raw exception string.
        revealController.driveTerminalError(target, cause)
    }

    @androidx.annotation.VisibleForTesting
    internal fun isStaleChannelSymptom(cause: Throwable?): Boolean =
        TmuxSessionFailureClassifiers.isStaleChannelSymptom(cause)

    /**
     * Issue #1326 (characterization test seam): drive the id-keyed reveal machine to
     * a HELD (Seeding) surface for the active target — the loading-hold state a
     * connected-but-blank pane hands off into. Replaces the deleted
     * `setSwitchHidesTerminalForTest` seam now that the reveal machine (not a
     * separate flag) is the single surface-hold authority.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setRevealHoldForTest() {
        val target = activeTarget ?: connectingTarget ?: return
        revealController.holdFor(target)
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
    ): String = tmuxReconnectSourceCandidate(trigger, sourceCandidate)

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
    ): String = tmuxAttachMilestoneMessage(
        attempt = attempt,
        target = target,
        startedAtMs = startedAtMs,
        event = event,
        trigger = trigger,
        detail = detail,
    )

    // Issue #1224: fed by the per-pane [TmuxClient.outputFor] tap
    // ([recordVisiblePaneOutput]), NOT by `%output` on the structural
    // [TmuxClient.events] bus. `%output` is no longer multiplexed onto that
    // shared bus — a dense output burst used to fill it and silently drop a
    // burst-tail structural event (`%window-close` / `%session-changed`). The
    // first-visible-output milestone now rides the pane's own output stream.
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
        // round-trips (the ANR). Issue #1224: `%output` no longer rides the
        // events bus at all — it is delivered via the per-pane `outputFor` pipes.
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
                // Issue #1224: [TmuxClient.events] carries STRUCTURAL events only
                // now (`%output` is off the shared bus). Structural events drive a
                // `list-panes` reconcile; route them through the coalescer
                // (non-blocking offer) so a burst collapses to ~1 off-main
                // reconcile per frame. No non-structural event needs handling here.
                if (LayoutChangeCoalescer.isStructural(event)) {
                    coalescer.offer(event)
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
                val decision = passiveTransportDropEffects.classify(client)
                if (decision == PassiveDropArm.Ignore) {
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
        // Issue #928 (VM-shrink extraction): body lives in the connection sibling.
        recordPassiveDisconnect(
            clientHash = System.identityHashCode(client),
            target = target,
            disconnectEvent = disconnectEvent,
            generation = connectGeneration,
            attempt = activeAttachMilestone?.attempt,
            activeTrigger = activeAttachMilestone?.trigger?.logValue,
            status = _connectionStatus.value.javaClass.simpleName,
            shortAppSwitchFields = shortAppSwitchReconnectFields(
                trigger = TmuxConnectTrigger.AutoReconnect,
                target = target,
                sourceCandidate = "passive_disconnect",
            ),
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
        // connection-core [PassiveTransportDropEffects] self-gates the IO arm by client
        // identity (a stale-client drop is ignored there). The display status no longer
        // gates recovery — only the client-identity guard in [PassiveTransportDropEffects]
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
            recordSuppressedDropDiagnostic(
                suppressedDropDiagnosticFor(
                    disconnectEvent = disconnectEvent,
                    source = "passive_disconnect_deferred_to_grace_owner",
                ),
            )
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
        val decision = passiveTransportDropEffects.classify(client)
        if (decision == PassiveDropArm.Ignore) {
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
            PassiveDropArm.SkipInAppNavigation ->
                if (target != null) skipPassiveInAppNavigation(target)
            PassiveDropArm.PauseUntilForeground ->
                if (target != null) pausePassiveUntilForeground(target, reason, disconnectEvent)
            PassiveDropArm.SilentReattachWithinGrace ->
                silentReattachWithinPassiveGrace(client, target, reason, disconnectEvent)
            PassiveDropArm.Ignore -> Unit
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
     * EPIC #687 slice 1a: the [PassiveDropArm.SkipInAppNavigation]
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
     * EPIC #687 slice 1a: the [PassiveDropArm.PauseUntilForeground]
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
     * EPIC #687 slice 1a: the [PassiveDropArm.SilentReattachWithinGrace]
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
        // Issue #928 (T6): capture the #969 close attribution BEFORE the redial
        // machinery swaps sessionRef — a keepalive-declared death enters the
        // per-host amortizer below; every other drop class keeps today's
        // instant silent reattach.
        val keepaliveDeath = sessionRef?.closeCause == SshSessionCloseCause.KeepaliveDead
        // Issue #896: this silent-reattach grace loop re-dials the transport and
        // re-reads the (possibly now-gone) session right on the kill→EOF cascade,
        // racing the scope teardown — exactly where an unguarded IO throw against
        // the dead transport would otherwise crash. It is a viewModelScope.launch
        // (not bridgeScope) so it doesn't inherit the scope-level net; attach the
        // same handler explicitly so a teardown-race throw here is swallowed +
        // recorded, never a process death.
        val graceJob = viewModelScope.launch(bridgeExceptionHandler) {
            if (keepaliveDeath && target != null) {
                // Issue #928 (T6): the Nth-in-a-row keepalive-death episode waits
                // an escalating grace (honest calm band + #1521 Reconnect button
                // shown) instead of reloading on a fixed cadence; a manual
                // reconnect cancels this job and dials immediately (T8 bypass).
                keepaliveDeathRedialAmortizer.awaitRedialGrace(target, connectGeneration)
            }
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
        // Issue #1568 (P0-2): prefer the lease-EVICTING fresh dial ONLY when a warm lease is held
        // AND the transport is not vouched alive; a vouched-alive `-CC` death recovers over the
        // LIVE transport so a channel hiccup never costs the shared per-host lease.
        val preferFreshTransport = preferFreshTransportForPassiveReattach(
            warmLeaseHeld = leaseRef != null,
            transportVouchedAlive = transportVouchedAlive(),
        )
        // EPIC #792 #833: count fresh-transport re-dials (not a one-shot latch) so a SUSTAINED
        // clean outage keeps RE-DIALLING a fresh transport across the bounded grace window and
        // the SAME session auto-recovers the moment the link returns — no switch dance. Bounded
        // by the 60s grace `withTimeoutOrNull` + per-attempt timeout + 250ms retry spacing (no
        // hot loop); never short-circuits a HEALTHY warm reattach (#635/#553).
        var transportReattachAttempts = 0
        recordSilentReattachStart(
            target = target,
            staleClientHash = System.identityHashCode(staleClient),
            graceMs = passiveDisconnectGraceMs,
            preferFreshTransport = preferFreshTransport,
            shortAppSwitchFields = shortAppSwitchReconnectFields(
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
                // EPIC #792 #833: when a fresh transport is preferred, RE-DIAL it on EVERY
                // iteration (not once) so a sustained clean outage keeps escalating into a
                // retrying ladder — the SAME session recovers when the link returns, no switch
                // dance. Bounded by the grace `withTimeoutOrNull` + per-attempt timeout + spacing.
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
                // No fresh transport preferred: the warm reattach above is the cheap path; if it
                // can't recover (the warm SSH session itself is gone) escalate to a fresh
                // transport and keep re-dialling it for the same sustained-outage resilience.
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
                recordSilentReattachFail(
                    target = target,
                    staleClientHash = System.identityHashCode(staleClient),
                    transportReattachAttempts = transportReattachAttempts,
                    shortAppSwitchFields = shortAppSwitchReconnectFields(
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
        // Issue #1098 item 3: a GENUINELY-unrecoverable host fails this primitive for
        // the whole window so the ladder exhausts instead of recovering.
        if (forceCleanOutageForTest || forceUnrecoverableHostForTest) return false
        val session = sessionRef?.takeIf { it.isConnected } ?: return false
        val startedAtMs = SystemClock.elapsedRealtime()
        val replacement = createTmuxClient(
            session,
            target.sessionName,
            target.startDirectory,
            probeServerLiveness = true,
        )
        return try {
            val ready = withTimeoutOrNull(timeoutMs) {
                eventsJob?.cancelAndJoin()
                eventsJob = null
                outputOverflowJob?.cancelAndJoin()
                outputOverflowJob = null
                disconnectedJob?.cancelAndJoin()
                disconnectedJob = null
                // Issue #866: re-point the current-client port (and clientRef) at the
                // replacement BEFORE closing the stale client. [CurrentClientTmuxPort.
                // disconnectedClients] flatMapLatest-follows the current client, and the
                // passive-drop classifier gates on `clientRef === client`. Closing the
                // stale client while it is STILL the current one re-enters the driver's
                // control-channel-drop path (classified as a current-client drop), which
                // cancels this very in-flight grace loop and relaunches it — the cancel
                // storm that wedged the silent reattach ("tries fresh transport once then
                // spins"). Swapping first makes the stale close a no-op for the driver.
                clientRef = replacement
                connectionTmuxPort.setClient(replacement)
                runCatching { staleClient.close() }
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
                // with the new control client. [healActivePaneIfStaleRender] now keeps
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
            revealControllerLive()
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
        // Issue #1098 item 3: a GENUINELY-unrecoverable host fails the fresh-transport
        // re-dial too, so the bounded ladder exhausts to the honest Unreachable band.
        if (forceCleanOutageForTest || forceUnrecoverableHostForTest) {
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
                // Issue #866: DETACH the current-client port from the stale client
                // BEFORE we tear its transport down. `disconnect(leaseKey)` kills the
                // stale `-CC` channel's underlying SSH session, so its reader EOFs and
                // `disconnected` flips true. [CurrentClientTmuxPort.disconnectedClients]
                // flatMapLatest-follows the current client, so unless we re-point it
                // first that EOF re-enters the driver's control-channel-drop path
                // (classified as a current-client drop because `clientRef` is still the
                // stale client), which cancels THIS in-flight grace loop and relaunches
                // it — the cancel storm that wedged the silent reattach ("tries a fresh
                // transport once then spins"). Detaching to null makes the port emit
                // nothing for the stale teardown; the success path re-points it at the
                // replacement below.
                connectionTmuxPort.setClient(null)
                withContext(NonCancellable) {
                    runCatching { sshLeaseManager.disconnect(leaseTarget.leaseKey) }
                }
                val lease = sshLeaseManager.acquire(leaseTarget).getOrThrow()
                acquiredLease = lease
                val session = lease.session
                val newClient = createTmuxClient(
                    session,
                    target.sessionName,
                    target.startDirectory,
                    probeServerLiveness = true,
                )
                replacement = newClient
                eventsJob?.cancelAndJoin()
                eventsJob = null
                outputOverflowJob?.cancelAndJoin()
                outputOverflowJob = null
                disconnectedJob?.cancelAndJoin()
                disconnectedJob = null
                // Issue #866: re-point the current-client port (and clientRef) at the
                // replacement BEFORE closing the stale client (see the warm-reattach
                // sibling above). Closing it while it is still the current client
                // re-enters the driver's control-channel-drop path and cancels this
                // in-flight grace loop (the cancel storm). Swap first, then close.
                leaseRef = lease
                sessionRef = session
                clientRef = newClient
                connectionTmuxPort.setClient(newClient)
                runCatching { staleClient?.close() }
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
            revealControllerLive()
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
            // Issue #998: a mid-session `%exit server exited` should kick the
            // reconnect path — NOT to silently resurrect, but because that path
            // now runs the server-liveness preflight that detects the dead server
            // and routes to failServerDied (drop to the list). Auto-reconnecting
            // here is what gives us the chance to classify and recover gracefully.
            TmuxDisconnectReason.ServerExited,
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
        val prefix = tmuxDisconnectReasonPrefix(disconnectEvent.reason)
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
        val prefix = tmuxDisconnectReasonPrefix(disconnectEvent.reason)
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
        // Issue #1328 (S5): the controller owns the SINGLE ladder; this body only dials.
        connectionManager.setReconnectLadder(delays)
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
            // Issue #1328 (S5): enter the ladder + drive off the controller's `Reconnecting`.
            connectionManager.enterReconnectLadder(
                controllerHostKey(target),
                controllerSessionId(target),
            )
            while (true) {
                val recon = connectionManager.state as? CoreConnectionState.Reconnecting ?: break
                val attemptNo = recon.attempt
                val maxAttempts = recon.maxAttempts
                val delayMs = recon.retryDelayMs
                val generation = nextConnectGeneration()
                latestConnectIntent = ConnectIntent(
                    target = target,
                    trigger = trigger,
                    generation = generation,
                )
                // Carry the reason inline; attempt/max/delay MIRROR the controller.
                setConnectionState(
                    ConnectionState.Reconnecting(
                        host = target.host,
                        port = target.port,
                        user = target.user,
                        attempt = attemptNo,
                        maxAttempts = maxAttempts,
                        retryDelayMs = delayMs,
                        reason = reason,
                    ),
                )
                recordReconnectRungScheduled(
                    target = target,
                    trigger = trigger,
                    attemptNo = attemptNo,
                    maxAttempts = maxAttempts,
                    delayMs = delayMs,
                    generation = generation,
                    reason = reason,
                    shortAppSwitchFields = shortAppSwitchReconnectFields(
                        trigger = trigger,
                        target = target,
                        sourceCandidate = "auto_reconnect",
                    ),
                )
                if (delayMs > 0) delay(delayMs)
                if (!appActive) {
                    recordAutoReconnectDecision(
                        decision = "cancelled_due_to_background",
                        target = target,
                        trigger = trigger,
                        reason = reason,
                        cause = "app_background_after_delay",
                        "attempt" to attemptNo,
                        "maxAttempts" to maxAttempts,
                    )
                    return@launch
                }
                val attempt = TMUX_CONNECT_ATTEMPTS.incrementAndGet()
                recordReconnectRungDialAttempt(
                    target = target,
                    trigger = trigger,
                    dialAttempt = attempt,
                    retryAttempt = attemptNo,
                    maxAttempts = maxAttempts,
                    generation = generation,
                    clientHash = clientRef?.let { System.identityHashCode(it) },
                    previousClientPresent = clientRef != null,
                    shortAppSwitchFields = shortAppSwitchReconnectFields(
                        trigger = trigger,
                        target = target,
                        sourceCandidate = "auto_reconnect",
                    ),
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
                            "attempt=$attemptNo " + targetLogFields(target),
                    )
                    connectingTarget = target
                    refreshReconnectAvailability()
                    // Issue #1328 (S5): honest give-up — the reducer decides Unreachable.
                    connectionManager.escalateUnreachable()
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
                        "attempt" to attemptNo,
                        "maxAttempts" to maxAttempts,
                    )
                    autoReconnectJob = null
                    return@launch
                }
                // Issue #1328 (S5): a SPECIFIC terminal error from runConnect (session ended
                // / server restarted) is not a ladder rung — keep it.
                if (connectionManager.state is CoreConnectionState.Unreachable) break
                // A retryable rung failed — the reducer advances (or exhausts) the ladder.
                connectionManager.reconnectRungFailed()
            }
            // Issue #1328 (S5): on genuine exhaustion (Unreachable, no specific terminal
            // message set) surface the unified "Disconnected from …" band (#1098).
            connectingTarget = target
            refreshReconnectAvailability()
            if (connectionManager.state is CoreConnectionState.Unreachable &&
                inlineConnectionStatus !is ConnectionStatus.Failed
            ) {
                setConnectionState(
                    ConnectionState.Unreachable(
                        "Disconnected from ${target.user}@${target.host}:${target.port}. " +
                            "Tap Reconnect to retry.",
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
            }
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

    /** S6 (#1329): the AUTHORITATIVE controller state — a test asserts it DECIDED the transition. */
    internal fun connectionControllerStateForTest(): CoreConnectionState = connectionManager.state

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
        // Issue #1085 (F2) test isolation: tear the OLD connection down
        // SYNCHRONOUSLY for this test seam. `closeCurrentConnection`'s
        // production caller (`onCleared`) defers the slow detach/close off the
        // Main thread (the F2 fix), but callers of this synchronous seam assert
        // the replaced client is closed the instant the seam returns (e.g.
        // `replacingClientClosesOldClientAndUpdatesRegistry` checks
        // `oldClient.closed`). `deferTeardown = false` runs the same teardown
        // body inline so the seam keeps its synchronous contract — independent
        // of whichever teardown scope a test injected.
        closeCurrentConnection(deferTeardown = false)
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
        revealControllerLive()
        setConnectionState(ConnectionState.Live(host, port, user))
        maybeRefreshControlClientSize()
    }

    /**
     * Issue #1083 test seam: drive the exact PARK→RESTORE round trip a session
     * switch performs — [deactivateCurrentRuntimeToCache] (the switch-away leg
     * that parks the active runtime, capturing its current
     * `agentConversations`) followed by [restoreCachedRuntime] (the switch-back
     * leg). This reproduces a cache-restore WITHOUT routing through any screen
     * recomposition / [refreshCurrentSessionRecordedKind] call, so a test can
     * assert the void-closing re-seed now fires from the restore operation
     * itself. The parked runtime keeps its own [SshSession]; the restore re-reads
     * `@ps_agent_kind` over it, exactly as production does on a warm switch-back.
     */
    @androidx.annotation.VisibleForTesting
    internal fun parkAndRestoreActiveRuntimeForTest(
        trigger: TmuxConnectTrigger = TmuxConnectTrigger.FastSwitch,
    ) {
        val target = activeTarget ?: error("#1083 test seam: no active runtime to park")
        // Park the active runtime into the cache (the switch-away leg). The
        // returned list is the EVICTED runtimes; the just-parked runtime stays in
        // the cache under `target.toRuntimeKey()` and is NOT closed here.
        deactivateCurrentRuntimeToCache()
        val runtime = runtimeCache.activate(target.toRuntimeKey()).runtime
            ?: error("#1083 test seam: parked runtime missing from cache after deactivate")
        restoreCachedRuntime(target = target, runtime = runtime, trigger = trigger)
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
        submitControllerSwitch(target)
        // Issue #437 (slice A) / #661: mirror production — a same-host fast
        // switch enters [Switching] (inline indicator), NOT the blanking
        // full-screen [Connecting] overlay; per #661 the reveal machine holds the
        // terminal surface so the leaving frame is never painted until the new
        // session's panes are seeded.
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
        // re-publish the leaving frame — blank the rendered panes and rely on the
        // reveal machine's hold to show the loading state until the new session's
        // panes reconcile and reveal.
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
        // Issue #661: reveal the new session's surface at the Connected flip
        // (the reveal machine promotes to Live via the seed/promote path).
        revealControllerLive()
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
        submitControllerOpen(target)
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
            revealControllerLive()
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
                message = connectFailureMessage(t, target.sessionName),
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
        // Issue #576 (Slice A of #792) / Issue #926: the `list-panes` round-trip
        // is a BLOCKING control-channel exchange. For the Codex `%layout-change`
        // storm — the original ANR — the caller is the [LayoutChangeCoalescer]'s
        // drain loop on [reconcileDispatcher] (`Dispatchers.Default`), already
        // off the Main event collector. The DIRECT callers (attach / switch /
        // cached-runtime refresh) used to run this `sendCommand` INLINE on Main —
        // that is the #895 freeze: an attach/switch `list-panes` against a
        // wedged-but-alive `-CC` channel parked the UI thread up to the 10 s
        // `commandTimeoutMs`. Issue #926 moves the round-trip itself OFF Main
        // onto [seedIoDispatcher] (`Dispatchers.IO`) for EVERY caller; the
        // resulting `_panes`/reveal mutation still hops back to Main via
        // [applyOnMain] below, so the single-threaded pane-state invariant and
        // the switch-ordering ([applyOnMain] last-write serialisation) are
        // preserved — the IO no longer competes with the UI thread.
        val listPanesStartedAtMs = SystemClock.elapsedRealtime()
        val response = try {
            withContext(seedIoDispatcher) {
                // Issue #1316: run the reconcile `list-panes` on the DEDICATED exec
                // lane (mirror of #1297's capture-pane move), NOT the shared per-host
                // `-CC` control channel. A new-session attach's reconcile used to
                // head-of-line-block behind a busy sibling session's `-CC` `%output`
                // burst on the ONE shared transport reader — the v0.4.24 "Attaching…"
                // wedge. The bounded [RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS] ceiling is
                // the escape: a genuinely wedged/half-open transport surfaces a
                // `Failed` fast (→ retryable "Tap Reconnect"), never a tens-of-seconds
                // input-gated freeze.
                client.listPanesViaExec(
                    buildListPanesCommand(target),
                    timeoutMs = RECONCILE_LIST_PANES_EXEC_TIMEOUT_MS,
                )
            }
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

    private fun buildListPanesCommand(target: ConnectionTarget?): String =
        buildTmuxPaneListingCommand(target?.sessionName)

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
                // Issue #959: a beyond-grace background→foreground reattaches
                // via `connect(LifecycleReattach)` against a FRESH client, but
                // tmux preserves pane ids across detach/reattach, so this is the
                // reuse branch. We keep the existing [TerminalSurfaceState] (so
                // scrollback survives), but the pane's output producer + input
                // drain are still wired to the DEAD client. RE-BIND them to the
                // live client whenever it changed identity — without this the
                // terminal is frozen (stale buffer painted, no new %output, key
                // bar / IME bytes route nowhere). This makes the reuse branch the
                // single re-bind site for EVERY reconcile caller (LifecycleReattach
                // cold reattach, parked-runtime restore, cache refresh), so the
                // post-grace path no longer depends on a `paneRows.clear()` having
                // raced ahead of it. Identity comparison: a producer bound to a
                // closed client must be replaced.
                if (client != null && paneProducerClients[p.paneId] !== client) {
                    paneProducerJobs.remove(p.paneId)?.cancel()
                    paneProducerClients.remove(p.paneId)
                    paneOutputActivityJobs.remove(p.paneId)?.cancel()
                    panePortDetectorJobs.remove(p.paneId)?.cancel()
                    panePortDetectorClients.remove(p.paneId)
                    panePortDetectorGenerations.remove(p.paneId)
                    paneInputJobs.remove(p.paneId)?.cancel()
                    paneInputQueues.remove(p.paneId)?.close()
                    existing.terminalState.detachExternalProducer()
                    attachTerminalProducerForPane(
                        paneId = p.paneId,
                        state = existing.terminalState,
                        client = client,
                    )
                }
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
            paneProducerClients.remove(paneId)
            paneOutputActivityJobs.remove(paneId)?.cancel()
            // Issue #448: stop the new-port detector for a removed pane.
            panePortDetectorJobs.remove(paneId)?.cancel()
            panePortDetectorClients.remove(paneId)
            panePortDetectorGenerations.remove(paneId)
            paneAgentJobs.remove(paneId)?.cancel()
            conversationLoadWatchdogJobs.remove(paneId)?.cancel()
            paneAgentTailGenerations.remove(paneId)
            paneAgentInputs.remove(paneId)
            paneAgentNullDetections.remove(paneId)
            paneLastOutputAtMs.remove(paneId)
            paneLastSeedAtMs.remove(paneId)
            paneInputJobs.remove(paneId)?.cancel()
            paneInputQueues.remove(paneId)?.close()
            paneSurfaceRecoveryTimestamps.remove(paneId)
            paneOverflowRecoveryTimestamps.remove(paneId)
            paneOverflowRecoveryInFlight.remove(paneId)
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
        // Issue #1158: latch any session whose reconciled pane reports the
        // SERVER-TRUTH `#{alternate_on}` flag (a full-screen agent TUI holds the
        // alternate buffer) so the Conversation tab appears for an agent launched
        // directly inside a `@ps_agent_kind=shell` session — the maintainer's
        // fleet where live detection never binds. This ALSO republishes the sticky
        // projection so a pane added by this reconcile for an already-latched
        // session keeps the tab (the latch is per-session; the pane id can rotate).
        latchAltBufferAgentsFromParsed(sorted)
        refreshAltBufferAgentPaneIds()
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
            // Issue #959: remember which client this producer (and the
            // 1-arg [inputSinkForPane] drain it just created) is bound to so a
            // later reconcile can detect a stale binding after a client swap.
            paneProducerClients[paneId] = client
            startPaneOutputActivityForPane(paneId = paneId, client = client)
            // Issue #448 (epic #432 slice C): tap the same shared output
            // flow for new-port detection whenever a pane's producer is
            // (re)attached — cold attach, warm switch, surface recreate.
            startPortDetectionForPane(paneId = paneId, client = client)
        }.onFailure { cause ->
            paneProducerClients.remove(paneId)
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
            paneProducerClients.remove(paneId)
            paneOutputActivityJobs.remove(paneId)?.cancel()
            panePortDetectorJobs.remove(paneId)?.cancel()
            panePortDetectorClients.remove(paneId)
            panePortDetectorGenerations.remove(paneId)
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
        // Issue #942: stamp the pane's last live-output wall-clock so an empty
        // grep detection can tell a still-streaming (wedged-but-alive) channel
        // apart from a genuinely-exited agent (no output) before it counts the
        // empty detection toward agent-exit confirmation.
        paneLastOutputAtMs[event.paneId] = SystemClock.elapsedRealtime()
        // Issue #1166 (heal-latency fix): fresh output on the ACTIVE visible pane
        // WAKES a backed-off stale-render watchdog so a redraw during a long
        // (8s/16s) backed-off window is captured/diffed/healed within the hot
        // bound (≤4s) instead of the next backed-off tick. Gated to the active
        // pane so a busy BACKGROUND pane can't defeat the battery back-off; the
        // trySend is cheap + non-suspending (CONFLATED channel).
        if (event.paneId == activeVisiblePane()?.paneId) {
            staleRenderWatchdogWake.trySend(Unit)
        }
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
        val guard = currentRuntimeGuardForClient(client) ?: return
        val existingJob = panePortDetectorJobs[paneId]
        if (
            existingJob?.isActive == true &&
            panePortDetectorClients[paneId] === client &&
            panePortDetectorGenerations[paneId] == guard.generation
        ) {
            return
        }
        if (existingJob != null) {
            panePortDetectorJobs.remove(paneId)
            existingJob.cancel()
        }
        panePortDetectorClients[paneId] = client
        panePortDetectorGenerations[paneId] = guard.generation
        val job = bridgeScope.launch {
            client.outputFor(paneId).collect { event ->
                if (panePortDetectorClients[paneId] !== client) return@collect
                if (panePortDetectorGenerations[paneId] != guard.generation) return@collect
                if (!isCurrentRuntime(guard)) return@collect
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
        panePortDetectorJobs[paneId] = job
        job.invokeOnCompletion {
            if (panePortDetectorJobs[paneId] === job) {
                panePortDetectorJobs.remove(paneId)
                panePortDetectorClients.remove(paneId)
                panePortDetectorGenerations.remove(paneId)
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

    /**
     * Issue #1205: the synchronous verdict the overflow handlers get from
     * [beginPaneOverflowRecovery] and record on the `terminal_output_overflow`
     * diagnostic, deciding what the handler does next.
     */
    private enum class OverflowRecoveryDecision {
        /** Reseed-and-reattach this pane (within the retry budget). */
        RESEED,

        /** Budget exhausted — fall to the actionable `surfaceError` card. */
        EXHAUSTED,

        /** A recovery is already running for this pane — drop the burst signal. */
        IN_FLIGHT,
    }

    /**
     * Issue #1205: a pane's delivery backlog (or the 2 MB seed gate) overflowed
     * under a sustained high-output burst. The KDoc on
     * [TmuxClient.outputBacklogOverflows] already prescribes the correct
     * recovery: this is a LOCAL rendering/ingestion backpressure signal, NOT a
     * transport disconnect, so the pane must recover by RESEEDING from
     * `capture-pane` — a transient burst costs one reseed, not the pane.
     *
     * Before #1205 the FIRST dropped frame cancelled the producer, detached the
     * pane, and latched `surfaceError` — a permanently dead pane the blank/stale
     * watchdog and heal oracle both early-return on, so nothing self-heals and
     * the user must tap "Recreate terminal". This routes both overflow classes
     * through the existing [reseedActivePaneForReattach]-family machinery
     * ([drainPaneOutputBacklog] → [healActivePaneIfStaleRender] →
     * [attachTerminalProducerForPane]) with a bounded retry budget
     * ([OVERFLOW_RECOVERY_MAX_ATTEMPTS] within [OVERFLOW_RECOVERY_WINDOW_MS]) so
     * a still-saturated channel can't loop into a reseed storm — after the
     * budget the pane lands on the same `surfaceError` card as a LAST resort.
     */
    private fun beginPaneOverflowRecovery(paneId: String): OverflowRecoveryDecision {
        // De-dup a burst: a single overflow fires the signal once per DROPPED
        // frame. If a recovery is already running for this pane, drop the
        // duplicate. The running job clears the flag on completion; only then can
        // a genuinely-new overflow re-trigger and be counted against the budget,
        // so a still-in-flight recovery can never be pre-empted into the card.
        if (paneId in paneOverflowRecoveryInFlight) return OverflowRecoveryDecision.IN_FLIGHT
        val now = SystemClock.elapsedRealtime()
        val attempts = paneOverflowRecoveryTimestamps.getOrPut(paneId) { ArrayDeque() }
        val recent = synchronized(attempts) {
            while (attempts.isNotEmpty() && now - attempts.first() > OVERFLOW_RECOVERY_WINDOW_MS) {
                attempts.removeFirst()
            }
            attempts.size
        }
        if (recent >= OVERFLOW_RECOVERY_MAX_ATTEMPTS) return OverflowRecoveryDecision.EXHAUSTED
        // Reserve the in-flight slot atomically so a concurrent burst signal
        // (the seed-gate path can fire from a producer-feed thread) can't also
        // start a second recovery for the same pane.
        if (!paneOverflowRecoveryInFlight.add(paneId)) return OverflowRecoveryDecision.IN_FLIGHT
        synchronized(attempts) { attempts.addLast(now) }
        return OverflowRecoveryDecision.RESEED
    }

    private fun handleTerminalOutputBacklogOverflow(overflow: TmuxOutputBacklogOverflow) {
        val existing = paneRows[overflow.paneId] ?: return
        if (existing.surfaceError) return
        val decision = beginPaneOverflowRecovery(overflow.paneId)
        Log.w(
            ISSUE_145_RECONNECT_TAG,
            "tmux-terminal-output-backlog-overflow pane=${overflow.paneId} " +
                "droppedEvents=${overflow.droppedEvents} status=${_connectionStatus.value} " +
                "recovery=${decision.name.lowercase()}",
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
            // Issue #1205: the recovery outcome DECISION on the existing event —
            // reseed (auto-heal), exhausted (fell to the card), or in_flight
            // (deduped burst). The async outcome lands on
            // `terminal_output_overflow_recovery`.
            "recovery" to decision.name.lowercase(),
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
        dispatchPaneOverflowRecovery(overflow.paneId, source = "pane_output_backlog", decision = decision)
    }

    private fun handleTerminalSeedGateOverflow(
        paneId: String,
        overflow: TerminalSeedGateOverflowException,
    ) {
        val existing = paneRows[paneId] ?: return
        if (existing.surfaceError) return
        val decision = beginPaneOverflowRecovery(paneId)
        Log.w(
            ISSUE_145_RECONNECT_TAG,
            "tmux-terminal-seed-gate-overflow pane=$paneId " +
                "pendingBytes=${overflow.pendingBytes} incomingBytes=${overflow.incomingBytes} " +
                "maxBytes=${overflow.maxBytes} status=${_connectionStatus.value} " +
                "recovery=${decision.name.lowercase()}",
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
            "recovery" to decision.name.lowercase(),
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
        dispatchPaneOverflowRecovery(paneId, source = "seed_gate_live_buffer", decision = decision)
    }

    private fun dispatchPaneOverflowRecovery(
        paneId: String,
        source: String,
        decision: OverflowRecoveryDecision,
    ) {
        when (decision) {
            OverflowRecoveryDecision.RESEED -> launchPaneOverflowReseed(paneId, source)
            OverflowRecoveryDecision.EXHAUSTED ->
                latchPaneSurfaceError(paneId, source, outcome = "surface_error_retry_exhausted")
            // A recovery for this pane is already running; the burst signal is a
            // duplicate — drop it (the in-flight job will finish the reseed).
            OverflowRecoveryDecision.IN_FLIGHT -> Unit
        }
    }

    /**
     * Issue #1205: reseed-and-reattach the overflowed pane through the existing
     * chokepoint machinery — drain the stale burst backlog, recapture the
     * authoritative server-side grid, and reattach a fresh producer — SILENTLY,
     * with NO user action and WITHOUT touching the SSH/tmux transport (this is
     * local renderer backpressure, not a disconnect). Mirrors
     * [rebindVisiblePaneProducersToClient]'s producer teardown+reattach so a
     * duplicate producer/input drain is never left behind. Releases the in-flight
     * slot in a `finally` so the retry budget can re-arm.
     */
    private fun launchPaneOverflowReseed(paneId: String, source: String) {
        val client = clientRef
        val guard = client?.let { currentRuntimeGuardForClient(it) }
        if (client == null || client.disconnected.value || guard == null) {
            // Nothing live to reseed from (dropped / reconnecting). Fall to the
            // actionable card and release the slot so a later live overflow can
            // recover.
            paneOverflowRecoveryInFlight.remove(paneId)
            latchPaneSurfaceError(paneId, source, outcome = "surface_error_no_live_client")
            return
        }
        bridgeScope.launch {
            var reseeded = false
            var drainedFrames = 0
            var reattached = false
            // Issue #1297: FREEZE %output delivery across the whole producer swap.
            // The teardown (step 1) tears the sole pane collector down and step 3
            // reattaches a fresh one; every %output emitted in that gap used to be
            // emitted into the zero-subscriber (replay = 0) pipe and vanish, so
            // recovery leaned SOLELY on the step-4 capture (the same SPOF this
            // issue closes). With delivery paused the frames are HELD in the
            // bounded backlog: on a successful capture the snapshot is
            // authoritative and the (pre-capture, now-stale) held frames are
            // dropped so they can't double-apply; on a FAILED capture they are
            // replayed to the fresh producer on resume instead of being lost.
            runCatching { client.pauseOutputDelivery(paneId) }
            try {
                val pane = paneRows[paneId] ?: return@launch
                // 1. Detach the current (dropped / feed-failed) producer + its
                //    output-activity, port-detector, and input drains — the same
                //    teardown [rebindVisiblePaneProducersToClient] does before a
                //    clean reattach, so nothing is left double-bound.
                paneProducerJobs.remove(paneId)?.cancel()
                paneProducerClients.remove(paneId)
                paneOutputActivityJobs.remove(paneId)?.cancel()
                panePortDetectorJobs.remove(paneId)?.cancel()
                panePortDetectorClients.remove(paneId)
                panePortDetectorGenerations.remove(paneId)
                paneInputJobs.remove(paneId)?.cancel()
                paneInputQueues.remove(paneId)?.close()
                runCatching { pane.terminalState.detachExternalProducer() }
                // 2. Drain the stale burst frames still queued in the pane's
                //    channel so they can't replay to the fresh producer and
                //    double-apply on top of the capture-pane snapshot.
                drainedFrames = runCatching { client.drainPaneOutputBacklog(paneId) }.getOrDefault(0)
                if (!isCurrentRuntime(guard) || client.disconnected.value) return@launch
                // 3. Reattach a FRESH producer (new bridge + emulator, seed gate
                //    CLOSED via awaitSeed) BEFORE reseeding: the seed must land on
                //    the SAME bridge the live producer feeds. Delivery is still
                //    PAUSED here, so the fresh collector receives nothing yet —
                //    the held frames wait for the capture outcome (step 4/5).
                attachTerminalProducerForPane(
                    paneId = paneId,
                    state = pane.terminalState,
                    client = client,
                )
                reattached = paneProducerJobs[paneId]?.isActive == true
                // 4. Reseed from tmux's authoritative server-side grid onto the
                //    fresh bridge; [appendRemoteOutput] OPENS the seed gate so the
                //    buffered live deltas flush in order after the snapshot. The
                //    non-destructive swap keeps the last good frame if the capture
                //    is momentarily near-blank.
                reseeded = healActivePaneIfStaleRender(
                    client, pane, guard, force = true, recordMilestone = false,
                ) == HealOutcome.Healed
                if (reseeded) {
                    // Issue #1297: the snapshot is authoritative — the held frames
                    // are all PRE-capture (already reflected server-side in the
                    // snapshot), so DROP them before the resume so they cannot
                    // double-apply on top of the fresh grid.
                    // Issue #1305: use the AUTHORITATIVE post-capture drain so it
                    // also discards the frame parked in the drain loop's local at
                    // the pause boundary (not reachable by the channel drain). The
                    // pre-capture drain in step 2 must NOT arm that discard, so on
                    // a capture FAILURE (no reseed → this branch is skipped) the
                    // parked frame still REPLAYS on resume (the #1297 guarantee).
                    drainedFrames +=
                        runCatching { client.drainPaneOutputBacklogAfterCapture(paneId) }.getOrDefault(0)
                }
                // 5. If no snapshot ever landed (all retries near-blank/errored),
                //    still OPEN the gate so buffered live output is flushed rather
                //    than swallowed — the same fallback the cold-open seed uses
                //    ([seedPrewarmedPane]/preload). Otherwise the reattached
                //    producer would be gated forever. On resume (finally) the held
                //    frames then replay to the fresh producer through this open
                //    gate — the #1297 belt so recovery isn't solely the capture.
                if (!reseeded && isCurrentRuntime(guard)) {
                    runCatching { pane.terminalState.openSeedGateWithoutSeed() }
                }
            } finally {
                // Issue #1297: THAW delivery last. On success the backlog was just
                // drained (nothing stale replays); on failure the held frames now
                // replay to the fresh producer through the opened gate. Always
                // resume so a pane is never left frozen, even on an early return.
                runCatching { client.resumeOutputDelivery(paneId) }
                paneOverflowRecoveryInFlight.remove(paneId)
                DiagnosticEvents.record(
                    "connection",
                    "terminal_output_overflow_recovery",
                    "pane" to paneId,
                    "source" to source,
                    "outcome" to when {
                        reseeded && reattached -> "reseeded_and_reattached"
                        reattached -> "reattached_gate_opened"
                        else -> "reseed_declined"
                    },
                    "reattached" to reattached,
                    "drainedFrames" to drainedFrames,
                    "generation" to connectGeneration,
                    "status" to _connectionStatus.value.javaClass.simpleName,
                )
            }
        }
    }

    /**
     * Issue #1205: the LAST-RESORT actionable-error card — the pre-#1205
     * latch-first behavior, now reached ONLY after the bounded reseed retry
     * budget is exhausted (or there is no live client to reseed from). Tears the
     * pane's producer/input drains down and flips it to `surfaceError` so the
     * user recovers via "Recreate terminal".
     */
    private fun latchPaneSurfaceError(paneId: String, source: String, outcome: String) {
        val existing = paneRows[paneId] ?: return
        if (existing.surfaceError) return
        paneProducerJobs.remove(paneId)?.cancel()
        paneProducerClients.remove(paneId) // Issue #959
        paneOutputActivityJobs.remove(paneId)?.cancel()
        panePortDetectorJobs.remove(paneId)?.cancel()
        panePortDetectorClients.remove(paneId)
        panePortDetectorGenerations.remove(paneId)
        paneInputJobs.remove(paneId)?.cancel()
        paneInputQueues.remove(paneId)?.close()
        runCatching { existing.terminalState.detachExternalProducer() }

        val errored = existing.copy(surfaceError = true)
        paneRows[paneId] = errored
        _panes.update { rows ->
            rows.map { row -> if (row.paneId == paneId) errored else row }
        }
        // Issue #1205: the terminal PAGER renders `unifiedPanes`, NOT `_panes`
        // directly, so the flipped-to-`surfaceError` row must be republished into
        // the unified list or the user-visible "Recreate terminal" give-up card
        // never appears — the pane just sits frozen with no recovery affordance.
        // (An on-device exhaustion journey caught this; the JVM proof only asserts
        // the `_panes` state, which the pager does not read.)
        rebuildUnifiedPanes()
        DiagnosticEvents.record(
            "connection",
            "terminal_output_overflow_recovery",
            "pane" to paneId,
            "source" to source,
            "outcome" to outcome,
            "generation" to connectGeneration,
            "status" to _connectionStatus.value.javaClass.simpleName,
        )
    }

    // Issue #1205 (test seam): drive the production seed-gate overflow handler
    // directly. The backlog-overflow class is driven end-to-end through the real
    // `outputBacklogOverflows` collector by a test emitting an overflow event;
    // the seed-gate class originates from the terminal bridge's
    // `onTerminalFeedFailure` callback, which a JVM test cannot raise without a
    // real 2 MB feed, so this seam calls the SAME private handler the callback
    // does. No production logic added.
    internal fun handleTerminalSeedGateOverflowForTest(
        paneId: String,
        overflow: TerminalSeedGateOverflowException,
    ) {
        handleTerminalSeedGateOverflow(paneId, overflow)
    }

    // Issue #1205 (connected-journey test seam): trip the LIVE-output backlog
    // overflow class the maintainer reported. A real 4096-deep `Channel` overflow
    // needs a sustained burst outrunning the frame-budgeted drain — timing-
    // dependent and flaky to force deterministically on the emulator (the #780
    // reason to inject the failing state synthetically). This calls the SAME
    // private handler the real `outputBacklogOverflows` collector calls
    // ([handleTerminalOutputBacklogOverflow], wired at the `outputOverflowJob`
    // collect), so everything downstream — `beginPaneOverflowRecovery`, the
    // bounded-retry budget, `launchPaneOverflowReseed`'s drain → capture-pane
    // reseed → producer reattach — runs on the REAL on-device path against the
    // REAL client/transport. Only the trigger is synthetic. No production logic
    // added; symmetric with [handleTerminalSeedGateOverflowForTest].
    internal fun handleTerminalOutputBacklogOverflowForTest(
        paneId: String,
        droppedEvents: Int,
    ) {
        handleTerminalOutputBacklogOverflow(
            com.pocketshell.core.tmux.TmuxOutputBacklogOverflow(
                paneId = paneId,
                droppedEvents = droppedEvents,
            ),
        )
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
        paneProducerClients.remove(paneId)
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
        paneOverflowRecoveryTimestamps.remove(paneId)
        paneOverflowRecoveryInFlight.remove(paneId)
        paneProducerJobs.remove(paneId)?.cancel()
        paneProducerClients.remove(paneId)
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
     * Issue #1295 (test seam): expose the production [isCurrentRuntime] predicate so a JVM
     * test can assert, after a GENUINE session switch (supersede), that a runtime guard
     * captured before the switch is no longer current (its generation/client/session was
     * superseded) — the exact predicate every arm/heal call site gates on. Test-only.
     */
    @androidx.annotation.VisibleForTesting
    internal fun isCurrentRuntimeForTest(guard: RuntimeRefreshGuard): Boolean =
        isCurrentRuntime(guard)

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
     * Issue #989 (test seam): drive [reseedActivePaneForReattach] directly against
     * the supplied guard (typically [currentRuntimeGuardForTest]) — the SAME
     * chokepoint the within-grace foreground reattach, the no-op-resize heal, and
     * the reflow completion use. Lets a JVM test prove the attach/return reseed
     * forces a repaint and is non-destructive without driving the whole
     * background→foreground lifecycle. A no-op when the guard is null. Passthrough,
     * no production logic.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun reseedActivePaneForReattachForTest(guard: RuntimeRefreshGuard?) {
        val refreshGuard = guard ?: return
        reseedActivePaneForReattach(refreshGuard, skipWhenFreshlySeeded = false)
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
     * Issue #941 (black-screen B1) test seam: invoke the production switch-reveal
     * gate [awaitActivePaneSeededOrLoading] directly against the supplied client,
     * building a runtime guard for the current generation/target. A passthrough —
     * no test-only behavior. Lets a JVM regression test prove the gate heals a
     * PARTIAL-black active pane (the switch-to-black symptom) without driving the
     * full connect coroutine state machine. Returns the gate's reveal decision.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun awaitActivePaneSeededOrLoadingForTest(client: TmuxClient): Boolean {
        val target = activeTarget
        val guard = if (target != null) {
            RuntimeRefreshGuard(
                generation = connectGeneration,
                target = target,
                client = client,
            )
        } else {
            null
        }
        return awaitActivePaneSeededOrLoading(guard)
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
     * Issue #1295 (test seam): drop the inline [ConnectionState] to a TRANSIENT
     * `Reconnecting` band while KEEPING the runtime (`activeTarget`/`clientRef` intact),
     * so a JVM test can drive the disconnect-recovery blank-watchdog case where the band
     * is still settling on a live client. Sets `_connectionState` directly (no controller
     * drive) so `inlineConnectionStatus` reads not-Connected on the next watchdog tick.
     */
    @androidx.annotation.VisibleForTesting
    internal fun forceInlineReconnectingBandForTest() {
        val target = activeTarget ?: return
        _connectionState = ConnectionState.Reconnecting(
            host = target.host,
            port = target.port,
            user = target.user,
            attempt = 1,
            maxAttempts = 3,
            retryDelayMs = 1_000L,
            reason = "test transient band (#1295)",
        )
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
            } else if (
                healActivePaneIfStaleRender(
                    client, activePane, refreshGuard, force = true, recordMilestone = true,
                ) != HealOutcome.Healed
            ) {
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
                    healActivePaneIfStaleRender(
                        client, pane, refreshGuard, force = true, recordMilestone = false,
                    ) == HealOutcome.Healed
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
            // The forced reseed feeds the snapshot through
            // [TerminalSurfaceState.appendRemoteOutput], which is safe to call
            // on an already-open gate (it is a feed + an open no-op), so a pane
            // that was already seeded as "new" simply gets its current frame
            // re-painted in place.
            healActivePaneIfStaleRender(client, pane, refreshGuard, force = true, recordMilestone = false)
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
            healActivePaneIfStaleRender(client, pane, refreshGuard, force = true, recordMilestone = false)
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
        var partialBlankHealed = false
        while (true) {
            if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return true
            if (client.disconnected.value) return true
            val activePane = activeVisiblePane() ?: return true
            val blank = activePane.terminalState.visibleScreenIsBlank()
            val partialBlank = activePane.terminalState.visibleScreenIsPartiallyBlank()
            if (!blank && !partialBlank) {
                // Issue #1176 (GAP C): a >3-line half-black BAND (the GAP-A dead-zone) reads
                // NON-blank AND NON-partial-black locally, yet it lost most of the frame tmux
                // still holds. The pre-#1176 gate revealed it UNHEALED — the black band only
                // recovered ~4s later at the steady watchdog (or never, in the dead-zone). Route
                // the reveal through the unified capture-diff oracle so a fast switch reveals a
                // HEALED pane. Cheap local pre-check first (no capture on a confidently-full
                // pane); [healActivePaneIfStaleRender] then confirms against tmux's authoritative
                // capture with [TerminalSurfaceState.visibleRenderLostFrameVsCapture] and heals
                // ONLY if the frame is truly lost — bounded to ONE capture, no reveal thrash.
                if (activePane.terminalState.renderLooksSuspect()) {
                    healActivePaneIfStaleRender(client, activePane, refreshGuard)
                }
                return true
            }
            // Issue #941 (black-screen B1): a plain session switch can reveal a
            // PARTIAL-black pane (one live line, the rest of the prior viewport
            // gone). That reads "not blank", so the pre-#941 gate passed it and the
            // partial-black pane was revealed as Connected and never reseeded (the
            // maintainer's "switched and it was black" symptom). Heal it here too —
            // the forced reseed does a full clear+repaint, so it restores the
            // FULL viewport from tmux's authoritative grid.
            //
            // Over-heal guard: a partial-black read is a HEURISTIC and a real
            // one-line prompt looks identical to "one live line, rest black". So
            // for partial-black we do EXACTLY ONE heal capture, then REVEAL — never
            // loop while it persists (a real prompt legitimately stays "partial",
            // and re-capturing it just re-paints the same content). Only a FULLY
            // blank pane keeps the bounded retry loop (a degraded link's empty
            // capture genuinely needs another attempt). This prevents reseed-thrash
            // and a spurious "Attaching…" overlay on a real blank prompt.
            if (!blank && partialBlank) {
                if (partialBlankHealed) return true
                Log.i(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-reveal-gate-active-pane-partial-blank pane=${activePane.paneId} " +
                        "session=${activeTarget?.sessionName} -> one heal capture then reveal",
                )
                healActivePaneIfStaleRender(
                    client = client,
                    pane = activePane,
                    refreshGuard = refreshGuard,
                    force = true,
                    recordMilestone = false,
                    maxAttempts = 1,
                )
                partialBlankHealed = true
                // Reveal regardless of the post-heal partial-black read (a real
                // small prompt is legitimately partial); the capture restored
                // whatever tmux's grid holds.
                return true
            }
            // Active pane is FULLY blank: try to (re-)seed it before revealing.
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-reveal-gate-active-pane-blank pane=${activePane.paneId} " +
                    "attempt=$attempt session=${activeTarget?.sessionName}",
            )
            healActivePaneIfStaleRender(
                client = client,
                pane = activePane,
                refreshGuard = refreshGuard,
                force = true,
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
                // Issue #1175: the single most user-visible black screen — the reveal
                // gate exhausted its bounded reseed retries and the active pane is STILL
                // blank as it hands off to the blank watchdog. Previously logcat-only
                // (unretrievable on-device); fingerprint it into the exportable JSONL.
                // This rides the existing reveal-gate path — no new poll.
                recordBlackFrameObserved(
                    activePane,
                    BLACK_FRAME_CLASS_REVEAL_GATE_GAVE_UP,
                    captureText = null,
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
                if (inlineConnectionStatus !is ConnectionStatus.Connected) {
                    // Issue #1295 (disconnect-recovery re-arm gap): a TRANSIENT reconnecting
                    // band during the blank window (a disconnect-recovery still settling on a
                    // still-live client + current runtime) must NOT strand the recovered
                    // runtime watchdog-less. On the silent-reattach / reconnect nets
                    // (surfaceErrorOnExhaustion=false) hand off the LIFETIME stale-render
                    // watchdog before exiting — it itself tolerates the transient band (it
                    // continues, healing once Connected resumes), so an idle pane that later
                    // diverges is still caught. Before #1295 this bare-exited, leaving the
                    // recovered runtime with NO post-reveal net (permanent black on a live
                    // transport). The cold/switch ATTACH reveal (surfaceErrorOnExhaustion=true)
                    // keeps its bare exit: its still-Seeding reveal is owned by the reveal gate,
                    // not this net, and a not-Connected there is a genuine attach failure.
                    if (!surfaceErrorOnExhaustion) armActivePaneStaleRenderWatchdog(refreshGuard)
                    return@launch
                }
                val activePane = activeVisiblePane()
                if (activePane == null || !activePane.terminalState.visibleScreenIsBlank()) {
                    // A frame landed (seed or live %output) — drop the loading
                    // overlay and let the Connected surface show the content.
                    //
                    // Issue #941 (black-screen B1): the watchdog INTENTIONALLY stays
                    // FULLY-blank-only here. A partial-black active pane (one live
                    // line) is healed by the switch-reveal gate BEFORE this watchdog
                    // is even armed ([awaitActivePaneSeededOrLoading]), and a
                    // send-overpaint partial-black is healed by the post-send heal
                    // ([requestReconcile] with [ReconcileReason.Send]). Extending this post-reveal net to
                    // partial-black would over-heal a legitimately small single-line
                    // pane (a real one-line prompt that landed after reveal reads
                    // partial-black), reseed-thrashing it on every arming — so the
                    // watchdog keeps its narrow fully-blank contract.
                    // Issue #1326: the frame recovered — promote the reveal machine
                    // to Live for the active target so the surface reveals (this
                    // replaces the deleted switch-hides-terminal clear; the reveal
                    // machine is now the single hold authority).
                    promoteRevealLiveForActiveTarget()
                    // Issue #973: the pane that was blank AT reveal has now
                    // produced a frame, so hand off the #966 lifetime net here —
                    // the reveal sites skip arming the stale-render watchdog while
                    // the pane is still blank (it would race this blank watchdog on
                    // the same pane). Now that a frame landed, arm it so a LATER
                    // stale render on this runtime is still healed.
                    armActivePaneStaleRenderWatchdog(refreshGuard)
                    return@launch
                }
                Log.i(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-connected-blank-watchdog tick=$tick pane=${activePane.paneId} " +
                        "session=${activeTarget?.sessionName} -> re-seeding blank visible panes",
                )
                reseedBlankVisiblePanes(refreshGuard)
                if (!activePane.terminalState.visibleScreenIsBlank()) {
                    // Issue #1326: reveal via the single reveal-machine authority.
                    promoteRevealLiveForActiveTarget()
                    // Issue #973: frame landed after a reseed — hand off the #966
                    // stale-render lifetime net (see the early-exit case above).
                    armActivePaneStaleRenderWatchdog(refreshGuard)
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
            // infinite spinner.
            if (!isCurrentRuntime(refreshGuard)) return@launch
            if (clientRef?.disconnected?.value == true) return@launch
            if (inlineConnectionStatus !is ConnectionStatus.Connected) return@launch
            if (!surfaceErrorOnExhaustion) {
                // Issue #1177 (black-screen GAP B): the passive/transport SILENT-
                // REATTACH paths (:8077/:8319) arm this watchdog with
                // surfaceErrorOnExhaustion=false. Before, when the reconnect seed
                // NEVER landed within the blank window this branch fell off the end
                // SILENTLY — leaving a PERMANENT BLACK pane on a LIVE (green)
                // transport with no error, no heal, no reconnect (the maintainer's
                // post_grace_foreground full-reconnect black, #874). The transport
                // IS healthy, so declaring a stuck attach (failStuckAttachReveal)
                // would be wrong; instead HAND OFF to the lifetime stale-render heal
                // watchdog so a seed that never landed keeps being re-captured and
                // healed against tmux's authoritative grid. A fully-black pane IS
                // caught by the heal oracle (visibleRenderLostFrameVsCapture), so
                // once tmux carries a frame the pane is repainted rather than
                // stranded black. This adds NO new polling (#1164): it arms the
                // EXISTING #966/#1166 stale-render watchdog on a path that
                // previously armed nothing on exhaustion.
                Log.i(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-connected-blank-watchdog exhausted on a reattach reseed " +
                        "(surfaceErrorOnExhaustion=false) with a still-blank active " +
                        "pane on a LIVE transport — arming the stale-render heal " +
                        "watchdog instead of exiting silently into a black pane " +
                        "(#1177). session=${activeTarget?.sessionName}",
                )
                armActivePaneStaleRenderWatchdog(refreshGuard)
                return@launch
            }
            val stillBlank = activeVisiblePane()?.terminalState?.visibleScreenIsBlank() ?: false
            if (stillBlank) {
                failStuckAttachReveal()
            }
        }
    }

    /**
     * Issue #966/#967 — the STEADY-STATE stale-render watchdog. The
     * [armConnectedBlankWatchdog] above exits the instant a frame lands, so it
     * cannot catch a pane that paints fine at attach and only LATER goes
     * black-with-fragments (a drop-induced stale grid, a mis-sized resize, a
     * partial reseed that stalled — the #966 shape). This watchdog runs for the
     * lifetime of the runtime on a SLOW cadence and, each tick, captures the
     * ACTIVE visible pane and re-seeds it ONLY when the local render is
     * mostly-black/stale relative to tmux's authoritative grid
     * ([healActivePaneIfStaleRender]).
     *
     * It is the residual-risk net the #967 spike called out: a render that dies
     * SILENTLY (no exception, just a stale grid) neither reconnects (correct — the
     * transport is alive) NOR is caught by the blank/partial-blank oracles. This
     * watchdog is the missing oracle, gated by a `capture-pane` DIFF so it never
     * over-fires on a legitimately sparse-but-correct pane.
     *
     * Cost: ONE `capture-pane` round-trip per tick, and only while the pane is
     * NON-blank (the blank watchdog owns the blank case). The diff is computed from
     * the captured text, so a healthy pane pays just the one cheap round-trip and
     * never relayouts.
     *
     * Issue #1166 (battery/heat) — the watchdog exists to heal the VISIBLE pane, so
     * it is gated + backed off so an idle/hidden pane stops hammering `capture-pane`
     * while a churning/agent pane still heals as fast as before:
     *
     *  - **Foreground + screen-on gate.** When the app is backgrounded OR the screen
     *    is off there is nothing on screen to heal, so the tick SKIPS the capture
     *    round-trip entirely (0 captures/min while backgrounded or screen-off). On
     *    resume the back-off is reset so the FIRST foreground tick captures at the
     *    hot 4s cadence — a pane that changed while away heals right on return.
     *  - **Back-off when HEALTHY (issue #1219 steady-heat fix).** A tick whose
     *    authoritative capture-diff oracle finds NO divergence widens the next
     *    interval (4s → 8s → 16s, capped) — EVEN while the pane is actively
     *    streaming `%output`. Only a real detected divergence (the heal fired), a
     *    session switch (fresh arm), or a reconnecting band snaps the cadence back
     *    to 4s. Before #1219 ANY streamed `%output` reset the back-off, so a
     *    continuously-streaming-but-CORRECT agent pane never left the hot 4s cadence
     *    and paid a `capture-pane` round-trip every 4s forever (the #1164 "runs
     *    warm" lever, the heaviest-use foreground window). A streaming-but-BLACK
     *    pane still heals within the same bound (#1138/#1153/#874) via the suspect
     *    wake below.
     *  - **Immediate wake on a SUSPECT redraw (issue #1166 heal-latency fix, #1219
     *    scoped).** The back-off is NOT purely poll-based: a fresh active-pane
     *    `%output` fires [staleRenderWatchdogWake] (see [recordVisiblePaneOutput])
     *    and the loop RACES its backed-off `delay(...)` against that wake (see
     *    [awaitStaleRenderWatchdogTick]) — but honors it ONLY while the render looks
     *    locally black/partial-black ([activeVisiblePaneRenderLooksSuspect]). So a
     *    redraw that turns the pane black ~1s into a 16s backed-off window is
     *    captured/diffed/healed within the hot bound (≤4s), not up to 16s later —
     *    the partial-black (#1138) case whose sole steady-state oracle is this
     *    watchdog can never sit visibly broken for a whole backed-off interval —
     *    while a healthy streaming pane's output is ignored so it reaps the back-off.
     *  - **Arm-dedup.** [staleRenderWatchdogJob] is cancelled before each re-arm so
     *    rapid A→B→A switching can never stack multiple concurrent 4s loops.
     */
    private fun armActivePaneStaleRenderWatchdog(refreshGuard: RuntimeRefreshGuard) {
        if (!staleRenderWatchdogAutoArmEnabled) return
        launchActivePaneStaleRenderWatchdog(refreshGuard)
    }

    private fun launchActivePaneStaleRenderWatchdog(refreshGuard: RuntimeRefreshGuard) {
        // Issue #1166 arm-dedup: cancel any prior loop before launching a new one.
        // A stale loop otherwise only self-terminates on its NEXT tick via
        // isCurrentRuntime (up to one full tick of double/triple captures per switch).
        staleRenderWatchdogJob?.cancel()
        staleRenderSparseWakeBaselines.clear()
        staleRenderWatchdogJob = bridgeScope.launch {
            // Issue #1495 (Part 2, #1517): ticks with nothing to heal; bounded so advanceUntilIdle can't spin forever.
            var idleTicks = 0
            // Issue #1166: consecutive stable ticks drive the back-off interval.
            var stableTicks = 0
            // Issue #1294: consecutive UNVERIFIED heal ticks (capture timeout/error/empty/
            // mutex-starved). Recorded into the exportable diagnostics so a shared log can
            // tell a genuinely-idle backed-off pane from a pane whose heal captures are
            // WEDGED while it is black. Reset on any confirming (HEALTHY/HEALED) tick.
            var unverifiedStreak = 0
            while (idleTicks < staleRenderWatchdogMaxTicks) {
                // Issue #1166: wait out the (possibly backed-off) interval, but a
                // fresh active-pane %output wake cuts a backed-off wait short so a
                // SUSPECT (locally black/partial-black) redraw is captured within the
                // hot bound, not the next long tick. Issue #1219: a HEALTHY streaming
                // pane's %output no longer cuts the wait short (see
                // [awaitStaleRenderWatchdogTick]), so a correctly-rendering agent pane
                // actually reaps the back-off instead of thrashing capture-pane at 4s.
                awaitStaleRenderWatchdogTick(stableTicks)
                if (!isCurrentRuntime(refreshGuard)) return@launch
                val client = clientRef ?: return@launch
                if (client.disconnected.value) return@launch
                if (inlineConnectionStatus !is ConnectionStatus.Connected) {
                    // Paused mid-runtime (a transient reconnecting band): keep the
                    // watchdog alive but skip the heal until Connected resumes. Reset
                    // the back-off so the resume tick captures at the hot cadence.
                    stableTicks = 0
                    unverifiedStreak = 0
                    staleRenderSparseWakeBaselines.clear()
                    idleTicks += 1
                    continue
                }
                // Issue #1166 foreground + screen-on gate: nothing to heal when the
                // pane is not on screen. Skip the capture round-trip entirely and
                // reset the back-off so the first tick after resume captures hot.
                if (!shouldRunStaleRenderWatchdogCapture()) {
                    stableTicks = 0
                    unverifiedStreak = 0
                    staleRenderSparseWakeBaselines.clear()
                    idleTicks += 1
                    continue
                }
                // Issue #1158: the alt-buffer agent latch is driven from the
                // SERVER-TRUTH `#{alternate_on}` read in the pane reconcile
                // ([latchAltBufferAgentsFromParsed]), NOT the inert CLIENT emulator
                // — the `-CC` capture-pane seed replays screen TEXT onto the
                // client's MAIN buffer, so the client never sees the alt buffer for
                // an idle agent. No watchdog-tick emulator read is needed here.
                val activePane = activeVisiblePane()
                if (activePane != null) {
                    // Issue #1295 (deliverable 1) — the POSITIVE watchdog-liveness heartbeat.
                    // We have passed every gate above, so at THIS point the watchdog is proven
                    // ARMED + running, the runtime is CURRENT + live-attached (isCurrentRuntime),
                    // the app is FOREGROUNDED + screen-on (shouldRunStaleRenderWatchdogCapture),
                    // and a visible pane is on screen. Fingerprint that fact into the exportable
                    // ring BEFORE the heal capture (so the heartbeat lands even when the capture
                    // is UNVERIFIED). Its ABSENCE alongside foreground+live evidence is the
                    // positive signature of the #1295 unarmed-watchdog bug.
                    recordWatchdogLiveness(
                        pane = activePane,
                        refreshGuard = refreshGuard,
                        tick = idleTicks,
                        backedOff = stableTicks > 0,
                    )
                    // Issue #1494 — bound the per-tick heal so one hung capture can't freeze the loop.
                    val outcome = runCatching {
                        withTimeoutOrNull(RENDER_HEAL_WATCHDOG_TICK_TIMEOUT_MS) {
                            healActivePaneIfStaleRender(client, activePane, refreshGuard)
                        } ?: HealOutcome.Unverified
                    }.getOrDefault(HealOutcome.Unverified)
                    // Issue #1294 (three-state scoring — the load-bearing fix): score the tick by
                    // WHICH oracle outcome it was, never conflating a capture FAILURE with healthy.
                    //
                    //  - HEALTHY: the capture CONFIRMED the render matches tmux. This — and ONLY
                    //    this — earns the #1219 steady-heat back-off (4s->8s->16s), even while the
                    //    pane streams %output (the #1164 "runs warm" lever). Before #1294 a FAILURE
                    //    was scored identically, throttling to 16s while the pane was black.
                    //  - HEALED: the oracle found + repaired a real divergence (black / partial-
                    //    black / stale render vs tmux's grid). Snap the cadence back to hot so a
                    //    just-blacked pane is re-checked at 4s (#1138/#1153 heal preservation).
                    //  - UNVERIFIED: the capture could NOT confirm health (timeout / error /
                    //    empty-on-live-transport / mutex-starved / #1494 tick-timeout). Keep the HOT
                    //    cadence — never throttle — so a black pane keeps retrying at 4s. Record the
                    //    streak into the exportable diagnostics (#1175) so it tells "blind" from "idle".
                    when (outcome) {
                        HealOutcome.Healthy -> {
                            stableTicks += 1
                            unverifiedStreak = 0
                            staleRenderSparseWakeBaselines.rememberIfHealthySparse(activePane)
                        }
                        HealOutcome.Healed -> {
                            stableTicks = 0
                            unverifiedStreak = 0
                            staleRenderSparseWakeBaselines.remove(activePane)
                        }
                        HealOutcome.Unverified -> {
                            stableTicks = 0
                            unverifiedStreak += 1
                            staleRenderSparseWakeBaselines.remove(activePane)
                            recordHealCaptureUnverified(activePane, unverifiedStreak)
                        }
                    }
                    // #1495 Part 2: only steadily-HEALTHY ticks count toward the bounded exit.
                    idleTicks = if (outcome == HealOutcome.Healthy) idleTicks + 1 else 0
                }
                if (activePane == null) idleTicks += 1 // fg + screen-on but no visible pane
            }
        }
    }

    /**
     * Issue #1166 (heal-latency fix) + issue #1219 (steady-heat fix): wait out the
     * watchdog interval for the current run of [stableTicks] stable ticks, but make
     * the wait WAKEABLE while backed off — ONLY for a wake that arrives while the
     * active pane's local render currently looks SUSPECT (black / partial-black).
     * Returns `true` when a suspect-pane `%output` wake cut the wait short (the
     * caller then re-checks the pane immediately so a black redraw heals within the
     * hot bound), `false` when the full interval simply elapsed.
     *
     * At the HOT cadence (interval == [STALE_RENDER_WATCHDOG_TICK_MS]) there is
     * nothing to snap back to, so it drains any stale wake and plain-delays — the
     * churning-pane behavior is unchanged (still a capture every 4s, no extra
     * captures from output bursts).
     *
     * Only a BACKED-OFF wait (8s/16s) races the delay against a wake:
     *  - Issue #1166 (heal latency): a redraw on a locally-SUSPECT pane wakes at
     *    once, so a black / partial-black frame (#1138/#966/#928) heals within the
     *    hot bound instead of being deferred up to a whole backed-off interval.
     *  - Issue #1219 (steady heat): a redraw on a HEALTHY (fully-rendered) pane is
     *    IGNORED — the loop keeps waiting out the interval — so a continuously-
     *    streaming-but-correct agent pane actually reaps the back-off instead of
     *    being snapped hot on every %output. The authoritative capture-diff oracle
     *    still runs when the interval elapses, catching any divergence a purely-
     *    local check would miss; it just no longer fires every 4s on a hot pane.
     */
    private suspend fun awaitStaleRenderWatchdogTick(stableTicks: Int): Boolean {
        val interval = staleRenderWatchdogIntervalMs(stableTicks)
        if (interval <= STALE_RENDER_WATCHDOG_TICK_MS) {
            // Hot cadence: drain a possibly-stale wake so it can't fire the FIRST
            // backed-off wait spuriously, then plain-delay the hot interval.
            staleRenderWatchdogWake.tryReceive()
            delay(interval)
            return false
        }
        // Backed off: race the long delay against a SUSPECT wake. A healthy pane's
        // wakes are consumed and ignored (keep waiting); the first wake that lands
        // while the render looks locally black/partial-black cuts the wait short so
        // the black redraw heals hot. If the interval elapses first, return false.
        return withTimeoutOrNull(interval) {
            while (true) {
                staleRenderWatchdogWake.receive()
                if (activeVisiblePaneRenderLooksSuspect()) break
            }
            true
        } ?: false
    }

    /**
     * Issue #1219: a cheap, IO-free local read of whether the active visible pane's
     * render currently looks like it may have LOST tmux's frame (black / partial-black
     * / scattered-fragments-over-black / surface-black). Used to decide whether a
     * `%output` wake should cut a backed-off watchdog wait short: a SUSPECT pane wakes
     * immediately so a black redraw heals within the hot bound (#1138/#966/#1214),
     * while a confidently-dense HEALTHY streaming pane's output is ignored so it reaps
     * the back-off (the #1164 steady-heat lever).
     *
     * It reuses [TerminalSurfaceState.renderLooksSuspect] — the SAME cheap
     * local pre-check the switch-reveal / no-op-resize heals (#1176/#1214) already run
     * to decide whether an authoritative `capture-pane` diff is worthwhile — so this
     * wake gate covers the whole fragments-over-black class (fully blank, ≤3-line
     * partial-black, the #1176 dead-zone band, the #1214 mostly-empty model), NOT just
     * the ≤3-line partial-black. It ORs the #1192 surface-black-model-intact class,
     * which has a full model the frame predicate cannot see. Both are pure model/surface
     * reads — no seed, no `capture-pane`. When wrong-positive the honored wake just runs
     * the real capture-diff oracle a little sooner (a no-op heal), never a wrong heal.
     */
    private fun activeVisiblePaneRenderLooksSuspect(): Boolean {
        return activeVisiblePane()?.let(staleRenderSparseWakeBaselines::renderLooksSuspect) == true
    }

    /**
     * Issue #1166 + #1301: the continuous reconciler's interval for the given run of
     * consecutive HEALTHY (no-divergence) ticks. A verified-clean pane cools
     * 4s → 8s → 16s → 30s (capped) even while streaming; [stableTicks] is reset to 0 on
     * any real divergence / switch (issue #1219) or FAILED/UNVERIFIED verification (issue
     * #1294), snapping the next interval back to 4s. The 30s ceiling (#1301) roughly halves
     * the idle-pane steady-state capture rate vs the v0.4.23 16s ceiling (the #1164 lever).
     */
    @androidx.annotation.VisibleForTesting
    internal fun staleRenderWatchdogIntervalMs(stableTicks: Int): Long {
        if (stableTicks <= 0) return STALE_RENDER_WATCHDOG_TICK_MS
        val doublings = stableTicks.coerceAtMost(STALE_RENDER_WATCHDOG_BACKOFF_MAX_DOUBLINGS)
        return (STALE_RENDER_WATCHDOG_TICK_MS shl doublings)
            .coerceAtMost(STALE_RENDER_WATCHDOG_MAX_INTERVAL_MS)
    }

    /**
     * Issue #1166: the watchdog heals the VISIBLE pane, so it only pays the
     * `capture-pane` round-trip while the app is foregrounded AND the screen is
     * interactive (on). A backgrounded/screen-off stale render is invisible and is
     * re-healed on the next foreground+screen-on tick anyway.
     */
    private fun shouldRunStaleRenderWatchdogCapture(): Boolean =
        isProcessForegroundForCleared() && isScreenInteractive()

    /**
     * Issue #1166: true when the device screen is interactive (on). Reads the real
     * [PowerManager.isInteractive]; a test override or a missing context (JVM unit
     * test) defaults to "on" so non-gating tests keep their behavior.
     */
    private fun isScreenInteractive(): Boolean {
        screenInteractiveOverrideForTest?.let { return it }
        val ctx = applicationContext ?: return true
        return runCatching {
            (ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive ?: true
        }.getOrDefault(true)
    }

    /**
     * Issue #1166 test seam: drive the stale-render watchdog loop directly (bypasses
     * the [staleRenderWatchdogAutoArmEnabled] auto-arm suppression a test sets to
     * stop `connect()` from arming its own loop), so a JVM test can exercise the
     * foreground/screen gate + back-off + arm-dedup deterministically.
     */
    @androidx.annotation.VisibleForTesting
    internal fun armActivePaneStaleRenderWatchdogForTest(refreshGuard: RuntimeRefreshGuard) {
        launchActivePaneStaleRenderWatchdog(refreshGuard)
    }

    /**
     * Issue #966/#967 (characterization test seam): run ONE stale-render heal pass
     * over the active visible pane against the supplied guard. A passthrough — no
     * production-only logic — so a connected test can drive the heal deterministically
     * without waiting on the watchdog cadence.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun healActivePaneIfStaleRenderForTest(): HealOutcome {
        val client = clientRef ?: return HealOutcome.Unverified
        val activePane = activeVisiblePane() ?: return HealOutcome.Unverified
        val target = activeTarget ?: return HealOutcome.Unverified
        val guard = RuntimeRefreshGuard(
            generation = connectGeneration,
            target = target,
            client = client,
        )
        return healActivePaneIfStaleRender(client, activePane, guard)
    }

    /** Issue #1353 R4 (test seam): submit a reconcile event via [requestReconcile] on the live client. */
    internal fun requestReconcileForTest(paneId: String, reason: ReconcileReason) {
        val client = clientRef ?: return
        requestReconcile(client, paneId, reason)
    }

    /** Issue #1353 R4 (test seam): the R3 per-pane single-flight owner the reconcile routes through. */
    internal fun renderHealCoordinatorForTest(): RenderHealCoordinator = renderHealCoordinator

    /**
     * Issue #966/#967 (test seam): is the underlying tmux control client currently
     * disconnected? The discriminating connected journey asserts this is FALSE while
     * the render is stale — proving the transport is alive (a render bug, not a drop).
     */
    @androidx.annotation.VisibleForTesting
    internal fun clientDisconnectedForTest(): Boolean = clientRef?.disconnected?.value ?: true

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
        // Preserve the target so Reconnect has something to re-dial, then drive
        // the honest-error surface via [ConnectionState.Unreachable] (the reveal
        // machine + [SessionSurfaceState] resolve the held surface to the calm
        // failure placeholder — no separate loading-overlay flag; #1326).
        connectingTarget = target
        refreshReconnectAvailability()
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
            healActivePaneIfStaleRender(client, pane, refreshGuard = null, force = true, recordMilestone = false)
        }
    }

    /**
     * Issue #966/#967 — the STALE-RENDER heal. The v0.4.17 black-screen heal only
     * engages on a FULLY-blank or ≤3-live-line pane, so the maintainer's #966 pane
     * — a connected Claude window rendering only a lone cursor + a couple of
     * scattered status fragments while tmux's grid holds the full TUI — reads
     * "not blank" and is SKIPPED, leaving a black-with-fragments pane on a LIVE
     * transport (the keepalive keeps succeeding; the render is just stale).
     *
     * This heal captures the active pane ONCE, diffs the authoritative capture
     * against what the local emulator actually rendered
     * ([TerminalSurfaceState.visibleScreenDivergesFromCapture]), and re-seeds the
     * pane ONLY when the render is mostly-black/stale relative to tmux. The diff
     * is what keeps a legitimately sparse-but-correct pane from over-firing: when
     * the render already matches tmux, the capture is applied as a no-op (the
     * existing full clear+repaint is idempotent — re-painting the SAME
     * authoritative content), but we skip even that to avoid a needless relayout.
     *
     * Returns true when a stale render was detected and re-seeded. The single
     * `capture-pane` round-trip is paid ONLY on the watchdog cadence (the caller
     * gates frequency), never per frame, so steady-state rendering is untouched.
     */
    /**
     * Issue #1175 — fingerprint a degenerate (black / partial-black) frame into the
     * EXPORTABLE diagnostics JSONL (the same ring the Settings → Diagnostics "Share
     * log" export reads), so a maintainer who hits a black screen can SHARE the log and
     * we can tell WHICH class of black screen it was.
     *
     * This is emitted ONLY from points the heal/reveal path ALREADY reaches on the
     * existing gated, backed-off stale-render watchdog tick and the connect/switch
     * reveal gate — it adds NO new timer/poll/coroutine loop (the deliberate contrast
     * with the #1164 heat regression). It rides the same `capture-pane` round-trip the
     * heal already pays; it never issues its own.
     *
     * Additive on the OBSERVE side: the successful-heal event (`stale_render_heal`)
     * still fires from [healActivePaneIfStaleRender]; this fingerprints the frame that
     * was observed degenerate regardless of whether a heal follows.
     *
     * The `class` discriminator is computed at each call site (deterministic per site,
     * so a unit test can drive every class); this builder just attaches the shared
     * geometry / lifecycle field set — all redacted by [DiagnosticRedactor] before it
     * reaches disk (only `session` is host-identifying, as it already is on
     * `stale_render_heal`).
     */
    private fun recordBlackFrameObserved(
        pane: TmuxPaneState,
        blackClass: String,
        captureText: String?,
        // Issue #1294: present only on the [BLACK_FRAME_CLASS_HEAL_CAPTURE_UNVERIFIED]
        // class — the count of consecutive UNVERIFIED heal ticks (capture timeout / error /
        // empty / mutex-starved) the watchdog has seen without a confirming capture. It lets
        // a shared-log export distinguish a genuinely-healthy pane that BACKED OFF (watchdog
        // throttled, unverifiedStreak absent) from a pane whose heal captures are WEDGED
        // (watchdog BLIND, unverifiedStreak climbing) — the exact #1294 mechanism.
        unverifiedStreak: Int? = null,
    ) {
        val nowMs = SystemClock.elapsedRealtime()
        val lastSeedAtMs = paneLastSeedAtMs[pane.paneId]
        val lastOutputAtMs = paneLastOutputAtMs[pane.paneId]
        val fields = buildBlackFrameObservedDiagnosticFields(
            pane = pane,
            blackClass = blackClass,
            sessionName = activeTarget?.sessionName,
            captureText = captureText,
            nowMs = nowMs,
            lastSeedAtMs = lastSeedAtMs,
            lastOutputAtMs = lastOutputAtMs,
            connectionStatusName = _connectionStatus.value::class.simpleName ?: "unknown",
            foreground = isProcessForegroundForCleared(),
            screenOn = isScreenInteractive(),
            unverifiedStreak = unverifiedStreak,
        )
        DiagnosticEvents.record("terminal", BLACK_FRAME_OBSERVED_EVENT, *fields)
    }

    /**
     * Issue #1294: record a watchdog tick whose authoritative `capture-pane` could NOT
     * confirm the render's health (a [HealOutcome.Unverified] outcome — capture timeout,
     * error response, empty-on-live-transport, or mutex-starved by a concurrent burst).
     * Rides the existing exportable `black_frame_observed` ring (#1175 conventions) under a
     * dedicated class + an `unverifiedStreak` field, so an export can tell an idle healthy
     * pane that legitimately backed off from a pane whose heal captures are WEDGED while it
     * is black (the #1294 "watchdog blind, not throttled" distinction). Emitted from the
     * watchdog tick that already paid the capture — no new poll/timer/round-trip.
     */
    private fun recordHealCaptureUnverified(pane: TmuxPaneState, unverifiedStreak: Int) {
        recordBlackFrameObserved(
            pane,
            BLACK_FRAME_CLASS_HEAL_CAPTURE_UNVERIFIED,
            captureText = null,
            unverifiedStreak = unverifiedStreak,
        )
    }

    /**
     * Issue #1295 — emit the POSITIVE watchdog-liveness heartbeat ([WATCHDOG_LIVENESS_EVENT]).
     * Called once per steady stale-render watchdog tick AFTER every gate has passed (runtime
     * current + foregrounded + screen-on + a visible pane), so the heartbeat certifies exactly
     * "an armed watchdog is ticking over a live-attached, foregrounded, visible pane". The
     * runtime identity is [refreshGuard]'s `generation` + the live client identity hash, so an
     * export can correlate the heartbeat to the same runtime the reconnect trail names, and its
     * ABSENCE (while the export otherwise shows foreground+live) convicts the unarmed-watchdog
     * bug. Rides the tick that already runs — no new poll/timer/round-trip.
     */
    private fun recordWatchdogLiveness(
        pane: TmuxPaneState,
        refreshGuard: RuntimeRefreshGuard,
        tick: Int,
        backedOff: Boolean,
    ) {
        val fields = buildWatchdogLivenessDiagnosticFields(
            pane = pane,
            sessionName = activeTarget?.sessionName,
            generation = refreshGuard.generation,
            clientHash = System.identityHashCode(refreshGuard.client),
            atMs = SystemClock.elapsedRealtime(),
            tick = tick,
            foreground = isProcessForegroundForCleared(),
            screenOn = isScreenInteractive(),
            backedOff = backedOff,
        )
        DiagnosticEvents.record(
            "terminal",
            WATCHDOG_LIVENESS_EVENT,
            *fields,
        )
    }

    private suspend fun healActivePaneIfStaleRender(
        client: TmuxClient,
        pane: TmuxPaneState,
        refreshGuard: RuntimeRefreshGuard?,
        force: Boolean = false,
        recordMilestone: Boolean = false,
        maxAttempts: Int = SEED_CAPTURE_EMPTY_RETRY_ATTEMPTS,
    ): HealOutcome =
        // Issue #1353 R3 — SINGLE-FLIGHT per pane. Serialize the whole close→capture→apply→open
        // seed-gate (M9) window so a sibling launcher (L1 send / L2 watchdog / L5 reveal) on the
        // SAME pane can't cross into it and open the gate early, clobbering a buffered newer
        // `%output` delta (spike §3 race). Keyed per pane, so different panes heal concurrently.
        // Issue #1494 — [withPaneHealLock] force-resets a lock wedged past HELD_TOO_LONG_MS.
        renderHealCoordinator.withPaneHealLock(pane.paneId) {
            healActivePaneIfStaleRenderLocked(
                client, pane, refreshGuard, force, recordMilestone, maxAttempts,
            )
        }

    private suspend fun healActivePaneIfStaleRenderLocked(
        client: TmuxClient,
        pane: TmuxPaneState,
        refreshGuard: RuntimeRefreshGuard?,
        // Issue #1353 R2 — the single reseed authority's UNCONDITIONAL (force) mode,
        // folded in from the deleted parallel `seedPaneFromCapture` sink (M4). A caller
        // that needs a GUARANTEED reseed (cold-open attach seed, blank-pane heal, reveal
        // handoff, reattach) passes `force = true`: the capture→[appendRemoteOutput] fires
        // WITHOUT consulting the #1300 divergence oracle. A non-forced call stays
        // oracle-gated (the M2 stale-render heal below). ONE capture→append authority.
        force: Boolean,
        // Force-mode only: warm-switch capture milestone (the cold-open active-pane seed).
        recordMilestone: Boolean,
        // Force-mode only: bound on the empty/near-blank capture retry loop (#693/#662).
        // Ignored on the oracle-gated (non-force) path, which pays exactly one capture.
        maxAttempts: Int,
    ): HealOutcome {
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return HealOutcome.Unverified
        if (client.disconnected.value) return HealOutcome.Unverified
        // Issue #1353 R2 — the UNCONDITIONAL reseed (seed mode), folded in from the old
        // `seedPaneFromCapture`: capture tmux's authoritative grid and apply it WITHOUT the
        // oracle gate, RETRYING a transiently empty/near-blank capture (bounded); the
        // non-destructive swap in [captureAndApplyPaneSnapshot] keeps the last good frame so a
        // momentary drop never repaints black. Returns [HealOutcome.Healed] when a snapshot
        // landed, [HealOutcome.Unverified] otherwise (a force caller reads `== Healed`).
        if (force) {
            // Issue #830: publish "a seed for this pane is in flight" BEFORE the first
            // round-trip so a concurrent reseed net (the pager-settle
            // [reseedVisiblePaneIfBlank]) dedups against the in-flight seed instead of
            // racing it into a second redundant `capture-pane`. Cleared in the finally.
            panesSeedInFlightThisAttach.add(pane.paneId)
            try {
                var attempt = 0
                while (true) {
                    if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) {
                        return HealOutcome.Unverified
                    }
                    if (client.disconnected.value) return HealOutcome.Unverified
                    if (captureAndApplyPaneSnapshot(client, pane, refreshGuard, recordMilestone)) {
                        return HealOutcome.Healed
                    }
                    attempt += 1
                    if (attempt >= maxAttempts) return HealOutcome.Unverified
                    // Short backoff so a flaky-link empty capture is re-tried after the
                    // channel has a moment to recover, without stalling a genuinely empty
                    // pane's reveal for long. The guard re-check at the top aborts
                    // immediately if the runtime was superseded mid-wait.
                    delay(SEED_CAPTURE_EMPTY_RETRY_DELAY_MS)
                }
            } finally {
                panesSeedInFlightThisAttach.remove(pane.paneId)
            }
        }
        // NOTE: this heal does NOT pre-skip a blank/partial-blank pane. The divergence oracle
        // ([visibleScreenDivergesFromCapture]) is the single decision: it fires only when
        // tmux's grid HAS substantial content while the VISIBLE viewport shows almost none of
        // it — a stale render whether the residue is zero glyphs, scattered fragments (#966), or
        // a post-burst clear. The heal is idempotent (a full clear+repaint of tmux's grid).
        //
        // Issue #1294: the SURFACE-BLACK detector/heal runs FIRST — BEFORE and INDEPENDENT of
        // the `capture-pane` round-trip below. A surface-only black (MODEL grid intact but the
        // on-screen SURFACE confirmed black, spike #874 GAP-1) recovers with a PURE surface
        // repaint that needs NO tmux content, so a wedged/timed-out capture must never gate it
        // (before #1294 it sat behind the capture's empty/errored early-return and a
        // capture-mutex-starving Claude burst blocked the one recovery needing no capture). Its
        // exportable FINGERPRINT is recorded below where captureBytes is known (or, on capture
        // failure, in the capture-failed branch).
        val surfaceBlack = pane.terminalState.surfaceIsBlackWhileModelHasContent()
        if (surfaceBlack) {
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-surface-black-model-intact-heal pane=${pane.paneId} " +
                    "window=${pane.windowId} session=${activeTarget?.sessionName} " +
                    "status=${_connectionStatus.value} " +
                    "rendered=${pane.terminalState.renderedNonBlankCharCount()}",
            )
            pane.terminalState.requestSurfaceRepaint()
        }
        // Issue #1301 (capture-clobbers-newer-delta race): QUIESCE live `%output` delivery
        // around the capture+apply — but ONLY for a pane whose render already looks SUSPECT
        // (one we are about to reseed). Closing the seed gate buffers any NEWER in-flight delta
        // (in arrival order); [appendRemoteOutput]'s `seedThenOpenGate` re-applies it ON TOP of
        // the snapshot (newest-wins) so the snapshot's `CSI 2J` clear can't clobber it. A
        // HEALTHY pane is NOT quiesced (gate stays open — #1219/#1164 steady-heat back-off
        // untouched); the `finally` REOPENS the gate on EVERY exit so live output is never
        // swallowed (#468 fail-safe). Issue #1353 R3 wraps this WHOLE window in the per-pane
        // single-flight ([renderHealCoordinator]) so a sibling launcher can't cross into it and
        // open the gate early (the concurrent premature-open clobber — see the coordinator).
        val quiesceLiveDeltas =
            surfaceBlack || pane.terminalState.renderLooksSuspect()
        if (quiesceLiveDeltas) pane.terminalState.closeSeedGate()
        try {
        val combined = runCatching {
            withContext(seedIoDispatcher) {
                client.captureWithCursor(
                    pane.paneId,
                    scrollbackLines = SEED_SCROLLBACK_LINES,
                    timeoutMs = seedCaptureTimeoutMs,
                )
            }
        }.getOrNull()
        if (refreshGuard != null && !isCurrentRuntime(refreshGuard)) return HealOutcome.Unverified
        val captureResponse = combined?.capture
        if (captureResponse == null || captureResponse.isError || captureResponse.output.isEmpty()) {
            // Issue #1294: the `capture-pane` round-trip could NOT confirm the render against
            // tmux's grid (timed out / errored / EMPTY on a live transport / starved by a
            // capture-mutex-holding burst). The surface repaint above already fired if black.
            // Scoring turns on whether the LOCAL render looks LOST ([renderLooksSuspect]):
            //  - LOOKS LOST → UNVERIFIED: we needed this capture to heal a suspect pane and
            //    couldn't; the watchdog keeps the HOT cadence (retry at 4s) instead of scoring
            //    the failure as healthy and backing off to 16s while the pane is black (the bug).
            //  - looks HEALTHY → a momentary empty/failed capture over a dense render is a
            //    no-op; scoring HEALTHY preserves the #1219/#1164 battery back-off.
            val renderLooksLost = surfaceBlack || pane.terminalState.renderLooksSuspect()
            // Issue #1175: fingerprint the observed frame when degenerate so an export sees
            // WHICH class of black it was (rides this same failed capture — no new round-trip):
            // surface-only-black → #1192 class; empty capture over a degenerate render →
            // `capture_empty`, or `never_seeded` if no seed ever landed. Healthy pane: silent.
            if (surfaceBlack) {
                recordBlackFrameObserved(pane, BLACK_FRAME_CLASS_SURFACE_BLACK_MODEL_INTACT, captureText = null)
            } else if (pane.terminalState.renderedNonBlankCharCount() == 0 ||
                pane.terminalState.visibleScreenIsBlankOrPartiallyBlank()
            ) {
                val blackClass = if (paneLastSeedAtMs[pane.paneId] == null) {
                    BLACK_FRAME_CLASS_NEVER_SEEDED
                } else {
                    BLACK_FRAME_CLASS_CAPTURE_EMPTY
                }
                recordBlackFrameObserved(pane, blackClass, captureText = null)
            }
            return if (renderLooksLost) HealOutcome.Unverified else HealOutcome.Healthy
        }
        val captureText = captureResponse.output.joinToString(separator = "\n")
        // The discriminating check: tmux carries a real frame but the local render
        // shows almost none of it → stale render on a live transport. If the
        // render already matches tmux, this is false and we never touch the grid.
        //
        // Issue #1138: use the UNION predicate so a live-streaming ALT-SCREEN agent pane
        // (Codex/Claude) that went PARTIAL-BLACK — only the live status line painted, upper
        // rows black — is healed too. Its sparse alt-screen frame puts the surviving band
        // ABOVE the #966 25% divergence ceiling, so the old `visibleScreenDivergesFromCapture`
        // predicate alone read it "healthy" and the watchdog never fired. The new predicate
        // also heals a partial-black pane when tmux's grid holds materially more (anti-thrash
        // guarded), restoring the FULL viewport from tmux's authoritative capture.
        if (!pane.terminalState.visibleRenderLostFrameVsCapture(captureText)) {
            // Issue #1192: the MODEL grid did NOT lose tmux's frame — the heal oracle,
            // comparing model-vs-tmux, calls this pane HEALTHY. The authoritative capture
            // CONFIRMED the render, so this is [HealOutcome.Healthy] — the ONLY outcome that
            // earns the #1219 back-off. But the on-screen SURFACE can still be black while
            // the model is intact (a surface-only black never diverges from tmux by
            // construction, spike #874 GAP-1), so it emits NONE of the five #1175 classes.
            // The #1203 surface repaint already fired above (issue #1294 moved it out from
            // behind the capture); fingerprint that ONE otherwise-invisible class here where
            // the authoritative captureBytes is known. Rides this same already-paid capture
            // tick: NO new poll/timer (#1164).
            if (surfaceBlack) {
                recordBlackFrameObserved(
                    pane,
                    BLACK_FRAME_CLASS_SURFACE_BLACK_MODEL_INTACT,
                    captureText,
                )
            }
            return HealOutcome.Healthy
        }
        // Issue #1175: the render LOST tmux's frame (capture carries materially more
        // than the render). Fingerprint the observed black frame BEFORE the heal
        // repaints it. A render with SOME surviving live content is the partial/half-
        // black class; a fully-blank render (renderedChars == 0) that was previously
        // painted is `lost_after_paint`. This rides the heal's already-paid capture —
        // no new poll, and it is additive to `stale_render_heal` below (which still
        // fires on the successful heal).
        val observedClass = if (pane.terminalState.renderedNonBlankCharCount() > 0) {
            BLACK_FRAME_CLASS_PARTIAL_BLANK
        } else {
            BLACK_FRAME_CLASS_LOST_AFTER_PAINT
        }
        recordBlackFrameObserved(pane, observedClass, captureText)
        val cursor = parseTmuxPaneCursor(combined.cursorReply)
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-stale-render-heal pane=${pane.paneId} window=${pane.windowId} " +
                "session=${activeTarget?.sessionName} status=${_connectionStatus.value} " +
                "rendered=${pane.terminalState.renderedNonBlankCharCount()} " +
                "captureChars=${captureText.count { !it.isWhitespace() }}",
        )
        DiagnosticEvents.record(
            "terminal",
            "stale_render_heal",
            "paneId" to pane.paneId,
            "windowId" to pane.windowId,
            "session" to activeTarget?.sessionName,
            "renderedChars" to pane.terminalState.renderedNonBlankCharCount(),
            "captureChars" to captureText.count { !it.isWhitespace() },
            "reconnect" to false,
        )
        try {
            pane.terminalState.appendRemoteOutput(
                captureResponse.output.toTerminalViewportBytes(cursor),
            )
        } catch (cause: Throwable) {
            if (cause is CancellationException) throw cause
            reportTerminalSurfaceFailure(pane.paneId, cause)
            // Issue #1294: the divergence was real but the surface write failed — the heal
            // did not complete, so this is UNVERIFIED (keep the hot cadence to retry), not a
            // confirmed-healthy back-off.
            return HealOutcome.Unverified
        }
        return HealOutcome.Healed
        } finally {
            // Issue #1301 (fail-safe): if we quiesced above, REOPEN the seed gate on EVERY
            // exit — the HEALED path already opened it (via `seedThenOpenGate`, an idempotent
            // no-op flush here), while the HEALTHY / UNVERIFIED / capture-failed / cancelled /
            // surface-write-error paths never applied a snapshot, so this flushes any delta
            // buffered during the capture window (in order) and opens the gate. Without this a
            // suspect pane that scored HEALTHY/UNVERIFIED would be left with the gate CLOSED,
            // silently swallowing all future live `%output` — a far worse bug than the race.
            if (quiesceLiveDeltas) {
                runCatching { pane.terminalState.openSeedGateWithoutSeed() }
            }
        }
    }

    /**
     * Issue #1353 R2 — the ONE capture→[appendRemoteOutput] apply. A single
     * single-flight `capture-pane`+cursor round-trip that applies tmux's authoritative
     * grid to the pane, with the #989 NON-DESTRUCTIVE swap ([captureWouldClearVisibleContent]):
     * a momentarily near-blank capture is REFUSED (the last good frame is kept, never
     * cleared to black) so the caller's retry loop re-captures. Returns true when a
     * snapshot was actually applied. Called ONLY from the force branch of
     * [healActivePaneIfStaleRender] (the unified reseed chokepoint) — there is no other
     * seed entry point after the R2 fold.
     */
    private suspend fun captureAndApplyPaneSnapshot(
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
        // Issue #926: run the BLOCKING `capture-pane`+cursor round-trip OFF the
        // Main (UI) thread on [seedIoDispatcher], bounded by the SHORT seed
        // ceiling ([seedCaptureTimeoutMs]) rather than the full 10 s
        // `commandTimeoutMs`. On a wedged-but-alive `-CC` channel this is the
        // single most important freeze fix: the seed can no longer park the UI
        // thread (it parks an IO thread, briefly) and falls through to the blank
        // watchdog on the still-live transport. The pane-emulator mutation
        // ([appendRemoteOutput]) + reveal signals stay on the caller's (Main)
        // thread below, so the single-threaded pane-state invariant is preserved.
        val combined = runCatching {
            withContext(seedIoDispatcher) {
                client.captureWithCursor(
                    pane.paneId,
                    scrollbackLines = SEED_SCROLLBACK_LINES,
                    timeoutMs = seedCaptureTimeoutMs,
                )
            }
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
        // Issue #989 — NON-DESTRUCTIVE swap. `toTerminalViewportBytes` prepends a
        // `CSI 2J` clear, so painting an idle alt-screen agent's near-blank-but-
        // non-empty capture would wipe the visible content to black (the #989
        // Redraw-to-black / attach-stays-black symptom). The `isEmpty()` guard
        // above only catches a TRULY empty capture; this catches the near-blank
        // one. Refuse to paint a capture that would clear an existing content-rich
        // frame — return false so the seed loop RE-CAPTURES (giving the forced
        // `C-l` repaint more time to land); if no good frame ever arrives the last
        // frame is simply KEPT (never cleared to black). A genuinely black pane's
        // first real seed is unaffected: the guard only fires when the current
        // render has materially MORE than the capture would restore.
        val captureText = captureResponse.output.joinToString(separator = "\n")
        if (pane.terminalState.captureWouldClearVisibleContent(captureText)) {
            Log.i(
                ISSUE_145_RECONNECT_TAG,
                "tmux-seed-skip-near-blank-capture pane=${pane.paneId} " +
                    "window=${pane.windowId} session=${activeTarget?.sessionName} " +
                    "rendered=${pane.terminalState.renderedNonBlankCharCount()} " +
                    "captureChars=${captureText.count { !it.isWhitespace() }} " +
                    "(kept last frame — not cleared to black)",
            )
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
        // Issue #1175: stamp the successful seed landing (same point the attach set
        // records it) so the `black_frame_observed` diagnostic can report
        // `msSinceLastSeed`, and so an ABSENT stamp cleanly discriminates the
        // `never_seeded` black-frame class from `capture_empty`. Diagnostic accounting
        // only — no write-path control-flow change.
        paneLastSeedAtMs[pane.paneId] = SystemClock.elapsedRealtime()
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
     *
     * Issue #819 (Slice A2 — re-anchor, don't trust the remembered source):
     * the seed used to restore `remembered.detection` BLIND and render it
     * `Live` immediately. That `detection` carries a `sourcePath`, and the
     * conversation source is selected by mtime among same-cwd same-kind
     * candidates — so when a sibling / sub-agent / second window/worktree
     * Codex rollout shared the cwd, the remembered `sourcePath` could be the
     * WRONG session's rollout, captured during a prior mis-pick. Rendering it
     * `Live` before live re-detection re-bound the route-true source showed the
     * Conversation tab a DIFFERENT transcript than the route-named Terminal —
     * the reported `ai-shipping-labs` header vs `git-pocketshell-desktop-codex`
     * divergence. The route-true source can only be re-confirmed by the live
     * `detectRecordedSessionForPane` `/proc/<pid>/fd` round-trip
     * ([AgentDetector.detect]'s owned-then-refuse-to-guess pin, #819 Slice A1).
     *
     * So the seed now restores ONLY the remembered KIND + the user's tab choice
     * as a RESOLVING placeholder ([rememberedAgentPlaceholder] = true,
     * `detection = null`, [ConversationLoadState.Loading]) — never the stale
     * `sourcePath`. The Conversation tab still appears instantly on reattach
     * (no black-Terminal flash, the #495 benefit preserved), but it shows
     * "Loading conversation…" until live detection binds the route's OWN source,
     * instead of flashing a sibling's transcript. The placeholder is held and
     * re-confirmed across a transient post-reattach null (a CONFIRMED-prior agent
     * window — the #554 no-flap guarantee, via [shouldDeferAgentDowngrade]); only
     * after [AGENT_EXIT_CONFIRMATIONS] consecutive nulls is it torn down by
     * [clearAgentDetectionForPane] (the agent genuinely exited). This NEVER tears
     * the pane down to a raw shell on the first null (no #554 flap regression).
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
                // Issue #819 (A2): do NOT carry remembered.detection.sourcePath
                // — it may be a stale/sibling source from a prior mis-pick. Seed
                // detection-less; live detectRecordedSessionForPane re-anchors
                // the route-true source via the /proc/<pid>/fd pin (A1).
                detection = null,
                events = emptyList(),
                selectedTab = if (remembered.wasOnConversation) {
                    SessionTab.Conversation
                } else {
                    SessionTab.Terminal
                },
                syncStatus = AgentConversationSyncStatus.Live,
                // Issue #819 (A2): hold "Loading conversation…" — a resolving
                // placeholder, not the stale transcript — until live detection
                // binds the real source.
                loadState = ConversationLoadState.Loading,
                // Issue #819 (A2): mark this as the remembered-agent placeholder
                // so a transient post-reattach null is held + re-confirmed (a
                // KNOWN agent, the #554 no-flap guarantee) and a confirmed exit
                // tears it down instead of stranding it on "Loading…".
                rememberedAgentPlaceholder = true,
            ),
        )
        // Issue #819 (A2): arm the load watchdog so a never-arriving live
        // re-detection resolves the placeholder to a clear terminal state
        // instead of spinning forever (mirrors seedPresumedAgentPlaceholder).
        armConversationLoadWatchdog(pane.paneId)
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
     *
     * Issue #962: a live agent detection that binds to a pane of this session
     * re-classifies it out of confirmed-shell via
     * [clearConfirmedShellOnLiveAgentDetection] (calling this with
     * `isShell = false`), so a claude/codex/opencode started inside a
     * shell-recorded session regains its Conversation toggle. A recorded-shell
     * read that re-marks the session is harmless: if a live agent is present the
     * next detection bind clears it again; a genuine shell stays marked (#894).
     *
     * Issue #975 (B2): a REMEMBERED-agent (#819 A2) resolving placeholder is NOT
     * dropped on `isShell = true` — only the fresh auto-seeded (#878) placeholder
     * is. A beyond-grace reattach (#959) re-stamps this verdict on every
     * reconnect; tearing down the restored remembered-agent placeholder would
     * re-suppress the Conversation toggle for a session that WAS a live agent.
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
            // Drop any AUTO-seeded (#878) placeholder that raced ahead of this
            // verdict — that is a fresh first-open optimistic seed, and a
            // confirmed shell must never linger on the wrong surface (the
            // first-open flash kill).
            //
            // Issue #975 (B2, reattach re-stamp): do NOT drop a REMEMBERED-agent
            // (#819 A2) resolving placeholder here. A beyond-grace reattach (#959)
            // re-reads `@ps_agent_kind=shell` and re-applies this verdict on every
            // reconnect; if it tore down the just-restored remembered-agent
            // placeholder, it would re-suppress the Conversation toggle for a
            // session that WAS a live agent before backgrounding — and recovery
            // would then depend on the same fragile daemon-classify B1 shows is
            // unproven, stranding the user on the raw black Terminal. A remembered
            // agent is trustworthy prior live-agent evidence: keep its placeholder
            // and let detection re-confirm it. A GENUINE shell (the agent really
            // exited and was replaced) still tears the placeholder down — through
            // the deferred-downgrade path (handleNullAgentDetection's
            // AGENT_EXIT_CONFIRMATIONS consecutive nulls), which is the correct,
            // evidence-driven teardown rather than an unconditional re-stamp.
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
        } else {
            // Issue #874 (residual black-screen): a presumed-agent pane that is
            // RECONCILED rather than freshly added — a beyond-grace reattach
            // (#959) or a switch-back to a REBUILT cached runtime
            // ([restoreCachedRuntime] restores `runtime.agentConversations`, which
            // only carries rows that had a live `detection`; a row dropped by the
            // R3-B 2-null collapse on a wedged channel is gone) — has NO
            // conversation row. `seedPresumedAgentPlaceholder` fires per-pane in
            // [applyParsedPanes], but the cache-restore path never reconciles, so
            // its presumed-agent panes fall through to the always-mounted raw
            // `TmuxTerminalPager` — the #807 black void.
            //
            // The verdict has now resolved this session as NOT a confirmed shell
            // (foreign / agent / re-classified). Running the re-seed pass HERE —
            // AFTER the recorded-kind verdict applies — re-establishes the
            // Conversation "Loading…" placeholder for any presumed-agent pane that
            // lost its row, closing the void. It is idempotent: a pane that still
            // has a row (`containsKey`), a confirmed shell
            // (`isConfirmedShellSession`, false here by construction), or the
            // Terminal open-time default are all skipped inside
            // [seedPresumedAgentPlaceholder] — so #894's no-flash-on-shell
            // invariant holds (a confirmed shell is never re-seeded) and a live
            // row is never clobbered (no #815 yank).
            paneRows.values
                .filter { it.sessionId.trim() == key }
                .forEach { seedPresumedAgentPlaceholder(it) }
        }
        if (changed || isShell) refreshConfirmedShellPaneIds()
    }

    /**
     * Issue #962: a live agent detection just bound to [paneId] — re-classify its
     * session out of [confirmedShellSessionIds] because the high-confidence live
     * signal outranks a stale recorded `@ps_agent_kind=shell`. Idempotent and
     * cheap; a no-op when the session was not confirmed-shell. This is the single
     * AUTHORITATIVE override point (driven by the real detection event, not the
     * unreliable wrapper `comm`), so the Conversation toggle returns for a
     * claude/codex/opencode started inside a shell-recorded session.
     */
    private fun clearConfirmedShellOnLiveAgentDetection(paneId: String) {
        val sessionId = paneRows[paneId]?.sessionId?.trim().orEmpty()
        if (sessionId.isEmpty()) return
        if (!confirmedShellSessionIds.contains(sessionId)) return
        applyRecordedShellVerdict(sessionId = sessionId, isShell = false)
    }

    /**
     * Issue #962: a session recorded `@ps_agent_kind=shell` (a plain shell the
     * user/kind-picker classified as shell) maps the recorded kind to null →
     * the FOREIGN resolver, whose host-daemon kind guess is ONE-SHOT cached per
     * session. If that guess fired BEFORE an agent was started inside the shell
     * it cached "no agent", and the one-shot rule would then never re-probe — so
     * a `claude`/`codex`/`opencode` later started in the pane would never bind a
     * live source. For a CONFIRMED-SHELL session we therefore bust the one-shot
     * foreign-guess cache before each detection so the daemon (which classifies
     * via the pane's `/proc` comm+cmdline — e.g. the `…/claude` path token, NOT
     * the wrapper `comm`) re-evaluates and binds the live agent. Once it binds,
     * [markAgentTailLive] → [clearConfirmedShellOnLiveAgentDetection] re-classifies
     * the session out of confirmed-shell so the Conversation toggle returns.
     *
     * Cheap + idempotent. A genuine shell re-probes and the daemon returns "no
     * agent" again (no flap — the #894 invariant holds; no detection ever binds,
     * so the confirmed-shell verdict is never cleared).
     */
    private fun refreshForeignGuessForConfirmedShellPane(pane: TmuxPaneState) {
        val sessionKey = pane.sessionId.trim()
        if (sessionKey.isEmpty()) return
        if (!confirmedShellSessionIds.contains(sessionKey)) return
        sessionForeignKindGuessCache.remove(sessionKey)
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
     * Issue #1158: thin forwarders into [TmuxAltBufferAgentLatch] (extracted for
     * PR #1431 file-size hygiene). The SERVER-TRUTH `#{alternate_on}` latch and
     * its per-pane projection live there; the VM just supplies its own state.
     */
    private fun latchAltBufferAgentsFromParsed(parsed: List<ParsedPane>) =
        TmuxAltBufferAgentLatch.latchAltBufferAgentsFromParsed(
            parsed, altBufferAgentSessionIds, paneRows, _altBufferAgentPaneIds,
        )

    private fun refreshAltBufferAgentPaneIds() =
        TmuxAltBufferAgentLatch.refreshAltBufferAgentPaneIds(
            altBufferAgentSessionIds, paneRows, _altBufferAgentPaneIds,
        )

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
     * sessions that have no recorded kind to find. [source] is the optional
     * exact `@ps_agent_source` transcript path captured by the host-side launch
     * watcher for sessions PocketShell started.
     */
    private class RecordedKindCacheEntry(
        val kind: AgentKind?,
        val source: String?,
        val sourceGenerationScoped: Boolean,
    )

    private suspend fun recordedSourceForCachedKind(
        session: SshSession,
        sessionTarget: String,
        cachedKind: RecordedKindCacheEntry,
    ): String? {
        val recordedKind = cachedKind.kind ?: return null
        cachedKind.source?.takeIf { cachedKind.sourceGenerationScoped }?.let { return it }
        val key = sessionTarget.trim()
        if (key.isEmpty()) return null
        val refreshedSource = agentRepository.readRecordedAgentSourceOption(
            session = session,
            sessionTarget = key,
        )
        sessionRecordedKindCache[key] = RecordedKindCacheEntry(
            kind = recordedKind,
            source = refreshedSource.source,
            sourceGenerationScoped = refreshedSource.generationScoped,
        )
        return refreshedSource.source
    }

    /**
     * Epic #821 slice A2: box for the per-session ONE-SHOT FOREIGN kind guess.
     * An ABSENT map entry means "not yet guessed" (the guess may still fire);
     * a PRESENT entry whose [kind] is null means "guessed = not an agent (a
     * shell) or unknown" — either way the guess has already fired and must NOT
     * be re-run on later reconciles (the one-shot discipline). [kind] holds the
     * guessed engine when the daemon classified the pane as an agent.
     *
     * Issue #975 (B1, classify-miss): also box the daemon's `none`-vs-`unknown`
     * distinction ([isShell]). `none` (`isShell = true`) is a READABLE scope with
     * no agent — a genuine shell. `unknown` (`isShell = false`, kind null) is an
     * UNREADABLE scope — "we could not tell", which on a CONFIRMED-SHELL session
     * with a live node-wrapped/quiet `claude` is exactly the masked-agent case.
     * The caller uses this to gate the transcript-evidence fallback so it fires
     * ONLY on `unknown` (never collapsing a genuine `none` shell).
     */
    private class ForeignKindGuessEntry(val kind: AgentKind?, val isShell: Boolean)

    /**
     * Epic #821 slice A2: resolve a FOREIGN session's agent kind via the
     * host-side ONE-SHOT daemon guess (`pocketshell agents kind`), cached per
     * tmux session id so it fires AT MOST ONCE per foreign session. Returns the
     * cached guess immediately on a hit; on a miss it sends the pane's
     * `(pane_id, pane_pid)` to the daemon, caches the verdict, and returns it.
     * A null [ForeignKindGuessEntry.kind] means the daemon did not classify the
     * pane as an agent (a shell / unknown / no pid / tool missing) — the caller
     * treats that as "no conversation for this pane" but does NOT re-probe.
     *
     * Issue #975: returns the FULL boxed verdict (kind + `none`-vs-`unknown`) so
     * the caller can distinguish a genuine readable shell (`none`) from an
     * unreadable scope (`unknown`) for the masked-live-agent transcript fallback.
     */
    private suspend fun resolveForeignKindGuess(
        session: SshSession,
        sessionTarget: String,
        pane: TmuxPaneState,
    ): ForeignKindGuessEntry {
        val key = sessionTarget.trim()
        sessionForeignKindGuessCache[key]?.let { return it }
        val panePid = pane.panePid.takeIf { it > 0L }
        // No pid → cannot ask the daemon. Cache the (null) verdict so we don't
        // re-probe a pane that never carries a pid, preserving the one-shot rule.
        // No pid means we could not even ask — `unknown`, not a readable `none`.
        if (panePid == null) {
            sessionForeignKindGuessCache.putIfAbsent(
                key,
                ForeignKindGuessEntry(kind = null, isShell = false),
            )
            return sessionForeignKindGuessCache.getValue(key)
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
        val paneVerdict = verdict[pane.paneId]
        sessionForeignKindGuessCache.putIfAbsent(
            key,
            ForeignKindGuessEntry(
                kind = paneVerdict?.kind,
                // An ABSENT pane verdict (tool missing / daemon error / parse
                // failure → empty map) is "we could not tell" = unknown, not a
                // confirmed shell. Only an explicit `none` row is a readable shell.
                isShell = paneVerdict?.isShell == true,
            ),
        )
        return sessionForeignKindGuessCache.getValue(key)
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
        val guess = resolveForeignKindGuess(
            session = session,
            sessionTarget = sessionTarget,
            pane = pane,
        )
        val guessedKind = guess.kind
        if (guessedKind != null) {
            return agentRepository.detectRecordedSessionForPane(
                session = session,
                cwd = cwd,
                paneTty = tty,
                paneCommand = command,
                recordedKind = guessedKind,
            )
        }
        // Issue #975 (B1, classify-miss): the daemon did not name a kind. If this
        // is a CONFIRMED-SHELL session AND the daemon's verdict was `unknown`
        // (an UNREADABLE scope — `isShell == false`), the recorded-shell verdict
        // is no longer trustworthy: a live node-wrapped/quiet `claude` the
        // cgroup-v2/`/proc` classify cannot see is exactly the masked-agent case
        // the maintainer hit (the live Claude session with NO Conversation
        // toggle, #962 recurrence). Trust a LIVE transcript scoped to the cwd as
        // the agent evidence: if a fresh `*.jsonl` binds, return that detection
        // so markAgentTailLive clears the stale shell verdict and the toggle
        // returns. A daemon `none` (readable scope, no agent — `isShell == true`)
        // is a GENUINE shell and gets NO fallback (the #894 no-flap invariant). A
        // foreign (not-confirmed-shell) pane with no kind guess also stays null —
        // we only second-guess a recorded-shell verdict, never a clean foreign.
        if (isConfirmedShellSession(sessionTarget) && !guess.isShell) {
            return agentRepository.detectLiveTranscriptForPane(
                session = session,
                cwd = cwd,
                paneTty = tty,
                paneCommand = command,
            )
        }
        return null
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
        // Issue #975 (B1′, dedup ordering): bust the one-shot foreign kind-guess
        // cache for a CONFIRMED-SHELL pane BEFORE the dedup early-return, not
        // after it. A `claude` started inside an already-detected shell pane does
        // NOT change the `(cwd, command, tty)` triple (no tty change), so the
        // dedup key matches and — with the bust ordered AFTER the early-return
        // (the #962 ordering bug) — the cache-bust never ran and the stale "no
        // agent" guess persisted for the life of the session, suppressing the
        // Conversation toggle. Busting first means the next re-probe of a
        // confirmed-shell pane always re-evaluates the daemon (and the #975 B1
        // transcript fallback), so a live agent started in the shell can bind and
        // clear the verdict even when the input triple is unchanged. A genuine
        // shell re-evaluates to "no agent" again (no flap — #894).
        refreshForeignGuessForConfirmedShellPane(pane)
        if (paneAgentInputs[pane.paneId] == input && paneAgentJobs[pane.paneId]?.isActive == true) {
            // The cache-bust above already forced the daemon to re-evaluate on
            // the NEXT probe. A confirmed-shell pane whose input is unchanged but
            // whose one-shot guess was just invalidated must not be left deduped
            // on a stale in-flight job — re-probe it so the bust takes effect.
            if (!isConfirmedShellSession(pane.sessionId)) return
        }
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
                        val recordedSource = recordedSourceForCachedKind(
                            session = session,
                            sessionTarget = sessionTarget,
                            cachedKind = cachedKind,
                        )
                        agentRepository.detectRecordedSessionForPane(
                            session = session,
                            cwd = cwd,
                            paneTty = tty,
                            paneCommand = command,
                            recordedKind = recordedKind,
                            recordedSource = recordedSource,
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
                        RecordedKindCacheEntry(
                            kind = open.recordedKind,
                            source = open.recordedSource,
                            sourceGenerationScoped = open.recordedSourceGenerationScoped,
                        ),
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
                                recordedSource = open.recordedSource,
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
        // Issue #942 (black-screen B2, reopen-class): an empty grep
        // (`Resolved(null)`) on a channel that is STILL actively streaming
        // output is a WEDGED-but-alive channel (the capture/grep raced behind a
        // busy agent — the #470/#835 symptom), not an agent that exited. Counting
        // it toward [AGENT_EXIT_CONFIRMATIONS] is how a remembered Conversation
        // collapsed to the raw black Terminal after 2× empty detections (#874 D4,
        // verified live on v0.4.16). #897 protected the `Unavailable` branch; this
        // guards the still-streaming `Resolved(null)` branch. We only protect a
        // pane that actually has agent UI to lose — a genuine shell pane (no
        // remembered/seeded agent, no live detection) is unaffected — and a
        // genuinely-exited agent stops emitting output, so its empty grep arrives
        // with NO recent activity and tears down correctly (no over-protection).
        if (isChannelWedgedButAlive(paneId) && hasProtectableAgentUi(paneId)) {
            scheduleAgentDetectionRecheck(pane, guard)
            return false
        }
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
        // Issue #819 (A2): a remembered-agent resolving placeholder (detection
        // still null while the route-true source is being re-bound) is a
        // KNOWN-prior agent — a degraded probe must keep retrying it, not strand
        // it on "Loading…", exactly as for the #878 auto-seed below.
        if (row?.autoSeededPlaceholder == true || row?.rememberedAgentPlaceholder == true) {
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
     *
     * Issue #819 (Slice A2): the #495 reattach seed now restores the remembered
     * window as a detection-LESS resolving placeholder
     * ([AgentConversationUiState.rememberedAgentPlaceholder] = true) instead of
     * carrying the (possibly stale) remembered source. That placeholder is just
     * as much a confirmed-prior agent that a transient post-reattach null must
     * NOT tear down, so the "still showing the seeded agent UI" condition below
     * holds for BOTH a real detection AND a remembered resolving placeholder.
     * Without this the A2 seed would re-introduce the #554 flap (a remembered
     * agent dropped to a raw shell on the first null because its `detection` is
     * now null until the live re-bind lands).
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
        // nothing to protect. A remembered resolving placeholder (#819 A2)
        // counts as "still showing" even though its detection is null.
        val row = _agentConversations.value[paneId] ?: return false
        return row.detection != null || row.rememberedAgentPlaceholder
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
     * Issue #942 (black-screen B2): true when [paneId]'s `-CC` channel produced
     * a `%output` chunk within the last [CHANNEL_WEDGED_RECENT_OUTPUT_MS] — i.e.
     * the channel is still actively STREAMING. An empty grep detection
     * (`Resolved(null)`) on such a channel is the wedged-but-alive
     * capture-behind-a-busy-agent race (#470/#835), NOT an agent exit, so it must
     * not be counted toward [AGENT_EXIT_CONFIRMATIONS]. A genuinely-exited agent
     * stops emitting output, so its empty grep lands with no recent activity and
     * this returns false (the row tears down correctly — no over-protection).
     * False when the pane has never emitted output (no entry) or its last output
     * is older than the window.
     */
    private fun isChannelWedgedButAlive(paneId: String): Boolean {
        val lastOutputAtMs = paneLastOutputAtMs[paneId] ?: return false
        return SystemClock.elapsedRealtime() - lastOutputAtMs < CHANNEL_WEDGED_RECENT_OUTPUT_MS
    }

    /**
     * Issue #942: true when [paneId] currently has agent Conversation UI that an
     * empty/transient detection could TEAR DOWN to the raw black Terminal — a
     * live detection, a remembered-agent resolving placeholder (#819 A2), an
     * auto-seeded detecting placeholder (#878), or a remembered window status the
     * #495 reattach seed protects (#554). The wedged-channel guard only fires for
     * such panes; a genuine shell pane (none of these) has nothing to protect, so
     * its null detection proceeds through the normal path unchanged.
     */
    private fun hasProtectableAgentUi(paneId: String): Boolean {
        val row = _agentConversations.value[paneId]
        if (row != null && (row.detection != null || row.rememberedAgentPlaceholder || row.autoSeededPlaceholder)) {
            return true
        }
        return shouldDeferAgentDowngrade(paneId)
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
     *
     * Issue #1057 (maintainer dogfood blocker — "conversation is not visible in
     * this app"): a row that has LOADED a real transcript ([events] non-empty)
     * is the DURABLE "events present but detection currently null" state the
     * Conversation tab must stay reachable for. Dropping such a row the instant
     * live detection settles null (the agent exited, the live tail dropped, or
     * re-detection never rebinds) is exactly what makes the conversation
     * UNREACHABLE — the user can no longer read the transcript that genuinely
     * exists. So an events-bearing row is now KEPT (detection nulled, transcript
     * preserved, marked [AgentConversationSyncStatus.Stale]) instead of dropped,
     * so [tmuxSessionTabState]'s `hasConversationContent` term keeps the toggle
     * reachable and a Conversation tap still renders the transcript. A row with
     * NO loaded transcript (a genuine shell / auto-seeded / remembered
     * placeholder) still drops exactly as before — the #186/#894 "tab disappears
     * for a window with no conversation" contract is unchanged.
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
                // Issue #1057: a row whose transcript has ALREADY loaded (events
                // present) is a conversation that genuinely EXISTS. Keep it
                // readable after the agent's live detection drops — null the
                // detection (no live tail) but preserve the events + the user's
                // tab choice, marking the frozen transcript Stale — so the
                // Conversation toggle stays reachable and tapping it still shows
                // the conversation instead of vanishing the user back to a raw
                // Terminal. This is the durable real-path state the maintainer's
                // "can't see the conversation" report needs.
                current.detection != null && current.events.isNotEmpty() -> {
                    current.copy(
                        detection = null,
                        syncStatus = AgentConversationSyncStatus.Stale,
                        loadState = ConversationLoadState.Ready,
                    )
                }
                // A row that carries a real detection but NO loaded transcript:
                // the agent exited before any transcript was read — drop it so
                // the Conversation tab disappears for this window (nothing to
                // read; the #186/#894 contract).
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
                // Issue #819 (A2): a REMEMBERED-agent resolving placeholder (the
                // #495 reattach seed, now detection-less) whose live re-detection
                // came back null AFTER the #554 hold/re-confirm window means the
                // agent genuinely exited — drop it so it does not strand on
                // "Loading conversation…". Like the auto-seed teardown above, this
                // is NOT a #815 yank (no user-chosen tab is being changed). The
                // transient post-reattach null was already absorbed by
                // shouldDeferAgentDowngrade's AGENT_EXIT_CONFIRMATIONS hold, so by
                // the time we reach here the exit is confirmed.
                current.rememberedAgentPlaceholder -> {
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
        // Issue #968: BIND THE UPLOAD TO ITS ORIGINATING SESSION AT TAP TIME.
        //
        // The attach path used to resolve its destination at upload-COMPLETION
        // time — it read `activeTarget` / `sessionRef` *after* the (possibly
        // slow) await, so a switch A->B mid-upload landed the bytes in B's
        // `.pocketshell/attachments/host-<hostId>-B/` scope (the warm `-CC`
        // SSH session is shared across every tmux session on the host — D21 —
        // so only the SCOPE DIR + the composer the paths merge into encode the
        // session, and both were read late). That is the maintainer's reported
        // data-MISROUTE: an attachment surprise-landing in the wrong agent
        // session.
        //
        // The send path already snapshots its target at INITIATION
        // (`sendTargetSnapshotProvider`); this is the un-snapshotted twin. The
        // fix snapshots the origin target HERE, at the first synchronous line
        // of the call (before any await / grace heal / switch can rebind
        // `activeTarget`), derives the scope dir from that snapshot, and — at
        // completion — refuses to deliver to a session that is no longer the
        // origin. An upload started in A always lands in A; if the user
        // switched away (the origin is no longer the active target) we surface
        // a clear error rather than silently misrouting to whatever is active
        // now (hard-cut per D22: no "deliver to current" fallback).
        val originTarget = activeTarget
        DiagnosticEvents.record(
            "action",
            "tmux_attachment_stage_start",
            "count" to uris.size,
            "originSession" to (originTarget?.sessionName ?: ""),
        )
        val context = applicationContext
            ?: return Result.failure(IllegalStateException("Attachment staging unavailable."))
        val scopeKey = when (originTarget) {
            null -> "tmux-session"
            else -> "host-${originTarget.hostId}-${originTarget.sessionName}"
        }
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
        // Issue #968: the await may have spanned a session switch (the file
        // picker backgrounds the app; the maintainer navigated A->B while the
        // upload looked stuck). If the active target is no longer the origin we
        // tapped in, the live `session` now belongs to a DIFFERENT tmux session
        // — uploading here would write the bytes into the wrong scope and merge
        // the paths into the wrong composer. Bind-to-origin: do NOT proceed.
        // Surface a clear error so the user re-attaches in the origin (the
        // upload is never silently misrouted to the now-active session).
        if (!isAttachmentOriginStillActive(originTarget)) {
            DiagnosticEvents.record(
                "action",
                "tmux_attachment_stage_origin_changed",
                "requestedCount" to uris.size,
                "scope" to scopeKey,
                "originSession" to (originTarget?.sessionName ?: ""),
                "activeSession" to (activeTarget?.sessionName ?: ""),
            )
            return Result.failure(
                IllegalStateException(
                    "Switched sessions before the upload finished — attachment not applied. " +
                        "Re-attach in the original session.",
                ),
            )
        }
        // Issue #1072: OWN the upload as a [viewModelScope] job so a connection
        // teardown ([closeCurrentConnectionAndJoin]) can cancel-and-join it BEFORE
        // it drops the transport, instead of leaving a free-floating writer blocked
        // on the dying `-CC` session that races teardown and wedges reconnect. We
        // run it in viewModelScope (a SupervisorJob) rather than the caller's
        // screen scope so a screen recomposition does not abandon the upload, and
        // the VM stays the single owner that the reconnect ladder coordinates with.
        val stager = PromptAttachmentStager(
            resolver = context.contentResolver,
            cacheDir = context.cacheDir,
        )
        val uploadDeferred = viewModelScope.async { stager.stage(session, scopeKey, uris) }
        attachmentUploadJob = uploadDeferred
        val result = try {
            uploadDeferred.await()
        } catch (ce: CancellationException) {
            // Our own (caller) coroutine was cancelled — propagate.
            if (!currentCoroutineContext().isActive) throw ce
            // The upload deferred was cancelled by a connection teardown (#1072):
            // surface a clear, draft-preserving error so a post-attach drop stays
            // recoverable (the reconnect ladder owns recovery) instead of crashing
            // the caller or silently misrouting.
            DiagnosticEvents.record(
                "action",
                "tmux_attachment_stage_cancelled_by_teardown",
                "requestedCount" to uris.size,
                "scope" to scopeKey,
            )
            Result.failure(
                IllegalStateException(
                    "Connection closed during the upload — attachment not applied. Re-attach.",
                ),
            )
        } finally {
            if (attachmentUploadJob === uploadDeferred) attachmentUploadJob = null
        }
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
     * Issue #968: is the [originTarget] snapshotted when the user tapped attach
     * still the active session at upload-completion time?
     *
     * The warm `-CC` SSH session is shared across every tmux session on a host
     * (D21), so the session-identity discriminator is the tmux **session name**
     * (plus host), NOT the SSH lease key (which is per-host). If the user
     * switched tmux sessions (A->B) during the upload await, the active target
     * is now B and delivering the bytes here would misroute them into B's scope
     * + composer. This returns `false` in exactly that case so the caller
     * surfaces an error instead of misrouting.
     *
     * A null origin (no target was active at tap) and a null active target both
     * resolve against the `"tmux-session"` fallback scope, so a null==null pair
     * is treated as "still the origin" (no switch occurred).
     */
    private fun isAttachmentOriginStillActive(originTarget: ConnectionTarget?): Boolean {
        val active = activeTarget
        if (originTarget == null) return active == null
        if (active == null) return false
        return active.hostId == originTarget.hostId &&
            active.sessionName == originTarget.sessionName
    }

    /**
     * Issue #968 (deterministic test seam): would the attach delivery be bound
     * to [originSessionName] on [originHostId] as its origin, given the current
     * active target? Mirrors the [isAttachmentOriginStillActive] gate the upload
     * consults at completion. On the OLD (base) code there was no origin gate —
     * the upload always resolved `activeTarget` at completion, so a switch
     * A->B misrouted the bytes into B. A JVM unit test snapshots A's target,
     * switches to B, and asserts this returns `false` (origin no longer active
     * -> error, no misroute) — the red->green discriminator for the fix.
     */
    @androidx.annotation.VisibleForTesting
    internal fun attachmentOriginStillActiveForTest(
        originHostId: Long?,
        originSessionName: String?,
    ): Boolean {
        val active = activeTarget
        if (originHostId == null || originSessionName == null) return active == null
        if (active == null) return false
        return active.hostId == originHostId && active.sessionName == originSessionName
    }

    public suspend fun uploadQueuedAttachmentSidecars(
        sidecars: List<LocalAttachmentSidecarRef>,
    ): Result<List<String>> = withContext(seedIoDispatcher) {
        DiagnosticEvents.record("action", "tmux_outbound_sidecar_upload_start", "count" to sidecars.size)
        if (sidecars.isEmpty()) return@withContext Result.success(emptyList())
        val session = awaitLiveSessionForAttachment()
            ?: return@withContext Result.failure(IllegalStateException("No live SSH session for attachment upload."))
        if (!session.isConnected) {
            return@withContext Result.failure(SshException("SSH session is not connected"))
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
        try {
            val mkdir = session.exec("mkdir -p \"\$HOME/$remoteDir\"")
            if (mkdir.exitCode != 0) {
                val detail = mkdir.stderr.ifBlank { mkdir.stdout }.trim()
                return@withContext Result.failure(
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

    @androidx.annotation.VisibleForTesting
    internal fun markActiveLeaseWarmForTest() {
        val target = activeTarget ?: connectingTarget ?: return
        liveLeaseKeys.add(target.toSshLeaseTarget().leaseKey)
    }

    /** Issue #1568 test seam: set [leaseRef] warm so a proof drives the `warmLeaseHeld` rung. */
    @androidx.annotation.VisibleForTesting
    internal suspend fun setActiveLeaseRefWarmForTest() {
        val target = activeTarget ?: connectingTarget ?: return
        leaseRef = sshLeaseManager.acquire(target.toSshLeaseTarget()).getOrNull()
    }

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

    // Issue #1526 S1 / #1541 / #1587: verify-before-resend ledger, durable-backed by
    // the injected @Singleton store (see [outboundDeliveryLedgerFor]). Null ⇒ base S1.
    private val outboundDeliveryLedger = outboundDeliveryLedgerFor(outboundQueueStore)

    private fun consumeSendResultLostSeamForTest() {
        if (!OutboundDeliverySeams.consumeSendResultLostBeforeSubmitEnter()) return
        // The seam models the audit's cut point (c): the paste ran server-side,
        // then the link died before the submit Enter's result came back.
        val dropped = triggerCleanPassiveDropForTest()
        DiagnosticEvents.record("action", "outbound_result_lost_seam", "dropped" to dropped)
        throw IllegalStateException(
            "test-seam: transport dropped after paste, before submit Enter (result lost)",
        )
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
        // Issue #1526 S1 (verify-before-resend): a PRIOR ambiguous attempt at this
        // exact (pane, payload) — timeout/drop after the paste may have run
        // server-side (audit A1/A2) — must not blindly re-paste. Probe the pane
        // (#869 needle, #1577 baseline-aware): already landed ⇒ submit-Enter ONLY;
        // unknown ⇒ fail WITHOUT resending (row stays queued for a verified retry);
        // not landed / no prior attempt ⇒ fall through to the normal full send.
        when (verifyBeforeAgentResend(outboundDeliveryLedger, client, paneId, payload)) {
            DeliveryProbeOutcome.AlreadyLanded -> return runCatching {
                sendNamedKeyToPane(client, paneId, "Enter")
                    .throwIfTmuxError("submit previously pasted agent input")
                outboundDeliveryLedger.clear(paneId, payload)
                requestReconcile(client, paneId, ReconcileReason.Send)
            }
            DeliveryProbeOutcome.Unknown -> return Result.failure(
                IllegalStateException("Prior send outcome unknown; kept queued without resend."),
            )
            DeliveryProbeOutcome.NotLanded, null -> Unit
        }
        return runCatching {
            ensurePaneAcceptsInput(client, paneId)
            outboundDeliveryLedger.recordWireAttemptWithBaseline(client, paneId, payload, localRenderTextForPane(paneId))
            // Issue #1577b: the pre-paste needle baseline the ack gate compares against
            // (Codex's permanent `(/goal resume)` footer occupies the baseline, so the
            // ack fires only on OUR paste adding an occurrence — never on the footer).
            val ackBaseline = outboundDeliveryLedger.needleBaseline(paneId, payload) ?: 0
            if (payloadBytes.size > TMUX_PASTE_BODY_CHUNK_BYTES || BracketedPaste.containsLineBreak(payloadBytes)) {
                sendBracketedPaste(client, paneId, payloadBytes)
            } else if (payload.isNotEmpty()) {
                sendLiteralTextKeys(client, paneId, payload)
                    .throwIfTmuxError("type agent input into pane $paneId")
            }
            awaitAgentPasteIngestedBeforeSubmit(client, paneId, payload, agent, ackBaseline)
            consumeSendResultLostSeamForTest()
            sendNamedKeyToPane(client, paneId, "Enter")
                .throwIfTmuxError("submit pasted agent input")
            outboundDeliveryLedger.clear(paneId, payload)
            // Issue #941/#1353 R4: after the submit Enter a full-screen agent TUI can
            // overpaint the active pane partial-black; a guarded heal EVENT through the
            // shared reconcile entry re-checks and re-seeds (no bespoke send-path timer).
            requestReconcile(client, paneId, ReconcileReason.Send)
        }
    }

    /**
     * Issue #1353 slice R4 — the EVENT-SUBMISSION entry for the render-heal reconciler. A trigger
     * submits an immediate hot reconcile for [paneId] via a [ReconcileReason] instead of owning a
     * bespoke poll loop; the reconcile runs through the SINGLE chokepoint
     * [healActivePaneIfStaleRender] (R3 per-pane single-flight), so two triggers on one pane can
     * never race the M9 seed gate. This slice migrates ONLY [ReconcileReason.Send] — the #941/#1153
     * post-send agent-overpaint heal that was the private `scheduleSendOverpaintHeal` 350 ms×4 poll;
     * L2/L3/L5 fold into this entry in a later slice (R5).
     *
     * Send behaviour preserved: after a send's submit Enter the agent's `%output` overpaint can
     * leave the active pane BLACK/partial/half-black on a LIVE transport. The reconcile re-checks
     * on a bounded cadence ([ReconcileReason.settleTicks] × [ReconcileReason.settleDelayMs]) so a
     * late/large redraw is still caught, judges the pane against tmux's AUTHORITATIVE `capture-pane`
     * (the #1138/#1300 union oracle), and is a no-op on a dense frame (each tick pays only the cheap
     * LOCAL [TerminalSurfaceState.renderLooksSuspect] pre-check). Guarded by a [RuntimeRefreshGuard]
     * so a late heal can never paint a switched-away session, and scoped to [paneId] while active.
     */
    private fun requestReconcile(client: TmuxClient, paneId: String, reason: ReconcileReason) {
        val target = activeTarget ?: return
        val healGeneration = connectGeneration
        val guard = RuntimeRefreshGuard(
            generation = healGeneration,
            target = target,
            client = client,
        )
        bridgeScope.launch {
            repeat(reason.settleTicks) { tick ->
                // Let the overpaint land (and re-check on later ticks for a late/large redraw)
                // before judging the pane.
                delay(reason.settleDelayMs)
                if (clientRef !== client) return@launch
                if (client.disconnected.value) return@launch
                if (inlineConnectionStatus !is ConnectionStatus.Connected) return@launch
                if (!isCurrentRuntime(guard)) return@launch
                val activePane = activeVisiblePane() ?: return@launch
                if (activePane.paneId != paneId) return@launch
                // Cheap LOCAL pre-check: skip the capture round-trip on a dense, normally-painted
                // response (its live rows sit above the sparse ceiling). Only a fully-blank /
                // partial-black / >3-line half-black render pays for the authoritative diff.
                if (!activePane.terminalState.renderLooksSuspect()) return@repeat
                Log.i(
                    ISSUE_145_RECONNECT_TAG,
                    "tmux-reconcile-event-active-pane-heal-check reason=$reason " +
                        "pane=${activePane.paneId} window=${activePane.windowId} " +
                        "session=${target.sessionName} tick=$tick",
                )
                // Authoritative heal through the SINGLE chokepoint (R3 per-pane single-flight):
                // capture tmux's grid, diff it against the render, and re-seed ONLY when the
                // render is materially less than the authoritative frame.
                val healed = runCatching {
                    healActivePaneIfStaleRender(client, activePane, guard)
                }.getOrDefault(HealOutcome.Unverified) == HealOutcome.Healed
                if (healed) {
                    Log.i(
                        ISSUE_145_RECONNECT_TAG,
                        "tmux-reconcile-event-active-pane-heal-applied reason=$reason " +
                            "pane=${activePane.paneId} session=${target.sessionName} tick=$tick",
                    )
                }
            }
        }
    }

    /**
     * Issue #869: ack-gate the submit Enter on the pasted composer text actually
     * landing in the agent's input, instead of the pre-#869 blind fixed sleep that
     * raced ahead of the TUI's ingestion ("I have to press Enter after"). Two parts:
     * (1) a MINIMUM floor (the #526 setting; Codex's [CODEX_AGENT_SUBMIT_DELAY_MS]
     * still applies) and (2) an ack poll of `capture-pane -p` for the payload,
     * pressing Enter the instant a capture CONFIRMS the paste (RTT-adaptive for
     * free). Bounded by [AGENT_SUBMIT_ACK_TIMEOUT_MS]; on a needle miss the Enter is
     * held to `max(minFloor, AGENT_SUBMIT_ACK_FALLBACK_FLOOR_MS + measuredRtt)` (a
     * WORKING delay, never the 150ms that raced), never a hung Send.
     *
     * Issue #1577b: the ack is COUNT-BASELINE-aware. [baselineNeedleCount] is the
     * payload needle's pre-paste occurrence count on the pane; the ack fires only
     * when the CURRENT count INCREASES over it (our paste added an occurrence), NOT
     * on mere presence. On a Codex pane whose status footer permanently renders the
     * command it wants (`Goal blocked (/goal resume)`), a presence-only ack matched
     * that footer INSTANTLY and fired Enter before Codex had read the paste — so on
     * a busy Codex the text and the CR landed in ONE stdin read batch and Codex's
     * paste-burst heuristic SWALLOWED the CR, leaving `/goal resume` unsubmitted.
     * Requiring an increase guarantees a REAL gap: Enter is sent only after the
     * typed text is confirmed ingested, so the CR lands in a separate read.
     */
    private suspend fun awaitAgentPasteIngestedBeforeSubmit(
        client: TmuxClient,
        paneId: String,
        payload: String,
        agent: AgentKind,
        baselineNeedleCount: Int,
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

        val ackTimeoutMs = agentSubmitAckTimeoutMsOverrideForTest ?: AGENT_SUBMIT_ACK_TIMEOUT_MS
        var poll = 0
        // The longest single `capture-pane` round-trip seen so far — the RTT
        // addend for the hardened fallback floor (#869).
        var maxCaptureRttMs = 0L
        var disconnectedDuringAck = false
        val ackObserved = withTimeoutOrNull(ackTimeoutMs.coerceAtLeast(1L)) {
            while (true) {
                if (client.disconnected.value) {
                    disconnectedDuringAck = true
                    return@withTimeoutOrNull false
                }
                val captureStartMs = agentSubmitNowMs()
                val visible = agentPaneShowsPayload(client, paneId, ackNeedle, baselineNeedleCount)
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
                    return@withTimeoutOrNull true
                }
                poll += 1
                delay(AGENT_SUBMIT_ACK_POLL_INTERVAL_MS)
            }
        } == true
        if (disconnectedDuringAck) return
        if (ackObserved) return

        // Codex input-freeze follow-up: the ack timeout must bound the WHOLE
        // capture loop, not just the number of polls. A single stuck
        // `capture-pane` on the busy control lane used to park this coroutine past
        // the advertised timeout, making Codex sends appear frozen. On timeout,
        // keep the #869 fallback floor, then let the caller send Enter rather than
        // blocking user input indefinitely.
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
    }

    /**
     * Issue #869/#1577b: `capture-pane -p` the pane and report whether OUR paste has
     * landed — i.e. the [needle]'s whitespace-stripped occurrence count now EXCEEDS
     * [baselineNeedleCount] (its pre-paste count). Both text and needle are
     * whitespace-stripped so a wrapped/reflowed input box still matches. A count
     * increase (not mere presence) is required so a payload the pane ALREADY showed
     * before the paste — a Codex `Goal blocked (/goal resume)` footer — does not
     * false-confirm the ack. A failed/empty capture is "not yet visible" so the
     * caller keeps polling; best-effort, never throws.
     */
    private suspend fun agentPaneShowsPayload(
        client: TmuxClient,
        paneId: String,
        needle: String,
        baselineNeedleCount: Int,
    ): Boolean {
        val response = runCatching {
            client.capturePaneTextViaExec(paneId)
        }.getOrNull() ?: return false
        if (response.isError) return false
        return agentSubmitVisibleTextNeedleCount(response.output, needle) > baselineNeedleCount
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
            val cachedEntry = sessionRecordedKindCache[pane.sessionId.trim()]
            val cachedSource = cachedEntry?.let {
                recordedSourceForCachedKind(
                    session = session,
                    sessionTarget = pane.sessionId,
                    cachedKind = it,
                )
            }
            agentRepository.detectRecordedSessionForPane(
                session = session,
                cwd = pane.cwd,
                paneTty = pane.paneTty,
                paneCommand = pane.currentCommand,
                recordedKind = currentDetection.agent,
                recordedSource = cachedSource ?: currentDetection.sourcePath,
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
        // Issue #962: a live agent detection just bound to [paneId]. If that
        // pane's session was recorded `@ps_agent_kind=shell` (a plain shell the
        // user/kind-picker classified as shell, with claude/codex/opencode then
        // started INSIDE it), the durable recorded-shell verdict
        // ([confirmedShellSessionIds]) would otherwise keep `presumedAgent` false
        // and collapse the Conversation toggle for the life of the session. The
        // AUTHORITATIVE live-detection event outranks the stale recorded shell, so
        // re-classify the session OUT of confirmed-shell — the symmetric
        // counterpart of `applyRecordedShellVerdict(isShell = false)`. Hard-cut
        // (D22): no "recorded shell wins forever" branch remains. A genuine shell
        // never reaches markAgentTailLive (no detection ever binds), so the #894
        // no-flap invariant is preserved.
        clearConfirmedShellOnLiveAgentDetection(paneId)
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
     * Issue #942 test seam: drive the production `%output` activity recorder for
     * [paneId] (the path the per-pane output collector calls on every live
     * `%output` chunk), stamping the pane as a STILL-STREAMING (wedged-but-alive)
     * channel. Lets a JVM test put a remembered/seeded agent's channel into the
     * wedged-but-alive state synthetically — without standing up a real `-CC`
     * stream — before injecting the empty `Resolved(null)` detection that #942
     * must not count toward agent-exit confirmation.
     */
    @androidx.annotation.VisibleForTesting
    internal fun recordPaneOutputActivityForTest(paneId: String) {
        recordVisiblePaneOutput(ControlEvent.Output(paneId, ByteArray(1)))
    }

    /**
     * Issue #942 test seam: clear [paneId]'s recorded `%output` activity so the
     * channel reads as IDLE (a genuinely-exited agent stops emitting output).
     * Lets a JVM test prove the genuine-exit case still tears the row down — the
     * wedged-channel guard must NOT over-protect a channel that went quiet.
     */
    @androidx.annotation.VisibleForTesting
    internal fun clearPaneOutputActivityForTest(paneId: String) {
        paneLastOutputAtMs.remove(paneId)
    }

    /**
     * Issue #1175 test seam: synthetically inject the "no seed has ever landed"
     * state for a pane by dropping its seed stamp, so a JVM test can drive the
     * `never_seeded` black-frame class deterministically (the connect flow otherwise
     * stamps a seed). Diagnostic accounting only — never touches render/heal state.
     */
    @androidx.annotation.VisibleForTesting
    internal fun clearPaneSeedStampForTest(paneId: String) {
        paneLastSeedAtMs.remove(paneId)
    }

    /**
     * Issue #1175 test seam: the recorded last-seed stamp for a pane, or null when no
     * seed has landed. Lets a test assert the successful-seed stamp is set (so
     * `msSinceLastSeed` is a real age, not the never-seeded -1 sentinel).
     */
    @androidx.annotation.VisibleForTesting
    internal fun paneLastSeedAtMsForTest(paneId: String): Long? = paneLastSeedAtMs[paneId]

    /**
     * Issue #942 test seam: true when [paneId]'s channel currently reads as
     * wedged-but-alive (recent `%output`). Lets a JVM test assert the signal
     * itself flips with activity vs idleness, independent of the routing.
     */
    @androidx.annotation.VisibleForTesting
    internal fun isChannelWedgedButAliveForTest(paneId: String): Boolean =
        isChannelWedgedButAlive(paneId)

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
     * Issue #819 (A2) test seam: drive [seedAgentConversationFromMemory]
     * directly for the row registered under [paneId]. Mirrors the pane-add
     * call in [applyParsedPanes] so a test can assert the reattach seed restores
     * the remembered window as a RESOLVING placeholder (detection-less,
     * [AgentConversationUiState.rememberedAgentPlaceholder] = true) — NOT the
     * remembered (possibly stale/sibling) `detection.sourcePath` rendered Live —
     * without standing up the SSH/JSONL detection round-trip.
     */
    @androidx.annotation.VisibleForTesting
    internal fun seedAgentConversationFromMemoryForTest(paneId: String) {
        val pane = paneRows[paneId] ?: return
        seedAgentConversationFromMemory(pane)
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
     * Issue #975 (B1, classify-miss) test seam: drive the REAL foreign-session
     * detection resolver against a supplied [session] for the row registered
     * under [paneId]. This exercises the actual
     * `resolveForeignKindGuess` → `AgentKindRemoteSource.classify` →
     * `AgentConversationRepository.detectLiveTranscriptForPane` chain (not a
     * synthetic `markAgentTailLive` injection), so a test using a fake SSH
     * session whose daemon classify returns `unknown` while a live `*.jsonl`
     * transcript is present can prove the masked-live-agent fallback binds a
     * detection (B1) and stays null for a genuine `none` shell (the no-flap
     * control). The pane must already be registered (e.g. via the test
     * connect/parse helper) and its session marked confirmed-shell.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun resolveForeignSessionDetectionForTest(
        paneId: String,
        session: SshSession,
    ): AgentDetection? {
        val pane = paneRows[paneId] ?: return null
        return resolveForeignSessionDetection(
            session = session,
            sessionTarget = pane.sessionId,
            pane = pane,
            cwd = pane.cwd,
            tty = pane.paneTty,
            command = pane.currentCommand,
        )
    }

    /**
     * Issue #975 (B1′, dedup ordering) test seam: true when a re-probe of
     * [paneId] would re-evaluate the daemon — i.e. the one-shot foreign-kind
     * guess cache for the pane's session is ABSENT (busted). Lets a JVM test
     * assert that [startAgentDetectionForPane] busts the cache for a
     * confirmed-shell pane BEFORE the dedup early-return, so a `claude` started
     * inside an already-detected shell pane (unchanged `(cwd, command, tty)`)
     * still forces a re-probe instead of keeping the stale "no agent" guess.
     */
    @androidx.annotation.VisibleForTesting
    internal fun foreignGuessIsCachedForTest(sessionId: String): Boolean =
        sessionForeignKindGuessCache.containsKey(sessionId.trim())

    /**
     * Issue #975 (B1′) test seam: seed the one-shot foreign-kind guess cache for
     * [sessionId] with a `unknown`-shaped (kind null) verdict, mirroring a daemon
     * classify that already cached "no agent" before the agent launched. A
     * subsequent [startAgentDetectionForPaneForTest] on a confirmed-shell pane of
     * that session must BUST this entry (the B1′ ordering fix) so the daemon
     * re-evaluates.
     */
    @androidx.annotation.VisibleForTesting
    internal fun seedForeignGuessCacheForTest(sessionId: String) {
        sessionForeignKindGuessCache.putIfAbsent(
            sessionId.trim(),
            ForeignKindGuessEntry(kind = null, isShell = false),
        )
    }

    /**
     * Issue #975 (B1′) test seam: drive [startAgentDetectionForPane] for the row
     * registered under [paneId]. With a fake [sessionRef] already set, a JVM test
     * can assert the cache-bust ordering without a full reconcile.
     */
    @androidx.annotation.VisibleForTesting
    internal fun startAgentDetectionForPaneForTest(paneId: String) {
        val pane = paneRows[paneId] ?: return
        startAgentDetectionForPane(pane)
    }

    /**
     * Issue #975 test seam: install [session] as the active [sessionRef] so a
     * JVM test can drive the detection chain ([startAgentDetectionForPane] /
     * [resolveForeignSessionDetectionForTest]) against a fake SSH session that
     * models the masked-live-agent host (daemon classify `unknown` + a live
     * transcript). Mirrors the production assignment in the connect/reconnect
     * path without standing up a real SSH transport.
     */
    @androidx.annotation.VisibleForTesting
    internal fun setSessionRefForTest(session: SshSession?) {
        sessionRef = session
    }

    /**
     * Issue #975 (B2, reattach re-stamp) test seam: register a REMEMBERED-agent
     * resolving placeholder (#819 A2: detection-less,
     * [AgentConversationUiState.rememberedAgentPlaceholder] = true) for [paneId],
     * mirroring what [seedAgentConversationFromMemory] restores on a beyond-grace
     * reattach. Lets a JVM test prove [applyRecordedShellVerdict] (`isShell=true`)
     * does NOT tear it down on the reconnect re-read — the toggle survives.
     */
    @androidx.annotation.VisibleForTesting
    internal fun seedRememberedAgentPlaceholderForTest(paneId: String) {
        _agentConversations.update { conversations ->
            conversations + (paneId to AgentConversationUiState(
                detection = null,
                events = emptyList(),
                selectedTab = SessionTab.Conversation,
                syncStatus = AgentConversationSyncStatus.Live,
                loadState = ConversationLoadState.Loading,
                rememberedAgentPlaceholder = true,
            ))
        }
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

    // Issue #1586: RawBytes lane rides the agent lane's verify-before-resend ledger (H1b).
    private suspend fun writeInputToPaneResult(
        client: TmuxClient,
        paneId: String,
        bytes: ByteArray,
    ): Result<Unit> = deliverRawInputWithGuard(
        ledger = outboundDeliveryLedger,
        client = client,
        paneId = paneId,
        bytes = bytes,
        localRenderText = localRenderTextForPane(paneId),
        send = { c, p, b -> sendInputBytesToPane(c, p, b) },
        // Issue #1586 (H1b): AlreadyLanded -> Enter-only submit (agent-lane parity).
        submitEnter = { c, p ->
            sendNamedKeyToPane(c, p, "Enter").throwIfTmuxError("submit typed shell input")
        },
        afterDelivered = { c, p, b ->
            if (BracketedPaste.containsLineBreak(b)) requestReconcile(c, p, ReconcileReason.Send)
        },
    )

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
                        val targetClient = client ?: clientRef
                        if (targetClient == null) {
                            DiagnosticEvents.record(
                                "connection",
                                "pane_input_send_deferred",
                                "pane" to paneId,
                                "bytes" to batch.bytes.size,
                                "reason" to "no_current_client",
                            )
                            newQueue.requeueFront(batch)
                            delay(TMUX_INPUT_SEND_RETRY_DELAY_MS)
                            continue
                        }
                        DiagnosticEvents.record(
                            "action",
                            "pane_input_batch",
                            "pane" to paneId,
                            "bytes" to batch.bytes.size,
                        )
                        val sent = sendDequeuedInputBatch(
                            client = targetClient,
                            paneId = paneId,
                            batch = batch,
                            queue = newQueue,
                        )
                        if (!sent) {
                            delay(TMUX_INPUT_SEND_RETRY_DELAY_MS)
                        }
                    }
                }
            }
        }
        return TmuxPaneInputStream(paneId, queue)
    }

    // Issue #1526 S1: extracted to [deliverDequeuedInputBatch] (OutboundDeliveryGuard.kt)
    // with the blind attempt-2 retry (audit B2) made probe-gated (verify-before-resend).
    private suspend fun sendDequeuedInputBatch(
        client: TmuxClient,
        paneId: String,
        batch: TmuxPaneInputBatch,
        queue: TmuxPaneInputQueue,
    ): Boolean = deliverDequeuedInputBatch(
        client = client,
        paneId = paneId,
        batch = batch,
        queue = queue,
        currentClient = { clientRef },
        sendBytes = { c, p, b -> sendInputBytesToPane(c, p, b) },
        onPersistentFailureOfCurrentClient = { event ->
            handlePassiveClientDisconnect(client = client, disconnectEvent = event)
        },
    )

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
        // Issue #1586 (H1a): a tmux `%error` (dead pane) must FAIL the send, not false-succeed.
        for (token in inputTokens(bytes)) {
            when (token) {
                is TmuxInputToken.Literal ->
                    if (token.text.isNotEmpty()) {
                        sendLiteralTextKeys(client, paneId, token.text)
                            .throwIfTmuxError("type into $paneId")
                    }
                is TmuxInputToken.NamedKey ->
                    sendNamedKeyToPane(client, paneId, token.name)
                        .throwIfTmuxError("send ${token.name} to $paneId")
            }
        }
    }

    private suspend fun ensurePaneAcceptsInput(client: TmuxClient, paneId: String) {
        if (paneRows[paneId]?.inCopyMode != true) return
        sendCancelCopyMode(client, paneId)
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
                sendNamedKeyToPane(client, paneId, key)
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
        // Issue #1169: record the current on-screen viewport grid as the floor
        // for every size we subsequently send to tmux. This is set on EVERY real
        // Compose measure (before the no-op short-circuit below) so it always
        // tracks the true emulator viewport; the floor guard in
        // [maybeRefreshControlClientSize] then keeps a warm reattach / cached
        // replay / session switch from shrinking the tmux window below it.
        liveMeasuredColumns = columns
        liveMeasuredRows = rows
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

    /**
     * Issue #1169: the size to send to tmux is the FLOOR-GUARDED, re-derived
     * grid — never smaller than the live on-screen viewport
     * ([liveMeasuredColumns]/[liveMeasuredRows]). The cached remoteColumns/
     * remoteRows a warm reattach / session switch / within-grace foreground
     * replays can be a stale or transiently-shrunk value; the live measured grid
     * is the authority for how tall the emulator will actually render. Sending
     * the LARGER of the two guarantees the tmux window (and therefore the
     * alt-screen agent TUI) fills the viewport with no top-rows-then-black cut,
     * and because `window-size latest` makes the phone authoritative, a second
     * client (Terminus) attached to the same window inherits the full size too.
     * A too-small window is the reported bug; a window >= the view is safe.
     */
    private fun effectiveControlClientColumns(): Int = maxOf(remoteColumns, liveMeasuredColumns)

    private fun effectiveControlClientRows(): Int = maxOf(remoteRows, liveMeasuredRows)

    private fun maybeRefreshControlClientSize() {
        val client = clientRef ?: return
        val target = activeTarget ?: return
        val cols = effectiveControlClientColumns()
        val rows = effectiveControlClientRows()
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
            // Issue #1169: re-derive the floor-guarded size; a mid-flight change
            // to either the cached target or the live measured viewport
            // invalidates this in-flight refresh (a newer one will run).
            if (effectiveControlClientColumns() != cols || effectiveControlClientRows() != rows) {
                return@launch
            }
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
            //    post-reflow, and [healActivePaneIfStaleRender] -> [toTerminalViewportBytes]
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
     * fresh `capture-pane` (which [healActivePaneIfStaleRender] -> [toTerminalViewportBytes]
     * replays as a full clear+repaint) — but ONLY when the pane is actually
     * blank/suspect, so a routine keyboard toggle on an already-painted pane stays
     * a no-op (no capture). Keyed to the target session id via the runtime guard,
     * so a late heal can never paint a switched-away session. No `refresh-client`
     * is issued because the grid dims did not change.
     */
    private fun maybeHealActivePaneOnNoOpResize(client: TmuxClient, target: ConnectionTarget) {
        if (client.disconnected.value) return
        val activePane = activeVisiblePane() ?: return
        // Only pay for a capture when the active pane looks lost. A normally-painted pane needs
        // no heal — this keeps the no-op resize cheap.
        val blank = activePane.terminalState.visibleScreenIsBlank()
        val partialBlank = activePane.terminalState.visibleScreenIsPartiallyBlank()
        // Issue #1176 (GAP C): a keyboard/resize toggle can leave the idle agent's redraw as a
        // >3-line half-black BAND (the GAP-A dead-zone) that reads NON-blank and NON-partial-black
        // locally — the pre-#1176 gate SKIPPED it and left the band until the ~4s watchdog. Widen
        // the local capture-gate to [renderLooksSuspect] (a superset of blank/partial
        // that also catches the band), then route the non-blank band through the unified
        // capture-diff oracle so a correct-but-sparse pane never flickers.
        val suspect = activePane.terminalState.renderLooksSuspect()
        if (!suspect) return
        val attachGeneration = connectGeneration
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-noop-resize-active-pane-heal pane=${activePane.paneId} " +
                "window=${activePane.windowId} session=${target.sessionName} " +
                "blank=$blank partialBlank=$partialBlank",
        )
        bridgeScope.launch {
            if (clientRef !== client) return@launch
            val guard = RuntimeRefreshGuard(
                generation = attachGeneration,
                target = target,
                client = client,
            )
            if (blank || partialBlank) {
                // Fully-blank / ≤3-line partial-black: the existing UNCONDITIONAL full-viewport
                // restore (forces a `C-l` repaint, then re-captures tmux's authoritative grid).
                reseedActivePaneForReattach(guard)
            } else {
                // Dead-zone half-black BAND: confirm against tmux's capture with the unified
                // oracle and heal ONLY if the frame is truly lost — no clear-to-repaint flicker
                // on a legitimately-sparse-but-correct pane (capture ≈ render → no heal).
                healActivePaneIfStaleRender(client, activePane, guard)
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
    ): String = tmuxControlClientSizeMessage(
        event = event,
        target = target,
        columns = columns,
        rows = rows,
        milestone = activeAttachMilestone,
        detail = detail,
    )

    private fun tmuxCommandErrorDetail(
        error: Throwable?,
        response: CommandResponse?,
    ): String = tmuxCommandErrorDetailText(error, response)

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
                    // Issue #976: a refused launch (e.g. name-collision guard in
                    // the gateway) must FAIL VISIBLY — never silently swallow it,
                    // or the user is left wondering why nothing happened. The
                    // launch line was NOT sent into the current pane; tell them
                    // why so they can retry once the session list is known. We do
                    // NOT call onResolved here, so no navigation/attach to a wrong
                    // session occurs.
                    _sessionCreateError.tryEmit(
                        error.message?.takeIf { it.isNotBlank() }
                            ?: "Couldn't create the session.",
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

    // #1496 exec off -CC (D22)
    private fun sendLifecycleCommand(command: String) {
        val client = clientRef ?: return
        bridgeScope.launch { runCatching { client.sendLifecycleViaExec(command) } }
    }

    /**
     * Translate a key-bar label (`Esc`, `Tab`, `‹`, `⌃`, ...) into a tmux `send-keys`
     * named-key argument, then dispatch it.
     *
     * Mirrors the byte-level mapping in `SessionViewModel.unmodifiedBytesFor`
     * — we map to tmux's named-key vocabulary rather than the literal
     * escape sequence because `send-keys` understands them directly and
     * because tmux owns the per-pane terminfo, which means letting tmux
     * choose the cursor-key encoding is more correct than us baking
     * ESC[A in here.
     */
    /**
     * Issue #1091: the sticky `Ctrl` modifier state, surfaced so the hotkeys
     * panel can render the active (accent) treatment. `Off` -> not armed;
     * `OneShot` -> the next composable key is sent as a control char then the
     * modifier auto-releases; `Locked` -> stays armed until tapped off.
     */
    private val _ctrlModifier = MutableStateFlow(KeyModifierState.Off)
    public val ctrlModifier: StateFlow<KeyModifierState> = _ctrlModifier.asStateFlow()

    /**
     * Issue #1091: cycle the sticky `Ctrl` modifier on each tap of the `Ctrl`
     * key — `Off -> OneShot -> Locked -> Off`. A single tap arms it for the
     * next key; a second consecutive tap (the "double tap") locks it on; a
     * third tap releases it. Matches the `docs/input-methods.md` key-bar
     * modifier spec.
     */
    public fun onCtrlModifierTap() {
        _ctrlModifier.value = when (_ctrlModifier.value) {
            KeyModifierState.Off -> KeyModifierState.OneShot
            KeyModifierState.OneShot -> KeyModifierState.Locked
            KeyModifierState.Locked -> KeyModifierState.Off
        }
    }

    public fun onKeyBarKey(paneId: String, label: String) {
        // Issue #1091: the lone-`Ctrl` modifier is BACK (it was removed in #784)
        // — but now as a real sticky modifier that composes with the panel's
        // LETTERS section so the maintainer can send `Ctrl+<any key>` (the
        // `nano`-trapped report). Direct one-tap control buttons (incl. the
        // newly-filled `^X`/`^O`/`^K`/…) stay too; both routes go through the
        // same `send-keys -H` overlay below — no new transport.
        DiagnosticEvents.record(
            "action",
            "shortcut_sent",
            "mode" to "tmux",
            "paneId" to paneId,
            "key" to label,
        )

        // Issue #1091: tapping the `Ctrl` modifier only cycles the sticky state
        // (no byte is sent — it decorates the NEXT key), like the key-bar spec.
        if (label == TmuxHotkeyCtrlModifierLabel) {
            onCtrlModifierTap()
            return
        }

        // Issue #1091: a single composable char (the LETTERS section, a–z and
        // the caret-range symbols). With `Ctrl` armed it is sent as its control
        // byte (`Ctrl+<letter>`); with `Ctrl` off it is typed literally. A
        // OneShot modifier auto-releases after the key; Locked persists.
        val composable = singleControlComposableChar(label)
        if (composable != null) {
            val armed = _ctrlModifier.value
            if (armed != KeyModifierState.Off) {
                controlByteForChar(composable)?.let { sendControlInputToPane(paneId, it) }
                if (armed == KeyModifierState.OneShot) {
                    _ctrlModifier.value = KeyModifierState.Off
                }
            } else {
                writeInputToPane(paneId, composable.toString().toByteArray(Charsets.UTF_8))
            }
            return
        }

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
            // Issue #1091: the control keys nano (and many TUIs) need that were
            // missing — `^G` `^J` `^K` `^T` `^U` `^W` `^\`. Direct one-tap
            // buttons routed through the same `send-keys -H` overlay path.
            "^G", "Ctrl-G" -> {
                sendControlInputToPane(paneId, CtrlGByte)
                null
            }
            "^J", "Ctrl-J" -> {
                sendControlInputToPane(paneId, CtrlJByte)
                null
            }
            "^K", "Ctrl-K" -> {
                sendControlInputToPane(paneId, CtrlKByte)
                null
            }
            "^T", "Ctrl-T" -> {
                sendControlInputToPane(paneId, CtrlTByte)
                null
            }
            "^U", "Ctrl-U" -> {
                sendControlInputToPane(paneId, CtrlUByte)
                null
            }
            "^W", "Ctrl-W" -> {
                sendControlInputToPane(paneId, CtrlWByte)
                null
            }
            "^\\", "Ctrl-\\" -> {
                sendControlInputToPane(paneId, CtrlBackslashByte)
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

    /** Issue #1169 test seam: the live measured viewport floor. */
    internal fun liveMeasuredDimensionsForTest(): Pair<Int, Int> =
        liveMeasuredColumns to liveMeasuredRows

    /**
     * Issue #1169 test seam: faithfully reproduce the cached-runtime size replay
     * of a warm reattach / within-grace foreground return / session switch. This
     * is exactly what [restoreCachedRuntime] (`remoteColumns/remoteRows =
     * runtime.remoteColumns/remoteRows; resetControlClientSizeForAttach()`) plus
     * [launchCachedRuntimeRemoteRefresh] (`maybeRefreshControlClientSize()`) do —
     * it sets the cached (possibly shrunk) target, resets the applied cache, and
     * re-drives the control-client size. On base this REPLAYS the small cached
     * value and shrinks the tmux window; with the floor guard it re-derives the
     * full live viewport so the window is never left cut. A passthrough — no
     * test-only behavior.
     */
    @androidx.annotation.VisibleForTesting
    internal fun replayCachedControlClientSizeForTest(cachedColumns: Int, cachedRows: Int) {
        remoteColumns = cachedColumns
        remoteRows = cachedRows
        resetControlClientSizeForAttach()
        maybeRefreshControlClientSize()
    }

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
        // Issue #1085 (F2): close the evicted cached runtimes OFF the Main
        // thread. This park path also runs from `onCleared` (Main); closing the
        // runtimes synchronously parked Main on a wedged socket. The live
        // runtime stays parked in the cache; only the evicted ones are closed.
        deferConnectionTeardownOffMain(
            clientToDetach = null,
            runtimesToClose = deactivateCurrentRuntimeToCache(),
            leaseToRelease = null,
            sessionToClose = null,
        )
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

    /**
     * Issue #1085 (F2): hand the SLOW connection-teardown IO to the
     * application-scoped [teardownScope] so the calling `onCleared()` returns
     * IMMEDIATELY instead of parking the Main thread.
     *
     * The work — the tmux `detach-client` round-trip + local client close, the
     * cached-runtime closes, and the warm-lease refcount-- ([SshLease.release])
     * or raw-[SshSession] close — is exactly what [closeCurrentConnection] used
     * to run synchronously via three `runBlocking(Dispatchers.IO)` blocks on
     * Main. Each step keeps its prior bounded ceiling
     * ([SYNC_DETACH_TIMEOUT_MS]); the close SEMANTICS are unchanged (same
     * detach, same per-runtime close, same lease release, same ordering) — only
     * the THREAD moves off Main.
     *
     * Correctness: every reference is captured + nulled on Main by the caller
     * BEFORE the hand-off, so there is no double-release and the VM's fields
     * never observe a half-torn-down connection. [teardownScope] outlives this
     * VM and is never cancelled, so the teardown runs to COMPLETION (refcount
     * decremented, sockets closed) even though the VM is already gone — no leak.
     * Each step is `runCatching`-wrapped so a wedged close never escapes.
     */
    private fun deferConnectionTeardownOffMain(
        clientToDetach: TmuxClient?,
        runtimesToClose: List<CachedTmuxRuntime>,
        leaseToRelease: SshLease?,
        sessionToClose: SshSession?,
    ) {
        if (clientToDetach == null &&
            runtimesToClose.isEmpty() &&
            leaseToRelease == null &&
            sessionToClose == null
        ) {
            return
        }
        teardownScope.launch {
            runConnectionTeardown(
                clientToDetach = clientToDetach,
                runtimesToClose = runtimesToClose,
                leaseToRelease = leaseToRelease,
                sessionToClose = sessionToClose,
            )
        }
    }

    /**
     * The actual SLOW connection-teardown body (#1085 F2): the tmux
     * `detach-client` round-trip + local client close, the evicted cached-runtime
     * closes, and the warm-lease refcount-- ([SshLease.release]) or raw-session
     * close. Each step keeps its bounded ceiling ([SYNC_DETACH_TIMEOUT_MS]) and
     * is `runCatching`-wrapped so a wedged close never escapes.
     *
     * Production runs this off the Main thread via [deferConnectionTeardownOffMain]
     * ([teardownScope]); the synchronous `replaceClientForTest` test seam runs it
     * inline via `runBlocking` so the replaced client is closed before the seam
     * returns. The body is identical either way — only the THREAD differs.
     */
    private suspend fun runConnectionTeardown(
        clientToDetach: TmuxClient?,
        runtimesToClose: List<CachedTmuxRuntime>,
        leaseToRelease: SshLease?,
        sessionToClose: SshSession?,
    ) {
        if (clientToDetach != null) {
            // Issue #215: notify tmux server-side before the local close so
            // the `-CC` control client does not linger as an orphan. Bounded
            // so a wedged socket cannot stall the teardown.
            runCatching {
                withTimeoutOrNull(SYNC_DETACH_TIMEOUT_MS) {
                    clientToDetach.detachCleanly(timeoutMs = SYNC_DETACH_TIMEOUT_MS)
                }
            }
            // [detachCleanly] already invokes [close]; this is a no-op when
            // it ran and the real teardown when the detach hop failed.
            runCatching { clientToDetach.close() }
        }
        closeCachedRuntimesBounded(runtimesToClose)
        if (leaseToRelease != null) {
            runCatching {
                withTimeoutOrNull(SYNC_DETACH_TIMEOUT_MS) { leaseToRelease.release() }
            }
        } else if (sessionToClose != null) {
            runCatching { sessionToClose.close() }
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
        // Issue #1072: cancel-and-join any in-flight attachment upload FIRST, before
        // we touch the SSH transport. An upload is a free-floating writer blocked in
        // `output.write`/`command.join` on the `-CC` session we are about to drop;
        // if we tore the transport down while it was still streaming, its teardown
        // (`dispatcher.run { command.close() }`) would race this close draining the
        // SAME dispatcher — the race that left the reconnect single-flight guards
        // wedged and required an app restart. Cancelling it here makes the upload
        // unwind deterministically (its `runInterruptible` is interrupted) so the
        // reconnect ladder never races it.
        attachmentUploadJob?.cancelAndJoin()
        attachmentUploadJob = null
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
        layoutChangeCoalescer?.stop()
        layoutChangeCoalescer = null
        layoutCoalescerScope?.cancel()
        layoutCoalescerScope = null
        outputOverflowJob?.cancelAndJoin()
        outputOverflowJob = null
        disconnectedJob?.cancelAndJoin()
        disconnectedJob = null
        projectRootsJob?.cancelAndJoin()
        projectRootsJob = null
        _projectRoots.value = emptyList()
        val producerJobsToJoin = paneProducerJobs.values.toList()
        val outputActivityJobsToJoin = paneOutputActivityJobs.values.toList()
        val portDetectorJobsToJoin = panePortDetectorJobs.values.toList()
        val agentJobsToJoin = paneAgentJobs.values.toList()
        val inputJobsToJoin = paneInputJobs.values.toList()
        for (job in producerJobsToJoin) job.cancelAndJoin()
        for (job in outputActivityJobsToJoin) job.cancelAndJoin()
        for (job in portDetectorJobsToJoin) job.cancelAndJoin()
        for (job in agentJobsToJoin) job.cancelAndJoin()
        for (job in inputJobsToJoin) job.cancelAndJoin()
        for ((_, queue) in paneInputQueues) {
            queue.close()
        }
        paneAgentJobs.clear()
        paneAgentTailGenerations.clear()
        paneAgentInputs.clear()
        paneAgentNullDetections.clear()
        paneLastOutputAtMs.clear()
        paneLastSeedAtMs.clear()
        paneConversationOpenStartedAtMs.clear()
        sessionRecordedKindCache.clear()
        sessionForeignKindGuessCache.clear()
        panePortDetectorJobs.clear()
        panePortDetectorClients.clear()
        panePortDetectorGenerations.clear()
        // Issue #894 (Slice C): drop the durable confirmed-shell verdicts so a
        // different session never inherits a stale shell verdict.
        confirmedShellSessionIds.clear()
        _confirmedShellPaneIds.value = emptySet()
        // Issue #1158: drop the sticky alt-buffer agent latch with the other
        // per-runtime caches so a different session never inherits a stale verdict.
        altBufferAgentSessionIds.clear()
        _altBufferAgentPaneIds.value = emptySet()
        _agentConversations.value = emptyMap()
        // Issue #959 (#780 synthetic-injection seam): when the test forces the
        // pane-runtime-survives-teardown race, KEEP paneRows + their producers +
        // input queues + the paneProducerClients bindings (still pointing at the
        // now-dead client) so the foreground reattach reconcile REUSES them and
        // must re-bind. Production ALWAYS clears (the common no-freeze path).
        if (!preservePaneRuntimeOnBackgroundTeardownForTest) {
            paneInputJobs.clear()
            paneInputQueues.clear()
            paneProducerJobs.clear()
            // Issue #959: drop stale per-pane client bindings so a fresh reconnect
            // re-binds every reused pane's producer/input to the new client.
            paneProducerClients.clear()
            paneOutputActivityJobs.clear()
            for ((_, row) in paneRows) {
                runCatching { row.terminalState.detachExternalProducer() }
            }
            paneRows.clear()
            _panes.value = emptyList()
        }
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
        val toDetach = clientRef
        connectionTmuxPort.setClient(null)
        runCatching { toDetach?.detachCleanly() }
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
    private fun closeCurrentConnection(deferTeardown: Boolean = true) {
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
        for ((_, job) in panePortDetectorJobs) {
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
        paneLastOutputAtMs.clear()
        paneLastSeedAtMs.clear()
        paneConversationOpenStartedAtMs.clear()
        sessionRecordedKindCache.clear()
        sessionForeignKindGuessCache.clear()
        // Issue #894 (Slice C): drop the durable confirmed-shell verdicts so a
        // different session never inherits a stale shell verdict.
        confirmedShellSessionIds.clear()
        _confirmedShellPaneIds.value = emptySet()
        // Issue #1158: drop the sticky alt-buffer agent latch with the other
        // per-runtime caches so a different session never inherits a stale verdict.
        altBufferAgentSessionIds.clear()
        _altBufferAgentPaneIds.value = emptySet()
        paneInputJobs.clear()
        paneInputQueues.clear()
        _agentConversations.value = emptyMap()
        paneProducerJobs.clear()
        // Issue #959: drop stale per-pane client bindings so a fresh reconnect
        // re-binds every reused pane's producer/input to the new client.
        paneProducerClients.clear()
        paneOutputActivityJobs.clear()
        for ((_, row) in paneRows) {
            runCatching { row.terminalState.detachExternalProducer() }
        }
        paneRows.clear()
        _panes.value = emptyList()
        rebuildUnifiedPanes()
        // Issue #1085 (F2): the SLOW teardown IO — the tmux `detach-client`
        // round-trip + local client close, the evicted cached-runtime closes,
        // and the warm-lease refcount-- / raw-session close — used to run here
        // on the Main thread via three `runBlocking(Dispatchers.IO)` blocks.
        // Each block's coroutine timeout was DEFEATED by the underlying
        // non-suspending nested-`runBlocking` socket close (a coroutine cancel
        // cannot interrupt a thread parked in nested `runBlocking`), so on a
        // wedged transport the real Main park was the SUM of the per-resource
        // ceilings — a multi-second / ANR-class freeze finishing a session. We
        // capture the references, null the VM fields on Main (no double-release,
        // no half-torn-down state observed), and hand the actual closes to the
        // application-scoped [teardownScope] so `onCleared` returns immediately
        // while the teardown still runs to completion off-Main.
        val toDetach = clientRef
        if (toDetach != null) {
            connectionTmuxPort.setClient(null)
        }
        clientRef = null
        unregisterCurrentClient()
        val runtimesToClose = closingHostId?.let { hostId ->
            runtimeCache.removeHost(hostId)
        } ?: emptyList()
        val leaseToRelease = leaseRef
        val sessionToClose = sessionRef
        sessionRef = null
        leaseRef = null
        if (deferTeardown) {
            // Production `onCleared` path: hand the slow detach/close to the
            // off-Main [teardownScope] so `onCleared` returns immediately
            // (#1085 F2). Byte-identical to the pre-test-isolation behavior.
            deferConnectionTeardownOffMain(
                clientToDetach = toDetach,
                runtimesToClose = runtimesToClose,
                leaseToRelease = leaseToRelease,
                sessionToClose = sessionToClose,
            )
        } else {
            // Synchronous `replaceClientForTest` test seam: run the SAME
            // teardown body inline so the replaced client is closed before the
            // seam returns. Each step is internally bounded
            // ([SYNC_DETACH_TIMEOUT_MS]) so a wedged fake cannot hang it, and it
            // is scope-independent (does not depend on a test-injected teardown
            // dispatcher being advanced).
            runBlocking {
                runConnectionTeardown(
                    clientToDetach = toDetach,
                    runtimesToClose = runtimesToClose,
                    leaseToRelease = leaseToRelease,
                    sessionToClose = sessionToClose,
                )
            }
        }
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
        // Issue #1158: copied from `#{alternate_on}` — the SERVER-TRUTH
        // alternate-screen-buffer flag. `true` while the pane's program holds the
        // DEC-1049 alternate screen (a full-screen agent TUI for its whole run),
        // `false` for a plain shell at a prompt. Drives the detection-independent
        // alt-buffer agent latch that restores the Conversation tab for an agent
        // launched directly inside a `@ps_agent_kind=shell` session. Defaults
        // false for older tmux / tests that omit the field.
        val alternateOn: Boolean = false,
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
    ) {
        // Issue #1575: identity equality EXCLUDES the secret `passphrase` (sibling helpers).
        override fun equals(other: Any?): Boolean = connectionTargetIdentityEquals(this, other)

        override fun hashCode(): Int = connectionTargetIdentityHashCode(this)
    }

    /**
     * Issue #972 — wire the host connection-log mirror to its trigger. Fired by the
     * [ConnectionEffectDriver] right after the current host's lease transport comes
     * back `Up` (a reconnect promoted the controller to Live): mirror the recorded
     * reconnect-cause trail ([DiagnosticRecorder.connectionLogJsonl]) to the host's
     * `~/.pocketshell/connection-log.jsonl` over the now-warm lease, so the
     * maintainer can attribute the just-completed drop in the in-app file viewer
     * with no adb (#969 part 3 was a tested-but-unwired writer; this is the wiring).
     *
     * FAIL-SOFT all the way: a missing recorder, no active target, a blank trail,
     * or any host-write error is a silent no-op — the mirror NEVER perturbs the
     * live connection. The write runs on [viewModelScope] off the driver's collect
     * so the transport-edge collector is never blocked by the host round-trip.
     */
    private fun mirrorConnectionLogToHost() {
        val recorder = diagnosticRecorder ?: return
        val target = activeTarget ?: return
        val leaseTarget = target.toLeaseSessionTarget()
        viewModelScope.launch {
            // runCatching is belt-and-braces so a surprise never escapes onto
            // viewModelScope; the shared body is itself fail-soft.
            runCatching { mirrorConnectionLogToHostBody(recorder, leaseTarget) }
        }
    }

    /**
     * The fail-soft mirror body shared by the production fire-and-forget
     * [mirrorConnectionLogToHost] and the connected-test seam
     * [mirrorConnectionLogToHostForTest]. Reads the recorder's reconnect-cause
     * trail and, when non-blank, writes it to the host over the warm lease via
     * [com.pocketshell.app.diagnostics.ConnectionLogHostMirror] (itself
     * `Result.failure` fail-soft, never a throw except cancellation). Returns the
     * mirror's [Result] (the absolute remote path on success, `null` when the
     * trail was blank so no write happened).
     */
    private suspend fun mirrorConnectionLogToHostBody(
        recorder: com.pocketshell.app.diagnostics.DiagnosticRecorder,
        leaseTarget: com.pocketshell.app.sessions.LeaseSessionTarget,
    ): Result<String?> {
        val jsonl = runCatching { recorder.connectionLogJsonl() }.getOrNull().orEmpty()
        if (jsonl.isBlank()) return Result.success(null)
        return com.pocketshell.app.diagnostics.ConnectionLogHostMirror.mirror(
            leaseManager = sshLeaseManager,
            target = leaseTarget,
            jsonl = jsonl,
        )
    }

    /**
     * Issue #972 connected-test seam. Drives the EXACT production mirror glue —
     * `activeTarget` → [toLeaseSessionTarget] (the byte-identical lease-key
     * mapping) → the warm-lease write — synchronously and returns the
     * [ConnectionLogHostMirror][com.pocketshell.app.diagnostics.ConnectionLogHostMirror]
     * `Result` so a Docker journey can assert the host file actually lands over
     * the warm lease (a wrong lease-key field mapping would dial a fresh handshake
     * or fail outright — the exact "wired but the host file never lands" regression
     * this issue closes). Returns `Result.failure` when there is no recorder or no
     * active target, exactly like the fire-and-forget production path returns early.
     */
    @androidx.annotation.VisibleForTesting
    internal suspend fun mirrorConnectionLogToHostForTest(): Result<String?> {
        val recorder = diagnosticRecorder
            ?: return Result.failure(IllegalStateException("no diagnosticRecorder"))
        val target = activeTarget
            ?: return Result.failure(IllegalStateException("no activeTarget"))
        return mirrorConnectionLogToHostBody(recorder, target.toLeaseSessionTarget())
    }

    // EPIC #687 Slice 1 (#1047): `reduceBackground()` is DELETED. The background
    // pause-vs-detach decision now lives in the connection core ([BackgroundEffects], the
    // SINGLE background authority), fed the injected `hasLiveControlChannel` liveness the
    // controller's display state lacks; the inline selector was the second decision authority
    // D28 exists to end (D22 hard-cut — no shadow).

    // EPIC #687 Slice 0 (#1047): `reduceForeground()` is DELETED. The beyond-grace
    // foreground replay-vs-resume decision now lives in the connection core
    // ([ForegroundReturnEffects], the SINGLE foreground authority); the inline selector
    // was the second decision authority D28 exists to end (D22 hard-cut — no shadow).

    // EPIC #687 Slice 2 (#1047): `classifyPassiveTransportDrop()` is DELETED. The passive
    // `-CC` drop classification now lives in the connection core
    // ([PassiveTransportDropEffects], the SINGLE passive-drop authority), fed the live
    // `clientRef` identity / `activeTarget`/`connectingTarget` / in-app-navigation context
    // the controller's display state lacks; the inline selector was the second decision
    // authority D28 exists to end (D22 hard-cut — no shadow). The status-agnostic #895/#766
    // contract + the #635 client-identity guard now live in [selectPassiveDropArm].

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
        connectionStatusHostPortUserFor(
            inlineState = inlineState,
            activeTarget = activeTarget,
            connectingTarget = connectingTarget,
        )

}
