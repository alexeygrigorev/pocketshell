package com.pocketshell.app.session

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.R
import com.pocketshell.app.assistant.AppAssistantActions
import com.pocketshell.app.assistant.AssistantActions
import com.pocketshell.app.assistant.AssistantInstallId
import com.pocketshell.app.assistant.AssistantSshExecutor
import com.pocketshell.app.assistant.AssistantSshParams
import com.pocketshell.app.assistant.AssistantUiState
import com.pocketshell.app.assistant.ExecutorTraceSink
import com.pocketshell.app.assistant.FolderCandidate
import com.pocketshell.app.assistant.RealAssistantSshExecutor
import com.pocketshell.app.assistant.SessionAssistantController
import com.pocketshell.app.assistant.SessionActionBridge
import com.pocketshell.app.composer.PromptAttachmentStager
import com.pocketshell.app.snippets.snippetDispatchText
import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.core.assistant.AssistantLlmClientFactory
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.app.proof.createStdoutFlow
import com.pocketshell.app.proof.readKeyFromRawResource
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.terminal.ui.TerminalRawInputPolicy
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.uikit.model.KeyModifierState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import java.io.File

/**
 * Holds the live SSH session, the terminal surface state, and the sticky
 * modifier state for the key bar.
 *
 * The class is Hilt-managed (`@HiltViewModel`) so the [MainActivity] can
 * obtain it via the standard `ComponentActivity.viewModels()` extension once
 * the activity is annotated `@AndroidEntryPoint` (it already is, since #9).
 *
 * ## Sticky modifier model (Ctrl / Alt)
 *
 * Per `docs/input-methods.md` §"Key bar" and the brief for issue #13:
 *
 * - Tap a modifier (`Ctrl` / `Alt`) → it arms for the next non-modifier key,
 *   then auto-clears. ("One-shot" sticky.)
 * - Double-tap a modifier in the key bar → it locks until tapped again.
 * - Tap a non-modifier (`Esc`, `Tab`, an arrow) while a modifier is armed →
 *   the modifier wraps the key on the wire. One-shot modifiers clear after
 *   the key; locked modifiers remain active.
 *
 * The Compose `KeyBar` from `:shared:ui-kit` carries its own internal
 * modifier state for the *visual* "active" treatment (accent fill). That
 * state is reported back to the screen through `onModifierStateChange`.
 * The ViewModel mirrors it so the terminal writer and the key-bar visuals
 * agree on one-shot versus locked modifier behavior.
 *
 * ## Key codes on the wire
 *
 * Per the brief for #13:
 *
 * - `Esc`  → `0x1B`
 * - `Tab`  → `0x09`
 * - `←`    → `ESC [ D`  (`0x1B 0x5B 0x44`)
 * - `↑`    → `ESC [ A`  (`0x1B 0x5B 0x41`)
 * - `↓`    → `ESC [ B`  (`0x1B 0x5B 0x42`)
 * - `→`    → `ESC [ C`  (`0x1B 0x5B 0x43`)
 *
 * When a modifier is armed:
 *
 * - `Ctrl` + ASCII letter `a`..`z` / `A`..`Z` → `0x01`..`0x1A` (XON/XOFF range,
 *   i.e. the standard terminal Ctrl-letter mapping; `Ctrl+C` → `0x03`).
 * - `Ctrl` + non-letter (e.g. `Ctrl+Esc`) → only the unmodified bytes are
 *   sent. The terminal does not have a canonical Ctrl-Esc encoding; we
 *   defer to the unwrapped key rather than invent one.
 * - `Alt`  + bytes → ESC (`0x1B`) prefix, then the unmodified bytes. This
 *   matches xterm's "Meta sends Escape" default.
 *
 * The sticky state survives across key taps that are NOT on the key bar
 * (e.g. system-keyboard letters do not pass through here), so a user tapping
 * `Ctrl` on the bar and then `c` on the system keyboard does NOT produce
 * `0x03` — that path requires intercepting the IME, which is out of scope
 * for #13. Inside the key bar's own 8 slots the sticky logic is fully
 * exercised: `Ctrl + ←` produces ESC[D wrapped with Ctrl (which we encode
 * as ESC [ 1 ; 5 D — same as xterm's modifyCursorKeys=2 default for control
 * arrow keys).
 *
 * @see com.pocketshell.uikit.components.KeyBar
 * @see TerminalSurfaceState.writeInput
 */
@HiltViewModel
public class SessionViewModel @Inject constructor(
    @ApplicationContext private val applicationContext: Context,
    private val assistantClientFactory: AssistantLlmClientFactory? = null,
    private val hostDao: HostDao? = null,
    private val folderListGateway: FolderListGateway? = null,
    private val reposRemoteSource: ReposRemoteSource? = null,
    private val projectRootDao: ProjectRootDao? = null,
    private var sshLeaseManager: SshLeaseManager = SshLeaseManager(
        connector = SshLeaseConnector { target ->
            DefaultSshLeaseConnector().connect(target)
        },
    ),
) : ViewModel() {

    /**
     * SSH executor seam for assistant inspect/exec tools. Not Hilt-injected
     * (no binding needed) — tests substitute it via [setAssistantSshExecutor].
     */
    private var assistantSshExecutor: AssistantSshExecutor = RealAssistantSshExecutor()

    @androidx.annotation.VisibleForTesting
    internal fun setAssistantSshExecutor(executor: AssistantSshExecutor) {
        assistantSshExecutor = executor
    }

    @androidx.annotation.VisibleForTesting
    internal fun setRawSshConnectorForTest(
        connector: suspend (
            host: String,
            port: Int,
            user: String,
            key: SshKey,
            passphrase: CharArray?,
            knownHosts: KnownHostsPolicy,
        ) -> Result<SshSession>,
    ) {
        sshLeaseManager = SshLeaseManager(
            connector = SshLeaseConnector { target ->
                connector(
                    target.leaseKey.host,
                    target.leaseKey.port,
                    target.leaseKey.user,
                    target.key,
                    target.passphrase,
                    target.knownHosts,
                )
            },
            idleTtlMillis = 0,
        )
    }

    /**
     * The terminal surface state hosted by the session screen. Created
     * inside the ViewModel so the SSH producer can be torn down when the
     * ViewModel is cleared, decoupled from the composable's recomposition
     * lifecycle.
     */
    public val terminalState: TerminalSurfaceState = TerminalSurfaceState()

    private val _connectionStatus: MutableStateFlow<ConnectionStatus> =
        MutableStateFlow(ConnectionStatus.Idle)

    /** Coarse-grained status the screen surfaces above the terminal. */
    public val connectionStatus: StateFlow<ConnectionStatus> = _connectionStatus.asStateFlow()

    private val _modifierStates: MutableStateFlow<Map<Modifier, KeyModifierState>> =
        MutableStateFlow(emptyMap())

    private val _armedModifiers: MutableStateFlow<Set<Modifier>> = MutableStateFlow(emptySet())

    /**
     * Snapshot of modifiers currently active, whether one-shot or locked.
     */
    public val armedModifiers: StateFlow<Set<Modifier>> = _armedModifiers.asStateFlow()

    /** Sticky state for active modifiers, mirrored from the ui-kit key bar. */
    public val modifierStates: StateFlow<Map<Modifier, KeyModifierState>> = _modifierStates.asStateFlow()

    private val agentRepository: AgentConversationRepository = AgentConversationRepository()

    private val _agentConversation: MutableStateFlow<AgentConversationUiState> =
        MutableStateFlow(AgentConversationUiState())

    public val agentConversation: StateFlow<AgentConversationUiState> =
        _agentConversation.asStateFlow()

    // Issue #266: the assistant replaces the deleted CommandPlanner voice
    // path (D22 hard cut). Command-mode dictation now lands in
    // [dictateToAssistant]; the controller owns the confirm-or-correct loop.
    private var assistantHostId: Long? = null
    private var assistantHostName: String? = null
    private var assistantHostname: String? = null
    private var assistantPort: Int = SessionDefaults.PORT
    private var assistantUsername: String? = null
    private var assistantKeyPath: String? = null
    private var assistantPassphrase: CharArray? = null

    private val _assistantNavRequests: MutableSharedFlow<AppDestination> =
        MutableSharedFlow(extraBufferCapacity = 4)

    /** Navigation requests the assistant made; the screen routes them. */
    internal val assistantNavRequests: kotlinx.coroutines.flow.SharedFlow<AppDestination> =
        _assistantNavRequests.asSharedFlow()

    private val assistant: SessionAssistantController =
        SessionAssistantController(scope = viewModelScope, sessionFactory = ::buildAssistantDeps)

    /** Assistant UI state (idle / thinking / confirming / done / error). */
    internal val assistantState: StateFlow<AssistantUiState> = assistant.state

    private val _projectNavigation: MutableStateFlow<ProjectNavigationUiState> =
        MutableStateFlow(ProjectNavigationUiState())

    public val projectNavigation: StateFlow<ProjectNavigationUiState> =
        _projectNavigation.asStateFlow()

    private var sessionRef: SshSession? = null
    private var shellRef: SshShell? = null
    private var leaseRef: SshLease? = null
    private var remoteColumns: Int = 0
    private var remoteRows: Int = 0
    private var producerJob: Job? = null
    private var connectJob: Job? = null
    private var autoReconnectJob: Job? = null
    private var agentDetectJob: Job? = null
    private var agentTailJob: Job? = null
    private var agentRetryJob: Job? = null
    private var projectRootsJob: Job? = null
    private var activeTarget: RawSshTarget? = null
    private var connectingTarget: RawSshTarget? = null
    private var pausedAutoReconnect: PausedAutoReconnect? = null
    private var appActive: Boolean = true

    private val _canReconnect: MutableStateFlow<Boolean> = MutableStateFlow(false)
    public val canReconnect: StateFlow<Boolean> = _canReconnect.asStateFlow()

    private var autoReconnectDelaysMs: List<Long> = DEFAULT_AUTO_RECONNECT_DELAYS_MS

    /**
     * Connect to the given host (idempotent — re-calling with the same
     * triple is a no-op while a connection is in flight or established).
     *
     * When [keyPath] is non-null the SSH key is loaded from disk at that
     * path — this is the route #18's host picker takes after resolving
     * `HostEntity.keyId → SshKeyEntity.privateKeyPath`. When [keyPath]
     * is null we fall back to the Phase 0 bundled raw-resource key,
     * which keeps `ProofPipelineTest` and the default Phase 1 boot path
     * working unchanged.
     */
    public fun connect(
        host: String,
        port: Int,
        user: String,
        keyPath: String? = null,
        passphrase: CharArray? = null,
        hostId: Long? = null,
    ) {
        pausedAutoReconnect = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        val target = RawSshTarget(host, port, user, keyPath, passphrase?.copyOf(), hostId)
        if (connectJob?.isActive == true) return
        if (_connectionStatus.value is ConnectionStatus.Connected && activeTarget == target) return
        closeCurrentConnection(clearActiveTarget = activeTarget != target)
        connectingTarget = target
        refreshReconnectAvailability()
        _connectionStatus.value = ConnectionStatus.Connecting(host, port, user)
        connectJob = viewModelScope.launch {
            runConnect(host, port, user, keyPath, passphrase, hostId, viewModelScope)
        }
    }

    public fun reconnect(): Boolean {
        pausedAutoReconnect = null
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        val target = activeTarget ?: connectingTarget ?: return false
        connect(
            host = target.host,
            port = target.port,
            user = target.user,
            keyPath = target.keyPath,
            passphrase = target.passphrase,
            hostId = target.hostId,
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

    public fun onAppForegrounded() {
        appActive = true
        val paused = pausedAutoReconnect ?: return
        resumePausedAutoReconnect(paused)
    }

    public fun onAppBackgrounded() {
        appActive = false
        autoReconnectJob?.cancel()
        autoReconnectJob = null
        val reconnecting = _connectionStatus.value as? ConnectionStatus.Reconnecting ?: return
        val target = activeTarget ?: connectingTarget
        if (target != null) {
            pausedAutoReconnect = PausedAutoReconnect(
                target = target,
            )
            activeTarget = target
            connectingTarget = target
            refreshReconnectAvailability()
        }
        _connectionStatus.value = ConnectionStatus.Failed(
            "${reconnecting.reason} Auto reconnect paused while PocketShell is in the background.",
        )
    }

    private fun resumePausedAutoReconnect(paused: PausedAutoReconnect) {
        pausedAutoReconnect = null
        val target = paused.target
        if (_connectionStatus.value is ConnectionStatus.Connected && activeTarget == target) return
        connect(
            host = target.host,
            port = target.port,
            user = target.user,
            keyPath = target.keyPath,
            passphrase = target.passphrase,
            hostId = target.hostId,
        )
    }

    /**
     * Issue #165: cancel an in-flight SSH connect attempt that the user
     * decides to abandon after the 15s "Cancel" affordance appears on the
     * progress overlay. Cancels the [connectJob] coroutine (any in-flight
     * SSH handshake will throw [CancellationException] and unwind), and
     * flips [_connectionStatus] to [ConnectionStatus.Failed] so the screen
     * surfaces a clear "Connect cancelled" message instead of looking
     * frozen on Connecting forever.
     *
     * No-op when there is no active connect job or the session has
     * already reached [ConnectionStatus.Connected] — the screen's Cancel
     * affordance is gated on the Connecting state anyway, but defensive
     * checks here mean a direct programmatic caller cannot accidentally
     * tear a live session down.
     *
     * Returns `true` when a cancel actually fired so callers can drive
     * post-cancel behaviour (e.g. pop the screen back to the host list)
     * without racing the state-flow update.
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

    public fun bindProjectNavigationHost(hostId: Long?) {
        if (_projectNavigation.value.hostId == hostId) return
        projectRootsJob?.cancel()
        _projectNavigation.value = ProjectNavigationUiState(hostId = hostId)
        if (hostId == null || projectRootDao == null) return
        projectRootsJob = viewModelScope.launch {
            projectRootDao.getByHostId(hostId).collectLatest { roots ->
                _projectNavigation.value = _projectNavigation.value.copy(roots = roots)
            }
        }
    }

    private suspend fun runConnect(
        host: String,
        port: Int,
        user: String,
        keyPath: String?,
        passphrase: CharArray?,
        hostId: Long?,
        producerScope: CoroutineScope,
    ) {
        var acquiredLease: SshLease? = null
        try {
            val key: SshKey = if (keyPath != null) {
                SshKey.Path(File(keyPath))
            } else {
                SshKey.Pem(readKeyFromRawResource(applicationContext))
            }
            val leaseResult = sshLeaseManager.acquire(
                SshLeaseTarget(
                    leaseKey = SshLeaseKey(
                        host = host,
                        port = port,
                        user = user,
                        credentialId = rawCredentialId(hostId, keyPath),
                        knownHostsId = "accept-all",
                    ),
                    key = key,
                    passphrase = passphrase?.copyOf(),
                    knownHosts = KnownHostsPolicy.AcceptAll,
                ),
            )
            val lease = leaseResult.getOrElse { e ->
                _connectionStatus.value = ConnectionStatus.Failed("connect failed: ${e.message}")
                return
            }
            acquiredLease = lease
            val session = lease.session
            leaseRef = lease
            sessionRef = session

            val shell = session.startShell()
            shellRef = shell

            val outputFlow = createStdoutFlow(shell.stdout)
                .onCompletion { cause -> markDisconnectedFromProducer(cause, host, port, user) }
            producerJob = terminalState.attachExternalProducer(
                scope = producerScope,
                stdout = outputFlow,
                remoteStdin = shell.stdin,
            )
            observeProducerCompletion(producerJob, host, port, user)

            // Kick the shell through the terminal bridge so sshj network I/O
            // stays on the bridge's background input-drainer thread.
            sendTerminalInput("\r".toByteArray())

            _connectionStatus.value = ConnectionStatus.Connected(host, port, user)
            activeTarget = RawSshTarget(host, port, user, keyPath, passphrase?.copyOf(), hostId)
            acquiredLease = null
            connectingTarget = null
            refreshReconnectAvailability()
            startAgentDetection(session)
        } catch (e: CancellationException) {
            clearUninstalledLeaseRefs(acquiredLease)
            throw e
        } catch (t: Throwable) {
            clearUninstalledLeaseRefs(acquiredLease)
            connectingTarget = RawSshTarget(host, port, user, keyPath, passphrase?.copyOf(), hostId)
            refreshReconnectAvailability()
            _connectionStatus.value =
                ConnectionStatus.Failed(
                    "error: ${t.javaClass.simpleName}: ${t.message}. Tap Reconnect to retry.",
                )
        } finally {
            val uninstalledLease = acquiredLease
            if (uninstalledLease != null) {
                withContext(NonCancellable) {
                    uninstalledLease.release()
                }
            }
        }
    }

    private fun clearUninstalledLeaseRefs(lease: SshLease?) {
        if (lease == null) return
        val shell = shellRef
        shellRef = null
        if (leaseRef === lease) {
            leaseRef = null
            sessionRef = null
        }
        closeIgnoringFailure { shell?.close() }
    }

    private fun startAgentDetection(session: SshSession) {
        agentDetectJob?.cancel()
        agentTailJob?.cancel()
        agentRetryJob?.cancel()
        _agentConversation.value = AgentConversationUiState()
        agentDetectJob = viewModelScope.launch {
            val detection = runCatching { agentRepository.detect(session) }.getOrNull() ?: return@launch
            startAgentConversation(session, detection)
        }
    }

    /**
     * Issue #165 test seam: stamp the ViewModel into [ConnectionStatus.Connecting]
     * with [connectJob] pointing at a caller-supplied [job] so unit tests can
     * exercise [cancelConnect] without spinning up the real SSH handshake.
     * Mirrors the early state setup [connect] would do before launching the
     * production handshake coroutine.
     */
    internal fun beginConnectingForTest(
        host: String,
        port: Int,
        user: String,
        job: Job,
    ): Job {
        _connectionStatus.value = ConnectionStatus.Connecting(host, port, user)
        connectJob = job
        connectingTarget = RawSshTarget(host, port, user, keyPath = null, passphrase = null, hostId = null)
        refreshReconnectAvailability()
        return job
    }

    internal fun markRawShellDisconnectedForTest(
        host: String,
        port: Int,
        user: String,
        cause: Throwable? = null,
        keyPath: String? = null,
    ) {
        activeTarget = RawSshTarget(host, port, user, keyPath = keyPath, passphrase = null, hostId = null)
        connectingTarget = null
        refreshReconnectAvailability()
        _connectionStatus.value = ConnectionStatus.Connected(host, port, user)
        markDisconnectedFromProducer(cause, host, port, user)
    }

    internal fun startAgentConversationForTest(
        detection: AgentDetection,
        initialEvents: List<ConversationEvent>,
    ) {
        _agentConversation.value = AgentConversationUiState(
            detection = detection,
            events = boundedDistinctEvents(initialEvents),
            selectedTab = SessionTab.Terminal,
        )
    }

    internal fun attachSessionForAgentRetryForTest(session: SshSession) {
        sessionRef = session
        _connectionStatus.value = ConnectionStatus.Connected("test", 0, "test")
    }

    /**
     * Issue #160 (review round 2) test seam: start the production
     * `session.tail` follow loop against [session] from
     * [fromLineExclusive] onward, plumbing every parsed event back
     * through [appendAgentEvents] (and therefore through the dedup
     * pass in [boundedDistinctEvents]). Used by
     * `ConversationInteractE2eTest` to exercise the full composer →
     * `sendToAgent` → SSH stdin → JSONL append → tail → conversation
     * pane journey against the Docker agent fixture without having
     * to spin up the production [startAgentDetection] path (which
     * scans for live processes the deterministic shim cannot
     * mimic).
     *
     * The returned [Job] is the live tail; tests cancel it (and the
     * VM's [onCleared] will cancel the stored [agentTailJob] as
     * usual on teardown).
     */
    internal fun startAgentTailForTest(
        session: SshSession,
        detection: AgentDetection,
        fromLineExclusive: Long,
    ): Job? {
        agentTailJob?.cancel()
        val job = agentRepository.tailEventsFromLine(session, detection, fromLineExclusive) { event ->
            appendAgentEvents(listOf(event))
        }
        agentTailJob = job
        observeAgentTailCompletion(job, detection)
        return job
    }

    public fun retryAgentConversationStream(): Boolean {
        val session = sessionRef?.takeIf { it.isConnected } ?: return false
        if (_connectionStatus.value !is ConnectionStatus.Connected) return false
        if (agentRetryJob?.isActive == true) return false
        val detection = markAgentConversationRetrying() ?: return false
        agentTailJob?.cancel()
        agentRetryJob = viewModelScope.launch {
            restartAgentConversationTail(session, detection)
        }
        return true
    }

    private suspend fun startAgentConversation(
        session: SshSession,
        detection: AgentDetection,
    ) {
        val lineCount = runCatching { agentRepository.lineCount(session, detection) }.getOrDefault(0L)
        val initialEvents = runCatching {
            agentRepository.readInitialEvents(session, detection)
        }.getOrDefault(emptyList())
        _agentConversation.value = AgentConversationUiState(
            detection = detection,
            events = boundedDistinctEvents(initialEvents),
            selectedTab = SessionTab.Terminal,
            syncStatus = AgentConversationSyncStatus.Live,
        )
        // Issue #160: OpenCode now uses the same `session.tail` route
        // Claude + Codex already use — no special polling branch.
        agentTailJob = agentRepository.tailEventsFromLine(session, detection, lineCount) { event ->
            appendAgentEvents(listOf(event))
        }
        observeAgentTailCompletion(agentTailJob, detection)
    }

    private suspend fun restartAgentConversationTail(
        session: SshSession,
        detection: AgentDetection,
    ) {
        val lineCount = runCatching { agentRepository.lineCount(session, detection) }
            .getOrElse {
                markAgentConversationSyncStatus(detection, AgentConversationSyncStatus.LogUnavailable)
                return
            }
        val initialEvents = runCatching {
            agentRepository.readInitialEvents(session, detection)
        }.getOrElse {
            markAgentConversationSyncStatus(detection, AgentConversationSyncStatus.LogUnavailable)
            return
        }
        _agentConversation.update { current ->
            if (current.detection != detection) {
                current
            } else {
                current.copy(
                    events = boundedDistinctEvents(current.events + initialEvents),
                    syncStatus = AgentConversationSyncStatus.Live,
                )
            }
        }
        val job = agentRepository.tailEventsFromLine(session, detection, lineCount) { event ->
            appendAgentEvents(listOf(event))
        }
        agentTailJob = job
        observeAgentTailCompletion(job, detection)
    }

    private fun appendAgentEvents(events: List<ConversationEvent>) {
        if (events.isEmpty()) return
        _agentConversation.update { current ->
            current.copy(events = boundedDistinctEvents(current.events + events))
        }
    }

    private fun observeAgentTailCompletion(job: Job?, detection: AgentDetection) {
        if (job == null) {
            markAgentConversationSyncStatus(detection, AgentConversationSyncStatus.LogUnavailable)
            return
        }
        job.invokeOnCompletion { cause ->
            if (cause is CancellationException) return@invokeOnCompletion
            markAgentTailStopped(detection, job, cause)
        }
    }

    private fun markAgentTailStopped(detection: AgentDetection, tailJob: Job, cause: Throwable?) {
        if (agentTailJob !== tailJob) return
        val nextStatus = if (cause == null) {
            AgentConversationSyncStatus.Stale
        } else {
            AgentConversationSyncStatus.LogUnavailable
        }
        markAgentConversationSyncStatus(detection, nextStatus)
    }

    private fun markAgentConversationRetrying(): AgentDetection? {
        var retryDetection: AgentDetection? = null
        _agentConversation.update { current ->
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

    private fun markAgentConversationSyncStatus(
        detection: AgentDetection,
        syncStatus: AgentConversationSyncStatus,
    ) {
        _agentConversation.update { current ->
            if (current.detection != detection || current.syncStatus == syncStatus) {
                current
            } else {
                current.copy(syncStatus = syncStatus)
            }
        }
    }

    private fun boundedDistinctEvents(events: List<ConversationEvent>): List<ConversationEvent> =
        reconcileAgentEvents(events, maxEvents = MaxAgentEvents)

    public fun selectSessionTab(tab: SessionTab) {
        _agentConversation.update { current ->
            if (tab == SessionTab.Conversation && current.detection == null) {
                current
            } else {
                current.copy(selectedTab = tab)
            }
        }
    }

    /**
     * Issue #154 (acceptance criterion #5): hoist the conversation
     * search query into the ViewModel so it survives Terminal ↔
     * Conversation tab switches. Bound to the search field's
     * `onValueChange` inside [ConversationPane].
     */
    public fun setAgentSearchQuery(query: String) {
        _agentConversation.update { current ->
            if (current.searchQuery == query) {
                current
            } else {
                current.copy(searchQuery = query)
            }
        }
    }

    /**
     * Handle a tap on a non-modifier key bar key (`Esc`, `Tab`, arrows).
     *
     * Modifier taps (`Ctrl`, `Alt`) never reach here: the ui-kit [KeyBar]
     * fires modifier taps through [onKeyBarModifierState] only, owning the
     * double-tap lock detection, while non-modifier taps fire through here.
     */
    public fun onKeyBarKey(label: String) {
        val unmodified: ByteArray = unmodifiedBytesFor(label) ?: return
        val modified = applyArmedModifiers(unmodified, label)
        terminalState.prepareForRawTerminalInput(smartTextPolicyForKeyBar(label))
        sendTerminalInput(modified)
        clearOneShotModifiers()
    }

    /**
     * Mirror modifier state changes from the ui-kit [KeyBar]. The ui-kit
     * performs the 350ms double-tap detection so its active/locked visual
     * state and this terminal-wire state transition together.
     */
    public fun onKeyBarModifierState(label: String, state: KeyModifierState) {
        val modifier = modifierForLabel(label) ?: return
        setModifierState(modifier, state)
    }

    /**
     * Write a literal command chip's text + Enter into the terminal. The
     * payload mirrors what would arrive from the system keyboard if the
     * user typed the command and pressed Enter — terminals echo it back,
     * so the visual confirmation comes for free.
     */
    public fun onChipTap(text: String) {
        sendText(text, withEnter = true)
    }

    public fun addProjectRoot(path: String, label: String = "") {
        val hostId = _projectNavigation.value.hostId
        if (hostId == null || projectRootDao == null) {
            updateProjectNavigationFeedback("Open a saved host before adding project roots.")
            return
        }
        val commandResult = ProjectNavigationCommands.cd(path)
        val normalized = path.trim()
        if (commandResult.isFailure) {
            updateProjectNavigationFeedback(commandResult.exceptionOrNull()?.message ?: "Invalid project root.")
            return
        }
        viewModelScope.launch {
            projectRootDao.insert(
                ProjectRootEntity(
                    hostId = hostId,
                    label = label.trim().ifBlank { normalized.substringAfterLast('/').ifBlank { normalized } },
                    path = normalized,
                ),
            )
            updateProjectNavigationFeedback("Project root saved.")
        }
    }

    public fun navigateToDirectory(path: String) {
        sendProjectCommand(
            result = ProjectNavigationCommands.cd(path),
            successMessage = "Running cd. Result will print in the terminal.",
            recentPath = path,
        )
    }

    public fun createFolderAndCd(root: String, folderName: String) {
        val target = root.trimEnd('/') + "/" + folderName.trim().trim('/')
        sendProjectCommand(
            result = ProjectNavigationCommands.mkdirAndCd(root, folderName),
            successMessage = "Running mkdir and cd. Result will print in the terminal.",
            recentPath = target,
        )
    }

    public fun cloneRepositoryAndCd(root: String, repository: String, folderName: String? = null) {
        val cleanFolder = folderName?.trim()?.takeIf { it.isNotEmpty() }
            ?: repository.trimEnd('/').substringAfterLast('/').substringAfterLast(':').removeSuffix(".git")
        val target = root.trimEnd('/') + "/" + cleanFolder
        sendProjectCommand(
            result = ProjectNavigationCommands.gitCloneAndCd(root, repository, folderName),
            successMessage = "Running git clone and cd. Result will print in the terminal.",
            recentPath = target,
        )
    }

    public fun clearProjectNavigationFeedback() {
        updateProjectNavigationFeedback(null)
    }

    /**
     * Issue #187: explicit-intent snippet send. Uses the caller-supplied
     * [withEnter] flag directly — `true` for `Send + ↵`, `false` for
     * plain `Send`. Wired by the snippet picker's chip row.
     *
     * Per D22 (issue #227) this is the only snippet entry point — the
     * legacy kind-aware `onSnippetPicked` smart default was removed
     * along with the picker's `onSnippetPicked` callback.
     */
    public fun sendSnippet(snippet: SnippetEntity, withEnter: Boolean) {
        val payload = snippetDispatchText(snippet, withEnter)
        if (payload.isEmpty()) return
        sendTerminalInput(payload.toByteArray(Charsets.UTF_8))
    }

    /**
     * Bind the SSH connection params the assistant needs for host-scoped
     * inspect / mutating tools (issue #266). Called by the screen with the
     * same resolved values the nav destination carries.
     */
    internal fun bindAssistant(
        hostId: Long?,
        hostName: String?,
        hostname: String?,
        port: Int,
        username: String?,
        keyPath: String?,
        passphrase: CharArray?,
    ) {
        assistantHostId = hostId
        assistantHostName = hostName
        assistantHostname = hostname
        assistantPort = port
        assistantUsername = username
        assistantKeyPath = keyPath
        assistantPassphrase = passphrase
    }

    /**
     * Voice Command-mode (and typed) action-assistant entry point (issue
     * #266). Replaces the deleted `planVoiceCommand`: the transcript is
     * handed to the agent loop, which inspects state and performs actions
     * through tools, gating mutating actions behind confirm-or-correct.
     */
    public fun dictateToAssistant(transcript: String) {
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

        val connected = _connectionStatus.value as? ConnectionStatus.Connected
        val activeHostName = assistantHostName ?: connected?.host ?: SessionDefaults.HOST
        val nav = _projectNavigation.value
        val recentDir = nav.recentDirectories.firstOrNull()?.takeIf { it.isNotBlank() }

        val bridge = object : SessionActionBridge {
            override fun activeHostName(): String? =
                if (_connectionStatus.value is ConnectionStatus.Connected) activeHostName else null
            override fun activeCwd(): String? = recentDir
            override fun activeSessionName(): String? = null
            override fun currentScreenLabel(): String = "raw-ssh session on $activeHostName"
            override suspend fun sendCommand(command: String): Result<Unit> {
                sendText(command, withEnter = true)
                return Result.success(Unit)
            }
            override suspend fun sendPromptToSession(sessionName: String, prompt: String): Result<Unit> {
                if (sessionName.isNotBlank() && sessionName != activeSessionName()) {
                    return Result.failure(IllegalStateException("Session $sessionName is not active."))
                }
                sendText(prompt, withEnter = true)
                return Result.success(Unit)
            }
            override fun navigate(destination: AppDestination) {
                _assistantNavRequests.tryEmit(destination)
            }
        }

        fun paramsForActive(): AssistantSshParams? {
            val id = assistantHostId ?: return null
            val key = assistantKeyPath ?: return null
            return AssistantSshParams(
                hostId = id,
                hostName = activeHostName,
                hostname = assistantHostname ?: connected?.host ?: SessionDefaults.HOST,
                port = assistantPort,
                username = assistantUsername ?: connected?.user ?: SessionDefaults.USER,
                keyPath = key,
                passphrase = assistantPassphrase,
            )
        }

        val actions: AssistantActions = AppAssistantActions(
            bridge = bridge,
            hostDao = dao,
            folderListGateway = gateway,
            reposRemoteSource = repos,
            sshExecutor = assistantSshExecutor,
            // The raw-SSH route only ever acts against its own connected
            // host; resolve only the active host by name and otherwise fall
            // back to the active params.
            resolveParams = { name -> paramsForActive()?.takeIf { it.hostName == name } },
            activeParams = ::paramsForActive,
        )

        val traceSink = ExecutorTraceSink(assistantSshExecutor, ::paramsForActive)

        return SessionAssistantController.AssistantRunDeps(
            client = client,
            actions = actions,
            traceSink = traceSink,
            installId = AssistantInstallId.get(applicationContext),
            sessionId = null,
        )
    }

    /**
     * Issue #160: send [text] as a chat message into the agent running
     * in the attached SSH shell.
     *
     * Adds an optimistic [ConversationEvent.Message] with
     * [ConversationRole.User] to the conversation feed first so the UI
     * updates without waiting for the agent's JSONL append. The text is
     * then written through the same terminal-input path used by chip
     * taps and snippet picks (`sendText` with a trailing carriage
     * return), so the agent's CLI sees it as a normal submitted prompt.
     *
     * No-op if no agent is currently detected — the conversation pane
     * isn't reachable in that state, but the defensive check matches
     * the rest of the public API.
     */
    public fun sendToAgent(text: String): Boolean = sendToAgentResult(text)

    public fun sendToAgentResult(text: String): Boolean {
        val payload = text.trim()
        if (payload.isEmpty()) return true
        val current = _agentConversation.value
        val detection = current.detection ?: return false
        // Issue #494: insert the optimistic pending turn FIRST — before any
        // delivery attempt — so the user always sees their own message the
        // instant they hit Send, with no "did it send?" gap. The turn starts
        // as [MessageSendState.Pending] ("sending…") and is reconciled away
        // when the real transcript entry arrives via the tail. If delivery
        // can't even be attempted (no live session), the turn flips to
        // [MessageSendState.Failed] so it is never silently dropped.
        val optimisticId = "$OPTIMISTIC_USER_MESSAGE_ID_PREFIX${System.nanoTime()}"
        val optimistic = ConversationEvent.Message(
            // Issue #160 round 2: prefix used by [reconcileAgentEvents]
            // to recognise the placeholder so it can be replaced by the
            // real `Message(role=User)` from the agent's JSONL tail.
            // [System.nanoTime] keeps each optimistic id unique so two
            // back-to-back identical prompts both stay visible until
            // their respective tails arrive.
            id = optimisticId,
            agent = detection.agent,
            atMillis = System.currentTimeMillis(),
            role = com.pocketshell.core.agents.ConversationRole.User,
            text = payload,
            sendState = com.pocketshell.core.agents.MessageSendState.Pending,
        )
        appendAgentEvents(listOf(optimistic))
        if (_connectionStatus.value !is ConnectionStatus.Connected) {
            // Issue #494: no live session — mark the optimistic turn failed
            // (with a retry affordance) instead of dropping the user's text.
            markOptimisticSendFailed(optimisticId)
            return false
        }
        sendText(payload, withEnter = true)
        return true
    }

    /**
     * Issue #494: flip the optimistic user turn [optimisticId] to
     * [com.pocketshell.core.agents.MessageSendState.Failed] so the
     * conversation feed shows it failed (with a retry affordance) rather
     * than leaving it stuck on "sending…" or dropping it entirely.
     */
    private fun markOptimisticSendFailed(optimisticId: String) {
        _agentConversation.update { current ->
            current.copy(events = current.events.markOptimisticFailed(optimisticId))
        }
    }

    /**
     * Issue #494: retry a previously-failed optimistic user turn. Drops the
     * failed placeholder and re-sends its text (which re-inserts a fresh
     * pending turn), so there is no double-send: the original failed turn is
     * removed before the new send is attempted.
     */
    public fun retryFailedAgentSend(optimisticId: String): Boolean {
        val failed = _agentConversation.value.events
            .filterIsInstance<ConversationEvent.Message>()
            .firstOrNull {
                it.id == optimisticId &&
                    it.sendState == com.pocketshell.core.agents.MessageSendState.Failed
            } ?: return false
        _agentConversation.update { current ->
            current.copy(events = current.events.filterNot { it.id == optimisticId })
        }
        return sendToAgentResult(failed.text)
    }

    /**
     * Shared text-entry path for composer, chips, snippets, and tests. This
     * intentionally funnels through [TerminalSurfaceState.writeInput], which
     * writes into the attached Termux [com.termux.terminal.TerminalSession]
     * and lets the SSH terminal bridge drain bytes to the remote stdin.
     */
    public fun sendText(text: String, withEnter: Boolean) {
        if (text.isEmpty() && !withEnter) return
        val payload = if (withEnter) text + "\r" else text
        sendTerminalInput(payload.toByteArray(Charsets.UTF_8))
    }

    public suspend fun stagePromptAttachments(uris: List<Uri>): Result<List<String>> {
        // Issue #451: the file picker backgrounds the app while selecting.
        // Attach now behaves like Send: on a not-currently-live session it
        // lazily connects-then-uploads instead of failing fast. #450 keeps
        // the terminal SSH session alive for a bounded grace window (~60s)
        // so the common quick round-trip returns to a still-live session and
        // the upload just works. If the round-trip outran the grace window
        // (or the OS killed the socket), Attach kicks the same connect-on-
        // action primitive Send uses ([reconnect], driven by [activeTarget])
        // and awaits the live session before uploading — the draft is
        // preserved if the (re)connect never lands within the bound.
        val session = awaitLiveSessionForAttachment()
            ?: return Result.failure(IllegalStateException("No live SSH session for attachment upload."))
        val target = activeTarget
        val scopeKey = when {
            target?.hostId != null -> "host-${target.hostId}"
            target != null -> "${target.user}-${target.host}-${target.port}"
            else -> "session"
        }
        return PromptAttachmentStager(
            resolver = applicationContext.contentResolver,
            cacheDir = applicationContext.cacheDir,
        ).stage(session, scopeKey, uris)
    }

    /**
     * Issue #451: return the live terminal [SshSession] for an attachment
     * upload, lazily connecting-then-awaiting the connection the way the
     * Send path does when the session is not currently live.
     *
     * Fast path: if the session is already live ([sessionRef] connected and
     * [connectionStatus] is [ConnectionStatus.Connected]) return it
     * immediately — the #450 grace window makes this the common case for a
     * quick picker round-trip.
     *
     * Slow path (connect-on-action, mirroring Send): if the session is not
     * live, kick the same connect primitive Send relies on. [reconnect]
     * (re)dials [activeTarget]/[connectingTarget]; it is a no-op when a
     * connect/reconnect is already in flight (#440 auto-reconnect), so this
     * is safe to call unconditionally. Then poll [connectionStatus] /
     * [sessionRef] up to [ATTACH_SESSION_WAIT_TIMEOUT_MS] and return the
     * session as soon as it becomes live. Returns null on timeout so the
     * caller surfaces the error with the draft preserved. Bounded and
     * foreground-only — no background work.
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

    public fun resizeRemotePty(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        if (columns == remoteColumns && rows == remoteRows) return
        remoteColumns = columns
        remoteRows = rows
        val shell = shellRef ?: return
        viewModelScope.launch(Dispatchers.IO) {
            shell.resizePty(columns, rows)
        }
    }

    private fun closeCurrentConnection(clearActiveTarget: Boolean = true) {
        producerJob?.cancel()
        terminalState.detachExternalProducer()
        val shell = shellRef
        val lease = leaseRef
        sessionRef = null
        shellRef = null
        leaseRef = null
        producerJob = null
        if (clearActiveTarget) {
            activeTarget = null
        }
        refreshReconnectAvailability()
        closeSshResourcesAsync(shell, lease)
    }

    private fun sendTerminalInput(bytes: ByteArray) {
        terminalState.writeInput(bytes)
    }

    private fun observeProducerCompletion(
        job: Job?,
        host: String,
        port: Int,
        user: String,
    ) {
        job?.invokeOnCompletion { cause ->
            markDisconnectedFromProducer(cause, host, port, user)
        }
    }

    private fun markDisconnectedFromProducer(
        cause: Throwable?,
        host: String,
        port: Int,
        user: String,
    ) {
        if (_connectionStatus.value !is ConnectionStatus.Connected) return
        val reason = when (cause) {
            null -> "Disconnected from $user@$host:$port. Tap Reconnect to retry."
            is CancellationException -> return
            else -> "Disconnected from $user@$host:$port: ${cause.javaClass.simpleName}: ${cause.message}. " +
                "Tap Reconnect to retry."
        }
        val target = activeTarget ?: connectingTarget ?: RawSshTarget(
            host = host,
            port = port,
            user = user,
            keyPath = null,
            passphrase = null,
            hostId = null,
        )
        scheduleAutoReconnect(target, reason)
    }

    private fun scheduleAutoReconnect(target: RawSshTarget, reason: String) {
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
                closeCurrentConnection(clearActiveTarget = false)
                connectingTarget = target
                refreshReconnectAvailability()
                runConnect(
                    host = target.host,
                    port = target.port,
                    user = target.user,
                    keyPath = target.keyPath,
                    passphrase = target.passphrase,
                    hostId = target.hostId,
                    producerScope = viewModelScope,
                )
                if (_connectionStatus.value is ConnectionStatus.Connected) {
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

    private fun sendProjectCommand(result: Result<String>, successMessage: String, recentPath: String) {
        result.fold(
            onSuccess = { command ->
                sendText(command, withEnter = true)
                rememberRecentDirectory(recentPath)
                updateProjectNavigationFeedback(successMessage)
            },
            onFailure = { error ->
                updateProjectNavigationFeedback(error.message ?: "Invalid project command.")
            },
        )
    }

    private fun rememberRecentDirectory(path: String) {
        val clean = path.trim()
        if (clean.isEmpty()) return
        val current = _projectNavigation.value
        _projectNavigation.value = current.copy(
            recentDirectories = (listOf(clean) + current.recentDirectories.filterNot { it == clean }).take(6),
        )
    }

    private fun updateProjectNavigationFeedback(message: String?) {
        _projectNavigation.value = _projectNavigation.value.copy(feedback = message)
    }

    /**
     * Translate a key-bar slot label into the bytes the unmodified key
     * sends on the wire. Returns `null` for unknown labels — callers
     * should silently swallow those (a future bar might add a slot the
     * ViewModel has not been taught about).
     *
     * Visible to the test seam so the byte-code mapping is exercised in
     * isolation from the modifier sticky FSM.
     */
    internal fun unmodifiedBytesFor(label: String): ByteArray? = when (label) {
        "Esc" -> byteArrayOf(0x1B)
        "Tab" -> byteArrayOf(0x09)
        "⏎", "Enter" -> byteArrayOf('\r'.code.toByte())
        "^C", "Ctrl-C" -> byteArrayOf(0x03)
        "^D", "Ctrl-D" -> byteArrayOf(0x04)
        // Mock-style arrows from `docs/mockups/session.html` and
        // `docs/input-methods.md`. `‹›` are left/right; `⌃⌄` are up/down
        // (see `.key.arrow` styling in `docs/mockups/styles.css`).
        "‹", "Left" -> byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())
        "⌃", "Up" -> byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())
        "⌄", "Down" -> byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())
        "›", "Right" -> byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte())
        else -> null
    }

    private fun smartTextPolicyForKeyBar(label: String): TerminalRawInputPolicy =
        when (label) {
            "⏎", "Enter" -> TerminalRawInputPolicy.FlushSmartText
            else -> TerminalRawInputPolicy.ClearSmartText
        }

    /**
     * Apply the currently-armed modifiers to a raw key payload. Public via
     * `internal` for unit tests; production callers go through
     * [onKeyBarKey].
     *
     * `label` is passed so we can detect the "Ctrl + ASCII letter" path
     * (which collapses to the single 0x01..0x1A control byte) versus
     * "Ctrl + Esc/Tab/arrow" (which falls back to the raw unmodified
     * bytes — see the class-level docs).
     */
    internal fun applyArmedModifiers(unmodified: ByteArray, label: String): ByteArray {
        val states = _modifierStates.value
        if (states.isEmpty()) return unmodified

        var bytes = unmodified

        if (states[Modifier.Ctrl] != null) {
            bytes = applyCtrl(bytes, label)
        }
        if (states[Modifier.Alt] != null) {
            // xterm-style "Meta sends Escape": prefix the bytes with ESC.
            bytes = byteArrayOf(0x1B, *bytes)
        }
        return bytes
    }

    private fun applyCtrl(bytes: ByteArray, label: String): ByteArray {
        // Single-byte ASCII letter? -> classic Ctrl-letter (0x01..0x1A).
        // The key bar does not own letter keys, so this branch is exercised
        // only by the test seam (which passes 'c' / 'C' / similar through
        // [unmodifiedBytesForTest]); inside the live screen Ctrl is paired
        // with Esc / Tab / arrows from the bar.
        if (bytes.size == 1) {
            val b = bytes[0].toInt() and 0x7F
            when (b.toChar()) {
                in 'a'..'z' -> return byteArrayOf((b - 'a'.code + 1).toByte())
                in 'A'..'Z' -> return byteArrayOf((b - 'A'.code + 1).toByte())
            }
        }
        // Ctrl + arrow → xterm modifyCursorKeys=2 format: ESC [ 1 ; 5 <X>
        if (bytes.size == 3 && bytes[0] == 0x1B.toByte() && bytes[1] == '['.code.toByte()) {
            val finalByte = bytes[2]
            return byteArrayOf(
                0x1B,
                '['.code.toByte(),
                '1'.code.toByte(),
                ';'.code.toByte(),
                '5'.code.toByte(),
                finalByte,
            )
        }
        // Otherwise we have no canonical encoding; pass through unmodified.
        return bytes
    }

    private fun clearOneShotModifiers() {
        val next = _modifierStates.value.filterValues { it == KeyModifierState.Locked }
        if (next != _modifierStates.value) setModifierStates(next)
    }

    private fun setModifierState(modifier: Modifier, state: KeyModifierState) {
        val current = _modifierStates.value
        val next = if (state == KeyModifierState.Off) current - modifier else current + (modifier to state)
        setModifierStates(next)
    }

    private fun setModifierStates(states: Map<Modifier, KeyModifierState>) {
        _modifierStates.value = states
        _armedModifiers.value = states.keys
    }

    private fun modifierForLabel(label: String): Modifier? = when (label) {
        "Ctrl" -> Modifier.Ctrl
        "Alt" -> Modifier.Alt
        else -> null
    }

    /**
     * Test seam: synthesise the unmodified bytes for an arbitrary ASCII
     * letter the way the bar would if it had a letter slot. Production
     * code routes through [onKeyBarKey], which never reaches a letter
     * branch (the bar only owns Esc/Tab/arrows + the Ctrl/Alt labels).
     *
     * The brief calls out "Ctrl + C → 0x03" as the canonical test case;
     * since the key bar never sends literal `c`, the test feeds the byte
     * directly via this seam and asserts the ViewModel wraps it correctly.
     */
    internal fun unmodifiedBytesForTest(letter: Char): ByteArray = byteArrayOf(letter.code.toByte())

    /**
     * Test seam: write a key directly with sticky modifiers applied, the
     * same way [onKeyBarKey] does for bar labels but with a caller-supplied
     * unmodified byte payload. Returns the bytes that would land on the
     * wire (the [terminalState] is not attached in unit tests, so the
     * write itself is a no-op).
     */
    internal fun writeKeyWithModifiersForTest(
        unmodified: ByteArray,
        label: String,
    ): ByteArray {
        val modified = applyArmedModifiers(unmodified, label)
        sendTerminalInput(modified)
        clearOneShotModifiers()
        return modified
    }

    override fun onCleared() {
        agentDetectJob?.cancel()
        agentTailJob?.cancel()
        assistant.dismiss()
        projectRootsJob?.cancel()
        producerJob?.cancel()
        terminalState.detachExternalProducer()
        closeCurrentConnection()
        super.onCleared()
    }

    private fun closeSshResourcesAsync(shell: SshShell?, lease: SshLease?) {
        if (shell == null && lease == null) return
        Thread(
            {
                closeIgnoringFailure { shell?.close() }
                closeIgnoringFailure {
                    if (lease != null) {
                        runBlocking(Dispatchers.IO) {
                            withContext(NonCancellable) {
                                lease.release()
                            }
                        }
                    }
                }
            },
            "PocketShellSshCleanup",
        ).apply {
            isDaemon = true
            start()
        }
    }

    private fun closeIgnoringFailure(block: () -> Unit) {
        try {
            block()
        } catch (_: Throwable) {
        }
    }

    /**
     * Sticky modifier identities. Kept here (not in the ui-kit) because
     * the wire encoding is the ViewModel's responsibility — the ui-kit
     * deliberately stays purely visual.
     */
    public enum class Modifier { Ctrl, Alt }

    /** Coarse-grained connection state surfaced in the breadcrumb's live dot. */
    public sealed interface ConnectionStatus {
        public object Idle : ConnectionStatus
        public data class Connecting(val host: String, val port: Int, val user: String) :
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

private val DEFAULT_AUTO_RECONNECT_DELAYS_MS: List<Long> = listOf(0L, 1_000L, 2_000L, 5_000L)

public enum class SessionTab { Terminal, Conversation }

public enum class AgentConversationSyncStatus {
    Live,
    Stale,
    LogUnavailable,
    Retrying,
}

public data class AgentConversationUiState(
    val detection: AgentDetection? = null,
    val events: List<ConversationEvent> = emptyList(),
    val selectedTab: SessionTab = SessionTab.Terminal,
    val syncStatus: AgentConversationSyncStatus = AgentConversationSyncStatus.Live,
    /**
     * Issue #154: persisted search query for the conversation pane. The
     * value lives on the ViewModel state (not as a local `remember` in
     * the pane composable) so the query survives Terminal ↔ Conversation
     * tab switches. Acceptance criterion (#5): the pane is the only
     * consumer of [searchQuery], and the ViewModel exposes a setter
     * (`setAgentSearchQuery` on `SessionViewModel` / `TmuxSessionViewModel`)
     * that the composer wires `onValueChange` into.
     */
    val searchQuery: String = "",
)

private data class RawSshTarget(
    val host: String,
    val port: Int,
    val user: String,
    val keyPath: String?,
    val passphrase: CharArray?,
    val hostId: Long?,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RawSshTarget) return false
        return host == other.host &&
            port == other.port &&
            user == other.user &&
            keyPath == other.keyPath &&
            hostId == other.hostId &&
            passphrasesEqual(passphrase, other.passphrase)
    }

    override fun hashCode(): Int {
        var result = host.hashCode()
        result = 31 * result + port
        result = 31 * result + user.hashCode()
        result = 31 * result + (keyPath?.hashCode() ?: 0)
        result = 31 * result + (passphrase?.contentHashCode() ?: 0)
        result = 31 * result + (hostId?.hashCode() ?: 0)
        return result
    }
}

private data class PausedAutoReconnect(
    val target: RawSshTarget,
)

private fun passphrasesEqual(left: CharArray?, right: CharArray?): Boolean = when {
    left == null && right == null -> true
    left == null || right == null -> false
    else -> left.contentEquals(right)
}

private fun rawCredentialId(hostId: Long?, keyPath: String?): String =
    when {
        hostId != null && keyPath != null -> "$hostId:$keyPath"
        keyPath != null -> "raw-path:$keyPath"
        else -> "raw-bundled-proof-key"
    }

private const val MaxAgentEvents: Int = 500

/**
 * Issue #451: how long [SessionViewModel.stagePromptAttachments] waits for
 * the terminal SSH session to come back live before failing the attachment
 * upload. Attach connects-on-action like Send: when the file picker
 * round-trip outran the #450 grace window (or the OS killed the socket) it
 * kicks [SessionViewModel.reconnect] and waits here for the (re)connect —
 * including the SSH handshake — to land. The auto-reconnect backoff chain
 * spans up to ~8s of delays alone, so the bound covers a full re-dial plus
 * handshake headroom. Bounded and foreground-only — no background work.
 */
internal const val ATTACH_SESSION_WAIT_TIMEOUT_MS: Long = 30_000L
internal const val ATTACH_SESSION_WAIT_POLL_MS: Long = 100L

/**
 * Default host parameters for the Phase 1 hardcoded landing — same values
 * as the Phase 0 proof-of-life screen used. `#18` introduces the saved-host
 * picker that drives these from storage.
 */
public object SessionDefaults {
    public const val HOST: String = "10.0.2.2"
    public const val PORT: Int = 2222
    public const val USER: String = "testuser"
}
