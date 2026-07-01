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
     * Issue #1139 / #1136 (D33 / G1 / G2 — the class fix): the caller returns
     * ALMOST IMMEDIATELY, NOT after the bounded [RealSshShell.CLOSE_TIMEOUT_MS]
     * (2s) wait, when the channel close wedges on a half-open transport.
     *
     * This is the load-bearing regression for the maintainer's "UI fully
     * freezes, buttons dead, restart required" wedge. The OLD body wrapped the
     * teardown in `runBlocking(Dispatchers.IO) { withTimeoutOrNull(2000) { … } }`,
     * so while the socket write ran off-Main the CALLER still PARKED for up to
     * ~2s per stale-client close. The `close returns within the bound` test
     * above only asserts the caller returns "within the 2s bound" — a 2s Main
     * park still passes it — so it did NOT catch this class. Here we assert the
     * caller returns in well UNDER the 2s ceiling: RED on base (blocks ~2s),
     * GREEN with the async source fix (returns in ~ms while the wedged teardown
     * drains on the IO close-scope).
     *
     * Class coverage (G2): this is the SOURCE guarantee. Every one of the six
     * `Dispatchers.Main.immediate` teardown sites in `TmuxSessionViewModel`
     * (`:7932, :8056, :8156, :8196, :8290, :8304`) reaches this method via
     * `TmuxClient.close()` → `closeInternal()` → `shell?.close()`, and
     * `closeInternal`'s only other work (state-flag flips, `readerJob.cancel()`,
     * in-memory pane-pipe closes, `clientScope.cancel()`) is non-blocking. So a
     * non-blocking `RealSshShell.close()` makes all six sites — and any future
     * caller of `SshShell.close()` / `TmuxClient.close()` — non-blocking-on-Main
     * by construction, rather than patching each call site.
     */
    @Test
    fun `close returns to the caller without parking for the bounded teardown wait`() {
        val closeEntered = CountDownLatch(1)
        val neverReturns = CountDownLatch(1)
        val shell = wedgingShell(closeEntered, neverReturns)
        val session = wedgingSession(closeEntered, neverReturns)
        val realShell = RealSshShell(session, shell, TransportDispatcher())

        // Call close() DIRECTLY on this (caller/Main-equivalent) thread and
        // measure how long it parks. With the async source fix it launches the
        // teardown and returns in ~ms; on base it blocks until the 2s
        // CLOSE_TIMEOUT_MS ceiling trips inside runBlocking.
        val start = System.nanoTime()
        realShell.close()
        val callerBlockedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue(
            "close() must return to the caller WITHOUT parking for the bounded " +
                "teardown wait — the caller (Main in production) must not block " +
                "while a half-open channel close drains. RED on base (~2s park), " +
                "GREEN with the async source fix. caller-blocked=${callerBlockedMs}ms",
            callerBlockedMs < CALLER_RETURN_BOUND_MS,
        )
        // The teardown must still actually run (off the caller thread): the
        // wedged channel close is entered on the IO close-scope. Proves the fix
        // is non-blocking, NOT a no-op (G6 — not a vacuous pass).
        assertTrue(
            "the channel-close teardown must still run asynchronously (entered " +
                "off the caller thread): caller-blocked=${callerBlockedMs}ms",
            closeEntered.await(5, TimeUnit.SECONDS),
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

    private companion object {
        /**
         * Upper bound on how long the caller thread may block inside a
         * non-blocking [RealSshShell.close]. The async fix returns in ~ms; the
         * base (blocking runBlocking) parks the caller until the 2s
         * `CLOSE_TIMEOUT_MS` ceiling. 750ms sits decisively between the two:
         * comfortably above coroutine-launch + scheduling jitter on a loaded CI
         * box, and far below the ~2000ms base park it must catch.
         */
        const val CALLER_RETURN_BOUND_MS: Long = 750L
    }
}
