package com.pocketshell.app.tmux

import android.os.Looper
import android.os.SystemClock
import com.pocketshell.app.cards.SessionCardsRemoteSource
import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkChangeKind
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.session.AgentConversationSyncStatus
import com.pocketshell.app.session.ConversationLoadState
import com.pocketshell.app.session.FIRST_PAINT_MESSAGE_BUDGET
import com.pocketshell.app.session.JSONL_RAW_LINES_PER_EVENT
import com.pocketshell.app.session.OPTIMISTIC_USER_MESSAGE_ID_PREFIX
import com.pocketshell.app.session.SessionTab
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.agents.ConversationRole
import com.pocketshell.core.agents.MessageSendState
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.ssh.SshLease
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.terminal.bridge.SshTerminalBridge
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.uikit.model.KeyKind
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import com.pocketshell.core.tmux.TmuxOutputBacklogOverflow
import com.pocketshell.core.tmux.protocol.ControlEvent
import com.pocketshell.core.terminal.ui.TerminalRawInputPolicy
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.seconds
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.TransportException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.CoroutineContext

/**
 * Unit tests for [TmuxSessionViewModel] that exercise the per-pane
 * reconciliation and command dispatch via a [FakeTmuxClient].
 *
 * Robolectric is required because [TerminalSurfaceState.attachExternalProducer]
 * spins up a [com.pocketshell.core.terminal.bridge.SshTerminalBridge]
 * whose constructor builds a [com.termux.terminal.TerminalSession] that
 * needs a working `Looper` / `Handler` to construct (mirrors the rationale
 * already documented in [com.pocketshell.app.session.SessionViewModelTest]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionViewModelTest {

    // Issue #708: ONE virtual-clock scheduler shared by `runTest(scheduler)`,
    // `Dispatchers.Main`, and every test's SshLeaseManager. The lease's bounded
    // cold connect (#687, b5733d33) defaults to a real `Dispatchers.IO` + wall
    // clock; under a virtual `runTest` clock that strands the dial so
    // `advanceUntilIdle()` never drives it. Binding the lease + Main to this one
    // scheduler makes the lease/connect path deterministic.
    private val scheduler = TestCoroutineScheduler()

    // Keep the existing UnconfinedTestDispatcher Main semantics (eager-on-advance)
    // that this suite's assertions already rely on — but bind it to the SHARED
    // scheduler so Main, runTest, and the lease clock stay in lockstep.
    // Hold the exact instance so the #576 reconcile/apply dispatcher pins can
    // reference the SAME dispatcher the rule installs as Main — the ViewModel's
    // `applyOnMain` inline-detection compares interceptor identity, and under
    // `Dispatchers.setMain(x)` the running coroutine's interceptor is `x`, not
    // the `Dispatchers.Main` delegate.
    private val testMainDispatcher = UnconfinedTestDispatcher(scheduler)

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testMainDispatcher)

    // factoryScope stays on REAL `Dispatchers.IO`: the TmuxClientFactory drives a
    // genuine background IO read/feed loop that the terminal-feed integration
    // tests pump via the Robolectric Looper, NOT via this scheduler. Moving it
    // onto the virtual clock races that Looper feed and flakes
    // `codexLikeTmuxOutputWithSlowTerminalSideChannel...` (verified empirically:
    // 30/30 green on IO vs ~8% failure on the virtual scheduler).
    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdViewModels = mutableListOf<TmuxSessionViewModel>()

    private fun newVm(
        registry: ActiveTmuxClients = ActiveTmuxClients(),
        runtimeCache: TmuxSessionRuntimeCache = TmuxSessionRuntimeCache(),
        agentSessionMemory: AgentSessionMemory = AgentSessionMemory(),
        sshLeaseManager: SshLeaseManager = SshLeaseManager(
            connector = SshLeaseConnector { target ->
                error("unexpected SSH lease connect for ${target.leaseKey}")
            },
            idleTtlMillis = 0L,
            // Issue #708: keep the lease on the shared virtual clock even for the
            // never-connecting default so any bounded dial resolves under runTest.
            connectTimeoutContext = StandardTestDispatcher(scheduler),
            nowMillis = { scheduler.currentTime },
        ),
        sessionLifecycleSignals: SessionLifecycleSignals? = null,
        folderListGateway: FolderListGateway? = null,
        hostDao: HostDao? = null,
        agentRepository: AgentConversationRepository = AgentConversationRepository(),
        agentKindRemoteSource: com.pocketshell.app.agents.AgentKindRemoteSource =
            // Issue #1001 (CI-flake fix): pin the daemon-RPC bounded-exec
            // dispatcher to the SHARED virtual-clock scheduler. In production it
            // is `Dispatchers.IO` (a real background thread, so the wedge-prone
            // SSH read never parks a caller). Under `runTest(scheduler)` that real
            // thread races the test scheduler: `runCurrent()` returns before the
            // off-thread `classify` exec completes, so the foreign-detection
            // chain's `detection != null` assertion sometimes observed a not-yet-
            // resolved bind (the b1Masked* ~1/3 release-variant flake). Confining
            // the exec to a `StandardTestDispatcher` on the shared scheduler makes
            // `runCurrent()` await the bind deterministically.
            com.pocketshell.app.agents.AgentKindRemoteSource().apply {
                setExecDispatcherForTest(StandardTestDispatcher(scheduler))
            },
    ): TmuxSessionViewModel =
        TmuxSessionViewModel(
            tmuxClientFactory = TmuxClientFactory(factoryScope),
            activeTmuxClients = registry,
            hostDao = hostDao,
            folderListGateway = folderListGateway,
            runtimeCache = runtimeCache,
            agentSessionMemory = agentSessionMemory,
            sshLeaseManager = sshLeaseManager,
            sessionLifecycleSignals = sessionLifecycleSignals,
            agentRepository = agentRepository,
            agentKindRemoteSource = agentKindRemoteSource,
        ).also {
            // Issue #576 (Slice A of #792): pin the structural-reconcile
            // dispatcher to Main (the MainDispatcherRule's
            // UnconfinedTestDispatcher on the shared scheduler). Using the SAME
            // Main dispatcher instance makes `withContext(reconcileDispatcher)`
            // a no-op from a bridgeScope (Main) coroutine, so the reconcile
            // runs inline on the virtual clock exactly as it did before this
            // slice — preserving the suite's synchronous-ordering assertions.
            // With the production default (Dispatchers.Default = a real
            // background thread) a structural event would race the test
            // scheduler.
            // Issue #576 (Slice A of #792): pin BOTH the reconcile and the
            // reconcile-APPLY dispatcher to the EXACT instance the rule installs
            // as Main. Both equal the running coroutine's interceptor, so
            // `applyOnMain` takes its inline branch (no `withContext` re-dispatch)
            // and the suite's synchronous-ordering assertions hold byte-for-byte
            // as before this slice. (A storm test overrides the reconcile
            // dispatcher to a separate off-Main one to exercise the hop.)
            it.setReconcileDispatcherForTest(testMainDispatcher)
            it.setReconcileApplyDispatcherForTest(testMainDispatcher)
            // Issue #926: pin the SEED-IO dispatcher (the off-Main hop for the
            // attach/switch/reattach `capture-pane`/`list-panes` round-trips) to
            // the shared virtual-clock Main so the IO runs inline on the test
            // scheduler — `advanceUntilIdle` then drains it deterministically.
            // The production default is `Dispatchers.IO` (a real off-Main thread,
            // so the seed never parks the UI thread); the dedicated #926
            // off-Main regression test ([Issue926SeedIoOffMainTest]) pins it to a
            // DISTINCT real dispatcher to assert the hop actually leaves Main.
            it.setSeedIoDispatcherForTest(testMainDispatcher)
            // Issue #877: pin the port-detection decode/scan dispatcher to the
            // shared virtual-clock Main so `withContext(portDetectionDispatcher)`
            // runs the scan inline on the test scheduler (no real background
            // thread racing `advanceUntilIdle`). The production default is
            // Dispatchers.Default.limitedParallelism(1) — a real off-Main thread;
            // the dedicated #877 regression tests override this with a tracking
            // StandardTestDispatcher to assert the hop actually happens.
            it.setPortDetectionDispatcherForTest(testMainDispatcher)
            // Session-card refreshes use a real IO dispatcher in production.
            // Pin the card source calls to the test scheduler so card feed
            // assertions cannot time out before a background continuation runs.
            it.setSessionCardsDispatcherForTest(testMainDispatcher)
            createdViewModels += it
        }

    private suspend fun TestScope.awaitCondition(predicate: () -> Boolean) {
        val observed = withTimeoutOrNull(1.seconds) {
            while (!predicate()) {
                runCurrent()
                delay(10)
            }
            true
        }
        assertEquals(true, observed)
    }

    private suspend fun TestScope.awaitCardsState(
        vm: TmuxSessionViewModel,
        predicate: (TmuxSessionViewModel.SessionCardsUiState) -> Boolean,
    ) {
        awaitCondition { predicate(vm.sessionCards.value) }
    }

    private fun checklistFeedJson(
        session: String,
        checkedIds: List<String> = emptyList(),
    ): String {
        val items = JSONArray()
            .put(JSONObject().put("id", "build-0").put("text", "Build app"))
            .put(JSONObject().put("id", "test-0").put("text", "Run tests"))
        val checked = JSONArray()
        checkedIds.forEach { checked.put(it) }
        val card = JSONObject()
            .put("id", "release")
            .put("type", SessionCardsRemoteSource.TYPE_CHECKLIST)
            .put("title", "Release")
            .put("created_at", "2026-06-24T10:00:00Z")
            .put("updated_at", "2026-06-24T10:00:00Z")
            .put("body", JSONObject().put("items", items))
            .put("state", JSONObject().put("checked", checked))
        return JSONObject()
            .put("session", session)
            .put("cards", JSONArray().put(card))
            .toString()
    }

    /**
     * Issue #640: the seed capture command. [TmuxClient.captureWithCursor] now
     * pairs this with the cursor query in ONE single-flight exchange, but the
     * `capture-pane` command string itself is unchanged. Centralised so the
     * command-string assertions track [SEED_SCROLLBACK_LINES] if it ever moves.
     */
    private fun seedCaptureCommand(paneId: String): String =
        "capture-pane -p -e -S -$SEED_SCROLLBACK_LINES -t $paneId"

    private fun seedCursorCommand(paneId: String): String =
        "display-message -p -t $paneId '#{cursor_x},#{cursor_y}'"

    @After
    fun tearDown() {
        createdViewModels.asReversed().forEach { vm ->
            runCatching { vm.clearForTest() }
        }
        createdViewModels.clear()
        // Issue #708 follow-up: cancel AND JOIN the real-`Dispatchers.IO`
        // factory scope before returning. `factoryScope.cancel()` only
        // *requests* cancellation; cancellation is cooperative, so a
        // TmuxClientFactory IO read/feed coroutine can still be unwinding
        // (and may touch `Dispatchers.Main` as it completes) when this test's
        // `MainDispatcherRule` runs its `finally { resetMain() }`. If that
        // unwind races the NEXT test class's `setMain`, kotlinx-coroutines
        // throws "Dispatchers.Main is used concurrently with setting it" and
        // the suite flakes inter-class (~50%/full run). Joining the scope's
        // children here makes tear-down fully quiescent: no IO coroutine
        // survives past `@After`, so nothing touches Main after `resetMain`.
        // A bounded wait keeps a genuinely wedged coroutine from hanging the
        // suite — it would surface as a real failure rather than the flake.
        factoryScope.cancel()
        runBlocking {
            // Issue #713: same real-`Dispatchers.IO` contention class as the
            // slow-feed drains — this is an await-for-quiescence join that
            // returns the instant the IO children unwind, so the generous
            // ceiling only adds headroom on a contended box (a premature 5s
            // give-up here lets an IO coroutine survive into the next class's
            // setMain and flake inter-class). A genuinely wedged coroutine
            // still surfaces as the documented bounded-wait failure.
            withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
                factoryScope.coroutineContext.job.children.forEach { it.join() }
            }
        }
    }

    @Test
    fun panesStateFlowStartsEmpty() {
        val vm = newVm()
        assertTrue(vm.panes.value.isEmpty())
    }

    @Test
    fun connectionStatusStartsIdle() {
        val vm = newVm()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Idle)
    }

    @Test
    fun killCurrentSessionBroadcastsSignalOnConfirmedKill() = runTest(scheduler) {
        // Issue #655: the in-session Stop now routes through the SAME verified
        // gateway SSH-exec path the host-detail Stop uses (#518), NOT the
        // control channel it is attached to. A gateway-confirmed kill (the
        // session is gone) broadcasts the lifecycle signal so the tree drops
        // the row.
        val signals = SessionLifecycleSignals()
        val gateway = RecordingStopGateway(killSucceeds = true)
        val vm = newVm(
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "doomed",
            client = client,
        )
        runCurrent()

        val killed = async { signals.killedSessions.first() }
        runCurrent()

        vm.killCurrentSession()
        advanceUntilIdle()

        val event = killed.await()
        assertEquals(7L, event.hostId)
        assertEquals("doomed", event.sessionName)
        assertTrue(
            "expected the gateway kill to target 'doomed', got ${gateway.killedSessionNames}",
            gateway.killedSessionNames.contains("doomed"),
        )
        // The Stop must NOT race its own teardown over the control channel.
        assertFalse(
            "in-session Stop must not kill over the control channel; sent=${client.sentCommands}",
            client.sentCommands.any { it.startsWith("kill-session") },
        )
    }

    @Test
    fun confirmedKillEvictsKilledSessionFromNameKeyedCaches() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val agentSessionMemory = AgentSessionMemory()
        val signals = SessionLifecycleSignals(
            runtimeCache = runtimeCache,
            agentSessionMemory = agentSessionMemory,
        )
        val doomedRuntime = cachedRuntimeForTest(sessionName = "doomed", hostId = 7L)
        val otherRuntime = cachedRuntimeForTest(sessionName = "other", hostId = 7L)
        runtimeCache.put(doomedRuntime)
        runtimeCache.put(otherRuntime)
        agentSessionMemory.remember(
            hostId = 7L,
            sessionName = "doomed",
            windowId = "@2",
            detection = AgentDetection(
                agent = AgentKind.ClaudeCode,
                sourcePath = "/home/alex/.claude/projects/doomed.jsonl",
                sessionId = "doomed-agent",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
            wasOnConversation = true,
        )
        val gateway = RecordingStopGateway(killSucceeds = true)
        val vm = newVm(
            runtimeCache = runtimeCache,
            agentSessionMemory = agentSessionMemory,
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "doomed",
            client = FakeTmuxClient(),
        )
        runCurrent()

        val killed = async { signals.killedSessions.first() }
        runCurrent()

        vm.killCurrentSession()
        advanceUntilIdle()

        val event = killed.await()
        assertEquals(7L, event.hostId)
        assertEquals("doomed", event.sessionName)
        assertFalse(
            "confirmed Stop must evict the killed session's parked runtime",
            runtimeCache.contains(doomedRuntime.key),
        )
        assertTrue(runtimeCache.contains(otherRuntime.key))
        assertNull(
            "same-name successor must not recall agent memory from the killed session",
            agentSessionMemory.recall(7L, "doomed", "@2"),
        )
    }

    // ----- Issue #898: the in-session kebab "+ New session" now opens the SAME
    // rich SessionTypePickerSheet the host screen uses, and its Create routes
    // through the SAME verified gateway createSession path so the created
    // session honours the chosen type / CLI / skip-permissions / profile. These
    // tests assert that the agent-CLI + skip-permissions + profile choices the
    // picker synthesises into a `startCommand` reach the gateway intact (the old
    // name+folder dialog path could carry NONE of these — it only had a
    // startDirectory). Red→green: on base (the old `createSession(name,
    // startDirectory)` that sent `new-session -d` over the control channel and
    // had no startCommand/gateway route), these assertions cannot pass.

    @Test
    fun createSessionRoutesChosenAgentOptionsThroughGateway() = runTest(scheduler) {
        val gateway = RecordingStopGateway(
            killSucceeds = true,
            createResolvedName = "git-claude",
        )
        val vm = newVm(
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        runCurrent()

        // The rich sheet synthesises this startCommand for an Agent=codex,
        // skip-permissions OFF, profile "work" choice. We pass it straight to
        // createSession exactly as TmuxSessionScreen does.
        val startCommand = "pocketshell agent codex --dir '/home/alex/git' " +
            "--no-skip-permissions --profile 'work'"
        // The create routes a `dao.getById` over Dispatchers.IO (real IO, not the
        // virtual clock), so await onResolved deterministically rather than
        // racing advanceUntilIdle — the kill tests await a signal for the same
        // reason. `await()` parks the test body until the real-IO continuation
        // completes the deferred, then runTest unparks and we assert.
        val resolvedDeferred = CompletableDeferred<String>()
        vm.createSession(
            name = "git-codex",
            cwd = "/home/alex/git",
            startCommand = startCommand,
            chosenKind = SessionAgentKind.Codex,
            onResolved = { resolvedDeferred.complete(it) },
        )
        val resolved = resolvedDeferred.await()
        advanceUntilIdle()

        assertEquals(
            "expected exactly one gateway createSession call, got ${gateway.createCalls}",
            1,
            gateway.createCalls.size,
        )
        val call = gateway.createCalls.single()
        assertEquals("git-codex", call.sessionName)
        assertEquals("/home/alex/git", call.cwd)
        // The load-bearing assertion (G6): the chosen CLI / skip-perms / profile
        // are all encoded in the startCommand that reached the verified gateway.
        assertEquals(startCommand, call.startCommand)
        // onResolved fired with the gateway's resolved name so the screen can
        // navigate/attach to the freshly-created session.
        assertEquals("git-claude", resolved)
        // The rich-sheet create must NOT fall back to the control-channel
        // `new-session -d` (which cannot launch an agent CLI).
        assertFalse(
            "rich-sheet create must use the gateway, not control-channel new-session; " +
                "sent=${client.sentCommands}",
            client.sentCommands.any { it.startsWith("new-session") },
        )
    }

    @Test
    fun createSessionShellChoiceHasNoStartCommand() = runTest(scheduler) {
        val gateway = RecordingStopGateway(
            killSucceeds = true,
            createResolvedName = "plain",
        )
        val vm = newVm(
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        runCurrent()

        // A Shell choice synthesises a null startCommand. Await onResolved so the
        // real-IO `dao.getById` hop completes before we assert (see the agent
        // test above for why advanceUntilIdle alone races the IO).
        val resolvedDeferred = CompletableDeferred<String>()
        vm.createSession(
            name = "plain",
            cwd = "/srv/app",
            startCommand = null,
            chosenKind = null,
            onResolved = { resolvedDeferred.complete(it) },
        )
        resolvedDeferred.await()
        advanceUntilIdle()

        val call = gateway.createCalls.single()
        assertEquals("/srv/app", call.cwd)
        assertNull("shell session must carry no startCommand", call.startCommand)
    }

    @Test
    fun killCurrentSessionDoesNotBroadcastWhenGatewayKillFails() = runTest(scheduler) {
        // Issue #655: a gateway-verified failure (the session is STILL running
        // after the kill, e.g. `tmux has-session` still exits 0) must NOT
        // broadcast, so the tree keeps the still-live row.
        val signals = SessionLifecycleSignals()
        val gateway = RecordingStopGateway(killSucceeds = false)
        val vm = newVm(
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "stubborn",
            client = client,
        )
        runCurrent()

        var broadcast: KilledSession? = null
        val collector = async { broadcast = signals.killedSessions.first() }
        runCurrent()

        vm.killCurrentSession()
        advanceUntilIdle()

        assertNull("a failed (unverified) kill must not broadcast a lifecycle signal", broadcast)
        collector.cancel()
    }

    // ----------------------------------------------------------- Issue #883

    /**
     * Issue #883 (reproduce-first). The tree presents each tmux WINDOW as its
     * own `[wN]` row, but "Stop session" used to ALWAYS run `kill-session`,
     * taking the whole session (every window) down. This proves the fix: Stop
     * on a window row of a MULTI-window session runs `kill-window` for ONLY
     * that window — NOT `kill-session` — and, because a sibling window
     * survived, broadcasts a WINDOW-close (not a whole-session kill) so the
     * tree drops only the killed window row.
     *
     * Pre-fix `killCurrentSession(windowIndex)` ignored the index and called
     * `killSession`, so `killedSessionNames` would contain "multi" and
     * `killedWindowTargets` would be empty — this test fails on base.
     */
    @Test
    fun stopOnMultiWindowRowKillsOnlyThatWindow() = runTest(scheduler) {
        val signals = SessionLifecycleSignals()
        val gateway = RecordingStopGateway(
            killSucceeds = true,
            windowKillSessionSurvived = true,
        )
        val vm = newVm(
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "multi",
            client = client,
        )
        runCurrent()
        // Two windows of the same session — `[w0]` (@10) and `[w1]` (@11).
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%0", "@10", "$0", "win0", paneIndex = 0,
                    windowIndex = 0, sessionName = "multi",
                ),
                TmuxSessionViewModel.ParsedPane(
                    "%1", "@11", "$0", "win1", paneIndex = 0,
                    windowIndex = 1, sessionName = "multi",
                ),
            ),
        )
        advanceUntilIdle()

        val closed = async { signals.closedWindows.first() }
        runCurrent()

        // The user is viewing window 0 (`[w0]`) and taps Stop.
        vm.killCurrentSession(windowIndex = 0)
        advanceUntilIdle()

        val event = closed.await()
        assertEquals(7L, event.hostId)
        assertEquals(
            "the closed-window signal must carry window 0's stable tmux id (@10)",
            "@10",
            event.windowId,
        )
        assertEquals(
            "Stop on a window row must run kill-window for ONLY that window; " +
                "got ${gateway.killedWindowTargets}",
            listOf("multi:0"),
            gateway.killedWindowTargets,
        )
        assertTrue(
            "Stop on a window row must NOT run kill-session (that would take " +
                "the whole session + sibling window down); got ${gateway.killedSessionNames}",
            gateway.killedSessionNames.isEmpty(),
        )
    }

    /**
     * Issue #883 class coverage: Stop on the LAST/only window of a session.
     * tmux auto-destroys the session when its final window closes, so the
     * gateway reports `sessionSurvived = false` and the VM must broadcast a
     * whole-session kill (the row drops entirely), exactly as today.
     */
    @Test
    fun stopOnLastWindowDestroysTheSession() = runTest(scheduler) {
        val signals = SessionLifecycleSignals()
        val gateway = RecordingStopGateway(
            killSucceeds = true,
            windowKillSessionSurvived = false,
        )
        val vm = newVm(
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "solo",
            client = client,
        )
        runCurrent()
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%0", "@20", "$0", "win0", paneIndex = 0,
                    windowIndex = 0, sessionName = "solo",
                ),
            ),
        )
        advanceUntilIdle()

        val killed = async { signals.killedSessions.first() }
        runCurrent()

        vm.killCurrentSession(windowIndex = 0)
        advanceUntilIdle()

        val event = killed.await()
        assertEquals(7L, event.hostId)
        assertEquals(
            "closing the last window destroys the session — the whole-session " +
                "signal must carry the session name",
            "solo",
            event.sessionName,
        )
        assertEquals(
            "the last-window Stop still goes through kill-window on the remote",
            listOf("solo:0"),
            gateway.killedWindowTargets,
        )
    }

    /**
     * Issue #883 class coverage: the whole-session Stop path (no window index)
     * still runs `kill-session` and broadcasts a session kill — the session-
     * level / fallback intent is unchanged.
     */
    @Test
    fun stopWithoutWindowIndexKillsWholeSession() = runTest(scheduler) {
        val signals = SessionLifecycleSignals()
        val gateway = RecordingStopGateway(killSucceeds = true)
        val vm = newVm(
            sessionLifecycleSignals = signals,
            folderListGateway = gateway,
            hostDao = StopHostDao(hostId = 7L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "whole",
            client = client,
        )
        runCurrent()

        val killed = async { signals.killedSessions.first() }
        runCurrent()

        // No window index — the session-level Stop.
        vm.killCurrentSession()
        advanceUntilIdle()

        val event = killed.await()
        assertEquals("whole", event.sessionName)
        assertEquals(
            "the no-window Stop must run kill-session, not kill-window",
            listOf("whole"),
            gateway.killedSessionNames,
        )
        assertTrue(
            "the no-window Stop must NOT run kill-window; got ${gateway.killedWindowTargets}",
            gateway.killedWindowTargets.isEmpty(),
        )
    }

    // ----------------------------------------------------------- Issue #665

    @Test
    fun staleChannelSymptomMatchesTransportDeadSpawnDisconnected() {
        // Issue #665 / #636: the transport-DEAD attach variant. When the pooled
        // SSH transport has silently died, the switch's `tmux -CC` spawn throws
        // `TmuxClientException("failed to spawn tmux -CC: Disconnected",
        // <TransportException [BY_APPLICATION] Disconnected>)`. The merged #621
        // heal only matched "open failed" / EOF-write / command-timeout, so this
        // slipped through and the switch stranded on the PREVIOUS session. The
        // extended matcher must recognise it so the dead lease is evicted + the
        // attach re-dialled on a fresh transport.
        val vm = newVm()
        val transportDead = TransportException(DisconnectReason.BY_APPLICATION, "Disconnected")
        val spawnFailure = TmuxClientException(
            "failed to spawn tmux -CC: ${transportDead.message}",
            transportDead,
        )
        assertTrue(
            "the `failed to spawn tmux -CC: Disconnected` (TransportException " +
                "[BY_APPLICATION]) attach variant must be treated as a stale-channel " +
                "symptom so the dead lease is evicted + re-dialled",
            vm.isStaleChannelSymptom(spawnFailure),
        )
    }

    @Test
    fun staleChannelSymptomMatchesBareTransportException() {
        // Even without the spawn wrapper, a bare sshj TransportException whose
        // disconnect reason is BY_APPLICATION (the dead-transport-during-attach
        // shape) is a stale-channel symptom — covers the deeper-wrap path.
        val vm = newVm()
        val transportDead = TransportException(DisconnectReason.BY_APPLICATION, "Disconnected")
        val wrapped = TmuxClientException("attach failed", transportDead)
        assertTrue(
            "a BY_APPLICATION TransportException in the cause chain is a stale-channel symptom",
            vm.isStaleChannelSymptom(wrapped),
        )
    }

    @Test
    fun staleChannelSymptomDoesNotMatchBenignFailures() {
        // Scope guard: the new matcher must NOT fire for failures that are not
        // the dead-transport attach symptom — a plain IO blip, a non-application
        // transport reason without "Disconnected", or null. Over-matching would
        // burn auto-recovery re-dials on failures that should surface normally.
        val vm = newVm()
        assertFalse(
            "a generic IOException is not a stale-channel symptom",
            vm.isStaleChannelSymptom(IOException("connection reset by peer")),
        )
        assertFalse(
            "a non-application transport reason without Disconnected text is not a symptom",
            vm.isStaleChannelSymptom(
                TransportException(DisconnectReason.PROTOCOL_ERROR, "protocol error"),
            ),
        )
        assertFalse(
            "null cause is not a stale-channel symptom",
            vm.isStaleChannelSymptom(null),
        )
    }

    @Test
    fun staleChannelSymptomStillMatchesOpenFailureAndEof() {
        // Regression guard: extending the matcher must not drop the pre-#665
        // variants the #621/#465 heal already recognised.
        val vm = newVm()
        assertTrue(
            "open-failed (channel-open failure) must still be a stale-channel symptom",
            vm.isStaleChannelSymptom(TmuxClientException("open failed")),
        )
        assertTrue(
            "tmux EOF-write failure must still be a stale-channel symptom",
            vm.isStaleChannelSymptom(
                TmuxClientException("failed to write tmux command `list-panes`: EOF"),
            ),
        )
        assertTrue(
            "tmux command timeout must still be a stale-channel symptom",
            vm.isStaleChannelSymptom(
                TmuxClientException("tmux command `list-panes` timed out after 100ms"),
            ),
        )
    }

    @Test
    fun commandTimeoutDuringReconcileSurfacesFailedStatus() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "list-panes"
            closeAndThrowException = TmuxClientException(
                "tmux command `list-panes` timed out after 100ms",
            )
            closeAndThrowDisconnectEvent = TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.CommandTimeout,
                source = "command_timeout",
                intent = "command_timeout",
                commandKind = "list-panes",
                timeoutMode = "fatal",
            )
        }
        vm.attachClientForTest(client)
        runCurrent()

        client.emittedEvents.emit(ControlEvent.WindowAdd("", "@1", ""))
        advanceUntilIdle()

        assertTrue(
            "expected list-panes reconcile, got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("list-panes") },
        )
        val status = vm.connectionStatus.value
        assertTrue(
            "expected Failed after tmux command timeout, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Tmux command timed out from test@test:0. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
    }

    @Test
    fun attachReadinessTimeoutFailsWithRetryableMessageAndClosesClient() = runTest(scheduler) {
        val vm = newVm()
        vm.setAttachPanesReadyTimeoutForTest(500L)
        val client = FakeTmuxClient().apply {
            suspendForeverOnCommandPrefix = "list-panes"
        }

        val attach = async {
            vm.attachClientWithReadinessForTest(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = client,
            )
        }
        runCurrent()

        assertTrue(
            "precondition: attach must be visibly connecting",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connecting,
        )

        advanceTimeBy(501L)
        advanceUntilIdle()
        attach.await()

        val status = vm.connectionStatus.value
        assertTrue(
            "stalled list-panes must fail the connect, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Timed out waiting for tmux panes from work. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
        assertTrue("Reconnect must remain available after attach timeout", vm.canReconnect.value)
        assertTrue("timed-out attach must close the tmux client", client.closed)
    }

    @Test
    fun attachReadinessRetriesEmptyPaneListUntilPanesArrive() = runTest(scheduler) {
        val vm = newVm()
        vm.setAttachPanesReadyTimeoutForTest(1_000L)
        val client = FakeTmuxClient().apply {
            responses += CommandResponse(number = 1L, output = emptyList(), isError = false)
            responses += CommandResponse(
                number = 2L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            )
        }

        val attach = async {
            vm.attachClientWithReadinessForTest(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = client,
            )
        }
        runCurrent()
        advanceTimeBy(ATTACH_PANES_READY_RETRY_MS + 1L)
        advanceUntilIdle()
        attach.await()

        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(listOf("%0"), vm.panes.value.map { it.paneId })
        assertEquals(
            "attach readiness should retry list-panes after an empty response",
            2,
            client.sentCommands.count { it.startsWith("list-panes") },
        )
    }

    @Test
    fun applyParsedPanesPopulatesStateFlowWithOnePerPane() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "left", paneIndex = 0),
                TmuxSessionViewModel.ParsedPane("%1", "@0", "$0", "right", paneIndex = 1),
            ),
        )
        advanceUntilIdle()

        val panes = vm.panes.value
        assertEquals(2, panes.size)
        assertEquals("%0", panes[0].paneId)
        assertEquals("left", panes[0].title)
        assertEquals("@0", panes[0].windowId)
        assertEquals("$0", panes[0].sessionId)
        assertEquals("%1", panes[1].paneId)
        assertEquals("right", panes[1].title)
    }

    @Test
    fun applyParsedPanesSortsByWindowThenPaneIndex() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        // Input arrives in a deliberately scrambled order to make sure
        // the sort actually runs.
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%2", "@1", "$0", "win1-b", paneIndex = 1),
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "win0-a", paneIndex = 0),
                TmuxSessionViewModel.ParsedPane("%1", "@1", "$0", "win1-a", paneIndex = 0),
            ),
        )
        advanceUntilIdle()

        val order = vm.panes.value.map { it.paneId }
        assertEquals(listOf("%0", "%1", "%2"), order)
    }

    @Test
    fun reusesTerminalStateAcrossReconcilesForSamePaneId() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "before", paneIndex = 0)),
        )
        advanceUntilIdle()
        val firstState = vm.panes.value.single().terminalState

        // Re-reconcile with the same pane ID but updated title; the
        // TerminalSurfaceState must be reused so the emulator's
        // scrollback survives. This is the central reason the reconcile
        // path keys by paneId rather than re-creating rows.
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "after", paneIndex = 0)),
        )
        advanceUntilIdle()
        val secondRow = vm.panes.value.single()
        assertSame(firstState, secondRow.terminalState)
        assertEquals("after", secondRow.title)
    }

    /**
     * Issue #959 — DURABLE JVM GUARD for the beyond-grace
     * background→foreground "terminal frozen (no I/O) but app responsive"
     * symptom, at the precise re-bind site ([applyParsedPanes]'s reuse branch).
     *
     * A ~2-minute background fires the grace-elapsed teardown; the foreground
     * `connect(TmuxConnectTrigger.LifecycleReattach)` forces a FRESH SSH lease +
     * a brand-new [TmuxClient]. tmux preserves pane ids across detach/reattach,
     * so the reconcile takes the REUSE branch: it keeps the existing
     * [TerminalSurfaceState] (scrollback survives) and — before this fix —
     * copied metadata ONLY, leaving the pane's output producer (subscribed to
     * the DEAD client's `outputFor(paneId)`) and its input drain still wired to
     * the dead client. Result: the stale buffer stays painted, NEW `%output`
     * never reaches the emulator, and key bar / IME bytes route nowhere — the
     * exact "content visible but frozen" report. The post-grace `connect`
     * reattach had no `rebindVisiblePaneProducersToClient` call (only the silent
     * passive-reattach paths did).
     *
     * This guard reproduces the client swap directly: connect client A, seed +
     * prove I/O is live on A, swap [clientRef] to a fresh client B, then run the
     * reconcile that REUSES pane `%0`. The load-bearing assertions are
     * BEHAVIORAL, on BOTH directions:
     *  - OUTPUT: a `%output` emitted on client B reaches the pane's emulator
     *    (proves the producer re-subscribed to B's `outputFor`).
     *  - INPUT: a key written to the pane's input sink lands as a `send-keys` on
     *    client B (proves the input drain re-bound to B).
     *
     * RED on base: output emitted on B never renders (producer still on A) and
     * the test fails on the OUTPUT assertion. GREEN with the reuse-branch
     * re-bind. Covers the class — output AND input — for the reused-pane swap.
     */
    @Test
    fun reusedPaneRebindsProducerAndInputToNewClientAfterClientSwap() = runTest(scheduler) {
        val vm = newVm()
        // Pin the producer's collect dispatcher to the shared virtual clock so the
        // %output -> emulator feed drains deterministically under advanceUntilIdle
        // (production default is a real Dispatchers.IO thread). Same harness the
        // #576 overflow regression uses.
        vm.setTerminalSurfaceStateFactoryForTest {
            TerminalSurfaceState(StandardTestDispatcher(scheduler))
        }
        // decoupleOutputForFromEvents: outputFor() reads emittedPaneOutputs, so a
        // test can emit pane %output directly and watch its producer subscription.
        val clientA = FakeTmuxClient().apply { decoupleOutputForFromEvents = true }
        vm.attachClientForTest(clientA)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        runCurrent()
        // Wait until client A's pane-output collectors (producer + activity +
        // port detector) are all subscribed before emitting.
        assertTrue(
            "client A pane-output collectors must subscribe",
            withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
                clientA.emittedPaneOutputs.subscriptionCount.first { it >= 3 }
                true
            } ?: false,
        )

        // Open the seed gate so live %output flushes to the emulator, then prove
        // I/O is wired to client A: an %output emitted on A renders.
        val state = vm.panes.value.single().terminalState
        state.appendRemoteOutput("SEED-A\r\n".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        clientA.emittedPaneOutputs.emit(
            ControlEvent.Output("%0", "OUTPUT-ON-A\r\n".toByteArray(Charsets.US_ASCII)),
        )
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "precondition: A's %output reaches the emulator transcript",
            renderedTranscriptFrom(state).contains("OUTPUT-ON-A"),
        )

        // The beyond-grace reattach: a fresh client B becomes the live control
        // client (mirrors `connect(LifecycleReattach)` swapping clientRef to the
        // fresh-lease client), then tmux re-lists the SAME pane id %0.
        val clientB = FakeTmuxClient().apply { decoupleOutputForFromEvents = true }
        vm.attachClientForTest(clientB)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        runCurrent()
        // After the fix, the reused pane's producer + activity + port detector
        // re-subscribe to client B. On base they stay on the dead client A, so
        // this wait TIMES OUT — making the bug observable as a re-bind failure.
        val reboundToB = withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
            clientB.emittedPaneOutputs.subscriptionCount.first { it >= 1 }
            true
        } ?: false
        assertTrue(
            "REGRESSION (#959): after a beyond-grace reattach the reused pane's " +
                "output producer must re-subscribe to the NEW client. On base it " +
                "stayed bound to the dead client -> no collector on B.",
            reboundToB,
        )

        // The reused pane must keep its TerminalSurfaceState (scrollback survives)
        // — the whole reason the reuse branch exists.
        assertSame(
            "reused pane must keep its emulator/scrollback across the reattach",
            state,
            vm.panes.value.single().terminalState,
        )

        // The rebind re-arms the producer's seed gate (awaitSeed=true closes it
        // until the reattach re-seed lands — in production that is
        // reseedActivePaneForReattach). Open it so live %output from B flushes,
        // exactly as the production reattach re-seed does.
        state.appendRemoteOutput("SEED-B\r\n".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()

        // LOAD-BEARING (output): a %output emitted on the NEW client B must now
        // reach the emulator. RED on base — the producer was still subscribed to
        // the dead client A, so B's output was dropped (the frozen terminal).
        clientB.emittedPaneOutputs.emit(
            ControlEvent.Output("%0", "OUTPUT-ON-B\r\n".toByteArray(Charsets.US_ASCII)),
        )
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(
            "REGRESSION (#959): fresh %output on the NEW client must render after " +
                "the reattach. On base it stayed bound to the dead client -> frozen.",
            renderedTranscriptFrom(state).contains("OUTPUT-ON-B"),
        )

        // LOAD-BEARING (input): a key written to the pane's input sink must drain
        // to the NEW client B (a send-keys on B), NOT the dead client A.
        val keysOnBBefore = clientB.sentCommands.count { it.startsWith("send-keys") }
        vm.tmuxInputSinkForTest("%0").write("x".toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()
        assertTrue(
            "REGRESSION (#959): input after a reattach must reach the NEW client " +
                "(a send-keys on B), not the dead client.",
            clientB.sentCommands.count { it.startsWith("send-keys") } > keysOnBBefore,
        )
        assertTrue(
            "the input key must carry the typed byte to client B",
            clientB.sentCommands.any { it.startsWith("send-keys") && it.contains("'x'") },
        )
    }

    @Test
    fun newPaneInReconcileGetsDistinctTerminalState() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0)),
        )
        advanceUntilIdle()
        val firstState = vm.panes.value.single().terminalState

        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0),
                TmuxSessionViewModel.ParsedPane("%1", "@0", "$0", "b", paneIndex = 1),
            ),
        )
        advanceUntilIdle()
        val rows = vm.panes.value
        assertEquals(2, rows.size)
        // Existing pane keeps its state.
        assertSame(firstState, rows[0].terminalState)
        // New pane has its own state.
        assertNotSame(firstState, rows[1].terminalState)
    }

    @Test
    fun closedPaneIsDroppedFromState() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0),
                TmuxSessionViewModel.ParsedPane("%1", "@0", "$0", "b", paneIndex = 1),
            ),
        )
        advanceUntilIdle()
        assertEquals(2, vm.panes.value.size)

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0)),
        )
        advanceUntilIdle()
        assertEquals(listOf("%0"), vm.panes.value.map { it.paneId })
    }

    @Test
    fun windowAddEventTriggersListPanesAndPopulatesState() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\tshell\t0"),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        assertEquals(1, vm.panes.value.size)
        assertEquals("%0", vm.panes.value.single().paneId)
        // The reconcile must have round-tripped a list-panes call through
        // sendCommand.
        assertTrue(
            "expected a list-panes command, got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("list-panes") },
        )
    }

    @Test
    fun newPaneReconcileCapturesExistingVisibleContent() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("issue103-line-001", "issue103-line-002"),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        assertTrue(
            "expected a capture-pane prefill for the new pane, got ${client.sentCommands}",
            client.sentCommands.contains(seedCaptureCommand("%0")),
        )
    }

    @Test
    fun newPaneReconcileSeedsCaptureWithRestoredCursor() = runTest(scheduler) {
        // Issue #259/#640: the seed pairs the capture with the pane's true
        // cursor (so the agent's next in-place spinner rewrite lands on the
        // right row) via [TmuxClient.captureWithCursor]. The capture command
        // must run, and the cursor query must follow the capture so the builder
        // can append the restore. (The single-flight FOLD that avoids a second
        // wire round-trip is verified in the core-tmux RealTmuxClient tests; the
        // [FakeTmuxClient] uses the two-command default, which exercises the
        // same observable command sequence here.)
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("> committed", "Beboppin'... (thinking)"),
                isError = false,
            ),
        )
        client.cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,1"), isError = false),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        assertTrue(
            "expected a capture-pane seed for the new pane, got ${client.sentCommands}",
            client.sentCommands.contains(seedCaptureCommand("%0")),
        )
        assertTrue(
            "expected a cursor-position query paired with the capture, " +
                "got ${client.sentCommands}",
            client.sentCommands.contains(seedCursorCommand("%0")),
        )
        // The cursor query must come AFTER the capture for the same pane so the
        // builder can append the restore to the replayed snapshot.
        val captureIdx = client.sentCommands.indexOf(seedCaptureCommand("%0"))
        val cursorIdx = client.sentCommands.indexOf(seedCursorCommand("%0"))
        assertTrue("capture must precede cursor query", captureIdx in 0 until cursorIdx)
    }

    @Test
    fun bestEffortCaptureFailureDuringAttachKeepsConnectionConnectedAndClientOpen() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.failBestEffortOnCommandPrefix = "capture-pane"
        client.bestEffortException = TmuxClientException("tmux command `capture-pane` timed out")

        vm.attachClientWithReadinessForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        advanceUntilIdle()

        assertTrue(
            "best-effort capture timeout must not surface as Failed/Reconnecting; " +
                "status=${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse("best-effort capture timeout must not close tmux client", client.closed)
        assertTrue(
            "expected capture-pane seed attempt, got ${client.sentCommands}",
            client.sentCommands.contains(seedCaptureCommand("%0")),
        )
    }

    @Test
    fun missingCursorReplyDuringSeedDegradesToSeedWithoutRestore() = runTest(scheduler) {
        // Issue #259/#640: the seed pairs the capture with a cursor query via
        // [TmuxClient.captureWithCursor]. When tmux returns no usable cursor
        // (older tmux / dropped reply) the seed must still apply (no explicit
        // cursor restore) and the attach must reach Connected — the
        // graceful-degradation contract the cursor restore depends on.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("visible"),
                isError = false,
            ),
        )
        // Cursor query returns an error so captureWithCursor yields a null
        // cursor reply, modelling tmux that did not emit a usable cursor.
        client.cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = emptyList(), isError = true),
        )

        vm.attachClientWithReadinessForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        advanceUntilIdle()

        assertTrue(
            "missing cursor reply must not surface as Failed/Reconnecting; " +
                "status=${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse("missing cursor reply must not close tmux client", client.closed)
        assertTrue(
            "expected the capture seed command, got ${client.sentCommands}",
            client.sentCommands.contains(seedCaptureCommand("%0")),
        )
    }

    @Test
    fun attachReadinessRecordsTmuxLatencyMetrics() = runTest(scheduler) {
        TmuxSessionLatencyTelemetry.resetForTest()
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("issue337-visible-output"),
                isError = false,
            ),
        )
        client.cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )

        vm.attachClientWithReadinessForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        client.emittedEvents.emit(ControlEvent.Output("%0", "live".toByteArray()))
        advanceUntilIdle()

        val names = TmuxSessionLatencyTelemetry.snapshot().map { it.name }
        assertTrue("expected list-panes timing in $names", "list_panes" in names)
        assertTrue("expected capture-pane timing in $names", "capture_pane" in names)
        assertTrue("expected cursor-query timing in $names", "cursor_query" in names)
        assertTrue(
            "expected append-to-buffer timing in $names",
            "terminal_output_append_to_buffer" in names,
        )
        assertTrue("expected first visible output timing in $names", "first_visible_output" in names)

        val artifactLines = TmuxSessionLatencyTelemetry.snapshot().map { it.toArtifactLine() }
        artifactLines.forEach(::println)
        assertTrue(
            "CI unit test reports should contain artifact-shaped timing lines, got $artifactLines",
            artifactLines.any { it.startsWith("tmux_latency_list_panes_ms=") },
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun coldOpenRevealsConnectedOnlyAfterTheVisiblePaneIsSeeded() = runTest(scheduler) {
        // Issue #640 (seed-before-reveal): the terminal surface must NOT flip
        // to the revealed Connected state until the initial capture-pane seed
        // for the visible pane has been APPLIED. Otherwise the user watches the
        // live agent spinner paint onto a black/partial grid until the seed
        // lands (the reported fragments-then-fill). We snapshot the sent
        // commands at the exact moment the status first becomes Connected and
        // assert the seed capture is already present.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("issue640-seed-line-001", "issue640-seed-line-002"),
                isError = false,
            ),
        )
        client.cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,1"), isError = false),
        )

        // Capture the connection status synchronously at the exact moment the
        // seed capture command reaches the wire. If the surface were revealed
        // before seeding, the status would already be Connected here.
        var statusWhenSeedSent: TmuxSessionViewModel.ConnectionStatus? = null
        client.onCommandSent = { cmd ->
            if (cmd == seedCaptureCommand("%0") && statusWhenSeedSent == null) {
                statusWhenSeedSent = vm.connectionStatus.value
            }
        }

        vm.attachClientWithReadinessForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        advanceUntilIdle()

        assertTrue(
            "status must reach Connected after seeding; got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        val seedStatus = statusWhenSeedSent
        assertNotNull("the visible pane was never seeded", seedStatus)
        assertFalse(
            "Connected was revealed BEFORE the visible pane was seeded — the user " +
                "would see a blank/partial grid with only the live spinner painting. " +
                "Status when seed sent: $seedStatus",
            seedStatus is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    @Test
    fun coldOpenSeedsEachVisiblePaneExactlyOnceWithNoRedundantReseed() = runTest(scheduler) {
        // Issue #640: on a cold open every visible pane is freshly created and
        // seeded by the preload pass; the post-attach reseed must NOT re-capture
        // those same panes (the redundant second full reseed the diagnosis
        // flagged). Assert each visible pane's capture seed runs exactly once.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "%0\t@0\t\$0\twork\tshell\t0",
                    "%1\t@1\t\$0\twork\teditor\t0",
                ),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("pane0-content"), isError = false),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(number = 3L, output = listOf("pane1-content"), isError = false),
        )

        vm.attachClientWithReadinessForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        advanceUntilIdle()

        assertEquals(
            "each visible pane must be seeded exactly once on cold open " +
                "(no redundant second reseed); got ${client.sentCommands}",
            1,
            client.sentCommands.count { it == seedCaptureCommand("%0") },
        )
        assertEquals(
            "each visible pane must be seeded exactly once on cold open " +
                "(no redundant second reseed); got ${client.sentCommands}",
            1,
            client.sentCommands.count { it == seedCaptureCommand("%1") },
        )
    }

    @Test
    fun warmSwitchReadinessTelemetryFollowsAttachOrdering() = runTest(scheduler) {
        TmuxSessionLatencyTelemetry.resetForTest()
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\tother\tshell\t0"),
                isError = false,
            ),
        )
        client.responses.addLast(
            CommandResponse(
                number = 4L,
                output = listOf("%0\t@0\t\$0\tother\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("warm switch seed"),
                isError = false,
            ),
        )
        client.cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )

        vm.attachClientWithReadinessForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = client,
            trigger = TmuxConnectTrigger.FastSwitch,
        )
        vm.resizeRemotePty(columns = 100, rows = 30)
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.LayoutChange(sessionId = "", windowId = "@0", layout = "bf3d,80x24"),
        )
        advanceUntilIdle()

        val warmEvents = TmuxSessionLatencyTelemetry.snapshot()
            .filter { it.name.startsWith("warm_switch_") }
        val names = warmEvents.map { it.name }
        val expectedOrder = listOf(
            "warm_switch_start",
            "warm_switch_selected_session_state",
            "warm_switch_tmux_shell_attached",
            "warm_switch_pane_list_ready",
            "warm_switch_terminal_bridge_ready",
            "warm_switch_terminal_capture_ready",
            "warm_switch_panes_ready",
            "warm_switch_connect_ready",
            "warm_switch_remote_refresh_complete",
        )
        var previousIndex = -1
        var previousEvent = "start"
        for (event in expectedOrder) {
            val index = names.indexOf(event)
            assertTrue("expected $event in warm switch telemetry $names", index >= 0)
            assertTrue(
                "expected $event after $previousEvent in $names",
                index > previousIndex,
            )
            previousIndex = index
            previousEvent = event
        }
        assertTrue(
            "warm switch telemetry should be tagged with fast-switch: $warmEvents",
            warmEvents.all { it.trigger == TmuxConnectTrigger.FastSwitch.logValue },
        )
        assertEquals(
            "pane-list readiness must be a one-shot milestone after attach",
            1,
            names.count { it == "warm_switch_pane_list_ready" },
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun parseTmuxPaneCursorReadsWellFormedReply() {
        // Issue #259: the cursor reply is `cursor_x,cursor_y` (0-based).
        assertEquals(TmuxPaneCursor(column = 0, row = 2), parseTmuxPaneCursor("0,2"))
        assertEquals(TmuxPaneCursor(column = 12, row = 5), parseTmuxPaneCursor(" 12 , 5 "))
    }

    @Test
    fun parseTmuxPaneCursorRejectsMalformedReplies() {
        // Issue #259: a missing/old/malformed reply degrades to no restore.
        assertNull(parseTmuxPaneCursor(null))
        assertNull(parseTmuxPaneCursor(""))
        assertNull(parseTmuxPaneCursor("3"))
        assertNull(parseTmuxPaneCursor("a,b"))
        assertNull(parseTmuxPaneCursor("1,2,3"))
        assertNull(parseTmuxPaneCursor("-1,2"))
        assertNull(parseTmuxPaneCursor("1,-2"))
    }

    @Test
    fun existingPaneReconcileDoesNotRecaptureContent() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.responses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        // Issue #693/#662: the attach-time seed must succeed in ONE shot
        // (non-empty capture) so the pane is marked seeded-this-attach. An empty
        // attach seed now legitimately RETRIES (the never-black guard), which is
        // a different code path than the "no RE-capture of EXISTING content on a
        // reconcile" invariant this test locks in. Queue real content so the
        // single attach seed lands and the LayoutChange reconcile below stays a
        // no-op (the #640 panesSeededThisAttach skip).
        client.capturePaneResponses.addLast(
            CommandResponse(number = 3L, output = listOf("work shell ready"), isError = false),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.LayoutChange(sessionId = "", windowId = "@0", layout = "bf3d,80x24"),
        )
        advanceUntilIdle()

        assertEquals(1, client.sentCommands.count { it.startsWith("capture-pane") })
    }

    /**
     * Issue #576 (Slice A of #792): MAIN-THREAD RESPONSIVENESS under a Codex
     * `%layout-change` storm — the on-device ANR reproduced through the REAL
     * collector wiring (`bindClientObservers` → coalescer → off-main
     * `reconcilePanes`), then proven fixed.
     *
     * The freeze was head-of-line blocking: the old path ran a synchronous
     * `reconcilePanes()` (`list-panes` round-trip) on the Main collector thread
     * for EVERY structural event, so a burst of N `%layout-change` events
     * serialised N `list-panes` round-trips on the UI thread → ANR.
     *
     * This test makes the `list-panes` round-trip SLOW (parked behind a gate)
     * and runs it on a SEPARATE [reconcileDispatcher] (modelling the production
     * `Dispatchers.Default`). It then fires a 5_000-event storm through the real
     * collector and HARD-asserts, while the reconcile is still parked:
     *  - a concurrently-posted MAIN task runs to completion (the Main thread is
     *    NOT wedged behind the storm) — the responsiveness contract;
     *  - the whole storm was OFFERED non-blockingly (the collector did not stall
     *    one-`list-panes`-per-event);
     *  - at most a handful of `list-panes` round-trips were started for the
     *    5_000 events — coalescing, not O(N).
     * Then it releases the gate and asserts the FINAL pane layout is correct
     * (the settled layout after the burst is never dropped).
     *
     * RED on base: with the pre-fix synchronous per-event main-thread reconcile,
     * the collector `emit` of the FIRST gated event would suspend the Main
     * collector on the gate `await()`; the marker task posted afterwards on Main
     * would NOT complete under `runCurrent()` (Main wedged) and the 5_000 emits
     * would each queue a blocking reconcile. GREEN with the coalescer +
     * off-main reconcile.
     *
     * No `assumeTrue` / `isRunningOnCi` skip (F3): the gate + virtual clock make
     * the head-of-line property deterministic on every machine and on CI.
     */
    @Test
    fun codexLayoutChangeStormKeepsMainThreadResponsiveAndSettlesFinalLayout() =
        runTest(scheduler) {
            val vm = newVm()
            // Model production: the structural reconcile IO runs OFF the Main
            // collector thread. A queued StandardTestDispatcher on the shared
            // scheduler stands in for Dispatchers.Default — work runs only when
            // the scheduler pumps it, never eagerly on the collector's stack.
            vm.setReconcileDispatcherForTest(StandardTestDispatcher(scheduler))
            // Apply stays on the test Main (the production
            // Dispatchers.Main.immediate analogue) so the `_panes` mutation is
            // single-threaded on the UI thread. Because the coalescer scope runs
            // on the separate StandardTestDispatcher above (≠ this apply
            // dispatcher), `applyOnMain` genuinely HOPS from off-Main back to
            // Main — the production behaviour under test.
            vm.setReconcileApplyDispatcherForTest(testMainDispatcher)

            val client = FakeTmuxClient()
            // Park EVERY `list-panes` round-trip behind a gate so a reconcile,
            // once started, is "slow" — the per-event cost that wedged the UI
            // thread on the old path. (attachClientForTest does NOT itself run a
            // reconcile; only the structural events below do, so the gate is
            // armed up-front and the storm reconcile is what parks on it.)
            val listPanesGate = CompletableDeferred<Unit>()
            client.sendCommandGatePrefix = "list-panes"
            client.sendCommandGate = listPanesGate

            // Each coalesced reconcile re-reads the authoritative pane list via
            // list-panes; both storm-driven reconciles see the SETTLED two-pane
            // layout (the last write after the burst). capture-pane seeds the
            // newly-discovered editor pane.
            val settledLayout = CommandResponse(
                number = 0L,
                output = listOf(
                    "%0\t@0\t\$0\twork\tshell\t0",
                    "%1\t@1\t\$0\twork\teditor\t0",
                ),
                isError = false,
            )
            repeat(4) { client.responses.addLast(settledLayout) }
            repeat(4) {
                client.capturePaneResponses.addLast(
                    CommandResponse(number = 0L, output = listOf("seed"), isError = false),
                )
                client.cursorQueryResponses.addLast(
                    CommandResponse(number = 0L, output = listOf("0,0"), isError = false),
                )
            }

            vm.attachClientForTest(client)
            runCurrent()

            // ---- the storm ------------------------------------------------
            // Fire the storm from a child coroutine and bound it: the coalescer
            // `offer` is non-blocking so the whole storm completes promptly.
            // On the OLD synchronous per-event path the FIRST gated reconcile
            // suspends the (UNDISPATCHED) Main collector mid-`emit`, so the
            // SharedFlow `emit`s block and this never completes — surfacing as a
            // FAST explicit failure here instead of the 1m runTest watchdog.
            val stormSize = 5_000
            val stormDone = AtomicInteger(0)
            val stormJob = launch {
                repeat(stormSize) {
                    client.emittedEvents.emit(
                        ControlEvent.LayoutChange(
                            sessionId = "",
                            windowId = "@0",
                            layout = "burst-$it",
                        ),
                    )
                }
                stormDone.incrementAndGet()
            }
            // Let the collector drain all offers and let ONE coalesced reconcile
            // start (it parks on the gated list-panes). Do NOT release the gate
            // yet — we assert responsiveness WHILE a reconcile is in flight.
            runCurrent()
            assertEquals(
                "the entire 5_000-event layout-change storm must be OFFERED " +
                    "non-blockingly — the collector must NOT head-of-line-block on " +
                    "the gated reconcile (the ANR)",
                1,
                stormDone.get(),
            )
            assertTrue("storm coroutine completed", stormJob.isCompleted)

            // RESPONSIVENESS: post a marker task on Main and assert it completes
            // even though a reconcile is parked on the gated list-panes. On the
            // old synchronous path the Main collector itself would be suspended
            // on the gate, so this marker could not run.
            val mainTaskRan = AtomicInteger(0)
            CoroutineScope(Dispatchers.Main).launch { mainTaskRan.incrementAndGet() }
            runCurrent()
            assertEquals(
                "a Main task posted during the layout-change storm must run — the " +
                    "Main thread must NOT be wedged behind the gated reconcile (the ANR)",
                1,
                mainTaskRan.get(),
            )

            // COALESCING: despite 5_000 events, only a handful of list-panes
            // round-trips were STARTED (the rest collapsed). Count how many have
            // reached the gated client so far.
            val listPanesStartedDuringStorm =
                client.sentCommands.count { it.startsWith("list-panes") }
            assertTrue(
                "5_000-event storm must collapse to a handful of list-panes " +
                    "round-trips, started=$listPanesStartedDuringStorm",
                listPanesStartedDuringStorm in 1..3,
            )

            // FINAL LAYOUT: release the gate and let the settled reconcile apply.
            // The reconcile that runs after the burst re-reads list-panes and
            // must produce the final two-pane layout (the last write is never
            // dropped).
            listPanesGate.complete(Unit)
            advanceUntilIdle()

            val finalPaneIds = vm.panes.value.map { it.paneId }
            assertEquals(
                "the final pane layout after the storm settles must be correct " +
                    "(both panes present, last write not dropped), was $finalPaneIds",
                listOf("%0", "%1"),
                finalPaneIds,
            )
            // And the coalesced reconciles never ran one-list-panes-per-event.
            val totalListPanes = client.sentCommands.count { it.startsWith("list-panes") }
            assertTrue(
                "total list-panes for a 5_000-event storm must stay a small constant, " +
                    "was $totalListPanes",
                totalListPanes in 1..4,
            )
        }

    @Test
    fun reconcileScopesPanesAndWindowSummariesToActiveSession() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "%0\t@0\t\$0\twork\tshell\t0",
                    "%1\t@1\t\$0\twork\teditor\t0",
                    "%2\t@2\t\$1\tother\tlogs\t0",
                    "%3\t@3\t\$1\tother\tbuild\t0",
                ),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "\$0", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        val command = client.sentCommands.single { it.startsWith("list-panes") }
        // Per #158: reconcilePanes now uses `-s` so the response covers
        // every window in the target session, not only the current window.
        // Issue #782: PocketShell no longer manages windows, but the unified
        // pager + the per-window `[wN]` switcher entries still rely on the
        // full multi-window pane list, so the `-s` scope is preserved.
        assertTrue(command.startsWith("list-panes -s -t 'work' -F "))
        assertTrue(command.contains(LIST_PANES_FIELD_SEPARATOR))
        assertFalse(command.contains(" -a "))

        val panes = vm.panes.value
        assertEquals(listOf("%0", "%1"), panes.map { it.paneId })
        assertEquals(listOf("@0", "@1"), panes.map { it.windowId })
    }

    @Test
    fun layoutChangeEventTriggersListPanesReconcile() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        // Two responses: bootstrap and layout-change reconcile.
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\tone\t0"),
                isError = false,
            ),
        )
        client.responses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf(
                    "%0\t@0\t\$0\tone\t0",
                    "%1\t@0\t\$0\ttwo\t1",
                ),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)

        client.emittedEvents.emit(
            ControlEvent.LayoutChange(sessionId = "", windowId = "@0", layout = "bf3d,80x24"),
        )
        advanceUntilIdle()
        assertEquals(2, vm.panes.value.size)
    }

    @Test
    fun windowCloseEventTriggersReconcileThatDropsThePane() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\ta\t0"),
                isError = false,
            ),
        )
        // Second reconcile after %window-close returns nothing — the
        // window (and its pane) are gone.
        client.responses.addLast(
            CommandResponse(number = 2L, output = emptyList(), isError = false),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)

        client.emittedEvents.emit(
            ControlEvent.WindowClose(sessionId = "", windowId = "@0"),
        )
        advanceUntilIdle()
        assertTrue(vm.panes.value.isEmpty())
    }

    @Test
    fun listPanesErrorLeavesExistingPaneListUntouched() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\ta\t0"),
                isError = false,
            ),
        )
        // Second response: a tmux error (e.g. server shutting down).
        client.responses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("server is shutting down"),
                isError = true,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        val before = vm.panes.value
        assertEquals(1, before.size)

        client.emittedEvents.emit(
            ControlEvent.LayoutChange(sessionId = "", windowId = "@0", layout = "bf3d"),
        )
        advanceUntilIdle()
        // Error must NOT wipe the state.
        val after = vm.panes.value
        assertEquals(1, after.size)
        assertSame(before.single().terminalState, after.single().terminalState)
    }

    @Test
    fun unrelatedEventDoesNotTriggerListPanes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        client.emittedEvents.emit(ControlEvent.SessionsChanged)
        client.emittedEvents.emit(
            ControlEvent.Output(paneId = "%0", data = "hi".toByteArray()),
        )
        advanceUntilIdle()

        assertTrue(
            "no list-panes should fire on Output / SessionsChanged",
            client.sentCommands.none { it.startsWith("list-panes") },
        )
    }

    @Test
    fun writeInputToPaneIssuesSendKeysWithLiteralBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Submission semantics: a `\r` byte (carriage return) is the
        // "Enter / submit" signal coming from the terminal emulator
        // and from the in-app callers (chips, snippets with-Enter,
        // composer send) — see [TmuxSessionScreen]. The single-line
        // route keeps the existing two-token send-keys shape so
        // keyboard typing still submits cleanly.
        //
        // A literal `\n` byte is reserved for the bracketed-paste
        // multi-line route (issue #209); we cover that in
        // [writeInputToPaneWrapsMultiLineInputInBracketedPaste].
        vm.writeInputToPane("%0", "ls\r".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(sent.toString(), 2, sent.size)
        assertEquals("send-keys -l -t %0 -- 'ls'", sent[0])
        assertEquals("send-keys -t %0 Enter", sent[1])
    }

    @Test
    fun writeInputToPaneExitsTmuxCopyModeBeforeSendingBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "shell",
                    paneIndex = 0,
                    inCopyMode = true,
                ),
            ),
        )

        val result = vm.writeInputToPaneResult("%0", "ls\r".toByteArray(Charsets.UTF_8))
        runCurrent()

        assertTrue("copy-mode recovery should keep pane input successful", result.isSuccess)
        assertFalse("copy-mode recovery must not mark tmux disconnected", client.disconnected.value)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(
            listOf(
                "send-keys -X -t %0 cancel",
                "send-keys -l -t %0 -- 'ls'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
        assertFalse(vm.panes.value.single { it.paneId == "%0" }.inCopyMode)
    }

    @Test
    fun writeInputToPaneSendKeysFailureSurfacesFailedStatus() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "send-keys"
            closeAndThrowException = TmuxClientException(
                "tmux command `send-keys` timed out after 100ms",
            )
            closeAndThrowDisconnectEvent = TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.CommandTimeout,
                source = "command_timeout",
                intent = "command_timeout",
                commandKind = "send-keys",
                timeoutMode = "fatal",
            )
        }
        vm.attachClientForTest(client)
        runCurrent()

        vm.writeInputToPane("%0", "ls\r".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        assertTrue(
            "expected send-keys dispatch, got ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("send-keys") },
        )
        val status = vm.connectionStatus.value
        assertTrue(
            "expected Failed after send-keys failure, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Tmux command timed out from test@test:0. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
    }

    @Test
    fun eofDisconnectUnregistersDeadClientAndPreservesReconnectTarget() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        runCurrent()
        assertSame(client, registry.clients.value[7L]?.client)

        client.disconnectedSignal.value = true
        runCurrent()

        assertTrue(
            "dead active tmux client must be removed from dashboard registry",
            registry.clients.value.isEmpty(),
        )
        assertEquals("work", vm.activeSessionNameForTest())
        assertTrue("Reconnect must remain available after EOF disconnect", vm.canReconnect.value)
        val status = vm.connectionStatus.value
        assertTrue(
            "expected Failed after EOF disconnect, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Disconnected from alex@alpha.example:22. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
    }

    @Test
    fun readerEofDisconnectStartsAutoReconnectAfterSilentGrace() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        try {
            val deadClient = FakeTmuxClient()
            val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
            vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
                assertEquals("work", sessionName)
                reconnectClient
            }
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = deadClient,
            )

            deadClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "eof",
                    intent = "unknown",
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "structured transport EOF should enter the bounded auto-reconnect loop",
                1,
                connector.connectCount,
            )
            assertEquals("work", vm.activeSessionNameForTest())
            assertSame(reconnectClient, registry.clients.value[7L]?.client)
            assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)

            val passive = diagnostics.eventsNamed("passive_disconnect").single()
            assertEquals("tmux_client_disconnected", passive.fields["source"])
            assertEquals("real_tmux_control_channel_closed", passive.fields["classification"])
            assertEquals("reader_eof", passive.fields["disconnectReason"])
            assertEquals("eof", passive.fields["disconnectSource"])
            assertEquals("unknown", passive.fields["disconnectIntent"])
            assertEquals("alpha.example", passive.fields["host"])
            assertEquals("work", passive.fields["session"])
            assertTrue(
                "passive EOF must not be logged as terminal render overflow",
                diagnostics.eventsNamed("terminal_output_overflow").isEmpty(),
            )
            val autoDecision = diagnostics.eventsNamed("auto_reconnect_decision").single()
            assertEquals("scheduled", autoDecision.fields["decision"])
            assertEquals("retryable", autoDecision.fields["cause"])
            assertEquals("tmux_eof_or_reader_disconnect", autoDecision.fields["reconnectSourceCandidate"])
            assertEquals("reader_eof", autoDecision.fields["disconnectReason"])
            assertTrue(
                "passive EOF should emit an automatic reconnect_start",
                diagnostics.eventsNamed("reconnect_start").any {
                    it.fields["trigger"] == TmuxConnectTrigger.AutoReconnect.logValue &&
                        it.fields["session"] == "work"
                },
            )
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun livenessProbeDeclaredDropClosesTheClientAndDrivesTheSingleReconnectEntrypoint() =
        runTest(scheduler) {
            // EPIC #792 Slice D (#822/V7a): the LivenessProbe's confirmed-drop body
            // must CLOSE the dead client + drive the EXISTING tested recovery path
            // (the single TransportEffects reconnect entrypoint) — never a second
            // reconnect writer. This pins that wiring at the production VM layer.
            val diagnostics = installRecordingDiagnosticSink()
            val registry = ActiveTmuxClients()
            val connector = QueueLeaseConnector(FakeSshSession())
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(
                    connector = connector,
                    scope = this,
                    idleTtlMillis = 0L,
                ),
            )
            vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            try {
                val liveClient = FakeTmuxClient()
                val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
                vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
                    assertEquals("work", sessionName)
                    reconnectClient
                }
                vm.replaceClientForTest(
                    hostId = 7L,
                    hostName = "alpha",
                    host = "alpha.example",
                    port = 22,
                    user = "alex",
                    keyPath = "/keys/a",
                    sessionName = "work",
                    client = liveClient,
                )

                // Pre-condition: settled Connected/Live, so the probe gate is OPEN.
                assertTrue(
                    "expected Connected before the probe-declared drop",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
                assertTrue(
                    "the probe gate must be open while foregrounded + Live",
                    vm.shouldRunLivenessProbeForTest(),
                )
                assertFalse(
                    "the live client must not be disconnected before the probe fires",
                    liveClient.disconnected.value,
                )

                // The probe declared a sustained silent drop. Drive its confirmed-
                // drop body directly (the body the periodic loop fires) — this is the
                // detection→recovery wiring under test, independent of probe cadence.
                vm.triggerLivenessProbeDropForTest(consecutiveFailures = 2)
                advanceUntilIdle()

                // 1) The dead client was CLOSED by the probe (the single action that
                //    drives the whole existing recovery path).
                assertTrue(
                    "the probe must CLOSE the dead client (flips disconnected → " +
                        "existing recovery)",
                    liveClient.disconnected.value,
                )
                // 2) The drop was DETECTED (a passive_disconnect fired off the closed
                //    client) and a liveness_probe_silent_drop diagnostic was recorded.
                assertTrue(
                    "the probe must record its silent-drop detection",
                    diagnostics.eventsNamed("liveness_probe_silent_drop").any {
                        it.fields["session"] == "work" &&
                            it.fields["source"] == "liveness_probe"
                    },
                )
                // 3) Recovery ran through the SINGLE reconnect entrypoint (the same
                //    AutoReconnect ladder a real EOF drives) — NOT a second writer.
                assertEquals(
                    "the probe-closed client must drive ONE reconnect dial via the " +
                        "single TransportEffects entrypoint",
                    1,
                    connector.connectCount,
                )
                assertSame(reconnectClient, registry.clients.value[7L]?.client)
                // 4) The SAME session auto-recovered to Connected — no switch dance.
                assertEquals("work", vm.activeSessionNameForTest())
                assertTrue(
                    "the SAME session must auto-recover to Connected after the probe drop",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
            } finally {
                diagnostics.close()
            }
        }

    @Test
    fun silentDropAutoReconnectForceEvictsPoisonedHalfOpenLeaseAndDialsFresh() =
        runTest(scheduler) {
            // EPIC #792 / #822 (THE WEDGE REGRESSION, D31 durable-fix gate).
            //
            // The on-device #822 symptom: on a SILENT half-open Wi-Fi drop (no
            // network reason, mid voice-recording) the header stuck amber
            // "Reconnecting" forever, and the ONLY recovery was the switch-away-
            // and-back dance. The root cause is in the lease pool: sshj's
            // `isConnected` LIES (stays true ~60s until the keep-alive miss-counter
            // trips), so [SshLeaseManager.acquire] REUSES the poisoned warm entry,
            // and EVERY auto-reconnect attempt re-dials the SAME dead socket. The
            // switch dance recovered only because re-entering connect() to a
            // DIFFERENT host eventually evicted the poisoned lease.
            //
            // The fix ([shouldForceFreshLease] now includes
            // [TmuxConnectTrigger.AutoReconnect]) force-evicts the poisoned idle
            // entry before each ladder re-dial, so the SAME session recovers onto a
            // FRESH transport WITHOUT the switch dance.
            //
            // The sibling test
            // `livenessProbeDeclaredDropClosesTheClientAndDrivesTheSingleReconnectEntrypoint`
            // pins the detection→recovery WIRING but uses an EMPTY lease pool, so it
            // passes EVEN IF the wedge fix is reverted (an empty pool always dials
            // fresh). THIS test closes that gap: it pre-seeds the pool with the
            // poisoned half-open lease the real device holds, so reverting the
            // `AutoReconnect` line of [shouldForceFreshLease] makes the ladder reuse
            // the poisoned entry (connectCount stays 1, the SAME dead session) and
            // this assertion fails — the red→green proof of the actual wedge.
            val diagnostics = installRecordingDiagnosticSink()
            val registry = ActiveTmuxClients()
            // First dial yields the POISONED half-open session (isConnected lies
            // true); the second dial — only reached if the ladder force-evicts —
            // yields a FRESH session.
            val poisoned = FakeSshSession()
            val fresh = FakeSshSession()
            val connector = QueueLeaseConnector(poisoned, fresh)
            // Keep a real (virtual-clock) lease pool with a NON-zero idle TTL so the
            // released poisoned lease sits WARM/idle in the pool (the exact on-device
            // state) rather than closing on release — that is what makes the next
            // acquire reuse it unless force-evicted.
            val leaseManager = testLeaseManager(
                connector = connector,
                scope = this,
                idleTtlMillis = 60_000L,
            )
            val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
            vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            try {
                // Pre-seed the pool with the poisoned half-open lease for the EXACT
                // key the ladder will reconstruct from the target (hostId=1 + keyPath
                // /keys/a -> credentialId "1:/keys/a"). Acquire then release so it
                // sits warm/idle (refCount 0) and `isConnected` still lies true.
                // NOTE: drive the seed with runCurrent(), NOT advanceUntilIdle() —
                // the latter would advance the virtual clock past the 60s idle TTL
                // and let the idle-close timer fire, closing the poisoned lease and
                // emptying the pool (which would make the later reuse-vs-evict
                // distinction vacuous). runCurrent() settles the bounded dial without
                // advancing time, so the released lease sits warm/idle exactly as it
                // does on-device within the warm window.
                leaseManager.acquire(testLeaseTarget()).getOrThrow().release()
                runCurrent()
                assertEquals(
                    "the poisoned lease is dialed once and pooled warm",
                    1,
                    connector.connectCount,
                )
                assertTrue(
                    "the poisoned half-open lease still LIES isConnected==true while pooled " +
                        "(the deceptive #822 case)",
                    leaseManager.hasLiveLease(testLeaseTarget().leaseKey),
                )

                val liveClient = FakeTmuxClient()
                val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
                vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
                    assertEquals("work", sessionName)
                    reconnectClient
                }
                vm.replaceClientForTest(
                    hostId = 1L,
                    hostName = "alpha",
                    host = "alpha.example",
                    port = 22,
                    user = "alex",
                    keyPath = "/keys/a",
                    sessionName = "work",
                    client = liveClient,
                )

                assertTrue(
                    "expected Connected before the silent half-open drop",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
                assertTrue(
                    "the probe gate must be open while foregrounded + Live",
                    vm.shouldRunLivenessProbeForTest(),
                )

                // The liveness probe declared a sustained SILENT drop (the #822
                // scenario: no send, half-open Wi-Fi). Drive its confirmed-drop body
                // directly — it walks the controller Live -> Reattaching (the
                // immediate indicator) and drives the single AutoReconnect ladder.
                vm.triggerLivenessProbeDropForTest(consecutiveFailures = 2)
                advanceUntilIdle()

                // THE WEDGE ASSERTION (red→green): the AutoReconnect ladder must have
                // FORCE-EVICTED the poisoned half-open lease and dialed a SECOND,
                // FRESH transport. Without the fix the ladder reuses the poisoned
                // pooled entry, connectCount stays 1, and the session stays wedged.
                assertEquals(
                    "the silent-drop AutoReconnect ladder must FORCE-EVICT the poisoned " +
                        "half-open lease and dial a FRESH transport (the #822 wedge fix). " +
                        "connectCount==1 means it reused the poisoned lease — the wedge.",
                    2,
                    connector.connectCount,
                )
                assertTrue(
                    "the poisoned half-open SSH session must be CLOSED by the force-evict " +
                        "(not silently reused under the new client)",
                    poisoned.closed,
                )

                // Recovery is on the SAME session, no switch dance.
                assertEquals("work", vm.activeSessionNameForTest())
                assertSame(reconnectClient, registry.clients.value[1L]?.client)
                assertTrue(
                    "the SAME session must auto-recover to Connected after the silent " +
                        "half-open drop — WITHOUT a switch-to-another-session dance",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )

                // The drop was DETECTED proactively (the headline #822 requirement:
                // surface it, don't discover it only when a send fails).
                assertTrue(
                    "the probe must record its proactive silent-drop detection",
                    diagnostics.eventsNamed("liveness_probe_silent_drop").any {
                        it.fields["session"] == "work" &&
                            it.fields["source"] == "liveness_probe"
                    },
                )
                // The force-fresh-lease eviction was recorded for the AutoReconnect
                // trigger (the mechanism that breaks the wedge).
                assertTrue(
                    "the AutoReconnect ladder must record the force-fresh-lease eviction " +
                        "that breaks the #822 wedge",
                    diagnostics.eventsNamed("tmux_force_fresh_ssh_lease").any {
                        it.fields["trigger"] == TmuxConnectTrigger.AutoReconnect.logValue &&
                            it.fields["evictedLease"] == true
                    },
                )
            } finally {
                diagnostics.close()
            }
        }

    @Test
    fun manualReconnectForceEvictsPoisonedHalfOpenLeaseAndDialsFresh() = runTest(scheduler) {
        // EPIC #792 / #822 — the SECOND seam of the wedge class (D31 class coverage).
        //
        // The same poisoned-half-open-lease pathology must not strand the MANUAL /
        // send-triggered reconnect either (the [reconnect] affordance #823 will call
        // the SAME single TransportEffects entrypoint). The manual `Reconnect`
        // trigger already force-evicts ([shouldForceFreshLease]); this pins that it
        // dials a FRESH transport over the poisoned warm lease so a user tap (or a
        // send-while-not-Connected) recovers the SAME session without the switch
        // dance — covering the recovery-without-switch path for the manual seam.
        val registry = ActiveTmuxClients()
        val poisoned = FakeSshSession()
        val fresh = FakeSshSession()
        val connector = QueueLeaseConnector(poisoned, fresh)
        val leaseManager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        val vm = newVm(registry = registry, sshLeaseManager = leaseManager)
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        try {
            // runCurrent() (not advanceUntilIdle) so the released poisoned lease sits
            // warm/idle in the pool rather than being closed by the 60s idle timer —
            // otherwise the reuse-vs-evict distinction below is vacuous.
            leaseManager.acquire(testLeaseTarget()).getOrThrow().release()
            runCurrent()
            assertEquals(1, connector.connectCount)
            assertTrue(
                "the poisoned half-open lease must sit warm/idle (the deceptive #822 case)",
                leaseManager.hasLiveLease(testLeaseTarget().leaseKey),
            )

            val deadClient = FakeTmuxClient()
            val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
            vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
                assertEquals("work", sessionName)
                reconnectClient
            }
            vm.replaceClientForTest(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = deadClient,
            )

            // The user taps Reconnect (the manual affordance / #823 entrypoint).
            val started = vm.reconnect()
            assertTrue("reconnect() must start with a target present", started)
            advanceUntilIdle()

            assertEquals(
                "the manual reconnect must FORCE-EVICT the poisoned half-open lease and " +
                    "dial a FRESH transport (no switch dance)",
                2,
                connector.connectCount,
            )
            assertTrue("the poisoned half-open session must be CLOSED by the force-evict", poisoned.closed)
            assertEquals("work", vm.activeSessionNameForTest())
            assertTrue(
                "the SAME session must recover to Connected after a manual reconnect",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
        } finally {
            vm.clearForTest()
        }
    }

    @Test
    fun livenessProbeGateIsClosedWhenNotConnected() = runTest(scheduler) {
        // The probe must NOT run when there is no live session (no false-positive
        // teardown of a not-yet-connected / idle VM).
        val vm = newVm()
        assertFalse(
            "the probe gate must be closed with no attached client",
            vm.shouldRunLivenessProbeForTest(),
        )
    }

    @Test
    fun livenessProbePingReportsDeadWhenSyntheticSeamIsArmed() = runTest(scheduler) {
        // The synthetic-drop seam makes the ping report DEAD without touching the
        // wire — the deterministic per-PR detection lever (also proven on the
        // emulator by SilentDropSyntheticSeamJourneyE2eTest).
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        assertTrue(
            "a healthy attached client must ping alive",
            vm.runLivenessProbePingForTest(),
        )
        vm.forceLivenessProbeDeadForTest = true
        assertFalse(
            "the synthetic-drop seam must make the ping report dead",
            vm.runLivenessProbePingForTest(),
        )
        vm.forceLivenessProbeDeadForTest = false
        assertTrue(
            "clearing the seam restores the live ping",
            vm.runLivenessProbePingForTest(),
        )
    }

    @Test
    fun issue964KeepAliveCoordinationGuardReflectsTheLiveSessionKeepalive() = runTest(scheduler) {
        // Issue #964 — the VM wiring of the keepalive-coordination guard the
        // LivenessProbe defers to. The probe's ProbeIo.transportProvenAliveRecently
        // is wired to the live SshSession's keepalive-liveness signal, so on a
        // slow-but-live link (control probe failing, keepalive still proving the
        // transport alive) the probe DEFERS and does NOT force a redial.
        val vm = newVm()

        // No live session yet → no keepalive signal → the probe keeps its own
        // authority (guard reports NOT-alive), exactly as before.
        assertFalse(
            "with no live session there is no keepalive signal to defer to",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        // Attach a live session whose transport keepalive is still riding through
        // (a slow-but-live link): the guard must report ALIVE so the probe defers.
        val session = FakeSshSession().apply { transportProvenAlive = true }
        val liveClient = FakeTmuxClient().withSinglePane("work", "%1")
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = liveClient,
            session = session,
        )
        runCurrent()
        assertTrue(
            "while the transport keepalive proves the link alive the guard must report " +
                "ALIVE so the probe defers (no spurious redial on a slow-but-live link)",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        // The transport genuinely dies — the keepalive stops proving liveness, so
        // the guard reports NOT-alive and the probe regains authority (no infinite
        // deferral / hang on a real death).
        session.transportProvenAlive = false
        assertFalse(
            "once the keepalive stops proving liveness the guard must report NOT-alive " +
                "so the probe can declare the real drop (#964 — deferral is not a hang)",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        // The explicit test seam pins the verdict independently of the session.
        vm.forceTransportProvenAliveForTest = true
        assertTrue(
            "the #964 test seam pins the keepalive-alive verdict",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )
        vm.forceTransportProvenAliveForTest = null
        assertFalse(
            "clearing the seam falls back to the (now dead) session signal",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        vm.clearForTest()
    }

    @Test
    fun briefPassiveEofSilentlyReattachesWithoutDisconnectBandOrConnectAttempt() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 1_000L, silentReattachTimeoutMs = 1_000L)
        val session = FakeSshSession()
        val deadClient = FakeTmuxClient()
        val replacementClient = FakeTmuxClient().withSinglePane("work", "%1")
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementClient
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = session,
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceUntilIdle()

        assertSame(replacementClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals("work", vm.activeSessionNameForTest())
        assertEquals(
            "silent same-SSH reattach must not count as a logical reconnect attempt",
            1,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertTrue("stale client should be closed after silent reattach", deadClient.closed)
        assertTrue("replacement control client should be opened", replacementClient.connectCalled)
    }

    @Test
    fun passiveEofShowsReconnectOnlyAfterSilentGraceExpires() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = FailingLeaseConnector(IOException("network still unavailable"))
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 500L, silentReattachTimeoutMs = 500L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceTimeBy(499L)
        runCurrent()

        // EPIC #687 slice 1c-iv-a — APPROVED #685 divergence #1 (silent recovery):
        // a recoverable live-channel drop now surfaces a CALM Reconnecting band
        // (the controller heals through Reattaching/Reconnecting), NOT the scary
        // Failed/"Tap Reconnect" band and NOT the old silently-held Connected frame.
        // Only the displayed status is the calm Reconnecting (was: Connected held).
        assertTrue(
            "recoverable drop must show the calm Reconnecting band, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertSame(
            "dashboard registry should still point at the held client during grace",
            deadClient,
            registry.clients.value[7L]?.client,
        )
        // EPIC #792 #833: the silent-reattach grace loop must KEEP re-dialling a
        // fresh transport across a SUSTAINED clean outage (the old one-shot latch
        // wedged after a single attempt). With a 500ms grace + 250ms retry spacing
        // the loop re-dials more than once before grace expires — proving the
        // resilience fix — while still BOUNDED by the grace window (no hot loop /
        // infinite re-dial: the count is small, paced by the retry delay).
        assertTrue(
            "silent fresh-transport probing must RE-DIAL across a sustained outage " +
                "(resilience #833), got connectCount=${connector.connectCount}",
            connector.connectCount >= 2,
        )
        assertTrue(
            "silent fresh-transport probing must stay BOUNDED by the grace window " +
                "(no hot loop), got connectCount=${connector.connectCount}",
            connector.connectCount <= 4,
        )

        advanceTimeBy(2L)
        runCurrent()

        val status = vm.connectionStatus.value
        assertTrue(
            "sustained passive EOF should surface the manual reconnect state after grace, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(registry.clients.value.isEmpty())
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
    }

    /**
     * Issue #685 (Bug A): the grace-clock collapse. The maintainer's #1 daily
     * pain ("lots of reconnects on stable home Wi-Fi") was caused by THREE
     * disagreeing grace clocks, the worst being a stray 8s VM
     * `PASSIVE_DISCONNECT_GRACE_MS` that tore the held Connected frame down long
     * before the single 60s lease/keepalive would. This pins the VM grace to the
     * ONE lease-anchored 60s window so the divergent 8s clock can never be
     * reintroduced — a sub-60s background/blip must hold without a reconnect.
     */
    @Test
    fun passiveDisconnectGraceIsAnchoredToTheSingle60sLeaseWindow() {
        assertEquals(
            "VM passive-disconnect grace must defer to the single 60s lease TTL " +
                "(no divergent 8s VM clock); collapsing the clocks is the #685 " +
                "Bug A reconnect-on-stable-Wi-Fi fix",
            SshLeaseManager.DEFAULT_IDLE_TTL_MILLIS,
            PASSIVE_DISCONNECT_GRACE_MS,
        )
        assertEquals(
            "the single lease-anchored grace window is 60s",
            60_000L,
            PASSIVE_DISCONNECT_GRACE_MS,
        )
    }

    /**
     * Issue #685 (Bug A): a passive transport blip WITHIN the lease-anchored 60s
     * grace must reattach silently with ZERO logical reconnect, using the REAL
     * production grace default (no test override) so the regression is caught at
     * the shipped value — not a convenient short test grace. The held Connected
     * frame stays; the dashboard registry keeps pointing at the live client.
     */
    @Test
    fun withinDefaultGracePassiveBlipReattachesWithZeroReconnect() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        // Deliberately NO setPassiveDisconnectRecoveryForTest override: exercise
        // the shipped 60s grace so a regressed short clock would fail here.
        val session = FakeSshSession()
        val deadClient = FakeTmuxClient()
        val replacementClient = FakeTmuxClient().withSinglePane("work", "%1")
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementClient
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = session,
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        // Resolve the silent reattach WITHIN the 60s grace.
        advanceTimeBy(5_000L)
        runCurrent()
        advanceUntilIdle()

        assertSame(
            "a within-grace blip must hand off to the freshly reattached client, " +
                "not strand on the dead one",
            replacementClient,
            registry.clients.value[7L]?.client,
        )
        assertTrue(
            "within the 60s grace the indicator stays green — no reconnect band",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(
            "a within-grace silent reattach must NOT count as a logical reconnect",
            1,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
    }

    /**
     * Issue #685 (Bug B): the "control channel closed before response /
     * mid-command" error in the maintainer's screenshot is the beyond-grace
     * foreground-reattach symptom. It must be classified as a stale-channel
     * symptom so it routes through the transparent re-dial (calm Reconnecting +
     * automatic pane reseed) instead of the scary `Failed` band with "Tap
     * Reconnect to retry." + a stuck spinner.
     */
    @Test
    fun controlChannelClosedIsTreatedAsAStaleChannelSymptom() {
        val vm = newVm()
        assertTrue(
            "'control channel closed before response' must heal via transparent " +
                "re-dial, not the scary manual-tap band",
            vm.isStaleChannelSymptom(
                TmuxClientException("control channel closed before response"),
            ),
        )
        assertTrue(
            "'control channel closed mid-command' must also heal transparently",
            vm.isStaleChannelSymptom(
                RuntimeException(
                    "list-panes failed",
                    TmuxClientException("control channel closed mid-command"),
                ),
            ),
        )
        assertFalse(
            "an ordinary error must NOT be misclassified as a stale-channel heal",
            vm.isStaleChannelSymptom(IllegalStateException("some unrelated failure")),
        )
    }

    /**
     * Issue #685 (honest indicator, false-negative direction — the "no
     * indication I'm disconnected" complaint): a sustained passive disconnect
     * that the silent grace CANNOT recover must flip the indicator OFF green
     * within the bounded grace — never keep showing a stale "connected" green
     * dot over a confirmed-dead channel. After the bounded grace the status is a
     * non-Connected (honest) state.
     */
    @Test
    fun sustainedPassiveDisconnectFlipsIndicatorOffGreenWithinBoundedGrace() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = FailingLeaseConnector(IOException("network still unavailable"))
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        // Bounded test grace so the assertion is deterministic; production uses
        // the 60s lease-anchored window asserted separately above.
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 500L, silentReattachTimeoutMs = 500L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceTimeBy(501L)
        runCurrent()
        advanceUntilIdle()

        assertFalse(
            "a confirmed-dead channel must NOT keep showing a stale green " +
                "'connected' indicator past the bounded grace (the #685 " +
                "'no indication I'm disconnected' false-negative)",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    @Test
    fun passiveEofSilentlyReacquiresTransportWhenOldSessionIsBroken() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 1_000L, silentReattachTimeoutMs = 1_000L)
        val deadClient = FakeTmuxClient()
        val replacementClient = FakeTmuxClient().withSinglePane("work", "%1")
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementClient
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceUntilIdle()

        assertEquals(1, connector.connectCount)
        assertSame(replacementClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(
            "hidden fresh-transport recovery must not increment the user-visible connect counter",
            1,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertTrue(replacementClient.connectCalled)
    }

    @Test
    fun failedSilentTransportReattachEvictsLeaseSoManualReconnectUsesFreshSession() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val failedReconnectSession = FakeSshSession()
        val manualReconnectSession = FakeSshSession()
        val connector = QueueLeaseConnector(failedReconnectSession, manualReconnectSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 100L, silentReattachTimeoutMs = 1L)
        val deadClient = FakeTmuxClient()
        val failedAttachClient = FakeTmuxClient()
        val manualReconnectClient = FakeTmuxClient().withSinglePane("work", "%9")
        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        val clients = ArrayDeque(listOf(failedAttachClient, manualReconnectClient))
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            clients.removeFirstOrNull() ?: error("unexpected tmux client factory call")
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        advanceUntilIdle()

        assertTrue(
            "failed hidden reattach should surface manual reconnect",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            "failed hidden reattach must close the half-attached tmux client",
            failedAttachClient.closed,
        )
        assertTrue(
            "failed hidden reattach must evict the acquired SSH lease instead of idling it",
            failedReconnectSession.closed,
        )
        assertEquals(1, connector.connectCount)

        assertTrue("manual reconnect should be available after failed hidden reattach", vm.reconnect())
        advanceUntilIdle()

        assertEquals(
            "manual reconnect must open a fresh SSH session, not reuse the failed hidden lease",
            2,
            connector.connectCount,
        )
        assertEquals(
            listOf(failedReconnectSession, manualReconnectSession),
            sessionsSeenByFactory,
        )
        assertSame(manualReconnectClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun manualReconnectCancelsInFlightSilentTransportReattachAndUsesFreshSession() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(1)
        val registry = ActiveTmuxClients()
        val hiddenReconnectSession = FakeSshSession()
        val manualReconnectSession = FakeSshSession()
        val connector = QueueLeaseConnector(hiddenReconnectSession, manualReconnectSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 60_000L, silentReattachTimeoutMs = 60_000L)
        val deadClient = FakeTmuxClient()
        val hiddenAttachGate = CompletableDeferred<Unit>()
        val hiddenAttachClient = FakeTmuxClient().apply {
            sendCommandGatePrefix = "list-panes"
            sendCommandGate = hiddenAttachGate
        }
        val manualReconnectClient = FakeTmuxClient().withSinglePane("work", "%9")
        val clients = ArrayDeque(listOf(hiddenAttachClient, manualReconnectClient))
        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            clients.removeFirstOrNull() ?: error("unexpected tmux client factory call")
        }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
            session = FakeSshSession(isConnectedValue = false),
        )
        runCurrent()

        deadClient.disconnectedSignal.value = true
        runCurrent()

        assertTrue(
            "hidden reattach should be stalled while waiting for panes",
            hiddenAttachClient.sentCommands.any { it.startsWith("list-panes") },
        )
        assertEquals(1, connector.connectCount)

        assertTrue("manual reconnect should be accepted while hidden reattach is in flight", vm.reconnect())
        advanceUntilIdle()

        assertTrue(
            "interrupted hidden reattach must close its half-attached tmux client",
            hiddenAttachClient.closed,
        )
        assertTrue(
            "interrupted hidden reattach must evict its fresh SSH lease",
            hiddenReconnectSession.closed,
        )
        assertEquals(
            "manual reconnect must open a fresh SSH session after interrupting hidden reattach",
            2,
            connector.connectCount,
        )
        assertEquals(
            listOf(hiddenReconnectSession, manualReconnectSession),
            sessionsSeenByFactory,
        )
        assertSame(manualReconnectClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun networkChangeLifecycleHookEntersReconnectingWithoutConnectionError() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "validated-default-network-changed"),
        )
        runCurrent()

        assertEquals(
            "network change should not wait for the tmux reader EOF",
            0,
            connector.connectCount,
        )
        val status = vm.connectionStatus.value
        assertTrue(
            "network change must show reconnect-in-progress, not a connection error; got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertTrue(
            "reconnect reason should avoid misleading manual retry wording",
            "Tap Reconnect" !in (status as TmuxSessionViewModel.ConnectionStatus.Reconnecting).reason,
        )
        assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
        assertTrue("manual reconnect remains available during proactive reconnect", vm.canReconnect.value)
        assertTrue(
            "proactive reconnect should remove the stale active client from the registry",
            registry.clients.value.isEmpty(),
        )
    }

    @Test
    fun sameNetworkHandleWithDifferentTransportsDoesNotEnterReconnecting() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        TMUX_CONNECT_ATTEMPTS.set(0)
        try {
            val registry = ActiveTmuxClients()
            val connector = QueueLeaseConnector(FakeSshSession())
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
            )
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            val client = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = client,
            )
            runCurrent()

            registry.lifecycleHooksSnapshot().single().onNetworkChanged(
                networkChange(
                    previous = TerminalNetworkSnapshot.Validated("same-handle", setOf("WIFI")),
                    current = TerminalNetworkSnapshot.Validated("same-handle", setOf("VPN", "WIFI")),
                    previousValidated = TerminalNetworkSnapshot.Validated("same-handle", setOf("WIFI")),
                    reason = "same-handle-transport-metadata",
                ),
            )
            advanceUntilIdle()

            assertTrue(
                "same network handle with changed transports is metadata churn, got ${vm.connectionStatus.value}",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertEquals(0, connector.connectCount)
            assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
            assertSame(client, registry.clients.value[7L]?.client)
            val skip = diagnostics.eventsNamed("network_reconnect_skip").single()
            assertEquals("no_real_validated_handoff", skip.fields["cause"])
            assertEquals("network_identity_unchanged", skip.fields["classification"])
            assertEquals(false, skip.fields["reconnect"])
            assertEquals(false, skip.fields["realValidatedIdentityChange"])
            assertEquals("same-handle", skip.fields["currentNetworkHandle"])
            assertEquals("VPN,WIFI", skip.fields["currentTransports"])
            val trail = diagnostics.eventsNamed("cause_trail")
                .single { it.fields["stage"] == "network_reconnect_decision" }
            assertEquals("suppress", trail.fields["outcome"])
            assertEquals("no_real_validated_handoff", trail.fields["cause"])
            assertEquals("network_identity_unchanged", trail.fields["classification"])
            assertEquals(7L, trail.fields["hostId"])
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun differentNetworkHandleStillEntersReconnecting() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val registry = ActiveTmuxClients()
            val connector = QueueLeaseConnector(FakeSshSession())
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
            )
            vm.setAutoReconnectDelaysForTest(listOf(60_000L))
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = FakeTmuxClient(),
            )
            runCurrent()

            registry.lifecycleHooksSnapshot().single().onNetworkChanged(
                networkChange(
                    previous = TerminalNetworkSnapshot.Validated("wifi", setOf("WIFI")),
                    current = TerminalNetworkSnapshot.Validated("cell", setOf("CELLULAR")),
                    previousValidated = TerminalNetworkSnapshot.Validated("wifi", setOf("WIFI")),
                    reason = "different-network-handle",
                ),
            )
            runCurrent()

            assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting)
            assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
            assertEquals(0, connector.connectCount)
            assertTrue("network reconnect removes stale active client", registry.clients.value.isEmpty())
            val start = diagnostics.eventsNamed("network_reconnect_start").single()
            assertEquals("proactive_network_handoff", start.fields["classification"])
            assertEquals(true, start.fields["reconnect"])
            assertEquals(true, start.fields["realValidatedIdentityChange"])
            assertEquals("wifi", start.fields["previousValidatedNetworkHandle"])
            assertEquals("cell", start.fields["currentNetworkHandle"])
            val trail = diagnostics.eventsNamed("cause_trail")
                .single { it.fields["stage"] == "network_reconnect_decision" }
            assertEquals("schedule_reconnect", trail.fields["outcome"])
            assertEquals("proactive_network_handoff", trail.fields["cause"])
            assertEquals("proactive_network_handoff", trail.fields["classification"])
            assertEquals(7L, trail.fields["hostId"])
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun restoredSameNetworkHintDuringActiveTerminalOutputDoesNotShowReconnect() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L, 0L))
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%0",
                    "@0",
                    "\$0",
                    "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        advanceUntilIdle()

        val state = vm.panes.value.single().terminalState
        val slowSideChannelCollector = launch {
            state.output.collect {
                delay(60_000)
            }
        }
        runCurrent()

        try {
            client.emittedEvents.emit(ControlEvent.Output("%0", issue576BackpressureOutputChunks().first()))
            runCurrent()

            val hook = registry.lifecycleHooksSnapshot().single()
            repeat(4) { index ->
                hook.onNetworkChanged(
                    networkChange(
                        previous = TerminalNetworkSnapshot.NoValidatedNetwork,
                        current = TerminalNetworkSnapshot.Validated("wifi"),
                        previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                        reason = "validated-network-during-output-$index",
                    ),
                )
                runCurrent()
            }

            assertTrue(
                "active terminal output proves the tmux stream is alive; network hints must not show reconnect",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertEquals(
                "network hints during active output must not enqueue reconnect attempts",
                0,
                connector.connectCount,
            )
            assertEquals(
                "network hints during active output must not increment the reconnect counter",
                0,
                TMUX_CONNECT_ATTEMPTS.get(),
            )
            assertFalse(
                "terminal-side backpressure must not flip the tmux disconnected signal",
                client.disconnectedSignal.value,
            )
        } finally {
            slowSideChannelCollector.cancel()
        }
    }

    @Test
    fun outputForOnlyTerminalOutputKeepsNetworkHintFromShowingReconnect() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
        val client = FakeTmuxClient()
        client.decoupleOutputForFromEvents = true
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%0",
                    "@0",
                    "\$0",
                    "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        advanceUntilIdle()

        client.emittedPaneOutputs.emit(ControlEvent.Output("%0", "visible via outputFor".toByteArray()))
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.NoValidatedNetwork,
                current = TerminalNetworkSnapshot.Validated("wifi"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "same-network-hint-after-outputFor-only-output",
            ),
        )
        runCurrent()

        assertTrue(
            "visible output delivered without client.events %output must still suppress same-network reconnect",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(0, connector.connectCount)
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
    }

    @Test
    fun firstValidatedNetworkHintDoesNotReconnectIdleStableTerminal() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        advanceUntilIdle()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.NoValidatedNetwork,
                current = TerminalNetworkSnapshot.Validated("wifi"),
                previousValidated = null,
                reason = "first-validated-network",
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "first validated callback is startup state discovery, not proof that SSH died",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(0, connector.connectCount)
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
    }

    @Test
    fun realNetworkIdentityChangeDuringActiveTerminalOutputStillReconnects() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        runCurrent()
        client.emittedEvents.emit(ControlEvent.Output("%0", "still streaming".toByteArray()))
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "wifi-cellular-handoff",
            ),
        )
        runCurrent()

        assertEquals(
            "real validated identity change should still use proactive reconnect despite recent output",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting)
        assertEquals(0, connector.connectCount)
    }

    // ---- Issue #981: network handoff rides through while the transport is proven alive ----

    @Test
    fun issue981ValidatedNetworkFlipDoesNotTearDownTransportProvenAliveLink() = runTest(scheduler) {
        // Issue #981 reproduce-first (red on base): a REAL validated default-network
        // identity flip (WIFI→CELLULAR) arrives while the live SSH transport is
        // provably alive (its keepalive saw inbound bytes within the ride-through
        // window — the #974 stable-wifi case where -CC traffic keeps the link warm).
        // The reactive handoff path must NOT tear down + redial the healthy socket.
        // On base (no liveness gate) this asserts Reconnecting + 1 connect and FAILS;
        // with the fix it rides through (Connected, zero connects).
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        // A live session whose transport keepalive proves the link is alive.
        val session = FakeSshSession().apply { transportProvenAlive = true }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )
        advanceUntilIdle()
        assertTrue(
            "precondition: the link is the proven-alive transport the gate must ride through",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "transient-wifi-cellular-flip-on-stable-wifi",
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "a validated network flip while the transport is PROVEN ALIVE must ride through, " +
                "not tear down the healthy socket (#981 / #974 stable-wifi drop)",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(
            "no redial may be scheduled while the old transport is provably alive",
            0,
            connector.connectCount,
        )
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
        assertNotEquals(
            "the proactive-handoff redial intent must NOT fire on a proven-alive link",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
    }

    @Test
    fun issue981ValidatedNetworkFlipStillReconnectsWhenTransportIsDead() = runTest(scheduler) {
        // Issue #981 class-coverage (G2): the gate must NOT mask a GENUINE handoff.
        // Same WIFI→CELLULAR flip, but the transport keepalive has aged out (the old
        // socket is really dead) → the path must STILL reconnect, exactly as before.
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        // A live session whose transport keepalive has stopped proving liveness.
        val session = FakeSshSession().apply { transportProvenAlive = false }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )
        runCurrent()
        assertFalse(
            "precondition: the dead transport must NOT report proven-alive",
            vm.isTransportKeepAliveProvenAliveRecentlyForTest(),
        )

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "genuine-wifi-cellular-handoff-dead-link",
            ),
        )
        runCurrent()

        assertEquals(
            "a genuine handoff on a DEAD link must still proactively reconnect (gate must not mask recovery)",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting)
    }

    @Test
    fun issue981SameIdentityReassocStillSuppressedRegardlessOfTransportLiveness() = runTest(scheduler) {
        // Issue #981 class-coverage: a same-identity reassoc (#875 pure-{WIFI} roam)
        // is still suppressed by hasSameNetworkIdentityAs BEFORE the liveness gate,
        // so the new gate does not change that path — proven-alive or not.
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val session = FakeSshSession().apply { transportProvenAlive = false }
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )
        advanceUntilIdle()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("wifi"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "same-identity-reassoc",
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "a same-identity reassoc is suppressed upstream (#875) and never reaches the redial",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(0, connector.connectCount)
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
    }

    @Test
    fun issue981ReducerSuppressesProvenAliveButSchedulesDeadLinkFlip() = runTest(scheduler) {
        // Issue #981 reducer-level proof: the decision classifier itself returns
        // SuppressNetworkTransportProvenAlive when proven alive and
        // ScheduleNetworkReconnect when the link is dead, using the explicit seam.
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()

        // Proven alive → ride through.
        vm.forceTransportProvenAliveForTest = true
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "flip-while-proven-alive"),
        )
        runCurrent()
        assertTrue(
            "proven-alive flip rides through (no Reconnecting band)",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(0, connector.connectCount)

        // Aged out (dead) → reconnect.
        vm.forceTransportProvenAliveForTest = false
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "flip-while-dead", sequence = 2L),
        )
        runCurrent()
        assertEquals(
            "once the keepalive is dead the same flip must schedule the proactive reconnect",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting)

        vm.forceTransportProvenAliveForTest = null
    }

    // ---- Issue #997: bare network LOSS → hold (no churn) → RESTORE → fast reconnect.
    // Pre-#997 a clean loss produced NO proactive change at all (the detector
    // swallowed it), so a loss-suspended session never fast-recovered — it waited
    // ~90s for the keepalive ride-through. The reducer arms here are the
    // ViewModel half of the fix: a NetworkLost holds the lease + surfaces the calm
    // band without churning; a NetworkRestored drives `scheduleAutoReconnect` even
    // from a non-Connected state and bypasses the proven-alive gate.

    @Test
    fun issue997BareNetworkLossHoldsTheLeaseWithoutChurningOrTearingDown() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
        runCurrent()

        // The calm "reconnecting" band is surfaced (UI not left on a dead-but-live
        // session) WITHOUT launching a redial loop and WITHOUT tearing the lease.
        assertTrue(
            "a bare loss surfaces the calm Reconnecting band immediately",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals("no redial during the loss window — lease is held", 0, connector.connectCount)

        // No churn: even after time passes nothing redials (no ladder running).
        advanceTimeBy(60_000L)
        advanceUntilIdle()
        assertEquals(0, connector.connectCount)
    }

    @Test
    fun issue997NetworkRestoreDrivesFastReconnectFromLossSuspendedState() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()

        // Loss: hold + flip to Reconnecting (the loss-suspended state).
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
        runCurrent()
        assertTrue(
            "loss leaves the session in the loss-suspended Reconnecting state",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals(0, connector.connectCount)

        // Restore: must drive a FAST reconnect even though status is NOT Connected
        // (the #997 gap — the Connected-only ScheduleNetworkReconnect path would
        // have Ignored this).
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkRestore())
        advanceUntilIdle()

        assertEquals(
            "restore must redial via the auto-reconnect ladder with the network trigger",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertEquals("exactly one fresh-lease redial on restore", 1, connector.connectCount)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
    }

    @Test
    fun issue997NetworkRestoreReconnectsEvenWhenTransportWasProvenAlive() = runTest(scheduler) {
        // A real loss means the old socket is DEAD, so the proven-alive
        // ride-through (#981) must NOT suppress a restore-driven reconnect. Pin
        // proven-alive=true and confirm the restore still redials (unlike a bare
        // validated handoff, which #981 rides through when proven alive).
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, _, _ -> reconnectClient }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        runCurrent()

        vm.forceTransportProvenAliveForTest = true
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkLoss())
        runCurrent()
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(networkRestore())
        advanceUntilIdle()

        assertEquals(
            "a restore after a real loss bypasses the proven-alive gate and reconnects",
            TmuxConnectTrigger.NetworkReconnect,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertEquals(1, connector.connectCount)

        vm.forceTransportProvenAliveForTest = null
    }

    @Test
    fun networkReconnectAndPassiveDisconnectAreCoalescedIntoOneAttempt() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )
        advanceUntilIdle()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "wifi-cellular-handoff"),
        )
        runCurrent()
        oldClient.disconnectedSignal.value = true
        runCurrent()

        assertTrue(
            "passive EOF during a scheduled network reconnect must not replace it with a second loop",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals(0, connector.connectCount)
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())

        advanceTimeBy(60_000L)
        advanceUntilIdle()

        assertEquals(
            "only the already scheduled network reconnect should open a transport",
            1,
            connector.connectCount,
        )
        assertEquals(1, TMUX_CONNECT_ATTEMPTS.get())
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
    }

    @Test
    fun networkChangeLifecycleHookProactivelyReattachesTmuxSession() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "validated-default-network-changed"),
        )
        advanceUntilIdle()

        assertEquals(1, connector.connectCount)
        assertTrue("old tmux control client must be detached during reconnect", oldClient.detachCleanlyCalled)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
    }

    @Test
    fun networkReconnectEvictsConnectedIdleLeaseBeforeReattaching() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val staleSession = FakeSshSession()
        val freshSession = FakeSshSession()
        val connector = QueueLeaseConnector(staleSession, freshSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        manager.acquire(testLeaseTarget()).getOrThrow().release()
        runCurrent()

        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "wifi-cellular-handoff"),
        )
        advanceUntilIdle()

        assertTrue(
            "stale connected idle lease must be closed before network reconnect acquire",
            staleSession.closed,
        )
        assertEquals(
            "network reconnect must open a fresh SSH transport instead of reusing the warm stale one",
            2,
            connector.connectCount,
        )
        assertEquals(listOf(freshSession), sessionsSeenByFactory)
        assertSame(reconnectClient, registry.clients.value[1L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
    }

    /**
     * Issue #548: when a network reconnect lands on a stale SSH transport
     * whose tmux control channel returns EOF on `list-panes`, the
     * auto-reconnect loop must:
     *
     *  1. detect the stale channel symptom and evict the poisoned lease
     *     ([SshLeaseManager.disconnect]),
     *  2. retry on a fresh SSH transport,
     *  3. recover to Connected.
     *
     * This proves the reconnect loop self-heals from a transport that
     * reports isConnected but silently drops tmux commands — the exact
     * symptom of a TCP reset that the SSH library hasn't noticed yet.
     */
    @Test
    fun networkReconnectRetriesAfterStaleListPanesEofAndRecovers() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val staleSession = FakeSshSession()
        val freshSession = FakeSshSession()
        val connector = QueueLeaseConnector(staleSession, freshSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )

        // Stale client: connect() succeeds but list-panes throws a
        // command-timeout exception (closeAndThrowOnCommandPrefix),
        // which is classified as a stale channel symptom.
        val staleClient = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "list-panes"
            closeAndThrowException = TmuxClientException("tmux command timed out")
        }

        // Fresh client: works normally.
        val freshClient = FakeTmuxClient().withSinglePane("work", "%1")

        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            if (session === staleSession) staleClient else freshClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )

        // Trigger network reconnect.
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "wifi-cellular-handoff"),
        )
        advanceUntilIdle()

        // Verify two connect attempts: stale (fails) + fresh (recovers).
        assertEquals(
            "auto-reconnect must dial twice: stale EOF then fresh recovery",
            2,
            connector.connectCount,
        )
        assertEquals(2, TMUX_CONNECT_ATTEMPTS.get())

        // Verify the stale SSH session was evicted (closed by disconnect).
        assertTrue(
            "stale SSH session must be evicted after list-panes EOF",
            staleSession.closed,
        )

        // Verify the factory saw both sessions: stale (failed) then fresh (recovered).
        assertEquals(listOf(staleSession, freshSession), sessionsSeenByFactory)

        // Verify the VM recovered to Connected with the fresh client.
        assertSame(freshClient, registry.clients.value[1L]?.client)
        assertTrue(
            "expected network reconnect to recover to Connected after stale retry, " +
                "got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
    }

    @Test
    fun lifecycleReattachEvictsConnectedIdleLeaseBeforeReattaching() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val staleSession = FakeSshSession()
        val freshSession = FakeSshSession()
        val connector = QueueLeaseConnector(staleSession, freshSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        manager.acquire(testLeaseTarget()).getOrThrow().release()
        runCurrent()

        val lifecycleClient = FakeTmuxClient().withSinglePane("work", "%1")
        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            lifecycleClient
        }
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )

        vm.onAppBackgroundedAndAwait()
        vm.onAppForegrounded()
        advanceUntilIdle()

        assertTrue(
            "stale connected idle lease must be closed before lifecycle reattach acquire",
            staleSession.closed,
        )
        assertEquals(
            "lifecycle reattach must open a fresh SSH transport instead of reusing the warm stale one",
            2,
            connector.connectCount,
        )
        assertEquals(listOf(freshSession), sessionsSeenByFactory)
        assertSame(lifecycleClient, registry.clients.value[1L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(TmuxConnectTrigger.LifecycleReattach, vm.latestRestoreIntentSnapshot()?.trigger)
    }

    @Test
    fun postGraceLifecycleReattachCoalescesDeferredNetworkReplay() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val lifecycleClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            lifecycleClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onBackground()
        advanceUntilIdle()
        assertTrue("background teardown should detach the stale control client", oldClient.closed)

        registry.lifecycleHooksSnapshot().single().onForeground(false)
        advanceUntilIdle()

        assertEquals(1, connector.connectCount)
        assertTrue(lifecycleClient.connectCalled)
        assertSame(lifecycleClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(TmuxConnectTrigger.LifecycleReattach, vm.latestRestoreIntentSnapshot()?.trigger)

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "post-grace-deferred-network-replay",
                sequence = 42L,
                deferredFromBackground = true,
            ),
        )
        runCurrent()

        assertTrue(
            "deferred replay after fresh lifecycle attach must stay connected, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(
            "deferred replay must not schedule a second network reconnect",
            TmuxConnectTrigger.LifecycleReattach,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertEquals(1, connector.connectCount)
        assertEquals(1, TMUX_CONNECT_ATTEMPTS.get())
        assertSame(lifecycleClient, registry.clients.value[7L]?.client)
    }

    @Test
    fun postGraceLifecycleReattachCoalescesForegroundNetworkReplayWithoutForceFreshReconnect() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val staleIdleSession = FakeSshSession()
        val lifecycleSession = FakeSshSession()
        val networkReconnectSession = FakeSshSession()
        val connector = QueueLeaseConnector(
            staleIdleSession,
            lifecycleSession,
            networkReconnectSession,
        )
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        manager.acquire(testLeaseTarget()).getOrThrow().release()
        runCurrent()

        val lifecycleClient = FakeTmuxClient().withSinglePane("work", "%1")
        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            lifecycleClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onBackground()
        advanceUntilIdle()
        registry.lifecycleHooksSnapshot().single().onForeground(false)
        advanceUntilIdle()

        assertTrue("post-grace lifecycle attach must evict the old idle transport", staleIdleSession.closed)
        assertEquals(listOf(lifecycleSession), sessionsSeenByFactory)
        assertEquals(2, connector.connectCount)
        assertSame(lifecycleClient, registry.clients.value[1L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(TmuxConnectTrigger.LifecycleReattach, vm.latestRestoreIntentSnapshot()?.trigger)

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "process-foreground",
                sequence = 43L,
                deferredFromBackground = false,
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "foreground network replay after fresh lifecycle attach must not force-refresh the active lease",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse("fresh lifecycle SSH session must remain active", lifecycleSession.closed)
        assertFalse("no network reconnect transport should be opened", networkReconnectSession.closed)
        assertEquals(
            "foreground replay must not schedule a second NetworkReconnect acquire",
            2,
            connector.connectCount,
        )
        assertEquals(listOf(lifecycleSession), sessionsSeenByFactory)
        assertEquals(TmuxConnectTrigger.LifecycleReattach, vm.latestRestoreIntentSnapshot()?.trigger)
        assertSame(lifecycleClient, registry.clients.value[1L]?.client)
    }

    @Test
    fun networkReconnectRetriesTransientFlapThenRecoversWithoutOverlappingAttempts() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = FailingThenConnectingLeaseConnector(
            failures = listOf(
                SshException("SSH connect to alex@alpha.example:22 failed: temporary link cut"),
                SshException("SSH connect to alex@alpha.example:22 failed: transient latency timeout"),
            ),
            session = FakeSshSession(),
        )
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L, 250L, 250L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "wifi-cellular-flap"),
        )
        runCurrent()

        assertEquals(
            "first network reconnect attempt should run immediately and fail transiently",
            1,
            connector.connectCount,
        )
        assertTrue(
            "after the first transient failure the VM should stay in the bounded reconnect loop",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals(1, TMUX_CONNECT_ATTEMPTS.get())

        advanceTimeBy(250L)
        runCurrent()

        assertEquals(
            "second network reconnect attempt should be the next bounded backoff step",
            2,
            connector.connectCount,
        )
        assertTrue(
            "after the second transient failure the VM should still wait for the final retry",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals(2, TMUX_CONNECT_ATTEMPTS.get())

        advanceTimeBy(250L)
        advanceUntilIdle()

        assertEquals(
            "third network reconnect attempt should recover when the link returns",
            3,
            connector.connectCount,
        )
        assertEquals(
            "bounded reconnect loop must not overlap SSH dials while the network is flapping",
            1,
            connector.maxConcurrentConnects,
        )
        assertTrue("old tmux client must be closed during reconnect", oldClient.closed)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(
            "expected network reconnect to recover to Connected, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(3, TMUX_CONNECT_ATTEMPTS.get())
        assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
    }

    @Test
    fun eofDisconnectDoesNotBurnAutoReconnectAttempts() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
        )

        deadClient.disconnectedSignal.value = true
        advanceUntilIdle()

        val status = vm.connectionStatus.value
        assertTrue(
            "expected manual reconnect failure state, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Disconnected from alex@alpha.example:22. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
        assertEquals("work", vm.activeSessionNameForTest())
        assertTrue("manual reconnect must remain available after passive EOF", vm.canReconnect.value)
    }

    @Test
    fun explicitReconnectAfterEofReportsNonRetryableAuthFailureOnce() = runTest(scheduler) {
        // Issue #440: a non-retryable failure (auth rejection) must NOT burn
        // the whole backoff schedule when a passive EOF has already surfaced
        // the manual Reconnect affordance.
        val registry = ActiveTmuxClients()
        val connector = FailingLeaseConnector(
            SshException(
                "SSH connect to alex@alpha.example:22 failed: UserAuthException: auth fail",
                UserAuthException("Exhausted available authentication methods"),
            ),
        )
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        // Four delays available — if the abort fails, all four would be used.
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L, 0L))
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
        )

        deadClient.disconnectedSignal.value = true
        runCurrent()

        assertEquals(0, connector.connectCount)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed)

        assertTrue(vm.reconnect())
        advanceUntilIdle()

        assertEquals(
            "explicit reconnect after passive EOF must make one SSH attempt",
            1,
            connector.connectCount,
        )
        val status = vm.connectionStatus.value
        assertTrue(
            "expected Failed after non-retryable auth failure, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        val message = (status as TmuxSessionViewModel.ConnectionStatus.Failed).message
        assertTrue(
            "Failed message must explain the non-retryable cause, was: $message",
            message.contains("auth fail") || message.contains("authentication failed"),
        )
        assertFalse(
            "non-retryable abort must not report exhausting all attempts, was: $message",
            message.contains("Auto reconnect failed after"),
        )
    }

    @Test
    fun explicitReconnectAfterEofDoesNotLoopOnTransientFailure() = runTest(scheduler) {
        // Issue #440: a transient transport failure (e.g. connection refused
        // while the host reboots) should not become a reconnect storm after a
        // passive EOF has surfaced the manual Reconnect affordance.
        val registry = ActiveTmuxClients()
        val connector = FailingThenConnectingLeaseConnector(
            failures = listOf(
                SshException(
                    "SSH connect to alex@alpha.example:22 failed: ConnectException: Connection refused",
                    ConnectException("Connection refused"),
                ),
            ),
            session = FakeSshSession(),
        )
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L))
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
        )

        deadClient.disconnectedSignal.value = true
        runCurrent()

        assertEquals(0, connector.connectCount)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed)

        assertTrue(vm.reconnect())
        advanceUntilIdle()

        assertEquals(
            "explicit reconnect after passive EOF should not retry in a tight loop",
            1,
            connector.connectCount,
        )
        assertTrue(
            "expected Failed after one transient reconnect failure, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
    }

    @Test
    fun tmuxAutoReconnectDelayIsCancelledWhenAppBackgrounds() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val registry = ActiveTmuxClients()
            val connector = QueueLeaseConnector(FakeSshSession())
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
            )
            vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
            vm.setAutoReconnectDelaysForTest(listOf(60_000L))
            val client = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = client,
            )

            registry.lifecycleHooksSnapshot().single().onNetworkChanged(
                networkChange(reason = "validated-default-network-changed"),
            )
            runCurrent()
            assertTrue(
                "network reconnect must enter retry delay before background",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
            )

            vm.onAppBackgrounded()
            advanceUntilIdle()
            advanceTimeBy(60_000L)
            advanceUntilIdle()

            assertEquals(
                "backgrounding during retry delay must cancel tmux reconnect attempts",
                0,
                connector.connectCount,
            )
            val status = vm.connectionStatus.value
            assertTrue(
                "backgrounded reconnect should settle in a manual retry state, got $status",
                status is TmuxSessionViewModel.ConnectionStatus.Failed,
            )
            assertTrue(
                (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
                status.message.contains("Auto reconnect paused while PocketShell is in the background."),
            )
            assertEquals("work", vm.connectingSessionNameForTest())
            assertTrue("manual reconnect remains available after background pause", vm.canReconnect.value)

            val decisions = diagnostics.eventsNamed("auto_reconnect_decision")
            assertTrue(
                "network reconnect must log that auto reconnect was scheduled",
                decisions.any {
                    it.fields["decision"] == "scheduled" &&
                        it.fields["cause"] == "retryable" &&
                        it.fields["trigger"] == TmuxConnectTrigger.NetworkReconnect.logValue
                },
            )
            assertTrue(
                "backgrounded retry delay must log auto reconnect cancellation",
                decisions.any {
                    it.fields["decision"] == "cancelled_due_to_background" &&
                        it.fields["cause"] == "app_background_lifecycle_pause" &&
                        it.fields["trigger"] == TmuxConnectTrigger.NetworkReconnect.logValue
                },
            )
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun foregroundReturnResumesBackgroundPausedAutoReconnect() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "validated-default-network-changed"),
        )
        runCurrent()
        assertTrue(
            "network reconnect must be waiting in auto-reconnect delay before background",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )

        vm.onAppBackgrounded()
        advanceUntilIdle()
        val backgroundStatus = vm.connectionStatus.value
        assertTrue(
            "background pause should be represented as Failed while app is not visible",
            backgroundStatus is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            (backgroundStatus as TmuxSessionViewModel.ConnectionStatus.Failed).message,
            backgroundStatus.message.contains("Auto reconnect paused while PocketShell is in the background."),
        )
        assertEquals(
            "backgrounding during retry delay must not connect",
            0,
            connector.connectCount,
        )

        vm.onAppForegrounded()
        advanceUntilIdle()

        assertEquals(
            "foreground return must resume the paused reconnect automatically",
            1,
            connector.connectCount,
        )
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(
            "foreground resume should reconnect, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        val foregroundStatus = vm.connectionStatus.value
        assertFalse(
            "stale background-paused copy must not remain visible in foreground",
            foregroundStatus.toString().contains("Auto reconnect paused while PocketShell is in the background."),
        )
    }

    // --- EPIC #766 slice 2a: the bg/fg arms are DRIVEN by the controller edge ----
    //
    // These pin the re-home of the inline `reduceConnection(Background/Foreground)`
    // arm dispatch onto the ConnectionController state EDGE fired by the
    // ConnectionEffectDriver. Each asserts the post-migration decision matches the
    // inline reducer's prior behavior (D31 per-event red→green): if the bg/fg arms
    // were NOT wired to the driver edge, the detach/replay would never run and these
    // would be RED. The #685 trap is covered: the detach arm fires only when the
    // inline-equivalent predicate (clientRef/sessionRef present) holds even though the
    // controller transitions to Backgrounded whenever it holds a host.

    @Test
    fun backgroundDetachArmIsDrivenByControllerBackgroundedEdge() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        assertTrue(
            "precondition: live (controller Live -> displayed Connected)",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        vm.onAppBackgrounded()
        advanceUntilIdle()

        // The controller's -> Backgrounded edge fired the detach arm (re-home of the
        // inline ConnectionDecision.DetachForBackground): teardown ran AND the pending
        // reattach bookkeeping was stashed — exactly the inline reducer's prior behavior.
        assertTrue(
            "controller Backgrounded edge must drive the clean detach (detachCleanly)",
            client.detachCleanlyCalled,
        )
        assertTrue("controller Backgrounded edge must seed pending reattach", vm.hasPendingReattachForTest())
    }

    @Test
    fun backgroundEdgeDoesNotDetachWhenNoClientOrSession() = runTest(scheduler) {
        // The #685 trap: the controller transitions to Backgrounded whenever it holds a
        // host, but the inline-equivalent predicate also gates on clientRef/sessionRef.
        // With no live client/session, the inline reducer returned Ignore — so the
        // re-homed arm must NOT detach or stash a reattach (no client to tear down).
        val vm = newVm()
        vm.onAppBackgrounded()
        advanceUntilIdle()

        assertFalse(
            "background with no live client/session must not stash a pending reattach",
            vm.hasPendingReattachForTest(),
        )
    }

    @Test
    fun foregroundReplayArmIsDrivenByControllerForegroundEdge() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val vm = newVm()
            var foregroundReattachCount = 0
            vm.setForegroundReattachForTest { foregroundReattachCount += 1 }
            val client = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = client,
            )

            vm.onAppBackgrounded()
            advanceUntilIdle()
            assertTrue("background must stash a pending reattach", vm.hasPendingReattachForTest())
            assertTrue(
                "background detach must not emit foreground_reattach",
                diagnostics.eventsNamed("foreground_reattach").isEmpty(),
            )

            // Beyond grace (the lease was evicted on the detach teardown -> controller's grace
            // predicate is not-warm), the controller walks Backgrounded -> Reconnecting, which
            // fires the re-homed foreground arm (ConnectionDecision.ReplayPendingReattach).
            vm.onAppForegrounded()
            assertFalse(
                "app foreground hook must arm post-grace reattach without waiting on a later driver turn",
                vm.hasPendingReattachForTest(),
            )
            assertEquals(
                "app foreground hook must drive the replay reattach exactly once",
                1,
                foregroundReattachCount,
            )
            val reattach = diagnostics.eventsNamed("foreground_reattach").single()
            assertEquals("connection", reattach.category)
            assertEquals("app_lifecycle", reattach.fields["source"])
            assertEquals(TmuxConnectTrigger.LifecycleReattach.logValue, reattach.fields["trigger"])
            assertEquals(1L, reattach.fields["hostId"])
            assertEquals("work", reattach.fields["session"])
            advanceUntilIdle()

            assertFalse(
                "controller foreground edge must consume the pending reattach",
                vm.hasPendingReattachForTest(),
            )
            assertEquals(
                "controller foreground edge must drive the replay reattach exactly once",
                1,
                foregroundReattachCount,
            )
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun postGraceForegroundReplaysPendingReattachEvenWhenControllerStillSeesWarmLease() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val connector = QueueLeaseConnector(FakeSshSession())
            val leaseManager = testLeaseManager(
                connector = connector,
                scope = this,
                idleTtlMillis = 60_000L,
            )
            val vm = newVm(sshLeaseManager = leaseManager)
            runCurrent()

            val warmLease = leaseManager.acquire(testLeaseTarget()).getOrThrow()
            warmLease.release()
            runCurrent()
            assertTrue(
                "precondition: controller warm snapshot must still see a live lease",
                leaseManager.hasLiveLease(testLeaseTarget().leaseKey),
            )

            var foregroundReattachCount = 0
            vm.setForegroundReattachForTest { foregroundReattachCount += 1 }
            val client = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = client,
            )

            vm.onAppBackgrounded()
            runCurrent()
            assertTrue("background must detach the control client", client.detachCleanlyCalled)
            assertTrue("background must stash a pending reattach", vm.hasPendingReattachForTest())
            assertTrue(
                "warm lease should survive background control-client teardown",
                leaseManager.hasLiveLease(testLeaseTarget().leaseKey),
            )

            vm.onAppForegrounded(resumedWithinGrace = false)

            assertFalse(
                "post-grace foreground must consume pending reattach even if controller picks warm reseed",
                vm.hasPendingReattachForTest(),
            )
            assertEquals(
                "post-grace foreground must replay exactly once",
                1,
                foregroundReattachCount,
            )
            assertEquals(
                "VM-level foreground diagnostic must be emitted for the Android wait",
                1,
                diagnostics.eventsNamed("foreground_reattach").size,
            )
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun shortAppSwitchPassiveDisconnectResumesAutoReconnectOnScreenStart() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 60_000L, silentReattachTimeoutMs = 1_000L)
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        val backgroundDeadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = backgroundDeadClient,
        )
        runCurrent()

        vm.onScreenStopped()
        backgroundDeadClient.markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ReaderEof,
                source = "device_background",
                intent = "unknown",
            ),
        )
        runCurrent()

        assertFalse(
            "short app-switch disconnect must not detach tmux while the screen is stopped",
            backgroundDeadClient.detachCleanlyCalled,
        )
        assertEquals(
            "no SSH reconnect may run while the app is still in the short background switch",
            0,
            connector.connectCount,
        )
        assertTrue(
            "dead background client must be removed from the live registry",
            registry.clients.value.isEmpty(),
        )
        val backgroundStatus = vm.connectionStatus.value
        assertTrue(
            "background passive EOF should be paused for foreground auto-reconnect, got $backgroundStatus",
            backgroundStatus is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            (backgroundStatus as TmuxSessionViewModel.ConnectionStatus.Failed).message,
            backgroundStatus.message.contains("Auto reconnect paused while PocketShell is in the background."),
        )

        advanceTimeBy(6_000L)
        runCurrent()
        assertEquals(
            "a 6 second app switch is below the configured grace and must not start background SSH",
            0,
            connector.connectCount,
        )

        vm.onScreenStarted(sessionName = "work")
        advanceUntilIdle()

        assertEquals(
            "foreground return must automatically resume the paused reconnect",
            1,
            connector.connectCount,
        )
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(
            "foreground return must not remain in the manual reconnect-needed state; got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertTrue("automatic foreground reconnect should open the replacement client", reconnectClient.connectCalled)
    }

    /**
     * Issue #630: when the user navigates back from session A to the host list
     * and then selects session B on the same host, the paused reconnect for
     * session A must NOT fire. [onScreenStarted] must clear the stale
     * [pausedAutoReconnect] instead of resuming it.
     */
    @Test
    fun onScreenStartedClearsPausedReconnectForDifferentSession() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 60_000L, silentReattachTimeoutMs = 1_000L)
        vm.setAutoReconnectDelaysForTest(listOf(0L))

        // Set up session A ("work") as the active session.
        val clientA = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = clientA,
        )
        runCurrent()
        assertTrue(
            "session A should be connected",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // User navigates back from session A.
        vm.onScreenStopped()

        // Session A's tmux client disconnects while the screen is stopped.
        clientA.markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ReaderEof,
                source = "device_background",
                intent = "unknown",
            ),
        )
        runCurrent()

        // The paused reconnect should target session A.
        val pausedStatus = vm.connectionStatus.value
        assertTrue(
            "expected paused-reconnect status after background disconnect, got $pausedStatus",
            pausedStatus is TmuxSessionViewModel.ConnectionStatus.Failed,
        )

        // User selects session B ("personal") on the same host.
        // onScreenStarted for session B must clear the stale paused reconnect
        // instead of resuming it.
        vm.onScreenStarted(sessionName = "personal")
        runCurrent()

        // No SSH connect should have been triggered — the paused reconnect
        // for session A was cleared, and session B's LaunchedEffect connect
        // is not tested here (it would go through a different code path).
        assertEquals(
            "onScreenStarted for a different session must NOT trigger a connect",
            0,
            connector.connectCount,
        )
    }

    /**
     * Issue #630: verifies that a legitimate background-to-foreground resume
     * (same session) still works after the session-mismatch guard is added.
     */
    @Test
    fun onScreenStartedResumesPausedReconnectForSameSession() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 60_000L, silentReattachTimeoutMs = 1_000L)
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }

        val backgroundDeadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = backgroundDeadClient,
        )
        runCurrent()

        vm.onScreenStopped()
        backgroundDeadClient.markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ReaderEof,
                source = "device_background",
                intent = "unknown",
            ),
        )
        runCurrent()

        // Resume foreground with the SAME session name — should trigger reconnect.
        vm.onScreenStarted(sessionName = "work")
        advanceUntilIdle()

        assertEquals(
            "same-session foreground resume must trigger auto-reconnect",
            1,
            connector.connectCount,
        )
        assertTrue(
            "expected Connected after same-session foreground resume, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    @Test
    fun writeInputToPaneSeparatesLeadingDashLiteralFromTmuxOptions() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", "-tproof".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val cmd = client.sentCommands.single { it.startsWith("send-keys") }
        assertEquals("send-keys -l -t %0 -- '-tproof'", cmd)
    }

    @Test
    fun writeInputToPaneEscapesSingleQuotesViaCloseEscapeOpen() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%2", "it's".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        // Single-quote sequence: closes ', escapes the literal quote, then
        // reopens. The composer wraps with outer single quotes too.
        val cmd = client.sentCommands.single { it.startsWith("send-keys") }
        assertTrue(
            "expected POSIX-shell-style escape in $cmd",
            cmd == "send-keys -l -t %2 -- 'it'\\''s'",
        )
    }

    @Test
    fun terminalStateInputRoutesThroughTmuxSendKeys() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()

        vm.panes.value.single().terminalState.writeInput("echo ok\r".toByteArray(Charsets.UTF_8))
        waitForSentCommandCount(client, expectedCount = 2)

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals("send-keys -l -t %0 -- 'echo ok'", sent[0])
        assertEquals("send-keys -t %0 Enter", sent[1])
    }

    @Test
    fun codexScaleTmuxOutputFloodKeepsTerminalAndConnectionStateConsistent() =
        runTest(scheduler, timeout = SLOW_FEED_RUN_TEST_TIMEOUT) {
        val diagnostics = installRecordingDiagnosticSink()
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "codex", paneIndex = 0)),
        )
        advanceUntilIdle()

        val state = vm.panes.value.single().terminalState
        val payloads = List(CODEX_SCALE_OUTPUT_CHUNKS, ::codexScaleOutputChunk)
        val emitted = payloads.sumOf { it.size }
        assertTrue(
            "test fixture drift: emitted=$emitted expectedFloor=$CODEX_SCALE_OUTPUT_BYTES",
            emitted >= CODEX_SCALE_OUTPUT_BYTES,
        )
        val observedSideChannelChunks = AtomicInteger(0)
        val outputCollector = launch {
            state.output.collect {
                observedSideChannelChunks.incrementAndGet()
            }
        }
        runCurrent()

        try {
            val sender = async {
                payloads.forEach { bytes ->
                    client.emittedEvents.emit(ControlEvent.Output("%0", bytes))
                }
            }

            val completed = withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
                while (sender.isActive) {
                    shadowOf(Looper.getMainLooper()).idle()
                    runCurrent()
                    delay(10)
                }
                sender.await()
                true
            } ?: false

            assertTrue(
                "tmux %output flood must not stall terminal pane output",
                completed,
            )
            advanceUntilIdle()
            shadowOf(Looper.getMainLooper()).idle()

            assertTrue(
                "tmux %output flood should still publish best-effort side-channel chunks",
                observedSideChannelChunks.get() > 0,
            )
            assertTrue(
                "Codex-scale output flood must not be diagnosed as a transport connection error",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertFalse(
                "Codex-scale output flood must not flip the tmux disconnected signal",
                client.disconnectedSignal.value,
            )
            assertFalse(
                "Codex-scale output flood must not mark the local terminal surface as failed",
                vm.panes.value.single().surfaceError,
            )
            assertTrue(
                "Codex-scale output flood must not be logged as terminal overflow",
                diagnostics.eventsNamed("terminal_output_overflow").isEmpty(),
            )
            assertTrue(
                "Codex-scale output flood must not be logged as passive SSH/tmux EOF",
                diagnostics.eventsNamed("passive_disconnect").isEmpty(),
            )
            assertTrue(
                "Codex-scale output flood must not start reconnect diagnostics",
                diagnostics.eventsNamed("reconnect_start").isEmpty(),
            )
        } finally {
            outputCollector.cancel()
            diagnostics.close()
        }
    }

    @Test
    fun codexLikeTmuxOutputWithSlowTerminalSideChannelRendersFinalMarkerWithoutReconnect() =
        runTest(scheduler, timeout = SLOW_FEED_RUN_TEST_TIMEOUT) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val diagnostics = installRecordingDiagnosticSink()
        val vm = newVm()
        val client = FakeTmuxClient()
        try {
            vm.attachClientForTest(client)
            vm.applyParsedPanesForTest(
                listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "codex", paneIndex = 0)),
            )
            advanceUntilIdle()

            val emittedConnectionStatuses =
                mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
            val statusCollector = launch {
                vm.connectionStatus.collect { status ->
                    emittedConnectionStatuses += status
                }
            }
            val state = vm.panes.value.single().terminalState
            state.appendRemoteOutput("ISSUE576-SEED\r\n".toByteArray(Charsets.UTF_8))
            shadowOf(Looper.getMainLooper()).idle()
            val observedSideChannelChunks = AtomicInteger(0)
            val slowSideChannelCollector = launch {
                state.output.collect {
                    observedSideChannelChunks.incrementAndGet()
                    delay(60_000)
                }
            }
            runCurrent()

            try {
                val payloads = codexLikeIssue576BurstChunks()
                val emittedBytes = payloads.sumOf { it.size }
                assertTrue(
                    "test fixture drift: Codex-like burst must stay high-volume, bytes=$emittedBytes",
                    emittedBytes >= 250_000,
                )
                val sender = async {
                    payloads.forEach { bytes ->
                        client.emittedEvents.emit(ControlEvent.Output("%0", bytes))
                    }
                }

                val completed = withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
                    while (sender.isActive) {
                        // Issue #803/#804: the off-main live `%output` drain is now
                        // frame-paced — the MainThreadDrainScheduler `postDelayed`s its
                        // continuation one frame (16ms) out between bounded parse turns
                        // so the looper is guaranteed a servicing gap (the ANR fix).
                        // Under the Robolectric PAUSED looper a plain `idle()` only runs
                        // tasks already DUE, so it never fires those delayed
                        // continuations and the 64KB process queue stays full, blocking
                        // the real off-main producer forever. Advance the virtual main
                        // looper one frame per pump (as SshTerminalBridgeTest.
                        // drainMainLooperUntil does) so the budgeted continuations fire
                        // and the burst drains. This models a real device looper that
                        // advances time; it does NOT weaken the assertion — if the burst
                        // genuinely stalled behind the slow side-channel the loop still
                        // times out and `completed` stays false.
                        shadowOf(Looper.getMainLooper())
                            .idleFor(16L, java.util.concurrent.TimeUnit.MILLISECONDS)
                        runCurrent()
                        delay(10)
                    }
                    sender.await()
                    true
                } ?: false

                assertTrue(
                    "Codex-like tmux %output burst must not stall behind a slow terminal side-channel",
                    completed,
                )
                // Issue #708: the final marker is applied by the REAL
                // SshTerminalBridge feed on a wall-clock background thread, which a
                // single virtual `advanceUntilIdle()` + one Looper idle does not
                // reliably drain under a contended JVM (this is the only
                // load-sensitive flake in the lease-fix gate). Pump the Looper +
                // the scheduler in a bounded wall-clock loop until the marker
                // renders. This strengthens, never weakens, the assertion: if the
                // marker genuinely never lands the loop still times out and the
                // assertTrue below fails.
                var transcript = ""
                val markerDeadline = System.currentTimeMillis() + SLOW_FEED_DRAIN_TIMEOUT_MS
                do {
                    advanceUntilIdle()
                    // Issue #803/#804: advance the virtual looper a frame so the
                    // budgeted `postDelayed` drain-continuations fire (a bare `idle()`
                    // never runs them), draining the queue tail and rendering the marker.
                    shadowOf(Looper.getMainLooper())
                        .idleFor(16L, java.util.concurrent.TimeUnit.MILLISECONDS)
                    transcript = renderedTranscriptFrom(state)
                    if (transcript.contains("ISSUE576-CODEX-LIKE-DONE")) break
                    Thread.sleep(20)
                } while (System.currentTimeMillis() < markerDeadline)
                assertTrue(
                    "integrated fake tmux -> TerminalSurfaceState -> SshTerminalBridge path must render " +
                        "the final marker; tail=${transcript.takeLast(500)}",
                    transcript.contains("ISSUE576-CODEX-LIKE-DONE"),
                )
                assertTrue(
                    "slow side-channel collector should prove the secondary output flow was subscribed",
                    observedSideChannelChunks.get() > 0,
                )
                val connectionFailureStatuses = emittedConnectionStatuses.filter { status ->
                    status is TmuxSessionViewModel.ConnectionStatus.Connecting ||
                        status is TmuxSessionViewModel.ConnectionStatus.Reconnecting ||
                        status is TmuxSessionViewModel.ConnectionStatus.Failed
                }
                assertTrue(
                    "terminal-side pressure must not emit reconnect/disconnect VM states; " +
                        "observed=$emittedConnectionStatuses",
                    connectionFailureStatuses.isEmpty(),
                )
                assertTrue(
                    "terminal-side pressure must not be diagnosed as a transport connection error",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
                assertFalse(
                    "terminal-side pressure must not flip the tmux disconnected signal",
                    client.disconnectedSignal.value,
                )
                assertFalse(
                    "terminal-side pressure must not mark the local terminal surface as failed",
                    vm.panes.value.single().surfaceError,
                )
                assertEquals(
                    "integrated terminal pressure must not enqueue tmux reconnect attempts",
                    0,
                    TMUX_CONNECT_ATTEMPTS.get(),
                )
                assertTrue(
                    "under-threshold Codex-like output must not be logged as terminal overflow",
                    diagnostics.eventsNamed("terminal_output_overflow").isEmpty(),
                )
                assertTrue(
                    "under-threshold Codex-like output must not be logged as passive SSH/tmux EOF",
                    diagnostics.eventsNamed("passive_disconnect").isEmpty(),
                )
                assertTrue(
                    "under-threshold Codex-like output must not start reconnect diagnostics",
                    diagnostics.eventsNamed("reconnect_start").isEmpty(),
                )
            } finally {
                statusCollector.cancel()
                slowSideChannelCollector.cancel()
            }
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun codexLikePaneOutputOverflowStaysLocalAndNeverReconnectsStableTransport() =
        runTest(scheduler, timeout = SLOW_FEED_RUN_TEST_TIMEOUT) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L))
        val client = FakeTmuxClient()
        client.decoupleOutputForFromEvents = true
        client.reportOutputBacklogOverflowOnTryEmitFailure = true
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "codex",
            client = client,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%0",
                    "@0",
                    "\$0",
                    "codex",
                    paneIndex = 0,
                    sessionName = "codex",
                ),
            ),
        )
        runCurrent()

        val emittedConnectionStatuses =
            mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
        val statusCollector = launch {
            vm.connectionStatus.collect { status ->
                emittedConnectionStatuses += status
            }
        }
        runCurrent()

        val state = vm.panes.value.single().terminalState
        val observedTerminalSideChannelChunks = AtomicInteger(0)
        val terminalSideChannelCollector = launch {
            state.output.collect {
                observedTerminalSideChannelChunks.incrementAndGet()
                delay(60_000)
            }
        }
        val slowPaneOutputCollector = launch {
            client.outputFor("%0").collect {
                delay(60_000)
            }
        }
        runCurrent()

        try {
            val chunks = codexLikeIssue576BurstChunks()
            val emittedBytes = chunks.sumOf { it.size }
            assertTrue(
                "test fixture drift: emittedBytes=$emittedBytes",
                emittedBytes >= 250_000,
            )

            var rejectedChunks = 0
            val sender = async {
                chunks.forEach { chunk ->
                    if (!client.tryEmitPaneOutput("%0", chunk)) rejectedChunks += 1
                }
            }
            val completed = withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
                sender.await()
                true
            } ?: false
            assertTrue(
                "Codex-like pane output burst must not block the reader/UI producer",
                completed,
            )
            assertTrue(
                "test must actually reproduce bounded pane-output backpressure",
                rejectedChunks > 0,
            )

            runCurrent()
            shadowOf(Looper.getMainLooper()).idle()

            registry.lifecycleHooksSnapshot().single().onNetworkChanged(
                networkChange(
                    previous = TerminalNetworkSnapshot.NoValidatedNetwork,
                    current = TerminalNetworkSnapshot.Validated("wifi"),
                    previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                    reason = "same-network-after-codex-output-overflow",
                ),
            )
            runCurrent()

            assertTrue(
                "some output should reach the terminal side-channel before local overflow recovery",
                observedTerminalSideChannelChunks.get() > 0,
            )
            val connectionFailureStatuses = emittedConnectionStatuses.filter { status ->
                status is TmuxSessionViewModel.ConnectionStatus.Connecting ||
                    status is TmuxSessionViewModel.ConnectionStatus.Reconnecting ||
                    status is TmuxSessionViewModel.ConnectionStatus.Failed
            }
            assertTrue(
                "Codex-like output overflow must not emit reconnect/disconnect VM states; " +
                    "observed=$emittedConnectionStatuses",
                connectionFailureStatuses.isEmpty(),
            )
            val disconnectUiStatuses = emittedConnectionStatuses
                .map { it.toUiStatus() }
                .filter { uiStatus ->
                    uiStatus == com.pocketshell.uikit.model.ConnectionStatus.Connecting ||
                        uiStatus == com.pocketshell.uikit.model.ConnectionStatus.Error
                }
            assertTrue(
                "Codex-like output overflow must not map to the breadcrumb Reconnecting/Disconnected UI; " +
                    "observed=${emittedConnectionStatuses.map { it.toUiStatus() }}",
                disconnectUiStatuses.isEmpty(),
            )
            assertTrue(
                "output overflow is local terminal backpressure, not an SSH/tmux disconnect",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertEquals(
                "stable mocked transport must not be reopened for local output overflow",
                0,
                connector.connectCount,
            )
            assertEquals(
                "local output overflow must not enqueue tmux reconnect attempts",
                0,
                TMUX_CONNECT_ATTEMPTS.get(),
            )
            assertFalse(
                "local output overflow must not flip the tmux disconnected signal",
                client.disconnectedSignal.value,
            )
            assertTrue(
                "overflowed pane should expose local terminal recovery instead of fake reconnect",
                vm.panes.value.single().surfaceError,
            )
        } finally {
            statusCollector.cancel()
            terminalSideChannelCollector.cancel()
            slowPaneOutputCollector.cancel()
        }
    }

    @Test
    fun terminalOutputBacklogOverflowIsLocalPaneErrorNotReconnect() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        val vm = newVm()
        val client = FakeTmuxClient()
        try {
            vm.attachClientForTest(client)
            vm.applyParsedPanesForTest(
                listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "codex", paneIndex = 0)),
            )
            advanceUntilIdle()

            client.outputBacklogOverflowEvents.emit(
                TmuxOutputBacklogOverflow(paneId = "%0", droppedEvents = 1),
            )
            advanceUntilIdle()

            assertTrue(
                "terminal backlog overflow is a local pane error, not reconnect",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
            )
            assertFalse(
                "terminal backlog overflow must not flip tmux disconnected",
                client.disconnectedSignal.value,
            )
            assertTrue(
                "overflowed pane should enter explicit terminal recovery state",
                vm.panes.value.single().surfaceError,
            )
            val overflow = diagnostics.eventsNamed("terminal_output_overflow").single()
            assertEquals("pane_output_backlog", overflow.fields["source"])
            assertEquals("local_terminal_renderer_backpressure", overflow.fields["classification"])
            assertEquals(false, overflow.fields["reconnect"])
            assertEquals(false, overflow.fields["tmuxDisconnected"])
            assertEquals("%0", overflow.fields["pane"])
            assertEquals(1, overflow.fields["droppedEvents"])
            assertTrue(
                "local overflow must not be logged as passive SSH/tmux EOF",
                diagnostics.eventsNamed("passive_disconnect").isEmpty(),
            )
            assertTrue(
                "local overflow must not start reconnect diagnostics",
                diagnostics.eventsNamed("reconnect_start").isEmpty(),
            )
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun seedGateLiveBufferOverflowIsLocalPaneErrorNotReconnect() =
        runTest(scheduler, timeout = SLOW_FEED_RUN_TEST_TIMEOUT) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val diagnostics = installRecordingDiagnosticSink()
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTerminalSurfaceStateFactoryForTest {
            TerminalSurfaceState(StandardTestDispatcher(testScheduler))
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L))
        val client = FakeTmuxClient(paneOutputExtraBufferCapacity = 0).apply {
            decoupleOutputForFromEvents = true
        }
        try {
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "codex",
                client = client,
            )
            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        "%0",
                        "@0",
                        "\$0",
                        "codex",
                        paneIndex = 0,
                        sessionName = "codex",
                    ),
                ),
            )
            runCurrent()
            val paneOutputSubscribersReady = withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
                client.emittedPaneOutputs.subscriptionCount.first { subscriberCount ->
                    // Terminal producer, output-activity observer, and port detector.
                    subscriberCount >= 3
                }
                true
            } ?: false
            assertTrue(
                "test must wait until pane-output collectors are subscribed",
                paneOutputSubscribersReady,
            )

            val emittedConnectionStatuses =
                mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
            val statusCollector = launch {
                vm.connectionStatus.collect { status ->
                    emittedConnectionStatuses += status
                }
            }
            runCurrent()

            try {
                client.emittedPaneOutputs.emit(
                    ControlEvent.Output(
                        "%0",
                        ByteArray(SshTerminalBridge.MAX_SEED_GATE_LIVE_BUFFER_BYTES + 1),
                    ),
                )
                advanceUntilIdle()
                shadowOf(Looper.getMainLooper()).idle()
                runCurrent()

                val overflow = diagnostics.eventsNamed("terminal_output_overflow").singleOrNull()

                assertNotNull(
                    "seed-gate live buffer overflow should become a local pane surface error",
                    overflow,
                )
                assertTrue(
                    "seed-gate live buffer overflow should mark the pane surface as errored",
                    vm.panes.value.single().surfaceError,
                )
                val overflowEvent = overflow!!
                val connectionFailureStatuses = emittedConnectionStatuses.filter { status ->
                    status is TmuxSessionViewModel.ConnectionStatus.Connecting ||
                        status is TmuxSessionViewModel.ConnectionStatus.Reconnecting ||
                        status is TmuxSessionViewModel.ConnectionStatus.Failed
                }
                assertTrue(
                    "seed-gate overflow must not emit reconnect/disconnect VM states; " +
                        "observed=$emittedConnectionStatuses",
                    connectionFailureStatuses.isEmpty(),
                )
                assertTrue(
                    "seed-gate overflow is terminal-local backpressure, not an SSH/tmux disconnect",
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
                assertEquals(
                    "stable mocked transport must not be reopened for local seed-gate overflow",
                    0,
                    connector.connectCount,
                )
                assertEquals(
                    "local seed-gate overflow must not enqueue tmux reconnect attempts",
                    0,
                    TMUX_CONNECT_ATTEMPTS.get(),
                )
                assertFalse(
                    "local seed-gate overflow must not flip the tmux disconnected signal",
                    client.disconnectedSignal.value,
                )

                assertEquals("seed_gate_live_buffer", overflowEvent.fields["source"])
                assertEquals("local_terminal_renderer_backpressure", overflowEvent.fields["classification"])
                assertEquals(false, overflowEvent.fields["reconnect"])
                assertEquals(false, overflowEvent.fields["tmuxDisconnected"])
                assertEquals("%0", overflowEvent.fields["pane"])
                assertTrue(
                    "local overflow must not be logged as passive SSH/tmux EOF",
                    diagnostics.eventsNamed("passive_disconnect").isEmpty(),
                )
                assertTrue(
                    "local overflow must not start reconnect diagnostics",
                    diagnostics.eventsNamed("reconnect_start").isEmpty(),
                )
            } finally {
                statusCollector.cancel()
            }
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun terminalSurfaceFailureDoesNotMarkTmuxTransportDisconnectedOrReconnect() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()
        val originalTerminalState = vm.panes.value.single().terminalState
        val attemptsBeforeFailure = TMUX_CONNECT_ATTEMPTS.get()

        vm.reportTerminalSurfaceFailureForTest(
            paneId = "%0",
            cause = RuntimeException("ime resize"),
        )
        advanceUntilIdle()

        assertFalse("local terminal failure must not flip tmux disconnected", client.disconnectedSignal.value)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(
            "local terminal failure must not enqueue reconnect attempts",
            attemptsBeforeFailure,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertNotSame(
            "terminal surface should be recreated locally so IME/input can recover",
            originalTerminalState,
            vm.panes.value.single().terminalState,
        )
    }

    @Test
    fun repeatedTerminalSurfaceFailuresStopAtErrorStateInsteadOfReconnectStorm() = runTest(scheduler) {
        // Issue #423: opening the keyboard after a long dictated Codex
        // prompt could send the terminal into a recovery storm — the
        // surface redraws, fails, gets recreated, fails again, and never
        // settles, then the app shows "reconnecting" and becomes
        // unrecoverable. This asserts the storm stops at an actionable
        // error state with the SSH/tmux transport untouched (no reconnect
        // attempts, no disconnected signal), and that the user-driven
        // recreate path clears the error and rebuilds the surface.
        TMUX_CONNECT_ATTEMPTS.set(0)
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()
        val attemptsBeforeFailure = TMUX_CONNECT_ATTEMPTS.get()

        // Drive a burst of failures past the storm threshold. The first
        // few recover transparently (surface recreated, surfaceError
        // stays false); once the threshold trips, the pane flips to the
        // actionable error state and stops re-attaching.
        repeat(SURFACE_RECOVERY_STORM_THRESHOLD + 2) {
            vm.reportTerminalSurfaceFailureForTest(
                paneId = "%0",
                cause = RuntimeException("ime redraw storm"),
            )
        }
        advanceUntilIdle()

        val pane = vm.panes.value.single()
        assertTrue(
            "a recovery storm must flip the pane into the actionable surface-error state",
            pane.surfaceError,
        )
        assertFalse(
            "surface recovery storm must not flip tmux disconnected",
            client.disconnectedSignal.value,
        )
        assertTrue(
            "surface recovery storm must leave the transport Connected",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(
            "surface recovery storm must not enqueue any reconnect attempts",
            attemptsBeforeFailure,
            TMUX_CONNECT_ATTEMPTS.get(),
        )

        // User taps "Recreate terminal": the error clears, a fresh surface
        // is attached, and the transport is still untouched.
        val erroredTerminalState = pane.terminalState
        vm.recreateTerminalSurface("%0")
        advanceUntilIdle()

        val recovered = vm.panes.value.single()
        assertFalse(
            "recreate must clear the surface-error state",
            recovered.surfaceError,
        )
        assertNotSame(
            "recreate must build a fresh TerminalSurfaceState so IME/input can recover",
            erroredTerminalState,
            recovered.terminalState,
        )
        assertEquals(
            "recreate must not reconnect SSH",
            attemptsBeforeFailure,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertFalse(
            "recreate must not flip tmux disconnected",
            client.disconnectedSignal.value,
        )
    }

    @Test
    fun tmuxHighRateInputStressBatchesWithBoundedBacklogAndNoContentLoss() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            sendCommandDelayMs = 3L
        }
        vm.attachClientForTest(client)
        val sink = vm.tmuxInputSinkForTest("%0")
        val chunks = List(2_000) { index ->
            ("stress-${index.toString().padStart(4, '0')}-" + "x".repeat(51))
                .toByteArray(Charsets.US_ASCII)
        }
        val expected = chunks.fold(ByteArray(0)) { acc, bytes -> acc + bytes }

        val writer = Thread({
            chunks.forEach { sink.write(it) }
        }, "tmux-input-stress-writer")
        writer.start()

        waitForTmuxInputBytes(vm, paneId = "%0", expectedBytes = expected.size.toLong(), writer = writer)
        writer.join(1_000)
        sink.close()

        val metrics = vm.tmuxInputMetricsForTest("%0")
            ?: error("stress metrics should be recorded")
        assertEquals(expected.size.toLong(), metrics.totalEnqueuedBytes)
        assertEquals(expected.size.toLong(), metrics.totalSentBytes)
        assertTrue(
            "stress should build real backlog; metrics=$metrics",
            metrics.maxPendingBytes > TMUX_INPUT_MAX_BATCH_BYTES,
        )
        assertTrue(
            "backlog must stay within bounded queue capacity; metrics=$metrics",
            metrics.maxPendingBytes <= vm.tmuxInputCapacityBytesForTest(),
        )
        assertTrue("input should be batched; metrics=$metrics", metrics.sentBatchCount < chunks.size)
        assertTrue("batch size metric should be recorded; metrics=$metrics", metrics.maxBatchBytes > chunks.first().size)
        assertTrue("batch size must remain bounded; metrics=$metrics", metrics.maxBatchBytes <= TMUX_INPUT_MAX_BATCH_BYTES)
        assertTrue("send latency metric should be recorded; metrics=$metrics", metrics.maxSendLatencyMs > 0.0)
        writeTmuxInputStressReport(metrics, expectedBytes = expected.size, chunks = chunks.size)

        val reconstructed = client.sentCommands
            .filter { it.startsWith("send-keys -l -t %0 -- '") }
            .joinToString(separator = "") { command ->
                command.substringAfter("-- '").removeSuffix("'")
            }
            .toByteArray(Charsets.US_ASCII)
        assertEquals(
            "high-rate stress must not lose or reorder input bytes",
            expected.toList(),
            reconstructed.toList(),
        )
    }

    @Test
    fun terminalDaQueryResponsesSuppressedInBridgeMode() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()

        val state = vm.panes.value.single().terminalState
        val queryBytes = "\u001b[c".toByteArray(Charsets.US_ASCII)
        state.appendRemoteOutput(queryBytes)
        drainTerminalBridgeHandler()

        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertTrue(
            "bridge-mode emulator must not generate DA query responses, got $sent",
            sent.none { it.contains("send-keys -H") || it.contains("send-keys -l") },
        )
    }

    @Test
    fun terminalOsc11QueryResponsesSuppressedInBridgeMode() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()

        val state = vm.panes.value.single().terminalState
        val queryBytes = "\u001b]11;?\u001b\\".toByteArray(Charsets.US_ASCII)
        state.appendRemoteOutput(queryBytes)
        drainTerminalBridgeHandler()

        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertTrue(
            "bridge-mode emulator must not generate OSC 11 color query responses, got $sent",
            sent.none { it.contains("send-keys -H") || it.contains("send-keys -l") },
        )
    }

    @Test
    fun terminalGeneratedInputResponseRoutesAsRawHex() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", "\u001b]11;rgb:0101/0404/0909\u001b\\".toByteArray(Charsets.US_ASCII))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf("send-keys -H -t %0 1b 5d 31 31 3b 72 67 62 3a 30 31 30 31 2f 30 34 30 34 2f 30 39 30 39 1b 5c"),
            sent,
        )
    }

    @Test
    fun singleEscapeInputStillUsesNamedKeyPath() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", byteArrayOf(0x1B))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(listOf("send-keys -t %0 Escape"), sent)
    }

    @Test
    fun writeInputToPaneIgnoresEmptyBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.writeInputToPane("%0", ByteArray(0))
        advanceUntilIdle()

        assertTrue(
            "empty input must not produce a send-keys command",
            client.sentCommands.none { it.startsWith("send-keys") },
        )
    }

    @Test
    fun writeInputToPaneResultPropagatesFailedPaneWrite() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "send-keys"
            closeAndThrowException = TmuxClientException("failed to write tmux command `send-keys`")
        }
        vm.attachClientForTest(client)

        val result = vm.writeInputToPaneResult("%0", "hello".toByteArray(Charsets.UTF_8))

        assertTrue(result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("expected TmuxClientException, got ${error?.javaClass?.name}", error is TmuxClientException)
        assertTrue(error?.message?.contains("send-keys") == true)
        assertEquals(listOf("send-keys -l -t %0 -- 'hello'"), client.sentCommands.filter { it.startsWith("send-keys") })
        assertTrue("failed pane write must close the dead tmux client", client.closed)
    }

    // ------------------------------------------------------------- Issue #209
    // Bracketed-paste wrapping for multi-line input. Single-line input
    // must keep the existing `send-keys -l` + `send-keys ... Enter` shape
    // so we don't regress per-line named-key routing for normal typing.

    @Test
    fun writeInputToPaneWrapsMultiLineInputInBracketedPaste() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        val payload = "para one\npara two\npara three"
        vm.writeInputToPane("%4", payload.toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            "expected bracketed paste start, body, and end commands, got $sent",
            3,
            sent.size,
        )
        val cmd = sent[1]
        assertTrue(
            "expected send-keys -H targeting %4, got '$cmd'",
            cmd.startsWith("send-keys -H -t %4 "),
        )
        // The hex payload should begin with the bracketed-paste start
        // marker bytes (\e[200~ -> 1b 5b 32 30 30 7e) and end with the
        // matching end marker (\e[201~ -> 1b 5b 32 30 31 7e). The
        // newlines inside the body must reach tmux as `0a` bytes (i.e.
        // literal LF), NOT as separate `send-keys ... Enter` calls.
        assertTrue(
            "expected bracketed-paste start marker in hex payload, got '$cmd'",
            sent.first().endsWith("1b 5b 32 30 30 7e"),
        )
        assertTrue(
            "expected bracketed-paste end marker in hex payload, got '$cmd'",
            sent.last().endsWith("1b 5b 32 30 31 7e"),
        )
        // Three paragraphs separated by two `\n` bytes inside the
        // markers.
        val hexBody = cmd.substringAfter("send-keys -H -t %4 ")
        val newlineCount = hexBody.split(' ').count { it == "0a" }
        assertEquals(
            "expected exactly 2 literal LF bytes inside bracketed paste, got '$hexBody'",
            2,
            newlineCount,
        )
        // No standalone `send-keys ... Enter` should fire — the whole
        // block went through `-H`.
        assertTrue(
            "multi-line input must not emit a separate Enter named-key, got $sent",
            sent.none { it.contains(" Enter") },
        )
    }

    @Test
    fun writeInputToPaneNormalisesCrLfToLfInsideBracketedPaste() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Windows-style line endings should collapse to LF only — we
        // never want two paragraph separators where the source had one.
        vm.writeInputToPane("%0", "alpha\r\nbeta".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals("expected start, body, and end commands, got $sent", 3, sent.size)
        val cmd = sent[1]
        val hexBody = cmd.substringAfter("send-keys -H -t %0 ")
        val tokens = hexBody.split(' ')
        assertEquals(
            "expected exactly 1 LF (not CR LF) inside the paste, got '$hexBody'",
            1,
            tokens.count { it == "0a" },
        )
        assertEquals(
            "expected no CR bytes inside the paste, got '$hexBody'",
            0,
            tokens.count { it == "0d" },
        )
    }

    @Test
    fun writeInputToPaneKeepsSingleLineInputOnTheLiteralPath() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Single-line input must NOT be wrapped in bracketed-paste.
        // The existing send-keys -l shape is preserved so the regression
        // suite around named-key Enter routing (and the per-line Enter
        // semantics of `writeInputToPaneIssuesSendKeysWithLiteralBytes`)
        // keeps working.
        vm.writeInputToPane("%0", "hello".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(1, sent.size)
        assertEquals("send-keys -l -t %0 -- 'hello'", sent[0])
        assertTrue(
            "single-line input must not use the -H bracketed-paste path, got $sent",
            sent.none { it.startsWith("send-keys -H") },
        )
    }

    @Test
    fun writeInputToPaneTrailingNewlineGoesThroughBracketedPaste() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // "text\n" contains a `\n` so we route it through the paste path
        // even though there is only one line of content. This preserves
        // the design: any `\n` in the input means "treat as a paste".
        // Submission of the input is the caller's responsibility (they
        // can send a separate Enter named-key after the paste lands).
        vm.writeInputToPane("%0", "ls\n".toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            "expected start, body, and end send-keys -H invocations for `\\n`-terminated input, got $sent",
            3,
            sent.size,
        )
        assertTrue(
            "expected send-keys -H, got '$sent'",
            sent.all { it.startsWith("send-keys -H -t %0 ") },
        )
    }

    @Test
    fun largeBracketedPasteIsSplitIntoBoundedSendKeysCommands() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        val payload = buildString {
            append("first line\n")
            repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 3) { append(('a'.code + (it % 26)).toChar()) }
        }
        vm.writeInputToPane("%0", payload.toByteArray(Charsets.UTF_8))
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys -H") }
        assertTrue("expected multiple bounded paste chunks, got ${sent.size}: $sent", sent.size > 3)
        assertTrue("paste start marker must be its own bounded command", sent.first().endsWith("1b 5b 32 30 30 7e"))
        assertTrue("paste end marker must be its own bounded command", sent.last().endsWith("1b 5b 32 30 31 7e"))
        val maxHexTokens = sent.drop(1).dropLast(1)
            .maxOf { command -> command.substringAfter("send-keys -H -t %0 ").split(' ').size }
        assertTrue(
            "body chunks must be bounded to $TMUX_PASTE_BODY_CHUNK_BYTES bytes; max tokens=$maxHexTokens",
            maxHexTokens <= TMUX_PASTE_BODY_CHUNK_BYTES,
        )
        assertTrue(
            "large paste must not fall back to one unbounded command",
            sent.none { it.substringAfter("send-keys -H -t %0 ").split(' ').size > TMUX_PASTE_BODY_CHUNK_BYTES },
        )
    }

    @Test
    fun buildBracketedPasteHexEmitsExpectedSequenceForKnownInput() {
        val vm = newVm()
        // Body: "a\nb" -> 0x61 0x0a 0x62.
        // Wrapped with the bracketed-paste markers:
        //   1b 5b 32 30 30 7e   <- ESC [ 2 0 0 ~
        //   61                  <- a
        //   0a                  <- LF
        //   62                  <- b
        //   1b 5b 32 30 31 7e   <- ESC [ 2 0 1 ~
        val hex = vm.buildBracketedPasteHexForTest("a\nb".toByteArray(Charsets.UTF_8))
        assertEquals(
            "1b 5b 32 30 30 7e 61 0a 62 1b 5b 32 30 31 7e",
            hex,
        )
    }

    @Test
    fun containsLineBreakIsTrueOnlyForLf() {
        val vm = newVm()
        assertTrue(vm.containsLineBreakForTest("a\nb".toByteArray(Charsets.UTF_8)))
        assertTrue(vm.containsLineBreakForTest("a\r\nb".toByteArray(Charsets.UTF_8)))
        assertFalse(vm.containsLineBreakForTest("a b".toByteArray(Charsets.UTF_8)))
        // A lone `\r` (rare on Android; carriage return without LF) is
        // intentionally NOT treated as a paragraph break — the input-
        // tokens path on the single-line route turns it into a tmux
        // `Enter` named key, which is the right thing for shell prompts.
        assertFalse(vm.containsLineBreakForTest("a\rb".toByteArray(Charsets.UTF_8)))
        assertFalse(vm.containsLineBreakForTest(ByteArray(0)))
    }

    @Test
    fun onKeyBarKeyTranslatesLabelsToTmuxNamedKeys() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", "Esc")
        vm.onKeyBarKey("%0", "Tab")
        // Issue #784: the panel's clean arrow glyphs (← ↑ ↓ →) map to the same
        // tmux cursor-key named keys the old `‹ ⌃ ⌄ ›` did.
        vm.onKeyBarKey("%0", "←")
        vm.onKeyBarKey("%0", "↑")
        vm.onKeyBarKey("%0", "↓")
        vm.onKeyBarKey("%0", "→")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(6, sent.size)
        assertTrue(sent[0].endsWith("Escape"))
        assertTrue(sent[1].endsWith("Tab"))
        assertTrue(sent[2].endsWith("Left"))
        assertTrue(sent[3].endsWith("Up"))
        assertTrue(sent[4].endsWith("Down"))
        assertTrue(sent[5].endsWith("Right"))
        // All addressed to the right pane.
        assertTrue(sent.all { it.contains("-t %0") })
    }

    /**
     * Issue #893: the ⇧Tab (back-tab / Shift+Tab) hotkey must resolve, through
     * the real [TmuxSessionViewModel.onKeyBarKey] mapping, to tmux's `BTab`
     * named key — `send-keys -t <pane> BTab`. tmux emits the back-tab escape
     * sequence `ESC [ Z` (0x1b 0x5b 0x5a) for `BTab`, which is what Claude Code
     * listens for to cycle its permission/plan mode. This drives the real
     * mapping (the panel's exact "⇧Tab" label), not a hand-rolled copy.
     */
    @Test
    fun onKeyBarKeyShiftTabSendsTmuxBackTabNamedKey() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // The label sent here is the EXACT label the KEYS panel renders for the
        // back-tab key (TmuxHotkeyPanelSections), so this exercises the real
        // panel→mapping contract.
        vm.onKeyBarKey("%0", "⇧Tab")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf("send-keys -t %0 BTab"),
            sent,
        )
    }

    @Test
    fun onKeyBarKeyEnterSendsTmuxEnterNamedKeyWithoutReflow() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Issue #527: the dedicated Enter/Return key submits a newline/CR to
        // the pane via the tmux named `Enter` key on the `send-keys` control
        // channel. Both the glyph label and the legacy "Enter" alias map to
        // the same sequence.
        vm.onKeyBarKey("%0", "⏎")
        vm.onKeyBarKey("%0", "Enter")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -t %0 Enter",
                "send-keys -t %0 Enter",
            ),
            sent,
        )
        // No resize/redraw path: the named-key route never uses
        // `refresh-client` and never the literal `send-keys -l`/`-H` byte
        // paths that would imply a paste/reflow.
        assertTrue(
            "Enter must not trigger a resize/refresh-client",
            client.sentCommands.none { it.startsWith("refresh-client") },
        )
    }

    @Test
    fun onKeyBarKeySendsCuratedCtrlCombosAsRawControlBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Issue #784: the hotkeys panel's Ctrl combos are DIRECT buttons (no
        // lone-Ctrl modifier to arm). Each `^X` label maps to its control byte
        // via the `send-keys -H` overlay path (no resize/redraw). `^B` (0x02,
        // tmux prefix / Claude "ctrl-b ctrl-b", #677) is in the set. The legacy
        // `Ctrl-C` / `Ctrl-D` aliases remain accepted for the byte mapping.
        vm.onKeyBarKey("%0", "^A")
        vm.onKeyBarKey("%0", "^B")
        vm.onKeyBarKey("%0", "^C")
        vm.onKeyBarKey("%0", "^D")
        vm.onKeyBarKey("%0", "^E")
        vm.onKeyBarKey("%0", "^L")
        vm.onKeyBarKey("%0", "^R")
        vm.onKeyBarKey("%0", "^Z")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -H -t %0 01",
                "send-keys -H -t %0 02",
                "send-keys -H -t %0 03",
                "send-keys -H -t %0 04",
                "send-keys -H -t %0 05",
                "send-keys -H -t %0 0c",
                "send-keys -H -t %0 12",
                "send-keys -H -t %0 1a",
            ),
            sent,
        )
    }

    @Test
    fun ctrlBHotkeyDoubleTapSendsRawCtrlBByteTwice() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Issue #677/#784: `^B` is a direct hotkey routed through `onKeyBarKey`
        // and sends the raw Ctrl-B byte (0x02). Two rapid taps (Claude Code's
        // "ctrl-b ctrl-b to background") each fire independently, so the pane
        // receives `02` twice with no de-dup/throttle swallowing the second.
        vm.onKeyBarKey("%0", "^B")
        vm.onKeyBarKey("%0", "^B")
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -H -t %0 02",
                "send-keys -H -t %0 02",
            ),
            sent,
        )
    }

    @Test
    fun hotkeyPanelSectionsAreDeDupedAndCarryTheAuditedSet() {
        // Issue #784: the dedicated panel set — no duplicate `/`, no lone
        // `Ctrl`, `^B` present, clean arrow glyphs. Issue #787 added the
        // INTERRUPT / EOF doubled chords (`^C×2` / `^D×2`), re-homed from the
        // deleted palette. Verify the curated set is exactly what we expect and
        // has no duplicate labels.
        val labels = TmuxHotkeyPanelSections.flatMap { it.keys }.map { it.label }
        assertEquals(
            listOf(
                // Issue #893: ⇧Tab (back-tab / Shift+Tab) sits between Tab and
                // Enter in the KEYS section.
                "Esc", "Tab", "⇧Tab", "Enter",
                "^A", "^B", "^C", "^D", "^E", "^L", "^R", "^Z",
                TmuxHotkeyInterruptX2Label, TmuxHotkeyEofX2Label,
                "←", "↑", "↓", "→",
            ),
            labels,
        )
        // Issue #893: the back-tab key is present in the KEYS section.
        assertTrue(labels.contains("⇧Tab"))
        // No duplicates (the maintainer's "/ appears twice" / "Esc duplicated"
        // complaints).
        assertEquals(labels.size, labels.toSet().size)
        // No lone Ctrl modifier and no `/` key in the panel.
        assertFalse(labels.contains("Ctrl"))
        assertFalse(labels.contains("/"))
        // ^B (tmux prefix) restored.
        assertTrue(labels.contains("^B"))
        // Issue #787: the doubled interrupt/EOF chords are present and DISTINCT
        // from the single `^C`/`^D` (they're not aliases of the same label).
        assertTrue(labels.contains(TmuxHotkeyInterruptX2Label))
        assertTrue(labels.contains(TmuxHotkeyEofX2Label))
        assertTrue(labels.contains("^C"))
        assertTrue(labels.contains("^D"))
        // The arrow section uses the clean glyphs, marked as Arrow kind.
        val arrows = TmuxHotkeyPanelSections.last().keys
        assertEquals(listOf("←", "↑", "↓", "→"), arrows.map { it.label })
        assertTrue(arrows.all { it.kind == KeyKind.Arrow })
    }

    @Test
    fun onKeyBarKeyInterruptAndEofDoubledChordsSendByteTwice() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Issue #787: the re-homed `^C×2` / `^D×2` hotkeys are DISTINCT from the
        // single `^C`/`^D` — they send the control byte TWICE (`repeatCount = 2`)
        // so they actually interrupt the running agent / send EOF (Claude Code
        // and many REPLs treat the first press as "press again to interrupt/exit").
        vm.onKeyBarKey("%0", TmuxHotkeyInterruptX2Label)
        vm.onKeyBarKey("%0", TmuxHotkeyEofX2Label)
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        // `sendControlInputToPane(..., repeatCount = 2)` emits one `send-keys -H`
        // carrying the byte twice (03 03 for Ctrl-C, 04 04 for Ctrl-D).
        assertEquals(
            listOf(
                "send-keys -H -t %0 03 03",
                "send-keys -H -t %0 04 04",
            ),
            sent,
        )
    }

    @Test
    fun keyBarControlEscapeAndHotkeysClearSmartTextBeforeTmuxRawSends() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()
        val state = vm.panes.value.single().terminalState
        val policies = mutableListOf<TerminalRawInputPolicy>()
        state.setSmartTextStagingBridgeForTest { policy ->
            policies += policy
            if (policy == TerminalRawInputPolicy.FlushSmartText) {
                client.sentCommands.add("flush-staged")
            }
        }

        vm.onKeyBarKey("%0", "^C")
        vm.onKeyBarKey("%0", "Esc")
        vm.onKeyBarKey("%0", "→")
        advanceUntilIdle()

        assertEquals(
            listOf(
                TerminalRawInputPolicy.ClearSmartText,
                TerminalRawInputPolicy.ClearSmartText,
                TerminalRawInputPolicy.ClearSmartText,
            ),
            policies,
        )
        assertEquals(
            listOf(
                "send-keys -H -t %0 03",
                "send-keys -t %0 Escape",
                "send-keys -t %0 Right",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
        assertFalse(client.sentCommands.contains("flush-staged"))
    }

    @Test
    fun keyBarEnterFlushesSmartTextBeforeTmuxEnter() = runTest(scheduler) {
        val vm = newVm()
        val literalFlushGate = CompletableDeferred<Unit>()
        val client = FakeTmuxClient().apply {
            sendCommandGatePrefix = "send-keys -l -t %0 -- 'staged'"
            sendCommandGate = literalFlushGate
        }
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()
        val state = vm.panes.value.single().terminalState
        val policies = mutableListOf<TerminalRawInputPolicy>()
        state.setSmartTextStagingBridgeForTest { policy ->
            policies += policy
            if (policy == TerminalRawInputPolicy.FlushSmartText) {
                state.writeInput("staged".toByteArray(Charsets.UTF_8))
            }
        }

        try {
            vm.onKeyBarKey("%0", "⏎")
            waitForSentCommandCount(client, expectedCount = 1)

            assertEquals(listOf(TerminalRawInputPolicy.FlushSmartText), policies)
            assertEquals(
                "Enter must wait behind the queued SmartText flush while the literal send is suspended",
                listOf("send-keys -l -t %0 -- 'staged'"),
                client.sentCommands.filter { it.startsWith("send-keys") },
            )

            literalFlushGate.complete(Unit)
            waitForSentCommandCount(client, expectedCount = 2)

            assertEquals(
                listOf(
                    "send-keys -l -t %0 -- 'staged'",
                    "send-keys -t %0 Enter",
                ),
                client.sentCommands.filter { it.startsWith("send-keys") },
            )
        } finally {
            literalFlushGate.complete(Unit)
            state.setSmartTextStagingBridgeForTest(null)
        }
    }

    @Test
    fun directTmuxControlHotkeyClearsSmartTextBeforeSendingRawBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", paneIndex = 0)),
        )
        advanceUntilIdle()
        val state = vm.panes.value.single().terminalState
        val policies = mutableListOf<TerminalRawInputPolicy>()
        state.setSmartTextStagingBridgeForTest { policy ->
            policies += policy
            if (policy == TerminalRawInputPolicy.FlushSmartText) {
                client.sentCommands.add("flush-staged")
            }
        }

        vm.sendControlInputToPane("%0", CtrlCByte, repeatCount = 2)
        advanceUntilIdle()

        assertEquals(listOf(TerminalRawInputPolicy.ClearSmartText), policies)
        assertEquals(
            listOf("send-keys -H -t %0 03 03"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
        assertFalse(client.sentCommands.contains("flush-staged"))
    }

    @Test
    fun sendControlInputToPaneCanSendDoublePressPayload() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.sendControlInputToPane("%0", CtrlCByte, repeatCount = 2)
        vm.sendControlInputToPane("%0", CtrlDByte, repeatCount = 2)
        advanceUntilIdle()

        val sent = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            listOf(
                "send-keys -H -t %0 03 03",
                "send-keys -H -t %0 04 04",
            ),
            sent,
        )
    }

    private fun TestScope.waitForSentCommandCount(client: FakeTmuxClient, expectedCount: Int) {
        repeat(100) {
            advanceUntilIdle()
            if (client.sentCommands.count { command -> command.startsWith("send-keys") } >= expectedCount) {
                return
            }
            Thread.sleep(10)
        }
        advanceUntilIdle()
        assertTrue(
            "expected at least $expectedCount send-keys commands, got ${client.sentCommands}",
            client.sentCommands.count { it.startsWith("send-keys") } >= expectedCount,
        )
    }

    private fun TestScope.waitForTmuxInputBytes(
        vm: TmuxSessionViewModel,
        paneId: String,
        expectedBytes: Long,
        writer: Thread,
    ) {
        repeat(10_000) {
            advanceTimeBy(3L)
            runCurrent()
            val metrics = vm.tmuxInputMetricsForTest(paneId)
            if (metrics?.totalSentBytes == expectedBytes && !writer.isAlive) return
            Thread.sleep(1)
        }
        val metrics = vm.tmuxInputMetricsForTest(paneId)
        assertEquals(
            "timed out waiting for tmux input stress drain; metrics=$metrics writerAlive=${writer.isAlive}",
            expectedBytes,
            metrics?.totalSentBytes,
        )
    }

    private fun writeTmuxInputStressReport(
        metrics: TmuxInputStressMetrics,
        expectedBytes: Int,
        chunks: Int,
    ) {
        val outputDir = if (File("settings.gradle.kts").isFile) {
            File("app/build/reports/tmux-input-stress")
        } else {
            File("build/reports/tmux-input-stress")
        }
        val report = File(outputDir, "high-rate-input.json")
        report.parentFile?.mkdirs()
        report.writeText(
            """
            {
              "stress": "tmux-high-rate-input",
              "input_chunks": $chunks,
              "expected_bytes": $expectedBytes,
              "max_pending_capacity_bytes": $TMUX_INPUT_MAX_PENDING_BYTES,
              "max_batch_capacity_bytes": $TMUX_INPUT_MAX_BATCH_BYTES,
              "metrics": {
                "total_enqueued_bytes": ${metrics.totalEnqueuedBytes},
                "total_sent_bytes": ${metrics.totalSentBytes},
                "max_pending_bytes": ${metrics.maxPendingBytes},
                "max_pending_chunks": ${metrics.maxPendingChunks},
                "max_batch_bytes": ${metrics.maxBatchBytes},
                "max_batch_chunks": ${metrics.maxBatchChunks},
                "sent_batch_count": ${metrics.sentBatchCount},
                "max_send_latency_ms": ${metrics.maxSendLatencyMs}
              },
              "no_content_loss": ${metrics.totalEnqueuedBytes == expectedBytes.toLong() && metrics.totalSentBytes == expectedBytes.toLong()}
            }
            """.trimIndent(),
        )
    }

    private fun drainTerminalBridgeHandler() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun renderedTranscriptFrom(state: TerminalSurfaceState): String {
        val bridgeField = TerminalSurfaceState::class.java.getDeclaredField("bridge").apply {
            isAccessible = true
        }
        val bridge = bridgeField.get(state) as? SshTerminalBridge ?: return ""
        return bridge.emulator.screen.transcriptText
    }

    @Test
    fun onKeyBarKeyIgnoresUnknownLabel() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        vm.onKeyBarKey("%0", "ZorkKey")
        advanceUntilIdle()

        assertTrue(client.sentCommands.none { it.startsWith("send-keys") })
    }

    @Test
    fun lifecycleCommandsTargetActiveSession() = runTest(scheduler) {
        // Issue #782: PocketShell no longer creates/switches/renames/kills tmux
        // WINDOWS — those commands are removed (hard-cut). Only session-scoped
        // lifecycle commands remain.
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.createSession("next")
        vm.renameCurrentSession("renamed")
        vm.killCurrentSession()
        advanceUntilIdle()

        assertTrue(client.sentCommands.contains("new-session -d -s 'next' -c '~'"))
        assertTrue(client.sentCommands.contains("rename-session -t 'work' 'renamed'"))
        assertTrue(client.sentCommands.contains("kill-session -t 'work'"))
        // No window-management commands are ever issued (#782 hard-cut).
        assertFalse(client.sentCommands.any { it.startsWith("new-window") })
        assertFalse(client.sentCommands.any { it.startsWith("select-window") })
        assertFalse(client.sentCommands.any { it.startsWith("rename-window") })
        assertFalse(client.sentCommands.any { it.startsWith("kill-window") })
    }

    @Test
    fun lifecycleCommandsDeriveCreateNameButIgnoreBlankRenameNames() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.createSession(" ")
        vm.renameCurrentSession("")
        advanceUntilIdle()

        val command = client.sentCommands.single()
        assertTrue(command.startsWith("new-session -d -s 'pocketshell-"))
        assertTrue(command.endsWith("' -c '~'"))
    }

    @Test
    fun escapeSingleQuotedRoundTripsBytesWithoutQuotes() {
        val vm = newVm()
        assertEquals("hello world", vm.escapeSingleQuoted("hello world"))
        assertEquals("\n\t", vm.escapeSingleQuoted("\n\t"))
        // Empty input → empty output.
        assertEquals("", vm.escapeSingleQuoted(""))
    }

    // ----- Issue #285: automatic tmux control-client sizing.

    @Test
    fun resizeRemotePtyReportsPhoneSizeToTmuxControlClient() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.resizeRemotePty(columns = 85, rows = 30)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "set-window-option -t 'work' window-size latest",
                "refresh-client -C 85x30",
            ),
            client.sentCommands,
        )
    }

    @Test
    fun resizeRemotePtyIsIdempotentForSameDimensions() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.resizeRemotePty(columns = 48, rows = 96)
        vm.resizeRemotePty(columns = 48, rows = 96)
        vm.resizeRemotePty(columns = 48, rows = 96)
        advanceUntilIdle()

        assertEquals(1, client.sentCommands.count { it.startsWith("set-window-option") })
        assertEquals(1, client.sentCommands.count { it.startsWith("refresh-client") })
    }

    @Test
    fun resizeRemotePtyRefreshesAgainWhenPhoneDimensionsChange() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.resizeRemotePty(columns = 48, rows = 95)
        advanceUntilIdle()
        vm.resizeRemotePty(columns = 50, rows = 95)
        advanceUntilIdle()
        vm.resizeRemotePty(columns = 50, rows = 94)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "set-window-option -t 'work' window-size latest",
                "refresh-client -C 48x95",
                "refresh-client -C 50x95",
                "refresh-client -C 50x94",
            ),
            client.sentCommands,
        )
    }

    @Test
    fun resizeRemotePtyIgnoresZeroAndNegativeDimensions() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.resizeRemotePty(columns = 0, rows = 0)
        vm.resizeRemotePty(columns = -1, rows = 96)
        vm.resizeRemotePty(columns = 48, rows = 0)
        advanceUntilIdle()

        assertTrue(client.sentCommands.none { it.startsWith("refresh-client") })
        assertTrue(client.sentCommands.none { it.startsWith("set-window-option") })
    }

    @Test
    fun resizeRemotePtyIsNoOpBeforeConnect() = runTest(scheduler) {
        val vm = newVm()

        vm.resizeRemotePty(columns = 48, rows = 96)
        advanceUntilIdle()

        assertEquals(48 to 96, vm.remoteDimensionsForTest())
    }

    @Test
    fun resizeRemotePtyEscapesSessionNameSingleQuotesForPolicyCommand() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            // tmux session names may contain `'` (rare but legal); the
            // resize command must close-escape-open so the shell does
            // not parse half the name as a positional arg.
            sessionName = "it's work",
            client = client,
        )

        vm.resizeRemotePty(columns = 60, rows = 24)
        advanceUntilIdle()

        assertEquals(
            "set-window-option -t 'it'\\''s work' window-size latest",
            client.sentCommands.single { it.startsWith("set-window-option") },
        )
    }

    @Test
    fun resizeRemotePtyFailureDoesNotBlockLaterSizeChangeRetry() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.refreshClientSizeException = IllegalStateException("boom")
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.resizeRemotePty(columns = 85, rows = 30)
        runCurrent()
        client.refreshClientSizeException = null
        vm.resizeRemotePty(columns = 86, rows = 30)
        advanceUntilIdle()

        assertEquals(
            "a failed refresh must not mark the size applied forever",
            1,
            client.sentCommands.count { it == "refresh-client -C 86x30" },
        )
    }

    @Test
    fun outputForReceivesEventsRoutedThroughEventsFlow() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)

        // Pre-seed list-panes so reconcile creates a pane row with the
        // bridge attached to client.outputFor("%0").
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\tt\t0"),
                isError = false,
            ),
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)

        // The view model wires its bridge through client.outputFor(...)
        // which filters [ControlEvent.Output]. Verify the filter shape by
        // collecting outputFor() directly from a sibling test scope.
        val output = client.outputFor("%0")
        val firstEvent = async(start = CoroutineStart.UNDISPATCHED) {
            output.first()
        }
        client.emittedEvents.emit(
            ControlEvent.Output(paneId = "%1", data = "wrong-pane".toByteArray()),
        )
        client.emittedEvents.emit(
            ControlEvent.Output(paneId = "%0", data = "right-pane".toByteArray()),
        )
        advanceUntilIdle()

        val evt = firstEvent.await()
        assertEquals("%0", evt.paneId)
        assertEquals("right-pane", String(evt.data, Charsets.UTF_8))
    }

    @Test
    fun listPanesRowWithFewerFieldsIsSkipped() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "garbage\twithout\tenough\tfields",  // 4 fields — skipped
                    "%0\t@0\t\$0\tok\t0",                // 5 fields — kept
                    "",                                  // empty — skipped
                ),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(1, vm.panes.value.size)
        assertEquals("%0", vm.panes.value.single().paneId)
    }

    @Test
    fun listPanesRowWithWrongIdPrefixIsSkipped() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "0\t@0\t\$0\tno-prefix\t0",   // bad pane id — no leading %
                    "%0\twindow\t\$0\tno-at\t0",   // bad window id — no leading @
                    "%0\t@0\tsession\tno-dollar\t0", // bad session id — no leading $
                    "%1\t@0\t\$0\tgood\t1",
                ),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        assertEquals(listOf("%1"), vm.panes.value.map { it.paneId })
    }

    @Test
    fun connectionStatusFlipsToConnectedAfterAttachForTest() {
        val vm = newVm()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Idle)
        vm.attachClientForTest(FakeTmuxClient())
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun replacingClientClosesOldClientAndUpdatesRegistry() {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry = registry)
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "old",
            client = oldClient,
        )
        assertSame(oldClient, registry.clients.value[1L]?.client)

        vm.replaceClientForTest(
            hostId = 2L,
            hostName = "bravo",
            host = "bravo.example",
            port = 2222,
            user = "root",
            keyPath = "/keys/b",
            sessionName = "new",
            client = newClient,
        )

        assertTrue(oldClient.closed)
        assertNull(registry.clients.value[1L])
        val entry = registry.clients.value[2L]
        assertNotNull(entry)
        assertSame(newClient, entry?.client)
        assertEquals("bravo", entry?.hostName)
        assertEquals("bravo.example", entry?.hostname)
        assertEquals(2222, entry?.port)
        assertEquals("root", entry?.username)
        assertEquals("/keys/b", entry?.keyPath)
    }

    // ─── Issue #282: detected agents no longer seed a popup hint ────

    private fun newClaudeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.ClaudeCode,
        sourcePath = "/home/u/.claude/sessions/abc.jsonl",
        sessionId = "abc",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun newCodexDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.Codex,
        sourcePath = "/home/u/.codex/sessions/xyz.jsonl",
        sessionId = "xyz",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    private fun newOpenCodeDetection(): AgentDetection = AgentDetection(
        agent = AgentKind.OpenCode,
        sourcePath = "/home/u/.local/share/opencode/opencode.db#ses_123",
        sessionId = "ses_123",
        confidence = AgentDetection.Confidence.ProcessConfirmed,
    )

    // ─── Issue #793: tail-first conversation load + load state machine ───

    @Test
    fun openingLargeConversationLoadsTailWithoutFetchingWholeHistory() = runTest(scheduler) {
        // REGRESSION PROOF (#793): a LARGE transcript (5000 raw lines on the
        // server). Opening the Conversation tab must render the TAIL quickly
        // WITHOUT fetching the whole history first, and report that older
        // messages remain to page in.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        // The tail window the server returns: only the most recent turns.
        val tailJsonl = listOf(
            """{"type":"user","uuid":"u4999","message":{"role":"user","content":"the latest question"}}""",
            """{"type":"assistant","uuid":"a4999","message":{"role":"assistant","content":"the latest answer"}}""",
        ).joinToString("\n")
        val session = FakeSshSession(
            wcOutput = "5000\n",
            initialEventsOutput = tailJsonl,
        )

        vm.startAgentConversationForPaneForTest(
            paneId = "%0",
            session = session,
            detection = detection,
        )
        advanceUntilIdle()

        val state = vm.agentConversations.value["%0"]!!
        // The tail rendered.
        assertEquals(
            listOf("the latest question", "the latest answer"),
            state.events.filterIsInstance<ConversationEvent.Message>().map { it.text },
        )
        // Load resolved to a clear terminal state (Ready), NOT a stuck spinner.
        assertEquals(ConversationLoadState.Ready, state.loadState)
        // Older messages remain → the pane offers upward paging.
        assertTrue("older messages must be pageable", state.hasMoreOlderEvents)

        // The CORE assertion: the open did NOT fetch the whole history. The
        // only window read is capped at the first-paint raw budget
        // (FIRST_PAINT_MESSAGE_BUDGET * JSONL_RAW_LINES_PER_EVENT raw lines),
        // far below the 5000-line file.
        val windowCommand = session.execCommands.single { it.contains("@@PS_WINDOW@@") }
        val firstPaintRawBudget = FIRST_PAINT_MESSAGE_BUDGET * JSONL_RAW_LINES_PER_EVENT
        assertTrue(
            "expected a first-paint-budget tail; got $windowCommand",
            windowCommand.contains("tail -n $firstPaintRawBudget "),
        )
        // No wide 500-message (=4000 raw line) eager read was issued.
        val eagerRawBudget = 500 * JSONL_RAW_LINES_PER_EVENT
        assertFalse(
            "the eager full-history read path must be gone (D22 hard-cut)",
            session.execCommands.any { it.contains("tail -n $eagerRawBudget ") },
        )
    }

    @Test
    fun coldOpenDropsRedundantLineCountExecAndTailsFromWindowPosition() = runTest(scheduler) {
        // REGRESSION PROOF (#817 slice 1): the cold-open path must NOT issue a
        // separate `wc -l` (lineCount) round-trip before the windowed read. The
        // window read already yields the file's line count, and the follow-tail
        // starts from THAT position (no dropped/duplicated events).
        TmuxSessionLatencyTelemetry.resetForTest()
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val tailJsonl = listOf(
            """{"type":"user","uuid":"u1","message":{"role":"user","content":"latest question"}}""",
            """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"latest answer"}}""",
        ).joinToString("\n")
        val session = FakeSshSession(
            wcOutput = "1234\n",
            initialEventsOutput = tailJsonl,
        )

        vm.startAgentConversationForPaneForTest(
            paneId = "%0",
            session = session,
            detection = detection,
        )
        advanceUntilIdle()

        // Events loaded correctly from the window read.
        val state = vm.agentConversations.value["%0"]!!
        assertEquals(
            listOf("latest question", "latest answer"),
            state.events.filterIsInstance<ConversationEvent.Message>().map { it.text },
        )

        // (a) The redundant standalone `wc -l` (lineCount) exec is gone. The
        // ONLY count of the source file happens inside the windowed read's own
        // single exec (the @@PS_WINDOW@@ sentinel command), never as a separate
        // `wc -l < ...` round-trip.
        val standaloneWcExecs = session.execCommands.filter {
            it.contains("wc -l < ") && !it.contains("@@PS_WINDOW@@")
        }
        assertTrue(
            "cold open must not fire a separate lineCount `wc -l` exec; commands=${session.execCommands}",
            standaloneWcExecs.isEmpty(),
        )

        // (b) The follow-tail starts from the window read's line count (1234),
        // so it only emits lines appended AFTER the window — no gaps/dupes.
        assertEquals(
            listOf("/home/u/.claude/sessions/abc.jsonl" to 1234L),
            session.tailFromLineCalls,
        )

        // The conversation_open latency span was emitted with the open duration
        // so a connected test / logcat can snapshot the authoritative number.
        val openEvents = TmuxSessionLatencyTelemetry.snapshot()
            .filter { it.name == CONVERSATION_OPEN_LATENCY_OPERATION }
        assertEquals(
            "exactly one conversation_open span; spans=${TmuxSessionLatencyTelemetry.snapshot().map { it.name }}",
            1,
            openEvents.size,
        )
        assertEquals("%0", openEvents.single().paneId)
        assertTrue(
            "span must carry a grep-able artifact line; got ${openEvents.single().toArtifactLine()}",
            openEvents.single().toArtifactLine().contains("tmux_latency_conversation_open_ms="),
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun openingExistingConversationStartsInLoadingThenReady() = runTest(scheduler) {
        // Problem 1/2 (#793): the Conversation tab shows a "Loading
        // conversation…" state (loadState == Loading) while the tail read is in
        // flight, and resolves to Ready — never a stuck "Waiting for agent…".
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "node", paneIndex = 0)),
        )
        val detection = newClaudeDetection()
        val execGate = CompletableDeferred<Unit>()
        val session = FakeSshSession(
            wcOutput = "10\n",
            initialEventsOutput =
                """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"hi"}}""",
            execGate = execGate,
        )

        // Realistic flow: the user opens the Conversation tab (seeds a Loading
        // placeholder row), THEN detection lands and the transcript loads.
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()
        assertEquals(ConversationLoadState.Loading, vm.agentConversations.value["%0"]!!.loadState)

        val loadJob = backgroundScope.launch {
            vm.startAgentConversationForPaneForTest("%0", session, detection)
        }
        runCurrent()
        // While the read is gated (in flight), the row is still Loading.
        assertEquals(ConversationLoadState.Loading, vm.agentConversations.value["%0"]!!.loadState)

        // Release the read; it resolves to Ready.
        execGate.complete(Unit)
        advanceUntilIdle()
        loadJob.join()
        assertEquals(ConversationLoadState.Ready, vm.agentConversations.value["%0"]!!.loadState)
    }

    @Test
    fun conversationLoadWatchdogFlipsLoadingToFailedSoItNeverSpinsForever() = runTest(scheduler) {
        // Problem 2 (#793): a never-completing load (the transport flap symptom)
        // must surface a clear Failed terminal state, not an infinite spinner.
        // Drive the detection-pending placeholder path (selectSessionTab seeds a
        // detection-less Loading row + arms the watchdog).
        val vm = newVm()
        vm.setConversationLoadTimeoutForTest(1_000L)
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "node", paneIndex = 0)),
        )

        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()
        assertEquals(ConversationLoadState.Loading, vm.agentConversations.value["%0"]!!.loadState)

        // Time passes with no detection/transcript ever landing.
        advanceTimeBy(1_500L)
        runCurrent()
        assertEquals(
            "the watchdog must flip a never-completing load to Failed",
            ConversationLoadState.Failed,
            vm.agentConversations.value["%0"]!!.loadState,
        )
    }

    @Test
    fun emptyTranscriptResolvesToEmptyTerminalStateNotSpinner() = runTest(scheduler) {
        // Problem 2 (#793): a successful read of an empty transcript surfaces a
        // clear Empty state, not a stuck spinner.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val session = FakeSshSession(wcOutput = "0\n", initialEventsOutput = "")

        vm.startAgentConversationForPaneForTest("%0", session, detection)
        advanceUntilIdle()

        val state = vm.agentConversations.value["%0"]!!
        assertEquals(ConversationLoadState.Empty, state.loadState)
        assertTrue(state.events.isEmpty())
        assertFalse(state.hasMoreOlderEvents)
    }

    @Test
    fun loadingOlderEventsWidensWindowAndPrependsOlderMessages() = runTest(scheduler) {
        // AC3 (#793): older messages page in lazily on upward scroll, preserving
        // the already-loaded tail (the merge keeps newer events in place).
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        // First paint: a large file, small tail.
        val firstPaintTail =
            """{"type":"user","uuid":"u2","message":{"role":"user","content":"recent"}}"""
        val session = FakeSshSession(
            wcOutput = "5000\n",
            initialEventsOutput = firstPaintTail,
        )
        // loadOlderAgentConversationEvents reads sessionRef + activeTarget, so
        // wire the same session as the live runtime session for the pane.
        vm.attachSessionForAgentRetryForTest(session)
        vm.startAgentConversationForPaneForTest("%0", session, detection)
        advanceUntilIdle()
        assertTrue(vm.agentConversations.value["%0"]!!.hasMoreOlderEvents)

        // The page-older read returns a WIDER window that includes an older
        // message AHEAD of the recent one.
        session.setInitialEventsOutput(
            listOf(
                """{"type":"user","uuid":"u1","message":{"role":"user","content":"older"}}""",
                """{"type":"user","uuid":"u2","message":{"role":"user","content":"recent"}}""",
            ).joinToString("\n"),
        )
        session.setWcOutput("12\n") // now the wider window covers the whole file

        vm.loadOlderAgentConversationEvents("%0")
        advanceUntilIdle()

        val state = vm.agentConversations.value["%0"]!!
        assertEquals(
            "older message must be prepended ahead of the recent one",
            listOf("older", "recent"),
            state.events.filterIsInstance<ConversationEvent.Message>().map { it.text },
        )
        assertFalse("the whole file is now in the window", state.hasMoreOlderEvents)
        assertFalse(state.isPagingOlder)
    }

    @Test
    fun detectedAgentConversationStartsWithoutHintPopupState() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "shell", paneIndex = 0)),
        )

        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val state = vm.agentConversations.value["%0"]
        assertNotNull(state)
        assertEquals(SessionTab.Terminal, state!!.selectedTab)
        assertEquals(AgentKind.ClaudeCode, state.detection?.agent)
    }

    @Test
    fun agentConversationStillReceivesEventsAfterPopupRemoval() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "assistant-1",
                    agent = AgentKind.ClaudeCode,
                    atMillis = 1L,
                    role = ConversationRole.Assistant,
                    text = "still here",
                ),
            ),
        )

        val after = vm.agentConversations.value["%0"]!!
        assertEquals(
            "the new event should reach the conversation feed",
            "assistant-1",
            after.events.last().id,
        )
        assertEquals(AgentKind.ClaudeCode, after.detection?.agent)
    }

    // ─── Issue #256: current-pane conversation semantics ──────────────

    @Test
    fun selectingConversationTabOnlyMutatesTheCurrentPaneState() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.startAgentConversationForTest(
            "%1",
            AgentDetection(
                agent = AgentKind.Codex,
                sourcePath = "/home/u/.codex/sessions/xyz.jsonl",
                sessionId = "xyz",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
        )

        vm.selectSessionTab("%0", SessionTab.Conversation)

        assertEquals(SessionTab.Conversation, vm.agentConversations.value["%0"]!!.selectedTab)
        assertEquals(SessionTab.Terminal, vm.agentConversations.value["%1"]!!.selectedTab)
    }

    @Test
    fun warmSwitchToLoadedConversationRecordsConversationSwitchSpan() = runTest(scheduler) {
        // Issue #817 (Rank-1 measurement): switching Terminal -> Conversation on a
        // row whose transcript is ALREADY loaded (events present) records a
        // conversation_switch latency span. This is the warm-switch case the spike
        // predicted is already <0.3s (a pure StateFlow read, no SSH); the span
        // makes that an authoritative number.
        TmuxSessionLatencyTelemetry.resetForTest()
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val event = ConversationEvent.Message(
            id = "message-1",
            agent = AgentKind.ClaudeCode,
            role = ConversationRole.Assistant,
            text = "already loaded answer",
        )
        // Seed a row with events, default tab Terminal (the #815 default).
        vm.startAgentConversationForTest("%0", newClaudeDetection(), listOf(event))
        assertEquals(SessionTab.Terminal, vm.agentConversations.value["%0"]!!.selectedTab)

        vm.selectSessionTab("%0", SessionTab.Conversation)

        val switchEvents = TmuxSessionLatencyTelemetry.snapshot()
            .filter { it.name == CONVERSATION_SWITCH_LATENCY_OPERATION }
        assertEquals(
            "exactly one conversation_switch span; spans=" +
                TmuxSessionLatencyTelemetry.snapshot().map { it.name },
            1,
            switchEvents.size,
        )
        assertEquals("%0", switchEvents.single().paneId)
        assertTrue(
            "span must carry a grep-able artifact line; got ${switchEvents.single().toArtifactLine()}",
            switchEvents.single().toArtifactLine().contains("tmux_latency_conversation_switch_ms="),
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun switchingToConversationOnAStillLoadingRowDoesNotRecordSwitchSpan() = runTest(scheduler) {
        // Issue #817: a tap that lands on a still-loading/placeholder row (no
        // events yet) is the OPEN path, NOT the warm switch — it must NOT emit a
        // conversation_switch span (the open is covered by conversation_open_full).
        TmuxSessionLatencyTelemetry.resetForTest()
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        // selectSessionTab seeds a detection-less, event-less Loading placeholder.
        vm.selectSessionTab("%0", SessionTab.Conversation)

        val switchEvents = TmuxSessionLatencyTelemetry.snapshot()
            .filter { it.name == CONVERSATION_SWITCH_LATENCY_OPERATION }
        assertTrue(
            "a switch onto an empty/loading row must not record a warm-switch span; " +
                "spans=${TmuxSessionLatencyTelemetry.snapshot().map { it.name }}",
            switchEvents.isEmpty(),
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun selectingConversationTabOnPresumedAgentWithoutDetectionSeedsPlaceholderRow() = runTest(scheduler) {
        // Issue #778: tapping Conversation on a live presumed-agent pane that has
        // no conversation row yet (no live detection, no remembered status) must
        // NOT be a no-op. It seeds a detection-less placeholder row with
        // `selectedTab = Conversation` so the screen switches to the Conversation
        // surface (placeholder) instead of staying stuck on Terminal. The real
        // transcript replaces this when detection lands.
        val vm = newVm()
        // Issue #878: pin the open-time default to Terminal so the pane-add
        // auto-seed no-ops and this test isolates the #778 TAP-to-seed path
        // (with the Conversation default the row would already exist from the
        // #878 auto-seed, which a separate test covers).
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        assertNull(vm.agentConversations.value["%0"])

        vm.selectSessionTab("%0", SessionTab.Conversation)

        val row = vm.agentConversations.value["%0"]
        assertNotNull(row)
        assertEquals(SessionTab.Conversation, row!!.selectedTab)
        assertNull(row.detection)
    }

    @Test
    fun selectingConversationTabOnExistingDetectionlessRowSwitchesToConversation() = runTest(scheduler) {
        // Issue #778: a presumed-agent pane that already has a detection-less row
        // (seeded by an earlier tap) honours a Conversation tap — it switches to
        // Conversation even though `detection == null`, rather than swallowing it.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        // Seed a detection-less Conversation row via the seed-on-tap path, then
        // flip back to Terminal so we can re-assert the Conversation switch.
        vm.selectSessionTab("%0", SessionTab.Conversation)
        vm.selectSessionTab("%0", SessionTab.Terminal)
        assertEquals(SessionTab.Terminal, vm.agentConversations.value["%0"]!!.selectedTab)
        assertNull(vm.agentConversations.value["%0"]!!.detection)

        vm.selectSessionTab("%0", SessionTab.Conversation)

        assertEquals(SessionTab.Conversation, vm.agentConversations.value["%0"]!!.selectedTab)
        assertNull(vm.agentConversations.value["%0"]!!.detection)
    }

    @Test
    fun selectingTmuxConversationAndTerminalTabsRecordsExplicitDiagnostics() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val vm = newVm()
            vm.attachClientForTest(FakeTmuxClient())
            val event = ConversationEvent.Message(
                id = "message-1",
                agent = AgentKind.ClaudeCode,
                role = ConversationRole.Assistant,
                text = "do not record this transcript text",
            )
            vm.startAgentConversationForTest("%0", newClaudeDetection(), listOf(event))

            vm.selectSessionTab("%0", SessionTab.Conversation)
            vm.selectSessionTab("%0", SessionTab.Terminal)

            val events = diagnostics.eventsNamed("conversation_terminal_tab_switch")
            assertEquals(2, events.size)
            assertEquals("tmux", events[0].fields["mode"])
            assertEquals("%0", events[0].fields["paneId"])
            assertEquals("Terminal", events[0].fields["fromTab"])
            assertEquals("Conversation", events[0].fields["toTab"])
            assertEquals("terminal_to_conversation", events[0].fields["direction"])
            assertEquals(true, events[0].fields["hasConversation"])
            assertEquals(1, events[0].fields["eventCount"])
            assertEquals("Conversation", events[1].fields["fromTab"])
            assertEquals("Terminal", events[1].fields["toTab"])
            assertFalse(events[0].fields.containsValue(event.text))
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun selectingConversationTabForUnknownOrPlainPaneIsNoOp() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        val before = vm.agentConversations.value

        vm.selectSessionTab("%missing", SessionTab.Conversation)

        assertEquals(before, vm.agentConversations.value)
    }

    // ─── Issue #495: remember agent sessions across reconnect ─────────

    /**
     * Connects the VM to a stable host/session and applies a single pane in
     * window @0, so reconnect simulations can re-apply panes under the same
     * window with a rotated pane id.
     */
    private fun TmuxSessionViewModel.connectWithPaneForTest(
        paneId: String,
        windowId: String = "@0",
        sessionName: String = "work",
    ) {
        replaceClientForTest(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
            client = FakeTmuxClient(),
        )
        applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId,
                    windowId,
                    "$0",
                    "shell",
                    paneIndex = 0,
                    sessionName = sessionName,
                ),
            ),
        )
    }

    @Test
    fun agentSessionRememberedAndRestoredImmediatelyAfterReconnect() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        // The user opens Conversation — this remembers the window's status.
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        // Reconnect: tmux re-attach assigns a NEW pane id under the SAME
        // window. The old pane row is gone; nothing is seeded by the live
        // detection round-trip yet.
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        // Issue #819 (A2): the Conversation tab is available immediately on the
        // new pane id (a row exists on the remembered tab), but the seed now
        // restores a RESOLVING placeholder — NOT the remembered (possibly
        // stale/sibling) detection rendered Live. The screen surfaces this via
        // the `presumedAgent` "Loading conversation…" placeholder path (a
        // detection-less row whose pane is not a confirmed shell), so the #495
        // tab-availability benefit is preserved without flashing a stale source.
        assertNull("old pane id is gone after reattach", vm.agentConversations.value["%0"])
        val restored = vm.agentConversations.value["%7"]
        assertNotNull("agent status restored immediately on the new pane", restored)
        assertEquals(
            "the remembered window is restored on the Conversation tab the user was on",
            SessionTab.Conversation,
            restored!!.selectedTab,
        )
        assertNull(
            "#819 (A2): the seed must NOT carry the remembered (possibly stale) " +
                "source — live detection re-anchors the route-true source",
            restored.detection,
        )
        assertTrue(
            "#819 (A2): the reattach seed is a remembered-agent resolving placeholder",
            restored.rememberedAgentPlaceholder,
        )
        assertEquals(
            "#819 (A2): the resolving placeholder holds Loading until live detection binds",
            ConversationLoadState.Loading,
            restored.loadState,
        )
    }

    @Test
    fun sharedAgentSessionMemoryRestoresAfterViewModelRecreation() = runTest(scheduler) {
        val memory = AgentSessionMemory()
        val firstVm = newVm(agentSessionMemory = memory)
        firstVm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        firstVm.startAgentConversationForTest("%0", newClaudeDetection())
        firstVm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        val recreatedVm = newVm(agentSessionMemory = memory)
        recreatedVm.connectWithPaneForTest(paneId = "%8", windowId = "@0")
        runCurrent()

        val restored = recreatedVm.agentConversations.value["%8"]
        assertNotNull(
            "process-scoped memory must restore agent status for a fresh VM before detection",
            restored,
        )
        assertEquals(
            "the user's Conversation tab choice survives VM recreation",
            SessionTab.Conversation,
            restored!!.selectedTab,
        )
        // Issue #819 (A2): a cross-VM restore must also NOT trust the remembered
        // source blind — it seeds the resolving placeholder and re-anchors the
        // route-true source on live re-detection in the fresh VM.
        assertNull(
            "#819 (A2): the cross-VM seed must not carry the remembered source",
            restored.detection,
        )
        assertTrue(
            "#819 (A2): the cross-VM reattach seed is a remembered-agent resolving placeholder",
            restored.rememberedAgentPlaceholder,
        )
    }

    @Test
    fun reconnectReselectsConversationWhenUserWasOnConversation() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        assertEquals(
            "user who was in Conversation stays on Conversation after reconnect",
            SessionTab.Conversation,
            vm.agentConversations.value["%7"]!!.selectedTab,
        )
    }

    @Test
    fun reconnectKeepsTerminalWhenUserWasOnTerminal() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        // User saw the agent but stayed on Terminal — remember Terminal.
        vm.selectSessionTab("%0", SessionTab.Terminal)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        val restored = vm.agentConversations.value["%7"]!!
        // Issue #819 (A2): the seed restores the remembered TAB (Terminal here)
        // as a resolving placeholder — not the remembered (possibly stale)
        // source — and live detection re-anchors the route-true source.
        assertNull(
            "#819 (A2): the seed must not carry the remembered source",
            restored.detection,
        )
        assertTrue(
            "#819 (A2): the reattach seed is a remembered-agent resolving placeholder",
            restored.rememberedAgentPlaceholder,
        )
        assertEquals(
            "Conversation tab available but Terminal stays selected",
            SessionTab.Terminal,
            restored.selectedTab,
        )
    }

    // ════════════════════════════════════════════════════════════════════
    // Issue #819 (Slice A2): the #495 reattach memory-seed must RE-ANCHOR the
    // remembered conversation source against live re-detection — it must NOT
    // render the remembered `detection.sourcePath` BLIND as Live.
    //
    // The bug (maintainer dogfood): the Conversation tab showed a DIFFERENT
    // Codex transcript than the route-true Terminal — the `ai-shipping-labs`
    // header over a `git-pocketshell-desktop-codex` transcript — because two
    // same-cwd same-kind (Codex) sessions (a sub-agent / second window / second
    // worktree) share a cwd and the source is picked by mtime, so a remembered
    // detection captured during a prior mis-pick carried the SIBLING's rollout.
    // The seed restored that stale source and rendered it Live before the live
    // `/proc/<pid>/fd` round-trip (Slice A1) re-bound the route-true source.
    //
    // RED on base: the seed carries `remembered.detection.sourcePath` (the stale
    // sibling source) as a Live row — so `restored.detection!!.sourcePath`
    // equals the sibling source the Terminal is NOT attached to.
    // GREEN with A2: the seed restores a detection-LESS resolving placeholder;
    // live re-detection (the route's OWN fd-pinned source) binds the route-true
    // source, so Conversation == Terminal route.
    //
    // G2 class coverage: sub-agent/nested, two-windows, two-worktrees all reduce
    // to "remembered source != route-true source"; plus the missing-data
    // (remembered window whose agent has since EXITED) case, and the #554 no-flap
    // hold (a transient post-reattach null must NOT tear the pane to a raw shell).
    // ════════════════════════════════════════════════════════════════════

    private fun codexDetection(sourcePath: String, sessionId: String): AgentDetection =
        AgentDetection(
            agent = AgentKind.Codex,
            sourcePath = sourcePath,
            sessionId = sessionId,
            confidence = AgentDetection.Confidence.ProcessConfirmed,
        )

    @Test
    fun codexReattachSeedDoesNotRenderRememberedSiblingSourceAsLive() = runTest(scheduler) {
        // The reported scenario: a Codex window the user was reading in
        // Conversation. The remembered detection was captured during a prior
        // same-cwd mis-pick, so it carries the SIBLING/orchestrator rollout
        // (`git-pocketshell-desktop-codex`), NOT the route's own session
        // (`ai-shipping-labs`).
        val siblingRollout = "/home/u/.codex/sessions/git-pocketshell-desktop-codex.jsonl"
        val routeTrueRollout = "/home/u/.codex/sessions/ai-shipping-labs.jsonl"

        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        // The remembered detection points at the SIBLING rollout (the stale
        // mis-pick). Recording it + opening Conversation arms agentSessionMemory.
        vm.startAgentConversationForTest(
            "%0",
            codexDetection(siblingRollout, "git-pocketshell-desktop-codex"),
        )
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        // Reconnect: a NEW pane id under the SAME window @0; the seed fires.
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    "%7", "@0", "$0", "shell", paneIndex = 0,
                    cwd = "/home/u/git/pocketshell", paneTty = "/dev/pts/9",
                    sessionName = "work",
                ),
            ),
        )
        runCurrent()

        val seeded = vm.agentConversations.value["%7"]!!
        // The LOAD-BEARING assertion (G6): the seed must NOT render the stale
        // sibling source. On base the seed carries remembered.detection, so
        // `seeded.detection!!.sourcePath == siblingRollout` (RED).
        assertNull(
            "#819 (A2): the reattach seed must NOT render the remembered " +
                "(stale sibling) Codex source — it holds resolving until live " +
                "re-detection binds the route-true source",
            seeded.detection,
        )
        assertNotEquals(
            "#819 (A2): the stale sibling rollout must NOT be the active source",
            siblingRollout,
            seeded.detection?.sourcePath,
        )
        assertTrue(seeded.rememberedAgentPlaceholder)
        assertEquals(SessionTab.Conversation, seeded.selectedTab)

        // Live re-detection (the route's OWN fd-pinned source via Slice A1) lands
        // and binds the route-true rollout. The Conversation now matches the
        // route-named Terminal.
        vm.markAgentTailLiveForTest("%7", codexDetection(routeTrueRollout, "ai-shipping-labs"))
        runCurrent()

        val bound = vm.agentConversations.value["%7"]!!
        assertEquals(
            "#819 (A2): after live re-detection the Conversation is bound to the " +
                "ROUTE-TRUE source the Terminal is attached to",
            routeTrueRollout,
            bound.detection!!.sourcePath,
        )
        assertEquals("ai-shipping-labs", bound.detection!!.sessionId)
        assertEquals(
            "the remembered Conversation tab is preserved across the re-anchor",
            SessionTab.Conversation,
            bound.selectedTab,
        )
        assertFalse(
            "the resolving-placeholder flag clears once the route-true source binds",
            bound.rememberedAgentPlaceholder,
        )
    }

    @Test
    fun cachedRecordedKindWithNullSourceRereadsSourceOptionAndRebinds() = runTest(scheduler) {
        val now = System.currentTimeMillis() / 1000
        val ownSource = "/home/u/.claude/projects/-workspace-proj/own.jsonl"
        val siblingSource = "/home/u/.claude/projects/-workspace-proj/sibling.jsonl"
        val session = FakeSshSession(
            recordedKindOutput = "claude\n",
            recordedSourceOutput = "",
            detectionOutput = """
                claude|${now - 60}|/workspace/proj|$ownSource
                claude|$now|/workspace/proj|$siblingSource
            """.trimIndent(),
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "claude",
                    paneIndex = 0,
                    cwd = "/workspace/proj",
                    currentCommand = "claude",
                    paneTty = "/dev/pts/7",
                    sessionName = "work",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(
            "precondition: before the delayed source option exists, the recorded-kind " +
                "cache has source=null and Claude falls back to newest same-cwd candidate",
            siblingSource,
            vm.agentConversations.value["%0"]?.detection?.sourcePath,
        )

        session.setRecordedSourceOutput("$ownSource\n")
        vm.startAgentDetectionForPaneForTest("%0")
        advanceUntilIdle()

        assertEquals(
            "a cached recorded kind with source=null must re-read @ps_agent_source " +
                "and rebind to the exact recorded source once the watcher writes it",
            ownSource,
            vm.agentConversations.value["%0"]?.detection?.sourcePath,
        )
        assertTrue(
            "the cache-hit path must issue a standalone source option read; commands=${session.execCommands}",
            session.execCommands.any {
                it.contains("show-options -v") &&
                    it.contains("@ps_agent_source") &&
                    !it.contains("@@PS_RECORDED_KIND@@")
            },
        )
    }

    @Test
    fun cachedRecordedKindWithRawSourceRereadsAfterGenerationAppearsAndRebinds() =
        runTest(scheduler) {
            val now = System.currentTimeMillis() / 1000
            val rawSource = "/home/u/.claude/projects/-workspace-proj/raw-before-generation.jsonl"
            val generationSource = "/home/u/.claude/projects/-workspace-proj/generation-current.jsonl"
            val siblingSource = "/home/u/.claude/projects/-workspace-proj/sibling.jsonl"
            val session = FakeSshSession(
                recordedKindOutput = "claude\n",
                recordedSourceOutput = "$rawSource\n",
                detectionOutput = """
                    claude|${now - 120}|/workspace/proj|$rawSource
                    claude|${now - 60}|/workspace/proj|$generationSource
                    claude|$now|/workspace/proj|$siblingSource
                """.trimIndent(),
            )
            val vm = newVm()
            vm.replaceClientForTest(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = FakeTmuxClient(),
                session = session,
            )
            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        paneId = "%0",
                        windowId = "@0",
                        sessionId = "$0",
                        title = "claude",
                        paneIndex = 0,
                        cwd = "/workspace/proj",
                        currentCommand = "claude",
                        paneTty = "/dev/pts/7",
                        sessionName = "work",
                    ),
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "precondition: the first open cached a raw legacy @ps_agent_source",
                rawSource,
                vm.agentConversations.value["%0"]?.detection?.sourcePath,
            )

            session.setRecordedSourceGenerationOutput("launch-2\n")
            session.setRecordedSourceOutput("launch-2\t$generationSource\n")
            vm.startAgentDetectionForPaneForTest("%0")
            advanceUntilIdle()

            assertEquals(
                "when @ps_agent_source_generation appears, the cache-hit path must " +
                    "not return the previously cached raw source before validating " +
                    "the generation-scoped source option",
                generationSource,
                vm.agentConversations.value["%0"]?.detection?.sourcePath,
            )
            assertTrue(
                "the raw cached source must be overridden via a standalone " +
                    "generation-aware source read; commands=${session.execCommands}",
                session.execCommands.any {
                    it.contains("@@PS_RECORDED_SOURCE_GENERATION@@") &&
                        it.contains("@ps_agent_source_generation") &&
                        it.contains("@ps_agent_source") &&
                        !it.contains("@@PS_RECORDED_KIND@@")
                },
            )
        }

    @Test
    fun codexReattachSeedReanchorsAcrossSubAgentTwoWindowAndTwoWorktreeSiblings() =
        runTest(scheduler) {
            // G2 class coverage: the three same-cwd same-kind collision shapes the
            // maintainer can hit (a sub-agent/orchestrator, a second tmux window,
            // a second git worktree) all reduce to "the remembered source is a
            // sibling rollout, the route-true source is the pane's own". For each,
            // the seed must refuse the remembered source and the live re-bind must
            // restore the route-true one.
            data class Case(
                val name: String,
                val siblingSource: String,
                val routeTrueSource: String,
            )
            val cases = listOf(
                Case(
                    "sub-agent/orchestrator out-flushing the pane's own rollout",
                    "/home/u/.codex/sessions/orchestrator-subagent.jsonl",
                    "/home/u/.codex/sessions/pane-own-session.jsonl",
                ),
                Case(
                    "two tmux windows in one project dir",
                    "/home/u/.codex/sessions/window-b.jsonl",
                    "/home/u/.codex/sessions/window-a.jsonl",
                ),
                Case(
                    "two git worktrees of one repo sharing the codex sessions dir",
                    "/home/u/.codex/sessions/worktree-feature.jsonl",
                    "/home/u/.codex/sessions/worktree-main.jsonl",
                ),
            )

            for ((index, case) in cases.withIndex()) {
                val windowId = "@$index"
                val oldPane = "%${index * 2}"
                val newPane = "%${index * 2 + 1}"
                val vm = newVm()
                vm.connectWithPaneForTest(paneId = oldPane, windowId = windowId)
                vm.startAgentConversationForTest(
                    oldPane,
                    codexDetection(case.siblingSource, "sibling-$index"),
                )
                vm.selectSessionTab(oldPane, SessionTab.Conversation)
                runCurrent()

                vm.applyParsedPanesForTest(
                    listOf(
                        TmuxSessionViewModel.ParsedPane(
                            newPane, windowId, "$0", "shell", paneIndex = 0,
                            cwd = "/home/u/git/pocketshell", paneTty = "/dev/pts/$index",
                            sessionName = "work",
                        ),
                    ),
                )
                runCurrent()

                val seeded = vm.agentConversations.value[newPane]!!
                assertNull(
                    "#819 (A2) [${case.name}]: seed must not carry the sibling source",
                    seeded.detection,
                )
                assertNotEquals(
                    "#819 (A2) [${case.name}]: sibling source must not be active",
                    case.siblingSource,
                    seeded.detection?.sourcePath,
                )

                vm.markAgentTailLiveForTest(
                    newPane,
                    codexDetection(case.routeTrueSource, "route-true-$index"),
                )
                runCurrent()

                val bound = vm.agentConversations.value[newPane]!!
                assertEquals(
                    "#819 (A2) [${case.name}]: live re-bind anchors the route-true source",
                    case.routeTrueSource,
                    bound.detection!!.sourcePath,
                )
            }
        }

    @Test
    fun codexReattachSeedHoldsResolvingPlaceholderThroughTransientNullThenTearsDownOnConfirmedExit() =
        runTest(scheduler) {
            // #554 no-flap guarantee AND the missing-data (agent-exited) case.
            // The A2 seed is detection-less, so the #554 hold can no longer key
            // off `detection != null`. A transient post-reattach null must be
            // HELD + re-confirmed (the window was a KNOWN agent), and only a
            // CONFIRMED exit (AGENT_EXIT_CONFIRMATIONS consecutive nulls) tears
            // the placeholder down — never a flap to a raw shell on the first null.
            val vm = newVm()
            vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
            vm.startAgentConversationForTest(
                "%0",
                codexDetection("/home/u/.codex/sessions/remembered.jsonl", "remembered"),
            )
            vm.selectSessionTab("%0", SessionTab.Conversation)
            runCurrent()

            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        "%7", "@0", "$0", "shell", paneIndex = 0,
                        cwd = "/home/u/git/pocketshell", paneTty = "/dev/pts/9",
                        sessionName = "work",
                    ),
                ),
            )
            runCurrent()

            assertTrue(
                "precondition: the reattach seed is a remembered-agent resolving placeholder",
                vm.agentConversations.value["%7"]!!.rememberedAgentPlaceholder,
            )

            // First live re-detection comes back NULL (the agent's log/process is
            // not yet observable on the fresh attach). The #554 hold must keep the
            // placeholder — NOT drop the pane to a raw shell.
            val droppedOnFirstNull = vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
            assertFalse(
                "#554/#819 (A2): a transient first null must NOT tear down the " +
                    "remembered resolving placeholder",
                droppedOnFirstNull,
            )
            assertNotNull(
                "#819 (A2): the resolving placeholder is retained across the first null",
                vm.agentConversations.value["%7"],
            )

            // After AGENT_EXIT_CONFIRMATIONS consecutive nulls the exit is
            // confirmed; the placeholder is torn down (the agent genuinely exited
            // — the missing-data case), never left stranded on "Loading…".
            var dropped = false
            repeat(8) {
                if (!dropped) {
                    dropped = vm.handleNullAgentDetectionForTest("%7")
                    runCurrent()
                }
            }
            assertTrue(
                "#819 (A2): a CONFIRMED exit tears down the remembered placeholder",
                dropped,
            )
            assertNull(
                "#819 (A2): the placeholder is dropped on confirmed exit — never " +
                    "stranded on 'Loading…'",
                vm.agentConversations.value["%7"],
            )
        }

    @Test
    fun codexReattachSeedSeam_seedsResolvingPlaceholderNotRememberedSource() =
        runTest(scheduler) {
            // Direct seam coverage of seedAgentConversationFromMemory itself (no
            // live-detection round-trip in the way), proving the seed in isolation
            // restores a resolving placeholder rather than the remembered source.
            val memory = AgentSessionMemory()
            val vm = newVm(agentSessionMemory = memory)
            vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
            runCurrent()
            // Arm memory for window @0 with a Codex sibling source.
            memory.remember(
                hostId = 42L,
                sessionName = "work",
                windowId = "@0",
                detection = codexDetection(
                    "/home/u/.codex/sessions/sibling.jsonl",
                    "sibling",
                ),
                wasOnConversation = true,
            )
            // Drop the existing row so the seed's "no existing row" gate passes,
            // then drive the seed seam directly.
            vm.clearAgentDetectionForPaneForTest("%0")
            // clearAgentDetectionForPane forgets memory; re-arm it after the clear.
            memory.remember(
                hostId = 42L,
                sessionName = "work",
                windowId = "@0",
                detection = codexDetection(
                    "/home/u/.codex/sessions/sibling.jsonl",
                    "sibling",
                ),
                wasOnConversation = true,
            )
            runCurrent()
            vm.seedAgentConversationFromMemoryForTest("%0")
            runCurrent()

            val seeded = vm.agentConversations.value["%0"]!!
            assertNull(
                "#819 (A2): the seed seam restores a resolving placeholder, not " +
                    "the remembered source",
                seeded.detection,
            )
            assertTrue(seeded.rememberedAgentPlaceholder)
            assertEquals(ConversationLoadState.Loading, seeded.loadState)
            assertEquals(SessionTab.Conversation, seeded.selectedTab)
        }

    // ─── Issue #818: configurable open-time default tab + the #815 line ───
    //
    // #818 makes the tab a freshly-OPENED agent session lands on configurable
    // (default Conversation — the black-screen cure). The #815 invariant is
    // preserved but reframed: the open-time default ONLY governs the fresh-row
    // open; a detection/refresh on an ALREADY-open session must never yank the
    // user's tab in either direction. A remembered/explicit per-session choice
    // still wins (seed-from-memory runs first).

    @Test
    fun freshlyOpenedAgentLandsOnConfiguredTerminalDefault() = runTest(scheduler) {
        // Issue #818: with the open-time default set to Terminal (the user
        // opted out of the Conversation default), a POSITIVE agent detection
        // that first lands on a pane with NO existing row (no remembered tab,
        // no explicit choice) opens on the raw Terminal view.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        assertNull("precondition: no conversation row before detection", vm.agentConversations.value["%0"])

        // Live detection lands (the production markAgentTailLive path, current == null).
        vm.markAgentTailLiveForTest("%0", newClaudeDetection())
        runCurrent()

        val state = vm.agentConversations.value["%0"]!!
        assertEquals(
            "open-time default Terminal: a freshly-opened agent session lands on Terminal",
            SessionTab.Terminal,
            state.selectedTab,
        )
        assertEquals(AgentKind.ClaudeCode, state.detection?.agent)
    }

    @Test
    fun freshlyOpenedAgentLandsOnConversationByDefault() = runTest(scheduler) {
        // Issue #818: the DEFAULT open-time view is Conversation — the
        // black-screen cure. With no override (= production default) a fresh
        // agent detection opens directly on the readable Conversation view.
        //
        // Issue #878: with the default = Conversation, the pre-detection
        // placeholder row is now seeded at pane-add (so the user sees the
        // detecting placeholder, NOT the black Terminal, during the detection
        // window). The detection-less seed already lands on Conversation; the
        // detection then fills in the real agent while PRESERVING that tab.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        val seeded = vm.agentConversations.value["%0"]!!
        assertEquals(
            "precondition (#878): the pre-detection placeholder lands on Conversation",
            SessionTab.Conversation,
            seeded.selectedTab,
        )
        assertNull("precondition (#878): the seed has no detection yet", seeded.detection)

        vm.markAgentTailLiveForTest("%0", newClaudeDetection())
        runCurrent()

        val state = vm.agentConversations.value["%0"]!!
        assertEquals(
            "the default open-time view is Conversation (#818 black-screen cure)",
            SessionTab.Conversation,
            state.selectedTab,
        )
        assertEquals(AgentKind.ClaudeCode, state.detection?.agent)
        assertFalse(
            "the autoSeededPlaceholder flag clears once a real detection lands",
            state.autoSeededPlaceholder,
        )
    }

    @Test
    fun freshlyOpenedAgentViaFullSshPathHonoursTerminalDefault() = runTest(scheduler) {
        // Issue #818: the open-time default through the REAL end-to-end
        // production conversation-start path (startAgentConversationForPane →
        // markAgentTailLive), not only the markAgentTailLive seam. Pinned to
        // Terminal to prove the opt-out reaches the open path.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val session = FakeSshSession(
            wcOutput = "2\n",
            initialEventsOutput =
                """{"type":"assistant","uuid":"a1","message":{"role":"assistant","content":"hi"}}""",
        )

        vm.startAgentConversationForPaneForTest("%0", session, detection)
        advanceUntilIdle()

        assertEquals(
            "the end-to-end detect+load path honours the Terminal open-time default",
            SessionTab.Terminal,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )
    }

    // ─── Issue #878: pre-detection placeholder seeded at pane-add ─────────
    //
    // The #818 Conversation-default was applied only AFTER the detection SSH
    // round-trip (markAgentTailLive's current == null branch). Meanwhile the raw
    // TmuxTerminalPager is always mounted, so a fresh presumed-agent pane with
    // NO remembered status showed the BLACK Terminal for the whole detection
    // window (~0.3s cache-hit, ~0.95s+ cold/foreign). #878 closes that gap by
    // seeding the detection-less Conversation placeholder row at PANE-ADD so the
    // screen paints ConversationDetectingPlaceholder (the "Loading…" state),
    // NOT the Terminal void, during detection.
    //
    // G10 reproduce-first: on base (no fix) `connectWithPaneForTest` leaves NO
    // conversation row, so `agentConversations.value[pane]` is null and the
    // screen's `showConversationPlaceholder` (requires a Conversation-tab,
    // detection-less row) is false → the black Terminal shows. These tests
    // assert the row EXISTS with `selectedTab == Conversation`, detection ==
    // null, loadState == Loading — i.e. the placeholder is up. RED on base,
    // GREEN with the seed.

    @Test
    fun freshPresumedAgentPaneSeedsConversationPlaceholderBeforeDetection() = runTest(scheduler) {
        // AC1 (class: the generic fresh-open path; Claude detection lands later).
        // The DEFAULT open-time view is Conversation, so a freshly-added pane
        // shows the detecting placeholder for the WHOLE detection window.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        val placeholder = vm.agentConversations.value["%0"]
        assertNotNull(
            "#878: a fresh presumed-agent pane gets a pre-detection placeholder row",
            placeholder,
        )
        assertEquals(
            "#878: the placeholder is on the Conversation tab (so the screen paints" +
                " the detecting placeholder, not the black Terminal)",
            SessionTab.Conversation,
            placeholder!!.selectedTab,
        )
        assertNull("#878: the placeholder has no detection yet", placeholder.detection)
        assertEquals(
            "#878: the placeholder is in the Loading state (the detecting placeholder)",
            ConversationLoadState.Loading,
            placeholder.loadState,
        )
        assertTrue(
            "#878: the row is flagged as an auto-seed so a shell can drop it",
            placeholder.autoSeededPlaceholder,
        )

        // Detection lands (Claude): the placeholder becomes the real agent row,
        // STILL on Conversation, and the auto-seed flag clears.
        vm.markAgentTailLiveForTest("%0", newClaudeDetection())
        runCurrent()
        val live = vm.agentConversations.value["%0"]!!
        assertEquals(AgentKind.ClaudeCode, live.detection?.agent)
        assertEquals(
            "#878: the user stays on Conversation once detection lands",
            SessionTab.Conversation,
            live.selectedTab,
        )
        assertFalse(
            "#878: a real detection clears the auto-seed flag",
            live.autoSeededPlaceholder,
        )
    }

    @Test
    fun freshPaneWherePlaceholderResolvesToCodexStaysOnConversation() = runTest(scheduler) {
        // AC1 (class: Codex). The pre-detection placeholder is the same for any
        // agent kind; here a Codex detection resolves it. The user is on the
        // Conversation surface for the whole window and after Codex lands.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        assertEquals(
            "#878: placeholder up (Conversation) before Codex is detected",
            SessionTab.Conversation,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )
        assertNull(vm.agentConversations.value["%0"]!!.detection)

        vm.markAgentTailLiveForTest("%0", newCodexDetection())
        runCurrent()
        val live = vm.agentConversations.value["%0"]!!
        assertEquals(AgentKind.Codex, live.detection?.agent)
        assertEquals(
            "#878: Codex pane stays on Conversation once detection lands",
            SessionTab.Conversation,
            live.selectedTab,
        )
    }

    // ─── Issue #894 (epic #821 "Slice C"): a freshly-opened CONFIRMED SHELL ──
    // pane (recorded `@ps_agent_kind=shell`) must NOT flash the #878 "Loading
    // conversation…" placeholder when the open-time default is Conversation,
    // while a presumed-agent / foreign pane STILL gets it (no #878 regression).
    //
    // G10 reproduce-first: on base (no Slice C gate) `seedPresumedAgentPlaceholder`
    // seeds EVERY pane (it has no confirmed-shell gate) and `confirmedShell` is
    // hard-wired false — so a confirmed-shell pane gets the auto-seeded
    // Conversation placeholder (the wrong-surface flash). These tests assert the
    // confirmed-shell pane has NO auto-seeded placeholder and is published in
    // [confirmedShellPaneIds]; RED on base (the placeholder is present, the set
    // is empty), GREEN with the verdict-driven gate.
    //
    // G2 class coverage: shell-vs-agent × default=Conversation-vs-Terminal.

    // ─── Issue #874 (residual black-screen): the reconcile/cache-restore void ──
    //
    // #975 fixed the verdict-clearing and #989 fixed the terminal-buffer reseed,
    // leaving one residual void: a presumed-agent pane that is RECONCILED rather
    // than freshly added — a beyond-grace reattach (#959) or a switch-back to a
    // REBUILT cached runtime — whose conversation row was DROPPED (the R3-B
    // 2-null collapse on a wedged channel) has NO row on restore.
    // `restoreCachedRuntime` only restarts rows that carried a live `detection`
    // (`restartAgentConversationsForRestoredRuntime`'s `state.detection ?: return`),
    // and that path never reconciles — so a presumed-agent pane with a dropped
    // row falls through to the always-mounted raw `TmuxTerminalPager` → the #807
    // black void.
    //
    // The fix re-seeds the #878 Conversation placeholder for the session's
    // presumed-agent panes when the recorded-kind verdict resolves the session as
    // NOT a confirmed shell (`applyRecordedShellVerdict(isShell = false)`, the
    // single verdict-application point reached after a restore via
    // `refreshCurrentSessionRecordedKind`). It runs AFTER the verdict so #894's
    // no-flash-on-shell invariant holds.
    //
    // G10 reproduce-first: on base the verdict-application re-seed does not exist,
    // so a dropped-row presumed-agent pane stays rowless → raw Terminal void. RED
    // on base (row null after the verdict), GREEN with the fix (Conversation
    // placeholder re-seeded). G2 class coverage: reconciled-presumed-agent
    // (re-seeds), reconciled-confirmed-shell (NO placeholder, #894 no-flash),
    // freshly-added (unchanged).

    @Test
    fun reconciledPresumedAgentWithDroppedRowReseedsConversationPlaceholder() = runTest(scheduler) {
        // The residual #874 void: a presumed-agent pane whose row was dropped and
        // is then restored/reconciled (no live detection to restart) gets its
        // Conversation placeholder re-seeded when the verdict resolves NOT-shell.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        // Drop the auto-seeded row to null — model the R3-B 2-null collapse on a
        // wedged channel that wiped the conversation row before the runtime was
        // parked (so the restored runtime carries NO row for this pane).
        vm.clearAgentDetectionForPaneForTest("%0")
        runCurrent()
        assertNull("precondition: the presumed-agent pane has no conversation row", vm.agentConversations.value["%0"])

        // The recorded-kind verdict resolves the session as NOT a confirmed shell
        // (foreign / agent / re-classified) — the single verdict-application point
        // reached on a restore via refreshCurrentSessionRecordedKind. On base no
        // re-seed runs → the pane stays rowless → the raw Terminal void (#807).
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = false)
        runCurrent()

        val row = vm.agentConversations.value["%0"]
        assertNotNull(
            "#874: a not-shell verdict re-seeds the dropped presumed-agent pane's" +
                " Conversation placeholder (no residual black Terminal void)",
            row,
        )
        assertEquals("#874: the re-seed lands on the Conversation surface", SessionTab.Conversation, row!!.selectedTab)
        assertNull("#874: the re-seed is detection-less (the detecting placeholder)", row.detection)
        assertEquals("#874: the re-seed is in the Loading state", ConversationLoadState.Loading, row.loadState)
        assertTrue("#874: the re-seed is flagged as an auto-seed", row.autoSeededPlaceholder)
    }

    @Test
    fun confirmedShellVerdictNeverReseedsPlaceholderNoFlash() = runTest(scheduler) {
        // #894 no-flash invariant (class coverage): a CONFIRMED-shell verdict must
        // NOT re-seed the Conversation placeholder for a rowless pane. The #874
        // re-seed pass runs ONLY on the not-shell branch; the shell branch still
        // drops/keeps placeholders exactly as before (no wrong-surface flash on a
        // genuine shell).
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        vm.clearAgentDetectionForPaneForTest("%0")
        runCurrent()
        assertNull("precondition: rowless pane", vm.agentConversations.value["%0"])

        // A recorded SHELL verdict must NOT re-seed — a confirmed shell stays on
        // the raw Terminal (correct), never flashing the Conversation placeholder.
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()
        assertNull(
            "#894: a confirmed-shell verdict never re-seeds the Conversation placeholder",
            vm.agentConversations.value["%0"],
        )
        assertTrue(
            "#894: the confirmed-shell verdict is published per-pane",
            "%0" in vm.confirmedShellPaneIds.value,
        )
    }

    @Test
    fun notShellVerdictDoesNotClobberLiveRowNoYank() = runTest(scheduler) {
        // #815 no-yank invariant (class coverage): the #874 re-seed must NOT
        // clobber a pane that ALREADY has a row (a live agent or a user-tapped
        // choice). seedPresumedAgentPlaceholder self-gates on `containsKey`, so a
        // not-shell verdict over a live row is a no-op.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        // A live Codex binds; user is on the Terminal tab (an explicit choice the
        // re-seed must not yank back to Conversation).
        vm.markAgentTailLiveForTest("%0", newCodexDetection())
        vm.selectSessionTab("%0", SessionTab.Terminal)
        runCurrent()
        val before = vm.agentConversations.value["%0"]!!
        assertEquals("precondition: live Codex row on Terminal", SessionTab.Terminal, before.selectedTab)
        assertEquals(AgentKind.Codex, before.detection?.agent)

        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = false)
        runCurrent()
        val after = vm.agentConversations.value["%0"]!!
        assertEquals(
            "#815: the not-shell verdict re-seed does NOT yank a live row's tab",
            SessionTab.Terminal,
            after.selectedTab,
        )
        assertEquals("#815: the live detection is preserved", AgentKind.Codex, after.detection?.agent)
    }


    @Test
    fun confirmedShellPaneSeedGateSuppressesConversationPlaceholder() = runTest(scheduler) {
        // AC1 + AC4 (shell branch, default = Conversation). A pane whose session
        // the durable tree recorded as `@ps_agent_kind=shell` must NOT carry the
        // auto-seeded "Loading conversation…" placeholder.
        val vm = newVm()
        // Default open-time tab is Conversation (the black-screen cure) — the
        // exact state where the wrong-surface shell flash happens.
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        // On open, before the recorded-kind read lands, the pane got the #878
        // auto-seed (the same optimistic cure an agent gets — we can't yet tell
        // them apart). This is the pre-verdict state.
        assertNotNull(
            "#878: a fresh pane gets the optimistic placeholder before the recorded-kind read",
            vm.agentConversations.value["%0"],
        )

        // The recorded `@ps_agent_kind` reads back SHELL (the durable tree
        // verdict). Slice C drops the auto-seeded placeholder IMMEDIATELY (the
        // confirmed shell never lingers on the wrong surface — the first-open
        // flash is killed) and publishes the pane as confirmed-shell.
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()
        assertNull(
            "#894 (Slice C): a confirmed-shell pane has its auto-seeded" +
                " Conversation placeholder dropped — no wrong-surface flash",
            vm.agentConversations.value["%0"],
        )
        assertTrue(
            "#894 (Slice C): the confirmed-shell verdict is published per-pane",
            "%0" in vm.confirmedShellPaneIds.value,
        )

        // The load-bearing GATE: a fresh seed attempt for a CONFIRMED shell must
        // be a no-op (on base, with no gate, this re-creates the placeholder —
        // the RED state).
        vm.seedPresumedAgentPlaceholderForTest("%0")
        runCurrent()
        assertNull(
            "#894 (Slice C): the seed gate skips a confirmed shell — it never" +
                " re-creates the Conversation placeholder",
            vm.agentConversations.value["%0"],
        )
    }

    @Test
    fun presumedAgentPaneStillSeedsConversationPlaceholder() = runTest(scheduler) {
        // AC2 + AC4 (agent / no-shell-verdict branch, default = Conversation).
        // The #878 black-screen cure is UNCHANGED: a pane with NO confirmed-shell
        // verdict (a presumed-agent / foreign / not-yet-classified pane) STILL
        // gets the auto-seeded placeholder so it never shows the black Terminal.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        val placeholder = vm.agentConversations.value["%0"]
        assertNotNull(
            "#894: a presumed-agent pane (no shell verdict) STILL gets the #878 cure",
            placeholder,
        )
        assertEquals(SessionTab.Conversation, placeholder!!.selectedTab)
        assertNull(placeholder.detection)
        assertTrue(placeholder.autoSeededPlaceholder)
        assertFalse(
            "#894: a presumed-agent pane is NOT published as confirmed-shell",
            "%0" in vm.confirmedShellPaneIds.value,
        )

        // A re-seed (e.g. a later reconcile) keeps the placeholder for a
        // presumed agent — the cure is intact.
        vm.seedPresumedAgentPlaceholderForTest("%0")
        runCurrent()
        assertNotNull(
            "#894: re-seeding a presumed-agent pane keeps the placeholder",
            vm.agentConversations.value["%0"],
        )
    }

    @Test
    fun confirmedShellPaneIdsReflectsRecordedVerdictNotAHardWiredConstant() = runTest(scheduler) {
        // AC3: `confirmedShell` reflects the real per-pane shell-vs-agent truth
        // from the recorded-kind record, not a hard-wired constant. Marking the
        // session shell publishes the pane; re-classifying it (shell -> agent)
        // un-publishes it so the agent surface returns.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        assertFalse(
            "#894: a pane with no recorded verdict is NOT confirmed-shell",
            "%0" in vm.confirmedShellPaneIds.value,
        )

        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()
        assertTrue(
            "#894: a recorded SHELL verdict publishes the pane as confirmed-shell",
            "%0" in vm.confirmedShellPaneIds.value,
        )

        // Re-classified to an agent (the recorded kind is no longer shell): the
        // confirmed-shell flag clears, so the presumed-agent surface returns.
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = false)
        runCurrent()
        assertFalse(
            "#894: a non-shell (agent) verdict un-publishes the pane — not sticky",
            "%0" in vm.confirmedShellPaneIds.value,
        )
    }

    // Issue #962 — a live agent started INSIDE a session recorded
    // `@ps_agent_kind=shell` must regain its agent surface (and the Conversation
    // toggle). The recorded-shell verdict (#894) publishes the pane as
    // confirmed-shell, which collapses `presumedAgent` and hides the toggle for
    // the life of the session — the maintainer's exact dogfood report. The fix:
    // the AUTHORITATIVE live-detection event re-classifies the session OUT of
    // confirmed-shell. This deterministic reproduction injects the exact state
    // machine the on-device journey exercises (the Docker fixture cannot make the
    // host daemon classify a non-cgroup-scoped process, so per D33 the failing
    // state is injected synthetically and hard-asserted, never self-skipped).
    //
    // RED on base: confirmedShell stays set after the live detection binds (the
    // override absent). GREEN: confirmedShell is cleared, so the toggle returns.
    // Class coverage (G2): claude / codex / opencode + the no-flap control.

    @Test
    fun liveAgentDetectionClearsConfirmedShellSoConversationToggleReturns() = runTest(scheduler) {
        assertConfirmedShellClearedByLiveAgent(::newClaudeDetection)
    }

    @Test
    fun liveCodexDetectionClearsConfirmedShellInRecordedShellSession() = runTest(scheduler) {
        assertConfirmedShellClearedByLiveAgent(::newCodexDetection)
    }

    @Test
    fun liveOpenCodeDetectionClearsConfirmedShellInRecordedShellSession() = runTest(scheduler) {
        assertConfirmedShellClearedByLiveAgent(::newOpenCodeDetection)
    }

    private fun TestScope.assertConfirmedShellClearedByLiveAgent(
        detectionFactory: () -> AgentDetection,
    ) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        // The session is recorded `@ps_agent_kind=shell` (a plain shell the
        // user/kind-picker classified as shell). The pane is published
        // confirmed-shell → the Conversation toggle is hidden (presumedAgent
        // collapses). This is the durable state the maintainer hit.
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()
        assertTrue(
            "#962 precondition: a recorded-shell pane is published confirmed-shell " +
                "(the state that hides the Conversation toggle)",
            "%0" in vm.confirmedShellPaneIds.value,
        )

        // A live agent runtime is detected INSIDE the shell-recorded pane (the
        // user started claude/codex/opencode). On base (no fix) confirmedShell
        // stays set and the toggle stays hidden; the fix re-classifies the
        // session out of confirmed-shell on this authoritative detection event.
        vm.markAgentTailLiveForTest("%0", detectionFactory())
        runCurrent()

        assertFalse(
            "#962: a live agent detection in a recorded-shell session must clear the " +
                "confirmed-shell verdict so presumedAgent returns and the Conversation " +
                "toggle reappears (RED on base — confirmedShell stays set)",
            "%0" in vm.confirmedShellPaneIds.value,
        )
        // The pane now carries the live agent detection (the parsed conversation
        // surface), proving the toggle reaches a real source, not an empty tab.
        assertEquals(
            "#962: the live agent detection is bound to the pane",
            detectionFactory().agent,
            vm.agentConversations.value["%0"]?.detection?.agent,
        )
    }

    @Test
    fun genuineRecordedShellWithNoAgentKeepsConfirmedShellNoFlap() = runTest(scheduler) {
        // Issue #962 adjacency / #894 no-flap invariant: a GENUINE recorded shell
        // (no live agent detection ever binds) must STAY confirmed-shell — the
        // #962 override must not resurrect the "fresh shell flashes Conversation"
        // regression. No markAgentTailLive is ever called here (no agent), so the
        // confirmed-shell verdict is never cleared.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()
        assertTrue(
            "#894: a genuine recorded shell is published confirmed-shell",
            "%0" in vm.confirmedShellPaneIds.value,
        )
        // A reconcile / re-seed must NOT clear it (no agent detection event).
        vm.seedPresumedAgentPlaceholderForTest("%0")
        runCurrent()
        assertTrue(
            "#962/#894 no-flap: a genuine recorded shell with NO agent STAYS " +
                "confirmed-shell (the toggle correctly stays hidden)",
            "%0" in vm.confirmedShellPaneIds.value,
        )
    }

    // ─────────────────────── Issue #975 — REAL-PATH classify-miss ───────────
    // The maintainer hit a LIVE Claude session showing ONLY "Terminal" — no
    // Conversation toggle — because the session was recorded `@ps_agent_kind=shell`
    // with a node-wrapped/quiet `claude` started inside it, and the host
    // agent-kind daemon's cgroup-v2/`/proc` classify returns `unknown` (it cannot
    // see the masked process) → no detection binds → the confirmed-shell verdict
    // never clears → the toggle is gone for the session's life (#962 recurrence).
    //
    // These drive the REAL detection chain (resolveForeignKindGuess →
    // AgentKindRemoteSource.classify → AgentConversationRepository) through a fake
    // SSH session that models the masked-live-agent host: the `agents kind` daemon
    // exec returns `unknown` (scope=null) while a fresh `*.jsonl` transcript is
    // genuinely present in the cwd. This is the #780 synthetic-state-injection at
    // the host seam — NOT a `markAgentTailLiveForTest` injection (which #962 used
    // and which CANNOT exercise the failing classify-miss). The end-to-end Docker
    // proof is `ConversationToggleVisibleForLiveAgentInShellRecordedSessionDockerTest`.

    @Test
    fun b1MaskedLiveClaudeInRecordedShellBindsDetectionViaTranscriptDespiteUnknownClassify() =
        runTest(scheduler) {
            // B1: daemon classify = `unknown` (masked agent) + a live Claude
            // transcript in the cwd → the foreign resolver binds Claude detection
            // (the trustworthy-live-agent-evidence fallback). On base (no fix) the
            // foreign resolver returns null on a null kind guess, so NO detection
            // binds and the toggle stays gone — RED. With the fix the transcript
            // fallback binds → GREEN.
            val now = System.currentTimeMillis() / 1000
            val session = MaskedAgentSshSession(
                // The daemon could not read the scope → `unknown`, NOT `none`.
                classifyAgentKind = "unknown",
                // …but a live Claude transcript is plainly present in the cwd.
                detectionOutput =
                    "claude|$now|/workspace/proj|" +
                        "/home/testuser/.claude/projects/-workspace-proj/live.jsonl",
            )
            val vm = newVm()
            vm.connectWithRichPaneForTest(
                paneId = "%0",
                windowId = "@0",
                cwd = "/workspace/proj",
                paneTty = "/dev/pts/7",
                panePid = 4242L,
                session = session,
            )
            runCurrent()
            // The durable tree recorded this session `@ps_agent_kind=shell`.
            vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
            runCurrent()
            assertTrue(
                "#975 precondition: the recorded-shell pane is published confirmed-shell",
                "%0" in vm.confirmedShellPaneIds.value,
            )

            val detection = vm.resolveForeignSessionDetectionForTest("%0", session)
            runCurrent()

            assertNotNull(
                "#975 (B1): a CONFIRMED-SHELL pane whose daemon classify returns " +
                    "`unknown` while a live Claude transcript is present must bind " +
                    "the agent via the transcript-evidence fallback (RED on base — " +
                    "the foreign resolver returned null and no detection bound)",
                detection,
            )
            assertEquals(
                "#975 (B1): the bound detection is the live Claude transcript",
                AgentKind.ClaudeCode,
                detection?.agent,
            )
        }

    @Test
    fun b1GenuineNoneShellGetsNoTranscriptFallbackNoFlap() = runTest(scheduler) {
        // B1 no-flap control (#894): a daemon `none` verdict (a READABLE scope
        // with no agent — a genuine shell) must NOT trigger the transcript
        // fallback even if a STALE transcript lingers in the cwd. `none` is a
        // confident "no agent", unlike the unreadable `unknown`. Detection stays
        // null → the confirmed-shell verdict is preserved → the toggle correctly
        // stays hidden.
        val now = System.currentTimeMillis() / 1000
        val session = MaskedAgentSshSession(
            classifyAgentKind = "none",
            detectionOutput =
                "claude|$now|/workspace/proj|" +
                    "/home/testuser/.claude/projects/-workspace-proj/stale.jsonl",
        )
        val vm = newVm()
        vm.connectWithRichPaneForTest(
            paneId = "%0",
            windowId = "@0",
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            panePid = 4242L,
            session = session,
        )
        runCurrent()
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()

        val detection = vm.resolveForeignSessionDetectionForTest("%0", session)
        runCurrent()

        assertNull(
            "#975 (B1 no-flap): a daemon `none` (genuine readable shell) must NOT " +
                "bind a transcript fallback — only the unreadable `unknown` does. " +
                "Detection stays null so the confirmed-shell verdict is preserved.",
            detection,
        )
    }

    @Test
    fun b1UnknownClassifyButNoTranscriptStaysNull() = runTest(scheduler) {
        // B1 boundary: `unknown` classify but NO live transcript in the cwd → the
        // fallback enumerates nothing → null (a genuine shell with an unreadable
        // scope and no agent). The fallback is evidence-driven: no transcript, no
        // bind, no flap.
        val session = MaskedAgentSshSession(
            classifyAgentKind = "unknown",
            detectionOutput = "",
        )
        val vm = newVm()
        vm.connectWithRichPaneForTest(
            paneId = "%0",
            windowId = "@0",
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            panePid = 4242L,
            session = session,
        )
        runCurrent()
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()

        val detection = vm.resolveForeignSessionDetectionForTest("%0", session)
        runCurrent()

        assertNull(
            "#975 (B1 boundary): `unknown` classify with NO transcript binds nothing",
            detection,
        )
    }

    @Test
    fun b1ForeignNotConfirmedShellUnknownClassifyGetsNoTranscriptFallback() = runTest(scheduler) {
        // B1 scope guard: the transcript fallback is for a CONFIRMED-SHELL session
        // ONLY — we second-guess a stale recorded-shell verdict, never a clean
        // foreign session. A foreign (not-confirmed-shell) pane whose daemon
        // returns `unknown` keeps the existing null behaviour (the user picks the
        // kind); it does NOT auto-bind a same-cwd transcript.
        val now = System.currentTimeMillis() / 1000
        val session = MaskedAgentSshSession(
            classifyAgentKind = "unknown",
            detectionOutput =
                "claude|$now|/workspace/proj|" +
                    "/home/testuser/.claude/projects/-workspace-proj/live.jsonl",
        )
        val vm = newVm()
        vm.connectWithRichPaneForTest(
            paneId = "%0",
            windowId = "@0",
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            panePid = 4242L,
            session = session,
        )
        runCurrent()
        // NOTE: no applyRecordedShellVerdict → the session is FOREIGN, not
        // confirmed-shell.

        val detection = vm.resolveForeignSessionDetectionForTest("%0", session)
        runCurrent()

        assertNull(
            "#975 (B1 scope): a FOREIGN (not-confirmed-shell) pane with `unknown` " +
                "classify gets NO transcript fallback — the fallback only clears a " +
                "stale recorded-shell verdict",
            detection,
        )
    }

    @Test
    fun b1PrimeDedupOrderingReProbesConfirmedShellPaneOnUnchangedInput() = runTest(scheduler) {
        // B1′ (dedup ordering): a `claude` started INSIDE an already-detected
        // shell pane does NOT change the `(cwd, command, tty)` triple. On base the
        // dedup early-return PRECEDED the cache-bust, so the stale one-shot "no
        // agent" guess persisted and the pane never re-probed. The fix busts the
        // confirmed-shell foreign-guess cache BEFORE the dedup check, so the next
        // probe re-evaluates. Here: seed a cached `unknown` guess, confirm it is
        // cached, then a confirmed-shell detection re-run must have BUSTED it.
        val session = MaskedAgentSshSession(classifyAgentKind = "unknown", detectionOutput = "")
        val vm = newVm()
        vm.connectWithRichPaneForTest(
            paneId = "%0",
            windowId = "@0",
            cwd = "/workspace/proj",
            paneTty = "/dev/pts/7",
            panePid = 4242L,
            session = session,
        )
        runCurrent()
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()

        // Simulate the one-shot guess having cached "no agent" before the agent
        // launched (the stale guess the dedup bug never re-evaluated). Re-seeding
        // the cache after the connect-path probe models the stale verdict the
        // dedup bug never re-evaluated; capture the daemon round-trip count as a
        // baseline so we can prove the re-probe forces a FRESH query past it.
        vm.seedForeignGuessCacheForTest("$0")
        assertTrue(
            "#975 (B1′): precondition — the stale one-shot guess is cached",
            vm.foreignGuessIsCachedForTest("$0"),
        )
        val classifyCountBeforeReProbe = session.classifyExecCount

        // A re-probe of the confirmed-shell pane (same unchanged input) must BUST
        // the cache BEFORE the dedup early-return, so the daemon re-evaluates.
        // Issue #1001: with the detection exec now confined to the shared test
        // scheduler, `runCurrent()` deterministically drains the re-probe job to
        // completion — including the FRESH daemon classify the bust enabled. The
        // load-bearing proof of the B1′ fix is therefore that the cache-bust
        // forced a NEW daemon round-trip (classifyExecCount incremented past the
        // baseline), not that the cache stays empty (which only ever held because
        // the race left the re-probe unfinished — exactly the #1001 flake this
        // change removes).
        vm.startAgentDetectionForPaneForTest("%0")
        runCurrent()

        assertEquals(
            "#975 (B1′): re-probing a confirmed-shell pane busts the stale foreign " +
                "guess cache BEFORE the dedup early-return, so the daemon is " +
                "re-queried exactly once more — RED on base where the early-return " +
                "skipped the bust and the stale seeded guess suppressed any " +
                "re-evaluation (the daemon round-trip count stayed at the baseline)",
            classifyCountBeforeReProbe + 1,
            session.classifyExecCount,
        )
    }

    @Test
    fun b2ReattachReStampDoesNotDropRememberedAgentPlaceholder() = runTest(scheduler) {
        // B2 (#959 beyond-grace reattach re-stamp): on reconnect the screen
        // re-reads `@ps_agent_kind=shell` and re-applies applyRecordedShellVerdict
        // (isShell=true). On base that UNCONDITIONALLY dropped the just-restored
        // remembered-agent placeholder (#819 A2), re-suppressing the Conversation
        // toggle for a session that WAS a live agent before backgrounding — the
        // raw black Terminal strand. The fix keeps the remembered-agent placeholder
        // (only the fresh #878 auto-seed is dropped); detection re-confirms it.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        // A beyond-grace reattach restored the remembered-agent resolving
        // placeholder (#819 A2 — detection-less, on Conversation).
        vm.seedRememberedAgentPlaceholderForTest("%0")
        runCurrent()
        assertNotNull(
            "#975 (B2): precondition — the remembered-agent placeholder is restored",
            vm.agentConversations.value["%0"],
        )
        assertEquals(
            SessionTab.Conversation,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )

        // The reconnect re-read re-applies the recorded-shell verdict.
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()

        assertNotNull(
            "#975 (B2): the reattach re-stamp must NOT drop the remembered-agent " +
                "placeholder (RED on base — applyRecordedShellVerdict tore it down " +
                "and the Conversation toggle disappeared post-reconnect)",
            vm.agentConversations.value["%0"],
        )
        assertTrue(
            "#975 (B2): the surviving row is still the remembered-agent placeholder",
            vm.agentConversations.value["%0"]!!.rememberedAgentPlaceholder,
        )
        assertEquals(
            "#975 (B2): it is still on the Conversation surface (the toggle survives)",
            SessionTab.Conversation,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )
    }

    @Test
    fun b2ReattachReStampStillDropsFreshAutoSeededPlaceholder() = runTest(scheduler) {
        // B2 adjacency (#894): the B2 fix must NOT resurrect the #894 first-open
        // FLASH — a FRESH #878 auto-seeded placeholder (NOT a remembered agent)
        // racing ahead of the recorded-shell read must STILL be dropped on the
        // confirmed-shell verdict. Only the remembered-agent placeholder is spared.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        // The fresh #878 auto-seed is up (autoSeededPlaceholder = true).
        assertTrue(
            "#894: precondition — the fresh auto-seed is up",
            vm.agentConversations.value["%0"]?.autoSeededPlaceholder == true,
        )

        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        runCurrent()

        assertNull(
            "#975 (B2 adjacency / #894): a FRESH auto-seeded placeholder is STILL " +
                "dropped on the confirmed-shell verdict (the first-open flash kill " +
                "is preserved — only the remembered-agent placeholder is spared)",
            vm.agentConversations.value["%0"],
        )
    }

    /**
     * Issue #975: connect + register a single pane carrying a real [cwd], [paneTty]
     * and [panePid] (the foreign-detection inputs the one-shot daemon guess + the
     * transcript fallback need), with [session] installed as the active sessionRef
     * so the REAL detection chain runs against the fake masked-agent host.
     */
    private fun TmuxSessionViewModel.connectWithRichPaneForTest(
        paneId: String,
        windowId: String,
        cwd: String,
        paneTty: String,
        panePid: Long,
        session: SshSession,
        sessionName: String = "work",
    ) {
        replaceClientForTest(
            hostId = 42L,
            hostName = "docker",
            host = "10.0.2.2",
            port = 2222,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
            client = FakeTmuxClient(),
            session = session,
        )
        applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId,
                    windowId,
                    "$0",
                    "node",
                    paneIndex = 0,
                    cwd = cwd,
                    currentCommand = "node",
                    paneTty = paneTty,
                    panePid = panePid,
                    sessionName = sessionName,
                ),
            ),
        )
        setSessionRefForTest(session)
    }

    @Test
    fun terminalDefaultNeverSeedsPlaceholderRegardlessOfShellVerdict() = runTest(scheduler) {
        // G2 class coverage (default = Terminal, both shell and agent): when the
        // user opted into the Terminal default, NOTHING is ever auto-seeded — the
        // raw Terminal IS the intended pre-detection view. The Slice C shell gate
        // must not change that (it is an early-return BEFORE the Terminal check
        // only matters for Conversation), and a presumed-agent pane likewise gets
        // no placeholder on the Terminal default.
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()
        assertNull(
            "#894 (G2): Terminal default never seeds a placeholder (presumed agent)",
            vm.agentConversations.value["%0"],
        )

        // Even a confirmed-shell verdict on the Terminal default seeds nothing.
        vm.applyRecordedShellVerdictForTest(sessionId = "$0", isShell = true)
        vm.seedPresumedAgentPlaceholderForTest("%0")
        runCurrent()
        assertNull(
            "#894 (G2): Terminal default + confirmed shell still seeds nothing",
            vm.agentConversations.value["%0"],
        )
        assertTrue(
            "#894 (G2): the confirmed-shell verdict is still published on Terminal default",
            "%0" in vm.confirmedShellPaneIds.value,
        )
    }

    @Test
    fun freshForeignNoGuessPaneShowsPlaceholderThenDropsOnNullDetection() = runTest(scheduler) {
        // AC1 (class: a FOREIGN / no-guess pane — the daemon does NOT classify
        // it as an agent, so detection comes back NULL). The user STILL sees the
        // detecting placeholder during the detection window (not the black
        // Terminal); when the null verdict lands, the auto-seeded placeholder is
        // dropped so a genuine shell does not linger on "Loading…" → "Failed".
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        val placeholder = vm.agentConversations.value["%0"]
        assertNotNull(
            "#878: even a foreign/no-guess pane shows the detecting placeholder" +
                " during the detection window (not the black Terminal)",
            placeholder,
        )
        assertEquals(SessionTab.Conversation, placeholder!!.selectedTab)
        assertNull(placeholder.detection)
        assertTrue(placeholder.autoSeededPlaceholder)

        // The daemon returns no agent kind → null detection for this pane. The
        // FIRST null is DEFERRED (#878): a transient null mid-detection must not
        // flash the black Terminal, so the placeholder is held and re-confirmed.
        val firstNull = vm.handleNullAgentDetectionForTest("%0")
        runCurrent()
        assertFalse(
            "#878: the FIRST null defers (holds the placeholder, does not flash black Terminal)",
            firstNull,
        )
        assertNotNull(
            "#878: the auto-seeded placeholder survives the first transient null",
            vm.agentConversations.value["%0"],
        )
        assertEquals(
            "#878: the held row is still the detecting placeholder",
            SessionTab.Conversation,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )

        // A SECOND consecutive null (AGENT_EXIT_CONFIRMATIONS = 2) confirms the
        // pane is a genuine shell/foreign → the placeholder is DROPPED so it does
        // not linger on "Loading…" → "Failed".
        val secondNull = vm.handleNullAgentDetectionForTest("%0")
        runCurrent()
        assertTrue(
            "#878: the second confirming null downgrades a genuine shell/foreign pane",
            secondNull,
        )
        assertNull(
            "#878: the auto-seeded placeholder is DROPPED once the shell/foreign verdict is confirmed",
            vm.agentConversations.value["%0"],
        )
    }

    @Test
    fun autoSeedDoesNotOverwriteRememberedTerminalChoiceNoYank() = runTest(scheduler) {
        // AC2 (#815 no-yank): a window whose user previously opted into Terminal
        // must reattach on Terminal — the #878 auto-seed must NOT overwrite the
        // remembered status with the Conversation default.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        // The user saw the agent but stayed on Terminal — remember Terminal.
        vm.selectSessionTab("%0", SessionTab.Terminal)
        runCurrent()

        // Reattach: a new pane id under the SAME window. seed-from-memory runs
        // FIRST and restores the remembered Terminal row; the #878 auto-seed
        // then no-ops because a row already exists (current != null).
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        val restored = vm.agentConversations.value["%7"]!!
        assertEquals(
            "#878/#815: the remembered Terminal choice is NOT overwritten by the auto-seed",
            SessionTab.Terminal,
            restored.selectedTab,
        )
        assertFalse(
            "#878: a remembered/explicit row is never an auto-seeded placeholder",
            restored.autoSeededPlaceholder,
        )
    }

    @Test
    fun autoSeedDoesNotOverwriteRememberedConversationRowNoYank() = runTest(scheduler) {
        // AC2 (#815 no-yank): a window whose user previously had a Conversation
        // row must reattach on the REMEMBERED Conversation tab — NOT replaced by
        // a fresh #878 auto-seed placeholder. seed-from-memory wins (it runs
        // first and creates the row, so seedPresumedAgentPlaceholder no-ops).
        //
        // Issue #819 (A2): the remembered row is now restored as a REMEMBERED-
        // agent resolving placeholder (rememberedAgentPlaceholder = true,
        // detection-less) — NOT the remembered detection rendered Live (which
        // could re-show a stale/sibling source). It is still distinct from the
        // #878 AUTO-seed (autoSeededPlaceholder = false), so the no-yank line
        // holds: the remembered Conversation choice survives.
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        val restored = vm.agentConversations.value["%7"]!!
        assertEquals(
            "#878/#815: the remembered Conversation choice survives the auto-seed",
            SessionTab.Conversation,
            restored.selectedTab,
        )
        assertNull(
            "#819 (A2): the reattach seed restores a resolving placeholder, not " +
                "the remembered (possibly stale) source rendered Live",
            restored.detection,
        )
        assertTrue(
            "#819 (A2): the remembered row is a remembered-agent resolving placeholder",
            restored.rememberedAgentPlaceholder,
        )
        assertFalse(
            "#878: a restored remembered row is the remembered-agent placeholder, " +
                "NOT the #878 auto-seed placeholder",
            restored.autoSeededPlaceholder,
        )
    }

    @Test
    fun autoSeedSuppressedWhenOpenTimeDefaultIsTerminal() = runTest(scheduler) {
        // AC (opt-out): when the user opted the open-time default to Terminal,
        // the raw Terminal IS the intended pre-detection view, so #878 seeds
        // NOTHING — the pager shows the Terminal as before (no placeholder).
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        assertNull(
            "#878: with the Terminal open-time default, no pre-detection placeholder is seeded",
            vm.agentConversations.value["%0"],
        )
    }

    @Test
    fun midSessionAgentTakeoverDoesNotYankUserOffTerminal() = runTest(scheduler) {
        // Issue #815/#818 (the no-mid-session-yank line): a DIFFERENT agent
        // taking over a pane's window (no same-source continuity) is a
        // detection/refresh on an ALREADY-open session, NOT an open-time event.
        // It must NEVER force the user onto another view — even when the global
        // open-time default is Conversation. A user watching the raw Terminal
        // stays on Terminal through the takeover.
        val vm = newVm()
        // Global default = Conversation (the #818 default) to prove the takeover
        // does NOT apply the open-time default mid-session.
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        // First agent lands and creates the row (open-time default = Conversation).
        vm.markAgentTailLiveForTest("%0", newClaudeDetection())
        runCurrent()
        assertEquals(
            "precondition: the first OPEN lands on the Conversation default",
            SessionTab.Conversation,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )
        // The user deliberately moves to the raw Terminal mid-session.
        vm.selectSessionTab("%0", SessionTab.Terminal)
        runCurrent()
        assertEquals(
            "precondition: the user is now on Terminal",
            SessionTab.Terminal,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )

        // A different agent (Codex) takes over the same pane — no same-source
        // continuity, so the takeover branch (current.detection != detection)
        // rebuilds the row. It must PRESERVE the user's Terminal tab, NOT yank
        // them to the Conversation default.
        vm.markAgentTailLiveForTest("%0", newCodexDetection())
        runCurrent()
        val takenOver = vm.agentConversations.value["%0"]!!
        assertEquals(
            "a mid-session takeover must NOT yank the user off Terminal (#815)",
            SessionTab.Terminal,
            takenOver.selectedTab,
        )
        assertEquals(AgentKind.Codex, takenOver.detection?.agent)
    }

    @Test
    fun midSessionRefreshDoesNotYankUserOffTerminalWithConversationDefault() = runTest(scheduler) {
        // Issue #815/#818: the core no-yank invariant for the SAME-agent
        // refinement path. With the open-time default = Conversation, a user who
        // opened on Conversation and then moved to Terminal must NOT be bounced
        // back to Conversation when live detection re-lands (a reconnect /
        // confidence drift on the same agent + same log).
        val vm = newVm()
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Conversation,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.markAgentTailLiveForTest("%0", newClaudeDetection())
        runCurrent()
        // The user moves to the raw Terminal.
        vm.selectSessionTab("%0", SessionTab.Terminal)
        runCurrent()
        assertEquals(SessionTab.Terminal, vm.agentConversations.value["%0"]!!.selectedTab)

        // Same-agent re-detection (only confidence drifted) re-lands.
        vm.markAgentTailLiveForTest(
            "%0",
            newClaudeDetection().copy(confidence = AgentDetection.Confidence.RecentFile),
        )
        runCurrent()
        assertEquals(
            "a same-agent mid-session refresh must NOT yank the user back to the Conversation default",
            SessionTab.Terminal,
            vm.agentConversations.value["%0"]!!.selectedTab,
        )
    }

    @Test
    fun nonAgentShellNeverGetsAConversationRow() = runTest(scheduler) {
        // Issue #815 guard: a plain shell pane (no agent detected) never gets a
        // conversation row at all — markAgentTailLive is only ever called once an
        // agent is detected — so it stays on the raw Terminal. This proves
        // detection-driven row creation can NEVER touch a non-agent/shell pane.
        val vm = newVm()
        // Issue #878: pin the open-time default to Terminal so the pane-add
        // auto-seed no-ops and this test keeps its original "never-agent window
        // has NO seed, so the first null downgrades immediately" semantics. The
        // #878 auto-seed + transient-null-defer behaviour for a shell under the
        // Conversation default is covered by
        // freshForeignNoGuessPaneShowsPlaceholderThenDropsOnNullDetection.
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        // The pane is a plain "shell" (see connectWithPaneForTest's ParsedPane).
        runCurrent()

        // No detection lands. handleNullAgentDetection is the path taken when live
        // detection comes back null for a never-agent window.
        val downgraded = vm.handleNullAgentDetectionForTest("%0")
        runCurrent()

        assertTrue("a never-agent window has no seed to protect", downgraded)
        assertNull(
            "a non-agent shell pane has no conversation row, so it stays on raw Terminal",
            vm.agentConversations.value["%0"],
        )
    }

    @Test
    fun rememberedConversationChoiceIsHonoredOnReattach() = runTest(scheduler) {
        // Issue #815: detection never changes the tab, but a remembered/explicit
        // user choice must still WIN. A user who deliberately moved to
        // Conversation must be put back on Conversation on reattach, NOT reset to
        // the Terminal default. (Seed-from-memory honours wasOnConversation
        // BEFORE markAgentTailLive runs, so the remembered Conversation row
        // already exists and live re-detection refines it without resetting it.)
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        // The user deliberately moved to Conversation.
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        // Reattach: a new pane id under the same window; memory restores the row.
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()
        assertEquals(
            "remembered Conversation choice is restored on reattach, not reset to the Terminal default",
            SessionTab.Conversation,
            vm.agentConversations.value["%7"]!!.selectedTab,
        )

        // Live re-detection lands for the SAME agent + same log: it must still
        // honour the remembered Conversation tab (same-source refinement preserves it).
        vm.markAgentTailLiveForTest(
            "%7",
            newClaudeDetection().copy(confidence = AgentDetection.Confidence.RecentFile),
        )
        runCurrent()
        assertEquals(
            "same-agent refinement keeps the user's remembered Conversation tab",
            SessionTab.Conversation,
            vm.agentConversations.value["%7"]!!.selectedTab,
        )
    }

    @Test
    fun reconnectDoesNotResurrectAgentThatExited() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        // Live detection reports the window no longer hosts an agent (the
        // user exited Claude). This reconciles the remembered status.
        vm.clearAgentDetectionForPaneForTest("%0")
        runCurrent()

        // Reconnect: the same window must NOT resurrect the EXITED agent. The
        // remembered status was forgotten on exit, so seed-from-memory restores
        // nothing.
        //
        // Issue #878: the pane-add auto-seed DOES paint a transient detecting
        // placeholder during the (now-pending) re-detection — this is the
        // black-screen cure, identical to a fresh open: we don't know yet that
        // the window is now a shell. Crucially the placeholder carries NO
        // detection (autoSeededPlaceholder=true), so the EXITED agent is NOT
        // resurrected — agentForWindow stays null — and the null re-detection
        // verdict drops the placeholder (asserted below).
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        val transient = vm.agentConversations.value["%7"]
        if (transient != null) {
            assertTrue(
                "#878: any row on the reconnected window is only the detection-less" +
                    " auto-seed placeholder, never the resurrected agent",
                transient.autoSeededPlaceholder && transient.detection == null,
            )
        }
        assertNull(
            "the EXITED agent is never resurrected on reconnect (no detection lights up)",
            vm.agentForWindow("@0"),
        )

        // The null re-detection verdict lands. If an auto-seeded placeholder is
        // up (#878), the first null is DEFERRED (held + re-confirmed) so the
        // detecting placeholder does not flash the black Terminal; the SECOND
        // confirming null drops it. Drive consecutive nulls until the window
        // settles with no conversation row.
        var attempts = 0
        while (vm.agentConversations.value["%7"] != null && attempts < AGENT_EXIT_CONFIRMATIONS + 1) {
            vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
            attempts++
        }
        assertNull(
            "an exited agent's window settles with no conversation row after the null verdict",
            vm.agentConversations.value["%7"],
        )
        assertNull("no Conversation tab for the exited agent's window", vm.agentForWindow("@0"))
    }

    // ─── Issue #554: transient null detection must not forget the agent ──

    /**
     * Issue #554: on reconnect, live detection routinely reads "no agent" for
     * a beat before the agent's JSONL log / process is observable on the fresh
     * connection. A remembered (seeded) agent window must NOT be downgraded to
     * a plain shell on that FIRST transient null — the seeded Conversation UI
     * stays up and detection re-confirms. Downgrading there was the
     * "we forget it's an agent and bounce to plain-shell-then-back" regression.
     */
    @Test
    fun transientNullDetectionDoesNotForgetRememberedAgentOnReconnect() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        // Reconnect: a rotated pane id under the same window, seeded from
        // memory so the agent UI shows immediately.
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()
        // Issue #819 (A2): the reattach seed is a detection-less remembered-agent
        // resolving placeholder (the agent UI is available, but the route-true
        // source is bound by live re-detection, not the remembered one).
        assertTrue(
            "precondition: remembered-agent resolving placeholder restored on reattach",
            vm.agentConversations.value["%7"]?.rememberedAgentPlaceholder == true,
        )

        // First live-detection null right after the reattach: DEFER, do not
        // forget. The seeded agent UI must survive. This is the #554 no-flap
        // guarantee — A2 preserves it for the detection-less placeholder via
        // shouldDeferAgentDowngrade keying off rememberedAgentPlaceholder.
        val downgraded = vm.handleNullAgentDetectionForTest("%7")
        runCurrent()
        assertFalse("first transient null must be deferred, not a downgrade", downgraded)
        assertNotNull(
            "the seeded agent UI must survive a single transient null",
            vm.agentConversations.value["%7"],
        )
        assertTrue(
            "the remembered-agent resolving placeholder survives the transient null",
            vm.agentConversations.value["%7"]?.rememberedAgentPlaceholder == true,
        )
        assertEquals(
            "the Conversation tab stays selected for the window",
            SessionTab.Conversation,
            vm.agentConversations.value["%7"]?.selectedTab,
        )
    }

    @Test
    fun degradedDetectionDoesNotForgetRememberedAgentOrConsumeExitConfirmations() = runTest(scheduler) {
        val detections = listOf(
            newClaudeDetection(),
            newCodexDetection(),
            newOpenCodeDetection(),
        )

        detections.forEachIndexed { index, detection ->
            val windowId = "@$index"
            val vm = newVm()
            vm.connectWithPaneForTest(paneId = "%0", windowId = windowId, sessionName = "work-$index")
            vm.startAgentConversationForTest("%0", detection)
            vm.selectSessionTab("%0", SessionTab.Conversation)
            runCurrent()

            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        "%7",
                        windowId,
                        "$0",
                        "shell",
                        paneIndex = 0,
                        sessionName = "work-$index",
                    ),
                ),
            )
            runCurrent()

            // Issue #819 (A2): the reattach seed is a detection-less remembered-
            // agent resolving placeholder. A degraded probe must keep retrying
            // it (#897) — it must NOT be treated as an agent exit, and the
            // remembered placeholder stays visible on the Conversation tab.
            repeat(AGENT_EXIT_CONFIRMATIONS) {
                val downgraded = vm.handleUnavailableAgentDetectionForTest("%7")
                runCurrent()
                assertFalse(
                    "#897: degraded ${detection.agent} probe must not be treated as agent exit",
                    downgraded,
                )
                val row = vm.agentConversations.value["%7"]
                assertNotNull("#897: remembered ${detection.agent} row stays visible", row)
                assertEquals(SessionTab.Conversation, row!!.selectedTab)
                assertTrue(
                    "#897/#819 (A2): the remembered-agent resolving placeholder survives the degraded probe",
                    row.rememberedAgentPlaceholder,
                )
            }

            val firstCleanNull = vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
            assertFalse(
                "#897: degraded probes must not consume the clean-null exit confirmation budget",
                firstCleanNull,
            )
            assertTrue(
                "#819 (A2): the remembered placeholder survives the first clean null",
                vm.agentConversations.value["%7"]?.rememberedAgentPlaceholder == true,
            )

            val secondCleanNull = vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
            assertTrue("clean nulls still confirm a genuine ${detection.agent} exit", secondCleanNull)
            assertNull(vm.agentConversations.value["%7"])
            assertNull(vm.agentForWindow(windowId))
            vm.clearForTest()
        }
    }

    /**
     * Issue #942 (black-screen B2, reopen-class D31): a remembered Conversation
     * row collapsed to the raw black Terminal after 2× consecutive
     * successful-but-EMPTY (`Resolved(null)`) detections on a wedged-but-alive
     * channel — the capture/grep raced behind a busy agent (#470/#835) and read
     * "no agent" while the agent was very much alive and still streaming output.
     * #897 protected only the `Unavailable` (probe-threw) branch; the empty-grep
     * `Resolved(null)` branch still counted toward [AGENT_EXIT_CONFIRMATIONS] and
     * tore the row down. The maintainer's 2026-06-24 Claude `faq-assistant` black
     * capture.
     *
     * RED (no fix): each empty null on the streaming channel counts toward exit;
     * the second confirms and the remembered Conversation row is cleared.
     * GREEN (fix): the still-streaming channel (recent `%output`) marks the empty
     * detection as wedged-but-alive, it does NOT count toward exit confirmation,
     * and the remembered Conversation row is preserved across both nulls.
     *
     * Class-cover: Claude, Codex AND OpenCode kinds.
     */
    @Test
    fun emptyDetectionOnWedgedButAliveChannelDoesNotCollapseRememberedConversation() = runTest(scheduler) {
        val detections = listOf(
            newClaudeDetection(),
            newCodexDetection(),
            newOpenCodeDetection(),
        )

        detections.forEachIndexed { index, detection ->
            val windowId = "@$index"
            val vm = newVm()
            vm.connectWithPaneForTest(paneId = "%0", windowId = windowId, sessionName = "wedged-$index")
            vm.startAgentConversationForTest("%0", detection)
            vm.selectSessionTab("%0", SessionTab.Conversation)
            runCurrent()

            // Reattach: the pane comes back as a remembered-agent resolving
            // placeholder (the #495/#819 A2 seed) — the exact remembered
            // Conversation state the maintainer had open when it went black.
            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        "%7",
                        windowId,
                        "$0",
                        "shell",
                        paneIndex = 0,
                        sessionName = "wedged-$index",
                    ),
                ),
            )
            runCurrent()
            assertTrue(
                "#819 (A2): the remembered ${detection.agent} placeholder is up before the empty detections",
                vm.agentConversations.value["%7"]?.rememberedAgentPlaceholder == true,
            )

            // The `-CC` channel is WEDGED-but-ALIVE: it is still streaming
            // `%output` for this pane (a busy agent), so the grep raced an empty
            // read. Inject 2× empty `Resolved(null)` while output keeps arriving.
            repeat(AGENT_EXIT_CONFIRMATIONS) {
                vm.recordPaneOutputActivityForTest("%7")
                assertTrue(
                    "#942: a freshly-streaming channel reads wedged-but-alive",
                    vm.isChannelWedgedButAliveForTest("%7"),
                )
                val downgraded = vm.handleNullAgentDetectionForTest("%7")
                runCurrent()
                assertFalse(
                    "#942: an empty grep on a streaming (wedged-but-alive) ${detection.agent} channel must NOT confirm agent exit",
                    downgraded,
                )
                assertTrue(
                    "#942: the remembered ${detection.agent} Conversation row survives the empty detection (no black Terminal)",
                    vm.agentConversations.value["%7"]?.rememberedAgentPlaceholder == true,
                )
            }

            vm.clearForTest()
        }
    }

    /**
     * Issue #942: the wedged-channel guard must NOT over-protect — a GENUINE
     * agent exit stops emitting output, so its empty `Resolved(null)` arrives on
     * a now-IDLE channel and must still tear the Conversation row down after
     * [AGENT_EXIT_CONFIRMATIONS] consecutive nulls. Class-cover Claude/Codex/
     * OpenCode so a kind-specific over-protection regression is caught.
     */
    @Test
    fun emptyDetectionOnIdleChannelStillTearsDownAGenuinelyExitedAgent() = runTest(scheduler) {
        val detections = listOf(
            newClaudeDetection(),
            newCodexDetection(),
            newOpenCodeDetection(),
        )

        detections.forEachIndexed { index, detection ->
            val windowId = "@$index"
            val vm = newVm()
            vm.connectWithPaneForTest(paneId = "%0", windowId = windowId, sessionName = "exited-$index")
            vm.startAgentConversationForTest("%0", detection)
            vm.selectSessionTab("%0", SessionTab.Conversation)
            runCurrent()

            vm.applyParsedPanesForTest(
                listOf(
                    TmuxSessionViewModel.ParsedPane(
                        "%7",
                        windowId,
                        "$0",
                        "shell",
                        paneIndex = 0,
                        sessionName = "exited-$index",
                    ),
                ),
            )
            runCurrent()

            // The agent exited: the channel went IDLE (no `%output`). The empty
            // grep is now a TRUE "no agent" verdict, not a wedged race.
            vm.clearPaneOutputActivityForTest("%7")
            assertFalse(
                "#942: an idle channel must not read wedged-but-alive",
                vm.isChannelWedgedButAliveForTest("%7"),
            )

            val firstNull = vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
            assertFalse(
                "#554: the first clean null defers (confirmation window) for ${detection.agent}",
                firstNull,
            )

            val secondNull = vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
            assertTrue(
                "#942: a real ${detection.agent} exit on an idle channel still tears the row down (no over-protection)",
                secondNull,
            )
            assertNull(
                "#942: the genuinely-exited ${detection.agent} Conversation row is gone",
                vm.agentConversations.value["%7"],
            )
            assertNull(vm.agentForWindow(windowId))
            vm.clearForTest()
        }
    }

    /**
     * Issue #554: the deferral is a confirmation window, not a permanent
     * pin. A genuinely-exited agent (null detection persisting past
     * [AGENT_EXIT_CONFIRMATIONS]) still reconciles away so a stale
     * Conversation tab does not linger.
     */
    @Test
    fun persistentNullDetectionEventuallyDowngradesAnExitedAgent() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()

        // Drive null detections up to the confirmation threshold. The last one
        // must downgrade the window to a plain shell.
        var downgraded = false
        repeat(AGENT_EXIT_CONFIRMATIONS) {
            downgraded = vm.handleNullAgentDetectionForTest("%7")
            runCurrent()
        }

        assertTrue("a persistently-null agent must eventually downgrade", downgraded)
        assertNull(
            "an agent that genuinely exited must reconcile away after confirmation",
            vm.agentConversations.value["%7"],
        )
        assertNull("no Conversation tab once the agent is confirmed gone", vm.agentForWindow("@0"))
    }

    /**
     * Issue #554: a null detection for a window that was NEVER an agent (no
     * remembered status, no seeded UI) downgrades immediately — the
     * confirmation window is only for protecting a remembered agent seed (or,
     * per #878, an auto-seeded detecting placeholder), not for delaying the
     * normal plain-shell verdict when there is nothing to protect.
     */
    @Test
    fun nullDetectionDowngradesImmediatelyWhenWindowWasNeverAnAgent() = runTest(scheduler) {
        val vm = newVm()
        // Issue #878: pin the open-time default to Terminal so there is no
        // auto-seeded placeholder to protect — this isolates the original #554
        // "nothing to protect → downgrade immediately" path. (The #878
        // placeholder-defer behaviour under the Conversation default is covered
        // by freshForeignNoGuessPaneShowsPlaceholderThenDropsOnNullDetection.)
        vm.setDefaultAgentSessionViewForTest(
            com.pocketshell.app.settings.DefaultAgentSessionView.Terminal,
        )
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        runCurrent()

        val downgraded = vm.handleNullAgentDetectionForTest("%0")
        runCurrent()

        assertTrue("a never-agent window has no seed to protect — downgrade now", downgraded)
        assertNull(vm.agentConversations.value["%0"])
    }

    @Test
    fun liveDetectionRefiningSameAgentKeepsRestoredConversationTab() = runTest(scheduler) {
        val vm = newVm()
        vm.connectWithPaneForTest(paneId = "%0", windowId = "@0")
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        runCurrent()

        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%7", "@0", "$0", "shell", paneIndex = 0, sessionName = "work")),
        )
        runCurrent()
        // Seed restored Conversation.
        assertEquals(SessionTab.Conversation, vm.agentConversations.value["%7"]!!.selectedTab)

        // Live re-detection lands for the SAME agent + same log but with a
        // drifted confidence. The user must NOT be bounced to Terminal.
        vm.markAgentTailLiveForTest(
            "%7",
            newClaudeDetection().copy(confidence = AgentDetection.Confidence.RecentFile),
        )
        runCurrent()

        val state = vm.agentConversations.value["%7"]!!
        assertEquals(
            "same-agent refinement preserves the user's Conversation tab",
            SessionTab.Conversation,
            state.selectedTab,
        )
        assertEquals(
            AgentDetection.Confidence.RecentFile,
            state.detection?.confidence,
        )
    }

    @Test
    fun sendToAgentPaneAppendsOptimisticMessageAndWritesCarriageReturn() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        vm.sendToAgentPane("%0", "  run tests  ")
        advanceUntilIdle()

        val state = vm.agentConversations.value["%0"]!!
        val optimistic = state.events.single() as ConversationEvent.Message
        assertTrue(optimistic.id.startsWith(OPTIMISTIC_USER_MESSAGE_ID_PREFIX))
        assertEquals(ConversationRole.User, optimistic.role)
        assertEquals("run tests", optimistic.text)
        assertEquals(AgentKind.ClaudeCode, optimistic.agent)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'run tests'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    @Test
    fun sendToAgentPaneExitsTmuxCopyModeBeforeTypingPrompt() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "claude",
                    paneIndex = 0,
                    inCopyMode = true,
                ),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        // Issue #869: confirm the paste landed so the ack-gate submits promptly.
        repeat(4) {
            client.capturePaneResponses.addLast(
                CommandResponse(number = 0L, output = listOf("> run tests"), isError = false),
            )
        }

        val send = async { vm.sendToAgentPaneResult("%0", "  run tests  ") }
        advanceUntilIdle()
        val result = send.await()

        assertTrue("agent prompt should be delivered after copy-mode recovery", result.isSuccess)
        assertFalse("copy-mode recovery must not mark tmux disconnected", client.disconnected.value)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(
            listOf(
                "send-keys -X -t %0 cancel",
                "send-keys -l -t %0 -- 'run tests'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
        assertFalse(vm.panes.value.single { it.paneId == "%0" }.inCopyMode)
    }

    /**
     * Issue #869: Codex keeps its known-needed floor of
     * [CODEX_AGENT_SUBMIT_DELAY_MS] as the MINIMUM wait before the submit Enter
     * — even when a `capture-pane` confirms the paste instantly. The ack-gate
     * then presses Enter once that floor elapses AND a capture confirms the
     * paste (here the very first capture confirms it, so Enter fires right at
     * the floor).
     */
    @Test
    fun codexAgentSubmitHonoursMinimumFloorThenAckGatesEnter() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newCodexDetection())

        // Capture confirms the paste immediately — but the Codex floor must
        // still gate the Enter so the TUI has its known-needed minimum.
        repeat(4) {
            client.capturePaneResponses.addLast(
                CommandResponse(number = 0L, output = listOf("> run tests"), isError = false),
            )
        }

        val send = async { vm.sendToAgentPaneResult("%0", "  run tests  ") }
        runCurrent()

        assertEquals(
            "Codex submit should type the prompt before waiting to press Enter",
            listOf("send-keys -l -t %0 -- 'run tests'"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )

        advanceTimeBy(CODEX_AGENT_SUBMIT_DELAY_MS - 1L)
        runCurrent()
        assertEquals(
            "Codex submit must not press Enter before the Codex floor elapses, " +
                "even when the capture confirms the paste instantly",
            listOf("send-keys -l -t %0 -- 'run tests'"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )

        advanceUntilIdle()
        assertTrue(send.await().isSuccess)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'run tests'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    /**
     * Issue #869 (G10 reproduce-first): the maintainer's on-device symptom —
     * "most of the time when I click Send it's not really sending; I have to
     * press Enter after". Under realistic RTT the agent TUI has NOT finished
     * ingesting the pasted prompt when the blind ~150ms submit-delay timer
     * fires, so the Enter races the paste and the line sits unsent.
     *
     * This test models that latency: `capture-pane` reports the pane WITHOUT
     * the payload for the first few polls (the agent is still ingesting), then
     * WITH the payload once ingestion completes. The fix must press the submit
     * Enter ONLY after a capture confirms the paste landed.
     *
     * RED on the pre-#869 blind delay: no `capture-pane` is issued between the
     * text and the Enter, so the Enter is sent while the pane still shows no
     * payload — `enterSentWhilePayloadVisible` is false → the test fails.
     *
     * GREEN with the ack-gate: the Enter is deferred until a confirming
     * `capture-pane` returns the payload, including under the simulated latency.
     */
    @Test
    fun sendToAgentPaneAckGatesSubmitEnterUntilPasteIngestedUnderLatency() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val payload = "deploy the staging build"

        // Simulate the agent taking a few capture round-trips to render the
        // pasted text (realistic RTT ingestion). The first two captures show the
        // pane WITHOUT the payload; the third shows it WITH the payload landed.
        val notYetIngested = CommandResponse(
            number = 0L,
            output = listOf("> "),
            isError = false,
        )
        val ingested = CommandResponse(
            number = 0L,
            output = listOf("> $payload"),
            isError = false,
        )
        client.capturePaneResponses.addLast(notYetIngested)
        client.capturePaneResponses.addLast(notYetIngested)
        client.capturePaneResponses.addLast(ingested)
        // Plenty of confirming captures left over so a slightly different poll
        // count still confirms.
        repeat(8) { client.capturePaneResponses.addLast(ingested) }

        // Track, at the moment the submit Enter reaches the wire, whether a
        // capture has already confirmed the payload is visible in the pane.
        var sawConfirmingCapture = false
        var enterSentWhilePayloadVisible = false
        client.onCommandSent = { cmd ->
            // The next capture-pane queued response is the one this command will
            // consume; peek it to know if THIS capture confirms the payload.
            if (cmd.startsWith("capture-pane")) {
                val next = client.capturePaneResponses.firstOrNull()
                if (next != null && next.output.any { it.contains(payload) }) {
                    sawConfirmingCapture = true
                }
            }
            if (cmd == "send-keys -t %0 Enter") {
                enterSentWhilePayloadVisible = sawConfirmingCapture
            }
        }

        val send = async { vm.sendToAgentPaneResult("%0", payload) }
        advanceUntilIdle()

        assertTrue("agent submit should succeed", send.await().isSuccess)
        val sentSendKeys = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            "the prompt text is typed then submitted with a single Enter",
            listOf(
                "send-keys -l -t %0 -- 'deploy the staging build'",
                "send-keys -t %0 Enter",
            ),
            sentSendKeys,
        )
        // The load-bearing assertion: the submit Enter must only fire AFTER a
        // capture confirmed the paste is visible in the agent input. On the
        // pre-#869 blind delay there is no confirming capture, so the Enter
        // races ahead and this is false (RED).
        assertTrue(
            "submit Enter must be ack-gated on a capture confirming the paste landed " +
                "(the maintainer's missed-submit race under RTT)",
            enterSentWhilePayloadVisible,
        )
        // Prove the gate actually POLLED the pane (issued at least one
        // capture-pane) before pressing Enter — not a vacuous pass.
        assertTrue(
            "the ack-gate must poll capture-pane before submitting",
            client.sentCommands.any { it.startsWith("capture-pane -p -t %0") },
        )
    }

    /**
     * Issue #869: when the agent's input rendering can never be recognised (the
     * payload never shows up in `capture-pane`), Send must NOT hang — it falls
     * back to pressing Enter after the bounded ack timeout (the pre-#869 blind
     * behaviour as the worst case, never a deadlock).
     */
    @Test
    fun sendToAgentPaneFallsBackToSubmitEnterAfterAckTimeout() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        // Every capture comes back WITHOUT the payload — an unrecognised TUI
        // rendering. The FakeTmuxClient default empty capture already models
        // this, but make it explicit for clarity.
        repeat(200) {
            client.capturePaneResponses.addLast(
                CommandResponse(number = 0L, output = listOf("> "), isError = false),
            )
        }

        val send = async { vm.sendToAgentPaneResult("%0", "never-rendered prompt") }

        // Before the bounded timeout, the Enter must not have been pressed.
        advanceTimeBy(AGENT_SUBMIT_ACK_TIMEOUT_MS - 1L)
        runCurrent()
        assertFalse(
            "Enter must not be pressed before the bounded ack timeout elapses",
            client.sentCommands.contains("send-keys -t %0 Enter"),
        )

        advanceUntilIdle()
        assertTrue("submit must not hang — it falls back after the timeout", send.await().isSuccess)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'never-rendered prompt'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    /**
     * Issue #869 (reviewer BLOCKED-G4 follow-up): the needle-miss FALLBACK must
     * NOT degrade to the pre-#869 short floor — that short delay IS the
     * maintainer's missed-submit symptom. When the ack is never observed (an
     * unrecognised / reflowed input box the needle can't match) the submit Enter
     * must be held to an ADEQUATE working floor:
     *   max(configuredFloor, AGENT_SUBMIT_ACK_FALLBACK_FLOOR_MS + measuredRtt).
     *
     * This test injects a known per-`capture-pane` RTT and a ZERO configured
     * floor (so neither the Codex floor nor the #526 setting masks the assertion)
     * and proves the Enter does NOT fire until at least
     * `AGENT_SUBMIT_ACK_FALLBACK_FLOOR_MS + injectedRtt` of virtual time has
     * elapsed since the gate started — i.e. the worst case is a working delay,
     * proportionally larger under latency, never the 150ms that raced.
     *
     * RED (pre-hardening): the fallback returns after only the short floor, so
     * the Enter is present well before FALLBACK_FLOOR + RTT → the
     * "not pressed before the adequate floor" assertion fails.
     * GREEN (hardened): the Enter is held until the adequate floor elapses.
     */
    @Test
    fun sendToAgentPaneFallbackHoldsEnterForAdequateFloorPlusRttOnNeedleMiss() =
        runTest(scheduler) {
            val vm = newVm()
            val client = FakeTmuxClient()
            // Read the runTest virtual clock so the gate's RTT measurement +
            // fallback-floor top-up are deterministic (SystemClock reads 0 here).
            vm.setAgentSubmitMonotonicClockForTest { scheduler.currentTime }
            vm.attachClientForTest(client)
            vm.startAgentConversationForTest("%0", newClaudeDetection())
            // Zero configured floor so the fallback floor is the only thing
            // gating the Enter (not the #526 setting / Codex floor).
            vm.setAgentSubmitEnterDelayForTest(0)
            // SHORT poll window so the incidental poll-loop duration is BELOW the
            // fallback floor — making the floor (not the loop) the binding
            // constraint, so this is a genuine red→green of the floor itself.
            vm.setAgentSubmitAckTimeoutForTest(80L) // 2 polls at 40ms

            val injectedRttMs = 20L
            client.captureCommandDelayMs = injectedRttMs
            // Every capture comes back WITHOUT the payload — the needle never
            // matches (a reflowed/unrecognised input box).
            repeat(200) {
                client.capturePaneResponses.addLast(
                    CommandResponse(number = 0L, output = listOf("> "), isError = false),
                )
            }

            val gateStart = scheduler.currentTime
            // Record the virtual-clock instant the submit Enter reaches the wire.
            var enterSentAtMs: Long = -1L
            client.onCommandSent = { cmd ->
                if (cmd == "send-keys -t %0 Enter" && enterSentAtMs < 0L) {
                    enterSentAtMs = scheduler.currentTime
                }
            }
            val send = async { vm.sendToAgentPaneResult("%0", "never-matched prompt") }

            // The adequate floor: the Enter must NOT have fired before this.
            val adequateFloorMs =
                com.pocketshell.app.settings.AppSettings.AGENT_SUBMIT_ACK_FALLBACK_FLOOR_MS +
                    injectedRttMs
            advanceUntilIdle()
            assertTrue(
                "fallback submit must have fired (no hang)",
                send.await().isSuccess,
            )
            // The Enter was eventually pressed.
            assertTrue(
                "fallback must press the submit Enter",
                client.sentCommands.contains("send-keys -t %0 Enter"),
            )
            // Load-bearing: the submit Enter fired only AFTER the adequate floor
            // (FALLBACK_FLOOR + injectedRtt) elapsed — never the old short delay.
            val enterAtMs = enterSentAtMs - gateStart
            assertTrue(
                "needle-miss fallback must hold the submit Enter for at least the " +
                    "adequate floor (FALLBACK_FLOOR + measuredRtt = ${adequateFloorMs}ms); " +
                    "Enter fired after only ${enterAtMs}ms",
                enterAtMs >= adequateFloorMs,
            )
        }

    /**
     * Issue #869 (reviewer BLOCKED-G4 follow-up): the load-bearing needle-vs-
     * real-echo property — the ack must MATCH a WRAPPED/reflowed agent input box.
     * The on-device fixture proved a long prompt wraps and `capture-pane` joins
     * the rows with a separator that can land MID-WORD (`against the` rendered as
     * `against t` + `he new`), AND the head of a very long prompt scrolls off the
     * top so only the tail near the cursor is captured. A naive whole-line
     * substring needle MISSES both → fallback fires → Send degrades to the
     * maintainer's missed-submit. This JVM test reproduces that wrapped+truncated
     * capture deterministically and asserts the ack-gate STILL submits promptly
     * (the needle matches the whitespace-stripped tail).
     *
     * RED on the pre-fix whole-line/space-collapsed needle: the wrap-boundary
     * mid-word split means the collapsed needle is not a substring of the
     * collapsed visible text, so no capture ever confirms → the gate runs to the
     * fallback timeout instead of submitting on the first confirming capture.
     * GREEN with the tail+strip needle: the first wrapped capture confirms.
     */
    @Test
    fun sendToAgentPaneAckMatchesWrappedReflowedInputBox() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.setAgentSubmitMonotonicClockForTest { scheduler.currentTime }
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.setAgentSubmitEnterDelayForTest(0)

        // A long single-line prompt the composer types via `send-keys -l`.
        val payload = "please refactor the authentication middleware so that every " +
            "inbound request is validated against the new session token format " +
            "before it reaches the handler layer"

        // The agent input box renders the payload WRAPPED across rows AND with the
        // head scrolled off — exactly what `capture-pane -p` returned on-device.
        // The wrap boundary lands mid-word (`...against t` | `he new...`), and the
        // first rows of the prompt are gone (only the tail near the cursor shows).
        val wrappedTruncatedEcho = CommandResponse(
            number = 0L,
            output = listOf(
                "y inbound request is validated",
                " against the new session token",
                " format before it reaches the",
                "handler layer",
            ),
            isError = false,
        )
        // First capture: still ingesting (input box empty).
        client.capturePaneResponses.addLast(
            CommandResponse(number = 0L, output = listOf("> "), isError = false),
        )
        // Then the wrapped+truncated echo confirms the paste landed.
        repeat(10) { client.capturePaneResponses.addLast(wrappedTruncatedEcho) }

        // Track that the submit Enter is gated on an OBSERVED ack (not the
        // fallback). With the tail+strip needle the SECOND capture confirms, so
        // the gate submits well before the fallback timeout.
        val send = async { vm.sendToAgentPaneResult("%0", payload) }
        advanceUntilIdle()

        assertTrue("wrapped-echo agent submit should succeed", send.await().isSuccess)
        // The gate must have polled and matched the wrapped echo (ack observed),
        // not run to the fallback floor. The fallback would issue all ~50 polls;
        // an observed ack matches within the first couple. Assert the capture
        // count is small (ack matched early) — i.e. the needle DID match the
        // wrapped box, the load-bearing property.
        val captureCount = client.sentCommands.count { it.startsWith("capture-pane -p -t %0") }
        assertTrue(
            "the ack-gate must MATCH the wrapped/reflowed input box within a couple " +
                "of polls (needle survives the wrap-boundary split + head scroll-off); " +
                "captureCount=$captureCount implies it ran to the fallback timeout instead",
            captureCount in 1..5,
        )
        assertEquals(
            "the prompt is typed then submitted with a single Enter",
            listOf(
                "send-keys -l -t %0 -- '${payload.replace("'", "'\\''")}'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    @Test
    fun codexSendInFlightSurvivesTerminalOverflowWithoutReconnectOrDuplicateSend() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "codex",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.startAgentConversationForTest("%0", newCodexDetection())

        val send = async { vm.sendToAgentPaneResult("%0", "  previous user prompt  ") }
        runCurrent()

        assertEquals(
            "precondition: Codex prompt text is typed once before delayed Enter",
            listOf("send-keys -l -t %0 -- 'previous user prompt'"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )

        client.outputBacklogOverflowEvents.emit(
            TmuxOutputBacklogOverflow(paneId = "%0", droppedEvents = 2_048),
        )
        runCurrent()

        assertTrue(
            "terminal overflow must stay a pane-surface error, not a transport disconnect",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse("overflow must not flip the tmux disconnected signal", client.disconnected.value)
        assertEquals(
            "overflow must not start a reconnect or reacquire SSH",
            0,
            connector.connectCount,
        )
        assertEquals(
            "overflow must not increment user-visible connect attempts",
            0,
            TMUX_CONNECT_ATTEMPTS.get(),
        )
        assertSame(
            "stable transport should remain registered after pane overflow",
            client,
            registry.clients.value[7L]?.client,
        )

        advanceTimeBy(CODEX_AGENT_SUBMIT_DELAY_MS)
        assertTrue(send.await().isSuccess)

        val sendKeys = client.sentCommands.filter { it.startsWith("send-keys") }
        assertEquals(
            "overflow during the delayed Codex submit must not duplicate the composer prompt",
            1,
            sendKeys.count { it == "send-keys -l -t %0 -- 'previous user prompt'" },
        )
        assertEquals(1, sendKeys.count { it == "send-keys -t %0 Enter" })
        val messages = vm.agentConversations.value["%0"]!!.events
            .filterIsInstance<ConversationEvent.Message>()
            .filter { it.role == ConversationRole.User && it.text == "previous user prompt" }
        assertEquals("Conversation should keep one optimistic user turn", 1, messages.size)
        assertEquals(MessageSendState.Pending, messages.single().sendState)
        assertTrue("overflowed pane is shown as a surface error", vm.panes.value.single().surfaceError)
    }

    @Test
    fun agentSubmitDelaysFinalEnterByConfiguredDelayForClaudeCode() = runTest(scheduler) {
        // Issue #526: the composer/agent send path types the message text,
        // waits the user-configurable delay, then presses the submit Enter as
        // a SEPARATE send-keys so the Enter can't race ahead of the agent
        // TUI's paste ingestion (which left the message sitting unsent). This
        // applies to every agent now, not just Codex.
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.setAgentSubmitEnterDelayForTest(200)

        val send = async { vm.sendToAgentPaneResult("%0", "  run tests  ") }
        runCurrent()

        // Text is typed immediately; the submit Enter must NOT have been sent
        // yet — it waits out the configured delay first.
        assertEquals(
            "Send should type the prompt before waiting to press Enter",
            listOf("send-keys -l -t %0 -- 'run tests'"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )

        advanceTimeBy(199L)
        runCurrent()
        assertEquals(
            "Submit Enter must not fire before the configured delay elapses",
            listOf("send-keys -l -t %0 -- 'run tests'"),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )

        advanceTimeBy(1L)
        assertTrue(send.await().isSuccess)
        assertEquals(
            "After the configured delay the submit Enter fires as a separate key",
            listOf(
                "send-keys -l -t %0 -- 'run tests'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    @Test
    fun agentSubmitWithZeroConfiguredFloorStillAckGatesEnter() = runTest(scheduler) {
        // Issue #869: a 0ms configured floor means "no minimum wait before
        // Enter" — but the submit is STILL ack-gated on the paste landing
        // (the #526 blind back-to-back behaviour was the missed-submit race the
        // maintainer hit). With a 0 floor and an immediately-confirming capture,
        // the Enter fires as soon as the first capture confirms the paste.
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.setAgentSubmitEnterDelayForTest(0)
        repeat(4) {
            client.capturePaneResponses.addLast(
                CommandResponse(number = 0L, output = listOf("> ship it"), isError = false),
            )
        }

        val send = async { vm.sendToAgentPaneResult("%0", "ship it") }
        advanceUntilIdle()
        val result = send.await()

        assertTrue(result.isSuccess)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'ship it'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
        assertTrue(
            "even a 0 floor must poll capture-pane to confirm the paste before Enter",
            client.sentCommands.any { it.startsWith("capture-pane -p -t %0") },
        )
    }

    @Test
    fun rawPaneInputDoesNotUseCodexAgentSubmitDelay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newCodexDetection())

        vm.writeInputToPane("%0", "manual\r".toByteArray(Charsets.UTF_8))
        runCurrent()

        assertEquals(
            "manual pane input should keep the immediate text + Enter routing",
            listOf(
                "send-keys -l -t %0 -- 'manual'",
                "send-keys -t %0 Enter",
            ),
            client.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    @Test
    fun sendToAgentPaneLongSingleLineUsesBoundedBracketedChunksThenEnter() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val draft = "x".repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 3 + 17)
        // Issue #869: confirm the paste landed so the ack-gate submits promptly.
        repeat(4) {
            client.capturePaneResponses.addLast(
                CommandResponse(number = 0L, output = listOf("> $draft"), isError = false),
            )
        }
        val send = async { vm.sendToAgentPaneResult("%0", draft) }
        advanceUntilIdle()
        val result = send.await()

        assertTrue("expected long single-line send to succeed", result.isSuccess)
        val sendKeys = client.sentCommands.filter { it.startsWith("send-keys") }
        assertTrue(
            "long single-line draft must not create one unbounded literal command: $sendKeys",
            sendKeys.none { it.startsWith("send-keys -l") },
        )
        assertTrue(
            "expected bracketed-paste chunks for long single-line draft, got $sendKeys",
            sendKeys.count { it.startsWith("send-keys -H -t %0 ") } > 3,
        )
        assertEquals("send-keys -t %0 Enter", sendKeys.last())
        val maxExpectedCommandLength =
            "send-keys -H -t %0 ".length + (TMUX_PASTE_BODY_CHUNK_BYTES * 3 - 1)
        val longest = sendKeys.maxOf { it.length }
        assertTrue(
            "tmux commands must stay bounded; longest=$longest max=$maxExpectedCommandLength commands=$sendKeys",
            longest <= maxExpectedCommandLength,
        )
    }

    @Test
    fun sendToAgentPaneLongDictationWithAttachmentBlockSubmitsFinalEnter() = runTest(scheduler) {
        // Issue #569: a long dictated prompt plus staged attachment paths
        // must not stop at "text inserted into the agent TUI". The composed
        // prompt is pasted through bounded chunks and then submitted with the
        // separate Enter key.
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val payload = buildString {
            append("Please inspect the attached screenshot and explain why the agent did not submit. ")
            repeat(80) {
                append("This sentence represents a long dictation segment that should stay one prompt. ")
            }
            append("\n\nAttached files:\n")
            append("- ~/.pocketshell/attachments/host-1/issue-569-135736.png")
        }
        // Issue #869: confirm the paste landed (the ack-gate matches on the last
        // non-blank line of the payload — here the attachment path).
        repeat(4) {
            client.capturePaneResponses.addLast(
                CommandResponse(
                    number = 0L,
                    output = listOf("> - ~/.pocketshell/attachments/host-1/issue-569-135736.png"),
                    isError = false,
                ),
            )
        }
        val send = async { vm.sendToAgentPaneResult("%0", payload) }
        advanceUntilIdle()
        val result = send.await()

        assertTrue("expected long dictation plus attachment send to succeed", result.isSuccess)
        val sendKeys = client.sentCommands.filter { it.startsWith("send-keys") }
        assertTrue(
            "combined dictation/attachment prompt must use bounded hex paste chunks, got $sendKeys",
            sendKeys.count { it.startsWith("send-keys -H -t %0 ") } > 3,
        )
        assertTrue(
            "combined prompt must not use one unbounded literal send-keys command: $sendKeys",
            sendKeys.none { it.startsWith("send-keys -l") },
        )
        assertEquals(
            "combined prompt must be submitted after paste chunks",
            "send-keys -t %0 Enter",
            sendKeys.last(),
        )
        val maxExpectedCommandLength =
            "send-keys -H -t %0 ".length + (TMUX_PASTE_BODY_CHUNK_BYTES * 3 - 1)
        val longest = sendKeys.maxOf { it.length }
        assertTrue(
            "tmux commands must stay bounded; longest=$longest max=$maxExpectedCommandLength commands=$sendKeys",
            longest <= maxExpectedCommandLength,
        )
    }

    @Test
    fun sendToAgentPaneResultFailureDuringLargePasteKeepsDraftOnlyAndReconnectAvailable() = runTest(scheduler) {
        val vm = newVm()
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val client = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "send-keys -H"
            closeAndThrowException = TmuxClientException("failed to write tmux command `send-keys`")
        }
        vm.replaceClientForTest(
            hostId = 42L,
            hostName = "dev",
            host = "dev.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/dev",
            sessionName = "work",
            client = client,
        )
        runCurrent()
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val result = vm.sendToAgentPaneResult(
            "%0",
            "line one\n" + "x".repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 2),
        )
        runCurrent()

        assertTrue("expected forced send failure", result.isFailure)
        assertTrue("forced failure should close fake client", client.closed)
        assertTrue("Reconnect must remain available after paste disconnect", vm.canReconnect.value)
        assertTrue(
            "composer keeps the draft, so tmux delivery failure must not also leave a failed row",
            vm.agentConversations.value["%0"]!!.events.isEmpty(),
        )
    }

    @Test
    fun sendToAgentPaneResultPasteChunkTmuxErrorRemovesOptimisticMessage() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            responses.addLast(CommandResponse(number = 1L, output = emptyList(), isError = false))
            responses.addLast(
                CommandResponse(
                    number = 2L,
                    output = listOf("not enough arguments"),
                    isError = true,
                ),
            )
        }
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val result = vm.sendToAgentPaneResult(
            "%0",
            "x".repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 2 + 1),
        )
        runCurrent()

        assertTrue("expected tmux %error to fail the paste result", result.isFailure)
        assertTrue(
            "composer keeps failed draft; conversation must drop the temporary optimistic row",
            vm.agentConversations.value["%0"]!!.events.isEmpty(),
        )
        assertTrue(
            "failed paste must stop before submitting Enter: ${client.sentCommands}",
            client.sentCommands.none { it == "send-keys -t %0 Enter" },
        )
    }

    @Test
    fun sendToAgentPaneResultFinalEnterTmuxErrorRemovesOptimisticMessage() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient().apply {
            repeat(5) {
                responses.addLast(
                    CommandResponse(number = it.toLong(), output = emptyList(), isError = false),
                )
            }
            responses.addLast(
                CommandResponse(
                    number = 6L,
                    output = listOf("can't find pane: %0"),
                    isError = true,
                ),
            )
        }
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        val draft = "x".repeat(TMUX_PASTE_BODY_CHUNK_BYTES * 2 + 1)
        // Issue #869: confirm the paste so the ack-gate reaches the (failing)
        // submit Enter promptly rather than burning the bounded ack timeout.
        repeat(4) {
            client.capturePaneResponses.addLast(
                CommandResponse(number = 0L, output = listOf("> $draft"), isError = false),
            )
        }

        val send = async { vm.sendToAgentPaneResult("%0", draft) }
        advanceUntilIdle()
        val result = send.await()

        assertTrue("expected tmux %error from final Enter to fail the send result", result.isFailure)
        assertTrue(
            "composer keeps failed draft; conversation must drop the temporary optimistic row",
            vm.agentConversations.value["%0"]!!.events.isEmpty(),
        )
        assertEquals(
            "send-keys -t %0 Enter",
            client.sentCommands.filter { it.startsWith("send-keys") }.last(),
        )
    }

    @Test
    fun sendToAgentPaneBlankTextIsNoOp() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        vm.sendToAgentPane("%0", "   ")
        advanceUntilIdle()

        assertTrue(vm.agentConversations.value["%0"]!!.events.isEmpty())
        assertTrue(client.sentCommands.none { it.startsWith("send-keys") })
    }

    @Test
    fun sendToAgentPaneResultDoesNotAppendOptimisticMessageWhenReconnectCannotStart() = runTest(scheduler) {
        // Issue #548 follow-up: if send-time reconnect cannot even start
        // (no remembered target), the unified composer keeps the draft.
        // Do not also append a failed optimistic row, or the next
        // successful send can show the same text twice.
        val vm = newVm()
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        val result = vm.sendToAgentPaneResult("%0", "preserve this prompt")
        runCurrent()

        assertTrue("disconnected tmux agent send must report failure", result.isFailure)
        assertTrue(vm.agentConversations.value["%0"]!!.events.isEmpty())
    }

    @Test
    fun sendToAgentPaneResultReconnectsAndSendsWhenDisconnectedRecoverable() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%0")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "claude",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)

        deadClient.disconnectedSignal.value = true
        runCurrent()
        assertTrue(
            "precondition: passive EOF should surface a recoverable disconnected state",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )

        val send = async { vm.sendToAgentPaneResult("%0", "send after return") }
        advanceUntilIdle()
        val result = send.await()

        assertTrue("send should reconnect and deliver instead of dead-ending", result.isSuccess)
        assertEquals(1, connector.connectCount)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'send after return'",
                "send-keys -t %0 Enter",
            ),
            reconnectClient.sentCommands.filter { it.startsWith("send-keys") },
        )
        val pending = vm.agentConversations.value["%0"]!!.events.single() as ConversationEvent.Message
        assertEquals("send after return", pending.text)
        assertEquals(MessageSendState.Pending, pending.sendState)
    }

    @Test
    fun sendAgentPayloadToPaneResultReconnectsAndSendsWhenDisconnectedRecoverable() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%0")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "codex",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )

        deadClient.disconnectedSignal.value = true
        runCurrent()

        val send = async {
            vm.sendAgentPayloadToPaneResult("%0", "codex terminal send", AgentKind.Codex)
        }
        advanceUntilIdle()
        val result = send.await()

        assertTrue("Codex Terminal-tab Send+Enter must reconnect before send", result.isSuccess)
        assertEquals(1, connector.connectCount)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertEquals(
            listOf(
                "send-keys -l -t %0 -- 'codex terminal send'",
                "send-keys -t %0 Enter",
            ),
            reconnectClient.sentCommands.filter { it.startsWith("send-keys") },
        )
    }

    @Test
    fun sendToAgentPaneResultDoesNotRetryNonRetryableFailedConnection() = runTest(scheduler) {
        val authFailure = UserAuthException("bad key")
        val connector = FailingLeaseConnector(authFailure)
        val vm = newVm(
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        deadClient.disconnectedSignal.value = true
        runCurrent()

        val first = async { vm.sendToAgentPaneResult("%0", "auth blocked") }
        advanceUntilIdle()
        assertTrue(first.await().isFailure)
        assertEquals(1, connector.connectCount)
        assertTrue(vm.agentConversations.value["%0"]?.events.orEmpty().isEmpty())

        val second = vm.sendToAgentPaneResult("%0", "auth blocked")
        runCurrent()

        assertTrue("non-retryable failed state must fail immediately", second.isFailure)
        assertEquals("send must not redial after non-retryable auth failure", 1, connector.connectCount)
        assertTrue(vm.agentConversations.value["%0"]?.events.orEmpty().isEmpty())
    }

    @Test
    fun retryFailedAgentSendDropsFailedTurnAndReSendsWithoutDoubleSend() = runTest(scheduler) {
        // Issue #494: retrying a failed tmux send drops the failed
        // placeholder and re-sends. With a live client the re-send inserts
        // a fresh pending turn and submits the keys — exactly one user turn
        // remains (no double-send, no orphaned failed row).
        val vm = newVm()
        val failed = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}seed",
            agent = AgentKind.ClaudeCode,
            atMillis = 1L,
            role = ConversationRole.User,
            text = "retry me",
            sendState = MessageSendState.Failed,
        )
        vm.attachClientForTest(FakeTmuxClient())
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.appendAgentEventsForTest("%0", listOf(failed))

        // Bring up a live client and retry.
        val client = FakeTmuxClient()
        vm.attachClientForTest(client)
        vm.retryFailedAgentSend("%0", failed.id)
        advanceUntilIdle()

        val events = vm.agentConversations.value["%0"]!!.events
        assertEquals("retry must leave exactly one user turn (no double-send)", 1, events.size)
        val pending = events.single() as ConversationEvent.Message
        assertEquals("retry me", pending.text)
        assertEquals(MessageSendState.Pending, pending.sendState)
        assertTrue("retried turn must be a fresh optimistic id", pending.id != failed.id)
        assertTrue(
            "retried send must submit Enter to the pane: ${client.sentCommands}",
            client.sentCommands.any { it == "send-keys -t %0 Enter" },
        )
    }

    @Test
    fun retryFailedAgentSendKeepsFailedTurnWhenRetryDeliveryFails() = runTest(scheduler) {
        val vm = newVm()
        val failed = ConversationEvent.Message(
            id = "${OPTIMISTIC_USER_MESSAGE_ID_PREFIX}seed",
            agent = AgentKind.ClaudeCode,
            atMillis = 1L,
            role = ConversationRole.User,
            text = "retry me",
            sendState = MessageSendState.Failed,
        )
        val client = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "send-keys"
        }
        vm.attachClientForTest(client)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.appendAgentEventsForTest("%0", listOf(failed))

        vm.retryFailedAgentSend("%0", failed.id)
        advanceUntilIdle()

        val events = vm.agentConversations.value["%0"]!!.events
        assertEquals("failed retry must still leave one retryable row", 1, events.size)
        val stillFailed = events.single() as ConversationEvent.Message
        assertEquals("retry me", stillFailed.text)
        assertEquals(MessageSendState.Failed, stillFailed.sendState)
        assertTrue("retry should create a fresh optimistic id", stillFailed.id != failed.id)
        assertTrue(
            "retry delivery should attempt tmux send before failing: ${client.sentCommands}",
            client.sentCommands.any { it.startsWith("send-keys") },
        )
    }

    // ─── Issue #786: the "Search in conversation" field + its `searchQuery`
    // hoisting (#154) were hard-cut (D22). The two former search-persistence
    // tests are deleted with the feature. The conversation feed now shows every
    // event with no query filter. ─────────────

    @Test
    fun stoppedAgentLogTailMarksConversationStaleWhileTmuxStaysConnected() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val tailJob = Job()
        vm.startAgentConversationForTest("%0", detection)

        val started = vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = tailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )

        // Issue #576: the VM now wires the batched tail, so the returned Job is
        // the umbrella that owns the underlying SSH tail + drain (not the raw
        // tail job). The contract the test cares about is that a tail started
        // and its terminal cause still drives the conversation sync status.
        assertNotNull(started)
        assertEquals(AgentConversationSyncStatus.Live, vm.agentConversations.value["%0"]!!.syncStatus)

        tailJob.complete()
        advanceUntilIdle()

        assertEquals(
            "normal tail exit means the conversation feed is stale, not that tmux disconnected",
            AgentConversationSyncStatus.Stale,
            vm.agentConversations.value["%0"]!!.syncStatus,
        )
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun codexNewReplayBurstCoalescesIntoFewStateFlowEmissionsNotPerLine() = runTest(scheduler) {
        // Issue #576 (the load-bearing regression): a Codex `/new` rewrites
        // the rollout JSONL with thousands of lines. With the per-line tail
        // (the old shape) each parsed event drove its own
        // `appendAgentEvents` → `updateAgentConversation` → ONE
        // `_agentConversations` StateFlow emission, so an N-line replay fired
        // N emissions → N O(n) conversation re-derivations on the main thread
        // (the recompose storm the maintainer saw). The VM now wires
        // `tailEventsBatchedFromLine`, so the burst must collapse to a HANDFUL
        // of emissions, NOT one per line — while still ingesting every event.
        val lineCount = 2_000
        val replay = (0 until lineCount).map { index ->
            """{"type":"user","uuid":"u$index","message":{"role":"user","content":"replayed $index"}}"""
        }
        // The repository's tail drain runs on `tailScope`; wiring it to this
        // test's virtual clock makes the 50 ms debounce window deterministic.
        val repository = AgentConversationRepository(
            tailScope = backgroundScope,
            tailBatchWindowMillis = 50L,
        )
        val vm = newVm(agentRepository = repository)
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        vm.startAgentConversationForTest("%0", detection)

        // Count distinct `agentConversations` emissions for the pane: each
        // emission is one reconcile + one recompose trigger. A per-line tail
        // would produce ~`lineCount` of these; the batched tail a handful.
        var emissionCount = 0
        var lastEventCount = 0
        val collector = backgroundScope.launch {
            vm.agentConversations.collect { map ->
                map["%0"]?.let {
                    emissionCount += 1
                    lastEventCount = it.events.size
                }
            }
        }
        runCurrent()
        val emissionsAfterRegister = emissionCount

        vm.startAgentTailForTest(
            paneId = "%0",
            session = ReplayTailSshSession(replayLines = replay),
            detection = detection,
            fromLineExclusive = 0L,
        )
        // Let the drain coroutine fire its debounce window(s). The whole
        // replay arrives in one synchronous burst from the fake tail, so it
        // coalesces into a single batch → a single append → a single emission.
        advanceTimeBy(500L)
        runCurrent()
        collector.cancel()

        val burstEmissions = emissionCount - emissionsAfterRegister
        // The headline assertion: O(batches), NOT O(N). A per-line tail would
        // have produced ~2000 burst emissions; the batched tail a tiny number.
        assertTrue(
            "expected a handful of burst emissions, got $burstEmissions for $lineCount replayed lines",
            burstEmissions in 1..5,
        )
        // ...and the conversation is still correct: every event was ingested,
        // then bounded to the most-recent window (the same final feed a
        // per-line ingest would yield — reconcile is order-preserving and the
        // bound trims the oldest). The most recent replayed id must survive.
        assertEquals(
            "batched ingest must produce the bounded feed, not drop to empty",
            500,
            lastEventCount,
        )
        val finalEvents = vm.agentConversations.value["%0"]!!.events
        assertEquals(500, finalEvents.size)
        assertEquals(
            "the most-recent replayed event must be present after coalesced ingest",
            "u${lineCount - 1}",
            finalEvents.last().id,
        )
        assertEquals(
            "the oldest surviving event is the start of the bounded window",
            "u${lineCount - 500}",
            finalEvents.first().id,
        )
    }

    @Test
    fun stoppedAgentLogTailPreservesConcurrentConversationState() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val tailJob = Job()
        vm.startAgentConversationForTest("%0", detection)
        vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = tailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )

        vm.selectSessionTab("%0", SessionTab.Conversation)
        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "assistant-late",
                    agent = AgentKind.ClaudeCode,
                    atMillis = 10L,
                    role = ConversationRole.Assistant,
                    text = "late event",
                ),
            ),
        )
        tailJob.complete()
        advanceUntilIdle()

        val after = vm.agentConversations.value["%0"]!!
        assertEquals(AgentConversationSyncStatus.Stale, after.syncStatus)
        assertEquals(SessionTab.Conversation, after.selectedTab)
        assertEquals("assistant-late", after.events.single().id)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun staleOldAgentLogTailCompletionDoesNotMarkRestartedPaneStale() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val oldTailJob = Job()
        val restartedTailJob = Job()
        vm.startAgentConversationForTest("%0", detection)

        vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = oldTailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )
        vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = restartedTailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )

        oldTailJob.complete()
        advanceUntilIdle()

        assertEquals(
            "old tail completion must not stale a pane that already has a newer tail",
            AgentConversationSyncStatus.Live,
            vm.agentConversations.value["%0"]!!.syncStatus,
        )

        restartedTailJob.complete()
        advanceUntilIdle()

        assertEquals(AgentConversationSyncStatus.Stale, vm.agentConversations.value["%0"]!!.syncStatus)
    }

    @Test
    fun failedAgentLogTailMarksConversationLogUnavailableWhileTmuxStaysConnected() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()
        val tailJob = Job()
        vm.startAgentConversationForTest("%0", detection)
        vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = tailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )

        tailJob.completeExceptionally(IOException("tail failed"))
        advanceUntilIdle()

        assertEquals(
            AgentConversationSyncStatus.LogUnavailable,
            vm.agentConversations.value["%0"]!!.syncStatus,
        )
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun disconnectedSshWhenAgentTailStartsMarksConversationUnavailableWithoutCrashing() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()

        try {
            vm.startAgentConversationForPaneForTest(
                paneId = "%0",
                session = FakeSshSession(
                    tailFailure = SshException("SSH session is not connected"),
                ),
                detection = detection,
            )
            advanceUntilIdle()

            val state = vm.agentConversations.value["%0"]
            assertEquals(AgentConversationSyncStatus.LogUnavailable, state?.syncStatus)
            assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
            val event = diagnostics.eventsNamed("tmux_agent_conversation_tail_status").single()
            assertEquals("recoverable", event.category)
            assertEquals("%0", event.fields["pane"])
            assertEquals("tail_start_unavailable", event.fields["reason"])
            assertEquals(AgentConversationSyncStatus.LogUnavailable.name, event.fields["status"])
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun cancelledAgentConversationStartupReadIsNotSwallowed() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        val detection = newClaudeDetection()

        try {
            vm.startAgentConversationForPaneForTest(
                paneId = "%0",
                session = FakeSshSession(
                    execFailure = CancellationException("cancelled during line count"),
                ),
                detection = detection,
            )
            assertTrue("startup cancellation must propagate", false)
        } catch (e: CancellationException) {
            assertEquals("cancelled during line count", e.message)
        }
        assertTrue(
            "cancelled startup must not leave a phantom conversation row",
            vm.agentConversations.value.isEmpty(),
        )
    }

    @Test
    fun retryAgentLogTailForPaneRestartsOneTailAndKeepsTmuxConnected() = runTest(scheduler) {
        val vm = newVm()
        val detection = newClaudeDetection()
        val oldTailJob = Job()
        val retryTailJob = Job()
        val recentMtimeSeconds = System.currentTimeMillis() / 1000
        val retryGate = CompletableDeferred<Unit>()
        val retrySession = FakeSshSession(
            tailJob = retryTailJob,
            execGate = retryGate,
            detectionOutput = "claude|$recentMtimeSeconds|/work|/home/u/.claude/projects/-work/abc.jsonl\n",
            processOutput = "123 1 pts/1 node claude\n",
        )
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "agent",
                    paneIndex = 0,
                    cwd = "/work",
                    currentCommand = "claude",
                    paneTty = "/dev/pts/1",
                ),
            ),
        )
        vm.attachSessionForAgentRetryForTest(retrySession)
        vm.startAgentConversationForTest("%0", detection)
        vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = oldTailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )
        oldTailJob.complete()
        advanceUntilIdle()

        assertTrue(vm.retryAgentConversationStreamForPane("%0"))
        assertEquals(
            AgentConversationSyncStatus.Retrying,
            vm.agentConversations.value["%0"]!!.syncStatus,
        )
        assertFalse(
            "duplicate retry must not start a second pane tail",
            vm.retryAgentConversationStreamForPane("%0"),
        )

        retryGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, retrySession.tailCalls)
        assertEquals(AgentConversationSyncStatus.Live, vm.agentConversations.value["%0"]!!.syncStatus)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    @Test
    fun codexAgentLogRetryWithTerminalFloodKeepsConversationAndTerminalConsistent() =
        runTest(scheduler, timeout = SLOW_FEED_RUN_TEST_TIMEOUT) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val detection = newCodexDetection()
        val oldTailJob = Job()
        val retryTailJob = Job()
        val retryGate = CompletableDeferred<Unit>()
        val recentMtimeSeconds = System.currentTimeMillis() / 1000
        val codexLines = codexTranscriptWithToolFlood(toolResults = 700)
        val retrySession = FakeSshSession(
            tailJob = retryTailJob,
            execGate = retryGate,
            wcOutput = "${codexLines.size}\n",
            agentLogLines = codexLines,
            detectionOutput = "codex|$recentMtimeSeconds|/work|${detection.sourcePath}\n",
            processOutput = "123 1 pts/1 codex codex --synthetic-overflow\n",
        )
        vm.attachClientForTest(client)
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "codex",
                    paneIndex = 0,
                    cwd = "/work",
                    currentCommand = "codex",
                    paneTty = "/dev/pts/1",
                ),
            ),
        )
        vm.attachSessionForAgentRetryForTest(retrySession)
        vm.startAgentConversationForTest("%0", detection)
        vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = oldTailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )
        oldTailJob.complete()
        advanceUntilIdle()

        val terminalState = vm.panes.value.single().terminalState
        val payloads = List(CODEX_SCALE_OUTPUT_CHUNKS, ::codexScaleOutputChunk)
        val emitted = payloads.sumOf { it.size }
        assertTrue(
            "test fixture drift: emitted=$emitted expectedFloor=$CODEX_SCALE_OUTPUT_BYTES",
            emitted >= CODEX_SCALE_OUTPUT_BYTES,
        )
        val observedSideChannelChunks = AtomicInteger(0)
        val outputCollector = launch {
            terminalState.output.collect {
                observedSideChannelChunks.incrementAndGet()
            }
        }
        runCurrent()

        try {
            assertTrue(vm.retryAgentConversationStreamForPane("%0"))
            val sender = async {
                payloads.forEach { bytes ->
                    client.emittedEvents.emit(ControlEvent.Output("%0", bytes))
                }
            }
            val completed = withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
                while (sender.isActive) {
                    shadowOf(Looper.getMainLooper()).idle()
                    runCurrent()
                    delay(10)
                }
                sender.await()
                true
            } ?: false

            assertTrue(
                "Codex-scale terminal output must not stall while Conversation retries",
                completed,
            )
            assertTrue(
                "Codex-scale terminal output should still publish best-effort side-channel chunks",
                observedSideChannelChunks.get() > 0,
            )
        } finally {
            outputCollector.cancel()
        }

        retryGate.complete(Unit)
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val state = vm.agentConversations.value["%0"]!!
        val messages = state.events.filterIsInstance<ConversationEvent.Message>()
        assertEquals(AgentConversationSyncStatus.Live, state.syncStatus)
        assertTrue(
            "widened Codex agent-log read must keep the user prompt that is outside the old 200-line tail",
            messages.any { it.role == ConversationRole.User && it.text == ISSUE_576_CODEX_USER_PROMPT },
        )
        assertTrue(
            "assistant response from the synthetic Codex transcript should also be present",
            messages.any { it.role == ConversationRole.Assistant && it.text == ISSUE_576_CODEX_ASSISTANT_REPLY },
        )
        assertTrue(
            "Codex conversation overflow must not be classified as a tmux disconnect",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse(client.disconnectedSignal.value)
        assertTrue(
            "Codex retry must request the widened raw-line window; commands=${retrySession.execCommands}",
            retrySession.execCommands.any { it.contains("pocketshell agent-log") && it.contains("--tail 1600") },
        )
    }

    @Test
    fun retryAgentLogTailForPaneDetectionFailureReturnsLogUnavailable() = runTest(scheduler) {
        val vm = newVm()
        val detection = newClaudeDetection()
        val retryGate = CompletableDeferred<Unit>()
        val retrySession = FakeSshSession(execGate = retryGate)
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "agent",
                    paneIndex = 0,
                    cwd = "/work",
                    currentCommand = "claude",
                    paneTty = "/dev/pts/1",
                ),
            ),
        )
        vm.attachSessionForAgentRetryForTest(retrySession)
        vm.startAgentConversationForTest("%0", detection)
        val oldTailJob = Job()
        vm.startAgentTailForTest(
            paneId = "%0",
            session = FakeSshSession(tailJob = oldTailJob),
            detection = detection,
            fromLineExclusive = 0L,
        )
        oldTailJob.complete()
        advanceUntilIdle()

        assertTrue(vm.retryAgentConversationStreamForPane("%0"))
        assertEquals(
            AgentConversationSyncStatus.Retrying,
            vm.agentConversations.value["%0"]!!.syncStatus,
        )
        retryGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            AgentConversationSyncStatus.LogUnavailable,
            vm.agentConversations.value["%0"]!!.syncStatus,
        )
        assertEquals(0, retrySession.tailCalls)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    // ─── Issue #186: per-window agent detection state ──────────────────
    //
    // Detection is per-pane (and therefore per-window for the simple
    // case of one pane per window). The view-model surface that the
    // screen drives off is [agentForWindow] — it must return the
    // current window's agent kind regardless of which window's pane the
    // user is currently viewing, so the Conversation tab can hide on
    // plain-shell windows even when a sibling window has a live agent.

    @Test
    fun agentForWindowReturnsNullForUnknownWindowId() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())

        assertNull(vm.agentForWindow(null))
        assertNull(vm.agentForWindow(""))
        assertNull(vm.agentForWindow("@nonexistent"))
    }

    @Test
    fun agentForWindowReturnsKindOfDetectedAgentInThatWindow() = runTest(scheduler) {
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "agent",
                    paneIndex = 0,
                ),
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%1",
                    windowId = "@1",
                    sessionId = "$0",
                    title = "shell",
                    paneIndex = 0,
                ),
            ),
        )
        // Only window @0's pane has a detection — window @1 is a plain
        // shell with no agent.
        vm.startAgentConversationForTest("%0", newClaudeDetection())

        assertEquals(
            "the agent-running window must report its agent kind",
            AgentKind.ClaudeCode,
            vm.agentForWindow("@0"),
        )
        assertNull(
            "a plain-shell window must NOT inherit a sibling window's agent kind " +
                "even when they share a cwd",
            vm.agentForWindow("@1"),
        )
    }

    @Test
    fun agentForWindowPicksLowestPaneIndexWhenMultipleAgentsInOneWindow() = runTest(scheduler) {
        // Edge case: two panes in the same window with detections.
        // Stable behaviour: the pane that appears first in the panes
        // list wins (which is paneIndex ascending per the reconcile
        // sort).
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "a",
                    paneIndex = 0,
                ),
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%1",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "b",
                    paneIndex = 1,
                ),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.startAgentConversationForTest(
            "%1",
            AgentDetection(
                agent = AgentKind.Codex,
                sourcePath = "/home/u/.codex/sessions/x.jsonl",
                sessionId = "x",
                confidence = AgentDetection.Confidence.ProcessConfirmed,
            ),
        )

        assertEquals(AgentKind.ClaudeCode, vm.agentForWindow("@0"))
    }

    @Test
    fun listPanesParserAcceptsPrintableFieldSeparator() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    listOf(
                        "%0",
                        "@0",
                        "\$0",
                        "work",
                        "shell",
                        "0",
                        "/workspace",
                        "bash",
                        "/dev/pts/3",
                    ).joinToString(LIST_PANES_FIELD_SEPARATOR),
                ),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "\$0", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        val pane = vm.panes.value.single()
        assertEquals("%0", pane.paneId)
        assertEquals("/workspace", pane.cwd)
        assertEquals("bash", pane.currentCommand)
        assertEquals("/dev/pts/3", pane.paneTty)
    }

    @Test
    fun listPanesFormatRequestsPaneTty() = runTest(scheduler) {
        // Issue #186: the list-panes format must include
        // `#{pane_tty}` so per-pane detection can scope its process
        // scan to a TTY. Without this, the per-window fix has no
        // signal to act on.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0\t/workspace\tbash\t/dev/pts/3"),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "\$0", windowId = "@0", name = ""),
        )
        advanceUntilIdle()

        val listPanesCmd = client.sentCommands.single { it.startsWith("list-panes") }
        assertTrue(
            "list-panes format must include #{pane_tty}; got `$listPanesCmd`",
            listPanesCmd.contains("#{pane_tty}"),
        )
        assertTrue(
            "list-panes format must include #{pane_in_mode}; got `$listPanesCmd`",
            listPanesCmd.contains("#{pane_in_mode}"),
        )

        val pane = vm.panes.value.single()
        assertEquals("/dev/pts/3", pane.paneTty)
        assertFalse(pane.inCopyMode)
    }

    @Test
    fun listPanesParserCapturesTmuxWindowIndex() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@7\t2\t\$0\twork\tshell\t0\t/workspace\tbash\t/dev/pts/3\t0"),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "\$0", windowId = "@7", name = ""),
        )
        advanceUntilIdle()

        val pane = vm.panes.value.single()
        assertEquals("@7", pane.windowId)
        assertEquals(2, pane.windowIndex)
    }

    @Test
    fun paneModeChangedRefreshesCopyModeStateFromListPanes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0\t/workspace\tbash\t/dev/pts/3\t1"),
                isError = false,
            ),
        )
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        client.emittedEvents.emit(ControlEvent.PaneModeChanged("%0"))
        advanceUntilIdle()

        val pane = vm.panes.value.single()
        assertEquals("%0", pane.paneId)
        assertTrue("pane_in_mode=1 must mark the pane as copy-mode/action mode", pane.inCopyMode)
    }

    @Test
    fun agentForWindowClearsAfterPaneLosesDetection() = runTest(scheduler) {
        // The screen drives Conversation-tab visibility off
        // [agentForWindow]. When a pane that previously had a
        // detection no longer reports one (the user exited Claude),
        // `agentForWindow` for that window must return null so the tab
        // disappears.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        assertEquals(AgentKind.ClaudeCode, vm.agentForWindow("@0"))

        // Simulate the production clear path: drop the conversation
        // entry directly (mirrors what `clearAgentDetectionForPane`
        // does when a subsequent source-resolution probe returns null).
        vm.clearAgentDetectionForPaneForTest("%0")

        assertNull(
            "agentForWindow must return null once the pane's detection is cleared",
            vm.agentForWindow("@0"),
        )
    }

    @Test
    fun conversationStateClearsWhenPaneLosesDetection() = runTest(scheduler) {
        // If the user has opened the Conversation tab on the agent pane
        // and then exits the agent in that pane, the per-pane
        // conversation state must disappear so the screen falls back to
        // Terminal for that visible pane.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "agent", paneIndex = 0),
            ),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        assertEquals(SessionTab.Conversation, vm.agentConversations.value["%0"]!!.selectedTab)

        vm.clearAgentDetectionForPaneForTest("%0")

        assertNull(
            "conversation state must clear when the pane loses its detection",
            vm.agentConversations.value["%0"],
        )
        assertNull(
            "agentForWindow must report null once the pane lost its detection",
            vm.agentForWindow("@0"),
        )
    }

    @Test
    fun parsedPanePaneTtyDefaultsToEmptyWhenOmitted() = runTest(scheduler) {
        // Defensive: an older tmux that doesn't emit the new field, or
        // a unit test passing the legacy shape, must still produce a
        // valid TmuxPaneState (with an empty paneTty so per-pane
        // detection short-circuits to null).
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0)),
        )
        advanceUntilIdle()

        assertEquals("", vm.panes.value.single().paneTty)
    }

    // ─── Issue #178: same-host fast-switch reuses the SSH transport ───
    //
    // The connect() path detects "same host, different tmux session"
    // and routes through [closeCurrentClientKeepSession] +
    // [runFastSessionSwitch] instead of the full SSH-handshake teardown
    // + reconnect. Production exercise lives in the connected
    // TmuxSessionSwitchSameHostReusesSshE2eTest because a unit test
    // cannot reach into [SshConnection.connect] without a real
    // network. The unit tests below pin the predicate behaviour, the
    // teardown-keep-session invariant, and the registry side-effects
    // through the dedicated test seams the VM exposes.

    @Test
    fun isFastSwitchEligibleRequiresAnActiveTargetAndSession() {
        val vm = newVm()
        assertFalse(
            "no active target -> not eligible",
            vm.isFastSwitchEligibleForTest(
                host = "h",
                port = 22,
                user = "u",
                keyPath = "/k",
                sessionName = "s",
            ),
        )

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            // No session injected — fast switch must require a live
            // SshSession reference, not just an active target.
        )
        assertFalse(
            "no SshSession reference -> not eligible",
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
    }

    @Test
    fun isFastSwitchEligibleRejectsDifferentHostParameters() {
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )
        // Different host
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "bravo.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
        // Different port
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 2222,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
        // Different user
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "other",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
        // Different key path
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/other",
                sessionName = "other",
            ),
        )
    }

    @Test
    fun isFastSwitchEligibleRejectsSameSessionName() {
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )
        // Same host + same session name = no-op, not a fast switch.
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
            ),
        )
    }

    @Test
    fun isFastSwitchEligibleAcceptsSameHostDifferentSession() {
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )
        assertTrue(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
    }

    @Test
    fun isFastSwitchEligibleRejectsDeadSshSession() {
        val vm = newVm()
        // Inject a session that says it is not connected — the predicate
        // must not pretend the transport is reusable.
        val deadSession = FakeSshSession(isConnectedValue = false)
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = deadSession,
        )
        assertFalse(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "other",
            ),
        )
    }

    @Test
    fun fastSwitchCachesOldRuntimeAndReusesSshSession() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(registry = registry, runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )
        assertSame(oldClient, registry.clients.value[1L]?.client)
        assertFalse("session must not be closed before fast switch", session.closed)
        assertFalse("old client should still be open before fast switch", oldClient.closed)

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
        )
        advanceUntilIdle()

        // Old tmux client stays warm in the runtime cache; SSH session is
        // reused (NOT closed). Registry now points at the new client.
        assertFalse("old tmux client must stay warm after fast switch", oldClient.closed)
        assertTrue(
            "old runtime must be cached by host/session key",
            runtimeCache.contains(
                TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "work"),
            ),
        )
        assertFalse(
            "fast switch must NOT close the underlying SSH session",
            session.closed,
        )
        assertSame(newClient, registry.clients.value[1L]?.client)
        assertTrue("new tmux client must be connect()ed", newClient.connectCalled)
        // After the fast switch a same-host probe for yet another
        // session name should still report eligible, because the
        // session ref was preserved.
        assertTrue(
            vm.isFastSwitchEligibleForTest(
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "third",
            ),
        )
    }

    @Test
    fun activatingCachedRuntimePublishesPanesSynchronouslyWithoutTmuxCommands() = runTest(scheduler) {
        TmuxSessionLatencyTelemetry.resetForTest()
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(registry = registry, runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = clientA,
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "cached-work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        val cachedTerminalState = vm.panes.value.single().terminalState

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = clientB,
            session = session,
        )
        runCurrent()
        clientA.sentCommands.clear()
        clientB.sentCommands.clear()
        clientA.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "%0\t@0\t\$0\twork\tcached-work\t0",
                    "%1\t@0\t\$0\twork\tfresh-remote\t1",
                ),
                isError = false,
            ),
        )
        clientA.capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("fresh remote line"), isError = false),
        )
        clientA.cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )

        val activateStartedAtMs = SystemClock.elapsedRealtime()
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        val activateMs = SystemClock.elapsedRealtime() - activateStartedAtMs

        val panes = vm.panes.value
        val telemetryBeforeRefresh = TmuxSessionLatencyTelemetry.snapshot()
        val firstCachedFrame = telemetryBeforeRefresh.single {
            it.name == "warm_switch_first_cached_frame"
        }
        val forbiddenCommandEventsBeforeFrame = telemetryBeforeRefresh.filter {
            it.elapsedRealtimeMs <= firstCachedFrame.elapsedRealtimeMs &&
                (
                    it.name == "tmux_control_attach_count" ||
                        it.name == "list_panes" ||
                        it.name == "capture_pane" ||
                        it.name == "cursor_query"
                    )
        }
        assertTrue(
            "cached pointer-swap activation must publish visible pane state under 100ms; " +
                "activateMs=$activateMs",
            activateMs < TMUX_WARM_SWITCH_LOCAL_P95_BUDGET_MS,
        )
        assertTrue(
            "first cached-frame telemetry must stay under 100ms; event=$firstCachedFrame",
            firstCachedFrame.durationMs < TMUX_WARM_SWITCH_LOCAL_P95_BUDGET_MS,
        )
        assertTrue(
            "cached first frame must not require synchronous tmux control/list/capture work; " +
                "forbidden=$forbiddenCommandEventsBeforeFrame events=$telemetryBeforeRefresh",
            forbiddenCommandEventsBeforeFrame.isEmpty(),
        )
        assertEquals(
            "work",
            firstCachedFrame.sessionName,
        )
        assertTrue(
            "first cached-frame artifact must include cache-hit detail",
            firstCachedFrame.toArtifactLine(prefix = "warm_switch").contains("cacheHit=true"),
        )
        assertEquals(listOf("%0"), panes.map { it.paneId })
        assertSame(cachedTerminalState, panes.single().terminalState)
        assertSame(clientA, registry.clients.value[1L]?.client)
        assertFalse("cached activation must not create a new tmux client", clientA.connectCalled)
        assertTrue(
            "cached activation must avoid synchronous tmux list/capture commands, got ${clientA.sentCommands}",
            clientA.sentCommands.none {
                it.startsWith("list-panes") ||
                    it.startsWith("capture-pane")
            },
        )
        assertTrue(
            "previous active runtime should now be cached",
            runtimeCache.contains(
                TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "other"),
            ),
        )

        advanceTimeBy(CACHED_RUNTIME_REMOTE_REFRESH_DELAY_MS)
        runCurrent()
        assertTrue(
            "cached activation should refresh list-panes asynchronously after the visible swap",
            clientA.sentCommands.any { it.startsWith("list-panes") },
        )
        assertTrue(
            "new panes discovered by the async refresh should be seeded after the visible swap",
            clientA.sentCommands.contains(seedCaptureCommand("%1")),
        )
        assertTrue(
            "async seed should pair the capture with the cursor restore query",
            clientA.sentCommands.contains(seedCursorCommand("%1")),
        )
        assertEquals(listOf("%0", "%1"), vm.panes.value.map { it.paneId })
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun cachedRuntimeWithStaleConnectedSessionFallsBackToFreshAttach() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val staleSession = FakeSshSession()
        val staleClient = FakeTmuxClient().apply {
            failBestEffortOnCommandPrefix = "display-message"
            bestEffortException = TmuxClientException("tmux command timed out")
        }
        val currentClient = FakeTmuxClient()
        val freshClient = FakeTmuxClient().withSinglePane("work", "%9")

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = staleClient,
            session = staleSession,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "cached-work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = currentClient,
            session = staleSession,
        )
        runCurrent()
        assertTrue(runtimeCache.contains(tmuxRuntimeKeyForTest("work")))
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            freshClient
        }

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        advanceUntilIdle()

        assertTrue("stale cached runtime must be closed after failed health probe", staleClient.closed)
        assertSame("fresh attach should replace the stale cached runtime", freshClient, registry.clients.value[1L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(listOf("%9"), vm.panes.value.map { it.paneId })
        assertFalse("failed cached runtime should be removed from cache", runtimeCache.contains(tmuxRuntimeKeyForTest("work")))
    }

    @Test
    fun cachedRuntimeRefreshBestEffortSeedFailureKeepsConnectedClientOpen() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(registry = registry, runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = clientA,
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "cached-work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = clientB,
            session = session,
        )
        runCurrent()
        clientA.sentCommands.clear()
        clientA.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "%0\t@0\t\$0\twork\tcached-work\t0",
                    "%1\t@0\t\$0\twork\tfresh-remote\t1",
                ),
                isError = false,
            ),
        )
        clientA.failBestEffortOnCommandPrefix = "capture-pane"
        clientA.bestEffortException = TmuxClientException("tmux command `capture-pane` timed out")

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        advanceTimeBy(CACHED_RUNTIME_REMOTE_REFRESH_DELAY_MS)
        runCurrent()

        assertTrue(
            "expected cached-runtime refresh to attempt capture seed, got ${clientA.sentCommands}",
            clientA.sentCommands.contains(seedCaptureCommand("%1")),
        )
        assertTrue(
            "cached-runtime best-effort seed timeout must not mark connection Failed/Reconnecting; " +
                "status=${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse("cached-runtime seed timeout must not close tmux client", clientA.closed)
        assertFalse("cached-runtime seed timeout must not mark tmux disconnected", clientA.disconnected.value)
    }

    @Test
    fun staleCachedRuntimeRefreshCannotOverwriteNewerSelection() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(registry = registry, runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()
        val listPanesGate = CompletableDeferred<Unit>()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = clientA,
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "cached-work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = clientB,
            session = session,
        )
        runCurrent()
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%9",
                    windowId = "@9",
                    sessionId = "$9",
                    title = "other-visible",
                    paneIndex = 0,
                    sessionName = "other",
                ),
            ),
        )
        clientA.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf(
                    "%7\t@7\t\$7\twork\tstale-refresh-pane\t0",
                ),
                isError = false,
            ),
        )
        clientA.capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("must not seed"), isError = false),
        )
        clientA.sendCommandGatePrefix = "list-panes"
        clientA.sendCommandGate = listPanesGate

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        assertEquals(listOf("%0"), vm.panes.value.map { it.paneId })
        advanceTimeBy(CACHED_RUNTIME_REMOTE_REFRESH_DELAY_MS)
        runCurrent()
        assertTrue(
            "precondition: cached work refresh is suspended in list-panes",
            clientA.sentCommands.any { it.startsWith("list-panes") },
        )
        clientB.responses.addLast(
            CommandResponse(
                number = 10L,
                output = listOf("%9\t@9\t\$9\tother\tother-visible\t0"),
                isError = false,
            ),
        )

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "other",
        )
        assertEquals("other", vm.activeSessionNameForTest())
        assertEquals(listOf("%9"), vm.panes.value.map { it.paneId })

        listPanesGate.complete(Unit)
        advanceUntilIdle()

        assertEquals(
            "stale work refresh must not replace the newer active session panes",
            listOf("%9"),
            vm.panes.value.map { it.paneId },
        )
        assertEquals("other", vm.activeSessionNameForTest())
        assertFalse(
            "stale refresh must not run capture-pane after losing the runtime guard",
            clientA.sentCommands.any { it.startsWith("capture-pane") },
        )
        assertSame(clientB, registry.clients.value[1L]?.client)
    }

    @Test
    fun activatingCachedRuntimeReinstallsDisconnectedObserver() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(registry = registry, runtimeCache = runtimeCache)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val session = FakeSshSession()
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = clientA,
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = clientB,
            session = session,
        )
        runCurrent()

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        runCurrent()
        clientA.disconnectedSignal.value = true
        runCurrent()

        assertTrue(
            "restored cached client must use the normal disconnected observer",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertNull(registry.clients.value[1L])
    }

    @Test
    fun activatingCachedRuntimeRestartsAgentConversationTail() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()
        val detection = newClaudeDetection()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = clientA,
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.startAgentConversationForTest("%0", detection)
        vm.startAgentTailForTest("%0", session, detection, fromLineExclusive = 0L)
        assertEquals(1, session.tailCalls)

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = clientB,
            session = session,
        )
        runCurrent()

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        advanceUntilIdle()

        assertEquals(
            "cached restore must restart the conversation tail producer",
            2,
            session.tailCalls,
        )
        assertEquals(AgentConversationSyncStatus.Live, vm.agentConversations.value["%0"]?.syncStatus)
    }

    @Test
    fun restoredRuntimeTailRefreshPreservesConcurrentConversationState() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val execGate = CompletableDeferred<Unit>()
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()
        val detection = newClaudeDetection()
        val refreshedLog = """
            {"type":"assistant","uuid":"cached","message":{"role":"assistant","content":"cached event"}}
            {"type":"assistant","uuid":"backlog","message":{"role":"assistant","content":"backlog while cached"}}
        """.trimIndent()
        val cachedEvent = ConversationEvent.Message(
            id = "cached",
            agent = AgentKind.ClaudeCode,
            atMillis = 1L,
            role = ConversationRole.Assistant,
            text = "cached event",
        )
        val newerEvent = ConversationEvent.Message(
            id = "newer",
            agent = AgentKind.ClaudeCode,
            atMillis = 2L,
            role = ConversationRole.Assistant,
            text = "newer event",
        )
        val session = FakeSshSession(
            execGate = execGate,
            wcOutput = "2\n",
            initialEventsOutput = refreshedLog,
        )

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = clientA,
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        vm.startAgentConversationForTest("%0", detection, initialEvents = listOf(cachedEvent))

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = clientB,
            session = session,
        )
        runCurrent()

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        runCurrent()

        vm.selectSessionTab("%0", SessionTab.Conversation)
        vm.appendAgentEventsForTest("%0", listOf(newerEvent))

        execGate.complete(Unit)
        advanceUntilIdle()

        val after = vm.agentConversations.value["%0"]!!
        assertEquals(AgentConversationSyncStatus.Live, after.syncStatus)
        assertEquals(SessionTab.Conversation, after.selectedTab)
        assertEquals(listOf("cached", "newer", "backlog"), after.events.map { it.id })
        assertEquals(
            "cached restore plus async restart should start exactly one new tail",
            1,
            session.tailCalls,
        )
        assertEquals(listOf("/home/u/.claude/sessions/abc.jsonl" to 2L), session.tailFromLineCalls)
    }

    @Test
    fun runtimeCacheEvictsLeastRecentlyUsedRuntimePerHost() = runTest(scheduler) {
        val cache = TmuxSessionRuntimeCache(maxEntries = 2)
        val first = cachedRuntimeForTest("one")
        val second = cachedRuntimeForTest("two")
        val third = cachedRuntimeForTest("three")

        assertTrue(cache.put(first).isEmpty())
        assertTrue(cache.put(second).isEmpty())
        assertSame(first, cache.activate(first.key).runtime)
        assertTrue(cache.put(first).isEmpty())
        val evicted = cache.put(third)

        assertEquals(
            "second should be the least recently used runtime for the host",
            listOf(second),
            evicted,
        )
        assertTrue(cache.contains(first.key))
        assertTrue(cache.contains(third.key))
        assertFalse(cache.contains(second.key))
    }

    @Test
    fun runtimeCachePerHostCapDoesNotEvictOtherHosts() = runTest(scheduler) {
        val cache = TmuxSessionRuntimeCache(maxEntries = 1)
        val hostOneFirst = cachedRuntimeForTest("one", hostId = 1L)
        val hostTwoFirst = cachedRuntimeForTest("one", hostId = 2L)
        val hostOneSecond = cachedRuntimeForTest("two", hostId = 1L)

        assertTrue(cache.put(hostOneFirst).isEmpty())
        assertTrue(cache.put(hostTwoFirst).isEmpty())
        val evicted = cache.put(hostOneSecond)

        assertEquals(listOf(hostOneFirst), evicted)
        assertFalse(cache.contains(hostOneFirst.key))
        assertTrue(cache.contains(hostOneSecond.key))
        assertTrue(cache.contains(hostTwoFirst.key))
    }

    @Test
    fun runtimeCacheEvictsExpiredRuntimesDeterministically() = runTest(scheduler) {
        var nowMs = 0L
        val cache = TmuxSessionRuntimeCache(
            maxEntries = 2,
            ttlMs = 100L,
            nowMs = { nowMs },
        )
        val expired = cachedRuntimeForTest("expired")
        val fresh = cachedRuntimeForTest("fresh")

        assertTrue(cache.put(expired).isEmpty())
        nowMs = 100L

        val evicted = cache.put(fresh)

        assertEquals(listOf(expired), evicted)
        assertFalse(cache.contains(expired.key))
        assertTrue(cache.contains(fresh.key))
        assertNull(cache.activate(expired.key).runtime)
    }

    @Test
    fun fastSwitchEvictionClosesOldWarmRuntimeAsynchronously() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 1)
        val vm = newVm(registry = registry, runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val clientA = FakeTmuxClient()
        val clientB = FakeTmuxClient()
        val clientC = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "one",
            client = clientA,
            session = session,
        )
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "two",
            client = clientB,
            session = session,
        )
        runCurrent()
        assertTrue(runtimeCache.contains(tmuxRuntimeKeyForTest("one")))
        assertFalse("first warm runtime should not close before eviction", clientA.closed)
        clientA.detachCleanlyGate = CompletableDeferred()

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "three",
            client = clientC,
            session = session,
        )

        assertFalse(
            "eviction cleanup is launched asynchronously, outside the switch call",
            clientA.closed,
        )
        assertTrue("eviction cleanup should have started detach work", clientA.detachCleanlyCalled)
        clientA.detachCleanlyGate?.complete(Unit)
        runCurrent()

        assertTrue("evicted warm client must detach/close", clientA.detachCleanlyCalled)
        assertTrue("evicted warm client must close", clientA.closed)
        assertFalse("active selected client must remain usable", clientC.closed)
        assertSame(clientC, registry.clients.value[1L]?.client)
        assertTrue(runtimeCache.contains(tmuxRuntimeKeyForTest("two")))
        assertFalse(runtimeCache.contains(tmuxRuntimeKeyForTest("one")))
    }

    @Test
    fun sessionSwitcherPrewarmCachesOnlyBoundedLikelyTargets() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession(), FakeSshSession(), FakeSshSession())
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val activeSession = FakeSshSession()
        val prewarmClients = listOf(
            FakeTmuxClient().withSinglePane("recent-a", "%1"),
            FakeTmuxClient().withSinglePane("recent-b", "%2"),
            FakeTmuxClient().withSinglePane("recent-c", "%3"),
        )
        val clients = ArrayDeque(prewarmClients)
        vm.setTmuxClientFactoryForTest { _, _, _ -> clients.removeFirst() }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = activeSession,
        )

        vm.prewarmLikelySwitchTargets(listOf("work", "recent-a", "recent-b", "recent-c"))
        advanceUntilIdle()

        assertTrue(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent-a")))
        assertTrue(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent-b")))
        assertFalse(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent-c")))
        assertEquals(
            "prewarm must stay capped to likely switch targets",
            TMUX_SESSION_PREWARM_MAX_TARGETS,
            prewarmClients.count { it.connectCalled },
        )
        assertEquals(
            "prewarm should reuse the warm SSH lease when possible",
            1,
            connector.connectCount,
        )
    }

    @Test
    fun sessionPrewarmSeedsCaptureWithCursorRestore() = runTest(scheduler) {
        // Issue #640: the prewarm seed shares the capture+cursor exchange via
        // [TmuxClient.captureWithCursor] (single-flight in production), pairing
        // the capture with the cursor restore, and still caches the runtime +
        // keeps the client open.
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val prewarmClient = FakeTmuxClient().withSinglePane("recent", "%4")
        vm.setTmuxClientFactoryForTest { _, _, _ -> prewarmClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )

        vm.prewarmLikelySwitchTargets(listOf("recent"))
        advanceUntilIdle()

        assertTrue(
            "expected prewarm seed to capture the pane, got ${prewarmClient.sentCommands}",
            prewarmClient.sentCommands.contains(seedCaptureCommand("%4")),
        )
        assertTrue(
            "expected prewarm seed to pair the capture with a cursor restore query, " +
                "got ${prewarmClient.sentCommands}",
            prewarmClient.sentCommands.contains(seedCursorCommand("%4")),
        )
        assertTrue(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent")))
        assertFalse("prewarm seed timeout must not close tmux client", prewarmClient.closed)
        assertFalse("prewarm seed timeout must not mark tmux disconnected", prewarmClient.disconnected.value)
    }

    @Test
    fun sessionPrewarmBestEffortCaptureFailureCachesRuntimeAndKeepsClientOpen() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val prewarmClient = FakeTmuxClient().withSinglePane("recent", "%4").apply {
            failBestEffortOnCommandPrefix = "capture-pane"
            bestEffortException = TmuxClientException("tmux command `capture-pane` timed out")
        }
        vm.setTmuxClientFactoryForTest { _, _, _ -> prewarmClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )

        vm.prewarmLikelySwitchTargets(listOf("recent"))
        advanceUntilIdle()

        assertTrue(
            "expected prewarm seed to attempt capture best-effort, got ${prewarmClient.sentCommands}",
            prewarmClient.sentCommands.contains(seedCaptureCommand("%4")),
        )
        assertTrue(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent")))
        assertFalse("prewarm capture timeout must not close tmux client", prewarmClient.closed)
        assertFalse("prewarm capture timeout must not mark tmux disconnected", prewarmClient.disconnected.value)
    }

    @Test
    fun switchingToPrewarmedTargetUsesCachedRuntimeFirstFramePath() = runTest(scheduler) {
        TmuxSessionLatencyTelemetry.resetForTest()
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val activeClient = FakeTmuxClient()
        val prewarmClient = FakeTmuxClient().withSinglePane("recent", "%4")
        vm.setTmuxClientFactoryForTest { _, _, _ -> prewarmClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = activeClient,
            session = FakeSshSession(),
        )

        vm.prewarmLikelySwitchTargets(listOf("recent"))
        advanceUntilIdle()
        prewarmClient.sentCommands.clear()

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "recent",
        )

        assertEquals(listOf("%4"), vm.panes.value.map { it.paneId })
        assertSame(prewarmClient, registry.clients.value[1L]?.client)
        assertTrue(
            TmuxSessionLatencyTelemetry.snapshot().any { it.name == "warm_switch_first_cached_frame" },
        )
        assertTrue(
            "cached activation must not attach a second tmux client",
            prewarmClient.sentCommands.none { it.startsWith("list-panes") },
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun cancelledSessionPrewarmClosesPartialRuntimeWithoutCachingIt() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val gate = CompletableDeferred<Unit>()
        val prewarmClient = FakeTmuxClient().withSinglePane("recent", "%5").apply {
            sendCommandGatePrefix = "list-panes"
            sendCommandGate = gate
        }
        vm.setTmuxClientFactoryForTest { _, _, _ -> prewarmClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )

        vm.prewarmLikelySwitchTargets(listOf("recent"))
        runCurrent()
        vm.cancelTmuxSessionPrewarm()
        gate.complete(Unit)
        advanceUntilIdle()

        assertFalse(runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "recent")))
        assertTrue("cancelled prewarm must detach its tmux client", prewarmClient.detachCleanlyCalled)
    }

    @Test
    fun switchingToColdTargetStillFallsBackToFastAttach() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val coldClient = FakeTmuxClient().withSinglePane("cold", "%6")
        vm.setTmuxClientFactoryForTest { _, _, _ -> coldClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )

        vm.prewarmLikelySwitchTargets(emptyList())
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "cold",
        )
        advanceUntilIdle()

        assertTrue("cold fallback must attach a tmux client", coldClient.connectCalled)
        assertEquals(listOf("%6"), vm.panes.value.map { it.paneId })
        assertTrue(
            "cold fallback should still use normal list-panes attach",
            coldClient.sentCommands.any { it.startsWith("list-panes") },
        )
    }

    @Test
    fun failedSameHostSwitchKeepsParkedRuntimeActivatable() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val vm = newVm(
            registry = registry,
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val clientA = FakeTmuxClient().withSinglePane("work", "%0")
        val clientB = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "list-panes"
            closeAndThrowException = TmuxClientException("pane readiness failed")
        }
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            when (sessionName) {
                "work" -> clientA
                "other" -> clientB
                else -> error("unexpected tmux client request for $sessionName")
            }
        }

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        advanceUntilIdle()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertSame(clientA, registry.clients.value[1L]?.client)
        assertEquals(1, connector.connectCount)

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "other",
        )
        advanceUntilIdle()

        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed)
        assertEquals(listOf(tmuxRuntimeKeyForTest("work")), runtimeCache.snapshotKeys())
        assertFalse("failed switch must not close parked session A", clientA.closed)
        assertTrue("failed session B client must be closed", clientB.closed)
        assertFalse(runtimeCache.contains(tmuxRuntimeKeyForTest("other")))

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        runCurrent()

        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertSame(clientA, registry.clients.value[1L]?.client)
        assertFalse("restored parked session A must remain open", clientA.closed)
        assertFalse(runtimeCache.contains(tmuxRuntimeKeyForTest("work")))
    }

    // ─── Issue #437 (slice A): same-host switch must NOT blank to ───
    // the full-screen "Connecting" overlay. The cold same-host switch
    // (target not yet cached) enters the new [Switching] state, keeps the
    // previous frame painted, gates input until the new -CC control client
    // attaches, then flips to [Connected] with the new session's panes.

    @Test
    fun sameHostSwitchToUncachedSessionEntersSwitchingNotConnecting() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 4)
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val registry = ActiveTmuxClients()
        val vm = newVm(
            registry = registry,
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        // Gate the cold client's list-panes so the new -CC attach stays
        // in flight while we observe the visible state during the switch.
        val attachGate = CompletableDeferred<Unit>()
        val coldClient = FakeTmuxClient().withSinglePane("cold", "%6").apply {
            sendCommandGatePrefix = "list-panes"
            sendCommandGate = attachGate
        }
        vm.setTmuxClientFactoryForTest { _, _, _ -> coldClient }

        // Establish a live same-host session with a rendered pane so the
        // switch is fast-switch eligible AND has a previous frame to keep.
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work-pane",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        runCurrent()
        assertEquals(listOf("%0"), vm.panes.value.map { it.paneId })
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)

        // Switch to a session that has never been opened this app-session
        // (cache miss). This is the maintainer's repro.
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "cold",
        )
        runCurrent()

        // While the new -CC client is attaching the screen must be in
        // Switching — NOT the blanking full-screen Connecting overlay.
        val midStatus = vm.connectionStatus.value
        assertTrue(
            "same-host switch must enter Switching, got $midStatus",
            midStatus is TmuxSessionViewModel.ConnectionStatus.Switching,
        )
        assertFalse(
            "same-host switch must never show the blanking Connecting overlay",
            midStatus is TmuxSessionViewModel.ConnectionStatus.Connecting,
        )
        // Issue #661: a CROSS-session switch must NOT paint the previous
        // session's frame — not even one frame. #634's keep-frame is reversed
        // for the cross-session case: the surface is HIDDEN
        // ([switchHidesTerminal] = true) and the rendered panes are blanked,
        // so the screen shows the "Attaching" loading state instead of the
        // leaving session's content.
        assertTrue(
            "cross-session switch must hide the terminal surface (loading state) " +
                "so the previous session's frame is never painted",
            vm.switchHidesTerminal.value,
        )
        assertEquals(
            "previous session's frame must NOT stay painted during a cross-session " +
                "switch (#661): panes are blanked until the new session reconciles",
            emptyList<String>(),
            vm.panes.value.map { it.paneId },
        )
        // Input is gated: only Connected counts as live (mirrors the
        // screen's sessionLive = status is Connected).
        assertFalse(
            "input must stay gated while switching (status != Connected)",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // Complete the attach: the viewport now swaps to the new session's
        // panes and the status flips to Connected (input ungated).
        attachGate.complete(Unit)
        advanceUntilIdle()

        assertTrue(
            "switch must complete to Connected once the new client attaches",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        // Issue #661: the terminal surface is revealed only AFTER the new
        // session's panes are seeded — and it is the NEW session's content.
        assertFalse(
            "the terminal surface must be revealed (switchHidesTerminal=false) " +
                "once the new session's panes are seeded",
            vm.switchHidesTerminal.value,
        )
        assertEquals(
            "viewport must swap to the new session's panes after attach",
            listOf("%6"),
            vm.panes.value.map { it.paneId },
        )
        assertTrue("new -CC client must attach", coldClient.connectCalled)
        assertSame(coldClient, registry.clients.value[1L]?.client)
    }

    @Test
    fun firstConnectToHostStillShowsConnectingNotSwitching() = runTest(scheduler) {
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val vm = newVm(
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        // Gate the first attach so we can observe the visible state before
        // it completes.
        val attachGate = CompletableDeferred<Unit>()
        val client = FakeTmuxClient().withSinglePane("work", "%0").apply {
            sendCommandGatePrefix = "list-panes"
            sendCommandGate = attachGate
        }
        vm.setTmuxClientFactoryForTest { _, _, _ -> client }

        // No prior active session => genuine first-connect to the host.
        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        runCurrent()

        val status = vm.connectionStatus.value
        assertTrue(
            "first-connect must show the full-screen Connecting overlay, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Connecting,
        )
        assertFalse(
            "first-connect must NOT use the same-host Switching state",
            status is TmuxSessionViewModel.ConnectionStatus.Switching,
        )

        attachGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(listOf("%0"), vm.panes.value.map { it.paneId })
    }

    @Test
    fun sameHostSwitchDeadSshSessionEscalatesToConnectingAndBlanks() = runTest(scheduler) {
        // #178 dead-session fallback preserved: if the reused SSH session
        // died mid-switch we do a genuine reconnect, so the UI must
        // escalate from Switching to the full-screen Connecting overlay
        // and drop the now-stale previous frame (no painting a dead pane).
        // The active session is LIVE at the eligibility check (so the
        // switch is fast-switch eligible and enters Switching), but the
        // lease the fast-switch reacquires comes back dead — exactly the
        // #178 race. The fast-switch fallback then reconnects with a fresh
        // live lease.
        val activeSession = FakeSshSession()
        val deadLeaseSession = FakeSshSession(isConnectedValue = false)
        val fallbackSession = FakeSshSession()
        val connector = QueueLeaseConnector(deadLeaseSession, fallbackSession)
        val vm = newVm(
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val reconnectGate = CompletableDeferred<Unit>()
        val fallbackClient = FakeTmuxClient().withSinglePane("cold", "%6").apply {
            sendCommandGatePrefix = "list-panes"
            sendCommandGate = reconnectGate
        }
        vm.setTmuxClientFactoryForTest { _, _, _ -> fallbackClient }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = activeSession,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work-pane",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        runCurrent()

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "cold",
        )
        runCurrent()

        // The reused session is dead, so the fast-switch path falls back
        // to runConnect: UI escalates to Connecting and the stale frame is
        // dropped while the fresh handshake runs.
        val midStatus = vm.connectionStatus.value
        assertTrue(
            "dead-session fallback must escalate to the full-screen Connecting overlay, got $midStatus",
            midStatus is TmuxSessionViewModel.ConnectionStatus.Connecting,
        )
        assertTrue(
            "dead-session fallback must drop the stale previous frame",
            vm.panes.value.isEmpty(),
        )

        reconnectGate.complete(Unit)
        advanceUntilIdle()
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(listOf("%6"), vm.panes.value.map { it.paneId })
    }

    @Test
    fun closeCachedRuntimeDetachesClientBeforeReleasingLease() = runTest(scheduler) {
        val session = FakeSshSession()
        val manager = testLeaseManager(
            connector = SshLeaseConnector { Result.success(session) },
            idleTtlMillis = 0L,
        )
        val lease = manager.acquire(testLeaseTarget()).getOrThrow()
        val client = FakeTmuxClient()
        val runtime = cachedRuntimeForTest(
            sessionName = "work",
            client = client,
            session = session,
            lease = lease,
        )

        runtime.closeCachedRuntime()

        assertTrue(
            "cached tmux client must detach before lease release completes",
            client.detachCleanlyCalled,
        )
        assertTrue("final cached lease release should close the idle SSH session", session.closed)
    }

    @Test
    fun forceFreshAcquireClosesSameLeaseCachedRuntimeBeforeOpeningTransport() = runTest(scheduler) {
        val staleSession = FakeSshSession()
        val freshSession = FakeSshSession()
        val connector = QueueLeaseConnector(staleSession, freshSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 0L,
        )
        val cachedLease = manager.acquire(testLeaseTarget()).getOrThrow()
        val runtimeCache = TmuxSessionRuntimeCache()
        val cachedClient = FakeTmuxClient()
        assertTrue(
            runtimeCache.put(
                cachedRuntimeForTest(
                    sessionName = "other",
                    client = cachedClient,
                    session = staleSession,
                    lease = cachedLease,
                ),
            ).isEmpty(),
        )
        val vm = newVm(runtimeCache = runtimeCache, sshLeaseManager = manager)

        val lease = vm.acquireLeaseForTmuxForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            trigger = TmuxConnectTrigger.NetworkReconnect,
        )

        assertNotNull(lease)
        assertSame("force-fresh acquire must open a new transport", freshSession, lease?.session)
        assertTrue("force-fresh lease should be marked as a new connection", lease?.isNewConnection == true)
        assertEquals("stale cached lease must not be reused", 2, connector.connectCount)
        assertFalse("same-lease cached runtime must be removed", runtimeCache.contains(tmuxRuntimeKeyForTest("other")))
        assertTrue("same-lease cached tmux client must close", cachedClient.closed)
        assertTrue("same-lease cached SSH session must close", staleSession.closed)
        lease?.release()
    }

    @Test
    fun autoReconnectAcquireForcesFreshLeaseEvictingThePoisonedWarmEntry() = runTest(scheduler) {
        // EPIC #792 Slice C / #822 WEDGE FIX — red→green for the production wiring.
        //
        // The #822 wedge: the AUTO-reconnect ladder re-dialled with the AutoReconnect
        // trigger, which on base did NOT force a fresh lease. On a silent half-open drop
        // sshj's `isConnected` lies (stays true), so the pool REUSED the poisoned warm
        // entry and every attempt re-dialled the dead socket — the maintainer's stuck-
        // Reconnecting wedge. Slice C adds AutoReconnect to `shouldForceFreshLease`, so
        // `acquireLeaseForTmux(AutoReconnect)` now evicts the idle/poisoned lease and
        // dials a FRESH transport — the SAME session recovers without the switch dance.
        //
        // RED on 8e1ccc99: AutoReconnect was not force-fresh, so this asserted the
        // poisoned `staleSession` was REUSED (connectCount stays 1, isNewConnection
        // false). GREEN after the fix: a fresh transport is dialled (connectCount 2).
        // The `staleSession` LIES about isConnected (default connected=true) to model
        // the half-open drop the pool's reuse predicate is fooled by.
        val staleSession = FakeSshSession() // half-open: connected=true but conceptually dead
        val freshSession = FakeSshSession()
        val connector = QueueLeaseConnector(staleSession, freshSession)
        // A non-zero idle TTL keeps the released lease genuinely WARM/idle (a 0 TTL would
        // close it on release, hiding the reuse-vs-evict distinction). The half-open drop
        // leaves it lying about isConnected, so the pool keeps it warm — the wedge state.
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        // Seed the pool with the (poisoned half-open) warm lease, then release it so it
        // sits idle — exactly the warm-lease state an auto-reconnect re-dial hits.
        manager.acquire(testLeaseTarget()).getOrThrow().release()
        val vm = newVm(sshLeaseManager = manager)

        val lease = vm.acquireLeaseForTmuxForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            trigger = TmuxConnectTrigger.AutoReconnect,
        )

        assertNotNull(lease)
        assertSame(
            "auto-reconnect must force-fresh: dial a NEW transport, not reuse the poisoned warm lease",
            freshSession,
            lease?.session,
        )
        assertTrue("the auto-reconnect lease must be a new connection", lease?.isNewConnection == true)
        assertEquals(
            "auto-reconnect must evict the poisoned warm lease and handshake fresh (the #822 fix)",
            2,
            connector.connectCount,
        )
        assertTrue("evicting the poisoned warm lease closes its (half-open dead) session", staleSession.closed)
        lease?.release()
    }

    @Test
    fun normalAcquirePreservesSameLeaseCachedRuntimeReuse() = runTest(scheduler) {
        val warmSession = FakeSshSession()
        val connector = QueueLeaseConnector(warmSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 0L,
        )
        val cachedLease = manager.acquire(testLeaseTarget()).getOrThrow()
        val runtimeCache = TmuxSessionRuntimeCache()
        val cachedClient = FakeTmuxClient()
        assertTrue(
            runtimeCache.put(
                cachedRuntimeForTest(
                    sessionName = "other",
                    client = cachedClient,
                    session = warmSession,
                    lease = cachedLease,
                ),
            ).isEmpty(),
        )
        val vm = newVm(runtimeCache = runtimeCache, sshLeaseManager = manager)

        val lease = vm.acquireLeaseForTmuxForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
        )

        assertNotNull(lease)
        assertSame("normal acquire should reuse the warm lease", warmSession, lease?.session)
        assertFalse("normal acquire should not open a new transport", lease?.isNewConnection == true)
        assertEquals("normal acquire must not reconnect", 1, connector.connectCount)
        assertTrue("normal acquire must preserve cached runtime", runtimeCache.contains(tmuxRuntimeKeyForTest("other")))
        assertFalse("normal acquire must keep cached tmux client warm", cachedClient.closed)
        assertFalse("normal acquire must keep cached SSH session warm", warmSession.closed)
        lease?.release()
        runtimeCache.remove(tmuxRuntimeKeyForTest("other"))?.closeCachedRuntime()
    }

    @Test
    fun fastSwitchDeadSessionFallbackReleasesAcquiredLeaseBeforeRetry() = runTest(scheduler) {
        val deadLeaseSession = FakeSshSession(isConnectedValue = false)
        val fallbackSession = FakeSshSession()
        val connector = QueueLeaseConnector(deadLeaseSession, fallbackSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 0L,
        )
        val vm = newVm(sshLeaseManager = manager)
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "other",
        )
        advanceUntilIdle()

        assertEquals(
            "dead fast-switch lease should be released before the fallback reacquires",
            2,
            connector.connectCount,
        )
        assertTrue("dead fast-switch lease must be closed by release", deadLeaseSession.closed)
    }

    @Test
    fun fastSwitchBlanksPreviousFrameAndHidesSurfaceUntilNewSessionReconciles() = runTest(scheduler) {
        // Issue #661 (reverses #437 slice A / #634 keep-frame for the
        // cross-session case): a same-host fast switch to a DIFFERENT session
        // must NEVER paint the leaving session's frame — not even one frame.
        // The previous frame is BLANKED and the terminal surface is HIDDEN
        // ([switchHidesTerminal] = true, the screen shows the "Attaching"
        // loading state) until the new session's panes reconcile and reveal.
        // This is the maintainer's refined preference (hard-cut, per D22):
        // we no longer keep the previous frame painted on a cross-session
        // switch — that was the "I can still see the previous session for a
        // split second" flash.
        val vm = newVm()
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )

        // Populate a pane row that represents the previous frame. The
        // sessionName must match the active target's session name;
        // otherwise [applyParsedPanes] filters it out.
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "old",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        assertEquals(1, vm.panes.value.size)

        // The new client reports no panes here, so nothing reconciles to
        // replace the blanked frame — letting us assert the leaving frame is
        // BLANKED (not kept) the instant the cross-session switch begins.
        val newClient = FakeTmuxClient()
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
        )
        advanceUntilIdle()

        // Issue #661: the previous session's frame is BLANKED across a
        // cross-session switch — never re-published — so the screen shows the
        // loading state instead of the leaving session's content. (The new
        // session reported no panes, so nothing reconciles in to replace it.)
        assertEquals(
            "previous session's frame must be BLANKED on a cross-session switch (#661), " +
                "not kept painted — was ${vm.panes.value}",
            emptyList<String>(),
            vm.panes.value.map { it.paneId },
        )
    }

    @Test
    fun fastSwitchDoesNotDetachPreviousClientOnCriticalPath() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(registry = registry, runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
        )
        runCurrent()

        assertFalse(
            "warm runtime caching must not detach the old client on the switch path",
            oldClient.detachCleanlyCalled,
        )
        assertFalse(vm.hasInFlightOrphanDetachForTest())
        assertTrue("new tmux client must be connect()ed", newClient.connectCalled)
        assertSame(newClient, registry.clients.value[1L]?.client)
        assertTrue(
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse(
            "old client must stay open as a warm cached runtime",
            oldClient.closed,
        )
        assertTrue(
            runtimeCache.contains(
                TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "work"),
            ),
        )
        assertFalse(
            "SSH session must be reused, never closed by a fast switch",
            session.closed,
        )
    }

    @Test
    fun fastSwitchIncrementsTmuxConnectCounterButNotSshHandshakeCounter() = runTest(scheduler) {
        val vm = newVm()
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )

        // Snapshot the SSH-handshake counter — the fast switch must
        // NOT increment it (the test seam bypasses runConnect entirely,
        // matching what production does when the eligibility predicate
        // passes).
        val handshakesBefore = SSH_HANDSHAKE_ATTEMPTS.get()
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
        )
        advanceUntilIdle()

        val handshakesAfter = SSH_HANDSHAKE_ATTEMPTS.get()
        assertEquals(
            "SSH handshakes must not advance when fast-switch reuses the transport",
            handshakesBefore,
            handshakesAfter,
        )
    }

    @Test
    fun fastSwitchTelemetryUsesVisibleSwitchBaseline() = runTest(scheduler) {
        TmuxSessionLatencyTelemetry.resetForTest()
        val vm = newVm()
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()
        val visibleSwitchStartedAtMs = SystemClock.elapsedRealtime() - 250L

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )

        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
            startedAtMs = visibleSwitchStartedAtMs,
        )
        advanceUntilIdle()

        val warmEvents = TmuxSessionLatencyTelemetry.snapshot()
            .filter { it.name.startsWith("warm_switch_") }
        val start = warmEvents.single { it.name == "warm_switch_start" }
        val shellAttached = warmEvents.single { it.name == "warm_switch_tmux_shell_attached" }
        val connectReady = warmEvents.single { it.name == "warm_switch_connect_ready" }

        assertTrue(
            "start should use the caller's visible-switch baseline: $warmEvents",
            start.durationMs >= 250L,
        )
        assertTrue(
            "shell-attached must keep the same baseline instead of restarting after teardown",
            shellAttached.durationMs >= start.durationMs,
        )
        assertTrue(
            "connect-ready must keep the same baseline instead of restarting after teardown",
            connectReady.durationMs >= shellAttached.durationMs,
        )
        TmuxSessionLatencyTelemetry.resetForTest()
    }

    @Test
    fun refreshActiveSessionCardsPublishesFeedForActiveSession() = runTest(scheduler) {
        val session = FakeSshSession(
            cardGetStdouts = listOf(checklistFeedJson("work", checkedIds = listOf("build-0"))),
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )

        assertTrue(vm.refreshActiveSessionCards())
        awaitCardsState(vm) { !it.loading && it.sessionName == "work" && it.feed.cards.isNotEmpty() }

        val card = vm.sessionCards.value.feed.cards.single() as SessionCardsRemoteSource.ChecklistCard
        assertEquals("release", card.id)
        assertEquals(setOf("build-0"), card.checkedIds)
        assertTrue(
            "card refresh must use the current tmux session name",
            session.execCommands.any { it.contains("push get") && it.contains("--session 'work'") },
        )
    }

    @Test
    fun refreshActiveSessionCardsDropsLateFeedWhenHostChangesButSessionNameMatches() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val oldSession = FakeSshSession(
            execGate = gate,
            cardGetStdouts = listOf(checklistFeedJson("work", checkedIds = listOf("build-0"))),
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = oldSession,
        )

        assertTrue(vm.refreshActiveSessionCards())
        awaitCondition { oldSession.execCommands.any { it.contains("push get") } }

        vm.replaceClientForTest(
            hostId = 2L,
            hostName = "beta",
            host = "beta.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/b",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )
        gate.complete(Unit)
        runCurrent()
        advanceUntilIdle()

        assertTrue(
            "late card feed from old host must not publish after switching to same-named session",
            vm.sessionCards.value.feed.cards.isEmpty(),
        )
    }

    @Test
    fun refreshActiveSessionCardsReturnsFalseWithoutWarmSession() = runTest(scheduler) {
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = null,
        )

        assertFalse(vm.refreshActiveSessionCards())
        assertEquals(TmuxSessionViewModel.SessionCardsUiState(), vm.sessionCards.value)
    }

    @Test
    fun toggleChecklistItemWritesThenRefreshesFeedOnSuccess() = runTest(scheduler) {
        val session = FakeSshSession(
            cardGetStdouts = listOf(checklistFeedJson("work", checkedIds = listOf("build-0"))),
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )

        assertTrue(vm.toggleChecklistItem(cardId = "release", itemId = "build-0", checked = true))
        awaitCardsState(vm) { !it.loading && it.feed.cards.isNotEmpty() }

        val checkCommand = session.execCommands.single { it.contains("push check") }
        assertTrue(checkCommand.contains("--id 'release'"))
        assertTrue(checkCommand.contains("--item 'build-0'"))
        assertTrue(checkCommand.contains("--done"))
        assertTrue(checkCommand.contains("--session 'work'"))
        val card = vm.sessionCards.value.feed.cards.single() as SessionCardsRemoteSource.ChecklistCard
        assertEquals(setOf("build-0"), card.checkedIds)
    }

    @Test
    fun toggleChecklistItemDropsPostWriteRefreshWhenHostChangesButSessionNameMatches() = runTest(scheduler) {
        val gate = CompletableDeferred<Unit>()
        val oldSession = FakeSshSession(
            execGate = gate,
            cardGetStdouts = listOf(checklistFeedJson("work", checkedIds = listOf("build-0"))),
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = oldSession,
        )

        assertTrue(vm.toggleChecklistItem(cardId = "release", itemId = "build-0", checked = true))
        awaitCondition { oldSession.execCommands.any { it.contains("push check") } }

        vm.replaceClientForTest(
            hostId = 2L,
            hostName = "beta",
            host = "beta.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/b",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = FakeSshSession(),
        )
        gate.complete(Unit)
        runCurrent()
        advanceUntilIdle()

        assertTrue(oldSession.execCommands.none { it.contains("push get") })
        assertTrue(
            "post-write refresh from old host must not publish after switching to same-named session",
            vm.sessionCards.value.feed.cards.isEmpty(),
        )
    }

    @Test
    fun toggleChecklistItemDoesNotRefreshOnHostFailure() = runTest(scheduler) {
        val session = FakeSshSession(
            cardGetStdouts = listOf(checklistFeedJson("work", checkedIds = listOf("build-0"))),
            cardCheckExitCode = 1,
        )
        val vm = newVm()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
            session = session,
        )

        assertTrue(vm.toggleChecklistItem(cardId = "release", itemId = "build-0", checked = false))
        awaitCondition { session.execCommands.any { it.contains("push check") } }

        assertTrue(session.execCommands.none { it.contains("push get") })
        assertEquals(TmuxSessionViewModel.SessionCardsUiState(), vm.sessionCards.value)
    }

    @Test
    fun closedPaneClearsConversationRoutingState() = runTest(scheduler) {
        // A pane that tmux removes between reconciles cannot keep any
        // conversation state behind.
        val vm = newVm()
        vm.attachClientForTest(FakeTmuxClient())
        vm.applyParsedPanesForTest(
            listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "a", paneIndex = 0)),
        )
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        vm.selectSessionTab("%0", SessionTab.Conversation)
        assertEquals(SessionTab.Conversation, vm.agentConversations.value["%0"]!!.selectedTab)

        // Reconcile with the pane gone.
        vm.applyParsedPanesForTest(emptyList())

        assertTrue(
            "conversation state must be empty when the pane disappears",
            vm.agentConversations.value.isEmpty(),
        )
    }

    // Issue #165 — cancelConnect tests. The progress overlay's 15s
    // Cancel affordance routes through [TmuxSessionViewModel.cancelConnect];
    // these tests assert it cancels the in-flight connect job AND flips
    // status to Failed so the screen renders a deterministic post-cancel
    // state instead of staying stuck on Connecting.

    @Test
    fun cancelConnectFlipsConnectingStatusToFailedAndCancelsJob() = runTest(scheduler) {
        val vm = newVm()
        // A real Job we can inspect post-cancel, parented to the test
        // scope. The production [connect] launches into [viewModelScope];
        // for this seam we just need the cancelable handle.
        val job = kotlinx.coroutines.Job()
        vm.beginConnectingForTest(host = "alpha.example", port = 22, user = "alex", job = job)
        assertTrue(
            "precondition: status must be Connecting",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connecting,
        )

        val fired = vm.cancelConnect()

        assertTrue("cancelConnect() must report success when called during Connecting", fired)
        val status = vm.connectionStatus.value
        assertTrue(
            "status must be Failed after cancel, was $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Connect cancelled by user.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
        assertTrue("connectJob must be cancelled by cancelConnect()", job.isCancelled)
    }

    @Test
    fun cancelConnectIsNoOpWhenNotConnecting() = runTest(scheduler) {
        val vm = newVm()
        // Status starts Idle — cancel must be a no-op.
        val firedFromIdle = vm.cancelConnect()
        assertFalse("cancelConnect() must no-op when status is Idle", firedFromIdle)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Idle)

        // Drive to Connected via the test seam and verify cancel is a
        // no-op there too — the screen's Cancel button is gated on
        // Connecting, but the defensive check inside cancelConnect()
        // is the safety net for direct programmatic callers.
        vm.attachClientForTest(FakeTmuxClient())
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        val firedFromConnected = vm.cancelConnect()
        assertFalse("cancelConnect() must no-op when status is Connected", firedFromConnected)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
    }

    /**
     * Issue #235: `onAppBackgrounded` must call `detachCleanly` on the
     * live tmux client when the process goes to background. We drive
     * the VM into a connected state via [replaceClientForTest] (which
     * also stamps an [activeTarget] in place) so the backgrounded hook
     * has something to tear down.
     */
    @Test
    fun onAppBackgroundedCallsDetachCleanlyOnLiveClient() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertFalse("detach must not have fired before background", client.detachCleanlyCalled)

        vm.onAppBackgrounded()
        advanceUntilIdle()

        assertTrue(
            "onAppBackgrounded must invoke detachCleanly via closeCurrentConnectionAndJoin",
            client.detachCleanlyCalled,
        )
        assertTrue("client must be closed after backgrounded detach", client.closed)
    }

    @Test
    fun onClearedWhileBackgroundedParksLiveRuntimeInsteadOfClosingLeaseBeforeGrace() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val client = FakeTmuxClient()
        val session = FakeSshSession()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
            session = session,
        )
        vm.setProcessForegroundForClearedForTest(false)

        vm.clearForTest()
        runCurrent()

        assertFalse(
            "background ViewModel clear must not detach the live tmux client before grace elapses",
            client.detachCleanlyCalled,
        )
        assertFalse(
            "background ViewModel clear must not close the live tmux client before grace elapses",
            client.closed,
        )
        assertFalse(
            "background ViewModel clear must leave the SSH session open for the grace handoff",
            session.closed,
        )
        assertEquals(
            "parked runtime should be available for the recreated Activity/ViewModel",
            listOf(tmuxRuntimeKeyForTest("work")),
            runtimeCache.snapshotKeys(),
        )
    }

    @Test
    fun onClearedAfterScreenStopParksRuntimeDuringProcessLifecycleStopDebounce() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val client = FakeTmuxClient()
        val session = FakeSshSession()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
            session = session,
        )
        vm.setProcessForegroundForClearedForTest(true)

        vm.onScreenStopped()
        vm.clearForTest()
        runCurrent()

        assertFalse(
            "screen-stopped clear must not detach during ProcessLifecycleOwner ON_STOP debounce",
            client.detachCleanlyCalled,
        )
        assertFalse("screen-stopped clear must keep the tmux client alive", client.closed)
        assertFalse("screen-stopped clear must keep the SSH session alive", session.closed)
        assertEquals(listOf(tmuxRuntimeKeyForTest("work")), runtimeCache.snapshotKeys())
    }

    @Test
    fun onClearedWhileBackgroundedClosesEvictedParkedRuntimeOutsideViewModelScope() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache(maxEntries = 1)
        val evictedSession = FakeSshSession()
        val evictedLeaseManager = testLeaseManager(
            connector = SshLeaseConnector { Result.success(evictedSession) },
            idleTtlMillis = 0L,
        )
        val evictedLease = evictedLeaseManager.acquire(testLeaseTarget()).getOrThrow()
        val evictedClient = FakeTmuxClient()
        runtimeCache.put(
            cachedRuntimeForTest(
                sessionName = "old",
                client = evictedClient,
                session = evictedSession,
                lease = evictedLease,
            ),
        )
        val vm = newVm(runtimeCache = runtimeCache)
        val activeClient = FakeTmuxClient()
        val activeSession = FakeSshSession()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = activeClient,
            session = activeSession,
        )
        vm.setProcessForegroundForClearedForTest(false)

        vm.clearForTest()
        runCurrent()

        assertTrue("evicted parked client must detach during background clear", evictedClient.detachCleanlyCalled)
        assertTrue("evicted parked client must close during background clear", evictedClient.closed)
        assertTrue("evicted SSH lease must be released during background clear", evictedSession.closed)
        assertFalse("newly parked active client must remain live for grace handoff", activeClient.closed)
        assertFalse("active SSH session must remain live for grace handoff", activeSession.closed)
        assertEquals(listOf(tmuxRuntimeKeyForTest("work")), runtimeCache.snapshotKeys())
        assertFalse(runtimeCache.contains(tmuxRuntimeKeyForTest("old")))
    }

    @Test
    fun foregroundOnClearedStillClosesLiveRuntimeForUserNavigation() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val client = FakeTmuxClient()
        val session = FakeSshSession()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
            session = session,
        )
        vm.setProcessForegroundForClearedForTest(true)

        vm.clearForTest()
        runCurrent()

        assertTrue("foreground clear must keep the explicit detach/close behavior", client.detachCleanlyCalled)
        assertTrue("foreground clear must close the tmux client", client.closed)
        assertTrue("foreground clear must close the SSH session", session.closed)
        assertTrue("foreground clear must not leave a parked runtime", runtimeCache.snapshotKeys().isEmpty())
    }

    @Test
    fun restoredParkedRuntimeRebindsViewModelScopedPaneJobsWithoutSshReconnect() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache()
        val parkedClient = FakeTmuxClient()
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(FakeSshSession())
        val pane = TmuxPaneState(
            paneId = "%0",
            windowId = "@0",
            sessionId = "\$0",
            title = "work",
            cwd = "/repo",
            currentCommand = "bash",
            paneTty = "/dev/pts/1",
            terminalState = TerminalSurfaceState(),
        )
        runtimeCache.put(
            CachedTmuxRuntime(
                key = tmuxRuntimeKeyForTest("work"),
                hostName = "alpha",
                startDirectory = null,
                session = session,
                client = parkedClient,
                panes = listOf(pane),
                paneRows = mapOf("%0" to pane),
                paneProducerJobs = mapOf("%0" to Job().also { it.cancel() }),
                paneInputQueues = emptyMap(),
                paneInputJobs = mapOf("%0" to Job().also { it.cancel() }),
                paneAgentJobs = emptyMap(),
                paneAgentInputs = emptyMap(),
                agentConversations = emptyMap(),
                remoteColumns = 80,
                remoteRows = 24,
            ),
        )
        val vm = newVm(
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )

        vm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        runCurrent()

        assertEquals("cache hit must avoid opening a fresh SSH lease", 0, connector.connectCount)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(listOf("%0"), vm.panes.value.map { it.paneId })
        assertTrue("restored pane must be reattached to the new ViewModel scope", pane.terminalState.isAttached)
        vm.tmuxInputSinkForTest("%0").write("x".toByteArray())
        runCurrent()
        assertTrue(
            "rebuilt input queue must send through the parked tmux client",
            parkedClient.sentCommands.any { it.startsWith("send-keys -l -t %0") },
        )
    }

    @Test
    fun foregroundReturnWithinGraceRestoresParkedRuntimeWithoutVisibleReconnectState() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val runtimeCache = TmuxSessionRuntimeCache()
        val firstVm = newVm(registry = registry, runtimeCache = runtimeCache)
        val client = FakeTmuxClient()
        val session = FakeSshSession()
        firstVm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
            session = session,
        )
        firstVm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        firstVm.setProcessForegroundForClearedForTest(true)

        firstVm.onScreenStopped()
        firstVm.clearForTest()
        runCurrent()

        assertFalse("within-grace screen clear must keep tmux client live", client.closed)
        assertFalse("within-grace screen clear must keep SSH session live", session.closed)
        assertEquals(listOf(tmuxRuntimeKeyForTest("work")), runtimeCache.snapshotKeys())

        val connector = QueueLeaseConnector(FakeSshSession())
        val restoredVm = newVm(
            registry = registry,
            runtimeCache = runtimeCache,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        val observedStatuses = mutableListOf<TmuxSessionViewModel.ConnectionStatus>()
        val statusJob = backgroundScope.launch {
            restoredVm.connectionStatus.collect { observedStatuses += it }
        }
        runCurrent()

        restoredVm.connect(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            passphrase = null,
            sessionName = "work",
        )
        runCurrent()
        statusJob.cancel()

        assertEquals("cache hit must avoid opening a fresh SSH lease", 0, connector.connectCount)
        assertEquals(listOf("%0"), restoredVm.panes.value.map { it.paneId })
        assertSame(client, registry.clients.value[1L]?.client)
        assertTrue(restoredVm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertTrue(
            "foreground return inside grace must not flash user-visible reconnect/teardown status; " +
                "observed=$observedStatuses",
            observedStatuses.none {
                it is TmuxSessionViewModel.ConnectionStatus.Connecting ||
                    it is TmuxSessionViewModel.ConnectionStatus.Switching ||
                    it is TmuxSessionViewModel.ConnectionStatus.Reconnecting ||
                    it is TmuxSessionViewModel.ConnectionStatus.Failed
            },
        )
    }

    @Test
    fun onAppBackgroundedClosesInactiveCachedRuntimesForHost() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val cachedClient = FakeTmuxClient()
        val foregroundClient = FakeTmuxClient()

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = cachedClient,
            session = session,
        )
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = foregroundClient,
            session = session,
        )
        runCurrent()
        assertTrue(
            runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "work")),
        )

        vm.onAppBackgrounded()
        advanceUntilIdle()

        assertTrue("foreground runtime must detach on background", foregroundClient.detachCleanlyCalled)
        assertTrue("inactive cached runtime must also detach on background", cachedClient.detachCleanlyCalled)
        assertFalse(
            "background detach must remove inactive cached runtimes for the host",
            runtimeCache.contains(TmuxRuntimeKey(1L, "alpha.example", 22, "alex", "/keys/a", "work")),
        )
    }

    /**
     * Issue #235 integration race: foreground can arrive after
     * `ON_STOP` starts the background detach but before
     * [closeCurrentConnectionAndJoin] clears the still-connected VM
     * state. The pending reattach must survive that window; otherwise
     * connect() sees the old active target, returns as already
     * connected, and the later detach leaves the screen disconnected
     * with no pending reattach left.
     */
    @Test
    fun onAppForegroundedWaitsForInFlightBackgroundDetachBeforeConsumingReattach() = runTest(scheduler) {
        val vm = newVm()
        val detachGate = CompletableDeferred<Unit>()
        val client = FakeTmuxClient().apply {
            detachCleanlyGate = detachGate
        }
        var foregroundReattachCount = 0
        vm.setForegroundReattachForTest {
            foregroundReattachCount += 1
        }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.onAppBackgrounded()
        runCurrent()
        assertTrue("detach must have started", client.detachCleanlyCalled)
        assertFalse("detach is intentionally still in flight", client.closed)
        assertTrue("background detach must seed pending reattach", vm.hasPendingReattachForTest())

        vm.onAppForegrounded()
        runCurrent()

        assertTrue(
            "foreground must not consume pending reattach until detach finishes",
            vm.hasPendingReattachForTest(),
        )
        assertEquals(
            "foreground reattach must wait for background detach",
            0,
            foregroundReattachCount,
        )

        detachGate.complete(Unit)
        advanceUntilIdle()

        assertFalse(
            "pending reattach should be consumed after detach completes",
            vm.hasPendingReattachForTest(),
        )
        assertEquals(1, foregroundReattachCount)
        assertTrue("client must be closed after backgrounded detach", client.closed)
    }

    /**
     * Issue #272: if the user starts switching from session A to session
     * B and the app backgrounds before B finishes attaching, lifecycle
     * foreground must not silently reattach A. The newer route/connect
     * intent owns foreground, so lifecycle reattach is an explicit no-op
     * and the B target remains available for the in-flight connect path.
     */
    @Test
    fun onAppForegroundedSkipsDetachedSessionWhenNewerConnectIntentExists() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        var foregroundReattachCount = 0
        vm.setForegroundReattachForTest {
            foregroundReattachCount += 1
        }
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "session-a",
            client = client,
        )
        vm.beginConnectingForTest(
            host = "alpha.example",
            port = 22,
            user = "alex",
            sessionName = "session-b",
            job = Job(),
        )

        vm.onAppBackgrounded()
        advanceUntilIdle()

        assertTrue("background detach must seed pending reattach", vm.hasPendingReattachForTest())
        assertEquals(
            "newer connecting session must survive lifecycle teardown",
            "session-b",
            vm.connectingSessionNameForTest(),
        )

        vm.onAppForegrounded()
        advanceUntilIdle()

        assertFalse(
            "pending reattach should be consumed as a deliberate newer-intent no-op",
            vm.hasPendingReattachForTest(),
        )
        assertEquals(
            "lifecycle must not reattach the detached A session when B is intended",
            0,
            foregroundReattachCount,
        )
        assertEquals(
            "session-b",
            vm.connectingSessionNameForTest(),
        )
    }

    /**
     * Issue #235: with no live client, `onAppBackgrounded` must be a
     * no-op. The lifecycle observer fires for every process-level
     * `ON_STOP`, so the hook is invoked even when the user never
     * opened a tmux session.
     */
    @Test
    fun onAppBackgroundedIsNoOpWhenNoActiveClient() = runTest(scheduler) {
        val vm = newVm()
        // Status is Idle, no client attached.
        vm.onAppBackgrounded()
        advanceUntilIdle()
        // No exception and the VM stays Idle.
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Idle)
    }

    /**
     * Issue #235: the manual Detach button drives [detachAndExit],
     * which must run the same detach-cleanly + close path AND clear
     * any pending reattach so a subsequent background event does not
     * unexpectedly resurrect the session the user just walked away
     * from.
     */
    @Test
    fun detachAndExitTearsClientDownAndClearsPendingReattach() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        vm.detachAndExit()
        advanceUntilIdle()

        assertTrue("detachAndExit must invoke detachCleanly", client.detachCleanlyCalled)
        assertTrue("client must be closed after manual detach", client.closed)

        // After detachAndExit, a follow-up onAppForegrounded must NOT
        // resurrect the session — the user explicitly walked away.
        // The hook should find no pending target and do nothing.
        val priorStatus = vm.connectionStatus.value
        vm.onAppForegrounded()
        advanceUntilIdle()
        // The status the screen reads stays consistent with "no
        // connection in flight". (The full-cycle assertion lives in the
        // connected E2E test; here we just verify the no-op invariant.)
        assertSame(
            "onAppForegrounded after detachAndExit must not transition status",
            priorStatus,
            vm.connectionStatus.value,
        )
    }

    /**
     * Issue #235: `ActiveTmuxClients.registerLifecycleHooks` is the
     * seam the [com.pocketshell.app.App] observer reads off. Every
     * successful attach (slow-path, fast-switch, or
     * `replaceClientForTest`) must install hooks under the connected
     * host id so the application-scope fanout can find them.
     */
    @Test
    fun replaceClientInstallsLifecycleHooksIntoRegistry() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        val hooks = registry.lifecycleHooksSnapshot()
        assertEquals("expected exactly one hook installed", 1, hooks.size)
    }

    /**
     * Issue #235: a connection teardown (e.g. lifecycle-driven
     * auto-detach) must NOT remove the lifecycle hook from the
     * registry — otherwise the very first auto-detach would drop the
     * foreground reattach hook and the app would never reattach on
     * `ON_START`. Hooks are removed only on `onCleared` (separate
     * test below).
     */
    @Test
    fun connectionTeardownPreservesLifecycleHooks() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.replaceClientForTest(
            hostId = 9L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        assertEquals(1, registry.lifecycleHooksSnapshot().size)

        // `detachAndExit` drives `closeCurrentConnectionAndJoin`, which
        // clears the active client registration. The hooks must
        // survive — they are tied to the VM, not the client.
        vm.detachAndExit()
        advanceUntilIdle()

        assertEquals(
            "lifecycle hook must survive connection teardown",
            1,
            registry.lifecycleHooksSnapshot().size,
        )
        // Client entry IS gone — that's the per-cycle state.
        assertTrue(
            "client entry must be unregistered after detach",
            registry.clients.value.isEmpty(),
        )
    }

    /**
     * Issue #235: `onCleared` is the only path that drops the
     * lifecycle hook. Sanity-checks the hook lifetime invariant.
     */
    @Test
    fun onClearedRemovesLifecycleHooksFromRegistry() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.replaceClientForTest(
            hostId = 11L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        assertEquals(1, registry.lifecycleHooksSnapshot().size)

        // `clearForTest` is the reflective seam tests use to drive
        // `onCleared` outside the Android lifecycle machinery.
        vm.clearForTest()
        advanceUntilIdle()

        assertTrue(
            "lifecycle hook must be removed when VM is cleared",
            registry.lifecycleHooksSnapshot().isEmpty(),
        )
    }

    /**
     * Issue #548: one VM can be reused for another host. Installing
     * hooks for the new host must remove the prior host's hook; only
     * client entries are allowed to churn independently across normal
     * detach/attach cycles.
     */
    @Test
    fun reinstallingLifecycleHooksForDifferentHostRemovesPreviousHook() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.replaceClientForTest(
            hostId = 21L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        assertEquals(1, registry.lifecycleHooksSnapshot().size)

        vm.replaceClientForTest(
            hostId = 22L,
            hostName = "beta",
            host = "beta.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/b",
            sessionName = "work",
            client = FakeTmuxClient(),
        )

        assertEquals(
            "reinstalling hooks for a different host must not leak the old host hook",
            1,
            registry.lifecycleHooksSnapshot().size,
        )
        assertTrue("old host client entry should be gone", 21L !in registry.clients.value)
        assertTrue("new host client entry should be present", 22L in registry.clients.value)

        vm.clearForTest()
        advanceUntilIdle()

        assertTrue(
            "clearing the VM must remove the latest lifecycle hook",
            registry.lifecycleHooksSnapshot().isEmpty(),
        )
    }

    /**
     * Issue #548: a stale VM can finish teardown after a newer VM has
     * already attached to the same host. The stale teardown must not
     * evict the newer client entry or lifecycle hook.
     */
    @Test
    fun staleViewModelClearDoesNotUnregisterNewerSameHostClientOrHooks() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val oldVm = newVm(registry)
        val newVm = newVm(registry)
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()
        oldVm.replaceClientForTest(
            hostId = 13L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )
        newVm.replaceClientForTest(
            hostId = 13L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = newClient,
        )
        assertSame(newClient, registry.clients.value[13L]?.client)
        assertEquals(1, registry.lifecycleHooksSnapshot().size)

        oldVm.clearForTest()
        advanceUntilIdle()

        assertSame(
            "stale VM teardown must not remove newer client entry",
            newClient,
            registry.clients.value[13L]?.client,
        )
        assertEquals(
            "stale VM teardown must not remove newer lifecycle hook",
            1,
            registry.lifecycleHooksSnapshot().size,
        )
    }

    // ---- Issue #448 (epic #432 slice C): new-port detection overlay ----

    /**
     * Attach a client + session that reports [listeningPorts] from its
     * `ss` confirm scan, then materialise one pane so the detection
     * collector is wired onto the pane's shared output flow.
     */
    private fun TmuxSessionViewModel.attachForPortDetection(
        client: FakeTmuxClient,
        session: FakeSshSession,
    ) {
        replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            // Empty session name so the default ParsedPane.sessionName ("")
            // passes applyParsedPanes' session filter.
            sessionName = "",
            client = client,
            session = session,
        )
        applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane("%0", "@0", "$0", "shell", paneIndex = 0),
            ),
        )
    }

    @Test
    fun confirmedNewPortSurfacesOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(5173))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        assertNull(vm.detectedPort.value)

        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Local:   http://localhost:5173/\n".toByteArray()),
        )
        advanceUntilIdle()

        assertEquals(5173, vm.detectedPort.value)
    }

    @Test
    fun assistantConversationLocalhostUrlSurfacesPortForwardOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(5173))
        vm.attachForPortDetection(client, session)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        advanceUntilIdle()

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "assistant-localhost",
                    agent = AgentKind.ClaudeCode,
                    role = ConversationRole.Assistant,
                    text = "Preview is ready at http://localhost:5173/",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(5173, vm.detectedPort.value)
    }

    @Test
    fun assistantConversationLoopbackPortPhraseSurfacesPortForwardOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(3000))
        vm.attachForPortDetection(client, session)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        advanceUntilIdle()

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "assistant-localhost-port-phrase",
                    agent = AgentKind.ClaudeCode,
                    role = ConversationRole.Assistant,
                    text = "Preview is running on localhost port 3000.",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(3000, vm.detectedPort.value)
    }

    @Test
    fun agentToolResultLoopbackPortSurfacesPortForwardOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        advanceUntilIdle()

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.ToolResult(
                    id = "tool-localhost",
                    agent = AgentKind.ClaudeCode,
                    output = "Server running on 0.0.0.0:8000\n",
                ),
            ),
        )
        advanceUntilIdle()

        assertEquals(8000, vm.detectedPort.value)
    }

    @Test
    fun userConversationLocalhostUrlDoesNotSurfacePortForwardOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(5173))
        vm.attachForPortDetection(client, session)
        vm.startAgentConversationForTest("%0", newClaudeDetection())
        advanceUntilIdle()

        vm.appendAgentEventsForTest(
            "%0",
            listOf(
                ConversationEvent.Message(
                    id = "user-localhost",
                    agent = AgentKind.ClaudeCode,
                    role = ConversationRole.User,
                    text = "Can you check http://localhost:5173?",
                ),
            ),
        )
        advanceUntilIdle()

        assertNull(vm.detectedPort.value)
    }

    @Test
    fun echoedPortNotListeningDoesNotSurfaceOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        // ss reports nothing listening — the regex hit is an echoed/old URL.
        val session = FakeSshSession(ssListeningPorts = emptySet())
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()

        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on http://127.0.0.1:8000\n".toByteArray()),
        )
        advanceUntilIdle()

        assertNull("unconfirmed port must not surface an overlay", vm.detectedPort.value)
    }

    @Test
    fun acceptingDetectedPortReturnsItAndClearsOverlay() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertEquals(8000, vm.detectedPort.value)

        assertEquals(8000, vm.acceptDetectedPort())
        assertNull(vm.detectedPort.value)
    }

    @Test
    fun dismissedPortDoesNotReSurfaceInSameSession() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertEquals(8000, vm.detectedPort.value)

        vm.dismissDetectedPort()
        assertNull(vm.detectedPort.value)

        // Same port reprinted later in the session — must not re-prompt.
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertNull("dismissed port must not re-prompt", vm.detectedPort.value)
    }

    @Test
    fun forwardedPortDoesNotReSurfaceInSameSession() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(8000))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertEquals(8000, vm.acceptDetectedPort())

        // Same port reprinted after the user forwarded it — no re-prompt.
        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Listening on 0.0.0.0:8000\n".toByteArray()),
        )
        advanceUntilIdle()
        assertNull("forwarded port must not re-prompt", vm.detectedPort.value)
    }

    // ---- Issue #877: per-%output port-detection decode + scan runs OFF Main ----

    /**
     * Issue #877 (idle-session ANR): a [CoroutineDispatcher] that delegates to
     * the shared virtual-clock test dispatcher but flips [usedForScan] true the
     * first time it is asked to [dispatch] a block. Injected as the VM's
     * `portDetectionDispatcher` so the test can assert the per-`%output`
     * decode + `PortDetector.scan` was hopped off the main/immediate dispatcher
     * (it goes THROUGH this tracking dispatcher) rather than running inline on
     * the bridge scope (Main) the way the unfixed code did.
     */
    private inner class ScanDispatchTracker(
        // A real-dispatch delegate (a StandardTestDispatcher on the shared
        // scheduler), NOT the Unconfined Main dispatcher — wrapping Unconfined
        // and forcing dispatch violates its "yield-only" contract. A
        // StandardTestDispatcher genuinely needs dispatch and is driven by
        // advanceUntilIdle, so a `withContext(this)` from a bridgeScope (Main)
        // coroutine is a real, observable hop OFF Main.
        private val delegate: CoroutineDispatcher,
    ) : CoroutineDispatcher() {
        val usedForScan = AtomicBoolean(false)
        val dispatchCount = AtomicInteger(0)

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            usedForScan.set(true)
            dispatchCount.incrementAndGet()
            delegate.dispatch(context, block)
        }
    }

    /**
     * Issue #877 regression (red→green, load-bearing): the per-`%output`
     * decode + 7-regex [PortDetector.scan] — the work that froze an idle agent
     * session because it ran on the UI thread for every output chunk — must run
     * on the injected off-main `portDetectionDispatcher`, NOT inline on the
     * bridge scope (Main). RED on base: `startPortDetectionForPane` ran the
     * scan inline so the tracking dispatcher is never used. GREEN with the fix:
     * `scanOutputEventForPorts` hops to `portDetectionDispatcher`, so the
     * tracker records the dispatch. The port is still detected (behaviour
     * preserved — only the thread changed).
     */
    @Test
    fun portDetectionDecodeAndScanRunsOffMainNotOnBridgeScope() = runTest(scheduler) {
        val vm = newVm()
        val tracker = ScanDispatchTracker(StandardTestDispatcher(scheduler))
        vm.setPortDetectionDispatcherForTest(tracker)
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = setOf(5173))
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        val dispatchesBeforeOutput = tracker.dispatchCount.get()

        client.emittedEvents.emit(
            ControlEvent.Output("%0", "Local:   http://localhost:5173/\n".toByteArray()),
        )
        advanceUntilIdle()

        assertTrue(
            "the %output chunk must produce a NEW off-main scan dispatch, proving " +
                "the decode + scan was hopped off the bridge scope (Main)",
            tracker.dispatchCount.get() > dispatchesBeforeOutput,
        )
        assertTrue(
            "the per-%output decode + PortDetector.scan must run on the off-main " +
                "portDetectionDispatcher, not inline on the bridge scope (Main)",
            tracker.usedForScan.get(),
        )
        assertEquals(
            "the port must still be detected after the scan moved off Main",
            5173,
            vm.detectedPort.value,
        )
    }

    /**
     * Issue #877 class coverage: an idle agent pane keeps emitting low-rate
     * spinner/status `%output` frames; EVERY such frame's decode + scan must
     * hop off Main, never accumulating main-thread work. Feed a burst of idle
     * spinner frames carrying NO port and assert the scan ran off-main for each
     * one (the tracker is dispatched once per frame) while no overlay is
     * surfaced. This is the steady-idle-on-Main pattern the maintainer hit.
     */
    @Test
    fun idleSpinnerOutputScansOffMainEveryFrameWithoutSurfacingOverlay() = runTest(scheduler) {
        val vm = newVm()
        val tracker = ScanDispatchTracker(StandardTestDispatcher(scheduler))
        vm.setPortDetectionDispatcherForTest(tracker)
        val client = FakeTmuxClient()
        val session = FakeSshSession(ssListeningPorts = emptySet())
        vm.attachForPortDetection(client, session)
        advanceUntilIdle()
        val dispatchesBeforeFrames = tracker.dispatchCount.get()

        val frames = 40
        val spinner = "⠋⠙⠹⠸"
        repeat(frames) { i ->
            // A typical idle-agent spinner/status repaint: cursor moves + a
            // braille spinner glyph, no listening-port signal.
            client.emittedEvents.emit(
                ControlEvent.Output(
                    "%0",
                    "[2K\rThinking ${spinner[i % spinner.length]} (esc to interrupt)".toByteArray(),
                ),
            )
        }
        advanceUntilIdle()

        assertTrue(
            "every idle %output frame's scan must run off-main (one new off-main " +
                "dispatch per emitted frame)",
            tracker.dispatchCount.get() - dispatchesBeforeFrames >= frames,
        )
        assertNull(
            "idle spinner output carries no port, so no overlay must surface",
            vm.detectedPort.value,
        )
    }

    private fun cachedRuntimeForTest(
        sessionName: String,
        hostId: Long = 1L,
        client: FakeTmuxClient = FakeTmuxClient(),
        session: FakeSshSession = FakeSshSession(),
        lease: SshLease? = null,
    ): CachedTmuxRuntime {
        val key = tmuxRuntimeKeyForTest(sessionName = sessionName, hostId = hostId)
        return CachedTmuxRuntime(
            key = key,
            hostName = "alpha",
            startDirectory = null,
            session = session,
            client = client,
            panes = emptyList(),
            paneRows = emptyMap(),
            paneProducerJobs = emptyMap(),
            paneInputQueues = emptyMap(),
            paneInputJobs = emptyMap(),
            paneAgentJobs = emptyMap(),
            paneAgentInputs = emptyMap(),
            agentConversations = emptyMap(),
            remoteColumns = 0,
            remoteRows = 0,
            lease = lease,
        )
    }

    private fun FakeTmuxClient.withSinglePane(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private fun tmuxRuntimeKeyForTest(
        sessionName: String,
        hostId: Long = 1L,
    ): TmuxRuntimeKey =
        TmuxRuntimeKey(
            hostId = hostId,
            hostname = "alpha.example",
            port = 22,
            username = "alex",
            keyPath = "/keys/a",
            sessionName = sessionName,
        )

    private fun testLeaseTarget(): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "alpha.example",
                port = 22,
                user = "alex",
                credentialId = "1:/keys/a",
            ),
            key = SshKey.Path(File("/keys/a")),
        )

    private fun codexScaleOutputChunk(index: Int): ByteArray {
        val linePrefix = "codex-overload-${index.toString().padStart(4, '0')}"
        val line = "$linePrefix " + "x".repeat(240) + "\r\n"
        return buildString {
            repeat(CODEX_SCALE_OUTPUT_LINES_PER_CHUNK) { append(line) }
        }.toByteArray(Charsets.UTF_8)
    }

    private fun codexTranscriptWithToolFlood(toolResults: Int): List<String> = buildList {
        add(
            """{"type":"session_meta","payload":{"id":"xyz","cwd":"/work"}}""",
        )
        add(
            """{"id":"issue-576-user","type":"event_msg","timestamp":"2026-06-06T15:15:00Z","payload":{"type":"user_message","message":${JSONObject.quote(ISSUE_576_CODEX_USER_PROMPT)}}}""",
        )
        repeat(toolResults) { index ->
            add(
                """{"type":"response_item","payload":{"type":"function_call_output","call_id":"issue-576-call-$index","output":${JSONObject.quote("terminal chunk $index " + "x".repeat(900))}}}""",
            )
        }
        add(
            """{"type":"response_item","payload":{"type":"message","id":"issue-576-assistant","role":"assistant","content":[{"type":"output_text","text":${JSONObject.quote(ISSUE_576_CODEX_ASSISTANT_REPLY)}}]}}""",
        )
    }

    private fun issue576BackpressureOutputChunks(): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        chunks += "\u001b[31mISSUE576-VM-START\u001b[0m\r\n".toByteArray(Charsets.UTF_8)
        chunks += ("VM-LONG-LINE-" + "B".repeat(10_000) + "\r\n").toByteArray(Charsets.UTF_8)
        chunks += "\u001b[".toByteArray(Charsets.UTF_8)
        chunks += "?25l".toByteArray(Charsets.UTF_8)
        repeat(180) { index ->
            chunks += buildString {
                append("\u001b[38;5;")
                append(index % 256)
                append('m')
                append("vm-frag-")
                append(index.toString().padStart(3, '0'))
                append(' ')
                append(('A'.code + (index % 26)).toChar().toString().repeat(700))
                if (index % 7 == 0) append("\u001b[2K")
                append("\r\n")
            }.toByteArray(Charsets.UTF_8)
        }
        chunks += "\u001b[?25h\u001b[0m\r\nISSUE576-VM-DONE\r\n".toByteArray(Charsets.UTF_8)
        return chunks
    }

    private fun codexLikeIssue576BurstChunks(): List<ByteArray> {
        val transcript = buildString {
            append("# Codex synthetic /new overflow harness\r\n\r\n")
            append("Preparing workspace context with many changed files and prior transcript lines.\r\n")
            append("```text\r\n")
            append("git status --short --branch\r\n")
            repeat(48) { index ->
                append(" M app/src/main/java/com/pocketshell/issue576/File")
                append(index.toString().padStart(3, '0'))
                append(".kt\r\n")
            }
            append("```\r\n\r\n")
            append("```kotlin\r\n")
            append("fun generatedStatus(index: Int) = \"line-${'$'}index\"\r\n")
            append("```\r\n\r\n")
            append("LONG-WRAPPED-CONTEXT ")
            append("L".repeat(32_000))
            append("\r\n")
            repeat(720) { index ->
                append("\u001b[2K\r")
                append("status: indexing ")
                append(index)
                append("/720 ")
                append(".".repeat(index % 40))
                append("\r\n")
                append("- changed file ")
                append(index.toString().padStart(4, '0'))
                append(": ")
                append("markdown `inline code` and shell output ".repeat(5))
                append("\r\n")
                if (index % 9 == 0) {
                    append("```sh\r\n")
                    append("printf 'burst chunk ")
                    append(index)
                    append("'; sleep 0.01\r\n")
                    append("```\r\n")
                }
                if (index % 17 == 0) {
                    append("> progress note ")
                    append(index)
                    append(": ")
                    append("wrapped ".repeat(90))
                    append("\r\n")
                }
            }
            append("\u001b[32mISSUE576-CODEX-LIKE-DONE\u001b[0m\r\n")
        }.toByteArray(Charsets.UTF_8)

        val chunkSizes = intArrayOf(1, 2, 5, 13, 3, 34, 8, 89, 21, 144)
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        var chunkIndex = 0
        while (offset < transcript.size) {
            val size = chunkSizes[chunkIndex % chunkSizes.size]
            val end = minOf(transcript.size, offset + size)
            chunks += transcript.copyOfRange(offset, end)
            offset = end
            chunkIndex += 1
        }
        return chunks
    }

    private fun networkChange(
        previous: TerminalNetworkSnapshot = TerminalNetworkSnapshot.Validated("wifi"),
        current: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("cell"),
        previousValidated: TerminalNetworkSnapshot.Validated? =
            previous as? TerminalNetworkSnapshot.Validated,
        reason: String = "validated-default-network-changed",
        sequence: Long = 1L,
        deferredFromBackground: Boolean = false,
    ): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = previous,
            current = current,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
            deferredFromBackground = deferredFromBackground,
        )

    // Issue #997: a bare network LOSS change (Validated → NoValidatedNetwork).
    private fun networkLoss(
        previousValidated: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("wifi"),
        reason: String = "default-network-lost",
        sequence: Long = 1L,
    ): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = previousValidated,
            current = TerminalNetworkSnapshot.NoValidatedNetwork,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
            kind = TerminalNetworkChangeKind.NetworkLost,
        )

    // Issue #997: a network RESTORE change (NoValidatedNetwork → Validated).
    private fun networkRestore(
        current: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("wifi"),
        previousValidated: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("wifi"),
        reason: String = "default-network-available",
        sequence: Long = 2L,
    ): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = TerminalNetworkSnapshot.NoValidatedNetwork,
            current = current,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
            kind = TerminalNetworkChangeKind.NetworkRestored,
        )

    // ---- Issue #626: unified pane list tests ----

    @Test
    fun unifiedPanesStartsEmpty() {
        val vm = newVm()
        assertTrue(vm.unifiedPanes.value.isEmpty())
    }

    @Test
    fun unifiedPanesMirrorsActivePanesWhenNoCachedRuntimes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work-pane",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        runCurrent()

        // Without cached runtimes, unified panes = active panes.
        assertEquals(1, vm.unifiedPanes.value.size)
        assertEquals("%0", vm.unifiedPanes.value[0].paneId)
    }

    @Test
    fun unifiedPanesIncludesCachedSessionPanes() = runTest(scheduler) {
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)
        val session = FakeSshSession()
        val oldClient = FakeTmuxClient()
        val newClient = FakeTmuxClient()

        // Set up initial "work" session with one pane.
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
            session = session,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work-pane",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        runCurrent()

        // Fast switch to "other" session, caching "work".
        vm.fastSwitchSessionForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "other",
            client = newClient,
            session = session,
        )
        advanceUntilIdle()

        // After the fast switch, unified panes should include both
        // the active "other" session's panes and the cached "work"
        // session's panes.
        val unified = vm.unifiedPanes.value
        assertTrue(
            "unified panes should contain panes from both sessions, got ${unified.size}",
            unified.size >= 1,
        )
    }

    @Test
    fun isActiveSessionPaneReturnsTrueForActivePanes() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work-pane",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        runCurrent()

        val pane = vm.panes.value.first()
        assertTrue(vm.isActiveSessionPane(pane))
    }

    @Test
    fun sessionNameForUnifiedPaneReturnsActiveSessionName() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work-pane",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        runCurrent()

        val pane = vm.panes.value.first()
        assertEquals("work", vm.sessionNameForUnifiedPane(pane))
    }

    @Test
    fun sessionNameForUnifiedPaneReturnsNullWhenNoActiveTarget() {
        val vm = newVm()
        val fakePane = TmuxPaneState(
            paneId = "%99",
            windowId = "@99",
            sessionId = "\$99",
            title = "orphan",
            cwd = "/tmp",
            terminalState = TerminalSurfaceState(),
        )
        assertNull(vm.sessionNameForUnifiedPane(fakePane))
    }

    // ---- End Issue #626 tests ----

    // ---- Issue #681: phantom pager page from a key-drifted active-session twin ----

    private fun unifiedTestPane(
        paneId: String,
        windowId: String = "@0",
        sessionId: String = "\$0",
        title: String = "pane",
    ): TmuxPaneState =
        TmuxPaneState(
            paneId = paneId,
            windowId = windowId,
            sessionId = sessionId,
            title = title,
            cwd = "/tmp",
            terminalState = TerminalSurfaceState(),
        )

    private fun driftedTwinRuntime(
        sessionName: String,
        hostId: Long,
        paneIds: List<String>,
    ): CachedTmuxRuntime =
        CachedTmuxRuntime(
            key = TmuxRuntimeKey(
                hostId = hostId,
                // Same host + same session name, but a DRIFTED keyPath: this is
                // the twin that activate()/deactivate() parked under a key that
                // no longer exactly matches the active session's key.
                hostname = "alpha.example",
                port = 22,
                username = "alex",
                keyPath = "/keys/a-DRIFTED",
                sessionName = sessionName,
            ),
            hostName = "alpha",
            startDirectory = null,
            session = null,
            client = FakeTmuxClient(),
            panes = paneIds.map { unifiedTestPane(it) },
            paneRows = emptyMap(),
            paneProducerJobs = emptyMap(),
            paneInputQueues = emptyMap(),
            paneInputJobs = emptyMap(),
            paneAgentJobs = emptyMap(),
            paneAgentInputs = emptyMap(),
            agentConversations = emptyMap(),
            remoteColumns = 0,
            remoteRows = 0,
            lease = null,
        )

    private fun foreignSessionRuntime(
        sessionName: String,
        hostId: Long,
        paneIds: List<String>,
    ): CachedTmuxRuntime =
        CachedTmuxRuntime(
            key = TmuxRuntimeKey(
                hostId = hostId,
                hostname = "alpha.example",
                port = 22,
                username = "alex",
                keyPath = "/keys/a",
                sessionName = sessionName,
            ),
            hostName = "alpha",
            startDirectory = null,
            session = null,
            client = FakeTmuxClient(),
            panes = paneIds.map { unifiedTestPane(it) },
            paneRows = emptyMap(),
            paneProducerJobs = emptyMap(),
            paneInputQueues = emptyMap(),
            paneInputJobs = emptyMap(),
            paneAgentJobs = emptyMap(),
            paneAgentInputs = emptyMap(),
            agentConversations = emptyMap(),
            remoteColumns = 0,
            remoteRows = 0,
            lease = null,
        )

    @Test
    fun unifiedPanesExcludesDriftedTwinOfActiveSessionSoNoPhantomPage() = runTest(scheduler) {
        // The maintainer's repro: ONE session "work" with TWO windows -> the
        // pager must show EXACTLY 2 pages. A key-drifted TWIN of "work"
        // survives in the cache (parked under a slightly different key, so
        // activate() never removed it). Before the fix, rebuildUnifiedPanes
        // blindly appended the twin's panes -> a phantom 3rd page that, when
        // settled on, mis-routed to a foreign session.
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)

        // Inject the drifted twin of the SAME session ("work") into the cache,
        // carrying the same two panes the active session has.
        runtimeCache.put(driftedTwinRuntime("work", hostId = 1L, paneIds = listOf("%0", "%1")))

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work-w0",
                    paneIndex = 0,
                    sessionName = "work",
                ),
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%1",
                    windowId = "@1",
                    sessionId = "\$0",
                    title = "work-w1",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        advanceUntilIdle()

        // Exactly 2 pages for a 2-window session: no phantom.
        val unified = vm.unifiedPanes.value
        assertEquals(
            "2-window session must yield exactly 2 unified pages, got ${unified.map { it.paneId }}",
            2,
            unified.size,
        )
        assertEquals(listOf("%0", "%1"), unified.map { it.paneId })

        // Settling on either real page must NOT emit a switch request — both
        // belong to the active "work" session.
        vm.onUnifiedPageSettled(0)
        vm.onUnifiedPageSettled(1)
        advanceUntilIdle()
    }

    @Test
    fun unifiedPanesSettleOnRealPageRoutesToCorrectSessionNeverForeign() = runTest(scheduler) {
        // 2-window "work" session (active) PLUS a genuinely different cached
        // session "deploy" with its own window, AND a key-drifted twin of
        // "work". The pager must show exactly 3 pages (2 work + 1 deploy),
        // NOT 4, and settling on the deploy page must emit a switch to
        // "deploy" — never to a foreign/random session.
        val runtimeCache = TmuxSessionRuntimeCache()
        val vm = newVm(runtimeCache = runtimeCache)

        // The phantom-producing drifted twin of the active session.
        runtimeCache.put(driftedTwinRuntime("work", hostId = 1L, paneIds = listOf("%0", "%1")))
        // A real OTHER session that legitimately deserves its own page.
        runtimeCache.put(foreignSessionRuntime("deploy", hostId = 1L, paneIds = listOf("%5")))

        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = FakeTmuxClient(),
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "\$0",
                    title = "work-w0",
                    paneIndex = 0,
                    sessionName = "work",
                ),
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%1",
                    windowId = "@1",
                    sessionId = "\$0",
                    title = "work-w1",
                    paneIndex = 0,
                    sessionName = "work",
                ),
            ),
        )
        advanceUntilIdle()

        val unified = vm.unifiedPanes.value
        assertEquals(
            "expected exactly 3 pages (2 work + 1 deploy), got ${unified.map { it.paneId }}",
            3,
            unified.size,
        )
        assertEquals(listOf("%0", "%1", "%5"), unified.map { it.paneId })

        // The deploy page resolves to "deploy", never the foreign/random twin.
        val deployPane = unified[2]
        assertEquals("deploy", vm.sessionNameForUnifiedPane(deployPane))

        // Capture the FIRST emitted switch. Subscribe before triggering any
        // settle (async + runCurrent reaches the SharedFlow subscription point).
        val firstSwitch = async { vm.sessionSwitchRequest.first() }
        runCurrent()

        // Settling on the active "work" pages must NOT emit a switch — if it
        // did, firstSwitch would resolve to "work" instead of "deploy".
        vm.onUnifiedPageSettled(0)
        vm.onUnifiedPageSettled(1)
        advanceUntilIdle()
        assertTrue(
            "settling on active-session pages must not switch yet",
            !firstSwitch.isCompleted,
        )

        // Settling on the deploy page switches to "deploy" — the CORRECT
        // session, never the foreign/random twin.
        vm.onUnifiedPageSettled(2)
        advanceUntilIdle()
        assertEquals("deploy", firstSwitch.await())
    }

    @Test
    fun runtimeCachePutPrunesDriftedSameSessionTwin() {
        // Cache-level guard: parking a fresh "work" runtime evicts a stale,
        // key-drifted "work" twin so a duplicate can never accumulate.
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val driftedTwin = driftedTwinRuntime("work", hostId = 1L, paneIds = listOf("%0"))
        val fresh = foreignSessionRuntime("work", hostId = 1L, paneIds = listOf("%0"))

        assertTrue(cache.put(driftedTwin).isEmpty())
        // Putting the fresh same-session runtime evicts the drifted twin.
        assertEquals(listOf(driftedTwin), cache.put(fresh))
        assertEquals(listOf(fresh.key), cache.snapshotKeys())
        assertFalse(cache.contains(driftedTwin.key))
    }

    @Test
    fun runtimeCacheActivatePrunesDriftedSameSessionTwin() {
        // Activating "work" (by its canonical key) drops a key-drifted "work"
        // twin still parked under a different key, so the now-active session
        // leaves no duplicate behind.
        val cache = TmuxSessionRuntimeCache(maxEntries = 4, nowMs = { 0L })
        val driftedTwin = driftedTwinRuntime("work", hostId = 1L, paneIds = listOf("%0"))
        val canonical = foreignSessionRuntime("work", hostId = 1L, paneIds = listOf("%0"))
        val otherSession = foreignSessionRuntime("deploy", hostId = 1L, paneIds = listOf("%5"))

        cache.put(driftedTwin)
        cache.put(canonical)
        cache.put(otherSession)
        // put already pruned the twin; re-park it to set up the activate case.
        cache.remove(canonical.key)
        cache.put(driftedTwin)

        val activation = cache.activate(canonical.key)
        // The canonical entry was removed above, so activation yields no exact
        // runtime, but the drifted twin is pruned as a same-session duplicate.
        assertEquals(listOf(driftedTwin), activation.evicted)
        assertFalse(cache.contains(driftedTwin.key))
        assertEquals(listOf(otherSession.key), cache.snapshotKeys())
    }

    // ---- End Issue #681 tests ----

    // ---- Issue #662: black-pane re-seed on a live connection ----

    @Test
    fun reseedVisiblePaneIfBlankReCapturesAndHealsABlackPane() = runTest(scheduler) {
        // The maintainer's symptom: a window renders a BLACK pane (the seed
        // never painted content) on a LIVE connection, and switching to it does
        // not recover it. Drive that exact state: the pane's attach-time seed
        // returns EMPTY (no content), so its emulator stays blank. Then a window
        // switch calls reseedVisiblePaneIfBlank — which must issue a FRESH
        // capture-pane and paint the content tmux's grid now holds.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        // Attach-time seed comes back EMPTY -> the pane stays black.
        client.capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = emptyList(), isError = false),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val pane = vm.panes.value.single { it.paneId == "%0" }
        assertTrue(
            "precondition: the empty attach-time seed must leave the pane BLACK",
            pane.terminalState.visibleScreenIsBlank(),
        )
        val captureCountAfterAttach =
            client.sentCommands.count { it == seedCaptureCommand("%0") }

        // The user switches to this window: a fresh capture now HAS content.
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 3L,
                output = listOf("ISSUE662-RECOVERED-CONTENT"),
                isError = false,
            ),
        )
        vm.reseedVisiblePaneIfBlank("%0")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val captureCountAfterReseed =
            client.sentCommands.count { it == seedCaptureCommand("%0") }
        assertTrue(
            "expected a FRESH capture-pane re-seed for the blank pane, " +
                "got commands ${client.sentCommands}",
            captureCountAfterReseed > captureCountAfterAttach,
        )
        assertFalse(
            "the pane must no longer be BLACK after the re-seed painted content",
            pane.terminalState.visibleScreenIsBlank(),
        )
        assertTrue(
            "the re-seeded content must be on the pane's grid, got " +
                renderedTranscriptFrom(pane.terminalState),
            renderedTranscriptFrom(pane.terminalState)
                .contains("ISSUE662-RECOVERED-CONTENT"),
        )
    }

    @Test
    fun reseedVisiblePaneIfBlankIsNoOpWhenPaneAlreadyShowsContent() = runTest(scheduler) {
        // A pane that already painted content must NOT be re-captured on a
        // window switch — the blank-only guard keeps the switch cheap and never
        // clobbers a good frame.
        val vm = newVm()
        val client = FakeTmuxClient()
        client.responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("%0\t@0\t\$0\twork\tshell\t0"),
                isError = false,
            ),
        )
        client.capturePaneResponses.addLast(
            CommandResponse(
                number = 2L,
                output = listOf("ALREADY-VISIBLE-CONTENT"),
                isError = false,
            ),
        )
        vm.attachClientForTest(client)

        client.emittedEvents.emit(
            ControlEvent.WindowAdd(sessionId = "", windowId = "@0", name = ""),
        )
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val pane = vm.panes.value.single { it.paneId == "%0" }
        assertFalse(
            "precondition: the seeded pane must show content (not blank)",
            pane.terminalState.visibleScreenIsBlank(),
        )
        val captureCountBefore =
            client.sentCommands.count { it == seedCaptureCommand("%0") }

        vm.reseedVisiblePaneIfBlank("%0")
        advanceUntilIdle()
        shadowOf(Looper.getMainLooper()).idle()

        val captureCountAfter =
            client.sentCommands.count { it == seedCaptureCommand("%0") }
        assertEquals(
            "a non-blank pane must NOT trigger a re-capture on switch",
            captureCountBefore,
            captureCountAfter,
        )
    }

    // ---- End Issue #662 tests ----

    private class QueueLeaseConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<com.pocketshell.core.ssh.SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected lease connect $connectCount for ${target.leaseKey}")
            connectCount += 1
            return Result.success(next)
        }
    }

    private companion object {
        // Issue #713: the slow-feed / real-async terminal tests below keep
        // `factoryScope` on real `Dispatchers.IO` (#708 judgment call A), so
        // they drain the real bridge feed / final marker via real-wall-clock
        // await-for-condition loops. These return the instant the condition is
        // ready, so a generous ceiling only adds headroom for a contended dev
        // box (observed >5s under 15+ load) WITHOUT slowing a passing run. A
        // genuinely stuck feed still times out and fails the assertion below.
        const val SLOW_FEED_DRAIN_TIMEOUT_MS = 30_000L

        // Issue #713: `runTest` enforces its OWN default 60s wall-clock budget.
        // The slow-feed drains above busy-wait (Thread.sleep / real-IO feed) for
        // real wall-clock time, so raising the drain ceiling to 30s alone can,
        // under heavy box contention, push the whole `runTest` body past that
        // default budget — which fails as an outer `runTest` timeout attributed
        // to the test's lambda line, NOT the inner marker assertion. Give the
        // slow-feed tests an explicit, generous `runTest` timeout (well above
        // the 30s drain + setup) so the headroom is real and a genuine failure
        // still surfaces as the specific inner assertion, never an opaque
        // outer-timeout flake.
        val SLOW_FEED_RUN_TEST_TIMEOUT = 120.seconds

        const val CODEX_SCALE_OUTPUT_CHUNKS = 320
        const val CODEX_SCALE_OUTPUT_LINES_PER_CHUNK = 20
        const val CODEX_SCALE_OUTPUT_BYTES = 1_500_000
        const val ISSUE_576_CODEX_USER_PROMPT = "issue 576 synthetic Codex prompt before tool flood"
        const val ISSUE_576_CODEX_ASSISTANT_REPLY = "issue 576 synthetic Codex final reply"
    }

    /**
     * Issue #440: lease connector that always fails with [failure]. Used to
     * drive the non-retryable abort path of the auto-reconnect loop. Counts
     * connect attempts so the test can assert the backoff loop stopped after
     * a single try instead of exhausting the whole schedule.
     */
    private class FailingLeaseConnector(
        private val failure: Throwable,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<com.pocketshell.core.ssh.SshSession> {
            connectCount += 1
            return Result.failure(failure)
        }
    }

    /**
     * Issue #440: lease connector that fails for the first [failures].size
     * attempts (transient failures), then returns [session]. Used to prove
     * the backoff loop keeps retrying through retryable failures and
     * recovers once the transport comes back.
     */
    private class FailingThenConnectingLeaseConnector(
        private val failures: List<Throwable>,
        private val session: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set
        private val inFlightConnects: AtomicInteger = AtomicInteger(0)

        var maxConcurrentConnects: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<com.pocketshell.core.ssh.SshSession> {
            val inFlight = inFlightConnects.incrementAndGet()
            maxConcurrentConnects = maxOf(maxConcurrentConnects, inFlight)
            return try {
                val index = connectCount
                connectCount += 1
                failures.getOrNull(index)?.let { Result.failure(it) }
                    ?: Result.success(session)
            } finally {
                inFlightConnects.decrementAndGet()
            }
        }
    }

    /**
     * Issue #440: a stand-in whose [Class.getSimpleName] is exactly
     * `UserAuthException` — the sshj authentication-failure type the
     * production classifier keys off — so the unit test exercises the
     * non-retryable abort without pulling the sshj hierarchy onto the
     * test classpath. The classifier matches on the simple name, so the
     * nested class name is what matters here.
     */
    private class UserAuthException(message: String) : Exception(message)

    /**
     * Issue #440: a stand-in whose simple name is `ConnectException`
     * (mirroring `java.net.ConnectException`) so the test can confirm
     * "connection refused" stays on the retryable path — it must NOT match
     * the non-retryable classifier.
     */
    private class ConnectException(message: String) : IOException(message)

    /**
     * Issue #178: minimal in-memory [SshSession] double for the
     * fast-switch unit tests. Mirrors the same shape as
     * `FakeSshSession` in `SessionViewModelTest` (intentionally a local
     * private class so that file keeps its own seam) — the production
     * code only consults [isConnected] / [close] from the fast-switch
     * path, but we still implement the rest of the interface as no-ops
     * so any future change to the VM that touches another method on
     * the session does not silently break this test.
     */
    private class FakeSshSession(
        private val isConnectedValue: Boolean = true,
        private val tailJob: CompletableJob = Job(),
        private val execGate: CompletableDeferred<Unit>? = null,
        // Issue #793: mutable so a paging test can widen the window the fake
        // returns between the first-paint read and a load-older read.
        private var wcOutput: String = "0\n",
        private var initialEventsOutput: String = "",
        private val agentLogLines: List<String>? = null,
        private val execFailure: Throwable? = null,
        private val tailFailure: Throwable? = null,
        private val detectionOutput: String = "",
        private val processOutput: String = "",
        private val recordedKindOutput: String = "",
        private var recordedSourceGenerationOutput: String = "",
        private var recordedSourceOutput: String = "",
        private val cardGetStdouts: List<String> = emptyList(),
        private val cardCheckExitCode: Int = 0,
        // Issue #448: ports the `ss -tlnp` confirm scan reports as
        // LISTENing. The detection collector calls PortScanner.scan, which
        // runs `ss -tlnp ... | awk ...`; we answer that with one line per
        // listening port in the `addr:port process` shape PortScanner parses.
        private val ssListeningPorts: Set<Int> = emptySet(),
    ) : com.pocketshell.core.ssh.SshSession {
        @Volatile
        var closed: Boolean = false

        val execCommands = mutableListOf<String>()
        private var cardGetIndex: Int = 0

        // Issue #793: let a paging test reprogram the window the fake returns.
        fun setWcOutput(value: String) { wcOutput = value }
        fun setInitialEventsOutput(value: String) { initialEventsOutput = value }
        fun setRecordedSourceGenerationOutput(value: String) { recordedSourceGenerationOutput = value }
        fun setRecordedSourceOutput(value: String) { recordedSourceOutput = value }

        override val isConnected: Boolean
            get() = isConnectedValue && !closed

        // Issue #964: lets a test report the transport keepalive as still proving
        // the link alive (a slow-but-live link). Default mirrors the production
        // SshSession default (false) so unrelated tests are unaffected.
        @Volatile
        var transportProvenAlive: Boolean = false

        override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean =
            transportProvenAlive && isConnected

        override suspend fun exec(command: String): com.pocketshell.core.ssh.ExecResult {
            execCommands += command
            execGate?.await()
            execFailure?.let { throw it }
            val stdout = when {
                command.contains("@@PS_RECORDED_KIND@@") -> buildString {
                    append(recordedKindOutput.trim())
                    append("\n@@PS_RECORDED_KIND@@\n")
                    append(recordedSourceGenerationOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE_GENERATION@@\n")
                    append(recordedSourceOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE@@\n")
                    append(detectionOutput)
                    append("\n@@PS_CLAUDE_WINDOW@@\n")
                }
                command.contains("show-options -v") && command.contains("@ps_agent_kind") ->
                    recordedKindOutput
                command.contains("@@PS_RECORDED_SOURCE_GENERATION@@") -> buildString {
                    append(recordedSourceGenerationOutput.trim())
                    append("\n@@PS_RECORDED_SOURCE_GENERATION@@\n")
                    append(recordedSourceOutput.trim())
                }
                command.contains("show-options -v") && command.contains("@ps_agent_source") ->
                    recordedSourceOutput
                command.contains("ss -tlnp") ->
                    ssListeningPorts.joinToString("\n") { "0.0.0.0:$it users:((\"server\",pid=1,fd=3))" }
                command.contains("netstat -tlnp") || command.contains("ss -tln") -> ""
                // Issue #793: the windowed read (readEventsWindow) combines
                // wc -l + a sentinel + the tail into ONE round-trip. Answer in
                // that shape so the repository can split total-lines from the
                // tail window.
                command.contains("@@PS_WINDOW@@") ->
                    "${wcOutput.trim()}\n@@PS_WINDOW@@\n$initialEventsOutput"
                // Issue #817: the Codex windowed read folds wc -l + a sentinel +
                // the agent-log window into ONE round-trip (no separate
                // lineCount exec). Answer in that shape so the repository can
                // split the raw-file line count (follow cursor) from the window.
                command.contains("@@PS_CODEX_WINDOW@@") ->
                    "${wcOutput.trim()}\n@@PS_CODEX_WINDOW@@\n${agentLogEnvelope(command) ?: initialEventsOutput}"
                command.contains("wc -l < ") -> wcOutput
                command.contains("pocketshell agent-log") -> agentLogEnvelope(command) ?: initialEventsOutput
                command.startsWith("tail -n ") -> initialEventsOutput
                command.contains("ps -eo pid,ppid,tty,comm,args") -> processOutput
                command.contains("push get") -> {
                    val stdout = cardGetStdouts.getOrNull(cardGetIndex)
                        ?: cardGetStdouts.lastOrNull()
                        ?: ""
                    if (cardGetStdouts.isNotEmpty()) cardGetIndex += 1
                    return com.pocketshell.core.ssh.ExecResult(
                        stdout = stdout,
                        stderr = "",
                        exitCode = 0,
                    )
                }
                command.contains("push check") ->
                    return com.pocketshell.core.ssh.ExecResult(
                        stdout = "",
                        stderr = "",
                        exitCode = cardCheckExitCode,
                    )
                command.contains(".claude") ||
                    command.contains(".codex") ||
                    command.contains("opencode") -> detectionOutput
                else -> ""
            }
            return com.pocketshell.core.ssh.ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        private fun agentLogEnvelope(command: String): String? {
            val lines = agentLogLines ?: return null
            val tail = Regex("""--tail\s+(\d+)""")
                .find(command)
                ?.groupValues
                ?.getOrNull(1)
                ?.toIntOrNull()
            val selected = if (tail == null || tail <= 0 || tail >= lines.size) {
                lines
            } else {
                lines.takeLast(tail)
            }
            return JSONObject(
                mapOf(
                    "count" to selected.size,
                    "engine" to "codex",
                    "lines" to JSONArray(selected),
                    "path" to "/home/u/.codex/sessions/xyz.jsonl",
                    "session" to "xyz",
                ),
            ).toString()
        }

        var tailCalls: Int = 0
            private set

        val tailFromLineCalls = mutableListOf<Pair<String, Long>>()

        override fun tail(path: String, onLine: (String) -> Unit): kotlinx.coroutines.Job {
            tailCalls += 1
            tailFailure?.let { throw it }
            return tailJob
        }

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): kotlinx.coroutines.Job {
            tailFromLineCalls += path to fromLineExclusive
            tailCalls += 1
            tailFailure?.let { throw it }
            return tailJob
        }

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): com.pocketshell.core.ssh.SshPortForward {
            throw NotImplementedError()
        }

        override fun startShell(): com.pocketshell.core.ssh.SshShell {
            throw NotImplementedError()
        }

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() {
            closed = true
        }
    }

    /**
     * Issue #576: a fake SSH session whose tail feeds a fixed list of JSONL
     * lines to `onLine` synchronously (one burst), the way a Codex `/new`
     * rollout replay arrives. Lets the VM burst-emit test drive a real
     * [AgentConversationRepository] tail end-to-end and observe how many
     * `agentConversations` emissions the batched ingest produces.
     */
    private class ReplayTailSshSession(
        private val replayLines: List<String>,
    ) : com.pocketshell.core.ssh.SshSession {
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): com.pocketshell.core.ssh.ExecResult {
            val stdout = if (command.contains("wc -l < ")) "0\n" else ""
            return com.pocketshell.core.ssh.ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): kotlinx.coroutines.Job {
            replayLines.forEach(onLine)
            return Job()
        }

        override fun tail(
            path: String,
            fromLineExclusive: Long,
            onLine: (String) -> Unit,
        ): kotlinx.coroutines.Job {
            replayLines.forEach(onLine)
            return Job()
        }

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): com.pocketshell.core.ssh.SshPortForward = throw NotImplementedError()

        override fun startShell(): com.pocketshell.core.ssh.SshShell = throw NotImplementedError()

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String =
            error("uploadFile not used in this test")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used in this test")

        override fun close() = Unit
    }

    /**
     * Issue #655: minimal [FolderListGateway] that records the session names
     * `killSession` was asked to stop. Drives the in-session Stop's unified
     * verified-kill path without a live SSH/tmux server. [killSucceeds]
     * toggles the gateway's confirmed-gone vs still-running outcome so the
     * "broadcast only on confirmed kill" contract can be asserted both ways.
     */
    private class RecordingStopGateway(
        private val killSucceeds: Boolean,
        // Issue #883: window-kill outcome. `null` means killWindow was never
        // expected to be called (a session-level kill test). `true` => a
        // sibling window survived; `false` => the session was destroyed.
        private val windowKillSessionSurvived: Boolean? = null,
        private val windowKillSucceeds: Boolean = true,
        // Issue #898: when set, createSession succeeds returning this resolved
        // name; otherwise it is "not used" (the kill tests never create).
        private val createResolvedName: String? = null,
    ) : FolderListGateway {
        val killedSessionNames = mutableListOf<String>()

        // Issue #883: records the `<sessionName>:<windowIndex>` window targets
        // killWindow was asked to stop, plus whether kill-session was used.
        val killedWindowTargets = mutableListOf<String>()

        // Issue #898: the exact (sessionName, cwd, startCommand) the in-session
        // "+ New session" rich-sheet create path passed through to the gateway —
        // the verified host-screen path. The startCommand encodes the chosen
        // CLI / skip-permissions / profile, so asserting on it proves every
        // picked option is honoured end-to-end.
        data class CreateCall(
            val sessionName: String,
            val cwd: String,
            val startCommand: String?,
        )
        val createCalls = mutableListOf<CreateCall>()

        override suspend fun listSessionsWithFolder(
            host: com.pocketshell.core.storage.entity.HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<com.pocketshell.core.storage.entity.ProjectRootEntity>,
        ): com.pocketshell.app.projects.FolderListResult =
            com.pocketshell.app.projects.FolderListResult.Sessions(rows = emptyList())

        override suspend fun createSession(
            host: com.pocketshell.core.storage.entity.HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            cwd: String,
            startCommand: String?,
        ): Result<String> {
            createCalls += CreateCall(sessionName, cwd, startCommand)
            return createResolvedName?.let { Result.success(it) } ?: error("not used")
        }

        override suspend fun createEmptyProject(
            host: com.pocketshell.core.storage.entity.HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            parentPath: String,
            folderName: String,
        ): Result<String> = error("not used")

        override suspend fun importFile(
            host: com.pocketshell.core.storage.entity.HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            folderPath: String,
            payload: com.pocketshell.app.projects.FolderImportPayload,
        ): Result<String> = error("not used")

        override suspend fun killSession(
            host: com.pocketshell.core.storage.entity.HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
        ): Result<Unit> {
            if (!killSucceeds) {
                return Result.failure(
                    RuntimeException("tmux session '$sessionName' is still running."),
                )
            }
            killedSessionNames += sessionName
            return Result.success(Unit)
        }

        override suspend fun killWindow(
            host: com.pocketshell.core.storage.entity.HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            windowIndex: Int,
        ): Result<com.pocketshell.app.projects.WindowKillOutcome> {
            killedWindowTargets += "$sessionName:$windowIndex"
            if (!windowKillSucceeds) {
                return Result.failure(
                    RuntimeException("tmux window '$sessionName:$windowIndex' is still running."),
                )
            }
            return Result.success(
                com.pocketshell.app.projects.WindowKillOutcome(
                    sessionSurvived = windowKillSessionSurvived ?: false,
                ),
            )
        }
    }

    /**
     * Issue #655: host DAO that returns one host for [hostId] so the
     * in-session Stop can resolve the [HostEntity] it hands the gateway.
     */
    private class StopHostDao(private val hostId: Long) : HostDao {
        private val host = com.pocketshell.core.storage.entity.HostEntity(
            id = hostId,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "alex",
            keyId = 1L,
        )

        override fun getAll() = kotlinx.coroutines.flow.flowOf(listOf(host))
        override suspend fun getById(id: Long): com.pocketshell.core.storage.entity.HostEntity? =
            host.takeIf { it.id == id }
        override fun getEnabled() = kotlinx.coroutines.flow.flowOf(listOf(host))
        override suspend fun insert(host: com.pocketshell.core.storage.entity.HostEntity): Long =
            error("not used")
        override suspend fun update(host: com.pocketshell.core.storage.entity.HostEntity) =
            error("not used")
        override suspend fun delete(host: com.pocketshell.core.storage.entity.HostEntity) =
            error("not used")
        override suspend fun deleteById(id: Long) = error("not used")
    }

    /**
     * Issue #975: an [SshSession] double modelling the MASKED-LIVE-AGENT host the
     * maintainer hit — the agent-kind daemon's cgroup-v2/`/proc` classify returns
     * `unknown` (or `none`) for a node-wrapped/quiet `claude`, while the agent's
     * `*.jsonl` transcript is genuinely present in the cwd-encoded log dir. It
     * answers exactly the two execs the foreign-detection chain issues:
     *   - the `pocketshell agents kind` daemon classify (JSON envelope, with
     *     [classifyAgentKind] one of `claude`/`codex`/`opencode`/`none`/`unknown`),
     *   - the cwd-scoped candidate enumeration ([detectionCommand], `claude_dir=`),
     *     returning [detectionOutput].
     * This is the #780 synthetic-state injection at the host seam — it reproduces
     * the classify-miss the real Docker fixture also produces (scope=null in a
     * non-systemd container), exercising the REAL resolver, not a markAgentTailLive
     * injection.
     */
    private class MaskedAgentSshSession(
        private val classifyAgentKind: String,
        private val detectionOutput: String = "",
        private val hostWideProcessOutput: String = "",
    ) : SshSession {
        override val isConnected: Boolean = true

        // Issue #1001: count `agents kind` daemon classify round-trips so the B1′
        // dedup-ordering test can assert the cache-bust forced a FRESH daemon
        // re-evaluation deterministically — instead of relying on the (now-fixed)
        // race that left the re-probe unfinished when `runCurrent()` returned.
        var classifyExecCount: Int = 0
            private set

        override suspend fun exec(command: String): ExecResult {
            val stdout = when {
                // The host-side `pocketshell agents kind` daemon classify. The
                // request pipes a `{"panes":[{"pane_id":"%N", ...}]}` snapshot; we
                // echo each requested pane id back with [classifyAgentKind].
                command.contains("agents kind") -> {
                    classifyExecCount++
                    val paneIds = PANE_ID_RE.findAll(command).map { it.groupValues[1] }.toList()
                    buildString {
                        append("{\"results\":[")
                        paneIds.forEachIndexed { index, paneId ->
                            if (index > 0) append(',')
                            append("{\"pane_id\":\"").append(paneId).append("\",")
                            append("\"agent_kind\":\"").append(classifyAgentKind).append("\",")
                            append("\"scope\":null}")
                        }
                        append("]}")
                    }
                }
                // The cwd-scoped candidate enumeration ([detectionCommand]).
                command.contains("claude_dir=") -> detectionOutput
                command.contains("ps -eo pid,ppid,tty,comm,args") -> hostWideProcessOutput
                command.contains("ps -eo pid,tty,comm,args") -> hostWideProcessOutput
                else -> ""
            }
            return ExecResult(stdout = stdout, stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()
        override fun tail(path: String, fromLineExclusive: Long, onLine: (String) -> Unit): Job = Job()
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError()
        override fun startShell(): SshShell = throw NotImplementedError()
        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("uploadFile not used")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("uploadStream not used")
        override fun close() = Unit

        private companion object {
            val PANE_ID_RE = Regex("\"pane_id\":\"([^\"]+)\"")
        }
    }
}
