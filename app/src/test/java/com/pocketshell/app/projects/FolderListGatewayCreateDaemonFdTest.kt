package com.pocketshell.app.projects

import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.app.sessions.HostTmuxSessionListParser
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Issue #1170: session creation FALSE-fails on a fresh tmux server.
 *
 * `tmuxctl create-detached` DOES create the detached session, but it starts the
 * tmux SERVER under a `systemd-run --user --scope` wrapper that keeps the spawned
 * server attached to the scope and lets it INHERIT the caller's stdout/stderr.
 * When those are the SSH exec channel PocketShell reads to EOF ([execBounded]),
 * the daemon holds the channel open, the read never reaches EOF, and the create
 * FALSE-fails on the [SshFolderListGateway.EXEC_READ_TIMEOUT_MS] bound even
 * though the session was created — after which the agent-launch never runs and
 * the session is left a BARE SHELL (the maintainer's three-message report:
 * timeout → "already exists" → shell-not-agent).
 *
 * The fix ([SshFolderListGateway.cappedCreateSessionCommand]) redirects the real
 * `create-detached` invocation's stdin/stdout to /dev/null (so the inherited
 * server fds are /dev/null, not the exec channel) and captures tmuxctl's own
 * stderr to a temp file that is echoed back afterwards. The exec then reaches EOF
 * as soon as tmuxctl returns, WITHOUT masking a genuine create error.
 *
 * These fakes model the fd-inheritance semantics directly: the create exec parks
 * forever ([awaitCancellation]) — the daemon holding the channel, i.e. no EOF —
 * UNLESS the command redirects the daemon's fds away from the channel
 * (`</dev/null >/dev/null`, which only the fix emits). So on BASE (no redirect)
 * every fresh-server case throws the bounded [FolderListExecTimeoutException] —
 * the false failure — and on the FIX the create returns promptly and the launch
 * proceeds. Deterministic under `runBlocking` with a short injected bound.
 */
class FolderListGatewayCreateDaemonFdTest {

    // The marker the #1170 fix emits: the real create-detached invocation
    // redirects the daemon's inherited stdin/stdout AWAY from the exec channel.
    private val fdRedirectMarker = "</dev/null >/dev/null"

    @Test
    fun freshServerAgentCreateNoLongerFalseFailsAndStartsAgent() {
        runBlocking {
            // Fresh server: the daemon this create spawns inherits the exec fd, so
            // the create read only reaches EOF if the command redirects it (the fix).
            val session = DaemonHoldingSshSession(daemonSpawnedByThisCreate = true)
            val gateway = newGateway(session)

            val name = gateway.createSessionOnSession(
                session = session,
                sessionName = SESSION_NAME,
                cwd = CWD,
                startCommand = "pocketshell agent claude --dir '$CWD'",
            )

            // Create returned promptly with no false timeout.
            assertEquals(SESSION_NAME, name)
            // The capped create ran with the daemon-fd redirect (the fix marker).
            val capped = session.execCommands.single {
                it.contains("create-detached") && it.contains(" -c ")
            }
            assertTrue(
                "the create-detached invocation must redirect the daemon's fds away " +
                    "from the exec channel (#1170): $capped",
                capped.contains(fdRedirectMarker),
            )
            // A SUCCESSFUL create must proceed to attach + start the AGENT — the
            // agent version pre-flight ran AND the launch line was typed in.
            assertTrue(
                "agent launch must pre-flight `pocketshell agent --help`",
                session.execCommands.any { it.contains("pocketshell agent --help") },
            )
            val sendKeys = session.execCommands.single { it.contains("send-keys") }
            assertTrue(
                "send-keys must type the agent launch line into the new session: $sendKeys",
                sendKeys.contains("agent claude") &&
                    sendKeys.contains("send-keys -t ${escapedInWrapper(SESSION_NAME)}"),
            )
        }
    }

    @Test
    fun freshServerShellCreateNoLongerFalseFailsAndStartsShell() {
        runBlocking {
            // Class coverage: SHELL create (start command is not an agent line) on a
            // fresh server hangs identically on base and must be fixed identically.
            val session = DaemonHoldingSshSession(daemonSpawnedByThisCreate = true)
            val gateway = newGateway(session)

            val name = gateway.createSessionOnSession(
                session = session,
                sessionName = SESSION_NAME,
                cwd = CWD,
                startCommand = "htop",
            )

            assertEquals(SESSION_NAME, name)
            val sendKeys = session.execCommands.single { it.contains("send-keys") }
            assertTrue(
                "send-keys must type the shell command into the new session: $sendKeys",
                sendKeys.contains("htop"),
            )
            // A plain shell must never run the agent version pre-flight.
            assertFalse(
                "a shell create must not probe `pocketshell agent --help`",
                session.execCommands.any { it.contains("pocketshell agent --help") },
            )
        }
    }

    @Test
    fun existingServerAgentCreateReturnsPromptlyAndStartsAgent() {
        runBlocking {
            // Class coverage: EXISTING server — the create-detached does NOT spawn a
            // new daemon that holds the exec fd, so the read reaches EOF regardless.
            // This passes on base and fix; it guards the fix against regressing the
            // already-working existing-server path.
            val session = DaemonHoldingSshSession(daemonSpawnedByThisCreate = false)
            val gateway = newGateway(session)

            val name = gateway.createSessionOnSession(
                session = session,
                sessionName = SESSION_NAME,
                cwd = CWD,
                startCommand = "pocketshell agent codex --dir '$CWD'",
            )

            assertEquals(SESSION_NAME, name)
            assertTrue(
                "existing-server create must still launch the agent",
                session.execCommands.any { it.contains("send-keys") && it.contains("agent codex") },
            )
        }
    }

    @Test
    fun genuineCreateFailureIsStillSurfacedWithTheFdRedirect() {
        runBlocking {
            // Acceptance: a GENUINE create failure (real tmux / systemd-run error)
            // must still surface with its message — the fd redirect must NOT
            // blanket-swallow stderr. On base this would hang (no redirect →
            // bounded timeout); the fix returns the real error result promptly and
            // surfaces the stderr, and does NOT type a launch into a failed session.
            val session = DaemonHoldingSshSession(
                daemonSpawnedByThisCreate = true,
                createResult = ExecResult(
                    stdout = "",
                    stderr = "systemd-run: Failed to start transient scope unit",
                    exitCode = 1,
                ),
            )
            val gateway = newGateway(session)

            val failure = runCatching {
                gateway.createSessionOnSession(
                    session = session,
                    sessionName = SESSION_NAME,
                    cwd = CWD,
                    startCommand = "pocketshell agent claude --dir '$CWD'",
                )
            }.exceptionOrNull()

            assertTrue("expected a surfaced RuntimeException, got $failure", failure is RuntimeException)
            assertFalse(
                "a create-timeout must not have masked the real error",
                failure is FolderListExecTimeoutException,
            )
            assertEquals(
                "systemd-run: Failed to start transient scope unit",
                failure?.message,
            )
            assertFalse(
                "must NOT type a launch line into a session that failed to create",
                session.execCommands.any { it.contains("send-keys") },
            )
        }
    }

    private fun newGateway(session: SshSession): SshFolderListGateway =
        SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(
                connector = SingleSessionConnector(session),
                idleTtlMillis = 0L,
            ),
            sessionListParser = HostTmuxSessionListParser(),
            // Short, real bound: the healthy fake returns in microseconds, the
            // daemon-holding fake parks far past this, so the wedge/heal split is
            // deterministic without a long real wait on the base (red) path.
            execReadTimeoutMs = 250L,
        )

    private class SingleSessionConnector(
        private val session: SshSession,
    ) : SshLeaseConnector {
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
            Result.success(session)
    }

    /**
     * A fake [SshSession] that reproduces the #1170 fd-inheritance semantics: the
     * capped create exec parks forever ([awaitCancellation]) — the daemon holding
     * the exec channel open, never reaching EOF — UNLESS the command redirects the
     * daemon's fds away from the channel (`</dev/null >/dev/null`, which only the
     * fix emits) OR the server already exists (no new daemon inherits the channel).
     */
    private class DaemonHoldingSshSession(
        private val daemonSpawnedByThisCreate: Boolean,
        private val createResult: ExecResult = ExecResult(stdout = "", stderr = "", exitCode = 0),
    ) : SshSession {
        val execCommands: MutableList<String> =
            java.util.Collections.synchronizedList(mutableListOf<String>())

        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return when {
                command.contains("test -d") -> ok()
                // Launch collision probe: the target name is free.
                command.contains("has-session") -> ExecResult(stdout = "", stderr = "", exitCode = 1)
                command.contains("create-detached") && command.contains(" -c ") -> {
                    val redirected = command.contains("</dev/null >/dev/null")
                    if (daemonSpawnedByThisCreate && !redirected) {
                        // BASE: the spawned daemon holds the exec channel; the read
                        // never reaches EOF. The gateway's bounded timeout must abandon
                        // this and surface the false failure — the #1170 bug.
                        awaitCancellation()
                    }
                    createResult
                }
                else -> ok()
            }
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

        override fun close() {
            closed = true
        }
    }

    private companion object {
        const val SESSION_NAME = "git-raised"
        const val CWD = "/home/alexey/git/raised"
    }
}

private fun ok(): ExecResult = ExecResult(stdout = "", stderr = "", exitCode = 0)

/** Single-quote a value, escaping inner quotes — mirrors `shellQuoteValue`. */
private fun shellQuoteForWrapper(value: String): String =
    "'" + value.replace("'", "'\"'\"'") + "'"

/**
 * The form a single-quoted value takes once it is INSIDE the pathAware wrapper:
 * each `'` is re-escaped by the outer wrapper's quoting. Mirrors the production
 * `pathAware` transform so the send-keys target can be matched on the real string.
 */
private fun escapedInWrapper(value: String): String =
    shellQuoteForWrapper(value).replace("'", "'\"'\"'")
