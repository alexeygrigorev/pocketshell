package com.pocketshell.next.composer

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.pocketshell.next.settings.AppSettings
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
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
            // discipline turns into "the user pressed Enter". Two writes, not
            // one concatenated buffer — agents treat an immediate Enter as a
            // newline (#2526).
            assertEquals(listOf("run the tests", "\r"), sink.sentText())
        }

    /**
     * Issue #2526: agents (Claude/Codex/Grok) treat a body+CR concatenated
     * into one PTY write as a newline and swallow the submit. The old client
     * (#526) wrote the text, waited, then sent Enter. This is that contract
     * on a virtual clock: after send, the body is on the wire WITHOUT CR;
     * advancing less than the delay does not send CR; advancing the delay
     * sends exactly `\r`.
     *
     * Written first against the unfixed concatenated write so the RED is
     * the reported defect, not a proxy.
     */
    @Test
    fun `send writes the body, waits the delay, then writes CR as a second PTY write`() =
        runTest(dispatcher) {
            val viewModel = bound()
            viewModel.onDraftChange("hello")
            advanceUntilIdle()

            viewModel.send()
            runCurrent()

            assertEquals(
                "body must leave without a concatenated CR",
                listOf("hello"),
                sink.sentText(),
            )
            assertEquals(
                "the first write must not be the concatenated body+CR buffer",
                "hello".toByteArray().toList(),
                sink.sent.single().toList(),
            )

            val delayMs = AppSettings.DEFAULT_AGENT_SUBMIT_ENTER_DELAY_MS.toLong()
            advanceTimeBy(delayMs - 1)
            runCurrent()
            assertEquals(
                "CR must not leave before the delay elapses",
                listOf("hello"),
                sink.sentText(),
            )

            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("hello", "\r"), sink.sentText())
            assertEquals(byteArrayOf(0x0D).toList(), sink.sent.last().toList())
        }

    /**
     * Issue #2526: a 0 ms delay is still two writes (body, then CR), never
     * one combined `body + "\r"` buffer — concatenating is the race even
     * when the wait is zero.
     */
    @Test
    fun `zero delay still writes body and CR as two buffers, never one concatenated write`() =
        runTest(dispatcher) {
            stack.settings.setAgentSubmitEnterDelayMs(0)
            val viewModel = bound()
            viewModel.onDraftChange("hello")
            advanceUntilIdle()

            viewModel.send()
            advanceUntilIdle()

            assertEquals(2, sink.sent.size)
            assertEquals("hello", sink.sent[0].toString(Charsets.UTF_8))
            assertEquals(byteArrayOf(0x0D).toList(), sink.sent[1].toList())
            assertTrue(
                "zero delay must still be two writes, not one concatenated body+CR buffer",
                sink.sent.none { it.toList() == "hello\r".toByteArray().toList() },
            )
        }

    /**
     * Same lesson as #2488: the delay is read per send off the live
     * settings snapshot, not captured when the ViewModel (or the Hilt
     * graph) was constructed. Changing the slider has to change the NEXT
     * Send without a process restart.
     */
    @Test
    fun `changing the agent submit delay is honoured on the next send`() =
        runTest(dispatcher) {
            val viewModel = bound()
            stack.settings.setAgentSubmitEnterDelayMs(200)
            viewModel.onDraftChange("first")
            advanceUntilIdle()

            viewModel.send()
            runCurrent()
            assertEquals(listOf("first"), sink.sentText())
            advanceTimeBy(199)
            runCurrent()
            assertEquals(listOf("first"), sink.sentText())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("first", "\r"), sink.sentText())

            stack.settings.setAgentSubmitEnterDelayMs(50)
            viewModel.onDraftChange("second")
            advanceUntilIdle()
            viewModel.send()
            runCurrent()
            assertEquals(listOf("first", "\r", "second"), sink.sentText())
            advanceTimeBy(49)
            runCurrent()
            assertEquals(listOf("first", "\r", "second"), sink.sentText())
            advanceTimeBy(1)
            runCurrent()
            assertEquals(listOf("first", "\r", "second", "\r"), sink.sentText())
        }

    @Test
    fun `insert on a live session puts the text on the wire without a carriage return`() =
        runTest(dispatcher) {
            val viewModel = bound()
            viewModel.onDraftChange("run the tests")
            advanceUntilIdle()

            viewModel.insert()
            advanceUntilIdle()

            assertEquals(listOf("run the tests"), sink.sentText())
            assertEquals("", viewModel.state.value.draft)
        }

    @Test
    fun `insert with the session offline keeps the draft and shows the chip`() =
        runTest(dispatcher) {
            val viewModel = bound()
            viewModel.onDraftChange("this should survive")
            advanceUntilIdle()
            sink.isLive = false

            viewModel.insert()
            advanceUntilIdle()

            assertTrue("nothing may reach a dead session", sink.sent.isEmpty())
            assertEquals("this should survive", viewModel.state.value.draft)
            assertEquals(ComposerNotice.Undelivered, viewModel.state.value.notice)
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

        assertEquals(listOf("first", "\r"), sink.sentText())
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

    // ------------------------------------------------- rebinding across sessions

    /*
     * Nothing in the app rebinds ONE ComposerViewModel across two sessions
     * today: `SessionRoute` resolves it with `hiltViewModel()` inside the
     * `session/{hostId}/{sessionName}` destination, so the ViewModel's store
     * owner is that route's `NavBackStackEntry` and opening another session
     * builds a new entry — and a new composer. These tests construct the
     * rebind synthetically (call `bind` twice on one instance, which is exactly
     * what a quick-switch sheet reusing one entry would do) and pin the
     * behaviour the composer must have on its own, without leaning on the
     * navigation graph to prevent the situation.
     */

    @Test
    fun `a rebind to another session never shows the previous session's draft`() =
        runTest(dispatcher) {
            val viewModel = bound()
            viewModel.onDraftChange("the thing I typed in session A")
            advanceUntilIdle()

            viewModel.bind(hostId, OTHER_SESSION, sink)

            // Synchronously, in the same frame as the bind: the load for the
            // new session is asynchronous, and a draft that is merely "about to
            // be replaced" is one the user can read and send.
            assertEquals("", viewModel.state.value.draft)
            assertTrue(viewModel.state.value.attachments.isEmpty())

            // And it does not come back when the in-flight work settles.
            advanceUntilIdle()
            assertEquals("", viewModel.state.value.draft)
            assertTrue(viewModel.state.value.attachments.isEmpty())
        }

    @Test
    fun `a rebind to another session clears the attachments, the chip and the history`() =
        runTest(dispatcher) {
            val viewModel = bound()
            viewModel.attach(listOf(pick("a-only.txt", "a")))
            advanceUntilIdle()
            sink.isLive = false
            viewModel.onDraftChange("undelivered in A")
            advanceUntilIdle()
            viewModel.send()
            advanceUntilIdle()
            viewModel.toggleHistory()
            assertEquals(1, viewModel.state.value.attachments.size)
            assertEquals(1, viewModel.state.value.history.size)

            viewModel.bind(hostId, OTHER_SESSION, sink)

            assertTrue(viewModel.state.value.attachments.isEmpty())
            assertNull(viewModel.state.value.notice)
            assertTrue("session A's sent messages are not session B's", viewModel.state.value.history.isEmpty())
            assertFalse(viewModel.state.value.historyOpen)

            advanceUntilIdle()
            assertTrue(viewModel.state.value.attachments.isEmpty())
            assertTrue(viewModel.state.value.history.isEmpty())
        }

    /**
     * The debounced write is the other direction of the same leak: [writeDraft]
     * reads `sessionKey` when it RUNS, so a keystroke still inside the debounce
     * window at the moment of the switch would be persisted under the NEW
     * session's key — and would clobber whatever that session had stored.
     */
    @Test
    fun `a rebind persists the outgoing draft under its own key, not the new one`() =
        runTest(dispatcher) {
            val viewModel = bound()
            viewModel.onDraftChange("half a thought")
            // Deliberately not `advanceUntilIdle()`: the write is still inside
            // its debounce, which is the window a session switch lands in.
            runCurrent()

            viewModel.bind(hostId, OTHER_SESSION, sink)
            advanceUntilIdle()

            assertEquals("half a thought", stack.drafts.load(SESSION_KEY).text)
            assertEquals(
                "session A's text must never be stored as session B's draft",
                "",
                stack.drafts.load("$hostId/$OTHER_SESSION").text,
            )
        }

    /** Clearing the screen is not throwing the draft away: A → B → A finds it. */
    @Test
    fun `the draft left behind by a rebind is still there when the session is reopened`() =
        runTest(dispatcher) {
            val viewModel = bound()
            viewModel.onDraftChange("half a thought")
            runCurrent()
            viewModel.bind(hostId, OTHER_SESSION, sink)
            advanceUntilIdle()

            val reopened = stack.viewModel()
            reopened.bind(hostId, SESSION, sink)
            advanceUntilIdle()

            assertEquals("half a thought", reopened.state.value.draft)
        }

    /**
     * The stored-draft load is asynchronous, so a switch can outrun it. If the
     * load for the session being left resolves after the switch, it finds an
     * empty composer and would paint the old session's draft into the new one.
     */
    @Test
    fun `a draft load in flight for the previous session cannot resolve into the new one`() =
        runTest(dispatcher) {
            hostId = stack.seedHost()
            stack.drafts.save(SESSION_KEY, ComposerDraft("stored for session A"))
            val viewModel = stack.viewModel()

            viewModel.bind(hostId, SESSION, sink)
            // No advance: session A's load is queued and has not resolved.
            viewModel.bind(hostId, OTHER_SESSION, sink)
            advanceUntilIdle()

            assertEquals("", viewModel.state.value.draft)
        }

    /**
     * The other half of the same window, and the one that costs the user
     * something: the hand-off must not WRITE either.
     *
     * `_state` is only the outgoing session's draft once its load has landed.
     * Switch away before that and the composer is empty because nothing has
     * been read yet — not because the session has no draft — so persisting that
     * emptiness takes the `clear` branch and deletes a real, unsent draft from
     * the mirror and the preferences file alike. Nothing can recover it.
     */
    @Test
    fun `a rebind before the outgoing draft has loaded leaves it stored`() = runTest(dispatcher) {
        hostId = stack.seedHost()
        stack.drafts.save(SESSION_KEY, ComposerDraft("important unsent text"))
        val viewModel = stack.viewModel()

        viewModel.bind(hostId, SESSION, sink)
        // No advance: session A's load is queued and has NOT resolved, so the
        // empty composer says nothing about what A has stored.
        viewModel.bind(hostId, OTHER_SESSION, sink)
        advanceUntilIdle()

        assertEquals(
            "a switch that outran the load must not delete the draft it never read",
            "important unsent text",
            stack.drafts.load(SESSION_KEY).text,
        )
    }

    /** And the user gets it back: A → B → A, with the switch inside A's load. */
    @Test
    fun `a session switched away from mid-load still shows its draft on the way back`() =
        runTest(dispatcher) {
            hostId = stack.seedHost()
            stack.drafts.save(SESSION_KEY, ComposerDraft("important unsent text"))
            val viewModel = stack.viewModel()

            viewModel.bind(hostId, SESSION, sink)
            viewModel.bind(hostId, OTHER_SESSION, sink)
            advanceUntilIdle()
            assertEquals("session B starts empty", "", viewModel.state.value.draft)

            viewModel.bind(hostId, SESSION, sink)
            advanceUntilIdle()

            assertEquals("important unsent text", viewModel.state.value.draft)
        }

    /**
     * The same rule on the ordinary persistence path, not just the hand-off:
     * [ComposerViewModel.writeDraft] runs on a debounce and from every "must
     * not wait" moment, so it too can fire while the stored draft is still on
     * its way back from disk.
     *
     * Reached here the way a user would: open a session and immediately attach
     * a file that fails to upload. The failure persists an empty composer —
     * empty only because the load has not landed — which is a `clear` of a
     * draft the user never saw and never touched.
     *
     * The load is genuinely parked (a gated dispatcher inside a store with a
     * cold mirror), not merely queued behind the test scheduler, because the
     * production window is a real disk hop that other work overtakes.
     */
    @Test
    fun `a failed upload before the draft has loaded cannot delete it`() = runTest(dispatcher) {
        hostId = stack.seedHost()
        // Seeded through another store instance so the one under test has to
        // read the preferences file rather than answer from its own mirror.
        stack.drafts.save(SESSION_KEY, ComposerDraft("important unsent text"))
        val gate = GatedDispatcher().apply { hold() }
        val slowDrafts = ComposerDraftStore(stack.context, gate)
        val viewModel = ComposerViewModel(
            registry = stack.registry,
            drafts = slowDrafts,
            history = stack.db.sentMessageDao(),
            stager = stack.stager,
            queuedDictations = stack.queuedDictations,
            speech = stack.speech,
            settings = stack.settings,
        )

        viewModel.bind(hostId, SESSION, sink)
        viewModel.attach(listOf(Uri.fromFile(File(temporaryFolder.root, "not-there.txt"))))
        advanceUntilIdle()
        assertTrue(
            "the upload must really have failed for this to be the right window",
            viewModel.state.value.notice is ComposerNotice.Problem,
        )
        assertTrue(viewModel.state.value.attachments.isEmpty())

        gate.release()
        advanceUntilIdle()

        assertEquals(
            "the stored draft must survive a persist that ran before it was read",
            "important unsent text",
            ComposerDraftStore(stack.context, Dispatchers.Unconfined).load(SESSION_KEY).text,
        )
        assertEquals(
            "and it lands in the composer once the load finally resolves",
            "important unsent text",
            viewModel.state.value.draft,
        )
    }

    /** A recording belongs to the session it was started in, transcript included. */
    @Test
    fun `a dictation in flight cannot type into the session it was not started in`() =
        runTest(dispatcher) {
            val viewModel = bound()
            viewModel.onMicTap()
            stack.speech.partial("words meant for session A")
            advanceUntilIdle()
            assertEquals(RecordingState.Recording, viewModel.state.value.recording)

            viewModel.bind(hostId, OTHER_SESSION, sink)
            // A recognizer callback can land after the switch; the service call
            // is asynchronous and nothing on the device stops mid-sentence.
            stack.speech.partial("words meant for session A, continued")
            advanceUntilIdle()

            assertEquals("", viewModel.state.value.draft)
            assertEquals(RecordingState.Idle, viewModel.state.value.recording)
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

        assertEquals(listOf("try again later", "\r"), sink.sentText())
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
                listOf("look at this\n\nAttached files:\n- ${staged.remotePath}", "\r"),
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
        assertTrue(sink.sentText().first().startsWith("Attached files:\n- $path"))
        assertEquals("\r", sink.sentText().last())
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

        val body = sink.sentText().first()
        assertTrue(body.contains("-b.txt"))
        assertFalse(body.contains("-a.txt"))
        assertEquals("\r", sink.sentText().last())
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
        assertTrue(sink.sentText().first().contains("Attached files:"))
        assertEquals("\r", sink.sentText().last())
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

    @Test
    fun `a denied record-audio permission surfaces a notice and does not start dictation`() =
        runTest(dispatcher) {
            val viewModel = bound()

            viewModel.surfacePermissionDenied()
            advanceUntilIdle()

            assertEquals(RecordingState.Idle, viewModel.state.value.recording)
            assertEquals(
                ComposerNotice.Problem(COMPOSER_RECORD_AUDIO_DENIED_TEXT),
                viewModel.state.value.notice,
            )
        }

    @Test
    fun `dictation passes the settings language hint to the recognizer`() = runTest(dispatcher) {
        stack.settings.setVoiceLanguage("ru")
        val viewModel = bound()

        viewModel.onMicTap()
        advanceUntilIdle()

        assertEquals("ru", stack.speech.lastLanguage)
    }

    @Test
    fun `auto language is passed as a null hint`() = runTest(dispatcher) {
        val viewModel = bound()

        viewModel.onMicTap()
        advanceUntilIdle()

        assertEquals(null, stack.speech.lastLanguage)
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

        /** A second session on the same host, for the rebind tests. */
        const val OTHER_SESSION = "laptop"
    }
}
