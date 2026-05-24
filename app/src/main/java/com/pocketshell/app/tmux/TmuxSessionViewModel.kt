package com.pocketshell.app.tmux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.session.AgentConversationUiState
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.DEFAULT_TMUX_START_DIRECTORY
import com.pocketshell.app.sessions.resolveTmuxSessionCreation
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.protocol.ControlEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
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

    private val _connectionStatus: MutableStateFlow<ConnectionStatus> =
        MutableStateFlow(ConnectionStatus.Idle)

    /** Coarse-grained status the screen surfaces above the terminal. */
    public val connectionStatus: StateFlow<ConnectionStatus> =
        _connectionStatus.asStateFlow()

    private var sessionRef: SshSession? = null
    private var clientRef: TmuxClient? = null
    private var registeredHostId: Long? = null
    private var activeTarget: ConnectionTarget? = null
    private var connectingTarget: ConnectionTarget? = null
    private var connectJob: Job? = null
    private var eventsJob: Job? = null
    private val agentRepository: AgentConversationRepository = AgentConversationRepository()
    private val paneAgentJobs: MutableMap<String, Job> = ConcurrentHashMap()
    private val paneAgentInputs: MutableMap<String, Pair<String, String>> = ConcurrentHashMap()
    private val paneInputChannels: MutableMap<String, Channel<ByteArray>> = ConcurrentHashMap()
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

        connectJob?.cancel()
        closeCurrentConnection()
        connectingTarget = target
        _connectionStatus.value = ConnectionStatus.Connecting(host, port, user)
        connectJob = viewModelScope.launch {
            runConnect(target)
        }
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
            activeTarget = target

            // Bootstrap the pane list once tmux has had a moment to settle.
            // We don't strictly need this — the opening %window-add event
            // already fires a reconcile — but it covers the case of
            // reattaching to a session with pre-existing windows that we
            // missed the original %window-add for.
            reconcilePanes()

            connectingTarget = null
            _connectionStatus.value = ConnectionStatus.Connected(
                target.host,
                target.port,
                target.user,
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            connectingTarget = null
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
        eventsJob = bridgeScope.launch(start = CoroutineStart.UNDISPATCHED) {
            client.events.collect { event ->
                onControlEvent(event)
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
        _connectionStatus.value = ConnectionStatus.Connected(host, port, user)
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
                buildString {
                    append("list-panes ")
                    if (target != null) {
                        append("-t '${escapeSingleQuoted(target.sessionName)}' ")
                    }
                    append("-F ")
                    append("'#{pane_id}\t#{window_id}\t#{session_id}\t#{session_name}\t#{pane_title}\t#{pane_index}\t#{pane_current_path}\t#{pane_current_command}'")
                },
            )
        }.getOrNull() ?: return
        if (response.isError) return

        val parsed: List<ParsedPane> = response.output.mapNotNull { parsePaneRow(it) }
        applyParsedPanes(parsed)
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

    private fun applyParsedPanes(parsed: List<ParsedPane>) {
        val client = clientRef
        val target = activeTarget
        val sorted = parsed
            .filter { pane -> target == null || pane.sessionName == target.sessionName }
            .sortedWith(compareBy({ it.windowId }, { it.paneIndex }, { it.paneId }))

        val nextById: MutableMap<String, TmuxPaneState> = LinkedHashMap()
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
                )
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
        paneRows.putAll(nextById)
        _panes.value = nextById.values.toList()
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
        setAgentConversation(
            paneId,
            AgentConversationUiState(
                detection = detection,
                events = boundedDistinctEvents(initialEvents),
                selectedTab = SessionTab.Terminal,
                hintVisible = true,
            ),
        )
        val followJob = if (detection.agent == AgentKind.OpenCode) {
            bridgeScope.launch {
                while (true) {
                    kotlinx.coroutines.delay(5_000)
                    val events = runCatching {
                        agentRepository.pollOpenCodeEvents(session, detection)
                    }.getOrDefault(emptyList())
                    appendAgentEvents(paneId, events)
                }
            }
        } else {
            agentRepository.tailEventsFromLine(session, detection, lineCount) { event ->
                appendAgentEvents(paneId, listOf(event))
            }
        }
        if (followJob != null) {
            paneAgentJobs[paneId] = followJob
        }
    }

    public fun selectSessionTab(paneId: String, tab: SessionTab) {
        val current = _agentConversations.value[paneId] ?: return
        if (tab == SessionTab.Conversation && current.detection == null) return
        setAgentConversation(paneId, current.copy(selectedTab = tab, hintVisible = false))
    }

    public fun dismissAgentHint(paneId: String) {
        val current = _agentConversations.value[paneId] ?: return
        setAgentConversation(paneId, current.copy(hintVisible = false))
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
        closeCurrentConnection()
        // bridgeScope is parented to viewModelScope, so its SupervisorJob
        // tears down automatically when viewModelScope cancels post-super
        // call. Explicit cancellation here is redundant — leaving it to
        // the framework keeps the teardown path single-sourced.
        super.onCleared()
    }

    private fun closeCurrentConnection() {
        eventsJob?.cancel()
        eventsJob = null
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

private fun boundedDistinctEvents(events: List<ConversationEvent>): List<ConversationEvent> {
    val byId = LinkedHashMap<String, ConversationEvent>()
    for (event in events.takeLast(MaxAgentEvents * 2)) {
        byId[event.id] = event
    }
    val distinct = byId.values.toList()
    return if (distinct.size <= MaxAgentEvents) {
        distinct
    } else {
        distinct.subList(distinct.size - MaxAgentEvents, distinct.size)
    }
}

private const val MaxAgentEvents: Int = 500
