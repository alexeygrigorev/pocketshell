#!/usr/bin/env bash
# Issue #1800: read the journey suite's OWN measured wall cost out of the
# summary artifact it writes (scripts/ci-journey-summary-functions.sh).
#
# The summary's result table row is:
#   | <N> load-bearing journey classes (shard i/n; per-class retry-once) | \
#     `pocketshellCi=true` | <exit> | <elapsed>s | **<STATUS>** |
#
# The 4th column is the measured elapsed seconds. That measurement — not the
# 4200s budget CAP — is what re-running the same selection actually costs, and
# it is what scripts/ci-journey-retry-budget.sh needs to size the cold-boot
# retry requirement against the remaining job wall.
#
# Prints the elapsed seconds on stdout and exits 0 when it can be read; prints
# nothing and exits 1 otherwise, so the caller falls back to the flat worst
# case instead of trusting a guess.
set -uo pipefail

summary="${1-}"

[[ -n "$summary" && -f "$summary" ]] || exit 1

elapsed="$(
  awk -F'|' '
    /load-bearing journey classes/ && NF >= 6 {
      value = $5
      gsub(/[ \t]/, "", value)
      if (value ~ /^[0-9]+s$/) {
        sub(/s$/, "", value)
        print value
        exit
      }
    }
  ' "$summary"
)"

[[ "$elapsed" =~ ^[0-9]+$ ]] || exit 1
printf '%s\n' "$elapsed"
