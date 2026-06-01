package com.pocketshell.core.terminal.bridge

import android.os.Looper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SshTerminalBridgeTest {

    @Test
    fun feedBytesPostsDrainBeforeWaitingOnChunksLargerThanByteQueueCapacity() {
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 1_000,
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
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun feedBytesOnMainLooperDrainsLargePayloadWithoutWaitingForPostedMessage() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 1_000,
        )
        val payload = largePayload()

        bridge.feedBytes(payload.bytes)
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(payload.transcriptText, bridge.emulator.screen.transcriptText)
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

    private companion object {
        private const val LINE_LENGTH = 100
        private const val LINES_LARGER_THAN_PROCESS_QUEUE = 700
    }
}
