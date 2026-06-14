package com.pocketshell.app.fileviewer

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.RemoteListing
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #764 — submit an annotated image over the REUSED viewer SSH lease.
 *
 * JVM-level (no emulator): a fake [SshSession] returns real PNG bytes for the
 * fetch (so the viewer routes to [FileViewerUiState.Image]) and captures the
 * `mkdir -p` + the two `uploadStream` writes. The test proves the submit:
 *  - writes the flattened `.png` AND the `pocketshell_annotation` `.yaml`
 *    sidecar to `<home>/inbox/pocketshell/annotations/` with a shared stem;
 *  - reuses the warm viewer lease (no fresh connection);
 *  - clears the pending annotations + emits Success on success;
 *  - KEEPS the annotations + emits Failure on failure (nothing lost).
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class FileViewerAnnotationSubmitTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var pngBytes: ByteArray

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        pngBytes = makePng(8, 8)
    }

    @Test
    fun submitWritesPngAndYamlSidecarToTheAnnotationsInboxOverTheReusedLease() = runBlocking {
        val session = RecordingImageSession(pngBytes)
        val connector = CountingConnector(session)
        val leaseManager = SshLeaseManager(connector = connector, idleTtlMillis = 30_000L)
        val vm = FileViewerViewModel(context, leaseManager)

        vm.bind(request("/srv/shot.png"))
        vm.state.awaitImage()
        val openHandshakes = connector.connectCount

        vm.toggleAnnotationMode()
        vm.setAnnotationTool(AnnotationTool.Pen)
        vm.addAnnotation(
            Annotation.Freehand(
                points = listOf(ImagePoint(1f, 1f), ImagePoint(5f, 5f)),
                colorArgb = ImageAnnotationState.DEFAULT_COLOR_ARGB,
                strokeWidthPx = 2f,
            ),
        )
        vm.setAnnotationNote("circled the misaligned button")

        val event = vm.submitAndAwaitEvent("hetzner")

        assertEquals("expected exactly one mkdir -p", 1, session.mkdirCommands.size)
        assertTrue(
            "mkdir must target the annotations inbox, was ${session.mkdirCommands.first()}",
            session.mkdirCommands.first().contains("/home/tester/inbox/pocketshell/annotations"),
        )

        // Two uploads: the PNG and the YAML sidecar, sharing a stem.
        assertEquals("expected the PNG + the YAML sidecar", 2, session.uploads.size)
        val png = session.uploads.first { it.remotePath.endsWith(".png") }
        val yaml = session.uploads.first { it.remotePath.endsWith(".yaml") }

        assertTrue(
            "PNG must land in the annotations inbox, was ${png.remotePath}",
            png.remotePath.startsWith("/home/tester/inbox/pocketshell/annotations/"),
        )
        assertTrue(
            "PNG name must carry the sanitised source stem, was ${png.name}",
            png.name.startsWith("shot-") && png.name.endsWith(".png"),
        )
        // The PNG and the YAML share the same stem (co-located pair).
        val pngStem = png.name.removeSuffix(".png")
        val yamlStem = yaml.name.removeSuffix(".yaml")
        assertEquals("PNG and YAML must share a stem", pngStem, yamlStem)

        // The uploaded PNG body really is PNG (magic header).
        assertTrue("PNG body must start with the PNG magic header", png.rawBody.size >= 8 && png.rawBody[0] == 0x89.toByte())

        // The sidecar is the pocketshell_annotation YAML referencing the PNG.
        val body = yaml.body
        assertTrue("sidecar must be pocketshell_annotation, was:\n$body", body.contains("type: pocketshell_annotation"))
        assertTrue(body.contains("host: hetzner"))
        assertTrue(body.contains("source_file: /srv/shot.png"))
        assertTrue("sidecar image must reference the written PNG path", body.contains("image: ${png.remotePath}"))
        assertTrue(body.contains("circled the misaligned button"))

        // Reused the warm lease — no extra handshake beyond the viewer open.
        assertEquals("submit must reuse the warm lease, not dial again", openHandshakes, connector.connectCount)
        assertFalse("submit must NOT close the shared transport", session.closed)

        assertTrue("expected Success, was $event", event is AnnotationSubmitEvent.Success)
        val success = event as AnnotationSubmitEvent.Success
        assertEquals("hetzner", success.host)
        assertTrue(success.remotePath.endsWith(".png"))

        val cleared = vm.annotationState.value
        assertFalse("annotations must be cleared on success", cleared.hasAnnotations)
        assertTrue("annotate mode stays active after submit", cleared.active)
        assertFalse("submitting flag must reset", cleared.submitting)

        leaseManager.close()
    }

    @Test
    fun submitFailureKeepsTheAnnotationsAndReportsTheError() = runBlocking {
        val session = RecordingImageSession(pngBytes, failUpload = true)
        val leaseManager = SshLeaseManager(connector = CountingConnector(session), idleTtlMillis = 30_000L)
        val vm = FileViewerViewModel(context, leaseManager)

        vm.bind(request("/srv/shot.png"))
        vm.state.awaitImage()

        vm.toggleAnnotationMode()
        vm.addAnnotation(
            Annotation.Arrow(
                start = ImagePoint(0f, 0f),
                end = ImagePoint(4f, 4f),
                colorArgb = ImageAnnotationState.DEFAULT_COLOR_ARGB,
                strokeWidthPx = 2f,
            ),
        )

        val event = vm.submitAndAwaitEvent("hetzner")

        assertTrue("expected Failure, was $event", event is AnnotationSubmitEvent.Failure)
        val kept = vm.annotationState.value
        assertTrue("annotations must survive a failed submit", kept.hasAnnotations)
        assertFalse("submitting flag must reset after failure", kept.submitting)

        leaseManager.close()
    }

    @Test
    fun submitIsANoOpWithNoAnnotations() = runBlocking {
        val session = RecordingImageSession(pngBytes)
        val leaseManager = SshLeaseManager(connector = CountingConnector(session), idleTtlMillis = 30_000L)
        val vm = FileViewerViewModel(context, leaseManager)

        vm.bind(request("/srv/shot.png"))
        vm.state.awaitImage()
        vm.toggleAnnotationMode()

        vm.submitAnnotation("hetzner")
        delay(100)

        assertEquals("no upload without annotations", 0, session.uploads.size)
        assertEquals("no mkdir without annotations", 0, session.mkdirCommands.size)

        leaseManager.close()
    }

    // --- helpers ------------------------------------------------------------

    private fun makePng(width: Int, height: Int): ByteArray {
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().use { out ->
            bmp.compress(Bitmap.CompressFormat.PNG, 100, out)
            out.toByteArray()
        }
    }

    private suspend fun FileViewerViewModel.submitAndAwaitEvent(host: String): AnnotationSubmitEvent {
        val captured = AtomicReference<AnnotationSubmitEvent?>(null)
        val collector = CoroutineScope(Dispatchers.Main).launch {
            annotationEvents.collect { captured.set(it) }
        }
        yield()
        submitAnnotation(host)
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && captured.get() == null) {
            delay(20)
        }
        collector.cancel()
        assertNotNull("submit never emitted an AnnotationSubmitEvent", captured.get())
        return captured.get()!!
    }

    private suspend fun StateFlow<FileViewerUiState>.awaitImage(): FileViewerUiState.Image {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) {
            val s = value
            if (s is FileViewerUiState.Image) return s
            delay(20)
        }
        error("viewer never reached Image; was $value")
    }

    private fun request(path: String) = FileViewerViewModel.Request(
        hostId = 1L,
        hostname = "10.0.2.2",
        port = 2222,
        username = "tester",
        keyPath = "/tmp/key",
        passphrase = null,
        path = path,
        cwd = null,
    )

    private class CountingConnector(private val session: RecordingImageSession) : SshLeaseConnector {
        var connectCount: Int = 0
        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.success(session)
        }
    }

    data class CapturedUpload(val name: String, val remotePath: String, val rawBody: ByteArray) {
        val body: String get() = rawBody.toString(Charsets.UTF_8)
    }

    private class RecordingImageSession(
        private val pngBytes: ByteArray,
        private val failUpload: Boolean = false,
    ) : SshSession {
        var closed: Boolean = false
        val mkdirCommands = mutableListOf<String>()
        val uploads = mutableListOf<CapturedUpload>()

        override val isConnected: Boolean get() = !closed

        override suspend fun exec(command: String): ExecResult {
            if (command.startsWith("mkdir -p")) {
                mkdirCommands += command
                return ExecResult(stdout = "", stderr = "", exitCode = 0)
            }
            return ExecResult(stdout = "/home/tester\n", stderr = "", exitCode = 0)
        }

        override suspend fun downloadFile(remotePath: String, maxBytes: Long): ByteArray = pngBytes

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String {
            if (failUpload) throw com.pocketshell.core.ssh.SshException("Permission denied")
            uploads += CapturedUpload(name = name, remotePath = remotePath, rawBody = input.readBytes())
            return remotePath
        }

        override suspend fun listDirectory(remotePath: String, maxEntries: Int): RemoteListing = error("not used")
        override fun tail(path: String, onLine: (String) -> Unit): Job = error("not used")
        override fun openLocalPortForward(remoteHost: String, remotePort: Int, localPort: Int): SshPortForward = error("not used")
        override fun startShell(): SshShell = error("not used")
        override suspend fun uploadFile(file: File, remotePath: String): String = error("not used")
        override fun close() { closed = true }
    }
}
