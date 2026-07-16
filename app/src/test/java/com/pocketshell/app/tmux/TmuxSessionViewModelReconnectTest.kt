package com.pocketshell.app.tmux

import com.pocketshell.app.connectivity.TerminalNetworkChange
import com.pocketshell.app.connectivity.TerminalNetworkSnapshot
import com.pocketshell.app.diagnostics.installRecordingDiagnosticSink
import com.pocketshell.app.sessions.ActiveTmuxClients
import com.pocketshell.core.connection.ConnectionController
import com.pocketshell.core.ssh.ExecResult
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshLeaseConnector
import com.pocketshell.core.ssh.SshLeaseKey
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class TmuxSessionViewModelReconnectTest : TmuxSessionViewModelTestBase() {
    @Test
    fun networkReconnectEvictsConnectedIdleLeaseBeforeReattaching() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val staleSession = FakeSshSession()
        val freshSession = FakeSshSession()
        val connector = QueueLeaseConnector(staleSession, freshSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        manager.acquire(testLeaseTarget()).getOrThrow().release()
        runCurrent()

        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "wifi-cellular-handoff"),
        )
        advanceUntilIdle()

        assertTrue(
            "stale connected idle lease must be closed before network reconnect acquire",
            staleSession.closed,
        )
        assertEquals(
            "network reconnect must open a fresh SSH transport instead of reusing the warm stale one",
            2,
            connector.connectCount,
        )
        assertEquals(listOf(freshSession), sessionsSeenByFactory)
        assertSame(reconnectClient, registry.clients.value[1L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
    }

    /**
     * Issue #548: when a network reconnect lands on a stale SSH transport
     * whose tmux control channel returns EOF on `list-panes`, the
     * auto-reconnect loop must:
     *
     *  1. detect the stale channel symptom and evict the poisoned lease
     *     ([SshLeaseManager.disconnect]),
     *  2. retry on a fresh SSH transport,
     *  3. recover to Connected.
     *
     * This proves the reconnect loop self-heals from a transport that
     * reports isConnected but silently drops tmux commands — the exact
     * symptom of a TCP reset that the SSH library hasn't noticed yet.
     */
    @Test
    fun networkReconnectRetriesAfterStaleListPanesEofAndRecovers() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val staleSession = FakeSshSession()
        val freshSession = FakeSshSession()
        val connector = QueueLeaseConnector(staleSession, freshSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )

        // Stale client: connect() succeeds but list-panes throws a
        // command-timeout exception (closeAndThrowOnCommandPrefix),
        // which is classified as a stale channel symptom.
        val staleClient = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "list-panes"
            closeAndThrowException = TmuxClientException("tmux command timed out")
        }

        // Fresh client: works normally.
        val freshClient = FakeTmuxClient().withSinglePane("work", "%1")

        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            if (session === staleSession) staleClient else freshClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )

        // Trigger network reconnect.
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "wifi-cellular-handoff"),
        )
        advanceUntilIdle()

        // Verify two connect attempts: stale (fails) + fresh (recovers).
        assertEquals(
            "auto-reconnect must dial twice: stale EOF then fresh recovery",
            2,
            connector.connectCount,
        )
        assertEquals(2, TMUX_CONNECT_ATTEMPTS.get())

        // Verify the stale SSH session was evicted (closed by disconnect).
        assertTrue(
            "stale SSH session must be evicted after list-panes EOF",
            staleSession.closed,
        )

        // Verify the factory saw both sessions: stale (failed) then fresh (recovered).
        assertEquals(listOf(staleSession, freshSession), sessionsSeenByFactory)

        // Verify the VM recovered to Connected with the fresh client.
        assertSame(freshClient, registry.clients.value[1L]?.client)
        assertTrue(
            "expected network reconnect to recover to Connected after stale retry, " +
                "got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
    }

    @Test
    fun beyondGraceReconnectPreflightClosedTransportRedialsFreshNotUnreachable() = runTest(scheduler) {
        // Issue #1328 (S5, #1321 §1b) — the BEHAVIORAL red→green (NOT the classifier
        // proxy). The maintainer's beyond-grace reconnect hit a `transport is closed`
        // failure: the reused warm lease's SSH transport was silently torn down while
        // backgrounded, so the reconnect's first dial fails with "transport is closed".
        // The BROKEN behavior surfaced a hard Unreachable / "Tap Reconnect" on that first
        // failure. The FIX: a closed transport is TRANSIENT — evict the poisoned lease and
        // SILENTLY DIAL A FRESH transport, healing the SAME session.
        //
        // The load-bearing GREEN assertion is the FRESH SECOND DIAL + recovery to
        // Connected (NOT a Failed band), and that the FIRST (poisoned) session was evicted.
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val closedSession = FakeSshSession()
        val freshSession = FakeSshSession()
        val connector = QueueLeaseConnector(closedSession, freshSession)
        val manager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 60_000L)

        // First client: connect() succeeds but list-panes throws the exact
        // `transport is closed` shape #1321 hit (the silently-dead backgrounded lease).
        val closedClient = FakeTmuxClient().apply {
            closeAndThrowOnCommandPrefix = "list-panes"
            closeAndThrowException = TmuxClientException(
                "failed to preflight tmux has-session -t work: transport is closed",
            )
        }
        val freshClient = FakeTmuxClient().withSinglePane("work", "%1")

        val vm = newVm(registry = registry, sshLeaseManager = manager)
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            if (session === closedSession) closedClient else freshClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )

        // Trigger the reconnect (a validated network handoff drives the beyond-grace
        // proactive reconnect through the SAME single ladder the maintainer's reconnect used).
        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "validated-default-network-changed"),
        )
        advanceUntilIdle()

        assertEquals(
            "a `transport is closed` reconnect must dial TWICE: the closed transport, " +
                "then a FRESH one — never hard-fail on the first (the #1321 break)",
            2,
            connector.connectCount,
        )
        assertTrue(
            "the poisoned (closed-transport) SSH session must be evicted, not reused",
            closedSession.closed,
        )
        assertSame(
            "the SAME session must recover onto the fresh client",
            freshClient,
            registry.clients.value[1L]?.client,
        )
        val status = vm.connectionStatus.value
        assertTrue(
            "a closed-transport reconnect must SILENTLY heal to Connected, not surface " +
                "a Failed/Unreachable 'Tap Reconnect' band; got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
    }

    @Test
    fun lifecycleReattachEvictsConnectedIdleLeaseBeforeReattaching() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val staleSession = FakeSshSession()
        val freshSession = FakeSshSession()
        val connector = QueueLeaseConnector(staleSession, freshSession)
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        manager.acquire(testLeaseTarget()).getOrThrow().release()
        runCurrent()

        val lifecycleClient = FakeTmuxClient().withSinglePane("work", "%1")
        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            lifecycleClient
        }
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )

        vm.onAppBackgroundedAndAwait()
        vm.onAppForegrounded()
        advanceUntilIdle()

        assertTrue(
            "stale connected idle lease must be closed before lifecycle reattach acquire",
            staleSession.closed,
        )
        assertEquals(
            "lifecycle reattach must open a fresh SSH transport instead of reusing the warm stale one",
            2,
            connector.connectCount,
        )
        assertEquals(listOf(freshSession), sessionsSeenByFactory)
        assertSame(lifecycleClient, registry.clients.value[1L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(TmuxConnectTrigger.LifecycleReattach, vm.latestRestoreIntentSnapshot()?.trigger)
    }

    @Test
    fun postGraceLifecycleReattachCoalescesDeferredNetworkReplay() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val lifecycleClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            lifecycleClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onBackground()
        advanceUntilIdle()
        assertTrue("background teardown should detach the stale control client", oldClient.closed)

        registry.lifecycleHooksSnapshot().single().onForeground(false)
        advanceUntilIdle()

        assertEquals(1, connector.connectCount)
        assertTrue(lifecycleClient.connectCalled)
        assertSame(lifecycleClient, registry.clients.value[7L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(TmuxConnectTrigger.LifecycleReattach, vm.latestRestoreIntentSnapshot()?.trigger)

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "post-grace-deferred-network-replay",
                sequence = 42L,
                deferredFromBackground = true,
            ),
        )
        runCurrent()

        assertTrue(
            "deferred replay after fresh lifecycle attach must stay connected, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(
            "deferred replay must not schedule a second network reconnect",
            TmuxConnectTrigger.LifecycleReattach,
            vm.latestRestoreIntentSnapshot()?.trigger,
        )
        assertEquals(1, connector.connectCount)
        assertEquals(1, TMUX_CONNECT_ATTEMPTS.get())
        assertSame(lifecycleClient, registry.clients.value[7L]?.client)
    }

    @Test
    fun postGraceLifecycleReattachCoalescesForegroundNetworkReplayWithoutForceFreshReconnect() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val staleIdleSession = FakeSshSession()
        val lifecycleSession = FakeSshSession()
        val networkReconnectSession = FakeSshSession()
        val connector = QueueLeaseConnector(
            staleIdleSession,
            lifecycleSession,
            networkReconnectSession,
        )
        val manager = testLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = 60_000L,
        )
        manager.acquire(testLeaseTarget()).getOrThrow().release()
        runCurrent()

        val lifecycleClient = FakeTmuxClient().withSinglePane("work", "%1")
        val sessionsSeenByFactory = mutableListOf<com.pocketshell.core.ssh.SshSession>()
        val vm = newVm(
            registry = registry,
            sshLeaseManager = manager,
        )
        vm.setTmuxClientFactoryForTest { session, sessionName, _ ->
            assertEquals("work", sessionName)
            sessionsSeenByFactory += session
            lifecycleClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onBackground()
        advanceUntilIdle()
        registry.lifecycleHooksSnapshot().single().onForeground(false)
        advanceUntilIdle()

        assertTrue("post-grace lifecycle attach must evict the old idle transport", staleIdleSession.closed)
        assertEquals(listOf(lifecycleSession), sessionsSeenByFactory)
        assertEquals(2, connector.connectCount)
        assertSame(lifecycleClient, registry.clients.value[1L]?.client)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected)
        assertEquals(TmuxConnectTrigger.LifecycleReattach, vm.latestRestoreIntentSnapshot()?.trigger)

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(
                previous = TerminalNetworkSnapshot.Validated("wifi"),
                current = TerminalNetworkSnapshot.Validated("cell"),
                previousValidated = TerminalNetworkSnapshot.Validated("wifi"),
                reason = "process-foreground",
                sequence = 43L,
                deferredFromBackground = false,
            ),
        )
        advanceUntilIdle()

        assertTrue(
            "foreground network replay after fresh lifecycle attach must not force-refresh the active lease",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertFalse("fresh lifecycle SSH session must remain active", lifecycleSession.closed)
        assertFalse("no network reconnect transport should be opened", networkReconnectSession.closed)
        assertEquals(
            "foreground replay must not schedule a second NetworkReconnect acquire",
            2,
            connector.connectCount,
        )
        assertEquals(listOf(lifecycleSession), sessionsSeenByFactory)
        assertEquals(TmuxConnectTrigger.LifecycleReattach, vm.latestRestoreIntentSnapshot()?.trigger)
        assertSame(lifecycleClient, registry.clients.value[1L]?.client)
    }

    @Test
    fun networkReconnectRetriesTransientFlapThenRecoversWithoutOverlappingAttempts() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val connector = FailingThenConnectingLeaseConnector(
            failures = listOf(
                SshException("SSH connect to alex@alpha.example:22 failed: temporary link cut"),
                SshException("SSH connect to alex@alpha.example:22 failed: transient latency timeout"),
            ),
            session = FakeSshSession(),
        )
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        // Issue #1633: the controller jitters every NON-ZERO rung by ±RETRY_JITTER_FRACTION,
        // so a 250ms rung really fires somewhere in [200, 300). Advancing a flat 250ms (as
        // this test did before #1638 added jitter) is a literal coin flip — it fired the rung
        // only when the roll happened to land at or below the base, which is why this test
        // failed ~50% of runs on `main`. Advance past the rung's GUARANTEED upper bound
        // instead. The assertions below are UNCHANGED and stay exact: each step still demands
        // EXACTLY one new dial, because the NEXT rung's own backoff is at least
        // rung*(1-fraction) = 200ms and at most 100ms of it can have elapsed by then, so it
        // cannot also have fired inside this window.
        val rungMs = 250L
        val rungUpperBoundMs = (rungMs * (1.0 + ConnectionController.RETRY_JITTER_FRACTION)).toLong()
        vm.setAutoReconnectDelaysForTest(listOf(0L, rungMs, rungMs))
        val oldClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = oldClient,
        )
        runCurrent()

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "wifi-cellular-flap"),
        )
        runCurrent()

        assertEquals(
            "first network reconnect attempt should run immediately and fail transiently",
            1,
            connector.connectCount,
        )
        assertTrue(
            "after the first transient failure the VM should stay in the bounded reconnect loop",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals(1, TMUX_CONNECT_ATTEMPTS.get())

        advanceTimeBy(rungUpperBoundMs)
        runCurrent()

        assertEquals(
            "second network reconnect attempt should be the next bounded backoff step",
            2,
            connector.connectCount,
        )
        assertTrue(
            "after the second transient failure the VM should still wait for the final retry",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )
        assertEquals(2, TMUX_CONNECT_ATTEMPTS.get())

        advanceTimeBy(rungUpperBoundMs)
        advanceUntilIdle()

        assertEquals(
            "third network reconnect attempt should recover when the link returns",
            3,
            connector.connectCount,
        )
        assertEquals(
            "bounded reconnect loop must not overlap SSH dials while the network is flapping",
            1,
            connector.maxConcurrentConnects,
        )
        assertTrue("old tmux client must be closed during reconnect", oldClient.closed)
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(
            "expected network reconnect to recover to Connected, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertEquals(3, TMUX_CONNECT_ATTEMPTS.get())
        assertEquals(TmuxConnectTrigger.NetworkReconnect, vm.latestRestoreIntentSnapshot()?.trigger)
    }

    @Test
    fun eofDisconnectDoesNotBurnAutoReconnectAttempts() = runTest(scheduler) {
        TMUX_CONNECT_ATTEMPTS.set(0)
        val registry = ActiveTmuxClients()
        val vm = newVm(registry)
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
        )

        deadClient.disconnectedSignal.value = true
        advanceUntilIdle()

        val status = vm.connectionStatus.value
        assertTrue(
            "expected manual reconnect failure state, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertEquals(
            "Disconnected from alex@alpha.example:22. Tap Reconnect to retry.",
            (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
        )
        assertEquals(0, TMUX_CONNECT_ATTEMPTS.get())
        assertEquals("work", vm.activeSessionNameForTest())
        assertTrue("manual reconnect must remain available after passive EOF", vm.canReconnect.value)
    }

    @Test
    fun autoReconnectExhaustionAgainstUnreachableHostSurfacesDisconnectedFromBand() =
        runTest(scheduler) {
            // Issue #1098 item 3: when the host is GENUINELY unrecoverable, the bounded
            // auto-reconnect ladder must EXHAUST and surface a CLEAR "Disconnected from
            // <user>@<host>:<port>. Tap Reconnect to retry." Failed band — never leave a
            // frozen-but-live screen, and never the jargon-y, self-contradictory
            // "Transport EOF …; reconnecting. Auto reconnect failed after N attempts."
            //
            // RED on base: the exhaustion message reads "Transport EOF from
            // alex@alpha.example:22; reconnecting. Auto reconnect failed after 2
            // attempts." so the assertEquals below fails. GREEN with the fix: the unified
            // #145 "Disconnected from …" wording.
            //
            // This is the per-push (Unit job) sibling of the connected end-to-end
            // BackgroundResumeSocketDeathE2eTest (which arms `forceUnrecoverableHostForTest`
            // on the real `agents:2222` path). Here a FailingLeaseConnector fails every
            // fresh dial (a retryable connection-refused), so the ladder runs every rung
            // and exhausts.
            TMUX_CONNECT_ATTEMPTS.set(0)
            val registry = ActiveTmuxClients()
            val connector = FailingLeaseConnector(
                SshException(
                    "SSH connect to alex@alpha.example:22 failed: ConnectException: Connection refused",
                    ConnectException("Connection refused"),
                ),
            )
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
            )
            // graceMs=0 so the silent-reattach grace loop returns immediately and the
            // structured EOF enters the bounded auto-reconnect ladder; two rungs so the
            // ladder genuinely exhausts both before surfacing the band.
            vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
            vm.setAutoReconnectDelaysForTest(listOf(0L, 0L))
            val deadClient = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = deadClient,
            )

            deadClient.markDisconnectedForTest(
                TmuxDisconnectEvent(
                    reason = TmuxDisconnectReason.ReaderEof,
                    source = "eof",
                    intent = "unknown",
                ),
            )
            advanceUntilIdle()

            assertEquals(
                "a genuinely unreachable host must exhaust every reconnect rung",
                2,
                connector.connectCount,
            )
            val status = vm.connectionStatus.value
            assertTrue(
                "expected Failed (Disconnected band) after the ladder exhausts, got $status",
                status is TmuxSessionViewModel.ConnectionStatus.Failed,
            )
            assertEquals(
                "Disconnected from alex@alpha.example:22. Tap Reconnect to retry.",
                (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
            )
            assertTrue(
                "the honest disconnect band must keep the manual Reconnect affordance",
                vm.canReconnect.value,
            )
        }

    @Test
    fun explicitReconnectAfterEofReportsNonRetryableAuthFailureOnce() = runTest(scheduler) {
        // Issue #440: a non-retryable failure (auth rejection) must NOT burn
        // the whole backoff schedule when a passive EOF has already surfaced
        // the manual Reconnect affordance.
        val registry = ActiveTmuxClients()
        val connector = FailingLeaseConnector(
            SshException(
                "SSH connect to alex@alpha.example:22 failed: UserAuthException: auth fail",
                UserAuthException("Exhausted available authentication methods"),
            ),
        )
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        // Four delays available — if the abort fails, all four would be used.
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L, 0L))
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
        )

        deadClient.disconnectedSignal.value = true
        runCurrent()

        assertEquals(0, connector.connectCount)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed)

        assertTrue(vm.reconnect())
        advanceUntilIdle()

        assertEquals(
            "explicit reconnect after passive EOF must make one SSH attempt",
            1,
            connector.connectCount,
        )
        val status = vm.connectionStatus.value
        assertTrue(
            "expected Failed after non-retryable auth failure, got $status",
            status is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        val message = (status as TmuxSessionViewModel.ConnectionStatus.Failed).message
        assertTrue(
            "Failed message must explain the non-retryable cause, was: $message",
            message.contains("auth fail") || message.contains("authentication failed"),
        )
        assertFalse(
            "non-retryable abort must not report exhausting all attempts, was: $message",
            message.contains("Auto reconnect failed after"),
        )
    }

    @Test
    fun explicitReconnectAfterEofDoesNotLoopOnTransientFailure() = runTest(scheduler) {
        // Issue #440: a transient transport failure (e.g. connection refused
        // while the host reboots) should not become a reconnect storm after a
        // passive EOF has surfaced the manual Reconnect affordance.
        val registry = ActiveTmuxClients()
        val connector = FailingThenConnectingLeaseConnector(
            failures = listOf(
                SshException(
                    "SSH connect to alex@alpha.example:22 failed: ConnectException: Connection refused",
                    ConnectException("Connection refused"),
                ),
            ),
            session = FakeSshSession(),
        )
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        vm.setAutoReconnectDelaysForTest(listOf(0L, 0L, 0L))
        val deadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = deadClient,
        )

        deadClient.disconnectedSignal.value = true
        runCurrent()

        assertEquals(0, connector.connectCount)
        assertTrue(vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed)

        assertTrue(vm.reconnect())
        advanceUntilIdle()

        assertEquals(
            "explicit reconnect after passive EOF should not retry in a tight loop",
            1,
            connector.connectCount,
        )
        assertTrue(
            "expected Failed after one transient reconnect failure, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
    }

    @Test
    fun tmuxAutoReconnectDelayIsCancelledWhenAppBackgrounds() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val registry = ActiveTmuxClients()
            val connector = QueueLeaseConnector(FakeSshSession())
            val vm = newVm(
                registry = registry,
                sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
            )
            vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
            vm.setAutoReconnectDelaysForTest(listOf(60_000L))
            val client = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 7L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = client,
            )

            registry.lifecycleHooksSnapshot().single().onNetworkChanged(
                networkChange(reason = "validated-default-network-changed"),
            )
            runCurrent()
            assertTrue(
                "network reconnect must enter retry delay before background",
                vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
            )

            vm.onAppBackgrounded()
            advanceUntilIdle()
            advanceTimeBy(60_000L)
            advanceUntilIdle()

            assertEquals(
                "backgrounding during retry delay must cancel tmux reconnect attempts",
                0,
                connector.connectCount,
            )
            val status = vm.connectionStatus.value
            assertTrue(
                "backgrounded reconnect should settle in a manual retry state, got $status",
                status is TmuxSessionViewModel.ConnectionStatus.Failed,
            )
            assertTrue(
                (status as TmuxSessionViewModel.ConnectionStatus.Failed).message,
                status.message.contains("Auto reconnect paused while PocketShell is in the background."),
            )
            assertEquals("work", vm.connectingSessionNameForTest())
            assertTrue("manual reconnect remains available after background pause", vm.canReconnect.value)

            val decisions = diagnostics.eventsNamed("auto_reconnect_decision")
            assertTrue(
                "network reconnect must log that auto reconnect was scheduled",
                decisions.any {
                    it.fields["decision"] == "scheduled" &&
                        it.fields["cause"] == "retryable" &&
                        it.fields["trigger"] == TmuxConnectTrigger.NetworkReconnect.logValue
                },
            )
            assertTrue(
                "backgrounded retry delay must log auto reconnect cancellation",
                decisions.any {
                    it.fields["decision"] == "cancelled_due_to_background" &&
                        it.fields["cause"] == "app_background_lifecycle_pause" &&
                        it.fields["trigger"] == TmuxConnectTrigger.NetworkReconnect.logValue
                },
            )
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun foregroundReturnResumesBackgroundPausedAutoReconnect() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 0L, silentReattachTimeoutMs = 1L)
        vm.setAutoReconnectDelaysForTest(listOf(60_000L))
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )

        registry.lifecycleHooksSnapshot().single().onNetworkChanged(
            networkChange(reason = "validated-default-network-changed"),
        )
        runCurrent()
        assertTrue(
            "network reconnect must be waiting in auto-reconnect delay before background",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Reconnecting,
        )

        vm.onAppBackgrounded()
        advanceUntilIdle()
        val backgroundStatus = vm.connectionStatus.value
        assertTrue(
            "background pause should be represented as Failed while app is not visible",
            backgroundStatus is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            (backgroundStatus as TmuxSessionViewModel.ConnectionStatus.Failed).message,
            backgroundStatus.message.contains("Auto reconnect paused while PocketShell is in the background."),
        )
        assertEquals(
            "backgrounding during retry delay must not connect",
            0,
            connector.connectCount,
        )

        vm.onAppForegrounded()
        advanceUntilIdle()

        assertEquals(
            "foreground return must resume the paused reconnect automatically",
            1,
            connector.connectCount,
        )
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(
            "foreground resume should reconnect, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        val foregroundStatus = vm.connectionStatus.value
        assertFalse(
            "stale background-paused copy must not remain visible in foreground",
            foregroundStatus.toString().contains("Auto reconnect paused while PocketShell is in the background."),
        )
    }

    // --- EPIC #766 slice 2a: the bg/fg arms are DRIVEN by the controller edge ----
    //
    // These pin the re-home of the inline background/foreground
    // arm dispatch onto the ConnectionController state EDGE fired by the
    // ConnectionEffectDriver. Each asserts the post-migration decision matches the
    // inline reducer's prior behavior (D31 per-event red→green): if the bg/fg arms
    // were NOT wired to the driver edge, the detach/replay would never run and these
    // would be RED. The #685 trap is covered: the detach arm fires only when the
    // inline-equivalent predicate (clientRef/sessionRef present) holds even though the
    // controller transitions to Backgrounded whenever it holds a host.

    @Test
    fun backgroundDetachArmIsDrivenByControllerBackgroundedEdge() = runTest(scheduler) {
        val vm = newVm()
        val client = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 1L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = client,
        )
        assertTrue(
            "precondition: live (controller Live -> displayed Connected)",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        vm.onAppBackgrounded()
        advanceUntilIdle()

        // The controller's -> Backgrounded edge fired the detach arm (re-home of the
        // inline ConnectionDecision.DetachForBackground): teardown ran AND the pending
        // reattach bookkeeping was stashed — exactly the inline reducer's prior behavior.
        assertTrue(
            "controller Backgrounded edge must drive the clean detach (detachCleanly)",
            client.detachCleanlyCalled,
        )
        assertTrue("controller Backgrounded edge must seed pending reattach", vm.hasPendingReattachForTest())
    }

    @Test
    fun backgroundEdgeDoesNotDetachWhenNoClientOrSession() = runTest(scheduler) {
        // The #685 trap: the controller transitions to Backgrounded whenever it holds a
        // host, but the inline-equivalent predicate also gates on clientRef/sessionRef.
        // With no live client/session, the inline reducer returned Ignore — so the
        // re-homed arm must NOT detach or stash a reattach (no client to tear down).
        val vm = newVm()
        vm.onAppBackgrounded()
        advanceUntilIdle()

        assertFalse(
            "background with no live client/session must not stash a pending reattach",
            vm.hasPendingReattachForTest(),
        )
    }

    @Test
    fun foregroundReplayArmIsDrivenByControllerForegroundEdge() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val vm = newVm()
            var foregroundReattachCount = 0
            vm.setForegroundReattachForTest { foregroundReattachCount += 1 }
            val client = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = client,
            )

            vm.onAppBackgrounded()
            advanceUntilIdle()
            assertTrue("background must stash a pending reattach", vm.hasPendingReattachForTest())
            assertTrue(
                "background detach must not emit foreground_reattach",
                diagnostics.eventsNamed("foreground_reattach").isEmpty(),
            )

            // Beyond grace (the lease was evicted on the detach teardown -> controller's grace
            // predicate is not-warm), the controller walks Backgrounded -> Reconnecting, which
            // fires the re-homed foreground arm (ForegroundReturnArm.ReplayPendingReattach).
            vm.onAppForegrounded()
            assertFalse(
                "app foreground hook must arm post-grace reattach without waiting on a later driver turn",
                vm.hasPendingReattachForTest(),
            )
            assertEquals(
                "app foreground hook must drive the replay reattach exactly once",
                1,
                foregroundReattachCount,
            )
            val reattach = diagnostics.eventsNamed("foreground_reattach").single()
            assertEquals("connection", reattach.category)
            assertEquals("app_lifecycle", reattach.fields["source"])
            assertEquals(TmuxConnectTrigger.LifecycleReattach.logValue, reattach.fields["trigger"])
            assertEquals(1L, reattach.fields["hostId"])
            assertEquals("work", reattach.fields["session"])
            advanceUntilIdle()

            assertFalse(
                "controller foreground edge must consume the pending reattach",
                vm.hasPendingReattachForTest(),
            )
            assertEquals(
                "controller foreground edge must drive the replay reattach exactly once",
                1,
                foregroundReattachCount,
            )
        } finally {
            diagnostics.close()
        }
    }

    @Test
    fun postGraceForegroundReplaysPendingReattachEvenWhenControllerStillSeesWarmLease() = runTest(scheduler) {
        val diagnostics = installRecordingDiagnosticSink()
        try {
            val connector = QueueLeaseConnector(FakeSshSession())
            val leaseManager = testLeaseManager(
                connector = connector,
                scope = this,
                idleTtlMillis = 60_000L,
            )
            val vm = newVm(sshLeaseManager = leaseManager)
            runCurrent()

            val warmLease = leaseManager.acquire(testLeaseTarget()).getOrThrow()
            warmLease.release()
            runCurrent()
            assertTrue(
                "precondition: controller warm snapshot must still see a live lease",
                leaseManager.hasLiveLease(testLeaseTarget().leaseKey),
            )

            var foregroundReattachCount = 0
            vm.setForegroundReattachForTest { foregroundReattachCount += 1 }
            val client = FakeTmuxClient()
            vm.replaceClientForTest(
                hostId = 1L,
                hostName = "alpha",
                host = "alpha.example",
                port = 22,
                user = "alex",
                keyPath = "/keys/a",
                sessionName = "work",
                client = client,
            )

            vm.onAppBackgrounded()
            runCurrent()
            assertTrue("background must detach the control client", client.detachCleanlyCalled)
            assertTrue("background must stash a pending reattach", vm.hasPendingReattachForTest())
            assertTrue(
                "warm lease should survive background control-client teardown",
                leaseManager.hasLiveLease(testLeaseTarget().leaseKey),
            )

            vm.onAppForegrounded(resumedWithinGrace = false)

            assertFalse(
                "post-grace foreground must consume pending reattach even if controller picks warm reseed",
                vm.hasPendingReattachForTest(),
            )
            assertEquals(
                "post-grace foreground must replay exactly once",
                1,
                foregroundReattachCount,
            )
            assertEquals(
                "VM-level foreground diagnostic must be emitted for the Android wait",
                1,
                diagnostics.eventsNamed("foreground_reattach").size,
            )
        } finally {
            diagnostics.close()
        }
    }

    // Issue #1123 (bounded-grace D21 update): the two
    // `postGraceForegroundWithoutPendingReattach…HeldRuntime` tests were removed with the
    // `launchPostGraceHeldForegroundProbeIfNeeded` path they covered. That path only
    // existed for the #1021 INDEFINITE foreground-service hold, where a foreground could
    // arrive post-grace with a still-live `-CC` client and NO pendingReattach. Under the
    // bounded grace the teardown ALWAYS runs at grace-elapsed (detach + pendingReattach
    // set), so that state can no longer occur. The "disconnected client on foreground ->
    // reconnect" behaviour is covered by the passive-disconnect tests and the
    // BackgroundGraceReconnect journey.

    @Test
    fun shortAppSwitchPassiveDisconnectResumesAutoReconnectOnScreenStart() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 60_000L, silentReattachTimeoutMs = 1_000L)
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }
        val backgroundDeadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = backgroundDeadClient,
        )
        runCurrent()

        vm.onScreenStopped()
        backgroundDeadClient.markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ReaderEof,
                source = "device_background",
                intent = "unknown",
            ),
        )
        runCurrent()

        assertFalse(
            "short app-switch disconnect must not detach tmux while the screen is stopped",
            backgroundDeadClient.detachCleanlyCalled,
        )
        assertEquals(
            "no SSH reconnect may run while the app is still in the short background switch",
            0,
            connector.connectCount,
        )
        assertTrue(
            "dead background client must be removed from the live registry",
            registry.clients.value.isEmpty(),
        )
        val backgroundStatus = vm.connectionStatus.value
        assertTrue(
            "background passive EOF should be paused for foreground auto-reconnect, got $backgroundStatus",
            backgroundStatus is TmuxSessionViewModel.ConnectionStatus.Failed,
        )
        assertTrue(
            (backgroundStatus as TmuxSessionViewModel.ConnectionStatus.Failed).message,
            backgroundStatus.message.contains("Auto reconnect paused while PocketShell is in the background."),
        )

        advanceTimeBy(6_000L)
        runCurrent()
        assertEquals(
            "a 6 second app switch is below the configured grace and must not start background SSH",
            0,
            connector.connectCount,
        )

        vm.onScreenStarted(sessionName = "work")
        advanceUntilIdle()

        assertEquals(
            "foreground return must automatically resume the paused reconnect",
            1,
            connector.connectCount,
        )
        assertSame(reconnectClient, registry.clients.value[7L]?.client)
        assertTrue(
            "foreground return must not remain in the manual reconnect-needed state; got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
        assertTrue("automatic foreground reconnect should open the replacement client", reconnectClient.connectCalled)
    }

    /**
     * Issue #630: when the user navigates back from session A to the host list
     * and then selects session B on the same host, the paused reconnect for
     * session A must NOT fire. [onScreenStarted] must clear the stale
     * [pausedAutoReconnect] instead of resuming it.
     */
    @Test
    fun onScreenStartedClearsPausedReconnectForDifferentSession() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 60_000L, silentReattachTimeoutMs = 1_000L)
        vm.setAutoReconnectDelaysForTest(listOf(0L))

        // Set up session A ("work") as the active session.
        val clientA = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = clientA,
        )
        runCurrent()
        assertTrue(
            "session A should be connected",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )

        // User navigates back from session A.
        vm.onScreenStopped()

        // Session A's tmux client disconnects while the screen is stopped.
        clientA.markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ReaderEof,
                source = "device_background",
                intent = "unknown",
            ),
        )
        runCurrent()

        // The paused reconnect should target session A.
        val pausedStatus = vm.connectionStatus.value
        assertTrue(
            "expected paused-reconnect status after background disconnect, got $pausedStatus",
            pausedStatus is TmuxSessionViewModel.ConnectionStatus.Failed,
        )

        // User selects session B ("personal") on the same host.
        // onScreenStarted for session B must clear the stale paused reconnect
        // instead of resuming it.
        vm.onScreenStarted(sessionName = "personal")
        runCurrent()

        // No SSH connect should have been triggered — the paused reconnect
        // for session A was cleared, and session B's LaunchedEffect connect
        // is not tested here (it would go through a different code path).
        assertEquals(
            "onScreenStarted for a different session must NOT trigger a connect",
            0,
            connector.connectCount,
        )
    }

    /**
     * Issue #630: verifies that a legitimate background-to-foreground resume
     * (same session) still works after the session-mismatch guard is added.
     */
    @Test
    fun onScreenStartedResumesPausedReconnectForSameSession() = runTest(scheduler) {
        val registry = ActiveTmuxClients()
        val connector = QueueLeaseConnector(FakeSshSession())
        val reconnectClient = FakeTmuxClient().withSinglePane("work", "%1")
        val vm = newVm(
            registry = registry,
            sshLeaseManager = testLeaseManager(connector = connector, scope = this, idleTtlMillis = 0L),
        )
        vm.setPassiveDisconnectRecoveryForTest(graceMs = 60_000L, silentReattachTimeoutMs = 1_000L)
        vm.setAutoReconnectDelaysForTest(listOf(0L))
        vm.setTmuxClientFactoryForTest { _, sessionName, _ ->
            assertEquals("work", sessionName)
            reconnectClient
        }

        val backgroundDeadClient = FakeTmuxClient()
        vm.replaceClientForTest(
            hostId = 7L,
            hostName = "alpha",
            host = "alpha.example",
            port = 22,
            user = "alex",
            keyPath = "/keys/a",
            sessionName = "work",
            client = backgroundDeadClient,
        )
        runCurrent()

        vm.onScreenStopped()
        backgroundDeadClient.markDisconnectedForTest(
            TmuxDisconnectEvent(
                reason = TmuxDisconnectReason.ReaderEof,
                source = "device_background",
                intent = "unknown",
            ),
        )
        runCurrent()

        // Resume foreground with the SAME session name — should trigger reconnect.
        vm.onScreenStarted(sessionName = "work")
        advanceUntilIdle()

        assertEquals(
            "same-session foreground resume must trigger auto-reconnect",
            1,
            connector.connectCount,
        )
        assertTrue(
            "expected Connected after same-session foreground resume, got ${vm.connectionStatus.value}",
            vm.connectionStatus.value is TmuxSessionViewModel.ConnectionStatus.Connected,
        )
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

    private fun testLeaseTarget(): SshLeaseTarget =
        SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "alpha.example",
                port = 22,
                user = "alex",
                credentialId = "1:/keys/a",
            ),
            key = SshKey.Path(File("/keys/a")),
        )

    private fun networkChange(
        previous: TerminalNetworkSnapshot = TerminalNetworkSnapshot.Validated("wifi"),
        current: TerminalNetworkSnapshot.Validated = TerminalNetworkSnapshot.Validated("cell"),
        previousValidated: TerminalNetworkSnapshot.Validated? =
            previous as? TerminalNetworkSnapshot.Validated,
        reason: String = "validated-default-network-changed",
        sequence: Long = 1L,
        deferredFromBackground: Boolean = false,
    ): TerminalNetworkChange =
        TerminalNetworkChange(
            previous = previous,
            current = current,
            previousValidated = previousValidated,
            reason = reason,
            sequence = sequence,
            deferredFromBackground = deferredFromBackground,
        )

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

    private class FailingLeaseConnector(
        private val failure: Throwable,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            connectCount += 1
            return Result.failure(failure)
        }
    }

    private class FailingThenConnectingLeaseConnector(
        private val failures: List<Throwable>,
        private val session: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0
            private set
        private val inFlightConnects: AtomicInteger = AtomicInteger(0)

        var maxConcurrentConnects: Int = 0
            private set

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val inFlight = inFlightConnects.incrementAndGet()
            maxConcurrentConnects = maxOf(maxConcurrentConnects, inFlight)
            return try {
                val index = connectCount
                connectCount += 1
                failures.getOrNull(index)?.let { Result.failure(it) }
                    ?: Result.success(session)
            } finally {
                inFlightConnects.decrementAndGet()
            }
        }
    }

    private class UserAuthException(message: String) : Exception(message)

    private class ConnectException(message: String) : IOException(message)

    private class FakeSshSession(
        private val isConnectedValue: Boolean = true,
    ) : SshSession {
        @Volatile
        var closed: Boolean = false

        @Volatile
        var transportProvenAlive: Boolean = false

        override val isConnected: Boolean
            get() = isConnectedValue && !closed

        override fun isTransportProvenAliveWithinKeepAliveWindow(): Boolean =
            transportProvenAlive && isConnected

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
