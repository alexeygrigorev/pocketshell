package com.pocketshell.app.tmux

import com.pocketshell.app.portfwd.SessionForwardingIndicatorState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1487: the always-visible in-session port-forwarding pill. Covers the
 * pure render-gate + label logic behind the top-chrome pill (a test per
 * acceptance criterion — G9):
 *
 *  - the pill is shown ONLY while forwarding is active (`visible`), so an
 *    inactive host leaves no empty pill / no gap;
 *  - it communicates single-vs-multiple at a glance (`1 port` / `N ports`);
 *  - it NEVER reads "0 ports" while spinning up or restoring (`…`).
 */
class ForwardingPillLabelTest {

    @Test
    fun pillHiddenWhenForwardingInactive() {
        // Default (no forwarding) and explicit inactive both hide the pill.
        assertFalse(SessionForwardingIndicatorState().visible)
        assertFalse(
            SessionForwardingIndicatorState(active = false, tunnelCount = 2).visible,
        )
    }

    @Test
    fun pillVisibleWhenForwardingActive() {
        assertTrue(
            SessionForwardingIndicatorState(active = true, tunnelCount = 1).visible,
        )
    }

    @Test
    fun labelForSingleTunnelReadsSingular() {
        assertEquals(
            "1 port",
            forwardingPillLabel(
                SessionForwardingIndicatorState(active = true, tunnelCount = 1),
            ),
        )
    }

    @Test
    fun labelForMultipleTunnelsReadsPlural() {
        assertEquals(
            "3 ports",
            forwardingPillLabel(
                SessionForwardingIndicatorState(active = true, tunnelCount = 3),
            ),
        )
    }

    @Test
    fun labelWhileRestoringReadsEllipsisNotZero() {
        assertEquals(
            "…",
            forwardingPillLabel(
                SessionForwardingIndicatorState(active = true, tunnelCount = 0, restoring = true),
            ),
        )
    }

    @Test
    fun labelNeverReadsZeroWhileSpinningUp() {
        // Active but the count has not posted yet — never a removed-looking "0".
        val label = forwardingPillLabel(
            SessionForwardingIndicatorState(active = true, tunnelCount = 0),
        )
        assertEquals("…", label)
    }
}
