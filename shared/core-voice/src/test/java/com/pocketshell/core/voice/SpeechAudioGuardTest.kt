package com.pocketshell.core.voice

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * Unit tests for [SpeechAudioGuard] — issue #452.
 *
 * Covers the two guards the composer / inline-dictation pipelines rely on:
 *
 *  - [SpeechAudioGuard.hasSpeechEnergy] returns `false` for silent,
 *    header-only, too-short, and low-energy WAV captures (so the caller
 *    skips Whisper and shows "no speech detected"), and `true` for a clip
 *    that carries real speech-level energy for long enough.
 *  - [SpeechAudioGuard.isLikelyHallucination] matches the known
 *    silence-hallucination phrases (including the exact Korean phrase from
 *    the #452 report) while leaving real dictation untouched.
 *
 * All WAV fixtures are built with the same canonical [buildWav] helper the
 * production [AudioRecorder] uses, so the header layout the guard parses is
 * identical to what ships.
 */
class SpeechAudioGuardTest {

    private val sampleRate = 16_000
    private val bitsPerSample = 16
    private val channels = 1

    /** Build a mono 16 kHz 16-bit WAV from raw little-endian PCM bytes. */
    private fun wav(pcm: ByteArray): ByteArray =
        buildWav(pcm = pcm, sampleRateHz = sampleRate, bitsPerSample = bitsPerSample, channels = channels)

    /** PCM bytes for [durationMs] of pure silence (all-zero samples). */
    private fun silentPcm(durationMs: Int): ByteArray {
        val samples = sampleRate * durationMs / 1000
        return ByteArray(samples * 2) // zero-filled
    }

    /**
     * PCM bytes for [durationMs] of a [amplitude]-scaled sine tone — a
     * stand-in for "real speech energy". [amplitude] is in `[0f, 1f]`
     * relative to full-scale 16-bit.
     */
    private fun tonePcm(durationMs: Int, amplitude: Float): ByteArray {
        val samples = sampleRate * durationMs / 1000
        val out = ByteArray(samples * 2)
        val peak = (amplitude * Short.MAX_VALUE).toInt()
        for (n in 0 until samples) {
            val v = (peak * sin(2.0 * PI * 220.0 * n / sampleRate)).toInt()
            val s = v.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            out[n * 2] = (s and 0xFF).toByte()
            out[n * 2 + 1] = ((s shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun silentRecordingHasNoSpeechEnergy() {
        // A full second of pure silence — long enough to clear the duration
        // floor, but zero RMS. This is the canonical "user recorded nothing"
        // case that made Whisper hallucinate.
        assertFalse(SpeechAudioGuard.hasSpeechEnergy(wav(silentPcm(1_000))))
    }

    @Test
    fun headerOnlyWavHasNoSpeechEnergy() {
        // AudioRecorder.stop() returns a 44-byte header even for a zero-PCM
        // session; that must never be transcribed.
        assertFalse(SpeechAudioGuard.hasSpeechEnergy(wav(ByteArray(0))))
    }

    @Test
    fun emptyBufferHasNoSpeechEnergy() {
        assertFalse(SpeechAudioGuard.hasSpeechEnergy(ByteArray(0)))
    }

    @Test
    fun tooShortLoudClipHasNoSpeechEnergy() {
        // Loud, but only 100ms — below the 350ms duration floor. A
        // fat-fingered double-tap on the mic must be dropped.
        assertFalse(SpeechAudioGuard.hasSpeechEnergy(wav(tonePcm(100, amplitude = 0.5f))))
    }

    @Test
    fun longButVeryQuietClipHasNoSpeechEnergy() {
        // A full second but at an amplitude far under the RMS floor (room
        // hum / mic self-noise). Must be treated as silence.
        assertFalse(SpeechAudioGuard.hasSpeechEnergy(wav(tonePcm(1_000, amplitude = 0.001f))))
    }

    @Test
    fun marginalLongClipIsRecoverableButNotFirstPassSpeech() {
        val audio = wav(tonePcm(1_000, amplitude = 0.004f))
        assertFalse(SpeechAudioGuard.hasSpeechEnergy(audio))
        assertTrue(SpeechAudioGuard.isRecoverableNoSpeechRejection(audio))
    }

    @Test
    fun pureSilenceIsNotRecoverableNoSpeech() {
        assertFalse(SpeechAudioGuard.isRecoverableNoSpeechRejection(wav(silentPcm(1_000))))
    }

    @Test
    fun tooShortLoudClipIsNotRecoverableNoSpeech() {
        assertFalse(SpeechAudioGuard.isRecoverableNoSpeechRejection(wav(tonePcm(100, amplitude = 0.5f))))
    }

    @Test
    fun realSpeechLevelClipHasSpeechEnergy() {
        // Conversational speech RMS is ~0.02+. A 0.3-amplitude sine over a
        // full second is comfortably above the floor and long enough.
        assertTrue(SpeechAudioGuard.hasSpeechEnergy(wav(tonePcm(1_000, amplitude = 0.3f))))
    }

    @Test
    fun shortButValidSpeechClipHasSpeechEnergy() {
        // 400ms of real-level audio clears both floors — a quick "yes".
        assertTrue(SpeechAudioGuard.hasSpeechEnergy(wav(tonePcm(400, amplitude = 0.3f))))
    }

    @Test
    fun koreanThanksForWatchingIsHallucination() {
        // The exact phrase from the #452 report, with and without trailing
        // punctuation.
        assertTrue(SpeechAudioGuard.isLikelyHallucination("시청해주셔서 감사합니다!"))
        assertTrue(SpeechAudioGuard.isLikelyHallucination("시청해주셔서 감사합니다."))
        assertTrue(SpeechAudioGuard.isLikelyHallucination("  시청해주셔서 감사합니다  "))
    }

    @Test
    fun englishThanksForWatchingIsHallucination() {
        assertTrue(SpeechAudioGuard.isLikelyHallucination("Thanks for watching!"))
        assertTrue(SpeechAudioGuard.isLikelyHallucination("thank you for watching"))
        assertTrue(SpeechAudioGuard.isLikelyHallucination("Thank you."))
    }

    @Test
    fun crossLanguageStockPhrasesAreHallucinations() {
        assertTrue(SpeechAudioGuard.isLikelyHallucination("ご視聴ありがとうございました。"))
        assertTrue(SpeechAudioGuard.isLikelyHallucination("Спасибо за просмотр!"))
        assertTrue(SpeechAudioGuard.isLikelyHallucination("Gracias por ver."))
    }

    @Test
    fun realDictationIsNotHallucination() {
        // Genuine shell prompts must pass through untouched.
        assertFalse(SpeechAudioGuard.isLikelyHallucination("git status"))
        assertFalse(SpeechAudioGuard.isLikelyHallucination("run the tests and fix the failures"))
        // Even a sentence that *contains* the stock phrase mid-utterance is
        // not silenced — only a whole-transcript match fires.
        assertFalse(
            SpeechAudioGuard.isLikelyHallucination(
                "tell the user thanks for watching the demo and then exit",
            ),
        )
    }

    @Test
    fun emptyTranscriptIsNotHallucination() {
        assertFalse(SpeechAudioGuard.isLikelyHallucination(""))
        assertFalse(SpeechAudioGuard.isLikelyHallucination("   "))
    }
}
