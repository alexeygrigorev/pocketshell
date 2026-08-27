#!/usr/bin/env bash
# Flake-quarantine list — shared parsing library (issue #2355, policy D36).
#
# WHAT THIS IS. `scripts/journey-quarantine.txt` names journey classes that are
# KNOWN to flake (fail, then pass on a rerun with no code change — the
# `JOURNEY_FLAKE_RECOVERED` signal `scripts/ci-journey-class-loop-functions.sh`
# already emits). A quarantined class:
#   * still runs, every push, exactly like any other selected journey class —
#     quarantine touches NOTHING upstream of the retry loop;
#   * if it fails BOTH attempts anyway, its failure is reported (its own
#     section in the run's summary.md, never hidden) but does NOT flip the
#     suite's exit code — that is the "non-blocking lane" from D36;
#   * carries an EXPIRY. `scripts/check-journey-quarantine-expiry.sh` fails CI
#     when an entry's expiry has passed without being resolved (removed) or
#     re-triaged (a fresh row with a new expiry + reason) — quarantine is a
#     queue with a deadline, never a graveyard.
#
# FORMAT — one quarantine per line, TAB-separated, comments (`#`) and blank
# lines ignored:
#
#   <FQCN><TAB><issue><TAB><added YYYY-MM-DD><TAB><expires YYYY-MM-DD><TAB><reason>
#
#   FQCN    — the fully-qualified journey class name, exactly as it appears in
#             scripts/ci-journey-suite.sh's EFFECTIVE_JOURNEY_CLASSES list (a
#             `#method` suffix is stripped for matching, same convention as
#             scripts/select-test-areas.sh's journey_registry_classes()).
#   issue   — the GitHub issue tracking the flake, e.g. `#2360` (bare number is
#             also accepted).
#   added   — ISO date the class was quarantined.
#   expires — ISO date after which the guard below fails CI. ~2 weeks out is
#             the default (D36); nothing here enforces that distance — the
#             guard enforces only that `expires` is a real date AFTER `added`.
#   reason  — free text (no TABs). Mandatory and non-empty.
#
# FAIL-SAFE DIRECTION. If the file is missing, empty, or fails to parse, NO
# class is treated as quarantined — every failure blocks, exactly the
# always-widen-toward-more-scrutiny convention scripts/lib/test-areas.sh
# established for the sibling coverage taxonomy. A quarantine list that failed
# to load must never silently make MORE failures non-blocking.

POCKETSHELL_JQ_FQCN=()
POCKETSHELL_JQ_ISSUE=()
POCKETSHELL_JQ_ADDED=()
POCKETSHELL_JQ_EXPIRES=()
POCKETSHELL_JQ_REASON=()
POCKETSHELL_JQ_LOAD_ERRORS=()
POCKETSHELL_JQ_LOADED=0

pocketshell_journey_quarantine_default_file() {
  local dir
  dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
  printf '%s/journey-quarantine.txt\n' "$dir"
}

# pocketshell_journey_quarantine_load [file] — parse the quarantine list.
# Returns 0 iff the file exists and every non-comment/non-blank row is
# well-formed. A malformed row is dropped from the lookup table (fail-safe:
# that specific FQCN is treated as NOT quarantined) and recorded in
# POCKETSHELL_JQ_LOAD_ERRORS; the function still returns non-zero so a caller
# that cares can distinguish "empty list" from "broken list".
pocketshell_journey_quarantine_load() {
  local file="${1:-}"
  [[ -z "$file" ]] && file="$(pocketshell_journey_quarantine_default_file)"

  POCKETSHELL_JQ_FQCN=(); POCKETSHELL_JQ_ISSUE=(); POCKETSHELL_JQ_ADDED=()
  POCKETSHELL_JQ_EXPIRES=(); POCKETSHELL_JQ_REASON=(); POCKETSHELL_JQ_LOAD_ERRORS=()
  POCKETSHELL_JQ_LOADED=0

  if [[ ! -f "$file" ]]; then
    # Missing file is not an error — an empty quarantine list is the normal
    # steady state (no class currently flaking). Only a PRESENT-but-broken file
    # is a load error.
    POCKETSHELL_JQ_LOADED=1
    return 0
  fi

  local lineno=0 line fqcn issue added expires reason rest
  while IFS= read -r line || [[ -n "$line" ]]; do
    lineno=$((lineno + 1))
    line="${line%%$'\r'}"
    [[ -z "${line//[[:space:]]/}" ]] && continue
    [[ "$line" == \#* ]] && continue
    IFS=$'\t' read -r fqcn issue added expires reason rest <<<"$line"
    if [[ -z "$fqcn" || -z "$issue" || -z "$added" || -z "$expires" || -z "${reason//[[:space:]]/}" ]]; then
      POCKETSHELL_JQ_LOAD_ERRORS+=("$file:$lineno: expected <fqcn>TAB<issue>TAB<added>TAB<expires>TAB<reason>, got: $line")
      continue
    fi
    if [[ -n "$rest" ]]; then
      POCKETSHELL_JQ_LOAD_ERRORS+=("$file:$lineno: reason field contains a TAB (or too many columns): $line")
      continue
    fi
    for existing in "${POCKETSHELL_JQ_FQCN[@]:-}"; do
      if [[ "$existing" == "$fqcn" ]]; then
        POCKETSHELL_JQ_LOAD_ERRORS+=("$file:$lineno: duplicate quarantine entry for $fqcn")
        continue 2
      fi
    done
    POCKETSHELL_JQ_FQCN+=("$fqcn")
    POCKETSHELL_JQ_ISSUE+=("$issue")
    POCKETSHELL_JQ_ADDED+=("$added")
    POCKETSHELL_JQ_EXPIRES+=("$expires")
    POCKETSHELL_JQ_REASON+=("$reason")
  done < "$file"

  POCKETSHELL_JQ_LOADED=1
  [[ "${#POCKETSHELL_JQ_LOAD_ERRORS[@]}" -eq 0 ]]
}

# pocketshell_journey_quarantine_contains <fqcn> — true iff the class (its
# `#method` suffix stripped, matching the journey-registry convention) has a
# well-formed row in the currently-loaded list. Fails closed (returns 1 / "not
# quarantined") when the list was never loaded — a caller must load first.
pocketshell_journey_quarantine_contains() {
  local target="${1%%#*}" i
  [[ "$POCKETSHELL_JQ_LOADED" -eq 1 ]] || return 1
  for i in "${!POCKETSHELL_JQ_FQCN[@]}"; do
    [[ "${POCKETSHELL_JQ_FQCN[$i]%%#*}" == "$target" ]] && return 0
  done
  return 1
}

# pocketshell_journey_quarantine_entry_index <fqcn> — echoes the row index or
# nothing (rc 1) if absent. Used by callers that want the issue/expiry/reason.
pocketshell_journey_quarantine_entry_index() {
  local target="${1%%#*}" i
  for i in "${!POCKETSHELL_JQ_FQCN[@]}"; do
    if [[ "${POCKETSHELL_JQ_FQCN[$i]%%#*}" == "$target" ]]; then
      printf '%s\n' "$i"
      return 0
    fi
  done
  return 1
}
