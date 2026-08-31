#!/usr/bin/env bash
set -eo pipefail

# Extracted from .github/workflows/tests.yml's "Collect Docker logs" step
# (scripts/check-file-size-hygiene.sh's workflow-headroom guard). Pure
# logic move, no behaviour change.

mkdir -p artifacts/docker
docker compose -f tests/docker/docker-compose.yml ps > artifacts/docker/compose-ps.txt
docker compose -f tests/docker/docker-compose.yml logs --no-color agents > artifacts/docker/agents.log 2>/dev/null || true
docker inspect pocketshell-test-agents > artifacts/docker/agents-inspect.json 2>/dev/null || true
docker compose -f tests/docker/docker-compose.yml logs --no-color network-fault-proxy > artifacts/docker/network-fault-proxy.log 2>/dev/null || true
docker inspect pocketshell-test-network-fault-proxy > artifacts/docker/network-fault-proxy-inspect.json 2>/dev/null || true
# Issue #849: also capture the old-CLI (2238) fixture diagnostics.
docker compose -f tests/docker/docker-compose.yml logs --no-color agents-old-cli > artifacts/docker/agents-old-cli.log 2>/dev/null || true
docker inspect pocketshell-test-agents-old-cli > artifacts/docker/agents-old-cli-inspect.json 2>/dev/null || true
# Issue #839: also capture the daemon (2239) fixture diagnostics.
docker compose -f tests/docker/docker-compose.yml logs --no-color agents-daemon > artifacts/docker/agents-daemon.log 2>/dev/null || true
docker inspect pocketshell-test-agents-daemon > artifacts/docker/agents-daemon-inspect.json 2>/dev/null || true
