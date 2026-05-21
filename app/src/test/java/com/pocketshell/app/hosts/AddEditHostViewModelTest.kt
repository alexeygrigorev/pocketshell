package com.pocketshell.app.hosts

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [AddEditHostViewModel] against an in-memory Room database.
 *
 * Covers:
 * - Saving a new host writes a row with the trimmed form values.
 * - Loading an existing host hydrates the form state.
 * - Validation errors surface for missing required fields and missing key.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AddEditHostViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            // Pin Room's executors to a synchronous Runnable::run so its
            // suspending DAO calls resume on whatever invoked them
            // (the test dispatcher). Otherwise the test thread races
            // Room's internal background executor.
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun save_writesHostRow_whenAllFieldsPresent() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"),
        )
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())

        vm.updateState {
            it.copy(
                name = "  prod  ",
                hostname = " example.com ",
                port = "2222",
                username = " alexey ",
                selectedKeyId = keyId,
            )
        }
        vm.save()

        // The UnconfinedTestDispatcher runs the viewModelScope launch
        // synchronously, so the state has already settled.
        assertTrue(vm.state.value.saved)
        assertNull(vm.state.value.error)

        val hosts = db.hostDao().getAll().first()
        assertEquals(1, hosts.size)
        // Trim normalises the leading / trailing whitespace.
        assertEquals("prod", hosts[0].name)
        assertEquals("example.com", hosts[0].hostname)
        assertEquals(2222, hosts[0].port)
        assertEquals("alexey", hosts[0].username)
        assertEquals(keyId, hosts[0].keyId)
    }

    @Test
    fun save_reportsError_whenRequiredFieldsBlank() = runTest {
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        vm.save()
        assertNotNull(vm.state.value.error)
        assertEquals(false, vm.state.value.saved)
        // No row was persisted.
        assertEquals(0, db.hostDao().getAll().first().size)
    }

    @Test
    fun save_reportsError_whenNoKeySelected() = runTest {
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        vm.updateState {
            it.copy(name = "x", hostname = "h", username = "u", selectedKeyId = null)
        }
        vm.save()
        assertNotNull(vm.state.value.error)
        assertEquals(false, vm.state.value.saved)
        assertEquals(0, db.hostDao().getAll().first().size)
    }

    @Test
    fun loadHost_hydratesFormState() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"),
        )
        val hostId = db.hostDao().insert(
            com.pocketshell.core.storage.entity.HostEntity(
                name = "homelab",
                hostname = "homelab.local",
                port = 2200,
                username = "deploy",
                keyId = keyId,
            ),
        )
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        vm.loadHost(hostId)

        // The UnconfinedTestDispatcher drains the launch synchronously,
        // so the state is hydrated by the time we read it back.
        val loaded = vm.state.value
        assertEquals("homelab", loaded.name)
        assertEquals("homelab.local", loaded.hostname)
        assertEquals("2200", loaded.port)
        assertEquals("deploy", loaded.username)
        assertEquals(keyId, loaded.selectedKeyId)
    }
}
