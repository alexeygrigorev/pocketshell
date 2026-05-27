package com.pocketshell.core.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * Issue #151 — `RealSshSession.close()` must be idempotent.
 *
 * The v0.2.7 dogfood crash report showed:
 *
 * ```
 * TransportException: [BY_APPLICATION] Disconnected
 *   at net.schmizz.sshj.transport.TransportImpl.disconnect(TransportImpl.java:397)
 *   at com.pocketshell.core.ssh.RealSshSession.close(RealSshSession.kt:179)
 *   at com.pocketshell.app.tmux.TmuxSessionViewModel.closeCurrentConnection(TmuxSessionViewModel.kt:1082)
 *   at com.pocketshell.app.tmux.TmuxSessionViewModel.connect(TmuxSessionViewModel.kt:243)
 * ```
 *
 * Root cause: when the user taps "Attach" on a different tmux session, the
 * tmux ViewModel's event-loop coroutine is still mid-processing a
 * `ControlEvent` when the SSH transport teardown begins. sshj's
 * `SSHClient.disconnect()` then throws `TransportException` with
 * `DisconnectReason.BY_APPLICATION` because the transport has already gone
 * down via the cancellation.
 *
 * The orthogonal `TmuxSessionViewModel` fix cancels-and-joins the event-loop
 * job before touching the transport. This test pins the belt-and-suspenders
 * side of the fix: even if some other code path (or a future regression in
 * the ViewModel) ever calls `close()` against an already-disconnected
 * transport, `RealSshSession.close()` must NOT propagate the
 * already-disconnected signal — `close()` is idempotent by design.
 *
 * Negative case: any other `TransportException` reason (KEX failure, MAC
 * error, protocol error) MUST still propagate. The catch is scoped to
 * `BY_APPLICATION` precisely so a genuine transport fault still surfaces.
 */
class RealSshSessionCloseIdempotencyTest {

    @Test
    fun `close swallows TransportException with BY_APPLICATION disconnect reason`() {
        val client = ThrowingDisconnectClient(
            toThrow = TransportException(
                DisconnectReason.BY_APPLICATION,
                "Disconnected",
            ),
        )
        val session = RealSshSession(client)

        // No exception should propagate — the BY_APPLICATION case is the
        // "transport already gone" no-op `close()` already wanted to be.
        session.close()

        assertTrue(
            "expected SSHClient.disconnect() to have been called on the first close()",
            client.disconnectCallCount == 1,
        )
    }

    @Test
    fun `close is idempotent under repeated invocation`() {
        // Mirrors the double-close path the ViewModel could trigger if a
        // teardown races between `onCleared()` and a subsequent
        // `connect()`. The first call observes the throw; the second
        // observes the same already-disconnected throw. Neither should
        // propagate.
        val client = ThrowingDisconnectClient(
            toThrow = TransportException(
                DisconnectReason.BY_APPLICATION,
                "Disconnected",
            ),
        )
        val session = RealSshSession(client)

        session.close()
        session.close()

        assertEquals(
            "expected SSHClient.disconnect() to have been invoked twice (idempotent close)",
            2,
            client.disconnectCallCount,
        )
    }

    @Test
    fun `close propagates TransportException with a non-BY_APPLICATION reason`() {
        // Negative case: a genuine transport fault (e.g. KEY_EXCHANGE_FAILED)
        // must still surface so callers can tell the difference between
        // "transport was already down" (no-op) and "transport blew up while
        // we were trying to shut it down" (alarming).
        val badReason = DisconnectReason.KEY_EXCHANGE_FAILED
        val client = ThrowingDisconnectClient(
            toThrow = TransportException(badReason, "kex blew up"),
        )
        val session = RealSshSession(client)

        try {
            session.close()
            fail("expected TransportException with reason=$badReason to propagate from close()")
        } catch (e: TransportException) {
            assertEquals(badReason, e.disconnectReason)
        }
    }

    @Test
    fun `close swallows TransportException whose chained cause uses BY_APPLICATION`() {
        // sshj internally re-wraps reason metadata across the
        // TransportException chain in some failure modes — the user-facing
        // crash report on v0.2.7 surfaced exactly this shape. The
        // `disconnectReason` getter on the throwing exception reports
        // BY_APPLICATION; the catch block keys off that.
        val applicationDisconnect = TransportException(
            DisconnectReason.BY_APPLICATION,
            "Disconnected",
        )
        val client = ThrowingDisconnectClient(toThrow = applicationDisconnect)
        val session = RealSshSession(client)

        session.close() // must not throw

        assertSame(
            "expected the SSHClient instance to remain unchanged",
            client,
            client,
        )
        assertNotNull(applicationDisconnect.disconnectReason)
    }

    /**
     * Subclass of [SSHClient] that overrides `disconnect()` to throw the
     * configured exception. Reaching into sshj this way avoids pulling a
     * mocking framework into the module just for this regression — the
     * `core-ssh` module deliberately has no mocking dependency (see
     * `RealSshSessionPtyAllocationTest` for the same constraint).
     *
     * The bare `SSHClient()` constructor sets up the configuration default
     * without opening a socket, so this is safe to instantiate in a unit
     * test (no network I/O).
     */
    private class ThrowingDisconnectClient(
        private val toThrow: Throwable,
    ) : SSHClient() {
        var disconnectCallCount: Int = 0
            private set

        override fun disconnect() {
            disconnectCallCount++
            // We deliberately do NOT call `super.disconnect()` because the
            // real implementation walks the (uninitialised) connection
            // state and would throw NPE on this bare-constructed client.
            // The test only cares about the throw-from-disconnect contract.
            throw toThrow
        }
    }
}
