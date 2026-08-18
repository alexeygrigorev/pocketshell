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

PROOF_BASE="$ROOT_DIR/app/src/androidTest/java/com/pocketshell/app/proof/NetworkFaultProofBase.kt"
TOXICONTROL="$ROOT_DIR/app/src/debug/java/com/pocketshell/app/proof/ToxiproxyControl.kt"
PORTS_KT="$ROOT_DIR/app/src/debug/java/com/pocketshell/app/proof/NetworkFaultPorts.kt"
PORTS_TEST="$ROOT_DIR/app/src/testDebug/java/com/pocketshell/app/proof/NetworkFaultPortsTest.kt"
COMPOSE="$ROOT_DIR/tests/docker/docker-compose.yml"
AGENTS_LIB="$ROOT_DIR/scripts/lib/agents-pool.sh"
CONNECTED="$ROOT_DIR/scripts/connected-test.sh"
TESTING_MD="$ROOT_DIR/docs/testing.md"
OUTBOUND="$ROOT_DIR/app/src/androidTest/java/com/pocketshell/app/proof/OutboundAttachmentOffsetResumeJourneyE2eTest.kt"
CONV_RTT="$ROOT_DIR/app/src/androidTest/java/com/pocketshell/app/tmux/ConversationOpenLatencyRttDockerTest.kt"

# --------------------------------------------------------------------------
# 1. THE BUG (red on base): NetworkFaultProofBase must not pin 2228/2229/8474
#    as compile-time constants. A const 2228 is what made --pool a lie for
#    every fault-class lane: agentsPort=2243 was honoured for the unproxied
#    seed and then thrown away when the app attached through hardcoded 2228.
# --------------------------------------------------------------------------
network_fault_base_must_not_hardcode_shared_ports() {
  local pin
  pin="$(grep -n 'const val NETWORK_FAULT_SSH_PORT: Int = 2228' "$PROOF_BASE" || true)"
  if [[ -n "$pin" ]]; then
    fail "NetworkFaultProofBase hardcodes NETWORK_FAULT_SSH_PORT=2228, so a --pool lane stays pinned to the shared toxiproxy fixture regardless of the allocated agents port (issue #2128): $pin"
    return 1
  fi
  pin="$(grep -n 'const val PACKET_LOSS_SSH_PORT: Int = 2229' "$PROOF_BASE" || true)"
  if [[ -n "$pin" ]]; then
    fail "NetworkFaultProofBase hardcodes PACKET_LOSS_SSH_PORT=2229 (same class of pin as 2228): $pin"
    return 1
  fi
  pin="$(grep -n 'const val TOXIPROXY_API_PORT: Int = 8474' "$PROOF_BASE" || true)"
  if [[ -n "$pin" ]]; then
    fail "NetworkFaultProofBase hardcodes TOXIPROXY_API_PORT=8474 (same class of pin as 2228): $pin"
    return 1
  fi
  # The runtime ports must come from the shared resolver, not a leftover local
  # literal. A getter that still returns 2228 for every agentsPort is the same
  # bug wearing a different hat — pinned by the formula checks below.
  if ! grep -q 'NetworkFaultPorts' "$PROOF_BASE"; then
    fail "NetworkFaultProofBase does not consult NetworkFaultPorts, so even without a const 2228 it cannot honour a pool-allocated agents port (issue #2128)"
    return 1
  fi
  pass "NetworkFaultProofBase does not hardcode 2228/2229/8474; runtime ports go through NetworkFaultPorts"
}

# --------------------------------------------------------------------------
# 2. Independent copies of the same pin. OutboundAttachmentOffsetResume and
#    ConversationOpenLatencyRtt keep their own `= 2228` constants; fixing only
#    the base leaves those fault-class lanes on the shared fixture.
# --------------------------------------------------------------------------
independent_fault_classes_must_not_keep_their_own_pin() {
  local file pin
  for file in "$OUTBOUND" "$CONV_RTT"; do
    pin="$(grep -n 'NETWORK_FAULT_SSH_PORT.*= 2228' "$file" || true)"
    if [[ -n "$pin" ]]; then
      fail "$(basename "$file") keeps its own NETWORK_FAULT_SSH_PORT=2228 pin, so a --pool run of that class still hits the shared fixture (issue #2128): $pin"
      return 1
    fi
    pin="$(grep -n 'TOXIPROXY_API_PORT.*= 8474' "$file" || true)"
    if [[ -n "$pin" ]]; then
      fail "$(basename "$file") keeps its own TOXIPROXY_API_PORT=8474 pin (issue #2128): $pin"
      return 1
    fi
  done
  pass "independent fault-class copies no longer hardcode 2228/8474"
}

# --------------------------------------------------------------------------
# 3. ToxiproxyControl.createProxy must take listen/upstream from parameters,
#    not a single hardcoded POST body. Per-lane toxiproxy still uses
#    agents:22 *inside its own compose project*, but the control object must
#    not be the thing that re-pins a pool lane onto the shared proxy.
# --------------------------------------------------------------------------
toxiproxy_control_must_parameterise_listen_and_upstream() {
  if [[ ! -f "$PORTS_KT" ]]; then
    fail "NetworkFaultPorts.kt is missing; ToxiproxyControl has no place to take listen/upstream from besides the hardcoded 0.0.0.0:2228 / agents:22 POST (issue #2128)"
    return 1
  fi
  if ! grep -q 'listen' "$TOXICONTROL" || ! grep -q 'upstream' "$TOXICONTROL"; then
    fail "ToxiproxyControl does not expose listen/upstream parameters (issue #2128)"
    return 1
  fi
  # The POST body must interpolate, not embed the shared-fixture literals as
  # the only path. A leftover """...0.0.0.0:2228...agents:22...""" in
  # createProxy is the pin.
  if grep -n 'createProxy' -A 20 "$TOXICONTROL" | grep -q '0.0.0.0:2228'; then
    fail "ToxiproxyControl.createProxy still embeds listen 0.0.0.0:2228 in the POST body, so a pool lane cannot claim its own proxy (issue #2128)"
    return 1
  fi
  if grep -n 'createProxy' -A 20 "$TOXICONTROL" | grep -q 'agents:22'; then
    fail "ToxiproxyControl.createProxy still embeds upstream agents:22 in the POST body (issue #2128)"
    return 1
  fi
  pass "ToxiproxyControl.createProxy parameterises listen and upstream"
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
# 5. Hard-cut (D22): no "use 2228 if unset" leftover that reintroduces the
#    pin as the default for a pool lane. The Kotlin resolver must derive from
#    agentsPort; a `?: 2228` / `?: 8474` on the fault path is the bug.
# --------------------------------------------------------------------------
no_unset_fallback_reintroduces_the_shared_pin() {
  if [[ ! -f "$PORTS_KT" ]]; then
    fail "NetworkFaultPorts.kt is missing, so the only runtime ports are the hardcoded 2228/8474 defaults (issue #2128)"
    return 1
  fi
  local hits
  hits="$(grep -n -E '\?:[[:space:]]*2228|\?:[[:space:]]*2229|\?:[[:space:]]*8474|else[[:space:]]+2228|else[[:space:]]+8474' "$PORTS_KT" "$PROOF_BASE" \
    | grep -v ':[[:space:]]*//' \
    | grep -v ':[[:space:]]*\*' \
    || true)"
  if [[ -n "$hits" ]]; then
    fail "a 'use 2228/8474 if unset' fallback is still on the fault path; a --pool lane that only sets agentsPort would silently re-pin (issue #2128 / D22): $hits"
    return 1
  fi
  pass "no unset-fallback reintroduces 2228/8474 as the pool-lane default"
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
  if [[ ! -f "$PORTS_KT" ]]; then
    fail "NetworkFaultPorts.kt is missing; there is no Kotlin formula to agree with bash (issue #2128)"
    return 1
  fi
  if ! grep -q 'pocketshell_network_fault_ssh_port' "$AGENTS_LIB"; then
    fail "bash derivation is missing; cannot prove it matches Kotlin"
    return 1
  fi
  local k_fault k_loss k_api
  k_fault="$(sed -n 's/.*POOL_FAULT_SSH_OFFSET[^0-9]*\([0-9]\+\).*/\1/p' "$PORTS_KT" | head -n1)"
  k_loss="$(sed -n 's/.*POOL_PACKET_LOSS_OFFSET[^0-9]*\([0-9]\+\).*/\1/p' "$PORTS_KT" | head -n1)"
  k_api="$(sed -n 's/.*SINGLE_LANE_TOXIPROXY_API_PORT[^0-9]*\([0-9]\+\).*/\1/p' "$PORTS_KT" | head -n1)"
  local b_fault b_loss
  b_fault="$(sed -n 's/.*FAULT_SSH_OFFSET=\([0-9]\+\).*/\1/p' "$AGENTS_LIB" | head -n1)"
  b_loss="$(sed -n 's/.*PACKET_LOSS_OFFSET=\([0-9]\+\).*/\1/p' "$AGENTS_LIB" | head -n1)"
  if [[ -z "$k_fault" || -z "$b_fault" || "$k_fault" != "$b_fault" ]]; then
    fail "fault-SSH offset disagrees (Kotlin='$k_fault' bash='$b_fault'); the two formulas would send the APK and the compose publish to different ports"
    return 1
  fi
  if [[ -z "$k_loss" || -z "$b_loss" || "$k_loss" != "$b_loss" ]]; then
    fail "packet-loss offset disagrees (Kotlin='$k_loss' bash='$b_loss')"
    return 1
  fi
  if [[ "$k_api" != "8474" ]]; then
    fail "Kotlin SINGLE_LANE_TOXIPROXY_API_PORT is '$k_api', expected 8474"
    return 1
  fi
  pass "Kotlin and bash fault-port offsets agree (fault +$k_fault, packet-loss +$k_loss)"
}

# --------------------------------------------------------------------------
# 10. The JVM unit test that pins the formula must exist and be wired into
#     the debug unit suite (it lives next to ToxiproxyControlTest).
# --------------------------------------------------------------------------
jvm_formula_test_exists() {
  if [[ ! -f "$PORTS_TEST" ]]; then
    fail "NetworkFaultPortsTest.kt is missing; the 2243-must-not-be-2228 property has no JVM gate (issue #2128)"
    return 1
  fi
  if ! grep -q '2243' "$PORTS_TEST" || ! grep -q '2228' "$PORTS_TEST"; then
    fail "NetworkFaultPortsTest.kt does not assert that agentsPort=2243 must not resolve to 2228 (issue #2128)"
    return 1
  fi
  pass "NetworkFaultPortsTest.kt pins the pool-lane-is-not-2228 property"
}

# --------------------------------------------------------------------------
# 11. Every NetworkFaultProofBase subclass (and the two independent
#     Toxiproxy journeys that now use NetworkFaultPorts) must be matched
#     by connected-test.sh's NETWORK_FAULT_RUN detector. A missed class
#     under --pool would derive 2253/8495 and find no proxy — a new hole
#     introduced by removing the 2228 pin.
# --------------------------------------------------------------------------
every_fault_class_is_detected_by_connected_test() {
  local classes missing="" name pat matched
  mapfile -t classes < <(
    grep -Rlh ': NetworkFaultProofBase(' \
      "$ROOT_DIR/app/src/androidTest" --include='*Test.kt' \
      | while IFS= read -r f; do basename "$f" .kt; done
    printf '%s\n' \
      OutboundAttachmentOffsetResumeJourneyE2eTest \
      ConversationOpenLatencyRttDockerTest
  )
  if (( ${#classes[@]} < 10 )); then
    fail "expected to discover NetworkFaultProofBase subclasses; found ${#classes[@]} — detector scan would pass vacuously"
    return 1
  fi

  local patterns=()
  mapfile -t patterns < <(
    awk '/case "\$gradle_args_str" in/,/esac/' "$CONNECTED" \
      | grep -v 'case ' | grep -v 'esac' | grep -v 'NETWORK_FAULT' \
      | tr -d ' \t\\' | tr '|' '\n' | sed 's/)//g' | grep -E '^\*.+\*$' || true
  )
  if (( ${#patterns[@]} == 0 )); then
    fail "could not extract NETWORK_FAULT_RUN globs from connected-test.sh"
    return 1
  fi

  for name in "${classes[@]}"; do
    [[ -n "$name" ]] || continue
    matched=0
    for pat in "${patterns[@]}"; do
      case "$name" in
        $pat) matched=1; break ;;
      esac
    done
    if (( matched == 0 )); then
      missing+="  $name"$'\n'
    fi
  done
  if [[ -n "$missing" ]]; then
    fail "connected-test.sh NETWORK_FAULT_RUN does not match these fault-class names, so a --pool run would derive isolated ports and never bring up the per-lane proxy (issue #2128):
$missing"
    return 1
  fi
  pass "every fault-class name is matched by connected-test.sh NETWORK_FAULT_RUN (${#classes[@]} classes, ${#patterns[@]} globs)"
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
  network_fault_base_must_not_hardcode_shared_ports || true
  independent_fault_classes_must_not_keep_their_own_pin || true
  toxiproxy_control_must_parameterise_listen_and_upstream || true
  pool_lane_must_not_resolve_to_the_shared_fault_ports || true
  single_lane_keeps_the_historical_fault_identity || true
  two_pool_lanes_get_disjoint_fault_ports || true
  no_unset_fallback_reintroduces_the_shared_pin || true
  compose_fault_proxy_is_parameterised || true
  connected_test_isolates_pool_fault_classes || true
  a_disturbed_fault_fixture_is_not_an_empty_session_list || true
  kotlin_and_bash_formulas_agree || true
  jvm_formula_test_exists || true
  every_fault_class_is_detected_by_connected_test || true
  docs_state_the_fault_pool_behaviour || true

  if (( FAILURES > 0 )); then
    printf '\nnetwork-fault pool isolation: %s FAILING check(s)\n' "$FAILURES" >&2
    exit 1
  fi
  printf '\nnetwork-fault pool isolation: all checks passed\n'
}

main "$@"
