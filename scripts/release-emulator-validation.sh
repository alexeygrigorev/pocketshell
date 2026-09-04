#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

source "$ROOT_DIR/scripts/lib/avd-lock.sh"
source "$ROOT_DIR/scripts/lib/gradle-profile.sh"
source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
source "$ROOT_DIR/scripts/lib/release-validation-storage.sh"
# Issue #2064: one binary for the whole chain — the pre-release gate builds it,
# every downstream stage installs it, publish_validated_apk ships it, and each
# hop re-checks the sha256.
source "$ROOT_DIR/scripts/lib/apk-identity.sh"
# Issue #2381: the deterministic `agents` fixture must report the same
# `pocketshell --version` as the APK this gate validates, or the bootstrap
# "Host setup needed" sheet takes over the long-running stability journey.
source "$ROOT_DIR/scripts/lib/agents-fixture-version.sh"
export_agents_fixture_version

# Issue #2054: apply and assert the release build resource profile ONCE, at the
# top of the whole validation, before the ~30-60 minute run takes the shared AVD
# lock. Every stage below inherits POCKETSHELL_TEST_MEM from here (each stage
# also re-asserts, so running a stage standalone is still protected).
#
# NOTE for whoever launches this via `systemd-run --user -p MemoryMax=...`: that
# OUTER cap is cosmetic for the build. Each heavy stage re-enters
# scripts/cgroup-run.sh, which creates its own SIBLING scope under robust.slice
# via scripts/lib/scope-run.sh. Only POCKETSHELL_TEST_MEM binds the compile.
pocketshell_assert_gradle_execution_profile \
  "release emulator validation" \
  "${POCKETSHELL_GRADLE_RESOURCE_ARGS[*]}"
# The build-scope ceiling is applied+asserted after the --help handler below, so
# `--help` stays a pure query. This is the release entry point and it always
# builds, so it keeps the EARLY assertion rather than a point-of-use one — and
# because it exports POCKETSHELL_TEST_MEM, every child stage inherits 24G and
# their own point-of-use assertions pass without repeating the export.

# Issue #2064: `--verify-apk-identity` drives the REAL chain wiring — this
# script's export of the gate-recorded pair, its publish-time digest assertion,
# and each downstream stage's own verification — with no emulator, no Docker and
# no Gradle. It touches no device, so it must not queue on the AVD lock.
if [[ "${1:-}" == "--verify-apk-identity" ]]; then
  export POCKETSHELL_AVD_LOCK_ACQUIRED=1
fi

LOG_ROOT="${LOG_ROOT:-$ROOT_DIR/build/release-emulator-validation}"
if [[ "$LOG_ROOT" != /* ]]; then
  LOG_ROOT="$ROOT_DIR/$LOG_ROOT"
fi
RUN_ID="${RUN_ID:-$(date +%Y%m%d-%H%M%S)}"
RUN_DIR="$LOG_ROOT/$RUN_ID"
SUMMARY_PATH="$RUN_DIR/summary.md"
PRE_RELEASE_RUN_ID="$RUN_ID-pre-release"
# Overridable so the #2064 identity test can point the whole chain at a fixture
# gate run without an emulator; the release default is unchanged.
PRE_RELEASE_GATE_LOG_ROOT="${PRE_RELEASE_GATE_LOG_ROOT:-$ROOT_DIR/build/pre-release-confidence-gate}"
PRE_RELEASE_GATE_RUN_DIR="$PRE_RELEASE_GATE_LOG_ROOT/$PRE_RELEASE_RUN_ID"
PRE_RELEASE_GATE_APK="$PRE_RELEASE_GATE_RUN_DIR/worktree/app2/build/outputs/apk/debug/app2-debug.apk"
VALIDATED_APK="$RUN_DIR/app2-debug.apk"
# Issue #2481: the journey screenshots the pre-release gate pulls off the device
# after its unfiltered app2 instrumented run. They are the release VISUAL-AUDIT
# artifact now that the four walkthrough stages that used to produce one are
# gone (see the note above the stage list below).
PRE_RELEASE_GATE_JOURNEY_SCREENSHOTS="$PRE_RELEASE_GATE_RUN_DIR/journey-screenshots"
ANDROID_SDK="${ANDROID_SDK:-/home/alexey/Android/Sdk}"
ADB="${ADB:-$ANDROID_SDK/platform-tools/adb}"

usage() {
  cat <<'USAGE'
Usage: scripts/release-emulator-validation.sh [--check-storage]

  --check-storage  Authenticate the generated release root, reclaim only its
                   stale safe-listed output, prove the 24 GiB capacity floor,
                   and exit before AVD lock, Gradle, Docker, or emulator work.
                   The hosted workflow runs this after its explicit reclaim.

Runs the required emulator-only pre-tag release validation from clean, pushed
main and writes a summary for scripts/push-release-tag.sh.

Acquires an exclusive `flock` on `build/.avd-lock` (relative to the repo
root) before touching the emulator so that parallel-worktree gate runs
serialise on the shared local AVD. If another emulator-touching gate script
is running, this invocation blocks until that script exits. The lock is
released automatically on script exit. See issue #182.

Required state:
  - current branch is main, or a release/vX.Y.Z candidate branch checked out
    in its own worktree (docs/release.md)
  - worktree and index are clean
  - HEAD equals that branch's pushed remote head (origin/<branch>)

Environment overrides:
  POCKETSHELL_AVD_LOCK_FILE
      Override the lock file path (default: <repo-root>/build/.avd-lock).
  RELEASE_VALIDATION_SKIP_MAIN_GUARD=1
      Skip the clean pushed-main guard for CI workflow_dispatch runs where the
      checkout is intentionally detached.

The optional TERMINAL_RELEASE_GATE=1 and LONG_RUNNING_TEST=1 stages were
DELETED by issue #2481, not disabled. Every class they drove lived in the
`app` module's androidTest tree, which the rewrite's hard cut removed:
RealAgentReleaseGateTest, LongRunningSessionStabilityTest, and (through
scripts/release-terminal-gate.sh -> scripts/terminal-workbench.sh, all deleted)
EmulatorDockerSshSmokeTest, TerminalLabDockerTest, TmuxAttachPrefillDockerTest
and TmuxExternalUpdateDockerTest. app2's terminal/reconnect evidence comes from
its own instrumented journeys (J03 attach-and-type, J05 reconnect-after-drop,
J06 background-grace-return), which the pre-release confidence gate now runs
unfiltered against the validated APK pair, and which the `app2-journey` lane in
.github/workflows/app2.yml runs on every push to `stable`/`main`. Real-agent
CLI evidence has no app2 successor at all — agent awareness is a cut feature
(docs/rewrite-implementation-plan.md "Scope amendment").

Artifacts:
  build/release-emulator-validation/<run-id>/summary.md
  build/release-emulator-validation/<run-id>/app2-debug.apk
  build/pre-release-confidence-gate/<run-id>-pre-release/
  build/pre-release-confidence-gate/<run-id>-pre-release/journey-screenshots/
USAGE
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi

# Issue #2381: a tagless checkout reaching this script is an ANOMALY, not a
# supported mode, and it must say so before the ~40-minute gate rather than
# after it.
#
# Everything this chain exists to prove is version-relative: the setup-detection
# matrix drives HostBootstrapScenarioSuiteTest, whose seven profiles all compare
# the app's version against the `pocketshell` CLI the Docker host reports
# ("up to date", "update available", "app too old"). At the `0.0.0` core that
# scripts/derive-version.sh emits with no reachable `v*` tag, none of those
# relationships is expressible and every profile is vacuous — and the artifact
# publish_validated_apk ships would be stamped versionCode=1 / 0.0.0-dev.
# Before this check the symptom was seven identical, cause-free
# `java.lang.AssertionError`s inside instrumentation, after the ~40-minute
# pre-release gate had already run.
#
# A function, not an inline block, so tests/scripts/gate-isolated-copy-version-
# test.sh can drive THIS code (not a copy of it) red and green without starting
# a release run — the same extract-and-source idiom
# tests/scripts/pre-release-version-test.sh uses for the gate's fixture check.
release_chain_version_preflight() {
  local root_dir="$1"
  local version_name core
  version_name="$(bash "$root_dir/scripts/derive-version.sh" version-name 2>/dev/null || true)"
  core="${version_name#v}"
  core="${core%%+*}"
  core="${core%%-*}"
  if [[ -z "$core" || "$core" == "0.0.0" ]]; then
    printf 'REFUSING: %s derives versionName=%s, whose release core is %s (issue #2381).\n' \
      "$root_dir" "${version_name:-<empty>}" "${core:-<empty>}" >&2
    printf 'The release validation chain is entirely version-relative — every setup-detection profile compares the app version against the host `pocketshell` CLI version — so at a 0.0.0 core it proves nothing, and publish_validated_apk would ship a versionCode=1 / 0.0.0-dev artifact.\n' >&2
    printf 'Fix the CHECKOUT, not this script: check out with full tag history (actions/checkout fetch-depth: 0, which .github/workflows/release-emulator-validation.yml already does) or `git fetch --tags` so at least one v* tag is reachable from HEAD.\n' >&2
    return 1
  fi
  printf 'Release chain version: %s (core %s) — issue #2381 preflight\n' "$version_name" "$core"
}

pocketshell_release_validation_require_run_id "$RUN_ID" ||
  exit "$POCKETSHELL_RELEASE_DISK_PREFLIGHT_FAIL_RC"

pocketshell_apply_release_gate_scope_memory "release emulator validation"

# Issue #2055: fail closed before taking the one AVD lock or launching the
# isolated pre-release copy. The device-free APK identity proof builds nothing
# and remains exempt, like --help and the profile-only probe.
RELEASE_STORAGE_RETENTION_ENABLED=1
if [[ "${1:-}" == "--verify-apk-identity" ]]; then
  RELEASE_STORAGE_RETENTION_ENABLED=0
else
  pocketshell_release_validation_prepare_root \
    "$ROOT_DIR" "$PRE_RELEASE_GATE_LOG_ROOT" ||
    exit "$POCKETSHELL_RELEASE_DISK_PREFLIGHT_FAIL_RC"
  pocketshell_release_disk_preflight \
    "$ROOT_DIR" "$PRE_RELEASE_GATE_LOG_ROOT" "release emulator validation"
  if [[ "${1:-}" == "--check-storage" ]]; then
    printf 'Release storage contract satisfied: canonical generated root authenticated; 24 GiB floor available.\n'
    exit 0
  fi
  # Issue #2381: last of the cheap fail-closed preconditions, in the same zone
  # as the storage/disk checks above and still before the shared AVD lock, the
  # isolated rsync copy and every minute of Gradle. `--check-storage` has
  # already exited; `--verify-apk-identity` never reaches here.
  release_chain_version_preflight "$ROOT_DIR" || exit 1
fi

pocketshell_acquire_avd_lock "$ROOT_DIR" "${1:-}"

release_validation_on_exit() {
  local exit_status="$?"
  local cleanup_status=0
  trap - EXIT
  set +e
  if [[ "$RELEASE_STORAGE_RETENTION_ENABLED" == "1" ]]; then
    if [[ "$exit_status" -eq 0 ]]; then
      pocketshell_release_validation_finish_run \
        "$PRE_RELEASE_GATE_LOG_ROOT" "$PRE_RELEASE_RUN_ID" success || cleanup_status=$?
    else
      pocketshell_release_validation_finish_run \
        "$PRE_RELEASE_GATE_LOG_ROOT" "$PRE_RELEASE_RUN_ID" failure || cleanup_status=$?
    fi
  fi
  pocketshell_release_all
  if [[ "$exit_status" -eq 0 && "$cleanup_status" -ne 0 ]]; then
    printf 'FAIL: release validation passed but generated-output retention cleanup failed.\n' >&2
    exit_status="$cleanup_status"
  fi
  exit "$exit_status"
}
trap release_validation_on_exit EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  printf 'Summary: %s\n' "$SUMMARY_PATH" >&2
  exit 1
}

require_clean_pushed_main() {
  if [[ "${RELEASE_VALIDATION_SKIP_MAIN_GUARD:-0}" == "1" ]]; then
    printf 'Skipping clean pushed-main guard because RELEASE_VALIDATION_SKIP_MAIN_GUARD=1\n'
    return 0
  fi

  local branch
  branch="$(git branch --show-current)"
  # docs/release.md: a release is cut on a `release/vX.Y.Z` candidate branch
  # checked out in its own worktree, and the root checkout never leaves
  # `main`. Both are valid release lines. The guard is unchanged in substance
  # — clean, and identical to the branch's OWN pushed remote head — it just
  # no longer hardcodes `main` as the only branch that can be that line.
  [[ "$branch" == "main" || "$branch" =~ ^release/v[0-9]+\.[0-9]+\.[0-9]+$ ]] ||
    fail "release validation must run from main or a release/vX.Y.Z candidate branch, not '$branch'"
  git diff --quiet || fail "worktree has unstaged changes"
  git diff --cached --quiet || fail "index has staged changes"
  [[ -z "$(git ls-files --others --exclude-standard)" ]] ||
    fail "worktree has untracked files"
  git fetch --quiet origin "$branch"
  local local_sha
  local local_origin_sha
  local_sha="$(git rev-parse HEAD)"
  local_origin_sha="$(git rev-parse "origin/$branch")"
  [[ "$local_sha" == "$local_origin_sha" ]] ||
    fail "HEAD ($local_sha) must match origin/$branch ($local_origin_sha)"
}

write_summary_header() {
  mkdir -p "$RUN_DIR"
  {
    printf '# PocketShell Release Emulator Validation\n\n'
    printf 'Generated: %s\n' "$(date -Is)"
    printf 'Commit SHA: %s\n' "$(git rev-parse HEAD)"
    printf 'Branch: %s\n' "$(git branch --show-current)"
    printf 'Automated status: RUNNING\n'
    printf 'Visual audit inspected: no\n'
    # Issue #2054: record the execution profile the build actually ran with, so a
    # future "the gate OOMed" triage does not have to guess at heaps and scopes.
    printf 'Gradle resource profile: %s\n' "${POCKETSHELL_GRADLE_RESOURCE_ARGS[*]}"
    printf 'Build scope MemoryMax (POCKETSHELL_TEST_MEM): %s\n' "${POCKETSHELL_TEST_MEM:-unset}"
    printf '\n## Required Artifacts\n\n'
  } > "$SUMMARY_PATH"
}

record_artifact() {
  local label="$1"
  local path="$2"
  printf -- '- %s: `%s`\n' "$label" "$path" >> "$SUMMARY_PATH"
}

safe_artifact_name() {
  printf '%s' "$1" | tr '[:upper:] /' '[:lower:]--' | tr -cd '[:alnum:]_.-'
}

print_failure_log_tail() {
  local log_file="$1"
  if [[ -s "$log_file" ]]; then
    printf '\nLast 80 lines from %s:\n' "$log_file" >&2
    tail -n 80 "$log_file" >&2 || true
  else
    printf '\nNo output was captured in %s\n' "$log_file" >&2
  fi
}

# Issue #2064: run_required already measured every stage and threw the number
# away. Keep them so the release summary carries its own wall-clock breakdown
# and a "before/after" is a `grep`, not an archaeology exercise across artefact
# mtimes (which is how the 41m56s baseline for run 20260809-v0442-r3 had to be
# reconstructed).
STAGE_LABELS=()
STAGE_SECONDS=()
STAGE_STATUSES=()

write_stage_timings() {
  local i total=0
  for i in "${!STAGE_LABELS[@]}"; do
    total=$((total + STAGE_SECONDS[i]))
  done
  {
    printf '\n## Stage wall clock (issue #2064)\n\n'
    printf -- '- Total stage seconds: **%s** (%sm%ss)\n\n' "$total" "$((total / 60))" "$((total % 60))"
    printf '| Stage | Seconds | %% | Status |\n'
    printf '| --- | ---: | ---: | --- |\n'
    for i in "${!STAGE_LABELS[@]}"; do
      if [[ "$total" -gt 0 ]]; then
        printf '| %s | %s | %s%% | %s |\n' \
          "${STAGE_LABELS[$i]}" "${STAGE_SECONDS[$i]}" \
          "$((STAGE_SECONDS[i] * 100 / total))" "${STAGE_STATUSES[$i]}"
      else
        printf '| %s | %s | n/a | %s |\n' \
          "${STAGE_LABELS[$i]}" "${STAGE_SECONDS[$i]}" "${STAGE_STATUSES[$i]}"
      fi
    done
  } >> "$SUMMARY_PATH"
}

run_required() {
  local label="$1"
  local artifact="$2"
  shift 2
  local safe_label
  safe_label="$(safe_artifact_name "$label")"
  local log_file="$RUN_DIR/$safe_label.log"
  local start_seconds end_seconds elapsed_seconds status
  start_seconds="$(date +%s)"
  printf '\n[%s]\n' "$label"
  printf 'Artifact: %s\n' "$artifact"
  printf 'Log: %s\n' "$log_file"
  record_artifact "$label" "$artifact"
  record_artifact "$label log" "build/release-emulator-validation/$RUN_ID/$safe_label.log"
  set +e
  {
    printf '[%s] %s\n' "$(date -Is)" "$label"
    printf 'Command:'
    printf ' %q' "$@"
    printf '\n\n'
    "$@"
  } > "$log_file" 2>&1
  status="$?"
  set -e
  end_seconds="$(date +%s)"
  elapsed_seconds=$((end_seconds - start_seconds))
  STAGE_LABELS+=("$label")
  STAGE_SECONDS+=("$elapsed_seconds")
  if [[ "$status" -eq 0 ]]; then
    STAGE_STATUSES+=("passed")
    printf 'PASS: %s (%ss)\n' "$label" "$elapsed_seconds"
    return 0
  fi

  STAGE_STATUSES+=("failed")
  printf 'FAIL: %s exited %s after %ss\n' "$label" "$status" "$elapsed_seconds" >&2
  print_failure_log_tail "$log_file"
  {
    sed -i 's/^Automated status: RUNNING$/Automated status: FAIL/' "$SUMMARY_PATH"
    write_stage_timings
    fail "$label failed"
  }
}

# Issue #2064: turn the gate's recorded identity into the environment every
# downstream stage consumes, and verify the gate APKs are still the recorded
# bytes before a single stage is launched. Hard-fails: the gate we just ran
# always writes apk-identity.txt, so a missing/mismatched one means the release
# chain is not describing one binary and must not continue.
export_validated_apk_identity() {
  pocketshell_export_walkthrough_apk_env "$PRE_RELEASE_GATE_RUN_DIR" ||
    fail "the pre-release confidence gate did not leave a usable APK identity record at $PRE_RELEASE_GATE_RUN_DIR/$POCKETSHELL_APK_IDENTITY_FILE_NAME (issue #2064). Every downstream stage installs and ships that exact pair; without the record the chain cannot prove it validated the binary it publishes."
  {
    printf '\n## Validated APK identity (issue #2064)\n\n'
    printf -- '- app APK: `%s`\n' "$APP_APK"
    printf -- '- app APK sha256: `%s`\n' "$POCKETSHELL_EXPECTED_APP_APK_SHA256"
    printf -- '- androidTest APK: `%s`\n' "$TEST_APK"
    printf -- '- androidTest APK sha256: `%s`\n' "$POCKETSHELL_EXPECTED_TEST_APK_SHA256"
    printf -- '- Every walkthrough/visual-audit stage below installs THESE bytes (BUILD_APKS=0) and re-verifies the digest before installing.\n'
  } >> "$SUMMARY_PATH"
}

publish_validated_apk() {
  # Issue #2064: ship the RECORDED file, not a second hardcoded guess at where
  # the gate put it. `$PRE_RELEASE_GATE_APK` and the gate's `app_apk` record
  # were two independent derivations of the same path — if they ever diverged
  # (a variant/output-dir change, a suffixed applicationId, a relocated
  # worktree) the chain would validate one binary and publish another, with
  # nothing to notice. `$APP_APK` comes from the identity record and has already
  # been digest-verified by export_validated_apk_identity.
  local source_apk="${APP_APK:-$PRE_RELEASE_GATE_APK}"
  [[ -f "$source_apk" ]] ||
    fail "validated debug APK was not created by the pre-release gate at $source_apk"
  cp "$source_apk" "$VALIDATED_APK"
  # Issue #2064: the published artifact must be provably the validated one.
  pocketshell_assert_apk_identity "release publish" "$VALIDATED_APK" \
    "${POCKETSHELL_EXPECTED_APP_APK_SHA256:-}" ||
    fail "the published debug APK does not match the sha256 the pre-release gate validated (issue #2064)"
  record_artifact "tested debug APK" "build/release-emulator-validation/$RUN_ID/app2-debug.apk"
  printf -- '- published APK sha256: `%s`\n' "$POCKETSHELL_EXPECTED_APP_APK_SHA256" >> "$SUMMARY_PATH"
}

# Issue #2064: the device-free end-to-end proof of the APK identity chain.
# Runs the REAL export and the REAL publish assertion, so the test exercises
# production wiring rather than a re-spelling of it. No emulator, no Docker, no
# Gradle.
#
# Issue #2481: this used to also launch each downstream stage's own
# `--verify-apk-identity` mode as a child process. There are no downstream
# stages left — terminal-workbench, phone-walkthrough, capture-walkthrough-
# screenshots and parallel-setup-detection were all deleted with the `app`
# module's androidTest classes they drove. The chain's install site is now the
# pre-release confidence gate itself (its `verify-debug-apk-identity` step,
# which runs before `update-install-debug-apk`), and its publish site is
# publish_validated_apk below; scripts/test-release-apk-identity.sh pins both.
if [[ "${1:-}" == "--verify-apk-identity" ]]; then
  write_summary_header
  export_validated_apk_identity
  # Test-only boundary seam: prove the publish-time assertion catches a file
  # replaced after every install stage has passed. It is intentionally limited
  # to this device-free verification mode.
  if [[ -n "${POCKETSHELL_APK_IDENTITY_POST_STAGE_HOOK:-}" ]]; then
    [[ -x "$POCKETSHELL_APK_IDENTITY_POST_STAGE_HOOK" ]] ||
      fail "APK identity post-stage proof hook is not executable: $POCKETSHELL_APK_IDENTITY_POST_STAGE_HOOK"
    "$POCKETSHELL_APK_IDENTITY_POST_STAGE_HOOK" "$APP_APK" "$TEST_APK" ||
      fail "APK identity post-stage proof hook failed"
  fi
  publish_validated_apk
  printf '\nPASS: release APK identity chain verified (issue #2064)\n'
  printf 'Summary: %s\n' "$SUMMARY_PATH"
  exit 0
fi

require_clean_pushed_main
write_summary_header

# Issue #851 (epic #848): FAIL the release when the latest nightly fault /
# bootstrap run is red, cancelled, stale, or missing. The toxiproxy
# network-fault proofs + the bootstrap setup-scenario matrix run ONLY in the
# nightly "Nightly Extensive Tests" workflow, whose extensive job is
# `continue-on-error: true` — so a RED fault run masks as a `success` workflow
# conclusion. This guard inspects the EXTENSIVE-job conclusion (not the masked
# workflow conclusion) AND that the run covers the release HEAD, so a stale /
# cancelled / red fault run blocks the tag instead of silently passing.
#
# D37 (#2379): this guard is unconditional. There is no environment variable and
# no workflow input that skips it — waiving it was routine and is how the #1610
# reconnect storm shipped. Unblock by fixing the failure, or by quarantining that
# test/journey class through the D36(4) flake mechanism.
check_nightly_fault_run() {
  local log_dir="$RUN_DIR/nightly-fault-guard"
  mkdir -p "$log_dir"
  local log_file="$log_dir/result.txt"
  printf '\n## Nightly fault/bootstrap run guard (issue #851)\n\n' >> "$SUMMARY_PATH"
  record_artifact "nightly fault guard log" "$log_file"

  # D37 round 2 (#2379): pass ONLY --release-head. The guard's test-only knobs
  # (--fixture / --workflow / --job-needle) are flags, not environment
  # variables, exactly so this invocation cannot inherit a fabricated verdict
  # from whatever the release-owner exported; adding one here — or reviving an
  # `env NIGHTLY_FAULT_*=` prefix — fails
  # scripts/check-release-gate-bypass-absent.sh (C4-static) on the next push.
  local rc=0
  scripts/check-nightly-fault-run.sh --release-head "$(git rev-parse HEAD)" \
    >"$log_file" 2>&1 || rc=$?
  cat "$log_file"

  {
    printf '```\n'
    cat "$log_file"
    printf '```\n'
  } >> "$SUMMARY_PATH"

  if [[ "$rc" -ne 0 ]]; then
    sed -i 's/^Automated status: RUNNING$/Automated status: FAIL/' "$SUMMARY_PATH"
    fail "nightly fault/bootstrap run guard BLOCKED the release (see $log_file). Re-run 'Nightly Extensive Tests' (workflow_dispatch, force_run=true) on the release commit and wait for the fault-verdict job to go green, then re-run this gate. There is no override (D37): fix the failing test/journey, or quarantine that class through the D36(4) flake mechanism (auto-filed issue, non-blocking lane, 2-week expiry)."
  fi
}

check_nightly_fault_run

# The pre-release child records the wrapper as its retention owner because the
# wrapper still consumes both APKs during every downstream stage after the
# child exits successfully.
export POCKETSHELL_RELEASE_RETENTION_OWNER_PID="$$"
run_required \
  "pre-release confidence gate" \
  "build/pre-release-confidence-gate/$PRE_RELEASE_RUN_ID/" \
  env LOG_ROOT="$PRE_RELEASE_GATE_LOG_ROOT" RUN_ID="$PRE_RELEASE_RUN_ID" PRE_RELEASE_MANAGE_EMULATOR=1 scripts/pre-release-confidence-gate.sh

# Issue #2064: the gate builds ONE pair and records its identity; whatever runs
# after it installs THOSE bytes. Before this, terminal-lab / tmux-existing-session
# / setup-detection / visual-audit each wiped app/build and rebuilt their own
# byte-different pair (472s in run 20260809-v0442-r3, 603s in 20260808-165533),
# so the journey evidence the tag rests on was produced against a binary nothing
# else in the chain validated and that publish_validated_apk does not ship. The
# rebuild inside terminal-lab is also what OOM-killed release attempt r1, 58
# minutes in.
#
# Issue #2481: all four of those downstream stages are now DELETED, and the
# journey evidence moved INSIDE the pre-release gate — it installs the recorded
# pair and runs app2's whole instrumented set against it, so "the journeys ran
# on the published binary" is true by construction rather than by four separate
# identity re-checks. This export stays because publish_validated_apk still
# asserts the shipped file against the recorded digest.
export_validated_apk_identity

# Issue #2481: the four `run_required` stages that lived here — terminal-lab and
# tmux-existing-session phone walkthroughs, the 7-profile setup-detection matrix
# (sequential or SETUP_DETECTION_SHARDS-parallel), and the visual-audit
# screenshot capture — are GONE. scripts/phone-walkthrough.sh and
# scripts/parallel-setup-detection.sh were deleted with them; the visual pass
# survives as a STANDALONE tool (scripts/capture-walkthrough-screenshots.sh,
# repointed at app2's journey screenshots) but is no longer a chain stage,
# because it would be a SECOND full run of the same instrumented set the gate
# already ran against the validated pair. Not disabled, not stubbed (D22):
#
#   * terminal-lab drove `com.pocketshell.app.terminal.TerminalLabDockerTest`,
#   * tmux-existing-session drove
#     `com.pocketshell.app.proof.EmulatorDockerSshSmokeTest`,
#   * visual-audit drove WalkthroughVisualScreenshotTest,
#     WalkthroughConversationScreenshotTest and
#     PromptComposerVisualScreenshotTest,
#   * setup-detection drove
#     `com.pocketshell.app.bootstrap.HostBootstrapScenarioSuiteTest`,
#
# and every one of those classes was deleted with the `app` module. Three have a
# successor inside app2's own instrumented set (J03 attach-and-type, J02/J04
# session tree + create, J07/J08 composer + dictation), which the pre-release
# gate above now runs unfiltered against the validated pair — pulling the
# journey screenshots it renders, which is where the visual-audit artifact comes
# from now; the fourth does
# not, because the guided host-setup/bootstrap sheet is a CUT feature — only the
# actionable "update the host CLI" error survives (docs/rewrite-implementation-plan.md,
# "Scope amendment"). The gate still asserts the 2238 old-CLI fixture really is
# version-mismatched, so the non-happy host state has not stopped being tested.

record_artifact "app2 journey screenshots (visual audit)" \
  "build/pre-release-confidence-gate/$PRE_RELEASE_RUN_ID/journey-screenshots/"
publish_validated_apk
write_stage_timings

{
  printf '\n## Release Notes Checklist\n\n'
  printf -- '- [ ] Attach or link every artifact directory listed above in the issue and tag notes.\n'
  printf -- '- [ ] Download the tested debug APK from `release-emulator-validation/%s/app2-debug.apk` inside the validation artifact, or `build/release-emulator-validation/%s/app2-debug.apk` locally.\n' "$RUN_ID" "$RUN_ID"
  printf -- '- [ ] Inspect the app2 journey screenshots in `%s/` for release blockers (issue #2481: these replace the deleted visual-audit stage).\n' "$PRE_RELEASE_GATE_JOURNEY_SCREENSHOTS"
  printf -- '- [ ] If a screenshot is missing or unreadable, regenerate the visual pass on its own with `VISUAL_AUDIT_BUILD_APKS=0 APP_APK=%s TEST_APK=%s scripts/capture-walkthrough-screenshots.sh` — it hard-asserts one rendered frame per app2 journey.\n' "${APP_APK:-<validated app apk>}" "${TEST_APK:-<validated test apk>}"
  printf -- '- [ ] Treat physical phone testing as final user acceptance only; emulator/Docker validation catches basic release blockers before tagging.\n'
} >> "$SUMMARY_PATH"

sed -i 's/^Automated status: RUNNING$/Automated status: PASS/' "$SUMMARY_PATH"

printf '\nPASS: release emulator validation completed\n'
printf 'Summary: %s\n' "$SUMMARY_PATH"
