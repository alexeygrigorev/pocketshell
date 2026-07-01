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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #492 / #602 screenshot test — renders the port-forward discovery table
 * with a deterministic fake host whose `ss -tlnp` output mirrors the
 * maintainer's v0.3.30 dogfood report (issue-602-portfwd-clutter.png):
 *  - dev-server ports in 3000..10000 (4000 / 4001 / 8080) shown by default, and
 *  - the docker/agent/test SSH-proxy noise (2222 / 2224 / 2226 / 2240) plus
 *    low/system (22 / 443) and high (11434 / 49152) ports hidden by default.
 *
 * Two artifacts prove the acceptance criteria:
 *  - `show-all-ports-default.png` — the default filtered table ("Show
 *    hidden/noisy ports" unchecked with the hidden noisy-port count). The 222x
 *    infra ports must NOT appear here.
 *  - `show-all-ports-checked.png` — after clicking the checkbox, the full
 *    table including the previously hidden/noisy ports.
 */
@RunWith(AndroidJUnit4::class)
class ShowAllPortsScreenshotTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var db: AppDatabase? = null

    @After
    fun tearDown() {
        db?.close()
        db = null
    }

    @Test
    fun capturesDefaultFilteredAndShowAllTables() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext

        // Reset the persisted checkbox so the default capture is unchecked.
        ShowAllPortsStore(targetContext).setShowAll(false)

        val database = withContext(Dispatchers.Main) {
            Room.inMemoryDatabaseBuilder(targetContext, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        }
        db = database

        val keyId = database.sshKeyDao()
            .insert(SshKeyEntity(name = "showall-key", privateKeyPath = "/tmp/showall-key"))
        val hostId = database.hostDao().insert(
            HostEntity(
                name = "noisy-host",
                hostname = "noisy.example",
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
                connector = FakeScanConnector(NOISY_SS_OUTPUT),
                portRemappingDao = database.portRemappingDao(),
                forwardingController = ForwardingController(targetContext),
                showAllPortsStore = ShowAllPortsStore(targetContext),
            )
        }

        compose.setContent {
            PocketShellTheme {
                PortForwardPanelScreen(
                    hostId = hostId,
                    keyPath = "/tmp/showall-key",
                    onBack = {},
                    modifier = Modifier.fillMaxSize(),
                    viewModel = viewModel,
                )
            }
        }

        // Wait for discovery to populate the default (filtered) table. The 222x /
        // 2240 docker/agent SSH proxies are hidden; only 4000 / 4001 / 8080 show.
        compose.waitUntil(timeoutMillis = 15_000) {
            val state = viewModel.state.value
            state.connectionState == PortForwardConnectionState.Connected &&
                state.tunnels.map { it.remotePort } == listOf(4000, 4001, 8080)
        }
        compose.waitForIdle()
        SystemClock.sleep(300)
        captureFullDevice(File(ensureArtifactDir(), "show-all-ports-default.png"))

        // Tick "Show hidden/noisy ports" and capture the full table.
        compose.onNodeWithTag(SHOW_ALL_PORTS_TEST_TAG).performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            viewModel.state.value.showAllPorts &&
                viewModel.state.value.tunnels.map { it.remotePort } ==
                listOf(4000, 4001, 8080, 22, 443, 2222, 2224, 2226, 2240, 11434, 49152)
        }
        compose.waitForIdle()
        SystemClock.sleep(300)
        captureFullDevice(File(ensureArtifactDir(), "show-all-ports-checked.png"))

        withContext(Dispatchers.Main) { viewModel.leavePanel() }
    } }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/show-all-ports")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create show-all-ports screenshot directory: ${dir.absolutePath}"
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
                    "Could not write show-all-ports screenshot: ${file.absolutePath}"
                }
            }
            println("SHOW_ALL_PORTS_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        // Mirrors the maintainer's dogfood host: docker/agent SSH proxies in the
        // 222x / 2240 family plus low/system + high ports are noise hidden by
        // default; only the dev-server ports in 3000..10000 (4000 / 4001 / 8080)
        // are visible until "Show hidden/noisy ports" is enabled.
        val NOISY_SS_OUTPUT: String = """
            0.0.0.0:22 users:(("sshd",pid=1,fd=3))
            0.0.0.0:443 users:(("nginx",pid=2,fd=3))
            0.0.0.0:2222 users:(("docker-proxy",pid=10,fd=3))
            0.0.0.0:2224 users:(("docker-proxy",pid=11,fd=3))
            0.0.0.0:2226 users:(("docker-proxy",pid=12,fd=3))
            0.0.0.0:2240 users:(("docker-proxy",pid=13,fd=3))
            0.0.0.0:4000 users:(("app",pid=40,fd=3))
            0.0.0.0:4001 users:(("app",pid=41,fd=3))
            0.0.0.0:8080 users:(("vite",pid=43,fd=3))
            0.0.0.0:11434 users:(("ollama",pid=44,fd=3))
            0.0.0.0:49152 users:(("app",pid=45,fd=3))
        """.trimIndent()
    }
}

private class FakeScanConnector(
    private val ssOutput: String,
) : PortForwardConnector {
    override suspend fun connect(
        host: HostEntity,
        keyPath: String,
        passphrase: CharArray?,
    ): Result<SshSession> = Result.success(FakeScanSession(ssOutput))
}

private class FakeScanSession(
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
    ): SshPortForward = FakeScanForward(remoteHost, remotePort, localPort)

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

private class FakeScanForward(
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
