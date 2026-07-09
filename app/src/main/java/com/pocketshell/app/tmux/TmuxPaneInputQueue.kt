package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.DiagnosticEvents
import kotlinx.coroutines.channels.Channel
import java.io.IOException
import java.io.OutputStream

internal class TmuxPaneInputStream(
    private val paneId: String,
    private val queue: TmuxPaneInputQueue,
) : OutputStream() {
    override fun write(b: Int) = write(byteArrayOf(b.toByte()))

    override fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (length <= 0) return
        try {
            queue.write(buffer, offset, length)
        } catch (overflow: TmuxPaneInputQueueOverflowException) {
            DiagnosticEvents.record(
                "connection", "pane_input_write_dropped", "pane" to paneId,
                "bytes" to length, "reason" to "queue_full", "message" to overflow.message,
            )
        }
    }

    override fun close() = queue.close()
}

internal data class TmuxInputStressMetrics(
    val totalEnqueuedBytes: Long,
    val totalSentBytes: Long,
    val maxPendingBytes: Int,
    val maxPendingChunks: Int,
    val maxBatchBytes: Int,
    val maxBatchChunks: Int,
    val sentBatchCount: Long,
    val maxSendLatencyMs: Double,
)

internal data class TmuxPaneInputSegment(
    val bytes: ByteArray,
    val enqueuedAtNs: Long,
)

internal data class TmuxPaneInputBatch(
    val bytes: ByteArray,
    val chunks: Int,
    val firstEnqueuedAtNs: Long,
)

internal class TmuxPaneInputQueue(
    private val maxPendingBytes: Int,
    private val maxBatchBytes: Int,
) {
    private val signal = Channel<Unit>(capacity = 1)
    private val lock = Any()
    private val pending = ArrayDeque<TmuxPaneInputSegment>()
    private var pendingBytes: Int = 0
    private var totalEnqueuedBytes: Long = 0L
    private var totalSentBytes: Long = 0L
    private var maxObservedPendingBytes: Int = 0
    private var maxObservedPendingChunks: Int = 0
    private var maxObservedBatchBytes: Int = 0
    private var maxObservedBatchChunks: Int = 0
    private var sentBatchCount: Long = 0L
    private var maxObservedSendLatencyNs: Long = 0L
    private var closed: Boolean = false

    fun write(buffer: ByteArray, offset: Int, length: Int) {
        if (offset < 0 || length < 0 || length > buffer.size - offset) {
            throw IndexOutOfBoundsException("offset=$offset length=$length size=${buffer.size}")
        }
        var written = 0
        while (written < length) {
            val chunkLength = minOf(TMUX_INPUT_CHUNK_BYTES, length - written)
            val copy = buffer.copyOfRange(offset + written, offset + written + chunkLength)
            enqueue(copy)
            written += chunkLength
        }
    }

    suspend fun takeBatch(): TmuxPaneInputBatch? {
        // Issue #1043: `signal` (capacity 1) only NOTIFIES that work may be
        // pending; it can go STALE relative to `pending` because the drain loop
        // below greedily consumes segments whose triggering `Unit` is still
        // buffered. Receiving such a stale signal finds an EMPTY `pending` — that
        // is NOT end-of-stream. Returning `null` here would be indistinguishable
        // from the only legitimate `null` (the channel was [close]d), and the
        // pane input bridge loop (`while (true) { takeBatch() ?: break }`) would
        // BREAK on it, killing the pane's input pump for good (typed input stops
        // reaching the pane — the #1043 / post-#1041 dogfood symptom). So a
        // wakeup on a transiently-empty deque must RE-WAIT for the next signal;
        // `null` is reserved for a genuinely closed channel.
        val first: TmuxPaneInputSegment
        while (true) {
            if (signal.receiveCatching().isClosed) return null
            val taken = synchronized(lock) { pending.removeFirstOrNull() }
            if (taken != null) {
                first = taken
                break
            }
        }
        val out = java.io.ByteArrayOutputStream(maxBatchBytes)
        out.write(first.bytes)
        var chunks = 1
        val firstEnqueuedAtNs = first.enqueuedAtNs

        while (out.size() + TMUX_INPUT_CHUNK_BYTES <= maxBatchBytes) {
            val next = synchronized(lock) {
                pending.firstOrNull()?.takeIf { out.size() + it.bytes.size <= maxBatchBytes }?.also {
                    pending.removeFirst()
                }
            } ?: break
            out.write(next.bytes)
            chunks += 1
        }
        signalIfPending()
        return TmuxPaneInputBatch(
            bytes = out.toByteArray(),
            chunks = chunks,
            firstEnqueuedAtNs = firstEnqueuedAtNs,
        )
    }

    fun recordSent(batch: TmuxPaneInputBatch) = synchronized(lock) {
        pendingBytes -= batch.bytes.size
        totalSentBytes += batch.bytes.size.toLong()
        sentBatchCount += 1
        maxObservedBatchBytes = maxOf(maxObservedBatchBytes, batch.bytes.size)
        maxObservedBatchChunks = maxOf(maxObservedBatchChunks, batch.chunks)
        val latencyNs = System.nanoTime() - batch.firstEnqueuedAtNs
        maxObservedSendLatencyNs = maxOf(maxObservedSendLatencyNs, latencyNs)
    }

    fun recordDropped(batch: TmuxPaneInputBatch) = synchronized(lock) {
        pendingBytes -= batch.bytes.size
    }

    fun requeueFront(batch: TmuxPaneInputBatch) {
        synchronized(lock) {
            pending.addFirst(TmuxPaneInputSegment(batch.bytes, batch.firstEnqueuedAtNs))
        }
        signal.trySend(Unit)
    }

    fun snapshot(): TmuxInputStressMetrics = synchronized(lock) {
        TmuxInputStressMetrics(
            totalEnqueuedBytes = totalEnqueuedBytes,
            totalSentBytes = totalSentBytes,
            maxPendingBytes = maxObservedPendingBytes,
            maxPendingChunks = maxObservedPendingChunks,
            maxBatchBytes = maxObservedBatchBytes,
            maxBatchChunks = maxObservedBatchChunks,
            sentBatchCount = sentBatchCount,
            maxSendLatencyMs = maxObservedSendLatencyNs.toDouble() / 1_000_000.0,
        )
    }

    fun close() { synchronized(lock) { closed = true }; signal.close() }

    private fun enqueue(bytes: ByteArray) {
        val segment = TmuxPaneInputSegment(bytes, System.nanoTime())
        synchronized(lock) {
            if (closed) throw IOException("tmux pane input queue is closed")
            if (pendingBytes + bytes.size > maxPendingBytes) {
                throw TmuxPaneInputQueueOverflowException("tmux pane input queue pending byte budget exceeded")
            }
            pendingBytes += bytes.size
            totalEnqueuedBytes += bytes.size.toLong()
            pending.addLast(segment)
            maxObservedPendingBytes = maxOf(maxObservedPendingBytes, pendingBytes)
            maxObservedPendingChunks = maxOf(
                maxObservedPendingChunks,
                (pendingBytes + maxBatchBytes - 1) / maxBatchBytes,
            )
        }
        val result = signal.trySend(Unit)
        if (result.isFailure && result.exceptionOrNull() == null) {
            return
        }
        if (result.isFailure) {
            synchronized(lock) {
                if (pending.remove(segment)) {
                    pendingBytes -= bytes.size
                    totalEnqueuedBytes -= bytes.size.toLong()
                }
            }
            throw IOException("tmux pane input queue is closed")
        }
    }

    private fun signalIfPending() { if (synchronized(lock) { pending.isNotEmpty() }) signal.trySend(Unit) }
}

private class TmuxPaneInputQueueOverflowException(message: String) : IOException(message)

internal const val TMUX_INPUT_MAX_PENDING_BYTES: Int = 64 * 1024
internal const val TMUX_INPUT_MAX_BATCH_BYTES: Int = 4 * 1024
internal const val TMUX_INPUT_CHUNK_BYTES: Int = 512
internal const val TMUX_INPUT_SEND_MAX_ATTEMPTS: Int = 2
internal const val TMUX_INPUT_SEND_RETRY_DELAY_MS: Long = 150L
internal const val TMUX_INPUT_MAX_PENDING_CHUNKS: Int =
    TMUX_INPUT_MAX_PENDING_BYTES / TMUX_INPUT_CHUNK_BYTES - 1
