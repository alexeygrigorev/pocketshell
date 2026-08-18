#!/usr/bin/env bash

# Agents-fixture pool helpers (issue #724).
#
# The emulator-lane half of parallel journey testing shipped in #674
# (scripts/lib/avd-lock.sh `pocketshell_claim_pool_serial` claims a distinct
# free emulator per caller). This file is the DOCKER half: a pool of isolated
# deterministic `agents` SSH/tmux fixtures, each on its OWN host port, so two
# emulator lanes never share one container's tmux state.
#
# The `agents` service in tests/docker/docker-compose.yml now takes two env
# knobs (BOTH defaulted to the legacy single-lane values, so nothing changes
# when they are unset):
#   * AGENTS_HOST_PORT       host port to publish (default 2222)
#   * AGENTS_CONTAINER_NAME  daemon-global container name
#                            (default pocketshell-test-agents)
# Each lane runs under a distinct COMPOSE_PROJECT_NAME + per-port container name
# so concurrent `docker compose up` invocations are fully independent.
#
# A lane CLAIMS a port the same way the AVD pool claims a serial: a per-port
# flock held by a background process for the caller's lifetime, released on
# exit. The candidate ports default to 2243 2244 2245 (override via
# POCKETSHELL_AGENTS_POOL_PORTS).

# The AVD half owns the machine-wide lock anchor (issue #1657). Source it so a
# caller that only pulled in this file (scripts/agents-pool.sh) still resolves
# lock paths through the same anchor as connected-test.sh; anything else
# reintroduces the split-lock bug this file exists to fix.
if ! declare -F pocketshell_avd_lock_path >/dev/null 2>&1; then
  # shellcheck source=scripts/lib/avd-lock.sh
  source "${BASH_SOURCE[0]%/*}/avd-lock.sh"
fi

# --- configuration --------------------------------------------------------

# Candidate host ports, first-free wins. 2243-2245 sit clear of the existing
# fixture ports (sshd 2222, tmux 2224, flaky-agent 2226, network proxies
# 2228/2229, bootstrap 2230-2236, real-agent 2240).
#
# ---------------------------------------------------------------------------
# 2222 is NOT a pool candidate (issue #1842)
#
# It used to be first in this list, "so the single-lane default and CI fall back
# to the long-standing fixture port". That reasoning was wrong, and it is the
# second of the two ways a sibling reached a claimed lane's fixture:
#
# Port 2222 is the DEFAULT single-lane fixture — container
# `pocketshell-test-agents` on the default compose project. A dozen scripts that
# know nothing about the pool recreate it unconditionally, some with
# `--force-recreate` (capture-terminal-lab.sh, terminal-workbench.sh,
# phone-walkthrough.sh, pre-release-confidence-gate.sh, keyboard-stress.sh,
# release-terminal-gate.sh, ...). None of them takes the port lock, and they
# never can: they are the legacy single-lane path and D21/CI depend on them
# hitting 2222.
#
# So a lock — however correctly anchored — cannot defend 2222. A `--pool` lane
# that was handed 2222 held a lock nobody else consults while a sibling
# `--no-pool` run wiped its tmux server (`Up 25 seconds` mid-loop; #1819 det2,
# discarded as contaminated). The only fix that holds is to stop handing 2222
# out as a lane: pool lanes claim ONLY ports whose sole writer is the pool.
#
# This does not change `--no-pool` / CI behaviour. Those never call this
# function: `AgentsFixtureTarget` defaults `agentsPort` to 2222 and
# connected-test.sh only threads an override when a port was claimed. The
# invariant is pinned by `default_pool_candidates_exclude_unlocked_fixture_ports`
# in scripts/test-agents-pool-isolation.sh, which derives the unlocked set from
# the scripts themselves rather than hard-coding 2222.
# ---------------------------------------------------------------------------
pocketshell_agents_pool_ports() {
  printf '%s\n' "${POCKETSHELL_AGENTS_POOL_PORTS:-2243 2244 2245}"
}

# ---------------------------------------------------------------------------
# Compose identity resolvers — ONE definition each (issue #1842)
#
# A lane's fixture is identified by three things: the compose file, the compose
# project, and the container name. Each of those used to be open-coded at every
# site that needed it (`fixture_up`, `fixture_down`, `wait_healthy`, the
# fingerprint check, and `agents-pool.sh status`), with the port-2222 special
# case spelled out by hand each time.
#
# That is not a style nit here — it is the SHAPE of this issue's first root
# cause. RC1 survived precisely because `_pocketshell_agents_try_lock_port`
# rebuilt the lock path by hand instead of asking the resolver, so the claim and
# `agents-pool.sh status` could disagree about which file was the lock, and did.
# A second hand-written copy of "which container is port 2222" is the same trap
# one level over: the fingerprint could then watch a different container than the
# lifecycle helpers create. So every site routes through these three functions
# and nothing else spells the identity out.
# ---------------------------------------------------------------------------

pocketshell_agents_compose_file() {
  local root_dir="$1"
  printf '%s/tests/docker/docker-compose.yml\n' "$root_dir"
}

# The container name a lane on $port resolves to. Port 2222 keeps the legacy
# single-lane identity (unsuffixed container) so `docker inspect
# pocketshell-test-agents` still works for the non-pool tooling.
pocketshell_agents_container_for_port() {
  local port="$1"
  if [[ "$port" == "2222" ]]; then
    printf 'pocketshell-test-agents\n'
  else
    printf 'pocketshell-test-agents-%s\n' "$port"
  fi
}

# The compose project a lane on $port runs under. EMPTY for 2222 — the legacy
# single-lane fixture lives in the default project, and forcing it into
# `psagents2222` would orphan the container the non-pool scripts manage.
pocketshell_agents_project_for_port() {
  local port="$1"
  if [[ "$port" == "2222" ]]; then
    printf '\n'
  else
    printf 'psagents%s\n' "$port"
  fi
}

# The `env` prefix that pins a docker-compose invocation to $port's lane.
pocketshell_agents_compose_env_for_port() {
  local port="$1"
  local container project
  container="$(pocketshell_agents_container_for_port "$port")"
  project="$(pocketshell_agents_project_for_port "$port")"
  printf 'AGENTS_HOST_PORT=%s\n' "$port"
  printf 'AGENTS_CONTAINER_NAME=%s\n' "$container"
  if [[ -n "$project" ]]; then
    printf 'COMPOSE_PROJECT_NAME=%s\n' "$project"
  fi
}

# ---------------------------------------------------------------------------
# Network-fault / packet-loss / Toxiproxy host ports (issue #2128)
#
# NetworkFaultProofBase used to hardcode 2228/2229/8474, so a --pool lane
# that had claimed agents:2243 still attached through the shared
# pocketshell-test-network-fault-proxy (upstream agents:22 = the 2222
# fixture). Keep these offsets in lockstep with NetworkFaultPorts.kt
# (POOL_FAULT_SSH_OFFSET / POOL_PACKET_LOSS_OFFSET). Single-lane 2222 keeps
# the historical identity; every other agents port derives a disjoint triple.
# ---------------------------------------------------------------------------
FAULT_SSH_OFFSET=10
PACKET_LOSS_OFFSET=20

pocketshell_network_fault_ssh_port() {
  local agents_port="$1"
  if [[ "$agents_port" == "2222" ]]; then
    printf '2228\n'
  else
    printf '%s\n' "$((agents_port + FAULT_SSH_OFFSET))"
  fi
}

pocketshell_packet_loss_ssh_port() {
  local agents_port="$1"
  if [[ "$agents_port" == "2222" ]]; then
    printf '2229\n'
  else
    printf '%s\n' "$((agents_port + PACKET_LOSS_OFFSET))"
  fi
}

pocketshell_toxiproxy_api_port() {
  local agents_port="$1"
  if [[ "$agents_port" == "2222" ]]; then
    printf '8474\n'
  else
    printf '%s\n' "$((8474 + agents_port - 2222))"
  fi
}

pocketshell_network_fault_container_for_port() {
  local port="$1"
  if [[ "$port" == "2222" ]]; then
    printf 'pocketshell-test-network-fault-proxy\n'
  else
    printf 'pocketshell-test-network-fault-proxy-%s\n' "$port"
  fi
}

pocketshell_packet_loss_container_for_port() {
  local port="$1"
  if [[ "$port" == "2222" ]]; then
    printf 'pocketshell-test-packet-loss-proxy\n'
  else
    printf 'pocketshell-test-packet-loss-proxy-%s\n' "$port"
  fi
}

# Agents env + the per-lane fault-proxy publish/name overrides.
pocketshell_network_fault_compose_env_for_port() {
  local port="$1"
  pocketshell_agents_compose_env_for_port "$port"
  printf 'NETWORK_FAULT_HOST_PORT=%s\n' "$(pocketshell_network_fault_ssh_port "$port")"
  printf 'TOXIPROXY_API_HOST_PORT=%s\n' "$(pocketshell_toxiproxy_api_port "$port")"
  printf 'NETWORK_FAULT_CONTAINER_NAME=%s\n' "$(pocketshell_network_fault_container_for_port "$port")"
  printf 'PACKET_LOSS_HOST_PORT=%s\n' "$(pocketshell_packet_loss_ssh_port "$port")"
  printf 'PACKET_LOSS_CONTAINER_NAME=%s\n' "$(pocketshell_packet_loss_container_for_port "$port")"
}

# Bring up the per-lane network-fault + packet-loss proxies on $port's
# compose project (idempotent). The agents fixture must already be up —
# `depends_on: agents` will start it if not, but a pool claim already did.
pocketshell_network_fault_fixture_up() {
  local root_dir="$1"
  local port="$2"
  local compose_file container
  compose_file="$(pocketshell_agents_compose_file "$root_dir")"
  container="$(pocketshell_network_fault_container_for_port "$port")"

  local env_prefix=(env)
  local assignment
  while IFS= read -r assignment; do
    env_prefix+=("$assignment")
  done < <(pocketshell_network_fault_compose_env_for_port "$port")

  printf 'Bringing up network-fault fixtures for agents port %s (proxy %s on host %s / API %s)...\n' \
    "$port" "$container" \
    "$(pocketshell_network_fault_ssh_port "$port")" \
    "$(pocketshell_toxiproxy_api_port "$port")" >&2
  _pocketshell_agents_run_without_avd_lock_fd \
    "${env_prefix[@]}" docker compose -f "$compose_file" up -d --build \
    network-fault-proxy packet-loss-proxy >&2
}

# ---------------------------------------------------------------------------
# Per-port lock file — MACHINE-anchored, not checkout-anchored (issue #1842)
#
# This carried the exact defect #1657 found and fixed in the AVD half, and it
# survived there because the two halves were fixed in different issues: the path
# was `$root_dir/build/.agents-port-lock-$port`, i.e. derived from the WORKTREE
# root. `flock` on distinct files serialises nothing, so two `--pool` lanes
# driven from `.worktrees/issue-A` and `.worktrees/issue-B` each took their own
# private lock, both "won" the same candidate port, and the second lane's
# `docker compose up` recreated the first lane's container out from under it.
# That is #1820 round 3: the #1819 lane and the #1820 lane both held 2243 and
# `pocketshell-test-agents-2243` was observed `Up 7 seconds` mid-run.
#
# The resource being protected is a docker container published on a HOST port —
# a property of the machine, not of the clone driving it — so the lock must be
# machine-wide. We reuse #1657's anchor (`$HOME/.cache/pocketshell/avd-locks`,
# `/tmp/pocketshell-avd-locks-$(id -u)` for a HOME-less shell) rather than
# inventing a second one, so the two halves of a pool claim can never again
# drift apart: one anchor, fixed once.
#
# `$root_dir` is accepted for call-site compatibility and deliberately unused,
# mirroring `pocketshell_avd_lock_file_for_serial`.
#
# Stale lock FILES are harmless by construction: `flock` lives on an open FD, so
# the kernel releases it the moment the holder dies. Never convert this to a
# pidfile/lock-content scheme, which WOULD wedge on a crash.
# ---------------------------------------------------------------------------
pocketshell_agents_lock_file_for_port() {
  local _root_dir_unused="$1"
  local port="$2"
  local token="${port//[^A-Za-z0-9._-]/_}"
  if [[ -z "$token" ]]; then
    token="default"
  fi
  pocketshell_avd_lock_path "agents-port-lock-$token"
}

_pocketshell_agents_run_without_avd_lock_fd() {
  if declare -F pocketshell_run_without_avd_lock_fd >/dev/null 2>&1; then
    pocketshell_run_without_avd_lock_fd "$@"
  else
    "$@"
  fi
}

# --- fixture lifecycle ----------------------------------------------------

# Bring up ONE agents fixture lane on $port (idempotent). When port==2222 and
# no per-port overrides are requested, this reproduces the legacy single-lane
# fixture (container `pocketshell-test-agents`, project default) so existing
# tooling that inspects that container keeps working.
pocketshell_agents_fixture_up() {
  local root_dir="$1"
  local port="$2"
  local compose_file container
  compose_file="$(pocketshell_agents_compose_file "$root_dir")"
  container="$(pocketshell_agents_container_for_port "$port")"

  local env_prefix=(env)
  local assignment
  while IFS= read -r assignment; do
    env_prefix+=("$assignment")
  done < <(pocketshell_agents_compose_env_for_port "$port")

  printf 'Bringing up agents fixture lane on host port %s (container %s)...\n' \
    "$port" "$container" >&2
  _pocketshell_agents_run_without_avd_lock_fd \
    "${env_prefix[@]}" docker compose -f "$compose_file" up -d --build agents >&2
}

# Tear down ONE agents fixture lane on $port.
pocketshell_agents_fixture_down() {
  local root_dir="$1"
  local port="$2"
  local compose_file
  compose_file="$(pocketshell_agents_compose_file "$root_dir")"

  local env_prefix=(env)
  local assignment
  while IFS= read -r assignment; do
    env_prefix+=("$assignment")
  done < <(pocketshell_agents_compose_env_for_port "$port")

  printf 'Tearing down agents fixture lane on host port %s...\n' "$port" >&2
  _pocketshell_agents_run_without_avd_lock_fd \
    "${env_prefix[@]}" docker compose -f "$compose_file" down -v >&2 || true
}

# Wait until the agents fixture on $port answers SSH healthy. Polls the named
# container's compose health status, then falls back to a direct SSH probe.
pocketshell_agents_fixture_wait_healthy() {
  local root_dir="$1"
  local port="$2"
  local timeout_seconds="${3:-180}"
  local container
  container="$(pocketshell_agents_container_for_port "$port")"

  local deadline=$((SECONDS + timeout_seconds))
  local status_file="${TMPDIR:-/tmp}/pocketshell-agents-health.$$.$RANDOM"
  while (( SECONDS < deadline )); do
    local status=""
    : > "$status_file"
    _pocketshell_agents_run_without_avd_lock_fd \
      docker inspect --format='{{.State.Health.Status}}' "$container" \
      > "$status_file" 2>/dev/null || true
    IFS= read -r status < "$status_file" || true
    if [[ "$status" == "healthy" ]]; then
      _pocketshell_agents_run_without_avd_lock_fd rm -f "$status_file"
      printf 'agents fixture on port %s healthy (%s).\n' "$port" "$container" >&2
      return 0
    fi
    _pocketshell_agents_run_without_avd_lock_fd sleep 2
  done
  _pocketshell_agents_run_without_avd_lock_fd rm -f "$status_file"
  printf 'FAIL: agents fixture on port %s (%s) not healthy within %ss.\n' \
    "$port" "$container" "$timeout_seconds" >&2
  return 1
}

# --- claim enforcement (issue #1842) --------------------------------------
#
# The lock makes the claim EXCLUSIVE among lock-taking lanes. It cannot make the
# claim ENFORCEABLE: `docker` is a machine-wide daemon and any process — a
# sibling script, a stray `docker compose down`, a human — can recreate a
# container without consulting any flock. Locks are advisory; the fixture is
# shared state.
#
# That gap is what made this bug expensive rather than merely annoying. A
# recreated fixture presents to the test as an EMPTY SESSION LIST, which is
# byte-for-byte the signature of two real product defects under investigation at
# the time (#1810 `list-sessions` resolving empty-and-non-error, #1820 the picker
# publishing Ready with a session omitted). Both affected lanes only avoided
# chasing a phantom product bug because they had already characterised the real
# one and could tell the signatures apart. A lane without that context loses a
# round.
#
# So the claim is made VERIFIABLE: we fingerprint the container at claim time and
# re-check it at the end of the run. A container's `.Id` changes when it is
# recreated and `.State.StartedAt` changes when it is restarted, so the pair
# catches both `up --force-recreate` and a plain `restart`.

# Fingerprint the fixture container backing $port. Prints "<id> <startedAt>".
# Prints an empty-ish sentinel (never a hard failure) when docker cannot answer,
# so a lane is never blocked by an inspect hiccup.
pocketshell_agents_fixture_identity() {
  local port="$1"
  local container
  container="$(pocketshell_agents_container_for_port "$port")"
  local out=""
  out="$(_pocketshell_agents_run_without_avd_lock_fd \
    docker inspect --format='{{.Id}} {{.State.StartedAt}}' "$container" 2>/dev/null)" || out=""
  # Collapse to one line; an unreadable container yields the literal "unknown"
  # so a missing fingerprint is distinguishable from a changed one.
  out="${out%%$'\n'*}"
  if [[ -z "$out" ]]; then
    out="unknown"
  fi
  printf '%s\n' "$out"
}

# Record the claimed fixture's fingerprint for later verification.
pocketshell_agents_record_fixture_identity() {
  local port="$1"
  POCKETSHELL_AGENTS_FIXTURE_IDENTITY="$(pocketshell_agents_fixture_identity "$port")"
  export POCKETSHELL_AGENTS_FIXTURE_IDENTITY
}

# Verify the claimed fixture was not recreated/restarted under this lane.
#
# Returns 0 when the fixture is intact (or when there is nothing to compare).
# Returns 1 AND prints a deliberately unmistakable multi-line banner otherwise.
# The banner exists to be read by a human at 2am who is looking at an empty
# session list: it must NOT be quiet, and it must name the collision explicitly
# so the reader never attributes it to the product.
#
# shellcheck disable=SC2120  # $1 is optional; the harness passes it explicitly.
pocketshell_agents_assert_fixture_undisturbed() {
  local port="${1:-${POCKETSHELL_AGENTS_PORT:-}}"
  local expected="${POCKETSHELL_AGENTS_FIXTURE_IDENTITY:-}"
  if [[ -z "$port" || -z "$expected" || "$expected" == "unknown" ]]; then
    return 0
  fi
  local actual
  actual="$(pocketshell_agents_fixture_identity "$port")"
  if [[ "$actual" == "$expected" ]]; then
    return 0
  fi
  local container
  container="$(pocketshell_agents_container_for_port "$port")"
  cat >&2 <<BANNER
=====================================================================
FAIL: AGENTS FIXTURE DISTURBED MID-RUN (pool isolation violated)

  port        : $port
  container   : $container
  at claim    : $expected
  now         : $actual

Another process recreated or restarted THIS LANE's fixture container
while the run was in progress. The tmux server this run was asserting
against no longer exists.

DO NOT read this run's result as a product signal. In particular, an
empty session list / "got []" / a missing session in the picker is
EXPECTED after the fixture is wiped and is NOT evidence of a product
defect (it mimics #1810 and #1820 exactly). Any PASS is equally void.

Find the other writer before re-running: 'docker ps' will show this
container with a short uptime. Issue #1842.
=====================================================================
BANNER
  return 1
}

# Distinct exit code for "the fixture moved under us". Deliberately not 1: a
# caller (or a human scanning a log) can tell a disturbed lane from an ordinary
# test failure without parsing text.
POCKETSHELL_AGENTS_DISTURBED_RC=90

# Issue #2128: fingerprint the per-lane network-fault-proxy the same way.
# A wiped toxiproxy presents as ATTACH death / empty session list just like
# a wiped agents fixture — the app attaches through 2228-equivalent onto a
# proxy that no longer forwards to the seeded tmux. Same inoculation.
pocketshell_network_fault_fixture_identity() {
  local port="$1"
  local container
  container="$(pocketshell_network_fault_container_for_port "$port")"
  local out=""
  out="$(_pocketshell_agents_run_without_avd_lock_fd \
    docker inspect --format='{{.Id}} {{.State.StartedAt}}' "$container" 2>/dev/null)" || out=""
  out="${out%%$'\n'*}"
  if [[ -z "$out" ]]; then
    out="unknown"
  fi
  printf '%s\n' "$out"
}

pocketshell_network_fault_record_fixture_identity() {
  local port="$1"
  POCKETSHELL_NETWORK_FAULT_FIXTURE_IDENTITY="$(pocketshell_network_fault_fixture_identity "$port")"
  export POCKETSHELL_NETWORK_FAULT_FIXTURE_IDENTITY
}

pocketshell_network_fault_assert_fixture_undisturbed() {
  local port="${1:-${POCKETSHELL_AGENTS_PORT:-}}"
  local expected="${POCKETSHELL_NETWORK_FAULT_FIXTURE_IDENTITY:-}"
  if [[ -z "$port" || -z "$expected" || "$expected" == "unknown" ]]; then
    return 0
  fi
  local actual
  actual="$(pocketshell_network_fault_fixture_identity "$port")"
  if [[ "$actual" == "$expected" ]]; then
    return 0
  fi
  local container
  container="$(pocketshell_network_fault_container_for_port "$port")"
  cat >&2 <<BANNER
=====================================================================
FAIL: NETWORK-FAULT FIXTURE DISTURBED MID-RUN (pool isolation violated)

  agents port : $port
  container   : $container
  at claim    : $expected
  now         : $actual

Another process recreated or restarted THIS LANE's network-fault-proxy
while the run was in progress. The Toxiproxy this run was asserting
against no longer exists (or no longer forwards to the seeded tmux).

DO NOT read this run's result as a product signal. In particular, an
empty session list / "got []" / ATTACH death / a missing session in
the picker is EXPECTED after the fixture is wiped and is NOT evidence of a product
defect (it mimics #1810 and #1820 exactly). Any PASS is equally void.

Find the other writer before re-running: 'docker ps' will show this
container with a short uptime. Issue #2128.
=====================================================================
BANNER
  return 1
}

# Fold the fixture check into a run's final exit code.
#
# Takes the gradle/test exit code, returns the code the wrapper should exit with.
# A disturbed lane overrides BOTH directions — see the banner above for why a
# disturbed PASS is as worthless as a disturbed FAIL.
pocketshell_agents_final_rc() {
  local gradle_rc="$1"
  local disturbed=0
  pocketshell_agents_assert_fixture_undisturbed || disturbed=1
  pocketshell_network_fault_assert_fixture_undisturbed || disturbed=1
  if (( disturbed == 0 )); then
    printf '%s\n' "$gradle_rc"
    return 0
  fi
  printf 'AGENTS_FIXTURE_DISTURBED=1 GRADLE_RC=%s (verdict overridden; see banner above)\n' \
    "$gradle_rc" >&2
  printf '%s\n' "$POCKETSHELL_AGENTS_DISTURBED_RC"
  return 0
}

# --- port claim/release (mirrors the per-serial AVD claim in avd-lock.sh) ---

_pocketshell_hold_agents_port_lock() {
  local lock_file="$1"
  local ready_file="$2"
  exec >/dev/null 2>/dev/null
  if ! exec 9>"$lock_file"; then
    exit 1
  fi
  if ! flock -n 9; then
    exit 1
  fi
  printf 'ready\n' > "$ready_file"
  local sleep_pid=""
  trap '[[ -n "$sleep_pid" ]] && kill "$sleep_pid" 2>/dev/null || true; exit 0' HUP INT TERM
  while :; do
    sleep 3600 9>&- &
    sleep_pid="$!"
    wait "$sleep_pid" || true
  done
}

# Try a non-blocking flock on a port's lock file. Echoes the holder PID on
# success; returns non-zero if a sibling holds it.
_pocketshell_agents_try_lock_port() {
  local root_dir="$1"
  local port="$2"
  # Route through the single resolver. This site used to build the
  # worktree-relative path a SECOND time by hand, which is why the defect
  # survived: `pocketshell_agents_lock_file_for_port` was only ever consulted by
  # `agents-pool.sh status`, so the claim path and the status readout could (and
  # did) disagree about which file is the lock (issue #1842).
  local lock_file
  lock_file="$(pocketshell_agents_lock_file_for_port "$root_dir" "$port")"
  _pocketshell_agents_run_without_avd_lock_fd mkdir -p "$(dirname "$lock_file")"

  local state_dir="${TMPDIR:-/tmp}/pocketshell-agents-claim.$$.$RANDOM"
  _pocketshell_agents_run_without_avd_lock_fd mkdir -p "$state_dir"
  local ready_file="$state_dir/ready"
  POCKETSHELL_AGENTS_TRY_HOLDER_PID=""

  if [[ "${POCKETSHELL_AVD_LOCK_CONTINUOUS_ACQUIRED:-}" == "1" \
        && "${POCKETSHELL_AVD_LOCK_FD:-}" =~ ^[0-9]+$ ]]; then
    _pocketshell_hold_agents_port_lock "$lock_file" "$ready_file" \
      {POCKETSHELL_AVD_LOCK_FD}>&- &
  else
    _pocketshell_hold_agents_port_lock "$lock_file" "$ready_file" &
  fi
  local holder_pid="$!"

  local waited=0
  while [[ ! -e "$ready_file" ]]; do
    if ! kill -0 "$holder_pid" 2>/dev/null; then
      _pocketshell_agents_run_without_avd_lock_fd rm -rf "$state_dir"
      wait "$holder_pid" 2>/dev/null || true
      return 1
    fi
    _pocketshell_agents_run_without_avd_lock_fd sleep 0.05
    waited=$((waited + 1))
    if (( waited > 100 )); then
      kill "$holder_pid" 2>/dev/null || true
      wait "$holder_pid" 2>/dev/null || true
      _pocketshell_agents_run_without_avd_lock_fd rm -rf "$state_dir"
      return 1
    fi
  done

  _pocketshell_agents_run_without_avd_lock_fd rm -rf "$state_dir"
  POCKETSHELL_AGENTS_TRY_HOLDER_PID="$holder_pid"
  return 0
}

# Claim the first free agents port from the candidate list, bring its fixture
# up + healthy, and export POCKETSHELL_AGENTS_PORT for the caller. Installs an
# EXIT trap to release the flock on the way out (the container is left running
# warm for the next lane — teardown is a separate explicit `down`).
#
# On success:
#   * exports POCKETSHELL_AGENTS_PORT=<claimed port>
#   * exports POCKETSHELL_AGENTS_HOLDER_PID / POCKETSHELL_AGENTS_OWNER_PID
#   * registers pocketshell_release_all (shared with the AVD release) on EXIT
# Returns non-zero if no port could be claimed / brought healthy.
#
# Optional env:
#   POCKETSHELL_AGENTS_POOL_PORTS  whitespace-separated candidate ports
#   POCKETSHELL_AGENTS_WAIT_SECONDS how long to retry when all are busy (default 600)
#
# ---------------------------------------------------------------------------
# THIS FUNCTION CANNOT BE CALLED IN A SUBSHELL, AND IT NOW REFUSES TO BE
# (issue #1842)
#
# A claim is not a value; it is a mutation of the CALLING shell — three exports
# plus an EXIT trap that holds the flock for that shell's lifetime. Written in a
# subshell (`port="$(pocketshell_claim_agents_port ...)"`, a backtick, a
# pipeline, `( ... )`) all of that lands in a process that dies immediately:
#
#   * `$$` inside a command substitution is STILL THE PARENT'S PID, so the
#     subshell's EXIT trap passes the owner check in
#     `pocketshell_release_agents_port` and releases the claim the instant the
#     substitution closes;
#   * the exports never reach the caller.
#
# The caller is then left holding a plausible-looking port number, NO LOCK, and
# an unset $POCKETSHELL_AGENTS_PORT. That single mistake silently restores all
# three root causes this issue fixed at once: no lock (RC1); connected-test.sh
# gates the `agentsPort` gradle arg on $POCKETSHELL_AGENTS_PORT being set, so
# the lane silently falls back to the indefensible 2222 (RC2); and the
# fixture-fingerprint guard returns early with no identity recorded, so a
# disturbed lane goes quiet again (RC3). The reviewer of round 1 mutated the
# production caller into exactly this shape and every check still passed.
#
# So the shape is fixed rather than documented (D22 — hard cut, no footgun kept
# for compatibility):
#
#   1. The port is NO LONGER printed to stdout. It was, which is what invited
#      the capture in the first place: a function that prints its result reads
#      like a function you may capture. Read $POCKETSHELL_AGENTS_PORT instead;
#      the human-readable line still goes to stderr with the rest of the log.
#   2. A subshell call is DETECTED and REFUSED below, loudly, instead of
#      half-succeeding. `$BASHPID` is the real current-process PID (unlike `$$`,
#      which command substitution leaves pointing at the parent), so the two
#      disagree exactly when we are somewhere a claim cannot survive.
#   3. `pocketshell_release_agents_port` keys its owner check on $BASHPID for
#      the same reason, so a subshell can no longer release a claim it does not
#      own even if one is somehow made.
#
# The fail-closed direction is deliberate: if $BASHPID is somehow unset we
# cannot prove we are in the main shell, and a claim we cannot enforce is worse
# than no claim. Pinned by `a_subshell_claim_is_refused_loudly` and, statically
# across every production caller, by
# `no_production_caller_captures_the_claim_in_a_subshell`.
# ---------------------------------------------------------------------------
pocketshell_claim_agents_port() {
  if [[ "${BASHPID:-}" != "$$" ]]; then
    cat >&2 <<'BANNER'
=====================================================================
FAIL: THE AGENTS-PORT CLAIM WAS CALLED IN A SUBSHELL (issue #1842)

A claim mutates the CALLING shell (exports + an EXIT trap holding the
flock). In a subshell -- command substitution "$(...)", backticks, a
pipeline, or an explicit ( ... ) -- that shell dies immediately, so the
claim would be released before you could use it while LOOKING like it
succeeded. That silently restores every failure mode issue #1842 fixed:
no lock at all, a lane falling back to the indefensible port 2222, and
a disturbed fixture going undetected.

Call it directly and read the result from $POCKETSHELL_AGENTS_PORT:

    if ! pocketshell_claim_agents_port "$ROOT_DIR"; then ... ; fi
    echo "claimed $POCKETSHELL_AGENTS_PORT"

Refusing to claim. No lock was taken and no fixture was started.
=====================================================================
BANNER
    return 2
  fi

  local root_dir="$1"
  local wait_seconds="${POCKETSHELL_AGENTS_WAIT_SECONDS:-600}"
  local deadline=$((SECONDS + wait_seconds))

  # Single source of truth for the candidate list — this used to duplicate the
  # default inline, so a change to `pocketshell_agents_pool_ports` would not have
  # reached the actual claim (issue #1842).
  local ports
  ports="$(pocketshell_agents_pool_ports)"

  while :; do
    local port
    for port in $ports; do
      local holder_pid
      if _pocketshell_agents_try_lock_port "$root_dir" "$port"; then
        holder_pid="$POCKETSHELL_AGENTS_TRY_HOLDER_PID"
        # Won the port lock. Bring its fixture up + wait healthy; if that
        # fails, release the lock and try the next candidate.
        if ! pocketshell_agents_fixture_up "$root_dir" "$port" \
          || ! pocketshell_agents_fixture_wait_healthy "$root_dir" "$port"; then
          kill "$holder_pid" 2>/dev/null || true
          wait "$holder_pid" 2>/dev/null || true
          printf 'agents fixture on port %s failed to come up healthy; trying next port.\n' \
            "$port" >&2
          continue
        fi
        export POCKETSHELL_AGENTS_PORT="$port"
        export POCKETSHELL_AGENTS_HOLDER_PID="$holder_pid"
        # $BASHPID, matching the release-side check. The subshell guard above
        # already proved $BASHPID == $$ here; recording the same variable both
        # sides use keeps the pair from drifting apart later.
        export POCKETSHELL_AGENTS_OWNER_PID="$BASHPID"
        # Fingerprint the container we just claimed so the end of the run can
        # prove nobody recreated it underneath us (issue #1842).
        pocketshell_agents_record_fixture_identity "$port"
        if command -v pocketshell_release_all >/dev/null 2>&1; then
          trap pocketshell_release_all EXIT
        else
          trap pocketshell_release_agents_port EXIT
        fi
        # Deliberately stderr-only, and deliberately NOT also printed to stdout:
        # a claim has no return value to capture (see the banner above).
        printf 'Claimed agents fixture port: %s\n' "$port" >&2
        return 0
      fi
    done

    if (( SECONDS >= deadline )); then
      printf 'FAIL: no free agents fixture port (candidates: %s)\n' "$ports" >&2
      return 1
    fi
    printf 'All agents fixture ports busy; waiting for a free one...\n' >&2
    _pocketshell_agents_run_without_avd_lock_fd sleep 3
  done
}

pocketshell_release_agents_port() {
  # Keyed on $BASHPID, not $$: inside a command substitution `$$` still reports
  # the PARENT's PID, so a `$$`-keyed check passes in a subshell that owns
  # nothing and tears down the real shell's claim on its way out. That is the
  # exact mechanism of the #1842 footgun; $BASHPID is the true current PID, so a
  # subshell now fails the check and leaves the owner's claim alone.
  if [[ "${POCKETSHELL_AGENTS_OWNER_PID:-}" != "${BASHPID:-}" ]]; then
    return 0
  fi
  local holder_pid="${POCKETSHELL_AGENTS_HOLDER_PID:-}"
  if [[ -n "$holder_pid" ]]; then
    kill "$holder_pid" 2>/dev/null || true
    wait "$holder_pid" 2>/dev/null || true
  fi
  unset POCKETSHELL_AGENTS_HOLDER_PID
  unset POCKETSHELL_AGENTS_OWNER_PID
  unset POCKETSHELL_AGENTS_PORT
  unset POCKETSHELL_AGENTS_FIXTURE_IDENTITY
  unset POCKETSHELL_NETWORK_FAULT_FIXTURE_IDENTITY
}
