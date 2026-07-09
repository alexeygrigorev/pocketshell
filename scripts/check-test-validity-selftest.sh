#!/usr/bin/env bash
# Self-test for scripts/check-test-validity.sh (issue #850).
#
# For each detector added for #848/#850 (C1, FAKE1, AWAIT1, J1) and the
# pre-existing A5, this driver plants a BAD fixture (the smell) and a GOOD fixture (the
# corrective shape), runs the guard, and asserts the bad fixture is reported as
# a finding while the good fixture is NOT — the red->green proof for the
# detector itself. It also asserts the guard HARD-FAILS (exit 1) when an
# unjustified hard-fail smell (A5 / C1 / J1) is planted, and PASSES (exit 0) when
# only the corrective shapes are present.
#
# Fixtures are planted under throwaway subdirectories of the REAL scanned test
# roots (so the guard's `find` picks them up unchanged) and removed on exit.
#
# Usage: scripts/check-test-validity-selftest.sh
# Runs alongside the guard in the Unit job; exits non-zero on any self-test miss.

set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT" || exit 1

GUARD="scripts/check-test-validity.sh"

# Throwaway fixture dirs inside real scanned roots / the connect-RPC source
# root. A per-invocation suffix (PID) keeps concurrent self-test runs (parallel
# agents / repeated CI invocations) from colliding on a shared fixture path. The
# fixtures sit under the directories the guard's `find` already walks, so they
# are scanned unchanged.
FIX_TAG="selftest_$$"
TEST_FIX_DIR="app/src/test/java/com/pocketshell/app/$FIX_TAG"
ANDROID_FIX_DIR="app/src/androidTest/java/com/pocketshell/app/$FIX_TAG"
SRC_FIX_DIR="app/src/main/java/com/pocketshell/app/$FIX_TAG"
# TIMING1 is scoped to the connection/terminal roots, so its fixtures must live
# under one of those dirs (here: the app tmux JVM test root).
TIMING_FIX_DIR="app/src/test/java/com/pocketshell/app/tmux/$FIX_TAG"

# Remove ONLY this invocation's own (PID-suffixed) fixture dirs, so a concurrent
# sibling self-test (different PID) is never disturbed.
cleanup() {
  rm -rf "$TEST_FIX_DIR" "$ANDROID_FIX_DIR" "$SRC_FIX_DIR" "$TIMING_FIX_DIR"
}
trap cleanup EXIT
cleanup
mkdir -p "$TEST_FIX_DIR" "$ANDROID_FIX_DIR" "$SRC_FIX_DIR" "$TIMING_FIX_DIR"

PASS=0
FAIL=0
note_pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
note_fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }

# Assert a path appears (mode=present) or does not appear (mode=absent) as a
# FINDING in the named report section. `section` is a substring of the section
# header line (e.g. "C1 — NEW", "FAKE1 — NEW", "AWAIT1 — NEW", "J1 — NEW"). A GOOD fixture
# may legitimately appear in an advisory JUSTIFIED/KNOWN list — "absent" here
# means "not listed as a finding in THIS section", not "absent from the whole
# report".
section_of() {
  # Print the bullet lines belonging to the section whose header contains $1.
  local section="$1" out="$2"
  printf '%s' "$out" | awk -v s="$section" '
    index($0, s) && /\(/ { capture=1; next }
    /^[A-Za-z].* \(/ { capture=0 }   # next section header ends capture
    capture && /^  - / { print }
  '
}
# PERFORMANCE (#1430): the guard scans the whole ~900-file test tree (~13 s), and
# this self-test asserts it ~30 times. Naively re-running the guard per assertion
# was ~7 min — too heavy for the per-push Unit job. But the guard's NORMAL
# (non-report) mode prints the SAME section bullets AND carries the guard-mode
# exit code, so ONE run per distinct fixture state serves both assert_report and
# assert_exit. We memoize that run, keyed on a cheap signature of the fixture
# dirs + the (possibly-overridden) registry, and re-run only when a fixture /
# registry file changed. This drops the self-test to ~1 run per fixture state.
_CACHE_SIG=""
_CACHE_OUT=""
_CACHE_RC=""
_fixture_signature() {
  {
    find "$TEST_FIX_DIR" "$ANDROID_FIX_DIR" "$SRC_FIX_DIR" "$TIMING_FIX_DIR" \
      -type f -printf '%p|%s|%T@\n' 2>/dev/null | sort
    printf 'REG:%s\n' "${VETTED_SEAM_REGISTRY:-<default>}"
    [[ -n "${VETTED_SEAM_REGISTRY:-}" && -f "${VETTED_SEAM_REGISTRY}" ]] \
      && printf 'REGSIG:%s\n' "$(cksum "$VETTED_SEAM_REGISTRY")"
  } | cksum
}
ensure_guard_cache() {
  local sig
  sig="$(_fixture_signature)"
  if [[ "$sig" != "$_CACHE_SIG" ]]; then
    _CACHE_SIG="$sig"
    _CACHE_OUT="$("$GUARD" 2>&1)"
    _CACHE_RC=$?
  fi
}

assert_report() {
  local mode="$1" needle="$2" section="$3" desc="$4"
  local sect
  ensure_guard_cache
  sect="$(section_of "$section" "$_CACHE_OUT")"
  if [[ "$mode" == "present" ]]; then
    if printf '%s' "$sect" | grep -Fq "$needle"; then note_pass "$desc"; else note_fail "$desc (expected '$needle' under '$section')"; fi
  else
    if printf '%s' "$sect" | grep -Fq "$needle"; then note_fail "$desc (did NOT expect '$needle' under '$section')"; else note_pass "$desc"; fi
  fi
}

# Assert the guard's guard-mode exit code (served from the same memoized run).
assert_exit() {
  local want="$1" desc="$2"
  ensure_guard_cache
  local got="$_CACHE_RC"
  if [[ "$got" -eq "$want" ]]; then note_pass "$desc (exit $got)"; else note_fail "$desc (want exit $want, got $got)"; fi
}

echo "=============================================================="
echo " Self-test: scripts/check-test-validity.sh (#850 detectors)"
echo "=============================================================="

# --------------------------------------------------------------------------
# C1 — assumeFalse(isRunningOnCi()) outside a fault class.
# --------------------------------------------------------------------------
echo
echo "[C1] load-bearing assumeFalse(isRunningOnCi()) self-skip"

# BAD: an unjustified CI self-skip on a journey assertion (no fixture reason).
cat > "$ANDROID_FIX_DIR/C1BadJourneyTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
import org.junit.Assume.assumeFalse
class C1BadJourneyTest {
    fun journey() {
        assumeFalse(isRunningOnCi())
        // load-bearing assertion below silently skipped on CI
    }
    private fun isRunningOnCi() = false
}
KT

# GOOD: the same skip but justified as an opt-in Docker fault fixture.
cat > "$ANDROID_FIX_DIR/C1GoodFaultFixtureTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
import org.junit.Assume.assumeFalse
class C1GoodFaultFixtureTest {
    fun journey() {
        // toxiproxy is an opt-in Docker fixture; tests.yml does not start it
        assumeFalse(isRunningOnCi())
    }
    private fun isRunningOnCi() = false
}
KT

# GOOD2: justified via an inline // JUSTIFIED: opt-out.
cat > "$ANDROID_FIX_DIR/C1GoodJustifiedTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
import org.junit.Assume.assumeFalse
class C1GoodJustifiedTest {
    fun journey() {
        assumeFalse(isRunningOnCi()) // JUSTIFIED: real soft IME never raises on swiftshader
    }
    private fun isRunningOnCi() = false
}
KT

assert_report present "C1BadJourneyTest.kt" "C1 — NEW" "C1 fires on an unjustified CI self-skip"
assert_report absent  "C1GoodFaultFixtureTest.kt" "C1 — NEW" "C1 spares a self-describing opt-in fault fixture skip"
assert_report absent  "C1GoodJustifiedTest.kt" "C1 — NEW" "C1 spares a // JUSTIFIED: skip"
# The bad C1 is a hard-fail category.
assert_exit 1 "C1 unjustified skip hard-fails the guard"

# Remove the BAD C1 so subsequent FAKE1/AWAIT1 (advisory) checks can confirm a
# clean exit-0 with only advisory findings present.
rm -f "$ANDROID_FIX_DIR/C1BadJourneyTest.kt"

# --------------------------------------------------------------------------
# J1 — androidTest E2e/Docker class missing ci-journey-suite coverage.
# --------------------------------------------------------------------------
echo
echo "[J1] unwired androidTest journey class"

# BAD: a new journey-shaped androidTest class that is not wired into
# scripts/ci-journey-suite.sh and has no local reason for staying out.
cat > "$ANDROID_FIX_DIR/J1BadUnwiredE2eTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class J1BadUnwiredE2eTest {
    fun journey() {
        // Load-bearing connected journey proof, but not in ci-journey-suite.
    }
}
KT

# GOOD: the same unwired shape with a local source-level justification.
cat > "$ANDROID_FIX_DIR/J1GoodJustifiedDockerTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
// CI_JOURNEY_SUITE_JUSTIFIED: opt-in Docker fixture runs only in nightly.
class J1GoodJustifiedDockerTest {
    fun journey() {
        // Local/nightly-only fixture; the comment above is the required reason.
    }
}
KT

assert_report present "J1BadUnwiredE2eTest" "J1 — NEW" "J1 fires on an unwired androidTest journey"
assert_report absent  "J1GoodJustifiedDockerTest" "J1 — NEW" "J1 spares a local ci-journey-suite justification"
assert_exit 1 "J1 unwired androidTest journey hard-fails the guard"

# Remove the BAD J1 so advisory checks can still prove guard-mode exit 0 when
# no hard-fail smells remain.
rm -f "$ANDROID_FIX_DIR/J1BadUnwiredE2eTest.kt"

# --------------------------------------------------------------------------
# FAKE1 — connect-path RPC test with an always-answering fake (no fault case).
# --------------------------------------------------------------------------
echo
echo "[FAKE1] always-answering connect-path fake"

# BAD: a FakeSshSession that routes `tree get` ALWAYS through exit 0, asserts a
# Loading->Ready resolution, with NO fault/error/timeout case.
cat > "$TEST_FIX_DIR/Fake1BadTreeHydrateTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Fake1BadTreeHydrateTest {
    private class FakeTreeSshSession {
        fun exec(command: String): ExecResult {
            // tree get cold-start hydrate always answers OK -> Loading always resolves
            return ExecResult(stdout = "{\"nodes\":[]}", stderr = "", exitCode = 0)
        }
    }
    data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)
}
KT

# GOOD: the same connect-path fake but WITH a fault case (non-zero exit injected
# for the verb under test + an assertThrows on cancellation).
cat > "$TEST_FIX_DIR/Fake1GoodTreeHydrateTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Fake1GoodTreeHydrateTest {
    private class FakeTreeSshSession(private val exitCode: Int) {
        fun exec(command: String): ExecResult {
            // tree get cold-start hydrate — old/missing CLI returns exit 64
            return ExecResult(stdout = "", stderr = "unknown command", exitCode = exitCode)
        }
    }
    fun getTree_oldCliNonZeroStillResolvesLoading() {
        val session = FakeTreeSshSession(exitCode = 64)
        // Loading must still resolve even when the connect RPC fails.
        assertThrows { session.exec("tree get") }
    }
    private fun assertThrows(block: () -> Unit) {}
    data class ExecResult(val stdout: String, val stderr: String, val exitCode: Int)
}
KT

assert_report present "Fake1BadTreeHydrateTest.kt" "FAKE1 — NEW" "FAKE1 fires on an always-answering connect fake"
assert_report absent  "Fake1GoodTreeHydrateTest.kt" "FAKE1 — NEW" "FAKE1 spares a connect fake that has a fault case"

# --------------------------------------------------------------------------
# AWAIT1 — unbounded connect-path RPC seam (no withTimeout).
# --------------------------------------------------------------------------
echo
echo "[AWAIT1] unbounded connect-path RPC await"

# BAD: a *RemoteSource seam that execs the warm session for `tree get` with NO
# withTimeout anywhere -> a non-returning exec pins the coroutine forever.
cat > "$SRC_FIX_DIR/Await1BadRemoteSource.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Await1BadRemoteSource {
    suspend fun getTree(session: FakeSession, host: String): String {
        // cold-start hydrate — UNBOUNDED warm-session exec (no withTimeout)
        val result = session.exec("printf %s | pocketshell tree get")
        return result
    }
    interface FakeSession { suspend fun exec(command: String): String }
}
KT

# GOOD: the same seam but the warm-session exec is bounded with withTimeout.
cat > "$SRC_FIX_DIR/Await1GoodRemoteSource.kt" <<'KT'
package com.pocketshell.app.validityselftest
import kotlinx.coroutines.withTimeout
class Await1GoodRemoteSource {
    suspend fun getTree(session: FakeSession, host: String): String {
        // cold-start hydrate — BOUNDED so a non-returning exec cannot pin us
        return withTimeout(5_000) { session.exec("printf %s | pocketshell tree get") }
    }
    interface FakeSession { suspend fun exec(command: String): String }
}
KT

assert_report present "Await1BadRemoteSource.kt" "AWAIT1 — NEW" "AWAIT1 fires on an unbounded connect-path RPC seam"
assert_report absent  "Await1GoodRemoteSource.kt" "AWAIT1 — NEW" "AWAIT1 spares a withTimeout-bounded connect-path RPC seam"

# --------------------------------------------------------------------------
# A5 — IME-availability assumeTrue self-skip (pre-existing detector; confirm it
# still fires now that the scan covers every test root).
# --------------------------------------------------------------------------
echo
echo "[A5] IME-availability assumeTrue self-skip (regression of pre-existing detector)"

cat > "$ANDROID_FIX_DIR/A5BadImeTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
import org.junit.Assume.assumeTrue
class A5BadImeTest {
    fun imeGeometry() {
        // boundsInRoot geometry assertion gated on whether the soft keyboard raised
        assumeTrue(imeShown())
    }
    private fun imeShown() = false
}
KT

cat > "$ANDROID_FIX_DIR/A5GoodSdkGuardTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
import android.os.Build
import org.junit.Assume.assumeTrue
class A5GoodSdkGuardTest {
    fun imeGeometry() {
        // boundsInRoot geometry — legitimate SDK guard, NOT an IME-availability skip
        assumeTrue(Build.VERSION.SDK_INT >= 29)
    }
}
KT

assert_report present "A5BadImeTest.kt" "A5 — NEW" "A5 fires on an IME-availability assumeTrue self-skip"
assert_report absent  "A5GoodSdkGuardTest.kt" "A5 — NEW" "A5 spares a Build.VERSION SDK guard"
# A5 bad + C1 GOOD-only present here -> A5 is a hard-fail category.
assert_exit 1 "A5 unjustified IME skip hard-fails the guard"

# Remove the A5 bad so the final clean-state assertion holds.
rm -f "$ANDROID_FIX_DIR/A5BadImeTest.kt"

# --------------------------------------------------------------------------
# TIMING1 — runTest virtual-clock-vs-real-dispatcher fragility (#1048).
# --------------------------------------------------------------------------
echo
echo "[TIMING1] runTest over a real dispatcher/thread (connection/terminal roots)"

# BAD (HARD-FAIL): a runTest test with a bare Thread.sleep(N) immediately before
# its load-bearing assert and NO bounded-deadline loop (the banned shape).
cat > "$TIMING_FIX_DIR/Timing1BadSleepBeforeAssertTest.kt" <<'KT'
package com.pocketshell.app.tmux.validityselftest
import kotlinx.coroutines.test.runTest
class Timing1BadSleepBeforeAssertTest {
    fun reattachWritesMarker() = runTest {
        val out = startRealThreadWorker()
        Thread.sleep(50)
        assertEquals("MARKER", out.value)
    }
    private fun startRealThreadWorker(): Holder = Holder()
    class Holder { val value: String = "MARKER" }
    private fun assertEquals(expected: String, actual: String) {}
}
KT

# GOOD-A (Shape A: pinnable seam): runTest + Dispatchers.IO but injects a
# StandardTestDispatcher seam for its owned scope -> spared.
cat > "$TIMING_FIX_DIR/Timing1GoodSeamTest.kt" <<'KT'
package com.pocketshell.app.tmux.validityselftest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
class Timing1GoodSeamTest {
    fun pinnedScope() = runTest {
        // owned scope pinned to the test scheduler (production uses Dispatchers.IO)
        val ctx = StandardTestDispatcher(testScheduler)
        require(ctx != Dispatchers.IO)
    }
}
KT

# GOOD-B (Shape B: bounded pump): runTest + Thread.sleep inside a bounded
# idleFor()+currentTimeMillis() deadline loop -> spared.
cat > "$TIMING_FIX_DIR/Timing1GoodBoundedPumpTest.kt" <<'KT'
package com.pocketshell.app.tmux.validityselftest
import kotlinx.coroutines.test.runTest
class Timing1GoodBoundedPumpTest {
    fun pumpUntilMarker() = runTest {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            looper().idleFor(16)
            if (markerRendered()) break
            Thread.sleep(20)
        }
        assertTrue(markerRendered())
    }
    private fun looper() = FakeLooper()
    private fun markerRendered() = true
    class FakeLooper { fun idleFor(ms: Long) {} }
    private fun assertTrue(b: Boolean) {}
}
KT

# GOOD-C (// JUSTIFIED:): runTest + Thread.sleep but opted out inline.
cat > "$TIMING_FIX_DIR/Timing1GoodJustifiedTest.kt" <<'KT'
package com.pocketshell.app.tmux.validityselftest
import kotlinx.coroutines.test.runTest
class Timing1GoodJustifiedTest {
    fun deliberateWallClock() = runTest {
        Thread.sleep(50) // JUSTIFIED: deliberate wall-clock wedge harness, not a sync proxy
        assertTrue(true)
    }
    private fun assertTrue(b: Boolean) {}
}
KT

# ADVISORY (non-hard): runTest + a real-IO owned scope, no sleep-before-assert,
# no seam, no pump -> advisory NEW finding (must NOT hard-fail).
cat > "$TIMING_FIX_DIR/Timing1AdvisoryRealScopeTest.kt" <<'KT'
package com.pocketshell.app.tmux.validityselftest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
class Timing1AdvisoryRealScopeTest {
    private val factoryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    fun connects() = runTest {
        require(factoryScope.coroutineContext != null)
    }
}
KT

assert_report present "Timing1BadSleepBeforeAssertTest.kt" "TIMING1 — NEW bare Thread.sleep" "TIMING1 hard-fails a bare sleep-before-assert with no bounded loop"
assert_report absent  "Timing1GoodSeamTest.kt" "TIMING1 — NEW bare Thread.sleep" "TIMING1 spares a StandardTestDispatcher seam (Shape A) from hard-fail"
assert_report absent  "Timing1GoodSeamTest.kt" "TIMING1 — NEW runTest over a real dispatcher" "TIMING1 spares a StandardTestDispatcher seam (Shape A) advisory"
assert_report absent  "Timing1GoodBoundedPumpTest.kt" "TIMING1 — NEW runTest over a real dispatcher" "TIMING1 spares a bounded pump (Shape B)"
assert_report present "Timing1GoodJustifiedTest.kt" "TIMING1 — JUSTIFIED" "TIMING1 lists a // JUSTIFIED: opt-out as justified"
assert_report present "Timing1AdvisoryRealScopeTest.kt" "TIMING1 — NEW runTest over a real dispatcher" "TIMING1 advisory-flags a real-IO owned scope with no seam"
assert_report absent  "Timing1AdvisoryRealScopeTest.kt" "TIMING1 — NEW bare Thread.sleep" "TIMING1 advisory real-IO scope is NOT a hard-fail"
assert_exit 1 "TIMING1 bare sleep-before-assert hard-fails the guard"

# Remove the BAD TIMING1 so the final clean-state assertion (exit 0 with only
# advisory/justified findings) holds.
rm -f "$TIMING_FIX_DIR/Timing1BadSleepBeforeAssertTest.kt"

# --------------------------------------------------------------------------
# SEAM1 — connected test driving an assertion from an UNVETTED production
# state-injection seam (#1430). Reconstructs the deleted #1158
# forceActivePaneAltBufferForTest cheat: a production `force*ForTest` seam that
# injects a state the real path never reaches, called by a connected test whose
# load-bearing assert then reads it back — green while the feature is broken.
# --------------------------------------------------------------------------
echo
echo "[SEAM1] connected test driving an assertion from an unvetted state-injection seam"

# Plant a PRODUCTION state-injection seam (the alt-buffer cheat shape) under
# src/main so the guard resolves the call to a real production seam (a
# test-double helper of the same name must be IGNORED — proven separately below).
cat > "$SRC_FIX_DIR/Seam1ProdSeam.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1ProdSeam {
    // A production state-injection seam of the #1158 alt-buffer shape: it forces
    // a runtime flag the real seed path never sets on its own.
    fun forceActivePaneAltBufferForTest(active: Boolean) { /* injects unreachable state */ }
}
KT

# BAD: a connected test that forces the unreachable state, then asserts on it —
# the exact #1158 cheat. The seam is production-defined but NOT registry-vetted.
cat > "$ANDROID_FIX_DIR/Seam1BadAltBufferCheatTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1BadAltBufferCheatTest {
    fun conversationTabAppearsForLiveAgent() {
        vm.forceActivePaneAltBufferForTest(true)   // injects a production-unreachable state
        assertTrue(showsConversationTab())          // load-bearing — green while broken
    }
    private val vm = Seam1ProdSeam()
    private fun showsConversationTab() = true
    private fun assertTrue(b: Boolean) {}
}
KT

# GOOD-1 (vetted): forceTreeStaleForTest IS a real production seam AND is listed
# in scripts/vetted-test-state-setters.txt with a real-path-reachability reason.
cat > "$ANDROID_FIX_DIR/Seam1GoodVettedTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1GoodVettedTest {
    fun treeGoesStale() {
        vm.forceTreeStaleForTest()   // registry-vetted: wraps the exact production markReconcileDue call
    }
    private val vm = FakeVm()
    class FakeVm { fun forceTreeStaleForTest() {} }
}
KT

# GOOD-2 (justified): the same unvetted production seam but opted out inline.
cat > "$ANDROID_FIX_DIR/Seam1GoodJustifiedTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1GoodJustifiedTest {
    fun oneOff() {
        vm.forceActivePaneAltBufferForTest(true) // SEAM_JUSTIFIED: selftest one-off; injected state is reachable here
    }
    private val vm = Seam1ProdSeam()
}
KT

# GOOD-3 (non-production helper): a `force*ForTest` of the same SHAPE but defined
# locally in the test (NOT a production seam) must be IGNORED — the cheat class is
# specifically a production seam.
cat > "$ANDROID_FIX_DIR/Seam1GoodLocalHelperTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1GoodLocalHelperTest {
    fun usesLocalHelper() {
        forceLocalOnlyHelperForTest(true)  // a test-double helper, never a production seam
    }
    private fun forceLocalOnlyHelperForTest(active: Boolean) {}
}
KT

assert_report present "Seam1BadAltBufferCheatTest.kt" "SEAM1 — NEW" "SEAM1 fires on an unvetted production state-injection seam (reconstructs the #1158 alt-buffer cheat)"
assert_report absent  "Seam1GoodVettedTest.kt" "SEAM1 — NEW" "SEAM1 spares a registry-vetted seam (forceTreeStaleForTest)"
assert_report absent  "Seam1GoodJustifiedTest.kt" "SEAM1 — NEW" "SEAM1 spares a // SEAM_JUSTIFIED: opt-out"
assert_report absent  "Seam1GoodLocalHelperTest.kt" "SEAM1 — NEW" "SEAM1 ignores a non-production test-double helper of the same shape"
assert_exit 1 "SEAM1 unvetted production state-injection seam hard-fails the guard"

# Remove the BAD SEAM1 caller so the registry-error and clean-state checks below
# are not confounded by its hard-fail.
rm -f "$ANDROID_FIX_DIR/Seam1BadAltBufferCheatTest.kt"

# --------------------------------------------------------------------------
# SEAM1 registry hygiene — a registry line with no `# justification` is a hard
# error (registry additions must carry a written real-path-reachability reason).
# Point the guard at a TEMP registry (real registry + one un-justified line) so
# the real 3 vetted seams stay covered (no spurious SEAM1 — NEW) and only the bad
# line trips the error.
# --------------------------------------------------------------------------
echo
echo "[SEAM1] registry line without a written justification is a hard error"

TMP_REG="$(mktemp)"
cat "scripts/vetted-test-state-setters.txt" > "$TMP_REG"
printf '\nselftestSeamWithoutReasonForTest\n' >> "$TMP_REG"   # no `# justification` -> error
export VETTED_SEAM_REGISTRY="$TMP_REG"

assert_report present "has no '# justification'" "SEAM1 — REGISTRY error" "SEAM1 flags a registry line with no written justification"
assert_exit 1 "SEAM1 un-justified registry line hard-fails the guard"

unset VETTED_SEAM_REGISTRY
rm -f "$TMP_REG"

# --------------------------------------------------------------------------
# Clean state: only corrective/advisory fixtures remain -> guard must PASS.
# --------------------------------------------------------------------------
echo
echo "[clean] only corrective shapes + advisory findings remain"
assert_exit 0 "guard passes (exit 0) with no hard-fail smells, only advisory findings"

echo
echo "=============================================================="
echo " Self-test result: $PASS passed, $FAIL failed"
echo "=============================================================="
if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
exit 0
