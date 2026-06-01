#!/usr/bin/env bash
set -euo pipefail

ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"

usage() {
  cat <<'USAGE'
Usage: scripts/install-update-apk.sh <apk>

Data-preserving update install for PocketShell APKs.

Runs exactly:
  adb install -r <apk>

This helper is the upgrade/update path. It does not clear app data, uninstall
packages, or retry through an uninstall fallback. Use a cold-reset script/gate
when destructive package cleanup is intended.

Environment overrides:
  ANDROID_SDK=/home/alexey/Android/Sdk
  ADB=$ANDROID_SDK/platform-tools/adb
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

if [[ "$#" -ne 1 ]]; then
  usage >&2
  exit 2
fi

apk="$1"
if [[ ! -f "$apk" ]]; then
  printf 'APK does not exist: %s\n' "$apk" >&2
  exit 1
fi

printf 'Data-preserving update install: adb install -r %s\n' "$apk"
"$ADB" install -r "$apk"
