package com.pocketshell.app.portfwd.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ForwardingChipShortLabelTest {
    @Test
    fun singleKnownPortNamesTheRemotePort() {
        assertEquals(
            ":65535",
            forwardingChipShortLabel(1, 0, setOf(65535)),
        )
    }

    @Test
    fun multiplePortsUseCountInsteadOfAnIncompletePortList() {
        assertEquals(
            "3 ports",
            forwardingChipShortLabel(3, 0, setOf(2222, 8080, 9090)),
        )
    }

    @Test
    fun singlePortWithoutSettledPortMapUsesCount() {
        assertEquals("1 port", forwardingChipShortLabel(1, 0, emptySet()))
    }

    @Test
    fun restoringAndStartingNeverReadZeroPorts() {
        assertEquals("…", forwardingChipShortLabel(0, 1, emptySet()))
        assertEquals("…", forwardingChipShortLabel(0, 0, emptySet()))
    }

    @Test
    fun everySupportedShapeStaysInsideSevenCharacterBudget() {
        val labels = listOf(
            forwardingChipShortLabel(1, 0, setOf(1)),
            forwardingChipShortLabel(1, 0, setOf(65535)),
            forwardingChipShortLabel(2, 0, setOf(1, 2)),
            forwardingChipShortLabel(9, 0, emptySet()),
            forwardingChipShortLabel(10, 0, emptySet()),
            forwardingChipShortLabel(99_999, 0, emptySet()),
            forwardingChipShortLabel(Int.MAX_VALUE, 0, emptySet()),
            forwardingChipShortLabel(0, 1, emptySet()),
        )

        assertTrue(
            "Android short critical text must stay <=7 chars; labels=$labels",
            labels.all { it.length <= 7 },
        )
    }
}
