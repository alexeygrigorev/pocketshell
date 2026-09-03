package com.pocketshell.next.composer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith

/**
 * [ComposerViewModel] over the real composer stack (rewrite task P-1).
 *
 * The send contract is the point of this suite, and it is asserted from both
 * ends: what the [SessionSink] received, and what the draft looks like
 * afterwards. A test that only checked the ViewModel's own state could pass
 * with nothing on the wire, and one that only checked the wire could pass while
 * silently eating the user's text.
 *
 * `J07ComposerSendJourney` proves the same path against a real host on a real
 * device; what lives here is everything a device journey cannot cheaply
 * enumerate — the not-live branch, the history round trip, attachment staging
 * failures, and dictation.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class ComposerViewModelTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    private val dispatcher = StandardTestDispatcher()
    private lateinit var stack: TestComposerStack

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        stack = TestComposerStack()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        stack.close()
    }

    // ------------------------------------------------------------ the contract

    @Test
    fun `a send on a live session puts the text plus a carriage return on the wire`() =
        runTest(dispatcher) {
            val viewModel = bound()
            viewModel.onDraftChange("run the tests")
            advanceUntilIdle()

            viewModel.send()
            advanceUntilIdle()

            // `\r`, not `\n`: this is a PTY, and carriage return is what a line
            // discipline turns into "the user pressed Enter".
            assertEquals(listOf("run the tests\r"), sink.sentText())
        }

    @Test
    fun `a delivered send clears the draft and the persisted copy`() = runTest(dispatcher) {
        val viewModel = bound()
        viewModel.onDraftChange("ship it")
        advanceUntilIdle()

        viewModel.send()
        advanceUntilIdle()

        assertEquals("", viewModel.state.value.draft)
        assertNull("a delivered send must not leave a chip", viewModel.state.value.notice)
        assertEquals("", stack.drafts.load(SESSION_KEY).text)
    }

    /**
     * The whole delivery story: nothing left, the text is still here, and the
     * user is told. No retry, no queue, no "will send when reconnected".
     */
    @Test
    fun `a send with the session offline keeps the draft and shows the chip`() =
        runTest(dispatcher) {
            val viewModel = bound()
            viewModel.onDraftChange("this should survive")
            advanceUntilIdle()
            sink.isLive = false

            viewModel.send()
            advanceUntilIdle()

            assertTrue("nothing may reach a dead session", sink.sent.isEmpty())
            assertEquals("this should survive", viewModel.state.value.draft)
            assertEquals(ComposerNotice.Undelivered, viewModel.state.value.notice)
            assertTrue(viewModel.state.value.undelivered)
        }

    /**
     * Liveness is asked at the tap, never cached. A composer holding its own
     * snapshot of session state is the divergence the rewrite exists to delete.
     */
    @Test
    fun `liveness is re-read for every send`() = runTest(dispatcher) {
        val viewModel = bound()
        viewModel.onDraftChange("first")
        advanceUntilIdle()
        viewModel.send()
        advanceUntilIdle()

        sink.isLive = false
        viewModel.onDraftChange("second")
        advanceUntilIdle()
        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf("first\r"), sink.sentText())
        assertEquals("second", viewModel.state.value.draft)
    }

    @Test
    fun `an empty draft sends nothing`() = runTest(dispatcher) {
        val viewModel = bound()

        viewModel.onDraftChange("   ")
        advanceUntilIdle()
        viewModel.send()
        advanceUntilIdle()

        assertTrue(sink.sent.isEmpty())
        assertFalse(viewModel.state.value.canSend)
    }

    @Test
    fun `typing dismisses the undelivered chip`() = runTest(dispatcher) {
        val viewModel = bound()
        sink.isLive = false
        viewModel.onDraftChange("nope")
        advanceUntilIdle()
        viewModel.send()
        advanceUntilIdle()

        viewModel.onDraftChange("nope!")
        advanceUntilIdle()

        assertNull(viewModel.state.value.notice)
    }

    @Test
    fun `a draft survives a rebind to the same session`() = runTest(dispatcher) {
        val first = bound()
        first.onDraftChange("half-written thought")
        advanceUntilIdle()

        val second = stack.viewModel()
        second.bind(hostId, SESSION, sink)
        advanceUntilIdle()

        assertEquals("half-written thought", second.state.value.draft)
    }

    // -------------------------------------------------------------- history

    @Test
    fun `a delivered send is recorded in the history`() = runTest(dispatcher) {
        val viewModel = bound()
        viewModel.onDraftChange("remember me")
        advanceUntilIdle()

        viewModel.send()
        advanceUntilIdle()

        val history = viewModel.state.value.history
        assertEquals(1, history.size)
        assertEquals("remember me", history.single().body)
        assertTrue(history.single().delivered)
    }

    /**
     * The reason the log exists: a send that did not leave must still be
     * recoverable without retyping it.
     */
    @Test
    fun `an undelivered send is recorded too, flagged as not delivered`() = runTest(dispatcher) {
        val viewModel = bound()
        sink.isLive = false
        viewModel.onDraftChange("never left")
        advanceUntilIdle()

        viewModel.send()
        advanceUntilIdle()

        val entry = viewModel.state.value.history.single()
        assertEquals("never left", entry.body)
        assertFalse(entry.delivered)
    }

    @Test
    fun `tapping a history entry refills the draft and closes the list`() = runTest(dispatcher) {
        val viewModel = bound()
        viewModel.onDraftChange("the long prompt I do not want to retype")
        advanceUntilIdle()
        viewModel.send()
        advanceUntilIdle()
        viewModel.toggleHistory()
        val entry = viewModel.state.value.history.single()

        viewModel.useHistoryEntry(entry)
        advanceUntilIdle()

        assertEquals("the long prompt I do not want to retype", viewModel.state.value.draft)
        assertFalse("picking an entry closes the list", viewModel.state.value.historyOpen)
        // The refilled draft is durable too: the point is not retyping it.
        assertEquals(
            "the long prompt I do not want to retype",
            stack.drafts.load(SESSION_KEY).text,
        )
    }

    /** Re-sending a recalled message is an ordinary send, not a queue replay. */
    @Test
    fun `a recalled message can be sent again`() = runTest(dispatcher) {
        val viewModel = bound()
        sink.isLive = false
        viewModel.onDraftChange("try again later")
        advanceUntilIdle()
        viewModel.send()
        advanceUntilIdle()
        viewModel.discard()
        advanceUntilIdle()

        sink.isLive = true
        viewModel.useHistoryEntry(viewModel.state.value.history.first())
        advanceUntilIdle()
        viewModel.send()
        advanceUntilIdle()

        assertEquals(listOf("try again later\r"), sink.sentText())
        assertEquals(2, viewModel.state.value.history.size)
    }

    @Test
    fun `history is scoped to its own session`() = runTest(dispatcher) {
        val viewModel = bound()
        viewModel.onDraftChange("session one")
        advanceUntilIdle()
        viewModel.send()
        advanceUntilIdle()

        val other = stack.viewModel()
        other.bind(hostId, "other-session", sink)
        advanceUntilIdle()

        assertTrue(other.state.value.history.isEmpty())
    }

    // ----------------------------------------------------------- attachments

    @Test
    fun `an attachment uploads over SFTP and its remote path lands in the message`() =
        runTest(dispatcher) {
            val viewModel = bound()
            val pick = pick("diagram.png", "PNGBYTES")

            viewModel.attach(listOf(pick))
            advanceUntilIdle()

            // Uploaded where the message says it is, with the account's real
            // home directory resolved from the host — the `~/` display path and
            // the absolute path must name the same file.
            val staged = viewModel.state.value.attachments.single()
            assertEquals("~/.pocketshell/attachments/$SCOPE/${staged.displayName}", staged.remotePath)
            assertEquals(
                "PNGBYTES",
                stack.sftp.textAt("/home/testuser/.pocketshell/attachments/$SCOPE/${staged.displayName}"),
            )
            assertTrue(
                "the generated name must be <timestamp>-<ordinal>-<file>, got ${staged.displayName}",
                Regex("""\d{8}-\d{6}-01-diagram\.png""").matches(staged.displayName),
            )

            viewModel.onDraftChange("look at this")
            advanceUntilIdle()
            viewModel.send()
            advanceUntilIdle()

            assertEquals(
                listOf("look at this\n\nAttached files:\n- ${staged.remotePath}\r"),
                sink.sentText(),
            )
        }

    @Test
    fun `an attachment with no text is a complete message`() = runTest(dispatcher) {
        val viewModel = bound()
        viewModel.attach(listOf(pick("log.txt", "boom")))
        advanceUntilIdle()

        assertTrue(viewModel.state.value.canSend)
        viewModel.send()
        advanceUntilIdle()

        val path = "~/.pocketshell/attachments/$SCOPE/"
        assertTrue(sink.sentText().single().startsWith("Attached files:\n- $path"))
    }

    @Test
    fun `staged attachments survive a rebind`() = runTest(dispatcher) {
        val viewModel = bound()
        viewModel.attach(listOf(pick("notes.md", "hello")))
        advanceUntilIdle()
        val staged = viewModel.state.value.attachments.single()

        val second = stack.viewModel()
        second.bind(hostId, SESSION, sink)
        advanceUntilIdle()

        assertEquals(listOf(staged), second.state.value.attachments)
    }

    @Test
    fun `removing a tile drops it from the next message`() = runTest(dispatcher) {
        val viewModel = bound()
        viewModel.attach(listOf(pick("a.txt", "a"), pick("b.txt", "b")))
        advanceUntilIdle()
        assertEquals(2, viewModel.state.value.attachments.size)

        viewModel.removeAttachment(viewModel.state.value.attachments.first().remotePath)
        advanceUntilIdle()
        viewModel.send()
        advanceUntilIdle()

        val body = sink.sentText().single()
        assertTrue(body.contains("-b.txt"))
        assertFalse(body.contains("-a.txt"))
    }

    /**
     * A failed pick must not discard the ones that worked — the shape that lost
     * a maintainer's files in the old client.
     */
    @Test
    fun `a partly failed batch keeps the survivors and says what failed`() = runTest(dispatcher) {
        val viewModel = bound()
        val good = pick("kept.txt", "kept")
        val missing = Uri.fromFile(File(temporaryFolder.root, "not-there.txt"))

        viewModel.attach(listOf(good, missing))
        advanceUntilIdle()

        assertEquals(1, viewModel.state.value.attachments.size)
        assertTrue(viewModel.state.value.attachments.single().displayName.endsWith("kept.txt"))
        val notice = viewModel.state.value.notice
        assertTrue("expected a problem notice, got $notice", notice is ComposerNotice.Problem)
        assertTrue((notice as ComposerNotice.Problem).message.contains("1 of 2"))
    }

    /**
     * A message referencing a file that is still on its way to the host would
     * arrive before the file does, so the send waits.
     *
     * The upload is genuinely mid-flight here, not simulated: the stager reads
     * the picked bytes through the TEST scheduler, so it parks at the first
     * file until the clock is advanced — which is exactly the window a user's
     * Send tap lands in.
     */
    @Test
    fun `a send is refused while an upload is in flight`() = runTest(dispatcher) {
        val viewModel = bound()
        viewModel.onDraftChange("wait for the file")
        advanceUntilIdle()

        // The upload is parked at its first byte read, which is exactly the
        // window a Send tap lands in.
        stack.uploadGate.hold()
        viewModel.attach(listOf(pick("big.bin", "payload")))
        runCurrent()
        assertTrue("expected the upload to be in flight", viewModel.state.value.busy)

        viewModel.send()
        runCurrent()
        assertTrue("a send during an upload must not reach the wire", sink.sent.isEmpty())

        stack.uploadGate.release()
        advanceUntilIdle()
        assertFalse(viewModel.state.value.busy)
        viewModel.send()
        advanceUntilIdle()
        assertTrue(sink.sentText().single().contains("Attached files:"))
    }

    // ------------------------------------------------------------- dictation

    @Test
    fun `dictation appends the transcript to whatever was already typed`() = runTest(dispatcher) {
        val viewModel = bound()
        viewModel.onDraftChange("please")
        advanceUntilIdle()

        viewModel.onMicTap()
        assertEquals(RecordingState.Recording, viewModel.state.value.recording)
        // A recognizer emits REPLACEMENTS, not increments.
        stack.speech.partial("run the")
        stack.speech.partial("run the tests")
        stack.speech.final("run the tests now")
        advanceUntilIdle()

        assertEquals("please run the tests now", viewModel.state.value.draft)
        assertEquals(RecordingState.Idle, viewModel.state.value.recording)
    }

    @Test
    fun `discarding a recording restores the pre-dictation draft`() = runTest(dispatcher) {
        val viewModel = bound()
        viewModel.onDraftChange("keep this")
        advanceUntilIdle()
        viewModel.onMicTap()
        stack.speech.partial("and throw this away")

        viewModel.cancelRecording()
        advanceUntilIdle()

        assertEquals("keep this", viewModel.state.value.draft)
        assertEquals(RecordingState.Idle, viewModel.state.value.recording)
    }

    @Test
    fun `the mic reports unavailable rather than pretending to listen`() = runTest(dispatcher) {
        stack.speech.available = false
        val viewModel = bound()

        viewModel.onMicTap()
        advanceUntilIdle()

        assertFalse(viewModel.state.value.micAvailable)
        assertEquals(RecordingState.Idle, viewModel.state.value.recording)
        assertTrue(viewModel.state.value.notice is ComposerNotice.Problem)
    }

    // ------------------------------------------------ offline-queued dictation

    /**
     * The subway case (task P-2): a dictation recorded with no signal is
     * parked as audio, not lost. Coming back to the foreground is what
     * delivers it — appended to the draft exactly as a live dictation would
     * have been, and persisted so it survives a process death too.
     */
    @Test
    fun `a dictation queued while offline lands in the draft on the next foreground resume`() =
        runTest(dispatcher) {
            val viewModel = bound()
            viewModel.onDraftChange("please")
            advanceUntilIdle()

            stack.pendingTranscriptions.idGenerator = { "offline-take" }
            stack.pendingTranscriptions.enqueueAudio(
                audio = ByteArray(32) { it.toByte() },
                destinationContext = com.pocketshell.core.storage.entity.PendingTranscriptionEntity.DESTINATION_COMPOSER,
                initialError = com.pocketshell.next.voice.PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
            )
            stack.whisperClientFactory.client = FakeWhisperClient(Result.success("run the tests now"))
            stack.connectivity.online = true

            viewModel.onForegroundResume()
            awaitDraftChange(viewModel, from = "please")

            assertEquals("please run the tests now", viewModel.state.value.draft)
            assertTrue(viewModel.state.value.notice is ComposerNotice.Info)
            assertEquals("please run the tests now", stack.drafts.load(SESSION_KEY).text)
        }

    /** Nothing queued: a foreground resume is a no-op, not a spurious notice. */
    @Test
    fun `a foreground resume with nothing queued leaves the draft alone`() = runTest(dispatcher) {
        val viewModel = bound()
        viewModel.onDraftChange("untouched")
        advanceUntilIdle()

        viewModel.onForegroundResume()
        // Real (non-virtual) wait: `deliverQueued()` hops through the real
        // `Dispatchers.IO` inside `PendingTranscriptionStore`, so there is
        // nothing to advance on the virtual clock — just settle it.
        repeat(20) { advanceUntilIdle(); Thread.sleep(5) }

        assertEquals("untouched", viewModel.state.value.draft)
        assertNull(viewModel.state.value.notice)
    }

    /**
     * Waits for [ComposerViewModel.onForegroundResume]'s launch to land.
     *
     * `advanceUntilIdle()` alone is not enough here: `deliverQueued()`
     * genuinely hops onto the real `Dispatchers.IO` inside
     * [com.pocketshell.next.voice.PendingTranscriptionStore] (a pure P-2
     * lift, unaltered), so the virtual test clock has nothing to fast-forward
     * through while that real background work is in flight. A short bounded
     * real-time poll is what actually waits for it.
     */
    private fun TestScope.awaitDraftChange(viewModel: ComposerViewModel, from: String, timeoutMs: Long = 3_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (viewModel.state.value.draft == from && System.currentTimeMillis() < deadline) {
            advanceUntilIdle()
            Thread.sleep(5)
        }
        advanceUntilIdle()
    }

    // --------------------------------------------------------------- helpers

    private var hostId: Long = 0
    private val sink = RecordingSessionSink()

    private fun bound(): ComposerViewModel {
        hostId = stack.seedHost()
        val viewModel = stack.viewModel()
        viewModel.bind(hostId, SESSION, sink)
        return viewModel
    }

    /** A device document the composer can actually read, as a `file://` content URI. */
    private fun pick(name: String, contents: String): Uri {
        val file = File(temporaryFolder.root, name)
        file.writeText(contents)
        return Uri.fromFile(file)
    }

    private val SESSION_KEY: String get() = "$hostId/$SESSION"

    private val SCOPE: String get() = "$hostId-$SESSION"

    private companion object {
        const val SESSION = "devbox"
    }
}
