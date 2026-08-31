#!/usr/bin/env bash
set -eo pipefail

# Extracted from .github/workflows/tests.yml's "Verify agent SSH target tool
# surface" step (scripts/check-file-size-hygiene.sh's workflow-headroom
# guard). Pure logic move, no behaviour change.

chmod 600 tests/docker/test_key
ssh -i tests/docker/test_key -p 2222 \
  -o BatchMode=yes \
  -o ConnectTimeout=2 \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null \
  testuser@127.0.0.1 \
  'for tool in pocketshell agent-log-explorer tmuxctl quse; do command -v "$tool"; done'
