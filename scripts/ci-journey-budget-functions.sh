#!/usr/bin/env bash
# Budget and bounded-run helpers for scripts/ci-journey-suite.sh.

# ---------------------------------------------------------------------------
# The suite-budget clock seam (issue #1839).
#
# Production measures elapsed suite time with bash's `$SECONDS`, which advances
# in whole INTEGER seconds. That is fine for the real 4200s budget, but it made
# the guard that drives a tiny budget (scripts/test-ci-journey-summary-evidence.sh
# `(c2)`) flaky-BY-CONSTRUCTION: with a 1s budget the verdict hinged on whether a
# `$SECONDS` tick happened to land in the sub-second window between SUITE_START
# and the first `budget_exhausted` check. Whether that tick lands is a property
# of the MACHINE, not of the budget logic — the hosted runner lost it 2/2 (red
# `main` @ ae368467, run 30368416112) while the contended dev box won it 5/5 on
# the identical commit.
#
# The cure is a pinnable seam, not a bigger number (process.md: "the tuning knob
# must be injectable/seedable so tests are deterministic while production keeps
# real randomness — never widen the assertion into a band that no longer
# constrains behaviour", G6). `JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE` pins the
# elapsed reading so a budget test is decided by the budget ARITHMETIC alone, on
# any hardware, at either extreme of the tick.
#
# Safety, because a pinned clock that leaked into production would silently
# DISABLE the #835 budget:
#   * It is validated HERE, once, at source time in the suite's own shell — a
#     malformed value is a hard `exit 2`, never a silently ignored override
#     (`budget_remaining` runs inside `$(...)`, where an exit could only kill the
#     subshell and would read as "not exhausted").
#   * scripts/test-ci-journey-budget.sh asserts the production default path still
#     measures `$SECONDS - SUITE_START`, and that neither the workflow nor the
#     suite ever sets the override.
if [[ -n "${JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE:-}" ]]; then
  if [[ ! "${JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE}" =~ ^[0-9]+$ ]]; then
    echo "JOURNEY_BUDGET_CLOCK_INVALID: JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE='${JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE}' is not a non-negative integer — refusing to run with an unreadable suite-budget clock (issue #1839)" >&2
    exit 2
  fi
fi

# journey_budget_elapsed — seconds of suite budget consumed so far.
journey_budget_elapsed() {
  if [[ -n "${JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE:-}" ]]; then
    printf '%s\n' "$JOURNEY_BUDGET_ELAPSED_SECS_OVERRIDE"
    return 0
  fi
  printf '%s\n' "$((SECONDS - SUITE_START))"
}

# budget_remaining — seconds left in the suite-level budget (never negative).
budget_remaining() {
  local elapsed
  elapsed="$(journey_budget_elapsed)"
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

# ---------------------------------------------------------------------------
# Issue #1840: the timeout cleanup must leave the RETRY a usable build tree.
#
# THE DEFECT
# ----------
# `gradlew --stop` stops the GRADLE daemon. It does NOT stop the other build
# JVMs that daemon spawned — above all the KOTLIN COMPILE DAEMON, a separate
# long-lived process (`kotlin.compiler.execution.strategy=daemon` is the KGP
# default and this repo does not override it). When a class attempt is cut
# mid-build, `run_bounded` kills the Gradle CLIENT, `--stop` kills the daemon,
# and the Kotlin daemon keeps compiling the module it was handed — still
# emitting `.class` files into `<module>/build/tmp/kotlin-classes/<variant>`.
#
# Observed end to end on run 30339688411, shard 0
# (`com.pocketshell.app.proof.TmuxSessionScreenArtVerifyE2eTest`):
#   13:10:41  attempt 1 cut by the per-class wall cap while Gradle was BUILDING
#   13:10:43  `Stopping Daemon(s)` / `1 Daemon stopped`, and this function
#             reported `GRADLE_TIMEOUT_CLEANUP: ... stop completed`
#   13:11:30  the RETRY's `:app:compileDebugAndroidTestKotlin` FAILED with
#               Unable to delete directory '.../kotlin-classes/debugAndroidTest'
#               Failed to delete some children. ...
#               New files were found. This might happen because a process is
#               still writing to the target directory.
#             naming freshly-written `com/pocketshell/app/tmux/*.class` files.
# Gradle said it in as many words: 47 seconds after the daemon "stopped",
# something was STILL WRITING. The retry never reached instrumentation, both
# attempts produced unusable summaries, and the shard went RED with no product
# defect anywhere in it — a per-class timeout converted into a whole-shard RED
# by our own cleanup, with the retry (the mechanism meant to absorb exactly that
# timeout) destroyed before it could help.
#
# THE FIX: SCOPING, NOT SKIPPING
# ------------------------------
# The cleanup is not the problem — a wedged daemon poisoning the next build was
# the #918 problem it exists to solve. What was wrong is its SCOPE: it reached
# one process out of the process tree the killed build left behind. So after the
# daemon stop, reap every process that still holds an open file under THIS
# checkout's build-output roots, and refuse to report the cleanup clean until
# none remains. No timeout and no budget is widened (G6): this runs only on the
# already-exceptional abort path and normally finds nothing at all.

# journey_build_output_roots — the build-output directories this checkout owns.
#
# Deliberately an EXPLICIT list, never a `find`/glob for "any directory named
# build under $REPO_ROOT": on the maintainer's dev box sibling agents' worktrees
# live at `$REPO_ROOT/.worktrees/issue-*/`, so a glob would nominate THEIR build
# processes and killing those is exactly the cross-agent damage process.md
# forbids. Every module the journey suite compiles is covered here.
journey_build_output_roots() {
  local dir
  for dir in "$REPO_ROOT/build" "$REPO_ROOT/app/build" "$REPO_ROOT/shared"/*/build; do
    [[ -d "$dir" ]] && printf '%s\n' "$dir"
  done
  return 0
}

# journey_self_and_ancestor_pids — this shell and every ancestor, so the reap can
# never signal the suite, the workflow's step shell, or the runner agent.
journey_self_and_ancestor_pids() {
  local pid=$$
  local parent
  while [[ "$pid" =~ ^[0-9]+$ ]] && (( pid > 1 )); do
    printf '%s\n' "$pid"
    parent="$(sed -n 's/^PPid:[[:space:]]*//p' "/proc/$pid/status" 2>/dev/null)"
    [[ "$parent" =~ ^[0-9]+$ ]] || break
    pid="$parent"
  done
  return 0
}

# journey_build_output_writer_pids — PIDs still holding an open file under this
# checkout's build-output roots.
#
# The test is the process's open FILE DESCRIPTORS, never its cwd. That is not an
# implementation detail: the `adb` server inherits the suite's cwd ($REPO_ROOT —
# the suite `cd`s there) and must never be nominated, while a build JVM that is
# still writing class files necessarily holds real descriptors under a build
# root. Processes owned by another user simply yield an unreadable
# `/proc/<pid>/fd` and are skipped.
#
# The whole scan runs in ONE python3 process on purpose: a bash loop would fork
# `readlink` once per descriptor (tens of thousands of forks on a busy box), and
# a cleanup that takes minutes to answer is its own outage. python3 is already a
# hard dependency of this helper (validate_png_artifact, write_harness_verdict_xml).
journey_build_output_writer_pids() {
  local -a roots=()
  local python_command="${PYTHON3:-python3}"

  [[ -d /proc ]] || return 0
  command -v "$python_command" >/dev/null 2>&1 || return 0
  mapfile -t roots < <(journey_build_output_roots)
  (( ${#roots[@]} > 0 )) || return 0

  command "$python_command" -I - \
    "$(printf '%s\n' "${roots[@]}")" \
    "$(journey_self_and_ancestor_pids | tr '\n' ' ')" <<'PY'
import os
import sys

roots = [line.rstrip("/") + "/" for line in sys.argv[1].split("\n") if line]
skip = set(sys.argv[2].split())
found = []
for entry in os.listdir("/proc"):
    if not entry.isdigit() or entry in skip:
        continue
    fd_dir = "/proc/%s/fd" % entry
    try:
        names = os.listdir(fd_dir)
    except OSError:
        continue
    for name in names:
        try:
            target = os.readlink(os.path.join(fd_dir, name))
        except OSError:
            continue
        if any(target.startswith(root) for root in roots):
            found.append(entry)
            break
if found:
    print("\n".join(found))
PY
}

journey_pid_cmdline() {
  local pid="$1" cmd
  cmd="$(tr '\0' ' ' < "/proc/$pid/cmdline" 2>/dev/null)" || cmd=""
  [[ -n "$cmd" ]] || cmd="<gone>"
  printf '%s\n' "${cmd:0:160}"
}

# cleanup_build_output_writers_after_abort <label> — TERM, then KILL, then VERIFY
# that nothing is left writing this checkout's build outputs. Same shape as
# cleanup_device_after_class_abort: a bounded pump whose load-bearing assertion
# is the absence condition, with a hard wall.
cleanup_build_output_writers_after_abort() {
  local label="$1"
  local deadline term_deadline signal pid
  local -a pids=()

  if [[ ! -d /proc ]]; then
    echo "JOURNEY_ABORT_BUILD_CLEANUP_UNSUPPORTED: /proc is unavailable, so no orphaned build writer can be observed for $label (issue #1840)" >&2
    return 0
  fi

  mapfile -t pids < <(journey_build_output_writer_pids)
  if (( ${#pids[@]} == 0 )); then
    echo "JOURNEY_ABORT_BUILD_CLEANUP_VERIFIED: no process still holds this checkout's build outputs after $label"
    return 0
  fi

  echo "JOURNEY_ABORT_BUILD_CLEANUP: the aborted build left process(es) still holding this checkout's build outputs after $label — \`gradlew --stop\` does not reach them (issue #1840):"
  for pid in "${pids[@]}"; do
    echo "  pid=$pid cmd=$(journey_pid_cmdline "$pid")"
  done

  term_deadline=$((SECONDS + ${JOURNEY_BUILD_CLEANUP_TERM_GRACE_SECS:-5}))
  deadline=$((SECONDS + ${JOURNEY_BUILD_CLEANUP_TIMEOUT_SECS:-30}))
  while :; do
    signal=TERM
    (( SECONDS >= term_deadline )) && signal=KILL
    for pid in "${pids[@]}"; do
      kill "-$signal" "$pid" 2>/dev/null || true
    done
    # Bounded verification pump, not a fixed correctness sleep: every turn
    # re-checks the load-bearing absence condition until its hard wall.
    sleep 0.2
    mapfile -t pids < <(journey_build_output_writer_pids)
    if (( ${#pids[@]} == 0 )); then
      echo "JOURNEY_ABORT_BUILD_CLEANUP_VERIFIED: no process still holds this checkout's build outputs after $label"
      return 0
    fi
    if (( SECONDS >= deadline )); then
      echo "JOURNEY_ABORT_CLEANUP_FAILED: process(es) still hold this checkout's build outputs after $label; the next build would race them (issue #1840):" >&2
      for pid in "${pids[@]}"; do
        echo "  pid=$pid cmd=$(journey_pid_cmdline "$pid")" >&2
      done
      return 1
    fi
  done
}

cleanup_gradle_after_timeout() {
  local fqcn="$1"
  local stop_rc writers_rc

  echo "GRADLE_TIMEOUT_CLEANUP: $fqcn aborted; stopping Gradle daemons before any retry"
  # NOTE (issue #1840): the exit code is captured DIRECTLY, not read from `$?`
  # after an `if`. The previous shape ran the stop inside `if ...; then return 0;
  # fi` and then read `stop_rc=$?` — but the exit status of an `if` whose branch
  # did not run is 0, so a FAILED daemon stop was reported as a clean cleanup and
  # `cleanup_status=verified_clean` was written over it.
  timeout --signal=TERM --kill-after=10 "${JOURNEY_GRADLE_STOP_TIMEOUT_SECS}s" \
    "$GRADLEW" --stop
  stop_rc=$?
  if (( stop_rc == 0 )); then
    echo "GRADLE_TIMEOUT_CLEANUP: Gradle daemon stop completed for $fqcn"
  else
    echo "GRADLE_TIMEOUT_CLEANUP_FAILED: Gradle daemon stop exited $stop_rc for $fqcn" >&2
  fi

  # Issue #1840: the daemon stop reaches the Gradle daemon and nothing else. Reap
  # whatever the killed build left writing this checkout's build outputs BEFORE
  # any retry starts, or the retry's own `compileDebugAndroidTestKotlin` races it
  # and dies clearing its output directory.
  cleanup_build_output_writers_after_abort "$fqcn"
  writers_rc=$?

  (( stop_rc != 0 )) && return "$stop_rc"
  return "$writers_rc"
}

LAST_RUN_CLASS_TIMEOUT_HIT=0
LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=0
LAST_RUN_CLASS_PRIMARY_RC=0
LAST_RUN_CLASS_ABORT_CLEANUP_FAILED=0
LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED=0
LAST_RUN_CLASS_GRADLE_CLEANUP_RC="not_run"
LAST_RUN_CLASS_DEVICE_CLEANUP_RC="not_run"
LAST_RUN_CLASS_ATTEMPT_DIR=""
LAST_RUN_CLASS_MODULE=""
LAST_RUN_CLASS_SELECTOR=""
LAST_RUN_CLASS_ATTEMPT=0
LAST_RUN_CLASS_PRIMARY_CLASSIFICATION=""
LAST_RUN_CLASS_RAW_JUNIT_STATUS=""
LAST_RUN_CLASS_RAW_JUNIT_COUNT=0
LAST_RUN_CLASS_SNAPSHOT_STATUS=""
# Issue #1814: which PHASE an outer-timeout attempt died in. A zero-test
# `outer_timeout` reads identically whether the runner was killed mid-journey or
# Gradle was still BUILDING, and that ambiguity is what made the shard-2
# first-class timeout on run 30323508796 expensive to diagnose.
LAST_RUN_CLASS_OUTER_TIMEOUT_PHASE=""
# Issue #1840: which PHASE a non-timeout FAILED attempt died in. A retry whose
# Gradle BUILD died never ran the journey at all, so it must not read as "the
# class failed twice" — see journey_attempt_phase.
LAST_RUN_CLASS_ATTEMPT_FAILURE_PHASE=""
BUILD_PHASE_TIMEOUT_ATTEMPTS=()
BUILD_PHASE_FAILURE_ATTEMPTS=()
JOURNEY_ABORT_ISOLATION_FAILED=0
JOURNEY_HARNESS_FAILURE_RC=125
declare -A JOURNEY_CLASS_ATTEMPT_COUNTS=()

is_timeout_rc() {
  [[ "$1" -eq 124 || ( "$1" -eq 137 && "${LAST_RUN_CLASS_TIMEOUT_HIT:-0}" -eq 1 ) ]]
}

class_attempt_hit_time_budget() {
  is_timeout_rc "$1" || [[ "${LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT:-0}" -eq 1 ]]
}

needs_gradle_cleanup_after_class_abort() {
  [[ "$1" -eq 124 || "$1" -eq 137 ]]
}

# journey_attempt_phase <attempt-log> <classification> <describes> [<raw-junit-count>]
#   — issue #1814, generalised in issue #1840.
#
# #1814: an `outer_timeout` with `raw-junit count=0` is produced by two very
# different situations that used to be indistinguishable in the artifacts:
#   * the Gradle BUILD was still running when the wall cap fired (nothing about
#     the journey was ever exercised — a build-cost artefact, not a verdict), and
#   * instrumentation started and then wedged (a real on-device signal).
#
# #1840: the SAME ambiguity exists one step over, for a plain non-timeout
# `failure`. On run 30339688411 shard 0 the retry's Gradle build died clearing
# `kotlin-classes/debugAndroidTest`, instrumentation never started, and the shard
# reported the class as having "failed twice" — a build-level failure wearing a
# product failure's clothes. Rather than invent a parallel scheme, this is the
# same helper answering the same question for a second classification; the caller
# says which one it is asking about via <describes>, so `outer_timeout_phase`
# keeps EXACTLY the #1814 meaning and `attempt_failure_phase` is its sibling.
#
# The attempt log is the evidence: Gradle prints `> Task :…` headers as it
# executes, and the connected-test task / runner announce themselves when
# instrumentation begins.
#
# Fail-SAFE by construction: `build` is claimed only on POSITIVE evidence of
# build activity WITH no instrumentation marker AND — for a `failure` — with no
# raw JUnit XML, since instrumentation that ran at all leaves result XML behind.
# No evidence at all stays `unknown`, which is never downgraded or specially
# labelled. This can only ever add attribution, never remove a failure signal.
journey_attempt_phase() {
  local log="$1"
  local classification="$2"
  local describes="$3"
  local raw_junit_count="${4:-0}"

  if [[ "$classification" != "$describes" ]]; then
    printf 'not_applicable\n'
    return 0
  fi
  if [[ ! -f "$log" ]]; then
    printf 'unknown\n'
    return 0
  fi
  # Result XML is proof instrumentation ran, whatever the log says.
  if [[ "$raw_junit_count" =~ ^[0-9]+$ ]] && (( raw_junit_count > 0 )); then
    printf 'instrumentation\n'
    return 0
  fi
  if grep -qE '> Task :[^[:space:]]*:connected[A-Za-z]*AndroidTest|Starting [0-9]+ tests on|Installing APK|Installed on ' "$log"; then
    printf 'instrumentation\n'
    return 0
  fi
  if grep -qE '> Task :' "$log"; then
    printf 'build\n'
    return 0
  fi
  printf 'unknown\n'
}

# record_build_phase_timeout_attempt <selector> — issue #1814.
#
# Name a build-phase timeout LOUDLY and greppably at the moment it happens, and
# collect it for its own summary section, so nobody has to reverse-engineer
# "exit 124 / count=0" into "the build had not finished yet" again. This is
# attribution only: the attempt's pass/fail bucket is untouched.
record_build_phase_timeout_attempt() {
  local fqcn="$1"

  [[ "${LAST_RUN_CLASS_OUTER_TIMEOUT_PHASE:-}" == "build" ]] || return 0
  echo "JOURNEY_BUILD_PHASE_TIMEOUT: $fqcn attempt ${LAST_RUN_CLASS_ATTEMPT} was cut while Gradle was still BUILDING — instrumentation never started, so there is no raw JUnit XML (raw-junit count=${LAST_RUN_CLASS_RAW_JUNIT_COUNT}). Read this as a build-cost timeout, NOT as a journey verdict or a product defect (issue #1814)."
  BUILD_PHASE_TIMEOUT_ATTEMPTS+=("$fqcn (attempt ${LAST_RUN_CLASS_ATTEMPT})")
}

# record_build_phase_failure_attempt <selector> — issue #1840.
#
# The #1814 sibling for a NON-timeout failure: name a build-level death LOUDLY
# and greppably at the moment it happens, so "the retry's build died" is never
# again read off the artifacts as "this class failed twice". Attribution only:
# the attempt's pass/fail bucket is untouched (softening it would be exactly the
# masking D31/D32 forbid — a build that cannot run is a real problem).
record_build_phase_failure_attempt() {
  local fqcn="$1"

  [[ "${LAST_RUN_CLASS_ATTEMPT_FAILURE_PHASE:-}" == "build" ]] || return 0
  echo "JOURNEY_BUILD_PHASE_FAILURE: $fqcn attempt ${LAST_RUN_CLASS_ATTEMPT} died at the GRADLE BUILD level — instrumentation never started, so there is no raw JUnit XML (raw-junit count=${LAST_RUN_CLASS_RAW_JUNIT_COUNT}) and this attempt produced NO journey verdict. Read this as a build-level failure, NOT as the named journey failing (issue #1840)."
  BUILD_PHASE_FAILURE_ATTEMPTS+=("$fqcn (attempt ${LAST_RUN_CLASS_ATTEMPT})")
}

journey_class_artifact_key() {
  local fqcn="$1"
  local readable="${fqcn//[^A-Za-z0-9._-]/_}"
  local digest

  # Keep one readable path component, but hash the full unsanitized selector so
  # selectors such as A#method and A_method can never overwrite one another.
  readable="${readable:0:120}"
  while [[ "$readable" == .* ]]; do
    readable="_${readable#.}"
  done
  [[ "$readable" =~ [A-Za-z0-9] ]] || readable="unnamed-class"
  digest="$(printf '%s' "$fqcn" | sha256sum | awk '{ print substr($1, 1, 16) }')" \
    || return 1
  [[ "$digest" =~ ^[0-9a-f]{16}$ ]] || return 1
  printf '%s--%s\n' "$readable" "$digest"
}

journey_app_package_names() {
  local suffix="${1:-}"
  local app_package="com.pocketshell.app"

  if [[ -n "$suffix" ]]; then
    [[ "$suffix" =~ ^[A-Za-z0-9._]+$ ]] || {
      echo "JOURNEY_ABORT_CLEANUP_FAILED: invalid application id suffix '$suffix'" >&2
      return 2
    }
    app_package+=".$suffix"
  fi
  printf '%s.test\n%s\n' "$app_package" "$app_package"
}

journey_target_package_names() {
  local module="$1"
  local suffix="${2:-}"

  case "$module" in
    app)
      journey_app_package_names "$suffix"
      ;;
    core-terminal)
      # Android library androidTest APK namespace from
      # shared/core-terminal/build.gradle.kts.
      printf '%s\n' "com.termux.view.test"
      ;;
    *)
      echo "JOURNEY_ABORT_CLEANUP_FAILED: unknown connected-test module '$module'" >&2
      return 2
      ;;
  esac
}

journey_adb() {
  local adb_command="${JOURNEY_ADB:-${ADB:-adb}}"
  command "$adb_command" "$@"
}

journey_resolve_device_serial() {
  local devices_output serial candidate found=0
  local -a serials=()

  if ! devices_output="$(journey_adb devices 2>&1)"; then
    echo "JOURNEY_ABORT_CLEANUP_FAILED: adb devices failed: $devices_output" >&2
    return 1
  fi
  mapfile -t serials < <(
    awk 'NR > 1 && $2 == "device" { print $1 }' <<< "$devices_output"
  )

  if [[ -n "${ANDROID_SERIAL:-}" ]]; then
    serial="$ANDROID_SERIAL"
    for candidate in "${serials[@]}"; do
      [[ "$candidate" == "$serial" ]] && found=1
    done
    if [[ "$found" -ne 1 ]]; then
      echo "JOURNEY_ABORT_CLEANUP_FAILED: ANDROID_SERIAL=$serial is not the one online device" >&2
      return 1
    fi
    printf '%s\n' "$serial"
    return 0
  fi

  if [[ "${#serials[@]}" -ne 1 ]]; then
    echo "JOURNEY_ABORT_CLEANUP_FAILED: expected exactly one online device without ANDROID_SERIAL; found ${#serials[@]}" >&2
    return 1
  fi
  printf '%s\n' "${serials[0]}"
}

journey_device_processes_for_packages() {
  local serial="$1"
  shift
  local ps_output package

  if ! ps_output="$(journey_adb -s "$serial" shell ps -A 2>&1)"; then
    echo "JOURNEY_ABORT_CLEANUP_FAILED: could not inspect processes on $serial: $ps_output" >&2
    return 2
  fi

  for package in "$@"; do
    awk -v package="$package" '
      {
        name=$NF
        if (name == package || index(name, package ":") == 1) {
          print
        }
      }
    ' <<< "$ps_output"
  done
}

cleanup_device_after_class_abort() {
  local module="$1"
  local fqcn="$2"
  local suffix="${3:-}"
  local serial package process_output deadline
  local force_stop_failed=0
  local -a packages=()

  mapfile -t packages < <(journey_target_package_names "$module" "$suffix") || return 1
  [[ "${#packages[@]}" -gt 0 ]] || {
    echo "JOURNEY_ABORT_CLEANUP_FAILED: no exact packages resolved for $fqcn" >&2
    return 1
  }
  serial="$(journey_resolve_device_serial)" || return 1

  echo "JOURNEY_ABORT_DEVICE_CLEANUP: force-stopping exact $module packages on $serial before retry: ${packages[*]}"
  for package in "${packages[@]}"; do
    if ! journey_adb -s "$serial" shell am force-stop "$package"; then
      echo "JOURNEY_ABORT_CLEANUP_FAILED: force-stop failed for exact package $package on $serial" >&2
      force_stop_failed=1
    fi
  done

  deadline=$((SECONDS + ${JOURNEY_DEVICE_CLEANUP_TIMEOUT_SECS:-15}))
  while :; do
    process_output="$(journey_device_processes_for_packages "$serial" "${packages[@]}")"
    case $? in
      0)
        if [[ -z "$process_output" ]]; then
          if [[ "$force_stop_failed" -ne 0 ]]; then
            echo "JOURNEY_ABORT_CLEANUP_FAILED: process absence was observed but an exact force-stop command failed for $fqcn" >&2
            return 1
          fi
          echo "JOURNEY_ABORT_DEVICE_CLEANUP_VERIFIED: no instrumentation/test/app process remains for $fqcn on $serial"
          return 0
        fi
        ;;
      *)
        return 1
        ;;
    esac

    if (( SECONDS >= deadline )); then
      echo "JOURNEY_ABORT_CLEANUP_FAILED: stale exact package process remains after abort for $fqcn:" >&2
      printf '%s\n' "$process_output" >&2
      return 1
    fi
    # Bounded verification pump, not a fixed correctness sleep: every turn
    # re-checks the load-bearing process-absence condition until its hard wall.
    sleep 0.2
  done
}

journey_module_build_roots() {
  local module="$1"
  local suffix="${2:-}"
  local module_dir

  case "$module" in
    app) module_dir="$REPO_ROOT/app" ;;
    core-terminal) module_dir="$REPO_ROOT/shared/core-terminal" ;;
    *) return 2 ;;
  esac

  printf '%s\n' "$module_dir/build"
  if [[ -n "$suffix" ]]; then
    [[ "$suffix" =~ ^[A-Za-z0-9._]+$ ]] || return 2
    printf '%s\n' "$module_dir/build/lane-$suffix"
  fi
}

clear_connected_test_outputs() {
  local module="$1"
  local suffix="${2:-}"
  local build_root
  local -a build_roots=()

  mapfile -t build_roots < <(journey_module_build_roots "$module" "$suffix") || return 1
  for build_root in "${build_roots[@]}"; do
    # Exact generated report roots only. Clearing them before the invocation
    # makes every later snapshot attributable to this attempt, never stale.
    rm -rf -- \
      "$build_root/reports/androidTests" \
      "$build_root/outputs/androidTest-results" \
      "$build_root/outputs/connected_android_test_additional_output" \
      || return 1
  done
}

begin_class_attempt_artifacts() {
  local module="$1"
  local fqcn="$2"
  local suffix="${3:-}"
  local key="$module:$fqcn"
  local attempt=$(( ${JOURNEY_CLASS_ATTEMPT_COUNTS[$key]:-0} + 1 ))
  local artifact_key capture_token

  JOURNEY_CLASS_ATTEMPT_COUNTS["$key"]="$attempt"
  artifact_key="$(journey_class_artifact_key "$fqcn")" || return 1
  LAST_RUN_CLASS_ATTEMPT_DIR="$ARTIFACT_DIR/class-attempts/$module/$artifact_key/attempt-$attempt"
  LAST_RUN_CLASS_MODULE="$module"
  LAST_RUN_CLASS_SELECTOR="$fqcn"
  LAST_RUN_CLASS_ATTEMPT="$attempt"
  LAST_RUN_CLASS_PRIMARY_CLASSIFICATION=""
  LAST_RUN_CLASS_RAW_JUNIT_STATUS=""
  LAST_RUN_CLASS_RAW_JUNIT_COUNT=0
  LAST_RUN_CLASS_SNAPSHOT_STATUS=""
  capture_token="$(
    printf '%s\0%s\0%s\0%s\0%s\n' \
      "$module" "$fqcn" "$attempt" "$(date +%s%N)" "$RANDOM" \
      | sha256sum | awk '{ print $1 }'
  )" || return 1
  [[ "$capture_token" =~ ^[0-9a-f]{64}$ ]] || return 1
  LAST_RUN_CLASS_CAPTURE_TOKEN="$capture_token"
  rm -rf -- "$LAST_RUN_CLASS_ATTEMPT_DIR" || return 1
  mkdir -p "$LAST_RUN_CLASS_ATTEMPT_DIR" || return 1
  : > "$LAST_RUN_CLASS_ATTEMPT_DIR/attempt.log" || return 1

  {
    printf 'format_version=1\n'
    printf 'module=%s\n' "$module"
    printf 'class=%s\n' "$fqcn"
    printf 'attempt=%s\n' "$attempt"
    printf 'capture_token=%s\n' "$capture_token"
    printf 'started_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf 'application_id_suffix=%s\n' "$suffix"
    printf 'status=running\n'
  } > "$LAST_RUN_CLASS_ATTEMPT_DIR/manifest.txt" || return 1

  if ! clear_connected_test_outputs "$module" "$suffix"; then
    echo "JOURNEY_ATTEMPT_ARTIFACT_FAILED: could not clear exact generated outputs for $module / $fqcn" >&2
    return 1
  fi

  local serial
  if ! serial="$(journey_resolve_device_serial)"; then
    printf 'device_serial=unresolved\nlogcat_clear=unavailable\nstatus=artifact_setup_failed\n' \
      >> "$LAST_RUN_CLASS_ATTEMPT_DIR/manifest.txt" 2>/dev/null || true
    echo "JOURNEY_ATTEMPT_ARTIFACT_FAILED: device identity is unresolved for $fqcn" >&2
    return 1
  fi
  printf 'device_serial=%s\n' "$serial" >> "$LAST_RUN_CLASS_ATTEMPT_DIR/manifest.txt" \
    || return 1
  if ! journey_adb -s "$serial" logcat -c; then
    printf 'logcat_clear=failed\nstatus=artifact_setup_failed\n' \
      >> "$LAST_RUN_CLASS_ATTEMPT_DIR/manifest.txt" 2>/dev/null || true
    echo "JOURNEY_ATTEMPT_ARTIFACT_FAILED: could not reset logcat for $fqcn on $serial" >&2
    return 1
  fi
  printf 'logcat_clear=ok\n' >> "$LAST_RUN_CLASS_ATTEMPT_DIR/manifest.txt" \
    || return 1
}

capture_required_nonempty() {
  local label="$1"
  local destination="$2"
  shift 2

  if ! "$@" > "$destination" 2>&1; then
    echo "JOURNEY_ATTEMPT_ARTIFACT_FAILED: $label capture command failed" >&2
    return 1
  fi
  if [[ ! -s "$destination" ]]; then
    echo "JOURNEY_ATTEMPT_ARTIFACT_FAILED: $label capture was empty" >&2
    return 1
  fi
}

validate_png_artifact() {
  local source="$1"
  local python_command="${PYTHON3:-python3}"

  # Python 3 is present on the ubuntu-latest journey runner and is already a
  # required repo-gate dependency. The validator uses only its standard library.
  command -v "$python_command" >/dev/null 2>&1 || return 1
  command "$python_command" -I - "$source" <<'PY'
import binascii
import struct
import sys
import zlib

data = open(sys.argv[1], "rb").read()
if data[:8] != b"\x89PNG\r\n\x1a\n":
    raise SystemExit(1)

offset = 8
chunks = []
idat = []
ihdr = None
seen_iend = False
seen_plte = False
idat_ended = False
while offset < len(data):
    if len(data) - offset < 12:
        raise SystemExit(1)
    length = struct.unpack(">I", data[offset:offset + 4])[0]
    kind = data[offset + 4:offset + 8]
    end = offset + 12 + length
    if end > len(data) or len(kind) != 4 or not all(
        65 <= byte <= 90 or 97 <= byte <= 122 for byte in kind
    ):
        raise SystemExit(1)
    payload = data[offset + 8:offset + 8 + length]
    stored_crc = struct.unpack(">I", data[offset + 8 + length:end])[0]
    if binascii.crc32(kind + payload) & 0xFFFFFFFF != stored_crc:
        raise SystemExit(1)
    if not chunks and kind != b"IHDR":
        raise SystemExit(1)
    if kind == b"IHDR":
        if chunks or length != 13 or ihdr is not None:
            raise SystemExit(1)
        ihdr = struct.unpack(">IIBBBBB", payload)
    elif kind == b"IDAT":
        if ihdr is None or idat_ended:
            raise SystemExit(1)
        idat.append(payload)
    elif kind == b"PLTE":
        if seen_plte or idat or length == 0 or length > 768 or length % 3 != 0:
            raise SystemExit(1)
        seen_plte = True
    elif kind == b"IEND":
        if length != 0 or not idat or end != len(data):
            raise SystemExit(1)
        seen_iend = True
    elif idat:
        idat_ended = True
    if kind[0] & 0x20 == 0 and kind not in {
        b"IHDR", b"PLTE", b"IDAT", b"IEND"
    }:
        raise SystemExit(1)
    chunks.append(kind)
    offset = end
    if seen_iend:
        break

if not seen_iend or ihdr is None:
    raise SystemExit(1)
width, height, bit_depth, color_type, compression, filtering, interlace = ihdr
valid_depths = {
    0: {1, 2, 4, 8, 16},
    2: {8, 16},
    3: {1, 2, 4, 8},
    4: {8, 16},
    6: {8, 16},
}
if (
    width == 0
    or height == 0
    or bit_depth not in valid_depths.get(color_type, set())
    or compression != 0
    or filtering != 0
    or interlace not in {0, 1}
):
    raise SystemExit(1)
if color_type == 3 and not seen_plte:
    raise SystemExit(1)

decoder = zlib.decompressobj()
try:
    pixels = decoder.decompress(b"".join(idat)) + decoder.flush()
except zlib.error:
    raise SystemExit(1)
if not decoder.eof or decoder.unused_data or decoder.unconsumed_tail:
    raise SystemExit(1)

channels = {0: 1, 2: 3, 3: 1, 4: 2, 6: 4}[color_type]
bits_per_pixel = channels * bit_depth
passes = [(0, 0, 1, 1)]
if interlace == 1:
    passes = [
        (0, 0, 8, 8), (4, 0, 8, 8), (0, 4, 4, 8), (2, 0, 4, 4),
        (0, 2, 2, 4), (1, 0, 2, 2), (0, 1, 1, 2),
    ]
cursor = 0
for x_start, y_start, x_step, y_step in passes:
    pass_width = max(0, (width - x_start + x_step - 1) // x_step)
    pass_height = max(0, (height - y_start + y_step - 1) // y_step)
    if pass_width == 0 or pass_height == 0:
        continue
    row_bytes = (pass_width * bits_per_pixel + 7) // 8
    for _ in range(pass_height):
        if cursor >= len(pixels) or pixels[cursor] > 4:
            raise SystemExit(1)
        cursor += 1 + row_bytes
if cursor != len(pixels):
    raise SystemExit(1)
PY
}

capture_required_png() {
  local destination="$1"
  shift

  capture_required_nonempty "fallback screenshot" "$destination" "$@" || return 1
  if ! validate_png_artifact "$destination"; then
    echo "JOURNEY_ATTEMPT_ARTIFACT_FAILED: fallback screenshot is not a structurally valid PNG" >&2
    return 1
  fi
}

snapshot_connected_test_outputs() {
  local module="$1"
  local fqcn="$2"
  local primary_rc="$3"
  local suffix="${4:-}"
  local attempt_dir="$LAST_RUN_CLASS_ATTEMPT_DIR"
  local build_root source_path relative_path destination serial
  local copied=0
  local snapshot_failed=0
  local raw_junit_count=0
  local raw_junit_status
  local primary_classification
  local outer_timeout_phase
  local attempt_failure_phase
  local activity_processes_sha256=""
  local activity_processes_size_bytes=""
  local activity_processes_captured_at_utc=""
  local -a build_roots=()

  mapfile -t build_roots < <(journey_module_build_roots "$module" "$suffix") || return 1
  for build_root in "${build_roots[@]}"; do
    for source_path in \
      "$build_root/reports/androidTests" \
      "$build_root/outputs/androidTest-results" \
      "$build_root/outputs/connected_android_test_additional_output"; do
      [[ -e "$source_path" ]] || continue
      relative_path="${source_path#"$REPO_ROOT"/}"
      destination="$attempt_dir/android-test-outputs/$relative_path"
      mkdir -p "$(dirname "$destination")" || {
        snapshot_failed=1
        continue
      }
      if cp -a "$source_path" "$destination"; then
        copied=$((copied + 1))
      else
        snapshot_failed=1
      fi
    done
  done
  if (( copied == 0 )); then
    printf 'No connected-test report/output directories were produced by this attempt.\n' \
      > "$attempt_dir/android-test-outputs-missing.txt" || snapshot_failed=1
  fi

  if [[ -d "$attempt_dir/android-test-outputs" ]]; then
    raw_junit_count="$(
      find "$attempt_dir/android-test-outputs" -type f -name 'TEST-*.xml' -print \
        | wc -l
    )" || snapshot_failed=1
    raw_junit_count="${raw_junit_count//[[:space:]]/}"
  fi
  [[ "$raw_junit_count" =~ ^[0-9]+$ ]] || {
    raw_junit_count=0
    snapshot_failed=1
  }
  if (( raw_junit_count > 0 )); then
    raw_junit_status="present"
  elif is_timeout_rc "$primary_rc"; then
    raw_junit_status="absent_by_outer_timeout"
  else
    raw_junit_status="absent"
  fi
  if [[ "$primary_rc" -eq 0 ]]; then
    primary_classification="pass"
  elif is_timeout_rc "$primary_rc"; then
    primary_classification="outer_timeout"
  else
    primary_classification="failure"
  fi
  # Issue #1814: attribute an outer timeout to the phase it died in, from this
  # attempt's own streamed log. Issue #1840: do the same for a plain failure, so
  # a retry whose BUILD died is distinguishable from a genuine journey failure.
  outer_timeout_phase="$(
    journey_attempt_phase "$attempt_dir/attempt.log" "$primary_classification" \
      outer_timeout "$raw_junit_count"
  )"
  attempt_failure_phase="$(
    journey_attempt_phase "$attempt_dir/attempt.log" "$primary_classification" \
      failure "$raw_junit_count"
  )"

  if serial="$(journey_resolve_device_serial)"; then
    if capture_required_nonempty \
        "device logcat" "$attempt_dir/device-logcat.txt" \
        journey_adb -s "$serial" logcat -d -v threadtime; then
      printf 'device_logcat=ok\n' >> "$attempt_dir/manifest.txt" \
        || snapshot_failed=1
    else
      printf 'device_logcat=failed\n' >> "$attempt_dir/manifest.txt" 2>/dev/null \
        || true
      snapshot_failed=1
    fi

    if [[ "$primary_rc" -ne 0 ]]; then
      capture_required_nonempty \
        "device process list" "$attempt_dir/device-processes.txt" \
        journey_adb -s "$serial" shell ps -A \
        || snapshot_failed=1
      if capture_required_nonempty \
          "activity processes" "$attempt_dir/activity-processes.txt" \
          journey_adb -s "$serial" shell dumpsys activity processes; then
        # Bind the exact process dump bytes to this attempt while capture is
        # still in progress.  The focus-ANR classifier verifies all three
        # fields before this evidence can downgrade a product failure to INFRA;
        # a later regular-file copy from a sibling attempt therefore fails
        # closed just like a symlink or missing snapshot.
        printf '\nPOCKETSHELL_ATTEMPT_CAPTURE_TOKEN=%s\n' \
          "$LAST_RUN_CLASS_CAPTURE_TOKEN" \
          >> "$attempt_dir/activity-processes.txt" || snapshot_failed=1
        activity_processes_captured_at_utc="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
        activity_processes_sha256="$(
          sha256sum "$attempt_dir/activity-processes.txt" | awk '{ print $1 }'
        )" || snapshot_failed=1
        activity_processes_size_bytes="$(
          wc -c < "$attempt_dir/activity-processes.txt"
        )" || snapshot_failed=1
        activity_processes_size_bytes="${activity_processes_size_bytes//[[:space:]]/}"
        [[ "$activity_processes_sha256" =~ ^[0-9a-f]{64}$ ]] \
          || snapshot_failed=1
        [[ "$activity_processes_size_bytes" =~ ^[1-9][0-9]*$ ]] \
          || snapshot_failed=1
      else
        snapshot_failed=1
      fi
      capture_required_nonempty \
        "activity top" "$attempt_dir/activity-top.txt" \
        journey_adb -s "$serial" shell dumpsys activity top \
        || snapshot_failed=1
      if capture_required_png \
          "$attempt_dir/failure-screen.png" \
          journey_adb -s "$serial" exec-out screencap -p; then
        printf 'fallback_screenshot=ok\n' >> "$attempt_dir/manifest.txt" \
          || snapshot_failed=1
      else
        printf 'fallback_screenshot=failed\n' >> "$attempt_dir/manifest.txt" 2>/dev/null \
          || true
        snapshot_failed=1
      fi
    fi
  else
    printf 'device_logcat=unavailable\n' >> "$attempt_dir/manifest.txt" 2>/dev/null \
      || true
    snapshot_failed=1
    if [[ "$primary_rc" -ne 0 ]]; then
      printf 'Device unavailable for post-failure diagnostics.\n' \
        > "$attempt_dir/device-diagnostics-missing.txt" || snapshot_failed=1
    fi
  fi

  {
    printf 'primary_exit_code=%s\n' "$primary_rc"
    printf 'primary_classification=%s\n' "$primary_classification"
    printf 'raw_junit_status=%s\n' "$raw_junit_status"
    printf 'raw_junit_count=%s\n' "$raw_junit_count"
    printf 'outer_timeout_phase=%s\n' "$outer_timeout_phase"
    printf 'attempt_failure_phase=%s\n' "$attempt_failure_phase"
    printf 'snapshotted_output_roots=%s\n' "$copied"
    if [[ -n "$activity_processes_sha256" ]]; then
      printf 'activity_processes_sha256=%s\n' "$activity_processes_sha256"
      printf 'activity_processes_size_bytes=%s\n' "$activity_processes_size_bytes"
      printf 'activity_processes_captured_at_utc=%s\n' \
        "$activity_processes_captured_at_utc"
    fi
    printf 'snapshot_status=%s\n' "$([[ "$snapshot_failed" -eq 0 ]] && printf complete || printf failed)"
  } >> "$attempt_dir/manifest.txt" || snapshot_failed=1

  LAST_RUN_CLASS_OUTER_TIMEOUT_PHASE="$outer_timeout_phase"
  LAST_RUN_CLASS_ATTEMPT_FAILURE_PHASE="$attempt_failure_phase"
  LAST_RUN_CLASS_PRIMARY_CLASSIFICATION="$primary_classification"
  LAST_RUN_CLASS_RAW_JUNIT_STATUS="$raw_junit_status"
  LAST_RUN_CLASS_RAW_JUNIT_COUNT="$raw_junit_count"
  LAST_RUN_CLASS_SNAPSHOT_STATUS="$(
    [[ "$snapshot_failed" -eq 0 ]] && printf complete || printf failed
  )"

  if (( snapshot_failed != 0 )); then
    echo "JOURNEY_ATTEMPT_ARTIFACT_FAILED: could not preserve every existing output for $fqcn" >&2
    return 1
  fi
  echo "JOURNEY_ATTEMPT_ARTIFACT_SNAPSHOT: $fqcn -> ${attempt_dir#"$REPO_ROOT"/}"
}

write_harness_verdict_xml() {
  local destination="$1"
  local cleanup_status="$2"
  local final_status="$3"
  local python_command="${PYTHON3:-python3}"

  command -v "$python_command" >/dev/null 2>&1 || return 1
  command "$python_command" -I - \
    "$destination" \
    "$LAST_RUN_CLASS_SELECTOR" \
    "$LAST_RUN_CLASS_MODULE" \
    "$LAST_RUN_CLASS_ATTEMPT" \
    "$LAST_RUN_CLASS_PRIMARY_RC" \
    "$LAST_RUN_CLASS_PRIMARY_CLASSIFICATION" \
    "$LAST_RUN_CLASS_RAW_JUNIT_STATUS" \
    "$LAST_RUN_CLASS_RAW_JUNIT_COUNT" \
    "$LAST_RUN_CLASS_SNAPSHOT_STATUS" \
    "$cleanup_status" \
    "$final_status" <<'PY'
import os
import sys
import tempfile
import xml.etree.ElementTree as ET

(
    destination,
    selector,
    module,
    attempt,
    primary_rc,
    classification,
    raw_status,
    raw_count,
    snapshot_status,
    cleanup_status,
    final_status,
) = sys.argv[1:]

root = ET.Element("journey-harness-result", {"format-version": "1"})
for tag, value in (
    ("selector", selector),
    ("module", module),
    ("attempt", attempt),
    ("primary-exit-code", primary_rc),
    ("classification", classification),
):
    ET.SubElement(root, tag).text = value
ET.SubElement(root, "raw-junit", {"status": raw_status, "count": raw_count})
ET.SubElement(root, "snapshot", {"status": snapshot_status})
ET.SubElement(root, "cleanup", {"status": cleanup_status})
ET.SubElement(root, "final-status").text = final_status

directory = os.path.dirname(destination)
fd, temporary = tempfile.mkstemp(prefix=".journey-harness-verdict.", suffix=".tmp", dir=directory)
try:
    with os.fdopen(fd, "wb") as output:
        ET.ElementTree(root).write(output, encoding="utf-8", xml_declaration=True)
        output.flush()
        os.fsync(output.fileno())
    os.replace(temporary, destination)
except BaseException:
    try:
        os.unlink(temporary)
    except FileNotFoundError:
        pass
    raise
PY
}

validate_harness_verdict_xml() {
  local source="$1"
  local cleanup_status="$2"
  local final_status="$3"
  local python_command="${PYTHON3:-python3}"

  [[ -s "$source" ]] || return 1
  command -v "$python_command" >/dev/null 2>&1 || return 1
  command "$python_command" -I - \
    "$source" \
    "$LAST_RUN_CLASS_SELECTOR" \
    "$LAST_RUN_CLASS_MODULE" \
    "$LAST_RUN_CLASS_ATTEMPT" \
    "$LAST_RUN_CLASS_PRIMARY_RC" \
    "$LAST_RUN_CLASS_PRIMARY_CLASSIFICATION" \
    "$LAST_RUN_CLASS_RAW_JUNIT_STATUS" \
    "$LAST_RUN_CLASS_RAW_JUNIT_COUNT" \
    "$LAST_RUN_CLASS_SNAPSHOT_STATUS" \
    "$cleanup_status" \
    "$final_status" <<'PY'
import sys
import xml.etree.ElementTree as ET

(
    source,
    selector,
    module,
    attempt,
    primary_rc,
    classification,
    raw_status,
    raw_count,
    snapshot_status,
    cleanup_status,
    final_status,
) = sys.argv[1:]

root = ET.parse(source).getroot()
assert root.tag == "journey-harness-result"
assert root.attrib == {"format-version": "1"}
assert root.findtext("selector") == selector
assert root.findtext("module") == module
assert root.findtext("attempt") == attempt
assert root.findtext("primary-exit-code") == primary_rc
assert root.findtext("classification") == classification
assert root.find("raw-junit").attrib == {"status": raw_status, "count": raw_count}
assert root.find("snapshot").attrib == {"status": snapshot_status}
assert root.find("cleanup").attrib == {"status": cleanup_status}
assert root.findtext("final-status") == final_status
assert len(root) == 9
PY
}

finalize_class_attempt_manifest() {
  local cleanup_status="$1"
  local final_status="$2"
  local manifest="$LAST_RUN_CLASS_ATTEMPT_DIR/manifest.txt"
  local verdict="$LAST_RUN_CLASS_ATTEMPT_DIR/journey-harness-verdict.xml"

  write_harness_verdict_xml "$verdict" "$cleanup_status" "$final_status" \
    || return 1
  validate_harness_verdict_xml "$verdict" "$cleanup_status" "$final_status" \
    || return 1
  {
    printf 'harness_verdict_kind=harness_owned\n'
    printf 'harness_verdict_xml=journey-harness-verdict.xml\n'
    printf 'harness_verdict_status=complete\n'
    printf 'cleanup_status=%s\n' "$cleanup_status"
    printf 'gradle_cleanup_exit_code=%s\n' "$LAST_RUN_CLASS_GRADLE_CLEANUP_RC"
    printf 'device_cleanup_exit_code=%s\n' "$LAST_RUN_CLASS_DEVICE_CLEANUP_RC"
    printf 'status=%s\n' "$final_status"
    printf 'finished_at_utc=%s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
  } >> "$manifest" || return 1

  [[ -s "$manifest" ]] \
    && [[ -s "$verdict" ]] \
    && grep -Fqx "cleanup_status=$cleanup_status" "$manifest" \
    && grep -Fqx "status=$final_status" "$manifest" \
    && grep -Fqx "harness_verdict_kind=harness_owned" "$manifest" \
    && grep -Fqx "harness_verdict_status=complete" "$manifest"
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
  read_rc=0
  while :; do
    if IFS= read -r -t "$no_output" -u "$rfd" line; then
      printf '%s\n' "$line"
      if [[ -n "${JOURNEY_CURRENT_ATTEMPT_LOG:-}" ]]; then
        printf '%s\n' "$line" >> "$JOURNEY_CURRENT_ATTEMPT_LOG"
      fi
    else
      read_rc=$?
      break
    fi
  done
  exec {rfd}<&-

  if (( read_rc > 128 )); then
    echo "JOURNEY_NO_OUTPUT_WATCHDOG: no output for ${no_output}s — hard-killing wedged connectedDebugAndroidTest (issue #1056)" >&2
    if [[ -n "${JOURNEY_CURRENT_ATTEMPT_LOG:-}" ]]; then
      printf 'JOURNEY_NO_OUTPUT_WATCHDOG: no output for %ss\n' "$no_output" \
        >> "$JOURNEY_CURRENT_ATTEMPT_LOG"
    fi
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
  local remaining cap rc attempt_start attempt_elapsed cleanup_status
  local notification_class="com.pocketshell.app.notifications.NoNotificationPromptOnAppOpenE2eTest"
  local app_id_suffix="${POCKETSHELL_APP_ID_SUFFIX:-}"
  local -a app_id_args=()
  local -a notification_gradle_args=(--max-workers=2)
  local -a network_fault_args=()
  if [[ "$fqcn" == *OutboundAttachmentOffsetResumeJourneyE2eTest* ]]; then
    network_fault_args+=(
      -Pandroid.testInstrumentationRunnerArguments.pocketshellNetworkFaultProofs=true
    )
  fi
  if [[ "$JOURNEY_ABORT_ISOLATION_FAILED" -eq 1 ]]; then
    return "$JOURNEY_HARNESS_FAILURE_RC"
  fi
  LAST_RUN_CLASS_TIMEOUT_HIT=0
  LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=0
  LAST_RUN_CLASS_PRIMARY_RC=0
  LAST_RUN_CLASS_ABORT_CLEANUP_FAILED=0
  LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED=0
  LAST_RUN_CLASS_GRADLE_CLEANUP_RC="not_run"
  LAST_RUN_CLASS_DEVICE_CLEANUP_RC="not_run"
  LAST_RUN_CLASS_OUTER_TIMEOUT_PHASE=""
  LAST_RUN_CLASS_ATTEMPT_FAILURE_PHASE=""
  if [[ -n "$app_id_suffix" ]]; then
    [[ "$app_id_suffix" =~ ^[A-Za-z0-9._]+$ ]] || {
      echo "JOURNEY_INVOCATION_IDENTITY_FAILED: invalid application id suffix '$app_id_suffix'" >&2
      LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED=1
      JOURNEY_ABORT_ISOLATION_FAILED=1
      return "$JOURNEY_HARNESS_FAILURE_RC"
    }
    app_id_args+=("-PpocketshellAppIdSuffix=$app_id_suffix")
  fi
  remaining="$(budget_remaining)"
  cap="$JOURNEY_CLASS_TIMEOUT_SECS"
  (( remaining < cap )) && cap="$remaining"
  if (( cap <= 0 )); then
    LAST_RUN_CLASS_TIMEOUT_HIT=1
    LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=1
    return 124
  fi
  if ! begin_class_attempt_artifacts app "$fqcn" "$app_id_suffix"; then
    LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED=1
    JOURNEY_ABORT_ISOLATION_FAILED=1
    return "$JOURNEY_HARNESS_FAILURE_RC"
  fi
  JOURNEY_CURRENT_ATTEMPT_LOG="$LAST_RUN_CLASS_ATTEMPT_DIR/attempt.log"
  attempt_start=$SECONDS
  if [[ "$fqcn" == "$notification_class" ]]; then
    # Issue #1741: this class must enter instrumentation already DENIED. The
    # canonical wrapper owns install -> external revoke/verify -> runner ->
    # result-count validation -> external grant/verify under the AVD lock.
    # POCKETSHELL_APP_ID_SUFFIX is inherited by the wrapper, which derives the
    # exact Gradle property and base/suffixed package identity itself.
    run_bounded "$cap" \
      "$REPO_ROOT/scripts/connected-test.sh" --no-pool \
      --deny-notifications-before-instrumentation \
      "${notification_gradle_args[@]}" \
      -Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true \
      -Pandroid.testInstrumentationRunnerArguments.timeout_msec=300000 \
      -Pandroid.testInstrumentationRunnerArguments.class="$fqcn" \
      --stacktrace
  else
    # Issue #1458: --max-workers=2 (parity with the Unit job) bounds Gradle's
    # worker fan-out while the emulator and Docker fixtures share the runner.
    # The Gradle DAEMON is still reused (no --no-daemon), preserving the #835
    # budget-fit for the ordinary class set.
    run_bounded "$cap" \
      "$GRADLEW" :app:connectedDebugAndroidTest \
      "${app_id_args[@]}" \
      --max-workers=2 \
      -Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true \
      "${network_fault_args[@]}" \
      -Pandroid.testInstrumentationRunnerArguments.timeout_msec=300000 \
      -Pandroid.testInstrumentationRunnerArguments.class="$fqcn" \
      --stacktrace
  fi
  rc=$?
  LAST_RUN_CLASS_PRIMARY_RC="$rc"
  attempt_elapsed=$((SECONDS - attempt_start))
  if [[ $rc -eq 124 || ( $rc -eq 137 && $attempt_elapsed -ge $cap ) ]]; then
    LAST_RUN_CLASS_TIMEOUT_HIT=1
  fi
  if budget_exhausted; then
    LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=1
  fi
  if ! snapshot_connected_test_outputs app "$fqcn" "$rc" "$app_id_suffix"; then
    LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED=1
    JOURNEY_ABORT_ISOLATION_FAILED=1
  fi
  # Issue #1814: name a still-building outer timeout for what it is.
  record_build_phase_timeout_attempt "$fqcn"
  # Issue #1840: and name a build-level failure for what it is.
  record_build_phase_failure_attempt "$fqcn"
  cleanup_status="not_required"
  if needs_gradle_cleanup_after_class_abort "$rc"; then
    cleanup_status="failed"
    cleanup_gradle_after_timeout "$fqcn"
    LAST_RUN_CLASS_GRADLE_CLEANUP_RC=$?
    cleanup_device_after_class_abort app "$fqcn" "$app_id_suffix"
    LAST_RUN_CLASS_DEVICE_CLEANUP_RC=$?
    if [[ "$LAST_RUN_CLASS_GRADLE_CLEANUP_RC" -eq 0 \
          && "$LAST_RUN_CLASS_DEVICE_CLEANUP_RC" -eq 0 ]]; then
      cleanup_status="verified_clean"
    else
      LAST_RUN_CLASS_ABORT_CLEANUP_FAILED=1
      JOURNEY_ABORT_ISOLATION_FAILED=1
    fi
  fi
  if ! finalize_class_attempt_manifest "$cleanup_status" \
      "$([[ "$JOURNEY_ABORT_ISOLATION_FAILED" -eq 0 ]] && printf complete || printf harness_failure)"; then
    echo "JOURNEY_ATTEMPT_ARTIFACT_FAILED: manifest finalization failed for $fqcn" >&2
    LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED=1
    JOURNEY_ABORT_ISOLATION_FAILED=1
  fi
  JOURNEY_CURRENT_ATTEMPT_LOG=""
  if [[ "$JOURNEY_ABORT_ISOLATION_FAILED" -eq 1 ]]; then
    return "$JOURNEY_HARNESS_FAILURE_RC"
  fi
  return "$rc"
}

# run_ct_class <FQCN> — runs ONE core-terminal proof class as its own
# :shared:core-terminal:connectedDebugAndroidTest invocation, wrapped in the
# SAME budget-capped timeout discipline as run_class.
run_ct_class() {
  local fqcn="$1"
  local remaining cap rc attempt_start attempt_elapsed cleanup_status
  if [[ "$JOURNEY_ABORT_ISOLATION_FAILED" -eq 1 ]]; then
    return "$JOURNEY_HARNESS_FAILURE_RC"
  fi
  LAST_RUN_CLASS_TIMEOUT_HIT=0
  LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=0
  LAST_RUN_CLASS_PRIMARY_RC=0
  LAST_RUN_CLASS_ABORT_CLEANUP_FAILED=0
  LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED=0
  LAST_RUN_CLASS_GRADLE_CLEANUP_RC="not_run"
  LAST_RUN_CLASS_DEVICE_CLEANUP_RC="not_run"
  LAST_RUN_CLASS_OUTER_TIMEOUT_PHASE=""
  LAST_RUN_CLASS_ATTEMPT_FAILURE_PHASE=""
  remaining="$(budget_remaining)"
  cap="$JOURNEY_CLASS_TIMEOUT_SECS"
  (( remaining < cap )) && cap="$remaining"
  if (( cap <= 0 )); then
    LAST_RUN_CLASS_TIMEOUT_HIT=1
    LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=1
    return 124
  fi
  if ! begin_class_attempt_artifacts core-terminal "$fqcn" ""; then
    LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED=1
    JOURNEY_ABORT_ISOLATION_FAILED=1
    return "$JOURNEY_HARNESS_FAILURE_RC"
  fi
  JOURNEY_CURRENT_ATTEMPT_LOG="$LAST_RUN_CLASS_ATTEMPT_DIR/attempt.log"
  attempt_start=$SECONDS
  # Issue #1458: --max-workers=2 (parity with the Unit job + run_class) bounds
  # Gradle's worker fan-out while the proof shares the runner with the emulator.
  # Daemon reuse is preserved for the #835 budget fit.
  run_bounded "$cap" \
    "$GRADLEW" :shared:core-terminal:connectedDebugAndroidTest \
    --max-workers=2 \
    -Pandroid.testInstrumentationRunnerArguments.class="$fqcn" \
    --stacktrace
  rc=$?
  LAST_RUN_CLASS_PRIMARY_RC="$rc"
  attempt_elapsed=$((SECONDS - attempt_start))
  if [[ $rc -eq 124 || ( $rc -eq 137 && $attempt_elapsed -ge $cap ) ]]; then
    LAST_RUN_CLASS_TIMEOUT_HIT=1
  fi
  if budget_exhausted; then
    LAST_RUN_CLASS_BUDGET_EXHAUSTED_AFTER_ATTEMPT=1
  fi
  if ! snapshot_connected_test_outputs core-terminal "$fqcn" "$rc" ""; then
    LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED=1
    JOURNEY_ABORT_ISOLATION_FAILED=1
  fi
  # Issue #1814: name a still-building outer timeout for what it is.
  record_build_phase_timeout_attempt "$fqcn"
  # Issue #1840: and name a build-level failure for what it is.
  record_build_phase_failure_attempt "$fqcn"
  cleanup_status="not_required"
  if needs_gradle_cleanup_after_class_abort "$rc"; then
    cleanup_status="failed"
    cleanup_gradle_after_timeout "$fqcn"
    LAST_RUN_CLASS_GRADLE_CLEANUP_RC=$?
    cleanup_device_after_class_abort core-terminal "$fqcn" ""
    LAST_RUN_CLASS_DEVICE_CLEANUP_RC=$?
    if [[ "$LAST_RUN_CLASS_GRADLE_CLEANUP_RC" -eq 0 \
          && "$LAST_RUN_CLASS_DEVICE_CLEANUP_RC" -eq 0 ]]; then
      cleanup_status="verified_clean"
    else
      LAST_RUN_CLASS_ABORT_CLEANUP_FAILED=1
      JOURNEY_ABORT_ISOLATION_FAILED=1
    fi
  fi
  if ! finalize_class_attempt_manifest "$cleanup_status" \
      "$([[ "$JOURNEY_ABORT_ISOLATION_FAILED" -eq 0 ]] && printf complete || printf harness_failure)"; then
    echo "JOURNEY_ATTEMPT_ARTIFACT_FAILED: manifest finalization failed for $fqcn" >&2
    LAST_RUN_CLASS_ARTIFACT_SNAPSHOT_FAILED=1
    JOURNEY_ABORT_ISOLATION_FAILED=1
  fi
  JOURNEY_CURRENT_ATTEMPT_LOG=""
  if [[ "$JOURNEY_ABORT_ISOLATION_FAILED" -eq 1 ]]; then
    return "$JOURNEY_HARNESS_FAILURE_RC"
  fi
  return "$rc"
}

shard_class() {
  local idx="$1"
  local fqcn="$2"
  local notification_class="com.pocketshell.app.notifications.NoNotificationPromptOnAppOpenE2eTest"
  local notification_fixture_args=()
  local -a network_fault_args=()
  if [[ "$fqcn" == "$notification_class" ]]; then
    notification_fixture_args+=(--deny-notifications-before-instrumentation)
  fi
  if [[ "$fqcn" == *OutboundAttachmentOffsetResumeJourneyE2eTest* ]]; then
    network_fault_args+=(
      -Pandroid.testInstrumentationRunnerArguments.pocketshellNetworkFaultProofs=true
    )
  fi
  "$REPO_ROOT/scripts/connected-test.sh" --pool --suffix "ij$idx" \
    "${notification_fixture_args[@]}" \
    -Pandroid.testInstrumentationRunnerArguments.pocketshellCi=true \
    "${network_fault_args[@]}" \
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
