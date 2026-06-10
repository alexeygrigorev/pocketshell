package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #621 / #634 / #636 (Slice 4): opening / switching a tmux session over a
 * warm SSH lease whose transport went silently dead must NOT strand the user on
 * a Disconnected band + manual Reconnect. The first `tmux -CC` open /
 * `list-panes` over the corpse EOFs; the fix evicts the poisoned lease and
 * transparently re-dials a fresh transport + reattaches.
 *
 * The maintainer's v0.3.30 dogfood hit this on EVERY session switch: each switch
 * produced `TmuxClientException: failed to open` / `Broken transport; encountered
 * EOF` -> Disconnected, needing a manual Reconnect tap.
 *
 * This proof drives the real toxiproxy `network-fault-proxy` harness:
 *
 *  1. Attach to session A over the proxy; confirm it is live.
 *  2. Clean-cut the link (`proxy.disable()`) so the warm SSH lease the folder
 *     screen keeps for the host goes silently dead — sshj's `isConnected` keeps
 *     reporting true until its 60s keepalive trips.
 *  3. Navigate back to the session list and OPEN session B while the lease is
 *     still stale (re-enabling the link first so a FRESH dial can actually
 *     succeed — modelling the metro-tunnel-just-returned case).
 *  4. Assert session B attaches WITHOUT a Disconnected band ever appearing and
 *     WITHOUT a manual Reconnect tap — the stale-lease heal is transparent.
 */
@RunWith(AndroidJUnit4::class)
class StaleLeaseSwitchRecoveryE2eTest : NetworkFaultProofBase() {

    @Test
    fun openingSecondSessionOverStaleLeaseAutoRecoversWithoutDisconnectBand() = runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "sl${System.currentTimeMillis().toString(36).takeLast(5)}"
        val sessionA = "issue621-stale-a-$marker"
        val sessionB = "issue621-stale-b-$marker"
        val hostName = "Issue621 Stale $marker"

        // Seed both sessions on the remote behind the proxy.
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = sessionA,
            readyText = "ISSUE621-A-READY-$marker",
        )
        seedExtraSession(key, sessionB, "ISSUE621-B-READY-$marker")
        val hostRowTag = seedNetworkFaultHost(key, hostName)

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)

        // (1) Attach to session A; confirm live.
        attachToSession(hostRowTag, hostName, sessionA)
        sendCommandThroughTerminalInput("printf 'A-LIVE-$marker\\n'", "session-a-live")
        waitForVisibleTerminalText("session-a-live") { "A-LIVE-$marker" in it }

        // (2) Clean-cut the link so the warm host lease's transport dies
        // silently. Hold long enough for the socket to be a corpse, but the
        // app keeps the lease (sshj still reports isConnected within keepalive).
        val proxy = toxiproxy()
        val cutStart = SystemClock.elapsedRealtime()
        proxy.disable()
        SystemClock.sleep(STALE_WINDOW_MS)

        // (3) Navigate back to the session list while the lease is stale, then
        // restore the link so a FRESH re-dial can succeed (metro tunnel just
        // returned). Open session B from the list: the open path reuses the
        // warm-but-dead lease, EOFs on the `tmux -CC` open / `list-panes`, and
        // must heal.
        launchedActivity?.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        SystemClock.sleep(500)
        proxy.enable()
        recordTiming("stale_lease_cut_total_ms", SystemClock.elapsedRealtime() - cutStart)

        val openStart = SystemClock.elapsedRealtime()
        openSessionFromList(hostName, sessionB)
        recordTiming("session_b_open_over_stale_lease_ms", SystemClock.elapsedRealtime() - openStart)

        // (4) Session B must attach with NO manual Reconnect and the heal must
        // never have left a Disconnected band on screen by the time content is
        // live. Prove the session is usable: a command echoes back.
        sendCommandThroughTerminalInput("printf 'B-LIVE-$marker\\n'", "session-b-live")
        waitForVisibleTerminalText("session-b-live") { "B-LIVE-$marker" in it }
        assertNoDisconnectBand("session-b-after-stale-open")
        waitForClientCountAtMost(key, sessionB, max = 1, label = "session B single client after heal")

        writeSummary(
            testName = "StaleLeaseSwitchRecoveryE2eTest",
            lines = listOf(
                "sessionA=$sessionA",
                "sessionB=$sessionB",
                "marker=$marker",
                "scenario=attach A, clean-cut link to stale the warm lease, open B over the corpse",
                "expectation=B auto-recovers (fresh re-dial), no Disconnected band, no manual Reconnect",
            ),
        )
        Unit
    }

    private companion object {
        // Long enough that the clean socket drop has fully killed the warm
        // transport, but well under sshj's 60s keepalive teardown so the lease
        // is still pooled as "connected" when B's open reuses it.
        const val STALE_WINDOW_MS: Long = 6_000L
    }
}
