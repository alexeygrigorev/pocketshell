package com.pocketshell.next.ports

import androidx.lifecycle.SavedStateHandle
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.portfwd.TunnelInfo
import com.pocketshell.next.nav.Destination
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
 * [PortForwardViewModel] over the real [ForwardingController], the real
 * `core-portfwd` engine and a real in-memory Room database — only the sshj dial
 * is faked (see [TestForwardingStack]).
 *
 * The ViewModel is a projection with no forwarding state of its own, so the
 * assertions that matter are exactly the seams: does the toggle reach Room AND
 * the engine, do the rows come back filtered, and does the checkbox re-filter
 * without waiting for a new snapshot.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class PortForwardViewModelTest {

    private var stack: TestForwardingStack? = null

    @After
    fun tearDown() {
        stack?.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial load paints the host identity and the persisted checkbox`() = vmTest { stack ->
        val hostId = stack.seedHost(name = "rmthz")
        stack.showAllPortsStore.setShowAll(true)

        val state = viewModel(stack, hostId).state.value

        assertEquals("rmthz", state.hostName)
        assertEquals("testuser@10.0.2.2:2222", state.hostSubtitle)
        assertFalse(state.enabled)
        assertTrue("the persisted checkbox must survive re-entry", state.showAllPorts)
        assertFalse(state.loading)
    }

    @Test
    fun `a host already enabled in Room opens with the toggle on`() = vmTest { stack ->
        val hostId = stack.seedHost(enabled = true)

        assertTrue(viewModel(stack, hostId).state.value.enabled)
    }

    @Test
    fun `turning the toggle on records the intent and opens forwards`() = vmTest { stack ->
        stack.listenOn(7_431 to "vite")
        val hostId = stack.seedHost()
        val viewModel = viewModel(stack, hostId)

        viewModel.setEnabled(true)
        runCurrent()

        assertTrue("hosts.enabled must be written", stack.isEnabled(hostId))
        assertTrue(stack.controller.isRunning(hostId))
        val state = viewModel.state.value
        assertTrue(state.enabled)
        assertEquals(listOf(7_431), state.rows.map { it.remotePort })
        assertEquals(TunnelInfo.Status.FORWARDING, state.rows.single().status)
    }

    @Test
    fun `turning the toggle off clears the intent and empties the rows`() = vmTest { stack ->
        stack.listenOn(7_431 to "vite")
        val hostId = stack.seedHost()
        val viewModel = viewModel(stack, hostId)
        viewModel.setEnabled(true)
        runCurrent()
        assertTrue(viewModel.state.value.rows.isNotEmpty())

        viewModel.setEnabled(false)
        runCurrent()

        assertFalse(stack.isEnabled(hostId))
        assertFalse(viewModel.state.value.enabled)
        assertEquals(emptyList<TunnelInfo>(), viewModel.state.value.rows)
    }

    @Test
    fun `rows arrive filtered and the hidden count is reported`() = vmTest { stack ->
        // 22 is below the host's skipPortsBelow AND outside the default visible
        // band, so it is discovered but hidden.
        stack.listenOn(7_431 to "vite", 22 to "sshd")
        val hostId = stack.seedHost()
        val viewModel = viewModel(stack, hostId)

        viewModel.setEnabled(true)
        runCurrent()

        val state = viewModel.state.value
        assertEquals(listOf(7_431), state.rows.map { it.remotePort })
        assertEquals(1, state.hiddenCount)
    }

    @Test
    fun `the show-all checkbox re-filters immediately and persists`() = vmTest { stack ->
        stack.listenOn(7_431 to "vite", 22 to "sshd")
        val hostId = stack.seedHost()
        val viewModel = viewModel(stack, hostId)
        viewModel.setEnabled(true)
        runCurrent()
        assertEquals(listOf(7_431), viewModel.state.value.rows.map { it.remotePort })

        viewModel.setShowAllPorts(true)

        // Re-filtered from the CACHED snapshot — no new controller emission is
        // needed, so the table does not sit stale until the next scan tick.
        assertEquals(listOf(7_431, 22), viewModel.state.value.rows.map { it.remotePort })
        runCurrent()
        assertTrue("the choice is global and persisted", stack.showAllPortsStore.isShowAll())
    }

    @Test
    fun `togglePort opts an out-of-window port in through the controller`() = vmTest { stack ->
        stack.listenOn(22 to "sshd")
        val hostId = stack.seedHost()
        val viewModel = viewModel(stack, hostId)
        viewModel.setEnabled(true)
        viewModel.setShowAllPorts(true)
        runCurrent()
        assertEquals(TunnelInfo.Status.AVAILABLE, viewModel.state.value.rows.single().status)

        viewModel.togglePort(22)
        runCurrent()

        assertEquals(TunnelInfo.Status.FORWARDING, viewModel.state.value.rows.single().status)
    }

    @Test
    fun `a second screen for the same host sees the forwards the first one opened`() = vmTest { stack ->
        // A forward outlives the ViewModel that started it — that is the whole
        // reason the state lives in the controller.
        stack.listenOn(7_431 to "vite")
        val hostId = stack.seedHost()
        viewModel(stack, hostId).setEnabled(true)
        runCurrent()

        val reopened = viewModel(stack, hostId)
        runCurrent()

        assertTrue(reopened.state.value.enabled)
        assertEquals(listOf(7_431), reopened.state.value.rows.map { it.remotePort })
    }

    /**
     * Builds the ViewModel and lets its `init` run: the host row and the persisted
     * checkbox are read on `viewModelScope`, which is Main, which is this test's
     * scheduler.
     */
    private fun TestScope.viewModel(
        stack: TestForwardingStack,
        hostId: Long,
    ): PortForwardViewModel = PortForwardViewModel(
        savedStateHandle = SavedStateHandle(mapOf(Destination.ARG_HOST_ID to hostId)),
        hostDao = stack.db.hostDao(),
        controller = stack.controller,
        showAllPortsStore = stack.showAllPortsStore,
    ).also { runCurrent() }

    /**
     * A `runTest` body with a live forwarding stack and Main pointed at the test
     * scheduler, torn down before the test ends.
     *
     * The teardown is required, not hygiene: the forwarder's scan loop is
     * intentionally unbounded, and `runTest` runs `advanceUntilIdle` after the
     * body — which would advance virtual time through `delay(scanIntervalSec)`
     * forever. [ForwardingController.stopAll] is the production teardown and
     * cancels every supervisor, so the scheduler really does go idle.
     */
    private fun vmTest(body: suspend TestScope.(TestForwardingStack) -> Unit) = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val stack = TestForwardingStack(dispatcher)
        this@PortForwardViewModelTest.stack = stack
        try {
            body(stack)
        } finally {
            stack.controller.stopAll()
            runCurrent()
        }
    }
}
