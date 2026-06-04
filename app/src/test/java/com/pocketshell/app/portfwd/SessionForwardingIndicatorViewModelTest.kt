package com.pocketshell.app.portfwd

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [SessionForwardingIndicatorViewModel] (issue #487): the
 * per-host projection over the production [ForwardingController]'s snapshot
 * map. We drive the controller directly and assert the VM's per-host state
 * reflects only the host whose session is on screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class SessionForwardingIndicatorViewModelTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun `hidden for a host with no active forwarding`() = runTest {
        val controller = ForwardingController(context)
        val vm = SessionForwardingIndicatorViewModel(controller)

        // Register a DIFFERENT host so the snapshot map is non-empty.
        controller.registerActiveHost(hostId = 1L, hostName = "alpha")
        controller.updateTunnelCount(1L, 2)

        val state = vm.stateFor(hostId = 99L).first()
        assertFalse("host 99 has no forwarding -> chip hidden", state.visible)
    }

    @Test
    fun `visible for the host that is actively forwarding`() = runTest {
        val controller = ForwardingController(context)
        val vm = SessionForwardingIndicatorViewModel(controller)

        controller.registerActiveHost(hostId = 7L, hostName = "beta")
        controller.updateTunnelCount(7L, 3)

        val state = vm.stateFor(hostId = 7L).first { it.visible }
        assertTrue(state.visible)
        assertEquals(3, state.tunnelCount)
        assertEquals("3 ports forwarding active for this host", state.contentDescription)
    }

    @Test
    fun `clears when the host unregisters`() = runTest {
        val controller = ForwardingController(context)
        val vm = SessionForwardingIndicatorViewModel(controller)

        controller.registerActiveHost(hostId = 5L, hostName = "gamma")
        controller.updateTunnelCount(5L, 1)
        // Sanity: visible while active.
        assertTrue(vm.stateFor(hostId = 5L).first { it.visible }.visible)

        controller.unregisterActiveHost(hostId = 5L)
        val cleared = vm.stateFor(hostId = 5L).first { !it.visible }
        assertFalse("chip clears once the host stops forwarding", cleared.visible)
    }

    @Test
    fun `reflects restoring state for the host`() = runTest {
        val controller = ForwardingController(context)
        val vm = SessionForwardingIndicatorViewModel(controller)

        controller.registerActiveHost(hostId = 3L, hostName = "delta")
        controller.updateTunnelCount(3L, 2)
        controller.setHostRestoring(3L, true)

        val state = vm.stateFor(hostId = 3L).first { it.restoring }
        assertTrue(state.visible)
        assertTrue(state.restoring)
        assertEquals("Port forwarding restoring for this host", state.contentDescription)
    }

    // --- Issue #488: forwarded-vs-not decision + local port lookup ----------

    @Test
    fun `forwardedLocalPortFor returns local port when the remote port is forwarded`() {
        val controller = ForwardingController(context)
        val vm = SessionForwardingIndicatorViewModel(controller)

        controller.registerActiveHost(hostId = 7L, hostName = "beta")
        // server:3000 -> phone:3000 (straight), server:8080 -> phone:18080 (remap).
        controller.updateActiveTunnels(7L, mapOf(3000 to 3000, 8080 to 18080))

        assertEquals(3000, vm.forwardedLocalPortFor(hostId = 7L, remotePort = 3000))
        assertEquals(18080, vm.forwardedLocalPortFor(hostId = 7L, remotePort = 8080))
    }

    @Test
    fun `forwardedLocalPortFor returns null when the remote port is not forwarded`() {
        val controller = ForwardingController(context)
        val vm = SessionForwardingIndicatorViewModel(controller)

        controller.registerActiveHost(hostId = 7L, hostName = "beta")
        controller.updateActiveTunnels(7L, mapOf(3000 to 3000))

        // A port that isn't in the active map -> not forwarded.
        assertNull(vm.forwardedLocalPortFor(hostId = 7L, remotePort = 9999))
        // A host that isn't forwarding at all -> not forwarded.
        assertNull(vm.forwardedLocalPortFor(hostId = 42L, remotePort = 3000))
    }
}
