package com.pocketshell.next.hosts

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [HostListViewModel] against a real in-memory Room database (plan §U-1
 * acceptance: "VM unit test with in-memory Room").
 *
 * No mock DAO on purpose: the thing that can realistically break here is the
 * projection of a *stored* row — column order, a blank stored name, the DAO's
 * `ORDER BY name` — and a hand-written fake would encode the answer instead of
 * checking it. Robolectric supplies the Android `Context` Room needs; the
 * database never touches disk.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HostListViewModelTest {

    private lateinit var db: AppDatabase
    private var keyId: Long = 0

    @Before
    fun setUp() {
        // `viewModelScope` dispatches on Main; the unconfined test dispatcher
        // makes the `stateIn` subscription start eagerly on collection instead
        // of waiting for a looper turn.
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
        // `hosts.keyId` is a foreign key onto `ssh_keys`, and Room enforces it,
        // so every seeded host needs a real key row to hang off.
        keyId = runBlocking {
            db.sshKeyDao().insert(
                SshKeyEntity(name = "test-key", privateKeyPath = "/tmp/id_ed25519"),
            )
        }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is not loaded and empty`() {
        // Before Room's first emission the screen must be able to tell
        // "still querying" from "no hosts" — otherwise every cold launch
        // flashes the empty state.
        val vm = viewModel()

        assertFalse(vm.state.value.loaded)
        assertTrue(vm.state.value.hosts.isEmpty())
    }

    @Test
    fun `empty database emits a loaded empty list`() = runTest {
        val vm = viewModel()

        val state = vm.state.first { it.loaded }

        assertTrue(state.hosts.isEmpty())
    }

    @Test
    fun `stored hosts are projected to rows in name order`() = runTest {
        val hetzner = insertHost(name = "hetzner", hostname = "135.181.114.209", username = "alexey")
        val builder = insertHost(name = "builder", hostname = "10.0.0.7", username = "root", port = 2222)

        val rows = viewModel().state.first { it.loaded }.hosts

        // DAO orders by name, so "builder" precedes "hetzner" regardless of
        // insertion order.
        assertEquals(
            listOf(
                HostRow(id = builder, name = "builder", subtitle = "root@10.0.0.7"),
                HostRow(id = hetzner, name = "hetzner", subtitle = "alexey@135.181.114.209"),
            ),
            rows,
        )
    }

    @Test
    fun `a host with a blank name falls back to its hostname`() = runTest {
        val id = insertHost(name = "   ", hostname = "box.example.com", username = "alexey")

        val rows = viewModel().state.first { it.loaded }.hosts

        assertEquals(
            listOf(HostRow(id = id, name = "box.example.com", subtitle = "alexey@box.example.com")),
            rows,
        )
    }

    @Test
    fun `a host added while the screen is open shows up without a refresh`() = runTest {
        insertHost(name = "hetzner", hostname = "135.181.114.209", username = "alexey")
        val vm = viewModel()
        assertEquals(1, vm.state.first { it.loaded }.hosts.size)

        insertHost(name = "zeta", hostname = "10.0.0.9", username = "root")

        // Room owns invalidation: the list re-emits on its own, which is why
        // this screen needs no refresh/reload plumbing at all.
        val names = vm.state.map { s -> s.hosts.map { it.name } }.first { it.size == 2 }
        assertEquals(listOf("hetzner", "zeta"), names)
    }

    private fun viewModel(): HostListViewModel =
        HostListViewModel(db.hostDao(), UnconfinedTestDispatcher())

    private suspend fun insertHost(
        name: String,
        hostname: String,
        username: String,
        port: Int = 22,
    ): Long = db.hostDao().insert(
        HostEntity(
            name = name,
            hostname = hostname,
            port = port,
            username = username,
            keyId = keyId,
        ),
    )
}
