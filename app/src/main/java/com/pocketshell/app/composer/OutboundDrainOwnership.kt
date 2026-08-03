package com.pocketshell.app.composer

import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicLong

internal data class OutboundDrainLease(
    val rowId: String,
    val token: Long,
    val acceptedByConsumer: Boolean = false,
)

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

    fun acceptByConsumer(rowId: String?, token: Long?): Boolean {
        if (rowId == null || token == null) return false
        while (true) {
            val current = activeLease.get() ?: return false
            if (current.rowId != rowId || current.token != token || current.acceptedByConsumer) return false
            if (activeLease.compareAndSet(current, current.copy(acceptedByConsumer = true))) return true
        }
    }

    fun release(rowId: String?, token: Long?): Boolean {
        if (rowId == null || token == null) return false
        while (true) {
            val current = activeLease.get() ?: return false
            if (current.rowId != rowId || current.token != token) return false
            if (activeLease.compareAndSet(current, null)) return true
        }
    }

    fun release(lease: OutboundDrainLease?): Boolean =
        release(lease?.rowId, lease?.token)

    fun forceRelease(): String? = activeLease.getAndSet(null)?.rowId

    fun activeRowId(): String? = activeLease.get()?.rowId
}

/**
 * Identifies the one mounted screen consumer allowed to turn a queued request
 * into physical host IO. A generation is stamped onto every request so an old
 * screen collector cannot steal work emitted for its replacement.
 */
internal class OutboundSendConsumerRegistry {
    private val nextGeneration = AtomicLong(0L)
    private val activeGeneration = AtomicReference<Long?>(null)
    @Volatile private var registrationRequired: Boolean = false

    fun register(): Long {
        registrationRequired = true
        return nextGeneration.incrementAndGet().also(activeGeneration::set)
    }

    fun unregister(generation: Long): Boolean =
        activeGeneration.compareAndSet(generation, null)

    fun activeGenerationForDispatch(): Long? = activeGeneration.get()

    fun canDispatch(): Boolean = !registrationRequired || activeGeneration.get() != null

    fun accepts(generation: Long, requestGeneration: Long?): Boolean =
        requestGeneration == generation && activeGeneration.get() == generation
}
