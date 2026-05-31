package com.pocketshell.core.terminal.ui

import android.os.Looper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.OutputStream
import java.util.concurrent.Executors

@RunWith(RobolectricTestRunner::class)
class TerminalSurfaceStateInputRoutingTest {

    @Test
    fun writeInputForAttachedExternalProducerReachesRemoteStdin() = runBlocking {
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val remoteStdin = RecordingOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = remoteStdin,
        )

        try {
            val payload = "printf hello\r".toByteArray(Charsets.UTF_8)

            state.writeInput(payload)

            withTimeout(2_000) {
                while (remoteStdin.snapshot().isEmpty()) {
                    delay(10)
                }
            }
            assertEquals("printf hello\r", remoteStdin.snapshot())
            assertTrue("remote stdin should be flushed after input writes", remoteStdin.wasFlushed)
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun writeInputIgnoresEmptyPayloads() = runBlocking {
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val remoteStdin = RecordingOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = remoteStdin,
        )

        try {
            state.writeInput(ByteArray(0))
            delay(50)

            assertEquals("", remoteStdin.snapshot())
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun externalProducerCollectsStdoutOffMainThread() = runBlocking {
        val feedExecutor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "PocketShellTerminalFeedTest")
        }
        val feedDispatcher = feedExecutor.asCoroutineDispatcher()
        val collectionThread = CompletableDeferred<String>()
        val state = TerminalSurfaceState(externalProducerDispatcher = feedDispatcher)
        val stdout = flow {
            collectionThread.complete(Thread.currentThread().name)
            emit("remote output\n".toByteArray(Charsets.UTF_8))
        }
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = RecordingOutputStream(),
        )

        try {
            val threadName = withTimeout(2_000) { collectionThread.await() }

            assertTrue(
                "stdout collection and ByteQueue feeding must run on the configured background dispatcher",
                threadName.startsWith("PocketShellTerminalFeedTest"),
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
            feedDispatcher.close()
            feedExecutor.shutdownNow()
        }
    }

    @Test
    fun externalProducerPreservesQueryResponsesByDefault() = runBlocking {
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val remoteStdin = RecordingOutputStream()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = remoteStdin,
        )

        try {
            state.appendRemoteOutput("\u001b[c".toByteArray(Charsets.US_ASCII))
            shadowOf(Looper.getMainLooper()).idle()

            withTimeout(2_000) {
                while (remoteStdin.snapshot().isEmpty()) {
                    delay(10)
                }
            }
            assertEquals("\u001b[?64;1;2;6;9;15;18;21;22c", remoteStdin.snapshot())
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun bridgeModeCaptureReplayDropsQueryResponseLeak() = runBlocking {
        // Issue #248: a `capture-pane` replay (appendRemoteOutput) that
        // contains the printable remnant of an OSC-colour + DA reply must NOT
        // paint that raw sequence onto the grid in bridge (tmux -CC) mode.
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = RecordingOutputStream(),
            suppressQueryResponses = true,
        )

        try {
            val leak = "real-output ]11;rgb:0101/0404/0909\\[?64;1;2;6;9;15;18;21;22c after\r\n"
            state.appendRemoteOutput(leak.toByteArray(Charsets.US_ASCII))
            shadowOf(Looper.getMainLooper()).idle()

            val transcript = state.renderedTranscriptForTesting()
            assertTrue(
                "bridge mode must keep real output, got: $transcript",
                transcript.contains("real-output"),
            )
            assertTrue(
                "bridge mode must keep trailing output, got: $transcript",
                transcript.contains("after"),
            )
            assertFalse(
                "raw OSC color reply must not reach the grid, got: $transcript",
                transcript.contains("rgb:0101"),
            )
            assertFalse(
                "raw DA reply must not reach the grid, got: $transcript",
                transcript.contains("64;1;2;6;9;15;18;21;22c"),
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun plainSshSurfaceKeepsQueryResponsesInOutput() = runBlocking {
        // Non-bridge surfaces must NOT sanitize — full fidelity is preserved.
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = RecordingOutputStream(),
            // suppressQueryResponses defaults to false (plain SSH surface).
        )
        val collected = CompletableDeferred<ByteArray>()
        val outputCollector = launch(Dispatchers.Unconfined) {
            state.output.collect { if (!collected.isCompleted) collected.complete(it) }
        }

        try {
            val wire = "]11;rgb:0101/0404/0909\\".toByteArray(Charsets.US_ASCII)
            // The no-replay _output SharedFlow drops emissions that arrive
            // before a collector subscribes, so poll-until-complete instead of
            // a single-shot await — matching the proven pattern in
            // externalProducerOutputEmitsRenderRequestWithoutComposeStateTick.
            withTimeout(2_000) {
                while (!collected.isCompleted) {
                    stdout.emit(wire)
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            val emitted = collected.await()
            assertEquals(
                "plain SSH surface must forward query responses verbatim",
                wire.toList(),
                emitted.toList(),
            )
        } finally {
            outputCollector.cancel()
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun externalProducerOutputEmitsRenderRequestWithoutComposeStateTick() = runBlocking {
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = RecordingOutputStream(),
        )
        val renderRequest = CompletableDeferred<Unit>()
        val renderCollector = launch(Dispatchers.Unconfined) {
            state.renderRequests.collect {
                if (!renderRequest.isCompleted) renderRequest.complete(Unit)
            }
        }

        try {
            // renderRequests is a no-replay SharedFlow, so a render request
            // emitted before renderCollector subscribes is lost. Re-emit the
            // output on every poll iteration so a fresh onTextChanged ->
            // tryEmit keeps firing until the collector observes one.
            val payload = "hello from remote\n".toByteArray(Charsets.UTF_8)
            withTimeout(2_000) {
                while (!renderRequest.isCompleted) {
                    stdout.emit(payload)
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertTrue("remote output must ask the TerminalView to redraw", renderRequest.isCompleted)
        } finally {
            renderCollector.cancel()
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun attachDetachCyclesLeaveNoInputDrainerThreads() = runBlocking {
        assertEquals(
            "test must start without leaked input drainers",
            0,
            liveInputDrainerThreadCount(),
        )

        repeat(25) {
            val state = TerminalSurfaceState()
            val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
            val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val producerJob = state.attachExternalProducer(
                scope = producerScope,
                stdout = stdout,
                remoteStdin = RecordingOutputStream(),
            )

            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }

        withTimeout(2_000) {
            while (liveInputDrainerThreadCount() != 0) {
                delay(10)
            }
        }
        assertEquals(
            "attach/detach must close the terminal input queue so PocketShellInputDrainer exits",
            0,
            liveInputDrainerThreadCount(),
        )
    }

    private fun liveInputDrainerThreadCount(): Int =
        Thread.getAllStackTraces().keys.count { thread ->
            thread.name == "PocketShellInputDrainer" && thread.isAlive
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
