package com.pocketshell.core.ssh

import net.schmizz.sshj.connection.channel.direct.DirectConnection
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicLong

class RealSshPortForwardCountersTest {

    @Test
    fun `copy counts bytes as soon as they are read before local write can observe them`() {
        val localPort = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
            .use { it.localPort }
        val forward = RealSshPortForward(
            channels = object : PortForwardChannelTransport {
                override fun openChannel(remoteHost: String, remotePort: Int): DirectConnection =
                    error("test does not accept local connections")

                override fun closeChannel(channel: DirectConnection) = Unit
            },
            remoteHost = "remote.example",
            remotePort = 5432,
            localPort = localPort,
        )
        try {
            val counter = AtomicLong(0)
            val input = SingleReadInputStream(byteArrayOf(1, 2, 3, 4))
            var counterObservedInWrite = -1L
            val output = object : OutputStream() {
                override fun write(b: Int) = Unit

                override fun write(b: ByteArray, off: Int, len: Int) {
                    counterObservedInWrite = counter.get()
                }
            }

            val copy = RealSshPortForward::class.java.getDeclaredMethod(
                "copy",
                InputStream::class.java,
                OutputStream::class.java,
                AtomicLong::class.java,
            )
            copy.isAccessible = true
            copy.invoke(forward, input, output, counter)

            assertEquals(4, counter.get())
            assertEquals(4, counterObservedInWrite)
            assertTrue(input.exhausted)
        } finally {
            forward.close()
        }
    }

    private class SingleReadInputStream(
        private val bytes: ByteArray,
    ) : InputStream() {
        var exhausted = false
            private set
        private var read = false

        override fun read(): Int = error("bulk read expected")

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (read) {
                exhausted = true
                return -1
            }
            read = true
            bytes.copyInto(b, off, 0, bytes.size)
            return bytes.size
        }
    }
}
