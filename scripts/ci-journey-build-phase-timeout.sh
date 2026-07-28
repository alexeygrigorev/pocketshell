#!/usr/bin/env bash
# Issue #1814: report whether ANY preserved attempt on this shard died while
# Gradle was still BUILDING, so the shard verdict can say so instead of leaving
# a build-cost timeout looking exactly like a genuine journey failure.
#
# Why this exists: an attempt cut by the per-class wall cap during the build
# phase writes `primary_classification=outer_timeout` with `raw_junit_count=0`
# — byte-for-byte identical to what a killed mid-journey runner writes. On run
# 30323508796 shard 2 that ambiguity sent an investigation after a healthy
# journey class (`MultiSessionSwitchJourneyE2eTest`) when the real cause was
# that the first class on the shard was paying the cold `:app:compileDebugKotlin`
# inside its own per-class budget.
#
# The evidence is the per-attempt manifest the suite already writes
# (`outer_timeout_phase=`, added by scripts/ci-journey-budget-functions.sh).
# This reads BOTH the live artifact tree and the preserved first-attempt
# snapshot, exactly like scripts/ci-journey-shard-signature-verdict.sh, so a
# retry cannot hide the first attempt's evidence.
#
# Usage:
#   ci-journey-build-phase-timeout.sh [ARTIFACTS_DIR]      (default: artifacts)
#
# Output (key=value on stdout; always exits 0 so a caller can never be broken by
# missing evidence — absence of evidence reports zero, never an error):
#   build_phase_timeout_attempts=<N>
#   build_phase_timeout_classes=<comma-separated FQCNs, or empty>
#
# Reporting only: this NEVER changes a verdict's severity. A build-phase timeout
# still counts exactly as much as it did before — it is merely named. Softening
# it to a re-run signal would be the masking D31/D32 exist to prevent (a build
# that reliably outgrows its bound is a real problem that must stay visible).
set -uo pipefail

artifacts_dir="${1:-artifacts}"

attempts=0
classes=""

if [[ -d "$artifacts_dir" ]]; then
  while IFS= read -r -d '' manifest; do
    grep -Fqx 'outer_timeout_phase=build' "$manifest" 2>/dev/null || continue
    attempts=$((attempts + 1))
    class="$(sed -n 's/^class=//p' "$manifest" 2>/dev/null | head -n 1)"
    [[ -n "$class" ]] || class="<unnamed>"
    case ",$classes," in
      *",$class,"*) ;;
      *) classes="${classes:+$classes,}$class" ;;
    esac
  done < <(find "$artifacts_dir" -type f -name 'manifest.txt' -print0 2>/dev/null)
fi

printf 'build_phase_timeout_attempts=%s\n' "$attempts"
printf 'build_phase_timeout_classes=%s\n' "$classes"
