package com.pocketshell.app.tmux

import com.pocketshell.app.di.CommandPlannerClientFactory
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import com.pocketshell.core.tmux.TmuxClientFactory
import com.pocketshell.core.voice.CommandPlan
import com.pocketshell.core.voice.CommandPlannerClient
import com.pocketshell.core.voice.CommandPlannerException
import com.pocketshell.core.voice.CommandPlannerRequest
import com.pocketshell.core.voice.PlannedCommand
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Voice / command-planner unit tests for [TmuxSessionViewModel] — mirrors the
 * [com.pocketshell.app.session.SessionViewModelTest] voice block so the tmux
 * route (host tap → tmux picker → "Attach to session") gets the same review-
 * before-execute coverage as the raw-SSH path (issue #123).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionViewModelVoiceTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun newVm(
        planner: CommandPlannerClient? = null,
        projectRootDao: ProjectRootDao? = null,
    ): TmuxSessionViewModel = TmuxSessionViewModel(
        tmuxClientFactory = TmuxClientFactory(factoryScope),
        activeTmuxClients = ActiveTmuxClients(),
        commandPlannerClientFactory = CommandPlannerClientFactory { planner },
        projectRootDao = projectRootDao,
    )

    @After
    fun tearDown() {
        factoryScope.cancel()
    }

    @Test
    fun voiceCommandReviewStateStartsEmpty() {
        val vm = newVm()
        val state = vm.voiceCommandReview.value
        assertFalse(state.isPlanning)
        assertNull(state.pendingPlan)
        assertNull(state.error)
        assertNull(state.transcript)
    }

    @Test
    fun planVoiceCommandSurfacesPendingReviewOnSuccess() = runTest {
        val planner = FakeCommandPlannerClient(
            result = Result.success(CommandPlan(listOf(PlannedCommand("git status --short")))),
        )
        val vm = newVm(planner = planner)

        vm.planVoiceCommand("  show git status  ")
        advanceUntilIdle()

        assertEquals(1, planner.requests.size)
        assertEquals("show git status", planner.requests.single().transcript)
        assertTrue(planner.requests.single().safety.requireReviewBeforeExecution)
        assertFalse(planner.requests.single().safety.allowAutoSend)

        val state = vm.voiceCommandReview.value
        assertFalse(state.isPlanning)
        assertNull(state.error)
        assertNotNull(state.pendingPlan)
        assertEquals("git status --short", state.pendingPlan!!.commands.single().command)
    }

    @Test
    fun planVoiceCommandSurfacesErrorOnPlannerFailure() = runTest {
        val planner = FakeCommandPlannerClient(
            result = Result.failure(CommandPlannerException.Rejected("unsafe command")),
        )
        val vm = newVm(planner = planner)

        vm.planVoiceCommand("delete everything")
        advanceUntilIdle()

        val state = vm.voiceCommandReview.value
        assertNull(state.pendingPlan)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("rejected", ignoreCase = true))
        assertTrue(state.error!!.contains("unsafe command"))
    }

    @Test
    fun planVoiceCommandRejectsBlankTranscriptWithoutHittingPlanner() = runTest {
        val planner = FakeCommandPlannerClient(
            result = Result.success(CommandPlan(emptyList())),
        )
        val vm = newVm(planner = planner)

        vm.planVoiceCommand("   ")
        advanceUntilIdle()

        assertTrue(planner.requests.isEmpty())
        val state = vm.voiceCommandReview.value
        assertNull(state.pendingPlan)
        assertNull(state.error)
    }

    @Test
    fun planVoiceCommandWithoutApiKeySurfacesActionableError() = runTest {
        val vm = newVm(planner = null)

        vm.planVoiceCommand("show git status")
        advanceUntilIdle()

        val state = vm.voiceCommandReview.value
        assertNull(state.pendingPlan)
        assertNotNull(state.error)
        assertTrue(state.error!!.contains("API key", ignoreCase = true))
    }

    @Test
    fun dismissVoiceCommandReviewClearsState() = runTest {
        val planner = FakeCommandPlannerClient(
            result = Result.success(CommandPlan(listOf(PlannedCommand("git status")))),
        )
        val vm = newVm(planner = planner)

        vm.planVoiceCommand("show git status")
        advanceUntilIdle()
        assertNotNull(vm.voiceCommandReview.value.pendingPlan)

        vm.dismissVoiceCommandReview()
        val cleared = vm.voiceCommandReview.value
        assertNull(cleared.pendingPlan)
        assertNull(cleared.error)
        assertNull(cleared.transcript)
        assertFalse(cleared.isPlanning)
    }

    @Test
    fun approvePendingVoiceCommandClearsPlanEvenWhenPaneNotConnected() = runTest {
        // Without a live tmux client the bridgeScope.launch in
        // writeInputToPane is a no-op (clientRef == null), so we cannot
        // assert against a captured stdin here — that path is exercised by
        // the connected `TmuxClientIntegrationTest`. We can still verify
        // the planner state is cleared synchronously and the API contract
        // is honored for blank pane ids.
        val planner = FakeCommandPlannerClient(
            result = Result.success(CommandPlan(listOf(PlannedCommand("git status")))),
        )
        val vm = newVm(planner = planner)

        vm.planVoiceCommand("show git status")
        advanceUntilIdle()
        assertNotNull(vm.voiceCommandReview.value.pendingPlan)

        vm.approvePendingVoiceCommand(paneId = "%0", withEnter = true)

        val state = vm.voiceCommandReview.value
        assertNull(state.pendingPlan)
        assertNull(state.error)
    }

    @Test
    fun approvePendingVoiceCommandIgnoresBlankPaneId() = runTest {
        val planner = FakeCommandPlannerClient(
            result = Result.success(CommandPlan(listOf(PlannedCommand("git status")))),
        )
        val vm = newVm(planner = planner)

        vm.planVoiceCommand("show git status")
        advanceUntilIdle()
        assertNotNull(vm.voiceCommandReview.value.pendingPlan)

        vm.approvePendingVoiceCommand(paneId = "", withEnter = false)

        // Blank pane id is a no-op — the plan must remain so the user can
        // retry once a pane is actually focused. This mirrors how the
        // composer / chip taps silently drop input when `currentPane` is
        // null on the tmux screen.
        assertNotNull(vm.voiceCommandReview.value.pendingPlan)
    }

    @Test
    fun planVoiceCommandRequestIncludesFocusedPaneCwdAndShellType() = runTest {
        val planner = FakeCommandPlannerClient(
            result = Result.success(CommandPlan(listOf(PlannedCommand("ls")))),
        )
        val vm = newVm(planner = planner)

        // Seed a connection target (so hostLabel / username get populated)
        // and a parsed pane with cwd + a known shell name.
        vm.replaceClientForTest(
            hostId = 42,
            hostName = "prod",
            host = "host.example",
            port = 22,
            user = "deploy",
            keyPath = "/tmp/key",
            sessionName = "main",
            client = FakeTmuxClient(),
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "shell",
                    paneIndex = 0,
                    cwd = "/srv/work",
                    currentCommand = "zsh",
                    sessionName = "main",
                ),
            ),
        )

        vm.planVoiceCommand("list files", focusedPaneId = "%0")
        advanceUntilIdle()

        val session = planner.requests.single().session
        assertEquals("prod", session.hostLabel)
        assertEquals("deploy", session.username)
        assertEquals("/srv/work", session.currentDirectory)
        assertEquals("zsh", session.shellType)
    }

    @Test
    fun planVoiceCommandRequestSuppressesNonShellCurrentCommand() = runTest {
        val planner = FakeCommandPlannerClient(
            result = Result.success(CommandPlan(listOf(PlannedCommand("ls")))),
        )
        val vm = newVm(planner = planner)

        vm.replaceClientForTest(
            hostId = 42,
            hostName = "prod",
            host = "host.example",
            port = 22,
            user = "deploy",
            keyPath = "/tmp/key",
            sessionName = "main",
            client = FakeTmuxClient(),
        )
        vm.applyParsedPanesForTest(
            listOf(
                TmuxSessionViewModel.ParsedPane(
                    paneId = "%0",
                    windowId = "@0",
                    sessionId = "$0",
                    title = "editing",
                    paneIndex = 0,
                    cwd = "/srv/work",
                    currentCommand = "vim",
                    sessionName = "main",
                ),
            ),
        )

        vm.planVoiceCommand("save the file", focusedPaneId = "%0")
        advanceUntilIdle()

        val session = planner.requests.single().session
        // Cwd is still cached even though the foreground process is not
        // a shell — only the shell-type heuristic suppresses non-shells.
        assertEquals("/srv/work", session.currentDirectory)
        assertNull(session.shellType)
    }

    @Test
    fun planVoiceCommandRequestIncludesProjectRootsFromDao() = runTest {
        val planner = FakeCommandPlannerClient(
            result = Result.success(CommandPlan(listOf(PlannedCommand("ls")))),
        )
        val dao = FakeProjectRootDao()
        dao.roots.value = listOf(
            ProjectRootEntity(id = 1L, hostId = 42, label = "work", path = "/srv/work"),
            ProjectRootEntity(id = 2L, hostId = 42, label = "src", path = "~/src"),
        )
        val vm = newVm(planner = planner, projectRootDao = dao)

        vm.replaceClientForTest(
            hostId = 42,
            hostName = "prod",
            host = "host.example",
            port = 22,
            user = "deploy",
            keyPath = "/tmp/key",
            sessionName = "main",
            client = FakeTmuxClient(),
        )
        advanceUntilIdle()

        vm.planVoiceCommand("list files", focusedPaneId = null)
        advanceUntilIdle()

        val session = planner.requests.single().session
        assertEquals(listOf("/srv/work", "~/src"), session.projectRoots)
        // No focused pane → cwd / shellType are absent (null), not empty
        // strings or stale values from another pane.
        assertNull(session.currentDirectory)
        assertNull(session.shellType)
    }

    @Test
    fun planVoiceCommandRequestEmptyMetadataWhenNothingKnown() = runTest {
        val planner = FakeCommandPlannerClient(
            result = Result.success(CommandPlan(listOf(PlannedCommand("ls")))),
        )
        val vm = newVm(planner = planner)

        vm.planVoiceCommand("list files")
        advanceUntilIdle()

        val session = planner.requests.single().session
        assertNull(session.currentDirectory)
        assertEquals(emptyList<String>(), session.projectRoots)
        assertNull(session.shellType)
    }

    private class FakeProjectRootDao : ProjectRootDao {
        val roots = MutableStateFlow<List<ProjectRootEntity>>(emptyList())

        override fun getByHostId(hostId: Long): Flow<List<ProjectRootEntity>> = roots

        override suspend fun insert(root: ProjectRootEntity): Long {
            roots.value = roots.value + root
            return roots.value.size.toLong()
        }

        override suspend fun update(root: ProjectRootEntity) {
            roots.value = roots.value.map { if (it.id == root.id) root else it }
        }

        override suspend fun delete(root: ProjectRootEntity) {
            roots.value = roots.value.filterNot { it.id == root.id }
        }
    }

    private class FakeCommandPlannerClient(
        private val result: Result<CommandPlan>,
    ) : CommandPlannerClient {
        val requests = mutableListOf<CommandPlannerRequest>()

        override suspend fun plan(request: CommandPlannerRequest): Result<CommandPlan> {
            requests += request
            return result
        }
    }
}
