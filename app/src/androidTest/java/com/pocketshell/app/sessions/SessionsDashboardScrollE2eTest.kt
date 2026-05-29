package com.pocketshell.app.sessions

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_LIST_ADD_FAB_TAG
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issues #268 / #269 — high-session-count scroll + single-FAB regression.
 *
 * On a Pixel 7a with 8+ live tmux sessions the v0.3.5 dashboard could not
 * scroll: the unbounded `SessionsSection` `Column` clipped its overflow,
 * starved the Hosts `LazyColumn` (`weight(1f)`) to ~0 height, and its
 * section-scoped `+` FAB collided with the screen-level add-host FAB (two
 * `+` buttons). The fix folds the Sessions rows and Hosts rows into ONE
 * screen-level `LazyColumn` with exactly one bottom-right `+` FAB.
 *
 * This test seeds 12 sessions on the deterministic Docker `agents` fixture
 * (host port 2222), connects, returns to the dashboard, and proves:
 *
 *  1. Every session row is reachable — scroll the single list to the LAST
 *     of the 12 seeded sessions and assert it is displayed (#268).
 *  2. The Hosts section is reachable regardless of session count — scroll
 *     to the seeded host row and assert it is displayed (#268).
 *  3. Exactly one `+` FAB is visible at the bottom-right at any time — the
 *     screen-level add-host FAB. The old section-scoped new-session FAB is
 *     gone; "+ New session" now lives in the Sessions header (#269).
 *
 * Artifacts written under
 * `<media>/additional_test_output/issue268-269-scroll/`:
 *  - `01-dashboard-top-viewport.png`
 *  - `02-scrolled-last-session-viewport.png`
 *  - `03-scrolled-to-host-viewport.png`
 *  - `timings.txt`
 *  - `summary.txt`
 */
@RunWith(AndroidJUnit4::class)
class SessionsDashboardScrollE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking { runCatching { cleanupSessions(readFixtureKey()) } }
    }

    @Test
    fun highSessionCountScrollsAndShowsSingleFab() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        cleanupSessions(key)
        seedManySessions(key)
        val hostRowTag = seedDockerHost(key, "Issue268 Scroll Dashboard")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // --- (1) Tap the host card, then attach to the first seeded
        // session. Attach wires the live tmux client into [ActiveTmuxClients]
        // so the dashboard's per-host poller can list-sessions.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

        val firstSession = sessionName(0)
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithText(firstSession, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onAllNodesWithText(firstSession, useUnmergedTree = true)
            .onFirst()
            .performClick()

        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // --- (2) Pop back to the dashboard so the Sessions section renders
        // with all 12 rows. The tmux screen and the FolderListScreen each
        // own a "‹" back affordance; pop until the dashboard re-appears.
        compose.onAllNodesWithText("‹", useUnmergedTree = true).onFirst().performClick()
        val dashboardVisible = runCatching {
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(DASHBOARD_SESSIONS_SECTION_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            true
        }.getOrDefault(false)
        if (!dashboardVisible) {
            // One more back pop (tmux → folder list → dashboard).
            compose.onAllNodesWithText("‹", useUnmergedTree = true).onFirst().performClick()
            compose.waitUntil(timeoutMillis = 30_000) {
                compose.onAllNodesWithTag(DASHBOARD_SESSIONS_SECTION_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
        }
        // Wait until at least the first row populates (poller round-trip).
        compose.waitUntil(timeoutMillis = 60_000) {
            compose.onAllNodesWithTag(
                DASHBOARD_SESSION_ROW_TAG_PREFIX + firstSession,
                useUnmergedTree = true,
            ).fetchSemanticsNodes().isNotEmpty()
        }
        captureFullDevice("01-dashboard-top")

        // The single screen-level FAB must already be the only `+` FAB.
        assertSingleFab(stage = "at dashboard top")

        // --- (3) #268 AC: every session row is reachable. Scroll the single
        // LazyColumn to the LAST seeded session row and assert it shows.
        val lastSession = sessionName(SESSION_COUNT - 1)
        val scrollStart = SystemClock.elapsedRealtime()
        // The dashboard poller returns the full session list in one round
        // trip, so once the first row appears all 12 are backing list
        // items. `performScrollToNode` realizes off-screen lazy items as it
        // scrolls, so this reaches the last of the 12 rows. Retry to absorb
        // any late poll that grows the list after the first emission.
        compose.waitUntil(timeoutMillis = 60_000) {
            runCatching { scrollListToTag(DASHBOARD_SESSION_ROW_TAG_PREFIX + lastSession) }
                .isSuccess &&
                compose.onAllNodesWithTag(
                    DASHBOARD_SESSION_ROW_TAG_PREFIX + lastSession,
                    useUnmergedTree = true,
                ).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(
            DASHBOARD_SESSION_ROW_TAG_PREFIX + lastSession,
            useUnmergedTree = true,
        ).assertExists()
        recordTiming("scroll_to_last_session_ms", SystemClock.elapsedRealtime() - scrollStart)
        captureFullDevice("02-scrolled-last-session")
        assertSingleFab(stage = "after scroll to last session")

        // --- (4) #268 AC: the Hosts section is reachable regardless of
        // session count. Scroll on to the host row and assert it shows.
        scrollListToTag(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).assertExists()
        captureFullDevice("03-scrolled-to-host")
        assertSingleFab(stage = "after scroll to host")

        writeTimings()
        writeSummary()
        Unit
    }

    // ---------------------------------------------------------------- Helpers

    private fun scrollListToTag(tag: String) {
        // The single screen-level LazyColumn is the only scroll container;
        // scroll it to the target row by tag.
        compose.onNode(
            androidx.compose.ui.test.hasScrollAction(),
            useUnmergedTree = true,
        ).performScrollToNode(androidx.compose.ui.test.hasTestTag(tag))
    }

    private fun assertSingleFab(stage: String) {
        val fabCount = compose
            .onAllNodesWithTag(HOST_LIST_ADD_FAB_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
        assertEquals(
            "exactly one screen-level + FAB expected ($stage); got $fabCount",
            1,
            fabCount,
        )
        // The retired section-scoped FAB is gone: `DASHBOARD_NEW_SESSION_TAG`
        // now rides the header "+ New session" button, never a second
        // bottom-right FAB. The header item is part of the single LazyColumn,
        // so it may be disposed (count 0) once scrolled out of view; what
        // must NEVER happen is two nodes carrying it (the old duplicate-FAB
        // bug). Assert at-most-one.
        val newSessionTagCount = compose
            .onAllNodesWithTag(DASHBOARD_NEW_SESSION_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
        assertTrue(
            "new-session affordance must be a single header button, never a 2nd FAB ($stage); got $newSessionTagCount",
            newSessionTagCount <= 1,
        )
    }

    private fun sessionName(index: Int): String = "scroll-sess-%02d".format(index)

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
                name = "issue268-key-${System.currentTimeMillis()}",
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

    private suspend fun seedManySessions(key: String) {
        val script = buildString {
            appendLine("set -eu")
            for (i in 0 until SESSION_COUNT) {
                val name = sessionName(i)
                appendLine("tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true")
                appendLine(
                    "tmux new-session -d -s ${shellQuote(name)} " +
                        shellQuote("printf 'READY-$i\\n'; exec sh"),
                )
            }
            appendLine("tmux list-sessions | wc -l")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 20_000,
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        org.junit.Assert.assertTrue(
            "expected seeding $SESSION_COUNT sessions to succeed; got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session count: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupSessions(key: String) {
        runCatching {
            withTimeout(30_000) {
                val script = buildString {
                    for (i in 0 until SESSION_COUNT) {
                        appendLine(
                            "tmux kill-session -t ${shellQuote(sessionName(i))} 2>/dev/null || true",
                        )
                    }
                }
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = SshKey.Pem(key),
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 15_000,
                ).mapCatching { session -> session.use { it.exec(script) } }
            }
        }
    }

    private fun captureFullDevice(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        val file = artifactFile("$name-viewport.png")
        try {
            FileOutputStream(file).use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    "could not write screenshot ${file.absolutePath}"
                }
            }
            println("ISSUE268_SCREENSHOT ${file.absolutePath}")
        } finally {
            bitmap.recycle()
        }
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = instrumentation.targetContext.externalMediaDirs
            .firstOrNull { it != null }
            ?: instrumentation.targetContext.getExternalFilesDir(null)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE268_TIMING $line")
    }

    private fun writeTimings() {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE268_TIMINGS ${file.absolutePath}")
    }

    private fun writeSummary() {
        val file = artifactFile("summary.txt")
        file.writeText(
            buildString {
                appendLine("scenario=dashboard-scroll-high-session-count")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("seeded_session_count=$SESSION_COUNT")
                appendLine("first_session=${sessionName(0)}")
                appendLine("last_session=${sessionName(SESSION_COUNT - 1)}")
                appendLine("expected_fab_count=1  # single screen-level add-host FAB (#269)")
                appendLine("artifacts:")
                appendLine("  01-dashboard-top-viewport.png")
                appendLine("  02-scrolled-last-session-viewport.png")
                appendLine("  03-scrolled-to-host-viewport.png")
                appendLine("  timings.txt")
            },
        )
        println("ISSUE268_SUMMARY ${file.absolutePath}")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue268ScrollDashboard"
        const val DEVICE_DIR_NAME: String = "issue268-269-scroll"
        const val SESSION_COUNT: Int = 12
    }
}
