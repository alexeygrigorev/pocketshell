package com.pocketshell.next.terminal

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.HostCliClient
import com.pocketshell.core.transport.FakeHostConnection
import com.pocketshell.core.transport.FakePtyChannel
import com.pocketshell.next.connect.TestConnectStack
import com.pocketshell.next.hostcli.HostCliClientFactory
import com.pocketshell.next.hostcli.asRemoteExec
import com.termux.terminal.TerminalSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows

/**
 * [SessionViewModel] over the REAL connect stack (task U-4): a real in-memory
 * Room database, the real [com.pocketshell.next.connect.ConnectionsRegistry],
 * the real [HostCliClient] that builds the attach command, and the real
 * [TerminalPtyBridge] driving a real vendored `TerminalSession` — with only the
 * sshj dial swapped for `core-transport`'s scripted [FakeHostConnection].
 *
 * The one thing NOT asserted here is what the user sees: the actual
 * `pocketshell sessions attach` round trip, the rendered viewport and real key
 * injection belong to `J03AttachAndTypeJourney` on a device against a real host
 * (the D29 lesson — a green ViewModel over a broken screen is the failure mode
 * this project has already paid for). What this suite pins is the lifecycle:
 * which command runs, at what size, what reaches the emulator, what leaves
 * through the channel, and every way the screen can end up saying "not
 * attached".
 *
 * Everything runs on one [StandardTestDispatcher] — both the main dispatcher
 * `viewModelScope` uses and the dispatcher injected into the ViewModel — so the
 * bridge's pumps live in virtual time. [settle] advances that clock rather than
 * calling `advanceUntilIdle()`, because the input pump polls on a `delay` loop
 * that by construction never leaves the scheduler idle.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class SessionViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val store = ViewModelStore()
    private val foreground = FakeForegroundSignal(initiallyForeground = true)
    private lateinit var stack: TestConnectStack

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        stack = TestConnectStack()
    }

    @After
    fun tearDown() {
        store.clear()
        Dispatchers.resetMain()
        stack.close()
    }

    @Test
    fun `attaching runs the host CLI attach command on a PTY and goes Live`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            livePty()
            val viewModel = viewModel()

            viewModel.open(hostId, SESSION)
            settle()

            val state = viewModel.uiState.value
            assertTrue("expected Live, got $state", state is SessionUiState.Live)

            // The command is the host CLI's own, verbatim — `exec` so the
            // wrapping shell is replaced, `--` so a session named like a flag
            // still resolves, and single-quoted because the name is user data.
            val request = connection().ptyRequests.single()
            assertEquals(
                "exec pocketshell sessions attach --hide-status -- '$SESSION'",
                request.command,
            )
            // Opened at the documented default until the view reports its real
            // geometry: no view has been laid out at `open()` time, so any other
            // number here would be an invented one.
            assertEquals(TerminalPtyBridge.DEFAULT_COLS, request.cols)
            assertEquals(TerminalPtyBridge.DEFAULT_ROWS, request.rows)
            assertEquals("xterm-256color", request.term)

            clear()
        }

    /**
     * Remote bytes reach the vendored emulator's screen buffer — the same
     * `getTranscriptText()` oracle J03 reads on a device, here proving the
     * ViewModel wires the bridge to a session that actually parses.
     */
    @Test
    fun `remote output lands in the terminal screen buffer`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        livePty()
        val viewModel = viewModel()

        viewModel.open(hostId, SESSION)
        settle()

        pty().emitText("testuser@fixture:~\$ echo ready\r\nready\r\n")
        settle()

        val transcript = transcriptOf(viewModel)
        assertTrue(
            "the emulator screen should carry the remote bytes, got: $transcript",
            transcript.contains("testuser@fixture:~\$ echo ready") && transcript.contains("ready"),
        )

        clear()
    }

    /**
     * A frame larger than one drain slice still lands whole — the chunk/message
     * accounting in the output pump has to post one `MSG_NEW_INPUT` per slice
     * because the vendored handler drains exactly one and never re-posts.
     */
    @Test
    fun `a multi-slice frame is parsed in full`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        livePty()
        val viewModel = viewModel()

        viewModel.open(hostId, SESSION)
        settle()

        // Three drain slices' worth of output, with a marker at the very end.
        val filler = "x".repeat(TerminalPtyBridge.DRAIN_SLICE_BYTES * 3)
        pty().emitText(filler + "\r\nTAIL-MARKER\r\n")
        settle()

        assertTrue(
            "the last bytes of a multi-slice frame must reach the screen",
            transcriptOf(viewModel).contains("TAIL-MARKER"),
        )

        clear()
    }

    /** Typed bytes leave through the PTY channel. */
    @Test
    fun `bytes written to the terminal session reach the remote channel`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            livePty()
            val viewModel = viewModel()

            viewModel.open(hostId, SESSION)
            settle()

            // This is exactly what the vendored TerminalView does per keystroke.
            (viewModel.uiState.value as SessionUiState.Live).terminal.write("echo hi\r")
            settle()

            assertEquals("echo hi\r", pty().writtenText)

            clear()
        }

    @Test
    fun `sendBytes forwards raw bytes to the remote channel`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        livePty()
        val viewModel = viewModel()

        viewModel.open(hostId, SESSION)
        settle()

        viewModel.sendBytes(byteArrayOf(0x03))
        settle()

        assertEquals(listOf(listOf<Byte>(0x03)), pty().writes.map { it.toList() })

        clear()
    }

    @Test
    fun `a host that needs its key confirmed fails with a message pointing at the host list`() =
        runTest(dispatcher) {
            // A fingerprint the seeded host row does not carry, so the dial comes
            // back NeedsTrust — the state this screen must NOT try to answer.
            stack.close()
            stack = TestConnectStack(presentedFingerprint = "SHA256:an-unknown-key")
            val hostId = stack.seedHost()
            val viewModel = viewModel()

            viewModel.open(hostId, SESSION)
            settle()

            val state = viewModel.uiState.value
            assertTrue("expected Failed, got $state", state is SessionUiState.Failed)
            assertTrue(
                "the message must send the user to the host list, got: " +
                    (state as SessionUiState.Failed).message,
                state.message.contains("host list"),
            )
            // And no second code path wrote the trust store behind the user.
            assertNull(stack.storedFingerprint(hostId))
        }

    @Test
    fun `a failed dial surfaces the transport's own message`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        stack.factory.failWith = "connect timed out after 30000ms"
        val viewModel = viewModel()

        viewModel.open(hostId, SESSION)
        settle()

        assertEquals(
            SessionUiState.Failed("connect timed out after 30000ms"),
            viewModel.uiState.value,
        )
    }

    /**
     * A resolved exit STATUS means the session really ended — you typed `exit`,
     * or the attach command could not find it. That is not a reconnect case,
     * and the ladder must not run for it: reattaching to a session that is gone
     * would burn 18 seconds and then say the same thing, less clearly.
     */
    @Test
    fun `the session ending flips the screen to Failed with its exit status`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            livePty()
            val viewModel = viewModel()

            viewModel.open(hostId, SESSION)
            settle()
            assertTrue(viewModel.uiState.value is SessionUiState.Live)

            // Exit 3 is `sessions attach`'s "no session named ...".
            pty().finish(3)
            settleFor(3_000)

            val state = viewModel.uiState.value
            assertTrue("expected Failed, got $state", state is SessionUiState.Failed)
            val message = (state as SessionUiState.Failed).message
            assertTrue(
                "the message should name the session and its exit status, got: $message",
                message.contains(SESSION) && message.contains("3"),
            )
            // And nothing tried to reattach behind that message.
            assertEquals(1, connection().ptyRequests.size)
        }

    // --- reconnect (task U-7) -------------------------------------------------

    /**
     * A channel that ends with NO exit status is the link going away under a
     * session that is still alive on the host, so the screen reattaches instead
     * of ending — on the SAME connection when that connection is still good,
     * which is the cheap common case (a killed channel, not a dead socket).
     */
    @Test
    fun `a channel ending without an exit status reattaches`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        livePty()
        val viewModel = viewModel()

        viewModel.open(hostId, SESSION)
        settle()
        val attached = terminalOf(viewModel)

        pty().finish(null)
        settleFor(3_000)

        val state = viewModel.uiState.value
        assertTrue("expected Live again, got $state", state is SessionUiState.Live)
        assertEquals("a second PTY must have been opened", 2, connection().ptyRequests.size)
        assertSame("the reattach must reuse the same terminal", attached, terminalOf(viewModel))

        clear()
    }

    /**
     * The headline U-7 behaviour: a lost transport shows the reconnect banner
     * over the LAST FRAME rather than clearing the screen or ending the
     * session.
     *
     * The dial is held down (`failWith`) so the state can be observed at all —
     * the first rung is 0 ms, so a link that comes straight back would be
     * `Live` again before any assertion could run.
     */
    @Test
    fun `a lost transport shows Reconnecting over the terminal it was showing`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            livePty()
            val viewModel = viewModel()

            viewModel.open(hostId, SESSION)
            settle()
            val attached = terminalOf(viewModel)
            pty().emitText("last-frame-before-the-drop\r\n")
            settle()

            stack.factory.failWith = "no route to host"
            dropLink()
            // Long enough for rung 0 (a 0 ms wait) to fire and fail, short
            // enough that rung 1's one-second countdown is still running.
            settleFor(400)

            val state = viewModel.uiState.value
            assertTrue("expected Reconnecting, got $state", state is SessionUiState.Reconnecting)
            state as SessionUiState.Reconnecting
            assertSame("the emulator must survive the drop", attached, state.terminal)
            // What the user was reading is still on screen: tmux repaints on
            // reattach, so there is deliberately no clear, no snapshot and no
            // reseed. A cleared pane here is the symptom this task exists for.
            assertTrue(
                "the last frame must still be in the screen buffer, got: " +
                    transcriptOf(viewModel),
                transcriptOf(viewModel).contains("last-frame-before-the-drop"),
            )
            // Rung 0 fired at once and failed, so the screen is now counting
            // down rung 1 — the ladder is running, not stuck at zero.
            assertEquals(1, state.attempt)
            assertTrue(
                "the banner needs a countdown to render, got ${state.retryInMs}",
                state.retryInMs in 1..1_000,
            )
        }

    /**
     * Recovery: a fresh connection, the same terminal, and typing works again.
     *
     * `dialCount == 2` is the load-bearing number — a `Lost` [HostConnection]
     * never self-heals, so a reattach that reused it would attach to nothing.
     */
    @Test
    fun `reconnecting dials a fresh connection and comes back Live`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        livePty()
        val viewModel = viewModel()

        viewModel.open(hostId, SESSION)
        settle()
        val attached = terminalOf(viewModel)

        dropLink()
        settleFor(2_000)

        val state = viewModel.uiState.value
        assertTrue("expected Live, got $state", state is SessionUiState.Live)
        assertSame("the reattach must reuse the same terminal", attached, terminalOf(viewModel))
        assertEquals("a FRESH connection, never the lost one", 2, stack.factory.dialCount)
        assertEquals(1, latestConnection().ptyRequests.size)

        // And the recovered session is usable, not just green: bytes typed
        // after the reconnect leave through the NEW channel.
        terminalOf(viewModel).write("echo j05-back\r")
        settle()
        assertEquals("echo j05-back\r", latestPty().writtenText)

        clear()
    }

    /**
     * Regression for issue #2477.
     *
     * A connection closed on PURPOSE — [com.pocketshell.next.connect.ConnectionsRegistry.closeAll] is the
     * shape every journey's own test hygiene uses, called here while this
     * screen's watcher is still alive, exactly the way
     * `J06BackgroundGraceReturnJourney`'s own end-of-test cleanup does on a
     * device — tears the PTY down with no exit status, identically to a
     * genuine network drop. Before the fix this was indistinguishable from
     * [dropLink] and the ladder redialled AT ONCE (rung 0 is 0 ms): a fresh,
     * live connection nobody asked for and nothing was watching landed in the
     * registry, orphaned until the next background/grace check found it
     * "live" — which is exactly what stranded
     * `backgroundingWithNoOpenSessionShowsNoHoldAndNoNotification` on a full,
     * unfiltered `app2` androidTest run: a notification for a session that was
     * never opened in THAT test, left behind by a REDIAL from the previous
     * test's deliberate close.
     *
     * The fix must show [SessionUiState.Failed] (never [SessionUiState.Reconnecting]
     * or [SessionUiState.Live]), must not dial again, and must leave the
     * registry with nothing live for this host — the orphan-connection class
     * this whole regression is about.
     */
    @Test
    fun `a connection closed on purpose ends the session instead of redialling`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            livePty()
            val viewModel = viewModel()

            viewModel.open(hostId, SESSION)
            settle()
            assertTrue(
                "expected Live before the close, got ${viewModel.uiState.value}",
                viewModel.uiState.value is SessionUiState.Live,
            )
            assertEquals(1, stack.factory.dialCount)

            // The exact call J06BackgroundGraceReturnJourney's own test body
            // makes on a real device, while this screen (this ViewModel) is
            // still alive and RESUMED — not a network failure.
            stack.registry.closeAll()
            // Rung 0 is 0 ms: if the bug were still here, this would be long
            // enough for the redial to land.
            settleFor(2_000)

            val state = viewModel.uiState.value
            assertTrue(
                "a DELIBERATE close must end the session, not start a reconnect " +
                    "ladder — got $state",
                state is SessionUiState.Failed,
            )
            assertEquals(
                "no fresh connection may be dialled for a connection closed on purpose",
                1,
                stack.factory.dialCount,
            )
            assertNull(
                "the registry must be left with no orphaned connection for this host",
                stack.registry.current(hostId),
            )

            // Issue #2477's OWN postmortem: without the fix, this scenario ends
            // with a REDIALLED, LIVE bridge whose input pump is a `delay` loop
            // with no terminal condition (see `settle`'s class doc) — so a test
            // that forgot this `clear()` would hang inside `runTest`'s own
            // implicit `advanceUntilIdle()` rather than failing fast. Present
            // for the same reason every other `livePty()` test above calls it,
            // and load-bearing here specifically because THIS test is the one
            // that used to leave a connection nothing was watching.
            clear()
        }

    /** The ladder is finite, and what it leaves behind names the way out. */
    @Test
    fun `exhausting the ladder fails with a message offering Retry`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        livePty()
        val viewModel = viewModel()

        viewModel.open(hostId, SESSION)
        settle()

        stack.factory.failWith = "no route to host"
        dropLink()
        // 0 + 1s + 2s + 5s + 10s = 18s of ladder, plus slack.
        settleFor(20_000)

        val state = viewModel.uiState.value
        assertTrue("expected Failed, got $state", state is SessionUiState.Failed)
        assertTrue(
            "the give-up message must point at the manual retry, got: " +
                (state as SessionUiState.Failed).message,
            state.message.contains("Retry"),
        )
        // Five rungs means five attempts — no sixth, and no storm.
        assertEquals("one initial dial plus five ladder rungs", 6, stack.factory.dialCount)
    }

    /**
     * Retry means "start over", not "skip one wait": the counter goes back to
     * the first rung, which is what makes a tap after a long outage try
     * immediately instead of at the 10-second rung.
     */
    @Test
    fun `retryNow restarts the ladder at its first rung`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        livePty()
        val viewModel = viewModel()

        viewModel.open(hostId, SESSION)
        settle()

        stack.factory.failWith = "no route to host"
        dropLink()
        // Rungs 0 (0 ms), 1 (1 s) and 2 (2 s) are spent; the screen is now
        // waiting out rung 3, the 5-second one.
        settleFor(3_500)
        assertEquals(3, (viewModel.uiState.value as SessionUiState.Reconnecting).attempt)

        viewModel.retryNow()
        settleFor(500)

        // Rung 0 fired at once (and failed, the dial is still down), so the
        // screen is back to counting down rung 1. Without the reset it would
        // have been rung 4.
        val state = viewModel.uiState.value
        assertTrue("expected Reconnecting, got $state", state is SessionUiState.Reconnecting)
        assertEquals(1, (state as SessionUiState.Reconnecting).attempt)
    }

    /** And Retry is still there after the ladder gave up. */
    @Test
    fun `retryNow recovers a session the ladder gave up on`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        livePty()
        val viewModel = viewModel()

        viewModel.open(hostId, SESSION)
        settle()
        val attached = terminalOf(viewModel)

        stack.factory.failWith = "no route to host"
        dropLink()
        settleFor(20_000)
        assertTrue(viewModel.uiState.value is SessionUiState.Failed)

        stack.factory.failWith = null
        viewModel.retryNow()
        settleFor(1_000)

        assertTrue("expected Live, got " + viewModel.uiState.value, viewModel.uiState.value is SessionUiState.Live)
        assertSame(attached, terminalOf(viewModel))

        clear()
    }

    /**
     * D21, and the reason [ForegroundSignal] exists: a backgrounded app makes
     * NO reconnect attempt — not a dial, not a countdown tick — no matter how
     * long it is away. The pre-rewrite client's reconnect storms were this
     * assertion missing.
     */
    @Test
    fun `no reconnect attempt fires while the app is backgrounded`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        livePty()
        val viewModel = viewModel()

        viewModel.open(hostId, SESSION)
        settle()
        val attached = terminalOf(viewModel)
        assertEquals(1, stack.factory.dialCount)

        foreground.background()
        dropLink()
        // Far longer than the whole ladder, which would have given up twice
        // over if it had been running.
        settleFor(60_000)

        val state = viewModel.uiState.value
        assertTrue("expected Reconnecting, got $state", state is SessionUiState.Reconnecting)
        state as SessionUiState.Reconnecting
        assertEquals("the ladder must not have advanced a rung", 0, state.attempt)
        assertEquals(
            "no dial may happen behind the launcher",
            1,
            stack.factory.dialCount,
        )

        // Coming back is what starts it — and it starts at once.
        foreground.foreground()
        settleFor(1_000)

        assertTrue(
            "expected Live after returning, got " + viewModel.uiState.value,
            viewModel.uiState.value is SessionUiState.Live,
        )
        assertEquals(2, stack.factory.dialCount)
        assertSame(attached, terminalOf(viewModel))

        clear()
    }

    @Test
    fun `sendBytes after a failure does not throw and does not resurrect the screen`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            stack.factory.failWith = "no route to host"
            val viewModel = viewModel()

            viewModel.open(hostId, SESSION)
            settle()

            viewModel.sendBytes("ls\r".toByteArray())
            viewModel.onResized(120, 40)
            settle()

            assertEquals(SessionUiState.Failed("no route to host"), viewModel.uiState.value)
        }

    /**
     * The size the view computes reaches the remote — the single `pty.resize`
     * path (task U-5 polishes WHEN it fires; this pins THAT it fires, once).
     */
    @Test
    fun `onResized resizes the remote PTY and the emulator once`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        livePty()
        val viewModel = viewModel()

        viewModel.open(hostId, SESSION)
        settle()

        viewModel.onResized(103, 47)
        settle()
        // The same size again is not a second window-change.
        viewModel.onResized(103, 47)
        settle()

        assertEquals(listOf(103 to 47), pty().resizes)
        val terminal = (viewModel.uiState.value as SessionUiState.Live).terminal
        assertEquals(103, terminal.emulator.mColumns)
        assertEquals(47, terminal.emulator.mRows)

        clear()
    }

    /** A resize that arrives before the attach lands still opens at that size. */
    @Test
    fun `a resize before the attach opens the PTY at the reported size`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        livePty()
        val viewModel = viewModel()

        viewModel.onResized(91, 41)
        viewModel.open(hostId, SESSION)
        settle()

        val request = connection().ptyRequests.single()
        assertEquals(91, request.cols)
        assertEquals(41, request.rows)

        clear()
    }

    /** A second `open()` (recomposition, rotation) must not open a second PTY. */
    @Test
    fun `open is idempotent`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        livePty()
        val viewModel = viewModel()

        viewModel.open(hostId, SESSION)
        viewModel.open(hostId, SESSION)
        settle()
        viewModel.open(hostId, SESSION)
        settle()

        assertEquals(1, connection().ptyRequests.size)

        clear()
    }

    /** Leaving the screen closes the channel but leaves the connection alive. */
    @Test
    fun `onCleared closes the PTY and leaves the host connection open`() = runTest(dispatcher) {
        val hostId = stack.seedHost()
        livePty()
        val viewModel = viewModel()

        viewModel.open(hostId, SESSION)
        settle()

        clear()
        settle()

        assertTrue("the PTY channel must be closed", pty().isEnded)
        assertFalse(
            "the host connection belongs to the registry, not to this screen",
            connection().isClosed,
        )
    }

    // --- helpers -------------------------------------------------------------

    /**
     * The ViewModel, held in a real [ViewModelStore].
     *
     * The store is not ceremony: `onCleared()` is `protected`, so the only
     * honest way to end this ViewModel's life from a test is the way Android
     * ends it — `ViewModelStore.clear()`, which cancels `viewModelScope` AND
     * calls `onCleared()`. Calling a reflectively-unlocked `onCleared()` would
     * skip the scope cancellation and test a lifecycle that never happens.
     */
    private fun viewModel(): SessionViewModel {
        val created = SessionViewModel(
            registry = stack.registry,
            clients = HostCliClientFactory { connection ->
                HostCliClient(connection.asRemoteExec())
            },
            // The REAL ladder, not a test-shortened one: the reconnect
            // assertions below are about the shipped timings, and a ladder
            // substituted here would leave nothing pinning them.
            reconnect = ReconnectController(),
            foreground = foreground,
            dispatcher = dispatcher,
        )
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = created as T
        }
        return ViewModelProvider(store, factory)[SessionViewModel::class.java]
    }

    /** Ends the ViewModel exactly as leaving the screen does. */
    private fun clear() = store.clear()

    /** Scripts the next dial to hand out a PTY that stays open. */
    private fun livePty() {
        stack.factory.script = { connection ->
            connection.enqueuePty(completeAfterFrames = false, exitCode = null)
        }
    }

    private fun connection(): FakeHostConnection = stack.factory.connections.single()

    /** The connection from the most recent dial — after a reconnect there are two. */
    private fun latestConnection(): FakeHostConnection = stack.factory.connections.last()

    private fun pty(): FakePtyChannel = connection().openedPtys.single()

    /** The PTY the session is attached through right now. */
    private fun latestPty(): FakePtyChannel = latestConnection().openedPtys.last()

    private fun transcriptOf(viewModel: SessionViewModel): String =
        terminalOf(viewModel).emulator.screen.transcriptText

    /** The emulator the screen is showing, in whichever state it is showing it. */
    private fun terminalOf(viewModel: SessionViewModel): TerminalSession =
        when (val state = viewModel.uiState.value) {
            is SessionUiState.Live -> state.terminal
            is SessionUiState.Reconnecting -> state.terminal
            else -> error("no terminal on screen: $state")
        }

    /**
     * The link goes away underneath a live session.
     *
     * `markLost` is the transport's OWN drop report (sshj's disconnect listener
     * on a dead socket), which is the event a real network loss produces — and
     * it also makes the spent connection unusable, so a reattach has to dial a
     * fresh one exactly as it would on a device.
     */
    private fun dropLink() = connection().markLost("network dropped")

    /**
     * Runs the virtual clock far enough for the attach chain AND several input
     * poll ticks, then drains the main looper.
     *
     * `advanceUntilIdle()` cannot be used: the input pump is a `delay` loop with
     * no terminal condition, so "the scheduler ran out of work" never happens.
     * The looper drain is separate because the vendored emulator parses on the
     * main thread by design (upstream's contract) and Robolectric's looper is
     * paused, so queued `MSG_NEW_INPUT` messages sit there until dispatched.
     */
    private fun TestScope.settle() = settleFor(SETTLE_STEP_MS * SETTLE_ROUNDS)

    /**
     * [settle] for a named span of virtual time — the reconnect ladder's rungs
     * are seconds apart, and stepping through them in [SETTLE_STEP_MS] slices
     * keeps the coroutine/looper hand-off working the same way it does for the
     * short waits.
     */
    private fun TestScope.settleFor(totalMs: Long) {
        var elapsed = 0L
        while (elapsed < totalMs) {
            advanceTimeBy(SETTLE_STEP_MS)
            runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            elapsed += SETTLE_STEP_MS
        }
    }

    private companion object {
        const val SESSION = "git-pocketshell"

        /**
         * Four rounds of 100 virtual ms. Several rounds rather than one long
         * advance because the output pump and the main looper hand work back and
         * forth: bytes are queued on the coroutine side, parsed on the looper,
         * and a resize posted from one is applied by the other.
         */
        const val SETTLE_STEP_MS = 100L
        const val SETTLE_ROUNDS = 4
    }
}
