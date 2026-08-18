#!/usr/bin/env bash
# Issue #2090: bounded last-completed-class + job-metadata telemetry for a
# hosted journey shard. The local snapshot is a convenience; the load-bearing
# copy is published OUTSIDE the runner filesystem so it survives abrupt runner
# disappearance (the #2038 hosted-shard-2 loss: job log BlobNotFound, every
# if:always() step still pending, no report/XML/summary).
#
# This path is INFRASTRUCTURE DIAGNOSTIC only. It never writes a product
# CLEAN/RED verdict and never lets a missing shard look green — artifact
# attendance / fail-closed accounting stays with #2082.
#
# Usage:
#   ci-journey-progress-telemetry.sh start
#   ci-journey-progress-telemetry.sh class-started FQCN
#   ci-journey-progress-telemetry.sh class-completed FQCN STATUS
#   ci-journey-progress-telemetry.sh suite-completed [STATUS]
#   ci-journey-progress-telemetry.sh artifacts-uploaded
#   ci-journey-progress-telemetry.sh observe-stream
#   ci-journey-progress-telemetry.sh classify
#   ci-journey-progress-telemetry.sh collect-from-github DEST_DIR
#   ci-journey-progress-telemetry.sh read [FILE]
#
# Env (production + tests):
#   CI_JOURNEY_PROGRESS_FILE           local snapshot (default under
#                                      artifacts/ci-journey-progress/)
#   CI_JOURNEY_PROGRESS_EXTERNAL_DIR   extra-runner store. Tests point this
#                                      outside the wiped workspace; CI can
#                                      also set it. THIS is the durable copy.
#   CI_JOURNEY_PROGRESS_MAX_BYTES      hard cap (default 4096)
#   CI_JOURNEY_PROGRESS_DISABLE_GITHUB=1  skip the Checks-API publisher
#   CI_JOURNEY_PROGRESS_VERDICT_PRESENT / _LATER_STEPS_RAN /
#   CI_JOURNEY_PROGRESS_LOST_COMMUNICATION
#                                      classify() inputs (true|false)
#
# Publish is ALWAYS fail-soft: a telemetry fault must never redden a shard.
set -uo pipefail

SCHEMA="pocketshell.journey.progress.v1"
OWNER="infra"
PROGRESS_SIGNATURE="hosted_runner_progress"
VERDICT_ROLE="diagnostic"
MAX_BYTES="${CI_JOURNEY_PROGRESS_MAX_BYTES:-4096}"
[[ "$MAX_BYTES" =~ ^[1-9][0-9]{2,6}$ ]] || MAX_BYTES=4096

cmd="${1:-}"
shift || true

progress_fail_soft() {
  echo "ci-journey-progress-telemetry: $*" >&2
  return 0
}

iso_now() {
  date -u +%Y-%m-%dT%H:%M:%SZ
}

sanitize_token() {
  local raw="${1-}"
  raw="${raw//$'\n'/}"
  raw="${raw//$'\r'/}"
  printf '%s' "$raw"
}

sanitize_class() {
  local raw
  raw="$(sanitize_token "${1-}")"
  if [[ "$raw" =~ ^[A-Za-z_][A-Za-z0-9_.]*([#][A-Za-z_][A-Za-z0-9_]*)?$ ]]; then
    printf '%s' "$raw"
    return 0
  fi
  printf ''
}

sanitize_status() {
  local raw
  raw="$(sanitize_token "${1-}")"
  raw="$(printf '%s' "$raw" | tr '[:upper:]' '[:lower:]')"
  case "$raw" in
    pass|fail|flake_recovered|budget_timeout|budget_skipped|isolation_failure|error|started|completed|running|'')
      printf '%s' "$raw"
      ;;
    *)
      printf 'unknown'
      ;;
  esac
}

resolve_shard() {
  local shard total
  shard="${POCKETSHELL_JOURNEY_CI_SHARD_INDEX:-${POCKETSHELL_NIGHTLY_SHARD_INDEX:-${JOURNEY_CI_SHARD_INDEX:-0}}}"
  total="${POCKETSHELL_JOURNEY_CI_SHARD_TOTAL:-${POCKETSHELL_NIGHTLY_SHARD_TOTAL:-${JOURNEY_CI_SHARD_TOTAL:-1}}}"
  [[ "$shard" =~ ^[0-9]+$ ]] || shard=0
  [[ "$total" =~ ^[1-9][0-9]*$ ]] || total=1
  printf '%s %s' "$shard" "$total"
}

progress_basename() {
  local shard attempt run_id
  shard="${1:-0}"
  attempt="${GITHUB_RUN_ATTEMPT:-1}"
  run_id="${GITHUB_RUN_ID:-local}"
  [[ "$attempt" =~ ^[0-9]+$ ]] || attempt=1
  printf 'journey-progress-shard-%s-attempt-%s-run-%s.txt' "$shard" "$attempt" "$run_id"
}

default_local_file() {
  local shard total
  read -r shard total <<<"$(resolve_shard)"
  printf 'artifacts/ci-journey-progress/%s' "$(progress_basename "$shard")"
}

LOCAL_FILE="${CI_JOURNEY_PROGRESS_FILE:-$(default_local_file)}"
EXTERNAL_DIR="${CI_JOURNEY_PROGRESS_EXTERNAL_DIR:-}"

read_field() {
  local file="$1" key="$2"
  [[ -f "$file" ]] || { printf ''; return 0; }
  sed -n "s/^${key}=//p" "$file" 2>/dev/null | head -n 1
}

write_record() {
  local phase="$1"
  local now
  now="$(iso_now)"
  local shard total
  read -r shard total <<<"$(resolve_shard)"

  local started_at="" seq="0" classes_completed="0" last_class="" last_status="" \
    last_at="" in_progress="" check_run_id="" suite_state="running" \
    classes_selected="${CI_JOURNEY_PROGRESS_CLASSES_SELECTED:-0}"
  if [[ -f "$LOCAL_FILE" ]]; then
    started_at="$(read_field "$LOCAL_FILE" started_at)"
    seq="$(read_field "$LOCAL_FILE" seq)"
    classes_completed="$(read_field "$LOCAL_FILE" classes_completed)"
    last_class="$(read_field "$LOCAL_FILE" last_completed_class)"
    last_status="$(read_field "$LOCAL_FILE" last_completed_status)"
    last_at="$(read_field "$LOCAL_FILE" last_completed_at)"
    in_progress="$(read_field "$LOCAL_FILE" in_progress_class)"
    check_run_id="$(read_field "$LOCAL_FILE" check_run_id)"
    suite_state="$(read_field "$LOCAL_FILE" suite_state)"
    classes_selected="$(read_field "$LOCAL_FILE" classes_selected)"
  fi
  [[ "${seq}" =~ ^[0-9]+$ ]] || seq=0
  [[ "${classes_completed}" =~ ^[0-9]+$ ]] || classes_completed=0
  [[ "${classes_selected}" =~ ^[0-9]+$ ]] || classes_selected="${CI_JOURNEY_PROGRESS_CLASSES_SELECTED:-0}"
  [[ -n "${started_at}" ]] || started_at="$now"
  [[ -n "${suite_state}" ]] || suite_state="running"

  case "$phase" in
    started)
      seq=0
      classes_completed=0
      last_class=""
      last_status=""
      last_at=""
      in_progress=""
      suite_state="running"
      started_at="$now"
      ;;
    class_started)
      in_progress="${2-}"
      seq=$((seq + 1))
      ;;
    class_completed)
      last_class="${2-}"
      last_status="${3-}"
      last_at="$now"
      in_progress=""
      classes_completed=$((classes_completed + 1))
      seq=$((seq + 1))
      ;;
    suite_completed)
      suite_state="${2:-completed}"
      in_progress=""
      seq=$((seq + 1))
      ;;
    artifacts_uploaded)
      suite_state="artifacts_uploaded"
      seq=$((seq + 1))
      ;;
  esac

  local parent
  parent="$(dirname "$LOCAL_FILE")"
  mkdir -p "$parent" || { progress_fail_soft "cannot create $parent"; return 0; }

  local tmp
  tmp="${LOCAL_FILE}.tmp.$$"
  {
    printf 'schema=%s\n' "$SCHEMA"
    printf 'owner=%s\n' "$OWNER"
    printf 'signature=%s\n' "$PROGRESS_SIGNATURE"
    printf 'verdict_role=%s\n' "$VERDICT_ROLE"
    printf 'workflow=%s\n' "$(sanitize_token "${CI_JOURNEY_PROGRESS_WORKFLOW:-${GITHUB_WORKFLOW:-unknown}}")"
    printf 'job_name=%s\n' "$(sanitize_token "${CI_JOURNEY_PROGRESS_JOB_NAME:-${GITHUB_JOB:-unknown}}")"
    printf 'job_id=%s\n' "$(sanitize_token "${CI_JOURNEY_PROGRESS_JOB_ID:-${GITHUB_JOB_ID:-unknown}}")"
    printf 'run_id=%s\n' "$(sanitize_token "${GITHUB_RUN_ID:-unknown}")"
    printf 'run_attempt=%s\n' "$(sanitize_token "${GITHUB_RUN_ATTEMPT:-unknown}")"
    printf 'shard=%s\n' "$shard"
    printf 'shard_total=%s\n' "$total"
    printf 'runner_name=%s\n' "$(sanitize_token "${RUNNER_NAME:-unknown}")"
    printf 'runner_os=%s\n' "$(sanitize_token "${RUNNER_OS:-unknown}")"
    printf 'head_sha=%s\n' "$(sanitize_token "${GITHUB_SHA:-unknown}")"
    printf 'started_at=%s\n' "$started_at"
    printf 'updated_at=%s\n' "$now"
    printf 'seq=%s\n' "$seq"
    printf 'phase=%s\n' "$phase"
    printf 'last_completed_class=%s\n' "$last_class"
    printf 'last_completed_status=%s\n' "$last_status"
    printf 'last_completed_at=%s\n' "$last_at"
    printf 'in_progress_class=%s\n' "$in_progress"
    printf 'classes_completed=%s\n' "$classes_completed"
    printf 'classes_selected=%s\n' "$classes_selected"
    printf 'suite_state=%s\n' "$suite_state"
    printf 'check_run_id=%s\n' "$check_run_id"
  } > "$tmp" || { rm -f "$tmp"; progress_fail_soft "cannot write $tmp"; return 0; }

  local size
  size="$(wc -c < "$tmp" | tr -d ' ')"
  if [[ "$size" =~ ^[0-9]+$ ]] && (( size > MAX_BYTES )); then
    progress_fail_soft "record ${size}B exceeds cap ${MAX_BYTES}B — dropping"
    rm -f "$tmp"
    return 0
  fi

  mv -f "$tmp" "$LOCAL_FILE" || { rm -f "$tmp"; progress_fail_soft "cannot publish $LOCAL_FILE"; return 0; }
  publish_external "$LOCAL_FILE" "$shard"
  publish_github_check "$LOCAL_FILE" || true
}

publish_external() {
  local file="$1" shard="$2"
  [[ -n "$EXTERNAL_DIR" ]] || return 0
  mkdir -p "$EXTERNAL_DIR" || { progress_fail_soft "cannot create external dir $EXTERNAL_DIR"; return 0; }
  local dest="$EXTERNAL_DIR/$(progress_basename "$shard")"
  cp -f "$file" "$dest" || progress_fail_soft "cannot copy to $dest"
}

publish_github_check() {
  local file="$1"
  [[ "${CI_JOURNEY_PROGRESS_DISABLE_GITHUB:-0}" == "1" ]] && return 0
  [[ -n "${GITHUB_TOKEN:-}" && -n "${GITHUB_REPOSITORY:-}" && -n "${GITHUB_SHA:-}" ]] || return 0
  command -v python3 >/dev/null 2>&1 || return 0

  local api="${GITHUB_API_URL:-https://api.github.com}"
  local check_id
  check_id="$(read_field "$file" check_run_id)"
  local new_id
  new_id="$(
    CI_JOURNEY_PROGRESS_FILE="$file" \
    CI_JOURNEY_PROGRESS_CHECK_ID="$check_id" \
    CI_JOURNEY_PROGRESS_API="$api" \
    python3 - <<'PY'
import json, os, urllib.error, urllib.request

path = os.environ["CI_JOURNEY_PROGRESS_FILE"]
token = os.environ.get("GITHUB_TOKEN", "")
repo = os.environ.get("GITHUB_REPOSITORY", "")
sha = os.environ.get("GITHUB_SHA", "")
api = os.environ.get("CI_JOURNEY_PROGRESS_API", "https://api.github.com").rstrip("/")
check_id = os.environ.get("CI_JOURNEY_PROGRESS_CHECK_ID", "").strip()
fields = {}
with open(path, encoding="utf-8") as handle:
    for raw in handle:
        raw = raw.rstrip("\n")
        if "=" not in raw:
            continue
        key, value = raw.split("=", 1)
        fields[key] = value

shard = fields.get("shard", "unknown")
attempt = fields.get("run_attempt", "unknown")
name = "journey-progress-shard-%s-attempt-%s" % (shard, attempt)
last_class = fields.get("last_completed_class") or "(none yet)"
title = "last completed: %s" % last_class
summary_lines = [
    "owner=infra (issue #2090) — diagnostic only, not a product verdict",
    "signature=%s" % fields.get("signature", "hosted_runner_progress"),
    "phase=%s" % fields.get("phase", ""),
    "last_completed_class=%s" % fields.get("last_completed_class", ""),
    "last_completed_status=%s" % fields.get("last_completed_status", ""),
    "in_progress_class=%s" % fields.get("in_progress_class", ""),
    "classes_completed=%s" % fields.get("classes_completed", ""),
    "classes_selected=%s" % fields.get("classes_selected", ""),
    "suite_state=%s" % fields.get("suite_state", ""),
    "run_id=%s" % fields.get("run_id", ""),
    "run_attempt=%s" % fields.get("run_attempt", ""),
    "job_name=%s" % fields.get("job_name", ""),
    "job_id=%s" % fields.get("job_id", ""),
    "runner_name=%s" % fields.get("runner_name", ""),
    "updated_at=%s" % fields.get("updated_at", ""),
]
payload = {
    "name": name,
    "head_sha": sha,
    "status": "in_progress" if fields.get("suite_state") not in (
        "completed", "artifacts_uploaded"
    ) else "completed",
    "output": {
        "title": title[:255],
        "summary": "\n".join(summary_lines)[:60000],
    },
}
if payload["status"] == "completed":
    payload["conclusion"] = "neutral"
data = json.dumps(payload).encode("utf-8")
if check_id.isdigit():
    url = "%s/repos/%s/check-runs/%s" % (api, repo, check_id)
    method = "PATCH"
else:
    url = "%s/repos/%s/check-runs" % (api, repo)
    method = "POST"
request = urllib.request.Request(
    url,
    data=data,
    method=method,
    headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "Content-Type": "application/json",
        "User-Agent": "pocketshell-ci-journey-progress",
    },
)
try:
    with urllib.request.urlopen(request, timeout=10) as response:
        body = json.loads(response.read().decode("utf-8"))
except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError):
    raise SystemExit(0)
ident = body.get("id")
if ident is not None:
    print(ident)
PY
  )" || true
  if [[ "$new_id" =~ ^[0-9]+$ && "$new_id" != "$check_id" ]]; then
    local tmp="${LOCAL_FILE}.tmp.$$"
    if [[ -f "$LOCAL_FILE" ]]; then
      sed "s/^check_run_id=.*/check_run_id=${new_id}/" "$LOCAL_FILE" > "$tmp" \
        && mv -f "$tmp" "$LOCAL_FILE" \
        || rm -f "$tmp"
      publish_external "$LOCAL_FILE" "$(read_field "$LOCAL_FILE" shard)"
    fi
  fi
}

cmd_start() {
  CI_JOURNEY_PROGRESS_CLASSES_SELECTED="${CI_JOURNEY_PROGRESS_CLASSES_SELECTED:-0}"
  write_record started
}

cmd_class_started() {
  local fqcn
  fqcn="$(sanitize_class "${1-}")"
  [[ -n "$fqcn" ]] || return 0
  write_record class_started "$fqcn"
}

cmd_class_completed() {
  local fqcn status
  fqcn="$(sanitize_class "${1-}")"
  status="$(sanitize_status "${2-}")"
  [[ -n "$fqcn" ]] || return 0
  write_record class_completed "$fqcn" "$status"
}

cmd_suite_completed() {
  local status
  status="$(sanitize_status "${1:-completed}")"
  [[ -n "$status" ]] || status="completed"
  write_record suite_completed "$status"
}

cmd_artifacts_uploaded() {
  write_record artifacts_uploaded
}

# Parse Gradle / instrumentation lines for a completed class. Always exit 0
# and pass every line through so a caller can sit in a pipeline.
cmd_observe_stream() {
  local line class_hint=""
  local gradle_re='^[[:space:]]*([A-Za-z_][A-Za-z0-9_.]*)[[:space:]]+>[[:space:]]+[^[:space:]]+[[:space:]]+(PASSED|FAILED|SKIPPED)\b'
  local instr_class_re='INSTRUMENTATION_STATUS:[[:space:]]*class=([A-Za-z_][A-Za-z0-9_.]*)'
  local instr_code_re='INSTRUMENTATION_STATUS_CODE:[[:space:]]*(-?[0-9]+)'
  local journey_pass_re='^JOURNEY_PASS:[[:space:]]+([A-Za-z_][A-Za-z0-9_.#]*)'
  local journey_fail_re='^JOURNEY_FAILED:[[:space:]]+([A-Za-z_][A-Za-z0-9_.#]*)'
  local journey_recovered_re='^JOURNEY_FLAKE_RECOVERED:[[:space:]]+([A-Za-z_][A-Za-z0-9_.#]*)'
  while IFS= read -r line || [[ -n "$line" ]]; do
    printf '%s\n' "$line"
    if [[ "$line" =~ $gradle_re ]]; then
      local gclass gstatus
      gclass="$(sanitize_class "${BASH_REMATCH[1]}")"
      gstatus="$(sanitize_status "${BASH_REMATCH[2]}")"
      [[ -n "$gclass" ]] && cmd_class_completed "$gclass" "$gstatus"
      continue
    fi
    if [[ "$line" =~ $instr_class_re ]]; then
      class_hint="$(sanitize_class "${BASH_REMATCH[1]}")"
      continue
    fi
    if [[ "$line" =~ $instr_code_re && -n "$class_hint" ]]; then
      local code="${BASH_REMATCH[1]}" status="pass"
      case "$code" in
        0) status="pass" ;;
        -1|1) status="fail" ;;
        -2) status="error" ;;
        -3) status="budget_skipped" ;;
        *) status="unknown" ;;
      esac
      cmd_class_completed "$class_hint" "$status"
      class_hint=""
      continue
    fi
    if [[ "$line" =~ $journey_pass_re ]]; then
      cmd_class_completed "${BASH_REMATCH[1]}" pass
      continue
    fi
    if [[ "$line" =~ $journey_recovered_re ]]; then
      cmd_class_completed "${BASH_REMATCH[1]}" flake_recovered
      continue
    fi
    if [[ "$line" =~ $journey_fail_re ]]; then
      cmd_class_completed "${BASH_REMATCH[1]}" fail
      continue
    fi
  done
  return 0
}

# Distinguish provider VM loss / process death / artifact-upload failure from
# a normal completion. Never a product verdict: owner stays infra.
cmd_classify() {
  local file="${1:-$LOCAL_FILE}"
  if [[ -z "$file" || ! -f "$file" ]]; then
    if [[ -n "$EXTERNAL_DIR" ]]; then
      local shard total
      read -r shard total <<<"$(resolve_shard)"
      file="$EXTERNAL_DIR/$(progress_basename "$shard")"
    fi
  fi
  local last_class="" phase="" suite_state=""
  if [[ -f "$file" ]]; then
    last_class="$(read_field "$file" last_completed_class)"
    phase="$(read_field "$file" phase)"
    suite_state="$(read_field "$file" suite_state)"
  fi
  local verdict_present="${CI_JOURNEY_PROGRESS_VERDICT_PRESENT:-false}"
  local later_steps="${CI_JOURNEY_PROGRESS_LATER_STEPS_RAN:-false}"
  local lost_comm="${CI_JOURNEY_PROGRESS_LOST_COMMUNICATION:-false}"
  local signature="none"
  if [[ "$lost_comm" == "true" ]]; then
    signature="hosted_runner_vm_loss"
  elif [[ "$suite_state" == "artifacts_uploaded" && "$verdict_present" == "true" ]]; then
    signature="none"
  elif [[ "$suite_state" == "completed" || "$phase" == "suite_completed" ]] \
       && [[ "$verdict_present" != "true" ]]; then
    signature="hosted_runner_artifact_upload_failure"
  elif [[ "$later_steps" == "true" && "$verdict_present" != "true" ]]; then
    signature="hosted_runner_process_death"
  elif [[ -f "$file" && "$verdict_present" != "true" && "$later_steps" != "true" ]]; then
    signature="hosted_runner_vm_loss"
  fi
  printf 'owner=%s\n' "$OWNER"
  printf 'signature=%s\n' "$signature"
  printf 'verdict_role=%s\n' "$VERDICT_ROLE"
  printf 'last_completed_class=%s\n' "$last_class"
  printf 'phase=%s\n' "$phase"
  printf 'suite_state=%s\n' "$suite_state"
}

cmd_collect_from_github() {
  local dest="${1:-}"
  [[ -n "$dest" ]] || { progress_fail_soft "collect-from-github needs DEST_DIR"; return 0; }
  mkdir -p "$dest" || return 0
  if [[ -n "$EXTERNAL_DIR" && -d "$EXTERNAL_DIR" ]]; then
    local src
    for src in "$EXTERNAL_DIR"/journey-progress-shard-*.txt; do
      [[ -f "$src" ]] || continue
      cp -f "$src" "$dest/" || true
    done
  fi
  [[ "${CI_JOURNEY_PROGRESS_DISABLE_GITHUB:-0}" == "1" ]] && return 0
  [[ -n "${GITHUB_TOKEN:-}" && -n "${GITHUB_REPOSITORY:-}" && -n "${GITHUB_SHA:-}" ]] || return 0
  command -v python3 >/dev/null 2>&1 || return 0
  CI_JOURNEY_PROGRESS_COLLECT_DIR="$dest" \
  CI_JOURNEY_PROGRESS_API="${GITHUB_API_URL:-https://api.github.com}" \
  python3 - <<'PY' || true
import json, os, urllib.error, urllib.request

dest = os.environ["CI_JOURNEY_PROGRESS_COLLECT_DIR"]
token = os.environ.get("GITHUB_TOKEN", "")
repo = os.environ.get("GITHUB_REPOSITORY", "")
sha = os.environ.get("GITHUB_SHA", "")
api = os.environ.get("CI_JOURNEY_PROGRESS_API", "https://api.github.com").rstrip("/")
url = "%s/repos/%s/commits/%s/check-runs?per_page=100" % (api, repo, sha)
request = urllib.request.Request(
    url,
    headers={
        "Authorization": "Bearer " + token,
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "pocketshell-ci-journey-progress",
    },
)
try:
    with urllib.request.urlopen(request, timeout=15) as response:
        body = json.loads(response.read().decode("utf-8"))
except (urllib.error.URLError, TimeoutError, json.JSONDecodeError, OSError):
    raise SystemExit(0)
for run in body.get("check_runs") or []:
    name = run.get("name") or ""
    if not name.startswith("journey-progress-shard-"):
        continue
    summary = ((run.get("output") or {}).get("summary") or "").strip()
    if not summary:
        continue
    safe = "".join(ch if ch.isalnum() or ch in "-._" else "_" for ch in name)
    path = os.path.join(dest, safe + ".txt")
    with open(path, "w", encoding="utf-8") as handle:
        handle.write(summary)
        if not summary.endswith("\n"):
            handle.write("\n")
PY
}

cmd_read() {
  local file="${1:-$LOCAL_FILE}"
  if [[ ! -f "$file" && -n "$EXTERNAL_DIR" ]]; then
    local shard total
    read -r shard total <<<"$(resolve_shard)"
    file="$EXTERNAL_DIR/$(progress_basename "$shard")"
  fi
  [[ -f "$file" ]] || return 0
  cat "$file"
}

case "$cmd" in
  start) cmd_start ;;
  class-started) cmd_class_started "${1-}" ;;
  class-completed) cmd_class_completed "${1-}" "${2-}" ;;
  suite-completed) cmd_suite_completed "${1-}" ;;
  artifacts-uploaded) cmd_artifacts_uploaded ;;
  observe-stream) cmd_observe_stream ;;
  classify) cmd_classify "${1-}" ;;
  collect-from-github) cmd_collect_from_github "${1-}" ;;
  read) cmd_read "${1-}" ;;
  ''|-h|--help|help)
    echo "usage: ci-journey-progress-telemetry.sh start|class-started|class-completed|suite-completed|artifacts-uploaded|observe-stream|classify|collect-from-github|read" >&2
    exit 2
    ;;
  *)
    progress_fail_soft "unknown command: $cmd"
    ;;
esac
exit 0
