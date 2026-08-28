package com.pocketshell.app.tmux

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Issue #2389 — the post-recovery terminal-viewport cohort
 * (`NatIdleMappingSurvivalE2eTest` + `PushResumeDeadSocketMainResponsiveE2eTest`,
 * both dying at `captureViewToBitmap(...) viewFound=false`).
 *
 * Root cause (measured on emulator + toxiproxy, issue #2389): while the surface
 * holds the recovery ("Attaching…", `terminalHeld = true`) the screen
 * intentionally unmounts the Termux `AndroidView`; the view model reaches
 * `Connected` BEFORE the screen recomposes and re-mounts it (~570–610 ms idle,
 * far longer on a loaded box). Both proofs captured the authoritative viewport
 * ~120–150 ms after `waitForConnected(...)`, i.e. inside that window.
 *
 * These per-push JVM assertions pin the two halves of the fix that a connected
 * nightly-only proof cannot pin for the PR gate:
 *
 *  1. The product mount contract that makes the viewport come back at all — a
 *     recovered `Connected` pane with panes must re-mount the pager even while
 *     the reveal fusion is still held ([shouldKeepTerminalMounted]). If that
 *     regressed, the viewport would never return and the e2e wait would time out.
 *  2. The harness contract: neither proof may capture its post-recovery viewport
 *     without first awaiting the restore through the shared, hard-failing
 *     [com.pocketshell.app.proof.RecoveredTerminalViewport] gate — and that gate
 *     must stay a bounded HARD failure, never a skip/soft-pass.
 *
 * Assertion (2) is a source contract on purpose (the same pattern as
 * [Issue2357KeepTerminalMountedTest.sessionScreenCallsTheKeepMountedHelper]):
 * the proofs are toxiproxy-gated nightly classes, so nothing in the per-push lane
 * can execute them, and a future edit that re-inlines the racy
 * "capture straight after Connected" shape would otherwise silently resurrect the
 * whole `#2135` cohort.
 */
class Issue2389PostRecoveryViewportGateTest {

    @Test
    fun recoveredConnectedPaneRemountsTheTerminalEvenWhileTheRevealIsHeld() {
        // The post-recovery tuple observed on the emulator right after the VM
        // flips back to Connected: panes are back, the connection is live, the
        // reveal fusion can still be held for a frame or two.
        val remounts = shouldKeepTerminalMounted(
            terminalHeld = true,
            deferTerminalAttachForSwap = false,
            hasPanes = true,
            sessionLive = true,
        )
        assertTrue(
            "REGRESSION (#2389/#2135): after a fault-recovery returns to Connected " +
                "the terminal pager must re-mount, or the post-recovery viewport " +
                "never comes back and the recovery proofs red on viewFound=false",
            remounts,
        )
    }

    @Test
    fun heldSurfaceWithoutALiveConnectionStillUnmounts() {
        // Vacuity guard: the gate above is not "always true". During the recovery
        // hold itself (not yet Connected) the terminal IS unmounted — which is
        // exactly why the proofs must WAIT instead of capturing immediately.
        assertFalse(
            "the recovery hold must still unmount the terminal (that is the " +
                "window the #2135 captures were racing); if this is true the " +
                "restore-wait assertion above proves nothing",
            shouldKeepTerminalMounted(
                terminalHeld = true,
                deferTerminalAttachForSwap = false,
                hasPanes = true,
                sessionLive = false,
            ),
        )
    }

    @Test
    fun bothRecoveryProofsAwaitTheRestoredViewportBeforeCapturing() {
        val natIdle = source(
            "app/src/androidTest/java/com/pocketshell/app/proof/" +
                "NatIdleMappingSurvivalE2eTest.kt",
        )
        val pushResume = source(
            "app/src/androidTest/java/com/pocketshell/app/proof/" +
                "PushResumeDeadSocketMainResponsiveE2eTest.kt",
        )
        for ((name, body) in listOf("NatIdle" to natIdle, "PushResume" to pushResume)) {
            assertTrue(
                "$name must await the restored viewport through the shared #2389 " +
                    "gate before its post-recovery capture, or it races the surface " +
                    "hold again (the #2135 viewFound=false cohort)",
                body.contains("RecoveredTerminalViewport.awaitRestored("),
            )
        }
        assertTrue(
            "NatIdle must await the restore BEFORE capturing the recovered viewport",
            natIdle.indexOf("RecoveredTerminalViewport.awaitRestored(") <
                natIdle.indexOf("captureViewport(\"issue1063-recover-02-recovered\")"),
        )
        assertTrue(
            "PushResume must await the restore BEFORE capturing the settled viewport",
            pushResume.indexOf("RecoveredTerminalViewport.awaitRestored(") <
                pushResume.indexOf("captureViewport(\"issue1139-03-settled\")"),
        )
    }

    @Test
    fun theRestoreGateStaysABoundedHardFailure() {
        val gate = source(
            "app/src/androidTest/java/com/pocketshell/app/proof/RecoveredTerminalViewport.kt",
        )
        assertTrue(
            "the restore gate must hard-fail when the viewport never comes back " +
                "(a missing post-recovery terminal viewport is the #2135 defect, " +
                "not something to wait out silently)",
            gate.contains("throw AssertionError("),
        )
        assertTrue(
            "the restore gate must stay BOUNDED so a permanently held surface reds " +
                "instead of hanging the proof",
            gate.contains("RESTORE_BUDGET_MS"),
        )
        assertFalse(
            "the restore gate must never downgrade a missing viewport to an " +
                "assume/skip",
            gate.contains("Assume.assume") || gate.contains("assumeTrue("),
        )
        assertTrue(
            "the restore gate must require a LIVE terminal (session + emulator " +
                "bound), not merely an attached empty view",
            gate.contains("currentSession != null") && gate.contains("mEmulator != null"),
        )
    }

    private fun source(relativePath: String): String {
        val userDir = checkNotNull(System.getProperty("user.dir")) { "user.dir is unset" }
        var cursor = File(userDir).absoluteFile
        while (true) {
            val candidate = File(cursor, relativePath)
            if (candidate.isFile) return candidate.readText()
            cursor = cursor.parentFile ?: break
        }
        error("Cannot locate $relativePath from $userDir")
    }
}
