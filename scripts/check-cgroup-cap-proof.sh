#!/usr/bin/env bash
set -euo pipefail

# Kernel-level proof that PocketShell's session memory cap is really applied
# (issue #2562), asserted on the RESULT of the run — never on its exit code.
#
# WHY THIS SCRIPT EXISTS
#
# `tests/test_sessions_mem_cap.py::test_real_aplexer_session_cgroup_carries_the_
# resolved_cap` is the only assertion in the repo that reads `memory.max` back
# out of the kernel for a session `pocketshell sessions create` just made. It
# needs a delegated cgroup-v2 systemd `--user` scope, and NO automated lane we
# own has one:
#
#   * `Python utility tests (pocketshell)` runs on `ubuntu-latest`, where
#     `a start --memory` fails with `spawn workload: Permission denied` because
#     the runner's process tree lives outside `user@<uid>.service` (PR #2590);
#   * `Integration tests (Docker)` runs the `agents` container, which has no
#     user systemd at all — that is why the fixture passes `--mem none`;
#   * `release-emulator-validation.yml`, which drives the release confidence
#     gate, is ALSO `runs-on: ubuntu-latest`.
#
# So the proof must skip in CI, and a skip nothing converts back into an
# obligation is how a proof quietly becomes a no-op.
#
# WHY IT PARSES THE RESULT INSTEAD OF TRUSTING $?
#
# The first version of this script ran pytest and printed PASS on exit 0.
# `pytest` exits 0 on a SKIP, so a skipped proof printed
# "PASS: the created session's cgroup carried the resolved memory.max." — the
# exact vacuous green this whole mechanism exists to prevent (round-2 review).
# `POCKETSHELL_CGROUP_CAP_PROOF=required` only converts skips raised inside the
# test's own `_unavailable()`; a DECORATOR-level skip (`@pytest.mark.skipif(...
# or True)`, or simply running on a non-Linux host) never reaches it. So every
# mode below asserts the reported COUNTS: the expected number of tests passed,
# and nothing was skipped, failed, errored or left uncollected.
#
# MODES
#
#   (default)        Required mode. The proof MUST run and pass here. Any skip,
#                    for any reason, is a failure. Run this on a host with a
#                    systemd --user session after touching session containment,
#                    `pocketshell/memcap.py`, `sessions.py`'s create arms, the
#                    delegation probe, or the pinned aplexer version.
#
#   --if-supported   For the pre-release confidence gate, which also runs on
#                    hosted runners. A skip is tolerated ONLY when its reason
#                    names a missing host capability (the marker
#                    `missing capability:`, emitted by the test's one skip
#                    helper) — and it is then disclosed loudly as NOT PROVEN,
#                    never as a pass. Any other skip, and every failure, fails.
#
#   --self-test      Proves the mechanism still has teeth: that an environment
#                    skip names its capability, that required mode converts it
#                    into a failure, and that no blanket `@pytest.mark.skip`
#                    can be parked on the proof. Asserts counts too.
#
# The proof is named by NODE ID, so renaming or deleting it makes pytest exit
# non-zero with `collected 0 items` instead of selecting nothing quietly.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG_DIR="$ROOT_DIR/tools/pocketshell"

NODE_ID="tests/test_sessions_mem_cap.py::test_real_aplexer_session_cgroup_carries_the_resolved_cap"
SELF_TEST_FILTER="missing_capability_skip or required_mode_turns or can_only_be_skipped_through"
SELF_TEST_EXPECTED=3

MODE="required"
case "${1:-}" in
  "") ;;
  --if-supported) MODE="if-supported" ;;
  --self-test) MODE="self-test" ;;
  *)
    echo "usage: scripts/check-cgroup-cap-proof.sh [--if-supported | --self-test]" >&2
    exit 2
    ;;
esac
if [[ "$#" -gt 1 ]]; then
  echo "usage: scripts/check-cgroup-cap-proof.sh [--if-supported | --self-test]" >&2
  exit 2
fi

OUT_FILE="$(mktemp -t cgroup-cap-proof-XXXXXX.log)"
cleanup() { rm -f "$OUT_FILE"; }
trap cleanup EXIT

# `<count> <outcome>` counts as pytest reports them ("1 passed", "1 skipped",
# …). Absent outcome -> 0, so every assertion below is explicit.
count_of() {
  local outcome="$1" value
  value="$(grep -oE "[0-9]+ $outcome" "$OUT_FILE" | tail -1 | cut -d' ' -f1 || true)"
  printf '%s' "${value:-0}"
}

skip_reason() {
  grep -E '^SKIPPED' "$OUT_FILE" | tail -1 || true
}

fail() {
  printf '\nFAIL: %s\n' "$1" >&2
  exit 1
}

# Assert on the RESULT, not on pytest's exit code: `pytest` exits 0 on a skip.
assert_counts() {
  local expected_passed="$1" status="$2"
  local passed skipped failed errors
  passed="$(count_of passed)"
  skipped="$(count_of skipped)"
  failed="$(count_of failed)"
  errors="$(count_of error)"
  [[ "$failed" == "0" ]] || fail "$failed test(s) failed"
  [[ "$errors" == "0" ]] || fail "$errors collection/setup error(s)"
  [[ "$skipped" == "0" ]] || fail "$skipped test(s) SKIPPED — a skipped proof is not a pass: $(skip_reason)"
  [[ "$passed" == "$expected_passed" ]] ||
    fail "expected exactly $expected_passed passing test(s), pytest reported $passed"
  [[ "$status" == "0" ]] || fail "pytest exited $status"
}

run_pytest() {
  set +e
  (cd "$PKG_DIR" && "$@") > "$OUT_FILE" 2>&1
  local status=$?
  set -e
  cat "$OUT_FILE"
  return "$status"
}

if [[ "$MODE" == "self-test" ]]; then
  echo "== cgroup memory-cap proof: self-test of the skip mechanism =="
  set +e
  run_pytest uv run --frozen pytest -v -rs tests/test_sessions_mem_cap.py -k "$SELF_TEST_FILTER"
  status=$?
  set -e
  assert_counts "$SELF_TEST_EXPECTED" "$status"
  echo "PASS: the skip mechanism still names its capability, still converts to a failure under required mode, and is still guarded against a blanket skip."
  exit 0
fi

if [[ "$MODE" == "if-supported" ]]; then
  # Used by scripts/pre-release-confidence-gate.sh, which also runs on hosted
  # runners that cannot delegate a cgroup-v2 user scope. `uv` may be absent
  # there too; that is a host capability like any other, and it is DISCLOSED,
  # never silently passed.
  echo "== cgroup memory-cap proof (disclose-if-unsupported) =="
  echo "   node: $NODE_ID"
  if ! command -v uv >/dev/null 2>&1; then
    echo "NOT PROVEN HERE — missing capability: uv is not installed, so the"
    echo "  pocketshell test suite cannot run on this host. The session memory"
    echo "  cap was NOT verified against the kernel by this run."
    exit 0
  fi
  set +e
  run_pytest uv run --frozen pytest -q -rs "$NODE_ID"
  status=$?
  set -e
  skipped="$(count_of skipped)"
  if [[ "$skipped" != "0" ]]; then
    reason="$(skip_reason)"
    # Only a MISSING HOST CAPABILITY may be tolerated. A platform guard, a
    # blanket `@pytest.mark.skip`, or an always-true `skipif` produces a skip
    # WITHOUT this marker, and must not pass here — that is the decorator-level
    # hole the round-2 review found.
    case "$reason" in
      *"missing capability:"*) ;;
      *) fail "the proof was skipped for a reason that is not a missing host capability: ${reason:-<no reason reported>}" ;;
    esac
    echo
    echo "NOT PROVEN HERE — $reason"
    echo "  The session memory cap was NOT verified against the kernel by this"
    echo "  run. Run scripts/check-cgroup-cap-proof.sh on a host with a systemd"
    echo "  --user session to prove it."
    exit 0
  fi
  assert_counts 1 "$status"
  echo "PASS: the created session's cgroup carried the resolved memory.max."
  exit 0
fi

echo "== cgroup memory-cap proof (required mode) =="
echo "   node: $NODE_ID"
# Exported explicitly (not as a `VAR=x func` prefix, whose scope for a shell
# FUNCTION is not portable) so the value really reaches pytest's environment.
export POCKETSHELL_CGROUP_CAP_PROOF=required
set +e
run_pytest uv run --frozen pytest -v -rs "$NODE_ID"
status=$?
set -e
assert_counts 1 "$status"
echo "PASS: the created session's cgroup carried the resolved memory.max."
