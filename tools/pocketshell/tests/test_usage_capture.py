"""Unit tests for the usage cache + history log (issue #689).

Covers:
 - cache write (usage-latest.json) + history append (usage-history.jsonl)
 - history rotation/line cap
 - XDG_STATE_HOME path resolution
 - the cached-document wire format
 - `pocketshell usage --capture` writes the cache + appends history
 - `pocketshell usage --cached` emits the cached document / exits 3 when empty
 - a failed live fetch is NOT cached
"""

from __future__ import annotations

import json
import subprocess
from pathlib import Path
from typing import Sequence
from unittest.mock import patch

from click.testing import CliRunner

from pocketshell import usage_capture
from pocketshell.cli import cli
from pocketshell.usage_capture import (
    UsagePaths,
    cached_document,
    read_cache,
    resolve_paths,
    write_capture,
)


# The flattened per-provider NDJSON `write_capture` consumes (pocketshell's
# own output format — one provider record per line).
_NDJSON = (
    '{"provider": "codex", "status": "ok", '
    '"short_term": {"percent_remaining": 77.0, "reset_at": "2026-06-11T15:00:00Z"}}\n'
    '{"provider": "claude", "status": "ok", '
    '"short_term": {"percent_remaining": 41.0, "reset_at": "2026-06-11T14:00:00Z"}}\n'
)

# quse v0.0.9's provider-keyed `--json` document — what `subprocess.run`
# returns when the CLI capture path shells out to the pinned quse. The
# `usage --capture` flow FLATTENS this into the NDJSON above before caching.
_QUSE_KEYED = json.dumps(
    {
        "codex": {
            "status": "ok",
            "short_term": {"percent_remaining": 77.0, "reset_at": "2026-06-11T15:00:00Z", "window": "5h"},
            "long_term": {"percent_remaining": 88.0, "reset_at": "2026-06-18T15:00:00Z", "window": "7d"},
            "error": None,
            "details": {},
        },
        "claude": {
            "status": "ok",
            "short_term": {"percent_remaining": 41.0, "reset_at": "2026-06-11T14:00:00Z", "window": "5h"},
            "long_term": {"percent_remaining": 85.0, "reset_at": "2026-06-18T14:00:00Z", "window": "7d"},
            "error": None,
            "details": {},
        },
    }
)


def _paths(tmp_path: Path) -> UsagePaths:
    return UsagePaths(usage_dir=tmp_path / "usage")


def test_resolve_paths_prefers_xdg_state_home(tmp_path: Path) -> None:
    paths = resolve_paths(env={"XDG_STATE_HOME": str(tmp_path / "state")})
    assert paths.usage_dir == tmp_path / "state" / "pocketshell" / "usage"
    assert paths.cache_file.name == "usage-latest.json"
    assert paths.history_file.name == "usage-history.jsonl"


def test_resolve_paths_falls_back_to_home_local_state(tmp_path: Path) -> None:
    paths = resolve_paths(home=tmp_path, env={})
    assert paths.usage_dir == tmp_path / ".local" / "state" / "pocketshell" / "usage"


def test_write_capture_writes_cache_and_appends_history(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    cache = write_capture(_NDJSON, paths=paths, captured_at="2026-06-11T09:00:00Z")

    # Cache file round-trips with two provider records + the timestamp.
    assert cache["captured_at"] == "2026-06-11T09:00:00Z"
    assert [r["provider"] for r in cache["records"]] == ["codex", "claude"]

    on_disk = json.loads(paths.cache_file.read_text())
    assert on_disk == cache

    # History has exactly one line for the one capture.
    history_lines = paths.history_file.read_text().splitlines()
    assert len(history_lines) == 1
    entry = json.loads(history_lines[0])
    assert entry["captured_at"] == "2026-06-11T09:00:00Z"
    assert [r["provider"] for r in entry["records"]] == ["codex", "claude"]


def test_write_capture_files_are_private(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    write_capture(_NDJSON, paths=paths)
    assert (paths.cache_file.stat().st_mode & 0o777) == 0o600
    assert (paths.history_file.stat().st_mode & 0o777) == 0o600


def test_history_appends_across_multiple_captures(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    write_capture(_NDJSON, paths=paths, captured_at="2026-06-11T09:00:00Z")
    write_capture(_NDJSON, paths=paths, captured_at="2026-06-11T10:00:00Z")
    write_capture(_NDJSON, paths=paths, captured_at="2026-06-11T11:00:00Z")

    history_lines = paths.history_file.read_text().splitlines()
    assert len(history_lines) == 3
    stamps = [json.loads(ln)["captured_at"] for ln in history_lines]
    assert stamps == [
        "2026-06-11T09:00:00Z",
        "2026-06-11T10:00:00Z",
        "2026-06-11T11:00:00Z",
    ]
    # The cache always reflects the LATEST capture.
    assert read_cache(paths)["captured_at"] == "2026-06-11T11:00:00Z"


def test_history_rotation_caps_to_max_lines(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    for i in range(10):
        write_capture(
            _NDJSON,
            paths=paths,
            captured_at=f"2026-06-11T{i:02d}:00:00Z",
            history_max_lines=3,
        )
    history_lines = paths.history_file.read_text().splitlines()
    assert len(history_lines) == 3
    stamps = [json.loads(ln)["captured_at"] for ln in history_lines]
    # Only the three most recent captures survive the trim.
    assert stamps == [
        "2026-06-11T07:00:00Z",
        "2026-06-11T08:00:00Z",
        "2026-06-11T09:00:00Z",
    ]


def test_cached_document_round_trips(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    write_capture(_NDJSON, paths=paths, captured_at="2026-06-11T09:00:00Z")
    document = usage_capture.cached_document(paths)
    assert document is not None
    obj = json.loads(document)
    assert obj["captured_at"] == "2026-06-11T09:00:00Z"
    assert [r["provider"] for r in obj["records"]] == ["codex", "claude"]


def test_cached_document_none_when_no_cache(tmp_path: Path) -> None:
    assert cached_document(_paths(tmp_path)) is None


def test_read_cache_tolerates_corrupt_file(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    paths.cache_file.parent.mkdir(parents=True)
    paths.cache_file.write_text("{ not json")
    assert read_cache(paths) is None


# ---------------------------------------------------------------------------
# CLI: `pocketshell usage --capture` / `--cached`
# ---------------------------------------------------------------------------


def _fake_completed(stdout: str = "", stderr: str = "", returncode: int = 0):
    return subprocess.CompletedProcess(args=["quse"], returncode=returncode, stdout=stdout, stderr=stderr)


def test_cli_capture_writes_cache_and_history(tmp_path: Path) -> None:
    env = {"XDG_STATE_HOME": str(tmp_path / "state")}
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/usr/bin/quse"), patch(
        "pocketshell.usage.subprocess.run", return_value=_fake_completed(stdout=_QUSE_KEYED)
    ), patch("pocketshell.usage._try_daemon_usage_fetch", return_value=None):
        result = CliRunner().invoke(cli, ["usage", "--capture", "--no-daemon"], env=env)

    assert result.exit_code == 0, result.output
    paths = resolve_paths(env=env)
    assert paths.cache_file.exists()
    cache = json.loads(paths.cache_file.read_text())
    assert [r["provider"] for r in cache["records"]] == ["codex", "claude"]
    assert len(paths.history_file.read_text().splitlines()) == 1
    # The command echoes the cache object so a cron log shows what landed.
    assert json.loads(result.output)["records"][0]["provider"] == "codex"


def test_cli_capture_does_not_cache_a_failed_fetch(tmp_path: Path) -> None:
    env = {"XDG_STATE_HOME": str(tmp_path / "state")}
    with patch("pocketshell.usage._resolve_quse_binary", return_value="/usr/bin/quse"), patch(
        "pocketshell.usage.subprocess.run",
        return_value=_fake_completed(stderr="boom\n", returncode=7),
    ), patch("pocketshell.usage._try_daemon_usage_fetch", return_value=None):
        result = CliRunner().invoke(cli, ["usage", "--capture", "--no-daemon"], env=env)

    assert result.exit_code == 7
    paths = resolve_paths(env=env)
    assert not paths.cache_file.exists()
    assert not paths.history_file.exists()


def test_cli_cached_emits_document(tmp_path: Path) -> None:
    env = {"XDG_STATE_HOME": str(tmp_path / "state")}
    paths = resolve_paths(env=env)
    write_capture(_NDJSON, paths=paths, captured_at="2026-06-11T09:00:00Z")

    result = CliRunner().invoke(cli, ["usage", "--cached"], env=env)
    assert result.exit_code == 0, result.output
    obj = json.loads(result.output)
    assert obj["captured_at"] == "2026-06-11T09:00:00Z"
    assert [r["provider"] for r in obj["records"]] == ["codex", "claude"]


def test_cli_cached_exits_3_when_no_capture(tmp_path: Path) -> None:
    env = {"XDG_STATE_HOME": str(tmp_path / "state")}
    result = CliRunner().invoke(cli, ["usage", "--cached"], env=env)
    assert result.exit_code == 3
    assert "no captured usage yet" in result.output
