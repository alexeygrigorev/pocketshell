#!/usr/bin/env bash
# Issue #1458: aggregate the per-shard emulator-journey verdict tokens into ONE
# overall verdict so `main`'s emulator-level health is readable at a glance and
# an all-infra flake run does NOT fire a false red-CI email.
#
# Background: per-shard infra aborts used to exit 1 (red) with no rollup —
# three independent red checks with no aggregated verdict, so the maintainer
# could not read main's health and got red-CI email spam. This helper is the
# aggregation: it reads the one-word verdict token each shard now writes and
# emits the single overall verdict.
#
# Usage:
#   ci-journey-aggregate-verdict.sh [VERDICT_DIR]
#     VERDICT_DIR  directory holding the downloaded per-shard verdict artifacts
#                  (default: artifacts/ci-journey-verdicts). Each shard artifact
#                  contributes a file whose FIRST non-empty line is the verdict
#                  token — CLEAN | INFRA | RED — followed by `key=value`
#                  provenance lines (`shard`, `run_id`, `run_attempt`,
#                  `written_at`) written by
#                  scripts/ci-journey-write-shard-verdict.sh. Files may sit
#                  directly under VERDICT_DIR or one level down
#                  (download-artifact per-shard subdirs) — any regular file
#                  named `shard-verdict*.txt` (recursively) counts.
#
# Issue #1809 (G5 re-run provenance): a re-run of ONE shard leaves the shards
# that were NOT re-run reporting their earlier attempt's token — that is
# correct, but until now it was invisible, so nobody could tell a fresh re-run
# verdict from a replay of stale tokens. Every token is therefore stamped with
# its `run_attempt`, and this script PRINTS, per shard, which attempt its
# verdict came from (and says so in the step summary). Two loud cases:
#   * some tokens carried over from an earlier attempt -> ::notice naming them,
#     so an on-call's "clean re-run" claim is backed by an artifact, not a
#     memory of which button was pressed;
#   * run_attempt > 1 and NOT ONE token is from this attempt -> ::warning: the
#     aggregation job was re-run WITHOUT re-running any shard, so this verdict
#     is a replay of unchanged data, not a re-run result. That is exactly the
#     "confusing second red" the on-call hit: because the per-shard classify
#     step is `continue-on-error`, a shard whose token is RED still finishes
#     `success`, so GitHub's "Re-run failed jobs" used to re-run ONLY this
#     aggregation job. The shard job now fails on a RED token (see tests.yml)
#     so the standard re-run button re-runs the offending shard too, and this
#     warning is the backstop that names the replay if it ever happens again.
# Provenance NEVER changes the verdict — it is reporting only. The verdict is
# still the honest rollup of the freshest token per shard.
#   EXPECTED_SHARDS (env, optional) the number of shards that should have
#                  reported. When set and fewer tokens are found, the missing
#                  shards downgrade the verdict to at least RE-RUN (a missing
#                  verdict is not a clean signal), but never to RED on their own.
#
# Verdict / exit code:
#   CLEAN   every shard reported CLEAN (and none are missing). exit 0.
#   RED     at least one shard reported RED (a genuine journey failure, or the
#           #835 budget-timeout hard-red), OR a present token file held an
#           unrecognised value (fail-closed — corruption must not silently
#           pass). exit 1 — the run goes red.
#   RE-RUN  no RED shard, but at least one INFRA shard and/or a missing shard:
#           every failing/absent shard was environmental (#470 cancel / #771
#           never-booted / retry budget denied / not reported). A re-run signal,
#           NOT a product regression, so it exits 0 (neutral) with a ::warning —
#           no false red-CI email.
#
# The verdict is printed as `AGGREGATE_VERDICT=<verdict>` and appended to
# $GITHUB_STEP_SUMMARY when that env var is set, so the rollup is readable in the
# GitHub checks UI. The load-bearing property — a genuine RED still turns the run
# red — is proven by scripts/test-ci-journey-aggregate-verdict.sh (no emulator).
set -uo pipefail

verdict_dir="${1:-artifacts/ci-journey-verdicts}"
expected_shards="${EXPECTED_SHARDS:-0}"

have_clean=0
have_infra=0
have_red=0
have_unknown=0
count=0
tokens=()
provenance=()
carried_over=()
fresh_tokens=0

current_attempt="${GITHUB_RUN_ATTEMPT:-}"
[[ "$current_attempt" =~ ^[0-9]+$ ]] || current_attempt=""

if [[ -d "$verdict_dir" ]]; then
  while IFS= read -r -d '' f; do
    # The verdict is the FIRST non-empty line; everything after it is the #1809
    # `key=value` provenance stamp. Reading only line 1 is what keeps a stamped
    # token from being mangled into an UNKNOWN (which would fail closed to RED).
    raw="$(awk 'NF { print; exit }' "$f" 2>/dev/null || true)"
    # Normalise: strip whitespace, upper-case.
    token="$(printf '%s' "$raw" | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]')"
    [[ -z "$token" ]] && token="UNKNOWN"

    tok_shard="$(sed -n 's/^shard=//p' "$f" 2>/dev/null | head -n 1)"
    tok_attempt="$(sed -n 's/^run_attempt=//p' "$f" 2>/dev/null | head -n 1)"
    tok_run="$(sed -n 's/^run_id=//p' "$f" 2>/dev/null | head -n 1)"
    if [[ -z "$tok_shard" ]]; then
      # No stamp (a token from a job that died before the pre-seed, or a foreign
      # artifact): fall back to the download-artifact per-shard subdir name.
      dir_name="$(basename "$(dirname "$f")")"
      case "$dir_name" in
        *shard-*) tok_shard="${dir_name##*shard-}" ;;
        *)        tok_shard="?" ;;
      esac
    fi
    [[ -n "$tok_attempt" ]] || tok_attempt="?"
    [[ -n "$tok_run" ]] || tok_run="?"

    origin="attempt ${tok_attempt}"
    if [[ -n "$current_attempt" && "$tok_attempt" =~ ^[0-9]+$ ]]; then
      if (( tok_attempt == current_attempt )); then
        origin="attempt ${tok_attempt} (this attempt)"
        fresh_tokens=$((fresh_tokens + 1))
      else
        origin="attempt ${tok_attempt} (carried over; this run is on attempt ${current_attempt})"
        carried_over+=("shard ${tok_shard}=${token} from attempt ${tok_attempt}")
      fi
    elif [[ "$tok_attempt" == "?" ]]; then
      origin="attempt unknown (token carries no #1809 provenance stamp)"
    fi

    provenance+=("shard ${tok_shard}: ${token} — run ${tok_run}, ${origin}")

    count=$((count + 1))
    tokens+=("$token")
    case "$token" in
      CLEAN) have_clean=$((have_clean + 1)) ;;
      INFRA) have_infra=$((have_infra + 1)) ;;
      RED)   have_red=$((have_red + 1)) ;;
      *)     have_unknown=$((have_unknown + 1)) ;;
    esac
  done < <(find "$verdict_dir" -type f -name 'shard-verdict*.txt' -print0 2>/dev/null)
fi

missing=0
if [[ "$expected_shards" =~ ^[0-9]+$ ]] && (( expected_shards > count )); then
  missing=$((expected_shards - count))
fi

echo "per-shard verdict tokens found: ${count} ${tokens[*]:-<none>}"
echo "  CLEAN=$have_clean  INFRA=$have_infra  RED=$have_red  UNKNOWN=$have_unknown  MISSING=$missing (expected ${expected_shards})"

# Issue #1809: per-shard provenance, so a G5 "clean re-run" claim is backed by
# the artifact rather than by which button someone remembers pressing.
echo "verdict provenance (issue #1809; this run is on attempt ${current_attempt:-unknown}):"
if (( count == 0 )); then
  echo "  <no tokens>"
else
  for line in "${provenance[@]}"; do
    echo "  $line"
  done
fi

if (( ${#carried_over[@]} > 0 )); then
  echo "::notice title=Emulator journey verdict — some shard verdicts carried over::${#carried_over[@]} shard verdict(s) come from an earlier attempt of this run (that shard was not re-run): ${carried_over[*]}. This is expected when a single shard is re-run; it is reported so the aggregate verdict's provenance is auditable (issue #1809, G5)."
fi

# The replay backstop: the aggregation job was re-run but not one shard was.
if [[ -n "$current_attempt" ]] && (( current_attempt > 1 && count > 0 && fresh_tokens == 0 )); then
  echo "::warning title=Emulator journey verdict — REPLAY, no shard was re-run::This is attempt ${current_attempt}, but every per-shard verdict token was produced by an earlier attempt. The aggregation job was re-run WITHOUT re-running any emulator-journey shard, so this verdict is a replay of unchanged data — it is NOT evidence that a re-run reached a different result. To satisfy G5, re-run the failing shard job itself (its job now fails on a RED token, so 'Re-run failed jobs' picks it up) and let this job aggregate the fresh token (issue #1809)."
fi

emit_summary() {
  local verdict="$1" detail="$2"
  if [[ -n "${GITHUB_STEP_SUMMARY:-}" ]]; then
    {
      echo "# Emulator journey — aggregate verdict (#1458)"
      echo
      echo "**${verdict}** — ${detail}"
      echo
      echo "| token | count |"
      echo "| --- | --- |"
      echo "| CLEAN | $have_clean |"
      echo "| INFRA | $have_infra |"
      echo "| RED | $have_red |"
      echo "| UNKNOWN | $have_unknown |"
      echo "| MISSING | $missing |"
      echo
      echo "Verdict provenance (issue #1809) — this run is on attempt ${current_attempt:-unknown}:"
      echo
      if (( count == 0 )); then
        echo "- _no per-shard verdict tokens_"
      else
        local line
        for line in "${provenance[@]}"; do
          echo "- ${line}"
        done
      fi
    } >> "$GITHUB_STEP_SUMMARY"
  fi
}

# No tokens at all AND none were expected: nothing ran (e.g. the emulator job was
# skipped). Treat as RE-RUN/neutral rather than a false red — there is no
# evidence of a genuine regression.
if (( count == 0 && missing == 0 )); then
  echo "::warning title=Emulator journey verdict — RE-RUN (no shard verdicts)::No per-shard verdict tokens were found and none were expected; nothing to aggregate. Treating as neutral RE-RUN, NOT a red — there is no evidence of a genuine journey failure."
  emit_summary "RE-RUN" "no per-shard verdict tokens were found (nothing to aggregate)"
  echo "AGGREGATE_VERDICT=RE-RUN"
  exit 0
fi

# RED wins: a genuine journey failure / #835 timeout, or a corrupt (unknown)
# token which we fail closed on so a real regression can never hide behind a
# malformed artifact.
if (( have_red > 0 || have_unknown > 0 )); then
  echo "::error title=Emulator journey verdict — RED::At least one shard reported a genuine journey failure (RED=$have_red) or an unrecognised verdict (UNKNOWN=$have_unknown). This is a real regression signal — the run stays red. Per-shard annotations/logs carry the failing class(es)."
  emit_summary "RED" "a genuine journey failure (or corrupt verdict) was reported — the run stays red"
  echo "AGGREGATE_VERDICT=RED"
  exit 1
fi

# No RED; some shard was environmental (INFRA) or did not report (MISSING).
if (( have_infra > 0 || missing > 0 )); then
  echo "::warning title=Emulator journey verdict — RE-RUN (environmental)::No genuine journey failure, but ${have_infra} shard(s) hit an environmental infra abort (#470 cancel / #771 never-booted / retry budget denied) and ${missing} shard(s) did not report. This is a re-run signal, NOT a product regression — the run stays green so main-health is readable and no false red-CI email fires. Re-run the emulator-journey job."
  emit_summary "RE-RUN" "${have_infra} infra shard(s) + ${missing} missing shard(s), no genuine failure — re-run"
  echo "AGGREGATE_VERDICT=RE-RUN"
  exit 0
fi

# Every shard reported CLEAN and none are missing.
echo "All ${count} shard(s) reported CLEAN — the emulator journey gate is trustworthy this run."
emit_summary "CLEAN" "every shard passed"
echo "AGGREGATE_VERDICT=CLEAN"
exit 0
