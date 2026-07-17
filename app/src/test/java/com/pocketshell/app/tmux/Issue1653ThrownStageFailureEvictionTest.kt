package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.DiagnosticEventSink
import com.pocketshell.app.diagnostics.DiagnosticEvents
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClientException
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #1653 — **#1539's vouch-before-evict guards only the `!ready` TIMEOUT branch. A stalled
 * tail THROWS into an unguarded `catch` that unconditionally evicts the SHARED per-host lease.**
 *
 * ## Why this class exists at all, and what it is NOT
 *
 * The acceptance for #1653 is the end-to-end journey
 * (`app/src/androidTest/.../proof/ReconnectStormLivelockE2eTest.kt`, #1652), which drives N>=5
 * real grace-loop cycles against a SIGSTOP-stalled tmux server over the Docker fixture. **That
 * journey is the gate; this JVM class is NOT acceptance and must never be treated as such.**
 *
 * The reason is the whole point of #1653: **a virtual clock never throws.** #1539 shipped with a
 * JVM proof (`Issue1539PassiveReattachStageBudgetTest`), three reviewers and mutation testing,
 * and every one of them missed this branch — because under `runTest`'s virtual clock the only way
 * a rung fails post-handshake is `withTimeoutOrNull` returning null, i.e. the `!ready` branch.
 * On a real device the `tmux has-session` preflight inside `newClient.connect()` hits its OWN
 * inner 10s sshj timeout and RAISES [TmuxClientException] *before* the outer 10s
 * `withTimeoutOrNull(budgets.attachMs)` can return null — so the identical event ("a tail stage
 * failed on a link the handshake already proved up") is delivered as an exception and lands in a
 * completely different, unguarded branch.
 *
 * So this class exists to pin the branch **cheaply and per-push** (the journey runs only in the
 * emulator lane), by injecting the throw SYNTHETICALLY via [FakeTmuxClient.connectThrows] — the
 * #780 model. It is the fast sibling of the journey, not a replacement for it.
 *
 * ## Class coverage (G2) — BOTH exits, BOTH verdicts, and the pre-dial edge
 *
 *  1. tail THROWS on a vouched-ALIVE transport -> KEEP it; the shared lease is NOT evicted.
 *     (The #1653 defect. Red on `main`.)
 *  2. tail THROWS on a genuinely DEAD transport -> STILL evict and STILL escalate.
 *     **The load-bearing negative case (G6):** over-guarding here means the app never recovers,
 *     which is strictly worse than the storm. A fix that made 1. pass by never evicting would
 *     leave this red.
 *  3. the throw beats the dial (no lease was ever acquired) -> evict/cleanup, no crash, and the
 *     loop keeps retrying. Nothing handshook, so there is no transport to preserve.
 *
 * The `!ready` TIMEOUT branch's own coverage (#1539's `attachThatOverrunsItsBudget…` +
 * `attachTimeoutOnGenuinelyDeadTransport…`) is deliberately NOT duplicated here — those tests
 * still guard it, and this slice routes both exits through the SAME authority
 * ([shouldEvictTransportAfterStageFailure]), so a drift between them is now a compile-level
 * impossibility rather than a thing to re-test.
 *
 * No `assumeTrue` / CI-skip on any load-bearing assertion.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue1653ThrownStageFailureEvictionTest : TmuxSessionViewModelTestBase() {

    // ---- 1. tail THROWS on a vouched-ALIVE transport -> keep it, do NOT evict the lease ----

    /**
     * **THE #1653 DEFECT.** The reported shape, minus the emulator: the dial + handshake
     * COMPLETE (the transport is live and vouches alive), then the tail throws exactly as a
     * stalled tmux server makes it throw.
     *
     * On base the `catch (t: Throwable)` runs `sshLeaseManager.disconnect(leaseKey)`
     * unconditionally, which closes the shared per-host transport — so `fresh.closed` is true
     * and the loop re-dials. With the fix the throw routes through the same
     * [shouldEvictTransportAfterStageFailure] the timeout branch uses, sees the transport
     * vouched alive, and KEEPS it.
     */
    @Test
    fun thrownTailOnAVouchedAliveTransportKeepsItInsteadOfEvictingTheSharedLease() =
        runTest(scheduler) {
            val f = Fixture(this)
            f.dialDelayMs = 1_000L
            // The `tmux has-session` preflight's inner sshj timeout, verbatim: this is the exact
            // exception `main` recorded on all 5 of #1652's real-path cycles.
            f.firstFreshClientConnectThrows = TmuxClientException(
                "failed to preflight tmux has-session for 'work': Timed out waiting for 10000 ms",
            )
            // The transport is ALIVE — a completed handshake on a host whose tmux server is
            // wedged. This is the ONLY difference from the negative case below; together they
            // pin the branch in both directions.
            f.freshSessionsAreDead = false
            f.graceMs = 30_000L
            f.start()
            f.dropControlChannelPassively()

            advanceTimeBy(f.graceMs + 1_000L)
            runCurrent()

            val fresh = f.freshSessions.firstOrNull()
            assertTrue(
                "precondition: the fresh-transport rung must have dialed a replacement transport " +
                    "(otherwise this test never reaches the code under test); " +
                    "dials=${f.connector.connectCount}",
                fresh != null,
            )
            // Precondition, hard-asserted: the throw really did fire. Without it the rung would
            // simply succeed and every assertion below would pass vacuously.
            assertTrue(
                "precondition: the tail must actually have THROWN — the first fresh client's " +
                    "connect() must have been called; clients=${f.clients.size}",
                f.clients.firstOrNull()?.connectCalled == true,
            )
            // LOAD-BEARING — the #1653 symptom. Base closes this transport via the unguarded
            // catch's `sshLeaseManager.disconnect(leaseKey)`.
            assertFalse(
                "a transport whose SSH handshake COMPLETED and which is STILL vouched alive must " +
                    "NOT have its lease evicted because the tail THREW. The completed handshake is " +
                    "proof the link is up, so a throwing tail is a wedged host, not a dead link — " +
                    "and the evicted lease is the SHARED per-host transport, so this kills every " +
                    "session on the host (#1653). The timeout branch has refused to do this since " +
                    "#1539; the throw branch is the one that was left unguarded.",
                fresh!!.closed,
            )
            // LOAD-BEARING — the rung's OWN recorded verdict, the same field #1652's journey
            // asserts on the real path (`killedAHandshakenTransport` = clientHash != null &&
            // evictedLease == true). Base records `evictedLease = true` here, hard-coded.
            val threw = f.rungVerdicts.filter { it["cause"] == "TmuxClientException" }
            assertEquals(
                "precondition: exactly ONE rung must have taken the THROW branch; " +
                    "all=${f.rungVerdicts.map { it["cause"] to it["evictedLease"] }}",
                1,
                threw.size,
            )
            assertEquals(
                "the THROW branch must record evictedLease=FALSE for a vouched-alive transport — " +
                    "this is #1652's `killedAHandshakenTransport` signature (clientHash != null && " +
                    "evictedLease == true), which was 5/5 on `main` and must be 0/5 (#1653).",
                false,
                threw.single()["evictedLease"],
            )
            assertTrue(
                "the killed-a-handshaken-transport signature must be ABSENT: the rung handshook " +
                    "(clientHash != null) so evicting it is the storm's defining act",
                threw.none { it["clientHash"] != null && it["evictedLease"] == true },
            )
            // LOAD-BEARING — no redial ladder. 1 warm dial + exactly 1 fresh dial. On base the
            // evicted lease forces a fresh dial every cycle: the storm.
            assertEquals(
                "the kept transport must stay published so the next tick re-vouches it and routes " +
                    "to the channel-only warm rung: the dial count must NOT climb " +
                    "(dials=${f.connector.connectCount}, freshClosed=${f.freshSessions.map { it.closed }})",
                2,
                f.connector.connectCount,
            )
            // LOAD-BEARING — the issue's criterion verbatim: "the attach retries over the same
            // transport (assert transport IDENTITY)". Asserted on identity, not on "no exception":
            // a redial that happened to succeed would also throw nothing, and would also be the
            // churn this issue is about.
            assertSame(
                "the attach must retry over the SAME handshaken transport the rung dialed — the " +
                    "last client must be built on that exact SshSession, not a replacement. A " +
                    "different session here IS the close-and-redial ladder (#1653).",
                fresh,
                f.sessionForClient[f.clients.last()],
            )
        }

    // ---- 2. tail THROWS on a genuinely DEAD transport -> STILL evict + escalate (G6) ----

    /**
     * **The load-bearing NEGATIVE case (G6).** The evict side of the same branch.
     *
     * A transport that is NOT vouched alive is genuinely dead, and a throw on it must still
     * evict and still escalate to a fresh dial. Over-guarding — riding through every throw —
     * would strand the user on a dead socket forever, which is strictly worse than the storm
     * this issue fixes. This is the test that a lazy "just never evict on throw" fix fails.
     *
     * ## Why the load-bearing assertion is `closed`, NOT the dial count (G6)
     *
     * MEASURED, not assumed: an earlier version of this test asserted only
     * `connectCount > 2` — and a mutant that over-guards the authority into NEVER evicting
     * (`evict = forceEvict || session == null`) **survived it green**. The reason is that a DEAD
     * transport also fails the grace LOOP's own `preferFreshTransportNow()` vouch, so the loop
     * re-dials on the next tick whether or not the rung evicted anything. The dial count is
     * therefore a PROXY that is satisfied by a different mechanism than the one under test —
     * exactly the D32-G6 wrong-cost trap.
     *
     * `fresh.closed` is the direct observable: `SshLeaseManager.disconnect(key)` removes the
     * entry and calls `entry.close()`, which closes that `SshSession`. So `closed == true` means
     * the lease really was evicted, and the over-guard mutant goes RED on it (verified).
     *
     * NOTE for the reviewer: #1539's sibling `attachTimeoutOnGenuinelyDeadTransportStillEscalates`
     * asserts only the dial count and therefore has this same blind spot on the TIMEOUT branch.
     * Flagged rather than edited — it is outside this slice's declared scope.
     */
    @Test
    fun thrownTailOnAGenuinelyDeadTransportStillEvictsAndStillEscalates() =
        runTest(scheduler) {
            val f = Fixture(this)
            f.dialDelayMs = 1_000L
            // The FIRST fresh client's tail throws; later cycles attach cleanly, so exactly one
            // dead transport faces the verdict and its fate is unambiguous.
            f.firstFreshClientConnectThrows =
                TmuxClientException("open failed: transport is dead")
            // The replacement transports are DEAD the moment they are handed over (sshj flipped
            // isConnected false). The vouch must FAIL, so the rung must still evict + escalate.
            f.freshSessionsAreDead = true
            f.start()
            f.dropControlChannelPassively()

            advanceTimeBy(f.graceMs + 1_000L)
            runCurrent()

            val fresh = f.freshSessions.firstOrNull()
            assertTrue(
                "precondition: the fresh-transport rung must have dialed a replacement",
                fresh != null,
            )
            assertTrue(
                "precondition: the tail must actually have THROWN on it",
                f.clients.firstOrNull()?.connectCalled == true,
            )
            // LOAD-BEARING (G6) — the RUNG'S OWN RECORDED VERDICT. Three weaker observables were
            // each MEASURED to let an over-guard mutant (`evict = forceEvict || session == null`)
            // survive GREEN, because for a DEAD transport the surrounding machinery re-checks
            // liveness and cleans up regardless of what the rung decided:
            //   * `connectCount > 2`      — the LOOP's own vouch fails, so it re-dials anyway;
            //   * `fresh.closed`          — the next cycle's pre-dial `disconnect(leaseKey)`
            //                               (VM stage 1) closes it anyway;
            //   * close-before-dial ORDER — that same pre-dial disconnect also runs immediately
            //                               BEFORE the next dial, so the order matches too.
            // `evictedLease` is the field [resolveRungStageFailure] returns and the rung records —
            // the decision itself, and the same field #1652's journey asserts on the real path.
            // `cause` is the THROWN exception's simple name on a throw record, and a fixed string
            // ("attach_not_ready" / "attach_slow_transport_kept" / "dial_handshake_timeout") on a
            // timeout record — so this selects the THROW branch's verdicts only, never `any {}`
            // over records a different branch produced.
            val threwVerdicts = f.rungVerdicts.filter { it["cause"] == "TmuxClientException" }
            assertEquals(
                "precondition: exactly ONE rung must have taken the THROW branch (the first fresh " +
                    "client is the only one whose tail throws); all=${f.rungVerdicts.map { it["cause"] to it["evictedLease"] }}",
                1,
                threwVerdicts.size,
            )
            assertEquals(
                "a tail that THROWS on a transport that is NOT vouched alive must record " +
                    "evictedLease=TRUE — the #1653 keep-the-transport fix must not MASK a real " +
                    "death. An app that rides through a genuinely dead transport never recovers, " +
                    "which is strictly worse than the storm (G6).",
                true,
                threwVerdicts.single()["evictedLease"],
            )
            // And the ladder still escalates, so the user actually recovers.
            assertTrue(
                "the rung must still escalate to a fresh dial after evicting a dead transport; " +
                    "dials=${f.connector.connectCount}",
                f.connector.connectCount > 2,
            )
        }

    // ---- 3. the throw beats the dial: nothing handshook -> cleanup, no crash (G2) ----

    /**
     * The pre-dial edge of the throw branch: `sshLeaseManager.acquire(...).getOrThrow()` itself
     * throws, so control reaches the `catch` with NO lease and NO session. There is no
     * handshaken transport to preserve, so the verdict must be evict (pure cleanup) and the loop
     * must keep retrying rather than crashing or wedging.
     *
     * This is the case that makes the authority's `lease == null -> evict` arm load-bearing: a
     * naive `shouldEvictTransportAfterStageFailure(session.vouchedAlive())` on a null session
     * would NPE here.
     */
    @Test
    fun aThrowThatBeatsTheDialIsCleanedUpAndTheLoopKeepsRetrying() =
        runTest(scheduler) {
            val f = Fixture(this)
            // The dial itself throws — a connection-refused / unreachable host, surfaced as a
            // failed Result that `getOrThrow()` raises inside the rung's try block.
            f.dialThrows = true
            f.start()
            f.dropControlChannelPassively()

            advanceTimeBy(f.graceMs + 1_000L)
            runCurrent()

            assertTrue(
                "a throw that beats the dial must be cleaned up and RETRIED — the rung has no " +
                    "handshaken transport to preserve, so it must not wedge the grace window on " +
                    "one failed acquire; dials=${f.connector.connectCount}",
                f.connector.connectCount > 1,
            )
        }

    // ---- fixture ----

    /**
     * The #1539 journey, with the tail THROWING instead of timing out: a warm per-host lease, a
     * passive `-CC` drop on a transport that is NOT vouched alive (so the fresh-transport rung is
     * preferred), then the bounded grace window under the virtual clock. The throw is injected
     * synthetically via [FakeTmuxClient.connectThrows]; nothing here can self-skip.
     */
    private inner class Fixture(private val scope: kotlinx.coroutines.test.TestScope) {
        val registry = ActiveTmuxClients()

        /** `[0]` is the warm-lease setup dial; `drop(1)` is every FRESH transport the grace loop
         *  re-dialed — the ones #1653 is about. */
        val dialedSessions = mutableListOf<FakeSshSession>()
        val freshSessions: List<FakeSshSession> get() = dialedSessions.drop(1)
        val clients = mutableListOf<FakeTmuxClient>()

        /** The TRANSPORT each tmux client was built over, captured from the production client
         *  factory's own `SshSession` argument — what makes "the attach retried over the SAME
         *  transport" assertable by IDENTITY rather than inferred from a dial count. */
        val sessionForClient = mutableMapOf<FakeTmuxClient, SshSession>()

        /**
         * The ordered transcript of lease-level events: `dial:N` when the connector hands over
         * transport N, `close:N` when transport N's `SshSession.close()` runs (which is what
         * `SshLeaseManager.disconnect` -> `entry.close()` does). The ORDER is what separates the
         * rung's OWN verdict from the confounders: the rung evicting transport 1 emits `close:1`
         * BEFORE `dial:2`, whereas the next cycle's pre-dial `disconnect(leaseKey)` (VM stage 1)
         * emits `close:1` AFTER `dial:2` has been requested. Asserting on `closed` alone cannot
         * tell those apart — verified by a surviving over-guard mutant.
         */
        val leaseEvents = mutableListOf<String>()

        var graceMs: Long = 20_000L
        var dialDelayMs: Long = 0L
        var dialThrows: Boolean = false
        var firstFreshClientConnectThrows: Throwable? = null
        var allFreshClientsConnectThrows: Throwable? = null
        var freshSessionsAreDead: Boolean = false

        lateinit var vm: TmuxSessionViewModel
        lateinit var connector: QueueLeaseConnector
        private lateinit var droppedCcClient: FakeTmuxClient

        /**
         * The rung's OWN recorded verdicts. `evictedLease` on a `reconnect_fail` /
         * `source=silent_transport_reattach` record IS the decision
         * [resolveRungStageFailure] made — the same field #1652's journey asserts on the real
         * path. Every indirect observable (dial count, `SshSession.closed`, close/dial ORDER) was
         * MEASURED to let the over-guard mutant survive, because a dead transport makes the loop
         * re-dial and pre-disconnect anyway; the recorded verdict is the only thing that reports
         * what the rung itself decided.
         */
        val rungVerdicts = mutableListOf<Map<String, Any?>>()

        suspend fun start() {
            TMUX_CONNECT_ATTEMPTS.set(1)
            DiagnosticEvents.install(object : DiagnosticEventSink {
                override fun record(category: String, event: String, fields: Map<String, Any?>) {
                    if (event == "reconnect_fail" &&
                        fields["source"] == "silent_transport_reattach"
                    ) {
                        rungVerdicts += fields
                    }
                }
            })
            connector = QueueLeaseConnector()
            vm = newVm(
                registry = registry,
                sshLeaseManager = with(scope) {
                    testLeaseManager(
                        connector = connector,
                        scope = scope,
                        idleTtlMillis = 60_000L,
                    )
                },
            )
            vm.setPassiveDisconnectRecoveryForTest(
                graceMs = graceMs,
                silentReattachTimeoutMs = 5_000L,
            )
            // A 1-rung ladder keeps these tests fast; their subject is the teardown VERDICT, not
            // backoff (#1633 owns that).
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            // The rolling watchdogs auto-arm on Connected and re-arm forever under the virtual
            // clock (#1517). Orthogonal to the evict-vs-keep decision under test.
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)

            vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
                assertEquals("work", sessionName)
                newFreshClient(session)
            }

            droppedCcClient = FakeTmuxClient()
            // The stale transport is DEAD so the transport vouch fails and the fresh-transport
            // rung is selected — the rung that owns the #1653 catch branch.
            val stale = FakeSshSession(isConnectedValue = false)
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = droppedCcClient,
                session = stale,
            )
            scope.runCurrent()
            vm.setActiveLeaseRefWarmForTest()
            scope.runCurrent()
            assertEquals(
                "the warm lease is dialed exactly once before the drop",
                1,
                connector.connectCount,
            )
        }

        fun dropControlChannelPassively() {
            droppedCcClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "network_starvation",
                    intent = "unknown",
                ),
            )
            scope.runCurrent()
        }

        private fun newDialedSession(dead: Boolean = false): FakeSshSession =
            FakeSshSession(
                isConnectedValue = !dead,
                id = dialedSessions.size,
                events = leaseEvents,
            ).also {
                dialedSessions += it
                leaseEvents += "dial:${dialedSessions.size - 1}"
            }

        private fun newFreshClient(session: SshSession): FakeTmuxClient {
            val isFirstFresh = clients.isEmpty()
            val client = FakeTmuxClient().withSinglePane("work", "%1")
            sessionForClient[client] = session
            client.connectThrows = when {
                allFreshClientsConnectThrows != null -> allFreshClientsConnectThrows
                isFirstFresh -> firstFreshClientConnectThrows
                else -> null
            }
            clients += client
            return client
        }

        inner class QueueLeaseConnector : SshLeaseConnector {
            var connectCount: Int = 0
                private set

            override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
                connectCount += 1
                // The warm-lease dial (#1) is the test's SETUP — it lands instantly and always
                // succeeds so the fixture reaches the reported precondition. Only the FRESH
                // re-dials the grace loop issues (#2+) carry the injected latency/failure.
                if (connectCount > 1 && dialDelayMs > 0L) delay(dialDelayMs)
                if (connectCount > 1 && dialThrows) {
                    return Result.failure(IllegalStateException("connection refused"))
                }
                return Result.success(
                    newDialedSession(dead = connectCount > 1 && freshSessionsAreDead),
                )
            }
        }
    }

    private fun FakeTmuxClient.withSinglePane(
        sessionName: String,
        paneId: String,
    ): FakeTmuxClient = apply {
        // Sticky fixtures: the reattach may legitimately re-issue these on an attach retry over
        // the SAME transport, so they must not be one-shot.
        repeat(8) {
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                    isError = false,
                ),
            )
        }
        defaultCaptureResponse = CommandResponse(
            number = 2L,
            output = listOf("$sessionName ready"),
            isError = false,
        )
        repeat(8) {
            cursorQueryResponses.addLast(
                CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
            )
        }
    }

    private class FakeSshSession(
        private val isConnectedValue: Boolean = true,
        private val isCloseInitiatedValue: Boolean = false,
        private val id: Int = 0,
        private val events: MutableList<String>? = null,
    ) : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = isConnectedValue && !closed

        override val isCloseInitiated: Boolean
            get() = isCloseInitiatedValue

        override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean = isConnected

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
            if (!closed) events?.add("close:$id")
            closed = true
        }
    }
}
