package com.pocketshell.app.assistant

import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.projects.FolderImportPayload
import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.app.projects.FolderListResult
import com.pocketshell.app.projects.SessionNamePolicy
import com.pocketshell.app.projects.SessionCreateOutcome
import com.pocketshell.app.repos.ReposJsonParser
import com.pocketshell.app.repos.ReposRemoteSource
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.dao.HostDao
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Verifies the production [AppAssistantActions] bridges to the right
 * surfaces (issue #266): clone/repos graceful gh-unauthenticated message,
 * run_command via the terminal bridge, navigation, and create_file SSH path.
 */
class AppAssistantActionsTest {

    /** Minimal [SshSession] fake: routes `exec` to a scripted responder. */
    private class FakeSession(
        private val responder: (String) -> ExecResult,
    ) : SshSession {
        val execed = mutableListOf<String>()
        override val isConnected: Boolean = true
        override suspend fun exec(command: String): ExecResult {
            execed += command
            return responder(command)
        }
        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward =
            throw NotImplementedError()
        override fun startShell(): SshShell = throw NotImplementedError()
        override suspend fun uploadFile(file: File, remotePath: String): String = remotePath
        override suspend fun uploadStream(input: InputStream, length: Long, name: String, remotePath: String): String =
            remotePath
        override fun close() = Unit
    }

    private class RecordingBridge : SessionActionBridge {
        var host: String? = "dev"
        var sendResult: Result<Unit> = Result.success(Unit)
        var sendPromptResult: Result<Unit> = Result.success(Unit)
        val sent = mutableListOf<String>()
        val sentPrompts = mutableListOf<Pair<String, String>>()
        val navigated = mutableListOf<AppDestination>()
        override fun activeHostName(): String? = host
        override fun activeCwd(): String? = "/home/dev/proj"
        override fun activeSessionName(): String? = "main"
        override fun currentScreenLabel(): String = "tmux"
        override suspend fun sendCommand(command: String): Result<Unit> {
            sent += command
            return sendResult
        }
        override suspend fun sendPromptToSession(sessionName: String, prompt: String): Result<Unit> {
            sentPrompts += sessionName to prompt
            return sendPromptResult
        }
        override fun navigate(destination: AppDestination) { navigated += destination }
    }

    private class FakeHostDao(private val host: HostEntity?) : HostDao {
        override fun getAll(): Flow<List<HostEntity>> = flowOf(listOfNotNull(host))
        override suspend fun getById(id: Long): HostEntity? = host
        override fun getEnabled(): Flow<List<HostEntity>> = flowOf(listOfNotNull(host))
        override suspend fun insert(host: HostEntity): Long = 1
        override suspend fun update(host: HostEntity) = Unit
        override suspend fun delete(host: HostEntity) = Unit
        override suspend fun deleteById(id: Long) = Unit
    }

    private val params = AssistantSshParams(
        hostId = 1,
        hostName = "dev",
        hostname = "1.2.3.4",
        port = 22,
        username = "dev",
        keyPath = "/keys/dev",
        passphrase = null,
    )

    private val hostEntity = HostEntity(id = 1, name = "dev", hostname = "1.2.3.4", username = "dev", keyId = 1)

    /**
     * Issue #1820: a [FolderListGateway] fake that **HONOURS** [SessionNamePolicy]
     * instead of discarding it.
     *
     * A fake that returns `Result.success(sessionName)` whatever the policy is
     * cannot fail when a caller passes the wrong one — and "each call site
     * declares its intent" is the entire fix, so that declaration is the
     * load-bearing state. This one models what the real gateway does against a
     * host that already has [liveSessions]:
     *
     *  - [SessionNamePolicy.UniqueOnHost] → resolve the smallest free
     *    `<base>`/`<base>-2`/`<base>-3`… (what the gateway's socket-wide name
     *    sweep resolves against the host, #1820/#2378) and create it.
     *  - [SessionNamePolicy.ExactName] → use the name verbatim; a LAUNCH
     *    (`startCommand != null`) onto a taken name is refused exactly as the
     *    #976 `has-session` routing guard refuses it, rather than typing into a
     *    live pane.
     *
     * So a caller that regresses to `ExactName` here produces the user-visible
     * `Failed to start session: …` the maintainer would see on device.
     */
    private class NameResolvingGateway(
        liveSessions: Set<String> = emptySet(),
        /**
         * Issue #1928: when set, every create reports PARTIAL success — the tmux
         * session exists but its agent launch failed with this reason.
         */
        private val launchFailureDetail: String? = null,
    ) : FolderListGateway {
        val live = liveSessions.toMutableSet()
        val policies = mutableListOf<SessionNamePolicy>()
        val createdNames = mutableListOf<String>()

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ) = FolderListResult.Sessions(emptyList())

        override suspend fun createSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
            cwd: String,
            startCommand: String?,
            namePolicy: SessionNamePolicy,
        ): Result<SessionCreateOutcome> {
            policies += namePolicy
            val resolved = when (namePolicy) {
                SessionNamePolicy.ExactName -> sessionName
                SessionNamePolicy.UniqueOnHost -> {
                    if (sessionName !in live) {
                        sessionName
                    } else {
                        var n = 2
                        while ("$sessionName-$n" in live) n++
                        "$sessionName-$n"
                    }
                }
            }
            if (startCommand != null && resolved in live) {
                // Mirrors the gateway's #976 routing-safety guard.
                return Result.failure(
                    RuntimeException("session '$resolved' already exists on this host"),
                )
            }
            live += resolved
            createdNames += resolved
            return Result.success(
                launchFailureDetail
                    ?.let { SessionCreateOutcome.LaunchFailed(resolved, it) }
                    ?: SessionCreateOutcome.Created(resolved),
            )
        }

        override suspend fun createEmptyProject(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            parentPath: String,
            folderName: String,
        ): Result<String> = Result.success("$parentPath/$folderName")

        override suspend fun importFile(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            folderPath: String,
            payload: FolderImportPayload,
        ): Result<String> = Result.success("$folderPath/${payload.remoteName}")

        override suspend fun killSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
        ): Result<Unit> = Result.success(Unit)
    }

    private fun actions(
        bridge: SessionActionBridge = RecordingBridge(),
        responder: (String) -> ExecResult,
        gateway: FolderListGateway = object : FolderListGateway {
            override suspend fun listSessionsWithFolder(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                watchedRoots: List<ProjectRootEntity>,
            ) =
                FolderListResult.Sessions(emptyList())
            override suspend fun createSession(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                sessionName: String,
                cwd: String,
                startCommand: String?,
                namePolicy: SessionNamePolicy,
            ): Result<SessionCreateOutcome> = Result.success(SessionCreateOutcome.Created(sessionName))

            override suspend fun createEmptyProject(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                parentPath: String,
                folderName: String,
            ): Result<String> = Result.success("$parentPath/$folderName")

            override suspend fun importFile(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                folderPath: String,
                payload: FolderImportPayload,
            ): Result<String> = Result.success("$folderPath/${payload.remoteName}")

            override suspend fun killSession(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                sessionName: String,
            ): Result<Unit> = Result.success(Unit)
        },
        createdProjects: MutableList<String> = mutableListOf(),
    ): AppAssistantActions {
        val executor = object : AssistantSshExecutor {
            override suspend fun <T> withSession(
                params: AssistantSshParams,
                block: suspend (com.pocketshell.core.ssh.SshSession) -> T,
            ): Result<T> = Result.success(block(FakeSession(responder)))
        }
        return AppAssistantActions(
            bridge = bridge,
            hostDao = FakeHostDao(hostEntity),
            folderListGateway = gateway,
            reposRemoteSource = ReposRemoteSource(ReposJsonParser()),
            sshExecutor = executor,
            resolveParams = { name -> if (name == "dev") params else null },
            activeParams = { params },
            onProjectCreated = { createdProjects += it },
        )
    }

    @Test
    fun listRepos_ghUnauthenticated_returnsClearMessage() = runTest {
        val actions = actions(responder = { ExecResult("", "gh: not authenticated. Run gh auth login.", 1) })
        val result = actions.listRepos()
        assertTrue(result.contains("GitHub not authenticated"))
    }

    @Test
    fun cloneRepo_ghUnauthenticated_doesNotCrash() = runTest {
        val actions = actions(responder = { ExecResult("", "error: gh auth login required", 1) })
        val result = actions.cloneRepo("owner/repo", null)
        assertFalse(result.ok)
        assertTrue(result.message.contains("GitHub not authenticated"))
    }

    @Test
    fun cloneRepo_success_returnsPath() = runTest {
        val actions = actions(responder = { cmd ->
            if (cmd.contains("repos clone")) ExecResult("/home/dev/git/repo\n", "", 0)
            else ExecResult("", "", 0)
        })
        val result = actions.cloneRepo("owner/repo", null)
        assertTrue(result.ok)
        assertTrue(result.message.contains("/home/dev/git/repo"))
    }

    @Test
    fun listRepos_toolMissing_returnsInstallMessage() = runTest {
        val actions = actions(responder = { ExecResult("", "command not found", 127) })
        val result = actions.listRepos()
        assertTrue(result.contains("not installed"))
    }

    @Test
    fun runCommand_reachesTerminalBridge() = runTest {
        val bridge = RecordingBridge()
        val actions = actions(bridge = bridge, responder = { ExecResult("", "", 0) })
        val result = actions.runCommand("git status")
        assertTrue(result.ok)
        assertEquals(listOf("git status"), bridge.sent)
    }

    @Test
    fun runCommand_noActiveHost_errors() = runTest {
        val bridge = RecordingBridge().apply { host = null }
        val actions = actions(bridge = bridge, responder = { ExecResult("", "", 0) })
        val result = actions.runCommand("git status")
        assertFalse(result.ok)
        assertTrue(bridge.sent.isEmpty())
    }

    @Test
    fun runCommand_paneSendFailureReturnsToolError() = runTest {
        val bridge = RecordingBridge().apply {
            sendResult = Result.failure(IllegalStateException("failed to write tmux command `send-keys`"))
        }
        val actions = actions(bridge = bridge, responder = { ExecResult("", "", 0) })

        val result = actions.runCommand("git status")

        assertFalse(result.ok)
        assertEquals(listOf("git status"), bridge.sent)
        assertTrue(result.message.contains("Failed to send command to the active terminal"))
        assertTrue(result.message.contains("send-keys"))
    }

    @Test
    fun openFolder_navigatesToFolderList() = runTest {
        val bridge = RecordingBridge()
        val actions = actions(bridge = bridge, responder = { ExecResult("", "", 0) })
        actions.openFolder("dev", "/home/dev/proj")
        assertTrue(bridge.navigated.any { it is AppDestination.FolderList })
    }

    @Test
    fun startSession_createsAndNavigates() = runTest {
        val bridge = RecordingBridge()
        val actions = actions(bridge = bridge, responder = { ExecResult("", "", 0) })
        val result = actions.startSession("dev", "/home/dev/proj", "codex")
        assertTrue(result.ok)
        assertTrue(bridge.navigated.any { it is AppDestination.TmuxSession })
    }

    @Test
    fun sendPromptToSession_reachesTerminalBridge() = runTest {
        val bridge = RecordingBridge()
        val actions = actions(bridge = bridge, responder = { ExecResult("", "", 0) })
        val result = actions.sendPromptToSession("course-management-agent", "write tests")
        assertTrue(result.ok)
        assertEquals(listOf("course-management-agent" to "write tests"), bridge.sentPrompts)
    }

    @Test
    fun sendPromptToSession_paneSendFailureFallsBackToTmuxSendKeys() = runTest {
        val bridge = RecordingBridge().apply {
            sendPromptResult = Result.failure(IllegalStateException("no focused agent pane"))
        }
        val execed = mutableListOf<String>()
        val actions = actions(bridge = bridge, responder = {
            execed += it
            ExecResult("", "", 0)
        })
        val result = actions.sendPromptToSession("course-management-agent", "write tests")
        assertTrue(result.ok)
        assertEquals(listOf("course-management-agent" to "write tests"), bridge.sentPrompts)
        assertEquals(1, execed.size)
        assertTrue(execed.single().contains("tmux send-keys"))
        assertTrue(execed.single().contains("course-management-agent"))
    }

    @Test
    fun sendPromptToSession_tmuxSendFailureReturnsToolError() = runTest {
        val bridge = RecordingBridge().apply {
            sendPromptResult = Result.failure(IllegalStateException("no focused agent pane"))
        }
        val actions = actions(bridge = bridge, responder = { ExecResult("", "no tmux target", 1) })
        val result = actions.sendPromptToSession("course-management-agent", "write tests")
        assertFalse(result.ok)
        assertTrue(result.message.contains("Failed to send prompt to session"))
        assertTrue(result.message.contains("no tmux target"))
    }

    @Test
    fun createProject_usesFolderGatewayAndReportsCreatedPath() = runTest {
        val created = mutableListOf<String>()
        val actions = actions(responder = { ExecResult("", "", 0) }, createdProjects = created)
        val result = actions.createProject("dev", "/home/dev/git", "new-app")
        assertTrue(result.ok)
        assertEquals(listOf("/home/dev/git/new-app"), created)
    }

    @Test
    fun cloneRepo_successReportsCreatedPathForHostDetailRefresh() = runTest {
        val created = mutableListOf<String>()
        val actions = actions(
            responder = { cmd ->
                if (cmd.contains("repos clone")) ExecResult("/home/dev/git/repo\n", "", 0)
                else ExecResult("", "", 0)
            },
            createdProjects = created,
        )
        val result = actions.cloneRepo("owner/repo", null)
        assertTrue(result.ok)
        assertEquals(listOf("/home/dev/git/repo"), created)
    }

    @Test
    fun resolveFolder_buildsFullCandidateSet_fromSessionsAndDiscovered() = runTest {
        val gateway = object : FolderListGateway {
            override suspend fun listSessionsWithFolder(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                watchedRoots: List<ProjectRootEntity>,
            ) = FolderListResult.Sessions(
                rows = listOf(
                    com.pocketshell.app.projects.FolderSessionRow(
                        sessionName = "pocketshell",
                        lastActivity = null,
                        attached = true,
                        cwd = "/home/dev/git/pocketshell",
                    ),
                ),
                projectFoldersByRoot = mapOf(
                    "/home/dev/git" to listOf("/home/dev/git/ssh-auto-forward", "/home/dev/git/notes"),
                ),
            )
            override suspend fun createSession(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                sessionName: String,
                cwd: String,
                startCommand: String?,
                namePolicy: SessionNamePolicy,
            ): Result<SessionCreateOutcome> = Result.success(SessionCreateOutcome.Created(sessionName))
            override suspend fun createEmptyProject(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                parentPath: String,
                folderName: String,
            ): Result<String> = Result.success("$parentPath/$folderName")
            override suspend fun importFile(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                folderPath: String,
                payload: FolderImportPayload,
            ): Result<String> = Result.success("$folderPath/${payload.remoteName}")

            override suspend fun killSession(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                sessionName: String,
            ): Result<Unit> = Result.success(Unit)
        }
        val actions = actions(responder = { ExecResult("", "", 0) }, gateway = gateway)

        // Discovered-only folder (no live session) is still resolvable — the
        // candidate set is NOT limited to live sessions.
        val resolved = actions.resolveFolder("dev", "ssh auto forward")
        assertTrue(resolved is FolderResolutionResult.Resolved)
        val resolution = (resolved as FolderResolutionResult.Resolved).resolution
        assertTrue(resolution is FolderResolution.Confident)
        assertEquals(
            "/home/dev/git/ssh-auto-forward",
            (resolution as FolderResolution.Confident).candidate.path,
        )
    }

    @Test
    fun resolveFolder_unknownHost_returnsUnavailable() = runTest {
        val actions = actions(responder = { ExecResult("", "", 0) })
        val result = actions.resolveFolder("ghost", "anything")
        assertTrue(result is FolderResolutionResult.Unavailable)
    }

    @Test
    fun createFile_writesViaSsh() = runTest {
        var heredoc = ""
        val actions = actions(responder = { cmd -> heredoc = cmd; ExecResult("", "", 0) })
        val result = actions.createFile("/home/dev/notes.txt", "hello")
        assertTrue(result.ok)
        assertTrue(heredoc.contains("cat >"))
        assertTrue(heredoc.contains("hello"))
    }

    // --- Issue #1820: the voice/assistant "start a <agent> session in <cwd>"
    // path. It derives its name from the directory ALONE and has never had a
    // known-names list to subtract, so before #1820 it always requested the bare
    // base — and in an occupied folder the gateway's #976 routing guard threw and
    // the user got `Failed to start session: …`. Nothing pinned this path.
    // These use [NameResolvingGateway], which HONOURS the policy, so a caller
    // that regresses to `ExactName` reproduces exactly that failure. ---

    @Test
    fun startSessionInAnOccupiedFolderCreatesASuffixedSessionInsteadOfFailing() = runTest {
        // The reported state: `proj` already has a live session with the name
        // this path derives from the directory.
        val gateway = NameResolvingGateway(liveSessions = setOf("proj"))
        val bridge = RecordingBridge()
        val actions = actions(
            bridge = bridge,
            responder = { ExecResult("", "", 0) },
            gateway = gateway,
        )

        val result = actions.startSession(host = "dev", cwd = "/home/dev/proj", agent = "claude")

        assertTrue(
            "starting an agent in an ALREADY-OCCUPIED folder must succeed; got ${result.message}",
            result.ok,
        )
        assertEquals(listOf("proj-2"), gateway.createdNames)
        assertTrue(
            "the confirmation must name the RESOLVED session, not the requested base: ${result.message}",
            result.message.contains("proj-2"),
        )
        // And the app must navigate to the session that actually exists.
        val destination = bridge.navigated.filterIsInstance<AppDestination.TmuxSession>().single()
        assertEquals("proj-2", destination.sessionName)
    }

    /**
     * Issue #1928 — the ASSISTANT caller of the partial-success outcome.
     *
     * "Start a Claude session in ~/proj" that creates the session but fails to
     * launch Claude used to answer `ok` ("Started claude session …") and
     * navigate — the assistant confidently reporting an agent that is not there.
     * Reporting a plain create failure would be the opposite lie: an orphan
     * session on the host that the answer never mentions. The answer must name
     * the session that exists AND say the agent did not start.
     */
    @Test
    fun startSessionLaunchFailureIsReportedAsAProblemNamingTheCreatedSession() = runTest {
        val gateway = NameResolvingGateway(launchFailureDetail = "can't find pane: =proj:")
        val bridge = RecordingBridge()
        val actions = actions(
            bridge = bridge,
            responder = { ExecResult("", "", 0) },
            gateway = gateway,
        )

        val result = actions.startSession(host = "dev", cwd = "/home/dev/proj", agent = "claude")

        assertFalse(
            "an agent that never started must not be reported as ok: ${result.message}",
            result.ok,
        )
        assertTrue("must name the created session: ${result.message}", result.message.contains("proj"))
        assertTrue(
            "must say the session WAS created: ${result.message}",
            result.message.contains("was created"),
        )
        assertTrue(
            "must carry the host's reason: ${result.message}",
            result.message.contains("can't find pane: =proj:"),
        )
        assertTrue(
            "must not navigate into a session whose agent never started",
            bridge.navigated.none { it is AppDestination.TmuxSession },
        )
        assertEquals(
            "the created session must be left alone on the host",
            listOf("proj"),
            gateway.createdNames,
        )
    }

    @Test
    fun startSessionAsksTheHostToResolveTheName() = runTest {
        val gateway = NameResolvingGateway()
        val actions = actions(responder = { ExecResult("", "", 0) }, gateway = gateway)

        actions.startSession(host = "dev", cwd = "/home/dev/proj", agent = "codex")

        // The declaration itself is the fix; pin it so a flip to ExactName
        // cannot land silently.
        assertEquals(listOf(SessionNamePolicy.UniqueOnHost), gateway.policies)
    }

    @Test
    fun startSessionInAFreeFolderStillUsesTheBareDerivedName() = runTest {
        // The `-2` walk must not fire when the base is free — host-side
        // resolution has to be a no-op in the common case.
        val gateway = NameResolvingGateway(liveSessions = setOf("unrelated"))
        val actions = actions(responder = { ExecResult("", "", 0) }, gateway = gateway)

        val result = actions.startSession(host = "dev", cwd = "/home/dev/proj", agent = "opencode")

        assertTrue(result.ok)
        assertEquals(listOf("proj"), gateway.createdNames)
    }

    @Test
    fun sendPromptSshFallbackTargetsTheSessionExactlyNotBySiblingPrefix() = runTest {
        // Issue #1820 sweep: when the in-app bridge cannot deliver, the prompt
        // is typed over SSH with `tmux send-keys -t <session>`. A BARE target
        // prefix-matches, so with `proj` gone and `proj-2` alive the user's
        // prompt is typed into the NEIGHBOUR's pane — the #976 misroute class,
        // and `<base>` + `<base>-2` is now the routine outcome of a second
        // same-folder create. `=<session>:` is the exact pane form (a bare
        // `=<session>` is rejected outright by tmux for a pane target).
        val bridge = RecordingBridge().apply {
            sendPromptResult = Result.failure(RuntimeException("bridge not attached"))
        }
        val execed = mutableListOf<String>()
        val actions = actions(
            bridge = bridge,
            responder = { cmd -> execed += cmd; ExecResult("", "", 0) },
        )

        val result = actions.sendPromptToSession("proj", "ship it")

        assertTrue(result.ok)
        val command = execed.single()
        assertTrue(
            "both send-keys must use the EXACT pane target '=proj:'; got $command",
            command.split("send-keys -t ").drop(1).all { it.startsWith("'=proj:'") },
        )
        assertEquals(
            "there must be exactly two send-keys (literal text, then Enter); got $command",
            2,
            command.split("send-keys -t ").size - 1,
        )
    }

    @Test
    fun startSessionSurfacesAGenuineGatewayFailure() = runTest {
        // Fail-safe direction: a real create failure still reports cleanly
        // rather than being masked by the resolution.
        val failing = object : FolderListGateway {
            override suspend fun listSessionsWithFolder(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                watchedRoots: List<ProjectRootEntity>,
            ) = FolderListResult.Sessions(emptyList())
            override suspend fun createSession(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                sessionName: String,
                cwd: String,
                startCommand: String?,
                namePolicy: SessionNamePolicy,
            ): Result<SessionCreateOutcome> = Result.failure(RuntimeException("start directory does not exist"))
            override suspend fun createEmptyProject(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                parentPath: String,
                folderName: String,
            ): Result<String> = error("not used")
            override suspend fun importFile(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                folderPath: String,
                payload: FolderImportPayload,
            ): Result<String> = error("not used")
            override suspend fun killSession(
                host: HostEntity,
                keyPath: String,
                passphrase: CharArray?,
                sessionName: String,
            ): Result<Unit> = error("not used")
        }
        val bridge = RecordingBridge()
        val actions = actions(bridge = bridge, responder = { ExecResult("", "", 0) }, gateway = failing)

        val result = actions.startSession(host = "dev", cwd = "/nope", agent = "claude")

        assertFalse(result.ok)
        assertTrue(result.message.contains("start directory does not exist"))
        assertTrue(
            "a failed create must not navigate anywhere",
            bridge.navigated.filterIsInstance<AppDestination.TmuxSession>().isEmpty(),
        )
    }
}
