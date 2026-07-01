package com.pocketshell.app.fileexplorer

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.WalkthroughScreenshotArtifacts
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.core.ssh.DefaultSshLeaseConnector
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshLeaseTarget
import java.util.concurrent.atomic.AtomicInteger
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
import java.io.File
import java.io.FileOutputStream

/**
 * Connected Docker test for the file explorer (issue #528).
 *
 * Seeds a known directory tree on the deterministic `agents` fixture (host
 * port `2222`, already wired into the CI emulator job), drives
 * [FileExplorerScreen] against the live SSH session, and exercises the full
 * browse journey: list the start dir, descend into a folder, go back up, and
 * tap a file — asserting the resolved absolute path is handed to the viewer.
 * Screenshots are captured under the walkthrough artifact directory for
 * reviewer inspection.
 */
@RunWith(AndroidJUnit4::class)
class FileExplorerDockerTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var sshKey: SshKey.Pem
    private lateinit var keyFile: File
    private var seededRoot: String? = null

    /**
     * Counts cold SSH handshakes the explorer dials (issue #697). The explorer
     * acquires from this shared app-wide [SshLeaseManager]; a warm lease already
     * held for the same key means the browse reuses it and this stays at the
     * pre-warmed count (no second ~3-4s handshake).
     */
    private val handshakeCount = AtomicInteger(0)
    private val leaseConnector: SshLeaseConnector = SshLeaseConnector { target ->
        handshakeCount.incrementAndGet()
        DefaultSshLeaseConnector().connect(target)
    }
    private lateinit var leaseManager: SshLeaseManager

    private fun newExplorerViewModel(): FileExplorerViewModel =
        FileExplorerViewModel(leaseManager)

    @Before
    fun setUp(): Unit { runBlocking {
        val keyText = InstrumentationRegistry.getInstrumentation()
            .context.assets.open("test_key").bufferedReader().use { it.readText() }
        sshKey = SshKey.Pem(keyText)
        leaseManager = SshLeaseManager(connector = leaseConnector)
        val cacheDir = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        keyFile = File(cacheDir, "issue528-explorer-key").apply {
            parentFile?.mkdirs()
            if (exists()) delete()
            FileOutputStream(this).use { it.write(keyText.toByteArray()) }
            setReadable(true, true)
        }
        waitForSshFixtureReady(sshKey)
    } }

    @After
    fun tearDown(): Unit { runBlocking {
        seededRoot?.let { root ->
            withTimeout(15_000) {
                connect()?.use { session -> runCatching { session.exec("rm -rf '$root'") } }
            }
        }
        runCatching { keyFile.delete() }
        runCatching { leaseManager.close() }
    } }

    @Test
    fun browsesDirectoriesAndOpensAFile(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val root = "/tmp/issue528-$suffix"
        seededRoot = root

        // Seed: root with a subfolder and a text file; the subfolder holds its
        // own file so we can descend, see content, then go back up.
        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec(
                    "mkdir -p '$root/logs' && " +
                        "printf 'hello explorer' > '$root/readme.txt' && " +
                        "printf 'deep' > '$root/logs/run.log' && echo ok",
                )
                assertEquals("seed exit", 0, exit.exitCode)
            } ?: error("could not connect to seed fixture tree")
        }

        var openedFilePath: String? = null
        composeRule.setContent {
            FileExplorerScreen(
                hostId = TEST_HOST_ID,
                hostName = "agents",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                startDir = root,
                onBack = {},
                onOpenFile = { openedFilePath = it },
                viewModel = newExplorerViewModel(),
            )
        }

        // 1. Start dir lists the subfolder + the file.
        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(FILE_EXPLORER_ROW_TAG_PREFIX + "logs")
        }
        composeRule.onNodeWithTag(FILE_EXPLORER_ROW_TAG_PREFIX + "readme.txt").assertExists()
        WalkthroughScreenshotArtifacts.capture("issue528-explorer-start")

        // 2. Descend into the subfolder; its own file shows, the start file is gone.
        composeRule.onNodeWithTag(FILE_EXPLORER_ROW_TAG_PREFIX + "logs").performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTagExists(FILE_EXPLORER_ROW_TAG_PREFIX + "run.log")
        }
        composeRule.onNodeWithTag(FILE_EXPLORER_UP_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue528-explorer-descended")

        // 3. Go back up to the root; the subfolder + start file are back.
        composeRule.onNodeWithTag(FILE_EXPLORER_UP_TAG).performClick()
        composeRule.waitUntil(timeoutMillis = 20_000) {
            composeRule.onAllNodesWithTagExists(FILE_EXPLORER_ROW_TAG_PREFIX + "readme.txt")
        }
        WalkthroughScreenshotArtifacts.capture("issue528-explorer-back-up")

        // 4. Tap the file — the resolved absolute path is handed to the viewer.
        composeRule.onNodeWithTag(FILE_EXPLORER_ROW_TAG_PREFIX + "readme.txt").performClick()
        composeRule.waitUntil(timeoutMillis = 10_000) { openedFilePath != null }
        assertNotNull("file tap should resolve a path", openedFilePath)
        assertTrue(
            "resolved path should end with /readme.txt, was $openedFilePath",
            openedFilePath!!.endsWith("/readme.txt"),
        )
    } }

    @Test
    fun uploadsADeviceFileThenDownloadsItBack(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val root = "/tmp/issue643-$suffix"
        seededRoot = root

        // Seed an empty directory we'll upload into.
        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec("mkdir -p '$root' && echo ok")
                assertEquals("seed exit", 0, exit.exitCode)
            } ?: error("could not connect to seed fixture dir")
        }

        val viewModel = newExplorerViewModel()
        viewModel.start(
            FileExplorerViewModel.Request(
                hostId = TEST_HOST_ID,
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                startDir = root,
            ),
        )
        // Wait for the listing to settle so currentDir is the resolved root.
        withTimeout(30_000) {
            while (viewModel.state.value !is FileExplorerUiState.Ready) {
                kotlinx.coroutines.delay(100)
            }
        }

        // 1. Upload a device-side file into the explorer's current directory.
        val payload = "issue643 round-trip payload".toByteArray()
        viewModel.uploadFile(
            displayName = "upload me.txt",
            length = payload.size.toLong(),
            openStream = { payload.inputStream() },
        )
        withTimeout(30_000) {
            while (viewModel.transfer.value !is FileTransferState.Success) {
                val t = viewModel.transfer.value
                if (t is FileTransferState.Failure) error("upload failed: ${t.message}")
                kotlinx.coroutines.delay(100)
            }
        }
        // The file (sanitised name) now exists remotely.
        val remoteName = FileExplorerViewModel.sanitizeUploadName("upload me.txt")
        withTimeout(15_000) {
            connect()?.use { session ->
                val cat = session.exec("cat '$root/$remoteName'")
                assertEquals("uploaded content", "issue643 round-trip payload", cat.stdout.trim())
            } ?: error("could not connect to verify upload")
        }

        // Re-list happens after a successful upload; wait for the row to appear.
        withTimeout(20_000) {
            while (true) {
                val s = viewModel.state.value
                if (s is FileExplorerUiState.Ready && s.entries.any { it.name == remoteName }) break
                kotlinx.coroutines.delay(100)
            }
        }

        // 2. Download the same file back to the device.
        val entry = (viewModel.state.value as FileExplorerUiState.Ready)
            .entries.first { it.name == remoteName }
        var downloaded: ByteArray? = null
        viewModel.downloadFile(entry) { bytes -> downloaded = bytes }
        withTimeout(30_000) {
            while (viewModel.transfer.value !is FileTransferState.Success) {
                val t = viewModel.transfer.value
                if (t is FileTransferState.Failure) error("download failed: ${t.message}")
                kotlinx.coroutines.delay(100)
            }
        }
        assertNotNull("download should produce bytes", downloaded)
        assertEquals(
            "round-trip content",
            "issue643 round-trip payload",
            String(downloaded!!),
        )
    } }

    @Test
    fun showsPermissionDeniedForUnreadableDirectory(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val root = "/tmp/issue528-perm-$suffix"
        seededRoot = root

        withTimeout(20_000) {
            connect()?.use { session ->
                // A directory with no read/execute bits for the owner — listing
                // is refused, so the explorer should surface the denied state.
                val exit = session.exec("mkdir -p '$root/locked' && chmod 000 '$root/locked' && echo ok")
                assertEquals("seed exit", 0, exit.exitCode)
            } ?: error("could not connect to seed fixture tree")
        }

        composeRule.setContent {
            FileExplorerScreen(
                hostId = TEST_HOST_ID,
                hostName = "agents",
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                startDir = "$root/locked",
                onBack = {},
                onOpenFile = {},
                viewModel = newExplorerViewModel(),
            )
        }

        composeRule.waitUntil(timeoutMillis = 30_000) {
            composeRule.onAllNodesWithTagExists(FILE_EXPLORER_ERROR_TAG)
        }
        composeRule.onNodeWithTag(FILE_EXPLORER_ERROR_TAG).assertExists()
        WalkthroughScreenshotArtifacts.capture("issue528-explorer-permission-denied")
    } }

    @Test
    fun browseReusesTheWarmLeaseInsteadOfHandshakingAgain(): Unit { runBlocking {
        val suffix = System.currentTimeMillis().toString().takeLast(6)
        val root = "/tmp/issue697-$suffix"
        seededRoot = root

        withTimeout(20_000) {
            connect()?.use { session ->
                val exit = session.exec(
                    "mkdir -p '$root/sub' && printf 'x' > '$root/a.txt' && echo ok",
                )
                assertEquals("seed exit", 0, exit.exitCode)
            } ?: error("could not connect to seed fixture tree")
        }

        // Pre-warm: a sibling screen (session/folder/tmux) already holds a live
        // lease for this host, keyed IDENTICALLY to what the explorer will use.
        val warmTarget = SshLeaseTarget(
            leaseKey = com.pocketshell.core.ssh.SshLeaseKey(
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
        val warmLease = withTimeout(30_000) { leaseManager.acquire(warmTarget).getOrThrow() }
        val afterWarm = handshakeCount.get()
        assertEquals("pre-warm dials exactly one handshake", 1, afterWarm)

        val viewModel = newExplorerViewModel()
        viewModel.start(
            FileExplorerViewModel.Request(
                hostId = TEST_HOST_ID,
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyFile.absolutePath,
                passphrase = null,
                startDir = root,
            ),
        )
        // Browse: list start dir, descend, go back up — several lease ops.
        withTimeout(30_000) {
            while (viewModel.state.value !is FileExplorerUiState.Ready) {
                kotlinx.coroutines.delay(100)
            }
        }
        val ready = viewModel.state.value as FileExplorerUiState.Ready
        val subDir = ready.entries.first { it.name == "sub" }
        viewModel.openDirectory(subDir)
        withTimeout(20_000) {
            while ((viewModel.state.value as? FileExplorerUiState.Ready)?.currentPath
                    ?.endsWith("/sub") != true
            ) {
                kotlinx.coroutines.delay(100)
            }
        }
        viewModel.goUp()
        withTimeout(20_000) {
            while ((viewModel.state.value as? FileExplorerUiState.Ready)?.entries
                    ?.any { it.name == "a.txt" } != true
            ) {
                kotlinx.coroutines.delay(100)
            }
        }

        // The explorer rode the warm transport for every op — no new handshake.
        assertEquals(
            "explorer browse must reuse the warm lease, not handshake again",
            afterWarm,
            handshakeCount.get(),
        )
        warmLease.release()
    } }

    private suspend fun connect() = SshConnection.connect(
        host = DEFAULT_HOST,
        port = DEFAULT_PORT,
        user = DEFAULT_USER,
        key = sshKey,
        knownHosts = KnownHostsPolicy.AcceptAll,
        timeoutMs = 10_000,
    ).getOrNull()

    private companion object {
        /**
         * Stable host id for the lease key (issue #697). The explorer keys its
         * lease as `"$hostId:$keyPath"`; this must match the pre-warmed sibling
         * lease so the pool hands back the same warm transport.
         */
        const val TEST_HOST_ID: Long = 528L
    }
}

/**
 * Small extension so [waitUntil] can poll for a node tag without throwing
 * mid-poll when the tree hasn't settled yet.
 */
private fun ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
    onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
