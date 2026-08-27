package com.pocketshell.app.composer

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.tmux.SessionLifecycleSignals
import com.pocketshell.app.tmux.TmuxSessionGeneration
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
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
 * Issue #1589 regression coverage: exact explicit disposal and crash-safe
 * sidecar repair. Stop/Kill is deliberately outside this disposal authority;
 * lifecycle signals must never silently destroy user queue data.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class OutboundParkedRowGcTest {

    private lateinit var context: Context
    private val coordinators = mutableListOf<OutboundQueueLifecycleCoordinator>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("outbound_queue", Context.MODE_PRIVATE)
            .edit().clear().commit()
        context.getSharedPreferences(OutboundAttachmentSidecarStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
        File(context.filesDir, OutboundAttachmentSidecarStore.DIRECTORY_NAME).deleteRecursively()
    }

    @After
    fun tearDown() {
        coordinators.forEach { it.close() }
        coordinators.clear()
    }

    @Test
    fun stopKillSignalDoesNotDisposeQueuedSidecars() = runTest {
        val queue = SharedPrefsOutboundQueueStore(context)
        val sidecar = pinnedSidecar()
        val row = queue.enqueue("tmux:7:\$12:1700000000", "keep this prompt")
        val ref = sidecar.stage(row.id, listOf(Uri.fromFile(sourceFile("keep.txt", "bytes")))).single()

        // B1 safe fallback: lifecycle fan-out carries tree/cache identity only;
        // there is no discard-on-kill path.
        SessionLifecycleSignals(null, null).emitKilled(
            hostId = 7L,
            generation = TmuxSessionGeneration("\$12", 1700000000L),
            lastKnownName = "old-name",
        )

        assertNotNull(queue.item(row.id))
        assertEquals(listOf(ref.id), sidecar.refsFor(row.id).map { it.id })
        assertTrue(File(ref.localPath).exists())
    }

    @Test
    fun nameOnlyKillOrStaleObservationLeavesParkedRowUntouched() = runTest {
        val queue = SharedPrefsOutboundQueueStore(context)
        val row = queue.enqueue("tmux:7:\$12:1700000000", "must survive uncertainty")
        val signals = SessionLifecycleSignals(null, null)

        signals.emitKilled(hostId = 7L, generation = null, lastKnownName = "old-name")
        signals.emitStaleSession(
            hostId = 7L,
            generation = null,
            lastKnownName = "old-name",
            folderPath = "/tmp/old-name",
        )

        assertEquals(row, queue.item(row.id))
    }

    @Test
    fun repairReReadsLiveIdsRatherThanUsingAStaleSnapshot() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val sidecar = pinnedSidecar()
        val live = queue.enqueue("tmux:7:\$13:1700000001", "live B")
        val staged = sidecar.stage(
            live.id,
            listOf(Uri.fromFile(sourceFile("live.txt", "live bytes"))),
        ).single()

        var liveReads = 0
        val delayed = object : OutboundQueueStore by queue {
            override fun allLiveRowIds(): Set<String> {
                liveReads += 1
                return if (liveReads == 1) emptySet() else queue.allLiveRowIds()
            }
        }
        val coordinator = newCoordinator(delayed, sidecar)
        coordinator.repairOrphans()

        assertTrue(File(staged.localPath).exists())
        assertEquals(listOf(staged.id), sidecar.refsFor(live.id).map { it.id })
        assertTrue("must consult live ids more than once", liveReads >= 2)
    }

    @Test
    fun uploadedRemoteOrphanIsTombstonedBeforeRefAndFileDrop() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val sidecar = pinnedSidecar()
        val row = queue.enqueue("tmux:7:\$12:1700000000", "orphan")
        val ref = sidecar.stage(row.id, listOf(Uri.fromFile(sourceFile("remote.txt", "remote")))).single()
        sidecar.markUploaded(mapOf(ref.id to "~/inbox/remote.txt"))
        queue.remove(row.id)

        val coordinator = newCoordinator(queue, sidecar)
        coordinator.repairOrphans()

        assertTrue(sidecar.refsFor(row.id).isEmpty())
        assertFalse(File(ref.localPath).exists())
        assertEquals(
            "remote evidence must survive ref removal for a later connected retry",
            "~/inbox/remote.txt",
            sidecar.pendingTombstonesBlocking().single { it.sidecarId == ref.id }.remotePath,
        )
    }

    @Test
    fun explicitDeleteTombstoneSurvivesRowRemovalAndRemoteRetry() = runTest {
        val queue = SharedPrefsOutboundQueueStore(context)
        val sidecar = pinnedSidecar()
        val coordinator = newCoordinator(queue, sidecar)
        val row = queue.enqueue(
            sessionKey = "tmux:7:\$12:1700000000",
            cleanText = "attach",
            attachments = listOf(DurableAttachmentRef("~/inbox/notes.txt", "notes.txt", "text/plain")),
        )
        val ref = sidecar.stage(row.id, listOf(Uri.fromFile(sourceFile("notes.txt", "notes")))).single()
        sidecar.markUploaded(mapOf(ref.id to "~/inbox/notes.txt"))

        val result = coordinator.discardWithOwnership(row.id)
        assertEquals(setOf(row.id), result.removedRowIds)
        assertTrue(result.tombstoneCount >= 1)
        assertNull(queue.item(row.id))
        advanceUntilIdle()
        assertTrue(sidecar.refsFor(row.id).isEmpty())
        assertFalse(File(ref.localPath).exists())
        assertTrue(sidecar.pendingTombstonesBlocking().any { it.sidecarId == ref.id })

        val cleaned = mutableListOf<Pair<String, String>>()
        coordinator.bindRemoteCleaner { remotePath, token -> cleaned += remotePath to token }
        coordinator.repairOrphans()

        assertEquals(listOf("~/inbox/notes.txt" to ref.id), cleaned)
        assertTrue(sidecar.pendingTombstonesBlocking().none { it.sidecarId == ref.id })
    }

    @Test
    fun remoteCleanupCannotTouchLiveRowToken() = runTest {
        val queue = InMemoryOutboundQueueStore()
        val sidecar = pinnedSidecar()
        val dead = queue.enqueue("tmux:7:\$12:1700000000", "dead")
        val live = queue.enqueue("tmux:7:\$13:1700000001", "live")
        val deadRef = sidecar.stage(dead.id, listOf(Uri.fromFile(sourceFile("dead.txt", "d")))).single()
        val liveRef = sidecar.stage(live.id, listOf(Uri.fromFile(sourceFile("live.txt", "l")))).single()
        sidecar.markUploaded(
            mapOf(deadRef.id to "~/inbox/dead.txt", liveRef.id to "~/inbox/live.txt"),
        )
        queue.remove(dead.id)

        val seen = mutableListOf<String>()
        val coordinator = newCoordinator(queue, sidecar)
        coordinator.bindRemoteCleaner { _, token -> seen += token }
        coordinator.repairOrphans()

        assertEquals(setOf(deadRef.id), seen.toSet())
        assertFalse(liveRef.id in seen)
        assertEquals(listOf(liveRef.id), sidecar.refsFor(live.id).map { it.id })
    }

    @Test
    fun removeIfIdleRefusesClaimThatWinsTheInterleaving() = runTest {
        val delegate = InMemoryOutboundQueueStore()
        val row = delegate.enqueue("tmux:7:\$12:1700000000", "claim race")
        val queue = object : OutboundQueueStore by delegate {
            override fun removeIfIdle(id: String): OutboundItem? {
                // Simulate the drain winning immediately before the store's
                // atomic removal check. The active row must remain recoverable.
                delegate.claim(id)
                return delegate.removeIfIdle(id)
            }
        }
        val coordinator = newCoordinator(queue, pinnedSidecar())

        val result = coordinator.discardWithOwnership(row.id)

        assertEquals(OutboundDisposalResult.Empty, result)
        assertEquals(OutboundState.InFlight, delegate.item(row.id)?.state)
    }

    @Test
    fun explicitDiscardBlockingPrefsRunOnCoordinatorIoDispatcher() = runTest {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            // kotlinx-coroutines-debug temporarily appends this coroutine id to
            // the physical worker name while the blocking operation runs. Keep
            // that valid observed variant deterministic without depending on a
            // process-wide debug property or agent.
            Thread(runnable, "issue-1589-queue-io @coroutine#7")
        }
        val dispatcher = executor.asCoroutineDispatcher()
        try {
            val queue = InMemoryOutboundQueueStore()
            val sidecar = OutboundAttachmentSidecarStore(context).also { it.ioDispatcher = dispatcher }
            val coordinator = OutboundQueueLifecycleCoordinator(
                queueStore = queue,
                sidecarStore = sidecar,
                ioDispatcher = dispatcher,
                autoRepairOnInit = false,
            ).also { coordinators += it }
            val row = queue.enqueue("tmux:7:\$12:1700000000", "thread check")
            sidecar.stage(row.id, listOf(Uri.fromFile(sourceFile("thread.txt", "bytes"))))

            coordinator.discardWithOwnership(row.id)

            val blockingThreadName = sidecar.lastBlockingAccessThreadNameForTest.orEmpty()
            assertTrue(
                "expected coordinator IO thread name, optionally followed by the " +
                    "kotlinx coroutine-debug suffix, but was <$blockingThreadName>",
                blockingThreadName.matches(Regex("""issue-1589-queue-io(?: @coroutine#\d+)?""")),
            )
        } finally {
            dispatcher.close()
        }
    }

    @Test
    fun coordinatorNeverClaimsOrDeliversRows() = runTest {
        val queue = CountingClaimStore()
        val coordinator = newCoordinator(queue, pinnedSidecar())
        val row = queue.enqueue("tmux:7:\$12:1700000000", "must not send")

        coordinator.discardWithOwnership(row.id)

        assertEquals(0, queue.claimCalls)
        assertEquals(0, queue.deliverCalls)
    }

    private fun TestScope.pinnedSidecar(): OutboundAttachmentSidecarStore =
        OutboundAttachmentSidecarStore(context).also {
            it.ioDispatcher = StandardTestDispatcher(testScheduler)
        }

    private fun TestScope.newCoordinator(
        queue: OutboundQueueStore,
        sidecar: OutboundAttachmentSidecarStore,
    ): OutboundQueueLifecycleCoordinator =
        OutboundQueueLifecycleCoordinator(
            queueStore = queue,
            sidecarStore = sidecar,
            ioDispatcher = StandardTestDispatcher(testScheduler),
            autoRepairOnInit = false,
        ).also { coordinators += it }

    private suspend fun OutboundQueueLifecycleCoordinator.discardWithOwnership(
        rowId: String,
    ): OutboundDisposalResult {
        val owner = OutboundDrainOwnership()
        val permit = requireNotNull(owner.tryAcquireDisposal(rowId))
        return try {
            discardAuthorized(OutboundDisposalAuthorization.ExplicitDiscard(rowId), permit)
        } finally {
            assertTrue(owner.releaseDisposal(permit))
        }
    }

    private fun sourceFile(name: String, content: String): File =
        File(context.cacheDir, name).apply {
            parentFile?.mkdirs()
            writeText(content)
        }

    private class CountingClaimStore(
        private val delegate: InMemoryOutboundQueueStore = InMemoryOutboundQueueStore(),
    ) : OutboundQueueStore by delegate {
        var claimCalls: Int = 0
        var deliverCalls: Int = 0

        override fun claimNext(sessionKey: String, nowMillis: Long): OutboundItem? {
            claimCalls += 1
            return delegate.claimNext(sessionKey, nowMillis)
        }

        override fun claim(id: String, nowMillis: Long): OutboundItem? {
            claimCalls += 1
            return delegate.claim(id, nowMillis)
        }

        override fun markDelivered(id: String): Boolean {
            deliverCalls += 1
            return delegate.markDelivered(id)
        }
    }
}
