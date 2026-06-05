package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Base64
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Connected Docker test for the in-app file viewer (issue #497).
 *
 * Seeds a real PNG and a text file on the deterministic `agents` fixture
 * (host port `2222`, already wired into the CI emulator job), then drives
 * [FileViewerScreen] against the live SSH session and asserts the image
 * view and text view render. Screenshots of both are captured under the
 * walkthrough artifact directory for reviewer inspection.
 */
@RunWith(AndroidJUnit4::class)
class FileViewerDockerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val seededPaths = mutableListOf<String>()

    @Before
    fun setUp(): Unit = runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue497-file-viewer-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        waitForSshFixtureReady(sshKey)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        if (seededPaths.isNotEmpty()) {
            withTimeout(15_000) {
                connect()?.use { session ->
                    for (path in seededPaths) {
                        runCatching { session.exec("rm -f '$path'") }
                    }
                }
            }
        }
        runCatching { keyFile.delete() }
    }

    @Test
    fun viewsRemotePngAndTextFromFixture(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val pngPath = "/tmp/issue497-image-$suffix.png"
        val textPath = "/tmp/issue497-notes-$suffix.txt"
        val textBody = "PocketShell file viewer issue #497\nline two\nUTF-8 café ☕\n"

        // Seed: a real PNG (base64-decoded server-side) and a text file.
        val pngBase64 = Base64.encodeToString(makePngBytes(), Base64.NO_WRAP)
        withTimeout(20_000) {
            connect()?.use { session ->
                val pngExit = session.exec(
                    "printf '%s' '$pngBase64' | base64 -d > '$pngPath'",
                )
                assertEquals("seed png exit", 0, pngExit.exitCode)
                seededPaths += pngPath
                val textExit = session.exec("cat > '$textPath' <<'PSEOF'\n$textBody\nPSEOF")
                assertEquals("seed text exit", 0, textExit.exitCode)
                seededPaths += textPath
            } ?: error("could not connect to seed fixture files")
        }

        // ---- Image preview ----
        composeRule.setContent {
            FileViewerScreen(
                hostName = "agents",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                remotePath = pngPath,
                cwd = null,
                onBack = {},
                viewModel = FileViewerViewModel(
                    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                ),
            )
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(FILE_VIEWER_IMAGE_TAG)
        }
        composeRule.onNodeWithTag(FILE_VIEWER_IMAGE_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue497-file-viewer-image")
    }

    @Test
    fun viewsRemoteTextFromFixture(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val textPath = "/tmp/issue497-text-$suffix.txt"
        val textBody = "PocketShell file viewer issue #497\nrelative-path + size-cap covered by unit tests\n"
        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec("cat > '$textPath' <<'PSEOF'\n$textBody\nPSEOF")
                assertEquals("seed text exit", 0, exit.exitCode)
                seededPaths += textPath
            } ?: error("could not connect to seed fixture file")
        }

        composeRule.setContent {
            FileViewerScreen(
                hostName = "agents",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                remotePath = textPath,
                cwd = null,
                onBack = {},
                viewModel = FileViewerViewModel(
                    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                ),
            )
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(FILE_VIEWER_TEXT_TAG)
        }
        composeRule.onNodeWithTag(FILE_VIEWER_TEXT_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue497-file-viewer-text")
    }

    @Test
    fun viewsRemotePdfPagesFromFixture(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val pdfPath = "/tmp/issue498-doc-$suffix.pdf"

        // Seed a real 3-page PDF (base64-decoded server-side).
        val pdfBase64 = Base64.encodeToString(makePdfBytes(pages = 3), Base64.NO_WRAP)
        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec(
                    "printf '%s' '$pdfBase64' | base64 -d > '$pdfPath'",
                )
                assertEquals("seed pdf exit", 0, exit.exitCode)
                seededPaths += pdfPath
            } ?: error("could not connect to seed fixture pdf")
        }

        composeRule.setContent {
            FileViewerScreen(
                hostName = "agents",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                remotePath = pdfPath,
                cwd = null,
                onBack = {},
                viewModel = FileViewerViewModel(
                    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                ),
            )
        }

        // Page 1 renders.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(FILE_VIEWER_PDF_PAGE_TAG)
        }
        composeRule.onNodeWithTag(FILE_VIEWER_PDF_PAGE_TAG).assertExists()
        composeRule.onNodeWithText("Page 1 / 3").assertExists()
        WalkthroughScreenshotArtifacts.capture("issue498-pdf-page1")

        // Page through to page 2 then page 3.
        composeRule.onNodeWithTag(FILE_VIEWER_PDF_NEXT_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Page 2 / 3").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(FILE_VIEWER_PDF_PAGE_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue498-pdf-page2")

        composeRule.onNodeWithTag(FILE_VIEWER_PDF_NEXT_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Page 3 / 3").fetchSemanticsNodes().isNotEmpty()
        }
        WalkthroughScreenshotArtifacts.capture("issue498-pdf-page3")

        // Back to page 1 via Prev.
        composeRule.onNodeWithTag(FILE_VIEWER_PDF_PREV_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("Page 2 / 3").fetchSemanticsNodes().isNotEmpty()
        }
    }

    private suspend fun connect() = SshConnection.connect(
        host = DEFAULT_HOST,
        port = DEFAULT_PORT,
        user = DEFAULT_USER,
        key = sshKey,
        knownHosts = KnownHostsPolicy.AcceptAll,
        timeoutMs = 10_000,
    ).getOrNull()

    /** Encode a small solid-colour bitmap to PNG bytes for seeding. */
    private fun makePngBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(34, 211, 238)) // PocketShell accent.
        val out = ByteArrayOutputStream()
        assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, out))
        bmp.recycle()
        return out.toByteArray()
    }

    /**
     * Build a real multi-page PDF with [android.graphics.pdf.PdfDocument] so the
     * connected test exercises the exact PdfRenderer decode path with a genuine
     * PDF byte stream (no third-party PDF library involved).
     */
    private fun makePdfBytes(pages: Int): ByteArray {
        val doc = PdfDocument()
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 48f
            isAntiAlias = true
        }
        for (p in 1..pages) {
            val pageInfo = PdfDocument.PageInfo.Builder(612, 792, p).create()
            val page = doc.startPage(pageInfo)
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawText("PocketShell PDF page $p of $pages", 60f, 120f, paint)
            doc.finishPage(page)
        }
        val out = ByteArrayOutputStream()
        doc.writeTo(out)
        doc.close()
        return out.toByteArray()
    }
}

/**
 * Small extension so [waitUntil] can poll for a node tag without throwing
 * mid-poll when the tree hasn't settled yet.
 */
private fun ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
