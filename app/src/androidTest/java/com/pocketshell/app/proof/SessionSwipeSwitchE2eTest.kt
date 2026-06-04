package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
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
import com.pocketshell.app.tmux.TMUX_FULL_BREADCRUMB_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_PAGER_OVERLAY_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_PAGER_PAGE_TAG_PREFIX
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
 * deterministic Docker `agents` fixture and pins four contracts:
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
 *  3. **Previous tmux client detached** (#235 / #215). After swiping away
 *     from SESSION_A, `tmux list-clients -t SESSION_A` reports zero clients
 *     — the previous `-CC` control client was `detachCleanly()`'d, so no
 *     orphan lingers to hit the size-lock issue.
 *  4. **The swipe debounces.** The pager's settle path can emit spurious
 *     `settledPage` values (the spike's flagged risk); if the overlay acted
 *     on them the logical tmux connect counter would advance more than once
 *     for the single swipe. We assert exactly one logical tmux connect fires
 *     for that swipe, so a spurious double-fire is caught.
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
    fun swipeDownPagerSwitchesSessionReusingSshAndDetaching() = runBlocking {
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
        captureViewport("issue237-01-attached-session-a")

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
        // replaces SESSION_A's, the previous tmux client detaches, and the SSH
        // transport is reused — proving two distinct sessions' content render
        // via the swipe gesture (SESSION_A's marker before, the adjacent
        // session's shell after).
        val switchAt = SystemClock.elapsedRealtime()
        val forwardSession = swipeSessionPagerForwardOnce(
            previousSession = SESSION_A,
            captureOpenAs = "issue237-02-pager-open-fullframe",
        )
        captureFullFrame("issue237-03-swiped-forward-to-$forwardSession-fullframe")
        assertTrue(
            "a forward session-pager swipe must switch AWAY from $SESSION_A to a " +
                "different same-host session; landed on '$forwardSession'",
            forwardSession != SESSION_A,
        )
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

        // (c) Previous tmux client detached (#235 / #215). After swiping away
        // from SESSION_A its previous `-CC` control client must have been
        // `detachCleanly()`'d, so `tmux list-clients -t SESSION_A` drops to
        // zero. We poll because the `detach-client` round-trip races the new
        // attach; the count must settle to zero within the budget.
        val orphanClients = pollListClientsUntilZero(key, SESSION_A)
        writeText("issue237-clients-on-a-after-forward-swipe.txt", "clients=$orphanClients\n")
        assertEquals(
            "after swiping away from $SESSION_A its previous tmux -CC client " +
                "must be detached (no orphan lingering for the size-lock issue, " +
                "#235/#215); tmux list-clients -t $SESSION_A reported $orphanClients " +
                "clients",
            0,
            orphanClients,
        )

        // ---- (5) The adjacent session renders its OWN content (not a blank
        // screen, and not SESSION_A's). Capture its visible terminal so the
        // reviewer can confirm two distinct sessions' content render across
        // the swipe (SESSION_A's `A-READY` in issue237-01-*, the adjacent
        // session's shell prompt here).
        compose.waitUntil(timeoutMillis = pickerWaitMs) {
            visibleTerminalText().isNotBlank()
        }
        assertTrue(
            "the session swiped onto ('$forwardSession') must render its own " +
                "terminal content, not a blank screen",
            visibleTerminalText().isNotBlank(),
        )
        captureViewport("issue237-04-adjacent-session-content")

        writeTimings()
        writeText(
            "issue237-counters.txt",
            buildString {
                appendLine("clients_on_A_while_attached=$clientsOnAWhileAttached")
                appendLine("forward_swipe_session=$forwardSession")
                appendLine("ssh_handshakes_during_forward=${handshakeAfter - handshakeBefore}")
                appendLine("tmux_connects_during_forward=$tmuxConnectsForward")
                appendLine("clients_on_A_after_forward=$orphanClients")
                appendLine("forward_swipe_switch_ms=$forwardSwitchMs")
            },
        )

        Unit
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
                    shellQuote("printf '$A_MARKER\\n'; exec sh"),
            )
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_B)} " +
                    shellQuote("printf '$B_MARKER\\n'; exec sh"),
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
     * Poll `tmux list-clients -t <session>` until it reports zero clients
     * or the budget elapses. Returns the final observed count.
     */
    private suspend fun pollListClientsUntilZero(key: String, sessionName: String): Int {
        val deadline = SystemClock.elapsedRealtime() +
            if (TerminalTestTimeouts.isRunningOnCi()) 60_000L else 20_000L
        var count = listClientsCount(key, sessionName)
        while (count != 0 && SystemClock.elapsedRealtime() < deadline) {
            SystemClock.sleep(500)
            count = listClientsCount(key, sessionName)
        }
        return count
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
    }
}
