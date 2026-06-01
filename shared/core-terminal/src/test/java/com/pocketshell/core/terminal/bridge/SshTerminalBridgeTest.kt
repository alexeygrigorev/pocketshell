package com.pocketshell.core.terminal.bridge

import android.os.Looper
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SshTerminalBridgeTest {

    @Test
    fun feedBytesPostsDrainBeforeWaitingOnChunksLargerThanByteQueueCapacity() {
        val trace = RecordingTraceSink()
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 1_000,
            traceSink = trace,
        )
        val payload = largePayload()

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SshTerminalBridgeLargeFeedTest").apply { isDaemon = true }
        }
        val feed = executor.submit {
            bridge.feedBytes(payload.bytes)
        }

        try {
            Thread.sleep(100)
            assertFalse(
                "large feed should wait for a main-thread drain after the first queue-sized chunk",
                feed.isDone,
            )

            shadowOf(Looper.getMainLooper()).idle()

            feed.get(2, TimeUnit.SECONDS)
            assertTrue("large feed must complete once the posted drain runs", feed.isDone)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(payload.transcriptText, bridge.emulator.screen.transcriptText)
            assertTrue(
                "expected producer write timing to record at least one wait behind a full queue; trace=$trace",
                trace.queueWrites.any { it.waitedForDrain },
            )
            assertEquals(payload.bytes.size, trace.screenUpdates.sumOf { it.bytes })
            assertTrue(
                "bounded drain slices should split at least one queue-sized write; trace=$trace",
                trace.screenUpdates.size > trace.scheduledDrains.size,
            )
            assertTrue(
                "screen update traces should stay within the main-thread drain budget",
                trace.screenUpdates.all { it.bytes in 1..SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test(timeout = 5_000)
    fun feedBytesOnMainLooperDrainsLargePayloadWithoutWaitingForPostedMessage() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val trace = RecordingTraceSink()
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 1_000,
            traceSink = trace,
        )
        val payload = largePayload()
        assertTrue(
            "main-looper regression payload must exceed one full queue plus one bounded drain slice",
            payload.bytes.size >
                SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES +
                SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES,
        )

        bridge.feedBytes(payload.bytes)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(payload.transcriptText, bridge.emulator.screen.transcriptText)
        assertEquals(expectedChunks(payload.bytes.size), trace.queueWrites.size)
        assertEquals(expectedDrainSlices(payload.bytes.size), trace.directDrains.size)
        assertTrue(
            "direct main-looper drains should stay within the bounded drain budget",
            trace.directDrains.all {
                it.bytes in 1..SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES
            },
        )
    }

    @Test
    fun defaultTracePathUsesSuppliedClientDirectly() {
        val client = RecordingTerminalSessionClient()
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 1_000,
            client = client,
        )

        assertSame(client, terminalSessionClient(bridge.session))
        assertSame(client, terminalEmulatorClient(bridge.emulator))

        bridge.feedBytes("client-callback".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, client.textChangedCount)
    }

    @Test
    fun feedBytesValidatesFullRequestedRangeBeforeWritingAnyChunk() {
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 1_000,
        )
        val payload = ByteArray(SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES) { 'x'.code.toByte() }

        try {
            bridge.feedBytes(payload, offset = 0, count = payload.size + 1)
        } catch (expected: IllegalArgumentException) {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("", bridge.emulator.screen.transcriptText)
            return
        }

        throw AssertionError("feedBytes should reject an out-of-bounds offset/count range")
    }

    @Test
    fun rawSshStyleBurstRendersWithoutHangingAndRecordsDrainCounts() {
        val trace = RecordingTraceSink()
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = RAW_BURST_LINES + 100,
            traceSink = trace,
        )
        val payload = rawSshStyleBurstPayload()

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SshTerminalBridgeRawBurstStressTest").apply { isDaemon = true }
        }
        val feed = executor.submit {
            bridge.feedBytes(payload.bytes)
        }

        try {
            drainMainLooperUntil(feed::isDone)
            feed.get(2, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(payload.transcriptText, bridge.emulator.screen.transcriptText)
            assertEquals(1, trace.feedCompletions.size)
            assertEquals(payload.bytes.size, trace.feedCompletions.single().bytes)
            assertEquals(expectedChunks(payload.bytes.size), trace.feedCompletions.single().chunks)
            assertEquals(expectedChunks(payload.bytes.size), trace.queueWrites.size)
            assertEquals(expectedChunks(payload.bytes.size), trace.scheduledDrains.size)
            val screenDrains = trace.screenUpdates.filter { it.bytes > 0 }
            assertEquals(expectedDrainSlices(payload.bytes.size), screenDrains.size)
            assertEquals(payload.bytes.size, screenDrains.sumOf { it.bytes })
            assertTrue(
                "screen-update traces should stay within the main-thread drain budget",
                screenDrains.all {
                    it.bytes in 1..SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES
                },
            )
            assertTrue(
                "all queue write timings should be captured",
                trace.queueWrites.all { it.bytes > 0 && it.durationNanos >= 0L },
            )
            assertTrue(
                "all non-empty scheduled drains should reach the screen-update callback",
                screenDrains.all { it.scheduleToCallbackNanos >= 0L },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun tmuxOutputStyleBurstChunksRenderAndKeepPerFeedTiming() {
        val trace = RecordingTraceSink()
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = TMUX_BURST_LINES + 100,
            traceSink = trace,
        )
        val chunks = tmuxOutputStyleBurstChunks()
        val expectedTranscript = chunks.joinToString(separator = "\n") { chunk ->
            chunk.decodeToString().removeSuffix("\r\n")
        }

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SshTerminalBridgeTmuxBurstStressTest").apply { isDaemon = true }
        }
        val feed = executor.submit {
            chunks.forEach { chunk ->
                bridge.feedBytes(chunk)
            }
        }

        try {
            drainMainLooperUntil(feed::isDone)
            feed.get(2, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(expectedTranscript, bridge.emulator.screen.transcriptText)
            assertEquals(chunks.size, trace.feedCompletions.size)
            assertEquals(chunks.sumOf { expectedChunks(it.size) }, trace.queueWrites.size)
            assertEquals(trace.queueWrites.size, trace.scheduledDrains.size)
            assertTrue(trace.screenUpdates.isNotEmpty())
            assertTrue(trace.screenUpdates.size <= trace.scheduledDrains.size)
            assertEquals(chunks.sumOf { it.size }, trace.screenUpdates.sumOf { it.bytes })
            assertTrue(
                "many small tmux-style feeds should coalesce redundant drain/render callbacks",
                trace.screenUpdates.size < trace.scheduledDrains.size,
            )
            assertTrue(
                "tmux-style burst should capture screen update latency for every emitted chunk",
                trace.screenUpdates.filter { it.bytes > 0 }.all { it.scheduleToCallbackNanos >= 0L },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    private data class Payload(
        val bytes: ByteArray,
        val transcriptText: String,
    )

    private fun largePayload(): Payload {
        val payloadLines = List(LINES_LARGER_THAN_PROCESS_QUEUE) { line ->
            ('a'.code + (line % 26)).toChar().toString().repeat(LINE_LENGTH - 1)
        }
        val payloadText = payloadLines.joinToString(separator = "\n")
        val payloadWireText = buildString {
            payloadLines.forEachIndexed { index, line ->
                if (index > 0) append("\r\n")
                append(line)
            }
        }
        val payload = payloadWireText.toByteArray(Charsets.US_ASCII)
        assertTrue(
            "test payload must exceed the process-to-terminal ByteQueue capacity",
            payload.size > SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES,
        )
        return Payload(payload, payloadText)
    }

    private fun rawSshStyleBurstPayload(): Payload {
        val payloadLines = List(RAW_BURST_LINES) { line ->
            "ssh-burst-%04d ".format(line) +
                ('A'.code + (line % 26)).toChar().toString().repeat(LINE_LENGTH - 16)
        }
        val payloadText = payloadLines.joinToString(separator = "\n")
        val payloadWireText = payloadLines.joinToString(separator = "\r\n")
        val payload = payloadWireText.toByteArray(Charsets.US_ASCII)
        assertTrue(
            "raw burst fixture must span several process-to-terminal queue drains",
            payload.size > SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES * 4,
        )
        return Payload(payload, payloadText)
    }

    private fun tmuxOutputStyleBurstChunks(): List<ByteArray> =
        List(TMUX_BURST_LINES) { line ->
            val text = "tmux-output-%04d ".format(line) +
                ('a'.code + (line % 26)).toChar().toString().repeat(LINE_LENGTH - 17) +
                "\r\n"
            text.toByteArray(Charsets.US_ASCII)
        }

    private fun expectedChunks(byteCount: Int): Int =
        (byteCount + SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES - 1) /
            SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES

    private fun expectedDrainSlices(byteCount: Int): Int =
        (byteCount + SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES - 1) /
            SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES

    private fun drainMainLooperUntil(done: () -> Boolean) {
        val deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (!done()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.nanoTime() > deadlineNanos) {
                throw AssertionError("timed out waiting for feedBytes burst to drain")
            }
            Thread.sleep(10)
        }
    }

    private class RecordingTraceSink : SshTerminalBridge.TraceSink() {
        val queueWrites = Collections.synchronizedList(mutableListOf<QueueWrite>())
        val scheduledDrains = Collections.synchronizedList(mutableListOf<ScheduledDrain>())
        val directDrains = Collections.synchronizedList(mutableListOf<DirectDrain>())
        val screenUpdates = Collections.synchronizedList(mutableListOf<ScreenUpdate>())
        val feedCompletions = Collections.synchronizedList(mutableListOf<FeedCompletion>())

        override fun onProcessQueueWrite(bytes: Int, durationNanos: Long, waitedForDrain: Boolean) {
            queueWrites += QueueWrite(bytes, durationNanos, waitedForDrain)
        }

        override fun onDrainMessageScheduled(bytes: Int, pendingMessages: Int, directDispatch: Boolean) {
            scheduledDrains += ScheduledDrain(bytes, pendingMessages, directDispatch)
        }

        override fun onDirectDrainDispatched(bytes: Int, durationNanos: Long) {
            directDrains += DirectDrain(bytes, durationNanos)
        }

        override fun onScreenUpdated(bytes: Int, scheduleToCallbackNanos: Long, callbackDurationNanos: Long) {
            screenUpdates += ScreenUpdate(bytes, scheduleToCallbackNanos, callbackDurationNanos)
        }

        override fun onFeedCompleted(bytes: Int, chunks: Int, durationNanos: Long) {
            feedCompletions += FeedCompletion(bytes, chunks, durationNanos)
        }

        override fun toString(): String =
            "RecordingTraceSink(queueWrites=$queueWrites, scheduledDrains=$scheduledDrains, " +
                "directDrains=$directDrains, screenUpdates=$screenUpdates, feedCompletions=$feedCompletions)"
    }

    private data class QueueWrite(val bytes: Int, val durationNanos: Long, val waitedForDrain: Boolean)
    private data class ScheduledDrain(val bytes: Int, val pendingMessages: Int, val directDispatch: Boolean)
    private data class DirectDrain(val bytes: Int, val durationNanos: Long)
    private data class ScreenUpdate(val bytes: Int, val scheduleToCallbackNanos: Long, val callbackDurationNanos: Long)
    private data class FeedCompletion(val bytes: Int, val chunks: Int, val durationNanos: Long)

    private class RecordingTerminalSessionClient : TerminalSessionClient {
        var textChangedCount = 0
            private set

        override fun onTextChanged(changedSession: TerminalSession) {
            textChangedCount += 1
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    private fun terminalSessionClient(session: TerminalSession): TerminalSessionClient {
        val field = TerminalSession::class.java.getDeclaredField("mClient").apply { isAccessible = true }
        return field.get(session) as TerminalSessionClient
    }

    private fun terminalEmulatorClient(emulator: TerminalEmulator): TerminalSessionClient {
        val field = TerminalEmulator::class.java.getDeclaredField("mClient").apply { isAccessible = true }
        return field.get(emulator) as TerminalSessionClient
    }

    private companion object {
        private const val LINE_LENGTH = 100
        private const val LINES_LARGER_THAN_PROCESS_QUEUE = 900
        private const val RAW_BURST_LINES = 5_000
        private const val TMUX_BURST_LINES = 1_200
    }
}
