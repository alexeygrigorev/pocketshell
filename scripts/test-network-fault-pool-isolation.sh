#!/usr/bin/env bash
set -euo pipefail

# Network-fault --pool isolation harness (issue #2128).
#
# THE DEFECT this harness exists to stop coming back: `connected-test.sh --pool`
# promises each lane a distinct `(emulator, agents-port)` claim, but
# `NetworkFaultProofBase` hardcodes host ports 2222/2228 and Toxiproxy's
# upstream `agents:22`. A fault-class lane therefore stays pinned to the shared
# `pocketshell-test-agents` / `pocketshell-test-network-fault-proxy` fixture
# regardless of what the pool allocated. Concurrent siblings wipe that fixture
# mid-run (empty session list — the #1842 class, in a place #1842's fix does
# not reach).
#
# No docker daemon, no emulator, no Gradle: this is a static + formula guard
# so a future pin cannot land as "isolation that does not isolate".
#
# Wired into the per-push Unit job next to scripts/test-agents-pool-isolation.sh
# (G9). An unwired regression test is not a regression test.

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
[[ -f "$ROOT_DIR/scripts/lib/agents-pool.sh" ]] \
  || { printf 'FAIL: ROOT_DIR=%s does not look like the repo root\n' "$ROOT_DIR" >&2; exit 2; }

FAILURES=0

fail() {
  printf 'FAIL: %s\n' "$1" >&2
  FAILURES=$((FAILURES + 1))
  return 1
}

pass() {
  printf '  ok: %s\n' "$1"
}

# REPOINTED BY THE REWRITE (D22, no shim). The old app module owned four files
# this harness read — NetworkFaultProofBase.kt, a src/debug NetworkFaultPorts.kt,
# its src/testDebug NetworkFaultPortsTest.kt, and two journeys that kept their
# own `= 2228` copies. All five are gone with the module. app2 keeps the SAME
# property in ONE place instead: ToxiproxyControl's companion derives both the
# fault SSH port and the Toxiproxy API port from the lane's agents port. So the
# Kotlin-side checks below read that file, and the checks whose subject has no
# app2 successor are deleted rather than repointed at a stand-in.
TOXICONTROL="$ROOT_DIR/app2/src/androidTest/java/com/pocketshell/next/connect/ToxiproxyControl.kt"
APP2_ANDROID_TEST="$ROOT_DIR/app2/src/androidTest"
COMPOSE="$ROOT_DIR/tests/docker/docker-compose.yml"
AGENTS_LIB="$ROOT_DIR/scripts/lib/agents-pool.sh"
CONNECTED="$ROOT_DIR/scripts/connected-test.sh"
TESTING_MD="$ROOT_DIR/docs/testing.md"
# The Gradle task app2's journey lane runs, unfiltered, per issue #2474. It is
# read from the suite that actually runs it so this harness cannot drift from it.
JOURNEY_SUITE="$ROOT_DIR/scripts/ci-app2-journey-suite.sh"

# --------------------------------------------------------------------------
# 1. THE BUG (red on base): the host ports the APP dials must be DERIVED from
#    the lane's agents port, never pinned as compile-time constants. A const
#    2228 is what made --pool a lie for every fault-class lane: agentsPort=2243
#    was honoured for the unproxied seed and then thrown away when the app
#    attached through hardcoded 2228.
#
#    In app2 this lives in ToxiproxyControl's companion (faultSshPort /
#    defaultApiPort). The single-lane 2228/8474 identity is allowed to appear
#    there — --no-pool and the nightly must not move — but ONLY inside the
#    `agentsPort == SINGLE_LANE_AGENTS_PORT` branch. A literal reachable from
#    any other agents port is the pin coming back.
# --------------------------------------------------------------------------
fault_ports_must_derive_from_the_agents_port() {
  if [[ ! -f "$TOXICONTROL" ]]; then
    fail "ToxiproxyControl.kt is missing at $TOXICONTROL; the host-port derivation has no home and a pool lane could only be on the shared 2228/8474 singleton (issue #2128)"
    return 1
  fi
  local fn
  for fn in faultSshPort defaultApiPort; do
    if ! grep -qE "fun $fn\\(agentsPort: Int = AgentsFixture\\.port\\)" "$TOXICONTROL"; then
      fail "ToxiproxyControl.$fn does not take the lane's agentsPort (defaulting to AgentsFixture.port), so it cannot honour a --pool allocation (issue #2128)"
      return 1
    fi
  done
  # Every occurrence of a shared-fixture literal must be a `const val` naming it
  # SINGLE_LANE_*, or a reference to one of those names. A bare 2228/8474 in an
  # expression is the pin.
  local stray
  stray="$(grep -nE '(^|[^A-Za-z0-9_])(2228|2229|8474)([^0-9]|$)' "$TOXICONTROL" \
    | grep -v 'SINGLE_LANE_' \
    | grep -v 'CONTAINER_LISTEN' \
    | grep -vE '^[0-9]+: *(\*|//)' \
    || true)"
  if [[ -n "$stray" ]]; then
    fail "ToxiproxyControl carries a shared-fixture port literal outside a SINGLE_LANE_ constant, so a pool lane can still land on the shared proxy (issue #2128):
$stray"
    return 1
  fi
  # ...and the single-lane constants must only be REACHED through the
  # agentsPort == SINGLE_LANE_AGENTS_PORT test.
  local uses guarded
  uses="$(grep -c 'SINGLE_LANE_FAULT_SSH_PORT\|SINGLE_LANE_API_PORT' "$TOXICONTROL" || true)"
  guarded="$(grep -c 'agentsPort == SINGLE_LANE_AGENTS_PORT' "$TOXICONTROL" || true)"
  if (( uses < 2 || guarded < 2 )); then
    fail "the single-lane 2228/8474 identity is not gated on 'agentsPort == SINGLE_LANE_AGENTS_PORT' in both derivations (uses=$uses guarded=$guarded); a pool lane would inherit the shared fixture (issue #2128)"
    return 1
  fi
  pass "ToxiproxyControl derives fault SSH/API ports from the lane's agents port; 2228/8474 only behind the single-lane branch"
}

# --------------------------------------------------------------------------
# 2. Hard-cut (D22): no "use 2228 if unset" leftover that reintroduces the pin
#    as the default. A `?: 2228` / `?: 8474` on the instrumentation-argument
#    path is the same bug wearing a different hat — the override must fall back
#    to the DERIVATION, not to the shared literal.
# --------------------------------------------------------------------------
no_unset_fallback_reintroduces_the_shared_pin() {
  [[ -f "$TOXICONTROL" ]] || { fail "ToxiproxyControl.kt is missing; cannot check the unset-fallback path"; return 1; }
  local hits
  hits="$(grep -nE '\?:[[:space:]]*(2228|2229|8474)|else[[:space:]]+(2228|8474)' "$TOXICONTROL" \
    | grep -v ':[[:space:]]*//' \
    | grep -v ':[[:space:]]*\*' \
    || true)"
  if [[ -n "$hits" ]]; then
    fail "a 'use 2228/8474 if unset' fallback is still on the fault path; a --pool lane that only sets agentsPort would silently re-pin (issue #2128 / D22): $hits"
    return 1
  fi
  # The instrumentation overrides must fall back to the derivation by NAME.
  local arg
  for arg in 'faultSshPortArg(): Int = instrumentationPort("faultSshPort") ?: faultSshPort()' \
             'apiPortArg(): Int = instrumentationPort("toxiproxyApiPort") ?: defaultApiPort()'; do
    grep -Fq "$arg" "$TOXICONTROL" \
      || { fail "ToxiproxyControl lost the derivation fallback '$arg'; an unset instrumentation argument must resolve through the formula, not a literal (issue #2128)"; return 1; }
  done
  pass "no unset-fallback reintroduces 2228/8474; overrides fall back to the derivation"
}

# --------------------------------------------------------------------------
# 3. ToxiproxyControl's proxy creation must take listen/upstream from
#    parameters, not a single hardcoded POST body. Per-lane toxiproxy still
#    uses `agents:22` *inside its own compose project* (which is why the
#    CONTAINER_* defaults keep those literals), but the control object must not
#    be the thing that re-pins a pool lane onto the shared proxy.
# --------------------------------------------------------------------------
toxiproxy_control_must_parameterise_listen_and_upstream() {
  [[ -f "$TOXICONTROL" ]] || { fail "ToxiproxyControl.kt is missing; cannot check listen/upstream parameterisation"; return 1; }
  if ! grep -qE 'private val listen: String = CONTAINER_LISTEN' "$TOXICONTROL" ||
     ! grep -qE 'private val upstream: String = CONTAINER_UPSTREAM' "$TOXICONTROL"; then
    fail "ToxiproxyControl does not expose listen/upstream as constructor parameters (issue #2128)"
    return 1
  fi
  # The POST body must interpolate, not embed the fixture literals as the only
  # path. A leftover """...0.0.0.0:2228...agents:22...""" in reset() is the pin.
  local body
  body="$(sed -n '/fun reset()/,/^    }$/p' "$TOXICONTROL")"
  if [[ -z "$body" ]]; then
    fail "could not read ToxiproxyControl.reset(); the POST-body check would pass vacuously (issue #2128)"
    return 1
  fi
  if grep -q '0.0.0.0:2228' <<<"$body"; then
    fail "ToxiproxyControl.reset() still embeds listen 0.0.0.0:2228 in the POST body, so a pool lane cannot claim its own proxy (issue #2128)"
    return 1
  fi
  if grep -q 'agents:22' <<<"$body"; then
    fail "ToxiproxyControl.reset() still embeds upstream agents:22 in the POST body (issue #2128)"
    return 1
  fi
  grep -q '"listen":"\$listen"' <<<"$body" && grep -q '"upstream":"\$upstream"' <<<"$body" \
    || { fail "ToxiproxyControl.reset() does not interpolate the listen/upstream parameters into the POST body (issue #2128)"; return 1; }
  pass "ToxiproxyControl.reset() parameterises listen and upstream"
}

# --------------------------------------------------------------------------
# 4. THE FORMULA (red on base: the functions do not exist). A pool agents
#    port must resolve to fault/API ports that are NOT the shared 2228/8474
#    singleton. Single-lane 2222 must keep the historical 2228/2229/8474
#    identity so --no-pool / nightly stay byte-identical.
# --------------------------------------------------------------------------
source_pool_lib() {
  # shellcheck source=scripts/lib/agents-pool.sh
  source "$AGENTS_LIB"
}

pool_lane_must_not_resolve_to_the_shared_fault_ports() {
  if ! grep -q 'pocketshell_network_fault_ssh_port' "$AGENTS_LIB"; then
    fail "scripts/lib/agents-pool.sh has no pocketshell_network_fault_ssh_port; there is no way for a --pool lane to honour its allocated port on the fault path (issue #2128)"
    return 1
  fi
  source_pool_lib
  local agents=2243
  local fault api loss
  fault="$(pocketshell_network_fault_ssh_port "$agents")"
  api="$(pocketshell_toxiproxy_api_port "$agents")"
  loss="$(pocketshell_packet_loss_ssh_port "$agents")"
  if [[ "$fault" == "2228" || "$fault" == "2222" ]]; then
    fail "agents port $agents resolved to fault SSH $fault — that is the shared-fixture pin (issue #2128)"
    return 1
  fi
  if [[ "$api" == "8474" ]]; then
    fail "agents port $agents resolved to toxiproxy API 8474 — that is the shared-fixture pin (issue #2128)"
    return 1
  fi
  if [[ "$loss" == "2229" || "$loss" == "2222" ]]; then
    fail "agents port $agents resolved to packet-loss $loss — that is the shared-fixture pin (issue #2128)"
    return 1
  fi
  pass "pool agents port $agents resolves to isolated fault=$fault packet-loss=$loss api=$api"
}

single_lane_keeps_the_historical_fault_identity() {
  if ! grep -q 'pocketshell_network_fault_ssh_port' "$AGENTS_LIB"; then
    fail "no derivation function; cannot prove the single-lane 2222 -> 2228 identity is preserved"
    return 1
  fi
  source_pool_lib
  local fault api loss
  fault="$(pocketshell_network_fault_ssh_port 2222)"
  api="$(pocketshell_toxiproxy_api_port 2222)"
  loss="$(pocketshell_packet_loss_ssh_port 2222)"
  if [[ "$fault" != "2228" || "$api" != "8474" || "$loss" != "2229" ]]; then
    fail "single-lane 2222 must stay on the historical 2228/2229/8474 identity (got fault=$fault loss=$loss api=$api); --no-pool / nightly must not move"
    return 1
  fi
  pass "single-lane 2222 still maps to 2228/2229/8474"
}

two_pool_lanes_get_disjoint_fault_ports() {
  if ! grep -q 'pocketshell_network_fault_ssh_port' "$AGENTS_LIB"; then
    fail "no derivation function; cannot prove two pool lanes get disjoint fault ports"
    return 1
  fi
  source_pool_lib
  local a_fault a_api a_loss b_fault b_api b_loss
  a_fault="$(pocketshell_network_fault_ssh_port 2243)"
  a_api="$(pocketshell_toxiproxy_api_port 2243)"
  a_loss="$(pocketshell_packet_loss_ssh_port 2243)"
  b_fault="$(pocketshell_network_fault_ssh_port 2244)"
  b_api="$(pocketshell_toxiproxy_api_port 2244)"
  b_loss="$(pocketshell_packet_loss_ssh_port 2244)"
  if [[ "$a_fault" == "$b_fault" || "$a_api" == "$b_api" || "$a_loss" == "$b_loss" ]]; then
    fail "pool lanes 2243 and 2244 collapsed onto the same fault ports (2243 -> $a_fault/$a_loss/$a_api, 2244 -> $b_fault/$b_loss/$b_api); concurrent fault-class lanes would share one proxy (issue #2128)"
    return 1
  fi
  local port
  for port in "$a_fault" "$a_api" "$a_loss" "$b_fault" "$b_api" "$b_loss"; do
    case "$port" in
      2222|2228|2229|8474)
        fail "a pool-derived port landed on a shared-fixture port ($port)"
        return 1
        ;;
    esac
  done
  pass "pool lanes 2243 and 2244 get disjoint fault ports ($a_fault/$a_loss/$a_api vs $b_fault/$b_loss/$b_api)"
}

# --------------------------------------------------------------------------
# 6. docker-compose must publish parameterized host ports and container
#    names. A hardcoded "2228:2228" / pocketshell-test-network-fault-proxy
#    with no ${...} is why a pool lane cannot claim its own proxy.
# --------------------------------------------------------------------------
compose_fault_proxy_is_parameterised() {
  if ! grep -q 'NETWORK_FAULT_HOST_PORT' "$COMPOSE"; then
    fail "docker-compose.yml network-fault-proxy host port is not parameterized (no NETWORK_FAULT_HOST_PORT); two lanes cannot publish distinct 2228-equivalents (issue #2128)"
    return 1
  fi
  if ! grep -q 'TOXIPROXY_API_HOST_PORT' "$COMPOSE"; then
    fail "docker-compose.yml toxiproxy API host port is not parameterized (no TOXIPROXY_API_HOST_PORT) (issue #2128)"
    return 1
  fi
  if ! grep -q 'NETWORK_FAULT_CONTAINER_NAME' "$COMPOSE"; then
    fail "docker-compose.yml network-fault-proxy container_name is not parameterized; two compose projects would collide on pocketshell-test-network-fault-proxy (issue #2128)"
    return 1
  fi
  if ! grep -q 'PACKET_LOSS_HOST_PORT' "$COMPOSE"; then
    fail "docker-compose.yml packet-loss-proxy host port is not parameterized (issue #2128)"
    return 1
  fi
  if ! grep -q 'PACKET_LOSS_CONTAINER_NAME' "$COMPOSE"; then
    fail "docker-compose.yml packet-loss-proxy container_name is not parameterized (issue #2128)"
    return 1
  fi
  # Defaults must keep the single-lane identity.
  grep -q 'NETWORK_FAULT_HOST_PORT:-2228' "$COMPOSE" \
    || { fail "NETWORK_FAULT_HOST_PORT default is not 2228; --no-pool / nightly would move"; return 1; }
  grep -q 'TOXIPROXY_API_HOST_PORT:-8474' "$COMPOSE" \
    || { fail "TOXIPROXY_API_HOST_PORT default is not 8474; --no-pool / nightly would move"; return 1; }
  pass "compose network-fault and packet-loss proxies are parameterized (defaults keep 2228/2229/8474)"
}

# --------------------------------------------------------------------------
# 7. connected-test.sh must bring up the per-lane fault proxies on a pool
#    fault-class run, and must not claim that they are "NOT pool-isolated".
# --------------------------------------------------------------------------
connected_test_isolates_pool_fault_classes() {
  if ! grep -q 'pocketshell_network_fault_fixture_up' "$CONNECTED"; then
    fail "connected-test.sh never calls pocketshell_network_fault_fixture_up, so a --pool fault-class lane has no per-lane toxiproxy to honour (issue #2128)"
    return 1
  fi
  if grep -q 'are NOT pool-isolated' "$CONNECTED"; then
    fail "connected-test.sh still documents network-fault proxies as NOT pool-isolated — that warning is now a lie if isolation landed, and a confession if it did not (issue #2128)"
    return 1
  fi
  pass "connected-test.sh brings up per-lane fault proxies for a pool fault-class run"
}

# --------------------------------------------------------------------------
# 8. A wipe of the fault fixture must be distinguishable from a product
#    failure. The banner must name the collision and inoculate the reader
#    against the empty-session-list signature (#1810/#1820/#1842).
# --------------------------------------------------------------------------
a_disturbed_fault_fixture_is_not_an_empty_session_list() {
  if ! grep -q 'pocketshell_network_fault_assert_fixture_undisturbed' "$AGENTS_LIB"; then
    fail "no fault-fixture disturbance check; a wiped network-fault-proxy would present as an empty session list / ATTACH death (issue #2128)"
    return 1
  fi
  local tmp
  tmp="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-nf-wipe.XXXXXX")"
  export POCKETSHELL_AVD_LOCK_DIR="$tmp/locks"
  export AGENTS_POOL_TEST_STATE="$tmp/docker-state"
  mkdir -p "$AGENTS_POOL_TEST_STATE"
  printf 'sha256:BEFORE 2026-08-18T00:00:00Z\n' > "$AGENTS_POOL_TEST_STATE/identity"
  # Minimal docker stub so identity inspect works.
  mkdir -p "$tmp/bin"
  cat > "$tmp/bin/docker" <<'DOCKER_STUB'
#!/usr/bin/env bash
set -u
state="${AGENTS_POOL_TEST_STATE:?}"
if [[ "${1:-}" == "inspect" ]]; then
  case "${2:-}" in
    *.Id*) cat "$state/identity"; exit 0 ;;
  esac
fi
exit 0
DOCKER_STUB
  chmod +x "$tmp/bin/docker"
  local oldpath="$PATH"
  export PATH="$tmp/bin:$PATH"
  source_pool_lib
  pocketshell_network_fault_record_fixture_identity 2243
  printf 'sha256:AFTER 2026-08-18T01:00:00Z\n' > "$AGENTS_POOL_TEST_STATE/identity"
  local out rc=0
  out="$(pocketshell_network_fault_assert_fixture_undisturbed 2243 2>&1)" || rc=$?
  export PATH="$oldpath"
  rm -rf "$tmp"
  if (( rc == 0 )); then
    fail "a recreated network-fault-proxy was NOT detected — the lane would report an empty session list as a product defect (issue #2128)"
    return 1
  fi
  local phrase
  for phrase in "NETWORK-FAULT FIXTURE DISTURBED" "2243" "empty session list" "NOT evidence of a product" "2128"; do
    if [[ "$out" != *"$phrase"* ]]; then
      fail "the disturbed-fault-fixture banner is missing '$phrase'; it must be impossible to mistake for an empty-session-list product bug. Got: $out"
      return 1
    fi
  done
  pass "a wiped network-fault fixture fails with a banner that cannot be read as an empty session list"
}

# --------------------------------------------------------------------------
# 9. Kotlin and bash formulas must agree. Two copies of "2243 + 10" that
#    drift are how the next pin lands.
# --------------------------------------------------------------------------
kotlin_and_bash_formulas_agree() {
  [[ -f "$TOXICONTROL" ]] || { fail "ToxiproxyControl.kt is missing; there is no Kotlin formula to agree with bash (issue #2128)"; return 1; }
  if ! grep -q 'pocketshell_network_fault_ssh_port' "$AGENTS_LIB"; then
    fail "bash derivation is missing; cannot prove it matches Kotlin"
    return 1
  fi
  # Kotlin: `else agentsPort + <offset>` inside faultSshPort.
  local k_fault k_single_api b_fault
  k_fault="$(sed -n '/fun faultSshPort(/,/^$/p' "$TOXICONTROL" \
    | sed -n 's/.*else agentsPort + \([0-9]\+\).*/\1/p' | head -n1)"
  k_single_api="$(sed -n 's/.*SINGLE_LANE_API_PORT: Int = \([0-9]\+\).*/\1/p' "$TOXICONTROL" | head -n1)"
  b_fault="$(sed -n 's/.*FAULT_SSH_OFFSET=\([0-9]\+\).*/\1/p' "$AGENTS_LIB" | head -n1)"
  if [[ -z "$k_fault" || -z "$b_fault" || "$k_fault" != "$b_fault" ]]; then
    fail "fault-SSH offset disagrees (Kotlin='$k_fault' bash='$b_fault'); the two formulas would send the APK and the compose publish to different ports"
    return 1
  fi
  if [[ "$k_single_api" != "8474" ]]; then
    fail "Kotlin SINGLE_LANE_API_PORT is '$k_single_api', expected 8474"
    return 1
  fi
  # The API offset: Kotlin adds (agentsPort - SINGLE_LANE_AGENTS_PORT) to 8474;
  # bash must land on the same number for a real pool port.
  source_pool_lib
  local agents=2243 k_api b_api
  k_api=$(( 8474 + (agents - 2222) ))
  b_api="$(pocketshell_toxiproxy_api_port "$agents")"
  if [[ "$k_api" != "$b_api" ]]; then
    fail "toxiproxy API port disagrees for agents=$agents (Kotlin=$k_api bash=$b_api); the APK would talk to a different control API than compose published"
    return 1
  fi
  # Same for the fault SSH port the app actually dials.
  local k_ssh b_ssh
  k_ssh=$(( agents + k_fault ))
  b_ssh="$(pocketshell_network_fault_ssh_port "$agents")"
  if [[ "$k_ssh" != "$b_ssh" ]]; then
    fail "fault SSH port disagrees for agents=$agents (Kotlin=$k_ssh bash=$b_ssh)"
    return 1
  fi
  # The bash side ALSO publishes a packet-loss port that Kotlin has no consumer
  # for yet; it must still exist, or the compose publish loses a port.
  grep -q 'PACKET_LOSS_OFFSET=' "$AGENTS_LIB" \
    || { fail "scripts/lib/agents-pool.sh lost PACKET_LOSS_OFFSET; the compose packet-loss publish has no derivation"; return 1; }
  pass "Kotlin and bash agree for agents=$agents (fault +$k_fault -> $k_ssh, API -> $k_api)"
}

# --------------------------------------------------------------------------
# 10. THE DETECTOR MUST FIRE FOR THE LANE THAT ACTUALLY RUNS THE FAULT
#     JOURNEY. connected-test.sh brings the per-lane proxy up only when it
#     classifies the run as fault-class. It used to do that by matching the old
#     app module's per-class names; issue #2474 made app2's suite run UNFILTERED
#     in one process, so the whole-suite Gradle task is the thing to match, and
#     the class-name globs could never fire again.
#
#     Non-vacuous by construction: it first proves app2's instrumented set
#     really does drive Toxiproxy (>= 1 consumer), then requires the task that
#     runs that set to be classified fault-class.
# --------------------------------------------------------------------------
the_app2_journey_lane_is_detected_by_connected_test() {
  local consumers task
  consumers="$(grep -Rl 'ToxiproxyControl' "$APP2_ANDROID_TEST" --include='*.kt' 2>/dev/null | wc -l)"
  if (( consumers < 1 )); then
    fail "no app2 androidTest source drives ToxiproxyControl, so this detector check would pass vacuously — if the fault journey really is gone, delete this harness (D22) rather than leaving it green over nothing (issue #2128)"
    return 1
  fi
  [[ -f "$JOURNEY_SUITE" ]] || { fail "journey suite not found at $JOURNEY_SUITE; cannot read the task the lane runs"; return 1; }
  task="$(sed -nE 's/^JOURNEY_TASK="([^"]+)".*/\1/p' "$JOURNEY_SUITE" | head -n1)"
  if [[ -z "$task" ]]; then
    fail "$JOURNEY_SUITE has no JOURNEY_TASK assignment; the detector cannot be checked against the task that actually runs"
    return 1
  fi

  local patterns=() pat matched=0
  mapfile -t patterns < <(
    awk '/case "\$gradle_args_str" in/,/esac/' "$CONNECTED" \
      | grep -v 'case ' | grep -v 'esac' | grep -v 'NETWORK_FAULT' \
      | tr -d ' \t\\' | tr '|' '\n' | sed 's/)//g' | grep -E '^\*.+\*$' || true
  )
  if (( ${#patterns[@]} == 0 )); then
    fail "could not extract NETWORK_FAULT_RUN globs from connected-test.sh"
    return 1
  fi
  for pat in "${patterns[@]}"; do
    case "$task" in
      $pat) matched=1; break ;;
    esac
  done
  if (( matched == 0 )); then
    fail "connected-test.sh NETWORK_FAULT_RUN does not classify '$task' as a fault-class run, so a --pool run of app2's suite would derive isolated ports and never bring up the per-lane proxy — the reconnect journey would fail as if the product were broken (issue #2128 / #2474)"
    return 1
  fi
  pass "connected-test.sh classifies '$task' as fault-class ($consumers Toxiproxy consumer(s) in app2's instrumented set, ${#patterns[@]} globs)"
}

# --------------------------------------------------------------------------
# 12. docs/testing.md must state the --pool fault-class behaviour honestly.
# --------------------------------------------------------------------------
docs_state_the_fault_pool_behaviour() {
  if ! grep -q '2128' "$TESTING_MD"; then
    fail "docs/testing.md does not mention issue #2128, so the --pool / network-fault limitation (or its fix) is not documented (AC)"
    return 1
  fi
  pass "docs/testing.md documents the #2128 network-fault --pool behaviour"
}

# --------------------------------------------------------------------------

main() {
  printf 'network-fault pool isolation harness (issue #2128)\n'
  fault_ports_must_derive_from_the_agents_port || true
  no_unset_fallback_reintroduces_the_shared_pin || true
  toxiproxy_control_must_parameterise_listen_and_upstream || true
  pool_lane_must_not_resolve_to_the_shared_fault_ports || true
  single_lane_keeps_the_historical_fault_identity || true
  two_pool_lanes_get_disjoint_fault_ports || true
  compose_fault_proxy_is_parameterised || true
  connected_test_isolates_pool_fault_classes || true
  a_disturbed_fault_fixture_is_not_an_empty_session_list || true
  kotlin_and_bash_formulas_agree || true
  the_app2_journey_lane_is_detected_by_connected_test || true
  docs_state_the_fault_pool_behaviour || true

  if (( FAILURES > 0 )); then
    printf '\nnetwork-fault pool isolation: %s FAILING check(s)\n' "$FAILURES" >&2
    exit 1
  fi
  printf '\nnetwork-fault pool isolation: all checks passed\n'
}

main "$@"
