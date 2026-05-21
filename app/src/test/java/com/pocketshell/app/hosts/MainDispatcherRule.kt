package com.pocketshell.app.hosts

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
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
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
