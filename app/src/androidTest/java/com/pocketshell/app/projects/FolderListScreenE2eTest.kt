package com.pocketshell.app.projects

import android.graphics.Bitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
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
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.di.WhisperClientFactory
import com.pocketshell.app.portfwd.ForwardingController
import com.pocketshell.app.session.InlineDictationViewModel
import com.pocketshell.app.settings.HostDetailViewMode
import com.pocketshell.core.portfwd.RemotePort
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
        db.projectRootDao().insert(
            ProjectRootEntity(
                hostId = hostId,
                label = "[02] archive",
                path = "~/archive",
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
                "~/archive" to emptyList(),
            ),
            resolvedWatchedRootPaths = mapOf(
                "~/git" to "/home/u/git",
                "~/tmp" to "/home/u/tmp",
                "~/archive" to "/home/u/archive",
            ),
        )
        // The view model attaches a ProcessLifecycleOwner observer in init
        // (#430), which is main-thread-affine. Construct it on the main
        // thread so the lifecycle registry touch is legal under the
        // instrumentation thread (the @Test body runs off the main thread).
        val viewModel = constructFolderListViewModel(fakeGateway)
        val dictationViewModel = noopAssistantDictationViewModel()
        var openedSettings = false
        var openedWorkspaceSettings = false
        var openedPortForwarding = false
        var editedEnvPath: String? = null

        compose.setContent {
            PocketShellTheme {
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
                    onOpenSettings = { openedSettings = true },
                    onOpenWorkspaceSettings = { openedWorkspaceSettings = true },
                    onEditEnv = { path, _, _ -> editedEnvPath = path },
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
        // #522 item 2: the former Browse repos / Host assistant / Workspace
        // settings buttons collapsed into a single header `⋮` kebab. The actions
        // are reachable as menu items once the kebab is open, not as standalone
        // top-bar buttons.
        compose.onNodeWithTag(FOLDER_LIST_OVERFLOW_TAG).assertExists()
        compose.onNodeWithTag(folderTreeRootLabelTag("~/git"), useUnmergedTree = true).assertExists()
        // The action-bearing root header Rows (~/git, ~/tmp) wrap their label
        // in a semantics-merging `combinedClickable` (#455), so the label
        // `Text` is no longer an independently-keyed node in the merged tree.
        // Scroll to the still-independent root actions kebab button instead,
        // then assert the label exists on the unmerged tree. The OTHER root
        // has no actions (no `combinedClickable`, no kebab), so its label
        // stays an independent node and can be scrolled to directly.
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(folderTreeRootActionsTestTag("~/tmp")))
        compose.onNodeWithTag(folderTreeRootLabelTag("~/tmp"), useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(folderTreeRootLabelTag(FolderListViewModel.OTHER_ROOT_PATH)))
        compose.onNodeWithTag(folderTreeRootLabelTag(FolderListViewModel.OTHER_ROOT_PATH), useUnmergedTree = true).assertExists()
        val readyRoots = (viewModel.state.value as FolderListUiState.Ready).treeRoots
        assertEquals(
            listOf("git", "tmp", "archive", FolderListViewModel.OTHER_ROOT_LABEL),
            readyRoots.map { it.label },
        )
        assertEquals(
            listOf("~/git", "~/tmp", "~/archive", FolderListViewModel.OTHER_ROOT_PATH),
            readyRoots.map { it.path },
        )
        assertEquals(
            listOf("pocketshell", "llm-zoomcamp"),
            readyRoots[0].folders.map { it.label },
        )
        assertEquals(listOf("scratch", "notebooks"), readyRoots[1].folders.map { it.label })
        assertEquals(emptyList<String>(), readyRoots[2].folders.map { it.label })
        assertEquals(listOf("demo"), readyRoots[3].folders.map { it.label })
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(FOLDER_LIST_EMPTY_ROOT_HINT_TAG))
        compose.onNodeWithTag(FOLDER_LIST_EMPTY_ROOT_HINT_TAG).assertIsDisplayed()
        compose.onNodeWithText("No project folders found").assertIsDisplayed()
        compose.onNodeWithTag(folderTreeRootEmptyHintAddTestTag("~/archive"))
            .assertIsDisplayed()
            .assertHasClickAction()
        compose.onNodeWithText("+ Add").assertIsDisplayed()
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(folderRowTestTag("/home/u/git/pocketshell")))
        // #602: the host-detail Port forwarding entry point is intentionally
        // available even before the host has discovered/active ports. Keep the
        // empty-state copy quiet and avoid stale scanning/discovered-port text
        // once the workspace tree is ready.
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(FOLDER_LIST_PORT_FORWARDING_TAG))
        compose.onNodeWithTag(FOLDER_LIST_PORT_FORWARDING_TAG)
            .assertIsDisplayed()
            .assertHasClickAction()
        compose.onNodeWithText("Port forwarding").assertIsDisplayed()
        compose.onNodeWithText("Off").assertIsDisplayed()
        compose.onNodeWithText("Auto-forward is off by default.").assertIsDisplayed()
        compose.onAllNodesWithText("Checking remote ports.")
            .fetchSemanticsNodes()
            .also {
                assertTrue("ready empty forwarding summary should not keep scanning copy", it.isEmpty())
            }
        compose.onAllNodesWithText("Tap to view discovered ports and forward.")
            .fetchSemanticsNodes()
            .also {
                assertTrue("empty forwarding summary should not advertise discovered ports", it.isEmpty())
            }
        compose.onNodeWithTag(FOLDER_LIST_PORT_FORWARDING_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) { openedPortForwarding }
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(folderRowTestTag("/home/u/git/pocketshell")))
        compose.onAllNodesWithTag(FOLDER_LIST_VIEW_TOGGLE_TAG)
            .fetchSemanticsNodes()
            .also { assertTrue("Tree/Flat toggle should not render on host detail", it.isEmpty()) }
        // Open the header kebab, then tap the "Workspace settings" menu item
        // (#522 item 2). Tapping a menu item dismisses the menu.
        compose.onNodeWithTag(FOLDER_LIST_OVERFLOW_TAG).performClick()
        // #522 item 2 + #592 evidence: the single header kebab opens a menu
        // with the host actions plus separate global Settings and Workspace
        // settings entries.
        compose.onNodeWithTag(FOLDER_LIST_BROWSE_REPOS_TAG).assertExists()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_TAG).assertExists()
        compose.onNodeWithTag(FOLDER_LIST_SETTINGS_TAG).assertExists()
        android.os.SystemClock.sleep(200)
        WalkthroughScreenshotArtifacts.capture("issue522-header-kebab-open")
        compose.onNodeWithTag(FOLDER_LIST_SETTINGS_TAG)
            .assertExists()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) { openedSettings }
        assertEquals(false, openedWorkspaceSettings)
        // Re-open the menu and verify the original Workspace settings action
        // still routes separately from the global app settings action.
        compose.onNodeWithTag(FOLDER_LIST_OVERFLOW_TAG).performClick()
        compose.onNodeWithTag(FOLDER_LIST_WORKSPACE_SETTINGS_TAG)
            .assertExists()
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) { openedWorkspaceSettings }
        // Re-open the kebab and tap "Host assistant" to surface the assistant
        // panel.
        compose.onNodeWithTag(FOLDER_LIST_OVERFLOW_TAG).performClick()
        compose.onNodeWithContentDescription("Host assistant").assertExists()
        compose.onNodeWithTag(FOLDER_LIST_ASSISTANT_TAG)
            .assertExists()
            .performClick()
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
        // #478: the count subtitle is now an inline `· N sessions/agents`
        // (the active/idle facet moved to the leading status dot, mirroring
        // the maintainer's target mockup).
        compose.onNodeWithTag(folderCountPillTestTag("/home/u/git/pocketshell"), useUnmergedTree = true)
            .assertTextEquals("· 2 sessions · 1 agent")
        assertProjectNameKeepsReadableWidth("/home/u/git/pocketshell", "pocketshell")
        compose.onNodeWithTag(folderCountPillTestTag("/home/u/git/llm-zoomcamp"), useUnmergedTree = true)
            .assertTextEquals("· 1 agent")
        assertProjectNameKeepsReadableWidth("/home/u/git/llm-zoomcamp", "llm-zoomcamp")
        compose.onNodeWithTag(
            folderStatusDotTestTag("/home/u/git/pocketshell"),
            useUnmergedTree = true,
        ).assertExists()
        // #504: the per-group list/grid view toggle was removed (grid view is
        // not wanted). No toggle node should exist in the group header; the
        // tree (list) view is the only renderer.
        compose.onAllNodesWithTag("folder-list:tree-root:~/git:view-toggle", useUnmergedTree = true)
            .fetchSemanticsNodes().also {
                require(it.isEmpty()) { "list/grid view toggle should be removed (#504)" }
            }
        compose.onAllNodesWithTag("folder-list:tree-root:~/git:grid-stub", useUnmergedTree = true)
            .fetchSemanticsNodes().also {
                require(it.isEmpty()) { "grid view stub should be removed (#504)" }
            }
        // #504 evidence: the group header (title + `N projects · M sessions`
        // counts + kebab + `+`) renders with NO list/grid toggle between the
        // counts and the kebab. Full-device capture via uiAutomation is the
        // reliable artifact path on this AVD (captureToImage is best-effort
        // and can return blank), so this is the authoritative #504 screenshot.
        WalkthroughScreenshotArtifacts.capture("issue504-folder-tree-header-no-grid-toggle")
        captureViewport("issue504-folder-tree-header-no-grid-toggle-viewport.png")
        assertAccessibleTouchTarget(folderTreeRootActionsTestTag("~/git"))
        assertAccessibleTouchTarget(folderTreeRootCreateTestTag("~/git"))
        assertAccessibleTouchTarget(folderTreeRootActionsTestTag("~/tmp"))
        assertAccessibleTouchTarget(folderTreeRootCreateTestTag("~/tmp"))
        // #478: the per-project trailing cluster is now JUST the overflow
        // kebab — the inline `+` (new-session) button was dropped to match
        // the maintainer's mockup. Start-session moved into the kebab sheet
        // (asserted below). Confirm the kebab still meets its touch target
        // and the inline `+` node no longer exists for a visible project.
        assertAccessibleTouchTarget(folderDetailActionsTestTag("/home/u/git/pocketshell"))
        compose.onAllNodesWithTag(
            folderDetailCreateTestTag("/home/u/git/pocketshell"),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().also {
            assertTrue(
                "#478: the per-project inline `+` button must be gone (start-session moved to the kebab)",
                it.isEmpty(),
            )
        }
        compose.onNodeWithText("+ New", useUnmergedTree = true).assertDoesNotExist()

        // #478 evidence: compact tree with the single overflow kebab (no
        // inline `+`), full folder names visible, more rows per screen.
        captureViewport("issue478b-folder-tree-kebab-only-viewport.png")

        // #478: opening the per-folder overflow kebab surfaces both the
        // "+ New session here" row (the start-session action that the inline
        // `+` used to provide) and the "Env files" row. Tapping New session
        // routes to the SessionTypePickerSheet pre-filled with this folder,
        // proving start-session is still reachable via the kebab.
        compose.onNodeWithTag(folderDetailActionsTestTag("/home/u/git/pocketshell")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(FOLDER_CONTEXT_NEW_SESSION_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        captureFullDevice("issue478b-overflow-sheet-with-new-session.png")
        compose.onNodeWithTag(FOLDER_CONTEXT_NEW_SESSION_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(SESSION_TYPE_PICKER_SHELL_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        // The picker is scoped to the tapped project's folder.
        compose.onNodeWithText("in pocketshell").assertExists()
        compose.onNodeWithTag(SESSION_TYPE_PICKER_CANCEL_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(SESSION_TYPE_PICKER_SHELL_TAG).fetchSemanticsNodes().isEmpty()
        }

        // The kebab sheet also still folds in the "Env files" row (#455).
        // Tapping it routes to the env editor (no-op onEditEnv in this test)
        // and dismisses the sheet, leaving the tree untouched for the
        // remaining assertions.
        compose.onNodeWithTag(folderDetailActionsTestTag("/home/u/git/pocketshell")).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(FOLDER_CONTEXT_ENV_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        captureFullDevice("issue478b-overflow-sheet-with-env.png")
        compose.onNodeWithTag(FOLDER_CONTEXT_ENV_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(FOLDER_CONTEXT_ENV_TAG).fetchSemanticsNodes().isEmpty()
        }
        assertEquals("/home/u/git/pocketshell", editedEnvPath)

        // --- Assertion 3: issue #471 — folders with ≥1 active session
        //    auto-expand on first ready, so the running sessions are visible
        //    at a glance WITHOUT a tap. The pocketshell project (active
        //    Claude + idle shell) starts expanded, revealing agent sessions
        //    before idle shell sessions and keeping the raw tmux name as
        //    fallback text.
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
        // #522 item 3: tree session rows lead with the terminal tile glyph too,
        // alongside the status dot, for consistency with the flat rows.
        compose.onNodeWithTag(
            folderSessionTileTestTag("/home/u/git/pocketshell", "claude-main"),
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

        // Agent / shell badge labels visible on the expanded project. #478
        // replaced the inline "Claude · Idle" rollup with a compact right-
        // aligned badge pill carrying just the agent/shell identity (the mockup
        // shows no activity word on the row), so match the short badge label.
        // #471: with active folders auto-expanded, more than one "Claude" badge
        // can render (pocketshell + the outside-lab demo), so assert presence
        // rather than a single node; the precise per-session badge tags below
        // pin the pocketshell claude badge specifically.
        assertTrue(
            "a Claude agent badge should render",
            compose.onAllNodesWithText("Claude", substring = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )

        // #478: each expanded session row carries a right-aligned agent-type
        // badge pill. The pocketshell project has a Claude agent + a Shell
        // session, so both a purple agent badge and a neutral Shell badge are
        // present.
        compose.onNodeWithTag(
            folderSessionBadgeTestTag("/home/u/git/pocketshell", "claude-main"),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag(
            folderSessionBadgeTestTag("/home/u/git/pocketshell", "build-shell"),
            useUnmergedTree = true,
        ).assertExists()
        // The agent badge renders "Claude" and the shell badge renders "Shell".
        // #471: auto-expand can surface more than one Claude badge, so assert
        // presence on the unmerged tree rather than a single node.
        assertTrue(
            "a Claude badge should render on the unmerged tree",
            compose.onAllNodesWithText("Claude", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )
        assertTrue(
            "a Shell badge should render on the unmerged tree",
            compose.onAllNodesWithText("Shell", useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty(),
        )

        // #503: every expanded session child hangs off a `├─/└─` tree connector
        // cell. Both children of the expanded pocketshell project carry a
        // connector node.
        compose.onNodeWithTag(
            folderSessionConnectorTestTag("/home/u/git/pocketshell", "claude-main"),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag(
            folderSessionConnectorTestTag("/home/u/git/pocketshell", "build-shell"),
            useUnmergedTree = true,
        ).assertExists()
        // #471: llm-zoomcamp also has an active session (codex-llm), so it
        // auto-expands on first ready too and renders its connector.
        compose.onNodeWithTag(
            folderSessionConnectorTestTag("/home/u/git/llm-zoomcamp", "codex-llm"),
            useUnmergedTree = true,
        ).assertExists()
        // #471 collapse stickiness at the screen level: the user collapses
        // llm-zoomcamp; it must stay collapsed (no connector) — auto-expand
        // does not spring it back open.
        compose.onNodeWithTag(
            folderDetailDisclosureTestTag("/home/u/git/llm-zoomcamp"),
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            (viewModel.state.value as? FolderListUiState.Ready)
                ?.expandedProjectPaths
                ?.contains("/home/u/git/llm-zoomcamp") == false
        }
        compose.onAllNodesWithTag(
            folderSessionConnectorTestTag("/home/u/git/llm-zoomcamp", "codex-llm"),
            useUnmergedTree = true,
        ).fetchSemanticsNodes().also {
            assertTrue("user-collapsed project must not render a tree connector (#503/#471)", it.isEmpty())
        }
        // Re-expand so the remaining assertions/captures see the original
        // auto-expanded tree state.
        compose.onNodeWithTag(
            folderDetailDisclosureTestTag("/home/u/git/llm-zoomcamp"),
            useUnmergedTree = true,
        ).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            (viewModel.state.value as? FolderListUiState.Ready)
                ?.expandedProjectPaths
                ?.contains("/home/u/git/llm-zoomcamp") == true
        }
        // The spine geometry is anchored to the child block: the first child's
        // connector top aligns with the row block start and the connector cell's
        // left edge sits at the same x for every child (one continuous spine, no
        // per-row x drift). Assert the two children's connector cells share a
        // left edge.
        val claudeConnectorBounds = compose.onNodeWithTag(
            folderSessionConnectorTestTag("/home/u/git/pocketshell", "claude-main"),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val shellConnectorBounds = compose.onNodeWithTag(
            folderSessionConnectorTestTag("/home/u/git/pocketshell", "build-shell"),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        assertEquals(
            "tree connector cells must share a left edge so the spine is one continuous line (#503)",
            claudeConnectorBounds.left,
            shellConnectorBounds.left,
            0.5f,
        )
        // And the connector cells abut vertically with no gap (the child column
        // carries zero inter-row spacing) so adjacent spine segments are
        // continuous, not seamed (#503).
        assertTrue(
            "adjacent tree connector cells must abut so the spine has no seam (#503): " +
                "claude.bottom=${claudeConnectorBounds.bottom}px shell.top=${shellConnectorBounds.top}px",
            kotlin.math.abs(claudeConnectorBounds.bottom - shellConnectorBounds.top) <= 1.0f,
        )
        assertHostDetailTreeGutterIsCompact(
            rootPath = "~/git",
            folderPath = "/home/u/git/pocketshell",
            sessionName = "claude-main",
        )
        // The session row keeps its 48 dp touch floor even at compact density.
        assertAccessibleTouchTarget(
            folderDetailRowTestTag("/home/u/git/pocketshell", "claude-main"),
            minDp = 48f,
        )

        // --- Capture viewport before opening any picker/sheet (artifact
        //    path matches the project's `*-viewport.png` convention so
        //    the reviewer's artifact-driven check picks it up).
        captureViewport("issue300-folder-tree-rendered-viewport.png")
        // #478: the same expanded tree captured under a #478-named artifact so
        // the reviewer can diff connectors + badges + status dots against the
        // maintainer's target mockup. The full-device capture is the
        // authoritative one here (the viewport node capture is best-effort and
        // can flake when the FAB overlay overlaps the screen node).
        captureViewport("issue478-folder-tree-target-viewport.png")
        android.os.SystemClock.sleep(200)
        captureFullDevice("issue478-folder-tree-target-fulldevice.png")

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
        compose.onNodeWithTag(rootProjectCandidateTestTag("/home/u/git/pocketshell")).assertDoesNotExist()
        compose.onNodeWithTag(rootProjectCandidateTestTag("/home/u/git/llm-zoomcamp")).assertDoesNotExist()
        compose.onNodeWithTag(rootProjectCandidateTestTag("/home/u/git/empty-pinned")).assertExists()
        // Scanned candidates carry no inline source badge after the row
        // compaction (only "Recent" history candidates are badged), so the
        // source slot exists but has no label text.
        compose.onNodeWithTag(
            rootProjectCandidateSourceTestTag("/home/u/git/empty-pinned"),
            useUnmergedTree = true,
        ).assertExists()
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

    /**
     * Flat-view regression test (#485). The #478 tree redesign reworked only
     * the tree render path and left the [HostDetailViewMode.Flat] path rendering
     * a folder-grouped list (or nothing). This locks the fixed behaviour:
     *
     *  - Flat view renders a clean, ungrouped list of EVERY session on the host
     *    using the shared `ListRow` rows (`folder-list:flat-row:<name>`), with no
     *    folder headers / tree roots / tree connectors.
     *  - Each row carries a status dot + agent-type badge (purple for agents,
     *    grey for shell) so it reads consistently with a tree session row (#479).
     *  - Tapping a flat row fires `onOpenSession` with the session's cwd.
     *  - Toggling tree↔flat works both ways.
     */
    @Test
    fun flatViewRendersUngroupedSessionRowsAndTapsThrough() {
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
            ),
            projectFoldersByRoot = mapOf(
                "~/git" to listOf(
                    "/home/u/git/pocketshell",
                    "/home/u/git/llm-zoomcamp",
                ),
            ),
            resolvedWatchedRootPaths = mapOf("~/git" to "/home/u/git"),
        )
        val viewModel = constructFolderListViewModel(fakeGateway)
        var mode by mutableStateOf(HostDetailViewMode.Tree)
        var openedSession: String? = null
        var openedDirectory: String? = null

        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "issue485-host",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue485",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { name, dir ->
                        openedSession = name
                        openedDirectory = dir
                    },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    hostDetailViewMode = mode,
                )
            }
        }

        // Tree mode first: the workspace tree root renders, the flat rows do not.
        compose.waitUntil(timeoutMillis = 10_000) {
            fakeGateway.callCount.get() >= 1 &&
                compose.onAllNodesWithTag(folderTreeRootTestTag("~/git"))
                    .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            "flat rows must not render in tree mode",
            compose.onAllNodesWithTag(folderListFlatRowTestTag("claude-main"))
                .fetchSemanticsNodes().isEmpty(),
        )

        // Switch to flat view.
        compose.runOnIdle { mode = HostDetailViewMode.Flat }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(folderListFlatRowTestTag("claude-main"))
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Every session renders as an ungrouped flat row — no folder grouping.
        compose.onNodeWithTag(folderListFlatRowTestTag("claude-main")).assertIsDisplayed()
        compose.onNodeWithTag(folderListFlatRowTestTag("build-shell")).assertIsDisplayed()
        compose.onNodeWithTag(folderListFlatRowTestTag("codex-llm")).assertIsDisplayed()
        compose.onNodeWithText("claude-main").assertIsDisplayed()
        compose.onNodeWithText("build-shell").assertIsDisplayed()
        compose.onNodeWithText("codex-llm").assertIsDisplayed()

        // No tree roots / folder headers / tree session rows in flat mode.
        assertTrue(
            compose.onAllNodesWithTag(folderTreeRootTestTag("~/git"))
                .fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            compose.onAllNodesWithTag(
                folderHeaderLabelTag("/home/u/git/pocketshell"),
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isEmpty(),
        )

        // Each row carries the shared status dot + agent-type badge so it reads
        // consistently with a tree session row (#479). Agent rows get the agent
        // badge; the shell row gets a "Shell" badge.
        compose.onNodeWithTag(
            folderListFlatRowStatusDotTestTag("claude-main"),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag(
            folderListFlatRowBadgeTestTag("claude-main"),
            useUnmergedTree = true,
        ).assertTextEquals("Claude")
        compose.onNodeWithTag(
            folderListFlatRowBadgeTestTag("codex-llm"),
            useUnmergedTree = true,
        ).assertTextEquals("Codex")
        compose.onNodeWithTag(
            folderListFlatRowBadgeTestTag("build-shell"),
            useUnmergedTree = true,
        ).assertTextEquals("Shell")

        // #489: sessions group into ACTIVE / IDLE sections (SectionHeaders with
        // counts) and a host header carries the `N active · M idle · K sessions`
        // split. claude-main (attached Claude) + codex-llm (attached Codex) are
        // active; build-shell (detached shell) is idle.
        compose.onNodeWithTag(FOLDER_LIST_FLAT_HEADER_TAG).assertIsDisplayed()
        compose.onNodeWithTag(
            FOLDER_LIST_FLAT_HEADER_COUNTS_TAG,
            useUnmergedTree = true,
        ).assertTextEquals("2 active · 1 idle · 3 sessions")
        compose.onNodeWithTag(FOLDER_LIST_FLAT_ACTIVE_SECTION_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FOLDER_LIST_FLAT_IDLE_SECTION_TAG).assertIsDisplayed()
        // Section labels render title-case with an inline `· N` count
        // (#479 §4 decision 5 — shared SectionHeader contract).
        compose.onNodeWithText("Active").assertIsDisplayed()
        compose.onNodeWithText("Idle").assertIsDisplayed()
        // #522 item 3: each flat session row leads with a terminal tile glyph
        // alongside the status dot.
        compose.onNodeWithTag(
            folderListFlatRowTileTestTag("claude-main"),
            useUnmergedTree = true,
        ).assertExists()
        compose.onNodeWithTag(
            folderListFlatRowStatusDotTestTag("claude-main"),
            useUnmergedTree = true,
        ).assertExists()
        // #522 evidence: header (single name + count subtitle + kebab) and the
        // flat rows (status dot + terminal tile glyph) captured before the tap.
        WalkthroughScreenshotArtifacts.capture("issue522-flat-header-and-rows")
        captureViewport("issue522-flat-header-and-rows-viewport.png")
        // The folder context now lives in the row subtitle (the directory label
        // the tree grouping otherwise carries).
        compose.onAllNodesWithText("pocketshell")
            .fetchSemanticsNodes().isNotEmpty().let { found ->
                assertTrue("flat row subtitle should show the folder label", found)
            }

        // The flat row is the tap target and routes to onOpenSession with cwd.
        compose.onNodeWithTag(folderListFlatRowTestTag("codex-llm")).assertHasClickAction()
        compose.onNodeWithTag(folderListFlatRowTestTag("codex-llm")).performClick()
        compose.runOnIdle {
            assertEquals("codex-llm", openedSession)
            assertEquals("/home/u/git/llm-zoomcamp", openedDirectory)
        }

        captureViewport("issue489-flat-view-grouped-viewport.png")
        android.os.SystemClock.sleep(200)
        captureFullDevice("issue489-flat-view-grouped-fulldevice.png")

        // Toggling back to tree restores the tree (both directions work).
        compose.runOnIdle { mode = HostDetailViewMode.Tree }
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(folderTreeRootTestTag("~/git"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(
            compose.onAllNodesWithTag(folderListFlatRowTestTag("claude-main"))
                .fetchSemanticsNodes().isEmpty(),
        )
    }

    /**
     * Flat-view empty state (#485): a host with zero sessions shows the
     * dedicated [FOLDER_LIST_FLAT_EMPTY_TAG] panel rather than rendering blank.
     */
    @Test
    fun flatViewShowsEmptyStateWhenNoSessions() {
        val fakeGateway = FakeFolderListGateway(rows = emptyList())
        val viewModel = constructFolderListViewModel(fakeGateway)

        compose.setContent {
            PocketShellTheme {
                FolderListScreen(
                    hostId = hostId,
                    hostName = "issue485-empty-host",
                    hostname = "h.example",
                    port = 22,
                    username = "u",
                    keyPath = "/tmp/issue485",
                    passphrase = null,
                    onBack = {},
                    onOpenSession = { _, _ -> },
                    onSessionCreated = { _, _ -> },
                    onBrowseRepos = { _ -> },
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    hostDetailViewMode = HostDetailViewMode.Flat,
                )
            }
        }

        compose.waitUntil(timeoutMillis = 10_000) {
            fakeGateway.callCount.get() >= 1 &&
                compose.onAllNodesWithTag(FOLDER_LIST_FLAT_EMPTY_TAG)
                    .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(FOLDER_LIST_FLAT_EMPTY_TAG).assertIsDisplayed()
        assertTrue(
            "no flat rows when the host has zero sessions",
            compose.onAllNodesWithText("session").fetchSemanticsNodes().isEmpty(),
        )
        captureViewport("issue485-flat-view-empty-viewport.png")
        android.os.SystemClock.sleep(200)
        captureFullDevice("issue485-flat-view-empty-fulldevice.png")
    }

    @Test
    fun discoveredPortForwardingSummaryStaysBelowWorkspaceTree() {
        val fakeGateway = FakeFolderListGateway(
            rows = emptyList(),
            discoveredPorts = listOf(RemotePort(port = 3000, processName = "node")),
        )
        val viewModel = constructFolderListViewModel(fakeGateway)
        var openedPortForwarding = false

        compose.setContent {
            PocketShellTheme {
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

    @Test
    fun longRootHeaderLeavesRoomForTrailingActions() {
        runBlocking {
            val longRootPath = "~/very-long-root-name-that-must-not-collide-with-actions"
            db.projectRootDao().insert(
                ProjectRootEntity(
                    hostId = hostId,
                    label = "very-long-root-name-that-must-not-collide-with-actions",
                    path = longRootPath,
                ),
            )
            val fakeGateway = FakeFolderListGateway(
                rows = emptyList(),
                projectFoldersByRoot = mapOf(longRootPath to listOf("/home/u/very-long-root-name/project-alpha")),
                resolvedWatchedRootPaths = mapOf(longRootPath to "/home/u/very-long-root-name"),
            )
            val viewModel = constructFolderListViewModel(fakeGateway)

            compose.setContent {
                PocketShellTheme {
                    FolderListScreen(
                        hostId = hostId,
                        hostName = "issue603-host",
                        hostname = "h.example",
                        port = 22,
                        username = "u",
                        keyPath = "/tmp/issue603",
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
                fakeGateway.callCount.get() >= 1 &&
                    compose.onAllNodesWithTag(folderTreeRootActionsTestTag(longRootPath))
                        .fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
                .performScrollToNode(hasTestTag(folderTreeRootActionsTestTag(longRootPath)))
            assertRootHeaderLeavesRoomForActions(longRootPath)
            assertAccessibleTouchTarget(folderTreeRootActionsTestTag(longRootPath))
            assertAccessibleTouchTarget(folderTreeRootCreateTestTag(longRootPath))
            compose.onNodeWithTag(folderTreeRootEmptyHintAddTestTag(longRootPath))
                .assertIsDisplayed()
                .assertHasClickAction()
        }
    }

    private fun assertProjectNameKeepsReadableWidth(
        folderPath: String,
        expectedName: String,
    ) {
        val labelNode = compose.onNodeWithTag(
            folderHeaderLabelTag(folderPath),
            useUnmergedTree = true,
        )
        val labelBounds = labelNode.fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag(
            folderCountPillTestTag(folderPath),
            useUnmergedTree = true,
        ).assertExists()

        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density

        // Part 1 (column width): the available name column — the gap from the
        // label's left edge to the start of the overflow/`+` action cluster —
        // must stay wide enough that long names have room. #455 widened this by
        // folding secondary actions behind the kebab and shrinking `+` to 36dp;
        // #478 added the ~8dp leading status dot, dropping the floor 240→232dp.
        val actionsBounds = compose.onNodeWithTag(
            folderDetailActionsTestTag(folderPath),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val availableNameWidth = actionsBounds.left - labelBounds.left
        val minColumnPx = 232f * density
        assertTrue(
            "project name column for $folderPath should keep readable width " +
                "(>= 232dp = ${minColumnPx}px): ${availableNameWidth}px " +
                "(label.left=${labelBounds.left}px, actions.left=${actionsBounds.left}px)",
            availableNameWidth >= minColumnPx,
        )

        // Part 2 (no truncation): the column being wide is necessary but not
        // sufficient — a competing trailing `weight(1f)` spacer used to clamp
        // the name `Text` to ~half the available column, so it ellipsised to
        // `poc…` even with empty space to its right. The width-only invariant
        // above passed through that bug. Catch it directly: the label `Text`
        // node's own rendered width must be at least the width the full string
        // needs at the same font size, so an ellipsised label fails this.
        val labelWidthPx = labelBounds.width
        val expectedTextPx = measureTextWidthPx(expectedName, fontSizeSp = 15f, density = density)
        // Small tolerance for measurement differences between the platform
        // Paint estimate and Compose's text layout (kerning, sub-pixel).
        val tolerancePx = 6f * density
        assertTrue(
            "project name `$expectedName` for $folderPath must render in full " +
                "without truncation: label Text width ${labelWidthPx}px is narrower " +
                "than the full string needs (${expectedTextPx}px, tol ${tolerancePx}px). " +
                "A regression to the competing trailing weight(1f) spacer clamps the " +
                "name and ellipsises it even though the column has room.",
            labelWidthPx >= expectedTextPx - tolerancePx,
        )

        // And assert the full text is queryable on the label node (no ellipsis
        // substitution in the rendered text), as a belt-and-braces semantic
        // check that pairs with the geometric one above.
        labelNode.assertTextEquals(expectedName)
    }

    private fun assertRootHeaderLeavesRoomForActions(rootPath: String) {
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val labelBounds = compose.onNodeWithTag(
            folderTreeRootLabelTag(rootPath),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val countBounds = compose.onNodeWithTag(
            folderTreeRootCountTag(rootPath),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val actionsBounds = compose.onNodeWithTag(
            folderTreeRootActionsTestTag(rootPath),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val minGapPx = 4f * density

        assertTrue(
            "long root title must end before trailing actions: " +
                "label.right=${labelBounds.right}px actions.left=${actionsBounds.left}px",
            labelBounds.right <= actionsBounds.left - minGapPx,
        )
        assertTrue(
            "long root count must end before trailing actions: " +
                "count.right=${countBounds.right}px actions.left=${actionsBounds.left}px",
            countBounds.right <= actionsBounds.left - minGapPx,
        )
        assertTrue(
            "long root title should keep a readable column before ellipsizing: width=${labelBounds.width}px",
            labelBounds.width >= 160f * density,
        )
    }

    private fun measureTextWidthPx(text: String, fontSizeSp: Float, density: Float): Float {
        // Regular weight deliberately: it slightly UNDER-estimates the SemiBold
        // render width, giving the assertion margin so a genuinely-full label
        // always clears the bar. A truncated `poc…` label (clamped to roughly
        // half the column) still falls far below this for the full string, so
        // the regression is caught. We only need a conservative lower bound on
        // the full-string width, not a pixel-exact match.
        val paint = android.graphics.Paint().apply {
            textSize = fontSizeSp * density
        }
        return paint.measureText(text)
    }

    private fun assertHostDetailTreeGutterIsCompact(
        rootPath: String,
        folderPath: String,
        sessionName: String,
    ) {
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val rootLabelBounds = compose.onNodeWithTag(
            folderTreeRootLabelTag(rootPath),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val projectBounds = compose.onNodeWithTag(
            folderHeaderClickTestTag(folderPath),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val connectorBounds = compose.onNodeWithTag(
            folderSessionConnectorTestTag(folderPath, sessionName),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot
        val sessionBounds = compose.onNodeWithTag(
            folderDetailRowTestTag(folderPath, sessionName),
            useUnmergedTree = true,
        ).fetchSemanticsNode().boundsInRoot

        val projectIndentDp = (projectBounds.left - rootLabelBounds.left) / density
        assertTrue(
            "host-detail project row should start within an 8dp compact gutter (#565): " +
                "rootLabel.left=${rootLabelBounds.left}px project.left=${projectBounds.left}px",
            projectIndentDp <= 8.5f,
        )

        val connectorIndentDp = (connectorBounds.left - projectBounds.left) / density
        assertTrue(
            "session connector should stay close to the project row, not waste left gutter (#565): " +
                "project.left=${projectBounds.left}px connector.left=${connectorBounds.left}px",
            connectorIndentDp <= 8.5f,
        )

        val connectorWidthDp = connectorBounds.width / density
        assertTrue(
            "session connector cell should be 16dp or tighter (#565): width=${connectorBounds.width}px",
            connectorWidthDp <= 16.5f,
        )

        val sessionIndentDp = (sessionBounds.left - projectBounds.left) / density
        assertTrue(
            "session row content should sit within a 24dp child gutter from the project row (#565): " +
                "project.left=${projectBounds.left}px session.left=${sessionBounds.left}px",
            sessionIndentDp <= 24.5f,
        )
    }

    // #455: the compact tree icon buttons (overflow kebab + accent `+`)
    // shrank to a 36dp hit box so the folder name keeps its width. 36dp is
    // the design-system minimum for tree icon buttons (§4/§6.1); secondary
    // actions live in the long-press / overflow sheet, not as 48dp pills.
    private fun assertAccessibleTouchTarget(tag: String, minDp: Float = 36f) {
        compose.onNodeWithTag(FOLDER_LIST_CONTENT_TAG)
            .performScrollToNode(hasTestTag(tag))
        val node = compose.onNodeWithTag(tag, useUnmergedTree = true)
            .assertExists()
            .assertHasClickAction()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        val density = InstrumentationRegistry.getInstrumentation()
            .targetContext.resources.displayMetrics.density
        val minPx = minDp * density
        assertTrue(
            "control $tag should expose at least a ${minDp}dp tap target: ${bounds.width}x${bounds.height}",
            bounds.width >= minPx && bounds.height >= minPx && bounds.width >= bounds.height * 0.85f,
        )
    }

    /**
     * Issue #465: reopening a stale session and going back landed the
     * host-detail screen on an unrecoverable `open failed` state — only the
     * error message + a Retry that never recovered, forcing a force-close.
     *
     * This reproduces that journey at the screen level: the first probe fails
     * with an "open failed" [FolderListResult.ConnectFailed], so the screen
     * shows the error panel + Retry (captured BEFORE). Tapping Retry must
     * re-run the probe and reload the folder tree (captured AFTER) — never a
     * blank dead-end. The app-bar Back stays present throughout so the screen
     * is always navigable.
     */
    @Test
    fun openFailedErrorRecoversOnRetry() {
        val recoveredRows = listOf(
            FolderSessionRow(
                sessionName = "claude-main",
                lastActivity = 1_700_004_000L,
                attached = true,
                cwd = "/home/u/git/pocketshell",
                agentKind = SessionAgentKind.Claude,
            ),
        )
        val fakeGateway = OpenFailedThenRecoveringGateway(
            recoveredRows = recoveredRows,
            projectFoldersByRoot = mapOf("~/git" to listOf("/home/u/git/pocketshell")),
            resolvedWatchedRootPaths = mapOf("~/git" to "/home/u/git"),
        )
        val viewModel = constructFolderListViewModel(fakeGateway)
        val dictationViewModel = noopAssistantDictationViewModel()

        compose.setContent {
            PocketShellTheme {
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
                    onOpenWorkspaceSettings = {},
                    onEditEnv = { _, _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                    assistantDictationViewModel = dictationViewModel,
                )
            }
        }

        // BEFORE: the open-failed probe drives the error panel + Retry.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(FOLDER_LIST_ERROR_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(FOLDER_LIST_ERROR_TAG).assertExists()
        compose.onNodeWithTag(FOLDER_LIST_RETRY_TAG).assertExists().assertHasClickAction()
        // The screen must remain navigable in the error state: Back is present.
        compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG).assertExists()
        captureFullDevice("issue465-open-failed-before.png")
        captureViewport("issue465-open-failed-before-viewport.png")

        // RETRY: re-run the probe; the host is reachable now.
        compose.onNodeWithTag(FOLDER_LIST_RETRY_TAG).performClick()

        // AFTER: the folder tree reloads — no blank dead-end.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(folderTreeRootTestTag("~/git"))
                .fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(FOLDER_LIST_ERROR_TAG).assertDoesNotExist()
        compose.onNodeWithTag(folderTreeRootTestTag("~/git")).assertExists()
        captureFullDevice("issue465-open-failed-after-retry.png")
        captureViewport("issue465-open-failed-after-retry-viewport.png")

        assertTrue(
            "Retry must have re-run the probe at least twice (fail then recover)",
            fakeGateway.callCount.get() >= 2,
        )
    }

    private fun constructFolderListViewModel(
        gateway: FolderListGateway,
    ): FolderListViewModel {
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

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val bitmap: Bitmap = try {
            instrumentation.uiAutomation.takeScreenshot() ?: return
        } catch (t: Throwable) {
            return
        }
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
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
        // so the artifact pipeline picks it up via the normal app-specific
        // external-files fallback chain.
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val outDir = File(mediaRoot, "additional_test_output/issue300-folder-tree").apply {
            if (!exists()) mkdirs()
        }
        val file = File(outDir, name)
        FileOutputStream(file).use { stream ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        }
    }
}

/**
 * Issue #465: gateway whose FIRST probe fails with an "open failed"
 * [FolderListResult.ConnectFailed] (the dead-end the maintainer hit) and whose
 * subsequent probes succeed — so the screen-level Retry recovery can be
 * exercised deterministically.
 */
private class OpenFailedThenRecoveringGateway(
    private val recoveredRows: List<FolderSessionRow>,
    private val projectFoldersByRoot: Map<String, List<String>> = emptyMap(),
    private val resolvedWatchedRootPaths: Map<String, String> = emptyMap(),
) : FolderListGateway {

    val callCount: AtomicInteger = AtomicInteger(0)

    override suspend fun listSessionsWithFolder(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        watchedRoots: List<ProjectRootEntity>,
    ): FolderListResult {
        val call = callCount.incrementAndGet()
        return if (call == 1) {
            FolderListResult.ConnectFailed(java.io.IOException("open failed"))
        } else {
            FolderListResult.Sessions(
                rows = recoveredRows,
                projectFoldersByRoot = projectFoldersByRoot,
                resolvedWatchedRootPaths = resolvedWatchedRootPaths,
            )
        }
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

    override suspend fun killSession(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
        sessionName: String,
    ): Result<Unit> = Result.success(Unit)
}
