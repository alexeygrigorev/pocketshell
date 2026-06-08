package com.pocketshell.core.terminal.ui

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM coverage of [TerminalSurfaceState.emitOutputForTesting].
 *
 * The helper exists for two reasons:
 *
 * 1. Previews can push synthetic output bytes into the state's [output]
 *    flow without standing up a real PTY (see `TerminalSurfacePreview.kt`).
 * 2. Unit tests can exercise the flow plumbing without coupling to the
 *    Termux JNI subprocess machinery.
 *
 * These tests verify the second use case: a caller can subscribe to
 * [TerminalSurfaceState.output], push bytes via `emitOutputForTesting`,
 * and observe them on the other side. They also verify side-effects: each
 * emission ticks `bufferTicks` (debounced by [TerminalSurfaceState.flowOfMatches])
 * so downstream match consumers re-run after a synthetic emission.
 *
 * No Robolectric, no Compose runtime — these run on the host JVM with
 * `kotlinx-coroutines-core`'s [runBlocking] driver.
 */
class TerminalSurfaceStateEmitOutputTest {

    @Test
    fun `emitOutputForTesting delivers bytes to an active collector`() = runBlocking {
        val state = TerminalSurfaceState()
        val received = CompletableDeferred<ByteArray>()

        // Start a collector before emitting. `output` is a SharedFlow with
        // replay = 0 (per TerminalSurfaceState's KDoc) — cold collectors
        // get no replay, so the subscriber MUST be in place before the
        // producer fires. We yield once after launching to give the
        // collector a chance to actually subscribe before emit() runs.
        val collectorJob = launch(Dispatchers.Unconfined) {
            state.output.collect { bytes ->
                if (!received.isCompleted) {
                    received.complete(bytes)
                }
            }
        }
        yield()

        val payload = "hello pocketshell\n".toByteArray()
        val delivered = state.emitOutputForTesting(payload)
        assertTrue("emitOutputForTesting must report delivery", delivered)

        val observed = withTimeout(1_000) { received.await() }
        assertArrayEquals(
            "the bytes pushed via emitOutputForTesting must arrive on the output flow",
            payload,
            observed,
        )
        collectorJob.cancel()
    }

    @Test
    fun `emitOutputForTesting ticks the bufferTicks signal`() = runBlocking {
        val state = TerminalSurfaceState()
        val initialTick = state.bufferTicks.value

        // Need a collector for `output` so this also verifies the public
        // side-channel while checking the tick signal.
        val collectorJob = launch(Dispatchers.Unconfined) {
            state.output.collect { /* drain */ }
        }
        yield()

        state.emitOutputForTesting("x".toByteArray())

        // bufferTicks is a StateFlow; reading `.value` post-emit is
        // deterministic — no race with debounce because we are not going
        // through `flowOfMatches`.
        assertEquals(
            "emitOutputForTesting must increment bufferTicks so debounced match consumers re-run",
            initialTick + 1L,
            state.bufferTicks.value,
        )
        collectorJob.cancel()
    }

    @Test
    fun `emitOutputForTesting delivers a sequence of payloads in order`() = runBlocking {
        val state = TerminalSurfaceState()
        val drained = mutableListOf<ByteArray>()
        val targetCount = 5

        val collectorJob = launch(Dispatchers.Unconfined) {
            state.output.collect { bytes -> drained += bytes }
        }
        yield()

        val payloads = (0 until targetCount).map { i ->
            "chunk-$i\n".toByteArray()
        }
        for (payload in payloads) {
            assertTrue(state.emitOutputForTesting(payload))
        }

        // With an active Unconfined collector, each emit progresses the
        // collector synchronously before returning, so by the time the
        // for-loop finishes the collector has observed every emission. No
        // extra yield / waitFor needed.
        assertEquals(
            "every emitted payload must reach the collector",
            targetCount,
            drained.size,
        )
        for (i in 0 until targetCount) {
            assertArrayEquals(
                "payload order must be preserved across emissions",
                payloads[i],
                drained[i],
            )
        }
        assertEquals(
            "bufferTicks must advance once per emission",
            targetCount.toLong(),
            state.bufferTicks.value,
        )
        collectorJob.cancel()
    }

    @Test
    fun `emitOutputForTesting stays non blocking behind a slow side-channel collector`() = runBlocking {
        val state = TerminalSurfaceState()
        val firstPayloadObserved = CompletableDeferred<Unit>()
        val slowCollector = launch {
            state.output.collect {
                firstPayloadObserved.complete(Unit)
                delay(60_000)
            }
        }
        yield()

        val payloads = (0 until 256).map { i -> "codex-output-$i\n".toByteArray() }
        withTimeout(1_000) {
            payloads.forEach { payload ->
                assertTrue(
                    "terminal output side-channel must accept/drop locally instead of backpressuring rendering",
                    state.emitOutputForTesting(payload),
                )
            }
        }

        withTimeout(1_000) { firstPayloadObserved.await() }
        assertEquals(
            "bufferTicks must still reflect every rendering-side emission even when the side-channel drops",
            payloads.size.toLong(),
            state.bufferTicks.value,
        )
        slowCollector.cancel()
    }

    @Test
    fun `emitOutputForTesting empty array still ticks and emits`() = runBlocking {
        // Documented behaviour: the seam mirrors `MutableSharedFlow.emit`'s
        // contract and does not pre-filter empty arrays. Callers that want
        // a no-op for empties should check size at the call site. This is
        // distinct from the public `writeInput` which DOES short-circuit on
        // empty input — that asymmetry is intentional and worth a test.
        val state = TerminalSurfaceState()
        val tickBefore = state.bufferTicks.value
        val received = CompletableDeferred<ByteArray>()

        val collectorJob = launch(Dispatchers.Unconfined) {
            state.output.collect { bytes ->
                if (!received.isCompleted) {
                    received.complete(bytes)
                }
            }
        }
        yield()

        val delivered = state.emitOutputForTesting(ByteArray(0))
        assertTrue("emitOutputForTesting on empty payload must still report delivery", delivered)

        val observed = withTimeout(1_000) { received.await() }
        assertEquals("empty payload must arrive as a 0-length array", 0, observed.size)
        assertEquals(
            "bufferTicks should advance even for empty payloads",
            tickBefore + 1L,
            state.bufferTicks.value,
        )
        collectorJob.cancel()
    }
}
