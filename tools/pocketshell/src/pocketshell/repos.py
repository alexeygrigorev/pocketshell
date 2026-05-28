"""`pocketshell repos` subcommand group.

GitHub-aware project navigation. Two scan modes share a unified output
schema so the Android picker (PR-B follow-up) can render local clones,
remote repositories from the user's GitHub account, and the union of
the two from one JSON shape.

Subcommands
-----------

- ``pocketshell repos list --local`` — scan one or more roots on disk
  for cloned git repositories. Best-effort: missing roots warn to
  stderr, unreadable repos produce ``None`` metadata rather than
  aborting the scan.
- ``pocketshell repos list --remote`` — delegate to
  ``gh api user/repos --paginate --slurp`` (subprocess; same pattern as
  ``pocketshell usage`` delegating to ``quse``). Phone holds zero
  GitHub credentials — locked as D23.
- ``pocketshell repos list`` (no flag) — defaults to ``--local`` and
  prints a one-line hint to stderr mentioning ``--remote``. Rationale:
  the existing behaviour was ``--local``; mixing two scan modes (one
  filesystem + one network) implicitly under one command is surprising
  for scripts that pipe the output. Keeping the existing default
  preserves muscle memory; ``--remote`` is opt-in.

Unified output schema (D22 hard cut)
------------------------------------

Every entry — whether produced by the local scan, the remote scan, or
the eventual merged-view command — uses one shape:

.. code-block:: json

    {
      "owner": "alexeygrigorev" | null,
      "name": "pocketshell",
      "full_name": "alexeygrigorev/pocketshell" | null,
      "local": {"path": "/home/...", "head": "main"} | null,
      "remote": {
        "default_branch": "main",
        "html_url": "https://github.com/...",
        "ssh_url": "git@github.com:...",
        "updated_at": "2026-05-27T12:00:00Z"
      } | null
    }

The previous ``{name, path, remote, head}`` shape is gone. No
compatibility shim, no version flag — per D22 in
``docs/decisions.md``. The Android consumer for ``repos list`` has
not yet shipped (no Kotlin parser exists in ``app/`` / ``shared/``),
so the schema swap is purely server-side.

Local-scan owner/name/full_name population
------------------------------------------

For a local clone we populate ``owner``/``full_name`` by parsing the
remote URL stored at ``remote.origin.url``. Supported forms:

- SSH: ``git@github.com:<owner>/<repo>[.git]``
- HTTPS: ``https://github.com/<owner>/<repo>[.git]``

Non-GitHub remotes (gitlab.com, gitea, internal hosts) currently leave
``owner``/``full_name`` as ``None``; ``name`` falls back to the
directory basename so identity stays stable.

Daemon integration
------------------

The daemon (``pocketshell.daemon``) gets two RPC methods:

- ``repos.list_local`` — 10 s TTL, same as the original PR.
- ``repos.list_remote`` — 5 min TTL. Remote repos change rarely; the
  GH API has tight rate limits (5000/hour for authenticated calls), so
  a longer cache window keeps the Android picker responsive without
  burning quota.

Both subcommands honour ``--no-daemon`` (skip the daemon entirely) and
``--no-cache`` (force the daemon to re-run upstream). On the
in-process path ``--no-cache`` is a no-op because there is no cache.
"""

from __future__ import annotations

import json
import os
import re
import shutil
import subprocess
import sys
from dataclasses import asdict, dataclass
from pathlib import Path
from typing import Any, Iterable, Iterator, Optional, Sequence

import click


# Directories that ``scan_roots`` refuses to descend into. Speeds up
# scans on dev boxes that keep large dependency caches under their
# normal source-code root and matches the spike's recommendation.
DEFAULT_SKIP_DIRS: frozenset[str] = frozenset(
    {".git", "node_modules", ".venv", "venv", "dist", "build", "target"}
)

# Default scan depth — see module docstring for justification.
DEFAULT_MAX_DEPTH = 4

# Default scan root when neither ``--root`` nor ``POCKETSHELL_REPOS_ROOTS``
# is set. Single-entry list to keep ``resolve_scan_roots`` symmetrical
# with the explicit-list cases.
DEFAULT_ROOT_PATHS: tuple[str, ...] = ("~/git",)

# Daemon-side TTL for ``repos.list_local``. Pulled into the module so
# the daemon and the docstring agree without duplicating the literal.
DAEMON_CACHE_TTL_SECS = 10.0

# Daemon-side TTL for ``repos.list_remote``. GH API rate-limited
# (5000/hour authenticated) so a longer cache keeps the picker fast
# without burning quota; remote repos rarely change minute-to-minute.
DAEMON_REMOTE_CACHE_TTL_SECS = 300.0

# Default page size for the GH API call. 100 is the GitHub-side
# maximum; smaller values just multiply the round-trips ``--paginate``
# takes to walk the full account.
GH_API_PER_PAGE = 100


# ---------------------------------------------------------------------------
# Unified data classes
# ---------------------------------------------------------------------------


@dataclass(frozen=True)
class LocalInfo:
    """Local clone metadata. Always paired with the unified ``Repo``."""

    path: str
    head: Optional[str]


@dataclass(frozen=True)
class RemoteInfo:
    """Remote (GitHub) metadata. Lifted straight from the GH API payload."""

    default_branch: Optional[str]
    html_url: Optional[str]
    ssh_url: Optional[str]
    updated_at: Optional[str]


@dataclass(frozen=True)
class Repo:
    """A unified repository entry.

    Either ``local`` or ``remote`` (or both, for a future merged view)
    is populated. ``owner``/``full_name`` may be ``None`` when the
    remote URL is missing or non-GitHub on a local-only scan.
    """

    name: str
    owner: Optional[str] = None
    full_name: Optional[str] = None
    local: Optional[LocalInfo] = None
    remote: Optional[RemoteInfo] = None


def _repo_to_dict(repo: Repo) -> dict[str, Any]:
    """Render a :class:`Repo` to its canonical JSON dict shape.

    Uses :func:`dataclasses.asdict` for the nested structures so the
    serialised shape and the dataclass definition cannot drift.
    """
    return {
        "owner": repo.owner,
        "name": repo.name,
        "full_name": repo.full_name,
        "local": asdict(repo.local) if repo.local is not None else None,
        "remote": asdict(repo.remote) if repo.remote is not None else None,
    }


# ---------------------------------------------------------------------------
# Remote URL parsing
# ---------------------------------------------------------------------------


# Match SSH form: ``git@github.com:<owner>/<repo>[.git]``. Owner and
# repo must be non-empty and contain only the URL-safe characters
# GitHub allows in slugs (``[\w.-]+`` is a superset; we keep it loose
# rather than tracking GitHub's exact slug rules).
_SSH_REMOTE_RE = re.compile(
    r"^git@github\.com:(?P<owner>[\w.-]+)/(?P<repo>[\w.-]+?)(?:\.git)?/?$"
)

# Match HTTPS form: ``https://github.com/<owner>/<repo>[.git]``. We
# also accept the rare ``http://`` and ``git://`` variants since they
# show up in older clones.
_HTTPS_REMOTE_RE = re.compile(
    r"^(?:https?|git)://github\.com/(?P<owner>[\w.-]+)/(?P<repo>[\w.-]+?)(?:\.git)?/?$"
)


def parse_github_remote(url: Optional[str]) -> Optional[tuple[str, str]]:
    """Return ``(owner, repo)`` parsed from a GitHub remote URL, or None.

    Supported forms (most common first):

    - ``git@github.com:owner/repo[.git]`` (SSH; the default for
      ``git clone`` when GitHub's "Copy" button is set to SSH).
    - ``https://github.com/owner/repo[.git]`` (HTTPS; the default
      when "Copy" is set to HTTPS).

    A non-GitHub URL (gitlab, gitea, internal host) returns ``None``
    so the caller can fall back to the directory basename for naming.
    """
    if not url:
        return None
    for pattern in (_SSH_REMOTE_RE, _HTTPS_REMOTE_RE):
        match = pattern.match(url.strip())
        if match:
            return match.group("owner"), match.group("repo")
    return None


# ---------------------------------------------------------------------------
# Root resolution
# ---------------------------------------------------------------------------


def resolve_scan_roots(
    explicit_roots: Sequence[str] = (),
    *,
    env: Optional[dict[str, str]] = None,
) -> list[Path]:
    """Return the ordered list of scan roots given CLI args + env.

    Precedence (highest to lowest, matching the docstring):

    1. ``explicit_roots`` from ``--root`` (CLI args).
    2. ``POCKETSHELL_REPOS_ROOTS`` env var (colon-separated like PATH).
    3. ``DEFAULT_ROOT_PATHS`` (currently just ``~/git``).

    Each entry is expanded via :func:`os.path.expanduser` so ``~/git``
    resolves to the caller's home dir. Duplicates are dropped while
    preserving the first occurrence so a user passing the same root
    twice does not see duplicate output.
    """
    sources: Sequence[str]
    if explicit_roots:
        sources = explicit_roots
    else:
        env_map = env if env is not None else os.environ
        env_value = env_map.get("POCKETSHELL_REPOS_ROOTS")
        if env_value:
            # Split on ``:`` like PATH. Empty entries (e.g. trailing
            # colon) are skipped silently — they would otherwise expand
            # to the current working dir, which is rarely useful and
            # is a common copy-paste footgun.
            sources = [part for part in env_value.split(":") if part]
        else:
            sources = DEFAULT_ROOT_PATHS

    seen: set[Path] = set()
    result: list[Path] = []
    for raw in sources:
        path = Path(os.path.expanduser(raw))
        if path in seen:
            continue
        seen.add(path)
        result.append(path)
    return result


# ---------------------------------------------------------------------------
# Local scan
# ---------------------------------------------------------------------------


def _is_git_repo(candidate: Path) -> bool:
    """Return True if ``candidate`` contains a ``.git`` entry.

    Accepts both directory and file forms of ``.git``:

    - Directory: a normal clone (``<repo>/.git/``).
    - File: a git worktree or submodule (``<repo>/.git`` points at the
      real git dir via ``gitdir: ...`` text content).
    """
    return (candidate / ".git").exists()


def _read_git_metadata(repo_path: Path) -> tuple[Optional[str], Optional[str]]:
    """Read ``(remote_url, head_branch)`` for a repo using ``git`` itself.

    Both fields are best-effort: any subprocess failure (missing git
    binary, corrupted repo, detached HEAD) yields ``None`` instead of
    propagating an exception. The scan loop must never abort on a
    single bad clone.

    ``git rev-parse --abbrev-ref HEAD`` returns ``HEAD`` for a detached
    HEAD; we map that to ``None`` so the JSON consumer can tell apart
    "on a branch named HEAD" (impossible) from "no branch checked out".
    """
    remote = _git_config_get(repo_path, "remote.origin.url")
    # Prefer ``rev-parse --abbrev-ref HEAD`` because it reflects the
    # working state (detached HEAD => the literal string ``HEAD``). On
    # an empty repository (just ``git init``, no commits yet)
    # ``rev-parse`` errors; ``symbolic-ref --short HEAD`` still returns
    # the branch name from ``.git/HEAD``. Falling back keeps brand-new
    # clones visible to the picker.
    head_raw = _git_run(repo_path, ["rev-parse", "--abbrev-ref", "HEAD"])
    head: Optional[str]
    if head_raw == "HEAD":
        # Detached HEAD: no branch name to surface.
        head = None
    elif head_raw is None:
        # Empty repo or rev-parse failure — try the symbolic ref.
        symbolic = _git_run(repo_path, ["symbolic-ref", "--short", "HEAD"])
        head = symbolic if symbolic else None
    else:
        head = head_raw
    return remote, head


def _git_run(repo_path: Path, args: Sequence[str], *, timeout: float = 5.0) -> Optional[str]:
    """Run ``git -C <repo_path> <args>`` and return stripped stdout, or None.

    None on any failure (non-zero exit, missing binary, timeout). 5 s
    per-call timeout protects the scan from a slow filesystem hang on
    an NFS/sshfs mount.
    """
    try:
        completed = subprocess.run(
            ["git", "-C", str(repo_path), *args],
            check=False,
            capture_output=True,
            text=True,
            timeout=timeout,
        )
    except (FileNotFoundError, subprocess.TimeoutExpired, OSError):
        return None
    if completed.returncode != 0:
        return None
    output = completed.stdout.strip()
    return output if output else None


def _git_config_get(repo_path: Path, key: str) -> Optional[str]:
    """Run ``git -C <repo> config --get <key>`` and return stripped value."""
    return _git_run(repo_path, ["config", "--get", key])


def _walk_for_repos(
    root: Path,
    *,
    max_depth: int,
    skip_dirs: Iterable[str] = DEFAULT_SKIP_DIRS,
) -> Iterator[Path]:
    """Yield repository directories under ``root`` up to ``max_depth`` deep.

    Depth semantics: a repo at ``<root>/<a>`` is depth 1; ``<root>/<a>/<b>``
    is depth 2; etc. ``max_depth`` is the deepest level we still scan.
    A ``.git`` entry at depth N marks the parent (depth N-1) as a repo;
    we never descend into a repo we already detected (so monorepos do
    not nest-explode).

    Symlinks are NOT followed: ``os.scandir`` does not auto-resolve, and
    we explicitly skip entries whose ``is_dir(follow_symlinks=False)`` is
    False. A symlink to a directory would otherwise let a user create
    infinite recursion via ``ln -s ../ infinity``.
    """
    skip_set = frozenset(skip_dirs)
    # Stack of (path, depth_below_root) so we can prune by max_depth.
    # depth_below_root == 0 means we are at the root itself.
    stack: list[tuple[Path, int]] = [(root, 0)]
    while stack:
        current, depth = stack.pop()
        # Check the current directory itself for a .git entry, but only
        # if depth > 0 — root is a scan target, not a repo we'd report.
        if depth > 0 and _is_git_repo(current):
            yield current
            # Don't descend into a detected repo.
            continue

        if depth >= max_depth:
            continue

        try:
            entries = list(os.scandir(current))
        except (PermissionError, FileNotFoundError, NotADirectoryError, OSError):
            continue

        for entry in entries:
            if entry.name in skip_set:
                continue
            try:
                is_dir = entry.is_dir(follow_symlinks=False)
            except OSError:
                continue
            if not is_dir:
                continue
            stack.append((Path(entry.path), depth + 1))


def scan_roots(
    roots: Sequence[Path],
    *,
    max_depth: int = DEFAULT_MAX_DEPTH,
    skip_dirs: Iterable[str] = DEFAULT_SKIP_DIRS,
    warn_fn: Optional[Any] = None,
) -> list[Repo]:
    """Scan ``roots`` and return all detected repos, sorted by ``name``.

    ``warn_fn(message: str)`` is invoked once per missing/inaccessible
    root so the CLI can route the warnings to stderr. Defaults to a
    no-op so library callers (and tests) do not need to wire one up.

    Each :class:`Repo` returned has ``local`` populated; ``remote`` is
    always ``None`` on this path (the GH API is never called for a
    local scan). ``owner``/``full_name`` are best-effort from the
    ``remote.origin.url``.
    """
    if warn_fn is None:
        def warn_fn(_message: str) -> None:  # pragma: no cover - no-op
            return

    repos: list[Repo] = []
    seen_paths: set[Path] = set()

    for root in roots:
        if not root.exists():
            warn_fn(f"pocketshell: scan root does not exist: {root}")
            continue
        if not root.is_dir():
            warn_fn(f"pocketshell: scan root is not a directory: {root}")
            continue

        for repo_path in _walk_for_repos(
            root, max_depth=max_depth, skip_dirs=skip_dirs
        ):
            resolved = repo_path.resolve()
            if resolved in seen_paths:
                continue
            seen_paths.add(resolved)
            remote_url, head = _read_git_metadata(repo_path)
            parsed = parse_github_remote(remote_url)
            if parsed is not None:
                owner, gh_name = parsed
                full_name = f"{owner}/{gh_name}"
                # Keep ``name`` as the directory basename (locally-renamed
                # forks stay identifiable). ``full_name`` reflects the
                # canonical GitHub identity.
            else:
                owner = None
                full_name = None
            repos.append(
                Repo(
                    name=repo_path.name,
                    owner=owner,
                    full_name=full_name,
                    local=LocalInfo(path=str(repo_path), head=head),
                    remote=None,
                )
            )

    # Stable sort by name then local path so the daemon's cache key
    # collapses to a single entry regardless of root order.
    repos.sort(
        key=lambda r: (r.name.lower(), r.local.path if r.local else "")
    )
    return repos


# ---------------------------------------------------------------------------
# Remote scan (gh api user/repos --paginate --slurp)
# ---------------------------------------------------------------------------


def _resolve_gh_binary() -> Optional[str]:
    """Locate the ``gh`` CLI on PATH, or return ``None`` if absent.

    Pulled out as a function so the unit suite can monkeypatch it.
    """
    return shutil.which("gh")


def _gh_missing_message() -> str:
    """Friendly install hint shown when ``gh`` is not on PATH."""
    return (
        "pocketshell: `gh` is not installed on this host. "
        "Install it (`apt install gh` on Debian/Ubuntu, "
        "`brew install gh` on macOS) and authenticate with "
        "`gh auth login -s repo:read` before re-running."
    )


class GhMissingError(RuntimeError):
    """Raised when ``gh`` cannot be located on PATH."""


class GhCommandError(RuntimeError):
    """Raised when ``gh`` exits non-zero. Carries returncode + stderr."""

    def __init__(self, returncode: int, stderr: str) -> None:
        super().__init__(f"gh exited {returncode}: {stderr.strip()}")
        self.returncode = returncode
        self.stderr = stderr


def fetch_remote_repos(
    *,
    limit: Optional[int] = None,
    per_page: int = GH_API_PER_PAGE,
    gh_binary: Optional[str] = None,
) -> list[Repo]:
    """Call ``gh api user/repos --paginate --slurp`` and return parsed repos.

    Sorted by ``updated_at`` descending so the picker can show
    most-recently-touched repositories first. Each :class:`Repo` has
    ``remote`` populated; ``local`` is ``None``.

    Parameters
    ----------
    limit:
        Cap the number of pages fetched. Passes ``per_page=limit``
        along when ``limit < per_page`` so we don't pull a 100-row
        page for a single-row request. When ``None`` (default) the
        full account is walked.
    per_page:
        GitHub-side page size. Capped at 100 (GitHub's API ceiling).
    gh_binary:
        Override the ``gh`` binary path. Injection point for the
        daemon-side handler that wants to surface a custom binary.

    Raises
    ------
    GhMissingError
        ``gh`` not on PATH.
    GhCommandError
        ``gh`` exited non-zero (auth missing, rate-limit, etc.). The
        stderr is preserved so the CLI can surface it to the user.
    """
    binary = gh_binary or _resolve_gh_binary()
    if binary is None:
        raise GhMissingError(_gh_missing_message())

    effective_per_page = min(per_page, GH_API_PER_PAGE)
    if limit is not None and limit > 0:
        # Don't request more rows than the user asked for; saves one
        # page round-trip when ``--limit 5`` is set.
        effective_per_page = min(effective_per_page, limit)

    # Keep PR-A's remote list owner-only. GitHub's default for
    # /user/repos also includes collaborator/org-member repos, which
    # makes the picker noisy and was explicitly deferred to a future
    # --include-orgs style affordance in the #205 spike.
    args = [
        binary,
        "api",
        f"user/repos?per_page={effective_per_page}&affiliation=owner&sort=updated",
        "--paginate",
        "--slurp",
    ]
    completed = subprocess.run(
        args,
        check=False,
        capture_output=True,
        text=True,
    )
    if completed.returncode != 0:
        raise GhCommandError(completed.returncode, completed.stderr)

    payload = _parse_gh_api_output(completed.stdout)
    repos = [_repo_from_gh_entry(entry) for entry in payload]
    # Sort by updated_at descending. ``None`` sorts last so a record
    # missing the field doesn't crowd the top.
    repos.sort(
        key=lambda r: (r.remote.updated_at if r.remote else "") or "",
        reverse=True,
    )
    if limit is not None and limit > 0:
        repos = repos[:limit]
    return repos


def _parse_gh_api_output(raw: str) -> list[dict[str, Any]]:
    """Parse the stdout of ``gh api ... --paginate --slurp``.

    ``gh --paginate`` emits each page separately. ``--slurp`` wraps
    those page payloads in one outer array, so list endpoints become
    ``[[repo, ...], [repo, ...]]``. A flat ``[repo, ...]`` is accepted
    defensively for direct parser callers and older stubs.
    """
    text = raw.strip()
    if not text:
        return []
    data = json.loads(text)
    if not isinstance(data, list):
        # Defensive: GH could in theory return an object envelope
        # (e.g. on an error already surfaced via stderr); treat as
        # empty rather than crashing the scan.
        return []

    entries: list[dict[str, Any]] = []
    for item in data:
        if isinstance(item, dict):
            entries.append(item)
        elif isinstance(item, list):
            entries.extend(entry for entry in item if isinstance(entry, dict))
    return entries


def _repo_from_gh_entry(entry: dict[str, Any]) -> Repo:
    """Translate one ``gh api user/repos`` entry into a unified :class:`Repo`."""
    owner_obj = entry.get("owner")
    owner = (
        owner_obj.get("login")
        if isinstance(owner_obj, dict) and isinstance(owner_obj.get("login"), str)
        else None
    )
    name = entry.get("name") or ""
    full_name = entry.get("full_name") if isinstance(entry.get("full_name"), str) else None
    return Repo(
        name=str(name),
        owner=owner,
        full_name=full_name,
        local=None,
        remote=RemoteInfo(
            default_branch=_str_or_none(entry.get("default_branch")),
            html_url=_str_or_none(entry.get("html_url")),
            ssh_url=_str_or_none(entry.get("ssh_url")),
            updated_at=_str_or_none(entry.get("updated_at")),
        ),
    )


def _str_or_none(value: Any) -> Optional[str]:
    """Return ``value`` as a string when it is one, else ``None``."""
    return value if isinstance(value, str) else None


# ---------------------------------------------------------------------------
# Output formatting
# ---------------------------------------------------------------------------


def _format_human_local(repos: Sequence[Repo]) -> str:
    """Render local-scan ``repos`` as an aligned three-column table.

    Columns: ``name``, ``local.path``, ``full_name`` (``-`` when the
    remote URL was not parseable). The Android side does NOT parse
    the human output — the JSON path is the contract — so we
    optimise purely for terminal readability.

    Empty input renders to an empty string (no header) so piping the
    output through ``wc -l`` reports 0 for the no-repos case.
    """
    if not repos:
        return ""
    name_w = max(len(r.name) for r in repos)
    path_w = max(len(r.local.path) if r.local else 0 for r in repos)
    lines: list[str] = []
    for repo in repos:
        path = repo.local.path if repo.local else "-"
        full = repo.full_name or "-"
        lines.append(f"{repo.name:<{name_w}}  {path:<{path_w}}  {full}")
    return "\n".join(lines) + "\n"


def _format_human_remote(repos: Sequence[Repo]) -> str:
    """Render remote-scan ``repos`` as an aligned three-column table.

    Columns: ``full_name``, ``default_branch`` (``-`` if absent),
    ``updated_at`` (``-`` if absent). Same empty-output policy as the
    local renderer.
    """
    if not repos:
        return ""
    full_w = max(len(r.full_name or r.name) for r in repos)
    branch_w = max(
        len((r.remote.default_branch if r.remote else None) or "-") for r in repos
    )
    lines: list[str] = []
    for repo in repos:
        full = repo.full_name or repo.name
        branch = (repo.remote.default_branch if repo.remote else None) or "-"
        updated = (repo.remote.updated_at if repo.remote else None) or "-"
        lines.append(f"{full:<{full_w}}  {branch:<{branch_w}}  {updated}")
    return "\n".join(lines) + "\n"


def _to_jsonable(repos: Sequence[Repo]) -> list[dict[str, Any]]:
    """Convert ``repos`` to a list of plain dicts (JSON-serialisable)."""
    return [_repo_to_dict(r) for r in repos]


def _repo_from_jsonable(entry: dict[str, Any]) -> Repo:
    """Rehydrate one JSON dict (from daemon or in-process) into a :class:`Repo`."""
    local_obj = entry.get("local")
    local = None
    if isinstance(local_obj, dict):
        local = LocalInfo(
            path=str(local_obj.get("path", "")),
            head=_str_or_none(local_obj.get("head")),
        )
    remote_obj = entry.get("remote")
    remote = None
    if isinstance(remote_obj, dict):
        remote = RemoteInfo(
            default_branch=_str_or_none(remote_obj.get("default_branch")),
            html_url=_str_or_none(remote_obj.get("html_url")),
            ssh_url=_str_or_none(remote_obj.get("ssh_url")),
            updated_at=_str_or_none(remote_obj.get("updated_at")),
        )
    return Repo(
        name=str(entry.get("name", "")),
        owner=_str_or_none(entry.get("owner")),
        full_name=_str_or_none(entry.get("full_name")),
        local=local,
        remote=remote,
    )


# ---------------------------------------------------------------------------
# Daemon probes (client-side)
# ---------------------------------------------------------------------------


def _try_daemon_call(
    method: str,
    params: dict[str, Any],
    *,
    timeout: float = 10.0,
) -> Optional[list[dict[str, Any]]]:
    """Probe the daemon and dispatch ``method``; ``None`` on miss.

    Returns the JSON-RPC ``result`` (a list-of-dicts payload) on
    success, or ``None`` when the daemon is unreachable and the caller
    should fall through to the in-process path. Mirrors
    ``pocketshell.usage._try_daemon_usage_fetch`` so fallthrough
    semantics stay uniform across subcommands.
    """
    from pocketshell import daemon as _daemon

    socket_path = _daemon.resolve_socket_path()
    if not socket_path.exists():
        return None

    try:
        result = _daemon.call(
            method,
            params=params,
            socket_path=socket_path,
            timeout=timeout,
        )
    except (_daemon.DaemonClientError, RuntimeError, OSError):
        return None
    if not isinstance(result, list):
        return None
    return result


def _try_daemon_list_local(
    *,
    roots: Sequence[Path],
    max_depth: int,
    no_cache: bool,
) -> Optional[list[dict[str, Any]]]:
    """Daemon probe for ``repos.list_local``. See :func:`_try_daemon_call`."""
    params: dict[str, Any] = {
        "roots": [str(p) for p in roots],
        "max_depth": max_depth,
    }
    if no_cache:
        params["no_cache"] = True
    return _try_daemon_call("repos.list_local", params)


def _try_daemon_list_remote(
    *,
    limit: Optional[int],
    no_cache: bool,
) -> Optional[list[dict[str, Any]]]:
    """Daemon probe for ``repos.list_remote``. See :func:`_try_daemon_call`."""
    params: dict[str, Any] = {}
    if limit is not None:
        params["limit"] = limit
    if no_cache:
        params["no_cache"] = True
    return _try_daemon_call("repos.list_remote", params)


# ---------------------------------------------------------------------------
# Click surface
# ---------------------------------------------------------------------------


@click.group(
    name="repos",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Discover and operate on git repositories.\n\n"
        "``list --local`` enumerates cloned repos under the configured "
        "scan roots (default ``~/git``). ``list --remote`` delegates to "
        "``gh api user/repos --paginate --slurp`` to enumerate the authenticated "
        "user's GitHub repositories. The JSON shape is unified across "
        "both modes; see ``pocketshell.repos`` module docstring."
    ),
)
def repos_group() -> None:
    """Top-level group registered onto the root `pocketshell` CLI."""


@repos_group.command(
    "list",
    context_settings={"help_option_names": ["-h", "--help"]},
)
@click.option(
    "--local",
    "local_only",
    is_flag=True,
    help=(
        "Scan the local filesystem for cloned git repos under the "
        "configured roots. Default behaviour when neither ``--local`` "
        "nor ``--remote`` is passed."
    ),
)
@click.option(
    "--remote",
    "remote_only",
    is_flag=True,
    help=(
        "Enumerate the authenticated user's GitHub repositories via "
        "``gh api user/repos --paginate --slurp``. Requires `gh` on PATH and a "
        "successful prior `gh auth login -s repo:read`."
    ),
)
@click.option(
    "--json",
    "json_output",
    is_flag=True,
    help="Emit a JSON array (one object per repo) instead of a human table.",
)
@click.option(
    "--root",
    "roots",
    multiple=True,
    type=str,
    help=(
        "Scan root directory (may be repeated). When passed, replaces "
        "(does NOT augment) the default ``~/git`` and the "
        "``POCKETSHELL_REPOS_ROOTS`` env var. Only meaningful with "
        "``--local``."
    ),
)
@click.option(
    "--max-depth",
    type=int,
    default=DEFAULT_MAX_DEPTH,
    show_default=True,
    help=(
        "Maximum directory depth (relative to each scan root) to descend "
        "while looking for a ``.git`` entry. Only meaningful with ``--local``."
    ),
)
@click.option(
    "--limit",
    type=int,
    default=None,
    help=(
        "Cap the number of remote repositories returned. Only "
        "meaningful with ``--remote``."
    ),
)
@click.option(
    "--no-daemon",
    "no_daemon",
    is_flag=True,
    help=(
        "Skip the IPC daemon and run the in-process scan even if a "
        "daemon is available. Useful for debugging."
    ),
)
@click.option(
    "--no-cache",
    "no_cache",
    is_flag=True,
    help=(
        "Bypass the daemon's per-method cache (10 s for ``repos.list_local``, "
        "5 min for ``repos.list_remote``). No effect on the in-process path, "
        "which always runs fresh."
    ),
)
@click.pass_context
def repos_list(
    ctx: click.Context,
    local_only: bool,
    remote_only: bool,
    json_output: bool,
    roots: tuple[str, ...],
    max_depth: int,
    limit: Optional[int],
    no_daemon: bool,
    no_cache: bool,
) -> None:
    """List repositories on this host (``--local``) or on GitHub (``--remote``).

    With neither flag, defaults to ``--local`` and prints a one-line
    hint mentioning ``--remote``. The default preserves the existing
    behaviour while making the new mode discoverable.
    """
    if local_only and remote_only:
        click.echo(
            "pocketshell repos list: --local and --remote are mutually exclusive.",
            err=True,
        )
        ctx.exit(2)

    # No flag => default to local with a discoverability hint.
    if not local_only and not remote_only:
        click.echo(
            "pocketshell repos list: defaulting to --local. "
            "Pass --remote to enumerate GitHub repositories instead.",
            err=True,
        )
        local_only = True

    if remote_only:
        _run_remote(
            ctx,
            json_output=json_output,
            limit=limit,
            no_daemon=no_daemon,
            no_cache=no_cache,
        )
        return

    # local path
    if max_depth < 0:
        click.echo(
            f"pocketshell repos list: --max-depth must be >= 0 (got {max_depth})",
            err=True,
        )
        ctx.exit(2)

    scan_root_paths = resolve_scan_roots(roots)

    # Daemon path is JSON-only; the human-readable rendering happens
    # client-side off the JSON payload so the daemon does not need to
    # learn two output formats.
    payload: Optional[list[dict[str, Any]]] = None
    if not no_daemon:
        payload = _try_daemon_list_local(
            roots=scan_root_paths,
            max_depth=max_depth,
            no_cache=no_cache,
        )

    if payload is None:
        warnings: list[str] = []

        def _capture_warning(message: str) -> None:
            warnings.append(message)

        repos = scan_roots(
            scan_root_paths,
            max_depth=max_depth,
            warn_fn=_capture_warning,
        )
        for message in warnings:
            click.echo(message, err=True)
        payload = _to_jsonable(repos)

    if json_output:
        click.echo(json.dumps(payload, indent=2, sort_keys=True))
        return

    # Human-readable path. Re-hydrate from the JSON dicts so the
    # daemon and in-process paths share one formatter.
    rendered = _format_human_local([_repo_from_jsonable(entry) for entry in payload])
    if rendered:
        sys.stdout.write(rendered)


def _run_remote(
    ctx: click.Context,
    *,
    json_output: bool,
    limit: Optional[int],
    no_daemon: bool,
    no_cache: bool,
) -> None:
    """Body of ``repos list --remote``; pulled out for readability."""
    payload: Optional[list[dict[str, Any]]] = None
    if not no_daemon:
        payload = _try_daemon_list_remote(limit=limit, no_cache=no_cache)

    if payload is None:
        try:
            repos = fetch_remote_repos(limit=limit)
        except GhMissingError as exc:
            click.echo(str(exc), err=True)
            ctx.exit(127)
        except GhCommandError as exc:
            if exc.stderr:
                # Preserve trailing newline behaviour of subprocess capture.
                sys.stderr.write(exc.stderr)
                if not exc.stderr.endswith("\n"):
                    sys.stderr.write("\n")
            ctx.exit(exc.returncode)
        payload = _to_jsonable(repos)

    if json_output:
        click.echo(json.dumps(payload, indent=2, sort_keys=True))
        return

    rendered = _format_human_remote([_repo_from_jsonable(entry) for entry in payload])
    if rendered:
        sys.stdout.write(rendered)


# ---------------------------------------------------------------------------
# Daemon handler entrypoints
# ---------------------------------------------------------------------------


def daemon_handler_local(params: dict[str, Any]) -> list[dict[str, Any]]:
    """JSON-RPC handler for ``repos.list_local``.

    Used by :mod:`pocketshell.daemon` so the daemon module does not
    have to reach into private helpers. Accepts ``roots`` (list of
    str), ``max_depth`` (int). ``no_cache`` is consumed by the daemon
    cache layer, not by this handler.
    """
    raw_roots = params.get("roots")
    if raw_roots is None:
        root_paths = resolve_scan_roots()
    elif isinstance(raw_roots, list) and all(isinstance(item, str) for item in raw_roots):
        root_paths = [Path(os.path.expanduser(item)) for item in raw_roots]
    else:
        # JSON-RPC layer expects an exception for invalid params, but
        # we'd rather degrade than raise — the daemon module's wrapper
        # translates a thrown ``_RpcError`` for us if desired. For
        # robustness, treat as default roots.
        root_paths = resolve_scan_roots()

    max_depth_raw = params.get("max_depth")
    if isinstance(max_depth_raw, int) and max_depth_raw >= 0:
        max_depth = max_depth_raw
    else:
        max_depth = DEFAULT_MAX_DEPTH

    repos = scan_roots(root_paths, max_depth=max_depth)
    return _to_jsonable(repos)


def daemon_handler_remote(params: dict[str, Any]) -> list[dict[str, Any]]:
    """JSON-RPC handler for ``repos.list_remote``.

    Accepts ``limit`` (int, optional). ``no_cache`` is consumed by the
    daemon cache layer, not by this handler. Failures propagate as
    ``RuntimeError`` (translated by the daemon wrapper into a JSON-RPC
    internal-error envelope) so the client sees a clear error rather
    than an empty list.
    """
    limit_raw = params.get("limit")
    limit: Optional[int]
    if isinstance(limit_raw, int) and limit_raw > 0:
        limit = limit_raw
    else:
        limit = None
    repos = fetch_remote_repos(limit=limit)
    return _to_jsonable(repos)


# Back-compat re-export so any in-tree caller that imports
# ``daemon_handler`` (the original single-handler name) keeps working
# until the next refactor moves them over. The daemon module itself
# uses the explicit ``daemon_handler_local`` name.
daemon_handler = daemon_handler_local
