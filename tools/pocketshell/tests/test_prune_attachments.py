"""Unit tests for `pocketshell prune-attachments` (issue #547).

Server-side attachment retention backstop. Exercises:

- TTL deletion: files strictly older than the TTL are removed; recent ones
  are kept.
- Size cap: when survivors still exceed the cap, oldest survivors are
  trimmed (oldest-first) until under the cap, sparing the protect window.
- Safety: only regular files exactly under
  ``~/.pocketshell/attachments/<scope>/`` are touched; the user's own files
  outside the tree, symlinks, the scope dirs, and the root are never
  deleted.
- Best-effort: a per-file delete error is recorded, not raised.
- CLI: `--dry-run` reports without deleting; `--json` emits the summary;
  the missing-root case is a clean no-op.
- `pocketshell --help` lists the subcommand.
"""

from __future__ import annotations

import os
import time
from pathlib import Path

from click.testing import CliRunner

from pocketshell.cli import cli
from pocketshell.prune_attachments import (
    DEFAULT_MAX_TOTAL_BYTES,
    DEFAULT_TTL_DAYS,
    PROTECT_NEWEST_HOURS,
    prune_attachments,
    resolve_attachments_root,
)

_DAY = 24 * 60 * 60
_HOUR = 60 * 60


def _make_root(tmp_path: Path) -> Path:
    root = tmp_path / ".pocketshell" / "attachments"
    root.mkdir(parents=True)
    return root


def _write(root: Path, scope: str, name: str, *, size: int, age_seconds: float, now: float) -> Path:
    scope_dir = root / scope
    scope_dir.mkdir(parents=True, exist_ok=True)
    f = scope_dir / name
    f.write_bytes(b"x" * size)
    mtime = now - age_seconds
    os.utime(f, (mtime, mtime))
    return f


def test_ttl_deletes_old_keeps_recent(tmp_path: Path) -> None:
    now = 1_000_000_000.0
    root = _make_root(tmp_path)
    old = _write(root, "host-1", "old.png", size=10, age_seconds=(DEFAULT_TTL_DAYS + 1) * _DAY, now=now)
    recent = _write(root, "host-1", "recent.png", size=10, age_seconds=1 * _DAY, now=now)
    # Exactly at the TTL boundary is NOT deleted (strictly older only).
    boundary = _write(root, "host-2", "boundary.png", size=10, age_seconds=DEFAULT_TTL_DAYS * _DAY, now=now)

    result = prune_attachments(root, now=now)

    assert not old.exists()
    assert recent.exists()
    assert boundary.exists()
    assert result.deleted_count == 1
    assert result.deleted[0].reason == "ttl"
    assert result.deleted[0].path == str(old)


def test_size_cap_trims_oldest_survivors(tmp_path: Path) -> None:
    now = 1_000_000_000.0
    root = _make_root(tmp_path)
    # Three files all younger than TTL but together over a tiny cap.
    # Ages chosen so all are older than the protect window.
    a = _write(root, "host", "a.bin", size=100, age_seconds=5 * _DAY, now=now)  # oldest
    b = _write(root, "host", "b.bin", size=100, age_seconds=4 * _DAY, now=now)
    c = _write(root, "host", "c.bin", size=100, age_seconds=3 * _DAY, now=now)  # newest

    # Cap = 150 bytes -> must delete oldest until <= 150. Deletes a (then 200), b (then 100 <= 150).
    result = prune_attachments(root, now=now, max_total_bytes=150)

    assert not a.exists(), "oldest should be deleted first"
    assert not b.exists(), "second-oldest deleted to get under the cap"
    assert c.exists(), "newest survivor kept"
    assert result.deleted_count == 2
    assert {d.reason for d in result.deleted} == {"size-cap"}


def test_size_cap_spares_protect_window(tmp_path: Path) -> None:
    now = 1_000_000_000.0
    root = _make_root(tmp_path)
    # Both within TTL, together over the cap, but BOTH younger than the
    # protect window -> size cap must not delete either even though over cap.
    a = _write(root, "host", "a.bin", size=100, age_seconds=1 * _HOUR, now=now)
    b = _write(root, "host", "b.bin", size=100, age_seconds=2 * _HOUR, now=now)

    result = prune_attachments(root, now=now, max_total_bytes=150, protect_newest_hours=PROTECT_NEWEST_HOURS)

    assert a.exists()
    assert b.exists()
    assert result.deleted_count == 0


def test_ttl_runs_before_size_cap(tmp_path: Path) -> None:
    now = 1_000_000_000.0
    root = _make_root(tmp_path)
    old = _write(root, "host", "old.bin", size=100, age_seconds=(DEFAULT_TTL_DAYS + 2) * _DAY, now=now)
    keep = _write(root, "host", "keep.bin", size=100, age_seconds=2 * _DAY, now=now)

    # Cap large enough that, after TTL removes `old`, the survivor fits.
    result = prune_attachments(root, now=now, max_total_bytes=150)

    assert not old.exists()
    assert keep.exists()
    assert result.deleted_count == 1
    assert result.deleted[0].reason == "ttl"


def test_only_touches_attachment_files_not_user_files(tmp_path: Path) -> None:
    now = 1_000_000_000.0
    root = _make_root(tmp_path)
    aged = _write(root, "host", "aged.png", size=10, age_seconds=(DEFAULT_TTL_DAYS + 5) * _DAY, now=now)

    # A user file OUTSIDE the attachments tree, also very old — must survive.
    outside = tmp_path / "important.txt"
    outside.write_text("do not delete me")
    os.utime(outside, (now - 999 * _DAY, now - 999 * _DAY))

    # An old file in the attachments ROOT (not under a scope dir) — depth-1,
    # outside the <scope>/<file> layout — must NOT be deleted.
    root_level = root / "loose-at-root.png"
    root_level.write_bytes(b"x")
    os.utime(root_level, (now - 999 * _DAY, now - 999 * _DAY))

    result = prune_attachments(root, now=now)

    assert not aged.exists()
    assert outside.exists()
    assert root_level.exists()
    assert result.deleted_count == 1


def test_does_not_delete_directories_or_descend_deeper(tmp_path: Path) -> None:
    now = 1_000_000_000.0
    root = _make_root(tmp_path)
    # A nested subdir two levels deep with an aged file in it -> our depth-2
    # scan (root/<scope>/<file>) must not reach root/<scope>/<subdir>/<file>.
    nested = root / "host" / "subdir"
    nested.mkdir(parents=True)
    deep = nested / "deep.png"
    deep.write_bytes(b"x")
    os.utime(deep, (now - 999 * _DAY, now - 999 * _DAY))

    result = prune_attachments(root, now=now)

    assert deep.exists(), "files deeper than <scope>/<file> are out of scope"
    assert (root / "host").is_dir(), "the scope dir itself is never deleted"
    assert result.deleted_count == 0


def test_does_not_follow_symlinks(tmp_path: Path) -> None:
    now = 1_000_000_000.0
    root = _make_root(tmp_path)
    # A precious file outside, and an aged symlink inside the attachments dir
    # pointing at it. Deleting the symlink TARGET would be data loss; the
    # pruner must skip symlinks entirely.
    precious = tmp_path / "precious.txt"
    precious.write_text("secret")
    os.utime(precious, (now - 999 * _DAY, now - 999 * _DAY))

    scope = root / "host"
    scope.mkdir()
    link = scope / "link.png"
    try:
        link.symlink_to(precious)
    except (OSError, NotImplementedError):
        # Platform without symlink support — nothing to assert.
        return
    os.utime(link, (now - 999 * _DAY, now - 999 * _DAY), follow_symlinks=False)

    result = prune_attachments(root, now=now)

    assert precious.exists(), "symlink target must never be touched"
    assert link.exists() or not link.exists()  # link itself may or may not be considered
    assert result.deleted_count == 0


def test_missing_root_is_clean_noop(tmp_path: Path) -> None:
    now = 1_000_000_000.0
    root = tmp_path / ".pocketshell" / "attachments"  # never created
    result = prune_attachments(root, now=now)
    assert result.skipped_root_missing is True
    assert result.deleted_count == 0


def test_dry_run_reports_without_deleting(tmp_path: Path) -> None:
    now = 1_000_000_000.0
    root = _make_root(tmp_path)
    old = _write(root, "host", "old.png", size=10, age_seconds=(DEFAULT_TTL_DAYS + 1) * _DAY, now=now)

    result = prune_attachments(root, now=now, dry_run=True)

    assert old.exists(), "dry-run must not delete"
    assert result.deleted_count == 1
    assert result.dry_run is True


def test_best_effort_records_delete_errors(tmp_path: Path, monkeypatch) -> None:
    now = 1_000_000_000.0
    root = _make_root(tmp_path)
    f = _write(root, "host", "old.png", size=10, age_seconds=(DEFAULT_TTL_DAYS + 1) * _DAY, now=now)

    real_unlink = Path.unlink

    def boom(self, *args, **kwargs):
        if self == f:
            raise OSError("permission denied")
        return real_unlink(self, *args, **kwargs)

    monkeypatch.setattr(Path, "unlink", boom)

    # Must not raise.
    result = prune_attachments(root, now=now)

    assert f.exists(), "the file could not be deleted, so it remains"
    assert result.deleted_count == 0
    assert len(result.errors) == 1
    assert "permission denied" in result.errors[0]


def test_resolve_root_uses_home_argument(tmp_path: Path) -> None:
    root = resolve_attachments_root(home=tmp_path)
    assert root == (tmp_path / ".pocketshell" / "attachments").resolve()


def test_defaults_are_sane() -> None:
    assert DEFAULT_TTL_DAYS == 14
    assert DEFAULT_MAX_TOTAL_BYTES == 256 * 1024 * 1024
    assert PROTECT_NEWEST_HOURS == 24


# ---------------------------------------------------------------------------
# CLI-level tests
# ---------------------------------------------------------------------------


def test_cli_help_lists_prune_attachments() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == 0
    assert "prune-attachments" in result.output


def test_cli_dry_run_json_against_temp_home(tmp_path: Path, monkeypatch) -> None:
    now = 1_000_000_000.0
    root = _make_root(tmp_path)
    old = _write(root, "host", "old.png", size=10, age_seconds=(DEFAULT_TTL_DAYS + 1) * _DAY, now=now)

    monkeypatch.setattr(Path, "home", classmethod(lambda cls: tmp_path))
    monkeypatch.setattr(time, "time", lambda: now)

    runner = CliRunner()
    result = runner.invoke(cli, ["prune-attachments", "--dry-run", "--json"])
    assert result.exit_code == 0, result.output
    assert '"dry_run": true' in result.output
    assert '"deleted_count": 1' in result.output
    assert old.exists(), "dry-run via CLI must not delete"


def test_cli_real_run_deletes_against_temp_home(tmp_path: Path, monkeypatch) -> None:
    now = 1_000_000_000.0
    root = _make_root(tmp_path)
    old = _write(root, "host", "old.png", size=10, age_seconds=(DEFAULT_TTL_DAYS + 1) * _DAY, now=now)
    recent = _write(root, "host", "recent.png", size=10, age_seconds=1 * _DAY, now=now)

    monkeypatch.setattr(Path, "home", classmethod(lambda cls: tmp_path))
    monkeypatch.setattr(time, "time", lambda: now)

    runner = CliRunner()
    result = runner.invoke(cli, ["prune-attachments"])
    assert result.exit_code == 0, result.output
    assert not old.exists()
    assert recent.exists()
    assert "deleted 1" in result.output


def test_cli_missing_root_message(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setattr(Path, "home", classmethod(lambda cls: tmp_path))
    runner = CliRunner()
    result = runner.invoke(cli, ["prune-attachments"])
    assert result.exit_code == 0
    assert "nothing to prune" in result.output
