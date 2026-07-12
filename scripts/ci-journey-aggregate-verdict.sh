#!/usr/bin/env bash
# Issue #1458: aggregate the per-shard emulator-journey verdict tokens into ONE
# overall verdict so `main`'s emulator-level health is readable at a glance and
# an all-infra flake run does NOT fire a false red-CI email.
#
# Background: the heavy `Emulator journey subset` job on `main` flakes constantly
# from host-CPU starvation on 2–4 vCPU hosted runners. The per-shard classify
# step already annotates a console-storm shard as `EMULATOR INFRA UNAVAILABLE`,
# but before this each storm-classified shard STILL exited 1 (red) with no
# rollup — three independent red checks with no aggregated verdict, so the
# maintainer could not read main's health and got red-CI email spam. This helper
# is the aggregation: it reads the one-word verdict token each shard now writes
# and emits the single overall verdict.
#
# Usage:
#   ci-journey-aggregate-verdict.sh [VERDICT_DIR]
#     VERDICT_DIR  directory holding the downloaded per-shard verdict artifacts
#                  (default: artifacts/ci-journey-verdicts). Each shard artifact
#                  contributes a file whose contents are a single token:
#                  CLEAN | INFRA | RED. Files may sit directly under VERDICT_DIR
#                  or one level down (download-artifact per-shard subdirs) — any
#                  regular file named `shard-verdict*.txt` (recursively) counts.
#   EXPECTED_SHARDS (env, optional) the number of shards that should have
#                  reported. When set and fewer tokens are found, the missing
#                  shards downgrade the verdict to at least RE-RUN (a missing
#                  verdict is not a clean signal), but never to RED on their own.
#
# Verdict / exit code:
#   CLEAN   every shard reported CLEAN (and none are missing). exit 0.
#   RED     at least one shard reported RED (a genuine HEALTHY-console journey
#           failure, or the #835 budget-timeout hard-red), OR a present token
#           file held an unrecognised value (fail-closed — corruption must not
#           silently pass). exit 1 — the run goes red.
#   RE-RUN  no RED shard, but at least one INFRA shard and/or a missing shard:
#           every failing/absent shard was environmental (console storm / #470
#           cancel / #771 never-booted / not reported). A re-run signal, NOT a
#           product regression, so it exits 0 (neutral) with a ::warning — no
#           false red-CI email.
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

if [[ -d "$verdict_dir" ]]; then
  while IFS= read -r -d '' f; do
    raw="$(cat "$f" 2>/dev/null || true)"
    # Normalise: strip whitespace, upper-case.
    token="$(printf '%s' "$raw" | tr -d '[:space:]' | tr '[:lower:]' '[:upper:]')"
    [[ -z "$token" ]] && token="UNKNOWN"
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
  echo "::warning title=Emulator journey verdict — RE-RUN (environmental)::No genuine journey failure, but ${have_infra} shard(s) hit an environmental infra abort (console storm / #470 cancel / #771 never-booted) and ${missing} shard(s) did not report. This is a re-run signal, NOT a product regression — the run stays green so main-health is readable and no false red-CI email fires. Re-run the emulator-journey job."
  emit_summary "RE-RUN" "${have_infra} infra shard(s) + ${missing} missing shard(s), no genuine failure — re-run"
  echo "AGGREGATE_VERDICT=RE-RUN"
  exit 0
fi

# Every shard reported CLEAN and none are missing.
echo "All ${count} shard(s) reported CLEAN — the emulator journey gate is trustworthy this run."
emit_summary "CLEAN" "every shard passed"
echo "AGGREGATE_VERDICT=CLEAN"
exit 0
