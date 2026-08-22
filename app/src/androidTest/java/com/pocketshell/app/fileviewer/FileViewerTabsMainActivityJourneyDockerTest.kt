package com.pocketshell.app.fileviewer

import android.app.Application
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.printToString
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.bootstrap.HOST_BOOTSTRAP_SKIP_TAG
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.projects.FOLDER_LIST_CONTENT_TAG
import com.pocketshell.app.projects.FOLDER_LIST_ERROR_TAG
import com.pocketshell.app.projects.FOLDER_LIST_FLAT_ACTIVE_SECTION_TAG
import com.pocketshell.app.projects.FOLDER_LIST_FLAT_EMPTY_TAG
import com.pocketshell.app.projects.FOLDER_LIST_FLAT_IDLE_SECTION_TAG
import com.pocketshell.app.projects.FOLDER_LIST_LOADING_TAG
import com.pocketshell.app.projects.folderListFlatRowTestTag
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.SeedBeforeLaunchRule
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.proof.clearLastSessionPrefs
import com.pocketshell.app.test.testArtifactsRoot
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.settings.HostDetailViewMode
import com.pocketshell.app.testaccess.TestAccessEntryPoint
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_OPEN_FILES_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_OPEN_FILE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_OPEN_FILE_DIALOG_CONFIRM_TAG
import com.pocketshell.app.tmux.TMUX_OPEN_FILE_DIALOG_FIELD_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.entity.HostEntity
import dagger.hilt.android.EntryPointAccessors
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.security.MessageDigest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #1715 — the real MainActivity/navigation/process-restart proof.
 *
 * This deliberately goes through the production host row → folder list →
 * tmux session → kebab → FileViewer route. It opens A/B/C, finishes the first
 * MainActivity and cold-launches a second one while the session route is live,
 * re-enters Open files, switches the restored tabs, exercises dirty app-bar
 * Back and Submit-then-switch, and closes the restored workspace to the
 * intentional empty state.
 *
 * The host is `agents-daemon` (2239), so the persistence check reads the real
 * Python registry schema rather than the standard shell fixture.
 */
@RunWith(AndroidJUnit4::class)
class FileViewerTabsMainActivityJourneyDockerTest {

    // JOURNEY_HARNESS_JUSTIFIED: #1715 must finish and cold-launch a second
    // MainActivity in one instrumentation test to prove a fresh ViewModel
    // hydrates from the daemon after the first ActivityScenario is gone.
    private val compose = createEmptyComposeRule()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: SshKey.Pem
    private lateinit var sessionName: String
    private lateinit var pathA: String
    private lateinit var pathB: String
    private lateinit var pathC: String
    private var hostId: Long = 0L
    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun tearDown() {
        runCatching { launchedActivity?.close() }
        launchedActivity = null
        if (!::sessionName.isInitialized) {
            clearLastSessionPrefs()
            return
        }
        runBlocking {
            runCatching {
                connect()?.use { session ->
                    session.exec("tmux kill-session -t '${sessionName}' 2>/dev/null || true")
                    session.exec("rm -f '$pathA' '$pathB' '$pathC'")
                    resetDaemonState(session)
                }
            }
        }
        clearLastSessionPrefs()
        runCatching {
            testAccess().settingsRepository().setHostDetailViewMode(HostDetailViewMode.Tree)
        }
    }

    @Test
    fun mainActivityRestartRestoresTabsGuardsDirtyBackAndConsumesSubmitAction() {
        runBlocking {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            openSeededSession()

            openFileFromSession(pathA)
            returnToSession()
            openFileFromSession(pathB)
            returnToSession()
            openFileFromSession(pathC)
            returnToSession()
            awaitRegistryContains(pathA, pathB, pathC)

            // Finish the real Activity, then cold-launch a new MainActivity.
            // Clearing the test-only last-session hint makes the second launch
            // deterministically enter through the production host/navigation
            // route; the open-file tabs themselves must come from the daemon.
            launchedActivity?.close()
            launchedActivity = null
            clearLastSessionPrefs()
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            openSeededSession()

            openFilesFromSession()
            waitForTag(FILE_VIEWER_TAB_STRIP_TAG)
            waitForTag(FILE_VIEWER_TAB_TAG_PREFIX + pathA)
            waitForTag(FILE_VIEWER_TAB_TAG_PREFIX + pathB)
            waitForTag(FILE_VIEWER_TAB_TAG_PREFIX + pathC)
            assertTitleContains(pathC)
            WalkthroughScreenshotArtifacts.capture("issue1715-main-activity-restored")

            // Switching restored tabs is a pure tab-strip action; it must not
            // navigate/rebind through the session route.
            compose.onNodeWithTag(FILE_VIEWER_TAB_TAG_PREFIX + pathB).performClick()
            waitForTitle(pathB)
            compose.onNodeWithTag(FILE_VIEWER_TAB_TAG_PREFIX + pathA).performClick()
            waitForTitle(pathA)

            // Add real review work, then use the app-bar Back. The dirty guard
            // must keep the FileViewer destination on screen.
            // The restored route is visible before SFTP text hydration has
            // finished; the review toggle is rendered only for loaded text.
            waitForTag(FILE_VIEWER_REVIEW_TOGGLE_TAG)
            compose.onNodeWithTag(FILE_VIEWER_REVIEW_TOGGLE_TAG).performClick()
            waitForTag(FILE_VIEWER_COMMENTABLE_TEXT_TAG)
            compose.onNodeWithTag(fileViewerLineGutterTag(1)).performClick()
            waitForTag(FILE_VIEWER_REVIEW_LINE_SHEET_TAG)
            compose.onNodeWithTag(FILE_VIEWER_REVIEW_COMMENT_FIELD_TAG)
                .performTextInput("guard this tab")
            compose.onNodeWithTag(FILE_VIEWER_REVIEW_SAVE_TAG).performClick()
            waitUntil { !hasTag(FILE_VIEWER_REVIEW_LINE_SHEET_TAG) }

            compose.onNodeWithTag(FILE_VIEWER_BACK_TAG).performClick()
            waitForTag(FILE_VIEWER_DIRTY_WORK_DIALOG_TAG)
            assertTrue("dirty app-bar Back must keep FileViewer visible", hasTag(FILE_VIEWER_SCREEN_TAG))
            compose.onNodeWithTag(FILE_VIEWER_DIRTY_STAY_TAG).performClick()

            // Queue a switch while dirty, then Submit. A successful Submit
            // must consume the queued switch instead of leaving the user on A
            // with a hidden/stale pending action.
            compose.onNodeWithTag(FILE_VIEWER_TAB_TAG_PREFIX + pathB).performClick()
            waitForTag(FILE_VIEWER_DIRTY_WORK_DIALOG_TAG)
            compose.onNodeWithTag(FILE_VIEWER_DIRTY_SUBMIT_TAG).performClick()
            waitForTitle(pathB)
            waitUntil { !hasTag(FILE_VIEWER_DIRTY_WORK_DIALOG_TAG) }
            waitForTag(FILE_VIEWER_REVIEW_SAVED_SHEET_TAG)
            compose.onNodeWithTag(FILE_VIEWER_REVIEW_SAVED_DONE_TAG).performClick()

            // Close all restored tabs, proving the close reducer and the empty
            // workspace route after process-restart restore.
            closeTabAndWait(pathB, pathC)
            closeTabAndWait(pathC, pathA)
            compose.onNodeWithTag(FILE_VIEWER_TAB_CLOSE_TAG_PREFIX + pathA).performClick()
            waitForTag(FILE_VIEWER_EMPTY_WORKSPACE_TAG)
            assertTrue("closing the last restored tab must show empty workspace", hasTag(FILE_VIEWER_EMPTY_WORKSPACE_TAG))
            WalkthroughScreenshotArtifacts.capture("issue1715-main-activity-empty")
        }
    }

    /**
     * Phase one of the host-owned process-restart proof. The host harness
     * keeps this target process alive after the production A/B/C opens, then
     * issues `adb shell am force-stop` from outside the test process.
     */
    @Test
    fun externalProcessPhaseOnePersistsWorkspaceAndWaitsForForceStop() {
        requireExternalPhase("1")
        runBlocking {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            openSeededSession()

            openFileFromSession(pathA)
            returnToSession()
            openFileFromSession(pathB)
            returnToSession()
            openFileFromSession(pathC)
            returnToSession()
            awaitRegistryContains(pathA, pathB, pathC)

            writeExternalProcessArtifact(phase = 1, restored = false)
            awaitExternalProcessKeepalive()
        }
    }

    /**
     * Phase two is launched by a new direct `am instrument` invocation after
     * the host has externally force-stopped both APK packages. It uses the
     * same production host row/session and reads the daemon registry-backed
     * workspace through the normal MainActivity -> FileViewer route.
     */
    @Test
    fun externalProcessPhaseTwoRestoresWorkspaceAndActiveTab() {
        requireExternalPhase("2")
        val phaseOne = readExternalProcessArtifact(phase = 1)
        assertNotEquals(
            "phase two must run in a new target process after external force-stop",
            phaseOne.getValue("pid").toInt(),
            Process.myPid(),
        )
        runBlocking {
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            openSeededSession()
            openFilesFromSession()

            waitForTag(FILE_VIEWER_TAB_STRIP_TAG)
            val restoredTabs = phaseOne.getValue("workspace_tabs").split('|')
            restoredTabs.forEach { path ->
                waitForTag(FILE_VIEWER_TAB_TAG_PREFIX + path)
            }
            assertTitleContains(phaseOne.getValue("workspace_active"))
            awaitRegistryContains(*restoredTabs.toTypedArray())
            writeExternalProcessArtifact(phase = 2, restored = true)
        }
    }

    private suspend fun seedBeforeLaunch() {
        if (externalProcessPhase() == "2") {
            seedExistingExternalProcessFixture()
            return
        }
        clearLastSessionPrefs()
        val keyText = readFixtureKeyText()
        fixtureKey = SshKey.Pem(keyText)
        waitForSshFixtureReady(fixtureKey, port = DAEMON_PORT)

        val suffix = if (externalProcessPhase() != null) {
            externalProcessNamespace()
        } else {
            System.currentTimeMillis().toString().takeLast(7)
        }
        sessionName = "issue1715-files-$suffix"
        pathA = "/tmp/issue1715-main-a-$suffix.txt"
        pathB = "/tmp/issue1715-main-b-$suffix.txt"
        pathC = "/tmp/issue1715-main-c-$suffix.txt"
        connect()?.use { session ->
            val script = """
                set -eu
                tmux kill-session -t '$sessionName' 2>/dev/null || true
                printf 'A body\\nsecond line\\n' > '$pathA'
                printf 'B body\\nsecond line\\n' > '$pathB'
                printf 'C body\\nsecond line\\n' > '$pathC'
                tmux new-session -d -s '$sessionName' -c /tmp 'exec sleep 900'
                sleep 1
                tmux has-session -t '$sessionName'
            """.trimIndent()
            val result = session.exec(script)
            assertEquals("tmux/file seed must succeed", 0, result.exitCode)
            assertSeedVisibleToHost(session)
            resetDaemonState(session)
        } ?: error("could not connect to seed agents-daemon")
        hostId = seedHost(keyText)
        forceFlatHostDetailViewMode()
    }

    private fun readFixtureKeyText(): String = InstrumentationRegistry.getInstrumentation()
        .context.assets.open("test_key").bufferedReader().use { it.readText() }

    private suspend fun seedExistingExternalProcessFixture() {
        clearLastSessionPrefs()
        fixtureKey = SshKey.Pem(readFixtureKeyText())
        waitForSshFixtureReady(fixtureKey, port = DAEMON_PORT)
        val phaseOne = readExternalProcessArtifact(phase = 1)
        sessionName = phaseOne.getValue("producer_session_name")
        pathA = phaseOne.getValue("path_a")
        pathB = phaseOne.getValue("path_b")
        pathC = phaseOne.getValue("path_c")
        hostId = phaseOne.getValue("host_id").toLong()
        check(testAccess().appDatabase().hostDao().getById(hostId) != null) {
            "phase two must reuse the durable production host row $hostId"
        }
        forceFlatHostDetailViewMode()
    }

    private suspend fun seedHost(keyText: String): Long {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // MainActivity observes the Hilt singleton database. A separate
        // Room connection can write the row successfully while leaving the
        // already-running host Flow unaware of it.
        val db = testAccess().appDatabase()
        db.clearAllTables()
        val storedKey = SshKeyStorage.persistKey(
            context = context,
            sshKeyDao = db.sshKeyDao(),
            name = "issue1715-main-key-${System.currentTimeMillis()}",
            content = keyText,
        )
        return db.hostDao().insert(
            HostEntity(
                name = HOST_NAME,
                hostname = DEFAULT_HOST,
                port = DAEMON_PORT,
                username = DEFAULT_USER,
                keyId = storedKey.id,
                tmuxInstalled = true,
                lastBootstrapAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun forceFlatHostDetailViewMode() {
        // Use the same SettingsRepository instance that MainActivity reads;
        // editing prefs through a throwaway Context does not update its
        // already-created StateFlow.
        val settings = testAccess().settingsRepository()
        settings.setHostDetailViewMode(HostDetailViewMode.Flat)
        assertEquals(
            "MainActivity must receive the production Flat view-mode state",
            HostDetailViewMode.Flat,
            settings.settings.value.hostDetailViewMode,
        )
    }

    private fun testAccess(): TestAccessEntryPoint =
        EntryPointAccessors.fromApplication(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            TestAccessEntryPoint::class.java,
        )

    private fun openSeededSession() {
        waitUntil {
            dismissBootstrapSheetIfPresent()
            hasTag(HOST_ROW_TAG_PREFIX + hostId) || hasTag(FOLDER_LIST_SCREEN_TAG)
        }
        if (hasTag(HOST_ROW_TAG_PREFIX + hostId)) {
            compose.onNodeWithTag(HOST_ROW_TAG_PREFIX + hostId).performClick()
        }
        waitUntil {
            dismissBootstrapSheetIfPresent()
            hasTag(FOLDER_LIST_SCREEN_TAG)
        }
        waitForSeededSessionRow()
        compose.onNodeWithTag(folderListFlatRowTestTag(sessionName)).performClick()
        waitForTag(TMUX_SESSION_SCREEN_TAG)
    }

    /** The real host probe can show setup guidance before the folder list. */
    private fun dismissBootstrapSheetIfPresent() {
        if (hasTag(HOST_BOOTSTRAP_SKIP_TAG)) {
            runCatching {
                compose.onNodeWithTag(HOST_BOOTSTRAP_SKIP_TAG).performClick()
            }
        }
    }

    private fun openFileFromSession(path: String) {
        openMoreMenu()
        compose.onNodeWithTag(TMUX_OPEN_FILE_BUTTON_TAG).performClick()
        waitForTag(TMUX_OPEN_FILE_DIALOG_FIELD_TAG)
        compose.onNodeWithTag(TMUX_OPEN_FILE_DIALOG_FIELD_TAG).performTextInput(path)
        compose.onNodeWithTag(TMUX_OPEN_FILE_DIALOG_CONFIRM_TAG).performClick()
        waitForTag(FILE_VIEWER_SCREEN_TAG)
        waitForTag(FILE_VIEWER_TAB_TAG_PREFIX + path)
        waitForTitle(path)
    }

    private fun returnToSession() {
        compose.onNodeWithTag(FILE_VIEWER_BACK_TAG).performClick()
        waitForTag(TMUX_SESSION_SCREEN_TAG)
    }

    private fun openFilesFromSession() {
        openMoreMenu()
        compose.onNodeWithTag(TMUX_OPEN_FILES_BUTTON_TAG).performClick()
        waitForTag(FILE_VIEWER_SCREEN_TAG)
    }

    private fun openMoreMenu() {
        val full = compose
            .onAllNodesWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (full.isNotEmpty()) {
            compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG).performClick()
        } else {
            compose.onNodeWithTag(TMUX_COMPACT_CHROME_MORE_BUTTON_TAG).performClick()
        }
        waitForTag(TMUX_OPEN_FILES_BUTTON_TAG)
    }

    private fun closeTabAndWait(path: String, nextPath: String) {
        compose.onNodeWithTag(FILE_VIEWER_TAB_CLOSE_TAG_PREFIX + path).performClick()
        waitForTitle(nextPath)
        waitUntil { !hasTag(FILE_VIEWER_TAB_CLOSE_TAG_PREFIX + path) }
    }

    private fun waitForTitle(path: String) {
        waitUntil { titleText().contains(path.substringAfterLast('/')) }
    }

    private fun assertTitleContains(path: String) {
        assertTrue(
            "expected viewer title to contain ${path.substringAfterLast('/')}, was ${titleText()}",
            titleText().contains(path.substringAfterLast('/')),
        )
    }

    private fun titleText(): String = compose
        .onNodeWithTag(FILE_VIEWER_TITLE_TAG, useUnmergedTree = true)
        .fetchSemanticsNode()
        .config
        .getOrNull(SemanticsProperties.Text)
        .orEmpty()
        .joinToString(separator = "") { it.text }

    private fun waitForTag(tag: String) {
        waitUntil { hasTag(tag) }
    }

    /**
     * Fail early with the actual rendered state and remote enumeration instead
     * of spending the full screen timeout on an absent row. The previous red
     * run proved only that the FolderList route existed; it did not say whether
     * the VM was still Loading, had an empty Ready result, rendered Tree mode,
     * or rendered a different session from stale state.
     */
    private fun waitForSeededSessionRow() {
        val rowTag = folderListFlatRowTestTag(sessionName)
        runCatching {
            compose.waitUntil(timeoutMillis = ROW_DIAGNOSTIC_TIMEOUT_MS) {
                hasTag(rowTag) ||
                    hasTag(FOLDER_LIST_CONTENT_TAG) ||
                    hasTag(FOLDER_LIST_FLAT_EMPTY_TAG) ||
                    hasTag(FOLDER_LIST_FLAT_ACTIVE_SECTION_TAG) ||
                    hasTag(FOLDER_LIST_FLAT_IDLE_SECTION_TAG) ||
                    hasTag(FOLDER_LIST_ERROR_TAG)
            }
        }
        if (hasTag(rowTag)) return

        val semanticTree = runCatching {
            compose.onRoot(useUnmergedTree = true)
                .printToString(maxDepth = 14)
        }.getOrElse { "<semantics unavailable: ${it.message}>" }
        val visibleTags = listOf(
            FOLDER_LIST_CONTENT_TAG,
            FOLDER_LIST_LOADING_TAG,
            FOLDER_LIST_ERROR_TAG,
            FOLDER_LIST_FLAT_EMPTY_TAG,
            FOLDER_LIST_FLAT_ACTIVE_SECTION_TAG,
            FOLDER_LIST_FLAT_IDLE_SECTION_TAG,
        ).filter(::hasTag)
        val remote = runBlocking { remoteNavigationSnapshot() }
        val diagnostic = buildString {
            appendLine("#1715 MainActivity seeded-row diagnostic")
            appendLine("expectedSession=$sessionName")
            appendLine("visibleTags=$visibleTags")
            appendLine("remote=$remote")
            appendLine("semantics=$semanticTree")
        }
        Log.e(DIAGNOSTIC_LOG_TAG, diagnostic)
        throw AssertionError(diagnostic)
    }

    private fun waitUntil(predicate: () -> Boolean) {
        compose.waitUntil(timeoutMillis = SCREEN_TIMEOUT_MS, condition = predicate)
    }

    private fun hasTag(tag: String): Boolean = runCatching {
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
    }.getOrDefault(false)

    private fun requireExternalPhase(expected: String) {
        check(externalProcessPhase() == expected) {
            "${this::class.simpleName} phase method requires " +
                "$ARG_PHASE=$expected; invoke it through the host-owned external " +
                "process-restart harness"
        }
    }

    private fun externalProcessPhase(): String? = InstrumentationRegistry
        .getArguments()
        .getString(ARG_PHASE)

    private fun externalProcessNamespace(): String = requireNotNull(
        InstrumentationRegistry.getArguments().getString(ARG_RUN_NAMESPACE),
    ) { "$ARG_RUN_NAMESPACE is required for an external process proof" }

    private fun externalProcessArtifactDirectory(): File = File(
        testArtifactsRoot(InstrumentationRegistry.getInstrumentation().targetContext),
        "process-restart/${externalProcessNamespace()}",
    ).apply { mkdirs() }

    private fun readExternalProcessArtifact(phase: Int): Map<String, String> {
        val artifact = File(externalProcessArtifactDirectory(), "phase-$phase.txt")
        check(artifact.isFile && artifact.length() > 0L) {
            "external process phase $phase artifact is absent: $artifact"
        }
        return artifact.readLines()
            .filter { it.isNotBlank() }
            .associate { line ->
                val separator = line.indexOf('=')
                check(separator > 0) { "invalid external process artifact line: $line" }
                line.substring(0, separator) to line.substring(separator + 1)
            }
    }

    private fun writeExternalProcessArtifact(phase: Int, restored: Boolean) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetPackage = instrumentation.targetContext.packageName
        val testPackage = instrumentation.context.packageName
        val paths = listOf(pathA, pathB, pathC)
        val artifact = File(externalProcessArtifactDirectory(), "phase-$phase.txt")
        val temporary = File(artifact.parentFile, ".phase-$phase-${Process.myPid()}.tmp")
        temporary.writeText(
            buildString {
                appendLine("schema=1")
                appendLine("run_namespace=${externalProcessNamespace()}")
                appendLine("phase=$phase")
                appendLine("pid=${Process.myPid()}")
                appendLine("process_name=${Application.getProcessName()}")
                appendLine("target_package=$targetPackage")
                appendLine("test_package=$testPackage")
                appendLine("host_id=$hostId")
                appendLine("producer_fixture_name=$HOST_NAME")
                appendLine("producer_fixture_host=$DEFAULT_HOST")
                appendLine("producer_fixture_port=$DAEMON_PORT")
                appendLine("producer_fixture_user=$DEFAULT_USER")
                appendLine("producer_key_path=asset:test_key")
                appendLine("producer_session_name=$sessionName")
                appendLine("path_a=$pathA")
                appendLine("path_b=$pathB")
                appendLine("path_c=$pathC")
                appendLine("workspace_tabs=${paths.joinToString("|")}")
                appendLine("workspace_tab_count=${paths.size}")
                appendLine("workspace_active=$pathC")
                appendLine("workspace_registry_schema=daemon-tree-registry.json:file_workspaces.default")
                appendLine("navigation_route=MainActivity>FolderList>Session>FileViewer")
                appendLine("generation_origin=$DAEMON_GENERATION_ORIGIN")
                appendLine("persistence_origin=${if (phase == 1) {
                    "FileWorkspaceRemoteSource.upsertWorkspace"
                } else {
                    "FileWorkspaceRemoteSource.getWorkspace"
                }}")
                appendLine("restored_workspace=$restored")
                appendLine("external_pid_boundary=true")
            },
        )
        check(temporary.renameTo(artifact)) { "could not atomically publish $artifact" }
        if (phase == 1) publishExternalProcessReadyMarker(artifact)
    }

    private fun publishExternalProcessReadyMarker(artifact: File) {
        val bytes = artifact.readBytes()
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val marker = File(artifact.parentFile, "phase-1.ready")
        val temporary = File(artifact.parentFile, ".phase-1.ready-${Process.myPid()}.tmp")
        temporary.writeText(
            buildString {
                appendLine("schema=1")
                appendLine("run_namespace=${externalProcessNamespace()}")
                appendLine("phase=1")
                appendLine("ready=true")
                appendLine("artifact=phase-1.txt")
                appendLine("artifact_complete=true")
                appendLine("pid=${Process.myPid()}")
                appendLine("process_name=${Application.getProcessName()}")
                appendLine("target_package=${instrumentation.targetContext.packageName}")
                appendLine("test_package=${instrumentation.context.packageName}")
                appendLine("artifact_bytes=${bytes.size}")
                appendLine("artifact_sha256=$digest")
            },
        )
        check(temporary.renameTo(marker)) { "could not atomically publish $marker" }
    }

    private fun awaitExternalProcessKeepalive() {
        val rawMillis = InstrumentationRegistry.getArguments()
            .getString(ARG_PHASE_ONE_KEEPALIVE_MILLIS)
        val keepaliveMillis = requireNotNull(rawMillis?.toLongOrNull()) {
            "$ARG_PHASE_ONE_KEEPALIVE_MILLIS is required and must be positive"
        }
        require(keepaliveMillis in 1L..MAX_PHASE_ONE_KEEPALIVE_MILLIS) {
            "$ARG_PHASE_ONE_KEEPALIVE_MILLIS must be between 1 and " +
                "$MAX_PHASE_ONE_KEEPALIVE_MILLIS milliseconds"
        }
        val deadline = SystemClock.elapsedRealtime() + keepaliveMillis
        while (true) {
            val remaining = deadline - SystemClock.elapsedRealtime()
            if (remaining <= 0L) return
            SystemClock.sleep(minOf(remaining, PHASE_ONE_KEEPALIVE_POLL_MILLIS))
        }
    }

    private suspend fun awaitRegistryContains(vararg paths: String) {
        withTimeout(REGISTRY_TIMEOUT_MS) {
            while (true) {
                val stdout = connect()?.use { session ->
                    session.exec(
                        "cat \"\${XDG_STATE_HOME:-\$HOME/.local/state}/pocketshell/tree/registry.json\"",
                    ).stdout
                }
                if (stdout != null &&
                    stdout.contains("\"file_workspaces\"") &&
                    stdout.contains("\"default\"") &&
                    paths.all(stdout::contains) &&
                    (stdout.contains("\"active_path\": \"$pathC\"") ||
                        stdout.contains("\"active_path\":\"$pathC\""))
                ) {
                    return@withTimeout
                }
                delay(200)
            }
        }
    }

    private suspend fun assertSeedVisibleToHost(session: com.pocketshell.core.ssh.SshSession) {
        val probes = linkedMapOf(
            "tmuxSessions" to session.exec(
                "tmux -u list-sessions -F " +
                    "'#{session_name}::#{session_id}::#{session_created}::#{session_activity}::" +
                    "#{session_attached}::#{@ps_agent_kind}::#{@ps_agent_profile}::" +
                    "#{@ps_agent_state}::#{@ps_agent_state_updated_at}::#{session_path}'",
            ),
            "tmuxPanes" to session.exec(
                "tmux -u list-panes -a -F " +
                    "'#{session_name}::#{window_index}::#{window_name}::#{window_active}::" +
                    "#{pane_active}::#{pane_current_path}::#{pane_tty}::#{pane_current_command}::" +
                    "#{window_id}::#{pane_pid}'",
            ),
            "pocketshellSessions" to session.exec("pocketshell sessions list --by activity"),
            "tmuxctl" to session.exec("tmuxctl list"),
        )
        val failed = probes.filterValues { result ->
            result.exitCode != 0 || !result.stdout.contains(sessionName)
        }
        assertTrue(
            "seeded session must be visible through every production folder-list " +
                "enumeration shape; missing=${failed.keys}; probes=$probes",
            failed.isEmpty(),
        )
    }

    private suspend fun resetDaemonState(session: com.pocketshell.core.ssh.SshSession) {
        val result = session.exec(
            "printf '%s' '{\"tabs\":[],\"active_path\":null}' | pocketshell tree workspace-upsert >/dev/null",
        )
        assertEquals("real daemon workspace reset must be acknowledged", 0, result.exitCode)
        val treeResult = session.exec(
            "printf '%s' '{\"host\":\"$HOST_NAME\",\"nodes\":[]}' | " +
                "pocketshell tree upsert >/dev/null",
        )
        assertEquals("real daemon host-tree reset must be acknowledged", 0, treeResult.exitCode)
    }

    private suspend fun remoteNavigationSnapshot(): String =
        connect()?.use { session ->
            buildString {
                val native = session.exec(
                    "tmux -u list-sessions -F " +
                        "'#{session_name}::#{session_id}::#{session_created}::#{session_activity}::" +
                        "#{session_attached}::#{@ps_agent_kind}::#{@ps_agent_profile}::" +
                        "#{@ps_agent_state}::#{@ps_agent_state_updated_at}::#{session_path}'",
                )
                val panes = session.exec(
                    "tmux -u list-panes -a -F " +
                        "'#{session_name}::#{window_index}::#{window_name}::#{window_active}::" +
                        "#{pane_active}::#{pane_current_path}::#{pane_tty}::#{pane_current_command}::" +
                        "#{window_id}::#{pane_pid}'",
                )
                val pocketshell = session.exec("pocketshell sessions list --by activity")
                val registry = session.exec(
                    "cat \"\${XDG_STATE_HOME:-\$HOME/.local/state}/pocketshell/tree/registry.json\"",
                )
                append("tmuxSessions(exit=${native.exitCode})=${native.stdout.trim()}")
                append("; tmuxPanes(exit=${panes.exitCode})=${panes.stdout.trim()}")
                append("; pocketshell(exit=${pocketshell.exitCode})=${pocketshell.stdout.trim()}")
                append("; registry(exit=${registry.exitCode})=${registry.stdout.trim()}")
            }
        }?.take(MAX_DIAGNOSTIC_TEXT_LENGTH) ?: "<SSH diagnostic connect failed>"

    private suspend fun connect() = SshConnection.connect(
        host = DEFAULT_HOST,
        port = DAEMON_PORT,
        user = DEFAULT_USER,
        key = fixtureKey,
        knownHosts = KnownHostsPolicy.AcceptAll,
        timeoutMs = 10_000,
    ).getOrNull()

    private companion object {
        const val ARG_PHASE = "pocketshellPhase"
        const val ARG_RUN_NAMESPACE = "pocketshellRunNamespace"
        const val ARG_PHASE_ONE_KEEPALIVE_MILLIS = "pocketshellPhaseOneKeepaliveMillis"
        const val MAX_PHASE_ONE_KEEPALIVE_MILLIS = 300_000L
        const val PHASE_ONE_KEEPALIVE_POLL_MILLIS = 100L
        const val HOST_NAME = "Issue1715 MainActivity"
        const val DAEMON_GENERATION_ORIGIN =
            "agents-daemon-2239-folder-list-session-to-file-workspace-daemon-registry"
        const val DIAGNOSTIC_LOG_TAG = "Issue1715Journey"
        const val DAEMON_PORT = 2239
        const val ROW_DIAGNOSTIC_TIMEOUT_MS = 20_000L
        const val SCREEN_TIMEOUT_MS = 45_000L
        const val REGISTRY_TIMEOUT_MS = 20_000L
        const val MAX_DIAGNOSTIC_TEXT_LENGTH = 12_000
    }
}
