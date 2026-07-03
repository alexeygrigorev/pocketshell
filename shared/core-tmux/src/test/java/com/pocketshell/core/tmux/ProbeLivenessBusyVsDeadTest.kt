package com.pocketshell.core.tmux

import com.pocketshell.core.tmux.protocol.ControlEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #927 — the "busy ≠ dead" distinction for the liveness probe
 * ([TmuxClient.probeLiveness]).
 *
 * The maintainer dogfood: "on the same wifi Terminus stays connected but
 * PocketShell keeps restarting." One contributor (#895 spike): the foreground
 * liveness probe's `refresh-client` round-trip can be PARKED behind a heavy,
 * legitimate `%output` burst (the control-mode FIFO serialises commands behind a
 * busy agent stream). On the OLD code that parked/failed reply counted as a probe
 * MISS even though the channel was demonstrably alive (the reader was still
 * parsing `%output`), and two such misses force-redialed the warm lease — the
 * visible "restart".
 *
 * The fix: [TmuxClient.probeLiveness] treats RECENT reader activity
 * ([TmuxClient.millisSinceLastReaderActivity] within
 * [TmuxClient.readerActivityLivenessWindowMs]) as positive liveness evidence. A
 * busy-but-alive channel (recent `%output`) reports ALIVE even when the
 * `refresh-client` reply did not arrive; a genuinely dead half-open channel
 * parses NOTHING, so its activity age grows without bound and it still reports
 * DEAD.
 *
 * Reproduce-first (D33/G10): the `busy` case FAILS red on the pre-#927
 * `probeLiveness` (which returned `false` whenever the reply failed, ignoring
 * reader activity) and passes green with the fix; the `dead` case proves the
 * guard never masks a real drop.
 */
class ProbeLivenessBusyVsDeadTest {

    /**
     * A minimal [TmuxClient] exercising ONLY the interface-default
     * [TmuxClient.probeLiveness]. [bestEffortIsError] simulates a parked/failed
     * `refresh-client` reply; [readerActivityAgeMs] simulates how long ago the
     * control reader last parsed a `%output`/block.
     */
    private class FakeProbeClient(
        private val bestEffortIsError: Boolean,
        private val readerActivityAgeMs: Long,
        private val isDisconnected: Boolean = false,
    ) : TmuxClient {
        private val disconnectedState = MutableStateFlow(isDisconnected)

        override fun millisSinceLastReaderActivity(): Long = readerActivityAgeMs

        override suspend fun sendBestEffortCommand(cmd: String): CommandResponse =
            CommandResponse(number = 1L, output = emptyList(), isError = bestEffortIsError)

        // --- unused interface surface (the default probeLiveness is the SUT) ---
        override suspend fun connect() = Unit
        override suspend fun sendCommand(cmd: String): CommandResponse =
            error("not used")
        override val events: Flow<ControlEvent> = emptyFlow()
        override fun outputFor(paneId: String): Flow<ControlEvent.Output> = emptyFlow()
        override fun drainPaneOutputBacklog(paneId: String): Int = 0
        override val disconnected: StateFlow<Boolean> = disconnectedState
        override val disconnectEvent: StateFlow<TmuxDisconnectEvent?> = MutableStateFlow(null)
        override val outputBacklogOverflows: Flow<TmuxOutputBacklogOverflow> = emptyFlow()
        override fun close() = Unit
        override suspend fun setWindowSizeLatest(sessionId: String): CommandResponse =
            error("not used")
        override suspend fun refreshClientSize(cols: Int, rows: Int): CommandResponse =
            error("not used")
        override suspend fun detachCleanly(timeoutMs: Long) = Unit
    }

    @Test
    fun `refresh-client reply arrives - probe is alive`() = runTest {
        // Healthy steady state: reply round-trips, no reliance on reader activity.
        val client = FakeProbeClient(
            bestEffortIsError = false,
            readerActivityAgeMs = Long.MAX_VALUE,
        )
        assertTrue("a clean refresh-client round-trip is alive", client.probeLiveness())
    }

    @Test
    fun `busy channel - reply parked behind a fresh %output burst is ALIVE (not a miss)`() =
        runTest {
            // The #927 reproduce-first case: the refresh-client reply did NOT come
            // back (parked behind a legitimate output storm), but the reader
            // parsed a control block very recently → the channel is alive.
            val client = FakeProbeClient(
                bestEffortIsError = true,
                readerActivityAgeMs = 100, // well within the 3s window
            )
            assertTrue(
                "a parked refresh-client reply over a channel still delivering " +
                    "%output must report ALIVE, not a dead-channel miss (#927)",
                client.probeLiveness(),
            )
        }

    @Test
    fun `dead half-open channel - no reply AND no recent reader activity is DEAD`() = runTest {
        // A genuine silent half-open drop: nothing answers and the reader has
        // parsed nothing for far longer than the liveness window → still DEAD, so
        // dead-peer detection (#822) is unaffected by the busy-vs-dead guard.
        val client = FakeProbeClient(
            bestEffortIsError = true,
            readerActivityAgeMs = 60_000, // way past the 3s window
        )
        assertFalse(
            "a dead half-open channel (no reply, no recent reader activity) must " +
                "still report DEAD (#822 not regressed)",
            client.probeLiveness(),
        )
    }

    @Test
    fun `stale reader activity just past the window does NOT mask a dead channel`() = runTest {
        // Boundary: reader activity older than the window must NOT count as alive.
        val client = FakeProbeClient(
            bestEffortIsError = true,
            readerActivityAgeMs = TmuxClient.DEFAULT_READER_ACTIVITY_LIVENESS_WINDOW_MS + 1,
        )
        assertFalse(
            "reader activity older than the liveness window must not mask a dead " +
                "channel (#927 guard is bounded)",
            client.probeLiveness(),
        )
    }

    @Test
    fun `disconnected client is DEAD regardless of reader activity`() = runTest {
        val client = FakeProbeClient(
            bestEffortIsError = false,
            readerActivityAgeMs = 0,
            isDisconnected = true,
        )
        assertFalse(
            "a disconnected client is dead even with fresh reader activity",
            client.probeLiveness(),
        )
    }

    // ---- Issue #1193: NETWORK-TRANSITION probe (requireAnsweredRoundTrip) ----
    //
    // The #927 reader-activity fallback is correct for a STEADY-STATE periodic probe
    // (a busy-but-alive link mid-`%output`-burst). But on a WiFi↔cellular
    // restore/handoff those recent reader bytes crossed the OLD socket's 4-tuple —
    // they do NOT prove the NEW default network's path is alive. On the maintainer's
    // cellular flap the old socket was silently dead while the last-inbound-byte age
    // was still fresh, so a fallback-tolerant probe would ride through onto the dead
    // transport and the reader threw ~157ms later. A transition probe must require an
    // actual ANSWERED round-trip.

    @Test
    fun `transition probe ignores the reader-activity fallback - a busy channel is DEAD`() = runTest {
        // The exact #927 busy case, but as a NETWORK-TRANSITION probe: the
        // refresh-client reply did NOT come back and only stale (old-socket) reader
        // activity is present. The steady-state probe tolerates it (#927); the
        // transition probe must report DEAD so the restore arm redials the new path.
        val client = FakeProbeClient(bestEffortIsError = true, readerActivityAgeMs = 100)
        assertTrue(
            "the steady-state probe still tolerates the busy channel (#927 preserved)",
            client.probeLiveness(),
        )
        assertFalse(
            "a network-transition probe must require an ANSWERED round-trip and ignore the " +
                "reader-activity fallback — recent bytes crossed the OLD socket (#1193)",
            client.probeLiveness(requireAnsweredRoundTrip = true),
        )
    }

    @Test
    fun `transition probe is ALIVE when the round-trip actually answers`() = runTest {
        // A truly-live new path answers refresh-client fast — the happy reattach that
        // must NOT regress: the transition probe rides through when the round-trip
        // answers, regardless of reader-activity.
        val client = FakeProbeClient(bestEffortIsError = false, readerActivityAgeMs = Long.MAX_VALUE)
        assertTrue(
            "a transition probe with a clean answered round-trip is alive (happy reattach " +
                "preserved)",
            client.probeLiveness(requireAnsweredRoundTrip = true),
        )
    }

    @Test
    fun `transition probe on a disconnected client is DEAD`() = runTest {
        val client = FakeProbeClient(
            bestEffortIsError = false,
            readerActivityAgeMs = 0,
            isDisconnected = true,
        )
        assertFalse(
            "a disconnected client is dead on a transition probe too",
            client.probeLiveness(requireAnsweredRoundTrip = true),
        )
    }
}
