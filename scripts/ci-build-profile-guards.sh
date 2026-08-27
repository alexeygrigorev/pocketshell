#!/usr/bin/env bash
# Build-profile guards for the `Static guards` job of .github/workflows/tests.yml.
#
# Issue #1581: gate the Build workflow's corrupted-NDK-download retry wrapper
# (scripts/ci-assemble-with-ndk-retry.sh) so its clear-and-retry logic cannot
# silently regress. Fast, JVM-free shell test (fake build cmd, temp dirs, no
# Gradle/SDK/network) proving: attempt-1 corrupted-NDK failure -> CLEAR partial
# download -> attempt-2 success (self-healed); that the clear is load-bearing
# (retry alone, with the clear stubbed off, still fails); and that a non-NDK
# failure is NOT retried (a real regression is never masked). Cheap (< 5 s), no
# emulator.
#
# The local APK assemble profile must stay on the FAST profile (daemon + cache +
# multi-worker) and must not silently pick up the release-gate
# --no-daemon/--no-build-cache/--max-workers=1 flags. Cheap, no Gradle.
#
# These two guards live in one script (rather than inline `run:` blocks) because
# tests.yml is held under the 128 KiB file-size hygiene threshold with 1 KiB of
# required headroom; see scripts/check-file-size-hygiene.sh.
set -euo pipefail

cd "$(dirname "$0")/.."

chmod +x scripts/ci-assemble-with-ndk-retry.sh scripts/test-ci-ndk-retry.sh \
  scripts/assemble-debug.sh scripts/test-assemble-debug.sh

scripts/test-ci-ndk-retry.sh
scripts/test-assemble-debug.sh
