package com.pocketshell.app.sessions

import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.tmux.FakeTmuxClient
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.tmux.CommandResponse
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream

class HostTmuxSessionsGatewayTest {
    private val parser = HostTmuxSessionListParser()
    private val activeTmuxClients = ActiveTmuxClients()

    @Before
    fun resetTelemetry() {
        SshOpenTelemetry.resetForTest()
    }

    @Test
    fun sameHostLiveClientListsSessionsWithoutOpeningSsh() = runTest {
        val client = FakeTmuxClient()
        client.responses += CommandResponse(
            number = 1L,
            output = listOf(
                "beta::101::301::1",
                "alpha::100::300::0",
            ),
            isError = false,
        )
        activeTmuxClients.register(
            hostId = HOST.id,
            hostName = HOST.name,
            hostname = HOST.hostname,
            port = HOST.port,
            username = HOST.username,
            keyPath = KEY_PATH,
            client = client,
        )
        val gateway = SshHostTmuxSessionsGateway(parser, activeTmuxClients)

        val result = gateway.listSessions(HOST, KEY_PATH, passphrase = null)

        assertTrue(result is HostTmuxSessionListResult.Sessions)
        val rows = (result as HostTmuxSessionListResult.Sessions).rows
        assertEquals(listOf("beta", "alpha"), rows.map { it.name })
        assertEquals(0, SshOpenTelemetry.count(SSH_SOURCE_SESSION_PICKER_LIST))
        assertEquals(
            listOf("list-sessions -F '#{session_name}::#{session_created}::#{session_activity}::#{session_attached}'"),
            client.sentCommands,
        )
    }

    @Test
    fun pickerListKeepsAndReusesLeaseSessionAcrossLoads() = runTest {
        val session = FakeSshSession(
            responses = ArrayDeque(
                listOf(
                    ExecResult(stdout = "", stderr = "not found", exitCode = 127),
                    ExecResult(stdout = "alpha::100::300::0\n", stderr = "", exitCode = 0),
                    ExecResult(stdout = "", stderr = "not found", exitCode = 127),
                    ExecResult(stdout = "beta::101::301::1\n", stderr = "", exitCode = 0),
                ),
            ),
        )
        val connector = CountingConnector(Result.success(session))
        val manager = SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 30_000L,
        )
        val gateway = SshHostTmuxSessionsGateway(parser, activeTmuxClients, manager)

        try {
            val first = gateway.listSessions(HOST, KEY_PATH, passphrase = null)
            val second = gateway.listSessions(HOST, KEY_PATH, passphrase = null)

            assertTrue(first is HostTmuxSessionListResult.Sessions)
            assertTrue(second is HostTmuxSessionListResult.Sessions)
            assertEquals(listOf("alpha"), (first as HostTmuxSessionListResult.Sessions).rows.map { it.name })
            assertEquals(listOf("beta"), (second as HostTmuxSessionListResult.Sessions).rows.map { it.name })
            assertEquals(1, connector.connectCount)
            assertEquals(HOST.hostname, connector.targets.single().leaseKey.host)
            assertEquals(HOST.port, connector.targets.single().leaseKey.port)
            assertEquals(HOST.username, connector.targets.single().leaseKey.user)
            assertEquals("${HOST.id}:$KEY_PATH", connector.targets.single().leaseKey.credentialId)
            assertEquals("accept-all", connector.targets.single().leaseKey.knownHostsId)
            assertFalse(session.closed)
            assertEquals(
                listOf(
                    "pocketshell sessions list --by activity",
                    "tmux list-sessions -F '#{session_name}::#{session_created}::#{session_activity}::#{session_attached}'",
                    "pocketshell sessions list --by activity",
                    "tmux list-sessions -F '#{session_name}::#{session_created}::#{session_activity}::#{session_attached}'",
                ).map { ReposRemoteSource.pathAwareCommand(it) },
                session.execCommands,
            )
        } finally {
            manager.close()
        }
    }

    @Test
    fun connectFailureMapsToConnectFailed() = runTest {
        val cause = SshException("connection refused")
        val gateway = SshHostTmuxSessionsGateway(
            parser = parser,
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = SshLeaseManager(
                connector = CountingConnector(Result.failure(cause)),
                scope = this,
                idleTtlMillis = 0L,
            ),
        )

        val result = gateway.listSessions(HOST, KEY_PATH, passphrase = null)

        assertTrue(result is HostTmuxSessionListResult.ConnectFailed)
        assertEquals(cause, (result as HostTmuxSessionListResult.ConnectFailed).cause)
    }

    @Test
    fun cancelledPickerListPropagatesCancellationAndReleasesLease() = runTest {
        val session = FakeSshSession(cancelOnExec = true)
        val gateway = SshHostTmuxSessionsGateway(
            parser = parser,
            activeTmuxClients = activeTmuxClients,
            sshLeaseManager = SshLeaseManager(
                connector = CountingConnector(Result.success(session)),
                scope = this,
                idleTtlMillis = 0L,
            ),
        )

        val thrown =
            try {
                gateway.listSessions(HOST, KEY_PATH, passphrase = null)
                null
            } catch (e: CancellationException) {
                e
            }

        assertEquals("cancelled during picker list", thrown?.message)
        assertTrue("cancelled picker list should release and close the lease", session.closed)
    }

    private class CountingConnector(
        private val result: Result<SshSession>,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
        val targets: MutableList<SshLeaseTarget> = mutableListOf()

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            targets += target
            return result
        }
    }

    private class FakeSshSession(
        private val responses: ArrayDeque<ExecResult> = ArrayDeque(),
        private val cancelOnExec: Boolean = false,
    ) : SshSession {
        val execCommands: MutableList<String> = mutableListOf()
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            if (cancelOnExec) {
                throw CancellationException("cancelled during picker list")
            }
            execCommands += command
            return responses.removeFirstOrNull()
                ?: ExecResult(stdout = "", stderr = "", exitCode = 0)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("not used")

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
        const val KEY_PATH: String = "/tmp/pocketshell-test-key"
        val HOST: HostEntity = HostEntity(
            id = 42L,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 7L,
        )
    }
}
