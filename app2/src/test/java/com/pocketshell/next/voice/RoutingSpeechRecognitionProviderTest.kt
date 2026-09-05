package com.pocketshell.next.voice

import com.pocketshell.next.composer.SpeechRecognitionListener
import com.pocketshell.next.composer.SpeechRecognitionProvider
import com.pocketshell.next.composer.SpeechRecognitionSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [RoutingSpeechRecognitionProvider] (rewrite task P-2).
 *
 * Plain JUnit — no Robolectric — since the class only touches the composer's
 * [SpeechRecognitionProvider] seam.
 */
class RoutingSpeechRecognitionProviderTest {

    private class FakeArm(var available: Boolean, val session: SpeechRecognitionSession? = null) :
        SpeechRecognitionProvider {
        var startCalls = 0
        override fun isAvailable(): Boolean = available
        override fun start(language: String?, listener: SpeechRecognitionListener): SpeechRecognitionSession? {
            startCalls++
            return if (available) session ?: FakeSession() else null
        }
    }

    private class FakeSession : SpeechRecognitionSession {
        override fun stopListening() {}
        override fun cancel() {}
    }

    /**
     * #2529 reproduce-first: a stored OpenAI key used to select Whisper
     * (`whisper.isAvailable()` was the routing question). Composer dictation
     * must stay on the Android `SpeechRecognizer` even when a key survives
     * from a v0.4.x install.
     */
    @Test
    fun `a stored openai key still starts the android arm`() {
        val whisper = FakeArm(available = true)
        val android = FakeArm(available = true)
        val routing = RoutingSpeechRecognitionProvider(whisper, android)

        assertTrue(routing.isAvailable())
        routing.start(language = null, listener = noopListener())

        assertEquals(0, whisper.startCalls)
        assertEquals(1, android.startCalls)
    }

    @Test
    fun `falls back to android when whisper has no key`() {
        val whisper = FakeArm(available = false)
        val android = FakeArm(available = true)
        val routing = RoutingSpeechRecognitionProvider(whisper, android)

        assertTrue(routing.isAvailable())
        routing.start(language = null, listener = noopListener())

        assertEquals(0, whisper.startCalls)
        assertEquals(1, android.startCalls)
    }

    @Test
    fun `unavailable when neither arm can run`() {
        val whisper = FakeArm(available = false)
        val android = FakeArm(available = false)
        val routing = RoutingSpeechRecognitionProvider(whisper, android)

        assertFalse(routing.isAvailable())
        assertEquals(null, routing.start(language = null, listener = noopListener()))
    }

    @Test
    fun `whisper-only is not enough — android availability is the mic`() {
        val whisper = FakeArm(available = true)
        val android = FakeArm(available = false)
        val routing = RoutingSpeechRecognitionProvider(whisper, android)

        assertFalse(routing.isAvailable())
        assertEquals(null, routing.start(language = null, listener = noopListener()))
        assertEquals(0, whisper.startCalls)
        assertEquals(1, android.startCalls)
    }

    @Test
    fun `storing a key mid-session does not flip the route off android`() {
        val whisper = FakeArm(available = false)
        val android = FakeArm(available = true)
        val routing = RoutingSpeechRecognitionProvider(whisper, android)

        routing.start(language = null, listener = noopListener())
        assertEquals(1, android.startCalls)

        whisper.available = true
        routing.start(language = null, listener = noopListener())
        assertEquals(0, whisper.startCalls)
        assertEquals(2, android.startCalls)
    }

    private fun noopListener(): SpeechRecognitionListener = object : SpeechRecognitionListener {
        override fun onPartial(text: String) {}
        override fun onFinal(text: String) {}
        override fun onError(message: String) {}
    }

    // Sanity: the session returned really is the arm's, not a routing wrapper.
    @Test
    fun `returns the android arm's own session even when whisper is available`() {
        val session = FakeSession()
        val whisper = FakeArm(available = true)
        val android = FakeArm(available = true, session = session)
        val routing = RoutingSpeechRecognitionProvider(whisper, android)

        val result = routing.start(language = null, listener = noopListener())
        assertSame(session, result)
    }
}
