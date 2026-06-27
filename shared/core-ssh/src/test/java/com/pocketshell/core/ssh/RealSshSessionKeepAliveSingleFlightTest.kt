package com.pocketshell.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import net.schmizz.concurrent.Promise
import net.schmizz.keepalive.KeepAlive
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.LoggerFactory
import net.schmizz.sshj.common.SSHPacket
import net.schmizz.sshj.connection.Connection
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.Channel
import net.schmizz.sshj.connection.channel.OpenFailException
import net.schmizz.sshj.connection.channel.forwarded.ForwardedChannelOpener
import net.schmizz.sshj.transport.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class RealSshSessionKeepAliveSingleFlightTest {

    @Test
    fun `sendKeepAlive does not send a second global request while the prior reply is pending`() = runBlocking {
        val connection = PendingKeepAliveConnection()
        val client = KeepAliveClient(connection)
        val session = RealSshSession(client)

        try {
            val first = async(Dispatchers.IO) { session.sendKeepAlive() }
            assertTrue(connection.requestSent.await(5, TimeUnit.SECONDS))
            assertTrue(connection.promise.retrieveStarted.await(5, TimeUnit.SECONDS))

            val second = async(Dispatchers.IO) { session.sendKeepAlive() }
            Thread.sleep(100L)
            assertEquals(
                "a pending keepalive reply observer must suppress the next wire request",
                1,
                connection.sendCount.get(),
            )

            connection.promise.allowRetrieveToFinish.countDown()

            withTimeout(5_000L) {
                assertTrue(first.await())
                assertTrue(second.await())
            }
            assertEquals(1, connection.sendCount.get())
        } finally {
            session.close()
        }
    }

    private class KeepAliveClient(
        private val connection: Connection,
    ) : SSHClient() {
        @Volatile
        private var disconnected = false

        override fun isConnected(): Boolean = !disconnected
        override fun isAuthenticated(): Boolean = !disconnected
        override fun getConnection(): Connection = connection
        override fun disconnect() {
            disconnected = true
        }
    }

    private class PendingKeepAliveConnection : Connection {
        val promise = BlockingKeepAlivePromise()
        val requestSent = CountDownLatch(1)
        val sendCount = AtomicInteger(0)

        override fun sendGlobalRequest(
            name: String,
            wantReply: Boolean,
            data: ByteArray,
        ): Promise<SSHPacket, ConnectionException> {
            assertEquals(KEEPALIVE_REQUEST_NAME, name)
            assertTrue(wantReply)
            sendCount.incrementAndGet()
            requestSent.countDown()
            return promise
        }

        override fun attach(chan: Channel) = Unit
        override fun attach(opener: ForwardedChannelOpener) = Unit
        override fun forget(chan: Channel) = Unit
        override fun forget(opener: ForwardedChannelOpener) = Unit
        override fun get(id: Int): Channel = throw UnsupportedOperationException("not used")
        override fun get(chanType: String): ForwardedChannelOpener =
            throw UnsupportedOperationException("not used")
        override fun join() = Unit
        override fun nextID(): Int = 1
        override fun sendOpenFailure(
            recipient: Int,
            reason: OpenFailException.Reason,
            message: String,
        ) = Unit
        override fun getMaxPacketSize(): Int = 32 * 1024
        override fun setMaxPacketSize(maxPacketSize: Int) = Unit
        override fun getWindowSize(): Long = 0L
        override fun setWindowSize(windowSize: Long) = Unit
        override fun getTransport(): Transport = throw UnsupportedOperationException("not used")
        override fun getTimeoutMs(): Int = 0
        override fun setTimeoutMs(timeout: Int) = Unit
        override fun getKeepAlive(): KeepAlive = throw UnsupportedOperationException("not used")
    }

    private class BlockingKeepAlivePromise : Promise<SSHPacket, ConnectionException>(
        "keepalive",
        ConnectionException.chainer,
        LoggerFactory.DEFAULT,
    ) {
        val retrieveStarted = CountDownLatch(1)
        val allowRetrieveToFinish = CountDownLatch(1)

        override fun retrieve(): SSHPacket {
            retrieveStarted.countDown()
            allowRetrieveToFinish.await(30, TimeUnit.SECONDS)
            throw ConnectionException("Global request keepalive failed")
        }
    }
}
