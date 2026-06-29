package com.pocketshell.app.env

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Verifies the env gateway reuses the app-wide warm lease across operations
 * (#699): two env reads against the same host pay ONE SSH handshake and never
 * close the shared transport.
 */
class SshEnvGatewayLeaseTest {

    @Test
    fun twoEnvReadsReuseOneWarmTransport() = runTest {
        val session = FakeSshSession { command ->
            // `env list --json` returns an empty array for both calls.
            ExecResult(stdout = "[]", stderr = "", exitCode = 0)
        }
        val connector = CountingConnector(session)
        val gateway = SshEnvGateway(
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 30_000L),
        )

        val first = gateway.listKeys(HOST, KEY_PATH, passphrase = null, directory = "/home/dev/proj")
        val second = gateway.listKeys(HOST, KEY_PATH, passphrase = null, directory = "/home/dev/proj")

        assertTrue(first is EnvListResult.Keys)
        assertTrue(second is EnvListResult.Keys)
        assertEquals("two env reads must share ONE warm transport", 1, connector.connectCount)
        assertFalse("env op must not close the shared transport", session.closed)
    }

    @Test
    fun editingValueTravelsViaStdinNeverArgv() = runTest {
        // #1092 / D24: editing an existing key's value reuses setKeys, so the
        // new secret VALUE must arrive over stdin (the uploaded JSON payload),
        // never on the `env set` command line / argv.
        val secret = "edited-sk-SECRET-1092"
        val execCommands = mutableListOf<String>()
        val uploads = mutableListOf<String>()
        val session = RecordingSession(
            onExec = { command ->
                execCommands += command
                ExecResult(stdout = "", stderr = "", exitCode = 0)
            },
            onUpload = { bytes -> uploads += bytes },
        )
        val connector = CountingConnector(session)
        val gateway = SshEnvGateway(
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 30_000L),
        )

        val result = gateway.setKeys(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            directory = "/home/dev/proj",
            file = EnvFileTarget.Env,
            updates = mapOf("API_KEY" to secret),
        )

        assertTrue(result is EnvOpResult.Success)
        // The value arrived over stdin (uploaded JSON), and NOT on any argv.
        assertTrue("value must travel via the uploaded stdin payload", uploads.any { it.contains(secret) })
        assertTrue(
            "no exec command line may contain the secret value (D24)",
            execCommands.none { it.contains(secret) },
        )
        assertTrue("at least one exec ran", execCommands.isNotEmpty())
    }

    @Test
    fun connectFailureSurfacesConnectFailed() = runTest {
        val connector = object : SshLeaseConnector {
            override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
                Result.failure(java.net.ConnectException("ECONNREFUSED"))
        }
        val gateway = SshEnvGateway(
            sshLeaseManager = SshLeaseManager(connector = connector, scope = this, idleTtlMillis = 30_000L),
        )

        val result = gateway.listKeys(HOST, KEY_PATH, passphrase = null, directory = "/home/dev/proj")

        assertTrue(result is EnvListResult.ConnectFailed)
    }

    private class CountingConnector(private val session: SshSession) : SshLeaseConnector {
        var connectCount: Int = 0
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
    }

    private class RecordingSession(
        private val onExec: (String) -> ExecResult,
        private val onUpload: (String) -> Unit,
    ) : SshSession {
        var closed: Boolean = false
        override val isConnected: Boolean get() = !closed
        override suspend fun exec(command: String): ExecResult = onExec(command)
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(input: InputStream, length: Long, name: String, remotePath: String): String {
            onUpload(input.readBytes().toString(Charsets.UTF_8))
            return remotePath
        }
        override fun close() {
            closed = true
        }
    }

    private class FakeSshSession(
        private val resultForCommand: (String) -> ExecResult,
    ) : SshSession {
        var closed: Boolean = false
        override val isConnected: Boolean get() = !closed
        override suspend fun exec(command: String): ExecResult = resultForCommand(command)
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(input: InputStream, length: Long, name: String, remotePath: String): String =
            error("not used")
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
