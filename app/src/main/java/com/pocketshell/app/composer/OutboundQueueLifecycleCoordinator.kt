package com.pocketshell.app.composer

import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exact remote checkpoint/file hygiene for a discarded queue sidecar.
 *
 * The coordinator never dials SSH (D28). A live foreground transport may be
 * handed in later to retry tombstones; failure leaves the tombstone for
 * another pass and never resurrects the send.
 */
public fun interface QueueSidecarRemoteCleaner {
    public suspend fun discardCheckpoint(remotePath: String, stableToken: String)
}

/**
 * Issue #1589: binds the current foreground SSH transport to the typed, atomic
 * lifecycle owner for the real user Delete path. Direct JVM tests may omit the
 * coordinator; production Hilt supplies the singleton coordinator.
 */
public fun PromptComposerViewModel.setOutboundQueueRemoteCleaner(
    cleaner: QueueSidecarRemoteCleaner?,
) {
    outboundQueueLifecycleCoordinator?.bindRemoteCleaner(cleaner)
}

public data class OutboundDisposalResult(
    val removedRowIds: Set<String>,
    val tombstoneCount: Int,
) {
    public companion object {
        public val Empty: OutboundDisposalResult = OutboundDisposalResult(emptySet(), 0)
    }
}

/**
 * Issue #1589: the one app-scoped foreground owner for authorized queue
 * disposal and crash-safe sidecar repair. It does not claim, flush, or send
 * rows — host-CLI ack remains the delivery authority.
 *
 * [ioDispatcher] is a constructor argument (Shape A) so tests can pin every
 * owned hop to the `runTest` scheduler before `init` runs. Setting a var
 * after construction cannot cancel the real-IO startup repair that raced
 * `stage()` / `deleteRecursively()`.
 */
@Singleton
public class OutboundQueueLifecycleCoordinator(
    private val queueStore: OutboundQueueStore,
    private val sidecarStore: OutboundAttachmentSidecarStore,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    autoRepairOnInit: Boolean = true,
) {
    @Inject
    public constructor(
        queueStore: OutboundQueueStore,
        sidecarStore: OutboundAttachmentSidecarStore,
    ) : this(
        queueStore = queueStore,
        sidecarStore = sidecarStore,
        ioDispatcher = Dispatchers.IO,
        autoRepairOnInit = true,
    )

    @Volatile
    @VisibleForTesting
    internal var remoteCleaner: QueueSidecarRemoteCleaner? = null

    private val job: Job = SupervisorJob()
    private val scope = CoroutineScope(job + ioDispatcher)
    private val lock = Any()
    private val repairMutex = Mutex()

    init {
        if (autoRepairOnInit) {
            scope.launch { runCatching { repairOrphans() } }
        }
    }

    /**
     * Bind the currently connected foreground transport for exact remote
     * checkpoint cleanup. The coordinator never creates a second SSH session.
     * A failed cleanup leaves its tombstone persisted for the next retry.
     */
    public fun bindRemoteCleaner(cleaner: QueueSidecarRemoteCleaner?) {
        remoteCleaner = cleaner
        if (cleaner != null) {
            scope.launch { runCatching { retryRemoteHygiene(cleaner) } }
        }
    }

    /**
     * Durable commit: persist sidecar tombstones, then remove authorized rows.
     * Local/remote bytes are cleaned after that commit so a crash can leak
     * files but cannot destroy bytes while a deliverable row remains.
     *
     * Post-commit hygiene only touches the just-removed row ids (via
     * tombstones). A full live-id repair is [repairOrphans] and must re-read
     * live ids immediately before each sidecar delete.
     */
    internal suspend fun discardAuthorized(
        authorization: OutboundDisposalAuthorization,
        ownership: OutboundDisposalPermit,
    ): OutboundDisposalResult = withContext(ioDispatcher) {
        val authorizedRowId = when (authorization) {
            is OutboundDisposalAuthorization.ExplicitDiscard -> authorization.rowId
        }
        if (ownership.rowId != authorizedRowId || !ownership.isCurrent()) {
            return@withContext OutboundDisposalResult.Empty
        }
        val candidate = synchronized(lock) {
            when (authorization) {
                is OutboundDisposalAuthorization.ExplicitDiscard ->
                    queueStore.item(authorization.rowId)
            }
        } ?: return@withContext OutboundDisposalResult.Empty
        if (!OutboundQueueRetentionPolicy.mayDiscard(candidate, authorization)) {
            return@withContext OutboundDisposalResult.Empty
        }

        // Keep the sidecar transaction lock held through the atomic queue
        // removal. Repair and enqueue use the same ordering, so neither can
        // delete a sidecar between this snapshot and its row commit.
        val committed = sidecarStore.withSidecarLockBlocking {
                val byDisplayName = candidate.attachments
                    .mapNotNull { ref ->
                        val path = ref.remotePath.trim().takeIf { it.isNotEmpty() }
                            ?: return@mapNotNull null
                        ref.displayName.takeIf { it.isNotBlank() }?.let { it to path }
                    }
                    .toMap()
                val bySidecarId = sidecarStore.allRefsIncludingMissingBlocking()
                    .filter { it.outboundItemId == candidate.id }
                    .mapNotNull { ref ->
                        val path = ref.uploadedRemotePath
                            ?: byDisplayName[ref.displayName]
                            ?: return@mapNotNull null
                        ref.id to path
                    }
                    .toMap()
                val tombstones = sidecarStore.tombstonesForOutboundItem(candidate.id, bySidecarId)
                if (tombstones.isNotEmpty()) {
                    sidecarStore.persistTombstonesBlocking(tombstones)
                }
                // The typed drain-owner permit stays reserved until this method
                // returns, while removeIfIdle atomically arbitrates a direct
                // store claim that may have started before the reservation.
                val removed = queueStore.removeIfIdle(candidate.id)
                removed to tombstones
        }
        val removed = committed.first ?: return@withContext OutboundDisposalResult.Empty
        val committedTombstones = committed.second
        if (committedTombstones.isNotEmpty()) {
            scope.launch {
                runCatching { applyLocalTombstones(committedTombstones) }
            }
        }
        OutboundDisposalResult(
            removedRowIds = setOf(removed.id),
            tombstoneCount = committedTombstones.size,
        )
    }

    public suspend fun repairOrphans() = withContext(ioDispatcher) {
        repairMutex.withLock {
            // Re-read live ids inside the sidecar lock, immediately before each
            // delete. A snapshot taken here would let a concurrent enqueue+stage
            // on B lose B's sidecar while B's row stayed queued.
            sidecarStore.reconcileAgainstLiveRowIds(queueStore::allLiveRowIds)
            val liveRowIds = queueStore.allLiveRowIds()
            val pending = sidecarStore.pendingTombstonesBlocking().filter { tombstone ->
                tombstone.outboundItemId !in liveRowIds &&
                    !OutboundQueueRetentionPolicy.isDraftSidecarScope(tombstone.outboundItemId)
            }
            applyLocalTombstones(pending)
            remoteCleaner?.let { retryRemoteHygieneLocked(it) }
        }
    }

    public suspend fun retryRemoteHygiene(cleaner: QueueSidecarRemoteCleaner) =
        withContext(ioDispatcher) {
            repairMutex.withLock { retryRemoteHygieneLocked(cleaner) }
        }

    @VisibleForTesting
    internal fun close() {
        job.cancel()
    }

    @VisibleForTesting
    internal suspend fun closeAndJoin() {
        job.cancelAndJoin()
    }

    private suspend fun retryRemoteHygieneLocked(cleaner: QueueSidecarRemoteCleaner) {
        val liveRowIds = queueStore.allLiveRowIds()
        val pending = sidecarStore.pendingTombstonesBlocking().filter { tombstone ->
            tombstone.outboundItemId !in liveRowIds &&
                !OutboundQueueRetentionPolicy.isDraftSidecarScope(tombstone.outboundItemId)
        }
        val completed = mutableSetOf<String>()
        for (tombstone in pending) {
            val remotePath = tombstone.remotePath ?: continue
            val discarded = runCatching {
                cleaner.discardCheckpoint(remotePath, tombstone.stableToken)
            }.isSuccess
            if (discarded) completed += tombstone.sidecarId
        }
        if (completed.isNotEmpty()) {
            sidecarStore.removeTombstonesBlocking(completed)
        }
    }

    private suspend fun applyLocalTombstones(tombstones: List<SidecarCleanupTombstone>) {
        val fullyLocal = mutableSetOf<String>()
        for (tombstone in tombstones) {
            // Re-read ownership while holding the same lock as enqueue/stage.
            // A row re-created with the same durable id between the earlier
            // snapshot and cleanup must keep its sidecar bytes.
            val removed = sidecarStore.withSidecarLockBlocking {
                val live = queueStore.allLiveRowIds()
                if (
                    tombstone.outboundItemId in live ||
                    OutboundQueueRetentionPolicy.isDraftSidecarScope(tombstone.outboundItemId)
                ) {
                    false
                } else {
                    runCatching {
                        sidecarStore.removeOutboundItemLocked(tombstone.outboundItemId)
                    }.isSuccess
                }
            }
            if (removed && tombstone.remotePath.isNullOrBlank()) {
                fullyLocal += tombstone.sidecarId
            }
        }
        if (fullyLocal.isNotEmpty()) {
            sidecarStore.removeTombstonesBlocking(fullyLocal)
        }
    }
}
