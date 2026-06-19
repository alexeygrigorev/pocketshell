package com.pocketshell.app.tmux

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.terminal.ui.TerminalKeyboardMode
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #796 (H3) — DETERMINISTIC recomposition-scoping proof for the
 * **composer-open → terminal-relayout collision** the maintainer pinpointed.
 *
 * ## The bug this reproduces (the maintainer's exact trigger, v0.4.6)
 *
 * > "I figured out when exactly Codex freezes: I see it redrawing and it's fine —
 * > but the moment I start using the Prompt Composer to actually type something,
 * > that's when it starts freezing."
 *
 * Opening the Prompt Composer flips `showMicSheet` in the [TmuxSessionScreen]
 * body — read in the body ROOT group — so the whole body re-executes. On
 * `origin/main` (HEAD `ecdfe896`, which already includes the H4 fix `aff7ac45`)
 * the terminal `HorizontalPager` was INLINE in that body and its `TerminalSurface`
 * callbacks were allocated FRESH on each body recomposition. Fresh lambda
 * arguments make `TerminalSurface` un-skippable, so the composer-open body
 * recomposition dragged the heavy terminal subtree (`AndroidView` update + the
 * per-render viewport URL / file-path / engine-command scanners — ALL main-thread)
 * through a recomposition. Stacked on an in-flight Codex `%output` burst, the main
 * thread blocks past the ANR threshold. `aff7ac45` (H4) only decoupled the
 * IME-INSET frame burst; it did NOT stop the `showMicSheet` body recomposition
 * from re-running the inline pager.
 *
 * ## The fix this proof guards (Compose recomposition scoping)
 *
 * [TmuxTerminalPager] hoists the `HorizontalPager` of `TerminalSurface`s into its
 * OWN restart group, fed STABLE inputs (the data classes + `remember`ed lambdas
 * built once in the body). A child composable with unchanged inputs is SKIPPED
 * under Kotlin-2.x strong skipping — so toggling `showMicSheet` (composer open) in
 * the parent body re-runs the body root group but SKIPS the pager: ZERO
 * main-thread terminal recomposition work while the composer opens over a bursting
 * pane.
 *
 * ## How this proves it (real production composable, no stand-in — process.md F2)
 *
 * "The terminal subtree did NOT recompose when the composer opened" is a property
 * of HOW the pager is invoked — the Compose invalidation/skip scope — independent
 * of the SSH/tmux machinery. So this proof exercises it deterministically:
 *
 *  - [ProductionStableSubtree] composes the REAL production [TmuxTerminalPager]
 *    with the REAL [TerminalSurface] (a real vendored `TerminalView`, all four
 *    viewport scanners live — NOT a `*StandIn`; per process.md F2 the stand-in
 *    ban is satisfied), feeding it the EXACT stable-lambda shape the production
 *    body builds. It reads a `composerOpen` flag in the parent (as `showMicSheet`
 *    is read in the body) and toggles it N times. The production
 *    [TmuxTerminalPagerRecompositionProbe] counts the pager's re-executions.
 *  - [LegacyInlineFreshLambdaSubtree] reproduces the PRE-FIX shape: the
 *    `HorizontalPager` of `TerminalSurface`s INLINE in the parent body with
 *    callbacks allocated FRESH each recomposition (exactly the `origin/main`
 *    code). So the same N composer-open toggles recompose the legacy terminal
 *    subtree ~N times — proving the production assertion is a real RED→GREEN
 *    guard, not a vacuous pass.
 *
 * The `composerOpen` flag is a [mutableStateOf] the test drives directly, so the
 * measurement is fully deterministic and environment-independent — NO real IME,
 * NO Docker, NO `assumeTrue` / `assumeFalse(isRunningOnCi())` on the load-bearing
 * assertion (process.md F3). The pane's [TerminalSurfaceState] is unattached
 * (renders the blank black `TerminalView` canvas) — the symptom under test is the
 * pager's SKIP scope on a composer-open toggle, not the attached render cost, so
 * an unattached real surface is the faithful, deterministic probe.
 */
@RunWith(AndroidJUnit4::class)
class Issue796ComposerOpenTerminalScopeProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    /**
     * The production fix: opening the Prompt Composer (toggling `showMicSheet`)
     * must NOT recompose the hoisted [TmuxTerminalPager]. Drives N composer-open
     * toggles and HARD-asserts the pager recomposes O(1).
     */
    @Test
    fun openingComposerDoesNotRecomposeTerminalPager() {
        var composerOpen by mutableStateOf(false)
        TmuxTerminalPagerRecompositionProbe.reset()

        compose.setContent {
            ProductionStableSubtree(composerOpen = { composerOpen })
        }
        compose.waitForIdle()

        // Settle the initial composition, then reset so only toggle-driven
        // pager recompositions count.
        TmuxTerminalPagerRecompositionProbe.reset()

        repeat(COMPOSER_TOGGLES) {
            // Open the composer (showMicSheet = true) — the body-root read flips,
            // re-running the body; a fresh waitForIdle flushes any recomposition.
            compose.runOnIdle { composerOpen = true }
            compose.waitForIdle()
            compose.runOnIdle { composerOpen = false }
            compose.waitForIdle()
        }

        val pagerRecompositions = TmuxTerminalPagerRecompositionProbe.count
        println(
            "ISSUE796_H3_SCOPE production_stable composer_toggles=$COMPOSER_TOGGLES " +
                "pager_recompositions=$pagerRecompositions ceiling=$MAX_PAGER_RECOMPOSITIONS",
        )

        assertTrue(
            "#796 (H3): opening the Prompt Composer (toggling showMicSheet) must " +
                "NOT recompose the hoisted TmuxTerminalPager — the production body " +
                "feeds it stable inputs so it skips. Over $COMPOSER_TOGGLES " +
                "open/close composer toggles the pager recomposed $pagerRecompositions " +
                "times; the O(1) ceiling is $MAX_PAGER_RECOMPOSITIONS. A bursting Codex " +
                "pane + opening the composer must do ZERO main-thread terminal " +
                "recomposition work (the maintainer's ANR trigger).",
            pagerRecompositions <= MAX_PAGER_RECOMPOSITIONS,
        )
    }

    /**
     * Pins the PRE-FIX behaviour so the assertion above is a real guard, not a
     * vacuous pass: the inline fresh-lambda pager recomposes once per composer
     * toggle (≈ N), far above the O(1) ceiling.
     */
    @Test
    fun legacyInlineFreshLambdaPagerRecomposesPerComposerToggle() {
        var composerOpen by mutableStateOf(false)
        val legacyRecompositions = AtomicLong(0L)

        compose.setContent {
            LegacyInlineFreshLambdaSubtree(
                composerOpen = { composerOpen },
                onTerminalSubtreeRecompose = { legacyRecompositions.incrementAndGet() },
            )
        }
        compose.waitForIdle()
        legacyRecompositions.set(0L)

        repeat(COMPOSER_TOGGLES) {
            compose.runOnIdle { composerOpen = true }
            compose.waitForIdle()
            compose.runOnIdle { composerOpen = false }
            compose.waitForIdle()
        }

        val recompositions = legacyRecompositions.get()
        println(
            "ISSUE796_H3_SCOPE legacy_inline composer_toggles=$COMPOSER_TOGGLES " +
                "terminal_subtree_recompositions=$recompositions floor=$MIN_LEGACY_RECOMPOSITIONS",
        )

        assertTrue(
            "#796 (H3) guard: the PRE-FIX inline fresh-lambda pager must recompose " +
                "the terminal subtree per composer toggle (the regression this fix " +
                "removes). Over $COMPOSER_TOGGLES toggles the legacy subtree " +
                "recomposed only $recompositions times — the test would not catch the " +
                "regression. Expected >= $MIN_LEGACY_RECOMPOSITIONS.",
            recompositions >= MIN_LEGACY_RECOMPOSITIONS,
        )
    }

    // --------------------------------------------------------------- harnesses

    /**
     * The PRODUCTION read structure: a parent that reads the `composerOpen` flag
     * (as [TmuxSessionScreen] reads `showMicSheet`) and composes the REAL
     * [TmuxTerminalPager] with STABLE, `remember`ed callbacks — the exact shape the
     * production body now builds. The terminal pager is the counted scope (via its
     * production probe); because its inputs never change identity, a composer-open
     * toggle skips it.
     */
    @Composable
    private fun ProductionStableSubtree(composerOpen: () -> Boolean) {
        val panes = rememberPanes()
        val pagerState = rememberPagerState(pageCount = { panes.size })
        // Stable, remembered callbacks — identical to the production body's
        // `stable*` lambdas. Built once, so the pager's args never change identity.
        val sessionNameForUnifiedPane: (TmuxPaneState) -> String? =
            remember { { null } }
        val onTerminalSizeChanged: (Int, Int) -> Unit = remember { { _, _ -> } }
        val onSurfaceError: (String, Throwable) -> Unit = remember { { _, _ -> } }
        val onRecreateSurface: (String) -> Unit = remember { { } }
        val onUrlTap: (String) -> Unit = remember { { } }
        val onFilePathTap: (String, String) -> Unit = remember { { _, _ -> } }
        val onEngineCommandTap: (String) -> Unit = remember { { } }

        // Read the composer-open flag in the parent body — as TmuxSessionScreen
        // reads `showMicSheet`. This is what re-runs the body root group on a
        // composer toggle; the production fix keeps the pager skipped despite it.
        @Suppress("UNUSED_VARIABLE")
        val isComposerOpen = composerOpen()

        Column(modifier = Modifier.fillMaxSize()) {
            TmuxTerminalPager(
                unifiedPanes = panes,
                pagerState = pagerState,
                sessionName = SESSION_NAME,
                terminalKeyboardMode = TerminalKeyboardMode.RawCommand,
                engineCommands = STABLE_ENGINE_COMMANDS,
                // Issue #796 (REOPENED): a Codex (agent) pane — the gate skips its
                // per-frame viewport scanners. A stable Boolean, so the pager stays
                // skippable (the property under test).
                isAgentPane = true,
                sessionNameForUnifiedPane = sessionNameForUnifiedPane,
                onTerminalSizeChanged = onTerminalSizeChanged,
                onSurfaceError = onSurfaceError,
                onRecreateSurface = onRecreateSurface,
                onUrlTap = onUrlTap,
                onFilePathTap = onFilePathTap,
                onEngineCommandTap = onEngineCommandTap,
            )
        }
    }

    /**
     * The PRE-FIX read structure: the `HorizontalPager` of `TerminalSurface`s
     * INLINE in the parent body with callbacks allocated FRESH each recomposition
     * (the exact `origin/main` shape). The counted hook lives inside the inline
     * pager page, so a composer-open toggle re-runs the body → re-runs the inline
     * pager → recomposes the terminal subtree.
     */
    @Composable
    private fun LegacyInlineFreshLambdaSubtree(
        composerOpen: () -> Boolean,
        onTerminalSubtreeRecompose: () -> Unit,
    ) {
        val panes = rememberPanes()
        val pagerState = rememberPagerState(pageCount = { panes.size })

        @Suppress("UNUSED_VARIABLE")
        val isComposerOpen = composerOpen()

        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalPager(
                state = pagerState,
                key = { pageIndex -> panes[pageIndex].paneId },
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                val pane = panes[pageIndex]
                // Count THIS inline page's re-execution — the terminal subtree.
                onTerminalSubtreeRecompose()
                TerminalSurface(
                    state = pane.terminalState,
                    terminalKeyboardMode = TerminalKeyboardMode.RawCommand,
                    // Pre-fix: FRESH lambdas allocated on every recomposition, so
                    // TerminalSurface is un-skippable and recomposes per toggle.
                    onTerminalSizeChanged = { _, _ -> },
                    onLocalTerminalError = { _ -> },
                    onUrlTap = { _ -> },
                    onFilePathTap = { _ -> },
                    engineCommands = setOf("/clear"),
                    onEngineCommandTap = { _ -> },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 2.dp, vertical = 4.dp),
                )
            }
        }
    }

    @Composable
    private fun rememberPanes(): List<TmuxPaneState> = remember {
        listOf(
            TmuxPaneState(
                paneId = "%0",
                windowId = "@0",
                sessionId = "\$0",
                title = "codex",
                cwd = "/home/agent",
                terminalState = TerminalSurfaceState(),
            ),
        )
    }

    private companion object {
        const val SESSION_NAME = "codex"
        const val COMPOSER_TOGGLES = 12
        val STABLE_ENGINE_COMMANDS = setOf("/clear", "/new")

        // O(1) ceiling: the production pager must recompose at most a small
        // constant across N composer toggles. With stable inputs it is 0 in
        // practice; 2 leaves slack for an initial settling frame.
        const val MAX_PAGER_RECOMPOSITIONS = 2L

        // The pre-fix inline fresh-lambda pager must recompose ~once per composer
        // toggle. Require a healthy floor (most of the N toggles) so the guard
        // genuinely proves the regression is detectable.
        const val MIN_LEGACY_RECOMPOSITIONS = (COMPOSER_TOGGLES - 4).toLong()
    }
}
