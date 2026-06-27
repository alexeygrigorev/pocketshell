package com.pocketshell.core.ssh

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #935 H1: tmux command writes must be cancellable while queued behind
 * another transport operation. The old tmux path called [SshShell.stdin] from
 * `Dispatchers.IO`; for [RealSshShell] that entered `runBlockingDispatch`, so a
 * cancelled command could leave an IO worker parked behind the dispatch mutex
 * until the current transport op finished.
 */
class RealSshShellWriteCancellationTest {

    @Test
    fun `writeStdin cancellation while queued behind dispatcher mutex does not write later`() =
        runBlocking {
            val dispatcher = TransportDispatcher(perOpTimeoutMs = 5_000L)
            val holderEntered = CountDownLatch(1)
            val releaseHolder = CountDownLatch(1)
            val writtenBytes = AtomicInteger(0)
            val output = object : OutputStream() {
                override fun write(b: Int) {
                    writtenBytes.incrementAndGet()
                }

                override fun write(b: ByteArray, off: Int, len: Int) {
                    writtenBytes.addAndGet(len)
                }
            }
            val shell = RealSshShell(
                sessionChannel = noopSession(),
                shell = shellWithOutput(output),
                dispatcher = dispatcher,
            )

            val holder = async(Dispatchers.IO) {
                dispatcher.run {
                    holderEntered.countDown()
                    releaseHolder.await()
                }
            }
            assertTrue("holder must acquire the dispatcher mutex", holderEntered.await(5, TimeUnit.SECONDS))

            val writer = async(Dispatchers.IO) {
                shell.writeStdin("queued-write\n".toByteArray(Charsets.UTF_8))
            }
            delay(100)

            writer.cancelAndJoin()
            releaseHolder.countDown()
            holder.await()
            delay(100)

            assertEquals(
                "a cancelled queued write must be removed from the dispatcher wait queue",
                0,
                writtenBytes.get(),
            )
            dispatcher.closeAndAwaitDrain { }
        }

    private fun shellWithOutput(output: OutputStream): Session.Shell =
        proxy(Session.Shell::class.java) { _, method, _ ->
            when (method.name) {
                "getOutputStream" -> output
                "getInputStream", "getErrorStream" -> ByteArrayInputStream(ByteArray(0))
                else -> defaultReturn(method)
            }
        }

    private fun noopSession(): Session =
        proxy(Session::class.java) { _, method, _ -> defaultReturn(method) }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(type: Class<T>, handler: InvocationHandler): T =
        Proxy.newProxyInstance(
            type.classLoader,
            arrayOf(type),
            handler,
        ) as T

    private fun defaultReturn(method: Method): Any? =
        when (method.returnType) {
            java.lang.Boolean.TYPE -> false
            java.lang.Byte.TYPE -> 0.toByte()
            java.lang.Short.TYPE -> 0.toShort()
            java.lang.Integer.TYPE -> 0
            java.lang.Long.TYPE -> 0L
            java.lang.Float.TYPE -> 0f
            java.lang.Double.TYPE -> 0.0
            java.lang.Character.TYPE -> 0.toChar()
            java.lang.Void.TYPE -> null
            InputStream::class.java -> ByteArrayInputStream(ByteArray(0))
            OutputStream::class.java -> OutputStream.nullOutputStream()
            else -> null
        }
}
