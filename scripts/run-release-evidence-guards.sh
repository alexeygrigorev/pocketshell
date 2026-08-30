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

# docs/release.md stabilizes a release/vX.Y.Z branch in its own worktree.
# Validation may run on the pushed candidate, but publication must fast-forward that exact SHA to main, push main, then tag from main.
# The tag helper accepts only pushed main; both gates bind evidence to the
# exact SHA they validate or publish.
bash scripts/test-release-branch-guard.sh

# Same worktree flow, one layer down: the pre-release gate's "isolated" copy
# must stay isolated when the source checkout is a worktree, where `.git` is a
# file rather than a directory.
bash scripts/test-release-gate-worktree-isolation.sh
