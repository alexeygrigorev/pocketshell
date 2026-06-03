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
    fun claudeWithSkipPermissionsUsesDangerousFlag() {
        assertEquals(
            "claude --dangerously-skip-permissions",
            agentChoice(AgentCli.Claude, skip = true).startCommand(),
        )
    }

    @Test
    fun claudeWithoutSkipPermissionsIsBareCommand() {
        assertEquals("claude", agentChoice(AgentCli.Claude, skip = false).startCommand())
    }

    @Test
    fun codexWithSkipPermissionsBypassesApprovalsAndSandbox() {
        assertEquals(
            "codex --dangerously-bypass-approvals-and-sandbox",
            agentChoice(AgentCli.Codex, skip = true).startCommand(),
        )
    }

    @Test
    fun codexWithoutSkipPermissionsIsBareCommand() {
        assertEquals("codex", agentChoice(AgentCli.Codex, skip = false).startCommand())
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
        assertEquals("claude --dangerously-skip-permissions", choice.startCommand())
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
}
