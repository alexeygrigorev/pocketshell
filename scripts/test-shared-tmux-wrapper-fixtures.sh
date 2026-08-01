#!/usr/bin/env bash
# Regression guard for every Docker fixture that installs the shared tmux shim.
#
# The shim delegates to /usr/bin/tmux.real. A fixture that copies agent-bin/
# without moving Alpine's tmux binary and exposing the shim at /usr/bin/tmux
# builds successfully, then fails only when a journey invokes tmux (#1937).
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SHARED_TMUX_COPY_REGEX='^COPY[[:space:]]+tests/docker/agent-bin/(?:[[:space:]]|tmux[[:space:]])'

fail() {
  printf 'FAIL: %s\n' "$*" >&2
  exit 1
}

mapfile -t consumers < <(
  rg -l "$SHARED_TMUX_COPY_REGEX" "$ROOT_DIR/tests/docker"/Dockerfile.* | sort
)

[[ ${#consumers[@]} -gt 0 ]] || fail "no Dockerfile consumers of the shared agent-bin/tmux shim found"

for dockerfile in "${consumers[@]}"; do
  grep -Fq 'mv /usr/bin/tmux /usr/bin/tmux.real' "$dockerfile" \
    || fail "${dockerfile#"$ROOT_DIR/"} copies the shared tmux shim but does not preserve real tmux"
  grep -Fq 'ln -sf /usr/local/bin/tmux /usr/bin/tmux' "$dockerfile" \
    || fail "${dockerfile#"$ROOT_DIR/"} copies the shared tmux shim but does not expose it at /usr/bin/tmux"
done

printf 'Static shared-tmux invariant passed for %d consumers.\n' "${#consumers[@]}"

case "$#:${1:-}" in
  0:) exit 0 ;;
  1:--docker) ;;
  *) fail "usage: $0 [--docker]" ;;
esac
command -v docker >/dev/null || fail "docker is required for --docker"

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

for dockerfile in "${consumers[@]}"; do
  fixture="${dockerfile##*/Dockerfile.}"
  tag="pocketshell-test:shared-tmux-guard-${fixture}"
  build_log="$tmp_dir/${fixture}-build.log"
  printf 'Building and smoking %s...\n' "$fixture"
  if ! docker build --file "$dockerfile" --tag "$tag" "$ROOT_DIR" >"$build_log" 2>&1; then
    tail -n 40 "$build_log" >&2
    fail "Docker build failed for $fixture"
  fi

  # Use an isolated socket. Test automation must never touch the maintainer's
  # default tmux socket, even inside a fixture smoke.
  docker run --rm --entrypoint /bin/sh "$tag" -ec '
    test -x /usr/bin/tmux.real
    test "$(readlink /usr/bin/tmux)" = /usr/local/bin/tmux
    /usr/bin/tmux -V
    /usr/local/bin/tmux -V
    su testuser -c "tmux -L pocketshell-shared-wrapper-smoke new-session -d -s smoke"
    su testuser -c "tmux -L pocketshell-shared-wrapper-smoke has-session -t smoke"
    su testuser -c "tmux -L pocketshell-shared-wrapper-smoke kill-session -t smoke"
  '
done

# Preserve the exact CI failure sequence: the expected old-CLI `tree` mismatch
# must not poison the following tmux liveness check.
docker run --rm --entrypoint /bin/sh pocketshell-test:shared-tmux-guard-agents-old-cli -ec '
  su testuser -c '\''
    pocketshell tree get </dev/null
    rc=$?
    if [ "$rc" -eq 0 ]; then
      echo "old-cli fixture unexpectedly accepted tree get" >&2
      exit 88
    fi
    tmux -V
    printf "old-cli mismatch confirmed: tree get rc=%s, tmux remains live\n" "$rc"
  '\''
'

printf 'Docker shared-tmux smoke passed for %d consumers.\n' "${#consumers[@]}"
