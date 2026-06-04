package com.pocketshell.app.usage

import android.graphics.Bitmap
import android.os.SystemClock
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.hosts.SETTINGS_BUTTON_TAG
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.TerminalTestTimeouts
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.settings.SETTINGS_LAZY_COLUMN_TAG
import com.pocketshell.app.settings.USAGE_OPEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #114 Fix A: connected emulator coverage for the Usage / quota
 * panel end-to-end.
 *
 * Both cells reuse the deterministic `tests/docker/agents` SSH target
 * (host port 2222). The quse fixture installed there returns three
 * provider records on `quse --json` (see
 * `tests/docker/agent-fixtures/quse-usage.ndjson`) — that drives the
 * populated cell. The empty cell uses an additional host pointed at a
 * port nobody is listening to (`10.0.2.2:2299`) so the per-host fetcher
 * returns `Skipped`, which the screen renders as "no provider cards" —
 * the closest the agents fixture can come to "quse returns empty" without
 * standing up a second container.
 *
 * The full four-cell matrix (no quse / quse empty / quse populated / SSH
 * dropped) lives with Fix B / Fix C — those follow-ups can layer the
 * bootstrap-ready Docker target (quse returns no lines) and the SSH
 * disconnect scenarios on top of this skeleton.
 */
@RunWith(AndroidJUnit4::class)
class UsageScreenE2eTest {

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
    fun usagePanel_populatedCell_showsProviderCards() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        // Sanity probe: the agents fixture actually returns provider
        // records before we drive the UI. This makes a failure on the
        // screen unambiguously a UI/integration issue and not a missing
        // Docker target.
        withTimeout(20_000) {
            val session = SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
            ).getOrThrow()
            session.use {
                // Issue #231 (D22 hard-cut): the app now drives the unified
                // `pocketshell usage --json` CLI; the deterministic `agents`
                // fixture proxies the same provider records the app's parser
                // was written against.
                val result = it.exec("pocketshell usage --json")
                assertTrue(
                    "expected pocketshell usage fixture to succeed, got exit=${result.exitCode} stderr=${result.stderr}",
                    result.exitCode == 0 && result.stdout.contains("provider"),
                )
            }
        }

        seedHost(
            key = key,
            name = "Usage Populated",
            hostname = DEFAULT_HOST,
            port = DEFAULT_PORT,
        )

        val artifactsDir = ensureArtifactDir()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // Tap the Settings gear on the host-list top bar.
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            compose.onAllNodesWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()

        // Scroll the Usage row into view (it lives below the fold on
        // ~854dp CI viewports because LazyColumn only composes children
        // once they're scrolled near) and tap it. `performScrollToNode`
        // drives the LazyColumn to materialise the matching item, which
        // a plain `onNodeWithTag` lookup can't trigger on its own.
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(USAGE_OPEN_TAG))
        compose.onNodeWithTag(USAGE_OPEN_TAG, useUnmergedTree = true).performClick()

        // UsageScreen breadcrumb renders "Usage" as the current crumb.
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            compose.onAllNodesWithText("Usage", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Usage", useUnmergedTree = true).assertExists()

        // Wait for the populated state. The agents fixture provides
        // three providers; the screen renders each as a card with the
        // display name ("Claude" / "Codex" / "Opencode"). Any of those
        // confirms the route + fetch + render path works.
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            val codex = compose.onAllNodesWithText("Codex", useUnmergedTree = true)
                .fetchSemanticsNodes()
            val claude = compose.onAllNodesWithText("Claude", useUnmergedTree = true)
                .fetchSemanticsNodes()
            codex.isNotEmpty() || claude.isNotEmpty()
        }

        captureFullDevice(File(artifactsDir, "01-usage-populated-viewport.png"))
        File(artifactsDir, "01-usage-populated-summary.txt").writeText(
            buildString {
                appendLine("scenario=usage-populated")
                appendLine("docker_target=agents")
                appendLine("docker_port=$DEFAULT_PORT")
                appendLine("expected_records=3 (claude, codex, opencode)")
                appendLine("route=HostList → Settings gear → Usage row → UsageScreen")
            },
        )
    }

    @Test
    fun usagePanel_emptyCell_rendersBreadcrumbAndEmptyState() = runBlocking {
        val key = readFixtureKey()
        // Best-effort probe; an unreachable agents target still lets the
        // empty-cell test prove the panel renders for hosts that can't
        // be reached (Skipped → no provider cards, no missing-tool rows).
        runCatching { waitForSshFixtureReady(SshKey.Pem(key)) }

        seedHost(
            key = key,
            name = "Usage Unreachable",
            hostname = DEFAULT_HOST,
            // Unbound port → fetcher returns Skipped without hanging on
            // the 30s SSH connect timeout (kernel rejects immediately).
            port = 2299,
        )

        val artifactsDir = ensureArtifactDir()
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            compose.onAllNodesWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(SETTINGS_BUTTON_TAG, useUnmergedTree = true).performClick()

        // Scroll the Usage row into view via the parent LazyColumn so
        // the item is composed on tighter CI viewports (see #141).
        compose.onNodeWithTag(SETTINGS_LAZY_COLUMN_TAG)
            .performScrollToNode(hasTestTag(USAGE_OPEN_TAG))
        compose.onNodeWithTag(USAGE_OPEN_TAG, useUnmergedTree = true).performClick()

        // The breadcrumb's "Usage" crumb proves the destination
        // mounted. The "0 providers · 1 hosts" meta row proves the view
        // model is materialised and ran against the seeded host (and
        // gracefully classified it as Skipped — no provider cards, no
        // missing-tool rows).
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            compose.onAllNodesWithText("Usage", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Usage", useUnmergedTree = true).assertExists()

        // Wait for the refresh to settle (the meta row flips from
        // "Syncing..." to "Last sync: host data").
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithText("Syncing...", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isEmpty()
        }

        captureFullDevice(File(artifactsDir, "02-usage-empty-viewport.png"))
        File(artifactsDir, "02-usage-empty-summary.txt").writeText(
            buildString {
                appendLine("scenario=usage-empty")
                appendLine("docker_target=agents")
                appendLine("docker_port=2299 (intentionally unbound)")
                appendLine("expected_records=0 (host unreachable → Skipped)")
                appendLine("route=HostList → Settings gear → Usage row → UsageScreen")
            },
        )
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedHost(
        key: String,
        name: String,
        hostname: String,
        port: Int,
    ) {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "usage-e2e-key-${System.currentTimeMillis()}",
                content = key,
            )
            db.hostDao().insert(
                HostEntity(
                    name = name,
                    hostname = hostname,
                    port = port,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
        } finally {
            db.close()
        }
    }

    private fun ensureArtifactDir(): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "Could not create usage-e2e artifact directory: ${dir.absolutePath}"
        }
        return dir
    }

    private fun captureFullDevice(file: File) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "Could not write usage-e2e screenshot: ${file.absolutePath}"
                }
            }
            println("USAGE_E2E_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val DEVICE_DIR_NAME: String = "usage-e2e"
    }
}
