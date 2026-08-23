package com.pocketshell.app.composer

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

internal data class OutboundDrainLease(
    val rowId: String,
    val token: Long,
    val acceptedByConsumer: Boolean = false,
    val purpose: OutboundDrainLeasePurpose = OutboundDrainLeasePurpose.Delivery,
)

internal enum class OutboundDrainLeasePurpose { Delivery, Disposal }

/**
 * Typed proof that the production drain owner atomically reserved [rowId] for
 * user-authorized disposal while no delivery owns the physical pipe. Keeping
 * this reservation alive prevents a reconnect/collector wake from acquiring a
 * delivery lease between the UI's Delete tap and the queue-store commit.
 */
internal class OutboundDisposalPermit internal constructor(
    internal val lease: OutboundDrainLease,
    private val owner: OutboundDrainOwnership,
) {
    val rowId: String get() = lease.rowId

    internal fun isCurrent(): Boolean = owner.ownsDisposal(this)
}

/**
 * Owns one durable outbound row during dispatch setup. Queue effects and
 * reconnect polls may trigger concurrently; only the row that atomically
 * acquires this lease may enter the send channel. Ownership spans the complete
 * physical host delivery, including a session-identity promotion. It is
 * released only by a delivered/deferred/failed terminal callback, or by the
 * cancellation/strand cleanup path. `sendInFlight` is UI state, not a durable
 * cross-identity serialization primitive.
 */
internal class OutboundDrainOwnership {
    private val nextToken = AtomicLong(0L)
    private val activeLease = AtomicReference<OutboundDrainLease?>(null)

    fun tryAcquire(rowId: String): OutboundDrainLease? {
        if (rowId.isBlank()) return null
        val lease = OutboundDrainLease(rowId = rowId, token = nextToken.incrementAndGet())
        return lease.takeIf { activeLease.compareAndSet(null, lease) }
    }

    /**
     * Refuse safely when any delivery lease is live; otherwise reserve the same
     * single-owner slot for disposal. There is deliberately no "empty owner
     * set" fallback: callers either hold this proof or they cannot delete.
     */
    fun tryAcquireDisposal(rowId: String): OutboundDisposalPermit? {
        if (rowId.isBlank()) return null
        val lease = OutboundDrainLease(
            rowId = rowId,
            token = nextToken.incrementAndGet(),
            purpose = OutboundDrainLeasePurpose.Disposal,
        )
        return if (activeLease.compareAndSet(null, lease)) {
            OutboundDisposalPermit(lease, this)
        } else {
            null
        }
    }

    fun acceptByConsumer(rowId: String?, token: Long?): Boolean {
        if (rowId == null || token == null) return false
        while (true) {
            val current = activeLease.get() ?: return false
            if (
                current.purpose != OutboundDrainLeasePurpose.Delivery ||
                current.rowId != rowId ||
                current.token != token ||
                current.acceptedByConsumer
            ) return false
            if (activeLease.compareAndSet(current, current.copy(acceptedByConsumer = true))) return true
        }
    }

    fun release(rowId: String?, token: Long?): Boolean {
        if (rowId == null || token == null) return false
        while (true) {
            val current = activeLease.get() ?: return false
            if (
                current.purpose != OutboundDrainLeasePurpose.Delivery ||
                current.rowId != rowId ||
                current.token != token
            ) return false
            if (activeLease.compareAndSet(current, null)) return true
        }
    }

    fun release(lease: OutboundDrainLease?): Boolean =
        release(lease?.rowId, lease?.token)

    fun releaseDisposal(permit: OutboundDisposalPermit): Boolean =
        activeLease.compareAndSet(permit.lease, null)

    internal fun ownsDisposal(permit: OutboundDisposalPermit): Boolean =
        permit.lease.purpose == OutboundDrainLeasePurpose.Disposal &&
            activeLease.get() === permit.lease

    fun forceRelease(): String? = activeLease.getAndSet(null)?.rowId

    fun activeRowId(): String? = activeLease.get()?.rowId
}

/**
 * Retains explicit Send-now approvals that hit a busy drain gate. The approval
 * is an intent for one durable row, not a second ownership lease: the normal
 * drain still has to acquire [OutboundDrainOwnership] and claim the row before
 * it can emit anything. A small synchronized FIFO also keeps two rapid row
 * actions from overwriting one another while a current owner resolves.
 */
internal class OutboundDrainApprovalQueue {
    private val lock = Any()
    private val pendingIds = LinkedHashSet<String>()

    fun retain(rowId: String) {
        if (rowId.isBlank()) return
        synchronized(lock) { pendingIds += rowId }
    }

    fun remove(rowId: String) {
        synchronized(lock) { pendingIds -= rowId }
    }

    fun snapshot(): List<String> = synchronized(lock) { pendingIds.toList() }
}

/**
 * Identifies the one mounted screen consumer allowed to turn a queued request
 * into physical host IO. A generation is stamped onto every request so an old
 * screen collector cannot steal work emitted for its replacement.
 */
internal class OutboundSendConsumerRegistry(
    private val onGenerationChanged: () -> Unit = {},
) {
    private val nextGeneration = AtomicLong(0L)
    private val activeGeneration = AtomicReference<Long?>(null)
    @Volatile private var registrationRequired: Boolean = false

    fun register(): Long {
        registrationRequired = true
        return nextGeneration.incrementAndGet().also {
            activeGeneration.set(it)
            onGenerationChanged()
        }
    }

    fun unregister(generation: Long): Boolean =
        activeGeneration.compareAndSet(generation, null).also { changed ->
            if (changed) onGenerationChanged()
        }

    fun activeGenerationForDispatch(): Long? = activeGeneration.get()

    fun canDispatch(): Boolean = !registrationRequired || activeGeneration.get() != null

    fun accepts(generation: Long, requestGeneration: Long?): Boolean =
        activeGeneration.get() == generation &&
            // Before the first screen registers, dispatch is intentionally
            // allowed and carries no generation. Only the active consumer may
            // adopt that bootstrap request; concrete generations stay exact so
            // a replacement cannot steal work emitted for a retired screen.
            (requestGeneration == null || requestGeneration == generation)
}
