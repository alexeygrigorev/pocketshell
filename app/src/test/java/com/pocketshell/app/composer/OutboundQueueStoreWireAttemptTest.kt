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
        store.enqueue("sessA", "hello", paneId = "%0")
        assertFalse(
            "a queued-but-never-sent row has no wire attempt (a fresh send must not verify)",
            store.hasWireAttempt("%0", "hello"),
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
        assertFalse(store.hasWireAttempt("%0", "deploy now"))
    }

    @Test
    fun markInFlightDoesNotStampWireAttempted() {
        // Issue #1577: as with claimNext, markInFlight (what the composer runs before
        // dispatch) must not stamp the flag before any byte is pushed.
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "restart pool", paneId = "%0")
        val updated = store.markInFlight(row.id)!!
        assertFalse(updated.wireAttempted)
        assertFalse(store.hasWireAttempt("%0", "restart pool"))
    }

    @Test
    fun wirePushStampsWireAttemptedWithBaseline() {
        // Issue #1541/#1577: the ONLY correct write site — the actual wire push —
        // sets the flag and records the pre-send needle baseline on the row.
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "deploy now", paneId = "%0")
        store.claim(row.id) // InFlight, but no flag yet (#1577)
        assertFalse(store.hasWireAttempt("%0", "deploy now"))

        val pushed = store.markWireAttempted("%0", "deploy now", baselineCount = 0)!!
        assertTrue("the wire push sets the durable flag", pushed.wireAttempted)
        assertNotNull(pushed.wireAttemptedAtMs)
        assertEquals(0, pushed.wireNeedleBaselineCount)
        assertTrue(store.hasWireAttempt("%0", "deploy now"))
        assertEquals(0, store.wireNeedleBaseline("%0", "deploy now"))
    }

    @Test
    fun markWireAttemptedMatchesByPaneAndPayloadNotId() {
        val store = InMemoryOutboundQueueStore()
        store.enqueue("sessA", "ship the notes", paneId = "%0")
        // The ledger keys by pane + payload (it does not know the durable id).
        val marked = store.markWireAttempted("%0", "ship the notes")
        assertNotNull("markWireAttempted must locate the row by pane + payload", marked)
        assertTrue(marked!!.wireAttempted)
        assertTrue(store.hasWireAttempt("%0", "ship the notes"))
        // A different pane / different payload does not match.
        assertFalse(store.hasWireAttempt("%1", "ship the notes"))
        assertFalse(store.hasWireAttempt("%0", "something else"))
        assertNull(store.markWireAttempted("%9", "no such row"))
    }

    @Test
    fun requeueForRetryPreservesWireAttempted() {
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "deferred payload", paneId = "%0")
        store.markInFlight(row.id)
        // Issue #1577: the flag is set at the wire push, not the claim.
        store.markWireAttempted("%0", "deferred payload")
        // The drop-failure path defers the row back to Queued (issue #987) — the
        // wire attempt MUST persist so the re-flush verifies before re-pasting.
        val requeued = store.requeueForRetry(row.id)!!
        assertEquals(OutboundState.Queued, requeued.state)
        assertTrue("a requeued row keeps its durable wire attempt", requeued.wireAttempted)
        assertTrue(store.hasWireAttempt("%0", "deferred payload"))
    }

    @Test
    fun requeueStaleInFlightPreservesWireAttempted() {
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "stale payload", paneId = "%0", createdAtMs = 1_000L)
        store.markInFlight(row.id)
        store.markWireAttempted("%0", "stale payload") // #1577: flag set at the wire push
        val requeued = store.requeueStaleInFlight("sessA", cutoffMs = Long.MAX_VALUE)
        assertEquals(1, requeued.size)
        assertTrue("a stale-recovered row keeps its durable wire attempt", requeued.single().wireAttempted)
        assertTrue(store.hasWireAttempt("%0", "stale payload"))
    }

    @Test
    fun deliveredPruneDropsTheWireAttemptFlag() {
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "delivered payload", paneId = "%0")
        store.markInFlight(row.id)
        store.markWireAttempted("%0", "delivered payload") // #1577: flag set at the wire push
        assertTrue(store.hasWireAttempt("%0", "delivered payload"))
        assertTrue(store.markDelivered(row.id))
        assertFalse(
            "a delivered+pruned row leaves no wire attempt — a deliberate identical " +
                "re-send after delivery is a normal full send, not a verify",
            store.hasWireAttempt("%0", "delivered payload"),
        )
    }

    @Test
    fun removeAndClearSessionDropTheWireAttemptFlag() {
        val store = InMemoryOutboundQueueStore()
        val row = store.enqueue("sessA", "cancel me", paneId = "%0")
        store.markInFlight(row.id)
        store.markWireAttempted("%0", "cancel me") // #1577: flag set at the wire push
        assertTrue(store.hasWireAttempt("%0", "cancel me"))
        assertTrue(store.remove(row.id))
        assertFalse(store.hasWireAttempt("%0", "cancel me"))

        val other = store.enqueue("sessA", "clear me", paneId = "%0")
        store.markInFlight(other.id)
        store.markWireAttempted("%0", "clear me")
        store.clearSession("sessA")
        assertFalse(store.hasWireAttempt("%0", "clear me"))
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
        first.markWireAttempted("%0", "survive the restart") // #1577: pushed to the wire → durable flag persisted

        // A brand-new store over the same on-disk prefs = the fresh process / the
        // VM-clear-rebuilt ledger's durable backing.
        val afterRestart = SharedPrefsOutboundQueueStore(context)
        assertTrue(
            "the rebuilt ledger must still see the prior wire attempt (durable flag)",
            afterRestart.hasWireAttempt("%0", "survive the restart"),
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
        first.markWireAttempted("%0", "delivered but prune lost") // #1577: flag set at the wire push
        // markDelivered() is NEVER persisted (its apply() was lost) — the InFlight
        // row survives the restart with the flag intact.
        val afterRestart = SharedPrefsOutboundQueueStore(context)
        assertEquals(OutboundState.InFlight, afterRestart.item(row.id)!!.state)
        assertTrue(
            "a delivered row whose prune was lost still carries the wire attempt, so " +
                "the rebuilt ledger verifies instead of re-pasting",
            afterRestart.hasWireAttempt("%0", "delivered but prune lost"),
        )
    }

    /** The durable adapter the ledger consumes delegates to the store row flag. */
    @Test
    fun asWireAttemptDurableStoreDelegatesToTheRow() {
        val store = InMemoryOutboundQueueStore()
        store.enqueue("sessA", "adapter payload", paneId = "%0")
        val durable = store.asWireAttemptDurableStore()
        assertFalse(durable.hasWireAttempt("%0", "adapter payload"))
        durable.recordWireAttempt("%0", "adapter payload", atMs = 123L)
        assertTrue(durable.hasWireAttempt("%0", "adapter payload"))
        assertTrue(store.item(store.itemsFor("sessA").single().id)!!.wireAttempted)
    }
}
