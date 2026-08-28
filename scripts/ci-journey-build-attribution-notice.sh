#!/usr/bin/env bash
# Issues #1814 / #1840 / #2374: emit the emulator-journey shard's Gradle-BUILD
# attribution annotations.
#
# Extracted verbatim from `.github/workflows/tests.yml`'s classify step so the
# workflow stays under scripts/check-file-size-hygiene.sh's workflow headroom —
# that guard's own prescribed remedy, with the explanatory comments moving here
# alongside the text they explain.
#
# #1814. An attempt cut by its per-class wall cap while Gradle was still
# BUILDING writes `exit 124 / outer_timeout / raw-junit count=0` — byte-for-byte
# what a killed mid-journey runner writes. On run 30323508796 shard 2 that
# ambiguity sent an investigation after a healthy journey class. Naming the
# build-cost case stops it looking like a product defect.
#
# #1840. The sibling one step over: a retry whose Gradle BUILD DIED never
# started instrumentation and produced NO journey verdict, yet on run
# 30339688411 shard 0 it was reported as the class "failing twice" — the suite's
# post-timeout `gradlew --stop` left an orphaned build process writing
# `kotlin-classes/debugAndroidTest`, the retry's `compileDebugAndroidTestKotlin`
# died clearing it, and a self-inflicted infra cascade was typed as a product
# defect.
#
# #2374. Both annotations still fire on their own evidence, unconditionally.
# What changed is only who may speak for the shard's VERDICT REASON: see
# scripts/ci-journey-genuine-journey-failure.sh. An annotation naming one class
# must not become the whole shard's explanation when other classes genuinely
# failed.
#
# Reporting only in every case: severity is unchanged (a build that outgrows its
# bound, or cannot run at all, is a real problem that must stay visible —
# softening it would be #1458-style masking in reverse). Always exits 0.
#
# Usage:
#   ci-journey-build-attribution-notice.sh phase   ATTEMPTS CLASSES_CSV
#   ci-journey-build-attribution-notice.sh failure ATTEMPTS CLASSES_CSV
set -uo pipefail

kind="${1:-}"
attempts="${2:-0}"
classes="${3:-}"

[[ "$attempts" =~ ^[0-9]+$ ]] || attempts=0
(( attempts > 0 )) || exit 0
[[ -n "$classes" ]] || classes="<unnamed>"

case "$kind" in
  phase)
    echo "::warning title=Emulator journey — an attempt was cut during the Gradle BUILD phase (#1814)::${attempts} attempt(s) on this shard hit their per-class wall cap while Gradle was still BUILDING: ${classes}. Instrumentation never started, so there is no JUnit XML and the zero-test outer timeout is a BUILD-COST artefact — it is NOT evidence that the named journey is broken. The cold build is paid up front by the suite's warm-build step, so a recurrence means the build outgrew its bound; investigate the build, not the journey."
    ;;
  failure)
    echo "::warning title=Emulator journey — an attempt died at the Gradle BUILD level (#1840)::${attempts} attempt(s) on this shard failed while Gradle was still BUILDING: ${classes}. Instrumentation never started, so there is no JUnit XML and this attempt produced NO journey verdict — it is NOT evidence that the named journey is broken. Check the inter-attempt cleanup (an orphaned build process still writing the module build outputs makes the next build die clearing them) and the build itself, not the listed journey."
    ;;
  *)
    echo "ci-journey-build-attribution-notice.sh: unknown kind '$kind'" >&2
    ;;
esac
exit 0
