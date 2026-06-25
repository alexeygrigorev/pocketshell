package com.pocketshell.core.ssh

import net.schmizz.sshj.connection.channel.direct.Session
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.lang.reflect.InvocationHandler
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Reproduce-first (issue #937 / S1-F1 / D33 / G10): `RealSshShell.close()` is
 * called SYNCHRONOUSLY from `TmuxSessionViewModel.onCleared` (activity destroy)
 * on the Main thread. The OLD body did a bare `dispatcher.runBlockingDispatch`
 * which blocks the CALLING thread until the dispatch-thread channel-close
 * socket write completes — on a half-open link that write WEDGES, so the
 * calling (Main) thread parks → ANR and the activity never destroys.
 *
 * These tests build a sshj `Session` / `Session.Shell` via a dynamic proxy
 * whose `close()` BLOCKS forever (the half-open-link stand-in), and assert that
 * `RealSshShell.close()`:
 *   (a) returns within the bounded [RealSshShell.CLOSE_TIMEOUT_MS] window — it
 *       does NOT park the calling thread forever (RED on base: hangs), and
 *   (b) runs the channel-close work OFF the calling thread (on Dispatchers.IO).
 *
 * Pure JVM, Docker-free — runs in the per-push Unit gate via `./gradlew test`.
 */
class RealSshShellCloseOffMainTest {

    /**
     * The calling thread is NOT parked beyond the bounded window when the
     * channel close wedges. RED on base: the unbounded `runBlockingDispatch`
     * parks the caller forever → the test (and the real onCleared) hang/ANR.
     */
    @Test
    fun `close returns within the bound even when the channel close wedges`() {
        val closeEntered = CountDownLatch(1)
        val neverReturns = CountDownLatch(1)
        val shell = wedgingShell(closeEntered, neverReturns)
        val session = wedgingSession(closeEntered, neverReturns)
        val realShell = RealSshShell(session, shell, TransportDispatcher())

        val start = System.nanoTime()
        // Run close on a worker we can observe; in production this is Main.
        val closeThread = Thread { realShell.close() }
        closeThread.start()
        // The CALLING thread (Main in production) must unpark within the
        // bounded CLOSE_TIMEOUT_MS (2s) window — NOT block for the dispatcher's
        // full 8s per-op ceiling (that is still a multi-second Main-thread ANR)
        // and NOT forever (the fully-unbounded original). A 5s join ceiling is
        // comfortably above the 2s bound and well below both the 8s dispatcher
        // ceiling and "forever".
        closeThread.join(5_000)
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertFalse(
            "close() must RETURN the calling thread within the bounded " +
                "CLOSE_TIMEOUT_MS window even when the channel close wedges — " +
                "the unbounded base parks the calling (Main) thread until the " +
                "dispatcher ceiling or forever (ANR). elapsed=${elapsedMs}ms",
            closeThread.isAlive,
        )
        assertTrue(
            "the wedged channel-close must actually have been entered (the test " +
                "is exercising the real close path, not a no-op): elapsed=${elapsedMs}ms",
            closeEntered.await(1, TimeUnit.SECONDS),
        )

        neverReturns.countDown() // let the wedged proxy thread unwind
    }

    /**
     * The channel-close work runs OFF the calling thread (on Dispatchers.IO),
     * so the Main thread is never the one doing the blocking socket write —
     * the StrictMode/ANR concern (#166/#937 S1-F1).
     */
    @Test
    fun `close runs the channel close off the calling thread`() {
        val closeEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closeThreadName = AtomicReference<String>()
        val shell = recordingShell(closeEntered, release, closeThreadName)
        val session = recordingSession(closeEntered, release, closeThreadName)
        val realShell = RealSshShell(session, shell, TransportDispatcher())

        val caller = Thread({ realShell.close() }, "ps-test-caller")
        caller.start()
        // Wait until the close work is observed, then release it.
        assertTrue("channel close must run", closeEntered.await(5, TimeUnit.SECONDS))
        release.countDown()
        caller.join(8_000)
        assertFalse("close() must return", caller.isAlive)

        assertNotEquals(
            "the channel-close socket write must run OFF the calling thread " +
                "(on the dispatcher's IO thread), never on the caller/Main thread",
            "ps-test-caller",
            closeThreadName.get(),
        )
    }

    // --- proxy-backed sshj fakes (no mocking dependency in core-ssh) ---

    private fun wedgingShell(entered: CountDownLatch, never: CountDownLatch): Session.Shell =
        proxy(Session.Shell::class.java) { _, method, _ ->
            if (method.name == "close") {
                entered.countDown()
                never.await() // wedge forever (until the test releases it)
            }
            defaultReturn(method)
        }

    private fun wedgingSession(entered: CountDownLatch, never: CountDownLatch): Session =
        proxy(Session::class.java) { _, method, _ ->
            if (method.name == "close") {
                entered.countDown()
                never.await()
            }
            defaultReturn(method)
        }

    private fun recordingShell(
        entered: CountDownLatch,
        release: CountDownLatch,
        threadName: AtomicReference<String>,
    ): Session.Shell = proxy(Session.Shell::class.java) { _, method, _ ->
        if (method.name == "close") {
            threadName.set(Thread.currentThread().name)
            entered.countDown()
            release.await()
        }
        defaultReturn(method)
    }

    private fun recordingSession(
        entered: CountDownLatch,
        release: CountDownLatch,
        threadName: AtomicReference<String>,
    ): Session = proxy(Session::class.java) { _, method, _ ->
        if (method.name == "close") {
            threadName.compareAndSet(null, Thread.currentThread().name)
            entered.countDown()
            release.await()
        }
        defaultReturn(method)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> proxy(iface: Class<T>, handler: InvocationHandler): T =
        Proxy.newProxyInstance(iface.classLoader, arrayOf(iface), handler) as T

    private fun defaultReturn(method: Method): Any? = when (method.returnType) {
        Boolean::class.javaPrimitiveType -> false
        Int::class.javaPrimitiveType -> 0
        Long::class.javaPrimitiveType -> 0L
        OutputStream::class.java -> ByteArrayOutputStream()
        InputStream::class.java -> ByteArrayInputStream(ByteArray(0))
        Void.TYPE -> null
        else -> null
    }
}
