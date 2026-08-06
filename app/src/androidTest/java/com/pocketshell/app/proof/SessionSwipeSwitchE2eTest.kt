package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeWithVelocity
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.SSH_HANDSHAKE_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_CONNECT_ATTEMPTS
import com.pocketshell.app.tmux.TMUX_CONSOLIDATED_SESSION_LABEL_TAG
import com.pocketshell.app.tmux.TMUX_CONVERSATION_DETECTING_TAG
import com.pocketshell.app.tmux.TMUX_CONVERSATION_PANE_TAG
import com.pocketshell.app.tmux.TMUX_FULL_BREADCRUMB_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_PAGER_OVERLAY_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_PAGER_PAGE_TAG_PREFIX
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_TERMINAL_TAB_TAG
import com.pocketshell.app.tmux.TmuxSessionLatencyTelemetry
import com.pocketshell.app.proof.signals.SyntheticFocusOwnerHarness
import com.pocketshell.app.proof.signals.activityWindowFocused
import com.pocketshell.app.proof.signals.describeActiveWindow
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.testsupport.FOREIGN_WINDOW_OVER_VIEWPORT_SIGNATURE
import com.pocketshell.testsupport.MarkerBandCaptureVerdict
import com.pocketshell.testsupport.evaluateMarkerBandCapture
import com.pocketshell.testsupport.requiredMarkerSpanPx
import com.termux.view.TerminalView
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
 * Issue #237 — verifies the "swipe between sessions" feature shipped in
 * commit `970cb54` ([SessionSwitcherOverlay]).
 *
 * The maintainer dogfood ask:
 *
 *  > "I really like swiping between windows like how it looks like — maybe
 *  >  there is also a way to in the same way to swipe between sessions or
 *  >  different sessions."
 *
 * The feature reuses the windows-pager pattern at the session level: a
 * swipe-DOWN on the top chrome opens a [HorizontalPager] of same-host
 * sessions ([TMUX_SESSION_PAGER_OVERLAY_TAG]); choosing a session
 * lazy-attaches it. This test exercises the path end-to-end on the
 * deterministic Docker `agents` fixture and pins five contracts:
 *
 *  1. **The switch happens.** A horizontal swipe switches the app AWAY from
 *     SESSION_A onto the adjacent same-host session: SESSION_A's unique
 *     `A-READY` marker disappears and the adjacent session renders its own
 *     (non-blank) terminal content. So two distinct sessions' content render
 *     via the swipe gesture — SESSION_A's marker before, the adjacent
 *     session's shell after — proving the visible app state actually changed,
 *     not just an assertion flipping green.
 *  2. **SSH transport is reused** (#178). The same-host swipe-switch must
 *     not fire a fresh SSH handshake; [SSH_HANDSHAKE_ATTEMPTS] must not
 *     advance across the switch. A fresh socket would be the 2-5s
 *     `kex_exchange_identification` regression the fast-switch path deleted.
 *  3. **Exactly one previous tmux client remains owned by the warm cache.**
 *     Since #626, a same-host switch deliberately parks SESSION_A's live
 *     runtime so switch-back is a pointer swap. `tmux list-clients` must
 *     therefore report exactly one client on SESSION_A: zero would mean the
 *     warm-switch contract was lost; more than one would be a real duplicate
 *     / orphan regression (#235 / #215).
 *  4. **The swipe debounces.** The pager's settle path can emit spurious
 *     `settledPage` values (the spike's flagged risk); if the overlay acted
 *     on them the logical tmux connect counter would advance more than once
 *     for the single swipe. We assert exactly one logical tmux connect fires
 *     for that swipe, so a spurious double-fire is caught.
 *  5. **Switch-back activates the cached runtime.** A second pager swipe from
 *     B back to A must atomically restore A's marker, record one cache
 *     activation, open no fresh tmux control client or SSH transport, and
 *     leave exactly one owned client on each runtime.
 *
 * Gesture note: BOTH gestures are driven as real touch input — the
 * swipe-DOWN that opens the session pager AND the horizontal swipe-LEFT that
 * settles onto the adjacent session (the settle fires the
 * `onSelectSession` → `onReplaceTmuxSession` switch). The horizontal swipe
 * is anchored on the current session's on-screen page card so it targets a
 * real, queryable node. We verify the swipe CONTRACT (switch away from
 * SESSION_A onto a distinct session) rather than reaching a specific session
 * name, because the shared `agents` fixture holds other same-host sessions
 * whose recency-based pager order is not deterministic and re-sorts on each
 * pager open; killing them to isolate a 2-session pager would race sibling
 * worktrees.
 *
 * Companion to [TmuxSessionSwitchSameHostReusesSshE2eTest] (#178), which
 * drives the switch via the kebab → drawer path; this one drives the
 * swipe-DOWN + pager-swipe session surface the maintainer asked for.
 */
@RunWith(AndroidJUnit4::class)
class SessionSwipeSwitchE2eTest {

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
     * Budget for compose UI conditions (overlay open, breadcrumb settle).
     * 20s local, 60s on the swiftshader CI emulator under Docker load.
     */
    private val pickerWaitMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L

    /**
     * Budget for the session-picker to transition `Loading` → `Ready`. The
     * picker opens a FRESH SSH connection per load (separate from the
     * attached session's live transport), then runs a remote
     * `tmux list-sessions`. Under emulator + Docker `agents` contention this
     * connect+exec round-trip has been observed to exceed 20s, so we give it
     * a generous ceiling distinct from the snappier UI-condition budget.
     */
    private val pickerReadyWaitMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 120_000L else 45_000L

    /**
     * Issue #1994: how long the device surface is given to actually PAINT a band
     * the emulator model already holds. The old capture allowed 150 ms with no
     * retry, which is not a paint barrier at all — see [captureViewport]. The
     * pump exits the instant the band appears, so a healthy device pays one
     * settle; only a genuinely unpainted surface spends the whole budget.
     */
    private val capturePaintBudgetMs: Long =
        if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 10_000L

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
    fun swipeDownPagerSwitchesSessionAndActivatesCachedRuntimeOnReturn() { runBlocking {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))

        seedTmuxSessions(key)
        val hostRowTag = seedDockerHost(key, "Issue237 Swipe")

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // ---- (1) Attach to SESSION_A through the host row + picker.
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SESSION_A, timeoutMs = pickerWaitMs)
        compose.onNodeWithText(SESSION_A).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        selectTerminalTabForVisibleCapture()

        // Confirm SESSION_A's content is actually on screen before we
        // switch — otherwise a "switch" assertion later could pass against
        // a still-loading SESSION_A placeholder.
        waitForVisibleTerminal("session A ready") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                A_MARKER,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("issue237-01-attached-session-a", A_MARKER)

        // ---- (2) The app is attached to SESSION_A; confirm exactly one
        // client is registered for it server-side. This is the baseline
        // for the detach-on-leave assertion below.
        val clientsOnAWhileAttached = listClientsCount(key, SESSION_A)
        Log.i(LOG_TAG, "clients on $SESSION_A while attached = $clientsOnAWhileAttached")
        assertTrue(
            "expected at least one tmux client on $SESSION_A while the app is " +
                "attached, got $clientsOnAWhileAttached",
            clientsOnAWhileAttached >= 1,
        )

        // Snapshot the SSH handshake + tmux connect counters right before the
        // gesture-driven switch. The SSH handshake counter must NOT advance
        // (transport reuse, #178); the tmux connect counter must advance by
        // exactly one (the switch processed exactly once — debounce).
        val handshakeBefore = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectBefore = TMUX_CONNECT_ATTEMPTS.get()
        Log.i(LOG_TAG, "snapshot-before handshake=$handshakeBefore tmuxConnect=$tmuxConnectBefore")

        // Make the test-owned B session the first non-current page. The shared
        // Docker fixture may contain unrelated sessions, and the picker sorts
        // them by second-resolution activity before name. Updating B immediately
        // before opening the pager makes A -> B deterministic without deleting
        // any foreign session.
        bumpSessionActivity(key, SESSION_B)

        // ---- (3) Swipe the session pager FORWARD one page, switching away
        // from SESSION_A — the exact horizontal session-swipe the maintainer
        // asked for. The deterministic `agents` fixture is SHARED and the
        // pager order is recency-descending then by name
        // (HostTmuxSessionPickerViewModel), so the page after SESSION_A is
        // some OTHER same-host session (an observed run had `claude-main`).
        // We do NOT depend on which session that is, nor on reaching a
        // specific name (that would require killing foreign sessions and
        // racing sibling worktrees). We verify the GESTURE CONTRACT: a
        // forward swipe switches the app to the adjacent session — its content
        // replaces SESSION_A's, the previous runtime is parked as the single
        // owned cache entry, and the SSH transport is reused — proving two
        // distinct sessions' content render via the swipe gesture (SESSION_A's
        // marker before, the adjacent session's shell after).
        val switchAt = SystemClock.elapsedRealtime()
        val forwardSession = swipeSessionPagerForwardOnce(
            previousSession = SESSION_A,
            captureOpenAs = "issue237-02-pager-open-fullframe",
        )
        captureFullFrame("issue237-03-swiped-forward-to-$forwardSession-fullframe")
        assertTrue(
            "the first forward pager page must be the freshly-active test-owned " +
                "$SESSION_B session; landed on '$forwardSession'",
            forwardSession == SESSION_B,
        )
        selectTerminalTabForVisibleCapture()
        // The adjacent session's content replaces SESSION_A's: SESSION_A's
        // unique marker must no longer be on screen.
        waitForVisibleTerminal("forward swipe left SESSION_A (A-marker gone)") { transcript ->
            !TerminalTextMatcher.containsWrapTolerant(
                transcript,
                A_MARKER,
                terminalCols = terminalGridSize().columns,
            )
        }
        val forwardSwitchMs = SystemClock.elapsedRealtime() - switchAt
        recordTiming("forward_swipe_switch_ms", forwardSwitchMs)

        // ---- (4) Structural invariants for the forward swipe.
        val handshakeAfter = SSH_HANDSHAKE_ATTEMPTS.get()
        val tmuxConnectAfter = TMUX_CONNECT_ATTEMPTS.get()
        Log.i(
            LOG_TAG,
            "snapshot-after-forward handshake=$handshakeAfter tmuxConnect=$tmuxConnectAfter " +
                "forwardSession=$forwardSession",
        )
        recordTiming("ssh_handshakes_during_forward", (handshakeAfter - handshakeBefore).toLong())
        recordTiming("tmux_connects_during_forward", (tmuxConnectAfter - tmuxConnectBefore).toLong())

        // (a) SSH transport reused — no fresh handshake (#178). The same-host
        // swipe-switch must not fire a new SSH handshake.
        assertEquals(
            "swipe-driven same-host session switch must NOT fire a fresh SSH " +
                "handshake (handshakeBefore=$handshakeBefore handshakeAfter=$handshakeAfter); " +
                "the live SSH transport must be reused for the new session's TmuxClient",
            handshakeBefore,
            handshakeAfter,
        )

        // (b) Debounce: the forward swipe processed EXACTLY one logical tmux
        // connect. The spike flagged that the pager can emit spurious
        // `settledPage` values mid-swipe; if the overlay acted on them the
        // tmux connect counter would advance more than once for the single
        // swipe.
        val tmuxConnectsForward = tmuxConnectAfter - tmuxConnectBefore
        assertEquals(
            "a single forward session-pager swipe must process exactly one logical " +
                "tmux connect (debounce contract); a spurious settledPage double-fire " +
                "would advance it more than once. tmuxConnectBefore=$tmuxConnectBefore " +
                "tmuxConnectAfter=$tmuxConnectAfter",
            1,
            tmuxConnectsForward,
        )

        // (c) The previous runtime is deliberately cached, but never duplicated.
        // #626 changed the fast-switch contract from detach-on-leave to a bounded
        // process-owned warm cache. The nightly used to assert the superseded zero
        // count and therefore failed on the one healthy cached client. Exactly one
        // is the class-covering signal: it proves the warm owner exists and catches
        // the actual duplicate/orphan accumulation class (>1).
        val cachedClients = listClientsCount(key, SESSION_A)
        writeText("issue237-clients-on-a-after-forward-swipe.txt", "clients=$cachedClients\n")
        assertEquals(
            "after swiping away from $SESSION_A the bounded warm-runtime cache must own " +
                "exactly one tmux -CC client (zero loses instant switch-back; more than one " +
                "is a duplicate/orphan, #235/#215); tmux list-clients reported $cachedClients",
            1,
            cachedClients,
        )

        // ---- (5) B renders its OWN seeded content (not a blank screen, and
        // not SESSION_A's stale frame).
        waitForVisibleTerminal("session B marker after A-to-B swipe") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                B_MARKER,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("issue237-04-adjacent-session-content", B_MARKER)

        // ---- (6) Swipe from B back to A. Refresh A's tmux activity just before
        // opening the pager so it is deterministically the first non-current
        // page even when the shared fixture contains foreign sessions. A is
        // already live in the runtime cache; this remote send-keys only affects
        // picker ordering and does not create an app SSH/tmux runtime.
        bumpSessionActivity(key, SESSION_A)
        val returnHandshakeBefore = SSH_HANDSHAKE_ATTEMPTS.get()
        val returnTmuxLogicalBefore = TMUX_CONNECT_ATTEMPTS.get()
        val returnTelemetryBefore = TmuxSessionLatencyTelemetry.snapshot()
        val returnAt = SystemClock.elapsedRealtime()
        val returnedSession = swipeSessionPagerForwardOnce(
            previousSession = forwardSession,
            captureOpenAs = "issue237-05-return-pager-open-fullframe",
        )
        assertEquals(
            "second pager swipe must return from $forwardSession to cached $SESSION_A",
            SESSION_A,
            returnedSession,
        )
        selectTerminalTabForVisibleCapture()
        waitForVisibleTerminal("cached A marker after B-to-A switch-back") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                A_MARKER,
                terminalCols = terminalGridSize().columns,
            )
        }
        val returnSwitchMs = SystemClock.elapsedRealtime() - returnAt
        val returnHandshakeAfter = SSH_HANDSHAKE_ATTEMPTS.get()
        val returnTmuxLogicalAfter = TMUX_CONNECT_ATTEMPTS.get()
        val returnTelemetry = TmuxSessionLatencyTelemetry.snapshot().drop(returnTelemetryBefore.size)
        val returnCacheActivations = returnTelemetry.filter { it.name == "runtime_cache_activate" }
        val returnControlAttaches = returnTelemetry.filter { it.name == "tmux_control_attach_count" }

        assertEquals(
            "cached B-to-A pointer swap must not perform another SSH handshake",
            returnHandshakeBefore,
            returnHandshakeAfter,
        )
        assertEquals(
            "cached B-to-A pointer swap must activate exactly one cached runtime; " +
                "events=$returnTelemetry",
            1,
            returnCacheActivations.size,
        )
        assertTrue(
            "cached B-to-A pointer swap must not attach a fresh tmux -CC client; " +
                "events=$returnTelemetry",
            returnControlAttaches.isEmpty(),
        )
        assertEquals(
            "B-to-A gesture must be accepted exactly once as a logical connect; " +
                "the separate tmux_control_attach_count oracle proves that this " +
                "logical activation did not open a new -CC connection",
            1,
            returnTmuxLogicalAfter - returnTmuxLogicalBefore,
        )

        val clientsOnAAfterReturn = listClientsCount(key, SESSION_A)
        val clientsOnBAfterReturn = listClientsCount(key, forwardSession)
        writeText(
            "issue237-clients-after-return-swipe.txt",
            "clients_on_A=$clientsOnAAfterReturn\n" +
                "clients_on_B=$clientsOnBAfterReturn\n",
        )
        assertEquals(
            "active $SESSION_A must still have exactly one owned -CC client after " +
                "cached activation (no duplicate attach)",
            1,
            clientsOnAAfterReturn,
        )
        assertEquals(
            "switched-away $forwardSession must have exactly one cache-owned -CC " +
                "client after return (no orphan/duplicate accumulation)",
            1,
            clientsOnBAfterReturn,
        )
        // The cached frame is published synchronously, followed by a legitimate
        // asynchronous remote refresh. Wait for that refresh to settle before
        // taking the authoritative bitmap so a mid-row draw cannot produce a
        // misleading partial-marker screenshot even though the screen grid is
        // already correct.
        SystemClock.sleep(1_000L)
        waitForVisibleTerminal("stable visible A marker before return capture") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                A_MARKER,
                terminalCols = terminalGridSize().columns,
            )
        }
        captureViewport("issue237-06-returned-to-session-a", A_MARKER)
        recordTiming("return_swipe_switch_ms", returnSwitchMs)
        recordTiming(
            "ssh_handshakes_during_return",
            (returnHandshakeAfter - returnHandshakeBefore).toLong(),
        )
        recordTiming(
            "logical_tmux_connects_during_return",
            (returnTmuxLogicalAfter - returnTmuxLogicalBefore).toLong(),
        )
        recordTiming("runtime_cache_activations_during_return", returnCacheActivations.size.toLong())
        recordTiming("tmux_control_attaches_during_return", returnControlAttaches.size.toLong())

        writeTimings()
        writeText(
            "issue237-counters.txt",
            buildString {
                appendLine("clients_on_A_while_attached=$clientsOnAWhileAttached")
                appendLine("forward_swipe_session=$forwardSession")
                appendLine("ssh_handshakes_during_forward=${handshakeAfter - handshakeBefore}")
                appendLine("tmux_connects_during_forward=$tmuxConnectsForward")
                appendLine("owned_cached_clients_on_A_after_forward=$cachedClients")
                appendLine("forward_swipe_switch_ms=$forwardSwitchMs")
                appendLine("return_swipe_session=$returnedSession")
                appendLine("ssh_handshakes_during_return=${returnHandshakeAfter - returnHandshakeBefore}")
                appendLine("logical_tmux_connects_during_return=${returnTmuxLogicalAfter - returnTmuxLogicalBefore}")
                appendLine("runtime_cache_activations_during_return=${returnCacheActivations.size}")
                appendLine("tmux_control_attaches_during_return=${returnControlAttaches.size}")
                appendLine("clients_on_A_after_return=$clientsOnAAfterReturn")
                appendLine("clients_on_B_after_return=$clientsOnBAfterReturn")
                appendLine("return_swipe_switch_ms=$returnSwitchMs")
            },
        )

        Unit
    } }

    /**
     * Issue #1994, arm 1 — a foreign window over the viewport must be NAMED, not
     * mistaken for the terminal failing to paint.
     *
     * **This arm is CLASS COVERAGE, not the reproduction of the `1ddeadb3`
     * red.** Round 2 re-derived that failure from the run's own per-class
     * logcat: the app was RESUMED and in front at capture time, with no ANR,
     * dialog or foreign-window signature — see the [captureViewport] KDoc for
     * the timestamps. The hosted red was the single-shot 150 ms non-composition
     * barrier, and the bounded pump fixes it.
     *
     * What this arm does cover is the neighbouring capture world the same shard
     * demonstrably contains: five OTHER classes on it failed with
     * `active_window_pkg=android active_window_class=android.widget.FrameLayout`,
     * i.e. a framework window owning the display. A window over the app does not
     * merely dim the frame — the captured pixels are not the app's pixels at
     * all, so a missing band there says nothing about what PocketShell painted,
     * and the oracle must say so rather than blame the terminal.
     *
     * The state is injected SYNTHETICALLY (D33: the environment cannot be asked
     * to raise a framework error dialog on demand inside a sibling-shared AVD)
     * using the reviewed #1985 [SyntheticFocusOwnerHarness], expanded to actually
     * cover the display so the injection is deterministic rather than relying on
     * the dialog's dim amount.
     *
     * RED on base: the capture throws "device-visible viewport did not paint the
     * complete 'A237-READY' cell band". GREEN with the fix: it throws the
     * foreign-owner attribution instead. Note it still THROWS — attribution is
     * never amnesty (pinned on the JVM by `MarkerBandCaptureVerdictTest`).
     */
    @Test
    fun foreignWindowOverTheViewportIsNamedInsteadOfBlamingTheTerminalPaint() { runBlocking {
        val key = attachToSessionAForCaptureProof()

        // The injected owner is a Dialog hosted by the instrumented app, so the
        // reading it produces is `active_window_pkg=<our own package>`, not a
        // genuinely third-party package like the hosted `android` window. What
        // is under test is the ORACLE's rule — "the app's Activity window did not
        // own the display, so these are not the app's pixels" — which reads
        // `activityWindowFocused`, not the package string; the package is only
        // carried into the message so a red shard says WHO owned the display.
        // A real cross-package owner cannot be commanded on demand inside a
        // sibling-shared AVD (D33: inject synthetically, hard-fail otherwise).
        val harness = SyntheticFocusOwnerHarness(
            scenario = requireNotNull(launchedActivity),
            label = "issue-1994 synthetic focus owner",
        )
        var captureFailure: Throwable? = null
        harness.withOwner { dialog ->
            // Cover the whole display so the injected state is "the captured
            // frame is not the app's", deterministically, on every device
            // density — not "the dialog's default dim happened to be enough".
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                dialog.window?.setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                dialog.window?.decorView?.setBackgroundColor(android.graphics.Color.BLACK)
            }
            InstrumentationRegistry.getInstrumentation().waitForIdleSync()
            SystemClock.sleep(500)
            captureFailure = runCatching {
                captureViewport("issue1994-01-foreign-window-over-viewport", A_MARKER)
            }.exceptionOrNull()
        }

        val failure = requireNotNull(captureFailure) {
            "expected the viewport capture to FAIL while a foreign window owned the " +
                "display — a green there would mean the oracle accepted a frame that " +
                "was never the app's"
        }
        val message = failure.message.orEmpty()
        assertTrue(
            "a capture taken while a foreign window owned the display must name that " +
                "owner, not blame the terminal's paint; got:\n$message",
            message.contains(FOREIGN_WINDOW_OVER_VIEWPORT_SIGNATURE),
        )
        assertTrue(
            "the foreign-owner attribution must carry the active window reading so a " +
                "red shard says WHO owned the display; got:\n$message",
            message.contains("active_window_pkg="),
        )
        // Prove the app really is healthy afterwards: the same capture that just
        // failed must pass once the foreign window is gone. Without this the test
        // could pass against a genuinely broken terminal.
        captureViewport("issue1994-02-after-foreign-window-dismissed", A_MARKER)
        writeText(
            "issue1994-foreign-window-attribution.txt",
            "captured_failure_message=$message\n",
        )
        Unit
    } }

    /**
     * Issue #1994, arm 2 — a device surface that has not painted the band YET
     * must be given a bounded second look, not judged on one 150 ms shot.
     *
     * `waitForIdleSync()` is a main-thread barrier, not a composition barrier:
     * it returns before SurfaceFlinger has necessarily put the frame on the
     * display. On a hosted swiftshader emulator under a 300-test shard the model
     * can lead the painted surface by well over the old flat 150 ms, which is
     * how a correct app produced `matchingPixels=0`.
     *
     * The lag is injected SYNTHETICALLY as an opaque, NON-focusable overlay in
     * the activity's own window (so window focus stays with the app and this arm
     * cannot pass through arm 1's attribution path), removed after
     * [SYNTHETIC_OCCLUSION_MS] — comfortably longer than the old single shot and
     * far inside the bounded budget.
     *
     * RED on base: the single-shot capture fails with the paint message. GREEN
     * with the fix: the pump keeps looking and captures the real frame.
     */
    @Test
    fun transientlyOccludedSurfaceIsCapturedOnceItRepaints() { runBlocking {
        attachToSessionAForCaptureProof()
        val scenario = requireNotNull(launchedActivity)

        var overlay: View? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            scenario.onActivity { activity ->
                val cover = View(activity).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    isFocusable = false
                    isFocusableInTouchMode = false
                }
                activity.addContentView(
                    cover,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    ),
                )
                overlay = cover
            }
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue(
            "the synthetic occlusion must NOT steal window focus — otherwise this arm " +
                "would exercise the foreign-owner attribution instead of the paint pump",
            activityWindowFocused(scenario),
        )

        val remover = Thread {
            SystemClock.sleep(SYNTHETIC_OCCLUSION_MS)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val cover = overlay ?: return@runOnMainSync
                (cover.parent as? ViewGroup)?.removeView(cover)
            }
        }
        remover.start()
        try {
            // Load-bearing: this must SUCCEED. On base it is evaluated once,
            // 150 ms in, while the cover is still up, and hard-fails.
            captureViewport("issue1994-03-transient-occlusion-repaint", A_MARKER)
        } finally {
            remover.join(SYNTHETIC_OCCLUSION_MS * 3)
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val cover = overlay
                if (cover != null) (cover.parent as? ViewGroup)?.removeView(cover)
            }
        }
        Unit
    } }

    /**
     * Shared prologue for the #1994 capture-attribution arms: seed SESSION_A,
     * attach through the real host-row + picker journey, and wait until the
     * emulator MODEL holds the marker. Everything after that is about what the
     * DEVICE painted.
     */
    private suspend fun attachToSessionAForCaptureProof(): String {
        val key = readFixtureKey()
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSessions(key)
        val hostRowTag = seedDockerHost(key, "Issue1994 Capture")
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(timeoutMillis = 10_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        waitForText(SESSION_A, timeoutMs = pickerWaitMs)
        compose.onNodeWithText(SESSION_A).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
        selectTerminalTabForVisibleCapture()
        waitForVisibleTerminal("session A ready") { transcript ->
            TerminalTextMatcher.containsWrapTolerant(
                transcript,
                A_MARKER,
                terminalCols = terminalGridSize().columns,
            )
        }
        return key
    }

    /**
     * Open the session pager with a swipe-DOWN gesture, then swipe it one page
     * LEFT (forward), and return the session the app lands on (read from the
     * top-chrome session label). Asserts the pager opens and reaches `Ready`
     * first.
     */
    private fun swipeSessionPagerForwardOnce(
        previousSession: String,
        captureOpenAs: String,
    ): String {
        // Swipe DOWN on the top chrome to open the session pager. The swipe
        // surface ([verticalSwipeInput]) wraps only the ~56dp top chrome, so a
        // stock `swipeDown()` confined to the breadcrumb node travels far less
        // than the 72.dp open threshold; we dispatch an explicit downward drag
        // whose cumulative travel (tracked by `detectVerticalDragGestures` as
        // `totalDrag`) clears it — Compose dispatches pointer moves past the
        // node's lower edge to the same pointer-input modifier, like a real
        // finger drag.
        compose.onNodeWithTag(TMUX_FULL_BREADCRUMB_TAG, useUnmergedTree = true)
            .performTouchInput {
                down(Offset(centerX, centerY))
                repeat(SWIPE_DOWN_STEPS) { moveBy(Offset(0f, SWIPE_DOWN_STEP_PX)) }
                up()
            }

        // Wait for the overlay to appear (the swipe-down opened it). The pager
        // is a Compose overlay over the TerminalView, so only a full-frame
        // device screenshot can show it.
        compose.waitUntil(timeoutMillis = pickerWaitMs) {
            compose.onAllNodesWithTag(TMUX_SESSION_PAGER_OVERLAY_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        captureFullFrame(captureOpenAs)

        // Wait for the picker to reach `Ready` with the real same-host session
        // list. While `Loading`, `sessionSwitcherPages` returns a single
        // synthetic placeholder ("loading same-host sessions") that cannot be
        // swiped to advance; once `Ready` the current card status flips to
        // "current". The picker opens a fresh SSH connection per load, so under
        // emulator/Docker contention this can take a while.
        val pickerReady = runCatching {
            compose.waitUntil(timeoutMillis = pickerReadyWaitMs) {
                val readyStatusVisible = compose
                    .onAllNodes(
                        hasText(READY_CURRENT_STATUS)
                            .and(hasAnyAncestor(hasTestTag(TMUX_SESSION_PAGER_OVERLAY_TAG))),
                        useUnmergedTree = true,
                    ).fetchSemanticsNodes().isNotEmpty()
                val loadingGone = compose
                    .onAllNodesWithText(LOADING_PLACEHOLDER_STATUS, useUnmergedTree = true)
                    .fetchSemanticsNodes().isEmpty()
                readyStatusVisible && loadingGone
            }
            true
        }.getOrDefault(false)
        if (!pickerReady) {
            captureFullFrame("issue237-FAIL-picker-not-ready-fullframe")
        }
        assertTrue(
            "session pager never reached Ready within ${pickerReadyWaitMs}ms; the " +
                "picker's remote tmux list-sessions did not resolve (see " +
                "issue237-FAIL-picker-not-ready screenshot + logcat).",
            pickerReady,
        )

        // Swipe the pager one page LEFT (content moves left → next page). We
        // anchor the fling on the CURRENT session's ON-SCREEN page card (the
        // pager re-scrolls to the current session on open) so the coordinates
        // are relative to a real, full-width, queryable node — off-screen
        // pages are ~0-width and not reliably tappable on the swiftshader
        // emulator. `swipeWithVelocity` spaces the synthetic pointer events
        // over `durationMillis` so the pager computes a genuine velocity and
        // settles exactly one page rather than snapping back.
        val currentPageIndex = onScreenPageIndex()
        compose.onNodeWithTag(
            "$TMUX_SESSION_PAGER_PAGE_TAG_PREFIX$currentPageIndex",
            useUnmergedTree = true,
        ).performTouchInput {
            val midY = centerY
            swipeWithVelocity(
                start = Offset(right - SWIPE_PAGER_EDGE_INSET_PX, midY),
                end = Offset(left + SWIPE_PAGER_EDGE_INSET_PX, midY),
                endVelocity = SWIPE_PAGER_VELOCITY,
                durationMillis = SWIPE_PAGER_DURATION_MS,
            )
        }

        // The settledPage flow fires onSelectSession → the overlay closes and
        // the app lazy-attaches the newly-settled session.
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        val next = waitForActiveSessionChange(previous = previousSession)
        waitForTerminalViewAttached()
        Log.i(LOG_TAG, "forward swipe from '$previousSession' landed on '$next'")
        return next
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
                name = "issue237-key-${System.currentTimeMillis()}",
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
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_A)} " +
                    shellQuote("printf '$PIXEL_MARKER_ANSI$A_MARKER$PIXEL_MARKER_RESET\\n'; exec sh"),
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_B)} " +
                    shellQuote("printf '$PIXEL_MARKER_ANSI$B_MARKER$PIXEL_MARKER_RESET\\n'; exec sh"),
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
            "expected tmux session seeding to succeed for #237, got " +
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
                                "tmux kill-session -t ${shellQuote(SESSION_B)} 2>/dev/null || true",
                        )
                    }
                }
            }
        }
    }

    /**
     * Issue #235/#215: return the number of clients currently attached to
     * [sessionName] per `tmux list-clients -t <session>`. One line per
     * client; zero clients prints nothing (exit 0).
     */
    private suspend fun listClientsCount(key: String, sessionName: String): Int {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec("tmux list-clients -t ${shellQuote(sessionName)} 2>/dev/null || true")
            }
        }
        return result.getOrNull()?.stdout.orEmpty().lines().count { it.isNotBlank() }
    }

    /**
     * Move a test-owned session to the front of the picker's activity ordering
     * without changing the app's active runtime. `Space` + `BSpace` leaves the
     * shell command line unchanged while tmux updates `session_activity`.
     */
    private suspend fun bumpSessionActivity(key: String, sessionName: String) {
        // tmux exposes session activity at one-second resolution. Crossing a
        // second boundary makes the ordering deterministic against sessions
        // touched earlier in this journey.
        SystemClock.sleep(1_100L)
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session ->
            session.use {
                it.exec(
                    "tmux send-keys -t ${shellQuote(sessionName)} Space BSpace; " +
                        "tmux display-message -p -t ${shellQuote(sessionName)} '#{session_activity}'",
                )
            }
        }
        val exec = result.getOrNull()
        assertTrue(
            "failed to refresh picker activity for $sessionName: " +
                "exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
    }

    private fun waitForText(text: String, timeoutMs: Long) {
        compose.waitUntil(timeoutMillis = timeoutMs) {
            compose.onAllNodesWithText(text, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
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

    /** Ensure screenshots observe the real Terminal surface, not the #818
     * Conversation default used while a presumed-agent transcript loads. */
    private fun selectTerminalTabForVisibleCapture() {
        val deadline = SystemClock.elapsedRealtime() + 20_000L
        while (SystemClock.elapsedRealtime() < deadline) {
            compose.waitForIdle()
            val conversationVisible =
                hasTag(TMUX_CONVERSATION_DETECTING_TAG) || hasTag(TMUX_CONVERSATION_PANE_TAG)
            if (!conversationVisible) return
            if (hasTag(TMUX_TERMINAL_TAB_TAG)) {
                compose.onNodeWithTag(TMUX_TERMINAL_TAB_TAG, useUnmergedTree = true)
                    .performClick()
            }
            SystemClock.sleep(250L)
        }
        assertTrue(
            "Terminal surface was not selected before the authoritative viewport capture; " +
                "conversationDetecting=${hasTag(TMUX_CONVERSATION_DETECTING_TAG)} " +
                "conversationPane=${hasTag(TMUX_CONVERSATION_PANE_TAG)} " +
                "terminalTab=${hasTag(TMUX_TERMINAL_TAB_TAG)}",
            false,
        )
    }

    private fun hasTag(tag: String): Boolean =
        compose.onAllNodesWithTag(tag, useUnmergedTree = true)
            .fetchSemanticsNodes()
            .isNotEmpty()

    /**
     * Read the session name currently shown in the top chrome's session
     * label ([TMUX_CONSOLIDATED_SESSION_LABEL_TAG]). Returns null when the
     * label is not present (e.g. mid-transition).
     */
    private fun readActiveSessionName(): String? {
        val nodes = compose.onAllNodesWithTag(
            TMUX_CONSOLIDATED_SESSION_LABEL_TAG,
            useUnmergedTree = true,
        ).fetchSemanticsNodes()
        val node = nodes.firstOrNull() ?: return null
        return node.config.getOrNull(SemanticsProperties.Text)
            ?.firstOrNull()
            ?.text
    }

    /**
     * Find the pager page index (1-based) whose card is currently ON-SCREEN.
     * The pager scrolls to the current session on open, so exactly one page
     * card occupies the visible viewport while the rest are composed
     * off-screen at ~zero width. We probe each candidate page tag's
     * `boundsInRoot` and return the index of the widest one — that is the
     * card the swipe gesture must target (swiping an off-screen ~0-width node
     * fails to generate a valid gesture). Defaults to 1.
     */
    private fun onScreenPageIndex(): Int {
        var bestIndex = 1
        var bestWidth = 0f
        for (index in 1..MAX_PAGER_PAGES_PROBE) {
            val nodes = compose.onAllNodesWithTag(
                "$TMUX_SESSION_PAGER_PAGE_TAG_PREFIX$index",
                useUnmergedTree = true,
            ).fetchSemanticsNodes()
            val node = nodes.firstOrNull() ?: continue
            val width = node.boundsInRoot.width
            if (width > bestWidth) {
                bestWidth = width
                bestIndex = index
            }
        }
        return bestIndex
    }

    /**
     * Wait until the top-chrome session label shows a session DIFFERENT from
     * [previous] (the pager has settled and the app has re-attached), then
     * return the newly-active session name.
     */
    private fun waitForActiveSessionChange(previous: String): String {
        var current: String? = null
        compose.waitUntil(timeoutMillis = pickerWaitMs) {
            current = readActiveSessionName()
            current != null && current != previous
        }
        return requireNotNull(current) {
            "active session label never changed away from '$previous'"
        }
    }

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ) {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        assertTrue(
            "expected visible terminal text for $label within ${timeoutMillis}ms; got:\n$last",
            satisfied && predicate(last),
        )
    }

    private fun visibleTerminalText(): String {
        var text = ""
        launchedActivity?.onActivity { activity ->
            text = activity.window.decorView
                .findTerminalView()
                ?.currentSession
                ?.emulator
                ?.screen
                ?.visibleScreenText
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

    /**
     * Issue #1994 — the capture is a BOUNDED PUMP, not a single shot.
     *
     * The nightly (run 30961154855, shard 2, exact main `1ddeadb3`) failed the
     * very FIRST capture with `magenta span=0 px … matchingPixels=0` — zero
     * matching pixels, before any switch, on a device whose emulator model
     * already held the marker.
     *
     * **Attribution, corrected in round 2 from that run's own per-class
     * logcat.** The bundle's
     * `logcat-…SessionSwipeSwitchE2eTest-swipeDownPagerSwitchesSessionAndActivatesCachedRuntimeOnReturn.txt`
     * shows the app RESUMED and in front at capture time:
     *
     * ```
     * 00:29:10.480 LifecycleMonitor: MainActivity … RESUMED
     * 00:29:10.772 VRI[NexusLauncherActivity]: visibilityChanged … newVisibility=false
     * 00:29:14.052 tmux-first-pane-output … bytes=8 elapsedMs=853
     * 00:29:14.551 ISSUE237_VIEWPORT …issue237-01-attached-session-a-viewport.png  <-- the capture
     * 00:29:15.390 VRI[MainActivity]: visibilityChanged … newVisibility=false      <-- teardown only
     * ```
     *
     * No ANR, no dialog, no foreign-window signature anywhere in that file, and
     * the shard's five `active_window_pkg=android` failures are at 00:04:44 and
     * 00:31:39–00:38:29 — nowhere near this capture. **A foreign window did NOT
     * own the display here.** The cause is the capture itself: it fired ~0.5 s
     * after the first 8 bytes of pane output, judged ONCE, after
     * `waitForIdleSync()` + a flat 150 ms sleep. That is a main-thread barrier,
     * not a composition/SurfaceFlinger barrier — under hosted swiftshader load a
     * late-but-correct paint is reported as a paint failure with no second look.
     *
     * The bounded pump is therefore the fix for THIS failure. The foreign-window
     * arm below is CLASS COVERAGE for the neighbouring capture world (a dim
     * scrim wipes the exact magenta match to zero while the band is still on the
     * screen), not the reproduction of the `1ddeadb3` red.
     *
     * So the capture now:
     *
     *  1. re-reads the geometry, re-takes the screenshot and re-runs the pixel
     *     oracle until it is satisfied or a CI-scaled deadline elapses (the
     *     pump's exit condition IS the load-bearing assertion — the AGENTS.md
     *     Shape-B bounded-pump rule); and
     *  2. attributes a still-missing band: if the app window did not own the
     *     display, the failure names that foreign owner
     *     ([FOREIGN_WINDOW_OVER_VIEWPORT_SIGNATURE]) rather than blaming the
     *     terminal for a frame that was never the app's.
     *
     * Attribution is NEVER amnesty: a missing band fails in both worlds (pinned
     * on the JVM by `MarkerBandCaptureVerdictTest`).
     */
    private fun captureViewport(name: String, expectedMarker: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val startedAt = SystemClock.elapsedRealtime()
        val deadline = startedAt + capturePaintBudgetMs
        var attempts = 0
        var lastVerdict: MarkerBandCaptureVerdict? = null
        val attemptTrail = StringBuilder()

        while (true) {
            instrumentation.waitForIdleSync()
            SystemClock.sleep(CAPTURE_SETTLE_MS)
            attempts++

            var viewportLeft = 0
            var viewportTop = 0
            var viewportWidth = 0
            var viewportHeight = 0
            var fontWidthPx = 0f
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView() ?: return@onActivity
                val location = IntArray(2)
                view.getLocationOnScreen(location)
                viewportLeft = location[0]
                viewportTop = location[1]
                viewportWidth = view.width
                viewportHeight = view.height
                fontWidthPx = view.mRenderer.fontWidth
            }
            val screen = instrumentation.uiAutomation.takeScreenshot()
            val left = viewportLeft.coerceIn(0, screen.width)
            val top = viewportTop.coerceIn(0, screen.height)
            val width = viewportWidth.coerceAtMost(screen.width - left)
            val height = viewportHeight.coerceAtMost(screen.height - top)
            check(width > 0 && height > 0) {
                "invalid terminal viewport crop left=$left top=$top width=$width height=$height " +
                    "screen=${screen.width}x${screen.height}"
            }
            // Crop the pixels the emulator actually displayed. Drawing
            // TerminalView into a fresh off-screen bitmap is not authoritative
            // because its dirty renderer cache describes the already-painted
            // device surface and can intentionally skip unchanged cells,
            // producing a partial evidence image.
            val bitmap = Bitmap.createBitmap(screen, left, top, width, height)
            screen.recycle()

            val band = measureMarkerBand(bitmap)
            val bandComplete = fontWidthPx > 0f &&
                band.spanPx >= requiredMarkerSpanPx(expectedMarker, fontWidthPx)
            val outOfBudget = SystemClock.elapsedRealtime() >= deadline
            // The window-owner diagnosis costs a full accessibility-tree fetch,
            // so it is computed ONLY on the terminal failure path (the same
            // laziness rule WindowFocusSignals documents). The per-attempt read
            // is the cheap `hasWindowFocus()` one.
            val focused = bandComplete || activityWindowFocused(requireNotNull(launchedActivity))
            val focusDiagnosis = when {
                focused -> "app_window_focused=true"
                bandComplete || !outOfBudget -> "app_window_focused=false"
                else -> "app_window_focused=false ${describeActiveWindow()}"
            }
            val verdict = evaluateMarkerBandCapture(
                marker = expectedMarker,
                artifactName = name,
                paintedSpanPx = band.spanPx,
                matchingPixels = band.matchingPixels,
                fontWidthPx = fontWidthPx,
                appWindowFocused = focused,
                activeWindowDescription = focusDiagnosis,
                attempts = attempts,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
            )
            lastVerdict = verdict
            attemptTrail.append("attempt=").append(attempts)
                .append(" elapsedMs=").append(SystemClock.elapsedRealtime() - startedAt)
                .append(" outcome=").append(verdict.outcome)
                .append(" span=").append(band.spanPx)
                .append(" matching=").append(band.matchingPixels)
                .append(' ').append(focusDiagnosis)
                .append('\n')

            if (verdict.painted || outOfBudget) {
                // Persist the LAST observed frame either way: a failure must
                // ship the evidence that produced it, not a stale earlier one.
                writeBitmap("$name-viewport", bitmap)
                var text = ""
                launchedActivity?.onActivity { activity ->
                    text = activity.window.decorView
                        .findTerminalView()
                        ?.currentSession
                        ?.emulator
                        ?.screen
                        ?.visibleScreenText
                        .orEmpty()
                }
                writeText("$name-visible-terminal.txt", text)
                writeText(
                    "$name-device-pixel-oracle.txt",
                    "marker=$expectedMarker\nfont_width_px=$fontWidthPx\n" +
                        "painted_magenta_span_px=${band.spanPx}\n" +
                        "required_span_px=${requiredMarkerSpanPx(expectedMarker, fontWidthPx)}\n" +
                        "matching_pixels=${band.matchingPixels}\n" +
                        "capture_attempts=$attempts\n" +
                        "capture_elapsed_ms=${SystemClock.elapsedRealtime() - startedAt}\n" +
                        "outcome=${verdict.outcome}\n",
                )
                writeText("$name-capture-attempts.txt", attemptTrail.toString())
                bitmap.recycle()
                break
            }
            bitmap.recycle()
            SystemClock.sleep(CAPTURE_RETRY_INTERVAL_MS)
        }

        val verdict = requireNotNull(lastVerdict)
        assertTrue(
            "${verdict.message}\ncapture attempt trail:\n$attemptTrail",
            verdict.painted,
        )
    }

    /** Widest horizontal run of the seeded true-colour magenta cell background. */
    private data class MarkerBand(val spanPx: Int, val matchingPixels: Int)

    /**
     * Device-pixel oracle for the complete marker. The seeded marker owns a
     * unique true-colour magenta cell background; the span of those pixels on
     * the UIAutomation screenshot therefore measures what the TerminalView
     * actually painted, independently of the emulator model text. A stale
     * #469 dirty clip that paints only `A2`/`B2` spans roughly two cells and
     * hard-fails against the full ten-cell marker width.
     *
     * The colour match stays EXACT (`r>=245, g<=10, b>=245`). Loosening it to
     * survive a dim scrim would also accept a dimmed/obscured frame as proof the
     * user can see the session — the opposite of what this oracle is for. The
     * dim case is handled by attribution, not by tolerance.
     */
    private fun measureMarkerBand(bitmap: Bitmap): MarkerBand {
        var minX = bitmap.width
        var maxX = -1
        var matchingPixels = 0
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = bitmap.getPixel(x, y)
                val red = android.graphics.Color.red(color)
                val green = android.graphics.Color.green(color)
                val blue = android.graphics.Color.blue(color)
                if (red >= 245 && green <= 10 && blue >= 245) {
                    minX = minOf(minX, x)
                    maxX = maxOf(maxX, x)
                    matchingPixels++
                }
            }
        }
        return MarkerBand(
            spanPx = if (maxX >= minX) maxX - minX + 1 else 0,
            matchingPixels = matchingPixels,
        )
    }

    /**
     * Full-device screenshot — diagnostic for terminal content, but the
     * authoritative way to see the Compose session-pager overlay, which is
     * NOT part of the TerminalView and therefore invisible to
     * [captureViewport].
     */
    private fun captureFullFrame(name: String): File {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(200)
        val bitmap = instrumentation.uiAutomation.takeScreenshot()
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write full-frame screenshot to ${file.absolutePath}"
            }
        }
        bitmap.recycle()
        println("ISSUE237_FULLFRAME ${file.absolutePath}")
        return file
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE237_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE237_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File {
        val file = artifactFile("timings.txt")
        file.writeText(timings.joinToString(separator = "\n", postfix = "\n"))
        println("ISSUE237_TIMINGS ${file.absolutePath}")
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
        println("ISSUE237_TIMING $line")
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
        const val LOG_TAG: String = "Issue237Swipe"
        const val DEVICE_DIR_NAME: String = "issue237-session-swipe"

        // Two same-host sessions are seeded so the pager always has at least
        // two pages even if the fixture were otherwise empty. SESSION_A is the
        // attach target whose unique A_MARKER content we track across the
        // forward/back swipe round-trip; SESSION_B guarantees a second page to
        // swipe onto (it need not be the page the swipe lands on — the shared
        // fixture may interleave its own sessions).
        const val SESSION_A: String = "issue237-session-a"
        const val SESSION_B: String = "issue237-session-b"
        const val A_MARKER: String = "A237-READY"
        const val B_MARKER: String = "B237-READY"
        const val PIXEL_MARKER_ANSI: String = "\\033[48;2;255;0;255m"
        const val PIXEL_MARKER_RESET: String = "\\033[0m"

        // Highest pager page tag index probed when locating the on-screen
        // card; comfortably above any realistic same-host session count on
        // the shared fixture.
        const val MAX_PAGER_PAGES_PROBE: Int = 24

        // The session-pager open threshold is 72.dp (`VerticalSwipeThreshold`
        // in TmuxSessionScreen). On the densest emulators (~3.5x) that is
        // ~252px. A cumulative downward travel of 8 × 80 = 640px clears it
        // with wide margin while staying a plausible finger drag from the
        // breadcrumb down across the terminal viewport.
        const val SWIPE_DOWN_STEPS: Int = 8
        const val SWIPE_DOWN_STEP_PX: Float = 80f

        // Horizontal pager fling on the current session's on-screen page card:
        // drag from the card's right edge to its left edge with a brisk
        // leftward velocity so the HorizontalPager settles forward exactly one
        // page (this velocity/duration was observed to advance the session
        // pager by one page on the swiftshader emulator). The edge inset keeps
        // the touch inside the card bounds.
        const val SWIPE_PAGER_EDGE_INSET_PX: Float = 20f
        const val SWIPE_PAGER_VELOCITY: Float = 2_000f
        const val SWIPE_PAGER_DURATION_MS: Long = 250L

        // Status labels rendered on the pager's current-session card by
        // `sessionSwitcherPages`: the placeholder while `Loading`, and
        // "current" once `Ready`. The transition from one to the other is the
        // picker-ready signal.
        const val LOADING_PLACEHOLDER_STATUS: String = "loading same-host sessions"
        const val READY_CURRENT_STATUS: String = "current"

        // Issue #1994 capture-pump cadence. The settle is the old flat wait kept
        // as the FIRST attempt's delay so a healthy device behaves exactly as
        // before; the retry interval paces the additional looks.
        const val CAPTURE_SETTLE_MS: Long = 150L
        const val CAPTURE_RETRY_INTERVAL_MS: Long = 250L

        /**
         * Issue #1994: how long the synthetic occlusion injected by
         * [transientlyOccludedSurfaceIsCapturedOnceItRepaints] stays up. It must
         * comfortably exceed the old single-shot 150 ms window (so the base
         * capture is deterministically red) while staying far inside the bounded
         * capture budget (so the fixed capture is deterministically green).
         */
        const val SYNTHETIC_OCCLUSION_MS: Long = 3_000L
    }
}
