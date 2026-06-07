package com.pocketshell.app.projects

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.core.graphics.createBitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.settings.HostDetailViewMode
import com.pocketshell.app.tmux.SessionLifecycleSignals
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compose-level connected test for the host-detail "Stop session" action —
 * issue #518.
 *
 * Drives the actual [FolderListScreen] (production view model + a mutable
 * fake gateway) through the maintainer's journey WITHOUT entering the
 * session:
 *
 *  1. The tree lists two sessions under an expanded project.
 *  2. The user taps a session row's kebab → a menu appears; choosing Stop
 *     opens the confirmation dialog.
 *  3. Tapping Cancel does nothing — both rows remain.
 *  4. Tapping Stop again then confirming kills the session: the gateway is
 *     asked to kill exactly that session, the lifecycle signal is broadcast,
 *     and the row disappears while the screen stays on the host-detail tree.
 *
 * Before/after viewport bitmaps are captured to
 * `additional_test_output/issue518-stop-session/` so a reviewer can see the
 * row gone after the confirmed kill.
 */
@RunWith(AndroidJUnit4::class)
class FolderListStopSessionTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private val hostId: Long = 9L
    private val projectPath = "/home/u/git/pocketshell"
    private val keep = "keep-shell"
    private val doomed = "doomed-agent"

    @Before
    fun openDatabase(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue518-key", privateKeyPath = "/tmp/issue518"),
        )
        db.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "issue518-host",
                hostname = "h.example",
                port = 22,
                username = "u",
                keyId = keyId,
            ),
        )
        db.projectRootDao().insert(
            ProjectRootEntity(hostId = hostId, label = "[00] git", path = "~/git"),
        )
    }

    @After
    fun closeDatabase() {
        runCatching { db.close() }
    }

    @Test
    fun stopSessionFromTreeRowConfirmsThenRemovesRow() {
        val gateway = MutableKillGateway(
            initialRows = listOf(
                FolderSessionRow(
                    sessionName = doomed,
                    lastActivity = 1_700_004_000L,
                    attached = true,
                    cwd = projectPath,
                    agentKind = SessionAgentKind.Claude,
                ),
                FolderSessionRow(
                    sessionName = keep,
                    lastActivity = 1_700_003_500L,
                    attached = false,
                    cwd = projectPath,
                    agentKind = SessionAgentKind.Shell,
                ),
            ),
            projectFoldersByRoot = mapOf("~/git" to listOf(projectPath)),
            resolvedWatchedRootPaths = mapOf("~/git" to "/home/u/git"),
        )
        val signals = SessionLifecycleSignals()
        val viewModel = constructFolderListViewModel(gateway, signals)

        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "issue518-host",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue518",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _ -> },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }

        // Wait for the bind() + first probe to render the tree.
        compose.waitUntil(timeoutMillis = 10_000) {
            gateway.callCount.get() >= 1 &&
                compose.onAllNodesWithTag(folderTreeRootTestTag("~/git"))
                    .fetchSemanticsNodes().isNotEmpty()
        }

        // Ensure the project is expanded so the session rows show.
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(folderHeaderClickTestTag(projectPath)))
        if (compose.onAllNodesWithTag(folderDetailRowTestTag(projectPath, doomed))
                .fetchSemanticsNodes().isEmpty()
        ) {
            compose.onNodeWithTag(folderHeaderClickTestTag(projectPath)).performClick()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(folderDetailRowTestTag(projectPath, doomed))
                .fetchSemanticsNodes().isNotEmpty()
        }
        captureViewport("before-stop-viewport.png")

        // Open the row kebab first. Destructive Stop must be a menu item, not
        // a direct-to-confirm affordance.
        compose.onNodeWithTag(folderSessionActionsTestTag(projectPath, doomed)).performClick()
        compose.onNodeWithTag(folderSessionStopMenuItemTestTag(projectPath, doomed)).assertExists()
        compose.onNodeWithText("Open session").assertExists()
        compose.onNodeWithText("Rename session").assertExists()
        compose.onNodeWithText("Stop session").assertExists()
        assertTrue(
            "confirmation must not open until the Stop menu item is chosen",
            compose.onAllNodesWithTag(STOP_SESSION_DIALOG_TAG).fetchSemanticsNodes().isEmpty(),
        )
        compose.onNodeWithTag(folderSessionStopMenuItemTestTag(projectPath, doomed)).performClick()
        compose.onNodeWithTag(STOP_SESSION_DIALOG_TAG).assertExists()

        // Cancel does nothing — the dialog closes and both rows stay.
        compose.onNodeWithTag(STOP_SESSION_CANCEL_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(STOP_SESSION_DIALOG_TAG).fetchSemanticsNodes().isEmpty()
        }
        assertTrue("doomed row must remain after Cancel", gateway.killedSessions.isEmpty())
        compose.onNodeWithTag(folderDetailRowTestTag(projectPath, doomed)).assertExists()

        // Now choose Stop from the menu again, then confirm the kill.
        compose.onNodeWithTag(folderSessionActionsTestTag(projectPath, doomed)).performClick()
        compose.onNodeWithTag(folderSessionStopMenuItemTestTag(projectPath, doomed)).performClick()
        compose.onNodeWithTag(STOP_SESSION_DIALOG_TAG).assertExists()
        compose.onNodeWithText("Stop").performClick()

        // The killed row disappears; the kept row stays; screen stays mounted.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(folderDetailRowTestTag(projectPath, doomed))
                .fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG).assertExists()
        compose.onNodeWithTag(folderDetailRowTestTag(projectPath, keep)).assertExists()
        captureViewport("after-stop-viewport.png")

        assertEquals(
            "the gateway must be asked to kill exactly the doomed session",
            listOf(doomed),
            gateway.killedSessions.toList(),
        )
    }

    @Test
    fun sessionRowMenuContainsRenameAndRenamesSession() {
        val oldName = doomed
        val newName = "renamed-agent"
        val gateway = MutableKillGateway(
            initialRows = listOf(
                FolderSessionRow(
                    sessionName = oldName,
                    lastActivity = 1_700_004_000L,
                    attached = true,
                    cwd = projectPath,
                    agentKind = SessionAgentKind.Claude,
                ),
                FolderSessionRow(
                    sessionName = keep,
                    lastActivity = 1_700_003_500L,
                    attached = false,
                    cwd = projectPath,
                    agentKind = SessionAgentKind.Shell,
                ),
            ),
            projectFoldersByRoot = mapOf("~/git" to listOf(projectPath)),
            resolvedWatchedRootPaths = mapOf("~/git" to "/home/u/git"),
        )
        val signals = SessionLifecycleSignals()
        val viewModel = constructFolderListViewModel(gateway, signals)

        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "issue600-host",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue518",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _ -> },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            gateway.callCount.get() >= 1 &&
                compose.onAllNodesWithTag(folderTreeRootTestTag("~/git"))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(folderHeaderClickTestTag(projectPath)))
        if (compose.onAllNodesWithTag(folderDetailRowTestTag(projectPath, oldName))
                .fetchSemanticsNodes().isEmpty()
        ) {
            compose.onNodeWithTag(folderHeaderClickTestTag(projectPath)).performClick()
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(folderDetailRowTestTag(projectPath, oldName))
                .fetchSemanticsNodes().isNotEmpty()
        }

        compose.onNodeWithTag(folderSessionActionsTestTag(projectPath, oldName)).performClick()
        compose.onNodeWithTag(folderSessionOpenMenuItemTestTag(projectPath, oldName)).assertExists()
        compose.onNodeWithTag(folderSessionRenameMenuItemTestTag(projectPath, oldName)).assertExists()
        compose.onNodeWithTag(folderSessionStopMenuItemTestTag(projectPath, oldName)).assertExists()
        assertTrue(
            "Stop confirmation must remain gated behind the Stop menu item",
            compose.onAllNodesWithTag(STOP_SESSION_DIALOG_TAG).fetchSemanticsNodes().isEmpty(),
        )

        compose.onNodeWithTag(folderSessionRenameMenuItemTestTag(projectPath, oldName)).performClick()
        compose.onNodeWithTag(RENAME_SESSION_DIALOG_TAG).assertExists()
        compose.onNodeWithTag(RENAME_SESSION_FIELD_TAG).performTextReplacement(newName)
        compose.onNodeWithTag(RENAME_SESSION_CONFIRM_TAG).performClick()

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(folderDetailRowTestTag(projectPath, newName))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "old session row should disappear after rename",
            compose.onAllNodesWithTag(folderDetailRowTestTag(projectPath, oldName))
                .fetchSemanticsNodes().isEmpty(),
        )
        assertEquals(listOf(oldName to newName), gateway.renamedSessions.toList())
    }

    @Test
    fun longSessionNamesKeepTreeKebabVisibleAndOpenMenu() {
        val longName = "agent-" + "very-long-session-name-".repeat(8) + "tail"

        assertLongNameKebabVisibleAndMenuOpens(
            longName = longName,
            mode = HostDetailViewMode.Tree,
            rowTag = folderDetailRowTestTag(projectPath, longName),
            triggerTag = folderSessionActionsTestTag(projectPath, longName),
            openItemTag = folderSessionOpenMenuItemTestTag(projectPath, longName),
            renameItemTag = folderSessionRenameMenuItemTestTag(projectPath, longName),
            stopItemTag = folderSessionStopMenuItemTestTag(projectPath, longName),
            expandTree = true,
        )
    }

    @Test
    fun longSessionNamesKeepFlatKebabVisibleAndOpenMenu() {
        val longName = "agent-" + "very-long-session-name-".repeat(8) + "tail"

        assertLongNameKebabVisibleAndMenuOpens(
            longName = longName,
            mode = HostDetailViewMode.Flat,
            rowTag = folderListFlatRowTestTag(longName),
            triggerTag = folderListFlatRowActionsTestTag(longName),
            openItemTag = folderListFlatRowOpenMenuItemTestTag(longName),
            renameItemTag = folderListFlatRowRenameMenuItemTestTag(longName),
            stopItemTag = folderListFlatRowStopMenuItemTestTag(longName),
            expandTree = false,
        )
    }

    private fun constructFolderListViewModel(
        gateway: FolderListGateway,
        signals: SessionLifecycleSignals,
    ): FolderListViewModel {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var vm: FolderListViewModel
        instrumentation.runOnMainSync {
            vm = FolderListViewModel(
                gateway = gateway,
                hostDao = db.hostDao(),
                projectRootDao = db.projectRootDao(),
                forwardingController = ForwardingController(instrumentation.targetContext),
                sessionLifecycleSignals = signals,
                attachLifecycle = true,
            )
        }
        return vm
    }

    private fun assertLongNameKebabVisibleAndMenuOpens(
        longName: String,
        mode: HostDetailViewMode,
        rowTag: String,
        triggerTag: String,
        openItemTag: String,
        renameItemTag: String,
        stopItemTag: String,
        expandTree: Boolean,
    ) {
        val gateway = MutableKillGateway(
            initialRows = listOf(
                FolderSessionRow(
                    sessionName = longName,
                    lastActivity = 1_700_004_000L,
                    attached = true,
                    cwd = projectPath,
                    agentKind = SessionAgentKind.Claude,
                ),
            ),
            projectFoldersByRoot = mapOf("~/git" to listOf(projectPath)),
            resolvedWatchedRootPaths = mapOf("~/git" to "/home/u/git"),
        )
        val viewModel = constructFolderListViewModel(gateway, SessionLifecycleSignals())
        var openedSession: String? = null

        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "issue597-host",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue518",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { name, _ -> openedSession = name },
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
            gateway.callCount.get() >= 1 &&
                if (expandTree) {
                    compose.onAllNodesWithTag(folderTreeRootTestTag("~/git"))
                        .fetchSemanticsNodes().isNotEmpty()
                } else {
                    compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()
                }
        }

        if (expandTree) {
            compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
                .performScrollToNode(hasTestTag(folderHeaderClickTestTag(projectPath)))
            if (compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isEmpty()) {
                compose.onNodeWithTag(folderHeaderClickTestTag(projectPath)).performClick()
            }
        }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(rowTag).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG).performScrollToNode(hasTestTag(rowTag))

        assertActionTargetVisibleInsideRow(rowTag = rowTag, triggerTag = triggerTag)

        compose.onNodeWithTag(triggerTag).performClick()
        compose.onNodeWithTag(openItemTag).assertExists()
        compose.onNodeWithTag(renameItemTag).assertExists()
        compose.onNodeWithTag(stopItemTag).assertExists()
        assertTrue(
            "kebab tap must open the action menu, not the Stop confirmation",
            compose.onAllNodesWithTag(STOP_SESSION_DIALOG_TAG).fetchSemanticsNodes().isEmpty(),
        )

        compose.onNodeWithTag(openItemTag).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { openedSession == longName }
    }

    private fun assertActionTargetVisibleInsideRow(rowTag: String, triggerTag: String) {
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val rowBounds = compose.onNodeWithTag(rowTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .fetchSemanticsNode()
            .boundsInRoot
        val triggerBounds = compose.onNodeWithTag(triggerTag, useUnmergedTree = true)
            .assertIsDisplayed()
            .assertHasClickAction()
            .fetchSemanticsNode()
            .boundsInRoot
        val minPx = 48f * density
        assertTrue(
            "session action target must remain a visible, tappable control: " +
                "${triggerBounds.width}x${triggerBounds.height}px",
            triggerBounds.width >= minPx && triggerBounds.height >= minPx,
        )
        assertTrue(
            "long session names must not push the action target outside the row: " +
                "row=$rowBounds trigger=$triggerBounds",
            triggerBounds.left >= rowBounds.left && triggerBounds.right <= rowBounds.right,
        )
    }

    private fun outDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        return File(mediaRoot, "additional_test_output/issue518-stop-session").apply {
            if (!exists()) mkdirs()
        }
    }

    private fun writePng(name: String, bitmap: Bitmap) {
        FileOutputStream(File(outDir(), name)).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    private fun captureViewport(name: String) {
        compose.waitForIdle()
        // Authoritative terminal-style viewport: the composed host-detail
        // screen node rendered to a bitmap. Falls back to a full-device
        // capture (the row/dialog is still visible there) when the node
        // capture flakes off-window so the before/after evidence is never
        // empty.
        val captured = try {
            val image = compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG).captureToImage()
            val bitmap: Bitmap = createBitmap(image.width, image.height)
            val pixels = IntArray(image.width * image.height)
            image.readPixels(pixels)
            bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)
            writePng(name, bitmap)
            true
        } catch (t: Throwable) {
            false
        }
        if (!captured) {
            runCatching {
                val instrumentation = InstrumentationRegistry.getInstrumentation()
                instrumentation.waitForIdleSync()
                instrumentation.uiAutomation.takeScreenshot()?.let { writePng(name, it) }
            }
        }
    }

    /**
     * Fake gateway whose session set is mutable: a successful [killSession]
     * drops the row (so subsequent authoritative probes agree the kill
     * landed), exactly like the real SSH gateway. Records killed names so the
     * test can assert which session was targeted.
     */
    private class MutableKillGateway(
        initialRows: List<FolderSessionRow>,
        private val projectFoldersByRoot: Map<String, List<String>> = emptyMap(),
        private val resolvedWatchedRootPaths: Map<String, String> = emptyMap(),
    ) : FolderListGateway {
        @Volatile
        private var rows: List<FolderSessionRow> = initialRows
        val callCount: AtomicInteger = AtomicInteger(0)
        val killedSessions: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
        val renamedSessions: CopyOnWriteArrayList<Pair<String, String>> = CopyOnWriteArrayList()

        override suspend fun listSessionsWithFolder(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            watchedRoots: List<ProjectRootEntity>,
        ): FolderListResult {
            callCount.incrementAndGet()
            return FolderListResult.Sessions(
                rows = rows,
                projectFoldersByRoot = projectFoldersByRoot,
                resolvedWatchedRootPaths = resolvedWatchedRootPaths,
            )
        }

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

        override suspend fun killSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            sessionName: String,
        ): Result<Unit> {
            killedSessions.add(sessionName)
            rows = rows.filterNot { it.sessionName == sessionName }
            return Result.success(Unit)
        }

        override suspend fun renameSession(
            host: HostEntity,
            keyPath: String,
            passphrase: CharArray?,
            oldName: String,
            newName: String,
        ): Result<Unit> {
            renamedSessions.add(oldName to newName)
            rows = rows.map { row ->
                if (row.sessionName == oldName) row.copy(sessionName = newName) else row
            }
            return Result.success(Unit)
        }
    }
}
