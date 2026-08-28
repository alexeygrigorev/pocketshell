package com.pocketshell.app.proof

import android.os.SystemClock
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.pocketshell.app.MainActivity
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #2380 — END-TO-END guard for the shared network-fault attach navigation,
 * over the REAL Docker fixture the phase-2 proofs use.
 *
 * The nightly phase-2 network-fault gate went VACUOUS (not merely red) on 2026-08-27:
 * every proof died in [NetworkFaultProofBase.openSessionFromList] with
 * `Timed out ... waiting for expanded folder ::untracked:: showing session row ...
 * after 166-174 tap(s)`, so no Toxiproxy fault was injected at all. The superseded
 * selector resolved the target folder by taking the FIRST hard-coded candidate
 * (`::untracked::`, `/home/testuser`, `~`) whose folder row MERELY EXISTED — a
 * selector that is only correct while every seeded session happens to land in the
 * first candidate.
 *
 * This test creates the non-happy fixture state the bug needs and that no other proof
 * creates: the seeded target session lives in its OWN project folder, while sibling
 * sessions occupy `/home/testuser` (the old candidate #2) and a second project folder.
 * The old selector picks a folder that does not hold the target and dies in setup; the
 * navigator must open the TARGET session — proved from inside the attached pane by
 * `tmux display-message -p '#S'`, not merely by "some terminal appeared".
 */
// CI_JOURNEY_SUITE_JUSTIFIED: NetworkFaultProofBase toxiproxy proof; gated by
// assumeNetworkFaultProofsEnabled() (self-skips on CI since tests.yml does not
// start network-fault-proxy:2228), so wiring it into ci-journey-suite.sh would only
// produce a vacuous CI skip. Its durable gate is the nightly suite's
// NETWORK_FAULT_CLASSES (scripts/nightly-extensive-suite.sh), alongside the very
// proofs whose shared setup this guards. The Docker-free per-push half of the same
// red->green is com.pocketshell.app.proof.FolderSessionRowNavigatorTest, which IS
// wired into scripts/ci-journey-suite.sh.
@RunWith(AndroidJUnit4::class)
class AttachNavigationMultiFolderE2eTest : NetworkFaultProofBase() {

    @Test
    fun attachOpensTheSeededSessionWhenSiblingProjectFoldersExist() { runBlocking {
        assumeNetworkFaultProofsEnabled()

        val key = readFixtureKey()
        val marker = "nav${System.currentTimeMillis().toString(36).takeLast(5)}"
        val targetSession = "issue2380-target-$marker"
        val siblingSession = "issue2380-sibling-$marker"
        val homeSession = "issue2380-home-$marker"
        val hostName = "Issue2380 Nav $marker"
        val targetCwd = "/home/testuser/issue2380-$marker/target"
        val siblingCwd = "/home/testuser/issue2380-$marker/sibling"

        // The target session lives in its own project folder ...
        prepareProxyAndRemoteSession(
            key = key,
            sessionName = targetSession,
            readyText = "ISSUE2380-TARGET-READY-$marker",
            cwd = targetCwd,
        )
        // ... alongside a SECOND real project folder ...
        seedExtraSession(
            key = key,
            sessionName = siblingSession,
            readyText = "ISSUE2380-SIBLING-READY-$marker",
            cwd = siblingCwd,
        )
        // ... and a session in the SSH login directory, which is the folder the
        // superseded candidate list latched onto whenever `::untracked::` was absent.
        seedExtraSession(
            key = key,
            sessionName = homeSession,
            readyText = "ISSUE2380-HOME-READY-$marker",
        )

        val hostRowTag = seedNetworkFaultHost(key, hostName)

        launchedActivity = ActivityScenario.launch(MainActivity::class.java)
        val attachStart = SystemClock.elapsedRealtime()
        attachToSession(hostRowTag, hostName, targetSession)
        recordTiming("multi_folder_attach_ms", SystemClock.elapsedRealtime() - attachStart)

        // Identity proof: ask the ATTACHED pane which tmux session it is in. A wrong
        // folder row (or a wrong session row) would answer with a sibling name.
        sendCommandThroughTerminalInput(
            "printf 'ATTACHED-%s\\n' \"\$(tmux display-message -p '#S')\"",
            "multi-folder-attach-identity",
        )
        waitForVisibleTerminalText("multi-folder-attach-identity", timeoutMillis = 25_000L) {
            "ATTACHED-$targetSession" in it
        }
        // ... and that the pane really is the one seeded in the target folder.
        sendCommandThroughTerminalInput("pwd", "multi-folder-attach-cwd")
        waitForVisibleTerminalText("multi-folder-attach-cwd", timeoutMillis = 25_000L) {
            targetCwd in it
        }

        writeSummary(
            testName = "AttachNavigationMultiFolderE2eTest",
            lines = listOf(
                "target_session=$targetSession",
                "target_cwd=$targetCwd",
                "sibling_session=$siblingSession",
                "sibling_cwd=$siblingCwd",
                "home_session=$homeSession",
                "topology=2 project folders + the /home/testuser login folder",
                "expectation=attach resolves the folder that CONTAINS the target session",
                "attached_session_verified_by=tmux display-message -p '#S'",
            ),
        )
    } }
}
