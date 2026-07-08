package com.pocketshell.app.git

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.ssh.SshPortForward
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Drives [GitHistoryGateway.commitDiff] over a scripted [SshSession] — issue
 * #1242. Pins the real command path (probe → ref-verify → byte-capped show) and
 * the load-bearing safety property: the server-side `head -c cap+1` byte cap
 * turns an over-cap diff into a truncated result rather than an unbounded read.
 */
class GitHistoryGatewayDiffTest {

    @Test
    fun `fetches and parses a commit diff over the session`() = runBlocking {
        val session = scripted { cmd ->
            when {
                "rev-parse --is-inside-work-tree" in cmd -> ExecResult("true\n", "", 0)
                "rev-parse --verify" in cmd -> ExecResult("a1b2c3d4\n", "", 0)
                "git -C" in cmd && "show" in cmd -> ExecResult(
                    "@@ -1 +1 @@\n-old\n+new\n",
                    "",
                    0,
                )
                else -> ExecResult("", "", 0)
            }
        }
        val diff = GitHistoryGateway(session).commitDiff("/repo", "a1b2c3d").getOrThrow()
        assertEquals("a1b2c3d", diff.ref)
        assertEquals(DiffLineKind.HunkHeader, diff.lines[0].kind)
        assertEquals(DiffLineKind.Removed, diff.lines[1].kind)
        assertEquals(DiffLineKind.Added, diff.lines[2].kind)
        assertFalse(diff.truncated)
    }

    @Test
    fun `pipes the show through a bounded head -c cap+1`() = runBlocking {
        var showCmd: String? = null
        val session = scripted { cmd ->
            when {
                "rev-parse --is-inside-work-tree" in cmd -> ExecResult("true\n", "", 0)
                "rev-parse --verify" in cmd -> ExecResult("sha\n", "", 0)
                "show" in cmd -> { showCmd = cmd; ExecResult("+x\n", "", 0) }
                else -> ExecResult("", "", 0)
            }
        }
        GitHistoryGateway(session).commitDiff("/repo", "abc", maxBytes = 1024).getOrThrow()
        assertTrue("show must be byte-capped via head -c", showCmd!!.contains("head -c 1025"))
        assertTrue("stderr must be dropped so it never pollutes the patch", showCmd!!.contains("2>/dev/null"))
    }

    @Test
    fun `over-cap output is marked truncated`() = runBlocking {
        // maxBytes = 8 → the gateway asks for 9 bytes; a 9-byte payload means the
        // real diff was longer, so it must come back truncated.
        val nineBytes = "+123456789" // 10 chars > 8 cap
        val session = scripted { cmd ->
            when {
                "rev-parse --is-inside-work-tree" in cmd -> ExecResult("true\n", "", 0)
                "rev-parse --verify" in cmd -> ExecResult("sha\n", "", 0)
                "show" in cmd -> ExecResult(nineBytes, "", 0)
                else -> ExecResult("", "", 0)
            }
        }
        val diff = GitHistoryGateway(session).commitDiff("/repo", "abc", maxBytes = 8).getOrThrow()
        assertTrue("payload over the byte cap must be truncated", diff.truncated)
    }

    @Test
    fun `under-cap output is not truncated`() = runBlocking {
        val session = scripted { cmd ->
            when {
                "rev-parse --is-inside-work-tree" in cmd -> ExecResult("true\n", "", 0)
                "rev-parse --verify" in cmd -> ExecResult("sha\n", "", 0)
                "show" in cmd -> ExecResult("+x\n", "", 0)
                else -> ExecResult("", "", 0)
            }
        }
        val diff = GitHistoryGateway(session).commitDiff("/repo", "abc", maxBytes = 1024).getOrThrow()
        assertFalse(diff.truncated)
    }

    @Test
    fun `unknown ref fails cleanly`() = runBlocking {
        val session = scripted { cmd ->
            when {
                "rev-parse --is-inside-work-tree" in cmd -> ExecResult("true\n", "", 0)
                "rev-parse --verify" in cmd -> ExecResult("", "", 1)
                else -> ExecResult("", "", 0)
            }
        }
        val result = GitHistoryGateway(session).commitDiff("/repo", "nope")
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is GitCommandException)
    }

    @Test
    fun `non-repo dir fails with NotAGitRepoException`() = runBlocking {
        val session = scripted { ExecResult("", "", 128) }
        val result = GitHistoryGateway(session).commitDiff("/tmp/plain", "abc")
        assertTrue(result.exceptionOrNull() is NotAGitRepoException)
    }

    private fun scripted(onExec: (String) -> ExecResult): SshSession = object : SshSession {
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult = onExec(command)
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("unused")
        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("unused")
        override fun startShell(): SshShell = error("unused")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("unused")
        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("unused")
        override fun close() = Unit
    }
}
