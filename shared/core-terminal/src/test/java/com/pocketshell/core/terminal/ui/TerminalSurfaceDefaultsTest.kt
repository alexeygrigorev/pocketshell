package com.pocketshell.core.terminal.ui

import android.content.Context
import android.graphics.drawable.ColorDrawable
import androidx.test.core.app.ApplicationProvider
import androidx.compose.ui.graphics.toArgb
import com.termux.view.TerminalView
import com.termux.view.TerminalViewClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class TerminalSurfaceDefaultsTest {

    @Test
    fun applyPocketShellDefaults_initializesRendererBeforeTypeface() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val view = TerminalView(context, null)

        view.applyPocketShellDefaults(FakeTerminalViewClient)

        assertEquals(DefaultTerminalBackground.toArgb(), (view.background as ColorDrawable).color)
        assertTrue(view.isFocusable)
        assertTrue(view.isFocusableInTouchMode)
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
}
