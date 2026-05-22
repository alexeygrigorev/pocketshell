package com.pocketshell.app.systemsurfaces

import org.junit.Assert.assertEquals
import org.junit.Test

class SystemSurfaceStateTest {

    @Test
    fun activeSessionCountTextFormatsZeroAndPlural() {
        assertEquals("0 active sessions", activeSessionCountText(0))
        assertEquals("2 active sessions", activeSessionCountText(2))
    }

    @Test
    fun activeSessionCountTextFormatsSingular() {
        assertEquals("1 active session", activeSessionCountText(1))
    }

    @Test
    fun activeSessionCountTextClampsNegativeCounts() {
        assertEquals("0 active sessions", activeSessionCountText(-3))
    }

    @Test
    fun bootForwardingMessageFormatsCounts() {
        assertEquals("No enabled forwarding hosts queued for restore", bootForwardingMessage(0))
        assertEquals("Forwarding restore pending for enabled hosts", bootForwardingMessage(1))
        assertEquals("Forwarding restore pending for 2 enabled hosts", bootForwardingMessage(2))
    }

    @Test
    fun bootForwardingMessageClampsNegativeCounts() {
        assertEquals("No enabled forwarding hosts queued for restore", bootForwardingMessage(-1))
    }
}
