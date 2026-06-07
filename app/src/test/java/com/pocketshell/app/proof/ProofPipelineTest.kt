package com.pocketshell.app.proof

import android.os.Looper
import com.pocketshell.core.terminal.bridge.SshTerminalBridge
import com.pocketshell.core.terminal.ui.TerminalSurfaceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * End-to-end smoke test for the Phase 0 byte pipeline.
 *
 * These host-JVM unit tests cover the hermetic terminal pipeline. Docker-backed
 * SSH proof checks live in [ProofPipelineIntegrationTest] under
 * `src/integrationTest`, so the unit-test workflow cannot block on
 * Testcontainers while the Docker coverage still runs in CI's integration job.
 *
 * Robolectric supplies a Looper so the bridge's `TerminalSession` constructor
 * (which instantiates a `Handler` against `Looper.myLooper()`) does not
 * throw. Without that we would need a real Android device.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class ProofPipelineTest {

    /**
     * The acceptance-criterion test: byte pipeline reaches the
     * [TerminalSurfaceState.output] flow.
     *
     * Spins up a [TerminalSurfaceState] (the same one the Compose surface
     * uses), wires it to a hand-rolled byte flow that emits `"phase0"`, and
     * asserts the bytes arrive on the state's output [SharedFlow]. Does
     * not need Docker — the pipeline is fully synthetic.
     */
    @Test
    fun attachExternalProducerEmitsBytesOnOutputFlow() = runBlocking {
        val state = TerminalSurfaceState()
        val source = MutableSharedFlow<ByteArray>(
            replay = 0,
            extraBufferCapacity = 8,
        )
        val collectorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val received = StringBuilder()
        val collected = Job()
        collectorScope.launch {
            state.output
                .takeWhile { !received.contains("phase0") }
                .collect { bytes ->
                    received.append(String(bytes))
                }
            collected.complete()
        }

        // Use a separate scope for the producer pump so we can keep
        // emitting until the collector has seen the marker.
        val pumpScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val producerJob = state.attachExternalProducer(
            scope = pumpScope,
            stdout = source,
            remoteStdin = null,
        )

        try {
            withTimeout(5_000) {
                // Idle the main looper periodically. The bridge's
                // `feedBytes` posts MSG_NEW_INPUT messages to the
                // session's main-thread handler; without idling them they
                // accumulate but never execute, which would otherwise
                // not affect this test (we listen to the output flow which
                // emits synchronously inside `collect`). Idling is kept as
                // defence in depth in case the bridge ever requires the
                // round-trip through the handler before emitting.
                val mainLooperShadow = shadowOf(Looper.getMainLooper())

                // Emit a few times — the SharedFlow's collector may not be
                // ready on the first emission; repeat until it lands.
                repeat(20) {
                    source.emit("phase0\n".toByteArray())
                    mainLooperShadow.idle()
                    if (received.contains("phase0")) return@repeat
                    delay(50)
                }
                collected.join()
            }
            assertTrue(
                "expected `phase0` to land on TerminalSurfaceState.output; got:\n$received",
                received.contains("phase0"),
            )
        } finally {
            producerJob.cancel()
            pumpScope.cancel()
            collectorScope.cancel()
            state.detachExternalProducer()
        }
    }

    /**
     * Item 3 of issue #33: emulator-content assertion.
     *
     * The existing [attachExternalProducerEmitsBytesOnOutputFlow] test proves
     * that bytes reach [TerminalSurfaceState.output], but `output` is a side
     * channel `TerminalSurfaceState.attachExternalProducer` emits to *in
     * addition to* feeding the bridge. A passing assertion on `output` does
     * not by itself prove the bridge's
     * [SshTerminalBridge.feedBytes] → `MSG_NEW_INPUT` → [Handler] →
     * `TerminalEmulator.append` chain ever executes — that path is silent
     * until something inspects the emulator's screen buffer.
     *
     * This test closes the gap. It constructs an [SshTerminalBridge]
     * directly (the same one [TerminalSurfaceState.attachExternalProducer]
     * builds internally), feeds it `echo phase0\n`, idles the Robolectric
     * main looper so the queued `MSG_NEW_INPUT` message runs, and reads back
     * the visible transcript. If `phase0` is missing the bridge's reflective
     * wiring (e.g. the hardcoded `MSG_NEW_INPUT = 1`, the `mEmulator` /
     * `mProcessToTerminalIOQueue` / `mMainThreadHandler` field names) has
     * broken silently and the proof-of-life screen would render an empty
     * terminal at runtime without the test catching it.
     *
     * Does not need Docker — exercises the bridge in isolation.
     */
    @Test
    fun feedBytesRendersOntoEmulatorScreenBuffer() {
        val bridge = SshTerminalBridge()
        try {
            val mainLooperShadow = shadowOf(Looper.getMainLooper())
            val payload = "echo phase0\n".toByteArray()
            bridge.feedBytes(payload)
            // The bridge posts MSG_NEW_INPUT to the session's main-thread
            // handler; without idling the looper the message stays queued
            // and `TerminalEmulator.append` never runs. This is exactly the
            // path the issue calls out as silent.
            mainLooperShadow.idle()

            val transcript = bridge.emulator.screen.transcriptText
            assertTrue(
                "expected `phase0` to appear in the emulator transcript " +
                    "after feedBytes(\"echo phase0\\n\"); got:\n$transcript",
                transcript.contains("phase0"),
            )
        } finally {
            bridge.stop()
        }
    }

}
