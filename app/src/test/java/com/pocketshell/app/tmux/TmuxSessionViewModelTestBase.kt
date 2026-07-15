package com.pocketshell.app.tmux

import com.pocketshell.app.cards.SessionCardsRemoteSource
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.testsupport.drainMainLooperUntil as drainMainLooperUntilShared
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.json.JSONArray
import org.json.JSONObject

// Issue #713: generous wall-clock ceiling for slow-feed / real-async terminal
// drains. These waits return immediately when ready; the ceiling only gives CI
// headroom on contended boxes.
private const val SLOW_FEED_DRAIN_TIMEOUT_MS = 30_000L

// Issue #1110: wall-clock ceiling for the shared settle pump, kept below the
// default runTest budget so the pump fails with its own diagnostic first.
private const val AWAIT_CONDITION_TIMEOUT_MS = 20_000L

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
abstract class TmuxSessionViewModelTestBase {

    // Issue #708: ONE virtual-clock scheduler shared by `runTest(scheduler)`,
    // `Dispatchers.Main`, and every test's SshLeaseManager. The lease's bounded
    // cold connect (#687, b5733d33) defaults to a real `Dispatchers.IO` + wall
    // clock; under a virtual `runTest` clock that strands the dial so
    // `advanceUntilIdle()` never drives it. Binding the lease + Main to this one
    // scheduler makes the lease/connect path deterministic.
    protected val scheduler = TestCoroutineScheduler()

    // Keep the existing UnconfinedTestDispatcher Main semantics (eager-on-advance)
    // that this suite's assertions already rely on — but bind it to the SHARED
    // scheduler so Main, runTest, and the lease clock stay in lockstep.
    // Hold the exact instance so the #576 reconcile/apply dispatcher pins can
    // reference the SAME dispatcher the rule installs as Main — the ViewModel's
    // `applyOnMain` inline-detection compares interceptor identity, and under
    // `Dispatchers.setMain(x)` the running coroutine's interceptor is `x`, not
    // the `Dispatchers.Main` delegate.
    protected val testMainDispatcher = UnconfinedTestDispatcher(scheduler)

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule(testMainDispatcher)

    // factoryScope stays on REAL `Dispatchers.IO`: the TmuxClientFactory drives a
    // genuine background IO read/feed loop that the terminal-feed integration
    // tests pump via the Robolectric Looper, NOT via this scheduler. Moving it
    // onto the virtual clock races that Looper feed and flakes
    // `codexLikeTmuxOutputWithSlowTerminalSideChannel...` (verified empirically:
    // 30/30 green on IO vs ~8% failure on the virtual scheduler).
    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Issue #1085 (F2) test isolation: the application-scoped teardown work
    // ([TmuxSessionViewModel.deferConnectionTeardownOffMain]) is now handed off
    // `onCleared`'s Main thread to a CoroutineScope. In production that is the
    // never-cancelled process singleton [AppTeardownScope.scope]; under unit
    // tests that real-`Dispatchers.IO` singleton is never drained, so every
    // `clearForTest()` (including the `@After` clear of still-connected VMs)
    // would leak an IO teardown coroutine past `runTest`/`resetMain` — and that
    // coroutine touches the test Main dispatcher as it cancels viewModelScope
    // pane jobs, racing the NEXT class's `setMain` ("Dispatchers.Main is used
    // concurrently with setting it"). We instead inject THIS per-test-instance
    // scope into every `newVm`, then cancel+JOIN it in `@After` (exactly like
    // `factoryScope`) so no teardown coroutine survives into the next class.
    // It is a real `Dispatchers.IO` scope (off-Main), so the F2 off-Main
    // hand-off it backs is genuine, not a virtual-clock proxy.
    private val defaultTeardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Issue #1168: third real-`Dispatchers.IO` scope of the same species as
    // factoryScope/defaultTeardownScope. The default `newVm()` builds its
    // AgentConversationRepository with THIS injected scope instead of the
    // repository's own default real-IO `tailScope`, so the test owns the tail
    // drain. The drain runs infinite `while (isActive) { delay() }` loops
    // (AgentConversationRepository JSONL-batch/OpenCode-poll tails); when a
    // follow job completes/cancels, the VM's `invokeOnCompletion` hops back via
    // `bridgeScope.launch { ... }` — and `bridgeScope` is `Dispatchers.Main`-
    // bound — so a foreign IO thread READS `Dispatchers.Main`. Left un-joined,
    // that read races the NEXT test's `setMain`/`resetMain` write and
    // kotlinx-coroutines-test throws "Dispatchers.Main is used concurrently
    // with setting it" (TestMainDispatcher.kt:72), attributed to whichever
    // victim test is doing setMain at that instant. We cancel-THEN-join this
    // scope in @After (the drain is infinite, so cancel first) BEFORE the
    // rule's `resetMain()` runs — exactly the factoryScope rationale.
    private val agentTailScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val createdViewModels = mutableListOf<TmuxSessionViewModel>()

    protected fun newVm(
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
        // Issue #1168: inject the test-owned real-IO `agentTailScope` so the
        // tail drain is joinable in @After (see the field doc). The #576
        // burst-ingest test keeps its own `tailScope = backgroundScope`.
        agentRepository: AgentConversationRepository =
            AgentConversationRepository(tailScope = agentTailScope),
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
        // Issue #1541: default null keeps every existing test's ledger in-memory
        // only (base S1). A test that needs the durable per-row `wireAttempted`
        // flag (survives VM-clear / back-navigation) passes the Robolectric app
        // context.
        applicationContext: android.content.Context? = null,
        // Issue #1587 (H3): the verify-before-resend ledger's durable backing now
        // comes from the INJECTED @Singleton OutboundQueueStore (one instance, one
        // lock — no lost-update race with the orphan sweep), not a store the VM
        // news up from the context. Default: build one over the same app context so
        // durable-flag tests keep their backing over the shared `outbound_queue`
        // prefs (production injects the singleton over the SAME prefs). A test can
        // pass a spy/shared store to prove the VM threads that exact instance.
        outboundQueueStore: com.pocketshell.app.composer.OutboundQueueStore? =
            applicationContext?.let {
                com.pocketshell.app.composer.SharedPrefsOutboundQueueStore(it)
            },
        // Issue #1617: reconnect stress scenarios can inject a scenario-owned
        // virtual scope instead of sharing this class's real-IO factoryScope.
        // The default preserves the genuine reader-loop coverage used by the
        // terminal-feed integration tests documented above.
        tmuxClientFactory: TmuxClientFactory = TmuxClientFactory(factoryScope),
    ): TmuxSessionViewModel =
        TmuxSessionViewModel(
            tmuxClientFactory = tmuxClientFactory,
            activeTmuxClients = registry,
            hostDao = hostDao,
            folderListGateway = folderListGateway,
            runtimeCache = runtimeCache,
            agentSessionMemory = agentSessionMemory,
            sshLeaseManager = sshLeaseManager,
            sessionLifecycleSignals = sessionLifecycleSignals,
            agentRepository = agentRepository,
            agentKindRemoteSource = agentKindRemoteSource,
            applicationContext = applicationContext,
            outboundQueueStore = outboundQueueStore,
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
            // Issue #1085 (F2) test isolation: route the off-Main connection
            // teardown to this per-test-instance scope (drained in `@After`)
            // instead of the never-cancelled production singleton, so no
            // teardown coroutine leaks into the next test class. Individual F2
            // off-Main tests may override this with a virtual-clock scope.
            it.setTeardownScopeForTest(defaultTeardownScope)
            createdViewModels += it
        }

    /**
     * Issue #1110: wall-clock-bounded settle pump (the de-flake convention's
     * Shape B — docs/testing.md "the one de-flake convention", #1048/#1102).
     *
     * The previous body bounded the wait in VIRTUAL time
     * (`withTimeoutOrNull(1.seconds) { runCurrent(); delay(10) }`): under the
     * `runTest` virtual clock those ~100 `delay(10)` iterations elapse in ~0
     * wall-clock time, so work the predicate awaits that hops to a REAL
     * dispatcher gets NO wall-clock scheduling time. The cache-restore re-seed
     * the `cacheRestore*` tests assert on is produced by
     * `restoreCachedRuntime -> refreshCurrentSessionRecordedKind`, whose
     * recorded-kind read runs inside `withContext(Dispatchers.IO)` (a real
     * off-Main thread — correct for production). Under CI CPU contention that
     * real read had not landed before the virtual budget exhausted, so the
     * load-bearing assertion flaked and reddened the required `Unit tests`
     * check on unrelated PRs/`main`.
     *
     * Crucially this pump only `runCurrent()`s — it NEVER advances the virtual
     * clock (`advanceUntilIdle`/`advanceTimeBy`). The work the predicate awaits
     * resumes off a real dispatcher and re-dispatches its continuation back onto
     * the virtual Main at the CURRENT virtual time, so `runCurrent()` drains it
     * without moving the clock. Advancing the clock would prematurely fire
     * scheduled `delay`-based timers — e.g. the conversation load watchdog that
     * flips a freshly-seeded row `Loading -> Failed` (the #793 timer), which is
     * exactly what an `advanceUntilIdle()` pump surfaced here. So each tick:
     * drain ready Main continuations (`runCurrent()`), check the predicate, then
     * yield real wall-clock time (`Thread.sleep(1)`) so the off-Main thread can
     * make progress before the next drain — to a GENEROUS wall-clock deadline.
     * It returns the instant the predicate holds (no slowdown on a healthy run)
     * and HARD-FAILS on the pump's exit condition if it never does — the
     * load-bearing assertion stays the predicate, never weakened. Same proven
     * real-wall-clock-drain shape as [waitForSentCommandCount].
     */
    protected fun TestScope.awaitCondition(predicate: () -> Boolean) {
        // Issue #1048: the bounded-wall-clock loop + the HARD deadline now live in
        // the ONE shared, audited settle-pump. The per-tick drain here is
        // `runCurrent()` ONLY — it deliberately does NOT idle the main looper,
        // because advancing the looper clock could trip the #793 re-seed watchdog
        // (the #1110 lesson). `runCurrent()` drains Main continuations after the
        // real-Dispatchers.IO recorded-kind read WITHOUT advancing the virtual
        // clock.
        val settled = drainMainLooperUntilShared(
            deadlineMs = AWAIT_CONDITION_TIMEOUT_MS,
            sleepMs = 1L,
            onTick = { runCurrent() },
            condition = predicate,
        )
        assertTrue(
            "awaitCondition timed out after ${AWAIT_CONDITION_TIMEOUT_MS}ms wall-clock before the " +
                "predicate held — the work it awaits (e.g. the real-Dispatchers.IO recorded-kind " +
                "read driving the cache-restore re-seed) never drained (issue #1110)",
            settled,
        )
    }

    protected fun TestScope.awaitCardsState(
        vm: TmuxSessionViewModel,
        predicate: (TmuxSessionViewModel.SessionCardsUiState) -> Boolean,
    ) {
        awaitCondition { predicate(vm.sessionCards.value) }
    }

    protected fun checklistFeedJson(
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
    protected fun seedCaptureCommand(paneId: String): String =
        "capture-pane -p -e -S -$SEED_SCROLLBACK_LINES -t $paneId"

    protected fun seedCursorCommand(paneId: String): String =
        "display-message -p -t $paneId '#{cursor_x},#{cursor_y}'"

    @After
    fun tearDown() {
        // Issue #1355: snapshot the VMs BEFORE clearing the list so the
        // structural own-scope drain below (inside `runBlocking`, before the
        // rule's `resetMain()`) still has the handles.
        val vmsToDrain = createdViewModels.toList()
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
            // Issue #1085 (F2) test isolation: JOIN the off-Main teardown
            // coroutines the `@After` clears (and any in-test `clearForTest`)
            // launched onto `defaultTeardownScope` BEFORE the rule's
            // `resetMain()` runs. Join first (the teardown closes are FINITE —
            // they complete after each close returns — so a natural join
            // terminates without needing a cancel), then cancel as a safety
            // net. Without this drain the IO teardown coroutine survives into
            // the next class's `setMain` and throws "Dispatchers.Main is used
            // concurrently with setting it".
            withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
                defaultTeardownScope.coroutineContext.job.children.forEach { it.join() }
            }
            // Issue #1168: drain the AgentConversationRepository tail scope
            // BEFORE the rule's `resetMain()` runs. Unlike the FINITE teardown
            // closes above, the tail drain is an infinite
            // `while (isActive) { delay() }` loop that never self-completes, so
            // it must be CANCELLED FIRST, THEN joined — a bare join would hang.
            // Joining here (Main still installed by the rule) forces every
            // `invokeOnCompletion -> bridgeScope.launch` foreign-IO-thread
            // `Dispatchers.Main` read to happen and finish while Main is stable
            // and no `setMain`/`resetMain` write is in flight, so no tail
            // completion touches Main after `resetMain`.
            agentTailScope.cancel()
            withTimeoutOrNull(SLOW_FEED_DRAIN_TIMEOUT_MS) {
                agentTailScope.coroutineContext.job.children.forEach { it.join() }
            }
        }
        // Issue #1355: the STRUCTURAL fix. The three drains above only cover the
        // INJECTED collaborator scopes (factory/teardown/agent-tail); they never
        // drained the VM's OWN coroutine roots. `clearForTest()` calls
        // `onCleared()` directly, which does NOT cancel `viewModelScope` (only
        // the framework's `ViewModel.clear()` does that), so any
        // `viewModelScope`/`bridgeScope` launch that hopped to a REAL background
        // dispatcher (`withContext(Dispatchers.IO){ dao.getById }`, the
        // `NonCancellable` teardown/detach launches) survives `@After` and
        // re-dispatches its continuation onto `Dispatchers.Main` during the NEXT
        // test's `setMain` — the inter-test leak (TestMainDispatcher:72) the
        // prior four point-fixes (#708/#1085/#1168/#1289) kept chasing one test
        // at a time. Draining each VM's OWN scopes here closes the whole CLASS.
        //
        // These children run on the test Main dispatcher (the shared virtual
        // scheduler), so a `runBlocking { join() }` would deadlock (it never
        // pumps that scheduler — that is why the prior drains work: their scopes
        // are REAL `Dispatchers.IO`). Instead alternate `cancel → runCurrent →
        // Thread.sleep(1)` to a bounded deadline: `runCurrent()` (NEVER
        // `advanceUntilIdle` — advancing the clock trips the #793/#1110 re-seed
        // watchdog) drives both the cancellations of the virtual-clock children
        // AND the Main re-dispatch of any child that hopped to a real dispatcher,
        // while the sleep yields wall-clock time for the real IO thread to
        // finish before the next drain. Re-cancel each pass so a completion
        // handler that re-spawns a `bridgeScope` child (the #1168 hand-back
        // shape) is cancelled too. Runs while the rule's Main is still installed
        // (before its `resetMain()`), so every real-thread Main touch happens now
        // against a stable Main. HARD-FAIL if a VM never quiesces: a future real
        // leak is then a DETERMINISTIC red, not an inter-class flake.
        val issue1355Deadline = System.currentTimeMillis() + SLOW_FEED_DRAIN_TIMEOUT_MS
        while (true) {
            vmsToDrain.forEach { it.cancelOwnScopesForTest() }
            scheduler.runCurrent()
            val active = vmsToDrain.sumOf { it.activeOwnScopeChildCountForTest() }
            if (active == 0) break
            assertTrue(
                "Issue #1355: $active TmuxSessionViewModel coroutine(s) did not quiesce within " +
                    "${SLOW_FEED_DRAIN_TIMEOUT_MS}ms of @After — one would survive into the next " +
                    "test's Dispatchers.setMain and race it (TestMainDispatcher:72, the " +
                    "leak-across-test-boundary flake). Root-cause the new un-drained " +
                    "viewModelScope/bridgeScope launch rather than adding another point-fix.",
                System.currentTimeMillis() < issue1355Deadline,
            )
            Thread.sleep(1)
        }
        defaultTeardownScope.cancel()
    }
}
