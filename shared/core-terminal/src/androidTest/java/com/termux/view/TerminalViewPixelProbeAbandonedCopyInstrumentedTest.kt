package com.termux.view

import android.graphics.Bitmap
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.terminal.ui.TerminalSurface
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import com.pocketshell.core.terminal.ui.testArtifactsRoot
import com.pocketshell.testsupport.captureViewToBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicReference

/**
 * Issue #2003 — REAL-PATH regression for the app-process ABORT caused by the
 * #1443 diagnostic pixel probe recycling a bitmap the HWUI RenderThread still owns.
 *
 * ## The reported symptom (a real process death, not a test artefact)
 *
 * During a connected Conversation journey on `main` base `20ec66b1` the app
 * process died:
 *
 * ```
 * F HWUI  : Error, cannot access an invalid/free'd bitmap here!
 * F libc  : Fatal signal 6 (SIGABRT) ... tid 9598 (RenderThread)
 * I Zygote: Process 9555 exited due to signal 6 (Aborted)
 * ```
 *
 * The device's `tombstone_16` names the mechanism exactly:
 *
 * ```
 * Cmdline: com.pocketshell.app.i1995
 * pid: 9555, tid: 9598, name: RenderThread
 * Abort message: 'Error, cannot access an invalid/free'd bitmap here!'
 *   #04 libhwui.so android::bitmap::toBitmap(long)
 *   #05 libhwui.so android::CopyRequestAdapter::getDestinationBitmap(int, int)
 *   #06 libhwui.so android::uirenderer::Readback::copySurfaceInto(...)
 *   #07 libhwui.so RenderProxy::copySurfaceInto(...)
 *   #08 libhwui.so android::uirenderer::WorkQueue::process()
 *   #09 libhwui.so RenderThread::threadLoop()
 * ```
 *
 * That is `PixelCopy` — and PocketShell has exactly ONE `PixelCopy` call site:
 * [TerminalView.sampleSurfaceNearUniformBlack], the #1443 diagnostic
 * pixel-truth probe bound by `TerminalSurface` as the
 * `TerminalSurfaceState.SurfacePixelSampler`. `PixelCopy.request` is
 * ASYNCHRONOUS: it hands the destination bitmap to HWUI, which resolves it later
 * on the RenderThread via `getDestinationBitmap` -> `toBitmap`, and `toBitmap` is
 * `LOG_ALWAYS_FATAL` on a recycled bitmap. The base probe recycled the
 * destination in a `finally` that ALSO ran when the caller abandoned the wait —
 * so any probe whose 250 ms `latch.await` timed out on a loaded device (or whose
 * thread was interrupted) armed a guaranteed SIGABRT of the whole app process.
 * A diagnostic that must "NEVER destabilise the terminal" was killing the app.
 *
 * ## Red -> green (this test, on the REAL path)
 *
 * The production `TerminalSurface` is mounted in a real Activity window and the
 * probe is driven through the REAL `TerminalView.sampleSurfaceNearUniformBlack`
 * against the REAL window surface (no stand-in sampler — the whole symptom lives
 * in the native readback, so a fake sampler proves nothing, F2).
 *
 *  - PRECONDITION (hard, never skipped): a generous-timeout probe returns
 *    non-null, proving `PixelCopy` on this device actually reaches
 *    `getDestinationBitmap` with the destination bitmap. Without this the
 *    reproduction below could pass vacuously (G3).
 *  - RED: each abandoned probe (the caller stops waiting while the copy is still
 *    queued) recycles the destination on base, so the RenderThread aborts the
 *    process — the run dies with `Process crashed` and the same tombstone.
 *  - GREEN: with the ownership fix nothing recycles a bitmap HWUI still owns, the
 *    process survives every abandoned copy, and the probe still works afterwards.
 *
 * ## Class coverage (G2)
 *
 * Both ways the caller can abandon an in-flight copy through that one `finally`
 * are exercised: the `await` TIMEOUT (what the maintainer's run hit) and an
 * INTERRUPT of the probing thread (the probe runs on `Dispatchers.IO` under
 * `withContext`, so a cancelled watchdog scope interrupts it the same way).
 * A third path — `await` returning normally — must still recycle, and is covered
 * by the post-abandon probe still succeeding (the shared handler thread and
 * sampler stay healthy).
 *
 * Uses NO Docker fixture (in-process real TerminalSurface + real window).
 * Wired into `scripts/ci-journey-suite.sh` via the core-terminal proof registry.
 */
@RunWith(AndroidJUnit4::class)
class TerminalViewPixelProbeAbandonedCopyInstrumentedTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun abandonedPixelProbeNeverRecyclesTheBitmapTheRenderThreadStillOwns() { runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )
        val timeline = mutableListOf<String>()

        try {
            // The REAL production surface — the same composable that binds
            // `SurfacePixelSampler { view.sampleSurfaceNearUniformBlack() }`.
            compose.setContent {
                TerminalSurface(state = state, modifier = Modifier)
            }
            compose.waitForIdle()
            val view = waitForTerminalView()

            // Paint real content so the window has a genuine queued buffer for
            // PixelCopy to read back (an empty window can make the readback fail
            // early, before it ever resolves the destination bitmap).
            state.appendRemoteOutput(
                buildString {
                    repeat(12) { row -> append("ISSUE2003 pixel probe row %02d abcdefghij\r\n".format(row)) }
                }.toByteArray(Charsets.US_ASCII),
            )
            withTimeout(8_000) {
                while (true) {
                    compose.waitForIdle()
                    if (visibleTerminalText(view).contains("ISSUE2003 pixel probe row 00")) break
                    delay(40)
                }
            }
            compose.waitForIdle()
            instrumentation.waitForIdleSync()
            SystemClock.sleep(500)

            // ----------------------------------------------------------------
            // PRECONDITION (hard-fails; never an assume/skip): the production
            // probe really does drive PixelCopy to a SUCCESSful readback on this
            // device. If this is null the abandoned-probe reproduction below
            // could never have armed the abort and the whole red->green would be
            // vacuous (G3). A generous wait is used so a contended emulator's
            // slow readback cannot turn a real capability into a false skip.
            val baseline = withContext(Dispatchers.IO) {
                view.sampleSurfaceNearUniformBlack(GENEROUS_WAIT_MS)
            }
            assertNotNull(
                "#2003 precondition: the REAL PixelCopy probe must complete on this device " +
                    "(non-null) — otherwise the readback never resolves the destination bitmap " +
                    "and the abandoned-copy reproduction below would pass vacuously",
                baseline,
            )
            timeline += "precondition_probe_completed near_uniform_black=$baseline"

            // ----------------------------------------------------------------
            // REPRODUCTION A — the TIMEOUT abandon (the maintainer's run).
            // A 0 ms wait ALWAYS returns before the copy can finish: the request
            // has been handed to HWUI and is still queued on the RenderThread's
            // WorkQueue. On base the `finally` recycled the destination right
            // here, so `getDestinationBitmap` -> `toBitmap` aborted the process.
            repeat(ABANDONED_PROBES) { i ->
                val abandoned = withContext(Dispatchers.IO) {
                    view.sampleSurfaceNearUniformBlack(0L)
                }
                assertNull(
                    "#2003 reproduction A probe $i must have ABANDONED an in-flight PixelCopy " +
                        "(a 0 ms wait cannot outlast the RenderThread hop). A non-null result " +
                        "means the copy completed synchronously and the abort window was never " +
                        "entered — the reproduction would be vacuous",
                    abandoned,
                )
            }
            timeline += "timeout_abandoned_probes=$ABANDONED_PROBES"
            settleRenderThread(instrumentation, view)
            timeline += "survived_timeout_abandon"

            // ----------------------------------------------------------------
            // REPRODUCTION B — the INTERRUPT abandon. The production probe runs
            // inside `withContext(Dispatchers.IO)` from a cancellable watchdog
            // scope, so cancellation interrupts the blocked `latch.await` and
            // exits through the SAME `finally`. Pre-setting the interrupt makes
            // `await` throw immediately and deterministically, with the copy
            // certainly still in flight.
            repeat(ABANDONED_PROBES) { i ->
                val result = AtomicReference<Any?>(NOT_SET)
                val worker = Thread({
                    Thread.currentThread().interrupt()
                    result.set(view.sampleSurfaceNearUniformBlack(GENEROUS_WAIT_MS))
                }, "issue2003-interrupt-probe-$i")
                worker.start()
                worker.join(WORKER_JOIN_MS)
                assertTrue(
                    "#2003 reproduction B probe $i: the interrupted probe must return promptly " +
                        "instead of blocking (it exits through the same abandon path)",
                    !worker.isAlive,
                )
                assertNull(
                    "#2003 reproduction B probe $i must have ABANDONED an in-flight PixelCopy " +
                        "via InterruptedException — a non-null result means the interrupt never " +
                        "landed and the reproduction would be vacuous",
                    result.get(),
                )
            }
            timeline += "interrupt_abandoned_probes=$ABANDONED_PROBES"
            settleRenderThread(instrumentation, view)
            timeline += "survived_interrupt_abandon"

            // ----------------------------------------------------------------
            // GREEN — reaching here at all means the RenderThread drained every
            // abandoned CopyRequest WITHOUT hitting a freed bitmap: on base this
            // process is already dead (SIGABRT) and no assertion below can run.
            // The probe must also still be functional, proving the fix did not
            // simply disable the diagnostic or wedge its shared handler thread.
            val afterAbandon = withContext(Dispatchers.IO) {
                view.sampleSurfaceNearUniformBlack(GENEROUS_WAIT_MS)
            }
            assertNotNull(
                "#2003 GREEN: the #1443 pixel probe must still complete after abandoned copies " +
                    "— the fix is about bitmap ownership, not about disabling the diagnostic",
                afterAbandon,
            )
            timeline += "post_abandon_probe_completed near_uniform_black=$afterAbandon"

            // The terminal itself must be unharmed: the surface still carries the
            // painted content the probe was sampling.
            assertTrue(
                "#2003: the terminal must still render its content after the abandoned probes",
                visibleTerminalText(view).contains("ISSUE2003 pixel probe row 00"),
            )
            timeline += "terminal_content_intact"

            captureViewport(view, "issue2003-pixel-probe-abandoned-copy")
            writeArtifact(
                "issue2003-acceptance.txt",
                buildString {
                    appendLine("issue=2003")
                    appendLine("probe_call_site=TerminalView.sampleSurfaceNearUniformBlack (the only PixelCopy in the app)")
                    appendLine("abandoned_probes_per_path=$ABANDONED_PROBES")
                    appendLine("timeout_abandon_survived=true")
                    appendLine("interrupt_abandon_survived=true")
                    appendLine("probe_still_functional_after_abandon=true")
                    timeline.forEach { appendLine("timeline=$it") }
                },
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
        }
        Unit
    } }

    /**
     * Give the HWUI RenderThread ample opportunity to dequeue and run every
     * pending `CopyRequest`. On base the abort lands inside this window — the
     * process dies and nothing after it executes.
     */
    private fun settleRenderThread(
        instrumentation: android.app.Instrumentation,
        view: TerminalView,
    ) {
        repeat(RENDER_SETTLE_FRAMES) {
            instrumentation.runOnMainSync { view.invalidate() }
            instrumentation.waitForIdleSync()
            SystemClock.sleep(RENDER_SETTLE_STEP_MS)
        }
    }

    private suspend fun waitForTerminalView(): TerminalView {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val ref = arrayOfNulls<TerminalView>(1)
        withTimeout(15_000) {
            while (ref[0] == null) {
                instrumentation.runOnMainSync {
                    ref[0] = findTerminalView(compose.activity.window.decorView)
                }
                if (ref[0] == null) delay(20)
            }
        }
        val view = requireNotNull(ref[0])
        withTimeout(15_000) {
            while (true) {
                var sized = false
                instrumentation.runOnMainSync { sized = view.width > 0 && view.height > 0 }
                if (sized) break
                delay(20)
            }
        }
        return view
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
        println("ISSUE2003_VIEWPORT ${file.absolutePath}")
        captured.recycle()
        artifactFile("$name-visible-terminal.txt").writeText(visibleTerminalText(view))
    }

    private fun writeArtifact(name: String, text: String) {
        val file = artifactFile(name)
        file.writeText(text)
        println("ISSUE2003_ARTIFACT ${file.absolutePath}")
        // Mirror into logcat too: the instrumentation APK (and its external media
        // dir) is uninstalled when the run ends, so logcat is the evidence that
        // outlives the run and shows the assertions were not vacuous.
        for (line in text.trim().lines()) android.util.Log.i(LOG_TAG, line)
    }

    private fun artifactFile(name: String): File {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(testArtifactsRoot(context), "terminal-lab").apply { mkdirs() }
        return File(dir, name)
    }

    private companion object {
        const val LOG_TAG = "Issue2003PixelProbe"

        /** How many abandoned probes to fire per abandon path. */
        const val ABANDONED_PROBES = 8

        /**
         * A wait long enough that a contended swiftshader emulator's readback
         * cannot be mistaken for "PixelCopy does not work here" (the precondition
         * and the GREEN probe must never self-skip).
         */
        const val GENEROUS_WAIT_MS = 15_000L

        const val WORKER_JOIN_MS = 30_000L
        const val RENDER_SETTLE_FRAMES = 12
        const val RENDER_SETTLE_STEP_MS = 150L

        /** Sentinel so "the worker never assigned a result" is distinguishable from `null`. */
        val NOT_SET = Any()
    }
}
