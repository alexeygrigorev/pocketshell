package com.pocketshell.core.voice

import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * Platform-agnostic PCM capture pump — the testable core of [AudioRecorder]'s
 * reader thread.
 *
 * ## Why this exists (issue #587)
 *
 * The original capture loop lived inline in [AudioRecorder.start] and read
 * straight from the Android [android.media.AudioRecord]. That made the two
 * intermittent "real speech → No speech detected" lifecycle races impossible
 * to reproduce in a JVM unit test:
 *
 *  1. **Start race / empty buffer.** [AudioRecorder.start] flipped the FSM to
 *     `Recording` and returned to the caller *before* the freshly-spawned
 *     reader thread had run its first blocking `read()`. A very fast
 *     tap-then-stop (or an auto-stop firing immediately) could set
 *     `recording = false` before the loop's first iteration even began —
 *     the thread saw `while (recording)` already false and captured **zero
 *     bytes** even though the mic was open and the user spoke. `stop()` then
 *     returned a header-only WAV that the silence guard correctly rejected as
 *     "no speech" — a false negative on real speech.
 *
 *  2. **Tail clip on stop.** When [AudioRecorder.stop] requested a stop it set
 *     `recording = false` and joined the thread. Whatever the platform had
 *     already buffered inside `AudioRecord` (a frame or two of the final word)
 *     was never drained — it went to `record.stop()` / `record.release()`
 *     unread. On a short utterance that trailing clip is the difference
 *     between clearing and missing the speech-energy / duration floor.
 *
 * This pump fixes both:
 *
 *  - [start] blocks until the reader thread has confirmed it is *inside* the
 *    capture loop and committed to its first blocking read (the [readyLatch],
 *    counted down from inside the loop, after the `while (capturing)` guard).
 *    So the caller's FSM and the actual capture can no longer race: a stop
 *    requested after [start] returns is guaranteed to be observed by a thread
 *    that has already entered its first [PcmSource.read], and that read's frame
 *    survives even when the source keeps no drainable backing buffer.
 *  - [stop] requests the loop to finish, joins, then performs a bounded
 *    **drain** — non-blocking reads that pull any frames the source already
 *    buffered before the handle is torn down — so the tail of the utterance
 *    is preserved.
 *
 * The pump owns no Android types; it reads through the [PcmSource] seam so a
 * unit test can drive it with a deterministic fake and assert the two race
 * fixes without an emulator. [AudioRecorder] supplies an [PcmSource] backed by
 * a real `AudioRecord`.
 */
internal class PcmCapturePump(
    private val source: PcmSource,
    private val frameBytes: Int,
    // How long [start] will wait for the reader thread to confirm it is
    // running before giving up and returning anyway. Generous because the
    // only cost of waiting is start latency; we never want to fail a
    // recording just because thread scheduling was slow.
    private val readyTimeoutMs: Long = READY_TIMEOUT_MS,
    // Hard ceiling on how many PCM bytes a single capture may accumulate. Once
    // the buffer reaches this, the reader loop self-stops and [captureCapReached]
    // flips true so the caller can surface a "recording stopped (max length)"
    // notice. Bounds memory (a forgotten/stuck capture otherwise grows ~19 MB per
    // 10 min at 16 kHz mono 16-bit) and keeps the utterance inside the 60 s
    // Whisper transcription timeout. Default derived for the AudioRecorder format
    // (16 kHz mono 16-bit = 32000 B/s); a test can shrink it to exercise the cap.
    private val maxCaptureBytes: Int = DEFAULT_MAX_CAPTURE_BYTES,
    // Backoff applied when [PcmSource.read] returns 0 (no data available). A live
    // AudioRecord can legitimately return 0 while the mic underruns; looping on
    // it with no pause pins a CPU core (the busy-spin). A short sleep yields the
    // core without meaningfully delaying real frames. Test-tunable.
    private val idleReadBackoffMs: Long = IDLE_READ_BACKOFF_MS,
    // Test-only seam (issue #683): a hook the reader thread runs as its very
    // first action, BEFORE the `while (capturing)` guard. Production passes the
    // default no-op so behaviour is unchanged; the unit test injects a barrier
    // here to deterministically order a stop() against the loop guard and thus
    // exercise the start-race without depending on real thread scheduling. It
    // runs once, on the reader thread, and must not block in production.
    private val onReaderThreadEntry: () -> Unit = {},
) {
    private val buffer = ByteArrayOutputStream()
    private val readyLatch = CountDownLatch(1)

    @Volatile
    private var capturing: Boolean = false

    @Volatile
    private var lastAmplitude: Float = 0f

    @Volatile
    private var captureError: AudioRecorderException? = null

    // Set true when the capture self-stopped because it reached [maxCaptureBytes]
    // (as opposed to the caller stopping it or a platform error). Lets the caller
    // surface a "recording stopped at max length" notice to the user.
    @Volatile
    private var capReached: Boolean = false

    private var captureThread: Thread? = null

    /**
     * Spawn the reader thread and block until it has entered the capture loop
     * (or [readyTimeoutMs] elapses). On return the pump is guaranteed to be
     * observing [capturing], so a stop requested by the caller right after
     * cannot be lost to the start race.
     */
    fun start() {
        if (capturing) return
        capturing = true
        captureThread = thread(start = true, name = "core-voice-capture") {
            val frame = ByteArray(frameBytes)
            // Reader-thread entry hook (no-op in production). The test uses it to
            // hold the reader BEFORE the loop guard so it can deterministically
            // decide whether a stop() wins the start race — see issue #683.
            onReaderThreadEntry()
            var first = true
            while (capturing) {
                // Signal readiness from INSIDE the loop, on the first iteration,
                // only after the `while (capturing)` guard has already been
                // passed and we are committed to the first blocking read. This
                // is the load-bearing ordering for issue #587 race #1: by the
                // time start() unblocks, the reader thread is guaranteed to be
                // entering source.read(), so a stop() that flips capturing=false
                // right after start() returns can no longer skip the first read
                // and drop the whole utterance. Signalling *before* the guard
                // (as an earlier version did) left a window where stop() could
                // flip the flag between countDown and the guard, the read never
                // ran, and live-stream audio with no drainable backing buffer
                // was lost — the literal "real speech -> No speech detected".
                if (first) {
                    first = false
                    readyLatch.countDown()
                }
                val read = source.read(frame, 0, frame.size)
                if (read > 0) {
                    // Never accumulate past the cap: clamp this frame's write to
                    // the remaining budget, then self-stop. Bounds memory and
                    // keeps the utterance within the Whisper timeout.
                    val remaining = maxCaptureBytes - buffer.size()
                    val writable = read.coerceAtMost(remaining)
                    if (writable > 0) {
                        buffer.write(frame, 0, writable)
                        lastAmplitude = peakAmplitude(frame, writable)
                    }
                    if (buffer.size() >= maxCaptureBytes) {
                        capReached = true
                        capturing = false
                    }
                } else if (read < 0) {
                    captureError = mapAudioReadErrorCode(read)
                    capturing = false
                } else {
                    // read == 0: no data available right now. Yield the core
                    // instead of hot-spinning until the next frame or a stop.
                    if (idleReadBackoffMs > 0) {
                        try {
                            Thread.sleep(idleReadBackoffMs)
                        } catch (_: InterruptedException) {
                            Thread.currentThread().interrupt()
                            capturing = false
                        }
                    }
                }
            }
        }
        // Wait for the reader thread to be inside the loop. If scheduling is
        // pathologically slow we still proceed after the timeout rather than
        // blocking the UI thread forever.
        runCatching { readyLatch.await(readyTimeoutMs, TimeUnit.MILLISECONDS) }
    }

    /**
     * Stop capturing and return the accumulated PCM bytes.
     *
     * Order matters: request stop, join the reader thread, then drain any
     * residual frames the source buffered before the handle is released. The
     * drain is what preserves the tail of the final word (race #2 above).
     *
     * @throws AudioRecorderException if the reader thread saw a platform error
     *   mid-capture.
     */
    @Throws(AudioRecorderException::class)
    fun stop(): ByteArray {
        if (captureThread == null && !capturing) {
            // Never started, or already stopped — nothing to drain.
            val pcm = buffer.toByteArray()
            buffer.reset()
            captureError?.let { err ->
                captureError = null
                throw err
            }
            return pcm
        }
        capturing = false
        captureThread?.join()
        captureThread = null

        drainResidual()

        val pcm = buffer.toByteArray()
        buffer.reset()
        captureError?.let { err ->
            captureError = null
            throw err
        }
        return pcm
    }

    fun currentAmplitude(): Float = lastAmplitude

    /**
     * True when the last capture self-stopped because it hit [maxCaptureBytes]
     * rather than being stopped by the caller. The caller uses this to tell the
     * user the recording was cut at the maximum length.
     */
    fun captureCapReached(): Boolean = capReached

    /**
     * Pull whatever the source already has buffered, without blocking. The
     * real `AudioRecord` keeps a small ring buffer the HAL fills; the inline
     * loop abandoned it on stop. We read in [DRAIN_FRAME_BUDGET] bounded
     * non-blocking passes so a hot mic can't keep us here indefinitely.
     */
    private fun drainResidual() {
        val frame = ByteArray(frameBytes)
        var passes = 0
        while (passes < DRAIN_FRAME_BUDGET) {
            val remaining = maxCaptureBytes - buffer.size()
            if (remaining <= 0) break
            val read = source.readNonBlocking(frame, 0, frame.size)
            if (read <= 0) break
            val writable = read.coerceAtMost(remaining)
            buffer.write(frame, 0, writable)
            lastAmplitude = peakAmplitude(frame, writable)
            passes++
        }
    }

    private fun peakAmplitude(frame: ByteArray, validBytes: Int): Float {
        var peak = 0
        var i = 0
        while (i + 1 < validBytes) {
            val lo = frame[i].toInt() and 0xFF
            val hi = frame[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val signed = if (sample and 0x8000 != 0) sample or 0xFFFF.inv() else sample
            val mag = abs(signed)
            if (mag > peak) peak = mag
            i += 2
        }
        return (peak / Short.MAX_VALUE.toFloat()).coerceIn(0f, 1f)
    }

    companion object {
        const val READY_TIMEOUT_MS: Long = 500L

        // Default capture ceiling. AudioRecorder records 16 kHz mono 16-bit =
        // 32000 B/s, so 60 s ≈ 1.92 MB. This bounds memory (vs ~19 MB for a
        // forgotten 10-min capture) AND keeps a single utterance within the 60 s
        // Whisper transcription timeout. When the cap trips the capture stops and
        // [captureCapReached] flips so the user can be told why.
        const val DEFAULT_MAX_CAPTURE_BYTES: Int = 32_000 * 60

        // Sleep applied on a zero-length read so an underrunning/idle mic can't
        // hot-spin the reader thread.
        const val IDLE_READ_BACKOFF_MS: Long = 5L

        // Cap the post-stop drain. At 16 kHz mono 16-bit a typical frame is
        // tens of ms; a handful of passes covers the AudioRecord ring buffer
        // without risking an unbounded loop if the mic is still hot.
        const val DRAIN_FRAME_BUDGET: Int = 16
    }
}

/**
 * The byte source [PcmCapturePump] reads from. Production wraps an Android
 * `AudioRecord`; tests supply a deterministic fake. Kept tiny on purpose so
 * the pump stays Android-free and JVM-unit-testable.
 */
internal interface PcmSource {
    /**
     * Blocking read, mirroring `AudioRecord.read(byte[], int, int)`: returns
     * the number of bytes read (>0), `0` if no data was available, or a
     * negative `AudioRecord.ERROR_*` sentinel.
     */
    fun read(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int

    /**
     * Non-blocking read used during the stop drain. Returns bytes already
     * buffered by the source (>0), or `0`/negative when nothing more is
     * immediately available. Implementations must never block here — the
     * drain is a best-effort tail capture, not a wait.
     */
    fun readNonBlocking(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int
}
