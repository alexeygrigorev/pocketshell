package com.pocketshell.app.projects

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/** Lease, command, and bounded-failure tests for [SshEnginesGateway]. */
class SshEnginesGatewayTest {

    @Test
    fun listsEnginesThroughTheSharedLeaseWithOneBoundedCommand() = runBlocking {
        val session = FakeSshSession { command ->
            assertTrue(command.contains("engines list --json"))
            ExecResult(
                stdout = """{"engines":[{"id":"godex","family":"codex","label":"GoDex"}]}""",
                stderr = "",
                exitCode = 0,
            )
        }
        val connector = CountingConnector(session)
        val gateway = SshEnginesGateway(
            sshLeaseManager = SshLeaseManager(
                connector = connector,
                scope = this,
                idleTtlMillis = 30_000L,
            ),
        )

        val result = gateway.listEngines(HOST, KEY_PATH, passphrase = null)

        assertTrue(result is EnginesResult.Engines)
        val engines = (result as EnginesResult.Engines).engines
        assertEquals("godex", engines.single().rawId)
        assertEquals(com.pocketshell.uikit.model.SessionAgentKind.Codex, engines.single().family)
        assertEquals(1, connector.connectCount)
        assertEquals(1, session.execCommands.size)
    }

    @Test
    fun successfulReadPopulatesRawIdFamilyCacheAndFailureServesItWithoutDroppingRows() = runBlocking {
        var calls = 0
        val session = FakeSshSession {
            calls += 1
            if (calls == 1) {
                ExecResult(
                    stdout = """{"engines":[{"id":"custom-codex","family":"codex","label":"Custom Codex","enabled":false,"available":false}]}""",
                    stderr = "",
                    exitCode = 0,
                )
            } else {
                ExecResult("not json", "", 0)
            }
        }
        val gateway = SshEnginesGateway(
            sshLeaseManager = SshLeaseManager(
                connector = CountingConnector(session),
                idleTtlMillis = 30_000L,
            ),
        )

        assertEquals(SessionAgentKind.Codex, gateway.familyForRawId(HOST.id, "codex"))
        val first = gateway.listEngines(HOST, KEY_PATH, passphrase = null)
        val second = gateway.listEngines(HOST, KEY_PATH, passphrase = null)

        // Issue #2439: a failed read with a good cache already on file gets ONE
        // retry (call #3) before falling back — both attempts fail here (the
        // fake keeps returning malformed JSON after the first call), so the
        // fallback still serves the pre-failure cache, just one call later.
        assertEquals(3, calls)
        assertTrue(first is EnginesResult.Engines && !(first as EnginesResult.Engines).fromCache)
        assertTrue(second is EnginesResult.Engines && (second as EnginesResult.Engines).fromCache)
        assertEquals(listOf("custom-codex"), gateway.cachedEngines(HOST.id).map { it.rawId })
        assertEquals(SessionAgentKind.Codex, gateway.familyForRawId(HOST.id, "custom-codex"))
        assertEquals(false, gateway.cachedEngines(HOST.id).single().available)
    }

    @Test
    fun malformedJsonIsARecoverableGatewayFailure() = runBlocking {
        val gateway = gatewayFor { ExecResult("not json", "", 0) }

        val result = gateway.listEngines(HOST, KEY_PATH, passphrase = null)

        assertTrue(result is EnginesResult.Failed)
    }

    @Test
    fun missingCliIsReportedSeparately() = runBlocking {
        val gateway = gatewayFor { ExecResult("", "pocketshell: not found", 127) }

        val result = gateway.listEngines(HOST, KEY_PATH, passphrase = null)

        assertEquals(EnginesResult.ToolUnavailable, result)
    }

    @Test
    fun wedgedExecReturnsWithinTheInjectedBound() = runBlocking {
        val session = FakeSshSession { awaitCancellation() }
        val connector = CountingConnector(session)
        val gateway = SshEnginesGateway(
            sshLeaseManager = SshLeaseManager(
                connector = connector,
                scope = this,
                idleTtlMillis = 0L,
            ),
            execReadTimeoutMs = 40L,
        )

        val started = System.nanoTime()
        val result = gateway.listEngines(HOST, KEY_PATH, passphrase = null)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000L

        assertTrue(result is EnginesResult.Failed)
        assertTrue("bounded engine read took ${elapsedMs}ms", elapsedMs < 2_000L)
    }

    private fun gatewayFor(result: suspend (String) -> ExecResult): SshEnginesGateway {
        val session = FakeSshSession(result)
        return SshEnginesGateway(
            sshLeaseManager = SshLeaseManager(
                connector = CountingConnector(session),
                idleTtlMillis = 30_000L,
            ),
        )
    }

    private class CountingConnector(private val session: FakeSshSession) : SshLeaseConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
    }

    private class FakeSshSession(
        private val resultForCommand: suspend (String) -> ExecResult,
    ) : SshSession {
        val execCommands = mutableListOf<String>()

        override val isConnected: Boolean
            get() = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return resultForCommand(command)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() = Unit
    }

    private companion object {
        const val KEY_PATH = "/tmp/pocketshell-test-key"
        val HOST = HostEntity(
            id = 42L,
            name = "docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 7L,
        )
    }
}
