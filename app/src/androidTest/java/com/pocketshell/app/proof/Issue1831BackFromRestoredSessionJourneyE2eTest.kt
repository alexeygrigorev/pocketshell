package com.pocketshell.app.proof

import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_LIST_CONTENT_TAG
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.proof.signals.repokeSessionPickerFromHostRow
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #1831 — "Back from an agent session sometimes lands on the host list
 * instead of the session list" (maintainer, real-device report):
 *
 * > sometimes when I click back and I'm in an agent session, when I click back
 * > I go to the first initial screen with choosing hosts, which is very
 * > strange. What I expected to happen is I want to go back to the sessions
 * > screen, not to the Host screen.
 *
 * ## The mechanism this journey reproduces
 *
 * `MainActivity.AppNavigator` hand-rolls the back stack as a plain
 * `remember { mutableListOf<AppDestination>() }`, and `back()` used to be
 *
 * ```kotlin
 * setCurrentDestination(backStack.removeLastOrNull() ?: AppDestination.HostList)
 * ```
 *
 * so an EMPTY stack always landed on the host list. The stack is empty for
 * every path that lands on a destination without a user navigation — most
 * commonly the #177 fast-resume restore: Android reaps the backgrounded
 * process, the user returns, `onCreate(savedInstanceState != null)` routes
 * straight into the persisted tmux session, and the freshly-composed navigator
 * has NOTHING on its stack. The first Back then jumps to the host list, exactly
 * as reported. The same happens on a plain configuration change (the activity
 * is recreated and the `remember`ed stack dies with it).
 *
 * ## What this journey drives (the maintainer's exact scenario, real path)
 *
 * On the deterministic Docker `agents` fixture:
 *
 *  1. Normal journey: host row -> host-detail session list -> open the seeded
 *     agent session (the stack now legitimately holds host list + session list).
 *  2. Background (`moveToState(CREATED)`) so `MainActivity.onStop` persists the
 *     last session (#177), then `recreate()` — the process-death resume path
 *     (`savedInstanceState != null`). The user is back IN the agent session
 *     with a brand-new, EMPTY back stack.
 *  3. Press Back.
 *
 * Acceptance: Back must land on the SESSION list (the host-detail
 * [FOLDER_LIST_SCREEN_TAG] screen), NOT the host list
 * ([HOST_LIST_CONTENT_TAG]). A second Back then reaches the host list, and a
 * third Back on the root host list still EXITS the app (`shouldTrapSystemBack`
 * deliberately leaves the root untrapped — `ColdInstallE2eTest` /
 * `EmulatorWorkflowE2eTest` assert that and it must not regress).
 *
 * The second test covers the plain configuration-change recreate (no
 * background hold) and drives the in-app `‹` chevron instead of system Back, so
 * BOTH back affordances and BOTH activity-recreation flavours are covered
 * rather than the single reported instance (G2).
 *
 * MUST FAIL on `main` before the #1831 fix (Back lands on the host list) and
 * PASS after it.
 */
@RunWith(AndroidJUnit4::class)
class Issue1831BackFromRestoredSessionJourneyE2eTest {

    // Issue #788: createAndroidComposeRule<MainActivity>() so the Compose test
    // clock drives the SAME foreground activity the Termux TerminalView interop
    // child is placed into. The rule launches MainActivity in its `before()`, so
    // the remote tmux session + DB host row are seeded BEFORE launch by the
    // chain. The rule-owned scenario also drives `recreate()` (the
    // process-death / configuration-change restore path) from the body.
    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private val trail = mutableListOf<String>()

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        runBlocking {
            if (::fixtureKey.isInitialized) {
                runCatching { killRemoteSession(fixtureKey) }
            }
        }
        clearLastSessionPrefs()
    }

    private suspend fun seedBeforeLaunch() {
        val key = readFixtureKey()
        fixtureKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        clearLastSessionPrefs()
        seedTmuxSession(key)
        hostRowTag = seedDockerHost(key)
        forceFlatHostDetailViewMode()
    }

    /**
     * The maintainer's reported scenario end-to-end: land back IN the agent
     * session through the #177 process-death restore, then press system Back.
     */
    @Test
    fun backFromProcessDeathRestoredAgentSessionLandsOnSessionListNotHostList() { runBlocking {
        openSeededSessionThroughNormalJourney()

        // ---- Background so onStop persists the last session (#177), then
        // recreate: onCreate(savedInstanceState != null) restores straight into
        // the session with a brand-new EMPTY back stack.
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        delay(LIFECYCLE_DRAIN_MS)
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForSessionScreen("after process-death restore")
        record("01-restored-session")

        // ---- Back #1: must reach the SESSION list, not the host list.
        pressSystemBack()
        val landedOnSessionList = waitForFolderListOrHostList()
        record("02-after-first-back")
        writeTrail("process-death-restore-back-trail.txt")
        assertTrue(
            "ISSUE 1831: Back from an agent session restored after process death must land on " +
                "the SESSION list (host-detail folder list), not the host list. Observed: " +
                trail.joinToString(" | "),
            landedOnSessionList,
        )
        assertFalse(
            "ISSUE 1831: the host list must NOT be showing after the first Back",
            onHostList(),
        )

        // ---- Back #2: session list -> host list (unchanged behaviour).
        pressSystemBack()
        compose.waitUntil(timeoutMillis = SCREEN_SETTLE_MS) { onHostList() }
        record("03-after-second-back")

        // ---- Back #3: the ROOT host list is deliberately NOT trapped, so Back
        // there still exits the app (ColdInstall / EmulatorWorkflow contract).
        pressSystemBack()
        var finishing = false
        val deadline = SystemClock.elapsedRealtime() + SCREEN_SETTLE_MS
        while (SystemClock.elapsedRealtime() < deadline && !finishing) {
            compose.activityRule.scenario.onActivity { activity ->
                finishing = activity.isFinishing || activity.isDestroyed
            }
            if (!finishing) SystemClock.sleep(100)
        }
        trail += "04-after-root-back finishing=$finishing"
        writeTrail("process-death-restore-back-trail.txt")
        assertTrue(
            "Back on the ROOT host list must still EXIT the app (shouldTrapSystemBack is " +
                "deliberately false there). Observed: " + trail.joinToString(" | "),
            finishing,
        )
        Unit
    } }

    /**
     * The configuration-change flavour, driven through the in-app `‹` chevron
     * rather than system Back — same empty-stack state, other affordance.
     */
    @Test
    fun chevronBackAfterConfigurationChangeLandsOnSessionListNotHostList() { runBlocking {
        openSeededSessionThroughNormalJourney()

        // A plain configuration-change recreate (no background hold): the
        // `remember`ed back stack dies with the composition.
        compose.activityRule.scenario.recreate()
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitForSessionScreen("after configuration-change recreate")
        record("01-recreated-session")

        clickInSessionBackChevron()
        val landedOnSessionList = waitForFolderListOrHostList()
        record("02-after-chevron-back")
        writeTrail("config-change-back-trail.txt")
        assertTrue(
            "ISSUE 1831: the in-session `<` chevron after a configuration change must land on " +
                "the SESSION list, not the host list. Observed: " + trail.joinToString(" | "),
            landedOnSessionList,
        )
        assertFalse(
            "ISSUE 1831: the host list must NOT be showing after the chevron Back",
            onHostList(),
        )
        Unit
    } }

    // ---------------------------------------------------------------- Journey

    private fun openSeededSessionThroughNormalJourney() {
        waitForHostRowPresent()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForSessionInPicker(
            rule = compose,
            sessionName = SEEDED_SESSION,
            timeoutMs = pickerWaitMs,
            onStateNote = ::recordPickerReadiness,
            onRepoke = {
                repokeSessionPickerFromHostRow(
                    rule = compose,
                    hostRowTag = hostRowTag,
                    onStateNote = ::recordPickerReadiness,
                )
            },
        )
        compose.onNodeWithText(SEEDED_SESSION, useUnmergedTree = true).performClick()
        waitForSessionScreen("initial open")
        record("00-opened-session")
    }

    private fun pressSystemBack() {
        compose.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun clickInSessionBackChevron() {
        val full = compose
            .onAllNodesWithTag(TMUX_FULL_CHROME_BACK_BUTTON_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
        if (full.isNotEmpty()) {
            compose.onNodeWithTag(TMUX_FULL_CHROME_BACK_BUTTON_TAG, useUnmergedTree = true).performClick()
        } else {
            compose
                .onNodeWithTag(TMUX_COMPACT_CHROME_BACK_BUTTON_TAG, useUnmergedTree = true)
                .performClick()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * Polls until either the session list or the host list is on screen, and
     * returns true only when the SESSION list won. Never self-skips: a timeout
     * returns false and the caller hard-fails with the recorded trail.
     */
    private fun waitForFolderListOrHostList(): Boolean {
        val deadline = SystemClock.elapsedRealtime() + SCREEN_SETTLE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (onFolderList()) return true
            if (onHostList()) return false
            SystemClock.sleep(100)
        }
        return false
    }

    private fun waitForSessionScreen(label: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
        Log.i(LOG_TAG, "session screen present: $label")
    }

    private fun waitForHostRowPresent() {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun onHostList(): Boolean = nodesWithTag(HOST_LIST_CONTENT_TAG)

    private fun onFolderList(): Boolean = nodesWithTag(FOLDER_LIST_SCREEN_TAG)

    private fun onSessionScreen(): Boolean = nodesWithTag(TMUX_SESSION_SCREEN_TAG)

    private fun nodesWithTag(tag: String): Boolean =
        runCatching {
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }.getOrDefault(false)

    private fun record(label: String) {
        val line = "$label => session=${onSessionScreen()} folderList=${onFolderList()} " +
            "hostList=${onHostList()}"
        trail += line
        Log.i(LOG_TAG, "ISSUE1831_TRAIL $line")
        println("ISSUE1831_TRAIL $line")
    }

    private fun recordPickerReadiness(note: String) {
        val line = "picker_readiness=$note"
        trail += line
        Log.i(LOG_TAG, "ISSUE1831_PICKER $line")
        println("ISSUE1831_PICKER $line")
    }

    // ---------------------------------------------------------------- Fixture

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHost(key: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1831-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue1831 BackNav",
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

    private fun forceFlatHostDetailViewMode() {
        InstrumentationRegistry.getInstrumentation().targetContext
            .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("host_detail_view_mode", "Flat")
            .commit()
    }

    private suspend fun seedTmuxSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SEEDED_SESSION)} " +
                    "${shellQuote("printf 'ISSUE1831-READY\\n'; exec sleep 900")}",
            )
            appendLine("sleep 1")
            appendLine("tmux has-session -t ${shellQuote(SEEDED_SESSION)}")
        }
        val exec = runScript(key, script)
        assertTrue(
            "expected tmux seeding to succeed; stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private suspend fun killRemoteSession(key: String) {
        runScript(key, "tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true")
    }

    private suspend fun runScript(key: String, script: String): ExecResult? =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }.getOrNull()

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun writeTrail(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        val file = File(dir, name)
        file.writeText(trail.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE1831_TRAIL_ARTIFACT ${file.absolutePath}")
    }

    private val pickerWaitMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 25_000L

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue1831BackNav"
        const val DEVICE_DIR_NAME: String = "issue1831-back-from-session"
        const val SEEDED_SESSION: String = "claude-main"
        const val LIFECYCLE_DRAIN_MS: Long = 1_200L
        const val SCREEN_SETTLE_MS: Long = 20_000L
    }
}
