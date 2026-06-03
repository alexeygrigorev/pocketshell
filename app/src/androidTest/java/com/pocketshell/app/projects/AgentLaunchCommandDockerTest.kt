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
 * Connected proof for issue #428: the per-agent skip-permissions launch
 * command PocketShell builds is the EXACT text that lands in the new
 * tmux pane via `createSession` -> `tmux send-keys`.
 *
 * This drives the real [SshFolderListGateway.createSession] (the same
 * code the picker confirm path calls) against the deterministic Docker
 * `agents` service on host port `2222`, then reads back the pane's
 * scrollback with `capture-pane` to confirm the literal command was
 * typed. We do NOT require the agent binaries to exist on the fixture —
 * the assertion is on the command string that reaches the shell, which
 * is exactly what the maintainer cares about (a wrong OpenCode command
 * would bill per token).
 *
 * The env-export prelude (issue #263) wraps the command, so the visible
 * line is:
 *
 * ```
 * eval "$(pocketshell env export --dir '<cwd>')"; <agent launch command>
 * ```
 *
 * Artifacts are written under the app's external files dir
 * (`agent-launch-command/`) so the reviewer can inspect the captured
 * pane text per the terminal-artifact rules.
 */
@RunWith(AndroidJUnit4::class)
class AgentLaunchCommandDockerTest {

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val createdSessions = mutableListOf<String>()
    private lateinit var artifactDir: File

    @Before
    fun setUp(): Unit = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val keyText = instrumentation.context.assets.open("test_key")
            .bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = instrumentation.targetContext.cacheDir
        keyFile = File(cacheDir, "issue428-launch-key").apply {
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
    }

    @After
    fun tearDown(): Unit = runBlocking {
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
    }

    @Test
    fun perAgentLaunchCommandLandsInPaneVerbatim(): Unit = runBlocking {
        val gateway = SshFolderListGateway()
        val host = dockerHost()
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val cwd = "/tmp/issue428-$suffix".also { ensureRemoteDir(it) }

        data class Case(val name: String, val choice: SessionTypeChoice, val expected: String, val mustNot: List<String>)

        val cases = listOf(
            Case(
                name = "issue428-claude-on-$suffix",
                choice = agentChoice(AgentCli.Claude, skip = true, cwd),
                expected = "claude --dangerously-skip-permissions",
                mustNot = emptyList(),
            ),
            Case(
                name = "issue428-claude-off-$suffix",
                choice = agentChoice(AgentCli.Claude, skip = false, cwd),
                expected = "; claude",
                mustNot = listOf("--dangerously-skip-permissions"),
            ),
            Case(
                name = "issue428-codex-on-$suffix",
                choice = agentChoice(AgentCli.Codex, skip = true, cwd),
                expected = "codex --dangerously-bypass-approvals-and-sandbox",
                mustNot = emptyList(),
            ),
            Case(
                name = "issue428-opencode-$suffix",
                choice = agentChoice(AgentCli.OpenCode, skip = true, cwd),
                expected = "OPENCODE_API_KEY",
                mustNot = listOf("--dangerously"),
            ),
        )

        val summary = StringBuilder()
        for (case in cases) {
            createdSessions += case.name
            val resolved = withTimeout(30_000) {
                gateway.createSession(
                    host = host,
                    keyPath = keyFile.absolutePath,
                    passphrase = null,
                    sessionName = case.name,
                    cwd = cwd,
                    startCommand = case.choice.startCommand(),
                ).getOrThrow()
            }
            // Give the shell a beat to echo the typed line, then capture.
            val pane = capturePane(case.name, case.expected)
            android.util.Log.i(
                "AgentLaunch428",
                "${case.name} dewrapped-capture:\n${pane.replace("\n", "")}",
            )
            // The default 80-col pane wraps the long typed command across
            // physical rows. tmux's `-J` does not reliably rejoin the
            // wrapped *input* line, and a wrap can land mid-token
            // (e.g. `OPENCOD\nE_API_KEY`). Reconstruct the contiguous
            // command by removing the row breaks (tmux inserts no extra
            // spaces at a wrap boundary), so token-level assertions match
            // the command exactly as it was typed.
            val dewrapped = pane.replace("\n", "")
            File(artifactDir, "${case.name}-pane.txt").writeText(pane)
            summary.appendLine("=== ${case.name} ===")
            summary.appendLine("resolved=$resolved")
            summary.appendLine("startCommand=${case.choice.startCommand()}")
            summary.appendLine("expected-contains=${case.expected}")
            summary.appendLine(pane.trim())
            summary.appendLine()

            assertTrue(
                "Pane for ${case.name} must contain '${case.expected}'. Captured:\n$pane",
                dewrapped.contains(case.expected),
            )
            for (forbidden in case.mustNot) {
                assertFalse(
                    "Pane for ${case.name} must NOT contain '$forbidden'. Captured:\n$pane",
                    dewrapped.contains(forbidden),
                )
            }
        }

        // OpenCode must strip every provider key var, never a bare opencode.
        val openCodeStart = agentChoice(AgentCli.OpenCode, skip = true, cwd).startCommand()!!
        for (varName in AgentCli.OPENCODE_ENV_UNSET_VARS) {
            assertTrue(
                "OpenCode launch must unset $varName",
                openCodeStart.contains("-u $varName "),
            )
        }
        assertFalse("OpenCode must never be the bare command", openCodeStart == "opencode")

        File(artifactDir, "agent-launch-command-summary.txt").writeText(summary.toString())
    }

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
                // The OpenCode env-strip command is ~1500 chars, so on the
                // default 80-col pane the committed command wraps across
                // many physical rows in the scrollback. `-S -` reads the
                // full scrollback (the command lands in history once
                // createSession presses Enter), and a wrap can land
                // mid-token (e.g. `OPENCOD\nE_API_KEY`). Removing the row
                // breaks reconstructs the contiguous command (tmux inserts
                // no extra characters at a wrap boundary). Poll until the
                // de-wrapped scrollback contains the token we expect for
                // this case — that proves the full command reached the
                // remote shell intact, not just the truncated readline
                // viewport.
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
        name = "issue428-agents",
        hostname = DEFAULT_HOST,
        port = DEFAULT_PORT,
        username = DEFAULT_USER,
        keyId = 1L,
    )
}
