"""Unit tests for usage limit/session reset detection (issue #690).

Covers:
 - reset detected: usage recovered toward baseline (percent jumped up)
 - reset detected: a new window boundary started (reset_at advanced)
 - no reset: normal usage decline / small noise / first capture
 - early-vs-stated: detected before vs on/after the previously-stated reset
 - de-dup: the same reset is flagged once across consecutive hourly runs
 - the reset event is embedded in the history entry by write_capture
 - the dedicated reset-events log + `pocketshell usage --reset-events` read
"""

from __future__ import annotations

import json
from pathlib import Path

from click.testing import CliRunner

from pocketshell import usage_reset
from pocketshell.cli import cli
from pocketshell.usage_capture import UsagePaths, resolve_paths, write_capture
from pocketshell.usage_reset import (
    detect_resets,
    read_reset_events,
    record_resets,
    reset_events_document,
    reset_events_file,
)


def _paths(tmp_path: Path) -> UsagePaths:
    return UsagePaths(usage_dir=tmp_path / "usage")


def _cache(
    captured_at: str,
    *,
    provider: str = "codex",
    percent: float | None = 40.0,
    reset_at: str | None = "2026-06-11T15:00:00Z",
    window: str = "short_term",
) -> dict:
    return {
        "captured_at": captured_at,
        "records": [
            {
                "provider": provider,
                "status": "ok",
                window: {
                    "percent_remaining": percent,
                    "reset_at": reset_at,
                    "window": "5h",
                },
            }
        ],
    }


# ---------------------------------------------------------------------------
# detect_resets — pure comparison logic
# ---------------------------------------------------------------------------


def test_no_reset_on_first_capture() -> None:
    current = _cache("2026-06-11T10:00:00Z", percent=40.0)
    assert detect_resets(None, current) == []


def test_no_reset_on_normal_usage_decline() -> None:
    previous = _cache("2026-06-11T10:00:00Z", percent=60.0)
    current = _cache("2026-06-11T11:00:00Z", percent=45.0)
    assert detect_resets(previous, current) == []


def test_no_reset_on_small_recovery_noise() -> None:
    # A small bump in percent_remaining (e.g. measurement noise) is NOT a reset.
    previous = _cache("2026-06-11T10:00:00Z", percent=40.0)
    current = _cache("2026-06-11T11:00:00Z", percent=50.0)  # +10 < threshold
    assert detect_resets(previous, current) == []


def test_reset_detected_on_recovery_to_baseline() -> None:
    # Usage dropped back toward baseline: 8% -> 100% remaining.
    previous = _cache("2026-06-11T14:30:00Z", percent=8.0)
    current = _cache("2026-06-11T15:30:00Z", percent=100.0, reset_at="2026-06-11T20:00:00Z")
    events = detect_resets(previous, current)
    assert len(events) == 1
    event = events[0]
    assert event["type"] == "reset"
    assert event["provider"] == "codex"
    assert event["window"] == "short_term"
    assert "recovery" in event["signals"]
    assert event["current_percent_remaining"] == 100.0
    assert event["previous_percent_remaining"] == 8.0


def test_reset_detected_on_new_window_boundary() -> None:
    # percent barely moves, but reset_at advanced to a new window.
    previous = _cache("2026-06-11T14:30:00Z", percent=70.0, reset_at="2026-06-11T15:00:00Z")
    current = _cache("2026-06-11T15:05:00Z", percent=72.0, reset_at="2026-06-11T20:00:00Z")
    events = detect_resets(previous, current)
    assert len(events) == 1
    assert "window_rolled" in events[0]["signals"]


def test_early_reset_flagged_when_before_stated_time() -> None:
    # Previous reading stated reset at 15:00; reset detected at 14:30 (early).
    previous = _cache("2026-06-11T14:00:00Z", percent=5.0, reset_at="2026-06-11T15:00:00Z")
    current = _cache("2026-06-11T14:30:00Z", percent=100.0, reset_at="2026-06-11T19:30:00Z")
    events = detect_resets(previous, current)
    assert len(events) == 1
    event = events[0]
    assert event["timing"] == "early"
    assert event["minutes_early"] == 30
    assert event["stated_reset_at"] == "2026-06-11T15:00:00Z"
    assert event["detected_at"] == "2026-06-11T14:30:00Z"


def test_on_or_after_stated_when_reset_at_expected_time() -> None:
    # Detected at/after the stated reset time -> not "early".
    previous = _cache("2026-06-11T14:50:00Z", percent=5.0, reset_at="2026-06-11T15:00:00Z")
    current = _cache("2026-06-11T15:05:00Z", percent=100.0, reset_at="2026-06-11T20:00:00Z")
    events = detect_resets(previous, current)
    assert len(events) == 1
    assert events[0]["timing"] == "on_or_after_stated"
    assert events[0]["minutes_early"] is None


def test_dedup_within_run_one_event_per_window() -> None:
    # Two identical reset signals collapsing to the same reset_key -> one event.
    previous = _cache("2026-06-11T14:00:00Z", percent=5.0, reset_at="2026-06-11T15:00:00Z")
    current = _cache("2026-06-11T14:30:00Z", percent=100.0, reset_at="2026-06-11T19:30:00Z")
    # Pre-seed the known keys with this reset's key -> suppressed.
    key = f"codex|short_term|2026-06-11T19:30:00Z"
    assert detect_resets(previous, current, known_reset_keys={key}) == []


# ---------------------------------------------------------------------------
# record_resets — appends to the reset-events log + cross-run de-dup
# ---------------------------------------------------------------------------


def test_record_resets_writes_event_and_dedups_across_runs(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    previous = _cache("2026-06-11T14:00:00Z", percent=5.0, reset_at="2026-06-11T15:00:00Z")
    current = _cache("2026-06-11T14:30:00Z", percent=100.0, reset_at="2026-06-11T19:30:00Z")

    # First run: the reset is recorded.
    events = record_resets(previous, current, paths=paths)
    assert len(events) == 1
    logged = read_reset_events(paths)
    assert len(logged) == 1
    assert logged[0]["timing"] == "early"

    # Next hourly run: still high relative to the pre-reset reading, SAME
    # window (reset_at unchanged) -> NO new event (cross-run de-dup).
    later = _cache("2026-06-11T15:30:00Z", percent=98.0, reset_at="2026-06-11T19:30:00Z")
    events2 = record_resets(current, later, paths=paths)
    assert events2 == []
    assert len(read_reset_events(paths)) == 1


def test_record_resets_logs_a_second_distinct_reset(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    prev1 = _cache("2026-06-11T14:00:00Z", percent=5.0, reset_at="2026-06-11T15:00:00Z")
    cur1 = _cache("2026-06-11T14:30:00Z", percent=100.0, reset_at="2026-06-11T19:30:00Z")
    record_resets(prev1, cur1, paths=paths)

    # A genuinely new reset later (different new reset_at -> different key).
    prev2 = _cache("2026-06-11T19:00:00Z", percent=4.0, reset_at="2026-06-11T19:30:00Z")
    cur2 = _cache("2026-06-11T19:35:00Z", percent=100.0, reset_at="2026-06-12T00:30:00Z")
    events = record_resets(prev2, cur2, paths=paths)
    assert len(events) == 1
    assert len(read_reset_events(paths)) == 2


def test_record_resets_returns_empty_when_no_reset(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    previous = _cache("2026-06-11T10:00:00Z", percent=60.0)
    current = _cache("2026-06-11T11:00:00Z", percent=45.0)
    assert record_resets(previous, current, paths=paths) == []
    assert not reset_events_file(paths).exists()


# ---------------------------------------------------------------------------
# write_capture integration: detect on the real capture path + de-dup
# ---------------------------------------------------------------------------


_NDJSON_LOW = (
    '{"provider": "codex", "status": "ok", '
    '"short_term": {"percent_remaining": 6.0, "reset_at": "2026-06-11T15:00:00Z"}}\n'
)
_NDJSON_RESET = (
    '{"provider": "codex", "status": "ok", '
    '"short_term": {"percent_remaining": 100.0, "reset_at": "2026-06-11T19:30:00Z"}}\n'
)


def test_write_capture_records_reset_in_history_and_log(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    # First capture: nothing to compare, no reset.
    write_capture(_NDJSON_LOW, paths=paths, captured_at="2026-06-11T14:30:00Z")
    assert read_reset_events(paths) == []

    # Second capture: usage recovered -> reset detected + embedded in history.
    write_capture(_NDJSON_RESET, paths=paths, captured_at="2026-06-11T14:45:00Z")

    logged = read_reset_events(paths)
    assert len(logged) == 1
    assert logged[0]["provider"] == "codex"
    assert logged[0]["timing"] == "early"

    history_lines = paths.history_file.read_text().splitlines()
    assert len(history_lines) == 2
    first_entry = json.loads(history_lines[0])
    second_entry = json.loads(history_lines[1])
    assert "reset_events" not in first_entry
    assert len(second_entry["reset_events"]) == 1
    assert second_entry["reset_events"][0]["provider"] == "codex"


def test_write_capture_dedups_reset_across_consecutive_runs(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    write_capture(_NDJSON_LOW, paths=paths, captured_at="2026-06-11T14:30:00Z")
    write_capture(_NDJSON_RESET, paths=paths, captured_at="2026-06-11T14:45:00Z")
    # A third run with the SAME (post-reset) window -> no new reset event.
    write_capture(_NDJSON_RESET, paths=paths, captured_at="2026-06-11T15:45:00Z")

    assert len(read_reset_events(paths)) == 1
    history_lines = paths.history_file.read_text().splitlines()
    third_entry = json.loads(history_lines[2])
    assert "reset_events" not in third_entry


# ---------------------------------------------------------------------------
# reset_events_document + CLI `--reset-events`
# ---------------------------------------------------------------------------


def test_reset_events_document_empty_when_none(tmp_path: Path) -> None:
    obj = json.loads(reset_events_document(_paths(tmp_path)))
    assert obj == {"reset_events": []}


def test_cli_reset_events_emits_document(tmp_path: Path) -> None:
    env = {"XDG_STATE_HOME": str(tmp_path / "state")}
    paths = resolve_paths(env=env)
    write_capture(_NDJSON_LOW, paths=paths, captured_at="2026-06-11T14:30:00Z")
    write_capture(_NDJSON_RESET, paths=paths, captured_at="2026-06-11T14:45:00Z")

    result = CliRunner().invoke(cli, ["usage", "--reset-events"], env=env)
    assert result.exit_code == 0, result.output
    obj = json.loads(result.output)
    assert len(obj["reset_events"]) == 1
    assert obj["reset_events"][0]["provider"] == "codex"
    assert obj["reset_events"][0]["timing"] == "early"


def test_cli_reset_events_empty_when_no_capture(tmp_path: Path) -> None:
    env = {"XDG_STATE_HOME": str(tmp_path / "state")}
    result = CliRunner().invoke(cli, ["usage", "--reset-events"], env=env)
    assert result.exit_code == 0, result.output
    assert json.loads(result.output) == {"reset_events": []}
