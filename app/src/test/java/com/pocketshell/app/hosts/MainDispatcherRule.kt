package com.pocketshell.app.hosts

import android.os.Looper
import com.pocketshell.app.tmux.LivenessProbeTestOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestRule
import org.junit.runners.model.Statement
import org.junit.runner.Description

/**
 * JUnit rule that swaps the kotlinx-coroutines `Dispatchers.Main` for a
 * test dispatcher for the duration of each test.
 *
 * `viewModelScope` uses `Dispatchers.Main.immediate` by default — without
 * this rule the scope's launches would post to the real Android main
 * looper, which Robolectric's `RobolectricTestRunner` does not pump for
 * non-instrumentation tests by default, leading to `runTest` timeouts.
 *
 * `UnconfinedTestDispatcher` runs the scheduled work synchronously the
 * first time the test scope advances, which is what most of our
 * assertions expect ("the ViewModel state has settled by the time we
 * check it").
 *
 * `Dispatchers.Main` is a process-global singleton. Gradle may run
 * multiple unit-test classes in the same JVM at once, and kotlinx-coroutines
 * guards against reading `Main` while another thread is swapping it. Hold a
 * shared lock across the whole test statement so every test using this rule
 * sees a stable Main dispatcher until its `@After` cleanup has finished.
 *
 * Teardown must never hand Main back in a state where dispatching throws
 * (issue #2413). Under Robolectric a real main looper exists, so it calls plain
 * `Dispatchers.resetMain()`. In a looper-less JVM unit test resetting leaves
 * Main *missing*, so a coroutine that escaped this test and later resumes on
 * Main throws on its own worker thread; that throw is an uncaught coroutine
 * exception, which `kotlinx-coroutines-test` replays against an arbitrary
 * innocent sibling `runTest` as `UncaughtExceptionsBeforeTest`. There it
 * installs a [PostTestMainDispatcher] instead, which records the late dispatch
 * against the test that leaked it, so the leak fails loudly with attribution
 * instead of reddening a random unrelated class. See
 * [MainDispatcherStragglers].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    // Give every default rule its own scheduler explicitly. The implicit
    // constructor adopts an already-installed test Main scheduler, which can
    // bind a newly constructed test class to another class's clock in the
    // long-lived full-suite worker (#1892).
    internal val dispatcher: TestDispatcher =
        UnconfinedTestDispatcher(TestCoroutineScheduler()),
) : TestRule {
    private val beforeResetMain = mutableListOf<() -> Unit>()

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                MainDispatcherTestIsolation.withOwnership {
                    // Issue #2413: a coroutine that escaped an EARLIER test onto
                    // Main is that test's bug. Report it here, attributed to its
                    // owner, before this test can be blamed for it.
                    MainDispatcherStragglers.failIfAnyRecorded(
                        "entry of ${description.displayName}",
                    )
                    Dispatchers.setMain(dispatcher)
                    // EPIC #792 Slice D: the LivenessProbe is an infinite periodic
                    // `delay` loop. Under `runTest` + the virtual-clock Main set above,
                    // an auto-started probe would make `advanceUntilIdle()` spin forever
                    // (the loop self-reschedules, so the scheduler never idles), hanging
                    // every VM unit test. Disable the auto-start for the rule's duration;
                    // tests drive the probe via the explicit VM seams instead.
                    LivenessProbeTestOverride.setAutoStartEnabledForTest(false)
                    try {
                        base.evaluate()
                    } finally {
                        LivenessProbeTestOverride.clear()
                        var cleanupFailure: Throwable? = null
                        beforeResetMain.asReversed().forEach { teardown ->
                            try {
                                teardown()
                            } catch (failure: Throwable) {
                                val firstFailure = cleanupFailure
                                if (firstFailure == null) {
                                    cleanupFailure = failure
                                } else if (firstFailure !== failure) {
                                    firstFailure.addSuppressed(failure)
                                }
                            }
                        }
                        beforeResetMain.clear()
                        try {
                            // Issue #2413: never hand Main back in a state where
                            // dispatching THROWS. Under Robolectric there is a
                            // real main looper, so `resetMain()` returns a
                            // working platform dispatcher and nothing changes.
                            // In a looper-less JVM unit test it returns the
                            // MISSING dispatcher, and a coroutine that escaped
                            // this test then throws on its own worker thread —
                            // an uncaught coroutine exception that
                            // kotlinx-coroutines-test replays against an
                            // arbitrary innocent sibling `runTest` as
                            // `UncaughtExceptionsBeforeTest`. See
                            // [MainDispatcherStragglers].
                            if (Looper.getMainLooper() != null) {
                                Dispatchers.resetMain()
                            } else {
                                Dispatchers.setMain(
                                    PostTestMainDispatcher(description.displayName),
                                )
                            }
                        } catch (failure: Throwable) {
                            val firstFailure = cleanupFailure
                            if (firstFailure == null) {
                                cleanupFailure = failure
                            } else if (firstFailure !== failure) {
                                firstFailure.addSuppressed(failure)
                            }
                        }
                        if (cleanupFailure == null) {
                            // Only when the test itself is otherwise clean: a
                            // straggler report must never mask the real failure.
                            try {
                                MainDispatcherStragglers.failIfAnyRecorded(
                                    "exit of ${description.displayName}",
                                )
                            } catch (failure: Throwable) {
                                cleanupFailure = failure
                            }
                        }
                        cleanupFailure?.let { throw it }
                    }
                }
            }
        }

    /**
     * Registers test-owned cleanup that must finish while the rule's process-global
     * Main dispatcher is still installed.
     *
     * This is deliberately a test-only lifecycle seam rather than a generic JUnit
     * `@After`: the rule owns the ordering boundary, so a consumer cannot
     * accidentally return from `@After` and let [Dispatchers.resetMain] race a
     * still-unwinding coroutine.
     */
    internal fun beforeResetMain(teardown: () -> Unit) {
        beforeResetMain += teardown
    }

    /** Runs only work already due on this rule's virtual clock; never advances time. */
    internal fun runCurrent() {
        dispatcher.scheduler.runCurrent()
    }

}
