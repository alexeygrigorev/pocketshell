#!/usr/bin/env bash
# Issue #2515: the tag-triggered Build workflow must ship the app2 APK.
#
# v0.5.0's Build (run 33955651890) assembled successfully, then "Rename APK"
# died on `app/build/outputs/apk/debug/app-debug.apk` — the deleted `app`
# module. The shipping APK is `app2/build/outputs/apk/debug/app2-debug.apk`.
# This guard fails closed if the old path or `:app:assembleDebug` returns, and
# requires the app2 path plus the `pocketshell-${VERSION}-debug.apk` filename.
#
# Cheap, JVM-free. Wired through scripts/ci-build-profile-guards.sh (the
# tests.yml `guards-ci-harness` job).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
DEFAULT_WORKFLOW="$ROOT_DIR/.github/workflows/build.yml"

OLD_APK="app/build/outputs/apk/debug/app-debug.apk"
OLD_DIR="app/build/outputs/apk/debug/"
OLD_TASK=":app:assembleDebug"
NEW_APK="app2/build/outputs/apk/debug/app2-debug.apk"
NEW_GLOB="app2/build/outputs/apk/debug/*.apk"
RENAME_NAME='pocketshell-${VERSION}-debug.apk'

fail() {
  printf 'FAIL: %s\n' "$1" >&2
}

check_workflow() {
  local wf="$1"
  local failures=0

  if [[ ! -f "$wf" ]]; then
    fail "workflow not found: $wf"
    return 1
  fi

  if grep -Fq "$OLD_APK" "$wf"; then
    fail "$wf still names deleted $OLD_APK (issue #2515)"
    failures=$((failures + 1))
  fi
  if grep -Fq "$OLD_TASK" "$wf"; then
    fail "$wf still names $OLD_TASK (issue #2515)"
    failures=$((failures + 1))
  fi
  if grep -Fq "$OLD_DIR" "$wf"; then
    fail "$wf still names deleted $OLD_DIR (rename/upload/release must not use the app module)"
    failures=$((failures + 1))
  fi
  if ! grep -Fq "$NEW_APK" "$wf"; then
    fail "$wf must name $NEW_APK as the assembled APK"
    failures=$((failures + 1))
  fi
  if ! grep -Fq "$NEW_GLOB" "$wf"; then
    fail "$wf upload-artifact / action-gh-release must glob $NEW_GLOB"
    failures=$((failures + 1))
  fi
  if ! grep -Fq "$RENAME_NAME" "$wf"; then
    fail "$wf rename dest must stay $RENAME_NAME"
    failures=$((failures + 1))
  fi
  if grep -Eq '^[[:space:]]*draft:[[:space:]]*true[[:space:]]*$' "$wf"; then
    fail "$wf Create Release must not set draft: true"
    failures=$((failures + 1))
  fi

  if (( failures > 0 )); then
    return 1
  fi
  printf 'PASS: %s ships %s as %s\n' "$wf" "$NEW_APK" "$RENAME_NAME"
  return 0
}

write_fixture() {
  local dest="$1"
  cat > "$dest"
}

self_test() {
  local tmp red_out green_out task_out draft_out
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/build-workflow-apk-path.XXXXXX")"
  trap 'rm -rf "$tmp"' RETURN

  write_fixture "$tmp/green.yml" <<'YAML'
name: Build
jobs:
  build:
    steps:
      - name: Rename APK
        run: |
          VERSION="${{ steps.version.outputs.version }}"
          mv app2/build/outputs/apk/debug/app2-debug.apk "app2/build/outputs/apk/debug/pocketshell-${VERSION}-debug.apk"
      - name: Upload debug APK
        uses: actions/upload-artifact@v7
        with:
          path: app2/build/outputs/apk/debug/*.apk
      - name: Create Release
        uses: softprops/action-gh-release@v3
        with:
          files: |
            app2/build/outputs/apk/debug/*.apk
YAML

  write_fixture "$tmp/red.yml" <<'YAML'
name: Build
jobs:
  build:
    steps:
      - name: Rename APK
        run: |
          VERSION="${{ steps.version.outputs.version }}"
          mv app/build/outputs/apk/debug/app-debug.apk "app/build/outputs/apk/debug/pocketshell-${VERSION}-debug.apk"
      - name: Upload debug APK
        uses: actions/upload-artifact@v7
        with:
          path: app/build/outputs/apk/debug/*.apk
      - name: Create Release
        uses: softprops/action-gh-release@v3
        with:
          files: |
            app/build/outputs/apk/debug/*.apk
YAML

  write_fixture "$tmp/old-task.yml" <<'YAML'
name: Build
jobs:
  build:
    steps:
      - run: ./gradlew :app:assembleDebug --stacktrace
      - name: Rename APK
        run: mv app2/build/outputs/apk/debug/app2-debug.apk "app2/build/outputs/apk/debug/pocketshell-${VERSION}-debug.apk"
      - uses: actions/upload-artifact@v7
        with:
          path: app2/build/outputs/apk/debug/*.apk
YAML

  write_fixture "$tmp/draft.yml" <<'YAML'
name: Build
jobs:
  build:
    steps:
      - name: Rename APK
        run: mv app2/build/outputs/apk/debug/app2-debug.apk "app2/build/outputs/apk/debug/pocketshell-${VERSION}-debug.apk"
      - uses: actions/upload-artifact@v7
        with:
          path: app2/build/outputs/apk/debug/*.apk
      - name: Create Release
        uses: softprops/action-gh-release@v3
        with:
          draft: true
          files: |
            app2/build/outputs/apk/debug/*.apk
YAML

  local failures=0

  printf '== self-test: current (buggy) app-debug.apk paths (expect FAIL) ==\n'
  red_out="$tmp/red.out"
  if check_workflow "$tmp/red.yml" >"$red_out" 2>&1; then
    printf '   -> UNEXPECTED PASS on deleted app-debug.apk paths\n' >&2
    cat "$red_out" >&2
    failures=$((failures + 1))
  elif grep -Fq "$OLD_APK" "$red_out"; then
    printf '   -> FAIL as expected\n'
  else
    printf '   -> FAIL used the wrong diagnostic\n' >&2
    cat "$red_out" >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: app2 APK paths (expect PASS) ==\n'
  green_out="$tmp/green.out"
  if check_workflow "$tmp/green.yml" >"$green_out" 2>&1; then
    printf '   -> PASS as expected\n'
  else
    printf '   -> UNEXPECTED FAIL on app2 APK paths\n' >&2
    cat "$green_out" >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: :app:assembleDebug leftover (expect FAIL) ==\n'
  task_out="$tmp/old-task.out"
  if check_workflow "$tmp/old-task.yml" >"$task_out" 2>&1; then
    printf '   -> UNEXPECTED PASS on :app:assembleDebug\n' >&2
    cat "$task_out" >&2
    failures=$((failures + 1))
  elif grep -Fq "$OLD_TASK" "$task_out"; then
    printf '   -> FAIL as expected\n'
  else
    printf '   -> FAIL used the wrong diagnostic\n' >&2
    cat "$task_out" >&2
    failures=$((failures + 1))
  fi

  printf '== self-test: draft: true (expect FAIL) ==\n'
  draft_out="$tmp/draft.out"
  if check_workflow "$tmp/draft.yml" >"$draft_out" 2>&1; then
    printf '   -> UNEXPECTED PASS on draft: true\n' >&2
    cat "$draft_out" >&2
    failures=$((failures + 1))
  elif grep -Fq 'draft: true' "$draft_out"; then
    printf '   -> FAIL as expected\n'
  else
    printf '   -> FAIL used the wrong diagnostic\n' >&2
    cat "$draft_out" >&2
    failures=$((failures + 1))
  fi

  if (( failures > 0 )); then
    fail "scripts/test-build-workflow-apk-path.sh --self-test ($failures case(s))"
    return 1
  fi
  printf 'PASS: scripts/test-build-workflow-apk-path.sh --self-test\n'
}

case "${1:-}" in
  --self-test)
    self_test
    ;;
  -h|--help)
    cat <<'USAGE'
Usage: scripts/test-build-workflow-apk-path.sh [WORKFLOW]
       scripts/test-build-workflow-apk-path.sh --self-test

Fails if the Build workflow still names the deleted app module APK or
:app:assembleDebug, and requires app2-debug.apk plus pocketshell-${VERSION}-debug.apk.
USAGE
    ;;
  *)
    check_workflow "${1:-$DEFAULT_WORKFLOW}"
    ;;
esac
