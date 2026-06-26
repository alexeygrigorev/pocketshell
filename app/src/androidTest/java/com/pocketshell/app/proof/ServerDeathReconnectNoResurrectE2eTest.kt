package com.pocketshell.app.proof

import android.content.Context
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
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
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
 * Issue #998 — a remote tmux SERVER death (host reboot / OOM / `kill-server`)
 * must NOT be silently resurrected into a blank "Connected" session.
 *
 * The defect: when the tmux *server* dies, a reattach to an expected-existing
 * session reattaches with `tmux -CC new-session -A`. On a DEAD server that
 * command boots a brand-NEW empty server and a fresh empty session — so the
 * reattach "succeeds" into a blank session that reports Connected, the user's
 * real work is silently gone, and the durable tree still lists vanished
 * sessions. That looks like data loss with no notice.
 *
 * The fix: a reattach to an expected-existing session runs a SERVER-liveness
 * preflight (`tmux has-session` → `no server running on <socket>`), classifies
 * the dead server distinctly from an ordinary transport blip, and drops to the
 * host/session list instead of the silent `new-session -A` resurrection.
 *
 * This reproduces the maintainer's exact reported journey on the REAL connected
 * path (G10/D33) using the deterministic Docker `agents` fixture:
 *
 *  1. Attach to a real seeded tmux session through the normal journey
 *     (host → session picker → Attach).
 *  2. `tmux kill-server` over a sidecar SSH connection — the whole tmux server
 *     (every session) is now gone (isolated fixture socket, never the
 *     maintainer's default socket).
 *  3. Background past the grace window, then foreground — the maintainer's "I
 *     left the app and came back" journey. The foreground fires a
 *     `LifecycleReattach` reconnect, which runs the server-liveness preflight,
 *     sees `no server running`, and drops to the list (instead of the silent
 *     `new-session -A` resurrection).
 *
 * Class coverage (G2): the dead server means EVERY session vanished, so the
 * journey seeds TWO sessions, attaches one, kills the server, and asserts
 * NEITHER session is resurrected — the multi-session class, not the single
 * reported instance.
 *
 * Acceptance:
 *  - No session is resurrected on the dead server: a `tmux list-sessions`
 *    probe taken AFTER the reattach still reports the server dead (the bug
 *    recreated an empty session here, reviving the server).
 *  - The app drops to the host list (a host row is visible) instead of a
 *    resurrected, empty session screen.
 *
 * Artifacts (process.md "Terminal Artifact Review"): a timings file plus a
 * server-liveness probe log so a reviewer can confirm from the SAME run that
 * the server stayed dead and the reattach landed on the list.
 */
@RunWith(AndroidJUnit4::class)
class ServerDeathReconnectNoResurrectE2eTest {

    val compose = createAndroidComposeRule<MainActivity>()

    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private val timings = mutableListOf<String>()

    @After
    fun tearDown() {
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        BackgroundGraceTestOverride.setForTest(null)
        clearBackgroundGraceSetting()
        clearLastSessionPrefs()
        runBlocking {
            if (::fixtureKey.isInitialized) {
                // Bring the fixture's tmux server back for the next test.
                runCatching { runScript(fixtureKey, "tmux kill-server 2>/dev/null || true") }
            }
        }
    }

    private suspend fun seedBeforeLaunch() {
        clearLastSessionPrefs()
        clearBackgroundGraceSetting()
        BackgroundGraceTestOverride.setForTest(null)
        val key = readFixtureKey()
        fixtureKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        // Seed TWO sessions so the multi-session class (every session vanishes on
        // server-death) is exercised, then confirm the server is alive.
        seedTmuxSessions(key)
        assertTrue("seeded primary session must be alive", sessionAlive(key, PRIMARY_SESSION))
        assertTrue("seeded second session must be alive", sessionAlive(key, SECOND_SESSION))
        hostRowTag = seedDockerHost(key)
    }

    @Test
    fun serverDeathOnReconnectDropsToListAndNeverResurrects() = runBlocking {
        val key = fixtureKey

        // ---- (1) Attach to the primary seeded session via the normal journey.
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(PRIMARY_SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(PRIMARY_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        // Let the attach fully settle (panes seeded, Connected revealed).
        delay(LIFECYCLE_DRAIN_MS)

        // ---- (2) KILL THE SERVER. Every session vanishes; the next reattach via
        // `new-session -A` would (on base) silently boot a fresh empty server.
        val killAt = SystemClock.elapsedRealtime()
        runScript(key, "tmux kill-server 2>/dev/null || true")
        recordTiming("kill_server_ms", SystemClock.elapsedRealtime() - killAt)
        assertTrue("the server must be dead after kill-server", serverDead(key))
        recordTiming("server_dead_after_kill", if (serverDead(key)) 1L else 0L)

        // ---- (3) Background past the grace window, then foreground — the
        // maintainer's "I left the app and came back" journey. The foreground
        // fires a LifecycleReattach reconnect, which runs the server-liveness
        // preflight, sees `no server running`, and routes to failServerDied
        // (drop to the list) — NOT the silent `new-session -A` resurrection.
        val reconnectAt = SystemClock.elapsedRealtime()
        BackgroundGraceTestOverride.setForTest(POST_GRACE_MS)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // Wait out the grace window so the app detaches the live client.
        delay(POST_GRACE_MS + LIFECYCLE_DRAIN_MS)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // The reattach runs the preflight against the dead server and drops out
        // of the (now-blank) session into the session list. Give it time.
        compose.waitUntil(timeoutMillis = RECONNECT_TIMEOUT_MS) {
            droppedToSessionList()
        }
        recordTiming("reconnect_to_list_ms", SystemClock.elapsedRealtime() - reconnectAt)

        // ---- Acceptance A: NO session was resurrected on the dead server. The
        // bug's symptom is `new-session -A` reviving an empty server+session. A
        // server-liveness probe taken now must still report the server dead.
        val stillDead = serverDead(key)
        val sessionList = listSessions(key)
        recordTiming("server_resurrected_after_reconnect", if (stillDead) 0L else 1L)
        writeText(
            "server-liveness-probe.txt",
            buildString {
                appendLine("primary_session=$PRIMARY_SESSION")
                appendLine("second_session=$SECOND_SESSION")
                appendLine("server_dead_after_reconnect=$stillDead")
                appendLine("expected_server_dead_after_reconnect=true")
                appendLine("list_sessions_after_reconnect=${sessionList?.trim()}")
            },
        )
        assertTrue(
            "REGRESSION (#998): the tmux server was RESURRECTED on reconnect " +
                "(`new-session -A` revived an empty server) — server-death must " +
                "drop to the list, not recreate. list-sessions=${sessionList?.trim()}",
            stillDead,
        )

        // ---- Acceptance B: the app dropped OUT of the (would-be-resurrected)
        // session into the session list, not a resurrected blank session screen.
        assertTrue(
            "expected to land on the session list after a server-death reconnect; " +
                "the FolderList session-list screen should be visible",
            droppedToSessionList(),
        )
        val sessionScreenStillUp = compose
            .onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        assertFalse(
            "the resurrected (blank) session screen must NOT be showing after a " +
                "server-death reconnect",
            sessionScreenStillUp,
        )

        writeTimings()
        Unit
    }

    // ---------------------------------------------------------------- Helpers

    /**
     * True once the app has dropped OUT of the (would-be-resurrected) session
     * onto the session list: the FolderList session-list screen is present AND
     * the tmux session screen is gone. On the base (silent-resurrection) bug the
     * session screen stays up (a blank "Connected" pane) and this never holds.
     */
    private fun droppedToSessionList(): Boolean {
        val onSessionList = compose
            .onAllNodesWithTag(FOLDER_LIST_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        val sessionScreenGone = compose
            .onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isEmpty()
        return onSessionList && sessionScreenGone
    }

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private fun clearBackgroundGraceSetting() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext
        ctx.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            .edit()
            .remove("background_grace_millis")
            .commit()
    }

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
                name = "issue998-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue998 ServerDeath",
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

    private suspend fun seedTmuxSessions(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-server 2>/dev/null || true")
            appendLine("sleep 1")
            appendLine(
                "tmux new-session -d -s ${shellQuote(PRIMARY_SESSION)} " +
                    "${shellQuote("printf 'ISSUE998-PRIMARY\\n'; exec sleep 600")}",
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SECOND_SESSION)} " +
                    "${shellQuote("printf 'ISSUE998-SECOND\\n'; exec sleep 600")}",
            )
            appendLine("sleep 1")
            appendLine("tmux has-session -t ${shellQuote(PRIMARY_SESSION)}")
        }
        val exec = runScript(key, script)
        assertTrue(
            "expected tmux seeding to succeed; stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded sessions: ${exec?.stdout?.trim()}")
    }

    /** True iff the named tmux session currently exists on the server. */
    private suspend fun sessionAlive(key: String, session: String): Boolean {
        val exec = runScript(
            key,
            "tmux has-session -t ${shellQuote(session)} 2>/dev/null && echo ALIVE || echo GONE",
        )
        return exec?.stdout?.contains("ALIVE") == true
    }

    /**
     * True iff the tmux SERVER is dead (no server behind the socket). `tmux
     * list-sessions` exits non-zero with `no server running on <socket>` when
     * the server is gone — the exact #998 signature.
     */
    private suspend fun serverDead(key: String): Boolean {
        // `tmux list-sessions` exits non-zero with `no server running on
        // <socket>` when the server is gone; print a sentinel on the success
        // branch so a revived (empty) server is unambiguously distinguished.
        val exec = runScript(
            key,
            "tmux list-sessions 2>&1 && echo SERVER_ALIVE || echo SERVER_DEAD",
        )
        val out = exec?.stdout.orEmpty() + exec?.stderr.orEmpty()
        return out.contains("no server running", ignoreCase = true) ||
            (out.contains("SERVER_DEAD") && !out.contains("SERVER_ALIVE"))
    }

    private suspend fun listSessions(key: String): String? =
        runScript(key, "tmux list-sessions 2>&1 || true")?.let {
            (it.stdout + it.stderr)
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
            runCatching {
                compose.onAllNodesWithText(text, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun waitForHostRowPresent(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE998_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE998_TIMINGS ${file.absolutePath}")
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
        println("ISSUE998_TIMING $line")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue998ServerDeath"
        const val DEVICE_DIR_NAME: String = "issue998-server-death-reconnect"

        // Picker entry names shipped by the deterministic `agents` fixture so the
        // normal attach journey reaches them; `tmux` itself is the real binary,
        // so has-session / kill-server are authoritative.
        const val PRIMARY_SESSION: String = "claude-main"
        const val SECOND_SESSION: String = "shell-2"

        const val LIFECYCLE_DRAIN_MS: Long = 1_500L
        // Short grace so the post-grace detach + foreground reattach cycle runs
        // without the user-facing 30s minimum.
        const val POST_GRACE_MS: Long = 1_200L
        const val RECONNECT_TIMEOUT_MS: Long = 30_000L
    }
}
