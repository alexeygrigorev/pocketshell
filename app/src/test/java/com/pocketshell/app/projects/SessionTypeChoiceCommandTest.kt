package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the per-agent launch-command assembly (issues #428 / #703 /
 * #718).
 *
 * Since #703 the app emits the SHORT server-side wrapper line
 * (`pocketshell agent <kind> --dir '<dir>' …`) instead of the old
 * ~1500-char inline `env -u …(71)… <agent>`. The wrapper owns the env
 * merge, the OpenCode-only env strip, the first-run-prompt suppression,
 * and the exec. Since #718 a selected, non-default profile is passed BY
 * NAME as `--profile '<name>'` (the host resolves it to
 * `CLAUDE_CONFIG_DIR` / `CODEX_HOME`) instead of the old client-resolved
 * `--config-dir '<path>'`. These tests pin the exact short command the app
 * types.
 */
class SessionTypeChoiceCommandTest {

    private fun agentChoice(agent: AgentCli, skip: Boolean, dir: String = "/srv/app") =
        SessionTypeChoice(
            type = SessionType.Agent,
            agent = agent,
            startDirectory = dir,
            skipPermissions = skip,
        )

    // --- The short wrapper form, per agent ---

    @Test
    fun claudeWithSkipPermissionsIsShortWrapperNoExtraFlag() {
        val command = agentChoice(AgentCli.Claude, skip = true).startCommand()!!
        // skip-permissions defaults ON in the wrapper, so nothing extra.
        assertEquals("pocketshell agent claude --dir '/srv/app'", command)
    }

    @Test
    fun claudeWithoutSkipPermissionsEmitsNoSkipFlag() {
        val command = agentChoice(AgentCli.Claude, skip = false).startCommand()!!
        assertEquals(
            "pocketshell agent claude --dir '/srv/app' --no-skip-permissions",
            command,
        )
    }

    @Test
    fun codexWithSkipPermissionsIsShortWrapperNoExtraFlag() {
        val command = agentChoice(AgentCli.Codex, skip = true).startCommand()!!
        assertEquals("pocketshell agent codex --dir '/srv/app'", command)
    }

    @Test
    fun codexWithoutSkipPermissionsEmitsNoSkipFlag() {
        val command = agentChoice(AgentCli.Codex, skip = false).startCommand()!!
        assertEquals(
            "pocketshell agent codex --dir '/srv/app' --no-skip-permissions",
            command,
        )
    }

    @Test
    fun openCodeIsShortWrapperAndNeverGetsSkipFlag() {
        // The checkbox is a no-op for OpenCode: same command either way and
        // never `--no-skip-permissions` (the wrapper does the billing strip).
        val withSkip = agentChoice(AgentCli.OpenCode, skip = true).startCommand()
        val withoutSkip = agentChoice(AgentCli.OpenCode, skip = false).startCommand()
        assertEquals(withSkip, withoutSkip)
        assertEquals("pocketshell agent opencode --dir '/srv/app'", withSkip)
        assertFalse(withSkip!!.contains("--no-skip-permissions"))
    }

    @Test
    fun commandNeverContainsTheOldInlineEnvStrip() {
        // Hard-cut (D22): the giant `env -u …` strip and the
        // `eval "$(pocketshell env export …)"` prelude are gone.
        for (agent in AgentCli.entries) {
            val command = agentChoice(agent, skip = true).startCommand()!!
            assertFalse("must not inline env -u: $command", command.contains("env -u "))
            assertFalse("must not inline export prelude: $command", command.contains("env export"))
            assertFalse("must not inline --dangerously: $command", command.contains("--dangerously"))
            assertTrue("must be the wrapper: $command", command.startsWith("pocketshell agent "))
        }
    }

    @Test
    fun commandNeverContainsLegacyConfigDirFlag() {
        // Hard-cut (#718): the client no longer resolves a profile to a path
        // and never emits `--config-dir`; profiles are passed by name.
        val profiles = listOf(ClaudeProfile(name = "work"))
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            claudeProfileName = "work",
        )
        assertFalse(choice.startCommand(claudeProfiles = profiles)!!.contains("--config-dir"))
    }

    // --- --dir handling / shell quoting ---

    @Test
    fun dirIsShellQuoted() {
        val command = agentChoice(AgentCli.Claude, skip = true, dir = "/home/alexey/git/my project")
            .startCommand()!!
        assertTrue(command.contains("--dir '/home/alexey/git/my project'"))
    }

    @Test
    fun hostileDirCannotInjectShell() {
        val hostile = "/tmp/it's a folder; rm -rf \$HOME"
        val command = agentChoice(AgentCli.Codex, skip = true, dir = hostile).startCommand()!!
        // Single-quoted with the embedded single quote escaped as '\''.
        assertTrue(
            command.contains("--dir '/tmp/it'\\''s a folder; rm -rf \$HOME'"),
        )
        // With skip ON the quoted dir is the last token.
        assertTrue(command.endsWith("'"))
    }

    // --- Claude profile → --profile <name> (issue #718) ---

    @Test
    fun claudeWithProfilePassesProfileName() {
        val profiles = listOf(
            ClaudeProfile(name = "Claude (Z.AI)"),
        )
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            skipPermissions = true,
            claudeProfileName = "Claude (Z.AI)",
        )
        val command = choice.startCommand(claudeProfiles = profiles)!!
        assertEquals(
            "pocketshell agent claude --dir '/srv/app' --profile 'Claude (Z.AI)'",
            command,
        )
    }

    @Test
    fun claudeWithoutProfileHasNoProfileFlag() {
        val command = agentChoice(AgentCli.Claude, skip = true).startCommand()!!
        assertFalse(command.contains("--profile"))
    }

    @Test
    fun claudeDefaultProfileEmitsNoProfileFlag() {
        // The engine's default profile means "built-in config dir": no flag.
        val profiles = listOf(ClaudeProfile(name = "Claude", default = true))
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            claudeProfileName = "Claude",
        )
        assertFalse(choice.startCommand(claudeProfiles = profiles)!!.contains("--profile"))
    }

    @Test
    fun claudeMissingProfileFallsBackToDefault() {
        val profiles = listOf(ClaudeProfile(name = "work"))
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            claudeProfileName = "personal",  // not in profiles
        )
        assertFalse(choice.startCommand(claudeProfiles = profiles)!!.contains("--profile"))
    }

    @Test
    fun claudeProfileNameIsShellQuoted() {
        val profiles = listOf(ClaudeProfile(name = "weird's name"))
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            claudeProfileName = "weird's name",
        )
        val command = choice.startCommand(claudeProfiles = profiles)!!
        assertTrue(command.contains("--profile 'weird'\\''s name'"))
    }

    @Test
    fun claudeProfileIgnoredForOtherAgents() {
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Codex,
            startDirectory = "/srv/app",
            claudeProfileName = "work",
        )
        assertFalse(choice.startCommand()!!.contains("--profile"))
    }

    @Test
    fun claudeProfileIgnoredForShellSessions() {
        val choice = SessionTypeChoice(
            type = SessionType.Shell,
            agent = null,
            startDirectory = "/srv/app",
            claudeProfileName = "work",
        )
        assertNull(choice.startCommand())
    }

    // --- Codex profile → --profile <name> (issue #718) ---

    @Test
    fun codexWithProfilePassesProfileName() {
        val profiles = listOf(
            CodexProfile(name = "work"),
        )
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Codex,
            startDirectory = "/srv/app",
            skipPermissions = true,
            codexProfileName = "work",
        )
        val command = choice.startCommand(codexProfiles = profiles)!!
        assertEquals(
            "pocketshell agent codex --dir '/srv/app' --profile 'work'",
            command,
        )
    }

    @Test
    fun codexWithoutProfileHasNoProfileFlag() {
        assertFalse(agentChoice(AgentCli.Codex, skip = true).startCommand()!!.contains("--profile"))
    }

    @Test
    fun codexDefaultProfileEmitsNoProfileFlag() {
        val profiles = listOf(CodexProfile(name = "Codex", default = true))
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Codex,
            startDirectory = "/srv/app",
            codexProfileName = "Codex",
        )
        assertFalse(choice.startCommand(codexProfiles = profiles)!!.contains("--profile"))
    }

    @Test
    fun codexMissingProfileFallsBackToDefault() {
        val profiles = listOf(CodexProfile(name = "work"))
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Codex,
            startDirectory = "/srv/app",
            codexProfileName = "personal",  // not in profiles
        )
        assertFalse(choice.startCommand(codexProfiles = profiles)!!.contains("--profile"))
    }

    @Test
    fun codexProfileNameIsShellQuoted() {
        val profiles = listOf(CodexProfile(name = "weird's name"))
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Codex,
            startDirectory = "/srv/app",
            codexProfileName = "weird's name",
        )
        val command = choice.startCommand(codexProfiles = profiles)!!
        assertTrue(command.contains("--profile 'weird'\\''s name'"))
    }

    @Test
    fun codexProfileIgnoredForOtherAgents() {
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            codexProfileName = "work",
        )
        assertFalse(choice.startCommand()!!.contains("--profile"))
    }

    @Test
    fun codexProfileIgnoredForShellSessions() {
        val choice = SessionTypeChoice(
            type = SessionType.Shell,
            agent = null,
            startDirectory = "/srv/app",
            codexProfileName = "work",
        )
        assertNull(choice.startCommand())
    }

    // --- Shell sessions + defaults ---

    @Test
    fun shellSessionHasNoStartCommand() {
        val shell = SessionTypeChoice(
            type = SessionType.Shell,
            agent = null,
            startDirectory = "/srv/app",
            skipPermissions = true,
        )
        assertNull(shell.startCommand())
    }

    @Test
    fun skipPermissionsDefaultsToTrue() {
        // Default-ON: the maintainer's preference sticks without ticking the
        // box, so the wrapper line carries no `--no-skip-permissions`.
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
        )
        assertTrue(choice.skipPermissions)
        assertEquals("pocketshell agent claude --dir '/srv/app'", choice.startCommand())
    }
}
