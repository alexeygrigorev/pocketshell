package com.pocketshell.core.terminal.bridge

import android.os.Looper
import com.pocketshell.testsupport.drainMainLooperUntil as drainMainLooperUntilShared
import com.termux.terminal.TerminalEmulator
import com.termux.terminal.TerminalSession
import com.termux.terminal.TerminalSessionClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.LooperMode
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@LooperMode(LooperMode.Mode.PAUSED)
class SshTerminalBridgeTest {

    @Test
    fun feedBytesPostsDrainBeforeWaitingOnChunksLargerThanByteQueueCapacity() {
        val trace = RecordingTraceSink()
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 1_000,
            traceSink = trace,
        )
        val payload = largePayload()

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SshTerminalBridgeLargeFeedTest").apply { isDaemon = true }
        }
        val feed = executor.submit {
            bridge.feedBytes(payload.bytes)
        }

        try {
            Thread.sleep(100)
            assertFalse(
                "large feed should wait for a main-thread drain after the first queue-sized chunk",
                feed.isDone,
            )

            // Issue #803: the drain is now frame-paced (the scheduler postDelays
            // its continuation one frame between parse turns), so advance the
            // virtual clock per frame until the producer's blocked write unblocks
            // and the whole payload drains — a single `idle()` would only run the
            // first posted slice.
            drainMainLooperUntil(feed::isDone)

            feed.get(2, TimeUnit.SECONDS)
            assertTrue("large feed must complete once the posted drain runs", feed.isDone)
            drainMainLooperUntil { trace.outputDrainSnapshot().sum() >= payload.bytes.size }

            assertEquals(payload.transcriptText, bridge.emulator.screen.transcriptText)
            assertTrue(
                "expected producer write timing to record at least one wait behind a full queue; trace=$trace",
                trace.queueWrites.any { it.waitedForDrain },
            )
            val outputDrains = trace.outputDrainSnapshot()
            assertEquals(payload.bytes.size, outputDrains.sum())
            assertTrue(
                "bounded drain slices should split at least one queue-sized write; trace=$trace",
                outputDrains.size > trace.scheduledDrains.size,
            )
            assertTrue(
                "terminal drains should stay within the main-thread drain budget",
                outputDrains.all {
                    it in 1..SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES
                },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Regression for the v0.4.19 release-gate HANG: the :shared:core-terminal
     * connected suite wedged the 3h ceiling at ~9/45 because
     * `CodexOutputBurstImeMainThreadProofTest.shellPaneStillRunsAffordanceScanners`'s
     * teardown ([TerminalSurfaceState.detachExternalProducer] -> [SshTerminalBridge.stop])
     * deadlocked: its `%output`-burst producer coroutine was BLOCKED inside
     * `feedChunks` -> writeProcessToTerminalQueue (ByteQueue.write) on a FULL
     * process->terminal queue, holding `feedLock`; `stop()` first cancelled the
     * only main-looper drain turn (so the queue could never make room) and THEN
     * tried to take `feedLock` -> it waited on the blocked producer forever, and
     * the producer waited on a drain that would never run. A thread dump from the
     * wedged emulator process confirmed exactly this: the Instr test thread blocked
     * in `SshTerminalBridge.stop` "waiting to lock <feedLock> held by" the producer
     * worker, which was parked in `ByteQueue.write` on the full queue, while the
     * MAIN thread sat idle in `nativePollOnce` (no drain pending).
     *
     * This reproduces it WITHOUT an emulator: under the PAUSED Robolectric looper
     * the posted drain never runs unless we pump it, so a >64 KB feed on a worker
     * thread blocks in `ByteQueue.write` holding `feedLock` — the identical
     * precondition. We then call `stop()` on its own thread and require it to
     * COMPLETE (it must unblock the producer by closing the output queue) rather
     * than hang. On the pre-fix `stop()` the stop thread stays blocked on
     * `feedLock` forever -> `join(5s)` leaves it alive -> RED. With the fix
     * (`stop()` closes the process->terminal queue before taking `feedLock`) the
     * blocked write returns `false`, the producer releases `feedLock`, and `stop()`
     * returns promptly -> GREEN. The outer `@Test(timeout)` is a backstop only; the
     * load-bearing signal is the `stopThread.isAlive` assertion, which fails fast.
     */
    @Test(timeout = 30_000)
    fun stopUnblocksProducerBlockedOnFullQueueInsteadOfDeadlocking() {
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 1_000,
        )
        val payload = largePayload()
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SshTerminalBridgeBlockedProducer").apply { isDaemon = true }
        }
        val feed = executor.submit { bridge.feedBytes(payload.bytes) }
        try {
            // Deliberately DO NOT pump the PAUSED main looper: the queue fills and
            // the producer blocks in ByteQueue.write holding `feedLock` — the exact
            // deadlock precondition observed on the wedged emulator.
            Thread.sleep(300)
            assertFalse(
                "producer must be BLOCKED on the full process->terminal queue (holding " +
                    "feedLock) for this to reproduce the stop() deadlock; it completed early",
                feed.isDone,
            )

            // stop() must NOT deadlock on `feedLock`: it has to release the blocked
            // producer first (by closing the output queue). Run it off the test
            // thread and bound the wait so a deadlock FAILS FAST instead of hanging.
            val stopThread = Thread({ bridge.stop() }, "SshTerminalBridgeStop").apply {
                isDaemon = true
                start()
            }
            stopThread.join(5_000)
            assertFalse(
                "stop() DEADLOCKED: it waited on feedLock held by a producer blocked on a " +
                    "full, no-longer-draining process->terminal queue (the v0.4.19 release-gate " +
                    "hang). stop() must close the output queue to release the producer before " +
                    "taking feedLock.",
                stopThread.isAlive,
            )

            // And the producer must actually have been released (its blocked write
            // returned false on close), so no wedged producer leaks past teardown.
            val producerReleasedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!feed.isDone && System.nanoTime() < producerReleasedDeadline) {
                Thread.sleep(10)
            }
            assertTrue(
                "the blocked producer must unwind once stop() closes the output queue",
                feed.isDone,
            )
        } finally {
            executor.shutdownNow()
        }
    }

    /**
     * Issue #1459 (the 6th Codex-freeze mechanism — a RENDER-side `feedLock`
     * main-thread deadlock): the live Codex `%output` producer runs off-Main and,
     * on a FULL process→terminal queue, BLOCKS in `writeProcessToTerminalQueue`
     * WHILE HOLDING [SshTerminalBridge.feedLock] (feedChunks takes it at the top and
     * the blocking write is inside that critical section). A reseed/heal apply
     * (`healActivePaneIfStaleRender`/reconciler → `appendRemoteOutput` →
     * [SshTerminalBridge.seedThenOpenGate]) deliberately hops BACK to Main (#926) and
     * takes the SAME `feedLock` on the Main thread. On the pre-fix code the Main
     * thread parks on the `feedLock` monitor → the looper can never run the drain
     * that would free the blocked producer → the queue never drains → the producer
     * never releases `feedLock` → PERMANENT deadlock → ANR → "Codex froze, had to
     * force-restart". `stop()` documents this exact deadlock and escapes it by
     * CLOSING the queue first; the live heal/reseed path had no such escape.
     *
     * This reproduces it synthetically WITHOUT an emulator (the #780 hard-assert
     * model, no `assume`-skip): under the PAUSED Robolectric looper a >64 KB feed on
     * a worker thread blocks in `ByteQueue.write` holding `feedLock` — the identical
     * precondition to the on-device freeze. Then, from the MAIN looper thread (the
     * test thread), we call the reseed apply `seedThenOpenGate(...)`.
     *
     * RED→GREEN: on base, `seedThenOpenGate` parks on the `feedLock` monitor held by
     * the blocked producer and NEVER returns → the `@Test(timeout)` fires (the test
     * HANGS → RED). With the fix the Main-looper acquisition drains the queue to
     * unblock the producer, so the reseed COMPLETES within a bounded deadline — the
     * load-bearing GREEN assertion below (`elapsedMs` bound). No terminal bytes are
     * lost or reordered: the producer's older live output renders in FIFO order,
     * then the reseed snapshot on top.
     */
    @Test(timeout = 30_000)
    fun mainThreadReseedDoesNotDeadlockAgainstProducerBlockedHoldingFeedLock() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 8_000,
        )
        val payload = uniquelyMarkedLargePayload()
        assertTrue(
            "producer payload must exceed one full process->terminal queue so the " +
                "producer BLOCKS in ByteQueue.write holding feedLock",
            payload.bytes.size > SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES,
        )

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Issue1459BlockedProducer").apply { isDaemon = true }
        }
        val feed = executor.submit { bridge.feedBytes(payload.bytes) }
        try {
            // Deliberately DO NOT pump the PAUSED main looper: the queue fills and the
            // producer blocks in ByteQueue.write holding feedLock — the exact #1459
            // deadlock precondition (a live %output burst mid-flight).
            Thread.sleep(300)
            assertFalse(
                "producer must be BLOCKED on the full process->terminal queue (holding " +
                    "feedLock) for this to reproduce the #1459 reseed deadlock; it finished early",
                feed.isDone,
            )

            // The reseed/heal apply runs on the Main looper (this test thread IS the
            // main looper) and MUST NOT park forever on feedLock. On the pre-fix code
            // this call deadlocks → the @Test(timeout) fires (RED).
            val seed = "\r\nreseed-line-A\r\nreseed-line-B".toByteArray(Charsets.US_ASCII)
            val startedAtNanos = System.nanoTime()
            bridge.seedThenOpenGate(seed)
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L

            // LOAD-BEARING GREEN assertion (#1459): the Main-thread reseed COMPLETED
            // while the producer was blocked-holding-feedLock, within a bounded
            // deadline. The deadlock manifests as this line never being reached (the
            // @Test(timeout) hang); a real completion is fast (drain-to-unblock).
            assertTrue(
                "Main-thread reseed must complete within 5s while a producer is blocked " +
                    "holding feedLock (it took ${elapsedMs}ms); the #1459 deadlock returns as a hang",
                elapsedMs < 5_000L,
            )

            // The blocked producer must have been unblocked (the looper drained the
            // queue so its write completed) — no wedged producer leaks.
            val producerReleasedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!feed.isDone && System.nanoTime() < producerReleasedDeadline) {
                Thread.sleep(10)
            }
            assertTrue(
                "the blocked producer must unwind once the Main-thread reseed drains the queue",
                feed.isDone,
            )
            feed.get(1, TimeUnit.SECONDS)

            // Byte-integrity: no lost/reordered bytes. All of the producer's live
            // output renders (its LAST line is present → nothing dropped), then the
            // reseed snapshot renders AFTER it (older live before the seed → no reorder).
            drainMainLooperUntil {
                bridge.emulator.screen.transcriptText.contains("reseed-line-B")
            }
            shadowOf(Looper.getMainLooper()).idle()
            val transcript = bridge.emulator.screen.transcriptText
            val lastProducerMarker = payload.transcriptText // its last marked line
            assertTrue(
                "all producer live output must survive the reseed (last line missing = dropped bytes)",
                transcript.contains(lastProducerMarker),
            )
            assertTrue(
                "the reseed snapshot must render",
                transcript.contains("reseed-line-A") && transcript.contains("reseed-line-B"),
            )
            assertTrue(
                "producer live output must render BEFORE the reseed snapshot (FIFO, no reorder)",
                transcript.indexOf(lastProducerMarker) < transcript.indexOf("reseed-line-A"),
            )
        } finally {
            executor.shutdownNow()
            bridge.stop()
        }
    }

    /**
     * Issue #1491 (the 7th Codex-freeze mechanism — the residual H1-sibling deadlock
     * #1459 left OPEN): the #1459 fix made only [SshTerminalBridge.feedChunks]'s
     * on-looper `feedLock` acquisition non-parking. It left TWO other Main-looper
     * `feedLock.lock()` sites — [SshTerminalBridge.runSeedTailPumpTurn] and
     * [SshTerminalBridge.flushAndOpenGateLocked] — STILL PARKING, guarded only by a
     * comment-invariant ("the pump only ever runs during a seed while the gate is
     * CLOSED, so no off-main producer is inside `feedChunks` holding `feedLock`").
     *
     * That invariant is BROKEN by the force-reseed of a HEALTHY (non-quiesced) live
     * Codex pane: `captureAndApplyPaneSnapshot` closes the seed gate only
     * conditionally (a healthy-looking pane is intentionally NOT quiesced — the
     * #1219/#1164 steady-heat back-off keeps the gate OPEN), then
     * `appendRemoteOutput`→`seedThenOpenGate` schedules the seed-tail pump on an OPEN
     * gate while a live `%output` producer streams. That off-main producer, on a full
     * process→terminal queue, BLOCKS in `writeProcessToTerminalQueue` HOLDING
     * `feedLock`; the pump's plain `feedLock.lock()` then parks THIS looper behind it →
     * the looper can service neither the pump nor the #803 drain → the queue never
     * makes room → the producer never releases `feedLock` → permanent Main-thread
     * deadlock → "backgrounded during a Codex burst, came back, app froze, had to
     * force-restart".
     *
     * This reproduces the [SshTerminalBridge.runSeedTailPumpTurn] site synthetically
     * WITHOUT an emulator (the #780 hard-assert model, NO `assume`-skip): a large
     * multi-chunk seed fed on Main (gate OPEN — never closed) defers its tail to the
     * frame-yielding pump (the #866 handoff), scheduling `runSeedTailPumpTurn` on an
     * OPEN gate. Then a >64 KB off-Main producer blocks in `ByteQueue.write` holding
     * `feedLock` (the identical live-`%output`-mid-flight precondition). We then drive
     * `runSeedTailPumpTurn` on the Main looper (via reflection — a #780-style synthetic
     * injection of the exact concurrency window that H1's own drain-to-unblock would
     * otherwise mask in a single-shot test, because a continuously-streaming pane
     * re-blocks after each unblock).
     *
     * RED→GREEN: on base, the pump parks on the `feedLock` monitor held by the blocked
     * producer and NEVER returns → the `@Test(timeout)` fires (HANGS → RED). With the
     * fix ([SshTerminalBridge.lockFeedForLooperOrBlocking] → the non-parking on-looper
     * acquire) the pump drains the queue to free the producer, completes within a
     * bounded deadline (the load-bearing GREEN `elapsedMs` assertion), and preserves
     * byte order/integrity: the producer's live output renders in FIFO order (its LAST
     * line survives → nothing dropped, in producer index order → no reorder), then the
     * deferred seed TAIL renders AFTER it (older live before the seed → no reorder).
     */
    @Test(timeout = 30_000)
    fun seedTailPumpOnOpenGateDoesNotDeadlockAgainstProducerBlockedHoldingFeedLock() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 20_000,
        )

        // Force-reseed of a HEALTHY pane: the gate is OPEN (never closed) — the exact
        // #1219/#1164 steady-heat state that breaks the #1459 comment-invariant. A
        // large multi-chunk dense-SGR seed (the #866 `capture-pane -e` shape) fed on
        // Main defers its tail to the pump, so `runSeedTailPumpTurn` is scheduled ON AN
        // OPEN GATE. Append a unique end marker so the test can prove the seed TAIL
        // (the part the pump drains) renders.
        val seed = multiChunkDenseSgrSeedPayload().bytes +
            "\r\n$SEED_TAIL_MARKER\r\n".toByteArray(Charsets.US_ASCII)
        bridge.feedBytes(seed)

        // Hard precondition: the seed-tail pump MUST be scheduled on the OPEN gate for
        // this to exercise the #1491 site (not a vacuous pass). If the seed drained
        // fully inline (no handoff) this assert fails loudly rather than green-washing.
        assertTrue(
            "the multi-chunk seed must have deferred its tail to the frame-yielding " +
                "pump (seedTailPumpScheduled), scheduling runSeedTailPumpTurn on the OPEN " +
                "gate — the #1491 precondition",
            seedTailPumpScheduled(bridge),
        )
        assertFalse(
            "the gate must be OPEN (healthy non-quiesced pane) — the state that breaks " +
                "the #1459 pump invariant",
            seedGateClosed(bridge),
        )

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Issue1491PumpBlockedProducer").apply { isDaemon = true }
        }
        val payload = uniquelyMarkedLargePayload()
        assertTrue(
            "producer payload must exceed one full process->terminal queue so the " +
                "producer BLOCKS in ByteQueue.write holding feedLock",
            payload.bytes.size > SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES,
        )
        val feed = executor.submit { bridge.feedBytes(payload.bytes) }
        try {
            // Deliberately DO NOT pump the PAUSED looper: the queue fills and the live
            // producer blocks in ByteQueue.write HOLDING feedLock — the exact #1491
            // deadlock precondition (a live %output burst mid-flight while the pump is
            // scheduled on an open gate).
            Thread.sleep(300)
            assertFalse(
                "producer must be BLOCKED on the full process->terminal queue (holding " +
                    "feedLock) for this to reproduce the #1491 seed-tail-pump deadlock; " +
                    "it finished early",
                feed.isDone,
            )

            // Drive the seed-tail pump on the Main looper (this test thread IS the main
            // looper). On the pre-fix code its plain feedLock.lock() parks FOREVER on the
            // monitor held by the blocked producer, and — because feedLock is an
            // uninterruptible ReentrantLock and Robolectric runs the test on this same
            // main thread — nothing can preempt it: the run HANGS (RED, caught by the CI
            // job timeout; locally by killing the run). With the fix the on-looper
            // acquire ([lockFeedForLooperOrBlocking]) drains the queue to free the
            // producer, so the pump turn COMPLETES within a bounded deadline (the GREEN
            // `elapsedMs` assertion below). This mirrors the sibling #1459 test's shape.
            val startedAtNanos = System.nanoTime()
            invokeRunSeedTailPumpTurn(bridge)
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            assertTrue(
                "seed-tail pump turn must complete within 5s while a producer is blocked " +
                    "holding feedLock (it took ${elapsedMs}ms); the #1491 deadlock returns " +
                    "as a hang (never reaching this line)",
                elapsedMs < 5_000L,
            )

            // The blocked producer must have been released (the looper drained the queue
            // so its write completed) — no wedged producer leaks past the pump turn.
            val producerReleasedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!feed.isDone && System.nanoTime() < producerReleasedDeadline) {
                Thread.sleep(10)
            }
            assertTrue(
                "the blocked producer must unwind once the on-looper pump drains the queue",
                feed.isDone,
            )
            feed.get(1, TimeUnit.SECONDS)

            // Byte-integrity: no lost/reordered bytes. Drain the reposted pump turns +
            // scheduler until the seed tail marker (the LAST seed byte) has rendered.
            drainMainLooperUntil {
                bridge.emulator.screen.transcriptText.contains(SEED_TAIL_MARKER)
            }
            shadowOf(Looper.getMainLooper()).idle()
            val transcript = bridge.emulator.screen.transcriptText

            // Byte integrity — NO drop, NO reorder. The producer's live output is
            // 1200 uniquely-marked lines. Assert the ENTIRE contiguous range 1..1199 is
            // present (a strong no-drop proof — a dropped/clobbered live byte removes its
            // line) and renders in strictly increasing index order (no reorder within the
            // live stream). Line 00000 is excluded ONLY because it shares the seed→producer
            // boundary row: the seed head's inline drain ends mid-row, so line 00000's
            // "producer-00000" LABEL is overwritten by the boundary render while its byte
            // payload still renders — a cursor-position artifact, not a dropped byte
            // (verified: the 80-glyph body of line 00000 is on-screen; only the shared-row
            // label is clobbered). Every subsequent line owns its own row and is intact.
            val missing = (1..1199).map { "producer-%05d".format(it) }
                .filterNot { transcript.contains(it) }
            assertTrue(
                "producer live output must not be dropped — missing lines: ${missing.take(8)}",
                missing.isEmpty(),
            )
            val firstMarker = "producer-00001"
            val midMarker = "producer-00600"
            val lastMarker = "producer-01199"
            assertTrue(
                "producer live output must render in FIFO order (no reorder within the stream)",
                transcript.indexOf(firstMarker) < transcript.indexOf(midMarker) &&
                    transcript.indexOf(midMarker) < transcript.indexOf(lastMarker),
            )
            // The deferred seed TAIL (pumped) renders, and STRICTLY AFTER the producer's
            // last live line — older live before the seed, no reorder (the #468/#866
            // seed-before-live ordering the pump preserves).
            assertTrue(
                "the deferred seed tail (drained by the pump) must render",
                transcript.contains(SEED_TAIL_MARKER),
            )
            assertTrue(
                "the producer's last live line must render BEFORE the seed tail (FIFO, no reorder)",
                transcript.indexOf(lastMarker) < transcript.indexOf(SEED_TAIL_MARKER),
            )
        } finally {
            executor.shutdownNow()
            bridge.stop()
        }
    }

    /**
     * Issue #1491, second residual parking site: [SshTerminalBridge.flushAndOpenGateLocked]
     * (`feedLock.lock()` at the gate-open bookkeeping). It is reached from the PUBLIC
     * `openGateFlushingPending()` (the #468 capture-failed / older-tmux fallback that
     * opens the gate WITHOUT a preceding feed — so nothing drains the queue before the
     * `feedLock.lock()`). Called on Main while a live `%output` producer is BLOCKED in
     * `ByteQueue.write` HOLDING `feedLock`, the plain acquire parks the looper behind
     * the queue-blocked producer → the same permanent Main-thread deadlock as the pump
     * site.
     *
     * This reproduces it synthetically (the #780 hard-assert model, NO `assume`-skip):
     * a >64 KB off-Main producer blocks in `ByteQueue.write` holding `feedLock` (the
     * live-mid-flight precondition), then `openGateFlushingPending()` runs on the Main
     * looper. RED→GREEN: on base, `flushAndOpenGateLocked`'s plain `feedLock.lock()`
     * parks forever → the `@Test(timeout)` fires (RED). With the fix
     * ([SshTerminalBridge.lockFeedForLooperOrBlocking]) it drains the queue to free the
     * producer, completes within a bounded deadline (the GREEN `elapsedMs` assertion),
     * and the producer's live output renders losslessly and in FIFO order.
     */
    @Test(timeout = 30_000)
    fun openGateFlushingPendingDoesNotDeadlockAgainstProducerBlockedHoldingFeedLock() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 20_000,
        )
        // Healthy pane: the gate is OPEN — openGateFlushingPending's flushAndOpenGateLocked
        // still executes its feedLock.lock() regardless of gate state.
        assertFalse(
            "the gate must be OPEN for the healthy-reseed precondition",
            seedGateClosed(bridge),
        )

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Issue1491FlushBlockedProducer").apply { isDaemon = true }
        }
        val payload = uniquelyMarkedLargePayload()
        val feed = executor.submit { bridge.feedBytes(payload.bytes) }
        try {
            // Producer blocks in ByteQueue.write HOLDING feedLock — the deadlock
            // precondition.
            Thread.sleep(300)
            assertFalse(
                "producer must be BLOCKED on the full process->terminal queue (holding " +
                    "feedLock) for this to reproduce the #1491 flushAndOpenGateLocked deadlock",
                feed.isDone,
            )

            // openGateFlushingPending() → flushAndOpenGateLocked() runs on the Main
            // looper. On the pre-fix code its plain feedLock.lock() parks FOREVER on the
            // monitor held by the blocked producer (uninterruptible ReentrantLock on the
            // Robolectric main thread → the run HANGS: RED, caught by the CI job timeout /
            // by killing the run). With the fix the on-looper acquire
            // ([lockFeedForLooperOrBlocking]) drains the queue to free the producer, so it
            // COMPLETES within a bounded deadline (the GREEN `elapsedMs` assertion below).
            val startedAtNanos = System.nanoTime()
            bridge.openGateFlushingPending()
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            assertTrue(
                "openGateFlushingPending must complete within 5s while a producer is " +
                    "blocked holding feedLock (it took ${elapsedMs}ms); the #1491 deadlock " +
                    "returns as a hang (never reaching this line)",
                elapsedMs < 5_000L,
            )

            val producerReleasedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!feed.isDone && System.nanoTime() < producerReleasedDeadline) {
                Thread.sleep(10)
            }
            assertTrue(
                "the blocked producer must unwind once the on-looper gate-open drains the queue",
                feed.isDone,
            )
            feed.get(1, TimeUnit.SECONDS)

            // Byte-integrity: the producer's live output survives losslessly and in FIFO
            // order (no drop, no reorder).
            drainMainLooperUntil {
                bridge.emulator.screen.transcriptText.contains("producer-01199")
            }
            shadowOf(Looper.getMainLooper()).idle()
            val transcript = bridge.emulator.screen.transcriptText
            val firstMarker = "producer-00000"
            val midMarker = "producer-00600"
            val lastMarker = "producer-01199"
            assertTrue(
                "producer live output must not be dropped (first/mid/last markers present)",
                transcript.contains(firstMarker) &&
                    transcript.contains(midMarker) &&
                    transcript.contains(lastMarker),
            )
            assertTrue(
                "producer live output must render in FIFO order (no reorder within the stream)",
                transcript.indexOf(firstMarker) < transcript.indexOf(midMarker) &&
                    transcript.indexOf(midMarker) < transcript.indexOf(lastMarker),
            )
        } finally {
            executor.shutdownNow()
            bridge.stop()
        }
    }

    /**
     * Issue #1489 (the residual the #1459/#1491 fixes left OPEN): EVERY main-looper
     * `feedLock` acquisition routes through [SshTerminalBridge.lockFeedOnLooperNeverParking],
     * whose drain-to-unblock loop USED to fall through to a plain `feedLock.lock()`
     * backstop after a fixed 2 s wall-clock
     * deadline. That backstop STILL deadlocks: a single in-flight `%output` payload
     * larger than 2 s worth of 2 KB drain slices keeps the off-main producer PARKED in
     * `ByteQueue.write` HOLDING `feedLock` when the deadline trips, and the plain
     * `feedLock.lock()` then parks THIS looper behind it → the exact #1459/#1485
     * Main-thread deadlock → "Codex froze, had to force-restart", merely DELAYED by 2 s.
     * The existing #1459/#1491 tests never reach the backstop — their producers drain
     * free within 2 s of real time — so this class member was green-but-unproven.
     *
     * This member (empty-capture heal exit — [SshTerminalBridge.openGateFlushingPending],
     * the #468 capture-failed / older-tmux fallback, gate OPEN so `gated == false`)
     * reproduces the backstop-expiry deadlock DETERMINISTICALLY with the #780 synthetic
     * model (NO `assume`-skip): an injected clock that advances only on MAIN-looper reads
     * and by more than the 2 s deadline per read, so the base drain-to-unblock loop's
     * deadline trips on its first check while the off-main producer is STILL parked
     * holding `feedLock` — exactly the state a >2 s payload creates on-device. On base
     * the backstop's plain `feedLock.lock()` parks forever → the `@Test(timeout)` fires
     * (RED). With the fix ([SshTerminalBridge.lockFeedOnLooperNeverParking] NEVER
     * plain-blocks: it keeps draining while any bytes remain and acquires `feedLock` only
     * via a TIMED `tryLock`) the acquisition drains the queue to free the producer,
     * completes within a bounded deadline (the load-bearing GREEN `elapsedMs` assertion),
     * and preserves the producer's live output losslessly and in FIFO order.
     */
    @Test(timeout = 30_000)
    fun openGateFlushingPendingReachesBackstopWithoutDeadlockWhenDeadlineTripsWithProducerParked() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        // #780 synthetic model: a clock that advances only on MAIN-looper reads, and by
        // MORE than the (base) 2 s deadline per read, so the base drain-to-unblock loop's
        // `while (nowMillis() < deadline)` check is ALWAYS past the deadline on its first
        // read → the loop falls straight through to the plain `feedLock.lock()` backstop
        // WITHOUT draining, while the off-main producer is still parked holding feedLock:
        // the exact >2 s-payload state, injected deterministically. The off-main producer
        // (no looper) reads 0, so it never advances the clock.
        val mainLooper = Looper.getMainLooper()
        val clock = AtomicLong(0L)
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 20_000,
            nowMillis = {
                if (Looper.myLooper() == mainLooper) {
                    clock.getAndAdd(BACKSTOP_TRIP_CLOCK_STEP_MS)
                } else {
                    0L
                }
            },
        )
        // Healthy pane: the gate is OPEN — openGateFlushingPending's flushAndOpenGateLocked
        // acquires feedLock (via the on-looper non-parking helper) regardless of gate state.
        assertFalse(
            "the gate must be OPEN (healthy non-quiesced pane) for the empty-capture heal exit",
            seedGateClosed(bridge),
        )

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Issue1489BackstopFlushProducer").apply { isDaemon = true }
        }
        val payload = uniquelyMarkedLargePayload()
        assertTrue(
            "producer payload must exceed one full process->terminal queue so the " +
                "producer BLOCKS in ByteQueue.write holding feedLock",
            payload.bytes.size > SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES,
        )
        val feed = executor.submit { bridge.feedBytes(payload.bytes) }
        try {
            // Producer blocks in ByteQueue.write HOLDING feedLock — the deadlock
            // precondition (a live %output burst mid-flight).
            Thread.sleep(300)
            assertFalse(
                "producer must be BLOCKED on the full process->terminal queue (holding " +
                    "feedLock) for this to reproduce the #1489 backstop deadlock; it finished early",
                feed.isDone,
            )

            // openGateFlushingPending() → flushAndOpenGateLocked() → the on-looper feedLock
            // acquire runs on Main. On base the injected clock makes the drain-to-unblock
            // loop's deadline trip immediately (no drain), so it falls to the plain
            // `feedLock.lock()` backstop and parks FOREVER behind the queue-blocked producer
            // → HANGS (RED, caught by the @Test timeout). With the fix the acquire never
            // plain-blocks — it drains the queue to free the producer and completes bounded.
            val startedAtNanos = System.nanoTime()
            bridge.openGateFlushingPending()
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            assertTrue(
                "openGateFlushingPending must complete within 5s while a producer is blocked " +
                    "holding feedLock past the backstop deadline (it took ${elapsedMs}ms); the " +
                    "#1489 backstop deadlock returns as a hang (never reaching this line)",
                elapsedMs < 5_000L,
            )

            // The blocked producer must have been released (the looper drained the queue so
            // its write completed) — no wedged producer leaks past the backstop.
            val producerReleasedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!feed.isDone && System.nanoTime() < producerReleasedDeadline) {
                Thread.sleep(10)
            }
            assertTrue(
                "the blocked producer must unwind once the on-looper acquire drains the queue",
                feed.isDone,
            )
            feed.get(1, TimeUnit.SECONDS)

            // Byte-integrity: the producer's live output survives losslessly and in FIFO
            // order (no drop, no reorder). Drain the trailing buffered bytes (the scheduler
            // uses the real Robolectric clock, not the injected budget clock).
            drainMainLooperUntil {
                bridge.emulator.screen.transcriptText.contains("producer-01199")
            }
            shadowOf(Looper.getMainLooper()).idle()
            val transcript = bridge.emulator.screen.transcriptText
            val firstMarker = "producer-00001"
            val midMarker = "producer-00600"
            val lastMarker = "producer-01199"
            assertTrue(
                "producer live output must not be dropped (first/mid/last markers present)",
                transcript.contains(firstMarker) &&
                    transcript.contains(midMarker) &&
                    transcript.contains(lastMarker),
            )
            assertTrue(
                "producer live output must render in FIFO order (no reorder within the stream)",
                transcript.indexOf(firstMarker) < transcript.indexOf(midMarker) &&
                    transcript.indexOf(midMarker) < transcript.indexOf(lastMarker),
            )
        } finally {
            executor.shutdownNow()
            bridge.stop()
        }
    }

    /**
     * Issue #1489, the large-payload backstop-expiry variant through the RESEED-APPLY
     * entry ([SshTerminalBridge.seedThenOpenGate] → [SshTerminalBridge.feedChunks] on
     * Main — the #1459 primary caller). Same residual, different main-looper entry point,
     * for class coverage: the empty-capture heal exit ([openGateFlushingPendingReachesBackstop...])
     * AND the reseed-apply feed both funnel through [SshTerminalBridge.lockFeedOnLooperNeverParking]'s
     * backstop.
     *
     * The producer is a genuine MULTI-CHUNK (~110 KB, > one 64 KB queue) live `%output`
     * payload, so the fix's drain-to-unblock must drain ACROSS chunks to free it (the
     * "single payload larger than the deadline's worth of drain slices" the audit named);
     * the injected clock (#780 synthetic model) deterministically trips the base 2 s
     * deadline while that producer is still parked. On base the reseed feed's on-looper
     * acquire falls to the plain `feedLock.lock()` backstop and parks forever → the seed
     * is never applied and the producer never freed → HANGS (RED, caught by the timeout).
     * With the fix the acquire drains the multi-chunk producer free, applies the seed, and
     * the producer's live output renders losslessly and strictly BEFORE the reseed snapshot
     * (older live before the seed — the #468 FIFO ordering).
     */
    @Test(timeout = 30_000)
    fun reseedApplyReachesBackstopWithoutDeadlockWhenLargePayloadKeepsProducerParked() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val mainLooper = Looper.getMainLooper()
        val clock = AtomicLong(0L)
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 20_000,
            nowMillis = {
                if (Looper.myLooper() == mainLooper) {
                    clock.getAndAdd(BACKSTOP_TRIP_CLOCK_STEP_MS)
                } else {
                    0L
                }
            },
        )

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "Issue1489BackstopReseedProducer").apply { isDaemon = true }
        }
        val payload = uniquelyMarkedLargePayload()
        assertTrue(
            "producer payload must exceed one full process->terminal queue (multi-chunk) so " +
                "the fix must drain ACROSS chunks to free the producer",
            payload.bytes.size > SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES,
        )
        val feed = executor.submit { bridge.feedBytes(payload.bytes) }
        try {
            // Producer blocks in ByteQueue.write HOLDING feedLock — the deadlock
            // precondition (a live %output burst mid-flight while a reseed lands).
            Thread.sleep(300)
            assertFalse(
                "producer must be BLOCKED on the full process->terminal queue (holding " +
                    "feedLock) for this to reproduce the #1489 backstop deadlock; it finished early",
                feed.isDone,
            )

            // A small reseed snapshot fed on Main (seedThenOpenGate). Its feedChunks acquire
            // runs the on-looper non-parking helper; on base the injected clock trips the
            // deadline immediately and it falls to the plain `feedLock.lock()` backstop →
            // parks forever behind the multi-chunk producer → HANGS (RED). With the fix it
            // drains the multi-chunk producer free, then applies the seed (bounded).
            val seed = "\r\nreseed-line-A\r\nreseed-line-B".toByteArray(Charsets.US_ASCII)
            val startedAtNanos = System.nanoTime()
            bridge.seedThenOpenGate(seed)
            val elapsedMs = (System.nanoTime() - startedAtNanos) / 1_000_000L
            assertTrue(
                "the reseed apply must complete within 5s while a large-payload producer is " +
                    "blocked holding feedLock past the backstop deadline (it took ${elapsedMs}ms); " +
                    "the #1489 backstop deadlock returns as a hang (never reaching this line)",
                elapsedMs < 5_000L,
            )

            val producerReleasedDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!feed.isDone && System.nanoTime() < producerReleasedDeadline) {
                Thread.sleep(10)
            }
            assertTrue(
                "the blocked multi-chunk producer must unwind once the reseed apply drains the queue",
                feed.isDone,
            )
            feed.get(1, TimeUnit.SECONDS)

            // Byte-integrity: all producer live output survives (its LAST line present →
            // nothing dropped) and renders BEFORE the reseed snapshot (older live before the
            // seed — the #468 FIFO ordering the drain-to-unblock preserves). feedChunks drains
            // ALL queued producer bytes before writing the seed, so this renders synchronously.
            drainMainLooperUntil {
                bridge.emulator.screen.transcriptText.contains("reseed-line-B")
            }
            shadowOf(Looper.getMainLooper()).idle()
            val transcript = bridge.emulator.screen.transcriptText
            val lastProducerMarker = payload.transcriptText // its last marked line
            assertTrue(
                "all producer live output must survive the reseed (last line missing = dropped bytes)",
                transcript.contains(lastProducerMarker),
            )
            assertTrue(
                "the reseed snapshot must render",
                transcript.contains("reseed-line-A") && transcript.contains("reseed-line-B"),
            )
            assertTrue(
                "producer live output must render BEFORE the reseed snapshot (FIFO, no reorder)",
                transcript.indexOf(lastProducerMarker) < transcript.indexOf("reseed-line-A"),
            )
        } finally {
            executor.shutdownNow()
            bridge.stop()
        }
    }

    @Test(timeout = 5_000)
    fun feedBytesOnMainLooperDrainsLargePayloadWithoutDeadlockOrLoss() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val trace = RecordingTraceSink()
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 1_000,
            traceSink = trace,
        )
        val payload = largePayload()
        assertTrue(
            "main-looper regression payload must exceed one full queue plus one bounded drain slice",
            payload.bytes.size >
                SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES +
                SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES,
        )
        // The payload must exceed the inline BYTE budget so the on-main feed cannot
        // drain it all inline — it must defer the tail to the pump (#866 fast-device).
        assertTrue(
            "regression payload must exceed the inline byte budget so the tail defers",
            payload.bytes.size > SshTerminalBridge.SEED_INLINE_MAX_BYTES,
        )

        bridge.feedBytes(payload.bytes)

        // #866 fast-device fix: a large on-main feed no longer drains the WHOLE
        // payload inline (that synchronous parse is the "Attaching…" ANR on a
        // multi-chunk seed). The inline drain is bounded by SEED_INLINE_MAX_BYTES;
        // the untouched tail is handed to the frame-yielding pump. The producer must
        // NOT deadlock waiting on a posted message it can't run, and the WHOLE
        // payload must still render losslessly once the looper advances.
        val expectedInline =
            (
                SshTerminalBridge.SEED_INLINE_MAX_BYTES +
                    SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES - 1
                ) / SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES
        val inlineSlices = trace.directDrains.size
        assertEquals(
            "the on-main inline drain must stop at the byte budget, not run all slices " +
                "inline (the #866 fast-device ANR); trace=$trace",
            expectedInline,
            inlineSlices,
        )
        assertTrue(
            "the bounded inline drain must leave the bulk for the pump; inline=$inlineSlices " +
                "total=${expectedDrainSlices(payload.bytes.size)}",
            inlineSlices < expectedDrainSlices(payload.bytes.size),
        )

        // Advance the looper so the pump + frame-budgeted scheduler drain the tail.
        drainMainLooperUntil { trace.outputDrainSnapshot().sum() >= payload.bytes.size }
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(payload.transcriptText, bridge.emulator.screen.transcriptText)
        assertEquals(payload.bytes.size, trace.outputDrainSnapshot().sum())
        assertTrue(
            "every drain (inline + pump) must stay within the bounded drain slice",
            trace.directDrains.all {
                it.bytes in 1..SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES
            },
        )
    }

    /**
     * Issue #829: the on-main seed drain ([dispatchMainLooperDrains]) must be
     * TIME-BOUNDED so a large seed cannot pin the main thread in one unbounded
     * inline run. With an injected clock that advances per slice, the inline drain
     * must STOP after the budget trips and hand the remainder — still in FIFO order
     * — to the frame-budgeted scheduler, which paints it across frames. The full
     * payload must still render with no loss.
     *
     * Red→green: before #829 the seed drained ALL slices inline regardless of
     * elapsed time (`directDrains` == total slices), so this asserts the inline
     * count is BOUNDED well below the total and the rest drains via the scheduler.
     */
    @Test(timeout = 5_000)
    fun onMainSeedDrainStopsAtTimeBudgetAndHandsRemainderToScheduler() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val trace = RecordingTraceSink()
        // Clock advances SEED_CLOCK_STEP_MS on EVERY read. The first read is the
        // feed start; each post-slice read then accrues elapsed time, so the budget
        // (SEED_DRAIN_MAX_MILLIS) trips after a small, deterministic slice count.
        val clock = AtomicLong(0L)
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 1_000,
            traceSink = trace,
            nowMillis = { clock.getAndAdd(SEED_CLOCK_STEP_MS) },
        )
        // A single-chunk seed (<= one queue) so it is the FINAL chunk and handoff is
        // allowed, but spanning MANY drain slices so the budget can cut it short.
        val payload = singleChunkMultiSlicePayload()
        val totalSlices = expectedDrainSlices(payload.bytes.size)
        assertTrue(
            "fixture must span well more slices than the budget allows inline",
            totalSlices > EXPECTED_INLINE_SLICES * 2,
        )

        bridge.feedBytes(payload.bytes)

        // The inline seed drain must have STOPPED at the time budget — far fewer
        // direct dispatches than the total slice count (the pre-#829 unbounded run
        // would have dispatched ALL of them inline).
        val inlineSlices = trace.directDrains.size
        // elapsed after slice N = N * SEED_CLOCK_STEP_MS; trips when >= budget.
        val expectedInline =
            ((SshTerminalBridge.SEED_DRAIN_MAX_MILLIS + SEED_CLOCK_STEP_MS - 1) / SEED_CLOCK_STEP_MS).toInt()
        assertEquals(
            "the seed drain must stop at the time budget, not run all slices inline; trace=$trace",
            expectedInline,
            inlineSlices,
        )
        assertTrue(
            "the time-bounded seed drain must leave queued bytes for the scheduler; trace=$trace",
            inlineSlices < totalSlices,
        )

        // The handed-off remainder drains via the frame-budgeted scheduler once the
        // looper advances — the FULL payload must render with no lost bytes.
        drainMainLooperUntil { trace.outputDrainSnapshot().sum() >= payload.bytes.size }
        assertEquals(payload.transcriptText, bridge.emulator.screen.transcriptText)
        assertEquals(payload.bytes.size, trace.outputDrainSnapshot().sum())
    }

    /**
     * Issue #866 (HIGH-severity ANR): a MULTI-CHUNK on-main seed — the Codex
     * alt-screen `capture-pane -e` snapshot over 200+ scrollback rows is several
     * 64 KB chunks — must respect the [SshTerminalBridge.SEED_DRAIN_MAX_MILLIS]
     * budget ACROSS THE WHOLE FEED, not just on the final chunk.
     *
     * This is the exact class-coverage gap that let #829 ship: the existing budget
     * test ([onMainSeedDrainStopsAtTimeBudgetAndHandsRemainderToScheduler]) uses a
     * SINGLE-chunk fixture, so it only ever exercised the final/only chunk — the
     * one chunk the pre-#866 code budgeted. Every NON-final chunk drained FULLY
     * inline (unbudgeted), pinning the main thread for seconds on a multi-chunk
     * seed → the "Attaching…" ANR.
     *
     * Red→green: on the pre-#866 code the first chunk (not the last) is dispatched
     * with `allowHandoff = false`, so the time budget is IGNORED and ALL of that
     * chunk's slices — plus every later chunk's slices — drain inline before the
     * call returns. The inline `directDrains` count therefore equals the TOTAL
     * slice count (far over the budget). With the #866 fix the inline drain STOPS
     * at the budget on the FIRST chunk and the untouched tail is handed to the
     * frame-yielding pump, so the inline count is bounded near the budget. The full
     * payload must still render with no swallowed bytes and no deadlock.
     */
    @Test(timeout = 10_000)
    fun onMainMultiChunkSeedRespectsTimeBudgetAcrossWholeFeedAndHandsTailToPump() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val trace = RecordingTraceSink()
        val clock = AtomicLong(0L)
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = MULTI_CHUNK_SEED_LINES + 100,
            traceSink = trace,
            nowMillis = { clock.getAndAdd(SEED_CLOCK_STEP_MS) },
        )
        // A dense-SGR (alt-screen-shaped) seed spanning SEVERAL 64 KB queue chunks —
        // the #866 ANR shape (>1 chunk), distinct from the #829 single-chunk fixture.
        val payload = multiChunkDenseSgrSeedPayload()
        val totalChunks = expectedChunks(payload.bytes.size)
        val totalSlices = expectedDrainSlices(payload.bytes.size)
        assertTrue(
            "fixture must span MULTIPLE process-to-terminal queue chunks (the #866 ANR shape)",
            totalChunks >= 3,
        )
        assertTrue(
            "fixture must span far more drain slices than the budget allows inline",
            totalSlices > EXPECTED_INLINE_SLICES * 4,
        )

        bridge.feedBytes(payload.bytes)

        // Load-bearing #866 assertion: the inline (synchronous, pre-looper-advance)
        // main-thread drain must STOP at the time budget on the FIRST chunk — NOT
        // run every chunk's slices inline (the pre-#866 ANR). The budget trips after
        // ceil(SEED_DRAIN_MAX_MILLIS / SEED_CLOCK_STEP_MS) slices.
        val inlineSlices = trace.directDrains.size
        val expectedInline =
            ((SshTerminalBridge.SEED_DRAIN_MAX_MILLIS + SEED_CLOCK_STEP_MS - 1) / SEED_CLOCK_STEP_MS).toInt()
        assertEquals(
            "multi-chunk seed must stop inline draining at the WHOLE-FEED time budget " +
                "(pre-#866 drained all $totalSlices slices inline); trace=$trace",
            expectedInline,
            inlineSlices,
        )
        assertTrue(
            "the bounded inline drain must leave the bulk of the seed for the pump; trace=$trace",
            inlineSlices < totalSlices,
        )

        // The handed-off tail (later chunks + queued remainder) drains via the
        // frame-yielding pump once the looper advances — the FULL payload must render
        // with no lost bytes and no deadlock (the test's 10s timeout guards the
        // would-be full-queue deadlock if the tail were written inline).
        drainMainLooperUntil { trace.outputDrainSnapshot().sum() >= payload.bytes.size }
        assertEquals(payload.bytes.size, trace.outputDrainSnapshot().sum())
        assertEquals(payload.transcriptText, bridge.emulator.screen.transcriptText)
    }

    /**
     * Issue #866 REOPEN (fast-device fix): on a FAST/warm device the wall-time
     * budget alone does NOT bound the inline seed drain — the regression that
     * brought #866 back. A pure time budget caps WALL TIME, not WORK: when each
     * 2 KB slice parses in well under [SshTerminalBridge.SEED_DRAIN_MAX_MILLIS] /
     * sliceCount, 24 ms of inline parsing chews through the BULK of a multi-chunk
     * seed before the time budget can trip, so the tail is NOT deferred and the
     * "Attaching…" ANR returns under load (the on-device proof was green cold/
     * isolated but RED warm at test 9/45, where hot-JIT slices parse fast).
     *
     * This reproduces that deterministically by holding the clock STABLE
     * (`nowMillis = { 0L }`), so the wall-time budget can NEVER trip — exactly the
     * limit of an infinitely-fast device. RED on the time-budget-only code: the
     * inline drain runs EVERY slice of the whole multi-chunk feed inline
     * (`directDrains == totalSlices`, the ANR). GREEN with the byte budget: the
     * inline drain stops after [SshTerminalBridge.SEED_INLINE_MAX_BYTES] and hands
     * the untouched tail to the frame-yielding pump — bounded inline parse, no
     * lost bytes, no deadlock (the 10s timeout guards the would-be full-queue
     * deadlock if the tail were written inline).
     */
    @Test(timeout = 10_000)
    fun onMainMultiChunkSeedBoundsInlineDrainByBytesWhenTheClockNeverAdvances() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val trace = RecordingTraceSink()
        // Stable clock: elapsed wall time is ALWAYS 0, so SEED_DRAIN_MAX_MILLIS can
        // never trip — the fast-device case the byte budget exists to bound.
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = MULTI_CHUNK_SEED_LINES + 100,
            traceSink = trace,
            nowMillis = { 0L },
        )
        val payload = multiChunkDenseSgrSeedPayload()
        val totalChunks = expectedChunks(payload.bytes.size)
        val totalSlices = expectedDrainSlices(payload.bytes.size)
        assertTrue(
            "fixture must span MULTIPLE process-to-terminal queue chunks (the #866 ANR shape)",
            totalChunks >= 3,
        )

        bridge.feedBytes(payload.bytes)

        // Load-bearing assertion: with the clock frozen the ONLY thing that can stop
        // the inline drain is the byte budget. The inline drain must stop after
        // ceil(SEED_INLINE_MAX_BYTES / slice) slices — NOT run every slice inline
        // (the time-budget-only code drained all totalSlices here → the ANR).
        val inlineSlices = trace.directDrains.size
        val expectedInline =
            (
                SshTerminalBridge.SEED_INLINE_MAX_BYTES +
                    SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES - 1
                ) / SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES
        assertEquals(
            "frozen-clock multi-chunk seed must stop inline draining at the BYTE budget " +
                "(time-budget-only code drained all $totalSlices slices inline → the #866 " +
                "fast-device ANR); trace=$trace",
            expectedInline,
            inlineSlices,
        )
        assertTrue(
            "the byte-bounded inline drain must leave the bulk of the seed for the pump; " +
                "inline=$inlineSlices total=$totalSlices",
            inlineSlices < totalSlices,
        )

        // The handed-off tail drains via the frame-yielding pump once the looper
        // advances — the FULL payload must render losslessly and without deadlock.
        drainMainLooperUntil { trace.outputDrainSnapshot().sum() >= payload.bytes.size }
        assertEquals(payload.bytes.size, trace.outputDrainSnapshot().sum())
        assertEquals(payload.transcriptText, bridge.emulator.screen.transcriptText)
    }

    /**
     * Issue #866 boundary: a seed that is EXACTLY two queue chunks. Pre-#866 the
     * first (non-final) chunk drained fully inline; the fix bounds it. Confirms the
     * budget+pump handoff also covers the smallest multi-chunk case (not just the
     * many-chunk one), and the whole payload still renders losslessly.
     */
    @Test(timeout = 10_000)
    fun onMainTwoChunkSeedBoundsInlineDrainAndPumpsTheTail() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val trace = RecordingTraceSink()
        val clock = AtomicLong(0L)
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 4_000,
            traceSink = trace,
            nowMillis = { clock.getAndAdd(SEED_CLOCK_STEP_MS) },
        )
        val payload = twoChunkSeedPayload()
        assertEquals(
            "boundary fixture must be exactly two queue chunks",
            2,
            expectedChunks(payload.bytes.size),
        )
        val totalSlices = expectedDrainSlices(payload.bytes.size)

        bridge.feedBytes(payload.bytes)

        // Pre-#866, the FIRST (non-final) chunk drained fully inline (~32 slices for
        // a 64 KB chunk at 2 KB/slice) before the final chunk handed off at the
        // budget, so inline >> the budget. The fix bounds the inline drain to the
        // whole-feed budget (~6 slices) on the very first chunk. Assert the inline
        // count stays near the budget — NOT the whole first chunk's slice count.
        val inlineSlices = trace.directDrains.size
        assertTrue(
            "two-chunk seed must bound the inline drain to the whole-feed budget " +
                "(pre-#866 ran the whole first chunk inline); inline=$inlineSlices " +
                "total=$totalSlices; trace=$trace",
            inlineSlices <= EXPECTED_INLINE_SLICES * 2,
        )

        drainMainLooperUntil { trace.outputDrainSnapshot().sum() >= payload.bytes.size }
        assertEquals(payload.bytes.size, trace.outputDrainSnapshot().sum())
        assertEquals(payload.transcriptText, bridge.emulator.screen.transcriptText)
    }

    /**
     * Issue #866 + #468 ordering: when a large MULTI-CHUNK seed is handed to the
     * pump, the gate's buffered live `%output` must still flush STRICTLY AFTER the
     * whole seed tail (seed-before-live), and live bytes that arrive WHILE the pump
     * is still draining must not race ahead. Drives the real attach shape:
     * closeSeedGate -> buffer live -> seedThenOpenGate(bigSeed) -> drain. The final
     * grid must be seed-then-live in order, with no swallowed bytes.
     */
    @Test(timeout = 10_000)
    fun multiChunkSeedHandoffStillFlushesBufferedLiveStrictlyAfterTheSeed() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val clock = AtomicLong(0L)
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = MULTI_CHUNK_SEED_LINES + 100,
            nowMillis = { clock.getAndAdd(SEED_CLOCK_STEP_MS) },
        )
        bridge.closeSeedGate()

        // Live %output buffered behind the gate while the capture-pane round-trip is
        // in flight — these MUST land after the seed, in order.
        bridge.feedBytes("live-after-seed-1\r\n".toByteArray(Charsets.US_ASCII))
        bridge.feedBytes("live-after-seed-2".toByteArray(Charsets.US_ASCII))

        // The big multi-chunk seed lands and is handed to the pump. End the seed
        // with a CRLF so the buffered live output starts on a fresh row (the seed's
        // last cursor position is otherwise mid-row, an artifact of the fixture, not
        // of the ordering under test).
        val seed = multiChunkDenseSgrSeedPayload()
        val seedBytes = seed.bytes + "\r\n".toByteArray(Charsets.US_ASCII)
        bridge.seedThenOpenGate(seedBytes)

        // Drain the pump + scheduler until the deferred buffered-live flush has run
        // (the second live line is the last byte fed through the whole pipeline).
        drainMainLooperUntil {
            bridge.emulator.screen.transcriptText.contains("live-after-seed-2")
        }
        shadowOf(Looper.getMainLooper()).idle()

        val transcript = bridge.emulator.screen.transcriptText
        val nonEmpty = transcript.lineSequence().map { it.trimEnd() }.filter { it.isNotEmpty() }.toList()
        // Seed renders first; the two buffered live lines are the LAST visible lines,
        // in arrival order — never interleaved into the middle of the seed tail.
        val tail = nonEmpty.takeLast(2)
        assertEquals(
            "buffered live output must flush strictly after the whole multi-chunk seed tail",
            listOf("live-after-seed-1", "live-after-seed-2"),
            tail,
        )
        // And the seed's own content is all present before the live tail (no
        // swallowed seed bytes, no live byte interleaved into the seed body).
        assertTrue(
            "the seed body must be present before the live tail",
            nonEmpty.size > 2 && nonEmpty.first().isNotEmpty(),
        )
        val firstLiveIndex = nonEmpty.indexOf("live-after-seed-1")
        assertEquals(
            "live output must be the contiguous final block (strictly after the seed)",
            nonEmpty.size - 2,
            firstLiveIndex,
        )
    }

    /**
     * Issue #829 control: the COMMON small seed must still drain fully inline (the
     * budget only bounds the pathological case). With a stable clock the budget
     * never trips, so the whole seed paints synchronously before the call returns
     * (the seed-before-live ordering #468/#803 depends on is preserved).
     */
    @Test(timeout = 5_000)
    fun onMainSmallSeedDrainsFullyInlineWithinBudget() {
        assertEquals(Looper.getMainLooper(), Looper.myLooper())
        val trace = RecordingTraceSink()
        // Stable clock: elapsed time never grows, so the budget never trips.
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 1_000,
            traceSink = trace,
            nowMillis = { 0L },
        )
        val seed = "seed-line-1\r\nseed-line-2\r\nseed-line-3".toByteArray(Charsets.US_ASCII)

        bridge.feedBytes(seed)

        // Drained fully inline before returning — the transcript is already painted
        // with NO scheduler frame advance needed.
        assertEquals(
            "small seed must render fully inline within the time budget",
            "seed-line-1\nseed-line-2\nseed-line-3",
            bridge.emulator.screen.transcriptText,
        )
        assertEquals(seed.size, trace.outputDrainSnapshot().sum())
        assertEquals(1, trace.directDrains.size)
    }

    @Test
    fun defaultTracePathUsesSuppliedClientDirectly() {
        val client = RecordingTerminalSessionClient()
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 1_000,
            client = client,
        )

        assertSame(client, terminalSessionClient(bridge.session))
        assertSame(client, terminalEmulatorClient(bridge.emulator))

        bridge.feedBytes("client-callback".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(1, client.textChangedCount)
    }

    @Test
    fun feedBytesValidatesFullRequestedRangeBeforeWritingAnyChunk() {
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = 1_000,
        )
        val payload = ByteArray(SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES) { 'x'.code.toByte() }

        try {
            bridge.feedBytes(payload, offset = 0, count = payload.size + 1)
        } catch (expected: IllegalArgumentException) {
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals("", bridge.emulator.screen.transcriptText)
            return
        }

        throw AssertionError("feedBytes should reject an out-of-bounds offset/count range")
    }

    @Test
    fun rawSshStyleBurstRendersWithoutHangingAndRecordsDrainCounts() {
        val trace = RecordingTraceSink()
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = RAW_BURST_LINES + 100,
            traceSink = trace,
        )
        val payload = rawSshStyleBurstPayload()

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SshTerminalBridgeRawBurstStressTest").apply { isDaemon = true }
        }
        val feed = executor.submit {
            bridge.feedBytes(payload.bytes)
        }

        try {
            drainMainLooperUntil(feed::isDone)
            feed.get(2, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(payload.transcriptText, bridge.emulator.screen.transcriptText)
            assertEquals(1, trace.feedCompletions.size)
            assertEquals(payload.bytes.size, trace.feedCompletions.single().bytes)
            assertEquals(expectedChunks(payload.bytes.size), trace.feedCompletions.single().chunks)
            assertEquals(expectedChunks(payload.bytes.size), trace.queueWrites.size)
            assertEquals(expectedChunks(payload.bytes.size), trace.scheduledDrains.size)
            val outputDrains = trace.outputDrainSnapshot()
            assertEquals(payload.bytes.size, outputDrains.sum())
            assertTrue(
                "raw burst should be drained in multiple bounded terminal drains",
                outputDrains.size > trace.scheduledDrains.size,
            )
            assertTrue(
                "terminal drains should stay within the main-thread drain budget",
                outputDrains.all {
                    it in 1..SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES
                },
            )
            val screenDrains = trace.screenUpdateSnapshot().filter { it.bytes > 0 }
            assertTrue(
                "all queue write timings should be captured",
                trace.queueWrites.all { it.bytes > 0 && it.durationNanos >= 0L },
            )
            assertTrue(
                "all non-empty scheduled drains should reach the screen-update callback",
                screenDrains.all { it.scheduleToCallbackNanos >= 0L },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun tmuxOutputStyleBurstChunksRenderAndKeepPerFeedTiming() {
        val trace = RecordingTraceSink()
        val bridge = SshTerminalBridge(
            columns = LINE_LENGTH,
            rows = 24,
            transcriptRows = TMUX_BURST_LINES + 100,
            traceSink = trace,
        )
        val chunks = tmuxOutputStyleBurstChunks()
        val expectedTranscript = chunks.joinToString(separator = "\n") { chunk ->
            chunk.decodeToString().removeSuffix("\r\n")
        }

        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SshTerminalBridgeTmuxBurstStressTest").apply { isDaemon = true }
        }
        val feed = executor.submit {
            chunks.forEach { chunk ->
                bridge.feedBytes(chunk)
            }
        }

        try {
            drainMainLooperUntil(feed::isDone)
            feed.get(2, TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()

            assertEquals(expectedTranscript, bridge.emulator.screen.transcriptText)
            assertEquals(chunks.size, trace.feedCompletions.size)
            assertEquals(chunks.sumOf { expectedChunks(it.size) }, trace.queueWrites.size)
            assertEquals(trace.queueWrites.size, trace.scheduledDrains.size)
            assertTrue(
                "tmux-style feed completions should retain per-feed timing; trace=$trace",
                trace.feedCompletions.all { it.chunks == 1 && it.durationNanos >= 0L },
            )
            assertTrue(
                "tmux-style queue writes should retain per-feed timing; trace=$trace",
                trace.queueWrites.all { it.bytes > 0 && it.durationNanos >= 0L },
            )
            val outputDrains = trace.outputDrainSnapshot()
            assertTrue(outputDrains.isNotEmpty())
            assertEquals(chunks.sumOf { it.size }, outputDrains.sum())
            assertTrue(
                "tmux-style terminal drains should stay within the main-thread drain budget",
                outputDrains.all {
                    it in 1..SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES
                },
            )
            val screenUpdates = trace.screenUpdateSnapshot()
            assertTrue(screenUpdates.isNotEmpty())
            assertTrue(screenUpdates.size <= trace.scheduledDrains.size)
            assertTrue(
                "many small tmux-style feeds should coalesce redundant drain/render callbacks",
                screenUpdates.size < trace.scheduledDrains.size,
            )
            assertTrue(
                "tmux-style burst should capture screen update latency for every mapped terminal drain",
                screenUpdates.filter { it.bytes > 0 }.all { it.scheduleToCallbackNanos >= 0L },
            )
        } finally {
            executor.shutdownNow()
        }
    }

    // -------------------------------------------------------------------
    // Issue #468: seed gate — live %output must never race the capture-pane
    // seed. The garble in the report is a stale snapshot landing AFTER live
    // deltas: the snapshot's ESC[2J clears live state and repaints an old
    // frame, then the next cursor-relative live delta paints onto a grid that
    // does not match (stranded/mashed frames, blank screen). The gate makes
    // seed-before-live deterministic.
    // -------------------------------------------------------------------

    /**
     * Reproduces the garble WITHOUT the gate: live deltas land, then the
     * (now-stale) capture-pane seed clears and repaints over them. The visible
     * row ends up holding the seeded frame, not the latest live frame — the
     * exact corruption from the screenshot. This is the negative control that
     * proves the test fixture actually exercises the race.
     */
    @Test
    fun ungatedSeedRacingLiveOutputCorruptsTheVisibleRow() {
        val bridge = SshTerminalBridge(columns = 40, rows = 4, transcriptRows = 100)

        // Live frames arrive first (no gate). A bare CR rewrites column 0 of
        // the current row in place — the agent-spinner pattern.
        bridge.feedBytes("frame-LIVE-LATEST".toByteArray(Charsets.US_ASCII))
        bridge.feedBytes("\rframe-LIVE-NEWEST".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()

        // The seed snapshot was captured at an OLDER instant; it clears the
        // screen and repaints the stale frame, with the cursor restored at the
        // end of the captured line.
        val staleSeed = "\u001b[H\u001b[2Jframe-SEED-STALE\u001b[0m\u001b[H".toByteArray(Charsets.US_ASCII)
        bridge.feedBytes(staleSeed)
        shadowOf(Looper.getMainLooper()).idle()

        val visibleRow0 = bridge.emulator.screen.transcriptText.lineSequence().first().trimEnd()
        // The bug: the stale seed wins, the latest live frame is gone.
        assertEquals("frame-SEED-STALE", visibleRow0)
    }

    /**
     * The fix: with the gate closed up front, live deltas are buffered until
     * the seed is applied, then replayed in order. The seed paints first, the
     * latest live frame overwrites it cleanly — the visible row holds the live
     * frame, not the stale snapshot. No reordering, no stranded frame.
     */
    @Test
    fun gatedSeedAppliesBeforeBufferedLiveOutputSoLatestLiveFrameWins() {
        val bridge = SshTerminalBridge(columns = 40, rows = 4, transcriptRows = 100)
        bridge.closeSeedGate()

        // Live frames arrive WHILE gated — buffered, not yet on the emulator.
        bridge.feedBytes("frame-LIVE-LATEST".toByteArray(Charsets.US_ASCII))
        bridge.feedBytes("\rframe-LIVE-NEWEST".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(
            "gated live output must not reach the emulator yet",
            "",
            bridge.emulator.screen.transcriptText.trimEnd(),
        )

        // Seed lands: snapshot first, then the buffered live deltas in order.
        val seed = "\u001b[H\u001b[2Jframe-SEED-STALE\u001b[0m\u001b[H".toByteArray(Charsets.US_ASCII)
        bridge.seedThenOpenGate(seed)
        shadowOf(Looper.getMainLooper()).idle()

        val visibleRow0 = bridge.emulator.screen.transcriptText.lineSequence().first().trimEnd()
        assertEquals("frame-LIVE-NEWEST", visibleRow0)
    }

    /**
     * After the gate opens, subsequent live bytes flow straight through with
     * no buffering and stay in order relative to the seed and the earlier
     * buffered burst.
     */
    @Test
    fun liveOutputAfterSeedFlowsThroughInOrder() {
        val bridge = SshTerminalBridge(columns = 40, rows = 8, transcriptRows = 100)
        bridge.closeSeedGate()

        bridge.feedBytes("buffered-1\r\n".toByteArray(Charsets.US_ASCII))
        bridge.feedBytes("buffered-2\r\n".toByteArray(Charsets.US_ASCII))
        bridge.seedThenOpenGate("seed-line\r\n".toByteArray(Charsets.US_ASCII))
        bridge.feedBytes("post-seed-1\r\n".toByteArray(Charsets.US_ASCII))
        bridge.feedBytes("post-seed-2".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()

        val visible = bridge.emulator.screen.transcriptText
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotEmpty() }
            .toList()
        assertEquals(
            listOf("seed-line", "buffered-1", "buffered-2", "post-seed-1", "post-seed-2"),
            visible,
        )
    }

    /**
     * Seed-failure fallback: when no snapshot ever arrives (capture-pane
     * failed / older tmux), [openGateFlushingPending] flushes the buffered
     * live output in order rather than swallowing it. This is the
     * self-recovery / no-permanent-blank guarantee.
     */
    @Test
    fun openGateWithoutSeedFlushesBufferedLiveOutputInOrder() {
        val bridge = SshTerminalBridge(columns = 40, rows = 8, transcriptRows = 100)
        bridge.closeSeedGate()

        bridge.feedBytes("live-a\r\n".toByteArray(Charsets.US_ASCII))
        bridge.feedBytes("live-b".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("", bridge.emulator.screen.transcriptText.trimEnd())

        bridge.openGateFlushingPending()
        shadowOf(Looper.getMainLooper()).idle()

        val visible = bridge.emulator.screen.transcriptText
            .lineSequence()
            .map { it.trimEnd() }
            .filter { it.isNotEmpty() }
            .toList()
        assertEquals(listOf("live-a", "live-b"), visible)
    }

    @Test
    fun seedGateRejectsLiveBufferOverflowAndClearsTheGate() {
        val bridge = SshTerminalBridge(columns = 40, rows = 8, transcriptRows = 100)
        bridge.closeSeedGate()

        val overflow = try {
            bridge.feedBytes(ByteArray(SshTerminalBridge.MAX_SEED_GATE_LIVE_BUFFER_BYTES + 1) {
                'x'.code.toByte()
            })
            null
        } catch (expected: TerminalSeedGateOverflowException) {
            expected
        }

        assertTrue("seed gate should reject unbounded live buffering", overflow != null)
        assertEquals(0, overflow!!.pendingBytes)
        assertEquals(
            SshTerminalBridge.MAX_SEED_GATE_LIVE_BUFFER_BYTES + 1,
            overflow.incomingBytes,
        )
        assertEquals(SshTerminalBridge.MAX_SEED_GATE_LIVE_BUFFER_BYTES, overflow.maxBytes)

        bridge.feedBytes("after-overflow".toByteArray(Charsets.US_ASCII))
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(
            "overflow should clear the gate so no further bytes accumulate behind a failed seed",
            "after-overflow",
            bridge.emulator.screen.transcriptText.trimEnd(),
        )
    }

    /**
     * Heavy/bursty deterministic stress: while gated, drive a large burst of
     * numbered live frames concurrently with the seed. The final grid must be
     * exactly seed-then-ordered-live with every byte present and in order — no
     * dropped frame, no reorder, no stranded snapshot. This is the
     * deterministic burst fixture the issue asks for.
     */
    @Test(timeout = 10_000)
    fun heavyBurstWhileGatedThenSeedKeepsEveryFrameInOrder() {
        val bridge = SshTerminalBridge(
            columns = 60,
            rows = 24,
            transcriptRows = SEED_BURST_LINES + 100,
        )
        bridge.closeSeedGate()

        // A producer thread blasts numbered frames while gated — mirrors the
        // Dispatchers.IO producer in attachExternalProducer.
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "SshTerminalBridgeSeedBurstStressTest").apply { isDaemon = true }
        }
        val burst = executor.submit {
            for (line in 0 until SEED_BURST_LINES) {
                val text = "live-burst-%05d ".format(line) +
                    ('a'.code + (line % 26)).toChar().toString().repeat(20) +
                    "\r\n"
                bridge.feedBytes(text.toByteArray(Charsets.US_ASCII))
            }
        }

        try {
            burst.get(5, TimeUnit.SECONDS)
            // Seed lands after the whole burst is buffered.
            bridge.seedThenOpenGate("seed-header\r\n".toByteArray(Charsets.US_ASCII))
            drainMainLooperUntil { true }
            shadowOf(Looper.getMainLooper()).idle()

            val lines = bridge.emulator.screen.transcriptText
                .lineSequence()
                .map { it.trimEnd() }
                .filter { it.isNotEmpty() }
                .toList()
            assertEquals("seed-header", lines.first())
            // Every live frame present, in order, immediately after the seed.
            val liveLines = lines.drop(1)
            assertEquals(SEED_BURST_LINES, liveLines.size)
            liveLines.forEachIndexed { index, line ->
                assertTrue(
                    "frame $index out of order or missing: '$line'",
                    line.startsWith("live-burst-%05d ".format(index)),
                )
            }
        } finally {
            executor.shutdownNow()
        }
    }

    private data class Payload(
        val bytes: ByteArray,
        val transcriptText: String,
    )

    private fun largePayload(): Payload {
        val payloadLines = List(LINES_LARGER_THAN_PROCESS_QUEUE) { line ->
            ('a'.code + (line % 26)).toChar().toString().repeat(LINE_LENGTH - 1)
        }
        val payloadText = payloadLines.joinToString(separator = "\n")
        val payloadWireText = buildString {
            payloadLines.forEachIndexed { index, line ->
                if (index > 0) append("\r\n")
                append(line)
            }
        }
        val payload = payloadWireText.toByteArray(Charsets.US_ASCII)
        assertTrue(
            "test payload must exceed the process-to-terminal ByteQueue capacity",
            payload.size > SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES,
        )
        return Payload(payload, payloadText)
    }

    /**
     * Issue #1459: a live-`%output`-shaped payload larger than one process→terminal
     * queue (so the producer BLOCKS holding `feedLock`), with a UNIQUE per-line
     * marker so the reseed regression can assert the producer's LAST line survived
     * (no dropped bytes) and rendered BEFORE the reseed snapshot (FIFO, no reorder).
     * Each line stays under the column width so it renders on one row (the marker is
     * not split across a wrap).
     */
    private fun uniquelyMarkedLargePayload(): Payload {
        val payloadLines = List(RESEED_PRODUCER_LINES) { line ->
            "producer-%05d ".format(line) +
                ('a'.code + (line % 26)).toChar().toString().repeat(LINE_LENGTH - 20)
        }
        val payloadWireText = payloadLines.joinToString(separator = "\r\n")
        val payload = payloadWireText.toByteArray(Charsets.US_ASCII)
        assertTrue(
            "reseed producer fixture must exceed one process-to-terminal queue",
            payload.size > SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES,
        )
        // transcriptText carries the LAST line's marker so the test can assert it
        // survived and ordered before the seed.
        return Payload(payload, payloadLines.last())
    }

    /**
     * Issue #829: a seed that fits in ONE process-to-terminal queue write (so it is
     * the final/only chunk and on-main handoff is allowed) but spans MANY drain
     * slices, so a time budget can cut the inline drain short with bytes left over
     * for the scheduler.
     */
    private fun singleChunkMultiSlicePayload(): Payload {
        val payloadLines = List(SINGLE_CHUNK_SEED_LINES) { line ->
            ('a'.code + (line % 26)).toChar().toString().repeat(LINE_LENGTH - 1)
        }
        val payloadText = payloadLines.joinToString(separator = "\n")
        val payloadWireText = payloadLines.joinToString(separator = "\r\n")
        val payload = payloadWireText.toByteArray(Charsets.US_ASCII)
        assertTrue(
            "fixture must fit one queue write (single/final chunk) so handoff is allowed",
            payload.size <= SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES,
        )
        assertTrue(
            "fixture must span several drain slices",
            payload.size > SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES * 8,
        )
        return Payload(payload, payloadText)
    }

    /**
     * Issue #866: a dense-SGR (alt-screen-shaped) seed spanning SEVERAL 64 KB
     * process-to-terminal queue chunks — the multi-chunk Codex `capture-pane -e`
     * snapshot shape that triggered the "Attaching…" ANR. Each line carries
     * per-cell SGR colour escapes (like a TUI redraw) so the parse is work-dense,
     * not just append-cheap.
     */
    private fun multiChunkDenseSgrSeedPayload(): Payload {
        val payloadLines = List(MULTI_CHUNK_SEED_LINES) { line ->
            denseSgrLine(line)
        }
        // transcriptText strips the SGR escapes; the visible glyphs are what render.
        val payloadText = payloadLines.joinToString(separator = "\n") { stripSgr(it) }
        val payloadWireText = payloadLines.joinToString(separator = "\r\n")
        val payload = payloadWireText.toByteArray(Charsets.US_ASCII)
        assertTrue(
            "multi-chunk seed fixture must exceed several process-to-terminal queue chunks",
            payload.size > SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES * 2,
        )
        return Payload(payload, payloadText)
    }

    /**
     * Issue #866 boundary fixture: a seed sized to exactly TWO queue chunks, so the
     * first chunk is non-final (the case the pre-#866 code drained fully inline).
     */
    private fun twoChunkSeedPayload(): Payload {
        // Target ~1.5 queues of payload so it splits into exactly two chunks.
        val targetBytes = SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES + (32 * 1024)
        val lines = mutableListOf<String>()
        var bytes = 0
        var line = 0
        while (bytes < targetBytes) {
            val text = "two-chunk-%05d ".format(line) +
                ('a'.code + (line % 26)).toChar().toString().repeat(LINE_LENGTH - 16)
            lines += text
            bytes += text.toByteArray(Charsets.US_ASCII).size + 2 // + CRLF
            line += 1
        }
        val payloadText = lines.joinToString(separator = "\n")
        val payloadWireText = lines.joinToString(separator = "\r\n")
        return Payload(payloadWireText.toByteArray(Charsets.US_ASCII), payloadText)
    }

    private fun denseSgrLine(line: Int): String = buildString {
        val glyphCount = LINE_LENGTH - 1
        for (col in 0 until glyphCount) {
            val color = 31 + ((line + col) % 7) // SGR 31..37 (foreground colours)
            append("[").append(color).append('m')
            append(('a'.code + ((line + col) % 26)).toChar())
        }
        append("[0m")
    }

    private fun stripSgr(s: String): String = SGR_REGEX.replace(s, "")

    private fun rawSshStyleBurstPayload(): Payload {
        val payloadLines = List(RAW_BURST_LINES) { line ->
            "ssh-burst-%04d ".format(line) +
                ('A'.code + (line % 26)).toChar().toString().repeat(LINE_LENGTH - 16)
        }
        val payloadText = payloadLines.joinToString(separator = "\n")
        val payloadWireText = payloadLines.joinToString(separator = "\r\n")
        val payload = payloadWireText.toByteArray(Charsets.US_ASCII)
        assertTrue(
            "raw burst fixture must span several process-to-terminal queue drains",
            payload.size > SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES * 4,
        )
        return Payload(payload, payloadText)
    }

    private fun tmuxOutputStyleBurstChunks(): List<ByteArray> =
        List(TMUX_BURST_LINES) { line ->
            val text = "tmux-output-%04d ".format(line) +
                ('a'.code + (line % 26)).toChar().toString().repeat(LINE_LENGTH - 17) +
                "\r\n"
            text.toByteArray(Charsets.US_ASCII)
        }

    private fun expectedChunks(byteCount: Int): Int =
        (byteCount + SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES - 1) /
            SshTerminalBridge.PROCESS_TO_TERMINAL_QUEUE_CAPACITY_BYTES

    private fun expectedDrainSlices(byteCount: Int): Int =
        (byteCount + SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES - 1) /
            SshTerminalBridge.PROCESS_TO_TERMINAL_DRAIN_SLICE_BYTES

    private fun drainMainLooperUntil(done: () -> Boolean) {
        // Issue #1048: the bounded-wall-clock loop + the HARD deadline now live in
        // the ONE shared, audited settle-pump. This test is NOT inside a `runTest`,
        // so it has no `TestScope` and cannot `runCurrent()` — its only per-tick
        // drain is idling the main looper a frame. Issue #803: the off-main live
        // drain is frame-paced — the MainThreadDrainScheduler `postDelayed`s its
        // continuation one frame (16ms) out so the main looper is guaranteed a
        // servicing gap between parse turns. Under Robolectric PAUSED looper a
        // plain `idle()` only runs tasks already DUE, so advance the looper one
        // frame per tick to fire the delayed continuations and let the queue drain.
        val drained = drainMainLooperUntilShared(
            deadlineMs = TimeUnit.SECONDS.toMillis(10),
            sleepMs = 1L,
            onTick = { shadowOf(Looper.getMainLooper()).idleFor(16L, TimeUnit.MILLISECONDS) },
            condition = done,
        )
        if (!drained) {
            throw AssertionError("timed out waiting for feedBytes burst to drain")
        }
        // Final flush: drain any trailing scheduled continuation.
        shadowOf(Looper.getMainLooper()).idleFor(64L, TimeUnit.MILLISECONDS)
    }

    private class RecordingTraceSink : SshTerminalBridge.TraceSink() {
        val queueWrites = Collections.synchronizedList(mutableListOf<QueueWrite>())
        val scheduledDrains = Collections.synchronizedList(mutableListOf<ScheduledDrain>())
        val directDrains = Collections.synchronizedList(mutableListOf<DirectDrain>())
        val outputDrains = Collections.synchronizedList(mutableListOf<Int>())
        val screenUpdates = Collections.synchronizedList(mutableListOf<ScreenUpdate>())
        val feedCompletions = Collections.synchronizedList(mutableListOf<FeedCompletion>())

        override fun onProcessQueueWrite(bytes: Int, durationNanos: Long, waitedForDrain: Boolean) {
            queueWrites += QueueWrite(bytes, durationNanos, waitedForDrain)
        }

        override fun onDrainMessageScheduled(bytes: Int, pendingMessages: Int, directDispatch: Boolean) {
            scheduledDrains += ScheduledDrain(bytes, pendingMessages, directDispatch)
        }

        override fun onProcessOutputDrained(bytes: Int) {
            outputDrains += bytes
        }

        override fun onDirectDrainDispatched(bytes: Int, durationNanos: Long) {
            directDrains += DirectDrain(bytes, durationNanos)
        }

        override fun onScreenUpdated(bytes: Int, scheduleToCallbackNanos: Long, callbackDurationNanos: Long) {
            screenUpdates += ScreenUpdate(bytes, scheduleToCallbackNanos, callbackDurationNanos)
        }

        override fun onFeedCompleted(bytes: Int, chunks: Int, durationNanos: Long) {
            feedCompletions += FeedCompletion(bytes, chunks, durationNanos)
        }

        override fun toString(): String =
            "RecordingTraceSink(queueWrites=$queueWrites, scheduledDrains=$scheduledDrains, " +
                "directDrains=$directDrains, outputDrains=$outputDrains, screenUpdates=$screenUpdates, " +
                "feedCompletions=$feedCompletions)"

        fun outputDrainSnapshot(): List<Int> =
            synchronized(outputDrains) {
                outputDrains.toList()
            }

        fun screenUpdateSnapshot(): List<ScreenUpdate> =
            synchronized(screenUpdates) {
                screenUpdates.toList()
            }
    }

    private data class QueueWrite(val bytes: Int, val durationNanos: Long, val waitedForDrain: Boolean)
    private data class ScheduledDrain(val bytes: Int, val pendingMessages: Int, val directDispatch: Boolean)
    private data class DirectDrain(val bytes: Int, val durationNanos: Long)
    private data class ScreenUpdate(val bytes: Int, val scheduleToCallbackNanos: Long, val callbackDurationNanos: Long)
    private data class FeedCompletion(val bytes: Int, val chunks: Int, val durationNanos: Long)

    private class RecordingTerminalSessionClient : TerminalSessionClient {
        var textChangedCount = 0
            private set

        override fun onTextChanged(changedSession: TerminalSession) {
            textChangedCount += 1
        }

        override fun onTitleChanged(changedSession: TerminalSession) = Unit
        override fun onSessionFinished(finishedSession: TerminalSession) = Unit
        override fun onCopyTextToClipboard(session: TerminalSession, text: String) = Unit
        override fun onPasteTextFromClipboard(session: TerminalSession?) = Unit
        override fun onBell(session: TerminalSession) = Unit
        override fun onColorsChanged(session: TerminalSession) = Unit
        override fun onTerminalCursorStateChange(state: Boolean) = Unit
        override fun setTerminalShellPid(session: TerminalSession, pid: Int) = Unit
        override fun getTerminalCursorStyle(): Int? = null
        override fun logError(tag: String?, message: String?) = Unit
        override fun logWarn(tag: String?, message: String?) = Unit
        override fun logInfo(tag: String?, message: String?) = Unit
        override fun logDebug(tag: String?, message: String?) = Unit
        override fun logVerbose(tag: String?, message: String?) = Unit
        override fun logStackTraceWithMessage(tag: String?, message: String?, e: Exception?) = Unit
        override fun logStackTrace(tag: String?, e: Exception?) = Unit
    }

    /**
     * Issue #1491: read the private `seedTailPumpScheduled` flag so the reproduction
     * can HARD-assert the seed-tail pump is scheduled on an OPEN gate before driving
     * it (a vacuous pass — pump not scheduled — must fail loudly, not green-wash).
     */
    private fun seedTailPumpScheduled(bridge: SshTerminalBridge): Boolean {
        val field = SshTerminalBridge::class.java
            .getDeclaredField("seedTailPumpScheduled")
            .apply { isAccessible = true }
        return field.getBoolean(bridge)
    }

    /** Issue #1491: read the private `gated` flag (true == seed gate CLOSED). */
    private fun seedGateClosed(bridge: SshTerminalBridge): Boolean {
        val field = SshTerminalBridge::class.java
            .getDeclaredField("gated")
            .apply { isAccessible = true }
        return field.getBoolean(bridge)
    }

    /**
     * Issue #1491: drive the private [SshTerminalBridge.runSeedTailPumpTurn] on the
     * Main looper (this test thread). A #780-style synthetic injection of the exact
     * on-device concurrency window (pump scheduled on an OPEN gate + a live producer
     * re-blocked holding feedLock) that a single-shot public-API call cannot hold open,
     * because H1's own drain-to-unblock frees the producer before the posted pump runs.
     */
    private fun invokeRunSeedTailPumpTurn(bridge: SshTerminalBridge) {
        val method = SshTerminalBridge::class.java
            .getDeclaredMethod("runSeedTailPumpTurn")
            .apply { isAccessible = true }
        method.invoke(bridge)
    }

    private fun terminalSessionClient(session: TerminalSession): TerminalSessionClient {
        val field = TerminalSession::class.java.getDeclaredField("mClient").apply { isAccessible = true }
        return field.get(session) as TerminalSessionClient
    }

    private fun terminalEmulatorClient(emulator: TerminalEmulator): TerminalSessionClient {
        val field = TerminalEmulator::class.java.getDeclaredField("mClient").apply { isAccessible = true }
        return field.get(emulator) as TerminalSessionClient
    }

    private companion object {
        private const val LINE_LENGTH = 100
        private const val LINES_LARGER_THAN_PROCESS_QUEUE = 900

        // Issue #1459: reseed-vs-blocked-producer fixture. ~1200 * 94 B ≈ 110 KB —
        // comfortably more than one 64 KB process->terminal queue, so the producer
        // blocks in ByteQueue.write holding feedLock (the deadlock precondition).
        private const val RESEED_PRODUCER_LINES = 1_200
        private const val RAW_BURST_LINES = 5_000
        private const val TMUX_BURST_LINES = 1_200

        // Issue #468: deterministic seed/live burst fixture size. Large enough
        // that the buffered live burst spans many process-to-terminal queue
        // drains while gated, so a reorder/drop would show up.
        private const val SEED_BURST_LINES = 2_000

        // Issue #829: on-main seed time-budget fixture. ~300 * 101 B ≈ 30 KB — one
        // queue write (single/final chunk, handoff allowed) spanning ~15 drain
        // slices, so the time budget can cut the inline drain short.
        private const val SINGLE_CHUNK_SEED_LINES = 300

        // Injected clock step per nowMillis() read; with SEED_DRAIN_MAX_MILLIS=24
        // the budget trips after ceil(24/4)=6 inline slices.
        private const val SEED_CLOCK_STEP_MS = 4L
        private const val EXPECTED_INLINE_SLICES = 6

        // Issue #1489: injected-clock step per MAIN-looper nowMillis() read for the
        // backstop-expiry reproductions. Set ABOVE the (base) 2 s
        // acquire deadline so the base drain-to-unblock loop's
        // `while (nowMillis() < deadline)` check is ALWAYS past the deadline on its first
        // read → it falls straight to the plain `feedLock.lock()` backstop while the
        // off-main producer is still parked (the >2 s-payload state, injected). The fix
        // never reads the clock in that loop, so the step value is irrelevant to GREEN.
        private const val BACKSTOP_TRIP_CLOCK_STEP_MS = 10_000L

        // Issue #866: multi-chunk on-main seed fixture. Each dense-SGR line is far
        // wider than its glyph count (per-cell colour escapes), so ~1200 lines span
        // SEVERAL 64 KB queue chunks — the multi-chunk Codex alt-screen seed that
        // pinned the main thread (every non-final chunk drained inline pre-#866).
        private const val MULTI_CHUNK_SEED_LINES = 1_200

        // Issue #1491: unique marker appended to the END of the multi-chunk seed so the
        // reproduction can prove the seed TAIL (the part the frame-yielding pump drains)
        // renders — and renders strictly AFTER the producer's live output (no reorder).
        private const val SEED_TAIL_MARKER = "SEED-TAIL-MARKER-1491"

        // Strips SGR (CSI ... m) escapes so the expected transcript matches the
        // glyphs Termux renders (it consumes the colour escapes, not the screen).
        private val SGR_REGEX = Regex("\\[[0-9;]*m")
    }
}
