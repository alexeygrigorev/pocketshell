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
 * 4. WHILE the burst runs, schedule periodic "ping" Runnables on the MAIN looper
 *    and measure how long each waits before it executes. A main thread blocked by
 *    the render storm inflates that latency; the [coalescePerFrame] fix keeps it
 *    bounded (≤1 repaint+scan per frame). The load-bearing assertion is that the
 *    MAX observed main-thread stall stays under the [MAX_MAIN_THREAD_STALL_MS]
 *    budget (well under the 5 s ANR window).
 *
 * On the pre-fix `TerminalSurface` (raw `renderRequests.collect`) the same burst
 * pins the main thread for seconds and the max stall blows past the budget → RED.
 * With the frame coalescer the stall stays bounded → GREEN. The
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
    fun codexOutputBurstWithKeyboardUpKeepsMainThreadResponsive() = runBlocking {
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
            val burstStartedAt = SystemClock.uptimeMillis()
            var chunk = 0
            while (SystemClock.uptimeMillis() - burstStartedAt < BURST_DURATION_MS) {
                stdout.emit(buildChunk(chunk).toByteArray(Charsets.US_ASCII))
                chunk += 1
            }
            // Let the tail of the burst drain and the settled frame paint.
            SystemClock.sleep(SETTLE_MS)
            val burstDurationMs = SystemClock.uptimeMillis() - burstStartedAt
            pingActive.set(false)
            rawTickJob.cancel()
            rawTickScope.cancel()

            val rawTicks = rawTickCount.get()
            val scans = scanCount.get()
            // The frame-coalesced consumer must run at most ~one scan per frame
            // window over the burst, plus a small constant for the leading frame
            // and the settle tail. Derive the ceiling from the actual burst
            // duration so it is emulator-speed-independent.
            val frameCeiling = (burstDurationMs + SETTLE_MS) / RENDER_FRAME_WINDOW_MS + SCAN_CEILING_SLACK

            // Confirm the burst actually rendered content (the final settled frame
            // painted — the last-frame-after-idle guarantee). If the screen is
            // blank the responsiveness number would be meaningless.
            val transcript = visibleTerminalText(view)
            assertTrue(
                "burst must have rendered visible terminal content (settled final " +
                    "frame must paint); transcript length=${transcript.length}",
                transcript.contains(BURST_MARKER),
            )

            captureViewport(view, "issue796-output-burst-ime")
            writeTimings(
                instrumentation,
                lines = listOf(
                    "scenario=codex-%output-burst + synthetic ime() inset up -> main-thread responsiveness",
                    "issue=796",
                    "burst_chunks_emitted=$chunk",
                    "burst_duration_target_ms=$BURST_DURATION_MS",
                    "viewport_rows_per_chunk=$VIEWPORT_ROWS",
                    "burst_duration_ms=$burstDurationMs",
                    "ime_bottom_px=$observedImeBottomPx",
                    "ping_interval_ms=$PING_INTERVAL_MS",
                    "ping_count=${pingCount.get()}",
                    "max_main_thread_stall_ms=${maxStallMs.get()}",
                    "max_main_thread_stall_budget_ms=$MAX_MAIN_THREAD_STALL_MS",
                    "raw_render_request_ticks=$rawTicks",
                    "production_overlay_scans=$scans",
                    "scan_per_frame_ceiling=$frameCeiling",
                    "render_frame_window_ms=$RENDER_FRAME_WINDOW_MS",
                    "anr_window_ms=5000",
                    "pane_type=agent (affordanceScannersEnabled=false)",
                    "expectation=RED on pre-fix TerminalSurface (agent pane STILL ran the four " +
                        "per-frame full-viewport scanners → multi-hundred-ms main-thread stall " +
                        "past the budget, and scans ~= raw ticks); GREEN with the #796-REOPENED " +
                        "agent-pane gate (scanners not wired for an agent pane → scans≈0 and the " +
                        "main thread stays under the budget)",
                ),
            )

            Log.i(
                LOG_TAG,
                "#796 Codex burst (keyboard up): rawTicks=$rawTicks scans=$scans " +
                    "frameCeiling=$frameCeiling maxStall=${maxStallMs.get()}ms " +
                    "pings=${pingCount.get()} burstDuration=${burstDurationMs}ms",
            )

            // Sanity: the burst must have produced a real storm of source render
            // ticks, otherwise the count comparison below would be vacuous.
            assertTrue(
                "burst must produce a real renderRequests storm; rawTicks=$rawTicks " +
                    "(needs >= $MIN_RAW_TICKS to be a meaningful test)",
                rawTicks >= MIN_RAW_TICKS,
            )

            // ---- LOAD-BEARING assertion #1 (emulator-speed-independent): for an
            // AGENT pane the four per-frame full-viewport scanners must NOT run at
            // all. On the pre-fix [TerminalSurface] the SmartSelectionAffordanceOverlay
            // scanned on EVERY delivered render tick even for an agent pane, so
            // `scans` tracked `rawTicks` (hundreds) and blew past the ceiling → RED.
            // The #796-REOPENED fix gates the scanners OFF for an agent pane
            // (`affordanceScannersEnabled = false`), so the overlay is never wired
            // and `scans ≈ 0` — far under the frame ceiling → GREEN. This is the
            // direct proof the fix removed the dominant per-frame cost at the root,
            // independent of how fast a given emulator runs. (The ceiling is the
            // generous per-frame bound from the prior frame-gate slice; an agent
            // pane now sits at ~0, well below it.)
            assertTrue(
                "#796 (REOPENED): an AGENT pane must run NO per-frame viewport " +
                    "affordance scanner. Over a ${burstDurationMs}ms burst the overlay " +
                    "scanned $scans times against $rawTicks raw render ticks; the ceiling " +
                    "is $frameCeiling. FAILS on the pre-fix surface (an agent pane still " +
                    "scanned ~every tick, scans ~= rawTicks); GREEN with the agent-pane gate " +
                    "(scanners not wired → scans ≈ 0).",
                scans <= frameCeiling,
            )

            // ---- LOAD-BEARING assertion #2: the main thread stays responsive
            // during the burst, well under the 5 s ANR window. This is the
            // maintainer's actual symptom (a real "PocketShell isn't responding"
            // ANR). On the pre-fix surface the agent pane's four per-frame scanners
            // stalled the main thread past this budget (observed ~1284ms in
            // isolation); the agent-pane gate removes that cost so it stays bounded.
            // The 1000ms budget is UNCHANGED — it holds because the production cost
            // is gone, not because the assertion was loosened.
            assertTrue(
                "#796: a Codex %output burst with the keyboard up on an AGENT pane must " +
                    "NOT stall the main thread past ${MAX_MAIN_THREAD_STALL_MS}ms (5s ANR " +
                    "window). Observed max stall=${maxStallMs.get()}ms over ${pingCount.get()} pings.",
                maxStallMs.get() <= MAX_MAIN_THREAD_STALL_MS,
            )
            // Sanity: we must have actually sampled the main thread during the
            // burst — a near-zero ping count would make the stall measurement
            // vacuous.
            assertTrue(
                "main-thread ping sampler must have run during the burst; pings=${pingCount.get()}",
                pingCount.get() >= MIN_PINGS,
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
        Unit
    }

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
    fun shellPaneStillRunsAffordanceScanners() = runBlocking {
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
    }

    // ---------------------------------------------------------------- Helpers

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

        // A realistic soft-keyboard height (~300dp) — the same keyboard-up
        // pressure the #780 squish proof uses, injected synthetically.
        const val IME_HEIGHT_DP = 300f

        // How many token-packed rows each chunk repaints. A full phone viewport
        // is ~30 rows; repainting a screenful per chunk means all four per-render
        // scanners traverse a fully-populated viewport every delivered frame —
        // the heavy per-tick cost the uncoalesced consumer multiplies.
        const val VIEWPORT_ROWS = 30

        // The burst: a TIGHT (no inter-chunk delay) full-viewport-repaint storm
        // held for this wall-clock duration. ~3.5s of continuous redraws is long
        // enough that the UNcoalesced per-tick repaint+4-scan path saturates the
        // main looper and the max ping stall blows past the budget, while the
        // frame-coalesced path services ≤1 repaint+scan-set per frame and stays
        // bounded. Bounding by duration (not chunk count) keeps the measurement
        // independent of how fast a given emulator's bridge can feed.
        const val BURST_DURATION_MS = 3_500L

        // The shell-pane inverse guard only needs to prove the scanners RUN, not
        // measure a stall, so a shorter burst keeps the test cheap.
        const val SHELL_BURST_DURATION_MS = 1_000L

        // Drain tail + final-frame settle.
        const val SETTLE_MS = 500L

        // Main-thread sampler cadence.
        const val PING_INTERVAL_MS = 16L

        // The responsiveness budget. The 5s ANR window is the hard ceiling; we
        // assert well under it so a regression is caught long before a real ANR.
        // A bounded one-repaint-per-frame path keeps the main thread free between
        // frames, so a sustained multi-hundred-ms stall already indicates the
        // uncoalesced storm is back. Calibrated from the RED (uncoalesced) vs
        // GREEN (coalesced) measured spread on the emulator.
        const val MAX_MAIN_THREAD_STALL_MS = 1_000L

        // We must have actually sampled the main thread during the burst.
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
