package com.pocketshell.app.tmux

import com.pocketshell.uikit.model.ConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2130 — the breadcrumb pill must pick a complete honest word, never a
 * clipped prefix like `Reco`. Widths here are synthetic character counts so
 * the picker can be mutated independently of Compose layout.
 */
class ConnectionStatusPillLabelTest {

    private val widths = mapOf(
        "Reconnecting" to 100,
        "Retrying" to 66,
        "Retry" to 47,
        "Disconnected" to 96,
        "Offline" to 52,
        "Connecting" to 78,
    )

    private fun measure(label: String): Int = widths.getValue(label)

    @Test
    fun wideSlotKeepsTheFullReconnectingWord() {
        assertEquals(
            "Reconnecting",
            ConnectionStatusPillLabels.pick(ConnectionStatus.Connecting, 200, ::measure),
        )
    }

    @Test
    fun midSlotStepsDownToRetryingNotAPrefix() {
        // Fits Retrying (66) but not Reconnecting (100) — the Pixel-7 +
        // conversation-toggle leftover that rendered `Reco` on base.
        assertEquals(
            "Retrying",
            ConnectionStatusPillLabels.pick(ConnectionStatus.Connecting, 80, ::measure),
        )
    }

    @Test
    fun tightSlotStillReturnsACompleteWord() {
        assertEquals(
            "Retry",
            ConnectionStatusPillLabels.pick(ConnectionStatus.Connecting, 50, ::measure),
        )
    }

    @Test
    fun zeroWidthStillNeverReturnsAFragment() {
        val label = ConnectionStatusPillLabels.pick(ConnectionStatus.Connecting, 0, ::measure)
        assertTrue(label in setOf("Reconnecting", "Retrying", "Retry"))
        assertFalse(
            "picker must not invent a truncated prefix when nothing fits",
            label == "Reco" || label.startsWith("Reco") && label != "Reconnecting",
        )
        assertEquals("Retry", label)
    }

    @Test
    fun disconnectedStepsDownToOfflineWhenTight() {
        assertEquals(
            "Disconnected",
            ConnectionStatusPillLabels.pick(ConnectionStatus.Error, 200, ::measure),
        )
        assertEquals(
            "Offline",
            ConnectionStatusPillLabels.pick(ConnectionStatus.Error, 60, ::measure),
        )
    }

    @Test
    fun connectedHasNoPillLabel() {
        assertEquals(
            "",
            ConnectionStatusPillLabels.pick(ConnectionStatus.Connected, 200, ::measure),
        )
        assertTrue(ConnectionStatusPillLabels.candidates(ConnectionStatus.Connected).isEmpty())
    }

    @Test
    fun restoringOldAlwaysReconnectingWouldPickTheWordThatClips() {
        // G6: a picker that always returns "Reconnecting" (the pre-fix label)
        // is exactly what produced `Reco` at 80px leftover. This test names
        // that mutation: at the reported mid width we must NOT stay on the
        // full word that cannot fit.
        val atReportedWidth = ConnectionStatusPillLabels.pick(
            ConnectionStatus.Connecting,
            80,
            ::measure,
        )
        assertTrue(
            "at the width where 'Reconnecting' cannot fit, the picker must step down",
            atReportedWidth != "Reconnecting",
        )
        assertTrue(atReportedWidth in setOf("Retrying", "Retry"))
    }
}
