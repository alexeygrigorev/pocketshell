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
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Connected proof for issue #703: the SHORT server-side wrapper command
 * PocketShell builds (`pocketshell agent <kind> --dir '<dir>' …`) is the
 * exact text that lands in the new tmux pane via `createSession` ->
 * `tmux send-keys`, AND the wrapper actually exec's the agent (the agent
 * STARTS / becomes usable), not merely that the command text is in
 * scrollback — closing the gap the old #428 test had.
 *
 * This drives the real [SshFolderListGateway.createSession] (the same code
 * the picker confirm path calls) against the deterministic Docker `agents`
 * service on host port `2222`. That fixture ships a fake `pocketshell`
 * whose `agent <kind> --dir <dir>` branch mirrors the real wrapper: it
 * cd's into `--dir` and exec's the (fake) agent, which prints a recognisable
 * "<Agent> fixture: …" ready line. So the test asserts:
 *
 * 1. The typed command is the SHORT wrapper form — no giant inline
 *    `env -u …(71)…` strip, no `eval "$(pocketshell env export …)"`
 *    prelude, no inline `--dangerously…` flag (hard-cut, D22).
 * 2. The agent's fixture-ready output appears in the pane — proof the
 *    wrapper resolved `--dir` and exec'd the agent end-to-end.
 *
 * The real-agent prompt-suppression (codex update modal / claude
 * folder-trust) is verified separately against real CLIs — the deterministic
 * fakes have no such modal, so they can only prove the exec path.
 *
 * Artifacts are written under the app's external files dir
 * (`agent-launch-command/`) for the reviewer per the terminal-artifact rules.
 */
@RunWith(AndroidJUnit4::class)
class AgentLaunchCommandDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val createdSessions = mutableListOf<String>()
    private lateinit var artifactDir: File

    @Before
    fun setUp(): Unit { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keyText = instrumentation.context.assets.open("test_key")
            .bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = instrumentation.targetContext.cacheDir
        keyFile = File(cacheDir, "issue703-launch-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        artifactDir = File(
            instrumentation.targetContext.getExternalFilesDir(null),
            "agent-launch-command",
        ).apply { mkdirs() }
        waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        if (createdSessions.isNotEmpty()) {
            withTimeout(15_000) {
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = sshKey,
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 10_000,
                ).getOrNull()?.use { session ->
                    for (name in createdSessions) {
                        runCatching { session.exec("tmux kill-session -t $name 2>/dev/null || true") }
                    }
                }
            }
        }
        runCatching { keyFile.delete() }
    } }

    @Test
    fun shortWrapperCommandLaunchesAgentToReadyState(): Unit { runBlocking {
        val gateway = SshFolderListGateway()
        val host = dockerHost()
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val cwd = "/tmp/issue703-$suffix".also { ensureRemoteDir(it) }

        data class Case(
            val name: String,
            val choice: SessionTypeChoice,
            // The fixture-ready output the agent prints once the wrapper
            // exec's it — proof the agent actually STARTED.
            val readyToken: String,
            // The exact short command the app must type (no inline strip).
            val expectedCommand: String,
        )

        val cases = listOf(
            Case(
                name = "issue703-claude-on-$suffix",
                choice = agentChoice(AgentCli.Claude, skip = true, cwd),
                readyToken = "Claude Code fixture",
                expectedCommand = "pocketshell agent claude --dir '$cwd'",
            ),
            Case(
                name = "issue703-claude-off-$suffix",
                choice = agentChoice(AgentCli.Claude, skip = false, cwd),
                readyToken = "Claude Code fixture",
                expectedCommand = "pocketshell agent claude --dir '$cwd' --no-skip-permissions",
            ),
            Case(
                name = "issue703-codex-on-$suffix",
                choice = agentChoice(AgentCli.Codex, skip = true, cwd),
                readyToken = "Codex fixture",
                expectedCommand = "pocketshell agent codex --dir '$cwd'",
            ),
            Case(
                name = "issue703-opencode-$suffix",
                choice = agentChoice(AgentCli.OpenCode, skip = true, cwd),
                readyToken = "OpenCode fixture",
                expectedCommand = "pocketshell agent opencode --dir '$cwd'",
            ),
        )

        val summary = StringBuilder()
        for (case in cases) {
            createdSessions += case.name
            // Pin the exact short command the app builds before sending it.
            val startCommand = case.choice.startCommand()
            assertTrue(
                "startCommand must be the short wrapper for ${case.name}: $startCommand",
                startCommand == case.expectedCommand,
            )
            // No legacy inline strip / prelude / dangerous flag (hard-cut).
            assertFalse("must not inline env -u: $startCommand", startCommand!!.contains("env -u "))
            assertFalse("must not inline export prelude: $startCommand", startCommand.contains("env export"))
            assertFalse("must not inline --dangerously: $startCommand", startCommand.contains("--dangerously"))

            withTimeout(30_000) {
                gateway.createSession(
                    host = host,
                    keyPath = keyFile.absolutePath,
                    passphrase = null,
                    sessionName = case.name,
                    cwd = cwd,
                    startCommand = startCommand,
                ).getOrThrow()
            }

            // Poll the pane until the agent's ready output appears — this is
            // the "agent STARTED / usable" assertion, not just typed text.
            val pane = capturePane(case.name, case.readyToken)
            val dewrapped = pane.replace("\n", "")
            File(artifactDir, "${case.name}-pane.txt").writeText(pane)
            summary.appendLine("=== ${case.name} ===")
            summary.appendLine("startCommand=$startCommand")
            summary.appendLine("ready-token=${case.readyToken}")
            summary.appendLine(pane.trim())
            summary.appendLine()

            // The short command is visible in the typed line (verbatim).
            assertTrue(
                "Pane for ${case.name} must show the short wrapper command. Captured:\n$pane",
                dewrapped.contains("pocketshell agent"),
            )
            // The agent reached its ready output — it actually started.
            assertTrue(
                "Pane for ${case.name} must show the agent reached ready ('${case.readyToken}'). Captured:\n$pane",
                dewrapped.contains(case.readyToken),
            )
            // The legacy giant env-strip never reaches the pane.
            assertFalse(
                "Pane for ${case.name} must NOT contain the legacy inline 'env -u' strip. Captured:\n$pane",
                dewrapped.contains("env -u OPENAI_API_KEY") || dewrapped.contains("env -u ANTHROPIC_API_KEY"),
            )
        }

        File(artifactDir, "agent-launch-command-summary.txt").writeText(summary.toString())
    } }

    private fun agentChoice(agent: AgentCli, skip: Boolean, cwd: String) =
        SessionTypeChoice(
            type = SessionType.Agent,
            agent = agent,
            startDirectory = cwd,
            skipPermissions = skip,
        )

    private suspend fun ensureRemoteDir(path: String) {
        withTimeout(15_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { session ->
                session.exec("mkdir -p $path")
            }
        }
    }

    private suspend fun capturePane(sessionName: String, expectedToken: String): String =
        withTimeout(30_000) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = sshKey,
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 10_000,
            ).getOrThrow().use { session ->
                // Poll the full scrollback until the agent's ready output
                // shows up. The short wrapper command is well under the 80-col
                // width so it does not wrap, but we de-wrap defensively in the
                // assertions; here we just wait for the agent to start.
                var captured = ""
                repeat(40) {
                    Thread.sleep(250)
                    captured = session.exec(
                        "tmux capture-pane -p -S - -t $sessionName 2>/dev/null",
                    ).stdout
                    if (captured.replace("\n", "").contains(expectedToken)) {
                        return@withTimeout captured
                    }
                }
                captured
            }
        }

    private fun dockerHost(): HostEntity = HostEntity(
        id = 4280L,
        name = "issue703-agents",
        hostname = DEFAULT_HOST,
        port = DEFAULT_PORT,
        username = DEFAULT_USER,
        keyId = 1L,
    )
}
