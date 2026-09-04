package com.pocketshell.next.settings

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.next.nav.Destination
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class WorkspaceRootsViewModelTest {

    private lateinit var db: AppDatabase
    private var hostId: Long = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        runBlocking {
            val keyId = db.sshKeyDao().insert(SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"))
            hostId = db.hostDao().insert(
                HostEntity(name = "hetzner", hostname = "10.0.0.1", username = "alexey", keyId = keyId),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    private fun viewModel(id: Long = hostId): WorkspaceRootsViewModel = WorkspaceRootsViewModel(
        projectRootDao = db.projectRootDao(),
        hostDao = db.hostDao(),
        savedStateHandle = SavedStateHandle(mapOf(Destination.ARG_HOST_ID to id)),
        dispatcher = UnconfinedTestDispatcher(),
    )

    @Test
    fun `a fresh host has no roots and shows its own name`() = runTest {
        val vm = viewModel()

        val state = vm.state.first { it.loaded }

        assertEquals("hetzner", state.hostName)
        assertTrue(state.roots.isEmpty())
    }

    @Test
    fun `adding a root makes it appear in state`() = runTest {
        val vm = viewModel()
        vm.state.first { it.loaded }

        vm.addRoot("Pocketshell", "/home/alexey/git/pocketshell")

        val roots = vm.state.first { it.roots.isNotEmpty() }.roots
        assertEquals("Pocketshell", roots.single().label)
        assertEquals("/home/alexey/git/pocketshell", roots.single().path)
    }

    @Test
    fun `a blank label falls back to the last path segment`() = runTest {
        val vm = viewModel()
        vm.state.first { it.loaded }

        vm.addRoot("  ", "/home/alexey/git/pocketshell")

        val root = vm.state.first { it.roots.isNotEmpty() }.roots.single()
        assertEquals("pocketshell", root.label)
    }

    @Test
    fun `a trailing slash on the path is trimmed`() = runTest {
        val vm = viewModel()
        vm.state.first { it.loaded }

        vm.addRoot("Pocketshell", "/home/alexey/git/pocketshell/")

        val root = vm.state.first { it.roots.isNotEmpty() }.roots.single()
        assertEquals("/home/alexey/git/pocketshell", root.path)
    }

    @Test
    fun `a blank path is a no-op`() = runTest {
        val vm = viewModel()
        vm.state.first { it.loaded }

        vm.addRoot("Label", "   ")

        assertTrue(db.projectRootDao().getByHostId(hostId).first().isEmpty())
    }

    @Test
    fun `deleting a root removes only that row`() = runTest {
        val vm = viewModel()
        vm.state.first { it.loaded }

        vm.addRoot("A", "/a")
        vm.addRoot("B", "/b")
        val roots = vm.state.first { it.roots.size == 2 }.roots
        val toDelete = roots.first { it.path == "/a" }

        vm.deleteRoot(toDelete)

        val remaining = vm.state.first { it.roots.size == 1 }.roots
        assertEquals("/b", remaining.single().path)
    }

    @Test
    fun `roots are scoped to their own host`() = runTest {
        val otherHostId = runBlocking {
            db.hostDao().insert(
                HostEntity(
                    name = "other",
                    hostname = "10.0.0.2",
                    username = "root",
                    keyId = db.sshKeyDao().getAll().first().first().id,
                ),
            )
        }
        val vmA = viewModel(hostId)
        val vmB = viewModel(otherHostId)
        vmA.state.first { it.loaded }
        vmB.state.first { it.loaded }

        vmA.addRoot("A", "/a")

        vmA.state.first { it.roots.isNotEmpty() }
        assertTrue(vmB.state.value.roots.isEmpty())
    }

    @Test
    fun `a duplicate path replaces the earlier row's label instead of duplicating it`() = runTest {
        val vm = viewModel()
        vm.state.first { it.loaded }

        vm.addRoot("First", "/home/alexey/proj")
        vm.state.first { it.roots.isNotEmpty() }
        vm.addRoot("Renamed", "/home/alexey/proj")

        val roots = vm.state.first { it.roots.any { row -> row.label == "Renamed" } }.roots
        assertEquals(1, roots.size)
        assertEquals("Renamed", roots.single().label)
    }
}
