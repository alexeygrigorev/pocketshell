package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshSessionCloseCause
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #928 Slice 1b (T6 from the definitive connection-stability audit) — the
 * keepalive-death redial must be AMORTIZED across episodes, not fixed-cadence.
 *
 * ## The reported defect (reproduced RED on base)
 *
 * The per-host idle flap: an idle host's NAT mapping dies, the transport
 * keepalive declares the peer dead (`SshSessionCloseCause.KeepaliveDead`), the
 * `-CC` reader EOFs, and `silentReattachWithinPassiveGrace` re-dials a fresh
 * transport IMMEDIATELY — full reseed, visible window reload. The fresh idle
 * socket then dies again ~2 minutes later, forever. The #1522 debouncer/backoff
 * amortizes only the bare-`NetworkLost` band arm; the keepalive-death recovery
 * path (reader-EOF → passive disconnect → silent reattach) had NO debounce and
 * NO cross-episode amortization, so a flapping host reloaded every ~2min at a
 * FIXED cadence with zero widening.
 *
 * ## The fix these tests pin (GREEN)
 *
 * A per-host [com.pocketshell.app.tmux.connection.KeepaliveDeathRedialAmortizer]
 * (the exact `NetworkLossBandDebouncer` shape from #1522) consulted at the top
 * of the silent-reattach grace job when the episode's close cause is
 * `KeepaliveDead`:
 *
 *  - the FIRST episode heals instantly (today's behaviour — a one-off death
 *    stays a seamless recovery);
 *  - the Nth-in-a-row episode waits progressively longer (the connection's own
 *    auto-reconnect ladder) under the honest calm band before re-dialing;
 *  - a sustained quiet period resets the backoff to instant;
 *  - a MANUAL reconnect (user action) bypasses the wait entirely (T8);
 *  - a NON-keepalive drop (plain reader EOF) is never amortized — the gate is
 *    scoped to the keepalive-death class only.
 *
 * Deterministic: the whole journey runs on the shared virtual clock
 * ([TmuxSessionViewModelTestBase.scheduler]); the amortizer waits are asserted
 * by advancing EXACTLY up to (and then past) each expected grace. No
 * `advanceUntilIdle()` between episodes — it would fast-forward the quiet-reset
 * window and erase the cross-episode state under test.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class TmuxSessionKeepaliveDeathAmortizationTest : TmuxSessionViewModelTestBase() {

    private companion object {
        /** The amortization ladder (the production auto-reconnect ladder shape). */
        val LADDER_MS = listOf(0L, 1_000L, 2_000L, 5_000L)

        /** Shortened quiet-reset window for the deterministic virtual-clock pin. */
        const val QUIET_RESET_MS = 10_000L
    }

    private class Rig(
        val vm: TmuxSessionViewModel,
        val registry: ActiveTmuxClients,
        val connector: QueueLeaseConnector,
        val sessions: List<FakeSshSession>,
        val initialSession: FakeSshSession,
        val initialClient: FakeTmuxClient,
        val replacementClients: List<FakeTmuxClient>,
    ) {
        /** The tmux client currently attached after [recovered] recoveries. */
        fun clientAfterRecoveries(recoveries: Int): FakeTmuxClient =
            if (recoveries == 0) initialClient else replacementClients[recoveries - 1]

        /** The SSH session currently held after [recoveries] recoveries. */
        fun sessionAfterRecoveries(recoveries: Int): FakeSshSession =
            if (recoveries == 0) initialSession else sessions[recoveries - 1]
    }

    private fun TestScope.buildRig(): Rig {
        val registry = ActiveTmuxClients()
        val sessions = List(4) { FakeSshSession() }
        val connector = QueueLeaseConnector(*sessions.toTypedArray())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(
                connector = connector,
                scope = this,
                idleTtlMillis = 60_000L,
            ),
        )
        vm.setPassiveDisconnectRecoveryForTest(
            graceMs = 60_000L,
            silentReattachTimeoutMs = 60_000L,
            keepaliveDeathQuietResetMs = QUIET_RESET_MS,
        )
        vm.setAutoReconnectDelaysForTest(LADDER_MS)
        val replacementClients = List(4) { i ->
            FakeTmuxClient().withSinglePane("work", "%${i + 1}")
        }
        val replacementQueue = ArrayDeque(replacementClients)
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            replacementQueue.removeFirstOrNull() ?: error("unexpected tmux client factory call")
        }
        val initialSession = FakeSshSession()
        val initialClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = initialClient,
            session = initialSession,
        )
        runCurrent()
        return Rig(
            vm = vm,
            registry = registry,
            connector = connector,
            sessions = sessions,
            initialSession = initialSession,
            initialClient = initialClient,
            replacementClients = replacementClients,
        )
    }

    /**
     * One keepalive-death episode, exactly as production produces it: the
     * transport keepalive stamps [SshSessionCloseCause.KeepaliveDead] BEFORE
     * closing the dead transport (#969), then the `-CC` reader EOFs.
     */
    private fun killByKeepalive(session: FakeSshSession, client: FakeTmuxClient) {
        session.closeCauseValue = SshSessionCloseCause.KeepaliveDead
        session.closed = true
        client.markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ReaderEof,
                source = "eof",
                intent = "unknown",
            ),
        )
    }

    private fun TestScope.awaitConnected(rig: Rig) {
        awaitCondition {
            rig.vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected
        }
    }

    // ---- 1. The core red→green: episodes widen instead of reloading on a fixed cadence ----

    @Test
    fun consecutiveKeepaliveDeathEpisodesRedialWithWideningGraceNotFixedCadence() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val rig = buildRig()

            // Episode 1: the FIRST keepalive death heals instantly (today's
            // seamless one-off recovery must be preserved).
            killByKeepalive(rig.sessionAfterRecoveries(0), rig.clientAfterRecoveries(0))
            runCurrent()
            assertEquals(
                "the FIRST keepalive-death episode must re-dial instantly (no amortizer wait)",
                1,
                rig.connector.connectCount,
            )
            awaitConnected(rig)

            // Episode 2: the SECOND consecutive death must NOT re-dial instantly —
            // this is the un-amortized fixed-cadence reload (RED on base).
            killByKeepalive(rig.sessionAfterRecoveries(1), rig.clientAfterRecoveries(1))
            runCurrent()
            assertEquals(
                "issue #928 (T6) — the 2nd consecutive keepalive-death episode must WAIT " +
                    "the amortized grace (${LADDER_MS[1]}ms) before re-dialing. An instant " +
                    "re-dial is the un-amortized fixed-cadence reload: a flapping idle host " +
                    "reloads the window every ~2min forever with zero widening.",
                1,
                rig.connector.connectCount,
            )
            advanceTimeBy(LADDER_MS[1] - 1)
            runCurrent()
            assertEquals(
                "the 2nd episode's re-dial must still be held 1ms before its amortized grace",
                1,
                rig.connector.connectCount,
            )
            advanceTimeBy(1)
            runCurrent()
            assertEquals(
                "the 2nd episode must re-dial once its amortized grace elapses (the host " +
                    "still auto-recovers — amortized, not abandoned)",
                2,
                rig.connector.connectCount,
            )
            awaitConnected(rig)

            // Episode 3: the grace must WIDEN (next ladder rung), not repeat.
            killByKeepalive(rig.sessionAfterRecoveries(2), rig.clientAfterRecoveries(2))
            runCurrent()
            advanceTimeBy(LADDER_MS[2] - 1)
            runCurrent()
            assertEquals(
                "the 3rd consecutive episode's grace must WIDEN to the next ladder rung " +
                    "(${LADDER_MS[2]}ms) — strictly widening cadence under a persistent flap",
                2,
                rig.connector.connectCount,
            )
            advanceTimeBy(1)
            runCurrent()
            assertEquals(3, rig.connector.connectCount)
            awaitConnected(rig)

            // The amortization is observable in field logs (the audit's exit
            // criterion): episode + graceMs recorded per episode.
            val episodes = diagnostics.eventsNamed("keepalive_death_redial_amortized")
            assertEquals(
                "every keepalive-death episode must record its amortization decision",
                listOf(0L, LADDER_MS[1], LADDER_MS[2]),
                episodes.map { it.fields["graceMs"] },
            )
            assertEquals(listOf(1, 2, 3), episodes.map { it.fields["episode"] })
        } finally {
            diagnostics.close()
        }
    }

    // ---- 2. Quiet reset: a genuinely recovered host returns to instant healing ----

    @Test
    fun sustainedQuietPeriodResetsTheKeepaliveDeathBackoffToInstant() = runTest(scheduler) {
        val rig = buildRig()

        // Two episodes escalate the backoff...
        killByKeepalive(rig.sessionAfterRecoveries(0), rig.clientAfterRecoveries(0))
        runCurrent()
        awaitConnected(rig)
        killByKeepalive(rig.sessionAfterRecoveries(1), rig.clientAfterRecoveries(1))
        runCurrent()
        advanceTimeBy(LADDER_MS[1])
        runCurrent()
        assertEquals(2, rig.connector.connectCount)
        awaitConnected(rig)

        // ...then the host stays QUIET past the reset window (no further deaths).
        advanceTimeBy(QUIET_RESET_MS + 1)
        runCurrent()

        // The next death is a FRESH first episode: instant heal again.
        killByKeepalive(rig.sessionAfterRecoveries(2), rig.clientAfterRecoveries(2))
        runCurrent()
        assertEquals(
            "after a sustained quiet period the keepalive-death backoff must RESET — " +
                "the next episode heals instantly like a fresh first death",
            3,
            rig.connector.connectCount,
        )
        awaitConnected(rig)
    }

    // ---- 3. T8 guard: a MANUAL reconnect bypasses the amortizer wait ----

    @Test
    fun manualReconnectBypassesTheKeepaliveDeathAmortizerWait() = runTest(scheduler) {
        val rig = buildRig()

        killByKeepalive(rig.sessionAfterRecoveries(0), rig.clientAfterRecoveries(0))
        runCurrent()
        assertEquals(1, rig.connector.connectCount)
        awaitConnected(rig)

        // Episode 2 enters its amortized wait (1s)...
        killByKeepalive(rig.sessionAfterRecoveries(1), rig.clientAfterRecoveries(1))
        runCurrent()
        assertEquals("episode 2 must be holding its amortized grace", 1, rig.connector.connectCount)

        // ...and the USER taps Reconnect: a deliberate action dials NOW, with no
        // virtual time advanced past the pending grace (the T8 bypass).
        assertTrue("manual reconnect must be accepted", rig.vm.reconnect())
        awaitCondition { rig.connector.connectCount >= 2 }
        assertEquals(
            "a manual reconnect must dial immediately — the amortizer only delays the " +
                "AUTOMATIC keepalive-death redial, never a deliberate user action",
            2,
            rig.connector.connectCount,
        )
        awaitConnected(rig)
    }

    // ---- 4. Class guard: a NON-keepalive drop is never amortized ----

    @Test
    fun plainReaderEofDropIsNeverAmortizedEvenAfterKeepaliveEpisodes() = runTest(scheduler) {
        val rig = buildRig()

        // Elevate the keepalive-death backoff with two episodes...
        killByKeepalive(rig.sessionAfterRecoveries(0), rig.clientAfterRecoveries(0))
        runCurrent()
        awaitConnected(rig)
        killByKeepalive(rig.sessionAfterRecoveries(1), rig.clientAfterRecoveries(1))
        runCurrent()
        advanceTimeBy(LADDER_MS[1])
        runCurrent()
        assertEquals(2, rig.connector.connectCount)
        awaitConnected(rig)

        // ...then a PLAIN reader-EOF drop (closeCause stays Unknown — not a
        // keepalive death). It must re-dial instantly: the amortizer is scoped
        // to the keepalive-death class only.
        val session = rig.sessionAfterRecoveries(2)
        session.closed = true
        rig.clientAfterRecoveries(2).markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ReaderEof,
                source = "eof",
                intent = "unknown",
            ),
        )
        runCurrent()
        assertEquals(
            "a NON-keepalive transport drop (plain reader EOF) must keep today's instant " +
                "silent reattach even while the keepalive-death backoff is elevated — the " +
                "amortizer must never delay recovery for other drop classes",
            3,
            rig.connector.connectCount,
        )
        awaitConnected(rig)
    }

    // ---- test doubles (the TmuxSessionViewModelPassiveReconnectTest shapes) ----

    private class QueueLeaseConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected lease connect $connectCount for ${target.leaseKey}")
            connectCount += 1
            return Result.success(next)
        }
    }

    private fun FakeTmuxClient.withSinglePane(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        responses.addLast(
            CommandResponse(
                number = 1L,
                output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                isError = false,
            ),
        )
        capturePaneResponses.addLast(
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false),
        )
        cursorQueryResponses.addLast(
            CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
        )
    }

    private class FakeSshSession : SshSession {
        @Volatile
        var closed: Boolean = false

        /**
         * The #969 close attribution: the transport keepalive stamps
         * [SshSessionCloseCause.KeepaliveDead] BEFORE closing the dead
         * transport; a plain EOF/teardown leaves it [SshSessionCloseCause.Unknown].
         */
        @Volatile
        var closeCauseValue: SshSessionCloseCause = SshSessionCloseCause.Unknown

        override val closeCause: SshSessionCloseCause
            get() = closeCauseValue

        override val isConnected: Boolean
            get() = !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job = Job()

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = throw NotImplementedError("not needed")

        override fun startShell(): SshShell = throw NotImplementedError("not needed")

        override suspend fun uploadFile(file: File, remotePath: String): String = remotePath

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = remotePath

        override fun close() {
            closed = true
        }
    }
}
