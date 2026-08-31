#!/usr/bin/env bash
set -eo pipefail

# Extracted from .github/workflows/tests.yml's "Verify agents-old-cli is a
# CLI mismatch (issue #849)" step (scripts/check-file-size-hygiene.sh's
# workflow-headroom guard). Pure logic move, no behaviour change.
#
# Confirm the 2238 fixture really IS an old CLI (rejects `tree` with a
# non-zero exit) while staying live (tmux works), so the regression tests
# exercise the real CLI-mismatch path and never pass vacuously against a
# fixture that silently accepted the new subcommand.

chmod 600 tests/docker/test_key
ssh -i tests/docker/test_key -p 2238 \
  -o BatchMode=yes \
  -o ConnectTimeout=3 \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null \
  testuser@127.0.0.1 \
  'pocketshell tree get </dev/null; rc=$?; if [ "$rc" -eq 0 ]; then echo "old-cli fixture unexpectedly accepted tree get (rc=0)" >&2; exit 1; fi; tmux -V >/dev/null && echo "old-cli mismatch confirmed: tree get rc=$rc, host still live"'
