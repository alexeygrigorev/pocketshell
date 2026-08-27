#!/usr/bin/env bash
# check-journey-quarantine-expiry.sh — issue #2355, policy D36.
#
# The flake-quarantine list (scripts/journey-quarantine.txt) is a QUEUE, not a
# graveyard: an entry that sits past its `expires` date without being resolved
# (the row deleted — the class fixed or removed) or re-triaged (the row
# replaced with a fresh `added`/`expires` and a reason for why it is still
# flaking) must fail CI loudly rather than silently keep exempting a class from
# blocking failures forever.
#
# This guard is the mechanical enforcement of that:
#   1. the list parses cleanly (scripts/lib/journey-quarantine.sh);
#   2. every entry's FQCN is a REAL registered journey class (a class that was
#      renamed or removed from scripts/ci-journey-suite.sh must not sit in the
#      list unnoticed — its row belongs somewhere real or nowhere);
#   3. `added` and `expires` are real ISO dates with `expires` strictly after
#      `added`;
#   4. `expires` has not already passed as of today.
#
# Usage:
#   scripts/check-journey-quarantine-expiry.sh              # check the real list
#   scripts/check-journey-quarantine-expiry.sh --self-test   # synthetic red/green proof
#
# Exit codes: 0 = clean (or --self-test passed), 1 = a real check failed (or a
# --self-test assertion failed).

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
DEFAULT_FILE="$SCRIPT_DIR/journey-quarantine.txt"
JOURNEY_SUITE="$SCRIPT_DIR/ci-journey-suite.sh"

# shellcheck source=scripts/lib/journey-quarantine.sh
source "$SCRIPT_DIR/lib/journey-quarantine.sh"

# Same shape as scripts/select-test-areas.sh's journey_registry_classes() —
# kept as its own small copy deliberately (the repo's existing convention:
# scripts/check-ci-journey-harness.sh also carries its own copy rather than a
# shared lib none of the three agree to depend on).
journey_registry_classes() {
  local suite="${1:-$JOURNEY_SUITE}"
  [[ -f "$suite" ]] || return 0
  sed -nE \
    -e 's/.*"\$FQCN_PREFIX\.([A-Za-z0-9_]+)(#[^"]*)?".*/com.pocketshell.app.proof.\1/p' \
    -e 's/.*"(com\.pocketshell\.app\.[A-Za-z0-9_.]+)(#[^"]*)?".*/\1/p' \
    "$suite" |
    grep -E '\.[A-Z][A-Za-z0-9_]*$' |
    LC_ALL=C sort -u
}

is_iso_date() {
  # `date -d` parses more than strict ISO (e.g. "next tuesday"); anchor with a
  # regex first so only YYYY-MM-DD is ever accepted, then confirm it is a real
  # calendar date.
  [[ "$1" =~ ^[0-9]{4}-[0-9]{2}-[0-9]{2}$ ]] || return 1
  date -u -d "$1" >/dev/null 2>&1
}

days_between() {
  # $2 - $1, in whole days. Both args are already-validated ISO dates.
  local a b
  a="$(date -u -d "$1" +%s)"
  b="$(date -u -d "$2" +%s)"
  echo $(( (b - a) / 86400 ))
}

# check_file FILE TODAY_ISO -> prints failures to stdout, returns count via
# echo of the count on the LAST line prefixed 'FAILCOUNT='. Kept as a function
# (not inlined) so --self-test can call it against a synthetic file + a fixed
# "today" without touching the real clock.
check_file() {
  local file="$1" today="$2"
  local -a failures=()

  if ! pocketshell_journey_quarantine_load "$file"; then
    local e
    for e in "${POCKETSHELL_JQ_LOAD_ERRORS[@]}"; do
      failures+=("parse error: $e")
    done
  fi

  if [[ "${#POCKETSHELL_JQ_FQCN[@]}" -eq 0 ]]; then
    echo "OK: quarantine list is empty (no well-formed entries) — $file"
  fi

  local registry
  registry="$(journey_registry_classes)"

  local i fqcn issue added expires reason
  for i in "${!POCKETSHELL_JQ_FQCN[@]}"; do
    fqcn="${POCKETSHELL_JQ_FQCN[$i]}"
    issue="${POCKETSHELL_JQ_ISSUE[$i]}"
    added="${POCKETSHELL_JQ_ADDED[$i]}"
    expires="${POCKETSHELL_JQ_EXPIRES[$i]}"
    reason="${POCKETSHELL_JQ_REASON[$i]}"

    if [[ -n "$registry" ]] && ! grep -qxF "$fqcn" <<<"$registry"; then
      failures+=("$fqcn: not a registered journey class in scripts/ci-journey-suite.sh — remove this row or fix the FQCN")
    fi

    if ! is_iso_date "$added"; then
      failures+=("$fqcn: 'added' is not a real YYYY-MM-DD date: $added")
    fi
    if ! is_iso_date "$expires"; then
      failures+=("$fqcn: 'expires' is not a real YYYY-MM-DD date: $expires")
    fi
    if is_iso_date "$added" && is_iso_date "$expires"; then
      if [[ "$(days_between "$added" "$expires")" -le 0 ]]; then
        failures+=("$fqcn: 'expires' ($expires) is not after 'added' ($added)")
      fi
    fi

    if [[ "$issue" != \#* ]] && ! [[ "$issue" =~ ^[0-9]+$ ]]; then
      failures+=("$fqcn: 'issue' does not look like a GitHub issue reference (#N or N): $issue")
    fi

    if is_iso_date "$expires" && is_iso_date "$today"; then
      if [[ "$(days_between "$expires" "$today")" -gt 0 ]]; then
        failures+=("$fqcn: quarantine EXPIRED $(days_between "$expires" "$today") day(s) ago (expires $expires, today $today, tracked $issue, reason: $reason) — resolve (delete the row) or re-triage (a fresh added/expires + reason) per policy D36")
      fi
    fi
  done

  local f
  for f in "${failures[@]}"; do
    echo "FAIL: $f"
  done
  echo "FAILCOUNT=${#failures[@]}"
}

run_real() {
  local today
  today="$(date -u +%Y-%m-%d)"
  echo "== check-journey-quarantine-expiry =="
  echo "file: $DEFAULT_FILE"
  echo "today (UTC): $today"
  local out count
  out="$(check_file "$DEFAULT_FILE" "$today")"
  count="$(sed -n 's/^FAILCOUNT=//p' <<<"$out")"
  sed '/^FAILCOUNT=/d' <<<"$out"
  if [[ "${count:-1}" -gt 0 ]]; then
    echo
    echo "::error title=Flake quarantine expired (issue #2355 / policy D36)::$count entry(ies) in scripts/journey-quarantine.txt need attention."
    return 1
  fi
  echo "PASS: quarantine list is clean."
  return 0
}

run_self_test() {
  local sandbox
  sandbox="$(mktemp -d)"
  trap 'rm -rf "$sandbox"' RETURN

  local rc=0

  # 1. A fresh, well-formed, non-expired, registered entry -> clean.
  local real_class
  real_class="$(journey_registry_classes | head -1)"
  [[ -n "$real_class" ]] || { echo "SELF-TEST SETUP FAIL: no registered journey classes found to anchor the fixture against"; return 1; }
  cat > "$sandbox/fresh.txt" <<EOF
$real_class	#1	2026-01-01	2026-01-15	fresh, not expired
EOF
  out="$(check_file "$sandbox/fresh.txt" "2026-01-05")"
  count="$(sed -n 's/^FAILCOUNT=//p' <<<"$out")"
  if [[ "$count" -ne 0 ]]; then
    echo "$out"
    echo "SELF-TEST FAIL: a fresh well-formed entry must not fail"; rc=1
  else
    echo "  ok: fresh well-formed entry -> clean"
  fi

  # 2. Same entry, evaluated AFTER its expiry -> must FAIL, mentioning EXPIRED.
  out="$(check_file "$sandbox/fresh.txt" "2026-01-20")"
  count="$(sed -n 's/^FAILCOUNT=//p' <<<"$out")"
  if [[ "$count" -eq 0 ]] || ! grep -q 'EXPIRED' <<<"$out"; then
    echo "$out"
    echo "SELF-TEST FAIL: an entry past its expires date must FAIL with EXPIRED"; rc=1
  else
    echo "  ok: expired entry -> FAIL, mentions EXPIRED"
  fi

  # 3. Malformed row (too few fields) -> must FAIL as a parse error, and must
  #    NOT silently vanish from the count.
  cat > "$sandbox/malformed.txt" <<EOF
$real_class	only-two-fields
EOF
  out="$(check_file "$sandbox/malformed.txt" "2026-01-05")"
  count="$(sed -n 's/^FAILCOUNT=//p' <<<"$out")"
  if [[ "$count" -eq 0 ]] || ! grep -q 'parse error' <<<"$out"; then
    echo "$out"
    echo "SELF-TEST FAIL: a malformed row must FAIL as a parse error"; rc=1
  else
    echo "  ok: malformed row -> FAIL (parse error)"
  fi

  # 4. Unregistered class name -> must FAIL naming it "not a registered journey class".
  cat > "$sandbox/unregistered.txt" <<EOF
com.example.NoSuchClassEverRegistered	#1	2026-01-01	2026-01-15	bogus
EOF
  out="$(check_file "$sandbox/unregistered.txt" "2026-01-05")"
  count="$(sed -n 's/^FAILCOUNT=//p' <<<"$out")"
  if [[ "$count" -eq 0 ]] || ! grep -q 'not a registered journey class' <<<"$out"; then
    echo "$out"
    echo "SELF-TEST FAIL: an unregistered FQCN must FAIL"; rc=1
  else
    echo "  ok: unregistered FQCN -> FAIL"
  fi

  # 5. expires <= added -> must FAIL.
  cat > "$sandbox/backwards.txt" <<EOF
$real_class	#1	2026-01-15	2026-01-01	backwards dates
EOF
  out="$(check_file "$sandbox/backwards.txt" "2026-01-05")"
  count="$(sed -n 's/^FAILCOUNT=//p' <<<"$out")"
  if [[ "$count" -eq 0 ]] || ! grep -q 'is not after' <<<"$out"; then
    echo "$out"
    echo "SELF-TEST FAIL: expires <= added must FAIL"; rc=1
  else
    echo "  ok: expires <= added -> FAIL"
  fi

  # 6. An empty file -> clean (steady state: nothing quarantined).
  : > "$sandbox/empty.txt"
  out="$(check_file "$sandbox/empty.txt" "2026-01-05")"
  count="$(sed -n 's/^FAILCOUNT=//p' <<<"$out")"
  if [[ "$count" -ne 0 ]]; then
    echo "$out"
    echo "SELF-TEST FAIL: an empty quarantine list must be clean"; rc=1
  else
    echo "  ok: empty list -> clean"
  fi

  if [[ "$rc" -eq 0 ]]; then
    echo "PASS: check-journey-quarantine-expiry self-test."
  else
    echo "SELF-TEST: one or more cases FAILED (see above)."
  fi
  return "$rc"
}

case "${1:-}" in
  --self-test) run_self_test ;;
  "") run_real ;;
  *) echo "unknown argument: $1" >&2; echo "usage: $0 [--self-test]" >&2; exit 1 ;;
esac
