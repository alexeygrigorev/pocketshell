#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

CASES=0
pass_case() {
  CASES=$((CASES + 1))
  printf '  ok: %s\n' "$1"
}

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

bash -n "$ROOT_DIR/scripts/phone-walkthrough.sh"
pass_case "phone-walkthrough.sh parses"

PHONE_WALKTHROUGH_VERIFY_DISPATCH_ONLY=1 \
  LOG_ROOT="$tmpdir/phone-walkthrough" \
  RUN_ID="dispatch-all" \
  "$ROOT_DIR/scripts/phone-walkthrough.sh" all > "$tmpdir/all.out"

for expected in \
  "terminal-lab -> run_terminal_lab" \
  "tmux-existing-session -> run_tmux_existing_session" \
  "visual-audit -> run_visual_audit" \
  "setup-detection -> run_setup_detection"; do
  grep -Fq "$expected" "$tmpdir/all.out" ||
    fail "missing dispatch verification line: $expected"
  pass_case "dispatches $expected"
done

PHONE_WALKTHROUGH_VERIFY_DISPATCH_ONLY=1 \
  LOG_ROOT="$tmpdir/phone-walkthrough" \
  RUN_ID="dispatch-profile" \
  "$ROOT_DIR/scripts/phone-walkthrough.sh" setup-detection:ready > "$tmpdir/profile.out"

grep -Fq "setup-detection:ready -> run_setup_detection" "$tmpdir/profile.out" ||
  fail "missing setup-detection profile dispatch verification"
pass_case "dispatches the setup-detection:ready profile form"

# Issue #2113: the count line is what makes the JVM assertion about behaviour
# rather than about bash's exit status.
(( CASES == 6 )) || fail "expected 6 cases to run, saw $CASES"
printf 'PASS: phone walkthrough dispatch handlers (%s cases)\n' "$CASES"
