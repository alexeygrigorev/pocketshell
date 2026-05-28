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
            stdout.emit("hello from remote\n".toByteArray(Charsets.UTF_8))

            withTimeout(2_000) {
                while (!renderRequest.isCompleted) {
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
