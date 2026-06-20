package com.pocketshell.app.voice

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.composer.PromptComposerViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

/**
 * Unit tests for [AndroidSpeechRecognitionProvider] (issue #590).
 *
 * Reproduce-first (D32 / G10): the maintainer dogfooded the Android speech
 * provider and reported it "stops too quickly". Two root causes are exercised
 * here, each as a red→green pair against the unfixed provider:
 *
 *  1. The intent was built WITHOUT the endpointer silence-length extras, so the
 *     recognizer fell back to the short system default and cut off after a brief
 *     pause. [intent_setsGenerousSilenceLengthExtras] asserts the extras are now
 *     present — it fails on the base (no extras put) and passes with the fix.
 *
 *  2. A recognizer-side turn end (a pause → end-of-speech / no-match /
 *     speech-timeout) terminated the WHOLE dictation. The base provider's
 *     listener mapped these straight to `onFinal` / `onError`. With the fix the
 *     session restarts and accumulates, so a pause rolls into the next phrase.
 *     [pause_restartsAndAccumulatesAcrossTurns] /
 *     [noMatchMidDictation_restartsInsteadOfTerminating] cover that.
 *
 * Uses Robolectric's [ShadowSpeechRecognizer] so the test drives the REAL
 * production path (default recognizer factory → `createSpeechRecognizer`), then
 * fires recognizer callbacks via the shadow — no stand-in for the view under
 * test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class AndroidSpeechRecognitionProviderTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ShadowSpeechRecognizer.reset()
        registerFakeRecognitionService()
    }

    /**
     * `SpeechRecognizer.isRecognitionAvailable` queries the PackageManager for a
     * [RecognitionService]. Under Robolectric none is installed, so register a
     * resolvable service component — this makes `start()` take the real path
     * (the provider's availability guard returns true).
     */
    private fun registerFakeRecognitionService() {
        val shadowPm = shadowOf(context.packageManager)
        val component = ComponentName("com.example.recognizer", "FakeRecognitionService")
        val resolveInfo = ResolveInfo().apply {
            serviceInfo = ServiceInfo().apply {
                packageName = component.packageName
                name = component.className
            }
        }
        shadowPm.addServiceIfNotPresent(component)
        shadowPm.addResolveInfoForIntent(
            Intent(RecognitionService.SERVICE_INTERFACE),
            resolveInfo,
        )
    }

    /** Collects every listener callback so tests can assert the final state. */
    private class RecordingListener : PromptComposerViewModel.SpeechRecognitionListener {
        val partials = mutableListOf<String>()
        var finalText: String? = null
        var error: String? = null

        override fun onPartial(text: String) {
            partials += text
        }

        override fun onFinal(text: String) {
            finalText = text
        }

        override fun onError(message: String) {
            error = message
        }
    }

    private fun resultsBundle(text: String): Bundle = Bundle().apply {
        putStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION, arrayListOf(text))
    }

    /**
     * The shadow's `startListening` posts a task to the main looper that binds
     * the [RecognitionService] (and records the recognition listener). Idle the
     * looper so callbacks can be fired before the test triggers them.
     */
    private fun idle() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Latest recognizer after letting the start task run. */
    private fun latestRecognizer(): SpeechRecognizer {
        idle()
        return ShadowSpeechRecognizer.getLatestSpeechRecognizer()
    }

    // ---- Cause 1: endpointer silence-length extras --------------------------

    @Test
    fun intent_setsGenerousSilenceLengthExtras() {
        val provider = AndroidSpeechRecognitionProvider(context)
        val intent = provider.buildRecognizerIntent(language = "en-US")

        // The load-bearing fix: the endpointer waits long enough to survive a
        // natural mid-sentence pause. Base code put none of these.
        assertEquals(
            AndroidSpeechRecognitionProvider.DEFAULT_COMPLETE_SILENCE_MS,
            intent.getLongExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS,
                -1L,
            ),
        )
        assertEquals(
            AndroidSpeechRecognitionProvider.DEFAULT_POSSIBLY_COMPLETE_SILENCE_MS,
            intent.getLongExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                -1L,
            ),
        )
        assertEquals(
            AndroidSpeechRecognitionProvider.DEFAULT_MINIMUM_LENGTH_MS,
            intent.getLongExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS,
                -1L,
            ),
        )
        // The values are comfortably above the short system default that caused
        // the cut-off (~700ms–1s on many recognizers).
        assertTrue(AndroidSpeechRecognitionProvider.DEFAULT_COMPLETE_SILENCE_MS >= 1_500L)
    }

    @Test
    fun intent_keepsFreeFormPartialResultsAndLanguage() {
        val provider = AndroidSpeechRecognitionProvider(context)
        val intent = provider.buildRecognizerIntent(language = "ru-RU")

        assertEquals(
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL),
        )
        assertTrue(intent.getBooleanExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false))
        assertEquals("ru-RU", intent.getStringExtra(RecognizerIntent.EXTRA_LANGUAGE))
    }

    // ---- Cause 2: pause must not terminate dictation ------------------------

    @Test
    fun pause_restartsAndAccumulatesAcrossTurns() {
        val provider = AndroidSpeechRecognitionProvider(context)
        val listener = RecordingListener()

        val session = provider.start(language = "en-US", listener = listener)
        assertTrue(session != null)

        // Turn 1: user speaks, then pauses. The recognizer finalizes the turn.
        val r1 = latestRecognizer()
        shadowOf(r1).triggerOnResults(resultsBundle("hello world"))

        // The base provider would have called onFinal here and ended dictation.
        // With the fix, dictation is still going (no final / no error yet) and a
        // new recognizer turn was started.
        assertNull("a mid-dictation pause must not end dictation", listener.finalText)
        assertNull(listener.error)

        // Turn 2: the recognizer restarted; speak the rest, then the user stops.
        val r2 = latestRecognizer()
        assertTrue("a new recognizer turn must have started after the pause", r2 !== r1)
        shadowOf(r2).triggerOnPartialResults(resultsBundle("this is me"))

        session!!.stopListening()
        // The stop asks the current turn to wrap up; its results commit.
        shadowOf(latestRecognizer())
            .triggerOnResults(resultsBundle("this is me dictating"))

        // The committed transcript spans BOTH turns — the pause didn't drop the
        // first phrase.
        assertEquals("hello world this is me dictating", listener.finalText)
        assertNull(listener.error)
    }

    @Test
    fun noMatchMidDictation_restartsInsteadOfTerminating() {
        val provider = AndroidSpeechRecognitionProvider(context)
        val listener = RecordingListener()

        val session = provider.start(language = "en-US", listener = listener)
        val r1 = latestRecognizer()

        // First phrase commits, then the recognizer reports a no-match on the
        // pause (the exact "stops too quickly" symptom).
        shadowOf(r1).triggerOnPartialResults(resultsBundle("first phrase"))
        shadowOf(r1).triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)

        // Base code surfaced ANDROID_SPEECH_NO_TEXT_MESSAGE and ended; the fix
        // restarts and keeps the committed phrase.
        assertNull(listener.error)
        assertNull(listener.finalText)

        val r2 = latestRecognizer()
        assertTrue(r2 !== r1)

        session!!.stopListening()
        shadowOf(latestRecognizer())
            .triggerOnResults(resultsBundle("second phrase"))

        assertEquals("first phrase second phrase", listener.finalText)
    }

    @Test
    fun speechTimeoutMidDictation_restartsInsteadOfTerminating() {
        val provider = AndroidSpeechRecognitionProvider(context)
        val listener = RecordingListener()

        val session = provider.start(language = null, listener = listener)
        val r1 = latestRecognizer()

        shadowOf(r1).triggerOnPartialResults(resultsBundle("keep going"))
        shadowOf(r1).triggerOnError(SpeechRecognizer.ERROR_SPEECH_TIMEOUT)

        assertNull(listener.error)
        assertNull(listener.finalText)
        assertTrue(latestRecognizer() !== r1)

        session!!.cancel()
    }

    // ---- Terminal paths still behave -----------------------------------------

    @Test
    fun hardError_endsDictationWithMessage() {
        val provider = AndroidSpeechRecognitionProvider(context)
        val listener = RecordingListener()

        provider.start(language = "en-US", listener = listener)
        val r1 = latestRecognizer()
        shadowOf(r1).triggerOnError(SpeechRecognizer.ERROR_NETWORK)

        assertTrue(listener.error?.contains("network", ignoreCase = true) == true)
        assertNull(listener.finalText)
    }

    @Test
    fun explicitStop_withNoSpeech_surfacesNoTextMessage() {
        val provider = AndroidSpeechRecognitionProvider(context)
        val listener = RecordingListener()

        val session = provider.start(language = "en-US", listener = listener)
        latestRecognizer()
        session!!.stopListening()
        shadowOf(latestRecognizer())
            .triggerOnError(SpeechRecognizer.ERROR_NO_MATCH)

        assertEquals(
            PromptComposerViewModel.ANDROID_SPEECH_NO_TEXT_MESSAGE,
            listener.error,
        )
        assertNull(listener.finalText)
    }

    @Test
    fun singleTurnDictation_commitsOnExplicitStop() {
        val provider = AndroidSpeechRecognitionProvider(context)
        val listener = RecordingListener()

        val session = provider.start(language = "en-US", listener = listener)
        val r1 = latestRecognizer()
        shadowOf(r1).triggerOnPartialResults(resultsBundle("just one phrase"))

        session!!.stopListening()
        shadowOf(latestRecognizer())
            .triggerOnResults(resultsBundle("just one phrase"))

        assertEquals("just one phrase", listener.finalText)
        assertNull(listener.error)
    }
}
