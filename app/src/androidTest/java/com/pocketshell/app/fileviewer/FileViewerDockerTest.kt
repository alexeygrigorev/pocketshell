package com.pocketshell.app.fileviewer

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.RecordingClipboardManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextDecoration
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.proof.signals.FOREIGN_WINDOW_FOCUS_SIGNATURE
import com.pocketshell.app.proof.signals.SyntheticFocusOwnerHarness
import com.pocketshell.app.proof.signals.awaitActivityWindowFocus
import com.pocketshell.app.proof.signals.requirePocketShellFocusAtJourneyBoundary
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.uikit.theme.PocketShellColors
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
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
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val seededPaths = mutableListOf<String>()

    /**
     * App-wide warm lease the viewer borrows from (issue #697). [handshakeCount]
     * tracks real cold SSH handshakes: a viewer open on a host whose lease is
     * already warm must NOT advance it (no per-open ~3-4s handshake).
     */
    private lateinit var leasing: CountingLeaseManager
    private val focusOwner by lazy {
        SyntheticFocusOwnerHarness(
            scenario = composeRule.activityRule.scenario,
            label = "issue-1942 synthetic focus owner",
            timeoutMs = 5_000,
        )
    }

    @Before
    fun setUp(): Unit { runBlocking {
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
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        focusOwner.dismissBestEffort()
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
    } }

    @Test
    fun viewsRemotePngAndTextFromFixture(): Unit { runBlocking {
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
    } }

    /** Issue #1985: an assertion exit cannot leak synthetic focus into the next journey. */
    @Test
    fun failedSyntheticOwnerBodyRestoresFocusBeforeNextImeJourney() {
        composeRule.setContent { Text("issue #1985 focus restoration surface") }
        composeRule.onNodeWithText("issue #1985 focus restoration surface").assertIsDisplayed()
        val injectedFailure = AssertionError("issue1985 injected owner-body failure")
        val harness = SyntheticFocusOwnerHarness(
            scenario = composeRule.activityRule.scenario,
            label = "issue-1985 synthetic focus owner",
            timeoutMs = 5_000,
        )
        val escaped = runCatching {
            harness.withOwner { owner ->
                assertTrue("synthetic owner must remain visible during its body", owner.isShowing)
                WalkthroughScreenshotArtifacts.capture("issue1985-synthetic-focus-owner")
                throw injectedFailure
            }
        }.exceptionOrNull()
        assertTrue("the injected body failure must escape unchanged", escaped === injectedFailure)

        requirePocketShellFocusAtJourneyBoundary(
            scenario = composeRule.activityRule.scenario,
            context = "issue #1985 next IME journey boundary",
            timeoutMs = 1_000,
        )
        WalkthroughScreenshotArtifacts.capture("issue1985-focus-restored-next-ime-boundary")
    }

    @Test
    fun viewsRemoteTextFromFixture(): Unit { runBlocking {
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
    } }

    @Test
    fun viewsRemotePdfPagesFromFixture(): Unit { runBlocking {
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
    } }

    @Test
    fun playsRemoteAudioFromFixture(): Unit { runBlocking {
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
    } }

    @Test
    fun opensAFileReusingTheWarmLeaseInsteadOfHandshakingAgain(): Unit { runBlocking {
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
    } }

    @Test
    fun submitsAReviewYamlToTheReviewsInboxOverTheReusedLease(): Unit { runBlocking {
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
    } }

    /**
     * Issue #1713 — reopening a file whose host content changed must show the
     * FRESH content. Opens a text file (body v1), reads it, mutates it on the
     * host over the same fixture, then reopens the SAME file with the SAME
     * surviving [FileViewerViewModel] (navigate away + back, driven by toggling
     * the screen in/out of composition so the real [FileViewerScreen]
     * `LaunchedEffect` re-fires `bind()` on re-entry). The viewer must reconcile
     * to the new body — before the fix, `bind()` returned early on the identical
     * request and the stale body persisted.
     */
    @Test
    fun reopeningAChangedTextFileShowsTheFreshHostContent(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val textPath = "/tmp/issue1713-notes-$suffix.txt"
        val bodyV1 = "issue #1713 ORIGINAL-BODY-V1\nline two\n"
        val bodyV2 = "issue #1713 CHANGED-ON-HOST-V2\nfresh line\n"
        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec("cat > '$textPath' <<'PSEOF'\n$bodyV1\nPSEOF")
                assertEquals("seed text v1 exit", 0, exit.exitCode)
                seededPaths += textPath
            } ?: error("could not connect to seed fixture file")
        }

        // A single VM instance survives the reopen (as it does when navigation
        // reuses a cached back-stack entry / activity-scoped VM).
        val viewModel = FileViewerViewModel(
            InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
            leasing.manager,
        )
        var showViewer by mutableStateOf(true)
        composeRule.setContent {
            if (showViewer) {
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
                    viewModel = viewModel,
                )
            }
        }

        // v1 renders.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("ORIGINAL-BODY-V1", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("ORIGINAL-BODY-V1", substring = true).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue1713-reopen-before-v1")

        // The host file changes while the viewer is open.
        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec("cat > '$textPath' <<'PSEOF'\n$bodyV2\nPSEOF")
                assertEquals("mutate text v2 exit", 0, exit.exitCode)
            } ?: error("could not connect to mutate fixture file")
        }

        // Reopen: navigate away (remove the screen), then back (re-add) — the VM
        // survives, so this is a reopen of the identical request. The
        // LaunchedEffect re-fires bind() on re-entry.
        composeRule.runOnUiThread { showViewer = false }
        composeRule.waitForIdle()
        composeRule.runOnUiThread { showViewer = true }

        // The reopen must reconcile to the fresh host content.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithText("CHANGED-ON-HOST-V2", substring = true)
                .fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("CHANGED-ON-HOST-V2", substring = true).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue1713-reopen-after-v2")

        // The authoritative viewer state carries the fresh body, and the stale
        // body is gone.
        val shown = viewModel.state.value
        assertTrue(
            "viewer state must be the fresh v2 body, was: $shown",
            shown is FileViewerUiState.TextContent && shown.content.contains("CHANGED-ON-HOST-V2"),
        )
        assertTrue(
            "stale v1 body must be gone after reopen, was: $shown",
            shown is FileViewerUiState.TextContent && !shown.content.contains("ORIGINAL-BODY-V1"),
        )
        assertTrue(
            "the stale v1 body must not be on screen after reopen",
            composeRule.onAllNodesWithText("ORIGINAL-BODY-V1", substring = true)
                .fetchSemanticsNodes().isEmpty(),
        )
    } }

    /**
     * Issue #1714 real File-viewer journey.
     *
     * Provenance is pinned to
     * DataTalksClub/ai-dev-tools-zoomcamp@132e601061ec3bb46c61a4e594a2bdc431754ca2,
     * `01-overview/article.md`, blob
     * 833c847fdd2b94f6ea76d78d8c9ce507e5a67a29, full-file SHA-256
     * 38703da0717d46fb6b61fb242f7a617e754229f526f02488d686e936b9563383,
     * reported 2026-07-22. [ISSUE1714_BODY] embeds the exact unordered and
     * ordered excerpts plus the exact link prose/target under explicitly
     * synthetic list wrappers; it does not claim to embed the whole article.
     *
     * The first rendered and raw-control frames are captured before the first
     * #1714 structural assertion. Therefore this same source-compatible test on
     * untouched base produces the required base-wrong screenshot and then fails
     * at runtime because the complete continued item is not one rendered text
     * node; it does not rely on fixed-only APIs to manufacture a compile RED.
     */
    @Test
    fun moduleOneArticleListsRenderIntactAndContinuedLinkOpensExactUrl(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val markdownPath = "/tmp/issue1714-module1-$suffix.md"
        val recordingClipboard = RecordingClipboardManager()
        val clipboard = object : Clipboard {
            override val nativeClipboard: android.content.ClipboardManager
                get() = recordingClipboard

            override suspend fun getClipEntry(): ClipEntry? =
                recordingClipboard.primaryClip?.let(::ClipEntry)

            override suspend fun setClipEntry(clipEntry: ClipEntry?) {
                if (clipEntry == null) {
                    recordingClipboard.clearPrimaryClip()
                } else {
                    recordingClipboard.setPrimaryClip(clipEntry.clipData)
                }
            }
        }
        withTimeout(20_000) {
            connect()?.use { session ->
                val body = Base64.encodeToString(ISSUE1714_BODY.toByteArray(), Base64.NO_WRAP)
                val exit = session.exec("printf '%s' '$body' | base64 -d > '$markdownPath'")
                assertEquals("seed issue #1714 markdown exit", 0, exit.exitCode)
                seededPaths += markdownPath
            } ?: error("could not connect to seed issue #1714 fixture")
        }

        composeRule.setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                FileViewerScreen(
                    hostId = TEST_HOST_ID,
                    hostName = "agents",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyPath = keyFile.absolutePath,
                    passphrase = null,
                    remotePath = markdownPath,
                    cwd = null,
                    onBack = {},
                    viewModel = FileViewerViewModel(
                        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext,
                        leasing.manager,
                    ),
                )
            }
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTag(FILE_VIEWER_MARKDOWN_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Capture the production rendered observation and raw-source control
        // before the first structural oracle. On base, the rendered frame is
        // visibly wrong and the later exact-node assertion is the behavioral RED.
        composeRule.onNodeWithText(
            "Feature-level - what a change should do",
            substring = true,
        ).performScrollTo()
        WalkthroughScreenshotArtifacts.capture("issue1714-rendered-before-runtime-assertion")
        composeRule.onNodeWithTag(FILE_VIEWER_RENDER_MD_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(FILE_VIEWER_MARKDOWN_TAG)
                .fetchSemanticsNodes().isEmpty()
        }
        composeRule.onNodeWithText(ISSUE1714_BODY).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue1714-raw-source-control")
        composeRule.onNodeWithTag(FILE_VIEWER_RENDER_MD_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 5_000) {
            composeRule.onAllNodesWithTag(FILE_VIEWER_MARKDOWN_TAG)
                .fetchSemanticsNodes().isNotEmpty()
        }

        val expectedContinuedFeatureBody =
            "Feature-level - what a change should do and how you'll know it worked, " +
                "written per task and thrown away after."

        // Load-bearing shared old/new runtime oracle: the production screen
        // must expose the complete continued item as ONE exact rendered text
        // node before any fixed-only list tag is consulted. Untouched base
        // renders the marker line and continuation as separate text nodes.
        composeRule.onNodeWithText(
            expectedContinuedFeatureBody,
            substring = false,
            useUnmergedTree = true,
        ).performScrollTo().assertExists()

        // Exact article continuation bodies must belong to their tagged item
        // bodies. A detached paragraph elsewhere cannot satisfy these oracles.
        val unorderedBody = listBodyTag("3:1")
        composeRule.onNodeWithTag(unorderedBody).performScrollTo()
        assertMarkerText("3:0", "•")
        assertMarkerText("3:1", "•")
        assertHangingItem(
            path = "3:0",
            expectedBody =
                "Project-level - what the project is. We create it once and don't modify often.",
            requireWrap = true,
        )
        assertHangingItem(
            path = "3:1",
            expectedBody = expectedContinuedFeatureBody,
            requireWrap = true,
        )
        WalkthroughScreenshotArtifacts.capture("issue1714-fixed-unordered")

        // Exact-main run 30730633763 reached this interaction with a persistent
        // framework ANR dialog holding focus. Reproduce that input geometry
        // deterministically before the healthy-path Copy assertion below: a
        // non-cancelable window takes focus while the rendered viewer remains
        // visible. The failure must name that pre-condition instead of timing
        // out as though Markdown selection were broken.
        composeRule.onNodeWithTag(unorderedBody).performTouchInput { longClick() }
        focusOwner.withOwner { owner ->
            WalkthroughScreenshotArtifacts.capture("issue1942-fileviewer-foreign-focus-owner")
            val foreignFocusStartedAt = android.os.SystemClock.elapsedRealtime()
            val foreignFocusFailure = runCatching {
                assertFileViewerWindowFocused()
            }.exceptionOrNull()
            val foreignFocusFailureMs =
                android.os.SystemClock.elapsedRealtime() - foreignFocusStartedAt
            val foreignFocusMessage = foreignFocusFailure?.message.orEmpty()
            assertTrue(
                "foreign focus must be reported as the cause instead of a generic Copy timeout; " +
                    "got: $foreignFocusMessage",
                foreignFocusMessage.contains(FOREIGN_WINDOW_FOCUS_SIGNATURE),
            )
            assertTrue(
                "causal focus failure must name the active window; got: $foreignFocusMessage",
                foreignFocusMessage.contains("active_window_pkg="),
            )
            assertTrue(
                "focus pre-condition must fail before the old 5s Copy timeout; observed " +
                    "${foreignFocusFailureMs}ms",
                foreignFocusFailureMs < 5_000,
            )
            assertTrue(
                "the app-owned focus thief must stay visible until harness cleanup",
                owner.isShowing,
            )
        }

        // The real production SelectionContainer must still select and copy
        // Markdown text. Use the platform Copy key action after long-pressing
        // the body: this avoids both floating-toolbar coordinates and the
        // unrelated File-viewer header "Copy" / body "Copy all" actions.
        assertFileViewerWindowFocused()
        composeRule.onNodeWithTag(unorderedBody).performTouchInput { longClick() }
        WalkthroughScreenshotArtifacts.capture("issue1714-fixed-selection")
        var copyAttempts = 0
        var nextCopyAttemptAtMillis = 0L
        composeRule.waitUntil(timeoutMillis = 5_000) {
            assertFileViewerWindowFocused(timeoutMs = 0)
            if (recordingClipboard.lastText != null) {
                true
            } else {
                val nowMillis = android.os.SystemClock.uptimeMillis()
                if (nowMillis >= nextCopyAttemptAtMillis) {
                    copyAttempts += 1
                    nextCopyAttemptAtMillis = nowMillis + 100
                    // Deterministically model the captured race: the first key
                    // action arrives before SelectionContainer owns a selection
                    // and is therefore a no-op. The bounded pump must recover by
                    // delivering a later real platform Copy action.
                    if (copyAttempts > 1) {
                        InstrumentationRegistry.getInstrumentation()
                            .sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_COPY)
                    }
                }
                false
            }
        }
        assertTrue(
            "copy pump must recover after the first action is dropped",
            copyAttempts > 1,
        )
        val copiedSelection = requireNotNull(recordingClipboard.lastText).trim()
        assertTrue(
            "system Copy must put non-blank Markdown text on the clipboard",
            copiedSelection.isNotEmpty(),
        )
        assertTrue(
            "clipboard payload must come from the continued Feature-level item, was: $copiedSelection",
            expectedContinuedFeatureBody.contains(copiedSelection),
        )
        // The exact article link prose is synthetically nested/continued to
        // cover the list class. Its body keeps the annotation, accent/underline
        // style, and exact HTTPS ACTION_VIEW target.
        val linkPath = "1:0:0:0"
        val linkBody = composeRule.onNodeWithTag(listBodyTag(linkPath))
        linkBody.performScrollTo()
        assertMarkerText(linkPath, "7.")
        assertHangingItem(
            path = linkPath,
            expectedBody = ISSUE1714_LINK_BODY,
            requireWrap = true,
        )
        val linkText = annotatedText(listBodyTag(linkPath))
        val linkAnnotation = linkText.getStringAnnotations(
            tag = MD_URL_TAG_FOR_TEST,
            start = 0,
            end = linkText.length,
        ).single()
        assertEquals(ISSUE1714_URL, linkAnnotation.item)
        val linkStyle = linkText.spanStyles.single { range ->
            range.start == linkAnnotation.start &&
                range.end == linkAnnotation.end &&
                range.item.textDecoration == TextDecoration.Underline
        }
        assertEquals(PocketShellColors.Accent, linkStyle.item.color)
        assertEquals(TextDecoration.Underline, linkStyle.item.textDecoration)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val monitor = object : Instrumentation.ActivityMonitor() {
            @Volatile
            var startedIntent: Intent? = null

            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult {
                startedIntent = Intent(intent)
                return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            val layout = textLayout(listBodyTag(linkPath))
            val linkOffset = layout.layoutInput.text.text.indexOf("SQLiteSearch")
            assertTrue("link label must be in the tagged continued body", linkOffset >= 0)
            linkBody.performTouchInput { click(layout.getBoundingBox(linkOffset).center) }
            composeRule.waitUntil(timeoutMillis = 5_000) { monitor.startedIntent != null }
            val started = requireNotNull(monitor.startedIntent)
            assertEquals(Intent.ACTION_VIEW, started.action)
            assertEquals(ISSUE1714_URL, started.dataString)
        } finally {
            instrumentation.removeMonitor(monitor)
        }
        WalkthroughScreenshotArtifacts.capture("issue1714-fixed-continued-link")

        // Exact ordered article markers and complete bodies.
        composeRule.onNodeWithTag(listBodyTag("7:3")).performScrollTo()
        assertMarkerText("7:0", "1.")
        assertMarkerText("7:3", "4.")
        assertHangingItem(
            path = "7:1",
            expectedBody =
                "Acceptance criteria - checkable statements. Not \"it works\" but things " +
                    "where you can point at the screen and say yes or no.",
            requireWrap = true,
        )
        assertHangingItem(
            path = "7:3",
            expectedBody =
                "Constraints - files it should stay inside, libraries it may not add, " +
                    "patterns it must follow.",
            requireWrap = true,
        )
        WalkthroughScreenshotArtifacts.capture("issue1714-fixed-ordered")

        // Synthetic class coverage: mixed kinds, fifth-level ownership, source
        // 7/42 ordinals, and an intrinsic nine-digit marker. A fixed marker
        // column mutation makes the one- and nine-digit widths equal and fails.
        val widePath = "5:0:0:1"
        composeRule.onNodeWithTag(listBodyTag(widePath)).performScrollTo()
        assertMarkerText("5:0:0:0", "7.")
        assertMarkerText("5:0:0:0:0:0:0:0", "42.")
        assertMarkerText(widePath, "123456789.")
        assertHangingItem(
            path = widePath,
            expectedBody = "wide ordered sibling",
            requireWrap = false,
        )
        val oneDigitMarker = bounds(listMarkerTag("5:0:0:0"))
        val wideMarker = bounds(listMarkerTag(widePath))
        assertTrue(
            "intrinsic marker width must grow for nine digits: one=$oneDigitMarker wide=$wideMarker",
            wideMarker.width > oneDigitMarker.width,
        )

        val deepestPath = "5:0:0:0:0:0:0:0:0:0"
        composeRule.onNodeWithTag(listBodyTag(deepestPath)).performScrollTo()
        assertMarkerText(deepestPath, "•")
        assertHangingItem(
            path = deepestPath,
            expectedBody = "fifth level is not clamped",
            requireWrap = false,
        )
        WalkthroughScreenshotArtifacts.capture("issue1714-fixed-wide-deep-containment")
    } }

    private fun assertMarkerText(path: String, expected: String) {
        assertEquals(expected, annotatedText(listMarkerTag(path)).text)
    }

    private fun assertFileViewerWindowFocused(timeoutMs: Long = 1_000) {
        val focus = awaitActivityWindowFocus(
            scenario = composeRule.activityRule.scenario,
            timeoutMs = timeoutMs,
        )
        if (!focus.focused) {
            fail(
                "$FOREIGN_WINDOW_FOCUS_SIGNATURE For the file viewer, long-press/system Copy " +
                    "cannot be measured in this state: ${focus.diagnosis}.",
            )
        }
    }

    private fun assertHangingItem(
        path: String,
        expectedBody: String,
        requireWrap: Boolean,
    ) {
        val markerBounds = bounds(listMarkerTag(path))
        val bodyTag = listBodyTag(path)
        val bodyBounds = bounds(bodyTag)
        val viewport = bounds(FILE_VIEWER_TEXT_TAG)
        assertEquals(
            "complete body must belong to tagged item $path",
            expectedBody,
            annotatedText(bodyTag).text,
        )
        assertTrue(
            "marker/body must not overlap for $path: marker=$markerBounds body=$bodyBounds",
            markerBounds.right <= bodyBounds.left,
        )
        assertTrue(
            "marker must stay in viewport for $path: marker=$markerBounds viewport=$viewport",
            markerBounds.left >= viewport.left && markerBounds.right <= viewport.right,
        )
        assertTrue(
            "body must stay in viewport for $path: body=$bodyBounds viewport=$viewport",
            bodyBounds.left >= viewport.left && bodyBounds.right <= viewport.right,
        )

        val layout = textLayout(bodyTag)
        if (requireWrap) {
            assertTrue("body $path must wrap to prove hanging alignment", layout.lineCount > 1)
        }
        val firstLeft = layout.getLineLeft(0).toDouble()
        repeat(layout.lineCount) { line ->
            assertEquals(
                "every wrapped line in body $path must share the body-column left edge",
                firstLeft,
                layout.getLineLeft(line).toDouble(),
                0.5,
            )
        }
    }

    private fun annotatedText(tag: String): AnnotatedString {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        return node.config.getOrNull(SemanticsProperties.Text)?.single()
            ?: error("$tag had no unique annotated text semantics")
    }

    private fun textLayout(tag: String): TextLayoutResult {
        val node = composeRule.onNodeWithTag(tag).fetchSemanticsNode()
        val action = node.config.getOrNull(SemanticsActions.GetTextLayoutResult)
            ?: error("$tag had no text-layout semantics action")
        val results = mutableListOf<TextLayoutResult>()
        assertTrue("$tag text-layout action must succeed", action.action?.invoke(results) == true)
        return results.single()
    }

    private fun bounds(tag: String): Rect =
        composeRule.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot

    // Kept local and literal so this connected RED remains source-compatible
    // with untouched base, where production does not yet expose these tags.
    private fun listMarkerTag(path: String): String = "fileViewerMarkdownListMarker:$path"
    private fun listBodyTag(path: String): String = "fileViewerMarkdownListBody:$path"

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

        const val MD_URL_TAG_FOR_TEST = "md_url"
        const val ISSUE1714_URL =
            "https://alexeyondata.substack.com/p/how-i-built-sqlitesearch-a-lightweight"
        const val ISSUE1714_LINK_BODY =
            "This is how I built SQLiteSearch, a small SQLite-backed search library. " +
                "First a long chat session to get the design straight, then I downloaded " +
                "the plan.md file and started coding. That file had all five sections: " +
                "what the library is, how it differs from minsearch, when you should use " +
                "it, when you shouldn't, and the architecture."

        /**
         * #1714 connected fixture. The two specifications/task-section excerpts
         * retain the exact issue-time source lines. The SQLiteSearch prose and
         * URL also come from exact source lines 95-101, but are intentionally
         * indented beneath synthetic unordered/ordered markers to cover a
         * continued nested link. The mixed/deep/wide section is wholly
         * synthetic class coverage. This is not the complete article.
         */
        val ISSUE1714_BODY = """
            # Synthetic nested-link class extension

            - Synthetic list wrapper
              7. This is how I built
                 [SQLiteSearch]($ISSUE1714_URL),
                 a small SQLite-backed search library. First a long chat session to get
                 the design straight, then I downloaded the `plan.md` file and started
                 coding. That file had all five sections: what the library is, how it
                 differs from `minsearch`, when you should use it, when you shouldn't,
                 and the architecture.

            There are two levels of specifications:

            - Project-level - what the project is. We create it once and don't
              modify often.
            - Feature-level - what a change should do and how you'll know it
              worked, written per task and thrown away after.

            ## Synthetic mixed, deep, and wide class coverage

            + root
              7) ordered child
                 * unordered grandchild
                   42. ordered great-grandchild
                       + fifth level is not clamped
              123456789) wide ordered sibling

            Every task has four sections:

            1. Goal - one or two sentences on what should be true afterwards.
            2. Acceptance criteria - checkable statements. Not "it works" but
               things where you can point at the screen and say yes or no.
            3. Out of scope - what this change must not do.
            4. Constraints - files it should stay inside, libraries it may not
               add, patterns it must follow.
        """.trimIndent()
    }
}

/**
 * Small extension so [waitUntil] can poll for a node tag without throwing
 * mid-poll when the tree hasn't settled yet.
 */
private fun ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
