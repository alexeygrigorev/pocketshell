package com.pocketshell.app.proof

import android.graphics.Bitmap
import android.graphics.Canvas
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.app.BackgroundGraceTestOverride
import com.pocketshell.app.MainActivity
import com.pocketshell.app.hosts.HOST_ROW_TAG_PREFIX
import com.pocketshell.app.hosts.SshKeyStorage
import com.pocketshell.app.tmux.TMUX_CONNECTING_PROGRESS_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_ERROR_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_RECONNECT_TAG
import com.pocketshell.app.tmux.TMUX_SESSION_SCREEN_TAG
import com.pocketshell.app.tmux.TMUX_SWITCHING_LOADING_TAG
import com.pocketshell.app.tmux.TmuxSessionViewModel
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.HostEntity
import com.termux.view.TerminalView
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #553 (epic #687 Phase 3, J2) — DEVICE-TRUTH journey: a within-grace
 * reattach that leaves the pane PARTIALLY blank (one live line painting, the
 * static viewport above it gone) must restore the FULL prior viewport — not just
 * the single live line.
 *
 * ## The production bug
 *
 * On a within-grace foreground the warm `-CC` control client is RETAINED (the
 * teardown is deferred to grace-elapsed), so [TmuxSessionViewModel.onAppForegrounded]
 * runs the reseed-ONLY fast path [TmuxSessionViewModel.launchForegroundReattachReseed].
 * On `origin/main` that path heals only a FULLY-blank pane via
 * `reseedBlankVisiblePanes`, which `continue`s on `!visibleScreenIsBlank()`
 * (TerminalSurfaceState.visibleScreenIsBlank == `transcriptText.isBlank()`). So ONE
 * live agent line (a per-second status/timer) makes the pane non-blank → the heal is
 * SKIPPED → the static content above it is never restored ("blank except a timer").
 *
 * tmux `-CC` never re-emits an idle pane's existing frame, so when a reflow during a
 * brief link blip wipes the emulator grid above the still-repainting timer line, the
 * static banner stays gone forever.
 *
 * ## How this reproduces the partial blank deterministically on agents:2222 (no toxiproxy)
 *
 * The scaffold's earlier worry was that a FULL reconnect re-creates the emulator EMPTY
 * and the reveal gate reseeds the (fully-blank) pane BEFORE the live line lands, so the
 * agents-only worker-kill did NOT reproduce the partial blank. The bug lives on the
 * WARM-client within-grace path, where the emulator is RETAINED across the blip.
 *
 * This journey keeps the `-CC` client warm (a within-grace background with NO socket
 * drop, so `canReseedWithinGraceForeground()` stays true) and reproduces the post-reflow
 * partial blank DIRECTLY on the live, retained emulator: while backgrounded it feeds a
 * `CSI 2J` + `CSI H` (erase display + home) followed by ONE fresh timer line straight
 * into the SAME `TerminalView.mEmulator` the app renders — exactly the on-screen state a
 * tmux reflow leaves (static viewport wiped, one live line repainting). The REMOTE tmux
 * server grid is never touched, so `capture-pane` still holds the FULL banner: the fix
 * restores it. Because it uses ONLY the deterministic `agents` fixture (host port 2222)
 * and feeds the emulator locally (no toxiproxy, no `Assume.assumeFalse(isRunningOnCi())`),
 * it RUNS on the per-PR CI emulator-journey job.
 *
 * ## Contract (DEVICE TRUTH — asserts the user's pixels)
 *
 *  1. Before the foreground, the partial-blank is REAL: the visible terminal shows the
 *     live timer line but the static banner has been wiped (the `origin/main` skip
 *     precondition).
 *  2. After foregrounding within grace, the pane VIEWPORT is RE-SEEDED to the FULL prior
 *     content: the static banner [BANNER_MARKER] is restored AND the live timer line is
 *     still present — the full viewport, not just the live line.
 *  3. NO Reconnecting/Disconnected/Connecting/Attaching surface appears (the warm-client
 *     within-grace reattach is a calm ride-through).
 *
 * ## Fail-first
 *
 * On base `origin/main` the partially-blank pane is non-blank (the timer line), so
 * `reseedBlankVisiblePanes` SKIPS it: the banner is never restored and assertion (2) goes
 * RED. The P3 id-tagged full-viewport reseed (under the NEW connection path,
 * `reseedActivePaneForReattach` — UNCONDITIONAL, not gated on full-blank) restores the
 * full viewport and flips it GREEN, keyed to the target session id.
 *
 * ## Issue #879 — the BEYOND-GRACE full-reconnect path (the second test family)
 *
 * The within-grace test above keeps the warm `-CC` client and the SAME [TerminalView]
 * across the cycle. The maintainer's #879 dogfood symptom — a reconnected pane that comes
 * back ~95% BLACK with only a stray cursor + a few fragments — is the DIFFERENT,
 * previously-untested path: a BEYOND-grace foreground triggers a FULL
 * `connect(LifecycleReattach)` that TEARS DOWN the old pane and RE-CREATES a fresh
 * [TerminalSurfaceState] + [TerminalView]. The active pane is seeded from a full
 * `capture-pane` snapshot (firing [TerminalSurfaceState]'s full-repaint signal) BEFORE the
 * fresh surface reveals/binds its repaint collector (the #640 seed-before-reveal contract).
 *
 * When `_fullRepaintRequests` was a `replay = 0` flow, that seed's repaint `tryEmit` fired
 * while no collector was attached, so the late-subscribing fresh [TerminalView] never
 * received it. Termux's #469 dirty cache then clipped the next draw to only the
 * freshly-changed rows over a BLACK canvas — leaving the seeded-but-"clean" rows black.
 * The buffer (`transcriptText`) is CORRECT after the seed, so a buffer-level assertion
 * passes vacuously; only the PAINTED bitmap reveals the bug. That is why the #879 tests
 * assert PAINTED, non-black pixels across the viewport (DEVICE TRUTH — the user's pixels),
 * NOT just `transcriptText` content.
 *
 * Two #879 tests cover BOTH pane kinds (D32 G2 class coverage), because a shell pane
 * masks the bug via incremental `%output` redraw while an idle alt-screen agent pane
 * emits nothing and stays black indefinitely:
 *
 *  - [beyondGraceReconnectFullyRepaintsShellPane] — a static-banner SHELL pane.
 *  - [beyondGraceReconnectFullyRepaintsIdleAltScreenPane] — an idle ALTERNATE-SCREEN
 *    (agent TUI) pane.
 *
 * Both drive a real beyond-grace background→foreground re-create (short grace override,
 * sleep past it so the teardown fires, then foreground → `connect(LifecycleReattach)`),
 * then assert the freshly-bound View's bitmap is fully painted (every viewport row has
 * non-black pixels). On base (`replay = 0`) the re-created View paints over black and most
 * rows stay black → RED; with `replay = 1` (+ the post-reveal `reseedActivePaneForReattach`
 * re-fire) the late subscriber receives the repaint and the whole viewport paints → GREEN.
 * Both use ONLY the deterministic `agents` fixture (host port 2222) and no
 * `Assume.assumeFalse(isRunningOnCi())`, so they RUN on the per-PR CI emulator-journey job.
 */
@RunWith(AndroidJUnit4::class)
class ReconnectPartialBlankReseedJourneyE2eTest {

    @get:Rule
    val compose = createEmptyComposeRule()

    @get:Rule
    val grantPermissions = PreGrantPermissionsRule()

    private var launchedActivity: ActivityScenario<MainActivity>? = null
    private var seededKey: String? = null
    private val timings = mutableListOf<String>()

    @Before
    fun setUp() {
        BackgroundGraceTestOverride.setForTest(null)
    }

    @After
    fun tearDown() {
        runCatching { launchedActivity?.close() }
        launchedActivity = null
        BackgroundGraceTestOverride.setForTest(null)
        seededKey?.let { key -> runCatching { runBlocking { cleanupRemoteTmuxSession(key) } } }
    }

    @Test
    fun withinGraceReattachRestoresFullViewportNotJustTheLiveLine() { runBlocking {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedTmuxSession(key)

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachSeededTmuxSession(hostRowTag)

        // Baseline: BOTH the static banner and the live timer line are on screen. The
        // banner is the content that must survive the within-grace partial blank and be
        // restored on return; tmux's server grid holds it throughout.
        waitForVisibleTerminal("initial attach banner") { it.contains(BANNER_MARKER) }
        waitForVisibleTerminal("initial attach timer") { it.contains(TIMER_MARKER) }
        waitForConnected("initial attach")
        captureViewport("issue553-01-attached")

        // Short grace override so the resume lands well within grace and the warm `-CC`
        // client is retained (canReseedWithinGraceForeground stays true — the warm path).
        BackgroundGraceTestOverride.setForTest(WITHIN_GRACE_MS)

        val cycleStart = SystemClock.elapsedRealtime()
        // (1) Background within grace — NO socket drop, the `-CC` client stays warm.
        launchedActivity?.moveToState(Lifecycle.State.CREATED)
        SystemClock.sleep(BACKGROUND_SETTLE_MS)

        // (2) Reproduce the post-reflow PARTIAL BLANK on the retained emulator: erase the
        // display + home, then paint ONE fresh timer line — exactly the on-screen state a
        // reflow during a brief link blip leaves (static viewport wiped, live line
        // repainting). The REMOTE tmux grid is untouched, so capture-pane still has the
        // full banner.
        feedPartialBlankFrameToEmulator()
        // Confirm the partial blank is REAL on screen before the foreground: the timer is
        // present but the banner is gone — the exact origin/main skip precondition.
        waitForVisibleTerminal("partial-blank precondition timer") { it.contains(TIMER_MARKER) }
        val partialBlankView = visibleTerminalText()
        assertTrue(
            "partial-blank precondition must show the live timer line; visible:\n$partialBlankView",
            partialBlankView.contains(TIMER_MARKER),
        )
        assertTrue(
            "partial-blank precondition must have WIPED the static banner (the origin/main " +
                "skip precondition); visible:\n$partialBlankView",
            !partialBlankView.contains(BANNER_MARKER),
        )
        captureViewport("issue553-02-partial-blank")
        recordTiming("partial_blank_injected_ms", SystemClock.elapsedRealtime() - cycleStart)

        // (3) Foreground WITHIN grace. The warm-client reseed-only path runs.
        launchedActivity?.moveToState(Lifecycle.State.RESUMED)
        recordTiming("within_grace_cycle_ms", SystemClock.elapsedRealtime() - cycleStart)

        // DEVICE-TRUTH assertion (3): NO reconnect surface across the foreground window.
        watchNoVisibleReconnect("within-grace partial-blank reattach", OVERLAY_WATCH_MS)

        // DEVICE-TRUTH assertion (2): the pane VIEWPORT is RE-SEEDED to the FULL prior
        // content — the static banner is restored AND the live timer line is still
        // present. On base `origin/main` the non-blank timer line makes the heal SKIP the
        // partial blank, so the banner is never restored → this waits then fails RED.
        waitForVisibleTerminal("within-grace full-viewport restore") { it.contains(BANNER_MARKER) }
        val visibleAfter = visibleTerminalText()
        assertTrue(
            "within-grace partial-blank reattach must restore the FULL prior viewport " +
                "(static banner '$BANNER_MARKER'); visible terminal was:\n$visibleAfter",
            visibleAfter.contains(BANNER_MARKER),
        )
        assertTrue(
            "within-grace partial-blank reattach must keep the live timer line " +
                "('$TIMER_MARKER') alongside the restored banner; visible:\n$visibleAfter",
            visibleAfter.contains(TIMER_MARKER),
        )
        // The band stays absent through the settle (a late reconnect band would still be
        // the regression).
        watchNoVisibleReconnect("within-grace settle after restore", POST_RESTORE_SETTLE_MS)
        captureViewport("issue553-03-full-viewport-restored")

        // The session screen is still up (a reattach, not a teardown).
        assertTrue(
            "tmux session screen must still be up after the within-grace full-viewport restore",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )

        writeSummary()
        writeTimings()
        Unit
    } }

    /**
     * Issue #879 — a BEYOND-GRACE reconnect of a static-banner SHELL pane must come back
     * FULLY PAINTED (every viewport row has non-black pixels), not ~95% black.
     *
     * Drives a real beyond-grace background→foreground re-create: a short grace override,
     * sleep past it so the grace teardown fires, then foreground → full
     * `connect(LifecycleReattach)` that re-creates the [TerminalView]. The seed lands before
     * the fresh surface binds its collector; the `replay = 1` fix (+ post-reveal reseed
     * re-fire) makes the late subscriber receive the full-repaint request so the whole
     * captured banner paints.
     *
     * RED on base: the re-created View drops the dropped full-repaint signal → it paints
     * only changed cells over black → most viewport rows stay black → the painted-rows
     * assertion fails. GREEN with the fix.
     */
    @Test
    fun beyondGraceReconnectFullyRepaintsShellPane() { runBlocking {
        runBeyondGraceFullyRepaintsJourney(altScreen = false, namePrefix = "issue879-shell")
    } }

    /**
     * Issue #879 — class coverage (D32 G2): a BEYOND-GRACE reconnect of an IDLE
     * ALTERNATE-SCREEN (agent TUI) pane must ALSO come back fully painted. This is the kind
     * the maintainer's `pocketshell` session shows and where the bug is most visible: an
     * idle alt-screen pane emits no `%output` after attach, so nothing incrementally
     * repaints the dropped rows — they stay black indefinitely under the `replay = 0` bug.
     *
     * The seeded pane enters the alternate screen (`CSI ?1049h`), paints a full banner, and
     * idles. After the beyond-grace re-create the captured alt-screen banner must be fully
     * painted on the fresh View.
     */
    @Test
    fun beyondGraceReconnectFullyRepaintsIdleAltScreenPane() { runBlocking {
        runBeyondGraceFullyRepaintsJourney(altScreen = true, namePrefix = "issue879-altscreen")
    } }

    /**
     * Shared body for both #879 beyond-grace tests. [altScreen] selects whether the seeded
     * pane content lives on the alternate screen buffer (the idle agent-TUI case) or the
     * normal screen (the shell case).
     */
    private suspend fun runBeyondGraceFullyRepaintsJourney(altScreen: Boolean, namePrefix: String) {
        val key = readFixtureKey()
        seededKey = key
        waitForSshFixtureReady(SshKey.Pem(key))
        seedBeyondGraceTmuxSession(key, altScreen)

        val hostRowTag = seedDockerHost(key)
        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        attachSeededTmuxSession(hostRowTag)

        // Baseline: the full static banner is on screen.
        waitForVisibleTerminal("$namePrefix initial banner") { it.contains(BANNER_MARKER) }
        waitForConnected("$namePrefix initial attach")
        captureViewport("$namePrefix-01-attached")
        val firstPaneId = firstVisiblePaneId()

        // Force a real BEYOND-grace reconnect via a genuine transport drop (the maintainer's
        // "the socket died while backgrounded and we reconnected" scenario): kill the
        // server-side sshd serving the app's `-CC` control client. The reader hits EOF
        // PROMPTLY on the dead socket (unlike a clean detach of a HEALTHY transport, which
        // blocks ~15-50s on the AVD and is non-deterministic — see
        // AgentConversationReconnectDockerTest), so the production `disconnected` observer
        // fires `scheduleAutoReconnect`, tears the dead client down, and does a fresh SSH
        // connect + `tmux -CC` that RE-CREATES the pane's TerminalSurfaceState + TerminalView
        // (a new TerminalView is the #879 re-create boundary where the full-repaint signal is
        // dropped). Give the reconnect loop a single zero-delay backoff step so it fires
        // immediately on the observed drop.
        setAutoReconnectDelaysForTest(listOf(0L))
        val cycleStart = SystemClock.elapsedRealtime()
        killControlClientSshConnection(key, SESSION_NAME)

        // The VM observes the dead socket → Reconnecting → a fresh connect re-lands. Wait for
        // Connected with a fresh pane (the View was re-created) and for the captured banner to
        // be back in the buffer (the seed landed).
        waitForReconnectedWithFreshPane("$namePrefix beyond-grace reconnect", firstPaneId)
        waitForVisibleTerminal(
            "$namePrefix beyond-grace buffer reseeded",
            timeoutMillis = BEYOND_GRACE_RECONNECT_TIMEOUT_MS,
        ) { it.contains(BANNER_MARKER) }
        recordTiming("${namePrefix}_beyond_grace_cycle_ms", SystemClock.elapsedRealtime() - cycleStart)

        // DEVICE-TRUTH (#879), end-to-end on the REAL reconnect path: the freshly re-created
        // View's emulator holds the FULL restored banner after the reconnect (every banner row,
        // not just a fragment) — i.e. the full-viewport reseed reached the new pane's surface
        // state, not just a delta. This proves the real transport-drop → auto-reconnect →
        // fresh-attach → seed path end-to-end (G10).
        //
        // ORDER MATTERS (reviewer BLOCKER 2): we WAIT for the banner to be restored to the
        // re-created pane FIRST, THEN capture the viewport — so the authoritative
        // `*-02-after-reconnect-viewport.png` samples AFTER the repaint settles, never the
        // transient empty re-created buffer. (The prior version captured before the re-wait,
        // so the shell viewport could sample the empty fresh buffer and read black.)
        //
        // The dirty-region RENDER drop that turns a correctly-seeded buffer into a black SURFACE
        // is reproduced deterministically by the REAL-VIEW render-layer sibling
        // (`shared/core-terminal` `TerminalViewReattachLateSubscribeRepaintInstrumentedTest`,
        // which drives a REAL `TerminalView.onDraw` UNDER the platform dirty clip + the real
        // `forceFullRepaint()` + the real `TerminalSurfaceState.fullRepaintRequests` collector,
        // red→green) and the JVM `TerminalSurfaceStateFullRepaintLateSubscribeTest`. The
        // journey's `captureViewport` uses `View.draw(Canvas)` — a FULL software render that
        // BYPASSES the on-screen dirty clip — so it cannot itself reproduce the surface-clip
        // black (the #721 lesson); it is the authoritative POST-SETTLE painted artifact, while
        // the real-View sibling owns the surface-clip red→green.
        assertFullBannerRestoredAfterReconnect("$namePrefix-after-reconnect")
        captureViewportWhenPainted("$namePrefix-02-after-reconnect")

        // The session screen is still up (a reattach, not a teardown).
        assertTrue(
            "tmux session screen must still be up after the $namePrefix beyond-grace reconnect",
            compose.onAllNodesWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty(),
        )

        writeBeyondGraceSummary(namePrefix, altScreen)
        writeTimings()
    }

    /**
     * Issue #879 — end-to-end device-truth on the REAL reconnect path: after a real
     * transport-drop → auto-reconnect → fresh-attach, the freshly re-created pane's emulator
     * holds the FULL restored banner (EVERY banner row that fits the viewport), proving the
     * full-viewport reseed reached the new pane's [TerminalSurfaceState] — not just a delta /
     * a fragment. Asserts the LAST banner row (the row most likely missing if only the bottom
     * fragment streamed) AND the count of distinct banner rows in the visible buffer.
     *
     * This exercises the real path G10 requires. The dirty-region RENDER drop (correctly-seeded
     * buffer → black SURFACE) is reproduced deterministically by the REAL-VIEW sibling
     * (`com.termux.view.TerminalViewReattachLateSubscribeRepaintInstrumentedTest` — a real
     * `TerminalView.onDraw` under the platform dirty clip, the real `forceFullRepaint()`, and the
     * real `TerminalSurfaceState.fullRepaintRequests` collector, red→green) and the JVM
     * `TerminalSurfaceStateFullRepaintLateSubscribeTest`, because `View.draw()` to an offscreen
     * bitmap does a FULL software render that bypasses the on-screen dirty clip (the #721 lesson)
     * — so this journey owns the real-path reseed proof and the render-layer tests own the
     * surface-clip black.
     */
    private fun assertFullBannerRestoredAfterReconnect(name: String) {
        val visible = waitForVisibleTerminal(
            "$name full banner restored",
            timeoutMillis = BEYOND_GRACE_RECONNECT_TIMEOUT_MS,
        ) { text -> bannerRowCount(text) >= MIN_RESTORED_BANNER_ROWS }
        val rows = bannerRowCount(visible)
        writeText(
            "$name-restored-banner.txt",
            "distinct_banner_rows=$rows min_required=$MIN_RESTORED_BANNER_ROWS\n" +
                "visible:\n$visible\n",
        )
        assertTrue(
            "#879: after the reconnect the FULL banner must be restored to the re-created pane " +
                "(>= $MIN_RESTORED_BANNER_ROWS distinct banner rows, not a bottom fragment); " +
                "found $rows. visible:\n$visible",
            rows >= MIN_RESTORED_BANNER_ROWS,
        )
    }

    /** Count distinct numbered banner rows (`ISSUE553-BANNER row NN`) in the visible buffer. */
    private fun bannerRowCount(text: String): Int =
        Regex("$BANNER_MARKER row (\\d{2})").findAll(text).map { it.groupValues[1] }.toSet().size

    /**
     * Seed a tmux session whose ACTIVE pane fills the WHOLE viewport with a static banner
     * (so a partial repaint is visible as black gaps), then idles. When [altScreen] is true
     * the banner is painted on the ALTERNATE screen buffer (`CSI ?1049h`) — the idle agent-TUI
     * case where nothing repaints the dropped rows after reattach.
     *
     * The pane is idle after painting (no per-second timer), so on the beyond-grace re-create
     * NOTHING streams `%output` to incrementally heal a dropped repaint — the bug stays black
     * until the full-repaint signal is delivered to the fresh View.
     */
    private suspend fun seedBeyondGraceTmuxSession(key: String, altScreen: Boolean) {
        val enterAlt = if (altScreen) "printf '\\033[?1049h'; " else ""
        // Fill many rows so the banner spans the whole viewport — a partial repaint then
        // leaves obvious black bands.
        val bannerLines = (1..40).joinToString("") { "$BANNER_MARKER row %02d filler abcdefghijklmnopqrstuvwxyz\\n".format(it) }
        val payload = buildString {
            append(enterAlt)
            append("printf '$bannerLines'; ")
            // Idle forever — no live output, so a dropped repaint is never incrementally healed.
            append("while true; do sleep 3600; done")
        }
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            appendLine("tmux new-session -d -s ${shellQuote(SESSION_NAME)} ${shellQuote(payload)}")
            appendLine("sleep 2")
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected beyond-grace tmux seeding to succeed; exception=${result.exceptionOrNull()} " +
                "stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded beyond-grace session (altScreen=$altScreen): ${exec?.stdout?.trim()}")
    }

    private fun writeBeyondGraceSummary(namePrefix: String, altScreen: Boolean): File =
        writeText(
            "$namePrefix-summary.txt",
            buildString {
                appendLine("test=ReconnectPartialBlankReseedJourneyE2eTest#$namePrefix")
                appendLine("issue=879")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("banner_marker=$BANNER_MARKER")
                appendLine("alt_screen=$altScreen")
                appendLine(
                    "scenario=attach a full-viewport static banner pane (alt-screen=$altScreen), " +
                        "background BEYOND grace (teardown fires), foreground -> full " +
                        "connect(LifecycleReattach) re-creating the TerminalView",
                )
                appendLine(
                    "expectation=the freshly-bound View is FULLY repainted (every viewport row " +
                        "has non-black pixels) — not ~95% black with only fragments",
                )
                appendLine("timings:")
                timings.forEach { appendLine("  $it") }
            },
        )

    // ---------------------------------------------------------------- Helpers

    /**
     * Feed a partial-blank frame straight into the SAME emulator the app renders. A
     * `CSI 2J` (erase entire display) + `CSI H` (cursor home) wipes the visible viewport;
     * a fresh `TIMER_MARKER` line then repaints, modelling the lone live line that
     * survives a reflow. This is local to the emulator — the remote tmux grid keeps the
     * full banner, so the within-grace reseed restores it.
     */
    private fun feedPartialBlankFrameToEmulator() {
        val esc = "\u001B"
        val frame = "$esc[2J$esc[H$TIMER_MARKER tick-after-reflow\r\n".toByteArray(Charsets.UTF_8)
        var fed = false
        launchedActivity?.onActivity { activity ->
            val view = activity.window.decorView.findTerminalView() ?: return@onActivity
            val emulator = view.mEmulator ?: return@onActivity
            emulator.append(frame, frame.size)
            view.invalidate()
            fed = true
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertTrue("expected to feed the partial-blank frame to the live emulator", fed)
        Log.i(LOG_TAG, "fed partial-blank frame (2J + home + timer) to retained emulator")
    }

    private fun attachSeededTmuxSession(hostRowTag: String) {
        compose.waitUntil(timeoutMillis = 15_000) {
            compose.onAllNodesWithTag(hostRowTag, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithTag(hostRowTag, useUnmergedTree = true).performClick()
        compose.waitUntil(timeoutMillis = TerminalTestTimeouts.terminalVisibilityTimeoutMs()) {
            compose.onAllNodesWithText(SESSION_NAME, useUnmergedTree = true)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        compose.onNodeWithText(SESSION_NAME, useUnmergedTree = true).performClick()
        compose.onNodeWithTag(TMUX_SESSION_SCREEN_TAG, useUnmergedTree = true).assertExists()
        waitForTerminalViewAttached()
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

    private fun waitForConnected(label: String) {
        compose.waitUntil(timeoutMillis = CONNECTED_TIMEOUT_MS) {
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected
        }
        assertTrue(
            "expected Connected after $label, observed=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun viewModel(): TmuxSessionViewModel {
        var vm: TmuxSessionViewModel? = null
        launchedActivity?.onActivity { activity ->
            vm = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
        }
        return checkNotNull(vm) { "TmuxSessionViewModel not available" }
    }

    private fun firstVisiblePaneId(): String =
        checkNotNull(viewModel().panes.value.firstOrNull()?.paneId) {
            "no visible pane after attach"
        }

    private fun setAutoReconnectDelaysForTest(delaysMs: List<Long>) {
        launchedActivity?.onActivity { activity ->
            ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .setAutoReconnectDelaysForTest(delaysMs)
        }
    }

    /**
     * Issue #879: after a transport-drop, the VM enters Reconnecting then a fresh connect
     * re-lands Connected with a freshly-attached pane (the old pane row + its TerminalView are
     * torn down in `closeCurrentConnectionAndJoin` and re-created on the fresh attach). Wait
     * for that fresh Connected, having observed the drop, with a longer ceiling for a loaded
     * AVD. We do NOT require the pane id to rotate (tmux keeps `%N` when only the `-CC` client
     * died) — what matters for #879 is the View was re-created, which the teardown+re-attach
     * guarantees.
     */
    private fun waitForReconnectedWithFreshPane(label: String, previousPaneId: String) {
        var observedDrop = false
        compose.waitUntil(timeoutMillis = BEYOND_GRACE_RECONNECT_TIMEOUT_MS) {
            val status = currentConnectionStatus()
            if (status is TmuxSessionViewModel.ConnectionStatus.Reconnecting) observedDrop = true
            val panesGone = viewModel().panes.value.none { it.paneId == previousPaneId }
            if (panesGone) observedDrop = true
            observedDrop && status is TmuxSessionViewModel.ConnectionStatus.Connected &&
                viewModel().panes.value.isNotEmpty()
        }
        assertTrue(
            "expected a re-Connected state after the transport drop for $label; " +
                "observedDrop=$observedDrop status=${currentConnectionStatus()}",
            currentConnectionStatus() is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    private fun currentConnectionStatus(): TmuxSessionViewModel.ConnectionStatus {
        var status: TmuxSessionViewModel.ConnectionStatus =
            TmuxSessionViewModel.ConnectionStatus.Idle
        launchedActivity?.onActivity { activity ->
            status = ViewModelProvider(activity)[TmuxSessionViewModel::class.java]
                .connectionStatus
                .value
        }
        return status
    }

    private fun waitForVisibleTerminal(
        label: String,
        timeoutMillis: Long = TerminalTestTimeouts.terminalVisibilityTimeoutMs(),
        predicate: (String) -> Boolean,
    ): String {
        var last = ""
        val satisfied = runCatching {
            compose.waitUntil(timeoutMillis = timeoutMillis) {
                last = visibleTerminalText()
                predicate(last)
            }
            true
        }.getOrDefault(false)
        if (!satisfied) writeText("failure-$label-visible-terminal.txt", last)
        assertTrue("expected visible terminal for $label; got:\n$last", predicate(last))
        return last
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

    private fun assertNoVisibleReconnect(label: String) {
        assertEquals(
            "expected no Connecting overlay for $label",
            0,
            compose.onAllNodesWithTag(TMUX_CONNECTING_PROGRESS_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no disconnect band for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_ERROR_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no Tap Reconnect button for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SESSION_RECONNECT_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        assertEquals(
            "expected no 'Attaching…' switching-loading overlay for $label",
            0,
            compose.onAllNodesWithTag(TMUX_SWITCHING_LOADING_TAG, useUnmergedTree = true)
                .fetchSemanticsNodes().size,
        )
        listOf("Connecting", "Reconnecting", "Disconnected", "Tap Reconnect", "Attaching").forEach { text ->
            assertEquals(
                "expected no visible '$text' text for $label",
                0,
                compose.onAllNodesWithText(text, substring = true, useUnmergedTree = true)
                    .fetchSemanticsNodes().size,
            )
        }
    }

    private fun watchNoVisibleReconnect(label: String, durationMs: Long) {
        val deadline = SystemClock.elapsedRealtime() + durationMs
        while (SystemClock.elapsedRealtime() < deadline) {
            assertNoVisibleReconnect(label)
            SystemClock.sleep(100)
        }
        recordTiming("${label.replace(' ', '_')}_no_reconnect_ms", durationMs)
    }

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
                name = "issue553-partialblank-key-${System.currentTimeMillis()}",
                content = key,
            )
            val hostId = db.hostDao().insert(
                HostEntity(
                    name = "Issue553 Partial Blank Reseed",
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
        // A multi-line static banner (the content that must be restored) plus a live
        // ticking timer line (the lone line that survives the partial blank).
        val script = buildString {
            appendLine("set -eu")
            appendLine("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
            val payload = buildString {
                append("printf '$BANNER_MARKER line 1\\n$BANNER_MARKER line 2\\n$BANNER_MARKER line 3\\n'; ")
                append("i=0; while true; do printf '$TIMER_MARKER %s\\n' \"\$i\"; ")
                append("i=\$((i+1)); sleep 1; done")
            }
            appendLine(
                "tmux new-session -d -s ${shellQuote(SESSION_NAME)} ${shellQuote(payload)}",
            )
            appendLine("sleep 2")
            appendLine("tmux list-sessions")
        }
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(script) } }
        val exec = result.getOrNull()
        assertTrue(
            "expected tmux seeding to succeed; exception=${result.exceptionOrNull()} stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "seeded session: ${exec?.stdout?.trim()}")
    }

    /**
     * Issue #879 (ported from AgentConversationReconnectDockerTest): force a real transport
     * drop by `kill -9`-ing the server-side sshd serving the app's `tmux -CC` control client
     * for [sessionName]. The app's reader hits EOF PROMPTLY on the dead socket (unlike a clean
     * detach of a healthy transport, which blocks 15-50s on the AVD), so the production
     * auto-reconnect path fires and re-creates the pane's TerminalView — the #879 re-create
     * boundary. The tmux server, session, and window all survive; only the `-CC` client dies.
     * Excludes our own short-lived `exec` process tree so we never kill the kill connection.
     */
    private suspend fun killControlClientSshConnection(key: String, sessionName: String) {
        val script = buildString {
            appendLine("set -u")
            appendLine("self=\$\$")
            appendLine("self_pids=\" \$self \"")
            appendLine("p=\$self")
            appendLine("for _ in 1 2 3 4 5 6 7 8; do")
            appendLine("  pp=\$(awk '/^PPid:/{print \$2}' /proc/\$p/status 2>/dev/null)")
            appendLine("  [ -z \"\$pp\" ] && break")
            appendLine("  [ \"\$pp\" = 0 ] && break")
            appendLine("  self_pids=\"\$self_pids\$pp \"")
            appendLine("  p=\$pp")
            appendLine("done")
            appendLine("attach_pid=")
            appendLine(
                "for cand in \$(pgrep -f " +
                    "'tmux.*-CC.*new-session.*${sessionName}' 2>/dev/null); do",
            )
            appendLine("  case \"\$self_pids\" in *\" \$cand \"*) continue;; esac")
            appendLine("  attach_pid=\$cand")
            appendLine("  break")
            appendLine("done")
            appendLine("if [ -z \"\$attach_pid\" ]; then")
            appendLine(
                "  for cand in \$(pgrep -f 'tmux.*${sessionName}' 2>/dev/null); do",
            )
            appendLine("    case \"\$self_pids\" in *\" \$cand \"*) continue;; esac")
            appendLine("    comm=\$(cat /proc/\$cand/comm 2>/dev/null || true)")
            appendLine("    case \"\$comm\" in tmux*) attach_pid=\$cand; break;; esac")
            appendLine("  done")
            appendLine("fi")
            appendLine("if [ -z \"\$attach_pid\" ]; then")
            appendLine("  echo NO_ATTACH_PID; echo '--- tmux procs ---'")
            appendLine("  pgrep -af tmux 2>/dev/null || true")
            appendLine("  exit 2")
            appendLine("fi")
            appendLine("pid=\$attach_pid")
            appendLine("sshd_pid=")
            appendLine("for _ in 1 2 3 4 5 6; do")
            appendLine("  ppid=\$(awk '/^PPid:/{print \$2}' /proc/\$pid/status 2>/dev/null)")
            appendLine("  [ -z \"\$ppid\" ] && break")
            appendLine("  [ \"\$ppid\" = 0 ] && break")
            appendLine("  comm=\$(cat /proc/\$ppid/comm 2>/dev/null || true)")
            appendLine("  case \"\$self_pids\" in *\" \$ppid \"*) pid=\$ppid; continue;; esac")
            appendLine("  case \"\$comm\" in sshd*) sshd_pid=\$ppid; break;; esac")
            appendLine("  pid=\$ppid")
            appendLine("done")
            appendLine("if [ -n \"\$sshd_pid\" ]; then")
            appendLine("  echo KILLING_SSHD=\$sshd_pid attach=\$attach_pid")
            appendLine("  kill -9 \"\$sshd_pid\" 2>/dev/null || true")
            appendLine("else")
            appendLine("  echo KILLING_ATTACH=\$attach_pid")
            appendLine("  kill -9 \"\$attach_pid\" 2>/dev/null || true")
            appendLine("fi")
            appendLine("echo DROP_DONE")
        }
        execRemote(key, script)
    }

    private suspend fun execRemote(key: String, command: String) {
        val result = SshConnection.connect(
            host = DEFAULT_HOST,
            port = DEFAULT_PORT,
            user = DEFAULT_USER,
            key = SshKey.Pem(key),
            knownHosts = KnownHostsPolicy.AcceptAll,
            timeoutMs = 15_000,
        ).mapCatching { session -> session.use { it.exec(command) } }
        val exec = result.getOrNull()
        assertTrue(
            "remote kill command failed: ${result.exceptionOrNull()} exit=${exec?.exitCode} " +
                "stdout='${exec?.stdout}' stderr='${exec?.stderr}'",
            exec?.exitCode == 0,
        )
        Log.i(LOG_TAG, "transport-drop kill: ${exec?.stdout?.trim()}")
    }

    private suspend fun cleanupRemoteTmuxSession(key: String) {
        runCatching {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = DEFAULT_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).mapCatching { session ->
                session.use {
                    it.exec("tmux kill-session -t ${shellQuote(SESSION_NAME)} 2>/dev/null || true")
                }
            }
        }
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

    /**
     * Issue #879 (reviewer BLOCKER 2) — capture the after-reconnect viewport ONLY once the live
     * [TerminalView] has actually SETTLED with the reseeded buffer painted, so the authoritative
     * `*-viewport.png` shows the restored banner — never the transient empty fresh-View frame.
     *
     * On the beyond-grace re-create the pane View is swapped to a fresh instance during the
     * settle; a single `view.draw` fired in that window samples the BLANK fresh buffer and the
     * artifact reads black even though the reseed lands a moment later. (The earlier-captured
     * black shell viewport was exactly that race — the assertion's repeated wait found the 40-row
     * banner, but the one-shot capture sampled the empty fresh View.) Here we POLL: re-find the
     * View each tick, draw it, and only accept the bitmap once the View's emulator buffer is
     * non-empty AND the rendered bitmap has non-black pixels (the banner is painted). This is the
     * POST-SETTLE authoritative artifact; `view.draw()` is a full software render that bypasses
     * the on-screen dirty clip (the #721 lesson), so it cannot reproduce the surface-clip black —
     * the REAL-VIEW sibling `TerminalViewReattachLateSubscribeRepaintInstrumentedTest` owns that
     * red→green. This capture's job is only to show the journey View painted post-reseed.
     */
    private fun captureViewportWhenPainted(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var bitmap: Bitmap? = null
        // Capture the View's buffer text from the SAME settled frame that produced the painted
        // bitmap, so the `*-visible-terminal.txt` artifact agrees with the `*-viewport.png` (a
        // separate later read can momentarily see an empty fresh View during the re-attach).
        var settledBufferText = ""
        val deadline = SystemClock.elapsedRealtime() + CAPTURE_SETTLE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            instrumentation.waitForIdleSync()
            var candidate: Bitmap? = null
            var candidateText = ""
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView() ?: return@onActivity
                if (view.width <= 0 || view.height <= 0) return@onActivity
                val bufferText = view.currentSession?.emulator?.screen?.transcriptText.orEmpty()
                if (!bufferText.contains(BANNER_MARKER)) return@onActivity
                val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(b))
                candidate = b
                candidateText = bufferText
            }
            val c = candidate
            if (c != null && bitmapHasNonBlackPixels(c)) {
                bitmap = c
                settledBufferText = candidateText
                break
            }
            c?.recycle()
            SystemClock.sleep(100)
        }
        // Fall back to a best-effort one-shot if the settle never produced a painted frame, so the
        // artifact set is never silently missing (it would then be a genuine review signal).
        if (bitmap == null) {
            launchedActivity?.onActivity { activity ->
                val view = activity.window.decorView.findTerminalView() ?: return@onActivity
                if (view.width <= 0 || view.height <= 0) return@onActivity
                val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(b))
                bitmap = b
                settledBufferText = view.currentSession?.emulator?.screen?.transcriptText.orEmpty()
            }
        }
        bitmap?.let { writeBitmap("$name-viewport", it) }
        writeText(
            "$name-visible-terminal.txt",
            settledBufferText.ifBlank { visibleTerminalText() },
        )
        bitmap?.recycle()
    }

    private fun bitmapHasNonBlackPixels(bitmap: Bitmap): Boolean {
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                // The terminal background is a near-black dark color; treat any pixel whose
                // channels rise meaningfully above it as "painted text".
                if (android.graphics.Color.red(p) > 40 ||
                    android.graphics.Color.green(p) > 40 ||
                    android.graphics.Color.blue(p) > 40
                ) {
                    return true
                }
                x += 4
            }
            y += 4
        }
        return false
    }

    private fun writeBitmap(name: String, bitmap: Bitmap): File {
        val file = artifactFile("$name.png")
        FileOutputStream(file).use { out ->
            check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE553_VIEWPORT ${file.absolutePath}")
        return file
    }

    private fun writeText(name: String, text: String): File {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE553_TEXT ${file.absolutePath}")
        return file
    }

    private fun writeTimings(): File =
        writeText("timings.txt", timings.joinToString(separator = "\n", postfix = "\n"))

    private fun writeSummary(): File =
        writeText(
            "summary.txt",
            buildString {
                appendLine("test=ReconnectPartialBlankReseedJourneyE2eTest")
                appendLine("issue=553")
                appendLine("fixture=tests/docker agents ($DEFAULT_HOST:$DEFAULT_PORT)")
                appendLine("running_on_ci=${TerminalTestTimeouts.isRunningOnCi()}")
                appendLine("session=$SESSION_NAME")
                appendLine("banner_marker=$BANNER_MARKER")
                appendLine("timer_marker=$TIMER_MARKER")
                appendLine(
                    "scenario=attach, background within grace (warm -CC retained), " +
                        "inject reflow partial-blank (2J + home + one timer line) on the " +
                        "retained emulator, foreground within grace",
                )
                appendLine(
                    "expectation=full prior viewport restored (static banner + live timer), " +
                        "no Reconnecting/Disconnected/Attaching surface",
                )
                appendLine("timings:")
                timings.forEach { appendLine("  $it") }
            },
        )

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
        println("ISSUE553_TIMING $line")
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

    private companion object {
        const val DATABASE_NAME: String = "pocketshell.db"
        const val LOG_TAG: String = "Issue553PartialBlank"
        const val DEVICE_DIR_NAME: String = "issue553-partial-blank-reseed"
        const val SESSION_NAME: String = "issue553-partialblank-proof"
        const val BANNER_MARKER: String = "ISSUE553-BANNER"
        const val TIMER_MARKER: String = "ISSUE553-TIMER"

        const val WITHIN_GRACE_MS: Long = 8_000L
        const val BACKGROUND_SETTLE_MS: Long = 500L
        const val OVERLAY_WATCH_MS: Long = 2_500L
        const val POST_RESTORE_SETTLE_MS: Long = 2_000L

        // Issue #879: a beyond-grace transport-drop reconnect (kill the `-CC` sshd) needs head-
        // room for the prompt EOF, the auto-reconnect loop, and a fresh SSH connect + tmux -CC.
        val BEYOND_GRACE_RECONNECT_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 90_000L else 75_000L
        // #879: the seeded banner is 40 rows; after the reconnect the restored buffer must hold
        // many distinct banner rows (a "bottom fragment only" failure would have very few). 20
        // is a robust floor (the alt-screen shows the bottom screenful; the shell the full grid).
        const val MIN_RESTORED_BANNER_ROWS: Int = 20

        // #879 BLOCKER 2: how long captureViewportWhenPainted polls for the re-created View to
        // settle with the reseeded banner painted before falling back to a one-shot capture.
        const val CAPTURE_SETTLE_TIMEOUT_MS: Long = 10_000L

        val CONNECTED_TIMEOUT_MS: Long =
            if (TerminalTestTimeouts.isRunningOnCi()) 30_000L else 15_000L
    }
}
