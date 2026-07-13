package com.pocketshell.app.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
                paneId = "%0",
                route = OutboundRoute.AgentConversation,
                agentKind = "claude",
            ),
            OutboundItem(
                id = "22222222-2222-2222-2222-222222222222",
                sessionKey = "sessA",
                cleanText = "no enter",
                withEnter = false,
                state = OutboundState.Queued,
                createdAtMs = 1_700_000_001_000,
                paneId = "%1",
                route = OutboundRoute.AgentPayload,
                agentKind = "codex",
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
                paneId = "%0\tpane\nsuffix",
                route = OutboundRoute.RawBytes,
                agentKind = "open\tcode",
            ),
        )
        val decoded = decodeOutboundItems("sessA", encodeOutboundItems(items))
        assertEquals(items, decoded)
    }

    @Test
    fun encodeDecodeRoundTripsSendKey() {
        // Issue #961: the logical-send coalesce key must survive process death so
        // a re-Send after app restart still coalesces onto the persisted row.
        val items = listOf(
            OutboundItem(
                id = "id-sk",
                sessionKey = "sessA",
                cleanText = "deploy now",
                state = OutboundState.Failed,
                createdAtMs = 1L,
                sendKey = "abc123def456",
            ),
        )
        val decoded = decodeOutboundItems("sessA", encodeOutboundItems(items))
        assertEquals(items, decoded)
        assertEquals("abc123def456", decoded.single().sendKey)
    }

    @Test
    fun decodeLegacyRowsWithoutSendKeyDefaultToEmpty() {
        // Issue #961: pre-sendKey rows ended at agentKind (field 11). They must
        // decode to an empty sendKey (never-coalesce), not a malformed row.
        val raw = "id-legacy\tx\t1\tQueued\t100\t\t0\t\t\t%0\tRawBytes\tclaude"
        val decoded = decodeOutboundItems("sessA", raw).single()
        assertEquals("", decoded.sendKey)
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
    fun decodeLegacyRowsDefaultsMissingRouteMetadata() {
        // Pre-route rows ended at attachmentsBlob.
        val raw = "id-legacy\tlegacy text\t1\tQueued\t100\t\t0\t\t"
        val decoded = decodeOutboundItems("sessA", raw).single()

        assertEquals("", decoded.paneId)
        assertEquals(OutboundRoute.RawBytes, decoded.route)
        assertEquals(null, decoded.agentKind)
    }

    @Test
    fun encodeDecodeRoundTripsWireAttempted() {
        // Issue #1541: the durable per-row wire-attempt flag must survive process
        // death so a ledger rebuilt after a VM-clear / restart re-enters
        // verify-before-resend instead of blindly re-pasting.
        val items = listOf(
            OutboundItem(
                id = "id-wa",
                sessionKey = "sessA",
                cleanText = "wire attempted payload",
                state = OutboundState.InFlight,
                createdAtMs = 1L,
                paneId = "%0",
                wireAttempted = true,
                wireAttemptedAtMs = 1_700_000_009_000,
            ),
        )
        val decoded = decodeOutboundItems("sessA", encodeOutboundItems(items))
        assertEquals(items, decoded)
        assertTrue(decoded.single().wireAttempted)
        assertEquals(1_700_000_009_000, decoded.single().wireAttemptedAtMs)
    }

    @Test
    fun decodeLegacyRowsWithoutWireAttemptedDefaultToFalse() {
        // Issue #1541: pre-#1541 rows ended at sendKey (field 12). They must decode
        // to wireAttempted=false (a fresh send), not a malformed row.
        val raw = "id-legacy\tx\t1\tQueued\t100\t\t0\t\t\t%0\tRawBytes\tclaude\tsk123"
        val decoded = decodeOutboundItems("sessA", raw).single()
        assertFalse(decoded.wireAttempted)
        assertEquals(null, decoded.wireAttemptedAtMs)
        assertEquals("sk123", decoded.sendKey)
    }

    @Test
    fun decodeUnknownRouteDefaultsToRawBytes() {
        val raw = "id-unknown\tx\t1\tQueued\t100\t\t0\t\t\t%0\tFutureRoute\tclaude"
        val decoded = decodeOutboundItems("sessA", raw).single()

        assertEquals("%0", decoded.paneId)
        assertEquals(OutboundRoute.RawBytes, decoded.route)
        assertEquals("claude", decoded.agentKind)
    }

    @Test
    fun blobKeyIsNamespacedToAvoidCollisions() {
        assertEquals("@q/sessA", blobKey("sessA"))
    }
}
