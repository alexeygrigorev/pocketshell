#!/usr/bin/env bash
set -euo pipefail

# check-nightly-fault-run.sh — release-gate guard (issue #851, epic #848).
#
# The #848 test-reliability audit found the safety suites run in NO blocking
# gate: the toxiproxy network-fault proofs + the bootstrap setup-scenario matrix
# live ONLY in the nightly "Nightly Extensive Tests" workflow
# (.github/workflows/nightly-extensive.yml).
#
# ISSUE #1201 — WHAT THIS GUARD NOW READS:
# The extensive shard job mixes the ACTUAL fault suite (network-fault +
# bootstrap) with the flaky full journey/E2E suite AND the #822 Slice C/D
# expected-fail lane (TDD specs for unbuilt features, designed RED). Reading the
# whole extensive-job conclusion meant the job was essentially never `success`,
# so every recent release had to WAIVE this gate (NIGHTLY_FAULT_GATE_DISABLED=1)
# — a permanently-waived gate protects nothing. The suite now emits a
# machine-readable fault verdict from the network-fault + bootstrap phases ONLY,
# and the workflow exposes it as a dedicated, NON-continue-on-error
# `Fault-injection safety verdict` job. This guard reads THAT job's conclusion,
# so it GREENs when the fault phases passed even though an unrelated journey /
# expected-fail test is red, and REDs only when a fault phase itself failed (or
# no verdict was produced — genuine nightly-infra breakage, the #1056 escape
# hatch case).
#
# This guard makes the release gate FAIL when the latest nightly fault-verdict
# run is red / cancelled / stale / missing. It is wired as an early required
# step in scripts/release-emulator-validation.sh.
#
# Two layers, kept separate so the decision logic is unit-testable WITHOUT any
# network/`gh` dependency:
#
#   1. evaluate_nightly_fault_run — PURE decision function. Given the run's
#      status, the fault-verdict-JOB conclusion, the run headSha, the release
#      HEAD, and whether headSha is an ancestor-or-equal of the release HEAD, it
#      prints a PASS/BLOCK verdict + reason and returns 0 (pass) / 1 (block). No
#      git, no gh, no I/O. The release HEAD ancestry is computed by the caller.
#
#   2. The `gh`/git fetch layer (main path) — queries the latest nightly run that
#      ACTUALLY RAN the fault-verdict job (skips guard-skipped runs), extracts
#      that job's conclusion, resolves ancestry against the release HEAD, then
#      calls the pure function.
#
# Self-test: `scripts/check-nightly-fault-run.sh --self-test` exercises the pure
# decision function across the red / cancelled / stale / missing / pass matrix
# with NO network, PLUS a fixture-driven end-to-end dry run proving the guard
# GREENs on a fault-verdict-green run and REDs on a fault-verdict-red run — this
# is the dry-run rejection proof the issue asks for.
#
# Override env (for dry-runs / CI without `gh` creds):
#   NIGHTLY_FAULT_RUN_FIXTURE   path to a JSON file shaped like one element of
#                               `gh run list --json ...` PLUS a `jobConclusion`
#                               field (the fault-verdict-job conclusion). When
#                               set, the script reads this instead of calling `gh`.
#   NIGHTLY_FAULT_RELEASE_HEAD  override the release HEAD SHA (default: HEAD).
#   NIGHTLY_FAULT_WORKFLOW      workflow file (default: nightly-extensive.yml).
#   NIGHTLY_FAULT_JOB_NEEDLE    substring identifying the fault-verdict job
#                               (default: "Fault-injection safety verdict").
#   NIGHTLY_FAULT_GATE_DISABLED=1  skip the guard entirely (escape hatch; the
#                               release gate prints a loud SKIPPED note).

WORKFLOW="${NIGHTLY_FAULT_WORKFLOW:-nightly-extensive.yml}"
JOB_NEEDLE="${NIGHTLY_FAULT_JOB_NEEDLE:-Fault-injection safety verdict}"

# ---------------------------------------------------------------------------
# Layer 1: PURE decision function (no git, no gh, no I/O).
#
# Args:
#   $1 run_status        the run's `status` (e.g. completed / in_progress)
#   $2 job_conclusion    the FAULT-VERDICT-job conclusion (success/failure
#                        /cancelled/timed_out/skipped/"" when the job did not
#                        exist). This is the network-fault + bootstrap verdict
#                        ONLY — never the flaky journey suite / #822 lane.
#   $3 run_head_sha      the run's headSha ("" if no run found)
#   $4 release_head_sha  the commit being released
#   $5 head_is_ancestor  "yes" if the run COVERS the release HEAD — i.e.
#                        release_head_sha is an ancestor-or-equal of
#                        run_head_sha, so the release commit is contained in the
#                        line the nightly actually tested. "no" otherwise (the
#                        release HEAD advanced PAST the nightly's sha → STALE).
#
# Prints "PASS: <reason>" / "BLOCK: <reason>"; returns 0 (pass) or 1 (block).
# ---------------------------------------------------------------------------
evaluate_nightly_fault_run() {
  local run_status="$1"
  local job_conclusion="$2"
  local run_head_sha="$3"
  local release_head_sha="$4"
  local head_is_ancestor="$5"

  # No run at all → the fault suite has never reported for this line. Block.
  if [[ -z "$run_head_sha" ]]; then
    echo "BLOCK: no nightly fault/bootstrap run found for workflow '$WORKFLOW' — the safety suite has produced no signal to release on. Trigger 'Nightly Extensive Tests' (workflow_dispatch, force_run=true) on the release commit."
    return 1
  fi

  # Run did not complete (in_progress / queued / waiting / requested) → no
  # final verdict yet. Block rather than release on an unfinished run.
  if [[ "$run_status" != "completed" ]]; then
    echo "BLOCK: latest nightly fault run is status='$run_status' (not completed) — no final fault verdict yet. Wait for it to finish (or re-run it) before releasing."
    return 1
  fi

  # Stale: the run tested an older line that does NOT contain the release HEAD.
  # Releasing on it would ship commits the fault suite never exercised.
  if [[ "$head_is_ancestor" != "yes" ]]; then
    echo "BLOCK: latest nightly fault run tested headSha=$run_head_sha which does NOT contain the release HEAD ($release_head_sha) — the run is STALE for this release. Re-run 'Nightly Extensive Tests' on the release commit."
    return 1
  fi

  # The load-bearing signal is the dedicated `Fault-injection safety verdict`
  # JOB conclusion (issue #1201) — the network-fault + bootstrap verdict, with
  # the flaky journey suite and the #822 expected-fail lane excluded. Anything
  # other than `success` is a block.
  case "$job_conclusion" in
    success)
      echo "PASS: nightly fault-injection safety verdict is green (fault-verdict job conclusion=success) and covers the release HEAD ($release_head_sha). The network-fault + bootstrap safety journeys passed on this line."
      return 0
      ;;
    cancelled)
      echo "BLOCK: latest nightly fault-verdict job was CANCELLED (conclusion=cancelled) — the fault/bootstrap suite did not complete, so it proves nothing. Re-run 'Nightly Extensive Tests' on the release commit."
      return 1
      ;;
    "")
      echo "BLOCK: latest nightly run did not run the fault-verdict job (no job matching '$JOB_NEEDLE' — it was guard-skipped or never started). There is no fault signal. Force-run 'Nightly Extensive Tests' on the release commit."
      return 1
      ;;
    *)
      echo "BLOCK: latest nightly fault-injection safety verdict is RED (fault-verdict job conclusion='$job_conclusion'). The safety suite (toxiproxy network-fault + bootstrap matrix) failed on the release line — fix the failure or re-run before releasing."
      return 1
      ;;
  esac
}

# ---------------------------------------------------------------------------
# Self-test: drives the pure function across the full matrix with NO network.
# This is the dry-run rejection proof the issue asks for.
# ---------------------------------------------------------------------------
self_test() {
  local failures=0
  local out rc

  assert_verdict() {
    local label="$1" expect_rc="$2"
    shift 2
    set +e
    out="$(evaluate_nightly_fault_run "$@")"
    rc=$?
    set -e
    if [[ "$rc" != "$expect_rc" ]]; then
      printf 'FAIL [%s]: expected rc=%s got rc=%s :: %s\n' "$label" "$expect_rc" "$rc" "$out"
      failures=$((failures + 1))
    else
      printf 'ok   [%s] rc=%s :: %s\n' "$label" "$rc" "$out"
    fi
  }

  local REL="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
  local OLD="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

  # PASS: completed, fault-verdict job success, covers the release HEAD.
  assert_verdict "green-and-current" 0 completed success "$REL" "$REL" yes
  # BLOCK: fault-verdict job failed (a fault phase actually failed).
  assert_verdict "red-job"           1 completed failure "$REL" "$REL" yes
  # BLOCK: run cancelled.
  assert_verdict "cancelled"         1 completed cancelled "$REL" "$REL" yes
  # BLOCK: timed_out.
  assert_verdict "timed-out"         1 completed timed_out "$REL" "$REL" yes
  # BLOCK: fault-verdict job absent (guard-skipped / never ran).
  assert_verdict "job-absent"        1 completed "" "$OLD" "$REL" yes
  # BLOCK: no run found at all.
  assert_verdict "no-run"            1 completed success "" "$REL" no
  # BLOCK: run not completed yet.
  assert_verdict "in-progress"       1 in_progress "" "$REL" "$REL" yes
  # BLOCK: green BUT stale (tested an older line not containing release HEAD).
  assert_verdict "green-but-stale"   1 completed success "$OLD" "$REL" no

  echo
  # -------------------------------------------------------------------------
  # Issue #1201 — fixture-driven END-TO-END dry run through the real fetch +
  # decide path (Layer 2 → Layer 1), proving BOTH load-bearing directions:
  #   * fault-verdict job GREEN (fault phases passed even though the extensive
  #     shard is red from an unrelated journey / #822 test) -> guard exits 0;
  #   * fault-verdict job RED (a fault phase itself failed)  -> guard exits 1.
  # The fixture's `jobConclusion` IS the dedicated fault-verdict job conclusion,
  # so this exercises exactly what the guard reads on a real run. headSha ==
  # release head so the ancestry short-circuit avoids any git dependency.
  # -------------------------------------------------------------------------
  local self_path="${BASH_SOURCE[0]}"
  fixture_dry_run() {
    local label="$1" expect_rc="$2" job_conclusion="$3"
    local sha="cccccccccccccccccccccccccccccccccccccccc"
    local tmp; tmp="$(mktemp)"
    printf '{"status":"completed","jobConclusion":"%s","headSha":"%s","databaseId":424242}\n' \
      "$job_conclusion" "$sha" > "$tmp"
    set +e
    out="$(NIGHTLY_FAULT_RUN_FIXTURE="$tmp" NIGHTLY_FAULT_RELEASE_HEAD="$sha" \
      bash "$self_path" 2>&1)"
    rc=$?
    set -e
    rm -f "$tmp"
    if [[ "$rc" != "$expect_rc" ]]; then
      printf 'FAIL [%s]: expected guard rc=%s got rc=%s\n%s\n' "$label" "$expect_rc" "$rc" "$out"
      failures=$((failures + 1))
    else
      printf 'ok   [%s] guard rc=%s :: %s\n' "$label" "$rc" "$(printf '%s' "$out" | tail -1)"
    fi
  }

  echo "--- fixture-driven end-to-end guard dry run (issue #1201) ---"
  # Fault phases PASSED (verdict job success) while the extensive shard is red
  # from unrelated journey / #822 flakes -> the guard GREENs (exit 0). This is
  # the exact case that used to force NIGHTLY_FAULT_GATE_DISABLED=1.
  fixture_dry_run "fault-verdict-green-shard-red" 0 success
  # A fault phase itself FAILED (verdict job failure) -> the guard BLOCKS (exit 1).
  fixture_dry_run "fault-verdict-red"             1 failure

  echo
  if [[ "$failures" -eq 0 ]]; then
    echo "SELF-TEST PASS: all pure-decision + fixture-driven cases produced the expected verdict."
    return 0
  fi
  echo "SELF-TEST FAIL: $failures case(s) wrong."
  return 1
}

# ---------------------------------------------------------------------------
# Layer 2: fetch + resolve, then call the pure function.
# ---------------------------------------------------------------------------

# Resolve the latest nightly run that ACTUALLY ran the fault-verdict job, plus
# that job's conclusion. Emits four tab-separated fields on stdout:
#   <run_status>\t<job_conclusion>\t<run_head_sha>\t<databaseId>
# job_conclusion is "" when no run ran a matching job.
#
# Reads NIGHTLY_FAULT_RUN_FIXTURE if set (a JSON object with the run fields PLUS
# a `jobConclusion` field) — no `gh` call. Otherwise queries `gh`.
resolve_latest_fault_run() {
  if [[ -n "${NIGHTLY_FAULT_RUN_FIXTURE:-}" ]]; then
    [[ -f "$NIGHTLY_FAULT_RUN_FIXTURE" ]] ||
      { echo "fixture not found: $NIGHTLY_FAULT_RUN_FIXTURE" >&2; return 2; }
    jq -r '[(.status // ""), (.jobConclusion // ""), (.headSha // ""), ((.databaseId // "") | tostring)] | @tsv' \
      "$NIGHTLY_FAULT_RUN_FIXTURE"
    return 0
  fi

  command -v gh >/dev/null 2>&1 || { echo "gh CLI is required" >&2; return 2; }
  command -v jq >/dev/null 2>&1 || { echo "jq is required" >&2; return 2; }

  # Pull recent runs. Walk newest→oldest and pick the FIRST whose fault-verdict
  # job exists (i.e. it was not guard-skipped). Guard-skipped runs only have the
  # cheap "Guard" job, so the fault suite never ran — those are not a signal.
  local runs
  runs="$(gh run list --workflow="$WORKFLOW" --limit 15 \
    --json databaseId,headSha,status,conclusion,createdAt 2>/dev/null)" || {
    echo "gh run list failed for workflow '$WORKFLOW'" >&2
    return 2
  }

  local count
  count="$(jq 'length' <<<"$runs")"
  local i id sha status
  for ((i = 0; i < count; i++)); do
    id="$(jq -r ".[$i].databaseId" <<<"$runs")"
    sha="$(jq -r ".[$i].headSha" <<<"$runs")"
    status="$(jq -r ".[$i].status" <<<"$runs")"

    # Inspect this run's jobs; find the fault-verdict job by name needle.
    local jobs job_conclusion
    jobs="$(gh run view "$id" --json jobs 2>/dev/null)" || continue
    job_conclusion="$(jq -r --arg needle "$JOB_NEEDLE" \
      'first(.jobs[] | select(.name | test($needle)) | .conclusion) // ""' \
      <<<"$jobs")"

    if [[ -n "$job_conclusion" ]]; then
      printf '%s\t%s\t%s\t%s\n' "$status" "$job_conclusion" "$sha" "$id"
      return 0
    fi
    # else: fault-verdict job did not run in this run (guard-skipped) → keep looking.
  done

  # No run in the window actually ran the fault-verdict job.
  printf '%s\t%s\t%s\t%s\n' "completed" "" "" ""
  return 0
}

main() {
  if [[ "${1:-}" == "--self-test" ]]; then
    self_test
    exit $?
  fi

  if [[ "${NIGHTLY_FAULT_GATE_DISABLED:-0}" == "1" ]]; then
    echo "SKIPPED: nightly-fault release guard disabled via NIGHTLY_FAULT_GATE_DISABLED=1 (escape hatch)."
    exit 0
  fi

  local release_head
  release_head="${NIGHTLY_FAULT_RELEASE_HEAD:-$(git rev-parse HEAD)}"

  local resolved
  resolved="$(resolve_latest_fault_run)" || {
    echo "BLOCK: could not resolve the latest nightly fault run (see error above)." >&2
    exit 1
  }

  local run_status job_conclusion run_head_sha db_id
  IFS=$'\t' read -r run_status job_conclusion run_head_sha db_id <<<"$resolved"

  # Does the run COVER the release HEAD? The nightly tested commit
  # `run_head_sha`; its result is valid for the release commit `release_head`
  # only if `release_head` is contained in the tested line — i.e. release_head
  # is an ancestor-or-equal of run_head_sha. If the release HEAD has advanced
  # PAST the nightly's sha (release_head is a DESCENDANT of run_head_sha), the
  # nightly never tested those newer commits → STALE. Empty headSha → no.
  local head_is_ancestor="no"
  if [[ -n "$run_head_sha" ]]; then
    if [[ "$run_head_sha" == "$release_head" ]]; then
      head_is_ancestor="yes"
    elif git merge-base --is-ancestor "$release_head" "$run_head_sha" 2>/dev/null; then
      head_is_ancestor="yes"
    fi
  fi

  echo "Nightly fault run: workflow=$WORKFLOW id=${db_id:-none} status=${run_status:-?} fault-verdict-job-conclusion=${job_conclusion:-<none>} headSha=${run_head_sha:-<none>}"
  echo "Release HEAD=$release_head head_is_ancestor=$head_is_ancestor"

  local verdict rc
  set +e
  verdict="$(evaluate_nightly_fault_run "$run_status" "$job_conclusion" "$run_head_sha" "$release_head" "$head_is_ancestor")"
  rc=$?
  set -e
  echo "$verdict"
  exit "$rc"
}

main "$@"
