#!/usr/bin/env bash
set -euo pipefail
#
# check-connection-vm-ratchet.sh — connection-core god-object ratchet (issue #1047).
#
# Three sub-second PURE STATIC GREP guards over the single connection-core
# god-object, app/.../tmux/TmuxSessionViewModel.kt. They are Path-B guardrails:
# protective under BOTH the "consolidate into core-connection.ConnectionController"
# path AND the "keep patching the VM" path. They never assert behavior; they only
# stop the dual-authority / god-object from GROWING. Each is a ONE-WAY downward
# ratchet — `--update` may only lower a cap (after an extraction slice removes
# IO / a decision variant / lines), never raise it.
#
#   G-A  no new un-seamed IO in the VM
#        FAIL if the count of `Dispatchers.IO` OR `runBlocking` in the VM exceeds
#        the baseline. New owned IO must go through an injectable dispatcher seam,
#        not a hardcoded dispatcher.
#
#   G-B  freeze the ConnectionDecision variant count
#        FAIL if the number of `ConnectionDecision` variants (lines matching
#        `: ConnectionDecision$`) exceeds the baseline. New lifecycle/reconnect
#        decisions belong in core-connection.ConnectionController, not the inline
#        mirror.
#
#   G-C  no-growth line cap on the god-object
#        FAIL if `wc -l` of the VM exceeds the baseline. The VM may only shrink.
#
# The baselines were RE-MEASURED on `main` @ 3f5c668f (issue #1324 re-land of the
# #1054 ratchet, re-baselined to current reality) and live in
# scripts/connection-vm-ratchet-baseline.txt. Lowering them after an extraction
# slice is the whole point — `--update` re-bakes the (lower) current counts.
#
# Usage:
#   scripts/check-connection-vm-ratchet.sh            # check the real VM vs baseline
#   scripts/check-connection-vm-ratchet.sh --update   # re-baseline DOWNWARD only
#   scripts/check-connection-vm-ratchet.sh --self-test # synthetic red->green proof
#
# Exit codes:
#   0  no growth (all counts <= baseline)   [also: --update / --self-test succeeded]
#   1  growth found (a count exceeds its baseline), or --update tried to RAISE a cap
#   2  bad usage / missing file
#
# Cheap: pure grep + wc over one file, well under a second. Wired into the Unit
# job of .github/workflows/tests.yml (a fast static guard — no emulator needed),
# alongside check-component-drift.sh / check-design-tokens.sh / check-test-validity.sh.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

VM_REL="app/src/main/java/com/pocketshell/app/tmux/TmuxSessionViewModel.kt"
BASELINE_REL="scripts/connection-vm-ratchet-baseline.txt"

usage() {
  cat <<'USAGE'
Usage: scripts/check-connection-vm-ratchet.sh [--update | --self-test]

Three downward-only static ratchets over TmuxSessionViewModel.kt (issue #1047):
  G-A  Dispatchers.IO + runBlocking count may not grow (use a dispatcher seam)
  G-B  ConnectionDecision variant count may not grow (use ConnectionController)
  G-C  the file's line count may not grow (the god-object may only shrink)

(no args)    check the real VM against the committed baseline; exit 1 on growth
--update     re-baseline to the CURRENT counts, but ONLY downward — refuses to
             raise any cap (run it after an extraction slice lowers a count)
--self-test  run a synthetic red->green proof on fixtures (no real files touched)
USAGE
}

# --- measurement helpers (each prints a single integer) -----------------------

count_io() {
  # G-A numerator part 1: hardcoded Dispatchers.IO uses.
  grep -cE 'Dispatchers\.IO' "$1" || true
}

count_runblocking() {
  # G-A numerator part 2: runBlocking uses.
  grep -cE '\brunBlocking\b' "$1" || true
}

count_decision_variants() {
  # G-B: ConnectionDecision sealed-hierarchy variants — lines ending in
  # `: ConnectionDecision` (the `data object Foo : ConnectionDecision` form).
  grep -cE ': ConnectionDecision$' "$1" || true
}

count_lines() {
  # G-C: raw line count.
  wc -l < "$1" | tr -d ' '
}

# Core comparison. Args: <vm-file> <base-io> <base-runblocking> <base-variants> <base-lines>
# Exits 0 when every current count <= its baseline, 1 otherwise. Prints a per-guard verdict.
check_against() {
  local vm="$1" base_io="$2" base_rb="$3" base_var="$4" base_lines="$5"

  if [[ ! -f "$vm" ]]; then
    printf 'FAIL: missing VM file %s\n' "$vm" >&2
    return 2
  fi

  local io rb var lines
  io="$(count_io "$vm")"
  rb="$(count_runblocking "$vm")"
  var="$(count_decision_variants "$vm")"
  lines="$(count_lines "$vm")"

  local fail=0

  printf '== connection-vm-ratchet (%s) ==\n' "$vm"

  # G-A — IO seam ratchet (Dispatchers.IO + runBlocking).
  if (( io > base_io )); then
    printf 'G-A FAIL: Dispatchers.IO uses %d > baseline %d (+%d). ' \
      "$io" "$base_io" "$((io - base_io))" >&2
    printf 'New owned IO must go through an injectable dispatcher seam, not a hardcoded Dispatchers.IO.\n' >&2
    fail=1
  else
    printf 'G-A ok: Dispatchers.IO %d (baseline %d)\n' "$io" "$base_io"
  fi
  if (( rb > base_rb )); then
    printf 'G-A FAIL: runBlocking uses %d > baseline %d (+%d). ' \
      "$rb" "$base_rb" "$((rb - base_rb))" >&2
    printf 'New owned IO must go through an injectable dispatcher seam, not runBlocking.\n' >&2
    fail=1
  else
    printf 'G-A ok: runBlocking %d (baseline %d)\n' "$rb" "$base_rb"
  fi

  # G-B — ConnectionDecision variant freeze.
  if (( var > base_var )); then
    printf 'G-B FAIL: ConnectionDecision variants %d > baseline %d (+%d). ' \
      "$var" "$base_var" "$((var - base_var))" >&2
    printf 'New lifecycle/reconnect decisions belong in core-connection.ConnectionController, not the inline mirror.\n' >&2
    fail=1
  else
    printf 'G-B ok: ConnectionDecision variants %d (baseline %d)\n' "$var" "$base_var"
  fi

  # G-C — no-growth line cap.
  if (( lines > base_lines )); then
    printf 'G-C FAIL: TmuxSessionViewModel.kt is %d lines > baseline %d (+%d). ' \
      "$lines" "$base_lines" "$((lines - base_lines))" >&2
    printf 'The VM may only shrink; extract into core-connection rather than growing the god-object.\n' >&2
    fail=1
  else
    printf 'G-C ok: %d lines (baseline %d)\n' "$lines" "$base_lines"
  fi

  if (( fail != 0 )); then
    printf 'connection-vm-ratchet: FAIL — the connection-core god-object grew. ' >&2
    printf 'These are one-way downward ratchets (issue #1047); do not raise a cap. ' >&2
    printf 'If a count genuinely DROPPED elsewhere, that does not license a rise here.\n' >&2
    return 1
  fi
  printf 'connection-vm-ratchet: OK — no growth (G-A/G-B/G-C all within baseline).\n'
  return 0
}

# --- baseline file I/O --------------------------------------------------------

# Reads BASELINE_FILE into the four globals BL_IO BL_RB BL_VAR BL_LINES.
load_baseline() {
  local file="$1"
  if [[ ! -f "$file" ]]; then
    printf 'FAIL: missing baseline %s — run: scripts/check-connection-vm-ratchet.sh --update\n' "$file" >&2
    return 2
  fi
  BL_IO=""; BL_RB=""; BL_VAR=""; BL_LINES=""
  local key val
  while read -r key val; do
    [[ -z "$key" || "$key" == \#* ]] && continue
    case "$key" in
      io_count) BL_IO="$val" ;;
      runblocking_count) BL_RB="$val" ;;
      connection_decision_variants) BL_VAR="$val" ;;
      vm_line_count) BL_LINES="$val" ;;
    esac
  done < "$file"
  if [[ -z "$BL_IO" || -z "$BL_RB" || -z "$BL_VAR" || -z "$BL_LINES" ]]; then
    printf 'FAIL: baseline %s is missing one of io_count/runblocking_count/connection_decision_variants/vm_line_count\n' "$file" >&2
    return 2
  fi
  return 0
}

write_baseline() {
  local file="$1" io="$2" rb="$3" var="$4" lines="$5"
  {
    echo "# connection-core god-object ratchet baseline (issue #1047; re-landed + re-baselined #1324)"
    echo "# Measured over app/src/main/java/com/pocketshell/app/tmux/TmuxSessionViewModel.kt."
    echo "# ONE-WAY DOWNWARD ratchet: scripts/check-connection-vm-ratchet.sh --update may"
    echo "# only LOWER these (after an extraction slice removes IO / a decision / lines)."
    echo "# Never hand-raise a value — that is exactly the growth the guard exists to stop."
    echo "io_count $io"
    echo "runblocking_count $rb"
    echo "connection_decision_variants $var"
    echo "vm_line_count $lines"
  } > "$file"
}

run_update() {
  local vm="$REPO_ROOT/$VM_REL" file="$REPO_ROOT/$BASELINE_REL"
  if [[ ! -f "$vm" ]]; then
    printf 'FAIL: missing VM file %s\n' "$vm" >&2
    return 2
  fi
  local io rb var lines
  io="$(count_io "$vm")"; rb="$(count_runblocking "$vm")"
  var="$(count_decision_variants "$vm")"; lines="$(count_lines "$vm")"

  # Downward-only: if there is an existing baseline, refuse to raise any cap.
  if [[ -f "$file" ]]; then
    load_baseline "$file" || return $?
    local raised=0
    (( io   > BL_IO ))    && { printf 'REFUSE: io_count %d > baseline %d — downward-only ratchet.\n' "$io" "$BL_IO" >&2; raised=1; }
    (( rb   > BL_RB ))    && { printf 'REFUSE: runblocking_count %d > baseline %d — downward-only ratchet.\n' "$rb" "$BL_RB" >&2; raised=1; }
    (( var  > BL_VAR ))   && { printf 'REFUSE: connection_decision_variants %d > baseline %d — downward-only ratchet.\n' "$var" "$BL_VAR" >&2; raised=1; }
    (( lines > BL_LINES )) && { printf 'REFUSE: vm_line_count %d > baseline %d — downward-only ratchet.\n' "$lines" "$BL_LINES" >&2; raised=1; }
    if (( raised != 0 )); then
      printf '%s\n' "update refused: a current count EXCEEDS the baseline. Remove the IO/decision/lines first; the ratchet never raises." >&2
      return 1
    fi
  fi

  write_baseline "$file" "$io" "$rb" "$var" "$lines"
  printf 'connection-vm-ratchet: baseline re-baked (downward-only) -> %s\n' "$file"
  printf '  io_count=%d runblocking_count=%d connection_decision_variants=%d vm_line_count=%d\n' \
    "$io" "$rb" "$var" "$lines"
  return 0
}

# --- self-test: synthetic red->green proof on fixtures ------------------------

run_self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  # A synthetic "at baseline" VM fixture with known counts:
  #   Dispatchers.IO x2, runBlocking x1, ConnectionDecision variants x2, 12 lines.
  local at_base="$tmp/AtBaseline.kt"
  cat > "$at_base" <<'EOF'
class Fixture {
    val a = Dispatchers.IO
    val b = Dispatchers.IO
    fun c() = runBlocking { 1 }
    sealed interface ConnectionDecision {
        data object Ignore : ConnectionDecision
        data object Hold : ConnectionDecision
    }
    fun pad1() {}
    fun pad2() {}
    fun pad3() {}
}
EOF
  local b_io b_rb b_var b_lines
  b_io="$(count_io "$at_base")"
  b_rb="$(count_runblocking "$at_base")"
  b_var="$(count_decision_variants "$at_base")"
  b_lines="$(count_lines "$at_base")"

  # An "over baseline" fixture: one extra Dispatchers.IO, one extra runBlocking,
  # one extra ConnectionDecision variant, and more lines than the baseline.
  local over="$tmp/OverBaseline.kt"
  cat > "$over" <<'EOF'
class Fixture {
    val a = Dispatchers.IO
    val b = Dispatchers.IO
    val extra = Dispatchers.IO
    fun c() = runBlocking { 1 }
    fun d() = runBlocking { 2 }
    sealed interface ConnectionDecision {
        data object Ignore : ConnectionDecision
        data object Hold : ConnectionDecision
        data object NewlyAdded : ConnectionDecision
    }
    fun pad1() {}
    fun pad2() {}
    fun pad3() {}
    fun pad4() {}
}
EOF

  local failures=0

  printf '== self-test: at-baseline fixture (expect PASS) ==\n'
  if check_against "$at_base" "$b_io" "$b_rb" "$b_var" "$b_lines"; then
    printf '   -> PASS as expected\n\n'
  else
    printf '   -> UNEXPECTED FAIL at baseline\n\n' >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: over-baseline fixture (expect FAIL) ==\n'
  if check_against "$over" "$b_io" "$b_rb" "$b_var" "$b_lines"; then
    printf '   -> UNEXPECTED PASS over baseline\n\n' >&2
    failures=$((failures + 1))
  else
    printf '   -> FAIL as expected\n\n'
  fi

  # Per-guard isolation: each of G-A(IO), G-A(runBlocking), G-B, G-C must be the
  # SOLE trigger when only its own dimension exceeds. Catches a guard wired to the
  # wrong counter.
  printf '== self-test: each guard fires in isolation (expect FAIL on each) ==\n'
  # Only IO over (others at baseline).
  if check_against "$at_base" "$((b_io - 1))" "$b_rb" "$b_var" "$b_lines"; then
    printf '   -> UNEXPECTED PASS: G-A IO did not fire\n' >&2; failures=$((failures + 1))
  else printf '   -> G-A(IO) fired as expected\n'; fi
  # Only runBlocking over.
  if check_against "$at_base" "$b_io" "$((b_rb - 1))" "$b_var" "$b_lines"; then
    printf '   -> UNEXPECTED PASS: G-A runBlocking did not fire\n' >&2; failures=$((failures + 1))
  else printf '   -> G-A(runBlocking) fired as expected\n'; fi
  # Only variants over.
  if check_against "$at_base" "$b_io" "$b_rb" "$((b_var - 1))" "$b_lines"; then
    printf '   -> UNEXPECTED PASS: G-B did not fire\n' >&2; failures=$((failures + 1))
  else printf '   -> G-B fired as expected\n'; fi
  # Only lines over.
  if check_against "$at_base" "$b_io" "$b_rb" "$b_var" "$((b_lines - 1))"; then
    printf '   -> UNEXPECTED PASS: G-C did not fire\n' >&2; failures=$((failures + 1))
  else printf '   -> G-C fired as expected\n'; fi
  printf '\n'

  if (( failures != 0 )); then
    printf 'SELF-TEST FAILED: %d case(s) behaved incorrectly.\n' "$failures" >&2
    return 1
  fi
  printf 'SELF-TEST OK: at-baseline passes, over-baseline fails, each guard fires in isolation.\n'
  return 0
}

main() {
  case "${1:-}" in
    -h|--help)
      usage
      exit 0
      ;;
    --self-test)
      run_self_test
      exit $?
      ;;
    --update)
      run_update
      exit $?
      ;;
    "")
      load_baseline "$REPO_ROOT/$BASELINE_REL" || exit $?
      check_against "$REPO_ROOT/$VM_REL" "$BL_IO" "$BL_RB" "$BL_VAR" "$BL_LINES"
      exit $?
      ;;
    *)
      printf 'FAIL: unknown arg: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac
}

main "$@"
