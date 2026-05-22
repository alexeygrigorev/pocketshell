package com.pocketshell.app.docker

import com.pocketshell.app.jobs.TmuxctlJobsParser
import com.pocketshell.core.usage.HeruUsageJsonParser
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
    fun heruUsageFixtureMatchesCoreUsageParser() {
        val output = runFixtureCommand("heru", "usage", "--json")
        val records = HeruUsageJsonParser().parse(output)

        assertEquals(listOf("claude", "codex", "opencode"), records.map { it.provider })
        assertEquals("limited", records.first { it.provider == "codex" }.rawStatus)
    }

    @Test
    fun tmuxctlJobsFixtureMatchesAppJobsParser() {
        val output = runFixtureCommand("tmuxctl", "jobs", "list", "--session", "codex")
        val jobs = TmuxctlJobsParser().parseList(output)

        assertEquals(listOf("claude-main", "codex", "opencode-lab"), jobs.map { it.sessionName })
        assertTrue(jobs.first { it.sessionName == "codex" }.enabled)
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
        assertTrue(runFixtureCommand("uv", "tool", "install", "heru").contains("installed fixture tool heru"))
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
