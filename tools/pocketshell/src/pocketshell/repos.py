"""`pocketshell repos` subcommand group.

First-PR slice carved out of GitHub-aware project navigation (issue #220,
parent #205). Today the only subcommand is ``list --local``: scan one or
more roots on disk, identify cloned git repositories, and emit either a
human-readable table or a stable JSON array. The JSON shape is the
contract the Android client (follow-up PR) will parse.

Design choices
--------------

- **Roots**: default ``~/git`` only. Override via ``--root <PATH>``
  (repeatable) or the ``POCKETSHELL_REPOS_ROOTS`` env var (colon-separated
  like ``PATH``). ``--root`` wins over env, env wins over default. When
  any explicit root is passed the default is REPLACED, not augmented —
  matching how ``PATH`` works. The brief's parent issue mentions
  ``~/code`` / ``~/projects`` as additional defaults the spike suggested,
  but only ``~/git`` is locked here; the user adds others via env or
  ``--root``.
- **Repo detection**: directory contains a ``.git`` entry (file or
  directory — git worktrees and submodules use a ``.git`` file pointing
  at the real git dir). We deliberately do NOT recurse into the
  ``.git/`` itself.
- **Name rule**: ``Path(repo_path).name`` — the directory basename. This
  keeps identity stable for forks and locally-renamed clones. Deriving
  from the remote URL would collide on multi-owner forks of the same
  repository.
- **Remote / HEAD**: read via ``git config --get remote.origin.url`` and
  ``git rev-parse --abbrev-ref HEAD``. Best-effort: any subprocess
  failure yields ``null`` in JSON rather than aborting the scan.
- **Skip-patterns**: ``.git``, ``node_modules``, ``.venv``, ``venv``,
  ``dist``, ``build``, ``target``. We do not recurse into any of them.
  Symlinks are also not followed (``os.scandir`` defaults).
- **Depth limit**: max 4 directory levels deep from each root, override
  via ``--max-depth``. Justification: shallow defaults match how dev
  boxes are typically organised (``~/git/<repo>`` and
  ``~/git/<group>/<repo>``); deeper scans are slow and pollute output.
- **Missing root**: a warning to stderr (does not abort), the remaining
  roots are still scanned, exit 0.
- **Empty result**: exit 0 with ``[]`` for JSON, or no human output. We
  do not print a "no repos found" message — that would surprise scripts
  that pipe the human output through ``wc -l``.

Daemon integration
------------------

The daemon (``pocketshell.daemon``) gets a ``repos.list_local`` RPC
method with a 10 s TTL cache. The CLI probes the daemon first and falls
through silently to the in-process scan path when the daemon is
unreachable, same pattern as ``usage``. ``--no-daemon`` forces the
subprocess path; ``--no-cache`` is forwarded to the daemon (no-op on
the in-process path because every call is fresh).
"""

from __future__ import annotations

import json
import os
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


@dataclass(frozen=True)
class Repo:
    """A single cloned repository, ready for JSON serialisation.

    Frozen + dataclass so the same struct can be reused by the daemon
    handler (which returns plain dicts) and the in-process scan path
    (which builds these directly).
    """

    name: str
    path: str
    remote: Optional[str]
    head: Optional[str]


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
# Repo scan
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
            remote, head = _read_git_metadata(repo_path)
            repos.append(
                Repo(
                    name=repo_path.name,
                    path=str(repo_path),
                    remote=remote,
                    head=head,
                )
            )

    # Stable sort by name then path so the daemon's cache key collapses
    # to a single entry regardless of root order.
    repos.sort(key=lambda r: (r.name.lower(), r.path))
    return repos


# ---------------------------------------------------------------------------
# Output formatting
# ---------------------------------------------------------------------------


def _format_human(repos: Sequence[Repo]) -> str:
    """Render ``repos`` as an aligned three-column human-readable table.

    Columns: ``name``, ``path``, ``remote`` (``-`` for missing remote).
    The Android side does NOT parse the human output — the JSON path is
    the contract — so we optimise purely for terminal readability.

    Empty input renders to an empty string (no header) so piping the
    output through ``wc -l`` reports 0 for the no-repos case.
    """
    if not repos:
        return ""
    name_w = max(len(r.name) for r in repos)
    path_w = max(len(r.path) for r in repos)
    lines: list[str] = []
    for repo in repos:
        remote = repo.remote or "-"
        lines.append(f"{repo.name:<{name_w}}  {repo.path:<{path_w}}  {remote}")
    return "\n".join(lines) + "\n"


def _to_jsonable(repos: Sequence[Repo]) -> list[dict[str, Any]]:
    """Convert ``repos`` to a list of plain dicts (JSON-serialisable)."""
    return [asdict(r) for r in repos]


# ---------------------------------------------------------------------------
# Daemon probe
# ---------------------------------------------------------------------------


def _try_daemon_list_local(
    *,
    roots: Sequence[Path],
    max_depth: int,
    no_cache: bool,
) -> Optional[list[dict[str, Any]]]:
    """Probe the daemon and dispatch ``repos.list_local``; None on miss.

    Returns the JSON-RPC ``result`` (the list-of-dicts payload) on
    success, or ``None`` when the daemon is unreachable and the caller
    should fall through to the in-process scan path. Mirrors
    ``pocketshell.usage._try_daemon_usage_fetch`` exactly so the
    fallthrough semantics stay uniform across subcommands.
    """
    from pocketshell import daemon as _daemon

    socket_path = _daemon.resolve_socket_path()
    if not socket_path.exists():
        return None

    params: dict[str, Any] = {
        "roots": [str(p) for p in roots],
        "max_depth": max_depth,
    }
    if no_cache:
        params["no_cache"] = True

    try:
        result = _daemon.call(
            "repos.list_local",
            params=params,
            socket_path=socket_path,
            timeout=10.0,
        )
    except (_daemon.DaemonClientError, RuntimeError, OSError):
        return None
    if not isinstance(result, list):
        return None
    return result


# ---------------------------------------------------------------------------
# Click surface
# ---------------------------------------------------------------------------


@click.group(
    name="repos",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Discover and operate on git repositories cloned on this host.\n\n"
        "First subcommand: ``list --local`` enumerates cloned repos under "
        "the configured scan roots (default ``~/git``). The JSON output "
        "shape is the contract the PocketShell Android client parses; "
        "see ``pocketshell.repos`` module docstring for details."
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
        "configured roots. Currently the only mode this subcommand "
        "supports — future work will add a ``--remote`` mode."
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
        "``POCKETSHELL_REPOS_ROOTS`` env var."
    ),
)
@click.option(
    "--max-depth",
    type=int,
    default=DEFAULT_MAX_DEPTH,
    show_default=True,
    help=(
        "Maximum directory depth (relative to each scan root) to descend "
        "while looking for a ``.git`` entry."
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
        "Bypass the daemon's per-method cache (10 s for ``repos.list_local``). "
        "No effect on the in-process path, which always scans fresh."
    ),
)
@click.pass_context
def repos_list(
    ctx: click.Context,
    local_only: bool,
    json_output: bool,
    roots: tuple[str, ...],
    max_depth: int,
    no_daemon: bool,
    no_cache: bool,
) -> None:
    """List repositories on this host.

    ``--local`` is required in this PR: the remote-scan path is the
    follow-up (#205 full slice). Output is either a human-readable
    three-column table (default) or a JSON array (``--json``).
    """
    if not local_only:
        click.echo(
            "pocketshell repos list: --local is required in this release. "
            "A future PR will add remote-scan support.",
            err=True,
        )
        ctx.exit(2)

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
    rendered = _format_human(
        [
            Repo(
                name=str(entry.get("name", "")),
                path=str(entry.get("path", "")),
                remote=entry.get("remote") if isinstance(entry.get("remote"), str) else None,
                head=entry.get("head") if isinstance(entry.get("head"), str) else None,
            )
            for entry in payload
        ]
    )
    if rendered:
        sys.stdout.write(rendered)


# ---------------------------------------------------------------------------
# Daemon handler entrypoint
# ---------------------------------------------------------------------------


def daemon_handler(params: dict[str, Any]) -> list[dict[str, Any]]:
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
