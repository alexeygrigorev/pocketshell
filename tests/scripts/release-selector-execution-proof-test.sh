#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
PRE_RELEASE_GATE="$ROOT_DIR/scripts/pre-release-confidence-gate.sh"
CHECKER="$ROOT_DIR/scripts/check-release-selector-execution.sh"
PRE_RELEASE_GATE="${POCKETSHELL_RELEASE_SELECTOR_PRE_RELEASE_GATE:-$PRE_RELEASE_GATE}"
CHECKER="${POCKETSHELL_RELEASE_SELECTOR_CHECKER:-$CHECKER}"
source "$ROOT_DIR/scripts/lib/instrumentation-evidence.sh"
FIXTURE_ROOT="$(mktemp -d)"
trap 'find "$FIXTURE_ROOT" -type f -delete; find "$FIXTURE_ROOT" -depth -type d -empty -delete' EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

run_capture_terminal_lab_finalizer_oracle() (
  local capture_script="$1"
  local expected_checker_result="$2"
  local probe_name="$3"
  local probe_root="$FIXTURE_ROOT/live-capture-$probe_name"
  local run_dir="$probe_root/run"
  local test_class="com.example.FakeTest"
  local selector="$test_class"
  local attendance_file="$run_dir/selector-attendance.tsv"
  local required_file="$run_dir/required-selectors.txt"
  local run_start_marker="$run_dir/selector-run-start.marker"
  local log_file="$run_dir/11-run-terminal-lab-instrumentation.log"
  local checker="$probe_root/scripts/check-release-selector-execution.sh"
  local checker_marker="$probe_root/checker-invoked"

  mkdir -p "$probe_root/scripts" "$run_dir"
  {
    printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
    printf 'printf "%%s\\n" invoked > %q\n' "$checker_marker"
    printf 'exit %d\n' "$expected_checker_result"
  } > "$checker"
  chmod +x "$checker"

  pocketshell_initialize_release_selector_attendance \
    "$attendance_file" live-run "$run_start_marker" "$test_class"
  printf '%s\n' "$selector" > "$required_file"
  sleep 0.02
  printf '%s\n' \
    'INSTRUMENTATION_STATUS: class=com.example.FakeTest' \
    'INSTRUMENTATION_STATUS: test=terminalLabSelector' \
    'INSTRUMENTATION_STATUS_CODE: 0' \
    'INSTRUMENTATION_CODE: -1' \
    'OK (1 test)' > "$log_file"

  eval "$(sed -n '/^verify_terminal_lab_selector_attendance()/,/^}/p' "$capture_script")"
  ROOT_DIR="$probe_root"
  RUN_DIR="$run_dir"
  RUN_ID=live-run
  TEST_CLASS="$test_class"
  SELECTOR_ATTENDANCE_FILE="$attendance_file"
  SELECTOR_REQUIRED_FILE="$required_file"
  SELECTOR_RUN_START_MARKER="$run_start_marker"

  if [[ "$expected_checker_result" -eq 0 ]]; then
    verify_terminal_lab_selector_attendance || return 1
  else
    local actual_checker_result=0
    if verify_terminal_lab_selector_attendance; then
      return 1
    else
      actual_checker_result=$?
    fi
    [[ "$actual_checker_result" -eq "$expected_checker_result" ]] || return 1
  fi
  [[ -s "$checker_marker" ]] || return 1
  awk -F '\t' -v selector="$selector" \
    '$1 == "selector" && $2 == selector && $3 == "1" { found = 1 }
     END { exit(found ? 0 : 1) }' "$attendance_file"
)

capture_terminal_lab_live_checker_mutation_is_rejected() (
  local mutant="$FIXTURE_ROOT/capture-terminal-lab-live-checker-mutant.sh"
  awk '
    {
      line = $0
      sub(/^[[:space:]]*/, "", line)
      if (!replaced && line ~ /^pocketshell_run_release_selector_checker/) {
        print "  # The checker token is retained only in unreachable code."
        print "  if false; then"
        print "    \"$ROOT_DIR/scripts/check-release-selector-execution.sh\" --help"
        print "  fi"
        print "  true"
        replaced = 1
        skip = 6
        next
      }
      if (skip > 0) {
        skip--
        next
      }
      print
    }
    END { exit(replaced ? 0 : 1) }
  ' "$ROOT_DIR/scripts/capture-terminal-lab.sh" > "$mutant"
  [[ -s "$mutant" ]] || return 1
  grep -Fq 'The checker token is retained only in unreachable code.' "$mutant" || return 1
  local mutant_result=0
  run_capture_terminal_lab_finalizer_oracle "$mutant" 0 live-mutant || mutant_result=$?
  [[ "$mutant_result" -eq 1 ]] || return 1
  printf 'PASS: live checker dead/true mutation rc=%s\n' "$mutant_result"
)

# Exercise the actual release-wrapper functions in the contexts that suppress
# errexit: run_ssh_smoke_step is called from `if ! ...`, while the two
# release-emulator-validation functions are passed to run_required after it
# enters `set +e`. A bare checker/attendance call followed by return 0 must
# therefore be observable as a failed production step, not only as a source
# token or a direct helper-function result.
run_release_selector_context_oracle() (
  local context="$1"
  local failure_mode="$2"
  local expected_status="$3"
  local probe_root="$FIXTURE_ROOT/release-context-$context-$failure_mode"
  local bin_dir="$probe_root/bin"
  local scripts_dir="$probe_root/scripts"
  local key_file="$probe_root/tests/docker/test_key"
  local checker="$scripts_dir/check-release-selector-execution.sh"
  local checker_marker="$probe_root/checker-invoked"
  local record_marker="$probe_root/attendance-record-invoked"
  local output_file="$probe_root/output.log"
  local instrumentation_fixture="$probe_root/instrumentation.log"
  local step_status_file="$probe_root/step-status"
  local actual_status=0
  local selector

  mkdir -p "$bin_dir" "$scripts_dir" "$(dirname "$key_file")"
  printf 'fixture-key\n' > "$key_file"

  {
    printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
    printf 'printf "%%s\\n" invoked > %q\n' "$checker_marker"
    if [[ "$failure_mode" == "checker-failure" ]]; then
      printf 'exit 17\n'
    else
      printf 'exit 0\n'
    fi
  } > "$checker"
  chmod +x "$checker"

  cat > "$bin_dir/docker" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
exit 0
EOF
  cat > "$bin_dir/ssh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' 'fixture ssh ready'
exit 0
EOF

  case "$context" in
    terminal)
      selector='com.pocketshell.app.proof.EmulatorDockerSshSmokeTest#debugAppConnectsToDockerAgentTargetViaEmulatorHostAlias'
      ;;
    real-agent)
      selector='com.pocketshell.app.proof.RealAgentReleaseGateTest'
      ;;
    long-running)
      selector='com.pocketshell.app.proof.LongRunningSessionStabilityTest'
      ;;
    *)
      return 1
      ;;
  esac

  local selector_class="${selector%%#*}"
  local selector_method=""
  if [[ "$selector" == *'#'* ]]; then
    selector_method="${selector#*#}"
  fi
  {
    printf 'INSTRUMENTATION_STATUS: class=%s\n' "$selector_class"
    [[ -z "$selector_method" ]] || printf 'INSTRUMENTATION_STATUS: test=%s\n' "$selector_method"
    printf '%s\n' \
      'INSTRUMENTATION_STATUS_CODE: 0' \
      'INSTRUMENTATION_CODE: -1' \
      'OK (1 test)'
  } > "$instrumentation_fixture"

  {
    printf '%s\n' '#!/usr/bin/env bash' 'set -euo pipefail'
    printf 'if [[ "${1:-}" == shell && "${2:-}" == am && "${3:-}" == instrument ]]; then\n'
    printf '  cat %q\n' "$instrumentation_fixture"
    printf 'fi\n'
    printf 'exit 0\n'
  } > "$bin_dir/adb"
  chmod +x "$bin_dir/docker" "$bin_dir/ssh" "$bin_dir/adb"

  export PATH="$bin_dir:$PATH"
  ROOT_DIR="$probe_root"
  RUN_ID="fixture-run"
  RUN_DIR="$probe_root/run"
  WORKBENCH_LOG_ROOT="$probe_root/workbench"
  DETERMINISTIC_COMPOSE_FILE="$probe_root/compose.yml"
  ADB="$bin_dir/adb"
  mkdir -p "$RUN_DIR" "$WORKBENCH_LOG_ROOT"

  wait_for_container_healthy() {
    printf '%s\n' 'healthy' > "$3"
    return 0
  }
  record_step() {
    printf '%s\n' "$2" > "$step_status_file"
  }

  case "$context" in
    terminal)
      eval "$(sed -n '/^run_ssh_smoke_step()/,/^}/p' "$RELEASE_TERMINAL_GATE")"
      ;;
    real-agent)
      REAL_AGENT_RELEASE_GATE_RUN_ID="fixture-real-agent"
      REAL_AGENT_RELEASE_GATE_RUN_DIR="$RUN_DIR"
      REAL_AGENT_RELEASE_GATE_TEST_CLASS="$selector_class"
      REAL_AGENT_RELEASE_GATE_INSTRUMENTATION_ATTEMPTS=1
      REAL_AGENT_COMPOSE_FILE="$probe_root/real-agent-compose.yml"
      eval "$(sed -n '/^run_real_agent_release_gate_instrumentation()/,/^}/p' "$RELEASE_EMULATOR_VALIDATION")"
      ;;
    long-running)
      LONG_RUNNING_TEST_RUN_ID="fixture-long-running"
      LONG_RUNNING_TEST_RUN_DIR="$RUN_DIR"
      LONG_RUNNING_TEST_CLASS="$selector_class"
      LONG_RUNNING_TEST_INSTRUMENTATION_ATTEMPTS=1
      LONG_RUNNING_COMPOSE_FILE="$probe_root/long-running-compose.yml"
      eval "$(sed -n '/^run_long_running_session_instrumentation()/,/^}/p' "$RELEASE_EMULATOR_VALIDATION")"
      run_long_running_session_detached_instrumentation_attempt() {
        cp "$instrumentation_fixture" "$2"
        return 0
      }
      ;;
  esac

  if [[ "$failure_mode" == "attendance-failure" ]]; then
    pocketshell_record_release_selector_attendance() {
      printf '%s\n' invoked > "$record_marker"
      return 23
    }
  fi

  case "$context" in
    terminal)
      # This conditional call reproduces the `if ! run_ssh_smoke_step` caller.
      if run_ssh_smoke_step > "$output_file" 2>&1; then
        actual_status=0
      else
        actual_status="$?"
      fi
      ;;
    real-agent)
      # run_required has set +e around this exact function invocation.
      set +e
      run_real_agent_release_gate_instrumentation > "$output_file" 2>&1
      actual_status="$?"
      set -e
      ;;
    long-running)
      set +e
      run_long_running_session_instrumentation > "$output_file" 2>&1
      actual_status="$?"
      set -e
      ;;
  esac

  [[ "$actual_status" == "$expected_status" ]] || {
    cat "$output_file" >&2
    printf 'expected %s/%s to return %s, got %s\n' \
      "$context" "$failure_mode" "$expected_status" "$actual_status" >&2
    return 1
  }

  if [[ "$failure_mode" == "attendance-failure" ]]; then
    [[ -s "$record_marker" ]] || return 1
    [[ ! -e "$checker_marker" ]] || {
      printf '%s/%s invoked the checker after attendance recording failed\n' \
        "$context" "$failure_mode" >&2
      return 1
    }
  else
    [[ -s "$checker_marker" ]] || {
      printf '%s/%s did not execute the checker\n' "$context" "$failure_mode" >&2
      return 1
    }
  fi

  if [[ "$context" == "terminal" ]]; then
    local expected_step_status=PASS
    [[ "$failure_mode" == "success" ]] || expected_step_status=FAIL
    grep -Fqx "$expected_step_status" "$step_status_file" || {
      printf '%s/%s recorded step status other than %s\n' \
        "$context" "$failure_mode" "$expected_step_status" >&2
      return 1
    }
  fi
)

run_release_selector_context_matrix() {
  local context
  for context in terminal real-agent long-running; do
    run_release_selector_context_oracle "$context" success 0 || return 1
    run_release_selector_context_oracle "$context" checker-failure 17 || return 1
    run_release_selector_context_oracle "$context" attendance-failure 23 || return 1
  done
  printf 'PASS: release-wrapper attendance/checker failures propagate in all three production contexts\n'
}

# Kill the exact false-green mutation that turns both new failure branches into
# dead code. The oracle must fail against each mutated production function; a
# static grep for status-variable names would not prove this.
release_wrapper_propagation_mutation_is_rejected() (
  local mutant_root="$FIXTURE_ROOT/release-wrapper-propagation-mutants"
  local mutant_terminal="$mutant_root/release-terminal-gate.sh"
  local mutant_emulator="$mutant_root/release-emulator-validation.sh"
  mkdir -p "$mutant_root"
  awk '
    /^  if \[\[ "\$attendance_status" -ne 0 \]\]; then$/ ||
    /^  if \[\[ "\$checker_status" -ne 0 \]\]; then$/ {
      print "  if false; then"
      next
    }
    { print }
  ' "$RELEASE_TERMINAL_GATE" > "$mutant_terminal"
  awk '
    /^  if \[\[ "\$attendance_status" -ne 0 \]\]; then$/ ||
    /^  if \[\[ "\$checker_status" -ne 0 \]\]; then$/ {
      print "  if false; then"
      next
    }
    { print }
  ' "$RELEASE_EMULATOR_VALIDATION" > "$mutant_emulator"
  grep -Fq 'if false; then' "$mutant_terminal" || return 1
  grep -Fq 'if false; then' "$mutant_emulator" || return 1

  RELEASE_TERMINAL_GATE="$mutant_terminal"
  RELEASE_EMULATOR_VALIDATION="$mutant_emulator"
  local context failure_mode expected_status mutation_output
  for context in terminal real-agent long-running; do
    for failure_mode in checker-failure attendance-failure; do
      if [[ "$failure_mode" == checker-failure ]]; then
        expected_status=17
      else
        expected_status=23
      fi
      mutation_output="$mutant_root/$context-$failure_mode.log"
      if run_release_selector_context_oracle \
        "$context" "$failure_mode" "$expected_status" > "$mutation_output" 2>&1; then
        printf 'FAIL: %s/%s propagation mutation survived\n' "$context" "$failure_mode" >&2
        return 1
      fi
      grep -Fq "expected $context/$failure_mode to return $expected_status, got 0" \
        "$mutation_output" || {
        cat "$mutation_output" >&2
        printf 'FAIL: %s/%s propagation mutation failed for an unexpected reason\n' \
          "$context" "$failure_mode" >&2
        return 1
      }
    done
  done
  printf 'PASS: dead propagation branches are killed for all three release-wrapper contexts\n'
)

[[ -x "$CHECKER" ]] || fail "release selector execution checker is missing or not executable"

run_capture_terminal_lab_finalizer_oracle \
  "$ROOT_DIR/scripts/capture-terminal-lab.sh" 0 live-success ||
  fail "the live terminal-lab finalizer did not execute a successful fake checker"
run_capture_terminal_lab_finalizer_oracle \
  "$ROOT_DIR/scripts/capture-terminal-lab.sh" 17 live-failure ||
  fail "the live terminal-lab finalizer did not propagate a failing checker result"
capture_terminal_lab_live_checker_mutation_is_rejected ||
  fail "the live terminal-lab checker dead/true mutation was accepted"
printf 'PASS: live terminal-lab checker invocation, result propagation, and mutation rejection\n'
RELEASE_TERMINAL_GATE="$ROOT_DIR/scripts/release-terminal-gate.sh"
RELEASE_EMULATOR_VALIDATION="$ROOT_DIR/scripts/release-emulator-validation.sh"
run_release_selector_context_matrix ||
  fail "a release-wrapper attendance/checker failure was swallowed in a production-shaped context"
release_wrapper_propagation_mutation_is_rejected ||
  fail "the release-wrapper propagation mutation was not killed"

release_selector_gates=(
  scripts/pre-release-confidence-gate.sh
  scripts/release-emulator-validation.sh
  scripts/release-terminal-gate.sh
  scripts/capture-terminal-lab.sh
  scripts/capture-walkthrough-screenshots.sh
  scripts/phone-walkthrough.sh
  scripts/terminal-workbench.sh
  scripts/tmux-attach-prefill.sh
  scripts/reconnect-app-switch.sh
  scripts/keyboard-stress.sh
  scripts/issue78-phone-walkthrough.sh
  scripts/tmux-issue303-toolbar-proof.sh
)
for gate in "${release_selector_gates[@]}"; do
  [[ -f "$ROOT_DIR/$gate" ]] || fail "release selector gate is missing: $gate"
  grep -Fq 'scripts/lib/instrumentation-evidence.sh' "$ROOT_DIR/$gate" ||
    fail "$gate does not source the shared instrumentation evidence helper"
  grep -Fq 'pocketshell_instrumentation_assert_log' "$ROOT_DIR/$gate" ||
    fail "$gate has no exact positive selector assertion"
  # This is only a wiring inventory. Semantic checker invocation and result
  # propagation are exercised through the executable capture finalizer oracle
  # below, rather than by looking for a checker pathname in source text.
  grep -Fq 'pocketshell_run_release_selector_checker' "$ROOT_DIR/$gate" ||
    fail "$gate does not route current-run attendance through the shared checker wrapper"
  if grep -Fq 'grep -q "OK ("' "$ROOT_DIR/$gate" ||
    grep -Fq "grep -q 'OK ('" "$ROOT_DIR/$gate"; then
    fail "$gate still accepts a broad OK summary instead of the shared positive-count parser"
  fi
done

# The two-phase process-boundary harness is intentionally nightly-only; its
# exact one-test assertion is covered by its own self-test and documented in
# the harness rather than being treated as a release-selector ledger.
grep -Fq 'nightly process-boundary harness' "$ROOT_DIR/scripts/two-phase-android-instrumentation.sh" ||
  fail "nightly-only two-phase harness lost its non-release-selector declaration"

selector="com.example.FakeTest#removedAnnotation"
class_file="$FIXTURE_ROOT/app/src/androidTest/java/com/example/FakeTest.kt"
zero_log="$FIXTURE_ROOT/zero-tests.log"
positive_log="$FIXTURE_ROOT/positive-tests.log"
selected_file="$FIXTURE_ROOT/selected.txt"
attendance_file="$FIXTURE_ROOT/selector-attendance.tsv"
marker="$FIXTURE_ROOT/run-start.marker"
mkdir -p "$(dirname "$class_file")"

printf 'package com.example\nclass FakeTest {\n    fun removedAnnotation() {}\n}\n' > "$class_file"
printf '%s\n' "$selector" > "$selected_file"
printf '%s\n' \
  'INSTRUMENTATION_STATUS: class=com.example.FakeTest' \
  'INSTRUMENTATION_STATUS: test=removedAnnotation' \
  'INSTRUMENTATION_STATUS_CODE: 0' \
  'INSTRUMENTATION_CODE: -1' \
  'OK (0 tests)' > "$zero_log"

if "$CHECKER" --verify-log --selector "$selector" --log "$zero_log"; then
  fail "OK (0 tests) was accepted as executed selector proof"
fi

source_guard_rejects_unannotated_method() (
  fail() { exit 1; }
  eval "$(sed -n '/^androidtest_method_is_annotated_test()/,/^}/p' "$PRE_RELEASE_GATE"; sed -n '/^assert_app_walkthrough_selectors_exist()/,/^}/p' "$PRE_RELEASE_GATE")"
  ROOT_DIR="$FIXTURE_ROOT"
  APP_WALKTHROUGH_TESTS=("$selector")
  assert_app_walkthrough_selectors_exist >/dev/null 2>&1
)
if source_guard_rejects_unannotated_method; then
  fail "a method with its @Test annotation removed was accepted by the selector guard"
fi

source_guard_accepts_real_annotation() (
  fail() { exit 1; }
  eval "$(sed -n '/^androidtest_method_is_annotated_test()/,/^}/p' "$PRE_RELEASE_GATE"; sed -n '/^assert_app_walkthrough_selectors_exist()/,/^}/p' "$PRE_RELEASE_GATE")"
  printf 'package com.example\nclass FakeTest {\n    @Test\n    fun removedAnnotation() {}\n}\n' > "$class_file"
  ROOT_DIR="$FIXTURE_ROOT"
  APP_WALKTHROUGH_TESTS=("$selector")
  assert_app_walkthrough_selectors_exist >/dev/null 2>&1
)
source_guard_accepts_real_annotation ||
  fail "a runnable @Test method was rejected by the selector guard"

source_guard_rejects_false_positive_annotation() (
  local gate_file="$PRE_RELEASE_GATE"
  local shape
  if [[ "$#" -eq 1 ]]; then
    shape="$1"
  else
    gate_file="$1"
    shape="$2"
  fi
  fail() { exit 1; }
  eval "$(sed -n '/^androidtest_method_is_annotated_test()/,/^}/p' "$gate_file"; sed -n '/^assert_app_walkthrough_selectors_exist()/,/^}/p' "$gate_file")"
  case "$shape" in
    block-comment)
      printf 'package com.example\nclass FakeTest {\n    /*\n       @Test\n    */\n    fun removedAnnotation() {}\n}\n' > "$class_file"
      ;;
    line-comment)
      printf 'package com.example\nclass FakeTest {\n    // @Test\n    fun removedAnnotation() {}\n}\n' > "$class_file"
      ;;
    string-literal)
      printf 'package com.example\nclass FakeTest {\n    val annotationText = "@Test "\n    fun removedAnnotation() {}\n}\n' > "$class_file"
      ;;
    *)
      return 1
      ;;
  esac
  ROOT_DIR="$FIXTURE_ROOT"
  APP_WALKTHROUGH_TESTS=("$selector")
  assert_app_walkthrough_selectors_exist >/dev/null 2>&1
)
for false_positive_shape in block-comment line-comment string-literal; do
  if source_guard_rejects_false_positive_annotation "$false_positive_shape"; then
    fail "$false_positive_shape containing @Test was accepted by the selector guard"
  fi
  printf 'PASS: selector guard rejects @Test in %s\n' "$false_positive_shape"
done

parser_false_positive_mutation_is_killed() (
  local shape="$1"
  local mutant_gate="$FIXTURE_ROOT/pre-release-parser-$shape-mutant.sh"
  awk '
    /^androidtest_method_is_annotated_test\(\)/ {
      print "androidtest_method_is_annotated_test() {"
      print "  local source_file=\"$1\""
      print "  local method=\"$2\""
      print "  grep -q \047@Test\047 \"$source_file\" &&"
      print "    grep -Eq \"fun[[:space:]]+${method}[[:space:]]*\\\\(\" \"$source_file\""
      print "}"
      replacing = 1
      next
    }
    replacing && /^}$/ {
      replacing = 0
      next
    }
    !replacing { print }
  ' "$PRE_RELEASE_GATE" > "$mutant_gate"
  [[ -s "$mutant_gate" ]] || return 1
  grep -Fq "grep -q '@Test'" "$mutant_gate" || return 1
  source_guard_rejects_false_positive_annotation "$mutant_gate" "$shape"
)

for false_positive_shape in block-comment line-comment string-literal; do
  if ! parser_false_positive_mutation_is_killed "$false_positive_shape"; then
    fail "the parser mutation for $false_positive_shape survived the false-positive assertion"
  fi
  printf 'PASS: parser mutation for %s is killed by the lexical source guard\n' "$false_positive_shape"
done

touch "$marker"
sleep 0.02
printf '%s\n' \
  'INSTRUMENTATION_STATUS: class=com.example.FakeTest' \
  'INSTRUMENTATION_STATUS: test=removedAnnotation' \
  'INSTRUMENTATION_STATUS_CODE: 0' \
  'INSTRUMENTATION_CODE: -1' \
  'OK (1 test)' > "$positive_log"
digest="$(sha256sum "$positive_log" | awk '{print $1}')"
{
  printf '%s\n' '# pocketshell-release-selector-attendance v1'
  printf 'run_id\t%s\n' current-run
  printf 'selector\t%s\t1\t%s\t%s\n' "$selector" "$positive_log" "$digest"
} > "$attendance_file"

"$CHECKER" --verify-log --selector "$selector" --log "$positive_log"
"$CHECKER" --verify-attendance \
  --selected-file "$selected_file" \
  --attendance "$attendance_file" \
  --run-id current-run \
  --newer-than "$marker"

checker_probe="$FIXTURE_ROOT/checker-result-probe.sh"
checker_probe_marker="$FIXTURE_ROOT/checker-result-probe.invoked"
cat > "$checker_probe" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' invoked > "$1"
exit 17
EOF
chmod +x "$checker_probe"
if pocketshell_run_release_selector_checker "$checker_probe" "$checker_probe_marker" --verify-attendance; then
  fail "checker wrapper accepted a failing live checker result"
fi
grep -Fqx invoked "$checker_probe_marker" ||
  fail "checker wrapper did not execute the live checker command"
printf 'PASS: current-run attendance checker execution and result are load-bearing\n'

stale_marker="$FIXTURE_ROOT/stale-run-start.marker"
touch "$stale_marker"
sleep 0.02
stale_log="$FIXTURE_ROOT/stale-run-positive.log"
printf '%s\n' \
  'INSTRUMENTATION_STATUS: class=com.example.FakeTest' \
  'INSTRUMENTATION_STATUS: test=removedAnnotation' \
  'INSTRUMENTATION_STATUS_CODE: 0' \
  'INSTRUMENTATION_CODE: -1' \
  'OK (1 test)' > "$stale_log"
stale_digest="$(sha256sum "$stale_log" | awk '{print $1}')"
stale_attendance="$FIXTURE_ROOT/stale-selector-attendance.tsv"
{
  printf '%s\n' '# pocketshell-release-selector-attendance v1'
  printf 'run_id\t%s\n' old-run
  printf 'selector\t%s\t1\t%s\t%s\n' "$selector" "$stale_log" "$stale_digest"
} > "$stale_attendance"
if "$CHECKER" --verify-attendance \
  --selected-file "$selected_file" \
  --attendance "$stale_attendance" \
  --run-id current-run \
  --newer-than "$stale_marker"; then
  fail "a stale selector attendance record was accepted for the current run"
fi

printf '%s\n' \
  'INSTRUMENTATION_STATUS: class=com.example.FakeTest' \
  'INSTRUMENTATION_STATUS: test=removedAnnotation' \
  'INSTRUMENTATION_STATUS_CODE: 0' \
  'INSTRUMENTATION_CODE: -1' \
  'OK (1 test)' > "$stale_log"
stale_current_marker="$FIXTURE_ROOT/stale-current-run-start.marker"
touch "$stale_current_marker"
stale_current_digest="$(sha256sum "$stale_log" | awk '{print $1}')"
stale_current_attendance="$FIXTURE_ROOT/stale-current-run-attendance.tsv"
{
  printf '%s\n' '# pocketshell-release-selector-attendance v1'
  printf 'run_id\t%s\n' current-run
  printf 'selector\t%s\t1\t%s\t%s\n' "$selector" "$stale_log" "$stale_current_digest"
} > "$stale_current_attendance"
if "$CHECKER" --verify-attendance \
  --selected-file "$selected_file" \
  --attendance "$stale_current_attendance" \
  --run-id current-run \
  --newer-than "$stale_current_marker"; then
  fail "a pre-marker raw selector log was accepted for the current run"
fi

printf 'PASS: release selector execution proof and current-run attendance contract\n'
