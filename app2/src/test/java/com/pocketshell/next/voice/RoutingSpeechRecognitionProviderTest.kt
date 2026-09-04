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

    @Test
    fun `routes to whisper when whisper is available`() {
        val whisper = FakeArm(available = true)
        val android = FakeArm(available = true)
        val routing = RoutingSpeechRecognitionProvider(whisper, android)

        assertTrue(routing.isAvailable())
        routing.start(language = null, listener = noopListener())

        assertEquals(1, whisper.startCalls)
        assertEquals(0, android.startCalls)
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
    fun `re-evaluates the route on every tap`() {
        val whisper = FakeArm(available = false)
        val android = FakeArm(available = true)
        val routing = RoutingSpeechRecognitionProvider(whisper, android)

        routing.start(language = null, listener = noopListener())
        assertEquals(1, android.startCalls)

        // Storing an API key mid-session flips the route on the very next tap.
        whisper.available = true
        routing.start(language = null, listener = noopListener())
        assertEquals(1, whisper.startCalls)
        assertEquals(1, android.startCalls)
    }

    private fun noopListener(): SpeechRecognitionListener = object : SpeechRecognitionListener {
        override fun onPartial(text: String) {}
        override fun onFinal(text: String) {}
        override fun onError(message: String) {}
    }

    // Sanity: the session returned really is the arm's, not a routing wrapper.
    @Test
    fun `returns the selected arm's own session`() {
        val session = FakeSession()
        val whisper = FakeArm(available = true, session = session)
        val android = FakeArm(available = true)
        val routing = RoutingSpeechRecognitionProvider(whisper, android)

        val result = routing.start(language = null, listener = noopListener())
        assertSame(session, result)
    }
}
