#!/usr/bin/env bash
# Issue #1924: emulator-free proof that nightly aggregate reports bounded
# failure-signature recurrence (count / first_seen / last_seen / streak) from
# small verdict artifacts only.
#
# Reproduce-first (D32): current main has no longitudinal reporter, so this
# fixture with the four #1868 Class#method signatures across three dated
# inputs cannot report count=3. The implementation must make that fixture
# GREEN without downloading historical Android report archives.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
REC="$SCRIPT_DIR/nightly-failure-recurrence.py"
NIGHTLY="$REPO_ROOT/.github/workflows/nightly-extensive.yml"
UNIT_WF="$REPO_ROOT/.github/workflows/tests.yml"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

[[ -f "$REC" ]] || fail "reporter missing: $REC (nightly aggregate does not report recurrence)"
[[ -f "$NIGHTLY" ]] || fail "missing $NIGHTLY"
[[ -f "$UNIT_WF" ]] || fail "missing $UNIT_WF"
chmod +x "$REC"

python3 "$REC" --self-test
pass "python --self-test (fixtures + G6 mutations)"

# ---------------------------------------------------------------------------
# Workflow wiring: a helper nobody calls is not coverage. History/report
# failures must stay off the emulator shards and off the #1201 fault verdict.
# ---------------------------------------------------------------------------
grep -q 'nightly-failure-recurrence.py' "$NIGHTLY" \
  || fail "nightly-extensive.yml must invoke nightly-failure-recurrence.py"
grep -q 'test-nightly-failure-recurrence.sh' "$NIGHTLY" \
  || fail "nightly guard job must run this self-test before spending an AVD"
grep -q 'test-nightly-failure-recurrence.sh' "$UNIT_WF" \
  || fail "tests.yml Unit/guards job must run this self-test"

awk '
  /^  [A-Za-z0-9_-]+:/ { job=$1; sub(/:.*/,"",job) }
  /^    name: Nightly failure recurrence/ { rec_job=job }
  END { if (!rec_job) exit 1; print rec_job }
' "$NIGHTLY" | grep -q . \
  || fail "nightly-extensive.yml must define a failure-recurrence aggregate job"

rec_job="$(
  awk '
    /^  [A-Za-z0-9_-]+:/ { job=$1; sub(/:.*/,"",job) }
    /^    name: Nightly failure recurrence/ { rec_job=job }
    END { print rec_job }
  ' "$NIGHTLY"
)"
[[ -n "$rec_job" ]] || fail "could not extract recurrence job key"

# The recurrence job is a sibling of fault-verdict, not an input to it.
fault_needs="$(
  awk '
    /^  fault-verdict:/ { injob=1; next }
    /^  [A-Za-z0-9_-]+:/ { if (injobsink) injob=0 }
    /^  [A-Za-z0-9_-]+:/ && injob && !/^  fault-verdict:/ { injob=0 }
    injob && /needs:/ { print; exit }
  ' "$NIGHTLY"
)"
echo "$fault_needs" | grep -q 'failure-history\|recurrence' \
  && fail "#1201 fault-verdict must not need the recurrence job (got: $fault_needs)"
echo "$fault_needs" | grep -q 'extensive' \
  || fail "fault-verdict needs: must still include extensive (got: $fault_needs)"
pass "fault-verdict stays independent of $rec_job (needs: $fault_needs)"

# Recurrence job must not download historical full Android report archives.
rec_block="$(
  awk -v want="$rec_job" '
    /^  [A-Za-z0-9_-]+:/ {
      job=$1; sub(/:.*/,"",job)
      injob = (job == want)
      next
    }
    injob { print }
  ' "$NIGHTLY"
)"
echo "$rec_block" | grep -q 'nightly-failure-recurrence.py' \
  || fail "recurrence job must invoke nightly-failure-recurrence.py"
echo "$rec_block" | grep -Eq -- '--window[[:space:]]+14|WINDOW=14|window: 14' \
  || echo "$rec_block" | grep -q '14' \
  || fail "recurrence job must query a bounded window of 14 completed nightly runs"
if echo "$rec_block" | grep -q 'nightly-extensive-android-test-reports'; then
  fail "recurrence job must not download nightly-extensive-android-test-reports (historical full archives are forbidden)"
fi
echo "$rec_block" | grep -q 'nightly-failure-signatures' \
  || fail "recurrence job must consume the small nightly-failure-signatures artifact"
pass "recurrence job $rec_job consumes only the small artifact over a 14-run window"

# Emit/upload on the emulator shard must be continue-on-error so a reporter
# failure cannot flip phase execution.
awk '
  /Emit current-run failure signatures/ { emit=1 }
  emit && /continue-on-error: true/ { emit_coe=1 }
  emit && /Upload failure-signature verdict/ { emit=0 }
  /Upload failure-signature verdict/ { up=1 }
  up && /continue-on-error: true/ { up_coe=1 }
  up && /^      - name:/ && !/Upload failure-signature verdict/ { up=0 }
  END { exit (emit_coe && up_coe ? 0 : 1) }
' "$NIGHTLY" \
  || fail "shard emit + small-artifact upload must be continue-on-error (keep reporter failures off emulator phase execution)"
pass "shard emit/upload are continue-on-error"

bash -n "$REC" 2>/dev/null || true
bash -n "$0" || fail "bash -n failed on $0"
python3 -m py_compile "$REC" || fail "py_compile failed on $REC"
pass "syntax"

echo
echo "PASS: test-nightly-failure-recurrence"
