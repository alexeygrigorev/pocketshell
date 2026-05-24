package com.pocketshell.app.docker

import com.pocketshell.app.jobs.TmuxctlJobsParser
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.core.usage.QuseUsageJsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

class DockerAgentFixtureContractTest {
    private val projectRoot: Path = findProjectRoot()
    private val dockerDir: Path = projectRoot.resolve("tests/docker")
    private val fixtureDir: Path = dockerDir.resolve("agent-fixtures")

    @Test
    fun quseUsageFixtureMatchesCoreUsageParser() {
        val output = runFixtureCommand("quse", "--json")
        val records = QuseUsageJsonParser().parse(output)

        assertEquals(listOf("codex", "claude", "copilot"), records.map { it.provider })
        assertEquals("limited", records.first { it.provider == "claude" }.rawStatus)
    }

    @Test
    fun tmuxctlJobsFixtureMatchesAppJobsParser() {
        val output = runFixtureCommand("tmuxctl", "jobs", "list", "--session", "codex")
        val jobs = TmuxctlJobsParser().parseList(output)

        assertEquals(listOf("claude-main", "codex", "opencode-lab"), jobs.map { it.sessionName })
        assertTrue(jobs.first { it.sessionName == "codex" }.enabled)
    }

    @Test
    fun tmuxctlListFixtureMatchesHostSessionParser() {
        val output = runFixtureCommand("tmuxctl", "list", "--by", "activity")
        val sessions = HostTmuxSessionListParser().parseTmuxctlList(output)

        assertEquals(listOf("claude-main", "codex", "opencode-lab"), sessions.map { it.name })
        assertTrue(sessions.all { it.createdAt != null })
    }

    @Test
    fun tmuxctlAttachAndCreateFixturesMatchSessionWorkflowCommands() {
        assertEquals(
            "Attached to session codex\n",
            runFixtureCommand("tmuxctl", "codex"),
        )
        assertEquals(
            "Attached to session codex\n",
            runFixtureCommand("tmuxctl", "2"),
        )
        assertEquals(
            "Created and attached to session fresh-work\n",
            runFixtureCommand("tmuxctl", ":fresh-work"),
        )
    }

    @Test
    fun tmuxctlMutationFixturesReturnStableShapes() {
        assertTrue(runFixtureCommand("tmuxctl", "jobs", "add", "codex", "--every", "5m").contains("Created job 4"))
        assertTrue(runFixtureCommand("tmuxctl", "jobs", "edit", "4", "--every", "15m").contains("Updated job 4"))
        assertTrue(runFixtureCommand("tmuxctl", "jobs", "remove", "4").contains("Removed job 4"))
    }

    @Test
    fun agentLogExplorerFixtureReportsAllSupportedAgentCandidates() {
        val output = runFixtureCommand("agent-log-explorer", "detect", "--cwd", "/workspace/pocketshell")
        val agents = output.lineSequence()
            .filter { it.isNotBlank() }
            .map { it.substringBefore('|') }
            .toList()

        assertEquals(listOf("claude", "codex", "opencode"), agents)
        assertTrue(output.contains("/home/testuser/.claude/projects/-workspace-pocketshell/"))
    }

    @Test
    fun providerCliFixturesAreCredentialFree() {
        assertTrue(runFixtureCommand("claude", "--version").contains("fixture"))
        assertTrue(runFixtureCommand("codex", "--version").contains("fixture"))
        assertTrue(runFixtureCommand("opencode", "--version").contains("fixture"))
    }

    @Test
    fun bootstrapInstallerAndSystemctlFixturesAreDeterministic() {
        assertTrue(runFixtureCommand("uv", "tool", "install", "quse").contains("installed fixture tool quse"))
        assertEquals("active\n", runFixtureCommand("systemctl", "--user", "is-active", "tmuxctl-jobs.service"))
        assertEquals("enabled\n", runFixtureCommand("systemctl", "--user", "is-enabled", "tmuxctl-jobs.service"))
    }

    private fun runFixtureCommand(vararg args: String): String {
        val command = dockerDir.resolve("agent-bin").resolve(args.first())
        val process = ProcessBuilder(listOf(command.toString()) + args.drop(1))
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
            .also { it.environment()["POCKETSHELL_AGENT_FIXTURE_DIR"] = fixtureDir.toString() }
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val completed = process.waitFor(5, TimeUnit.SECONDS)
        assertTrue("fixture command timed out: ${args.joinToString(" ")}", completed)
        assertEquals("fixture command failed: ${args.joinToString(" ")}\n$output", 0, process.exitValue())
        return output
    }

    private fun findProjectRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (dir.resolve("tests/docker/docker-compose.yml").toFile().exists()) {
                return dir
            }
            dir = dir.parent
        }
        error("Could not locate tests/docker/docker-compose.yml from user.dir=${System.getProperty("user.dir")}")
    }
}
