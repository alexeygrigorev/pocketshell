#!/usr/bin/env bash
set -euo pipefail

# ---------------------------------------------------------------------------
# APK signing-identity check (issue #2638)
#
# Asserts, on a BUILT APK, the identity contract the release/debuild split
# introduced:
#
#   release variant: packageName == com.pocketshell.app.release, launcher
#     label DISTINCT from debug's "PocketShell", and the signer certificate
#     is NOT the committed debug.keystore's certificate (and, when release
#     signing material is resolvable on this machine, IS the release
#     keystore's certificate).
#
#   debug variant: packageName == com.pocketshell.app, label "PocketShell",
#     signer IS the committed debug.keystore — i.e. the daily-driver install
#     identity did not move when release signing landed.
#
# Neither check can be done from source alone: the point is what actually got
# packaged and signed, so this runs against APK files. Side-by-side coinstall
# evidence on a device is the reviewer's emulator pass (docs/review-standards.md).
#
# Usage:
#   scripts/check-apk-signing.sh --variant release --apk <path-to-apk>
#   scripts/check-apk-signing.sh --variant debug  --apk <path-to-apk>
#
# The debug variant expects the PLAIN com.pocketshell.app build; a worktree
# build made with -PpocketshellAppIdSuffix=<token> has a different package by
# design and is out of scope here.
#
# Environment:
#   ANDROID_SDK / ANDROID_HOME / ANDROID_SDK_ROOT (falls back to
#   local.properties sdk.dir, then /home/alexey/Android/Sdk)
# ---------------------------------------------------------------------------

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

usage() {
  sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
  exit 2
}

VARIANT=""
APK=""
while [[ $# -gt 0 ]]; do
  case "$1" in
    --variant)
      [[ -n "${2:-}" ]] || fail "--variant needs a value"
      VARIANT="$2"
      shift 2
      ;;
    --apk)
      [[ -n "${2:-}" ]] || fail "--apk needs a value"
      APK="$2"
      shift 2
      ;;
    --help|-h)
      usage
      ;;
    *)
      fail "unknown argument: $1"
      ;;
  esac
done

[[ "$VARIANT" == "release" || "$VARIANT" == "debug" ]] || usage
[[ -n "$APK" ]] || usage
[[ -f "$APK" ]] || fail "APK not found: $APK"

CHECKS=0
pass() {
  CHECKS=$((CHECKS + 1))
  printf '  ok  %s\n' "$1"
}

# --- tool resolution -------------------------------------------------------

resolve_sdk() {
  if [[ -n "${ANDROID_SDK:-}" ]]; then echo "$ANDROID_SDK"; return; fi
  if [[ -n "${ANDROID_HOME:-}" ]]; then echo "$ANDROID_HOME"; return; fi
  if [[ -n "${ANDROID_SDK_ROOT:-}" ]]; then echo "$ANDROID_SDK_ROOT"; return; fi
  local prop
  prop="$(grep -E '^sdk\.dir=' "$ROOT_DIR/local.properties" 2>/dev/null | cut -d= -f2- || true)"
  if [[ -n "$prop" && -d "$prop" ]]; then echo "$prop"; return; fi
  echo "/home/alexey/Android/Sdk"
}

SDK_DIR="$(resolve_sdk)"
[[ -d "$SDK_DIR" ]] || fail "Android SDK not found (set ANDROID_SDK)"

BUILD_TOOLS_DIR="$SDK_DIR/build-tools"
[[ -d "$BUILD_TOOLS_DIR" ]] || fail "no build-tools under $SDK_DIR"

AAPT="" APKSIGNER=""
readarray -t BT_DIRS < <(ls "$BUILD_TOOLS_DIR" | sort -rV | head -5)
for bt in "${BT_DIRS[@]}"; do
  if [[ -x "$BUILD_TOOLS_DIR/$bt/aapt" && -x "$BUILD_TOOLS_DIR/$bt/apksigner" ]]; then
    AAPT="$BUILD_TOOLS_DIR/$bt/aapt"
    APKSIGNER="$BUILD_TOOLS_DIR/$bt/apksigner"
    break
  fi
done
[[ -n "$AAPT" ]] || fail "no build-tools version with both aapt and apksigner under $BUILD_TOOLS_DIR"

if command -v keytool >/dev/null 2>&1; then
  KEYTOOL="keytool"
elif [[ -n "${JAVA_HOME:-}" && -x "$JAVA_HOME/bin/keytool" ]]; then
  KEYTOOL="$JAVA_HOME/bin/keytool"
else
  fail "keytool not found on PATH or under JAVA_HOME"
fi

# --- keystore certificate helpers ------------------------------------------

# A certificate fingerprint is only comparable in one spelling: lowercase hex
# without colons/whitespace.
normalize_sha() {
  printf '%s' "$1" | tr -d ' :' | tr 'A-F' 'a-f'
}

keystore_cert_sha() { # <keystore> <storepass> [alias]
  local out
  if [[ -n "${3:-}" ]]; then
    out="$("$KEYTOOL" -list -v -keystore "$1" -storepass "$2" -alias "$3" 2>/dev/null || true)"
  else
    out="$("$KEYTOOL" -list -v -keystore "$1" -storepass "$2" 2>/dev/null || true)"
  fi
  local line
  line="$(printf '%s\n' "$out" | grep -m1 -E '^[[:space:]]*SHA256:')"
  [[ -n "$line" ]] || return 1
  normalize_sha "${line#*SHA256:}"
}

DEBUG_CERT_SHA="$(keystore_cert_sha "$ROOT_DIR/debug.keystore" android androiddebugkey)" ||
  fail "could not read a SHA256 certificate from the committed debug.keystore"

# --- APK facts -------------------------------------------------------------

apk_package() {
  "$AAPT" dump badging "$1" | sed -n "s/^package: name='\([^']*\)'.*/\1/p" | head -1
}

apk_label() {
  "$AAPT" dump badging "$1" | sed -n "s/^application-label:'\([^']*\)'.*/\1/p" | head -1
}

apk_signer_sha() {
  local out line
  out="$("$APKSIGNER" verify --print-certs "$1" 2>/dev/null)" || return 1
  line="$(printf '%s\n' "$out" | grep -m1 'certificate SHA-256 digest:')"
  [[ -n "$line" ]] || return 1
  normalize_sha "${line#*certificate SHA-256 digest:}"
}

# --- signature validity (both variants) ------------------------------------

"$APKSIGNER" verify "$APK" ||
  fail "apksigner verify rejected $APK — not a validly signed APK"
pass "apksigner verify accepts the signature"

PACKAGE_NAME="$(apk_package "$APK")"
[[ -n "$PACKAGE_NAME" ]] || fail "could not read a package name from $APK via aapt"
SIGNER_SHA="$(apk_signer_sha "$APK")" ||
  fail "could not read a signer certificate SHA-256 from $APK"
LABEL="$(apk_label "$APK")"

if [[ "$VARIANT" == "release" ]]; then
  [[ "$PACKAGE_NAME" == "com.pocketshell.app.release" ]] ||
    fail "release APK packageName is '$PACKAGE_NAME', expected com.pocketshell.app.release"
  pass "packageName == com.pocketshell.app.release"

  [[ -n "$LABEL" ]] || fail "release APK has no application-label"
  [[ "$LABEL" != "PocketShell" ]] ||
    fail "release APK launcher label is 'PocketShell' — it must be distinct from debug's label"
  pass "launcher label is distinct from debug's ('$LABEL')"

  [[ "$SIGNER_SHA" != "$DEBUG_CERT_SHA" ]] ||
    fail "release APK is signed by the DEBUG certificate ($DEBUG_CERT_SHA) — the release identity did not apply"
  pass "signer certificate differs from the committed debug.keystore cert"

  # When this machine holds the release keystore (locally via gitignored
  # keystore.properties), additionally prove the APK was signed by THAT key
  # and not just by "any non-debug key". CI is skipped: there the identity
  # comes from the same secrets the build itself used.
  local_props="$ROOT_DIR/keystore.properties"
  if [[ -f "$local_props" ]]; then
    prop() { grep -E "^$1=" "$local_props" | head -1 | cut -d= -f2-; }
    rel_store="$(prop storeFile)"
    rel_pass="$(prop storePassword)"
    rel_alias="$(prop keyAlias)"
    [[ -n "$rel_store" && -n "$rel_pass" && -n "$rel_alias" ]] ||
      fail "keystore.properties exists but is missing storeFile/storePassword/keyAlias"
    rel_cert="$(keystore_cert_sha "$rel_store" "$rel_pass" "$rel_alias")" ||
      fail "could not read a SHA256 certificate from $rel_store (alias $rel_alias)"
    [[ "$SIGNER_SHA" == "$rel_cert" ]] ||
      fail "release APK signer ($SIGNER_SHA) is not the keystore.properties cert ($rel_cert)"
    pass "signer certificate matches the local release keystore (keystore.properties)"
  else
    printf '  --  keystore.properties absent; local ==keystore check skipped (CI uses the same secrets it signed with)\n'
  fi
else
  [[ "$PACKAGE_NAME" == "com.pocketshell.app" ]] ||
    fail "debug APK packageName is '$PACKAGE_NAME', expected com.pocketshell.app"
  pass "packageName == com.pocketshell.app"

  [[ "$LABEL" == "PocketShell" ]] ||
    fail "debug APK launcher label is '$LABEL', expected 'PocketShell'"
  pass "launcher label is still 'PocketShell'"

  [[ "$SIGNER_SHA" == "$DEBUG_CERT_SHA" ]] ||
    fail "debug APK signer ($SIGNER_SHA) is not the committed debug.keystore cert ($DEBUG_CERT_SHA)"
  pass "signer certificate is the committed debug.keystore cert"
fi

# The check asserts its OWN count so a silently skipped assertion cannot read
# as green.
[[ "$CHECKS" -ge 4 ]] || fail "only $CHECKS assertions ran; expected at least 4 — the check did not really run"
printf 'PASS: %s APK signing identity (%s checks): %s\n' "$VARIANT" "$CHECKS" "$APK"
