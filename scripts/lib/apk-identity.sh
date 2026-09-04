#!/usr/bin/env bash

# ---------------------------------------------------------------------------
# Release-chain APK identity (issue #2064)
#
# THE DEFECT THIS CLOSES — a correctness hole, not just a slow gate
# -----------------------------------------------------------------
# `scripts/pre-release-confidence-gate.sh` builds the debug + androidTest APK
# pair inside its isolated worktree copy and validates THAT binary on the
# emulator. `scripts/release-emulator-validation.sh` then publishes exactly that
# file as `release-emulator-validation/<run>/app-debug.apk` — the artifact the
# release notes tell a human to download.
#
# But every downstream stage (the deleted `phone-walkthrough.sh` terminal-lab /
# tmux-existing-session / setup-detection, and `capture-walkthrough-
# screenshots.sh`) used to `rm -rf app/build` and rebuild its own pair from the
# same source. Same source is NOT the same binary: those are byte-different
# builds. So the terminal, tmux, setup-detection and visual-audit evidence — the
# journey evidence a tag actually rests on — was produced against a binary that
# nothing else in the chain ever validated, and that is not the one shipped.
#
# A release gate that signs off on a binary it did not test is a hole regardless
# of how fast it runs. (It also cost real time: 472s in run 20260809-v0442-r3,
# 603s in 20260808-165533 — and the FIRST v0.4.42 attempt died 58 minutes in,
# with a zipflinger OOM inside terminal-lab's redundant rebuild.)
#
# THE CONTRACT
# ------------
#   1. The confidence gate builds the pair ONCE and records
#      `apk-identity.txt` (absolute paths + sha256 + byte size) in its run dir.
#   2. `release-emulator-validation.sh` reads that file and exports the pair,
#      their expected sha256s, and BUILD_APKS=0 into every downstream stage.
#   3. Every downstream stage HARD-FAILS before installing anything if the file
#      it is about to install does not hash to the recorded value.
#   4. `publish_validated_apk` re-verifies the published copy against the same
#      recorded sha, so the shipped artifact is provably the validated one.
#
# FAIL CLOSED, BOTH WAYS
# ----------------------
# "I could not check" must never read the same as "I checked and it is fine"
# (the standing rule in this repo's catalogue). So:
#   * a missing APK, a missing identity file, a malformed field, an empty
#     expected sha, or only ONE of the two expected shas being set is a hard
#     failure, not a skip;
#   * with NO expected sha exported at all the verifier is a deliberate no-op,
#     because that is the standalone developer invocation
#     (a standalone `scripts/capture-walkthrough-screenshots.sh` on a dev box) which builds
#     its own APKs and has nothing to compare against. The release chain always
#     exports them, and `--verify-apk-identity` mode REQUIRES them.
# ---------------------------------------------------------------------------

POCKETSHELL_APK_IDENTITY_FILE_NAME="apk-identity.txt"

pocketshell_apk_identity_fail() {
  printf 'FAIL: %s\n' "$1" >&2
  return 1
}

# sha256 of a file. Hard-fails (empty output, non-zero) when the file is absent
# so a caller can never compare against a silently empty digest.
pocketshell_apk_sha256() {
  local file="$1"
  [[ -f "$file" ]] || {
    pocketshell_apk_identity_fail "cannot hash a file that does not exist: $file"
    return 1
  }
  local digest
  digest="$(sha256sum "$file" | awk '{print $1}')"
  [[ -n "$digest" ]] || {
    pocketshell_apk_identity_fail "sha256sum produced no digest for $file"
    return 1
  }
  printf '%s' "$digest"
}

# pocketshell_record_apk_identity <identity-file> <app-apk> <test-apk>
#
# Called by the confidence gate immediately after the ONE build of the pair.
pocketshell_record_apk_identity() {
  local identity_file="$1"
  local app_apk="$2"
  local test_apk="$3"

  local app_sha test_sha app_bytes test_bytes
  app_sha="$(pocketshell_apk_sha256 "$app_apk")" || return 1
  test_sha="$(pocketshell_apk_sha256 "$test_apk")" || return 1
  app_bytes="$(stat -c '%s' "$app_apk")"
  test_bytes="$(stat -c '%s' "$test_apk")"

  mkdir -p "$(dirname "$identity_file")"
  {
    printf '# PocketShell release APK identity (issue #2064)\n'
    printf '# Every downstream release stage installs THESE files and verifies\n'
    printf '# THESE digests; publish_validated_apk ships the same bytes.\n'
    printf 'recorded_at=%s\n' "$(date -Is)"
    printf 'app_apk=%s\n' "$app_apk"
    printf 'app_apk_sha256=%s\n' "$app_sha"
    printf 'app_apk_bytes=%s\n' "$app_bytes"
    printf 'test_apk=%s\n' "$test_apk"
    printf 'test_apk_sha256=%s\n' "$test_sha"
    printf 'test_apk_bytes=%s\n' "$test_bytes"
  } > "$identity_file"

  printf 'Recorded release APK identity: %s\n' "$identity_file"
  printf '  app  %s  %s\n' "$app_sha" "$app_apk"
  printf '  test %s  %s\n' "$test_sha" "$test_apk"
}

# pocketshell_read_apk_identity_field <identity-file> <key>
#
# Exact key match on a `key=value` line; comments ignored. Prints nothing and
# returns non-zero when the key is absent, so an unset field can never be read
# as an empty-but-valid value.
pocketshell_read_apk_identity_field() {
  local identity_file="$1"
  local key="$2"
  [[ -f "$identity_file" ]] || {
    pocketshell_apk_identity_fail "APK identity file not found: $identity_file"
    return 1
  }
  local value
  value="$(awk -v want="$key" -F= '
    /^[[:space:]]*#/ { next }
    { name = $1; sub(/^[[:space:]]+/, "", name); sub(/[[:space:]]+$/, "", name) }
    name == want { sub(/^[^=]*=/, "", $0); print; exit }
  ' "$identity_file")"
  [[ -n "$value" ]] || {
    pocketshell_apk_identity_fail "APK identity file $identity_file has no '$key' value"
    return 1
  }
  printf '%s' "$value"
}

# pocketshell_assert_apk_identity <label> <apk> <expected-sha256>
#
# The load-bearing assertion. A mismatch means the caller is about to install /
# ship a DIFFERENT binary than the one the gate validated.
pocketshell_assert_apk_identity() {
  local label="$1"
  local apk="$2"
  local expected="$3"

  [[ -n "$expected" ]] || {
    pocketshell_apk_identity_fail "$label: no expected sha256 was supplied for $apk. An unverifiable APK is not a verified APK (issue #2064)."
    return 1
  }
  [[ -f "$apk" ]] || {
    pocketshell_apk_identity_fail "$label: APK is missing at $apk (expected sha256 $expected)"
    return 1
  }
  local actual
  actual="$(pocketshell_apk_sha256 "$apk")" || return 1
  if [[ "$actual" != "$expected" ]]; then
    pocketshell_apk_identity_fail "$label: APK identity mismatch for $apk
  expected sha256: $expected  (built and validated by the pre-release confidence gate)
  actual   sha256: $actual
This stage would have installed a DIFFERENT binary than the one the release gate
validated and than publish_validated_apk ships. Rebuild-in-stage is exactly the
defect issue #2064 removed; do not paper over it by re-recording the digest."
    return 1
  fi
  printf '%s: APK identity verified (%s) %s\n' "$label" "$actual" "$apk"
}

# pocketshell_export_walkthrough_apk_env <gate-run-dir>
#
# The ONE place the release chain turns the gate's recorded identity into the
# environment every downstream stage consumes. Production and the #2064 test
# both call this, so the test drives the real wiring rather than a re-spelling
# of it.
pocketshell_export_walkthrough_apk_env() {
  local gate_run_dir="$1"
  local identity_file="$gate_run_dir/$POCKETSHELL_APK_IDENTITY_FILE_NAME"

  local app_apk test_apk app_sha test_sha
  app_apk="$(pocketshell_read_apk_identity_field "$identity_file" app_apk)" || return 1
  test_apk="$(pocketshell_read_apk_identity_field "$identity_file" test_apk)" || return 1
  app_sha="$(pocketshell_read_apk_identity_field "$identity_file" app_apk_sha256)" || return 1
  test_sha="$(pocketshell_read_apk_identity_field "$identity_file" test_apk_sha256)" || return 1

  # Verify at the source, before any stage is launched: a gate APK that has
  # been overwritten/truncated since it was recorded must stop the release here
  # rather than at the third stage that tries to install it.
  pocketshell_assert_apk_identity "release chain (gate-built app APK)" "$app_apk" "$app_sha" || return 1
  pocketshell_assert_apk_identity "release chain (gate-built androidTest APK)" "$test_apk" "$test_sha" || return 1

  export APP_APK="$app_apk"
  export TEST_APK="$test_apk"
  export POCKETSHELL_EXPECTED_APP_APK_SHA256="$app_sha"
  export POCKETSHELL_EXPECTED_TEST_APK_SHA256="$test_sha"
  # Build knobs for the four install-stage consumers. Each already had its own;
  # none gains a new bypass channel — these turn the existing "do not rebuild" path
  # ON for the release chain, which is what makes the binary identical.
  export BUILD_APKS=0
  export PHONE_WALKTHROUGH_CLEAN_GENERATED=0
  export PARALLEL_BUILD_APKS=0
  export VISUAL_AUDIT_BUILD_APKS=0
  export POCKETSHELL_APK_IDENTITY_FILE="$identity_file"
}

# pocketshell_verify_walkthrough_apks <label>
#
# Called by every downstream stage before it installs anything.
#   * both expected shas exported -> verify both, hard-fail on any mismatch;
#   * neither exported            -> deliberate no-op (standalone dev run);
#   * exactly one exported        -> hard failure (a half-configured chain must
#                                    not silently verify half the pair).
pocketshell_verify_walkthrough_apks() {
  local label="$1"
  local app_expected="${POCKETSHELL_EXPECTED_APP_APK_SHA256:-}"
  local test_expected="${POCKETSHELL_EXPECTED_TEST_APK_SHA256:-}"

  if [[ -z "$app_expected" && -z "$test_expected" ]]; then
    return 0
  fi
  if [[ -z "$app_expected" || -z "$test_expected" ]]; then
    pocketshell_apk_identity_fail "$label: exactly one of POCKETSHELL_EXPECTED_APP_APK_SHA256 / POCKETSHELL_EXPECTED_TEST_APK_SHA256 is set. Half a verified pair is not a verified pair (issue #2064)."
    return 1
  fi

  pocketshell_assert_apk_identity "$label (app APK)" "${APP_APK:-}" "$app_expected" || return 1
  pocketshell_assert_apk_identity "$label (androidTest APK)" "${TEST_APK:-}" "$test_expected" || return 1
}

# pocketshell_require_walkthrough_apk_identity <label>
#
# The `--verify-apk-identity` mode's entry point. Same as the verifier above but
# an unset expectation is a HARD failure: a verification mode that verifies
# nothing is the vacuous green this repo keeps re-learning about.
pocketshell_require_walkthrough_apk_identity() {
  local label="$1"
  if [[ -z "${POCKETSHELL_EXPECTED_APP_APK_SHA256:-}" || -z "${POCKETSHELL_EXPECTED_TEST_APK_SHA256:-}" ]]; then
    pocketshell_apk_identity_fail "$label: --verify-apk-identity requires POCKETSHELL_EXPECTED_APP_APK_SHA256 and POCKETSHELL_EXPECTED_TEST_APK_SHA256. Refusing to report a pass over an unchecked pair."
    return 1
  fi
  pocketshell_verify_walkthrough_apks "$label"
}
