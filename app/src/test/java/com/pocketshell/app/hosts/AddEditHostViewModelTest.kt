package com.pocketshell.app.hosts

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * - Per-field validation errors surface for missing required fields,
 *   missing key, and out-of-range port (issue #111).
 * - The first invalid field is reported so the screen can request focus.
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
        assertTrue(vm.state.value.fieldErrors.isClean())

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
    fun save_reportsPerFieldErrors_andFirstInvalid_whenRequiredFieldsBlank() = runTest {
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        vm.save()

        val state = vm.state.value
        // Issue #111 — global prose error is gone; per-field errors light up.
        assertNull(state.error)
        assertEquals("Required", state.fieldErrors.name)
        assertEquals("Required", state.fieldErrors.hostname)
        assertEquals("Required", state.fieldErrors.username)
        // Port keeps the default "22" so it is valid even on an empty submit.
        assertNull(state.fieldErrors.port)
        // No keys exist either.
        assertNotNull(state.fieldErrors.selectedKey)
        // The screen should move focus to the first field in tab order.
        assertEquals(HostFormField.Name, state.firstInvalidField)
        assertFalse(state.saved)
        assertEquals(0, db.hostDao().getAll().first().size)
    }

    @Test
    fun save_reportsKeyError_whenAllFieldsFilledButNoKeySelected() = runTest {
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        vm.updateState {
            it.copy(name = "x", hostname = "h", username = "u", selectedKeyId = null)
        }
        vm.save()

        val state = vm.state.value
        assertNull(state.error)
        assertEquals("Select an SSH key", state.fieldErrors.selectedKey)
        assertEquals(HostFormField.SelectedKey, state.firstInvalidField)
        assertFalse(state.saved)
        assertEquals(0, db.hostDao().getAll().first().size)
    }

    @Test
    fun save_reportsPortError_whenPortOutOfRange() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"),
        )
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        vm.updateState {
            it.copy(
                name = "x",
                hostname = "h",
                username = "u",
                port = "99999",
                selectedKeyId = keyId,
            )
        }
        vm.save()

        val state = vm.state.value
        assertEquals("Invalid port (1-65535)", state.fieldErrors.port)
        assertEquals(HostFormField.Port, state.firstInvalidField)
        assertFalse(state.saved)
        assertEquals(0, db.hostDao().getAll().first().size)
    }

    @Test
    fun save_reportsPortError_whenPortNotInteger() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"),
        )
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        vm.updateState {
            it.copy(
                name = "x",
                hostname = "h",
                username = "u",
                port = "abc",
                selectedKeyId = keyId,
            )
        }
        vm.save()

        assertEquals("Invalid port (1-65535)", vm.state.value.fieldErrors.port)
        assertFalse(vm.state.value.saved)
    }

    @Test
    fun updateState_clearsFieldError_whenUserEditsThatField() = runTest {
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        // Trigger errors first.
        vm.save()
        assertEquals("Required", vm.state.value.fieldErrors.name)

        // Type into the Name field — its error should clear immediately so
        // the red outline doesn't linger after the fix (issue #111).
        vm.updateState { it.copy(name = "homelab") }
        assertNull(vm.state.value.fieldErrors.name)
        // Untouched fields keep their errors until the user edits them or
        // hits Save again.
        assertEquals("Required", vm.state.value.fieldErrors.hostname)
    }

    @Test
    fun consumeFirstInvalidField_clearsTheSignal() = runTest {
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        vm.save()
        assertNotNull(vm.state.value.firstInvalidField)

        vm.consumeFirstInvalidField()
        assertNull(vm.state.value.firstInvalidField)
    }

    @Test
    fun validate_pure_returnsCleanErrorsForCompletelyValidState() {
        val state = HostFormState(
            name = "x",
            hostname = "h",
            port = "22",
            username = "u",
            selectedKeyId = 1L,
        )
        val errs = AddEditHostViewModel.validate(state)
        assertTrue(errs.isClean())
    }

    @Test
    fun validate_pure_acceptsPortBoundaries() {
        val base = HostFormState(
            name = "x", hostname = "h", username = "u", selectedKeyId = 1L,
        )
        assertNull(AddEditHostViewModel.validate(base.copy(port = "1")).port)
        assertNull(AddEditHostViewModel.validate(base.copy(port = "65535")).port)
        assertNotNull(AddEditHostViewModel.validate(base.copy(port = "0")).port)
        assertNotNull(AddEditHostViewModel.validate(base.copy(port = "65536")).port)
        assertNotNull(AddEditHostViewModel.validate(base.copy(port = "")).port)
    }

    @Test
    fun isDirty_falseOnFreshAddForm_trueAfterEdit() = runTest {
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        // A pristine Add form should not be dirty — the BackHandler in
        // AddEditHostScreen relies on this to navigate back without
        // prompting (issue #38 item 3).
        assertEquals(false, vm.isDirty())

        vm.updateState { it.copy(name = "anything") }
        assertEquals(true, vm.isDirty())
    }

    @Test
    fun isDirty_falseAfterLoadHost_trueAfterUserEdit() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"),
        )
        val hostId = db.hostDao().insert(
            com.pocketshell.core.storage.entity.HostEntity(
                name = "host",
                hostname = "h.example",
                port = 22,
                username = "u",
                keyId = keyId,
            ),
        )
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        vm.loadHost(hostId)
        // Right after a load the baseline matches the form, so the
        // BackHandler should fall straight through to onDone().
        assertEquals(false, vm.isDirty())

        vm.updateState { it.copy(hostname = "h2.example") }
        assertEquals(true, vm.isDirty())
    }

    @Test
    fun save_persistsUsageCommandOverride_andTrimsBlankToNull() = runTest {
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"),
        )
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        vm.updateState {
            it.copy(
                name = "x",
                hostname = "h",
                username = "u",
                selectedKeyId = keyId,
                usageCommand = " mycorp-usage --json ",
            )
        }
        vm.save()

        val hosts = db.hostDao().getAll().first()
        assertEquals("mycorp-usage --json", hosts.single().usageCommandOverride)

        // A second save with a blank usage command clears the override
        // back to null (the scheduler then falls back to the default).
        val vm2 = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        vm2.loadHost(hosts.single().id)
        vm2.updateState { it.copy(usageCommand = "   ") }
        vm2.save()
        assertNull(db.hostDao().getAll().first().single().usageCommandOverride)
    }

    @Test
    fun save_onEdit_preservesBootstrapCacheColumns() = runTest {
        // Issue #117 regression: save() used to overwrite the entire
        // HostEntity, clobbering tmuxInstalled / heruInstalled /
        // lastBootstrapAt. Verify the edit path now merges into the
        // existing row.
        val keyId = db.sshKeyDao().insert(
            SshKeyEntity(name = "k", privateKeyPath = "/tmp/k"),
        )
        val hostId = db.hostDao().insert(
            com.pocketshell.core.storage.entity.HostEntity(
                name = "h",
                hostname = "h.example",
                port = 22,
                username = "u",
                keyId = keyId,
                tmuxInstalled = true,
                lastBootstrapAt = 12345L,
                heruInstalled = true,
                heruLastDetectedAt = 9999L,
            ),
        )
        val vm = AddEditHostViewModel(db.hostDao(), db.sshKeyDao())
        vm.loadHost(hostId)
        vm.updateState { it.copy(name = "renamed") }
        vm.save()

        val row = db.hostDao().getById(hostId)!!
        assertEquals("renamed", row.name)
        assertEquals(true, row.tmuxInstalled)
        assertEquals(12345L, row.lastBootstrapAt)
        assertEquals(true, row.heruInstalled)
        assertEquals(9999L, row.heruLastDetectedAt)
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
