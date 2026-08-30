"""Unit tests for `pocketshell.tree` — the durable per-host project-tree
registry (epic #821 slice C, issue #837).

Coverage:

- `tree.get` returns the persisted node list (order / folder_path / collapsed /
  optional foreign_kind); the empty-registry seed is valid.
- `tree.upsert` atomically persists with mode 0600 and bumps the version; one
  malformed node is skipped, the rest persist.
- `tree.reconcile` diffs the registry against live `tmuxctl list` and returns
  `{alive, gone, added}` DELTAS, pruning gone sessions — WITH the optimistic
  grace guard (a just-upserted node still inside grace is spared) — and never
  pruning when the live enumeration is unavailable.
- The CLI seam (`pocketshell tree get|upsert|reconcile`) reads params on stdin
  and emits the result envelope, falling back to the in-process handler when
  the daemon is absent.
- The kind is NOT stored: `tree.upsert` keeps no `@ps_agent_kind`-style kind
  field; only the cheap foreign-guess cache (`foreign_kind`) survives.
"""

from __future__ import annotations

import json
import multiprocessing
import os
import threading
from pathlib import Path

from click.testing import CliRunner

from pocketshell import tree as tree_mod
from pocketshell.cli import cli


def _paths(tmp_path: Path) -> tree_mod.TreePaths:
    return tree_mod.TreePaths(tree_dir=tmp_path / "pocketshell" / "tree")


def _upsert_tree(params: dict, *, paths: tree_mod.TreePaths) -> dict:
    request = dict(params)
    request.setdefault(
        "expected_version",
        tree_mod.get_tree({"host": request["host"]}, paths=paths)["version"],
    )
    return tree_mod.upsert_tree(request, paths=paths)


_READ_BARRIER = None
_REAL_READ_REGISTRY = tree_mod._read_registry


def _barrier_registry_read(paths: tree_mod.TreePaths) -> dict:
    doc = _REAL_READ_REGISTRY(paths)
    try:
        _READ_BARRIER.wait(timeout=0.2)
    except threading.BrokenBarrierError:
        pass
    return doc


def _concurrent_tree_writer(tree_dir: str, host: str, session: str) -> None:
    paths = tree_mod.TreePaths(tree_dir=Path(tree_dir))
    tree_mod.upsert_tree(
        {"host": host, "expected_version": 0, "nodes": [{"session": session}]},
        paths=paths,
    )


# ----- resolve_paths -------------------------------------------------


def test_resolve_paths_prefers_xdg_state() -> None:
    paths = tree_mod.resolve_paths(env={"XDG_STATE_HOME": "/x/state"})
    assert paths.tree_dir == Path("/x/state/pocketshell/tree")
    assert paths.registry_file == Path("/x/state/pocketshell/tree/registry.json")


def test_resolve_paths_falls_back_to_local_state_without_xdg() -> None:
    paths = tree_mod.resolve_paths(home=Path("/home/u"), env={})
    assert paths.tree_dir == Path("/home/u/.local/state/pocketshell/tree")


# ----- tree.get ------------------------------------------------------


def test_get_empty_registry_is_valid_seed(tmp_path: Path) -> None:
    """No registry yet → empty nodes + version 0 (client seeds fresh)."""
    result = tree_mod.get_tree({"host": "hetzner"}, paths=_paths(tmp_path))
    assert result["nodes"] == []
    assert result["version"] == 0


def test_get_envelope_carries_cli_version(tmp_path: Path) -> None:
    """Issue #885: `tree.get` stamps the installed CLI version into the
    envelope so the client can detect a version mismatch PASSIVELY on every
    normal open (no separate blocking `--version` exec)."""
    from pocketshell import __version__

    result = tree_mod.get_tree({"host": "hetzner"}, paths=_paths(tmp_path))
    assert result["cli_version"] == str(__version__)
    assert result["cli_version"]  # non-empty


def test_reconcile_envelope_carries_cli_version(tmp_path: Path, monkeypatch) -> None:
    """Issue #885: `tree.reconcile` also stamps the CLI version — it is the
    most-frequent regular payload (fired on every open + resume)."""
    from pocketshell import __version__

    paths = _paths(tmp_path)
    _upsert_tree({"host": "h1", "nodes": [{"session": "a"}]}, paths=paths)
    # With a live enumeration containing the session, it reports alive + version.
    result = tree_mod.reconcile_tree(
        {"host": "h1"}, paths=paths, live_names={"a"}
    )
    assert result["cli_version"] == str(__version__)
    # And on the no-enumeration branch (tmuxctl missing) it still stamps it.
    monkeypatch.setattr(tree_mod, "_live_session_names", lambda env=None: None)
    result_no_enum = tree_mod.reconcile_tree({"host": "h1"}, paths=paths)
    assert result_no_enum["cli_version"] == str(__version__)


def test_get_requires_host(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    for bad in ({}, {"host": ""}, {"host": 123}):
        try:
            tree_mod.get_tree(bad, paths=paths)
        except ValueError:
            continue
        raise AssertionError(f"expected ValueError for params={bad!r}")


def test_get_returns_persisted_nodes_after_upsert(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    nodes = [
        {"session": "git-a", "order": 0, "folder_path": "/p/a", "collapsed": True},
        {"session": "git-b", "order": 1, "folder_path": "/p/b", "collapsed": False},
    ]
    _upsert_tree({"host": "h1", "nodes": nodes}, paths=paths)

    got = tree_mod.get_tree({"host": "h1"}, paths=paths)
    assert got["version"] == 1
    assert [n["session"] for n in got["nodes"]] == ["git-a", "git-b"]
    assert got["nodes"][0]["collapsed"] is True
    assert got["nodes"][0]["folder_path"] == "/p/a"
    assert got["nodes"][1]["collapsed"] is False


def test_get_preserves_exact_tmux_generation(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    _upsert_tree(
        {
            "host": "h1",
            "nodes": [
                {
                    "session": "work",
                    "tmux_session_id": "$9",
                    "session_created": 1720000000,
                },
            ],
        },
        paths=paths,
    )

    node = tree_mod.get_tree({"host": "h1"}, paths=paths)["nodes"][0]
    assert node["tmux_session_id"] == "$9"
    assert node["session_created"] == 1720000000


def test_get_is_host_scoped(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    _upsert_tree(
        {"host": "h1", "nodes": [{"session": "a"}]}, paths=paths
    )
    _upsert_tree(
        {"host": "h2", "nodes": [{"session": "b"}]}, paths=paths
    )
    assert [n["session"] for n in tree_mod.get_tree({"host": "h1"}, paths=paths)["nodes"]] == ["a"]
    assert [n["session"] for n in tree_mod.get_tree({"host": "h2"}, paths=paths)["nodes"]] == ["b"]


# ----- tree.upsert ---------------------------------------------------


def test_upsert_persists_atomically_with_0600(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    _upsert_tree({"host": "h1", "nodes": [{"session": "a"}]}, paths=paths)

    assert paths.registry_file.exists()
    mode = paths.registry_file.stat().st_mode & 0o777
    assert mode == 0o600, oct(mode)
    # No stray temp file left behind.
    assert not paths.registry_file.with_name(paths.registry_file.name + ".tmp").exists()


def test_upsert_bumps_version_monotonically(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    r1 = _upsert_tree({"host": "h1", "nodes": []}, paths=paths)
    r2 = _upsert_tree({"host": "h1", "nodes": []}, paths=paths)
    r3 = _upsert_tree({"host": "h1", "nodes": []}, paths=paths)
    assert (r1["version"], r2["version"], r3["version"]) == (1, 2, 3)
    assert r3["status"] == "ok"


def test_upsert_rejects_stale_expected_version(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    first = _upsert_tree(
        {"host": "opaque-a", "expected_version": 0, "nodes": [{"session": "new"}]},
        paths=paths,
    )
    stale = _upsert_tree(
        {"host": "opaque-a", "expected_version": 0, "nodes": [{"session": "old"}]},
        paths=paths,
    )
    assert first == {"status": "ok", "version": 1}
    assert stale == {"status": "conflict", "version": 1}
    assert [n["session"] for n in tree_mod.get_tree({"host": "opaque-a"}, paths=paths)["nodes"]] == ["new"]


def test_concurrent_process_writers_for_different_hosts_lose_neither(
    tmp_path: Path, monkeypatch
) -> None:
    global _READ_BARRIER
    paths = _paths(tmp_path)
    ctx = multiprocessing.get_context("fork")
    _READ_BARRIER = ctx.Barrier(2)
    # Without the production flock, both children deterministically read the
    # same empty document before either writes. With the flock, the first
    # barrier wait times out while the second blocks on the lock; the second
    # then reads the first writer's complete document and preserves both hosts.
    monkeypatch.setattr(tree_mod, "_read_registry", _barrier_registry_read)
    writers = [
        ctx.Process(target=_concurrent_tree_writer, args=(str(paths.tree_dir), "opaque-a", "a")),
        ctx.Process(target=_concurrent_tree_writer, args=(str(paths.tree_dir), "opaque-b", "b")),
    ]
    for writer in writers:
        writer.start()
    for writer in writers:
        writer.join(10)
        assert writer.exitcode == 0
    monkeypatch.setattr(tree_mod, "_read_registry", _REAL_READ_REGISTRY)
    assert [n["session"] for n in tree_mod.get_tree({"host": "opaque-a"}, paths=paths)["nodes"]] == ["a"]
    assert [n["session"] for n in tree_mod.get_tree({"host": "opaque-b"}, paths=paths)["nodes"]] == ["b"]


def test_atomic_writer_uses_unique_temp_and_fsyncs_file_then_directory(
    tmp_path: Path, monkeypatch
) -> None:
    paths = _paths(tmp_path)
    fsync_targets: list[str] = []
    real_fsync = os.fsync

    def recording_fsync(fd: int) -> None:
        fsync_targets.append(os.readlink(f"/proc/self/fd/{fd}"))
        real_fsync(fd)

    monkeypatch.setattr(tree_mod.os, "fsync", recording_fsync)
    _upsert_tree(
        {"host": "opaque-a", "expected_version": 0, "nodes": [{"session": "a"}]},
        paths=paths,
    )
    assert len(fsync_targets) >= 2
    assert fsync_targets[0] != str(paths.tree_dir)
    assert fsync_targets[-1] == str(paths.tree_dir)
    assert not list(paths.tree_dir.glob("registry.json.*.tmp"))


def test_atomic_writer_replace_failure_keeps_old_document_and_cleans_temp(
    tmp_path: Path, monkeypatch
) -> None:
    paths = _paths(tmp_path)
    _upsert_tree(
        {"host": "opaque-a", "expected_version": 0, "nodes": [{"session": "old"}]},
        paths=paths,
    )

    def crash_cut(_source: Path, _target: Path) -> None:
        raise OSError("simulated crash before rename")

    monkeypatch.setattr(tree_mod.os, "replace", crash_cut)
    try:
        tree_mod.upsert_tree(
            {
                "host": "opaque-a",
                "expected_version": 1,
                "nodes": [{"session": "partial-new"}],
            },
            paths=paths,
        )
    except OSError:
        pass
    else:
        raise AssertionError("expected simulated replace failure")

    persisted = json.loads(paths.registry_file.read_text())
    assert persisted["hosts"]["opaque-a"]["nodes"][0]["session"] == "old"
    assert not list(paths.tree_dir.glob("registry.json.*.tmp"))


def test_upsert_skips_malformed_nodes_without_sinking_batch(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    _upsert_tree(
        {
            "host": "h1",
            "nodes": [
                {"session": "good-a"},
                {"no_session": True},  # malformed — dropped
                "not-a-mapping",  # malformed — dropped
                {"session": "", "order": 5},  # empty session — dropped
                {"session": "good-b"},
            ],
        },
        paths=paths,
    )
    got = tree_mod.get_tree({"host": "h1"}, paths=paths)
    assert [n["session"] for n in got["nodes"]] == ["good-a", "good-b"]


def test_upsert_persists_foreign_guess_cache_but_no_kind(tmp_path: Path) -> None:
    """The optional foreign-GUESS cache survives; there is NO confirmed-kind
    field — confirmed kind lives in `@ps_agent_kind`, not this registry."""
    paths = _paths(tmp_path)
    _upsert_tree(
        {
            "host": "h1",
            "nodes": [
                {"session": "a", "foreign_kind": "codex", "kind": "claude"},
            ],
        },
        paths=paths,
    )
    node = tree_mod.get_tree({"host": "h1"}, paths=paths)["nodes"][0]
    assert node.get("foreign_kind") == "codex"
    # The registry must NOT carry a confirmed-kind copy (two-writers guard).
    assert "kind" not in node


def test_upsert_accepts_single_node(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    _upsert_tree({"host": "h1", "node": {"session": "solo"}}, paths=paths)
    got = tree_mod.get_tree({"host": "h1"}, paths=paths)
    assert [n["session"] for n in got["nodes"]] == ["solo"]


def test_upsert_overwrites_node_set(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    _upsert_tree(
        {"host": "h1", "nodes": [{"session": "a"}, {"session": "b"}]}, paths=paths
    )
    _upsert_tree({"host": "h1", "nodes": [{"session": "c"}]}, paths=paths)
    got = tree_mod.get_tree({"host": "h1"}, paths=paths)
    assert [n["session"] for n in got["nodes"]] == ["c"]


# ----- tree.reconcile ------------------------------------------------


def test_reconcile_returns_deltas_alive_gone_added(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    _upsert_tree(
        {"host": "h1", "nodes": [{"session": "alive"}, {"session": "dead"}]},
        paths=paths,
    )
    result = tree_mod.reconcile_tree(
        {"host": "h1"},
        paths=paths,
        live_names={"alive", "fresh"},
        now=10_000.0,
    )
    assert sorted(result["alive"]) == ["alive"]
    assert sorted(result["gone"]) == ["dead"]
    assert sorted(result["added"]) == ["fresh"]


def test_reconcile_prunes_gone_sessions_from_registry(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    _upsert_tree(
        {"host": "h1", "nodes": [{"session": "alive"}, {"session": "dead"}]},
        paths=paths,
    )
    tree_mod.reconcile_tree(
        {"host": "h1"}, paths=paths, live_names={"alive"}, now=10_000.0
    )
    # The gone session is pruned from the persisted registry (not just the
    # delta) so it never flashes on a later cold start.
    remaining = [n["session"] for n in tree_mod.get_tree({"host": "h1"}, paths=paths)["nodes"]]
    assert remaining == ["alive"]


def test_reconcile_spares_node_within_optimistic_grace(tmp_path: Path) -> None:
    """A just-upserted node the live probe has not yet observed is NOT pruned
    while inside the optimistic-grace window (mirror OPTIMISTIC_GRACE_MS)."""
    paths = _paths(tmp_path)
    _upsert_tree(
        {
            "host": "h1",
            "nodes": [
                {"session": "fresh", "optimistic_since": 1000.0},
            ],
        },
        paths=paths,
    )
    # 5 s later: still within the 30 s grace → spared, reported alive.
    result = tree_mod.reconcile_tree(
        {"host": "h1"}, paths=paths, live_names=set(), now=1005.0
    )
    assert result["gone"] == []
    assert result["alive"] == ["fresh"]
    # Still in the registry.
    assert [n["session"] for n in tree_mod.get_tree({"host": "h1"}, paths=paths)["nodes"]] == ["fresh"]


def test_reconcile_prunes_node_past_optimistic_grace(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    _upsert_tree(
        {
            "host": "h1",
            "nodes": [{"session": "stale", "optimistic_since": 1000.0}],
        },
        paths=paths,
    )
    # 40 s later: past the 30 s grace and absent from live → pruned.
    result = tree_mod.reconcile_tree(
        {"host": "h1"}, paths=paths, live_names=set(), now=1040.0
    )
    assert result["gone"] == ["stale"]
    assert tree_mod.get_tree({"host": "h1"}, paths=paths)["nodes"] == []


def test_reconcile_does_not_prune_when_enumeration_unavailable(
    tmp_path: Path, monkeypatch
) -> None:
    """A tmuxctl miss (`_live_session_names()` -> None) must NOT wipe the held
    tree — report everything alive and prune nothing."""
    paths = _paths(tmp_path)
    _upsert_tree(
        {"host": "h1", "nodes": [{"session": "a"}, {"session": "b"}]}, paths=paths
    )
    # Simulate `tmuxctl` missing / a non-zero exit: the live enumeration is
    # unavailable, so reconcile must not prune. (`live_names` defaults to None
    # here, which means "resolve from the host" — the stub makes that fail.)
    monkeypatch.setattr(tree_mod, "_live_session_names", lambda env=None: None)
    result = tree_mod.reconcile_tree({"host": "h1"}, paths=paths)
    assert sorted(result["alive"]) == ["a", "b"]
    assert result["gone"] == []
    assert result["added"] == []
    # Registry untouched.
    assert [n["session"] for n in tree_mod.get_tree({"host": "h1"}, paths=paths)["nodes"]] == ["a", "b"]


def test_reconcile_empty_registry_yields_added_only(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    result = tree_mod.reconcile_tree(
        {"host": "h1"}, paths=paths, live_names={"x", "y"}, now=1.0
    )
    assert result["alive"] == []
    assert result["gone"] == []
    assert sorted(result["added"]) == ["x", "y"]


# ----- live-session enumeration parsing ------------------------------


def test_parse_session_names_from_tmuxctl_table() -> None:
    table = (
        "IDX  SESSION               CREATED\n"
        "1    git-tmuxcli           2026-05-27 17:32:30 \n"
        "2    git-ai-engineering    2026-05-27 15:55:44 \n"
        "8    git-raw-guard         2026-05-20 17:41:29 \n"
    )
    names = tree_mod._parse_session_names(table)
    assert names == {"git-tmuxcli", "git-ai-engineering", "git-raw-guard"}


def test_parse_session_names_keeps_overflowed_long_names() -> None:
    table = (
        "IDX  SESSION               CREATED\n"
        "1    git-pocketshell-release 2026-08-27 09:22:55 \n"
        "5    git-ai-shipping-labs-workshops-raw-guard 2026-05-20 17:41:29 \n"
    )
    names = tree_mod._parse_session_names(table)
    assert names == {
        "git-pocketshell-release",
        "git-ai-shipping-labs-workshops-raw-guard",
    }


def test_parse_session_names_skips_header_and_blanks() -> None:
    assert tree_mod._parse_session_names("") == set()
    assert tree_mod._parse_session_names("IDX  SESSION  CREATED\n\n") == set()


def test_live_session_names_none_when_tmuxctl_missing(monkeypatch) -> None:
    monkeypatch.setattr(tree_mod, "_resolve_tmuxctl_binary", lambda: None)
    assert tree_mod._live_session_names() is None


# ----- CLI seam ------------------------------------------------------


def test_cli_top_level_lists_tree_subcommand() -> None:
    result = CliRunner().invoke(cli, ["--help"])
    assert result.exit_code == 0, result.output
    assert "tree" in result.output


def test_cli_tree_get_round_trips_via_in_process(
    tmp_path: Path, monkeypatch
) -> None:
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path / "state"))
    # Force the daemon-absent path so the CLI uses the in-process handler.
    monkeypatch.setattr(tree_mod, "_try_daemon_call", lambda *a, **k: None)

    runner = CliRunner()
    runner.invoke(
        cli,
        ["tree", "upsert"],
        input=json.dumps({"host": "h1", "expected_version": 0, "nodes": [{"session": "a", "collapsed": True}]}),
    )
    result = runner.invoke(cli, ["tree", "get"], input=json.dumps({"host": "h1"}))
    assert result.exit_code == 0, result.output
    envelope = json.loads(result.output)
    assert envelope["version"] == 1
    assert envelope["nodes"][0]["session"] == "a"
    assert envelope["nodes"][0]["collapsed"] is True


def test_cli_tree_reconcile_emits_deltas(tmp_path: Path, monkeypatch) -> None:
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path / "state"))
    monkeypatch.setattr(tree_mod, "_try_daemon_call", lambda *a, **k: None)
    # Live enumeration is stubbed so reconcile is deterministic.
    monkeypatch.setattr(tree_mod, "_live_session_names", lambda env=None: {"keep"})

    runner = CliRunner()
    runner.invoke(
        cli,
        ["tree", "upsert"],
        input=json.dumps({"host": "h1", "expected_version": 0, "nodes": [{"session": "keep"}, {"session": "drop"}]}),
    )
    result = runner.invoke(cli, ["tree", "reconcile"], input=json.dumps({"host": "h1"}))
    assert result.exit_code == 0, result.output
    envelope = json.loads(result.output)
    assert envelope["alive"] == ["keep"]
    assert envelope["gone"] == ["drop"]
    assert envelope["added"] == []


# ----- tree.workspace.get / tree.workspace.upsert (#1715) -------------


def test_workspace_round_trip(tmp_path: Path) -> None:
    """Issue #1715: persist + restore the host file workspace."""
    paths = _paths(tmp_path)
    upserted = tree_mod.upsert_workspace(
        {
            "tabs": [
                {"path": "/home/u/a.md", "last_activated_ms": 10},
                {"path": "/home/u/b.kt", "last_activated_ms": 20},
            ],
            "active_path": "/home/u/b.kt",
        },
        paths=paths,
    )
    assert upserted["status"] == "ok"
    got = tree_mod.get_workspace({}, paths=paths)
    assert [t["path"] for t in got["tabs"]] == ["/home/u/a.md", "/home/u/b.kt"]
    assert got["active_path"] == "/home/u/b.kt"
    assert got["tabs"][0]["last_activated_ms"] == 10
    assert got["tabs"][1]["last_activated_ms"] == 20


def test_workspace_empty_registry_is_valid_seed(tmp_path: Path) -> None:
    got = tree_mod.get_workspace({}, paths=_paths(tmp_path))
    assert got["tabs"] == []
    assert got["active_path"] is None


def test_workspace_rejects_non_absolute_and_normalizes(tmp_path: Path) -> None:
    """Relative / blank / non-string paths are dropped; `.`/`..`/`//` collapse."""
    paths = _paths(tmp_path)
    tree_mod.upsert_workspace(
        {
            "tabs": [
                {"path": "relative.md", "last_activated_ms": 1},
                {"path": "", "last_activated_ms": 2},
                {"path": 123, "last_activated_ms": 3},
                {"path": "/home/u/../u/src/./App.kt", "last_activated_ms": 4},
                {"path": "//home//u//notes.md", "last_activated_ms": 5},
            ],
            "active_path": "/home/u/src/App.kt",
        },
        paths=paths,
    )
    got = tree_mod.get_workspace({}, paths=paths)
    assert [t["path"] for t in got["tabs"]] == [
        "/home/u/src/App.kt",
        "/home/u/notes.md",
    ]
    assert got["active_path"] == "/home/u/src/App.kt"


def test_workspace_skips_malformed_rows_without_sinking_batch(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    tree_mod.upsert_workspace(
        {
            "tabs": [
                {"path": "/ok/a.txt", "last_activated_ms": 1},
                "not-a-mapping",
                {"no_path": True},
                {"path": "/ok/b.txt", "last_activated_ms": "nope"},
                {"path": "/ok/c.txt", "last_activated_ms": 3},
            ],
            "active_path": "/ok/c.txt",
        },
        paths=paths,
    )
    got = tree_mod.get_workspace({}, paths=paths)
    assert [t["path"] for t in got["tabs"]] == ["/ok/a.txt", "/ok/c.txt"]


def test_workspace_dedupes_same_absolute_path(tmp_path: Path) -> None:
    """Same resolved path collapses to one tab; last recency wins, first slot stays."""
    paths = _paths(tmp_path)
    tree_mod.upsert_workspace(
        {
            "tabs": [
                {"path": "/home/u/a.md", "last_activated_ms": 1},
                {"path": "/home/u/../u/a.md", "last_activated_ms": 9},
                {"path": "/home/u/b.md", "last_activated_ms": 2},
            ],
            "active_path": "/home/u/a.md",
        },
        paths=paths,
    )
    got = tree_mod.get_workspace({}, paths=paths)
    assert [t["path"] for t in got["tabs"]] == ["/home/u/a.md", "/home/u/b.md"]
    assert got["tabs"][0]["last_activated_ms"] == 9


def test_workspace_recovers_missing_or_invalid_active_path(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    tree_mod.upsert_workspace(
        {
            "tabs": [
                {"path": "/a.md", "last_activated_ms": 1},
                {"path": "/b.md", "last_activated_ms": 5},
            ],
            "active_path": "/gone.md",
        },
        paths=paths,
    )
    got = tree_mod.get_workspace({}, paths=paths)
    assert got["active_path"] == "/b.md"

    tree_mod.upsert_workspace(
        {
            "tabs": [{"path": "/a.md", "last_activated_ms": 1}],
            "active_path": "relative",
        },
        paths=paths,
    )
    got = tree_mod.get_workspace({}, paths=paths)
    assert got["active_path"] == "/a.md"


def test_workspace_caps_at_12_and_evicts_oldest_inactive(tmp_path: Path) -> None:
    """The 13th tab evicts the least-recently-activated inactive tab, never the active."""
    paths = _paths(tmp_path)
    tabs = [
        {"path": f"/f/{i:02d}.txt", "last_activated_ms": i}
        for i in range(12)
    ]
    # Active is the oldest (0). The next open must evict 1 (oldest inactive), not 0.
    tree_mod.upsert_workspace(
        {
            "tabs": tabs + [{"path": "/f/new.txt", "last_activated_ms": 100}],
            "active_path": "/f/00.txt",
        },
        paths=paths,
        now_ms=100,
    )
    got = tree_mod.get_workspace({}, paths=paths)
    paths_out = [t["path"] for t in got["tabs"]]
    assert len(paths_out) == 12
    assert "/f/00.txt" in paths_out
    assert "/f/new.txt" in paths_out
    assert "/f/01.txt" not in paths_out
    assert got["active_path"] == "/f/00.txt"


def test_workspace_survives_tree_upsert_and_pruning_reconcile(tmp_path: Path) -> None:
    """A tree writer must not erase file_workspaces (two-writers guard)."""
    paths = _paths(tmp_path)
    tree_mod.upsert_workspace(
        {
            "tabs": [{"path": "/keep/me.md", "last_activated_ms": 1}],
            "active_path": "/keep/me.md",
        },
        paths=paths,
    )
    _upsert_tree(
        {"host": "h1", "nodes": [{"session": "alive"}, {"session": "dead"}]},
        paths=paths,
    )
    tree_mod.reconcile_tree(
        {"host": "h1"}, paths=paths, live_names={"alive"}, now=10_000.0
    )
    got = tree_mod.get_workspace({}, paths=paths)
    assert [t["path"] for t in got["tabs"]] == ["/keep/me.md"]
    assert [n["session"] for n in tree_mod.get_tree({"host": "h1"}, paths=paths)["nodes"]] == [
        "alive"
    ]


def test_tree_survives_workspace_upsert(tmp_path: Path) -> None:
    """A workspace writer must not erase hosts."""
    paths = _paths(tmp_path)
    _upsert_tree(
        {"host": "h1", "nodes": [{"session": "keep-session"}]},
        paths=paths,
    )
    tree_mod.upsert_workspace(
        {
            "tabs": [{"path": "/a.md", "last_activated_ms": 1}],
            "active_path": "/a.md",
        },
        paths=paths,
    )
    got = tree_mod.get_tree({"host": "h1"}, paths=paths)
    assert [n["session"] for n in got["nodes"]] == ["keep-session"]


def test_workspace_persists_atomically_with_0600(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    tree_mod.upsert_workspace(
        {
            "tabs": [{"path": "/a.md", "last_activated_ms": 1}],
            "active_path": "/a.md",
        },
        paths=paths,
    )
    assert paths.registry_file.exists()
    mode = paths.registry_file.stat().st_mode & 0o777
    assert mode == 0o600, oct(mode)
    assert not paths.registry_file.with_name(paths.registry_file.name + ".tmp").exists()


def test_workspace_envelope_carries_cli_version(tmp_path: Path) -> None:
    from pocketshell import __version__

    got = tree_mod.get_workspace({}, paths=_paths(tmp_path))
    assert got["cli_version"] == str(__version__)


def test_cli_tree_workspace_round_trips_via_in_process(
    tmp_path: Path, monkeypatch
) -> None:
    monkeypatch.setenv("XDG_STATE_HOME", str(tmp_path / "state"))
    monkeypatch.setattr(tree_mod, "_try_daemon_call", lambda *a, **k: None)

    runner = CliRunner()
    upsert = runner.invoke(
        cli,
        ["tree", "workspace-upsert"],
        input=json.dumps(
            {
                "tabs": [{"path": "/home/u/a.md", "last_activated_ms": 3}],
                "active_path": "/home/u/a.md",
            }
        ),
    )
    assert upsert.exit_code == 0, upsert.output
    result = runner.invoke(cli, ["tree", "workspace-get"], input="{}")
    assert result.exit_code == 0, result.output
    envelope = json.loads(result.output)
    assert [t["path"] for t in envelope["tabs"]] == ["/home/u/a.md"]
    assert envelope["active_path"] == "/home/u/a.md"
