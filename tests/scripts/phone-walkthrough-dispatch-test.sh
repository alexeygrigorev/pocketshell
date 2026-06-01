#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

tmpdir="$(mktemp -d)"
trap 'rm -rf "$tmpdir"' EXIT

bash -n "$ROOT_DIR/scripts/phone-walkthrough.sh"

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
done

PHONE_WALKTHROUGH_VERIFY_DISPATCH_ONLY=1 \
  LOG_ROOT="$tmpdir/phone-walkthrough" \
  RUN_ID="dispatch-profile" \
  "$ROOT_DIR/scripts/phone-walkthrough.sh" setup-detection:ready > "$tmpdir/profile.out"

grep -Fq "setup-detection:ready -> run_setup_detection" "$tmpdir/profile.out" ||
  fail "missing setup-detection profile dispatch verification"

printf 'PASS: phone walkthrough dispatch handlers\n'
