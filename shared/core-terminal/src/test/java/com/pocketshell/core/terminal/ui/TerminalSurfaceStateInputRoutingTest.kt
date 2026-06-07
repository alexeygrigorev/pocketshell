package com.pocketshell.core.terminal.ui

import android.os.Looper
import com.pocketshell.core.terminal.bridge.SshTerminalBridge
import com.pocketshell.core.terminal.bridge.TerminalSeedGateOverflowException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

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
    fun externalProducerDoesNotStallBehindSlowOutputCollectorDuringHugeFragmentedAnsiBurst() = runBlocking {
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>()
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = RecordingOutputStream(),
        )
        val observedSideChannelChunks = AtomicInteger(0)
        val slowCollector = launch {
            state.output.collect {
                observedSideChannelChunks.incrementAndGet()
                delay(60_000)
            }
        }
        yield()

        try {
            val chunks = issue576FragmentedAnsiBurstChunks()
            val sender = launch {
                chunks.forEach { chunk -> stdout.emit(chunk) }
            }

            val completed = withTimeoutOrNull(5_000) {
                while (sender.isActive) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
                sender.join()
                true
            } ?: false

            assertTrue(
                "terminal producer must not wait behind a slow TerminalSurfaceState.output collector",
                completed,
            )
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(
                "slow side-channel collector should prove at least one output subscriber was active",
                observedSideChannelChunks.get() > 0,
            )
            assertTrue(
                "terminal emulator should still render the end marker from the burst",
                state.renderedTranscriptForTesting().contains(ISSUE_576_DONE_MARKER),
            )
        } finally {
            slowCollector.cancel()
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
        }
    }

    @Test
    fun externalProducerFeedFailureInvokesLocalCallbackAndDoesNotFailParentScope() = runBlocking {
        val state = TerminalSurfaceState()
        val stdout = MutableSharedFlow<ByteArray>(extraBufferCapacity = 1)
        val producerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val failure = CompletableDeferred<Throwable>()
        val producerJob = state.attachExternalProducer(
            scope = producerScope,
            stdout = stdout,
            remoteStdin = RecordingOutputStream(),
            awaitSeed = true,
            onTerminalFeedFailure = { cause ->
                failure.complete(cause)
            },
        )
        yield()

        try {
            stdout.emit(ByteArray(SshTerminalBridge.MAX_SEED_GATE_LIVE_BUFFER_BYTES + 1))

            val cause = withTimeout(2_000) { failure.await() }
            assertTrue(
                "seed-gate feed overflow should surface through the local terminal callback",
                cause is TerminalSeedGateOverflowException,
            )
            withTimeout(2_000) {
                while (producerJob.isActive) {
                    shadowOf(Looper.getMainLooper()).idle()
                    delay(10)
                }
            }
            assertTrue(
                "terminal feed failure must be contained to the producer job",
                producerScope.coroutineContext[Job]?.isActive == true,
            )
        } finally {
            producerJob.cancel()
            producerScope.cancel()
            state.detachExternalProducer()
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

    private fun issue576FragmentedAnsiBurstChunks(): List<ByteArray> {
        val chunks = mutableListOf<ByteArray>()
        chunks += "\u001b[31mISSUE576-START\u001b[0m\r\n".toByteArray(Charsets.UTF_8)
        chunks += ("LONG-LINE-" + "A".repeat(12_000) + "\r\n").toByteArray(Charsets.UTF_8)
        chunks += "\u001b[".toByteArray(Charsets.UTF_8)
        chunks += "?25l".toByteArray(Charsets.UTF_8)
        repeat(320) { index ->
            val line = buildString {
                append("\u001b[38;5;")
                append(index % 256)
                append('m')
                append("frag-")
                append(index.toString().padStart(3, '0'))
                append(' ')
                append(('a'.code + (index % 26)).toChar().toString().repeat(900))
                if (index % 5 == 0) append("\u001b[2K")
                append("\r\n")
            }
            chunks += line.toByteArray(Charsets.UTF_8)
        }
        chunks += "\u001b[?25h\u001b[0m\r\n$ISSUE_576_DONE_MARKER\r\n".toByteArray(Charsets.UTF_8)
        return chunks
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

    private companion object {
        private const val ISSUE_576_DONE_MARKER = "ISSUE576-DONE"
    }
}
