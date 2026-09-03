package com.pocketshell.core.transport

/**
 * Handle to an active local-to-remote SSH port forward — the `ssh -L
 * <localPort>:<remoteHost>:<remotePort>` primitive.
 *
 * A forward listens on `127.0.0.1:[localPort]` on the DEVICE and tunnels every
 * accepted connection to `[remoteHost]:[remotePort]` resolved from the SSH
 * server's side, over the [HostConnection] that produced it.
 *
 * Obtain instances via [HostConnection.openPortForward]. The handle is spent
 * once [close] returns; a new forward must be opened to resume.
 */
interface PortForward {

    /** Loopback port on the *device* the forward listens on. */
    val localPort: Int

    /** Host the remote end of each channel connects to, from the server's perspective. */
    val remoteHost: String

    /** Port the remote end of each channel connects to, from the server's perspective. */
    val remotePort: Int

    /** True while the local listener is open and accepting connections. */
    val isActive: Boolean

    /** Bytes pushed from local clients out through the SSH channel. */
    val bytesForwarded: Long

    /** Bytes pulled from the SSH channel back to local clients. */
    val bytesReceived: Long

    /**
     * Tears the forward down: stops accepting, closes every in-flight pair, and
     * joins the copy threads so no file descriptor outlives the call.
     *
     * Idempotent. Suspends (rather than blocking the caller) because the SSH
     * channel-teardown packets it drives can park on a wedged socket — the
     * caller is typically the UI thread's coroutine, and a blocking close there
     * is exactly the freeze this signature prevents. Cancelling the calling
     * coroutine interrupts the teardown.
     */
    suspend fun close()
}
