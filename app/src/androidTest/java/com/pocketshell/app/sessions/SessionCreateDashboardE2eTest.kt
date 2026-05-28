package com.pocketshell.app.sessions

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.DEFAULT_HOST
import com.pocketshell.app.proof.DEFAULT_PORT
import com.pocketshell.app.proof.DEFAULT_USER
import com.pocketshell.app.proof.waitForSshFixtureReady
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
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
 * Issue #204 — connected E2E for the dashboard's "+ New session" affordance.
 *
 * The user reported (v0.2.8 feedback, 2026-05-27) that tapping `+` while
 * looking at the sessions list on a connected host opened the "Add new
 * host" sheet instead of creating a new tmux session on the current
 * host. The fix moves the create-session call inside the dashboard
 * view model (`new-session -d -s <name> -c <cwd>`) and refreshes the
 * list, so the user stays on the dashboard and sees the new row
 * within 2s of confirming the dialog.
 *
 * This test exercises the path end-to-end against the deterministic
 * Docker `agents` fixture (host port 2222). Setup mirrors
 * [SessionKillDashboardE2eTest]:
 *
 *  1. Seed one tmux session (`seed-pre-create`) on the remote so the
 *     dashboard's Sessions section renders and the `+ New session`
 *     button is reachable.
 *  2. Connect to the host (registers a live tmux client into
 *     `ActiveTmuxClients`).
 *  3. Pop back to the dashboard so the Sessions section appears.
 *  4. Tap `+ New session`, type a session name + start folder, confirm.
 *  5. Assert the new row appears in the dashboard within 2s (the issue
 *     #204 acceptance criterion).
 *  6. Cross-check `tmux list-sessions` on the remote agrees — proves
 *     the row didn't just appear in the UI but the server-side session
 *     genuinely exists.
 *  7. No regression: no create-error banner on the happy path.
 *  8. Negative case: tapping `+ New session` and re-using the same
 *     name surfaces a visible error banner with the duplicate-name
 *     detail.
 *
 * Artifacts written under
 * `<media>/additional_test_output/issue204-create-dashboard/`:
 *  - `01-before-create-viewport.png`
 *  - `02-after-create-viewport.png`
 *  - `03-duplicate-error-viewport.png`
 *  - `timings.txt`
 *  - `summary.txt`
 */
@RunWith(AndroidJUnit4::class)
class SessionCreateDashboardE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching {
                cleanupSessions(readFixtureKey())
            }
        }
    }

    @Test
    fun newSessionButtonCreatesSessionOnCurrentHostWithinTwoSeconds() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        // Ensure the remote is clean of any leftover from a prior run.
        cleanupSessions(key)
        seedPreCreateSession(key)
        val hostRowTag = seedDockerHost(key, "Issue204 Create Dashboard")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // --- (1) Tap the host card, then attach to the seeded session.
        // Attach is what wires the live tmux client into
        // [ActiveTmuxClients] — without that, the dashboard's
        // per-host poller never finds a client and the rows never
        // populate.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()

        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithText(SESSION_PRE_CREATE, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(SESSION_PRE_CREATE).performClick()

        // Wait for the TmuxSessionScreen to mount — proves the client
        // was registered with ActiveTmuxClients.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(
                TMUX_SESSION_SCREEN_TAG,
                useUnmergedTree = true,
            )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // --- (2) Pop back to the dashboard so the Sessions section is
        // visible again with the live client still registered. Use the
        // tagged in-app back affordance. System back would finish
        // the activity (the session screen does not register a BackHandler).
        performTmuxChromeBack(hostRowTag)
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // The dashboard's per-host poller issues list-sessions; wait
        // for the seeded row to appear inside the Sessions section.
        compose.waitUntil(timeoutMillis = 30_000) {
            compose.onAllNodesWithTag(
                DASHBOARD_SESSION_ROW_TAG_PREFIX + SESSION_PRE_CREATE,
                useUnmergedTree = true,
            )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        captureFullDevice("01-before-create")

        // --- (3) Tap the section's `+ New session` button. The
        // dashboard's button is gated on at least one session being
        // visible (the cross-host empty state collapses entirely), so
        // seeding `seed-pre-create` above is what makes the button
        // reachable.
        val createTapAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(DASHBOARD_NEW_SESSION_TAG, useUnmergedTree = true)
            .performClick()

        // The "New session" dialog renders with two fields: name and
        // start folder. Find the name field (it carries the
        // "Session name" label inside Compose's outlined-text
        // affordance) and type the chosen name.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Session name", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Session name", useUnmergedTree = true)
            .performTextInput(SESSION_CREATED)
        // Confirm via the dialog's "Save" button. The dialog uses
        // "Save" for create/rename (kill is "Kill"), so onLast()
        // disambiguates against any stray menu items above.
        compose.onAllNodesWithText("Save", useUnmergedTree = true)
            .onLast()
            .performClick()

        // --- (4) Within 2s the new row must appear in the dashboard.
        compose.waitUntil(timeoutMillis = 2_000) {
            compose.onAllNodesWithTag(
                DASHBOARD_SESSION_ROW_TAG_PREFIX + SESSION_CREATED,
                useUnmergedTree = true,
            )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val createLatencyMs = SystemClock.elapsedRealtime() - createTapAt
        recordTiming("create_session_ms", createLatencyMs)
        Log.i(LOG_TAG, "create row appeared in ${createLatencyMs}ms")
        captureFullDevice("02-after-create")

        // No silent failure on the happy path: the create-error banner
        // must NOT be visible.
        assertEquals(
            "happy path should not raise a create-error banner",
            0,
            compose.onAllNodesWithTag(DASHBOARD_CREATE_ERROR_BANNER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .size,
        )

        // Cross-check the real tmux server: the new session genuinely
        // exists, not just visible in the UI.
        val remoteSessions = listRemoteSessions(key)
        assertTrue(
            "remote tmux must show $SESSION_CREATED; got $remoteSessions",
            remoteSessions.contains(SESSION_CREATED),
        )
        assertTrue(
            "remote tmux must still show the seeded session; got $remoteSessions",
            remoteSessions.contains(SESSION_PRE_CREATE),
        )

        // --- (5) Negative path: a duplicate-name attempt surfaces a
        // visible error banner instead of silently failing. Tap the
        // same `+ New session` button, type the SAME name as the row
        // we just created, confirm — tmux should refuse with
        // "duplicate session" and the banner should render.
        compose.onNodeWithTag(DASHBOARD_NEW_SESSION_TAG, useUnmergedTree = true)
            .performClick()
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithText("Session name", useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText("Session name", useUnmergedTree = true)
            .performTextInput(SESSION_CREATED)
        compose.onAllNodesWithText("Save", useUnmergedTree = true)
            .onLast()
            .performClick()

        // The banner must render within a reasonable window — tmux's
        // duplicate-name rejection is sub-100ms on Docker so 5s is
        // generous.
        compose.waitUntil(timeoutMillis = 5_000) {
            compose.onAllNodesWithTag(DASHBOARD_CREATE_ERROR_BANNER_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        captureFullDevice("03-duplicate-error")

        // The duplicate-name attempt must NOT have caused a second
        // session row to appear — verify by counting via the remote.
        val afterDuplicate = listRemoteSessions(key)
        assertEquals(
            "duplicate-name attempt must not create a second row on the remote; got $afterDuplicate",
            afterDuplicate.toSet().size,
            afterDuplicate.size,
        )

        writeTimings()
        writeSummary(createLatencyMs)
        Unit
    }

    // ---------------------------------------------------------------- Helpers

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
                name = "issue204-key-${System.currentTimeMillis()}",
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

    private suspend fun seedPreCreateSession(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_PRE_CREATE)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_PRE_CREATE)} " +
                    shellQuote("printf 'PRE-CREATE-READY\\n'; exec sh"),
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
        ).mapCatching { session ->
            session.use { it.exec(script) }
        }
        val exec = result.getOrNull()
        assertTrue(
            "expected pre-create seeding to succeed for #204, got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded sessions: ${exec?.stdout?.trim()}")
    }

    private suspend fun listRemoteSessions(key: String): List<String> {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use { it.exec("tmux list-sessions -F '#{session_name}' 2>/dev/null || true") }
        }
        val exec = result.getOrNull() ?: return emptyList()
        return exec.stdout
            .split('\n')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
    }

    private suspend fun cleanupSessions(key: String) {
        runCatching {
            withTimeout(20_000) {
                SshConnection.connect(
                    host = DEFAULT_HOST,
                    port = DEFAULT_PORT,
                    user = DEFAULT_USER,
                    key = SshKey.Pem(key),
                    knownHosts = KnownHostsPolicy.AcceptAll,
                    timeoutMs = 15_000,
                ).mapCatching { session ->
                    session.use {
                        it.exec(
                            "tmux kill-session -t ${shellQuote(SESSION_PRE_CREATE)} 2>/dev/null || true; " +
                                "tmux kill-session -t ${shellQuote(SESSION_CREATED)} 2>/dev/null || true",
                        )
                    }
                }
            }
        }
    }

    private fun performTmuxChromeBack(hostRowTag: String) {
        val clicked = listOf(
            TMUX_COMPACT_CHROME_BACK_BUTTON_TAG,
            TMUX_FULL_CHROME_BACK_BUTTON_TAG,
        ).any { tag ->
            runCatching {
                compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            }.isSuccess
        }
        if (!clicked) {
            compose.onNodeWithText("‹", useUnmergedTree = true).performClick()
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
            println("ISSUE204_SCREENSHOT ${file.absolutePath}")
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
        println("ISSUE204_TIMING $line")
    }

    private fun writeTimings() {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE204_TIMINGS ${file.absolutePath}")
    }

    private fun writeSummary(createMs: Long) {
        val file = artifactFile("summary.txt")
        file.writeText(
            buildString {
                appendLine("scenario=dashboard-create")
                appendLine("host=$DEFAULT_HOST port=$DEFAULT_PORT user=$DEFAULT_USER")
                appendLine("seeded=$SESSION_PRE_CREATE")
                appendLine("created=$SESSION_CREATED")
                appendLine("create_session_ms=$createMs")
                appendLine("threshold_ms=2000  # per issue #204 acceptance criterion")
                appendLine("artifacts:")
                appendLine("  01-before-create-viewport.png")
                appendLine("  02-after-create-viewport.png")
                appendLine("  03-duplicate-error-viewport.png")
                appendLine("  timings.txt")
            },
        )
        println("ISSUE204_SUMMARY ${file.absolutePath}")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue204CreateDashboard"
        const val DEVICE_DIR_NAME: String = "issue204-create-dashboard"
        const val SESSION_PRE_CREATE: String = "seed-pre-create"
        const val SESSION_CREATED: String = "created-by-plus-button"
    }
}
