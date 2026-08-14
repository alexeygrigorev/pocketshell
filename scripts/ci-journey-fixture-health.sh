#!/usr/bin/env bash
# ci-journey-fixture-health.sh — shared SSH/tmux fixture health gate for the
# emulator-journey suite (issue #2143).
#
# WHY THIS EXISTS — the measured mechanism, not a hypothesis.
#
# `ReconnectStormLivelockE2eTest` reproduces the #1610 storm by `kill -STOP`-ing
# the tmux SERVER inside the shared `agents` container while leaving sshd alone.
# Every restore path it owns runs IN THE TEST PROCESS: the fixture helper's own
# `catch { resumeTmuxServer() }` and `@After`'s SIGCONT. Both are unreachable
# when the process is not unwound but KILLED — and the journey harness kills a
# class attempt at `JOURNEY_CLASS_TIMEOUT_SECS` (420s).
#
# That is what happened on `main` at 23ed1b10 (run 31764246653, shard 0):
#
#   03:06:39  storm class attempt 1 starts
#   03:10:19  method 1 passes (210s — identical to the green rerun)
#   03:13:27  the 420s per-class cap fires MID-method-2, while that method holds
#             the shared tmux server SIGSTOPped. The process dies; no SIGCONT.
#   03:14:11  attempt 2: all 3 methods fail in SETUP, each after the full 90s
#             `execRemoteSetupUntilReady` deadline
#   03:19:28  ProfileChipRelaunchDockerTest fails twice at `withTimeout(30_000)`
#   03:22:09  FolderListDurableTreeDaemonDockerTest PASSES — it targets the
#             SEPARATE `agents-daemon` container on 2239
#
# Every class after the abort that touched `agents:2222`'s tmux server failed;
# every class that did not, passed. Reproduced headlessly on the real fixture:
# with the server SIGSTOPped the storm test's verbatim seed script hangs past
# 90s (the reported `IllegalStateException ... within 90000ms`), while
# `waitForSshFixtureReady`'s `printf ...; tmux -V` still returns 0 in
# milliseconds — `tmux -V` never contacts the server. THAT is why a wedged
# fixture presents as a per-test SETUP failure rather than "fixture not ready",
# and why it reads like a product regression on a version-only commit.
#
# WHAT THIS DOES. Around every class attempt, probe the shared fixture over the
# SAME SSH path the journey classes use, bounded. A probe that cannot complete
# means the fixture — not the code under test — is broken, so:
#
#   1. record the probe latency (this is the per-shard bring-up timing series
#      the artifacts were missing);
#   2. repair: SIGCONT every stopped process in the container, re-probe, and if
#      it is still wedged kill the tmux server so the next `new-session` starts
#      a fresh one. Journey classes seed their own sessions, and a wedged server
#      has no usable sessions to preserve;
#   3. classify: a failure observed while the shared fixture was wedged is a
#      SETUP failure, reported distinctly from an assertion failure.
#
# This is deliberately a CLASS-level guard, not a patch on one test. #1940 was
# this same cascade and its fix hardened only the in-process assertion-throw
# path, which a SIGKILL cannot reach. Any journey class that wedges the shared
# fixture — this one, a future one, or a killed one — is now contained.
#
# NOT A RETRY. The per-class retry already exists (#712). This gate does not add
# one and never turns a red green: a repaired fixture only means the retry runs
# against a working fixture instead of a frozen one, and a class that still
# fails is still red — with an honest reason.
#
# Sourced by scripts/ci-journey-suite.sh; also runnable standalone:
#   scripts/ci-journey-fixture-health.sh probe   [container]
#   scripts/ci-journey-fixture-health.sh repair  [container]
#   scripts/ci-journey-fixture-health.sh gate    <phase> <label>
#
# When SOURCED it must not mutate the caller's shell options (the suite runs its
# own discipline), so `set` is applied only on direct execution.
if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  set -uo pipefail
fi

# --- configuration seams -----------------------------------------------------
# Every external command is injectable so the guard self-test can drive the real
# control flow with stubs, and so a local run can point at a private fixture
# instead of the shared one.
JOURNEY_FIXTURE_HEALTH_DOCKER="${JOURNEY_FIXTURE_HEALTH_DOCKER:-docker}"
JOURNEY_FIXTURE_HEALTH_SSH="${JOURNEY_FIXTURE_HEALTH_SSH:-ssh}"
JOURNEY_FIXTURE_HEALTH_CONTAINER="${JOURNEY_FIXTURE_HEALTH_CONTAINER:-pocketshell-test-agents}"
JOURNEY_FIXTURE_HEALTH_PORT="${JOURNEY_FIXTURE_HEALTH_PORT:-2222}"
JOURNEY_FIXTURE_HEALTH_USER="${JOURNEY_FIXTURE_HEALTH_USER:-testuser}"
JOURNEY_FIXTURE_HEALTH_HOST="${JOURNEY_FIXTURE_HEALTH_HOST:-127.0.0.1}"
JOURNEY_FIXTURE_HEALTH_KEY="${JOURNEY_FIXTURE_HEALTH_KEY:-tests/docker/test_key}"
# The probe bound must be well UNDER the 90s in-test setup deadline it exists to
# pre-empt, and well OVER a healthy round-trip (measured 56ms on a warm fixture).
JOURNEY_FIXTURE_HEALTH_PROBE_SECS="${JOURNEY_FIXTURE_HEALTH_PROBE_SECS:-20}"
JOURNEY_FIXTURE_HEALTH_REPAIR_SECS="${JOURNEY_FIXTURE_HEALTH_REPAIR_SECS:-30}"
# Set to 1 where there is no shared Docker fixture at all (unit self-tests of
# unrelated harness logic). A MISSING fixture is reported as `unavailable`, never
# silently as `ok` — "I could not check" must not read like "I checked".
JOURNEY_FIXTURE_HEALTH_DISABLED="${JOURNEY_FIXTURE_HEALTH_DISABLED:-0}"

# Set by journey_fixture_health_gate for the caller to read.
JOURNEY_FIXTURE_HEALTH_STATUS="not_run"
JOURNEY_FIXTURE_HEALTH_PROBE_MS="unknown"
JOURNEY_FIXTURE_HEALTH_DETAIL=""

journey_fixture_health_tsv_path() {
  local root="${JOURNEY_FIXTURE_HEALTH_TSV:-}"
  if [[ -n "$root" ]]; then
    printf '%s\n' "$root"
    return 0
  fi
  printf '%s\n' "${ARTIFACT_DIR:-artifacts/ci-journey}/fixture-health.tsv"
}

# The committed key is checked out 0644 on GitHub runners and OpenSSH refuses
# it (#1876). Copy it once to a private 0600 file rather than depending on some
# earlier workflow step having chmod'ed the tracked file — a probe that fails on
# permissions would be misread as a wedged fixture.
journey_fixture_health_private_key() {
  # Keyed by the SOURCE path, not just the pid: two fixtures in one process must
  # not share one cached copy (a stale copy authenticates as the wrong identity
  # and the failure reads as "Permission denied", i.e. an unavailable fixture).
  local tag dest
  tag="$(printf '%s' "$JOURNEY_FIXTURE_HEALTH_KEY" | cksum | tr -d ' ')"
  dest="${TMPDIR:-/tmp}/ci-journey-fixture-health-key.$$.$tag"
  if [[ ! -s "$dest" ]]; then
    cp "$JOURNEY_FIXTURE_HEALTH_KEY" "$dest" 2>/dev/null || {
      printf '%s\n' "$JOURNEY_FIXTURE_HEALTH_KEY"
      return 0
    }
    chmod 600 "$dest" 2>/dev/null || true
  fi
  printf '%s\n' "$dest"
}

journey_fixture_health_now_ms() {
  # `date +%s%3N` is GNU-only; fall back to whole seconds elsewhere.
  local raw
  raw="$(date +%s%3N 2>/dev/null || true)"
  if [[ "$raw" =~ ^[0-9]+$ ]]; then
    printf '%s\n' "$raw"
  else
    printf '%s000\n' "$(date +%s)"
  fi
}

# journey_fixture_probe — one bounded round-trip THROUGH the tmux server over
# the same SSH path the journey classes use.
#
# Exit codes carry the classification, and the three outcomes are genuinely
# distinct (this is the part a naive probe gets wrong):
#   0  healthy — either sessions listed (rc 0) or a FAST, explicit
#      "no server running" (rc 1). An idle fixture with no server is healthy:
#      the next `new-session` starts one.
#   1  wedged  — the bound expired. A stopped/blocked server accepts the socket
#      connection and never answers, so the client hangs forever; that is the
#      exact shape of the reported failure.
#   2  unavailable — ssh itself could not reach the fixture (no container, no
#      key, no port). Reported as its own state, never as healthy.
#
# Writes JOURNEY_FIXTURE_HEALTH_PROBE_MS and JOURNEY_FIXTURE_HEALTH_DETAIL as
# globals rather than echoing them: a `$(journey_fixture_probe)` call would run
# the body in a SUBSHELL and silently drop every variable it set, which is
# exactly the kind of "reads correctly, records nothing" defect this file exists
# to make visible.
journey_fixture_probe() {
  local started ended rc out key
  key="$(journey_fixture_health_private_key)"
  started="$(journey_fixture_health_now_ms)"
  out="$(
    timeout "$JOURNEY_FIXTURE_HEALTH_PROBE_SECS" \
      "$JOURNEY_FIXTURE_HEALTH_SSH" \
      -i "$key" \
      -p "$JOURNEY_FIXTURE_HEALTH_PORT" \
      -o BatchMode=yes \
      -o ConnectTimeout=5 \
      -o StrictHostKeyChecking=no \
      -o UserKnownHostsFile=/dev/null \
      -o LogLevel=ERROR \
      "$JOURNEY_FIXTURE_HEALTH_USER@$JOURNEY_FIXTURE_HEALTH_HOST" \
      'tmux list-sessions' 2>&1
  )"
  rc=$?
  ended="$(journey_fixture_health_now_ms)"
  JOURNEY_FIXTURE_HEALTH_PROBE_MS=$((ended - started))
  JOURNEY_FIXTURE_HEALTH_DETAIL="$(printf '%s' "$out" | tr '\n\t' ';  ' | cut -c1-200)"
  case "$rc" in
    0) return 0 ;;
    124 | 137)
      JOURNEY_FIXTURE_HEALTH_DETAIL="probe_timeout_after_${JOURNEY_FIXTURE_HEALTH_PROBE_SECS}s: $JOURNEY_FIXTURE_HEALTH_DETAIL"
      return 1
      ;;
  esac
  # A non-timeout, non-zero exit is healthy ONLY when tmux itself answered that
  # there is no server. Anything else (auth failure, refused connection, unknown
  # host) is an unavailable fixture, not a healthy one.
  if printf '%s' "$out" | grep -qE 'no server running|no current (client|session)'; then
    return 0
  fi
  return 2
}

# journey_fixture_repair — restore a wedged shared fixture, escalating.
#
# Step 1 SIGCONTs every stopped process in the container. That is the exact
# inverse of the fixture's `kill -STOP`, and it is what the killed test process
# never got to run.
# Step 2, only if the fixture is STILL wedged, kills the tmux server outright so
# the next `new-session` starts a fresh one. Safe here by construction: we only
# reach step 2 with a server that provably cannot answer, so there is no live
# session state to preserve; journey classes seed their own sessions in setup.
#
# Prints what it did. Returns 0 if the fixture probes healthy afterwards.
journey_fixture_repair() {
  local resumed rc
  resumed="$(
    timeout "$JOURNEY_FIXTURE_HEALTH_REPAIR_SECS" \
      "$JOURNEY_FIXTURE_HEALTH_DOCKER" exec "$JOURNEY_FIXTURE_HEALTH_CONTAINER" \
      sh -c 'ps -eo pid,state 2>/dev/null | awk '"'"'$2 ~ /^T/ {print $1}'"'"'' 2>/dev/null | tr '\n' ' '
  )" || true
  echo "JOURNEY_FIXTURE_REPAIR: stopped pids in $JOURNEY_FIXTURE_HEALTH_CONTAINER: [${resumed:-none}] — sending SIGCONT"
  timeout "$JOURNEY_FIXTURE_HEALTH_REPAIR_SECS" \
    "$JOURNEY_FIXTURE_HEALTH_DOCKER" exec "$JOURNEY_FIXTURE_HEALTH_CONTAINER" \
    sh -c 'ps -eo pid,state 2>/dev/null | awk '"'"'$2 ~ /^T/ {print $1}'"'"' | xargs -r kill -CONT; exit 0' \
    >/dev/null 2>&1 || true

  journey_fixture_probe
  rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "JOURNEY_FIXTURE_REPAIR_OK: SIGCONT restored $JOURNEY_FIXTURE_HEALTH_CONTAINER (probe ${JOURNEY_FIXTURE_HEALTH_PROBE_MS}ms)"
    return 0
  fi

  echo "JOURNEY_FIXTURE_REPAIR_ESCALATE: still unhealthy (rc=$rc) after SIGCONT — killing the tmux server so a fresh one can start"
  timeout "$JOURNEY_FIXTURE_HEALTH_REPAIR_SECS" \
    "$JOURNEY_FIXTURE_HEALTH_DOCKER" exec "$JOURNEY_FIXTURE_HEALTH_CONTAINER" \
    sh -c 'pkill -9 -f tmux >/dev/null 2>&1; exit 0' \
    >/dev/null 2>&1 || true

  journey_fixture_probe
  rc=$?
  if [[ $rc -eq 0 ]]; then
    echo "JOURNEY_FIXTURE_REPAIR_OK: tmux-server kill restored $JOURNEY_FIXTURE_HEALTH_CONTAINER (probe ${JOURNEY_FIXTURE_HEALTH_PROBE_MS}ms)"
    return 0
  fi
  echo "JOURNEY_FIXTURE_REPAIR_FAILED: $JOURNEY_FIXTURE_HEALTH_CONTAINER is still not answering (probe ${JOURNEY_FIXTURE_HEALTH_PROBE_MS}ms, ${JOURNEY_FIXTURE_HEALTH_DETAIL})" >&2
  return 1
}

# journey_fixture_health_gate <phase> <label> — probe, repair if needed, record.
#
# Sets JOURNEY_FIXTURE_HEALTH_STATUS to one of:
#   ok               the fixture answered on the first probe
#   wedged_repaired  it did not, and the repair restored it
#   wedged           it did not, and the repair could not restore it
#   unavailable      the fixture could not be reached at all
#   skipped          this run has no shared fixture (explicitly disabled)
# Always returns 0: the gate reports, it does not decide the verdict.
journey_fixture_health_gate() {
  local phase="$1" label="${2:-}" rc
  JOURNEY_FIXTURE_HEALTH_DETAIL=""
  if [[ "$JOURNEY_FIXTURE_HEALTH_DISABLED" == "1" ]]; then
    JOURNEY_FIXTURE_HEALTH_STATUS="skipped"
    JOURNEY_FIXTURE_HEALTH_PROBE_MS="unknown"
    journey_fixture_health_record "$phase" "$label"
    return 0
  fi

  journey_fixture_probe
  rc=$?
  case "$rc" in
    0)
      JOURNEY_FIXTURE_HEALTH_STATUS="ok"
      echo "JOURNEY_FIXTURE_HEALTH: phase=$phase label=${label:-none} fixture=$JOURNEY_FIXTURE_HEALTH_CONTAINER status=ok probe_ms=$JOURNEY_FIXTURE_HEALTH_PROBE_MS"
      ;;
    1)
      echo "JOURNEY_FIXTURE_WEDGED: phase=$phase label=${label:-none} fixture=$JOURNEY_FIXTURE_HEALTH_CONTAINER did not answer within ${JOURNEY_FIXTURE_HEALTH_PROBE_SECS}s — this is a SETUP failure of the shared fixture, not an assertion failure of the code under test (issue #2143)" >&2
      if journey_fixture_repair; then
        JOURNEY_FIXTURE_HEALTH_STATUS="wedged_repaired"
      else
        JOURNEY_FIXTURE_HEALTH_STATUS="wedged"
      fi
      ;;
    *)
      JOURNEY_FIXTURE_HEALTH_STATUS="unavailable"
      echo "JOURNEY_FIXTURE_UNAVAILABLE: phase=$phase label=${label:-none} fixture=$JOURNEY_FIXTURE_HEALTH_CONTAINER unreachable (${JOURNEY_FIXTURE_HEALTH_DETAIL})" >&2
      ;;
  esac
  journey_fixture_health_record "$phase" "$label"
  return 0
}

journey_fixture_health_record() {
  local phase="$1" label="${2:-}"
  local tsv
  tsv="$(journey_fixture_health_tsv_path)"
  mkdir -p "$(dirname "$tsv")" 2>/dev/null || return 0
  if [[ ! -s "$tsv" ]]; then
    printf 'timestamp_utc\tphase\tlabel\tfixture\tstatus\tprobe_ms\tdetail\n' > "$tsv" || return 0
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    "$phase" \
    "${label:-none}" \
    "$JOURNEY_FIXTURE_HEALTH_CONTAINER" \
    "$JOURNEY_FIXTURE_HEALTH_STATUS" \
    "$JOURNEY_FIXTURE_HEALTH_PROBE_MS" \
    "${JOURNEY_FIXTURE_HEALTH_DETAIL:-}" >> "$tsv" || true
  return 0
}

# journey_fixture_health_was_wedged — did the LAST gate observe a broken fixture?
# Used by the class loop to classify a failure as SETUP rather than assertion.
journey_fixture_health_was_wedged() {
  [[ "$JOURNEY_FIXTURE_HEALTH_STATUS" == "wedged_repaired" \
     || "$JOURNEY_FIXTURE_HEALTH_STATUS" == "wedged" \
     || "$JOURNEY_FIXTURE_HEALTH_STATUS" == "unavailable" ]]
}

if [[ "${BASH_SOURCE[0]}" == "$0" ]]; then
  cmd="${1:-gate}"
  shift || true
  case "$cmd" in
    probe)
      journey_fixture_probe; rc=$?
      echo "probe_ms=$JOURNEY_FIXTURE_HEALTH_PROBE_MS rc=$rc detail=$JOURNEY_FIXTURE_HEALTH_DETAIL"
      exit "$rc"
      ;;
    repair) journey_fixture_repair; exit $? ;;
    gate)
      journey_fixture_health_gate "${1:-manual}" "${2:-}"
      echo "status=$JOURNEY_FIXTURE_HEALTH_STATUS probe_ms=$JOURNEY_FIXTURE_HEALTH_PROBE_MS"
      [[ "$JOURNEY_FIXTURE_HEALTH_STATUS" == "ok" || "$JOURNEY_FIXTURE_HEALTH_STATUS" == "wedged_repaired" || "$JOURNEY_FIXTURE_HEALTH_STATUS" == "skipped" ]]
      exit $?
      ;;
    *) echo "usage: $0 {probe|repair|gate <phase> [label]}" >&2; exit 2 ;;
  esac
fi
