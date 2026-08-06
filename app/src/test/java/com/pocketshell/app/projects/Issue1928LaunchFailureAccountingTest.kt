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
import com.pocketshell.core.storage.entity.HostEntity
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
 * Issue #1928 — the post-create agent launch must be ACCOUNTED FOR.
 *
 * ## The reported defect
 *
 * `SshFolderListGateway.createSessionOnSession` created the tmux session, then
 * ran the optional agent launch (`tmux send-keys …`) and **discarded its
 * result**. A launch that exited non-zero still produced
 * `Result.success(resolvedName)`, so the app reported a working Claude/Codex
 * session while the user actually had an empty shell.
 *
 * ## Why these tests go red on a live mutant, not on a base checkout
 *
 * The fix changes the gateway's return TYPE (`Result<String>` →
 * `Result<SessionCreateOutcome>`, D22 hard cut), so a base checkout cannot even
 * compile this file. The reproduction is therefore a LIVE MUTANT: restore the
 * pre-fix body of `launchStartCommand` (run the send-keys, ignore the result,
 * `return null`) and every "must report LaunchFailed" test below fails while
 * the negative controls stay green. The mutation and its red output are
 * recorded on the issue.
 *
 * ## Class coverage (G2) — the launch half fails in THREE ways, and all three
 * were mis-reported, in two opposite directions
 *
 *  1. **non-zero `send-keys`** (pane target gone between create and send) —
 *     silently DISCARDED, reported as full success. The reported instance.
 *  2. **the #759 outdated-host pre-flight** — THREW, so the user was told
 *     "couldn't create session" while an orphan session sat on the host.
 *  3. **a bounded-exec timeout on the send-keys** — THREW
 *     `FolderListExecTimeoutException`, which is a stale-channel symptom, so
 *     `withLeaseSession` retried the WHOLE block on a fresh lease and created a
 *     SECOND session.
 *
 * Each has its own test here, plus the negative controls that stop the fix
 * degenerating into "always report a launch failure" (G6).
 */
class Issue1928LaunchFailureAccountingTest {

    // ---------------------------------------------------------------- (1) exit code

    /**
     * THE reported defect. The host really did refuse the launch (`send-keys`
     * exit 1, "can't find pane"), so the agent never started — the outcome must
     * say so instead of handing the caller a name as if all was well.
     */
    @Test
    fun nonZeroSendKeysIsReportedAsLaunchFailedNotSuccess() = runBlocking {
        val session = LaunchFake(
            sendKeys = ExecResult(
                stdout = "",
                stderr = "can't find pane: =git-pocketshell:",
                exitCode = 1,
            ),
        )

        val outcome = SshFolderListGateway().createSessionOnSession(
            session = session,
            sessionName = SESSION,
            cwd = CWD,
            startCommand = CLAUDE_LAUNCH,
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertEquals(
            "a non-zero launch must be a partial success, not a full one; got $outcome",
            SessionCreateOutcome.LaunchFailed(SESSION, "can't find pane: =git-pocketshell:"),
            outcome,
        )
    }

    /**
     * The created session is the user's and MUST survive (issue #1928 non-goal:
     * do not delete a created session because its optional launch failed). The
     * gateway must not "clean up" after itself, and must not create a second
     * session either.
     */
    @Test
    fun launchFailureNeitherKillsNorDuplicatesTheCreatedSession() = runBlocking {
        val session = LaunchFake(sendKeys = ExecResult("", "server exited unexpectedly", 1))

        val outcome = SshFolderListGateway().createSessionOnSession(
            session = session,
            sessionName = SESSION,
            cwd = CWD,
            startCommand = CLAUDE_LAUNCH,
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertTrue("expected a partial success, got $outcome", outcome is SessionCreateOutcome.LaunchFailed)
        assertEquals(
            "the outcome must name the session that EXISTS on the host",
            SESSION,
            outcome.sessionName,
        )
        assertFalse(
            "a failed launch must never kill the created session: ${session.execCommands}",
            session.execCommands.any { it.contains("kill-session") },
        )
        assertEquals(
            "exactly one create must have run: ${session.execCommands}",
            1,
            session.execCommands.count { it.contains("create-detached") },
        )
    }

    /**
     * The reason has to reach the user, so it has to reach the outcome. A blank
     * stderr falls back to stdout and then to the exit code — never an empty
     * "the agent didn't start: ." sentence.
     */
    @Test
    fun launchFailureCarriesTheHostReasonAndFallsBackToTheExitCode() = runBlocking {
        val withStdout = LaunchFake(sendKeys = ExecResult("no current client", "", 1))
        val silent = LaunchFake(sendKeys = ExecResult("", "   ", 3))
        val gateway = SshFolderListGateway()

        val fromStdout = gateway.createSessionOnSession(
            session = withStdout,
            sessionName = SESSION,
            cwd = CWD,
            startCommand = CLAUDE_LAUNCH,
            namePolicy = SessionNamePolicy.ExactName,
        )
        val fromExitCode = gateway.createSessionOnSession(
            session = silent,
            sessionName = SESSION,
            cwd = CWD,
            startCommand = CLAUDE_LAUNCH,
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertEquals(
            SessionCreateOutcome.LaunchFailed(SESSION, "no current client"),
            fromStdout,
        )
        assertEquals(
            SessionCreateOutcome.LaunchFailed(SESSION, "tmux send-keys exited 3"),
            fromExitCode,
        )
    }

    /**
     * The launch line can carry a `--profile` (and, for OpenCode, a long
     * env-stripping prelude). The reason we surface is the HOST's, never the
     * command, so nothing from the launch line can leak into a banner or a log.
     */
    @Test
    fun launchFailureDetailNeverEchoesTheLaunchCommand() = runBlocking {
        val session = LaunchFake(sendKeys = ExecResult("", "can't find pane", 1))

        val outcome = SshFolderListGateway().createSessionOnSession(
            session = session,
            sessionName = SESSION,
            cwd = CWD,
            startCommand = "pocketshell agent claude --dir '$CWD' --profile 'Claude (Z.AI)'",
            namePolicy = SessionNamePolicy.ExactName,
        )

        val detail = (outcome as SessionCreateOutcome.LaunchFailed).detail
        assertFalse("detail must not echo the launch line: $detail", detail.contains("--profile"))
        assertFalse("detail must not echo the launch line: $detail", detail.contains("pocketshell agent"))
        assertFalse(
            "the user-facing sentence must not echo it either",
            sessionLaunchFailedMessage(outcome.sessionName, detail).contains("--profile"),
        )
    }

    // ------------------------------------------------------ (2) outdated host CLI

    /**
     * Issue #759's outdated-host pre-flight runs AFTER the session has been
     * created, so throwing its hint reported "couldn't create session" for a
     * session that exists. Same words, honest accounting: it is now the reason
     * of a partial success, and the doomed line is still never typed.
     */
    @Test
    fun outdatedHostPreflightIsALaunchFailureNotACreateFailure() = runBlocking {
        val session = LaunchFake(agentSubcommandMissing = true)

        val outcome = runCatching {
            SshFolderListGateway().createSessionOnSession(
                session = session,
                sessionName = SESSION,
                cwd = CWD,
                startCommand = CLAUDE_LAUNCH,
                namePolicy = SessionNamePolicy.ExactName,
            )
        }

        val value = outcome.getOrNull()
        assertTrue(
            "an outdated host must NOT fail the create — the session exists; got $outcome",
            value is SessionCreateOutcome.LaunchFailed,
        )
        val detail = (value as SessionCreateOutcome.LaunchFailed).detail
        assertTrue(
            "must keep the actionable update hint: $detail",
            detail.contains(AgentLaunchVersionCheck.MIN_AGENT_POCKETSHELL_VERSION) &&
                detail.contains(AgentLaunchVersionCheck.UPDATE_COMMAND),
        )
        assertFalse("raw Click jargon must not leak: $detail", detail.contains("No such command"))
        assertTrue(
            "the session must still have been created: ${session.execCommands}",
            session.execCommands.any { it.contains("create-detached") },
        )
        assertFalse(
            "must not type a launch that is known to fail: ${session.execCommands}",
            session.execCommands.any { it.contains("send-keys") },
        )
    }

    // ------------------------------------------------------- (3) send-keys timeout

    /**
     * A send-keys that never returns used to throw `FolderListExecTimeoutException`.
     * That is a stale-channel symptom, so `withLeaseSession` evicted the lease and
     * retried the WHOLE create block — creating a SECOND tmux session while telling
     * the user the create had failed.
     *
     * This drives the full lease path (`createSession`, not
     * `createSessionOnSession`) precisely so the retry is reachable: exactly one
     * create must run, and the answer must be the partial success.
     */
    @Test
    fun wedgedSendKeysReportsLaunchFailedAndDoesNotRetryTheWholeCreate() = runBlocking {
        val session = LaunchFake(wedgeSendKeys = true)
        val gateway = gatewayWith(session, execReadTimeoutMs = 250L)

        val result = gateway.createSession(
            host = HOST,
            keyPath = KEY_PATH,
            passphrase = null,
            sessionName = SESSION,
            cwd = CWD,
            startCommand = CLAUDE_LAUNCH,
            namePolicy = SessionNamePolicy.ExactName,
        )

        val outcome = result.getOrNull()
        assertTrue(
            "a wedged launch must be a partial success, not a create failure; got $result",
            outcome is SessionCreateOutcome.LaunchFailed,
        )
        assertEquals(SESSION, outcome!!.sessionName)
        assertEquals(
            "the whole create must NOT be retried on a launch timeout — that is how " +
                "an orphan duplicate session appeared: ${session.execCommands}",
            1,
            session.execCommands.count { it.contains("create-detached") },
        )
    }

    /**
     * The launch step must not turn a thrown transport error into a create
     * failure either — the session is already on the host.
     */
    @Test
    fun thrownSendKeysErrorIsReportedAsLaunchFailed() = runBlocking {
        val session = LaunchFake(sendKeysThrows = IllegalStateException("SSH channel closed"))

        val outcome = SshFolderListGateway().createSessionOnSession(
            session = session,
            sessionName = SESSION,
            cwd = CWD,
            startCommand = CLAUDE_LAUNCH,
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertEquals(
            SessionCreateOutcome.LaunchFailed(SESSION, "SSH channel closed"),
            outcome,
        )
    }

    // ------------------------------------------------------------ negative controls

    /**
     * G6 guard: the fix must not report a launch failure for a launch that
     * WORKED, and the launch line must still be typed verbatim — including the
     * env-stripped OpenCode form, which has to reach the pane unaltered or the
     * agent bills per token instead of using the subscription.
     */
    @Test
    fun successfulLaunchIsStillReportedAsCreatedAndTypedVerbatim() = runBlocking {
        val session = LaunchFake()
        val opencode = "env -u ANTHROPIC_API_KEY -u OPENAI_API_KEY opencode"

        val outcome = SshFolderListGateway().createSessionOnSession(
            session = session,
            sessionName = SESSION,
            cwd = CWD,
            startCommand = opencode,
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertEquals(SessionCreateOutcome.Created(SESSION), outcome)
        val sendKeys = session.execCommands.single { it.contains("send-keys") }
        assertTrue(
            "the env-stripped OpenCode line must reach the pane verbatim: $sendKeys",
            sendKeys.contains("env -u ANTHROPIC_API_KEY -u OPENAI_API_KEY opencode"),
        )
    }

    /** A plain shell create asks for no launch, so it can never be partial. */
    @Test
    fun shellCreateWithNoStartCommandIsAlwaysCreated() = runBlocking {
        val session = LaunchFake(sendKeys = ExecResult("", "should never run", 1))

        val outcome = SshFolderListGateway().createSessionOnSession(
            session = session,
            sessionName = SESSION,
            cwd = CWD,
            startCommand = null,
            namePolicy = SessionNamePolicy.ExactName,
        )

        assertEquals(SessionCreateOutcome.Created(SESSION), outcome)
        assertFalse(
            "no launch was requested, so nothing may be typed: ${session.execCommands}",
            session.execCommands.any { it.contains("send-keys") },
        )
    }

    /**
     * The CREATE half still fails loudly. Nothing exists on the host on this
     * path, so reporting a partial success would be the mirror-image lie —
     * the tree would insert a row for a session that was never made.
     */
    @Test
    fun aFailedCreateIsStillAThrownFailureNotAPartialSuccess() = runBlocking {
        val session = LaunchFake(createResult = ExecResult("", "no space left on device", 1))

        val failure = runCatching {
            SshFolderListGateway().createSessionOnSession(
                session = session,
                sessionName = SESSION,
                cwd = CWD,
                startCommand = CLAUDE_LAUNCH,
                namePolicy = SessionNamePolicy.ExactName,
            )
        }.exceptionOrNull()

        assertTrue("a create failure must still throw, got $failure", failure is RuntimeException)
        assertTrue(
            "must carry the host's create reason: ${failure?.message}",
            failure?.message.orEmpty().contains("no space left on device"),
        )
        assertFalse(
            "a create that failed must not type a launch: ${session.execCommands}",
            session.execCommands.any { it.contains("send-keys") },
        )
    }

    /** The shared user-facing sentence has to say all three actionable things. */
    @Test
    fun theUserFacingSentenceNamesTheSessionTheFailureAndWhatToDo() {
        val message = sessionLaunchFailedMessage("git-pocketshell", "can't find pane")

        assertTrue(message, message.contains("git-pocketshell"))
        assertTrue(message, message.contains("was created"))
        assertTrue(message, message.contains("can't find pane"))
        assertTrue(message, message.contains("Open the session"))
        assertTrue(
            "a host that gave no reason must still read sensibly",
            sessionLaunchFailedMessage("s", "  ").contains("no reason"),
        )
    }

    /**
     * The typed outcome exists so nobody can read the session name without
     * choosing a branch — [fold] requires both, and neither branch is optional.
     */
    @Test
    fun foldForcesBothBranchesToBeHandled() {
        val seen = mutableListOf<String>()
        SessionCreateOutcome.Created("a").fold(
            onCreated = { seen += "created:$it" },
            onLaunchFailed = { name, detail -> seen += "failed:$name:$detail" },
        )
        SessionCreateOutcome.LaunchFailed("b", "why").fold(
            onCreated = { seen += "created:$it" },
            onLaunchFailed = { name, detail -> seen += "failed:$name:$detail" },
        )

        assertEquals(listOf("created:a", "failed:b:why"), seen)
    }

    /**
     * Issue #1928 caller sweep — the DASHBOARD.
     *
     * The acceptance criteria name the sessions dashboard alongside the folder
     * tree, the in-session sheet, the assistant and stale-session recovery. It
     * is the one surface with nothing to change: it does not use
     * [FolderListGateway] at all (it creates over the already-attached `-CC`
     * exec lane) and its create cannot request an agent launch, so it has no
     * partial success to collapse.
     *
     * "It doesn't apply" is exactly the kind of claim that silently stops being
     * true, so it is asserted rather than written down: wiring a
     * [FolderListGateway] into the dashboard, or giving its create a start
     * command, fails here and forces that new call site through the same
     * three-state handling as the other four.
     */
    @Test
    fun theSessionsDashboardHasNoGatewayCreateSeamToCollapse() {
        val dashboard = com.pocketshell.app.sessions.SessionsDashboardViewModel::class.java

        val injected = dashboard.declaredConstructors.flatMap { it.parameterTypes.toList() } +
            dashboard.declaredFields.map { it.type }
        assertFalse(
            "the dashboard gained a FolderListGateway seam — its create must now " +
                "handle SessionCreateOutcome explicitly (issue #1928 caller sweep)",
            injected.any { FolderListGateway::class.java.isAssignableFrom(it) },
        )

        val createParams = dashboard.declaredMethods
            .filter { it.name == "createSession" }
            .flatMap { it.parameterTypes.toList() }
        assertTrue(
            "the dashboard must still have a create to reason about",
            createParams.isNotEmpty(),
        )
        assertEquals(
            "the dashboard create takes (entry, name, startDirectory) and no start " +
                "command; a launch parameter here means it can now fail partially",
            listOf("Entry", "String", "String"),
            createParams.map { it.simpleName },
        )
    }

    // ------------------------------------------------------------------- helpers

    private fun gatewayWith(session: SshSession, execReadTimeoutMs: Long): SshFolderListGateway =
        SshFolderListGateway(
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            activeTmuxClients = ActiveTmuxClients(),
            sshLeaseManager = SshLeaseManager(
                connector = object : SshLeaseConnector {
                    override suspend fun connect(target: SshLeaseTarget): Result<SshSession> =
                        Result.success(session)
                },
                idleTtlMillis = 0L,
            ),
            sessionListParser = HostTmuxSessionListParser(),
            execReadTimeoutMs = execReadTimeoutMs,
        )

    /**
     * A fake host for the create+launch path: the directory exists, the launch
     * target is free (so the #976 collision guard passes), the capped create
     * answers [createResult], and the launch answers whichever failure mode the
     * test asked for.
     */
    private class LaunchFake(
        private val createResult: ExecResult = OK,
        private val sendKeys: ExecResult = OK,
        private val sendKeysThrows: Throwable? = null,
        private val wedgeSendKeys: Boolean = false,
        private val agentSubcommandMissing: Boolean = false,
    ) : SshSession {
        val execCommands: MutableList<String> =
            java.util.Collections.synchronizedList(mutableListOf<String>())

        override val isConnected: Boolean = true

        override suspend fun exec(command: String): ExecResult {
            execCommands += command
            return when {
                command.contains("pocketshell agent --help") ->
                    if (agentSubcommandMissing) {
                        ExecResult("", "Error: No such command 'agent'.", 2)
                    } else {
                        OK
                    }
                command.contains("pocketshell --version") ->
                    ExecResult("pocketshell, version 0.3.33", "", 0)
                command.contains("test -d") -> OK
                // Free target: the launch guard must not fire.
                command.contains("has-session") -> ExecResult("", "can't find session", 1)
                command.contains("create-detached") || command.contains("new-session") ->
                    createResult
                command.contains("send-keys") -> {
                    sendKeysThrows?.let { throw it }
                    if (wedgeSendKeys) awaitCancellation()
                    sendKeys
                }
                else -> OK
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

        override fun close() = Unit
    }

    private companion object {
        val OK = ExecResult(stdout = "", stderr = "", exitCode = 0)
        const val SESSION = "git-pocketshell"
        const val CWD = "/home/alexey/git/pocketshell"
        const val CLAUDE_LAUNCH = "pocketshell agent claude --dir '/home/alexey/git/pocketshell'"
        const val KEY_PATH = "/data/keys/id_ed25519"

        val HOST = HostEntity(
            id = 7L,
            name = "Docker",
            hostname = "10.0.2.2",
            port = 2222,
            username = "testuser",
            keyId = 3L,
        )
    }
}
