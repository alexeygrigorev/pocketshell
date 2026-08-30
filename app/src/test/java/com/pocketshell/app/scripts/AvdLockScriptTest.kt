package com.pocketshell.app.scripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Runs the AVD-lock shell harnesses under `./gradlew test` — the `Unit tests`
 * required check (issue #1657).
 *
 * WHY a JVM test drives bash: `tests/scripts/avd-lock-*.sh` and
 * `tests/scripts/avd-pool-test.sh` existed but were wired into NO lane, so
 * nothing ever ran them. `avd-pool-test.sh` had been failing on `main` for
 * however long it took `connected-test.sh` to grow its `agents-pool.sh` (#724)
 * / `scope-run.sh` (#730) sources — the sandbox never copied them, every case
 * died at `source: No such file or directory`, and no one saw it. A test that
 * no gate runs is not a test (#1640/#1646); this class is the gate.
 *
 * The harnesses are JVM-free, emulator-free, and Docker-daemon-free (fake
 * `adb`/`docker`, stub `gradlew`, sandboxed lock dirs), and run in seconds.
 * Locks are provable with two processes and a lock file — racing a real AVD to
 * test the lock that protects it would corrupt whatever sibling lane is on it.
 */
class AvdLockScriptTest {
    private val projectRoot: Path = findProjectRoot()

    /**
     * THE #1657 regression: the AVD lock file used to be derived from the
     * *worktree* root, so every checkout got its own lock and `flock`
     * serialised nothing — two agents ran on the one emulator concurrently
     * while `process.md` promised since #672 that this lock made that safe.
     *
     * Covers both directions, because either alone is a bug: the same emulator
     * from two worktrees must CONTEND, and distinct emulators must STILL run
     * CONCURRENTLY (a global serialisation would queue every #724 pool lane
     * behind every other — worse than the bug it fixes).
     */
    @Test
    fun avdLockIsSharedAcrossWorktreesAndStillLetsDistinctEmulatorsRunConcurrently() {
        runShellHarness(
            "tests/scripts/avd-lock-sharing-test.sh",
            expectedPassLine = "PASS: avd-lock cross-worktree sharing (issue #1657) (6 cases)",
            timeoutSeconds = 180,
        )
    }

    /** Lock acquire/release ownership: no leak to children, nested gates, `--help` fast path. */
    @Test
    fun avdLockHelperOwnershipHarnessPasses() {
        runShellHarness(
            "tests/scripts/avd-lock-test.sh",
            expectedPassLine = "PASS: avd-lock helper (4 cases)",
            timeoutSeconds = 120,
        )
    }

    /** #724 pool claim/release through the REAL `connected-test.sh --pool` wrapper. */
    @Test
    fun avdPoolClaimReleaseHarnessPasses() {
        runShellHarness(
            "tests/scripts/avd-pool-test.sh",
            expectedPassLine = "PASS: avd-pool claim/release (4 cases)",
            timeoutSeconds = 180,
        )
    }

    /** Hosted emulator jobs pin a real device; the fake pool harness must ignore that caller state. */
    @Test
    fun avdPoolHarnessIgnoresCallerAndroidSerial() {
        runShellHarness(
            "tests/scripts/avd-pool-test.sh",
            expectedPassLine = "PASS: avd-pool claim/release (4 cases)",
            timeoutSeconds = 180,
            environmentOverrides = mapOf("ANDROID_SERIAL" to "emulator-5554"),
        )
    }

    /** #1737: pool and legacy wrappers must share one serial-ownership domain. */
    @Test
    fun connectedTestPoolAndLegacyWrappersCannotMutateOneSerialConcurrently() {
        runShellHarness(
            "tests/scripts/connected-test-serial-ownership-test.sh",
            expectedPassLine = "PASS: connected-test per-serial ownership (issue #1737) (19 cases)",
            timeoutSeconds = 180,
        )
    }

    /**
     * #2421 STRUCTURAL GUARD, and the reason it lives in this class rather than
     * in the workflow: `tests.yml` is at its file-size-hygiene cap (a new step
     * pushes it under the required 1 KiB headroom and reddens
     * `scripts/check-file-size-hygiene.sh`), so the guard is wired into
     * `./gradlew test` — the same `Unit tests` required check `guards-static`
     * feeds — next to the harness it protects.
     *
     * WHAT IT PROTECTS. Every "the serial lock became free again" assertion in
     * `connected-test-serial-ownership-test.sh` must go through the bounded-retry
     * `wait_for_serial_flock_reclaim` oracle. Written as a single instantaneous
     * `flock -n "$serial_lock" true`, it loses a race against the SIGKILLed
     * wrapper's already-forked ownership probe and reddens CI for a scheduling
     * reason (#2421). #2085 converted ONE such site and left five behind — those
     * five became #2421 — so prose plainly does not hold this line. A seventh
     * SIGKILL case written with a bare one-shot probe now fails here, by file,
     * line and enclosing function.
     *
     * The guard's own `--self-test` mutates a COPY of the real harness 20 ways
     * (revert a converted site, add a new bare-probe case, degrade a retry into
     * an unbounded wait, delete an assertion, smuggle in an extra fixture probe,
     * delete the harness) and requires each mutant red — so this assertion is
     * about detection, not about a script that prints PASS unconditionally.
     *
     * Six of those mutants exist because the guard classifies FAIL-CLOSED: the
     * banned probe has more than one spelling, and a scanner that shrugs at what
     * it cannot parse reports "0 bare one-shot probe(s)" over a live regression.
     * `flock -nx` (bundled short options), `flock -w 0` (a zero-second wait) and
     * `flock --nonblo` (an abbreviation of the undocumented `--nonblocking`
     * alias) are all the same instantaneous probe as `flock -n` — each verified
     * against util-linux 2.39.3 to return rc=1 in 0.00s on a held lock — and are
     * rejected as such. A literal `flock -w <n>`, n > 0, stays allowed and is
     * named positively in the pass line. An unknown/ambiguous option, or a
     * `-w "$var"` timeout the guard cannot prove is non-zero, is a rejection
     * rather than a silent skip. The 20th mutant pins the exemption table:
     * one entry exempts exactly one probe, so a copy of the exempt line inside
     * its own function cannot ride the same key.
     *
     * The check count is asserted on both sides on purpose — the shell
     * `EXPECTED_SELFTEST_CHECKS` and this string must move together, and this
     * assertion is what catches a bump applied to only one of them.
     */
    @Test
    fun serialFlockReclaimAssertionsCannotRegressToSingleShotProbes() {
        val guardOutput = runShellHarness(
            "scripts/check-serial-flock-reclaim-probe.sh",
            expectedPassLine = "PASS: serial-flock reclaim-probe guard (#2421)",
            timeoutSeconds = 120,
        )
        // The two intentional lookalikes must be positively CLASSIFIED as
        // allowed, not merely unnoticed by the scan: `make_fake_flock`'s fake
        // `flock` payload, and the deliberately instantaneous negative
        // "the in-flight probe still HOLDS this serial" assertion (#2085).
        // "0 unclassifiable" is the fail-closed receipt: it says every flock
        // invocation in the harness was positively bucketed, not that the
        // scanner found nothing it recognised and called that a pass.
        listOf(
            "1 fixture probe(s)",
            "1 exempted negative held-lock assertion(s)",
            "0 unclassifiable flock invocation(s)",
            "0 bare one-shot probe(s)",
        ).forEach { fragment ->
            assertTrue(
                "reclaim-probe guard did not account for: $fragment\n$guardOutput",
                guardOutput.contains(fragment),
            )
        }

        runShellHarness(
            "scripts/check-serial-flock-reclaim-probe.sh",
            // The check count is pinned for the #2113 reason: an early `exit 0`
            // in the self-test reads exactly like a full mutation sweep.
            expectedPassLine =
                "PASS: #2421 serial-flock reclaim-probe guard self-test rejects " +
                    "every one-shot regression shape (20 checks)",
            timeoutSeconds = 300,
            arguments = listOf("--self-test"),
        )
    }

    /**
     * #2085: parallel JVM lanes must not share the user-systemd manager, temp
     * state, lock paths, or a SIGKILLed wrapper's short-lived ownership probe.
     * Keep both real-wrapper ordering directions in this concurrent check.
     */
    @Test
    fun sigkillDescendantHarnessIsHermeticAcrossConcurrentJvmInvocations() {
        val lanes = 4
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(lanes)
        val futures = (1..lanes).map {
            executor.submit<String> {
                start.await()
                runShellHarness(
                    "tests/scripts/connected-test-serial-ownership-test.sh",
                    // The four-case filter below, so the count pins THIS argument set.
                    expectedPassLine =
                        "PASS: connected-test per-serial ownership (issue #1737) (4 cases)",
                    timeoutSeconds = 120,
                    arguments = listOf(
                        "pool_then_legacy_serialises",
                        "legacy_then_pool_serialises",
                        "hard_killed_wrapper_leaves_no_descendant_flock",
                        "controlled_inflight_probe_does_not_masquerade_as_inherited_flock",
                    ),
                )
            }
        }

        try {
            start.countDown()
            futures.forEachIndexed { index, future ->
                val output = future.get(150, TimeUnit.SECONDS)
                assertTrue(
                    "lane ${index + 1} did not exercise the controlled descendant conflict:\n$output",
                    output.contains("CONTROLLED_FLOCK_CONFLICT"),
                )
            }
        } finally {
            futures.forEach { it.cancel(true) }
            executor.shutdownNow()
            assertTrue("concurrent harness executor did not stop", executor.awaitTermination(10, TimeUnit.SECONDS))
        }
    }

    private fun runShellHarness(
        relativePath: String,
        expectedPassLine: String,
        timeoutSeconds: Long,
        environmentOverrides: Map<String, String> = emptyMap(),
        arguments: List<String> = emptyList(),
    ): String {
        val script = projectRoot.resolve(relativePath)
        assertTrue("harness is missing: $relativePath", script.toFile().exists())

        // Hermetic invocation (issue #1702). `scripts/pre-release-confidence-gate.sh`
        // acquires the machine AVD lock and EXPORTS the acquire state
        // (POCKETSHELL_AVD_LOCK_ACQUIRED and friends) BEFORE running
        // `assembleDebug check` -> this suite. Inherited through gradle -> the test
        // worker -> bash, that state short-circuits `pocketshell_acquire_avd_lock`
        // so the harness self-contends with the gate's own lock. Scrub the inherited
        // acquire state, and sandbox the lock dir + TMPDIR into a fresh per-run temp
        // dir so no case can touch the real `$HOME/.cache/pocketshell/avd-locks/`.
        // (The harness scripts scrub the same state themselves too; this makes the
        // JVM boundary hermetic regardless of caller.)
        val lockSandbox = Files.createTempDirectory("pocketshell-avd-lock-test").toFile()
        val tempDir = lockSandbox.resolve("tmp").apply { mkdirs() }
        val outputFile = lockSandbox.resolve("harness-output.log")

        val builder = ProcessBuilder(listOf("bash", script.toString()) + arguments)
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
            .redirectOutput(outputFile)
        builder.environment().apply {
            keys.removeAll { it.startsWith("POCKETSHELL_") }
            remove("ANDROID_SERIAL")
            remove("ADB_SERIAL")
            put("POCKETSHELL_AVD_LOCK_DIR", lockSandbox.resolve("locks").toString())
            put("POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR", lockSandbox.resolve("gradle-output-locks").toString())
            put("POCKETSHELL_CONNECTED_TEST_LIFECYCLE_DIR", lockSandbox.resolve("lifecycle").toString())
            put("TMPDIR", tempDir.toString())
            putAll(environmentOverrides)
        }
        val process = builder.start()

        try {
            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
            if (!completed) {
                terminateProcessTree(process)
            }
            val output = outputFile.takeIf { it.exists() }?.readText().orEmpty()
            assertTrue("harness timed out after ${timeoutSeconds}s: $relativePath\n$output", completed)
            assertEquals("harness failed: $relativePath\n$output", 0, process.exitValue())
            // Issue #2113: `exit == 0` alone is the vacuous green process.md
            // catalogues — an early `exit 0` after the first case reads exactly
            // like a full pass. Each harness counts its cases and prints the
            // total; asserting the exact count line is what makes this assertion
            // about behaviour rather than about bash's exit status. Carried over
            // from the sibling `DiskPreflightScriptTest`.
            assertTrue(
                "harness did not report its case count: $relativePath\n" +
                    "expected line: $expectedPassLine\n$output",
                output.contains(expectedPassLine),
            )
            return output
        } finally {
            if (process.isAlive) {
                terminateProcessTree(process)
            }
            check(lockSandbox.deleteRecursively()) {
                "harness left its invocation temp directory behind: $lockSandbox"
            }
        }
    }

    private fun terminateProcessTree(process: Process) {
        val rootPid = processPid(process)
        val descendants = descendantPids(rootPid).asReversed()
        signalProcesses(descendants, "TERM")
        process.destroy()
        process.waitFor(2, TimeUnit.SECONDS)
        val survivors = (descendants + descendantPids(rootPid)).distinct().filter(::isRunningProcess)
        signalProcesses(survivors, "KILL")
        if (process.isAlive) process.destroyForcibly()
        process.waitFor(5, TimeUnit.SECONDS)
        val remaining = survivors.filter(::isRunningProcess)
        check(remaining.isEmpty() && !process.isAlive) {
            "harness process tree survived forced teardown: root=$rootPid descendants=$remaining"
        }
    }

    private fun processPid(process: Process): Long =
        (Process::class.java.getMethod("pid").invoke(process) as Number).toLong()

    private fun descendantPids(rootPid: Long): List<Long> {
        val ps = ProcessBuilder("ps", "-eo", "pid=,ppid=").start()
        val childrenByParent = ps.inputStream.bufferedReader().useLines { lines ->
            lines.mapNotNull { line ->
                val fields = line.trim().split(Regex("\\s+"))
                if (fields.size == 2) fields[0].toLongOrNull()?.let { it to fields[1].toLongOrNull() } else null
            }.filter { it.second != null }.groupBy({ it.second!! }, { it.first })
        }
        check(ps.waitFor(5, TimeUnit.SECONDS) && ps.exitValue() == 0) { "could not inventory harness descendants" }
        val descendants = mutableListOf<Long>()
        fun collect(parent: Long) {
            childrenByParent[parent].orEmpty().forEach { child ->
                descendants += child
                collect(child)
            }
        }
        collect(rootPid)
        return descendants
    }

    private fun signalProcesses(pids: List<Long>, signal: String) {
        if (pids.isEmpty()) return
        ProcessBuilder(listOf("kill", "-$signal") + pids.map(Long::toString))
            .start()
            .waitFor(5, TimeUnit.SECONDS)
    }

    private fun isRunningProcess(pid: Long): Boolean {
        val stat = Paths.get("/proc", pid.toString(), "stat").toFile()
        if (!stat.exists()) return false
        val fields = stat.readText().substringAfterLast(") ").split(' ')
        return fields.firstOrNull() != "Z"
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
