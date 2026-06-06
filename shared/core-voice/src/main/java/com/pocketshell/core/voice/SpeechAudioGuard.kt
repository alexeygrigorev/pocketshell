package com.pocketshell.core.voice

import androidx.annotation.VisibleForTesting
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pre-transcription silence guard + post-transcription hallucination
 * backstop (issue #452).
 *
 * ## Why this exists
 *
 * Whisper is well documented to *hallucinate* on silent / very short /
 * low-energy audio: rather than returning an empty transcript it emits a
 * stock phrase — most infamously a "thanks for watching"-style line, often
 * in a language the user never spoke (the bug that opened #452 inserted the
 * Korean `시청해주셔서 감사합니다!` into the prompt after the maintainer recorded
 * effectively nothing).
 *
 * The robust fix is to never send near-silent audio to Whisper in the first
 * place. [hasSpeechEnergy] inspects the captured WAV and decides whether it
 * carries enough sound, for long enough, to be worth transcribing. When it
 * returns `false` the caller skips the network round-trip entirely and shows
 * a "no speech detected" hint instead of inserting hallucinated text.
 *
 * As a secondary guard — for the case where audio *does* clear the energy
 * bar but Whisper still returns a canned phrase — [isLikelyHallucination]
 * matches the transcript against a small cross-language blocklist of the
 * known stock outputs. This is deliberately conservative: it only fires when
 * the *entire* transcript is one of the known phrases, so a user who
 * genuinely says "thanks for watching" mid-sentence is never silenced.
 */
public object SpeechAudioGuard {

    /**
     * Root-mean-square amplitude (in the normalised `[0f, 1f]` range, where
     * `1f` is full-scale 16-bit PCM) below which a recording is treated as
     * "no speech". 0.006 sits comfortably above the noise floor of a quiet
     * room captured by the `VOICE_RECOGNITION` source (which already applies
     * noise suppression) while staying well below the RMS of even soft
     * speech. Empirically a silent capture lands near 0.000–0.002; quiet
     * conversational speech is ~0.02 and up.
     */
    public const val MIN_RMS_AMPLITUDE: Float = 0.006f

    /**
     * Lower RMS floor used only to decide whether a rejected recording is
     * worth preserving for retry. This does NOT make the audio eligible for
     * Whisper on the first pass; it only separates pure silence / mic noise
     * from "there is real captured signal here, but it landed below the
     * conservative speech threshold".
     */
    public const val MIN_RECOVERABLE_RMS_AMPLITUDE: Float = 0.002f

    /**
     * Minimum captured audio duration. A capture shorter than this almost
     * never contains a usable utterance — it is a fat-fingered double-tap on
     * the mic, or the auto-stop firing before the user spoke. Whisper's
     * hallucination rate is highest on these sub-half-second clips, so we
     * refuse to transcribe them regardless of energy.
     */
    public const val MIN_DURATION_MS: Long = 350L

    /**
     * Decide whether [wav] carries enough speech energy, for long enough, to
     * be worth transcribing.
     *
     * Returns `true` only when BOTH hold:
     *  - the audio is at least [MIN_DURATION_MS] long, and
     *  - its RMS amplitude is at least [MIN_RMS_AMPLITUDE].
     *
     * Anything else (empty buffer, header-only WAV, a too-short clip, or a
     * long-but-silent clip) returns `false` → the caller must skip Whisper
     * and surface the "no speech" hint.
     *
     * Defensive: a malformed / non-PCM-16 WAV that we cannot measure returns
     * `false` (treat unknown as "do not spend a transcription on it") rather
     * than throwing.
     */
    public fun hasSpeechEnergy(wav: ByteArray): Boolean {
        val analysis = analyze(wav)
        if (!analysis.hasRecognizedPcm || analysis.pcmByteCount <= 0) return false
        if (analysis.durationMs < MIN_DURATION_MS) return false
        return analysis.rmsAmplitude >= MIN_RMS_AMPLITUDE
    }

    /**
     * Whether a [hasSpeechEnergy] rejection is suspicious enough to preserve
     * for manual retry/export instead of being treated as plain silence.
     *
     * This deliberately stays narrower than [hasSpeechEnergy]: callers must
     * still skip Whisper on the first pass. We only return `true` for a
     * measurable, long-enough WAV with some non-trivial signal below the
     * speech floor. Header-only, too-short, malformed, and pure-silence
     * captures remain non-recoverable so #452 silence suppression is intact.
     */
    public fun isRecoverableNoSpeechRejection(wav: ByteArray): Boolean {
        val analysis = analyze(wav)
        return analysis.hasRecognizedPcm &&
            analysis.pcmByteCount > 0 &&
            analysis.durationMs >= MIN_DURATION_MS &&
            analysis.rmsAmplitude >= MIN_RECOVERABLE_RMS_AMPLITUDE &&
            analysis.rmsAmplitude < MIN_RMS_AMPLITUDE
    }

    /**
     * Measured shape of a captured WAV. Exposed so app-level code can make a
     * recoverability decision without duplicating WAV parsing.
     */
    public data class AudioEnergyAnalysis(
        val hasRecognizedPcm: Boolean,
        val durationMs: Long,
        val rmsAmplitude: Float,
        val pcmByteCount: Int,
    )

    public fun analyze(wav: ByteArray): AudioEnergyAnalysis {
        val pcm = pcm16DataChunk(wav) ?: return AudioEnergyAnalysis(
            hasRecognizedPcm = false,
            durationMs = 0L,
            rmsAmplitude = 0f,
            pcmByteCount = 0,
        )
        if (pcm.isEmpty()) {
            return AudioEnergyAnalysis(
                hasRecognizedPcm = true,
                durationMs = 0L,
                rmsAmplitude = 0f,
                pcmByteCount = 0,
            )
        }
        return AudioEnergyAnalysis(
            hasRecognizedPcm = true,
            durationMs = durationMsFromWav(wav),
            rmsAmplitude = rms16(pcm),
            pcmByteCount = pcm.size,
        )
    }

    /**
     * Whether [transcript] is, in its entirety, one of the known Whisper
     * silence-hallucination phrases.
     *
     * Conservative by design: the comparison is against the whole trimmed,
     * case-folded transcript (ignoring trailing punctuation), so this only
     * fires when Whisper returned *nothing but* a stock phrase. A real
     * sentence that happens to contain "thanks for watching" is not matched.
     *
     * This is the secondary backstop; the primary defence is
     * [hasSpeechEnergy] skipping the call before Whisper can hallucinate.
     */
    public fun isLikelyHallucination(transcript: String): Boolean {
        val normalised = normalisePhrase(transcript)
        if (normalised.isEmpty()) return false
        return normalised in HALLUCINATION_PHRASES
    }

    private val WHITESPACE_RUN = Regex("\\s+")
    private val TRIMMABLE_PUNCTUATION = ".!?。！？·…,，".toSet()

    /**
     * Known stock phrases Whisper emits on silent / near-silent audio,
     * across languages. Stored already-normalised (see [normalisePhrase])
     * so the lookup is a direct set membership test.
     *
     * Kept intentionally small and high-confidence — the "thanks for
     * watching" family plus a few of its most common sibling outputs. These
     * are the artefacts of Whisper's YouTube-heavy training data leaking
     * through on empty input; none is something a user dictating a shell
     * prompt would plausibly say as their *entire* utterance.
     */
    private val HALLUCINATION_PHRASES: Set<String> = buildSet {
        // English
        add(normalisePhrase("Thanks for watching!"))
        add(normalisePhrase("Thank you for watching!"))
        add(normalisePhrase("Thanks for watching."))
        add(normalisePhrase("Thank you for watching."))
        add(normalisePhrase("Thank you."))
        add(normalisePhrase("Thank you so much for watching."))
        add(normalisePhrase("Please subscribe to my channel."))
        // Korean — the exact phrase from the #452 report and its variants.
        add(normalisePhrase("시청해주셔서 감사합니다."))
        add(normalisePhrase("시청해주셔서 감사합니다!"))
        add(normalisePhrase("시청해 주셔서 감사합니다."))
        add(normalisePhrase("구독과 좋아요 부탁드립니다."))
        // Japanese
        add(normalisePhrase("ご視聴ありがとうございました。"))
        add(normalisePhrase("ご視聴ありがとうございます。"))
        // Spanish
        add(normalisePhrase("Gracias por ver el video."))
        add(normalisePhrase("Gracias por ver."))
        // Russian
        add(normalisePhrase("Спасибо за просмотр!"))
        add(normalisePhrase("Спасибо за просмотр."))
        // French
        add(normalisePhrase("Merci d'avoir regardé cette vidéo."))
        add(normalisePhrase("Sous-titrage Société Radio-Canada"))
    }

    /**
     * Normalise a phrase for blocklist comparison: trim surrounding
     * whitespace, drop trailing/leading punctuation and exclamation marks,
     * collapse internal whitespace runs to a single space, and lower-case.
     * Done so "Thanks for watching!", "thanks for watching", and "  Thanks
     * for watching .  " all map to the same key.
     */
    private fun normalisePhrase(raw: String): String {
        val collapsed = raw.trim().replace(WHITESPACE_RUN, " ")
        val stripped = collapsed.trim { it.isWhitespace() || it in TRIMMABLE_PUNCTUATION }
        return stripped.lowercase()
    }

    /**
     * Root-mean-square of signed 16-bit little-endian PCM samples,
     * normalised to `[0f, 1f]` against full-scale ([Short.MAX_VALUE]).
     */
    private fun rms16(pcm: ByteArray): Float {
        var sumSquares = 0.0
        var count = 0L
        var i = 0
        while (i + 1 < pcm.size) {
            val lo = pcm[i].toInt() and 0xFF
            val hi = pcm[i + 1].toInt()
            val sample = (hi shl 8) or lo
            val signed = if (sample and 0x8000 != 0) sample or 0xFFFF.inv() else sample
            val norm = signed / FULL_SCALE
            sumSquares += norm * norm
            count++
            i += 2
        }
        if (count == 0L) return 0f
        return sqrt(sumSquares / count).toFloat()
    }

    private const val FULL_SCALE: Double = 32_768.0

    /**
     * Extract the raw PCM bytes from the canonical WAV produced by
     * [AudioRecorder] (a 44-byte header followed by the data chunk). Returns
     * `null` for anything that is not a recognisable RIFF/WAVE/PCM-16 mono
     * container — we only ever measure audio we recorded ourselves.
     */
    private fun pcm16DataChunk(wav: ByteArray): ByteArray? {
        if (wav.size < 44) return null
        if (!wav.copyOfRange(0, 4).contentEquals("RIFF".toByteArray(Charsets.US_ASCII))) return null
        if (!wav.copyOfRange(8, 12).contentEquals("WAVE".toByteArray(Charsets.US_ASCII))) return null
        if (!wav.copyOfRange(12, 16).contentEquals("fmt ".toByteArray(Charsets.US_ASCII))) return null
        if (!wav.copyOfRange(36, 40).contentEquals("data".toByteArray(Charsets.US_ASCII))) return null

        val declaredDataSize = le32(wav, 40).toInt()
        val available = wav.size - 44
        if (available <= 0) return ByteArray(0)
        val size = if (declaredDataSize in 1..available) declaredDataSize else available
        return wav.copyOfRange(44, 44 + size)
    }

    /**
     * Audio duration in milliseconds derived from the RIFF header's
     * sample-rate / channel / bits-per-sample fields. Returns `0` for a
     * buffer that is not a measurable WAV.
     */
    private fun durationMsFromWav(wav: ByteArray): Long {
        if (wav.size < 44) return 0L
        val channels = le16(wav, 22)
        val sampleRate = le32(wav, 24)
        val bitsPerSample = le16(wav, 34)
        if (sampleRate <= 0 || channels <= 0 || bitsPerSample <= 0) return 0L

        val bytesPerSecond = sampleRate * channels * bitsPerSample / 8L
        if (bytesPerSecond <= 0) return 0L

        val dataSize = (wav.size - 44).coerceAtLeast(0)
        return dataSize * 1000L / bytesPerSecond
    }

    /**
     * Build a canonical 16 kHz mono 16-bit WAV that **passes**
     * [hasSpeechEnergy] — a one-second 0.3-amplitude tone standing in for
     * real speech. Exposed for tests across modules that drive the voice
     * FSMs and need a recording the silence guard will let through. Pass a
     * [durationMs] short enough and it can also be used to exercise the
     * too-short rejection path; the default clears both floors.
     */
    @VisibleForTesting
    public fun speechWavForTesting(durationMs: Int = 1_000, amplitude: Float = 0.3f): ByteArray {
        val sampleRate = 16_000
        val samples = sampleRate * durationMs / 1000
        val pcm = ByteArray(samples * 2)
        val peak = (amplitude.coerceIn(0f, 1f) * Short.MAX_VALUE).toInt()
        for (n in 0 until samples) {
            val v = (peak * sin(2.0 * PI * 220.0 * n / sampleRate)).toInt()
            val s = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[n * 2] = (s and 0xFF).toByte()
            pcm[n * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return buildWav(pcm = pcm, sampleRateHz = sampleRate, bitsPerSample = 16, channels = 1)
    }

    /**
     * Build a canonical 16 kHz mono 16-bit WAV of pure silence (all-zero
     * PCM) that **fails** [hasSpeechEnergy]. Exposed for tests that exercise
     * the silence-detection path with a realistic header-bearing capture
     * rather than a bare header.
     */
    @VisibleForTesting
    public fun silentWavForTesting(durationMs: Int = 1_000): ByteArray {
        val sampleRate = 16_000
        val samples = sampleRate * durationMs / 1000
        return buildWav(pcm = ByteArray(samples * 2), sampleRateHz = sampleRate, bitsPerSample = 16, channels = 1)
    }

    private fun le16(b: ByteArray, offset: Int): Int =
        (b[offset].toInt() and 0xFF) or ((b[offset + 1].toInt() and 0xFF) shl 8)

    private fun le32(b: ByteArray, offset: Int): Long =
        ((b[offset].toInt() and 0xFF).toLong()) or
            (((b[offset + 1].toInt() and 0xFF).toLong()) shl 8) or
            (((b[offset + 2].toInt() and 0xFF).toLong()) shl 16) or
            (((b[offset + 3].toInt() and 0xFF).toLong()) shl 24)
}
