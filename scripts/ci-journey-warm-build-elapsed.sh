#!/usr/bin/env bash
# Issue #1833: read the COLD-BUILD cost the suite paid up front out of the
# summary artifact it writes (scripts/ci-journey-summary-functions.sh).
#
# WHY THIS NUMBER EXISTS AS A SEPARATE READING
# --------------------------------------------
# Issue #1800 sizes the cold-boot retry against the first attempt's own measured
# suite elapsed. Issue #1814 then made the shard's cold Gradle build an explicit,
# separately measured phase INSIDE that elapsed:
#
#   Warm build (issue #1814): **ok** in 464s — paid before the per-class budget
#   clock, charged to the suite budget.
#
# That build is the one part of the suite a within-job retry does NOT repeat.
# The retry re-creates the AVD and cold-boots a fresh emulator, but it runs on
# the SAME runner, against the same `~/.gradle` and the same `app/build` the
# first attempt populated — so `:app:assembleDebug`,
# `:app:assembleDebugAndroidTest` and `:shared:core-terminal:assembleDebugAndroidTest`
# are UP-TO-DATE the second time.
#
# Observed, not assumed — run 30318408377, job 90153112226 (shard 0), the one
# run in recent history where the cold-boot retry actually executed. Same class,
# same job, first class on the shard, from the suite's own budget-remaining
# stamps:
#
#   attempt 1 (cold Gradle):  TmuxSessionScreenArtVerifyE2eTest  4200s -> 3889s  = 311s
#   attempt 2 (the retry, warm Gradle): same class               4200s -> 4179s  =  21s
#
# 290 of those 311 seconds were build, and the retry paid none of them; its whole
# suite came in 379s cheaper (1987s -> 1608s) on the identical 48-class
# selection. Post-#1814 that same work is the named warm-build phase, measured
# directly at 373s / 464s / 489s across run 30383504733's three shards.
#
# So this reading is what lets scripts/ci-journey-retry-budget.sh price a WARM
# retry instead of charging the cold build a second time.
#
# STATUS GATE: only a warm build that reports **ok** is deductible. A `failed`,
# `skipped` or `not_run` warm build means the cold build was NOT paid as a
# separate up-front phase — the class loop absorbed it, exactly as before #1814 —
# so there is nothing separately measured to deduct and this exits 1, leaving the
# caller on #1800's unmodified model. Fail-safe direction is toward DENYING the
# retry, never toward permitting one on a guess.
#
# Prints the warm-build seconds on stdout and exits 0 when it can be read; prints
# nothing and exits 1 otherwise.
set -uo pipefail

summary="${1-}"

[[ -n "$summary" && -f "$summary" ]] || exit 1

elapsed="$(
  sed -n 's/^Warm build (issue #1814): \*\*ok\*\* in \([0-9][0-9]*\)s .*$/\1/p' \
    "$summary" | head -n 1
)"

[[ "$elapsed" =~ ^[0-9]+$ ]] || exit 1
printf '%s\n' "$elapsed"
