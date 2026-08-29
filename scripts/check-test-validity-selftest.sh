#!/usr/bin/env bash
# Self-test for scripts/check-test-validity.sh (issue #850).
#
# For each detector added for #848/#850 (C1, FAKE1, AWAIT1, J1), the
# pre-existing A5, #1048/#2026 TIMING1, and #1857 A5L, this driver plants a BAD fixture (the smell)
# and a GOOD fixture (the corrective shape), runs the guard, and asserts the bad
# fixture is reported as a finding while the good fixture is NOT — the
# red->green proof for the detector itself. It also asserts the guard HARD-FAILS
# (exit 1) when an unjustified hard-fail smell (A5 / A5L / C1 / J1) is planted,
# and PASSES (exit 0) when only the corrective shapes are present.
#
# Fixtures are planted under throwaway subdirectories of the REAL scanned test
# roots (so the guard's `find` picks them up unchanged) and removed on exit.
#
# Usage: scripts/check-test-validity-selftest.sh
# Runs alongside the guard in the Unit job; exits non-zero on any self-test miss.

# This harness judges another fail-closed script. Its own unhandled runtime
# errors must likewise stop the run instead of reaching a green summary.
set -Eeuo pipefail

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
# TIMING1's original runTest fixture lives under app/tmux. Its #2026
# plain-JUnit deadline-pump fixtures live under the two exact new roots so the
# self-test proves real scan reachability rather than merely branch presence.
TIMING_FIX_DIR="app/src/test/java/com/pocketshell/app/tmux/$FIX_TAG"
TIMING_PORTFWD_FIX_DIR="app/src/test/java/com/pocketshell/app/portfwd/$FIX_TAG"
TIMING_PREFS_FIX_DIR="app/src/test/java/com/pocketshell/app/prefs/$FIX_TAG"
TMP_REG=""
CLEANUP_SIBLING_TAG=""
MUTATED_GUARD=""

# Remove ONLY this invocation's own (PID-suffixed) fixture dirs, so a concurrent
# sibling self-test (different PID) is never disturbed. Also remove this
# invocation's unique temporary registry if an interrupt arrives mid-check.
cleanup() {
  rm -rf \
    "$TEST_FIX_DIR" \
    "$ANDROID_FIX_DIR" \
    "$SRC_FIX_DIR" \
    "$TIMING_FIX_DIR" \
    "$TIMING_PORTFWD_FIX_DIR" \
    "$TIMING_PREFS_FIX_DIR"
  [[ -z "${TMP_REG:-}" ]] || rm -f -- "$TMP_REG"
  [[ -z "${MUTATED_GUARD:-}" ]] || rm -f -- "$MUTATED_GUARD"
  if [[ -n "${CLEANUP_SIBLING_TAG:-}" ]]; then
    rm -rf \
      "app/src/test/java/com/pocketshell/app/$CLEANUP_SIBLING_TAG" \
      "app/src/androidTest/java/com/pocketshell/app/$CLEANUP_SIBLING_TAG" \
      "app/src/main/java/com/pocketshell/app/$CLEANUP_SIBLING_TAG" \
      "app/src/test/java/com/pocketshell/app/tmux/$CLEANUP_SIBLING_TAG" \
      "app/src/test/java/com/pocketshell/app/portfwd/$CLEANUP_SIBLING_TAG" \
      "app/src/test/java/com/pocketshell/app/prefs/$CLEANUP_SIBLING_TAG"
  fi
}
exit_from_signal() {
  local status="$1"
  cleanup
  trap - EXIT INT TERM
  exit "$status"
}
trap cleanup EXIT
trap 'exit_from_signal 130' INT
trap 'exit_from_signal 143' TERM
cleanup
mkdir -p \
  "$TEST_FIX_DIR" \
  "$ANDROID_FIX_DIR" \
  "$SRC_FIX_DIR" \
  "$TIMING_FIX_DIR" \
  "$TIMING_PORTFWD_FIX_DIR" \
  "$TIMING_PREFS_FIX_DIR"

# Internal child mode for the deterministic SIGTERM cleanup regression below.
# It creates only its normal PID-scoped fixtures, then waits to be terminated.
if [[ "${1:-}" == "--sigterm-cleanup-probe" ]]; then
  # Exercise the explicit signal path independently of the normal-completion
  # EXIT fallback. Before #1758 installed INT/TERM handlers this deterministically
  # left all four directories behind, matching the reviewer interruption.
  trap - EXIT
  while true; do
    sleep 1
  done
fi

PASS=0
FAIL=0
note_pass() { echo "  PASS: $1"; PASS=$((PASS + 1)); }
note_fail() { echo "  FAIL: $1"; FAIL=$((FAIL + 1)); }

run_sigterm_cleanup_regression() {
  local probe_pid probe_rc=0 attempt probe_ready=0 own_removed=0 sibling_preserved=0
  local probe_tag
  CLEANUP_SIBLING_TAG="selftest_9$$"
  mkdir -p \
    "app/src/test/java/com/pocketshell/app/$CLEANUP_SIBLING_TAG" \
    "app/src/androidTest/java/com/pocketshell/app/$CLEANUP_SIBLING_TAG" \
    "app/src/main/java/com/pocketshell/app/$CLEANUP_SIBLING_TAG" \
    "app/src/test/java/com/pocketshell/app/tmux/$CLEANUP_SIBLING_TAG" \
    "app/src/test/java/com/pocketshell/app/portfwd/$CLEANUP_SIBLING_TAG" \
    "app/src/test/java/com/pocketshell/app/prefs/$CLEANUP_SIBLING_TAG"

  "$REPO_ROOT/scripts/check-test-validity-selftest.sh" --sigterm-cleanup-probe &
  probe_pid=$!
  probe_tag="selftest_$probe_pid"

  for attempt in {1..100}; do
    if [[ -d "app/src/test/java/com/pocketshell/app/$probe_tag" &&
          -d "app/src/androidTest/java/com/pocketshell/app/$probe_tag" &&
          -d "app/src/main/java/com/pocketshell/app/$probe_tag" &&
          -d "app/src/test/java/com/pocketshell/app/tmux/$probe_tag" &&
          -d "app/src/test/java/com/pocketshell/app/portfwd/$probe_tag" &&
          -d "app/src/test/java/com/pocketshell/app/prefs/$probe_tag" ]]; then
      probe_ready=1
      break
    fi
    kill -0 "$probe_pid" 2>/dev/null || break
    sleep 0.05
  done

  kill -TERM "$probe_pid" 2>/dev/null || true
  wait "$probe_pid" || probe_rc=$?

  if [[ "$probe_ready" -eq 1 &&
        "$probe_rc" -eq 143 &&
        ! -e "app/src/test/java/com/pocketshell/app/$probe_tag" &&
        ! -e "app/src/androidTest/java/com/pocketshell/app/$probe_tag" &&
        ! -e "app/src/main/java/com/pocketshell/app/$probe_tag" &&
        ! -e "app/src/test/java/com/pocketshell/app/tmux/$probe_tag" &&
        ! -e "app/src/test/java/com/pocketshell/app/portfwd/$probe_tag" &&
        ! -e "app/src/test/java/com/pocketshell/app/prefs/$probe_tag" ]]; then
    own_removed=1
  fi
  if [[ -d "app/src/test/java/com/pocketshell/app/$CLEANUP_SIBLING_TAG" &&
      -d "app/src/androidTest/java/com/pocketshell/app/$CLEANUP_SIBLING_TAG" &&
      -d "app/src/main/java/com/pocketshell/app/$CLEANUP_SIBLING_TAG" &&
      -d "app/src/test/java/com/pocketshell/app/tmux/$CLEANUP_SIBLING_TAG" &&
      -d "app/src/test/java/com/pocketshell/app/portfwd/$CLEANUP_SIBLING_TAG" &&
      -d "app/src/test/java/com/pocketshell/app/prefs/$CLEANUP_SIBLING_TAG" ]]; then
    sibling_preserved=1
  fi

  if [[ "$own_removed" -eq 1 ]]; then
    note_pass "SIGTERM exits 143 and removes the interrupted run's PID-scoped fixtures"
  else
    note_fail "SIGTERM cleanup (ready=$probe_ready exit=$probe_rc own_removed=$own_removed)"
  fi
  if [[ "$sibling_preserved" -eq 1 ]]; then
    note_pass "SIGTERM cleanup preserves a sibling invocation's fixture directories"
  else
    note_fail "SIGTERM cleanup removed or damaged sibling fixture directories"
  fi

  rm -rf \
    "app/src/test/java/com/pocketshell/app/$probe_tag" \
    "app/src/androidTest/java/com/pocketshell/app/$probe_tag" \
    "app/src/main/java/com/pocketshell/app/$probe_tag" \
    "app/src/test/java/com/pocketshell/app/tmux/$probe_tag" \
    "app/src/test/java/com/pocketshell/app/portfwd/$probe_tag" \
    "app/src/test/java/com/pocketshell/app/prefs/$probe_tag" \
    "app/src/test/java/com/pocketshell/app/$CLEANUP_SIBLING_TAG" \
    "app/src/androidTest/java/com/pocketshell/app/$CLEANUP_SIBLING_TAG" \
    "app/src/main/java/com/pocketshell/app/$CLEANUP_SIBLING_TAG" \
    "app/src/test/java/com/pocketshell/app/tmux/$CLEANUP_SIBLING_TAG" \
    "app/src/test/java/com/pocketshell/app/portfwd/$CLEANUP_SIBLING_TAG" \
    "app/src/test/java/com/pocketshell/app/prefs/$CLEANUP_SIBLING_TAG"
  CLEANUP_SIBLING_TAG=""
}

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
    find \
      "$TEST_FIX_DIR" \
      "$ANDROID_FIX_DIR" \
      "$SRC_FIX_DIR" \
      "$TIMING_FIX_DIR" \
      "$TIMING_PORTFWD_FIX_DIR" \
      "$TIMING_PREFS_FIX_DIR" \
      -type f -printf '%p|%s|%T@\n' 2>/dev/null | sort
    printf 'REG:%s\n' "${VETTED_SEAM_REGISTRY:-<default>}"
    [[ -n "${VETTED_SEAM_REGISTRY:-}" && -f "${VETTED_SEAM_REGISTRY}" ]] \
      && printf 'REGSIG:%s\n' "$(cksum "$VETTED_SEAM_REGISTRY")"
  } | cksum
  return 0
}
ensure_guard_cache() {
  local sig
  sig="$(_fixture_signature)"
  if [[ "$sig" != "$_CACHE_SIG" ]]; then
    _CACHE_SIG="$sig"
    if _CACHE_OUT="$("$GUARD" 2>&1)"; then
      _CACHE_RC=0
    else
      _CACHE_RC=$?
    fi
    if printf '%s\n' "$_CACHE_OUT" \
        | grep -Eq '(^|: )[[:alnum:]_./-]+: command not found($|[[:space:]])|unbound variable|syntax error near unexpected token'; then
      note_fail "guard cache state emitted an internal shell/runtime diagnostic"
      # Never let an expected detector rc=1 launder an unrelated runtime error
      # into a successful red proof.
      _CACHE_RC=125
    fi
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

# A shell/runtime diagnostic in the guard output invalidates every cached
# detector verdict. In particular, a later expected exit=1 must not launder an
# unrelated `command not found` into a successful red proof.
assert_guard_runtime_clean() {
  local desc="$1"
  ensure_guard_cache
  if printf '%s\n' "$_CACHE_OUT" \
      | grep -Eq '(^|: )[[:alnum:]_./-]+: command not found($|[[:space:]])|unbound variable|syntax error near unexpected token'; then
    note_fail "$desc (guard emitted an internal shell/runtime diagnostic)"
  else
    note_pass "$desc"
  fi
}

# Mutate the actual TIMING1 conditional-helper call in a private executable copy
# beside the real guard (so its repo-root discovery is unchanged). Bash disables
# errexit for commands used as an `if` condition, including all commands inside
# a condition-invoked function. The mutant must therefore return non-zero by the
# guard's explicit predicate-error contract, not by top-level `set -e` luck.
run_guard_runtime_error_regression() {
  local out rc inserted pass_footers
  MUTATED_GUARD="$(mktemp "scripts/.check-test-validity-runtime-error.$$.XXXXXX.sh")"
  awk '
    {
      if (index($0, "timing1_checked_predicate timing1_uses_shared_pump_only_scope \"$file\"") > 0) {
        sub(/timing1_uses_shared_pump_only_scope/, "test_validity_selftest_missing_conditional_helper")
        inserted++
      }
      print
    }
    END {
      if (inserted != 1) exit 90
    }
  ' "$GUARD" > "$MUTATED_GUARD"
  chmod +x "$MUTATED_GUARD"
  inserted="$(grep -c 'timing1_checked_predicate test_validity_selftest_missing_conditional_helper "\$file"' "$MUTATED_GUARD")"
  if [[ "$inserted" -eq 1 ]]; then
    note_pass "conditional-helper mutant is live exactly once at the TIMING1 call site"
  else
    note_fail "conditional-helper mutant insertion count (want 1, got $inserted)"
  fi
  if out="$("$MUTATED_GUARD" 2>&1)"; then
    rc=0
  else
    rc=$?
  fi
  if printf '%s\n' "$out" | grep -q 'test_validity_selftest_missing_conditional_helper: command not found'; then
    note_pass "conditional-helper mutant executed the missing command"
  else
    note_fail "conditional-helper mutant did not emit its command-not-found diagnostic"
  fi
  pass_footers="$(printf '%s\n' "$out" | grep -c '^PASS: no new unjustified' || true)"
  if [[ "$pass_footers" -eq 0 ]]; then
    note_pass "conditional command-not-found cannot reach the normal PASS footer"
  else
    note_fail "conditional command-not-found reached $pass_footers normal PASS footer(s)"
  fi
  if [[ "$rc" -ne 0 ]]; then
    note_pass "conditional command-not-found cannot be reported as a passing guard (exit $rc)"
  else
    note_fail "conditional command-not-found false-greened the guard (exit 0)"
  fi
  rm -f -- "$MUTATED_GUARD"
  MUTATED_GUARD=""
}

# Keep this awk-shaped historical fragment out of the runtime mutant above:
# the round-3 top-level mutation was insufficient because `set -e` made it red
# without exercising the conditional-command exception.
: <<'REMOVED_TOP_LEVEL_MUTANT'
  awk '
    { print }
    /^scan_void1$/ {
      print "test_validity_selftest_missing_command"
      inserted++
    }
    END {
      if (inserted != 1) exit 90
    }
  ' "$GUARD" > "$MUTATED_GUARD"
  chmod +x "$MUTATED_GUARD"
  inserted="$(grep -c '^test_validity_selftest_missing_command$' "$MUTATED_GUARD")"
  if [[ "$inserted" -eq 1 ]]; then
    note_pass "internal-error mutant is live exactly once"
  else
    note_fail "internal-error mutant insertion count (want 1, got $inserted)"
  fi
  if out="$("$MUTATED_GUARD" 2>&1)"; then
    rc=0
  else
    rc=$?
  fi
  if printf '%s\n' "$out" | grep -q 'test_validity_selftest_missing_command: command not found'; then
    note_pass "internal-error mutant executed the missing command"
  else
    note_fail "internal-error mutant did not emit its command-not-found diagnostic"
  fi
  if [[ "$rc" -ne 0 ]]; then
    note_pass "internal command-not-found cannot be reported as a passing guard (exit $rc)"
  else
    note_fail "internal command-not-found false-greened the guard (exit 0)"
  fi
  rm -f -- "$MUTATED_GUARD"
  MUTATED_GUARD=""
}
REMOVED_TOP_LEVEL_MUTANT

echo "=============================================================="
echo " Self-test: scripts/check-test-validity.sh (#850 detectors)"
echo "=============================================================="

echo
echo "[cleanup] SIGTERM removes only the interrupted invocation's fixtures"
run_sigterm_cleanup_regression

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

# C1 STALE (#2082): a baseline row whose file no longer has the smell must
# hard-fail. Mutation: point C1_BASELINE at a planted file with no
# assumeFalse(isRunningOnCi()). That is the EmulatorWorkflowE2eTest shape
# after its stopgap was deleted.
echo "[C1] stale baseline row (listed file has no remaining smell)"
cat > "$ANDROID_FIX_DIR/C1StaleBaselineTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class C1StaleBaselineTest {
    fun journey() { }
}
KT
MUTATED_GUARD="$(mktemp "scripts/.check-test-validity-c1-stale.$$.XXXXXX.sh")"
python3 - "$GUARD" "$MUTATED_GUARD" "$ANDROID_FIX_DIR/C1StaleBaselineTest.kt" <<'PY'
import sys
src, dest, row = sys.argv[1], sys.argv[2], sys.argv[3]
text = open(src).read()
anchor = "C1_BASELINE=(\n)"
assert text.count(anchor) == 1, "C1_BASELINE empty-array anchor not unique"
text = text.replace(anchor, f'C1_BASELINE=(\n  "{row}"\n)')
open(dest, "w").write(text)
PY
chmod +x "$MUTATED_GUARD"
if ! grep -q "C1StaleBaselineTest.kt" "$MUTATED_GUARD"; then
  note_fail "C1 stale mutation did not apply"
else
  set +e
  stale_out="$("$MUTATED_GUARD" 2>&1)"
  stale_rc=$?
  set -e
  if [[ "$stale_rc" -eq 1 ]] && grep -q "C1 — STALE" <<<"$stale_out" && grep -q "C1StaleBaselineTest.kt" <<<"$stale_out"; then
    note_pass "C1 stale baseline (no remaining smell) hard-fails, naming the file"
  else
    note_fail "C1 stale baseline did not hard-fail as STALE (rc=$stale_rc)
$(printf '%s\n' "$stale_out" | grep -E 'C1 —|FAIL:|PASS:' | head)"
  fi
fi
rm -f -- "$MUTATED_GUARD"
MUTATED_GUARD=""
rm -f "$ANDROID_FIX_DIR/C1StaleBaselineTest.kt"

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
# A5 — assumeTrue self-skip. Confirm the pre-existing IME detector and the
# topic-independent literal-false detector both fire.
# --------------------------------------------------------------------------
echo
echo "[A5] assumeTrue self-skip (IME heuristic + topic-independent literal false)"

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

cat > "$TEST_FIX_DIR/A5LBadGenericLiteralFalseTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
import org.junit.Assume.assumeTrue
class A5LBadGenericLiteralFalseTest {
    fun loadBearingAssertion() {
        assumeTrue("x", false)
        error("unreachable")
    }
}
KT

cat > "$TEST_FIX_DIR/A5LBadParenAwareLiteralTrueTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
import org.junit.Assume
class A5LBadParenAwareLiteralTrueTest {
    fun loadBearingAssertion() {
        Assume.assumeFalse(
            reasonWithNestedCall(listOf(1, 2)),
            ((true)),
        )
        error("unreachable")
    }
    private fun reasonWithNestedCall(value: List<Int>) = value.toString()
}
KT

cat > "$TEST_FIX_DIR/A5LBadBlockCommentCalleeTriviaTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
import org.junit.Assume.assumeTrue
class A5LBadBlockCommentCalleeTriviaTest {
    fun loadBearingAssertion() {
        assumeTrue /* legal Kotlin callee trivia */ (false)
        error("unreachable")
    }
}
KT

cat > "$TEST_FIX_DIR/A5LBadNewlineCommentCalleeTriviaTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
import org.junit.Assume
class A5LBadNewlineCommentCalleeTriviaTest {
    fun loadBearingAssertion() {
        Assume.assumeFalse
            // legal Kotlin newline/comment trivia before the argument list
            (
                true,
            )
        error("unreachable")
    }
}
KT

cat > "$TEST_FIX_DIR/A5LGoodDynamicAndDecoyTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
import org.junit.Assume.assumeTrue
class A5LGoodDynamicAndDecoyTest {
    fun loadBearingAssertion() {
        // Literal booleans nested inside a dynamic condition are not unconditional.
        assumeTrue("x", listOf(false, true).isNotEmpty())
        assumeTrue("the text assumeTrue(false) is not executable", dynamicCondition())
    }
    private fun dynamicCondition() = true
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
assert_report present "A5LBadGenericLiteralFalseTest.kt" "A5L — NEW" "A5L fires on a generic literal-false assumeTrue self-skip"
assert_report present "A5LBadParenAwareLiteralTrueTest.kt" "A5L — NEW" "A5L matches a multiline assumeFalse with nested commas, redundant parens, and a trailing comma"
assert_report present "A5LBadBlockCommentCalleeTriviaTest.kt" "A5L — NEW" "A5L matches legal block-comment trivia between the callee and opening parenthesis"
assert_report present "A5LBadNewlineCommentCalleeTriviaTest.kt" "A5L — NEW" "A5L matches legal newline/comment trivia between the callee and opening parenthesis"
assert_report absent  "A5LGoodDynamicAndDecoyTest.kt" "A5L — NEW" "A5L spares nested dynamic booleans and string/comment decoys"
assert_report absent  "A5GoodSdkGuardTest.kt" "A5 — NEW" "A5 spares a Build.VERSION SDK guard"
# A5 bad + C1 GOOD-only present here -> A5 is a hard-fail category.
assert_exit 1 "A5 unjustified IME skip hard-fails the guard"

# Remove the A5 bad so the final clean-state assertion holds.
rm -f \
  "$ANDROID_FIX_DIR/A5BadImeTest.kt" \
  "$TEST_FIX_DIR/A5LBadGenericLiteralFalseTest.kt" \
  "$TEST_FIX_DIR/A5LBadParenAwareLiteralTrueTest.kt" \
  "$TEST_FIX_DIR/A5LBadBlockCommentCalleeTriviaTest.kt" \
  "$TEST_FIX_DIR/A5LBadNewlineCommentCalleeTriviaTest.kt"

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

# BAD-PLAIN-PORTFWD (#2026): the exact predecessor shape from
# ForwardingServiceObserverGenerationTest. It is deliberately ordinary JUnit
# (no runTest), computes a local 5 s currentTimeMillis deadline, then sleeps and
# rereads the notification inside the while pump.
cat > "$TIMING_PORTFWD_FIX_DIR/Timing1BadPortfwdLegacyPumpTest.kt" <<'KT'
package com.pocketshell.app.portfwd.validityselftest
class Timing1BadPortfwdLegacyPumpTest {
    private fun awaitForwardingNotificationText(expected: String): String? {
        val deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MS
        var text = forwardingNotificationText()
        while (text != expected && System.currentTimeMillis() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            text = forwardingNotificationText()
        }
        return text
    }
    private fun forwardingNotificationText(): String? = null
    private companion object {
        const val SETTLE_TIMEOUT_MS = 5_000L
        const val POLL_INTERVAL_MS = 20L
    }
}
KT

# BAD-PLAIN-PREFS (#2026): the exact predecessor shape from
# DeferredPrefsCorruptPrefsTest. It uses nanoTime plus the ordered latch/BLOCKED
# observations and likewise has no runTest branch for the detector to lean on.
cat > "$TIMING_PREFS_FIX_DIR/Timing1BadPrefsLegacyPumpTest.kt" <<'KT'
package com.pocketshell.app.prefs.validityselftest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class Timing1BadPrefsLegacyPumpTest {
    private val secondThrowingOpenStarted = CountDownLatch(1)
    fun awaitSecondThrowingOpenOrColdGetBlocked(coldGetThread: Thread): Boolean {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (secondThrowingOpenStarted.await(10, TimeUnit.MILLISECONDS)) return true
            if (coldGetThread.state == Thread.State.BLOCKED) return false
        }
        throw AssertionError("cold get neither raced nor blocked")
    }
}
KT

# BAD-PLAIN-PORTFWD-MULTILINE (#2026 round 3): the same predecessor semantics
# with an ordinary wrapped deadline declaration and while condition. Formatting
# cannot turn local wall-clock loop ownership into a clean result.
cat > "$TIMING_PORTFWD_FIX_DIR/Timing1BadPortfwdMultilinePumpTest.kt" <<'KT'
package com.pocketshell.app.portfwd.validityselftest
class Timing1BadPortfwdMultilinePumpTest {
    private fun awaitForwardingNotificationText(expected: String): String? {
        val deadline =
            System.currentTimeMillis() + 5_000L
        var text = forwardingNotificationText()
        while (
            text != expected &&
            System.currentTimeMillis() < deadline
        ) {
            Thread.sleep(20L)
            text = forwardingNotificationText()
        }
        return text
    }
    private fun forwardingNotificationText(): String? = null
}
KT

# BAD-PLAIN-PREFS-MULTILINE (#2026 round 3): the nanoTime predecessor keeps its
# ordered latch/BLOCKED observations while both its typed deadline declaration
# and loop comparison use normal multiline Kotlin formatting.
cat > "$TIMING_PREFS_FIX_DIR/Timing1BadPrefsMultilinePumpTest.kt" <<'KT'
package com.pocketshell.app.prefs.validityselftest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class Timing1BadPrefsMultilinePumpTest {
    private val secondThrowingOpenStarted = CountDownLatch(1)
    fun awaitSecondThrowingOpenOrColdGetBlocked(coldGetThread: Thread): Boolean {
        val deadline: Long =
            System.nanoTime() +
                TimeUnit.SECONDS.toNanos(5)
        while (
            System.nanoTime() <
                deadline
        ) {
            if (secondThrowingOpenStarted.await(10, TimeUnit.MILLISECONDS)) return true
            if (coldGetThread.state == Thread.State.BLOCKED) return false
        }
        throw AssertionError("cold get neither raced nor blocked")
    }
}
KT

# BAD lexical control: comments are allowed between executable tokens. The
# sanitizer must erase them without erasing the real multiline deadline pump.
cat > "$TIMING_PORTFWD_FIX_DIR/Timing1BadCommentInterleavedPumpTest.kt" <<'KT'
package com.pocketshell.app.portfwd.validityselftest
class Timing1BadCommentInterleavedPumpTest {
    fun awaitCondition(): Boolean {
        val deadline =
            System.currentTimeMillis() /* still executable */ + 5_000L
        while (System.currentTimeMillis() < /* compare */ deadline) {
            Thread.sleep(20L)
        }
        return false
    }
}
KT

# BAD lexical control (#2026 round 4): executable block-comment trivia may
# legally separate a clock method name from its call parentheses. Both the
# declaration and while comparison use that spelling, so normal sanitizing
# leaves whitespace exactly where a contiguous `currentTimeMillis()` matcher
# used to false-green the forbidden portfwd pump.
cat > "$TIMING_PORTFWD_FIX_DIR/Timing1BadPortfwdInsideCallTriviaPumpTest.kt" <<'KT'
package com.pocketshell.app.portfwd.validityselftest
class Timing1BadPortfwdInsideCallTriviaPumpTest {
    fun awaitCondition(): Boolean {
        val deadline =
            System.currentTimeMillis /* declaration clock trivia */ () + 5_000L
        while (
            System.currentTimeMillis /* comparison clock trivia */ () < deadline
        ) {
            Thread.sleep(20L)
            rereadNotification()
        }
        return false
    }
    private fun rereadNotification() = Unit
}
KT

# BAD lexical control (#2026 round 4): nanoTime has the same legal inside-call
# trivia in the prefs predecessor, including its ordered latch/BLOCKED drain.
cat > "$TIMING_PREFS_FIX_DIR/Timing1BadPrefsInsideCallTriviaPumpTest.kt" <<'KT'
package com.pocketshell.app.prefs.validityselftest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class Timing1BadPrefsInsideCallTriviaPumpTest {
    private val secondThrowingOpenStarted = CountDownLatch(1)
    fun awaitSecondThrowingOpenOrColdGetBlocked(coldGetThread: Thread): Boolean {
        val deadline =
            System.nanoTime /* declaration clock trivia */ () +
                TimeUnit.SECONDS.toNanos(5)
        while (
            System.nanoTime /* comparison clock trivia */ () < deadline
        ) {
            if (secondThrowingOpenStarted.await(10, TimeUnit.MILLISECONDS)) return true
            if (coldGetThread.state == Thread.State.BLOCKED) return false
        }
        throw AssertionError("cold get neither raced nor blocked")
    }
}
KT

# GOOD lexical control: semantically complete pump text that exists only in
# ordinary/raw strings and comments must not manufacture a TIMING1 finding.
cat > "$TIMING_PREFS_FIX_DIR/Timing1GoodDeadlinePumpTextTest.kt" <<'KT'
package com.pocketshell.app.prefs.validityselftest
class Timing1GoodDeadlinePumpTextTest {
    val ordinary = "val deadline = System.nanoTime() + 5_000L; while (System.nanoTime() < deadline)"
    val raw = """
        val deadline =
            System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) { Thread.sleep(20L) }
    """.trimIndent()
    /*
     * val deadline =
     *     System.nanoTime() + 5_000L
     * while (System.nanoTime() < deadline) { Thread.sleep(20L) }
     */
}
KT

# GOOD-PLAIN-PORTFWD: candidate shape. The same sleep + notification reread is
# injected verbatim through onTick, while only drainMainLooperUntil owns the
# deadline and loop.
cat > "$TIMING_PORTFWD_FIX_DIR/Timing1GoodPortfwdSharedPumpTest.kt" <<'KT'
package com.pocketshell.app.portfwd.validityselftest
class Timing1GoodPortfwdSharedPumpTest {
    private fun awaitForwardingNotificationText(expected: String): String? {
        var text = forwardingNotificationText()
        val settled = drainMainLooperUntil(
            sleepMs = 0L,
            onTick = {
                Thread.sleep(POLL_INTERVAL_MS)
                text = forwardingNotificationText()
            },
            condition = { text == expected },
        )
        if (!settled) throw AssertionError("timed out")
        return text
    }
    private fun forwardingNotificationText(): String? = null
    private fun drainMainLooperUntil(
        sleepMs: Long,
        onTick: () -> Unit,
        condition: () -> Boolean,
    ): Boolean = condition()
    private companion object { const val POLL_INTERVAL_MS = 20L }
}
KT

# GOOD-PLAIN-PREFS: candidate shape. The ordered latch/BLOCKED observations are
# preserved through onTick and the shared helper remains the sole loop owner.
cat > "$TIMING_PREFS_FIX_DIR/Timing1GoodPrefsSharedPumpTest.kt" <<'KT'
package com.pocketshell.app.prefs.validityselftest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class Timing1GoodPrefsSharedPumpTest {
    private val secondThrowingOpenStarted = CountDownLatch(1)
    fun awaitSecondThrowingOpenOrColdGetBlocked(coldGetThread: Thread): Boolean {
        var result: Boolean? = null
        val settled = drainMainLooperUntil(
            sleepMs = 0L,
            onTick = {
                if (secondThrowingOpenStarted.await(10, TimeUnit.MILLISECONDS)) {
                    result = true
                } else if (coldGetThread.state == Thread.State.BLOCKED) {
                    result = false
                }
            },
            condition = { result != null },
        )
        if (!settled) throw AssertionError("cold get neither raced nor blocked")
        return checkNotNull(result)
    }
    private fun drainMainLooperUntil(
        sleepMs: Long,
        onTick: () -> Unit,
        condition: () -> Boolean,
    ): Boolean = condition()
}
KT

# GOOD-PLAIN-BOUNDARY: a bounded one-shot coordination helper is sound and is
# not a hand-rolled polling loop. This protects TIMING1 from broadening into a
# ban on every legitimate timeout under the two roots.
cat > "$TIMING_PORTFWD_FIX_DIR/Timing1GoodBoundedLatchTest.kt" <<'KT'
package com.pocketshell.app.portfwd.validityselftest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
class Timing1GoodBoundedLatchTest {
    fun waitForBoundary(latch: CountDownLatch) {
        check(latch.await(5, TimeUnit.SECONDS))
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
assert_report present "Timing1BadPortfwdLegacyPumpTest.kt" "TIMING1 — hand-rolled wall-clock deadline pump" "TIMING1 catches the exact plain-JUnit portfwd predecessor without runTest"
assert_report present "Timing1BadPrefsLegacyPumpTest.kt" "TIMING1 — hand-rolled wall-clock deadline pump" "TIMING1 catches the exact plain-JUnit prefs predecessor without runTest"
assert_report present "Timing1BadPortfwdMultilinePumpTest.kt" "TIMING1 — hand-rolled wall-clock deadline pump" "TIMING1 catches a semantically equivalent multiline portfwd pump"
assert_report present "Timing1BadPrefsMultilinePumpTest.kt" "TIMING1 — hand-rolled wall-clock deadline pump" "TIMING1 catches a semantically equivalent multiline prefs pump"
assert_report present "Timing1BadCommentInterleavedPumpTest.kt" "TIMING1 — hand-rolled wall-clock deadline pump" "TIMING1 catches a real multiline pump with block comments between tokens"
assert_report present "Timing1BadPortfwdInsideCallTriviaPumpTest.kt" "TIMING1 — hand-rolled wall-clock deadline pump" "TIMING1 catches portfwd executable trivia inside both clock calls"
assert_report present "Timing1BadPrefsInsideCallTriviaPumpTest.kt" "TIMING1 — hand-rolled wall-clock deadline pump" "TIMING1 catches prefs executable trivia inside both clock calls"
assert_report absent  "Timing1GoodDeadlinePumpTextTest.kt" "TIMING1 — hand-rolled wall-clock deadline pump" "TIMING1 ignores multiline pump text in strings and comments"
assert_report absent  "Timing1GoodPortfwdSharedPumpTest.kt" "TIMING1 — hand-rolled wall-clock deadline pump" "TIMING1 spares the migrated portfwd shared-pump shape"
assert_report absent  "Timing1GoodPrefsSharedPumpTest.kt" "TIMING1 — hand-rolled wall-clock deadline pump" "TIMING1 spares the migrated prefs shared-pump shape"
assert_report absent  "Timing1GoodBoundedLatchTest.kt" "TIMING1 — hand-rolled wall-clock deadline pump" "TIMING1 spares a sound one-shot bounded coordination helper"
assert_exit 1 "TIMING1 bare sleep-before-assert hard-fails the guard"

# Prove every #2026 deadline-pump fixture bears the guard exit independently.
# Park all offenders outside TIMING1's scoped roots, restore exactly one at a
# time, and require both one named finding and no sibling finding. This makes a
# broad/confounded red distinguishable from a selective detector kill.
rm -f "$TIMING_FIX_DIR/Timing1BadSleepBeforeAssertTest.kt"
TIMING1_BAD_PATHS=(
  "$TIMING_PORTFWD_FIX_DIR/Timing1BadPortfwdLegacyPumpTest.kt"
  "$TIMING_PREFS_FIX_DIR/Timing1BadPrefsLegacyPumpTest.kt"
  "$TIMING_PORTFWD_FIX_DIR/Timing1BadPortfwdMultilinePumpTest.kt"
  "$TIMING_PREFS_FIX_DIR/Timing1BadPrefsMultilinePumpTest.kt"
  "$TIMING_PORTFWD_FIX_DIR/Timing1BadCommentInterleavedPumpTest.kt"
  "$TIMING_PORTFWD_FIX_DIR/Timing1BadPortfwdInsideCallTriviaPumpTest.kt"
  "$TIMING_PREFS_FIX_DIR/Timing1BadPrefsInsideCallTriviaPumpTest.kt"
)
for timing_bad_path in "${TIMING1_BAD_PATHS[@]}"; do
  timing_bad_name="${timing_bad_path##*/}"
  mv "$timing_bad_path" "$TEST_FIX_DIR/$timing_bad_name.held"
done

for timing_bad_path in "${TIMING1_BAD_PATHS[@]}"; do
  timing_bad_name="${timing_bad_path##*/}"
  mv "$TEST_FIX_DIR/$timing_bad_name.held" "$timing_bad_path"
  assert_report \
    present \
    "$timing_bad_name" \
    "TIMING1 — hand-rolled wall-clock deadline pump" \
    "TIMING1 $timing_bad_name is the sole restored deadline-pump finding"
  for timing_sibling_path in "${TIMING1_BAD_PATHS[@]}"; do
    timing_sibling_name="${timing_sibling_path##*/}"
    [[ "$timing_sibling_name" == "$timing_bad_name" ]] && continue
    assert_report \
      absent \
      "$timing_sibling_name" \
      "TIMING1 — hand-rolled wall-clock deadline pump" \
      "TIMING1 $timing_bad_name does not launder a sibling $timing_sibling_name finding"
  done
  assert_exit 1 "TIMING1 $timing_bad_name independently hard-fails the guard"
  mv "$timing_bad_path" "$TEST_FIX_DIR/$timing_bad_name.held"
done

# Remove every held BAD TIMING1. The end-of-script clean-state assertion now
# proves the shared-pump and lexical text controls remain green without an
# offender supplying their result.
rm -f "$TEST_FIX_DIR"/Timing1Bad*.held

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

    // Property-shaped counterpart: assigning this can mask the same real-path
    // failure as the call-shaped seam above.
    var forceSyntheticTransportAliveForTest: Boolean? = null

    // These definitions prove call/property resolution stays kind-specific.
    fun forceFunctionKindOnlyForTest(active: Boolean) {}
    var forcePropertyKindOnlyForTest: Boolean = false

    // Production test knob outside the deliberately narrow injection-name
    // shape. SEAM1 must not broaden to every ForTest property.
    var passiveTimeoutForTest: Long = 0L

    // Lexical decoys are not production definitions.
    val stringDefinitionDecoy = "var forceStringDefinitionOnlyForTest: Boolean = false"
    val commentDefinitionDecoy = 1 /* var forceCommentDefinitionOnlyForTest: Boolean = false */
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

# BAD property assignment: production-defined, injection-shaped, and unvetted.
cat > "$ANDROID_FIX_DIR/Seam1BadPropertyAssignmentTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1BadPropertyAssignmentTest {
    fun transportLooksAlive() {
        seam.forceSyntheticTransportAliveForTest = true
        assertTrue(transportLooksAliveNow())
    }
    private val seam = Seam1ProdSeam()
    private fun transportLooksAliveNow() = true
    private fun assertTrue(b: Boolean) {}
}
KT

# GOOD-4 (vetted property): this production property is registry-listed with
# real-path reasons for both of its production owners.
cat > "$ANDROID_FIX_DIR/Seam1GoodVettedPropertyAssignmentTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1GoodVettedPropertyAssignmentTest {
    fun transportRideThrough() {
        controller.forceTransportProvenAliveForTest = true
    }
    private val controller = FakeController()
    class FakeController { var forceTransportProvenAliveForTest: Boolean? = null }
}
KT

# GOOD-5 (inline-justified property): assignment-line opt-out semantics are the
# same as call-line semantics.
cat > "$ANDROID_FIX_DIR/Seam1GoodJustifiedPropertyAssignmentTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1GoodJustifiedPropertyAssignmentTest {
    fun oneOffPropertyPin() {
        seam.forceSyntheticTransportAliveForTest = true // SEAM_JUSTIFIED: selftest one-off reachable state
    }
    private val seam = Seam1ProdSeam()
}
KT

# GOOD-6 (test-local property): injection-shaped assignment with no production
# definition must remain outside SEAM1.
cat > "$ANDROID_FIX_DIR/Seam1GoodLocalPropertyAssignmentTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1GoodLocalPropertyAssignmentTest {
    fun usesLocalProperty() {
        forceLocalOnlyPropertyForTest = true
    }
    private var forceLocalOnlyPropertyForTest: Boolean = false
}
KT

# GOOD-7 (non-injection property): production ForTest configuration knobs stay
# outside the deliberately narrow force*/Override*/set*Active* shape.
cat > "$ANDROID_FIX_DIR/Seam1GoodNonInjectionPropertyAssignmentTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1GoodNonInjectionPropertyAssignmentTest {
    fun shortensTimeout() {
        seam.passiveTimeoutForTest = 10L
    }
    private val seam = Seam1ProdSeam()
}
KT

# GOOD-8 (property read/comparison): a high-signal property name by itself is
# not an assignment; in particular, `==` must not trip the assignment matcher.
cat > "$ANDROID_FIX_DIR/Seam1GoodPropertyComparisonTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1GoodPropertyComparisonTest {
    fun readsInjectedState() {
        assertTrue(seam.forceSyntheticTransportAliveForTest == true)
    }
    private val seam = Seam1ProdSeam()
    private fun assertTrue(b: Boolean) {}
}
KT

# GOOD-9 (named arguments): a production property name used as an inline or
# multiline Kotlin named argument is not a write to that property.
cat > "$ANDROID_FIX_DIR/Seam1GoodNamedArgumentTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1GoodNamedArgumentTest {
    fun configuresFake() {
        configure(forceSyntheticTransportAliveForTest = true)
        configure(
            other = false,
            forceSyntheticTransportAliveForTest /* argument docs */ = true,
        )
    }
    private fun configure(
        other: Boolean = false,
        forceSyntheticTransportAliveForTest: Boolean,
    ) {}
}
KT

# GOOD-10 (non-code assignment text): strings, raw strings, line comments, and
# trailing comments must not be classified as property writes.
cat > "$ANDROID_FIX_DIR/Seam1GoodNonCodeAssignmentTextTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1GoodNonCodeAssignmentTextTest {
    fun documentsTheSeam() {
        val ordinary = "forceSyntheticTransportAliveForTest = true"
        val raw = """forceSyntheticTransportAliveForTest = true"""
        val ordinaryNested = "value=${"forceSyntheticTransportAliveForTest = true"}"
        val rawNested = """value=${"forceSyntheticTransportAliveForTest = true"}"""
        // forceSyntheticTransportAliveForTest = true
        doWork() // forceSyntheticTransportAliveForTest = true
        require(
            ordinary.isNotBlank() &&
                raw.isNotBlank() &&
                ordinaryNested.isNotBlank() &&
                rawNested.isNotBlank(),
        )
    }
    private fun doWork() {}
}
KT

# GOOD-11 (fake production definitions): these injection-shaped writes resolve
# only to `var ...ForTest` text inside a production string/comment, never to a
# real production property, so the production-defined filter must ignore them.
cat > "$ANDROID_FIX_DIR/Seam1GoodFakeProductionDefinitionTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1GoodFakeProductionDefinitionTest {
    fun usesTestDoubleProperties() {
        fake.forceStringDefinitionOnlyForTest = true
        fake.forceCommentDefinitionOnlyForTest = true
    }
    private val fake = Fake()
    class Fake {
        var forceStringDefinitionOnlyForTest: Boolean = false
        var forceCommentDefinitionOnlyForTest: Boolean = false
    }
}
KT

# GOOD-12 (definition-kind separation): a production function is not enough to
# make assignment syntax a property seam, and a production property is not
# enough to make call syntax a function seam.
cat > "$ANDROID_FIX_DIR/Seam1GoodDefinitionKindSeparationTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1GoodDefinitionKindSeparationTest {
    fun mismatchedSyntaxStaysOutOfScope() {
        fake.forceFunctionKindOnlyForTest = true
        fake.forcePropertyKindOnlyForTest(true)
    }
    private val fake = Fake()
    class Fake {
        var forceFunctionKindOnlyForTest: Boolean = false
        fun forcePropertyKindOnlyForTest(active: Boolean) {}
    }
}
KT

# GOOD-13 (real preceding comment): an actual source comment directly above an
# occurrence remains a valid opt-out.
cat > "$ANDROID_FIX_DIR/Seam1GoodPrecedingCommentJustificationTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1GoodPrecedingCommentJustificationTest {
    fun oneOffPropertyPin() {
        // SEAM_JUSTIFIED: selftest real preceding source comment
        seam.forceSyntheticTransportAliveForTest = true
    }
    private val seam = Seam1ProdSeam()
}
KT

# BAD: a block comment between the property name and `=` is legal Kotlin and
# must not hide a real production assignment.
cat > "$ANDROID_FIX_DIR/Seam1BadBlockCommentAssignmentTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1BadBlockCommentAssignmentTest {
    fun transportLooksAlive() {
        seam.forceSyntheticTransportAliveForTest /* deterministic input */ = true
    }
    private val seam = Seam1ProdSeam()
}
KT

# BAD: preserve detection when the assignment line ends at `=` and the RHS is
# on the next line.
cat > "$ANDROID_FIX_DIR/Seam1BadMultilineRhsAssignmentTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1BadMultilineRhsAssignmentTest {
    fun transportLooksAlive() {
        seam.forceSyntheticTransportAliveForTest =
            true
    }
    private val seam = Seam1ProdSeam()
}
KT

# BAD: marker text in ordinary and raw strings is not a source-comment opt-out,
# whether it is on the occurrence line or directly above it.
cat > "$ANDROID_FIX_DIR/Seam1BadInlineStringJustificationTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1BadInlineStringJustificationTest {
    fun transportLooksAlive() {
        val reason = "SEAM_JUSTIFIED: not a comment"; seam.forceSyntheticTransportAliveForTest = true
        require(reason.isNotBlank())
    }
    private val seam = Seam1ProdSeam()
}
KT

cat > "$ANDROID_FIX_DIR/Seam1BadPrecedingStringJustificationTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1BadPrecedingStringJustificationTest {
    fun transportLooksAlive() {
        val reason = """SEAM_JUSTIFIED: still not a comment"""
        seam.forceSyntheticTransportAliveForTest = true
        require(reason.isNotBlank())
    }
    private val seam = Seam1ProdSeam()
}
KT

cat > "$ANDROID_FIX_DIR/Seam1BadOrdinaryTemplateAssignmentTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1BadOrdinaryTemplateAssignmentTest {
    fun executesAssignment() {
        val result = "${run { seam.forceSyntheticTransportAliveForTest = true; Unit }}"
        require(result.isNotBlank())
    }
    private val seam = Seam1ProdSeam()
}
KT

cat > "$ANDROID_FIX_DIR/Seam1BadRawTemplateAssignmentTest.kt" <<'KT'
package com.pocketshell.app.validityselftest
class Seam1BadRawTemplateAssignmentTest {
    fun executesAssignment() {
        val result = """${run { seam.forceSyntheticTransportAliveForTest = false; Unit }}"""
        require(result.isNotBlank())
    }
    private val seam = Seam1ProdSeam()
}
KT

assert_report present "Seam1BadAltBufferCheatTest.kt" "SEAM1 — NEW" "SEAM1 fires on an unvetted production state-injection seam (reconstructs the #1158 alt-buffer cheat)"
assert_report absent  "Seam1GoodVettedTest.kt" "SEAM1 — NEW" "SEAM1 spares a registry-vetted seam (forceTreeStaleForTest)"
assert_report absent  "Seam1GoodJustifiedTest.kt" "SEAM1 — NEW" "SEAM1 spares a // SEAM_JUSTIFIED: opt-out"
assert_report absent  "Seam1GoodLocalHelperTest.kt" "SEAM1 — NEW" "SEAM1 ignores a non-production test-double helper of the same shape"
assert_report present "Seam1BadPropertyAssignmentTest.kt" "SEAM1 — NEW" "SEAM1 fires on an unvetted production property assignment"
assert_report absent  "Seam1GoodVettedPropertyAssignmentTest.kt" "SEAM1 — NEW" "SEAM1 spares a registry-vetted production property assignment"
assert_report absent  "Seam1GoodJustifiedPropertyAssignmentTest.kt" "SEAM1 — NEW" "SEAM1 spares an inline // SEAM_JUSTIFIED: property assignment"
assert_report absent  "Seam1GoodLocalPropertyAssignmentTest.kt" "SEAM1 — NEW" "SEAM1 ignores a property defined only in test code"
assert_report absent  "Seam1GoodNonInjectionPropertyAssignmentTest.kt" "SEAM1 — NEW" "SEAM1 keeps non-injection ForTest properties outside its high-signal shape"
assert_report absent  "Seam1GoodPropertyComparisonTest.kt" "SEAM1 — NEW" "SEAM1 does not mistake a property comparison for an assignment"
assert_report absent  "Seam1GoodNamedArgumentTest.kt" "SEAM1 — NEW" "SEAM1 does not mistake Kotlin named arguments for property assignments"
assert_report absent  "Seam1GoodNonCodeAssignmentTextTest.kt" "SEAM1 — NEW" "SEAM1 ignores assignment-shaped text in strings and comments"
assert_report absent  "Seam1GoodFakeProductionDefinitionTest.kt" "SEAM1 — NEW" "SEAM1 ignores production definitions that exist only in strings or comments"
assert_report absent  "Seam1GoodDefinitionKindSeparationTest.kt" "SEAM1 — NEW" "SEAM1 keeps function-call and property-assignment definition kinds separate"
assert_report absent  "Seam1GoodPrecedingCommentJustificationTest.kt" "SEAM1 — NEW" "SEAM1 accepts a real source comment directly above an assignment"
assert_report present "Seam1BadBlockCommentAssignmentTest.kt" "SEAM1 — NEW" "SEAM1 detects a property assignment split from '=' by a block comment"
assert_report present "Seam1BadMultilineRhsAssignmentTest.kt" "SEAM1 — NEW" "SEAM1 preserves multiline-RHS property assignment detection"
assert_report present "Seam1BadInlineStringJustificationTest.kt" "SEAM1 — NEW" "SEAM1 rejects a same-line string-literal justification spoof"
assert_report present "Seam1BadPrecedingStringJustificationTest.kt" "SEAM1 — NEW" "SEAM1 rejects a preceding-line string-literal justification spoof"
assert_report present "Seam1BadOrdinaryTemplateAssignmentTest.kt" "SEAM1 — NEW" "SEAM1 detects an executable assignment inside an ordinary string template"
assert_report present "Seam1BadRawTemplateAssignmentTest.kt" "SEAM1 — NEW" "SEAM1 detects an executable assignment inside a raw string template"
assert_exit 1 "SEAM1 unvetted production state-injection seam hard-fails the guard"

# Remove the BAD SEAM1 calls and assignments so the registry-error and
# clean-state checks below are not confounded by their hard-fails.
rm -f \
  "$ANDROID_FIX_DIR/Seam1BadAltBufferCheatTest.kt" \
  "$ANDROID_FIX_DIR/Seam1BadPropertyAssignmentTest.kt" \
  "$ANDROID_FIX_DIR/Seam1BadBlockCommentAssignmentTest.kt" \
  "$ANDROID_FIX_DIR/Seam1BadMultilineRhsAssignmentTest.kt" \
  "$ANDROID_FIX_DIR/Seam1BadInlineStringJustificationTest.kt" \
  "$ANDROID_FIX_DIR/Seam1BadPrecedingStringJustificationTest.kt" \
  "$ANDROID_FIX_DIR/Seam1BadOrdinaryTemplateAssignmentTest.kt" \
  "$ANDROID_FIX_DIR/Seam1BadRawTemplateAssignmentTest.kt"

# --------------------------------------------------------------------------
# SEAM1 stale-registry hygiene remains advisory: a justified name that no
# longer has any real production definition is surfaced but does not hard-fail.
# --------------------------------------------------------------------------
echo
echo "[SEAM1] stale registry entry remains visible and advisory"

TMP_REG="$(mktemp)"
cat "scripts/vetted-test-state-setters.txt" > "$TMP_REG"
printf '\nforceRemovedGhostForTest  # selftest stale entry; no production definition exists\n' >> "$TMP_REG"
export VETTED_SEAM_REGISTRY="$TMP_REG"

assert_report present "forceRemovedGhostForTest" "SEAM1 — STALE registry entry" "SEAM1 reports a registry name with no real production definition as stale"
assert_exit 0 "SEAM1 stale registry entry remains advisory"

unset VETTED_SEAM_REGISTRY
rm -f "$TMP_REG"
TMP_REG=""

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
TMP_REG=""

# --------------------------------------------------------------------------
# Clean state: only corrective/advisory fixtures remain -> guard must PASS.
# --------------------------------------------------------------------------
echo
echo "[clean] only corrective shapes + advisory findings remain"
assert_exit 0 "guard passes (exit 0) with no hard-fail smells, only advisory findings"
assert_guard_runtime_clean "guard clean state emits no internal shell/runtime diagnostic"
run_guard_runtime_error_regression

echo
echo "=============================================================="
echo " Self-test result: $PASS passed, $FAIL failed"
echo "=============================================================="
if [[ "$FAIL" -gt 0 ]]; then
  exit 1
fi
exit 0
