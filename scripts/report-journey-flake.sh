#!/usr/bin/env bash
# report-journey-flake.sh — issue #2355, policy D36 "auto-file on first flake".
#
# THE SIGNAL. The per-class journey loop that emitted this signal was deleted with the old
# app module; app2's unfiltered lane surfaces a flake as a differing result
# between runs of the same commit. The runbook below still applies. It detects the
# exact D36 flake definition mechanically: a class that FAILS attempt 1 then
# PASSES attempt 2 with no code change is printed as
# `JOURNEY_FLAKE_RECOVERED: <fqcn> passed on retry ...` in the journey suite's
# own log. This script is what an on-call (or, per the header note below, a
# future automated step) runs against that signal.
#
# WHY THIS IS A SEMI-AUTOMATED PROCEDURE, NOT A FULLY WIRED CI STEP (this
# round). Filing a GitHub issue and moving a class into the non-blocking
# quarantine lane from inside the required per-push gate is a real, durable
# decision (D36: "a flaking test/journey class ... must be ... auto-filed ...
# moved to a designated non-blocking lane") that risks spamming duplicate
# issues or quarantining a class on a SINGLE observed flake (D36's window is
# "first occurrence", but a run killed by infra for an unrelated reason can
# also print a superficially similar failure-then-recovery). Wiring this
# directly into tests.yml/app2.yml (issue #2355's non-goals: avoid
# touching those without a stated reason) would also need `gh` auth threaded
# into the emulator-journey job and careful de-dup against concurrent shards
# reporting the same class. Scoped down per the issue's own guidance: this
# script IS the real, tested mechanism; a human/on-call runs it after seeing a
# `JOURNEY_FLAKE_RECOVERED` line (or a repeated one across runs), rather than
# it firing unattended. Automating the trigger is a natural follow-up once this
# has run for real occurrences.
#
# RUNBOOK (see docs/testing.md "Flake quarantine (issue #2355)" for the full
# write-up):
#   1. Notice `JOURNEY_FLAKE_RECOVERED: <fqcn> ...` in a journey-suite log (or
#      a `SHARD_LANE_RETRY` recovery), or a class that fails twice repeatedly
#      across otherwise-unrelated pushes.
#   2. scripts/report-journey-flake.sh <fqcn> --run-url <url> [--file-issue]
#      Preview with no flags (default is --dry-run); add --file-issue to
#      actually create/update the tracking issue via `gh issue create` /
#      `gh issue comment` (de-duplicated by searching for the FQCN in existing
#      issue titles first).
#   3. Within 24h of the first occurrence (D36), quarantine it. Quarantine is
#      TWO steps now, because it is enforced at the SOURCE (see
#      scripts/lib/journey-quarantine.sh's header — app2's lane runs unfiltered
#      in one process per #2474, so there is no runner-level seam to exempt a
#      class at):
#        a) annotate the METHOD:
#             @Ignore("quarantined: #NNNN, expires YYYY-MM-DD — <reason>")
#        b) register it:
#             scripts/report-journey-flake.sh '<fqcn>#<method>' --quarantine \
#               --issue '#NNNN' --reason "<short reason>" [--days 14]
#      scripts/check-journey-quarantine-expiry.sh reconciles the two in both
#      directions, so doing only one of them fails CI.
#
# Usage:
#   scripts/report-journey-flake.sh <fqcn> [--run-url URL] [--dry-run|--file-issue]
#   scripts/report-journey-flake.sh <fqcn#method> --quarantine --issue REF --reason TEXT [--days N]
#   scripts/report-journey-flake.sh --self-test

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
QUARANTINE_FILE="${POCKETSHELL_JOURNEY_QUARANTINE_FILE:-$SCRIPT_DIR/journey-quarantine.txt}"
GH_BIN="${POCKETSHELL_GH_BIN:-gh}"

# shellcheck source=scripts/lib/journey-quarantine.sh
source "$SCRIPT_DIR/lib/journey-quarantine.sh"

usage() {
  sed -n '2,42p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
}

issue_body() {
  local fqcn="$1" run_url="$2"
  cat <<EOF
A journey class flaked in CI: it failed on the first attempt and passed on an
immediate retry with no code change (\`JOURNEY_FLAKE_RECOVERED\`), matching the
D36 flake definition (process.md "Main health ... flake quarantine").

**Method:** \`$fqcn\`
**Run:** ${run_url:-<not supplied — attach the run URL manually>}

Per policy D36 this should be quarantined into the non-blocking lane within
24h if it recurs (a single occurrence may still be worth a closer look before
quarantining — see scripts/report-journey-flake.sh's header runbook):

\`\`\`
scripts/report-journey-flake.sh "$fqcn#<method>" --quarantine --issue '#<this-issue>' --reason "<short reason>"
\`\`\`

Quarantine is applied at the SOURCE, as \`@Ignore("quarantined: #<issue>,
expires <date> — <reason>")\` on the specific method, plus a row in
scripts/journey-quarantine.txt. It does NOT remove anything from the run: the
whole suite still executes together in one instrumentation process (issue
#2474), so the quarantined class keeps sharing that process and cross-journey
pollution stays observable for every other test. What changes is only that the
known-bad assertion reports as skipped-with-reason instead of failing.

Expiry is enforced by scripts/check-journey-quarantine-expiry.sh, which also
reconciles annotation and row in both directions; resolve or re-triage before
it expires.
EOF
}

cmd_report() {
  local fqcn="$1"; shift
  local run_url="" mode="dry-run"
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --run-url) run_url="$2"; shift 2 ;;
      --dry-run) mode="dry-run"; shift ;;
      --file-issue) mode="file"; shift ;;
      *) echo "unknown argument: $1" >&2; usage >&2; exit 1 ;;
    esac
  done

  local title="Flaky journey class: $fqcn"
  local body
  body="$(issue_body "$fqcn" "$run_url")"

  if [[ "$mode" == "dry-run" ]]; then
    echo "-- DRY RUN (no issue created; pass --file-issue to actually file) --"
    echo "title: $title"
    echo
    echo "$body"
    return 0
  fi

  # Resolved fresh on every call (not from the script-startup GH_BIN) so a
  # per-invocation POCKETSHELL_GH_BIN override — the ONLY way --self-test may
  # safely exercise the --file-issue path without ever reaching a real `gh` —
  # actually takes effect. A stale top-level GH_BIN was the round-1 defect: the
  # self-test's override silently missed this function and created a real
  # GitHub issue (#2364, deleted) instead of hitting the "not found" branch.
  local gh_bin="${POCKETSHELL_GH_BIN:-$GH_BIN}"
  if ! command -v "$gh_bin" >/dev/null 2>&1; then
    echo "report-journey-flake: '$gh_bin' not found — cannot file an issue. Preview:" >&2
    echo "title: $title" >&2
    echo "$body" >&2
    return 1
  fi

  local existing
  existing="$("$gh_bin" issue list --state open --search "\"$fqcn\" in:title" --json number,title \
    --jq ".[] | select(.title | contains(\"$fqcn\")) | .number" 2>/dev/null | head -1)"
  if [[ -n "$existing" ]]; then
    echo "report-journey-flake: an open issue already names $fqcn: #$existing — adding a comment instead of a duplicate"
    "$gh_bin" issue comment "$existing" --body "Recurred.$([[ -n "$run_url" ]] && echo " Run: $run_url")"
    return $?
  fi

  "$gh_bin" issue create --title "$title" --body "$body"
}

cmd_quarantine() {
  local fqcn="$1"; shift
  local issue="" reason="" days=14
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --issue) issue="$2"; shift 2 ;;
      --reason) reason="$2"; shift 2 ;;
      --days) days="$2"; shift 2 ;;
      *) echo "unknown argument: $1" >&2; usage >&2; exit 1 ;;
    esac
  done
  case "$fqcn" in
    *#*) : ;;
    *) echo "report-journey-flake --quarantine: expected <fqcn>#<method> — quarantine annotates ONE @Test method, so a class-only key would claim more than the @Ignore actually exempts" >&2; exit 1 ;;
  esac
  [[ -n "$issue" ]] || { echo "report-journey-flake --quarantine: --issue is required" >&2; exit 1; }
  [[ -n "$reason" ]] || { echo "report-journey-flake --quarantine: --reason is required" >&2; exit 1; }
  [[ "$reason" != *$'\t'* ]] || { echo "report-journey-flake --quarantine: --reason must not contain a TAB" >&2; exit 1; }

  pocketshell_journey_quarantine_load "$QUARANTINE_FILE" >/dev/null 2>&1 || true
  if pocketshell_journey_quarantine_contains "$fqcn"; then
    echo "report-journey-flake: $fqcn already has a quarantine row in $QUARANTINE_FILE — edit it directly to re-triage" >&2
    exit 1
  fi

  local added expires
  added="$(date -u +%Y-%m-%d)"
  expires="$(date -u -d "+${days} days" +%Y-%m-%d)"
  printf '%s\t%s\t%s\t%s\t%s\n' "$fqcn" "$issue" "$added" "$expires" "$reason" >> "$QUARANTINE_FILE"
  echo "report-journey-flake: appended quarantine row for $fqcn (expires $expires) to $QUARANTINE_FILE"
}

run_self_test() {
  local rc=0

  # 1. --dry-run (the default) prints a preview and creates nothing.
  out="$(cmd_report "com.example.SelfTestFlake" --run-url "https://example.invalid/run/1")"
  if ! grep -q 'DRY RUN' <<<"$out" || ! grep -q 'com.example.SelfTestFlake' <<<"$out"; then
    echo "$out"
    echo "SELF-TEST FAIL: --dry-run did not produce the expected preview"; rc=1
  else
    echo "  ok: --dry-run previews the title and body without needing gh"
  fi

  # 2. --file-issue with gh absent fails loudly rather than pretending to
  #    file. issue #2364 (deleted): this exact case FIRST SHIPPED BROKEN — the
  #    override was assigned to POCKETSHELL_GH_BIN but cmd_report read a
  #    stale top-level GH_BIN, so the "self-test" silently filed a REAL
  #    GitHub issue instead of exercising the not-found branch. cmd_report now
  #    resolves `${POCKETSHELL_GH_BIN:-$GH_BIN}` fresh on every call, which is
  #    what makes this override load-bearing rather than decorative. The path
  #    named here is guaranteed absent (not just "not on PATH" — an absolute
  #    path under a directory that cannot exist), so even a PATH-resolution
  #    quirk on the runner cannot accidentally make it resolve to a real `gh`.
  out="$(POCKETSHELL_GH_BIN=/nonexistent/gh-does-not-exist cmd_report "com.example.SelfTestFlake" --file-issue 2>&1)"
  rc2=$?
  if [[ "$rc2" -eq 0 ]] || ! grep -q 'not found' <<<"$out"; then
    echo "$out"
    echo "SELF-TEST FAIL: --file-issue with no gh binary must fail loudly, not silently no-op"; rc=1
  else
    echo "  ok: --file-issue with no gh binary fails loudly, never files"
  fi
  # Belt-and-braces: assert the fix's OWN mechanism — the fresh per-call
  # resolution — is actually present in the source, so a future edit cannot
  # silently reintroduce the stale-GH_BIN defect this case exists to catch.
  if ! grep -q 'local gh_bin="\${POCKETSHELL_GH_BIN:-\$GH_BIN}"' "${BASH_SOURCE[0]}"; then
    echo "SELF-TEST FAIL: cmd_report no longer resolves POCKETSHELL_GH_BIN fresh per call (the #2364 regression shape) — case 2 above would be decorative"; rc=1
  else
    echo "  ok: cmd_report still resolves gh_bin fresh per call (guards against the #2364 regression shape)"
  fi

  # 3. --quarantine appends a well-formed row that the expiry guard's own
  #    parser accepts, and refuses a duplicate.
  local sandbox; sandbox="$(mktemp -d)"
  local qf="$sandbox/journey-quarantine.txt"
  : > "$qf"
  QUARANTINE_FILE="$qf" cmd_quarantine "com.example.SelfTestFlake#flaky" --issue '#1' --reason "self-test" --days 14 >/dev/null
  pocketshell_journey_quarantine_load "$qf" >/dev/null
  if ! pocketshell_journey_quarantine_contains "com.example.SelfTestFlake#flaky"; then
    cat "$qf"
    echo "SELF-TEST FAIL: --quarantine did not append a row the loader accepts"; rc=1
  elif pocketshell_journey_quarantine_contains "com.example.SelfTestFlake#other"; then
    cat "$qf"
    echo "SELF-TEST FAIL: quarantining one method also matched a SIBLING method — the exemption would cover tests nobody triaged"; rc=1
  else
    echo "  ok: --quarantine appends a well-formed, loader-accepted row that matches only its own method"
  fi
  # A class-only key must be refused outright: the @Ignore it implies does not
  # exist, so the row could never reconcile against the source.
  if (QUARANTINE_FILE="$qf" cmd_quarantine "com.example.ClassOnly" --issue '#3' --reason "no method" --days 14) 2>/dev/null; then
    echo "SELF-TEST FAIL: --quarantine accepted a class-only key"; rc=1
  else
    echo "  ok: --quarantine refuses a class-only key (quarantine is per method)"
  fi
  # Run in a SUBSHELL: cmd_quarantine's duplicate guard calls `exit 1` (correct
  # for real CLI usage — it must actually terminate the process), and calling
  # it directly here would kill this whole self-test process, not just this
  # one assertion, silently losing every check after it (and PASS) with no
  # FAIL line — exactly a "no result reads as a passing one" trap. `( )` makes
  # that `exit` end only the subshell.
  if (QUARANTINE_FILE="$qf" cmd_quarantine "com.example.SelfTestFlake#flaky" --issue '#2' --reason "dup" --days 14) 2>/dev/null; then
    echo "SELF-TEST FAIL: --quarantine must refuse to double-quarantine the same class"; rc=1
  else
    echo "  ok: --quarantine refuses a duplicate entry"
  fi
  rm -rf "$sandbox"

  if [[ "$rc" -eq 0 ]]; then
    echo "PASS: report-journey-flake self-test."
  fi
  return "$rc"
}

# Guard the dispatch so this file can be `source`d (the self-test's empty-PATH
# subshell above does exactly that) without also running the CLI dispatch
# against whatever positional args happened to be in scope at source time.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  case "${1:-}" in
    --self-test) run_self_test ;;
    -h|--help|"") usage; [[ "${1:-}" == "" ]] && exit 1 || exit 0 ;;
    --quarantine)
      shift
      echo "usage: $0 <fqcn> --quarantine ..." >&2; exit 1 ;;
    *)
      fqcn="$1"; shift
      if [[ "${1:-}" == "--quarantine" ]]; then
        shift
        cmd_quarantine "$fqcn" "$@"
      else
        cmd_report "$fqcn" "$@"
      fi
      ;;
  esac
fi
