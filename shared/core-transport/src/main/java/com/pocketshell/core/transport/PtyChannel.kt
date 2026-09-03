package com.pocketshell.core.transport

import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.Flow

/** One interactive PTY channel on a [HostConnection]. */
interface PtyChannel {
    /**
     * Bytes coming back from the remote PTY, in arrival order. The flow
     * completes when the channel reaches EOF (remote exit or [close]).
     * Collection is single-consumer.
     */
    val output: Flow<ByteArray>

    /** Sends [bytes] to the remote PTY's stdin. Writes are serialized. */
    suspend fun write(bytes: ByteArray)

    /** Sends a window-change for the new terminal size. */
    suspend fun resize(cols: Int, rows: Int)

    /**
     * Completes when the channel ends, with the remote exit status when the
     * server reported one and `null` when it did not.
     */
    val exit: Deferred<Int?>

    /** Closes the channel. Idempotent. */
    suspend fun close()
}
