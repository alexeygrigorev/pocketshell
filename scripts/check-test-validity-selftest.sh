#!/usr/bin/env bash
# Self-test for scripts/check-test-validity.sh (issue #850).
#
# For each NEW detector added in #850 (C1, FAKE1, AWAIT1) and the pre-existing
# A5, this driver plants a BAD fixture (the smell) and a GOOD fixture (the
# corrective shape), runs the guard, and asserts the bad fixture is reported as
# a finding while the good fixture is NOT — the red->green proof for the
# detector itself. It also asserts the guard HARD-FAILS (exit 1) when an
# unjustified hard-fail smell (A5 / C1) is planted, and PASSES (exit 0) when
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

# Remove ONLY this invocation's own (PID-suffixed) fixture dirs, so a concurrent
# sibling self-test (different PID) is never disturbed.
cleanup() {
  rm -rf "$TEST_FIX_DIR" "$ANDROID_FIX_DIR" "$SRC_FIX_DIR"
}
trap cleanup EXIT
cleanup
mkdir -p "$TEST_FIX_DIR" "$ANDROID_FIX_DIR" "$SRC_FIX_DIR"

PASS=0
FAIL=0
note_pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
note_fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }

# Assert a path appears (mode=present) or does not appear (mode=absent) as a
# FINDING in the named report section. `section` is a substring of the section
# header line (e.g. "C1 — NEW", "FAKE1 — NEW", "AWAIT1 — NEW"). A GOOD fixture
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
assert_report() {
  local mode="$1" needle="$2" section="$3" desc="$4"
  local out sect
  out="$("$GUARD" --report 2>&1)"
  sect="$(section_of "$section" "$out")"
  if [[ "$mode" == "present" ]]; then
    if printf '%s' "$sect" | grep -Fq "$needle"; then note_pass "$desc"; else note_fail "$desc (expected '$needle' under '$section')"; fi
  else
    if printf '%s' "$sect" | grep -Fq "$needle"; then note_fail "$desc (did NOT expect '$needle' under '$section')"; else note_pass "$desc"; fi
  fi
}

# Assert the guard's guard-mode exit code.
assert_exit() {
  local want="$1" desc="$2"
  "$GUARD" >/dev/null 2>&1
  local got=$?
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
