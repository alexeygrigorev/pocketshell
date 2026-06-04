package com.pocketshell.app.portfwd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the pure per-host visibility / label / talkback logic
 * backing the in-session forwarding chip (issue #487). The composable +
 * ViewModel are thin wrappers over this state, so the "show only while THIS
 * host is forwarding" contract is asserted here without an emulator.
 */
class SessionForwardingIndicatorStateTest {

    @Test
    fun hiddenByDefault() {
        assertFalse(SessionForwardingIndicatorState().visible)
    }

    @Test
    fun hiddenWhenHostNotActive() {
        val state = SessionForwardingIndicatorState(active = false, tunnelCount = 0)
        assertFalse(state.visible)
    }

    @Test
    fun visibleWhenHostActive() {
        val state = SessionForwardingIndicatorState(active = true, tunnelCount = 2)
        assertTrue(state.visible)
        assertEquals("2", state.label)
        assertEquals("2 ports forwarding active for this host", state.contentDescription)
    }

    @Test
    fun visibleWhileTunnelsStillSpinningUp() {
        // Host registered (active) but tunnel count not yet posted.
        val state = SessionForwardingIndicatorState(active = true, tunnelCount = 0)
        assertTrue(state.visible)
        // Label stays blank rather than reading "0"; the chip text covers it.
        assertEquals("", state.label)
        assertEquals("Port forwarding active for this host", state.contentDescription)
    }

    @Test
    fun singleTunnelContentDescriptionIsSingular() {
        val state = SessionForwardingIndicatorState(active = true, tunnelCount = 1)
        assertEquals("1", state.label)
        assertEquals("1 port forwarding active for this host", state.contentDescription)
    }

    @Test
    fun restoringReadsAsRestoringNotRemoved() {
        val state = SessionForwardingIndicatorState(
            active = true,
            tunnelCount = 0,
            restoring = true,
        )
        assertTrue(state.visible)
        assertEquals(
            "Port forwarding restoring for this host",
            state.contentDescription,
        )
    }
}
