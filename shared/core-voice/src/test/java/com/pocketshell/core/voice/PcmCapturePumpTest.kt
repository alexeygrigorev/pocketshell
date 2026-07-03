package com.pocketshell.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // Counted down the instant the blocking read is entered for the first
        // time — i.e. the reader thread has passed the loop guard and committed
        // to a read. This is precisely the state the ready latch must guarantee
        // before start() returns.
        val firstReadEntered = CountDownLatch(1)

        @Volatile
        var stopped = false
        private val firstRead = AtomicInteger(0)

        override fun read(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int {
            if (firstRead.compareAndSet(0, 1)) {
                // The reader thread reached its first blocking read: it passed
                // `while (capturing)` and is committed.
                firstReadEntered.countDown()
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
        // De-flake (issue #683): the previous version asserted the contract with
        // a zero-wait `firstReadEntered.await(0)` immediately after start(). That
        // raced the reader thread — start() unblocks the instant the pump counts
        // down its ready latch, but firstReadEntered only fires one statement
        // later when source.read() is actually entered, so under CI CPU load the
        // reader could be descheduled in that window and the zero-wait check saw
        // `false` even though the latch was working. Here we make the start race
        // DETERMINISTIC with a reader-thread entry barrier the pump exposes as a
        // test seam: the reader is held BEFORE the `while (capturing)` guard until
        // the test releases it, so the latch/no-latch outcome no longer depends on
        // scheduler timing.
        val source = LiveStreamSource(frameBytes)

        // Held while the reader thread sits at its entry hook, before the loop
        // guard. The test owns exactly when the reader is allowed to proceed.
        val releaseReader = CountDownLatch(1)
        val readerAtEntry = CountDownLatch(1)
        val pump = PcmCapturePump(
            source = source,
            frameBytes = frameBytes,
            // Large ready timeout so the ONLY thing that can unblock start() in
            // this test is the genuine ready-latch countDown (which happens after
            // the guard) — never an incidental await timeout while the reader is
            // deliberately held. That keeps the "start() still blocked" assertion
            // below load-bearing for the latch and free of timing fragility.
            readyTimeoutMs = 30_000L,
            onReaderThreadEntry = {
                readerAtEntry.countDown()
                // Park the reader BEFORE it can evaluate `while (capturing)`.
                releaseReader.await(5, TimeUnit.SECONDS)
            },
        )

        // start() runs on its own thread: WITH the ready latch it must BLOCK
        // inside start() until the reader passes the guard and counts the latch
        // down — and the reader is parked at the entry hook, so start() cannot
        // return yet. That blocking IS the latch's contract; we verify it.
        val startReturned = CountDownLatch(1)
        val startThread = Thread {
            pump.start()
            startReturned.countDown()
        }
        startThread.start()

        // Reader has reached the entry hook and is parked there, pre-guard.
        assertTrue(
            "reader thread must reach its entry hook",
            readerAtEntry.await(2, TimeUnit.SECONDS),
        )
        // Ready-latch contract, deterministic & load-bearing: while the reader is
        // held BEFORE the loop guard, the ready latch CANNOT have been counted down
        // (it is counted down only after the guard, one statement past this hook).
        // So WITH the latch, start() is causally guaranteed to still be parked in
        // readyLatch.await() — `startReturned` cannot count down no matter how long
        // we wait. We give it a generous window: a true here means start() returned
        // EARLY, which is exactly the neutered-latch behaviour (start() does not
        // wait for the reader at all) and exactly the start race that drops a live
        // frame. The neutered return is causally immediate and independent of the
        // held reader, so this window deterministically catches it; with the latch
        // the window provably elapses without a return.
        assertFalse(
            "ready-latch contract: start() must NOT return while the reader thread " +
                "is still parked before the `while (capturing)` guard — without the " +
                "latch start() returns early and a fast stop drops the live frame",
            startReturned.await(1, TimeUnit.SECONDS),
        )

        // Release the reader: it passes the guard, counts the ready latch down,
        // enters source.read(), and start() unblocks. All deterministic now.
        releaseReader.countDown()
        assertTrue(
            "start() must return once the reader passes the guard",
            startReturned.await(2, TimeUnit.SECONDS),
        )
        assertTrue(
            "by the time start() returns the reader has entered its first blocking " +
                "read — the frame is now recoverable",
            source.firstReadEntered.await(2, TimeUnit.SECONDS),
        )
        startThread.join(2_000)

        // Data-loss consequence end to end: with the read committed, the live
        // frame survives a stop; the drain alone could never have recovered it.
        val stopResult = arrayOfNulls<ByteArray>(1)
        val stopThread = Thread { stopResult[0] = pump.stop() }
        stopThread.start()
        // Tell the source to stop; the in-flight first read has already delivered
        // its frame, so stop() joins and returns the captured byte(s).
        source.stopped = true
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

    /**
     * A source that never runs dry — every blocking read delivers a full frame.
     * Models a mic that keeps streaming forever (a stuck/forgotten recording),
     * which is exactly the runaway the byte cap must bound. The drain has nothing
     * to add.
     */
    private class InfiniteSource(private val frameBytes: Int) : PcmSource {
        val totalReads = AtomicInteger(0)
        override fun read(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int {
            totalReads.incrementAndGet()
            loudFrame(frameBytes).copyInto(buffer, destinationOffset = offsetBytes)
            return sizeBytes
        }

        override fun readNonBlocking(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int = 0
    }

    @Test
    fun captureSelfStopsAtByteCapOnAMultipleOfFrameSize() {
        // A never-ending mic stream must not accumulate without bound. With a cap
        // of exactly 4 frames the reader loop self-stops after 4 frames instead of
        // growing forever (~19 MB per 10 min on-device), and flags that it capped.
        val cap = frameBytes * 4
        val source = InfiniteSource(frameBytes)
        val pump = PcmCapturePump(source = source, frameBytes = frameBytes, maxCaptureBytes = cap)

        pump.start()
        // The loop self-stops WITHOUT any external stop() once the cap trips.
        val cappedInTime = awaitTrue(2_000) { pump.captureCapReached() }
        assertTrue("capture must self-stop when the byte cap is reached", cappedInTime)

        val pcm = pump.stop()
        assertEquals("capture must not exceed the byte cap", cap, pcm.size)
        assertTrue("cap-reached flag must be set for the user notice", pump.captureCapReached())
    }

    @Test
    fun captureCapClampsToExactByteBudgetNotAWholeFrame() {
        // The cap is byte-exact, not frame-rounded: a cap that lands mid-frame
        // clamps the final write so the buffer is never even one frame over.
        val cap = frameBytes * 3 + 100
        val source = InfiniteSource(frameBytes)
        val pump = PcmCapturePump(source = source, frameBytes = frameBytes, maxCaptureBytes = cap)

        pump.start()
        assertTrue(awaitTrue(2_000) { pump.captureCapReached() })
        val pcm = pump.stop()

        assertEquals("cap must clamp to the exact byte budget", cap, pcm.size)
    }

    /**
     * A source whose blocking read always reports "no data available right now"
     * (return 0) — the underrunning-mic case. Without a backoff the reader loop
     * hot-spins on this; the test counts reads to prove the loop yields.
     */
    private class ZeroReadSource : PcmSource {
        val totalReads = AtomicInteger(0)
        override fun read(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int {
            totalReads.incrementAndGet()
            return 0
        }

        override fun readNonBlocking(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int = 0
    }

    @Test
    fun zeroLengthReadsBackOffInsteadOfBusySpinning() {
        // read == 0 must not pin a CPU core. With a 10 ms backoff, a ~120 ms
        // window can only fit ~12 reads; a hot-spin (no backoff) would rack up
        // many thousands. Asserting a low bound pins the yield: remove the
        // backoff and this goes red.
        val source = ZeroReadSource()
        val pump = PcmCapturePump(
            source = source,
            frameBytes = frameBytes,
            idleReadBackoffMs = 10L,
        )

        pump.start()
        Thread.sleep(120)
        pump.stop()

        val reads = source.totalReads.get()
        assertTrue("reader must actually poll at least once", reads > 0)
        assertTrue(
            "zero-length reads must back off, not hot-spin (saw $reads reads in ~120ms)",
            reads < 500,
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

/** Poll [condition] until it is true or [timeoutMs] elapses. */
private fun awaitTrue(timeoutMs: Long, condition: () -> Boolean): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (condition()) return true
        Thread.sleep(2)
    }
    return condition()
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
