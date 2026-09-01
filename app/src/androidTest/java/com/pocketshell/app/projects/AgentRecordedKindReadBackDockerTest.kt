package com.pocketshell.app.projects

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.uikit.model.SessionAgentKind
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #852: connected read-back proof for recorded agent kind on production
 * agent launches. This replaces the deleted broad agent-kind journeys with one
 * focused Docker-gated path:
 *
 *  1. launch Claude, Codex, and OpenCode through [SshFolderListGateway.createSession]
 *     using the same short registry-wrapper line the app types;
 *  2. wait for the fixture `pocketshell agent` wrapper to write host-side
 *     `@ps_agent_kind`;
 *  3. re-read via [SshFolderListGateway.listSessionsWithFolder], the same
 *     authoritative read-back path the tree uses.
 *
 * No app fake is involved: the test drives the deterministic `agents` Docker
 * fixture already used by the connected journey suite.
 */
@RunWith(AndroidJUnit4::class)
class AgentRecordedKindReadBackDockerTest {
    private lateinit var trustedHostKeySha256: String

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val cleanupCommands = mutableListOf<String>()

    @Before
    fun setUp(): Unit { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keyText = instrumentation.context.assets.open("test_key")
            .bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = instrumentation.targetContext.cacheDir
        keyFile = File(cacheDir, "issue852-kind-readback-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        trustedHostKeySha256 = waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        if (cleanupCommands.isNotEmpty()) {
            runCatching {
                withTimeout(15_000) {
                    withSshSession { session ->
                        session.exec(cleanupCommands.joinToString("\n"))
                    }
                }
            }
        }
        runCatching { keyFile.delete() }
    } }

    @Test
    fun launchedAgentsRecordKindAndGatewayReadsBackTheSameKind(): Unit { runBlocking {
        val gateway = SshFolderListGateway()
        val host = dockerHost()
        val suffix = System.currentTimeMillis().toString().takeLast(8)
        val cwd = "/tmp/issue852-kind-$suffix"
        cleanupCommands += "rm -rf ${shellQuote(cwd)} 2>/dev/null || true"
        ensureRemoteDir(cwd)

        data class Case(
            val engine: RemoteEngine,
            val rawKind: String,
            val expected: SessionAgentKind,
        )

        val cases = listOf(
            Case(pickerTestEngine("claude", SessionAgentKind.Claude), rawKind = "claude", expected = SessionAgentKind.Claude),
            Case(pickerTestEngine("codex", SessionAgentKind.Codex), rawKind = "codex", expected = SessionAgentKind.Codex),
            Case(pickerTestEngine("opencode", SessionAgentKind.OpenCode), rawKind = "opencode", expected = SessionAgentKind.OpenCode),
        )

        for (case in cases) {
            val sessionName = "issue852-${case.rawKind}-$suffix"
            cleanupCommands += "tmux kill-session -t ${shellQuote(sessionName)} 2>/dev/null || true"
            val choice = SessionTypeChoice(
                type = SessionType.Agent,
                engine = case.engine,
                startDirectory = cwd,
                skipPermissions = true,
            )
            assertEquals(case.rawKind, choice.engineId)
            assertEquals(case.expected, choice.sessionAgentKind)
            val startCommand = choice.startCommand()!!

            withTimeout(30_000) {
                gateway.createSession(
                    host = host,
                    keyPath = keyFile.absolutePath,
                    passphrase = null,
                    sessionName = sessionName,
                    cwd = cwd,
                    startCommand = startCommand,
                    namePolicy = SessionNamePolicy.UniqueOnHost,
                ).getOrThrow()
            }

            assertEquals(
                "fixture wrapper must record @ps_agent_kind for ${case.engine.id}",
                case.rawKind,
                awaitRecordedKindOption(sessionName, case.rawKind),
            )

            val row = readSessionRow(gateway, host, sessionName)
            assertEquals(
                "gateway must read back recordedKind for ${case.engine.id}",
                case.expected,
                row.recordedKind,
            )
            assertEquals(
                "recorded kind must drive the authoritative rendered kind for ${case.engine.id}",
                case.expected,
                row.agentKind,
            )
        }
    } }

    private suspend fun ensureRemoteDir(path: String) {
        withTimeout(15_000) {
            withSshSession { session ->
                session.exec("mkdir -p ${shellQuote(path)}")
            }
        }
    }

    private suspend fun awaitRecordedKindOption(sessionName: String, expected: String): String =
        withTimeout(30_000) {
            var raw = ""
            repeat(60) {
                raw = withSshSession { session ->
                    session.exec(
                        "tmux show-options -v -t ${shellQuote(sessionName)} @ps_agent_kind 2>/dev/null || true",
                    ).stdout.trim()
                }
                if (raw == expected) return@withTimeout raw
                delay(500)
            }
            raw
        }

    private suspend fun readSessionRow(
        gateway: SshFolderListGateway,
        host: HostEntity,
        sessionName: String,
    ): FolderSessionRow {
        val result = withTimeout(30_000) {
            gateway.listSessionsWithFolder(host, keyFile.absolutePath, null)
        }
        assertTrue("expected Sessions result, got $result", result is FolderListResult.Sessions)
        return (result as FolderListResult.Sessions).rows
            .firstOrNull { it.sessionName == sessionName }
            ?: error("gateway did not return session '$sessionName'; rows=${result.rows}")
    }

    private suspend fun <T> withSshSession(block: suspend (SshSession) -> T): T {
        val session = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = sshKey,
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).getOrThrow()
        return session.use { block(it) }
    }

    private fun dockerHost(): HostEntity = HostEntity(
        id = 852L,
        name = "issue852-agents",
        hostname = DEFAULT_HOST,
        port = DEFAULT_PORT,
        username = DEFAULT_USER,
        keyId = 1L,
        trustedHostKeySha256 = trustedHostKeySha256,
    )

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}
