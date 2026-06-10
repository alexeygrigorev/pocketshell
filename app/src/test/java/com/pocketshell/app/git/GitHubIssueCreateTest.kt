package com.pocketshell.app.git

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Unit tests for `gh issue create` over SSH — issue #650 (epic #644 slice 6).
 *
 * The headline concern is **shell safety**: `title` / `body` are arbitrary user
 * free-text. These tests pin that [GitHistoryGateway.buildCreateIssueCommand]
 * single-quotes every user value so quotes, `$()`, backticks, `;`, `&&`,
 * pipes, and newlines stay literal and can't break out of their argument or
 * inject a second command. They also cover URL parsing and the
 * success/failure outcomes of the suspend [GitHistoryGateway.createIssue].
 */
class GitHubIssueCreateTest {

    // ---- shell safety: argv / command construction --------------------------

    @Test
    fun `plain title and body are single-quoted in the command`() {
        val cmd = GitHistoryGateway.buildCreateIssueCommand(
            dir = "/home/me/proj",
            title = "Add timeline view",
            body = "It would be nice to see commits.",
        )
        assertEquals(
            "cd '/home/me/proj' && gh issue create" +
                " --title 'Add timeline view'" +
                " --body 'It would be nice to see commits.'",
            cmd,
        )
    }

    @Test
    fun `single quotes in title are escaped, not closed`() {
        val cmd = GitHistoryGateway.buildCreateIssueCommand(
            dir = "/repo",
            title = "can't reproduce",
            body = "",
        )
        // The embedded ' is escaped as '\'' so the quoting never terminates early.
        assertEquals(
            "cd '/repo' && gh issue create --title 'can'\\''t reproduce' --body ''",
            cmd,
        )
    }

    @Test
    fun `command-injection attempts in title stay inside a single-quoted word`() {
        val malicious = "x'; rm -rf ~; echo '"
        val cmd = GitHistoryGateway.buildCreateIssueCommand(
            dir = "/repo",
            title = malicious,
            body = "ok",
        )
        // Every ' in the payload becomes '\'' — the rm/echo can never escape the
        // --title argument. The only UNescaped single quotes are the wrappers our
        // helper itself adds; the payload contributes none that close the word.
        val expectedTitleArg = "'" + malicious.replace("'", "'\\''") + "'"
        assertTrue(cmd.contains("--title $expectedTitleArg"))
        // No bare `rm -rf` token sitting outside a quoted word (i.e. not preceded
        // by an escape sequence): the injection is fully neutralised.
        assertFalse("; rm -rf ~; echo " in stripSingleQuotedWords(cmd))
    }

    @Test
    fun `dollar-paren and backtick substitutions in body are not evaluated`() {
        val body = "before \$(whoami) and `id` after"
        val cmd = GitHistoryGateway.buildCreateIssueCommand(
            dir = "/repo",
            title = "t",
            body = body,
        )
        // Inside single quotes $() and `` are literal — they appear verbatim,
        // wrapped, with no escaping needed (no single quotes in this payload).
        assertTrue(cmd.endsWith("--body '$body'"))
    }

    @Test
    fun `newlines and shell metacharacters in body are quoted literally`() {
        val body = "line1\nline2 && echo pwned | cat ; ls > /tmp/x"
        val cmd = GitHistoryGateway.buildCreateIssueCommand(
            dir = "/repo",
            title = "t",
            body = body,
        )
        assertTrue(cmd.endsWith("--body '$body'"))
        // The && / | / ; / > all live inside the single-quoted body word.
        assertFalse("&& echo pwned" in stripSingleQuotedWords(cmd))
    }

    @Test
    fun `dir with spaces and quotes is also safely quoted`() {
        val cmd = GitHistoryGateway.buildCreateIssueCommand(
            dir = "/home/me/a dir's path",
            title = "t",
            body = "b",
        )
        assertTrue(cmd.startsWith("cd '/home/me/a dir'\\''s path' && gh issue create"))
    }

    // ---- URL parsing --------------------------------------------------------

    @Test
    fun `parses the issue url from gh stdout`() {
        val url = GitHistoryGateway.parseCreatedIssueUrl(
            "https://github.com/alexeygrigorev/pocketshell/issues/651\n",
        )
        assertEquals("https://github.com/alexeygrigorev/pocketshell/issues/651", url)
    }

    @Test
    fun `parses the url even with a preamble line`() {
        val out = buildString {
            append("Creating issue in alexeygrigorev/pocketshell\n")
            append("\n")
            append("https://github.com/alexeygrigorev/pocketshell/issues/652\n")
        }
        assertEquals(
            "https://github.com/alexeygrigorev/pocketshell/issues/652",
            GitHistoryGateway.parseCreatedIssueUrl(out),
        )
    }

    @Test
    fun `returns null when no issue url is present`() {
        assertNull(GitHistoryGateway.parseCreatedIssueUrl(""))
        assertNull(GitHistoryGateway.parseCreatedIssueUrl("some unrelated output\n"))
        // A pull URL must not be mistaken for an issue URL.
        assertNull(
            GitHistoryGateway.parseCreatedIssueUrl(
                "https://github.com/owner/repo/pull/10\n",
            ),
        )
    }

    // ---- createIssue end-to-end (fake session) ------------------------------

    @Test
    fun `createIssue runs the safe command and returns the url on success`() = runTest {
        val session = FakeExecSession { cmd ->
            assertTrue("expected the quoted command", cmd.contains("--title 'My title'"))
            ExecResult(
                stdout = "https://github.com/owner/repo/issues/700\n",
                stderr = "",
                exitCode = 0,
            )
        }
        val gateway = GitHistoryGateway(session)

        val result = gateway.createIssue("/repo", "My title", "My body")

        assertTrue(result.isSuccess)
        assertEquals("https://github.com/owner/repo/issues/700", result.getOrNull())
        assertEquals(1, session.commands.size)
        assertEquals(
            "cd '/repo' && gh issue create --title 'My title' --body 'My body'",
            session.commands.single(),
        )
    }

    @Test
    fun `createIssue fails fast on a blank title without execing`() = runTest {
        val session = FakeExecSession { error("must not exec on blank title") }
        val gateway = GitHistoryGateway(session)

        val result = gateway.createIssue("/repo", "   ", "body")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GitCommandException)
        assertTrue(session.commands.isEmpty())
    }

    @Test
    fun `createIssue surfaces a non-zero exit as a failure with stderr`() = runTest {
        val session = FakeExecSession {
            ExecResult(stdout = "", stderr = "GraphQL: Could not resolve to a Repository", exitCode = 1)
        }
        val gateway = GitHistoryGateway(session)

        val result = gateway.createIssue("/repo", "t", "b")

        assertTrue(result.isFailure)
        val ex = result.exceptionOrNull()
        assertTrue(ex is GitCommandException)
        assertEquals("GraphQL: Could not resolve to a Repository", ex!!.message)
    }

    @Test
    fun `createIssue treats a zero-exit with no url as a failure`() = runTest {
        val session = FakeExecSession {
            ExecResult(stdout = "all good but no link\n", stderr = "", exitCode = 0)
        }
        val gateway = GitHistoryGateway(session)

        val result = gateway.createIssue("/repo", "t", "b")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GitCommandException)
    }

    @Test
    fun `createIssue propagates a transport error as a failure`() = runTest {
        val boom = RuntimeException("connection lost")
        val session = FakeExecSession { throw boom }
        val gateway = GitHistoryGateway(session)

        val result = gateway.createIssue("/repo", "t", "b")

        assertTrue(result.isFailure)
        assertEquals(boom, result.exceptionOrNull())
    }

    // ---- helpers ------------------------------------------------------------

    /**
     * Remove every single-quoted word (and our `'\''` escapes) from a command
     * line, leaving only the UNquoted shell skeleton. If an injection payload's
     * metacharacters survive in the skeleton, the quoting failed. Used to assert
     * a malicious title/body contributes nothing to the executable shell.
     */
    private fun stripSingleQuotedWords(cmd: String): String {
        // Collapse the `'\''` escape (a quoted ' ) first so it doesn't look like
        // a word boundary, then drop everything between paired single quotes.
        val withoutEscapes = cmd.replace("'\\''", "")
        return withoutEscapes.replace(Regex("'[^']*'"), "")
    }

    private class FakeExecSession(
        private val onExec: (String) -> ExecResult,
    ) : SshSession {
        val commands: MutableList<String> = mutableListOf()
        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            commands += command
            return onExec(command)
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() = Unit
    }
}
