package com.pocketshell.app.composer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #1541 (finding P9): the durable per-row `wireAttempted` flag that makes
 * the verify-before-resend ledger survive a plain **VM-clear / back-navigation**,
 * not only live-VM retries.
 *
 * The #1526 S1 exactly-once ledger is VOLATILE (held on `TmuxSessionViewModel`),
 * so an ordinary Back mid-delivery destroyed it: reopen → the fresh ledger had no
 * memory of the in-flight attempt → the row was blindly re-pasted → server
 * occurrence 2. This suite proves the store side of the durable fix:
 *
 *  - a row PUSHED TO THE WIRE ([claimNext]/[claim]/[markInFlight] or
 *    [OutboundQueueStore.markWireAttempted]) carries a durable
 *    [OutboundItem.wireAttempted] flag,
 *  - the flag survives a simulated process restart (a fresh store over the same
 *    prefs — the SAME mechanism a VM-clear rebuild reads through),
 *  - it is PRESERVED across requeue (deferred rows still verify), and
 *  - it is dropped only when the row leaves the queue (delivered-prune / remove /
 *    clear), which is exactly what also closes the `markDelivered`-lost-on-`apply()`
 *    corner (a delivered row whose prune was lost keeps the flag → verify, not
 *    re-paste).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OutboundQueueStoreWireAttemptTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    // ---------------------------------------------------------- in-memory mechanics

    @Test
    fun freshlyEnqueuedRowHasNoWireAttempt() {
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "hello", paneId = "%0")
        assertFalse(
            "a queued-but-never-sent row has no wire attempt (a fresh send must not verify)",
            store.hasWireAttempt("sessA", row.id),
        )
    }

    @Test
    fun claimNextDoesNotStampWireAttempted() {
        // Issue #1577: the CLAIM (InFlight) must NOT stamp `wireAttempted` — a queue
        // row is claimed before the composer send emits a single byte. Stamping at
        // claim marked every FIRST send as a prior wire attempt, forcing the #1526
        // verify-before-resend probe on the first send (the silent false-success
        // drop). The flag is set ONLY at the actual wire push (`markWireAttempted`).
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "deploy now", paneId = "%0")
        assertFalse(store.item(row.id)!!.wireAttempted)

        val claimed = store.claimNext("sessA")!!
        assertEquals(OutboundState.InFlight, claimed.state)
        assertFalse("claiming must NOT set the durable wire-attempt flag (#1577)", claimed.wireAttempted)
        assertFalse(store.hasWireAttempt("sessA", row.id))
    }

    @Test
    fun markInFlightDoesNotStampWireAttempted() {
        // Issue #1577: as with claimNext, markInFlight (what the composer runs before
        // dispatch) must not stamp the flag before any byte is pushed.
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "restart pool", paneId = "%0")
        val updated = store.markInFlight(row.id)!!
        assertFalse(updated.wireAttempted)
        assertFalse(store.hasWireAttempt("sessA", row.id))
    }

    @Test
    fun wirePushStampsWireAttemptedWithBaseline() {
        // Issue #1541/#1577: the ONLY correct write site — the actual wire push —
        // sets the flag and records the pre-send needle baseline on the row.
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "deploy now", paneId = "%0")
        store.claim(row.id) // InFlight, but no flag yet (#1577)
        assertFalse(store.hasWireAttempt("sessA", row.id))

        val pushed = store.markWireAttempted(
            "sessA",
            row.id,
            baselineCount = 0,
            collapsedMarkerBaselineCount = 2,
        )!!
        assertTrue("the wire push sets the durable flag", pushed.wireAttempted)
        assertNotNull(pushed.wireAttemptedAtMs)
        assertEquals(0, pushed.wireNeedleBaselineCount)
        assertEquals(2, pushed.wireCollapsedMarkerBaselineCount)
        assertTrue(store.hasWireAttempt("sessA", row.id))
        assertEquals(0, store.wireNeedleBaseline("sessA", row.id))
        assertEquals(2, store.wireCollapsedMarkerBaseline("sessA", row.id))
    }

    @Test
    fun submitAttemptSurvivesRequeueUntilRowLeavesQueue() {
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "ambiguous submit", paneId = "%0")
        store.claim(row.id)
        store.markWireAttempted("sessA", row.id, baselineCount = 0)

        val baseline = OutboundSubmitTranscriptBaseline(
            sourcePath = "/home/u/.codex/sessions/rollout.jsonl",
            agentSessionId = "rollout",
            agentKind = "Codex",
            confirmedMatchingIds = setOf("old-turn"),
        )
        val attempted = requireNotNull(store.markWireSubmitAttempted("sessA", row.id, baseline))
        assertTrue(attempted.wireSubmitAttempted)
        assertTrue(store.hasWireSubmitAttempt("sessA", row.id))
        assertEquals(baseline, store.wireSubmitTranscriptBaseline("sessA", row.id))

        store.requeueForRetry(row.id)
        assertTrue("requeue must preserve Enter ambiguity", store.hasWireSubmitAttempt("sessA", row.id))
        store.remove(row.id)
        assertFalse("row removal drops the latch with the row", store.hasWireSubmitAttempt("sessA", row.id))
    }

    @Test
    fun submitTranscriptBaselineSurvivesProcessRestart() {
        val first = SharedPrefsOutboundQueueStore(context)
        val row = first.enqueue("sess-submit-baseline", "late ack", paneId = "%0")
        first.markWireAttempted("sess-submit-baseline", row.id, baselineCount = 0)
        val baseline = OutboundSubmitTranscriptBaseline(
            sourcePath = "/home/u/.claude/projects/p/session.jsonl",
            agentSessionId = "session-1",
            agentKind = "ClaudeCode",
            confirmedMatchingIds = linkedSetOf("same-payload-before-enter"),
        )
        requireNotNull(first.markWireSubmitAttempted("sess-submit-baseline", row.id, baseline))

        val afterRestart = SharedPrefsOutboundQueueStore(context)
        assertTrue(afterRestart.hasWireSubmitAttempt("sess-submit-baseline", row.id))
        assertEquals(
            baseline,
            afterRestart.wireSubmitTranscriptBaseline("sess-submit-baseline", row.id),
        )
    }

    @Test
    fun markWireAttemptedRequiresExactSessionAndRowId() {
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "ship the notes", paneId = "%0")
        val marked = store.markWireAttempted("sessA", row.id)
        assertNotNull("markWireAttempted must locate the exact durable row", marked)
        assertTrue(marked!!.wireAttempted)
        assertTrue(store.hasWireAttempt("sessA", row.id))
        assertFalse(store.hasWireAttempt("sessB", row.id))
        assertFalse(store.hasWireAttempt("sessA", "no-such-row"))
        assertNull(store.markWireAttempted("sessB", row.id))
        assertNull(store.markWireAttempted("sessA", "no-such-row"))
    }

    @Test
    fun requeueForRetryPreservesWireAttempted() {
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "deferred payload", paneId = "%0")
        store.markInFlight(row.id)
        // Issue #1577: the flag is set at the wire push, not the claim.
        store.markWireAttempted("sessA", row.id)
        // The drop-failure path defers the row back to Queued (issue #987) — the
        // wire attempt MUST persist so the re-flush verifies before re-pasting.
        val requeued = store.requeueForRetry(row.id)!!
        assertEquals(OutboundState.Queued, requeued.state)
        assertTrue("a requeued row keeps its durable wire attempt", requeued.wireAttempted)
        assertTrue(store.hasWireAttempt("sessA", row.id))
    }

    @Test
    fun requeueStaleInFlightPreservesWireAttempted() {
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "stale payload", paneId = "%0", createdAtMs = 1_000L)
        store.markInFlight(row.id)
        store.markWireAttempted("sessA", row.id) // #1577: flag set at the wire push
        val requeued = store.requeueStaleInFlight("sessA", cutoffMs = Long.MAX_VALUE)
        assertEquals(1, requeued.size)
        assertTrue("a stale-recovered row keeps its durable wire attempt", requeued.single().wireAttempted)
        assertTrue(store.hasWireAttempt("sessA", row.id))
    }

    @Test
    fun deliveredPruneDropsTheWireAttemptFlag() {
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "delivered payload", paneId = "%0")
        store.markInFlight(row.id)
        store.markWireAttempted("sessA", row.id) // #1577: flag set at the wire push
        assertTrue(store.hasWireAttempt("sessA", row.id))
        assertTrue(store.markDelivered(row.id))
        assertFalse(
            "a delivered+pruned row leaves no wire attempt — a deliberate identical " +
                "re-send after delivery is a normal full send, not a verify",
            store.hasWireAttempt("sessA", row.id),
        )
    }

    @Test
    fun removeAndClearSessionDropTheWireAttemptFlag() {
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "cancel me", paneId = "%0")
        store.markInFlight(row.id)
        store.markWireAttempted("sessA", row.id) // #1577: flag set at the wire push
        assertTrue(store.hasWireAttempt("sessA", row.id))
        assertTrue(store.remove(row.id))
        assertFalse(store.hasWireAttempt("sessA", row.id))

        val other = store.enqueue("sessA", "clear me", paneId = "%0")
        store.markInFlight(other.id)
        store.markWireAttempted("sessA", other.id)
        store.clearSession("sessA")
        assertFalse(store.hasWireAttempt("sessA", other.id))
    }

    // ---------------------------------------------------------- durable / restart

    /**
     * THE durability proof: a wire attempt set on one store instance is visible to
     * a FRESH instance over the same prefs — the exact mechanism the verify-before-
     * resend ledger reads through when the VM is cleared on a back-navigation and a
     * new VM rebuilds the ledger. On base (volatile ledger only) this cross-instance
     * memory did not exist, so the reopened session re-pasted (occurrence 2).
     */
    @Test
    fun wireAttemptSurvivesProcessRestartForTheRebuiltLedger() {
        val first = SharedPrefsOutboundQueueStore(context)
        val row = first.enqueue("sessA", "survive the restart", paneId = "%0")
        first.markInFlight(row.id)
        first.markWireAttempted("sessA", row.id) // #1577: pushed to the wire → durable flag persisted

        // A brand-new store over the same on-disk prefs = the fresh process / the
        // VM-clear-rebuilt ledger's durable backing.
        val afterRestart = SharedPrefsOutboundQueueStore(context)
        assertTrue(
            "the rebuilt ledger must still see the prior wire attempt (durable flag)",
            afterRestart.hasWireAttempt("sessA", row.id),
        )
        assertTrue(afterRestart.item(row.id)!!.wireAttempted)
    }

    /**
     * The `markDelivered`-lost-on-`apply()` corner: the delivery succeeded but its
     * prune write was lost (process died before flush), so the row survives. Because
     * the wire attempt is durable, the rebuilt ledger still verifies (already landed
     * ⇒ occurrence 1) instead of blindly re-pasting.
     */
    @Test
    fun lostMarkDeliveredLeavesWireAttemptSoRebuildVerifies() {
        val first = SharedPrefsOutboundQueueStore(context)
        val row = first.enqueue("sessA", "delivered but prune lost", paneId = "%0")
        first.markInFlight(row.id)
        first.markWireAttempted("sessA", row.id) // #1577: flag set at the wire push
        // markDelivered() is NEVER persisted (its apply() was lost) — the InFlight
        // row survives the restart with the flag intact.
        val afterRestart = SharedPrefsOutboundQueueStore(context)
        assertEquals(OutboundState.InFlight, afterRestart.item(row.id)!!.state)
        assertTrue(
            "a delivered row whose prune was lost still carries the wire attempt, so " +
                "the rebuilt ledger verifies instead of re-pasting",
            afterRestart.hasWireAttempt("sessA", row.id),
        )
    }

    /** The durable adapter the ledger consumes delegates to the store row flag. */
    @Test
    fun asWireAttemptDurableStoreDelegatesToTheRow() {
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "adapter payload", paneId = "%0")
        val durable = store.asWireAttemptDurableStore()
        assertFalse(durable.hasWireAttempt("sessA", row.id))
        durable.recordWireAttempt("sessA", row.id, atMs = 123L)
        assertTrue(durable.hasWireAttempt("sessA", row.id))
        assertTrue(store.item(row.id)!!.wireAttempted)
    }

    @Test
    fun identicalPaneAndPayloadAcrossSessionsNeverCrossStampOrRead() {
        val store = SharedPrefsOutboundQueueStore(context)
        store.clearSession("session-a")
        store.clearSession("session-b")
        val rowA = store.enqueue("session-a", "identical payload", paneId = "%0")
        val rowB = store.enqueue("session-b", "identical payload", paneId = "%0")

        store.markWireAttempted(
            sessionKey = "session-a",
            itemId = rowA.id,
            baselineCount = 3,
            collapsedMarkerBaselineCount = 1,
        )

        val rebuilt = SharedPrefsOutboundQueueStore(context)
        assertTrue(rebuilt.hasWireAttempt("session-a", rowA.id))
        assertEquals(3, rebuilt.wireNeedleBaseline("session-a", rowA.id))
        assertEquals(1, rebuilt.wireCollapsedMarkerBaseline("session-a", rowA.id))
        assertFalse("session B's identical row must remain fresh", rebuilt.hasWireAttempt("session-b", rowB.id))
        assertNull(rebuilt.wireNeedleBaseline("session-b", rowB.id))
        assertNull(rebuilt.wireCollapsedMarkerBaseline("session-b", rowB.id))
        assertFalse("a row id cannot be read through the wrong session", rebuilt.hasWireAttempt("session-b", rowA.id))
    }
}
