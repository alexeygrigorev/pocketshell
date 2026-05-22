package com.pocketshell.app.proof

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.session.AgentConversationRepository
import com.pocketshell.core.agents.AgentDetection
import com.pocketshell.core.agents.AgentKind
import com.pocketshell.core.agents.ConversationEvent
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray

/**
 * Connected Android smoke test for the emulator-to-host-Docker path.
 *
 * The `androidTest` source set packages `tests/docker` as test assets, so this
 * test authenticates with the same `tests/docker/test_key` fixture that local
 * Docker and JVM integration tests use. The SSH host is intentionally the
 * Android emulator's host-loopback alias, proving that the debug app can reach
 * Docker's `2222:22` mapping from inside the emulator and execute the
 * deterministic agent command surfaces in the `agents` Docker target.
 */
@RunWith(AndroidJUnit4::class)
class EmulatorDockerSshSmokeTest {

    @Test
    fun debugAppConnectsToDockerAgentTargetViaEmulatorHostAlias() = runBlocking {
        val key = InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

        withTimeout(20_000) {
            val connection = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            )

            assertTrue(
                "expected SSH connection to $DEFAULT_USER@$DEFAULT_HOST:$DEFAULT_PORT " +
                    "to succeed, got ${connection.exceptionOrNull()}",
                connection.isSuccess,
            )

            connection.getOrThrow().use { session ->
                val result = session.exec("printf 'pocketshell-emulator-docker-smoke\\n'")
                assertTrue(
                    "expected command output from Docker SSH target, got stdout='${result.stdout}' stderr='${result.stderr}'",
                    result.stdout.contains("pocketshell-emulator-docker-smoke"),
                )

                val toolPaths = session.exec(
                    "for tool in claude codex opencode heru agent-log-explorer tmuxctl uv; do command -v \"${'$'}tool\"; done",
                )
                assertTrue(
                    "expected deterministic agent tools on PATH, got stdout='${toolPaths.stdout}' stderr='${toolPaths.stderr}'",
                    toolPaths.exitCode == 0 &&
                        listOf("claude", "codex", "opencode", "heru", "agent-log-explorer", "tmuxctl", "uv")
                            .all { toolPaths.stdout.contains("/$it") },
                )

                val usage = session.exec("heru usage --json")
                assertTrue(
                    "expected heru usage fixture to succeed, got stdout='${usage.stdout}' stderr='${usage.stderr}'",
                    usage.exitCode == 0,
                )
                val usageJson = JSONArray(usage.stdout)
                assertTrue("expected three provider usage records", usageJson.length() == 3)

                val jobs = session.exec("tmuxctl jobs list --session codex")
                assertTrue(
                    "expected tmuxctl jobs list fixture, got stdout='${jobs.stdout}' stderr='${jobs.stderr}'",
                    jobs.exitCode == 0 && jobs.stdout.contains("codex") && jobs.stdout.contains("opencode-lab"),
                )

                val detection = session.exec("agent-log-explorer detect --cwd /workspace/pocketshell")
                assertTrue(
                    "expected agent-log-explorer fixture to report all agent candidates, got '${detection.stdout}'",
                    detection.exitCode == 0 &&
                        listOf("claude|", "codex|", "opencode|").all { detection.stdout.contains(it) },
                )

                val repository = AgentConversationRepository()
                val refreshClaudeFixture = session.exec(
                    "touch /home/testuser/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl",
                )
                assertTrue(
                    "expected to refresh seeded Claude JSONL mtime, got stderr='${refreshClaudeFixture.stderr}'",
                    refreshClaudeFixture.exitCode == 0,
                )
                val claudeCandidates = session.exec(repository.detectionCommand("/workspace/pocketshell"))
                assertTrue(
                    "expected PocketShell Claude detection command to find seeded JSONL, got '${claudeCandidates.stdout}'",
                    claudeCandidates.exitCode == 0 && claudeCandidates.stdout.contains("claude|"),
                )
                val claudePath = claudeCandidates.stdout
                    .lineSequence()
                    .first { it.startsWith("claude|") }
                    .split("|", limit = 4)[3]
                val events = repository.readInitialEvents(
                    session = session,
                    detection = AgentDetection(
                        agent = AgentKind.ClaudeCode,
                        sourcePath = claudePath,
                        sessionId = "pocketshell-claude",
                        confidence = AgentDetection.Confidence.ProcessConfirmed,
                    ),
                )
                assertTrue(
                    "expected seeded Claude JSONL to parse into conversation events, got $events",
                    events.any { it is ConversationEvent.Message && it.text.contains("relevant checks") },
                )
            }
        }
    }
}
