package com.pocketshell.app.portfwd

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.test.testArtifactsRoot
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import com.pocketshell.uikit.theme.PocketShellTheme
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #756 loader batch L1 — captures the port-forward "Scanning ports…"
 * empty state after migrating its raw centered `CircularProgressIndicator` to
 * the canonical `LoadingIndicator.Spinner(size = SpinnerSize.Medium, label =
 * "Scanning ports...")`. Drives a deterministic fake host whose `ss` discovery
 * returns no listening ports, so the auto-forward-on panel renders the
 * `EmptyScanningState` spinner+label rather than a port table.
 */
@RunWith(AndroidJUnit4::class)
class PortForwardScanningStateScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var db: AppDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        db = null
    }

    @Test
    fun capturesScanningEmptyState() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext

        ShowAllPortsStore(targetContext).setShowAll(false)

        val database = withContext(Dispatchers.Main) {
            Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        }
        db = database

        val keyId = database.sshKeyDao()
            .insert(SshKeyEntity(name = "scan-key", privateKeyPath = "/tmp/scan-key"))
        val hostId = database.hostDao().insert(
            HostEntity(
                name = "scan-host",
                hostname = "scan.example",
                username = "alexey",
                keyId = keyId,
                maxAutoPort = 10_000,
                skipPortsBelow = 1,
                scanIntervalSec = 5,
                enabled = false,
            ),
        )

        val viewModel = withContext(Dispatchers.Main) {
            PortForwardPanelViewModel(
                hostDao = database.hostDao(),
                sshKeyDao = database.sshKeyDao(),
                connector = EmptyScanConnector(),
                portRemappingDao = database.portRemappingDao(),
                forwardingController = ForwardingController(targetContext),
                showAllPortsStore = ShowAllPortsStore(targetContext),
            )
        }

        compose.setContent {
            PocketShellTheme {
                PortForwardPanelScreen(
                    hostId = hostId,
                    keyPath = "/tmp/scan-key",
                    onBack = {},
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }

        // Turn auto-forwarding on so the empty (no-discovered-ports) panel renders
        // the EmptyScanningState spinner + "Scanning ports..." label.
        compose.waitForIdle()
        withContext(Dispatchers.Main) { viewModel.setAutoForwardEnabled(true) }

        compose.waitUntil(timeoutMillis = 15_000) {
            val state = viewModel.state.value
            state.autoForwardEnabled && state.tunnels.isEmpty()
        }
        compose.waitForIdle()
        SystemClock.sleep(300)

        compose.onNodeWithText("Scanning ports...").assertIsDisplayed()

        captureFullDevice(File(ensureArtifactDir(), "portfwd-scanning-empty.png"))

        withContext(Dispatchers.Main) { viewModel.leavePanel() }
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/portfwd-scanning")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create portfwd-scanning screenshot directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap: Bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write portfwd-scanning screenshot: ${file.absolutePath}"
                }
            }
            println("PORTFWD_SCANNING_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }
}

private class EmptyScanConnector : PortForwardConnector {
    override suspend fun connect(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): Result<SshSession> = Result.success(EmptyScanSession())
}

private class EmptyScanSession : SshSession {
    @Volatile
    private var closed = false

    override val isConnected: Boolean
        get() = !closed

    // Empty `ss` output → zero discovered listening ports → EmptyScanningState.
    override suspend fun exec(command: String): ExecResult =
        ExecResult(stdout = "", stderr = "", exitCode = 0)

    override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

    override fun openLocalPortForward(
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): SshPortForward = error("no forward in scanning empty-state test")

    override fun startShell(): SshShell = error("shell not used in screenshot test")

    override suspend fun uploadFile(file: File, remotePath: String): String =
        error("uploadFile not used in screenshot test")

    override suspend fun uploadStream(
        input: java.io.InputStream,
        length: Long,
        name: String,
        remotePath: String,
    ): String = error("uploadStream not used in screenshot test")

    override fun close() {
        closed = true
    }
}
