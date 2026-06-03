package com.pocketshell.app.portfwd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure visibility / label logic backing the global
 * port-forward indicator (issue #446). The composable + ViewModel are
 * thin wrappers over this state, so the "show only when ≥1 forward is
 * active" contract is asserted here without an emulator.
 */
class ForwardingIndicatorStateTest {

    @Test
    fun hiddenWhenNoActiveHosts() {
        val state = ForwardingIndicatorState(activeHostCount = 0, totalTunnelCount = 0)
        assertFalse(state.visible)
    }

    @Test
    fun visibleWhenAtLeastOneHostActive() {
        val state = ForwardingIndicatorState(activeHostCount = 1, totalTunnelCount = 2)
        assertTrue(state.visible)
    }

    @Test
    fun visibleEvenWhileTunnelsStillSpinningUp() {
        // Host registered (count 1) but tunnel snapshot not yet posted.
        val state = ForwardingIndicatorState(activeHostCount = 1, totalTunnelCount = 0)
        assertTrue(state.visible)
        // Falls back to the host count so the pill never reads "0".
        assertEquals("1", state.label)
    }

    @Test
    fun labelReflectsTunnelCountWhenKnown() {
        assertEquals("3", ForwardingIndicatorState(activeHostCount = 2, totalTunnelCount = 3).label)
        assertEquals("1", ForwardingIndicatorState(activeHostCount = 1, totalTunnelCount = 1).label)
    }

    @Test
    fun contentDescriptionPluralizes() {
        assertEquals(
            "1 port forwarding active",
            ForwardingIndicatorState(activeHostCount = 1, totalTunnelCount = 1).contentDescription,
        )
        assertEquals(
            "4 ports forwarding active",
            ForwardingIndicatorState(activeHostCount = 2, totalTunnelCount = 4).contentDescription,
        )
        assertEquals(
            "Port forwarding active",
            ForwardingIndicatorState(activeHostCount = 1, totalTunnelCount = 0).contentDescription,
        )
    }

    @Test
    fun defaultStateIsHidden() {
        assertFalse(ForwardingIndicatorState().visible)
    }
}
