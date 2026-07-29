package com.pocketshell.app.tmux

import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseTarget
import com.pocketshell.core.ssh.SshPortForward
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.SshShell
import com.pocketshell.core.tmux.CommandResponse
import com.pocketshell.core.tmux.TmuxClient
import com.pocketshell.core.tmux.TmuxDisconnectEvent
import com.pocketshell.core.tmux.TmuxDisconnectReason
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream

/**
 * Issue #1863 (AC5 / G2) — the reveal guard on the TWO PASSIVE-RECOVERY rungs.
 *
 * `requireControlChannelAliveForReveal` is called at five production sites. Three of
 * them (cold connect, fast switch, network ride-through) are covered by
 * `Issue1863DeadWireAfterCancelledConnectTest` and
 * `TmuxSessionViewModelRestoreRideThroughTest`. The other two —
 * `silentlyReattachAfterPassiveDisconnect` (the channel-only rung over a still-live
 * transport) and `silentlyReconnectTransportAfterPassiveDisconnect` (the
 * fresh-transport rung) — shipped in round 1 with NO triggering test: a mutation
 * deleting either line survived the whole suite. That is a G9 hole, and this file
 * closes it.
 *
 * ## The property, and why it is asserted on the REGISTRY
 *
 * Round 1 placed both guards AFTER `activeTmuxClients.register(...)` /
 * `installLifecycleHooks` / `activeTarget = target`. So a replacement whose `-CC`
 * channel died during its own attach was published to the host registry FIRST and only
 * then rejected — leaving the app's registry pointing at a corpse while the rung
 * reported failure, which the sibling `!ready` early-return in the same function is
 * careful never to do. Round 2 moves both guards ABOVE that registration.
 *
 * The assertion is therefore: after a passive drop whose every replacement dies
 * mid-attach, `ActiveTmuxClients` must never have been re-pointed at a dead
 * replacement. That is red with the guard in its round-1 position (and red with the
 * guard absent entirely), green with it above the registration — and it is the state
 * other surfaces read, not an internal flag.
 *
 * Both rungs are driven through the REAL passive-grace loop
 * (`handlePassiveClientDisconnect` -> the bounded grace window), selected by the
 * transport vouch exactly as production selects them: a vouched-ALIVE stale transport
 * takes the channel-only rung, a DEAD one takes the fresh-transport rung. Nothing here
 * calls the rung directly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Issue1863PassiveRungRevealGuardTest : TmuxSessionViewModelTestBase() {

    @Test
    fun channelOnlyRungMustNotRegisterAReplacementWhoseChannelDiedMidAttach() =
        runTest(scheduler) {
            // A LIVE stale transport => `preferFreshTransportNow()` is false => the grace
            // loop takes `silentlyReattachAfterPassiveDisconnect` (the channel-only rung).
            val f = Fixture(this, staleTransportAlive = true)
            f.start()
            f.dropControlChannelPassively()

            advanceTimeBy(f.graceMs + 1_000L)
            runCurrent()

            f.assertTheRungRanAndNeverRegisteredACorpse()
        }

    @Test
    fun freshTransportRungMustNotRegisterAReplacementWhoseChannelDiedMidAttach() =
        runTest(scheduler) {
            // A DEAD stale transport => the fresh-transport rung
            // (`silentlyReconnectTransportAfterPassiveDisconnect`) is preferred.
            val f = Fixture(this, staleTransportAlive = false)
            f.start()
            f.dropControlChannelPassively()

            advanceTimeBy(f.graceMs + 1_000L)
            runCurrent()

            f.assertTheRungRanAndNeverRegisteredACorpse()
        }

    // ------------------------------------------------------------------ fixture

    private inner class Fixture(
        private val scope: kotlinx.coroutines.test.TestScope,
        private val staleTransportAlive: Boolean,
    ) {
        val registry = ActiveTmuxClients()
        val graceMs: Long = 20_000L

        /** Every replacement the rungs built, in build order. All of them die mid-attach. */
        val replacements = mutableListOf<FakeTmuxClient>()

        lateinit var vm: TmuxSessionViewModel
        private lateinit var staleClient: FakeTmuxClient

        suspend fun start() {
            TMUX_CONNECT_ATTEMPTS.set(1)
            vm = newVm(
                registry = registry,
                sshLeaseManager = with(scope) {
                    testLeaseManager(
                        connector = SshLeaseConnector { Result.success(FakeSshSession()) },
                        scope = scope,
                        idleTtlMillis = 60_000L,
                    )
                },
            )
            vm.setPassiveDisconnectRecoveryForTest(
                graceMs = graceMs,
                silentReattachTimeoutMs = 5_000L,
            )
            vm.setAutoReconnectDelaysForTest(listOf(0L))
            // Orthogonal rolling watchdogs; they re-arm forever under the virtual clock
            // (#1517) and have nothing to do with the reveal guard.
            vm.setStaleRenderWatchdogAutoArmEnabledForTest(false)
            vm.setConnectedBlankWatchdogAutoArmEnabledForTest(false)

            // EVERY replacement dies during its own attach, the #1863 shape: the `-CC`
            // channel is closed by a SELF-INFLICTED `ExplicitClose` (what a cancelled
            // caller's `close()` produces), which the passive-drop classifier ignores —
            // so nothing else recovers it and the rung must reject it itself.
            vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
                newReplacementThatDiesMidAttach(sessionName)
            }

            staleClient = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = HOST_ID,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = staleClient,
                session = FakeSshSession(isConnectedValue = staleTransportAlive),
            )
            scope.runCurrent()
            vm.setActiveLeaseRefWarmForTest()
            scope.runCurrent()
            assertSameClientRegistered(
                staleClient,
                "precondition: the stale client is the registered one before the drop",
            )
        }

        fun dropControlChannelPassively() {
            staleClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "network_starvation",
                    intent = "unknown",
                ),
            )
            scope.runCurrent()
        }

        fun assertTheRungRanAndNeverRegisteredACorpse() {
            // HARD precondition: without this the assertion below would pass vacuously on
            // a fixture where the rung never ran at all.
            assertTrue(
                "precondition: the passive-grace loop must have actually run a recovery rung " +
                    "and built at least one replacement — otherwise the guard under test was " +
                    "never reached. replacements=${replacements.size}",
                replacements.isNotEmpty(),
            )
            assertTrue(
                "precondition: every replacement must have died mid-attach (that is the " +
                    "state under test)",
                replacements.all { it.disconnectedSignal.value },
            )
            // LOAD-BEARING. Red with the guard in its round-1 position (after the
            // registration) and red with no guard at all: the corpse becomes the host's
            // registered client, which is what every other surface reads.
            val registered = registry.clients.value[HOST_ID]?.client
            for (corpse in replacements) {
                assertNotSame(
                    "issue #1863 (AC5/G2): a passive-recovery rung must NEVER register a " +
                        "replacement whose control channel died during its own attach — the " +
                        "guard has to run BEFORE activeTmuxClients.register(), like the " +
                        "sibling `!ready` early return does. registered=$registered",
                    corpse,
                    registered,
                )
            }
            assertFalse(
                "issue #1863 (AC5/G2): and the rung must not have revealed the corpse as a " +
                    "live session either — got ${vm.connectionStatus.value}",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected &&
                    vm.clientDisconnectedForTest(),
            )
        }

        private fun assertSameClientRegistered(expected: TmuxClient, why: String) {
            assertTrue("$why (registered=${registry.clients.value[HOST_ID]?.client})",
                registry.clients.value[HOST_ID]?.client === expected)
        }

        private fun newReplacementThatDiesMidAttach(sessionName: String): FakeTmuxClient {
            val client = FakeTmuxClient()
            // Sticky fixtures: a rung may re-issue these on a retry.
            repeat(8) {
                client.responses.addLast(
                    CommandResponse(
                        number = 1L,
                        output = listOf("%1\t@0\t\$0\t$sessionName\t$sessionName\t0"),
                        isError = false,
                    ),
                )
                client.cursorQueryResponses.addLast(
                    CommandResponse(number = 3L, output = listOf("0,0"), isError = false),
                )
            }
            client.defaultCaptureResponse =
                CommandResponse(number = 2L, output = listOf("$sessionName ready"), isError = false)
            // The kill point: the panes have already come back (`list-panes` answered), so
            // the attach is PAST readiness and on its way to the registration + reveal —
            // the reported window. Closing on the seed capture, which the rung's
            // `reseedVisiblePanesForPassiveReattach` awaits before the registration, keeps
            // the ordering on the rung's own coroutine with no dispatcher race.
            client.onCommandSent = { command ->
                if (command.trimStart().startsWith("capture-pane") &&
                    !client.disconnectedSignal.value
                ) {
                    client.markDisconnectedForTest(
                        TmuxDisconnectEvent(
                            reason = TmuxDisconnectReason.ExplicitClose,
                            source = "local",
                            intent = "local_close",
                        ),
                    )
                }
            }
            replacements += client
            return client
        }
    }

    private class FakeSshSession(
        private val isConnectedValue: Boolean = true,
    ) : SshSession {
        @Volatile
        var closed: Boolean = false

        override val isConnected: Boolean get() = isConnectedValue && !closed

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
        const val HOST_ID = 7L
    }
}
