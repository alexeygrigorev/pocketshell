"""Server-side usage limit/session reset detection (issue #690).

Builds on the hourly usage capture (#689). Each scheduled
``pocketshell usage --capture`` writes a cached latest reading
(``usage-latest.json``) and appends one entry to the history log
(``usage-history.jsonl``). This module compares the *current* reading to
the *previous* cached one and flags a **reset event** when a provider's
limit/session window has reset — usage dropped back toward baseline, or a
new window boundary started — **especially when it happens earlier than the
stated reset time**.

A reset event is recorded so the app (a later slice) and a
``pocketshell usage --reset-events`` read can surface
"limits reset at <time>". The FCM push delivery + the app-side notification
are explicitly OUT of scope for this slice; this is the detection + logging
foundation only.

What counts as a reset
----------------------

For each provider, and each window (``short_term`` / ``long_term``), we
compare the previous reading to the current one:

1. **Usage dropped back toward baseline.** ``percent_remaining`` jumped UP
   by at least :data:`RESET_RECOVERY_THRESHOLD` percentage points (e.g.
   from 8% remaining to 100% remaining). A fresh limit window resets the
   meter, so a large recovery is the strongest signal a reset happened.
2. **A new window boundary started.** The previous reading carried a
   ``reset_at`` and the current ``reset_at`` has advanced *past* the old
   one (the old window's deadline elapsed and the provider rolled to a new
   window). This catches a reset even when the percentage signal is noisy.

Either signal flags a reset. Both being present strengthens confidence but
is not required.

Early-vs-stated
---------------

The previous reading stated a ``reset_at`` (the provider's advertised reset
time). If the reset is *detected* (this capture's ``captured_at``) **before**
that stated time, it is an **early** reset — the interesting case the
maintainer cares about ("resume heavy work the moment limits actually
reset"). Otherwise it is ``on_or_after_stated``. The event records both the
detected time and the previously-stated time so the app can say
"limits reset at <detected>, ~Nm earlier than stated".

De-duplication (#619 don't-renotify principle)
----------------------------------------------

One reset event per *actual* reset. Two guards:

1. **Per-run guard.** Within a single capture, a provider+window can emit at
   most one event.
2. **Cross-run guard.** A reset event carries a ``reset_key`` derived from
   the provider, window, and the new window's identity (its ``reset_at``, or
   the detected time when no new ``reset_at`` is known). The detector
   suppresses any event whose ``reset_key`` already exists in the recent
   reset-events log, so the same reset is never re-flagged on the next hourly
   run (where ``percent_remaining`` is still high relative to the pre-reset
   reading, but it is the *same* window we already logged).
"""

from __future__ import annotations

import json
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Optional

from pocketshell.usage_capture import (
    NEW_FILE_MODE,
    UsagePaths,
    _append_history,
    _write_private,
    resolve_paths,
)

# Minimum jump (percentage points) in ``percent_remaining`` for the
# "usage dropped back toward baseline" reset signal. A real window reset
# restores most/all of the budget, so 30 points comfortably separates a
# reset from normal hour-to-hour noise (a heavy hour rarely *recovers* 30
# points without a reset).
RESET_RECOVERY_THRESHOLD = 30.0

# How many recent reset events to keep in the dedicated reset-events log.
# Resets are rare (a handful a day at most), so a small cap is plenty and
# keeps the cross-run de-dup scan trivial.
DEFAULT_RESET_EVENTS_MAX_LINES = 500

RESET_EVENTS_FILENAME = "usage-reset-events.jsonl"

# The windows we inspect on each provider record, in the app-facing schema
# written by ``normalize_usage_record``.
_WINDOWS = ("short_term", "long_term")


def reset_events_file(paths: UsagePaths) -> Path:
    """Return the dedicated reset-events log path for ``paths``."""
    return paths.usage_dir / RESET_EVENTS_FILENAME


def _parse_iso(value: Any) -> Optional[datetime]:
    """Parse an ISO-8601 UTC timestamp (``...Z``) into an aware datetime."""
    if not isinstance(value, str) or not value.strip():
        return None
    text = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.fromisoformat(text)
    except ValueError:
        return None
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    return parsed.astimezone(timezone.utc)


def _window_obj(record: Any, window: str) -> Optional[dict[str, Any]]:
    if not isinstance(record, dict):
        return None
    obj = record.get(window)
    return obj if isinstance(obj, dict) else None


def _records_by_provider(cache: Optional[dict[str, Any]]) -> dict[str, dict[str, Any]]:
    """Index a cache object's records by provider name (lower-cased)."""
    out: dict[str, dict[str, Any]] = {}
    if not isinstance(cache, dict):
        return out
    records = cache.get("records")
    if not isinstance(records, list):
        return out
    for record in records:
        if not isinstance(record, dict):
            continue
        provider = record.get("provider")
        if isinstance(provider, str) and provider.strip():
            out[provider.strip().lower()] = record
    return out


def _detect_window_reset(
    provider: str,
    window: str,
    previous: dict[str, Any],
    current: dict[str, Any],
    *,
    captured_at: str,
) -> Optional[dict[str, Any]]:
    """Return a reset event dict for one provider+window, or ``None``.

    ``previous``/``current`` are the window objects (``short_term`` etc.)
    from the previous and current readings. A reset is flagged when usage
    recovered toward baseline OR a new window boundary started.
    """
    prev_pct = previous.get("percent_remaining")
    cur_pct = current.get("percent_remaining")
    prev_reset_at = previous.get("reset_at")
    cur_reset_at = current.get("reset_at")

    prev_reset_dt = _parse_iso(prev_reset_at)
    cur_reset_dt = _parse_iso(cur_reset_at)
    captured_dt = _parse_iso(captured_at)

    # Signal 1: usage dropped back toward baseline (large recovery).
    recovery_reset = False
    if isinstance(prev_pct, (int, float)) and isinstance(cur_pct, (int, float)):
        if float(cur_pct) - float(prev_pct) >= RESET_RECOVERY_THRESHOLD:
            recovery_reset = True

    # Signal 2: a new window boundary started (reset_at advanced past the
    # previously-stated deadline). Requires both timestamps to compare.
    window_rolled = False
    if prev_reset_dt is not None and cur_reset_dt is not None:
        if cur_reset_dt > prev_reset_dt:
            window_rolled = True

    if not (recovery_reset or window_rolled):
        return None

    # Early-vs-stated: was the reset detected before the previously-stated
    # reset time? When we have no captured/stated time to compare, we cannot
    # claim "early" — default to on/after.
    early = False
    if captured_dt is not None and prev_reset_dt is not None:
        early = captured_dt < prev_reset_dt
    timing = "early" if early else "on_or_after_stated"

    minutes_early: Optional[int] = None
    if early and captured_dt is not None and prev_reset_dt is not None:
        delta = (prev_reset_dt - captured_dt).total_seconds()
        if delta > 0:
            minutes_early = int(delta // 60)

    # The detected reset time: when no new window deadline is known, the
    # detection moment (captured_at) is the best estimate of "reset at".
    detected_reset_at = captured_at

    # ``reset_key`` identifies the *new* window so the same reset is never
    # re-flagged. Prefer the new window's advertised reset_at; fall back to
    # the detected time when the provider gives no new deadline.
    key_part = cur_reset_at if isinstance(cur_reset_at, str) and cur_reset_at.strip() else detected_reset_at
    reset_key = f"{provider}|{window}|{key_part}"

    signals = []
    if recovery_reset:
        signals.append("recovery")
    if window_rolled:
        signals.append("window_rolled")

    return {
        "type": "reset",
        "provider": provider,
        "window": window,
        "detected_at": captured_at,
        "detected_reset_at": detected_reset_at,
        "stated_reset_at": prev_reset_at if isinstance(prev_reset_at, str) else None,
        "new_reset_at": cur_reset_at if isinstance(cur_reset_at, str) else None,
        "timing": timing,
        "minutes_early": minutes_early,
        "previous_percent_remaining": float(prev_pct) if isinstance(prev_pct, (int, float)) else None,
        "current_percent_remaining": float(cur_pct) if isinstance(cur_pct, (int, float)) else None,
        "signals": signals,
        "reset_key": reset_key,
    }


def detect_resets(
    previous_cache: Optional[dict[str, Any]],
    current_cache: dict[str, Any],
    *,
    known_reset_keys: Optional[set[str]] = None,
) -> list[dict[str, Any]]:
    """Compare two readings and return de-duplicated reset events.

    ``previous_cache`` is the last cached reading (``None`` on the very first
    capture — nothing to compare, so no events). ``current_cache`` is the
    fresh reading. ``known_reset_keys`` are the ``reset_key`` values already
    recorded in the reset-events log; any event whose key is already known is
    suppressed (cross-run de-dup).
    """
    if previous_cache is None:
        return []

    known = known_reset_keys if known_reset_keys is not None else set()
    captured_at = current_cache.get("captured_at")
    if not isinstance(captured_at, str):
        captured_at = ""

    prev_by_provider = _records_by_provider(previous_cache)
    cur_by_provider = _records_by_provider(current_cache)

    events: list[dict[str, Any]] = []
    seen_keys: set[str] = set()
    for provider, cur_record in cur_by_provider.items():
        prev_record = prev_by_provider.get(provider)
        if prev_record is None:
            continue
        for window in _WINDOWS:
            prev_window = _window_obj(prev_record, window)
            cur_window = _window_obj(cur_record, window)
            if prev_window is None or cur_window is None:
                continue
            event = _detect_window_reset(
                provider,
                window,
                prev_window,
                cur_window,
                captured_at=captured_at,
            )
            if event is None:
                continue
            key = event["reset_key"]
            # Per-run + cross-run de-dup: one event per reset_key.
            if key in known or key in seen_keys:
                continue
            seen_keys.add(key)
            events.append(event)
    return events


def read_reset_events(
    paths: Optional[UsagePaths] = None,
) -> list[dict[str, Any]]:
    """Return all recorded reset events (oldest first), or ``[]``."""
    if paths is None:
        paths = resolve_paths()
    path = reset_events_file(paths)
    if not path.exists():
        return []
    out: list[dict[str, Any]] = []
    try:
        text = path.read_text(encoding="utf-8")
    except OSError:
        return []
    for line in text.splitlines():
        if not line.strip():
            continue
        try:
            parsed = json.loads(line)
        except json.JSONDecodeError:
            continue
        if isinstance(parsed, dict):
            out.append(parsed)
    return out


def _known_reset_keys(paths: UsagePaths) -> set[str]:
    keys: set[str] = set()
    for event in read_reset_events(paths):
        key = event.get("reset_key")
        if isinstance(key, str):
            keys.add(key)
    return keys


def record_resets(
    previous_cache: Optional[dict[str, Any]],
    current_cache: dict[str, Any],
    *,
    paths: Optional[UsagePaths] = None,
    reset_events_max_lines: int = DEFAULT_RESET_EVENTS_MAX_LINES,
) -> list[dict[str, Any]]:
    """Detect resets, append new events to the reset-events log, and return them.

    Reads the existing reset-events log for cross-run de-dup so the same reset
    is never re-flagged on a later hourly run. Returns the list of NEW events
    written this run (empty when there was no reset, or every detected reset
    was already logged).
    """
    if paths is None:
        paths = resolve_paths()
    known = _known_reset_keys(paths)
    events = detect_resets(previous_cache, current_cache, known_reset_keys=known)
    if not events:
        return []
    path = reset_events_file(paths)
    for event in events:
        _append_history(path, event, history_max_lines=reset_events_max_lines)
    return events


def reset_events_document(paths: Optional[UsagePaths] = None) -> str:
    """Return the reset-events as the app-facing JSON document.

    Emits a single JSON object ``{"reset_events": [ {…}, … ]}`` (newest
    last). The app reads this to surface "limits reset at <time>" on next
    open (the non-push fallback) and a future push slice consumes the same
    detection output.
    """
    return json.dumps(
        {"reset_events": read_reset_events(paths)},
        sort_keys=True,
    ) + "\n"
