#!/usr/bin/env bash

# ---------------------------------------------------------------------------
# Gradle output-tree lock (issue #2007)
#
# THE DEFECT this exists to stop coming back. `scripts/connected-test.sh` and
# `scripts/full-jvm-gate.py` are both canonical wrappers around `./gradlew`, and
# both write the SAME `app/build/...` output graph of the checkout they are run
# from. Nothing stopped them overlapping. During #893 validation in
# `.worktrees/issue-893-recurrence` a connected `--rerun-tasks` build and the
# full JVM gate ran at once and the gate died after 1m41s, before a single test:
#
#     :app:hiltAggregateDepsDebug
#     could not find generated app/build/.../processDebugResources/R.jar
#     multiple Kotlin daemon sessions warning
#
# `--rerun-tasks` deletes and regenerates outputs the sibling build is mid-way
# through consuming, so one of the two dies with a missing intermediate. Gradle
# does not defend against this: its own locks cover the caches, not the build
# output tree, so two builds in one project directory really do run at once.
#
# Why that is worse than "just a flake": the failure LOOKS like a product/test
# defect (a missing generated artifact, a Hilt task blowing up) and it lands in
# whichever lane happened to lose the race, not in the lane that caused it. The
# #893 gate had to be discarded as evidence. This is the same shape as the
# #1842 fixture collision — contention manufacturing evidence for a bug that is
# not there — and process.md's "vacuous green" catalogue is full of its cousins.
#
# THE ANCHOR. The resource being protected is ONE Gradle output tree, so the key
# is the *resolved output root path*, and the lock file lives in a machine-wide
# per-user directory:
#
#   * `$root_dir/build/...` (the #1657 / #1842 defect): a lock inside the very
#     tree it protects is fine for one checkout but breaks the moment anything
#     resolves the path differently (a symlinked worktree, a bind mount), and it
#     puts lock state inside the directory `--rerun-tasks` is rewriting. No.
#   * `$HOME/.cache/pocketshell/gradle-output-locks` (chosen), with `$HOME` taken
#     from the PASSWD entry, not the environment. A caller with an unusual
#     exported HOME must not silently get a different lock directory than its
#     sibling — that is exactly the split-lock bug #1657 fixed for the AVD half,
#     and the Python half of this lock (scripts/full-jvm-gate.py) reads the
#     passwd entry because it deliberately distrusts the inherited environment.
#     Both halves must agree byte-for-byte or the lock protects nothing.
#
# The KEY deliberately keeps distinct output trees independent:
#
#   * two DIFFERENT checkouts/worktrees write different `app/build` trees, so
#     they get different locks and still run concurrently. Serialising them
#     would queue every parallel agent behind every other — far worse than the
#     bug (the #1842 "distinct ports must stay distinct locks" lesson).
#   * a `connected-test.sh --pool` lane relocates every project's build dir to
#     `build/lane-<suffix>` (issue #724). That IS a disjoint output tree, so it
#     passes its lane token here and keeps its concurrency.
#
# Stale locks are impossible by construction: `flock` lives on an open file
# description, so the kernel drops it the instant the holder dies (SIGKILL, OOM,
# harness timeout). The lock FILE persisting is harmless. The owner line written
# INTO the file is advisory diagnostics only — never lock state. Do not replace
# this with a pidfile scheme, which WOULD wedge on a crash.
#
# `POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR` relocates the directory (tests sandbox it;
# an operator can point it elsewhere). It cannot disable the lock, and there is
# deliberately no "skip the lock" switch.
# `POCKETSHELL_GRADLE_OUTPUT_LOCK_WAIT_SECONDS` bounds the wait (default 7200).
#
# Usage as a library:
#   source scripts/lib/gradle-output-lock.sh
#   pocketshell_acquire_gradle_output_lock "$ROOT_DIR" "$lane" "$label"
#   ... run gradle ...
#   pocketshell_release_gradle_output_lock      # or via pocketshell_release_all
#
# Usage as a command (what an out-of-tree caller and the harness use):
#   scripts/lib/gradle-output-lock.sh --output-root R [--lane L] [--label X] \
#       -- <command...>
#   scripts/lib/gradle-output-lock.sh --output-root R --print-lock-file
# ---------------------------------------------------------------------------

# Exit code used when the bounded wait expires. Distinct from a build failure so
# a queued lane that never started is never mistaken for a red build.
POCKETSHELL_GRADLE_OUTPUT_LOCK_TIMEOUT_RC=75

pocketshell_gradle_output_lock_dir() {
  if [[ -n "${POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR:-}" ]]; then
    printf '%s\n' "${POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR%/}"
    return 0
  fi
  # The passwd home, NOT $HOME: the Python half of this lock distrusts the
  # inherited environment and uses the passwd entry, and the two halves must
  # resolve the identical path or neither excludes the other.
  local passwd_home=""
  passwd_home="$(getent passwd "$(id -u)" 2>/dev/null | awk -F: 'NR==1 {print $6}')"
  if [[ -n "$passwd_home" && -d "$passwd_home" && -w "$passwd_home" ]]; then
    printf '%s/.cache/pocketshell/gradle-output-locks\n' "$passwd_home"
    return 0
  fi
  # Home-less / read-only-home shell: still machine-wide, still per-user.
  printf '/tmp/pocketshell-gradle-output-locks-%s\n' "$(id -u)"
}

# Resolve the output root exactly the way the Python half does, so the two
# wrappers cannot disagree about which tree they are about to write.
pocketshell_gradle_output_lock_resolved_root() {
  local output_root="$1"
  local resolved
  if ! resolved="$(cd -- "$output_root" 2>/dev/null && pwd -P)"; then
    printf 'FAIL: gradle output root does not exist: %s\n' "$output_root" >&2
    return 1
  fi
  printf '%s\n' "$resolved"
}

# Lock path for one (output root, lane) pair. The digest keeps the filename
# bounded and path-safe; the human-readable key is written INTO the file by the
# holder. Must stay byte-identical to gradle_output_lock_file() in
# scripts/full-jvm-gate.py — scripts/test-gradle-output-lock.sh pins that.
pocketshell_gradle_output_lock_file() {
  local output_root="$1"
  local lane="${2:-}"
  local resolved digest dir
  resolved="$(pocketshell_gradle_output_lock_resolved_root "$output_root")" || return 1
  digest="$(printf '%s\n%s\n' "$resolved" "$lane" | sha256sum | cut -c1-32)"
  dir="$(pocketshell_gradle_output_lock_dir)"
  mkdir -p "$dir" 2>/dev/null || true
  printf '%s/gradle-output-%s.lock\n' "$dir" "$digest"
}

# Read the advisory owner line without disturbing the lock. Opening for READ
# only (never `>`), because a truncating open would erase the live holder's
# diagnostics.
pocketshell_gradle_output_lock_owner() {
  local lock_file="$1"
  local owner=""
  if [[ -r "$lock_file" ]]; then
    owner="$(head -c 512 "$lock_file" 2>/dev/null | head -n1)"
  fi
  printf '%s\n' "${owner:-unknown (no owner line recorded yet)}"
}

# Probe whether the lock is currently free, WITHOUT truncating the owner line
# (`>` would erase a live holder's diagnostics) and without leaking
# connected-test.sh's continuous AVD flock descriptor into the probe subshell.
_pocketshell_gradle_output_lock_is_free() {
  local lock_file="$1"
  if [[ "${POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED:-}" == "1" \
        && "${POCKETSHELL_AVD_LOCK_FD:-}" =~ ^[0-9]+$ ]]; then
    ( flock -n 9 ) {POCKETSHELL_AVD_LOCK_FD}>&- 9>>"$lock_file"
  else
    ( flock -n 9 ) 9>>"$lock_file"
  fi
}

# Publishes this process's CURRENT parent pid (field 4 of /proc/self/stat) in
# POCKETSHELL_GRADLE_OUTPUT_LOCK_PPID. The kernel updates that field on
# reparent; bash's `$PPID` is fixed at shell startup and never does.
#
# It ASSIGNS rather than echoes, and parses with parameter expansion rather than
# `set --`, for one load-bearing reason each:
#   * a command substitution forks, and inside that fork `/proc/self` is the
#     SUBSHELL, whose parent is the holder itself -- so the reparent check would
#     compare a constant against itself and silently never fire. A guard that
#     cannot fire reads exactly like a working one (this is the G6 shape that
#     cost this issue a review round; I shipped that fork in the first draft).
#   * `set --` would clobber the caller's positional parameters.
# The comm field can contain spaces and parentheses, so everything through the
# last ") " is dropped before the state and ppid fields are read.
_pocketshell_gradle_output_lock_read_ppid() {
  local statline rest
  IFS= read -r statline < /proc/self/stat 2>/dev/null || return 1
  rest="${statline##*) }"
  rest="${rest#* }"
  POCKETSHELL_GRADLE_OUTPUT_LOCK_PPID="${rest%% *}"
  [[ "$POCKETSHELL_GRADLE_OUTPUT_LOCK_PPID" =~ ^[0-9]+$ ]] || return 1
}

# How often the holder checks whether the wrapper that created it is still
# alive. Small enough that a hard-killed wrapper frees the tree effectively
# immediately; a knob only so the regression harness can prove the mechanism,
# never to skip it.
POCKETSHELL_GRADLE_OUTPUT_LOCK_PARENT_POLL_SECONDS="${POCKETSHELL_GRADLE_OUTPUT_LOCK_PARENT_POLL_SECONDS:-1}"

# Background holder. Owning the flock in a dedicated process means no build
# child ever inherits the descriptor, so a lingering grandchild (a Kotlin
# daemon, a stray gradle worker, a booted emulator) can never keep the lock
# alive after the wrapper exits.
#
# It is started with connected-test.sh's continuous AVD flock descriptor CLOSED
# at process creation (the same shape as _pocketshell_hold_toxiproxy_lock). That
# wrapper must remain the SOLE continuous owner of the emulator lock (#1737): a
# long-lived descendant holding a copy would keep the emulator locked after the
# wrapper was SIGKILLed. `tests/scripts/connected-test-serial-ownership-test.sh`
# scans every wrapper descendant for exactly that leak.
#
# PARENT-DEATH EXIT (issue #2007 review round 2). A dedicated holder buys the
# no-inheritance property above at the cost of outliving its creator: on SIGKILL
# the wrapper's EXIT trap never runs, the holder is reparented to init (or the
# nearest subreaper) and keeps its flock forever, so every later canonical run
# in that worktree burns the full bounded wait and exits 75 without building.
# That kill is production-real -- `run_bounded` in
# scripts/ci-journey-budget-functions.sh escalates to `kill -KILL` on the
# --no-pool connected path, and this box's session harness hard-kills
# long-running background bash. So the holder watches for its creator's death
# and drops the lock, giving the output lock the SAME crash contract the AVD
# lock already has (#1737). Reparenting is the primary signal because it is
# immune to pid reuse; the `kill -0` check is a belt-and-braces second.
#
# KNOWN RESIDUAL WINDOW (review round 2, deliberately not closed here).
# connected-test.sh runs gradle under a `systemd-run --user --scope`, which can
# outlive a SIGKILLed wrapper. So between the holder noticing its creator died
# and that orphaned build child actually exiting, a leftover gradle process can
# still be writing a tree this lock no longer protects. That is strictly better
# than the alternative it replaces (a 7200s wedge in which NOTHING can build,
# and which never self-heals), and it is exactly the contract the sibling AVD
# lock has carried since #1737 -- so closing it is a separate change to the
# scope teardown, not a change to this lock. Closing it here would mean the
# holder killing a process group it did not create, which is the cross-agent
# damage process.md's contended-box section warns about. Left as a follow-up:
# tear the scope down on holder exit, or have the holder watch the build child.
_pocketshell_hold_gradle_output_lock() {
  local lock_file="$1"
  local ready_file="$2"
  local wait_seconds="$3"
  local owner_line="$4"
  local creator_pid="$5"
  exec >/dev/null 2>/dev/null
  # Append mode: opening with `>` would TRUNCATE a live holder's owner line.
  if ! exec 9>>"$lock_file"; then
    exit 1
  fi
  if ! flock -w "$wait_seconds" 9; then
    exit 3
  fi
  # Recorded by the holder itself so the advisory line names the process that
  # really owns the descriptor. A diagnostic that names only the wrapper points
  # a blocked operator at a process that may already be gone.
  printf 'holder_pid=%s wrapper_pid=%s %s\n' \
    "$BASHPID" "$creator_pid" "$owner_line" > "$lock_file"
  printf 'ready\n' > "$ready_file"
  local initial_ppid=""
  if _pocketshell_gradle_output_lock_read_ppid; then
    initial_ppid="$POCKETSHELL_GRADLE_OUTPUT_LOCK_PPID"
  fi
  local sleep_pid=""
  trap '[[ -n "$sleep_pid" ]] && kill "$sleep_pid" 2>/dev/null || true; exit 0' HUP INT TERM
  local poll="${POCKETSHELL_GRADLE_OUTPUT_LOCK_PARENT_POLL_SECONDS:-1}"
  while :; do
    sleep "$poll" 9>&- &
    sleep_pid="$!"
    wait "$sleep_pid" || true
    # Two independent death signals, deliberately redundant. Reparenting is
    # immune to pid reuse; `kill -0` still fires when /proc is unavailable or
    # the stat parse failed. Either one alone frees the tree.
    if [[ -n "$initial_ppid" ]] && _pocketshell_gradle_output_lock_read_ppid \
       && [[ "$POCKETSHELL_GRADLE_OUTPUT_LOCK_PPID" != "$initial_ppid" ]]; then
      exit 0
    fi
    if ! kill -0 "$creator_pid" 2>/dev/null; then
      exit 0
    fi
  done
}

# pocketshell_acquire_gradle_output_lock <output-root> [lane] [label]
#
# Blocks (bounded, with a visible owner diagnostic) until this shell owns the
# output tree, then returns 0. Returns
# $POCKETSHELL_GRADLE_OUTPUT_LOCK_TIMEOUT_RC when the bounded wait expires, and
# 1 on a setup failure. Never starts the build on a tree someone else owns.
pocketshell_acquire_gradle_output_lock() {
  local output_root="$1"
  local lane="${2:-}"
  local label="${3:-unknown}"

  if [[ -n "${POCKETSHELL_GRADLE_OUTPUT_LOCK_OWNER_PID:-}" ]]; then
    return 0
  fi

  local lock_file resolved
  resolved="$(pocketshell_gradle_output_lock_resolved_root "$output_root")" || return 1
  lock_file="$(pocketshell_gradle_output_lock_file "$output_root" "$lane")" || return 1

  # Re-entrancy. An ancestor process that already owns this exact output tree
  # EXPORTS the lock file it holds, so a nested wrapper reuses that ownership
  # instead of queuing behind its own ancestor. Without this, wrapping any
  # script that later calls a canonical wrapper is an instant self-deadlock —
  # and it would present as a wedged lock, the least debuggable failure this
  # change could introduce. Ownership is NOT taken here, so the nested call also
  # does not release a lock it does not own.
  if [[ -n "${POCKETSHELL_GRADLE_OUTPUT_LOCK_HELD_FILE:-}" \
        && "${POCKETSHELL_GRADLE_OUTPUT_LOCK_HELD_FILE}" == "$lock_file" ]]; then
    printf 'Gradle output tree %s is already owned by an ancestor of this process (%s); reusing that ownership.\n' \
      "$resolved${lane:+ (lane $lane)}" "$lock_file" >&2
    return 0
  fi

  local wait_seconds="${POCKETSHELL_GRADLE_OUTPUT_LOCK_WAIT_SECONDS:-7200}"
  # The holder prefixes holder_pid= (itself) and wrapper_pid= (this shell). Both
  # matter to a blocked operator: the holder is what actually owns the flock,
  # the wrapper is what to stop -- stopping the holder alone would release the
  # tree while the build kept running, which is the corruption this lock exists
  # to prevent.
  local owner_line
  owner_line="label=${label} output_root=${resolved} lane=${lane:-<none>} started=$(date -Is 2>/dev/null || date)"

  # Tell the operator BEFORE queuing, and say who owns the tree. A silent
  # multi-minute block is indistinguishable from a wedge, which is how the
  # sibling AVD lock got misdiagnosed for months.
  local started_at="$SECONDS"
  if ! _pocketshell_gradle_output_lock_is_free "$lock_file"; then
    printf 'Gradle output tree %s is LOCKED by another canonical wrapper run; queuing up to %ss rather than corrupting its build.\n' \
      "$resolved${lane:+ (lane $lane)}" "$wait_seconds" >&2
    printf '  lock file: %s\n' "$lock_file" >&2
    printf '  held by:   %s\n' "$(pocketshell_gradle_output_lock_owner "$lock_file")" >&2
  fi

  local state_dir ready_file
  state_dir="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-gradle-output-lock.XXXXXX")"
  ready_file="$state_dir/ready"

  # $BASHPID, not $$: in a subshell the holder must watch the process that
  # actually owns this acquisition (and that release() keys on), not the outer
  # shell that would survive it.
  local creator_pid="$BASHPID"
  if [[ "${POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED:-}" == "1" \
        && "${POCKETSHELL_AVD_LOCK_FD:-}" =~ ^[0-9]+$ ]]; then
    _pocketshell_hold_gradle_output_lock \
      "$lock_file" "$ready_file" "$wait_seconds" "$owner_line" "$creator_pid" \
      {POCKETSHELL_AVD_LOCK_FD}>&- &
  else
    _pocketshell_hold_gradle_output_lock \
      "$lock_file" "$ready_file" "$wait_seconds" "$owner_line" "$creator_pid" &
  fi
  local holder_pid="$!"

  while [[ ! -e "$ready_file" ]]; do
    if ! kill -0 "$holder_pid" 2>/dev/null; then
      local holder_rc=0
      wait "$holder_pid" 2>/dev/null || holder_rc=$?
      rm -rf "$state_dir"
      if (( holder_rc == 3 )); then
        printf 'FAIL: gave up waiting %ss for the gradle output lock on %s.\n' \
          "$wait_seconds" "$resolved" >&2
        printf '  lock file: %s\n' "$lock_file" >&2
        printf '  held by:   %s\n' "$(pocketshell_gradle_output_lock_owner "$lock_file")" >&2
        printf '  This run did NOT start; nothing was built. Wait for the owner above to finish, or stop its wrapper_pid (NOT its holder_pid: stopping the holder alone would release the tree while that build kept writing it), then rerun.\n' >&2
        return "$POCKETSHELL_GRADLE_OUTPUT_LOCK_TIMEOUT_RC"
      fi
      printf 'FAIL: could not open the gradle output lock: %s\n' "$lock_file" >&2
      return 1
    fi
    sleep 0.05
  done

  rm -rf "$state_dir"
  POCKETSHELL_GRADLE_OUTPUT_LOCK_HOLDER_PID="$holder_pid"
  # $BASHPID, not $$: a subshell inherits $$ from its parent and would wrongly
  # pass an ownership check keyed on it (the #1842 release-side defect).
  POCKETSHELL_GRADLE_OUTPUT_LOCK_OWNER_PID="$BASHPID"
  # The lock path is published ONLY as the exported HELD_FILE below. A second,
  # non-exported copy of it was dead state (assigned and unset, never read) and
  # is worse than noise here: an unused variable that looks like lock state
  # invites a future reader to treat it as one. Hard cut, D22.
  #
  # Exported (unlike the ownership markers above) purely so a DESCENDANT wrapper
  # can see that this tree is already owned by its own process tree.
  export POCKETSHELL_GRADLE_OUTPUT_LOCK_HELD_FILE="$lock_file"
  printf 'Acquired gradle output lock: %s (%s%s, waited %ss)\n' \
    "$lock_file" "$resolved" "${lane:+, lane $lane}" "$((SECONDS - started_at))" >&2

  # Only install an EXIT trap when the caller has none: connected-test.sh
  # installs a richer one (evidence capture + permission restore) that already
  # routes through pocketshell_release_all, and clobbering it would drop that.
  if [[ -z "$(trap -p EXIT)" ]]; then
    if declare -F pocketshell_release_all >/dev/null 2>&1; then
      trap pocketshell_release_all EXIT
    else
      trap pocketshell_release_gradle_output_lock EXIT
    fi
  fi
}

pocketshell_release_gradle_output_lock() {
  if [[ "${POCKETSHELL_GRADLE_OUTPUT_LOCK_OWNER_PID:-}" != "$BASHPID" ]]; then
    return 0
  fi
  local holder_pid="${POCKETSHELL_GRADLE_OUTPUT_LOCK_HOLDER_PID:-}"
  if [[ -n "$holder_pid" ]]; then
    kill "$holder_pid" 2>/dev/null || true
    wait "$holder_pid" 2>/dev/null || true
  fi
  unset POCKETSHELL_GRADLE_OUTPUT_LOCK_HOLDER_PID
  unset POCKETSHELL_GRADLE_OUTPUT_LOCK_OWNER_PID
  unset POCKETSHELL_GRADLE_OUTPUT_LOCK_HELD_FILE
}

# ---------------------------------------------------------------------------
# Command mode
# ---------------------------------------------------------------------------
_pocketshell_gradle_output_lock_usage() {
  cat >&2 <<'USAGE'
Gradle output-tree lock (issue #2007).

Usage:
  scripts/lib/gradle-output-lock.sh --output-root <dir> [--lane <token>] \
      [--label <text>] -- <command> [args...]
  scripts/lib/gradle-output-lock.sh --output-root <dir> [--lane <token>] \
      --print-lock-file

Runs <command> while holding the exclusive lock for that Gradle output tree, so
two canonical wrapper runs can never rewrite one app/build graph at the same
time. The loser queues (bounded, with a visible owner diagnostic) instead of
starting.
USAGE
}

_pocketshell_gradle_output_lock_main() {
  local output_root="" lane="" label="gradle-output-lock.sh" print_only=0
  local command=()
  while [[ $# -gt 0 ]]; do
    case "$1" in
      --help | -h)
        _pocketshell_gradle_output_lock_usage
        return 0
        ;;
      --output-root)
        [[ $# -ge 2 ]] || { printf 'FAIL: --output-root needs a value\n' >&2; return 2; }
        output_root="$2"
        shift 2
        ;;
      --output-root=*)
        output_root="${1#--output-root=}"
        shift
        ;;
      --lane)
        [[ $# -ge 2 ]] || { printf 'FAIL: --lane needs a value\n' >&2; return 2; }
        lane="$2"
        shift 2
        ;;
      --lane=*)
        lane="${1#--lane=}"
        shift
        ;;
      --label)
        [[ $# -ge 2 ]] || { printf 'FAIL: --label needs a value\n' >&2; return 2; }
        label="$2"
        shift 2
        ;;
      --label=*)
        label="${1#--label=}"
        shift
        ;;
      --print-lock-file)
        print_only=1
        shift
        ;;
      --)
        shift
        command+=("$@")
        break
        ;;
      *)
        printf 'FAIL: unexpected argument %s\n' "$1" >&2
        _pocketshell_gradle_output_lock_usage
        return 2
        ;;
    esac
  done

  if [[ -z "$output_root" ]]; then
    printf 'FAIL: --output-root is required\n' >&2
    return 2
  fi

  if (( print_only == 1 )); then
    pocketshell_gradle_output_lock_file "$output_root" "$lane" || return 1
    return 0
  fi

  if (( ${#command[@]} == 0 )); then
    printf 'FAIL: nothing to run; pass -- <command> (or --print-lock-file)\n' >&2
    return 2
  fi

  local acquire_rc=0
  pocketshell_acquire_gradle_output_lock "$output_root" "$lane" "$label" || acquire_rc=$?
  if (( acquire_rc != 0 )); then
    return "$acquire_rc"
  fi

  local child_pid rc=0
  "${command[@]}" &
  child_pid="$!"
  # shellcheck disable=SC2317  # invoked from the traps below
  _pocketshell_gradle_output_lock_forward() {
    kill -s "$1" "$child_pid" 2>/dev/null || true
  }
  trap '_pocketshell_gradle_output_lock_forward INT' INT
  trap '_pocketshell_gradle_output_lock_forward TERM' TERM
  wait "$child_pid" || rc=$?
  trap - INT TERM
  pocketshell_release_gradle_output_lock
  return "$rc"
}

if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  set -uo pipefail
  trap pocketshell_release_gradle_output_lock EXIT
  _pocketshell_gradle_output_lock_main "$@"
  exit $?
fi
