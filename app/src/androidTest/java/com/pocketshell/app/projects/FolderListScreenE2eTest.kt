package com.pocketshell.app.projects

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.core.graphics.createBitmap
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.composer.PromptComposerViewModel
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.core.portfwd.RemotePort
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.model.SessionAgentKind
import com.pocketshell.uikit.theme.PocketShellTheme
import com.pocketshell.uikit.theme.PocketShellThemeMode
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
import java.util.concurrent.atomic.AtomicInteger

/**
 * Compose-level connected E2E test for [FolderListScreen] — issue #171
 * round 2 blocker #2.
 *
 * The previous round of #171 only verified the SSH gateway in isolation
 * (`FolderListGatewayDockerTest`) — there was no proof that the
 * resulting `FolderListUiState.Ready` actually rendered correctly. This
 * test wires the screen against an in-memory DAO + a fake
 * [FolderListGateway] and asserts:
 *
 *  1. The folder list mounts and shows the host name on the app-bar.
 *  2. Active project rows render with horizontal count pills and
 *     inactive scanned folders stay hidden from the main tree.
 *  3. Projects are collapsed by default; expanding a project reveals
 *     tappable session rows with compact active/idle indicators.
 *  4. Inactive scanned folders stay out of the main tree and appear in
 *     the root add sheet, which exposes quick actions plus a
 *     start-session path for the selected project.
 *  5. The SessionTypePickerSheet opens on the FAB tap and the agent /
 *     shell segments + agent CLI radio buttons are present.
 *
 * The screen surface is captured to `additional_test_output/issue300-folder-tree/` as a
 * viewport bitmap (artifact-pattern matching `*-viewport.png`) so a
 * reviewer can verify the rendered output against the spike's locked
 * design tokens.
 */
@RunWith(AndroidJUnit4::class)
class FolderListScreenE2eTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var db: AppDatabase
    private val hostId: Long = 7L

    @Before
    fun openDatabase(): Unit = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "issue171-key", privateKeyPath = "/tmp/issue171"),
        )
        db.hostDao().insert(
            HostEntity(
                id = hostId,
                name = "issue171-host",
                hostname = "h.example",
                port = 22,
                username = "u",
                keyId = keyId,
            ),
        )
        // Seed the review fixture roots verbatim. The fake gateway
        // resolves them to absolute paths and returns active projects
        // under each root plus outside-root sessions after them.
        db.projectRootDao().insert(
            ProjectRootEntity(
                hostId = hostId,
                label = "[00] git",
                path = "~/git",
            ),
        )
        db.projectRootDao().insert(
            ProjectRootEntity(
                hostId = hostId,
                label = "[01] tmp",
                path = "~/tmp",
            ),
        )
    }

    @After
    fun closeDatabase() {
        db.close()
    }

    @Test
    fun folderListRendersGroupedSessionsAndPickerOpens() {
        val fakeGateway = FakeFolderListGateway(
            rows = listOf(
                FolderSessionRow(
                    sessionName = "claude-main",
                    lastActivity = 1_700_004_000L,
                    attached = true,
                    cwd = "/home/u/git/pocketshell",
                    agentKind = SessionAgentKind.Claude,
                ),
                FolderSessionRow(
                    sessionName = "build-shell",
                    lastActivity = 1_700_003_500L,
                    attached = false,
                    cwd = "/home/u/git/pocketshell",
                    agentKind = SessionAgentKind.Shell,
                ),
                FolderSessionRow(
                    sessionName = "codex-llm",
                    lastActivity = 1_700_001_000L,
                    attached = true,
                    cwd = "/home/u/git/llm-zoomcamp",
                    agentKind = SessionAgentKind.Codex,
                ),
                FolderSessionRow(
                    sessionName = "tmp-agent",
                    lastActivity = 1_700_002_000L,
                    attached = true,
                    cwd = "/home/u/tmp/scratch",
                    agentKind = SessionAgentKind.OpenCode,
                ),
                FolderSessionRow(
                    sessionName = "tmp-codex",
                    lastActivity = 1_700_001_500L,
                    attached = false,
                    cwd = "/home/u/tmp/scratch",
                    agentKind = SessionAgentKind.Codex,
                ),
                FolderSessionRow(
                    sessionName = "notes-shell",
                    lastActivity = 1_700_000_800L,
                    attached = false,
                    cwd = "/home/u/tmp/notebooks",
                    agentKind = SessionAgentKind.Shell,
                ),
                FolderSessionRow(
                    sessionName = "outside-lab",
                    lastActivity = 1_700_005_000L,
                    attached = true,
                    cwd = "/opt/work/demo",
                    agentKind = SessionAgentKind.Claude,
                ),
            ),
            projectFoldersByRoot = mapOf(
                "~/git" to listOf(
                    "/home/u/git/pocketshell",
                    "/home/u/git/llm-zoomcamp",
                    "/home/u/git/empty-pinned",
                ),
                "~/tmp" to listOf(
                    "/home/u/tmp/scratch",
                    "/home/u/tmp/notebooks",
                    "/home/u/tmp/old-run",
                ),
            ),
            resolvedWatchedRootPaths = mapOf(
                "~/git" to "/home/u/git",
                "~/tmp" to "/home/u/tmp",
            ),
        )
        val viewModel = FolderListViewModel(
            gateway = fakeGateway,
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(InstrumentationRegistry.getInstrumentation().targetContext),
        )
        val dictationViewModel = noopAssistantDictationViewModel()
        var openedWorkspaceSettings = false

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "issue171-host",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue171",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _ -> },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onOpenWorkspaceSettings = { openedWorkspaceSettings = true },
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    assistantDictationViewModel = dictationViewModel,
                )
            }
        }

        // Wait for the bind() + initial probe to complete.
        compose.waitUntil(timeoutMillis = 10_000) {
            fakeGateway.callCount.get() >= 1 &&
                compose.onAllNodesWithTag(folderTreeRootTestTag("~/git"))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(folderRowTestTag("/home/u/git/pocketshell")))

        // --- Assertion 1: screen mounted with the host name on the
        //    app-bar header.
        compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG).assertExists()
        compose.onNodeWithTag(FOLDER_LIST_TITLE_TAG).assertExists()
        compose.onNodeWithText("issue171-host").assertExists()
        compose.onNodeWithText("Workspace roots").assertDoesNotExist()
        compose.onNodeWithText("Flat projects").assertDoesNotExist()
        compose.onNodeWithText("Repos").assertDoesNotExist()
        compose.onNodeWithContentDescription("Browse repos").assertExists()
        compose.onNodeWithTag(folderTreeRootLabelTag("~/git")).assertExists()
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(folderTreeRootLabelTag("~/tmp")))
        compose.onNodeWithTag(folderTreeRootLabelTag("~/tmp")).assertExists()
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(folderTreeRootLabelTag(FolderListViewModel.OTHER_ROOT_PATH)))
        compose.onNodeWithTag(folderTreeRootLabelTag(FolderListViewModel.OTHER_ROOT_PATH)).assertExists()
        val readyRoots = (viewModel.state.value as FolderListUiState.Ready).treeRoots
        assertEquals(
            listOf("git", "tmp", FolderListViewModel.OTHER_ROOT_LABEL),
            readyRoots.map { it.label },
        )
        assertEquals(listOf("~/git", "~/tmp", FolderListViewModel.OTHER_ROOT_PATH), readyRoots.map { it.path })
        assertEquals(
            listOf("pocketshell", "llm-zoomcamp"),
            readyRoots[0].folders.map { it.label },
        )
        assertEquals(listOf("scratch", "notebooks"), readyRoots[1].folders.map { it.label })
        assertEquals(listOf("demo"), readyRoots[2].folders.map { it.label })
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(folderRowTestTag("/home/u/git/pocketshell")))
        compose.onAllNodesWithTag(FOLDER_LIST_PORT_FORWARDING_TAG)
            .fetchSemanticsNodes()
            .also {
                assertTrue("off/empty forwarding summary should not render above the tree", it.isEmpty())
            }
        compose.onAllNodesWithTag(FOLDER_LIST_VIEW_TOGGLE_TAG)
            .fetchSemanticsNodes()
            .also { assertTrue("Tree/Flat toggle should not render on host detail", it.isEmpty()) }
        compose.onNodeWithTag(FOLDER_LIST_WORKSPACE_SETTINGS_TAG)
            .assertExists()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) { openedWorkspaceSettings }
        compose.onNodeWithContentDescription("Host assistant").assertExists()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_TAG)
            .assertExists()
            .performClick()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_ICON_TAG, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_PANEL_TAG).assertExists()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_PROMPT_MIC_TAG).assertExists()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_PROMPT_TAG)
            .performTextInput("create a project called notes under git")
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_SUBMIT_TAG).performClick()
        compose.onNodeWithText("No assistant provider configured. Add an API key in Settings → Assistant.")
            .assertExists()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_CLOSE_TAG).performClick()

        // --- Assertion 2: active project rows under configured roots
        //    render compactly. Inactive scanned projects stay sheet-only.
        compose.onNodeWithTag(folderHeaderLabelTag("/home/u/git/pocketshell"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(folderHeaderLabelTag("/home/u/git/llm-zoomcamp"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(folderHeaderLabelTag("/home/u/git/empty-pinned"), useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(folderCountPillTestTag("/home/u/git/pocketshell"), useUnmergedTree = true)
            .assertTextEquals("active · 2 sessions · 1 agent")
        assertProjectNameKeepsReadableWidth("/home/u/git/pocketshell")
        compose.onNodeWithTag(folderCountPillTestTag("/home/u/git/llm-zoomcamp"), useUnmergedTree = true)
            .assertTextEquals("active · 1 agent")
        assertProjectNameKeepsReadableWidth("/home/u/git/llm-zoomcamp")
        compose.onNodeWithTag(
            folderStatusDotTestTag("/home/u/git/pocketshell"),
            useUnmergedTree = true,
        ).assertExists()
        assertAccessibleTouchTarget(folderTreeRootActionsTestTag("~/git"))
        assertAccessibleTouchTarget(folderTreeRootCreateTestTag("~/git"))
        assertAccessibleTouchTarget(folderTreeRootActionsTestTag("~/tmp"))
        assertAccessibleTouchTarget(folderTreeRootCreateTestTag("~/tmp"))
        assertAccessibleTouchTarget(folderDetailActionsTestTag("/home/u/git/pocketshell"))
        assertAccessibleTouchTarget(folderDetailEnvTestTag("/home/u/git/pocketshell"))
        assertAccessibleTouchTarget(folderDetailCreateTestTag("/home/u/git/pocketshell"))
        compose.onNodeWithText("+ New", useUnmergedTree = true).assertDoesNotExist()

        // --- Assertion 3: projects are collapsed by default; expanding
        //    pocketshell reveals agent sessions before idle shell
        //    sessions and keeps the raw tmux name as fallback text.
        compose.onNodeWithText("claude-main", useUnmergedTree = true).assertDoesNotExist()
        compose.onNodeWithTag(
            folderDetailDisclosureTestTag("/home/u/git/pocketshell"),
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            (viewModel.state.value as? FolderListUiState.Ready)
                ?.expandedProjectPaths
                ?.contains("/home/u/git/pocketshell") == true
        }
        compose.onNodeWithText("claude-main", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(
            folderSessionStatusDotTestTag("/home/u/git/pocketshell", "claude-main"),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithText("build-shell", useUnmergedTree = true).assertExists()

        // Issue #276: per-host session rows stay compact. The retired
        // dashboard badge, empty host separator, and prose status line
        // must not render on the folder/session surface.
        compose.onNodeWithContentDescription("Session initial C").assertDoesNotExist()
        compose.onNodeWithContentDescription("Session initial B").assertDoesNotExist()
        compose.onNodeWithText(" · ").assertDoesNotExist()
        compose.onNodeWithText("claude conversation active").assertDoesNotExist()
        compose.onNodeWithText("codex conversation active").assertDoesNotExist()
        compose.onNodeWithText("tmux session detached").assertDoesNotExist()

        // Agent / shell rollup labels visible on the expanded project.
        compose.onNodeWithText("Claude").assertExists()
        compose.onNodeWithText("Idle").assertExists()

        // --- Capture viewport before opening any picker/sheet (artifact
        //    path matches the project's `*-viewport.png` convention so
        //    the reviewer's artifact-driven check picks it up).
        captureViewport("issue300-folder-tree-rendered-viewport.png")

        // --- Assertion 4: inactive scanned folders are sheet-only.
        //    The root add affordance opens RootProjectAddSheet with quick actions and a
        //    candidate row that starts a session in the inactive folder.
        compose.onNodeWithText("Watched").assertDoesNotExist()
        compose.onAllNodesWithTag(folderDetailCreateTestTag("/home/u/git/empty-pinned"))
            .fetchSemanticsNodes().also {
                assertTrue("expected empty-pinned folder to stay out of the main tree", it.isEmpty())
            }

        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(folderTreeRootCreateTestTag("~/git")))
        compose.onNodeWithTag(folderTreeRootCreateTestTag("~/git")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(ROOT_PROJECT_ADD_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ROOT_PROJECT_ADD_EMPTY_PROJECT_TAG).assertExists()
        compose.onNodeWithTag(ROOT_PROJECT_ADD_CLONE_TAG).assertExists()
        compose.onNodeWithTag(rootProjectCandidateTestTag("/home/u/git/empty-pinned")).assertExists()
        compose.onNodeWithTag(rootProjectCandidateTestTag("/home/u/git/empty-pinned")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(SESSION_TYPE_PICKER_SHELL_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("in empty-pinned").assertExists()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CWD_TAG).assertExists()
        compose.onNodeWithText("/home/u/git/empty-pinned").assertExists()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CANCEL_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(SESSION_TYPE_PICKER_SHELL_TAG).fetchSemanticsNodes().isEmpty()
        }

        // --- Assertion 5: SessionTypePickerSheet opens on FAB tap and
        //    shows the agent / shell segments + agent CLI radio rows.
        compose.onNodeWithTag(FOLDER_LIST_NEW_SESSION_FAB_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(SESSION_TYPE_PICKER_SHELL_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(SESSION_TYPE_PICKER_SHELL_TAG).assertExists()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_AGENT_TAG).assertExists()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_AGENT_CLAUDE_TAG).assertExists()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_AGENT_CODEX_TAG).assertExists()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_AGENT_OPENCODE_TAG).assertExists()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CREATE_TAG).assertExists()

        // Capture the picker sheet via the full-device screenshot path
        // (ModalBottomSheet renders into its own window, so capturing
        // the FOLDER_LIST_SCREEN_TAG node alone would only show the
        // dimmed scrim behind the sheet). The picker is confirmed
        // visible by the assertExists() chain above, so the
        // screenshot taken right after is guaranteed to include it.
        android.os.SystemClock.sleep(250)
        captureFullDevice("issue300-session-type-picker-viewport.png")
    }

    @Test
    fun discoveredPortForwardingSummaryStaysBelowWorkspaceTree() {
        val fakeGateway = FakeFolderListGateway(
            rows = emptyList(),
            discoveredPorts = listOf(RemotePort(port = 3000, processName = "node")),
        )
        val viewModel = FolderListViewModel(
            gateway = fakeGateway,
            hostDao = db.hostDao(),
            projectRootDao = db.projectRootDao(),
            forwardingController = ForwardingController(InstrumentationRegistry.getInstrumentation().targetContext),
        )
        var openedPortForwarding = false

        compose.setContent {
            PocketShellTheme(mode = PocketShellThemeMode.Dark) {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "issue171-host",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue171",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _ -> },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onOpenPortForwarding = { openedPortForwarding = true },
                    onOpenWorkspaceSettings = {},
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            fakeGateway.callCount.get() >= 1 &&
                compose.onAllNodesWithTag(folderTreeRootTestTag("~/git"))
                    .fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodesWithTag(FOLDER_LIST_PORT_FORWARDING_TAG)
                    .fetchSemanticsNodes().isNotEmpty()
        }

        val rootBounds = compose.onNodeWithTag(folderTreeRootTestTag("~/git"))
            .fetchSemanticsNode()
            .boundsInRoot
        val forwardingBounds = compose.onNodeWithTag(FOLDER_LIST_PORT_FORWARDING_TAG)
            .assertHasClickAction()
            .fetchSemanticsNode()
            .boundsInRoot
        assertTrue(
            "workspace tree should remain above discovered forwarding summary",
            rootBounds.top < forwardingBounds.top,
        )

        compose.onNodeWithTag(FOLDER_LIST_PORT_FORWARDING_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { openedPortForwarding }
    }

    private fun assertProjectNameKeepsReadableWidth(folderPath: String) {
        val labelBounds = compose.onNodeWithTag(
            folderHeaderLabelTag(folderPath),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(
            folderCountPillTestTag(folderPath),
            useUnmergedTree = true,
        ).assertExists()
        assertTrue(
            "project label for $folderPath should keep enough readable width: ${labelBounds.width}",
            labelBounds.width >= 180f,
        )
    }

    private fun assertAccessibleTouchTarget(tag: String) {
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(tag))
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .assertExists()
            .assertHasClickAction()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val minPx = 48f * density
        assertTrue(
            "control $tag should expose at least a 48dp tap target: ${bounds.width}x${bounds.height}",
            bounds.width >= minPx && bounds.height >= minPx && bounds.width >= bounds.height * 0.85f,
        )
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap: Bitmap = try {
            instrumentation.uiAutomation.takeScreenshot() ?: return
        } catch (t: Throwable) {
            return
        }
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val outDir = File(mediaRoot, "additional_test_output/issue300-folder-tree").apply {
            if (!exists()) mkdirs()
        }
        val file = File(outDir, name)
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }

    private fun captureViewport(name: String) {
        val node = compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG)
        val image = try {
            node.captureToImage()
        } catch (t: Throwable) {
            return // capture is best-effort — the assertions above are the
            // load-bearing checks; we don't fail the test on a render
            // capture flake.
        }
        val bitmap: Bitmap = createBitmap(image.width, image.height)
        val pixels = IntArray(image.width * image.height)
        image.readPixels(pixels)
        bitmap.setPixels(pixels, 0, image.width, 0, 0, image.width, image.height)

        // Use the standard `additional_test_output/<bucket>` convention
        // used elsewhere (HostConnectErrorE2eTest, walkthrough captures)
        // so the artifact pipeline picks it
        // up via the normal externalMediaDirs / getExternalFilesDir
        // fallback chain.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val outDir = File(mediaRoot, "additional_test_output/issue300-folder-tree").apply {
            if (!exists()) mkdirs()
        }
        val file = File(outDir, name)
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}

private fun noopAssistantDictationViewModel(): InlineDictationViewModel =
    InlineDictationViewModel(
        audioRecorder = object : PromptComposerViewModel.MicCapture {
            override fun start() = Unit
            override fun stop(): ByteArray = ByteArray(0)
            override fun currentAmplitude(): Float = 0f
        },
        whisperClientFactory = WhisperClientFactory { null },
        apiKeyStorage = object : PromptComposerViewModel.ApiKeyVault {
            override fun save(key: CharArray) = Unit
            override fun load(): CharArray? = null
            override fun clear() = Unit
        },
        voiceSettings = object : PromptComposerViewModel.VoiceSettingsSnapshot {
            override fun silenceWindowMs(): Long = InlineDictationViewModel.SILENCE_WINDOW_MS
            override fun whisperLanguageHint(): String? = null
        },
    )

/**
 * Fake [FolderListGateway] that returns a static [rows] payload — the
 * connected E2E test exercises the Compose surface without paying for
 * a Docker SSH round-trip. The gateway counts how many times it has
 * been called so the test can wait for the initial probe to complete
 * before asserting on the rendered output.
 */
private class FakeFolderListGateway(
    private val rows: List<FolderSessionRow>,
    private val projectFoldersByRoot: Map<String, List<String>> = emptyMap(),
    private val resolvedWatchedRootPaths: Map<String, String> = emptyMap(),
    private val discoveredPorts: List<RemotePort> = emptyList(),
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
            resolvedWatchedRootPaths = resolvedWatchedRootPaths,
            discoveredPorts = discoveredPorts,
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
}
