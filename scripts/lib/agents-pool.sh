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
# exit. The candidate ports default to 2222 2243 2244 2245 (override via
# POCKETSHELL_AGENTS_POOL_PORTS). 2222 is first so the single-lane default and
# CI fall back to the long-standing fixture port.

# --- configuration --------------------------------------------------------

# Candidate host ports, first-free wins. 2222 first preserves the single-lane
# default. 2243-2245 sit clear of the existing fixture ports (sshd 2222,
# tmux 2224, flaky-agent 2226, network proxies 2228/2229, bootstrap 2230-2236,
# real-agent 2240).
pocketshell_agents_pool_ports() {
  printf '%s\n' "${POCKETSHELL_AGENTS_POOL_PORTS:-2222 2243 2244 2245}"
}

pocketshell_agents_compose_file() {
  local root_dir="$1"
  printf '%s/tests/docker/docker-compose.yml\n' "$root_dir"
}

pocketshell_agents_container_name_for_port() {
  printf 'pocketshell-test-agents-%s\n' "$1"
}

pocketshell_agents_project_name_for_port() {
  printf 'psagents%s\n' "$1"
}

# Per-port lock file (parallel to pocketshell_avd_lock_file_for_serial).
pocketshell_agents_lock_file_for_port() {
  local root_dir="$1"
  local port="$2"
  printf '%s/build/.agents-port-lock-%s\n' "$root_dir" "$port"
}

# --- fixture lifecycle ----------------------------------------------------

# Bring up ONE agents fixture lane on $port (idempotent). When port==2222 and
# no per-port overrides are requested, this reproduces the legacy single-lane
# fixture (container `pocketshell-test-agents`, project default) so existing
# tooling that inspects that container keeps working.
pocketshell_agents_fixture_up() {
  local root_dir="$1"
  local port="$2"
  local compose_file
  compose_file="$(pocketshell_agents_compose_file "$root_dir")"

  local project container
  if [[ "$port" == "2222" ]]; then
    # Legacy single-lane identity: default project + legacy container name so
    # `docker inspect pocketshell-test-agents` still resolves.
    project=""
    container="pocketshell-test-agents"
  else
    project="$(pocketshell_agents_project_name_for_port "$port")"
    container="$(pocketshell_agents_container_name_for_port "$port")"
  fi

  local env_prefix=(env "AGENTS_HOST_PORT=$port" "AGENTS_CONTAINER_NAME=$container")
  if [[ -n "$project" ]]; then
    env_prefix+=("COMPOSE_PROJECT_NAME=$project")
  fi

  printf 'Bringing up agents fixture lane on host port %s (container %s)...\n' \
    "$port" "$container" >&2
  "${env_prefix[@]}" docker compose -f "$compose_file" up -d --build agents >&2
}

# Tear down ONE agents fixture lane on $port.
pocketshell_agents_fixture_down() {
  local root_dir="$1"
  local port="$2"
  local compose_file
  compose_file="$(pocketshell_agents_compose_file "$root_dir")"

  local project container
  if [[ "$port" == "2222" ]]; then
    project=""
    container="pocketshell-test-agents"
  else
    project="$(pocketshell_agents_project_name_for_port "$port")"
    container="$(pocketshell_agents_container_name_for_port "$port")"
  fi

  local env_prefix=(env "AGENTS_HOST_PORT=$port" "AGENTS_CONTAINER_NAME=$container")
  if [[ -n "$project" ]]; then
    env_prefix+=("COMPOSE_PROJECT_NAME=$project")
  fi

  printf 'Tearing down agents fixture lane on host port %s...\n' "$port" >&2
  "${env_prefix[@]}" docker compose -f "$compose_file" down -v >&2 || true
}

# Wait until the agents fixture on $port answers SSH healthy. Polls the named
# container's compose health status, then falls back to a direct SSH probe.
pocketshell_agents_fixture_wait_healthy() {
  local root_dir="$1"
  local port="$2"
  local timeout_seconds="${3:-180}"
  local container
  if [[ "$port" == "2222" ]]; then
    container="pocketshell-test-agents"
  else
    container="$(pocketshell_agents_container_name_for_port "$port")"
  fi

  local deadline=$((SECONDS + timeout_seconds))
  while (( SECONDS < deadline )); do
    local status
    status="$(docker inspect --format='{{.State.Health.Status}}' "$container" 2>/dev/null || true)"
    if [[ "$status" == "healthy" ]]; then
      printf 'agents fixture on port %s healthy (%s).\n' "$port" "$container" >&2
      return 0
    fi
    sleep 2
  done
  printf 'FAIL: agents fixture on port %s (%s) not healthy within %ss.\n' \
    "$port" "$container" "$timeout_seconds" >&2
  return 1
}

# --- port claim/release (mirrors the per-serial AVD claim in avd-lock.sh) ---

# Try a non-blocking flock on a port's lock file. Echoes the holder PID on
# success; returns non-zero if a sibling holds it.
_pocketshell_agents_try_lock_port() {
  local root_dir="$1"
  local port="$2"
  local lock_file
  lock_file="$(pocketshell_agents_lock_file_for_port "$root_dir" "$port")"
  mkdir -p "$(dirname "$lock_file")"

  local state_dir
  state_dir="$(mktemp -d "${TMPDIR:-/tmp}/pocketshell-agents-claim.XXXXXX")"
  local ready_file="$state_dir/ready"

  (
    exec >/dev/null 2>/dev/null
    if ! exec 9>"$lock_file"; then
      exit 1
    fi
    if ! flock -n 9; then
      exit 1
    fi
    printf 'ready\n' > "$ready_file"
    sleep_pid=""
    trap '[[ -n "$sleep_pid" ]] && kill "$sleep_pid" 2>/dev/null || true; exit 0' HUP INT TERM
    while :; do
      sleep 3600 9>&- &
      sleep_pid="$!"
      wait "$sleep_pid" || true
    done
  ) &
  local holder_pid="$!"

  local waited=0
  while [[ ! -e "$ready_file" ]]; do
    if ! kill -0 "$holder_pid" 2>/dev/null; then
      rm -rf "$state_dir"
      wait "$holder_pid" 2>/dev/null || true
      return 1
    fi
    sleep 0.05
    waited=$((waited + 1))
    if (( waited > 100 )); then
      kill "$holder_pid" 2>/dev/null || true
      wait "$holder_pid" 2>/dev/null || true
      rm -rf "$state_dir"
      return 1
    fi
  done

  rm -rf "$state_dir"
  printf '%s\n' "$holder_pid"
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
pocketshell_claim_agents_port() {
  local root_dir="$1"
  local wait_seconds="${POCKETSHELL_AGENTS_WAIT_SECONDS:-600}"
  local deadline=$((SECONDS + wait_seconds))

  local ports
  ports="$(pocketshell_agents_pool_ports)"

  while :; do
    local port
    for port in $ports; do
      local holder_pid
      if holder_pid="$(_pocketshell_agents_try_lock_port "$root_dir" "$port")"; then
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
        export POCKETSHELL_AGENTS_OWNER_PID="$$"
        if command -v pocketshell_release_all >/dev/null 2>&1; then
          trap pocketshell_release_all EXIT
        else
          trap pocketshell_release_agents_port EXIT
        fi
        printf 'Claimed agents fixture port: %s\n' "$port" >&2
        printf '%s\n' "$port"
        return 0
      fi
    done

    if (( SECONDS >= deadline )); then
      printf 'FAIL: no free agents fixture port (candidates: %s)\n' "$ports" >&2
      return 1
    fi
    printf 'All agents fixture ports busy; waiting for a free one...\n' >&2
    sleep 3
  done
}

pocketshell_release_agents_port() {
  if [[ "${POCKETSHELL_AGENTS_OWNER_PID:-}" != "$$" ]]; then
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
}
