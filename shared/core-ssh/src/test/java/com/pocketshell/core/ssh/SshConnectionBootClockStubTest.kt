package com.pocketshell.core.ssh

import android.os.SystemClock
import net.schmizz.sshj.SSHClient
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1111 — reproduce-first JVM proof that the production SSH session
 * construction site survives the forked-JVM android.jar STUB (D33/G10).
 *
 * ## The bug
 *
 * #1080 (`ef45e95d`) hard-coded the wall-elapsed boot clock at the single session
 * construction site, [SshConnection.RealSshConnector.toSession]:
 *
 * ```
 * RealSshSession(client, nowNanos = { SystemClock.elapsedRealtimeNanos() })
 * ```
 *
 * [RealSshSession]'s `init` stamps its activity watermarks by CALLING `nowNanos()`
 * immediately, so constructing the session evaluates `SystemClock`. On-device that
 * is the real `CLOCK_BOOTTIME` (correct — it must count deep Doze). But in a
 * forked-JVM test that links the **android.jar STUB** (not Robolectric / the
 * emulator) — `core-portfwd:integrationTest` and the `core-ssh` failure tests —
 * `SystemClock.elapsedRealtimeNanos()` throws `RuntimeException("Stub!")`. So the
 * real `connect()` path (which calls `toSession()` after auth) died with
 * `SshException at SshConnection.kt:299  Caused by: RuntimeException at
 * SystemClock.java:-1`, reddening the heavy `Integration tests (Docker)` job
 * (`PortForwardIntegrationTest` 6/6) on every `main` push.
 *
 * ## The fix
 *
 * The clock is now the JVM-stub-safe [SshConnection.bootElapsedNanos]:
 * `runCatching { SystemClock.elapsedRealtimeNanos() }.getOrElse { System.nanoTime() }`.
 * On-device the `runCatching` never catches anything (the boot clock is real, so the
 * #1080 Doze contract is intact); only the android.jar stub's `"Stub!"` throw is
 * caught, falling back to `System.nanoTime()` so the construction site works.
 *
 * ## Why this is the real path, not a proxy
 *
 * These tests exercise the EXACT production construction site
 * [SshConnection.RealSshConnector.toSession] under the EXACT failing host-JVM stub
 * the integration suite runs in. The `[mechanism]` test pins WHY the guard is
 * needed (the bare stub call throws); the `[toSession]` test is the load-bearing
 * GREEN assertion — RED on the #1080 base (the unguarded inline call throws while
 * stamping the watermark in `RealSshSession.init`), GREEN with [bootElapsedNanos].
 */
class SshConnectionBootClockStubTest {

    @Test
    fun `mechanism - the bare SystemClock boot clock throws under the android jar stub`() {
        // Anchors the root cause: on the host JVM the android.jar stub makes
        // SystemClock.elapsedRealtimeNanos() throw, which is exactly why the single
        // construction site must guard it (#1080 hard-coded it bare → #1111 red CI).
        assertThrows(RuntimeException::class.java) {
            SystemClock.elapsedRealtimeNanos()
        }
    }

    @Test
    fun `bootElapsedNanos falls back to a real monotonic value instead of throwing`() {
        // The guarded seam must NEVER throw on the JVM stub, and must return a usable
        // (positive, monotonic) nanosecond reading from the System.nanoTime fallback.
        val first = SshConnection.bootElapsedNanos()
        val second = SshConnection.bootElapsedNanos()
        assertTrue(
            "bootElapsedNanos must return a positive nanosecond reading on the JVM stub " +
                "fallback, got first=$first",
            first > 0L,
        )
        assertTrue(
            "bootElapsedNanos must be monotonic non-decreasing across calls, " +
                "got first=$first second=$second",
            second >= first,
        )
    }

    @Test
    fun `toSession does not throw under the JVM stub - the real construction site`() {
        // The LOAD-BEARING assertion: building a session at the EXACT production site
        // (RealSshConnector.toSession, which RealSshSession.init evaluates by calling
        // nowNanos() to stamp its activity watermarks) must NOT throw under the
        // android.jar stub. On the #1080 base this throws RuntimeException("Stub!")
        // (RED); with the bootElapsedNanos guard it returns a live session (GREEN).
        val session = SshConnection.RealSshConnector.toSession(connectedClient())
        try {
            assertNotNull(
                "toSession() must build a session under the forked-JVM android.jar stub " +
                    "instead of dying with SystemClock \"Stub!\" — the #1111 regression guard",
                session,
            )
        } finally {
            session.close()
        }
    }

    /**
     * Minimal connected sshj client: the session construction only stamps activity
     * watermarks from `nowNanos()` and starts a 30s keepalive loop that never ticks
     * in this sub-second test. `disconnect()` is a no-op (no real socket).
     */
    private fun connectedClient(): SSHClient = object : SSHClient() {
        override fun isConnected(): Boolean = true
        override fun isAuthenticated(): Boolean = true
        override fun disconnect() = Unit
    }
}
