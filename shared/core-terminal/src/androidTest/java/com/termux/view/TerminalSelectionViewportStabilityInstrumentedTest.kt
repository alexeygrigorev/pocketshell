package com.termux.view

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.terminal.ui.pinTerminalToBottom
import com.pocketshell.core.terminal.ui.testArtifactsRoot
import com.pocketshell.testsupport.captureViewToBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #2154 — the terminal viewport must not JUMP while the user is selecting
 * text.
 *
 * ## The maintainer's report (on-device, 2026-08-15)
 *
 * > "when I'm in the terminal I try to select text — I hold my finger and I
 * > select a part, I start selecting the rest of the thing I want to copy-paste,
 * > and then my terminal starts jumping. It's even difficult to sometimes just
 * > scroll it up."
 *
 * ## Why the vendored guard did NOT cover it
 *
 * [TerminalView.onScreenUpdated] already refuses to scroll for OUTPUT while a
 * selection is live (`isSelectingText()` → `skipScrolling = true`), and that
 * guard is correct. The jumping came from PocketShell's OWN viewport resets,
 * none of which consulted the selection:
 *
 *  - `pinTerminalToBottom` (#184's IME pin) called `setTopRow(0)` FIRST and only
 *    then `onScreenUpdated()`, so the vendored guard ran after the damage. And
 *    the gesture that starts a selection calls `TerminalView.requestFocus()`,
 *    which can raise the soft IME — the very event that fires the pin.
 *  - [TerminalView.updateSize] reset `mTopRow = 0` on ANY row/column change, and
 *    a selection drag is surrounded by layout shifts (floating action mode,
 *    bottom chrome, IME inset).
 *
 * ## What each test pins (D32-G2 class coverage — every app-layer mover)
 *
 * | test | reset path | RED on base |
 * |---|---|---|
 * | [imeShowPinDoesNotMoveViewportOrDropSelectionWhileSelecting] | the #184 IME pin | `topRow` snapped from -6 to 0 |
 * | [layoutSizeChangeDoesNotSnapViewportToBottomWhileSelecting] | `onSizeChanged` → `updateSize` | `topRow` snapped from -6 to 0 |
 * | [modelReseedWithFullRepaintHoldsViewportAndSelection] | capture-pane model reseed + `forceFullRepaint` | (green on base — characterization guard) |
 * | [outputBurstWhileScrolledBackDoesNotYankViewportToBottom] | live `%output` burst | (green on base — pins the vendored guard) |
 * | [selectionLifecycleIsPublishedOntoTerminalSurfaceState] | the `copyModeChanged` seam | the flag never left `false` |
 * | [imePinStillSnapsToBottomWhenNoSelectionIsActive] | the #184 pin's POSITIVE path | (green on base — the counterweight) |
 * | [imePinWorksAgainAfterTheSelectionEnds] | refuse-then-honour, no latch | (green on base — the counterweight) |
 *
 * The last two rows are the counterweight to the guard this issue adds. Every other
 * test that touches `pinTerminalToBottom` asserts only its REFUSAL, so hardening the
 * new early return into `if (true) return false` — buying selection stability by
 * silently killing #184 — would pass all of them. These two redden it, and nothing
 * else does.
 *
 * The reseed and burst tests are green on base by design: the issue asks to CONFIRM the reseed
 * / repaint / output paths do not move the viewport or drop the selection, and a
 * confirmation is only durable if a test holds it. Their load-bearing character
 * is proven by mutation (deleting the `isSelectingText()` branch in
 * `onScreenUpdated`, or resetting `mTopRow` in `forceFullRepaint`, reddens them).
 *
 * ## Fidelity (F2 — the REAL reported state, no proxy)
 *
 * Everything runs on the production path: the real [TerminalSurface] composable
 * hosted on a real Activity, the real vendored [TerminalView] it mounts, a REAL
 * long-press gesture through `dispatchTouchEvent` (the same
 * `GestureAndScaleRecognizer` → `startTextSelectionMode` route a finger takes),
 * the real transcript scrolled BACK into history, and the production
 * `pinTerminalToBottom(rootView)` entry point called on the Activity's real
 * decor view. Assertions are on the geometry the user sees — `TerminalView.topRow`
 * and the selection's on-screen row — never on a seam having fired.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSelectionViewportStabilityInstrumentedTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    private val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var state: TerminalSurfaceState

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val activity = composeTestRule.activity
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
            @Suppress("DEPRECATION")
            activity.window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    android.view.WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                    android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
            )
            activity.window.decorView.requestFocus()
        }
        try {
            val ua = instrumentation.uiAutomation
            ua.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
            ua.executeShellCommand("wm dismiss-keyguard").close()
        } catch (_: Throwable) {
            // UiAutomation is not available on every SDK level; the window flags above
            // are enough for the assertions in this file (none need clipboard focus).
        }
    }

    @After
    fun tearDown() {
        producerScope.cancel()
    }

    // -----------------------------------------------------------------------
    // The four class members.
    // -----------------------------------------------------------------------

    /**
     * THE reproduction (G10/D33). The user is scrolled back in the transcript with
     * a live selection; the #184 IME pin fires (as it does when the selection
     * gesture's own `requestFocus()` raises the soft keyboard).
     *
     * RED on base: `pinTerminalToBottom` set `topRow = 0` — the transcript snapped
     * to the bottom and the selection scrolled off screen mid-drag.
     * GREEN: the pin is refused; `topRow`, the selection anchors, the selected
     * text and the selection's on-screen row are all unchanged.
     */
    @Test
    fun imeShowPinDoesNotMoveViewportOrDropSelectionWhileSelecting() {
        val scenario = startSelectionScrolledBack("issue2154-ime-pin")

        val pinned = booleanArrayOf(false)
        onMain {
            // The production entry point, on the Activity's real decor view — exactly
            // what TmuxSessionScreen passes as `composeRootView`.
            pinned[0] = pinTerminalToBottom(composeTestRule.activity.window.decorView)
        }
        composeTestRule.waitForIdle()

        val after = readSelectionSnapshot(scenario.view)
        captureViewport(scenario.view, "issue2154-ime-pin-after")
        writeArtifact(
            "issue2154-ime-pin-summary.txt",
            "before=${scenario.before}\nafter=$after\npinReturned=${pinned[0]}\n",
        )

        assertTrue(
            "the selection must still be live after the IME pin — the user is still dragging",
            after.selecting,
        )
        assertEquals(
            "ISSUE #2154: the #184 IME pin must NOT move the viewport while a text selection " +
                "is live. topRow ${scenario.before.topRow} -> ${after.topRow} means the transcript " +
                "jumped out from under the user's finger (0 = snapped to the bottom).",
            scenario.before.topRow,
            after.topRow,
        )
        assertEquals(
            "the selection anchors must be untouched by the pin",
            scenario.before.selectors.toList(),
            after.selectors.toList(),
        )
        assertEquals(
            "the selected text must be untouched by the pin",
            scenario.before.selectedText,
            after.selectedText,
        )
        assertEquals(
            "the selection must stay on the same ON-SCREEN row (selY1 - topRow) — that is what " +
                "'the terminal jumped' means to the user",
            scenario.before.selectionScreenRow,
            after.selectionScreenRow,
        )
        assertEquals(
            "pinTerminalToBottom must report the refusal to its caller",
            false,
            pinned[0],
        )
    }

    /**
     * The layout/size-change member of the class. A selection drag is surrounded by
     * layout shifts (the floating action mode, the bottom chrome, the IME inset);
     * each re-runs `onSizeChanged` → [TerminalView.updateSize].
     *
     * RED on base: `updateSize` reset `mTopRow = 0` on any row change, snapping the
     * transcript to the bottom.
     * GREEN: the scroll position is preserved (clamped into the reflowed history)
     * and the selection is still live and still on screen.
     */
    @Test
    fun layoutSizeChangeDoesNotSnapViewportToBottomWhileSelecting() {
        val scenario = startSelectionScrolledBack("issue2154-resize")
        val view = scenario.view

        val rowsBefore = intArrayOf(0)
        val rowsAfter = intArrayOf(0)
        onMain {
            rowsBefore[0] = view.currentSession.emulator.mRows
            // The REAL resize entry point: View.layout -> setFrame -> onSizeChanged ->
            // updateSize(). Shrink by three text rows, the scale of a chip row / action
            // mode appearing under a selection.
            val shrinkPx = (3 * view.mRenderer.fontLineSpacing)
            view.layout(view.left, view.top, view.right, view.bottom - shrinkPx)
            rowsAfter[0] = view.currentSession.emulator.mRows
        }

        val after = readSelectionSnapshot(view)
        captureViewport(view, "issue2154-resize-after")
        writeArtifact(
            "issue2154-resize-summary.txt",
            "before=${scenario.before}\nafter=$after\n" +
                "emulatorRows ${rowsBefore[0]} -> ${rowsAfter[0]}\n",
        )

        assertNotEquals(
            "precondition: the layout change must actually change the emulator row count, " +
                "otherwise updateSize short-circuits and this test proves nothing (G3)",
            rowsBefore[0],
            rowsAfter[0],
        )
        assertTrue(
            "the selection must survive a resize — the user is still dragging",
            after.selecting,
        )
        assertEquals(
            "ISSUE #2154: TerminalView.updateSize must NOT reset the viewport to the bottom " +
                "while a text selection is live. topRow ${scenario.before.topRow} -> " +
                "${after.topRow} (0 = snapped to the bottom).",
            scenario.before.topRow,
            after.topRow,
        )
        assertEquals(
            "the selection must stay on the same ON-SCREEN row across the resize",
            scenario.before.selectionScreenRow,
            after.selectionScreenRow,
        )
    }

    /**
     * The model-reseed / full-repaint member. Production's tmux reattach + render
     * heal apply a `capture-pane` snapshot through
     * [TerminalSurfaceState.appendRemoteOutput], which also fires the
     * `fullRepaintRequests` signal the surface turns into
     * [TerminalView.forceFullRepaint].
     *
     * The issue asks to CONFIRM that path neither moves the viewport nor drops the
     * selection. It does not today — and this pins it so it cannot start to.
     */
    @Test
    fun modelReseedWithFullRepaintHoldsViewportAndSelection() {
        val scenario = startSelectionScrolledBack("issue2154-reseed")
        val view = scenario.view

        val rows = intArrayOf(0)
        onMain { rows[0] = view.currentSession.emulator.mRows }
        // A screenful-sized reseed shaped exactly like tmux `capture-pane` replay:
        // cursor home + erase display, then one screen of content. Sized to the
        // screen so it repaints without scrolling, as a real reseed does.
        val reseed = buildString {
            append("\u001B[H\u001B[2J")
            for (i in 0 until (rows[0] - 1).coerceAtLeast(1)) {
                append("RESEED %03d capture-pane snapshot line\r\n".format(i))
            }
        }
        state.appendRemoteOutput(reseed.toByteArray(Charsets.US_ASCII))
        // Bounded + HARD-failing: the snapshot must actually be in the emulator before
        // we judge the viewport, or the assertions below would pass against a screen the
        // reseed never touched (G3).
        awaitOrFail("the capture-pane reseed must reach the emulator") {
            visibleTerminalText(view).contains("RESEED 000")
        }
        composeTestRule.waitForIdle()

        val after = readSelectionSnapshot(view)
        captureViewport(view, "issue2154-reseed-after")
        writeArtifact(
            "issue2154-reseed-summary.txt",
            "before=${scenario.before}\nafter=$after\nreseedBytes=${reseed.length}\n",
        )

        assertTrue(
            "ISSUE #2154: a capture-pane model reseed + full repaint must NOT drop the user's " +
                "live selection",
            after.selecting,
        )
        assertEquals(
            "ISSUE #2154: a capture-pane model reseed + full repaint must NOT move the viewport " +
                "while a text selection is live. topRow ${scenario.before.topRow} -> ${after.topRow}",
            scenario.before.topRow,
            after.topRow,
        )
        assertEquals(
            "the reseed must not move the selection anchors",
            scenario.before.selectors.toList(),
            after.selectors.toList(),
        )
    }

    /**
     * The live-output member — the maintainer's "it's even difficult to sometimes
     * just scroll it up". While a selection is live and the user is scrolled back,
     * an output burst must keep the SAME content under the selection instead of
     * yanking the viewport to the bottom.
     *
     * The vendored `onScreenUpdated` guard is what provides this; the test pins it
     * (deleting the `isSelectingText()` branch reddens it) so no future app-layer
     * change can quietly reintroduce the yank.
     */
    @Test
    fun outputBurstWhileScrolledBackDoesNotYankViewportToBottom() {
        val scenario = startSelectionScrolledBack("issue2154-burst")
        val view = scenario.view

        for (i in 0 until BURST_LINES) {
            state.appendRemoteOutput("BURST %03d streaming output line\r\n".format(i).toByteArray(Charsets.US_ASCII))
        }
        // Bounded + HARD-failing: every burst line must have reached the emulator (and
        // therefore driven the repaint path) before we judge the viewport.
        awaitOrFail("the whole output burst must reach the emulator") {
            transcriptText(view).contains("BURST %03d".format(BURST_LINES - 1))
        }
        composeTestRule.waitForIdle()

        val after = readSelectionSnapshot(view)
        captureViewport(view, "issue2154-burst-after")
        writeArtifact(
            "issue2154-burst-summary.txt",
            "before=${scenario.before}\nafter=$after\nburstLines=$BURST_LINES\n",
        )

        assertTrue(
            "ISSUE #2154: an output burst must not end the user's selection",
            after.selecting,
        )
        assertTrue(
            "ISSUE #2154: an output burst must not yank the viewport back to the bottom while a " +
                "selection is live. topRow ${scenario.before.topRow} -> ${after.topRow} " +
                "(0 = bottom); the viewport must ride the scroll so the SAME content stays visible.",
            after.topRow < 0 && after.topRow <= scenario.before.topRow,
        )
        assertEquals(
            "the selected TEXT must be unchanged after the burst — the anchors ride the scroll " +
                "with the content the user picked",
            scenario.before.selectedText,
            after.selectedText,
        )
        assertEquals(
            "the selection must stay on the same ON-SCREEN row through the burst",
            scenario.before.selectionScreenRow,
            after.selectionScreenRow,
        )
    }

    /**
     * The seam itself, end-to-end on the production composable: a real long-press
     * must publish through [com.pocketshell.core.terminal.ui.PocketShellTerminalViewClient.copyModeChanged]
     * onto [TerminalSurfaceState.textSelectionActive], and ending the selection must
     * release it again.
     *
     * RED on base: the override was `= Unit`, so the flag never left `false` and no
     * app-layer code could defer to a live selection.
     */
    @Test
    fun selectionLifecycleIsPublishedOntoTerminalSurfaceState() {
        val scenario = startSelectionScrolledBack("issue2154-seam")
        val view = scenario.view

        assertTrue(
            "ISSUE #2154: starting a real selection must publish through the production " +
                "copyModeChanged wiring onto TerminalSurfaceState.textSelectionActive — that " +
                "override was `= Unit`, so nothing outside the vendored view could know a " +
                "selection was live",
            scenario.before.textSelectionActiveOnState,
        )

        onMain { view.stopTextSelectionMode() }
        composeTestRule.waitForIdle()
        val after = readSelectionSnapshot(view)
        writeArtifact(
            "issue2154-seam-summary.txt",
            "before=${scenario.before}\nafter=$after\n",
        )

        assertEquals(
            "precondition: stopTextSelectionMode must actually end the selection",
            false,
            after.selecting,
        )
        assertEquals(
            "ISSUE #2154: ending the selection must publish the falling edge too — otherwise the " +
                "#184 IME pin would stay dead for the rest of the session",
            false,
            after.textSelectionActiveOnState,
        )
    }

    // -----------------------------------------------------------------------
    // The #184 POSITIVE path — the guard must not buy selection stability by
    // killing the behaviour it guards.
    // -----------------------------------------------------------------------

    /**
     * #184's original job, unchanged: the user raised the soft keyboard because they
     * committed to typing at the prompt, so the transcript snaps to the bottom and
     * the cursor row is on screen again.
     *
     * This is the counterweight to
     * [imeShowPinDoesNotMoveViewportOrDropSelectionWhileSelecting]. That test asserts
     * only the REFUSAL, so hardening the new early return to `if (true) return false`
     * — protecting the selection by silently killing #184 — would survive it. This
     * test is what reddens that mutant: no selection is live, so the pin MUST fire,
     * report `true`, and put `topRow` back at 0.
     */
    @Test
    fun imePinStillSnapsToBottomWhenNoSelectionIsActive() {
        val view = composeScrolledBack()

        val before = readSelectionSnapshot(view)
        captureViewport(view, "issue2154-pin-no-selection-before")
        // HARD preconditions — a pin from an already-bottomed viewport, or one taken
        // while a stray selection is live, would make the assertion below vacuous.
        check(!before.selecting) { "precondition: no selection may be live for the #184 positive path" }
        check(before.topRow == SCROLLED_BACK_ROWS) {
            "precondition: the viewport must be scrolled BACK before the pin, topRow=${before.topRow}"
        }

        val pinned = booleanArrayOf(false)
        onMain {
            pinned[0] = pinTerminalToBottom(composeTestRule.activity.window.decorView)
        }
        composeTestRule.waitForIdle()

        val after = readSelectionSnapshot(view)
        captureViewport(view, "issue2154-pin-no-selection-after")
        writeArtifact(
            "issue2154-pin-no-selection-summary.txt",
            "before=$before\nafter=$after\npinReturned=${pinned[0]}\n",
        )

        assertEquals(
            "ISSUE #2154 must NOT break #184: with no selection live the IME pin must still " +
                "snap the transcript to the bottom so the cursor row is visible. " +
                "topRow ${before.topRow} -> ${after.topRow}, expected 0.",
            0,
            after.topRow,
        )
        assertEquals(
            "pinTerminalToBottom must report that it pinned",
            true,
            pinned[0],
        )
    }

    /**
     * The guard must be a live read of the selection, not a latch. A user selects,
     * copies, dismisses the selection, scrolls back, then raises the keyboard again —
     * #184 must work exactly as before. The falling edge of `copyModeChanged` exists
     * for precisely this.
     *
     * Both directions are asserted in ONE journey so a mutation cannot satisfy one by
     * breaking the other: refused while selecting, honoured once the selection ends.
     */
    @Test
    fun imePinWorksAgainAfterTheSelectionEnds() {
        val scenario = startSelectionScrolledBack("issue2154-pin-after-selection")
        val view = scenario.view

        val refused = booleanArrayOf(true)
        onMain {
            refused[0] = pinTerminalToBottom(composeTestRule.activity.window.decorView)
        }
        composeTestRule.waitForIdle()
        val whileSelecting = readSelectionSnapshot(view)

        assertEquals(
            "precondition (the refusal half): the pin must be refused while the selection is live",
            false,
            refused[0],
        )
        assertEquals(
            "precondition (the refusal half): the refused pin must not have moved the viewport",
            scenario.before.topRow,
            whileSelecting.topRow,
        )

        // The user is done: the selection ends and they scroll back again.
        onMain { view.stopTextSelectionMode() }
        composeTestRule.waitForIdle()
        val released = readSelectionSnapshot(view)
        check(!released.selecting) { "precondition: stopTextSelectionMode must end the selection" }
        check(!released.textSelectionActiveOnState) {
            "precondition: the falling edge must reach TerminalSurfaceState"
        }
        settleScrolledBack(view)

        val pinned = booleanArrayOf(false)
        onMain {
            pinned[0] = pinTerminalToBottom(composeTestRule.activity.window.decorView)
        }
        composeTestRule.waitForIdle()

        val after = readSelectionSnapshot(view)
        captureViewport(view, "issue2154-pin-after-selection-after")
        writeArtifact(
            "issue2154-pin-after-selection-summary.txt",
            "before=${scenario.before}\nwhileSelecting=$whileSelecting refused=${refused[0]}\n" +
                "released=$released\nafter=$after\npinReturned=${pinned[0]}\n",
        )

        assertEquals(
            "ISSUE #2154: the selection guard must not LATCH — once the selection ends, the #184 " +
                "IME pin must work again. topRow ${released.topRow} -> ${after.topRow}, expected 0.",
            0,
            after.topRow,
        )
        assertEquals(
            "pinTerminalToBottom must report that it pinned once the selection ended",
            true,
            pinned[0],
        )
    }

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private class SelectionSnapshot(
        val topRow: Int,
        val selecting: Boolean,
        val selectors: IntArray,
        val selectedText: String,
        /** `selY1 - topRow`: which VISIBLE row the selection starts on. */
        val selectionScreenRow: Int,
        val textSelectionActiveOnState: Boolean,
    ) {
        override fun toString(): String =
            "topRow=$topRow selecting=$selecting selectors=${selectors.toList()} " +
                "screenRow=$selectionScreenRow stateFlag=$textSelectionActiveOnState " +
                "selectedText=${selectedText.trim().take(60)}"
    }

    private class Scenario(val view: TerminalView, val before: SelectionSnapshot)

    /**
     * Compose the production [TerminalSurface], fill the transcript and scroll the
     * user BACK into history — the state the report starts from, with NO selection
     * yet. The #184-positive tests stop here; the selection tests go on to
     * [longPressToSelect].
     */
    private fun composeScrolledBack(): TerminalView {
        state = TerminalSurfaceState()
        state.attachExternalProducer(
            scope = producerScope,
            stdout = MutableSharedFlow(extraBufferCapacity = 8),
            remoteStdin = null,
        )

        composeTestRule.setContent {
            TerminalSurface(state = state, modifier = Modifier)
        }
        composeTestRule.waitForIdle()
        val view = waitForTerminalView()

        // Fill the transcript so there IS scrollback to be scrolled back into.
        for (i in 0 until TRANSCRIPT_LINES) {
            state.appendRemoteOutput("LINE %03d selectable-token-$i tail\r\n".format(i).toByteArray(Charsets.US_ASCII))
        }
        awaitOrFail("the transcript fill must reach the emulator") {
            transcriptText(view).contains("LINE %03d".format(TRANSCRIPT_LINES - 1))
        }
        composeTestRule.waitForIdle()

        // The user scrolls up. This is the state the report is about: "it's even
        // difficult to sometimes just scroll it up".
        settleScrolledBack(view)
        return view
    }

    /**
     * Compose the production [TerminalSurface], fill the transcript, scroll the user
     * BACK into history, and start a real long-press text selection there.
     */
    private fun startSelectionScrolledBack(artifactPrefix: String): Scenario {
        val view = composeScrolledBack()

        longPressToSelect(view)

        val before = readSelectionSnapshot(view)
        captureViewport(view, "$artifactPrefix-before")
        // HARD preconditions — without them every assertion below is vacuous (G3).
        check(before.selecting) { "precondition: the long-press must start a real selection" }
        check(before.topRow == SCROLLED_BACK_ROWS) {
            "precondition: the selection must start while scrolled BACK, topRow=${before.topRow}"
        }
        check(before.selectors[0] >= 0) {
            "precondition: the selection must have real anchors, got ${before.selectors.toList()}"
        }
        return Scenario(view, before)
    }

    /**
     * Put the user where the report starts: scrolled BACK in the transcript, and
     * STAYING there.
     *
     * Driven through the vendored view's REAL scroll path (`doScroll` — what the
     * gesture recogniser's `onScroll` calls), so the viewport lands where a finger
     * would put it and clamps to the history that actually exists.
     *
     * The retry loop is not decoration. Until a selection exists, `onScreenUpdated`
     * legitimately snaps the viewport back to the bottom, and the coalesced repaint
     * that the transcript fill queued can land AFTER the scroll. So the exit
     * condition is the real one — the viewport is at the target AND still there
     * after a quiet window wider than the render-coalescer frame. Bounded, and it
     * HARD-FAILS rather than proceeding from an unscrolled viewport (which would
     * make every assertion downstream vacuous).
     */
    private fun settleScrolledBack(view: TerminalView) {
        val deadline = SystemClock.uptimeMillis() + SETTLE_TIMEOUT_MS
        var lastTopRow = 0
        var transcriptRows = 0
        while (SystemClock.uptimeMillis() < deadline) {
            val now = SystemClock.uptimeMillis()
            val scrollEvent = MotionEvent.obtain(now, now, MotionEvent.ACTION_MOVE, 1f, 1f, 0)
            onMain {
                try {
                    val delta = SCROLLED_BACK_ROWS - view.topRow
                    if (delta != 0) view.doScroll(scrollEvent, delta)
                } finally {
                    scrollEvent.recycle()
                }
            }
            composeTestRule.waitForIdle()
            SystemClock.sleep(QUIET_WINDOW_MS)
            composeTestRule.waitForIdle()
            onMain {
                lastTopRow = view.topRow
                transcriptRows = view.currentSession.emulator.screen.activeTranscriptRows
            }
            if (lastTopRow == SCROLLED_BACK_ROWS) return
        }
        throw AssertionError(
            "precondition: the transcript must settle scrolled back to $SCROLLED_BACK_ROWS " +
                "within ${SETTLE_TIMEOUT_MS}ms, last topRow=$lastTopRow " +
                "(activeTranscriptRows=$transcriptRows)",
        )
    }

    /** Drive a REAL long-press on a middle visible row, exactly as a finger does. */
    private fun longPressToSelect(view: TerminalView) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val press = floatArrayOf(0f, 0f)
        onMain {
            val renderer = view.mRenderer
            // A column inside the "selectable-token" word and a row in the middle of
            // the visible viewport.
            press[0] = 12.5f * renderer.fontWidth
            press[1] = renderer.fontLineSpacingAndAscent +
                (view.currentSession.emulator.mRows / 2f) * renderer.fontLineSpacing
        }

        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, press[0], press[1], 0)
        onMain {
            try {
                view.dispatchTouchEvent(down)
            } finally {
                down.recycle()
            }
        }
        // Hold past ViewConfiguration.getLongPressTimeout() (500 ms) on the TEST
        // thread so the detector's pending Runnable can fire on Main.
        SystemClock.sleep(750)
        val upTime = SystemClock.uptimeMillis()
        val up = MotionEvent.obtain(downTime, upTime, MotionEvent.ACTION_UP, press[0], press[1], 0)
        onMain {
            try {
                view.dispatchTouchEvent(up)
            } finally {
                up.recycle()
            }
        }
        composeTestRule.waitForIdle()

        val deadline = SystemClock.uptimeMillis() + SELECTION_TIMEOUT_MS
        var selecting = false
        while (SystemClock.uptimeMillis() < deadline && !selecting) {
            onMain { selecting = view.isSelectingText }
            if (!selecting) SystemClock.sleep(25)
        }
        assertTrue(
            "the long-press gesture must put the real TerminalView into selection mode — the " +
                "whole reported scenario is a selection drag",
            selecting,
        )
        // Past the controller's 300 ms show/hide guard, so nothing we do next is
        // silently swallowed as a too-fast hide.
        SystemClock.sleep(350)
    }

    private fun readSelectionSnapshot(view: TerminalView): SelectionSnapshot {
        var snapshot: SelectionSnapshot? = null
        onMain {
            val selectors = IntArray(4)
            view.getTextSelectionCursorController().getSelectors(selectors)
            snapshot = SelectionSnapshot(
                topRow = view.topRow,
                selecting = view.isSelectingText,
                selectors = selectors,
                selectedText = view.selectedText.orEmpty(),
                selectionScreenRow = selectors[0] - view.topRow,
                textSelectionActiveOnState = state.isTextSelectionActive(),
            )
        }
        return requireNotNull(snapshot)
    }

    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    /**
     * Poll [predicate] to a generous wall-clock deadline and HARD-FAIL if it never
     * holds. The exit condition is the load-bearing assertion — never a bare sleep
     * before a judgement (the repo's TIMING1 rule).
     */
    private fun awaitOrFail(what: String, predicate: () -> Boolean) {
        val deadline = SystemClock.uptimeMillis() + SETTLE_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < deadline) {
            composeTestRule.waitForIdle()
            if (predicate()) return
            SystemClock.sleep(25)
        }
        throw AssertionError("timed out after ${SETTLE_TIMEOUT_MS}ms waiting: $what")
    }

    private fun transcriptText(view: TerminalView): String {
        var text = ""
        onMain { text = view.currentSession?.emulator?.screen?.transcriptText.orEmpty() }
        return text
    }

    private fun waitForTerminalView(): TerminalView {
        val deadline = SystemClock.uptimeMillis() + TERMINAL_VIEW_TIMEOUT_MS
        var found: TerminalView? = null
        while (SystemClock.uptimeMillis() < deadline && found == null) {
            onMain {
                found = composeTestRule.activity.window.decorView.findTerminalView()
            }
            if (found == null) {
                composeTestRule.waitForIdle()
                SystemClock.sleep(50)
            }
        }
        return requireNotNull(found) { "the production TerminalSurface never mounted a TerminalView" }
    }

    private fun View.findTerminalView(): TerminalView? {
        if (this is TerminalView) return this
        if (this !is ViewGroup) return null
        for (i in 0 until childCount) {
            val hit = getChildAt(i).findTerminalView()
            if (hit != null) return hit
        }
        return null
    }

    // -----------------------------------------------------------------------
    // Artifacts (authoritative terminal-viewport evidence for the reviewer)
    // -----------------------------------------------------------------------

    /**
     * Artifacts go under `additional_test_output/terminal-lab` on the external
     * media root: AGP/UTP pulls `additional_test_output` into
     * `build/outputs/connected_android_test_additional_output/` BEFORE it
     * uninstalls the test APK, which is what makes the authoritative
     * `*-viewport.png` / `*-visible-terminal.txt` survive the run (the media dir
     * itself is deleted with the package). Same mechanism the app module's
     * durable-state exports use.
     */
    private fun artifactFile(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(testArtifactsRoot(context), "additional_test_output/terminal-lab")
            .apply { mkdirs() }
        return File(dir, name)
    }

    private fun visibleTerminalText(view: TerminalView): String {
        var text = ""
        onMain {
            val emulator = view.currentSession?.emulator
            text = if (emulator == null) {
                ""
            } else {
                val top = view.topRow
                val rows = emulator.mRows
                (top until top + rows).joinToString("\n") { row ->
                    emulator.screen.getSelectedText(0, row, emulator.mColumns - 1, row)
                }
            }
        }
        return text
    }

    private fun captureViewport(view: TerminalView, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        var bitmap: Bitmap? = null
        onMain {
            bitmap = captureViewToBitmap(view, name)
        }
        val captured = checkNotNull(bitmap) {
            "main thread did not produce viewport bitmap '$name' (#2206)"
        }
        val file = artifactFile("$name-viewport.png")
        FileOutputStream(file).use { out ->
            check(captured.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                "failed to write bitmap to ${file.absolutePath}"
            }
        }
        println("ISSUE2154_VIEWPORT ${file.absolutePath}")
        captured.recycle()
        artifactFile("$name-visible-terminal.txt").writeText(visibleTerminalText(view))
    }

    private fun writeArtifact(name: String, text: String) {
        artifactFile(name).writeText(text)
        println("ISSUE2154_ARTIFACT $name")
        for (line in text.trim().lines()) android.util.Log.i(LOG_TAG, line)
    }

    private companion object {
        const val LOG_TAG = "Issue2154Selection"

        /** Lines pushed into the transcript so there is real scrollback. */
        const val TRANSCRIPT_LINES = 120

        /** How far back the user has scrolled when the selection starts. */
        const val SCROLLED_BACK_ROWS = -6

        /** Live output lines streamed while the selection is held. */
        const val BURST_LINES = 30

        const val TERMINAL_VIEW_TIMEOUT_MS = 20_000L
        const val SELECTION_TIMEOUT_MS = 5_000L

        /** Generous wall-clock ceiling for a bounded settle poll on a contended CI AVD. */
        const val SETTLE_TIMEOUT_MS = 30_000L

        /** Quiet window wider than the render coalescer's frame budget. */
        const val QUIET_WINDOW_MS = 250L
    }
}
