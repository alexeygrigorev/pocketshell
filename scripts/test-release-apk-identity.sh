#!/usr/bin/env bash
# shellcheck disable=SC1003,SC2016,SC2030,SC2031
set -uo pipefail

# ---------------------------------------------------------------------------
# Release APK identity contract (issue #2064)
#
# THE REPORTED DEFECT, REPRODUCED
# -------------------------------
# `scripts/pre-release-confidence-gate.sh` builds the debug + androidTest pair
# inside its isolated worktree, validates THAT binary on the emulator, and
# `publish_validated_apk` ships it. Every downstream stage then used to
# `rm -rf app/build` and rebuild its own pair, so terminal-lab,
# tmux-existing-session, setup-detection and the visual audit produced their
# journey evidence against a DIFFERENT binary — one nothing else in the chain
# validated and that is not the one shipped.
#
# ISSUE #2481 — ALL FOUR OF THOSE STAGES ARE GONE. They drove `app` module
# androidTest classes the rewrite's hard cut deleted, so the scripts went with
# them (D22). The identity chain now has exactly two live sites: the pre-release
# gate's own `verify-debug-apk-identity` step (which must run BEFORE
# `update-install-debug-apk`, its install site) and `publish_validated_apk`.
# Both are still driven here — the recorder/asserter behaviourally, the two
# wiring facts by line order, which is what would catch someone reintroducing a
# rebuild between validation and publish.
#
# Case `rebuilt_stage_binary_is_rejected` below is that exact scenario: the
# gate records its pair, then the app APK on disk is replaced by a different
# (still perfectly valid) build, and the chain is driven. Before the fix the
# stage would install it without a murmur. Now the chain hard-fails, naming
# both digests.
#
# WHAT IS ACTUALLY EXERCISED
# --------------------------
# Not a re-spelling of production. Case 5 onward invoke the REAL
# `scripts/release-emulator-validation.sh --verify-apk-identity`, which runs the
# REAL `export_validated_apk_identity` and the REAL `publish_validated_apk`
# assertion with exactly the environment the release flow gives them. No
# emulator, no Docker, no Gradle, no network: a couple of seconds.
#
# THE MUTATION THAT MUST REDDEN EACH CHECK is named in every case label. A case
# whose mutation cannot be stated is decorative, and this repo has paid for that
# lesson repeatedly (the G6 "wrong-cost" catalogue in AGENTS.md).
#
# Usage: scripts/test-release-apk-identity.sh
# ---------------------------------------------------------------------------

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$ROOT_DIR/scripts/lib/apk-identity.sh"

CHECKS=0
SANDBOX=""

cleanup() {
  [[ -n "$SANDBOX" && -d "$SANDBOX" ]] && rm -rf "$SANDBOX"
}
trap cleanup EXIT

pass_case() {
  CHECKS=$((CHECKS + 1))
  printf '  ok  %s\n' "$1"
}

fail_case() {
  printf 'FAIL: %s\n' "$1" >&2
  [[ -s "${CASE_LOG:-}" ]] && tail -n 30 "$CASE_LOG" >&2
  exit 1
}

# ---------------------------------------------------------------------------
# Fixture: a pre-release confidence-gate run directory holding a built pair and
# the identity record the real gate writes with the real recorder.
# ---------------------------------------------------------------------------
make_fixture() {
  local gate_run_dir="$1"
  mkdir -p "$gate_run_dir/worktree/app2/build/outputs/apk/debug"
  mkdir -p "$gate_run_dir/worktree/app2/build/outputs/apk/androidTest/debug"
  APP_FIXTURE="$gate_run_dir/worktree/app2/build/outputs/apk/debug/app2-debug.apk"
  TEST_FIXTURE="$gate_run_dir/worktree/app2/build/outputs/apk/androidTest/debug/app2-debug-androidTest.apk"
  printf 'PK\x03\x04 pocketshell debug apk build A\n' > "$APP_FIXTURE"
  printf 'PK\x03\x04 pocketshell androidTest apk build A\n' > "$TEST_FIXTURE"
  pocketshell_record_apk_identity \
    "$gate_run_dir/$POCKETSHELL_APK_IDENTITY_FILE_NAME" \
    "$APP_FIXTURE" "$TEST_FIXTURE" >/dev/null
}

# Drive the REAL release chain in its device-free identity-verification mode.
run_chain() {
  local gate_log_root="$1"
  local run_id="$2"
  env -u POCKETSHELL_TEST_MEM \
      -u APP_APK -u TEST_APK \
      -u POCKETSHELL_EXPECTED_APP_APK_SHA256 -u POCKETSHELL_EXPECTED_TEST_APK_SHA256 \
      -u BUILD_APKS -u PARALLEL_BUILD_APKS -u VISUAL_AUDIT_BUILD_APKS \
      LOG_ROOT="$SANDBOX/release" \
      PRE_RELEASE_GATE_LOG_ROOT="$gate_log_root" \
      RUN_ID="$run_id" \
      POCKETSHELL_AVD_LOCK_DIR="$SANDBOX/locks" \
      bash "$ROOT_DIR/scripts/release-emulator-validation.sh" --verify-apk-identity
}

main() {
  SANDBOX="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-apk-identity-test.XXXXXX")"
  CASE_LOG="$SANDBOX/case.log"
  printf 'release APK identity contract test (issue #2064)\n'

  # -- 1. the recorder + asserter agree on an untouched file ----------------
  # Mutation that reddens this: any change to the digest algorithm or to the
  # identity-file field names.
  local unit_dir="$SANDBOX/unit"
  mkdir -p "$unit_dir"
  printf 'binary A\n' > "$unit_dir/a.apk"
  printf 'binary B\n' > "$unit_dir/b.apk"
  pocketshell_record_apk_identity "$unit_dir/apk-identity.txt" \
    "$unit_dir/a.apk" "$unit_dir/b.apk" > "$CASE_LOG" 2>&1 ||
    fail_case "the recorder could not record two real files"
  local recorded_app
  recorded_app="$(pocketshell_read_apk_identity_field "$unit_dir/apk-identity.txt" app_apk_sha256)" ||
    fail_case "app_apk_sha256 was not readable back out of the identity file"
  pocketshell_assert_apk_identity "unit" "$unit_dir/a.apk" "$recorded_app" >/dev/null 2>&1 ||
    fail_case "an untouched file failed its own recorded digest"
  pass_case "recorded digest verifies against the untouched file"

  # -- 2. a one-byte change reddens it --------------------------------------
  printf 'binary A tampered\n' > "$unit_dir/a.apk"
  if pocketshell_assert_apk_identity "unit" "$unit_dir/a.apk" "$recorded_app" >"$CASE_LOG" 2>&1; then
    fail_case "a changed file passed its recorded digest"
  fi
  grep -q 'APK identity mismatch' "$CASE_LOG" ||
    fail_case "the mismatch failure did not name the mismatch"
  pass_case "a changed file is rejected, naming both digests"

  # -- 3. missing file and empty expectation both redden --------------------
  if pocketshell_assert_apk_identity "unit" "$unit_dir/gone.apk" "$recorded_app" >/dev/null 2>&1; then
    fail_case "a missing APK passed the identity assertion"
  fi
  pass_case "a missing APK is rejected"

  if pocketshell_assert_apk_identity "unit" "$unit_dir/b.apk" "" >/dev/null 2>&1; then
    fail_case "an EMPTY expected digest passed — 'I could not check' read as 'fine'"
  fi
  pass_case "an empty expected digest is rejected (fail closed)"

  # -- 4. the standalone developer run is untouched -------------------------
  # Mutation that reddens this: making the verifier mandatory unconditionally,
  # which would break every standalone `scripts/pre-release-confidence-gate.sh`
  # run on a dev box that builds its own APKs.
  (
    unset POCKETSHELL_EXPECTED_APP_APK_SHA256 POCKETSHELL_EXPECTED_TEST_APK_SHA256
    pocketshell_verify_walkthrough_apks "standalone"
  ) >/dev/null 2>&1 ||
    fail_case "the verifier is not a no-op when no expectation was exported"
  pass_case "no exported expectation -> deliberate no-op (standalone dev run)"

  # A HALF-configured chain must not silently verify half the pair.
  (
    export POCKETSHELL_EXPECTED_APP_APK_SHA256="$recorded_app"
    unset POCKETSHELL_EXPECTED_TEST_APK_SHA256
    export APP_APK="$unit_dir/b.apk"
    pocketshell_verify_walkthrough_apks "half"
  ) >/dev/null 2>&1 &&
    fail_case "only one of the two expected digests set was accepted"
  pass_case "a half-set expectation pair is rejected"

  # A verification MODE that verifies nothing must fail, not report a pass.
  (
    unset POCKETSHELL_EXPECTED_APP_APK_SHA256 POCKETSHELL_EXPECTED_TEST_APK_SHA256
    pocketshell_require_walkthrough_apk_identity "mode"
  ) >/dev/null 2>&1 &&
    fail_case "--verify-apk-identity reported a pass over an unchecked pair"
  pass_case "verification mode with nothing to verify fails closed"

  # -- 5. the REAL chain, green ---------------------------------------------
  # Mutation that reddens this: removing export_validated_apk_identity from
  # scripts/release-emulator-validation.sh, or making it tolerate a record it
  # cannot verify.
  local gate_log_root="$SANDBOX/gate"
  make_fixture "$gate_log_root/chain-green-pre-release"
  if ! run_chain "$gate_log_root" "chain-green" > "$CASE_LOG" 2>&1; then
    fail_case "the release chain rejected an intact validated pair"
  fi
  grep -q 'PASS: release APK identity chain verified' "$CASE_LOG" ||
    fail_case "the chain did not report its end-to-end pass"
  # Issue #2481: the four downstream stage scripts this used to launch are
  # deleted. A stale grep for them would go from "green because they verified"
  # to "red because they are gone", so assert the OPPOSITE property instead —
  # the chain must not resurrect a stage that no longer exists (which would be
  # the D22 fallback-branch smell, and would abort every release).
  local dead_stage
  for dead_stage in terminal-workbench phone-walkthrough parallel-setup-detection; do
    grep -q "scripts/$dead_stage.sh --verify-apk-identity" "$CASE_LOG" &&
      fail_case "the chain still drives the deleted stage scripts/$dead_stage.sh"
    [[ -e "$ROOT_DIR/scripts/$dead_stage.sh" ]] &&
      fail_case "scripts/$dead_stage.sh is back on disk; it drives deleted app-module androidTest classes (issue #2481)"
  done
  # scripts/capture-walkthrough-screenshots.sh SURVIVES, repointed at app2's
  # journey screenshots — but as a STANDALONE tool, not a chain stage (a second
  # full run of the set the gate already ran). It keeps the identity contract so
  # it can be pointed at the validated pair, and that must not rot.
  grep -q "scripts/capture-walkthrough-screenshots.sh --verify-apk-identity" "$CASE_LOG" &&
    fail_case "the chain drives the visual pass as a stage again; it is a standalone tool (issue #2481)"
  local ca="$ROOT_DIR/scripts/capture-walkthrough-screenshots.sh"
  grep -qE '^[[:space:]]+pocketshell_verify_walkthrough_apks "visual audit \(install\)"' "$ca" ||
    fail_case "the standalone visual pass no longer re-verifies before installing"
  pass_case "real chain: export -> publish, with no deleted stage resurrected"

  # The published artifact must exist and be byte-identical to the gate's APK.
  local published="$SANDBOX/release/chain-green/app2-debug.apk"
  [[ -f "$published" ]] || fail_case "the chain published no APK"
  local gate_apk="$gate_log_root/chain-green-pre-release/worktree/app2/build/outputs/apk/debug/app2-debug.apk"
  local published_sha gate_sha recorded_sha
  published_sha="$(sha256sum "$published" | awk '{print $1}')"
  gate_sha="$(sha256sum "$gate_apk" | awk '{print $1}')"
  recorded_sha="$(pocketshell_read_apk_identity_field \
    "$gate_log_root/chain-green-pre-release/$POCKETSHELL_APK_IDENTITY_FILE_NAME" app_apk_sha256)"
  [[ "$published_sha" == "$gate_sha" && "$gate_sha" == "$recorded_sha" ]] ||
    fail_case "gate-validated ($gate_sha) / stage-installed ($recorded_sha) / published ($published_sha) are not one binary"
  grep -q "published APK sha256: \`$published_sha\`" "$SANDBOX/release/chain-green/summary.md" ||
    fail_case "the release summary does not record the published digest"
  pass_case "gate-validated == stage-installed == published ($published_sha)"

  # -- 6. THE REPORTED DEFECT: a stage rebuilds its own binary --------------
  # This is what the three phone-walkthrough stages did on every release before
  # this change: `rm -rf app/build` then rebuild. The rebuilt APK is valid — it
  # is simply NOT the one the gate validated and ships.
  local rebuilt_root="$SANDBOX/gate-rebuilt"
  make_fixture "$rebuilt_root/chain-rebuilt-pre-release"
  printf 'PK\x03\x04 pocketshell debug apk build B (stage rebuild)\n' \
    > "$rebuilt_root/chain-rebuilt-pre-release/worktree/app2/build/outputs/apk/debug/app2-debug.apk"
  if run_chain "$rebuilt_root" "chain-rebuilt" > "$CASE_LOG" 2>&1; then
    fail_case "a stage-rebuilt, byte-different app APK was accepted — the #2064 defect is not closed"
  fi
  grep -q 'APK identity mismatch' "$CASE_LOG" ||
    fail_case "the rebuilt-binary rejection did not name the identity mismatch"
  pass_case "rebuilt_stage_binary_is_rejected (the reported defect, reproduced)"

  # Same for the androidTest half — the instrumentation APK is half the journey
  # evidence and was rebuilt by exactly the same code path.
  local rebuilt_test_root="$SANDBOX/gate-rebuilt-test"
  make_fixture "$rebuilt_test_root/chain-rebuilt-test-pre-release"
  printf 'PK\x03\x04 pocketshell androidTest apk build B (stage rebuild)\n' \
    > "$rebuilt_test_root/chain-rebuilt-test-pre-release/worktree/app2/build/outputs/apk/androidTest/debug/app2-debug-androidTest.apk"
  if run_chain "$rebuilt_test_root" "chain-rebuilt-test" > "$CASE_LOG" 2>&1; then
    fail_case "a stage-rebuilt androidTest APK was accepted"
  fi
  pass_case "a rebuilt androidTest APK is rejected too"

  # -- 6b2. publish ships the RECORDED binary, not a second guess -----------
  # The chain used to derive the publishable APK twice: once as the gate's
  # recorded `app_apk`, once as a hardcoded `<gate-run>/worktree/app/build/...`
  # path. Two derivations of "the validated binary" is one too many. Here the
  # gate recorded a RELOCATED APK while a different, decoy binary sits at the
  # conventional path — the artefact the release ships must be the recorded,
  # validated one. Mutation that reddens this: publishing $PRE_RELEASE_GATE_APK
  # instead of the recorded $APP_APK.
  local reloc_root="$SANDBOX/gate-relocated"
  local reloc_run="$reloc_root/chain-relocated-pre-release"
  make_fixture "$reloc_run"
  local reloc_apk="$reloc_run/worktree/app2/build/outputs/apk/debug/app2-debug-relocated.apk"
  printf 'PK\x03\x04 pocketshell debug apk RELOCATED (the validated one)\n' > "$reloc_apk"
  pocketshell_record_apk_identity "$reloc_run/$POCKETSHELL_APK_IDENTITY_FILE_NAME" \
    "$reloc_apk" "$reloc_run/worktree/app2/build/outputs/apk/androidTest/debug/app2-debug-androidTest.apk" \
    >/dev/null
  printf 'PK\x03\x04 DECOY at the conventional path — never validated\n' \
    > "$reloc_run/worktree/app2/build/outputs/apk/debug/app2-debug.apk"
  run_chain "$reloc_root" "chain-relocated" > "$CASE_LOG" 2>&1 ||
    fail_case "the chain rejected a validated pair that simply lives at a different path"
  local reloc_published_sha reloc_recorded_sha decoy_sha
  reloc_published_sha="$(sha256sum "$SANDBOX/release/chain-relocated/app2-debug.apk" | awk '{print $1}')"
  reloc_recorded_sha="$(sha256sum "$reloc_apk" | awk '{print $1}')"
  decoy_sha="$(sha256sum "$reloc_run/worktree/app2/build/outputs/apk/debug/app2-debug.apk" | awk '{print $1}')"
  [[ "$reloc_published_sha" == "$reloc_recorded_sha" ]] ||
    fail_case "the release published $reloc_published_sha, not the validated $reloc_recorded_sha"
  [[ "$reloc_published_sha" != "$decoy_sha" ]] ||
    fail_case "the release published the never-validated binary sitting at the conventional path"
  pass_case "publish ships the RECORDED validated binary, not a second path guess"

  # -- 6d. a mutation AFTER the last stage but BEFORE publish reddens -------
  # Mutation that reddens this: moving publish_validated_apk before the final
  # stage, or deleting its publish-time digest assertion. The hook is accepted
  # only by the device-free proof mode and changes the app APK at the exact
  # boundary that used to be untested.
  local late_root="$SANDBOX/gate-late-tamper"
  make_fixture "$late_root/chain-late-tamper-pre-release"
  local late_hook="$SANDBOX/tamper-after-stages.sh"
  cat > "$late_hook" <<'HOOK'
#!/usr/bin/env bash
set -euo pipefail
printf 'PK\x03\x04 tampered after the last stage\n' > "$1"
HOOK
  chmod +x "$late_hook"
  if POCKETSHELL_APK_IDENTITY_POST_STAGE_HOOK="$late_hook" \
      run_chain "$late_root" "chain-late-tamper" > "$CASE_LOG" 2>&1; then
    fail_case "an APK changed after the last stage was still published"
  fi
  grep -q 'APK identity mismatch' "$CASE_LOG" ||
    fail_case "the publish-time late-tamper rejection did not name the mismatch"
  pass_case "publish rejects an APK changed after the last validation stage"

  # -- 7. a missing identity record stops the release -----------------------
  # Mutation that reddens this: making export_validated_apk_identity tolerant of
  # an absent record, which would silently restore the old rebuild-per-stage
  # behaviour on any gate run that failed to write one.
  local no_record_root="$SANDBOX/gate-no-record"
  make_fixture "$no_record_root/chain-no-record-pre-release"
  rm -f "$no_record_root/chain-no-record-pre-release/$POCKETSHELL_APK_IDENTITY_FILE_NAME"
  if run_chain "$no_record_root" "chain-no-record" > "$CASE_LOG" 2>&1; then
    fail_case "the release chain continued with NO APK identity record"
  fi
  pass_case "a missing identity record stops the release chain"

  # -- 8. a gate APK deleted after recording ---------------------------------
  local gone_root="$SANDBOX/gate-gone"
  make_fixture "$gone_root/chain-gone-pre-release"
  rm -f "$gone_root/chain-gone-pre-release/worktree/app2/build/outputs/apk/debug/app2-debug.apk"
  if run_chain "$gone_root" "chain-gone" > "$CASE_LOG" 2>&1; then
    fail_case "the release chain continued with the validated APK missing"
  fi
  pass_case "a validated APK missing at export time stops the chain"

  # -- 9. the two wiring facts the device-free mode cannot reach -------------
  # Everything above drives production code. Two links can only be checked
  # statically, because reaching them behaviourally needs an emulator:
  #   (a) the REAL release flow calls export_validated_apk_identity between the
  #       confidence gate and the first walkthrough stage — the verification
  #       mode calls it directly, so deleting it from the real flow would not
  #       redden case 5;
  #   (b) each stage re-verifies at its INSTALL site, after any optional
  #       rebuild, not only at config time.
  # Both are asserted on line ORDER (not mere substring presence) and reject a
  # commented-out call, which is the shape that keeps a wiring guard green while
  # the wiring is gone.
  local rev="$ROOT_DIR/scripts/release-emulator-validation.sh"
  local export_line gate_line
  export_line="$(grep -n '^export_validated_apk_identity$' "$rev" | head -1 | cut -d: -f1)"
  gate_line="$(grep -n '"pre-release confidence gate" \\$' "$rev" | head -1 | cut -d: -f1)"
  [[ -n "$export_line" && -n "$gate_line" ]] ||
    fail_case "the real release flow has no top-level export_validated_apk_identity after the confidence gate"
  [[ "$export_line" -gt "$gate_line" ]] ||
    fail_case "export_validated_apk_identity runs before the gate that produces the identity record"
  pass_case "the real release flow exports the identity after the gate"

  local publish_line
  publish_line="$(grep -n '^publish_validated_apk$' "$rev" | tail -1 | cut -d: -f1)"
  [[ -n "$publish_line" && "$publish_line" -gt "$export_line" ]] ||
    fail_case "publish_validated_apk is not pinned after the validated-APK export"
  pass_case "the release publishes only after the validated-APK export"

  # Issue #2481: with the four walkthrough stages deleted, the pre-release gate
  # IS the install stage. Its digest re-check must run BEFORE the update-install
  # that consumes the binary, and it must be checking app2's APK, not a path from
  # the deleted `app` module. Asserted on line ORDER so a commented-out or
  # after-the-fact check reddens — the shape that keeps a wiring guard green
  # while the wiring is gone.
  local gate="$ROOT_DIR/scripts/pre-release-confidence-gate.sh"
  local gate_verify_line gate_install_line
  gate_verify_line="$(grep -n '^run_step "verify-debug-apk-identity"' "$gate" | head -1 | cut -d: -f1)"
  gate_install_line="$(grep -n '^run_step "update-install-debug-apk"' "$gate" | head -1 | cut -d: -f1)"
  [[ -n "$gate_verify_line" && -n "$gate_install_line" ]] ||
    fail_case "the pre-release gate no longer has both a verify-debug-apk-identity and an update-install-debug-apk step"
  [[ "$gate_verify_line" -lt "$gate_install_line" ]] ||
    fail_case "the pre-release gate verifies the APK digest AFTER update-installing it, which proves nothing"
  grep -qF 'APK_PATH="${APK_PATH:-app2/build/outputs/apk/debug/app2-debug.apk}"' "$gate" ||
    fail_case "the pre-release gate does not validate app2's debug APK (issue #2481)"
  grep -qF 'TEST_APK_PATH="${TEST_APK_PATH:-app2/build/outputs/apk/androidTest/debug/app2-debug-androidTest.apk}"' "$gate" ||
    fail_case "the pre-release gate does not validate app2's androidTest APK (issue #2481)"
  pass_case "the gate re-verifies app2's recorded pair before its install site"

  # -- 10. green again, so none of the reds above are permanent --------------
  local again_root="$SANDBOX/gate-again"
  make_fixture "$again_root/chain-again-pre-release"
  run_chain "$again_root" "chain-again" > "$CASE_LOG" 2>&1 ||
    fail_case "the chain no longer passes on an intact pair after the red cases"
  pass_case "green again after every red case"

  # The test asserts its OWN check count so it cannot pass vacuously.
  if [[ "$CHECKS" -ne 19 ]]; then
    printf 'FAIL: ran %s checks, expected 19\n' "$CHECKS" >&2
    exit 1
  fi
  printf 'PASS: release APK identity contract (%s checks)\n' "$CHECKS"
}

main "$@"
