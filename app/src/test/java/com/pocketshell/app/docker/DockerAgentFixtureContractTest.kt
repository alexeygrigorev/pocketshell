package com.pocketshell.app.docker

import com.pocketshell.app.jobs.RecurringJobsParser
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.core.usage.PocketshellUsageJsonParser
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
    fun pocketshellUsageFixtureMatchesCoreUsageParser() {
        val output = runFixtureCommand("pocketshell", "usage", "--json")
        val records = PocketshellUsageJsonParser().parse(output)

        assertEquals(listOf("codex", "claude", "copilot"), records.map { it.provider })
        assertEquals("limited", records.first { it.provider == "claude" }.rawStatus)
    }

    @Test
    fun pocketshellJobsFixtureMatchesAppJobsParser() {
        val output = runFixtureCommand("pocketshell", "jobs", "list", "--session", "codex")
        val jobs = RecurringJobsParser().parseList(output)

        assertEquals(listOf("claude-main", "codex", "opencode-lab"), jobs.map { it.sessionName })
        assertTrue(jobs.first { it.sessionName == "codex" }.enabled)
    }

    @Test
    fun pocketshellSessionsFixtureMatchesHostSessionParser() {
        val output = runFixtureCommand("pocketshell", "sessions", "list", "--by", "activity")
        val sessions = HostTmuxSessionListParser().parsePocketshellSessionsList(output)

        assertEquals(listOf("claude-main", "codex", "opencode-lab"), sessions.map { it.name })
        assertTrue(sessions.all { it.createdAt != null })
    }

    @Test
    fun pocketshellVersionFixtureMatchesAndroidVersionName() {
        assertEquals(
            "pocketshell fixture ${androidVersionName()}\n",
            runFixtureCommand("pocketshell", "--version"),
        )
    }

    @Test
    fun pocketshellJobsMutationFixturesReturnStableShapes() {
        assertTrue(
            runFixtureCommand("pocketshell", "jobs", "add", "codex", "--every", "5m").contains("Created job 4"),
        )
        assertTrue(
            runFixtureCommand("pocketshell", "jobs", "edit", "4", "--every", "15m").contains("Updated job 4"),
        )
        assertTrue(
            runFixtureCommand("pocketshell", "jobs", "remove", "4").contains("Removed job 4"),
        )
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
        assertTrue(
            runFixtureCommand("uv", "tool", "install", "pocketshell").contains("installed fixture tool pocketshell"),
        )
        assertEquals("active\n", runFixtureCommand("systemctl", "--user", "is-active", "pocketshell-jobs.service"))
        assertEquals("enabled\n", runFixtureCommand("systemctl", "--user", "is-enabled", "pocketshell-jobs.service"))
    }

    private fun runFixtureCommand(vararg args: String): String {
        val command = dockerDir.resolve("agent-bin").resolve(args.first())
        val process = ProcessBuilder(listOf(command.toString()) + args.drop(1))
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
            .also {
                it.environment()["POCKETSHELL_AGENT_FIXTURE_DIR"] = fixtureDir.toString()
                it.environment()["POCKETSHELL_PROJECT_ROOT"] = projectRoot.toString()
            }
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

    /**
     * Issue #2356 (Phase 4 of epic #2350): `app/build.gradle.kts` no longer
     * has a literal `versionName = "X.Y.Z"` to regex out — it is derived at
     * Gradle configuration time from `scripts/derive-version.sh` (the git
     * tag being built). This shells out to that SAME script, matching how
     * `app/build.gradle.kts` itself resolves the value (see
     * `derivePocketshellVersion()`), rather than carrying a second,
     * independently-written derivation that could silently drift.
     */
    private fun androidVersionName(): String {
        val script = projectRoot.resolve("scripts/derive-version.sh")
        val process = ProcessBuilder("bash", script.toString(), "version-name")
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val completed = process.waitFor(5, TimeUnit.SECONDS)
        if (!completed || process.exitValue() != 0) {
            error("Could not derive app versionName via $script (output: $output)")
        }
        return output.trim().ifEmpty { error("derive-version.sh produced an empty versionName") }
    }
}
