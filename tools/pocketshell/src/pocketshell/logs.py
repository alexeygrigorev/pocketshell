"""`pocketshell logs` subcommand group — canonical server-side event sink.

The dev box is the single canonical log host (Tier 1, locked decision
**D27**). This group is the **persistent, greppable record** of two
signals:

1. The in-app **action-assistant** action traces (`kind=agent_action`)
   and plain app / crash logs (`kind in {app_log, crash}`) — the phone
   pipes one JSON event over SSH into ``pocketshell logs ingest`` per
   meaningful action.
2. **Coding-agent engine events** (`kind=engine_event`) — Claude / Codex
   / OpenCode stop/idle/waiting signals, mirrored from the #267 hooks
   JSONL bus (``~/.cache/pocketshell/hooks/events.jsonl``) by
   ``pocketshell logs import-hooks``.

Why server-side (D27)
---------------------

Every meaningful assistant action is already a server-side ``pocketshell``
call over SSH (D19/D23 — zero provider credentials on the phone), so the
dev box is already the choke point. Volume is tiny (KB/day, append-only
JSONL). Putting the canonical sink here means the record:

- survives app deletion / reinstall (it lives on the server, not the
  phone),
- is readable even if the phone app crashes on startup — the orchestrator
  can ``rg`` the JSONL directly with no SDK, and
- adds **zero new credential surface** (the phone holds no cloud creds).

S3 / cloud was considered and rejected for Tier 1 (it adds AWS credential
brokering for ~no benefit at this volume). Off-box durability — a private
git mirror preferred over S3 — is deferred to a later Tier-2 issue and is
explicitly out of scope here.

Storage
-------

``${XDG_STATE_HOME:-~/.local/state}/pocketshell/logs/``:

- ``agent-YYYYMMDD.jsonl`` — ``kind in {agent_action, engine_event}``.
- ``app-YYYYMMDD.jsonl`` — ``kind in {app_log, crash}``.

Files are created mode ``0600`` (they may contain command lines and host
names). Dirs are created as needed.

Secret redaction (REQUIRED — aggressive, deny-by-default)
---------------------------------------------------------

``ingest`` redacts before anything is written. Secret values must NEVER
reach the file. Three independent passes:

- **secret-named keys** — any dict key matching ``*_KEY`` / ``*_TOKEN`` /
  ``*_SECRET`` / ``PASSWORD`` / ``SECRET`` / ``CREDENTIAL`` etc. has its
  value replaced with ``"<redacted>"``.
- **token-shaped strings** — any string value that looks like a provider
  token (``sk-…``, ``ghp_…``, long high-entropy base64/hex blobs, JWTs,
  AWS keys, …) is replaced.
- **inline ``KEY=value`` assignments** — inside any string (e.g. a
  ``run_command`` arg like ``export OPENAI_API_KEY=sk-…``) a
  secret-named assignment keeps the key but masks the value, so the
  record reads ``export OPENAI_API_KEY=<redacted>``.

Redaction walks the whole event recursively (nested dicts/lists), so a
secret cannot hide one level down.
"""

from __future__ import annotations

import json
import os
import re
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

import click

# Schema version stamped on every normalized record.
SCHEMA_VERSION = 1

# Recognised event kinds and the file family each lands in. ``agent`` and
# ``app`` are the two log families on disk.
AGENT_KINDS: tuple[str, ...] = ("agent_action", "engine_event")
APP_KINDS: tuple[str, ...] = ("app_log", "crash")
KNOWN_KINDS: tuple[str, ...] = AGENT_KINDS + APP_KINDS

# Recognised event sources.
KNOWN_SOURCES: tuple[str, ...] = ("phone", "cli")

# File permissions for any freshly-created log file. ``0600`` keeps the
# record readable only by the owning user — it can hold command lines.
NEW_FILE_MODE = 0o600

# Cursor file recording how far ``import-hooks`` has drained the hooks
# bus, so re-running it never duplicates engine events.
HOOKS_CURSOR_FILENAME = "hooks-import.cursor"

# Replacement token written in place of any redacted secret value.
REDACTED = "<redacted>"


# ---------------------------------------------------------------------------
# Path resolution (parametrized for tests — never touch the real home)
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class LogsPaths:
    """Resolved filesystem locations for the logs feature.

    Both the canonical logs dir and the hooks bus path are fields so the
    unit suite can point them at a tmp dir. Nothing in this module reads
    ``~`` directly — everything flows through here.
    """

    logs_dir: Path
    hooks_events_file: Path

    @property
    def cursor_file(self) -> Path:
        return self.logs_dir / HOOKS_CURSOR_FILENAME

    def agent_file(self, day: str) -> Path:
        return self.logs_dir / f"agent-{day}.jsonl"

    def app_file(self, day: str) -> Path:
        return self.logs_dir / f"app-{day}.jsonl"

    def file_for_kind(self, kind: str, day: str) -> Path:
        """Return the dated log file a given ``kind`` lands in."""
        if kind in APP_KINDS:
            return self.app_file(day)
        return self.agent_file(day)

    def files_for_family(self, family: str) -> list[Path]:
        """Return existing dated files for a log family (``agent``/``app``).

        Sorted by name, which is chronological because the date is
        zero-padded ``YYYYMMDD``.
        """
        if not self.logs_dir.exists():
            return []
        return sorted(self.logs_dir.glob(f"{family}-*.jsonl"))


def resolve_paths(
    *,
    home: Optional[Path] = None,
    env: Optional[dict[str, str]] = None,
) -> LogsPaths:
    """Return the :class:`LogsPaths` for the current (or given) environment.

    Precedence for the logs dir:

    1. ``$XDG_STATE_HOME/pocketshell/logs`` when ``$XDG_STATE_HOME`` is set.
    2. ``<home>/.local/state/pocketshell/logs``.

    The hooks bus path mirrors :func:`pocketshell.hooks.resolve_paths`:

    1. ``$POCKETSHELL_HOOKS_DIR/events.jsonl`` when set.
    2. ``<home>/.cache/pocketshell/hooks/events.jsonl``.
    """
    env_map = env if env is not None else os.environ
    base_home = home if home is not None else Path(os.path.expanduser("~"))

    xdg_state = env_map.get("XDG_STATE_HOME")
    if xdg_state:
        state_root = Path(os.path.expanduser(xdg_state))
    else:
        state_root = base_home / ".local" / "state"
    logs_dir = state_root / "pocketshell" / "logs"

    hooks_env = env_map.get("POCKETSHELL_HOOKS_DIR")
    if hooks_env:
        hooks_dir = Path(os.path.expanduser(hooks_env))
    else:
        hooks_dir = base_home / ".cache" / "pocketshell" / "hooks"
    hooks_events_file = hooks_dir / "events.jsonl"

    return LogsPaths(logs_dir=logs_dir, hooks_events_file=hooks_events_file)


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def _day_from_ts(ts: str) -> str:
    """Return the ``YYYYMMDD`` UTC day for an ISO-8601 ``ts``.

    Falls back to *today* (UTC) when ``ts`` cannot be parsed, so a record
    is never dropped just because its timestamp is odd.
    """
    try:
        parsed = datetime.fromisoformat(ts)
    except (ValueError, TypeError):
        return datetime.now(timezone.utc).strftime("%Y%m%d")
    if parsed.tzinfo is not None:
        parsed = parsed.astimezone(timezone.utc)
    return parsed.strftime("%Y%m%d")


# ---------------------------------------------------------------------------
# Secret redaction
# ---------------------------------------------------------------------------
#
# Deny-by-default: we would rather over-redact than ever persist a secret.

# A dict key (or an env var name in an inline assignment) whose *value*
# must be masked. Matches common secret suffixes/words case-insensitively.
_SECRET_KEY_RE = re.compile(
    r"(?i)(?:^|_)(?:key|token|secret|password|passwd|pwd|credential|"
    r"credentials|apikey|auth|access[_-]?key|private[_-]?key|session[_-]?token)$"
)
# A few standalone secret-ish names that don't end in the words above.
_SECRET_KEY_EXTRA = re.compile(r"(?i)^(?:password|passwd|pwd|secret|token|apikey|api_key)$")

# Token-shaped string values redacted regardless of the key they sit under.
_TOKEN_PATTERNS: tuple[re.Pattern[str], ...] = (
    re.compile(r"sk-[A-Za-z0-9_\-]{8,}"),               # OpenAI-style
    re.compile(r"sk-ant-[A-Za-z0-9_\-]{8,}"),           # Anthropic
    re.compile(r"gh[pousr]_[A-Za-z0-9]{16,}"),          # GitHub PAT family
    re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),        # GitHub fine-grained
    re.compile(r"xox[baprs]-[A-Za-z0-9\-]{10,}"),       # Slack
    re.compile(r"AKIA[0-9A-Z]{16}"),                    # AWS access key id
    re.compile(r"AIza[0-9A-Za-z_\-]{20,}"),             # Google API key
    re.compile(r"glpat-[A-Za-z0-9_\-]{16,}"),           # GitLab PAT
    re.compile(r"eyJ[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]{8,}\.[A-Za-z0-9_\-]+"),  # JWT
    # Generic long high-entropy blob (base64/hex). Length guard avoids
    # eating ordinary words; the charset requires at least mixed alnum.
    re.compile(r"\b[A-Za-z0-9+/]{40,}={0,2}\b"),
    re.compile(r"\b[A-Fa-f0-9]{40,}\b"),
)

# An inline ``KEY=value`` (optionally ``export KEY=value``) assignment
# whose KEY is secret-named — we keep the key, mask the value. Captures
# an optional ``export`` + leading-quote run so quoted values mask fully.
_INLINE_ASSIGN_RE = re.compile(
    r"((?:export\s+)?[A-Za-z_][A-Za-z0-9_]*)\s*=\s*(['\"]?)([^'\"\s]*)\2"
)


def _key_is_secret(key: str) -> bool:
    """True when a dict key / env var name denotes a secret value."""
    if not isinstance(key, str):
        return False
    return bool(_SECRET_KEY_RE.search(key) or _SECRET_KEY_EXTRA.search(key))


def _name_part_is_secret(name: str) -> bool:
    """True for an inline-assignment LHS like ``export OPENAI_API_KEY``.

    Strips a leading ``export`` token before the secret-name test.
    """
    bare = re.sub(r"^export\s+", "", name).strip()
    return _key_is_secret(bare)


def _redact_string(value: str) -> str:
    """Redact token-shaped substrings and inline secret assignments.

    Inline secret assignments are masked first (so the env var name is
    preserved as evidence — ``export OPENAI_API_KEY=<redacted>``), then
    any remaining token-shaped substrings anywhere in the string.
    """

    def _assign_sub(m: re.Match[str]) -> str:
        name = m.group(1)
        if _name_part_is_secret(name):
            return f"{name}={REDACTED}"
        return m.group(0)

    redacted = _INLINE_ASSIGN_RE.sub(_assign_sub, value)
    for pattern in _TOKEN_PATTERNS:
        redacted = pattern.sub(REDACTED, redacted)
    return redacted


def redact(obj: Any, *, parent_key: Optional[str] = None) -> Any:
    """Return a deep copy of ``obj`` with every secret value redacted.

    Walks dicts and lists recursively:

    - a value under a secret-named key becomes ``"<redacted>"`` outright;
    - any string value (anywhere) has token-shaped substrings and inline
      secret assignments masked.

    Non-container, non-string scalars (int/float/bool/None) pass through.
    """
    if isinstance(obj, dict):
        out: dict[str, Any] = {}
        for key, value in obj.items():
            if isinstance(key, str) and _key_is_secret(key):
                out[key] = REDACTED
            else:
                out[key] = redact(value, parent_key=key if isinstance(key, str) else None)
        return out
    if isinstance(obj, list):
        return [redact(item, parent_key=parent_key) for item in obj]
    if isinstance(obj, str):
        return _redact_string(obj)
    return obj


# ---------------------------------------------------------------------------
# Normalization + writing
# ---------------------------------------------------------------------------


def normalize_event(raw: dict[str, Any], *, default_source: str = "phone") -> dict[str, Any]:
    """Normalize an ingested event dict into the canonical schema.

    - Stamps ``ts`` (ISO-8601 UTC) when absent.
    - Stamps ``schema`` = :data:`SCHEMA_VERSION`.
    - Defaults ``source`` to ``default_source`` and ``kind`` to
      ``agent_action`` when missing/unknown so a malformed event still
      lands somewhere greppable rather than being dropped.
    - Coerces ``result`` to ``ok``/``error`` when present.
    - Redacts the whole record (deny-by-default) as the final step, so
      *nothing* written to disk can carry a secret value.

    The caller's keys are preserved (e.g. ``engine``, ``state``,
    ``session_id``, ``install_id``, ``target_host``, ``cwd``, ``detail``);
    only the normalized fields are forced.
    """
    event = dict(raw)

    ts = event.get("ts")
    if not isinstance(ts, str) or not ts.strip():
        ts = _now_iso()
    event["ts"] = ts

    event["schema"] = SCHEMA_VERSION

    source = event.get("source")
    if source not in KNOWN_SOURCES:
        source = default_source
    event["source"] = source

    kind = event.get("kind")
    if kind not in KNOWN_KINDS:
        kind = "agent_action"
    event["kind"] = kind

    result = event.get("result")
    if result is not None and result not in ("ok", "error"):
        event["result"] = "error" if str(result).lower() in ("err", "fail", "failed") else "ok"

    return redact(event)


def _append_jsonl(path: Path, record: dict[str, Any]) -> None:
    """Append one JSON record as a line to ``path`` (mode 0600 on create).

    The parent dir is created if needed. A pre-existing file keeps its
    perms; a freshly-created file is opened ``0600`` before any bytes are
    written.
    """
    path.parent.mkdir(parents=True, exist_ok=True)
    line = json.dumps(record, sort_keys=True) + "\n"
    if path.exists():
        with open(path, "a", encoding="utf-8") as handle:
            handle.write(line)
        return
    fd = os.open(str(path), os.O_WRONLY | os.O_CREAT | os.O_APPEND, NEW_FILE_MODE)
    with os.fdopen(fd, "a", encoding="utf-8") as handle:
        handle.write(line)


def ingest_event(
    paths: LogsPaths,
    raw: dict[str, Any],
    *,
    default_source: str = "phone",
) -> dict[str, Any]:
    """Normalize + redact ``raw`` and append it to the right dated file.

    Returns the normalized record that was written (already redacted) so
    callers/tests can assert on it without re-reading the file.
    """
    record = normalize_event(raw, default_source=default_source)
    day = _day_from_ts(record["ts"])
    target = paths.file_for_kind(record["kind"], day)
    _append_jsonl(target, record)
    return record


# ---------------------------------------------------------------------------
# Reading
# ---------------------------------------------------------------------------


def read_records(paths: LogsPaths, family: str, *, limit: Optional[int] = None) -> list[dict[str, Any]]:
    """Read records for a log ``family`` (``agent``/``app``), oldest first.

    Reads every dated file for the family in chronological order and
    concatenates their records. ``limit`` keeps only the last N records
    (most recent). Malformed/blank lines are skipped.
    """
    records: list[dict[str, Any]] = []
    for path in paths.files_for_family(family):
        for line in path.read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line:
                continue
            try:
                obj = json.loads(line)
            except (json.JSONDecodeError, ValueError):
                continue
            if isinstance(obj, dict):
                records.append(obj)
    if limit is not None and limit >= 0:
        records = records[-limit:]
    return records


# ---------------------------------------------------------------------------
# Source 2 — hooks-bus drainer (idempotent via a byte cursor)
# ---------------------------------------------------------------------------
#
# We mirror the #267 hooks bus into the canonical logs as kind=engine_event.
# The hooks bus stays the live event source (for the deferred supervisor
# UX); the canonical logs are the durable, greppable record.
#
# Idempotency: we persist a *byte offset* cursor of how far we have read
# the append-only bus. A re-run reads only the bytes after the cursor, so
# no engine event is ever forwarded twice — even mid-line writes are
# handled because we only advance the cursor to the last complete line.


def _read_cursor(paths: LogsPaths) -> int:
    if not paths.cursor_file.exists():
        return 0
    try:
        return int(paths.cursor_file.read_text(encoding="utf-8").strip() or "0")
    except (ValueError, OSError):
        return 0


def _write_cursor(paths: LogsPaths, offset: int) -> None:
    paths.logs_dir.mkdir(parents=True, exist_ok=True)
    if paths.cursor_file.exists():
        paths.cursor_file.write_text(str(offset), encoding="utf-8")
        return
    fd = os.open(str(paths.cursor_file), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, NEW_FILE_MODE)
    with os.fdopen(fd, "w", encoding="utf-8") as handle:
        handle.write(str(offset))


def _hook_record_to_event(rec: dict[str, Any], *, target_host: Optional[str]) -> dict[str, Any]:
    """Map a #267 hooks-bus record into a canonical engine_event.

    The hooks bus emits ``{ts, engine, state, source, session_id, cwd,
    ...}``. We carry those through, tag ``kind=engine_event`` and
    ``source=cli`` (the dev box produced it), set ``action`` to the
    engine state for greppability, and attach ``target_host`` (the
    canonical host) so every event has one. The full original payload is
    preserved under ``detail`` for debugging.
    """
    event: dict[str, Any] = {
        "ts": rec.get("ts"),
        "kind": "engine_event",
        "source": "cli",
        "engine": rec.get("engine"),
        "state": rec.get("state"),
        "action": rec.get("state") or "engine_event",
        "session_id": rec.get("session_id"),
        "cwd": rec.get("cwd"),
        "target_host": target_host,
        "result": "ok",
        "detail": rec,
    }
    return event


def import_hooks(paths: LogsPaths, *, target_host: Optional[str]) -> int:
    """Drain new hooks-bus lines into the canonical logs as engine_events.

    Idempotent: only bus bytes after the persisted cursor are read, and
    the cursor advances to the end of the last *complete* line consumed.
    Returns the number of engine events forwarded this run (0 on a
    re-run with no new bus activity).
    """
    bus = paths.hooks_events_file
    if not bus.exists():
        return 0

    data = bus.read_bytes()
    start = _read_cursor(paths)
    # Bus truncated/rotated under us — restart from the top.
    if start > len(data):
        start = 0

    chunk = data[start:]
    if not chunk:
        return 0

    # Only consume up to the last newline so a half-written final line is
    # left for the next run.
    last_nl = chunk.rfind(b"\n")
    if last_nl == -1:
        return 0
    consumable = chunk[: last_nl + 1]

    forwarded = 0
    for raw_line in consumable.decode("utf-8", errors="replace").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        try:
            rec = json.loads(line)
        except (json.JSONDecodeError, ValueError):
            continue
        if not isinstance(rec, dict):
            continue
        event = _hook_record_to_event(rec, target_host=target_host)
        ingest_event(paths, event, default_source="cli")
        forwarded += 1

    _write_cursor(paths, start + len(consumable))
    return forwarded


# ---------------------------------------------------------------------------
# Click surface
# ---------------------------------------------------------------------------


_KIND_OPTION = click.option(
    "--kind",
    "family",
    type=click.Choice(["agent", "app"]),
    default="agent",
    show_default=True,
    help="Which log family to act on (agent = action/engine events; app = app_log/crash).",
)


@click.group(
    name="logs",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Canonical server-side sink for assistant action traces, app/crash "
        "logs, and coding-agent engine events.\n\n"
        "`ingest` reads ONE JSON event from stdin, aggressively redacts "
        "secrets, stamps `ts`, and appends to a dated JSONL under "
        "`$XDG_STATE_HOME/pocketshell/logs` (0600). `tail` and `path` let "
        "the orchestrator read/grep the record directly. `import-hooks` "
        "mirrors the #267 hooks bus in as `engine_event`s (idempotent). "
        "Tier 1: single canonical host, no cloud. See D27 in "
        "docs/decisions.md."
    ),
)
def logs_group() -> None:
    """Top-level group registered onto the root `pocketshell` CLI."""


@logs_group.command(
    "ingest",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.option(
    "--json",
    "json_output",
    is_flag=True,
    help="Echo the normalized (redacted) record that was written.",
)
@click.pass_context
def logs_ingest(ctx: click.Context, json_output: bool) -> None:
    """Append ONE JSON event (read from stdin) to the canonical log.

    The event is normalized (schema/ts stamped, kind/source defaulted)
    and aggressively redacted before any byte hits disk: secret-named
    keys, token-shaped strings, and inline `KEY=value` secret
    assignments never persist. Secret values are NEVER echoed even with
    `--json` (the echoed record is the redacted one).
    """
    raw = sys.stdin.read()
    if not raw.strip():
        click.echo("pocketshell logs ingest: no JSON on stdin", err=True)
        ctx.exit(2)
    try:
        payload = json.loads(raw)
    except json.JSONDecodeError as exc:
        click.echo(f"pocketshell logs ingest: invalid JSON on stdin: {exc}", err=True)
        ctx.exit(2)
    if not isinstance(payload, dict):
        click.echo("pocketshell logs ingest: stdin JSON must be an object", err=True)
        ctx.exit(2)
    paths = resolve_paths()
    record = ingest_event(paths, payload)
    if json_output:
        click.echo(json.dumps(record, sort_keys=True))


@logs_group.command(
    "tail",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@_KIND_OPTION
@click.option(
    "-n",
    "--lines",
    "count",
    type=int,
    default=20,
    show_default=True,
    help="Number of most-recent records to print.",
)
@click.option(
    "--json",
    "json_output",
    is_flag=True,
    help="Emit a JSON array of records instead of one JSON line per record.",
)
def logs_tail(family: str, count: int, json_output: bool) -> None:
    """Print the most-recent records for a log family."""
    paths = resolve_paths()
    records = read_records(paths, family, limit=count)
    if json_output:
        click.echo(json.dumps(records, indent=2, sort_keys=True))
        return
    for record in records:
        click.echo(json.dumps(record, sort_keys=True))


@logs_group.command(
    "path",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@_KIND_OPTION
def logs_path(family: str) -> None:
    """Print today's log file path for a family so it can be grepped.

    Prints the dated path for the current UTC day. The directory and
    file may not yet exist (nothing has been ingested today); the path
    is still where today's records would land.
    """
    paths = resolve_paths()
    day = datetime.now(timezone.utc).strftime("%Y%m%d")
    if family == "app":
        click.echo(str(paths.app_file(day)))
    else:
        click.echo(str(paths.agent_file(day)))


@logs_group.command(
    "import-hooks",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.option(
    "--target-host",
    "target_host",
    type=str,
    default=None,
    help="Canonical host name to stamp on each forwarded engine event.",
)
def logs_import_hooks(target_host: Optional[str]) -> None:
    """Mirror new #267 hooks-bus events into the canonical log.

    Forwards every new coding-agent stop/idle/waiting event as
    `kind=engine_event`. Idempotent via a byte cursor: re-running with
    no new bus activity forwards nothing (no duplicates).
    """
    paths = resolve_paths()
    forwarded = import_hooks(paths, target_host=target_host)
    click.echo(f"forwarded {forwarded} engine event(s)")
