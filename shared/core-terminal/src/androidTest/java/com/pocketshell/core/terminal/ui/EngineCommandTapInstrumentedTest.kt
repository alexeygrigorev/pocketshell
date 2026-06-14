package com.pocketshell.core.terminal.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.pocketshell.core.terminal.selection.EngineCommandRegion
import com.pocketshell.core.terminal.selection.findVisibleEngineCommands
import com.pocketshell.core.terminal.selection.hitTestEngineCommand
import com.termux.view.TerminalView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Issue #770 — connected coverage for tappable engine-command detection on the
 * terminal viewport.
 *
 * Renders a Claude-Code-style status hint line that mentions `/clear` (the
 * maintainer's motivating case), runs [findVisibleEngineCommands] with the
 * Claude command set to recover grid coordinates, hit-tests the centre of the
 * command's bounding box with [hitTestEngineCommand], and drives the same
 * `onTapMaybeUrl` tap hook the production [TerminalSurface] installs — asserting
 * the tapped command string `/clear` is delivered to the host (which opens the
 * prompt composer pre-filled with it). A non-command `/help` token on the same
 * line must NOT be tappable, proving the scan is catalog-scoped.
 *
 * Mirrors [FilePathTapInstrumentedTest]; driving the scanner + hit-test + tap
 * hook directly gives end-to-end coverage of the gesture-routing math without
 * pulling Compose pointer machinery into this module.
 */
@RunWith(AndroidJUnit4::class)
class EngineCommandTapInstrumentedTest {

    // A Claude-Code-shaped catalog slice (what the app passes from
    // AgentCommandCatalog.commandsFor(ClaudeCode)).
    private val claudeCommands = setOf("/clear", "/compact", "/rewind", "/model")

    @Test
    fun clearTokenIsTappableAndRoutesTheCommandWhileNonCommandFallsThrough() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = null,
        )
        val client = PocketShellTerminalViewClient()

        try {
            val viewRef = arrayOfNulls<TerminalView>(1)
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                viewRef[0] = view
            }
            val view = requireNotNull(viewRef[0])

            // A Claude status-hint line carrying the catalog command `/clear`
            // and a NON-catalog `/help` (a real Claude shortcut, but not in this
            // quick-send set) so we prove the affordance is catalog-scoped.
            val hintPrefix = "? for shortcuts  "
            val line = "$hintPrefix/clear to reset  /help for help\r\n"
            state.appendRemoteOutput(line.toByteArray(Charsets.US_ASCII))

            // Wait until the command is visible on the viewport.
            val commands = arrayOfNulls<List<EngineCommandRegion>>(1)
            withTimeout(3_000) {
                while ((commands[0]?.size ?: 0) < 1) {
                    delay(20)
                    instrumentation.runOnMainSync {
                        commands[0] = findVisibleEngineCommands(view, claudeCommands)
                    }
                }
            }
            val found = requireNotNull(commands[0])

            assertEquals(
                "scanner should surface exactly the one catalog command (/clear); " +
                    "the non-catalog /help must NOT be linked: $found",
                1,
                found.size,
            )
            val clearRegion = found.single()
            assertEquals("/clear", clearRegion.command)
            assertEquals("/clear starts after the hint prefix", hintPrefix.length, clearRegion.startCol)
            assertEquals(
                "/clear ends one past its last char",
                hintPrefix.length + "/clear".length,
                clearRegion.endColExclusive,
            )

            val renderer = view.mRenderer
            assertNotNull("renderer should be initialised after layout", renderer)
            val fontWidth = renderer!!.fontWidth
            val lineSpacing = renderer.fontLineSpacing.toFloat()
            val rowOffset = renderer.fontLineSpacingAndAscent.toFloat()

            fun centreX(region: EngineCommandRegion): Float =
                (region.startCol + (region.endColExclusive - region.startCol) / 2f) * fontWidth

            fun centreY(region: EngineCommandRegion): Float =
                rowOffset + (region.row - view.topRow + 0.5f) * lineSpacing

            // Centre-of-/clear tap resolves to the command.
            val clearHit = hitTestEngineCommand(view, found, centreX(clearRegion), centreY(clearRegion))
            assertNotNull("centre-of-/clear tap should resolve to the command", clearHit)
            assertEquals("/clear", clearHit!!.command)

            // A tap on the `/help` column must NOT resolve (it is not a catalog
            // command for this engine).
            val helpCol = line.indexOf("/help").toFloat()
            val helpMiss = hitTestEngineCommand(
                view,
                found,
                (helpCol + 1f) * fontWidth,
                centreY(clearRegion),
            )
            assertNull("tap on non-catalog /help must fall through", helpMiss)

            // End-to-end: install the production tap hook exactly the way
            // TerminalSurface wires onTapMaybeUrl when the host supplies
            // onEngineCommandTap, and synthesise single-tap-up gestures.
            val tappedCommands = mutableListOf<String>()
            client.onTapMaybeUrl = { x, y ->
                val hit = hitTestEngineCommand(view, found, x, y)
                if (hit != null) {
                    tappedCommands.add(hit.command)
                    true
                } else {
                    false
                }
            }
            try {
                instrumentation.runOnMainSync {
                    val now = SystemClock.uptimeMillis()
                    val tap = MotionEvent.obtain(
                        now,
                        now,
                        MotionEvent.ACTION_UP,
                        centreX(clearRegion),
                        centreY(clearRegion),
                        0,
                    )
                    try {
                        client.onSingleTapUp(tap)
                    } finally {
                        tap.recycle()
                    }
                }
                assertEquals(
                    "the /clear tap should reach the host (→ open composer pre-filled)",
                    listOf("/clear"),
                    tappedCommands,
                )

                instrumentation.runOnMainSync {
                    assertTrue(
                        "engine-command tap hook must return true so the composer action runs",
                        client.onTapMaybeUrl?.invoke(centreX(clearRegion), centreY(clearRegion)) ?: false,
                    )
                    assertFalse(
                        "non-command tap must return false so no composer action runs",
                        client.onTapMaybeUrl?.invoke(
                            (helpCol + 1f) * fontWidth,
                            centreY(clearRegion),
                        ) ?: true,
                    )
                }
            } finally {
                client.onTapMaybeUrl = null
            }

            instrumentation.runOnMainSync {
                saveViewportScreenshot(view, "issue-770-tappable-engine-command-viewport.png")
            }
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    /**
     * Render the live [TerminalView] off-screen to a bitmap and persist it under
     * `additional_test_output/issue-770/` for reviewer inspection. Non-fatal on
     * failure — the assertions are the contract.
     */
    private fun saveViewportScreenshot(view: TerminalView, fileName: String) {
        runCatching {
            val width = view.width.coerceAtLeast(1)
            val height = view.height.coerceAtLeast(1)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            view.draw(canvas)
            val ctx = InstrumentationRegistry.getInstrumentation().targetContext
            val mediaRoot = testArtifactsRoot(ctx)
            val dir = File(mediaRoot, "additional_test_output/issue-770")
            if (!dir.exists()) dir.mkdirs()
            val outFile = File(dir, fileName)
            FileOutputStream(outFile).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            println("ISSUE770_VIEWPORT ${outFile.absolutePath}")
        }
    }
}
