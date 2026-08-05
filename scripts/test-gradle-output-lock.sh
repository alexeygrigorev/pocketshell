#!/usr/bin/env bash
set -uo pipefail

# Gradle output-tree lock harness (issue #2007).
#
# THE DEFECT this harness exists to stop coming back. `scripts/connected-test.sh`
# and `scripts/full-jvm-gate.sh` are both canonical wrappers around `./gradlew`,
# and both rewrite the SAME `app/build/...` output graph of the checkout they run
# from. Nothing stopped them overlapping. During #893 validation in
# `.worktrees/issue-893-recurrence` a connected `--rerun-tasks` build and the
# full JVM gate ran at once and the gate died after 1m41s, before a single test:
#
#     :app:hiltAggregateDepsDebug
#     could not find generated app/build/.../processDebugResources/R.jar
#
# `--rerun-tasks` deletes and regenerates intermediates the sibling build is
# consuming. Gradle does not defend against this — its own locks cover the
# caches, not the output tree — so two builds in one project directory really do
# run at once and one of them dies on a missing generated artifact.
#
# It is worse than a flake: the failure looks like a product/test defect (a Hilt
# task blowing up on a missing R.jar) and lands in whichever lane LOST the race,
# not the one that caused it. The #893 gate had to be thrown away as evidence.
# Same shape as #1842, where a fixture collision manufactured evidence for a
# product bug that was not there and cost two review rounds.
#
# The load-bearing check is check 1: it drives TWO REAL `connected-test.sh`
# wrapper runs in ONE worktree, on DISTINCT stub emulators (so the per-serial AVD
# lock deliberately does NOT serialise them — it is a different resource), one
# behaving like `--rerun-tasks` and one like a connected build consuming R.jar.
# On the unfixed tree the consumer reports the maintainer's exact error and two
# builders are observed live at once; with the lock they queue and both pass.
#
# No Gradle, no Android SDK, no JDK, no emulator, no Docker: `gradlew`, `adb`,
# `systemctl` and `docker` are stubbed and both lock anchors are sandboxed.
# Racing the real emulator/output tree would corrupt a sibling agent's run — the
# exact bug under test.
#
# WHY THIS LIVES IN scripts/ AND NOT tests/scripts/: every shell test that
# actually executes per-push is a `scripts/test-*.sh` invoked by the Unit job;
# `tests/scripts/` is unwired legacy (see the note in
# scripts/test-agents-pool-isolation.sh). An unwired regression test is not a
# regression test (G9).

# A driving shell may already hold lock state; inherited, it would short-circuit
# the acquires below and make the harness self-contend (the #1702 lesson).
#
# POCKETSHELL_GRADLE_OUTPUT_LOCK_HELD_FILE is the only EXPORTED member of that
# set, hence the only one a driving shell can really pass down: inherited, it
# would make every lane below take the re-entrancy early-return and never
# contend at all. It is scrubbed first for that reason.
unset POCKETSHELL_GRADLE_OUTPUT_LOCK_HELD_FILE \
      POCKETSHELL_GRADLE_OUTPUT_LOCK_OWNER_PID \
      POCKETSHELL_GRADLE_OUTPUT_LOCK_HOLDER_PID \
      POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR \
      POCKETSHELL_GRADLE_OUTPUT_LOCK_WAIT_SECONDS \
      POCKETSHELL_AVD_LOCK_ACQUIRED \
      POCKETSHELL_AVD_LOCK_FILE \
      POCKETSHELL_AVD_LOCK_FD \
      POCKETSHELL_AVD_LOCK_DIR \
      POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED \
      POCKETSHELL_AGENTS_PORT \
      ANDROID_SERIAL

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ -f "$ROOT_DIR/scripts/connected-test.sh" ]] \
  || { printf 'FAIL: ROOT_DIR=%s does not look like the repo root\n' "$ROOT_DIR" >&2; exit 2; }

# Timings. The consumer polls for longer than the mutator's destructive window,
# so an unserialised pair ALWAYS overlaps: this reproduction is deterministic,
# not a sampled race.
MARKER_WAIT_SECONDS="${POCKETSHELL_OUTPUT_LOCK_TEST_MARKER_WAIT:-3}"
MUTATION_WINDOW_SECONDS="${POCKETSHELL_OUTPUT_LOCK_TEST_WINDOW:-1.2}"
CONSUME_SECONDS="${POCKETSHELL_OUTPUT_LOCK_TEST_CONSUME:-2.5}"

FAILURES=0

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  FAILURES=$((FAILURES + 1))
  return 1
}

pass() {
  printf '  ok: %s\n' "$1"
}

# --------------------------------------------------------------------------
# Fixture construction
# --------------------------------------------------------------------------

# A fake "worktree": a private root carrying its own copy of the wrappers,
# exactly like `.worktrees/issue-N/` carries its own checkout. Two of these is
# what two parallel agents have.
make_worktree() {
  local root="$1"
  mkdir -p "$root/scripts/lib" "$root/tests/docker" \
    "$root/app/build/intermediates/processDebugResources/debug"
  local script
  for script in connected-test.sh full-jvm-gate.sh; do
    cp "$ROOT_DIR/scripts/$script" "$root/scripts/$script" 2>/dev/null || true
  done
  for script in avd-lock.sh agents-pool.sh scope-run.sh gradle-output-lock.sh; do
    cp "$ROOT_DIR/scripts/lib/$script" "$root/scripts/lib/$script" 2>/dev/null || true
  done
  chmod +x "$root/scripts/"*.sh "$root/scripts/lib/"*.sh 2>/dev/null || true
  cp "$ROOT_DIR/tests/docker/docker-compose.yml" "$root/tests/docker/docker-compose.yml"
  printf 'generated by the fixture\n' > "$(r_jar "$root")"
  install_gradlew_stub "$root"
}

# The generated intermediate the #893 gate died on.
r_jar() {
  printf '%s/app/build/intermediates/processDebugResources/debug/R.jar\n' "$1"
}

# `./gradlew` stub. It plays one of the two real roles by POCKETSHELL_HARNESS_ROLE
# and records live-builder concurrency, so "did they overlap?" is measured, not
# inferred from a timing guess.
install_gradlew_stub() {
  local root="$1"
  cat > "$root/gradlew" <<'GRADLEW_STUB'
#!/usr/bin/env bash
set -uo pipefail
state="${HARNESS_STATE:?HARNESS_STATE must be set}"
role="${POCKETSHELL_HARNESS_ROLE:-consume}"
root_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
r_jar="$root_dir/app/build/intermediates/processDebugResources/debug/R.jar"

printf '%s %s %s\n' "$(date +%s.%N)" "$role" "invoked" >> "$state/gradlew.log"

bump() {
  local delta="$1" now peak
  (
    flock 8
    now="$(cat "$state/count" 2>/dev/null || printf '0')"
    now=$((now + delta))
    printf '%s\n' "$now" > "$state/count"
    peak="$(cat "$state/max" 2>/dev/null || printf '0')"
    if (( now > peak )); then printf '%s\n' "$now" > "$state/max"; fi
  ) 8>"$state/count.lock"
}

wait_for_marker() {
  local deadline_ticks="$1" ticks=0
  while [[ ! -e "$state/consumer-started" ]]; do
    (( ticks++ >= deadline_ticks )) && return 1
    sleep 0.05
  done
  return 0
}

bump 1
rc=0
case "$role" in
  rerun)
    # `test --rerun-tasks`: delete the generated intermediate, take a while to
    # regenerate it. Waiting for the consumer's marker first makes the overlap
    # deterministic on an unserialised tree instead of a coin flip.
    wait_for_marker "${MARKER_WAIT_TICKS:-60}" || true
    rm -f "$r_jar"
    sleep "${MUTATION_WINDOW:-1.2}"
    mkdir -p "$(dirname "$r_jar")"
    printf 'regenerated\n' > "$r_jar"
    ;;
  consume)
    # A connected build consuming the generated intermediate, e.g.
    # :app:hiltAggregateDepsDebug reading processDebugResources/R.jar.
    : > "$state/consumer-started"
    consume_ticks="${CONSUME_TICKS:-50}"
    tick=0
    while (( tick < consume_ticks )); do
      if [[ ! -f "$r_jar" ]]; then
        printf '%s\n' "> Task :app:hiltAggregateDepsDebug FAILED" >> "$state/gradlew.log"
        printf 'could not find generated %s\n' "$r_jar" >> "$state/gradlew.log"
        printf 'could not find generated %s\n' "$r_jar" >&2
        rc=1
        break
      fi
      tick=$((tick + 1))
      sleep 0.05
    done
    ;;
  *)
    printf 'unknown harness role: %s\n' "$role" >&2
    rc=2
    ;;
esac
bump -1
printf '%s %s exit=%s\n' "$(date +%s.%N)" "$role" "$rc" >> "$state/gradlew.log"
exit "$rc"
GRADLEW_STUB
  chmod +x "$root/gradlew"
}

# adb / systemctl / docker stubs. Two online emulators so the two lanes claim
# DISTINCT serials and their per-serial AVD locks do NOT serialise them — the
# whole point of check 1.
install_stub_bin() {
  local bindir="$1"
  mkdir -p "$bindir"
  cat > "$bindir/adb" <<'ADB_STUB'
#!/usr/bin/env bash
set -uo pipefail
if [[ "${1:-}" == "devices" ]]; then
  printf 'List of devices attached\n'
  printf 'emulator-5554\tdevice\n'
  printf 'emulator-5556\tdevice\n'
  exit 0
fi
exit 0
ADB_STUB
  # No reachable user systemd => scope-run degrades to the bare invocation
  # (with POCKETSHELL_SCOPE_ALLOW_BARE=1) instead of creating real transient
  # scopes for a stub build.
  cat > "$bindir/systemctl" <<'SYSTEMCTL_STUB'
#!/usr/bin/env bash
exit 1
SYSTEMCTL_STUB
  cat > "$bindir/docker" <<'DOCKER_STUB'
#!/usr/bin/env bash
exit 0
DOCKER_STUB
  chmod +x "$bindir/adb" "$bindir/systemctl" "$bindir/docker"
}

new_state_dir() {
  local state="$1"
  rm -rf "$state"
  mkdir -p "$state"
  printf '0\n' > "$state/count"
  printf '0\n' > "$state/max"
  : > "$state/gradlew.log"
}

# Start one REAL connected-test.sh lane in its own process group.
#
# The pid is published in $LANE_PID rather than echoed: a background job started
# inside a command substitution is a grandchild of the caller, and `wait` on a
# non-child returns 127 -- which reads exactly like a crashed lane.
start_connected_lane() {
  local root="$1" role="$2" suffix="$3" state="$4" log="$5"
  # Optional: pin the lane to one emulator serial, override its consume length,
  # bound its output-lock wait, and bound its pool-serial wait. Defaults keep the
  # original 5-arg behaviour.
  local serial="${6:-}" consume_ticks="${7:-$CONSUME_TICKS}" wait_seconds="${8:-}"
  local pool_wait_seconds="${9:-}"
  # `-u ANDROID_SERIAL` FIRST, then the conditional assignment: with an empty
  # $serial the lane must reach connected-test.sh's own serial ALLOCATION path
  # (pocketshell_claim_pool_serial), which a preset serial skips entirely. env
  # applies -u before the assignment operands, so the pinned case is unaffected.
  setsid env -u ANDROID_SERIAL \
    HARNESS_STATE="$state" \
    POCKETSHELL_HARNESS_ROLE="$role" \
    MARKER_WAIT_TICKS="$MARKER_WAIT_TICKS" \
    MUTATION_WINDOW="$MUTATION_WINDOW_SECONDS" \
    CONSUME_TICKS="$consume_ticks" \
    POCKETSHELL_SCOPE_ALLOW_BARE=1 \
    ADB="$STUB_BIN/adb" \
    PATH="$STUB_BIN:$PATH" \
    POCKETSHELL_AVD_LOCK_DIR="$AVD_LOCK_DIR" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$OUTPUT_LOCK_DIR" \
    ${serial:+ANDROID_SERIAL="$serial"} \
    ${wait_seconds:+POCKETSHELL_GRADLE_OUTPUT_LOCK_WAIT_SECONDS="$wait_seconds"} \
    ${pool_wait_seconds:+POCKETSHELL_POOL_WAIT_SECONDS="$pool_wait_seconds"} \
    "$root/scripts/connected-test.sh" --suffix "$suffix" \
    -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.HarnessProbe \
    > "$log" 2>&1 &
  LANE_PID="$!"
}

# Start one full-gate-STYLE lane: the destructive `--rerun-tasks` mutation run
# through the very lock command the full JVM gate now holds around its build.
start_full_gate_style_lane() {
  local root="$1" state="$2" log="$3"
  setsid env \
    HARNESS_STATE="$state" \
    POCKETSHELL_HARNESS_ROLE=rerun \
    MARKER_WAIT_TICKS="$MARKER_WAIT_TICKS" \
    MUTATION_WINDOW="$MUTATION_WINDOW_SECONDS" \
    PATH="$STUB_BIN:$PATH" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$OUTPUT_LOCK_DIR" \
    "$root/scripts/lib/gradle-output-lock.sh" \
    --output-root "$root" --label "full-jvm-gate.sh style lane" \
    -- "$root/gradlew" test --rerun-tasks \
    > "$log" 2>&1 &
  LANE_PID="$!"
}

# Publishes the lane's exit status in $WAIT_RC. NOT via `$(...)`: `wait` inside
# a command substitution runs in a subshell that does not own the job, returns
# immediately without waiting, and yields a bogus status -- which silently turns
# every concurrency assertion below into a measurement of two lanes that had not
# started yet.
wait_pid() {
  WAIT_RC=0
  wait "$1" 2>/dev/null || WAIT_RC=$?
}

kill_group() {
  local pid="${1:-}"
  [[ -n "$pid" ]] || return 0
  kill -TERM -- "-$pid" 2>/dev/null || kill -TERM "$pid" 2>/dev/null || true
  wait "$pid" 2>/dev/null || true
}

wait_for_file() {
  local target="$1" timeout="${2:-5}" waited=0
  local limit=$(( timeout * 20 ))
  while [[ ! -e "$target" ]]; do
    (( waited++ >= limit )) && return 1
    sleep 0.05
  done
  return 0
}

# Bounded wait for a phrase to appear in a growing log. Returns non-zero on
# expiry so the caller HARD-fails instead of asserting against a lane that had
# not reached the interesting state yet (a vacuous pass).
wait_for_log() {
  local log="$1" phrase="$2" timeout="${3:-10}" waited=0
  local limit=$(( timeout * 20 ))
  while ! grep -qF "$phrase" "$log" 2>/dev/null; do
    (( waited++ >= limit )) && return 1
    sleep 0.05
  done
  return 0
}

# Is this flock free right now? A subshell so the probe's own lock is dropped
# immediately, and `>>` so a live holder's owner line is never truncated.
lock_is_free() {
  ( flock -n 9 ) 9>>"$1" 2>/dev/null
}

# Every process that currently has $1 open, for failure diagnostics. Bounded to
# this user's own processes; it never signals anything.
processes_holding() {
  local target="$1" fd pid out=""
  for fd in /proc/[0-9]*/fd/*; do
    [[ -e "$fd" ]] || continue
    if [[ "$(readlink -f "$fd" 2>/dev/null)" == "$target" ]]; then
      pid="${fd#/proc/}"
      pid="${pid%%/*}"
      out+="$(ps -o pid=,ppid=,args= -p "$pid" 2>/dev/null)"$'\n'
    fi
  done
  printf '%s' "${out:-<nothing has the lock file open>}"
}

# Resolve the per-serial AVD lock path through the SAME sandboxed lock dir the
# lanes use, so the probe below inspects the file a lane would really take.
resolve_avd_lock_file() {
  local root="$1" serial="$2"
  POCKETSHELL_AVD_LOCK_DIR="$AVD_LOCK_DIR" bash -c \
    'source "$1/scripts/lib/avd-lock.sh"; pocketshell_avd_lock_file_for_serial "$1" "$2"' \
    bash "$root" "$serial" 2>/dev/null
}

observed_peak() {
  cat "$1/max" 2>/dev/null || printf 'unknown'
}

# Resolve a lock path through a worktree's OWN copy of the library, and hard-fail
# when the resolver produced nothing. Without this an absent library resolves to
# the empty string and every "these two paths differ" comparison below passes
# vacuously -- observed on the unfixed tree, where check 10 went green over two
# empty strings.
resolve_lock_file() {
  local root="$1" lane="${2:-}"
  local resolved
  resolved="$(bash -c 'source "$1/scripts/lib/gradle-output-lock.sh" 2>/dev/null || exit 1; pocketshell_gradle_output_lock_file "$2" "$3"' \
    bash "$root" "${3:-$root}" "$lane" 2>/dev/null)"
  if [[ -z "$resolved" ]]; then
    printf '<unresolved: %s has no working scripts/lib/gradle-output-lock.sh>\n' "$root"
    return 1
  fi
  printf '%s\n' "$resolved"
}

# Hold one output lock in the background until $release appears. Echoes the pid.
start_lock_holder() {
  local root="$1" lane="$2" label="$3" release="$4" ready="$5"
  setsid env \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$OUTPUT_LOCK_DIR" \
    "$ROOT_DIR/scripts/lib/gradle-output-lock.sh" \
    --output-root "$root" ${lane:+--lane "$lane"} --label "$label" \
    -- bash -c 'printf ready > "$1"; while [[ ! -e "$2" ]]; do sleep 0.05; done' \
    bash "$ready" "$release" \
    >/dev/null 2>&1 &
  printf '%s\n' "$!"
}

# --------------------------------------------------------------------------
# 1. THE BUG (red on base). Two REAL connected-test.sh wrapper runs in ONE
#    worktree, on DISTINCT emulators, must not rewrite one output tree at once.
#
#    This is the #893 failure reduced to two processes. On the unfixed tree the
#    consumer reports the maintainer's exact "could not find generated ...R.jar"
#    and two builders are observed live simultaneously.
# --------------------------------------------------------------------------
concurrent_wrapper_runs_do_not_corrupt_one_output_tree() {
  local tmp="$1"
  local wt="$tmp/wt-shared"
  make_worktree "$wt"
  local state="$tmp/state-1"
  new_state_dir "$state"

  local consumer_pid mutator_pid
  start_connected_lane "$wt" consume i2007c "$state" "$tmp/lane-consume.log"
  consumer_pid="$LANE_PID"
  start_connected_lane "$wt" rerun i2007r "$state" "$tmp/lane-rerun.log"
  mutator_pid="$LANE_PID"

  local consumer_rc mutator_rc
  wait_pid "$consumer_pid"; consumer_rc="$WAIT_RC"
  wait_pid "$mutator_pid"; mutator_rc="$WAIT_RC"

  local peak
  peak="$(observed_peak "$state")"

  if grep -q 'could not find generated' "$state/gradlew.log" 2>/dev/null; then
    fail "two canonical wrapper runs in ONE worktree corrupted each other's gradle output tree: the connected lane hit the reported failure while the sibling --rerun-tasks lane regenerated intermediates (peak concurrent builders=$peak). This is issue #2007 / the #893 gate death:
$(sed 's/^/      /' "$state/gradlew.log")"
    return 1
  fi
  if [[ "$peak" != "1" ]]; then
    fail "peak concurrent gradle builders in one worktree was $peak, expected 1 -- the output-tree lock did not serialise the two canonical wrappers (issue #2007). gradlew log:
$(sed 's/^/      /' "$state/gradlew.log")
    consumer lane (rc=$consumer_rc):
$(tail -n 20 "$tmp/lane-consume.log" | sed 's/^/      /')
    mutator lane (rc=$mutator_rc):
$(tail -n 20 "$tmp/lane-rerun.log" | sed 's/^/      /')"
    return 1
  fi
  if [[ "$consumer_rc" != "0" || "$mutator_rc" != "0" ]]; then
    fail "serialised lanes should both succeed, got consumer rc=$consumer_rc mutator rc=$mutator_rc. Lane logs:
$(sed 's/^/      /' "$tmp/lane-consume.log" | tail -n 20)
$(sed 's/^/      /' "$tmp/lane-rerun.log" | tail -n 20)"
    return 1
  fi
  # Both really ran: a lock that made one lane silently skip its build would be
  # a worse bug than the one under test (the vacuous-green trap).
  local invocations
  invocations="$(grep -c ' invoked$' "$state/gradlew.log" 2>/dev/null || printf '0')"
  if [[ "$invocations" != "2" ]]; then
    fail "expected BOTH lanes to actually run gradle (2 invocations), saw $invocations -- a serialisation that skips a build proves nothing"
    return 1
  fi
  pass "two connected-test.sh runs in one worktree serialise on the output tree (peak builders=1, both built)"
}

# --------------------------------------------------------------------------
# 2. The reported PAIR: a connected lane and a full-gate-style lane. The gate
#    holds this same lock command around its `test --rerun-tasks` graph.
# --------------------------------------------------------------------------
a_connected_lane_and_a_full_gate_style_lane_serialize() {
  local tmp="$1"
  local wt="$tmp/wt-cross"
  make_worktree "$wt"
  local state="$tmp/state-2"
  new_state_dir "$state"

  local consumer_pid gate_pid
  start_connected_lane "$wt" consume i2007x "$state" "$tmp/cross-consume.log"
  consumer_pid="$LANE_PID"
  start_full_gate_style_lane "$wt" "$state" "$tmp/cross-gate.log"
  gate_pid="$LANE_PID"

  local consumer_rc gate_rc
  wait_pid "$consumer_pid"; consumer_rc="$WAIT_RC"
  wait_pid "$gate_pid"; gate_rc="$WAIT_RC"
  local peak
  peak="$(observed_peak "$state")"

  if grep -q 'could not find generated' "$state/gradlew.log" 2>/dev/null; then
    fail "a connected-test.sh lane and a full-gate-style --rerun-tasks lane corrupted one output tree (peak builders=$peak) -- exactly the #893 pairing this issue reports"
    return 1
  fi
  if [[ "$peak" != "1" ]]; then
    fail "connected + full-gate-style peak concurrency was $peak, expected 1"
    return 1
  fi
  if [[ "$consumer_rc" != "0" || "$gate_rc" != "0" ]]; then
    fail "serialised cross-wrapper lanes should both succeed, got consumer rc=$consumer_rc gate rc=$gate_rc:
$(tail -n 15 "$tmp/cross-gate.log" | sed 's/^/      /')"
    return 1
  fi
  pass "a connected lane and a full-gate-style lane serialise on one worktree output tree"
}

# --------------------------------------------------------------------------
# 3. NON-GOAL GUARD. Distinct worktrees write distinct app/build trees and MUST
#    keep running concurrently. Serialising them would queue every parallel
#    agent behind every other -- worse than the bug (G6, the #1842 lesson).
# --------------------------------------------------------------------------
distinct_worktrees_still_build_concurrently() {
  local tmp="$1"
  local a="$tmp/wt-par-a" b="$tmp/wt-par-b"
  make_worktree "$a"
  make_worktree "$b"

  local release="$tmp/par.release" ready_a="$tmp/par.a.ready" ready_b="$tmp/par.b.ready"
  local a_pid b_pid
  a_pid="$(start_lock_holder "$a" "" "worktree A" "$release" "$ready_a")"
  if ! wait_for_file "$ready_a" 10; then
    kill_group "$a_pid"
    fail "worktree A never acquired its own output lock"
    return 1
  fi
  b_pid="$(start_lock_holder "$b" "" "worktree B" "$release" "$ready_b")"
  if ! wait_for_file "$ready_b" 10; then
    touch "$release"; kill_group "$a_pid"; kill_group "$b_pid"
    fail "worktree B could not build while worktree A was building. Distinct checkouts own DISTINCT app/build trees; serialising them would queue every parallel agent behind every other (issue #2007 non-goal)"
    return 1
  fi
  touch "$release"
  kill_group "$a_pid"; kill_group "$b_pid"
  pass "distinct worktrees hold distinct output locks and still build concurrently"
}

# --------------------------------------------------------------------------
# 4. NON-GOAL GUARD. A `--pool` lane relocates every project's build dir to
#    build/lane-<suffix> (issue #724). Those ARE disjoint output trees, so two
#    pool lanes in ONE worktree must keep their concurrency.
# --------------------------------------------------------------------------
pool_lanes_in_one_worktree_keep_their_concurrency() {
  local tmp="$1"
  local wt="$tmp/wt-pool"
  make_worktree "$wt"

  local release="$tmp/pool.release" ready_a="$tmp/pool.a.ready" ready_b="$tmp/pool.b.ready"
  local a_pid b_pid
  a_pid="$(start_lock_holder "$wt" "lane-i100" "pool lane A" "$release" "$ready_a")"
  if ! wait_for_file "$ready_a" 10; then
    kill_group "$a_pid"
    fail "pool lane A never acquired its lane output lock"
    return 1
  fi
  b_pid="$(start_lock_holder "$wt" "lane-i200" "pool lane B" "$release" "$ready_b")"
  if ! wait_for_file "$ready_b" 10; then
    touch "$release"; kill_group "$a_pid"; kill_group "$b_pid"
    fail "a second --pool lane could not build while the first was building; #724 relocates each lane's build dir to build/lane-<suffix>, so those disjoint trees must stay concurrent"
    return 1
  fi
  touch "$release"
  kill_group "$a_pid"; kill_group "$b_pid"

  # ... and a lane must NOT resolve the default build dir's lock, or the lane
  # token would be a blanket opt-out from the serialisation.
  local plain_a plain_b
  plain_a="$(resolve_lock_file "$wt" "")" || { fail "$plain_a"; return 1; }
  plain_b="$(resolve_lock_file "$wt" "lane-i100")" || { fail "$plain_b"; return 1; }
  if [[ "$plain_a" == "$plain_b" ]]; then
    fail "a pool lane resolved the SAME lock file as the default build dir; then #724 lanes would serialise"
    return 1
  fi
  pass "pool lanes (build/lane-<suffix>) keep distinct locks and stay concurrent"
}

# --------------------------------------------------------------------------
# 5. The lock ANCHOR. Machine-wide per-user, never inside the tree it protects,
#    and identical for two shells that name the same output root differently.
# --------------------------------------------------------------------------
lock_path_is_machine_anchored_and_per_output_tree() {
  local tmp="$1"
  local wt="$tmp/wt-anchor"
  make_worktree "$wt"
  mkdir -p "$tmp/link-parent"
  ln -sfn "$wt" "$tmp/link-parent/aliased-worktree"

  local direct aliased other
  direct="$(resolve_lock_file "$wt" "")" || { fail "$direct"; return 1; }
  aliased="$(resolve_lock_file "$wt" "" "$tmp/link-parent/aliased-worktree")" \
    || { fail "$aliased"; return 1; }
  other="$(resolve_lock_file "$tmp/wt-shared" "")" || { fail "$other"; return 1; }

  if [[ "$direct" != "$aliased" ]]; then
    fail "the same output tree reached by a symlink resolved to a DIFFERENT lock file ($direct vs $aliased) -- flock on distinct files serialises nothing (the #1657 defect)"
    return 1
  fi
  if [[ "$direct" == "$other" ]]; then
    fail "two different worktrees collapsed onto ONE lock file ($direct)"
    return 1
  fi
  if [[ "$direct" == "$wt"* || "$direct" == *"/build/"* ]]; then
    fail "the output lock lives inside the tree it protects ($direct); --rerun-tasks rewrites that tree"
    return 1
  fi
  if [[ "$direct" != "$OUTPUT_LOCK_DIR/"* ]]; then
    fail "POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR did not relocate the lock ($direct)"
    return 1
  fi
  pass "the output lock is machine-anchored, per output tree, and outside the tree it protects"
}

# --------------------------------------------------------------------------
# 6. The two implementations must agree. connected-test.sh resolves the lock in
#    Bash; scripts/full-jvm-gate.sh resolves it in Python because it distrusts
#    the inherited environment. Two wrappers that disagree about the path
#    exclude nothing -- and the disagreement would be SILENT, which is exactly
#    how #1657 and #1842 stayed broken.
# --------------------------------------------------------------------------
the_gate_and_the_wrappers_resolve_one_lock_file() {
  local tmp="$1"
  local wt="$tmp/wt-equiv"
  make_worktree "$wt"

  local from_bash from_python
  from_bash="$(resolve_lock_file "$wt" "")" || { fail "$from_bash"; return 1; }
  from_python="$(printf '' | env -u CI \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$OUTPUT_LOCK_DIR" \
    "$wt/scripts/full-jvm-gate.sh" --output-lock-probe 2>"$tmp/equiv.err" \
    | sed -n 's/^gradle_output_lock=//p')"

  if [[ -z "$from_python" ]]; then
    fail "the full JVM gate did not report an output lock; this check cannot say anything (guard against a vacuous pass). stderr: $(cat "$tmp/equiv.err")"
    return 1
  fi
  if [[ "$from_bash" != "$from_python" ]]; then
    fail "scripts/lib/gradle-output-lock.sh and scripts/full-jvm-gate.sh resolve DIFFERENT lock files for one output tree ($from_bash vs $from_python) -- the connected wrapper and the gate would never exclude each other (issue #2007)"
    return 1
  fi
  pass "the Bash wrapper half and the Python gate half resolve the identical lock file"
}

# --------------------------------------------------------------------------
# 7. The loser WAITS with a bounded, visible owner diagnostic and never starts
#    the build -- both halves. A silent multi-minute block is indistinguishable
#    from a wedge, and starting anyway is the bug.
# --------------------------------------------------------------------------
a_blocked_gate_reports_the_owner_and_never_starts() {
  local tmp="$1"
  local wt="$tmp/wt-queue-gate"
  make_worktree "$wt"

  local release="$tmp/queue-gate.release" ready="$tmp/queue-gate.ready"
  local holder_pid
  holder_pid="$(start_lock_holder "$wt" "" "harness holder for the gate" "$release" "$ready")"
  if ! wait_for_file "$ready" 10; then
    kill_group "$holder_pid"
    fail "the harness holder never acquired the lock, so this check would pass vacuously"
    return 1
  fi

  local rc=0
  printf '' | env -u CI \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$OUTPUT_LOCK_DIR" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_WAIT_SECONDS=2 \
    "$wt/scripts/full-jvm-gate.sh" --output-lock-probe \
    > "$tmp/queue-gate.out" 2> "$tmp/queue-gate.err" || rc=$?
  touch "$release"; kill_group "$holder_pid"

  if [[ "$rc" == "0" ]]; then
    fail "the full JVM gate STARTED while another run owned the output tree (rc=0). That is the #893 failure: --rerun-tasks would delete intermediates the sibling build is consuming"
    return 1
  fi
  if [[ "$rc" != "75" ]]; then
    fail "expected the distinct did-not-start rc 75 from the gate, got $rc. A queued lane that never built must never look like a red build. stderr: $(cat "$tmp/queue-gate.err")"
    return 1
  fi
  local phrase
  for phrase in "LOCKED by another canonical wrapper" "held by" "harness holder for the gate" "did NOT start"; do
    if ! grep -qF "$phrase" "$tmp/queue-gate.err"; then
      fail "the gate's contention diagnostic is missing '$phrase'; the operator must be able to see WHO owns the tree instead of watching a silent block. Got: $(cat "$tmp/queue-gate.err")"
      return 1
    fi
  done
  pass "a blocked full JVM gate names the owner, exits 75, and never starts a build"
}

a_blocked_connected_run_reports_the_owner_and_never_starts() {
  local tmp="$1"
  local wt="$tmp/wt-queue-connected"
  make_worktree "$wt"
  local state="$tmp/state-queue"
  new_state_dir "$state"

  local release="$tmp/queue-conn.release" ready="$tmp/queue-conn.ready"
  local holder_pid
  holder_pid="$(start_lock_holder "$wt" "" "harness holder for connected-test" "$release" "$ready")"
  if ! wait_for_file "$ready" 10; then
    kill_group "$holder_pid"
    fail "the harness holder never acquired the lock, so this check would pass vacuously"
    return 1
  fi

  local rc=0
  env \
    HARNESS_STATE="$state" \
    POCKETSHELL_HARNESS_ROLE=consume \
    CONSUME_TICKS=2 \
    POCKETSHELL_SCOPE_ALLOW_BARE=1 \
    ADB="$STUB_BIN/adb" \
    PATH="$STUB_BIN:$PATH" \
    POCKETSHELL_AVD_LOCK_DIR="$AVD_LOCK_DIR" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$OUTPUT_LOCK_DIR" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_WAIT_SECONDS=2 \
    "$wt/scripts/connected-test.sh" --suffix i2007q \
    -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.HarnessProbe \
    > "$tmp/queue-conn.out" 2> "$tmp/queue-conn.err" || rc=$?
  touch "$release"; kill_group "$holder_pid"

  if [[ "$rc" == "0" ]]; then
    fail "connected-test.sh ran its gradle task while another run owned this worktree's output tree (rc=0)"
    return 1
  fi
  if [[ "$rc" != "75" ]]; then
    fail "expected the distinct did-not-start rc 75 from connected-test.sh, got $rc. stderr tail: $(tail -n 15 "$tmp/queue-conn.err")"
    return 1
  fi
  if grep -q ' invoked$' "$state/gradlew.log" 2>/dev/null; then
    fail "connected-test.sh invoked gradle despite failing to own the output tree -- the refusal must happen BEFORE any task runs"
    return 1
  fi
  local phrase
  for phrase in "LOCKED by another canonical wrapper" "harness holder for connected-test" "did NOT start" "2007"; do
    if ! grep -qF "$phrase" "$tmp/queue-conn.err"; then
      fail "connected-test.sh's contention diagnostic is missing '$phrase'. Got: $(tail -n 20 "$tmp/queue-conn.err")"
      return 1
    fi
  done
  pass "a blocked connected-test.sh names the owner, exits 75, and invokes no gradle task"
}

# --------------------------------------------------------------------------
# 7b. ... but a NESTED wrapper must reuse its ancestor's ownership instead of
#     queuing behind it. Wrapping any script that later calls a canonical
#     wrapper would otherwise self-deadlock, and a self-deadlock presents as a
#     wedged lock -- the least debuggable failure this change could introduce.
#     Both halves are checked: an inner connected-test.sh (Bash) and an inner
#     full JVM gate (Python), each under a lock its ancestor already holds.
# --------------------------------------------------------------------------
a_nested_wrapper_reuses_its_ancestors_ownership() {
  local tmp="$1"
  local wt="$tmp/wt-nested"
  make_worktree "$wt"
  local state="$tmp/state-nested"
  new_state_dir "$state"

  # The ancestor holds the lock and then runs the inner wrappers, exactly as a
  # future gate wrapper would. A short bound turns a self-deadlock into a fast,
  # unmistakable failure rather than a 2-hour hang.
  local rc=0
  env \
    HARNESS_STATE="$state" \
    POCKETSHELL_HARNESS_ROLE=consume \
    CONSUME_TICKS=2 \
    POCKETSHELL_SCOPE_ALLOW_BARE=1 \
    ADB="$STUB_BIN/adb" \
    PATH="$STUB_BIN:$PATH" \
    POCKETSHELL_AVD_LOCK_DIR="$AVD_LOCK_DIR" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$OUTPUT_LOCK_DIR" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_WAIT_SECONDS=3 \
    "$wt/scripts/lib/gradle-output-lock.sh" --output-root "$wt" \
    --label "ancestor holding for a nested wrapper" \
    -- env CI= "$wt/scripts/connected-test.sh" --suffix i2007n \
    -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.HarnessProbe \
    > "$tmp/nested-conn.out" 2> "$tmp/nested-conn.err" || rc=$?

  if [[ "$rc" != "0" ]]; then
    fail "a connected-test.sh nested INSIDE a held output lock did not reuse its ancestor's ownership (rc=$rc); it queued behind its own ancestor, which is a self-deadlock that looks exactly like a wedged lock. stderr tail: $(tail -n 15 "$tmp/nested-conn.err")"
    return 1
  fi
  if ! grep -q ' invoked$' "$state/gradlew.log" 2>/dev/null; then
    fail "the nested connected-test.sh never reached gradle, so this check would pass vacuously"
    return 1
  fi
  if ! grep -qF "already owned by an ancestor" "$tmp/nested-conn.err"; then
    fail "the nested connected-test.sh did not say it was reusing its ancestor's ownership; a silent reuse is indistinguishable from having taken a second lock. Got: $(tail -n 15 "$tmp/nested-conn.err")"
    return 1
  fi

  rc=0
  printf '' | env \
    PATH="$STUB_BIN:$PATH" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$OUTPUT_LOCK_DIR" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_WAIT_SECONDS=3 \
    "$wt/scripts/lib/gradle-output-lock.sh" --output-root "$wt" \
    --label "ancestor holding for a nested gate" \
    -- env -u CI "$wt/scripts/full-jvm-gate.sh" --output-lock-probe \
    > "$tmp/nested-gate.out" 2> "$tmp/nested-gate.err" || rc=$?

  if [[ "$rc" != "0" ]]; then
    fail "the full JVM gate nested INSIDE a held output lock did not reuse its ancestor's ownership (rc=$rc) -- the Python half must behave like the Bash half or wrapping the gate self-deadlocks. stderr: $(tail -n 15 "$tmp/nested-gate.err")"
    return 1
  fi
  if ! grep -qF "already owned by an ancestor" "$tmp/nested-gate.err"; then
    fail "the nested gate did not report reusing its ancestor's ownership. Got: $(tail -n 15 "$tmp/nested-gate.err")"
    return 1
  fi
  pass "a nested wrapper (Bash and Python halves) reuses its ancestor's ownership instead of self-deadlocking"
}

# --------------------------------------------------------------------------
# 8a. DESIGN GUARD (not the crash property). Killing the lock-owning PROCESS
#     must free the lock, because flock lives on the open file description and
#     the kernel drops it when that process dies. This forbids a future
#     "improvement" to a pidfile/lock-content scheme, which WOULD wedge.
#
#     This check is deliberately NOT the crash property. Nothing in production
#     kills the holder specifically, so on its own it is a green structural
#     proxy over the real failure mode (G6) -- it passed for a whole review
#     round while a SIGKILLed *wrapper* left an orphaned holder wedging the
#     lock for the default 7200s. Check 8b is that property.
# --------------------------------------------------------------------------
a_crashed_holder_does_not_wedge_the_lock() {
  local tmp="$1"
  local wt="$tmp/wt-crash"
  make_worktree "$wt"

  local release="$tmp/crash.release" ready="$tmp/crash.ready"
  local holder_pid
  holder_pid="$(start_lock_holder "$wt" "" "about to be SIGKILLed" "$release" "$ready")"
  if ! wait_for_file "$ready" 10; then
    kill_group "$holder_pid"
    fail "the crash-test holder never acquired the lock"
    return 1
  fi

  kill -KILL -- "-$holder_pid" 2>/dev/null || kill -KILL "$holder_pid" 2>/dev/null || true
  wait "$holder_pid" 2>/dev/null || true

  local lock_file waited=0
  lock_file="$(resolve_lock_file "$wt" "")" || { fail "$lock_file"; return 1; }
  while (( waited < 100 )); do
    if ( flock -n 9 ) 9>>"$lock_file" 2>/dev/null; then
      pass "a SIGKILLed holder releases the lock (kernel-owned flock, no pidfile to wedge on)"
      return 0
    fi
    waited=$((waited + 1))
    sleep 0.05
  done
  fail "the lock is STILL held 5s after its holder was SIGKILLed -- a crashed run would strand every later build in that worktree (issue #2007)"
  return 1
}

# --------------------------------------------------------------------------
# 8b. THE CRASH PROPERTY (load-bearing). A SIGKILLed WRAPPER must leave the
#     output tree immediately reclaimable, so the next canonical run in that
#     worktree RUNS.
#
#     This is the production shape, not a hypothetical: `run_bounded`
#     (scripts/ci-journey-budget-functions.sh) escalates to `kill -KILL` on the
#     --no-pool connected path, and this box's session harness hard-kills
#     long-running background bash. SIGTERM is not the risk (the EXIT trap
#     releases); SIGKILL is, because every descendant the wrapper started is
#     reparented and keeps running.
#
#     The sibling per-serial AVD lock already guarantees exactly this
#     (tests/scripts/connected-test-serial-ownership-test.sh::
#     hard_killed_wrapper_leaves_no_descendant_flock, driven per-push from
#     app/src/test/java/com/pocketshell/app/scripts/AvdLockScriptTest.kt). Two
#     locks in one wrapper with OPPOSITE crash semantics is worse than either
#     alone, so this pins the output lock to the same contract.
# --------------------------------------------------------------------------
a_sigkilled_wrapper_leaves_no_wedged_output_lock() {
  local tmp="$1"
  local wt="$tmp/wt-wrapper-kill"
  make_worktree "$wt"
  local state="$tmp/state-wrapper-kill"
  new_state_dir "$state"

  # A REAL connected-test.sh, mid-build (600 ticks == 30s of "gradle") with the
  # output lock held, exactly as a lane looks when the harness kills it.
  start_connected_lane "$wt" consume i2007k "$state" "$tmp/kill-victim.log" \
    emulator-5554 600
  local victim_pid="$LANE_PID"
  if ! wait_for_log "$tmp/kill-victim.log" 'Acquired gradle output lock' 30; then
    kill_group "$victim_pid"
    fail "the victim lane never acquired the output lock, so this check would pass vacuously. Lane log: $(tail -n 20 "$tmp/kill-victim.log")"
    return 1
  fi

  local lock_file
  lock_file="$(resolve_lock_file "$wt" "")" || { kill_group "$victim_pid"; fail "$lock_file"; return 1; }

  # SIGKILL the WRAPPER ONLY -- never its process group. Killing the group would
  # take the holder with it and this check would pass over the very orphan it
  # exists to catch.
  kill -KILL "$victim_pid" 2>/dev/null || true
  wait "$victim_pid" 2>/dev/null || true

  local reclaimed=0 waited=0
  while (( waited < 300 )); do
    if lock_is_free "$lock_file"; then reclaimed=1; break; fi
    waited=$((waited + 1))
    sleep 0.05
  done

  if (( reclaimed == 0 )); then
    local orphans
    orphans="$(processes_holding "$lock_file")"
    kill -KILL -- "-$victim_pid" 2>/dev/null || true
    fail "the gradle output lock is STILL held 15s after connected-test.sh (pid $victim_pid) was SIGKILLed: a descendant survived the kill and kept the flock. Every later canonical run in that worktree then burns the full bounded wait (default 7200s) and exits 75 WITHOUT building -- the silent cross-lane starvation this lock exists to remove, and the opposite of what the same wrapper guarantees for the AVD lock (issue #2007). Still holding the lock file:
$(printf '%s' "$orphans" | sed 's/^/      /')"
    return 1
  fi

  # The user-visible property: the NEXT canonical run in that worktree runs.
  # A short bounded wait so it cannot pass by out-waiting a wedge.
  local state2="$tmp/state-wrapper-kill-2"
  new_state_dir "$state2"
  start_connected_lane "$wt" consume i2007k2 "$state2" "$tmp/kill-next.log" \
    emulator-5556 2 15
  local next_pid="$LANE_PID"
  wait_pid "$next_pid"
  local next_rc="$WAIT_RC"

  kill -KILL -- "-$victim_pid" 2>/dev/null || true

  if [[ "$next_rc" != "0" ]]; then
    fail "the next connected-test.sh in that worktree could not run after a sibling wrapper was SIGKILLed (rc=$next_rc; 75 means it queued out on the orphaned holder and built nothing). Lane log:
$(tail -n 20 "$tmp/kill-next.log" | sed 's/^/      /')"
    return 1
  fi
  if ! grep -q ' invoked$' "$state2/gradlew.log" 2>/dev/null; then
    fail "the follow-up lane exited 0 without ever invoking gradle, so this check would pass vacuously"
    return 1
  fi
  pass "a SIGKILLed connected-test.sh wrapper leaves no orphaned holder: the tree is reclaimable and the next run in that worktree builds"
}

# --------------------------------------------------------------------------
# 8c. The contention diagnostic must name the process that actually HOLDS the
#     lock, not just the wrapper. The flock lives in a dedicated holder process
#     (so no build child can inherit the descriptor), so recording only the
#     wrapper pid leaves an operator with no pointer to the live owner and makes
#     "stop it" unactionable. Both pids, and the holder pid must be real.
# --------------------------------------------------------------------------
the_contention_diagnostic_names_the_live_holder() {
  local tmp="$1"
  local wt="$tmp/wt-owner"
  make_worktree "$wt"
  local state="$tmp/state-owner"
  new_state_dir "$state"

  start_connected_lane "$wt" consume i2007o "$state" "$tmp/owner-holder.log" \
    emulator-5554 600
  local owner_pid="$LANE_PID"
  if ! wait_for_log "$tmp/owner-holder.log" 'Acquired gradle output lock' 30; then
    kill_group "$owner_pid"
    fail "the owning lane never acquired the output lock, so this check would pass vacuously. Lane log: $(tail -n 20 "$tmp/owner-holder.log")"
    return 1
  fi

  local rc=0
  env \
    HARNESS_STATE="$state" \
    POCKETSHELL_HARNESS_ROLE=consume \
    CONSUME_TICKS=2 \
    POCKETSHELL_SCOPE_ALLOW_BARE=1 \
    ADB="$STUB_BIN/adb" \
    PATH="$STUB_BIN:$PATH" \
    ANDROID_SERIAL=emulator-5556 \
    POCKETSHELL_AVD_LOCK_DIR="$AVD_LOCK_DIR" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$OUTPUT_LOCK_DIR" \
    POCKETSHELL_GRADLE_OUTPUT_LOCK_WAIT_SECONDS=2 \
    "$wt/scripts/connected-test.sh" --suffix i2007o2 \
    -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.HarnessProbe \
    > "$tmp/owner-blocked.out" 2> "$tmp/owner-blocked.err" || rc=$?

  local diagnostic reported_holder reported_wrapper
  diagnostic="$(grep -F 'held by:' "$tmp/owner-blocked.err" | tail -n1)"
  reported_holder="$(printf '%s\n' "$diagnostic" | sed -n 's/.*holder_pid=\([0-9]\{1,\}\).*/\1/p')"
  reported_wrapper="$(printf '%s\n' "$diagnostic" | sed -n 's/.*wrapper_pid=\([0-9]\{1,\}\).*/\1/p')"

  local lock_file holder_fd_ok=0
  lock_file="$(resolve_lock_file "$wt" "")" || true
  if [[ -n "$reported_holder" && -e "/proc/$reported_holder" ]]; then
    local fd
    for fd in /proc/"$reported_holder"/fd/*; do
      [[ -e "$fd" ]] || continue
      if [[ "$(readlink -f "$fd" 2>/dev/null)" == "$lock_file" ]]; then
        holder_fd_ok=1
        break
      fi
    done
  fi
  kill_group "$owner_pid"

  if [[ "$rc" != "75" ]]; then
    fail "the blocked lane should have timed out with rc 75 so its owner diagnostic could be inspected, got rc=$rc. stderr: $(tail -n 15 "$tmp/owner-blocked.err")"
    return 1
  fi
  if [[ -z "$reported_holder" ]]; then
    fail "the contention diagnostic does not record the pid of the process that HOLDS the lock (no holder_pid=). The flock lives in a dedicated holder process, so a diagnostic that names only the wrapper points an operator at a process that may already be dead, and 'stop the owner' becomes a no-op. Got: $diagnostic"
    return 1
  fi
  if [[ -z "$reported_wrapper" ]]; then
    fail "the contention diagnostic does not record the wrapper pid (no wrapper_pid=); the operator needs the process to actually stop, not just the descriptor holder. Got: $diagnostic"
    return 1
  fi
  if [[ "$reported_wrapper" != "$owner_pid" ]]; then
    fail "the diagnostic reported wrapper_pid=$reported_wrapper but the owning connected-test.sh is pid $owner_pid; the operator would chase the wrong process. Got: $diagnostic"
    return 1
  fi
  if [[ "$reported_holder" == "$reported_wrapper" ]]; then
    fail "holder_pid and wrapper_pid are identical ($reported_holder) although the Bash half holds the flock in a SEPARATE process; one of the two is wrong. Got: $diagnostic"
    return 1
  fi
  if (( holder_fd_ok == 0 )); then
    fail "the pid reported as holder_pid=$reported_holder does not have $lock_file open, so the diagnostic names a process that is not the owner (issue #2007). Got: $diagnostic"
    return 1
  fi
  pass "the contention diagnostic names both the live flock holder and the wrapper to stop"
}

# --------------------------------------------------------------------------
# 8d. A lane that LOSES the output-tree race must not be sitting on the
#     emulator. The AVD serial is the scarcest resource on this box (often ONE
#     emulator online); claiming it and then queuing up to two hours for a
#     build lock starves every other lane's device access -- a silent
#     cross-lane starvation introduced by the very change that removes one.
#     Ordering: output tree first, THEN the toxiproxy singleton, THEN the
#     serial, THEN the agents port. That order is also uniform across every
#     wrapper path, so two lanes can never deadlock holding half of each.
#
#     THIS IS A CLASS, NOT ONE CASE (issue #2007 review round 2, D31/G2).
#     connected-test.sh claims a serial at TWO different sites, and which one
#     runs depends on whether the caller preset ANDROID_SERIAL:
#
#       * no preset (the DEFAULT every agent invocation takes) ->
#         `pocketshell_claim_pool_serial` allocates and locks a free serial
#         (connected-test.sh ~:455);
#       * preset -> that allocator is skipped entirely and only
#         `pocketshell_acquire_avd_lock` (~:906) runs.
#
#     A guard that only drives the PRESET lane pins nothing about the site the
#     round-1 review actually observed starving on a real run, and stays green
#     while the acquire is moved to just after the allocator. That is the same
#     G6 wrong-cost shape as check 8a-vs-8b, one layer down: a green guard
#     standing in for the property it was meant to protect. Both members below
#     are load-bearing; neither substitutes for the other.
# --------------------------------------------------------------------------

# Shared body. `preset_serial` empty => the lane allocates its own serial.
# `guarded_serials` are every serial the lane could possibly end up holding, so
# the no-preset member constrains the allocator's whole candidate set rather
# than whichever one it happened to pick this run.
_assert_a_queued_lane_holds_no_emulator_serial() {
  local tmp="$1" tag="$2" preset_serial="$3" description="$4"
  shift 4
  local guarded_serials=("$@")

  local wt="$tmp/wt-$tag"
  make_worktree "$wt"
  local state="$tmp/state-$tag"
  new_state_dir "$state"
  local log="$tmp/$tag-lane.log"

  local release="$tmp/$tag.release" ready="$tmp/$tag.ready"
  local holder_pid
  holder_pid="$(start_lock_holder "$wt" "" "harness holder for the ordering check ($tag)" "$release" "$ready")"
  if ! wait_for_file "$ready" 10; then
    kill_group "$holder_pid"
    fail "[$description] the harness holder never acquired the lock, so this check would pass vacuously"
    return 1
  fi

  # Resolve and pre-flight EVERY guarded serial. An unresolvable or
  # already-held lock cannot tell the two states apart, so it is a hard failure
  # rather than a silent pass.
  local avd_locks=() serial avd_lock
  for serial in "${guarded_serials[@]}"; do
    avd_lock="$(resolve_avd_lock_file "$wt" "$serial")"
    if [[ -z "$avd_lock" ]]; then
      touch "$release"; kill_group "$holder_pid"
      fail "[$description] could not resolve the per-serial AVD lock path for $serial, so this check would pass vacuously"
      return 1
    fi
    if ! lock_is_free "$avd_lock"; then
      touch "$release"; kill_group "$holder_pid"
      fail "[$description] the sandboxed AVD lock for $serial ($avd_lock) was already held before the lane started; this check could not tell the two states apart"
      return 1
    fi
    avd_locks+=("$avd_lock")
  done

  # Pool wait 0: in the no-preset case every candidate serial is free, so the
  # allocator either claims instantly (wrong order -> caught below) or is never
  # reached (right order). Bounding it at 0 means a surprise busy sandbox fails
  # fast and loudly instead of blocking this check for the 600s default.
  start_connected_lane "$wt" consume "i2007$tag" "$state" "$log" \
    "$preset_serial" 2 8 0
  local lane_pid="$LANE_PID"
  if ! wait_for_log "$log" 'LOCKED by another canonical wrapper' 20; then
    touch "$release"; kill_group "$holder_pid"; kill_group "$lane_pid"
    fail "[$description] the lane never announced that it was queuing for the output tree, so this check would pass vacuously. Lane log: $(tail -n 20 "$log")"
    return 1
  fi

  local held_serials=() i
  for i in "${!guarded_serials[@]}"; do
    lock_is_free "${avd_locks[$i]}" \
      || held_serials+=("${guarded_serials[$i]} (${avd_locks[$i]})")
  done

  # Let the lane's own bounded wait expire BEFORE releasing the harness holder:
  # releasing first would hand it the tree and it would build, so the rc-75
  # assertion below would stop constraining anything.
  wait_pid "$lane_pid"
  local lane_rc="$WAIT_RC"
  touch "$release"
  kill_group "$holder_pid"

  if (( ${#held_serials[@]} > 0 )); then
    fail "[$description] a lane queuing for the gradle output tree was HOLDING an emulator lock: ${held_serials[*]}. On a box with one emulator online that starves every other lane's device access for the whole bounded wait (default 7200s) while nothing is built. Claim the output tree BEFORE any serial claim (issue #2007). Lane log:
$(tail -n 20 "$log" | sed 's/^/      /')"
    return 1
  fi
  if [[ "$lane_rc" != "75" ]]; then
    fail "[$description] expected the queued lane to give up with rc 75, got $lane_rc -- if it built instead, this check proved nothing. Lane log: $(tail -n 20 "$log")"
    return 1
  fi
  if grep -q ' invoked$' "$state/gradlew.log" 2>/dev/null; then
    fail "[$description] the queued lane invoked gradle despite never owning the output tree"
    return 1
  fi
  return 0
}

# Member (i): the caller pinned a device, so only pocketshell_acquire_avd_lock
# runs. Kept because an explicitly targeted lane must not starve the box either.
a_queued_lane_with_a_preset_serial_holds_no_emulator_lock() {
  local tmp="$1"
  _assert_a_queued_lane_holds_no_emulator_serial "$tmp" order "emulator-5556" \
    "preset ANDROID_SERIAL" "emulator-5556" || return 1
  pass "a lane queuing for the output tree with a PRESET serial holds no emulator lock"
}

# Member (ii): no preset, so the lane runs the DEFAULT allocation path
# (pocketshell_claim_pool_serial) -- the site the round-1 review observed
# claiming the emulator before the output tree on a real run. Both stub serials
# are guarded because the allocator is free to pick either.
a_queued_lane_that_allocates_its_own_serial_holds_none() {
  local tmp="$1"
  _assert_a_queued_lane_holds_no_emulator_serial "$tmp" alloc "" \
    "no preset ANDROID_SERIAL (default path)" "emulator-5554" "emulator-5556" \
    || return 1
  pass "a lane queuing for the output tree that ALLOCATES its own serial holds none (output lock precedes pocketshell_claim_pool_serial)"
}

# --------------------------------------------------------------------------
# 9. The per-AVD lock stays a SEPARATE concern (explicit issue non-goal). Two
#    lanes on distinct emulators legitimately hold distinct AVD locks and run
#    concurrently on the devices; they must still queue on the one output tree.
# --------------------------------------------------------------------------
the_avd_lock_stays_a_separate_concern() {
  local tmp="$1"
  local wt="$tmp/wt-avd"
  make_worktree "$wt"

  local avd_a avd_b output_lock
  avd_a="$(bash -c 'source "$1/scripts/lib/avd-lock.sh"; pocketshell_avd_lock_file_for_serial "$1" emulator-5554' bash "$wt")"
  avd_b="$(bash -c 'source "$1/scripts/lib/avd-lock.sh"; pocketshell_avd_lock_file_for_serial "$1" emulator-5556' bash "$wt")"
  output_lock="$(resolve_lock_file "$wt" "")" || { fail "$output_lock"; return 1; }

  if [[ -z "$avd_a" || -z "$avd_b" ]]; then
    fail "the AVD lock resolver produced nothing, so this check would pass vacuously"
    return 1
  fi
  if [[ "$avd_a" == "$avd_b" ]]; then
    fail "distinct emulator serials collapsed onto one AVD lock; the output-tree lock must not have changed the emulator lock's per-serial concurrency"
    return 1
  fi
  if [[ "$output_lock" == "$avd_a" || "$output_lock" == "$avd_b" ]]; then
    fail "the output-tree lock reuses an AVD lock file ($output_lock); they protect different resources and merging them would serialise every emulator lane behind every build"
    return 1
  fi
  # Check 1 already proved the behavioural half: those two lanes claimed
  # DIFFERENT serials (so the AVD lock did not serialise them) and still had a
  # peak of exactly one live builder.
  pass "the per-AVD lock and the output-tree lock stay distinct resources"
}

# --------------------------------------------------------------------------
# 10. Drift guard. Both canonical wrappers must take the lock BEFORE they run
#     gradle. A future edit that drops either call restores the bug silently,
#     and every behavioural check above would keep passing for the wrapper that
#     still locks.
# --------------------------------------------------------------------------
both_canonical_wrappers_take_the_lock_before_gradle() {
  local connected="$ROOT_DIR/scripts/connected-test.sh"
  local gate="$ROOT_DIR/scripts/full-jvm-gate.sh"

  # Comments ABOUT the call are not the call, at either end.
  local acquire_line first_gradle_line
  acquire_line="$(grep -n 'pocketshell_acquire_gradle_output_lock' "$connected" \
    | grep -v ':[[:space:]]*#' | head -n1 | cut -d: -f1)"
  first_gradle_line="$(grep -n '\./gradlew' "$connected" \
    | grep -v ':[[:space:]]*#' | head -n1 | cut -d: -f1)"
  if [[ -z "$acquire_line" ]]; then
    fail "scripts/connected-test.sh no longer acquires the gradle output lock (issue #2007); a connected build would again be free to race the full JVM gate in one worktree"
    return 1
  fi
  if [[ -z "$first_gradle_line" ]]; then
    fail "could not find a ./gradlew invocation in scripts/connected-test.sh, so this drift guard would pass vacuously"
    return 1
  fi
  if (( acquire_line > first_gradle_line )); then
    fail "scripts/connected-test.sh acquires the output lock (line $acquire_line) AFTER its first ./gradlew invocation (line $first_gradle_line); the lock must be held before any task runs"
    return 1
  fi

  local gate_acquire_line gate_exec_line
  gate_acquire_line="$(grep -n '^acquire_gradle_output_lock(' "$gate" | head -n1 | cut -d: -f1)"
  gate_exec_line="$(grep -n '^os.execve(' "$gate" | head -n1 | cut -d: -f1)"
  if [[ -z "$gate_acquire_line" ]]; then
    fail "scripts/full-jvm-gate.sh no longer acquires the gradle output lock before exec (issue #2007)"
    return 1
  fi
  if [[ -z "$gate_exec_line" ]]; then
    fail "could not find the gate's os.execve call, so this drift guard would pass vacuously"
    return 1
  fi
  if (( gate_acquire_line > gate_exec_line )); then
    fail "scripts/full-jvm-gate.sh acquires the output lock (line $gate_acquire_line) after its exec (line $gate_exec_line)"
    return 1
  fi
  pass "both canonical wrappers acquire the output lock before they run gradle"
}

# --------------------------------------------------------------------------

main() {
  # Deliberately NOT `local`: the EXIT trap fires after main's frame is gone.
  TMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-gradle-output-lock.XXXXXX")"
  trap 'rm -rf "$TMP_ROOT"' EXIT
  local tmp="$TMP_ROOT"

  STUB_BIN="$tmp/stubbin"
  AVD_LOCK_DIR="$tmp/avd-locks"
  OUTPUT_LOCK_DIR="$tmp/output-locks"
  install_stub_bin "$STUB_BIN"
  mkdir -p "$AVD_LOCK_DIR" "$OUTPUT_LOCK_DIR"

  MARKER_WAIT_TICKS="$(awk -v s="$MARKER_WAIT_SECONDS" 'BEGIN { printf "%d", s * 20 }')"
  CONSUME_TICKS="$(awk -v s="$CONSUME_SECONDS" 'BEGIN { printf "%d", s * 20 }')"
  export POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$OUTPUT_LOCK_DIR"

  printf 'gradle output-tree lock harness (issue #2007)\n'
  concurrent_wrapper_runs_do_not_corrupt_one_output_tree "$tmp" || true
  a_connected_lane_and_a_full_gate_style_lane_serialize "$tmp" || true
  distinct_worktrees_still_build_concurrently "$tmp" || true
  pool_lanes_in_one_worktree_keep_their_concurrency "$tmp" || true
  lock_path_is_machine_anchored_and_per_output_tree "$tmp" || true
  the_gate_and_the_wrappers_resolve_one_lock_file "$tmp" || true
  a_blocked_gate_reports_the_owner_and_never_starts "$tmp" || true
  a_blocked_connected_run_reports_the_owner_and_never_starts "$tmp" || true
  a_nested_wrapper_reuses_its_ancestors_ownership "$tmp" || true
  a_crashed_holder_does_not_wedge_the_lock "$tmp" || true
  a_sigkilled_wrapper_leaves_no_wedged_output_lock "$tmp" || true
  the_contention_diagnostic_names_the_live_holder "$tmp" || true
  a_queued_lane_with_a_preset_serial_holds_no_emulator_lock "$tmp" || true
  a_queued_lane_that_allocates_its_own_serial_holds_none "$tmp" || true
  the_avd_lock_stays_a_separate_concern "$tmp" || true
  both_canonical_wrappers_take_the_lock_before_gradle || true

  if (( FAILURES > 0 )); then
    printf '\ngradle output-tree lock: %s FAILING check(s)\n' "$FAILURES" >&2
    exit 1
  fi
  printf '\ngradle output-tree lock: all checks passed\n'
}

main "$@"
