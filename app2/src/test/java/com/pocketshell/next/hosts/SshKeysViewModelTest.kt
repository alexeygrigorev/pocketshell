package com.pocketshell.next.hosts

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
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
        viewModel = SshKeysViewModel(db.sshKeyDao(), keyStore)
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `generating a key adds it to the list with a usable file on disk`() = runTest {
        viewModel.generate("laptop")

        val state = viewModel.state.first { it.keys.isNotEmpty() }
        assertEquals(listOf("laptop"), state.keys.map { it.name })
        assertEquals("Generated laptop", state.message)

        val stored = db.sshKeyDao().getAll().first().single()
        assertTrue(File(stored.privateKeyPath).isFile)
        assertTrue(SshKeyMaterial.looksLikePrivateKey(File(stored.privateKeyPath).readText()))
    }

    @Test
    fun `generating without a name still produces a named key`() = runTest {
        viewModel.generate("   ")

        val state = viewModel.state.first { it.keys.isNotEmpty() }
        assertTrue(state.keys.single().name.startsWith("generated-"))
    }

    @Test
    fun `importing a pasted key adds it`() = runTest {
        viewModel.import("id_ed25519", UNENCRYPTED_PEM)

        val state = viewModel.state.first { it.keys.isNotEmpty() }
        assertEquals(listOf("id_ed25519"), state.keys.map { it.name })
        assertEquals("Added id_ed25519", state.message)
    }

    /**
     * The refusal has to be legible in the UI, because the user's next move
     * (decrypt the key on their computer, or generate a new one) depends on
     * knowing why it was refused.
     */
    @Test
    fun `an encrypted key surfaces the store's explanation and adds nothing`() = runTest {
        viewModel.import("locked", ENCRYPTED_PEM)

        val state = viewModel.state.first { it.message != null }
        assertTrue(state.message!!.contains("passphrase-protected"))
        assertTrue(db.sshKeyDao().getAll().first().isEmpty())
    }

    @Test
    fun `text that is not a key surfaces the store's explanation`() = runTest {
        viewModel.import("notes", "hello")

        val state = viewModel.state.first { it.message != null }
        assertTrue(state.message!!.contains("PRIVATE KEY"))
        assertTrue(db.sshKeyDao().getAll().first().isEmpty())
    }

    @Test
    fun `deleting a key removes the row and its file`() = runTest {
        viewModel.generate("doomed")
        val row = viewModel.state.first { it.keys.isNotEmpty() }.keys.single()
        val path = db.sshKeyDao().getById(row.id)!!.privateKeyPath

        viewModel.delete(row.id)

        val state = viewModel.state.first { it.keys.isEmpty() && it.message != null }
        assertEquals("Deleted doomed", state.message)
        assertTrue(!File(path).exists())
    }

    @Test
    fun `dismissing the message clears it`() = runTest {
        viewModel.import("notes", "hello")
        viewModel.state.first { it.message != null }

        viewModel.clearMessage()

        assertNull(viewModel.state.value.message)
    }

    private companion object {
        val UNENCRYPTED_PEM: String = """
            -----BEGIN OPENSSH PRIVATE KEY-----
            b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAAB
            -----END OPENSSH PRIVATE KEY-----
        """.trimIndent()

        val ENCRYPTED_PEM: String = """
            -----BEGIN ENCRYPTED PRIVATE KEY-----
            AAAA
            -----END ENCRYPTED PRIVATE KEY-----
        """.trimIndent()
    }
}
