package com.pocketshell.core.ssh

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * Issue #1185: a session the user SELECTED that merely COALESCED (#620) onto an
 * in-flight connect owned by a just-superseded create/attach flow must be able to
 * tell a superseded-owner cancellation apart from a genuine unreachable failure.
 *
 * These pin the TYPED delivery contract the consumer keys off:
 *  - owner CANCELLATION wakes a joined awaiter with the typed
 *    [SshLeaseConnectCoalescedCancelException] (a retryable failure), NOT a bare
 *    [CancellationException] value — so the consumer re-dials the selected session
 *    rather than stranding it (the #1185 Disconnected + "Attaching…" bug);
 *  - owner FAILURE (a genuine connect error) still wakes the awaiter with the
 *    ORIGINAL failure, NOT the coalesced-cancel type — so a real unreachable still
 *    surfaces the honest terminal error (no infinite re-dial);
 *  - the owner-cancel path publishes a distinct
 *    [SshLeaseCloseReason.ConnectCancelled] edge so the shareable log (#1175)
 *    fingerprints the cancel instead of an anonymous `lease_down`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SshLeaseCoalescedCancelTypedTest {

    @Test
    fun `owner cancellation wakes a joined awaiter with the typed coalesced-cancel failure`() = runTest {
        // The owner's connect parks in the gate; a second acquire for the SAME
        // key JOINS it (coalesces). Cancelling the owner (the create-then-switch
        // supersede) must wake the awaiter with the TYPED failure so the consumer
        // can distinguish it from a genuine unreachable.
        val connector = GatedLeaseConnector(FakeSshSession())
        val manager = leaseManager(connector)

        val owner = async { manager.acquire(TARGET) }
        runCurrent()
        assertEquals("owner started the only dial", 1, connector.startedConnects)

        val awaiter = async { manager.acquire(TARGET) }
        runCurrent()
        assertEquals("the awaiter coalesced onto the in-flight connect", 1, connector.startedConnects)

        owner.cancel()
        owner.join()
        runCurrent()

        val awaiterResult = awaiter.await()
        assertTrue("the coalescing awaiter is woken with a failure", awaiterResult.isFailure)
        val error = awaiterResult.exceptionOrNull()
        assertTrue(
            "the awaiter must receive the TYPED coalesced-cancel, got ${error?.javaClass?.name}",
            error is SshLeaseConnectCoalescedCancelException,
        )
        assertFalse(
            "the typed coalesced-cancel must NOT be a CancellationException (the consumer would " +
                "misclassify it as a terminal failure)",
            error is CancellationException,
        )
        assertEquals(
            "the coalesced-cancel carries the awaiter's lease key",
            TARGET.leaseKey,
            (error as SshLeaseConnectCoalescedCancelException).key,
        )
    }

    @Test
    fun `owner cancellation publishes a distinct ConnectCancelled close edge`() = runTest {
        val connector = GatedLeaseConnector(FakeSshSession())
        val manager = leaseManager(connector)

        val events = mutableListOf<SshLeaseStateEvent>()
        val collector = launch { manager.stateEvents.collect { events.add(it) } }
        runCurrent()

        val owner = async { manager.acquire(TARGET) }
        runCurrent()
        assertTrue(
            "the optimistic Connecting hint is announced",
            events.any {
                it.key == TARGET.leaseKey && it.state == SshLeaseConnectionState.Connecting
            },
        )

        owner.cancel()
        owner.join()
        runCurrent()

        assertTrue(
            "owner cancellation must publish a Closed edge with the distinct ConnectCancelled reason " +
                "(so #1175 fingerprints the cancel, not an anonymous lease_down); saw: " +
                events.joinToString { "${it.state}/${it.closeReason}" },
            events.any {
                it.key == TARGET.leaseKey &&
                    it.state == SshLeaseConnectionState.Closed &&
                    it.closeReason == SshLeaseCloseReason.ConnectCancelled
            },
        )
        collector.cancel()
    }

    @Test
    fun `a genuine owner connect failure is NOT reported as a coalesced-cancel`() = runTest {
        // The owner's connect RESOLVES as a failure (a genuine unreachable/auth/
        // DNS error), not a cancellation. The joined awaiter must receive the
        // ORIGINAL failure — never the coalesced-cancel type — so the consumer
        // still surfaces the honest terminal error and does NOT re-dial forever.
        val connector = GatedLeaseConnector(/* null slot => the connect fails */ null)
        val manager = leaseManager(connector)

        val owner = async { manager.acquire(TARGET) }
        runCurrent()
        val awaiter = async { manager.acquire(TARGET) }
        runCurrent()
        assertEquals("the awaiter coalesced onto the in-flight connect", 1, connector.startedConnects)

        connector.releaseConnect() // resolve the owning connect as a FAILURE
        runCurrent()

        val ownerResult = owner.await()
        val awaiterResult = awaiter.await()
        assertTrue("the owner surfaces the genuine connect failure", ownerResult.isFailure)
        assertTrue("the awaiter is woken with the shared failure", awaiterResult.isFailure)
        assertFalse(
            "a genuine connect failure must NOT masquerade as a coalesced-cancel " +
                "(otherwise a real unreachable would loop re-dialing)",
            awaiterResult.exceptionOrNull() is SshLeaseConnectCoalescedCancelException,
        )
        assertTrue(
            "the awaiter receives the ORIGINAL IO failure, got ${awaiterResult.exceptionOrNull()?.javaClass?.name}",
            awaiterResult.exceptionOrNull() is IOException,
        )
    }

    // ---- harness (mirrors SshLeaseCoalescingCharacterizationTest's local fakes) ----

    private fun TestScope.leaseManager(
        connector: SshLeaseConnector,
        idleTtlMillis: Long = 60_000,
        maxIdleLeases: Int = 2,
    ): SshLeaseManager =
        SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = idleTtlMillis,
            maxIdleLeases = maxIdleLeases,
            connectTimeoutContext = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            abortTimeoutContext = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        )

    /**
     * Connector whose every `connect` parks until the test releases it (or the
     * owner is cancelled), so a test can observe the in-flight coalescing window.
     * A queued session resolves successfully; a `null` slot resolves as a genuine
     * connect failure.
     */
    private class GatedLeaseConnector(
        private vararg val sessions: FakeSshSession?,
    ) : SshLeaseConnector {
        var startedConnects: Int = 0
        private val gates: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque()

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val index = startedConnects
            startedConnects += 1
            val gate = CompletableDeferred<Unit>()
            gates.addLast(gate)
            try {
                gate.await()
            } finally {
                gates.remove(gate)
            }
            val session = sessions.getOrNull(index)
                ?: return Result.failure(IOException("connect $index failed"))
            return Result.success(session)
        }

        fun releaseConnect() {
            val gate = gates.firstOrNull()
                ?: error("no in-flight connect to release")
            gate.complete(Unit)
        }
    }

    private class FakeSshSession : SshSession {
        var closed: Boolean = false
        var connected: Boolean = true

        override val isConnected: Boolean
            get() = connected && !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = object : SshShell {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(ByteArray(0))
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun close() = Unit
        }

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closed = true
        }
    }

    private companion object {
        val TARGET: SshLeaseTarget = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "10.0.2.2",
                port = 2222,
                user = "testuser",
                credentialId = "/tmp/key-a",
            ),
            key = SshKey.Path(File("/tmp/key-a")),
        )
    }
}
