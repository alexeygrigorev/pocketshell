package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.lifecycle.Lifecycle
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.projects.FOLDER_LIST_BACK_TAG
import com.pocketshell.app.projects.FOLDER_LIST_PULL_TO_REFRESH_TAG
import com.pocketshell.app.projects.SshFolderListGateway
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.tmux.SSH_HANDSHAKE_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.ProjectRootEntity
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

/**
 * Issue #758 / epic #636 — the maintainer's priority-#1 reconnect: open session A
 * → tap BACK to the picker → open a DIFFERENT session B → a FULL reconnect
 * (Connecting overlay + fresh SSH handshake) instead of reusing the warm lease.
 *
 * Root cause (research comment 4699435569 on #636): a lease-lifetime collision on
 * the shared SSH transport. The activity-scoped `TmuxSessionViewModel` survives
 * back-navigation and keeps A's `-CC` channel + SSH lease LIVE. But while the user
 * sits on the FolderList picker, `FolderListGateway` polls session/folder
 * discovery over the EXACT SAME `SshLeaseKey` A rides on. On a transient
 * stale-channel/EOF symptom during that poll, the gateway used to call
 * `sshLeaseManager.disconnect(leaseKey)` — which FORCE-CLOSED the shared transport
 * out from under A. Opening B then found no live lease → COLD path → the
 * Connecting overlay + fresh handshake the maintainer sees.
 *
 * The fix (#758): the gateway's poison-eviction is now refcount-aware
 * (`evictIdle`, a no-op while the session VM still holds the lease) instead of an
 * unconditional `disconnect`. A transport an active session holds is healed by the
 * VM's own stale-lease path on its next attach, never torn down by the picker.
 *
 * ## Why this is a regular-CI per-PR gate (no toxiproxy)
 *
 * It uses ONLY the deterministic Docker `agents` fixture on host port 2222 that
 * the emulator-journey workflow already brings up — NOT the opt-in toxiproxy
 * network-fault proxy family. The poll-time stale-channel symptom is induced
 * DETERMINISTICALLY via the inert test hook
 * [SshFolderListGateway.forcedStaleChannelSymptoms] (default 0 in production), so
 * the bug reproduces on a healthy link without relying on poll-timing luck (the
 * research's flake risk #2). This test is NOT gated out of CI (no
 * `assumeFalse(isRunningOnCi())`), so the back→open-B reconnect cannot silently
 * regress.
 *
 * ## Reproduction mechanics
 *
 *  1. Seed TWO live tmux sessions (A, B) on the fixture, plus ONE watched project
 *     root for the host so the picker's discovery poll ALWAYS runs the SSH-lease
 *     path (the watched-root expansion) — that is the lease the eviction would
 *     poison. (Without a watched root the picker can serve enumeration purely off
 *     the live `-CC` client and never touch the lease path.)
 *  2. Tap host → picker → open A; wait for A's marker in the terminal viewport.
 *  3. Snapshot [SSH_HANDSHAKE_ATTEMPTS].
 *  4. Press BACK to the picker, then ARM exactly ONE forced stale-channel symptom
 *     and dwell until ≥1 picker discovery poll runs over the shared lease (the
 *     poll that would, pre-fix, `disconnect` the lease A holds).
 *  5. Open B from the picker.
 *  6. Assert the load-bearing, user-visible invariants:
 *       - [SSH_HANDSHAKE_ATTEMPTS] delta == 0 across the back→open-B (warm reuse —
 *         a fresh handshake here IS the bug).
 *       - The full-screen [TMUX_CONNECTING_PROGRESS_TAG] Connecting overlay never
 *         appears for this open (assert on the VISIBLE surface — D28 rule 3).
 *       - No [TMUX_SESSION_ERROR_TAG] Disconnected band / manual Reconnect.
 *       - B's marker appears (the session is live and usable).
 *
 * MUST FAIL on `main` before the #758 fix (the gateway `disconnect` poisons the
 * shared lease → fresh handshake + Connecting overlay) and PASS after it.
 */
@RunWith(AndroidJUnit4::class)
class BackThenOpenSecondSessionReusesWarmLeaseE2eTest {

    // Issue #788: createAndroidComposeRule<MainActivity>() so the Compose test
    // clock drives the SAME foreground activity the Termux TerminalView interop
    // child is placed into — fixing the #470 swiftshader interop-placement /
    // enumeration stall. The rule launches MainActivity in its `before()`, so the
    // two live tmux sessions + watched-root DB host row are seeded BEFORE launch
    // by the chain.
    val compose = createAndroidComposeRule<MainActivity>()

    // Issue #470 blocker #1 (grant) + #788 seed-before-launch ordering:
    //   grant perms -> seed two remote sessions + watched-root DB host -> launch.
    @get:Rule
    val ruleChain: org.junit.rules.RuleChain = org.junit.rules.RuleChain
        .outerRule(PreGrantPermissionsRule())
        .around(SeedBeforeLaunchRule { seedBeforeLaunch() })
        .around(compose)

    private lateinit var fixtureKey: String
    private lateinit var hostRowTag: String
    private val timings = mutableListOf<String>()

    private val pickerWaitMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L

    @After
    fun tearDown() {
        // Issue #788: restore RESUMED before the rule's auto-close so close()
        // does not crash if the body left the scenario in a non-RESUMED state.
        runCatching { compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED) }
        // Always disarm the test hook so a failure mid-test cannot leak a forced
        // symptom into a sibling test sharing the process.
        SshFolderListGateway.forcedStaleChannelSymptoms.set(0)
        runBlocking {
            if (::fixtureKey.isInitialized) {
                runCatching { cleanupSeededSessions(fixtureKey) }
            }
        }
    }

    /**
     * Issue #788: seed two live remote tmux sessions (A, B) + a watched-root DB
     * host row BEFORE MainActivity launches (run by [SeedBeforeLaunchRule]). Also
     * disarms the forced stale-channel hook so the seed phase starts clean.
     */
    private suspend fun seedBeforeLaunch() {
        SshFolderListGateway.forcedStaleChannelSymptoms.set(0)
        val key = readFixtureKey()
        fixtureKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSessions(key)
        // Seed a watched root so the picker discovery poll ALWAYS runs the
        // SSH-lease path (the watched-root expansion), which is the lease the
        // eviction would poison. The path need not exist on the remote — the
        // expansion best-effort-degrades — it only needs to make
        // `watchedRoots.isEmpty()` false so the lease path runs every poll.
        hostRowTag = seedDockerHostWithWatchedRoot(key, "Issue758 Back Open")
        forceFlatHostDetailViewMode()
    }

    @Test
    fun backThenOpenSecondSessionReusesWarmLeaseNoReconnect() { runBlocking {
        // Issue #788: sessions + watched-root host seeded BEFORE launch by the
        // rule chain's `before()`; the forced-symptom hook was disarmed there too.

        // ---- (0) Open the FIRST session A from the host-detail picker.
        waitForHostRowPresent(hostRowTag)
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        // Pass the #740 re-poke recovery so a cold-AVD first-open enumeration
        // stall (#470 infra) self-heals (Back to the host list + re-tap) instead
        // of burning the whole bound and double-flaking the class-level retry.
        waitForSessionInPicker(
            rule = compose,
            sessionName = SESSION_A,
            timeoutMs = pickerWaitMs,
            onRepoke = { repokeFolderListFromHostRow(hostRowTag) },
        )
        compose.onNodeWithText(SESSION_A, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTerminalContains(SESSION_A_MARKER, "initial open of A")
        captureViewport("issue758-00-opened-$SESSION_A")

        // ---- (1) Snapshot the handshake counter right before backing out. The
        // open-A handshake (the only legitimate dial) is now behind us; from here
        // on, opening B must add ZERO fresh handshakes (warm-lease reuse).
        val handshakesBeforeBack = SSH_HANDSHAKE_ATTEMPTS.get()

        // ---- (2) BACK to the picker. A's transport + lease stay LIVE (the VM is
        // activity-scoped; Back runs no teardown).
        clickTmuxBack()
        waitForSessionInPicker(rule = compose, sessionName = SESSION_B, timeoutMs = pickerWaitMs)

        // ---- (3) ARM exactly one forced poll-time stale-channel symptom, then
        // trigger a picker discovery reconcile over the shared lease via the
        // production "Refresh Sessions" overflow action (EPIC #679's explicit
        // refresh; the held tree is otherwise NOT re-polled on a same-host
        // re-bind). Pre-fix this poll `disconnect`s the lease A holds → opening B
        // is then COLD (Connecting overlay + fresh handshake).
        //
        // Retry the refresh until the armed symptom is consumed (counter back to
        // 0) so a single debounced reconcile that coalesces does not leave the
        // symptom unfired. Each refresh is a real user-triggered reconcile.
        SshFolderListGateway.forcedStaleChannelSymptoms.set(1)
        Log.i(LOG_TAG, "armed forced stale-channel symptom; triggering picker refresh over the lease")
        val dwellStart = SystemClock.elapsedRealtime()
        val deadline = dwellStart + POLL_DWELL_MS
        var refreshes = 0
        while (SystemClock.elapsedRealtime() < deadline &&
            SshFolderListGateway.forcedStaleChannelSymptoms.get() != 0
        ) {
            triggerRefreshSessions()
            refreshes += 1
            // Give the reconcile time to run the gateway lease poll (and consume
            // the armed symptom) before re-checking / re-triggering.
            runCatching {
                compose.waitUntil(timeoutMillis = REFRESH_SETTLE_MS) {
                    SshFolderListGateway.forcedStaleChannelSymptoms.get() == 0
                }
            }
        }
        recordTiming("poll_dwell_ms", SystemClock.elapsedRealtime() - dwellStart)
        recordTiming("picker_refreshes_triggered", refreshes.toLong())
        recordTiming(
            "forced_symptom_consumed",
            if (SshFolderListGateway.forcedStaleChannelSymptoms.get() == 0) 1L else 0L,
        )
        // Confirm the injected symptom actually fired (the poll ran the lease
        // path and hit the eviction). If it never fired the test would be a
        // false-green, so fail loud.
        assertEquals(
            "the armed poll-time stale-channel symptom must have been consumed by a " +
                "picker discovery reconcile over the shared lease (else the bug is not " +
                "exercised). Forced-symptom counter not drained to 0 after $refreshes refresh(es).",
            0,
            SshFolderListGateway.forcedStaleChannelSymptoms.get(),
        )

        // ---- (4) Open session B from the picker. With the fix the warm lease
        // (held by A's VM) survived the poll's evictIdle no-op, so this is an
        // instant WARM attach — no Connecting overlay, no fresh handshake.
        // Watch for the Connecting overlay throughout the open: it must NEVER
        // appear (a sampled poll, since the overlay can flash and clear).
        //
        // Re-confirm B's row is settled + tappable before tapping (the refresh
        // above may still be reconciling), then retry the tap-until-marker-lands
        // (mirroring MultiSessionSwitchJourneyE2eTest.switchToSessionViaBackTap):
        // right after a reconcile the picker can briefly ignore the row tap, so a
        // single tap that does not land is re-driven via Back→picker→tap rather
        // than flaking the open.
        var connectingOverlaySeen = false
        val openStart = SystemClock.elapsedRealtime()
        val openDeadline = openStart + OPEN_B_TIMEOUT_MS
        var landed = false
        var openAttempts = 0
        while (!landed && SystemClock.elapsedRealtime() < openDeadline) {
            openAttempts += 1
            // If we are not on the picker (a prior tap landed on a session
            // screen that is not B), Back to the picker first.
            if (compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                clickTmuxBack()
            }
            waitForSessionInPicker(rule = compose, sessionName = SESSION_B, timeoutMs = pickerWaitMs)
            compose.onNodeWithText(SESSION_B, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
            // Sample the Connecting overlay while B's content lands.
            landed = runCatching {
                compose.waitUntil(timeoutMillis = OPEN_B_LAND_RETRY_MS) {
                    if (connectingOverlayShowing()) connectingOverlaySeen = true
                    TerminalTextMatcher.containsWrapTolerant(
                        visibleTerminalText(),
                        SESSION_B_MARKER,
                        terminalCols = terminalGridSize().columns,
                    )
                }
                true
            }.getOrDefault(false)
        }
        waitForTerminalViewAttached()
        recordTiming("open_b_to_content_ms", SystemClock.elapsedRealtime() - openStart)
        recordTiming("open_b_attempts", openAttempts.toLong())
        captureViewport("issue758-01-opened-$SESSION_B")

        if (!landed) {
            artifactFile("failure-open-b-visible-terminal.txt").writeText(visibleTerminalText())
        }
        assertTrue(
            "session B must open and show its marker '$SESSION_B_MARKER' within " +
                "${OPEN_B_TIMEOUT_MS}ms ($openAttempts attempt(s)); visible terminal:\n" +
                visibleTerminalText(),
            landed,
        )

        // ---- (5) The load-bearing assertions (visible surface — D28 rule 3).
        val handshakesAfterOpenB = SSH_HANDSHAKE_ATTEMPTS.get()
        val handshakeDelta = handshakesAfterOpenB - handshakesBeforeBack
        recordTiming("back_to_open_b_ssh_handshakes", handshakeDelta.toLong())

        // (5a) ZERO fresh SSH handshakes across back→open-B — warm-lease reuse.
        // A fresh handshake here is exactly the reconnect bug.
        assertEquals(
            "back→open-another-session must REUSE the warm lease — ZERO fresh SSH " +
                "handshakes (a fresh dial here is the #758 reconnect bug). " +
                "handshakesBeforeBack=$handshakesBeforeBack handshakesAfterOpenB=$handshakesAfterOpenB",
            0,
            handshakeDelta,
        )

        // (5b) The full-screen Connecting overlay must NEVER have shown for the
        // open of B (a warm reuse shows at most the green Attaching affordance).
        assertFalse(
            "the full-screen Connecting overlay (TMUX_CONNECTING_PROGRESS_TAG) must " +
                "NEVER appear when opening B over the warm lease — its appearance is " +
                "the visible reconnect the maintainer reports (#758).",
            connectingOverlaySeen,
        )
        // And it is not showing now either.
        assertFalse(
            "no Connecting overlay must be present after B's content is live",
            connectingOverlayShowing(),
        )

        // (5c) No Disconnected band / manual Reconnect — the transport A held was
        // never torn down under it.
        assertNoDisconnectBand("open-b-after-back")

        writeSummary(
            lines = listOf(
                "sessionA=$SESSION_A",
                "sessionB=$SESSION_B",
                "scenario=open A, BACK to picker, force ONE poll-time stale-channel " +
                    "symptom over the shared lease, open B",
                "back_to_open_b_ssh_handshakes=$handshakeDelta (expected 0 — warm reuse)",
                "connecting_overlay_seen=$connectingOverlaySeen (expected false)",
                "expectation=warm-lease reuse: no fresh handshake, no Connecting overlay, " +
                    "no Disconnected band",
            ),
        )
        writeTimings()
        Unit
    } }

    // ---------------------------------------------------------------- Helpers

    private fun readFixtureKey(): String =
        InstrumentationRegistry.getInstrumentation()
            .context
            .assets
            .open("test_key")
            .bufferedReader()
            .use { it.readText() }

    private suspend fun seedDockerHostWithWatchedRoot(key: String, hostName: String): String {
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        val db = Room.databaseBuilder(appContext, AppDatabase::class.java, DATABASE_NAME)
            .fallbackToDestructiveMigration(dropAllTables = true)
            .build()
        return try {
            db.clearAllTables()
            val storedKey = SshKeyStorage.persistKey(
                context = appContext,
                sshKeyDao = db.sshKeyDao(),
                name = "issue758-key-${System.currentTimeMillis()}",
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
            // One watched root so the picker's discovery poll ALWAYS runs the
            // SSH-lease path (watched-root expansion) — the lease the eviction
            // would poison. The path is the fixture user's real home so the
            // expansion has something to resolve, but even a missing path is fine
            // (the expansion degrades best-effort).
            db.projectRootDao().insert(
                ProjectRootEntity(hostId = hostId, label = "home", path = "/home/$DEFAULT_USER"),
            )
            HOST_ROW_TAG_PREFIX + hostId
        } finally {
            db.close()
        }
    }

    private suspend fun seedTmuxSessions(key: String) {
        val script = buildString {
            appendLine("set -eu")
            listOf(SESSION_A, SESSION_B).forEach { name ->
                appendLine("tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true")
            }
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
            "expected tmux session seeding to succeed for #758, got " +
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
                            listOf(SESSION_A, SESSION_B).joinToString(separator = "; ") { name ->
                                "tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true"
                            },
                        )
                    }
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

    /**
     * Issue #788: cold-compose-aware host-row presence poll under
     * createAndroidComposeRule (MainActivity cold compose can take ~28s on a
     * contended swiftshader emulator). Early-exits the instant the row appears,
     * and tolerates the transient "No compose hierarchies found" ISE on the very
     * first frames before composition registers.
     */
    private fun waitForHostRowPresent(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.screenRenderPresenceTimeoutMs()) {
            runCatching {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }.getOrDefault(false)
        }
    }

    /**
     * Issue #740 first-open enumeration-stall recovery: pop Back to the host
     * list (via the folder-list back affordance), wait for the host row, then
     * re-tap it to re-trigger the warm-lease acquire + `tmux list-sessions`
     * enumeration. Re-opens the SAME host's picker so the picker readiness polls
     * stay valid. Best-effort — guarded so a transient missing affordance never
     * throws out of the watchdog.
     */
    private fun repokeFolderListFromHostRow(hostRowTag: String) {
        runCatching {
            if (compose.onAllNodesWithTag(FOLDER_LIST_BACK_TAG, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            ) {
                compose.onNodeWithTag(FOLDER_LIST_BACK_TAG, useUnmergedTree = true).performClick()
            }
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                    .fetchSemanticsNodes()
                    .isNotEmpty()
            }
            compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        }
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

    /**
     * Trigger the production pull-to-refresh — the SINGLE explicit manual-refresh
     * affordance (EPIC #679 Slice 4; the kebab "Refresh Sessions" item is
     * hard-cut, D22). The swipe-down on the host-detail tree forces a picker
     * discovery reconcile over the warm SSH lease
     * (`FolderListViewModel.refreshSessions`). This is the real user gesture that
     * re-polls the lease the active session VM still holds, which is where the
     * #758 eviction fires.
     */
    private fun triggerRefreshSessions() {
        runCatching {
            compose.onNodeWithTag(FOLDER_LIST_PULL_TO_REFRESH_TAG, useUnmergedTree = true)
                .performTouchInput { swipeDown() }
        }
    }

    private fun connectingOverlayShowing(): Boolean =
        compose.onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    private fun waitForTerminalViewAttached() {
        compose.waitUntil(timeoutMillis = 30_000) {
            var attached = false
            compose.activityRule.scenario.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                attached = view?.currentSession != null && view.mEmulator != null
            }
            attached
        }
    }

    private fun waitForTerminalContains(
        expected: String,
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
    ) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                TerminalTextMatcher.containsWrapTolerant(
                    last,
                    expected,
                    terminalCols = terminalGridSize().columns,
                )
            }
            true
        }.getOrDefault(false)
        if (!satisfied) {
            artifactFile("failure-$label-visible-terminal.txt").writeText(last)
        }
        assertTrue(
            "expected visible terminal text for $label to contain '$expected' within " +
                "${timeoutMillis}ms; got:\n$last",
            satisfied,
        )
    }

    private fun assertNoDisconnectBand(label: String) {
        val count = compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
        assertTrue(
            "expected NO Disconnected band for $label, found $count — a Disconnected band " +
                "after back→open-B means the picker tore down the lease A held (#758)",
            count == 0,
        )
        val reconnectNodes = compose.onAllNodesWithText("Reconnect", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
        assertTrue(
            "expected NO manual Reconnect affordance for $label, found $reconnectNodes",
            reconnectNodes == 0,
        )
    }

    private fun visibleTerminalText(): String {
        var text = ""
        compose.activityRule.scenario.onActivity { activity ->
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

    private fun terminalGridSize(): GridSize {
        var grid: GridSize? = null
        compose.activityRule.scenario.onActivity { activity ->
            activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.let { emulator ->
                    grid = GridSize(columns = emulator.mColumns, rows = emulator.mRows)
                }
        }
        return grid ?: GridSize(columns = 80, rows = 24)
    }

    private fun captureViewport(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)

        var bitmap: Bitmap? = null
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            if (view.width <= 0 || view.height <= 0) return@onActivity
            val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(b))
            bitmap = b
        }
        bitmap?.let { writeBitmap("$name-viewport", it) }
        writeText("$name-visible-terminal.txt", visibleTerminalText())
        bitmap?.recycle()
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE758_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE758_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE758_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun writeSummary(lines: List<String>): File {
        val file = artifactFile("BackThenOpenSecondSessionReusesWarmLeaseE2eTest-summary.txt")
        file.writeText(
            buildString {
                appendLine("test=BackThenOpenSecondSessionReusesWarmLeaseE2eTest")
                appendLine("fixture_host=$DEFAULT_HOST:$DEFAULT_PORT")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("timings:")
                timings.forEach { appendLine("  $it") }
                appendLine("details:")
                lines.forEach { appendLine("  $it") }
            },
        )
        println("ISSUE758_SUMMARY ${file.absolutePath}")
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
        println("ISSUE758_TIMING $line")
    }

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

    private data class GridSize(val columns: Int, val rows: Int)

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue758BackOpen"
        const val DEVICE_DIR_NAME: String = "issue758-back-open-warm-lease"

        // Issue-prefixed so they never collide with sibling tests on the shared
        // Docker fixture.
        const val SESSION_A: String = "issue758-session-a"
        const val SESSION_B: String = "issue758-session-b"

        // Short markers that won't soft-wrap on the Pixel-7 grid.
        const val SESSION_A_MARKER: String = "AAA-READY-758"
        const val SESSION_B_MARKER: String = "BBB-READY-758"

        // Overall budget for the armed poll-time symptom to be consumed by a
        // picker discovery reconcile (triggered via the Refresh Sessions action).
        const val POLL_DWELL_MS: Long = 30_000L

        // Per-refresh settle window: how long one Refresh Sessions reconcile is
        // given to run the gateway lease poll and consume the armed symptom
        // before the loop re-triggers.
        const val REFRESH_SETTLE_MS: Long = 8_000L

        // Overall budget for B's warm open to land its content (across retries).
        const val OPEN_B_TIMEOUT_MS: Long = 45_000L

        // Per-attempt window for B's marker to land before the loop re-drives the
        // open via Back→picker→tap.
        const val OPEN_B_LAND_RETRY_MS: Long = 10_000L
    }
}
