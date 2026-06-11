"""Server-side usage cache + history log (issue #689).

Stale-while-revalidate plumbing for the Android usage screen. The host
captures provider usage on a schedule (cron / systemd timer — server-side
scheduling is fine; D21 foreground-only applies to the Android app, not
the host CLI) and persists two artifacts:

1. **Cached latest reading** — ``usage-latest.json``, a single JSON object
   holding the most recent ``pocketshell usage --json`` NDJSON output plus
   a ``captured_at`` UTC timestamp. The app reads this and renders it
   *instantly* with a "last captured at <time>" label, then refreshes live
   in the foreground.
2. **Append-only history log** — ``usage-history.jsonl``, one JSON object
   per capture (``{"captured_at": ..., "records": [...]}``). Powers usage
   tracking over time and the future reset-detection follow-up. The log is
   size-bounded (line cap with rotation) so it never grows without limit.

Storage location
----------------

``${XDG_STATE_HOME:-~/.local/state}/pocketshell/usage/``:

- ``usage-latest.json`` — the cached latest reading (mode ``0600``).
- ``usage-history.jsonl`` — the append-only history log (mode ``0600``).

This mirrors :mod:`pocketshell.logs`' XDG-state convention so all
PocketShell server state lives under one root. Files are ``0600`` because
they carry per-provider quota detail.

History bound
-------------

Each append trims the history file to the most recent
:data:`DEFAULT_HISTORY_MAX_LINES` lines. At ~1 capture/hour that is ~83
days of hourly history in a file that stays well under ~1 MB. The trim is
in-place (read tail, rewrite) which is simple and correct for this volume;
no external logrotate dependency.
"""

from __future__ import annotations

import json
import os
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

# File permissions for the cache + history files. ``0600`` keeps the
# per-provider quota detail readable only by the owning user.
NEW_FILE_MODE = 0o600

# Default history line cap. ~1 capture/hour * 24 * ~83 days ≈ 2000 lines,
# each ~a few hundred bytes, so the file stays well under ~1 MB.
DEFAULT_HISTORY_MAX_LINES = 2000

CACHE_FILENAME = "usage-latest.json"
HISTORY_FILENAME = "usage-history.jsonl"


@dataclass(frozen=True)
class UsagePaths:
    """Resolved filesystem locations for the usage cache + history.

    Both paths are fields so the unit suite can point them at a tmp dir.
    Nothing in this module reads ``~`` directly — everything flows through
    :func:`resolve_paths`.
    """

    usage_dir: Path

    @property
    def cache_file(self) -> Path:
        return self.usage_dir / CACHE_FILENAME

    @property
    def history_file(self) -> Path:
        return self.usage_dir / HISTORY_FILENAME


def resolve_paths(
    *,
    home: Optional[Path] = None,
    env: Optional[dict[str, str]] = None,
) -> UsagePaths:
    """Return the :class:`UsagePaths` for the current (or given) environment.

    Precedence for the usage state dir:

    1. ``$XDG_STATE_HOME/pocketshell/usage`` when ``$XDG_STATE_HOME`` is set.
    2. ``<home>/.local/state/pocketshell/usage``.
    """
    env_map = env if env is not None else os.environ
    base_home = home if home is not None else Path(os.path.expanduser("~"))

    xdg_state = env_map.get("XDG_STATE_HOME")
    if xdg_state:
        state_root = Path(os.path.expanduser(xdg_state))
    else:
        state_root = base_home / ".local" / "state"
    usage_dir = state_root / "pocketshell" / "usage"
    return UsagePaths(usage_dir=usage_dir)


def _now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def _ensure_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(path, 0o700)
    except PermissionError:
        pass


def _write_private(path: Path, text: str) -> None:
    """Write ``text`` to ``path`` atomically with mode 0600.

    A temp file + ``os.replace`` keeps a concurrent reader (the app's SSH
    fetch racing the scheduled capture) from ever seeing a half-written
    cache file.
    """
    _ensure_dir(path.parent)
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, NEW_FILE_MODE)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(text)
    except BaseException:
        try:
            os.unlink(tmp)
        except FileNotFoundError:
            pass
        raise
    os.replace(tmp, path)
    try:
        os.chmod(path, NEW_FILE_MODE)
    except FileNotFoundError:
        pass


def _parse_ndjson_records(stdout: str) -> list[dict[str, Any]]:
    """Parse ``pocketshell usage --json`` NDJSON stdout into a record list.

    Tolerant: skips blank lines and any line that is not a JSON object so a
    stray warning printed to stdout never wedges the capture.
    """
    records: list[dict[str, Any]] = []
    for line in stdout.splitlines():
        if not line.strip():
            continue
        try:
            parsed = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(parsed, dict):
            records.append(parsed)
    return records


def write_capture(
    stdout: str,
    *,
    paths: Optional[UsagePaths] = None,
    captured_at: Optional[str] = None,
    history_max_lines: int = DEFAULT_HISTORY_MAX_LINES,
) -> dict[str, Any]:
    """Persist a fresh capture: write the cache + append to history.

    ``stdout`` is the raw NDJSON ``pocketshell usage --json`` output. Returns
    the cache object that was written (also useful for the ``--capture``
    command to emit so the operator/cron can see what landed).
    """
    if paths is None:
        paths = resolve_paths()
    captured_at = captured_at or _now_iso()
    records = _parse_ndjson_records(stdout)

    # Read the PREVIOUS cached reading BEFORE we overwrite it, so reset
    # detection (#690) can compare the current reading to the last one.
    previous_cache = read_cache(paths)

    cache_obj: dict[str, Any] = {
        "captured_at": captured_at,
        "records": records,
    }
    _write_private(paths.cache_file, json.dumps(cache_obj, sort_keys=True) + "\n")

    # Reset detection (#690) is best-effort: a bad reading must never wedge
    # the #689 cache write the app's stale-while-revalidate render depends
    # on, so the whole detect+log step is wrapped and swallows errors. New
    # reset events are appended to the dedicated reset-events log (the
    # de-dup source of truth) and embedded in this capture's history entry.
    history_entry: dict[str, Any] = cache_obj
    try:
        # Lazy import avoids the usage_capture <-> usage_reset circular import.
        from pocketshell import usage_reset as _reset

        reset_events = _reset.record_resets(
            previous_cache,
            cache_obj,
            paths=paths,
        )
        if reset_events:
            history_entry = {**cache_obj, "reset_events": reset_events}
    except Exception:
        history_entry = cache_obj

    _append_history(
        paths.history_file,
        history_entry,
        history_max_lines=history_max_lines,
    )
    return cache_obj


def _append_history(
    history_file: Path,
    entry: dict[str, Any],
    *,
    history_max_lines: int,
) -> None:
    """Append ``entry`` as one JSON line, then trim to the line cap.

    The trim reads the existing tail and rewrites the file when it would
    exceed ``history_max_lines``. At ~1 capture/hour the file is tiny, so a
    full read+rewrite on each append is cheap and avoids an external
    logrotate dependency.
    """
    _ensure_dir(history_file.parent)
    line = json.dumps(entry, sort_keys=True)

    existing: list[str] = []
    if history_file.exists():
        try:
            existing = [
                ln for ln in history_file.read_text(encoding="utf-8").splitlines()
                if ln.strip()
            ]
        except OSError:
            existing = []
    existing.append(line)

    if history_max_lines > 0 and len(existing) > history_max_lines:
        existing = existing[-history_max_lines:]

    _write_private(history_file, "\n".join(existing) + "\n")


def read_cache(paths: Optional[UsagePaths] = None) -> Optional[dict[str, Any]]:
    """Return the cached latest reading, or ``None`` if absent/unreadable."""
    if paths is None:
        paths = resolve_paths()
    cache_file = paths.cache_file
    if not cache_file.exists():
        return None
    try:
        parsed = json.loads(cache_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if not isinstance(parsed, dict):
        return None
    return parsed


def cached_document(paths: Optional[UsagePaths] = None) -> Optional[str]:
    """Return the cached reading as the app-facing JSON document.

    Unlike the live ``pocketshell usage --json`` path (which emits NDJSON,
    one provider per line), the cached read emits a SINGLE JSON object:

    .. code-block:: json

       {"captured_at": "2026-06-11T09:00:00Z", "records": [ {…}, {…} ]}

    The app reads this for an instant cached-first render: ``captured_at``
    powers the "last captured at <time>" label and ``records`` are the same
    provider objects the live NDJSON carries (the app parses each record
    with the same NDJSON parser by re-emitting them line by line).

    Returns ``None`` when there is no cache yet.
    """
    cache = read_cache(paths)
    if cache is None:
        return None
    records = cache.get("records")
    if not isinstance(records, list):
        records = []
    captured_at = cache.get("captured_at")
    return json.dumps(
        {"captured_at": captured_at, "records": records},
        sort_keys=True,
    ) + "\n"
