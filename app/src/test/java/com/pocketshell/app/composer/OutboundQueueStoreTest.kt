package com.pocketshell.app.composer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #900 — Slice A: SEND-ONCE / idempotency core of the per-session
 * outbound queue.
 *
 * These are the red→green proofs of the maintainer's 3×-duplicate root cause:
 * an enqueue without item identity + a non-durable in-flight guard let repeated
 * taps + reconnect re-emits deliver the same payload multiple times. The store
 * cures it with (a) a stable id minted once + a no-op repeat enqueue, and
 * (b) a serialized [OutboundQueueStore.claimNext] that two concurrent flushers
 * can never both win.
 *
 * The [InMemoryOutboundQueueStore] is the JVM-testable core (the
 * SharedPreferences impl is the same logic over a durable blob; its serialized
 * encode/decode is proven separately in
 * [OutboundQueueStoreEncodingTest], and a Robolectric durability round-trip is
 * proven in the androidTest source set).
 */
class OutboundQueueStoreTest {

    private fun store() = InMemoryOutboundQueueStore()

    // --- Enqueue mints a stable id (the durable idempotency key) -----------

    @Test
    fun enqueueMintsOneStableIdAndPersistsBeforeAnyAttempt() {
        val store = store()
        val item = store.enqueue("sessA", "hello")
        assertTrue("id must be minted", item.id.isNotEmpty())
        assertEquals(OutboundState.Queued, item.state)
        // Persisted immediately, retrievable by id.
        assertEquals(item, store.item(item.id))
        assertEquals(listOf(item), store.itemsFor("sessA"))
    }

    @Test
    fun twoSeparateEnqueuesGetDistinctIdsAndAreTwoItems() {
        val store = store()
        val a = store.enqueue("sessA", "one")
        val b = store.enqueue("sessA", "two")
        assertFalse("distinct taps mint distinct ids", a.id == b.id)
        assertEquals(2, store.itemsFor("sessA").size)
    }

    // --- Repeated enqueue of a PENDING id is a strict no-op ----------------

    @Test
    fun repeatedEnqueueOfPendingIdIsNoOp_stillExactlyOneItem() {
        val store = store()
        val original = store.enqueue("sessA", "deploy now")

        // A retry path re-enqueues the SAME id while it is still pending.
        val again = store.enqueueExisting(original)
        assertEquals("must return the existing item, not a copy", original.id, again.id)
        assertEquals("repeat enqueue of a pending id must NOT add a second item",
            1, store.itemsFor("sessA").size)

        // Even after it goes InFlight, a duplicate enqueue is still a no-op.
        store.claimNext("sessA")
        store.enqueueExisting(original)
        assertEquals(1, store.itemsFor("sessA").size)
        assertEquals(OutboundState.InFlight, store.item(original.id)!!.state)
    }

    @Test
    fun enqueueExistingOfFailedIdReArmsToQueuedWithoutDuplicating() {
        val store = store()
        val item = store.enqueue("sessA", "retry me")
        store.claimNext("sessA")
        store.markFailed(item.id, "link dropped")
        assertEquals(OutboundState.Failed, store.item(item.id)!!.state)

        // Explicit retry: re-arm, do NOT duplicate.
        val rearmed = store.enqueueExisting(store.item(item.id)!!)
        assertEquals(OutboundState.Queued, rearmed.state)
        assertEquals(1, store.itemsFor("sessA").size)
    }

    // --- The exactly-once heart: two concurrent claims, one delivers -------

    @Test
    fun twoConcurrentClaims_exactlyOneGetsTheItem_theOtherGetsNothing() = runBlocking {
        val store = store()
        val item = store.enqueue("sessA", "only once")

        // Hammer it: many parallel claimNext on the SAME session with ONE
        // queued item. Exactly one must return the item; all others null.
        val workers = 32
        val results = coroutineScope {
            (1..workers).map {
                async(Dispatchers.Default) { store.claimNext("sessA") }
            }.awaitAll()
        }
        val winners = results.filterNotNull()
        assertEquals("exactly one flusher may claim a single queued item", 1, winners.size)
        assertEquals(item.id, winners.single().id)
        assertEquals(OutboundState.InFlight, winners.single().state)
        // Everyone else got nothing — no double delivery is possible.
        assertEquals(workers - 1, results.count { it == null })
    }

    @Test
    fun concurrentClaims_withBarrier_neverDoubleClaimAcrossManyItems() = runBlocking {
        // A stress variant: N items, M concurrent claimers released together.
        // Every item is claimed by AT MOST one claimer (no id appears twice).
        val store = store()
        val itemCount = 50
        repeat(itemCount) { store.enqueue("sessA", "msg-$it", createdAtMs = it.toLong()) }

        val claimers = 16
        val barrier = CyclicBarrier(claimers)
        // Dispatchers.Default is capped at ~nproc threads (<16 on CI), so a
        // CyclicBarrier(16) running there could never reach its trip count →
        // permanent deadlock. Park the claimers on a dedicated pool sized to the
        // party count so all 16 can actually block on the barrier and trip it,
        // preserving the "released together → maximal contention" intent.
        val barrierPool = Executors.newFixedThreadPool(claimers)
        val barrierDispatcher = barrierPool.asCoroutineDispatcher()
        val claimed = try {
            coroutineScope {
                (1..claimers).map {
                    async(barrierDispatcher) {
                        barrier.await() // release all at once → maximal contention
                        buildList {
                            while (true) add(store.claimNext("sessA") ?: break)
                        }
                    }
                }.awaitAll()
            }.flatten()
        } finally {
            barrierDispatcher.close()
            barrierPool.shutdown()
        }

        val claimedIds = claimed.map { it.id }
        assertEquals("every queued item is claimed exactly once",
            itemCount, claimedIds.size)
        assertEquals("no item is claimed by two flushers",
            claimedIds.size, claimedIds.toSet().size)
    }

    // --- Claim → InFlight → late duplicate attempt → no second delivery ----

    @Test
    fun claimedItemIsNeverReClaimedWhileInFlight_reconnectFlushRaceIsSafe() {
        val store = store()
        val item = store.enqueue("sessA", "no re-paste")

        // First flush claims it → InFlight.
        val first = store.claimNext("sessA")
        assertNotNull(first)
        assertEquals(OutboundState.InFlight, first!!.state)

        // A reconnect fires a SECOND flush while the first is still mid-paste.
        val second = store.claimNext("sessA")
        assertNull("an InFlight item must not be re-claimed by a racing flush", second)

        // A duplicate enqueue (a stray retry tap) also cannot add a copy.
        store.enqueueExisting(item)
        assertEquals(1, store.itemsFor("sessA").size)
    }

    @Test
    fun markDeliveredIsExactlyOnce_lateDuplicateAckIsNoOp() {
        val store = store()
        val item = store.enqueue("sessA", "deliver once")
        store.claimNext("sessA")

        assertTrue("first ack performs the delivery", store.markDelivered(item.id))
        assertNull("delivered item is pruned", store.item(item.id))
        // A late duplicate ack (the flapping-link replay) does nothing.
        assertFalse("second ack for the same id is a no-op", store.markDelivered(item.id))
        assertTrue(store.itemsFor("sessA").isEmpty())
    }

    @Test
    fun concurrentDeliveryAttemptsOnOneClaimedItem_onlyOneSucceeds() = runBlocking {
        val store = store()
        val item = store.enqueue("sessA", "single delivery")
        store.claimNext("sessA")

        val attempts = 24
        val successes = AtomicInteger(0)
        coroutineScope {
            (1..attempts).map {
                async(Dispatchers.Default) {
                    if (store.markDelivered(item.id)) successes.incrementAndGet()
                }
            }.awaitAll()
        }
        assertEquals("exactly one delivery transition may succeed", 1, successes.get())
        assertNull(store.item(item.id))
    }

    // --- Per-session isolation (the #899 cross-delivery guard, at unit level)

    @Test
    fun claimForSessionA_neverClaimsAnItemKeyedToSessionB() {
        val store = store()
        val a = store.enqueue("sessA", "for A")
        val b = store.enqueue("sessB", "for B")

        // Drain session A — must only ever yield A's item.
        val fromA = generateSequence { store.claimNext("sessA") }.toList()
        assertEquals(1, fromA.size)
        assertEquals(a.id, fromA.single().id)

        // B's item is untouched and still Queued.
        assertEquals(OutboundState.Queued, store.item(b.id)!!.state)
        val fromB = store.claimNext("sessB")
        assertEquals(b.id, fromB!!.id)
    }

    @Test
    fun clearSessionAndRemoveAreScopedAndDoNotBleed() {
        val store = store()
        val a = store.enqueue("sessA", "a1")
        store.enqueue("sessA", "a2")
        val b = store.enqueue("sessB", "b1")

        assertTrue(store.remove(a.id))
        assertEquals(1, store.itemsFor("sessA").size)

        store.clearSession("sessA")
        assertTrue(store.itemsFor("sessA").isEmpty())
        // Session B is completely unaffected.
        assertEquals(listOf(b), store.itemsFor("sessB"))
    }

    // --- Ordering (oldest-first) -------------------------------------------

    @Test
    fun itemsAndClaimsAreOrderedOldestFirstByCreatedAt() {
        val store = store()
        // Enqueue out of chronological order to prove ordering is by createdAtMs.
        val third = store.enqueue("sessA", "third", createdAtMs = 300)
        val first = store.enqueue("sessA", "first", createdAtMs = 100)
        val second = store.enqueue("sessA", "second", createdAtMs = 200)

        assertEquals(
            listOf(first.id, second.id, third.id),
            store.itemsFor("sessA").map { it.id },
        )
        // Claim order follows the same oldest-first discipline.
        assertEquals(first.id, store.claimNext("sessA")!!.id)
        assertEquals(second.id, store.claimNext("sessA")!!.id)
        assertEquals(third.id, store.claimNext("sessA")!!.id)
        assertNull(store.claimNext("sessA"))
    }

    // --- State-machine transitions -----------------------------------------

    @Test
    fun happyPathLifecycleQueuedClaimedInFlightDelivered() {
        val store = store()
        val item = store.enqueue("sessA", "lifecycle")
        assertEquals(OutboundState.Queued, store.item(item.id)!!.state)

        // A flush claims the queued item → InFlight (the durable in-flight guard).
        val claimed = store.claimNext("sessA")!!
        assertEquals(item.id, claimed.id)
        assertEquals(OutboundState.InFlight, claimed.state)
        assertEquals(OutboundState.InFlight, store.item(item.id)!!.state)

        // Ack confirms delivery → pruned.
        assertTrue(store.markDelivered(item.id))
        assertNull(store.item(item.id))
        assertTrue(store.itemsFor("sessA").isEmpty())
    }

    @Test
    fun markUploadingIsAPersistedTransitionAndUploadingIsNotClaimable() {
        val store = store()
        val item = store.enqueue("sessA", "uploading first")

        // Upload-in-progress is a real persisted state transition.
        assertEquals(OutboundState.Uploading, store.markUploading(item.id)!!.state)
        assertEquals(OutboundState.Uploading, store.item(item.id)!!.state)

        // An Uploading item is NOT claimable (claimNext only claims Queued), so a
        // racing flush cannot grab an item whose attachment upload is mid-flight.
        assertNull(store.claimNext("sessA"))
    }

    @Test
    fun markFailedKeepsItemVisibleAndBumpsAttemptCount() {
        val store = store()
        val item = store.enqueue("sessA", "keep me on failure")
        store.claimNext("sessA")

        val failed = store.markFailed(item.id, "ack timeout")!!
        assertEquals(OutboundState.Failed, failed.state)
        assertEquals("ack timeout", failed.lastError)
        assertEquals(1, failed.attemptCount)
        assertNotNull(failed.lastAttemptAtMs)
        // Still present (never silently dropped).
        assertEquals(1, store.itemsFor("sessA").size)

        // A second failure bumps the attempt count again.
        store.claimNext("sessA") // nothing queued now, so re-arm first
        store.enqueueExisting(store.item(item.id)!!) // Failed → Queued
        store.claimNext("sessA")
        val failedAgain = store.markFailed(item.id, "dropped")!!
        assertEquals(2, failedAgain.attemptCount)
    }

    @Test
    fun lateAckAfterFailureCannotResurrectAlreadyFailedItem() {
        // markUploading/markDelivered must not undo a terminal Failed except via
        // an explicit re-arm. markUploading on a Failed item is a no-op.
        val store = store()
        val item = store.enqueue("sessA", "x")
        store.claimNext("sessA")
        store.markFailed(item.id, "boom")
        val afterUpload = store.markUploading(item.id)
        assertEquals("markUploading must not move a Failed item",
            OutboundState.Failed, afterUpload!!.state)
    }

    // --- Durability across a simulated restart (in-memory analogue) --------
    //
    // The real cross-process durability is the SharedPreferences blob, proven
    // by encode→decode round-trip in OutboundQueueStoreEncodingTest and by the
    // Robolectric androidTest. Here we assert the *contract* a reload must
    // satisfy: state survives the round-trip with no field loss.

    @Test
    fun encodeDecodeRoundTripPreservesEveryFieldAcrossSimulatedRestart() {
        val original = listOf(
            OutboundItem(
                id = "id-1",
                sessionKey = "sessA",
                cleanText = "deploy\twith\ttabs\nand newline",
                attachments = listOf(DurableAttachmentRef("~/a.png", "a.png", "image/png")),
                withEnter = true,
                state = OutboundState.InFlight,
                createdAtMs = 1_000,
                lastAttemptAtMs = 2_000,
                attemptCount = 3,
                lastError = "ack timeout\tdetail",
            ),
            OutboundItem(
                id = "id-2",
                sessionKey = "sessA",
                cleanText = "second",
                state = OutboundState.Failed,
                withEnter = false,
                createdAtMs = 500,
                attemptCount = 1,
                lastError = null,
            ),
        )
        val reloaded = decodeOutboundItems("sessA", encodeOutboundItems(original))
        // The store always re-sorts oldest-first; compare as a set of fields.
        assertEquals(original.map { it.id }.toSet(), reloaded.map { it.id }.toSet())
        val byId = reloaded.associateBy { it.id }
        assertEquals(original[0], byId["id-1"])
        assertEquals(original[1], byId["id-2"])
    }
}
