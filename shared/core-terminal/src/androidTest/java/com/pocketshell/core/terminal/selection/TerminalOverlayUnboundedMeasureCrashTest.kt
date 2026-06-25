package com.pocketshell.core.terminal.selection

import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Constraints
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.MutableSharedFlow
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * REGRESSION PROOF for the v0.4.17 RELEASE-BLOCKING Compose **measure** crash on
 * the terminal / session-picker journey (issue #958, CI run 28184338389,
 * reproduced 4/4 — NOT a flake).
 *
 * ## The reported defect
 *
 * The `MultiSessionSwitchJourneyE2eTest#backToPicker…` /
 * `#rapidMultiSessionSwitch…` / `BackThenOpenSecondSessionReusesWarmLease…`
 * journeys tore down with:
 *
 * ```
 * java.lang.IllegalStateException: Size(1070 x 2147483647) is out of range.
 *   Each dimension must be between 0 and 16777215.
 *   at androidx.compose.ui.node.LookaheadCapablePlaceable.layout(…)
 *   at …selection.SmartSelectionAffordanceOverlayKt
 *       $AgentPaneAffordanceOverlay$4$1.measure(SmartSelectionAffordanceOverlay.kt:350)
 *   at …ui.TerminalSurfaceKt$TerminalSurface$9$1.measure(TerminalSurface.kt:688)
 *   at …pager.PagerMeasureKt.measurePager(…)
 * ```
 *
 * i.e. the draw-only terminal affordance overlays
 * ([SmartSelectionAffordanceOverlay], [FilePathOverlay],
 * [AgentPaneAffordanceOverlay], [EngineCommandOverlay]) each laid out at the raw
 * `constraints.maxHeight.coerceAtLeast(0)` — fine under a normal bounded measure,
 * but the overlay sits inside the terminal pane, which sits inside the
 * `TmuxTerminalPager` ([androidx.compose.foundation.pager.Pager]). The pager /
 * lookahead runs intermittent measure passes with an **unbounded**
 * (`Constraints.Infinity` = `Int.MAX_VALUE`) maximum height. `coerceAtLeast(0)`
 * left `Int.MAX_VALUE` intact, so `layout(width, Int.MAX_VALUE)` threw the
 * out-of-range crash that brought the whole journey down. (The on-call's
 * banner-correlation was the trigger that produced the unbounded remeasure on
 * the back-to-picker path, not the crash site — the crash is in the terminal
 * overlay.)
 *
 * ## Why this is the load-bearing assertion (F2 / G6 / G10)
 *
 * This composes EACH of the four PRODUCTION overlays (no proxy/stand-in) inside a
 * [measureUnbounded] harness that hands it EXACTLY the crash constraint —
 * `Constraints(maxWidth = 1070, maxHeight = Infinity)`. On the unfixed overlay
 * this throws the exact `Size(1070 x Int.MAX_VALUE)` crash (the test fails / the
 * activity dies); with the fix every overlay lays out at a FINITE, bounded size.
 * Class coverage (G2): all four overlays, not only the one in the captured trace.
 */
@RunWith(AndroidJUnit4::class)
class TerminalOverlayUnboundedMeasureCrashTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /** The full device width the CI crash reported (`Size(1070 x …)`). */
    private val widthPx = 1070

    private val renders = MutableSharedFlow<Unit>()

    /**
     * Measures [content] at a fixed full width (1070px) with an **unbounded**
     * maximum height — the precise `Constraints(maxWidth = 1070, maxHeight =
     * Infinity)` the `TmuxTerminalPager`'s lookahead/measure pass hands the
     * terminal-overlay subtree on-device. Records the measured height of the
     * overlay so the test can hard-assert it came back FINITE (never the
     * `Int.MAX_VALUE` that overflowed `layout()` on the unfixed code).
     */
    @Composable
    private fun measureUnbounded(onMeasuredHeight: (Int) -> Unit, content: @Composable () -> Unit) {
        Layout(content = content) { measurables, _ ->
            val unbounded = Constraints(
                minWidth = widthPx,
                maxWidth = widthPx,
                minHeight = 0,
                maxHeight = Constraints.Infinity,
            )
            val placeables = measurables.map { it.measure(unbounded) }
            val height = placeables.maxOfOrNull { it.height } ?: 0
            onMeasuredHeight(height)
            layout(widthPx, height.coerceIn(0, 1_000)) {
                placeables.forEach { it.place(0, 0) }
            }
        }
    }

    private fun assertOverlayMeasuresFinite(name: String, overlay: @Composable () -> Unit) {
        var measuredHeight = -1
        compose.setContent {
            measureUnbounded(onMeasuredHeight = { measuredHeight = it }) {
                overlay()
            }
        }
        compose.waitForIdle()
        SystemClock.sleep(100)
        // The overlay laid out (no `Size(1070 x Int.MAX_VALUE)` crash tore down
        // the activity) AND it came back at a FINITE height — never the
        // unbounded `Int.MAX_VALUE` that overflowed `layout()` before the fix.
        assertTrue(
            "$name overlay measured height should be set (>=0), was $measuredHeight",
            measuredHeight in 0..16_777_215,
        )
    }

    @Test
    fun smartSelectionAffordanceOverlay_measuresFiniteUnderUnboundedHeight() {
        assertOverlayMeasuresFinite("SmartSelectionAffordance") {
            SmartSelectionAffordanceOverlay(
                view = null,
                renderRequests = renders,
                onMatchesChanged = {},
                modifier = Modifier,
            )
        }
    }

    @Test
    fun filePathOverlay_measuresFiniteUnderUnboundedHeight() {
        assertOverlayMeasuresFinite("FilePath") {
            FilePathOverlay(
                view = null,
                renderRequests = renders,
                onFilePathsChanged = {},
                modifier = Modifier,
            )
        }
    }

    @Test
    fun agentPaneAffordanceOverlay_measuresFiniteUnderUnboundedHeight() {
        // This is the exact overlay in the captured crash trace
        // (SmartSelectionAffordanceOverlay.kt:350, AgentPaneAffordanceOverlay$4$1).
        assertOverlayMeasuresFinite("AgentPaneAffordance") {
            AgentPaneAffordanceOverlay(
                view = null,
                renderRequests = renders,
                onFilePathsChanged = {},
                onUrlsChanged = {},
                modifier = Modifier,
            )
        }
    }

    @Test
    fun engineCommandOverlay_measuresFiniteUnderUnboundedHeight() {
        assertOverlayMeasuresFinite("EngineCommand") {
            EngineCommandOverlay(
                view = null,
                renderRequests = renders,
                knownCommands = setOf("git", "ls"),
                onEngineCommandsChanged = {},
                modifier = Modifier,
            )
        }
    }
}
