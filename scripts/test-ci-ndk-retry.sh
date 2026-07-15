#!/usr/bin/env bash
# Issue #1581: fast, JVM-free shell test for the corrupted-NDK-download retry
# wrapper (scripts/ci-assemble-with-ndk-retry.sh).
#
# The wrapper's job is to survive a single corrupted-NDK-download attempt
# ("Error on ZipFile unknown archive") WITHOUT a manual re-run, by CLEARING the
# partial download between attempts so the retry fetches a fresh archive.
#
# This test proves — deterministically, with NO Gradle, NO Android SDK, NO
# network — that:
#   (T1) clear_partial_ndk_download removes exactly the configured partial paths;
#   (T2) attempt-1 failure -> clear -> attempt-2 success returns 0 (self-healed);
#   (T3) the CLEAR is load-bearing, not just the retry: with the clear stubbed
#        to a no-op, the SAME simulated flake fails on every attempt (proving a
#        retry-without-clear would not fix the corrupted-archive bug — the exact
#        failure mode the issue calls out);
#   (T4) a NON-NDK failure (a real compile error) is NOT retried — it fails
#        immediately after one attempt, so the wrapper can never mask a real
#        regression.
#
# Wired into the Unit job of .github/workflows/tests.yml (G9: a test per
# acceptance criterion).
#
# Each test runs the sourced wrapper in an isolated ( ... ) subshell so its
# functions/env do not leak between cases; the env exports are intentionally
# subshell-local (SC2030/SC2031) and the no-op clear stub in T3 is invoked
# indirectly (SC2317).
# shellcheck disable=SC2030,SC2031,SC2317

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WRAPPER="$SCRIPT_DIR/ci-assemble-with-ndk-retry.sh"

fail() {
  echo "FAIL: $*" >&2
  exit 1
}

# ---------------------------------------------------------------------------
# T1: clear_partial_ndk_download removes exactly the configured partial paths.
# ---------------------------------------------------------------------------
test_clear_removes_partial_paths() {
  local tmp; tmp="$(mktemp -d)"
  mkdir -p "$tmp/ndk-extract" "$tmp/sdk-temp"
  echo "corrupt-archive-bytes" > "$tmp/ndk-extract/partial.zip"
  echo "in-progress" > "$tmp/sdk-temp/op.json"
  local keep="$tmp/keep-me"; echo "unrelated" > "$keep"

  (
    export NDK_PARTIAL_PATHS="$tmp/ndk-extract $tmp/sdk-temp"
    export BUILD_CMD="true"
    # shellcheck disable=SC1090
    source "$WRAPPER"
    clear_partial_ndk_download
  ) || fail "T1: clear_partial_ndk_download exited non-zero"

  [[ -e "$tmp/ndk-extract" ]] && fail "T1: partial ndk-extract not removed"
  [[ -e "$tmp/sdk-temp" ]] && fail "T1: partial sdk-temp not removed"
  [[ -e "$keep" ]] || fail "T1: unrelated file wrongly removed"
  rm -rf "$tmp"
  echo "PASS T1: clear_partial_ndk_download removes exactly the configured partial paths"
}

# A fake build command whose behaviour depends on whether a "corrupt partial
# download" marker file is present — modelling the real bug where the downloader
# reuses a cached corrupt archive:
#   - marker PRESENT  -> emit the corrupted-NDK signature and FAIL
#   - marker ABSENT   -> "compile" and SUCCEED
# The marker lives INSIDE the configured NDK_PARTIAL_PATHS dir, so the wrapper's
# clear step removes it and lets the next attempt succeed.
make_flaky_build_cmd() {
  local marker="$1"
  # Printed via the wrapper's `eval`; must be a single shell expression.
  cat <<EOF
if [ -e "$marker" ]; then echo "Error on ZipFile unknown archive"; exit 1; else echo "compiled ok"; exit 0; fi
EOF
}

# ---------------------------------------------------------------------------
# T2: attempt-1 failure -> clear -> attempt-2 success returns 0.
# ---------------------------------------------------------------------------
test_retry_clear_then_success() {
  local tmp; tmp="$(mktemp -d)"
  local partial="$tmp/ndk-partial"
  mkdir -p "$partial"
  local marker="$partial/corrupt.zip"
  echo "corrupt" > "$marker"     # pre-seed a leftover corrupt download
  local log="$tmp/build.log"

  local rc=0
  (
    export NDK_PARTIAL_PATHS="$partial"
    export NDK_RETRY_DELAY=0
    export NDK_RETRY_ATTEMPTS=3
    export BUILD_LOG="$log"
    export BUILD_CMD; BUILD_CMD="$(make_flaky_build_cmd "$marker")"
    # shellcheck disable=SC1090
    source "$WRAPPER"
    run_build_with_ndk_retry
  ) || rc=$?

  [[ "$rc" -eq 0 ]] || fail "T2: expected self-heal to succeed (rc=0), got rc=$rc"
  echo "PASS T2: attempt-1 corrupt-NDK failure -> clear -> attempt-2 success (self-healed)"
  rm -rf "$tmp"
}

# ---------------------------------------------------------------------------
# T3: the CLEAR is load-bearing. With clear stubbed to a no-op, the corrupt
# archive is reused on every attempt and the build fails -> proves a
# retry-WITHOUT-clear would NOT fix the flake (the issue's core point).
# ---------------------------------------------------------------------------
test_clear_is_load_bearing() {
  local tmp; tmp="$(mktemp -d)"
  local partial="$tmp/ndk-partial"
  mkdir -p "$partial"
  local marker="$partial/corrupt.zip"
  echo "corrupt" > "$marker"
  local log="$tmp/build.log"

  local rc=0
  (
    export NDK_PARTIAL_PATHS="$partial"
    export NDK_RETRY_DELAY=0
    export NDK_RETRY_ATTEMPTS=3
    export BUILD_LOG="$log"
    export BUILD_CMD; BUILD_CMD="$(make_flaky_build_cmd "$marker")"
    # shellcheck disable=SC1090
    source "$WRAPPER"
    # Defeat the clear so the corrupt archive persists across attempts.
    clear_partial_ndk_download() { echo "  (clear stubbed to no-op)"; }
    run_build_with_ndk_retry
  ) || rc=$?

  [[ "$rc" -ne 0 ]] || fail "T3: build unexpectedly succeeded WITHOUT clearing the corrupt archive; the clear is not actually load-bearing"
  [[ -e "$marker" ]] || fail "T3: corrupt marker gone despite no-op clear (test wiring bug)"
  echo "PASS T3: with clear stubbed no-op, the corrupt archive persists and the build fails -> clear is load-bearing (retry alone is not enough)"
  rm -rf "$tmp"
}

# ---------------------------------------------------------------------------
# T4: a NON-NDK failure is not retried — fails immediately (never masks a real
# regression), and only ONE attempt runs.
# ---------------------------------------------------------------------------
test_non_ndk_failure_not_retried() {
  local tmp; tmp="$(mktemp -d)"
  local log="$tmp/build.log"
  local counter="$tmp/attempts"
  echo 0 > "$counter"

  local rc=0
  (
    export NDK_PARTIAL_PATHS="$tmp/none"
    export NDK_RETRY_DELAY=0
    export NDK_RETRY_ATTEMPTS=3
    export BUILD_LOG="$log"
    # A real compile error: fails with a DIFFERENT message every attempt and
    # bumps the attempt counter so we can prove it ran only once.
    export BUILD_CMD="n=\$(cat '$counter'); echo \$((n+1)) > '$counter'; echo 'e: Unresolved reference: foo'; exit 1"
    # shellcheck disable=SC1090
    source "$WRAPPER"
    run_build_with_ndk_retry
  ) || rc=$?

  [[ "$rc" -ne 0 ]] || fail "T4: non-NDK failure unexpectedly returned success"
  local attempts; attempts="$(cat "$counter")"
  [[ "$attempts" -eq 1 ]] || fail "T4: expected exactly 1 attempt for a non-NDK failure, got $attempts (a real compile error must not be retried)"
  echo "PASS T4: non-NDK failure fails immediately after 1 attempt (real regressions are never masked)"
  rm -rf "$tmp"
}

test_clear_removes_partial_paths
test_retry_clear_then_success
test_clear_is_load_bearing
test_non_ndk_failure_not_retried

echo "ALL PASS: scripts/test-ci-ndk-retry.sh (issue #1581)"
