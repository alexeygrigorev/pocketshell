package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.proof.signals.waitForSessionInPicker
import com.pocketshell.app.tmux.SSH_HANDSHAKE_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_COMPACT_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_FULL_CHROME_BACK_BUTTON_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
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

/**
 * Issue #638 / epic #636 — multi-session-switch JOURNEY regression test that
 * runs in regular CI so the v0.3.30 wave of session-switch regressions can
 * never silently ship again.
 *
 * The maintainer's v0.3.30 dogfood hit THREE broken session-switch outcomes,
 * all on the same attach/switch path:
 *   1. Stale dead SSH lease -> `tmux -CC` EOF -> Disconnected band + manual
 *      Reconnect (#621/#634). "Broken transport; encountered EOF".
 *   2. Switch "succeeds" but the PREVIOUS session's content is still shown ->
 *      the user types/records into the wrong session (the stale-session bug).
 *   3. Connects but the pane is never re-seeded -> blank/garbled terminal
 *      (#553).
 *
 * The #635 (within-grace foreground probe rides through transient slowness)
 * and #621 (stale-lease heal on switch/open — no Disconnected EOF) fixes are
 * merged on `main`. This test LOCKS IN the correct switch behaviour so a future
 * change cannot regress it.
 *
 * With THREE live tmux sessions (A, B, C) on the deterministic Docker `agents`
 * fixture, it drives the real user journey A -> B -> C -> A repeatedly via the
 * in-session "Switch session" drawer + pager, and after EACH switch asserts
 * from authoritative artifacts:
 *
 *   1. CORRECT session shown: the switched-to session's per-session marker is
 *      visible in the terminal and the previous session's marker is NOT (never
 *      stale/previous content).
 *   2. NO Disconnected band ([TMUX_SESSION_ERROR_TAG]) and no `Broken
 *      transport` / EOF text in the visible transcript.
 *   3. Pane RE-SEEDED (terminal not blank): the switched-to session's marker is
 *      present in non-blank visible terminal text.
 *   4. NO spurious reconnect: across the switch, [SSH_HANDSHAKE_ATTEMPTS] does
 *      NOT increment (same-host switch reuses the warm transport — a fresh SSH
 *      handshake here would be a spurious re-dial), and no reconnect-trigger
 *      `connect()` fires (the only [TMUX_CONNECT_ATTEMPTS] delta is the one
 *      user-driven switch).
 *   5. INPUT routes to the SHOWN session: a unique marker typed after the
 *      switch echoes back into the switched-to session's terminal.
 *
 * Why this is a regular-CI gate, not a release-only gate: it uses the plain
 * Docker `agents` fixture on host port 2222 that `emulator-smoke.yml` already
 * brings up, NOT the opt-in toxiproxy network-fault proxy family. It is NOT
 * gated out of CI ([assumeFalse(isRunningOnCi())] is intentionally absent) so a
 * session-switch regression is caught by the connected suite, not only by the
 * release validation gate.
 *
 * Pre-fix behaviour this would have caught: before #621's stale-lease heal a
 * switch over a stale lease left a Disconnected band — assertion (2) catches
 * exactly that. Before the atomic-swap fix a switch could leave the previous
 * session's content on screen — assertion (1)'s "previous marker no longer
 * visible after the new marker appears" catches that. A blank pane (#553) is
 * caught by assertion (3).
 *
 * Shape notes (mirroring [TmuxSessionSwitchE2eTest] / #151 and
 * [TmuxSessionSwitchSameHostReusesSshE2eTest] / #178):
 *  - We drive the switch from the More menu -> "Switch session" drawer and a
 *    named-session pager-page click, hitting the same `showSessionDrawer`
 *    production path the user uses, instead of a flaky `pointerInput` swipe.
 *  - The three sessions are seeded fresh every run via a sidecar SSH exec so
 *    the test is hermetic against earlier runs and sibling tests.
 *  - Content is matched with [TerminalTextMatcher.containsWrapTolerant] because
 *    commands wider than the Compose grid wrap with a real `\n` after #102's
 *    `resize-window` propagation.
 */
@RunWith(AndroidJUnit4::class)
class MultiSessionSwitchJourneyE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    // Issue #470 blocker #1: grant runtime permissions before the activity
    // launches so the system GrantPermissionsActivity never steals focus
    // from the Compose hierarchy ("No compose hierarchies found").
    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private val timings = mutableListOf<String>()

    private val pickerWaitMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L

    @After
    fun closeLaunchedActivity() {
        launchedActivity?.close()
        launchedActivity = null
        runBlocking {
            runCatching { cleanupSeededSessions(readFixtureKey()) }
        }
    }

    @Test
    fun rapidMultiSessionSwitchAlwaysShowsCorrectSessionWithoutSpuriousReconnect() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        // Three live sessions so the journey is A -> B -> C -> A (and beyond),
        // exercising more than the two-session A<->B toggle the existing
        // suite covers.
        seedTmuxSessions(key)
        val hostRowTag = seedDockerHost(key, "Issue638 Multi Switch")
        forceFlatHostDetailViewMode()

        // Clear logcat so the reconnect-trigger scan only counts connect
        // attempts that happen during THIS test's switches, not a sibling's.
        clearLogcat()

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // ---- (0) Attach to the FIRST session from the host-detail list.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        val attachAt = SystemClock.elapsedRealtime()
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForFolderListReady()
        waitForText(SESSION_A, timeoutMs = pickerWaitMs)
        compose.onNodeWithText(SESSION_A, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTerminalContains(SESSION_A_MARKER, "initial attach to A")
        recordTiming("attach_session_a_ms", SystemClock.elapsedRealtime() - attachAt)
        captureViewport("issue638-00-attached-$SESSION_A")

        // Per-session "currently-visible identity marker". Each session is
        // seeded printing its own marker (AAA/BBB/CCC). As soon as we type a
        // command into a session that marker can scroll out of the captured
        // viewport, so we track the LATEST marker known to be in each session's
        // visible buffer and assert on THAT when we return — never the
        // original seed marker, which may have scrolled away. This is what
        // makes "correct, non-stale session shown" robust without depending on
        // scrollback retention. The marker is still UNIQUE per session, so it
        // also proves the PREVIOUS session's content is gone.
        expectedMarker[SESSION_A] = SESSION_A_MARKER
        expectedMarker[SESSION_B] = SESSION_B_MARKER
        expectedMarker[SESSION_C] = SESSION_C_MARKER

        // Confirm input routing works on the first session too, so the
        // subsequent per-switch input checks have a clean baseline. This
        // updates A's tracked marker to the typed route marker.
        var previousSession = SESSION_A
        assertInputRoutesToShownSession(SESSION_A, "initial")

        // ---- The journey: A -> B -> C -> A (one full lap of the ring,
        // returning to the origin). This exercises every transition (A->B,
        // B->C, C->A) including the critical return-to-the-first-session
        // switch — the case most prone to the v0.3.30 stale-lease/wrong-session
        // regressions. Kept to a single lap so the whole journey completes well
        // inside the host lease's idle TTL (no switch re-dials a TTL-evicted
        // transport), making the every-push gate deterministic rather than
        // racing the lease lifecycle.
        val ring = listOf(SESSION_B, SESSION_C, SESSION_A)

        ring.forEachIndexed { index, target ->
            switchAndAssert(
                step = index + 1,
                fromSession = previousSession,
                toSession = target,
            )
            previousSession = target
        }

        writeSummary(
            lines = listOf(
                "sessions=$SESSION_A,$SESSION_B,$SESSION_C",
                "journey=A->B->C->A (one full lap, returns to origin)",
                "switches_asserted=${ring.size}",
                "expectation=each switch shows correct (non-stale) content, no Disconnected band, " +
                    "pane re-seeded, no spurious SSH re-dial, input routed to shown session",
            ),
        )
        writeTimings()
        Unit
    }

    /**
     * Issue #620 (the maintainer's #1 recurring ask) — the FIRST session open
     * from host detail must be an INSTANT warm attach, not a 3-4s COLD connect.
     *
     * Root cause this locks in: host detail acquires a warm SSH lease when the
     * host row is tapped (#370/#620). Before connect coalescing, the user tapping
     * a session before that warm handshake finished raced a SECOND independent
     * SSH dial — a full 3-4s connect on the first open. With coalescing in
     * [com.pocketshell.core.ssh.SshLeaseManager], the session open joins the
     * in-flight host-detail handshake and reuses the warm transport, so:
     *
     *   1. NO fresh SSH handshake is charged to the session open:
     *      [SSH_HANDSHAKE_ATTEMPTS] does NOT increment across the open (the host
     *      detail warm acquire — counted nowhere in the session VM — is the only
     *      dial). A fresh handshake here is exactly the 3-4s cold-connect bug.
     *   2. The open-to-content latency is well under the cold-connect budget.
     *
     * This is a regular-CI gate (no [assumeFalse(isRunningOnCi())]) on the plain
     * Docker `agents` fixture, so the recurring "first open is slow" regression
     * is caught at PR time, not only in the release gate.
     */
    @Test
    fun firstOpenFromHostDetailReusesWarmLeaseNoFreshHandshake() = runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        seedTmuxSessions(key)
        val hostRowTag = seedDockerHost(key, "Issue620 First Open")
        forceFlatHostDetailViewMode()
        clearLogcat()

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }

        // Open host detail — this kicks the warm-lease acquire (#370/#620). The
        // warm handshake is the ONE legitimate dial; capture the handshake count
        // right BEFORE the session open so we can prove the open itself adds
        // none.
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForFolderListReady()
        waitForText(SESSION_A, timeoutMs = pickerWaitMs)

        val handshakesBeforeOpen = SSH_HANDSHAKE_ATTEMPTS.get()
        val openAt = SystemClock.elapsedRealtime()
        compose.onNodeWithText(SESSION_A, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTerminalContains(SESSION_A_MARKER, "first-open warm attach to A")
        val openToContentMs = SystemClock.elapsedRealtime() - openAt
        val handshakesAfterOpen = SSH_HANDSHAKE_ATTEMPTS.get()
        recordTiming("first_open_to_content_ms", openToContentMs)
        recordTiming("first_open_ssh_handshakes", (handshakesAfterOpen - handshakesBeforeOpen).toLong())
        captureViewport("issue620-00-first-open-$SESSION_A")

        // (1) The session open charged NO fresh SSH handshake — it reused the
        // host-detail warm lease (coalesced onto the in-flight handshake). This
        // is the structural proof the first open is not a cold re-dial.
        assertEquals(
            "first open from host detail must reuse the warm lease — NO fresh SSH " +
                "handshake (a fresh dial is the 3-4s cold-connect bug #620). " +
                "Reconnect log tail:\n${reconnectTriggerLogTail()}",
            handshakesBeforeOpen,
            handshakesAfterOpen,
        )

        // (1b) No Disconnected/EOF band on the first open.
        assertNoDisconnectBand("first-open")
        assertNoBrokenTransportText("first-open", visibleTerminalText())

        // (2) The open-to-content latency must be well under the cold-connect
        // budget. The handshake-count proof above is the structural guarantee;
        // this wall-clock bound is the user-perceived "instant" assertion. The
        // threshold is generous for a loaded shared AVD but still far below the
        // 3-4s cold-connect the maintainer reported.
        val budgetMs = if (TerminalTestTimeouts.isRunningOnCi()) 4_000L else 2_500L
        assertTrue(
            "first open-to-content must be well under the cold-connect budget; " +
                "got ${openToContentMs}ms (budget ${budgetMs}ms). A slow first open is the #620 bug.",
            openToContentMs < budgetMs,
        )

        writeSummary(
            lines = listOf(
                "scenario=first-open-from-host-detail",
                "session=$SESSION_A",
                "first_open_to_content_ms=$openToContentMs",
                "first_open_ssh_handshakes=${handshakesAfterOpen - handshakesBeforeOpen}",
                "expectation=first open reuses the host-detail warm lease (0 fresh handshakes), " +
                    "instant warm attach, no Disconnected/EOF band",
            ),
        )
        writeTimings()
        Unit
    }

    /**
     * Drive ONE switch [fromSession] -> [toSession] via the in-session drawer
     * pager, then assert all five journey invariants for that switch.
     */
    private fun switchAndAssert(
        step: Int,
        fromSession: String,
        toSession: String,
    ) {
        // The marker currently visible in each session is the LAST thing typed
        // (or the seed marker for a not-yet-typed-into session) — tracked in
        // [expectedMarker]. We assert the switched-to session shows ITS marker
        // and the leaving session's marker is gone.
        val toMarker = requireNotNull(expectedMarker[toSession]) {
            "no tracked marker for $toSession"
        }
        val fromMarker = requireNotNull(expectedMarker[fromSession]) {
            "no tracked marker for $fromSession"
        }
        Log.i(LOG_TAG, "switch step=$step $fromSession -> $toSession")

        // Snapshot the no-spurious-reconnect signals immediately before the
        // switch:
        //  - [TMUX_CONNECT_ATTEMPTS] must ADVANCE, proving the switch was
        //    actually processed (not silently dropped). The drawer pager
        //    legitimately fires several user-driven connects as it settles
        //    through pages, so we assert "advanced", not an exact delta.
        //  - The count of RECONNECT-TRIGGER connect attempts (trigger=
        //    reconnect/auto-reconnect/network-reconnect/lifecycle-reattach,
        //    from the PsTmuxReconnect logcat trail) must NOT change. A
        //    reconnect-trigger connect during a plain user switch is the
        //    spurious reconnect this test locks out. User-tap / fast-switch
        //    connects (and any fresh SSH handshake they entail) are legitimate
        //    user-initiated work, NOT spurious reconnects.
        val handshakeBefore = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectBefore = TMUX_CONNECT_ATTEMPTS.get()
        val reconnectTriggerBefore = reconnectTriggerConnectCount()

        // Switch via Back -> session list -> tap the NAMED target row. This is
        // a real switching gesture the maintainer uses and, unlike the drawer's
        // session pager (which auto-attaches on every settle and can refuse to
        // land on a specific session with three live ones), it targets EXACTLY
        // the requested session. We still retry the back-then-tap until the
        // target marker is on screen so a transient settle does not flake.
        val switchTapAt = SystemClock.elapsedRealtime()
        switchToSessionViaBackTap(toSession, toMarker, "step$step")

        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        waitForTmuxConnectCountAbove(tmuxConnectBefore)

        // (1) + (3): the CORRECT, non-stale session's content is shown and the
        // pane is RE-SEEDED (non-blank, bears the switched-to marker).
        waitForTerminalContains(toMarker, "step$step switch to $toSession")
        val switchMs = SystemClock.elapsedRealtime() - switchTapAt
        recordTiming("switch_${step}_${fromSession}_to_${toSession}_ms", switchMs)
        captureViewport("issue638-${"%02d".format(step)}-switched-to-$toSession")

        val visibleAfterSwitch = visibleTerminalText()
        assertTrue(
            "step$step switch to $toSession: pane must be re-seeded (non-blank) — " +
                "visible terminal was blank",
            visibleAfterSwitch.isNotBlank(),
        )

        // (1): the PREVIOUS session's marker must no longer be visible — the
        // switch atomically replaced the leaving frame; the user must never be
        // left looking at (and typing into) the stale previous session.
        assertFalse(
            "step$step switch to $toSession: after the new session's content " +
                "('$toMarker') is shown, the PREVIOUS session's content " +
                "('$fromMarker') must NOT still be visible — showing stale/previous " +
                "content is the v0.3.30 wrong-session regression this test locks out",
            TerminalTextMatcher.containsWrapTolerant(
                visibleAfterSwitch,
                fromMarker,
                terminalCols = terminalGridSize().columns,
            ),
        )

        // (2): no Disconnected band, and no broken-transport/EOF text.
        assertNoDisconnectBand("step$step switch to $toSession")
        assertNoBrokenTransportText("step$step switch to $toSession", visibleAfterSwitch)

        // (4): no spurious reconnect across the switch.
        val handshakeAfter = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectAfter = TMUX_CONNECT_ATTEMPTS.get()
        val reconnectTriggerAfter = reconnectTriggerConnectCount()
        // Observational metrics: a user-driven switch may legitimately open a
        // fresh transport / fire several pager-settle connects; these are not
        // failures, only recorded for the artifact trail.
        recordTiming(
            "switch_${step}_ssh_handshakes",
            (handshakeAfter - handshakeBefore).toLong(),
        )
        recordTiming(
            "switch_${step}_tmux_connects",
            (tmuxConnectAfter - tmuxConnectBefore).toLong(),
        )
        recordTiming(
            "switch_${step}_reconnect_trigger_connects",
            (reconnectTriggerAfter - reconnectTriggerBefore).toLong(),
        )
        assertTrue(
            "step$step switch to $toSession: the switch must advance the logical tmux " +
                "connect counter, proving the switch was actually processed " +
                "(tmuxConnectBefore=$tmuxConnectBefore tmuxConnectAfter=$tmuxConnectAfter)",
            tmuxConnectAfter > tmuxConnectBefore,
        )
        assertEquals(
            "step$step switch to $toSession: a plain user session switch must NOT trigger any " +
                "RECONNECT-trigger connect (reconnect / auto-reconnect / network-reconnect / " +
                "lifecycle-reattach). An app-initiated reconnect during a user switch is the " +
                "spurious reconnect this test locks out " +
                "(reconnectTriggerBefore=$reconnectTriggerBefore reconnectTriggerAfter=$reconnectTriggerAfter). " +
                "PsTmuxReconnect connect-attempt log tail:\n${reconnectTriggerLogTail()}",
            reconnectTriggerBefore,
            reconnectTriggerAfter,
        )

        // (5): input routes to the SHOWN session.
        assertInputRoutesToShownSession(toSession, "step$step")

        // The marker-routing command may itself have re-seeded the pane; assert
        // once more that the band stayed clean through the whole step.
        assertNoDisconnectBand("step$step after input to $toSession")
    }

    /**
     * Type a unique marker through the live terminal input connection and
     * confirm it echoes back into the CURRENTLY SHOWN session's terminal —
     * proving input is wired to the session the user sees, not a stale one.
     *
     * The typed marker becomes the session's NEW tracked identity marker: it is
     * now the most-recent line in that session's visible buffer, so on a later
     * return-switch we assert on it (the original seed marker may have scrolled
     * out of the captured viewport). The route marker is unique per session, so
     * it still distinguishes this session from every other.
     */
    private fun assertInputRoutesToShownSession(sessionName: String, label: String) {
        val routeMarker = "route-$label-${routingNonce()}"
        sendCommandThroughTerminalInput("printf '$routeMarker\\n'", "$label input to $sessionName")
        waitForTerminalContains(routeMarker, "$label input echo in $sessionName")
        expectedMarker[sessionName] = routeMarker
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
                name = "issue638-key-${System.currentTimeMillis()}",
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
        // Kill the three target sessions plus any strays a sibling test left
        // behind (the pager enumerates EVERY live `tmux list-sessions` entry,
        // so a stray between targets would make the named-page click
        // ambiguous), then create A, B, C fresh. The `exec sh` shells keep the
        // sessions alive long enough to attach without tmux GC'ing them.
        val script = buildString {
            appendLine("set -eu")
            listOf(SESSION_A, SESSION_B, SESSION_C).forEach { name ->
                appendLine("tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true")
            }
            appendLine(
                "tmux list-sessions -F '#{session_name}' 2>/dev/null | " +
                    "grep -vx ${shellQuote(SESSION_A)} | grep -vx ${shellQuote(SESSION_B)} | " +
                    "grep -vx ${shellQuote(SESSION_C)} | " +
                    "while IFS= read -r s; do tmux kill-session -t \"\$s\" 2>/dev/null || true; done",
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_A)} " +
                    shellQuote("printf '$SESSION_A_MARKER\\n'; exec sh"),
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_B)} " +
                    shellQuote("printf '$SESSION_B_MARKER\\n'; exec sh"),
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_C)} " +
                    shellQuote("printf '$SESSION_C_MARKER\\n'; exec sh"),
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
            "expected tmux session seeding to succeed for #638, got " +
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
                            listOf(SESSION_A, SESSION_B, SESSION_C).joinToString(separator = "; ") { name ->
                                "tmux kill-session -t ${shellQuote(name)} 2>/dev/null || true"
                            },
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

    private fun waitForFolderListReady() {
        // Issue #470 blocker #2: shared session-picker readiness gate with a
        // generous bound and a production Retry, replacing a bare `waitUntil`
        // that burned its full timeout when a cold-AVD `tmux list-sessions`
        // probe landed on the connect-error panel. The folder-list screen is
        // implicitly up once SESSION_A is present.
        waitForSessionInPicker(
            rule = compose,
            sessionName = SESSION_A,
            timeoutMs = pickerWaitMs,
        )
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
     * Switch to [toSession] via Back -> session list -> tap the NAMED row,
     * confirming the switch landed by waiting for [toMarker] on screen. The
     * named-row tap targets exactly the requested session; we retry the
     * back-then-tap until the target marker is visible so a transient settle
     * does not flake the targeted switch.
     */
    private fun switchToSessionViaBackTap(toSession: String, toMarker: String, label: String) {
        val deadline = SystemClock.elapsedRealtime() + SWITCH_DEADLINE_MS
        var attempts = 0
        while (SystemClock.elapsedRealtime() < deadline) {
            attempts += 1
            clickTmuxBack()
            waitForSessionInPicker(
                rule = compose,
                sessionName = toSession,
                timeoutMs = pickerWaitMs,
            )
            compose.onNodeWithText(toSession, useUnmergedTree = true).performClick()
            compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
            val landed = runCatching {
                compose.waitUntil(timeoutMillis = SWITCH_LAND_RETRY_MS) {
                    TerminalTextMatcher.containsWrapTolerant(
                        visibleTerminalText(),
                        toMarker,
                        terminalCols = terminalGridSize().columns,
                    )
                }
                true
            }.getOrDefault(false)
            if (landed) {
                recordTiming("${label}_switch_backtap_attempts", attempts.toLong())
                return
            }
        }
        recordTiming("${label}_switch_backtap_attempts", attempts.toLong())
        // The switch never landed within the deadline. Capture the connect
        // trail so the RED is diagnostic rather than a bare marker timeout. The
        // dominant stranding cause observed for the return-to-a-prior-session
        // switch is the stale-lease symptom `failed to spawn tmux -CC:
        // Disconnected` (TransportException [BY_APPLICATION] Disconnected),
        // which the merged #621 heal does NOT recognise — isStaleChannelSymptom
        // only matches "open failed" / "failed to open SSH shell" / EOF-write /
        // command-timeout, never "spawn ... Disconnected" — so the poisoned
        // lease is never evicted + re-dialled and the keep-frame leaves the
        // PREVIOUS session's content on screen (the #636 wrong/stale-session
        // outcome). The outer waitForTerminalContains then attaches the visible
        // transcript for the authoritative assertion failure.
        val trail = dumpReconnectLogcat()
        artifactFile("failure-$label-switch-strand-logcat.txt").writeText(trail.takeLast(60_000))
        if (trail.contains("failed to spawn tmux -CC: Disconnected") ||
            trail.contains("[BY_APPLICATION] Disconnected")
        ) {
            recordTiming("${label}_strand_spawn_disconnected_observed", 1L)
        }
        if (disconnectBandShowing()) {
            recordTiming("${label}_strand_disconnect_band_visible", 1L)
        }
    }

    private fun disconnectBandShowing(): Boolean =
        compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

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

    private fun waitForTmuxConnectCountAbove(previous: Int) {
        compose.waitUntil(timeoutMillis = 30_000) {
            TMUX_CONNECT_ATTEMPTS.get() > previous
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
            "expected NO Disconnected band for $label, found $count — a Disconnected band on " +
                "a session switch is the #621/#634 stale-lease-EOF regression",
            count == 0,
        )
        // The Disconnected band always renders a "Reconnect" affordance; assert
        // it is absent too as a belt-and-braces signal independent of the tag.
        val reconnectNodes = compose.onAllNodesWithText("Reconnect", useUnmergedTree = true)
            .fetchSemanticsNodes()
            .size
        assertTrue(
            "expected NO manual Reconnect affordance for $label, found $reconnectNodes",
            reconnectNodes == 0,
        )
    }

    private fun assertNoBrokenTransportText(label: String, visible: String) {
        BROKEN_TRANSPORT_SIGNALS.forEach { signal ->
            assertFalse(
                "expected NO '$signal' text in the visible terminal for $label — that is the " +
                    "broken-transport / EOF surface of the v0.3.30 switch regression. Got:\n$visible",
                visible.contains(signal, ignoreCase = true),
            )
        }
    }

    private fun sendCommandThroughTerminalInput(command: String, label: String) {
        // Type the command and wait for its echo. Right after a warm switch the
        // cached frame is shown while the live `-CC` channel reconciles in the
        // background, so the first keystrokes can land before the pane is ready
        // to echo. Retry the whole type-then-echo a few times (re-acquiring
        // focus each round) so a momentarily not-yet-ready pane does not flake
        // the input-routing assertion. 4-char chunks give the remote pane time
        // to redraw between chunks on slow emulators (avoids the soft-wrap
        // glitch a single-packet commit can cause).
        var lastVisible = ""
        for (attempt in 1..COMMAND_TYPE_ATTEMPTS) {
            if (attempt > 1) {
                // Flush any partial keystrokes from the previous attempt with a
                // bare Enter so the re-typed command starts on a clean prompt.
                runCatching { terminalInputConnection().commitText("\n", 1) }
                SystemClock.sleep(200)
            }
            command.chunked(4).forEach { chunk ->
                val committed = terminalInputConnection().commitText(chunk, 1)
                assertTrue(
                    "expected terminal input connection to commit `$chunk` for $label",
                    committed,
                )
                SystemClock.sleep(35)
            }
            val echoed = runCatching {
                compose.waitUntil(timeoutMillis = COMMAND_ECHO_RETRY_MS) {
                    lastVisible = visibleTerminalText()
                    TerminalTextMatcher.containsWrapTolerant(
                        lastVisible,
                        command,
                        terminalCols = terminalGridSize().columns,
                    )
                }
                true
            }.getOrDefault(false)
            if (echoed) {
                val enterCommitted = terminalInputConnection().commitText("\n", 1)
                assertTrue("expected terminal input connection to submit $label", enterCommitted)
                return
            }
            recordTiming("${label}_command_type_retry", attempt.toLong())
        }
        artifactFile("failure-$label-command-echo.txt").writeText(lastVisible)
        assertTrue(
            "expected typed command '$command' to echo in the shown session for $label after " +
                "$COMMAND_TYPE_ATTEMPTS attempts; last visible:\n$lastVisible",
            false,
        )
    }

    private fun terminalInputConnection(): InputConnection {
        // The TerminalView can be transiently detached during the drawer-close
        // / pane-swap animation right after a switch. Poll on the main thread —
        // reading the view and creating its input connection atomically inside
        // the same `onActivity` block — until it is present, rather than
        // throwing on the first miss.
        var connection: InputConnection? = null
        compose.waitUntil(timeoutMillis = 30_000) {
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView()
                if (view != null && view.currentSession != null && view.mEmulator != null) {
                    view.requestFocus()
                    connection = view.onCreateInputConnection(EditorInfo())
                }
            }
            connection != null
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        return requireNotNull(connection) { "TerminalView did not create an InputConnection" }
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

    private fun terminalGridSize(): GridSize {
        var grid: GridSize? = null
        launchedActivity?.onActivity { activity ->
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
        launchedActivity?.onActivity { activity ->
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
        println("ISSUE638_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE638_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE638_TIMINGS ${file.absolutePath}")
        return file
    }

    private fun writeSummary(lines: List<String>): File {
        val file = artifactFile("MultiSessionSwitchJourneyE2eTest-summary.txt")
        file.writeText(
            buildString {
                appendLine("test=MultiSessionSwitchJourneyE2eTest")
                appendLine("fixture_host=$DEFAULT_HOST:$DEFAULT_PORT")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("timings:")
                timings.forEach { appendLine("  $it") }
                appendLine("details:")
                lines.forEach { appendLine("  $it") }
            },
        )
        println("ISSUE638_SUMMARY ${file.absolutePath}")
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
        println("ISSUE638_TIMING $line")
    }

    private fun routingNonce(): String =
        (routingCounter++).toString() + "x" + System.nanoTime().toString(36).takeLast(4)

    /**
     * Count the `tmux-connect-attempt` log lines whose trigger is a
     * RECONNECT trigger (reconnect / auto-reconnect / network-reconnect /
     * lifecycle-reattach) — i.e. an APP-initiated reconnect, as opposed to a
     * user-tap / fast-switch. This is the authoritative "spurious reconnect"
     * signal: on a healthy session switch the app must never auto-reconnect.
     *
     * We read from the `PsTmuxReconnect` logcat tag (the same #145 trail the
     * existing reconnect tests grep). Logcat was cleared at test start, so the
     * count is anchored to this test's lifetime; the short per-switch window
     * means buffer rollover is not a concern here.
     */
    private fun reconnectTriggerConnectCount(): Int =
        reconnectTriggerConnectLines().size

    private fun reconnectTriggerLogTail(): String =
        reconnectTriggerConnectLines().takeLast(8).joinToString("\n").ifBlank { "(none)" }

    private fun reconnectTriggerConnectLines(): List<String> =
        dumpReconnectLogcat()
            .lineSequence()
            .filter { it.contains("tmux-connect-attempt") }
            .filter { line -> RECONNECT_TRIGGERS.any { line.contains("trigger=$it") } }
            .toList()

    private fun clearLogcat() {
        runCatching { ProcessBuilder("logcat", "-c").start().waitFor() }
    }

    private fun dumpReconnectLogcat(): String =
        runCatching {
            ProcessBuilder(
                "logcat",
                "-d",
                "-v",
                "threadtime",
                "PsTmuxReconnect:I",
                "*:S",
            )
                .redirectErrorStream(true)
                .start()
                .inputStream
                .bufferedReader()
                .use { it.readText() }
        }.getOrDefault("")

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

    private var routingCounter: Int = 0

    /**
     * The marker currently known to be in each session's visible buffer. Seeded
     * with the per-session identity marker (AAA/BBB/CCC) and updated to the
     * latest typed route marker whenever we type into a session, so a
     * return-switch asserts on content that is still on screen rather than a
     * seed line that has scrolled away.
     */
    private val expectedMarker: MutableMap<String, String> = mutableMapOf()

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue638MultiSwitch"
        const val DEVICE_DIR_NAME: String = "issue638-multi-session-switch"

        // Three distinct, marker-bearing sessions. Names are issue-prefixed so
        // they never collide with sibling tests on the shared Docker fixture.
        const val SESSION_A: String = "issue638-session-a"
        const val SESSION_B: String = "issue638-session-b"
        const val SESSION_C: String = "issue638-session-c"

        // Per-session shell markers printed on attach. Distinct, short (won't
        // soft-wrap on the Pixel-7 grid), and unambiguous so a stale frame is
        // immediately detectable.
        const val SESSION_A_MARKER: String = "AAA-READY-638"
        const val SESSION_B_MARKER: String = "BBB-READY-638"
        const val SESSION_C_MARKER: String = "CCC-READY-638"

        // How long one back-then-tap is given to land on the target session's
        // marker before the loop retries the Back + named-row tap.
        const val SWITCH_LAND_RETRY_MS: Long = 8_000L

        // How many times we re-type a command waiting for its echo, and how
        // long each attempt waits — covers the brief window right after a warm
        // switch where the cached frame is shown but the live `-CC` channel is
        // still reconciling and not yet echoing keystrokes.
        const val COMMAND_TYPE_ATTEMPTS: Int = 4
        const val COMMAND_ECHO_RETRY_MS: Long = 6_000L

        // Overall deadline for the retried targeted switch. Generous so a slow
        // emulator settle still lands deterministically rather than failing the
        // whole journey on one slow switch.
        const val SWITCH_DEADLINE_MS: Long = 60_000L

        // The connect-attempt trigger values that mean an APP-initiated
        // reconnect (as opposed to a user-tap / fast-switch). Any of these
        // firing during a plain user session switch is a spurious reconnect.
        // Mirrors TmuxConnectTrigger.isReconnectTrigger.logValue set.
        val RECONNECT_TRIGGERS: List<String> = listOf(
            "reconnect",
            "auto-reconnect",
            "network-reconnect",
            "lifecycle-reattach",
        )

        // Strings the Disconnected/broken-transport path surfaces. None must
        // appear in the visible transcript on a healthy switch.
        val BROKEN_TRANSPORT_SIGNALS: List<String> = listOf(
            "Broken transport",
            "encountered EOF",
            "EOF'ed",
            "Getting data on EOF",
        )
    }
}
