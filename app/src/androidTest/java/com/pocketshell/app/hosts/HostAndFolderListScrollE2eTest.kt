package com.pocketshell.app.hosts

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.projects.FOLDER_LIST_BOTTOM_SPACER_TAG
import com.pocketshell.app.projects.FOLDER_LIST_CONTENT_TAG
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.projects.FolderImportPayload
import com.pocketshell.app.projects.FolderListGateway
import com.pocketshell.app.projects.FolderListResult
import com.pocketshell.app.projects.FolderListScreen
import com.pocketshell.app.projects.FolderListViewModel
import com.pocketshell.app.projects.FolderSessionRow
import com.pocketshell.app.projects.folderDetailRowTestTag
import com.pocketshell.app.projects.folderListFlatRowTestTag
import com.pocketshell.app.proof.clearLastSessionPrefs
import com.pocketshell.app.settings.HostDetailViewMode
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellTheme
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #274 regression coverage for the current host/session surfaces.
 *
 * The v0.3.5 all-host sessions dashboard no longer exists on main, so
 * this exercises the replacement list surfaces that can overflow a Pixel
 * viewport: the host landing list and the per-host folder/session list.
 * Each assertion scrolls to the final row and captures full-device
 * screenshots under `additional_test_output/issue274-scroll/`.
 */
@RunWith(AndroidJUnit4::class)
class HostAndFolderListScrollE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var persistentDb: AppDatabase? = null

    @Before
    fun clearFastResume() {
        clearLastSessionPrefs()
    }

    @After
    fun cleanup() {
        launchedActivity?.close()
        launchedActivity = null
        persistentDb?.let { db ->
            runBlocking { db.clearAllTables() }
            db.close()
        }
        persistentDb = null
        clearLastSessionPrefs()
    }

    @Test
    fun hostListScrollsToLastHostWithoutVersionFooterAboveFab(): Unit = runBlocking {
        val lastHostId = seedOverflowHosts(count = 24)
        val lastHostTag = HOST_ROW_TAG_PREFIX + lastHostId

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(HOST_LIST_CONTENT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        captureFullDevice("01-host-list-before-scroll-viewport.png")

        compose.onNodeWithTag(HOST_LIST_CONTENT_TAG, useUnmergedTree = true)
            .performScrollToNode(hasTestTag(lastHostTag))
        compose.waitForIdle()
        compose.onNodeWithTag(lastHostTag, useUnmergedTree = true).assertIsDisplayed()
        assertTrue(
            "Host list version footer should stay out of the primary host-list surface",
            compose.onAllNodesWithTag(OLD_HOST_LIST_VERSION_FOOTER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isEmpty(),
        )
        captureFullDevice("02-host-list-bottom-viewport.png")
    }

    private suspend fun seedOverflowHosts(count: Int): Long {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        persistentDb = db
        db.clearAllTables()
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue274-key", privateKeyPath = "/tmp/issue274-key"),
        )
        var lastHostId = 0L
        repeat(count) { index ->
            val number = (index + 1).toString().padStart(2, '0')
            lastHostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue274 Host $number",
                    hostname = "host-$number.example",
                    port = 22,
                    username = "u",
                    keyId = keyId,
                    tmuxInstalled = true,
                    pocketshellInstalled = false,
                    lastBootstrapAt = System.currentTimeMillis(),
                    pocketshellLastDetectedAt = System.currentTimeMillis(),
                ),
            )
        }
        return lastHostId
    }

    private fun captureFullDevice(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Could not capture issue274 screenshot $name"
        }
        val file = issue274ArtifactFile(name)
        FileOutputStream(file).use { output ->
            assertTrue(
                "Could not write issue274 screenshot: ${file.absolutePath}",
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
        bitmap.recycle()
        println("ISSUE274_SCROLL_SCREENSHOT ${file.absolutePath}")
        return file
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val OLD_HOST_LIST_VERSION_FOOTER_TAG: String = "host-list:version-footer"
    }
}

@RunWith(AndroidJUnit4::class)
class FolderListScrollE2eTest {

    @get:Rule
    val compose = createComposeRule()

    private var db: AppDatabase? = null

    @After
    fun cleanup() {
        db?.close()
        db = null
    }

    @Test
    fun folderSessionListScrollsToLastSessionInTreeAndFlatModes(): Unit = runBlocking {
        val database = Room.inMemoryDatabaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        db = database
        val hostId = seedFolderHost(database)
        val folders = (1..18).map { index -> "/home/u/work/project-${index.toString().padStart(2, '0')}" }
        val rows = folders.mapIndexed { index, folder ->
            FolderSessionRow(
                sessionName = "session-${(index + 1).toString().padStart(2, '0')}",
                lastActivity = 1_700_000_000L + index,
                attached = index % 3 == 0,
                cwd = folder,
                agentKind = if (index % 2 == 0) SessionAgentKind.Codex else SessionAgentKind.Shell,
            )
        }
        val fakeGateway = OverflowFolderListGateway(
            rows = rows,
            projectFoldersByRoot = mapOf("/home/u/work" to folders),
        )
        // The FolderListViewModel `init` attaches a ProcessLifecycleOwner
        // observer, which must run on the main thread.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var viewModel: FolderListViewModel
        instrumentation.runOnMainSync {
            viewModel = FolderListViewModel(
                gateway = fakeGateway,
                hostDao = database.hostDao(),
                projectRootDao = database.projectRootDao(),
                forwardingController = ForwardingController(instrumentation.targetContext),
            )
        }
        val lastRowTag = folderDetailRowTestTag(folders.first(), rows.first().sessionName)
        var hostDetailViewMode by mutableStateOf(HostDetailViewMode.Tree)

        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "issue274-host",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue274",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _, _, _ -> },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    hostDetailViewMode = hostDetailViewMode,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            fakeGateway.callCount.get() >= 1 &&
                compose.onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        captureFullDevice("03-folder-tree-before-scroll-viewport.png")

        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG, useUnmergedTree = true)
            .performScrollToNode(hasTestTag(FOLDER_LIST_BOTTOM_SPACER_TAG))
        compose.waitForIdle()
        compose.onNodeWithTag(lastRowTag, useUnmergedTree = true).assertIsDisplayed()
        captureFullDevice("04-folder-tree-bottom-viewport.png")

        // Flat view (#485): an ungrouped list of every session — the session
        // detail rows that the tree path renders are gone, replaced by flat
        // rows keyed by session name. `session-01` is the oldest session so it
        // sorts to the bottom of the recency-ordered flat list.
        val lastFlatRowTag = folderListFlatRowTestTag(rows.first().sessionName)
        compose.runOnIdle { hostDetailViewMode = HostDetailViewMode.Flat }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(lastFlatRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG, useUnmergedTree = true)
            .performScrollToNode(hasTestTag(FOLDER_LIST_BOTTOM_SPACER_TAG))
        compose.waitForIdle()
        compose.onNodeWithTag(lastFlatRowTag, useUnmergedTree = true).assertIsDisplayed()
        captureFullDevice("05-folder-flat-bottom-viewport.png")
    }

    private suspend fun seedFolderHost(db: AppDatabase): Long {
        db.clearAllTables()
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue274-folder-key", privateKeyPath = "/tmp/issue274-folder-key"),
        )
        val hostId = db.hostDao().insert(
            HostEntity(
                name = "issue274-host",
                hostname = "h.example",
                port = 22,
                username = "u",
                keyId = keyId,
            ),
        )
        db.projectRootDao().insert(
            ProjectRootEntity(
                hostId = hostId,
                label = "work",
                path = "/home/u/work",
            ),
        )
        return hostId
    }

    private fun captureFullDevice(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(250)
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot()) {
            "Could not capture issue274 screenshot $name"
        }
        val file = issue274ArtifactFile(name)
        FileOutputStream(file).use { output ->
            assertTrue(
                "Could not write issue274 screenshot: ${file.absolutePath}",
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output),
            )
        }
        bitmap.recycle()
        println("ISSUE274_SCROLL_SCREENSHOT ${file.absolutePath}")
        return file
    }

}

private fun issue274ArtifactFile(name: String): File {
    val instrumentation = InstrumentationRegistry.getInstrumentation()
    val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
    val directory = File(mediaRoot, "additional_test_output/issue274-scroll")
    check(directory.exists() || directory.mkdirs()) {
        "Could not create issue274 artifact directory: ${directory.absolutePath}"
    }
    return File(directory, name)
}

private class OverflowFolderListGateway(
    private val rows: List<FolderSessionRow>,
    private val projectFoldersByRoot: Map<String, List<String>>,
) : FolderListGateway {
    val callCount: AtomicInteger = AtomicInteger(0)

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
    ): Result<Unit> = Result.success(Unit)
}
