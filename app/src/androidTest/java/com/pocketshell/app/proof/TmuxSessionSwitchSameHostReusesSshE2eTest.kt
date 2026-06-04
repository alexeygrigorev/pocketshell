package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.sessions.SSH_SOURCE_FOLDER_LIST_PROBE
import com.pocketshell.app.sessions.SSH_SOURCE_SESSION_PICKER_LIST
import com.pocketshell.app.sessions.SSH_SOURCE_START_DIRECTORY_AUTOCOMPLETE
import com.pocketshell.app.sessions.SSH_SOURCE_TMUX_CONNECT
import com.pocketshell.app.sessions.SSH_SOURCE_WARM_HOST_CONNECT
import com.pocketshell.app.sessions.SshOpenTelemetry
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_MORE_BUTTON_TAG
import com.pocketshell.app.tmux.SSH_HANDSHAKE_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_WARM_SWITCH_CI_ADVISORY_P95_MS
import com.pocketshell.app.tmux.TMUX_WARM_SWITCH_LOCAL_P95_BUDGET_MS
import com.pocketshell.app.tmux.TMUX_SESSION_PAGER_PAGE_TAG_PREFIX
import com.pocketshell.app.tmux.TMUX_SESSION_PAGER_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TmuxSessionLatencyTelemetry
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import kotlin.math.ceil

/**
 * Issue #178 — verifies that switching tmux sessions on the SAME host
 * reuses the existing SSH transport instead of opening a fresh socket.
 *
 * The user-visible motivation: full SSH handshake on every attach was
 * 2-5s in real use, dominated by `kex_exchange_identification` + key
 * exchange. The transport is reusable, so a same-host session switch
 * should be near-instant.
 *
 * Structural assertion: [SSH_HANDSHAKE_ATTEMPTS] is the process-wide
 * counter the ViewModel increments each time it actually calls
 * [SshConnection.connect]. A same-host switch must not increment it.
 *
 * Timing assertion: end-to-end switch latency below 1s. The full
 * teardown path used to be 2-5s in real use; the new path skips both
 * the SSH handshake AND the previous SSH `disconnect()` thread hop,
 * so a sub-second ceiling is a comfortable local guard while the
 * structural no-new-SSH assertion remains the release gate.
 *
 * Companion to [TmuxSessionSwitchE2eTest] (#151) — that test pins the
 * crash-safety contract of the same UX gesture; this one pins the
 * "no new socket" optimisation.
 */
@RunWith(AndroidJUnit4::class)
class TmuxSessionSwitchSameHostReusesSshE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    /**
     * Budget for the tmux-session picker bottom sheet to transition from
     * `Loading` to `Ready`. The transition covers SSH connect, remote
     * `tmux list-sessions`, and the picker's recomposition into row
     * widgets bearing each session name.
     *
     * On a local Linux dev emulator the full Loading → Ready cycle lands
     * in well under 5s, so 20s is comfortable. On the GitHub Actions
     * swiftshader CI emulator running with a parallel Docker `agents`
     * container, the same cycle has been observed to exceed 20s under
     * load (see #207 sub-failure bisect: this test was added by #178 and
     * failed on its very first CI run with a clean 20s
     * `ComposeTimeoutException` at the picker `waitForText`). Scaling to
     * 60s on CI gives generous headroom while keeping the local budget
     * tight so a real regression on a dev box still surfaces.
     */
    private val pickerWaitMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching {
                cleanupSeededSessions(readFixtureKey())
            }
        }
    }

    @Test
    fun sameHostSessionSwitchReusesSshTransport() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        seedTmuxSessions(key)
        val hostRowTag = seedDockerHost(key, "Issue178 Same Host")
        forceFlatHostDetailViewMode()

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        SshOpenTelemetry.resetForTest()

        // ---- (1) Attach to SESSION_A.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForFolderListReady()
        compose.onNodeWithText(SESSION_A, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTerminalText(SESSION_A_MARKER)
        captureViewport("issue178-01-attached-session-a")

        // ---- (2) Snapshot the SSH handshake counter and tmux connect
        // counter right before the switch. We snapshot both because the
        // logical tmux connect must still advance (proving the switch
        // was processed, not silently no-op'd) while the SSH handshake
        // must NOT.
        val handshakeBefore = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectBefore = TMUX_CONNECT_ATTEMPTS.get()
        val sshOpenBefore = SshOpenTelemetry.snapshot()
        Log.i(LOG_TAG, "snapshot-before handshake=$handshakeBefore tmuxConnect=$tmuxConnectBefore")

        // ---- (3) Tap the More menu, open the switcher, and switch to a
        // DIFFERENT same-host session. We trigger the switch by paging the
        // switcher to the next session and letting it auto-attach on
        // settle (the production behaviour — the user swipes the session
        // pager and it attaches when it lands).
        //
        // We deliberately do NOT pin the assertion to one specific target
        // session name: the picker enumerates every live `tmux
        // list-sessions` entry on the SHARED Docker fixture, and sibling
        // tests can create/destroy sessions (e.g. `claude-main`)
        // concurrently with this run, so the settled page is not stable.
        // What this issue actually requires — and what we assert below —
        // is invariant for ANY same-host target: the SSH transport is
        // reused (no new handshake), the tmux logical connect advances,
        // and the stale previous-session frame is cleared. Landing on a
        // sibling's session is still a same-host fast-switch and exercises
        // the exact production path under test.
        openMoreMenu()
        compose.onNodeWithText("Switch session").performClick()
        val pickerOpenAt = SystemClock.elapsedRealtime()
        waitForSessionPagerReady(timeoutMs = pickerWaitMs)
        recordTiming("switcher_open_to_ready_ms", SystemClock.elapsedRealtime() - pickerOpenAt)
        // [switchAt] is stamped the instant the switch is DISPATCHED (the
        // pager settles and the tmux logical connect counter advances), so
        // the latency we measure is the same-host switch itself — not the
        // preceding picker navigation or the picker's own `tmux
        // list-sessions` SSH round-trip, which are user-driven setup, not
        // the switch we are timing.
        val switchAt = switchToNextSameHostSession(tmuxConnectBefore, timeoutMs = pickerWaitMs)
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()

        // ---- (3a) Issue #437 (slice A) supersedes the old issue #257
        // scope-F "clear the stale frame immediately" contract. A same-host
        // warm switch reuses the live SSH transport, so the ViewModel enters
        // [ConnectionStatus.Switching] (not [Connecting]) and DELIBERATELY
        // KEEPS the previous session's frame painted while the warm `-CC`
        // client attaches — the viewport must never blank to an empty
        // full-screen "Connecting" overlay on a same-host switch. The leaving
        // session's content is replaced ATOMICALLY when the new session's
        // panes reconcile (or, on the cached pointer-swap path, when the
        // cached runtime activates): there is no intermediate blank where the
        // old marker is gone but the new one has not yet appeared. We
        // therefore assert the keep-frame invariant by transition rather than
        // by early clearing — wait until the new session's content has
        // swapped in, then confirm the stale marker is no longer visible.
        // Reaching SESSION_B_MARKER proves the swap happened; the absence of
        // SESSION_A_MARKER after it proves the swap fully replaced the
        // leaving frame (no stale residue). The "previous frame never blanks
        // to empty mid-switch" property is what keep-frame guarantees: had
        // the viewport blanked, the user would have seen neither marker.
        waitForTerminalText(SESSION_B_MARKER)
        val newFrameVisibleMs = SystemClock.elapsedRealtime() - switchAt
        recordTiming("new_frame_visible_ms", newFrameVisibleMs)
        captureViewport("issue437-01-new-frame-swapped-in")
        assertFalse(
            "after the atomic swap to the new session, stale previous-session " +
                "content ('$SESSION_A_MARKER') must no longer be visible — the warm " +
                "switch replaces the kept frame with the new session's frame in one " +
                "atomic step (issue #437 slice A keep-frame contract, superseding the " +
                "old #257 scope-F synchronous-clear behaviour)",
            visibleTerminalText().contains(SESSION_A_MARKER),
        )

        waitForTerminalViewAttached()
        waitForTmuxConnectCountAbove(tmuxConnectBefore)
        waitForTerminalText(SESSION_B_MARKER)
        val switchMs = SystemClock.elapsedRealtime() - switchAt
        recordTiming("same_host_switch_ms", switchMs)
        captureViewport("issue437-02-switched-to-other-session")

        // ---- (4) Verify the structural invariants.
        val handshakeAfter = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectAfter = TMUX_CONNECT_ATTEMPTS.get()
        val sshOpenDeltas = sshOpenDeltasSince(sshOpenBefore)
        Log.i(
            LOG_TAG,
            "snapshot-after handshake=$handshakeAfter tmuxConnect=$tmuxConnectAfter " +
                "switchMs=$switchMs sshOpenDeltas=$sshOpenDeltas",
        )
        recordTiming("ssh_handshakes_during_switch", (handshakeAfter - handshakeBefore).toLong())
        recordTiming("tmux_connects_during_switch", (tmuxConnectAfter - tmuxConnectBefore).toLong())
        SSH_SWITCH_SOURCES.forEach { source ->
            recordTiming("ssh_opens_${source}_during_switch", (sshOpenDeltas[source] ?: 0).toLong())
        }

        assertEquals(
            "same-host switch must NOT increment the SSH handshake counter " +
                "(handshakeBefore=$handshakeBefore handshakeAfter=$handshakeAfter); " +
                "the SSH transport must be reused for the new TmuxClient",
            handshakeBefore,
            handshakeAfter,
        )
        assertTrue(
            "tmux logical connect counter must advance on a session switch " +
                "(tmuxConnectBefore=$tmuxConnectBefore tmuxConnectAfter=$tmuxConnectAfter)",
            tmuxConnectAfter > tmuxConnectBefore,
        )
        assertEquals(
            "same-host switch must not open fresh SSH from any session-switch source; " +
                "deltas=$sshOpenDeltas",
            emptyMap<String, Int>(),
            sshOpenDeltas.filterValues { it != 0 },
        )

        // Acceptance criterion: the structural no-new-SSH assertion above is
        // the release gate. Keep a timing guard too, but give the CI emulator
        // headroom for Compose pager animation and terminal repaint load.
        val switchBudgetMs = if (TerminalTestTimeouts.isRunningOnCi()) {
            CI_SWITCH_BUDGET_MS
        } else {
            LOCAL_SWITCH_BUDGET_MS
        }
        assertTrue(
            "same-host session switch must complete in under ${switchBudgetMs}ms, " +
                "took ${switchMs}ms",
            switchMs < switchBudgetMs,
        )

        writeTimings()
        Unit
    }

    @Test
    fun dashboardSameHostSessionTapReusesSshTransport() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        seedTmuxSessions(key)
        val hostRowTag = seedDockerHost(key, "Issue278 Dashboard Same Host")
        forceFlatHostDetailViewMode()

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForFolderListReady()
        compose.onNodeWithText(SESSION_A, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTerminalText(SESSION_A_MARKER)
        captureViewport("issue278-01-dashboard-attached-session-a")

        val handshakeBefore = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectBefore = TMUX_CONNECT_ATTEMPTS.get()
        SshOpenTelemetry.resetForTest()
        val switchWindowStart = SystemClock.elapsedRealtime()

        clickTmuxBack()
        waitForFolderListReady()
        recordTiming("folder_back_to_rows_ready_ms", SystemClock.elapsedRealtime() - switchWindowStart)
        val rowTapAt = SystemClock.elapsedRealtime()
        compose.onNodeWithText(SESSION_B, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTmuxConnectCountAbove(tmuxConnectBefore)
        waitForTerminalText(SESSION_B_MARKER)
        val rowTapToReadyMs = SystemClock.elapsedRealtime() - rowTapAt
        recordTiming("folder_row_tap_to_terminal_ready_ms", rowTapToReadyMs)
        captureViewport("issue278-02-dashboard-switched-to-session-b")

        val handshakeAfter = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectAfter = TMUX_CONNECT_ATTEMPTS.get()
        val sshOpenDeltas = sshOpenDeltasSince(emptyMap())
        recordTiming("dashboard_ssh_handshakes_during_switch", (handshakeAfter - handshakeBefore).toLong())
        recordTiming("dashboard_tmux_connects_during_switch", (tmuxConnectAfter - tmuxConnectBefore).toLong())
        SSH_SWITCH_SOURCES.forEach { source ->
            recordTiming("dashboard_ssh_opens_${source}_during_switch", (sshOpenDeltas[source] ?: 0).toLong())
        }

        assertEquals(
            "dashboard same-host switch must not perform a ViewModel SSH handshake",
            handshakeBefore,
            handshakeAfter,
        )
        assertTrue(
            "dashboard same-host switch must still advance tmux logical connect count",
            tmuxConnectAfter > tmuxConnectBefore,
        )
        assertEquals(
            "dashboard same-host switch must not open fresh SSH from picker/folder/tmux sources; " +
                "deltas=$sshOpenDeltas",
            emptyMap<String, Int>(),
            sshOpenDeltas.filterValues { it != 0 },
        )
        val budgetMs = if (TerminalTestTimeouts.isRunningOnCi()) {
            CI_SWITCH_BUDGET_MS
        } else {
            LOCAL_DASHBOARD_SWITCH_BUDGET_MS
        }
        assertTrue(
            "dashboard same-host row tap should be under ${budgetMs}ms; took ${rowTapToReadyMs}ms",
            rowTapToReadyMs < budgetMs,
        )

        writeTimings()
        Unit
    }

    @Test
    fun sameHostWarmSwitchBenchmarkReportsRepeatedStats() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        seedTmuxSessions(key)
        val hostRowTag = seedDockerHost(key, "Issue337 Warm Switch Budget")
        forceFlatHostDetailViewMode()

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        SshOpenTelemetry.resetForTest()
        TmuxSessionLatencyTelemetry.resetForTest()

        try {
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
            waitForFolderListReady()
            compose.onNodeWithText(SESSION_A, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
            waitForTerminalViewAttached()
            waitForTerminalText(SESSION_A_MARKER)

            prewarmRuntimeCacheForBenchmark()
            SshOpenTelemetry.resetForTest()
            TmuxSessionLatencyTelemetry.resetForTest()

            val firstNewFrameSamples = mutableListOf<Long>()
            var currentMarker = SESSION_A_MARKER
            repeat(WARM_SWITCH_BENCHMARK_ITERATIONS) { iteration ->
                val expectedMarker = if (currentMarker == SESSION_A_MARKER) {
                    SESSION_B_MARKER
                } else {
                    SESSION_A_MARKER
                }
                val sample = performWarmSwitchBenchmarkSample(
                    iteration = iteration + 1,
                    staleMarker = currentMarker,
                    expectedMarker = expectedMarker,
                )
                firstNewFrameSamples += sample.firstNewFrameMs
                recordTiming("warm_switch_${iteration + 1}_first_new_frame_ms", sample.firstNewFrameMs)
                currentMarker = expectedMarker
            }

            val firstNewStats = recordTimingStats(
                prefix = "warm_switch_first_new_frame",
                samples = firstNewFrameSamples,
            )
            val cacheActivationStats = recordTimingStats(
                prefix = "warm_switch_runtime_cache_activate",
                samples = TmuxSessionLatencyTelemetry.snapshot()
                    .filter { it.name == "runtime_cache_activate" }
                    .map { it.durationMs },
            )
            recordTiming("warm_switch_local_p95_budget_ms", TMUX_WARM_SWITCH_LOCAL_P95_BUDGET_MS)
            recordTiming("warm_switch_ci_advisory_p95_ms", TMUX_WARM_SWITCH_CI_ADVISORY_P95_MS)
            recordTiming("warm_switch_iterations", WARM_SWITCH_BENCHMARK_ITERATIONS.toLong())
            TmuxSessionLatencyTelemetry.snapshot().forEach { event ->
                timings += event.toArtifactLine(prefix = "warm_switch")
            }

            assertTrue(
                "cached pointer-swap activation p95 must stay under " +
                    "${TMUX_WARM_SWITCH_LOCAL_P95_BUDGET_MS}ms; " +
                    "stats=$cacheActivationStats",
                cacheActivationStats.p95Ms < TMUX_WARM_SWITCH_LOCAL_P95_BUDGET_MS,
            )

            // Local physical-device runs are the hard visible-frame p95 gate.
            // Emulator and CI runs publish the same visible p50/p95/max lines
            // plus the 100 ms target, but stay advisory because the emulator's
            // visible-terminal polling path has proven too noisy to validate
            // the actual cached pointer-swap display latency.
            val budgetMs = if (TerminalTestTimeouts.isRunningOnCi() || isRunningOnEmulator()) {
                TMUX_WARM_SWITCH_CI_ADVISORY_P95_MS
            } else {
                TMUX_WARM_SWITCH_LOCAL_P95_BUDGET_MS
            }
            assertTrue(
                "warm same-host switch p95 should stay under ${budgetMs}ms; " +
                    "stats=$firstNewStats samples=$firstNewFrameSamples",
                firstNewStats.p95Ms < budgetMs,
            )
        } finally {
            writeTimings()
        }
        Unit
    }

    // ---------------------------------------------------------------- Helpers

    private fun prewarmRuntimeCacheForBenchmark() {
        openMoreMenu()
        compose.onNodeWithText("Switch session").performClick()
        waitForSessionPagerReady(timeoutMs = pickerWaitMs)
        val toBConnectBefore = TMUX_CONNECT_ATTEMPTS.get()
        switchToNextSameHostSession(toBConnectBefore, timeoutMs = pickerWaitMs)
        waitForTerminalText(SESSION_B_MARKER)

        openMoreMenu()
        compose.onNodeWithText("Switch session").performClick()
        waitForSessionPagerReady(timeoutMs = pickerWaitMs)
        val toAConnectBefore = TMUX_CONNECT_ATTEMPTS.get()
        switchToNextSameHostSession(toAConnectBefore, timeoutMs = pickerWaitMs)
        waitForTerminalText(SESSION_A_MARKER)
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
                name = "issue178-key-${System.currentTimeMillis()}",
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

    private suspend fun seedTmuxSessions(key: String) {
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_A)} 2>/dev/null || true")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_B)} 2>/dev/null || true")
            // Clean-slate the deterministic Docker test fixture: kill any
            // other sessions a sibling test may have left behind (e.g.
            // `claude-main`). The session picker enumerates every live
            // `tmux list-sessions` entry, and the switcher pager auto-
            // attaches whenever it SETTLES on a selectable page — so a
            // stray session sitting between the current one and SESSION_B
            // would be auto-selected mid-scroll and the switch would never
            // reach SESSION_B. Removing strays keeps the two seeded
            // sessions the only switch targets, making the switch
            // deterministic. This only touches the test fixture on
            // 10.0.2.2:2222, never a real host.
            appendLine(
                "tmux list-sessions -F '#{session_name}' 2>/dev/null | " +
                    "grep -vx ${shellQuote(SESSION_A)} | grep -vx ${shellQuote(SESSION_B)} | " +
                    "while IFS= read -r s; do tmux kill-session -t \"\$s\" 2>/dev/null || true; done",
            )
            appendLine("mkdir -p \"\$HOME/.local/bin\"")
            appendLine("if [ -f \"\$HOME/.local/bin/tmuxctl\" ] && [ ! -f \"\$HOME/.local/bin/tmuxctl.issue178-backup\" ]; then")
            appendLine("  mv \"\$HOME/.local/bin/tmuxctl\" \"\$HOME/.local/bin/tmuxctl.issue178-backup\"")
            appendLine("fi")
            appendLine("cat > \"\$HOME/.local/bin/tmuxctl\" <<'ISSUE178_TMUXCTL'")
            appendLine("#!/bin/sh")
            appendLine("if [ \"\${1:-}\" = \"list\" ]; then")
            appendLine("  printf 'IDX  SESSION               CREATED\\n'")
            appendLine("  printf '1    $SESSION_A           2026-05-28 11:00:00\\n'")
            appendLine("  printf '2    $SESSION_B           2026-05-28 11:00:01\\n'")
            appendLine("  printf '\\nJoin a session: tmuxctl <id> or tmuxctl <session>\\n'")
            appendLine("  printf 'Create a new one: tmuxctl :<session>\\n'")
            appendLine("  printf 'Use current folder: tmuxctl - or tmuxctl -name\\n'")
            appendLine("  printf 'Help: tmuxctl --help\\n'")
            appendLine("  exit 0")
            appendLine("fi")
            appendLine("exec tmux \"\$@\"")
            appendLine("ISSUE178_TMUXCTL")
            appendLine("chmod +x \"\$HOME/.local/bin/tmuxctl\"")
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_A)} " +
                    shellQuote("printf '$SESSION_A_MARKER\\n'; exec sh"),
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_B)} " +
                    shellQuote("printf '$SESSION_B_MARKER\\n'; exec sh"),
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
            "expected tmux session seeding to succeed for #178, got " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded sessions: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupSeededSessions(key: String) {
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
                            "tmux kill-session -t ${shellQuote(SESSION_A)} 2>/dev/null || true; " +
                                "tmux kill-session -t ${shellQuote(SESSION_B)} 2>/dev/null || true; " +
                                "if [ -f \"\$HOME/.local/bin/tmuxctl.issue178-backup\" ]; then " +
                                "mv \"\$HOME/.local/bin/tmuxctl.issue178-backup\" \"\$HOME/.local/bin/tmuxctl\"; " +
                                "else rm -f \"\$HOME/.local/bin/tmuxctl\"; fi",
                        )
                    }
                }
            }
        }
    }

    private fun waitForText(text: String, timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForSessionPagerReady(timeoutMs: Long) {
        // The picker has materialised once the first pager page is
        // composed. We wait on the page-1 tag (always present once the
        // picker leaves Loading) rather than a specific session name,
        // because the named target may sit on an off-screen page that
        // [selectSessionByName] still has to swipe to.
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithTag(
                "${TMUX_SESSION_PAGER_PAGE_TAG_PREFIX}1",
                useUnmergedTree = true,
            )
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
    }

    private fun waitForFolderListReady() {
        // Issue #470 blocker #2: wait on the shared session-picker readiness
        // gate, which surfaces the folder-list session row (here SESSION_B,
        // the last seeded session, so the whole enumeration has landed) with
        // a generous bound and a single production Retry if the cold-AVD
        // `tmux list-sessions` SSH-exec probe lands on the connect-error
        // panel — replacing the bare `waitUntil` that burned the full
        // timeout when the first connect attempt blew its window. The
        // folder-list screen is implicitly up once the row is present.
        waitForSessionInPicker(
            rule = compose,
            sessionName = SESSION_B,
            timeoutMs = pickerWaitMs,
        )
    }

    /**
     * Scroll the session pager to the page bearing [name] and click it.
     * The pager only mounts a few pages around the current one, so we
     * swipe left until the named page's clickable container is composed,
     * then dispatch its OnClick — the same action a user tap fires. By
     * selecting on the session NAME we stay deterministic no matter how
     * many other sessions are live on the shared Docker fixture.
     */
    /**
     * Page the session switcher to the NEXT session and let it auto-attach
     * on settle (the production gesture). The switcher attaches whenever
     * its pager settles on a selectable, non-current page, so scrolling to
     * page index 1 lands on a different same-host session and fires the
     * switch. We confirm the switch by watching the tmux logical connect
     * counter advance past [tmuxConnectBefore].
     *
     * Returns the [SystemClock.elapsedRealtime] timestamp at which the
     * switch was observed to fire, so the caller can time the switch
     * itself rather than the preceding picker navigation.
     */
    private fun switchToNextSameHostSession(tmuxConnectBefore: Int, timeoutMs: Long): Long {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var index = 1
        var dispatchAt: Long? = null
        while (SystemClock.elapsedRealtime() < deadline &&
            TMUX_CONNECT_ATTEMPTS.get() == tmuxConnectBefore
        ) {
            if (compose.onAllNodesWithTag(TMUX_SESSION_PAGER_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes().isNotEmpty()
            ) {
                runCatching {
                    if (dispatchAt == null) {
                        dispatchAt = SystemClock.elapsedRealtime()
                    }
                    compose.onNodeWithTag(TMUX_SESSION_PAGER_TAG, useUnmergedTree = true)
                        .performScrollToIndex(index)
                }
                index = (index % MAX_PAGER_PAGES) + 1
            }
            compose.waitForIdle()
            SystemClock.sleep(150)
        }
        check(TMUX_CONNECT_ATTEMPTS.get() > tmuxConnectBefore) {
            "session switch never fired in the pager within ${timeoutMs}ms"
        }
        return dispatchAt ?: SystemClock.elapsedRealtime()
    }

    private fun openMoreMenu() {
        val tags = listOf(
            TMUX_COMPACT_CHROME_MORE_BUTTON_TAG,
            TMUX_FULL_CHROME_MORE_BUTTON_TAG,
        ).filter { tag ->
            compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        tags.forEach { tag ->
            compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
            val opened = runCatching {
                compose.waitUntil(timeoutMillis = 1_000) {
                    compose.onAllNodesWithText("Switch session", useUnmergedTree = true)
                        .fetchSemanticsNodes()
                        .isNotEmpty()
                }
            }.isSuccess
            if (opened) return
        }
        compose.onNodeWithTag(TMUX_FULL_CHROME_MORE_BUTTON_TAG, useUnmergedTree = true)
            .performClick()
    }

    private fun clickTmuxBack() {
        val tags = listOf(
            TMUX_COMPACT_CHROME_BACK_BUTTON_TAG,
            TMUX_FULL_CHROME_BACK_BUTTON_TAG,
        )
        for (tag in tags) {
            if (compose.onAllNodesWithTag(tag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                compose.onNodeWithTag(tag, useUnmergedTree = true).performClick()
                return
            }
        }
        compose.onNodeWithTag(TMUX_FULL_CHROME_BACK_BUTTON_TAG, useUnmergedTree = true)
            .performClick()
    }

    private fun sshOpenDeltasSince(before: Map<String, Int>): Map<String, Int> =
        SSH_SWITCH_SOURCES.associateWith { source ->
            SshOpenTelemetry.count(source) - (before[source] ?: 0)
        }

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun waitForTerminalText(expected: String) {
        compose.waitUntil(timeoutMillis = 30_000) {
            visibleTerminalText().contains(expected)
        }
    }

    private fun visibleTerminalText(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        return text
    }

    private fun waitForTmuxConnectCountAbove(previous: Int) {
        compose.waitUntil(timeoutMillis = 30_000) {
            TMUX_CONNECT_ATTEMPTS.get() > previous
        }
    }

    private data class SwitchSample(
        val firstNewFrameMs: Long,
    )

    private data class TimingStats(
        val p50Ms: Long,
        val p95Ms: Long,
        val maxMs: Long,
    )

    private fun performWarmSwitchBenchmarkSample(
        iteration: Int,
        staleMarker: String,
        expectedMarker: String,
    ): SwitchSample {
        val handshakeBefore = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectBefore = TMUX_CONNECT_ATTEMPTS.get()
        val sshOpenBefore = SshOpenTelemetry.snapshot()
        val telemetryBefore = TmuxSessionLatencyTelemetry.snapshot()

        openMoreMenu()
        compose.onNodeWithText("Switch session").performClick()
        waitForSessionPagerReady(timeoutMs = pickerWaitMs)
        val switchAt = switchToNextSameHostSession(tmuxConnectBefore, timeoutMs = pickerWaitMs)
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()

        // Issue #437 (slice A) keep-frame contract: a warm same-host switch
        // does NOT clear the leaving session's frame early. Under the warm
        // cache pointer-swap path the leaving frame ([staleMarker]) stays
        // painted and is replaced ATOMICALLY by the new session's frame
        // ([expectedMarker]) when the cached runtime activates — there is no
        // intermediate blank where the old marker is gone but the new one has
        // not yet arrived. So we measure the single visible-frame latency
        // (tap → new frame painted) rather than a now-meaningless "stale
        // frame cleared" interval, then assert the swap fully replaced the
        // leaving frame (no [staleMarker] residue).
        waitForTerminalViewAttached()
        waitForTmuxConnectCountAbove(tmuxConnectBefore)
        waitForTerminalText(expectedMarker)
        val firstNewFrameMs = SystemClock.elapsedRealtime() - switchAt
        assertFalse(
            "warm same-host switch must atomically replace the leaving frame: " +
                "after the new session ('$expectedMarker') is painted, stale content " +
                "('$staleMarker') must no longer be visible (issue #437 slice A " +
                "keep-frame contract — the kept frame is swapped in one atomic step, " +
                "not blanked then refilled)",
            visibleTerminalText().contains(staleMarker),
        )

        val handshakeAfter = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectAfter = TMUX_CONNECT_ATTEMPTS.get()
        val sshOpenDeltas = sshOpenDeltasSince(sshOpenBefore)
        val telemetryAfter = TmuxSessionLatencyTelemetry.snapshot()
        val switchTelemetry = telemetryAfter.drop(telemetryBefore.size)
        // Issue #437 (slice A): the cached pointer-swap path shows the first
        // frame from the warm runtime cache (cacheHit=true) WITHOUT a fresh
        // tmux control-client attach. The forbidden work is therefore a fresh
        // [tmux_control_attach_count] — that would mean we re-attached a `-CC`
        // control client instead of reusing the cached runtime, defeating the
        // whole optimisation. It is NOT [list_panes]/[capture_pane]: under the
        // keep-frame model [launchCachedRuntimeRemoteRefresh] deliberately
        // reconciles the real tmux pane state in the BACKGROUND a short delay
        // AFTER the cached frame is already on screen (the
        // [warm_switch_remote_refresh_complete] milestone), and that reconcile
        // legitimately runs `list-panes`. The benchmark's telemetry window
        // extends through [waitForTerminalText], so it necessarily observes
        // that background refresh. Asserting zero `list-panes` here is the old
        // pre-#437 pure-pointer-swap contract and is now contradicted by the
        // remote-refresh design, so we only forbid a fresh control attach and
        // keep the `list-panes`/`capture-pane` counts as observational metrics.
        val forbiddenTmuxWork = switchTelemetry.filter {
            it.name == "tmux_control_attach_count"
        }
        val cachedRefreshListPanes = switchTelemetry.count { it.name == "list_panes" }
        val cachedRefreshCapturePanes = switchTelemetry.count { it.name == "capture_pane" }
        val cacheActivations = switchTelemetry.filter { it.name == "runtime_cache_activate" }
        recordTiming("warm_switch_${iteration}_ssh_handshakes_during_switch", (handshakeAfter - handshakeBefore).toLong())
        recordTiming("warm_switch_${iteration}_logical_tmux_connects_during_switch", (tmuxConnectAfter - tmuxConnectBefore).toLong())
        recordTiming("warm_switch_${iteration}_runtime_cache_activations", cacheActivations.size.toLong())
        recordTiming("warm_switch_${iteration}_tmux_control_attaches_during_cached_switch", forbiddenTmuxWork.size.toLong())
        // Observational only under #437: these are the background
        // [launchCachedRuntimeRemoteRefresh] reconcile calls that run AFTER the
        // cached frame is shown, not forbidden synchronous work.
        recordTiming("warm_switch_${iteration}_tmux_list_panes_during_cached_refresh", cachedRefreshListPanes.toLong())
        recordTiming("warm_switch_${iteration}_tmux_capture_pane_during_cached_refresh", cachedRefreshCapturePanes.toLong())
        SSH_SWITCH_SOURCES.forEach { source ->
            recordTiming("warm_switch_${iteration}_ssh_opens_${source}_during_switch", (sshOpenDeltas[source] ?: 0).toLong())
        }
        assertEquals(
            "warm same-host switch must not perform a fresh SSH handshake",
            handshakeBefore,
            handshakeAfter,
        )
        assertTrue(
            "warm same-host switch gesture must be accepted by connect(); " +
                "this logical counter is not used as tmux-control attach proof",
            tmuxConnectAfter > tmuxConnectBefore,
        )
        assertTrue(
            "cached same-host switch must activate exactly one cached runtime; " +
                "events=$switchTelemetry",
            cacheActivations.size == 1,
        )
        assertTrue(
            "cached same-host switch must not attach a fresh tmux control " +
                "client — the cached runtime is reused via a pointer swap, not a " +
                "new `-CC` attach (issue #437 slice A); a background list-panes / " +
                "capture-pane reconcile after the cached frame is shown is " +
                "expected and not forbidden. forbidden=$forbiddenTmuxWork " +
                "events=$switchTelemetry",
            forbiddenTmuxWork.isEmpty(),
        )
        assertEquals(
            "warm same-host switch must not open fresh SSH from switch sources; deltas=$sshOpenDeltas",
            emptyMap<String, Int>(),
            sshOpenDeltas.filterValues { it != 0 },
        )
        return SwitchSample(firstNewFrameMs)
    }

    private fun recordTimingStats(prefix: String, samples: List<Long>): TimingStats {
        val sorted = samples.sorted()
        check(sorted.isNotEmpty()) { "cannot record stats for an empty sample set" }
        val stats = TimingStats(
            p50Ms = percentile(sorted, 0.50),
            p95Ms = percentile(sorted, 0.95),
            maxMs = sorted.last(),
        )
        recordTiming("${prefix}_p50_ms", stats.p50Ms)
        recordTiming("${prefix}_p95_ms", stats.p95Ms)
        recordTiming("${prefix}_max_ms", stats.maxMs)
        return stats
    }

    private fun percentile(sortedSamples: List<Long>, percentile: Double): Long {
        val index = (ceil(sortedSamples.size * percentile).toInt() - 1)
            .coerceIn(0, sortedSamples.lastIndex)
        return sortedSamples[index]
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { writeBitmap("$name-viewport", it) }
        var text = ""
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.transcriptText
                .orEmpty()
        }
        writeText("$name-visible-terminal.txt", text)
        bitmap?.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE178_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE178_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE178_TIMINGS ${file.absolutePath}")
        return file
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
        println("ISSUE178_TIMING $line")
    }

    private fun forceFlatHostDetailViewMode() {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        appContext
            .getSharedPreferences("app_settings", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("host_detail_view_mode", "Flat")
            .commit()
    }

    private fun isRunningOnEmulator(): Boolean =
        Build.FINGERPRINT.contains("generic", ignoreCase = true) ||
            Build.FINGERPRINT.contains("emulator", ignoreCase = true) ||
            Build.MODEL.contains("sdk", ignoreCase = true) ||
            Build.MODEL.contains("emulator", ignoreCase = true) ||
            Build.HARDWARE.contains("goldfish", ignoreCase = true) ||
            Build.HARDWARE.contains("ranchu", ignoreCase = true)

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (index in 0 until childCount) {
            val match = getChildAt(index).findTerminalView()
            if (match != null) return match
        }
        return null
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\"'\"'") + "'"

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue178SameHost"
        const val DEVICE_DIR_NAME: String = "issue178-same-host-switch"
        const val SESSION_A: String = "issue178-session-a"
        const val SESSION_B: String = "issue178-session-b"
        // Per-session shell markers printed on attach. Used to tell the
        // two sessions' terminal content apart — including for the issue
        // #437 slice-A keep-frame check, which proves the leaving session's
        // marker is atomically replaced by the arriving session's marker
        // during a same-host switch (the previous frame stays painted until
        // the swap, never blanking, and leaves no stale residue afterwards).
        const val SESSION_A_MARKER: String = "A-READY"
        const val SESSION_B_MARKER: String = "B-READY"
        const val LOCAL_SWITCH_BUDGET_MS: Long = 1_000L
        const val LOCAL_DASHBOARD_SWITCH_BUDGET_MS: Long = 1_000L
        const val CI_SWITCH_BUDGET_MS: Long = 5_000L
        const val WARM_SWITCH_BENCHMARK_ITERATIONS: Int = 5
        // Upper bound on session-pager page tags we probe when matching a
        // session by name (the pager is index-tagged from 1).
        const val MAX_PAGER_PAGES: Int = 12
        val SSH_SWITCH_SOURCES: List<String> = listOf(
            SSH_SOURCE_TMUX_CONNECT,
            SSH_SOURCE_WARM_HOST_CONNECT,
            SSH_SOURCE_SESSION_PICKER_LIST,
            SSH_SOURCE_FOLDER_LIST_PROBE,
            SSH_SOURCE_START_DIRECTORY_AUTOCOMPLETE,
        )
    }
}
