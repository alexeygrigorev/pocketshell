package com.pocketshell.app.bootstrap

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Opt-in end-to-end bootstrap/setup scenarios against deterministic Docker
 * hosts. These tests drive the real host-list tap path and assert the visible
 * bootstrap sheet/action state. Direct SSH is used only for remote reset and
 * post-action probes.
 */
@RunWith(AndroidJUnit4::class)
class HostBootstrapScenarioSuiteTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun ready() = scenario("ready") {
        launchSeededHost()
        capture("01-host-list")
        tapSeededHost()

        waitForReadyNavigation()
        capture("02-ready-navigation")
        assertRemote("ready profile should expose all server tools and an enabled daemon") {
            installedToolsAndEnabledDaemonCommand()
        }
    }

    @Test
    fun uvInstall() = scenario("uv-install") {
        launchSeededHost()
        tapSeededHost()

        waitForBootstrapSheet()
        compose.onNodeWithText("Host setup needed").assertExists()
        assertSetupRows("tmuxctl", "quse")
        capture("02-setup-needed")
        compose.onNodeWithTag(HOST_BOOTSTRAP_INSTALL_ALL_TAG).assertExists().performClick()
        compose.onNodeWithTag(HOST_BOOTSTRAP_INSTALLING_TAG).assertExists()
        capture("03-installing")
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Host ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Host ready").assertExists()
        capture("04-host-ready")
        assertRemote("uv install should leave all server tools available") {
            installedToolsAndEnabledDaemonCommand()
        }
    }

    @Test
    fun unsupported() = scenario("unsupported") {
        launchSeededHost()
        tapSeededHost()

        waitForBootstrapSheet()
        compose.onNodeWithText("Host setup needed").assertExists()
        assertSetupRows("tmuxctl", "quse")
        compose.onNodeWithText("uv tool install tmuxctl or pipx install tmuxctl").assertExists()
        capture("02-unsupported-manual-setup")
        compose.onNodeWithTag(HOST_BOOTSTRAP_INSTALL_ALL_TAG).assertExists().performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Install failed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Install failed").assertExists()
        compose.onNodeWithText("Install uv or pipx", substring = true).assertExists()
        compose.onNodeWithTag(HOST_BOOTSTRAP_CLOSE_TAG).assertExists()
        capture("03-install-failed")
        assertRemote("unsupported profile should still lack automatic installers and tools") {
            "! command -v tmuxctl >/dev/null 2>&1 && " +
                "! command -v quse >/dev/null 2>&1 && " +
                "! command -v uv >/dev/null 2>&1 && " +
                "! command -v pipx >/dev/null 2>&1"
        }
    }

    @Test
    fun daemonDisabled() = scenario("daemon-disabled") {
        launchSeededHost()
        tapSeededHost()

        waitForBootstrapSheet()
        compose.onNodeWithText("Host setup needed").assertExists()
        compose.onNodeWithTag(HOST_BOOTSTRAP_ROW_TAG_PREFIX + "tmuxctl jobs daemon").assertExists()
        capture("02-daemon-disabled")
        compose.onNodeWithText("Enable").assertExists().performClick()
        compose.onNodeWithTag(HOST_BOOTSTRAP_INSTALLING_TAG).assertExists()
        capture("03-enabling-daemon")
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Host ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Host ready").assertExists()
        capture("04-host-ready")
        assertRemote("daemon-disabled scenario should enable the fixture daemon") {
            "systemctl --user is-enabled tmuxctl-jobs.service >/dev/null"
        }
    }

    @Test
    fun userLocalPath() = scenario("user-local-path") {
        launchSeededHost()
        tapSeededHost()

        waitForReadyNavigation()
        capture("02-ready-navigation")
        assertRemote("user-local-path profile should expose all server tools through expanded PATH") {
            installedToolsAndEnabledDaemonCommand()
        }
    }

    @Test
    fun fishUserLocalPath() = scenario("fish-user-local-path") {
        launchSeededHost()
        tapSeededHost()

        waitForReadyNavigation()
        capture("02-ready-navigation")
        assertRemote("fish-user-local-path profile should expose all server tools through login PATH handling") {
            installedToolsAndEnabledDaemonCommand()
        }
    }

    private fun scenario(name: String, block: ScenarioContext.() -> Unit) = runBlocking {
        assumeScenariosEnabled()
        val definition = requireNotNull(SCENARIOS[name]) { "unknown bootstrap scenario: $name" }
        val key = testKey()
        val context = ScenarioContext(name = name, definition = definition, key = key)
        val scenarioStartMs = SystemClock.elapsedRealtime()
        withTimeout(60_000) {
            context.resetRemote()
            try {
                context.seedAppDatabase()
                context.block()
            } finally {
                context.recordTiming("scenario_elapsed_ms", SystemClock.elapsedRealtime() - scenarioStartMs)
                context.writeTimings()
                context.cleanupAppDatabase()
                context.resetRemote()
            }
        }
    }

    private inner class ScenarioContext(
        val name: String,
        val definition: ScenarioDefinition,
        val key: String,
    ) {
        private val hostName = "Bootstrap ${definition.label} ${System.nanoTime()}"
        private var hostId: Long? = null
        private var keyId: Long? = null
        private val timings = mutableListOf<String>()

        fun seedAppDatabase() {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            try {
                runBlocking {
                    val storedKey = SshKeyStorage.persistKey(
                        context = appContext,
                        sshKeyDao = db.sshKeyDao(),
                        name = "bootstrap-test-key",
                        content = key,
                    )
                    keyId = storedKey.id
                    hostId = db.hostDao().insert(
                        HostEntity(
                            name = hostName,
                            hostname = DEFAULT_HOST,
                            port = definition.port,
                            username = DEFAULT_USER,
                            keyId = storedKey.id,
                        ),
                    )
                }
            } finally {
                db.close()
            }
        }

        fun cleanupAppDatabase() {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
            try {
                runBlocking {
                    hostId?.let { db.hostDao().deleteById(it) }
                    keyId?.let { db.sshKeyDao().deleteById(it) }
                }
            } finally {
                db.close()
            }
        }

        fun launchSeededHost() {
            val startMs = SystemClock.elapsedRealtime()
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText(hostName).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(hostName).assertExists()
            recordTiming("host_list_visible_ms", SystemClock.elapsedRealtime() - startMs)
        }

        fun tapSeededHost() {
            compose.onNodeWithText(hostName).performClick()
        }

        suspend fun resetRemote() {
            val reset = connect().mapCatching { session ->
                session.use { it.exec(definition.resetCommand) }
            }
            assertTrue(
                "expected reset for bootstrap scenario '$name' on $DEFAULT_HOST:${definition.port}, got ${reset.exceptionOrNull()}",
                reset.getOrNull()?.exitCode == 0,
            )
        }

        fun assertRemote(description: String, command: () -> String) = runBlocking {
            val startMs = SystemClock.elapsedRealtime()
            val probe = connect().mapCatching { session ->
                session.use { it.exec(command()) }
            }
            val elapsedMs = SystemClock.elapsedRealtime() - startMs
            recordTiming("remote_probe_ms", elapsedMs)
            BootstrapScenarioArtifacts.writeProbe(
                scenario = name,
                description = description,
                elapsedMs = elapsedMs,
                exitCode = probe.getOrNull()?.exitCode,
                stdout = probe.getOrNull()?.stdout,
                stderr = probe.getOrNull()?.stderr,
                error = probe.exceptionOrNull()?.toString(),
            )
            assertTrue(
                "expected $description, got ${probe.exceptionOrNull()} stdout='${probe.getOrNull()?.stdout}' stderr='${probe.getOrNull()?.stderr}'",
                probe.getOrNull()?.exitCode == 0,
            )
        }

        fun capture(name: String): File =
            BootstrapScenarioArtifacts.capture(name = "$name.png", scenario = this.name)

        fun recordTiming(name: String, value: Long) {
            val line = "SETUP_DETECTION_TIMING $name=$value"
            timings += line
            println(line)
        }

        fun writeTimings(): File =
            BootstrapScenarioArtifacts.writeTimings(scenario = name, lines = timings)

        private suspend fun connect() = SshConnection.connect(
            host = DEFAULT_HOST,
            port = definition.port,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        )
    }

    private fun installedToolsAndEnabledDaemonCommand(): String =
        "/bin/sh -lc 'PATH=\"\$HOME/.local/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH\"; " +
            "command -v tmuxctl quse >/dev/null && " +
            "systemctl --user is-enabled tmuxctl-jobs.service >/dev/null'"

    private fun waitForBootstrapSheet() {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(HOST_BOOTSTRAP_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(HOST_BOOTSTRAP_SHEET_TAG).assertExists()
    }

    private fun waitForReadyNavigation() {
        // Issue #171: post-bootstrap navigation now lands on the
        // FolderListScreen ("Folders" title) instead of the inline
        // HostTmuxSessionPickerSheet ("Tmux sessions" title).
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Folders").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(HOST_BOOTSTRAP_SHEET_TAG).assertDoesNotExist()
        compose.onNodeWithText("Folders").assertExists()
    }

    private fun assertSetupRows(vararg rows: String) {
        rows.forEach { row ->
            compose.onNodeWithTag(HOST_BOOTSTRAP_ROW_TAG_PREFIX + row).assertExists()
        }
    }

    private fun assumeScenariosEnabled() {
        val enabled = InstrumentationRegistry.getArguments()
            .getString("pocketshellBootstrapScenarios")
            ?.toBooleanStrictOrNull() == true
        assumeTrue(
            "bootstrap scenario suite is opt-in; pass -Pandroid.testInstrumentationRunnerArguments.pocketshellBootstrapScenarios=true",
            enabled,
        )
    }

    private fun testKey(): String = InstrumentationRegistry.getInstrumentation()
        .context
        .assets
        .open("test_key")
        .bufferedReader()
        .use { it.readText() }

    private data class ScenarioDefinition(
        val label: String,
        val port: Int,
        val resetCommand: String,
    )

    private companion object {
        const val DEFAULT_HOST: String = "10.0.2.2"
        const val DEFAULT_USER: String = "testuser"
        const val DATABASE_NAME: String = "pocketshell.db"
        const val STATE_FILE: String = "/tmp/pocketshell-bootstrap-systemctl-state"

        val SCENARIOS = mapOf(
            "ready" to ScenarioDefinition(
                label = "ready",
                port = 2230,
                resetCommand = "rm -f ~/.local/bin/tmuxctl ~/.local/bin/quse; " +
                    "printf 'active enabled\\n' > $STATE_FILE",
            ),
            "uv-install" to ScenarioDefinition(
                label = "uv install",
                port = 2231,
                resetCommand = "rm -f ~/.local/bin/tmuxctl ~/.local/bin/quse; " +
                    "printf 'active enabled\\n' > $STATE_FILE",
            ),
            "unsupported" to ScenarioDefinition(
                label = "unsupported",
                port = 2232,
                resetCommand = "rm -f ~/.local/bin/tmuxctl ~/.local/bin/quse; " +
                    "printf 'inactive disabled\\n' > $STATE_FILE",
            ),
            "daemon-disabled" to ScenarioDefinition(
                label = "daemon disabled",
                port = 2233,
                resetCommand = "rm -f ~/.local/bin/tmuxctl ~/.local/bin/quse; " +
                    "printf 'active disabled\\n' > $STATE_FILE",
            ),
            "user-local-path" to ScenarioDefinition(
                label = "user local path",
                port = 2234,
                resetCommand = "printf 'active enabled\\n' > $STATE_FILE",
            ),
            "fish-user-local-path" to ScenarioDefinition(
                label = "fish user local path",
                port = 2235,
                resetCommand = "printf 'active enabled\\n' > $STATE_FILE",
            ),
        )
    }
}

private object BootstrapScenarioArtifacts {
    private const val DEVICE_DIR_NAME: String = "setup-detection"

    fun capture(name: String, scenario: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(300)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = artifactFile(scenario = scenario, name = name)
        FileOutputStream(file).use { output ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                "Could not write setup-detection screenshot: ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("SETUP_DETECTION_SCREENSHOT ${file.absolutePath}")
        return file
    }

    fun writeTimings(scenario: String, lines: List<String>): File {
        val file = artifactFile(scenario = scenario, name = "timings.txt")
        file.writeText(lines.joinToString(separator = "\n", postfix = "\n"))
        println("SETUP_DETECTION_TIMINGS ${file.absolutePath}")
        return file
    }

    fun writeProbe(
        scenario: String,
        description: String,
        elapsedMs: Long,
        exitCode: Int?,
        stdout: String?,
        stderr: String?,
        error: String?,
    ): File {
        val file = artifactFile(scenario = scenario, name = "remote-probes.txt")
        file.appendText(
            buildString {
                appendLine("description=$description")
                appendLine("elapsed_ms=$elapsedMs")
                appendLine("exit_code=${exitCode ?: -1}")
                if (error != null) appendLine("error=$error")
                appendLine("stdout=${stdout.orEmpty().trim()}")
                appendLine("stderr=${stderr.orEmpty().trim()}")
                appendLine()
            },
        )
        println("SETUP_DETECTION_REMOTE_PROBE ${file.absolutePath}")
        return file
    }

    private fun artifactFile(scenario: String, name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val directory = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME/$scenario")
        check(directory.exists() || directory.mkdirs()) {
            "Could not create setup-detection artifact directory: ${directory.absolutePath}"
        }
        return File(directory, name)
    }
}
