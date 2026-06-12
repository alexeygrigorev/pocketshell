package com.pocketshell.app.projects

import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

class FolderListGatewayFallbackTest {

    @Test
    fun nativeTmuxNoServerFallsBackToPocketshellSessions() = runTest {
        val session = FakeSshSession(
            pocketshellResult = ExecResult(
                stdout = """
                    IDX  SESSION               CREATED
                      1  claude-main           2026-05-30 00:20:01
                      2  codex                 2026-05-30 00:19:58
                """.trimIndent(),
                stderr = "",
                exitCode = 0,
            ),
        )
        val gateway = SshFolderListGateway()

        val result = gateway.listSessionsFromNativeOrPocketshell(
            session = session,
            host = HOST,
            watchedRoots = emptyList(),
            listSessions = ExecResult(
                stdout = "",
                stderr = "error connecting to /tmp/tmux-1000/default (No such file or directory)",
                exitCode = 1,
            ),
        )

        assertTrue(result is FolderListResult.Sessions)
        val rows = (result as FolderListResult.Sessions).rows
        assertEquals(listOf("claude-main", "codex"), rows.map { it.sessionName })
        assertEquals(listOf(null, null), rows.map { it.cwd })
        assertEquals(listOf(false, false), rows.map { it.attached })
        assertTrue(session.execCommands.any { it.contains(SshFolderListGateway.POCKETSHELL_SESSIONS_COMMAND) })
    }

    @Test
    fun nativeAndPocketshellTmuxSocketMissingReturnsEmptySessions() = runTest {
        val socketMissing = ExecResult(
            stdout = "",
            stderr = "error connecting to /tmp/tmux-1000/default (No such file or directory)",
            exitCode = 1,
        )
        val session = FakeSshSession(pocketshellResult = socketMissing)
        val gateway = SshFolderListGateway()

        val result = gateway.listSessionsFromNativeOrPocketshell(
            session = session,
            host = HOST,
            watchedRoots = emptyList(),
            listSessions = socketMissing,
        )

        assertTrue(result is FolderListResult.Sessions)
        assertEquals(emptyList<FolderSessionRow>(), (result as FolderListResult.Sessions).rows)
        assertTrue(session.execCommands.any { it.contains(SshFolderListGateway.POCKETSHELL_SESSIONS_COMMAND) })
    }

    // --- Issue #726: memory-capped create-detached + two-layer fallback ---

    @Test
    fun cappedCreatePathEmitsTmuxctlCreateDetachedWithQuoting() = runTest {
        // tmuxctl present + supports create-detached -> the capped verb runs and
        // exits 0; no raw-tmux fallback should be issued.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val name = gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = null,
        )

        assertEquals(SESSION_NAME, name)
        val capped = session.execCommands.single { it.contains("create-detached") }
        // The recorded command is wrapped by pathAware (`/bin/sh -lc '<PATH=…;
        // body>'`), which re-escapes every inner single quote to `'"'"'`. Build
        // the EXACT expected wrapped string from the production companion
        // builder so the assertion can't drift from the real quoting.
        val expectedCapped = pathAware(
            SshFolderListGateway.cappedCreateSessionCommand(
                shellQuote(SESSION_NAME),
                shellQuote(CWD),
            ),
        )
        assertEquals(
            "capped command must invoke create-detached on the quoted name",
            expectedCapped,
            capped,
        )
        // Sanity: the inner verb + escaped name survive in the wrapped string,
        // and the capability probe precedes the real verb so an absent/old
        // tmuxctl is detected before we try to run it.
        assertTrue(
            "capped command must route create-detached on the escaped name: $capped",
            capped.contains("tmuxctl create-detached ${escapedInWrapper(SESSION_NAME)} -c ${escapedInWrapper(CWD)}"),
        )
        assertTrue(
            "capped command must probe tmuxctl support first: $capped",
            capped.contains("command -v tmuxctl") && capped.contains("create-detached --help"),
        )
        // No raw-tmux fallback when the capped path succeeded.
        assertFalse(
            "must NOT fall back to raw tmux when create-detached succeeded",
            session.execCommands.any { it.contains("new-session -A -d") },
        )
    }

    @Test
    fun binaryAbsentFallsBackToRawNewSession() = runTest {
        // The capped probe finds no tmuxctl on PATH and exits the sentinel
        // (TMUXCTL_UNSUPPORTED_EXIT_CODE) -> client falls back to raw tmux.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(
                    match = "create-detached",
                    result = ExecResult(
                        stdout = "",
                        stderr = "",
                        exitCode = SshFolderListGateway.TMUXCTL_UNSUPPORTED_EXIT_CODE,
                    ),
                ),
                CreateSessionFake.Rule(match = "new-session -A -d", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val name = gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = null,
        )

        assertEquals(SESSION_NAME, name)
        val fallback = session.execCommands.single { it.contains("new-session -A -d") }
        // Same pathAware wrapping applies to the raw fallback create; assert the
        // EXACT wrapped string from the production builder.
        val expectedFallback = pathAware(
            SshFolderListGateway.fallbackCreateSessionCommand(
                shellQuote(SESSION_NAME),
                shellQuote(CWD),
            ),
        )
        assertEquals(
            "fallback must be the raw uncapped create with the quoted name + cwd",
            expectedFallback,
            fallback,
        )
    }

    @Test
    fun verbAbsentOlderTmuxctlAlsoFallsBackToRawNewSession() = runTest {
        // Binary present but an OLDER tmuxctl lacks the verb. The capability
        // probe (`create-detached --help`) fails, so the wrapper exits the same
        // sentinel and the client falls back exactly like binary-absent — it is
        // NOT mistaken for a hard error (the pre-fix exit-127-only check threw).
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(
                    match = "create-detached",
                    result = ExecResult(
                        stdout = "",
                        stderr = "",
                        exitCode = SshFolderListGateway.TMUXCTL_UNSUPPORTED_EXIT_CODE,
                    ),
                ),
                CreateSessionFake.Rule(match = "new-session -A -d", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        val name = gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = null,
        )

        assertEquals(SESSION_NAME, name)
        assertTrue(
            "verb-absent host must fall back to raw new-session, not error",
            session.execCommands.any {
                it.contains("tmux new-session -A -d -s ${escapedInWrapper(SESSION_NAME)}")
            },
        )
    }

    @Test
    fun genuineCreateDetachedErrorIsSurfacedNotSwallowed() = runTest {
        // tmuxctl ran the real verb and it genuinely failed (e.g. systemd-run /
        // tmux error). The error must surface, NOT be downgraded to an uncapped
        // raw-tmux session behind the user's back.
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(
                    match = "create-detached",
                    result = ExecResult(
                        stdout = "",
                        stderr = "systemd-run: Failed to start transient scope unit",
                        exitCode = 1,
                    ),
                ),
            ),
        )
        val gateway = SshFolderListGateway()

        val ex = runCatching {
            gateway.createSessionOnSession(
                session = session,
                sessionName = SESSION_NAME,
                cwd = CWD,
                startCommand = null,
            )
        }.exceptionOrNull()
        assertTrue("expected a surfaced RuntimeException, got $ex", ex is RuntimeException)
        assertEquals("systemd-run: Failed to start transient scope unit", ex?.message)
        assertFalse(
            "a genuine create-detached failure must NOT fall back to raw tmux",
            session.execCommands.any { it.contains("new-session -A -d") },
        )
    }

    @Test
    fun startCommandSendKeysStillFiresAfterCappedCreate() = runTest {
        val session = CreateSessionFake(
            results = listOf(
                CreateSessionFake.Rule(match = "create-detached", result = ok()),
                CreateSessionFake.Rule(match = "send-keys", result = ok()),
            ),
        )
        val gateway = SshFolderListGateway()

        gateway.createSessionOnSession(
            session = session,
            sessionName = SESSION_NAME,
            cwd = CWD,
            startCommand = "pocketshell agent claude --dir '/home/me/proj dir'",
        )

        val sendKeys = session.execCommands.single { it.contains("send-keys") }
        // pathAware wraps the send-keys command too, re-escaping the inner single
        // quotes: assert the escaped session name target and the trailing
        // `Enter'` of the wrapper's closing quote.
        assertTrue(
            "send-keys must target the quoted session name and end with Enter: $sendKeys",
            sendKeys.contains("tmux send-keys -t ${escapedInWrapper(SESSION_NAME)}") &&
                sendKeys.endsWith("Enter'"),
        )
        // The capped create still ran before send-keys.
        assertTrue(session.execCommands.any { it.contains("create-detached") })
    }

    /**
     * A configurable fake [SshSession] for the create-session path: it records
     * every command, returns the start-directory-exists probe as success, and
     * resolves create/fallback/send-keys commands against ordered [Rule]s
     * (first matching substring wins). Anything unmatched is a failure so a
     * mis-routed command surfaces in the test rather than passing silently.
     */
    private class CreateSessionFake(
        private val results: List<Rule>,
    ) : SshSession {
        data class Rule(val match: String, val result: ExecResult)

        val execCommands = mutableListOf<String>()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            // The start-directory existence probe must pass so creation proceeds.
            if (command.contains("test -d")) {
                return ok()
            }
            val rule = results.firstOrNull { command.contains(it.match) }
            return rule?.result
                ?: ExecResult(stdout = "", stderr = "unexpected command: $command", exitCode = 1)
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

    private class FakeSshSession(
        private val pocketshellResult: ExecResult,
    ) : SshSession {
        val execCommands = mutableListOf<String>()

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return if (command.contains(SshFolderListGateway.POCKETSHELL_SESSIONS_COMMAND)) {
                pocketshellResult
            } else {
                ExecResult(stdout = "", stderr = "unexpected command: $command", exitCode = 1)
            }
        }

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = error("not used")

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() = Unit
    }

    private companion object {
        const val SESSION_NAME = "work session"
        const val CWD = "/home/me/proj dir"

        val HOST = HostEntity(
            id = 1L,
            name = "Walkthrough Docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 10L,
        )
    }
}

private fun ok(): ExecResult = ExecResult(stdout = "", stderr = "", exitCode = 0)

// --- Issue #726 test helpers: reproduce the production pathAware wrapping ---
//
// `SshFolderListGateway.createSessionOnSession` routes its commands through
// `pathAware(...)` -> `ReposRemoteSource.pathAwareCommand`, which wraps the body
// in `/bin/sh -lc '<PATH=…; body>'`. Because the whole body is single-quoted by
// the wrapper, every inner single quote is re-escaped to `'"'"'`. These helpers
// mirror that exact transform (ReposRemoteSource.pathAwareCommand / shellQuote)
// so assertions check the real recorded string instead of the pre-wrap form.

/** Single-quote a value, escaping inner quotes — mirrors `shellQuoteValue`. */
private fun shellQuote(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

/** Wrap a command exactly as production `pathAware` does. */
private fun pathAware(command: String): String =
    "/bin/sh -lc " + shellQuote("PATH=\"\$HOME/.local/bin:\$HOME/.cargo/bin:\$PATH\"; $command")

/**
 * The form a single-quoted value takes once it is INSIDE the pathAware wrapper:
 * the inner `'<value>'` has each `'` re-escaped by the outer wrapper's quoting.
 * e.g. `work session` -> `'"'"'work session'"'"'`.
 */
private fun escapedInWrapper(value: String): String =
    shellQuote(value).replace("'", "'\"'\"'")
