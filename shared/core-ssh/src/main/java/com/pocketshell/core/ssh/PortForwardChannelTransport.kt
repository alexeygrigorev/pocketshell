package com.pocketshell.core.ssh

import net.schmizz.sshj.connection.channel.direct.DirectConnection

/**
 * Single-writer-safe channel factory for a port forward (issue #980).
 *
 * ## Why this exists
 *
 * `RealSshPortForward` opens one `direct-tcpip` channel per accepted local
 * connection and closes it when either side EOFs. A channel open and a channel
 * close are BOTH transport-mutating SSH packets that advance the encoder
 * sequence counter — exactly the kind of write that issue #847 proved must be
 * serialised against every other writer (the keepalive global request, the
 * `-CC` stdin write, exec channel opens) or the encoder sequence-number / cipher
 * desync resurfaces and the server logs `ssh_dispatch_run_fatal: ... Connection
 * corrupted`.
 *
 * Before #980 `RealSshPortForward` held the raw [net.schmizz.sshj.SSHClient] and
 * called `client.newDirectConnection(...)` / `channel.close()` straight off the
 * accept loop and the copy threads — a SECOND, un-ownable writer racing the
 * dispatcher-serialised keepalive on the SAME transport. A burst of accepted
 * connections (a fresh-reconnect scan-and-forward storm) or a flapping forwarded
 * service churned un-serialised opens/closes continuously, re-opening the #847
 * desync on an otherwise-stable link.
 *
 * This interface restores the single-writer invariant: the port forward never
 * touches the raw client. Every channel open and every channel close funnels
 * through the connection's [TransportDispatcher] (the same FIFO single-thread
 * path the keepalive / `-CC` / exec writes use), so there is exactly ONE writer
 * to the transport again. The local-socket `accept()` and the byte copy loops
 * stay off-dispatcher — only the SSH-side channel-lifecycle packets serialise.
 */
internal interface PortForwardChannelTransport {

    /**
     * Open a `direct-tcpip` channel to `remoteHost:remotePort` from the SSH
     * server's perspective, serialised through the transport dispatcher so the
     * channel-open packet can never interleave with the keepalive / another op.
     * Blocks the calling (accept-loop) thread until the dispatch thread has run
     * the open. Throws on open failure or once the transport is closed.
     */
    fun openChannel(remoteHost: String, remotePort: Int): DirectConnection

    /**
     * Close [channel], serialised through the transport dispatcher so the
     * channel-close packet can never interleave with another writer. Best
     * effort — a failure (transport already gone) is swallowed so teardown of a
     * forwarded pair never propagates an exception into the copy threads.
     */
    fun closeChannel(channel: DirectConnection)
}

/**
 * The production [PortForwardChannelTransport] (issue #980): opens and closes
 * every direct-tcpip channel through the connection's single-writer
 * [TransportDispatcher].
 *
 * `openChannel` runs the [open] body on the dispatch thread, serialised FIFO
 * against the keepalive global request, the `-CC` stdin write, exec channel
 * opens, and teardown — restoring the single-writer invariant #847 established.
 * The blocking [TransportDispatcher.runBlockingDispatch] bridge is correct here
 * because the only callers are the forward's accept-loop / copy threads (IO
 * daemon threads), never the dispatch thread itself, so it cannot deadlock on
 * the dispatcher's mutex.
 *
 * `closeChannel` is best-effort: once the transport is torn down the dispatcher
 * rejects the op with [TransportClosedException] (the channel is already gone
 * with the transport), so we swallow it — a forwarded pair's EOF teardown must
 * never propagate an exception into the copy threads.
 *
 * Split out from `RealSshSession` so the single-writer property is unit-testable
 * (`PortForwardSingleWriterTest`) without a live SSH transport: the [open]/[close]
 * lambdas are injected, while the dispatcher serialisation is the real thing.
 */
internal class DispatcherBackedChannelTransport(
    private val dispatcher: TransportDispatcher,
    private val open: (remoteHost: String, remotePort: Int) -> DirectConnection,
    private val close: (DirectConnection) -> Unit,
) : PortForwardChannelTransport {

    override fun openChannel(remoteHost: String, remotePort: Int): DirectConnection =
        dispatcher.runBlockingDispatch { open(remoteHost, remotePort) }

    override fun closeChannel(channel: DirectConnection) {
        runCatching { dispatcher.runBlockingDispatch { close(channel) } }
    }
}
