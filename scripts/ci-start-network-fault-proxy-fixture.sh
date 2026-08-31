#!/usr/bin/env bash
set -eo pipefail

# Extracted from .github/workflows/tests.yml's "Start Docker fixture
# (network-fault-proxy, issue #1733)" step
# (scripts/check-file-size-hygiene.sh's workflow-headroom guard). Pure logic
# move, no behaviour change.

scripts/ci-journey-fixture-retry.sh network-fault-proxy -- docker compose -f tests/docker/docker-compose.yml up -d --no-deps network-fault-proxy
for attempt in $(seq 1 30); do
  if curl --fail --silent --show-error http://127.0.0.1:8474/version; then
    exit 0
  fi
  sleep 1
done
docker compose -f tests/docker/docker-compose.yml logs --no-color network-fault-proxy
exit 1
