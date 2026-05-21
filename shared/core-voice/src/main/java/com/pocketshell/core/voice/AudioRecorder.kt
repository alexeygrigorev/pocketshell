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

    /**
     * Open the microphone and start capturing.
     *
     * Caller is responsible for holding [Manifest.permission.RECORD_AUDIO];
     * the annotation makes that explicit at the call site. Calling [start]
     * twice without an intervening [stop] is a no-op (returns immediately).
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    @SuppressLint("MissingPermission")
    public fun start() {
        if (recording) return

        buffer.reset()
        lastAmplitude = 0f

        // `getMinBufferSize` is the smallest internal buffer AudioRecord
        // will accept on this device. We double it so a brief reader-thread
        // stall doesn't drop samples.
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE_HZ, CHANNEL, ENCODING)
        require(minBuf > 0) { "AudioRecord.getMinBufferSize returned $minBuf (unsupported config)" }
        val internalBufferBytes = minBuf * 2

        val record = AudioRecord(
            // VOICE_RECOGNITION applies a noise-suppression profile tuned for
            // speech (vs MIC which is unfiltered). Whisper handles noise OK,
            // but the pre-processing measurably helps quality on cheap mics.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE_HZ,
            CHANNEL,
            ENCODING,
            internalBufferBytes,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord failed to initialise (state=${record.state})"
        }

        audioRecord = record
        recording = true
        record.startRecording()

        captureThread = thread(start = true, name = "core-voice-capture") {
            val frame = ByteArray(internalBufferBytes)
            while (recording) {
                val read = record.read(frame, 0, frame.size)
                if (read > 0) {
                    buffer.write(frame, 0, read)
                    lastAmplitude = peakAmplitude(frame, read)
                }
            }
        }
    }

    /**
     * Stop recording and return the captured audio as a WAV file (PCM
     * 16-bit LE, mono, 16 kHz). Calling [stop] without a prior [start]
     * returns an empty `ByteArray`.
     */
    public fun stop(): ByteArray {
        if (!recording) return ByteArray(0)
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
