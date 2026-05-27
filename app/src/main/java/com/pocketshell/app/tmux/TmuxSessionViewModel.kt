package com.pocketshell.app.tmux

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.di.CommandPlannerClientFactory
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.OPTIMISTIC_USER_MESSAGE_ID_PREFIX
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.session.VoiceCommandReviewUiState
import com.pocketshell.app.session.reconcileAgentEvents
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.DEFAULT_TMUX_START_DIRECTORY
import com.pocketshell.app.sessions.resolveTmuxSessionCreation
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.core.voice.CommandPlannerException
import com.pocketshell.core.voice.CommandPlannerRequest
import com.pocketshell.core.voice.CommandPlannerSafetyConstraints
import com.pocketshell.core.voice.CommandPlannerSessionMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
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
    private val commandPlannerClientFactory: CommandPlannerClientFactory =
        CommandPlannerClientFactory { null },
    private val projectRootDao: ProjectRootDao? = null,
) : ViewModel() {

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

    // Issue #197: the pane the conversation composer currently sends to.
    // Set when the user explicitly selects the Conversation tab for a pane
    // with a detected agent; cleared when they switch back to the Terminal
    // tab. While non-null, the screen renders the conversation pane for
    // this pane regardless of the pager's current page, so navigating to a
    // sibling window via the WindowStrip does NOT silently move the send
    // target to a pane that has no agent. The mismatch between this
    // locked pane and the currently-visible pane (when they differ) is
    // surfaced as the "Agent is on Window N, not the current window."
    // banner in [TmuxSessionScreen]. Cleared on connection teardown via
    // [closeCurrentConnection*].
    private val _lockedConversationPaneId: MutableStateFlow<String?> =
        MutableStateFlow(null)

    public val lockedConversationPaneId: StateFlow<String?> =
        _lockedConversationPaneId.asStateFlow()

    // Issue #197: set of (paneId) keys for which the user has already
    // acknowledged the first-send confirmation banner. We track at
    // pane granularity rather than session-name granularity so a second
    // Claude pane in the same tmux session also gets its own one-time
    // confirmation — different panes are different conceptual targets.
    // Survives the ViewModel lifetime (process lifetime of the
    // foreground tmux session); cleared on connection teardown.
    private val _firstSendConfirmedPanes: MutableStateFlow<Set<String>> =
        MutableStateFlow(emptySet())

    public val firstSendConfirmedPanes: StateFlow<Set<String>> =
        _firstSendConfirmedPanes.asStateFlow()

    private val _connectionStatus: MutableStateFlow<ConnectionStatus> =
        MutableStateFlow(ConnectionStatus.Idle)

    /** Coarse-grained status the screen surfaces above the terminal. */
    public val connectionStatus: StateFlow<ConnectionStatus> =
        _connectionStatus.asStateFlow()

    // Voice Command-mode planner state — mirrors
    // [com.pocketshell.app.session.SessionViewModel]'s voice planner so the
    // tmux route (host tap → tmux picker → "Attach to session") gets the
    // same dictate-then-review affordance as the raw-SSH route. The pending
    // plan is screen-global, not per-pane, because the user reviews and
    // approves one transcript at a time; the approving call targets the
    // currently focused pane (see [approvePendingVoiceCommand]).
    private val _voiceCommandReview: MutableStateFlow<VoiceCommandReviewUiState> =
        MutableStateFlow(VoiceCommandReviewUiState())

    public val voiceCommandReview: StateFlow<VoiceCommandReviewUiState> =
        _voiceCommandReview.asStateFlow()

    private var commandPlannerJob: Job? = null

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

    // Issue #102 (reopen): last on-screen terminal grid we propagated to
    // tmux via `resize-window`. Tracked so [resizeRemotePty] is a no-op
    // when Compose re-fires onTerminalSizeChanged with the same numbers
    // (which it does on every layout pass), and so a fresh
    // [closeCurrentConnection] resets back to "unknown" — the new
    // tmux-CC client will get its own resize-window when the surface
    // lays out against the freshly attached pane.
    private var remoteColumns: Int = 0
    private var remoteRows: Int = 0
    private val agentRepository: AgentConversationRepository = AgentConversationRepository()
    private val paneAgentJobs: MutableMap<String, Job> = ConcurrentHashMap()
    private val paneAgentInputs: MutableMap<String, Pair<String, String>> = ConcurrentHashMap()
    private val paneInputChannels: MutableMap<String, Channel<ByteArray>> = ConcurrentHashMap()
    private val paneInputJobs: MutableMap<String, Job> = ConcurrentHashMap()

    // Issue #179: per-(pane, hint-key) record of "the user has already
    // dismissed this hint for this detected agent session, do not show it
    // again". Without this set, the production hint composable in
    // [TmuxSessionScreen] kept re-mounting because [appendAgentEvents]
    // (called on every parsed JSONL event from
    // [agentRepository.tailEventsFromLine]) does not clear `hintVisible`
    // and [startAgentConversationForPane] re-runs whenever
    // [reconcilePanes] sees the same pane with the same `(cwd, command)`
    // tuple after a tmux reconcile. The set is keyed on the agent
    // detection's `sessionId ?: sourcePath` so re-detecting the same
    // file/session never resurrects a hint the user explicitly dismissed
    // or auto-dismissed. Survives ViewModel lifetime (process lifetime
    // for the foreground tmux screen). The companion field on
    // [SessionViewModel] is the raw-SSH mirror of this same idea.
    private val dismissedAgentHints: MutableSet<String> = mutableSetOf()

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

        // Issue #145: deterministic reconnect-loop signal. Every accepted
        // connect attempt increments a process-wide counter and emits a
        // log line that the connected disconnect+reconnect test greps for
        // from logcat. The two early-return guards above intentionally do
        // NOT emit — they are no-ops, not reconnect attempts.
        val attempt = TMUX_CONNECT_ATTEMPTS.incrementAndGet()
        Log.i(
            ISSUE_145_RECONNECT_TAG,
            "tmux-connect-attempt count=$attempt host=$host port=$port " +
                "user=$user session=${target.sessionName}",
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
        // crash was reproduced on the v0.2.7 dogfood device. The new shape
        // cancels-and-joins the prior connect job AND the event-loop job
        // inside a launched coroutine, so by the time we touch the SSH
        // transport the only thread interacting with it is us.
        val previousConnectJob = connectJob
        connectingTarget = target
        refreshReconnectAvailability()
        _connectionStatus.value = ConnectionStatus.Connecting(host, port, user)
        connectJob = viewModelScope.launch {
            // First: drain any in-flight connect() coroutine. `cancelAndJoin`
            // sends cancel and suspends until the prior job actually exits.
            // We do this in `NonCancellable` so a fresh cancel on the new
            // job (e.g. another rapid connect()) does not interrupt the
            // join itself — that would leave us in the original race window.
            withContext(NonCancellable) {
                previousConnectJob?.cancelAndJoin()
                // Then tear the previous connection down properly — joining
                // the event-loop coroutine before touching the transport.
                closeCurrentConnectionAndJoin()
            }
            runConnect(target)
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
        )
        return true
    }

    private fun refreshReconnectAvailability() {
        _canReconnect.value = activeTarget != null || connectingTarget != null
    }

    private suspend fun runConnect(target: ConnectionTarget) {
        try {
            val key: SshKey = SshKey.Path(File(target.keyPath))
            val sessionResult = SshConnection.connect(
                host = target.host,
                port = target.port,
                user = target.user,
                key = key,
                passphrase = target.passphrase?.copyOf(),
                knownHosts = KnownHostsPolicy.AcceptAll,
            )
            val session = sessionResult.getOrElse { e ->
                connectingTarget = null
                refreshReconnectAvailability()
                _connectionStatus.value =
                    ConnectionStatus.Failed("connect failed: ${e.message}")
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
            client.connect()
            activeTmuxClients.register(
                hostId = target.hostId,
                hostName = target.hostName,
                hostname = target.host,
                port = target.port,
                username = target.user,
                keyPath = target.keyPath,
                client = client,
            )
            registeredHostId = target.hostId
            bindProjectRootsForHost(target.hostId)

            // Bootstrap the pane list once tmux has had a moment to settle.
            // We don't strictly need this — the opening %window-add event
            // already fires a reconcile — but it covers the case of
            // reattaching to a session with pre-existing windows that we
            // missed the original %window-add for.
            reconcilePanes()

            connectingTarget = null
            refreshReconnectAvailability()
            _connectionStatus.value = ConnectionStatus.Connected(
                target.host,
                target.port,
                target.user,
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            connectingTarget = null
            refreshReconnectAvailability()
            _connectionStatus.value = ConnectionStatus.Failed(
                "error: ${t.javaClass.simpleName}: ${t.message}",
            )
        }
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
        activeTarget = target
        bindProjectRootsForHost(hostId)
        _connectionStatus.value = ConnectionStatus.Connected(host, port, user)
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
    private suspend fun reconcilePanes() {
        val client = clientRef ?: return
        val target = activeTarget
        val response = runCatching {
            client.sendCommand(
                // pane_index is appended last so we can sort within a
                // window. tmux can change index order on layout-rotate
                // commands, so we re-read it on every reconcile.
                //
                // Per #158: include `-s` so we list panes across every
                // window in the session, not only the current window.
                // Without `-s`, `list-panes -t <session>` returns only
                // the panes of the session's current window — meaning
                // `new-window` reconciles wipe the prior window's pane
                // rows from the UI, the WindowStrip never sees more
                // than one entry, and tap-to-switch has nothing to
                // switch between. The existing unit test
                // `reconcileScopesPanesAndWindowSummariesToActiveSession`
                // already expects a multi-window response shape — that
                // was a latent inconsistency between the unit fake and
                // the real tmux command, surfaced by the #158 E2E.
                buildString {
                    append("list-panes ")
                    if (target != null) {
                        append("-s -t '${escapeSingleQuoted(target.sessionName)}' ")
                    }
                    append("-F ")
                    append("'#{pane_id}\t#{window_id}\t#{session_id}\t#{session_name}\t#{pane_title}\t#{pane_index}\t#{pane_current_path}\t#{pane_current_command}'")
                },
            )
        }.getOrNull() ?: return
        if (response.isError) return

        val parsed: List<ParsedPane> = response.output.mapNotNull { parsePaneRow(it) }
        val newPanes = applyParsedPanes(parsed)
        preloadVisibleContentForNewPanes(newPanes)
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
            paneInputChannels.remove(paneId)?.close()
            paneRows[paneId]?.terminalState?.detachExternalProducer()
            paneRows.remove(paneId)
        }
        _agentConversations.value = _agentConversations.value.filterKeys { it in nextById.keys }
        // Issue #197: drop any first-send acknowledgement for panes that
        // tmux just removed — a fresh pane reusing the same `%N` id later
        // (rare but possible after a kill+re-create cycle within the same
        // session) should get its own confirmation banner. Also clear
        // the conversation lock if it was pointing at a gone pane.
        _firstSendConfirmedPanes.value = _firstSendConfirmedPanes.value.intersect(nextById.keys)
        _lockedConversationPaneId.value?.let { locked ->
            if (locked !in nextById.keys) {
                _lockedConversationPaneId.value = null
            }
        }
        paneRows.putAll(nextById)
        _panes.value = nextById.values.toList()
        return newRows
    }

    private suspend fun preloadVisibleContentForNewPanes(newPanes: List<TmuxPaneState>) {
        val client = clientRef ?: return
        for (pane in newPanes) {
            val response = runCatching {
                client.sendCommand("capture-pane -p -e -S -200 -t ${pane.paneId}")
            }.getOrNull()
            if (response == null || response.isError || response.output.isEmpty()) continue
            pane.terminalState.appendRemoteOutput(response.output.toTerminalViewportBytes())
        }
    }

    private fun startAgentDetectionForPane(pane: TmuxPaneState) {
        val session = sessionRef ?: return
        val cwd = pane.cwd.takeIf { it.isNotBlank() } ?: return
        val command = pane.currentCommand
        val input = cwd to command
        if (paneAgentInputs[pane.paneId] == input && paneAgentJobs[pane.paneId]?.isActive == true) return
        paneAgentJobs.remove(pane.paneId)?.cancel()
        paneAgentInputs[pane.paneId] = input
        paneAgentJobs[pane.paneId] = bridgeScope.launch {
            val detection = runCatching {
                agentRepository.detect(session, cwd, processHints = listOf(command))
            }.getOrNull() ?: return@launch
            startAgentConversationForPane(session, pane.paneId, detection)
        }
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
        // Issue #179: a fresh `startAgentConversationForPane` call
        // (e.g. because [reconcilePanes] cycled and the existing pane
        // got re-detected) must NOT resurrect a hint the user
        // already dismissed for the same (pane, session) key. We
        // therefore initialise `hintVisible` from [dismissedAgentHints]
        // rather than unconditionally to `true`.
        val hintKey = hintKeyFor(paneId, detection)
        setAgentConversation(
            paneId,
            AgentConversationUiState(
                detection = detection,
                events = boundedDistinctEvents(initialEvents),
                selectedTab = SessionTab.Terminal,
                hintVisible = hintKey !in dismissedAgentHints,
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
        // Issue #179: tapping the Conversation tab counts as the user
        // visiting the hint's destination — record the dismissal so a
        // later JSONL append + reconcile cannot resurrect the chip.
        if (tab == SessionTab.Conversation) {
            current.detection?.let { detection ->
                dismissedAgentHints += hintKeyFor(paneId, detection)
            }
            // Issue #197: lock the conversation send-target to this pane
            // so a subsequent WindowStrip tap to a non-agent window does
            // not silently swap the composer onto a pane with no agent.
            _lockedConversationPaneId.value = paneId
        } else {
            // Issue #197: explicit Terminal-tab selection unlocks the
            // conversation pane. Clearing only when the user owns the
            // intent keeps the lock alive when JSONL appends or pane
            // reconciles call selectSessionTab(Conversation) again.
            if (_lockedConversationPaneId.value == paneId) {
                _lockedConversationPaneId.value = null
            }
        }
        setAgentConversation(paneId, current.copy(selectedTab = tab, hintVisible = false))
    }

    public fun dismissAgentHint(paneId: String) {
        val current = _agentConversations.value[paneId] ?: return
        // Issue #179: record the dismissal key so subsequent JSONL
        // events or reconciles never re-set `hintVisible = true` for
        // this pane's detected agent session within the process
        // lifetime. The key matches the one
        // [startAgentConversationForPane] reads at construction time.
        current.detection?.let { detection ->
            dismissedAgentHints += hintKeyFor(paneId, detection)
        }
        setAgentConversation(paneId, current.copy(hintVisible = false))
    }

    /**
     * Issue #197: acknowledge the first-send confirmation banner for the
     * conversation pane bound to [paneId]. Once acknowledged the
     * "Sending to window N's <agent> pane — got it?" banner stays
     * dismissed for the lifetime of the ViewModel (i.e. the live tmux
     * session). Public so the screen's "Got it" button has a single
     * named seam to call.
     */
    public fun confirmFirstSendForPane(paneId: String) {
        if (paneId.isBlank()) return
        if (paneId in _firstSendConfirmedPanes.value) return
        _firstSendConfirmedPanes.value = _firstSendConfirmedPanes.value + paneId
    }

    /**
     * Issue #197: unlock the conversation send-target without requiring
     * the caller to know which pane is currently locked. Used by the
     * screen's Terminal-tab tap path: if the user has navigated to a
     * sibling window while the conversation pane is locked on the
     * agent pane, the currently-visible pane has no `AgentConversationUiState`
     * and [selectSessionTab] would silently no-op. This entry point
     * unlocks the conversation, while [selectSessionTab] still owns
     * flipping the locked pane's `selectedTab` if there is a pane
     * still alive at that id.
     */
    public fun returnToTerminalFromConversation() {
        val locked = _lockedConversationPaneId.value ?: return
        _lockedConversationPaneId.value = null
        val current = _agentConversations.value[locked] ?: return
        setAgentConversation(locked, current.copy(selectedTab = SessionTab.Terminal))
    }

    /**
     * Issue #179: the per-pane hint dismissal key. Composed of the
     * pane id plus the detected agent's `sessionId ?: sourcePath` so a
     * second pane independently detecting Claude in the same project
     * still gets its own (un-dismissed) hint chip on first detection.
     */
    private fun hintKeyFor(paneId: String, detection: AgentDetection): String {
        val sessionKey = detection.sessionId ?: detection.sourcePath
        return "$paneId|$sessionKey"
    }

    /**
     * Issue #179: snapshot the dismissed-hint keys for unit tests.
     * The set is otherwise private — tests need a read-only seam so
     * they can prove the explicit-dismiss and visit-to-dismiss paths
     * both populate it.
     */
    internal fun dismissedHintKeysForTest(): Set<String> = dismissedAgentHints.toSet()

    private fun appendAgentEvents(paneId: String, events: List<ConversationEvent>) {
        if (events.isEmpty()) return
        val current = _agentConversations.value[paneId] ?: return
        setAgentConversation(paneId, current.copy(events = boundedDistinctEvents(current.events + events)))
    }

    private fun setAgentConversation(paneId: String, state: AgentConversationUiState) {
        _agentConversations.value = _agentConversations.value + (paneId to state)
    }

    /**
     * Issue #179 test seam: synthesize a freshly-detected agent
     * conversation on [paneId] without going through the production SSH
     * + JSONL path. The state mirrors what
     * [startAgentConversationForPane] produces (initial events, terminal
     * tab selected, hint visible iff the (pane, detection) key has not
     * been dismissed). Tests use this to drive the hint-dismiss state
     * machine deterministically: stamp the detection, dismiss, then
     * replay this seam to mimic the next JSONL-driven re-detection and
     * assert the hint stays gone.
     */
    internal fun startAgentConversationForTest(
        paneId: String,
        detection: AgentDetection,
        initialEvents: List<ConversationEvent> = emptyList(),
    ) {
        val hintKey = hintKeyFor(paneId, detection)
        setAgentConversation(
            paneId,
            AgentConversationUiState(
                detection = detection,
                events = boundedDistinctEvents(initialEvents),
                selectedTab = SessionTab.Terminal,
                hintVisible = hintKey !in dismissedAgentHints,
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

    /**
     * Voice Command-mode transcript entry point. Mirrors
     * [com.pocketshell.app.session.SessionViewModel.planVoiceCommand]:
     * Prompt-mode dictation writes the transcript bytes directly into the
     * pane (via [writeInputToPane]); Command mode lands here so the
     * generated shell commands are planned, validated, and held for
     * explicit user review before they reach a pane.
     *
     * [focusedPaneId] is the pane the user is currently looking at — when
     * non-null we use its cached `pane_current_path` / `pane_current_command`
     * to populate the planner request's per-session metadata. The screen
     * supplies the pager's `currentPage` pane; tests can pass `null` to
     * exercise the no-focus path.
     */
    public fun planVoiceCommand(transcript: String, focusedPaneId: String? = null) {
        val cleaned = transcript.trim()
        if (cleaned.isEmpty()) return
        if (commandPlannerJob?.isActive == true) return

        val client = commandPlannerClientFactory.create()
        if (client == null) {
            _voiceCommandReview.value = VoiceCommandReviewUiState(
                error = "No OpenAI API key saved. Open the composer to add one.",
            )
            return
        }

        _voiceCommandReview.value = VoiceCommandReviewUiState(
            isPlanning = true,
            transcript = cleaned,
        )
        commandPlannerJob = viewModelScope.launch {
            val result = client.plan(
                CommandPlannerRequest(
                    transcript = cleaned,
                    session = commandPlannerSessionMetadata(focusedPaneId),
                    safety = CommandPlannerSafetyConstraints(
                        requireReviewBeforeExecution = true,
                        allowAutoSend = false,
                    ),
                ),
            )
            _voiceCommandReview.value = result.fold(
                onSuccess = { plan ->
                    VoiceCommandReviewUiState(
                        transcript = cleaned,
                        pendingPlan = plan,
                    )
                },
                onFailure = { error ->
                    VoiceCommandReviewUiState(
                        transcript = cleaned,
                        error = commandPlannerMessage(error),
                    )
                },
            )
        }
    }

    /**
     * Approve the held [voiceCommandReview] plan and route its commands
     * into [paneId] through the same `send-keys` bridge used by chip taps,
     * inline dictation prompt-mode, and snippet picks. `withEnter = true`
     * sends Enter after the literal so the command runs; `false` inserts
     * the bytes only so the user can keep editing.
     */
    public fun approvePendingVoiceCommand(paneId: String, withEnter: Boolean) {
        val plan = _voiceCommandReview.value.pendingPlan ?: return
        if (paneId.isBlank()) return
        val joined = plan.commands.joinToString("\n") { it.command }
        val payload = if (withEnter) joined + "\r" else joined
        writeInputToPane(paneId, payload.toByteArray(Charsets.UTF_8))
        _voiceCommandReview.value = VoiceCommandReviewUiState()
    }

    public fun dismissVoiceCommandReview() {
        commandPlannerJob?.cancel()
        _voiceCommandReview.value = VoiceCommandReviewUiState()
    }

    private fun commandPlannerSessionMetadata(
        focusedPaneId: String? = null,
    ): CommandPlannerSessionMetadata {
        val connected = _connectionStatus.value as? ConnectionStatus.Connected
        val target = activeTarget
        val focusedPane = focusedPaneId?.let { paneRows[it] }
        return CommandPlannerSessionMetadata(
            hostLabel = target?.hostName?.takeIf { it.isNotBlank() }
                ?: connected?.host
                ?: target?.host
                ?: "",
            username = connected?.user ?: target?.user ?: "",
            // tmux already caches each pane's `pane_current_path` /
            // `pane_current_command` (see [reconcilePanes]); when the
            // screen tells us which pane the user is looking at, we
            // forward that cache to the planner request rather than do a
            // redundant `pwd` round-trip.
            currentDirectory = focusedPane?.cwd?.takeIf { it.isNotBlank() },
            projectRoots = _projectRoots.value.map { it.path },
            // `pane_current_command` is the *focused* foreground process
            // tmux is reporting — often a shell name (`bash` / `zsh` /
            // `fish`) but sometimes a transient process (`vim`, `git`,
            // `claude`). Only forward it when it looks like a known
            // shell; anything else falls back to null so the planner
            // sees "unknown" instead of being misled by an editor name.
            shellType = focusedPane?.currentCommand?.let { shellTypeOrNull(it) },
        )
    }

    /**
     * Filter [pane_current_command] down to a known shell name, or null
     * if the foreground process is not a shell we want to advertise.
     *
     * Keeping the allow-list narrow on purpose: passing `vim` or `git`
     * as `shell_type` would teach the planner the wrong syntax. The list
     * matches the common Unix login shells called out in the #66 doc.
     */
    private fun shellTypeOrNull(command: String): String? {
        val trimmed = command.trim().substringAfterLast('/')
        return when (trimmed) {
            "bash", "zsh", "fish", "sh", "dash", "ksh", "tcsh", "csh" -> trimmed
            else -> null
        }
    }

    private fun commandPlannerMessage(error: Throwable): String = when (error) {
        is CommandPlannerException.Rejected -> "Command planner rejected this request: ${error.message}"
        is CommandPlannerException.Auth -> "Command planner credentials were rejected."
        is CommandPlannerException.RateLimited -> "Command planner is rate limited. Try again later."
        is CommandPlannerException.Server -> "Command planner service failed. Try again later."
        is CommandPlannerException.Parse -> "Command planner returned an invalid response."
        is CommandPlannerException.Transport -> "Command planner could not be reached."
        else -> error.message ?: "Command planner failed."
    }

    private fun inputSinkForPane(paneId: String): OutputStream {
        val channel = paneInputChannels.getOrPut(paneId) {
            Channel<ByteArray>(Channel.UNLIMITED).also { newChannel ->
                paneInputJobs[paneId] = bridgeScope.launch {
                    for (bytes in newChannel) {
                        val client = clientRef ?: continue
                        runCatching { sendInputBytesToPane(client, paneId, bytes) }
                    }
                }
            }
        }
        return TmuxPaneInputStream(channel)
    }

    private suspend fun sendInputBytesToPane(
        client: TmuxClient,
        paneId: String,
        bytes: ByteArray,
    ) {
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
     * Issue #102 (reopen): propagate the on-screen [TerminalView]'s grid
     * dimensions to the **remote tmux pane** so opencode / Codex / Claude
     * Code (which draw their alternate-screen UI for whatever size tmux
     * reports) line up with the cells the local emulator is rendering.
     *
     * The raw-SSH route ([com.pocketshell.app.session.SessionViewModel.resizeRemotePty])
     * forwards via the SSH session channel's `changeWindowDimensions`
     * (TIOCSWINSZ on the remote PTY) so the shell sees a SIGWINCH and
     * re-flows. The tmux-CC route cannot do the same — the SSH PTY
     * carries the tmux *control* channel, not the inner pane's PTY.
     * tmux owns the per-pane terminal size; we must ask tmux to resize
     * the session window via the control protocol.
     *
     * `resize-window -t <session> -x <cols> -y <rows>` does exactly that:
     * tmux resizes the named session's active window (and its panes) to
     * the requested geometry and refreshes attached clients. The change
     * is wire-only (no `set-option -g window-size manual` toggling) so
     * the next reattach without a connected client of our size falls
     * back to tmux defaults — the right behaviour for an SSH-CC client
     * that may be detached and reattached from different devices.
     *
     * Idempotent: a re-call with the same dimensions is a no-op (the
     * Compose layout system re-fires `onTerminalSizeChanged` on every
     * pass even when nothing visibly changed). Initial dimensions of
     * `0` (i.e. the view never laid out) are also a no-op.
     *
     * Without this, the user reported "typing into opencode places the
     * input somewhere in the center" on v0.2.6: tmux thought the
     * window was 80x24 (the size the SSH PTY allocated in
     * [com.pocketshell.core.ssh.RealSshSession.startShell]), opencode
     * drew its UI box for an 80x24 grid, and the local emulator
     * rendered a different on-screen grid — so the box edges and
     * cursor positions landed at the wrong cells of the visible
     * viewport. The TerminalLab path in the original #102 round did
     * not have this bug because it owns the SSH PTY directly and
     * already calls `changeWindowDimensions` from
     * [com.pocketshell.app.terminal.TerminalLabActivity].
     */
    public fun resizeRemotePty(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        if (columns == remoteColumns && rows == remoteRows) return
        val client = clientRef ?: return
        val target = activeTarget ?: return
        remoteColumns = columns
        remoteRows = rows
        bridgeScope.launch {
            runCatching {
                client.sendCommand(
                    "resize-window -t '${escapeSingleQuoted(target.sessionName)}' " +
                        "-x $columns -y $rows",
                )
            }
        }
    }

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

    public fun killWindow(windowId: String) {
        if (windowId.isBlank()) return
        sendLifecycleCommand("kill-window -t $windowId")
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
     * Quote a string for inclusion inside single quotes in a tmux command
     * line. tmux's command parser uses POSIX-shell-ish single quoting:
     * everything between the outer pair of `'...'` is literal except the
     * `'` character itself, which must be closed and re-opened
     * (`'\''`). We replace single quotes with the close-escape-open
     * sequence and leave everything else alone.
     */
    internal fun escapeSingleQuoted(input: String): String =
        input.replace("'", "'\\''")

    override fun onCleared() {
        connectJob?.cancel()
        connectJob = null
        commandPlannerJob?.cancel()
        commandPlannerJob = null
        closeCurrentConnection()
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
    private suspend fun closeCurrentConnectionAndJoin() {
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
        for ((_, channel) in paneInputChannels) {
            channel.close()
        }
        paneAgentJobs.clear()
        paneAgentInputs.clear()
        paneInputJobs.clear()
        paneInputChannels.clear()
        _agentConversations.value = emptyMap()
        // Issue #197: clear conversation-target state so a fresh connect
        // does not start with a stale lock or a stale "already
        // acknowledged first send" record from the previous session.
        _lockedConversationPaneId.value = null
        _firstSendConfirmedPanes.value = emptySet()
        paneProducerJobs.clear()
        for ((_, row) in paneRows) {
            runCatching { row.terminalState.detachExternalProducer() }
        }
        paneRows.clear()
        _panes.value = emptyList()
        runCatching { clientRef?.close() }
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
        connectingTarget = null
        // Issue #102 (reopen): a fresh attach must re-fire `resize-window`
        // against the new tmux session's pane on the next layout pass.
        remoteColumns = 0
        remoteRows = 0
    }

    /**
     * Non-suspending teardown used by `onCleared()` and the synchronous
     * test-replacement seam. Cancels the event-loop coroutine (fire and
     * forget) and then tears the rest of the state down sync. Production
     * callers that need the cancel-and-join ordering — most importantly
     * `connect()`, which raced the event loop on real devices (#151) —
     * must use [closeCurrentConnectionAndJoin] instead.
     */
    private fun closeCurrentConnection() {
        eventsJob?.cancel()
        eventsJob = null
        projectRootsJob?.cancel()
        projectRootsJob = null
        _projectRoots.value = emptyList()
        for ((_, job) in paneProducerJobs) {
            job.cancel()
        }
        for ((_, job) in paneAgentJobs) {
            job.cancel()
        }
        for ((_, job) in paneInputJobs) {
            job.cancel()
        }
        for ((_, channel) in paneInputChannels) {
            channel.close()
        }
        paneAgentJobs.clear()
        paneAgentInputs.clear()
        paneInputJobs.clear()
        paneInputChannels.clear()
        _agentConversations.value = emptyMap()
        // Issue #197: clear conversation-target state so a fresh connect
        // does not start with a stale lock or a stale "already
        // acknowledged first send" record from the previous session.
        _lockedConversationPaneId.value = null
        _firstSendConfirmedPanes.value = emptySet()
        paneProducerJobs.clear()
        for ((_, row) in paneRows) {
            runCatching { row.terminalState.detachExternalProducer() }
        }
        paneRows.clear()
        _panes.value = emptyList()
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
        // Issue #102 (reopen): a fresh attach must re-fire `resize-window`
        // against the new tmux session's pane on the next layout pass.
        remoteColumns = 0
        remoteRows = 0
    }

    /**
     * Parse one row from `list-panes -F ...` output into a
     * [ParsedPane]. Returns null if the row is malformed — we tolerate a
     * trailing blank line or a tmux version that surfaces fewer fields
     * than the format string requested.
     */
    private fun parsePaneRow(line: String): ParsedPane? {
        val parts = line.split('\t')
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

    private sealed interface TmuxInputToken {
        data class Literal(val text: String) : TmuxInputToken
        data class NamedKey(val name: String) : TmuxInputToken
    }

    private class TmuxPaneInputStream(
        private val channel: Channel<ByteArray>,
    ) : OutputStream() {
        override fun write(b: Int) {
            write(byteArrayOf(b.toByte()))
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            if (length <= 0) return
            val copy = buffer.copyOfRange(offset, offset + length)
            channel.trySend(copy)
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

private fun List<String>.toTerminalViewportBytes(): ByteArray {
    val lines = this
    val text = buildString {
        append("\u001b[H\u001b[2J")
        lines.forEachIndexed { index, line ->
            if (index > 0) append("\r\n")
            append(line)
        }
        append("\r\n")
    }
    return text.toByteArray(Charsets.UTF_8)
}

private const val MaxAgentEvents: Int = 500

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
