package com.pocketshell.app.session

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.R
import com.pocketshell.app.di.CommandPlannerClientFactory
import com.pocketshell.app.proof.SshShellHandle
import com.pocketshell.app.proof.createStdoutFlow
import com.pocketshell.app.proof.openShell
import com.pocketshell.app.proof.readKeyFromRawResource
import com.pocketshell.app.snippets.SnippetKind
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.storage.entity.SnippetEntity
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.voice.CommandPlan
import com.pocketshell.core.voice.CommandPlannerException
import com.pocketshell.core.voice.CommandPlannerRequest
import com.pocketshell.core.voice.CommandPlannerSafetyConstraints
import com.pocketshell.core.voice.CommandPlannerSessionMetadata
import com.pocketshell.uikit.model.KeyModifierState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import net.schmizz.sshj.connection.channel.direct.SessionChannel
import javax.inject.Inject

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
    private val commandPlannerClientFactory: CommandPlannerClientFactory = CommandPlannerClientFactory { null },
    private val projectRootDao: ProjectRootDao? = null,
) : ViewModel() {

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

    private val _voiceCommandReview: MutableStateFlow<VoiceCommandReviewUiState> =
        MutableStateFlow(VoiceCommandReviewUiState())

    public val voiceCommandReview: StateFlow<VoiceCommandReviewUiState> =
        _voiceCommandReview.asStateFlow()

    private val _projectNavigation: MutableStateFlow<ProjectNavigationUiState> =
        MutableStateFlow(ProjectNavigationUiState())

    public val projectNavigation: StateFlow<ProjectNavigationUiState> =
        _projectNavigation.asStateFlow()

    private val dismissedAgentHints: MutableSet<String> = mutableSetOf()

    private var sessionRef: SshSession? = null
    private var shellRef: SshShellHandle? = null
    private var remoteColumns: Int = 0
    private var remoteRows: Int = 0
    private var producerJob: Job? = null
    private var connectJob: Job? = null
    private var agentDetectJob: Job? = null
    private var agentTailJob: Job? = null
    private var commandPlannerJob: Job? = null
    private var projectRootsJob: Job? = null

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
    ) {
        if (connectJob?.isActive == true) return
        if (_connectionStatus.value is ConnectionStatus.Connected) return
        _connectionStatus.value = ConnectionStatus.Connecting(host, port, user)
        connectJob = viewModelScope.launch {
            runConnect(host, port, user, keyPath, passphrase, viewModelScope)
        }
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
        producerScope: CoroutineScope,
    ) {
        try {
            val key: SshKey = if (keyPath != null) {
                SshKey.Path(java.io.File(keyPath))
            } else {
                SshKey.Pem(readKeyFromRawResource(applicationContext))
            }
            val sessionResult = SshConnection.connect(
                host = host,
                port = port,
                user = user,
                key = key,
                passphrase = passphrase?.copyOf(),
                knownHosts = KnownHostsPolicy.AcceptAll,
            )
            val session = sessionResult.getOrElse { e ->
                _connectionStatus.value = ConnectionStatus.Failed("connect failed: ${e.message}")
                return
            }
            sessionRef = session

            val handle = openShell(host, port, user, key, passphrase?.copyOf())
            shellRef = handle

            val outputFlow = createStdoutFlow(handle.shell)
            producerJob = terminalState.attachExternalProducer(
                scope = producerScope,
                stdout = outputFlow,
                remoteStdin = handle.shell.outputStream,
            )

            // Kick the shell through the terminal bridge so sshj network I/O
            // stays on the bridge's background input-drainer thread.
            sendTerminalInput("\r".toByteArray())

            _connectionStatus.value = ConnectionStatus.Connected(host, port, user)
            startAgentDetection(session)
        } catch (t: Throwable) {
            _connectionStatus.value =
                ConnectionStatus.Failed("error: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    private fun startAgentDetection(session: SshSession) {
        agentDetectJob?.cancel()
        agentTailJob?.cancel()
        _agentConversation.value = AgentConversationUiState()
        agentDetectJob = viewModelScope.launch {
            val detection = runCatching { agentRepository.detect(session) }.getOrNull() ?: return@launch
            startAgentConversation(session, detection)
        }
    }

    internal fun startAgentConversationForTest(
        detection: AgentDetection,
        initialEvents: List<ConversationEvent>,
    ) {
        _agentConversation.value = AgentConversationUiState(
            detection = detection,
            events = boundedDistinctEvents(initialEvents),
            selectedTab = SessionTab.Terminal,
            hintVisible = true,
        )
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
        return job
    }

    private suspend fun startAgentConversation(
        session: SshSession,
        detection: AgentDetection,
    ) {
            val hintKey = detection.sessionId ?: detection.sourcePath
            val lineCount = runCatching { agentRepository.lineCount(session, detection) }.getOrDefault(0L)
            val initialEvents = runCatching {
                agentRepository.readInitialEvents(session, detection)
            }.getOrDefault(emptyList())
            _agentConversation.value = AgentConversationUiState(
                detection = detection,
                events = boundedDistinctEvents(initialEvents),
                selectedTab = SessionTab.Terminal,
                hintVisible = hintKey !in dismissedAgentHints,
            )
            // Issue #160: OpenCode now uses the same `session.tail` route
            // Claude + Codex already use — no special polling branch.
            agentTailJob = agentRepository.tailEventsFromLine(session, detection, lineCount) { event ->
                appendAgentEvents(listOf(event))
            }
    }

    private fun appendAgentEvents(events: List<ConversationEvent>) {
        if (events.isEmpty()) return
        val current = _agentConversation.value
        _agentConversation.value = current.copy(events = boundedDistinctEvents(current.events + events))
    }

    private fun boundedDistinctEvents(events: List<ConversationEvent>): List<ConversationEvent> =
        reconcileAgentEvents(events, maxEvents = MaxAgentEvents)

    public fun selectSessionTab(tab: SessionTab) {
        val current = _agentConversation.value
        if (tab == SessionTab.Conversation && current.detection == null) return
        _agentConversation.value = current.copy(selectedTab = tab, hintVisible = false)
    }

    public fun dismissAgentHint() {
        val current = _agentConversation.value
        val detection = current.detection ?: return
        dismissedAgentHints += detection.sessionId ?: detection.sourcePath
        _agentConversation.value = current.copy(hintVisible = false)
    }

    /**
     * Handle a tap on the key bar.
     *
     * `Esc`, `Tab`, arrows and the modifier names (`Ctrl`, `Alt`) all flow
     * through here. Modern callers should route modifier taps through
     * [onKeyBarModifierState] so the ui-kit visuals can own double-tap
     * detection; the modifier cases here remain for legacy/test callers.
     */
    public fun onKeyBarKey(label: String) {
        when (label) {
            "Ctrl" -> toggleModifier(Modifier.Ctrl)
            "Alt" -> toggleModifier(Modifier.Alt)
            else -> {
                val unmodified: ByteArray = unmodifiedBytesFor(label) ?: return
                val modified = applyArmedModifiers(unmodified, label)
                sendTerminalInput(modified)
                clearOneShotModifiers()
            }
        }
    }

    /**
     * Public seam for tests + the on-screen "modifier armed" hint. Used by
     * [onKeyBarKey] for the `Ctrl` / `Alt` labels and exposed so the screen
     * can show a small chip while a modifier is armed.
     */
    internal fun toggleModifier(modifier: Modifier) {
        val current = _modifierStates.value[modifier] ?: KeyModifierState.Off
        val next = if (current == KeyModifierState.Off) {
            KeyModifierState.OneShot
        } else {
            KeyModifierState.Off
        }
        setModifierState(modifier, next)
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
     * Route a picked snippet through the same terminal input bridge as the
     * key bar and command chips.
     *
     * Command snippets execute immediately by appending the same carriage
     * return byte Termux emits for keyboard Enter. Prompt snippets paste only
     * so the user can continue editing before pressing Enter manually.
     */
    public fun onSnippetPicked(snippet: SnippetEntity) {
        when (SnippetKind.fromStorage(snippet.kind)) {
            SnippetKind.Command -> sendText(snippet.body, withEnter = true)
            SnippetKind.Prompt -> sendText(snippet.body, withEnter = false)
        }
    }

    /**
     * Voice Command-mode transcript entry point. Prompt-mode dictation keeps
     * using [sendText] directly; Command mode comes here so generated shell
     * commands are planned, validated, and held for explicit review.
     */
    public fun planVoiceCommand(transcript: String) {
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

        _voiceCommandReview.value = VoiceCommandReviewUiState(isPlanning = true, transcript = cleaned)
        commandPlannerJob = viewModelScope.launch {
            val result = client.plan(
                CommandPlannerRequest(
                    transcript = cleaned,
                    session = commandPlannerSessionMetadata(),
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

    public fun approvePendingVoiceCommand(withEnter: Boolean) {
        val plan = _voiceCommandReview.value.pendingPlan ?: return
        sendText(plan.commands.joinToString("\n") { it.command }, withEnter = withEnter)
        _voiceCommandReview.value = VoiceCommandReviewUiState()
    }

    public fun dismissVoiceCommandReview() {
        commandPlannerJob?.cancel()
        _voiceCommandReview.value = VoiceCommandReviewUiState()
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
    public fun sendToAgent(text: String) {
        val payload = text.trim()
        if (payload.isEmpty()) return
        val current = _agentConversation.value
        val detection = current.detection ?: return
        val optimistic = ConversationEvent.Message(
            // Issue #160 round 2: prefix used by [reconcileAgentEvents]
            // to recognise the placeholder so it can be replaced by the
            // real `Message(role=User)` from the agent's JSONL tail.
            // [System.nanoTime] keeps each optimistic id unique so two
            // back-to-back identical prompts both stay visible until
            // their respective tails arrive.
            id = "$OPTIMISTIC_USER_MESSAGE_ID_PREFIX${System.nanoTime()}",
            agent = detection.agent,
            atMillis = System.currentTimeMillis(),
            role = com.pocketshell.core.agents.ConversationRole.User,
            text = payload,
        )
        appendAgentEvents(listOf(optimistic))
        sendText(payload, withEnter = true)
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

    public fun resizeRemotePty(columns: Int, rows: Int) {
        if (columns <= 0 || rows <= 0) return
        if (columns == remoteColumns && rows == remoteRows) return
        remoteColumns = columns
        remoteRows = rows
        val channel = shellRef?.sessionChannel as? SessionChannel ?: return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { channel.changeWindowDimensions(columns, rows, 0, 0) }
        }
    }

    private fun sendTerminalInput(bytes: ByteArray) {
        terminalState.writeInput(bytes)
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

    private fun commandPlannerSessionMetadata(): CommandPlannerSessionMetadata {
        val connected = _connectionStatus.value as? ConnectionStatus.Connected
        val nav = _projectNavigation.value
        // Opportunistic cwd capture: the project-navigation chips
        // ([navigateToDirectory], [createFolderAndCd], [cloneRepositoryAndCd])
        // push the target path onto [recentDirectories] the moment the
        // command is dispatched. The freshest entry is therefore the
        // best directory the ViewModel knows about — better than `null`,
        // and free of the round-trip a periodic `pwd` would cost. When
        // the user has not yet navigated this session, fall back to null
        // and let the planner default to `~`.
        val recentDir = nav.recentDirectories.firstOrNull()?.takeIf { it.isNotBlank() }
        return CommandPlannerSessionMetadata(
            hostLabel = connected?.host ?: SessionDefaults.HOST,
            username = connected?.user ?: SessionDefaults.USER,
            currentDirectory = recentDir,
            projectRoots = nav.roots.map { it.path },
            // Raw-SSH shell type detection (e.g. parsing `$SHELL` or
            // `ps -p $$`) requires a round-trip per session boot and is
            // covered by the follow-up to #66 — leaving null until we
            // wire a one-shot probe at connect time.
            shellType = null,
        )
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
        // Mock-style arrows from `docs/mockups/session.html` and
        // `docs/input-methods.md`. `‹›` are left/right; `⌃⌄` are up/down
        // (see `.key.arrow` styling in `docs/mockups/styles.css`).
        "‹", "Left" -> byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())
        "⌃", "Up" -> byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())
        "⌄", "Down" -> byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())
        "›", "Right" -> byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte())
        else -> null
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
        commandPlannerJob?.cancel()
        projectRootsJob?.cancel()
        producerJob?.cancel()
        terminalState.detachExternalProducer()
        val shell = shellRef
        val session = sessionRef
        sessionRef = null
        shellRef = null
        producerJob = null
        closeSshResourcesAsync(shell, session)
        super.onCleared()
    }

    private fun closeSshResourcesAsync(shell: SshShellHandle?, session: SshSession?) {
        if (shell == null && session == null) return
        Thread(
            {
                closeIgnoringFailure { shell?.shell?.close() }
                closeIgnoringFailure { shell?.sessionChannel?.close() }
                // The SSHJ transport can throw a BY_APPLICATION disconnect
                // exception during Activity teardown even when the close is
                // intentional. Keep explicit transport cleanup, but keep it
                // off the lifecycle callback thread and suppress teardown
                // noise locally.
                closeIgnoringFailure { shell?.client?.disconnect() }
                closeIgnoringFailure { session?.close() }
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
        public data class Failed(val message: String) : ConnectionStatus
    }
}

public enum class SessionTab { Terminal, Conversation }

public data class AgentConversationUiState(
    val detection: AgentDetection? = null,
    val events: List<ConversationEvent> = emptyList(),
    val selectedTab: SessionTab = SessionTab.Terminal,
    val hintVisible: Boolean = false,
)

public data class VoiceCommandReviewUiState(
    val isPlanning: Boolean = false,
    val transcript: String? = null,
    val pendingPlan: CommandPlan? = null,
    val error: String? = null,
)

private const val MaxAgentEvents: Int = 500

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
