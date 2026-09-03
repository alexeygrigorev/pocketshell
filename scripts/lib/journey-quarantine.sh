#!/usr/bin/env bash
# Flake-quarantine list — shared parsing library (issue #2355, policy D36).
#
# WHAT THIS IS. `scripts/journey-quarantine.txt` names journey TEST METHODS
# that are KNOWN to fail or flake, each with a tracking issue and an expiry.
#
# HOW QUARANTINE IS ENFORCED (changed for app2, issue #2474).
#
# The old mechanism was a runner-level exclusion: the per-class journey loop
# read this list and, for a listed class, reported a both-attempts failure
# without flipping the suite's exit code. That loop is gone. app2's lane runs
# `:app2:connectedDebugAndroidTest` ONCE, unfiltered, in a single
# instrumentation process, and #2474 forbids a
# `-Pandroid.testInstrumentationRunnerArguments.class=` filter precisely
# because such a filter is what would hide cross-journey state pollution
# (that is how #2477 was found).
#
# So quarantine moved DOWN to the source level: a quarantined method carries
#
#     @Ignore("quarantined: #<issue>, expires <YYYY-MM-DD> — <reason>")
#
# This satisfies BOTH constraints rather than trading one off against the
# other:
#   * D36 gets its real requirement — a known-bad assertion stops blocking
#     within the SLA, is still visible (JUnit reports it as skipped WITH the
#     reason string, never silently absent), and carries an expiry this
#     library's guard enforces;
#   * #2474 keeps its real guarantee — the whole suite still executes together
#     in one process. Nothing is excluded from the run, so every OTHER test
#     still shares the process with the quarantined class and cross-journey
#     pollution stays observable.
#
# The list is therefore no longer consumed at runtime by anything. It is the
# REGISTRY that `scripts/check-journey-quarantine-expiry.sh` reconciles against
# the source tree, in BOTH directions:
#   * every row must have a matching `@Ignore` in the named method (a claimed
#     quarantine that is not in effect is a lie), and
#   * every `@Ignore` on an app2 journey `@Test` must have a matching row (an
#     untracked `@Ignore` is the graveyard D36 exists to prevent — no issue, no
#     expiry, no one ever coming back to it).
#
# FORMAT — one quarantine per line, TAB-separated, comments (`#`) and blank
# lines ignored:
#
#   <FQCN#method><TAB><issue><TAB><added YYYY-MM-DD><TAB><expires YYYY-MM-DD><TAB><reason>
#
#   FQCN#method — the journey class, then `#`, then the exact `fun` name of the
#             quarantined @Test method. Method-level is the point: quarantining
#             one broken assertion must not silently stop running its siblings.
#   issue   — the GitHub issue tracking it, e.g. `#2478` (bare number accepted).
#   added   — ISO date the method was quarantined.
#   expires — ISO date after which the guard fails CI. ~2 weeks is the D36
#             default; a longer window is allowed when the row says why (a real
#             investigation rather than a quick fix). The guard enforces only
#             that `expires` is a real date AFTER `added`, and that it has not
#             passed.
#   reason  — free text (no TABs). Mandatory and non-empty.
#
# FAIL-SAFE DIRECTION. If the file is missing, empty, or fails to parse, NO
# method is treated as quarantined — so every `@Ignore` in the tree becomes an
# UNTRACKED one and the guard reddens. A quarantine list that failed to load
# must never silently legitimise more exclusions.

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

# pocketshell_journey_quarantine_contains <fqcn#method> — true iff that EXACT
# method has a well-formed row in the currently-loaded list. Matching is on the
# whole key, not the class: quarantining one method must not exempt its
# siblings. Fails closed (returns 1 / "not quarantined") when the list was
# never loaded — a caller must load first.
pocketshell_journey_quarantine_contains() {
  local target="$1" i
  [[ "$POCKETSHELL_JQ_LOADED" -eq 1 ]] || return 1
  for i in "${!POCKETSHELL_JQ_FQCN[@]}"; do
    [[ "${POCKETSHELL_JQ_FQCN[$i]}" == "$target" ]] && return 0
  done
  return 1
}

# pocketshell_journey_quarantine_entry_index <fqcn#method> — echoes the row
# index or nothing (rc 1). Used by callers that want issue/expiry/reason.
pocketshell_journey_quarantine_entry_index() {
  local target="$1" i
  for i in "${!POCKETSHELL_JQ_FQCN[@]}"; do
    if [[ "${POCKETSHELL_JQ_FQCN[$i]}" == "$target" ]]; then
      printf '%s\n' "$i"
      return 0
    fi
  done
  return 1
}

# pocketshell_journey_quarantine_class <fqcn#method>  -> the class half.
pocketshell_journey_quarantine_class() { printf '%s\n' "${1%%#*}"; }
# pocketshell_journey_quarantine_method <fqcn#method> -> the method half, or
# empty when the key carries no `#method` (which the guard rejects).
pocketshell_journey_quarantine_method() {
  case "$1" in
    *#*) printf '%s\n' "${1#*#}" ;;
    *)   printf '' ;;
  esac
}

# pocketshell_journey_quarantine_source_for_class <root> <fqcn> — the source
# path under <root> for a journey class, or nothing (rc 1) when absent.
pocketshell_journey_quarantine_source_for_class() {
  local root="$1" fqcn="$2" rel candidate
  rel="${fqcn//./\/}"
  for candidate in "$root/java/$rel.kt" "$root/kotlin/$rel.kt" "$root/java/$rel.java"; do
    [[ -f "$candidate" ]] && { printf '%s\n' "$candidate"; return 0; }
  done
  return 1
}

# pocketshell_journey_quarantine_ignore_reason <file> <method> — echoes the
# string inside the `@Ignore("...")` that immediately governs `fun <method>`,
# or nothing (rc 1) when that method carries no @Ignore.
#
# "Immediately governs" means: within the annotation block directly above the
# `fun` line, i.e. after the previous `fun`/`}` boundary. Scanning the whole
# file for @Ignore would let one method's annotation vouch for another's.
pocketshell_journey_quarantine_ignore_reason() {
  local file="$1" method="$2"
  [[ -f "$file" ]] || return 1
  awk -v m="$method" '
    # Reset the pending annotation block at each declaration boundary.
    /^[[:space:]]*fun[[:space:]]/ {
      if ($0 ~ ("fun[[:space:]]+" m "[[:space:]]*\\(")) { if (ign != "") { print ign; found = 1 } ; exit }
      ign = ""; next
    }
    /^[[:space:]]*@Ignore[[:space:]]*\(/ {
      line = $0
      sub(/^[^(]*\([[:space:]]*"?/, "", line)
      sub(/"?[[:space:]]*\)[[:space:]]*$/, "", line)
      ign = line
      next
    }
    /^[[:space:]]*@Ignore[[:space:]]*$/ { ign = "(no reason given)"; next }
    # A blank line or a closing brace ends an annotation block.
    /^[[:space:]]*$/ { ign = ""; next }
    END { if (!found) exit 1 }
  ' "$file"
}
