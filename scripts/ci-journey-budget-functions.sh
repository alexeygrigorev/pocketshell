#!/usr/bin/env bash
# Budget and bounded-run helpers for scripts/ci-journey-suite.sh.

# budget_remaining — seconds left in the suite-level budget (never negative).
budget_remaining() {
  local elapsed=$((SECONDS - SUITE_START))
  local remaining=$((JOURNEY_STEP_BUDGET_SECS - elapsed))
  (( remaining < 0 )) && remaining=0
  echo "$remaining"
}

# budget_exhausted — true (0) once the suite-level budget is spent. Checked
# before launching each class so the loop stops cleanly instead of being
# SIGKILLed by the workflow job cap mid-class (which writes no summary).
budget_exhausted() {
  (( $(budget_remaining) <= 0 ))
}

cleanup_gradle_after_timeout() {
  local fqcn="$1"
  local stop_rc

  echo "GRADLE_TIMEOUT_CLEANUP: $fqcn timed out; stopping Gradle daemons before any retry"
  if timeout --signal=TERM --kill-after=10 "${JOURNEY_GRADLE_STOP_TIMEOUT_SECS}s" \
      "$GRADLEW" --stop; then
    echo "GRADLE_TIMEOUT_CLEANUP: Gradle daemon stop completed for $fqcn"
    return 0
  fi

  stop_rc=$?
  echo "GRADLE_TIMEOUT_CLEANUP: Gradle daemon stop exited $stop_rc for $fqcn; continuing with retry/budget handling" >&2
  return 0
}

LAST_RUN_CLASS_TIMEOUT_HIT=0
LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=0

is_timeout_rc() {
  [[ "$1" -eq 124 || ( "$1" -eq 137 && "${LAST_RUN_CLASS_TIMEOUT_HIT:-0}" -eq 1 ) ]]
}

class_attempt_hit_time_budget() {
  is_timeout_rc "$1" || [[ "${LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT:-0}" -eq 1 ]]
}

needs_gradle_cleanup_after_class_abort() {
  [[ "$1" -eq 124 || "$1" -eq 137 ]]
}

# run_bounded <wall_cap_secs> <cmd...> — issue #1056.
run_bounded() {
  local wall_cap="$1"; shift
  local no_output="$JOURNEY_NO_OUTPUT_TIMEOUT_SECS"
  # Clamp the silence window to the wall cap so a tiny cap (self-test) still
  # governs, and never let a mis-set 0/negative window disable the read timeout.
  (( no_output <= 0 || no_output > wall_cap )) && no_output="$wall_cap"

  local tmpdir fifo to_pid rc read_rc line rfd killer
  tmpdir="$(mktemp -d)"
  fifo="$tmpdir/out"
  mkfifo "$fifo"

  timeout --signal=TERM --kill-after="$JOURNEY_CLASS_KILL_AFTER_SECS" "${wall_cap}s" \
    "$@" > "$fifo" 2>&1 &
  to_pid=$!

  exec {rfd}<"$fifo"
  # Issue #1458: tee every streamed line to a DURABLE capture file so the
  # workflow "Classify emulator-journey result" step can count the
  # `Failed to start Emulator console` storm (a CPU/RAM-starved swiftshader
  # symptom, ~100×/shard) AFTER this emulator step has ended and its stdout is
  # gone. Live streaming (the `printf` to stdout) is UNCHANGED. The fd is opened
  # in append mode ONCE per class attempt (cheap) and only when
  # JOURNEY_CONSOLE_LOG is set + openable, so the budget self-test and any
  # non-CI caller that leaves it unset behave exactly as before.
  local cfd=-1
  if [[ -n "${JOURNEY_CONSOLE_LOG:-}" ]]; then
    exec {cfd}>>"$JOURNEY_CONSOLE_LOG" || cfd=-1
  fi
  read_rc=0
  while :; do
    if IFS= read -r -t "$no_output" -u "$rfd" line; then
      printf '%s\n' "$line"
      (( cfd >= 0 )) && printf '%s\n' "$line" >&"$cfd"
    else
      read_rc=$?
      break
    fi
  done
  exec {rfd}<&-
  (( cfd >= 0 )) && exec {cfd}>&-

  if (( read_rc > 128 )); then
    echo "JOURNEY_NO_OUTPUT_WATCHDOG: no output for ${no_output}s — hard-killing wedged connectedDebugAndroidTest (issue #1056)" >&2
    kill -TERM "$to_pid" 2>/dev/null || true
    ( sleep "$JOURNEY_CLASS_KILL_AFTER_SECS"; kill -KILL "$to_pid" 2>/dev/null || true ) &
    killer=$!
    wait "$to_pid" 2>/dev/null
    kill "$killer" 2>/dev/null || true
    wait "$killer" 2>/dev/null || true
    rm -rf "$tmpdir"
    return 124
  fi

  wait "$to_pid" 2>/dev/null
  rc=$?
  rm -rf "$tmpdir"
  return "$rc"
}

# run_class <FQCN> — runs ONE journey class as its own gradle connected-test
# invocation and returns gradle's exit code (0 == that class passed).
run_class() {
  local fqcn="$1"
  local remaining cap rc attempt_start attempt_elapsed
  LAST_RUN_CLASS_TIMEOUT_HIT=0
  LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=0
  remaining="$(budget_remaining)"
  cap="$JOURNEY_CLASS_TIMEOUT_SECS"
  (( remaining < cap )) && cap="$remaining"
  if (( cap <= 0 )); then
    LAST_RUN_CLASS_TIMEOUT_HIT=1
    LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=1
    return 124
  fi
  attempt_start=$SECONDS
  run_bounded "$cap" \
    "$GRADLEW" :app:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=300000 \
    -Pandroid.testInstrumentationRunnerArguments.class="$fqcn" \
    --stacktrace
  rc=$?
  attempt_elapsed=$((SECONDS - attempt_start))
  if [[ $rc -eq 124 || ( $rc -eq 137 && $attempt_elapsed -ge $cap ) ]]; then
    LAST_RUN_CLASS_TIMEOUT_HIT=1
  fi
  if budget_exhausted; then
    LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=1
  fi
  if needs_gradle_cleanup_after_class_abort "$rc"; then
    cleanup_gradle_after_timeout "$fqcn"
  fi
  return "$rc"
}

# run_ct_class <FQCN> — runs ONE core-terminal proof class as its own
# :shared:core-terminal:connectedDebugAndroidTest invocation, wrapped in the
# SAME budget-capped timeout discipline as run_class.
run_ct_class() {
  local fqcn="$1"
  local remaining cap rc attempt_start attempt_elapsed
  LAST_RUN_CLASS_TIMEOUT_HIT=0
  LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=0
  remaining="$(budget_remaining)"
  cap="$JOURNEY_CLASS_TIMEOUT_SECS"
  (( remaining < cap )) && cap="$remaining"
  if (( cap <= 0 )); then
    LAST_RUN_CLASS_TIMEOUT_HIT=1
    LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=1
    return 124
  fi
  attempt_start=$SECONDS
  run_bounded "$cap" \
    "$GRADLEW" :shared:core-terminal:connectedDebugAndroidTest \
    -Pandroid.testInstrumentationRunnerArguments.class="$fqcn" \
    --stacktrace
  rc=$?
  attempt_elapsed=$((SECONDS - attempt_start))
  if [[ $rc -eq 124 || ( $rc -eq 137 && $attempt_elapsed -ge $cap ) ]]; then
    LAST_RUN_CLASS_TIMEOUT_HIT=1
  fi
  if budget_exhausted; then
    LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=1
  fi
  if needs_gradle_cleanup_after_class_abort "$rc"; then
    cleanup_gradle_after_timeout "$fqcn"
  fi
  return "$rc"
}

shard_class() {
  local idx="$1"
  local fqcn="$2"
  "$REPO_ROOT/scripts/connected-test.sh" --pool --suffix "ij$idx" \
    -Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true \
    -Pandroid.testInstrumentationRunnerArguments.timeout_msec=300000 \
    -Pandroid.testInstrumentationRunnerArguments.class="$fqcn" \
    --stacktrace
}

shard_run() {
  local lanes="${POCKETSHELL_JOURNEY_LANES:-2}"
  echo "=========================================================="
  echo ">>> SHARDED journey run (issue #724): up to ${lanes} concurrent lanes"
  echo "    each lane self-allocates (emulator serial, agents port) via"
  echo "    scripts/connected-test.sh --pool  (retry-once per class — #712)"
  echo "=========================================================="

  local rc=0
  local idx=0
  local -a pids=()
  local -a pid_classes=()
  local -a pid_idx=()
  for fqcn in "${JOURNEY_CLASSES[@]}"; do
    while (( $(jobs -rp | wc -l) >= lanes )); do
      wait -n 2>/dev/null || true
    done
    idx=$((idx + 1))
    local logf
    logf="$ARTIFACT_DIR/shard-lane-$idx-$(basename "$fqcn").log"
    echo ">>> dispatch lane $idx: $fqcn (log: $logf)"
    (
      if shard_class "$idx" "$fqcn"; then
        exit 0
      fi
      echo "SHARD_LANE_RETRY: $fqcn failed attempt 1 — retrying once"
      if shard_class "$idx" "$fqcn"; then
        echo "JOURNEY_FLAKE_RECOVERED: $fqcn passed on retry (sharded lane $idx)"
        exit 0
      fi
      exit 1
    ) > "$logf" 2>&1 &
    pids+=("$!")
    pid_classes+=("$fqcn")
    pid_idx+=("$idx")
  done

  local i
  for i in "${!pids[@]}"; do
    if ! wait "${pids[$i]}"; then
      echo "SHARD_LANE_FAILED: ${pid_classes[$i]} (lane ${pid_idx[$i]}, log: $ARTIFACT_DIR/shard-lane-${pid_idx[$i]}-$(basename "${pid_classes[$i]}").log)"
      rc=1
    else
      echo "SHARD_LANE_PASS: ${pid_classes[$i]} (lane ${pid_idx[$i]})"
    fi
  done
  return "$rc"
}
