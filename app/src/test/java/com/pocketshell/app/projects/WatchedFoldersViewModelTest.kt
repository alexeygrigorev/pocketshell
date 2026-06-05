package com.pocketshell.app.projects

import com.pocketshell.core.storage.dao.ProjectRootDao
import com.pocketshell.core.storage.entity.ProjectRootEntity
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [WatchedFoldersViewModel] focused on the data-layer
 * paths: bind + observe, validation, dedupe, reorder, and the
 * discover-output parser. SSH-touching code (the actual
 * `SshConnection.connect` call) is exercised by the connected E2E
 * test, not here.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class WatchedFoldersViewModelTest {

    private fun newVm(dao: ProjectRootDao = FakeProjectRootDao()): WatchedFoldersViewModel =
        WatchedFoldersViewModel(
            projectRootDao = dao,
        )

    @Test
    fun bindEmitsCurrentRoots() = runTest {
        val dao = FakeProjectRootDao()
        dao.roots.value = listOf(
            ProjectRootEntity(id = 1L, hostId = 42L, label = "work", path = "/srv/work"),
            ProjectRootEntity(id = 2L, hostId = 42L, label = "src", path = "~/src"),
        )
        val vm = newVm(dao)

        vm.bind(hostId = 42L, hostName = "hetzner")
        advanceUntilIdle()

        assertEquals(42L, vm.state.value.hostId)
        assertEquals("hetzner", vm.state.value.hostName)
        assertEquals(2, vm.state.value.roots.size)
        assertFalse(vm.state.value.sshCapable)
    }

    @Test
    fun addFolderInsertsValidRow() = runTest {
        val dao = FakeProjectRootDao()
        val vm = newVm(dao)
        vm.bind(hostId = 7L, hostName = "h")
        advanceUntilIdle()

        vm.addFolder(rawLabel = "code", rawPath = "~/code/project")
        advanceUntilIdle()

        assertEquals(1, dao.inserted.size)
        val inserted = dao.inserted.first()
        assertEquals(7L, inserted.hostId)
        assertEquals("code", inserted.label)
        assertEquals("~/code/project", inserted.path)
    }

    @Test
    fun addFolderRejectsRelativePath() = runTest {
        val dao = FakeProjectRootDao()
        val vm = newVm(dao)
        vm.bind(hostId = 1L, hostName = "h")
        advanceUntilIdle()

        vm.addFolder(rawLabel = "x", rawPath = "src/foo")
        advanceUntilIdle()

        assertTrue(dao.inserted.isEmpty())
        assertNotNull(vm.state.value.feedback)
    }

    @Test
    fun addFolderRejectsParentSegment() = runTest {
        val dao = FakeProjectRootDao()
        val vm = newVm(dao)
        vm.bind(hostId = 1L, hostName = "h")
        advanceUntilIdle()

        vm.addFolder(rawLabel = "", rawPath = "~/foo/../etc")
        advanceUntilIdle()

        assertTrue(dao.inserted.isEmpty())
        assertEquals(
            "Parent-directory segments are not allowed.",
            vm.state.value.feedback,
        )
    }

    @Test
    fun addFolderDedupesByPath() = runTest {
        val dao = FakeProjectRootDao()
        dao.roots.value = listOf(
            ProjectRootEntity(id = 1L, hostId = 1L, label = "code", path = "~/code"),
        )
        val vm = newVm(dao)
        vm.bind(hostId = 1L, hostName = "h")
        advanceUntilIdle()

        vm.addFolder(rawLabel = "code-2", rawPath = "~/code")
        advanceUntilIdle()

        assertTrue(dao.inserted.isEmpty())
        assertEquals(
            "Path already in this host's watched folders.",
            vm.state.value.feedback,
        )
    }

    @Test
    fun addFolderDerivesLabelFromPath() = runTest {
        val dao = FakeProjectRootDao()
        val vm = newVm(dao)
        vm.bind(hostId = 1L, hostName = "h")
        advanceUntilIdle()

        vm.addFolder(rawLabel = "", rawPath = "~/git/pocketshell")
        advanceUntilIdle()

        assertEquals(1, dao.inserted.size)
        assertEquals("pocketshell", dao.inserted.first().label)
    }

    @Test
    fun deleteFolderRemovesRow() = runTest {
        val dao = FakeProjectRootDao()
        val row = ProjectRootEntity(id = 99L, hostId = 1L, label = "doomed", path = "~/doomed")
        dao.roots.value = listOf(row)
        val vm = newVm(dao)
        vm.bind(hostId = 1L, hostName = "h")
        advanceUntilIdle()

        vm.deleteFolder(99L)
        advanceUntilIdle()

        assertTrue(dao.deleted.contains(99L))
    }

    @Test
    fun reorderUpdatesLabelsToHonorNewOrder() = runTest {
        val dao = FakeProjectRootDao()
        dao.roots.value = listOf(
            ProjectRootEntity(id = 1L, hostId = 1L, label = "alpha", path = "/a"),
            ProjectRootEntity(id = 2L, hostId = 1L, label = "beta", path = "/b"),
            ProjectRootEntity(id = 3L, hostId = 1L, label = "gamma", path = "/c"),
        )
        val vm = newVm(dao)
        vm.bind(hostId = 1L, hostName = "h")
        advanceUntilIdle()

        // Move 3rd row (gamma) up by one → order becomes alpha, gamma, beta
        vm.reorderFolder(fromIndex = 2, delta = -1)
        advanceUntilIdle()

        // Each row should now carry an [NN] order prefix matching the
        // index they occupied after the swap.
        val updatedById = dao.updates.associateBy { it.id }
        assertEquals("[00] alpha", updatedById[1L]?.label)
        assertEquals("[01] gamma", updatedById[3L]?.label)
        assertEquals("[02] beta", updatedById[2L]?.label)
    }

    @Test
    fun updateFolderAfterReorderPreservesConfiguredOrder() = runTest {
        val dao = FakeProjectRootDao()
        dao.roots.value = listOf(
            ProjectRootEntity(id = 1L, hostId = 1L, label = "alpha", path = "/a"),
            ProjectRootEntity(id = 2L, hostId = 1L, label = "beta", path = "/b"),
            ProjectRootEntity(id = 3L, hostId = 1L, label = "gamma", path = "/c"),
        )
        val vm = newVm(dao)
        vm.bind(hostId = 1L, hostName = "h")
        advanceUntilIdle()

        vm.reorderFolder(fromIndex = 2, delta = -1)
        advanceUntilIdle()

        val moved = vm.state.value.roots.first { it.id == 3L }
        vm.updateFolder(id = moved.id, rawLabel = "zeta", rawPath = "/z")
        advanceUntilIdle()

        assertEquals(
            listOf("alpha", "zeta", "beta"),
            vm.state.value.roots.map { WatchedFoldersViewModel.stripOrderPrefix(it.label) },
        )
        val updated = dao.roots.value.first { it.id == 3L }
        assertEquals("[01] zeta", updated.label)
        assertEquals("/z", updated.path)
    }

    @Test
    fun stripOrderPrefixRoundTrips() {
        assertEquals("alpha", WatchedFoldersViewModel.stripOrderPrefix("[00] alpha"))
        assertEquals("beta", WatchedFoldersViewModel.stripOrderPrefix("beta"))
        assertEquals(
            "src",
            WatchedFoldersViewModel.stripOrderPrefix(
                WatchedFoldersViewModel.applyOrderPrefix("src", index = 4, total = 10),
            ),
        )
    }

    @Test
    fun parseDiscoverOutputExtractsAbsolutePaths() {
        val stdout = """
            /home/u/git/pocketshell/
            /home/u/git/dotfiles/
            /home/u/code/site/

        """.trimIndent()
        val candidates = WatchedFoldersViewModel.parseDiscoverOutput(stdout)
        assertEquals(3, candidates.size)
        assertEquals("pocketshell", candidates[0].label)
        assertEquals("/home/u/git/pocketshell", candidates[0].path)
        assertEquals("/home/u/code/site", candidates[2].path)
    }

    @Test
    fun parseDiscoverOutputSkipsBlanksAndDots() {
        val stdout = "\n\n/home/u/git/.\n/home/u/git/foo/\n   \n"
        val candidates = WatchedFoldersViewModel.parseDiscoverOutput(stdout)
        assertEquals(1, candidates.size)
        assertEquals("foo", candidates[0].label)
    }

    @Test
    fun parseDiscoverOutputDedupes() {
        val stdout = "/home/u/git/foo/\n/home/u/git/foo/\n"
        val candidates = WatchedFoldersViewModel.parseDiscoverOutput(stdout)
        assertEquals(1, candidates.size)
    }

    @Test
    fun discoverWithoutCredentialsSurfacesHint() = runTest {
        val dao = FakeProjectRootDao()
        val vm = newVm(dao)
        vm.bind(hostId = 1L, hostName = "h", sshCredentials = null)
        advanceUntilIdle()

        vm.discoverFromRemote()
        advanceUntilIdle()

        assertEquals("Open this host to enable discovery.", vm.state.value.feedback)
        assertFalse(vm.state.value.discovering)
    }

    @Test
    fun acceptDiscoveredInsertsAndRemovesCandidate() = runTest {
        val dao = FakeProjectRootDao()
        val vm = newVm(dao)
        vm.bind(hostId = 1L, hostName = "h")
        advanceUntilIdle()

        val candidate = DiscoveredFolder(label = "alpha", path = "~/git/alpha")
        // Inject candidates via a discover call against a fake — easier
        // to call acceptDiscovered directly after seeding state through
        // the test path.
        vm.javaClass.getDeclaredField("_state").apply {
            isAccessible = true
            @Suppress("UNCHECKED_CAST")
            val flow = get(vm) as MutableStateFlow<WatchedFoldersUiState>
            flow.value = flow.value.copy(discoveredCandidates = listOf(candidate))
        }

        vm.acceptDiscovered(candidate)
        advanceUntilIdle()

        assertEquals(1, dao.inserted.size)
        assertEquals("~/git/alpha", dao.inserted.first().path)
        assertTrue(vm.state.value.discoveredCandidates.isEmpty())
    }

    @Test
    fun clearFeedbackResetsFeedback() = runTest {
        val dao = FakeProjectRootDao()
        val vm = newVm(dao)
        vm.bind(hostId = 1L, hostName = "h")
        advanceUntilIdle()
        vm.addFolder(rawLabel = "x", rawPath = "../bad")
        advanceUntilIdle()
        assertNotNull(vm.state.value.feedback)

        vm.clearFeedback()
        assertNull(vm.state.value.feedback)
    }
}

private class FakeProjectRootDao : ProjectRootDao {
    val roots = MutableStateFlow<List<ProjectRootEntity>>(emptyList())
    val inserted = mutableListOf<ProjectRootEntity>()
    val updates = mutableListOf<ProjectRootEntity>()
    val deleted = mutableListOf<Long>()
    private var nextId = 100L

    override fun getByHostId(hostId: Long): Flow<List<ProjectRootEntity>> = roots

    override suspend fun insert(root: ProjectRootEntity): Long {
        val withId = if (root.id == 0L) root.copy(id = nextId++) else root
        inserted += withId
        roots.value = sorted(roots.value + withId)
        return withId.id
    }

    override suspend fun update(root: ProjectRootEntity) {
        updates += root
        roots.value = sorted(roots.value.map { if (it.id == root.id) root else it })
    }

    override suspend fun delete(root: ProjectRootEntity) {
        deleted += root.id
        roots.value = roots.value.filterNot { it.id == root.id }
    }

    private fun sorted(rows: List<ProjectRootEntity>): List<ProjectRootEntity> =
        rows.sortedWith(compareBy<ProjectRootEntity> { it.label }.thenBy { it.path })
}
