"""Unit tests for `pocketshell repos`.

Scope (issue #220 first PR — Python-side only):

- Top-level CLI registration: ``pocketshell --help`` lists ``repos``.
- ``pocketshell repos list --help`` shows the flags and does not scan.
- Synthetic-tmpdir scans: a `git init`-style ``.git`` directory is
  picked up; a non-repo directory is not.
- ``--json`` output shape: array of dicts with ``name``/``path``/``remote``/
  ``head`` fields, sorted by name.
- Human-readable output is three-column with ``-`` for missing remotes.
- Skip-pattern: ``node_modules`` under a root must not pollute the scan
  and must NOT be descended into.
- Depth limit: a repo deeper than ``--max-depth`` is excluded.
- Non-existent root: warning to stderr + exit 0, other roots still scan.
- Empty root: ``[]`` JSON, no human output.
- Env-var roots: ``POCKETSHELL_REPOS_ROOTS`` honoured when ``--root``
  is absent; ``--root`` wins when both are set.
- Name rule: repo name is derived from the directory basename, not from
  the ``remote.origin.url``.
- Symlinks are not followed.
- Daemon integration: ``repos.list_local`` round-trips, falls through
  when no daemon, and respects ``--no-daemon``.

Tests use tmpdir-driven synthetic repos: ``.git/`` is created as a
directory (no real ``git init`` needed for detection), plus an explicit
``git init`` + ``git config remote.origin.url`` for the few tests that
need the remote / HEAD fields to be populated.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

import pytest
from click.testing import CliRunner

from pocketshell import daemon as daemon_mod
from pocketshell.cli import cli
from pocketshell.repos import (
    DAEMON_CACHE_TTL_SECS,
    DEFAULT_MAX_DEPTH,
    DEFAULT_ROOT_PATHS,
    daemon_handler,
    repos_group,
    resolve_scan_roots,
    scan_roots,
)


# ---------------------------------------------------------------------------
# Synthetic-repo helpers
# ---------------------------------------------------------------------------


def _make_bare_repo(parent: Path, name: str) -> Path:
    """Create a bare directory with an empty ``.git/`` subdir.

    Detection only requires the ``.git`` entry to exist; we use this
    for the fast-path tests that do not need remote/HEAD population.
    """
    repo = parent / name
    repo.mkdir(parents=True, exist_ok=True)
    (repo / ".git").mkdir(exist_ok=True)
    return repo


def _make_real_repo(
    parent: Path,
    name: str,
    *,
    remote_url: str | None = None,
) -> Path:
    """Run ``git init`` and optionally configure ``remote.origin.url``.

    Used by the tests that need ``remote`` / ``head`` populated. Skips
    via ``pytest.skip`` if the ``git`` binary is not on PATH (CI matrix
    that strips it).
    """
    if shutil.which("git") is None:
        pytest.skip("git binary not available")
    repo = parent / name
    repo.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["git", "init", "-q", "-b", "main", str(repo)],
        check=True,
        capture_output=True,
    )
    # Some older git versions ignore -b; force the branch via update-ref.
    if remote_url is not None:
        subprocess.run(
            ["git", "-C", str(repo), "config", "remote.origin.url", remote_url],
            check=True,
            capture_output=True,
        )
    # Set user identity for git so commits/branches work in case future
    # tests need it; keeps the test isolated from the host's ~/.gitconfig.
    subprocess.run(
        ["git", "-C", str(repo), "config", "user.email", "test@example.com"],
        check=True,
        capture_output=True,
    )
    subprocess.run(
        ["git", "-C", str(repo), "config", "user.name", "Tester"],
        check=True,
        capture_output=True,
    )
    return repo


# ---------------------------------------------------------------------------
# CLI registration + help
# ---------------------------------------------------------------------------


def test_top_level_help_lists_repos_subcommand() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == 0, result.output
    assert "repos" in result.output


def test_repos_help_lists_list_subcommand() -> None:
    runner = CliRunner()
    result = runner.invoke(repos_group, ["--help"])
    assert result.exit_code == 0, result.output
    assert "list" in result.output.lower()


def test_repos_list_help_does_not_scan(tmp_path: Path) -> None:
    runner = CliRunner()
    # `--help` must not touch the filesystem at all.
    result = runner.invoke(repos_group, ["list", "--help"])
    assert result.exit_code == 0, result.output
    assert "--local" in result.output
    assert "--json" in result.output
    assert "--root" in result.output
    assert "--max-depth" in result.output


# ---------------------------------------------------------------------------
# resolve_scan_roots
# ---------------------------------------------------------------------------


def test_resolve_scan_roots_default_to_home_git(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("POCKETSHELL_REPOS_ROOTS", raising=False)
    monkeypatch.setenv("HOME", "/home/test")
    roots = resolve_scan_roots()
    assert roots == [Path("/home/test/git")]
    # Sanity-check the default constant agrees with the resolved path.
    assert DEFAULT_ROOT_PATHS == ("~/git",)


def test_resolve_scan_roots_env_var(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.setenv("HOME", "/home/test")
    monkeypatch.setenv("POCKETSHELL_REPOS_ROOTS", "/srv/code:~/projects")
    roots = resolve_scan_roots()
    assert roots == [Path("/srv/code"), Path("/home/test/projects")]


def test_resolve_scan_roots_env_var_strips_empty_entries(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("HOME", "/home/test")
    monkeypatch.setenv("POCKETSHELL_REPOS_ROOTS", "/a::/b:")
    roots = resolve_scan_roots()
    assert roots == [Path("/a"), Path("/b")]


def test_resolve_scan_roots_explicit_wins_over_env(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setenv("POCKETSHELL_REPOS_ROOTS", "/from/env")
    roots = resolve_scan_roots(["/from/cli"])
    assert roots == [Path("/from/cli")]


def test_resolve_scan_roots_dedupes() -> None:
    roots = resolve_scan_roots(["/a", "/a", "/b"])
    assert roots == [Path("/a"), Path("/b")]


# ---------------------------------------------------------------------------
# scan_roots: detection + skip + depth + symlinks
# ---------------------------------------------------------------------------


def test_scan_roots_detects_repo(tmp_path: Path) -> None:
    _make_bare_repo(tmp_path, "alpha")
    _make_bare_repo(tmp_path, "beta")
    repos = scan_roots([tmp_path])
    names = [r.name for r in repos]
    # Sorted by name (case-insensitive) per spec.
    assert names == ["alpha", "beta"]


def test_scan_roots_ignores_non_repo_directories(tmp_path: Path) -> None:
    (tmp_path / "not-a-repo").mkdir()
    _make_bare_repo(tmp_path, "yes-a-repo")
    repos = scan_roots([tmp_path])
    assert [r.name for r in repos] == ["yes-a-repo"]


def test_scan_roots_skips_node_modules(tmp_path: Path) -> None:
    # A node_modules dir containing a phantom repo must not be scanned.
    _make_bare_repo(tmp_path / "node_modules", "evil-dep")
    _make_bare_repo(tmp_path, "real-app")
    repos = scan_roots([tmp_path])
    assert [r.name for r in repos] == ["real-app"]


def test_scan_roots_skips_all_default_skip_dirs(tmp_path: Path) -> None:
    for skip_dir in ("node_modules", ".venv", "venv", "dist", "build", "target"):
        _make_bare_repo(tmp_path / skip_dir, f"phantom-{skip_dir}")
    _make_bare_repo(tmp_path, "real")
    repos = scan_roots([tmp_path])
    assert [r.name for r in repos] == ["real"]


def test_scan_roots_depth_limit_excludes_deep_repos(tmp_path: Path) -> None:
    # `--max-depth 2` allows tmp_path/a/.git (depth 1) and
    # tmp_path/group/repo/.git (depth 2), but excludes
    # tmp_path/a/b/c/.git (depth 3).
    _make_bare_repo(tmp_path, "shallow")
    _make_bare_repo(tmp_path / "group", "mid")
    _make_bare_repo(tmp_path / "a" / "b", "deep")
    repos = scan_roots([tmp_path], max_depth=2)
    names = sorted(r.name for r in repos)
    assert names == ["mid", "shallow"]
    assert "deep" not in names


def test_scan_roots_default_depth_is_4(tmp_path: Path) -> None:
    # Repos at depths 1..4 are included; depth 5 is excluded.
    paths = [
        tmp_path / "d1",
        tmp_path / "a" / "d2",
        tmp_path / "a" / "b" / "d3",
        tmp_path / "a" / "b" / "c" / "d4",
        tmp_path / "a" / "b" / "c" / "d" / "d5",
    ]
    for p in paths:
        p.mkdir(parents=True)
        (p / ".git").mkdir()
    repos = scan_roots([tmp_path], max_depth=DEFAULT_MAX_DEPTH)
    names = sorted(r.name for r in repos)
    assert names == ["d1", "d2", "d3", "d4"]


def test_scan_roots_does_not_descend_into_detected_repo(tmp_path: Path) -> None:
    """A nested .git inside an outer repo's working tree must not be reported.

    Avoids monorepo / submodule double-counting. The outer repo is
    enough; the inner one is considered part of the outer's working
    tree by ``git`` and surfacing it would confuse the picker.
    """
    outer = _make_bare_repo(tmp_path, "outer")
    nested = outer / "submodule"
    nested.mkdir()
    (nested / ".git").mkdir()
    repos = scan_roots([tmp_path])
    assert [r.name for r in repos] == ["outer"]


def test_scan_roots_does_not_follow_symlinks(tmp_path: Path) -> None:
    # A symlink to the same root would cause infinite recursion under
    # follow_symlinks=True. Make sure os.scandir's symlink follow is off.
    real = _make_bare_repo(tmp_path, "real")
    link = tmp_path / "link-to-real"
    try:
        os.symlink(real, link, target_is_directory=True)
    except (OSError, NotImplementedError):
        pytest.skip("symlinks not supported on this platform")
    repos = scan_roots([tmp_path])
    # Only the real repo is detected; the symlink is skipped.
    assert [r.name for r in repos] == ["real"]


def test_scan_roots_warns_on_missing_root(tmp_path: Path) -> None:
    missing = tmp_path / "does-not-exist"
    present = tmp_path / "present"
    present.mkdir()
    _make_bare_repo(present, "yes")

    warnings: list[str] = []
    repos = scan_roots(
        [missing, present],
        warn_fn=warnings.append,
    )
    assert [r.name for r in repos] == ["yes"]
    assert any("does-not-exist" in w for w in warnings)


def test_scan_roots_dedupes_via_resolved_paths(tmp_path: Path) -> None:
    """Two roots that resolve to the same dir yield each repo once."""
    repo = _make_bare_repo(tmp_path, "uniq")
    # Pass the same root twice; the scan must still report the repo once.
    repos = scan_roots([tmp_path, tmp_path])
    assert [r.name for r in repos] == ["uniq"]
    assert repos[0].path == str(repo)


# ---------------------------------------------------------------------------
# Remote + HEAD population (real git init)
# ---------------------------------------------------------------------------


def test_scan_roots_reads_remote_and_head(tmp_path: Path) -> None:
    repo = _make_real_repo(
        tmp_path,
        "with-remote",
        remote_url="git@github.com:owner/with-remote.git",
    )
    repos = scan_roots([tmp_path])
    assert len(repos) == 1
    only = repos[0]
    assert only.name == "with-remote"
    assert only.path == str(repo)
    assert only.remote == "git@github.com:owner/with-remote.git"
    # Branch is `main` (we forced -b main on `git init`); some older git
    # versions stick to `master` though, so accept either.
    assert only.head in ("main", "master")


def test_scan_roots_remote_is_none_when_unset(tmp_path: Path) -> None:
    _make_real_repo(tmp_path, "no-remote")  # no remote URL configured
    repos = scan_roots([tmp_path])
    assert len(repos) == 1
    assert repos[0].remote is None


def test_name_derives_from_directory_not_remote(tmp_path: Path) -> None:
    """Even when the remote URL points at a different name, the local
    directory's basename is the source of truth for the ``name`` field.

    Justification: forks and locally-renamed clones keep stable identity.
    """
    _make_real_repo(
        tmp_path,
        "my-fork",
        remote_url="git@github.com:upstream-org/upstream-name.git",
    )
    repos = scan_roots([tmp_path])
    assert len(repos) == 1
    assert repos[0].name == "my-fork"
    assert repos[0].remote == "git@github.com:upstream-org/upstream-name.git"


# ---------------------------------------------------------------------------
# CLI output: --json + human
# ---------------------------------------------------------------------------


def test_cli_list_local_json_shape(tmp_path: Path) -> None:
    _make_bare_repo(tmp_path, "alpha")
    _make_bare_repo(tmp_path, "beta")

    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--local", "--json", "--root", str(tmp_path), "--no-daemon"],
        catch_exceptions=False,
    )
    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert isinstance(payload, list)
    assert len(payload) == 2
    # Sorted by name.
    assert [entry["name"] for entry in payload] == ["alpha", "beta"]
    # Every entry has the documented four fields.
    for entry in payload:
        assert set(entry.keys()) == {"name", "path", "remote", "head"}


def test_cli_list_local_json_empty_when_no_repos(tmp_path: Path) -> None:
    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--local", "--json", "--root", str(tmp_path), "--no-daemon"],
        catch_exceptions=False,
    )
    assert result.exit_code == 0, result.output
    assert json.loads(result.output) == []


def test_cli_list_local_human_three_columns(tmp_path: Path) -> None:
    _make_bare_repo(tmp_path, "alpha")
    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--local", "--root", str(tmp_path), "--no-daemon"],
        catch_exceptions=False,
    )
    assert result.exit_code == 0, result.output
    line = result.output.strip()
    # Three whitespace-separated columns: name, path, remote-or-dash.
    parts = line.split()
    assert parts[0] == "alpha"
    assert parts[1] == str(tmp_path / "alpha")
    # Remote missing => `-` placeholder so the column stays aligned.
    assert parts[-1] == "-"


def test_cli_list_local_human_empty_emits_nothing(tmp_path: Path) -> None:
    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--local", "--root", str(tmp_path), "--no-daemon"],
        catch_exceptions=False,
    )
    assert result.exit_code == 0, result.output
    # Empty output (no header line) so pipelines see `wc -l == 0`.
    assert result.output == ""


def test_cli_list_local_warns_on_missing_root_but_exits_zero(
    tmp_path: Path,
) -> None:
    runner = CliRunner()
    missing = tmp_path / "does-not-exist"
    result = runner.invoke(
        cli,
        ["repos", "list", "--local", "--json", "--root", str(missing), "--no-daemon"],
        catch_exceptions=False,
    )
    assert result.exit_code == 0, result.output
    # In click>=8.2 ``mix_stderr`` was removed and CliRunner merges
    # stderr into ``result.output`` by default; the warning hint and the
    # empty JSON array both land in the merged buffer.
    combined = result.output
    # The empty JSON array should be present somewhere in the output.
    assert "[]" in combined
    # And the warning text should have been emitted.
    assert "does-not-exist" in combined


def test_cli_list_local_requires_local_flag() -> None:
    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--no-daemon"],
        catch_exceptions=False,
    )
    # --local is required in this PR; absence is exit 2.
    assert result.exit_code == 2, result.output
    assert "--local" in result.output


def test_cli_list_local_rejects_negative_max_depth(tmp_path: Path) -> None:
    runner = CliRunner()
    result = runner.invoke(
        cli,
        [
            "repos",
            "list",
            "--local",
            "--root",
            str(tmp_path),
            "--max-depth",
            "-1",
            "--no-daemon",
        ],
        catch_exceptions=False,
    )
    assert result.exit_code == 2, result.output


def test_cli_list_local_env_var_used_when_no_root_flag(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    _make_bare_repo(tmp_path, "from-env")
    monkeypatch.setenv("POCKETSHELL_REPOS_ROOTS", str(tmp_path))

    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--local", "--json", "--no-daemon"],
        catch_exceptions=False,
    )
    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert [entry["name"] for entry in payload] == ["from-env"]


# ---------------------------------------------------------------------------
# Daemon integration
# ---------------------------------------------------------------------------


@pytest.fixture()
def sandbox_socket(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
    """Isolate the test from the real user-runtime daemon socket.

    Mirrors the fixture in ``test_daemon.py`` so the daemon-integration
    tests here do not have to import a fixture from a sibling test
    module.
    """
    socket_path = tmp_path / "pocketshell-test.sock"
    monkeypatch.setenv("POCKETSHELL_DAEMON_SOCKET", str(socket_path))
    yield socket_path
    for path in (socket_path, socket_path.with_suffix(".pid")):
        try:
            path.unlink()
        except FileNotFoundError:
            pass


def _spawn_daemon(
    socket_path: Path,
    *,
    idle_timeout: float = 120.0,
    extra_env: dict[str, str] | None = None,
) -> subprocess.Popen:
    env = dict(os.environ)
    env["POCKETSHELL_DAEMON_SOCKET"] = str(socket_path)
    env["POCKETSHELL_DAEMON_IDLE_SECS"] = str(idle_timeout)
    if extra_env:
        env.update(extra_env)
    proc = subprocess.Popen(
        [sys.executable, "-m", "pocketshell", "daemon", "_serve"],
        stdin=subprocess.DEVNULL,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        env=env,
        start_new_session=True,
    )
    assert daemon_mod.wait_until_ready(
        socket_path=socket_path, deadline=10.0
    ), "daemon did not become ready"
    return proc


def _terminate(proc: subprocess.Popen, timeout: float = 5.0) -> None:
    if proc.poll() is not None:
        return
    import signal as _signal

    proc.send_signal(_signal.SIGTERM)
    try:
        proc.wait(timeout=timeout)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait(timeout=2.0)


def test_repos_list_local_round_trips_via_daemon(
    sandbox_socket: Path, tmp_path: Path
) -> None:
    """`repos.list_local` RPC returns the same shape as the in-process scan."""
    work = tmp_path / "work"
    work.mkdir()
    _make_bare_repo(work, "alpha")
    _make_bare_repo(work, "beta")

    proc = _spawn_daemon(sandbox_socket)
    try:
        result = daemon_mod.call(
            "repos.list_local",
            params={"roots": [str(work)], "max_depth": DEFAULT_MAX_DEPTH},
            socket_path=sandbox_socket,
            timeout=10.0,
        )
        assert isinstance(result, list)
        assert len(result) == 2
        names = [entry["name"] for entry in result]
        assert names == ["alpha", "beta"]
        # Each entry has the four documented fields.
        for entry in result:
            assert set(entry.keys()) == {"name", "path", "remote", "head"}
    finally:
        _terminate(proc)


def test_repos_list_local_caches_within_ttl(
    sandbox_socket: Path, tmp_path: Path
) -> None:
    """A repo added between two calls within TTL is not visible on the second."""
    work = tmp_path / "work"
    work.mkdir()
    _make_bare_repo(work, "first")

    proc = _spawn_daemon(sandbox_socket)
    try:
        first = daemon_mod.call(
            "repos.list_local",
            params={"roots": [str(work)], "max_depth": DEFAULT_MAX_DEPTH},
            socket_path=sandbox_socket,
        )
        assert [entry["name"] for entry in first] == ["first"]

        # Add a second repo on disk; without --no-cache the daemon should
        # serve the stale cached result.
        _make_bare_repo(work, "second")
        cached = daemon_mod.call(
            "repos.list_local",
            params={"roots": [str(work)], "max_depth": DEFAULT_MAX_DEPTH},
            socket_path=sandbox_socket,
        )
        assert [entry["name"] for entry in cached] == ["first"]

        # With no_cache the daemon must re-scan and pick up the new dir.
        fresh = daemon_mod.call(
            "repos.list_local",
            params={
                "roots": [str(work)],
                "max_depth": DEFAULT_MAX_DEPTH,
                "no_cache": True,
            },
            socket_path=sandbox_socket,
        )
        assert sorted(entry["name"] for entry in fresh) == ["first", "second"]
    finally:
        _terminate(proc)


def test_cli_repos_list_falls_through_when_daemon_absent(
    sandbox_socket: Path, tmp_path: Path
) -> None:
    """No daemon on disk => CLI must use the in-process scan."""
    work = tmp_path / "work"
    work.mkdir()
    _make_bare_repo(work, "no-daemon-needed")

    assert not sandbox_socket.exists()

    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--local", "--json", "--root", str(work)],
        catch_exceptions=False,
    )
    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert [entry["name"] for entry in payload] == ["no-daemon-needed"]


def test_cli_repos_list_no_daemon_flag_skips_daemon(
    sandbox_socket: Path, tmp_path: Path
) -> None:
    """`--no-daemon` skips the daemon even when one is up."""
    work_daemon = tmp_path / "daemon-only"
    work_daemon.mkdir()
    _make_bare_repo(work_daemon, "in-daemon-root")

    work_cli = tmp_path / "cli-only"
    work_cli.mkdir()
    _make_bare_repo(work_cli, "in-cli-root")

    proc = _spawn_daemon(sandbox_socket)
    try:
        assert daemon_mod.is_daemon_running(sandbox_socket)

        runner = CliRunner()
        # Even though a daemon is up, --no-daemon must force the
        # in-process scan against work_cli (and ignore work_daemon).
        result = runner.invoke(
            cli,
            [
                "repos",
                "list",
                "--local",
                "--json",
                "--root",
                str(work_cli),
                "--no-daemon",
            ],
            catch_exceptions=False,
        )
        assert result.exit_code == 0, result.output
        payload = json.loads(result.output)
        names = [entry["name"] for entry in payload]
        assert names == ["in-cli-root"]
        # Sanity-check the daemon-only root is not present.
        assert "in-daemon-root" not in names
    finally:
        _terminate(proc)


def test_daemon_handler_with_invalid_params_falls_back_to_defaults(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """A malformed `roots` payload must not crash the handler.

    The robustness contract: degrade to defaults rather than throw.
    """
    monkeypatch.setenv("HOME", str(tmp_path))
    # No ~/git directory exists, but the handler must still return a
    # well-shaped (empty) list rather than raising.
    result = daemon_handler({"roots": "not-a-list", "max_depth": "wat"})
    assert isinstance(result, list)


def test_method_ttl_for_repos_list_local_is_ten_seconds() -> None:
    # Pin the cache TTL in test so the spec lives in the suite, not
    # only in the daemon source.
    assert daemon_mod.METHOD_TTLS["repos.list_local"] == DAEMON_CACHE_TTL_SECS
    assert DAEMON_CACHE_TTL_SECS == 10.0
