package com.pocketshell.app.composer

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Issue #900 — Slice A: durability of the production [SharedPrefsOutboundQueueStore]
 * across a simulated process restart, and that the SharedPreferences impl honours
 * the SAME send-once contract as the in-memory core.
 *
 * Robolectric gives a real [Context] + a real on-disk SharedPreferences file, so
 * "restart" is modelled by constructing a SECOND store instance over the same
 * application context — exactly what a fresh process gets after the app is killed
 * and relaunched. This is the AC1 "durable across app restart" proof on the real
 * persistence path (not just the in-memory analogue).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SharedPrefsOutboundQueueStoreDurabilityTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun newStore() = SharedPrefsOutboundQueueStore(context)

    @Test
    fun queuedItemSurvivesProcessRestartWithEveryFieldIntact() {
        val first = newStore()
        val enqueued = first.enqueue(
            sessionKey = "sessA",
            cleanText = "deploy\tnow\nplease",
            attachments = listOf(DurableAttachmentRef("~/a.png", "a.png", "image/png")),
            withEnter = true,
            createdAtMs = 1_700_000_000_000,
        )

        // Simulate a process restart: a brand-new store instance over the same
        // on-disk prefs file.
        val afterRestart = newStore()
        val reloaded = afterRestart.itemsFor("sessA")
        assertEquals(1, reloaded.size)
        assertEquals(enqueued, reloaded.single())
        assertEquals(enqueued, afterRestart.item(enqueued.id))
    }

    @Test
    fun inFlightStateSurvivesRestart_soAReconnectFlushDoesNotRePaste() {
        val first = newStore()
        val item = first.enqueue("sessA", "exactly once")
        first.claimNext("sessA") // → InFlight, persisted

        // Restart while the item is InFlight (the app was killed mid-delivery).
        val afterRestart = newStore()
        val reloaded = afterRestart.item(item.id)!!
        assertEquals(OutboundState.InFlight, reloaded.state)
        // A reconnect-triggered flush after restart must NOT re-claim it.
        assertNull(afterRestart.claimNext("sessA"))
    }

    @Test
    fun sendOnceContractHoldsOnSharedPrefsImpl() {
        val store = newStore()
        val item = store.enqueue("sessA", "once")
        // Duplicate enqueue of the pending id → no second item.
        store.enqueueExisting(item)
        assertEquals(1, store.itemsFor("sessA").size)
        // Claim → deliver once → prune; a late duplicate ack is a no-op.
        store.claimNext("sessA")
        assertTrue(store.markDelivered(item.id))
        assertFalse(store.markDelivered(item.id))
        assertNull(store.item(item.id))
        assertTrue(store.itemsFor("sessA").isEmpty())
    }

    @Test
    fun perSessionIsolationHoldsAcrossRestart() {
        val first = newStore()
        val a = first.enqueue("sessA", "for A")
        val b = first.enqueue("sessB", "for B")

        val afterRestart = newStore()
        // Draining A never yields B's item.
        val claimedA = afterRestart.claimNext("sessA")!!
        assertEquals(a.id, claimedA.id)
        assertNull(afterRestart.claimNext("sessA"))
        // B is intact.
        assertEquals(OutboundState.Queued, afterRestart.item(b.id)!!.state)
    }

    @Test
    fun clearSessionRemovesBlobAndSessionIndexEntry() {
        val store = newStore()
        store.enqueue("sessA", "x")
        store.clearSession("sessA")
        // A fresh instance sees nothing — the blob and index entry are gone.
        val afterRestart = newStore()
        assertTrue(afterRestart.itemsFor("sessA").isEmpty())
        assertNull(afterRestart.item("anything"))
    }
}
