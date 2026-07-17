#!/usr/bin/env bash
set -euo pipefail

# AVD pool claim/release regression harness (issue #674).
#
# These tests exercise the REAL scripts/connected-test.sh --pool path and the
# pool claim/release helpers in scripts/lib/avd-lock.sh using a fake `adb` and a
# stub `./gradlew`, so NO real emulator is needed. They prove the load-bearing
# property of AC2: after a connected-test run finishes, every claimed pool
# serial is reclaimable again (its per-serial flock is free).
#
# The key regression is `reclaim_after_full_pool_run`: it simulates a COMPLETE
# (not killed) `connected-test.sh --pool` run. Before the exec->child fix the
# wrapper ended with `exec ./gradlew`, which replaced the shell so bash never
# fired its EXIT trap; the backgrounded per-serial flock holder was orphaned and
# kept the claim held forever. With a 3-emulator pool that stranded one serial
# per run. This test would have caught it: it asserts `claimable-after-run`
# equals the pool size, not size-1.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

# Build an isolated sandbox containing:
#   * a fake `adb` reporting POOL_SERIALS as online emulators
#   * a stub `./gradlew` that records its invocation and exits 0 (or with a
#     caller-chosen exit code) WITHOUT touching a real device
#   * a private ROOT clone plus a private POCKETSHELL_AVD_LOCK_DIR so this
#     test's lock files never touch the REAL machine-wide lock dir (which a
#     live emulator run may be holding). Since issue #1657 the lock is anchored
#     to the machine, not to $root_dir, so isolating the root is no longer
#     enough on its own -- the lock dir must be sandboxed explicitly.
# The sandbox is a directory; the caller passes it to the *_in_sandbox helpers.
make_sandbox() {
  local sandbox="$1"
  shift
  local pool_serials="$1"   # space-separated emulator-XXXX serials

  mkdir -p "$sandbox/bin" "$sandbox/root/scripts/lib" "$sandbox/root/build"

  # Issue #1657: point the machine-wide lock anchor at this sandbox so the test
  # cannot contend with (or corrupt) a real connected-test run on this box.
  export POCKETSHELL_AVD_LOCK_DIR="$sandbox/locks"

  # Fake adb: only the `devices` subcommand matters for pool claim. Report each
  # pool serial as a `device`-state emulator. Everything else is a harmless no-op.
  cat > "$sandbox/bin/adb" <<ADB
#!/usr/bin/env bash
case "\$1" in
  devices)
    printf 'List of devices attached\n'
    for s in $pool_serials; do
      printf '%s\tdevice\n' "\$s"
    done
    ;;
  start-server) : ;;
  *) : ;;
esac
exit 0
ADB
  chmod +x "$sandbox/bin/adb"

  # Copy the real scripts into the sandbox root so connected-test.sh runs the
  # ACTUAL production logic against sandboxed lock state.
  #
  # Copy the WHOLE scripts/lib: connected-test.sh sources agents-pool.sh (#724)
  # and scope-run.sh (#730), both of which it grew AFTER this harness was
  # written. The harness only ever copied avd-lock.sh, so every case died at
  # `source .../agents-pool.sh: No such file or directory` -- this suite has been
  # failing on `main` (it is wired to no lane, so nobody saw it). Globbing the
  # directory keeps it from rotting again the next time a lib is added.
  cp "$ROOT_DIR/scripts/connected-test.sh" "$sandbox/root/scripts/connected-test.sh"
  cp "$ROOT_DIR"/scripts/lib/*.sh "$sandbox/root/scripts/lib/"

  # Stub gradlew at the sandbox root: connected-test.sh invokes `./gradlew`
  # relative to ROOT_DIR (which it cd's into). Record args + a marker file so the
  # test can assert gradle actually ran, then exit with $STUB_GRADLEW_RC (default 0).
  cat > "$sandbox/root/gradlew" <<'GRADLEW'
#!/usr/bin/env bash
printf '%s\n' "$@" > "$STUB_GRADLEW_ARGS_FILE"
printf 'ran\n' > "$STUB_GRADLEW_MARKER"
exit "${STUB_GRADLEW_RC:-0}"
GRADLEW
  chmod +x "$sandbox/root/gradlew"
}

# Is a given serial's per-serial lock currently FREE (i.e. reclaimable)?
# Returns 0 if a non-blocking flock succeeds (free), 1 if held.
serial_lock_free() {
  local root_dir="$1"
  local serial="$2"
  local lock_file
  lock_file="$(
    source "$root_dir/scripts/lib/avd-lock.sh"
    pocketshell_avd_lock_file_for_serial "$root_dir" "$serial"
  )"
  # If the lock file doesn't exist yet, it was never claimed -> trivially free.
  [[ -e "$lock_file" ]] || return 0
  flock -n "$lock_file" true
}

count_reclaimable() {
  local root_dir="$1"
  shift
  local n=0 s
  for s in "$@"; do
    if serial_lock_free "$root_dir" "$s"; then
      n=$((n + 1))
    fi
  done
  printf '%s\n' "$n"
}

# REGRESSION: a complete connected-test.sh --pool run must leave the claimed
# serial reclaimable. With a 2-serial pool, two sequential runs should each
# claim+release cleanly, and at the end BOTH serials are reclaimable
# (claimable-after-run == pool size, not size-1).
reclaim_after_full_pool_run() {
  local sandbox="$1"
  local pool_serials="emulator-5556 emulator-5558"
  make_sandbox "$sandbox" "$pool_serials"
  local sroot="$sandbox/root"

  local run
  for run in 1 2; do
    local args_file="$sandbox/gradlew-args-$run.txt"
    local marker="$sandbox/gradlew-marker-$run.txt"
    # Run the REAL wrapper to completion. PATH puts the fake adb first; ADB/ANDROID_SDK
    # point at it too so the helper's default resolution also finds the fake.
    PATH="$sandbox/bin:$PATH" \
      ADB="$sandbox/bin/adb" \
      ANDROID_SDK="$sandbox" \
      POCKETSHELL_POOL_WAIT_SECONDS=5 \
      POCKETSHELL_POOL_SERIALS="$pool_serials" \
      POCKETSHELL_AGENTS_PORT=2222 \
      STUB_GRADLEW_ARGS_FILE="$args_file" \
      STUB_GRADLEW_MARKER="$marker" \
      STUB_GRADLEW_RC=0 \
      bash "$sroot/scripts/connected-test.sh" --pool \
      > "$sandbox/run-$run.out" 2> "$sandbox/run-$run.err" \
      || fail "connected-test.sh --pool run $run exited non-zero (see $sandbox/run-$run.err)"

    # Gradle must actually have run as a CHILD (the exec->child fix). If `exec`
    # were still used the trap couldn't fire; the marker also proves gradle ran.
    [[ -e "$marker" ]] || fail "stub gradlew did not run on pool run $run"
    grep -q ':app:connectedDebugAndroidTest' "$args_file" \
      || fail "gradle was not invoked with the connected test task on run $run"
  done

  # THE assertion: after two complete runs, ALL pool serials are reclaimable.
  # $pool_serials is a deliberately space-separated serial list -> word-split it.
  local claimable
  # shellcheck disable=SC2086
  claimable="$(count_reclaimable "$sroot" $pool_serials)"
  local expected
  # shellcheck disable=SC2086
  expected="$(printf '%s\n' $pool_serials | wc -l)"
  printf 'claimable-after-run=%s expected=%s\n' "$claimable" "$expected" >&2
  [[ "$claimable" == "$expected" ]] \
    || fail "pool serial leaked: claimable-after-run=$claimable expected=$expected (exec->child release regression)"
}

# A non-zero gradle exit must STILL release the claim (trap fires on failure too)
# and the wrapper must propagate gradle's exit code.
failed_run_still_releases_and_propagates_rc() {
  local sandbox="$1"
  local pool_serials="emulator-5560 emulator-5562"
  make_sandbox "$sandbox" "$pool_serials"
  local sroot="$sandbox/root"

  set +e
  PATH="$sandbox/bin:$PATH" \
    ADB="$sandbox/bin/adb" \
    ANDROID_SDK="$sandbox" \
    POCKETSHELL_POOL_WAIT_SECONDS=5 \
    POCKETSHELL_POOL_SERIALS="$pool_serials" \
    POCKETSHELL_AGENTS_PORT=2222 \
    STUB_GRADLEW_ARGS_FILE="$sandbox/args.txt" \
    STUB_GRADLEW_MARKER="$sandbox/marker.txt" \
    STUB_GRADLEW_RC=7 \
    bash "$sroot/scripts/connected-test.sh" --pool \
    > "$sandbox/run.out" 2> "$sandbox/run.err"
  local rc=$?
  set -e

  [[ "$rc" == "7" ]] || fail "wrapper did not propagate gradle exit code (got $rc, want 7)"

  local claimable
  # shellcheck disable=SC2086
  claimable="$(count_reclaimable "$sroot" $pool_serials)"
  local expected
  # shellcheck disable=SC2086
  expected="$(printf '%s\n' $pool_serials | wc -l)"
  [[ "$claimable" == "$expected" ]] \
    || fail "failed run leaked a pool serial: claimable=$claimable expected=$expected"
}

# Default non-pool path: a --suffix-only run (no --pool, no preset ANDROID_SERIAL)
# must acquire and RELEASE the single base AVD lock. This guards the #672
# deadlock fix interaction: after the run, build/.avd-lock is free again.
non_pool_suffix_run_acquires_and_releases_base_lock() {
  local sandbox="$1"
  make_sandbox "$sandbox" "emulator-5556"
  local sroot="$sandbox/root"

  PATH="$sandbox/bin:$PATH" \
    ADB="$sandbox/bin/adb" \
    ANDROID_SDK="$sandbox" \
    STUB_GRADLEW_ARGS_FILE="$sandbox/args.txt" \
    STUB_GRADLEW_MARKER="$sandbox/marker.txt" \
    STUB_GRADLEW_RC=0 \
    bash "$sroot/scripts/connected-test.sh" --suffix i674 \
    > "$sandbox/run.out" 2> "$sandbox/run.err" \
    || fail "non-pool --suffix run exited non-zero (see $sandbox/run.err)"

  [[ -e "$sandbox/marker.txt" ]] || fail "stub gradlew did not run on non-pool path"
  grep -q -- '-PpocketshellAppIdSuffix=i674' "$sandbox/args.txt" \
    || fail "suffix was not threaded into gradle on non-pool path"

  # The base lock must be free again after the run (trap released the holder).
  # Issue #1657: the base lock lives in the machine-wide lock dir (sandboxed
  # here via POCKETSHELL_AVD_LOCK_DIR), no longer under the checkout's build/.
  local base_lock="$POCKETSHELL_AVD_LOCK_DIR/avd-lock"
  [[ -e "$base_lock" ]] || fail "base AVD lock file was never created on non-pool path"
  flock -n "$base_lock" true \
    || fail "non-pool run leaked the base AVD lock holder (deadlock-fix regression)"
}

run_case() {
  local name="$1"
  local sandbox
  sandbox="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-avd-pool-test.XXXXXX")"
  "$name" "$sandbox"
  rm -rf "$sandbox"
  printf '  ok: %s\n' "$name"
}

run_case reclaim_after_full_pool_run
run_case failed_run_still_releases_and_propagates_rc
run_case non_pool_suffix_run_acquires_and_releases_base_lock

printf 'PASS: avd-pool claim/release\n'
