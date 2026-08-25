package com.pocketshell.app.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2240 — prerequisite model/API slice.
 *
 * The outbound queue blob is positional. A HostAck outcome must be appended
 * after the existing fields so old rows keep their established indexes.
 */
class Issue2240HostAckOutcomeModelTest {

    @Test
    fun hostAckOutcomeSlotIsAppendedAfterExistingSerializedFields() {
        val item = OutboundItem(
            id = "id-host-ack",
            sessionKey = "sessA",
            cleanText = "send me",
            state = OutboundState.Failed,
            createdAtMs = 1_700_000_000_000L,
            sendKey = "send-key",
            wireAttempted = true,
            wireAttemptedAtMs = 1_700_000_001_000L,
            wireAttemptGeneration = 2,
            wireOutcomeUnknown = true,
            staleApprovedAtMs = 1_700_000_002_000L,
            hostAckOutcome = OutboundDeliveryOutcome.UnknownMayHaveLanded,
        )

        val fields = encodeOutboundItems(listOf(item)).split('\t')

        assertEquals(
            "HostAck must add one trailing field without renumbering the queue schema",
            28,
            fields.size,
        )
        assertEquals("send-key", fields[12])
        assertEquals("1", fields[13])
        assertEquals("1700000001000", fields[14])
        assertEquals("2", fields[24])
        assertEquals("1", fields[25])
        assertEquals("1700000002000", fields[26])
        assertEquals("UnknownMayHaveLanded", fields[27])
        assertEquals(item, decodeOutboundItems("sessA", fields.joinToString("\t")).single())
    }

    @Test
    fun defaultAndLegacyRowsUseNoneHostAckOutcome() {
        val defaultItem = OutboundItem(
            id = "default-host-ack",
            sessionKey = "sessA",
            cleanText = "send me",
            createdAtMs = 1L,
        )
        assertEquals(OutboundDeliveryOutcome.None, defaultItem.hostAckOutcome)

        // This is a pre-#2240 row with all existing fields through index 26.
        val legacyFields = listOf(
            "legacy-host-ack",
            "legacy text",
            "1",
            "Failed",
            "100",
            "90",
            "1",
            "old error",
            "",
            "%0",
            "RawBytes",
            "claude",
            "legacy-key",
            "1",
            "80",
            "",
            "",
            "tmux-1",
            "70",
            "1",
            "source.jsonl",
            "agent-1",
            "Claude",
            "old-id",
            "2",
            "1",
            "60",
        )
        val decoded = decodeOutboundItems("sessA", legacyFields.joinToString("\t")).single()

        assertEquals("legacy-key", decoded.sendKey)
        assertEquals(2, decoded.wireAttemptGeneration)
        assertEquals(OutboundDeliveryOutcome.None, decoded.hostAckOutcome)
    }

    @Test
    fun presentUnknownHostAckOutcomeFailsClosedAsUnknownMayHaveLanded() {
        val legacyFields = listOf(
            "unknown-host-ack",
            "legacy text",
            "1",
            "Failed",
            "100",
            "90",
            "1",
            "old error",
            "",
            "%0",
            "RawBytes",
            "claude",
            "legacy-key",
            "1",
            "80",
            "",
            "",
            "tmux-1",
            "70",
            "1",
            "source.jsonl",
            "agent-1",
            "Claude",
            "old-id",
            "2",
            "1",
            "60",
        )

        val decoded = decodeOutboundItems(
            "sessA",
            (legacyFields + "FutureHostAckOutcome").joinToString("\t"),
        ).single()

        assertEquals(
            "a present unknown outcome must never become ordinary-retryable None",
            OutboundDeliveryOutcome.UnknownMayHaveLanded,
            decoded.hostAckOutcome,
        )
        assertFalse(decoded.isHostAckOrdinaryRetryAllowed())
    }

    @Test
    fun ordinaryHostAckRetryIsAllowedOnlyWhenOutcomeIsNone() {
        val ordinary = OutboundItem(
            id = "ordinary-host-ack",
            sessionKey = "sessA",
            cleanText = "send me",
            createdAtMs = 1L,
        )
        val maybeLanded = ordinary.copy(
            hostAckOutcome = OutboundDeliveryOutcome.UnknownMayHaveLanded,
        )

        assertTrue(ordinary.isHostAckOrdinaryRetryAllowed())
        assertFalse(maybeLanded.isHostAckOrdinaryRetryAllowed())
    }
}
