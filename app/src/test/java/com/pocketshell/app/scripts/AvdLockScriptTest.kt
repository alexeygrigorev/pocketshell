package com.pocketshell.app.scripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
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
 * The harnesses are JVM-free, emulator-free, and Docker-free (fake `adb`, stub
 * `gradlew`, sandboxed lock dir, preset agents port), and run in seconds.
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
        runShellHarness("tests/scripts/avd-lock-sharing-test.sh", timeoutSeconds = 180)
    }

    /** Lock acquire/release ownership: no leak to children, nested gates, `--help` fast path. */
    @Test
    fun avdLockHelperOwnershipHarnessPasses() {
        runShellHarness("tests/scripts/avd-lock-test.sh", timeoutSeconds = 120)
    }

    /** #724 pool claim/release through the REAL `connected-test.sh --pool` wrapper. */
    @Test
    fun avdPoolClaimReleaseHarnessPasses() {
        runShellHarness("tests/scripts/avd-pool-test.sh", timeoutSeconds = 180)
    }

    private fun runShellHarness(relativePath: String, timeoutSeconds: Long) {
        val script = projectRoot.resolve(relativePath)
        assertTrue("harness is missing: $relativePath", script.toFile().exists())

        val process = ProcessBuilder("bash", script.toString())
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
            .start()

        val output = process.inputStream.bufferedReader().use { it.readText() }
        val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
        }
        assertTrue("harness timed out after ${timeoutSeconds}s: $relativePath\n$output", completed)
        assertEquals("harness failed: $relativePath\n$output", 0, process.exitValue())
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
