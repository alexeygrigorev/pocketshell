package com.pocketshell.app.projects

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.settings.HostDetailViewMode
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

/**
 * Tight unit-level Compose test for FolderListScreen — issue #171 round 2.
 *
 * Verifies the load-bearing behaviour the round-1 review found broken:
 * tapping a session name inside an expanded project dispatches the
 * click callback (the parent navigator then routes to TmuxSession).
 * Without this contract, the post-host-tap flow loses the user
 * mid-stream — the regression the connected TmuxAttachPrefillDockerTest
 * detects but at full SSH+tmux cost.
 */
@RunWith(AndroidJUnit4::class)
class FolderListSessionClickTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private val hostId: Long = 11L

    @Before
    fun openDatabase(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue171-click-key", privateKeyPath = "/tmp/issue171"),
        )
        db.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "issue171-click-host",
                hostname = "h.example",
                port = 22,
                username = "u",
                keyId = keyId,
            ),
        )
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun tappingSessionTextRoutesToOnSessionClick() {
        var clickedSession: String? = null
        var clickedStart: String? = null

        val fakeGateway = StaticGateway(
            rows = listOf(
                FolderSessionRow(
                    sessionName = "claude-main",
                    lastActivity = 1_700_000_000L,
                    attached = true,
                    cwd = "/root",
                    agentKind = SessionAgentKind.Shell,
                ),
            ),
        )
        val viewModel = FolderListViewModel(
            gateway = fakeGateway,
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(InstrumentationRegistry.getInstrumentation().targetContext),
        )

        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "h",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue171",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { name, start ->
                        clickedSession = name
                        clickedStart = start
                    },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(folderRowTestTag("/root")).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(folderRowTestTag("/root")).performClick()
        compose.onNodeWithText("claude-main").performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            clickedSession != null
        }
        assertEquals("claude-main", clickedSession)
        assertEquals("/root", clickedStart)
    }

    @Test
    fun folderActionsExposeScopedCloneAndEmptyProjectCreation() {
        var cloneRoot: String? = null
        val fakeGateway = StaticGateway(
            rows = listOf(
                FolderSessionRow(
                    sessionName = "shell-main",
                    lastActivity = 1_700_000_000L,
                    attached = false,
                    cwd = "/root/projects",
                    agentKind = SessionAgentKind.Shell,
                ),
            ),
        )
        val viewModel = FolderListViewModel(
            gateway = fakeGateway,
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(InstrumentationRegistry.getInstrumentation().targetContext),
        )
        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "h",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue171",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _ -> },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { root -> cloneRoot = root },
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(folderDetailActionsTestTag("/root/projects")).fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(folderDetailActionsTestTag("/root/projects")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Clone git project").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(FOLDER_CONTEXT_NEW_SESSION_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FOLDER_CONTEXT_IMPORT_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FOLDER_CONTEXT_NEW_SESSION_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("New session").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("in projects").assertIsDisplayed()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CANCEL_TAG).performClick()

        compose.onNodeWithTag(folderDetailActionsTestTag("/root/projects")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Clone git project").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(FOLDER_CONTEXT_CLONE_TAG).performClick()
        assertEquals("/root/projects", cloneRoot)

        compose.onNodeWithTag(folderDetailActionsTestTag("/root/projects")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Empty project").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(FOLDER_CONTEXT_EMPTY_PROJECT_TAG).performClick()
        compose.onNodeWithTag(EMPTY_PROJECT_NAME_TAG).performTextInput("scratch")
        compose.onNodeWithTag(EMPTY_PROJECT_CREATE_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("scratch").fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals("/root/projects", fakeGateway.lastEmptyParent)
        assertEquals("scratch", fakeGateway.lastEmptyName)
        compose.onNodeWithTag(folderHeaderLabelTag("/root/projects/scratch"), useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun cableWorldRowsRenderRawSessionNamesInTreeAndFlatModes() {
        runBlocking {
            db.projectRootDao().insert(
                ProjectRootEntity(
                    hostId = hostId,
                    label = "git",
                    path = "/home/alexey/git",
                ),
            )
        }
        val cableWorldPath = "/home/alexey/git/cable-world"
        val fakeGateway = StaticGateway(
            rows = listOf(
                FolderSessionRow(
                    sessionName = "git-cable-world",
                    lastActivity = 1_700_000_000L,
                    attached = false,
                    cwd = cableWorldPath,
                    agentKind = SessionAgentKind.Shell,
                    windows = listOf(
                        FolderSessionWindowRow(
                            sessionName = "git-cable-world",
                            index = 0,
                            name = "node",
                            active = true,
                            cwd = cableWorldPath,
                            tty = "/dev/pts/2",
                            command = "node",
                        ),
                    ),
                ),
                FolderSessionRow(
                    sessionName = "git-cable-world-map",
                    lastActivity = 1_700_000_100L,
                    attached = false,
                    cwd = cableWorldPath,
                    agentKind = SessionAgentKind.Claude,
                    windows = listOf(
                        FolderSessionWindowRow(
                            sessionName = "git-cable-world-map",
                            index = 0,
                            name = "claude",
                            active = true,
                            cwd = cableWorldPath,
                            tty = "/dev/pts/3",
                            command = "claude",
                            agentKind = SessionAgentKind.Claude,
                        ),
                    ),
                ),
            ),
        )
        val viewModel = FolderListViewModel(
            gateway = fakeGateway,
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(InstrumentationRegistry.getInstrumentation().targetContext),
        )
        var mode by mutableStateOf(HostDetailViewMode.Tree)

        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "h",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue419",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _ -> },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    hostDetailViewMode = mode,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(folderHeaderLabelTag(cableWorldPath), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("cable-world").assertIsDisplayed()
        compose.onNodeWithTag(folderRowTestTag(cableWorldPath)).performClick()
        compose.onNodeWithText("git-cable-world").assertIsDisplayed()
        compose.onNodeWithText("git-cable-world-map").assertIsDisplayed()
        assertTrue(compose.onAllNodesWithText("Git Cable World Map").fetchSemanticsNodes().isEmpty())

        compose.runOnIdle { mode = HostDetailViewMode.Flat }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("git-cable-world-map").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("cable-world").assertIsDisplayed()
        compose.onNodeWithText("git-cable-world").assertIsDisplayed()
        compose.onNodeWithText("git-cable-world-map").assertIsDisplayed()
    }

    @Test
    fun defaultViewModeSwitchesBetweenTreeAndFlatFolderList() {
        runBlocking {
            db.projectRootDao().insert(
                ProjectRootEntity(
                    hostId = hostId,
                    label = "work",
                    path = "/root/work",
                ),
            )
        }
        val fakeGateway = StaticGateway(
            rows = listOf(
                FolderSessionRow(
                    sessionName = "codex-app",
                    lastActivity = 1_700_000_000L,
                    attached = false,
                    cwd = "/root/work/app/service",
                    agentKind = SessionAgentKind.Codex,
                ),
            ),
            projectFoldersByRoot = mapOf(
                "/root/work" to listOf(
                    "/root/work/app",
                    "/root/work/empty",
                ),
            ),
        )
        val viewModel = FolderListViewModel(
            gateway = fakeGateway,
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(InstrumentationRegistry.getInstrumentation().targetContext),
        )
        var mode by mutableStateOf(HostDetailViewMode.Tree)

        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "h",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue171",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _ -> },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    hostDetailViewMode = mode,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(
                folderHeaderLabelTag("/root/work/app"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(folderTreeRootLabelTag("/root/work")).assertIsDisplayed()
        compose.onNodeWithTag(folderHeaderLabelTag("/root/work/app"), useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            compose.onAllNodesWithTag(
                folderHeaderLabelTag("/root/work/empty"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(compose.onAllNodesWithText("codex-app").fetchSemanticsNodes().isEmpty())

        compose.runOnIdle { mode = HostDetailViewMode.Flat }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("service").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(compose.onAllNodesWithTag(folderTreeRootTestTag("/root/work")).fetchSemanticsNodes().isEmpty())
        assertTrue(
            compose.onAllNodesWithTag(
                folderHeaderLabelTag("/root/work/app"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag(folderHeaderLabelTag("/root/work/app/service"), useUnmergedTree = true).assertIsDisplayed()
    }
}

private class StaticGateway(
    private val rows: List<FolderSessionRow>,
    private val projectFoldersByRoot: Map<String, List<String>> = emptyMap(),
) : FolderListGateway {
    var lastEmptyParent: String? = null
    var lastEmptyName: String? = null

    override suspend fun listSessionsWithFolder(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        watchedRoots: List<ProjectRootEntity>,
    ): FolderListResult = FolderListResult.Sessions(
        rows = rows,
        projectFoldersByRoot = projectFoldersByRoot,
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
    ): Result<String> {
        lastEmptyParent = parentPath
        lastEmptyName = folderName
        return Result.success("$parentPath/$folderName")
    }

    override suspend fun importFile(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        folderPath: String,
        payload: FolderImportPayload,
    ): Result<String> = Result.success("$folderPath/${payload.remoteName}")
}

@Suppress("unused")
private val unused: AtomicInteger = AtomicInteger(0)
