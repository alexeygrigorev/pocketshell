#!/usr/bin/env bash
# The Tests workflow's "Nightly exact-method guard self-test" step (#1678/#1751).
#
# Extracted out of `.github/workflows/tests.yml` because that file sits within a
# kilobyte of the 128 KiB file-size-hygiene threshold, and
# scripts/check-file-size-hygiene.sh's prescribed remedy for a workflow at its
# cap is exactly this: move the inline `run: |` shell into scripts/ and call it
# from the step, taking the explanatory comments with it. The step NAME is
# unchanged. Same precedent as scripts/run-release-evidence-guards.sh (#2064).
#
# Issue #1678/#1751/#2141: the nightly phase-2 fault verdict is only as honest as
# the exact-method guards that compute it — a class-level phase greens vacuously
# when a required method is skipped, renamed, or reports a timeline that claims
# nothing. Those guards run inside the nightly job, so nothing per-push proved
# they still go RED on a vacuous artifact. This runs their mutation self-test
# (skipped method, wrong method, forbidden typed event in the snapshot AND in the
# timeline, unfired positive-control observer, blocked/unrecovered
# same-connection sentinel, malformed authoritative viewport PNG) every push.
# Pure shell: no emulator, no Docker, no Gradle.
set -euo pipefail

cd "$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

bash scripts/lib/nightly-exact-method-guard.sh --self-test
