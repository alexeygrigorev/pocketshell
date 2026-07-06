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
 * Issue #666 REOPEN (2026-07-06) — a tmux session the user killed on the host
 * must NOT be resurrected when the app REATTACHES to it (the lifecycle /
 * reconnect path), with the tmux SERVER still alive.
 *
 * The maintainer's exact dogfood journey (reported again on v0.4.23): "the bug
 * where it creates a session when I removed it from the computer is still here."
 * You are in a session, you leave the app briefly (to screenshot/annotate), the
 * session is removed on the computer, you come back — and the app RECREATES the
 * killed session server-side.
 *
 * The prior #666 fix guarded the process-death COLD-RESTORE path
 * ([ColdRestoreGoneSessionNoResurrectE2eTest]) but the RECONNECT/REATTACH path
 * still leaked: on a `LifecycleReattach` / `AutoReconnect` / `Reconnect`
 * (`probeServerLiveness=true`, `createIfMissing=true`) the `has-session`
 * preflight saw the SERVER alive but the ONE target session gone and FELL
 * THROUGH to `tmux -CC new-session -A` (attach-OR-create) → resurrection. The
 * fix: a gone session on a LIVE server throws [TmuxSessionNotFoundException] on
 * EVERY reattach path (not just cold restore), so the app drops to the list.
 *
 * This reproduces the reopened symptom on the REAL connected path (G10/D33)
 * using the deterministic Docker `agents` fixture:
 *
 *  1. Seed TWO sessions — the target (`claude-main`) and a KEEPALIVE
 *     (`shell-2`). The keepalive is load-bearing: it keeps the tmux SERVER
 *     alive after the target is killed, so the journey exercises the
 *     server-alive-session-gone branch (the #666 case) and NOT the dead-server
 *     branch (the #998 case, already covered). A happy fixture that killed the
 *     only session would kill the server too and mask this exact leak (G10).
 *  2. Attach to `claude-main` through the normal journey.
 *  3. Kill ONLY `claude-main` over a sidecar SSH connection; `shell-2` (and the
 *     server) stay alive. `claude-main` is now gone on a LIVE server.
 *  4. Background past the grace window, then foreground — the maintainer's "I
 *     left the app and came back" journey. The foreground fires a
 *     `LifecycleReattach` reconnect, which runs the server-liveness preflight,
 *     sees the target session gone (server alive), and drops to the list
 *     instead of the silent `new-session -A` resurrection.
 *
 * Acceptance:
 *  - `claude-main` is NOT resurrected: a `tmux has-session` probe taken AFTER
 *    the reattach still fails (on base the reattach recreated it here).
 *  - The tmux SERVER stayed alive throughout (the keepalive `shell-2` is still
 *    there) — proving this is the server-alive-session-gone branch.
 *  - The app drops to the session list instead of a resurrected, empty session
 *    screen.
 */
@RunWith(AndroidJUnit4::class)
class LifecycleReattachGoneSessionNoResurrectE2eTest {

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
                // Reset the fixture's tmux server for the next test.
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
        // Seed the target AND a keepalive so the server survives the target kill.
        seedTmuxSessions(key)
        assertTrue("seeded target session must be alive", sessionAlive(key, TARGET_SESSION))
        assertTrue("seeded keepalive session must be alive", sessionAlive(key, KEEPALIVE_SESSION))
        hostRowTag = seedDockerHost(key)
    }

    @Test
    fun lifecycleReattachToKilledSessionOnLiveServerDropsToListAndNeverResurrects() { runBlocking {
        val key = fixtureKey

        // ---- (1) Attach to the target session via the normal journey.
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(TARGET_SESSION, timeoutMs = 20_000)
        compose.onNodeWithText(TARGET_SESSION).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        // Let the attach fully settle (panes seeded, Connected revealed).
        delay(LIFECYCLE_DRAIN_MS)

        // ---- (2) Kill ONLY the target session. The server + keepalive stay
        // alive, so this is the server-alive-session-gone branch (the #666 case).
        val killAt = SystemClock.elapsedRealtime()
        killRemoteSession(key, TARGET_SESSION)
        recordTiming("kill_session_ms", SystemClock.elapsedRealtime() - killAt)
        assertTrue(
            "the target session must be gone on the server after kill-session",
            !sessionAlive(key, TARGET_SESSION),
        )
        assertTrue(
            "the keepalive session must still be alive (server stays up)",
            sessionAlive(key, KEEPALIVE_SESSION),
        )
        recordTiming("target_alive_after_kill", if (sessionAlive(key, TARGET_SESSION)) 1L else 0L)
        recordTiming("keepalive_alive_after_kill", if (sessionAlive(key, KEEPALIVE_SESSION)) 1L else 0L)

        // ---- (3) Background past the grace window, then foreground — the
        // maintainer's "I left the app and came back" journey. Foreground fires a
        // LifecycleReattach reconnect, which runs the server-liveness preflight,
        // sees the target session gone (server alive), and routes to
        // failSessionEnded (drop to the list) — NOT `new-session -A`.
        val reattachAt = SystemClock.elapsedRealtime()
        BackgroundGraceTestOverride.setForTest(POST_GRACE_MS)
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        // Wait out the grace window so the app detaches the live client.
        delay(POST_GRACE_MS + LIFECYCLE_DRAIN_MS)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)

        // The reattach runs the preflight against the gone session and drops out
        // of the (now-dead) session into the session list. Give it time.
        compose.waitUntil(timeoutMillis = RECONNECT_TIMEOUT_MS) {
            droppedToSessionList()
        }
        recordTiming("reattach_to_list_ms", SystemClock.elapsedRealtime() - reattachAt)

        // ---- Acceptance A: the killed session was NOT resurrected server-side.
        // The bug's symptom is `new-session -A` reviving the killed session. A
        // has-session probe taken now must still fail.
        val stillGone = !sessionAlive(key, TARGET_SESSION)
        val keepaliveAlive = sessionAlive(key, KEEPALIVE_SESSION)
        recordTiming("target_recreated_after_reattach", if (stillGone) 0L else 1L)
        recordTiming("keepalive_alive_after_reattach", if (keepaliveAlive) 1L else 0L)
        writeText(
            "has-session-probe.txt",
            buildString {
                appendLine("target_session=$TARGET_SESSION")
                appendLine("keepalive_session=$KEEPALIVE_SESSION")
                appendLine("target_alive_after_reattach=${!stillGone}")
                appendLine("expected_target_alive_after_reattach=false")
                appendLine("keepalive_alive_after_reattach=$keepaliveAlive")
                appendLine("expected_keepalive_alive_after_reattach=true")
            },
        )
        assertTrue(
            "REGRESSION (#666 reopen): the killed session `$TARGET_SESSION` was " +
                "RECREATED on lifecycle reattach (tmux has-session succeeded) — a " +
                "reattach must not resurrect a session gone on a live server",
            stillGone,
        )
        // The server stayed alive throughout (keepalive present) — this proves the
        // journey exercised the server-alive-session-gone branch, not server death.
        assertTrue(
            "the keepalive session must still be alive — the server must have stayed " +
                "up so this is the #666 (session-gone) branch, not the #998 (server-dead) branch",
            keepaliveAlive,
        )

        // ---- Acceptance B: the app dropped OUT of the (would-be-resurrected)
        // session into the session list, not a resurrected blank session screen.
        assertTrue(
            "expected to land on the session list after a gone-session reattach; " +
                "the FolderList session-list screen should be visible",
            droppedToSessionList(),
        )
        val sessionScreenStillUp = compose
            .onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()
        assertFalse(
            "the resurrected session screen must NOT be showing after a gone-session reattach",
            sessionScreenStillUp,
        )

        writeTimings()
        Unit
    } }

    // ---------------------------------------------------------------- Helpers

    /**
     * True once the app has dropped OUT of the (would-be-resurrected) session
     * onto the session list: the FolderList session-list screen is present AND
     * the tmux session screen is gone. On the base (silent-resurrection) bug the
     * session screen stays up (a resurrected pane) and this never holds.
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
                name = "issue666reopen-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue666 ReattachGone",
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
                "tmux new-session -d -s ${shellQuote(TARGET_SESSION)} " +
                    "${shellQuote("printf 'ISSUE666-TARGET\\n'; exec sleep 600")}",
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(KEEPALIVE_SESSION)} " +
                    "${shellQuote("printf 'ISSUE666-KEEPALIVE\\n'; exec sleep 600")}",
            )
            appendLine("sleep 1")
            appendLine("tmux has-session -t ${shellQuote(TARGET_SESSION)}")
        }
        val exec = runScript(key, script)
        assertTrue(
            "expected tmux seeding to succeed; stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded sessions: ${exec?.stdout?.trim()}")
    }

    private suspend fun killRemoteSession(key: String, session: String) {
        runScript(
            key,
            "tmux kill-session -t ${shellQuote(session)} 2>/dev/null || true",
        )
    }

    /** True iff the named tmux session currently exists on the server. */
    private suspend fun sessionAlive(key: String, session: String): Boolean {
        val exec = runScript(
            key,
            "tmux has-session -t ${shellQuote(session)} 2>/dev/null && echo ALIVE || echo GONE",
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
        println("ISSUE666REOPEN_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE666REOPEN_TIMINGS ${file.absolutePath}")
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
        println("ISSUE666REOPEN_TIMING $line")
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue666ReattachGone"
        const val DEVICE_DIR_NAME: String = "issue666-reopen-lifecycle-reattach-gone-session"

        // Picker entry names shipped by the deterministic `agents` fixture so the
        // normal attach journey reaches them; `tmux` itself is the real binary,
        // so has-session / kill-session are authoritative.
        const val TARGET_SESSION: String = "claude-main"
        const val KEEPALIVE_SESSION: String = "shell-2"

        const val LIFECYCLE_DRAIN_MS: Long = 1_500L
        // Short grace so the post-grace detach + foreground reattach cycle runs
        // without the user-facing 30s minimum.
        const val POST_GRACE_MS: Long = 1_200L
        const val RECONNECT_TIMEOUT_MS: Long = 30_000L
    }
}
