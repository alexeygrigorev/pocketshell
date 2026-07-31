package com.pocketshell.core.ssh

import kotlinx.coroutines.Job
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.InetAddress

class SshAddressFallbackTest {

    @Test
    fun `planner deliberately prefers IPv6 then IPv4 and removes duplicates stably`() {
        val v4First = InetAddress.getByAddress("mixed.test", byteArrayOf(127, 0, 0, 1))
        val v6 = InetAddress.getByAddress(
            "mixed.test",
            byteArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 2),
        )
        val v4Second = InetAddress.getByAddress("mixed.test", byteArrayOf(127, 0, 0, 2))

        val planned = SshDialPlanner.plan(
            host = "mixed.test",
            resolved = listOf(v4First, v6, v4First, v4Second),
        )

        assertEquals(
            listOf("IPv6[0:0:0:0:0:0:0:2]", "IPv4[127.0.0.1]", "IPv4[127.0.0.2]"),
            planned.map { it.diagnosticLabel },
        )
    }

    @Test
    fun `first address failure is disconnected before one second address dial starts`() = runTest {
        val connector = RecordingFallbackConnector(
            outcomes = ArrayDeque(listOf(IOException("v6 unreachable"), null)),
        )

        val result = SshConnection.connect(
            host = "dual.test",
            port = 22,
            user = "me",
            key = SshKey.Pem("key"),
            timeoutMs = 30_000,
            connector = connector,
            nowNanos = { 0L },
        )

        assertTrue("second address should return a live session: $result", result.isSuccess)
        result.getOrThrow().close()
        assertEquals(
            listOf(
                "create:IPv6[2001:db8:0:0:0:0:0:1]",
                "connect:1:10000",
                "disconnect:1",
                "create:IPv4[192.0.2.1]",
                "connect:2:10000",
                "auth:2:30000",
                "session:2",
                "session-close:2",
            ),
            connector.events,
        )
        assertEquals(1, connector.maxConnectsInFlight)
    }

    @Test
    fun `all address failures report every family and cause within one total budget`() = runTest {
        var nowNanos = 0L
        val connector = RecordingFallbackConnector(
            outcomes = ArrayDeque(listOf(IOException("v6 blackhole"), IOException("v4 refused"))),
            afterConnect = { timeoutMs -> nowNanos += timeoutMs.toLong() * 1_000_000L },
        )

        val result = SshConnection.connect(
            host = "dual.test",
            port = 2222,
            user = "me",
            key = SshKey.Pem("key"),
            timeoutMs = 8_000,
            connector = connector,
            nowNanos = { nowNanos },
        )

        assertTrue(result.isFailure)
        val message = result.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("IPv6[2001:db8:0:0:0:0:0:1]=IOException: v6 blackhole"))
        assertTrue(message, message.contains("IPv4[192.0.2.1]=IOException: v4 refused"))
        assertEquals(listOf(4_000, 4_000), connector.connectTimeouts)
        assertEquals(8_000_000_000L, nowNanos)
        assertEquals(1, connector.maxConnectsInFlight)
    }

    @Test
    fun `authentication failure does not create a duplicate address dial`() = runTest {
        val connector = RecordingFallbackConnector(
            outcomes = ArrayDeque(listOf(null, null)),
            authenticationFailure = IOException("key rejected"),
        )

        val result = SshConnection.connect(
            host = "dual.test",
            port = 22,
            user = "me",
            key = SshKey.Pem("key"),
            timeoutMs = 30_000,
            connector = connector,
            nowNanos = { 0L },
        )

        assertTrue(result.isFailure)
        assertEquals(1, connector.clientsCreated)
        assertFalse(connector.events.any { it == "create:IPv4[192.0.2.1]" })
    }

    @Test
    fun `non retryable trust failure stops before a second address`() = runTest {
        val trustFailure = SshException("host key rejected")
        val connector = RecordingFallbackConnector(
            outcomes = ArrayDeque(listOf(trustFailure, null)),
            retryable = { false },
        )

        val result = SshConnection.connect(
            host = "dual.test",
            port = 22,
            user = "me",
            key = SshKey.Pem("key"),
            timeoutMs = 30_000,
            connector = connector,
            nowNanos = { 0L },
        )

        assertEquals(trustFailure, result.exceptionOrNull())
        assertEquals(1, connector.clientsCreated)
        assertEquals(listOf(10_000), connector.connectTimeouts)
    }

    @Test
    fun `cancellation during first address disconnects it and never starts fallback`() = runTest {
        val connector = CancellingFallbackConnector()
        val job = launch {
            SshConnection.connect(
                host = "dual.test",
                port = 22,
                user = "me",
                key = SshKey.Pem("key"),
                timeoutMs = 30_000,
                connector = connector,
                nowNanos = { 0L },
            )
        }

        connector.connectEntered.await()
        job.cancelAndJoin()
        connector.disconnectCalled.await()

        assertEquals(1, connector.clientsCreated)
        assertEquals(1, connector.connectCalls)
    }

    private class RecordingFallbackConnector(
        outcomes: Collection<Throwable?>,
        private val afterConnect: (Int) -> Unit = {},
        private val authenticationFailure: IOException? = null,
        private val retryable: (Throwable) -> Boolean = { true },
    ) : SshConnection.SshConnector<FakeClient> {
        private val outcomes = ArrayDeque(outcomes)
        val events = mutableListOf<String>()
        val connectTimeouts = mutableListOf<Int>()
        var clientsCreated = 0
        var connectsInFlight = 0
        var maxConnectsInFlight = 0

        private val targets = listOf(
            SshDialTarget(
                InetAddress.getByAddress(byteArrayOf(0x20, 0x01, 0x0d, 0xb8.toByte(), 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1)),
                "IPv6[2001:db8:0:0:0:0:0:1]",
            ),
            SshDialTarget(InetAddress.getByAddress(byteArrayOf(192.toByte(), 0, 2, 1)), "IPv4[192.0.2.1]"),
        )

        override fun resolve(host: String): List<SshDialTarget> = targets

        override fun createClient(): FakeClient = error("target-aware factory required")

        override fun createClient(target: SshDialTarget): FakeClient {
            val client = FakeClient(++clientsCreated, target)
            events += "create:${target.diagnosticLabel}"
            return client
        }

        override fun applyKnownHostsPolicy(client: FakeClient, policy: KnownHostsPolicy) = Unit

        override suspend fun connect(client: FakeClient, host: String, port: Int, timeoutMs: Int) {
            connectsInFlight += 1
            maxConnectsInFlight = maxOf(maxConnectsInFlight, connectsInFlight)
            connectTimeouts += timeoutMs
            events += "connect:${client.id}:$timeoutMs"
            val failure = outcomes.removeFirst()
            afterConnect(timeoutMs)
            connectsInFlight -= 1
            if (failure != null) throw failure
        }

        override fun prepareAuthentication(client: FakeClient, timeoutMs: Int) {
            events += "auth:${client.id}:$timeoutMs"
        }

        override fun isAddressFailureRetryable(failure: Throwable): Boolean = retryable(failure)

        override suspend fun authenticate(client: FakeClient, user: String, key: SshKey, passphrase: CharArray?) {
            authenticationFailure?.let { throw it }
        }

        override fun toSession(client: FakeClient): SshSession {
            events += "session:${client.id}"
            return FakeSession(client, events)
        }

        override fun disconnect(client: FakeClient) {
            events += "disconnect:${client.id}"
        }
    }

    private data class FakeClient(val id: Int, val target: SshDialTarget)

    private class CancellingFallbackConnector : SshConnection.SshConnector<FakeClient> {
        val connectEntered = CompletableDeferred<Unit>()
        val disconnectCalled = CompletableDeferred<Unit>()
        var clientsCreated = 0
        var connectCalls = 0

        override fun resolve(host: String): List<SshDialTarget> = listOf(
            SshDialTarget(null, "IPv6[first]"),
            SshDialTarget(null, "IPv4[second]"),
        )

        override fun createClient(): FakeClient = error("target-aware factory required")

        override fun createClient(target: SshDialTarget): FakeClient =
            FakeClient(++clientsCreated, target)

        override fun applyKnownHostsPolicy(client: FakeClient, policy: KnownHostsPolicy) = Unit

        override suspend fun connect(client: FakeClient, host: String, port: Int, timeoutMs: Int) {
            connectCalls += 1
            connectEntered.complete(Unit)
            CompletableDeferred<Unit>().await()
        }

        override suspend fun authenticate(client: FakeClient, user: String, key: SshKey, passphrase: CharArray?) = Unit

        override fun toSession(client: FakeClient): SshSession = error("not reached")

        override fun disconnect(client: FakeClient) {
            disconnectCalled.complete(Unit)
        }
    }

    private class FakeSession(
        private val client: FakeClient,
        private val events: MutableList<String>,
    ) : SshSession {
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult = error("not used")
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("not used")
        override fun startShell(): SshShell = object : SshShell {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(ByteArray(0))
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun close() = Unit
        }
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(input: java.io.InputStream, length: Long, name: String, remotePath: String): String =
            error("not used")
        override fun close() {
            events += "session-close:${client.id}"
        }
    }
}
