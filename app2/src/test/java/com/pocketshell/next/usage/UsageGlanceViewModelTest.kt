package com.pocketshell.next.usage

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.uikit.model.PillKind
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
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith

/**
 * [UsageGlanceViewModel] — the terminal top bar's pill (task P-5). Pins that
 * it starts absent (no fetch has run), and that a real fetch round turns into
 * the single most-constraining [UsageGlancePillState] the pill paints.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class UsageGlanceViewModelTest {

    private var stack: TestUsageStack? = null

    @After
    fun tearDown() {
        stack?.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `before any refresh the pill is absent, not a placeholder`() = vmTest { _ ->
        val viewModel = UsageGlanceViewModel(stack!!.fetcher)

        assertNull(viewModel.state.value)
    }

    @Test
    fun `a near-limit reading surfaces as a Warn pill with the right percent`() = vmTest { stack ->
        val hostId = stack.seedHost("codex-box")
        stack.scriptUsage(CODEX_NEAR_LIMIT_NDJSON)
        stack.connect(hostId)
        val viewModel = UsageGlanceViewModel(stack.fetcher)

        viewModel.refresh()
        runCurrent()

        val pill = viewModel.state.value
        checkNotNull(pill) { "expected a pill state after a connected host's fetch" }
        assertEquals(91, pill.percent)
        assertEquals("Codex", pill.provider)
        assertEquals(PillKind.Warn, pill.kind)
    }

    @Test
    fun `no connected hosts leaves the pill absent`() = vmTest { stack ->
        stack.seedHost()
        val viewModel = UsageGlanceViewModel(stack.fetcher)

        viewModel.refresh()
        runCurrent()

        assertNull(viewModel.state.value)
    }

    private fun vmTest(body: suspend TestScope.(TestUsageStack) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val stack = TestUsageStack()
        this@UsageGlanceViewModelTest.stack = stack
        body(stack)
    }

    private companion object {
        // percent_remaining 9 -> 91% used, above WARN_PERCENT(85) and below
        // CRITICAL_PERCENT(95) -> Approaching -> the pill's Warn kind.
        const val CODEX_NEAR_LIMIT_NDJSON =
            "{\"provider\":\"codex\",\"status\":\"ok\"," +
                "\"windows\":{\"7d\":{\"percent_remaining\":9.0,\"reset_at\":null}}," +
                "\"block_reason\":null,\"error\":null,\"details\":{}}"
    }
}
