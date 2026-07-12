package com.pocketshell.app.bootstrap

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
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
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.hosts.HOST_LIST_APP_UPDATE_WARNING_TAG
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_BACK_TAG
import com.pocketshell.app.projects.FOLDER_LIST_NEW_SESSION_FAB_TAG
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
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

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
    }

    @Test
    fun ready() { scenario("ready") {
        launchSeededHost()
        capture("01-host-list")
        tapSeededHost()

        waitForReadyNavigation()
        capture("02-ready-navigation")
        assertRemote("ready profile should expose all server tools and an enabled daemon") {
            installedToolsAndEnabledDaemonCommand()
        }
    } }

    @Test
    fun uvInstall() { scenario("uv-install") {
        launchSeededHost()
        tapSeededHost()

        waitForBootstrapSheet()
        compose.onNodeWithText("Host setup needed").assertExists()
        assertSetupRows("pocketshell")
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
    } }

    @Test
    fun uvUpgrade() { scenario("uv-upgrade") {
        launchSeededHost()
        tapSeededHost()

        waitForBootstrapSheet()
        compose.onNodeWithText("Host setup needed").assertExists()
        assertSetupRows("pocketshell CLI update needed")
        // Issue #779: ONE clear action. The status badge says "Outdated" (a
        // state, not a verb) and the single action button says "Update" — the
        // old "Update" badge + "Upgrade" button pair read as two competing
        // buttons. The synonym verb "Upgrade" must NOT appear as a control.
        compose.onNodeWithText("Outdated").assertExists()
        compose.onNodeWithText("Update").assertExists()
        compose.onAllNodesWithText("Upgrade").assertCountEquals(0)
        capture("02-cli-update-needed")
        compose.onNodeWithTag(HOST_BOOTSTRAP_INSTALL_ALL_TAG).assertExists().performClick()
        compose.onNodeWithTag(HOST_BOOTSTRAP_INSTALLING_TAG).assertExists()
        capture("03-upgrading")
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Host ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Host ready").assertExists()
        capture("04-host-ready")
        assertRemote("uv upgrade should leave an app-compatible pocketshell CLI") {
            installedToolsAndEnabledDaemonCommand(targetAppVersion())
        }
    } }

    @Test
    fun uvUpgradeFailure() { scenario("uv-upgrade-failure") {
        launchSeededHost()
        tapSeededHost()

        waitForBootstrapSheet()
        compose.onNodeWithText("Host setup needed").assertExists()
        assertSetupRows("pocketshell CLI update needed")
        compose.onNodeWithText("path /usr/local/bin/pocketshell", substring = true).assertExists()
        compose.onNodeWithText("remote 0.1.0", substring = true).assertExists()
        compose.onNodeWithText("expected ${targetAppVersion()}", substring = true).assertExists()
        compose.onNodeWithText("uv at /usr/local/bin/uv", substring = true).assertExists()
        compose.onNodeWithTag(HOST_BOOTSTRAP_INSTALL_ALL_TAG).assertExists().performClick()
        compose.onNodeWithTag(HOST_BOOTSTRAP_INSTALLING_TAG).assertExists()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Install failed").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Install failed").assertExists()
        compose.onNodeWithText("PocketShell CLI update failed", substring = true).assertExists()
        compose.onNodeWithText("fixture refused upgrade", substring = true).assertExists()
        compose.onNodeWithText(
            "uv tool install --upgrade --exclude-newer 2099-12-31 pocketshell",
            substring = true,
        ).assertExists()
        compose.onNodeWithText("Path: /usr/local/bin/pocketshell", substring = true).assertExists()
        capture("03-upgrade-failed")
        assertRemote("failed uv upgrade should leave the old pocketshell CLI in place") {
            installedToolsAndEnabledDaemonCommand("0.1.0")
        }
    } }

    @Test
    fun appUpdateRequired() { scenario("app-update-required") {
        // Issue #514 (DESIGN REFINEMENT 2026-06-05): remote pocketshell CLI
        // is NEWER than this app build. A minor delta rarely breaks
        // compatibility, so the host must stay fully usable — usage panel,
        // sessions, and folders all render and work exactly as for a
        // version-matched host. The host installer must NOT run, there must
        // be NO takeover setup sheet / modal / "Continue" gate, and NO loop.
        // The ONLY surfaced difference is a small, dismissible, non-blocking
        // warning banner on the host list.
        launchSeededHost()
        tapSeededHost()

        // The host is treated as ready: it navigates straight to the folder
        // list (proving usage/sessions/folders are reachable and functional)
        // without ever popping the bootstrap setup sheet.
        waitForReadyNavigation()
        // No takeover sheet and no "Host setup needed" framing was shown.
        compose.onNodeWithTag(HOST_BOOTSTRAP_SHEET_TAG).assertDoesNotExist()
        assertTrue(
            "remote-newer must never show 'Host setup needed'",
            compose.onAllNodesWithText("Host setup needed").fetchSemanticsNodes().isEmpty(),
        )
        capture("02-host-fully-usable")

        // Go back to the host list: the soft, dismissible "consider updating
        // the app" warning banner must be visible (and is the only surfaced
        // difference) — NOT a sheet, NOT a setup-needed badge.
        compose.onNodeWithTag(FOLDER_LIST_BACK_TAG).performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(HOST_LIST_APP_UPDATE_WARNING_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(HOST_LIST_APP_UPDATE_WARNING_TAG).assertExists()
        assertTrue(
            "expected a 'consider updating the app' warning",
            compose.onAllNodesWithText("consider updating the app", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        assertTrue(
            "expected the warning to name the remote pocketshell CLI being newer",
            compose.onAllNodesWithText("is newer than this app", substring = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )
        // The bootstrap sheet must still be absent — the warning is inline,
        // not a takeover.
        compose.onNodeWithTag(HOST_BOOTSTRAP_SHEET_TAG).assertDoesNotExist()
        capture("03-soft-warning-banner")

        // Dismissible and non-blocking: tapping Dismiss removes the banner
        // and the host list stays usable (no loop, no re-prompt).
        compose.onNodeWithText("Dismiss").performClick()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(HOST_LIST_APP_UPDATE_WARNING_TAG)
                .fetchSemanticsNodes()
                .isEmpty()
        }
        compose.onNodeWithTag(HOST_LIST_APP_UPDATE_WARNING_TAG).assertDoesNotExist()
        capture("04-warning-dismissed")

        assertRemote("app-update-required profile should still expose the newer host CLI") {
            installedToolsAndEnabledDaemonCommand()
        }
    } }

    @Test
    fun unsupported() { scenario("unsupported") {
        launchSeededHost()
        tapSeededHost()

        waitForBootstrapSheet()
        compose.onNodeWithText("Host setup needed").assertExists()
        assertSetupRows("pocketshell")
        compose.onNodeWithText(
            "uv tool install --exclude-newer 2099-12-31 pocketshell or pipx install pocketshell",
        ).assertExists()
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
            "! command -v pocketshell >/dev/null 2>&1 && " +
                "! command -v uv >/dev/null 2>&1 && " +
                "! command -v pipx >/dev/null 2>&1"
        }
    } }

    @Test
    fun daemonDisabled() { scenario("daemon-disabled") {
        launchSeededHost()
        tapSeededHost()

        waitForReadyNavigation()
        capture("02-ready-navigation")
        assertRemote("daemon-disabled profile should navigate normally without enabling the optional jobs daemon") {
            "/bin/sh -lc 'PATH=\"\$HOME/.local/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH\"; " +
                "command -v pocketshell >/dev/null && " +
                "! systemctl --user is-enabled pocketshell-jobs.service >/dev/null'"
        }
    } }

    @Test
    fun userLocalPath() { scenario("user-local-path") {
        launchSeededHost()
        tapSeededHost()

        waitForReadyNavigation()
        capture("02-ready-navigation")
        assertRemote("user-local-path profile should expose all server tools through expanded PATH") {
            installedToolsAndEnabledDaemonCommand()
        }
    } }

    @Test
    fun fishUserLocalPath() { scenario("fish-user-local-path") {
        launchSeededHost()
        tapSeededHost()

        waitForReadyNavigation()
        capture("02-ready-navigation")
        assertRemote("fish-user-local-path profile should expose all server tools through login PATH handling") {
            installedToolsAndEnabledDaemonCommand()
        }
    } }

    @Test
    fun notifications() { scenario("notifications") {
        // Issue #1236 (D26): the CLI + tmux are ready, but the agent stop/idle
        // notification hooks are OFF — a SILENT host under D21 (server-side push
        // is the only "agent needs input" path). Bootstrap must surface the
        // silent host and offer a one-tap enable that folds in a NON-DESTRUCTIVE
        // `pocketshell hooks install`.
        launchSeededHost()
        tapSeededHost()

        // Precondition: the pre-existing foreign Claude Stop hook is present and
        // our hook is NOT yet installed (silent host).
        assertRemote("fixture must start with a pre-existing foreign hook and NO pocketshell hook") {
            "/bin/sh -lc 'grep -q my-preexisting-stop-hook ~/.claude/settings.json && " +
                "! grep -q claude_stop.py ~/.claude/settings.json'"
        }

        // A ready host with notifications off is diverted to the setup sheet
        // instead of navigating past it — the silent host is VISIBLE.
        waitForBootstrapSheet()
        compose.onNodeWithText("Host setup needed").assertExists()
        compose.onNodeWithTag(HOST_BOOTSTRAP_ROW_TAG_PREFIX + HOST_BOOTSTRAP_NOTIFICATIONS_ROW_TITLE)
            .assertExists()
        compose.onNodeWithTag(HOST_BOOTSTRAP_ENABLE_NOTIFICATIONS_TAG).assertExists()
        capture("02-notifications-off")

        // Tap "Enable": runs the server setup path, which folds in the
        // non-destructive hooks install.
        compose.onNodeWithTag(HOST_BOOTSTRAP_ENABLE_NOTIFICATIONS_TAG).performClick()
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithText("Host ready").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Host ready").assertExists()
        compose.onNodeWithTag(HOST_BOOTSTRAP_NOTIFICATIONS_STATUS_TAG).assertExists()
        compose.onNodeWithText("Notifications: on", substring = true).assertExists()
        capture("03-notifications-on")

        // AC3 (G2 class-coverage): the hook files are present AND the install
        // MERGED non-destructively — BOTH our hook AND the pre-existing foreign
        // hook survive in ~/.claude/settings.json.
        assertRemote("hooks install must merge non-destructively into the existing Claude config") {
            "/bin/sh -lc 'grep -q claude_stop.py ~/.claude/settings.json && " +
                "grep -q my-preexisting-stop-hook ~/.claude/settings.json && " +
                "test -f ~/.cache/pocketshell/hooks/.installed'"
        }
    } }

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
                session.use { it.exec(definition.resetCommand(targetAppVersion())) }
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

    private fun installedToolsAndEnabledDaemonCommand(expectedVersion: String? = null): String {
        val versionCheck = expectedVersion?.let {
            "pocketshell --version | grep -F '${it}' >/dev/null && "
        }.orEmpty()
        return (
            "/bin/sh -lc 'PATH=\"\$HOME/.local/bin:\$HOME/bin:\$HOME/.cargo/bin:\$PATH\"; " +
                "command -v pocketshell >/dev/null && " +
                versionCheck +
                "systemctl --user is-enabled pocketshell-jobs.service >/dev/null'"
            )
    }

    private fun targetAppVersion(): String =
        InstrumentationRegistry.getInstrumentation()
            .targetContext
            .packageManager
            .getPackageInfo(InstrumentationRegistry.getInstrumentation().targetContext.packageName, 0)
            .versionName
            ?: error("target app versionName is missing")

    private fun waitForBootstrapSheet() {
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(HOST_BOOTSTRAP_SHEET_TAG).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(HOST_BOOTSTRAP_SHEET_TAG).assertExists()
    }

    private fun waitForReadyNavigation() {
        // Issue #171: post-bootstrap navigation now lands on FolderListScreen
        // instead of the inline HostTmuxSessionPickerSheet. Wait on the merged
        // visible screen contract so the screenshot proves the user sees
        // FolderList chrome, not a pre-visible destination node while the host
        // list still says "Checking setup".
        compose.waitUntil(timeoutMillis = 20_000) {
            compose.onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG)
                .fetchSemanticsNodes()
                .isNotEmpty() &&
                compose.onAllNodesWithTag(FOLDER_LIST_NEW_SESSION_FAB_TAG)
                    .fetchSemanticsNodes()
                    .isNotEmpty() &&
                compose.onAllNodesWithText("Checking setup")
                    .fetchSemanticsNodes()
                    .isEmpty() &&
                compose.onAllNodesWithText("Hosts")
                    .fetchSemanticsNodes()
                    .isEmpty()
        }
        compose.waitForIdle()
        compose.onNodeWithTag(HOST_BOOTSTRAP_SHEET_TAG).assertDoesNotExist()
        compose.onNodeWithTag(FOLDER_LIST_SCREEN_TAG).assertIsDisplayed()
        compose.onNodeWithTag(FOLDER_LIST_NEW_SESSION_FAB_TAG).assertIsDisplayed()
        assertTrue(
            "expected host-list Checking setup surface to be gone before capturing ready navigation",
            compose.onAllNodesWithText("Checking setup")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
        assertTrue(
            "expected host list 'Hosts' header/section to be gone before capturing ready navigation",
            compose.onAllNodesWithText("Hosts")
                .fetchSemanticsNodes()
                .isEmpty(),
        )
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
        val resetCommand: (targetAppVersion: String) -> String,
    )

    private companion object {
        const val DEFAULT_HOST: String = "10.0.2.2"
        const val DEFAULT_USER: String = "testuser"
        const val DATABASE_NAME: String = "pocketshell.db"
        const val STATE_FILE: String = "/tmp/pocketshell-bootstrap-systemctl-state"
        const val VERSION_FILE: String = "/tmp/pocketshell-bootstrap-pocketshell-version"
        const val LATEST_VERSION_FILE: String = "/tmp/pocketshell-bootstrap-latest-version"
        const val UPGRADE_FAILURE_FILE: String = "/tmp/pocketshell-bootstrap-upgrade-failure"

        fun versionReset(targetAppVersion: String): String =
            "printf ${shellQuote("$targetAppVersion\n")} > $VERSION_FILE; " +
                "printf ${shellQuote("$targetAppVersion\n")} > $LATEST_VERSION_FILE; "

        fun latestVersionReset(targetAppVersion: String): String =
            "printf ${shellQuote("$targetAppVersion\n")} > $LATEST_VERSION_FILE; "

        /**
         * Issue #514: produce a dotted-numeric version strictly newer than
         * [targetAppVersion] by bumping its last numeric component, so the
         * remote CLI is guaranteed to outrank whatever the running app
         * build reports. Falls back to a high fixed version if the app
         * version is not dotted-numeric.
         */
        fun newerThanAppVersion(targetAppVersion: String): String {
            val parts = targetAppVersion.trim().split('.')
            val numeric = parts.map { it.toIntOrNull() }
            if (numeric.isEmpty() || numeric.any { it == null }) {
                return "9999.0.0"
            }
            val bumped = numeric.toMutableList()
            bumped[bumped.lastIndex] = (bumped.last() ?: 0) + 1
            return bumped.joinToString(".")
        }

        fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"

        val SCENARIOS = mapOf(
            "ready" to ScenarioDefinition(
                label = "ready",
                port = 2230,
                resetCommand = { targetAppVersion ->
                    "rm -f ~/.local/bin/pocketshell; " +
                        versionReset(targetAppVersion) +
                        "printf 'active enabled\\n' > $STATE_FILE"
                },
            ),
            "uv-install" to ScenarioDefinition(
                label = "uv install",
                port = 2231,
                resetCommand = { targetAppVersion ->
                    "rm -f ~/.local/bin/pocketshell $VERSION_FILE; " +
                        latestVersionReset(targetAppVersion) +
                        "printf 'active enabled\\n' > $STATE_FILE"
                },
            ),
            "uv-upgrade" to ScenarioDefinition(
                label = "uv upgrade",
                port = 2236,
                resetCommand = { targetAppVersion ->
                    "rm -f ~/.local/bin/pocketshell; " +
                        "rm -f $UPGRADE_FAILURE_FILE; " +
                        "printf '0.3.6\\n' > $VERSION_FILE; " +
                        latestVersionReset(targetAppVersion) +
                        "printf 'active enabled\\n' > $STATE_FILE"
                },
            ),
            "uv-upgrade-failure" to ScenarioDefinition(
                label = "uv upgrade failure",
                port = 2236,
                resetCommand = { targetAppVersion ->
                    "rm -f ~/.local/bin/pocketshell; " +
                        "printf '0.1.0\\n' > $VERSION_FILE; " +
                        latestVersionReset(targetAppVersion) +
                        "printf 'fixture refused upgrade\\n' > $UPGRADE_FAILURE_FILE; " +
                        "printf 'active enabled\\n' > $STATE_FILE"
                },
            ),
            "app-update-required" to ScenarioDefinition(
                // Issue #514: reuse the uv-upgrade container (pocketshell +
                // uv + systemctl installed) but report a remote CLI version
                // strictly NEWER than the running app build.
                label = "app update required",
                port = 2236,
                resetCommand = { targetAppVersion ->
                    "rm -f ~/.local/bin/pocketshell $UPGRADE_FAILURE_FILE; " +
                        "printf ${shellQuote("${newerThanAppVersion(targetAppVersion)}\n")} > $VERSION_FILE; " +
                        latestVersionReset(targetAppVersion) +
                        "printf 'active enabled\\n' > $STATE_FILE"
                },
            ),
            "unsupported" to ScenarioDefinition(
                label = "unsupported",
                port = 2232,
                resetCommand = {
                    "rm -f ~/.local/bin/pocketshell $VERSION_FILE $LATEST_VERSION_FILE $UPGRADE_FAILURE_FILE; " +
                        "printf 'inactive disabled\\n' > $STATE_FILE"
                },
            ),
            "daemon-disabled" to ScenarioDefinition(
                label = "daemon disabled",
                port = 2233,
                resetCommand = { targetAppVersion ->
                    "rm -f ~/.local/bin/pocketshell; " +
                        versionReset(targetAppVersion) +
                        "printf 'active disabled\\n' > $STATE_FILE"
                },
            ),
            "user-local-path" to ScenarioDefinition(
                label = "user local path",
                port = 2234,
                resetCommand = { targetAppVersion ->
                    versionReset(targetAppVersion) +
                        "printf 'active enabled\\n' > $STATE_FILE"
                },
            ),
            "fish-user-local-path" to ScenarioDefinition(
                label = "fish user local path",
                port = 2235,
                resetCommand = { targetAppVersion ->
                    versionReset(targetAppVersion) +
                        "printf 'active enabled\\n' > $STATE_FILE"
                },
            ),
            // Issue #1236 (D26): CLI + tmux ready, but the agent stop/idle
            // notification hooks are OFF (a silent host). A pre-existing foreign
            // Claude hook is seeded so the bootstrap install can be asserted to
            // MERGE non-destructively.
            "notifications" to ScenarioDefinition(
                label = "notifications",
                port = 2241,
                resetCommand = { targetAppVersion ->
                    "mkdir -p ~/.claude ~/.cache/pocketshell/hooks; " +
                        "printf '%s' " +
                        shellQuote(PREEXISTING_CLAUDE_SETTINGS) +
                        " > ~/.claude/settings.json; " +
                        "rm -f ~/.cache/pocketshell/hooks/.installed; " +
                        "touch ~/.pocketshell-fixture-hooks-enabled; " +
                        versionReset(targetAppVersion) +
                        "printf 'active enabled\\n' > $STATE_FILE"
                },
            ),
        )

        // A user's PRE-EXISTING Claude config with a foreign Stop hook. The
        // D26 `hooks install` merge must preserve this entry (non-destructive).
        const val PREEXISTING_CLAUDE_SETTINGS: String =
            "{\"model\":\"sonnet\",\"hooks\":{\"Stop\":[{\"hooks\":" +
                "[{\"type\":\"command\",\"command\":\"my-preexisting-stop-hook\"}]}]}}"
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
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val directory = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME/$scenario")
        check(directory.exists() || directory.mkdirs()) {
            "Could not create setup-detection artifact directory: ${directory.absolutePath}"
        }
        return File(directory, name)
    }
}
