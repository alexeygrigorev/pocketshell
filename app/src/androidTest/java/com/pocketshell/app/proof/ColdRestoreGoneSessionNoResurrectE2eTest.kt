package com.pocketshell.app.proof

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue #666 — a tmux session the user killed elsewhere must NOT be
 * resurrected on app resume.
 *
 * The maintainer's exact dogfood journey (reported twice): you are in a
 * session, you leave the app (to screenshot/annotate), you kill that session
 * on the computer, you come back — and the app reopens the killed session AND,
 * because it no longer exists, RECREATES it server-side (`tmux new-session -A`
 * is attach-OR-create). Expected: a gone session must not be recreated; drop
 * to the host/session list.
 *
 * This test reproduces it on the deterministic Docker `agents` fixture:
 *
 *  1. Attach to a real seeded tmux session through the normal journey
 *     (host -> session picker -> Attach).
 *  2. `moveToState(CREATED)` so `MainActivity.onStop` persists the last
 *     session into [com.pocketshell.app.session.LastSessionStore] (#177).
 *  3. Kill that tmux session over a sidecar SSH connection — it is now gone
 *     on the server.
 *  4. `recreate()` the activity, which drives `onSaveInstanceState` +
 *     `onCreate(savedInstanceState != null)` — the process-death-resume path
 *     that reads the persisted snapshot and cold-restores into it
 *     (`TmuxConnectTrigger.ColdRestore`).
 *
 * Acceptance:
 *  - The killed session is NOT recreated on the server: a `tmux has-session`
 *    probe taken AFTER the restore still fails (the bug recreated it here).
 *  - The app drops to the host list (a host row is visible) instead of
 *    showing a resurrected, empty session screen.
 *
 * Artifacts (process.md "Terminal Artifact Review"): a timings file plus a
 * has-session probe log so a reviewer can confirm from the SAME run that the
 * session stayed gone and the restore landed on the list.
 */
@RunWith(AndroidJUnit4::class)
class ColdRestoreGoneSessionNoResurrectE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    @After
    fun tearDown() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching { killRemoteSession(readFixtureKey()) }
        }
        clearLastSessionPrefs()
    }

    @Test
    fun coldRestoreToKilledSessionDoesNotRecreateAndLandsOnList() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        clearLastSessionPrefs()

        // Real tmux session, named to match a picker entry so the normal
        // attach journey can reach it. The `tmux` shim delegates to the real
        // binary, so has-session / kill-session are authoritative.
        seedTmuxSession(key)
        assertTrue("seeded session must be alive before the journey", sessionAlive(key))

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // ---- (1) Attach to the seeded session via the normal journey.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SEEDED_SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(SEEDED_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()

        // ---- (2) Background -> onStop persists the last session (#177).
        val stopAt = SystemClock.elapsedRealtime()
        launchedActivity?.moveToState(Lifecycle.State.CREATED)
        delay(LIFECYCLE_DRAIN_MS)
        recordTiming("stop_drain_ms", SystemClock.elapsedRealtime() - stopAt)

        // ---- (3) Kill the session on the server. It is now GONE.
        val killAt = SystemClock.elapsedRealtime()
        killRemoteSession(key)
        recordTiming("kill_session_ms", SystemClock.elapsedRealtime() - killAt)
        assertTrue(
            "the session must be gone on the server after kill-session",
            !sessionAlive(key),
        )
        recordTiming("session_alive_after_kill", if (sessionAlive(key)) 1L else 0L)

        // ---- (4) Resume via recreate -> savedInstanceState != null -> the
        // process-death cold-restore path reads the persisted snapshot and
        // attaches ColdRestore.
        val resumeAt = SystemClock.elapsedRealtime()
        launchedActivity?.recreate()
        launchedActivity?.moveToState(Lifecycle.State.RESUMED)
        recordTiming("recreate_ms", SystemClock.elapsedRealtime() - resumeAt)

        // ---- (5) Give the cold-restore attach-only preflight time to run,
        // find the session gone, and route to the list.
        compose.waitUntil(timeoutMillis = RESTORE_TIMEOUT_MS) {
            onHostList(hostRowTag)
        }

        // ---- Acceptance A: the killed session was NOT recreated server-side.
        // The bug's second symptom is `new-session -A` recreating it. A
        // has-session probe taken now must still fail.
        val stillGone = !sessionAlive(key)
        recordTiming("session_recreated_after_restore", if (stillGone) 0L else 1L)
        writeText(
            "has-session-probe.txt",
            buildString {
                appendLine("session=$SEEDED_SESSION")
                appendLine("alive_after_restore=${!stillGone}")
                appendLine("expected_alive_after_restore=false")
            },
        )
        assertTrue(
            "REGRESSION: the killed session `$SEEDED_SESSION` was RECREATED on resume " +
                "(tmux has-session succeeded) — cold-restore must not resurrect it",
            stillGone,
        )

        // ---- Acceptance B: the app dropped to the host list, not a
        // resurrected empty session screen.
        assertTrue(
            "expected to land on the host list after a gone-session restore; " +
                "a host row should be visible",
            onHostList(hostRowTag),
        )
        val sessionScreenStillUp = compose
            .onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        assertTrue(
            "the resurrected session screen must NOT be showing after a gone-session restore",
            !sessionScreenStillUp,
        )

        recordTiming("restore_to_list_ms", SystemClock.elapsedRealtime() - resumeAt)
        writeTimings()
        Unit
    }

    // ---------------------------------------------------------------- Helpers

    private fun onHostList(hostRowTag: String): Boolean =
        compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

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
                name = "issue666-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue666 GoneRestore",
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
            appendLine("tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SEEDED_SESSION)} " +
                    "${shellQuote("printf 'ISSUE666-READY\\n'; exec sleep 600")}",
            )
            appendLine("sleep 1")
            appendLine("tmux has-session -t ${shellQuote(SEEDED_SESSION)}")
        }
        val exec = runScript(key, script)
        assertTrue(
            "expected tmux seeding to succeed; stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
    }

    private suspend fun killRemoteSession(key: String) {
        runScript(
            key,
            "tmux kill-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null || true",
        )
    }

    /** True iff the seeded tmux session currently exists on the server. */
    private suspend fun sessionAlive(key: String): Boolean {
        val exec = runScript(
            key,
            "tmux has-session -t ${shellQuote(SEEDED_SESSION)} 2>/dev/null && echo ALIVE || echo GONE",
        )
        return exec?.stdout?.contains("ALIVE") == true
    }

    private suspend fun runScript(key: String, script: String) =
        SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }.getOrNull()

    private fun waitForText(text: String, timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE666_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE666_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun artifactFile(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val mediaRoot = com.pocketshell.app.test.testArtifactsRoot(instrumentation.targetContext)
        val dir = File(mediaRoot, "additional_test_output/$DEVICE_DIR_NAME")
        check(dir.exists() || dir.mkdirs()) {
            "could not create artifact directory ${dir.absolutePath}"
        }
        return File(dir, name)
    }

    private fun recordTiming(name: String, value: Long) {
        val line = "$name=$value"
        timings += line
        println("ISSUE666_TIMING $line")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue666GoneRestore"
        const val DEVICE_DIR_NAME: String = "issue666-cold-restore-gone-session"

        // A picker entry name shipped by the deterministic `agents` fixture so
        // the normal attach journey reaches it; `tmux` itself is the real
        // binary, so has-session/kill-session are authoritative.
        const val SEEDED_SESSION: String = "claude-main"

        const val LIFECYCLE_DRAIN_MS: Long = 750L
        const val RESTORE_TIMEOUT_MS: Long = 20_000L
    }
}
