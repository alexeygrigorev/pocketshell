package com.pocketshell.app.hosts

import com.pocketshell.app.tmux.LivenessProbeTestOverride
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                synchronized(mainDispatcherLock) {
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
                        Dispatchers.resetMain()
                    }
                }
            }
        }

    private companion object {
        private val mainDispatcherLock = Any()
    }
}
