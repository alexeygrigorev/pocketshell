#!/usr/bin/env bash
# scripts/test-ci-cadence.sh — issue #2353
#
# Runs all four scheduled-cadence self-tests in sequence (a thin wrapper so
# .github/workflows/tests.yml only needs one short `run:` line under its
# file-size ratchet — see scripts/check-file-size-hygiene.sh).
#
#   scripts/test-ci-skip-check.sh — scripts/ci-skip-check.sh
#   scripts/test-ci-plan.sh       — scripts/ci-plan.sh
#   scripts/test-ci-red-issue.sh  — scripts/ci-red-issue.sh
#   scripts/test-ci-run-jobs.sh   — scripts/ci-run-jobs.sh
#
# Issue #2459 adds the bounded retry-and-compare self-tests here too (same
# rationale — one short `run:` line under tests.yml's file-size ratchet):
#   scripts/test-ci-retry-gate.sh               — scripts/ci-retry-gate.sh
#   scripts/test-ci-retry-classify.sh            — scripts/ci-retry-classify.sh
#   scripts/test-ci-retry-signature.sh           — scripts/ci-retry-signature.py
#   scripts/test-ci-retry-notify.sh              — scripts/ci-retry-notify.sh
#   scripts/test-ci-nightly-extensive-red-issue.sh — scripts/ci-nightly-extensive-red-issue.sh
set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
"$SCRIPT_DIR/test-ci-skip-check.sh"
"$SCRIPT_DIR/test-ci-plan.sh"
"$SCRIPT_DIR/test-ci-red-issue.sh"
"$SCRIPT_DIR/test-ci-run-jobs.sh"
"$SCRIPT_DIR/test-ci-retry-gate.sh"
"$SCRIPT_DIR/test-ci-retry-classify.sh"
"$SCRIPT_DIR/test-ci-retry-signature.sh"
"$SCRIPT_DIR/test-ci-retry-notify.sh"
"$SCRIPT_DIR/test-ci-nightly-extensive-red-issue.sh"
