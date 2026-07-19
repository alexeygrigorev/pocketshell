#!/usr/bin/env bash
set -euo pipefail

# Issue #1685: per-PR dex register-pressure ratchet for the TmuxSessionScreen
# composable family.
#
# ## Why this guard exists
# `TmuxSessionScreen` is the app's MAIN screen and a single enormous composable.
# When its compiled dex method frame crosses ART's WIDE-REGISTER cliff (a method
# that needs registers v256+ forces `move-object/from16` moves whose verifier
# register-type merge ART rejects), the class fails verification at load and the
# session screen crashes on-device with `java.lang.VerifyError` — invisible to the
# JVM unit tests. This has now recurred THREE times (#1158 v273, #1362 v300, #1685
# v304): each prior fix hoisted just enough code to duck back UNDER 256 and left
# the method sitting AT the cliff, so the next +1-register change (an added
# argument, an inline derivation) silently tipped it over and shipped as a phone
# crash.
#
# The existing on-device proof (TmuxSessionScreenArtVerifyE2eTest) only runs in the
# batched post-merge emulator lane, which is exactly why recurrence #3 reached the
# release gate. THIS guard runs in the cheap per-PR Unit lane: it dexes the debug
# APK and asserts every method of the TmuxSessionScreen* classes stays comfortably
# under a budget (200) that keeps a hard margin below the 256 verifier cliff. A
# creep back toward the cliff becomes a per-PR RED here instead of an on-device
# crash later.
#
# ## What it checks
#   * every method of every dex class whose descriptor starts with
#     `Lcom/pocketshell/app/tmux/TmuxSessionScreen` (TmuxSessionScreenKt, its
#     extracted state-helper / region siblings, and the synthetic `$lambda$N`
#     methods Kotlin/Compose emits into those file classes) — G2 class coverage,
#     not just the one reported method.
#   * `registers_size` (the method frame width) of each such method is < BUDGET.
#
# Downward-only ratchet spirit: the budget is a fixed cliff-margin, not a per-file
# baseline that grows. Getting further under it is always welcome; crossing it is
# a hard fail. The remedy is ALWAYS to reduce register pressure (extract a
# cohesive sub-composable / state-holder into its own frame — see
# TmuxSessionScreenStateHelpers.kt), NEVER to raise the budget toward 256.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"

# The wide-register verifier cliff is 256. The budget keeps a hard margin below
# it so a small future change cannot silently cross the cliff between the per-PR
# ratchet and the batched on-device proof.
BUDGET="${DEX_REGISTER_BUDGET:-200}"
# Which dex classes to gate. A descriptor-PREFIX match, so every TmuxSessionScreen*
# file class (and its synthetic lambda methods) is covered.
CLASS_PREFIX="${DEX_REGISTER_CLASS_PREFIX:-Lcom/pocketshell/app/tmux/TmuxSessionScreen}"
DEFAULT_APK="$REPO_ROOT/app/build/outputs/apk/debug/app-debug.apk"

usage() {
  cat <<'USAGE'
Usage: scripts/check-dex-register-pressure.sh [--apk PATH] [--build] [--self-test]

Dex register-pressure ratchet for the TmuxSessionScreen composable family.

  (no args)     dexdump the debug APK and fail if any TmuxSessionScreen* method's
                register frame >= the budget (default 200; the verifier cliff is 256).
  --apk PATH    use this APK instead of app/build/outputs/apk/debug/app-debug.apk.
  --build       run `./gradlew :app:assembleDebug` first (otherwise the APK must
                already exist).
  --self-test   run an embedded red->green proof of the dexdump PARSER + budget
                logic (no Gradle, no Android SDK, no APK).

Environment:
  DEX_REGISTER_BUDGET         override the 200-register budget.
  DEX_REGISTER_CLASS_PREFIX   override the gated dex class descriptor prefix.
  DEXDUMP                     path to a dexdump binary (else auto-located).
USAGE
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit "${2:-1}"
}

locate_dexdump() {
  if [[ -n "${DEXDUMP:-}" ]]; then
    [[ -x "$DEXDUMP" ]] || fail "DEXDUMP=$DEXDUMP is not executable"
    printf '%s\n' "$DEXDUMP"
    return 0
  fi
  if command -v dexdump >/dev/null 2>&1; then
    command -v dexdump
    return 0
  fi
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [[ -n "$sdk" && -d "$sdk/build-tools" ]]; then
    # Prefer the highest build-tools version that ships a dexdump.
    local found
    found="$(find "$sdk/build-tools" -maxdepth 2 -name dexdump -type f 2>/dev/null | sort -V | tail -n1)"
    if [[ -n "$found" && -x "$found" ]]; then
      printf '%s\n' "$found"
      return 0
    fi
  fi
  fail "could not locate dexdump (set DEXDUMP, or install Android build-tools and set ANDROID_HOME)"
}

# Parse a dexdump `-f` text stream on stdin and emit "<registers>\t<method>" for
# every method of a class whose descriptor starts with $CLASS_PREFIX. Kept as a
# standalone function so --self-test can drive it with fixture text.
parse_registers() {
  local prefix="$1"
  awk -v prefix="$prefix" '
    /^  Class descriptor  :/ {
      cls = $0
      sub(/.*: /, "", cls)
      gsub(/'\''/, "", cls)
      inclass = (index(cls, prefix) == 1)
      next
    }
    inclass && /name +:/ {
      m = $0
      sub(/.*: /, "", m)
      gsub(/'\''/, "", m)
      curname = m
      next
    }
    inclass && /registers +:/ {
      r = $0
      sub(/.*: /, "", r)
      gsub(/[^0-9]/, "", r)
      if (r != "") print r "\t" curname
      next
    }
  '
}

check_apk() {
  local apk="$1"
  [[ -f "$apk" ]] || fail "APK not found: $apk (build it with --build or ./gradlew :app:assembleDebug)"

  local dexdump
  dexdump="$(locate_dexdump)"

  local work
  work="$(mktemp -d)"
  trap 'rm -rf "${work:-}"' RETURN

  ( cd "$work" && unzip -oq "$apk" 'classes*.dex' ) \
    || fail "no classes*.dex in $apk"

  local dumped="$work/register-report.txt"
  : > "$dumped"
  # The gated dex class' simple name, matched against the raw .dex string table so
  # we skip dexdump'ing dexes that cannot contain it. Grep the FILE directly (not a
  # `dexdump | grep -q` pipe: with `pipefail`, grep -q closing the pipe early gives
  # dexdump a SIGPIPE and the pipeline exits non-zero, wrongly hiding the match).
  local simple_name
  simple_name="$(basename "$CLASS_PREFIX")"
  local dex
  for dex in "$work"/classes*.dex; do
    [[ -f "$dex" ]] || continue
    grep -qa "$simple_name" "$dex" || continue
    # awk reads all input, so dexdump completes normally (no SIGPIPE).
    "$dexdump" -f "$dex" 2>/dev/null | parse_registers "$CLASS_PREFIX" >> "$dumped"
  done

  local method_count
  method_count="$(wc -l < "$dumped" | tr -d ' ')"
  if [[ "$method_count" -eq 0 ]]; then
    # A ZERO-method result is a broken run, NOT a pass (G3 anti-vacuous). The
    # gated class must exist in a real debug APK.
    fail "found ZERO methods for classes matching '${CLASS_PREFIX}' in $apk — dex parse produced no data (broken run, not a pass)"
  fi

  printf '== dex-register-pressure (budget %s / cliff 256) ==\n' "$BUDGET"
  printf 'gated classes: %s*  (%s method(s) inspected)\n' "$CLASS_PREFIX" "$method_count"

  # Sort to a file (no `sort | head` pipe: head closing early SIGPIPEs sort,
  # which `pipefail` would surface as a spurious failure).
  local sorted="$work/register-report.sorted"
  sort -rn "$dumped" > "$sorted"
  local worst_line worst_r worst_m
  IFS= read -r worst_line < "$sorted" || worst_line=""
  worst_r="${worst_line%%$'\t'*}"
  worst_m="${worst_line#*$'\t'}"
  printf 'widest method: %s registers  (%s)\n' "$worst_r" "$worst_m"

  local over=0 r m
  while IFS=$'\t' read -r r m; do
    [[ -z "${r:-}" ]] && continue
    if (( r >= BUDGET )); then
      printf 'OVER-BUDGET %s: %s registers >= budget %s (cliff 256)\n' "$m" "$r" "$BUDGET" >&2
      over=$((over + 1))
    fi
  done < "$dumped"

  echo
  if (( over > 0 )); then
    printf 'check-dex-register-pressure: FAIL — %d method(s) at/over the %s-register budget.\n' "$over" "$BUDGET" >&2
    printf '  A TmuxSessionScreen* method is drifting toward the 256 ART wide-register\n' >&2
    printf '  verifier cliff (issue #1685 / #1362 / #1158). Extract a cohesive\n' >&2
    printf '  sub-composable or state-holder into its OWN frame to shed register\n' >&2
    printf '  pressure (see TmuxSessionScreenStateHelpers.kt). Do NOT raise the budget.\n' >&2
    return 1
  fi
  printf 'check-dex-register-pressure: OK — every TmuxSessionScreen* method is under the %s-register budget.\n' "$BUDGET"
}

run_self_test() {
  # Drive the parser + budget logic with synthetic dexdump text — no Gradle, no
  # Android SDK, no APK — proving it goes RED over-budget and GREEN under-budget.
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "${tmp:-}"' RETURN

  local red="$tmp/red.txt" green="$tmp/green.txt"
  # RED fixture: a gated class with one method at 305 registers (the #1685 shape),
  # plus an UNRELATED class at 400 that must be IGNORED (prefix scoping).
  cat > "$red" <<'DUMP'
  Class descriptor  : 'Lcom/pocketshell/app/tmux/TmuxSessionScreenKt;'
  Direct methods    -
    #0              : (in Lcom/pocketshell/app/tmux/TmuxSessionScreenKt;)
      name          : 'TmuxSessionScreen'
      type          : '(...)V'
      code          -
      registers     : 305
      ins           : 49
    #1              : (in Lcom/pocketshell/app/tmux/TmuxSessionScreenKt;)
      name          : 'TmuxSessionScreen$lambda$343'
      code          -
      registers     : 102
  Class descriptor  : 'Lcom/pocketshell/app/other/UnrelatedHugeKt;'
  Direct methods    -
    #0              : (in Lcom/pocketshell/app/other/UnrelatedHugeKt;)
      name          : 'unrelated'
      code          -
      registers     : 400
DUMP
  # GREEN fixture: same gated class, mega-method now extracted down to 188.
  cat > "$green" <<'DUMP'
  Class descriptor  : 'Lcom/pocketshell/app/tmux/TmuxSessionScreenKt;'
  Direct methods    -
    #0              : (in Lcom/pocketshell/app/tmux/TmuxSessionScreenKt;)
      name          : 'TmuxSessionScreen'
      code          -
      registers     : 188
  Class descriptor  : 'Lcom/pocketshell/app/tmux/TmuxSessionScreenStateHelpersKt;'
  Direct methods    -
    #0              : (in Lcom/pocketshell/app/tmux/TmuxSessionScreenStateHelpersKt;)
      name          : 'rememberTmuxSessionSurfaceRuntime'
      code          -
      registers     : 74
DUMP

  local failures=0
  local prefix="Lcom/pocketshell/app/tmux/TmuxSessionScreen"

  printf '== self-test: prefix scoping ignores unrelated classes ==\n'
  if parse_registers "$prefix" < "$red" | grep -q 'unrelated'; then
    printf '   -> UNEXPECTED: an unrelated class leaked past the prefix filter\n\n' >&2
    failures=$((failures + 1))
  else
    printf '   -> PASS: only gated classes parsed\n\n'
  fi

  printf '== self-test: RED fixture (305-register method) trips the budget ==\n'
  local worst
  worst="$(parse_registers "$prefix" < "$red" | sort -rn | head -n1 | cut -f1)"
  if [[ "$worst" == "305" ]]; then
    printf '   -> PASS: widest gated method read as 305\n\n'
  else
    printf '   -> UNEXPECTED widest=%s (expected 305)\n\n' "$worst" >&2
    failures=$((failures + 1))
  fi
  local over_red=0
  while IFS=$'\t' read -r r _; do (( r >= 200 )) && over_red=$((over_red + 1)); done \
    < <(parse_registers "$prefix" < "$red")
  if (( over_red >= 1 )); then
    printf '   -> PASS: RED fixture reports %d over-budget method(s)\n\n' "$over_red"
  else
    printf '   -> UNEXPECTED: RED fixture reported NO over-budget method\n\n' >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: GREEN fixture (188/74) passes the budget ==\n'
  local over_green=0
  while IFS=$'\t' read -r r _; do (( r >= 200 )) && over_green=$((over_green + 1)); done \
    < <(parse_registers "$prefix" < "$green")
  if (( over_green == 0 )); then
    printf '   -> PASS: GREEN fixture reports 0 over-budget methods\n\n'
  else
    printf '   -> UNEXPECTED: GREEN fixture reported %d over-budget method(s)\n\n' "$over_green" >&2
    failures=$((failures + 1))
  fi

  if (( failures > 0 )); then
    fail "SELF-TEST FAILED: $failures case(s) behaved incorrectly"
  fi
  printf 'SELF-TEST OK: register parser is prefix-scoped and the budget catches the wide-register creep.\n'
}

main() {
  local apk="$DEFAULT_APK"
  local do_build=0
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -h|--help) usage; exit 0 ;;
      --self-test) run_self_test; exit 0 ;;
      --build) do_build=1; shift ;;
      --apk) apk="${2:?--apk needs a path}"; shift 2 ;;
      *) usage >&2; fail "unknown arg: $1" 2 ;;
    esac
  done

  if (( do_build )); then
    ( cd "$REPO_ROOT" && ./gradlew :app:assembleDebug )
  fi
  check_apk "$apk"
}

main "$@"
