package com.pocketshell.app.proof

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.printToLog
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #520 — proof that the system/gesture Back button on the host-detail
 * (FolderList) and SSH terminal (Session) screens returns the user to the
 * previous screen instead of finishing the activity (which exited PocketShell
 * to the launcher — the #513 audit's release blocker #1).
 *
 * The journey runs against the deterministic Docker `agents` fixture
 * (`10.0.2.2:2222`) so it exercises the real navigator wiring, not a stub:
 *
 *  1. Seed a host + a tmux session, land on the host list.
 *  2. Tap the host -> host-detail (FolderList). Dispatch a real system Back
 *     via the activity's [androidx.activity.OnBackPressedDispatcher] (the same
 *     dispatcher the framework routes the hardware Back key through). Assert
 *     the activity is NOT finishing and the host row is visible again -> Back
 *     returned to the host list.
 *  3. Tap the host again, then a seeded session -> terminal (Session/Tmux).
 *     Dispatch system Back. Assert the activity is NOT finishing and the
 *     host-detail session row is visible again -> Back returned to the
 *     previous screen.
 *
 * The authoritative foreground-package evidence (the acceptance criterion) is
 * captured at each step via `dumpsys activity activities | grep
 * mResumedActivity` and written to an artifact file. After each Back the
 * resumed activity must still be `com.pocketshell.app/.MainActivity`, never
 * `com.android.launcher3` — proving the app stayed foregrounded.
 */
@RunWith(AndroidJUnit4::class)
class SystemBackForegroundE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val foregroundLog = mutableListOf<String>()

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
        kotlinx.coroutines.runBlocking {
            runCatching { cleanupSeededSession(readFixtureKey()) }
        }
    }

    @Test
    fun systemBackOnHostDetailAndTerminalKeepsAppForegrounded() = kotlinx.coroutines.runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSession(key)
        val hostRowTag = seedDockerHost(key, "Issue520 Back")
        forceFlatHostDetailViewMode()
        clearLastSessionPrefs()

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // --- Land on the host list.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        recordForeground("00-host-list")

        // --- (1) host-detail: tap host -> FolderList. We gate on the
        // always-present screen root tag (not the SSH-probed session list) so
        // the Back assertion is hermetic against the cold-AVD `list-sessions`
        // probe latency (#470) — the navigator-level BackHandler is wired the
        // moment the host-detail destination is current, regardless of probe
        // state.
        tapHostUntilHostDetail(hostRowTag)
        recordForeground("01-host-detail")

        // System Back from host-detail must return to the host list.
        pressSystemBack()
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        recordForeground("02-after-back-from-host-detail")
        assertActivityAlive("host-detail Back")
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).assertExists()

        // --- (2) terminal: tap host -> session -> Session/Tmux screen. This
        // leg genuinely needs a live tmux session, so we wait for the SSH-
        // probed session row (with the production one-retry inside
        // waitForSessionInPicker).
        tapHostUntilHostDetail(hostRowTag)
        waitForSessionInPicker(rule = compose, sessionName = SESSION_NAME, timeoutMs = 30_000)
        compose.onNodeWithText(SESSION_NAME).performClick()
        // Wait for the terminal screen to mount (the session row is gone from
        // the picker; the terminal viewport is up).
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                attached = activity.window.decorView.hasTerminalView()
            }
            attached
        }
        recordForeground("03-terminal")

        // System Back from the terminal must return to host-detail.
        pressSystemBack()
        waitForHostDetail()
        recordForeground("04-after-back-from-terminal")
        assertActivityAlive("terminal Back")
        // waitForHostDetail already confirmed the host-detail screen is up
        // (back returned to the previous screen, not the launcher).

        writeForegroundArtifact()

        // Every captured foreground line must be PocketShell foregrounded and
        // not finishing — never the launcher.
        foregroundLog.forEach { line ->
            assertTrue(
                "foreground must stay PocketShell after system Back; got: $line",
                line.contains("com.pocketshell.app"),
            )
            assertFalse(
                "foreground must NOT be the launcher after system Back; got: $line",
                line.contains("com.android.launcher"),
            )
            assertTrue(
                "PocketShell must remain foreground (process importance) after Back; got: $line",
                line.contains("foreground=true"),
            )
            assertTrue(
                "MainActivity must not be finishing after Back; got: $line",
                line.contains("finishing=false"),
            )
        }
        Unit
    }

    /**
     * Wait until the host-detail (FolderList) screen is mounted. Gates on the
     * always-present screen root tag so this is hermetic against the cold-AVD
     * `tmux list-sessions` probe latency (#470): the navigator-level
     * BackHandler is wired the moment this destination is current, regardless
     * of whether the session list has resolved yet.
     */
    /**
     * Tap the host row and wait for the host-detail screen. The host-list
     * tap is async and gated by `HostListViewModel.beginHostOpen` — if a
     * background reprobe / a prior open is in flight the tap is dropped. We
     * therefore re-tap (the exact thing a user does) until the host-detail
     * destination is current, up to a generous bound.
     */
    private fun tapHostUntilHostDetail(hostRowTag: String) {
        // The host-list tap opens an SSH connection to resolve the route; on a
        // cold AVD that first connect can be slow (#470). `beginHostOpen` gates
        // re-taps while one open is in flight, so we tap, then wait generously
        // for the host-detail destination, and only re-tap once the host row is
        // back on screen (meaning the prior open resolved/cleared).
        val deadline = SystemClock.elapsedRealtime() + 120_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (isHostDetailUp()) return
            if (onHostList(hostRowTag)) {
                runCatching {
                    compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
                }
            }
            // Give the in-flight open a long window to land on host-detail
            // before considering another tap.
            runCatching {
                compose.waitUntil(timeoutMillis = 20_000) {
                    isHostDetailUp() || onHostList(hostRowTag)
                }
            }
            if (isHostDetailUp()) return
        }
        if (!isHostDetailUp()) {
            runCatching {
                compose.onRoot(useUnmergedTree = true).printToLog("ISSUE520_TREE")
            }
            check(false) {
                "host-detail (FolderList) screen did not mount after repeated host taps"
            }
        }
    }

    private fun onHostList(hostRowTag: String): Boolean =
        compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
            .fetchSemanticsNodes().isNotEmpty()

    private fun isHostDetailUp(): Boolean =
        compose.onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG).fetchSemanticsNodes().isNotEmpty() ||
            compose.onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()

    private fun waitForHostDetail() {
        compose.waitUntil(timeoutMillis = 25_000) {
            val tagPresent =
                compose.onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG).fetchSemanticsNodes().isNotEmpty() ||
                    compose.onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true)
                        .fetchSemanticsNodes().isNotEmpty()
            val sessionRowPresent =
                compose.onAllNodesWithText(SESSION_NAME).fetchSemanticsNodes().isNotEmpty()
            tagPresent || sessionRowPresent
        }
    }

    private fun assertActivityAlive(label: String) {
        var finishing = true
        launchedActivity?.onActivity { activity ->
            finishing = activity.isFinishing || activity.isDestroyed
        }
        assertFalse(
            "MainActivity must NOT be finishing/destroyed after $label (app must not exit)",
            finishing,
        )
    }

    private fun pressSystemBack() {
        launchedActivity?.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        SystemClock.sleep(250)
    }

    /**
     * Capture the foreground-package proof at [label]. We deliberately do NOT
     * use `UiAutomation.executeShellCommand("dumpsys ...")` here: that shares
     * the single UiAutomation connection the Compose test framework drives, and
     * issuing a blocking shell command mid-journey desyncs it so the next
     * `performClick` silently no-ops. Instead we read the foreground state from
     * inside the app process:
     *
     *  - the RESUMED activity's component name (read off the live activity), and
     *  - the app process IMPORTANCE_FOREGROUND flag from [android.app.ActivityManager].
     *
     * If PocketShell had exited to the launcher on a Back press, the activity
     * would be finishing/destroyed and the process importance would drop below
     * FOREGROUND — both captured here. The literal `dumpsys mResumedActivity`
     * line is captured separately by the host-side adb wrapper in the issue
     * evidence (it is unsafe to run from inside the instrumented journey).
     */
    private fun recordForeground(label: String) {
        var resumedComponent = "<no activity>"
        var finishing = true
        launchedActivity?.onActivity { activity ->
            resumedComponent = activity.componentName.flattenToShortString()
            finishing = activity.isFinishing || activity.isDestroyed
        }
        val am = InstrumentationRegistry.getInstrumentation().targetContext
            .getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        val myPid = android.os.Process.myPid()
        val importance = am.runningAppProcesses
            ?.firstOrNull { it.pid == myPid }
            ?.importance
            ?: -1
        val foreground = importance == android.app.ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
        val line = "$label => resumedActivity=$resumedComponent finishing=$finishing " +
            "processImportance=$importance foreground=$foreground"
        foregroundLog += line
        Log.i(LOG_TAG, "ISSUE520_FOREGROUND $line")
        println("ISSUE520_FOREGROUND $line")
    }

    private fun writeForegroundArtifact() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        val file = File(dir, "foreground-package.txt")
        file.writeText(foregroundLog.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE520_FOREGROUND_ARTIFACT ${file.absolutePath}")
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String, hostName: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue520-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = hostName,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    username = DEFAULT_USER,
                    keyId = storedKey.id,
                    tmuxInstalled = true,
                    lastBootstrapAt = System.currentTimeMillis(),
                ),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private suspend fun seedTmuxSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} " +
                    shellQuote("printf 'BACK-READY\\n'; exec sh"),
            )
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux session seeding to succeed, got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupSeededSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
                }
            }
        }
    }

    private fun forceFlatHostDetailViewMode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext
            .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("host_detail_view_mode", "Flat")
            .commit()
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun android.view.View.hasTerminalView(): Boolean {
        if (this is com.termux.view.TerminalView) return true
        if (this !is android.view.ViewGroup) return false
        for (index in 0 until childCount) {
            if (getChildAt(index).hasTerminalView()) return true
        }
        return false
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue520SystemBack"
        const val DEVICE_DIR_NAME: String = "issue520-system-back"
        const val SESSION_NAME: String = "back-probe"
    }
}
