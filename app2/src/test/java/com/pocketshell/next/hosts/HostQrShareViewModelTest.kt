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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [HostQrShareViewModel]: what a host's exported QR actually contains, and what
 * happens when it cannot be built.
 *
 * The security assertion is the important one here — the export must reference
 * the key by name and never carry key material — and it is made on the encoded
 * payload string, which is what a camera pointed at the screen would recover.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class HostQrShareViewModelTest {

    private lateinit var db: AppDatabase
    private var keyId: Long = 0

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        keyId = runBlocking {
            db.sshKeyDao().insert(SshKeyEntity(name = "hetzner-key", privateKeyPath = "/tmp/k"))
        }
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `a host is encoded as a single-part envelope naming its key`() = runTest {
        val hostId = insertHost()

        val state = viewModel(hostId).state.first { it.parts.isNotEmpty() }

        assertEquals("hetzner", state.hostName)
        assertEquals("alexey@135.181.114.209:2222", state.hostSubtitle)
        assertEquals(1, state.parts.size)
        assertFalse(state.hasNext)
        assertFalse(state.hasPrevious)

        val decoded = SshImportPayloadCodec.decode(
            String(QrChunkCodec.decodePart(state.current!!).getOrThrow().chunk, Charsets.UTF_8),
        ).getOrThrow()
        assertEquals("hetzner", decoded.name)
        assertEquals("135.181.114.209", decoded.host)
        assertEquals(2222, decoded.port)
        assertEquals("alexey", decoded.username)
        assertEquals(SshImportAuth.KeyReference("hetzner-key"), decoded.auth)
    }

    /**
     * The stance from `docs/ssh-qr-import.md`: PocketShell's own export never
     * puts a private key on a screen. Asserted on the raw QR text, because that
     * is what anyone with a camera gets.
     */
    @Test
    fun `the exported QR text contains no key material`() = runTest {
        val hostId = insertHost()

        val state = viewModel(hostId).state.first { it.parts.isNotEmpty() }

        val text = state.parts.joinToString("")
        assertTrue(text.contains("pocketshell.qr.v1?"))
        val payload = String(QrChunkCodec.decodePart(state.current!!).getOrThrow().chunk, Charsets.UTF_8)
        assertFalse(payload.contains("privateKeyPem"))
        assertFalse(payload.contains("PRIVATE KEY"))
        assertTrue(payload.contains("keyRef"))
    }

    @Test
    fun `a host whose key row is gone reports why instead of rendering a broken QR`() = runTest {
        val hostId = insertHost()
        // `hosts.keyId` is a real FK, so this state only arises in the window
        // between the screen reading the host and the key being deleted (the
        // cascade then removes the host too, but this read already happened).
        // The PRAGMA reproduces that window deterministically instead of racing
        // for it; the branch it exercises is a genuine nullable lookup, not a
        // hypothetical.
        db.openHelper.writableDatabase.execSQL("PRAGMA foreign_keys = OFF")
        db.hostDao().update(db.hostDao().getById(hostId)!!.copy(keyId = 9999))

        val state = viewModel(hostId).state.first { it.error != null }

        assertTrue(state.error!!.contains("SSH key is missing"))
        assertTrue(state.parts.isEmpty())
    }

    @Test
    fun `a host that no longer exists reports it`() = runTest {
        val state = viewModel(4242).state.first { it.error != null }

        assertEquals("That host no longer exists", state.error)
    }

    @Test
    fun `the view model refuses to be built without a host argument`() {
        val error = runCatching {
            HostQrShareViewModel(db.hostDao(), db.sshKeyDao(), SavedStateHandle())
        }.exceptionOrNull()

        assertNotNull(error)
    }

    private fun viewModel(hostId: Long) = HostQrShareViewModel(
        db.hostDao(),
        db.sshKeyDao(),
        SavedStateHandle(mapOf(Destination.ARG_HOST_ID to hostId)),
    )

    private suspend fun insertHost(): Long = db.hostDao().insert(
        HostEntity(
            name = "hetzner",
            hostname = "135.181.114.209",
            port = 2222,
            username = "alexey",
            keyId = keyId,
        ),
    )
}
