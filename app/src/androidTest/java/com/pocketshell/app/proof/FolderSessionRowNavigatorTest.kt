package com.pocketshell.app.proof

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.projects.FolderImportPayload
import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.app.projects.FolderListResult
import com.pocketshell.app.projects.FolderListScreen
import com.pocketshell.app.projects.FolderListViewModel
import com.pocketshell.app.projects.FolderSessionRow
import com.pocketshell.app.projects.SessionCreateOutcome
import com.pocketshell.app.projects.SessionNamePolicy
import com.pocketshell.app.projects.folderDetailRowTestTag
import com.pocketshell.app.projects.folderHeaderClickTestTag
import com.pocketshell.app.projects.folderRowTestTag
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

/**
 * Issue #2380 — regression guard for the network-fault proofs' shared host-detail
 * navigation.
 *
 * Reproduces the fixture topology that made the whole nightly phase-2 network-fault
 * gate VACUOUS: sessions grouped under REAL project folders inside the
 * `::other-folders::` root, with a `::untracked::` row also rendered. The superseded
 * selector took the first entry of a hard-coded candidate list (`::untracked::`,
 * `/home/testuser`, `~`) whose folder row MERELY EXISTED, so it latched onto a folder
 * that does not hold the target session, tapped it ~170 times and died in shared
 * setup — before a single Toxiproxy fault was ever injected.
 *
 * These cases drive the REAL [FolderListScreen] with the REAL
 * [FolderSessionRowNavigator] the proofs use, over the exact topology class:
 * multiple project folders, a `/home/testuser` folder (the old candidate #2), and an
 * `::untracked::` row that is NOT the target's home. No Docker, no Toxiproxy — this is
 * the per-push gate for the selector itself; the end-to-end proof over the real
 * fixture is [AttachNavigationMultiFolderE2eTest].
 */
@RunWith(AndroidJUnit4::class)
class FolderSessionRowNavigatorTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private val hostId: Long = 2380L

    @Before
    fun openDatabase() { runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue2380-key", privateKeyPath = "/tmp/issue2380"),
        )
        db.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "issue2380-host",
                hostname = "h.example",
                port = 22,
                username = "testuser",
                keyId = keyId,
            ),
        )
    } }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun resolvesTheProjectFolderHoldingTheSessionWhileAnUntrackedRowExists() {
        val opened = renderMultiFolderHostDetail()

        val resolved = navigator().revealSessionRow(
            sessionName = TARGET_SESSION,
            expectedFolderPath = TARGET_FOLDER,
            timeoutMillis = RESOLVE_TIMEOUT_MS,
        )

        assertEquals(TARGET_FOLDER, resolved)
        // The resolved row is the real, tappable session row the proof opens next.
        compose.onNodeWithTag(folderDetailRowTestTag(resolved, TARGET_SESSION), useUnmergedTree = true)
            .performClick()
        compose.waitUntil(CLICK_TIMEOUT_MS) { opened.session != null }
        assertEquals(TARGET_SESSION, opened.session)
        assertEquals(TARGET_FOLDER, opened.startDirectory)
    }

    @Test
    fun resolvesTheContainingFolderWithNoExpectedFolderHint() {
        // The degraded case: the harness could not observe the seeded session's
        // remote cwd, so ordering has no hint. The navigator must still land on the
        // folder that CONTAINS the session instead of the first row that exists.
        renderMultiFolderHostDetail()

        val resolved = navigator().revealSessionRow(
            sessionName = TARGET_SESSION,
            expectedFolderPath = null,
            timeoutMillis = RESOLVE_TIMEOUT_MS,
        )

        assertEquals(TARGET_FOLDER, resolved)
    }

    @Test
    fun resolvesASessionThatGenuinelyLivesUnderTheUntrackedRow() {
        // The opposite over-correction guard: a session with no resolvable cwd really
        // does live under `::untracked::`, and must still be found even though real
        // project folders render first.
        renderMultiFolderHostDetail()

        val resolved = navigator().revealSessionRow(
            sessionName = UNTRACKED_SESSION,
            expectedFolderPath = null,
            timeoutMillis = RESOLVE_TIMEOUT_MS,
        )

        assertEquals(FolderListViewModel.UNTRACKED_PATH, resolved)
    }

    @Test
    fun reExpandsACollapsedProjectFolderToReachTheSession() {
        renderMultiFolderHostDetail()

        // Collapse the target's folder, so the session child row is gone and the
        // navigator has to expand the right folder — not merely read the tree.
        compose.onNodeWithTag(folderHeaderClickTestTag(TARGET_FOLDER), useUnmergedTree = true)
            .performClick()
        compose.waitUntil(CLICK_TIMEOUT_MS) {
            compose.onAllNodesWithTag(folderDetailRowTestTag(TARGET_FOLDER, TARGET_SESSION), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }

        val resolved = navigator().revealSessionRow(
            sessionName = TARGET_SESSION,
            expectedFolderPath = TARGET_FOLDER,
            timeoutMillis = RESOLVE_TIMEOUT_MS,
        )

        assertEquals(TARGET_FOLDER, resolved)
        compose.onNodeWithTag(folderDetailRowTestTag(resolved, TARGET_SESSION), useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun reportsTheFoldersItSearchedWhenTheSessionIsAbsent() {
        renderMultiFolderHostDetail()

        val error = runCatching {
            navigator().revealSessionRow(
                sessionName = "issue2380-not-on-this-host",
                expectedFolderPath = TARGET_FOLDER,
                timeoutMillis = ABSENT_TIMEOUT_MS,
            )
        }.exceptionOrNull()

        assertTrue("expected an AssertionError, got $error", error is AssertionError)
        val message = error?.message.orEmpty()
        assertTrue("diagnostics must name the expected folder: $message", message.contains(TARGET_FOLDER))
        assertTrue("diagnostics must list the folders seen: $message", message.contains("folders seen"))
        assertTrue(
            "diagnostics must list the folders expanded and rejected: $message",
            message.contains("folders expanded and rejected"),
        )
    }

    private fun navigator(): FolderSessionRowNavigator = FolderSessionRowNavigator(compose)

    /**
     * The reported topology: 2 real project folders, the historical
     * `/home/testuser` candidate folder, and an `::untracked::` row holding a
     * session that is NOT the target.
     */
    private fun renderMultiFolderHostDetail(): OpenedSession {
        val opened = OpenedSession()
        val gateway = StaticFolderGateway(
            rows = listOf(
                FolderSessionRow(
                    sessionName = "issue2380-alpha",
                    lastActivity = 1_700_000_100L,
                    attached = false,
                    cwd = "/home/testuser/projects/alpha",
                    agentKind = SessionAgentKind.Shell,
                ),
                FolderSessionRow(
                    sessionName = TARGET_SESSION,
                    lastActivity = 1_700_000_200L,
                    attached = false,
                    cwd = TARGET_FOLDER,
                    agentKind = SessionAgentKind.Shell,
                ),
                FolderSessionRow(
                    sessionName = "issue2380-home",
                    lastActivity = 1_700_000_300L,
                    attached = false,
                    cwd = "/home/testuser",
                    agentKind = SessionAgentKind.Shell,
                ),
                FolderSessionRow(
                    sessionName = UNTRACKED_SESSION,
                    lastActivity = 1_700_000_400L,
                    attached = false,
                    cwd = null,
                    agentKind = SessionAgentKind.Shell,
                ),
            ),
        )
        val viewModel = constructViewModelOnMainThread(gateway)
        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "issue2380-host",
                    hostname = "h.example",
                    port = 22,
                    username = "testuser",
                    keyPath = "/tmp/issue2380",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { name, start, _, _ ->
                        opened.session = name
                        opened.startDirectory = start
                    },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }
        compose.waitUntil(RENDER_TIMEOUT_MS) {
            compose.onAllNodesWithTag(folderRowTestTag(FolderListViewModel.UNTRACKED_PATH), useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                compose.onAllNodesWithTag(folderRowTestTag(TARGET_FOLDER), useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
        }
        return opened
    }

    private fun constructViewModelOnMainThread(gateway: FolderListGateway): FolderListViewModel {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var vm: FolderListViewModel
        instrumentation.runOnMainSync {
            vm = FolderListViewModel(
                gateway = gateway,
                hostDao = db.hostDao(),
                projectRootDao = db.projectRootDao(),
                forwardingController = ForwardingController(instrumentation.targetContext),
            )
        }
        return vm
    }

    private class OpenedSession {
        @Volatile
        var session: String? = null

        @Volatile
        var startDirectory: String? = null
    }

    private companion object {
        const val TARGET_SESSION: String = "issue2380-target"
        const val TARGET_FOLDER: String = "/home/testuser/projects/beta"
        const val UNTRACKED_SESSION: String = "issue2380-stray"
        const val RENDER_TIMEOUT_MS: Long = 20_000L
        const val RESOLVE_TIMEOUT_MS: Long = 30_000L
        const val CLICK_TIMEOUT_MS: Long = 10_000L

        /**
         * Deliberately short: the absent-session case must FAIL FAST with a
         * diagnosable message, not burn a full picker budget silently.
         */
        const val ABSENT_TIMEOUT_MS: Long = 8_000L
    }
}

private class StaticFolderGateway(
    private val rows: List<FolderSessionRow>,
) : FolderListGateway {
    override suspend fun listSessionsWithFolder(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        watchedRoots: List<ProjectRootEntity>,
    ): FolderListResult = FolderListResult.Sessions(rows = rows)

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
