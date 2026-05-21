package com.pocketshell.core.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.DirectConnection
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * sshj-backed implementation of [SshPortForward].
 *
 * Equivalent to `ssh -L <localPort>:<remoteHost>:<remotePort>`. We do not use
 * sshj's bundled [net.schmizz.sshj.connection.channel.direct.LocalPortForwarder]
 * because it copies bytes through internal `StreamCopier` instances we can't
 * instrument for the per-tunnel byte counters required by [SshPortForward].
 * Instead we accept connections on our own [ServerSocket] and open a
 * [DirectConnection] (direct-tcpip channel) per accepted client, copying bytes
 * in both directions through counting streams.
 *
 * Internal to `core-ssh` — callers obtain instances via
 * [SshSession.openLocalPortForward].
 */
internal class RealSshPortForward(
    private val client: SSHClient,
    override val remoteHost: String,
    override val remotePort: Int,
    override val localPort: Int,
) : SshPortForward {

    private val serverSocket: ServerSocket =
        ServerSocket(localPort, /* backlog = */ 50, InetAddress.getByName("127.0.0.1"))

    private val running = AtomicBoolean(true)
    private val forwardedBytes = AtomicLong(0)
    private val receivedBytes = AtomicLong(0)

    /** Daemon thread that accepts incoming local connections. */
    private val acceptThread: Thread = Thread(::acceptLoop, "ssh-portfwd-accept-$localPort").apply {
        isDaemon = true
        start()
    }

    override val isActive: Boolean
        get() = running.get() && !serverSocket.isClosed

    override val bytesForwarded: Long
        get() = forwardedBytes.get()

    override val bytesReceived: Long
        get() = receivedBytes.get()

    private fun acceptLoop() {
        while (running.get()) {
            val client: Socket = try {
                serverSocket.accept()
            } catch (e: SocketException) {
                // serverSocket.close() unblocks accept() with a SocketException;
                // that's our cue to exit cleanly.
                if (!running.get()) return
                throw e
            } catch (e: IOException) {
                if (!running.get()) return
                throw e
            }
            startChannel(client)
        }
    }

    private fun startChannel(local: Socket) {
        // Open the direct-tcpip channel synchronously so an open failure is
        // visible *here* (we can log + close the local socket). Once open, we
        // hand off to two daemon copy threads — one per direction.
        val channel: DirectConnection = try {
            client.newDirectConnection(remoteHost, remotePort)
        } catch (t: Throwable) {
            // Couldn't open the channel — drop the local connection. Don't
            // crash the accept loop; another connection might succeed.
            runCatching { local.close() }
            return
        }

        val sockIn: InputStream = local.getInputStream()
        val sockOut: OutputStream = local.getOutputStream()
        val chanIn: InputStream = channel.inputStream
        val chanOut: OutputStream = channel.outputStream

        // local -> remote (bytes forwarded out)
        Thread({
            copy(sockIn, chanOut, forwardedBytes)
            // When one side EOFs, tear the whole pair down so the partner
            // copier doesn't hang on a half-closed channel.
            closePair(local, channel)
        }, "ssh-portfwd-l2r-$localPort").apply { isDaemon = true; start() }

        // remote -> local (bytes received from remote)
        Thread({
            copy(chanIn, sockOut, receivedBytes)
            closePair(local, channel)
        }, "ssh-portfwd-r2l-$localPort").apply { isDaemon = true; start() }
    }

    private fun copy(src: InputStream, dst: OutputStream, counter: AtomicLong) {
        val buf = ByteArray(BUFFER_SIZE)
        try {
            while (true) {
                val n = src.read(buf)
                if (n < 0) break
                dst.write(buf, 0, n)
                dst.flush()
                counter.addAndGet(n.toLong())
            }
        } catch (_: IOException) {
            // Either side closed; normal termination of the copy loop.
        }
    }

    private fun closePair(local: Socket, channel: DirectConnection) {
        runCatching { local.close() }
        runCatching { channel.close() }
    }

    override fun close() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket.close() }
        // Don't join the accept thread — it's a daemon and the SocketException
        // wakeup is immediate. Joining would deadlock if close() is called
        // from inside the accept thread itself.
    }

    private companion object {
        // 32 KiB matches sshj's default channel max-packet size, which is the
        // largest single read we'd ever get back from the channel side. Keeps
        // the copy loop tight without over-allocating.
        const val BUFFER_SIZE = 32 * 1024
    }
}
