package com.pocketshell.app.scripts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

/**
 * Runs the issue #1989 free-disk preflight + safe-list cleanup harness under
 * `./gradlew test` — the `Unit tests` required check.
 *
 * WHY a JVM test drives bash, again: a shell harness that no gate invokes is not
 * a test (#1640/#1646/#1853). `tests/scripts/` accumulated twelve harnesses that
 * had never executed, four of which were failing when #1853 finally ran them.
 * Wiring this one here means it runs in the per-PR required check AND inside
 * `scripts/full-jvm-gate.sh`'s complete graph, with no workflow YAML edit to
 * drift out of sync.
 *
 * The harness is emulator-free, Docker-daemon-free, and disk-free: it uses a
 * fake `adb`, a fake `docker`, a stub `gradlew`, sandboxed lock and scratch
 * directories, and a throwaway git repository with real worktrees. Nothing in it
 * fills a filesystem — the free-space THRESHOLD is lowered instead, because
 * actually filling the dev box's root to reproduce a disk-full bug would take
 * the box down and break every sibling lane.
 */
class DiskPreflightScriptTest {
    private val projectRoot: Path = findProjectRoot()

    /**
     * THE #1989 regression: on a full root filesystem the canonical wrappers
     * started anyway and died with ENOSPC deep inside Docker BuildKit, Gradle's
     * cache, and `:app:kspDebugKotlin`. None of those is an assertion failure,
     * but all of them read like one until someone runs `df`.
     *
     * Covers both wrappers and both directions — a preflight that only ever says
     * no is as useless as one that never fires — plus the safe-list cleanup
     * path's refusals, including the 2026-07-19 shape where an entire
     * implementation lived only as untracked files that `git diff` calls clean.
     */
    @Test
    fun canonicalWrappersRefuseAFullDiskAndTheCleanupPathRefusesUnsavedWork() {
        runShellHarness("tests/scripts/disk-preflight-test.sh", timeoutSeconds = 300)
    }

    private fun runShellHarness(
        relativePath: String,
        timeoutSeconds: Long,
    ) {
        val script = projectRoot.resolve(relativePath)
        assertTrue("harness is missing: $relativePath", script.toFile().exists())

        // Hermetic invocation. Three inherited-state hazards, all real here:
        //   * a driving gate exports AVD/output-lock acquire state, which would
        //     short-circuit the wrapper's own lock calls inside the fixtures
        //     (the #1702 lesson);
        //   * `CI` is set in the required Unit job, and scripts/full-jvm-gate.sh
        //     rejects any run with CI set outside its guard-only modes — the
        //     harness drives its real preflight probe, so CI must be absent.
        //     Removing CI also disables scripts/lib/scope-run.sh's hosted-runner
        //     bare-execution fallback, which on a runner with no reachable
        //     `systemd --user` manager would make connected-test.sh fail closed
        //     at rc 125 before reaching gradle; the harness opts back in
        //     explicitly in make_connected_sandbox and pins both directions in
        //     connected_test_survives_a_host_without_user_systemd, so that
        //     hosted-vs-dev-box divergence cannot come back;
        //   * a caller-set POCKETSHELL_DISK_* threshold would silently redefine
        //     what "below the floor" means for every case.
        val sandbox = Files.createTempDirectory("pocketshell-disk-preflight-test").toFile()
        sandbox.deleteOnExit()

        val builder = ProcessBuilder("bash", script.toString())
            .directory(projectRoot.toFile())
            .redirectErrorStream(true)
        builder.environment().apply {
            keys.removeAll { it.startsWith("POCKETSHELL_AVD_LOCK") }
            keys.removeAll { it.startsWith("POCKETSHELL_GRADLE_OUTPUT_LOCK") }
            keys.removeAll { it.startsWith("POCKETSHELL_DISK_") }
            keys.removeAll { it.startsWith("POCKETSHELL_POOL") }
            keys.removeAll { it.startsWith("POCKETSHELL_TOXIPROXY") }
            remove("ANDROID_SERIAL")
            remove("CI")
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
        // A harness that exits 0 having run nothing is the vacuous green
        // process.md catalogues; the count line is what makes this assertion
        // about behaviour rather than about bash's exit status.
        assertTrue(
            "harness did not report its case count: $relativePath\n$output",
            output.contains("PASS: disk preflight + safe-list cleanup harness (17 cases)"),
        )
    }

    private fun findProjectRoot(): Path {
        var dir: Path? = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
        while (dir != null) {
            if (dir.resolve("scripts/lib/disk-preflight.sh").toFile().exists()) {
                return dir
            }
            dir = dir.parent
        }
        error(
            "Could not locate scripts/lib/disk-preflight.sh from " +
                "user.dir=${System.getProperty("user.dir")}",
        )
    }
}
