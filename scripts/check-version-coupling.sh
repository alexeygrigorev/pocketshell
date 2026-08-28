#!/usr/bin/env bash
set -uo pipefail

# PR-time version-coupling guard for issue #948, RESTRUCTURED for #2356
# (Phase 4 of epic #2350).
#
# BEFORE #2356: app/build.gradle.kts's `versionName` and
# tools/pocketshell/pyproject.toml's `version` were two hand-maintained
# string literals, and this guard compared them for equality — catching the
# 2026-06-25 drift (app 0.4.16 vs package 0.4.14) that nagged the maintainer
# with the in-app host-version-mismatch banner.
#
# AFTER #2356: neither side is a hand-maintained literal any more.
# app/build.gradle.kts derives versionCode/versionName at CONFIGURATION TIME
# from scripts/derive-version.sh (the git tag being built). The
# tools/pocketshell PyPI version is STAMPED from the same script into
# pyproject.toml immediately before `uv build`/`python -m build`
# in .github/workflows/build.yml's publish-pypi job — it is never a committed
# literal that can independently drift going forward.
#
# So there is no longer "two hand-maintained numbers" to compare. What CAN
# still drift is the WIRING: a bug in app/build.gradle.kts's exec/parsing
# code, or a future edit to the PyPI stamp step, silently reimplementing the
# derivation instead of calling scripts/derive-version.sh. This guard now
# checks exactly that "single source of truth" property, structurally:
#
#   1. scripts/derive-version.sh's own --self-test passes (the derivation
#      logic itself is sound: version-code is monotonic across synthetic
#      tags, version-name matches the documented convention, and both fall
#      back cleanly with no tags / no git).
#   2. app/build.gradle.kts's Gradle-resolved versionCode/versionName
#      (via `:app:printPocketshellVersion`) EXACTLY matches a direct
#      invocation of `scripts/derive-version.sh both` run against the same
#      ref — proving the Gradle exec wiring has not drifted from the script
#      it calls.
#   3. Every consumer that needs a version (app/build.gradle.kts, and the
#      PyPI publish step in .github/workflows/build.yml) references
#      scripts/derive-version.sh BY PATH, rather than embedding its own
#      independent `git describe`/`git tag` logic — the property "not two
#      independently-written implementations that could drift" the #2356
#      issue asked this guard to enforce.
#
# Usage:
#   scripts/check-version-coupling.sh              # check the real tree
#   scripts/check-version-coupling.sh --skip-gradle # skip step 2 (no JDK/SDK
#                                                    # available, e.g. a
#                                                    # lightweight Python-only
#                                                    # CI job); steps 1 and 3
#                                                    # still run.
#   scripts/check-version-coupling.sh --self-test
#                                            # run an in-process red->green
#                                            # proof on synthetic fixtures and
#                                            # exit 0 only if both pass

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

DERIVE_SCRIPT_REL="scripts/derive-version.sh"
GRADLE_REL="app/build.gradle.kts"
BUILD_WORKFLOW_REL=".github/workflows/build.yml"

usage() {
  cat <<'USAGE'
Usage: scripts/check-version-coupling.sh [--skip-gradle] [--self-test]

Verifies scripts/derive-version.sh is the SOLE source of truth for both the
Android versionCode/versionName and the tools/pocketshell PyPI version
(issue #2356): its own self-test passes, app/build.gradle.kts's Gradle-
resolved version matches a direct script invocation, and both
app/build.gradle.kts and .github/workflows/build.yml reference the script by
path rather than reimplementing the git derivation independently.

--skip-gradle skips the Gradle-resolved-version cross-check (step 2), for a
context with no JDK/Android SDK available. Steps 1 and 3 still run.

--self-test runs a synthetic red->green proof on fixture trees and exits 0
only if the guard behaves correctly on both a wired-correctly tree and a
drifted (independently-reimplemented) tree.
USAGE
}

fail() {
  printf 'FAIL: %s\n' "$1" >&2
}

# Step 1: the derivation script's own self-test.
check_derive_self_test() {
  local script="$1"
  if [[ ! -x "$script" && ! -f "$script" ]]; then
    fail "missing $script"
    return 1
  fi
  if ! bash "$script" --self-test; then
    fail "scripts/derive-version.sh --self-test failed"
    return 1
  fi
  return 0
}

# Step 2: Gradle's resolved version must match a direct script invocation.
check_gradle_matches_script() {
  local repo_root="$1" script="$2"
  local direct
  direct="$(bash "$script" both 2>/dev/null)" || {
    fail "direct 'derive-version.sh both' invocation failed"
    return 1
  }

  if [[ ! -x "$repo_root/gradlew" ]]; then
    fail "gradlew not found/executable at $repo_root/gradlew"
    return 1
  fi

  local gradle_out
  if ! gradle_out="$(cd "$repo_root" && ./gradlew -q :app:printPocketshellVersion 2>&1)"; then
    fail "':app:printPocketshellVersion' failed:"
    printf '%s\n' "$gradle_out" >&2
    return 1
  fi

  local gradle_lines
  gradle_lines="$(printf '%s\n' "$gradle_out" | grep -E '^VERSION_(CODE|NAME)=')"

  if [[ "$gradle_lines" != "$direct" ]]; then
    fail "Gradle-resolved version does not match scripts/derive-version.sh directly:"
    printf '  Gradle:  %s\n' "$(printf '%s' "$gradle_lines" | tr '\n' ' ')" >&2
    printf '  Script:  %s\n' "$(printf '%s' "$direct" | tr '\n' ' ')" >&2
    return 1
  fi

  printf 'OK: Gradle-resolved version matches scripts/derive-version.sh: %s\n' \
    "$(printf '%s' "$direct" | tr '\n' ' ')"
  return 0
}

# Step 3: every consumer references the script by path (grep-based single-
# source-of-truth check — catches an independently-reimplemented derivation).
check_single_source_reference() {
  local gradle_file="$1" build_workflow_file="$2"

  local ok=1

  if [[ ! -f "$gradle_file" ]]; then
    fail "missing $gradle_file"
    ok=0
  elif ! grep -Fq "scripts/derive-version.sh" "$gradle_file"; then
    fail "$gradle_file does not reference scripts/derive-version.sh — it may have grown an independent derivation"
    ok=0
  fi

  if [[ ! -f "$build_workflow_file" ]]; then
    fail "missing $build_workflow_file"
    ok=0
  elif ! grep -Fq "derive-version.sh" "$build_workflow_file"; then
    fail "$build_workflow_file does not reference scripts/derive-version.sh — the PyPI publish step may have grown an independent derivation"
    ok=0
  fi

  [[ "$ok" -eq 1 ]] || return 1
  printf 'OK: app/build.gradle.kts and the Build workflow both reference scripts/derive-version.sh (single source of truth).\n'
  return 0
}

run_self_test() {
  local tmp
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN

  local failures=0

  # --- Fixture pair for step 3 (grep-based reference check) ---
  mkdir -p "$tmp/wired/app" "$tmp/wired/.github/workflows"
  printf 'versionCode = derived.code // scripts/derive-version.sh\n' \
    > "$tmp/wired/app/build.gradle.kts"
  printf 'run: scripts/derive-version.sh both\n' \
    > "$tmp/wired/.github/workflows/build.yml"

  mkdir -p "$tmp/drifted/app" "$tmp/drifted/.github/workflows"
  printf 'versionCode = 92 // hand-maintained, no derivation\n' \
    > "$tmp/drifted/app/build.gradle.kts"
  printf 'run: echo "0.4.45" > pyproject.toml\n' \
    > "$tmp/drifted/.github/workflows/build.yml"

  printf '== self-test: single-source reference, wired tree (expect PASS) ==\n'
  if check_single_source_reference \
    "$tmp/wired/app/build.gradle.kts" "$tmp/wired/.github/workflows/build.yml" >/dev/null; then
    printf '   -> PASS as expected\n\n'
  else
    printf '   -> UNEXPECTED FAIL on wired tree\n\n' >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: single-source reference, drifted tree (expect FAIL) ==\n'
  if check_single_source_reference \
    "$tmp/drifted/app/build.gradle.kts" "$tmp/drifted/.github/workflows/build.yml" >/dev/null 2>&1; then
    printf '   -> UNEXPECTED PASS on drifted tree\n\n' >&2
    failures=$((failures + 1))
  else
    printf '   -> FAIL as expected\n\n'
  fi

  # --- derive-version.sh's own self-test (delegated) ---
  printf '== self-test: derive-version.sh --self-test (expect PASS) ==\n'
  if check_derive_self_test "$ROOT_DIR/$DERIVE_SCRIPT_REL" >/dev/null; then
    printf '   -> PASS as expected\n\n'
  else
    printf '   -> UNEXPECTED FAIL: scripts/derive-version.sh --self-test itself is red\n\n' >&2
    failures=$((failures + 1))
  fi

  if [[ "$failures" -ne 0 ]]; then
    printf 'SELF-TEST FAILED: %d case(s) behaved incorrectly.\n' "$failures" >&2
    return 1
  fi
  printf 'SELF-TEST OK: single-source reference check and the delegated derive-version.sh self-test both behave correctly.\n'
  return 0
}

main() {
  local skip_gradle=0
  case "${1:-}" in
    -h|--help)
      usage
      exit 0
      ;;
    --self-test)
      run_self_test
      exit $?
      ;;
    --skip-gradle)
      skip_gradle=1
      ;;
    "")
      ;;
    *)
      printf 'FAIL: unknown arg: %s\n' "$1" >&2
      usage >&2
      exit 2
      ;;
  esac

  local ok=1

  check_derive_self_test "$ROOT_DIR/$DERIVE_SCRIPT_REL" || ok=0

  if [[ "$skip_gradle" -eq 0 ]]; then
    check_gradle_matches_script "$ROOT_DIR" "$ROOT_DIR/$DERIVE_SCRIPT_REL" || ok=0
  else
    printf 'SKIPPED: Gradle-resolved-version cross-check (--skip-gradle)\n'
  fi

  check_single_source_reference \
    "$ROOT_DIR/$GRADLE_REL" "$ROOT_DIR/$BUILD_WORKFLOW_REL" || ok=0

  if [[ "$ok" -ne 1 ]]; then
    printf 'FAIL: version-coupling guard failed one or more checks above.\n' >&2
    exit 1
  fi
  printf 'OK: version derivation is structurally single-sourced (issue #2356).\n'
  exit 0
}

main "$@"
