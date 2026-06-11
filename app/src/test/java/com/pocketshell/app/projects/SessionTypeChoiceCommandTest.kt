package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the per-agent launch-command assembly (issues #428 / #703).
 *
 * Since #703 the app emits the SHORT server-side wrapper line
 * (`pocketshell agent <kind> --dir '<dir>' …`) instead of the old
 * ~1500-char inline `env -u …(71)… <agent>`. The wrapper owns the env
 * merge, the OpenCode-only env strip, the first-run-prompt suppression,
 * and the exec. These tests pin the exact short command the app types.
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

    // --- Claude profile → --config-dir (issue #627) ---

    @Test
    fun claudeWithProfileSetsConfigDir() {
        val profiles = listOf(
            ClaudeProfile(name = "work", configDir = "/home/user/.claude-work"),
        )
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            skipPermissions = true,
            claudeProfileName = "work",
        )
        val command = choice.startCommand(claudeProfiles = profiles)!!
        assertEquals(
            "pocketshell agent claude --dir '/srv/app' --config-dir '/home/user/.claude-work'",
            command,
        )
    }

    @Test
    fun claudeWithoutProfileHasNoConfigDir() {
        val command = agentChoice(AgentCli.Claude, skip = true).startCommand()!!
        assertFalse(command.contains("--config-dir"))
    }

    @Test
    fun claudeMissingProfileFallsBackToDefault() {
        val profiles = listOf(
            ClaudeProfile(name = "work", configDir = "/home/user/.claude-work"),
        )
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            claudeProfileName = "personal",  // not in profiles
        )
        assertFalse(choice.startCommand(claudeProfiles = profiles)!!.contains("--config-dir"))
    }

    @Test
    fun claudeEmptyConfigDirDoesNotEmitConfigDir() {
        val profiles = listOf(ClaudeProfile(name = "default", configDir = ""))
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            claudeProfileName = "default",
        )
        assertFalse(choice.startCommand(claudeProfiles = profiles)!!.contains("--config-dir"))
    }

    @Test
    fun claudeConfigDirIsShellQuoted() {
        val profiles = listOf(ClaudeProfile(name = "test", configDir = "/path/it's/weird"))
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            claudeProfileName = "test",
        )
        val command = choice.startCommand(claudeProfiles = profiles)!!
        assertTrue(command.contains("--config-dir '/path/it'\\''s/weird'"))
    }

    @Test
    fun claudeProfileIgnoredForOtherAgents() {
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Codex,
            startDirectory = "/srv/app",
            claudeProfileName = "work",
        )
        assertFalse(choice.startCommand()!!.contains("--config-dir"))
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

    // --- Codex profile → --config-dir (issue #631) ---

    @Test
    fun codexWithProfileSetsConfigDir() {
        val profiles = listOf(
            CodexProfile(name = "work", configDir = "/home/user/.codex-work"),
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
            "pocketshell agent codex --dir '/srv/app' --config-dir '/home/user/.codex-work'",
            command,
        )
    }

    @Test
    fun codexWithoutProfileHasNoConfigDir() {
        assertFalse(agentChoice(AgentCli.Codex, skip = true).startCommand()!!.contains("--config-dir"))
    }

    @Test
    fun codexMissingProfileFallsBackToDefault() {
        val profiles = listOf(
            CodexProfile(name = "work", configDir = "/home/user/.codex-work"),
        )
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Codex,
            startDirectory = "/srv/app",
            codexProfileName = "personal",  // not in profiles
        )
        assertFalse(choice.startCommand(codexProfiles = profiles)!!.contains("--config-dir"))
    }

    @Test
    fun codexEmptyConfigDirDoesNotEmitConfigDir() {
        val profiles = listOf(CodexProfile(name = "default", configDir = ""))
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Codex,
            startDirectory = "/srv/app",
            codexProfileName = "default",
        )
        assertFalse(choice.startCommand(codexProfiles = profiles)!!.contains("--config-dir"))
    }

    @Test
    fun codexConfigDirIsShellQuoted() {
        val profiles = listOf(CodexProfile(name = "test", configDir = "/path/it's/weird"))
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Codex,
            startDirectory = "/srv/app",
            codexProfileName = "test",
        )
        val command = choice.startCommand(codexProfiles = profiles)!!
        assertTrue(command.contains("--config-dir '/path/it'\\''s/weird'"))
    }

    @Test
    fun codexProfileIgnoredForOtherAgents() {
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            codexProfileName = "work",
        )
        assertFalse(choice.startCommand()!!.contains("--config-dir"))
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

/** Unit tests for [ClaudeProfile] JSON serialization (issue #627). */
class ClaudeProfileTest {

    @Test
    fun fromJsonReturnsEmptyListForNull() {
        assertEquals(emptyList<ClaudeProfile>(), ClaudeProfile.fromJson(null))
    }

    @Test
    fun fromJsonReturnsEmptyListForBlank() {
        assertEquals(emptyList<ClaudeProfile>(), ClaudeProfile.fromJson("  "))
    }

    @Test
    fun fromJsonReturnsEmptyListForInvalidJson() {
        assertEquals(emptyList<ClaudeProfile>(), ClaudeProfile.fromJson("not json"))
    }

    @Test
    fun fromJsonParsesValidArray() {
        val json = """[{"name":"work","configDir":"/home/.claude-work"},{"name":"personal","configDir":""}]"""
        val profiles = ClaudeProfile.fromJson(json)
        assertEquals(2, profiles.size)
        assertEquals("work", profiles[0].name)
        assertEquals("/home/.claude-work", profiles[0].configDir)
        assertEquals("personal", profiles[1].name)
        assertEquals("", profiles[1].configDir)
    }

    @Test
    fun fromJsonSkipsEntriesWithBlankName() {
        val json = """[{"name":"","configDir":"/dir"},{"name":"valid","configDir":""}]"""
        val profiles = ClaudeProfile.fromJson(json)
        assertEquals(1, profiles.size)
        assertEquals("valid", profiles[0].name)
    }

    @Test
    fun fromJsonSkipsNonObjectEntries() {
        val json = """["string",{"name":"ok","configDir":""}]"""
        val profiles = ClaudeProfile.fromJson(json)
        assertEquals(1, profiles.size)
        assertEquals("ok", profiles[0].name)
    }

    @Test
    fun toJsonReturnsNullForEmptyList() {
        assertNull(ClaudeProfile.toJson(emptyList()))
    }

    @Test
    fun toJsonProducesValidJsonArray() {
        val profiles = listOf(
            ClaudeProfile(name = "work", configDir = "/home/.claude-work"),
            ClaudeProfile(name = "default", configDir = ""),
        )
        val json = ClaudeProfile.toJson(profiles)!!
        // Round-trip.
        val parsed = ClaudeProfile.fromJson(json)
        assertEquals(profiles, parsed)
    }

    @Test
    fun roundTripPreservesProfiles() {
        val profiles = listOf(
            ClaudeProfile(name = "a", configDir = "/a"),
            ClaudeProfile(name = "b"),
            ClaudeProfile(name = "c", configDir = "/path with spaces"),
        )
        val json = ClaudeProfile.toJson(profiles)
        val restored = ClaudeProfile.fromJson(json)
        assertEquals(profiles, restored)
    }
}

/** Unit tests for [CodexProfile] JSON serialization (issue #631). */
class CodexProfileTest {

    @Test
    fun fromJsonReturnsEmptyListForNull() {
        assertEquals(emptyList<CodexProfile>(), CodexProfile.fromJson(null))
    }

    @Test
    fun fromJsonReturnsEmptyListForBlank() {
        assertEquals(emptyList<CodexProfile>(), CodexProfile.fromJson("  "))
    }

    @Test
    fun fromJsonReturnsEmptyListForInvalidJson() {
        assertEquals(emptyList<CodexProfile>(), CodexProfile.fromJson("not json"))
    }

    @Test
    fun fromJsonParsesValidArray() {
        val json = """[{"name":"work","configDir":"/home/.codex-work"},{"name":"personal","configDir":""}]"""
        val profiles = CodexProfile.fromJson(json)
        assertEquals(2, profiles.size)
        assertEquals("work", profiles[0].name)
        assertEquals("/home/.codex-work", profiles[0].configDir)
        assertEquals("personal", profiles[1].name)
        assertEquals("", profiles[1].configDir)
    }

    @Test
    fun fromJsonSkipsEntriesWithBlankName() {
        val json = """[{"name":"","configDir":"/dir"},{"name":"valid","configDir":""}]"""
        val profiles = CodexProfile.fromJson(json)
        assertEquals(1, profiles.size)
        assertEquals("valid", profiles[0].name)
    }

    @Test
    fun fromJsonSkipsNonObjectEntries() {
        val json = """["string",{"name":"ok","configDir":""}]"""
        val profiles = CodexProfile.fromJson(json)
        assertEquals(1, profiles.size)
        assertEquals("ok", profiles[0].name)
    }

    @Test
    fun toJsonReturnsNullForEmptyList() {
        assertNull(CodexProfile.toJson(emptyList()))
    }

    @Test
    fun toJsonProducesValidJsonArray() {
        val profiles = listOf(
            CodexProfile(name = "work", configDir = "/home/.codex-work"),
            CodexProfile(name = "default", configDir = ""),
        )
        val json = CodexProfile.toJson(profiles)!!
        // Round-trip.
        val parsed = CodexProfile.fromJson(json)
        assertEquals(profiles, parsed)
    }

    @Test
    fun roundTripPreservesProfiles() {
        val profiles = listOf(
            CodexProfile(name = "a", configDir = "/a"),
            CodexProfile(name = "b"),
            CodexProfile(name = "c", configDir = "/path with spaces"),
        )
        val json = CodexProfile.toJson(profiles)
        val restored = CodexProfile.fromJson(json)
        assertEquals(profiles, restored)
    }
}
