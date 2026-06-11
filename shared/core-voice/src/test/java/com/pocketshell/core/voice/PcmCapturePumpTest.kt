package com.pocketshell.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Regression tests for the two intermittent "real speech → No speech detected"
 * capture-lifecycle races behind [PcmCapturePump] (issue #587).
 *
 * Both reproduce a failure that the inline capture loop in [AudioRecorder]
 * exhibited before the pump was extracted, driving a deterministic JVM fake
 * [PcmSource] instead of a real Android `AudioRecord` (which can't run in a
 * unit test):
 *
 *  1. [startThenImmediateStopStillCapturesSpokenAudio] — a stop requested the
 *     instant [PcmCapturePump.start] returns must NOT drop the whole utterance.
 *     With the old inline loop the reader thread could observe `recording`
 *     already false before its first read and capture zero bytes; the pump's
 *     ready latch closes that race.
 *  1b. [startRaceFrameRecoverableOnlyByBlockingReadSurvives] — the load-bearing
 *     guard for the ready latch *specifically*. Models a LIVE mic stream where a
 *     frame is produced and consumed only by the blocking [PcmSource.read] (the
 *     drain can recover nothing). If [PcmCapturePump.start] returns before the
 *     reader thread is inside the loop, a fast stop flips `capturing=false`, the
 *     `while (capturing)` guard is never entered, the blocking read never runs,
 *     and zero bytes are captured — the literal "real speech → No speech
 *     detected" dogfood symptom. This test FAILS if the latch is removed even
 *     though the drain is intact, so it pins the latch and not the drain.
 *  2. [stopDrainsResidualTailFrames] — frames the source had already buffered
 *     when stop was requested must be drained into the result, not abandoned.
 *     The old loop released the AudioRecord without draining, clipping the tail
 *     of the final word.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PcmCapturePumpTest {

    /**
     * Fake modelling an Android `AudioRecord`'s ring buffer: a shared queue of
     * captured frames. The blocking [read] pops the next frame, or — when the
     * queue is empty — parks (like an open-but-idle mic) until the pump stops
     * capturing. The non-blocking [readNonBlocking] used by the stop-drain pops
     * whatever frames are STILL buffered.
     *
     * Crucially, frames the blocking loop never got to consume (because a fast
     * stop fired first) remain in the queue and are therefore recoverable by
     * the drain — exactly the real-world guarantee: spoken audio the HAL
     * buffered isn't lost just because the reader thread didn't pop it before
     * the FSM flipped.
     */
    private class FakeSource(
        private val frameBytes: Int,
        bufferedFrames: Int,
        // When non-null, the first blocking read parks on this latch before
        // returning data — modelling AudioRecord warmup so a stop can be
        // requested while the reader thread is mid-read.
        private val gateFirstReadUntil: CountDownLatch? = null,
    ) : PcmSource {
        private val remaining = AtomicInteger(bufferedFrames)
        val totalReads = AtomicInteger(0)

        @Volatile
        var stopped = false

        override fun read(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int {
            if (totalReads.incrementAndGet() == 1 && gateFirstReadUntil != null) {
                gateFirstReadUntil.await(2, TimeUnit.SECONDS)
            }
            if (remaining.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                loudFrame(frameBytes).copyInto(buffer, destinationOffset = offsetBytes)
                return sizeBytes
            }
            // Buffer empty: emulate an open-but-idle mic. Park until the pump
            // asks us to stop so the reader thread blocks here like a real
            // blocking read() would.
            while (!stopped) {
                Thread.sleep(1)
            }
            return 0
        }

        override fun readNonBlocking(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int {
            if (remaining.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
                loudFrame(frameBytes).copyInto(buffer, destinationOffset = offsetBytes)
                return sizeBytes
            }
            return 0
        }
    }

    private val frameBytes = 640 // 20ms @ 16kHz mono 16-bit

    @Test
    fun startThenImmediateStopStillCapturesSpokenAudio() {
        // Worst case of the start race: the user tapped the mic and spoke (the
        // source has a buffered frame), but the blocking reader thread is still
        // parked in its first warming-up read when a fast auto-stop / second
        // tap fires. The blocking loop NEVER pops the frame — only the stop
        // drain can recover it. The utterance must survive, not be raced away.
        val warmupGate = CountDownLatch(1)
        val source = FakeSource(
            frameBytes = frameBytes,
            bufferedFrames = 1,
            gateFirstReadUntil = warmupGate,
        )
        val pump = PcmCapturePump(source = source, frameBytes = frameBytes)

        pump.start() // blocks until the reader thread is inside the loop
        source.stopped = true

        // Stop on another thread; it sets capturing=false and joins the reader
        // (which is gated). Release the gate so the gated read returns 0 and the
        // reader exits — then the drain pulls the buffered frame.
        val result = arrayOfNulls<ByteArray>(1)
        val stopThread = Thread { result[0] = pump.stop() }
        stopThread.start()
        Thread.sleep(20)
        warmupGate.countDown()
        stopThread.join(2_000)

        val pcm = result[0]
        assertTrue("stop() must complete", pcm != null)
        assertEquals(
            "fast tap-then-stop must not drop the buffered frame",
            frameBytes,
            pcm!!.size,
        )
        assertTrue("captured audio must be non-silent", pcm.any { it != 0.toByte() })
    }

    @Test
    fun startThenStopWhileFirstReadIsWarmingUpStillCapturesAudio() {
        // Harsher variant of the start race: stop is requested while the
        // reader thread is parked inside its FIRST (warming-up) read. Once the
        // warmup gate opens that read still delivers a frame, and the pump's
        // join + drain must include it instead of tearing down early.
        val warmupGate = CountDownLatch(1)
        val source = FakeSource(
            frameBytes = frameBytes,
            bufferedFrames = 1,
            gateFirstReadUntil = warmupGate,
        )
        val pump = PcmCapturePump(source = source, frameBytes = frameBytes)

        pump.start()

        // Request stop on a separate thread while the first read is still
        // gated, then release the gate so the in-flight read completes.
        val stopResult = arrayOfNulls<ByteArray>(1)
        val stopThread = Thread {
            source.stopped = true
            stopResult[0] = pump.stop()
        }
        stopThread.start()
        Thread.sleep(20) // ensure stop() has set capturing=false and is joining
        warmupGate.countDown() // first read now returns its buffered frame
        stopThread.join(2_000)

        val pcm = stopResult[0]
        assertTrue("stop() must complete", pcm != null)
        assertEquals(
            "the in-flight warmup read's frame must be captured, not abandoned",
            frameBytes,
            pcm!!.size,
        )
    }

    /**
     * Live-stream fake that pins the READY LATCH and only the latch.
     *
     * Unlike [FakeSource] (a pre-filled ring buffer where unread frames stay
     * recoverable by the drain), this models a *live* mic: a frame only exists
     * the moment the blocking [read] loop pulls it off the wire. The drain
     * ([readNonBlocking]) can recover NOTHING — there is no backing queue.
     *
     * So the single frame of spoken audio is captured if and only if the
     * blocking-read loop actually runs at least once. If [PcmCapturePump.start]
     * returns before the reader thread enters its `while (capturing)` loop, a
     * stop that flips `capturing=false` first means the loop body never
     * executes, [read] is never called, and the utterance is lost — exactly the
     * race the ready latch exists to close.
     */
    private class LiveStreamSource(private val frameBytes: Int) : PcmSource {
        // Signalled the instant the blocking read is entered for the first time —
        // i.e. the reader thread has passed the loop guard and committed to a
        // read. This is precisely the state the ready latch must guarantee before
        // start() returns.
        val firstReadEntered = CountDownLatch(1)

        // Gates the FIRST read body so the test controls exactly when the live
        // frame is delivered. Without this the reader could race ahead and the
        // discriminator between latch / no-latch would be lost to scheduling.
        val releaseFirstRead = CountDownLatch(1)

        @Volatile
        var stopped = false
        private val firstRead = AtomicInteger(0)

        override fun read(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int {
            if (firstRead.compareAndSet(0, 1)) {
                // The reader thread reached its first blocking read: it passed
                // `while (capturing)` and is committed. Announce it, then park
                // until the test releases the live frame.
                firstReadEntered.countDown()
                releaseFirstRead.await(2, TimeUnit.SECONDS)
                if (!stopped) {
                    // Deliver exactly one live frame. There is NO backing buffer:
                    // a frame produced here exists only because this blocking read
                    // ran. If the read never runs, the frame never exists and the
                    // drain (readNonBlocking) cannot recover it.
                    loudFrame(frameBytes).copyInto(buffer, destinationOffset = offsetBytes)
                    return sizeBytes
                }
            }
            // Idle mic: park until stop, then report end-of-stream.
            while (!stopped) {
                Thread.sleep(1)
            }
            return 0
        }

        // The drain has nothing to give: a live stream keeps no residual buffer.
        override fun readNonBlocking(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int = 0
    }

    @Test
    fun startRaceFrameRecoverableOnlyByBlockingReadSurvives() {
        // Load-bearing guard for the ready latch (race #1) — and ONLY the latch.
        //
        // The frame lives on a LIVE stream: recoverable by the blocking read,
        // NEVER by the post-stop drain (readNonBlocking returns 0). So the only
        // way to capture it is for the blocking read loop to actually run.
        //
        // The pump's ready latch makes start() block until the reader thread has
        // passed `while (capturing)` and entered its first read. We assert that
        // contract DIRECTLY and deterministically: the moment start() returns,
        // firstReadEntered must already be counted down. If the latch is removed,
        // start() returns before the reader thread is scheduled, firstReadEntered
        // is NOT yet down, and a stop() at that point flips capturing=false so the
        // loop guard fails, the read never runs, and zero bytes are captured —
        // the literal "real speech -> No speech detected" dogfood symptom.
        val source = LiveStreamSource(frameBytes)
        val pump = PcmCapturePump(source = source, frameBytes = frameBytes)

        pump.start()

        // The ready-latch contract, asserted with a zero-wait check so the test
        // never synchronises with the reader thread itself. WITH the latch this
        // is already true on return; WITHOUT the latch it is false (the reader
        // thread has not yet been scheduled past the loop guard).
        assertTrue(
            "ready-latch contract: start() must not return until the reader thread " +
                "has entered its first blocking read — without the latch the read " +
                "never runs for a live (non-drainable) frame and the utterance is lost",
            source.firstReadEntered.await(0, TimeUnit.MILLISECONDS),
        )

        // And the data-loss consequence end to end: with the read committed, the
        // live frame survives a stop; the drain alone could never have recovered
        // it. Request stop, release the in-flight read so it delivers its frame.
        val stopResult = arrayOfNulls<ByteArray>(1)
        val stopThread = Thread { stopResult[0] = pump.stop() }
        stopThread.start()
        Thread.sleep(20)
        source.releaseFirstRead.countDown()
        stopThread.join(2_000)

        val pcm = stopResult[0]
        assertTrue("stop() must complete", pcm != null)
        assertEquals(
            "the live frame, recoverable only by the blocking read, must survive",
            frameBytes,
            pcm!!.size,
        )
        assertTrue("captured audio must be non-silent", pcm.any { it != 0.toByte() })
    }

    @Test
    fun stopDrainsResidualTailFrames() {
        // The ring buffer holds three frames when stop is requested. However
        // the blocking loop and the drain split them, ALL three must end up in
        // the result so the tail of the final word is not clipped.
        val source = FakeSource(frameBytes = frameBytes, bufferedFrames = 3)
        val pump = PcmCapturePump(source = source, frameBytes = frameBytes)

        pump.start()
        source.stopped = true
        val pcm = pump.stop()

        assertEquals(
            "stop must capture every buffered frame, not clip the tail",
            frameBytes * 3,
            pcm.size,
        )
    }

    @Test
    fun stopWithoutStartReturnsEmpty() {
        val source = FakeSource(frameBytes = frameBytes, bufferedFrames = 0)
        val pump = PcmCapturePump(source = source, frameBytes = frameBytes)
        assertEquals(0, pump.stop().size)
    }

    @Test
    fun midCaptureErrorSentinelIsRethrownOnStop() {
        // A negative AudioRecord.read sentinel mid-capture parks a typed error
        // that stop() must re-throw — the recorder contract the inline loop had.
        val source = object : PcmSource {
            override fun read(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int =
                android.media.AudioRecord.ERROR_DEAD_OBJECT

            override fun readNonBlocking(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int = 0
        }
        val pump = PcmCapturePump(source = source, frameBytes = frameBytes)
        pump.start()
        try {
            pump.stop()
            throw AssertionError("expected AudioRecorderException")
        } catch (e: AudioRecorderException.NoDevice) {
            assertTrue(e.message?.contains("ERROR_DEAD_OBJECT") == true)
        }
    }
}

/** One frame of non-silent 16-bit PCM (constant 0x4000 samples). */
private fun loudFrame(bytes: Int): ByteArray {
    val out = ByteArray(bytes)
    var i = 0
    while (i + 1 < bytes) {
        out[i] = 0x00
        out[i + 1] = 0x40 // 0x4000 little-endian -> well above any RMS floor
        i += 2
    }
    return out
}
