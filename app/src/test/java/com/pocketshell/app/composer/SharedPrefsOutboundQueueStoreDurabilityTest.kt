package com.pocketshell.app.composer

import android.content.Context
import android.content.SharedPreferences
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
            paneId = "%1",
            route = OutboundRoute.AgentPayload,
            agentKind = "codex",
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
        assertEquals(1, reloaded.attemptCount)
        // A reconnect-triggered flush after restart must NOT re-claim it.
        assertNull(afterRestart.claimNext("sessA"))
    }

    @Test
    fun requeueStaleInFlightSurvivesRestartAndLeavesFreshRowsInFlight() {
        val first = newStore()
        val stale = OutboundItem(
            id = "durable-stale-in-flight",
            sessionKey = "sessA-requeue",
            cleanText = "retry after restart",
            state = OutboundState.InFlight,
            createdAtMs = 1L,
            lastAttemptAtMs = 10L,
            attemptCount = 1,
            lastError = "previous timeout",
        )
        val fresh = OutboundItem(
            id = "durable-fresh-in-flight",
            sessionKey = "sessA-requeue",
            cleanText = "still active",
            state = OutboundState.InFlight,
            createdAtMs = 2L,
            lastAttemptAtMs = 50L,
            attemptCount = 1,
        )
        val otherSession = OutboundItem(
            id = "durable-other-session",
            sessionKey = "sessB-requeue",
            cleanText = "do not touch",
            state = OutboundState.InFlight,
            createdAtMs = 1L,
            lastAttemptAtMs = 10L,
            attemptCount = 1,
        )
        val staleUpload = OutboundItem(
            id = "durable-stale-upload",
            sessionKey = "sessA-requeue",
            cleanText = "upload was interrupted",
            state = OutboundState.Uploading,
            createdAtMs = 3L,
            lastAttemptAtMs = null,
            attemptCount = 0,
        )
        val freshUpload = OutboundItem(
            id = "durable-fresh-upload",
            sessionKey = "sessA-requeue",
            cleanText = "upload still active",
            state = OutboundState.Uploading,
            createdAtMs = 50L,
            lastAttemptAtMs = null,
            attemptCount = 0,
        )
        first.enqueueExisting(stale)
        first.enqueueExisting(fresh)
        first.enqueueExisting(otherSession)
        first.enqueueExisting(staleUpload)
        first.enqueueExisting(freshUpload)

        val afterRestart = newStore()
        val requeued = afterRestart.requeueStaleInFlight("sessA-requeue", cutoffMs = 10L)
        assertEquals(listOf(stale.id, staleUpload.id), requeued.map { it.id })

        val afterRecoveryRestart = newStore()
        val recovered = afterRecoveryRestart.item(stale.id)!!
        assertEquals(OutboundState.Queued, recovered.state)
        assertEquals(1, recovered.attemptCount)
        assertEquals(10L, recovered.lastAttemptAtMs)
        assertEquals("previous timeout", recovered.lastError)
        assertEquals(OutboundState.Queued, afterRecoveryRestart.item(staleUpload.id)!!.state)
        assertEquals(0, afterRecoveryRestart.item(staleUpload.id)!!.attemptCount)
        assertEquals(OutboundState.Uploading, afterRecoveryRestart.item(freshUpload.id)!!.state)
        assertEquals(OutboundState.InFlight, afterRecoveryRestart.item(fresh.id)!!.state)
        assertEquals(OutboundState.InFlight, afterRecoveryRestart.item(otherSession.id)!!.state)

        val claimed = afterRecoveryRestart.claimNext("sessA-requeue")!!
        assertEquals(stale.id, claimed.id)
        assertEquals(OutboundState.InFlight, claimed.state)
        assertEquals(2, claimed.attemptCount)
        val claimedUpload = afterRecoveryRestart.claimNext("sessA-requeue")!!
        assertEquals(staleUpload.id, claimedUpload.id)
        assertEquals(OutboundState.InFlight, claimedUpload.state)
        assertEquals(1, claimedUpload.attemptCount)
        assertNull(afterRecoveryRestart.claimNext("sessA-requeue"))
    }

    @Test
    fun freshUploadingRetryUsesUploadTimestampForStaleRecoveryAfterRestart() {
        val first = newStore()
        val item = first.enqueue(
            sessionKey = "sess-upload-retry",
            cleanText = "retry upload",
            createdAtMs = 1L,
        )
        first.claimNext("sess-upload-retry")
        first.markFailed(item.id, "old failure", lastAttemptAtMs = 10L)
        first.markUploading(item.id, lastAttemptAtMs = 200L)

        val afterRestart = newStore()
        assertEquals(OutboundState.Uploading, afterRestart.item(item.id)!!.state)
        assertEquals(200L, afterRestart.item(item.id)!!.lastAttemptAtMs)

        assertTrue(afterRestart.requeueStaleInFlight("sess-upload-retry", cutoffMs = 100L).isEmpty())
        assertEquals(OutboundState.Uploading, afterRestart.item(item.id)!!.state)

        val requeued = afterRestart.requeueStaleInFlight("sess-upload-retry", cutoffMs = 200L)
        assertEquals(listOf(item.id), requeued.map { it.id })
        assertEquals(OutboundState.Queued, afterRestart.item(item.id)!!.state)
    }

    @Test
    fun targetedMarkInFlightSurvivesRestartWithoutClaimingOlderRows() {
        val first = newStore()
        val older = first.enqueue("sessA", "older", createdAtMs = 1L)
        val current = first.enqueue("sessA", "current", createdAtMs = 2L)

        first.markInFlight(current.id)

        val afterRestart = newStore()
        assertEquals(OutboundState.Queued, afterRestart.item(older.id)!!.state)
        val currentAfterRestart = afterRestart.item(current.id)!!
        assertEquals(OutboundState.InFlight, currentAfterRestart.state)
        assertEquals(1, currentAfterRestart.attemptCount)
        assertEquals(older.id, afterRestart.claimNext("sessA")!!.id)
        assertNull(afterRestart.claimNext("sessA"))
    }

    @Test
    fun exactClaimOfFailedItemSurvivesRestartWithoutClaimingOlderRows() {
        val first = newStore()
        val older = first.enqueue("sessA", "older", createdAtMs = 1L)
        val clicked = first.enqueue("sessA", "clicked failed", createdAtMs = 2L)
        first.claim(clicked.id)
        first.markFailed(clicked.id, "link dropped")

        val afterFailureRestart = newStore()
        val retried = afterFailureRestart.claim(clicked.id)!!
        assertEquals(clicked.id, retried.id)
        assertEquals(OutboundState.InFlight, retried.state)
        assertEquals(2, retried.attemptCount)
        assertEquals(OutboundState.Queued, afterFailureRestart.item(older.id)!!.state)

        val afterClaimRestart = newStore()
        val persistedRetry = afterClaimRestart.item(clicked.id)!!
        assertEquals(OutboundState.InFlight, persistedRetry.state)
        assertEquals(2, persistedRetry.attemptCount)
        assertEquals(older.id, afterClaimRestart.claimNext("sessA")!!.id)
        assertNull(afterClaimRestart.claim(clicked.id))
    }

    @Test
    fun uploadedAttachmentsSurviveRestartAndRemainClaimable() {
        val first = newStore()
        val item = first.enqueue(
            sessionKey = "sessA",
            cleanText = "queued attachment",
            createdAtMs = 7L,
            paneId = "%2",
            route = OutboundRoute.AgentConversation,
            agentKind = "claude",
        )
        first.markUploading(item.id)
        val attachments = listOf(
            DurableAttachmentRef(
                remotePath = "~/.pocketshell/attachments/sess/log.txt",
                displayName = "log.txt",
                mimeType = "text/plain",
            ),
        )
        first.markAttachmentsUploaded(item.id, attachments)

        val afterRestart = newStore()
        val reloaded = afterRestart.item(item.id)!!
        assertEquals(OutboundState.Queued, reloaded.state)
        assertEquals(attachments, reloaded.attachments)
        assertEquals(0, reloaded.attemptCount)
        assertEquals("%2", reloaded.paneId)
        assertEquals(OutboundRoute.AgentConversation, reloaded.route)
        assertEquals("claude", reloaded.agentKind)

        val claimed = afterRestart.claimNext("sessA")!!
        assertEquals(item.id, claimed.id)
        assertEquals(1, claimed.attemptCount)
        assertEquals(attachments, claimed.attachments)
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

    @Test
    fun paneProvenIdentityPromotionIsAtomicAndPreservesDeliveryLedgerAcrossRestart() {
        val fallback = "1944/project-a"
        val durable = "tmux:1944:\$12:1700000000"
        val store = newStore()
        val first = store.enqueue(
            fallback,
            "first",
            attachments = listOf(DurableAttachmentRef("/remote/a", "a.txt", "text/plain")),
            withEnter = false,
            createdAtMs = 10,
            paneId = "%192",
            route = OutboundRoute.AgentPayload,
            agentKind = "claude",
            sendKey = "logical-first",
            tmuxSessionId = "\$12",
            tmuxSessionCreated = 1700000000,
        )
        store.claimNext(fallback)
        store.markWireAttempted(fallback, first.id, 12, 3, 1)
        store.markFailed(first.id, "deferred", 13)
        store.requeueForRetry(first.id)
        val second = store.enqueue(fallback, "second", createdAtMs = 20, paneId = "%192", tmuxSessionId = "\$12", tmuxSessionCreated = 1700000000)
        val foreign = store.enqueue(fallback, "foreign", createdAtMs = 30, paneId = "%777")
        val expectedFirst = requireNotNull(store.item(first.id))

        assertEquals(
            listOf(first.id, second.id),
            store.promoteSessionIdentity(fallback, durable, setOf("%192"), "\$12", 1700000000).map { it.id },
        )

        val restarted = newStore()
        val rebound = restarted.itemsFor(durable)
        assertEquals(listOf(first.id, second.id), rebound.map { it.id })
        assertEquals(listOf("first", "second"), rebound.map { it.cleanText })
        assertTrue(rebound.first().wireAttempted)
        assertEquals(3, rebound.first().wireNeedleBaselineCount)
        assertEquals(expectedFirst.copy(sessionKey = durable), rebound.first())
        assertEquals("\$12", rebound.first().tmuxSessionId)
        assertEquals(1700000000L, rebound.first().tmuxSessionCreated)
        assertEquals(listOf(foreign.id), restarted.itemsFor(fallback).map { it.id })
        assertTrue(restarted.promoteSessionIdentity(fallback, durable, setOf("%192"), "\$12", 1700000000).isEmpty())
        assertEquals(first.id, restarted.claimNext(durable)?.id)
        assertEquals(second.id, restarted.claimNext(durable)?.id)
    }

    @Test
    fun submitLatchIsSynchronouslyCommittedBeforeCrashAndVisibleAfterRestart() {
        val backing = context.getSharedPreferences("issue1944-submit-latch", Context.MODE_PRIVATE)
        backing.edit().clear().commit()
        val row = SharedPrefsOutboundQueueStore(backing).enqueue("sessA", "must not submit twice")
        // Wait for the ordinary enqueue apply before opening the deliberate crash window.
        backing.edit().commit()
        val crashWindowPrefs = ApplyLosingSharedPreferences(backing)
        val store = SharedPrefsOutboundQueueStore(crashWindowPrefs)

        val latched = store.markWireSubmitAttempted("sessA", row.id)

        assertEquals("submit latch must use synchronous commit", 1, crashWindowPrefs.commitCount)
        assertEquals("submit latch must not rely on async apply", 0, crashWindowPrefs.applyCount)
        assertEquals(true, latched?.wireSubmitAttempted)
        val afterCrash = SharedPrefsOutboundQueueStore(backing)
        assertTrue(afterCrash.hasWireSubmitAttempt("sessA", row.id))
        assertTrue(
            "the exact restart-visible row carries the fail-closed submit latch",
            afterCrash.item(row.id)?.wireSubmitAttempted == true,
        )
    }
}

/** Simulates process death dropping every asynchronous apply while preserving commit writes. */
private class ApplyLosingSharedPreferences(
    private val delegate: SharedPreferences,
) : SharedPreferences by delegate {
    var applyCount: Int = 0
        private set
    var commitCount: Int = 0
        private set

    override fun edit(): SharedPreferences.Editor = Editor(delegate.edit())

    private inner class Editor(
        private val delegateEditor: SharedPreferences.Editor,
    ) : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { delegateEditor.putString(key, value) }
        override fun putStringSet(key: String?, values: MutableSet<String>?) =
            apply { delegateEditor.putStringSet(key, values) }
        override fun putInt(key: String?, value: Int) = apply { delegateEditor.putInt(key, value) }
        override fun putLong(key: String?, value: Long) = apply { delegateEditor.putLong(key, value) }
        override fun putFloat(key: String?, value: Float) = apply { delegateEditor.putFloat(key, value) }
        override fun putBoolean(key: String?, value: Boolean) = apply { delegateEditor.putBoolean(key, value) }
        override fun remove(key: String?) = apply { delegateEditor.remove(key) }
        override fun clear() = apply { delegateEditor.clear() }
        override fun commit(): Boolean {
            commitCount += 1
            return delegateEditor.commit()
        }
        override fun apply() {
            applyCount += 1
        }
    }
}
