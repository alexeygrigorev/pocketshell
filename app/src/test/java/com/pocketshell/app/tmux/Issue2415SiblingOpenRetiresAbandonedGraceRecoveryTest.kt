package com.pocketshell.app.tmux

import com.pocketshell.app.diagnostics.RecordedDiagnosticEvent
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import com.pocketshell.core.tmux.TmuxSessionNotFoundException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.InputStream

/**
 * Issue #2415 — an ABANDONED session's within-grace recovery survives a sibling open.
 *
 * ## The reported defect (CI run 33255395435, shard 2, journey
 * `ColdRestoreGoneSessionNoResurrectE2eTest > dismissTapOnColdRestoreStaleDialogLandsOnHostSessionTree`)
 *
 * `claude-main` is killed. The user dismisses the "session ended" dialog and taps the LIVE
 * sibling `codex`. `codex` reaches `tmux-control-mode-ready … paneCount=1` — and is then
 * killed by `claude-main`'s own recovery:
 *
 * ```
 * 13:55:37.212  tmux-foreground-heal-within-grace … session=claude-main    <- the 60s bounded loop starts
 * 13:55:40.188  app-navigator-current destination=TmuxSession(session=codex)  <- the user leaves claude-main
 * 13:55:41.883  tmux-passive-disconnect-silent-transport-reattach-failed … session=claude-main
 * 13:55:41.978  tmux-control-mode-ready … session=codex paneCount=1
 * 13:55:41.980  transport.down … reason=ExplicitDisconnect                 <- claude-main's loop tore it down
 * 13:55:41.982  tmux-connect-failed … session=codex cause=TmuxClientException:
 *               control channel closed before the connect could reveal session `codex` as live
 * …            ~190 more claude-main iterations at ~270ms, each dialing + closing the shared transport
 * 13:56:37.235  tmux-connect-attempt attempt=12 session=claude-main trigger=auto-reconnect generation=4
 *               ^ exactly 60s after the heal started: the grace window elapsed and the loop handed off
 *                 scheduleAutoReconnect(target = claude-main) — for a session the user LEFT
 * 13:56:38.522  tmux-restore-session-gone session=claude-main → Failed(Session “claude-main” has ended.)
 * ```
 *
 * ## Root cause
 *
 * `TmuxSessionViewModel.connect()` cancels every competing recovery owner before accepting a
 * new connect — `passiveDisconnectGraceJob`, `autoReconnectJob`, `pausedAutoReconnect`,
 * `lifecycleReattachNetworkCoalesce` — but NOT the [com.pocketshell.app.tmux.connection.GraceEffects]
 * within-grace recovery owner. `GraceEffects.retireForSupersedingOwner()` already exists and does
 * exactly the right thing, but #822 wired it to the manual-Reconnect body ONLY. Opening a
 * DIFFERENT session is just as much a superseding owner, and was never wired.
 *
 * ## Class coverage (G2)
 *
 *  1. sibling session on the SAME host supersedes the abandoned recovery — the headline.
 *  2. a session on a DIFFERENT host supersedes it too (the rule is session identity, not host).
 *  3. a SAME-session re-entry must NOT retire its own recovery — the #1538/#754/#1954
 *     within-grace ride-through must not regress (the non-masking half).
 *
 * The gone-session state is injected synthetically (`connectThrows =
 * TmuxSessionNotFoundException`, the exact throw `RealTmuxClient.connect` raises when its
 * `has-session` preflight reports the session gone), the #780 model — no `assumeTrue`, no skip.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class Issue2415SiblingOpenRetiresAbandonedGraceRecoveryTest : TmuxSessionViewModelTestBase() {

    // ---- 1. sibling session on the same host — the headline reproduction ----

    @Test
    fun openingALiveSiblingRetiresTheAbandonedSessionsWithinGraceRecoveryInsteadOfChurningTheTransport() =
        runTest(scheduler) {
            TMUX_CONNECT_ATTEMPTS.set(1)
            val registry = ActiveTmuxClients()
            val warmSession = FakeSshSession()
            val connector = EndlessLeaseConnector(warmSession)
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(
                    connector = connector,
                    scope = this,
                    idleTtlMillis = GRACE_MS,
                ),
            )
            vm.setPassiveDisconnectRecoveryForTest(graceMs = GRACE_MS, silentReattachTimeoutMs = 100L)
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)

            val factory = GoneSessionClientFactory(goneSession = KILLED, liveSession = SIBLING)
            vm.setTmuxClientFactoryForTest(factory::create)

            val killedClient = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 10L,
                hostName = "docker",
                host = "10.0.2.2",
                port = 2222,
                user = "testuser",
                keyPath = "/keys/a",
                sessionName = KILLED,
                client = killedClient,
                session = warmSession,
            )
            vm.setActiveLeaseRefWarmForTest()
            runCurrent()

            // The `-CC` channel dies while backgrounded (the journey's kill + dismiss), so the drop
            // is DEFERRED to the single grace owner exactly as in production.
            vm.setProcessForegroundForClearedForTest(false)
            killedClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "device_background",
                    intent = "unknown",
                ),
            )
            runCurrent()

            // Foreground return within grace -> `launchForegroundHealWithinGrace` starts the bounded
            // 60s retry loop for the (now GONE) `claude-main`.
            vm.setProcessForegroundForClearedForTest(true)
            vm.onAppForegrounded(resumedWithinGrace = true)
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(
                "the abandoned session's within-grace recovery must actually be running before the " +
                    "sibling open — otherwise this test proves nothing (G6)",
                factory.createdFor(KILLED) > 0,
            )

            val diagnostics = installRecordingDiagnosticSink()
            try {
                // --- The user taps the LIVE sibling `codex` ---
                val createdForKilledBeforeSiblingOpen = factory.createdFor(KILLED)
                val leaseDialsBeforeSiblingOpen = connector.connectCount
                vm.connect(
                    hostId = 10L,
                    hostName = "docker",
                    host = "10.0.2.2",
                    port = 2222,
                    user = "testuser",
                    keyPath = "/keys/a",
                    passphrase = null,
                    sessionName = SIBLING,
                    startDirectory = "/home/testuser",
                    trigger = TmuxConnectTrigger.OpenExisting,
                )
                runCurrent()

                // LOAD-BEARING #0 — the abandoned session's bounded claim must be RETIRED the
                // instant the sibling open is accepted (ownership, the observable the IO counters
                // below cannot distinguish from the superseding connect's own attach), and the
                // breadcrumb must name the superseding session.
                assertFalse(
                    "opening a different session must retire the abandoned bounded within-grace " +
                        "owner immediately, not merely stop its IO (#2415)",
                    vm.withinGraceRecoveryActiveForTest(),
                )
                assertEquals(
                    "the retirement must leave exactly one breadcrumb naming the superseding " +
                        "session; got ${diagnostics.eventsNamed(WITHIN_GRACE_RETIRED_EVENT)}",
                    listOf(SIBLING),
                    diagnostics.eventsNamed(WITHIN_GRACE_RETIRED_EVENT).map { it.fields["session"] },
                )

                // Walk past the whole remaining grace window — on the unfixed build the abandoned
                // loop keeps re-dialing here and then hands off `scheduleAutoReconnect(claude-main)`.
                advanceTimeBy(GRACE_MS + 5_000L)
                runCurrent()

                // LOAD-BEARING #1 — the abandoned session must do NO further recovery IO once the
                // user has opened a different session. On the unfixed build this keeps climbing
                // (~190 iterations in the reported logcat).
                assertEquals(
                    "an abandoned session's within-grace recovery must be RETIRED the moment the " +
                        "user opens a different session — every extra `$KILLED` control client is " +
                        "another dial + `ExplicitDisconnect` teardown of the SHARED per-host " +
                        "transport, which is what killed `$SIBLING`'s reveal (#2415)",
                    createdForKilledBeforeSiblingOpen,
                    factory.createdFor(KILLED),
                )

                // LOAD-BEARING #2 — the reconnect ladder must never be installed for the abandoned
                // session. On the unfixed build the grace window elapses and the heal hands off
                // `scheduleAutoReconnect(target = claude-main)` -> `tmux-connect-attempt
                // session=claude-main trigger=auto-reconnect`.
                assertEquals(
                    "the reconnect ladder must never re-target the session the user LEFT; " +
                        "connect/reconnect starts for `$KILLED` after the sibling open: " +
                        connectStartsFor(diagnostics.events, KILLED),
                    emptyList<String>(),
                    connectStartsFor(diagnostics.events, KILLED),
                )

                // LOAD-BEARING #3 — the user-visible symptom: the screen opened for `codex` must
                // never carry `claude-main`'s identity. This is the exact journey assertion
                // (`lastStatus=Failed(message=Session “claude-main” has ended.)`).
                awaitCondition {
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected
                }
                val status = vm.connectionStatus.value
                assertFalse(
                    "the screen opened for `$SIBLING` must never surface `$KILLED`'s failure; got $status",
                    status.toString().contains(KILLED),
                )
                assertEquals(
                    "the sibling the user opened stays the active session",
                    SIBLING,
                    vm.activeSessionNameForTest(),
                )
                assertTrue(
                    "the sibling open must settle Connected, not be knocked down by the abandoned " +
                        "session's transport churn; got $status",
                    status is TmuxSessionViewModel.ConnectionStatus.Connected,
                )
                assertTrue(
                    "the abandoned recovery must not keep dialing the shared transport after the " +
                        "sibling open (lease dials before=$leaseDialsBeforeSiblingOpen, " +
                        "after=${connector.connectCount})",
                    connector.connectCount - leaseDialsBeforeSiblingOpen <= 1,
                )
            } finally {
                diagnostics.close()
            }
        }

    // ---- 2. a DIFFERENT HOST open supersedes it too (the rule is session identity) ----

    @Test
    fun openingASessionOnAnotherHostAlsoRetiresTheAbandonedSessionsWithinGraceRecovery() =
        runTest(scheduler) {
            TMUX_CONNECT_ATTEMPTS.set(1)
            val registry = ActiveTmuxClients()
            val warmSession = FakeSshSession()
            val connector = EndlessLeaseConnector(warmSession)
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(
                    connector = connector,
                    scope = this,
                    idleTtlMillis = GRACE_MS,
                ),
            )
            vm.setPassiveDisconnectRecoveryForTest(graceMs = GRACE_MS, silentReattachTimeoutMs = 100L)
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)

            val factory = GoneSessionClientFactory(goneSession = KILLED, liveSession = SIBLING)
            vm.setTmuxClientFactoryForTest(factory::create)

            val killedClient = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 10L,
                hostName = "docker",
                host = "10.0.2.2",
                port = 2222,
                user = "testuser",
                keyPath = "/keys/a",
                sessionName = KILLED,
                client = killedClient,
                session = warmSession,
            )
            vm.setActiveLeaseRefWarmForTest()
            runCurrent()

            vm.setProcessForegroundForClearedForTest(false)
            killedClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "device_background",
                    intent = "unknown",
                ),
            )
            runCurrent()
            vm.setProcessForegroundForClearedForTest(true)
            vm.onAppForegrounded(resumedWithinGrace = true)
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            assertTrue(
                "the abandoned recovery must be running before the other-host open (G6)",
                factory.createdFor(KILLED) > 0,
            )

            val diagnostics = installRecordingDiagnosticSink()
            try {
                val createdForKilledBeforeOpen = factory.createdFor(KILLED)
                vm.connect(
                    hostId = 11L,
                    hostName = "other",
                    host = "10.0.2.3",
                    port = 2222,
                    user = "testuser",
                    keyPath = "/keys/b",
                    passphrase = null,
                    sessionName = SIBLING,
                    startDirectory = "/home/testuser",
                    trigger = TmuxConnectTrigger.OpenExisting,
                )
                runCurrent()

                assertFalse(
                    "an open on ANOTHER host must retire the abandoned bounded within-grace owner " +
                        "too — the rule is session identity, not host (#2415 G2)",
                    vm.withinGraceRecoveryActiveForTest(),
                )
                assertEquals(
                    "the cross-host retirement must leave the breadcrumb too; got " +
                        diagnostics.eventsNamed(WITHIN_GRACE_RETIRED_EVENT),
                    listOf(SIBLING),
                    diagnostics.eventsNamed(WITHIN_GRACE_RETIRED_EVENT).map { it.fields["session"] },
                )

                advanceTimeBy(GRACE_MS + 5_000L)
                runCurrent()

                assertEquals(
                    "opening a session on ANOTHER host supersedes the abandoned within-grace " +
                        "recovery just as a same-host sibling does (#2415 G2)",
                    createdForKilledBeforeOpen,
                    factory.createdFor(KILLED),
                )
                assertEquals(
                    "the ladder must not re-target the abandoned session across a host switch; got " +
                        connectStartsFor(diagnostics.events, KILLED),
                    emptyList<String>(),
                    connectStartsFor(diagnostics.events, KILLED),
                )
                awaitCondition {
                    vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected
                }
                assertFalse(
                    "the newly opened session's screen must never surface `$KILLED`'s failure; " +
                        "got ${vm.connectionStatus.value}",
                    vm.connectionStatus.value.toString().contains(KILLED),
                )
            } finally {
                diagnostics.close()
            }
        }

    // ---- 3. a SAME-session re-entry must NOT retire its own recovery (non-masking, G2) ----

    @Test
    fun sameSessionReEntryDoesNotRetireItsOwnWithinGraceRecovery() =
        runTest(scheduler) {
            TMUX_CONNECT_ATTEMPTS.set(1)
            val registry = ActiveTmuxClients()
            val warmSession = FakeSshSession()
            val connector = EndlessLeaseConnector(warmSession)
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(
                    connector = connector,
                    scope = this,
                    idleTtlMillis = GRACE_MS,
                ),
            )
            vm.setPassiveDisconnectRecoveryForTest(graceMs = GRACE_MS, silentReattachTimeoutMs = 100L)
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)

            // `work` NEVER recovers here (every replacement client fails its connect), so the
            // bounded owner has to stay claimed for the recovery to keep retrying at all.
            val factory = GoneSessionClientFactory(goneSession = WORK, liveSession = "unused")
            vm.setTmuxClientFactoryForTest(factory::create)

            val droppedClient = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = WORK,
                client = droppedClient,
                session = warmSession,
            )
            vm.setActiveLeaseRefWarmForTest()
            runCurrent()

            vm.setProcessForegroundForClearedForTest(false)
            droppedClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "device_background",
                    intent = "unknown",
                ),
            )
            runCurrent()
            vm.setProcessForegroundForClearedForTest(true)
            vm.onAppForegrounded(resumedWithinGrace = true)
            runCurrent()
            advanceTimeBy(1_000L)
            runCurrent()
            val createdBefore = factory.createdFor(WORK)
            assertTrue("the within-grace recovery must be running (G6)", createdBefore > 0)
            assertTrue(
                "precondition: the bounded within-grace owner holds the claim before the re-entry",
                vm.withinGraceRecoveryActiveForTest(),
            )

            val diagnostics = installRecordingDiagnosticSink()
            try {
                // A SAME-session re-entry (the within-grace foreground path re-asserting its own
                // target) must leave its recovery owner alone — the #1538/#754/#1954 ride-through.
                vm.connect(
                    hostId = 7L,
                    hostName = "alpha",
                    host = "alpha.example",
                    port = 22,
                    user = "alex",
                    keyPath = "/keys/a",
                    passphrase = null,
                    sessionName = WORK,
                    startDirectory = null,
                    trigger = TmuxConnectTrigger.LifecycleReattach,
                )
                runCurrent()

                // LOAD-BEARING (the non-masking half of #2415, and the ONLY assertion here that
                // dies when the identity guard inside `retireIfOwnedByOtherSession` is removed):
                // the bounded owner must STILL hold the claim. An unconditional retire flips
                // ownership to Idle here, which is exactly the #1538/#754/#1954 regression — the
                // silent ride-through hold lifts, the loud "Attaching…" band comes back, and the
                // heal loop's tracked coroutines are cancelled mid-window.
                //
                // NOTE (why this is asserted and not just retry progress): `factory.createdFor` and
                // the diagnostics below both keep climbing after an unconditional retire, because
                // the superseding connect performs its OWN attach and the zero-delay auto-reconnect
                // ladder then re-dials — so IO-count assertions alone are satisfied by the mutant.
                // Ownership is the only observable that distinguishes "my claim survived" from
                // "my claim was retired and someone else's IO replaced it".
                assertTrue(
                    "a SAME-session re-entry must NOT retire its own within-grace recovery — the " +
                        "bounded owner must still hold the claim, or the #1538/#754/#1954 " +
                        "ride-through regresses (#2415 G2, non-masking half)",
                    vm.withinGraceRecoveryActiveForTest(),
                )
                assertEquals(
                    "a SAME-session re-entry is NOT a superseding owner, so the #2415 retirement " +
                        "breadcrumb must never fire for it; got " +
                        diagnostics.eventsNamed(WITHIN_GRACE_RETIRED_EVENT),
                    emptyList<RecordedDiagnosticEvent>(),
                    diagnostics.eventsNamed(WITHIN_GRACE_RETIRED_EVENT),
                )

                advanceTimeBy(3_000L)
                runCurrent()

                assertTrue(
                    "the surviving owner must still be retrying its own session — replacement " +
                        "clients for `$WORK` before=$createdBefore after=${factory.createdFor(WORK)}",
                    factory.createdFor(WORK) > createdBefore,
                )
                assertTrue(
                    "the claim must survive the whole re-entry, not just the connect instant",
                    vm.withinGraceRecoveryActiveForTest(),
                )
                assertEquals(
                    "no retirement breadcrumb may appear later in the window either; got " +
                        diagnostics.eventsNamed(WITHIN_GRACE_RETIRED_EVENT),
                    emptyList<RecordedDiagnosticEvent>(),
                    diagnostics.eventsNamed(WITHIN_GRACE_RETIRED_EVENT),
                )
            } finally {
                diagnostics.close()
            }
        }

    // ---- helpers ----

    private fun connectStartsFor(
        events: List<RecordedDiagnosticEvent>,
        sessionName: String,
    ): List<String> =
        events
            .filter { it.name == "connect_start" || it.name == "reconnect_start" }
            .filter { it.fields["session"] == sessionName }
            .map { "${it.name}(trigger=${it.fields["trigger"]}, attempt=${it.fields["attempt"]})" }

    /**
     * The gone-session client factory. Every control client built for [goneSession] fails its
     * `connect()` with the exact [TmuxSessionNotFoundException] `RealTmuxClient.connect` raises
     * when its `has-session` preflight reports the session gone — so the within-grace recovery
     * loop can never succeed and keeps retrying, exactly as in the reported logcat.
     * [liveSession] gets a healthy single-pane client.
     */
    private class GoneSessionClientFactory(
        private val goneSession: String,
        private val liveSession: String,
    ) {
        private val createdBySession = mutableMapOf<String, Int>()

        fun createdFor(sessionName: String): Int = createdBySession[sessionName] ?: 0

        fun create(session: SshSession, sessionName: String, startDirectory: String?): FakeTmuxClient {
            createdBySession[sessionName] = createdFor(sessionName) + 1
            return when (sessionName) {
                goneSession -> FakeTmuxClient().apply {
                    connectThrows = TmuxSessionNotFoundException(goneSession)
                }
                liveSession -> stickySinglePaneClient(sessionName, "%2")
                else -> error("unexpected tmux client for session '$sessionName'")
            }
        }
    }

    /** Hands out a fresh transport for every dial — the abandoned loop must not be able to. */
    private class EndlessLeaseConnector(private val first: SshSession) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = if (connectCount == 0) first else FakeSshSession()
            connectCount += 1
            return Result.success(next)
        }
    }

    private class FakeSshSession(
        isConnectedValue: Boolean = true,
        private val isCloseInitiatedValue: Boolean = false,
    ) : SshSession {
        @Volatile
        private var connected: Boolean = isConnectedValue

        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean
            get() = connected && !closed

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
            closed = true
        }
    }

    private companion object {
        const val KILLED = "claude-main"
        const val SIBLING = "codex"
        const val WORK = "work"

        /** The production bounded window ([PASSIVE_DISCONNECT_GRACE_MS]) the reported loop ran for. */
        const val GRACE_MS = 60_000L

        /** The #2415 retirement breadcrumb — fires only for a genuinely superseding owner. */
        const val WITHIN_GRACE_RETIRED_EVENT = "within_grace_recovery_retired"
    }
}

/**
 * A [FakeTmuxClient] that answers `list-panes` with the same single pane for every reconcile and
 * always seeds a non-blank capture, so the session it stands for can reach (and stay) Connected.
 */
private fun stickySinglePaneClient(sessionName: String, paneId: String): FakeTmuxClient =
    FakeTmuxClient().apply {
        repeat(16) {
            responses.addLast(
                CommandResponse(
                    number = 1L,
                    output = listOf("$paneId\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                    isError = false,
                ),
            )
            cursorQueryResponses.addLast(
                CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
            )
        }
        defaultCaptureResponse =
            CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false)
    }
