package com.pocketshell.next.hosts

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

    private lateinit var db: AppDatabase
    private lateinit var keyStore: SshKeyStore
    private lateinit var viewModel: SshKeysViewModel
    private val unlocker = object : SshKeyUnlocker {
        override fun rememberPassphrase(keyId: Long, value: CharArray) = Unit
        override fun copyPassphrase(keyId: Long): CharArray? = null
        override fun clearPassphrase(keyId: Long) = Unit
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        keyStore = SshKeyStore(
            File(temporaryFolder.root, "ssh-keys"),
            db.sshKeyDao(),
            UnconfinedTestDispatcher(),
        )
        viewModel = SshKeysViewModel(db.sshKeyDao(), keyStore, unlocker)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `generating a key adds it to the list with a usable file on disk`() = runTest {
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
    fun `generating without a name still produces a named key`() = runTest {
        viewModel.generate("   ").join()

        val state = viewModel.state.first { it.keys.isNotEmpty() }
        assertTrue(state.keys.single().name.startsWith("generated-"))
    }

    @Test
    fun `importing a pasted key adds it`() = runTest {
        viewModel.import("id_ed25519", UNENCRYPTED_PEM).join()

        val state = viewModel.state.first { it.message == "Added id_ed25519" && it.keys.any { key -> key.name == "id_ed25519" } }
        assertEquals(listOf("id_ed25519"), state.keys.map { it.name })
        assertEquals("Added id_ed25519", state.message)
    }

    @Test
    fun `an encrypted key is added and defers passphrase entry until needed`() = runTest {
        viewModel.import("locked", ENCRYPTED_PEM).join()

        val state = viewModel.state.first { it.message != null }
        assertTrue(state.message!!.contains("passphrase"))
        val stored = db.sshKeyDao().getAll().first().single()
        assertEquals("locked", stored.name)
        assertTrue(stored.hasPassphrase)
    }

    @Test
    fun `passphrase fallback rejects a non-empty but invalid passphrase`() = runTest {
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
    fun `text that is not a key surfaces the store's explanation`() = runTest {
        viewModel.import("notes", "hello").join()

        val state = viewModel.state.first { it.message != null }
        assertTrue(state.message!!.contains("PRIVATE KEY"))
        assertTrue(db.sshKeyDao().getAll().first().isEmpty())
    }

    @Test
    fun `deleting a key removes the row and its file`() = runTest {
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
    fun `dismissing the message clears it`() = runTest {
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
