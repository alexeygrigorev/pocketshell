package com.pocketshell.app.pocketshell

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the centralised PATH-robust `pocketshell` wrapper
 * (issue #484). These assert the SHAPE of the emitted shell command so a
 * regression that drops the absolute-path probe or the PATH prefix is caught
 * without standing up a real SSH host. The behavioural proof (a host whose
 * binary is off the non-interactive PATH still resolves) lives in
 * `UsageRemoteSourceTest` / `UsageViewModelTest` via a fake [com.pocketshell.core.ssh.SshSession].
 */
class PocketshellCommandTest {

    @Test
    fun wrap_prependsCommonUserBinDirsToPath() {
        val cmd = PocketshellCommand.wrap("usage --json")
        // ~/.local/bin is the directory the stock ~/.bashrc interactive guard
        // hides from non-interactive SSH — it MUST be on the PATH prefix.
        assertTrue(cmd.contains("\$HOME/.local/bin"))
        assertTrue(cmd.contains("\$HOME/.cargo/bin"))
        assertTrue(cmd.contains("export PATH="))
        // The user's existing PATH is preserved, not clobbered.
        assertTrue(cmd.contains(":\$PATH\""))
    }

    @Test
    fun wrap_probesAbsoluteCandidatesWhenCommandVFails() {
        val cmd = PocketshellCommand.wrap("usage --json")
        assertTrue(cmd.contains("command -v pocketshell"))
        // Absolute fallback covers the common per-user install location.
        assertTrue(cmd.contains("\$HOME/.local/bin/pocketshell"))
        assertTrue(cmd.contains("/usr/local/bin/pocketshell"))
        // The first runnable candidate wins (executable test).
        assertTrue(cmd.contains("-x"))
    }

    @Test
    fun wrap_exits127WhenGenuinelyAbsent() {
        val cmd = PocketshellCommand.wrap("usage --json")
        // No binary resolved -> exit 127 so callers map it to ToolMissing.
        assertTrue(cmd.contains("exit 127"))
    }

    @Test
    fun wrap_runsResolvedBinaryWithCallerArgs() {
        val cmd = PocketshellCommand.wrap("jobs list --session 'codex'")
        // The resolved binary is invoked, not a hard-coded `pocketshell`.
        assertTrue(cmd.contains("\"\$__ps_bin\" jobs list --session 'codex'"))
    }

    @Test
    fun detect_printsResolvedPathAndExits127WhenAbsent() {
        val cmd = PocketshellCommand.detect()
        assertTrue(cmd.contains("\$HOME/.local/bin"))
        assertTrue(cmd.contains("\$HOME/.local/bin/pocketshell"))
        assertTrue(cmd.contains("command -v pocketshell"))
        assertTrue(cmd.contains("exit 127"))
        // detect prints the resolved path on success rather than running a subcommand.
        assertTrue(cmd.contains("printf"))
        assertFalse(cmd.contains("usage --json"))
    }

    @Test
    fun notInstalledHint_pointsAtServerSetupDoc() {
        assertTrue(PocketshellCommand.NOT_INSTALLED_HINT.contains("docs/server-setup.md"))
    }
}
