package com.pocketshell.core.terminal.ui

import android.app.Instrumentation
import android.text.InputType
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.OutputStream

/**
 * Emulator/device coverage for the path users exercise manually:
 * explicit keyboard control -> focus terminal -> IME input connection -> remote stdin.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSurfaceInputInstrumentedTest {

    @Test
    fun rawCommandImeCommitRoutesTextAndEnterToRemoteStdin() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val remoteStdin = RecordingOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = remoteStdin,
        )
        val client = PocketShellTerminalViewClient()

        try {
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)

                assertTrue("explicit keyboard path should focus the TerminalView", view.requestFocus())

                val editorInfo = EditorInfo()
                val inputConnection = requireNotNull(view.onCreateInputConnection(editorInfo))
                assertEquals(
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS,
                    editorInfo.inputType,
                )

                inputConnection.commitText("echo ok", 1)
                inputConnection.commitText("\n", 1)
            }

            withTimeout(2_000) {
                while (!remoteStdin.snapshot().contains("echo ok\r")) {
                    delay(10)
                }
            }
            assertEquals("echo ok\r", remoteStdin.snapshot())
            assertTrue("remote stdin should be flushed after IME input", remoteStdin.wasFlushed)
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun smartTextImeStagesCommittedTextUntilEnterConfirms() = runBlocking {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val remoteStdin = RecordingOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = remoteStdin,
        )
        val client = PocketShellTerminalViewClient().apply {
            terminalKeyboardMode = TerminalKeyboardMode.SmartText
        }

        try {
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                view.requestFocus()

                val editorInfo = EditorInfo()
                val inputConnection = requireNotNull(view.onCreateInputConnection(editorInfo))
                assertEquals(
                    InputType.TYPE_CLASS_TEXT or
                        InputType.TYPE_TEXT_VARIATION_NORMAL or
                        InputType.TYPE_TEXT_FLAG_AUTO_CORRECT,
                    editorInfo.inputType,
                )

                inputConnection.commitText("echo smart", 1)
                assertEquals(
                    "smart text mode must not write autocorrected text to the terminal before Enter",
                    "",
                    remoteStdin.snapshot(),
                )

                inputConnection.finishComposingText()
                assertEquals(
                    "finishComposingText must not silently flush smart text into a shell command",
                    "",
                    remoteStdin.snapshot(),
                )

                inputConnection.commitText("\n", 1)
            }

            withTimeout(2_000) {
                while (!remoteStdin.snapshot().contains("echo smart\r")) {
                    delay(10)
                }
            }
            assertEquals("echo smart\r", remoteStdin.snapshot())
            assertTrue("remote stdin should be flushed after confirmed smart text", remoteStdin.wasFlushed)
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun smartTextSendKeyEventEnterFlushesStagedTextBeforeCarriageReturn() = runBlocking {
        withSmartTextInputConnection { instrumentation, inputConnection, remoteStdin ->
            instrumentation.runOnMainSync {
                inputConnection.commitText("echo ok", 1)
                assertEquals("", remoteStdin.snapshot())
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            }

            remoteStdin.awaitSnapshot("echo ok\r")
            assertEquals("echo ok\r", remoteStdin.snapshot())
        }
    }

    @Test
    fun smartTextComposingTextWaitsForEditorConfirmation() = runBlocking {
        withSmartTextInputConnection { instrumentation, inputConnection, remoteStdin ->
            instrumentation.runOnMainSync {
                inputConnection.setComposingText("echo composed", 1)
                assertEquals(
                    "smart text composing updates must stay local until the user confirms",
                    "",
                    remoteStdin.snapshot(),
                )
                inputConnection.performEditorAction(EditorInfo.IME_ACTION_SEND)
            }

            remoteStdin.awaitSnapshot("echo composed\r")
            assertEquals("echo composed\r", remoteStdin.snapshot())
        }
    }

    @Test
    fun smartTextStagingClearsBeforeCtrlCAndEscapeControls() = runBlocking {
        withSmartTextInputConnection { instrumentation, inputConnection, remoteStdin ->
            instrumentation.runOnMainSync {
                inputConnection.commitText("discard me", 1)
                inputConnection.sendKeyEvent(ctrlKeyEvent(KeyEvent.KEYCODE_C))
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            }

            remoteStdin.awaitSnapshot("\u0003\r")
            assertEquals("\u0003\r", remoteStdin.snapshot())
        }

        withSmartTextInputConnection { instrumentation, inputConnection, remoteStdin ->
            instrumentation.runOnMainSync {
                inputConnection.commitText("discard me", 1)
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ESCAPE))
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            }

            remoteStdin.awaitSnapshot("\u001B\r")
            assertEquals("\u001B\r", remoteStdin.snapshot())
        }
    }

    @Test
    fun smartTextStagingClearsBeforePasteActionAndHotkey() = runBlocking {
        withSmartTextInputConnection { instrumentation, inputConnection, remoteStdin ->
            instrumentation.runOnMainSync {
                inputConnection.commitText("discard me", 1)
                inputConnection.performContextMenuAction(android.R.id.paste)
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            }

            remoteStdin.awaitSnapshot("\r")
            assertEquals("\r", remoteStdin.snapshot())
        }

        withSmartTextInputConnection { instrumentation, inputConnection, remoteStdin ->
            instrumentation.runOnMainSync {
                inputConnection.commitText("discard me", 1)
                inputConnection.sendKeyEvent(ctrlKeyEvent(KeyEvent.KEYCODE_V))
                inputConnection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            }

            remoteStdin.awaitSnapshot("\u0016\r")
            assertEquals("\u0016\r", remoteStdin.snapshot())
        }
    }

    private suspend fun withSmartTextInputConnection(
        block: suspend (Instrumentation, InputConnection, RecordingOutputStream) -> Unit,
    ) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val remoteStdin = RecordingOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = remoteStdin,
        )
        val client = PocketShellTerminalViewClient().apply {
            terminalKeyboardMode = TerminalKeyboardMode.SmartText
        }
        var inputConnection: InputConnection? = null

        try {
            instrumentation.runOnMainSync {
                val view = TerminalView(context, null)
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))
                val widthSpec = View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY)
                val heightSpec = View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY)
                view.measure(widthSpec, heightSpec)
                view.layout(0, 0, view.measuredWidth, view.measuredHeight)
                view.requestFocus()

                val editorInfo = EditorInfo()
                inputConnection = requireNotNull(view.onCreateInputConnection(editorInfo))
            }

            block(instrumentation, requireNotNull(inputConnection), remoteStdin)
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    private fun ctrlKeyEvent(keyCode: Int): KeyEvent = KeyEvent(
        0L,
        0L,
        KeyEvent.ACTION_DOWN,
        keyCode,
        0,
        KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON,
    )

    private suspend fun RecordingOutputStream.awaitSnapshot(expected: String) {
        withTimeout(2_000) {
            while (snapshot() != expected) {
                delay(10)
            }
        }
    }

    private class RecordingOutputStream : OutputStream() {
        private val bytes = mutableListOf<Byte>()

        @Volatile
        var wasFlushed: Boolean = false
            private set

        override fun write(b: Int) {
            synchronized(bytes) {
                bytes += b.toByte()
            }
        }

        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            synchronized(bytes) {
                for (index in offset until offset + length) {
                    bytes += buffer[index]
                }
            }
        }

        override fun flush() {
            wasFlushed = true
        }

        fun snapshot(): String = synchronized(bytes) {
            bytes.toByteArray().toString(Charsets.UTF_8)
        }
    }
}
