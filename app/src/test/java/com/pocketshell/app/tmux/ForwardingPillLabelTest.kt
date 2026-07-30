package com.pocketshell.app.tmux

import com.pocketshell.app.portfwd.SessionForwardingIndicatorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardingPillLabelTest {
    @Test
    fun pillIsActiveOnly() {
        assertFalse(SessionForwardingIndicatorState().visible)
        assertTrue(SessionForwardingIndicatorState(active = true, tunnelCount = 1).visible)
    }

    @Test
    fun singleSettledTunnelNamesItsRemotePort() {
        assertEquals(
            ":8080",
            forwardingPillLabel(
                SessionForwardingIndicatorState(
                    active = true,
                    tunnelCount = 1,
                    activeRemotePorts = setOf(8080),
                ),
            ),
        )
    }

    @Test
    fun multipleTunnelsCommunicateTheCount() {
        assertEquals(
            "3 ports",
            forwardingPillLabel(
                SessionForwardingIndicatorState(
                    active = true,
                    tunnelCount = 3,
                    activeRemotePorts = setOf(2222, 8080, 9090),
                ),
            ),
        )
        assertEquals(
            "28p",
            forwardingPillLabel(
                SessionForwardingIndicatorState(active = true, tunnelCount = 28),
            ),
        )
    }

    @Test
    fun restoringAndStartingNeverReadZero() {
        assertEquals(
            "…",
            forwardingPillLabel(
                SessionForwardingIndicatorState(active = true, restoring = true),
            ),
        )
        assertEquals(
            "…",
            forwardingPillLabel(SessionForwardingIndicatorState(active = true)),
        )
    }
}
