#!/usr/bin/env bash
# ci-emulator-repair-sdk-tools.sh — repair the hosted runner's Android cmdline-tools.
#
# Extracted VERBATIM out of the `Repair Android cmdline-tools + accept licenses (issue #771)` step of
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

# Issue #771 — REAL root cause + fix (supersedes the earlier "warm up a
# separate SDK copy" pre-step, which was INEFFECTIVE).
#
# The job dies inside the emulator-runner action's OWN "Install Android SDK"
# step. The action runs, by name on PATH:
#     sh -c "yes | sdkmanager --licenses > /dev/null"
# and that exits 1 in ~10 ms — far too fast for a JVM tool that actually
# started. The matching symptom in the previous pre-step's log was
# `yes: standard output: Broken pipe` on EVERY attempt: `sdkmanager` exits
# before reading a single line of stdin, so `yes` gets SIGPIPE. In other
# words the runner image's PRE-INSTALLED `sdkmanager`
# ($ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager) is broken on the
# current ubuntu-24.04 image and crashes instantly. The later
# "could not connect to TCP port 5554" is only the action's cleanup handler
# firing after that crash — no emulator was ever started.
#
# Why the previous attempt did NOT work: it accepted licenses / warmed
# packages against the SAME broken sdkmanager and threw the output away
# (`> /dev/null 2>&1`), so it could not even tell the binary was crashing.
# And the action does not reuse a "warmed" SDK — it re-invokes its own
# `sdkmanager --licenses` regardless, against the same broken binary.
#
# Why bumping the action version does NOT help: android-emulator-runner only
# downloads its own cmdline-tools when `$ANDROID_HOME/cmdline-tools` does NOT
# already exist (`if (!fs.existsSync(cmdlineToolsPath))`). On the hosted
# runner that directory exists, so EVERY action version (incl. main) skips
# the download and uses the runner's broken pre-installed sdkmanager.
#
# THE FIX (targets the action's actual failing call): replace the runner's
# broken `$ANDROID_HOME/cmdline-tools/latest` with the exact known-good
# cmdline-tools build the action itself pins (commandlinetools build
# 14742923). When the action then runs `sdkmanager --licenses` by name on
# PATH, PATH resolves to THIS freshly-installed, working sdkmanager — the
# same path the action uses — so its own license step succeeds and the AVD
# actually boots. We also accept licenses + warm the api-34 packages with
# the fresh tools so the action's subsequent install is a no-op.
#
# This step runs BEFORE any emulator/test, so a failure here can only ever
# block the run on infra provisioning — it can NEVER turn a genuine J1/J3
# test failure green (no #636/#638 false-green). We keep the journey suite
# + #760 retry-once + classifier below as the sole authoritative test gate.
set -uo pipefail
SDK_ROOT="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-/usr/local/lib/android/sdk}}"
echo "Using SDK_ROOT=$SDK_ROOT"
PREINSTALLED="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"

# 1) DIAGNOSE the pre-installed sdkmanager so the next CI run proves the
#    root cause (do NOT discard stderr this time). A working sdkmanager
#    prints its version in well under a second; the broken one exits
#    instantly. `|| true` keeps this purely diagnostic.
echo "::group::Diagnose pre-installed sdkmanager (expected to be broken)"
if [[ -x "$PREINSTALLED" ]]; then
  echo "java -version:"; java -version 2>&1 | sed 's/^/  /' || true
  echo "sdkmanager --version (stderr included):"
  "$PREINSTALLED" --version 2>&1 | sed 's/^/  /' || echo "  (non-zero exit from pre-installed sdkmanager --version)"
else
  echo "No pre-installed sdkmanager at $PREINSTALLED"
fi
echo "::endgroup::"

# 2) REPAIR: install the action's exact known-good cmdline-tools build
#    over the (broken) pre-installed `latest`, so the action's own
#    PATH-resolved `sdkmanager --licenses` runs against a working copy.
CMDLINE_TOOLS_ZIP="commandlinetools-linux-14742923_latest.zip"
CMDLINE_TOOLS_URL="https://dl.google.com/android/repository/${CMDLINE_TOOLS_ZIP}"
echo "::group::Reinstall known-good cmdline-tools (build 14742923)"
TMP_DL="$(mktemp -d)"
ok=""
for attempt in 1 2 3; do
  if curl -fsSL --retry 3 -o "$TMP_DL/$CMDLINE_TOOLS_ZIP" "$CMDLINE_TOOLS_URL"; then
    ok="yes"; echo "Downloaded $CMDLINE_TOOLS_ZIP on attempt $attempt"; break
  fi
  echo "cmdline-tools download attempt $attempt failed; retrying in 5s..."
  sleep 5
done
if [[ -z "$ok" ]]; then
  echo "::error title=cmdline-tools download failed::Could not fetch ${CMDLINE_TOOLS_URL} after retries. This is a runner-network infra failure, not a test failure (issue #771)."
  exit 1
fi
rm -rf "$TMP_DL/extract"
unzip -q "$TMP_DL/$CMDLINE_TOOLS_ZIP" -d "$TMP_DL/extract"
# The zip extracts to `cmdline-tools/`; the action expects the tools at
# `$SDK_ROOT/cmdline-tools/latest`.
rm -rf "$SDK_ROOT/cmdline-tools/latest"
mkdir -p "$SDK_ROOT/cmdline-tools"
mv "$TMP_DL/extract/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
chmod +x "$SDKMANAGER" || true
echo "Installed fresh sdkmanager at $SDKMANAGER"
echo "::endgroup::"

# 3) VERIFY the fresh sdkmanager actually runs. This is the load-bearing
#    proof that the repair worked (a JVM tool that starts prints a
#    version line in well under a second). If it still can't run, fail
#    NOW with an explicit infra classification rather than letting the
#    action crash deeper with the confusing "TCP port 5554" message.
echo "::group::Verify fresh sdkmanager"
if ! "$SDKMANAGER" --version; then
  echo "::error title=sdkmanager still broken after repair::The freshly-installed cmdline-tools sdkmanager still failed to run (issue #771). Treat as EMULATOR INFRA UNAVAILABLE, not a test failure."
  exit 1
fi
echo "::endgroup::"

# 4) Accept licenses + warm the exact api-34 packages with the WORKING
#    tools so the action's own license/install step is a no-op. Output is
#    visible this time so a future regression is diagnosable. The `|| true`
#    keeps these non-fatal: the action re-runs them, and the journey
#    suite + classifier remain the authoritative test gate.
echo "::group::Accept SDK licenses"
yes | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses || true
echo "::endgroup::"
echo "::group::Warm up api-35 packages"
"$SDKMANAGER" --sdk_root="$SDK_ROOT" \
  "platform-tools" \
  "platforms;android-35" \
  "system-images;android-35;google_apis;x86_64" || true
echo "::endgroup::"
