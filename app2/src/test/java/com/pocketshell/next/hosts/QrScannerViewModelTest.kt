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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The scan state machine end to end, driven by feeding it exactly the strings a
 * camera would decode.
 *
 * Nothing here mocks the importer or the DB: a "scan" that reported success
 * without a row in `hosts` is precisely the failure a scanner test is worth
 * having, so the assertion is always the stored row.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class QrScannerViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var keyStore: SshKeyStore
    private lateinit var viewModel: QrScannerViewModel

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
        viewModel = QrScannerViewModel(
            HostImporter(db.hostDao(), db.sshKeyDao(), keyStore, UnconfinedTestDispatcher()),
        )
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `scanning a single-part host QR imports it`() = runTest {
        keyStore.generateKey("k")
        viewModel.onPermissionGranted()

        viewModel.onScanned(QrChunkCodec.encode(payload("hetzner")).single())

        assertEquals(QrScannerViewModel.State.Imported("Imported hetzner"), awaitTerminal())
        assertEquals("hetzner", db.hostDao().getAll().first().single().name)
    }

    @Test
    fun `a multi-part payload reports progress and imports once complete`() = runTest {
        keyStore.generateKey("k")
        viewModel.onPermissionGranted()
        // Pad the host name so the payload genuinely needs two QRs.
        val parts = QrChunkCodec.encode(payload("h".repeat(QrChunkCodec.CHUNK_SIZE)))
        assertTrue("fixture must be multi-part", parts.size > 1)

        viewModel.onScanned(parts.first())

        val progress = viewModel.state.value as QrScannerViewModel.State.Scanning
        assertEquals(1, progress.scanned)
        assertEquals(parts.size, progress.total)

        parts.drop(1).forEach(viewModel::onScanned)

        assertTrue(awaitTerminal() is QrScannerViewModel.State.Imported)
        assertEquals(1, db.hostDao().getAll().first().size)
    }

    @Test
    fun `a QR that is not a PocketShell code fails without importing`() = runTest {
        viewModel.onPermissionGranted()

        viewModel.onScanned("https://example.com")

        val state = viewModel.state.value as QrScannerViewModel.State.Failed
        assertEquals("That QR is not a PocketShell host code", state.message)
        assertTrue(db.hostDao().getAll().first().isEmpty())
    }

    @Test
    fun `a checksum-corrupted chunk fails instead of importing garbage`() = runTest {
        viewModel.onPermissionGranted()
        val envelope = QrChunkCodec.encode(payload("hetzner")).single()
        val tampered = envelope.dropLast(4) + "AAAA"

        viewModel.onScanned(tampered)

        assertTrue(viewModel.state.value is QrScannerViewModel.State.Failed)
        assertTrue(db.hostDao().getAll().first().isEmpty())
    }

    @Test
    fun `a frame arriving after a terminal state cannot overwrite it`() = runTest {
        keyStore.generateKey("k")
        viewModel.onPermissionGranted()
        val envelope = QrChunkCodec.encode(payload("hetzner")).single()

        viewModel.onScanned(envelope)
        val terminal = awaitTerminal()
        // The camera keeps firing for a few frames after the decode.
        viewModel.onScanned(envelope)
        viewModel.onScanned("https://example.com")

        assertEquals(terminal, viewModel.state.value)
        assertEquals(1, db.hostDao().getAll().first().size)
    }

    @Test
    fun `a denied permission is reported with whether it can be re-requested`() {
        viewModel.onPermissionDenied(canRetry = false)

        assertEquals(
            QrScannerViewModel.State.PermissionDenied(canRetry = false),
            viewModel.state.value,
        )
    }

    @Test
    fun `retry returns to the permission request and clears partial scans`() = runTest {
        viewModel.onPermissionGranted()
        val parts = QrChunkCodec.encode(payload("h".repeat(QrChunkCodec.CHUNK_SIZE)))
        viewModel.onScanned(parts.first())

        viewModel.retry()
        viewModel.onPermissionGranted()

        // A fresh scan starts at zero rather than resuming the abandoned one.
        assertEquals(QrScannerViewModel.State.Scanning(), viewModel.state.value)
    }

    /** The camera-unavailable fallback: a payload read out of a picked image. */
    @Test
    fun `a payload picked from an image imports the same way`() = runTest {
        keyStore.generateKey("k")

        viewModel.onPayloadPicked(QrChunkCodec.encode(payload("from-file")).single())

        assertTrue(awaitTerminal() is QrScannerViewModel.State.Imported)
        assertEquals("from-file", db.hostDao().getAll().first().single().name)
    }

    /**
     * The import runs on Room's executor, so `Importing` is a real intermediate
     * state, not a formality. Awaiting the terminal state is the machine's own
     * completion signal — the screen navigates on exactly this transition.
     */
    private suspend fun awaitTerminal(): QrScannerViewModel.State =
        viewModel.state.first { it !is QrScannerViewModel.State.Importing }

    private fun payload(name: String): String = SshImportPayloadCodec.encode(
        SshImportConfig(
            name = name,
            host = "135.181.114.209",
            port = 22,
            username = "alexey",
            auth = SshImportAuth.KeyReference("k"),
        ),
    )
}
