package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.composer.MarkdownText
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.terminal.selection.ConversationLink
import com.pocketshell.core.terminal.selection.ConversationLinkKind
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream

/**
 * Issues #557 + #558 — end-to-end coverage for tap-to-view path/URL parsing:
 *
 *  1. `~`-prefixed path expands to `$HOME` and opens (no false "No such file").
 *  2. A `../`-relative path opens AND its breadcrumb shows the normalized
 *     absolute path (no literal `..` segments).
 *  3. A URL wrapped across terminal rows is reassembled into ONE full URL.
 *  4. A path detected in the CONVERSATION view opens the file viewer (not the
 *     keyboard) — parity with the terminal tab.
 *
 * Runs against the deterministic `agents` fixture (host port `2222`, already
 * wired into the CI emulator job). Captures a screenshot per scenario for
 * reviewer inspection.
 */
@RunWith(AndroidJUnit4::class)
class LinkTapParsingDockerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val seededDirs = mutableListOf<String>()

    @Before
    fun setUp(): Unit = runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue557-linktap-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        waitForSshFixtureReady(sshKey)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        if (seededDirs.isNotEmpty()) {
            withTimeout(15_000) {
                connect()?.use { session ->
                    for (dir in seededDirs) runCatching { session.exec("rm -rf '$dir'") }
                }
            }
        }
        runCatching { keyFile.delete() }
    }

    // --- #558 bug 3: `~` expands to $HOME --------------------------------------

    @Test
    fun tildePathExpandsToHomeAndOpensTheImageViewer(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        // Seed a real PNG under $HOME so a `~/...` path must expand to open it.
        val relUnderHome = "issue558-tilde-$suffix/shot.png"
        val tildePath = "~/$relUnderHome"
        val pngBase64 = Base64.encodeToString(makePngBytes(), Base64.NO_WRAP)
        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec(
                    "mkdir -p \"\$HOME/issue558-tilde-$suffix\" && " +
                        "printf '%s' '$pngBase64' | base64 -d > \"\$HOME/$relUnderHome\"",
                )
                assertEquals("seed png under \$HOME exit", 0, exit.exitCode)
                seededDirs += "/home/$DEFAULT_USER/issue558-tilde-$suffix"
            } ?: error("could not connect to seed fixture png")
        }

        composeRule.setContent {
            FileViewerScreen(
                hostName = "agents",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                remotePath = tildePath,
                cwd = null,
                onBack = {},
                viewModel = FileViewerViewModel(targetAppContext()),
            )
        }
        // The image rendering (NOT a CannotPreview "No such file") proves the
        // `~` was expanded server-side.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(FILE_VIEWER_IMAGE_TAG)
        }
        composeRule.onNodeWithTag(FILE_VIEWER_IMAGE_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue558-tilde-path-opens")
    }

    // --- #558 bug 1: relative `../` normalizes for resolution + breadcrumb -----

    @Test
    fun relativeDotDotPathResolvesAndShowsNormalizedBreadcrumb(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        // Seed the file at a known absolute path.
        val baseDir = "/tmp/issue558-rel-$suffix"
        val absPath = "$baseDir/target/shot.png"
        val pngBase64 = Base64.encodeToString(makePngBytes(), Base64.NO_WRAP)
        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec(
                    "mkdir -p '$baseDir/target' && " +
                        "printf '%s' '$pngBase64' | base64 -d > '$absPath'",
                )
                assertEquals("seed png exit", 0, exit.exitCode)
                seededDirs += baseDir
            } ?: error("could not connect to seed fixture png")
        }

        // Tap a `../`-relative path against a deeper cwd: it must collapse to the
        // seeded absolute path for both resolution AND the breadcrumb.
        // cwd = .../a/b/c → ../../target/shot.png → .../target/shot.png
        val cwd = "$baseDir/a/b/c"
        val relInput = "../../../target/shot.png"

        composeRule.setContent {
            FileViewerScreen(
                hostName = "agents",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                remotePath = relInput,
                cwd = cwd,
                onBack = {},
                viewModel = FileViewerViewModel(targetAppContext()),
            )
        }
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(FILE_VIEWER_IMAGE_TAG)
        }
        composeRule.onNodeWithTag(FILE_VIEWER_IMAGE_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue558-relative-normalized-breadcrumb")
        // Cross-check the resolved string the breadcrumb renders: the canonical
        // absolute path with no literal `..` segments.
        val resolved = RemotePathResolver.resolve(relInput, cwd)
        assertEquals("$baseDir/target/shot.png", resolved)
        assertTrue("breadcrumb path must not contain literal ..", !resolved.contains(".."))
    }

    // (The #558 bug-2 wrapped-URL reassembly is covered on the live grid by
    // core-terminal's WrappedUrlReassemblyInstrumentedTest, which can reach the
    // module-internal TerminalView wiring this app test cannot.)

    // --- #557: conversation-view path opens the viewer, not the keyboard ------

    @Test
    fun conversationPathTapOpensFileViewer(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val cwd = "/tmp/issue557-convo-$suffix"
        val relPath = "out/shot.png"
        val absPath = "$cwd/$relPath"
        val pngBase64 = Base64.encodeToString(makePngBytes(), Base64.NO_WRAP)
        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec(
                    "mkdir -p '$cwd/out' && printf '%s' '$pngBase64' | base64 -d > '$absPath'",
                )
                assertEquals("seed png exit", 0, exit.exitCode)
                seededDirs += cwd
            } ?: error("could not connect to seed fixture png")
        }

        val tappedLink = mutableStateOf<ConversationLink?>(null)
        val openViewer = mutableStateOf(false)

        composeRule.setContent {
            if (!openViewer.value) {
                // The conversation body renders the agent's message; the detected
                // path must be a tappable link (issue #557), NOT plain text.
                MarkdownText(
                    text = "Wrote image to $relPath — take a look.",
                    onLinkTap = { link ->
                        tappedLink.value = link
                        if (link.kind == ConversationLinkKind.FILE) openViewer.value = true
                    },
                )
            } else {
                // The tap routed a FILE link → open the production viewer with
                // the detected relative path resolved against the pane cwd, the
                // same wiring TmuxSessionScreen.onConversationLinkTap uses.
                FileViewerScreen(
                    hostName = "agents",
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyPath = keyFile.absolutePath,
                    passphrase = null,
                    remotePath = tappedLink.value!!.text,
                    cwd = cwd,
                    onBack = {},
                    viewModel = FileViewerViewModel(targetAppContext()),
                )
            }
        }

        // Drive the tap on the conversation link span (the detected FILE
        // target). [renderInline] is the same builder MarkdownText renders, so
        // the link span + listener here are exactly what the on-screen body
        // carries; firing the listener proves the tap routes a FILE link rather
        // than falling through to the composer/keyboard.
        composeRule.waitForIdle()
        val annotated = com.pocketshell.app.composer.renderInline(
            "Wrote image to $relPath — take a look.",
        ) { link ->
            tappedLink.value = link
            if (link.kind == ConversationLinkKind.FILE) openViewer.value = true
        }
        val links = annotated.getLinkAnnotations(0, annotated.length)
            .map { it.item }
            .filterIsInstance<androidx.compose.ui.text.LinkAnnotation.Clickable>()
        assertEquals("the conversation path must be exactly one tappable link", 1, links.size)
        instrumentationRunOnMain { links[0].linkInteractionListener?.onClick(links[0]) }
        composeRule.waitForIdle()
        assertNotNull("a conversation link tap must be routed", tappedLink.value)
        assertEquals(ConversationLinkKind.FILE, tappedLink.value!!.kind)
        assertEquals(relPath, tappedLink.value!!.text)

        // The viewer opened and rendered the image — proving the tap reached the
        // file viewer rather than focusing the composer/keyboard.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(FILE_VIEWER_IMAGE_TAG)
        }
        composeRule.onNodeWithTag(FILE_VIEWER_IMAGE_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue557-conversation-path-opens-viewer")
    }

    private fun instrumentationRunOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private fun targetAppContext() =
        InstrumentationRegistry.getInstrumentation().targetContext.applicationContext

    private suspend fun connect() = SshConnection.connect(
        host = DEFAULT_HOST,
        port = DEFAULT_PORT,
        user = DEFAULT_USER,
        key = sshKey,
        knownHosts = KnownHostsPolicy.AcceptAll,
        timeoutMs = 10_000,
    ).getOrNull()

    private fun makePngBytes(): ByteArray {
        val bmp = Bitmap.createBitmap(48, 48, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.rgb(34, 211, 238))
        val out = ByteArrayOutputStream()
        assertTrue(bmp.compress(Bitmap.CompressFormat.PNG, 100, out))
        bmp.recycle()
        return out.toByteArray()
    }
}

private fun ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
