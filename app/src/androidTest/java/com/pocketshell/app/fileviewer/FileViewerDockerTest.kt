package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Base64
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseTarget
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

    /**
     * App-wide warm lease the viewer borrows from (issue #697). [handshakeCount]
     * tracks real cold SSH handshakes: a viewer open on a host whose lease is
     * already warm must NOT advance it (no per-open ~3-4s handshake).
     */
    private lateinit var leasing: CountingLeaseManager

    @Before
    fun setUp(): Unit = runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        leasing = CountingLeaseManager()
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
                    // Sweep the review-submit inbox so a rerun starts clean.
                    runCatching { session.exec("rm -rf \"\$HOME/inbox/pocketshell/reviews\"") }
                }
            }
        }
        runCatching { keyFile.delete() }
        runCatching { leasing.manager.close() }
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
                hostId = TEST_HOST_ID,
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
                    leasing.manager,
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
                hostId = TEST_HOST_ID,
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
                    leasing.manager,
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
                hostId = TEST_HOST_ID,
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
                    leasing.manager,
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

    @Test
    fun playsRemoteAudioFromFixture(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val wavPath = "/tmp/issue499-clip-$suffix.wav"

        // Seed a real, valid PCM WAV (silent) so MediaPlayer prepares it with a
        // platform codec — no third-party audio lib involved.
        val wavBase64 = Base64.encodeToString(makeWavBytes(millis = 800), Base64.NO_WRAP)
        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec(
                    "printf '%s' '$wavBase64' | base64 -d > '$wavPath'",
                )
                assertEquals("seed wav exit", 0, exit.exitCode)
                seededPaths += wavPath
            } ?: error("could not connect to seed fixture audio")
        }

        composeRule.setContent {
            FileViewerScreen(
                hostId = TEST_HOST_ID,
                hostName = "agents",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                remotePath = wavPath,
                cwd = null,
                onBack = {},
                viewModel = FileViewerViewModel(
                    InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                    leasing.manager,
                ),
            )
        }

        // The audio panel renders with its play/pause control and seekbar.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(FILE_VIEWER_AUDIO_TAG)
        }
        composeRule.onNodeWithTag(FILE_VIEWER_AUDIO_TAG).assertExists()
        composeRule.onNodeWithTag(FILE_VIEWER_AUDIO_SEEKBAR_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue499-audio-ready")

        // Tap play, then verify it reaches a started/playing state (the
        // pause glyph "❚❚" appears once playback starts).
        composeRule.onNodeWithTag(FILE_VIEWER_AUDIO_PLAY_PAUSE_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 15_000) {
            composeRule.onAllNodesWithText("❚❚").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("❚❚").assertExists()
        WalkthroughScreenshotArtifacts.capture("issue499-audio-playing")

        // Scrub the seekbar to seek; the player accepts the seek without error
        // (the audio panel is still shown, not the can't-preview state).
        composeRule.onNodeWithTag(FILE_VIEWER_AUDIO_SEEKBAR_TAG)
            .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) {
                it(400f)
            }
        composeRule.waitForIdle()
        composeRule.onNodeWithTag(FILE_VIEWER_AUDIO_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue499-audio-after-seek")
    }

    @Test
    fun opensAFileReusingTheWarmLeaseInsteadOfHandshakingAgain(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val textPath = "/tmp/issue697-viewer-$suffix.txt"
        val textBody = "issue #697 — file open reuses the warm transport\nno per-open handshake\n"
        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec("cat > '$textPath' <<'PSEOF'\n$textBody\nPSEOF")
                assertEquals("seed text exit", 0, exit.exitCode)
                seededPaths += textPath
            } ?: error("could not connect to seed fixture file")
        }

        // Pre-warm: a sibling screen (session/folder/tmux/explorer) already holds
        // a live lease for this host, keyed IDENTICALLY to what the viewer uses.
        val warmTarget = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                credentialId = "$TEST_HOST_ID:${keyFile.absolutePath}",
                knownHostsId = "accept-all",
            ),
            key = SshKey.Path(keyFile),
            passphrase = null,
            knownHosts = KnownHostsPolicy.AcceptAll,
        )
        val warmLease = withTimeout(30_000) { leasing.manager.acquire(warmTarget).getOrThrow() }
        val afterWarm = leasing.handshakeCount.get()
        assertEquals("pre-warm dials exactly one handshake", 1, afterWarm)

        // Open the file in the viewer; it must borrow the warm transport.
        composeRule.setContent {
            FileViewerScreen(
                hostId = TEST_HOST_ID,
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
                    leasing.manager,
                ),
            )
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(FILE_VIEWER_TEXT_TAG)
        }
        composeRule.onNodeWithTag(FILE_VIEWER_TEXT_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue697-viewer-warm-lease-reuse")

        // The viewer rode the warm transport — NO new ~3-4s handshake.
        assertEquals(
            "file open must reuse the warm lease, not handshake again",
            afterWarm,
            leasing.handshakeCount.get(),
        )
        warmLease.release()
    }

    @Test
    fun submitsAReviewYamlToTheReviewsInboxOverTheReusedLease(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val srcPath = "/tmp/issue714-review-$suffix.kt"
        val srcBody = "val x = doThing(y)\nreturn null\n"
        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec("cat > '$srcPath' <<'PSEOF'\n$srcBody\nPSEOF")
                assertEquals("seed source exit", 0, exit.exitCode)
                seededPaths += srcPath
                // Start from a clean reviews inbox so we can assert on the one file.
                session.exec("rm -rf \"\$HOME/inbox/pocketshell/reviews\"")
            } ?: error("could not connect to seed fixture file")
        }

        val viewModel = FileViewerViewModel(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            leasing.manager,
        )
        // Issue #763: capture the "Attach to current session" prompt routed up.
        val attachedPrompts = mutableListOf<String>()
        composeRule.setContent {
            FileViewerScreen(
                hostId = TEST_HOST_ID,
                hostName = "agents",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                remotePath = srcPath,
                cwd = null,
                onBack = {},
                onAttachReviewToSession = { prompt -> attachedPrompts += prompt },
                viewModel = viewModel,
            )
        }

        // The text view renders, then we enter review mode and add a line +
        // file comment (driven through the ViewModel — the same state the gutter
        // tap writes to), and submit through the real tray Submit button.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(FILE_VIEWER_TEXT_TAG)
        }
        composeRule.runOnUiThread {
            viewModel.toggleReviewMode()
            viewModel.setLineComment(1, "this allocation is on the hot path")
            viewModel.setFileComment("overall structure is good")
        }
        composeRule.waitForIdle()

        // Open the pending tray and submit.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTagExists(FILE_VIEWER_REVIEW_TRAY_TAG)
        }
        composeRule.onNodeWithTag(FILE_VIEWER_REVIEW_TRAY_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTagExists(FILE_VIEWER_REVIEW_SUBMIT_TAG)
        }
        composeRule.onNodeWithTag(FILE_VIEWER_REVIEW_SUBMIT_TAG).performClick()

        // The pending set clears once the SSH write lands.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            !viewModel.reviewState.value.hasPending
        }

        // Issue #763: the post-Submit confirmation sheet surfaces the saved path
        // (copyable) and the "Attach to current session" action.
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithTagExists(FILE_VIEWER_REVIEW_SAVED_SHEET_TAG)
        }
        composeRule.onNodeWithTag(FILE_VIEWER_REVIEW_SAVED_PATH_TAG, useUnmergedTree = true)
            .assertIsDisplayed()
        composeRule.onNodeWithTag(FILE_VIEWER_REVIEW_ATTACH_TAG).assertIsDisplayed()
        WalkthroughScreenshotArtifacts.capture("issue763-review-saved-sheet")

        // Read the YAML back off the host and prove it parses as a
        // pocketshell_review with the expected fields. Both the name and the
        // body are read in ONE session so a sibling shard sharing this fixture
        // can't slip a write in between the ls and the cat.
        val (name, yaml) = withTimeout(20_000) {
            connect()?.use { session ->
                val ls = session.exec("ls \"\$HOME/inbox/pocketshell/reviews/\"")
                assertEquals("reviews dir listing exit", 0, ls.exitCode)
                val n = ls.stdout.lineSequence().map { it.trim() }
                    .firstOrNull { it.endsWith(".yaml") }
                    ?: error("no .yaml landed in the reviews inbox; ls=\n${ls.stdout}")
                val cat = session.exec("cat \"\$HOME/inbox/pocketshell/reviews/$n\"")
                assertEquals("cat review yaml exit", 0, cat.exitCode)
                n to cat.stdout
            } ?: error("could not connect to read the review yaml")
        }
        assertTrue("YAML must be a pocketshell_review, was:\n$yaml", yaml.contains("type: pocketshell_review"))
        assertTrue(yaml.contains("host: agents"))
        assertTrue(yaml.contains("file: $srcPath"))
        assertTrue(yaml.contains("this allocation is on the hot path"))
        assertTrue(yaml.contains("overall structure is good"))
        assertTrue("YAML must carry the verbatim line code, was:\n$yaml", yaml.contains("val x = doThing(y)"))

        // Issue #763: the surfaced saved path is the absolute one that was just
        // written (it ends with the yaml name read off the host), and "Attach to
        // current session" routes a prompt that references that exact path.
        val surfacedPath = composeRule.onNodeWithTag(FILE_VIEWER_REVIEW_SAVED_PATH_TAG, useUnmergedTree = true)
            .fetchSemanticsNode()
            .config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)
            ?.joinToString("") { it.text }
            ?: error("saved path node had no text")
        assertTrue(
            "surfaced path must end with the written yaml name, was: $surfacedPath",
            surfacedPath.endsWith("/reviews/$name"),
        )
        composeRule.onNodeWithTag(FILE_VIEWER_REVIEW_ATTACH_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) { attachedPrompts.isNotEmpty() }
        assertEquals(reviewAttachPrompt(surfacedPath), attachedPrompts.single())
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

    /**
     * Build a minimal valid PCM WAV of [millis] of silence (16-bit mono, 8 kHz)
     * so MediaPlayer can prepare it with a platform codec — no third-party
     * audio library involved.
     */
    private fun makeWavBytes(millis: Int): ByteArray {
        val sampleRate = 8000
        val numSamples = sampleRate * millis / 1000
        val dataSize = numSamples * 2
        val out = ByteArrayOutputStream()
        fun writeIntLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF)
            out.write((v shr 24) and 0xFF)
        }
        fun writeShortLE(v: Int) {
            out.write(v and 0xFF)
            out.write((v shr 8) and 0xFF)
        }
        out.write("RIFF".toByteArray())
        writeIntLE(36 + dataSize)
        out.write("WAVE".toByteArray())
        out.write("fmt ".toByteArray())
        writeIntLE(16)
        writeShortLE(1)
        writeShortLE(1)
        writeIntLE(sampleRate)
        writeIntLE(sampleRate * 2)
        writeShortLE(2)
        writeShortLE(16)
        out.write("data".toByteArray())
        writeIntLE(dataSize)
        repeat(dataSize) { out.write(0) }
        return out.toByteArray()
    }

    private companion object {
        /**
         * Stable host id for the viewer's lease key (issue #697). The viewer
         * keys its lease as `"$hostId:$keyPath"`; the warm-reuse test pre-warms a
         * sibling lease with this same id so the pool hands back the SAME warm
         * transport.
         */
        const val TEST_HOST_ID: Long = 497L
    }
}

/**
 * Small extension so [waitUntil] can poll for a node tag without throwing
 * mid-poll when the tree hasn't settled yet.
 */
private fun ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
