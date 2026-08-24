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

import errno
import json
import logging
import multiprocessing
import subprocess
import time
from concurrent.futures import ProcessPoolExecutor, ThreadPoolExecutor
from pathlib import Path
from unittest.mock import patch

import pytest
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
    '"windows": {"5h": {"percent_remaining": 77.0, "reset_at": "2026-06-11T15:00:00Z"}}}\n'
    '{"provider": "claude", "status": "ok", '
    '"windows": {"5h": {"percent_remaining": 41.0, "reset_at": "2026-06-11T14:00:00Z"}}}\n'
)

# The published quse==0.0.14 provider-keyed `--json` document (old
# `short_term` / `long_term` schema) — what `subprocess.run` returns when the
# CLI capture path shells out to the pinned quse. The `usage --capture` flow
# translates this at the producer boundary, then caches canonical NDJSON.
_QUSE_KEYED = json.dumps(
    {
        "codex": {
            "status": "ok",
            "short_term": {
                "percent_remaining": 77.0,
                "reset_at": "2026-06-11T15:00:00Z",
                "window": "5h",
            },
            "long_term": {
                "percent_remaining": 88.0,
                "reset_at": "2026-06-18T15:00:00Z",
                "window": "7d",
            },
            "error": None,
            "details": {},
        },
        "claude": {
            "status": "ok",
            "short_term": {
                "percent_remaining": 41.0,
                "reset_at": "2026-06-11T14:00:00Z",
                "window": "5h",
            },
            "long_term": {
                "percent_remaining": 85.0,
                "reset_at": "2026-06-18T14:00:00Z",
                "window": "7d",
            },
            "error": None,
            "details": {},
        },
    }
)


def _paths(tmp_path: Path) -> UsagePaths:
    return UsagePaths(usage_dir=tmp_path / "usage")


def _append_history_in_process(args: tuple[str, str, int]) -> str:
    history_path, captured_at, history_max_lines = args
    usage_capture._append_history(
        Path(history_path),
        {"captured_at": captured_at},
        history_max_lines=history_max_lines,
    )
    return captured_at


def _write_capture_in_process(args: tuple[str, str, int]) -> str:
    usage_dir, captured_at, history_max_lines = args
    write_capture(
        json.dumps({"provider": captured_at, "status": "ok"}) + "\n",
        paths=UsagePaths(usage_dir=Path(usage_dir)),
        captured_at=captured_at,
        history_max_lines=history_max_lines,
    )
    return captured_at


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


def test_private_rewrite_uses_unique_temp_and_durability_barriers(
    tmp_path: Path, monkeypatch
) -> None:
    target = tmp_path / "usage" / "history.jsonl"
    replaced: list[tuple[Path, Path]] = []
    real_replace = usage_capture.os.replace
    real_fsync = usage_capture.os.fsync
    fsync_calls: list[int] = []

    def record_replace(source, destination):
        replaced.append((Path(source), Path(destination)))
        return real_replace(source, destination)

    def record_fsync(fd: int) -> None:
        fsync_calls.append(fd)
        real_fsync(fd)

    monkeypatch.setattr(usage_capture.os, "replace", record_replace)
    monkeypatch.setattr(usage_capture.os, "fsync", record_fsync)

    usage_capture._write_private(target, "first\n")
    usage_capture._write_private(target, "second\n")

    assert [destination for _, destination in replaced] == [target, target]
    assert len({source for source, _ in replaced}) == 2
    assert all(source.parent == target.parent for source, _ in replaced)
    assert len(fsync_calls) >= 4, "each rewrite needs file and directory barriers"
    assert target.read_text(encoding="utf-8") == "second\n"
    assert (target.stat().st_mode & 0o777) == 0o600
    assert list(target.parent.glob("*.tmp")) == []


def test_unsupported_fsync_is_explicitly_best_effort(
    tmp_path: Path, monkeypatch, caplog: pytest.LogCaptureFixture
) -> None:
    target = tmp_path / "usage" / "cache.json"

    def unsupported(_fd: int) -> None:
        raise OSError(errno.EINVAL, "fsync is not supported")

    monkeypatch.setattr(usage_capture.os, "fsync", unsupported)
    with caplog.at_level(logging.WARNING, logger=usage_capture.__name__):
        usage_capture._write_private(target, "durable enough to publish\n")

    assert target.read_text(encoding="utf-8") == "durable enough to publish\n"
    assert "durability barrier unavailable" in caplog.text


def test_history_write_fails_closed_without_cross_process_locking(
    tmp_path: Path, monkeypatch
) -> None:
    history = tmp_path / "usage-history.jsonl"
    monkeypatch.setattr(usage_capture, "fcntl", None)

    with pytest.raises(RuntimeError, match="refusing an unsafe"):
        usage_capture._append_history(
            history,
            {"captured_at": "2026-06-11T09:00:00Z"},
            history_max_lines=10,
        )

    assert not history.exists()


def test_history_preserves_prior_valid_lines_when_write_fails_before_publish(
    tmp_path: Path, monkeypatch
) -> None:
    history = tmp_path / "usage-history.jsonl"
    usage_capture._append_history(
        history,
        {"captured_at": "2026-06-11T09:00:00Z"},
        history_max_lines=10,
    )
    real_write = usage_capture._write_private

    def fail_history_write(path: Path, text: str) -> None:
        if Path(path) == history:
            raise OSError("simulated crash before history publish")
        real_write(path, text)

    monkeypatch.setattr(usage_capture, "_write_private", fail_history_write)
    with pytest.raises(OSError, match="before history publish"):
        usage_capture._append_history(
            history,
            {"captured_at": "2026-06-11T10:00:00Z"},
            history_max_lines=10,
        )

    assert [json.loads(line)["captured_at"] for line in history.read_text().splitlines()] == [
        "2026-06-11T09:00:00Z"
    ]
    assert list(history.parent.glob("*.tmp")) == []


def test_history_preserves_prior_valid_lines_when_rename_is_interrupted(
    tmp_path: Path, monkeypatch
) -> None:
    history = tmp_path / "usage-history.jsonl"
    usage_capture._append_history(
        history,
        {"captured_at": "2026-06-11T09:00:00Z"},
        history_max_lines=10,
    )
    real_replace = usage_capture.os.replace
    real_fsync = usage_capture.os.fsync
    fsync_calls: list[int] = []

    def record_fsync(fd: int) -> None:
        fsync_calls.append(fd)
        real_fsync(fd)

    def crash_before_rename(source, destination):
        if Path(destination) == history:
            raise KeyboardInterrupt("simulated crash after temp fsync")
        return real_replace(source, destination)

    monkeypatch.setattr(usage_capture.os, "fsync", record_fsync)
    monkeypatch.setattr(usage_capture.os, "replace", crash_before_rename)
    with pytest.raises(KeyboardInterrupt, match="after temp fsync"):
        usage_capture._append_history(
            history,
            {"captured_at": "2026-06-11T10:00:00Z"},
            history_max_lines=10,
        )

    assert len(fsync_calls) >= 1
    assert [json.loads(line)["captured_at"] for line in history.read_text().splitlines()] == [
        "2026-06-11T09:00:00Z"
    ]
    assert list(history.parent.glob("*.tmp")) == []


def test_concurrent_history_append_and_trim_keeps_complete_updates(
    tmp_path: Path, monkeypatch
) -> None:
    history = tmp_path / "usage-history.jsonl"
    real_write = usage_capture._write_private

    def slow_history_write(path: Path, text: str) -> None:
        if Path(path) == history:
            # Give every unsynchronised reader time to take the same snapshot.
            time.sleep(0.02)
        real_write(path, text)

    monkeypatch.setattr(usage_capture, "_write_private", slow_history_write)
    stamps = [f"2026-06-11T{i:02d}:00:00Z" for i in range(16)]

    def append(stamp: str) -> None:
        usage_capture._append_history(
            history,
            {"captured_at": stamp},
            history_max_lines=len(stamps),
        )

    with ThreadPoolExecutor(max_workers=len(stamps)) as executor:
        list(executor.map(append, stamps))

    entries = [json.loads(line) for line in history.read_text().splitlines()]
    assert len(entries) == len(stamps)
    assert {entry["captured_at"] for entry in entries} == set(stamps)


def test_cross_process_history_append_and_trim_is_serialized(tmp_path: Path) -> None:
    history = tmp_path / "usage-history.jsonl"
    stamps = [f"2026-06-11T{i:02d}:00:00Z" for i in range(24)]
    max_lines = 8
    work = [(str(history), stamp, max_lines) for stamp in stamps]

    # Threads only prove the in-process guard. Spawned workers exercise the
    # advisory flock that protects independent scheduled/manual captures.
    context = multiprocessing.get_context("spawn")
    with ProcessPoolExecutor(max_workers=8, mp_context=context) as executor:
        assert set(executor.map(_append_history_in_process, work)) == set(stamps)

    entries = [json.loads(line) for line in history.read_text(encoding="utf-8").splitlines()]
    assert len(entries) == max_lines
    assert len({entry["captured_at"] for entry in entries}) == max_lines
    assert all(set(entry) == {"captured_at"} for entry in entries)
    assert list(history.parent.glob("*.tmp")) == []


def test_cross_process_capture_keeps_cache_and_history_complete(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    stamps = [f"2026-06-11T{i:02d}:00:00Z" for i in range(12)]
    work = [(str(paths.usage_dir), stamp, len(stamps)) for stamp in stamps]

    # The history transaction is locked independently from the cache's
    # atomic publication. Prove both artifacts remain complete and that the
    # final cache is one of the captures represented in history.
    context = multiprocessing.get_context("spawn")
    with ProcessPoolExecutor(max_workers=6, mp_context=context) as executor:
        assert set(executor.map(_write_capture_in_process, work)) == set(stamps)

    history_entries = [
        json.loads(line) for line in paths.history_file.read_text(encoding="utf-8").splitlines()
    ]
    assert {entry["captured_at"] for entry in history_entries} == set(stamps)
    cache = read_cache(paths)
    assert cache is not None
    assert cache["captured_at"] in stamps
    assert cache["records"] == [{"provider": cache["captured_at"], "status": "ok"}]
    assert list(paths.usage_dir.glob("*.tmp")) == []


def test_malformed_capture_lines_are_quarantined_and_bounded(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    malformed_lines = [f"not-json-{i}" for i in range(120)]
    stdout = "\n".join(malformed_lines + [_NDJSON.splitlines()[0]]) + "\n"

    cache = write_capture(stdout, paths=paths, captured_at="2026-06-11T09:00:00Z")

    assert [record["provider"] for record in cache["records"]] == ["codex"]
    diagnostics = [
        json.loads(line) for line in paths.malformed_file.read_text(encoding="utf-8").splitlines()
    ]
    assert len(diagnostics) <= usage_capture.DEFAULT_MALFORMED_MAX_LINES
    assert any(item.get("raw_line") == "not-json-119" for item in diagnostics)
    assert any(item.get("reason") == "diagnostics_truncated" for item in diagnostics)
    assert (paths.malformed_file.stat().st_mode & 0o777) == 0o600


def test_malformed_history_lines_are_quarantined_before_rotation(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    valid_before = {"captured_at": "2026-06-11T09:00:00Z"}
    valid_after = {"captured_at": "2026-06-11T10:00:00Z"}
    paths.history_file.parent.mkdir(parents=True)
    paths.history_file.write_text(
        "\n".join([json.dumps(valid_before), "truncated {", json.dumps(valid_after)]) + "\n",
        encoding="utf-8",
    )

    usage_capture._append_history(
        paths.history_file,
        {"captured_at": "2026-06-11T11:00:00Z"},
        history_max_lines=3,
    )

    entries = [json.loads(line) for line in paths.history_file.read_text().splitlines()]
    assert [entry["captured_at"] for entry in entries] == [
        "2026-06-11T09:00:00Z",
        "2026-06-11T10:00:00Z",
        "2026-06-11T11:00:00Z",
    ]
    diagnostics = [
        json.loads(line) for line in paths.malformed_file.read_text(encoding="utf-8").splitlines()
    ]
    assert any(
        item.get("source") == "usage-history.jsonl" and item.get("raw_line") == "truncated {"
        for item in diagnostics
    )


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
    return subprocess.CompletedProcess(
        args=["quse"], returncode=returncode, stdout=stdout, stderr=stderr
    )


def test_cli_capture_writes_cache_and_history(tmp_path: Path) -> None:
    env = {"XDG_STATE_HOME": str(tmp_path / "state")}
    with (
        patch("pocketshell.usage._resolve_quse_binary", return_value="/usr/bin/quse"),
        patch("pocketshell.usage.subprocess.run", return_value=_fake_completed(stdout=_QUSE_KEYED)),
        patch("pocketshell.usage._try_daemon_usage_fetch", return_value=None),
    ):
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
    with (
        patch("pocketshell.usage._resolve_quse_binary", return_value="/usr/bin/quse"),
        patch(
            "pocketshell.usage.subprocess.run",
            return_value=_fake_completed(stderr="boom\n", returncode=7),
        ),
        patch("pocketshell.usage._try_daemon_usage_fetch", return_value=None),
    ):
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
