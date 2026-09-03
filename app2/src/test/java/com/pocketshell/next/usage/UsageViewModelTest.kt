package com.pocketshell.next.usage

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [UsageViewModel] over the real [UsageFetcher] and [TestUsageStack] — only
 * the sshj dial is faked. Pins the mapping from a fetch round to
 * [UsageScreenState]: the no-host empty state, the connected-but-empty state,
 * and a real provider record landing in [UsageScreenState.hosts].
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class UsageViewModelTest {

    private var stack: TestUsageStack? = null

    @After
    fun tearDown() {
        stack?.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `before any refresh nothing is loaded`() = vmTest { _ ->
        val viewModel = UsageViewModel(stack!!.fetcher)

        assertFalse(viewModel.state.value.loaded)
    }

    @Test
    fun `no connected hosts refreshes into the no-connected-hosts state`() = vmTest { stack ->
        stack.seedHost()
        val viewModel = UsageViewModel(stack.fetcher)

        viewModel.refresh()
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.loaded)
        assertFalse(state.isRefreshing)
        assertTrue(state.isEmptyWithNoConnectedHosts)
        assertEquals(0, state.connectedHostCount)
    }

    @Test
    fun `a connected host's provider record lands in the panel state`() = vmTest { stack ->
        val hostId = stack.seedHost("claude-box")
        stack.scriptUsage(CLAUDE_NDJSON)
        stack.connect(hostId)
        val viewModel = UsageViewModel(stack.fetcher)

        viewModel.refresh()
        runCurrent()

        val state = viewModel.state.value
        assertTrue(state.loaded)
        assertEquals(1, state.hostCount)
        assertEquals(1, state.providerCount)
        assertEquals("claude", state.allRecords.single().provider)
    }

    @Test
    fun `a refresh already in flight is not re-entered`() = vmTest { stack ->
        val hostId = stack.seedHost()
        stack.scriptUsage(CLAUDE_NDJSON)
        stack.connect(hostId)
        val viewModel = UsageViewModel(stack.fetcher)

        viewModel.refresh()
        // Fires while the first refresh's fetch is still suspended on the
        // virtual scheduler — must be a no-op, not a second concurrent fetch.
        viewModel.refresh()
        runCurrent()

        assertEquals(1, viewModel.state.value.hostCount)
    }

    private fun vmTest(body: suspend TestScope.(TestUsageStack) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val stack = TestUsageStack()
        this@UsageViewModelTest.stack = stack
        body(stack)
    }

    private companion object {
        const val CLAUDE_NDJSON =
            "{\"provider\":\"claude\",\"status\":\"ok\"," +
                "\"windows\":{\"5h\":{\"percent_remaining\":80.0,\"reset_at\":null}}," +
                "\"block_reason\":null,\"error\":null,\"details\":{}}"
    }
}
