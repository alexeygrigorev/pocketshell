package com.pocketshell.core.ssh

import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.transport.TransportException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketException

/**
 * Issue #151 + #239 — `RealSshSession.close()` must be idempotent and
 * silent.
 *
 * The v0.2.7 crash report showed:
 *
 * ```
 * TransportException: [BY_APPLICATION] Disconnected
 *   at net.schmizz.sshj.transport.TransportImpl.disconnect(TransportImpl.java:397)
 *   at com.pocketshell.core.ssh.RealSshSession.close(RealSshSession.kt:179)
 *   at com.pocketshell.app.tmux.TmuxSessionViewModel.closeCurrentConnection(TmuxSessionViewModel.kt:1082)
 *   at com.pocketshell.app.tmux.TmuxSessionViewModel.connect(TmuxSessionViewModel.kt:243)
 * ```
 *
 * v0.2.8 then captured twin crashes on the same `onCleared` cascade
 * (issue #239 crashes A + B). The original narrow catch only handled
 * `BY_APPLICATION`; the wider contract now is that **any**
 * `TransportException` raised during teardown is non-actionable and
 * must not propagate. The Android lifecycle (activity destroy ->
 * `ViewModelStore.clear` -> `onCleared` on every ViewModel) is the
 * canonical close path under D21; teardown-time transport faults give
 * the caller nothing useful to recover from.
 *
 * Genuine "transport blew up while connected" diagnostics still surface
 * through the regular read/write path (sshj raises the same exception
 * on the producer-coroutine boundary), so widening this catch does not
 * hide a live-connection fault.
 *
 * This test pins:
 *   1. The `BY_APPLICATION` case is silently swallowed (idempotency).
 *   2. Other `TransportException` reasons are also swallowed (close is
 *      idempotent for every teardown-time transport fault).
 *   3. Repeated `close()` is a no-op.
 *   4. `IOException` / `ConnectionException` / `SocketException` from
 *      sshj's `disconnect()` chain are also swallowed.
 *   5. Close-while-disconnected (sshj already cancelled, then close
 *      arrives) propagates no exception.
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
    fun `close swallows TransportException with a non-BY_APPLICATION reason`() {
        // Issue #239: widened from the v0.2.7 narrow `BY_APPLICATION`
        // catch. Every `TransportException` raised during teardown is
        // non-actionable — the Android lifecycle cascade
        // (activity destroy -> ViewModel.onCleared ->
        // `RealSshSession.close()`) has no recovery path, and propagating
        // would trip the crash reporter exactly like the maintainer
        // device's v0.2.8 reports showed.
        val badReason = DisconnectReason.KEY_EXCHANGE_FAILED
        val client = ThrowingDisconnectClient(
            toThrow = TransportException(badReason, "kex blew up"),
        )
        val session = RealSshSession(client)

        // Must NOT throw — widened contract per issue #239.
        session.close()

        assertEquals(1, client.disconnectCallCount)
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

    @Test
    fun `close swallows ConnectionException raised during disconnect`() {
        // Issue #239: `ConnectionException` extends sshj's `SSHException`,
        // which in turn extends `IOException`. A connection-layer
        // teardown fault (e.g. a channel close hiccup) is just as
        // non-actionable as a transport-layer fault — silent no-op.
        val client = ThrowingDisconnectClient(
            toThrow = ConnectionException("Software caused connection abort"),
        )
        val session = RealSshSession(client)

        session.close() // must not throw

        assertEquals(1, client.disconnectCallCount)
    }

    @Test
    fun `close swallows raw IOException raised during disconnect`() {
        // Issue #239: sshj declares `disconnect()` as `throws IOException`.
        // A non-SSH IOException (e.g. half-closed socket on flush) during
        // teardown is also non-actionable — preserve the idempotent
        // close contract.
        val client = ThrowingDisconnectClient(
            toThrow = IOException("socket already closed"),
        )
        val session = RealSshSession(client)

        session.close() // must not throw

        assertEquals(1, client.disconnectCallCount)
    }

    @Test
    fun `close while transport already disconnected propagates no exception`() {
        // Issue #239 acceptance criterion: explicit close-while-disconnected
        // path. Simulates the v0.2.8 maintainer reports: the SSH transport
        // had already been torn down (e.g. by sshj's internal Reader
        // thread observing socket abort) before `ViewModel.onCleared` ran;
        // when `close()` then invoked `SSHClient.disconnect()`, sshj
        // surfaced a chained `IOException` cause (`SocketException`).
        //
        // Concretely we simulate the second-close shape that the crash
        // report showed: the disconnect attempt finds the transport
        // already gone and raises `BY_APPLICATION` again, then a raw
        // `SocketException`. Neither must propagate.
        val firstClient = ThrowingDisconnectClient(
            toThrow = TransportException(
                DisconnectReason.BY_APPLICATION,
                "Disconnected",
            ),
        )
        val firstSession = RealSshSession(firstClient)
        firstSession.close()
        // Second close on an already-disconnected client: raises a
        // raw SocketException (the underlying socket is gone).
        val secondClient = ThrowingDisconnectClient(
            toThrow = SocketException("Software caused connection abort"),
        )
        val secondSession = RealSshSession(secondClient)

        secondSession.close() // must not throw

        assertEquals(1, firstClient.disconnectCallCount)
        assertEquals(1, secondClient.disconnectCallCount)
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
