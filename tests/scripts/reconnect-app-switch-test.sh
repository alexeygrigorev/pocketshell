#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

script="$ROOT_DIR/scripts/reconnect-app-switch.sh"

bash -n "$script"

grep -q 'BackgroundGraceReconnectE2eTest#sixSecondAppSwitchWithProductionGraceDoesNotShowOrRecordReconnect' "$script" ||
  fail "script default selector must run the six-second production-grace reconnect proof"

grep -q 'issue548-background-grace-reconnect' "$script" ||
  fail "script must pull the issue548 artifact directory"

grep -q 'six_second_production_grace_cycle_ms' "$script" ||
  fail "script must validate the six-second app-switch timing artifact"

printf 'PASS: reconnect app-switch harness\n'
