package com.pocketshell.next.terminal

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.hostapi.HostCliClient
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows

/**
 * Typing at the "Reconnecting" banner must still run when the ladder lands
 * (issue #2566).
 *
 * ## What broke
 *
 * Before #2566, bytes written to the vendored `TerminalSession` while no bridge
 * was pumping landed in its 4 KB terminal→process ring buffer and were drained
 * by the NEXT bridge's input pump. That buffer is what carried a keystroke
 * across a reconnect: the user types at the banner, the ladder attaches, the
 * command runs.
 *
 * #2566's first design replaced that buffer with a `TerminalSession.InputSink`
 * the bridge installs in `start()` and clears in `stop()`, and dropped anything
 * written while no sink was installed. Between
 * `SessionViewModel.releaseChannel()` (stop) and the ladder's next successful
 * `attachOnce()` (start) there is a window — seconds, since it spans a full SSH
 * dial — in which every keystroke was therefore lost. Not hypothetical: with
 * that design
 * `J06BackgroundGraceReturnJourney.returningAfterTheGraceWindowExpiresReattachesInsteadOfReportingTheSessionEnded`
 * went red on a real emulator against the Docker fixture, and this test went red
 * with it while passing on `c79e17051`.
 *
 * ## What it asserts now
 *
 * The session holds what is written while no sink is installed, bounded by
 * `TerminalSession.PENDING_INPUT_CAPACITY_BYTES`, and flushes it to the sink
 * the next bridge installs. This test drives the WHOLE screen — a real
 * [SessionViewModel], a real reconnect ladder, a real [TerminalSession] — so it
 * fails if any layer between the keystroke and the reattached channel stops
 * carrying those bytes, not just if the session's buffer is removed.
 *
 * The per-unit halves live in `TerminalSessionTest` (flush order, exactly-once,
 * the capacity bound) and `TerminalPtyBridgeTest` (a fresh bridge receives what
 * was typed in the gap).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TerminalInputAcrossReconnectRegressionTest {

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
    fun `typing while the reconnect ladder is dialling reaches the reattached channel`() =
        runTest(dispatcher) {
            val hostId = stack.seedHost()
            stack.factory.script = { c -> c.enqueuePty(completeAfterFrames = false, exitCode = null) }
            val viewModel = viewModel()
            viewModel.open(hostId, "probe")
            settle()
            val terminal = (viewModel.uiState.value as SessionUiState.Live).terminal

            // The link goes away, and the dial is held down so the ladder is
            // genuinely mid-flight when the user types.
            stack.factory.failWith = "no route to host"
            stack.factory.connections.single().markLost("network dropped")
            settleFor(400)
            check(viewModel.uiState.value is SessionUiState.Reconnecting) {
                "expected Reconnecting, got ${viewModel.uiState.value}"
            }

            // The user types at the banner, exactly as J06 does on a device.
            terminal.write("echo typed-while-reconnecting\r")
            settleFor(200)

            // Now the link comes back.
            stack.factory.failWith = null
            viewModel.retryNow()
            settleFor(3_000)

            val state = viewModel.uiState.value
            check(state is SessionUiState.Live) { "expected Live, got $state" }
            val pty: FakePtyChannel = stack.factory.connections.last().openedPtys.last()
            assertEquals(
                "keystrokes typed while the ladder was dialling must reach the " +
                    "reattached channel",
                "echo typed-while-reconnecting\r",
                pty.writtenText,
            )
            store.clear()
        }

    private fun viewModel(): SessionViewModel {
        val created = SessionViewModel(
            registry = stack.registry,
            clients = HostCliClientFactory { c -> HostCliClient(c.asRemoteExec()) },
            reconnect = ReconnectController(),
            foreground = foreground,
            dispatcher = dispatcher,
            mainDispatcher = dispatcher,
        )
        val factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T = created as T
        }
        return ViewModelProvider(store, factory)[SessionViewModel::class.java]
    }

    private fun TestScope.settle() = settleFor(400)

    private fun TestScope.settleFor(totalMs: Long) {
        var elapsed = 0L
        while (elapsed < totalMs) {
            advanceTimeBy(100)
            runCurrent()
            Shadows.shadowOf(Looper.getMainLooper()).idle()
            elapsed += 100
        }
    }

}
