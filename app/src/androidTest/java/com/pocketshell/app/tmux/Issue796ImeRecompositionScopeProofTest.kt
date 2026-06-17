package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.layout.TmuxImeLayoutState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #796 (H4) — DETERMINISTIC recomposition-scoping proof.
 *
 * ## What this proves (the load-bearing H4 assertion)
 *
 * The H4 bug is a Compose *recomposition-scoping* defect: when the host-IME
 * inset is read with a DELEGATED read in a composable body
 * (`val imeBottomPx by ...`), the soft-keyboard show animation's BURST of
 * interpolated inset frames recomposes the whole enclosing scope — including the
 * render-critical terminal subtree — once PER FRAME, on the main thread. The fix
 * is [TmuxImeLayoutState]: hold the `State<Int>` inside the holder, expose only a
 * `derivedStateOf`-backed `isImeVisible` (boundary-gated) and a deferred
 * `panOffsetPx()` lambda (read at draw time), so the per-frame inset churn never
 * recomposes the subtree.
 *
 * "The render-critical subtree did NOT recompose per inset frame" is a property
 * of HOW the inset is read — the Compose invalidation scope — independent of the
 * SSH/tmux/render machinery. So this proof exercises it deterministically:
 *
 *  - The [ImeScopedSubtree] harness composes the PRODUCTION [TmuxImeLayoutState]
 *    EXACTLY as [TmuxSessionScreen] does (read `isImeVisible` in the body for the
 *    chrome flag, read `panOffsetPx()` inside a `graphicsLayer { ... }`) and
 *    counts re-executions of the HOSTING scope — the scope that, in
 *    `TmuxSessionScreen`, hosts the `HorizontalPager` terminal subtree. This is
 *    NOT a `*StandIn` for a heavy view: the symptom under test is the INVALIDATION
 *    SCOPE of the inset read (does an inset frame re-run the hosting scope?), not
 *    the terminal view's geometry/cost — counting the hosting scope's
 *    re-execution is the faithful probe (process.md F2: a stand-in is acceptable
 *    when the heavy view's cost is irrelevant to the symptom, and this comment
 *    states that explicitly). The harness composes the real production holder, not
 *    a copy.
 *  - The [LegacyDelegatedReadSubtree] harness reproduces the PRE-FIX structure
 *    (`val imeBottomPx by imeBottomPxState` delegated read + inline
 *    `isImeVisible = imeBottomPx > 0` + the pan offset read in composition), so
 *    the test is a real RED/GREEN guard: the same N-frame inset burst recomposes
 *    the legacy subtree O(N) and the production-holder subtree O(1). A test that
 *    only asserted the fixed path could pass vacuously; pinning the pre-fix
 *    O(N) here proves the assertion actually catches the regression.
 *
 * The inset is a [mutableIntStateOf] the test drives directly (the keyboard-show
 * animation's interpolated frames), so the measurement is fully deterministic and
 * environment-independent — no real IME, no `assumeTrue` / CI-skip on the
 * load-bearing assertion (process.md F3). The companion full-journey proof
 * [Issue796ImeRecompositionProofTest] composes the REAL [TmuxSessionScreen] over
 * Docker + a Codex `%output` burst and asserts the keyboard-up controls are not
 * occluded (the production-screen visual-regression acceptance).
 */
@RunWith(AndroidJUnit4::class)
class Issue796ImeRecompositionScopeProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The production fix: composing [TmuxImeLayoutState] as [TmuxSessionScreen]
     * does must NOT recompose the render-critical subtree on each inset frame.
     */
    @Test
    fun productionImeLayoutStateKeepsRenderSubtreeOffPerFrameInsetInvalidation() {
        val insetPx = mutableIntStateOf(0)
        val subtreeRecompositions = AtomicLong(0L)

        compose.setContent {
            ImeScopedSubtree(insetPxState = insetPx, onSubtreeRecompose = {
                subtreeRecompositions.incrementAndGet()
            })
        }
        compose.waitForIdle()

        val recompsAfterShow = driveKeyboardShowBurst(insetPx, subtreeRecompositions)
        println(
            "ISSUE796_H4_SCOPE production_holder ime_frames=$IME_FRAMES " +
                "subtree_recompositions=$recompsAfterShow ceiling=$MAX_SUBTREE_RECOMPOSITIONS",
        )

        // The render-critical subtree must recompose O(1) (a single show
        // transition at most), NOT O(N) (once per inset frame). With the
        // production holder the subtree reads NEITHER the raw inset NOR
        // `isImeVisible`, so it is never invalidated by the inset burst.
        assertTrue(
            "#796 (H4): the production TmuxImeLayoutState must keep the " +
                "render-critical subtree OFF the per-inset-frame invalidation path. " +
                "Over a $IME_FRAMES-frame keyboard-show inset burst the subtree " +
                "recomposed $recompsAfterShow times; the O(1) ceiling is " +
                "$MAX_SUBTREE_RECOMPOSITIONS.",
            recompsAfterShow <= MAX_SUBTREE_RECOMPOSITIONS,
        )
    }

    /**
     * Pins the PRE-FIX behaviour so the assertion above is a real guard, not a
     * vacuous pass: the delegated-read structure recomposes the subtree once per
     * inset frame (≈ N), far above the O(1) ceiling.
     */
    @Test
    fun legacyDelegatedReadRecomposesRenderSubtreePerInsetFrame() {
        val insetPx = mutableIntStateOf(0)
        val subtreeRecompositions = AtomicLong(0L)

        compose.setContent {
            LegacyDelegatedReadSubtree(insetPxState = insetPx, onSubtreeRecompose = {
                subtreeRecompositions.incrementAndGet()
            })
        }
        compose.waitForIdle()

        val recompsAfterShow = driveKeyboardShowBurst(insetPx, subtreeRecompositions)
        println(
            "ISSUE796_H4_SCOPE legacy_delegated_read ime_frames=$IME_FRAMES " +
                "subtree_recompositions=$recompsAfterShow floor=$MIN_LEGACY_RECOMPOSITIONS",
        )

        // The pre-fix delegated read invalidates the subtree on EVERY distinct
        // inset value, so an N-frame burst recomposes it ~N times — well above the
        // O(1) ceiling the fix achieves. This proves the production assertion above
        // is not vacuous (the regression is genuinely detectable).
        assertTrue(
            "#796 (H4) guard: the PRE-FIX delegated `by` read must recompose the " +
                "render subtree per inset frame (the regression this fix removes). " +
                "Over a $IME_FRAMES-frame burst the legacy subtree recomposed only " +
                "$recompsAfterShow times — the test would not catch the regression. " +
                "Expected >= $MIN_LEGACY_RECOMPOSITIONS.",
            recompsAfterShow >= MIN_LEGACY_RECOMPOSITIONS,
        )
    }

    // --------------------------------------------------------------- harnesses

    /**
     * Reset the counter, then drive a keyboard-SHOW animation: [IME_FRAMES]
     * interpolated inset frames from 0 → [IME_HEIGHT_PX]. Returns the subtree
     * recomposition count attributable to the burst (the count is reset to 0 just
     * before, after the initial composition settles).
     */
    private fun driveKeyboardShowBurst(
        insetPx: androidx.compose.runtime.MutableIntState,
        subtreeRecompositions: AtomicLong,
    ): Long {
        // Settle the initial composition, then reset so only burst-driven
        // recompositions count.
        compose.runOnIdle { insetPx.intValue = 0 }
        compose.waitForIdle()
        subtreeRecompositions.set(0L)

        for (frame in 1..IME_FRAMES) {
            val px = (IME_HEIGHT_PX.toLong() * frame / IME_FRAMES).toInt()
            // Each frame is a DISTINCT inset value (the first crosses 0 →
            // positive). waitForIdle after each so the recomposition (if any) is
            // flushed and counted before the next frame — deterministic, no
            // Choreographer race.
            compose.runOnIdle { insetPx.intValue = px }
            compose.waitForIdle()
        }
        return subtreeRecompositions.get()
    }

    /**
     * The PRODUCTION read structure: composes [TmuxImeLayoutState] exactly as
     * [TmuxSessionScreen] does — `isImeVisible` read in the body (chrome flag),
     * `panOffsetPx()` read deferred in a `graphicsLayer`. The counted leaf is the
     * render-critical subtree under test.
     */
    @Composable
    private fun ImeScopedSubtree(
        insetPxState: State<Int>,
        onSubtreeRecompose: () -> Unit,
    ) {
        // Production holder, fed the test-controlled inset state. This is the exact
        // object TmuxSessionScreen builds (rememberTmuxImeLayoutState wraps it).
        val imeLayout = remember(insetPxState) {
            TmuxImeLayoutState(imeBottomPxState = insetPxState, navBarBottomPx = 0)
        }
        // Read `isImeVisible` in the body, as TmuxSessionScreen does for the chrome
        // flag — boundary-gated, so the burst's positive frames don't invalidate
        // this scope.
        @Suppress("UNUSED_VARIABLE")
        val chromeCompressed = imeLayout.isImeVisible

        // Count THIS scope's re-execution — this is the scope that, in
        // TmuxSessionScreen, hosts the HorizontalPager terminal subtree. The H4
        // property is "does an inset frame re-run this hosting scope?" (the cost
        // the research spike flagged), so the counter is inline here, not in a
        // separately-skippable child group.
        onSubtreeRecompose()
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // Pan offset read DEFERRED inside the graphicsLayer lambda — the
                    // inset frames re-run only this GPU block, never recompose this
                    // hosting scope.
                    .graphicsLayer { translationY = imeLayout.panOffsetPx() },
            )
        }
    }

    /**
     * The PRE-FIX read structure: a DELEGATED `by` read of the raw inset plus an
     * inline `isImeVisible = imeBottomPx > 0` and a pan offset COMPUTED IN
     * COMPOSITION — the exact shape that subscribed the whole scope to every inset
     * frame. The counted leaf lives in the same scope, so it recomposes per frame.
     */
    @Composable
    private fun LegacyDelegatedReadSubtree(
        insetPxState: State<Int>,
        onSubtreeRecompose: () -> Unit,
    ) {
        // Pre-fix: delegated read of the raw inset in the body — subscribes THIS
        // scope to every inset value.
        val imeBottomPx by insetPxState
        @Suppress("UNUSED_VARIABLE")
        val isImeVisible = imeBottomPx > 0
        // Pre-fix: pan offset computed in composition (read in the body), so the
        // body re-runs on every inset value.
        val panOffsetPx = -imeBottomPx.toFloat()

        // Same inline counter as the production harness — counts THIS hosting
        // scope's re-execution. Because this scope reads the raw inset, every
        // distinct inset frame re-runs it → O(N).
        onSubtreeRecompose()
        Column(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { translationY = panOffsetPx },
            )
        }
    }

    private companion object {
        const val IME_FRAMES = 24
        const val IME_HEIGHT_PX = 800

        // O(1) ceiling: the production holder must recompose the render subtree at
        // most a small constant (a single show transition) across the N-frame
        // burst. With the holder the subtree reads no inset state at all, so this
        // is 0 in practice; 2 leaves slack for an initial settling frame.
        const val MAX_SUBTREE_RECOMPOSITIONS = 2L

        // The pre-fix delegated read must recompose the subtree ~once per distinct
        // inset frame. Require a healthy floor (most of the N frames) so the guard
        // genuinely proves the regression is detectable.
        const val MIN_LEGACY_RECOMPOSITIONS = (IME_FRAMES - 4).toLong()
    }
}
