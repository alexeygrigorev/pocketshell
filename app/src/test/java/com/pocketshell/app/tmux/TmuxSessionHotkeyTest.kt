package com.pocketshell.app.tmux

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exact-byte guards for the issue #1662 two-page Ctrl flow. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionHotkeyTest : TmuxSessionViewModelTestBase() {

    private fun TmuxSessionViewModel.attachForHotkeys(): FakeTmuxClient =
        FakeTmuxClient().also { client ->
            attachClientForTest(client)
            applyParsedPanesForTest(
                listOf(TmuxSessionViewModel.ParsedPane("%0", "@0", "\$0", "shell", 0)),
            )
        }

    private fun FakeTmuxClient.hotkeyCommands(): List<String> =
        sentCommands.filter { it.startsWith("send-keys") }

    @Test
    fun everyCtrlPickerKeyMapsToItsExactControlByte() = runTest(scheduler) {
        val vm = newVm()
        val client = vm.attachForHotkeys()
        advanceUntilIdle()

        val labels = ('A'..'Z').map { "^$it" } + "^\\"
        labels.forEach { label -> vm.onKeyBarKey("%0", label) }
        advanceUntilIdle()

        val expected = (1..26).map { byte -> "%02x".format(byte) } + "1c"
        assertEquals(
            expected.map { "send-keys -H -t %0 $it" },
            client.hotkeyCommands(),
        )
    }

    @Test
    fun ctrlQIsXonAndRepeatedCtrlBRemainsTwoIndependentBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = vm.attachForHotkeys()
        advanceUntilIdle()

        vm.onKeyBarKey("%0", "^Q")
        vm.onKeyBarKey("%0", "^B")
        vm.onKeyBarKey("%0", "^B")
        advanceUntilIdle()

        assertEquals(
            listOf(
                "send-keys -H -t %0 11",
                "send-keys -H -t %0 02",
                "send-keys -H -t %0 02",
            ),
            client.hotkeyCommands(),
        )
    }

    @Test
    fun doubledControlsStayAtomicAndContainBothBytes() = runTest(scheduler) {
        val vm = newVm()
        val client = vm.attachForHotkeys()
        advanceUntilIdle()

        vm.onKeyBarKey("%0", TmuxHotkeyInterruptX2Label)
        vm.onKeyBarKey("%0", TmuxHotkeyEofX2Label)
        advanceUntilIdle()

        assertEquals(
            listOf(
                "send-keys -H -t %0 03 03",
                "send-keys -H -t %0 04 04",
            ),
            client.hotkeyCommands(),
        )
    }

    @Test
    fun ordinaryTwoTapCtrlCAndCtrlDRemainFourIndependentSingleByteActions() =
        runTest(scheduler) {
            val vm = newVm()
            val client = vm.attachForHotkeys()
            advanceUntilIdle()

            vm.onKeyBarKey("%0", "^C")
            vm.onKeyBarKey("%0", "^C")
            vm.onKeyBarKey("%0", "^D")
            vm.onKeyBarKey("%0", "^D")
            advanceUntilIdle()

            assertEquals(
                listOf(
                    "send-keys -H -t %0 03",
                    "send-keys -H -t %0 03",
                    "send-keys -H -t %0 04",
                    "send-keys -H -t %0 04",
                ),
                client.hotkeyCommands(),
            )
        }
}
