package com.pocketshell.next.settings

import androidx.lifecycle.SavedStateHandle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.hostapi.HostCliClient
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.core.transport.ExecResult
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.connect.FakeHostConnectionFactory
import com.pocketshell.next.connect.RoomTrustStore
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.hostcli.asRemoteExec
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
    private lateinit var registry: ConnectionsRegistry
    private var hostId: Long = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        val factory = FakeHostConnectionFactory()
        factory.script = { connection ->
            val sftp = connection.sftpFixture()
            connection.onExec("pwd", ExecResult(0, "/home/alexey\n", "", timedOut = false))
            listOf(
                "/home/alexey/git/pocketshell",
                "/a",
                "/b",
                "/home/alexey/proj",
            ).forEach(sftp::seedDirectory)
        }
        registry = ConnectionsRegistry(
            factory = factory,
            trustStore = RoomTrustStore(db.hostDao(), Dispatchers.Unconfined),
            hostDao = db.hostDao(),
            dispatcher = Dispatchers.Unconfined,
        )
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
        registry = registry,
        clients = HostCliClientFactory { connection -> HostCliClient(connection.asRemoteExec()) },
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
    fun `a home-relative root resolves against the host home before it is saved`() = runTest {
        val vm = viewModel()
        vm.state.first { it.loaded }

        vm.addRoot("Projects", "~/proj")

        val root = vm.state.first { it.roots.isNotEmpty() }.roots.single()
        assertEquals("/home/alexey/proj", root.path)
        assertEquals("/home/alexey/proj", db.projectRootDao().getByHostId(hostId).first().single().path)
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

        vm.deleteRoot(toDelete).join()

        val remaining = db.projectRootDao().getByHostId(hostId).first()
        assertEquals(listOf("/b"), remaining.map { it.path })
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
