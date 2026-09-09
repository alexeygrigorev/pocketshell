package com.pocketshell.next.hosts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.next.connect.SshKeyUnlocker
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [SshKeysViewModel] over the real [SshKeyStore] and a real database.
 *
 * The assertions are on the key list and the on-disk file, not just on the
 * message string: a "Generated x" banner over an empty `ssh_keys` table is
 * exactly the failure worth catching.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SshKeysViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /** One scheduler for Main, the store and every runTest body (issue #2623). */
    private val main = UnconfinedTestDispatcher()
    private lateinit var db: AppDatabase
    private lateinit var keyStore: SshKeyStore
    private lateinit var viewModel: SshKeysViewModel
    private val viewModelStore = ViewModelStore()
    private val unlocker = object : SshKeyUnlocker {
        override fun rememberPassphrase(keyId: Long, value: CharArray) = Unit
        override fun copyPassphrase(keyId: Long): CharArray? = null
        override fun clearPassphrase(keyId: Long) = Unit
    }

    @Before
    fun setUp() {
        // ONE UnconfinedTestDispatcher for everything Main-driven AND the
        // store: two instances mean two schedulers, and work the store resumes
        // onto its own scheduler is never advanced by runTest — the ViewModel
        // job parks until runTest's 60s UncompletedCoroutinesError, and the
        // parked coroutine then trips the next tearDown's resetMain with an
        // IllegalStateException (issue #2623). runTest advances the shared
        // scheduler while the body waits, so both parks drain.
        Dispatchers.setMain(main)
        // Room gets DIRECT executors so its suspend/Flow work also runs inline
        // on the calling thread: Room's own pooled executors are real threads
        // no test scheduler drives, which left the same park-forever hole as
        // the second dispatcher above (issue #2623 — the class still hung one
        // method in three once the schedulers were unified).
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .setQueryExecutor { it.run() }
            .setTransactionExecutor { it.run() }
            .build()
        keyStore = SshKeyStore(
            File(temporaryFolder.root, "ssh-keys"),
            db.sshKeyDao(),
            main,
        )
        val created = SshKeysViewModel(db.sshKeyDao(), keyStore, unlocker)
        viewModel = ViewModelProvider(
            viewModelStore,
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = created as T
            },
        )[SshKeysViewModel::class.java]
    }

    @After
    fun tearDown() {
        viewModelStore.clear()
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `generating a key adds it to the list with a usable file on disk`() = runTest(main) {
        viewModel.generate("laptop").join()

        val state = viewModel.state.first {
            it.keys.isNotEmpty() && it.message == "Generated laptop"
        }
        assertEquals(listOf("laptop"), state.keys.map { it.name })
        assertEquals("Generated laptop", state.message)

        val stored = db.sshKeyDao().getAll().first().single()
        assertTrue(File(stored.privateKeyPath).isFile)
        assertTrue(SshKeyMaterial.looksLikePrivateKey(File(stored.privateKeyPath).readText()))
    }

    @Test
    fun `generating without a name still produces a named key`() = runTest(main) {
        viewModel.generate("   ").join()

        val state = viewModel.state.first { it.keys.isNotEmpty() }
        assertTrue(state.keys.single().name.startsWith("generated-"))
    }

    @Test
    fun `importing a pasted key adds it`() = runTest(main) {
        viewModel.import("id_ed25519", UNENCRYPTED_PEM).join()

        val state = viewModel.state.first { it.message == "Added id_ed25519" && it.keys.any { key -> key.name == "id_ed25519" } }
        assertEquals(listOf("id_ed25519"), state.keys.map { it.name })
        assertEquals("Added id_ed25519", state.message)
    }

    @Test
    fun `loading an unencrypted detail asynchronously exposes the complete public key`() = runTest(main) {
        viewModel.import("id_ed25519", UNENCRYPTED_PEM).join()
        val row = viewModel.state.first { it.keys.isNotEmpty() }.keys.single()

        viewModel.loadPublicKey(row.id).join()

        val loaded = viewModel.state.value.keys.single()
        assertFalse(loaded.publicKeyLoading)
        assertEquals(SshKeyMaterial.publicKeyLine(UNENCRYPTED_PEM), loaded.publicKey)
        assertNull(loaded.publicKeyError)
    }

    @Test
    fun `loading a missing detail file leaves loading and reports a useful error`() = runTest(main) {
        val id = db.sshKeyDao().insert(
            com.pocketshell.core.storage.entity.SshKeyEntity(
                name = "missing",
                privateKeyPath = File(temporaryFolder.root, "missing-key").absolutePath,
            ),
        )
        val row = viewModel.state.first { it.keys.any { key -> key.id == id } }
            .keys.single()

        viewModel.loadPublicKey(row.id)

        val failed = viewModel.state.first {
            it.keys.single().publicKeyError != null && !it.keys.single().publicKeyLoading
        }.keys.single()
        assertEquals("The private key file is missing", failed.publicKeyError)
        assertNull(failed.publicKey)
    }

    @Test
    fun `an encrypted key is added and defers passphrase entry until needed`() = runTest(main) {
        viewModel.import("locked", ENCRYPTED_PEM).join()

        val state = viewModel.state.first { it.message != null }
        assertTrue(state.message!!.contains("passphrase"))
        val stored = db.sshKeyDao().getAll().first().single()
        assertEquals("locked", stored.name)
        assertTrue(stored.hasPassphrase)
    }

    @Test
    fun `passphrase fallback rejects a non-empty but invalid passphrase`() = runTest(main) {
        viewModel.import("locked", ENCRYPTED_PEM).join()
        val row = viewModel.state.first { it.keys.isNotEmpty() }.keys.single()
        var success = true
        var error: String? = null

        viewModel.unlockWithPassphrase(row.id, "wrong".toCharArray()) { unlocked, detail ->
            success = unlocked
            error = detail
        }.join()

        assertFalse(success)
        assertEquals("Could not unlock that key. Check the passphrase and try again.", error)
    }

    @Test
    fun `text that is not a key surfaces the store's explanation`() = runTest(main) {
        viewModel.import("notes", "hello").join()

        val state = viewModel.state.first { it.message != null }
        assertTrue(state.message!!.contains("PRIVATE KEY"))
        assertTrue(db.sshKeyDao().getAll().first().isEmpty())
    }

    @Test
    fun `deleting a key removes the row and its file`() = runTest(main) {
        viewModel.generate("doomed").join()
        val row = viewModel.state.first { it.keys.isNotEmpty() }.keys.single()
        val path = db.sshKeyDao().getById(row.id)!!.privateKeyPath

        viewModel.delete(row.id).join()

        val state = viewModel.state.value
        assertEquals("Deleted doomed", state.message)
        assertTrue(db.sshKeyDao().getAll().first().isEmpty())
        assertTrue(!File(path).exists())
    }

    @Test
    fun `dismissing the message clears it`() = runTest(main) {
        viewModel.import("notes", "hello").join()
        viewModel.state.first { it.message != null }

        viewModel.clearMessage()

        assertNull(viewModel.state.value.message)
    }

    private companion object {
        val UNENCRYPTED_PEM: String by lazy {
            SshKeyMaterial.generatePrivateKeyPem()
        }

        val ENCRYPTED_PEM: String by lazy {
            SshKeyMaterial.generatePrivateKeyPem(
                type = SshKeyGenerationType.RSA,
                passphrase = "test passphrase".toCharArray(),
            )
        }
    }
}
