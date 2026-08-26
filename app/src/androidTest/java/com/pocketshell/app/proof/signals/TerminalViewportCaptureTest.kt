package com.pocketshell.app.proof.signals

import android.graphics.Color
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2135 — the shared terminal-viewport capture must hard-fail on a
 * 0x0 view instead of returning quietly.
 *
 * Forty-eight androidTest classes copy-pasted:
 *
 * ```
 * if (view.width <= 0 || view.height <= 0) return@onActivity
 * ```
 *
 * When TerminalView has collapsed to 0x0 — the failure state worth
 * capturing — that branch writes no `*-viewport.png` and the test can
 * still pass. This class is the D32 proof that the extracted helper
 * does not reproduce that hole.
 *
 * ## The mutation that must redden this test (D32/G6)
 *
 * Restore the silent-skip: make [captureViewToBitmap] return a dummy
 * bitmap (or return without throwing) when `width <= 0 || height <= 0`.
 * [captureViewToBitmap_failsOnZeroByZeroViewRatherThanReturningQuietly]
 * then fails because no [AssertionError] is thrown.
 * The same mutation against [captureSessionFrameToBitmap] must redden
 * [captureSessionFrameToBitmap_failsOnMissingViewRatherThanReturningQuietly].
 */
@RunWith(AndroidJUnit4::class)
class TerminalViewportCaptureTest {

    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun captureViewToBitmap_failsOnZeroByZeroViewRatherThanReturningQuietly() {
        val view = View(compose.activity)
        assertEquals("unlaid-out View must start 0x0 so this is the reported hole", 0, view.width)
        assertEquals(0, view.height)

        val error = runCatching { captureViewToBitmap(view, "issue2135-zero") }.exceptionOrNull()

        assertNotNull(
            "a 0x0 view must hard-fail the capture; a silent return is the #2135 hole",
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
            message.contains("issue2135-zero"),
        )
    }

    @Test
    fun captureViewToBitmap_failsOnMissingView() {
        val error = runCatching {
            captureViewToBitmap(null, "issue2135-missing")
        }.exceptionOrNull()

        assertNotNull("a missing view must hard-fail, not return quietly", error)
        assertTrue(
            "expected AssertionError, got ${error!!::class.java.name}: ${error.message}",
            error is AssertionError,
        )
        val message = error.message.orEmpty()
        assertTrue("diagnostic must name a missing view: $message", message.contains("viewFound=false"))
        assertTrue("failure must name the requested artifact: $message", message.contains("issue2135-missing"))
    }

    @Test
    fun captureViewToBitmap_drawsALaidOutView() {
        val view = View(compose.activity).apply {
            setBackgroundColor(Color.RED)
            measure(
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
        val bitmap = captureViewToBitmap(view, "issue2135-sized")
        try {
            assertEquals(48, bitmap.width)
            assertEquals(32, bitmap.height)
            assertEquals(Color.RED, bitmap.getPixel(0, 0))
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun captureSessionFrameToBitmap_failsOnMissingViewRatherThanReturningQuietly() {
        val error = runCatching {
            captureSessionFrameToBitmap(null, "issue2297-session-missing")
        }.exceptionOrNull()

        assertNotNull("a missing session frame must hard-fail, not return quietly", error)
        assertTrue(
            "expected AssertionError, got ${error!!::class.java.name}: ${error.message}",
            error is AssertionError,
        )
        assertTrue(
            "failure must identify the session-frame artifact: ${error.message}",
            error.message.orEmpty().contains("issue2297-session-missing"),
        )
    }

    @Test
    fun captureSessionFrameToBitmap_failsOnZeroByZeroViewRatherThanReturningQuietly() {
        val view = View(compose.activity)
        val error = runCatching {
            captureSessionFrameToBitmap(view, "issue2297-session-zero")
        }.exceptionOrNull()

        assertNotNull("a 0x0 session frame must hard-fail, not return quietly", error)
        assertTrue(error is AssertionError)
        assertTrue(
            "failure must identify the session-frame artifact: ${error?.message}",
            error?.message.orEmpty().contains("issue2297-session-zero"),
        )
    }

    @Test
    fun captureSessionFrameToBitmap_drawsALaidOutView() {
        val view = View(compose.activity).apply {
            setBackgroundColor(Color.BLUE)
            measure(
                View.MeasureSpec.makeMeasureSpec(24, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(16, View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
        val bitmap = captureSessionFrameToBitmap(view, "issue2297-session-sized")
        try {
            assertEquals(24, bitmap.width)
            assertEquals(16, bitmap.height)
            assertEquals(Color.BLUE, bitmap.getPixel(0, 0))
        } finally {
            bitmap.recycle()
        }
    }
}
