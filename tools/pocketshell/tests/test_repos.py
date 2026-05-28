"""Unit tests for `pocketshell repos`.

Scope (issue #230 PR-A — first slice of #205):

- Existing ``--local`` coverage (carried from #220):

  - Top-level CLI registration: ``pocketshell --help`` lists ``repos``.
  - ``pocketshell repos list --help`` shows the flags and does not scan.
  - Synthetic-tmpdir scans: a `git init`-style ``.git`` directory is
    picked up; a non-repo directory is not.
  - Skip-pattern: ``node_modules`` under a root must not pollute the
    scan and must NOT be descended into.
  - Depth limit: a repo deeper than ``--max-depth`` is excluded.
  - Non-existent root: warning to stderr + exit 0, other roots still
    scan.
  - Symlinks are not followed.
  - Daemon integration: ``repos.list_local`` round-trips, falls through
    when no daemon, and respects ``--no-daemon``.

- New schema (per D22 hard cut): every entry is the unified shape
  ``{owner, name, full_name, local?, remote?}``. The local-scan path
  populates ``owner``/``full_name`` from the parsed
  ``remote.origin.url`` (GitHub SSH/HTTPS forms only), and always
  populates ``local.path`` + ``local.head``.

- ``--remote`` coverage (new in #242):

  - Stubbed ``gh api user/repos --paginate --slurp`` round-trip (single page
    + multi-page) produces the unified ``RemoteInfo`` shape.
  - Missing ``gh`` exits 127 with the friendly install hint.
  - Non-zero ``gh`` exit propagates returncode and stderr.
  - Daemon round-trip for ``repos.list_remote`` with the 5-min TTL.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest
from click.testing import CliRunner

from pocketshell import daemon as daemon_mod
from pocketshell import repos as repos_mod
from pocketshell.cli import cli
from pocketshell.repos import (
    DAEMON_CACHE_TTL_SECS,
    DAEMON_REMOTE_CACHE_TTL_SECS,
    DEFAULT_MAX_DEPTH,
    DEFAULT_ROOT_PATHS,
    GhCommandError,
    GhMissingError,
    daemon_handler_local,
    daemon_handler_remote,
    fetch_remote_repos,
    find_local_repo,
    github_clone_url,
    normalize_full_name,
    parse_github_remote,
    repos_group,
    resolve_scan_roots,
    safe_clone_target,
    scan_roots,
)


# ---------------------------------------------------------------------------
# Synthetic-repo helpers
# ---------------------------------------------------------------------------


def _make_bare_repo(parent: Path, name: str) -> Path:
    """Create a bare directory with an empty ``.git/`` subdir."""
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
    """Run ``git init`` and optionally configure ``remote.origin.url``."""
    if shutil.which("git") is None:
        pytest.skip("git binary not available")
    repo = parent / name
    repo.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["git", "init", "-q", "-b", "main", str(repo)],
        check=True,
        capture_output=True,
    )
    if remote_url is not None:
        subprocess.run(
            ["git", "-C", str(repo), "config", "remote.origin.url", remote_url],
            check=True,
            capture_output=True,
        )
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


def _write_fake_gh(
    target_dir: Path,
    *,
    pages: list[list[dict]] | None = None,
    payload: str | None = None,
    exit_code: int = 0,
    stderr: str = "",
) -> Path:
    """Write a fake ``gh`` shell script under ``target_dir``.

    Two modes:

    - ``pages`` is a list of JSON arrays — the script emits the
      ``--slurp`` shape, an outer array containing one array per page.
    - ``payload`` is the raw stdout body verbatim.

    ``exit_code`` lets us simulate auth failures and rate limits.
    """
    if pages is not None and payload is not None:
        raise ValueError("pass exactly one of pages / payload")
    script = target_dir / "gh"
    payload_file = target_dir / "_gh_payload.txt"

    if pages is not None:
        payload_file.write_text(json.dumps(pages))
    elif payload is not None:
        payload_file.write_text(payload)
    else:
        payload_file.write_text("[]")

    stderr_file = target_dir / "_gh_stderr.txt"
    stderr_file.write_text(stderr)

    body = textwrap.dedent(
        f"""\
        #!/bin/sh
        # Stub `gh` used by pocketshell tests. Emits a fixed JSON
        # payload on stdout and exits with the configured code.
        cat {payload_file}
        if [ -s {stderr_file} ]; then
            cat {stderr_file} >&2
        fi
        exit {exit_code}
        """
    )
    script.write_text(body)
    script.chmod(0o755)
    return script


def _gh_repo_entry(
    *,
    owner: str,
    name: str,
    default_branch: str = "main",
    updated_at: str = "2026-05-27T00:00:00Z",
) -> dict:
    """Build one ``gh api user/repos`` shaped dict for stub input."""
    return {
        "name": name,
        "full_name": f"{owner}/{name}",
        "owner": {"login": owner},
        "default_branch": default_branch,
        "html_url": f"https://github.com/{owner}/{name}",
        "ssh_url": f"git@github.com:{owner}/{name}.git",
        "updated_at": updated_at,
    }


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
    assert "--remote" in result.output
    assert "--json" in result.output
    assert "--root" in result.output
    assert "--max-depth" in result.output
    assert "--limit" in result.output


# ---------------------------------------------------------------------------
# parse_github_remote
# ---------------------------------------------------------------------------


def test_parse_github_remote_ssh() -> None:
    parsed = parse_github_remote("git@github.com:alexeygrigorev/pocketshell.git")
    assert parsed == ("alexeygrigorev", "pocketshell")


def test_parse_github_remote_ssh_without_suffix() -> None:
    parsed = parse_github_remote("git@github.com:alexeygrigorev/pocketshell")
    assert parsed == ("alexeygrigorev", "pocketshell")


def test_parse_github_remote_https() -> None:
    parsed = parse_github_remote("https://github.com/alexeygrigorev/pocketshell.git")
    assert parsed == ("alexeygrigorev", "pocketshell")


def test_parse_github_remote_https_without_suffix() -> None:
    parsed = parse_github_remote("https://github.com/alexeygrigorev/pocketshell")
    assert parsed == ("alexeygrigorev", "pocketshell")


def test_parse_github_remote_dotted_owner() -> None:
    # GitHub allows ``.`` in user/org slugs; the regex must accept it.
    parsed = parse_github_remote("git@github.com:foo.bar/baz-qux.git")
    assert parsed == ("foo.bar", "baz-qux")


def test_parse_github_remote_non_github_returns_none() -> None:
    assert parse_github_remote("git@gitlab.com:owner/repo.git") is None
    assert parse_github_remote("https://gitlab.com/owner/repo.git") is None
    assert parse_github_remote("git@internal.example.com:owner/repo.git") is None


def test_parse_github_remote_none_and_empty() -> None:
    assert parse_github_remote(None) is None
    assert parse_github_remote("") is None


# ---------------------------------------------------------------------------
# resolve_scan_roots
# ---------------------------------------------------------------------------


def test_resolve_scan_roots_default_to_home_git(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv("POCKETSHELL_REPOS_ROOTS", raising=False)
    monkeypatch.setenv("HOME", "/home/test")
    roots = resolve_scan_roots()
    assert roots == [Path("/home/test/git")]
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
    # Every entry has `local` populated and `remote` is None on a local scan.
    for repo in repos:
        assert repo.local is not None
        assert repo.remote is None


def test_scan_roots_ignores_non_repo_directories(tmp_path: Path) -> None:
    (tmp_path / "not-a-repo").mkdir()
    _make_bare_repo(tmp_path, "yes-a-repo")
    repos = scan_roots([tmp_path])
    assert [r.name for r in repos] == ["yes-a-repo"]


def test_scan_roots_skips_node_modules(tmp_path: Path) -> None:
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
    _make_bare_repo(tmp_path, "shallow")
    _make_bare_repo(tmp_path / "group", "mid")
    _make_bare_repo(tmp_path / "a" / "b", "deep")
    repos = scan_roots([tmp_path], max_depth=2)
    names = sorted(r.name for r in repos)
    assert names == ["mid", "shallow"]
    assert "deep" not in names


def test_scan_roots_default_depth_is_4(tmp_path: Path) -> None:
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
    outer = _make_bare_repo(tmp_path, "outer")
    nested = outer / "submodule"
    nested.mkdir()
    (nested / ".git").mkdir()
    repos = scan_roots([tmp_path])
    assert [r.name for r in repos] == ["outer"]


def test_scan_roots_does_not_follow_symlinks(tmp_path: Path) -> None:
    real = _make_bare_repo(tmp_path, "real")
    link = tmp_path / "link-to-real"
    try:
        os.symlink(real, link, target_is_directory=True)
    except (OSError, NotImplementedError):
        pytest.skip("symlinks not supported on this platform")
    repos = scan_roots([tmp_path])
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
    repo = _make_bare_repo(tmp_path, "uniq")
    repos = scan_roots([tmp_path, tmp_path])
    assert [r.name for r in repos] == ["uniq"]
    assert repos[0].local is not None
    assert repos[0].local.path == str(repo)


# ---------------------------------------------------------------------------
# Local scan: unified schema population
# ---------------------------------------------------------------------------


def test_scan_roots_populates_local_path_and_head(tmp_path: Path) -> None:
    repo = _make_real_repo(
        tmp_path,
        "with-remote",
        remote_url="git@github.com:owner/with-remote.git",
    )
    repos = scan_roots([tmp_path])
    assert len(repos) == 1
    only = repos[0]
    assert only.name == "with-remote"
    assert only.local is not None
    assert only.local.path == str(repo)
    # Branch is `main` (we forced -b main on `git init`); some older git
    # versions stick to `master` though, so accept either.
    assert only.local.head in ("main", "master")


def test_scan_roots_populates_owner_from_github_ssh_remote(tmp_path: Path) -> None:
    _make_real_repo(
        tmp_path,
        "with-remote",
        remote_url="git@github.com:alexeygrigorev/with-remote.git",
    )
    repos = scan_roots([tmp_path])
    assert len(repos) == 1
    only = repos[0]
    assert only.owner == "alexeygrigorev"
    assert only.full_name == "alexeygrigorev/with-remote"


def test_scan_roots_populates_owner_from_github_https_remote(tmp_path: Path) -> None:
    _make_real_repo(
        tmp_path,
        "with-remote",
        remote_url="https://github.com/alexeygrigorev/with-remote.git",
    )
    repos = scan_roots([tmp_path])
    only = repos[0]
    assert only.owner == "alexeygrigorev"
    assert only.full_name == "alexeygrigorev/with-remote"


def test_scan_roots_owner_none_for_non_github_remote(tmp_path: Path) -> None:
    _make_real_repo(
        tmp_path,
        "with-gitlab",
        remote_url="git@gitlab.com:owner/with-gitlab.git",
    )
    repos = scan_roots([tmp_path])
    only = repos[0]
    assert only.owner is None
    assert only.full_name is None
    # ``name`` still falls back to the directory basename.
    assert only.name == "with-gitlab"


def test_scan_roots_owner_none_when_remote_unset(tmp_path: Path) -> None:
    _make_real_repo(tmp_path, "no-remote")
    repos = scan_roots([tmp_path])
    only = repos[0]
    assert only.owner is None
    assert only.full_name is None
    assert only.local is not None


def test_name_derives_from_directory_not_remote(tmp_path: Path) -> None:
    """Even when the remote URL points at a different name, the local
    directory's basename is the source of truth for the ``name`` field.

    Justification: forks and locally-renamed clones keep stable identity.
    ``full_name`` carries the canonical GitHub identity separately.
    """
    _make_real_repo(
        tmp_path,
        "my-fork",
        remote_url="git@github.com:upstream-org/upstream-name.git",
    )
    repos = scan_roots([tmp_path])
    assert len(repos) == 1
    only = repos[0]
    assert only.name == "my-fork"
    # owner/full_name reflect the canonical GitHub identity.
    assert only.owner == "upstream-org"
    assert only.full_name == "upstream-org/upstream-name"


# ---------------------------------------------------------------------------
# Clone/open helpers
# ---------------------------------------------------------------------------


def test_normalize_full_name_accepts_owner_repo() -> None:
    assert normalize_full_name(" alexeygrigorev/pocketshell.git ") == (
        "alexeygrigorev",
        "pocketshell",
    )


@pytest.mark.parametrize(
    "value",
    ["pocketshell", "../owner/repo", "owner/repo/extra", "https://github.com/o/r"],
)
def test_normalize_full_name_rejects_non_slugs(value: str) -> None:
    with pytest.raises(ValueError):
        normalize_full_name(value)


def test_github_clone_url_supports_ssh_and_https() -> None:
    assert (
        github_clone_url("alexeygrigorev/pocketshell")
        == "git@github.com:alexeygrigorev/pocketshell.git"
    )
    assert (
        github_clone_url("alexeygrigorev/pocketshell", protocol="https")
        == "https://github.com/alexeygrigorev/pocketshell.git"
    )


def test_safe_clone_target_stays_under_root(tmp_path: Path) -> None:
    assert safe_clone_target(tmp_path, "owner/project") == tmp_path / "project"
    assert safe_clone_target(tmp_path, "owner/project", "custom") == tmp_path / "custom"
    with pytest.raises(ValueError):
        safe_clone_target(tmp_path, "owner/project", "../escape")
    with pytest.raises(ValueError):
        safe_clone_target(tmp_path, "owner/project", "/tmp/escape")


def test_find_local_repo_prefers_full_name_over_directory_name(tmp_path: Path) -> None:
    _make_real_repo(
        tmp_path,
        "renamed",
        remote_url="git@github.com:alexeygrigorev/pocketshell.git",
    )
    repo = find_local_repo("alexeygrigorev/pocketshell", roots=[tmp_path])
    assert repo is not None
    assert repo.name == "renamed"
    assert repo.local is not None
    assert repo.local.path == str(tmp_path / "renamed")


def test_cli_repos_open_prints_local_clone_path(tmp_path: Path) -> None:
    repo_path = _make_real_repo(
        tmp_path,
        "pocketshell",
        remote_url="https://github.com/alexeygrigorev/pocketshell.git",
    )
    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "open", "alexeygrigorev/pocketshell", "--root", str(tmp_path)],
        catch_exceptions=False,
    )
    assert result.exit_code == 0, result.output
    assert result.output.strip() == str(repo_path)


def test_cli_repos_open_reports_missing_clone(tmp_path: Path) -> None:
    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "open", "alexeygrigorev/pocketshell", "--root", str(tmp_path)],
        catch_exceptions=False,
    )
    assert result.exit_code == 1, result.output
    assert "not cloned" in result.output


def test_cli_repos_clone_invokes_git_clone(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    calls: list[list[str]] = []

    def fake_run(args: list[str], **_kwargs: object) -> subprocess.CompletedProcess[str]:
        calls.append(args)
        return subprocess.CompletedProcess(args=args, returncode=0)

    monkeypatch.setattr(repos_mod.subprocess, "run", fake_run)
    runner = CliRunner()
    result = runner.invoke(
        cli,
        [
            "repos",
            "clone",
            "alexeygrigorev/pocketshell",
            "--root",
            str(tmp_path),
            "--protocol",
            "https",
        ],
        catch_exceptions=False,
    )
    assert result.exit_code == 0, result.output
    assert result.output.strip() == str(tmp_path / "pocketshell")
    assert calls == [
        [
            "git",
            "clone",
            "https://github.com/alexeygrigorev/pocketshell.git",
            str(tmp_path / "pocketshell"),
        ]
    ]


def test_cli_repos_clone_rejects_existing_target(tmp_path: Path) -> None:
    (tmp_path / "pocketshell").mkdir()
    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "clone", "alexeygrigorev/pocketshell", "--root", str(tmp_path)],
        catch_exceptions=False,
    )
    assert result.exit_code == 1, result.output
    assert "already exists" in result.output


# ---------------------------------------------------------------------------
# CLI output: --json + human (local mode)
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
    assert [entry["name"] for entry in payload] == ["alpha", "beta"]
    # Unified schema: every entry has owner/name/full_name/local/remote
    # at the top level. ``local`` is populated; ``remote`` is None.
    for entry in payload:
        assert set(entry.keys()) == {"owner", "name", "full_name", "local", "remote"}
        assert entry["remote"] is None
        assert isinstance(entry["local"], dict)
        assert set(entry["local"].keys()) == {"path", "head"}


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
    parts = line.split()
    assert parts[0] == "alpha"
    assert parts[1] == str(tmp_path / "alpha")
    # full_name missing => `-` placeholder.
    assert parts[-1] == "-"


def test_cli_list_local_human_empty_emits_nothing(tmp_path: Path) -> None:
    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--local", "--root", str(tmp_path), "--no-daemon"],
        catch_exceptions=False,
    )
    assert result.exit_code == 0, result.output
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
    combined = result.output
    assert "[]" in combined
    assert "does-not-exist" in combined


def test_cli_list_defaults_to_local_with_hint() -> None:
    """No --local / --remote: must default to local and emit a hint."""
    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--no-daemon", "--json", "--root", "/nonexistent-root"],
        catch_exceptions=False,
    )
    # Exit 0 with a warning about the missing root + the hint mentioning --remote.
    assert result.exit_code == 0, result.output
    assert "--remote" in result.output


def test_cli_list_local_and_remote_mutually_exclusive() -> None:
    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--local", "--remote", "--no-daemon"],
        catch_exceptions=False,
    )
    assert result.exit_code == 2, result.output
    assert "mutually exclusive" in result.output


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
# fetch_remote_repos (subprocess stub)
# ---------------------------------------------------------------------------


def test_fetch_remote_repos_single_page(tmp_path: Path) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake_gh(
        bin_dir,
        pages=[
            [
                _gh_repo_entry(
                    owner="alexeygrigorev",
                    name="pocketshell",
                    updated_at="2026-05-27T12:00:00Z",
                ),
                _gh_repo_entry(
                    owner="alexeygrigorev",
                    name="quse",
                    updated_at="2026-05-20T08:00:00Z",
                ),
            ]
        ],
    )
    repos = fetch_remote_repos(gh_binary=str(bin_dir / "gh"))
    # Sorted by updated_at descending: pocketshell (newer) first.
    assert [r.name for r in repos] == ["pocketshell", "quse"]
    first = repos[0]
    assert first.owner == "alexeygrigorev"
    assert first.full_name == "alexeygrigorev/pocketshell"
    assert first.local is None
    assert first.remote is not None
    assert first.remote.default_branch == "main"
    assert first.remote.html_url == "https://github.com/alexeygrigorev/pocketshell"
    assert first.remote.ssh_url == "git@github.com:alexeygrigorev/pocketshell.git"
    assert first.remote.updated_at == "2026-05-27T12:00:00Z"


def test_fetch_remote_repos_multi_page_slurp_output(tmp_path: Path) -> None:
    """`gh --paginate --slurp` wraps page arrays in one outer array."""
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    page_one = [
        _gh_repo_entry(owner="alexeygrigorev", name=f"page1-{i}", updated_at=f"2026-05-2{i}T00:00:00Z")
        for i in range(3)
    ]
    page_two = [
        _gh_repo_entry(owner="alexeygrigorev", name=f"page2-{i}", updated_at=f"2026-05-1{i}T00:00:00Z")
        for i in range(2)
    ]
    _write_fake_gh(bin_dir, pages=[page_one, page_two])
    repos = fetch_remote_repos(gh_binary=str(bin_dir / "gh"))
    assert len(repos) == 5
    # Sorted by updated_at descending; page-one rows are newer than page-two rows.
    assert repos[0].name == "page1-2"
    assert repos[-1].name == "page2-0"


def test_fetch_remote_repos_respects_limit(tmp_path: Path) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    pages = [
        [
            _gh_repo_entry(owner="o", name=f"r{i}", updated_at=f"2026-05-{i:02d}T00:00:00Z")
            for i in range(1, 11)
        ]
    ]
    _write_fake_gh(bin_dir, pages=pages)
    repos = fetch_remote_repos(gh_binary=str(bin_dir / "gh"), limit=3)
    assert len(repos) == 3
    # Top 3 by updated_at descending.
    assert [r.name for r in repos] == ["r10", "r9", "r8"]


def test_fetch_remote_repos_requests_owner_affiliation(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    calls: list[list[str]] = []

    class Completed:
        returncode = 0
        stdout = "[]"
        stderr = ""

    def fake_run(args: list[str], **_kwargs: object) -> Completed:
        calls.append(args)
        return Completed()

    monkeypatch.setattr(repos_mod, "_resolve_gh_binary", lambda: "/usr/bin/gh")
    monkeypatch.setattr(subprocess, "run", fake_run)

    fetch_remote_repos()

    assert calls == [
        [
            "/usr/bin/gh",
            "api",
            "user/repos?per_page=100&affiliation=owner&sort=updated",
            "--paginate",
            "--slurp",
        ]
    ]


def test_fetch_remote_repos_missing_gh_raises() -> None:
    # Override _resolve_gh_binary via the public API: pass gh_binary=None
    # while ensuring shutil.which can't find ``gh``. Easiest: stub the
    # resolver function directly.
    original = repos_mod._resolve_gh_binary
    repos_mod._resolve_gh_binary = lambda: None
    try:
        with pytest.raises(GhMissingError) as excinfo:
            fetch_remote_repos()
        assert "gh auth login" in str(excinfo.value)
    finally:
        repos_mod._resolve_gh_binary = original


def test_fetch_remote_repos_nonzero_exit_raises(tmp_path: Path) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake_gh(
        bin_dir,
        payload="",
        exit_code=4,
        stderr="gh: not authenticated. Run `gh auth login`.\n",
    )
    with pytest.raises(GhCommandError) as excinfo:
        fetch_remote_repos(gh_binary=str(bin_dir / "gh"))
    err = excinfo.value
    assert err.returncode == 4
    assert "not authenticated" in err.stderr


def test_fetch_remote_repos_empty_stdout(tmp_path: Path) -> None:
    """A whitespace-only stdout body must produce an empty list, not crash."""
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake_gh(bin_dir, payload="   \n")
    repos = fetch_remote_repos(gh_binary=str(bin_dir / "gh"))
    assert repos == []


# ---------------------------------------------------------------------------
# CLI output: --remote
# ---------------------------------------------------------------------------


def test_cli_list_remote_json_shape(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake_gh(
        bin_dir,
        pages=[
            [
                _gh_repo_entry(owner="o", name="r1", updated_at="2026-05-26T00:00:00Z"),
                _gh_repo_entry(owner="o", name="r2", updated_at="2026-05-27T00:00:00Z"),
            ]
        ],
    )
    monkeypatch.setattr(repos_mod, "_resolve_gh_binary", lambda: str(bin_dir / "gh"))

    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--remote", "--json", "--no-daemon"],
        catch_exceptions=False,
    )
    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert isinstance(payload, list)
    assert [entry["name"] for entry in payload] == ["r2", "r1"]
    for entry in payload:
        assert set(entry.keys()) == {"owner", "name", "full_name", "local", "remote"}
        assert entry["local"] is None
        assert isinstance(entry["remote"], dict)
        assert set(entry["remote"].keys()) == {
            "default_branch",
            "html_url",
            "ssh_url",
            "updated_at",
        }
        assert entry["owner"] == "o"


def test_cli_list_remote_missing_gh_exits_127(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    monkeypatch.setattr(repos_mod, "_resolve_gh_binary", lambda: None)
    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--remote", "--json", "--no-daemon"],
        catch_exceptions=False,
    )
    assert result.exit_code == 127, result.output
    assert "gh" in result.output
    assert "gh auth login" in result.output


def test_cli_list_remote_nonzero_exit_propagates(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake_gh(
        bin_dir,
        payload="",
        exit_code=4,
        stderr="gh: rate limit exceeded\n",
    )
    monkeypatch.setattr(repos_mod, "_resolve_gh_binary", lambda: str(bin_dir / "gh"))

    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--remote", "--json", "--no-daemon"],
        catch_exceptions=False,
    )
    assert result.exit_code == 4, result.output
    assert "rate limit" in result.output


def test_cli_list_remote_human_format(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake_gh(
        bin_dir,
        pages=[
            [_gh_repo_entry(owner="o", name="r", updated_at="2026-05-27T00:00:00Z")]
        ],
    )
    monkeypatch.setattr(repos_mod, "_resolve_gh_binary", lambda: str(bin_dir / "gh"))

    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--remote", "--no-daemon"],
        catch_exceptions=False,
    )
    assert result.exit_code == 0, result.output
    line = result.output.strip()
    parts = line.split()
    assert parts[0] == "o/r"
    assert parts[1] == "main"
    assert parts[2] == "2026-05-27T00:00:00Z"


def test_cli_list_remote_limit_passthrough(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake_gh(
        bin_dir,
        pages=[
            [
                _gh_repo_entry(owner="o", name=f"r{i}", updated_at=f"2026-05-{i:02d}T00:00:00Z")
                for i in range(1, 6)
            ]
        ],
    )
    monkeypatch.setattr(repos_mod, "_resolve_gh_binary", lambda: str(bin_dir / "gh"))

    runner = CliRunner()
    result = runner.invoke(
        cli,
        ["repos", "list", "--remote", "--json", "--limit", "2", "--no-daemon"],
        catch_exceptions=False,
    )
    assert result.exit_code == 0, result.output
    payload = json.loads(result.output)
    assert len(payload) == 2


# ---------------------------------------------------------------------------
# Daemon integration
# ---------------------------------------------------------------------------


@pytest.fixture()
def sandbox_socket(tmp_path: Path, monkeypatch: pytest.MonkeyPatch) -> Path:
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
        # Each entry has the unified-schema fields.
        for entry in result:
            assert set(entry.keys()) == {"owner", "name", "full_name", "local", "remote"}
            assert entry["remote"] is None
            assert isinstance(entry["local"], dict)
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

        _make_bare_repo(work, "second")
        cached = daemon_mod.call(
            "repos.list_local",
            params={"roots": [str(work)], "max_depth": DEFAULT_MAX_DEPTH},
            socket_path=sandbox_socket,
        )
        assert [entry["name"] for entry in cached] == ["first"]

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
        assert "in-daemon-root" not in names
    finally:
        _terminate(proc)


def test_daemon_handler_local_with_invalid_params_falls_back_to_defaults(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    monkeypatch.setenv("HOME", str(tmp_path))
    result = daemon_handler_local({"roots": "not-a-list", "max_depth": "wat"})
    assert isinstance(result, list)


def test_daemon_handler_remote_returns_unified_shape(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake_gh(
        bin_dir,
        pages=[
            [_gh_repo_entry(owner="o", name="r", updated_at="2026-05-27T00:00:00Z")]
        ],
    )
    monkeypatch.setattr(repos_mod, "_resolve_gh_binary", lambda: str(bin_dir / "gh"))
    payload = daemon_handler_remote({})
    assert isinstance(payload, list)
    assert len(payload) == 1
    entry = payload[0]
    assert set(entry.keys()) == {"owner", "name", "full_name", "local", "remote"}
    assert entry["local"] is None
    assert entry["remote"]["default_branch"] == "main"


def test_method_ttl_for_repos_list_local_is_ten_seconds() -> None:
    assert daemon_mod.METHOD_TTLS["repos.list_local"] == DAEMON_CACHE_TTL_SECS
    assert DAEMON_CACHE_TTL_SECS == 10.0


def test_method_ttl_for_repos_list_remote_is_five_minutes() -> None:
    assert daemon_mod.METHOD_TTLS["repos.list_remote"] == DAEMON_REMOTE_CACHE_TTL_SECS
    assert DAEMON_REMOTE_CACHE_TTL_SECS == 300.0


def test_repos_list_remote_round_trips_via_daemon(
    sandbox_socket: Path, tmp_path: Path
) -> None:
    """`repos.list_remote` RPC returns the unified shape via the daemon."""
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    _write_fake_gh(
        bin_dir,
        pages=[
            [
                _gh_repo_entry(owner="o", name="r1", updated_at="2026-05-26T00:00:00Z"),
                _gh_repo_entry(owner="o", name="r2", updated_at="2026-05-27T00:00:00Z"),
            ]
        ],
    )
    # Inject the fake gh into the daemon child's PATH so its
    # ``_resolve_gh_binary()`` lookup finds the stub.
    env = {"PATH": f"{bin_dir}:{os.environ.get('PATH', '')}"}
    proc = _spawn_daemon(sandbox_socket, extra_env=env)
    try:
        result = daemon_mod.call(
            "repos.list_remote",
            params={},
            socket_path=sandbox_socket,
            timeout=15.0,
        )
        assert isinstance(result, list)
        assert len(result) == 2
        assert [entry["name"] for entry in result] == ["r2", "r1"]
        for entry in result:
            assert set(entry.keys()) == {"owner", "name", "full_name", "local", "remote"}
            assert entry["local"] is None
            assert entry["remote"]["default_branch"] == "main"
    finally:
        _terminate(proc)


def test_repos_list_remote_caches_within_ttl(
    sandbox_socket: Path, tmp_path: Path
) -> None:
    """Within the 5-min TTL a second call must serve the cached envelope."""
    bin_dir = tmp_path / "bin"
    bin_dir.mkdir()
    counter_file = tmp_path / "counter"
    counter_file.write_text("0")
    script = bin_dir / "gh"
    # Each invocation bumps the counter and emits a single repo with the
    # incremented name so the test can tell apart cached vs. fresh.
    script.write_text(
        "#!/bin/sh\n"
        f"n=$(cat {counter_file})\n"
        "n=$((n+1))\n"
        f'echo "$n" > {counter_file}\n'
        'printf \'[{"name":"r%s","full_name":"o/r%s","owner":{"login":"o"},'
        '"default_branch":"main","html_url":"https://x","ssh_url":"git@x:o/r%s.git",'
        '"updated_at":"2026-05-27T00:00:00Z"}]\' "$n" "$n" "$n"\n'
    )
    script.chmod(0o755)

    env = {"PATH": f"{bin_dir}:{os.environ.get('PATH', '')}"}
    proc = _spawn_daemon(sandbox_socket, extra_env=env)
    try:
        first = daemon_mod.call(
            "repos.list_remote",
            params={},
            socket_path=sandbox_socket,
            timeout=15.0,
        )
        assert first[0]["name"] == "r1"

        # Within TTL: must serve cached r1, not re-run gh.
        cached = daemon_mod.call(
            "repos.list_remote",
            params={},
            socket_path=sandbox_socket,
            timeout=15.0,
        )
        assert cached[0]["name"] == "r1"
        assert counter_file.read_text().strip() == "1"

        # With no_cache: must re-run gh.
        fresh = daemon_mod.call(
            "repos.list_remote",
            params={"no_cache": True},
            socket_path=sandbox_socket,
            timeout=15.0,
        )
        assert fresh[0]["name"] == "r2"
        assert counter_file.read_text().strip() == "2"
    finally:
        _terminate(proc)
