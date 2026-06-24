package com.pocketshell.app.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #900 — Slice A: the serialized blob the [SharedPrefsOutboundQueueStore]
 * persists must round-trip losslessly, including text/paths/errors that contain
 * tabs or newlines, so a queued item survives process death and app restart
 * (AC1). The cross-process durability over a REAL SharedPreferences file is
 * proven separately in [SharedPrefsOutboundQueueStoreDurabilityTest]
 * (Robolectric).
 */
class OutboundQueueStoreEncodingTest {

    @Test
    fun encodeDecodeRoundTripsAllFields() {
        val items = listOf(
            OutboundItem(
                id = "11111111-1111-1111-1111-111111111111",
                sessionKey = "sessA",
                cleanText = "deploy the thing",
                attachments = listOf(
                    DurableAttachmentRef("~/.pocketshell/a/shot.png", "shot.png", "image/png"),
                    DurableAttachmentRef("~/.pocketshell/a/report.txt", "report.txt", null),
                ),
                withEnter = true,
                state = OutboundState.InFlight,
                createdAtMs = 1_700_000_000_000,
                lastAttemptAtMs = 1_700_000_005_000,
                attemptCount = 2,
                lastError = "ack timeout",
            ),
            OutboundItem(
                id = "22222222-2222-2222-2222-222222222222",
                sessionKey = "sessA",
                cleanText = "no enter",
                withEnter = false,
                state = OutboundState.Queued,
                createdAtMs = 1_700_000_001_000,
            ),
        )
        val decoded = decodeOutboundItems("sessA", encodeOutboundItems(items))
        assertEquals(items, decoded)
    }

    @Test
    fun encodeDecodeSurvivesTabsAndNewlinesInTextAndError() {
        val items = listOf(
            OutboundItem(
                id = "id-x",
                sessionKey = "sessA",
                cleanText = "line one\nline\ttwo",
                state = OutboundState.Failed,
                createdAtMs = 1L,
                lastError = "failed:\tdetail\nmore",
            ),
        )
        val decoded = decodeOutboundItems("sessA", encodeOutboundItems(items))
        assertEquals(items, decoded)
    }

    @Test
    fun decodeEmptyStringIsEmptyList() {
        assertTrue(decodeOutboundItems("sessA", "").isEmpty())
    }

    @Test
    fun decodeDropsRowsWithBlankIdOrUnparseableCreatedAt() {
        // No id → dropped; non-numeric createdAt → dropped.
        val raw = "\thello\t1\tQueued\t100\t\t0\t\t" + "\n" + "id-2\thi\t1\tQueued\tNaN\t\t0\t\t"
        assertTrue(decodeOutboundItems("sessA", raw).isEmpty())
    }

    @Test
    fun decodeRebindsSessionKeyFromCaller() {
        // The blob does not store the sessionKey (it is the prefs key); decode
        // stamps the provided sessionKey onto every item.
        val items = listOf(
            OutboundItem(id = "id-1", sessionKey = "ignored", cleanText = "x", createdAtMs = 1L),
        )
        val decoded = decodeOutboundItems("realSession", encodeOutboundItems(items))
        assertEquals("realSession", decoded.single().sessionKey)
    }

    @Test
    fun blobKeyIsNamespacedToAvoidCollisions() {
        assertEquals("@q/sessA", blobKey("sessA"))
    }
}
