package com.pocketshell.next.composer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [AndroidSpeechRecognitionDelegate]'s state machine, at the seam
 * [ComposerViewModel] cannot reach: what happens when the recognizer is
 * already gone but the composer still thinks it is listening.
 *
 * That gap is #2598. `deliver()` dropped the session with the silent
 * [AndroidSpeechRecognitionDelegate.release] meant for a composer that is
 * going away, and `cancel()` early-returned when there was no session left —
 * so nothing could move the composer out of [RecordingState.Recording] again.
 * The rule these tests pin: the delegate never leaves a LIVE composer in a
 * recording state it has no recognizer for.
 */
class AndroidSpeechRecognitionDelegateTest {

    private val provider = FakeSpeechRecognitionProvider()
    private val callbacks = RecordingCallbacks()
    private val delegate = AndroidSpeechRecognitionDelegate(provider, callbacks)

    @Test
    fun `a discard with no recognizer live still reports Idle`() {
        delegate.cancel()

        assertEquals(listOf(RecordingState.Idle), callbacks.states)
    }

    /**
     * The ghost case, in the shape the user hits it: the recording ended
     * behind the composer's back, then Discard (or the sheet's dismiss) is
     * tapped. It must normalise the state and leave the draft ALONE — there
     * is no pre-dictation text to restore any more, and restoring the stale
     * one would delete whatever has been typed since.
     */
    @Test
    fun `a discard after the recognizer was already dropped keeps the draft`() {
        callbacks.draft = "before the mic"
        delegate.start()
        provider.partial("dictated words")
        assertEquals("before the mic dictated words", callbacks.draft)

        delegate.releaseToIdle()
        callbacks.draft = "typed after the send"
        callbacks.states.clear()

        delegate.cancel()

        assertEquals(listOf(RecordingState.Idle), callbacks.states)
        assertEquals("typed after the send", callbacks.draft)
    }

    /** A live discard is unchanged: the pre-dictation draft comes back. */
    @Test
    fun `a discard during a live dictation restores the pre-dictation draft`() {
        callbacks.draft = "before the mic"
        delegate.start()
        provider.partial("dictated words")

        delegate.cancel()

        assertEquals("before the mic", callbacks.draft)
        assertEquals(listOf(RecordingState.Recording, RecordingState.Idle), callbacks.states)
        assertTrue(provider.cancelled)
    }

    /** The stop affordance's safety valve: nothing to stop still means Idle. */
    @Test
    fun `a stop with no recognizer live reports Idle`() {
        delegate.stop()

        assertEquals(listOf(RecordingState.Idle), callbacks.states)
    }

    /**
     * The Insert/Send path: the recognizer goes so no late partial can type
     * into a draft that is being cleared, the transcript already on screen
     * stays, and the composer — still very much alive — goes back to Idle.
     */
    @Test
    fun `releaseToIdle drops the recognizer, keeps the text and reports Idle`() {
        callbacks.draft = "before the mic"
        delegate.start()
        provider.partial("dictated words")
        callbacks.states.clear()

        delegate.releaseToIdle()

        assertEquals(listOf(RecordingState.Idle), callbacks.states)
        assertEquals("before the mic dictated words", callbacks.draft)
        assertTrue(provider.cancelled)
        // A late partial from the dropped session cannot type any more.
        provider.partial("too late")
        assertEquals("before the mic dictated words", callbacks.draft)
    }

    /**
     * [AndroidSpeechRecognitionDelegate.release] stays silent — its callers
     * (a session hand-off, `onCleared`) have no composer left to talk to.
     */
    @Test
    fun `release reports no state at all`() {
        delegate.start()
        callbacks.states.clear()

        delegate.release()

        assertEquals(emptyList<RecordingState>(), callbacks.states)
        assertTrue(provider.cancelled)
    }

    private class RecordingCallbacks : AndroidSpeechRecognitionDelegate.Callbacks {
        var draft: String = ""
        val states = mutableListOf<RecordingState>()
        val errors = mutableListOf<String>()

        override fun currentDraft(): String = draft

        override fun onDraft(text: String) {
            draft = text
        }

        override fun onState(state: RecordingState) {
            states += state
        }

        override fun onError(message: String) {
            errors += message
        }
    }
}
