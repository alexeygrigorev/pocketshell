#!/usr/bin/env bash
# The Tests workflow's "Release identity and evidence guards" step (#2064).
#
# Extracted out of `.github/workflows/tests.yml` because that file sits within
# a kilobyte of the 128 KiB file-size-hygiene threshold, and
# scripts/check-file-size-hygiene.sh's prescribed remedy for a workflow at its
# cap is exactly this: move the inline `run: |` shell into scripts/ and call it
# from the step, taking the explanatory comments with it. The step NAME (a
# required-check name) is unchanged.
#
# Each harness below is device-free, network-free and Gradle-free — they drive
# the release chain's contracts with fixtures, so they belong in the cheap
# static-guard job rather than in any emulator lane.
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

# Issue #2064: one binary flows through the whole release chain, and every hop
# re-checks its sha256.
bash scripts/test-release-apk-identity.sh

# Issue #2064: reusing a CI unit-test run as release evidence has to be
# recorded as such, not silently substituted for a local run.
bash scripts/test-reuse-ci-unit-evidence.sh

# Issue #2064: the summary contract push-release-tag.sh reads (`Commit SHA:`,
# the result line, per-step wall clock, validated APK digest).
bash scripts/test-release-gate-summary.sh

# docs/release.md cuts a release on a release/vX.Y.Z branch in its own
# worktree. Both release gates must accept that line without opening up to any
# branch, and must bind their evidence to the candidate's own SHA.
bash scripts/test-release-branch-guard.sh
