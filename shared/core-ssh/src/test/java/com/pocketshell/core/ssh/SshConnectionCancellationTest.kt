package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class SshConnectionCancellationTest {

    @Test
    fun `cancel during handshake disconnects the partially owned client`() = runTest {
        val connector = FakeConnector(connectMode = ConnectMode.HangUntilCancelled)
        val job = launch {
            SshConnection.connect(
                host = "example.test",
                port = 22,
                user = "me",
                key = SshKey.Pem("key"),
                connector = connector,
            )
        }

        connector.connectEntered.await()
        job.cancelAndJoin()

        assertTrue(connector.client.disconnectEntered.await(5, TimeUnit.SECONDS))
        assertEquals(1, connector.client.disconnectCount)
        assertTrue(connector.client.closed)
    }

    @Test
    fun `cancel during handshake does not wait for a blocking disconnect cleanup`() = runTest {
        val connector = FakeConnector(
            connectMode = ConnectMode.HangUntilCancelled,
            blockDisconnect = true,
        )
        val job = launch {
            SshConnection.connect(
                host = "example.test",
                port = 22,
                user = "me",
                key = SshKey.Pem("key"),
                connector = connector,
            )
        }

        connector.connectEntered.await()
        job.cancel()

        withTimeout(1_000L) {
            job.join()
        }
        assertTrue(
            "disconnect cleanup should be running off the cancellation path",
            connector.client.disconnectEntered.await(5, TimeUnit.SECONDS),
        )

        connector.client.allowDisconnectToFinish.countDown()
        assertTrue(connector.client.disconnectFinished.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun `late success after parent cancellation closes the delivered session`() = runTest {
        val connector = FakeConnector(connectMode = ConnectMode.IgnoreCancellationUntilReleased)
        val job = launch {
            SshConnection.connect(
                host = "example.test",
                port = 22,
                user = "me",
                key = SshKey.Pem("key"),
                connector = connector,
            )
        }

        connector.connectEntered.await()
        job.cancel()
        connector.releaseConnect.complete(Unit)
        job.join()

        assertTrue(connector.client.disconnectEntered.await(5, TimeUnit.SECONDS))
        assertTrue("expected cancellation cleanup to disconnect the client", connector.client.disconnectCount >= 1)
        assertTrue("expected late success session to be closed", connector.client.sessionClosed)
    }

    @Test
    fun `connect failure disconnects the partially owned client and returns failure`() = runTest {
        val connector = FakeConnector(connectMode = ConnectMode.FailAuthentication)

        val result = SshConnection.connect(
            host = "example.test",
            port = 22,
            user = "me",
            key = SshKey.Pem("key"),
            connector = connector,
        )

        assertTrue("expected failure, got $result", result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertEquals(1, connector.client.disconnectCount)
        assertTrue(connector.client.closed)
    }

    private enum class ConnectMode {
        HangUntilCancelled,
        IgnoreCancellationUntilReleased,
        FailAuthentication,
    }

    private class FakeConnector(
        private val connectMode: ConnectMode,
        private val blockDisconnect: Boolean = false,
    ) : SshConnection.SshConnector<FakeClient> {
        val client = FakeClient(blockDisconnect)
        val connectEntered = CompletableDeferred<Unit>()
        val releaseConnect = CompletableDeferred<Unit>()

        override fun createClient(): FakeClient = client

        override fun applyKnownHostsPolicy(client: FakeClient, policy: KnownHostsPolicy) {
            client.knownHostsApplied = true
        }

        override suspend fun connect(
            client: FakeClient,
            host: String,
            port: Int,
            timeoutMs: Int,
        ) {
            connectEntered.complete(Unit)
            when (connectMode) {
                ConnectMode.HangUntilCancelled -> CompletableDeferred<Unit>().await()
                ConnectMode.IgnoreCancellationUntilReleased -> withContext(NonCancellable) {
                    releaseConnect.await()
                }
                ConnectMode.FailAuthentication -> Unit
            }
        }

        override suspend fun authenticate(
            client: FakeClient,
            user: String,
            key: SshKey,
            passphrase: CharArray?,
        ) {
            if (connectMode == ConnectMode.FailAuthentication) {
                throw IOException("auth failed")
            }
            client.authenticated = true
        }

        override fun toSession(client: FakeClient): SshSession = FakeSession(client)

        override fun disconnect(client: FakeClient) {
            client.disconnect()
        }
    }

    private class FakeClient(
        private val blockDisconnect: Boolean = false,
    ) {
        @Volatile var knownHostsApplied: Boolean = false
        @Volatile var authenticated: Boolean = false
        @Volatile var closed: Boolean = false
        @Volatile var sessionClosed: Boolean = false
        @Volatile var disconnectCount: Int = 0
        val disconnectEntered = CountDownLatch(1)
        val allowDisconnectToFinish = CountDownLatch(1)
        val disconnectFinished = CountDownLatch(1)

        fun disconnect() {
            disconnectEntered.countDown()
            if (blockDisconnect) {
                allowDisconnectToFinish.await(30, TimeUnit.SECONDS)
            }
            disconnectCount += 1
            closed = true
            disconnectFinished.countDown()
        }
    }

    private class FakeSession(
        private val client: FakeClient,
    ) : SshSession {
        override val isConnected: Boolean = !client.closed

        override suspend fun exec(command: String): ExecResult = error("not used")

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

        override suspend fun uploadFile(file: java.io.File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: java.io.InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            client.sessionClosed = true
            client.disconnect()
        }
    }
}
