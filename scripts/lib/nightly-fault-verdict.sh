#!/usr/bin/env bash
# nightly-fault-verdict.sh — the authoritative fault-injection safety verdict
# helper (issue #1201).
#
# WHY THIS EXISTS
# ---------------
# The release gate's nightly-fault guard (scripts/check-nightly-fault-run.sh,
# #851) used to block the release on the WHOLE "Nightly Extensive Tests"
# extensive-job conclusion. But that shard mixes THREE unrelated things:
#
#   1. phase 1 — the full connected journey/E2E suite (chronic emulator /
#      toxiproxy infra flakes + stale-test debt);
#   2. phase 2 — the toxiproxy network-fault proofs (the ACTUAL fault suite);
#   3. phase 3 — the bootstrap setup-scenario matrix (also a release-gating
#      safety journey);
#
#   plus an intentional TDD "expected-fail" lane (#822 Slice C/D unbuilt-feature
#   journeys, designed RED until the slice lands).
#
# Because phase 1's chronic flakes and the #822 expected-fail lane are ALWAYS
# red, the extensive job conclusion was essentially never `success`, so the
# release gate had to be waived with NIGHTLY_FAULT_GATE_DISABLED=1 on every
# recent release — a permanently-waived safety gate protects nothing.
#
# This helper computes a verdict that reflects ONLY the fault-injection safety
# phases (network-fault + bootstrap), EXCLUDING the flaky journey suite AND the
# non-gating #822 expected-fail lane. `nightly-extensive-suite.sh` writes that
# verdict to a machine-readable file on the shard that runs those phases; the
# workflow's dedicated `Fault-injection safety verdict` job reads it and turns it
# into a job conclusion; and `check-nightly-fault-run.sh` reads THAT job's
# conclusion (not the mixed extensive-job conclusion).
#
# Everything here is PURE (no gradle, no emulator, no network) so it is
# unit-testable with `scripts/lib/nightly-fault-verdict.sh --self-test`.

# ---------------------------------------------------------------------------
# compute_fault_verdict <network_fault_status> <bootstrap_status>
#
# The two arguments are the PASS/FAIL results of the network-fault (phase 2) and
# bootstrap (phase 3) phases. The journey phase (phase 1) and the #822
# expected-fail lane are DELIBERATELY not arguments — they must never influence
# the fault-injection safety verdict.
#
# Prints "PASS" / "FAIL"; returns 0 (pass) / 1 (fail).
# ---------------------------------------------------------------------------
compute_fault_verdict() {
  local network_fault_status="$1"
  local bootstrap_status="$2"

  if [[ "$network_fault_status" == "PASS" && "$bootstrap_status" == "PASS" ]]; then
    echo "PASS"
    return 0
  fi
  echo "FAIL"
  return 1
}

# ---------------------------------------------------------------------------
# write_fault_verdict_file <path> <nf_status> <nf_exit> <bootstrap_status> \
#                          <bootstrap_exit> <expectedfail_status> <expectedfail_exit>
#
# Writes the machine-readable verdict file the CI `Fault-injection safety
# verdict` job reads. The expected-fail fields are recorded for TRACKING only
# and are explicitly NON-GATING (they do not feed `fault_verdict`).
# ---------------------------------------------------------------------------
write_fault_verdict_file() {
  local path="$1"
  local nf_status="$2"
  local nf_exit="$3"
  local bootstrap_status="$4"
  local bootstrap_exit="$5"
  local expectedfail_status="$6"
  local expectedfail_exit="$7"

  local verdict
  # `compute_fault_verdict` returns non-zero on FAIL; capture the string without
  # letting a caller's `set -e` abort here (the assignment inherits its status).
  verdict="$(compute_fault_verdict "$nf_status" "$bootstrap_status")" || true

  {
    echo "# Fault-injection safety verdict (issue #1201) — machine-readable."
    echo "# GATING inputs: network-fault (phase 2) + bootstrap (phase 3) ONLY."
    echo "# The journey/E2E suite (phase 1) and the #822 expected-fail lane"
    echo "# (phase 2b) are DELIBERATELY excluded and never gate this verdict."
    echo "fault_phase_ran=yes"
    echo "network_fault_status=$nf_status"
    echo "network_fault_exit=$nf_exit"
    echo "bootstrap_status=$bootstrap_status"
    echo "bootstrap_exit=$bootstrap_exit"
    echo "# NON-GATING (tracked only): the #822 Slice C/D expected-fail lane."
    echo "expected_fail_status=$expectedfail_status"
    echo "expected_fail_exit=$expectedfail_exit"
    echo "fault_verdict=$verdict"
  } > "$path"
}

# ---------------------------------------------------------------------------
# Self-test: exercises the pure verdict function across the full matrix with NO
# gradle/emulator. This is the dry-run proof (issue #1201 acceptance) that the
# verdict GREENs when the fault phases passed even though the journey suite /
# expected-fail lane are red, and REDs when a fault phase itself failed.
# ---------------------------------------------------------------------------
_fault_verdict_self_test() {
  local failures=0
  local out rc tmp

  assert_verdict() {
    local label="$1" expect="$2" expect_rc="$3" nf="$4" bootstrap="$5"
    out="$(compute_fault_verdict "$nf" "$bootstrap")" && rc=0 || rc=$?
    if [[ "$out" != "$expect" || "$rc" != "$expect_rc" ]]; then
      printf 'FAIL [%s]: expected %s(rc=%s) got %s(rc=%s)\n' \
        "$label" "$expect" "$expect_rc" "$out" "$rc"
      failures=$((failures + 1))
    else
      printf 'ok   [%s] -> %s (rc=%s)\n' "$label" "$out" "$rc"
    fi
  }

  # Both gating phases green -> PASS.
  assert_verdict "nf+bootstrap green"                 PASS 0 PASS PASS
  # A gating phase red -> FAIL.
  assert_verdict "nf red"                             FAIL 1 FAIL PASS
  assert_verdict "bootstrap red"                      FAIL 1 PASS FAIL
  assert_verdict "both gating red"                    FAIL 1 FAIL FAIL

  # THE load-bearing #1201 direction, at the FILE level: the journey suite and
  # the #822 expected-fail lane are red, but the fault phases passed -> the
  # written verdict is PASS and does NOT reflect the unrelated red.
  echo
  echo "--- file-level dry run: fault phases green, journey + expected-fail RED ---"
  tmp="$(mktemp)"
  # journey (not an arg here) FAIL + expected-fail FAIL, but nf/bootstrap PASS.
  write_fault_verdict_file "$tmp" PASS 0 PASS 0 FAIL 1
  cat "$tmp"
  if grep -q '^fault_verdict=PASS$' "$tmp"; then
    echo "ok   [file] fault_verdict=PASS despite expected-fail lane red"
  else
    echo "FAIL [file] fault_verdict should be PASS despite expected-fail lane red"
    failures=$((failures + 1))
  fi
  rm -f "$tmp"

  echo
  echo "--- file-level dry run: a fault phase itself RED -> verdict FAIL ---"
  tmp="$(mktemp)"
  write_fault_verdict_file "$tmp" FAIL 1 PASS 0 FAIL 1
  cat "$tmp"
  if grep -q '^fault_verdict=FAIL$' "$tmp"; then
    echo "ok   [file] fault_verdict=FAIL when the network-fault phase failed"
  else
    echo "FAIL [file] fault_verdict should be FAIL when the network-fault phase failed"
    failures=$((failures + 1))
  fi
  rm -f "$tmp"

  echo
  if [[ "$failures" -eq 0 ]]; then
    echo "FAULT-VERDICT SELF-TEST PASS: all cases produced the expected verdict."
    return 0
  fi
  echo "FAULT-VERDICT SELF-TEST FAIL: $failures case(s) wrong."
  return 1
}

# Allow running directly for the self-test; stays a pure library when sourced.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  if [[ "${1:-}" == "--self-test" ]]; then
    _fault_verdict_self_test
    exit $?
  fi
  echo "usage: source this file, or run with --self-test" >&2
  exit 2
fi
