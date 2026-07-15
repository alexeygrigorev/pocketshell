#!/usr/bin/env bash
# Issue #1581: run the release-critical Build's `assembleDebug`, self-healing
# the intermittent corrupted-NDK-download flake.
#
# Gradle auto-installs NDK 27.0.12077973 (AGP 8.9.2's default NDK) on demand
# when it configures :shared:core-terminal (that module has an externalNativeBuild
# CMake block). The GitHub-hosted runner occasionally receives a corrupt or
# incomplete NDK archive and the configure phase dies with:
#
#     An error occurred while preparing SDK package NDK (Side by side) 27.0.12077973:
#     Error on ZipFile unknown archive.
#     java.io.IOException: Error on ZipFile unknown archive
#
# Confirmed a pure download flake: a `gh run rerun --failed` with NO code change
# went green. Each occurrence costs a manual re-run on the release path.
#
# THE KEY INSIGHT: re-running the same install over the partial/corrupt archive
# does NOT help — the downloader reuses the cached corrupt file. The partial
# download must be CLEARED between attempts so the retry fetches a fresh archive.
#
# This wrapper drives Gradle's OWN SDK downloader (already working in the Build
# job — only the archive integrity flakes), so it does not depend on the
# runner's pre-installed `sdkmanager` binary (which issue #771 found broken on
# some runner images). It ONLY retries when the failure carries the specific
# corrupted-NDK signature; any other failure (a real compile error, a genuine
# regression) fails immediately so retries cannot mask it or waste CI time.
#
# The retry/clear logic is exercised by scripts/test-ci-ndk-retry.sh (a fast,
# JVM-free shell test wired into the Unit job of .github/workflows/tests.yml).

set -uo pipefail

SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
NDK_VERSION="${NDK_VERSION:-27.0.12077973}"
MAX_ATTEMPTS="${NDK_RETRY_ATTEMPTS:-3}"

# The build command. Overridable so the retry/clear logic can be unit-tested
# without a real Gradle build or Android SDK.
BUILD_CMD="${BUILD_CMD:-./gradlew assembleDebug --stacktrace}"

# File the build output is tee'd to so the corrupted-NDK signature can be
# scanned. Overridable for tests.
BUILD_LOG="${BUILD_LOG:-$(mktemp)}"

# Partial NDK download/extract locations + the SDK download caches. Clearing
# these between attempts forces the next attempt to fetch a fresh archive.
# Overridable (space-separated) so the clear-and-retry behaviour can be tested
# against a temp dir with no real SDK present.
: "${NDK_PARTIAL_PATHS:=$SDK_ROOT/ndk/$NDK_VERSION $SDK_ROOT/.temp ${ANDROID_PREFS_ROOT:-$HOME/.android}/cache}"

# Substring that identifies the corrupted-NDK-download failure (issue #1581).
# Only a failure whose output contains this triggers the clear-and-retry.
NDK_CORRUPT_SIGNATURE="${NDK_CORRUPT_SIGNATURE:-Error on ZipFile unknown archive}"

# Seconds to wait between attempts (0 in tests).
NDK_RETRY_DELAY="${NDK_RETRY_DELAY:-5}"

# Remove every configured partial-download/cache path so the next attempt
# re-downloads the NDK archive from scratch. This is the load-bearing step:
# without it, the retry reuses the corrupt cached archive and fails again.
clear_partial_ndk_download() {
  local p
  for p in $NDK_PARTIAL_PATHS; do
    if [[ -e "$p" ]]; then
      echo "  clearing partial NDK state: $p"
      rm -rf "$p" || true
    fi
  done
}

run_build_with_ndk_retry() {
  local attempt
  for (( attempt = 1; attempt <= MAX_ATTEMPTS; attempt++ )); do
    echo "::group::Build attempt ${attempt}/${MAX_ATTEMPTS}"
    # pipefail (set above) makes the pipeline's exit status the build's, not
    # tee's, so a build failure is detected even though the output is tee'd.
    if eval "$BUILD_CMD" 2>&1 | tee "$BUILD_LOG"; then
      echo "::endgroup::"
      echo "Build succeeded on attempt ${attempt}."
      return 0
    fi
    echo "::endgroup::"

    # Only self-heal the specific corrupted-NDK-download flake. Any other
    # failure fails immediately — retrying would only waste CI time and could
    # mask a real regression.
    if ! grep -qF "$NDK_CORRUPT_SIGNATURE" "$BUILD_LOG"; then
      echo "Build failed for a reason other than the corrupted-NDK-download flake (issue #1581); not retrying."
      return 1
    fi

    if (( attempt >= MAX_ATTEMPTS )); then
      echo "::error title=NDK download still corrupt after ${MAX_ATTEMPTS} attempts::The NDK ${NDK_VERSION} archive was corrupt on every attempt (issue #1581). This is a runner-network/download infra failure, not a code failure."
      return 1
    fi

    echo "Detected the corrupted-NDK-download flake (issue #1581). Clearing the partial download so the next attempt fetches a fresh archive..."
    clear_partial_ndk_download
    sleep "$NDK_RETRY_DELAY"
  done
  return 1
}

# Run only when executed directly; sourcing (the shell test) gets the functions
# without triggering a build.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  run_build_with_ndk_retry
fi
