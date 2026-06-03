package com.pocketshell.app.assistant

import com.pocketshell.app.nav.AppDestination
import com.pocketshell.app.projects.FolderImportPayload
import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.app.projects.FolderListResult
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
        val sent = mutableListOf<String>()
        val navigated = mutableListOf<AppDestination>()
        override fun activeHostName(): String? = host
        override fun activeCwd(): String? = "/home/dev/proj"
        override fun activeSessionName(): String? = "main"
        override fun currentScreenLabel(): String = "tmux"
        override suspend fun sendCommand(command: String): Result<Unit> {
            sent += command
            return sendResult
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
            ): Result<String> = Result.success(sessionName)

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
            ): Result<String> = Result.success(sessionName)
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
}
