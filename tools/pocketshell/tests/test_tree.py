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
import os
from pathlib import Path

from click.testing import CliRunner

from pocketshell import tree as tree_mod
from pocketshell.cli import cli


def _paths(tmp_path: Path) -> tree_mod.TreePaths:
    return tree_mod.TreePaths(tree_dir=tmp_path / "pocketshell" / "tree")


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
    assert result == {"nodes": [], "version": 0}


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
    tree_mod.upsert_tree({"host": "h1", "nodes": nodes}, paths=paths)

    got = tree_mod.get_tree({"host": "h1"}, paths=paths)
    assert got["version"] == 1
    assert [n["session"] for n in got["nodes"]] == ["git-a", "git-b"]
    assert got["nodes"][0]["collapsed"] is True
    assert got["nodes"][0]["folder_path"] == "/p/a"
    assert got["nodes"][1]["collapsed"] is False


def test_get_is_host_scoped(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    tree_mod.upsert_tree(
        {"host": "h1", "nodes": [{"session": "a"}]}, paths=paths
    )
    tree_mod.upsert_tree(
        {"host": "h2", "nodes": [{"session": "b"}]}, paths=paths
    )
    assert [n["session"] for n in tree_mod.get_tree({"host": "h1"}, paths=paths)["nodes"]] == ["a"]
    assert [n["session"] for n in tree_mod.get_tree({"host": "h2"}, paths=paths)["nodes"]] == ["b"]


# ----- tree.upsert ---------------------------------------------------


def test_upsert_persists_atomically_with_0600(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    tree_mod.upsert_tree({"host": "h1", "nodes": [{"session": "a"}]}, paths=paths)

    assert paths.registry_file.exists()
    mode = paths.registry_file.stat().st_mode & 0o777
    assert mode == 0o600, oct(mode)
    # No stray temp file left behind.
    assert not paths.registry_file.with_name(paths.registry_file.name + ".tmp").exists()


def test_upsert_bumps_version_monotonically(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    r1 = tree_mod.upsert_tree({"host": "h1", "nodes": []}, paths=paths)
    r2 = tree_mod.upsert_tree({"host": "h1", "nodes": []}, paths=paths)
    r3 = tree_mod.upsert_tree({"host": "h1", "nodes": []}, paths=paths)
    assert (r1["version"], r2["version"], r3["version"]) == (1, 2, 3)
    assert r3["status"] == "ok"


def test_upsert_skips_malformed_nodes_without_sinking_batch(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    tree_mod.upsert_tree(
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
    tree_mod.upsert_tree(
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
    tree_mod.upsert_tree({"host": "h1", "node": {"session": "solo"}}, paths=paths)
    got = tree_mod.get_tree({"host": "h1"}, paths=paths)
    assert [n["session"] for n in got["nodes"]] == ["solo"]


def test_upsert_overwrites_node_set(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    tree_mod.upsert_tree(
        {"host": "h1", "nodes": [{"session": "a"}, {"session": "b"}]}, paths=paths
    )
    tree_mod.upsert_tree({"host": "h1", "nodes": [{"session": "c"}]}, paths=paths)
    got = tree_mod.get_tree({"host": "h1"}, paths=paths)
    assert [n["session"] for n in got["nodes"]] == ["c"]


# ----- tree.reconcile ------------------------------------------------


def test_reconcile_returns_deltas_alive_gone_added(tmp_path: Path) -> None:
    paths = _paths(tmp_path)
    tree_mod.upsert_tree(
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
    tree_mod.upsert_tree(
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
    tree_mod.upsert_tree(
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
    tree_mod.upsert_tree(
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
    tree_mod.upsert_tree(
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
        input=json.dumps({"host": "h1", "nodes": [{"session": "a", "collapsed": True}]}),
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
        input=json.dumps({"host": "h1", "nodes": [{"session": "keep"}, {"session": "drop"}]}),
    )
    result = runner.invoke(cli, ["tree", "reconcile"], input=json.dumps({"host": "h1"}))
    assert result.exit_code == 0, result.output
    envelope = json.loads(result.output)
    assert envelope["alive"] == ["keep"]
    assert envelope["gone"] == ["drop"]
    assert envelope["added"] == []
