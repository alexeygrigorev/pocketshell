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
   Rewrites use a unique same-directory temp, fsync the data before the
   atomic rename, fsync the parent directory after the rename where the
   filesystem supports it, and serialize writers with an advisory lock.

Storage location
----------------

``${XDG_STATE_HOME:-~/.local/state}/pocketshell/usage/``:

- ``usage-latest.json`` — the cached latest reading (mode ``0600``).
- ``usage-history.jsonl`` — the append-only history log (mode ``0600``).
- ``usage-history-malformed.jsonl`` — bounded diagnostics for malformed
  capture/history lines (mode ``0600``), written only when needed.

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

from collections import deque
from contextlib import contextmanager
import errno
import json
import logging
import os
import tempfile
import threading
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterator, Optional

try:
    import fcntl
except ImportError:  # pragma: no cover - PocketShell's host is POSIX
    fcntl = None  # type: ignore[assignment]

# File permissions for the cache + history files. ``0600`` keeps the
# per-provider quota detail readable only by the owning user.
NEW_FILE_MODE = 0o600

# Default history line cap. ~1 capture/hour * 24 * ~83 days ≈ 2000 lines,
# each ~a few hundred bytes, so the file stays well under ~1 MB.
DEFAULT_HISTORY_MAX_LINES = 2000

CACHE_FILENAME = "usage-latest.json"
HISTORY_FILENAME = "usage-history.jsonl"
MALFORMED_HISTORY_FILENAME = "usage-history-malformed.jsonl"

# A malformed provider line must remain diagnosable without allowing a noisy
# producer to create an unbounded second log. The raw line is also clipped so
# a single broken output cannot consume the entire diagnostic budget.
DEFAULT_MALFORMED_MAX_LINES = 100
MAX_MALFORMED_LINE_LENGTH = 4096

# One lock file per state directory covers the usage history and its related
# append-only logs. It is coordination state, not user-visible history.
HISTORY_LOCK_FILENAME = ".usage-history.lock"

_LOGGER = logging.getLogger(__name__)
_UNSUPPORTED_SYNC_ERRNOS = frozenset(
    {
        errno.EINVAL,
        errno.ENOTSUP,
        errno.EOPNOTSUPP,
        errno.ENOSYS,
        errno.EISDIR,
    }
)

# `flock` is the process boundary; this lock prevents threads in one process
# from racing through the read/trim/publish transaction before they reach the
# kernel lock. The number of usage-state directories is tiny in practice.
_HISTORY_THREAD_LOCKS: dict[str, threading.Lock] = {}
_HISTORY_THREAD_LOCKS_GUARD = threading.Lock()


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

    @property
    def malformed_file(self) -> Path:
        return self.usage_dir / MALFORMED_HISTORY_FILENAME


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


def _is_unsupported_sync_error(error: OSError) -> bool:
    return error.errno in _UNSUPPORTED_SYNC_ERRNOS


def _durability_barrier(fd: int, *, path: Path, kind: str) -> bool:
    """Apply an fsync barrier, reporting supported-but-unavailable cases.

    Linux filesystems normally support both regular-file and directory fsync,
    but some network, virtual, or non-POSIX filesystems reject one of them
    with ``EINVAL``/``ENOTSUP``. Those filesystems still get atomic publication
    and a warning that durability is best-effort; unexpected I/O errors remain
    fatal so a real storage failure cannot be mistaken for a durable write.
    """
    while True:
        try:
            os.fsync(fd)
            return True
        except OSError as error:
            if error.errno == errno.EINTR:
                continue
            if _is_unsupported_sync_error(error):
                _LOGGER.warning(
                    "durability barrier unavailable for %s %s: %s; "
                    "continuing with atomic publication",
                    kind,
                    path,
                    error,
                )
                return False
            raise


def _fsync_directory(path: Path) -> bool:
    """Fsync a parent directory after rename when the filesystem permits it."""
    flags = os.O_RDONLY
    directory_flag = getattr(os, "O_DIRECTORY", 0)
    try:
        fd = os.open(str(path), flags | directory_flag)
    except OSError as error:
        if _is_unsupported_sync_error(error):
            _LOGGER.warning(
                "durability barrier unavailable for directory %s: %s; "
                "continuing with atomic publication",
                path,
                error,
            )
            return False
        raise
    try:
        return _durability_barrier(fd, path=path, kind="directory")
    finally:
        os.close(fd)


def _write_private(path: Path, text: str) -> None:
    """Write ``text`` to ``path`` atomically and as durably as possible.

    The temporary is created with ``mkstemp`` in the destination directory, so
    it is unique even when separate processes write at once and the eventual
    ``os.replace`` remains one-filesystem atomic. The temp's bytes and mode
    are fsync'd before publication; the parent directory is fsync'd after the
    rename. A filesystem that does not support one of those barriers is
    explicitly logged and still receives the atomic write, while unexpected
    storage errors fail closed and leave the previous destination intact.
    """
    _ensure_dir(path.parent)
    fd, tmp_name = tempfile.mkstemp(
        prefix=f".{path.name}.",
        suffix=".tmp",
        dir=str(path.parent),
    )
    tmp = Path(tmp_name)
    open_fd = fd
    try:
        with os.fdopen(fd, "wb") as handle:
            # Ownership of the descriptor has transferred to `handle`.
            open_fd = -1
            handle.write(text.encode("utf-8"))
            handle.flush()
            os.chmod(tmp, NEW_FILE_MODE)
            _durability_barrier(handle.fileno(), path=tmp, kind="file")
        os.replace(tmp, path)
        _fsync_directory(path.parent)
    except BaseException:
        if open_fd >= 0:
            try:
                os.close(open_fd)
            except OSError:
                pass
        try:
            tmp.unlink()
        except FileNotFoundError:
            pass
        except OSError:
            pass
        raise


def _history_thread_lock(history_file: Path) -> threading.Lock:
    lock_path = os.path.abspath(str(history_file.parent / HISTORY_LOCK_FILENAME))
    with _HISTORY_THREAD_LOCKS_GUARD:
        lock = _HISTORY_THREAD_LOCKS.get(lock_path)
        if lock is None:
            lock = threading.Lock()
            _HISTORY_THREAD_LOCKS[lock_path] = lock
        return lock


@contextmanager
def _history_writer_lock(history_file: Path) -> Iterator[None]:
    """Serialize a history read/trim/publish transaction across writers."""
    _ensure_dir(history_file.parent)
    lock_path = history_file.parent / HISTORY_LOCK_FILENAME
    thread_lock = _history_thread_lock(history_file)
    with thread_lock:
        if fcntl is None:
            raise RuntimeError(
                "cross-process history locking is unavailable; "
                "refusing an unsafe usage-history write"
            )
        fd = os.open(str(lock_path), os.O_RDWR | os.O_CREAT, NEW_FILE_MODE)
        locked = False
        try:
            try:
                fcntl.flock(fd, fcntl.LOCK_EX)
            except OSError as error:
                if error.errno not in {
                    errno.EINVAL,
                    errno.ENOTSUP,
                    errno.EOPNOTSUPP,
                    errno.ENOSYS,
                }:
                    raise
                raise RuntimeError(
                    "cross-process history locking is unavailable; "
                    f"refusing an unsafe usage-history write in {history_file.parent}"
                ) from error
            else:
                locked = True
            yield
        finally:
            if locked:
                fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)


def _truncate_malformed_line(line: str) -> str:
    if len(line) <= MAX_MALFORMED_LINE_LENGTH:
        return line
    return line[:MAX_MALFORMED_LINE_LENGTH] + "…[truncated]"


def _malformed_diagnostic(
    *, source: str, line_number: int, line: str, reason: str
) -> dict[str, Any]:
    return {
        "source": source,
        "line_number": line_number,
        "reason": reason,
        "raw_line": _truncate_malformed_line(line),
    }


def _default_malformed_file(history_file: Path) -> Path:
    if history_file.name == HISTORY_FILENAME:
        return history_file.with_name(MALFORMED_HISTORY_FILENAME)
    return history_file.with_name(history_file.name + ".malformed.jsonl")


def _append_quarantine_locked(
    quarantine_file: Path,
    diagnostics: list[dict[str, Any]],
    *,
    dropped_count: int = 0,
) -> None:
    """Append bounded diagnostic objects while the parent state lock is held.

    ``diagnostics`` is already the retained tail when the caller processed a
    large input. ``dropped_count`` carries the count of older diagnostics so
    the sidecar can retain an honest truncation marker without first building
    an unbounded list.
    """
    if not diagnostics:
        return
    _ensure_dir(quarantine_file.parent)
    # The sidecar is itself bounded, so retain only the tail while reading it.
    # This keeps a manually corrupted or very old sidecar from turning the
    # next capture into an unbounded read/alloc.
    retained = deque[dict[str, Any]](maxlen=DEFAULT_MALFORMED_MAX_LINES - 1)
    existing_count = 0
    try:
        diagnostic_stream = quarantine_file.open("r", encoding="utf-8")
    except FileNotFoundError:
        diagnostic_stream = None
    if diagnostic_stream is not None:
        with diagnostic_stream:
            for line_number, raw_line in enumerate(diagnostic_stream, start=1):
                raw_line = raw_line.rstrip("\r\n")
                if not raw_line.strip():
                    continue
                try:
                    parsed = json.loads(raw_line)
                except json.JSONDecodeError:
                    item = _malformed_diagnostic(
                        source=quarantine_file.name,
                        line_number=line_number,
                        line=raw_line,
                        reason="invalid_diagnostic_json",
                    )
                else:
                    if isinstance(parsed, dict):
                        item = dict(parsed)
                        raw_value = item.get("raw_line")
                        if isinstance(raw_value, str):
                            item["raw_line"] = _truncate_malformed_line(raw_value)
                    else:
                        item = _malformed_diagnostic(
                            source=quarantine_file.name,
                            line_number=line_number,
                            line=raw_line,
                            reason="diagnostic_not_object",
                        )
                existing_count += 1
                retained.append(item)

    all_count = existing_count + dropped_count + len(diagnostics)
    retained.extend(diagnostics)
    retained_list = list(retained)[-(DEFAULT_MALFORMED_MAX_LINES - 1) :]
    if all_count > DEFAULT_MALFORMED_MAX_LINES - 1:
        retained_list = [
            {
                "source": "pocketshell.usage_capture",
                "reason": "diagnostics_truncated",
            },
            *retained_list,
        ]
        retained_list[0]["dropped"] = all_count - (DEFAULT_MALFORMED_MAX_LINES - 1)
    _write_private(
        quarantine_file,
        "\n".join(json.dumps(item, sort_keys=True, ensure_ascii=False) for item in retained_list)
        + "\n",
    )


def _parse_ndjson_records(
    stdout: str,
    *,
    quarantine_file: Optional[Path] = None,
) -> list[dict[str, Any]]:
    """Parse ``pocketshell usage --json`` NDJSON stdout into a record list.

    Tolerant: skips blank lines and any line that is not a JSON object so a
    stray warning printed to stdout never wedges the capture. When a
    ``quarantine_file`` is supplied, skipped non-blank lines are recorded in a
    bounded sidecar rather than disappearing without evidence.
    """
    records: list[dict[str, Any]] = []
    diagnostics = deque[dict[str, Any]](maxlen=DEFAULT_MALFORMED_MAX_LINES - 1)
    malformed_count = 0

    def remember_malformed(diagnostic: dict[str, Any]) -> None:
        nonlocal malformed_count
        malformed_count += 1
        diagnostics.append(diagnostic)

    for line_number, line in enumerate(stdout.splitlines(), start=1):
        if not line.strip():
            continue
        try:
            parsed = json.loads(line)
        except json.JSONDecodeError:
            remember_malformed(
                _malformed_diagnostic(
                    source="capture-stdout",
                    line_number=line_number,
                    line=line,
                    reason="invalid_json",
                )
            )
            continue
        if isinstance(parsed, dict):
            records.append(parsed)
        else:
            remember_malformed(
                _malformed_diagnostic(
                    source="capture-stdout",
                    line_number=line_number,
                    line=line,
                    reason="line_not_object",
                )
            )
    if quarantine_file is not None and malformed_count:
        with _history_writer_lock(quarantine_file):
            _append_quarantine_locked(
                quarantine_file,
                list(diagnostics),
                dropped_count=malformed_count - len(diagnostics),
            )
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
    records = _parse_ndjson_records(stdout, quarantine_file=paths.malformed_file)

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
            # Push delivery (#690) is best-effort and fail-soft: a new reset
            # event triggers an FCM data push to the registered device, but a
            # missing credential / token / google-auth never breaks the hourly
            # capture (push_reset_events itself no-ops and never raises).
            from pocketshell import push as _push

            _push.push_reset_events(reset_events, paths=paths)
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
    quarantine_file: Optional[Path] = None,
) -> None:
    """Append ``entry`` as one JSON line, then trim to the line cap.

    The trim reads the existing tail and rewrites the file when it would
    exceed ``history_max_lines``. At ~1 capture/hour the file is tiny, so a
    full read+rewrite on each append is cheap and avoids an external
    logrotate dependency. The complete read/trim/publish transaction is held
    under the per-directory writer lock, so concurrent captures cannot lose a
    valid line or publish a mixed update. Existing malformed lines are
    quarantined before the rewrite.
    """
    quarantine_file = quarantine_file or _default_malformed_file(history_file)
    with _history_writer_lock(history_file):
        existing: deque[str] | list[str]
        if history_max_lines > 0:
            existing = deque(maxlen=history_max_lines)
        else:
            existing = []
        malformed = deque[dict[str, Any]](maxlen=DEFAULT_MALFORMED_MAX_LINES - 1)
        malformed_count = 0
        try:
            history_stream = history_file.open("r", encoding="utf-8")
        except FileNotFoundError:
            history_stream = None
        if history_stream is not None:
            with history_stream:
                for line_number, raw_line in enumerate(history_stream, start=1):
                    raw_line = raw_line.rstrip("\r\n")
                    if not raw_line.strip():
                        continue
                    try:
                        parsed = json.loads(raw_line)
                    except json.JSONDecodeError:
                        malformed_count += 1
                        malformed.append(
                            _malformed_diagnostic(
                                source=history_file.name,
                                line_number=line_number,
                                line=raw_line,
                                reason="invalid_json",
                            )
                        )
                        continue
                    if not isinstance(parsed, dict):
                        malformed_count += 1
                        malformed.append(
                            _malformed_diagnostic(
                                source=history_file.name,
                                line_number=line_number,
                                line=raw_line,
                                reason="line_not_object",
                            )
                        )
                        continue
                    existing.append(raw_line)

        if malformed:
            _append_quarantine_locked(
                quarantine_file,
                list(malformed),
                dropped_count=malformed_count - len(malformed),
            )

        existing.append(json.dumps(entry, sort_keys=True))
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
    return (
        json.dumps(
            {"captured_at": captured_at, "records": records},
            sort_keys=True,
        )
        + "\n"
    )
