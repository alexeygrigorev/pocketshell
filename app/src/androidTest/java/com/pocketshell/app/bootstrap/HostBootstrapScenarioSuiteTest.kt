package com.pocketshell.app.bootstrap

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
        tapSeededHost()

        waitForNoBootstrapSheet()
        compose.onNodeWithText("Terminal").assertExists()
        compose.onNodeWithText("$DEFAULT_USER@$DEFAULT_HOST:${definition.port}").assertExists()
    }

    @Test
    fun uvInstall() = scenario("uv-install") {
        launchSeededHost()
        tapSeededHost()

        waitForBootstrapSheet()
        compose.onNodeWithText("Host setup needed").assertExists()
        assertSetupRows("tmuxctl", "heru", "agent-log-explorer")
        compose.onNodeWithTag(HOST_BOOTSTRAP_INSTALL_ALL_TAG).assertExists().performClick()
        compose.onNodeWithTag(HOST_BOOTSTRAP_INSTALLING_TAG).assertExists()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Host ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Host ready").assertExists()
        assertRemote("uv install should leave all server tools available") {
            "command -v tmuxctl heru agent-log-explorer >/dev/null && " +
                "systemctl --user is-enabled tmuxctl-jobs.service >/dev/null"
        }
    }

    @Test
    fun unsupported() = scenario("unsupported") {
        launchSeededHost()
        tapSeededHost()

        waitForBootstrapSheet()
        compose.onNodeWithText("Host setup needed").assertExists()
        assertSetupRows("tmuxctl", "heru", "agent-log-explorer")
        compose.onNodeWithText("uv tool install tmuxctl or pipx install tmuxctl").assertExists()
        compose.onNodeWithTag(HOST_BOOTSTRAP_INSTALL_ALL_TAG).assertExists().performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Install failed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Install failed").assertExists()
        compose.onNodeWithText("Install uv or pipx", substring = true).assertExists()
        compose.onNodeWithTag(HOST_BOOTSTRAP_CLOSE_TAG).assertExists()
    }

    @Test
    fun daemonDisabled() = scenario("daemon-disabled") {
        launchSeededHost()
        tapSeededHost()

        waitForBootstrapSheet()
        compose.onNodeWithText("Host setup needed").assertExists()
        compose.onNodeWithTag(HOST_BOOTSTRAP_ROW_TAG_PREFIX + "tmuxctl jobs daemon").assertExists()
        compose.onNodeWithText("Enable").assertExists().performClick()
        compose.onNodeWithTag(HOST_BOOTSTRAP_INSTALLING_TAG).assertExists()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Host ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Host ready").assertExists()
        assertRemote("daemon-disabled scenario should enable the fixture daemon") {
            "systemctl --user is-enabled tmuxctl-jobs.service >/dev/null"
        }
    }

    @Test
    fun userLocalPath() = scenario("user-local-path") {
        launchSeededHost()
        tapSeededHost()

        waitForNoBootstrapSheet()
        compose.onNodeWithText("Terminal").assertExists()
        compose.onNodeWithText("$DEFAULT_USER@$DEFAULT_HOST:${definition.port}").assertExists()
    }

    private fun scenario(name: String, block: ScenarioContext.() -> Unit) = runBlocking {
        assumeScenariosEnabled()
        val definition = requireNotNull(SCENARIOS[name]) { "unknown bootstrap scenario: $name" }
        val key = testKey()
        val context = ScenarioContext(name = name, definition = definition, key = key)
        withTimeout(60_000) {
            context.resetRemote()
            try {
                context.seedAppDatabase()
                context.block()
            } finally {
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

        fun seedAppDatabase() {
            val appContext = InstrumentationRegistry.getInstrumentation().targetContext
            val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME).build()
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
            val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME).build()
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
            launchedActivity = ActivityScenario.launch(MainActivity::class.java)
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithText(hostName).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithText(hostName).assertExists()
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
            val probe = connect().mapCatching { session ->
                session.use { it.exec(command()) }
            }
            assertTrue(
                "expected $description, got ${probe.exceptionOrNull()} stdout='${probe.getOrNull()?.stdout}' stderr='${probe.getOrNull()?.stderr}'",
                probe.getOrNull()?.exitCode == 0,
            )
        }

        private suspend fun connect() = SshConnection.connect(
            host = DEFAULT_HOST,
            port = definition.port,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        )
    }

    private fun waitForBootstrapSheet() {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(HOST_BOOTSTRAP_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(HOST_BOOTSTRAP_SHEET_TAG).assertExists()
    }

    private fun waitForNoBootstrapSheet() {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Terminal").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(HOST_BOOTSTRAP_SHEET_TAG).assertDoesNotExist()
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
                resetCommand = "rm -f ~/.local/bin/tmuxctl ~/.local/bin/heru ~/.local/bin/agent-log-explorer; " +
                    "printf 'active enabled\\n' > $STATE_FILE",
            ),
            "uv-install" to ScenarioDefinition(
                label = "uv install",
                port = 2231,
                resetCommand = "rm -f ~/.local/bin/tmuxctl ~/.local/bin/heru ~/.local/bin/agent-log-explorer; " +
                    "printf 'active enabled\\n' > $STATE_FILE",
            ),
            "unsupported" to ScenarioDefinition(
                label = "unsupported",
                port = 2232,
                resetCommand = "rm -f ~/.local/bin/tmuxctl ~/.local/bin/heru ~/.local/bin/agent-log-explorer; " +
                    "printf 'inactive disabled\\n' > $STATE_FILE",
            ),
            "daemon-disabled" to ScenarioDefinition(
                label = "daemon disabled",
                port = 2233,
                resetCommand = "rm -f ~/.local/bin/tmuxctl ~/.local/bin/heru ~/.local/bin/agent-log-explorer; " +
                    "printf 'active disabled\\n' > $STATE_FILE",
            ),
            "user-local-path" to ScenarioDefinition(
                label = "user local path",
                port = 2234,
                resetCommand = "printf 'active enabled\\n' > $STATE_FILE",
            ),
        )
    }
}
