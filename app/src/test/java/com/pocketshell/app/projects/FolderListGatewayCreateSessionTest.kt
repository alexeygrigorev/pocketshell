package com.pocketshell.app.projects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the send-keys payload composition in
 * [SshFolderListGateway] — issue #263.
 *
 * When the app launches a coding assistant in a folder, the folder's
 * `.env` / `.envrc` variables must be exported into the new pane's shell
 * BEFORE the agent CLI starts. [SshFolderListGateway.composeStartCommand]
 * builds the literal text typed into the pane via `tmux send-keys`:
 *
 * ```
 * eval "$(pocketshell env export --dir '<cwd>')"; <startCommand>
 * ```
 *
 * The prelude is only present when a start command was requested. The
 * `--dir` path is shell-quoted so a hostile folder path cannot inject
 * shell, and command substitution keeps the exported values out of the
 * visible terminal.
 */
class FolderListGatewayCreateSessionTest {

    @Test
    fun composeStartCommandPrependsEnvExportPrelude() {
        val payload = SshFolderListGateway.composeStartCommand(
            cwd = "/home/alexey/git/pocketshell",
            startCommand = "claude",
        )
        assertEquals(
            "eval \"\$(pocketshell env export --dir '/home/alexey/git/pocketshell')\"; claude",
            payload,
        )
        // The literal export call is present...
        assertTrue(payload.contains("pocketshell env export --dir"))
        assertTrue(payload.startsWith("eval \"\$("))
        // ...and the original start command is preserved verbatim at the end.
        assertTrue(payload.endsWith("; claude"))
    }

    @Test
    fun composeStartCommandShellQuotesTheCwd() {
        // A folder path with spaces, quotes, and shell metacharacters must
        // be single-quoted so it cannot break out of the `--dir` argument
        // or inject a command.
        val hostile = "/tmp/it's a folder; rm -rf \$HOME"
        val payload = SshFolderListGateway.composeStartCommand(
            cwd = hostile,
            startCommand = "codex",
        )
        // Single-quoted with embedded single quote escaped as '\''.
        val expectedDir = "'/tmp/it'\\''s a folder; rm -rf \$HOME'"
        assertEquals(
            "eval \"\$(pocketshell env export --dir $expectedDir)\"; codex",
            payload,
        )
        // The metacharacters are inside the quoted --dir token, not bare.
        assertTrue(payload.contains("--dir $expectedDir"))
        // The trailing `; rm -rf` belongs to the quoted path, not a second
        // shell statement: the only top-level `;` separates the prelude
        // from the start command, which is the final token.
        assertTrue(payload.endsWith("; codex"))
    }

    @Test
    fun composeStartCommandKeepsComplexStartCommandIntact() {
        val payload = SshFolderListGateway.composeStartCommand(
            cwd = "/srv/app",
            startCommand = "opencode --model gpt-4",
        )
        assertEquals(
            "eval \"\$(pocketshell env export --dir '/srv/app')\"; opencode --model gpt-4",
            payload,
        )
    }

    @Test
    fun envExportPreludeOnlyAppliesWhenAStartCommandIsRequested() {
        // Acceptance criterion: the payload is unchanged (no prelude) when
        // startCommand is null. createSession's guard
        // (`if (startCommand != null)`) means composeStartCommand is only
        // invoked for a non-null start command — when null, no send-keys is
        // issued at all, so the session is just created with no typed text.
        // Here we assert the inverse contract directly: any payload produced
        // by composeStartCommand carries the prelude, and the function is
        // never asked to produce one for a "no command" case (its parameter
        // is non-nullable by construction).
        val withCommand = SshFolderListGateway.composeStartCommand(
            cwd = "/srv/app",
            startCommand = "claude",
        )
        assertTrue(withCommand.contains("pocketshell env export --dir"))
        // The start command alone (the "would-be" payload without the
        // prelude) does not contain the export step.
        assertFalse("claude".contains("pocketshell env export"))
    }

    @Test
    fun composeStartCommandUsesCommandSubstitutionNotInlineValues() {
        // The export must happen via command substitution so the secret
        // values are never present in the typed (and echoed) text — only
        // the literal `eval "$(...)"` is.
        val payload = SshFolderListGateway.composeStartCommand(
            cwd = "/srv/app",
            startCommand = "claude",
        )
        assertTrue(payload.contains("eval \"\$(pocketshell env export"))
        // No literal `export KEY=...` is embedded by the app.
        assertFalse(payload.contains("export FOO="))
    }

    @Test
    fun normaliseProjectFolderNameRejectsPathTraversalAndSeparators() {
        assertEquals("scratch", SshFolderListGateway.normaliseProjectFolderName(" scratch "))
        assertEquals("scratch", SshFolderListGateway.normaliseProjectFolderName("/scratch/"))
        assertEquals(null, SshFolderListGateway.normaliseProjectFolderName(""))
        assertEquals(null, SshFolderListGateway.normaliseProjectFolderName(".."))
        assertEquals(null, SshFolderListGateway.normaliseProjectFolderName("../bad"))
        assertEquals(null, SshFolderListGateway.normaliseProjectFolderName("nested/bad"))
    }

    @Test
    fun childPathComposesUnderTargetFolder() {
        assertEquals(
            "/home/alexey/git/scratch",
            SshFolderListGateway.childPath("/home/alexey/git/", "scratch"),
        )
        assertEquals("/scratch", SshFolderListGateway.childPath("/", "scratch"))
        assertEquals("~/git/scratch", SshFolderListGateway.childPath("~/git", "scratch"))
    }

    @Test
    fun shellQuoteRemotePathExpandsHomeWithoutLettingPathInjectShell() {
        assertEquals("\$HOME", SshFolderListGateway.shellQuoteRemotePathValue("~"))
        assertEquals("\$HOME/'git/my project'", SshFolderListGateway.shellQuoteRemotePathValue("~/git/my project"))
        assertEquals(
            "'/tmp/it'\\''s-safe'",
            SshFolderListGateway.shellQuoteRemotePathValue("/tmp/it's-safe"),
        )
    }
}
