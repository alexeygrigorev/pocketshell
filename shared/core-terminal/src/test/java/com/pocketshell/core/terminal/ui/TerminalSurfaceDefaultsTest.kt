package com.pocketshell.core.terminal.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.graphics.toArgb
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class TerminalSurfaceDefaultsTest {

    @Test
    fun applyPocketShellDefaults_initializesRendererBeforeTypeface() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context, null)

        view.applyPocketShellDefaults(FakeTerminalViewClient)

        assertEquals(DefaultTerminalBackground.toArgb(), (view.background as ColorDrawable).color)
        assertEquals(DEFAULT_TEXT_SIZE_RAW_PX, view.appliedRendererTextSize())
        assertMonospace(view.appliedRendererTypeface())
        assertTrue(view.isFocusable)
        assertTrue(view.isFocusableInTouchMode)
    }

    @Test
    fun unattachedTerminalCanvasUsesPocketShellBackground() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context, null)
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)

        view.applyPocketShellDefaults(FakeTerminalViewClient)
        view.layout(0, 0, bitmap.width, bitmap.height)
        view.draw(Canvas(bitmap))

        assertEquals(
            "blank terminal frames should match the attached terminal background",
            DefaultTerminalBackground.toArgb(),
            bitmap.getPixel(0, 0),
        )
    }

    @Test
    fun pocketShellClientDoesNotRequestSoftKeyboardOnTerminalTap() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context, null)
        val client = PocketShellTerminalViewClient()
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        view.applyPocketShellDefaults(client)
        client.onSingleTapUp(null)

        assertFalse("terminal tap client must not request TerminalView focus", view.isFocused)
        assertFalse(
            "terminal tap client must not ask Android to show the soft keyboard",
            shadowOf(inputMethodManager).isSoftInputVisible,
        )
    }

    @Test
    fun showTerminalSoftKeyboardRequestsSoftKeyboardExplicitly() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context, null)
        val client = PocketShellTerminalViewClient()
        val inputMethodManager = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager

        view.applyPocketShellDefaults(client)

        assertTrue(showTerminalSoftKeyboard(view))
        assertTrue("explicit show-keyboard helper should focus the TerminalView", view.isFocused)
        assertTrue(
            "explicit show-keyboard helper should ask Android to show the soft keyboard",
            shadowOf(inputMethodManager).isSoftInputVisible,
        )
    }

    @Test
    fun rawCommandKeyboardUsesNoSuggestionsVisiblePasswordInput() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context, null)
        val client = PocketShellTerminalViewClient()
        val editorInfo = EditorInfo()

        view.applyPocketShellDefaults(client)
        view.onCreateInputConnection(editorInfo)

        assertEquals(
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
            editorInfo.inputType,
        )
    }

    @Test
    fun smartTextKeyboardUsesAutocorrectTextInput() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context, null)
        val client = PocketShellTerminalViewClient().apply {
            terminalKeyboardMode = TerminalKeyboardMode.SmartText
        }
        val editorInfo = EditorInfo()

        view.applyPocketShellDefaults(client)
        view.onCreateInputConnection(editorInfo)

        assertEquals(
            InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_VARIATION_NORMAL or
                InputType.TYPE_TEXT_FLAG_AUTO_CORRECT,
            editorInfo.inputType,
        )
    }

    @Test
    fun terminalViewCoalescesRenderInvalidationsToFrame() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context, null)

        view.applyPocketShellDefaults(FakeTerminalViewClient)
        val frameCountBefore = view.getCoalescedRenderInvalidationFramesForTesting()
        val requestCountBefore = view.getPendingRenderInvalidationRequestsForTesting()

        view.requestRenderInvalidationForTesting()
        view.requestRenderInvalidationForTesting()
        view.requestRenderInvalidationForTesting()

        assertTrue(
            "screen updates in the same loop should leave one render invalidation pending",
            view.hasPendingRenderInvalidationForTesting(),
        )
        assertEquals(
            "each screen update is tracked for smoothness metrics",
            requestCountBefore + 3,
            view.getPendingRenderInvalidationRequestsForTesting(),
        )
        assertEquals(
            "coalesced frame must not run before the choreographer tick",
            frameCountBefore,
            view.getCoalescedRenderInvalidationFramesForTesting(),
        )

        view.drainPendingRenderInvalidationForTesting()

        assertEquals(
            "multiple screen updates should collapse to one frame invalidation",
            frameCountBefore + 1,
            view.getCoalescedRenderInvalidationFramesForTesting(),
        )
    }

    private object FakeTerminalViewClient : TerminalViewClient {
        override fun onScale(scale: Float): Float = 1.0f
        override fun onSingleTapUp(event: android.view.MotionEvent?) = Unit
        override fun shouldBackButtonBeMappedToEscape(): Boolean = false
        override fun shouldEnforceCharBasedInput(): Boolean = true
        override fun shouldUseCtrlSpaceWorkaround(): Boolean = false
        override fun isTerminalViewSelected(): Boolean = true
        override fun copyModeChanged(copyMode: Boolean) = Unit
        override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?, session: com.termux.terminal.TerminalSession?): Boolean = false
        override fun onKeyUp(keyCode: Int, event: android.view.KeyEvent?): Boolean = false
        override fun onLongPress(event: android.view.MotionEvent?): Boolean = false
        override fun readControlKey(): Boolean = false
        override fun readAltKey(): Boolean = false
        override fun readShiftKey(): Boolean = false
        override fun readFnKey(): Boolean = false
        override fun onCodePoint(codePoint: Int, ctrlDown: Boolean, session: com.termux.terminal.TerminalSession?): Boolean = false
        override fun onEmulatorSet() = Unit
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private fun TerminalView.appliedRendererTextSize(): Int {
        val renderer = TerminalView::class.java.getField("mRenderer").get(this)
        val textSize = renderer.javaClass.getDeclaredField("mTextSize")
        textSize.isAccessible = true
        return textSize.getInt(renderer)
    }

    private fun TerminalView.appliedRendererTypeface(): Typeface {
        val renderer = TerminalView::class.java.getField("mRenderer").get(this)
        val typeface = renderer.javaClass.getDeclaredField("mTypeface")
        typeface.isAccessible = true
        return typeface.get(renderer) as Typeface
    }

    private fun assertMonospace(typeface: Typeface) {
        val paint = Paint().apply {
            this.typeface = typeface
            textSize = DEFAULT_TEXT_SIZE_RAW_PX.toFloat()
        }

        assertEquals(
            "terminal typeface must give narrow and wide ASCII glyphs the same cell width",
            paint.measureText("i"),
            paint.measureText("W"),
            0.01f,
        )
    }
}
