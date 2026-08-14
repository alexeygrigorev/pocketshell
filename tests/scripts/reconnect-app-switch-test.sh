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

script="$ROOT_DIR/scripts/reconnect-app-switch.sh"

bash -n "$script"
pass_case "reconnect-app-switch.sh parses"

grep -q 'BackgroundGraceReconnectE2eTest#sixSecondAppSwitchWithProductionGraceDoesNotShowOrRecordReconnect' "$script" ||
  fail "script default selector must run the six-second production-grace reconnect proof"
pass_case "default selector runs the six-second production-grace reconnect proof"

grep -q 'issue548-background-grace-reconnect' "$script" ||
  fail "script must pull the issue548 artifact directory"
pass_case "pulls the issue548 artifact directory"

grep -q 'six_second_production_grace_cycle_ms' "$script" ||
  fail "script must validate the six-second app-switch timing artifact"
pass_case "validates the six-second app-switch timing artifact"

# A harness that exits 0 having run nothing is the vacuous green process.md
# catalogues (issue #2113): an early `exit 0` after case 1 used to leave this
# green. The count is what makes the JVM assertion about behaviour.
(( CASES == 4 )) || fail "expected 4 cases to run, saw $CASES"
printf 'PASS: reconnect app-switch harness (%s cases)\n' "$CASES"
