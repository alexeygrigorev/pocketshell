package com.pocketshell.app.tmux

import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Regression proof for the stale-session dismiss owner cancellation seam. */
class TmuxSessionViewModelStaleDismissOwnerTest : TmuxSessionViewModelTestBase() {
    @Test
    fun detachAndExitCancelsStaleConnectAndReconnectOwnersBeforeCloseCascade() =
        runTest(scheduler) {
            val vm = newVm()
            val connect = Job()
            vm.beginConnectingForTest("10.0.2.2", 2222, "testuser", "claude-main", connect)
            val reconnect = Job()
            vm.autoReconnectJob = reconnect

            vm.detachAndExit()

            assertTrue(connect.isCancelled)
            assertTrue(reconnect.isCancelled)
            assertNull(vm.connectJob)
            assertNull(vm.autoReconnectJob)
            assertNull(vm.latestRestoreIntentSnapshot())
            assertNull(vm.connectingSessionNameForTest())
            advanceUntilIdle()
        }
}
