#!/usr/bin/env bash
# Issue #1840: report whether ANY preserved attempt on this shard died at the
# GRADLE BUILD level, so the shard verdict can say so instead of leaving a
# build-level failure looking exactly like a class that genuinely failed twice.
#
# Why this exists: on run 30339688411 shard 0 a per-class timeout triggered the
# suite's own `gradlew --stop` cleanup, the retry's
# `:app:compileDebugAndroidTestKotlin` then died clearing
# `app/build/tmp/kotlin-classes/debugAndroidTest` ("New files were found. This
# might happen because a process is still writing to the target directory"), and
# instrumentation never started. The shard reported
# `JOURNEY_FAILED: ... failed twice` and the classifier typed it RED with the
# reason `journey_failure_both_attempts` — a self-inflicted infra cascade wearing
# a product defect's clothes. The attempt never produced a journey verdict at
# all, and the artifacts must say so.
#
# This is the exact sibling of scripts/ci-journey-build-phase-timeout.sh (issue
# #1814) and reads the SAME per-attempt manifests the suite already writes — the
# `attempt_failure_phase=` key added by scripts/ci-journey-budget-functions.sh.
# It reads BOTH the live artifact tree and the preserved first-attempt snapshot,
# exactly like scripts/ci-journey-shard-signature-verdict.sh, so a retry cannot
# hide the first attempt's evidence.
#
# Usage:
#   ci-journey-build-phase-failure.sh [ARTIFACTS_DIR]      (default: artifacts)
#
# Output (key=value on stdout; always exits 0 so a caller can never be broken by
# missing evidence — absence of evidence reports zero, never an error):
#   build_phase_failure_attempts=<N>
#   build_phase_failure_classes=<comma-separated FQCNs, or empty>
#
# Reporting only: this NEVER changes a verdict's severity. A build-level failure
# still counts exactly as much as it did before — it is merely named. Softening
# it to a re-run signal would be the masking D31/D32 exist to prevent (a build
# that cannot run is a real problem that must stay visible).
set -uo pipefail

artifacts_dir="${1:-artifacts}"

attempts=0
classes=""

if [[ -d "$artifacts_dir" ]]; then
  while IFS= read -r -d '' manifest; do
    grep -Fqx 'attempt_failure_phase=build' "$manifest" 2>/dev/null || continue
    attempts=$((attempts + 1))
    class="$(sed -n 's/^class=//p' "$manifest" 2>/dev/null | head -n 1)"
    [[ -n "$class" ]] || class="<unnamed>"
    case ",$classes," in
      *",$class,"*) ;;
      *) classes="${classes:+$classes,}$class" ;;
    esac
  done < <(find "$artifacts_dir" -type f -name 'manifest.txt' -print0 2>/dev/null)
fi

printf 'build_phase_failure_attempts=%s\n' "$attempts"
printf 'build_phase_failure_classes=%s\n' "$classes"
