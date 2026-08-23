#!/usr/bin/env bash
# Bounded fake-ADB contract/mutation test for the host-owned two-phase harness.
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
HARNESS="$ROOT_DIR/scripts/two-phase-android-instrumentation.sh"
MUTATION_SCRIPT="$ROOT_DIR/scripts/two-phase-android-mutation.sh"
DURABILITY_MUTATION_SCRIPT="$ROOT_DIR/scripts/two-phase-android-durability-mutation.sh"
PROOF_SOURCE="$ROOT_DIR/app/src/androidTest/java/com/pocketshell/app/proof/LastSessionProcessRestartProofTest.kt"
READ_SOURCE="$ROOT_DIR/app/src/main/java/com/pocketshell/app/session/LastSessionStore.kt"
SCRATCH="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-two-phase-self-test.XXXXXX")"
MUTANT=""
cleanup() {
  rm -rf "$SCRATCH"
  [[ -z "$MUTANT" ]] || rm -f "$MUTANT"
}
trap cleanup EXIT

verify_real_mutation_contract() {
  local proof_read='        LastSessionStore(context).read(maxAgeMillis = Long.MAX_VALUE)'
  local production_read='        parse(nowMillis, maxAgeMillis)?.also { session ->'
  [[ -f "$PROOF_SOURCE" ]] || { echo "missing proof source: $PROOF_SOURCE" >&2; exit 1; }
  [[ -f "$READ_SOURCE" ]] || { echo "missing production read source: $READ_SOURCE" >&2; exit 1; }
  [[ -x "$MUTATION_SCRIPT" ]] || { echo "missing mutation runner: $MUTATION_SCRIPT" >&2; exit 1; }
  [[ "$(grep -Fc "$proof_read" "$PROOF_SOURCE")" == "1" ]] \
    || { echo 'real proof LastSessionStore.read call is not unique' >&2; exit 1; }
  [[ "$(grep -Fc "$production_read" "$READ_SOURCE")" == "1" ]] \
    || { echo 'production LastSessionStore.read mutation anchor is not unique' >&2; exit 1; }
  for mutation_token in \
    'MUTANT_ROOT=' \
    'BUILD_APKS=1' \
    'fixed-read-mutant' \
    'phase 2 did not report exactly one passing test' \
    'control_result=PASS' \
    'mutant_result=RED'; do
    grep -Fq "$mutation_token" "$MUTATION_SCRIPT" \
      || { echo "real APK mutation runner lost required token: $mutation_token" >&2; exit 1; }
  done
  if grep -Eq 'FAKE_PHASE2_READ_MUTANT|fixedPhaseTwoReadMutation' "$MUTATION_SCRIPT" "$PROOF_SOURCE"; then
    echo 'phase-2 mutation must not be a fake ADB/result or test-only fixed-read helper' >&2
    exit 1
  fi
  printf 'phase-two-read-apk-mutation=live\nproduction_source=%s\nrunner=%s\n' \
    "$READ_SOURCE" "$MUTATION_SCRIPT" > "$SCRATCH/mutation-contract.txt"
}

verify_durability_boundary_contract() {
  local phase_one_source
  [[ -x "$DURABILITY_MUTATION_SCRIPT" ]] \
    || { echo "missing durability mutation runner: $DURABILITY_MUTATION_SCRIPT" >&2; exit 1; }
  phase_one_source="$(sed -n \
    '/fun phaseOnePersistsExactSuccessorGeneration()/,/^    @Test$/p' \
    "$PROOF_SOURCE")"
  if grep -Eq '\.read\(' <<<"$phase_one_source"; then
    echo 'phase 1 must not read LastSessionStore before the external boundary' >&2
    exit 1
  fi
  for proof_token in \
    'ExternalKillBoundaryContext' \
    'DropAsyncApplySharedPreferences' \
    'only phase 2'
  do
    grep -Fq "$proof_token" "$PROOF_SOURCE" \
      || { echo "durability proof lost required token: $proof_token" >&2; exit 1; }
  done
  for mutation_token in \
    'WRITE_ANCHOR=' \
    'apply().let { true }' \
    'phase_one_external_boundary=true' \
    'phase2_started_pid=' \
    'mutant_result=RED'; do
    grep -Fq "$mutation_token" "$DURABILITY_MUTATION_SCRIPT" \
      || { echo "durability mutation runner lost required token: $mutation_token" >&2; exit 1; }
  done
  printf 'async-apply-boundary=deterministic\nrunner=%s\n' \
    "$DURABILITY_MUTATION_SCRIPT" > "$SCRATCH/durability-contract.txt"
}

verify_real_mutation_contract
verify_durability_boundary_contract
for production_token in \
  'SshHostTmuxSessionsGateway' \
  'gateway.listSessions' \
  'navigationTargetOrNull()'; do
  grep -Fq "$production_token" "$PROOF_SOURCE" \
    || { echo "proof source lost required real generation producer token: $production_token" >&2; exit 1; }
done
if grep -Eq 'SecureRandom|java\.util\.Random' "$PROOF_SOURCE"; then
  echo 'proof source must not synthesize tmux generations with Random/SecureRandom' >&2
  exit 1
fi

FAKE_ADB="$SCRATCH/adb"
FAKE_DEVICE="$SCRATCH/device"
FAKE_STATE="$SCRATCH/state"
mkdir -p "$FAKE_DEVICE" "$FAKE_STATE"
printf 'fresh\n' > "$FAKE_STATE/process-generation"

cat > "$FAKE_ADB" <<'FAKE'
#!/usr/bin/env bash
set -euo pipefail
device_root="${FAKE_DEVICE_ROOT:?}"
state_root="${FAKE_STATE_ROOT:?}"
if [[ "${1:-}" == "-s" ]]; then shift 2; fi
command_name="${1:-}"
shift || true
case "$command_name" in
  devices)
    printf 'List of devices attached\nemulator-2264\tdevice product:sdk model:test device:test transport_id:1\n'
    ;;
  install)
    printf 'Success\n'
    ;;
  uninstall)
    printf 'Success\n'
    ;;
  pull)
    source_path="$1"
    destination="$2"
    cp "$device_root$source_path" "$destination"
    printf '1 file pulled\n'
    ;;
  logcat)
    if [[ "${1:-}" == "-c" ]]; then exit 0; fi
    printf '08-21 12:00:00.000  test two-phase fake logcat\n'
    ;;
  shell)
    if [[ "${1:-}" == "getprop" ]]; then
      case "${2:-}" in
        ro.build.fingerprint) printf 'pocketshell/fake/fingerprint\n' ;;
        ro.boot.qemu.avd_name) printf 'fake-issue-2264\n' ;;
      esac
      exit 0
    fi
    if [[ "${1:-}" == "pm" && "${2:-}" == "path" ]]; then
      printf 'package:/data/app/%s/base.apk\n' "$3"
      exit 0
    fi
    if [[ "${1:-}" == "rm" ]]; then
      rm -rf "$device_root${2:-}"
      exit 0
    fi
    if [[ "${1:-}" == "test" && "${2:-}" == "-s" ]]; then
      [[ -s "$device_root${3:-}" ]]
      exit
    fi
    if [[ "${1:-}" == "test" && "${2:-}" == "-e" ]]; then
      pid="${3##*/}"
      case "${FAKE_PROC_CHECK_ERROR:-0}" in
        rc) exit 42 ;;
        output)
          printf 'error: device transport unavailable\n' >&2
          exit 1
          ;;
      esac
      if [[ "${FAKE_PRE_FORCE_STOP_PID_GONE:-0}" == "1" &&
            ! -e "$state_root/pre-force-stop-probe" ]]; then
        : > "$state_root/pre-force-stop-probe"
        exit 1
      fi
      [[ -f "$state_root/pid-$pid" ]]
      exit
    fi
    if [[ "${1:-}" == "cat" && "${2:-}" == /proc/*/cmdline ]]; then
      pid="${2#/proc/}"
      pid="${pid%%/*}"
      [[ -f "$state_root/pid-$pid" ]] || exit 1
      printf 'com.pocketshell.app.i2264\n'
      exit 0
    fi
    if [[ "${1:-}" == "am" && "${2:-}" == "force-stop" ]]; then
      package="${3:-}"
      case "$package" in
        com.pocketshell.app.i2264)
          rm -f "$state_root"/pid-*
          ;;
        com.pocketshell.app.i2264.test)
          :
          ;;
        *)
          printf 'unexpected force-stop package: %s\n' "$package" >&2
          exit 94
          ;;
      esac
      printf 'stopped %s\n' "$package" >> "$state_root/force-stops"
      exit 0
    fi
    if [[ "${1:-}" == "am" && "${2:-}" == "instrument" ]]; then
      selector=""
      namespace=""
      keepalive_millis=""
      previous=""
      for arg in "$@"; do
        if [[ "$previous" == "class" ]]; then selector="$arg"; previous=""; continue; fi
        if [[ "$previous" == "namespace" ]]; then namespace="$arg"; previous=""; continue; fi
        if [[ "$previous" == "keepalive" ]]; then keepalive_millis="$arg"; previous=""; continue; fi
        [[ "$arg" == "class" ]] && previous="class"
        [[ "$arg" == "pocketshellRunNamespace" ]] && previous="namespace"
        [[ "$arg" == "pocketshellPhaseOneKeepaliveMillis" ]] && previous="keepalive"
      done
      method="${selector##*#}"
      test_class="${selector%%#*}"
      package_name="com.pocketshell.app.i2264"
      output_dir="$device_root/sdcard/Android/media/$package_name/process-restart/$namespace"
      mkdir -p "$output_dir"
      if [[ "$method" == "phaseOnePersistsExactSuccessorGeneration" ]]; then
        [[ "$keepalive_millis" =~ ^[0-9]+$ && "$keepalive_millis" -gt 0 ]] || exit 94
        pid=22641
        printf 'alive\n' > "$state_root/pid-$pid"
        phase=1
        read -r successor_checksum _ < <(printf '%s' "$namespace:successor" | cksum)
        read -r predecessor_checksum _ < <(printf '%s' "$namespace:predecessor" | cksum)
        successor_id="\$$((successor_checksum + 1))"
        successor_created="$((1700000000 + successor_checksum % 500000000))"
        predecessor_id="\$$((predecessor_checksum + 1))"
        predecessor_created="$((1700000000 + predecessor_checksum % 500000000))"
        if [[ "${FAKE_SUCCESSOR_SAME_CREATED:-0}" == "1" ]]; then
          successor_created="$predecessor_created"
        fi
        if [[ "${FAKE_SUCCESSOR_SAME_ID:-0}" == "1" ]]; then
          successor_id="$predecessor_id"
        fi
        printf '%s\n' "$successor_id" > "$state_root/production-tmux-session-id"
        printf '%s\n' "$successor_created" > "$state_root/production-session-created"
        printf '%s\n' "$predecessor_id" > "$state_root/predecessor-tmux-session-id"
        printf '%s\n' "$predecessor_created" > "$state_root/predecessor-session-created"
      else
        if compgen -G "$state_root/pid-*" >/dev/null; then
          pid=22641
        else
          pid=22642
        fi
        printf 'alive\n' > "$state_root/pid-$pid"
        phase=2
        [[ -s "$state_root/production-tmux-session-id" ]] || exit 93
        [[ -s "$state_root/production-session-created" ]] || exit 93
        successor_id="$(<"$state_root/production-tmux-session-id")"
        successor_created="$(<"$state_root/production-session-created")"
        predecessor_id="$(<"$state_root/predecessor-tmux-session-id")"
        predecessor_created="$(<"$state_root/predecessor-session-created")"
      fi
      started_path="$output_dir/phase-$phase.started.txt"
      {
        printf 'schema=1\nrun_namespace=%s\nphase=%s\npid=%s\n' "$namespace" "$phase" "$pid"
        printf 'process_name=%s\ntarget_package=%s\ntest_package=%s.test\n' \
          "$package_name" "$package_name" "$package_name"
      } > "$started_path"
      artifact_path="$output_dir/phase-$phase.txt"
      producer_fixture_host='10.0.2.2'
      if [[ "${FAKE_PRODUCER_METADATA_MUTANT:-0}" == "1" ]]; then
        producer_fixture_host='10.0.2.99'
      fi
      {
        printf 'schema=1\nrun_namespace=%s\nphase=%s\npid=%s\n' "$namespace" "$phase" "$pid"
        printf 'process_name=%s\ntarget_package=%s\ntest_package=%s.test\n' "$package_name" "$package_name" "$package_name"
        printf 'generation_origin=agents-daemon-2239-tmux-list-sessions-through-SshHostTmuxSessionsGateway-to-navigation-to-on-stop-to-last-session-store\n'
        if [[ "$phase" == "1" ]]; then
          printf 'persistence_origin=LastSessionStore.save\n'
        else
          printf 'persistence_origin=LastSessionStore.read\n'
        fi
        printf 'producer_fixture_name=agents-daemon-2239\n'
        printf 'producer_fixture_host=%s\n' "$producer_fixture_host"
        printf 'producer_fixture_port=2239\n'
        printf 'producer_fixture_user=testuser\n'
        printf 'producer_key_path=/data/user/0/%s/cache/issue2264-key\n' "$package_name"
        printf 'producer_session_name=issue2264-%s\n' "$namespace"
        if [[ "${FAKE_PARTIAL_GENERATION:-0}" != "1" ||
              "${FAKE_PARTIAL_GENERATION_FIELD:-session_created}" != "tmux_session_id" ||
              "$phase" != "2" ]]; then
          printf 'tmux_session_id=%s\n' "$successor_id"
        fi
        if [[ "${FAKE_PARTIAL_GENERATION:-0}" != "1" ||
              "${FAKE_PARTIAL_GENERATION_FIELD:-session_created}" != "session_created" ||
              "$phase" != "2" ]]; then
          printf 'session_created=%s\n' "$successor_created"
        fi
        printf 'predecessor_tmux_session_id=%s\n' "$predecessor_id"
        printf 'predecessor_session_created=%s\n' "$predecessor_created"
        printf 'predecessor_reappeared=false\n'
        if [[ "${FAKE_DUPLICATE_PRODUCER_FIXTURE_NAME:-0}" == "1" ]]; then
          printf 'producer_fixture_name=duplicate-producer\n'
        fi
      } > "$artifact_path"
      if [[ "$phase" == "1" ]]; then
        artifact_bytes="$(wc -c < "$artifact_path" | tr -d '[:space:]')"
        artifact_sha256="$(sha256sum "$artifact_path" | awk '{print $1}')"
        {
          printf 'schema=1\nrun_namespace=%s\nphase=1\n' "$namespace"
          printf 'ready=true\nartifact=phase-1.txt\nartifact_complete=true\n'
          printf 'pid=%s\nprocess_name=%s\ntarget_package=%s\ntest_package=%s.test\n' \
            "$pid" "$package_name" "$package_name" "$package_name"
          printf 'artifact_bytes=%s\nartifact_sha256=%s\n' "$artifact_bytes" "$artifact_sha256"
        } > "$output_dir/phase-1.ready"
        # A phase-1 success transcript before the host's external boundary is
        # exactly the false proof this contract must reject. Stay alive until
        # the modeled target force-stop removes the PID marker, then report the
        # interrupted instrumentation result.
        while [[ -f "$state_root/pid-$pid" ]]; do
          sleep 0.05
        done
        printf 'INSTRUMENTATION_STATUS: class=%s\n' "$test_class"
        printf 'INSTRUMENTATION_STATUS: test=%s\n' "$method"
        printf 'INSTRUMENTATION_STATUS_CODE: -2\n'
        printf 'INSTRUMENTATION_RESULT: shortMsg=Process crashed\n'
        printf 'INSTRUMENTATION_FAILED: Process crashed\n'
        exit 1
      fi
      printf 'INSTRUMENTATION_STATUS: class=%s\n' "$test_class"
      printf 'INSTRUMENTATION_STATUS: test=%s\n' "$method"
      printf 'INSTRUMENTATION_STATUS_CODE: 1\n'
      printf 'INSTRUMENTATION_STATUS_CODE: 0\n'
      printf 'OK (1 test)\n'
      printf 'INSTRUMENTATION_CODE: -1\n'
      exit 0
    fi
    printf 'unexpected fake shell command: %q ' "$@" >&2
    exit 91
    ;;
  *)
    printf 'unexpected fake adb command: %s\n' "$command_name" >&2
    exit 92
    ;;
esac
FAKE
chmod +x "$FAKE_ADB"

APP_APK="$SCRATCH/app.apk"
TEST_APK="$SCRATCH/test.apk"
printf 'app\n' > "$APP_APK"
printf 'test\n' > "$TEST_APK"

run_harness() {
  local script="$1" run_name="$2"
  shift 2
  env \
    ADB="$FAKE_ADB" \
    ANDROID_SERIAL=emulator-2264 \
    POCKETSHELL_AVD_LOCK_DIR="$SCRATCH/locks-$run_name" \
    FAKE_DEVICE_ROOT="$FAKE_DEVICE-$run_name" \
    FAKE_STATE_ROOT="$FAKE_STATE-$run_name" \
    BUILD_APKS=0 \
    APP_APK="$APP_APK" \
    TEST_APK="$TEST_APK" \
    SUFFIX=i2264 \
    RUN_NAMESPACE="$run_name" \
    RUN_DIR="$SCRATCH/evidence-$run_name" \
    "$@" \
    bash "$script"
}

reset_fake() {
  local run_name="$1"
  mkdir -p "$FAKE_DEVICE-$run_name" "$FAKE_STATE-$run_name"
}

reset_fake baseline
if ! run_harness "$HARNESS" baseline > "$SCRATCH/baseline.log" 2>&1; then
  cat "$SCRATCH/baseline.log" >&2
  exit 1
fi
grep -Fq 'result=PASS' "$SCRATCH/evidence-baseline/summary.txt"
grep -Fq 'pid_changed=true' "$SCRATCH/evidence-baseline/summary.txt"
grep -Fqx 'producer_fixture_name=agents-daemon-2239' "$SCRATCH/evidence-baseline/summary.txt"
grep -Fqx 'producer_fixture_host=10.0.2.2' "$SCRATCH/evidence-baseline/summary.txt"
grep -Fqx 'producer_fixture_port=2239' "$SCRATCH/evidence-baseline/summary.txt"
grep -Fqx 'producer_fixture_user=testuser' "$SCRATCH/evidence-baseline/summary.txt"
grep -Fqx 'persistence_origin=LastSessionStore.read' "$SCRATCH/evidence-baseline/phase-2.txt"
baseline_phase1_id="$(sed -n 's/^tmux_session_id=//p' "$SCRATCH/evidence-baseline/phase-1.txt")"
baseline_phase2_id="$(sed -n 's/^tmux_session_id=//p' "$SCRATCH/evidence-baseline/phase-2.txt")"
[[ "$baseline_phase1_id" == "$baseline_phase2_id" ]]
[[ "$baseline_phase1_id" != "\$2264-new" ]]
[[ "$(grep -c 'event=install_target_once' "$SCRATCH/evidence-baseline/package-mutations.log")" == "1" ]]
[[ "$(grep -c 'event=install_test_once' "$SCRATCH/evidence-baseline/package-mutations.log")" == "1" ]]
grep -Fqx 'ready=true' "$SCRATCH/evidence-baseline/phase-1.ready"
grep -Fqx 'artifact=phase-1.txt' "$SCRATCH/evidence-baseline/phase-1.ready"
grep -Fqx 'artifact_complete=true' "$SCRATCH/evidence-baseline/phase-1.ready"
grep -Fqx 'pid=22641' "$SCRATCH/evidence-baseline/phase-1.ready"
phase1_ready_bytes="$(sed -n 's/^artifact_bytes=//p' "$SCRATCH/evidence-baseline/phase-1.ready")"
[[ "$phase1_ready_bytes" == "$(wc -c < "$SCRATCH/evidence-baseline/phase-1.txt" | tr -d '[:space:]')" ]]
phase1_ready_sha="$(sed -n 's/^artifact_sha256=//p' "$SCRATCH/evidence-baseline/phase-1.ready")"
[[ "$phase1_ready_sha" == "$(sha256sum "$SCRATCH/evidence-baseline/phase-1.txt" | awk '{print $1}')" ]]
grep -Fqx 'INSTRUMENTATION_STATUS_CODE: -2' \
  "$SCRATCH/evidence-baseline/phase-1-instrumentation.log"
grep -Fqx 'INSTRUMENTATION_RESULT: shortMsg=Process crashed' \
  "$SCRATCH/evidence-baseline/phase-1-instrumentation.log"
if grep -Fqx 'INSTRUMENTATION_CODE: -1' "$SCRATCH/evidence-baseline/phase-1-instrumentation.log" || \
  grep -Fqx 'OK (1 test)' "$SCRATCH/evidence-baseline/phase-1-instrumentation.log"; then
  echo 'baseline phase-1 instrumentation was allowed to report natural success' >&2
  exit 1
fi

# Pair-difference control: a same-second recreation may legitimately retain
# session_created while tmux advances the session id. The oracle must accept a
# pair with one changed field; requiring both fields to change is the original
# false-negative reviewer found.
reset_fake single-field-generation
if ! run_harness "$HARNESS" single-field-generation FAKE_SUCCESSOR_SAME_CREATED=1 \
  > "$SCRATCH/single-field-generation.log" 2>&1; then
  cat "$SCRATCH/single-field-generation.log" >&2
  exit 1
fi
grep -Fq 'result=PASS' "$SCRATCH/evidence-single-field-generation/summary.txt"
grep -Fqx 'exact_generation_survived=true' \
  "$SCRATCH/evidence-single-field-generation/summary.txt"

# Mutation M1: delete the external force-stop boundary. The fake device reuses
# phase 1's PID, so unchanged-PID and absent force-stop evidence both fail shut.
MUTANT="$(mktemp "$ROOT_DIR/scripts/.two-phase-no-force-stop.XXXXXX.sh")"
cp "$HARNESS" "$MUTANT"
# The literal shell variable is the mutation anchor in the copied harness.
# shellcheck disable=SC2016
sed -i 's/^force_stop_between_phases "\$PHASE1_PID"$/# MUTANT: force-stop skipped/' "$MUTANT"
grep -Fqx '# MUTANT: force-stop skipped' "$MUTANT"
reset_fake no-force-stop
set +e
run_harness "$MUTANT" no-force-stop PHASE1_REAP_WAIT_SECONDS=1 \
  > "$SCRATCH/no-force-stop.log" 2>&1
no_force_stop_rc=$?
set -e
[[ "$no_force_stop_rc" -ne 0 ]] || { echo 'force-stop mutant survived' >&2; exit 1; }
grep -Eq 'did not terminate after the two external force-stops|no process restart occurred|external force-stop evidence is absent' \
  "$SCRATCH/no-force-stop.log"
rm -f "$MUTANT"
MUTANT=""

# Mutation M1b: redirect the target force-stop to the test package. The fake
# device models the two package lifetimes separately, so a harness that only
# checks that some force-stop command ran would wrongly accept the live target
# PID. Keep the wait bounded for this negative case.
MUTANT="$(mktemp "$ROOT_DIR/scripts/.two-phase-wrong-force-stop.XXXXXX.sh")"
cp "$HARNESS" "$MUTANT"
# shellcheck disable=SC2016
sed -i 's/adb_mutate shell am force-stop "\$TARGET_PACKAGE"/adb_mutate shell am force-stop "\$TEST_PACKAGE"/' "$MUTANT"
# shellcheck disable=SC2016
grep -Fq '  adb_mutate shell am force-stop "$TEST_PACKAGE"' "$MUTANT"
reset_fake wrong-force-stop
set +e
run_harness "$MUTANT" wrong-force-stop FORCE_STOP_WAIT_SECONDS=0 \
  > "$SCRATCH/wrong-force-stop.log" 2>&1
wrong_force_stop_rc=$?
set -e
[[ "$wrong_force_stop_rc" -ne 0 ]] || { echo 'wrong-package force-stop mutant survived' >&2; exit 1; }
grep -Fq 'survived external force-stop' "$SCRATCH/wrong-force-stop.log"
grep -Fq 'stopped com.pocketshell.app.i2264.test' "$FAKE_STATE-wrong-force-stop/force-stops"
rm -f "$MUTANT"
MUTANT=""

# Mutation M2: phase 2 emits only one generation field. The harness must reject
# the partial artifact before it can synthesize a passing summary.
reset_fake partial-generation
set +e
run_harness "$HARNESS" partial-generation FAKE_PARTIAL_GENERATION=1 \
  > "$SCRATCH/partial-generation.log" 2>&1
partial_generation_rc=$?
set -e
[[ "$partial_generation_rc" -ne 0 ]] || { echo 'partial-generation mutant survived' >&2; exit 1; }
grep -Fq 'must contain exactly one session_created field' "$SCRATCH/partial-generation.log"

reset_fake partial-generation-id
set +e
run_harness "$HARNESS" partial-generation-id \
  FAKE_PARTIAL_GENERATION=1 FAKE_PARTIAL_GENERATION_FIELD=tmux_session_id \
  > "$SCRATCH/partial-generation-id.log" 2>&1
partial_generation_id_rc=$?
set -e
[[ "$partial_generation_id_rc" -ne 0 ]] || { echo 'partial-generation-id mutant survived' >&2; exit 1; }
grep -Fq 'must contain exactly one tmux_session_id field' \
  "$SCRATCH/partial-generation-id.log"

# Mutation M3: duplicate producer fixture identity is not accepted. A phase
# artifact has one producer_fixture_name plus its metadata; duplicate identity
# fields make provenance ambiguous and must fail before the generation oracle.
reset_fake duplicate-producer
set +e
run_harness "$HARNESS" duplicate-producer FAKE_DUPLICATE_PRODUCER_FIXTURE_NAME=1 \
  > "$SCRATCH/duplicate-producer.log" 2>&1
duplicate_producer_rc=$?
set -e
[[ "$duplicate_producer_rc" -ne 0 ]] || { echo 'duplicate producer fixture mutant survived' >&2; exit 1; }
grep -Fq 'must contain exactly one producer_fixture_name field' \
  "$SCRATCH/duplicate-producer.log"

# Mutation M4: producer metadata no longer identifies the agents-daemon
# fixture. The name alone is insufficient; the host metadata must agree too.
reset_fake producer-metadata
set +e
run_harness "$HARNESS" producer-metadata FAKE_PRODUCER_METADATA_MUTANT=1 \
  > "$SCRATCH/producer-metadata.log" 2>&1
producer_metadata_rc=$?
set -e
[[ "$producer_metadata_rc" -ne 0 ]] || { echo 'producer metadata mutant survived' >&2; exit 1; }
grep -Fq 'producer fixture host is not 10.0.2.2' "$SCRATCH/producer-metadata.log"

# Mutation M5: the phase-1 process dies before the host reaches its external
# force-stop call. A later new PID is not proof that am force-stop caused the
# restart; the harness must reject this run before issuing either stop.
reset_fake pre-force-stop-gone
set +e
run_harness "$HARNESS" pre-force-stop-gone FAKE_PRE_FORCE_STOP_PID_GONE=1 \
  > "$SCRATCH/pre-force-stop-gone.log" 2>&1
pre_force_stop_gone_rc=$?
set -e
[[ "$pre_force_stop_gone_rc" -ne 0 ]] || { echo 'pre-force-stop-dead mutant survived' >&2; exit 1; }
grep -Fq 'was not alive before external force-stop' "$SCRATCH/pre-force-stop-gone.log"
[[ ! -s "$FAKE_STATE-pre-force-stop-gone/force-stops" ]]

# Mutation M6: an adb/transport failure while probing /proc is unknown state,
# never evidence that the old process is gone. The harness must fail closed.
reset_fake proc-check-error
set +e
run_harness "$HARNESS" proc-check-error FAKE_PROC_CHECK_ERROR=rc \
  > "$SCRATCH/proc-check-error.log" 2>&1
proc_check_error_rc=$?
set -e
[[ "$proc_check_error_rc" -ne 0 ]] || { echo 'adb /proc transport-error mutant survived' >&2; exit 1; }
grep -Fq 'cannot determine whether phase-1 PID 22641 exists' "$SCRATCH/proc-check-error.log"
grep -Fq 'adb exited 42' "$SCRATCH/proc-check-error.log"

# Mutation M7: even rc=1 means "absent" only when adb/toybox is silent. Error
# output is an indeterminate probe and must not be laundered into "PID gone".
reset_fake proc-check-output
set +e
run_harness "$HARNESS" proc-check-output FAKE_PROC_CHECK_ERROR=output \
  > "$SCRATCH/proc-check-output.log" 2>&1
proc_check_output_rc=$?
set -e
[[ "$proc_check_output_rc" -ne 0 ]] || { echo 'adb /proc output-error mutant survived' >&2; exit 1; }
grep -Fq 'unexpected adb output: error: device transport unavailable' "$SCRATCH/proc-check-output.log"

# Static no-reset guard for the load-bearing interval.
if grep -Eq '^[[:space:]]*(adb_cmd|adb_mutate).*(pm([[:space:]]|\\ )+clear([[:space:]]|\\ |$)|cmd([[:space:]]|\\ )+package([[:space:]]|\\ )+clear([[:space:]]|\\ |$))' "$HARNESS"; then
  echo 'host harness contains a package-state clear command' >&2
  exit 1
fi
if grep -Fq 'connected-test.sh' "$HARNESS"; then
  echo 'host harness delegates to connected-test cleanup' >&2
  exit 1
fi
if grep -Eq 'write_junit_xml|<testsuite|phase-[12]-junit' "$HARNESS"; then
  echo 'host harness fabricates a JUnit runtime artifact' >&2
  exit 1
fi

reset_fake restored
if ! run_harness "$HARNESS" restored > "$SCRATCH/restored.log" 2>&1; then
  cat "$SCRATCH/restored.log" >&2
  exit 1
fi
grep -Fq 'result=PASS' "$SCRATCH/evidence-restored/summary.txt"

printf 'two-phase harness self-test: PASS (baseline green; single-field-generation green; no-force-stop red rc=%s; wrong-force-stop red rc=%s; partial-created red rc=%s; partial-id red rc=%s; duplicate-producer red rc=%s; producer-metadata red rc=%s; pre-force-stop-dead red rc=%s; proc-rc red rc=%s; proc-output red rc=%s; restored green)\n' \
  "$no_force_stop_rc" "$wrong_force_stop_rc" "$partial_generation_rc" "$partial_generation_id_rc" \
  "$duplicate_producer_rc" "$producer_metadata_rc" "$pre_force_stop_gone_rc" \
  "$proc_check_error_rc" "$proc_check_output_rc"
