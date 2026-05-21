package com.pocketshell.app.tmux

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.protocol.ControlEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File
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
 *    `list-panes -a` and reconcile [_panes]. New rows get a fresh
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

    private val _connectionStatus: MutableStateFlow<ConnectionStatus> =
        MutableStateFlow(ConnectionStatus.Idle)

    /** Coarse-grained status the screen surfaces above the terminal. */
    public val connectionStatus: StateFlow<ConnectionStatus> =
        _connectionStatus.asStateFlow()

    private var sessionRef: SshSession? = null
    private var clientRef: TmuxClient? = null
    private var connectJob: Job? = null
    private var eventsJob: Job? = null

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
     * Idempotent — re-entering with a live connection in flight is a
     * no-op. [keyPath] is the resolved absolute path of the user's
     * private key on disk, the same way [com.pocketshell.app.session.SessionViewModel]
     * consumes it from the host picker.
     */
    public fun connect(
        host: String,
        port: Int,
        user: String,
        keyPath: String,
        sessionName: String,
    ) {
        if (connectJob?.isActive == true) return
        if (_connectionStatus.value is ConnectionStatus.Connected) return
        _connectionStatus.value = ConnectionStatus.Connecting(host, port, user)
        connectJob = viewModelScope.launch {
            runConnect(host, port, user, keyPath, sessionName)
        }
    }

    private suspend fun runConnect(
        host: String,
        port: Int,
        user: String,
        keyPath: String,
        sessionName: String,
    ) {
        try {
            val key: SshKey = SshKey.Path(File(keyPath))
            val sessionResult = SshConnection.connect(
                host = host,
                port = port,
                user = user,
                key = key,
                knownHosts = KnownHostsPolicy.AcceptAll,
            )
            val session = sessionResult.getOrElse { e ->
                _connectionStatus.value =
                    ConnectionStatus.Failed("connect failed: ${e.message}")
                return
            }
            sessionRef = session

            val client = tmuxClientFactory.create(session, sessionName = sessionName)
            attachClient(client)
            client.connect()

            // Bootstrap the pane list once tmux has had a moment to settle.
            // We don't strictly need this — the opening %window-add event
            // already fires a reconcile — but it covers the case of
            // reattaching to a session with pre-existing windows that we
            // missed the original %window-add for.
            reconcilePanes()

            _connectionStatus.value = ConnectionStatus.Connected(host, port, user)
        } catch (t: Throwable) {
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
        eventsJob = bridgeScope.launch {
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

    /**
     * Process one event from the bus.
     *
     * Per the issue body the structural events of interest are
     * [ControlEvent.WindowAdd] / [ControlEvent.WindowClose] /
     * [ControlEvent.LayoutChange]: each one triggers a `list-panes -a`
     * round-trip that re-derives the pane list authoritatively. We do not
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
     * Format string matches the order of the [TmuxPaneState] fields:
     * `<pane-id> <window-id> <session-id> <title>`. We pick the
     * non-printable `\t` as the field separator because pane titles can
     * contain spaces but cannot contain literal tabs (tmux strips them).
     *
     * Errors from `list-panes` (e.g. the server torn down mid-request)
     * leave the existing pane list intact rather than wiping it — a
     * transient failure should not blank the UI.
     */
    private suspend fun reconcilePanes() {
        val client = clientRef ?: return
        val response = runCatching {
            client.sendCommand(
                // pane_index is appended last so we can sort within a
                // window. tmux can change index order on layout-rotate
                // commands, so we re-read it on every reconcile.
                "list-panes -a -F " +
                    "'#{pane_id}\t#{window_id}\t#{session_id}\t#{pane_title}\t#{pane_index}'",
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
        val sorted = parsed
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
                        // No remote stdin here — user keystrokes route
                        // back to tmux via `send-keys -t %N <bytes>`
                        // from [writeInputToPane], not via a per-pane PTY
                        // stream. tmux -CC does not surface a writable
                        // per-pane fd to the client at all.
                        remoteStdin = null,
                    )
                    paneProducerJobs[p.paneId] = job
                }
                TmuxPaneState(
                    paneId = p.paneId,
                    windowId = p.windowId,
                    sessionId = p.sessionId,
                    title = p.title,
                    terminalState = state,
                )
            }
            nextById[p.paneId] = row
        }

        // Tear down panes that disappeared. Cancel the producer + detach
        // the bridge so the TerminalSurfaceState releases its emulator
        // reference cleanly.
        val gonePaneIds = paneRows.keys - nextById.keys
        for (paneId in gonePaneIds) {
            paneProducerJobs.remove(paneId)?.cancel()
            paneRows[paneId]?.terminalState?.detachExternalProducer()
            paneRows.remove(paneId)
        }
        paneRows.putAll(nextById)
        _panes.value = nextById.values.toList()
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
        val literal = String(bytes, Charsets.UTF_8)
        val escaped = escapeSingleQuoted(literal)
        bridgeScope.launch {
            runCatching {
                client.sendCommand("send-keys -t $paneId '$escaped'")
            }
        }
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
        eventsJob?.cancel()
        eventsJob = null
        connectJob?.cancel()
        connectJob = null
        for ((_, job) in paneProducerJobs) {
            job.cancel()
        }
        paneProducerJobs.clear()
        for ((_, row) in paneRows) {
            runCatching { row.terminalState.detachExternalProducer() }
        }
        paneRows.clear()
        _panes.value = emptyList()
        runCatching { clientRef?.close() }
        clientRef = null
        runCatching { sessionRef?.close() }
        sessionRef = null
        // bridgeScope is parented to viewModelScope, so its SupervisorJob
        // tears down automatically when viewModelScope cancels post-super
        // call. Explicit cancellation here is redundant — leaving it to
        // the framework keeps the teardown path single-sourced.
        super.onCleared()
    }

    /**
     * Parse one row from `list-panes -a -F ...` output into a
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
        val title = parts[3]
        val paneIndex = parts[4].trim().toIntOrNull() ?: 0
        return ParsedPane(
            paneId = paneId,
            windowId = windowId,
            sessionId = sessionId,
            title = title,
            paneIndex = paneIndex,
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
    )

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

