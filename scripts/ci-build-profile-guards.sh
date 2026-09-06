#!/usr/bin/env bash
# Build-profile guards for the `Static guards` job of .github/workflows/tests.yml.
#
# The local APK assemble profile must stay on the FAST profile (daemon + cache +
# multi-worker) and must not silently pick up the release-gate
# --no-daemon/--no-build-cache/--max-workers=1 flags. Cheap, no Gradle.
#
# Issue #2515: the tag-triggered Build workflow must rename/upload/release the
# app2 APK (`app2/build/outputs/apk/debug/app2-debug.apk`), not the deleted
# `app` module output. v0.5.0's Build died on `mv app-debug.apk`. Cheap grep
# of .github/workflows/build.yml, no Gradle.
#
# Issue #2570: no module may declare a native build again. #2566 deleted the
# last `externalNativeBuild` (core-terminal's vendored local-pty JNI), so #2570
# deleted the Build workflow's corrupted-NDK-download retry wrapper (#1581)
# along with its shell test — a hard cut, no dormant fallback. The guard makes
# a reintroduced native build (which would silently regain an unprotected
# on-demand NDK download on the release path) a loud per-push failure.
#
# These guards live in one script (rather than inline `run:` blocks) because
# tests.yml is held under the 128 KiB file-size hygiene threshold with 1 KiB of
# required headroom; see scripts/check-file-size-hygiene.sh.
set -euo pipefail

cd "$(dirname "$0")/.."

chmod +x scripts/assemble-debug.sh scripts/test-assemble-debug.sh \
  scripts/test-build-workflow-apk-path.sh scripts/check-no-native-build.sh

scripts/test-assemble-debug.sh
scripts/test-build-workflow-apk-path.sh --self-test
scripts/test-build-workflow-apk-path.sh
scripts/check-no-native-build.sh --self-test
scripts/check-no-native-build.sh
