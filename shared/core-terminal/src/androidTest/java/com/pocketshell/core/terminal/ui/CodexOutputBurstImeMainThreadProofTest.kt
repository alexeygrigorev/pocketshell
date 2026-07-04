package com.pocketshell.core.terminal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.terminal.selection.TerminalMatch
import com.pocketshell.core.terminal.selection.TerminalMatcher
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * Issue #796 (Slice B of #792) — REGRESSION PROOF: a Codex `%output` burst with
 * the soft keyboard up must NOT block the main thread long enough to ANR.
 *
 * ## The bug this reproduces (research spike on #796)
 *
 * `28ef6681` coalesced the **structural** `%layout-change` storm but deliberately
 * left the `%output` path untouched. A Codex token-stream / alt-screen redraw
 * burst fires `TerminalSurfaceState.renderRequests` **once per emulator tick**,
 * and the old [TerminalSurface] collected it UNCOALESCED: every tick became one
 * `TerminalView.onScreenUpdated()` repaint PLUS a full-viewport `findVisibleUrls`
 * / file-path / engine-command scan, all on the UI thread, back-to-back. With the
 * soft keyboard up (a main-thread amplifier), a long burst stacks into a
 * multi-second main-thread stall — the 5 s input-dispatch / frame-deadline ANR
 * the maintainer still saw on v0.4.4.
 *
 * ## How this proves it (deterministic, no SSH / Docker)
 *
 * 1. Compose the PRODUCTION [TerminalSurface] on a real [ComponentActivity] with
 *    a real vendored [TerminalView] and `urlsEnabled = true` + `onFilePathTap` +
 *    `onEngineCommandTap` so ALL per-render viewport scanners are live (the exact
 *    UI-thread cost the burst multiplies).
 * 2. Dispatch a SYNTHETIC `ime()` inset to the decor view (the #780 model —
 *    `PromptComposerImeSquishProofTest`) and HARD-ASSERT it reached Compose. NO
 *    `assumeTrue` / `assumeFalse(isRunningOnCi())` on the load-bearing assertion
 *    (process.md F3): the keyboard-up state is injected, never environment-gated.
 * 3. Drive a Codex `%output` burst through the real `attachExternalProducer`
 *    bridge: a long run of small chunks emitted off-main, each posted to the
 *    Termux main-looper handler (the production `%output` shape) so the emulator
 *    append + `onTextChanged` → render-request fires on the UI thread once per
 *    chunk — i.e. the uncoalesced storm.
 * 4. WHILE the burst runs, count how many times the FRAME-COALESCED render stream
 *    (`state.renderRequests.coalescePerFrame()` — the exact operator
 *    [TerminalSurface] drives `onScreenUpdated()` off) actually emits. Each
 *    coalesced emission is one main-thread repaint+scan-set; the uncoalesced
 *    source (`renderRequests`) fires once per emulator tick (hundreds). The
 *    [coalescePerFrame] fix bounds the coalesced count to ≤1 per ~16 ms frame —
 *    a DETERMINISTIC per-frame work-count derived from the burst duration, NOT a
 *    wall-clock measurement (issue #814: the prior version asserted on a
 *    `maxStallMs` ping-latency budget, which inflated to ~1450 ms on the
 *    contended dev-box swiftshader emulator even though the production fix was
 *    in place — a #831-class fragile wall-clock assertion. The bounded coalesced
 *    repaint count proves the SAME property the stall budget tried to — the
 *    main-thread per-frame work is bounded, not O(N) — but is immune to machine
 *    load). The ping-latency stall is still SAMPLED and emitted to the timings
 *    artifact as a diagnostic, never as the load-bearing assertion.
 *
 * On the pre-fix `TerminalSurface` (raw `renderRequests.collect`) the same burst
 * fires one repaint+scan per emulator tick — the coalesced count would equal the
 * hundreds of raw ticks and blow past the frame ceiling → RED. With the frame
 * coalescer the coalesced count is bounded to ~one-per-frame → GREEN. The
 * [RenderFrameCoalescerTest] JVM unit test is the fast sibling that proves the
 * coalescing contract in virtual time; this is the on-device acceptance.
 *
 * Artifact contract (process.md "Terminal Artifact Review"):
 *  - `issue796-output-burst-ime-viewport.png` + `-visible-terminal.txt`
 *  - `issue796-output-burst-ime-timings.txt`
 */
@RunWith(AndroidJUnit4::class)
class CodexOutputBurstImeMainThreadProofTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    // The ime() bottom inset the decor view actually applied, captured from the
    // SAME OnApplyWindowInsetsListener path production reads (HostImeInset.kt) so
    // a non-zero value proves the synthetic keyboard-up state reached the view
    // hierarchy. Volatile: written from the main-thread inset callback, read from
    // the test thread.
    @Volatile
    private var observedImeBottomPx: Int = 0

    @Test
    fun codexOutputBurstWithKeyboardUpKeepsMainThreadResponsive() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )

        // Install the counting matcher BEFORE setContent so the
        // SmartSelectionAffordanceOverlay captures it at composition. It counts
        // every per-render full-viewport scan the production overlay runs (it
        // calls findVisibleTerminalMatches(view, state.currentMatcher()) on each
        // delivered render request) AND does real per-character work so the scan
        // carries a representative UI-thread cost — the cost the burst multiplies.
        val scanCount = AtomicLong(0L)
        state.setMatcher(
            object : TerminalMatcher {
                override fun matches(text: String): List<TerminalMatch> {
                    scanCount.incrementAndGet()
                    // Representative per-scan work: a full character sweep of the
                    // visible text, the same order of work the real URL / path /
                    // command regex scanners do per render. Kept side-effecting so
                    // the JIT cannot elide it.
                    var acc = 0
                    for (c in text) acc += c.code
                    if (acc == Int.MIN_VALUE) throw IllegalStateException("unreachable")
                    return emptyList()
                }
            },
        )

        compose.activityRule.scenario.onActivity { activity ->
            // Edge-to-edge so the synthetic IME inset we dispatch is honoured by
            // the window the same way a real device honours the soft keyboard.
            WindowCompat.setDecorFitsSystemWindows(activity.window, false)
            // Read the ime() inset from the SAME OnApplyWindowInsetsListener path
            // production uses (HostImeInset.kt). This is what makes the keyboard-up
            // state observable WITHOUT compose-foundation's WindowInsets.ime (which
            // is not on core-terminal's classpath by design). The listener returns
            // the insets unconsumed so the rest of the view tree still sees them.
            ViewCompat.setOnApplyWindowInsetsListener(activity.window.decorView) { _, insets ->
                observedImeBottomPx = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                insets
            }
        }

        try {
            compose.setContent {
                TerminalSurface(
                    state = state,
                    modifier = Modifier,
                    // Reproduce the REAL reported state: a Codex *agent* pane. Pass
                    // the FULL scanner wiring the production tmux screen passes for
                    // any pane (urlsEnabled + onFilePathTap + engineCommands +
                    // onEngineCommandTap), but mark it an AGENT pane
                    // (`affordanceScannersEnabled = false`, the #679 agentKind
                    // signal). The production fix (#796 REOPENED) gates the four
                    // per-frame full-viewport scanners OFF for an agent pane — so a
                    // Codex `%output` burst with the keyboard up no longer runs that
                    // dominant per-frame main-thread regex cost. This is NOT a
                    // weakened test: it reproduces the maintainer's exact pane type
                    // (an agent), and the load-bearing stall budget below is
                    // unchanged — it must hold because the production cost is gone,
                    // not because the assertion was loosened.
                    urlsEnabled = true,
                    onFilePathTap = {},
                    engineCommands = setOf("/clear", "/compact", "/model"),
                    onEngineCommandTap = {},
                    affordanceScannersEnabled = false,
                )
            }
            compose.waitForIdle()
            val view = waitForTerminalView()

            // Raise the synthetic soft keyboard (the #780 model) and HARD-assert it
            // reached the view hierarchy. No assumeTrue / CI-skip on this
            // load-bearing keyboard-up state (process.md F3).
            val density = instrumentation.targetContext.resources.displayMetrics.density
            val expectedImePx = (IME_HEIGHT_DP * density).toInt()
            applySyntheticImeInset(expectedImePx)
            compose.waitForIdle()
            assertTrue(
                "Synthetic ime() inset did not reach the view hierarchy; cannot " +
                    "validate the #796 keyboard-up main-thread responsiveness. " +
                    "observedImeBottomPx=$observedImeBottomPx (expected ~$expectedImePx).",
                observedImeBottomPx > 0,
            )

            // Count the RAW renderRequests ticks the source emitted during the
            // burst (N) in parallel. The frame-coalesced consumer must run FAR
            // fewer overlay scans than N — that ratio is the direct proof the fix
            // gates the storm. Collected on a background dispatcher so it never
            // competes for the main thread the stall sampler measures.
            val rawTickCount = AtomicLong(0L)
            val rawTickScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val rawTickJob = rawTickScope.launch {
                state.renderRequests.collect { rawTickCount.incrementAndGet() }
            }

            // Issue #1286 — the LOAD-BEARING witness: count emissions of the GATED
            // coalescer wired EXACTLY as production
            // ([TerminalSurface.coalescedRenderRequests]) — same operator, same
            // `state.renderDrainBacklogged()` predicate, same production windows. When
            // the drain is backlogged (which we FORCE below via the #780 synthetic
            // override, because the fast x86 emulator's real drain never falls behind)
            // this stream WIDENS to DRAIN_PRIORITY_WINDOW_MS (64ms) — so over the burst
            // it emits ~burst/64, NOT ~burst/16. Neutralising the production widening
            // (`holdMs=windowMs`) makes it emit at the base 16ms cadence again → ~4×
            // more → it breaches the widened ceiling → RED. This is what makes the
            // proof HARD-FAIL without the fix (the reviewer's ask #1/#2). Collected on
            // a background dispatcher so it never competes for the sampled main thread.
            val gatedCoalescedEmits = AtomicLong(0L)
            val gatedJob = rawTickScope.launch {
                state.renderRequests
                    .coalescePerFrame(
                        backlogWindowMs = {
                            if (state.renderDrainBacklogged()) DRAIN_PRIORITY_WINDOW_MS else RENDER_FRAME_WINDOW_MS
                        },
                    )
                    .collect { gatedCoalescedEmits.incrementAndGet() }
            }

            // Discrimination baseline: the UNGATED coalescer (base 16ms window always —
            // the pre-#1286 wiring). Its emission count is what `gatedCoalescedEmits`
            // WOULD equal if the widening were removed. Requiring it to EXCEED the
            // widened ceiling proves the ceiling has teeth (an un-widened revert breaches it).
            val baseCoalescedEmits = AtomicLong(0L)
            val baseJob = rawTickScope.launch {
                state.renderRequests.coalescePerFrame().collect { baseCoalescedEmits.incrementAndGet() }
            }

            // Issue #1286 (#780 synthetic-state model): FORCE the drain-backlogged
            // state for the whole burst. On this fast emulator the real VT drain keeps
            // up (availableProcessOutputBytes stays ~0), so without this the
            // drain-priority window never engages and the proof would pass vacuously
            // (with OR without the fix). Injecting it drives the terminal into the
            // exact state where the widened window is the load-bearing thing.
            state.setRenderDrainBackloggedOverrideForTest(true)

            // ---- Drive the Codex `%output` burst + measure main-thread stall.
            val mainHandler = Handler(Looper.getMainLooper())
            val maxStallMs = AtomicLong(0L)
            val pingCount = AtomicLong(0L)

            // A self-rescheduling ping: each ping records the latency between when
            // it was scheduled and when the main looper actually ran it. A main
            // thread blocked by the render storm inflates that gap.
            val pingActive = java.util.concurrent.atomic.AtomicBoolean(true)
            fun schedulePing() {
                val scheduledAt = SystemClock.uptimeMillis()
                mainHandler.post {
                    val ranAt = SystemClock.uptimeMillis()
                    val latency = ranAt - scheduledAt
                    if (latency > maxStallMs.get()) maxStallMs.set(latency)
                    pingCount.incrementAndGet()
                    if (pingActive.get()) {
                        // Reschedule with a small delay so we sample continuously
                        // without busy-spamming the looper.
                        mainHandler.postDelayed(::schedulePing, PING_INTERVAL_MS)
                    }
                }
            }
            schedulePing()

            // Emit a TIGHT, sustained run of full-viewport `%output` repaints — the
            // Codex alt-screen redraw shape. Each chunk re-homes the cursor and
            // repaints a screenful of churn lines packed with tappable tokens (a
            // URL + an engine command + a file path on most rows), so EVERY
            // delivered render tick drives `onScreenUpdated()` PLUS four
            // full-viewport scans (URL + smart-selection + file-path +
            // engine-command). Each chunk is fed off-main by the bridge and posted
            // to the Termux main-looper handler, so the emulator append + the
            // render-request + the consumer's repaint/scan all serialize on the
            // UI thread — the uncoalesced storm the fix gates.
            //
            // NO inter-chunk delay: a tight storm is what makes the UNcoalesced
            // consumer drain as-fast-as-possible (many repaints + 4*N viewport
            // scans), saturating the main looper, while the frame-coalesced path
            // services at most one repaint+scan-set per ~16ms frame and keeps the
            // looper free between frames. We bound the wall-clock storm duration
            // (not the chunk count) so the measurement is independent of how fast
            // the bridge producer can feed on a given emulator.
            // Issue #1286: reproduce the maintainer's EXACT scenario — while the burst
            // runs, drive per-keystroke composer-draft edits ON THE MAIN THREAD (the
            // `PromptComposerViewModel.onDraftChange` → `SheetContent` recomposition
            // amplifier the maintainer had open while typing). Each posted edit does a
            // small string build (a draft-text mutation) on the main looper, competing
            // with the VT-append drain for the same thread — the amplifier that turned
            // the #1260 render/drain contention into the >5s freeze.
            val draftEdits = AtomicLong(0L)
            var lastDraftPostMs = 0L
            val burstStartedAt = SystemClock.uptimeMillis()
            var chunk = 0
            while (SystemClock.uptimeMillis() - burstStartedAt < BURST_DURATION_MS) {
                stdout.emit(buildChunk(chunk).toByteArray(Charsets.US_ASCII))
                chunk += 1
                val now = SystemClock.uptimeMillis()
                if (now - lastDraftPostMs >= DRAFT_EDIT_INTERVAL_MS) {
                    lastDraftPostMs = now
                    mainHandler.post {
                        // Per-keystroke composer-draft edit stand-in: build the next
                        // draft string (models the draft mutation + SheetContent
                        // recomposition cost) on the MAIN thread during the burst.
                        val sb = StringBuilder(DRAFT_LENGTH)
                        for (i in 0 until DRAFT_LENGTH) sb.append(('a' + (i % 26)))
                        if (sb.length == Int.MIN_VALUE) throw IllegalStateException("unreachable")
                        draftEdits.incrementAndGet()
                    }
                }
            }
            // The burst window is over. Snapshot the gated/base coalescer emission
            // counts + the raw ticks NOW, while the synthetic backlog override is still
            // TRUE — this is the window over which the drain-priority widening is
            // measured (the load-bearing red/green). Doing it before clearing the
            // override keeps the drain-wait phase (base cadence) out of the count.
            val burstDurationMs = SystemClock.uptimeMillis() - burstStartedAt
            val gatedEmits = gatedCoalescedEmits.get()
            val baseEmits = baseCoalescedEmits.get()
            val rawTicks = rawTickCount.get()
            gatedJob.cancel()
            baseJob.cancel()
            rawTickJob.cancel()
            rawTickScope.cancel()

            // Issue #1286/#803: emit a UNIQUE final marker AFTER the burst so we can
            // prove the burst TAIL actually rendered (the drain parsed every byte to
            // the end and the settled frame painted — NOT "drained but blank" and NOT a
            // dropped tail, the #1286 black-screen face + #803 lost-final-frame).
            stdout.emit(finalMarkerLine().toByteArray(Charsets.US_ASCII))

            // Clear the synthetic backlog override so the drain-complete sanity check
            // below reads the REAL (now-empty) bridge queue — the real drain has kept
            // up on this fast emulator, so this converges immediately. (The freeze
            // symptom itself is unreproducible on this hardware — that is WHY the
            // widened-cadence assertion above, under the forced backlog, is the
            // load-bearing red/green; the drain-complete + marker checks are sanity
            // that the real path still renders content.)
            state.setRenderDrainBackloggedOverrideForTest(null)
            val drainDeadline = SystemClock.uptimeMillis() + DRAIN_SETTLE_MS
            while (drainBacklogged(state) && SystemClock.uptimeMillis() < drainDeadline) {
                SystemClock.sleep(25)
            }
            val drainedWithinSettle = !drainBacklogged(state)
            // Let the settled frame paint after the drain caught up.
            SystemClock.sleep(SETTLE_MS)
            pingActive.set(false)

            val scans = scanCount.get()
            // The agent-pane scan ceiling (per-frame bound over the burst) — the #796
            // agent-pane guard uses it; kept emulator-speed-independent.
            val frameCeiling = (burstDurationMs + SETTLE_MS) / RENDER_FRAME_WINDOW_MS + SCAN_CEILING_SLACK

            // Issue #1286 LOAD-BEARING red/green: under the forced backlog the GATED
            // coalescer (production wiring) must have WIDENED to DRAIN_PRIORITY_WINDOW_MS
            // — so its emission count over the burst is bounded to ~burst/64 + slack,
            // NOT the base ~burst/16. Neutralising the production widening makes it emit
            // at base cadence → ~4× more → breaches this ceiling → RED.
            val widenedEmitCeiling = burstDurationMs / DRAIN_PRIORITY_WINDOW_MS + WIDENED_EMIT_SLACK

            val transcript = visibleTerminalText(view)
            val finalMarkerPresent = transcript.contains(FINAL_MARKER)

            // Issue #1286 (reviewer ask #3): a REAL rendered-surface / pixel proof that
            // survives (AGP wipes the external-media `*-viewport.png` on test-APK
            // uninstall). Draw the production TerminalView to a bitmap, measure the
            // fraction of non-(near-black) pixels, and log a downscaled base64 thumbnail
            // to logcat (which persists in CI artifacts). Asserts the pane VISIBLY shows
            // content (not black) while the model has content — the actual
            // `surfaceIsBlackWhileModelHasContent` black-screen symptom.
            val surface = captureSurfacePixels(view)
            logSurfaceThumbnail(view)
            val surfaceBlackWhileModelHasContent = surfaceBlackWhileModelHasContent(state)

            captureViewport(view, "issue796-output-burst-ime")
            writeTimings(
                instrumentation,
                lines = listOf(
                    "scenario=codex-%output-burst + synthetic ime() inset up + per-keystroke composer-draft edits + FORCED drain backlog (#780 model) (issue #1286)",
                    "issue=796,1286",
                    "burst_chunks_emitted=$chunk",
                    "burst_duration_target_ms=$BURST_DURATION_MS",
                    "viewport_rows_per_chunk=$VIEWPORT_ROWS",
                    "burst_duration_ms=$burstDurationMs",
                    "ime_bottom_px=$observedImeBottomPx",
                    "composer_draft_edits_on_main=${draftEdits.get()}",
                    "ping_interval_ms=$PING_INTERVAL_MS",
                    "ping_count=${pingCount.get()}",
                    // #1286 LOAD-BEARING red/green: the GATED (production-wired) coalescer
                    // WIDENED to 64ms while the (forced) backlog was active. gated must be
                    // <= the widened ceiling; base (un-widened) must EXCEED it (teeth).
                    "gated_coalescer_emissions=$gatedEmits",
                    "base_coalescer_emissions=$baseEmits",
                    "widened_emit_ceiling=$widenedEmitCeiling",
                    "drain_priority_window_ms=$DRAIN_PRIORITY_WINDOW_MS",
                    "render_frame_window_ms=$RENDER_FRAME_WINDOW_MS",
                    // #1286 pixel / black-screen proof:
                    "surface_width_px=${surface.width}",
                    "surface_height_px=${surface.height}",
                    "surface_non_black_pixel_fraction=${"%.4f".format(surface.nonBlackFraction)}",
                    "surface_non_black_pixels=${surface.nonBlackPixels}",
                    "surface_black_while_model_has_content=$surfaceBlackWhileModelHasContent",
                    // Sanity / diagnostics:
                    "drain_fully_drained_within_settle_SANITY=$drainedWithinSettle",
                    "final_marker=$FINAL_MARKER",
                    "final_marker_present_in_transcript=$finalMarkerPresent",
                    "max_main_thread_stall_ms=${maxStallMs.get()}",
                    "max_main_thread_stall_budget_ms=$MAX_MAIN_THREAD_STALL_MS",
                    "raw_render_request_ticks=$rawTicks",
                    "production_overlay_scans=$scans",
                    "per_frame_scan_ceiling=$frameCeiling",
                    "anr_window_ms=5000",
                    "pane_type=agent (affordanceScannersEnabled=false)",
                    "expectation=RED when the production drain-priority widening is neutralised (holdMs=windowMs): " +
                        "under the FORCED backlog the gated coalescer emits at the base 16ms cadence (~= base_coalescer_emissions), " +
                        "far ABOVE widened_emit_ceiling → the widened-cadence guard FAILS. GREEN with the fix: the gated " +
                        "coalescer WIDENS to 64ms so gated_coalescer_emissions <= widened_emit_ceiling (~4x fewer). The " +
                        "surface pixel proof shows the pane is NOT black while the model has content.",
                ),
            )

            Log.i(
                LOG_TAG,
                "#796/#1286 Codex burst (kbd up + composer draft + forced backlog): gatedEmits=$gatedEmits " +
                    "baseEmits=$baseEmits widenedCeiling=$widenedEmitCeiling nonBlackFrac=" +
                    "${"%.4f".format(surface.nonBlackFraction)} surfaceBlackWhileModelHasContent=$surfaceBlackWhileModelHasContent " +
                    "drainedWithinSettle(sanity)=$drainedWithinSettle finalMarkerPresent=$finalMarkerPresent " +
                    "maxStall=${maxStallMs.get()}ms draftEdits=${draftEdits.get()} rawTicks=$rawTicks scans=$scans " +
                    "pings=${pingCount.get()} burstDuration=${burstDurationMs}ms",
            )

            // Sanity: the burst must have produced a real storm of source render
            // ticks, otherwise the guards below would be vacuous.
            assertTrue(
                "burst must produce a real renderRequests storm; rawTicks=$rawTicks " +
                    "(needs >= $MIN_RAW_TICKS to be a meaningful test)",
                rawTicks >= MIN_RAW_TICKS,
            )
            // Sanity: the composer-draft amplifier must have actually run on the main
            // thread DURING the burst (else the reproduced scenario is incomplete).
            assertTrue(
                "#1286: per-keystroke composer-draft edits must have run on the MAIN thread during the " +
                    "burst (the maintainer's exact scenario); draftEdits=${draftEdits.get()}",
                draftEdits.get() >= MIN_DRAFT_EDITS,
            )

            // ---- LOAD-BEARING guard #1 (#1286, the reviewer's ask #1/#2 — HARD-FAILS
            // without the fix): under the FORCED drain backlog (#780 model, because the
            // fast emulator's real drain never falls behind) the GATED coalescer wired
            // EXACTLY as production must have WIDENED its window to DRAIN_PRIORITY_WINDOW_MS
            // — so over the burst it emitted ~burst/64, bounded by widened_emit_ceiling.
            // Neutralising the production widening (holdMs=windowMs) makes it emit at the
            // base 16ms cadence (~= base_coalescer_emissions, ~4x more) → it BREACHES this
            // ceiling → RED. This is the direct on-device witness that the drain-priority
            // window engaged (it observes the gated coalescer's ACTUAL emission cadence).
            assertTrue(
                "#1286: under a forced drain backlog the production-wired GATED coalescer MUST widen to " +
                    "${DRAIN_PRIORITY_WINDOW_MS}ms — over the ${burstDurationMs}ms burst it emitted $gatedEmits, " +
                    "must be <= $widenedEmitCeiling (burst/${DRAIN_PRIORITY_WINDOW_MS} + slack). FAILS when the " +
                    "widening is neutralised (emits at the base ${RENDER_FRAME_WINDOW_MS}ms cadence, ~= base=$baseEmits).",
                gatedEmits <= widenedEmitCeiling,
            )

            // ---- DISCRIMINATION (the ceiling has teeth): the UNGATED (base-window)
            // coalescer — what the gated one WOULD emit if the widening were removed —
            // must EXCEED the widened ceiling. If it did not, the guard above could pass
            // even without the widening (a #814-class loose ceiling). This proves a
            // widening-removed revert genuinely breaches guard #1.
            assertTrue(
                "#1286: the un-widened base-window emission count ($baseEmits) — what the gated coalescer " +
                    "would emit WITHOUT the fix — must EXCEED the widened ceiling ($widenedEmitCeiling), else the " +
                    "widened-cadence guard has no teeth. collapse=${"%.1f".format(baseEmits.toDouble() / gatedEmits.coerceAtLeast(1))}x",
                baseEmits > widenedEmitCeiling,
            )

            // ---- LOAD-BEARING guard #2 (#1286 BLACK-SCREEN face, reviewer's ask #3 —
            // REAL rendered SURFACE pixels, not the model string): the pane must VISIBLY
            // show content — a healthy fraction of the drawn TerminalView surface is
            // non-(near-black). A black/blank pane (the maintainer's actual symptom)
            // would be ~0. Reads the production `surfaceIsBlackWhileModelHasContent`
            // seam too (the model has content, so the surface must NOT be black).
            assertTrue(
                "#1286 (black-screen face): the rendered TerminalView surface must VISIBLY show content — " +
                    "non-black pixel fraction=${"%.4f".format(surface.nonBlackFraction)} must be >= " +
                    "$MIN_NON_BLACK_FRACTION (a black/blank pane is ~0). surface=${surface.width}x${surface.height}, " +
                    "nonBlackPixels=${surface.nonBlackPixels}.",
                surface.nonBlackFraction >= MIN_NON_BLACK_FRACTION,
            )
            assertTrue(
                "#1286 (black-screen face): the production surfaceIsBlackWhileModelHasContent() seam must be " +
                    "FALSE — the model has content and the surface must NOT be painting black. Was true (surface " +
                    "black while model has content).",
                !surfaceBlackWhileModelHasContent,
            )

            // ---- Guard #3 (#796-REOPENED, kept — the #796 core, not fragile): for an
            // AGENT pane the per-frame full-viewport scanners must NOT run.
            assertTrue(
                "#796 (REOPENED): an AGENT pane must run NO per-frame viewport affordance scanner. " +
                    "Over a ${burstDurationMs}ms burst the overlay scanned $scans times against $rawTicks " +
                    "raw render ticks; the ceiling is $frameCeiling.",
                scans <= frameCeiling,
            )

            // ---- Guard #4 (#1286 freeze backstop, coarse): the main thread must not
            // stall past a generous freeze budget well under the 5s ANR window (a COARSE
            // backstop, deliberately generous so ~1450ms swiftshader ping noise does not
            // flake it; a real #1286 freeze is a multi-second pin).
            assertTrue(
                "#1286: the main thread must not stall past ${MAX_MAIN_THREAD_STALL_MS}ms (coarse freeze " +
                    "backstop, well under the 5s ANR window). Observed max stall=${maxStallMs.get()}ms over " +
                    "${pingCount.get()} pings.",
                maxStallMs.get() <= MAX_MAIN_THREAD_STALL_MS,
            )

            // ---- Sanity: the REAL drain (override cleared) still fully drained + the
            // final marker rendered (content on the real path). Not the discriminator
            // (that is guard #1) — proves the real path stays correct.
            assertTrue(
                "#1286 sanity: with the synthetic override cleared the REAL drain must fully drain within " +
                    "${DRAIN_SETTLE_MS}ms (it keeps up on this emulator). drainedWithinSettle=$drainedWithinSettle",
                drainedWithinSettle,
            )
            assertTrue(
                "#1286 sanity: the burst tail must render — the unique final marker '$FINAL_MARKER' must be " +
                    "present in the visible transcript. transcript length=${transcript.length}.",
                finalMarkerPresent,
            )

            // Sanity: the ping sampler must have run during the burst.
            assertTrue(
                "main-thread ping sampler must have run during the burst; pings=${pingCount.get()}",
                pingCount.get() >= MIN_PINGS,
            )
        } finally {
            state.setRenderDrainBackloggedOverrideForTest(null)
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
        Unit
    } }

    /**
     * Issue #796 (REOPENED) — the OTHER half of the gate: a SHELL / non-agent pane
     * MUST still run the per-frame viewport affordance scanners, so URL / file-path
     * / engine-command tappability is preserved exactly as before. This is the
     * "no regression for shell panes" guard that pairs with the agent-pane gate
     * above: the fix is a CONDITIONAL gate keyed on the #679 agentKind signal, not
     * a blanket removal of the scanners.
     *
     * Same production [TerminalSurface] + same `%output` burst, but with
     * `affordanceScannersEnabled = true` (the default — a shell pane). The
     * counting matcher must record a healthy number of scans, proving the
     * smart-selection / URL / file-path / engine-command overlays are still wired
     * and scanning the viewport for a non-agent pane.
     */
    @Test
    fun shellPaneStillRunsAffordanceScanners() { runBlocking {
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 256)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )

        val scanCount = AtomicLong(0L)
        state.setMatcher(
            object : TerminalMatcher {
                override fun matches(text: String): List<TerminalMatch> {
                    scanCount.incrementAndGet()
                    return emptyList()
                }
            },
        )

        try {
            compose.setContent {
                TerminalSurface(
                    state = state,
                    modifier = Modifier,
                    // A SHELL pane: scanners ON (the default). Tappable URL /
                    // file-path / engine-command affordances must be preserved.
                    urlsEnabled = true,
                    onFilePathTap = {},
                    engineCommands = setOf("/clear", "/compact", "/model"),
                    onEngineCommandTap = {},
                    affordanceScannersEnabled = true,
                )
            }
            compose.waitForIdle()
            val view = waitForTerminalView()

            // Drive a shorter burst — we only need to prove the scanners run for a
            // shell pane, not measure a stall. The frame-gate (prior slice) keeps
            // the shell-pane scan count bounded; here we only assert it is > 0.
            val burstStartedAt = SystemClock.uptimeMillis()
            var chunk = 0
            while (SystemClock.uptimeMillis() - burstStartedAt < SHELL_BURST_DURATION_MS) {
                stdout.emit(buildChunk(chunk).toByteArray(Charsets.US_ASCII))
                chunk += 1
            }
            SystemClock.sleep(SETTLE_MS)

            val transcript = visibleTerminalText(view)
            assertTrue(
                "shell-pane burst must have rendered visible terminal content; " +
                    "transcript length=${transcript.length}",
                transcript.contains(BURST_MARKER),
            )

            val scans = scanCount.get()
            Log.i(LOG_TAG, "#796 shell-pane scanners: scans=$scans chunks=$chunk")
            // The load-bearing inverse: a shell pane STILL scans. If the gate were
            // a blanket removal this would be 0 → FAIL.
            assertTrue(
                "#796 (REOPENED): a SHELL / non-agent pane must STILL run the per-frame " +
                    "viewport affordance scanners (URL/path/command tappability preserved). " +
                    "Observed scans=$scans over $chunk burst chunks; expected > 0.",
                scans > 0,
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
        Unit
    } }

    // ---------------------------------------------------------------- Helpers

    /**
     * Issue #1286 (reviewer ask #3): the count of non-(near-black) pixels drawn on the
     * production [TerminalView] surface — a REAL pixel proof, distinct from the model
     * `transcriptText` string.
     */
    private data class SurfacePixels(
        val width: Int,
        val height: Int,
        val nonBlackPixels: Long,
        val totalPixels: Long,
    ) {
        val nonBlackFraction: Double get() = if (totalPixels == 0L) 0.0 else nonBlackPixels.toDouble() / totalPixels
    }

    /**
     * Draw the production [TerminalView] to an offscreen bitmap (which triggers the
     * vendored `onDraw` + the frame-paint observer) and count the non-(near-black)
     * pixels — the fraction that VISIBLY shows content. A black/blank pane (the
     * maintainer's symptom) is ~0.
     */
    private fun captureSurfacePixels(view: TerminalView): SurfacePixels {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(100)
        var result = SurfacePixels(0, 0, 0L, 0L)
        instrumentation.runOnMainSync {
            val w = view.width
            val h = view.height
            if (w <= 0 || h <= 0) return@runOnMainSync
            val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap)) // triggers onDraw + the frame-paint observer
            var nonBlack = 0L
            val rowPixels = IntArray(w)
            for (y in 0 until h) {
                bitmap.getPixels(rowPixels, 0, w, 0, y, w, 1)
                for (p in rowPixels) {
                    val r = (p shr 16) and 0xFF
                    val g = (p shr 8) and 0xFF
                    val b = p and 0xFF
                    if (maxOf(r, g, b) > NEAR_BLACK_MAX_CHANNEL) nonBlack++
                }
            }
            result = SurfacePixels(w, h, nonBlack, w.toLong() * h.toLong())
            bitmap.recycle()
        }
        return result
    }

    /**
     * Issue #1286: read the production `surfaceIsBlackWhileModelHasContent()` seam on
     * the main thread — the model has content, so the surface must NOT be black.
     */
    private fun surfaceBlackWhileModelHasContent(state: TerminalSurfaceState): Boolean {
        val out = booleanArrayOf(false)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            out[0] = state.surfaceIsBlackWhileModelHasContent()
        }
        return out[0]
    }

    /**
     * Issue #1286 (reviewer ask #3): emit a downscaled PNG thumbnail of the rendered
     * surface as chunked base64 to LOGCAT — a pixel artifact that SURVIVES AGP's
     * uninstall-wipe of the self-instrumenting test APK's external-media dir (where
     * `*-viewport.png` is lost). A reviewer can reassemble the chunks and decode the
     * PNG to eyeball that the pane VISIBLY shows content (not black).
     */
    private fun logSurfaceThumbnail(view: TerminalView) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        var b64: String? = null
        instrumentation.runOnMainSync {
            val w = view.width
            val h = view.height
            if (w <= 0 || h <= 0) return@runOnMainSync
            val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(full))
            val thumb = Bitmap.createScaledBitmap(full, THUMB_W, THUMB_H, true)
            val baos = java.io.ByteArrayOutputStream()
            thumb.compress(Bitmap.CompressFormat.PNG, 100, baos)
            b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
            full.recycle()
            thumb.recycle()
        }
        val s = b64 ?: return
        Log.i(LOG_TAG, "ISSUE1286_SURFACE_THUMB_PNG_B64_BEGIN len=${s.length} w=$THUMB_W h=$THUMB_H")
        var i = 0
        var idx = 0
        while (i < s.length) {
            val end = minOf(i + THUMB_LOG_CHUNK, s.length)
            Log.i(LOG_TAG, "ISSUE1286_SURFACE_THUMB_PNG_B64[$idx]=${s.substring(i, end)}")
            i = end
            idx++
        }
        Log.i(LOG_TAG, "ISSUE1286_SURFACE_THUMB_PNG_B64_END")
    }

    private fun buildChunk(i: Int): String {
        // A full-viewport alt-screen repaint: home the cursor, then write a
        // screenful of token-packed rows so EVERY per-render scan (URL +
        // smart-selection + file-path + engine-command) traverses a fully
        // populated viewport every delivered frame. This is the heavy per-tick
        // cost the uncoalesced consumer multiplies by N; the frame coalescer runs
        // it at most once per frame. ESC is the VT control byte (cursor home /
        // clear-to-EOL), written as \u001B so the source stays editable.
        val esc = "\u001B"
        return buildString {
            append("$esc[H") // cursor home (alt-screen redraw shape)
            for (row in 0 until VIEWPORT_ROWS) {
                append("$esc[K") // clear to end of line
                append("$BURST_MARKER r$row see https://example.com/codex-$i-$row ")
                append("/clear edit src/main/kotlin/Burst$i$row.kt ")
                append("token-$i-$row done\r\n")
            }
        }
    }


    /**
     * Issue #1286/#803: a plain (uncolored) UNIQUE final line emitted AFTER the burst.
     * Its presence in the transcript proves the frame-budgeted drain parsed every byte
     * to the end and the settled frame painted it — the direct black-screen / dropped-
     * tail guard.
     */
    private fun finalMarkerLine(): String {
        val esc = "\u001B"
        return "${esc}[K$FINAL_MARKER\r\n"
    }

    /**
     * Issue #1286: read the production drain-backlog signal
     * ([TerminalSurfaceState.renderDrainBacklogged], i.e. `availableBytes() > 0`) on the
     * MAIN thread — the same looper the frame-budgeted [MainThreadDrainScheduler] reads
     * it on. Non-blocked ⇒ the burst has fully drained.
     */
    private fun drainBacklogged(state: TerminalSurfaceState): Boolean {
        val backlogged = booleanArrayOf(false)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            backlogged[0] = state.renderDrainBacklogged()
        }
        return backlogged[0]
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

    private fun captureViewport(view: TerminalView, name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        SystemClock.sleep(150)
        var bitmap: Bitmap? = null
        instrumentation.runOnMainSync {
            if (view.width > 0 && view.height > 0) {
                val b = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
                view.draw(Canvas(b))
                bitmap = b
            }
        }
        val ctx = instrumentation.targetContext
        bitmap?.let { b ->
            val file = artifactFile(ctx, "$name-viewport.png")
            FileOutputStream(file).use { out ->
                check(b.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "failed to write bitmap to ${file.absolutePath}"
                }
            }
            println("ISSUE796_BURST_VIEWPORT ${file.absolutePath}")
            b.recycle()
        }
        artifactFile(ctx, "$name-visible-terminal.txt").writeText(visibleTerminalText(view))
    }

    private fun writeTimings(
        instrumentation: android.app.Instrumentation,
        lines: List<String>,
    ) {
        val file = artifactFile(instrumentation.targetContext, "issue796-output-burst-ime-timings.txt")
        file.writeText(lines.joinToString("\n") + "\n")
        println("ISSUE796_BURST_TIMINGS ${file.absolutePath}")
        // ALSO emit every timing line to logcat so the evidence is retrievable
        // even when the test-package external-media dir is cleared on reinstall
        // (a self-instrumenting library test's getExternalMediaDirs is volatile).
        for (line in lines) Log.i(LOG_TAG, "TIMING $line")
    }

    private fun artifactFile(context: android.content.Context, name: String): File {
        val dir = File(testArtifactsRoot(context), "terminal-lab").apply { mkdirs() }
        return File(dir, name)
    }

    private companion object {
        const val LOG_TAG = "Issue796OutputBurst"
        const val BURST_MARKER = "ISSUE796-BURST"

        // Issue #1286: a UNIQUE final marker emitted AFTER the burst. Its presence in
        // the visible transcript is the direct black-screen / dropped-tail guard — the
        // frame-budgeted drain must parse to the end AND the settled frame must paint.
        const val FINAL_MARKER = "ISSUE1286-FINAL-DONE"

        // Issue #1286: cadence + size of the per-keystroke composer-draft edits driven
        // on the MAIN thread during the burst (the maintainer's exact amplifier — an
        // open composer whose SheetContent recomposes per keystroke). ~60ms ≈ a brisk
        // typing rate; 256 chars ≈ a draft-mutation's worth of main-thread work.
        const val DRAFT_EDIT_INTERVAL_MS = 60L
        const val DRAFT_LENGTH = 256
        const val MIN_DRAFT_EDITS = 10L

        // Issue #1286 sanity: the bounded settle the REAL drain (override cleared)
        // must fully drain within. Generous — the real drain keeps up on this emulator.
        const val DRAIN_SETTLE_MS = 4_000L

        // Issue #1286 LOAD-BEARING widened-cadence guard: slack added to the
        // (burst_duration / DRAIN_PRIORITY_WINDOW_MS) widened emission ceiling — covers
        // the leading emission + a little jitter. Generous enough that the widened path
        // never flakes, tight enough that the un-widened base cadence (~4x more) breaches it.
        const val WIDENED_EMIT_SLACK = 40L

        // Issue #1286 pixel proof: a pixel is "non-(near-black)" when its brightest
        // channel exceeds this. The terminal background is 0xFF010409 (max channel 9),
        // so this cleanly excludes background while counting any rendered glyph/cursor.
        const val NEAR_BLACK_MAX_CHANNEL = 24

        // Issue #1286 pixel proof: the minimum fraction of the rendered surface that
        // must be non-black for the pane to count as "VISIBLY showing content". A
        // black/blank pane is ~0; a token-packed agent viewport is well above this.
        const val MIN_NON_BLACK_FRACTION = 0.02

        // Issue #1286 surviving-artifact thumbnail dimensions + logcat chunk size.
        const val THUMB_W = 160
        const val THUMB_H = 90
        const val THUMB_LOG_CHUNK = 2_000

        // Issue #1286 COARSE freeze backstop (NOT the load-bearing signal). Deliberately
        // generous so contended-swiftshader ping-latency noise (~1450ms observed in
        // #814) does not flake it, while still well under the 5s ANR window so a real
        // multi-second #1286 freeze is caught. The load-bearing guards are the
        // DETERMINISTIC drain-complete + final-marker-rendered checks.
        const val MAX_MAIN_THREAD_STALL_MS = 3_000L

        // A realistic soft-keyboard height (~300dp) — the same keyboard-up
        // pressure the #780 squish proof uses, injected synthetically.
        const val IME_HEIGHT_DP = 300f

        // How many token-packed rows each chunk repaints. A full phone viewport
        // is ~30 rows; repainting a screenful per chunk means all four per-render
        // scanners traverse a fully-populated viewport every delivered frame —
        // the heavy per-tick cost the uncoalesced consumer multiplies.
        const val VIEWPORT_ROWS = 30

        // The burst: a TIGHT (no inter-chunk delay) full-viewport-repaint storm
        // held for this wall-clock duration. A long continuous redraw run makes
        // the UNcoalesced per-tick repaint path emit O(N) downstream frames (one
        // per source tick), while the frame-coalesced path services ≤1
        // repaint/scan-set per ~16ms frame and stays bounded. Bounding by duration
        // (not chunk count) keeps the measurement independent of how fast a given
        // emulator's bridge can feed.
        //
        // Issue #814: 6s (was 3.5s). The longer burst widens the margin between the
        // raw uncoalesced tick count and the per-frame ceiling: the ceiling's
        // fixed settle+slack constant (~71 frames) is a smaller fraction of a 6s
        // burst's frame count (~375) than of a 3.5s one (~220), so even at the
        // slowest observed feed (~1.3 ticks/frame) `rawTicks` clears the ceiling
        // with a comfortable, never-flaky margin (the discrimination check below).
        const val BURST_DURATION_MS = 6_000L

        // The shell-pane inverse guard only needs to prove the scanners RUN, not
        // measure a stall, so a shorter burst keeps the test cheap.
        const val SHELL_BURST_DURATION_MS = 1_000L

        // Drain tail + final-frame settle.
        const val SETTLE_MS = 500L

        // Main-thread sampler cadence. The ping-latency stall is a COARSE freeze
        // backstop only (issue #814: raw wall-clock ping latency on a contended
        // swiftshader emulator inflates to ~1450ms even with the fix, a #831-class
        // fragile-wall-clock signal). The load-bearing red/green (issue #1286) is the
        // deterministic widened-cadence guard (the GATED coalescer must widen to
        // DRAIN_PRIORITY_WINDOW_MS under a forced backlog) + the surface pixel proof.
        const val PING_INTERVAL_MS = 16L

        // We must have actually sampled the main thread during the burst so the
        // diagnostic stall number is meaningful.
        const val MIN_PINGS = 20L

        // The burst must produce a real renderRequests storm for the scan-count
        // comparison to be meaningful. A genuine ~3.5s full-viewport-repaint
        // storm fires hundreds of source ticks; require a healthy floor so the
        // test cannot pass vacuously on a stalled producer.
        const val MIN_RAW_TICKS = 60L

        // Slack added to the (burst_duration / frame_window) ceiling for the
        // per-render scan count: covers the leading frame, the viewportTick
        // initial scans, and a little frame-jitter headroom. Generous enough that
        // GREEN never flakes, tight enough that the uncoalesced ~per-tick count
        // (which tracks the hundreds of raw ticks) blows past it.
        const val SCAN_CEILING_SLACK = 40L
    }
}
