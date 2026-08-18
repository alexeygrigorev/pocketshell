package com.pocketshell.core.terminal.ui

import android.content.Context
import android.graphics.Color
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.testsupport.captureViewToBitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Issue #2206 — the leftover `:shared:core-terminal` capture helper must
 * hard-fail on a 0x0 view instead of returning quietly.
 *
 * Seven instrumented tests used to do:
 *
 * ```
 * if (view.width > 0 && view.height > 0) { view.draw(...) }
 * bitmap?.let { write png }
 * ```
 *
 * When TerminalView has collapsed to 0x0 — the failure state worth
 * capturing — that branch writes no `*-viewport.png` and the test can
 * still pass. This class is the D32 proof that the shared-module sibling
 * of the #2135 helper does not reproduce that hole.
 *
 * ## The mutation that must redden this test (D32/G6)
 *
 * Restore the silent-skip: make [captureViewToBitmap] return null (or
 * return a dummy bitmap without throwing) when `width <= 0 || height <= 0`.
 * [captureViewToBitmap_failsOnZeroByZeroViewRatherThanReturningQuietly]
 * then fails because no [AssertionError] is thrown.
 */
@RunWith(RobolectricTestRunner::class)
class CaptureViewToBitmapTest {

    private fun newView(): View = View(ApplicationProvider.getApplicationContext<Context>())

    @Test
    fun captureViewToBitmap_failsOnZeroByZeroViewRatherThanReturningQuietly() {
        val view = newView()
        assertEquals("unlaid-out View must start 0x0 so this is the reported hole", 0, view.width)
        assertEquals(0, view.height)

        val error = runCatching { captureViewToBitmap(view, "issue2206-zero") }.exceptionOrNull()

        assertNotNull(
            "a 0x0 view must hard-fail the capture; a silent return is the #2206 hole",
            error,
        )
        assertTrue(
            "expected AssertionError, got ${error!!::class.java.name}: ${error.message}",
            error is AssertionError,
        )
        val message = error.message.orEmpty()
        assertTrue(
            "diagnostic must name measured size: $message",
            message.contains("measured=0x0"),
        )
        assertTrue(
            "diagnostic must name on-screen rect: $message",
            message.contains("onScreenRect="),
        )
        assertTrue(
            "diagnostic must name blank-frame detection: $message",
            message.contains("drawnFrameIsUniformBlank="),
        )
        assertTrue(
            "failure must name the requested artifact: $message",
            message.contains("issue2206-zero"),
        )
    }

    @Test
    fun captureViewToBitmap_failsOnMissingView() {
        val error = runCatching {
            captureViewToBitmap(null, "issue2206-missing")
        }.exceptionOrNull()

        assertNotNull("a missing view must hard-fail, not return quietly", error)
        assertTrue(
            "expected AssertionError, got ${error!!::class.java.name}: ${error.message}",
            error is AssertionError,
        )
        val message = error.message.orEmpty()
        assertTrue("diagnostic must name a missing view: $message", message.contains("viewFound=false"))
        assertTrue("failure must name the requested artifact: $message", message.contains("issue2206-missing"))
    }

    @Test
    fun captureViewToBitmap_drawsALaidOutView() {
        val view = newView().apply {
            setBackgroundColor(Color.RED)
            measure(
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
        val bitmap = captureViewToBitmap(view, "issue2206-sized")
        try {
            assertEquals(48, bitmap.width)
            assertEquals(32, bitmap.height)
            assertEquals(Color.RED, bitmap.getPixel(0, 0))
        } finally {
            bitmap.recycle()
        }
    }

    /**
     * Criterion: none of the seven leftover FQCNs may keep a private
     * silent-skip capture. Restoring
     * `if (view.width > 0 && view.height > 0) { view.draw(...) }` in any
     * of them must redden this test.
     */
    @Test
    fun leftoverCoreTerminalCapturesNoLongerSilentSkipAZeroByZeroView() {
        val androidTestRoot = leftoverAndroidTestRoot()
        val leftovers = listOf(
            "com/termux/view/TerminalSelectionViewportStabilityInstrumentedTest.kt",
            "com/termux/view/TerminalViewPixelProbeAbandonedCopyInstrumentedTest.kt",
            "com/pocketshell/core/terminal/ui/CodexMultiChunkSeedAttachMainThreadProofTest.kt",
            "com/pocketshell/core/terminal/ui/ShellPaneAffordanceSingleSnapshotProofTest.kt",
            "com/pocketshell/core/terminal/ui/CodexOutputBurstImeMainThreadProofTest.kt",
            "com/pocketshell/core/terminal/ui/AgentPaneLinkAffordanceOffMainProofTest.kt",
            "com/pocketshell/core/terminal/ui/CodexAppendBurstMainThreadProofTest.kt",
        )
        val silentSkip = Regex(
            """if\s*\(\s*\w+\.width\s*>\s*0\s*&&\s*\w+\.height\s*>\s*0\s*\)""",
        )
        leftovers.forEach { relative ->
            val file = File(androidTestRoot, relative)
            assertTrue("leftover source missing: ${file.path}", file.isFile)
            val source = file.readText()
            assertFalse(
                "${file.name} still has a silent-skip 0x0 capture; migrate it to captureViewToBitmap (#2206)",
                silentSkip.containsMatchIn(source),
            )
            assertTrue(
                "${file.name} must call the shared hard-fail helper",
                source.contains("captureViewToBitmap("),
            )
        }
    }

    private fun leftoverAndroidTestRoot(): File {
        val marker = "com/termux/view/TerminalSelectionViewportStabilityInstrumentedTest.kt"
        val userDir = requireNotNull(System.getProperty("user.dir")) { "user.dir is unset" }
        var dir = File(userDir).absoluteFile
        repeat(6) {
            val candidate = File(dir, "src/androidTest/java")
            if (File(candidate, marker).isFile) return candidate
            dir = dir.parentFile ?: return@repeat
        }
        throw AssertionError(
            "could not locate core-terminal androidTest sources from user.dir=$userDir",
        )
    }
}
