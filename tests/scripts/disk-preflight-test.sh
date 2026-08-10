#!/usr/bin/env bash
set -uo pipefail

# Free-disk preflight + safe-list cleanup regression harness (issue #1989).
#
# WHAT IT PROVES, and why in this shape.
#
# The reported defect is a dev-box root filesystem at 100% turning two canonical
# gates into infrastructure failures that LOOK like product failures. The
# reproduction therefore drives the REAL `scripts/connected-test.sh` and the REAL
# `scripts/full-jvm-gate.py` — not a stand-in — against a filesystem whose free
# space is below the bar, and asserts they refuse to start.
#
# The "below the bar" condition is created by lowering the BAR, not by filling
# the disk. Actually filling the box's root filesystem to reproduce a disk-full
# bug would take the box down, break four live agent lanes and a running
# emulator, and is exactly the cross-agent damage process.md warns about. The
# threshold is injectable precisely so this test is deterministic and harmless;
# a test that waited for the real disk to fall below 10 GiB would be a test that
# runs on Tuesdays.
#
# That injectability creates one obvious way to fake a pass — an implementation
# that only ever compares two numbers it was handed, and never looks at a
# filesystem at all, would satisfy every threshold case. `measurement_is_real`
# closes it by cross-checking both halves against `df` on a real path.
#
# The cleanup half is tested against a REAL git repository with REAL worktrees
# in a sandbox, because its load-bearing property is a refusal: the 2026-07-19
# incident destroyed an entire implementation held only as UNTRACKED files in an
# agent worktree, which `git diff` reports as clean. `untracked_only_worktree_is_refused`
# is that exact shape. Stage 3's scratch root and the docker binary are both
# redirected into the sandbox so no case can touch the shared /tmp or the real
# Docker daemon that sibling lanes are using.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Inherited preflight/lock state from a driving gate must not leak into the
# fixtures (the #1702 hermeticity lesson from the AVD harnesses).
unset POCKETSHELL_DISK_MIN_FREE_MB \
      POCKETSHELL_DISK_WARN_FREE_MB \
      POCKETSHELL_AVD_LOCK_ACQUIRED \
      POCKETSHELL_AVD_LOCK_FILE \
      POCKETSHELL_AVD_LOCK_HOLDER_PID \
      POCKETSHELL_AVD_LOCK_OWNER_PID \
      POCKETSHELL_GRADLE_OUTPUT_LOCK_HELD_FILE \
      POCKETSHELL_GRADLE_OUTPUT_LOCK_HOLDER_PID \
      POCKETSHELL_GRADLE_OUTPUT_LOCK_OWNER_PID \
      POCKETSHELL_POOL_HOLDER_PID \
      POCKETSHELL_POOL_OWNER_PID \
      POCKETSHELL_POOL_SERIAL \
      POCKETSHELL_TOXIPROXY_LOCK_HOLDER_PID \
      POCKETSHELL_TOXIPROXY_LOCK_OWNER_PID \
      ANDROID_SERIAL \
      CI

CASES=0
WORK_DIR="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-disk-preflight-test.XXXXXX")"
trap 'rm -rf "$WORK_DIR"' EXIT

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  exit 1
}

pass_case() {
  CASES=$((CASES + 1))
  printf '  ok: %s\n' "$1"
}

# A threshold so large no filesystem can satisfy it, used to put every fixture
# deterministically below the bar.
BELOW_FLOOR_MB=999999999999

# ---------------------------------------------------------------------------
# 1. The two halves of the preflight must agree.
#
# connected-test.sh resolves the preflight in Bash and full-jvm-gate.py (an
# isolated Python program that deliberately sources no shell library) resolves
# it in Python. Two canonical wrappers that disagree about "is there room"
# protect nothing — the same failure mode as the #2007 lock's two halves, which
# is why that lock is pinned the same way.
# ---------------------------------------------------------------------------
halves_agree_on_thresholds_and_rc() {
  local bash_min bash_warn python_min python_warn python_rc bash_rc

  bash_min="$("$ROOT_DIR/scripts/lib/disk-preflight.sh" --print-min-free-mb)" \
    || fail "bash half could not report its minimum threshold"
  bash_warn="$("$ROOT_DIR/scripts/lib/disk-preflight.sh" --print-warn-free-mb)" \
    || fail "bash half could not report its warn threshold"
  bash_rc="$(
    source "$ROOT_DIR/scripts/lib/disk-preflight.sh"
    printf '%s\n' "$POCKETSHELL_DISK_PREFLIGHT_FAIL_RC"
  )"

  python_min="$(sed -n 's/^DISK_PREFLIGHT_DEFAULT_MIN_FREE_MB = \([0-9]*\)$/\1/p' \
    "$ROOT_DIR/scripts/full-jvm-gate.py")"
  python_warn="$(sed -n 's/^DISK_PREFLIGHT_DEFAULT_WARN_FREE_MB = \([0-9]*\)$/\1/p' \
    "$ROOT_DIR/scripts/full-jvm-gate.py")"
  python_rc="$(sed -n 's/^DISK_PREFLIGHT_FAIL_RC = \([0-9]*\)$/\1/p' \
    "$ROOT_DIR/scripts/full-jvm-gate.py")"

  [[ -n "$python_min" && -n "$python_warn" && -n "$python_rc" ]] \
    || fail "could not read the python half's disk-preflight constants"
  [[ "$bash_min" == "$python_min" ]] \
    || fail "minimum free-space threshold differs: bash=$bash_min python=$python_min"
  [[ "$bash_warn" == "$python_warn" ]] \
    || fail "warn free-space threshold differs: bash=$bash_warn python=$python_warn"
  [[ "$bash_rc" == "$python_rc" ]] \
    || fail "disk-preflight failure rc differs: bash=$bash_rc python=$python_rc"
  [[ "$bash_min" -lt "$bash_warn" ]] \
    || fail "the warn line ($bash_warn) must sit above the hard floor ($bash_min)"
  pass_case "bash and python halves agree on thresholds ($bash_min/$bash_warn MiB) and rc ($bash_rc)"
}

# ---------------------------------------------------------------------------
# 1b. The DOCUMENTED floor is the floor in force, and it clears one run's own
#     working set.
#
# Round one of this issue shipped `--help` advertising 10240 MiB while
# `--print-min-free-mb` returned 4096: the operator is told a floor that is not
# in force, which is strictly worse than no documentation. Prose drifts silently
# because nothing executes it, so the three places that state the number —
# the library's usage text, docs/testing.md's behaviour table, and the constant
# itself — are pinned against each other here.
#
# The second assertion is the one with teeth. A floor BELOW one run's own output
# admits a run that then dies of the very ENOSPC it was checked for, and that
# rubber stamp is worse than no preflight because the failure now carries a
# "disk preflight OK" line above it. Measured on the dev box (2026-08-06) a
# completed gate leaves 2.7 GiB in app/build and 3.1 GiB across all module
# build/ dirs, before Gradle daemon temp, ~/.gradle/caches growth and (for a
# connected lane) Docker layers — so the floor must clear ~3.1 GiB with real
# margin, not merely be larger than zero.
# ---------------------------------------------------------------------------
MEASURED_RUN_WORKING_SET_MB=3174   # 3.1 GiB of build output, one completed gate

documented_floor_matches_the_floor_in_force() {
  local in_force help_default doc_floor_gib doc_warn_gib

  in_force="$("$ROOT_DIR/scripts/lib/disk-preflight.sh" --print-min-free-mb)" \
    || fail "could not read the floor in force"
  local warn_in_force
  warn_in_force="$("$ROOT_DIR/scripts/lib/disk-preflight.sh" --print-warn-free-mb)" \
    || fail "could not read the warn line in force"

  help_default="$("$ROOT_DIR/scripts/lib/disk-preflight.sh" --help 2>&1 \
    | sed -n 's/.*POCKETSHELL_DISK_MIN_FREE_MB (default \([0-9]*\)).*/\1/p')"
  [[ -n "$help_default" ]] || fail "--help no longer states the default floor"
  [[ "$help_default" == "$in_force" ]] \
    || fail "--help advertises a floor of $help_default MiB but $in_force MiB is in force"

  doc_floor_gib="$(sed -n 's/^| below \([0-9]*\) GiB | refuse to start.*/\1/p' \
    "$ROOT_DIR/docs/testing.md")"
  doc_warn_gib="$(sed -n 's/^| above \([0-9]*\) GiB | run silently.*/\1/p' \
    "$ROOT_DIR/docs/testing.md")"
  [[ -n "$doc_floor_gib" && -n "$doc_warn_gib" ]] \
    || fail "docs/testing.md no longer states the preflight behaviour table"
  (( doc_floor_gib * 1024 == in_force )) \
    || fail "docs/testing.md documents a ${doc_floor_gib} GiB floor but $in_force MiB is in force"
  (( doc_warn_gib * 1024 == warn_in_force )) \
    || fail "docs/testing.md documents a ${doc_warn_gib} GiB warn line but $warn_in_force MiB is in force"

  (( in_force >= MEASURED_RUN_WORKING_SET_MB * 2 )) \
    || fail "the floor ($in_force MiB) does not clear one run's own measured working set (${MEASURED_RUN_WORKING_SET_MB} MiB of build output) with margin: a run admitted at the floor can still ENOSPC after the preflight said OK"
  pass_case "the documented floor ($in_force MiB) is the floor in force and clears one run's working set"
}

# ---------------------------------------------------------------------------
# 2. The measurement is a real filesystem read, not arithmetic on the knob.
#
# Without this, an implementation that never calls statvfs would pass every
# other case in this file. Both halves are cross-checked against `df` on the
# same path; the tolerance absorbs live churn on a shared box (other lanes are
# writing while this runs) and df's block rounding, and is far tighter than any
# plausible constant an implementation could return instead.
# ---------------------------------------------------------------------------
measurement_is_real() {
  local df_mb bash_mb python_mb delta
  df_mb="$(df -P -k -- "$ROOT_DIR" | awk 'NR==2 {print int($4/1024)}')"
  [[ "$df_mb" =~ ^[0-9]+$ ]] || fail "could not read df available space for $ROOT_DIR"

  bash_mb="$("$ROOT_DIR/scripts/lib/disk-preflight.sh" --path "$ROOT_DIR" --print-free-mb)" \
    || fail "bash half could not measure free space"
  python_mb="$(python3 -c '
import os, sys
usage = os.statvfs(sys.argv[1])
print(usage.f_bavail * usage.f_frsize // 1048576)
' "$ROOT_DIR")"

  for measured in "$bash_mb" "$python_mb"; do
    [[ "$measured" =~ ^[0-9]+$ ]] || fail "non-numeric free-space measurement: $measured"
    delta=$(( measured > df_mb ? measured - df_mb : df_mb - measured ))
    (( delta <= 512 )) \
      || fail "free-space measurement $measured MiB disagrees with df ($df_mb MiB) by $delta MiB"
  done
  pass_case "both halves measure real free space (df=$df_mb bash=$bash_mb python=$python_mb MiB)"
}

# ---------------------------------------------------------------------------
# 3. A malformed threshold fails CLOSED in both halves.
#
# Silently falling back to the default on an unparseable value is how a guard
# becomes decoration: the operator believes a floor is in force and no output
# ever contradicts them.
# ---------------------------------------------------------------------------
malformed_threshold_fails_closed() {
  local rc=0 output
  output="$(POCKETSHELL_DISK_MIN_FREE_MB="10 GiB" \
    "$ROOT_DIR/scripts/lib/disk-preflight.sh" --path "$ROOT_DIR" 2>&1)" || rc=$?
  (( rc == 76 )) || fail "bash half accepted a malformed threshold (rc=$rc)"
  [[ "$output" == *"must be a whole number of MiB"* ]] \
    || fail "bash half did not name the malformed threshold: $output"

  rc=0
  output="$(POCKETSHELL_DISK_MIN_FREE_MB="10 GiB" \
    "$ROOT_DIR/scripts/full-jvm-gate.py" --disk-preflight-probe 2>&1)" || rc=$?
  (( rc == 76 )) || fail "python half accepted a malformed threshold (rc=$rc)"
  [[ "$output" == *"must be a whole number of MiB"* ]] \
    || fail "python half did not name the malformed threshold: $output"
  pass_case "a malformed threshold fails closed in both halves"
}

# ---------------------------------------------------------------------------
# 4. full-jvm-gate.py refuses below the floor and proceeds above it.
# ---------------------------------------------------------------------------
full_jvm_gate_probe_refuses_and_allows() {
  local rc=0 output
  output="$(POCKETSHELL_DISK_MIN_FREE_MB="$BELOW_FLOOR_MB" \
    "$ROOT_DIR/scripts/full-jvm-gate.py" --disk-preflight-probe 2>&1)" || rc=$?
  (( rc == 76 )) || fail "full-jvm-gate.py did not refuse below the floor (rc=$rc): $output"
  [[ "$output" == *"=== DISK PREFLIGHT FAILED (issue #1989) ==="* ]] \
    || fail "full-jvm-gate.py refusal is missing the operator-facing banner: $output"
  [[ "$output" == *"scripts/disk-cleanup.sh"* ]] \
    || fail "full-jvm-gate.py refusal does not name the cleanup path: $output"
  [[ "$output" == *"required:  $BELOW_FLOOR_MB MiB"* ]] \
    || fail "full-jvm-gate.py refusal does not report the required space: $output"

  rc=0
  POCKETSHELL_DISK_MIN_FREE_MB=0 \
    "$ROOT_DIR/scripts/full-jvm-gate.py" --disk-preflight-probe >/dev/null 2>&1 || rc=$?
  (( rc == 0 )) || fail "full-jvm-gate.py refused above the floor (rc=$rc)"
  pass_case "full-jvm-gate.py refuses below the floor with a full report, and proceeds above it"
}

# ---------------------------------------------------------------------------
# 5. The advisory warn line (issue bullet 3: alert before root reaches 100%).
# ---------------------------------------------------------------------------
warn_line_fires_before_the_floor() {
  local rc=0 output
  output="$(POCKETSHELL_DISK_MIN_FREE_MB=0 POCKETSHELL_DISK_WARN_FREE_MB="$BELOW_FLOOR_MB" \
    "$ROOT_DIR/scripts/lib/disk-preflight.sh" --path "$ROOT_DIR" 2>&1)" || rc=$?
  (( rc == 0 )) || fail "the warn line must not block the run (rc=$rc)"
  [[ "$output" == *"WARN: disk preflight (issue #1989)"* ]] \
    || fail "bash half did not warn below the comfort line: $output"
  [[ "$output" == *"scripts/disk-cleanup.sh"* ]] \
    || fail "the warn line does not name the cleanup path: $output"

  rc=0
  output="$(POCKETSHELL_DISK_MIN_FREE_MB=0 POCKETSHELL_DISK_WARN_FREE_MB="$BELOW_FLOOR_MB" \
    "$ROOT_DIR/scripts/full-jvm-gate.py" --disk-preflight-probe 2>&1)" || rc=$?
  (( rc == 0 )) || fail "the python warn line must not block the run (rc=$rc)"
  [[ "$output" == *"WARN: disk preflight (issue #1989)"* ]] \
    || fail "python half did not warn below the comfort line: $output"

  rc=0
  output="$(POCKETSHELL_DISK_MIN_FREE_MB=0 POCKETSHELL_DISK_WARN_FREE_MB=0 \
    "$ROOT_DIR/scripts/lib/disk-preflight.sh" --path "$ROOT_DIR" 2>&1)" || rc=$?
  (( rc == 0 )) || fail "a healthy filesystem must pass silently (rc=$rc)"
  [[ "$output" != *"WARN: disk preflight"* ]] \
    || fail "the warn line fired above the comfort threshold: $output"
  pass_case "the warn line fires only between the floor and the comfort threshold"
}

# ---------------------------------------------------------------------------
# 6. connected-test.sh — the reproduction.
#
# Sandbox shape borrowed from tests/scripts/avd-pool-test.sh: fake `adb`, stub
# `./gradlew`, private lock directories. The lock dirs matter here beyond
# hygiene: the load-bearing assertion is that a refused run took NO lock, which
# is only observable if the lock files this run would have created are somewhere
# no other lane could have created them first.
# ---------------------------------------------------------------------------
make_connected_sandbox() {
  local sandbox="$1"
  mkdir -p "$sandbox/bin" "$sandbox/root/scripts/lib" "$sandbox/root/build"
  export POCKETSHELL_AVD_LOCK_DIR="$sandbox/avd-locks"
  export POCKETSHELL_GRADLE_OUTPUT_LOCK_DIR="$sandbox/gradle-output-locks"

  # This harness unsets CI (full-jvm-gate.py rejects any run with CI set outside
  # its guard-only modes), and scripts/lib/scope-run.sh keys its hosted-runner
  # fallback off exactly that variable: with no reachable `systemd --user`
  # manager AND no CI, pocketshell_scope_run fails closed with rc 125 rather
  # than running the command bare. A hosted runner is precisely that host —
  # scope_available()'s own comment calls an unreachable user manager "common in
  # CI containers" — so without this export the fixtures' verdict would depend
  # on whether the HOST happens to have a user manager: green on the dev box
  # (`is-system-running` -> degraded), red on the required `Unit tests` check.
  # Opting in explicitly is correct here rather than a workaround: the fixture's
  # entire "build" is a stub gradlew that touches a marker file, so there is
  # nothing to memory-cap, and the property under test is the disk preflight,
  # not the cgroup wrapper. scope_bare_fallback_is_available_without_ci pins it.
  export POCKETSHELL_SCOPE_ALLOW_BARE=1

  cat > "$sandbox/bin/adb" <<'ADB'
#!/usr/bin/env bash
case "$1" in
  devices)
    printf 'List of devices attached\n'
    printf 'emulator-5554\tdevice\n'
    ;;
  *) : ;;
esac
exit 0
ADB
  chmod +x "$sandbox/bin/adb"

  cp "$ROOT_DIR/scripts/connected-test.sh" "$sandbox/root/scripts/connected-test.sh"
  cp "$ROOT_DIR"/scripts/lib/*.sh "$sandbox/root/scripts/lib/"
  chmod +x "$sandbox/root/scripts/connected-test.sh"

  cat > "$sandbox/root/gradlew" <<'GRADLEW'
#!/usr/bin/env bash
printf 'ran\n' > "$STUB_GRADLEW_MARKER"
exit 0
GRADLEW
  chmod +x "$sandbox/root/gradlew"
}

connected_test_refuses_below_the_floor_before_any_lock() {
  local sandbox="$WORK_DIR/connected-refuse"
  make_connected_sandbox "$sandbox"
  local marker="$sandbox/gradlew-ran"
  local rc=0 output

  output="$(
    PATH="$sandbox/bin:$PATH" \
    ADB="$sandbox/bin/adb" \
    STUB_GRADLEW_MARKER="$marker" \
    POCKETSHELL_DISK_MIN_FREE_MB="$BELOW_FLOOR_MB" \
    "$sandbox/root/scripts/connected-test.sh" --suffix i1989 2>&1
  )" || rc=$?

  (( rc == 76 )) || fail "connected-test.sh did not refuse below the floor (rc=$rc): $output"
  [[ "$output" == *"=== DISK PREFLIGHT FAILED (issue #1989) ==="* ]] \
    || fail "connected-test.sh refusal is missing the operator-facing banner: $output"
  [[ "$output" == *"scripts/disk-cleanup.sh"* ]] \
    || fail "connected-test.sh refusal does not name the cleanup path: $output"
  [[ ! -e "$marker" ]] || fail "connected-test.sh invoked gradle despite refusing"

  # The refusal must come BEFORE any shared resource is claimed. A lane that
  # cannot possibly succeed must not first sit on the Gradle output tree or the
  # one online emulator while it discovers that. If either lock directory has a
  # lock file, the wrapper got further than it should have.
  local stray
  stray="$(find "$sandbox/gradle-output-locks" "$sandbox/avd-locks" -type f 2>/dev/null | head -5)"
  [[ -z "$stray" ]] \
    || fail "connected-test.sh claimed a lock before failing the disk preflight: $stray"
  pass_case "connected-test.sh refuses below the floor before claiming any lock"
}

connected_test_proceeds_above_the_floor() {
  local sandbox="$WORK_DIR/connected-allow"
  make_connected_sandbox "$sandbox"
  local marker="$sandbox/gradlew-ran"
  local rc=0 output

  output="$(
    PATH="$sandbox/bin:$PATH" \
    ADB="$sandbox/bin/adb" \
    STUB_GRADLEW_MARKER="$marker" \
    POCKETSHELL_DISK_MIN_FREE_MB=0 \
    POCKETSHELL_DISK_WARN_FREE_MB=0 \
    "$sandbox/root/scripts/connected-test.sh" --suffix i1989 2>&1
  )" || rc=$?

  (( rc == 0 )) || fail "connected-test.sh failed above the floor (rc=$rc): $output"
  [[ -e "$marker" ]] || fail "connected-test.sh never reached gradle above the floor: $output"
  pass_case "connected-test.sh still runs normally above the floor"
}

connected_test_cleanup_mode_is_exempt() {
  # Gating the recovery path on the condition it recovers from is a self-lockout:
  # --cleanup-suffixes builds nothing, needs no space, and is part of how an
  # operator unwedges a contended box.
  local sandbox="$WORK_DIR/connected-cleanup"
  make_connected_sandbox "$sandbox"
  local rc=0 output

  output="$(
    PATH="$sandbox/bin:$PATH" \
    ADB="$sandbox/bin/adb" \
    STUB_GRADLEW_MARKER="$sandbox/gradlew-ran" \
    POCKETSHELL_DISK_MIN_FREE_MB="$BELOW_FLOOR_MB" \
    "$sandbox/root/scripts/connected-test.sh" --cleanup-suffixes 2>&1
  )" || rc=$?

  (( rc != 76 )) \
    || fail "--cleanup-suffixes was blocked by the disk preflight: $output"
  pass_case "--cleanup-suffixes is exempt from the disk preflight"
}

# ---------------------------------------------------------------------------
# 6b. The fixtures must survive a HOSTED-RUNNER host, not just this dev box.
#
# This harness runs in the required `Unit tests` check, where CI=true — but it
# must unset CI, because full-jvm-gate.py rejects any run with CI set outside
# its guard-only modes. scripts/lib/scope-run.sh keys its bare-execution
# fallback off exactly that variable, so unsetting CI on a host with no
# reachable `systemd --user` manager makes pocketshell_scope_run fail closed
# with rc 125 and connected-test.sh never reaches gradle. That is the hosted
# runner (scope_available()'s comment: an unreachable user manager is "common
# in CI containers"); the dev box, whose manager answers `degraded`, never
# enters it. A fixture whose verdict depends on the host is a fixture CI is
# green on with zero protection.
#
# So simulate the hosted host rather than trusting this one: a stub `systemctl`
# whose `--user is-system-running` fails, exactly as an unreachable bus does.
# Both directions are asserted, because a stub that failed to force the
# unavailable branch would make the positive case pass for the wrong reason —
# the same "a guard that cannot fire reads like a working one" problem the
# docker-image survival case is driven for.
# ---------------------------------------------------------------------------
connected_test_survives_a_host_without_user_systemd() {
  local sandbox="$WORK_DIR/connected-hosted"
  make_connected_sandbox "$sandbox"
  mkdir -p "$sandbox/hostedbin"

  # An unreachable user bus: `systemctl --user is-system-running` prints nothing
  # useful and exits non-zero.
  cat > "$sandbox/hostedbin/systemctl" <<'SYSTEMCTL'
#!/usr/bin/env bash
if [[ "$1" == "--user" && "$2" == "is-system-running" ]]; then
  printf 'Failed to connect to bus: No such file or directory\n' >&2
  exit 1
fi
exit 0
SYSTEMCTL
  # Nothing should reach systemd-run once the manager is unreachable; if
  # something does, fail loudly instead of silently starting a real scope.
  cat > "$sandbox/hostedbin/systemd-run" <<'SYSTEMDRUN'
#!/usr/bin/env bash
printf 'systemd-run invoked despite an unreachable user manager\n' >&2
exit 1
SYSTEMDRUN
  chmod +x "$sandbox/hostedbin/systemctl" "$sandbox/hostedbin/systemd-run"

  local marker="$sandbox/gradlew-ran"
  local rc=0 output
  output="$(
    PATH="$sandbox/hostedbin:$sandbox/bin:$PATH" \
    ADB="$sandbox/bin/adb" \
    STUB_GRADLEW_MARKER="$marker" \
    POCKETSHELL_DISK_MIN_FREE_MB=0 \
    POCKETSHELL_DISK_WARN_FREE_MB=0 \
    "$sandbox/root/scripts/connected-test.sh" --suffix i1989 2>&1
  )" || rc=$?

  (( rc == 0 )) \
    || fail "connected-test.sh failed on a host without user systemd (rc=$rc): $output"
  [[ -e "$marker" ]] \
    || fail "connected-test.sh never reached gradle on a host without user systemd: $output"
  [[ "$output" == *"BARE (explicit uncapped fallback)"* ]] \
    || fail "the run did not take scope-run's bare fallback, so the simulated host was not in force: $output"

  # The stub really does force the unavailable branch: without the opt-in the
  # same host fails closed at rc 125, which is the failure this case pins.
  rm -f "$marker"
  rc=0
  output="$(
    env -u POCKETSHELL_SCOPE_ALLOW_BARE \
      PATH="$sandbox/hostedbin:$sandbox/bin:$PATH" \
      ADB="$sandbox/bin/adb" \
      STUB_GRADLEW_MARKER="$marker" \
      POCKETSHELL_DISK_MIN_FREE_MB=0 \
      POCKETSHELL_DISK_WARN_FREE_MB=0 \
      "$sandbox/root/scripts/connected-test.sh" --suffix i1989 2>&1
  )" || rc=$?
  (( rc == 125 )) \
    || fail "the simulated hosted host did not fail closed without the opt-in (rc=$rc), so the positive assertion above proves nothing: $output"
  [[ ! -e "$marker" ]] \
    || fail "gradle ran despite scope-run failing closed"
  pass_case "connected-test.sh still runs on a host with no reachable user systemd (the hosted-runner shape)"
}

# ---------------------------------------------------------------------------
# 7. scripts/disk-cleanup.sh — the safe-list reclaim path.
# ---------------------------------------------------------------------------
make_cleanup_sandbox() {
  local sandbox="$1"
  local main="$sandbox/main"
  mkdir -p "$main/scripts/lib" "$sandbox/bin" "$sandbox/tmp"

  cp "$ROOT_DIR/scripts/disk-cleanup.sh" "$main/scripts/disk-cleanup.sh"
  cp "$ROOT_DIR"/scripts/lib/*.sh "$main/scripts/lib/"
  chmod +x "$main/scripts/disk-cleanup.sh"

  # Fake docker: records its argv so a case can assert `-a` is NEVER passed, and
  # reports one tagged pocketshell-test image so the survival check has
  # something real to verify.
  cat > "$sandbox/bin/docker" <<DOCKER
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$sandbox/docker-calls.txt"
case "\$1 \$2" in
  "info "*|"info") exit 0 ;;
  "images "*|"images") printf 'pocketshell-test:agents\nother-project:latest\n'; exit 0 ;;
  "system df") printf 'TYPE TOTAL ACTIVE SIZE RECLAIMABLE\n'; exit 0 ;;
esac
exit 0
DOCKER
  chmod +x "$sandbox/bin/docker"

  git -C "$main" init -q -b main
  git -C "$main" config user.email test@example.com
  git -C "$main" config user.name test
  printf 'seed\n' > "$main/README.md"
  git -C "$main" add -A
  git -C "$main" commit -q -m seed
  git init -q --bare "$sandbox/origin.git"
  git -C "$main" remote add origin "$sandbox/origin.git"
  git -C "$main" push -q origin main
  git -C "$main" fetch -q origin

  mkdir -p "$main/.claude/worktrees"
  git -C "$main" worktree add -q --detach "$main/.claude/worktrees/agent-clean" HEAD
  git -C "$main" worktree add -q --detach "$main/.claude/worktrees/agent-untracked" HEAD
  git -C "$main" worktree add -q --detach "$main/.claude/worktrees/agent-ahead" HEAD
  git -C "$main" worktree add -q --detach "$main/.worktrees/issue-4242" HEAD

  # The #1487 shape: an entire implementation held ONLY as untracked files.
  # `git diff` reports this worktree as clean.
  printf 'the whole implementation\n' \
    > "$main/.claude/worktrees/agent-untracked/BrandNewRegressionTest.kt"

  # Committed but never pushed: still unrecoverable if the worktree is removed.
  printf 'work\n' > "$main/.claude/worktrees/agent-ahead/work.txt"
  git -C "$main/.claude/worktrees/agent-ahead" add -A
  git -C "$main/.claude/worktrees/agent-ahead" commit -q -m "unpushed work"

  # Protected sibling data that must survive every stage.
  mkdir -p "$main/build/test-results" "$main/build/pre-release-confidence-gate" \
           "$main/build/phone-walkthrough"
  printf 'the only artifact naming the failing assertion\n' \
    > "$main/build/test-results/TEST-Example.xml"
  printf 'scratch\n' > "$main/build/pre-release-confidence-gate/log.txt"
  printf 'scratch\n' > "$main/build/phone-walkthrough/log.txt"

  # Scratch that IS old enough to sweep, and scratch that is not.
  mkdir -p "$sandbox/tmp/pocketshell-old" "$sandbox/tmp/pocketshell-fresh" \
           "$sandbox/tmp/someone-elses-scratch"
  touch -d '30 days ago' "$sandbox/tmp/pocketshell-old"
  printf '%s\n' "$main"
}

run_cleanup() {
  local sandbox="$1"
  shift
  PATH="$sandbox/bin:$PATH" \
  POCKETSHELL_DISK_CLEANUP_LOCK_DIR="$sandbox/locks" \
    "$sandbox/main/scripts/disk-cleanup.sh" \
      --tmp-root "$sandbox/tmp" --tmp-age-days 1 "$@" 2>&1
}

cleanup_dry_run_deletes_nothing() {
  local sandbox="$WORK_DIR/cleanup-dry"
  mkdir -p "$sandbox"
  make_cleanup_sandbox "$sandbox" > /dev/null
  local rc=0 output
  output="$(run_cleanup "$sandbox" --worktrees)" || rc=$?
  (( rc == 0 )) || fail "dry run failed (rc=$rc): $output"
  [[ -d "$sandbox/main/build/pre-release-confidence-gate" ]] \
    || fail "dry run deleted gate scratch"
  [[ -d "$sandbox/tmp/pocketshell-old" ]] || fail "dry run deleted tmp scratch"
  [[ -d "$sandbox/main/.claude/worktrees/agent-clean" ]] \
    || fail "dry run removed a worktree"
  [[ "$output" == *"[DRY-RUN] would remove"* ]] \
    || fail "dry run did not report candidates: $output"
  [[ "$output" != *"[APPLY]"* ]] || fail "dry run reported an apply action: $output"
  if [[ -f "$sandbox/docker-calls.txt" ]]; then
    grep -qE 'prune' "$sandbox/docker-calls.txt" \
      && fail "dry run pruned docker"
  fi
  pass_case "dry run reports candidates and deletes nothing"
}

cleanup_apply_reclaims_only_the_safe_list() {
  local sandbox="$WORK_DIR/cleanup-apply"
  mkdir -p "$sandbox"
  make_cleanup_sandbox "$sandbox" > /dev/null
  local rc=0 output
  output="$(run_cleanup "$sandbox" --apply --worktrees)" || rc=$?
  (( rc == 0 )) || fail "apply run failed (rc=$rc): $output"

  # Reclaimed.
  [[ ! -e "$sandbox/main/build/pre-release-confidence-gate" ]] \
    || fail "apply did not remove build/pre-release-confidence-gate"
  [[ ! -e "$sandbox/main/build/phone-walkthrough" ]] \
    || fail "apply did not remove build/phone-walkthrough"
  [[ ! -e "$sandbox/tmp/pocketshell-old" ]] \
    || fail "apply did not remove aged pocketshell-* scratch"
  [[ ! -e "$sandbox/main/.claude/worktrees/agent-clean" ]] \
    || fail "apply did not remove the genuinely clean agent worktree"

  # Preserved. Each of these is a documented never-touch.
  [[ -f "$sandbox/main/build/test-results/TEST-Example.xml" ]] \
    || fail "apply removed build/test-results (the only artifact naming a failing assertion)"
  [[ -d "$sandbox/tmp/pocketshell-fresh" ]] \
    || fail "apply removed fresh scratch a live lane may own"
  [[ -d "$sandbox/tmp/someone-elses-scratch" ]] \
    || fail "apply removed scratch that is not ours"
  [[ -d "$sandbox/main/.worktrees/issue-4242" ]] \
    || fail "apply removed a .worktrees/issue-* worktree holding unmerged work"

  grep -q 'builder prune -f' "$sandbox/docker-calls.txt" \
    || fail "apply did not prune the docker build cache"
  grep -q 'image prune -f' "$sandbox/docker-calls.txt" \
    || fail "apply did not prune dangling docker images"
  # -a would take other projects' tagged images. AGENTS.md says ask first, so
  # the script must not be able to.
  grep -qE 'prune .*(-a|--all)' "$sandbox/docker-calls.txt" \
    && fail "apply passed -a to a docker prune"
  pass_case "apply reclaims exactly the safe list and preserves every never-touch path"
}

untracked_only_worktree_is_refused() {
  # THE 2026-07-19 SHAPE. `git diff` reports this worktree as clean; only
  # `git status --porcelain` sees the `??` entry. A sweep that trusts `git diff`
  # destroys the whole implementation, which is what happened to #1487.
  local sandbox="$WORK_DIR/cleanup-untracked"
  mkdir -p "$sandbox"
  make_cleanup_sandbox "$sandbox" > /dev/null

  local diff_says_clean
  diff_says_clean="$(git -C "$sandbox/main/.claude/worktrees/agent-untracked" diff)"
  [[ -z "$diff_says_clean" ]] \
    || fail "fixture is wrong: git diff must report the untracked worktree as clean"

  local rc=0 output
  output="$(run_cleanup "$sandbox" --apply --worktrees)" || rc=$?
  (( rc == 0 )) || fail "apply run failed (rc=$rc): $output"
  [[ -f "$sandbox/main/.claude/worktrees/agent-untracked/BrandNewRegressionTest.kt" ]] \
    || fail "the untracked-only worktree was DESTROYED (the #1487 incident)"
  [[ "$output" == *"agent-untracked -- 1 uncommitted/untracked change(s)"* ]] \
    || fail "the refusal did not name the untracked work: $output"
  pass_case "a worktree holding only UNTRACKED work is refused (git diff says clean)"
}

unpushed_worktree_is_refused() {
  local sandbox="$WORK_DIR/cleanup-ahead"
  mkdir -p "$sandbox"
  make_cleanup_sandbox "$sandbox" > /dev/null
  local rc=0 output
  output="$(run_cleanup "$sandbox" --apply --worktrees)" || rc=$?
  (( rc == 0 )) || fail "apply run failed (rc=$rc): $output"
  [[ -f "$sandbox/main/.claude/worktrees/agent-ahead/work.txt" ]] \
    || fail "a worktree with unpushed commits was destroyed"
  [[ "$output" == *"agent-ahead -- 1 commit(s) not reachable from origin/main"* ]] \
    || fail "the refusal did not name the unpushed commit: $output"
  pass_case "a worktree with commits not on origin/main is refused"
}

worktrees_need_the_explicit_opt_in() {
  local sandbox="$WORK_DIR/cleanup-noopt"
  mkdir -p "$sandbox"
  make_cleanup_sandbox "$sandbox" > /dev/null
  local rc=0 output
  output="$(run_cleanup "$sandbox" --apply)" || rc=$?
  (( rc == 0 )) || fail "apply run failed (rc=$rc): $output"
  [[ -d "$sandbox/main/.claude/worktrees/agent-clean" ]] \
    || fail "--apply removed a worktree without --worktrees"
  [[ "$output" == *"pass --worktrees to sweep"* ]] \
    || fail "the report did not explain the opt-in: $output"
  pass_case "worktree removal requires the separate --worktrees opt-in"
}

docker_prune_must_preserve_pocketshell_test_images() {
  # The survival check is load-bearing, not decoration: if a future edit added
  # `-a`, the tagged fixture images would vanish and this must be what stops it.
  # Drive it by making the fake docker drop the image and asserting the script
  # fails, which is the only way to know the check can fire at all (a guard that
  # cannot fire reads exactly like a working one).
  local sandbox="$WORK_DIR/cleanup-images"
  mkdir -p "$sandbox"
  make_cleanup_sandbox "$sandbox" > /dev/null
  cat > "$sandbox/bin/docker" <<DOCKER
#!/usr/bin/env bash
printf '%s\n' "\$*" >> "$sandbox/docker-calls.txt"
case "\$1 \$2" in
  "info "*|"info") exit 0 ;;
  "images "*|"images")
    if [[ -f "$sandbox/pruned" ]]; then
      printf 'other-project:latest\n'
    else
      printf 'pocketshell-test:agents\nother-project:latest\n'
    fi
    exit 0
    ;;
  "image prune"*) : > "$sandbox/pruned"; exit 0 ;;
esac
exit 0
DOCKER
  chmod +x "$sandbox/bin/docker"

  local rc=0 output
  output="$(run_cleanup "$sandbox" --apply)" || rc=$?
  (( rc == 1 )) \
    || fail "the pocketshell-test image survival check did not fire (rc=$rc): $output"
  [[ "$output" == *"pocketshell-test images changed across the prune"* ]] \
    || fail "the survival check did not explain itself: $output"
  pass_case "a prune that loses a pocketshell-test:* image hard-fails the cleanup"
}

cleanup_is_serialized() {
  # Two concurrent sweeps would race on one worktree list and one Docker daemon.
  # Hold the lock from a separate process and assert a second invocation queues
  # and then gives up, rather than running alongside it.
  local sandbox="$WORK_DIR/cleanup-lock"
  mkdir -p "$sandbox/locks"
  make_cleanup_sandbox "$sandbox" > /dev/null

  local lock_file="$sandbox/locks/disk-cleanup.lock"
  : > "$lock_file"
  flock "$lock_file" sleep 20 &
  local holder_pid="$!"
  local waited=0
  while (( waited < 100 )) && flock -n "$lock_file" true; do
    sleep 0.05
    waited=$((waited + 1))
  done
  flock -n "$lock_file" true \
    && { kill "$holder_pid" 2>/dev/null; fail "could not establish the competing lock holder"; }

  local rc=0 output
  output="$(POCKETSHELL_DISK_CLEANUP_LOCK_WAIT_SECONDS=1 run_cleanup "$sandbox" --apply)" || rc=$?
  kill "$holder_pid" 2>/dev/null || true
  wait "$holder_pid" 2>/dev/null || true

  (( rc == 1 )) || fail "a second concurrent cleanup was not serialized (rc=$rc): $output"
  [[ "$output" == *"gave up waiting"* ]] \
    || fail "the serialized run did not report the contention: $output"
  [[ -d "$sandbox/main/build/pre-release-confidence-gate" ]] \
    || fail "the blocked run deleted something anyway"
  pass_case "a second concurrent cleanup queues on a machine-wide lock instead of racing"
}

# The two wrapper reproductions run FIRST, deliberately. Against an unfixed tree
# this file must fail with the REPORTED symptom -- "connected-test.sh started a
# build on a filesystem below the floor" -- and not with "a library this change
# adds is missing", which proves only that the change is absent. `bash -e` is not
# in force but fail() exits, so whichever case fails first is the message a
# reviewer reads; that message has to be the defect.
connected_test_refuses_below_the_floor_before_any_lock
connected_test_proceeds_above_the_floor
connected_test_cleanup_mode_is_exempt
connected_test_survives_a_host_without_user_systemd
full_jvm_gate_probe_refuses_and_allows
halves_agree_on_thresholds_and_rc
documented_floor_matches_the_floor_in_force
measurement_is_real
malformed_threshold_fails_closed
warn_line_fires_before_the_floor
cleanup_dry_run_deletes_nothing
cleanup_apply_reclaims_only_the_safe_list
untracked_only_worktree_is_refused
unpushed_worktree_is_refused
worktrees_need_the_explicit_opt_in
docker_prune_must_preserve_pocketshell_test_images
cleanup_is_serialized

if (( CASES != 17 )); then
  fail "expected 17 cases to run, saw $CASES (a case was skipped or silently removed)"
fi
printf 'PASS: disk preflight + safe-list cleanup harness (%s cases)\n' "$CASES"
