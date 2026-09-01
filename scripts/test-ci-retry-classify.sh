#!/usr/bin/env bash
# Self-test for scripts/ci-retry-classify.sh (issue #2459).
#
# Pure/no-`gh` classification-table test, covering acceptance criterion #6:
# a synthetic clean-retry case (classified infra) and a synthetic
# same-signature-retry case (classified regression), plus the
# differing-signature, degraded-capture, empty-original, and missing-file
# edges.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TARGET="$SCRIPT_DIR/ci-retry-classify.sh"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass_count=0
pass() { pass_count=$((pass_count + 1)); echo "PASS: $*"; }

[[ -f "$TARGET" ]] || fail "target script not found: $TARGET"

SANDBOX="$(mktemp -d)"
trap 'rm -rf "$SANDBOX"' EXIT

write_sig() {
  # $1 = path, $2 = status (ok|degraded:...), $3.. = token lines
  local path="$1" status="$2"
  shift 2
  : > "$path"
  local line
  for line in "$@"; do
    printf '%s\n' "$line" >> "$path"
  done
  printf '# status=%s\n' "$status" >> "$path"
}

run_classify() {
  CLASSIFY_OUT="$("$TARGET" "$@" 2>&1)"
  CLASSIFY_RC=$?
  CLASSIFICATION="$(sed -n 's/^CLASSIFICATION=//p' <<<"$CLASSIFY_OUT" | tail -n1)"
}

# -----------------------------------------------------------------------
# AC#6 case A: synthetic CLEAN retry -> infra, no escalation.
orig="$SANDBOX/orig-a.txt"
write_sig "$orig" ok "job:Emulator journey subset (load-bearing, Docker agents) (2)" \
  "class:com.pocketshell.app.hosts.ReconnectJourneyE2eTest#reconnectAfterBackground"
run_classify --original-signature "$orig" --retry-conclusion success
[[ "$CLASSIFY_RC" -eq 0 ]] || fail "clean-retry case: exited $CLASSIFY_RC: $CLASSIFY_OUT"
[[ "$CLASSIFICATION" == "infra" ]] || fail "clean-retry case: expected infra, got $CLASSIFICATION: $CLASSIFY_OUT"
pass "AC6 case A: a clean retry (conclusion=success) classifies as infra, never regression"

# -----------------------------------------------------------------------
# AC#6 case B: synthetic SAME-SIGNATURE retry -> regression, both cited.
retry_same="$SANDBOX/retry-same.txt"
write_sig "$retry_same" ok "job:Emulator journey subset (load-bearing, Docker agents) (2)" \
  "class:com.pocketshell.app.hosts.ReconnectJourneyE2eTest#reconnectAfterBackground"
run_classify --original-signature "$orig" --retry-conclusion failure --retry-signature "$retry_same"
[[ "$CLASSIFY_RC" -eq 0 ]] || fail "same-signature case: exited $CLASSIFY_RC: $CLASSIFY_OUT"
[[ "$CLASSIFICATION" == "regression" ]] || fail "same-signature case: expected regression, got $CLASSIFICATION: $CLASSIFY_OUT"
echo "$CLASSIFY_OUT" | grep -qi "confirmed regression" || fail "same-signature case: reason must say confirmed regression: $CLASSIFY_OUT"
pass "AC6 case B: an identical-signature retry classifies as regression"

# -----------------------------------------------------------------------
# Differing signature -> infra (not regression, not silently dropped).
retry_diff="$SANDBOX/retry-diff.txt"
write_sig "$retry_diff" ok "job:Python utility tests (pocketshell)"
run_classify --original-signature "$orig" --retry-conclusion failure --retry-signature "$retry_diff"
[[ "$CLASSIFICATION" == "infra" ]] || fail "differing-signature case: expected infra, got $CLASSIFICATION: $CLASSIFY_OUT"
pass "a red retry with a DIFFERENT failure signature classifies as infra, not regression"

# -----------------------------------------------------------------------
# Subset/superset signature (still technically "differs") -> infra, not a
# false regression confirmation.
retry_subset="$SANDBOX/retry-subset.txt"
write_sig "$retry_subset" ok "job:Emulator journey subset (load-bearing, Docker agents) (2)"
run_classify --original-signature "$orig" --retry-conclusion failure --retry-signature "$retry_subset"
[[ "$CLASSIFICATION" == "infra" ]] || fail "subset-signature case: expected infra, got $CLASSIFICATION: $CLASSIFY_OUT"
pass "a red retry whose failing set is a strict subset of the original classifies as infra, not a false regression"

# -----------------------------------------------------------------------
# Degraded capture on either side -> inconclusive, never a confident verdict.
retry_degraded="$SANDBOX/retry-degraded.txt"
write_sig "$retry_degraded" "degraded:journey artifact listing failed" \
  "job:Emulator journey subset (load-bearing, Docker agents) (2)"
run_classify --original-signature "$orig" --retry-conclusion failure --retry-signature "$retry_degraded"
[[ "$CLASSIFICATION" == "inconclusive" ]] || fail "degraded-retry case: expected inconclusive, got $CLASSIFICATION: $CLASSIFY_OUT"
pass "a degraded retry-signature capture classifies as inconclusive, not infra or regression"

orig_degraded="$SANDBOX/orig-degraded.txt"
write_sig "$orig_degraded" "degraded:job listing failed" "job:Unit tests"
run_classify --original-signature "$orig_degraded" --retry-conclusion failure --retry-signature "$retry_same"
[[ "$CLASSIFICATION" == "inconclusive" ]] || fail "degraded-original case: expected inconclusive, got $CLASSIFICATION: $CLASSIFY_OUT"
pass "a degraded original-signature capture also classifies as inconclusive"

# -----------------------------------------------------------------------
# Empty original signature despite status=ok -> inconclusive (cannot claim
# either verdict off nothing).
orig_empty="$SANDBOX/orig-empty.txt"
write_sig "$orig_empty" ok
run_classify --original-signature "$orig_empty" --retry-conclusion failure --retry-signature "$retry_same"
[[ "$CLASSIFICATION" == "inconclusive" ]] || fail "empty-original case: expected inconclusive, got $CLASSIFICATION: $CLASSIFY_OUT"
pass "an empty (but status=ok) original signature classifies as inconclusive rather than guessing"

# -----------------------------------------------------------------------
# retry-conclusion=failure with no --retry-signature supplied at all ->
# inconclusive, not a crash.
run_classify --original-signature "$orig" --retry-conclusion failure
[[ "$CLASSIFY_RC" -eq 0 ]] || fail "missing retry-signature case: exited $CLASSIFY_RC: $CLASSIFY_OUT"
[[ "$CLASSIFICATION" == "inconclusive" ]] || fail "missing retry-signature case: expected inconclusive, got $CLASSIFICATION: $CLASSIFY_OUT"
pass "retry-conclusion=failure with no --retry-signature classifies as inconclusive instead of crashing"

# -----------------------------------------------------------------------
# Missing required arguments -> usage error, exit 2.
set +e
"$TARGET" --original-signature "$orig" >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" -eq 2 ]] || fail "missing --retry-conclusion must exit 2, got $rc"
pass "missing required argument exits 2"

# -----------------------------------------------------------------------
# Invalid --retry-conclusion value -> usage error, exit 2.
set +e
"$TARGET" --original-signature "$orig" --retry-conclusion bogus >/dev/null 2>&1
rc=$?
set -e
[[ "$rc" -eq 2 ]] || fail "invalid --retry-conclusion must exit 2, got $rc"
pass "invalid --retry-conclusion value exits 2"

echo "OK: $pass_count self-test case(s) passed."
