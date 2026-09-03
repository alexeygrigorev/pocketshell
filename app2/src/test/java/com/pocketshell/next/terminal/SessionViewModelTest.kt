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
     * The PTY ending is the whole of U-4's failure handling: no reconnect, no
     * retry, just an honest "it ended" (task U-7 owns the rest).
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
            settle()

            val state = viewModel.uiState.value
            assertTrue("expected Failed, got $state", state is SessionUiState.Failed)
            val message = (state as SessionUiState.Failed).message
            assertTrue(
                "the message should name the session and its exit status, got: $message",
                message.contains(SESSION) && message.contains("3"),
            )
        }

    /** A dropped transport — output completes with no exit status at all. */
    @Test
    fun `output ending without an exit status still flips the screen to Failed`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            livePty()
            val viewModel = viewModel()

            viewModel.open(hostId, SESSION)
            settle()

            pty().finish(null)
            settle()

            val state = viewModel.uiState.value
            assertTrue("expected Failed, got $state", state is SessionUiState.Failed)
            assertTrue((state as SessionUiState.Failed).message.contains(SESSION))
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

    private fun pty(): FakePtyChannel = connection().openedPtys.single()

    private fun transcriptOf(viewModel: SessionViewModel): String =
        (viewModel.uiState.value as SessionUiState.Live).terminal.emulator.screen.transcriptText

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
    private fun TestScope.settle() {
        repeat(SETTLE_ROUNDS) {
            advanceTimeBy(SETTLE_STEP_MS)
            runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
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
