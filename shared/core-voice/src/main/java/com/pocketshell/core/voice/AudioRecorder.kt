package com.pocketshell.core.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * PCM voice recorder shaped for Whisper.
 *
 * Captures **16-bit mono PCM at 16 kHz** — Whisper's preferred input format,
 * cheap to encode, and the lowest sample rate that doesn't degrade speech
 * quality. On [stop] the buffer is wrapped in a WAV container so the bytes
 * can go straight to [WhisperClient.transcribe] without further encoding.
 *
 * Threading: [start] hands the platform [AudioRecord] to a [PcmCapturePump]
 * which spawns a single reader thread, blocks until that thread is confirmed
 * inside its read loop (so a fast tap-then-stop can't race the capture and
 * drop the whole utterance — issue #587), and pumps bytes into an in-memory
 * buffer. [stop] joins that thread, drains any audio the platform still has
 * buffered (so the tail of the final word isn't clipped — issue #587),
 * releases the [AudioRecord], and returns the WAV-encoded bytes.
 *
 * @param context only used to acquire the `RECORD_AUDIO` permission check
 *   implicitly via [AudioRecord]; the field is retained as application
 *   context so we don't leak an Activity reference.
 */
public class AudioRecorder(context: Context) {

    private val appContext: Context = context.applicationContext

    private var audioRecord: AudioRecord? = null

    // Owns the reader thread, the in-memory PCM buffer, the start-readiness
    // latch, and the post-stop drain. Extracted so the capture lifecycle
    // (the source of the issue #587 races) is unit-testable on the JVM with
    // a fake source. Non-null only while a recording is in flight.
    private var pump: PcmCapturePump? = null

    @Volatile
    private var recording: Boolean = false

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

        // Hand the live AudioRecord to the pump. start() blocks until the
        // reader thread is confirmed inside the capture loop, closing the
        // start race where a fast tap-then-stop dropped the whole utterance.
        pump = PcmCapturePump(
            source = AudioRecordPcmSource(record),
            frameBytes = internalBufferBytes,
        ).also { it.start() }
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

        // The pump joins the reader thread and drains any audio still buffered
        // by AudioRecord before we tear the handle down (so the tail of the
        // final word survives — issue #587). It also re-throws any mid-capture
        // platform error the reader thread parked. Pull the PCM *before*
        // releasing the AudioRecord; the drain reads through the still-live
        // handle.
        val activePump = pump
        pump = null
        val record = audioRecord
        audioRecord = null

        val pcm: ByteArray = try {
            activePump?.stop() ?: ByteArray(0)
        } finally {
            try {
                record?.stop()
            } catch (_: IllegalStateException) {
                // Some devices throw if stop() is called and the recorder was
                // already in STOPPED state. Safe to swallow.
            }
            record?.release()
        }

        return wrapInWav(pcm)
    }

    /**
     * Current peak amplitude in `[0f, 1f]`. Updated each time the reader
     * thread pulls a buffer; reads outside of an active recording return the
     * last seen value (zero after a fresh [start]).
     */
    public fun currentAmplitude(): Float = pump?.currentAmplitude() ?: 0f

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

/**
 * [PcmSource] backed by a live Android [AudioRecord]. The blocking [read]
 * mirrors the platform default; [readNonBlocking] uses
 * [AudioRecord.READ_NON_BLOCKING] so the post-stop drain pulls already-buffered
 * frames without ever parking on the mic. Lives at file scope so the pump
 * stays Android-free and unit-testable with a fake source.
 */
private class AudioRecordPcmSource(
    private val record: AudioRecord,
) : PcmSource {
    override fun read(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int =
        record.read(buffer, offsetBytes, sizeBytes)

    override fun readNonBlocking(buffer: ByteArray, offsetBytes: Int, sizeBytes: Int): Int =
        record.read(buffer, offsetBytes, sizeBytes, AudioRecord.READ_NON_BLOCKING)
}
