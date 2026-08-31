#!/usr/bin/env bash
set -eo pipefail

# Extracted from .github/workflows/tests.yml's "Publish journey suite
# summary" step (scripts/check-file-size-hygiene.sh's workflow-headroom
# guard). Pure logic move, no behaviour change. Relies on GITHUB_STEP_SUMMARY
# already being set in the runner environment, exactly as it was inline.

if [[ -f artifacts/ci-journey/summary.md ]]; then
  cat artifacts/ci-journey/summary.md >> "$GITHUB_STEP_SUMMARY"
else
  {
    echo "# Per-push CI journey suite — summary"
    echo
    echo "No suite summary was written (the suite script may have aborted before writing it)."
  } >> "$GITHUB_STEP_SUMMARY"
fi
