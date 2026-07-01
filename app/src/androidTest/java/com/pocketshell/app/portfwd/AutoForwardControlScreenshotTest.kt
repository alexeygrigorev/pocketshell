package com.pocketshell.app.portfwd

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #751 screenshot test — captures the redesigned, prominent Auto-forward
 * control on the port-forward panel in both states so the maintainer's
 * "inconspicuous button" report can be verified visually:
 *  - `auto-forward-off.png`  — the panel as first opened (Auto-forward OFF),
 *    showing the strong label + cyan-active On/Off [SegmentedToggle] instead of
 *    the old greyed-out Switch + inert subtitle.
 *  - `auto-forward-on.png`   — after tapping the "On" segment.
 *
 * Acceptance: the On/Off control is obviously a tappable control with clear
 * on/off states (the active segment paints with the cyan accent fill).
 */
@RunWith(AndroidJUnit4::class)
class AutoForwardControlScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var db: AppDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        db = null
    }

    @Test
    fun capturesAutoForwardOffAndOn() { runBlocking {
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
            .insert(SshKeyEntity(name = "af-key", privateKeyPath = "/tmp/af-key"))
        val hostId = database.hostDao().insert(
            HostEntity(
                name = "af-host",
                hostname = "af.example",
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
                connector = FakeAutoForwardScanConnector(AF_SS_OUTPUT),
                portRemappingDao = database.portRemappingDao(),
                forwardingController = ForwardingController(targetContext),
                showAllPortsStore = ShowAllPortsStore(targetContext),
            )
        }

        compose.setContent {
            PocketShellTheme {
                PortForwardPanelScreen(
                    hostId = hostId,
                    keyPath = "/tmp/af-key",
                    onBack = {},
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }

        // Panel opens with Auto-forward OFF; discovery populates the table.
        compose.waitUntil(timeoutMillis = 15_000) {
            val state = viewModel.state.value
            state.connectionState == PortForwardConnectionState.Connected &&
                !state.autoForwardEnabled &&
                state.tunnels.map { it.remotePort } == listOf(4000, 8080)
        }
        compose.waitForIdle()
        SystemClock.sleep(300)
        captureFullDevice(File(ensureArtifactDir(), "auto-forward-off.png"))

        // Tap the "On" segment of the prominent toggle.
        compose.onNodeWithTag("${AUTO_FORWARD_TOGGLE_TEST_TAG}_on").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            viewModel.state.value.autoForwardEnabled
        }
        compose.waitForIdle()
        SystemClock.sleep(400)
        captureFullDevice(File(ensureArtifactDir(), "auto-forward-on.png"))

        assertTrue("Auto-forward should be enabled after tapping On",
            viewModel.state.value.autoForwardEnabled)

        // Tap the "Off" segment again to confirm the control toggles both ways.
        compose.onNodeWithTag("${AUTO_FORWARD_TOGGLE_TEST_TAG}_off").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            !viewModel.state.value.autoForwardEnabled
        }

        withContext(Dispatchers.Main) { viewModel.leavePanel() }
    } }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/auto-forward")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create auto-forward screenshot directory: ${dir.absolutePath}"
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
                    "Could not write auto-forward screenshot: ${file.absolutePath}"
                }
            }
            println("AUTO_FORWARD_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        val AF_SS_OUTPUT: String = """
            0.0.0.0:4000 users:(("app",pid=40,fd=3))
            0.0.0.0:8080 users:(("vite",pid=43,fd=3))
        """.trimIndent()
    }
}

private class FakeAutoForwardScanConnector(
    private val ssOutput: String,
) : PortForwardConnector {
    override suspend fun connect(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): Result<SshSession> = Result.success(FakeAutoForwardSession(ssOutput))
}

private class FakeAutoForwardSession(
    private val ssOutput: String,
) : SshSession {
    @Volatile
    var closed = false
        private set

    override val isConnected: Boolean
        get() = !closed

    override suspend fun exec(command: String): ExecResult =
        ExecResult(stdout = ssOutput, stderr = "", exitCode = 0)

    override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

    override fun openLocalPortForward(
        remoteHost: String,
        remotePort: Int,
        localPort: Int,
    ): SshPortForward = FakeAutoForwardForward(remoteHost, remotePort, localPort)

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

private class FakeAutoForwardForward(
    override val remoteHost: String,
    override val remotePort: Int,
    override val localPort: Int,
) : SshPortForward {
    override var isActive: Boolean = true
        private set
    override val bytesForwarded: Long = 0
    override val bytesReceived: Long = 0

    override fun close() {
        isActive = false
    }
}
