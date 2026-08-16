#!/usr/bin/env bash
# ci-emulator-free-disk.sh — reclaim runner disk before the AVD is created.
#
# Extracted VERBATIM out of the `Free disk space for the AVD` step of
# .github/workflows/tests.yml's `emulator-journey` job (issue #2134). The step
# body and the rationale below are byte-identical to what ran inline; only their
# home moved, so `tests.yml` stops being ~270 bytes from the 128 KiB
# oversized-file guard. The workflow now calls this file directly.
#
# The inline step ran under Actions' DEFAULT shell, `bash -e {0}` — errexit was
# already on before the body's own `set -uo pipefail`. `set -e` here reproduces
# that exactly, and the body keeps its own `set -uo pipefail` line unchanged, so
# every `|| true` in it still guards the same thing it always did.
set -e

# Issue #760: the AVD create intermittently FATAL-ed with
# "Not enough space to create userdata partition. Available: 6957 MB,
# need 7372 MB" — pure runner disk pressure, not a test failure. The
# ubuntu-latest image ships ~30 GB of preinstalled SDKs/toolchains we do
# not use for this job (.NET, Haskell/GHC, the bundled Android NDK, the
# large CodeQL/boost caches). Reclaim them BEFORE the emulator-runner
# creates the AVD so the userdata partition always fits, then print the
# free space so a future disk regression is visible in the log. The `|| true`
# guards keep the step green if a path is already gone on a newer image.
#
# Issue #771 (round-3, PROVEN ROOT CAUSE): the original #760 version also
# ran
#     sudo rm -rf "$AGENT_TOOLSDIRECTORY"   # == /opt/hostedtoolcache
# which DELETED the JDK that `actions/setup-java@v5` installs under
# `/opt/hostedtoolcache/Java_*` and that `JAVA_HOME` points into
# (e.g. /opt/hostedtoolcache/Java_Temurin-Hotspot_jdk/17.0.19-10/x64).
# After that delete, EVERY Java tool died with
#     ERROR: JAVA_HOME is set to an invalid directory: …
# so the runner's sdkmanager (and the #771 freshly-reinstalled copy)
# crashed instantly and the emulator never booted. The cmdline-tools were
# never the real problem — the missing JDK was. Fix: prune the OTHER
# tool-caches under $AGENT_TOOLSDIRECTORY but PRESERVE the directory the
# live JAVA_HOME resolves into, so the active JDK survives.
set -uo pipefail
echo "::group::Disk before cleanup"
df -h /
echo "::endgroup::"
sudo rm -rf /usr/share/dotnet || true
sudo rm -rf /usr/local/lib/android/sdk/ndk || true
sudo rm -rf /opt/ghc /usr/local/.ghcup || true
sudo rm -rf /usr/local/share/boost || true
sudo rm -rf /opt/hostedtoolcache/CodeQL || true
# Prune unused tool-caches under $AGENT_TOOLSDIRECTORY but KEEP the
# active JDK that JAVA_HOME depends on (issue #771). Resolve the
# top-level tool-cache subdir the live JAVA_HOME lives in (e.g.
# `Java_Temurin-Hotspot_jdk`) and exclude exactly that one, so the
# exact JDK setup-java installed survives even across version bumps.
if [[ -n "${AGENT_TOOLSDIRECTORY:-}" && -d "${AGENT_TOOLSDIRECTORY:-}" ]]; then
  keep_dir=""
  if [[ -n "${JAVA_HOME:-}" && "$JAVA_HOME" == "$AGENT_TOOLSDIRECTORY"/* ]]; then
    # First path segment of JAVA_HOME relative to the tool-cache root.
    rel="${JAVA_HOME#"$AGENT_TOOLSDIRECTORY"/}"
    keep_dir="${rel%%/*}"
  fi
  echo "Pruning $AGENT_TOOLSDIRECTORY, keeping active JDK dir: '${keep_dir:-<none>}'"
  if [[ -n "$keep_dir" ]]; then
    sudo find "$AGENT_TOOLSDIRECTORY" -mindepth 1 -maxdepth 1 \
      ! -name "$keep_dir" -exec rm -rf {} + || true
  else
    # No JAVA_HOME under the tool-cache (unexpected): leave the dir
    # intact rather than risk deleting the JDK. The deletions above
    # plus docker prune already free far more than the 9000 MB guard.
    echo "JAVA_HOME not under \$AGENT_TOOLSDIRECTORY; leaving it intact to protect the JDK."
  fi
fi
sudo docker image prune -af || true
echo "::group::Disk after cleanup"
df -h /
echo "::endgroup::"
# Confirm the active JDK survived the prune (issue #771): a deleted
# JAVA_HOME makes every Java tool fail with
# "JAVA_HOME is set to an invalid directory" and the emulator never
# boots. Fail NOW with an explicit infra classification if it's gone.
if [[ -n "${JAVA_HOME:-}" && ! -x "$JAVA_HOME/bin/java" ]]; then
  echo "::error title=EMULATOR INFRA UNAVAILABLE::JAVA_HOME=$JAVA_HOME has no bin/java after disk cleanup; the JDK was deleted (issue #771 regression guard). This is an infra setup error, not a test failure."
  exit 1
fi
avail_mb="$(df -m --output=avail / | tail -1 | tr -d ' ')"
echo "Available on / after cleanup: ${avail_mb} MB"
# The AVD userdata partition needs ~7372 MB; require a safe margin so
# the create cannot FATAL on disk. If it still can't be freed, fail
# NOW with an explicit infra classification rather than letting the
# emulator-runner FATAL deeper in with a confusing message.
if [[ "$avail_mb" -lt 9000 ]]; then
  echo "::error title=EMULATOR INFRA UNAVAILABLE::Only ${avail_mb} MB free on / after cleanup; the AVD userdata partition needs ~7372 MB. This is a runner-disk infra shortage (issue #760), not a test failure."
  exit 1
fi
