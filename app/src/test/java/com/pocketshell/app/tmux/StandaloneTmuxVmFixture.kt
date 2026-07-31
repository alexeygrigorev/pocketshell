package com.pocketshell.app.tmux

import com.pocketshell.app.hosts.MainDispatcherTestIsolation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Issue #1765 — the shared teardown seam for the *standalone* JVM fixtures that
 * construct [TmuxSessionViewModel] directly inside a `runTest` body and then
 * reset the process-global `Dispatchers.Main` themselves (i.e. every VM fixture
 * that does NOT use [com.pocketshell.app.hosts.MainDispatcherRule]).
 *
 * ## The bug this exists to stop
 *
 * The #1355 recurrence was a standalone fixture of exactly this shape:
 *
 * ```
 * runTest {
 *     Dispatchers.setMain(StandardTestDispatcher(testScheduler))
 *     try { body() } finally {
 *         createdVms.forEach { it.clearForTest() }   // does NOT cancel viewModelScope
 *         advanceUntilIdle()                         // does NOT wait for real-IO children
 *         Dispatchers.resetMain()                    // <-- races the still-live children
 *     }
 * }
 * ```
 *
 * `clearForTest()` calls `onCleared()` DIRECTLY, so — unlike the framework's
 * `ViewModel.clear()` — it never cancels `viewModelScope`/`bridgeScope`. Any VM
 * launch that hopped to a REAL background dispatcher therefore survives the
 * body, and its continuation re-dispatches onto `Dispatchers.Main` after the
 * fixture already reset the global delegate — the `TestMainDispatcher:72` race,
 * which surfaces as a flake in a completely unrelated test class.
 * `factoryScope.cancel()` in `@After` has the same hole: `cancel()` only
 * *requests* cancellation and returns before a real-IO child has unwound.
 *
 * ## What this fixture guarantees
 *
 * While Main is still installed, and only then resetting it:
 *
 * 1. every registered body cleanup runs (LIFO — e.g. a diagnostic sink close);
 * 2. every tracked VM is cleared in reverse construction order;
 * 3. every VM-owned coroutine root is cancelled;
 * 4. every fixture-owned real-IO root ([factoryScope], [leaseScope], anything
 *    passed to [trackRoot]) is cancelled **and joined**;
 * 5. a generously bounded, `runCurrent`-only drain HARD-FAILS unless the active
 *    VM-owned child count reaches zero; and
 * 6. `Dispatchers.resetMain()` runs — guarded by a phase oracle so moving it
 *    ahead of the drain is a deterministic RED rather than an intermittent
 *    cross-class race.
 *
 * The drain itself is [TmuxSessionViewModelRuleTeardown], shared verbatim with
 * the [com.pocketshell.app.hosts.MainDispatcherRule] consumers — this class only
 * supplies the scheduler binding and the Main install/reset ordering.
 *
 * ## Invariants callers rely on
 *
 * - The teardown NEVER advances virtual time (no `advanceUntilIdle`, no
 *   `advanceTimeBy`): it uses `runCurrent()` only, so a body's watchdog
 *   deadlines and timing oracles cannot be perturbed by cleanup.
 * - The teardown runs after the `runTest` body has completed but still INSIDE
 *   the `runTest` block, so it cannot interleave with body assertions and it
 *   still finishes before `runTest`'s own `advanceUntilIdleOr` cleanup (see
 *   [runVmTest]).
 * - A body failure stays the PRIMARY failure; later teardown failures are
 *   attached as suppressed exceptions.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StandaloneTmuxVmFixture(
    timeoutMs: Long = DEFAULT_TIMEOUT_MS,
) {

    /**
     * The single virtual clock shared by the installed Main dispatcher, the
     * `runTest` body, and the teardown drain.
     */
    val scheduler: TestCoroutineScheduler = TestCoroutineScheduler()

    private val teardown = TmuxSessionViewModelRuleTeardown(
        runCurrent = scheduler::runCurrent,
        timeoutMs = timeoutMs,
    )

    private val bodyCleanups = mutableListOf<() -> Unit>()
    private val oracle = MainResetPhaseOracle()
    private var started = false
    private var trackedVmCount = 0

    /**
     * Fixture-owned REAL-IO root for `TmuxClientFactory`. Replaces the
     * per-fixture `private val factoryScope = CoroutineScope(SupervisorJob() +
     * Dispatchers.IO)` + `@After { factoryScope.cancel() }` pair, which
     * cancelled without joining.
     *
     * Deliberately a real `Dispatchers.IO` scope, not a test dispatcher: the
     * production `TmuxClientFactory` runs its blocking control-channel IO off
     * the caller's thread, and pinning it to the virtual clock would change the
     * behaviour under test. The join in the teardown is what makes the real
     * dispatcher safe here.
     */
    val factoryScope: CoroutineScope = trackRoot(
        name = "factoryScope",
        scope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    /**
     * Fixture-owned REAL-IO root for an `SshLeaseManager` built WITHOUT an
     * explicit `scope =` argument. `SshLeaseManager`'s default scope is a fresh
     * unowned `CoroutineScope(SupervisorJob() + Dispatchers.IO)` that nothing
     * ever cancels — a second leak vector alongside the VM roots. Pass this
     * instead.
     *
     * Lease managers built through `TestScope.testLeaseManager(...)` default to
     * `scope = this` (the `TestScope`), which `runTest` already owns; those do
     * not need this root.
     */
    val leaseScope: CoroutineScope by lazy {
        trackRoot(name = "leaseScope", scope = CoroutineScope(SupervisorJob() + Dispatchers.IO))
    }

    /**
     * Registers a VM for teardown. EVERY directly constructed
     * [TmuxSessionViewModel] in a fixture must be tracked — an untracked VM is
     * exactly the #1355 leak, and the fixture cannot see it.
     */
    fun track(vm: TmuxSessionViewModel): TmuxSessionViewModel {
        trackedVmCount++
        teardown.track(vm)
        return vm
    }

    /**
     * Adapter for the teardown ordering invariant itself — the fixture's own
     * tests register observable stand-ins here. Production-shaped callers use
     * [track]; both paths share the exact same clear/cancel/drain implementation.
     */
    internal fun trackForTest(
        clear: () -> Unit,
        cancelOwnScopes: () -> Unit,
        activeOwnScopeChildCount: () -> Int,
    ) {
        trackedVmCount++
        teardown.track(
            clear = clear,
            cancelOwnScopes = cancelOwnScopes,
            activeOwnScopeChildCount = activeOwnScopeChildCount,
        )
    }

    /** Registers an extra fixture-owned coroutine root to cancel AND join. */
    fun trackRoot(name: String, scope: CoroutineScope): CoroutineScope =
        teardown.trackRoot(name, scope)

    /**
     * Registers body-scoped cleanup (e.g. `sink.close()`) that must run BEFORE
     * the VMs are cleared and while Main is still installed. Callbacks run in
     * reverse registration order.
     */
    fun onTeardown(block: () -> Unit) {
        bodyCleanups += block
    }

    /** Number of VMs registered through [track] — used by the fixture's own tests. */
    internal fun trackedVmCountForTest(): Int = trackedVmCount

    /** The phase oracle this fixture drives — asserted directly by its own tests. */
    internal fun phaseOracleForTest(): MainResetPhaseOracle = oracle

    /**
     * Runs [body] as a `runTest` on this fixture's scheduler with
     * `Dispatchers.Main` installed, then performs the ordered teardown above —
     * INSIDE the `runTest` body, exactly where the 28 hand-rolled `finally`
     * blocks it replaces ran.
     *
     * That placement is load-bearing, not cosmetic. `runTest`'s OWN cleanup
     * (`TestBuilders.kt:370`) calls `testScheduler.advanceUntilIdleOr { ... }`
     * after the body returns, and it drains work from EVERY coroutine on the
     * scheduler — including foreign, VM-owned ones. A VM whose self-rearming
     * loop is still armed at that point (the #1543 liveness probe with
     * auto-start explicitly enabled) makes that cleanup spin forever: a silent
     * 35-minute CI hang, not an assertion failure. Draining after `runTest`
     * returns reproduces exactly that hang, so the drain must finish first.
     *
     * @param disableLivenessProbeAutoStart mirrors the per-fixture "EPIC #792
     * Slice D" opt-out. Left ON only by the fixtures that deliberately exercise
     * the auto-start default itself (#1543).
     */
    fun runVmTest(
        disableLivenessProbeAutoStart: Boolean = true,
        body: suspend TestScope.() -> Unit,
    ) {
        MainDispatcherTestIsolation.withOwnership {
            runVmTestWithMainOwnership(disableLivenessProbeAutoStart, body)
        }
    }

    private fun runVmTestWithMainOwnership(
        disableLivenessProbeAutoStart: Boolean,
        body: suspend TestScope.() -> Unit,
    ) {
        check(!started) {
            "Issue #1765: a StandaloneTmuxVmFixture drives exactly one test method; " +
                "construct it as a per-test field, not a shared object"
        }
        started = true
        Dispatchers.setMain(StandardTestDispatcher(scheduler))
        if (disableLivenessProbeAutoStart) {
            LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
        }

        runTest(scheduler, timeout = TEST_TIMEOUT) {
            var failure: Throwable? = null
            fun capture(block: () -> Unit) {
                try {
                    block()
                } catch (thrown: Throwable) {
                    val first = failure
                    if (first == null) {
                        failure = thrown
                    } else if (first !== thrown) {
                        first.addSuppressed(thrown)
                    }
                }
            }

            // The body failure is recorded the same way as teardown failures so
            // it stays PRIMARY: every later cleanup failure is attached as
            // suppressed instead of masking what the test actually proved.
            try {
                body()
            } catch (thrown: Throwable) {
                failure = thrown
            }

            bodyCleanups.asReversed().forEach { cleanup -> capture(cleanup) }

            capture {
                oracle.beginDrain()
                try {
                    teardown.drain()
                } finally {
                    // "Complete" means the bounded drain phase returned or
                    // hard-failed. drain()'s own zero-child assertion stays the
                    // behavioural invariant; this transition only lets
                    // exception-safe cleanup reset the global Main after
                    // recording that failure, rather than stranding Main
                    // installed for every following test class.
                    oracle.drainComplete()
                }
            }

            capture { LivenessProbeTestOverride.clear() }

            // Phase oracle: resetting Main before the drain is the #1355 defect.
            // Reordering this call ahead of the drain is a deterministic RED, and
            // the oracle detects it WITHOUT having to dispatch anything onto an
            // already-reset Main dispatcher.
            capture { oracle.resetMain(Dispatchers::resetMain) }

            failure?.let { throw it }
        }
    }

    private companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L

        /**
         * `runTest`'s own wall-clock bound, raised from its 60s default only so
         * that a hard-failing teardown drain (up to three bounded
         * [DEFAULT_TIMEOUT_MS] phases: factory root, lease root, VM children)
         * reports ITS diagnostic instead of being masked by an
         * `UncompletedCoroutinesError`. A wedged body is still bounded.
         */
        val TEST_TIMEOUT: Duration = 150.seconds
    }
}

/**
 * Issue #1765 ordering oracle for "reset `Dispatchers.Main` ONLY after the VM
 * drain".
 *
 * It is a separate, directly exercisable object rather than a couple of inline
 * `check(...)` calls precisely so the RED for a premature reset is deterministic
 * and cheap: a test (and a mutation) can drive [resetMain] without a completed
 * drain and observe the hard failure, WITHOUT having to dispatch a coroutine
 * onto an already-reset Main dispatcher and hope the race reproduces. The old
 * failure mode was an intermittent `TestMainDispatcher.kt:72` crash in an
 * unrelated later test class; that is not something a test can wait for.
 */
internal class MainResetPhaseOracle {

    enum class Phase {
        MAIN_INSTALLED,
        DRAIN_COMPLETE,
        MAIN_RESET_STARTED,
        MAIN_RESET_COMPLETE,
    }

    var phase: Phase = Phase.MAIN_INSTALLED
        private set

    /** Hard-fails unless the drain is about to run with Main still installed. */
    fun beginDrain() {
        check(phase == Phase.MAIN_INSTALLED) {
            "Issue #1765: the TmuxSessionViewModel drain must run while " +
                "Dispatchers.Main is still installed (phase=$phase)"
        }
    }

    /**
     * Records that the bounded drain phase returned OR hard-failed. The drain's
     * own zero-child assertion stays the behavioural invariant; this transition
     * only lets exception-safe cleanup still reset the global Main after
     * recording that failure, instead of stranding Main for the next class.
     */
    fun drainComplete() {
        if (phase == Phase.MAIN_INSTALLED) {
            phase = Phase.DRAIN_COMPLETE
        }
    }

    /** Hard-fails unless [drainComplete] has run, then performs [reset]. */
    fun resetMain(reset: () -> Unit) {
        check(phase == Phase.DRAIN_COMPLETE) {
            "Issue #1765: Dispatchers.resetMain() cannot start before the " +
                "TmuxSessionViewModel drain completes (phase=$phase); resetting Main " +
                "first recreates the TestMainDispatcher:72 race in an unrelated test class"
        }
        phase = Phase.MAIN_RESET_STARTED
        try {
            reset()
        } finally {
            phase = Phase.MAIN_RESET_COMPLETE
        }
    }
}
