package com.pocketshell.next.hosts

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [AddEditHostViewModel] against a real in-memory Room database.
 *
 * The central group here is the **#2456 / audit-F1 identity** set. That bug
 * ("Add Host can overwrite the last edited host", `docs/audit-2026-08-30-code-quality.md`
 * F1) is only observable when ONE ViewModel instance is driven through more than
 * one route, which is why those tests reuse `vm` across `bind` calls instead of
 * constructing a fresh ViewModel per scenario the way the old client's tests did
 * — the audit named that exact test gap as the reason the bug shipped.
 *
 * Verified red→green by reintroducing the old binding shape
 * (`fun bind(hostId: Long?) { if (hostId == null) return; … }`) in
 * `AddEditHostViewModel.bind`: with it, `add after an edit inserts a new host…`
 * fails on the row count (1, not 2) and `add after an edit does not carry the
 * edited host's values` fails on the retained name. Reverting the shape turns
 * both green. `AddEditHostNavigationTest`'s
 * `editing a host then adding another leaves both rows` goes red on the same
 * change, through the real screens and the real graph.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AddEditHostViewModelTest {

    private lateinit var db: AppDatabase
    private var keyId: Long = 0
    private var otherKeyId: Long = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        runBlocking {
            keyId = db.sshKeyDao().insert(SshKeyEntity(name = "key-a", privateKeyPath = "/tmp/a"))
            otherKeyId = db.sshKeyDao().insert(SshKeyEntity(name = "key-b", privateKeyPath = "/tmp/b"))
        }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    // ---------------------------------------------------------------- add ---

    @Test
    fun `add writes a new host that the host list then shows`() = runTest {
        val vm = viewModel()
        bindAndAwait(vm, null)

        fill(vm, name = "hetzner", hostname = "135.181.114.209", port = "22", username = "alexey")
        saveAndAwait(vm)

        val rows = db.hostDao().getAll().first()
        assertEquals(1, rows.size)
        assertEquals("hetzner", rows.single().name)
        assertEquals("135.181.114.209", rows.single().hostname)
        assertEquals(22, rows.single().port)
        assertEquals("alexey", rows.single().username)
        assertEquals(keyId, rows.single().keyId)

        // The acceptance for "add a host on a fresh install → it appears in
        // HostListScreen": the row the form wrote is the row U-1's projection
        // paints, through the unchanged HostListViewModel.
        val listed = HostListViewModel(db.hostDao(), UnconfinedTestDispatcher())
            .state.first { it.loaded }.hosts
        assertEquals(
            listOf(HostRow(id = rows.single().id, name = "hetzner", subtitle = "alexey@135.181.114.209")),
            listed,
        )
    }

    @Test
    fun `add trims whitespace out of the stored fields`() = runTest {
        val vm = viewModel()
        bindAndAwait(vm, null)

        fill(vm, name = "  hetzner  ", hostname = " 10.0.0.7 ", port = " 2222 ", username = " root ")
        saveAndAwait(vm)

        val row = db.hostDao().getAll().first().single()
        assertEquals("hetzner", row.name)
        assertEquals("10.0.0.7", row.hostname)
        assertEquals(2222, row.port)
        assertEquals("root", row.username)
    }

    // --------------------------------------------------------------- edit ---

    @Test
    fun `edit loads the stored host into the form`() = runTest {
        val id = insertHost("hetzner", "135.181.114.209", 22, "alexey")

        val vm = viewModel()
        bindAndAwait(vm, id)

        val state = vm.state.value
        assertTrue(state.editing)
        assertFalse(state.loading)
        assertEquals("hetzner", state.name)
        assertEquals("135.181.114.209", state.hostname)
        assertEquals("22", state.port)
        assertEquals("alexey", state.username)
        assertEquals(keyId, state.selectedKeyId)
    }

    /**
     * The other half of the F1 class, stated the way the symptom reads from the
     * user's side: an edit must never leave two rows behind.
     */
    @Test
    fun `edit updates the existing row instead of inserting a duplicate`() = runTest {
        val id = insertHost("hetzner", "135.181.114.209", 22, "alexey")

        val vm = viewModel()
        bindAndAwait(vm, id)
        vm.update { it.copy(name = "hetzner-prod", username = "root") }
        saveAndAwait(vm)

        val rows = db.hostDao().getAll().first()
        assertEquals("editing must not create a second row", 1, rows.size)
        assertEquals(id, rows.single().id)
        assertEquals("hetzner-prod", rows.single().name)
        assertEquals("root", rows.single().username)
    }

    /**
     * An edit owns five columns. Everything else on `hosts` is cache the
     * connect/bootstrap paths wrote, and rebuilding the entity from the form
     * would silently reset all of it — including `treeIdentity`, which host-side
     * durable state is keyed on.
     */
    @Test
    fun `edit preserves the columns the form does not own`() = runTest {
        val id = db.hostDao().insert(
            HostEntity(
                name = "hetzner",
                hostname = "135.181.114.209",
                port = 22,
                username = "alexey",
                keyId = keyId,
                tmuxInstalled = true,
                pocketshellInstalled = true,
                pocketshellCliVersion = "1.2.3",
                lastConnectedAt = 1234L,
                treeIdentity = "stable-identity",
            ),
        )

        val vm = viewModel()
        bindAndAwait(vm, id)
        vm.update { it.copy(name = "renamed") }
        saveAndAwait(vm)

        val row = db.hostDao().getById(id)!!
        assertEquals("renamed", row.name)
        assertEquals(true, row.tmuxInstalled)
        assertEquals(true, row.pocketshellInstalled)
        assertEquals("1.2.3", row.pocketshellCliVersion)
        assertEquals(1234L, row.lastConnectedAt)
        assertEquals("stable-identity", row.treeIdentity)
    }

    /**
     * Trust is pinned to an exact `hostname:port`. Carrying an accepted host key
     * across a repoint would make the next dial silently trust a different
     * machine on the strength of the old one's fingerprint.
     */
    @Test
    fun `repointing a host at another endpoint drops its pinned host key`() = runTest {
        val id = db.hostDao().insert(
            HostEntity(
                name = "hetzner",
                hostname = "135.181.114.209",
                port = 22,
                username = "alexey",
                keyId = keyId,
                trustedHostKeyAlgorithm = "ssh-ed25519",
                trustedHostKeySha256 = "SHA256:old",
            ),
        )

        val vm = viewModel()
        bindAndAwait(vm, id)
        vm.update { it.copy(hostname = "10.0.0.9") }
        saveAndAwait(vm)

        val row = db.hostDao().getById(id)!!
        assertNull(row.trustedHostKeyAlgorithm)
        assertNull(row.trustedHostKeySha256)
    }

    @Test
    fun `renaming a host keeps its pinned host key`() = runTest {
        val id = db.hostDao().insert(
            HostEntity(
                name = "hetzner",
                hostname = "135.181.114.209",
                port = 22,
                username = "alexey",
                keyId = keyId,
                trustedHostKeyAlgorithm = "ssh-ed25519",
                trustedHostKeySha256 = "SHA256:old",
            ),
        )

        val vm = viewModel()
        bindAndAwait(vm, id)
        vm.update { it.copy(name = "prod") }
        saveAndAwait(vm)

        val row = db.hostDao().getById(id)!!
        assertEquals("ssh-ed25519", row.trustedHostKeyAlgorithm)
        assertEquals("SHA256:old", row.trustedHostKeySha256)
    }

    // ------------------------------------- #2456 / audit-F1 identity class ---

    /**
     * **The F1 regression.** Edit host A, go back, choose Add, fill in host B,
     * save.
     *
     * On the old binding shape — `bind(hostId) { if (hostId == null) return }`
     * on an Activity-scoped ViewModel — the retained `editingHostId` still
     * pointed at A, so this save UPDATED A and the row count stayed at 1. The
     * screen said "Add host" throughout, because the title came from the route
     * while the write came from the retained field.
     */
    @Test
    fun `add after an edit inserts a new host instead of overwriting the edited one`() = runTest {
        val alpha = insertHost("alpha", "10.0.0.1", 22, "alexey")
        val vm = viewModel()

        bindAndAwait(vm, alpha)
        vm.update { it.copy(name = "alpha-renamed") }
        saveAndAwait(vm)
        vm.consumeSaved()

        bindAndAwait(vm, null)
        fill(vm, name = "beta", hostname = "10.0.0.2", port = "2222", username = "root")
        saveAndAwait(vm)

        val rows = db.hostDao().getAll().first()
        assertEquals("Add after Edit must insert, not overwrite", 2, rows.size)
        val reloadedAlpha = rows.first { it.id == alpha }
        assertEquals("alpha-renamed", reloadedAlpha.name)
        assertEquals("10.0.0.1", reloadedAlpha.hostname)
        assertEquals(22, reloadedAlpha.port)
        val beta = rows.first { it.id != alpha }
        assertEquals("beta", beta.name)
        assertEquals("10.0.0.2", beta.hostname)
        assertEquals(2222, beta.port)
    }

    /**
     * The visible half of the same bug: the Add form must open blank. The old
     * client's no-op `bind(null)` left A's values on screen under an "Add host"
     * title, which is how a user ended up overwriting A without ever seeing an
     * edit form.
     */
    @Test
    fun `add after an edit does not carry the edited host's values`() = runTest {
        val alpha = insertHost("alpha", "10.0.0.1", 2222, "alexey")
        val vm = viewModel()

        bindAndAwait(vm, alpha)
        assertEquals("alpha", vm.state.value.name)

        bindAndAwait(vm, null)

        val state = vm.state.value
        assertEquals("", state.name)
        assertEquals("", state.hostname)
        assertEquals("22", state.port)
        assertEquals("", state.username)
        assertNull(state.selectedKeyId)
        assertFalse("the form must not claim to be editing", state.editing)
    }

    /** The same reuse in the other direction: Add, then Edit, must update. */
    @Test
    fun `edit after an add updates the edited row rather than inserting again`() = runTest {
        val vm = viewModel()

        bindAndAwait(vm, null)
        fill(vm, name = "beta", hostname = "10.0.0.2", port = "22", username = "root")
        saveAndAwait(vm)
        vm.consumeSaved()
        val betaId = db.hostDao().getAll().first().single().id

        bindAndAwait(vm, betaId)
        vm.update { it.copy(name = "beta-renamed") }
        saveAndAwait(vm)

        val rows = db.hostDao().getAll().first()
        assertEquals(1, rows.size)
        assertEquals(betaId, rows.single().id)
        assertEquals("beta-renamed", rows.single().name)
    }

    /** Two adds in a row are two hosts, not one host written twice. */
    @Test
    fun `two adds on one instance create two hosts`() = runTest {
        val vm = viewModel()

        bindAndAwait(vm, null)
        fill(vm, name = "one", hostname = "10.0.0.1", port = "22", username = "u")
        saveAndAwait(vm)
        vm.consumeSaved()

        bindAndAwait(vm, null)
        fill(vm, name = "two", hostname = "10.0.0.2", port = "22", username = "u")
        saveAndAwait(vm)

        assertEquals(
            listOf("one", "two"),
            db.hostDao().getAll().first().map { it.name },
        )
    }

    /** Re-binding the same host must not reload over what the user has typed. */
    @Test
    fun `re-binding the same host leaves in-progress edits alone`() = runTest {
        val id = insertHost("hetzner", "135.181.114.209", 22, "alexey")
        val vm = viewModel()

        bindAndAwait(vm, id)
        vm.update { it.copy(name = "half-typed") }
        vm.bind(id)

        assertEquals("half-typed", vm.state.value.name)
    }

    /**
     * The route argument is the identity, so a ViewModel restored after process
     * death already knows which host it was editing before `bind` runs — and
     * saving must update that row, not insert a copy.
     */
    @Test
    fun `identity survives process death through the saved state handle`() = runTest {
        val id = insertHost("hetzner", "135.181.114.209", 22, "alexey")
        val handle = SavedStateHandle(mapOf(Destination.ARG_HOST_ID to id))

        // The recreated ViewModel binds from the route it was restored with.
        val restored = viewModel(handle)
        bindAndAwait(restored, id)
        restored.update { it.copy(name = "after-restore") }
        saveAndAwait(restored)

        val rows = db.hostDao().getAll().first()
        assertEquals(1, rows.size)
        assertEquals("after-restore", rows.single().name)
    }

    // ---------------------------------------------------------- validation ---

    @Test
    fun `a blank required field blocks the save and is reported per field`() = runTest {
        val vm = viewModel()
        bindAndAwait(vm, null)

        vm.save()

        val errors = vm.state.value.errors
        assertEquals("Required", errors.name)
        assertEquals("Required", errors.hostname)
        assertEquals("Required", errors.username)
        assertEquals("Choose an SSH key", errors.key)
        assertEquals(HostFormField.Name, errors.firstInvalid)
        assertFalse(vm.state.value.saved)
        assertTrue(db.hostDao().getAll().first().isEmpty())
    }

    /**
     * The port field is a text field, so "22x" is a value a user can genuinely
     * produce. It has to be a validation message rather than a
     * `NumberFormatException` on submit.
     */
    @Test
    fun `a port that is not a number is a validation error, not a crash`() = runTest {
        val vm = viewModel()
        bindAndAwait(vm, null)
        fill(vm, name = "h", hostname = "10.0.0.1", port = "22x", username = "u")

        vm.save()

        assertEquals("Enter a port between 1 and 65535", vm.state.value.errors.port)
        assertEquals(HostFormField.Port, vm.state.value.errors.firstInvalid)
        assertFalse(vm.state.value.saved)
        assertTrue(db.hostDao().getAll().first().isEmpty())
    }

    @Test
    fun `a port outside 1-65535 is rejected`() = runTest {
        listOf("0", "65536", "-1", "999999999999").forEach { port ->
            val vm = viewModel()
            bindAndAwait(vm, null)
            fill(vm, name = "h", hostname = "10.0.0.1", port = port, username = "u")

            vm.save()

            assertNotNull("expected port '$port' to be rejected", vm.state.value.errors.port)
            assertFalse(vm.state.value.saved)
        }
        assertTrue(db.hostDao().getAll().first().isEmpty())
    }

    @Test
    fun `an empty port is required rather than silently defaulted`() = runTest {
        val vm = viewModel()
        bindAndAwait(vm, null)
        fill(vm, name = "h", hostname = "10.0.0.1", port = "", username = "u")

        vm.save()

        assertEquals("Required", vm.state.value.errors.port)
        assertFalse(vm.state.value.saved)
    }

    @Test
    fun `fixing a field clears its error without waiting for another submit`() = runTest {
        val vm = viewModel()
        bindAndAwait(vm, null)
        vm.save()
        assertNotNull(vm.state.value.errors.name)

        vm.update { it.copy(name = "hetzner") }

        assertNull(vm.state.value.errors.name)
        // Untouched fields keep theirs — the user has not fixed those yet.
        assertNotNull(vm.state.value.errors.hostname)
    }

    @Test
    fun `choosing a key clears the key error`() = runTest {
        val vm = viewModel()
        bindAndAwait(vm, null)
        vm.save()
        assertNotNull(vm.state.value.errors.key)

        vm.update { it.copy(selectedKeyId = otherKeyId) }

        assertNull(vm.state.value.errors.key)
    }

    @Test
    fun `the saved signal is one-shot`() = runTest {
        val vm = viewModel()
        bindAndAwait(vm, null)
        fill(vm, name = "h", hostname = "10.0.0.1", port = "22", username = "u")
        saveAndAwait(vm)

        vm.consumeSaved()

        assertFalse(vm.state.value.saved)
    }

    @Test
    fun `the key picker sees every registered key`() = runTest {
        val vm = viewModel()

        assertEquals(listOf("key-a", "key-b"), vm.sshKeys.first { it.size == 2 }.map { it.name })
    }

    // ------------------------------------------------------------- helpers ---

    private fun viewModel(
        handle: SavedStateHandle = SavedStateHandle(),
    ): AddEditHostViewModel = AddEditHostViewModel(db.hostDao(), db.sshKeyDao(), handle)

    /**
     * `bind` starts a real Room read on Room's own executor, so the form is not
     * populated the instant it returns. Awaiting `loading == false` is the
     * ViewModel's own "the read landed" signal — no arbitrary delay, and it is
     * the same signal the screen renders on.
     */
    private suspend fun bindAndAwait(vm: AddEditHostViewModel, hostId: Long?) {
        vm.bind(hostId)
        vm.state.first { !it.loading }
    }

    /**
     * `save` writes through Room's executor too. The `saved` flag is set as the
     * last statement of that coroutine, so awaiting it means the row is
     * committed — the DAO read that follows cannot race the write.
     *
     * Only for saves expected to succeed; a rejected save never sets the flag,
     * and those tests assert on the synchronously-set errors instead.
     */
    private suspend fun saveAndAwait(vm: AddEditHostViewModel) {
        vm.save()
        vm.state.first { it.saved }
    }

    private fun fill(
        vm: AddEditHostViewModel,
        name: String,
        hostname: String,
        port: String,
        username: String,
        selectedKeyId: Long = keyId,
    ) {
        vm.update {
            it.copy(
                name = name,
                hostname = hostname,
                port = port,
                username = username,
                selectedKeyId = selectedKeyId,
            )
        }
    }

    private suspend fun insertHost(
        name: String,
        hostname: String,
        port: Int,
        username: String,
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
