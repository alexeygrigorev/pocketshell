package com.pocketshell.core.terminal.ui

import android.text.InputType
import android.view.inputmethod.EditorInfo
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
 * tap terminal -> focus terminal -> IME input connection -> remote stdin.
 */
@RunWith(AndroidJUnit4::class)
class TerminalSurfaceInputInstrumentedTest {

    @Test
    fun terminalTapAndImeCommitRouteTextAndEnterToRemoteStdin() = runBlocking {
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
        val view = TerminalView(context, null)
        val client = PocketShellTerminalViewClient()

        try {
            instrumentation.runOnMainSync {
                view.applyPocketShellDefaults(client)
                view.attachSession(requireNotNull(state.session))

                client.onSingleTapUp(null)
                assertTrue("terminal tap should focus the TerminalView", view.isFocused)

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
