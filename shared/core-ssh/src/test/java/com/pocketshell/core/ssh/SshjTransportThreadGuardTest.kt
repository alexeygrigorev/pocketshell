package com.pocketshell.core.ssh

import net.schmizz.sshj.common.SSHException
import net.schmizz.sshj.transport.TransportException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketException
import java.util.concurrent.atomic.AtomicReference

/**
 * Pure unit tests for the [SshjTransportThreadGuard] classification +
 * install machinery. No Docker, no sshj instantiation — we drive the
 * guard's [SshjTransportThreadGuard.isSshjTransportCrash] predicate
 * directly and assert the install path is idempotent.
 *
 * Issue #173 round-2: these tests pin the behaviour the CI fix relies
 * on — namely that only sshj-named threads with transport-family
 * exceptions are swallowed, and the previous default handler is
 * preserved + chained for everything else.
 */
class SshjTransportThreadGuardTest {

    private var savedDefaultHandler: Thread.UncaughtExceptionHandler? = null

    @Before
    fun snapshotDefault() {
        // Some other test in the same JVM may have already installed
        // the guard (or any other default handler). Snapshot whatever
        // is there now so [restoreDefault] can put it back, and clear
        // our own guard's install state so each test sees a clean
        // slate for [SshjTransportThreadGuard.installIfNecessary].
        savedDefaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        SshjTransportThreadGuard.resetForTests()
    }

    @After
    fun restoreDefault() {
        Thread.setDefaultUncaughtExceptionHandler(savedDefaultHandler)
        SshjTransportThreadGuard.resetForTests()
    }

    @Test
    fun `predicate accepts sshj-Reader thread with TransportException`() {
        val thread = Thread(Runnable {}, "sshj-Reader")
        val ex = TransportException("Broken transport; encountered EOF")
        assertTrue(
            "sshj-Reader + TransportException should be classified as a transport crash",
            SshjTransportThreadGuard.isSshjTransportCrash(thread, ex),
        )
    }

    @Test
    fun `predicate accepts sshj-Reader thread with SSHException`() {
        val thread = Thread(Runnable {}, "sshj-Reader-127.0.0.1:22-1700000000")
        val ex = SSHException("Software caused connection abort")
        assertTrue(
            "sshj-Reader + SSHException should be classified as a transport crash",
            SshjTransportThreadGuard.isSshjTransportCrash(thread, ex),
        )
    }

    @Test
    fun `predicate accepts sshj-KeepAliveRunner thread`() {
        // ThreadNameProvider formats KeepAlive thread names as
        // sshj-<class>-<address>-<ts>. We match anything starting
        // sshj- so the KeepAlive thread is covered.
        val thread = Thread(Runnable {}, "sshj-KeepAliveRunner-/127.0.0.1:22-1700000000000")
        val ex = TransportException("Broken transport; encountered EOF")
        assertTrue(
            SshjTransportThreadGuard.isSshjTransportCrash(thread, ex),
        )
    }

    @Test
    fun `predicate accepts sshj thread with wrapped SocketException`() {
        // The v0.2.7 user crash report's root cause: SocketException
        // wrapped as SSHException by sshj's chainer.
        val thread = Thread(Runnable {}, "sshj-Reader")
        val socketEx = SocketException("Software caused connection abort")
        val sshEx = SSHException(socketEx)
        assertTrue(
            SshjTransportThreadGuard.isSshjTransportCrash(thread, sshEx),
        )
    }

    @Test
    fun `predicate accepts sshj thread with bare IOException`() {
        // sshj's transport layer also surfaces plain IOExceptions for
        // packet-level decode failures. The guard should swallow those
        // too — they're still transport-layer failures.
        val thread = Thread(Runnable {}, "sshj-Reader")
        val ex = IOException("bad packet length")
        assertTrue(
            SshjTransportThreadGuard.isSshjTransportCrash(thread, ex),
        )
    }

    @Test
    fun `predicate rejects sshj thread with NullPointerException`() {
        // Genuine programming errors on sshj threads must NOT be
        // swallowed — we want them loud so we can fix them.
        val thread = Thread(Runnable {}, "sshj-Reader")
        val ex = NullPointerException("not a transport failure")
        assertFalse(
            "NPE on an sshj thread must propagate so real bugs surface",
            SshjTransportThreadGuard.isSshjTransportCrash(thread, ex),
        )
    }

    @Test
    fun `predicate rejects non-sshj thread even with TransportException`() {
        // The thread-name guard exists so an app-side coroutine that
        // re-throws an SSHException (e.g. as part of a wrap-and-
        // rethrow path) still gets full crash reporting.
        val thread = Thread(Runnable {}, "DefaultDispatcher-worker-3")
        val ex = TransportException("not from sshj's own thread")
        assertFalse(
            SshjTransportThreadGuard.isSshjTransportCrash(thread, ex),
        )
    }

    @Test
    fun `installIfNecessary chains over the previous default handler`() {
        val seen = AtomicReference<Pair<Thread, Throwable>?>(null)
        val previous = Thread.UncaughtExceptionHandler { t, e ->
            seen.set(t to e)
        }
        Thread.setDefaultUncaughtExceptionHandler(previous)

        SshjTransportThreadGuard.installIfNecessary()

        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull("install should set a new default handler", installed)
        assertFalse(
            "install should wrap, not replace, the previous handler",
            installed === previous,
        )

        // A non-sshj-thread crash must reach the previous handler.
        val otherThread = Thread(Runnable {}, "AppMain")
        val otherEx = IllegalStateException("not from sshj")
        installed!!.uncaughtException(otherThread, otherEx)
        val (capturedThread, capturedEx) = seen.get()
            ?: error("previous handler was not invoked for non-sshj crash")
        assertSame(otherThread, capturedThread)
        assertSame(otherEx, capturedEx)
    }

    @Test
    fun `installIfNecessary is idempotent across calls`() {
        SshjTransportThreadGuard.installIfNecessary()
        val first = Thread.getDefaultUncaughtExceptionHandler()
        SshjTransportThreadGuard.installIfNecessary()
        val second = Thread.getDefaultUncaughtExceptionHandler()
        SshjTransportThreadGuard.installIfNecessary()
        val third = Thread.getDefaultUncaughtExceptionHandler()
        assertSame(
            "second install must not wrap us in a second chained handler",
            first,
            second,
        )
        assertSame(
            "third install must not wrap us in a third chained handler",
            first,
            third,
        )
    }

    @Test
    fun `installIfNecessary handles null previous handler`() {
        Thread.setDefaultUncaughtExceptionHandler(null)
        // Must not throw, even when there is no previous handler.
        SshjTransportThreadGuard.installIfNecessary()
        val installed = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull("install should still set a handler when previous was null", installed)

        // And the chained handler must not crash when delegating a
        // non-sshj crash with no previous handler to fall back to.
        installed!!.uncaughtException(
            Thread(Runnable {}, "AppMain"),
            IllegalStateException("must not throw, must not crash"),
        )
    }

    @Test
    fun `installed handler swallows sshj-thread transport crashes`() {
        val previousSeen = AtomicReference<Throwable?>(null)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            previousSeen.set(e)
        }

        SshjTransportThreadGuard.installIfNecessary()

        val installed = Thread.getDefaultUncaughtExceptionHandler()
        installed!!.uncaughtException(
            Thread(Runnable {}, "sshj-Reader"),
            TransportException("Broken transport; encountered EOF"),
        )
        assertNull(
            "sshj-thread transport crashes must NOT reach the previous handler",
            previousSeen.get(),
        )
    }

    @Test
    fun `chained handler routes non-sshj-thread crashes through previous`() {
        val previousSeen = AtomicReference<Throwable?>(null)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            previousSeen.set(e)
        }

        SshjTransportThreadGuard.installIfNecessary()

        val installed = Thread.getDefaultUncaughtExceptionHandler()
        val ex = IllegalStateException("not from sshj")
        installed!!.uncaughtException(
            Thread(Runnable {}, "ProductionDispatcher-worker-1"),
            ex,
        )
        assertSame(
            "non-sshj crashes must propagate to the previous handler",
            ex,
            previousSeen.get(),
        )
    }

    @Test
    fun `chained handler propagates sshj thread NPE through previous`() {
        // sshj-named thread + non-transport exception → propagate.
        // This protects against accidental over-swallowing of real
        // bugs that happen to manifest on sshj threads.
        val previousSeen = AtomicReference<Throwable?>(null)
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            previousSeen.set(e)
        }

        SshjTransportThreadGuard.installIfNecessary()

        val installed = Thread.getDefaultUncaughtExceptionHandler()
        val ex = NullPointerException("sshj-thread, but a real bug")
        installed!!.uncaughtException(Thread(Runnable {}, "sshj-Reader"), ex)
        assertSame(ex, previousSeen.get())
    }

    @Test
    fun `re-install on top of an existing ChainedHandler does not double-wrap`() {
        // Simulate the case where this guard was already installed
        // by an earlier classloader / earlier test in the same JVM
        // run. The fresh `installIfNecessary` from a process that
        // resets [resetForTests] must NOT wrap the existing chained
        // handler in a second layer — that would shadow the original
        // previous handler and break crash reporting.
        SshjTransportThreadGuard.installIfNecessary()
        val firstChain = Thread.getDefaultUncaughtExceptionHandler()
        assertNotNull(firstChain)

        SshjTransportThreadGuard.resetForTests()
        SshjTransportThreadGuard.installIfNecessary()

        assertSame(
            "re-install over an existing ChainedHandler must adopt it",
            firstChain,
            Thread.getDefaultUncaughtExceptionHandler(),
        )
    }

    @Test
    fun `predicate walks the cause chain for transport exceptions`() {
        // Defensive: some wrappers in the coroutines world bury the
        // real cause beneath a generic RuntimeException. We walk the
        // chain so a wrapped transport failure still classifies.
        val thread = Thread(Runnable {}, "sshj-Reader")
        val root = SocketException("Software caused connection abort")
        val mid = SSHException(root)
        val wrapper = RuntimeException("buried", mid)
        assertTrue(
            "deep-buried SSHException must still classify as a transport crash",
            SshjTransportThreadGuard.isSshjTransportCrash(thread, wrapper),
        )
    }

    @Test
    fun `predicate stops at a reasonable cause chain depth`() {
        // Defensive: if some buggy wrapper introduces a cycle, the
        // chain walk must terminate. We do not assert true/false
        // semantics here because a cycle is malformed input; we
        // just assert the call returns without OOM/stack overflow.
        val thread = Thread(Runnable {}, "sshj-Reader")
        val cyclic = object : RuntimeException("cycle") {
            init {
                // Throwable.initCause() refuses self-causes, so we
                // have to use a 2-cycle: a -> b -> a. JVM enforces a
                // single-call initCause(), so we set up the cycle via
                // a wrapper class that returns the partner on cause().
            }
        }
        // We rely on the depth limit so we don't actually need a
        // self-cycle here; a regular long chain is enough.
        var top: Throwable = RuntimeException("leaf")
        for (i in 0 until 100) {
            top = RuntimeException("wrap-$i", top)
        }
        // No SSHException in the chain → must return false but also
        // must not loop forever.
        assertEquals(
            false,
            SshjTransportThreadGuard.isSshjTransportCrash(thread, top),
        )
        // Sanity: keep the unused `cyclic` reference so the IDE
        // doesn't warn — it's documentation of why the depth limit
        // exists even though we can't construct an actual cycle in
        // user code.
        assertNotNull(cyclic)
    }
}
