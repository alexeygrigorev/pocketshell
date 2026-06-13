package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #741 (epic #657, Gap B): keep-alive dead-peer detection proof.
 *
 * Keep-alive dead-peer detection is config-only in `SshConnection.kt`
 * ([SshConnection.DEFAULT_KEEP_ALIVE_SECONDS] = 15s,
 * [SshConnection.DEFAULT_MAX_ALIVE_COUNT] = 4, and the
 * `KeepAliveProvider.KEEP_ALIVE` / `KeepAliveRunner` switch in
 * `createSshConfig()`): every interval sshj sends a `keepalive@openssh.com`
 * global request and waits for a reply; an unanswered request increments a
 * miss counter, and after `maxAliveCount` consecutive misses the transport is
 * torn down with `CONNECTION_LOST`. Until this round-2 audit there was NO test
 * that killed a real peer and asserted the connection NOTICES rather than
 * hanging indefinitely.
 *
 * This proof connects a REAL production [SshSession] through the Docker
 * `network-fault-proxy` (Toxiproxy forward on host port 2228 -> `agents:22`),
 * then BLACKHOLES the link with Toxiproxy's `timeout=0` toxic. That toxic keeps
 * the TCP socket established while silently dropping every byte in both
 * directions — a half-open / no-FIN dead peer, the exact failure mode that an
 * EOF-based detector misses and that only the keep-alive miss-counter can catch.
 *
 * The assertion is the whole point of the gap: while the link is blackholed,
 * sshj's `client.isConnected` lies (it returns `true` until the keep-alive
 * trips — see the note in `RealSshSession.tail`), so the test polls
 * [SshSession.isConnected] and asserts it FLIPS to `false` within the configured
 * keep-alive window plus a margin. If keep-alive were misconfigured (e.g. the
 * NAT-only `HEARTBEAT` provider, or `keepAliveInterval == 0` so the thread never
 * starts — issue #548), `isConnected` would stay `true` forever and this test
 * would time out: a dead peer that hangs indefinitely.
 *
 * Nightly-only: gated through [NetworkFaultProofBase.assumeNetworkFaultProofsEnabled]
 * (`pocketshellNetworkFaultProofs=true`, self-skips on CI), so it runs in the
 * nightly-extensive network-fault phase against fixtures the per-push gate never
 * brings up. No new fixture — reuses `network-fault-proxy` (2228) + the Toxiproxy
 * control API (8474) the nightly workflow already starts.
 */
@RunWith(AndroidJUnit4::class)
class KeepAliveDeadPeerDetectionE2eTest : NetworkFaultProofBase() {

    @Test
    fun blackholedPeerSurfacesAsDeadWithinKeepAliveWindow() = runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()

        // Bring the Toxiproxy forward (2228 -> agents:22) up with no toxics.
        toxiproxy().reset()
        waitForSshFixtureReady(SshKey.Pem(key), port = NETWORK_FAULT_SSH_PORT)

        // Connect a REAL production session THROUGH the proxy. The default
        // `keepAliveSeconds` is deliberately NOT overridden, so this exercises
        // the exact shipped keep-alive config (SshConnection.DEFAULT_KEEP_ALIVE_SECONDS
        // = 15s interval x SshConnection.DEFAULT_MAX_ALIVE_COUNT = 4 misses = 60s
        // window). Those production constants are `internal` to `core-ssh`, so the
        // mirrored values in this test's companion (PROD_KEEP_ALIVE_SECONDS /
        // PROD_MAX_ALIVE_COUNT) pin the asserted budget; if the production
        // window changes, the mirror should move with it.
        val session: SshSession = withTimeout(SESSION_CONNECT_TIMEOUT_MS) {
            SshConnection.connect(
                host = DEFAULT_HOST,
                port = NETWORK_FAULT_SSH_PORT,
                user = DEFAULT_USER,
                key = SshKey.Pem(key),
                knownHosts = KnownHostsPolicy.AcceptAll,
                timeoutMs = 15_000,
            ).getOrThrow()
        }

        try {
            // Sanity: the live session reports connected before the blackhole.
            assertTrue("expected the session connected before blackhole", session.isConnected)

            // Make the peer go dead: keep the socket established, drop all bytes.
            // The keep-alive global requests now go unanswered. clearToxics()
            // first so a leftover toxic from an aborted prior run cannot turn
            // addBlackhole into a "toxic already exists" 409.
            val blackholeStart = SystemClock.elapsedRealtime()
            toxiproxy().clearToxics()
            toxiproxy().addBlackhole()

            // Poll isConnected until keep-alive declares CONNECTION_LOST. The
            // deadline is the production window (DEFAULT_KEEP_ALIVE_SECONDS x
            // DEFAULT_MAX_ALIVE_COUNT) plus a margin for the first-request phase
            // and CI scheduling jitter. If keep-alive never trips, the loop runs
            // to the deadline and the assertion below fails with the elapsed time
            // — i.e. "the connection hangs indefinitely", the exact regression
            // this proof guards.
            val configuredWindowMs =
                PROD_KEEP_ALIVE_SECONDS.toLong() * PROD_MAX_ALIVE_COUNT.toLong() * 1_000L
            val deadlineMs = SystemClock.elapsedRealtime() + DEAD_PEER_DETECT_DEADLINE_MS
            var detectedDeadMs = -1L
            while (SystemClock.elapsedRealtime() < deadlineMs) {
                if (!session.isConnected) {
                    detectedDeadMs = SystemClock.elapsedRealtime() - blackholeStart
                    break
                }
                SystemClock.sleep(500)
            }

            recordTiming("keepalive_configured_window_ms", configuredWindowMs)
            recordTiming("keepalive_detect_deadline_ms", DEAD_PEER_DETECT_DEADLINE_MS)
            recordTiming("keepalive_dead_peer_detected_ms", detectedDeadMs)

            assertTrue(
                "expected keep-alive to surface the blackholed peer as dead within " +
                    "${DEAD_PEER_DETECT_DEADLINE_MS}ms (configured window " +
                    "${configuredWindowMs}ms); session.isConnected was still true at the " +
                    "deadline — the connection hung indefinitely instead of detecting the " +
                    "dead peer",
                detectedDeadMs >= 0,
            )
            assertFalse(
                "expected session.isConnected to be false after keep-alive detection",
                session.isConnected,
            )

            writeSummary(
                testName = "KeepAliveDeadPeerDetectionE2eTest",
                lines = listOf(
                    "failure=toxiproxy timeout toxic timeout=0 (blackhole) on upstream/downstream",
                    "keep_alive_seconds=$PROD_KEEP_ALIVE_SECONDS",
                    "max_alive_count=$PROD_MAX_ALIVE_COUNT",
                    "configured_window_ms=$configuredWindowMs",
                    "detect_deadline_ms=$DEAD_PEER_DETECT_DEADLINE_MS",
                    "dead_peer_detected_ms=$detectedDeadMs",
                ),
            )
        } finally {
            runCatching { session.close() }
        }
    }

    private companion object {
        /**
         * Mirror of `SshConnection.DEFAULT_KEEP_ALIVE_SECONDS` (the keepalive
         * interval). That production constant is `internal` to `core-ssh`, so it
         * cannot be referenced from this `:app` androidTest. Keep this in sync.
         */
        const val PROD_KEEP_ALIVE_SECONDS: Int = 15

        /**
         * Mirror of `SshConnection.DEFAULT_MAX_ALIVE_COUNT` (consecutive missed
         * keepalive replies tolerated before `CONNECTION_LOST`). `internal` to
         * `core-ssh`; keep in sync. 15s x 4 = the 60s configured window.
         */
        const val PROD_MAX_ALIVE_COUNT: Int = 4

        /** Generous connect window — connect+auth through the proxy before any toxic. */
        const val SESSION_CONNECT_TIMEOUT_MS: Long = 30_000L

        /**
         * Deadline for the dead-peer signal to appear after the blackhole.
         *
         * The production window is 60s (15s x 4). The detector cannot fire
         * before that window, and the first keep-alive request can land up to one
         * full interval (15s) after connect, so the worst-case detection time is
         * ~75s. We add headroom for the swiftshader CI emulator sharing two cores
         * with a parallel Docker `agents` container (the same load that pushed the
         * terminal round-trip ceilings to 180s in [TerminalTestTimeouts]). 150s
         * leaves a wide margin above the ~75s worst case while still failing fast
         * if keep-alive never trips (the connection would otherwise hang forever).
         */
        const val DEAD_PEER_DETECT_DEADLINE_MS: Long = 150_000L
    }
}
