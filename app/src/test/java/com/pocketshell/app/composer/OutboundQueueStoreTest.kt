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
    fun enqueuePersistsRouteMetadata() {
        val store = store()
        val item = store.enqueue(
            sessionKey = "sessA",
            cleanText = "agent payload",
            createdAtMs = 123,
            paneId = "%1",
            route = OutboundRoute.AgentPayload,
            agentKind = "codex",
        )

        assertEquals("%1", item.paneId)
        assertEquals(OutboundRoute.AgentPayload, item.route)
        assertEquals("codex", item.agentKind)
        assertEquals(item, store.item(item.id))
    }

    @Test
    fun twoDistinctContentEnqueuesGetDistinctIdsAndAreTwoItems() {
        val store = store()
        // Issue #961: distinct LOGICAL prompts (distinct sendKey) still mint
        // distinct rows — the coalesce only collapses a re-send of the SAME
        // logical prompt while its row is still un-delivered.
        val a = store.enqueue("sessA", "one", sendKey = "key-one")
        val b = store.enqueue("sessA", "two", sendKey = "key-two")
        assertFalse("distinct taps mint distinct ids", a.id == b.id)
        assertEquals(2, store.itemsFor("sessA").size)
    }

    @Test
    fun enqueueOfEmptySendKeyNeverCoalesces_eachIsItsOwnRow() {
        val store = store()
        // Issue #961: an empty sendKey is the legacy / no-logical-key path — it
        // must NEVER coalesce, so two same-content empty-key enqueues stay two
        // rows (the store contract for callers that do not supply a key).
        val a = store.enqueue("sessA", "same text")
        val b = store.enqueue("sessA", "same text")
        assertFalse("empty sendKey must not coalesce", a.id == b.id)
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

    // --- Issue #961: logical-send coalesce-on-enqueue ----------------------

    @Test
    fun enqueueOfSameSendKeyWhilePendingCoalescesToOneRow() {
        val store = store()
        // First Send: a Queued row with a logical key.
        val first = store.enqueue("sessA", "deploy now", sendKey = "logical-1")

        // A second Send of the SAME logical prompt while the first is still
        // un-delivered must COALESCE to the existing row, not mint a second.
        val second = store.enqueue("sessA", "deploy now", sendKey = "logical-1")
        assertEquals("same-sendKey re-send must coalesce to the SAME row", first.id, second.id)
        assertEquals("coalesce must not add a second row", 1, store.itemsFor("sessA").size)
    }

    @Test
    fun enqueueOfSameSendKeyAfterFailureReArmsTheSameRow_theDropReconnectCase() {
        val store = store()
        // The reported scenario, at the store level: row A enqueued + claimed +
        // failed (drop). It is left Failed, still queued/retryable.
        val first = store.enqueue("sessA", "deploy now", sendKey = "logical-1")
        store.claimNext("sessA")
        store.markFailed(first.id, "link dropped")
        assertEquals(OutboundState.Failed, store.item(first.id)!!.state)

        // The user re-Sends the restored draft (NEW enqueue, same logical key).
        // Today this minted a second deliverable row → double-send on flush.
        // It must instead RE-ARM the existing Failed row back to Queued.
        val second = store.enqueue("sessA", "deploy now", sendKey = "logical-1")
        assertEquals("re-Send must coalesce onto the existing row", first.id, second.id)
        assertEquals("must be exactly ONE row, not two", 1, store.itemsFor("sessA").size)
        assertEquals(OutboundState.Queued, store.item(first.id)!!.state)

        // Reconnect auto-flush: exactly one Queued row → exactly one delivery.
        val claimed = store.claimNext("sessA")
        assertEquals(first.id, claimed!!.id)
        assertTrue(store.markDelivered(first.id))
        assertNull("no second row to flush after the one delivery", store.claimNext("sessA"))
        assertEquals(0, store.itemsFor("sessA").size)
    }

    @Test
    fun enqueueOfDifferentSendKeyStillMintsASecondRow() {
        val store = store()
        store.enqueue("sessA", "deploy now", sendKey = "logical-1")
        // A genuinely-different prompt (different key) must still make a row.
        val other = store.enqueue("sessA", "rollback", sendKey = "logical-2")
        assertEquals(2, store.itemsFor("sessA").size)
        assertEquals(OutboundState.Queued, store.item(other.id)!!.state)
    }

    @Test
    fun enqueueOfSameSendKeyAfterDeliveryMintsAFreshRow() {
        val store = store()
        val first = store.enqueue("sessA", "deploy now", sendKey = "logical-1")
        store.claimNext("sessA")
        // Deliver + prune the first logical send.
        assertTrue(store.markDelivered(first.id))
        assertEquals(0, store.itemsFor("sessA").size)

        // An intentional identical send AFTER delivery is a brand-new prompt —
        // the delivered row was pruned, so there is nothing to coalesce into.
        val second = store.enqueue("sessA", "deploy now", sendKey = "logical-1")
        assertFalse("a post-delivery identical send is a fresh row", first.id == second.id)
        assertEquals(1, store.itemsFor("sessA").size)
    }

    @Test
    fun enqueueExistingWithFreshIdCoalescesOnSendKey_theSidecarPath() {
        val store = store()
        // The sidecar/attachment path mints a NEW id on every send and calls
        // enqueueExisting, so the id branch never fires — coalesce on sendKey.
        val first = OutboundItem(
            id = "id-A",
            sessionKey = "sessA",
            cleanText = "with file",
            createdAtMs = 1L,
            sendKey = "logical-att",
        )
        store.enqueueExisting(first)
        store.claimNext("sessA")
        store.markFailed("id-A", "drop")

        val second = OutboundItem(
            id = "id-B", // brand-new id, same logical prompt
            sessionKey = "sessA",
            cleanText = "with file",
            createdAtMs = 2L,
            sendKey = "logical-att",
        )
        val coalesced = store.enqueueExisting(second)
        assertEquals("fresh-id re-send must coalesce onto the existing row", "id-A", coalesced.id)
        assertEquals(1, store.itemsFor("sessA").size)
        assertEquals(OutboundState.Queued, store.item("id-A")!!.state)
        assertNull("the throwaway id must not have been stored", store.item("id-B"))
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
    fun requeueStaleInFlightOnlyForSessionUsingLastAttemptOrCreatedCutoff() {
        val store = store()
        val staleByLastAttempt = OutboundItem(
            id = "stale-by-last-attempt",
            sessionKey = "sessA",
            cleanText = "stale attempt",
            state = OutboundState.InFlight,
            createdAtMs = 50L,
            lastAttemptAtMs = 90L,
            attemptCount = 2,
            lastError = "prior failure",
        )
        val freshByLastAttempt = OutboundItem(
            id = "fresh-by-last-attempt",
            sessionKey = "sessA",
            cleanText = "fresh attempt",
            state = OutboundState.InFlight,
            createdAtMs = 10L,
            lastAttemptAtMs = 110L,
            attemptCount = 1,
        )
        val staleByCreatedAtFallback = OutboundItem(
            id = "stale-by-created-at",
            sessionKey = "sessA",
            cleanText = "legacy stale attempt",
            state = OutboundState.InFlight,
            createdAtMs = 80L,
            lastAttemptAtMs = null,
            attemptCount = 1,
        )
        val freshByCreatedAtFallback = OutboundItem(
            id = "fresh-by-created-at",
            sessionKey = "sessA",
            cleanText = "legacy fresh attempt",
            state = OutboundState.InFlight,
            createdAtMs = 120L,
            lastAttemptAtMs = null,
            attemptCount = 1,
        )
        val otherSession = OutboundItem(
            id = "other-session",
            sessionKey = "sessB",
            cleanText = "do not touch",
            state = OutboundState.InFlight,
            createdAtMs = 1L,
            lastAttemptAtMs = 1L,
            attemptCount = 1,
        )
        listOf(
            staleByLastAttempt,
            freshByLastAttempt,
            staleByCreatedAtFallback,
            freshByCreatedAtFallback,
            otherSession,
        ).forEach(store::enqueueExisting)
        val queued = store.enqueue("sessA", "already queued", createdAtMs = 70L)

        val requeued = store.requeueStaleInFlight("sessA", cutoffMs = 100L)

        assertEquals(
            listOf(staleByLastAttempt.id, staleByCreatedAtFallback.id),
            requeued.map { it.id },
        )
        assertEquals(OutboundState.Queued, store.item(staleByLastAttempt.id)!!.state)
        assertEquals(2, store.item(staleByLastAttempt.id)!!.attemptCount)
        assertEquals(90L, store.item(staleByLastAttempt.id)!!.lastAttemptAtMs)
        assertEquals("prior failure", store.item(staleByLastAttempt.id)!!.lastError)
        assertEquals(OutboundState.InFlight, store.item(freshByLastAttempt.id)!!.state)
        assertEquals(OutboundState.Queued, store.item(staleByCreatedAtFallback.id)!!.state)
        assertEquals(OutboundState.InFlight, store.item(freshByCreatedAtFallback.id)!!.state)
        assertEquals(OutboundState.InFlight, store.item(otherSession.id)!!.state)
        assertEquals(OutboundState.Queued, store.item(queued.id)!!.state)
    }

    @Test
    fun requeuedStaleInFlightRowsAreClaimableAgainInOldestFirstOrder() {
        val store = store()
        val first = OutboundItem(
            id = "first",
            sessionKey = "sessA",
            cleanText = "first",
            state = OutboundState.InFlight,
            createdAtMs = 1L,
            lastAttemptAtMs = 10L,
            attemptCount = 1,
        )
        val second = OutboundItem(
            id = "second",
            sessionKey = "sessA",
            cleanText = "second",
            state = OutboundState.InFlight,
            createdAtMs = 2L,
            lastAttemptAtMs = 10L,
            attemptCount = 1,
        )
        store.enqueueExisting(second)
        store.enqueueExisting(first)

        store.requeueStaleInFlight("sessA", cutoffMs = 10L)

        val claimedFirst = store.claimNext("sessA")!!
        val claimedSecond = store.claimNext("sessA")!!
        assertEquals(first.id, claimedFirst.id)
        assertEquals(2, claimedFirst.attemptCount)
        assertEquals(second.id, claimedSecond.id)
        assertEquals(2, claimedSecond.attemptCount)
        assertNull(store.claimNext("sessA"))
    }

    @Test
    fun requeueStaleInFlightAlsoRequeuesAbandonedUploadingRows() {
        val store = store()
        val staleUpload = OutboundItem(
            id = "stale-upload",
            sessionKey = "sessA",
            cleanText = "upload died",
            state = OutboundState.Uploading,
            createdAtMs = 10L,
            lastAttemptAtMs = null,
            attemptCount = 0,
        )
        val freshUpload = OutboundItem(
            id = "fresh-upload",
            sessionKey = "sessA",
            cleanText = "upload still active",
            state = OutboundState.Uploading,
            createdAtMs = 200L,
            lastAttemptAtMs = null,
            attemptCount = 0,
        )
        store.enqueueExisting(staleUpload)
        store.enqueueExisting(freshUpload)

        val requeued = store.requeueStaleInFlight("sessA", cutoffMs = 100L)

        assertEquals(listOf(staleUpload.id), requeued.map { it.id })
        assertEquals(OutboundState.Queued, store.item(staleUpload.id)!!.state)
        assertEquals(0, store.item(staleUpload.id)!!.attemptCount)
        assertEquals(OutboundState.Uploading, store.item(freshUpload.id)!!.state)
        val claimed = store.claimNext("sessA")!!
        assertEquals(staleUpload.id, claimed.id)
        assertEquals(1, claimed.attemptCount)
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
        assertEquals(1, claimed.attemptCount)
        assertEquals(OutboundState.InFlight, store.item(item.id)!!.state)

        // Ack confirms delivery → pruned.
        assertTrue(store.markDelivered(item.id))
        assertNull(store.item(item.id))
        assertTrue(store.itemsFor("sessA").isEmpty())
    }

    @Test
    fun markInFlightTargetsExactItemWithoutClaimingOlderQueuedRows() {
        val store = store()
        val older = store.enqueue("sessA", "older", createdAtMs = 1L)
        val current = store.enqueue("sessA", "current", createdAtMs = 2L)

        val marked = store.markInFlight(current.id)!!

        assertEquals(current.id, marked.id)
        assertEquals(OutboundState.InFlight, marked.state)
        assertEquals(1, marked.attemptCount)
        assertEquals(OutboundState.Queued, store.item(older.id)!!.state)
        assertEquals(older.id, store.claimNext("sessA")!!.id)
        assertNull(store.claimNext("sessA"))
    }

    @Test
    fun claimTargetsExactQueuedItemWithoutClaimingOlderQueuedRows() {
        val store = store()
        val older = store.enqueue("sessA", "older", createdAtMs = 1L)
        val clicked = store.enqueue("sessA", "clicked", createdAtMs = 2L)

        val claimed = store.claim(clicked.id)!!

        assertEquals(clicked.id, claimed.id)
        assertEquals(OutboundState.InFlight, claimed.state)
        assertEquals(1, claimed.attemptCount)
        assertEquals(OutboundState.Queued, store.item(older.id)!!.state)
        assertEquals(older.id, store.claimNext("sessA")!!.id)
        assertNull(store.claimNext("sessA"))
    }

    @Test
    fun claimTargetsExactFailedItemWithoutClaimingOlderQueuedRows() {
        val store = store()
        val older = store.enqueue("sessA", "older", createdAtMs = 1L)
        val clicked = store.enqueue("sessA", "clicked", createdAtMs = 2L)
        store.claim(clicked.id)
        val failed = store.markFailed(clicked.id, "lost")!!
        assertEquals(1, failed.attemptCount)

        val retry = store.claim(clicked.id)!!

        assertEquals(clicked.id, retry.id)
        assertEquals(OutboundState.InFlight, retry.state)
        assertEquals(2, retry.attemptCount)
        assertEquals(OutboundState.Queued, store.item(older.id)!!.state)
        assertEquals(older.id, store.claimNext("sessA")!!.id)
        assertNull(store.claimNext("sessA"))
    }

    @Test
    fun claimReturnsNullForUnknownDeliveredUploadingAndInFlightItems() {
        val store = store()
        assertNull(store.claim("missing"))

        val delivered = store.enqueue("sessA", "delivered")
        store.claim(delivered.id)
        store.markDelivered(delivered.id)
        assertNull(store.claim(delivered.id))

        val uploading = store.enqueue("sessA", "uploading")
        store.markUploading(uploading.id)
        assertNull(store.claim(uploading.id))

        val inFlight = store.enqueue("sessA", "in flight")
        store.claim(inFlight.id)
        assertNull(store.claim(inFlight.id))
    }

    @Test
    fun markUploadingIsAPersistedTransitionAndUploadingIsNotClaimable() {
        val store = store()
        val item = store.enqueue("sessA", "uploading first")

        // Upload-in-progress is a real persisted state transition.
        val uploading = store.markUploading(item.id, lastAttemptAtMs = 42L)!!
        assertEquals(OutboundState.Uploading, uploading.state)
        assertEquals(42L, uploading.lastAttemptAtMs)
        assertEquals(OutboundState.Uploading, store.item(item.id)!!.state)

        // An Uploading item is NOT claimable (claimNext only claims Queued), so a
        // racing flush cannot grab an item whose attachment upload is mid-flight.
        assertNull(store.claimNext("sessA"))
        assertNull(store.markUploading(item.id, lastAttemptAtMs = 43L))
    }

    @Test
    fun markAttachmentsUploadedUpdatesRefsAndRearmsWithoutBumpingAttemptCount() {
        val store = store()
        val item = store.enqueue(
            sessionKey = "sessA",
            cleanText = "send with attachment",
            createdAtMs = 10L,
            paneId = "%1",
            route = OutboundRoute.AgentPayload,
            agentKind = "codex",
        )
        store.markUploading(item.id)

        val attachments = listOf(
            DurableAttachmentRef(
                remotePath = "~/.pocketshell/attachments/sess/report.txt",
                displayName = "report.txt",
                mimeType = "text/plain",
            ),
        )
        val updated = store.markAttachmentsUploaded(item.id, attachments)!!

        assertEquals(OutboundState.Queued, updated.state)
        assertEquals(attachments, updated.attachments)
        assertEquals(0, updated.attemptCount)
        assertEquals("%1", updated.paneId)
        assertEquals(OutboundRoute.AgentPayload, updated.route)
        assertEquals("codex", updated.agentKind)

        val claimed = store.claimNext("sessA")!!
        assertEquals(item.id, claimed.id)
        assertEquals(1, claimed.attemptCount)
        assertEquals(attachments, claimed.attachments)
    }

    @Test
    fun lateAttachmentUploadCannotRewriteInFlightFailedOrDeliveredRows() {
        val store = store()
        val attachments = listOf(DurableAttachmentRef("~/late.txt", "late.txt", "text/plain"))

        val inFlight = store.enqueue("sessA", "in flight")
        store.claimNext("sessA")
        val inFlightAfterLateUpload = store.markAttachmentsUploaded(inFlight.id, attachments)!!
        assertEquals(OutboundState.InFlight, inFlightAfterLateUpload.state)
        assertTrue(inFlightAfterLateUpload.attachments.isEmpty())

        val failed = store.enqueue("sessA", "failed")
        store.markFailed(failed.id, "upload failed")
        val failedAfterLateUpload = store.markAttachmentsUploaded(failed.id, attachments)!!
        assertEquals(OutboundState.Failed, failedAfterLateUpload.state)
        assertEquals("upload failed", failedAfterLateUpload.lastError)
        assertTrue(failedAfterLateUpload.attachments.isEmpty())

        val delivered = store.enqueue("sessA", "delivered")
        store.markDelivered(delivered.id)
        assertNull(store.markAttachmentsUploaded(delivered.id, attachments))
    }

    @Test
    fun markFailedKeepsItemVisibleAndPreservesClaimAttemptCount() {
        val store = store()
        val item = store.enqueue("sessA", "keep me on failure")
        assertEquals(1, store.claimNext("sessA")!!.attemptCount)

        val failed = store.markFailed(item.id, "ack timeout")!!
        assertEquals(OutboundState.Failed, failed.state)
        assertEquals("ack timeout", failed.lastError)
        assertEquals(1, failed.attemptCount)
        assertNotNull(failed.lastAttemptAtMs)
        // Still present (never silently dropped).
        assertEquals(1, store.itemsFor("sessA").size)

        // A second claimed attempt bumps the attempt count again; recording the
        // failure does not double-count that same attempt.
        store.claimNext("sessA") // nothing queued now, so re-arm first
        store.enqueueExisting(store.item(item.id)!!) // Failed → Queued
        assertEquals(2, store.claimNext("sessA")!!.attemptCount)
        val failedAgain = store.markFailed(item.id, "dropped")!!
        assertEquals(2, failedAgain.attemptCount)
    }

    @Test
    fun markUploadingCanOwnFailedRetryButNotActiveRows() {
        val store = store()
        val item = store.enqueue("sessA", "x")
        store.claimNext("sessA")
        store.markFailed(item.id, "boom")

        val afterUpload = store.markUploading(item.id, lastAttemptAtMs = 100L)!!
        assertEquals(OutboundState.Uploading, afterUpload.state)
        assertEquals(100L, afterUpload.lastAttemptAtMs)
        assertNull(store.markUploading(item.id, lastAttemptAtMs = 101L))
        assertNull(store.claim(item.id))
    }

    @Test
    fun requeueStaleInFlightDoesNotRequeueFreshUploadingRowWithOldFailureTimestamp() {
        val store = store()
        val item = store.enqueue("sessA", "retry upload", createdAtMs = 1L)
        store.claimNext("sessA")
        store.markFailed(item.id, "old failure", lastAttemptAtMs = 10L)

        val uploading = store.markUploading(item.id, lastAttemptAtMs = 200L)!!
        assertEquals(OutboundState.Uploading, uploading.state)
        assertEquals(200L, uploading.lastAttemptAtMs)

        assertTrue(store.requeueStaleInFlight("sessA", cutoffMs = 100L).isEmpty())
        assertEquals(OutboundState.Uploading, store.item(item.id)!!.state)

        val requeued = store.requeueStaleInFlight("sessA", cutoffMs = 200L)
        assertEquals(listOf(item.id), requeued.map { it.id })
        assertEquals(OutboundState.Queued, store.item(item.id)!!.state)
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
                paneId = "%0",
                route = OutboundRoute.AgentConversation,
                agentKind = "claude",
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
                paneId = "%1",
                route = OutboundRoute.RawBytes,
                agentKind = null,
            ),
        )
        val reloaded = decodeOutboundItems("sessA", encodeOutboundItems(original))
        // The store always re-sorts oldest-first; compare as a set of fields.
        assertEquals(original.map { it.id }.toSet(), reloaded.map { it.id }.toSet())
        val byId = reloaded.associateBy { it.id }
        assertEquals(original[0], byId["id-1"])
        assertEquals(original[1], byId["id-2"])
    }

    @Test
    fun decodeLegacyRowsDefaultsRouteMetadataSafely() {
        // Old rows ended at attachmentsBlob and had no paneId/route/agentKind.
        val raw = "legacy-id\tlegacy text\t1\tQueued\t123\t\t0\t\t"
        val decoded = decodeOutboundItems("sessA", raw).single()

        assertEquals("legacy-id", decoded.id)
        assertEquals("", decoded.paneId)
        assertEquals(OutboundRoute.RawBytes, decoded.route)
        assertNull(decoded.agentKind)
    }
}
