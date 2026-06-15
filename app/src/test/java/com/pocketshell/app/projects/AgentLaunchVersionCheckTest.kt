package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [AgentLaunchVersionCheck] — graceful version-mismatch
 * detection for the agent-launch flow (issue #759).
 *
 * Two signals are covered:
 *   1. the PRIMARY missing-subcommand text match (the real dogfood failure:
 *      `Error: No such command 'agent'. (Did you mean one of: 'agent-log', 'usage'?)`)
 *      mapped to an actionable update hint, and
 *   2. the secondary `pocketshell --version` parse + minimum compare.
 */
class AgentLaunchVersionCheckTest {

    // --- Primary signal: missing `agent` subcommand in the launch output ---

    @Test
    fun detectsTheRealDogfoodNoSuchCommandError() {
        val stderr =
            "Error: No such command 'agent'. (Did you mean one of: 'agent-log', 'usage'?)"
        assertTrue(
            AgentLaunchVersionCheck.isAgentSubcommandMissing(
                stdout = "",
                stderr = stderr,
                exitCode = 2,
            ),
        )
    }

    @Test
    fun detectsNoSuchCommandRegardlessOfStream() {
        // Some shells fold stderr into stdout; the detector reads both.
        assertTrue(
            AgentLaunchVersionCheck.isAgentSubcommandMissing(
                stdout = "Error: No such command 'agent'.",
                stderr = "",
            ),
        )
    }

    @Test
    fun matchesDoubleQuotedClickForm() {
        assertTrue(
            AgentLaunchVersionCheck.isMissingSubcommand(
                "Error: No such command \"agent\".",
                "agent",
            ),
        )
    }

    @Test
    fun ordinaryAgentOutputIsNotAVersionMismatch() {
        // A working host: the agent launched, no "No such command".
        assertFalse(
            AgentLaunchVersionCheck.isAgentSubcommandMissing(
                stdout = "Starting claude in /home/alexey/tmp/test",
                stderr = "",
                exitCode = 0,
            ),
        )
    }

    @Test
    fun unrelatedErrorIsNotMappedToTheHint() {
        assertNull(
            AgentLaunchVersionCheck.mapLaunchFailureToHint(
                stdout = "",
                stderr = "claude: command not found",
                exitCode = 127,
            ),
        )
    }

    // --- error -> hint mapping ---

    @Test
    fun mapsMissingSubcommandToActionableHint() {
        val hint = AgentLaunchVersionCheck.mapLaunchFailureToHint(
            stdout = "",
            stderr = "Error: No such command 'agent'. (Did you mean one of: 'agent-log', 'usage'?)",
            exitCode = 2,
        )
        assertNotNull(hint)
        requireNotNull(hint)
        // The hint names the required minimum and gives a copyable command.
        assertTrue(hint.contains(AgentLaunchVersionCheck.MIN_AGENT_POCKETSHELL_VERSION))
        assertTrue(hint.contains(AgentLaunchVersionCheck.UPDATE_COMMAND))
        // Issue #779: the copyable command must bypass the host's global uv
        // `exclude-newer` cutoff, or it can silently report "Nothing to
        // upgrade" and the mismatch never clears.
        assertTrue(hint.contains("--exclude-newer-package pocketshell="))
        assertTrue(hint.contains("uv tool install --upgrade"))
        // The raw Click jargon must NOT leak into the user-facing hint.
        assertFalse(hint.contains("No such command"))
    }

    @Test
    fun hintNamesTheInstalledVersionWhenKnown() {
        val hint = AgentLaunchVersionCheck.outdatedHint(installedVersion = "0.3.33")
        assertTrue(hint.contains("0.3.33"))
        assertTrue(hint.contains(AgentLaunchVersionCheck.MIN_AGENT_POCKETSHELL_VERSION))
        assertTrue(hint.contains(AgentLaunchVersionCheck.UPDATE_COMMAND))
    }

    @Test
    fun hintNamesTheParsedVersionFromAFullVersionLine() {
        // outdatedHint tolerates a raw `pocketshell --version` line too.
        val hint = AgentLaunchVersionCheck.outdatedHint(
            installedVersion = "pocketshell, version 0.3.33",
        )
        assertTrue(hint.contains("0.3.33"))
    }

    @Test
    fun hintFallsBackToGenericPhrasingWhenVersionUnknown() {
        val hint = AgentLaunchVersionCheck.outdatedHint(installedVersion = null)
        assertTrue(hint.contains("too old"))
        assertTrue(hint.contains(AgentLaunchVersionCheck.MIN_AGENT_POCKETSHELL_VERSION))
        assertTrue(hint.contains(AgentLaunchVersionCheck.UPDATE_COMMAND))
    }

    @Test
    fun mappedHintNamesInstalledVersionWhenProvided() {
        val hint = AgentLaunchVersionCheck.mapLaunchFailureToHint(
            stdout = "",
            stderr = "Error: No such command 'agent'.",
            exitCode = 2,
            installedVersion = "0.3.33",
        )
        assertNotNull(hint)
        assertTrue(hint!!.contains("0.3.33"))
    }

    // --- `pocketshell --version` parse ---

    @Test
    fun parsesClickVersionOptionLine() {
        // Click's version_option(prog_name = "pocketshell") prints this form.
        assertEquals(
            "0.3.34",
            AgentLaunchVersionCheck.parseReportedVersion("pocketshell, version 0.3.34"),
        )
    }

    @Test
    fun parsesBareVersion() {
        assertEquals("0.3.33", AgentLaunchVersionCheck.parseReportedVersion("0.3.33"))
    }

    @Test
    fun parsesVersionWithTrailingNewlineAndSuffix() {
        assertEquals(
            "0.3.34",
            AgentLaunchVersionCheck.parseReportedVersion("pocketshell, version 0.3.34.dev1\n"),
        )
    }

    @Test
    fun parseReturnsNullWhenNoVersionToken() {
        assertNull(AgentLaunchVersionCheck.parseReportedVersion("command not found"))
    }

    // --- minimum-version compare ---

    @Test
    fun olderHostIsFlaggedAsOutdated() {
        assertTrue(AgentLaunchVersionCheck.isOlderThanRequired("0.3.33"))
    }

    @Test
    fun exactMinimumIsNotOutdated() {
        assertFalse(AgentLaunchVersionCheck.isOlderThanRequired("0.3.34"))
    }

    @Test
    fun newerHostIsNotOutdated() {
        assertFalse(AgentLaunchVersionCheck.isOlderThanRequired("0.3.40"))
    }

    @Test
    fun multiDigitPatchOrdersNumericallyNotLexically() {
        // "0.3.100" must be NEWER than "0.3.34" even though it sorts earlier
        // as a string.
        assertFalse(AgentLaunchVersionCheck.isOlderThanRequired("0.3.100"))
    }

    @Test
    fun unparseableVersionIsNotFlaggedOutdated() {
        // Version math is not authoritative — the missing-subcommand match is.
        // An unparseable version must never produce a false "too old" verdict.
        assertFalse(AgentLaunchVersionCheck.isOlderThanRequired("garbage"))
    }

    // --- agent-launch command recognition (gateway pre-flight gating) ---

    @Test
    fun recognisesAgentLaunchCommand() {
        assertTrue(
            AgentLaunchVersionCheck.isAgentLaunchCommand(
                "pocketshell agent claude --dir '/home/alexey/tmp/test'",
            ),
        )
        // Leading whitespace must not defeat the prefix match.
        assertTrue(
            AgentLaunchVersionCheck.isAgentLaunchCommand("  pocketshell agent codex --dir '/x'"),
        )
    }

    @Test
    fun shellAndNullCommandsAreNotAgentLaunches() {
        assertFalse(AgentLaunchVersionCheck.isAgentLaunchCommand(null))
        assertFalse(AgentLaunchVersionCheck.isAgentLaunchCommand("htop"))
        // A different pocketshell subcommand is not an agent launch.
        assertFalse(AgentLaunchVersionCheck.isAgentLaunchCommand("pocketshell usage"))
    }

    @Test
    fun probeCommandsAreTheExpectedWrapperLines() {
        assertEquals("pocketshell agent --help", AgentLaunchVersionCheck.AGENT_PROBE_COMMAND)
        assertEquals("pocketshell --version", AgentLaunchVersionCheck.VERSION_PROBE_COMMAND)
        assertEquals("pocketshell agent ", AgentLaunchVersionCheck.AGENT_COMMAND_PREFIX)
    }
}
