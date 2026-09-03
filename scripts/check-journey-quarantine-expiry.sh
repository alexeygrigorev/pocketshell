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
#   2. every entry's FQCN is a REAL registered journey class, and names a
#      `#method` that class actually declares (a class or method renamed away
#      must not sit in the list unnoticed — its row belongs somewhere real or
#      nowhere);
#   3. `added` and `expires` are real ISO dates with `expires` strictly after
#      `added`;
#   4. `expires` has not already passed as of today;
#   5. RECONCILIATION, list -> source: the named method carries an
#      `@Ignore("quarantined: #<issue>, expires <expires> ...")` whose issue and
#      expiry MATCH the row. A row whose @Ignore is missing is a quarantine
#      that was never in effect; a row whose @Ignore disagrees about the expiry
#      is two deadlines, one of which nothing enforces;
#   6. RECONCILIATION, source -> list: every `@Ignore` on an app2 journey
#      `@Test` has a row. This is the direction that makes the list a QUEUE
#      rather than a graveyard — without it, anyone can silently `@Ignore` a
#      journey test forever, with no issue, no expiry and no owner, which is
#      the exact outcome D36 exists to prevent.
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
# Overridable so the sibling mechanism test can drive the REAL guard against a
# mutated list without writing into the worktree.
DEFAULT_FILE="${POCKETSHELL_JOURNEY_QUARANTINE_FILE:-$SCRIPT_DIR/journey-quarantine.txt}"
JOURNEY_SUITE="${POCKETSHELL_TEST_AREAS_JOURNEY_SUITE:-$SCRIPT_DIR/ci-app2-journey-suite.sh}"
# The androidTest root the journey lane runs, derived from the suite (below) so
# this guard and the selection engine cannot disagree about where journeys live.
JOURNEY_ROOT_OVERRIDE="${POCKETSHELL_JOURNEY_QUARANTINE_ROOT:-}"

# shellcheck source=scripts/lib/journey-quarantine.sh
source "$SCRIPT_DIR/lib/journey-quarantine.sh"

# The androidTest root whose classes the journey lane runs, derived from the
# suite's own JOURNEY_TASK (issue #2474: the lane runs one module wholesale, so
# the registry IS that module's instrumented source set).
journey_root() {
  if [[ -n "$JOURNEY_ROOT_OVERRIDE" ]]; then
    printf '%s\n' "$JOURNEY_ROOT_OVERRIDE"
    return 0
  fi
  [[ -f "$JOURNEY_SUITE" ]] || return 1
  local task mod
  task="$(sed -nE 's/^JOURNEY_TASK="([^"]+)".*/\1/p' "$JOURNEY_SUITE" | head -1)"
  [[ -n "$task" ]] || return 1
  mod="${task#:}"; mod="${mod%:*}"
  printf '%s/%s/src/androidTest\n' "$REPO_ROOT" "${mod//://}"
}

journey_registry_classes() {
  local root
  root="$(journey_root)" || return 0
  [[ -d "$root" ]] || return 0
  local f rel
  while IFS= read -r f; do
    [[ -n "$f" ]] || continue
    grep -qE '^[[:space:]]*@Test([[:space:]]|\(|$)' "$f" || continue
    rel="${f#"$root"/}"
    rel="${rel#java/}"; rel="${rel#kotlin/}"
    rel="${rel%.kt}"; rel="${rel%.java}"
    printf '%s\n' "${rel//\//.}"
  done < <(find "$root" -type f \( -name '*.kt' -o -name '*.java' \) 2>/dev/null) |
    LC_ALL=C sort -u
}

# Every `@Ignore`-carrying @Test method in the journey root, as `FQCN#method`.
# This is check 6's input: the source side of the reconciliation.
journey_ignored_methods() {
  local root
  root="$(journey_root)" || return 0
  [[ -d "$root" ]] || return 0
  local f rel fqcn
  while IFS= read -r f; do
    [[ -n "$f" ]] || continue
    rel="${f#"$root"/}"
    rel="${rel#java/}"; rel="${rel#kotlin/}"
    rel="${rel%.kt}"; rel="${rel%.java}"
    fqcn="${rel//\//.}"
    awk -v cls="$fqcn" '
      /^[[:space:]]*@Ignore([[:space:]]|\(|$)/ { pending = 1; next }
      /^[[:space:]]*fun[[:space:]]/ {
        if (pending) {
          line = $0
          sub(/^[[:space:]]*fun[[:space:]]+/, "", line)
          sub(/[[:space:]]*\(.*$/, "", line)
          print cls "#" line
        }
        pending = 0; next
      }
      /^[[:space:]]*$/ { pending = 0 }
    ' "$f"
  done < <(find "$root" -type f \( -name '*.kt' -o -name '*.java' \) 2>/dev/null) |
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

    local cls method
    cls="$(pocketshell_journey_quarantine_class "$fqcn")"
    method="$(pocketshell_journey_quarantine_method "$fqcn")"

    if [[ -z "$method" ]]; then
      failures+=("$fqcn: no '#method' — quarantine is per METHOD (an @Ignore annotates one function), so the row must name the exact @Test it exempts")
    fi
    if [[ -n "$registry" ]] && ! grep -qxF "$cls" <<<"$registry"; then
      failures+=("$fqcn: $cls is not a journey class in the lane's androidTest root — remove this row or fix the FQCN")
    elif [[ -n "$method" ]]; then
      # Check 5 — list -> source. The @Ignore must exist on that exact method
      # and agree with the row about who owns it and when it expires.
      local root src ignore_reason
      root="$(journey_root || true)"
      if ! src="$(pocketshell_journey_quarantine_source_for_class "$root" "$cls")"; then
        failures+=("$fqcn: no source file found for $cls under $root")
      elif ! ignore_reason="$(pocketshell_journey_quarantine_ignore_reason "$src" "$method")"; then
        failures+=("$fqcn: the row claims a quarantine but $method carries NO @Ignore in ${src#"$REPO_ROOT"/} — the quarantine is not in effect, so the method still blocks (add the annotation or drop the row)")
      else
        if [[ "$ignore_reason" != *"$issue"* ]]; then
          failures+=("$fqcn: @Ignore text does not name the tracked issue $issue (got: $ignore_reason)")
        fi
        if [[ "$ignore_reason" != *"$expires"* ]]; then
          failures+=("$fqcn: @Ignore text does not name the row's expiry $expires (got: $ignore_reason) — two deadlines means one of them is unenforced")
        fi
      fi
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

  # Check 6 — source -> list. An @Ignore with no row has no issue, no expiry
  # and no owner: it is a permanent silent exclusion, which is precisely the
  # graveyard D36 forbids. Scanned even when the list is empty or failed to
  # load, because that is exactly when an untracked @Ignore would slip through.
  local ignored
  while IFS= read -r ignored; do
    [[ -n "$ignored" ]] || continue
    if ! pocketshell_journey_quarantine_contains "$ignored"; then
      failures+=("$ignored: @Ignore on a journey @Test with NO row in $(basename "$file") — an untracked quarantine has no issue, no expiry and no owner (add a row or delete the annotation)")
    fi
  done < <(journey_ignored_methods)

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

  # A SYNTHETIC journey root, so the source-reconciliation cases (5 and 6) can
  # plant and mutate @Ignore annotations without touching the real tree. Every
  # case below runs against it via JOURNEY_ROOT_OVERRIDE.
  local root="$sandbox/androidTest"
  mkdir -p "$root/java/com/pocketshell/next/probe"
  write_probe_source() {  # $1 = @Ignore line for flaky() (empty = none)
    cat > "$root/java/com/pocketshell/next/probe/JZZProbeJourney.kt" <<KT
package com.pocketshell.next.probe
import org.junit.Ignore
import org.junit.Test
class JZZProbeJourney {
    @Test
${1:+$1}
    fun flaky() {}

    @Test
    fun healthy() {}
}
KT
  }
  local IGNORE_OK='    @Ignore("quarantined: #1, expires 2026-01-15 — synthetic")'
  write_probe_source "$IGNORE_OK"
  JOURNEY_ROOT_OVERRIDE="$root"

  local real_class="com.pocketshell.next.probe.JZZProbeJourney#flaky"

  # 1. A fresh, well-formed, non-expired, registered entry -> clean.
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
com.example.NoSuchClassEverRegistered#nope	#1	2026-01-01	2026-01-15	bogus
EOF
  out="$(check_file "$sandbox/unregistered.txt" "2026-01-05")"
  count="$(sed -n 's/^FAILCOUNT=//p' <<<"$out")"
  if [[ "$count" -eq 0 ]] || ! grep -q 'is not a journey class in the lane' <<<"$out"; then
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

  # 6. An empty file, with no @Ignore anywhere -> clean (the steady state:
  #    nothing quarantined, nothing annotated). The probe source is rewritten
  #    WITHOUT its annotation first, because an empty list plus a live @Ignore
  #    is case 8's red, not this one's green.
  write_probe_source ""
  : > "$sandbox/empty.txt"
  out="$(check_file "$sandbox/empty.txt" "2026-01-05")"
  count="$(sed -n 's/^FAILCOUNT=//p' <<<"$out")"
  if [[ "$count" -ne 0 ]]; then
    echo "$out"
    echo "SELF-TEST FAIL: an empty quarantine list must be clean"; rc=1
  else
    echo "  ok: empty list -> clean"
  fi

  # 7. RECONCILIATION list -> source: the row is well-formed but the method
  #    carries NO @Ignore. The quarantine was never in effect, so the method
  #    still blocks — that must be loud, not a green row.
  write_probe_source ""
  out="$(check_file "$sandbox/fresh.txt" "2026-01-05")"
  count="$(sed -n 's/^FAILCOUNT=//p' <<<"$out")"
  if [[ "$count" -eq 0 ]] || ! grep -q 'carries NO @Ignore' <<<"$out"; then
    echo "$out"
    echo "SELF-TEST FAIL: a row whose method has no @Ignore must FAIL"; rc=1
  else
    echo "  ok: row without its @Ignore -> FAIL (quarantine not in effect)"
  fi

  # 8. RECONCILIATION source -> list: an @Ignore with no row. This is the
  #    graveyard direction — no issue, no expiry, no owner, forever.
  write_probe_source "$IGNORE_OK"
  : > "$sandbox/empty2.txt"
  out="$(check_file "$sandbox/empty2.txt" "2026-01-05")"
  count="$(sed -n 's/^FAILCOUNT=//p' <<<"$out")"
  if [[ "$count" -eq 0 ]] || ! grep -q 'NO row in' <<<"$out"; then
    echo "$out"
    echo "SELF-TEST FAIL: an untracked @Ignore must FAIL"; rc=1
  else
    echo "  ok: untracked @Ignore -> FAIL (graveyard direction)"
  fi

  # 9. The @Ignore and the row must agree about the DEADLINE. Two dates means
  #    one of them is enforced by nothing.
  write_probe_source '    @Ignore("quarantined: #1, expires 2099-01-01 — synthetic")'
  out="$(check_file "$sandbox/fresh.txt" "2026-01-05")"
  count="$(sed -n 's/^FAILCOUNT=//p' <<<"$out")"
  if [[ "$count" -eq 0 ]] || ! grep -q "does not name the row's expiry" <<<"$out"; then
    echo "$out"
    echo "SELF-TEST FAIL: a drifted @Ignore expiry must FAIL"; rc=1
  else
    echo "  ok: @Ignore expiry disagreeing with the row -> FAIL"
  fi

  # 10. Sibling isolation: quarantining `flaky` must NOT exempt `healthy`.
  #     A class-level match here would silently stop enforcing a method nobody
  #     triaged, which is why matching is on the whole FQCN#method key.
  write_probe_source "$IGNORE_OK"
  cat > "$sandbox/sibling.txt" <<EOF
com.pocketshell.next.probe.JZZProbeJourney#healthy	#1	2026-01-01	2026-01-15	claims the WRONG method
EOF
  out="$(check_file "$sandbox/sibling.txt" "2026-01-05")"
  count="$(sed -n 's/^FAILCOUNT=//p' <<<"$out")"
  if [[ "$count" -eq 0 ]] ||
     ! grep -q 'healthy: the row claims a quarantine but healthy carries NO @Ignore' <<<"$out" ||
     ! grep -q 'flaky: @Ignore on a journey @Test with NO row' <<<"$out"; then
    echo "$out"
    echo "SELF-TEST FAIL: a row naming the wrong sibling method must FAIL in BOTH directions"; rc=1
  else
    echo "  ok: sibling methods are independent (row->source and source->list both fire)"
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
