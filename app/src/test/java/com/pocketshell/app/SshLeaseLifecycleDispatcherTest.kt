package com.pocketshell.app

import androidx.lifecycle.Lifecycle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SshLeaseLifecycleDispatcherTest {
    @Test
    fun `process start cannot overtake in flight process stop`() = runTest {
        val stopMayFinish = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val dispatcher = SshLeaseLifecycleDispatcher(
            scope = backgroundScope,
            onProcessStopped = {
                calls += "stop-start"
                stopMayFinish.await()
                calls += "stop-end"
            },
            onProcessStarted = {
                calls += "start"
            },
        )

        dispatcher.dispatch(Lifecycle.Event.ON_STOP)
        runCurrent()
        dispatcher.dispatch(Lifecycle.Event.ON_START)
        runCurrent()

        assertEquals(listOf("stop-start"), calls)

        stopMayFinish.complete(Unit)
        runCurrent()

        assertEquals(listOf("stop-start", "stop-end", "start"), calls)
        dispatcher.close()
    }

    @Test
    fun `ssh lifecycle events run in dispatch order`() = runTest {
        val calls = mutableListOf<String>()
        val dispatcher = SshLeaseLifecycleDispatcher(
            scope = backgroundScope,
            onProcessStopped = { calls += "stop" },
            onProcessStarted = { calls += "start" },
        )

        dispatcher.dispatch(Lifecycle.Event.ON_STOP)
        dispatcher.dispatch(Lifecycle.Event.ON_START)
        dispatcher.dispatch(Lifecycle.Event.ON_STOP)
        runCurrent()

        assertEquals(listOf("stop", "start", "stop"), calls)
        dispatcher.close()
    }
}
