package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the per-agent launch-command assembly (issue #428).
 *
 * PocketShell emits the explicit launch command itself (approach (b) in
 * the issue) rather than relying on the maintainer's `csp` / `cy` / `oc`
 * shell aliases being sourced on the remote host. These tests pin the
 * EXACT command strings, since a wrong OpenCode command (one that does
 * not strip the provider API-key env vars) would bill the maintainer per
 * token instead of using subscription auth.
 */
class SessionTypeChoiceCommandTest {

    private fun agentChoice(agent: AgentCli, skip: Boolean) =
        SessionTypeChoice(
            type = SessionType.Agent,
            agent = agent,
            startDirectory = "/srv/app",
            skipPermissions = skip,
        )

    @Test
    fun claudeWithSkipPermissionsUsesEnvStrippedAndDangerousFlag() {
        val command = agentChoice(AgentCli.Claude, skip = true).startCommand()!!
        assertTrue("must start with env -u", command.startsWith("env -u "))
        assertTrue(command.endsWith(" claude --dangerously-skip-permissions"))
    }

    @Test
    fun claudeWithoutSkipPermissionsIsEnvStripped() {
        val command = agentChoice(AgentCli.Claude, skip = false).startCommand()!!
        assertTrue("must start with env -u", command.startsWith("env -u "))
        assertTrue(command.endsWith(" claude"))
        assertFalse(command.contains("--dangerously"))
    }

    @Test
    fun codexWithSkipPermissionsUsesEnvStrippedAndBypassesFlag() {
        val command = agentChoice(AgentCli.Codex, skip = true).startCommand()!!
        assertTrue("must start with env -u", command.startsWith("env -u "))
        assertTrue(command.endsWith(" codex --dangerously-bypass-approvals-and-sandbox"))
    }

    @Test
    fun codexWithoutSkipPermissionsIsEnvStripped() {
        val command = agentChoice(AgentCli.Codex, skip = false).startCommand()!!
        assertTrue("must start with env -u", command.startsWith("env -u "))
        assertTrue(command.endsWith(" codex"))
        assertFalse(command.contains("--dangerously"))
    }

    // --- Issue #631: Codex env strip + profiles ---

    @Test
    fun codexStripsAllSeventyOneProviderVars() {
        val command = agentChoice(AgentCli.Codex, skip = false).startCommand()!!
        for (varName in AgentCli.OPENCODE_ENV_UNSET_VARS) {
            assertTrue(
                "Codex launch must unset $varName",
                command.contains("-u $varName "),
            )
        }
    }

    @Test
    fun codexWithoutProfileHasNoCodexHome() {
        val command = agentChoice(AgentCli.Codex, skip = false).startCommand()!!
        assertFalse(command.contains("CODEX_HOME"))
    }

    @Test
    fun codexWithProfileSetsCodexHome() {
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
        assertTrue(
            "must contain CODEX_HOME",
            command.contains("CODEX_HOME='/home/user/.codex-work'"),
        )
        assertTrue(command.endsWith(" codex --dangerously-bypass-approvals-and-sandbox"))
    }

    @Test
    fun codexWithProfileButMissingProfileFallsBackToDefault() {
        val profiles = listOf(
            CodexProfile(name = "work", configDir = "/home/user/.codex-work"),
        )
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Codex,
            startDirectory = "/srv/app",
            codexProfileName = "personal",  // not in profiles
        )
        val command = choice.startCommand(codexProfiles = profiles)!!
        assertFalse(command.contains("CODEX_HOME"))
    }

    @Test
    fun codexProfileWithEmptyConfigDirDoesNotSetEnvVar() {
        val profiles = listOf(
            CodexProfile(name = "default", configDir = ""),
        )
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Codex,
            startDirectory = "/srv/app",
            codexProfileName = "default",
        )
        val command = choice.startCommand(codexProfiles = profiles)!!
        assertFalse(command.contains("CODEX_HOME"))
    }

    @Test
    fun codexConfigDirIsShellQuoted() {
        val profiles = listOf(
            CodexProfile(name = "test", configDir = "/path/it's/weird"),
        )
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Codex,
            startDirectory = "/srv/app",
            codexProfileName = "test",
        )
        val command = choice.startCommand(codexProfiles = profiles)!!
        assertTrue(
            "single quote must be escaped",
            command.contains("CODEX_HOME='/path/it'\\''s/weird'"),
        )
    }

    @Test
    fun codexProfileIgnoredForOtherAgents() {
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            codexProfileName = "work",
        )
        val command = choice.startCommand()!!
        assertFalse(command.contains("CODEX_HOME"))
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

    @Test
    fun openCodeIsAlwaysEnvStrippedRegardlessOfSkipPermissions() {
        val withSkip = agentChoice(AgentCli.OpenCode, skip = true).startCommand()
        val withoutSkip = agentChoice(AgentCli.OpenCode, skip = false).startCommand()
        // The checkbox is a no-op for OpenCode: same env-stripped command
        // either way.
        assertEquals(withSkip, withoutSkip)
        // It is the env-stripped launch, NOT the bare `opencode`.
        assertTrue("must start with env -u", withSkip!!.startsWith("env -u "))
        assertTrue(withSkip.endsWith(" opencode"))
        // No skip-permissions / bypass flag is ever appended for OpenCode.
        assertFalse(withSkip.contains("--dangerously"))
    }

    @Test
    fun openCodeNeverEmitsBareNonStrippedCommand() {
        val command = agentChoice(AgentCli.OpenCode, skip = true).startCommand()!!
        // A bare or partially-stripped command would let OpenCode pick up
        // an env API key and bill per token. Guard against that: the
        // command must not be just `opencode`, and must strip every var.
        assertFalse(command == "opencode")
        for (varName in AgentCli.OPENCODE_ENV_UNSET_VARS) {
            assertTrue(
                "OpenCode launch must unset $varName",
                command.contains("-u $varName "),
            )
        }
    }

    @Test
    fun openCodeUnsetsTheFullSeventyOneVariableListVerbatim() {
        // The list mirrors the maintainer's dotfiles
        // config/opencode/env_unset.txt (71 entries). Pin the count and a
        // representative sample so an accidental truncation is caught.
        assertEquals(71, AgentCli.OPENCODE_ENV_UNSET_VARS.size)
        // No duplicates.
        assertEquals(
            AgentCli.OPENCODE_ENV_UNSET_VARS.size,
            AgentCli.OPENCODE_ENV_UNSET_VARS.toSet().size,
        )
        // Spot-check the provider keys that cost real money.
        assertTrue(AgentCli.OPENCODE_ENV_UNSET_VARS.contains("ANTHROPIC_API_KEY"))
        assertTrue(AgentCli.OPENCODE_ENV_UNSET_VARS.contains("OPENAI_API_KEY"))
        assertTrue(AgentCli.OPENCODE_ENV_UNSET_VARS.contains("OPENCODE_API_KEY"))
        assertTrue(AgentCli.OPENCODE_ENV_UNSET_VARS.contains("OPENCODE_ZEN_API_KEY"))
        assertTrue(AgentCli.OPENCODE_ENV_UNSET_VARS.contains("GEMINI_API_KEY"))
        // The first and last entries, to catch off-by-one truncation.
        assertEquals("AWS_ACCESS_KEY_ID", AgentCli.OPENCODE_ENV_UNSET_VARS.first())
        assertEquals("GEMINI_API_KEY", AgentCli.OPENCODE_ENV_UNSET_VARS.last())
    }

    @Test
    fun openCodeCommandIsExactlyTheEnvStrippedForm() {
        val expected = "env " +
            AgentCli.OPENCODE_ENV_UNSET_VARS.joinToString(" ") { "-u $it" } +
            " opencode"
        assertEquals(expected, agentChoice(AgentCli.OpenCode, skip = true).startCommand())
    }

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
        // Default-ON: the maintainer's preference sticks without ticking
        // the box.
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
        )
        assertTrue(choice.skipPermissions)
        val command = choice.startCommand()!!
        assertTrue(command.endsWith(" claude --dangerously-skip-permissions"))
    }

    @Test
    fun openCodeEnvVarNamesAreShellSafeIdentifiers() {
        // The env -u arguments are interpolated without per-arg quoting,
        // so every name must be a plain env identifier (no spaces, no
        // shell metacharacters). If a future dotfiles edit introduced a
        // hostile name, launchCommand() would throw rather than emit an
        // injectable command.
        val identifier = Regex("^[A-Za-z0-9_]+$")
        for (varName in AgentCli.OPENCODE_ENV_UNSET_VARS) {
            assertTrue("Unsafe env var name: $varName", identifier.matches(varName))
        }
    }

    // --- Issue #627: Claude Code env strip + profiles ---

    @Test
    fun claudeStripsAllSeventyOneProviderVars() {
        val command = agentChoice(AgentCli.Claude, skip = false).startCommand()!!
        for (varName in AgentCli.OPENCODE_ENV_UNSET_VARS) {
            assertTrue(
                "Claude launch must unset $varName",
                command.contains("-u $varName "),
            )
        }
    }

    @Test
    fun claudeWithoutProfileHasNoConfigDir() {
        val command = agentChoice(AgentCli.Claude, skip = false).startCommand()!!
        assertFalse(command.contains("CLAUDE_CONFIG_DIR"))
    }

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
        val command = choice.startCommand(profiles)!!
        assertTrue(
            "must contain CLAUDE_CONFIG_DIR",
            command.contains("CLAUDE_CONFIG_DIR='/home/user/.claude-work'"),
        )
        assertTrue(command.endsWith(" claude --dangerously-skip-permissions"))
    }

    @Test
    fun claudeWithProfileButMissingProfileFallsBackToDefault() {
        val profiles = listOf(
            ClaudeProfile(name = "work", configDir = "/home/user/.claude-work"),
        )
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            claudeProfileName = "personal",  // not in profiles
        )
        val command = choice.startCommand(profiles)!!
        assertFalse(command.contains("CLAUDE_CONFIG_DIR"))
    }

    @Test
    fun claudeProfileWithEmptyConfigDirDoesNotSetEnvVar() {
        val profiles = listOf(
            ClaudeProfile(name = "default", configDir = ""),
        )
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            claudeProfileName = "default",
        )
        val command = choice.startCommand(profiles)!!
        assertFalse(command.contains("CLAUDE_CONFIG_DIR"))
    }

    @Test
    fun claudeConfigDirIsShellQuoted() {
        val profiles = listOf(
            ClaudeProfile(name = "test", configDir = "/path/it's/weird"),
        )
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Claude,
            startDirectory = "/srv/app",
            claudeProfileName = "test",
        )
        val command = choice.startCommand(profiles)!!
        assertTrue(
            "single quote must be escaped",
            command.contains("CLAUDE_CONFIG_DIR='/path/it'\\''s/weird'"),
        )
    }

    @Test
    fun claudeProfileIgnoredForOtherAgents() {
        val choice = SessionTypeChoice(
            type = SessionType.Agent,
            agent = AgentCli.Codex,
            startDirectory = "/srv/app",
            claudeProfileName = "work",
        )
        val command = choice.startCommand()!!
        assertFalse(command.contains("CLAUDE_CONFIG_DIR"))
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
