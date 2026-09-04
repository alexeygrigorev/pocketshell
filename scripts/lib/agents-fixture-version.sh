#!/usr/bin/env bash
# Issue #2381 — keep the Docker `agents` fixture's `pocketshell --version` in
# lockstep with the APK built from the same checkout.
#
# WHY THIS EXISTS
#
# The app treats the release core of its own `versionName` as the `pocketshell`
# CLI version it expects on the host. Before issue #2356 that was a literal in
# app/build.gradle.kts, and tests/docker/Dockerfile.agents sed'd the SAME
# literal out of a COPYed copy of that file at image-build time — so the two
# agreed by construction. #2356 derived versionName from the git tag instead,
# the literal disappeared, the sed silently started matching nothing, and the
# fixture froze at a placeholder. From then on every connected journey against
# the `agents` fixture could land on the bootstrap "Host setup needed" sheet
# rather than the screen it asserts.
#
# The Docker build context cannot carry the git history the derivation needs,
# so the version is passed IN: this helper exports
# POCKETSHELL_AGENT_FIXTURE_VERSION, tests/docker/docker-compose.yml forwards it
# to the container's environment, and tests/docker/agent-entrypoint.sh stamps it
# into /opt/pocketshell-agent-fixture-version at container start (sshd does not
# inherit the entrypoint's environment, so the file — not the variable — is what
# the app's probe can see).
#
# Source it before any `docker compose ... up` that brings up `agents` for a run
# that also installs the app:
#
#   source scripts/lib/agents-fixture-version.sh
#   export_agents_fixture_version "$APP_APK"      # APK is optional
#
# PRECEDENCE (highest first), and why:
#
#   1. The versionName baked into $1, when an APK path is given and readable.
#      This is the ONLY ground truth, because it is literally what the running
#      app reports to HostBootstrapper. The checkout's derivation is merely a
#      prediction of it, and the two genuinely diverge on the release chain:
#      scripts/pre-release-confidence-gate.sh builds its APK inside a `.git`-less
#      isolated rsync copy (so that APK says `0.0.0-dev`), then every downstream
#      stage — the confidence gate, capture-walkthrough-screenshots — installs THAT
#      pair (issue #2064) while running from the tagged outer checkout, whose
#      derivation says e.g. `0.4.45-4-gSHA`. Deriving there would stamp the
#      fixture with a version no installed binary ever reports.
#   2. An already-set POCKETSHELL_AGENT_FIXTURE_VERSION, so a caller that knows
#      the exact string it is about to ASSERT can pin it (the pre-release gate
#      pins $APP_VERSION_NAME, making "what we stamp" and "what we assert" the
#      same value by construction).
#   3. The checkout's own scripts/derive-version.sh — right whenever the APK is
#      built from this same tree (agents pool, CI journey lane, a plain local
#      run).
#
# Never fails: an unreadable APK falls through to 2/3, and an underivable
# version leaves the variable empty, which falls through to the image's baked
# `0.0.0-dev` — exactly what a tagless checkout's APK reports.

# Echo a usable `aapt2` (or `aapt`) path, or nothing. Checked in PATH first,
# then the SDK's build-tools, newest first.
pocketshell_resolve_aapt() {
  local candidate sdk
  for candidate in aapt2 aapt; do
    if command -v "$candidate" >/dev/null 2>&1; then
      command -v "$candidate"
      return 0
    fi
  done
  for sdk in "${ANDROID_SDK_ROOT:-}" "${ANDROID_HOME:-}" "$HOME/Android/Sdk"; do
    [ -n "$sdk" ] || continue
    [ -d "$sdk/build-tools" ] || continue
    candidate="$(find "$sdk/build-tools" -mindepth 2 -maxdepth 2 \
      \( -name aapt2 -o -name aapt \) -type f -perm -u+x 2>/dev/null | sort -V | tail -n 1)"
    if [ -n "$candidate" ]; then
      printf '%s\n' "$candidate"
      return 0
    fi
  done
  return 1
}

# Echo the versionName recorded in the APK at $1, or nothing when it cannot be
# read (no aapt on this machine, unreadable/corrupt APK).
pocketshell_apk_version_name() {
  local apk="$1" aapt badging
  [ -n "$apk" ] && [ -r "$apk" ] || return 1
  aapt="$(pocketshell_resolve_aapt)" || return 1
  badging="$("$aapt" dump badging "$apk" 2>/dev/null | head -n 5 || true)"
  [ -n "$badging" ] || return 1
  printf '%s\n' "$badging" |
    sed -n "s/.*versionName='\([^']*\)'.*/\1/p" | head -n 1
}

export_agents_fixture_version() {
  local apk="${1:-}"
  local from_apk=""

  if [ -n "$apk" ]; then
    from_apk="$(pocketshell_apk_version_name "$apk" 2>/dev/null || true)"
    if [ -n "$from_apk" ]; then
      POCKETSHELL_AGENT_FIXTURE_VERSION="$from_apk"
      export POCKETSHELL_AGENT_FIXTURE_VERSION
      return 0
    fi
    # An APK that is not there yet is normal (a standalone run stamps the
    # fixture before it builds), and the checkout derivation is right for that
    # case. An APK that EXISTS but cannot be parsed is worth saying out loud.
    if [ -r "$apk" ]; then
      printf 'WARN: could not read versionName from %s (no usable aapt2/aapt?); falling back to the checkout derivation for the agents fixture stamp (#2381)\n' \
        "$apk" >&2
    fi
  fi

  if [ -n "${POCKETSHELL_AGENT_FIXTURE_VERSION:-}" ]; then
    export POCKETSHELL_AGENT_FIXTURE_VERSION
    return 0
  fi

  local here root derived
  here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)"
  root="$(cd -- "$here/../.." && pwd -P)"
  derived="$(bash "$root/scripts/derive-version.sh" version-name 2>/dev/null || true)"

  POCKETSHELL_AGENT_FIXTURE_VERSION="${derived:-}"
  export POCKETSHELL_AGENT_FIXTURE_VERSION
  return 0
}

# The one idiom every emulator/walkthrough script uses, so the "which version
# does the binary we are about to install report?" decision lives in ONE place.
#
#   $1  the script's BUILD_APKS-style flag: "1" means it rebuilds the pair from
#       THIS checkout below (so derive), anything else means it installs $2 as
#       it stands (so read $2).
#   $2  the app APK that will be installed.
#
# Echoes the resulting stamp so every run log carries the evidence.
export_agents_fixture_version_for_run() {
  local build_apks="${1:-1}" apk="${2:-}"

  if [ "$build_apks" = "1" ]; then
    export_agents_fixture_version
  else
    export_agents_fixture_version "$apk"
  fi

  printf 'agents fixture pocketshell version stamp: %s (issue #2381)\n' \
    "${POCKETSHELL_AGENT_FIXTURE_VERSION:-<empty: image default 0.0.0-dev>}" >&2
  return 0
}
