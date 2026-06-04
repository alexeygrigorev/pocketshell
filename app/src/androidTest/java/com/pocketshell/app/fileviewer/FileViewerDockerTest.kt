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
}

/**
 * Small extension so [waitUntil] can poll for a node tag without throwing
 * mid-poll when the tree hasn't settled yet.
 */
private fun ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
