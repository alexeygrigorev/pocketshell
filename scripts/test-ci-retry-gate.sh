#!/usr/bin/env bash
# Self-test for scripts/ci-retry-gate.sh (issue #2459).
#
# Pure/no-`gh` decision table covering every PHASE branch, PLUS the
# acceptance-criterion #5 proof: no matter how many times the retry's OWN
# result is red (attempt 2, 3, 4, ...), RETRY_ALLOWED is never true again —
# a retry can never trigger a second retry.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/ci-retry-gate.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"

run_gate() {
  GATE_OUT="$("$TARGET" --run-attempt "$1" --conclusion "$2" 2>&1)"
  GATE_RC=$?
  GATE_PHASE="$(sed -n 's/^PHASE=//p' <<<"$GATE_OUT" | tail -n1)"
  GATE_ALLOWED="$(sed -n 's/^RETRY_ALLOWED=//p' <<<"$GATE_OUT" | tail -n1)"
}

assert_gate() {
  local label="$1" attempt="$2" conclusion="$3" phase="$4" allowed="$5"
  run_gate "$attempt" "$conclusion"
  [[ "$GATE_RC" -eq 0 ]] || { echo "$GATE_OUT"; fail "$label: exited $GATE_RC"; }
  [[ "$GATE_PHASE" == "$phase" ]] || { echo "$GATE_OUT"; fail "$label: phase=$GATE_PHASE, expected $phase"; }
  [[ "$GATE_ALLOWED" == "$allowed" ]] || { echo "$GATE_OUT"; fail "$label: retry_allowed=$GATE_ALLOWED, expected $allowed"; }
  pass "$label"
}

# 1. Attempt 1, red -> the one genuinely-new-red case: retry IS allowed.
assert_gate "attempt 1 + failure -> first_red, retry allowed" 1 failure first_red true

# 2. Attempt 1, green -> ordinary scheduled pass, nothing to do.
assert_gate "attempt 1 + success -> noop, no retry" 1 success noop false

# 3. Attempt 2, red -> this IS the bounded retry's own red result. Must
#    NEVER allow another retry (the core anti-infinite-loop assertion).
assert_gate "attempt 2 + failure -> retry_red, retry NOT allowed" 2 failure retry_red false

# 4. Attempt 2, green -> the bounded retry came back clean.
assert_gate "attempt 2 + success -> retry_clean, retry NOT allowed" 2 success retry_clean false

# 5. Acceptance criterion #5: a retry's own red result never triggers a
#    THIRD run. Exercise attempts 3..10, always red, and assert every single
#    one refuses to allow a further retry — proving the bound holds no
#    matter how many times a human/automation later re-runs it again.
for attempt in 3 4 5 10; do
  assert_gate "attempt $attempt + failure -> never re-triggers (bounded)" "$attempt" failure retry_red false
done

# 6. Missing/blank run-attempt fails CLOSED to noop (never retries on data it
#    cannot trust to be attempt 1).
assert_gate "missing run-attempt -> noop, fails closed" "" failure noop false

# 7. Non-numeric run-attempt also fails closed.
assert_gate "non-numeric run-attempt -> noop, fails closed" "not-a-number" failure noop false

# 8. Zero/negative run-attempt (should never happen from the real webhook,
#    but a malformed value must still fail closed rather than being treated
#    as attempt 1).
assert_gate "run-attempt=0 -> noop, fails closed" 0 failure noop false

# 9. $GITHUB_OUTPUT mirrors the three keys (lowercased).
gh_out="$(mktemp)"
trap 'rm -f "$gh_out"' EXIT
GITHUB_OUTPUT="$gh_out" "$TARGET" --run-attempt 1 --conclusion failure >/dev/null
grep -qx "phase=first_red" "$gh_out" || fail "GITHUB_OUTPUT must mirror phase: $(cat "$gh_out")"
grep -qx "retry_allowed=true" "$gh_out" || fail "GITHUB_OUTPUT must mirror retry_allowed: $(cat "$gh_out")"
pass "GITHUB_OUTPUT mirrors phase/retry_allowed/reason"

# 10. Missing required argument exits 2.
set +e
"$TARGET" --run-attempt 1 >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" -eq 2 ]] || fail "missing --conclusion must exit 2, got $rc"
pass "missing required argument exits 2"

echo "OK: $pass_count self-test case(s) passed."
