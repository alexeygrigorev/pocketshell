#!/usr/bin/env bash
# scripts/ci-retry-classify.sh — issue #2459
#
# Pure classification of a bounded retry outcome against the original red
# run's captured failure signature (see scripts/ci-retry-signature.py for how
# a signature is produced). No `gh`/network dependency — a deterministic
# function of two signature files plus the retry's own conclusion, so it is
# fully covered by scripts/test-ci-retry-classify.sh without touching the
# GitHub API.
#
# SIGNATURE FILE FORMAT (see ci-retry-signature.py)
#   Lines starting with '#' are metadata/comments, e.g. `# status=ok` or
#   `# status=degraded:<reason>`. Every other non-blank line is a token:
#     job:<job name>            a top-level workflow job that failed
#     class:<Class#method>      a failing test inside a journey job
#
# USAGE
#   ci-retry-classify.sh \
#     --original-signature FILE \
#     --retry-conclusion success|failure \
#     [--retry-signature FILE]
#
# OUTPUT (stdout, KEY=VALUE, one per line — always both keys):
#   CLASSIFICATION=infra|regression|inconclusive
#   REASON=<free text>
#
# CLASSIFICATION meanings:
#   infra         G5 "captured signature + clean re-run" case — the retry
#                 came back clean, OR came back red with a DIFFERENT failure
#                 signature. Do not escalate/freeze on this alone.
#   regression    the retry reproduced the IDENTICAL non-empty failure
#                 signature (same failed job names + same failing test
#                 classes where applicable). This is the D36-freeze-worthy
#                 case, now backed by two runs' evidence instead of one.
#   inconclusive  both runs are red but the automated comparison could not be
#                 trusted (a signature capture was degraded, or the original
#                 signature was empty despite a healthy capture) — needs
#                 human triage. Never silently reported as either infra or a
#                 confirmed regression when the evidence itself is in doubt.
#
# If $GITHUB_OUTPUT is set, the same two keys (lowercased) are ALSO appended
# there.
#
# Self-test: scripts/test-ci-retry-classify.sh

set -uo pipefail

ORIGINAL_SIG=""
RETRY_CONCLUSION=""
RETRY_SIG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --original-signature) ORIGINAL_SIG="$2"; shift 2 ;;
    --retry-conclusion) RETRY_CONCLUSION="$2"; shift 2 ;;
    --retry-signature) RETRY_SIG="$2"; shift 2 ;;
    -h|--help)
      sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0
      ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

if [[ -z "$ORIGINAL_SIG" || -z "$RETRY_CONCLUSION" ]]; then
  echo "usage: $0 --original-signature FILE --retry-conclusion success|failure [--retry-signature FILE]" >&2
  exit 2
fi

case "$RETRY_CONCLUSION" in
  success | failure) ;;
  *)
    echo "--retry-conclusion must be 'success' or 'failure', got '$RETRY_CONCLUSION'" >&2
    exit 2
    ;;
esac

emit() {
  local classification="$1" reason="$2"
  printf 'CLASSIFICATION=%s\n' "$classification"
  printf 'REASON=%s\n' "$reason"
  if [[ -n "${GITHUB_OUTPUT:-}" ]]; then
    {
      printf 'classification=%s\n' "$classification"
      printf 'reason=%s\n' "$reason"
    } >> "$GITHUB_OUTPUT"
  fi
}

# status=<ok|degraded:...> from the trailing `# status=` comment line, and the
# real (non-comment, non-blank) token set, sorted+deduped, from a signature
# file. A missing file reads as an empty, degraded signature rather than
# crashing — the classifier's job is to say "inconclusive", never to abort.
read_status() {
  local file="$1"
  [[ -f "$file" ]] || { printf 'degraded:missing signature file %s' "$file"; return; }
  local line
  line="$(grep -m1 '^# status=' "$file" 2>/dev/null || true)"
  if [[ -z "$line" ]]; then
    printf 'degraded:no status line in %s' "$file"
    return
  fi
  printf '%s' "${line#\# status=}"
}

read_tokens() {
  local file="$1"
  [[ -f "$file" ]] || return 0
  grep -v '^\s*#' "$file" 2>/dev/null | grep -v '^\s*$' | LC_ALL=C sort -u
}

original_status="$(read_status "$ORIGINAL_SIG")"
original_tokens="$(read_tokens "$ORIGINAL_SIG")"

if [[ "$RETRY_CONCLUSION" == "success" ]]; then
  emit infra "retry completed clean (conclusion=success) — G5 infra/flake, no escalation"
  exit 0
fi

# retry-conclusion == failure from here on.
if [[ -z "$RETRY_SIG" ]]; then
  emit inconclusive "retry-conclusion=failure but no --retry-signature was supplied — cannot compare, needs human triage"
  exit 0
fi

retry_status="$(read_status "$RETRY_SIG")"
retry_tokens="$(read_tokens "$RETRY_SIG")"

if [[ "$original_status" != ok || "$retry_status" != ok ]]; then
  emit inconclusive "both runs are red but at least one signature capture was degraded (original=$original_status, retry=$retry_status) — the automated comparison cannot be trusted, needs human triage"
  exit 0
fi

if [[ -z "$original_tokens" ]]; then
  emit inconclusive "original run's failure signature was empty despite a healthy capture (status=ok) — cannot compare, needs human triage"
  exit 0
fi

if [[ "$original_tokens" == "$retry_tokens" ]]; then
  emit regression "retry reproduced the identical failure signature (same failed job(s)/class(es)) — confirmed regression, both runs cited as evidence"
  exit 0
fi

emit infra "retry's failure signature differs from the original (different job(s)/class(es) failed, or the retry's set is a strict subset/superset) — G5 infra/flake, no escalation"
