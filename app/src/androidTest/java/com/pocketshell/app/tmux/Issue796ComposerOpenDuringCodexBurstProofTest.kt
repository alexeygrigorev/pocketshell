package com.pocketshell.app.tmux

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.terminal.ui.TerminalKeyboardMode
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #796 (H3) — ON-DEVICE PROOF of the maintainer's exact collision:
 * a Codex pane streaming a `%output` burst + OPENING the Prompt Composer
 * (soft-keyboard / IME inset up) must NOT recompose the terminal subtree on the
 * main thread, and must keep the main thread responsive.
 *
 * ## The maintainer's exact trigger (v0.4.6)
 *
 * > "I see it redrawing and it's fine — but the moment I start using the Prompt
 * > Composer to actually type something, that's when it starts freezing."
 *
 * The H4 fix (`aff7ac45`) decoupled the IME-INSET frame burst from the body. But
 * opening the composer (`showMicSheet = true`) re-runs the [TmuxSessionScreen]
 * body, and the OLD inline pager's fresh-lambda `TerminalSurface` callbacks made
 * the terminal subtree recompose on the same main thread the `%output` burst is
 * already saturating — past the ANR threshold. The H3 fix hoists the pager into
 * the SKIPPABLE [TmuxTerminalPager]; this test proves the collision is gone.
 *
 * ## How this proves it (real production composable + real burst, no stand-in)
 *
 *  1. Compose the REAL [TmuxTerminalPager] with the REAL [TerminalSurface] /
 *     vendored [TerminalView] and a pane whose [TerminalSurfaceState] is wired to
 *     a live `%output` producer (the production `attachExternalProducer` bridge) —
 *     the actual on-device render path, NOT a `*StandIn` (process.md F2).
 *  2. Raise a SYNTHETIC `ime()` inset (the #780 model) and HARD-assert it reached
 *     the view hierarchy — NO `assumeTrue` / `assumeFalse(isRunningOnCi())` on the
 *     load-bearing keyboard-up state (process.md F3).
 *  3. Drive a tight, sustained Codex `%output` alt-screen redraw burst through the
 *     real bridge, and WHILE it runs, TOGGLE the parent's `composerOpen` flag (the
 *     production `showMicSheet`) repeatedly — i.e. open/close the Prompt Composer
 *     over a bursting pane, the exact reported trigger.
 *  4. LOAD-BEARING assertion: the [TmuxTerminalPagerRecompositionProbe] proves the
 *     terminal pager recomposes O(1) across all the composer toggles even while
 *     the burst floods the main thread — so opening the composer does ZERO
 *     main-thread terminal recomposition work. Secondary backstop: the main thread
 *     stays under the ANR budget.
 *
 * The burst floods the real main looper while we open/close the composer, so this
 * is the production main-thread contention the maintainer hit — and the assertion
 * is emulator-speed-independent (a recomposition COUNT, not a wall-clock stall
 * that a fast dev-box emulator could mask, per the Slice B finding).
 *
 * Runs only via explicit class filter; it needs no Docker/port fixture (the
 * `%output` producer is driven in-process), so it is CI-redness-safe but is not
 * in any curated CI allowlist.
 */
@RunWith(AndroidJUnit4::class)
class Issue796ComposerOpenDuringCodexBurstProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Volatile
    private var observedImeBottomPx: Int = 0

    @Test
    fun openingComposerDuringCodexBurstDoesNotRecomposeTerminalPager() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )

        var composerOpen by mutableStateOf(false)
        val panes = listOf(
            TmuxPaneState(
                paneId = "%0",
                windowId = "@0",
                sessionId = "\$0",
                title = "codex",
                cwd = "/home/agent",
                terminalState = state,
            ),
        )

        compose.activityRule.scenario.onActivity { activity ->
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            // Read the ime() inset from the SAME OnApplyWindowInsetsListener path
            // production uses (HostImeInset.kt) so the synthetic keyboard-up state
            // is observable + hard-assertable.
            ViewCompat.setOnApplyWindowInsetsListener(activity.window.decorView) { _, insets ->
                observedImeBottomPx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                insets
            }
        }

        TmuxTerminalPagerRecompositionProbe.reset()

        try {
            compose.setContent {
                ComposerOverPagerHarness(panes = panes, composerOpen = { composerOpen })
            }
            compose.waitForIdle()
            val view = waitForTerminalView()

            // Raise the synthetic soft keyboard (the #780 model) and HARD-assert it
            // reached the view hierarchy. No assumeTrue / CI-skip (process.md F3).
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val expectedImePx = (IME_HEIGHT_DP * density).toInt()
            applySyntheticImeInset(expectedImePx)
            compose.waitForIdle()
            assertTrue(
                "Synthetic ime() inset did not reach the view hierarchy; cannot " +
                    "validate the #796 composer-open-during-burst path. " +
                    "observedImeBottomPx=$observedImeBottomPx (expected ~$expectedImePx).",
                observedImeBottomPx > 0,
            )

            // Reset the pager probe AFTER the keyboard-up settle so the count
            // reflects ONLY the composer toggles during the burst.
            compose.waitForIdle()
            TmuxTerminalPagerRecompositionProbe.reset()

            // ---- Drive the Codex `%output` burst on a background producer and,
            // WHILE it floods the main looper, open/close the Prompt Composer.
            val mainHandler = Handler(Looper.getMainLooper())
            val maxStallMs = AtomicLong(0L)
            val pingCount = AtomicLong(0L)
            val pingActive = AtomicBoolean(true)
            fun schedulePing() {
                val scheduledAt = SystemClock.uptimeMillis()
                mainHandler.post {
                    val ranAt = SystemClock.uptimeMillis()
                    val latency = ranAt - scheduledAt
                    if (latency > maxStallMs.get()) maxStallMs.set(latency)
                    pingCount.incrementAndGet()
                    if (pingActive.get()) mainHandler.postDelayed(::schedulePing, PING_INTERVAL_MS)
                }
            }
            schedulePing()

            // Feed the burst off-main, continuously, for the whole window.
            val burstActive = AtomicBoolean(true)
            val burstScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val burstChunks = AtomicLong(0L)
            burstScope.launch {
                var chunk = 0
                while (burstActive.get()) {
                    stdout.emit(buildChunk(chunk).toByteArray(Charsets.US_ASCII))
                    burstChunks.incrementAndGet()
                    chunk += 1
                }
            }

            // While the burst floods the main thread, open + close the composer
            // COMPOSER_TOGGLES times — the maintainer's "open the composer over a
            // redrawing Codex pane" trigger. NOTE: we CANNOT use
            // `compose.waitForIdle()` here — the continuous `%output` burst keeps
            // the Termux MainThreadHandler perpetually busy, so the Compose idling
            // resource never settles (AppNotIdleException). Instead we flip the
            // state directly on the main thread (as a real composer-launcher tap
            // does) and sleep a few frames so any recomposition the toggle triggers
            // is applied + counted by the pager probe. This mirrors how Slice B's
            // burst proof samples without `waitForIdle`.
            val burstStartedAt = SystemClock.uptimeMillis()
            repeat(COMPOSER_TOGGLES) {
                instrumentation.runOnMainSync { composerOpen = true }
                SystemClock.sleep(TOGGLE_SETTLE_MS)
                instrumentation.runOnMainSync { composerOpen = false }
                SystemClock.sleep(TOGGLE_SETTLE_MS)
            }
            val toggleDurationMs = SystemClock.uptimeMillis() - burstStartedAt

            burstActive.set(false)
            SystemClock.sleep(SETTLE_MS)
            pingActive.set(false)
            burstScope.cancel()

            val pagerRecompositions = TmuxTerminalPagerRecompositionProbe.count
            val transcript = visibleTerminalText(view)
            val burstRendered = transcript.contains(BURST_MARKER)

            Log.i(
                LOG_TAG,
                "#796 H3 composer-open-during-burst: composerToggles=$COMPOSER_TOGGLES " +
                    "pagerRecompositions=$pagerRecompositions ceiling=$MAX_PAGER_RECOMPOSITIONS " +
                    "imeBottomPx=$observedImeBottomPx burstChunks=${burstChunks.get()} " +
                    "maxStallMs=${maxStallMs.get()} pings=${pingCount.get()} " +
                    "toggleDurationMs=$toggleDurationMs burstRendered=$burstRendered",
            )
            println(
                "ISSUE796_H3_BURST composer_toggles=$COMPOSER_TOGGLES " +
                    "pager_recompositions=$pagerRecompositions ceiling=$MAX_PAGER_RECOMPOSITIONS " +
                    "ime_bottom_px=$observedImeBottomPx burst_chunks=${burstChunks.get()} " +
                    "max_main_thread_stall_ms=${maxStallMs.get()} ping_count=${pingCount.get()}",
            )

            // Sanity: the burst must have actually rendered content (so the
            // contention is real, not a vacuous quiet pane).
            assertTrue(
                "burst must have rendered visible terminal content during the " +
                    "composer toggles; transcript length=${transcript.length}",
                burstRendered,
            )
            // Sanity: a real flood of chunks fed during the toggles.
            assertTrue(
                "burst must feed a real chunk flood during the composer toggles; " +
                    "chunks=${burstChunks.get()} (needs >= $MIN_BURST_CHUNKS)",
                burstChunks.get() >= MIN_BURST_CHUNKS,
            )

            // ---- LOAD-BEARING assertion (emulator-speed-independent): opening the
            // Prompt Composer over a bursting Codex pane must NOT recompose the
            // hoisted terminal pager. The pager's inputs are all stable, so the
            // composer-open body recomposition skips it → ZERO main-thread terminal
            // recomposition work, even while the burst floods the looper.
            assertTrue(
                "#796 (H3): opening the Prompt Composer over a bursting Codex pane " +
                    "must NOT recompose the terminal subtree. Over $COMPOSER_TOGGLES " +
                    "open/close composer toggles DURING a live %output burst, the " +
                    "TmuxTerminalPager recomposed $pagerRecompositions times; the O(1) " +
                    "ceiling is $MAX_PAGER_RECOMPOSITIONS. This is the maintainer's exact " +
                    "ANR trigger — the terminal must do zero recomposition work when the " +
                    "composer opens.",
                pagerRecompositions <= MAX_PAGER_RECOMPOSITIONS,
            )

            // ---- Secondary backstop: the main thread stays under the ANR budget.
            assertTrue(
                "#796: opening the composer over a Codex %output burst (keyboard up) " +
                    "must NOT stall the main thread past ${MAX_MAIN_THREAD_STALL_MS}ms " +
                    "(5s ANR window). Observed max stall=${maxStallMs.get()}ms over " +
                    "${pingCount.get()} pings.",
                maxStallMs.get() <= MAX_MAIN_THREAD_STALL_MS,
            )
            assertTrue(
                "main-thread ping sampler must have run during the burst; " +
                    "pings=${pingCount.get()}",
                pingCount.get() >= MIN_PINGS,
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
        Unit
    }

    // --------------------------------------------------------------- harness

    /**
     * A parent that reads the `composerOpen` flag (as [TmuxSessionScreen] reads
     * `showMicSheet`) and composes the REAL [TmuxTerminalPager] with stable,
     * `remember`ed callbacks — the production shape.
     */
    @Composable
    private fun ComposerOverPagerHarness(
        panes: List<TmuxPaneState>,
        composerOpen: () -> Boolean,
    ) {
        val pagerState = rememberPagerState(pageCount = { panes.size })
        val sessionNameForUnifiedPane: (TmuxPaneState) -> String? = remember { { null } }
        val onTerminalSizeChanged: (Int, Int) -> Unit = remember { { _, _ -> } }
        val onSurfaceError: (String, Throwable) -> Unit = remember { { _, _ -> } }
        val onRecreateSurface: (String) -> Unit = remember { { } }
        val onUrlTap: (String) -> Unit = remember { { } }
        val onFilePathTap: (String, String) -> Unit = remember { { _, _ -> } }
        val onEngineCommandTap: (String) -> Unit = remember { { } }

        // Read the composer-open flag in the body — as TmuxSessionScreen reads
        // `showMicSheet`. This re-runs the body on a composer toggle; the H3 fix
        // keeps the pager skipped despite it.
        @Suppress("UNUSED_VARIABLE")
        val isComposerOpen = composerOpen()

        Column(modifier = Modifier.fillMaxSize()) {
            TmuxTerminalPager(
                unifiedPanes = panes,
                pagerState = pagerState,
                sessionName = "codex",
                terminalKeyboardMode = TerminalKeyboardMode.RawCommand,
                engineCommands = STABLE_ENGINE_COMMANDS,
                // Issue #796 (REOPENED): this is a Codex (agent) pane — the gate
                // skips the per-frame viewport scanners for it. A stable Boolean,
                // so the pager stays skippable (the H3 property under test).
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

    // --------------------------------------------------------------- helpers

    private fun buildChunk(i: Int): String {
        val esc = "\u001B"
        return buildString {
            append("$esc[H")
            for (row in 0 until VIEWPORT_ROWS) {
                append("$esc[K")
                append("$BURST_MARKER r$row see https://example.com/codex-$i-$row ")
                append("/clear edit src/main/kotlin/Burst$i$row.kt token-$i-$row\r\n")
            }
        }
    }

    private fun applySyntheticImeInset(imeBottomPx: Int) {
        compose.activityRule.scenario.onActivity { activity ->
            val decor = activity.window.decorView
            val insets = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, imeBottomPx))
                .build()
            ViewCompat.dispatchApplyWindowInsets(decor, insets)
        }
    }

    private suspend fun waitForTerminalView(): TerminalView {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ref = arrayOfNulls<TerminalView>(1)
        withTimeout(5_000) {
            while (ref[0] == null) {
                instrumentation.runOnMainSync {
                    ref[0] = findTerminalView(compose.activity.window.decorView)
                }
                if (ref[0] == null) delay(20)
            }
        }
        return requireNotNull(ref[0])
    }

    private fun findTerminalView(root: View): TerminalView? {
        if (root is TerminalView) return root
        if (root is ViewGroup) {
            for (i in 0 until root.childCount) {
                val hit = findTerminalView(root.getChildAt(i))
                if (hit != null) return hit
            }
        }
        return null
    }

    private fun visibleTerminalText(view: TerminalView): String {
        var text = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            text = view.currentSession?.emulator?.screen?.transcriptText.orEmpty()
        }
        return text
    }

    private companion object {
        const val LOG_TAG = "Issue796H3Burst"
        const val IME_HEIGHT_DP = 320
        const val VIEWPORT_ROWS = 24
        const val COMPOSER_TOGGLES = 10
        const val TOGGLE_SETTLE_MS = 50L
        const val PING_INTERVAL_MS = 8L
        const val SETTLE_MS = 250L
        const val MAX_MAIN_THREAD_STALL_MS = 1_000L
        const val MIN_PINGS = 10L
        const val MIN_BURST_CHUNKS = 20L
        const val BURST_MARKER = "ISSUE796-H3-BURST"
        val STABLE_ENGINE_COMMANDS = setOf("/clear", "/new")

        // O(1) ceiling: the production pager must recompose at most a small
        // constant across the composer toggles even during the burst.
        const val MAX_PAGER_RECOMPOSITIONS = 2L
    }
}
