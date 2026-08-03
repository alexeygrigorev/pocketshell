#!/usr/bin/env bash
# Issue #1883: regression fixture for the post-#1863 dead-channel oracle.
# Pure filesystem/Python test: no Gradle, emulator, Docker, or tmux.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd -P)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd -P)"
ORACLE="$SCRIPT_DIR/ci-journey-dead-channel-oracle.py"
GATE="$REPO_ROOT/app/src/main/java/com/pocketshell/app/tmux/connection/LivenessProbeGate.kt"
PROBE="$REPO_ROOT/shared/core-connection/src/main/java/com/pocketshell/core/connection/LivenessProbe.kt"

fail() { echo "TEST FAIL: $*" >&2; exit 1; }
pass() { echo "  ok: $*"; }

[[ -f "$ORACLE" ]] || fail "oracle missing: $ORACLE"
for source in "$GATE" "$PROBE"; do
  [[ -f "$source" ]] || fail "production source missing: $source"
done

sandbox="$(mktemp -d)"
trap 'rm -rf "$sandbox"' EXIT
canonical="$sandbox/artifacts/ci-journey/class-attempts/app"
signal='liveness-probe DECLARED DROP (control channel definitively closed)'

make_attempt() {
  local key="$1" class="$2" attempt="$3"
  local dir="$canonical/$key/attempt-$attempt"
  mkdir -p "$dir"
  {
    printf 'format_version=1\n'
    printf 'module=app\n'
    printf 'class=%s\n' "$class"
    printf 'attempt=%s\n' "$attempt"
  } > "$dir/manifest.txt"
  printf '%s' "$dir"
}

wedge1="$(make_attempt KnownWedged--aaaaaaaaaaaaaaaa com.pocketshell.KnownWedged 1)"
printf '%s\n%s\n' "$signal consecutive=1" "$signal consecutive=1" > "$wedge1/device-logcat.txt"
mkdir -p "$wedge1/android-test-outputs/results"
printf '%s\n%s\n%s\n' "$signal" "$signal" "$signal" \
  > "$wedge1/android-test-outputs/results/logcat-com.pocketshell.KnownWedged-first.txt"
printf '%s\n' "$signal" \
  > "$wedge1/android-test-outputs/results/logcat-com.pocketshell.KnownWedged-second.txt"

wedge2="$(make_attempt KnownWedged--aaaaaaaaaaaaaaaa com.pocketshell.KnownWedged 2)"
printf '%s\n' "$signal consecutive=1" > "$wedge2/device-logcat.txt"

control="$(make_attempt ReconnectStormLivelockE2eTest--bbbbbbbbbbbbbbbb com.pocketshell.app.proof.ReconnectStormLivelockE2eTest 1)"
{
  for _ in $(seq 1 4); do
    printf '%s\n' 'gate closed bg=false appActive=true hasClient=true disconnected=false ctrl=Reattaching'
  done
  for _ in $(seq 1 18); do
    printf '%s\n' 'gate closed bg=false appActive=true hasClient=true disconnected=false ctrl=Attaching'
  done
  for _ in $(seq 1 18); do
    printf '%s\n' 'gate closed bg=false appActive=true hasClient=true disconnected=false ctrl=Reconnecting'
  done
} > "$control/device-logcat.txt"

# The artifact upload also carries this duplicate snapshot. It must never be
# scanned, or attempt 1 is counted twice (and retry totals become nonsense).
duplicate="$sandbox/artifacts/ci-journey-attempt-1/ci-journey/class-attempts/app/Duplicate--cccccccccccccccc/attempt-1"
mkdir -p "$duplicate"
for _ in $(seq 1 99); do printf '%s\n' "$signal"; done > "$duplicate/device-logcat.txt"

# RED control: substitute the obsolete foreground signature into the real
# counter. It misses both the synthetic post-fix dead-channel attempts and the
# faithful current ReconnectStorm control shapes. Its blindness to the known
# positive rows is the load-bearing mutation failure.
mutant="$sandbox/old-gate-closed-oracle.py"
sed 's|^SIGNAL = .*|SIGNAL = "gate closed bg=false appActive=true hasClient=true disconnected=true ctrl=Live"|' \
  "$ORACLE" > "$mutant"
mutant_output="$(python3 "$mutant" "$sandbox")" || fail "old-oracle mutant did not run"
grep -Fqx $'com.pocketshell.KnownWedged\t1\t0\t0\t0\tZERO_OBSERVED' <<<"$mutant_output" \
  || fail "RED control: old oracle unexpectedly saw the post-fix dead-channel fixture"
grep -Fqx $'com.pocketshell.app.proof.ReconnectStormLivelockE2eTest\t1\t0\t0\t0\tZERO_OBSERVED' <<<"$mutant_output" \
  || fail "RED control: old oracle unexpectedly matched a faithful current control shape"
pass "RED control: obsolete foreground oracle misses the post-#1863 known positive"

output="$("$ORACLE" "$sandbox")" || fail "oracle exited non-zero"
printf '%s\n' "$output"

grep -Fqx $'com.pocketshell.KnownWedged\t1\t2\t4\t4\tDEAD_CHANNEL_OBSERVED' <<<"$output" \
  || fail "attempt 1 must use max(device=2, aggregate UTP=4), not sum or one source"
grep -Fqx $'com.pocketshell.KnownWedged\t2\t1\t0\t1\tDEAD_CHANNEL_OBSERVED' <<<"$output" \
  || fail "attempt 2 must remain a separate row"
grep -Fqx $'com.pocketshell.app.proof.ReconnectStormLivelockE2eTest\t1\t0\t0\t0\tZERO_OBSERVED' <<<"$output" \
  || fail "ReconnectStorm faithful current high-emission control must score zero"
[[ "$(grep -c '^com.pocketshell.KnownWedged' <<<"$output")" -eq 2 ]] \
  || fail "attempts were summed or duplicate snapshot was scanned"
! grep -q 'Duplicate' <<<"$output" \
  || fail "obsolete ci-journey-attempt-1 snapshot entered the measurement"
pass "canonical-only, max-of-sources, per-attempt measurement and specificity"

# Structural red control: after #1863, foreground Live+disconnected selects
# DeadChannel, while the obsolete text is emitted only for Closed by the same
# gate authority. Therefore an old foreground `gate closed` grep is guaranteed
# to miss the repaired path.
grep -q 'controlChannelDisconnected -> LivenessProbeGate.DeadChannel' "$GATE" \
  || fail "foreground disconnected channel no longer selects DeadChannel"
closed_block="$(sed -n '/if (gate == LivenessProbeGate.Closed)/,/^[[:space:]]*}/p' "$GATE")"
grep -q '"gate closed bg=' <<<"$closed_block" \
  || fail "could not prove old message is confined to the Closed branch"
[[ "$(grep -c '"gate closed bg=' "$GATE")" -eq "$(grep -c '"gate closed bg=' <<<"$closed_block")" ]] \
  || fail "old message escaped the Closed branch in the gate authority"
grep -Fq "$signal" "$PROBE" \
  || fail "replacement signal no longer exists at the definitive-close decision"
old_foreground_count="$(grep -c 'gate closed bg=false appActive=true hasClient=true disconnected=true ctrl=Live' \
  "$wedge1/device-logcat.txt" || true)"
new_dead_channel_count="$(grep -cF "$signal" "$wedge1/device-logcat.txt" || true)"
[[ "$old_foreground_count" -eq 0 && "$new_dead_channel_count" -gt 0 ]] \
  || fail "post-#1863 dead-channel fixture did not reproduce old=0 / replacement>0"
pass "old foreground oracle is structurally blind; replacement survives"

# The post-fix scale is event presence, not the obsolete severity ladder:
# exact connected calibration gives known-dead-wire=1 while real healthy and
# high-emission ReconnectStorm controls give 0.
[[ "$(grep -c $'\tDEAD_CHANNEL_OBSERVED$' <<<"$output")" -eq 2 ]] \
  || fail "positive attempts must use the calibrated event-presence category"
[[ "$(grep -c $'\tZERO_OBSERVED$' <<<"$output")" -eq 1 ]] \
  || fail "zero-event control must use ZERO_OBSERVED"
! grep -Eq $'\t(healthy|degraded|wedged)$' <<<"$output" \
  || fail "oracle laundered event presence into an unsupported severity verdict"
pass "new binary event-presence scale never reuses obsolete severity thresholds"

if "$ORACLE" "$sandbox/artifacts/ci-journey-attempt-1" >/dev/null 2>&1; then
  fail "snapshot root must be rejected instead of silently measured"
fi
pass "non-canonical/snapshot input fails closed"

empty_report="$sandbox/empty-report"
mkdir -p "$empty_report/artifacts/ci-journey/class-attempts"
empty_error=""
if empty_error="$("$ORACLE" "$empty_report" 2>&1)"; then
  fail "canonical tree with zero attempts must fail, not print a header-only zero observation"
fi
grep -Fq 'canonical journey attempts tree is empty' <<<"$empty_error" \
  || fail "empty-tree failure did not identify missing observation evidence"
pass "canonical tree with zero attempts fails closed"

missing_logs_report="$sandbox/missing-logs-report"
missing_logs_root="$missing_logs_report/artifacts/ci-journey/class-attempts/app/MissingLogs--dddddddddddddddd/attempt-1"
mkdir -p "$missing_logs_root"
{
  printf 'format_version=1\n'
  printf 'module=app\n'
  printf 'class=com.pocketshell.MissingLogs\n'
  printf 'attempt=1\n'
} > "$missing_logs_root/manifest.txt"
missing_logs_error=""
if missing_logs_error="$("$ORACLE" "$missing_logs_report" 2>&1)"; then
  fail "attempt with neither device nor UTP logcat must fail, not emit an observed zero"
fi
grep -Fq 'attempt has no readable device or UTP logcat source' <<<"$missing_logs_error" \
  || fail "missing-log failure did not identify missing observation evidence"
pass "attempt with no readable log source fails closed"
