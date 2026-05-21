package com.pocketshell.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * PCM voice recorder shaped for Whisper.
 *
 * Captures **16-bit mono PCM at 16 kHz** — Whisper's preferred input format,
 * cheap to encode, and the lowest sample rate that doesn't degrade speech
 * quality. On [stop] the buffer is wrapped in a WAV container so the bytes
 * can go straight to [WhisperClient.transcribe] without further encoding.
 *
 * Threading: [start] spawns a single reader thread that pumps bytes out of
 * the platform [AudioRecord] into an in-memory buffer. [stop] joins that
 * thread, releases the [AudioRecord], and returns the WAV-encoded bytes.
 *
 * @param context only used to acquire the `RECORD_AUDIO` permission check
 *   implicitly via [AudioRecord]; the field is retained as application
 *   context so we don't leak an Activity reference.
 */
public class AudioRecorder(context: Context) {

    private val appContext: Context = context.applicationContext

    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private val buffer = ByteArrayOutputStream()

    // Updated by the reader thread, read by the UI thread for waveform
    // rendering. Plain `@Volatile` is sufficient — no need for locks since
    // there's exactly one writer.
    @Volatile
    private var lastAmplitude: Float = 0f

    @Volatile
    private var recording: Boolean = false

    // Errors raised by the reader thread mid-capture. Surfaced by `stop()`
    // so the caller doesn't have to install a thread-level handler.
    @Volatile
    private var captureError: AudioRecorderException? = null

    /**
     * Open the microphone and start capturing.
     *
     * Caller is responsible for holding [Manifest.permission.RECORD_AUDIO];
     * the annotation makes that explicit at the call site. Calling [start]
     * twice without an intervening [stop] is a no-op (returns immediately).
     *
     * Throws an [AudioRecorderException] subtype on failure — see the sealed
     * hierarchy for the discriminated variants. The platform's raw
     * `IllegalStateException` / `SecurityException` never escape this method.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("MissingPermission")
    @Throws(AudioRecorderException::class)
    public fun start() {
        if (recording) return

        buffer.reset()
        lastAmplitude = 0f
        captureError = null

        // `getMinBufferSize` is the smallest internal buffer AudioRecord
        // will accept on this device. Negative returns are sentinel error
        // codes (ERROR_BAD_VALUE = -2, ERROR = -1) indicating the requested
        // config is unsupported — surface as NoDevice.
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL, ENCODING)
        if (minBuf <= 0) {
            throw AudioRecorderException.NoDevice(
                "AudioRecord.getMinBufferSize returned $minBuf (unsupported config or no input device)",
            )
        }
        val internalBufferBytes = minBuf * 2

        val record = try {
            AudioRecord(
                // VOICE_RECOGNITION applies a noise-suppression profile tuned for
                // speech (vs MIC which is unfiltered). Whisper handles noise OK,
                // but the pre-processing measurably helps quality on cheap mics.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE_HZ,
                CHANNEL,
                ENCODING,
                internalBufferBytes,
            )
        } catch (sec: SecurityException) {
            throw AudioRecorderException.PermissionDenied(
                sec.message ?: "RECORD_AUDIO permission denied",
                sec,
            )
        } catch (iae: IllegalArgumentException) {
            // AudioRecord constructor rejects an unsupported sample rate /
            // channel / encoding combination with IAE.
            throw AudioRecorderException.Initialization(
                iae.message ?: "AudioRecord rejected configuration",
                iae,
            )
        }

        if (record.state != AudioRecord.STATE_INITIALIZED) {
            // We own the AudioRecord at this point but never started it; release
            // the platform handle before throwing so we don't leak the mic.
            runCatching { record.release() }
            throw AudioRecorderException.Initialization(
                "AudioRecord failed to initialise (state=${record.state})",
            )
        }

        audioRecord = record
        recording = true
        try {
            record.startRecording()
        } catch (ise: IllegalStateException) {
            recording = false
            audioRecord = null
            runCatching { record.release() }
            throw AudioRecorderException.Initialization(
                ise.message ?: "AudioRecord.startRecording rejected (uninitialised)",
                ise,
            )
        }

        captureThread = thread(start = true, name = "core-voice-capture") {
            val frame = ByteArray(internalBufferBytes)
            while (recording) {
                val read = record.read(frame, 0, frame.size)
                if (read > 0) {
                    buffer.write(frame, 0, read)
                    lastAmplitude = peakAmplitude(frame, read)
                } else if (read < 0) {
                    // Platform sentinel returned mid-capture. Stop the loop,
                    // park the typed error for stop() to re-throw.
                    captureError = mapAudioReadErrorCode(read)
                    recording = false
                }
            }
        }
    }

    /**
     * Stop recording and return the captured audio as a WAV file (PCM
     * 16-bit LE, mono, 16 kHz). Calling [stop] without a prior [start]
     * returns an empty `ByteArray`.
     *
     * Throws an [AudioRecorderException] if the reader thread observed a
     * platform error mid-capture (e.g. the mic was unplugged). The platform's
     * raw `IllegalStateException` from `AudioRecord.stop()` is swallowed —
     * the only surfaced failure is the typed sealed type.
     */
    @Throws(AudioRecorderException::class)
    public fun stop(): ByteArray {
        // Capture the active flag *before* clearing it. We've explicitly seen
        // `recording = false` set by the reader thread when it hit a sentinel
        // error, but we still need to drain and release the AudioRecord.
        val wasActive = recording || audioRecord != null
        if (!wasActive) return ByteArray(0)
        recording = false
        captureThread?.join()
        captureThread = null

        val record = audioRecord
        audioRecord = null
        try {
            record?.stop()
        } catch (_: IllegalStateException) {
            // Some devices throw if stop() is called and the recorder was
            // already in STOPPED state. Safe to swallow.
        }
        record?.release()

        val pcm = buffer.toByteArray()
        buffer.reset()

        captureError?.let { err ->
            captureError = null
            throw err
        }

        return wrapInWav(pcm)
    }

    /**
     * Current peak amplitude in `[0f, 1f]`. Updated each time the reader
     * thread pulls a buffer; reads outside of an active recording return the
     * last seen value (zero after a fresh [start]).
     */
    public fun currentAmplitude(): Float = lastAmplitude

    /**
     * Build a WAV (RIFF) file from the captured PCM. We control the format
     * ourselves so there's no surprise from a device codec — Whisper sniffs
     * the container, and a hand-built RIFF header is the safest contract.
     *
     * Exposed `internal` for tests.
     */
    internal fun wrapInWav(pcm: ByteArray): ByteArray = buildWav(
        pcm = pcm,
        sampleRateHz = SAMPLE_RATE_HZ,
        bitsPerSample = BITS_PER_SAMPLE,
        channels = CHANNELS,
    )

    private fun peakAmplitude(frame: ByteArray, validBytes: Int): Float {
        // 16-bit little-endian samples; peak magnitude over the buffer
        // normalised to [0f, 1f].
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

    private companion object {
        // Whisper docs explicitly mention 16 kHz as the model's working
        // resolution. Sending higher just means OpenAI resamples on their
        // side, costing bandwidth for no quality gain.
        const val SAMPLE_RATE_HZ = 16_000
        const val BITS_PER_SAMPLE = 16
        const val CHANNELS = 1
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}

/**
 * Build a canonical RIFF/WAVE container around raw PCM bytes.
 *
 * Lives at file scope (and is `internal`) so unit tests can verify the
 * header layout without instantiating the recorder — which would require
 * an Android microphone.
 */
internal fun buildWav(
    pcm: ByteArray,
    sampleRateHz: Int,
    bitsPerSample: Int,
    channels: Int,
): ByteArray {
    val byteRate = sampleRateHz * channels * bitsPerSample / 8
    val blockAlign = channels * bitsPerSample / 8
    val dataSize = pcm.size
    val chunkSize = 36 + dataSize

    // 44-byte canonical WAV header.
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
    header.put("RIFF".toByteArray(Charsets.US_ASCII))
    header.putInt(chunkSize)
    header.put("WAVE".toByteArray(Charsets.US_ASCII))
    header.put("fmt ".toByteArray(Charsets.US_ASCII))
    header.putInt(16) // PCM subchunk size
    header.putShort(1) // PCM format
    header.putShort(channels.toShort())
    header.putInt(sampleRateHz)
    header.putInt(byteRate)
    header.putShort(blockAlign.toShort())
    header.putShort(bitsPerSample.toShort())
    header.put("data".toByteArray(Charsets.US_ASCII))
    header.putInt(dataSize)

    return header.array() + pcm
}

/**
 * Map a negative `AudioRecord.read` return code onto the right
 * [AudioRecorderException] variant. Exposed at file scope (and `internal`) so
 * the unit test can assert the classification table without driving an
 * AudioRecord — which would require an Android microphone.
 */
internal fun mapAudioReadErrorCode(code: Int): AudioRecorderException = when (code) {
    android.media.AudioRecord.ERROR_DEAD_OBJECT -> AudioRecorderException.NoDevice(
        "AudioRecord.read returned ERROR_DEAD_OBJECT (input device gone)",
    )
    android.media.AudioRecord.ERROR_INVALID_OPERATION -> AudioRecorderException.Underrun(
        "AudioRecord.read returned ERROR_INVALID_OPERATION (recorder not active)",
    )
    android.media.AudioRecord.ERROR_BAD_VALUE -> AudioRecorderException.Initialization(
        "AudioRecord.read returned ERROR_BAD_VALUE",
    )
    else -> AudioRecorderException.Other(
        "AudioRecord.read returned unexpected error code $code",
    )
}
