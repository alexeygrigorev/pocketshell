package com.pocketshell.app.scripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Runs the release-gate / walkthrough / packaging shell harnesses under
 * `./gradlew test` — the `Unit tests` required check (issue #1853).
 *
 * WHY this class exists: `tests/scripts/` held 16 harnesses. Four of them
 * (the AVD-lock family) were wired into [AvdLockScriptTest] by #1657. The other
 * twelve were wired into NOTHING — no workflow, no Gradle task, no script, no
 * doc telling a human to run them — so they had never executed, ever. A test
 * that no gate runs is not a test (#1640/#1646/#1851); this class is the gate
 * for the remaining twelve.
 *
 * What "never ran" had already cost: all twelve were dead on arrival when first
 * executed. Four failed outright. Three of those had rotted the same way — the
 * harness `sed`-extracts a helper region out of a production release script and
 * sources it, and the production region had since grown references to variables
 * (`RUN_ID`, `TEST_PACKAGE`, `APP_WALKTHROUGH_GL_REBOOT_*`) that the harness
 * never declared, so the sourced helper died at `unbound variable` before
 * asserting anything. The fourth stubbed `ps` as a constant, which cannot model
 * a process that does not exist and then does. Those are fixture gaps, repaired
 * in the same change; no production script's behaviour was altered.
 *
 * The harnesses are JVM-free, emulator-free, and Docker-daemon-free (fake
 * `adb`/`docker`/`emulator`/`ps`, sandboxed temp dirs) and run in ~40s total.
 * The two that reach `scripts/lib/scope-run.sh` degrade to a bare uncapped run
 * when no user systemd manager is reachable and `CI` is set, which is exactly
 * the hosted-runner shape.
 */
class ReleaseGateScriptTest {
    private val projectRoot: Path = findProjectRoot()

    /** `scripts/lib/app-version.sh` reads `versionName` out of the Gradle DSL. */
    @Test
    fun appVersionHelperParsesVersionNameFromGradle() {
        runShellHarness(
            relativePath = "tests/scripts/app-version-test.sh",
            expectedPassLine = "PASS: app version helper and pocketshell fixture version (6 cases)",
            timeoutSeconds = 60,
        )
    }

    /** Every `scripts/phone-walkthrough.sh` scenario must dispatch to a real target. */
    @Test
    fun phoneWalkthroughDispatchesEveryScenario() {
        runShellHarness(
            relativePath = "tests/scripts/phone-walkthrough-dispatch-test.sh",
            expectedPassLine = "PASS: phone walkthrough dispatch handlers (6 cases)",
            timeoutSeconds = 60,
        )
    }

    /** The pre-release gate pins the host-CLI/APK version lockstep (release lockstep). */
    @Test
    fun preReleaseGatePinsDockerAgentsPocketshellVersion() {
        runShellHarness(
            relativePath = "tests/scripts/pre-release-version-test.sh",
            expectedPassLine = "PASS: pre-release Docker fixture version exact check (2 cases)",
            timeoutSeconds = 60,
        )
    }

    /** #548 grace proof: the reconnect script must keep selecting the load-bearing test. */
    @Test
    fun reconnectAppSwitchScriptKeepsItsLoadBearingSelector() {
        runShellHarness(
            relativePath = "tests/scripts/reconnect-app-switch-test.sh",
            expectedPassLine = "PASS: reconnect app-switch harness (4 cases)",
            timeoutSeconds = 60,
        )
    }

    /** `scripts/update-install-helper.sh` argument construction against a fake `adb`. */
    @Test
    fun updateInstallHelperBuildsTheRightAdbInvocation() {
        runShellHarness(
            relativePath = "tests/scripts/update-install-helper-test.sh",
            expectedPassLine = "PASS: update install helper (3 cases)",
            timeoutSeconds = 60,
        )
    }

    /**
     * Emulator readiness in `scripts/pre-release-confidence-gate.sh`: it must
     * start a managed emulator, and must NOT mistake its own generated shell
     * command for a running emulator process (the self-match case).
     */
    @Test
    fun preReleaseEmulatorReadinessStartsManagedAndRejectsSelfMatch() {
        runShellHarness(
            relativePath = "tests/scripts/pre-release-emulator-readiness-test.sh",
            expectedPassLine = "PASS: pre-release emulator readiness helper (14 cases)",
            timeoutSeconds = 180,
        )
    }

    /** App-walkthrough instrumentation retry / transport recovery in the pre-release gate. */
    @Test
    fun preReleaseGateRetriesInstrumentationAndRecoversTransport() {
        runShellHarness(
            relativePath = "tests/scripts/pre-release-gate-retry-test.sh",
            expectedPassLine = "PASS: pre-release gate retry helper (4 cases)",
            timeoutSeconds = 300,
        )
    }

    /** Real-agent release-gate retry: success-after-retry and exhausted-retry both. */
    @Test
    fun realAgentReleaseGateRetryHandlesSuccessAndExhaustion() {
        runShellHarness(
            relativePath = "tests/scripts/release-gate-retry-test.sh",
            expectedPassLine = "PASS: release gate retry helper (6 cases)",
            timeoutSeconds = 120,
        )
    }

    /** The long-running release-gate hold's retry + evidence handling. */
    @Test
    fun longRunningReleaseGateRetryHandlesSuccessAndExhaustion() {
        runShellHarness(
            relativePath = "tests/scripts/long-running-release-gate-retry-test.sh",
            expectedPassLine = "PASS: long-running release gate retry helper (22 cases)",
            timeoutSeconds = 180,
        )
    }

    /** `scripts/start-local-avd.sh` boot flags, PASS summary, and accel diagnostics. */
    @Test
    fun startLocalAvdStartsTheEmulatorAndRecordsPass() {
        runShellHarness(
            relativePath = "tests/scripts/start-local-avd-test.sh",
            expectedPassLine = "PASS: start-local-avd helper (6 cases)",
            timeoutSeconds = 120,
        )
    }

    /** #1c4ab315: the terminal workbench must FAIL on exhausted instrumentation retries. */
    @Test
    fun terminalWorkbenchFailsOnExhaustedRetriesAndPassesOnRecovery() {
        runShellHarness(
            relativePath = "tests/scripts/terminal-workbench-retry-test.sh",
            expectedPassLine = "PASS: terminal workbench retry helper (19 cases)",
            timeoutSeconds = 120,
        )
    }

    /** Visual-audit screenshot capture retry in `scripts/capture-walkthrough-screenshots.sh`. */
    @Test
    fun walkthroughVisualCaptureRetryHandlesSuccessAndExhaustion() {
        runShellHarness(
            relativePath = "tests/scripts/walkthrough-visual-retry-test.sh",
            expectedPassLine = "PASS: walkthrough visual retry helper (12 cases)",
            timeoutSeconds = 300,
        )
    }

    /**
     * Same hermetic invocation contract as [AvdLockScriptTest] (issue #1702):
     * `scripts/pre-release-confidence-gate.sh` acquires the machine AVD lock and
     * EXPORTS its acquire state before running `assembleDebug check` -> this
     * suite, so an inherited `POCKETSHELL_AVD_LOCK_*` would short-circuit a
     * harness into self-contending with the gate's own lock. Scrub that state and
     * sandbox the lock dir + TMPDIR per run so no case can reach the real
     * `$HOME/.cache/pocketshell/avd-locks/`.
     */
    private fun runShellHarness(
        relativePath: String,
        expectedPassLine: String,
        timeoutSeconds: Long,
    ) {
        val script = projectRoot.resolve(relativePath)
        assertTrue("harness is missing: $relativePath", script.toFile().exists())

        val sandbox = Files.createTempDirectory("pocketshell-release-gate-script-test").toFile()
        sandbox.deleteOnExit()

        val builder = ProcessBuilder("bash", script.toString())
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
        builder.environment().apply {
            keys.removeAll { it.startsWith("POCKETSHELL_AVD_LOCK") }
            keys.removeAll { it.startsWith("POCKETSHELL_POOL") }
            keys.removeAll { it.startsWith("POCKETSHELL_TOXIPROXY") }
            put("POCKETSHELL_AVD_LOCK_DIR", sandbox.resolve("locks").toString())
            put("TMPDIR", sandbox.toString())
        }
        val process = builder.start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
        }
        assertTrue("harness timed out after ${timeoutSeconds}s: $relativePath\n$output", completed)
        assertEquals("harness failed: $relativePath\n$output", 0, process.exitValue())
        // Issue #2113: `exit == 0` alone is the vacuous green process.md catalogues —
        // an early `exit 0` after the harness's FIRST case left every one of these
        // twelve tests passing, having verified nothing (measured: the
        // walkthrough-visual harness dropped from 18.1s to 0.029s and stayed green).
        // Each harness now counts its cases and prints the total; asserting the exact
        // count line is what makes this assertion about behaviour rather than about
        // bash's exit status. Carried over from the sibling `DiskPreflightScriptTest`.
        assertTrue(
            "harness did not report its case count: $relativePath\n" +
                "expected line: $expectedPassLine\n$output",
            output.contains(expectedPassLine),
        )
    }

    private fun findProjectRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (dir.resolve("scripts/lib/avd-lock.sh").toFile().exists()) {
                return dir
            }
            dir = dir.parent
        }
        error("Could not locate scripts/lib/avd-lock.sh from user.dir=${System.getProperty("user.dir")}")
    }
}
