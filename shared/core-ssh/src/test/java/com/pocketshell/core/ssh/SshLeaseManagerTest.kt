package com.pocketshell.core.ssh

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream

@OptIn(ExperimentalCoroutinesApi::class)
class SshLeaseManagerTest {
    @Test
    fun `same host and credentials reuse a live leased session`() = runTest {
        val first = FakeSshSession()
        val connector = QueueLeaseConnector(first)
        val manager = leaseManager(connector)

        val lease1 = manager.acquire(TARGET).getOrThrow()
        val lease2 = manager.acquire(TARGET).getOrThrow()

        assertEquals(1, connector.connectCount)
        assertSame(first, lease1.session)
        assertSame(first, lease2.session)

        lease1.release()
        assertFalse(first.closed)
        lease2.release()
    }

    @Test
    fun `different credential ids do not share a host connection`() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val connector = QueueLeaseConnector(first, second)
        val manager = leaseManager(connector)

        val lease1 = manager.acquire(TARGET).getOrThrow()
        val lease2 = manager.acquire(TARGET.copy(leaseKey = TARGET.leaseKey.copy(credentialId = "key-b"))).getOrThrow()

        assertEquals(2, connector.connectCount)
        assertNotSame(lease1.session, lease2.session)

        lease1.release()
        lease2.release()
    }

    @Test
    fun `released idle session closes after ttl`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(QueueLeaseConnector(session), idleTtlMillis = 1_000)

        val lease = manager.acquire(TARGET).getOrThrow()
        lease.release()

        advanceTimeBy(999)
        runCurrent()
        assertFalse(session.closed)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(session.closed)
    }

    // ----------------------------------------------------------------------
    // EPIC #792 Slice C / #822 WEDGE — the poisoned-warm-lease reuse mechanism.
    //
    // The #822 wedge: on a silent HALF-OPEN drop sshj's `isConnected` LIES — it
    // keeps reporting `true` until the ~60s keep-alive trips. The pool's `acquire`
    // reuses any entry whose `session.isConnected` is true, so a poisoned (dead-but-
    // lying) idle entry is REUSED and every auto-reconnect attempt re-dials the SAME
    // dead socket — the maintainer's "stuck Reconnecting, only the switch dance
    // recovers" wedge. The Slice C production fix makes the auto-reconnect ladder use
    // the force-fresh-lease path (`shouldForceFreshLease(AutoReconnect)` →
    // `acquireLeaseForTmux` evicts the idle lease via `evictIdle` before re-dialling),
    // which EVICTS the poisoned entry so the re-dial gets a FRESH transport.
    //
    // These two tests pin BOTH sides of that mechanism deterministically (no
    // toxiproxy, no emulator): the wedge (reuse) and its fix (evict-then-fresh). A
    // [FakeSshSession] that LIES about `isConnected` (the `connected=true` flag stays
    // set after the worker conceptually died) reproduces the half-open condition the
    // emulator cannot deterministically inject without the Slice D LivenessProbe.

    @Test
    fun `WEDGE - a poisoned half-open idle lease that lies about isConnected is REUSED on reacquire`() =
        runTest {
            val poisoned = FakeSshSession() // connected=true, but conceptually dead (half-open)
            val fresh = FakeSshSession()
            val connector = QueueLeaseConnector(poisoned, fresh)
            val manager = leaseManager(connector)

            // Acquire + release: the entry sits warm/idle. The half-open drop now
            // happens on the wire — but sshj's isConnected LIES, so the FakeSshSession
            // keeps `connected=true` (the exact #822 condition).
            manager.acquire(TARGET).getOrThrow().release()
            runCurrent()

            // A plain reacquire (the inline AutoReconnect path BEFORE the fix, which did
            // NOT force-fresh) REUSES the poisoned entry — no new connect. This is the
            // wedge: the re-dial rides the same dead socket and never recovers.
            val reacquired = manager.acquire(TARGET).getOrThrow()
            assertSame("the poisoned half-open lease was reused (the #822 wedge)", poisoned, reacquired.session)
            assertEquals("no fresh transport was dialled (the wedge)", 1, connector.connectCount)
            reacquired.release()
        }

    @Test
    fun `FIX - force-fresh evicts the poisoned half-open idle lease then re-dials a FRESH transport`() =
        runTest {
            val poisoned = FakeSshSession()
            val fresh = FakeSshSession()
            val connector = QueueLeaseConnector(poisoned, fresh)
            val manager = leaseManager(connector)

            manager.acquire(TARGET).getOrThrow().release()
            runCurrent()

            // The Slice C force-fresh sequence the AutoReconnect trigger now performs:
            // acquireLeaseForTmux evicts the idle lease (evictIdle) BEFORE acquiring.
            val evicted = manager.evictIdle(TARGET.leaseKey)
            assertTrue("the poisoned idle entry must be evicted by force-fresh", evicted)
            assertTrue("evicting the poisoned entry closes its (dead) session", poisoned.closed)

            // The follow-up acquire now dials a FRESH transport — the SAME session
            // recovers instead of riding the poisoned socket.
            val refreshed = manager.acquire(TARGET).getOrThrow()
            assertSame("force-fresh dialled a brand-new transport", fresh, refreshed.session)
            assertNotSame("the poisoned lease is NOT reused after the fix", poisoned, refreshed.session)
            assertEquals("a fresh handshake was performed", 2, connector.connectCount)
            assertTrue("the fresh transport is a new connection", refreshed.isNewConnection)
            refreshed.release()
        }

    @Test
    fun `default idle ttl keeps released lease warm for sixty seconds`() = runTest {
        val session = FakeSshSession()
        val manager = SshLeaseManager(
            connector = QueueLeaseConnector(session),
            scope = this,
            connectTimeoutContext = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        )

        manager.acquire(TARGET).getOrThrow().release()

        advanceTimeBy(59_999)
        runCurrent()
        assertFalse(session.closed)

        advanceTimeBy(1)
        runCurrent()
        assertTrue(session.closed)
    }

    @Test
    fun `reacquire before ttl cancels idle close`() = runTest {
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val manager = leaseManager(connector, idleTtlMillis = 1_000)

        manager.acquire(TARGET).getOrThrow().release()
        advanceTimeBy(500)
        val lease = manager.acquire(TARGET).getOrThrow()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(1, connector.connectCount)
        assertFalse(session.closed)

        lease.release()
    }

    @Test
    fun `active lease is not closed by another holder release or ttl`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(QueueLeaseConnector(session), idleTtlMillis = 100)

        val lease1 = manager.acquire(TARGET).getOrThrow()
        val lease2 = manager.acquire(TARGET).getOrThrow()
        lease1.release()
        advanceTimeBy(100)
        runCurrent()

        assertFalse(session.closed)

        lease2.release()
        advanceTimeBy(100)
        runCurrent()
        assertTrue(session.closed)
    }

    @Test
    fun `hasLiveLease reflects active idle and closed transports without mutating the pool`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(QueueLeaseConnector(session), idleTtlMillis = 1_000)

        // No lease yet.
        assertFalse(
            "no lease acquired: pool must report no live lease for the key",
            manager.hasLiveLease(TARGET.leaseKey),
        )
        assertFalse(
            "an unrelated key must report no live lease",
            manager.hasLiveLease(OTHER_TARGET.leaseKey),
        )

        // Actively leased -> live.
        val lease = manager.acquire(TARGET).getOrThrow()
        assertTrue("actively leased transport is live", manager.hasLiveLease(TARGET.leaseKey))

        // Released but warm/idle within TTL -> still live, and the probe itself
        // must NOT close it (no mutation).
        lease.release()
        advanceTimeBy(999)
        runCurrent()
        assertTrue("warm idle lease within TTL is live", manager.hasLiveLease(TARGET.leaseKey))
        assertFalse("hasLiveLease must not close the idle transport", session.closed)

        // After the idle TTL expires -> the transport closes -> not live.
        advanceTimeBy(1)
        runCurrent()
        assertTrue(session.closed)
        assertFalse(
            "closed transport must report no live lease",
            manager.hasLiveLease(TARGET.leaseKey),
        )
    }

    @Test
    fun `concurrent acquires for the same key coalesce into one connect`() = runTest {
        // Issue #620: host detail's warm-lease acquire and the FIRST
        // session-open tap dial the SAME key concurrently. They must share ONE
        // SSH handshake so the tap reuses the warm transport instead of racing
        // a second 3-4s connect (the bug behind the maintainer's "3-4s on the
        // first open"). A second FakeSshSession is queued only to prove it is
        // NEVER dialed.
        val shared = FakeSshSession()
        val neverUsed = FakeSshSession()
        val connector = GatedLeaseConnector(shared, neverUsed)
        val manager = leaseManager(connector, idleTtlMillis = 60_000)

        // First acquire owns the connect and parks inside connector.connect().
        val firstDeferred = async { manager.acquire(TARGET).getOrThrow() }
        runCurrent()
        assertEquals(1, connector.startedConnects)

        // Second acquire arrives while the first connect is still in flight. It
        // must NOT start a second connect — it joins the in-flight one.
        val secondDeferred = async { manager.acquire(TARGET).getOrThrow() }
        runCurrent()
        assertEquals(
            "second concurrent acquire must not dial a second connect",
            1,
            connector.startedConnects,
        )

        // Let the single shared connect complete.
        connector.releaseConnect()
        runCurrent()
        val lease1 = firstDeferred.await()
        val lease2 = secondDeferred.await()

        assertEquals("only one SSH handshake for the coalesced opens", 1, connector.startedConnects)
        assertSame("both acquires share the same warm transport", shared, lease1.session)
        assertSame(shared, lease2.session)
        assertTrue("exactly one of the two acquires owns the fresh transport", lease1.isNewConnection)
        assertFalse("the coalesced acquire reuses, it does not re-dial", lease2.isNewConnection)
        assertFalse("the redundant queued session must never be dialed", neverUsed.closed)

        // Both holders own a ref: releasing one keeps the transport warm.
        lease1.release()
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse("a remaining holder keeps the shared transport alive", shared.closed)
        lease2.release()
    }

    @Test
    fun `hasLiveOrConnectingLease is true while a connect is in flight and emits Connecting`() = runTest {
        // Issue #620: the FIRST session open from host detail must read the
        // host's in-flight warm handshake as "warm" so it shows Attaching, not
        // a cold Connecting overlay. hasLiveOrConnectingLease reports true for an
        // in-flight connect; a Connecting state event lets a synchronous consumer
        // (the tmux warm-open hint) pick the same warm affordance.
        val session = FakeSshSession()
        val connector = GatedLeaseConnector(session)
        val manager = leaseManager(connector, idleTtlMillis = 60_000)

        val events = mutableListOf<SshLeaseConnectionState>()
        val eventsCollector = launch { manager.stateEvents.collect { events.add(it.state) } }
        runCurrent()

        // No connect yet: not live, not connecting.
        assertFalse(manager.hasLiveLease(TARGET.leaseKey))
        assertFalse(manager.hasLiveOrConnectingLease(TARGET.leaseKey))

        // Start the connect; it parks in the gated connector (in flight).
        val acquireJob = async { manager.acquire(TARGET).getOrThrow() }
        runCurrent()
        assertFalse("transport not up yet: hasLiveLease must be false", manager.hasLiveLease(TARGET.leaseKey))
        assertTrue(
            "an in-flight connect must read as live-or-connecting",
            manager.hasLiveOrConnectingLease(TARGET.leaseKey),
        )
        assertTrue("starting a connect emits Connecting", events.contains(SshLeaseConnectionState.Connecting))

        // Complete it; now genuinely live.
        connector.releaseConnect()
        runCurrent()
        val lease = acquireJob.await()
        assertTrue(manager.hasLiveLease(TARGET.leaseKey))
        assertTrue(manager.hasLiveOrConnectingLease(TARGET.leaseKey))
        assertTrue("a completed connect emits Connected", events.contains(SshLeaseConnectionState.Connected))

        lease.release()
        eventsCollector.cancel()
    }

    @Test
    fun `failed in-flight connect retracts the connecting hint`() = runTest {
        // Issue #620: a Connecting hint for a key whose handshake FAILS must be
        // retracted (Closed) so a synchronous consumer drops the stale Attaching
        // hint instead of treating a dead key as warm.
        val connector = GatedLeaseConnector(null)
        val manager = leaseManager(connector, idleTtlMillis = 60_000)

        val events = mutableListOf<SshLeaseConnectionState>()
        val eventsCollector = launch { manager.stateEvents.collect { events.add(it.state) } }
        runCurrent()

        val acquireJob = async { manager.acquire(TARGET) }
        runCurrent()
        assertTrue(manager.hasLiveOrConnectingLease(TARGET.leaseKey))
        assertTrue(events.contains(SshLeaseConnectionState.Connecting))

        connector.releaseConnect()
        runCurrent()
        val result = acquireJob.await()
        assertTrue("a failed connect surfaces failure", result.isFailure)
        assertFalse(
            "a failed connect must retract the connecting hint",
            manager.hasLiveOrConnectingLease(TARGET.leaseKey),
        )
        assertTrue(
            "a failed in-flight connect emits Closed to retract the hint",
            events.contains(SshLeaseConnectionState.Closed),
        )
        eventsCollector.cancel()
    }

    @Test
    fun `awaiter falls back to its own connect when the shared connect fails`() = runTest {
        // Issue #620: if the coalesced (owning) connect fails, the acquire that
        // joined it must NOT silently fail — it dials its own transport so the
        // caller still gets a usable lease.
        val good = FakeSshSession()
        val connector = GatedLeaseConnector(null, good)
        val manager = leaseManager(connector, idleTtlMillis = 60_000)

        val firstDeferred = async { manager.acquire(TARGET) }
        runCurrent()
        val secondDeferred = async { manager.acquire(TARGET) }
        runCurrent()
        assertEquals("awaiter coalesced behind the in-flight connect", 1, connector.startedConnects)

        // The shared connect resolves as a failure.
        connector.releaseConnect()
        runCurrent()

        val first = firstDeferred.await()
        assertTrue("the owning acquire surfaces the connect failure", first.isFailure)

        // The awaiter falls back to its own dial and succeeds.
        connector.releaseConnect()
        runCurrent()
        val second = secondDeferred.await()
        assertTrue("the awaiter recovers with its own connect", second.isSuccess)
        assertSame(good, second.getOrThrow().session)
        assertEquals("exactly two connects: failed shared + awaiter fallback", 2, connector.startedConnects)
    }

    @Test
    fun `hasLiveLease is false for a transport that dropped while pooled`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(QueueLeaseConnector(session), idleTtlMillis = 60_000)

        manager.acquire(TARGET).getOrThrow().release()
        assertTrue(manager.hasLiveLease(TARGET.leaseKey))

        // The transport silently dies while still pooled (network drop). The
        // pooled entry remains, but the session is no longer connected.
        session.closed = true
        assertFalse(
            "a dropped pooled transport must report no live lease",
            manager.hasLiveLease(TARGET.leaseKey),
        )
    }

    @Test
    fun `idle cap closes oldest released session`() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val connector = QueueLeaseConnector(first, second)
        val manager = leaseManager(
            connector = connector,
            idleTtlMillis = 60_000,
            maxIdleLeases = 1,
        )

        manager.acquire(TARGET).getOrThrow().release()
        manager.acquire(OTHER_TARGET).getOrThrow().release()

        assertTrue(first.closed)
        assertFalse(second.closed)
    }

    @Test
    fun `process stop closes all idle warm hosts`() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val connector = QueueLeaseConnector(first, second)
        val manager = leaseManager(
            connector = connector,
            idleTtlMillis = 60_000,
            maxIdleLeases = 2,
        )

        manager.acquire(TARGET).getOrThrow().release()
        manager.acquire(OTHER_TARGET).getOrThrow().release()

        assertFalse(first.closed)
        assertFalse(second.closed)

        manager.onProcessStopped()

        assertTrue(first.closed)
        assertTrue(second.closed)
        assertEquals(1, first.closeCount)
        assertEquals(1, second.closeCount)
    }

    @Test
    fun `active lease released after process stop closes without idle ttl`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(
            connector = QueueLeaseConnector(session),
            idleTtlMillis = 60_000,
        )

        val lease = manager.acquire(TARGET).getOrThrow()
        manager.onProcessStopped()

        assertFalse("active foreground owner still holds the lease", session.closed)

        lease.release()
        advanceTimeBy(1)
        runCurrent()

        assertTrue(session.closed)
        assertEquals(1, session.closeCount)
    }

    @Test
    fun `process start restores warm ttl behavior`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(
            connector = QueueLeaseConnector(session),
            idleTtlMillis = 1_000,
        )

        manager.onProcessStopped()
        manager.onProcessStarted()
        val lease = manager.acquire(TARGET).getOrThrow()
        lease.release()
        advanceTimeBy(999)
        runCurrent()

        assertFalse(session.closed)

        advanceTimeBy(1)
        runCurrent()

        assertTrue(session.closed)
    }

    @Test
    fun `explicit disconnect closes host once and stale release is ignored`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(
            connector = QueueLeaseConnector(session),
            idleTtlMillis = 60_000,
        )

        val lease = manager.acquire(TARGET).getOrThrow()
        manager.disconnect(TARGET.leaseKey)
        lease.release()
        advanceTimeBy(60_000)
        runCurrent()

        assertTrue(session.closed)
        assertEquals(1, session.closeCount)
    }

    @Test
    fun `evict idle closes connected retained lease so next acquire opens fresh session`() = runTest {
        val stale = FakeSshSession()
        val fresh = FakeSshSession()
        val connector = QueueLeaseConnector(stale, fresh)
        val manager = leaseManager(
            connector = connector,
            idleTtlMillis = 60_000,
        )

        manager.acquire(TARGET).getOrThrow().release()

        assertFalse(stale.closed)
        assertTrue(manager.evictIdle(TARGET.leaseKey))

        val replacement = manager.acquire(TARGET).getOrThrow()

        assertTrue(stale.closed)
        assertEquals(1, stale.closeCount)
        assertEquals(2, connector.connectCount)
        assertSame(fresh, replacement.session)

        replacement.release()
    }

    @Test
    fun `evict idle ignores active lease`() = runTest {
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val manager = leaseManager(
            connector = connector,
            idleTtlMillis = 60_000,
        )

        val active = manager.acquire(TARGET).getOrThrow()

        assertFalse(manager.evictIdle(TARGET.leaseKey))
        assertFalse(session.closed)

        val shared = manager.acquire(TARGET).getOrThrow()
        assertEquals(1, connector.connectCount)
        assertSame(session, shared.session)

        active.release()
        shared.release()
    }

    @Test
    fun `evict idle leaves a transport a second active holder still owns`() = runTest {
        // Issue #758 (back -> open-another-session reconnect): the FolderList
        // picker poll and an active TmuxSessionViewModel ride the SAME lease key.
        // When the picker poll sees a stale-channel symptom it must NOT close the
        // transport the session VM still holds. Model both consumers: the session
        // VM keeps its lease (refCount stays 1 after the picker releases), so the
        // picker's poison-eviction must be a no-op and the shared transport must
        // survive for the next session open to reuse warm.
        val session = FakeSshSession()
        val connector = QueueLeaseConnector(session)
        val manager = leaseManager(
            connector = connector,
            idleTtlMillis = 60_000,
        )

        // Session VM holds the lease (the live -CC channel) the whole time.
        val sessionHold = manager.acquire(TARGET).getOrThrow()
        // Picker poll acquires the SAME pooled transport (reuse, no new connect)...
        val pickerHold = manager.acquire(TARGET).getOrThrow()
        assertEquals(1, connector.connectCount)
        assertSame(session, pickerHold.session)

        // ...sees a stale-channel symptom, releases its own ref, then evicts.
        pickerHold.release()
        assertFalse(
            "evictIdle must be a no-op while the session VM still holds the lease",
            manager.evictIdle(TARGET.leaseKey),
        )
        assertFalse("the session's transport must NOT be closed", session.closed)

        // Opening another session reuses the WARM transport (no fresh handshake).
        val reuse = manager.acquire(TARGET).getOrThrow()
        assertEquals(
            "warm reuse — no fresh SSH handshake after the picker poison-evict",
            1,
            connector.connectCount,
        )
        assertSame(session, reuse.session)
        assertFalse(reuse.isNewConnection)

        sessionHold.release()
        reuse.release()
    }

    @Test
    fun `evict idle releases the transport once no consumer holds it`() = runTest {
        // The other half of #758: a genuinely idle (zero-refcount) poisoned
        // transport — one NO active session holds — is still cleared so the next
        // poll/open dials a fresh, healthy connection.
        val stale = FakeSshSession()
        val fresh = FakeSshSession()
        val connector = QueueLeaseConnector(stale, fresh)
        val manager = leaseManager(
            connector = connector,
            idleTtlMillis = 60_000,
        )

        // Only the picker poll held it; after release the refcount is 0.
        manager.acquire(TARGET).getOrThrow().release()
        assertFalse(stale.closed)

        assertTrue(
            "evictIdle must clear an idle corpse no consumer holds",
            manager.evictIdle(TARGET.leaseKey),
        )
        assertTrue(stale.closed)

        val replacement = manager.acquire(TARGET).getOrThrow()
        assertEquals(2, connector.connectCount)
        assertSame(fresh, replacement.session)
        assertTrue(replacement.isNewConnection)
        replacement.release()
    }

    @Test
    fun `state events include idle expiry and lifecycle close reasons`() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val manager = leaseManager(
            connector = QueueLeaseConnector(first, second),
            idleTtlMillis = 1_000,
            maxIdleLeases = 2,
        )
        val events = mutableListOf<SshLeaseStateEvent>()
        val collectJob = backgroundScope.launch {
            manager.stateEvents.toList(events)
        }
        runCurrent()

        manager.acquire(TARGET).getOrThrow().release()
        advanceTimeBy(1_000)
        runCurrent()
        manager.acquire(OTHER_TARGET).getOrThrow().release()
        manager.onProcessStopped()
        runCurrent()
        collectJob.cancel()

        assertTrue(
            events.any {
                it.key == TARGET.leaseKey &&
                    it.state == SshLeaseConnectionState.Idle
            },
        )
        assertTrue(
            events.any {
                it.key == TARGET.leaseKey &&
                    it.state == SshLeaseConnectionState.Closed &&
                    it.closeReason == SshLeaseCloseReason.IdleExpired
            },
        )
        assertTrue(
            events.any {
                it.key == OTHER_TARGET.leaseKey &&
                    it.state == SshLeaseConnectionState.Closed &&
                    it.closeReason == SshLeaseCloseReason.ProcessStopped
            },
        )
    }

    // ----------------------------------------------------------------------
    // Issue #845 (G10 / D31) — the transport-up STORM.
    //
    // ROOT CAUSE: `stateEvents` is the SOURCE of the controller's transport
    // up/down edges (`SshLeaseTransportPort.transportEvents` maps `Connected`→
    // `TransportUpDown.Up`). Before the fix, `acquire()` re-emitted `Connected`
    // on EVERY reuse of an already-live lease (the `Reuse`/`raced`/`shared`
    // paths), even though the SSH transport never went down and back up. Because
    // many independent subsystems (session gateway, file explorer, git history,
    // folder list, conversation detection execs, recurring jobs, host list…)
    // each `acquire()` the SAME warm host, ONE connection emitted a STORM of
    // `Connected` events (the maintainer's ~9-up/1-down, 46/7 at scale) → a
    // storm of spurious `transport.up` edges → the #794 flap / re-projection
    // churn. A reuse of a still-up transport is NOT a transport-up edge.
    //
    // FIX: `stateEvents` is now a true transport-state EDGE stream — `Connected`
    // is emitted only when the key transitions INTO a live transport from a
    // not-live published state (`{null, Connecting, Closed}`), never on a reuse
    // of a transport that was already up (`{Connected, Idle}`). One real connect
    // ⇒ exactly one logical `Connected`/`transport.up`.

    @Test
    fun `reusing one live lease many times emits exactly one Connected edge (no transport-up storm) - #845`() =
        runTest {
            val session = FakeSshSession()
            // ONE queued session: if any reuse re-dialled we'd run out / count >1.
            val connector = QueueLeaseConnector(session)
            // 60s TTL so nothing idle-closes between reuses (the transport stays up).
            val manager = leaseManager(connector, idleTtlMillis = 60_000)

            val connectedEvents = mutableListOf<SshLeaseStateEvent>()
            val collectJob = backgroundScope.launch {
                manager.stateEvents.collect { event ->
                    if (event.state == SshLeaseConnectionState.Connected &&
                        event.key == TARGET.leaseKey
                    ) {
                        connectedEvents.add(event)
                    }
                }
            }
            runCurrent()

            // Simulate the many independent subsystems each acquiring the SAME warm
            // host while the transport stays up (overlapping holders — refCount never
            // drops to 0, so the entry is reused in place every time).
            val held = mutableListOf<SshLease>()
            repeat(9) {
                held.add(manager.acquire(TARGET).getOrThrow())
                runCurrent()
            }

            assertEquals(
                "ONE real connect must dial exactly once (every reuse rode the warm transport)",
                1,
                connector.connectCount,
            )
            // RED on current code: 9 reuse-acquires emit 9 Connected events (the storm).
            // GREEN after the fix: a reuse of a still-up transport is not an up-edge.
            assertEquals(
                "one live transport must emit exactly ONE logical transport.up — got the storm: " +
                    "${connectedEvents.size} Connected edges for one connection (#845)",
                1,
                connectedEvents.size,
            )

            held.forEach { it.release() }
            collectJob.cancel()
        }

    @Test
    fun `release to idle then reacquire of the still-up transport emits no extra Connected edge - #845`() =
        runTest {
            // The other half of the class: a release drops refCount to 0 → the entry
            // is published `Idle` (transport still alive, warm). A reacquire BEFORE
            // the idle TTL reuses that warm transport — it is NOT a transport re-up,
            // so it must not emit another `Connected`/`transport.up`.
            val session = FakeSshSession()
            val connector = QueueLeaseConnector(session)
            val manager = leaseManager(connector, idleTtlMillis = 60_000)

            val connectedEvents = mutableListOf<SshLeaseStateEvent>()
            val collectJob = backgroundScope.launch {
                manager.stateEvents.collect { event ->
                    if (event.state == SshLeaseConnectionState.Connected &&
                        event.key == TARGET.leaseKey
                    ) {
                        connectedEvents.add(event)
                    }
                }
            }
            runCurrent()

            // First connect → one Connected. Release → Idle (transport stays up).
            manager.acquire(TARGET).getOrThrow().release()
            runCurrent()
            // Reacquire the warm/idle transport: no fresh dial, no new up-edge.
            val reacquired = manager.acquire(TARGET).getOrThrow()
            runCurrent()

            assertEquals("the warm transport was reused, never re-dialled", 1, connector.connectCount)
            assertSame(session, reacquired.session)
            assertEquals(
                "an Idle→reacquire of a still-up transport must NOT emit another transport.up (#845)",
                1,
                connectedEvents.size,
            )

            reacquired.release()
            collectJob.cancel()
        }

    @Test
    fun `a real reconnect after a Closed drop re-emits Connected (the heal up-edge survives dedupe) - #845`() =
        runTest {
            // The dedupe must NOT swallow the genuine heal up-edge: after the
            // transport actually closes (Down), the next connect IS a real
            // transport-up the controller's reconnect ladder needs.
            val first = FakeSshSession()
            val second = FakeSshSession()
            val connector = QueueLeaseConnector(first, second)
            val manager = leaseManager(connector, idleTtlMillis = 60_000)

            val connectedEvents = mutableListOf<SshLeaseStateEvent>()
            val collectJob = backgroundScope.launch {
                manager.stateEvents.collect { event ->
                    if (event.state == SshLeaseConnectionState.Connected &&
                        event.key == TARGET.leaseKey
                    ) {
                        connectedEvents.add(event)
                    }
                }
            }
            runCurrent()

            // First connect → one Connected (one up-edge).
            manager.acquire(TARGET).getOrThrow().release()
            runCurrent()
            // The transport genuinely drops (the entry becomes disconnected): the
            // next acquire must dial a FRESH transport and re-emit Connected.
            first.connected = false
            val reconnected = manager.acquire(TARGET).getOrThrow()
            runCurrent()

            assertEquals("the dead transport forced a fresh dial", 2, connector.connectCount)
            assertSame(second, reconnected.session)
            assertEquals(
                "a real reconnect after a transport drop must re-emit Connected (the heal up-edge)",
                2,
                connectedEvents.size,
            )

            reconnected.release()
            collectJob.cancel()
        }

    @Test
    fun `disconnected idle entry is replaced on next acquire`() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val connector = QueueLeaseConnector(first, second)
        val manager = leaseManager(connector, idleTtlMillis = 60_000)

        manager.acquire(TARGET).getOrThrow().release()
        first.connected = false
        val lease = manager.acquire(TARGET).getOrThrow()

        assertEquals(2, connector.connectCount)
        assertSame(second, lease.session)
    }

    @Test
    fun `release from replaced disconnected lease does not mutate active replacement`() = runTest {
        val first = FakeSshSession()
        val second = FakeSshSession()
        val connector = QueueLeaseConnector(first, second)
        val manager = leaseManager(connector, idleTtlMillis = 1_000)

        val staleLease = manager.acquire(TARGET).getOrThrow()
        first.connected = false
        val replacementLease = manager.acquire(TARGET).getOrThrow()

        staleLease.release()
        advanceTimeBy(1_000)
        runCurrent()

        assertEquals(2, connector.connectCount)
        assertSame(second, replacementLease.session)
        assertFalse(second.closed)

        replacementLease.release()
        advanceTimeBy(1_000)
        runCurrent()
        assertTrue(second.closed)
    }

    @Test
    fun `release completes ref count update when caller is cancelled`() = runTest {
        val releaseCanFinish = CompletableDeferred<Unit>()
        var startedReleaseCount = 0
        var completedReleaseCount = 0
        val lease = SshLease(
            key = TARGET.leaseKey,
            session = FakeSshSession(),
            isNewConnection = true,
            entryId = 1L,
        ) { _, _ ->
            startedReleaseCount += 1
            releaseCanFinish.await()
            completedReleaseCount += 1
        }

        val releaseJob = launch { lease.release() }
        runCurrent()

        assertEquals(1, startedReleaseCount)
        assertEquals(0, completedReleaseCount)

        releaseJob.cancel()
        runCurrent()
        releaseCanFinish.complete(Unit)
        releaseJob.join()

        assertEquals(1, completedReleaseCount)

        lease.release()
        assertEquals(1, startedReleaseCount)
        assertEquals(1, completedReleaseCount)
    }

    @Test
    fun `closing manager closes retained sessions and rejects new acquires`() = runTest {
        val session = FakeSshSession()
        val manager = leaseManager(QueueLeaseConnector(session), idleTtlMillis = 60_000)

        manager.acquire(TARGET).getOrThrow().release()
        manager.close()
        val result = manager.acquire(TARGET)

        assertTrue(session.closed)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SshLeaseManagerClosedException)
    }

    private fun TestScope.leaseManager(
        connector: SshLeaseConnector,
        idleTtlMillis: Long = 1_000,
        maxIdleLeases: Int = 2,
    ): SshLeaseManager =
        SshLeaseManager(
            connector = connector,
            scope = this,
            idleTtlMillis = idleTtlMillis,
            maxIdleLeases = maxIdleLeases,
            connectTimeoutContext = StandardTestDispatcher(testScheduler),
            nowMillis = { testScheduler.currentTime },
        )

    private class QueueLeaseConnector(
        private vararg val sessions: FakeSshSession,
    ) : SshLeaseConnector {
        var connectCount: Int = 0

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val next = sessions.getOrNull(connectCount)
                ?: error("unexpected connect $connectCount for ${target.leaseKey}")
            connectCount += 1
            return Result.success(next)
        }
    }

    /**
     * Issue #620: a connector whose every `connect` parks until the test
     * releases it, so a test can observe the in-flight window where a second
     * acquire for the same key would race a redundant handshake. Each queued
     * session resolves successfully; a `null` slot resolves as a connect
     * failure (used to exercise the awaiter-fallback path).
     */
    private class GatedLeaseConnector(
        private vararg val sessions: FakeSshSession?,
    ) : SshLeaseConnector {
        var startedConnects: Int = 0
        private val gates: ArrayDeque<CompletableDeferred<Unit>> = ArrayDeque()

        override suspend fun connect(target: SshLeaseTarget): Result<SshSession> {
            val index = startedConnects
            startedConnects += 1
            val gate = CompletableDeferred<Unit>()
            gates.addLast(gate)
            gate.await()
            val session = sessions.getOrNull(index)
                ?: return Result.failure(java.io.IOException("connect $index failed"))
            return Result.success(session)
        }

        fun releaseConnect() {
            val gate = gates.removeFirstOrNull()
                ?: error("no in-flight connect to release")
            gate.complete(Unit)
        }
    }

    private class FakeSshSession : SshSession {
        var closed: Boolean = false
        var connected: Boolean = true
        var closeCount: Int = 0

        override val isConnected: Boolean
            get() = connected && !closed

        override suspend fun exec(command: String): ExecResult =
            ExecResult(stdout = "", stderr = "", exitCode = 0)

        override fun tail(path: String, onLine: (String) -> Unit): Job =
            error("not used")

        override fun openLocalPortForward(
            remoteHost: String,
            remotePort: Int,
            localPort: Int,
        ): SshPortForward = error("not used")

        override fun startShell(): SshShell = object : SshShell {
            override val stdin = ByteArrayOutputStream()
            override val stdout = ByteArrayInputStream(ByteArray(0))
            override val stderr = ByteArrayInputStream(ByteArray(0))
            override fun close() = Unit
        }

        override suspend fun uploadFile(file: File, remotePath: String): String =
            error("not used")

        override suspend fun uploadStream(
            input: InputStream,
            length: Long,
            name: String,
            remotePath: String,
        ): String = error("not used")

        override fun close() {
            closeCount += 1
            closed = true
        }
    }

    private companion object {
        val TARGET: SshLeaseTarget = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "10.0.2.2",
                port = 2222,
                user = "testuser",
                credentialId = "/tmp/key-a",
            ),
            key = SshKey.Path(File("/tmp/key-a")),
        )

        val OTHER_TARGET: SshLeaseTarget = SshLeaseTarget(
            leaseKey = SshLeaseKey(
                host = "example.test",
                port = 22,
                user = "deploy",
                credentialId = "/tmp/key-b",
            ),
            key = SshKey.Path(File("/tmp/key-b")),
        )
    }
}
