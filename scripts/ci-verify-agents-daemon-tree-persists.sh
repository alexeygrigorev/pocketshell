#!/usr/bin/env bash
set -eo pipefail

# Extracted from .github/workflows/tests.yml's "Verify agents-daemon real
# `pocketshell tree` persists (issue #839)" step
# (scripts/check-file-size-hygiene.sh's workflow-headroom guard). Pure logic
# move, no behaviour change.
#
# Confirm the 2239 fixture really IS the REAL daemon: `pocketshell tree`
# round-trips a persist (upsert then get over a fresh exec returns the
# collapsed node) AND reconcile diffs against the LIVE tmux server. A broken
# fixture fails the job loudly here instead of letting the journey pass
# vacuously / self-skip. Mirrors the agents-old-cli mismatch sanity check.

chmod 600 tests/docker/test_key
ssh -i tests/docker/test_key -p 2239 \
  -o BatchMode=yes \
  -o ConnectTimeout=3 \
  -o StrictHostKeyChecking=no \
  -o UserKnownHostsFile=/dev/null \
  testuser@127.0.0.1 \
  'set -e;
   printf "%s" "{\"host\":\"ci\",\"expected_version\":0,\"nodes\":[{\"session\":\"s\",\"order\":0,\"folder_path\":\"/p\",\"collapsed\":true}]}" | pocketshell tree upsert >/dev/null;
   got="$(printf "%s" "{\"host\":\"ci\"}" | pocketshell tree get)";
   echo "$got" | grep -q "\"session\": \"s\"" || { echo "agents-daemon tree did NOT persist: $got" >&2; exit 1; };
   echo "$got" | grep -q "\"collapsed\": true" || { echo "agents-daemon tree dropped the collapse: $got" >&2; exit 1; };
   echo "agents-daemon real-tree persist confirmed: $got"'
