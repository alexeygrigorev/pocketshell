package com.pocketshell.app.nav

import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_LIST_CONTENT_TAG
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.PreGrantPermissionsRule
import com.pocketshell.app.proof.clearLastSessionPrefs
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.session.LastSessionStore
import com.pocketshell.app.systemsurfaces.ActiveSessionsWidgetProvider
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.usage.USAGE_OVERFLOW_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #1831 — the ENTRY-PATH half of "Back from an agent session sometimes
 * lands on the host list instead of the session list".
 *
 * `Issue1831BackFromRestoredSessionJourneyE2eTest` covers the dominant
 * activity-re-creation producer (the #177 process-death restore and a plain
 * configuration change). This class covers the OTHER producers of the same
 * empty-back-stack state — every launch path that lands the user directly on a
 * destination without ever going through the navigator's `navigate()` (G2, the
 * class rather than the single reported instance):
 *
 *  1. **share-into-session** — `ShareActivity.buildSessionLaunchIntent`
 *     (`EXTRA_OPEN_SESSION_*`), a fresh `onCreate`;
 *  2. **the home-screen active-sessions widget** — built here through the
 *     PRODUCTION [ActiveSessionsWidgetProvider.widgetLaunchIntent] so the test
 *     cannot drift from the real intent;
 *  3. **the #859 agent-card push deep-link** — `EXTRA_OPEN_SESSION_FEED`,
 *     resolved off-Main and then applied with a bare `setCurrentDestination`
 *     while the user is still on the cold-launch host list (so the stack is
 *     empty by construction).
 *
 * All three are COLD `onCreate` launches on purpose, not `onNewIntent`
 * re-deliveries: every one of those production intents carries
 * `FLAG_ACTIVITY_NEW_TASK or FLAG_ACTIVITY_CLEAR_TOP` WITHOUT
 * `FLAG_ACTIVITY_SINGLE_TOP`, and `MainActivity` is `standard` launch mode, so
 * `CLEAR_TOP` finishes and re-creates the activity rather than delivering
 * `onNewIntent`. (`onNewIntent` is reached by the forwarding / connection
 * notifications, which use `SINGLE_TOP or CLEAR_TOP` and carry no session
 * extras.) The one navigator line those async/late routes DO share — the bare
 * `setCurrentDestination` in the `LaunchedEffect(requestedDestination)` — is
 * exercised by the agent-card-push test, whose destination arrives after the
 * first composition.
 *
 * Each must land Back on the host's SESSION list ([FOLDER_LIST_SCREEN_TAG]),
 * not the host list ([HOST_LIST_CONTENT_TAG]).
 *
 * The Usage tests pin the path whose host-list fallback is CORRECT and must not
 * change: a usage-notification launch (`EXTRA_OPEN_USAGE`) goes Back to the host
 * list, consumes that transient route before Activity recreation, and has the
 * same one-shot contract when delivered to a warm Activity through
 * `onNewIntent` (#2256).
 *
 * ## Harness note
 *
 * This class deliberately uses `createEmptyComposeRule()` +
 * `ActivityScenario.launch(intent)`: every test needs its OWN cold launch
 * INTENT, which the launch-owned `createAndroidComposeRule<MainActivity>()`
 * cannot express (it launches with a fixed default intent in its `before()`).
 * It is the same shape as the already per-push-wired
 * `com.pocketshell.app.proof.DeepLinkSessionSwitchE2eTest`,
 * `com.pocketshell.app.share.ShareTargetE2eTest` and
 * `com.pocketshell.app.hosts.HostResumeLastSessionE2eTest`. The #788
 * interop-placement stall this rule guards against is a Termux `TerminalView`
 * attach problem; this journey never waits on terminal attachment — it only
 * asserts which navigator destination is composed — so it is not exposed to it.
 *
 * Backed by the deterministic Docker `agents` fixture so the sessions are real.
 */
@RunWith(AndroidJUnit4::class)
class Issue1831SessionEntryPathBackE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var hostId: Long = 0L
    private lateinit var fixtureKey: String
    private val trail = mutableListOf<String>()

    @Before
    fun seedFixture() {
        // Issue #1154 / V1: a VOID BLOCK body, never `= runBlocking { … }` —
        // an expression body makes the method non-void and JUnit rejects the
        // whole class at load, so it would silently never run.
        runBlocking {
            clearLastSessionPrefs()
            val key = readFixtureKey()
            fixtureKey = key
            waitForSshFixtureReady(SshKey.Pem(key))
            seedTmuxSession(key)
            hostId = seedDockerHost(key)
            forceFlatHostDetailViewMode()
        }
    }

    @After
    fun tearDown() {
        // runCatching: a scenario whose activity was re-created by an intent
        // flag can refuse to reach DESTROYED; that is teardown noise and must
        // never mask a test's own verdict.
        runCatching { launchedActivity?.close() }
        launchedActivity = null
        clearLastSessionPrefs()
        runBlocking {
            if (::fixtureKey.isInitialized) {
                runCatching { killRemoteSession(fixtureKey) }
            }
        }
    }

    /** Entry path 1: a share-into-session launch. */
    @Test
    fun shareIntoSessionLaunchBackLandsOnSessionListNotHostList() {
        launchAndAssertBackReachesSessionList(
            label = "share-into-session",
            intent = shareIntoSessionIntent(),
        )
    }

    /** Entry path 2: the home-screen active-sessions widget, real production intent. */
    @Test
    fun activeSessionsWidgetLaunchBackLandsOnSessionListNotHostList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val widgetIntent = ActiveSessionsWidgetProvider.widgetLaunchIntent(
            context,
            LastSessionStore.LastSession(
                hostId = hostId,
                hostName = HOST_LABEL,
                hostname = DEFAULT_HOST,
                port = DEFAULT_PORT,
                username = DEFAULT_USER,
                keyPath = keyPathForSeededHost(),
                sessionName = SEEDED_SESSION,
                startDirectory = null,
                savedAtMillis = System.currentTimeMillis(),
            ),
        )
        launchAndAssertBackReachesSessionList(
            label = "active-sessions-widget",
            intent = widgetIntent,
        )
    }

    /** Entry path 3: the #859 agent-card push deep-link (off-Main host resolve). */
    @Test
    fun agentCardPushLaunchBackLandsOnSessionListNotHostList() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val feedIntent = Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SESSION_FEED, true)
            .putExtra(MainActivity.EXTRA_OPEN_SESSION_FEED_SESSION, SEEDED_SESSION)
            .putExtra(MainActivity.EXTRA_OPEN_SESSION_FEED_HOST, DEFAULT_HOST)
        launchAndAssertBackReachesSessionList(
            label = "agent-card-push",
            intent = feedIntent,
        )
    }

    /**
     * The correct-by-design counterpart: a usage-notification launch lands on
     * the Usage screen, whose parent IS the host list — Back must keep going
     * there, not to some host's session list.
     */
    @Test
    fun usageNotificationLaunchBackRecreateDoesNotReplayUsage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        launchedActivity = ActivityScenario.launch(
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_USAGE, true),
        )
        waitFor("usage screen") { nodesWithTag(USAGE_OVERFLOW_TAG) }
        record("00-usage")

        pressSystemBack()
        val reachedHostList = waitFor("host list after Back from Usage") { onHostList() }
        record("01-after-back")
        writeTrail("usage-back-trail.txt")
        assertTrue(
            "Back from the Usage screen must still land on the host list. Observed: " +
                trail.joinToString(" | "),
            reachedHostList,
        )
        assertFalse(
            "Back from Usage must NOT land on a host's session list",
            onFolderList(),
        )

        recreateStoppedActivity()
        val routeAfterRecreate = waitForHostListOrUsage()
        record("02-after-recreate")
        writeTrail("usage-back-recreate-trail.txt")
        assertEquals(
            "ISSUE 2256: the consumed Usage notification route must not replay after Back + " +
                "Activity recreation. Route=$routeAfterRecreate. Observed: " +
                trail.joinToString(" | "),
            PostRecreateRoute.HOST_LIST,
            routeAfterRecreate,
        )
        assertFalse(
            "ISSUE 2256: EXTRA_OPEN_USAGE must be absent from the Activity's retained intent",
            retainedActivityIntentHasUsageExtra(),
        )
    }

    /** The same one-shot contract when a live Activity receives `onNewIntent`. */
    @Test
    fun warmUsageIntentBackRecreateDoesNotReplayUsage() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        launchedActivity = ActivityScenario.launch(Intent(context, MainActivity::class.java))
        assertTrue("warm-delivery precondition must start on the host list", waitFor("host list") {
            onHostList()
        })

        launchedActivity?.onActivity { activity ->
            activity.startActivity(
                Intent(activity, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(MainActivity.EXTRA_OPEN_USAGE, true),
            )
        }
        assertTrue(
            "warm onNewIntent delivery must route to Usage once",
            waitFor("usage screen after warm intent") { nodesWithTag(USAGE_OVERFLOW_TAG) },
        )
        record("00-warm-usage")

        pressSystemBack()
        assertTrue(
            "Back from warm-delivered Usage must land on the host list",
            waitFor("host list after warm Usage Back") { onHostList() },
        )
        record("01-warm-after-back")

        recreateStoppedActivity()
        val routeAfterRecreate = waitForHostListOrUsage()
        record("02-warm-after-recreate")
        writeTrail("usage-warm-back-recreate-trail.txt")
        assertEquals(
            "ISSUE 2256: a warm-delivered consumed Usage route must not replay after Back + " +
                "Activity recreation. Route=$routeAfterRecreate. Observed: " +
                trail.joinToString(" | "),
            PostRecreateRoute.HOST_LIST,
            routeAfterRecreate,
        )
        assertFalse(
            "ISSUE 2256: warm delivery must retain an intent without EXTRA_OPEN_USAGE",
            retainedActivityIntentHasUsageExtra(),
        )
    }

    // ---------------------------------------------------------------- Journey

    private fun launchAndAssertBackReachesSessionList(label: String, intent: Intent) {
        launchedActivity = ActivityScenario.launch(intent)
        assertReachedSessionThenSessionList(label)
    }

    private fun assertReachedSessionThenSessionList(label: String) {
        val reachedSession = waitFor("$label -> session screen") { onSessionScreen() }
        record("00-$label-launched")
        assertTrue(
            "the $label entry path must land on the tmux session screen before Back can be " +
                "judged (this is the precondition, not the assertion under test). Observed: " +
                trail.joinToString(" | "),
            reachedSession,
        )

        pressSystemBack()
        val landedOnSessionList = waitForFolderListOrHostList()
        record("01-$label-after-back")
        writeTrail("$label-back-trail.txt")
        assertTrue(
            "ISSUE 1831: Back from an agent session opened through the $label entry path must " +
                "land on the host's SESSION list, not the host list. Observed: " +
                trail.joinToString(" | "),
            landedOnSessionList,
        )
        assertFalse(
            "ISSUE 1831: the host list must NOT be showing after Back ($label)",
            onHostList(),
        )
    }

    private fun pressSystemBack() {
        launchedActivity?.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    /**
     * Polls until the session list or the host list wins; returns true only for
     * the SESSION list. Never self-skips — a timeout returns false and the
     * caller hard-fails with the recorded trail.
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

    /** Distinguishes a #2256 Usage replay from a generic screen-settle timeout. */
    private fun waitForHostListOrUsage(): PostRecreateRoute {
        val deadline = SystemClock.elapsedRealtime() + SCREEN_SETTLE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (nodesWithTag(USAGE_OVERFLOW_TAG)) return PostRecreateRoute.USAGE
            if (onHostList()) return PostRecreateRoute.HOST_LIST
            SystemClock.sleep(100)
        }
        return PostRecreateRoute.TIMEOUT
    }

    private fun recreateStoppedActivity() {
        launchedActivity?.moveToState(Lifecycle.State.CREATED)
        launchedActivity?.recreate()
        launchedActivity?.moveToState(Lifecycle.State.RESUMED)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun retainedActivityIntentHasUsageExtra(): Boolean {
        var retained = false
        launchedActivity?.onActivity { activity ->
            retained = activity.intent.hasExtra(MainActivity.EXTRA_OPEN_USAGE)
        }
        return retained
    }

    private fun waitFor(label: String, condition: () -> Boolean): Boolean {
        val deadline = SystemClock.elapsedRealtime() + SCREEN_SETTLE_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return true
            SystemClock.sleep(100)
        }
        Log.w(LOG_TAG, "timed out waiting for $label")
        return false
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
            "hostList=${onHostList()} usage=${nodesWithTag(USAGE_OVERFLOW_TAG)}"
        trail += line
        Log.i(LOG_TAG, "ISSUE1831_ENTRY_TRAIL $line")
        println("ISSUE1831_ENTRY_TRAIL $line")
    }

    // ---------------------------------------------------------------- Fixture

    private fun shareIntoSessionIntent(): Intent {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        return Intent(context, MainActivity::class.java)
            .putExtra(MainActivity.EXTRA_OPEN_SESSION_HOST_ID, hostId)
            .putExtra(MainActivity.EXTRA_OPEN_SESSION_HOST_NAME, HOST_LABEL)
            .putExtra(MainActivity.EXTRA_OPEN_SESSION_HOSTNAME, DEFAULT_HOST)
            .putExtra(MainActivity.EXTRA_OPEN_SESSION_PORT, DEFAULT_PORT)
            .putExtra(MainActivity.EXTRA_OPEN_SESSION_USERNAME, DEFAULT_USER)
            .putExtra(MainActivity.EXTRA_OPEN_SESSION_KEY_PATH, keyPathForSeededHost())
            .putExtra(MainActivity.EXTRA_OPEN_SESSION_NAME, SEEDED_SESSION)
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private var seededKeyPath: String = ""

    private fun keyPathForSeededHost(): String = seededKeyPath

    private suspend fun seedDockerHost(key: String): Long {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue1831-entry-key-${System.currentTimeMillis()}",
                content = key,
            )
            seededKeyPath = storedKey.privateKeyPath
            db.hostDao().insert(
                HostEntity(
                    name = HOST_LABEL,
                    hostname = DEFAULT_HOST,
                    port = DEFAULT_PORT,
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
                    "${shellQuote("printf 'ISSUE1831-ENTRY-READY\\n'; exec sleep 900")}",
            )
            appendLine("sleep 1")
            appendLine("tmux has-session -t ${shellQuote(SEEDED_SESSION)}")
        }
        val exec = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }.getOrNull()
        assertTrue(
            "expected tmux seeding to succeed; stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private suspend fun killRemoteSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = com.pocketshell.testssh.TEST_ACCEPT_ALL_HOST_KEYS,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true")
                }
            }
        }
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private fun writeTrail(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        File(dir, name).writeText(trail.joinToString(separator = "\n", postfix = "\n"))
    }

    private enum class PostRecreateRoute {
        HOST_LIST,
        USAGE,
        TIMEOUT,
    }

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue1831EntryPaths"
        const val DEVICE_DIR_NAME: String = "issue1831-session-entry-paths"
        const val HOST_LABEL: String = "Issue1831 EntryPaths"
        const val SEEDED_SESSION: String = "claude-main"
        const val SCREEN_SETTLE_MS: Long = 40_000L
    }
}
