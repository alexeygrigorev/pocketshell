#!/usr/bin/env bash
# scripts/ci-retry-notify.sh — issue #2459
#
# Orchestrates the two halves of the bounded retry-and-compare mechanism that
# need to talk to the SAME marker-found tracking issue
# (scripts/ci-red-issue.sh / a workflow-specific sibling files it) but happen
# in two SEPARATE job invocations, potentially hours apart (the retry has to
# finish running first):
#
#   store   run right after a genuinely new red run (attempt 1) is filed.
#           Captures THIS run's failure signature as a hidden, machine-
#           parseable comment on the tracking issue, keyed by run_id, so the
#           later `report` call — a completely separate workflow_run event,
#           with no in-memory state carried over — can find it again.
#
#   report  run when the bounded retry (attempt >= 2) itself completes.
#           Re-finds the same tracking issue by marker, re-finds the stored
#           attempt-1 signature by run_id, classifies via
#           scripts/ci-retry-classify.sh (kept pure/testable on its own), and
#           posts ONE final comment citing both run URLs and the verdict.
#
# USAGE
#   ci-retry-notify.sh store \
#     --repo OWNER/NAME --marker MARKER --run-id ID \
#     --signature-file FILE [--gh PATH]
#
#   ci-retry-notify.sh report \
#     --repo OWNER/NAME --marker MARKER --run-id ID \
#     --original-run-url URL --retry-run-url URL \
#     --retry-conclusion success|failure --retry-signature-file FILE \
#     [--classify-script PATH] [--gh PATH]
#
# `store` exits non-zero (loud) when the tracking issue cannot be found or
# the comment cannot be posted — a silent storage failure would make the
# LATER `report` call blind, defeating the whole mechanism, so this must
# fail as loudly as scripts/ci-red-issue.sh itself does on a `gh` failure.
#
# `report` degrades more gently: if the tracking issue has disappeared (e.g.
# manually closed) or the stored signature cannot be found, it prints a loud
# warning AND still runs the classifier against an empty original signature
# (which scripts/ci-retry-classify.sh reports as `inconclusive`, never a
# false `infra` or `regression`), so the retry's outcome is never silently
# dropped even without anywhere to comment it.
#
# Self-test: scripts/test-ci-retry-notify.sh

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
CLASSIFY_SCRIPT_DEFAULT="$SCRIPT_DIR/ci-retry-classify.sh"

MODE="${1:-}"
[[ "$MODE" == "store" || "$MODE" == "report" ]] && shift || true

REPO=""
MARKER=""
RUN_ID=""
SIGNATURE_FILE=""
ORIGINAL_RUN_URL=""
RETRY_RUN_URL=""
RETRY_CONCLUSION=""
RETRY_SIGNATURE_FILE=""
CLASSIFY_SCRIPT="$CLASSIFY_SCRIPT_DEFAULT"
GH_BIN="${POCKETSHELL_GH_BIN:-gh}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --repo) REPO="$2"; shift 2 ;;
    --marker) MARKER="$2"; shift 2 ;;
    --run-id) RUN_ID="$2"; shift 2 ;;
    --signature-file) SIGNATURE_FILE="$2"; shift 2 ;;
    --original-run-url) ORIGINAL_RUN_URL="$2"; shift 2 ;;
    --retry-run-url) RETRY_RUN_URL="$2"; shift 2 ;;
    --retry-conclusion) RETRY_CONCLUSION="$2"; shift 2 ;;
    --retry-signature-file) RETRY_SIGNATURE_FILE="$2"; shift 2 ;;
    --classify-script) CLASSIFY_SCRIPT="$2"; shift 2 ;;
    --gh) GH_BIN="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

STORE_TAG_PREFIX="pocketshell-ci-retry-signature"

usage_fail() {
  echo "usage: $0 store|report --repo O/R --marker M --run-id ID [...]" >&2
  exit 2
}

[[ "$MODE" == "store" || "$MODE" == "report" ]] || usage_fail
[[ -n "$REPO" && -n "$MARKER" && -n "$RUN_ID" ]] || usage_fail

if ! command -v "$GH_BIN" >/dev/null 2>&1; then
  echo "gh CLI not found ($GH_BIN) — cannot reach the tracking issue" >&2
  exit 1
fi

find_tracking_issue() {
  "$GH_BIN" issue list --repo "$REPO" --state open \
    --search "$MARKER in:body" --json number --limit 5 --jq '.[0].number // empty' \
    2>/dev/null
}

if [[ "$MODE" == "store" ]]; then
  [[ -n "$SIGNATURE_FILE" ]] || usage_fail
  [[ -f "$SIGNATURE_FILE" ]] || { echo "signature file not found: $SIGNATURE_FILE" >&2; exit 1; }

  issue_number=""
  issue_number="$(find_tracking_issue)" || issue_number=""
  if [[ -z "$issue_number" ]]; then
    echo "no open tracking issue found via marker '$MARKER' — cannot store the retry signature (the caller must file the tracking issue BEFORE calling store)" >&2
    exit 1
  fi

  body="$(cat <<BODY
<!-- $STORE_TAG_PREFIX run_id=$RUN_ID -->
Stored the attempt-1 failure signature for run_id \`$RUN_ID\` so the bounded
retry's completion can be compared against it (issue #2459).

BEGIN_SIGNATURE
$(cat "$SIGNATURE_FILE")
END_SIGNATURE
BODY
)"

  if ! "$GH_BIN" issue comment "$issue_number" --repo "$REPO" --body "$body"; then
    echo "failed to store the retry signature on tracking issue #$issue_number" >&2
    exit 1
  fi
  echo "Stored retry signature for run_id $RUN_ID on tracking issue #$issue_number"
  exit 0
fi

# --- report ------------------------------------------------------------
[[ -n "$ORIGINAL_RUN_URL" && -n "$RETRY_RUN_URL" && -n "$RETRY_CONCLUSION" ]] || usage_fail
case "$RETRY_CONCLUSION" in
  success | failure) ;;
  *) echo "--retry-conclusion must be 'success' or 'failure'" >&2; exit 2 ;;
esac

issue_number=""
issue_number="$(find_tracking_issue)" || issue_number=""
if [[ -z "$issue_number" ]]; then
  echo "WARNING: no open tracking issue found via marker '$MARKER' — the bounded retry for run_id $RUN_ID completed but there is nowhere to comment the verdict. Classifying anyway so this is not silently dropped from the job log." >&2
fi

original_sig_tmp="$(mktemp)"
trap 'rm -f "$original_sig_tmp"' EXIT

found_stored=0
if [[ -n "$issue_number" ]]; then
  comments_json=""
  comments_json="$("$GH_BIN" issue view "$issue_number" --repo "$REPO" --json comments --jq \
    "[.comments[] | select(.body | contains(\"$STORE_TAG_PREFIX run_id=$RUN_ID\"))] | last | .body // empty" \
    2>/dev/null)" || comments_json=""
  if [[ -n "$comments_json" ]]; then
    printf '%s\n' "$comments_json" | awk '/^BEGIN_SIGNATURE$/{f=1;next}/^END_SIGNATURE$/{f=0}f' > "$original_sig_tmp"
    if [[ -s "$original_sig_tmp" ]]; then
      found_stored=1
    fi
  fi
fi

if [[ "$found_stored" -eq 0 ]]; then
  echo "WARNING: no stored attempt-1 signature found for run_id $RUN_ID (issue ${issue_number:-none}) — classifying against an empty signature, which reports as inconclusive rather than a false infra/regression verdict." >&2
  : > "$original_sig_tmp"
fi

classify_out=""
classify_out="$("$CLASSIFY_SCRIPT" \
  --original-signature "$original_sig_tmp" \
  --retry-conclusion "$RETRY_CONCLUSION" \
  --retry-signature "${RETRY_SIGNATURE_FILE:-/dev/null}")"

classification="$(sed -n 's/^CLASSIFICATION=//p' <<<"$classify_out" | tail -n1)"
reason="$(sed -n 's/^REASON=//p' <<<"$classify_out" | tail -n1)"

echo "$classify_out"

if [[ -z "$issue_number" ]]; then
  exit 0
fi

verdict_line=""
case "$classification" in
  regression)
    verdict_line="**REGRESSION CONFIRMED** — the bounded retry reproduced the identical failure signature. This is real, reproducible evidence for a D36 freeze (not a single-run guess)."
    ;;
  infra)
    verdict_line="**Infra/flake (G5)** — the bounded retry did not reproduce the same failure. Not treated as blocking; not extending/triggering a freeze on this alone."
    ;;
  *)
    verdict_line="**Inconclusive** — both runs are red but the automated comparison could not be trusted (see reason below). Needs human triage."
    ;;
esac

body="$(cat <<BODY
## Bounded retry result (issue #2459)

$verdict_line

- Original run: $ORIGINAL_RUN_URL
- Retry run: $RETRY_RUN_URL
- Classification: \`$classification\`
- Reason: $reason

This retry was automatically triggered once (bounded — the retry's own
result is never itself retried) after the original run's failure signature
was captured.
BODY
)"

if ! "$GH_BIN" issue comment "$issue_number" --repo "$REPO" --body "$body"; then
  echo "failed to post the bounded-retry verdict on tracking issue #$issue_number" >&2
  exit 1
fi
echo "Posted bounded-retry verdict ($classification) on tracking issue #$issue_number"
