package com.pocketshell.app.fileviewer

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
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
import com.pocketshell.core.terminal.selection.detectFilePathsInLine
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
 * Issue #500 — end-to-end glue: a project-relative file path printed by the
 * agent is (a) detected by the same conservative scanner the terminal uses and
 * (b) opens in the in-app file viewer (#497) when its path + the active pane's
 * cwd are handed to the viewer — exactly what
 * [com.pocketshell.app.tmux.TmuxSessionScreen]'s `onFilePathTap` lambda does:
 * `onOpenFile(detectedPath, pane.cwd)`.
 *
 * Seeds a real PNG in a sub-directory on the deterministic `agents` fixture
 * (host port `2222`, already wired into the CI emulator job), runs the
 * detection over the agent's "Wrote image to out/<png>" line, then drives the
 * production [FileViewerScreen] with the detected RELATIVE path resolved
 * against the seeded cwd, asserting the image renders. Captures a screenshot
 * of the opened viewer for reviewer inspection.
 *
 * The deterministic on-device tap-routing math (scanner → hit-test → tap hook)
 * is covered by core-terminal's `FilePathTapInstrumentedTest`; this test closes
 * the loop to the actual viewer using the real cwd-resolution path.
 */
@RunWith(AndroidJUnit4::class)
class TerminalFilePathTapToViewerDockerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private val seededPaths = mutableListOf<String>()
    private var seededDir: String? = null

    @Before
    fun setUp(): Unit = runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue500-tap-viewer-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        waitForSshFixtureReady(sshKey)
    }

    @After
    fun tearDown(): Unit = runBlocking {
        if (seededPaths.isNotEmpty() || seededDir != null) {
            withTimeout(15_000) {
                connect()?.use { session ->
                    for (path in seededPaths) {
                        runCatching { session.exec("rm -f '$path'") }
                    }
                    seededDir?.let { runCatching { session.exec("rm -rf '$it'") } }
                }
            }
        }
        runCatching { keyFile.delete() }
    }

    @Test
    fun detectedRelativePngPathOpensImageViewerResolvedAgainstCwd(): Unit = runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        // Seed under a unique cwd so a project-relative `out/<png>` resolves to
        // the seeded file the same way the live pane's cwd would resolve it.
        val cwd = "/tmp/issue500-cwd-$suffix"
        val relPath = "out/shot.png"
        val absPath = "$cwd/$relPath"
        val pngBase64 = Base64.encodeToString(makePngBytes(), Base64.NO_WRAP)
        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec(
                    "mkdir -p '$cwd/out' && printf '%s' '$pngBase64' | base64 -d > '$absPath'",
                )
                assertEquals("seed png exit", 0, exit.exitCode)
                seededDir = cwd
                seededPaths += absPath
            } ?: error("could not connect to seed fixture png")
        }

        // The agent prints the relative path; the same conservative scanner the
        // terminal uses must detect exactly that token (and nothing in prose).
        val agentLine = "Wrote image to $relPath"
        val detected = detectFilePathsInLine(agentLine).map { it.path }
        assertEquals(
            "the conservative scanner must surface exactly the relative png path",
            listOf(relPath),
            detected,
        )

        // Open the viewer with the detected RELATIVE path + the pane cwd, which
        // is exactly what TmuxSessionScreen's onFilePathTap does. The viewer's
        // RemotePathResolver joins them server-side.
        composeRule.setContent {
            FileViewerScreen(
                hostName = "agents",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                remotePath = detected.single(),
                cwd = cwd,
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
        WalkthroughScreenshotArtifacts.capture("issue500-tap-opens-image-viewer")
    }

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
