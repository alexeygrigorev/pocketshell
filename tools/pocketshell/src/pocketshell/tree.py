"""Durable per-host project-tree registry (epic #821 workstream C, issue #837).

The PocketShell Android client maintains an in-memory project tree per host
(`HostTreeModel`, #679): the session ordering, folder expand/collapse memory,
and a one-shot foreign-agent guess cache. That tree is volatile — a process
kill loses it, so a cold start shows a brief Loading flash and can shuffle the
session order until the first authoritative probe re-seeds it.

This module is the host-side durability store for that small *presentation*
state. It is a host-keyed JSON registry the `pocketshell` daemon owns, exposed
via three JSON-RPC methods:

- ``tree.get {host}`` -> ``{nodes: [...], version}`` — the persisted node list
  (order, folder_path, collapsed, optional cached foreign-guess kind). An empty
  result is valid (no registry yet → the client seeds fresh). Cached with a
  short TTL (~5 s) like ``sessions.list``.
- ``tree.upsert {host, nodes}`` -> ``{status, version}`` — atomically persists
  the node list. A mutation: it carries NO TTL and invalidates the ``tree.get``
  cache for that host (see :data:`pocketshell.daemon.METHOD_CACHE_INVALIDATIONS`).
- ``tree.reconcile {host}`` -> ``{alive, gone, added}`` — diffs the persisted
  registry against live ``tmuxctl list`` and returns DELTAS ONLY (never a full
  reload), pruning the gone sessions from the registry with an optimistic-grace
  guard (mirrors ``HostTreeModel.reconcile`` + ``OPTIMISTIC_GRACE_MS``).

What this store deliberately does NOT hold
-------------------------------------------

The per-session agent **kind** (recorded AND confirmed-foreign) lives ONLY in
the tmux ``@ps_agent_kind`` user-option (``ManualKindWriter`` writes it; the
client reads it back as the sole kind authority). This registry stores no kind
copy — a second kind writer would be the exact "third cache / two writers"
smell the design forbids. The optional ``foreign_kind`` field on a node is the
cheap one-shot foreign-GUESS cache (a hint the client re-derives if absent), not
the confirmed kind.

Storage
-------

``${XDG_STATE_HOME:-~/.local/state}/pocketshell/tree/registry.json`` — a single
JSON document keyed by host alias. Persisted with the atomic temp-file +
``os.replace`` private-write pattern (mode 0600, dir 0700) copied from
:func:`pocketshell.usage_capture._write_private`, so a concurrent reader (the
app's SSH fetch racing a mutation) never sees a half-written file. JSON, not
SQLite: the dataset is tiny (a few hosts × tens of sessions) and read-whole /
write-whole semantics match the per-open ``tree.get`` + per-mutation
``tree.upsert`` access pattern (#837 non-goal: SQLite / versioned history).
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Optional

import click

# File permissions for the registry file. ``0600`` keeps the per-host tree
# state readable only by the owning user, matching usage_capture.
NEW_FILE_MODE = 0o600

REGISTRY_FILENAME = "registry.json"

# Optimistic-grace guard, mirrored from ``HostTreeModel.OPTIMISTIC_GRACE_MS``
# (30 s, expressed in seconds here). A node the registry holds but live
# ``tmuxctl list`` does not yet report is NOT pruned while it is still within
# this window of its ``optimistic_since`` stamp, so a session the client just
# created (and upserted optimistically) survives the immediately-following
# reconcile that has not yet observed it.
OPTIMISTIC_GRACE_SECS = 30.0


@dataclass(frozen=True)
class TreePaths:
    """Resolved filesystem location for the tree registry.

    The path is a field so the unit suite can point it at a tmp dir; nothing in
    this module reads ``~`` directly — everything flows through
    :func:`resolve_paths`.
    """

    tree_dir: Path

    @property
    def registry_file(self) -> Path:
        return self.tree_dir / REGISTRY_FILENAME


def resolve_paths(
    *,
    home: Optional[Path] = None,
    env: Optional[Mapping[str, str]] = None,
) -> TreePaths:
    """Return the :class:`TreePaths` for the current (or given) environment.

    Precedence for the tree state dir:

    1. ``$XDG_STATE_HOME/pocketshell/tree`` when ``$XDG_STATE_HOME`` is set.
    2. ``<home>/.local/state/pocketshell/tree``.

    Mirrors :func:`pocketshell.usage_capture.resolve_paths` so all PocketShell
    durable state lives under one XDG-state root.
    """
    env_map = env if env is not None else os.environ
    base_home = home if home is not None else Path(os.path.expanduser("~"))

    xdg_state = env_map.get("XDG_STATE_HOME")
    if xdg_state:
        state_root = Path(os.path.expanduser(xdg_state))
    else:
        state_root = base_home / ".local" / "state"
    tree_dir = state_root / "pocketshell" / "tree"
    return TreePaths(tree_dir=tree_dir)


def _ensure_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(path, 0o700)
    except PermissionError:
        pass


def _write_private(path: Path, text: str) -> None:
    """Write ``text`` to ``path`` atomically with mode 0600.

    Temp file + ``os.replace`` so a concurrent reader (the app's SSH ``tree.get``
    racing a ``tree.upsert``) never sees a half-written registry. Copied from
    :func:`pocketshell.usage_capture._write_private`.
    """
    _ensure_dir(path.parent)
    tmp = path.with_name(path.name + ".tmp")
    fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_TRUNC, NEW_FILE_MODE)
    try:
        with os.fdopen(fd, "w", encoding="utf-8") as handle:
            handle.write(text)
    except BaseException:
        try:
            os.unlink(tmp)
        except FileNotFoundError:
            pass
        raise
    os.replace(tmp, path)
    try:
        os.chmod(path, NEW_FILE_MODE)
    except FileNotFoundError:
        pass


def _read_registry(paths: TreePaths) -> dict[str, Any]:
    """Read the whole registry document, returning an empty doc on any miss.

    A missing file, an empty file, or an unparseable file all degrade to an
    empty registry — the client treats "no registry yet" as a valid fresh-seed
    state, and a corrupt file must never wedge a cold start (it is rewritten on
    the next upsert).
    """
    try:
        raw = paths.registry_file.read_text(encoding="utf-8")
    except (FileNotFoundError, IsADirectoryError, PermissionError):
        return {"hosts": {}}
    if not raw.strip():
        return {"hosts": {}}
    try:
        doc = json.loads(raw)
    except json.JSONDecodeError:
        return {"hosts": {}}
    if not isinstance(doc, dict):
        return {"hosts": {}}
    hosts = doc.get("hosts")
    if not isinstance(hosts, dict):
        doc["hosts"] = {}
    return doc


def _write_registry(paths: TreePaths, doc: Mapping[str, Any]) -> None:
    _write_private(
        paths.registry_file,
        json.dumps(doc, sort_keys=True) + "\n",
    )


def _normalise_node(raw: Any, fallback_order: int) -> Optional[dict[str, Any]]:
    """Coerce one inbound node into the persisted shape, or ``None`` if invalid.

    A node MUST have a non-empty ``session`` string; everything else is
    optional and defaulted. ``foreign_kind`` is the cheap one-shot foreign-guess
    cache (NOT the confirmed kind — that lives in ``@ps_agent_kind``); it is
    persisted verbatim when present and omitted otherwise. One malformed node
    never sinks the batch — the caller filters ``None`` out.
    """
    if not isinstance(raw, Mapping):
        return None
    session = raw.get("session")
    if not isinstance(session, str) or not session:
        return None
    order_raw = raw.get("order", fallback_order)
    try:
        order = int(order_raw)
    except (TypeError, ValueError):
        order = fallback_order
    folder_path = raw.get("folder_path")
    if not isinstance(folder_path, str):
        folder_path = ""
    collapsed = bool(raw.get("collapsed", False))
    node: dict[str, Any] = {
        "session": session,
        "order": order,
        "folder_path": folder_path,
        "collapsed": collapsed,
    }
    foreign_kind = raw.get("foreign_kind")
    if isinstance(foreign_kind, str) and foreign_kind:
        node["foreign_kind"] = foreign_kind
    # Preserve / stamp the optimistic-grace marker so the next reconcile spares
    # a just-upserted node the live probe has not yet observed.
    optimistic_since = raw.get("optimistic_since")
    if isinstance(optimistic_since, (int, float)):
        node["optimistic_since"] = float(optimistic_since)
    return node


def _host_nodes(doc: Mapping[str, Any], host: str) -> list[dict[str, Any]]:
    hosts = doc.get("hosts")
    if not isinstance(hosts, Mapping):
        return []
    entry = hosts.get(host)
    if not isinstance(entry, Mapping):
        return []
    nodes = entry.get("nodes")
    if not isinstance(nodes, list):
        return []
    out: list[dict[str, Any]] = []
    for index, raw in enumerate(nodes):
        node = _normalise_node(raw, fallback_order=index)
        if node is not None:
            out.append(node)
    return out


def _host_version(doc: Mapping[str, Any], host: str) -> int:
    hosts = doc.get("hosts")
    if not isinstance(hosts, Mapping):
        return 0
    entry = hosts.get(host)
    if not isinstance(entry, Mapping):
        return 0
    version = entry.get("version")
    try:
        return int(version)
    except (TypeError, ValueError):
        return 0


def _require_host(params: Mapping[str, Any]) -> str:
    host = params.get("host")
    if not isinstance(host, str) or not host:
        raise ValueError("tree: `host` must be a non-empty string")
    return host


def _cli_version() -> str:
    """The installed ``pocketshell`` version, for the passive client check.

    Issue #885: the PocketShell client execs ``tree get`` / ``tree reconcile``
    on EVERY host open (warm/direct included), so stamping the server CLI
    version into those envelopes lets the client detect a version mismatch
    PASSIVELY during normal use — no separate slow blocking ``--version`` exec,
    and consistently regardless of how the host was opened. A read failure
    degrades to an empty string (the client treats "unknown" as "no signal").
    """
    try:
        from pocketshell import __version__

        return str(__version__)
    except Exception:  # pragma: no cover - defensive; never wedge the payload
        return ""


# ---------------------------------------------------------------------------
# Live-session enumeration (for reconcile)
# ---------------------------------------------------------------------------


def _resolve_tmuxctl_binary() -> Optional[str]:
    """Locate ``tmuxctl`` on PATH (pulled out so tests can monkeypatch it)."""
    return shutil.which("tmuxctl")


def _live_session_names(env: Optional[Mapping[str, str]] = None) -> Optional[set[str]]:
    """Enumerate the live tmux session names from ``tmuxctl list``.

    Returns the set of session names, or ``None`` when the enumeration could
    not be performed (``tmuxctl`` missing or a non-zero exit) — in which case
    reconcile must NOT prune anything (treating an enumeration failure as "all
    sessions gone" would wipe the held tree on a transient hiccup).

    Uses the SAME ``tmuxctl list`` enumeration ``sessions.list`` proxies. The
    output is the fixed-width ``IDX  SESSION  CREATED`` table; the SESSION name
    is the second whitespace-delimited token on each data row (tmux session
    names cannot contain whitespace, so this is unambiguous). The header row and
    any blank lines are skipped.
    """
    tmuxctl_path = _resolve_tmuxctl_binary()
    if tmuxctl_path is None:
        return None
    try:
        completed = subprocess.run(
            [tmuxctl_path, "list"],
            check=False,
            capture_output=True,
            text=True,
        )
    except OSError:
        return None
    if completed.returncode != 0:
        return None
    return _parse_session_names(completed.stdout)


def _parse_session_names(stdout: str) -> set[str]:
    """Parse session names from the ``tmuxctl list`` fixed-width table."""
    names: set[str] = set()
    for line in stdout.splitlines():
        stripped = line.strip()
        if not stripped:
            continue
        tokens = stripped.split()
        if not tokens:
            continue
        # Skip the header row ("IDX  SESSION  CREATED").
        if tokens[0].upper() == "IDX":
            continue
        # The IDX column is numeric on data rows; the SESSION name is token[1].
        if len(tokens) < 2:
            continue
        if not tokens[0].isdigit():
            # Defensive: a row whose first token is not an index is not a
            # recognisable data row; skip it rather than mis-key a name.
            continue
        names.add(tokens[1])
    return names


# ---------------------------------------------------------------------------
# RPC handlers
# ---------------------------------------------------------------------------


def get_tree(
    params: Mapping[str, Any],
    *,
    paths: Optional[TreePaths] = None,
) -> dict[str, Any]:
    """Handle ``tree.get`` — return the persisted node list for one host.

    An empty result (no registry yet) is valid: the client seeds a fresh tree.
    """
    host = _require_host(params)
    if paths is None:
        paths = resolve_paths()
    doc = _read_registry(paths)
    return {
        "nodes": _host_nodes(doc, host),
        "version": _host_version(doc, host),
        # Issue #885: passive payload-version detection — the client compares
        # this against its expected version on every normal open.
        "cli_version": _cli_version(),
    }


def upsert_tree(
    params: Mapping[str, Any],
    *,
    paths: Optional[TreePaths] = None,
) -> dict[str, Any]:
    """Handle ``tree.upsert`` — atomically persist the node list for one host.

    A mutation: it bumps the host's ``version`` and rewrites the registry with
    the atomic private-write. Accepts either a ``nodes`` list or a single
    ``node`` object (folded into a one-element list). One malformed node is
    skipped; the rest persist. The daemon invalidates the ``tree.get`` cache
    for this host after a successful call.
    """
    host = _require_host(params)
    if paths is None:
        paths = resolve_paths()

    raw_nodes = params.get("nodes")
    if raw_nodes is None:
        single = params.get("node")
        raw_nodes = [single] if single is not None else []
    if not isinstance(raw_nodes, list):
        raise ValueError("tree.upsert: `nodes` must be a list of node objects")

    nodes: list[dict[str, Any]] = []
    for index, raw in enumerate(raw_nodes):
        node = _normalise_node(raw, fallback_order=index)
        if node is not None:
            nodes.append(node)

    doc = _read_registry(paths)
    hosts = doc.setdefault("hosts", {})
    if not isinstance(hosts, dict):
        hosts = {}
        doc["hosts"] = hosts
    version = _host_version(doc, host) + 1
    hosts[host] = {"nodes": nodes, "version": version}
    _write_registry(paths, doc)
    return {"status": "ok", "version": version}


def reconcile_tree(
    params: Mapping[str, Any],
    *,
    paths: Optional[TreePaths] = None,
    live_names: Optional[set[str]] = None,
    now: Optional[float] = None,
) -> dict[str, Any]:
    """Handle ``tree.reconcile`` — return ``{alive, gone, added}`` DELTAS.

    Diffs the persisted registry against live ``tmuxctl list`` by session name:

    - ``alive``  — registry sessions still present in the live enumeration.
    - ``gone``   — registry sessions absent from the live enumeration AND past
      their optimistic grace (these are PRUNED from the registry).
    - ``added``  — live sessions not yet in the registry (the client folds
      these into its tree; they are NOT auto-added to the registry here, the
      client upserts the freshened tree afterwards).

    Deltas only — never a full node reload. When the live enumeration cannot be
    performed (``tmuxctl`` missing / error), NOTHING is pruned: ``gone`` and
    ``added`` are empty and every registry session is reported ``alive`` so a
    transient enumeration hiccup never wipes the held tree.

    ``live_names`` / ``now`` are injectable for deterministic tests; production
    resolves them from the host.
    """
    host = _require_host(params)
    if paths is None:
        paths = resolve_paths()
    if now is None:
        now = time.time()

    doc = _read_registry(paths)
    nodes = _host_nodes(doc, host)
    registry_names = [node["session"] for node in nodes]

    resolved_live = live_names if live_names is not None else _live_session_names()
    if resolved_live is None:
        # Enumeration unavailable — do NOT prune. Report everything alive.
        return {
            "alive": list(registry_names),
            "gone": [],
            "added": [],
            "cli_version": _cli_version(),
        }

    alive: list[str] = []
    gone: list[str] = []
    kept_nodes: list[dict[str, Any]] = []
    for node in nodes:
        name = node["session"]
        if name in resolved_live:
            alive.append(name)
            kept_nodes.append(node)
            continue
        # Absent from the live enumeration — prune UNLESS still within the
        # optimistic grace window (a just-upserted node the probe has not yet
        # observed). Mirrors HostTreeModel.reconcile + OPTIMISTIC_GRACE_MS.
        since = node.get("optimistic_since")
        if isinstance(since, (int, float)) and (now - since) < OPTIMISTIC_GRACE_SECS:
            # Spared this round; keep it but DO NOT list it gone yet, and treat
            # it as still alive for the client's purposes.
            alive.append(name)
            kept_nodes.append(node)
            continue
        gone.append(name)

    added = [name for name in resolved_live if name not in registry_names]

    # Persist the prune (deltas only — we rewrite the registry without the gone
    # nodes). Skip the write when nothing was pruned to avoid needless churn.
    if gone:
        hosts = doc.setdefault("hosts", {})
        if not isinstance(hosts, dict):
            hosts = {}
            doc["hosts"] = hosts
        version = _host_version(doc, host) + 1
        hosts[host] = {"nodes": kept_nodes, "version": version}
        _write_registry(paths, doc)

    return {
        "alive": alive,
        "gone": gone,
        "added": added,
        "cli_version": _cli_version(),
    }


# ---------------------------------------------------------------------------
# Daemon JSON-RPC handler shims
# ---------------------------------------------------------------------------


def daemon_handler_get(params: Mapping[str, Any]) -> dict[str, Any]:
    """JSON-RPC handler for ``tree.get``."""
    return get_tree(params)


def daemon_handler_upsert(params: Mapping[str, Any]) -> dict[str, Any]:
    """JSON-RPC handler for ``tree.upsert``."""
    return upsert_tree(params)


def daemon_handler_reconcile(params: Mapping[str, Any]) -> dict[str, Any]:
    """JSON-RPC handler for ``tree.reconcile``."""
    return reconcile_tree(params)


# ---------------------------------------------------------------------------
# CLI seam (`pocketshell tree get|upsert|reconcile`)
# ---------------------------------------------------------------------------
#
# The PocketShell Android client only ever execs ``pocketshell <subcommand>``
# over its warm SSH session — it never speaks JSON-RPC directly. These commands
# are that seam (mirroring ``pocketshell agents kind`` in ``agents_kind.py``):
# each reads its params as a JSON object on stdin (the RPC request shape),
# dispatches to the daemon when it is up (so a long-lived process owns the file
# and the cache), falls back to the in-process handler when the daemon is absent
# (the same computation), and emits the RPC result envelope as JSON on stdout.


def _read_stdin_params() -> dict[str, Any]:
    """Read the request params as a JSON object from stdin (empty -> ``{}``)."""
    if sys.stdin is None or sys.stdin.isatty():
        return {}
    raw = sys.stdin.read()
    if not raw.strip():
        return {}
    try:
        doc = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise click.ClickException(f"tree: stdin is not valid JSON: {exc}") from exc
    if not isinstance(doc, Mapping):
        raise click.ClickException("tree: stdin JSON must be an object")
    return dict(doc)


def _try_daemon_call(method: str, params: Mapping[str, Any]) -> Optional[Any]:
    """Dispatch ``method`` to the daemon; return ``None`` on miss/error.

    Mirrors :func:`pocketshell.agents_kind._try_daemon_call`.
    """
    from pocketshell import daemon as _daemon

    socket_path = _daemon.resolve_socket_path()
    if not socket_path.exists():
        return None
    try:
        return _daemon.call(
            method,
            params=dict(params),
            socket_path=socket_path,
            timeout=5.0,
        )
    except (_daemon.DaemonClientError, RuntimeError, OSError):
        return None


@click.group(
    name="tree",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Durable per-host project-tree registry (epic #821 slice C).\n\n"
        "`get` / `upsert` / `reconcile` persist + restore the maintained "
        "project tree's ordering, expand/collapse memory, and foreign-guess "
        "cache so the PocketShell client renders the held tree instantly "
        "across an app restart. Params are read as a JSON object on stdin "
        "(the RPC request shape); the result envelope is emitted as JSON on "
        "stdout. NB: the per-session agent KIND is NOT stored here — it lives "
        "in the tmux `@ps_agent_kind` option (one source of truth)."
    ),
)
def tree_group() -> None:
    """Top-level `tree` group registered onto the root `pocketshell` CLI."""


@tree_group.command(
    name="get",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Return the persisted node list for a host. Reads `{\"host\": ...}` on "
        "stdin; emits `{\"nodes\": [...], \"version\": N}`. Empty registry -> "
        "`{\"nodes\": [], \"version\": 0}` (the client seeds fresh)."
    ),
)
def tree_get_command() -> None:
    params = _read_stdin_params()
    envelope = _try_daemon_call("tree.get", params)
    if envelope is None:
        envelope = get_tree(params)
    click.echo(json.dumps(envelope))


@tree_group.command(
    name="upsert",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Persist a host's node list atomically. Reads "
        "`{\"host\": ..., \"nodes\": [...]}` on stdin; emits "
        "`{\"status\": \"ok\", \"version\": N}`."
    ),
)
def tree_upsert_command() -> None:
    params = _read_stdin_params()
    envelope = _try_daemon_call("tree.upsert", params)
    if envelope is None:
        envelope = upsert_tree(params)
    click.echo(json.dumps(envelope))


@tree_group.command(
    name="reconcile",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Diff the registry against live `tmuxctl list` and return deltas. "
        "Reads `{\"host\": ...}` on stdin; emits "
        "`{\"alive\": [...], \"gone\": [...], \"added\": [...]}`. Gone "
        "sessions (past optimistic grace) are pruned from the registry."
    ),
)
def tree_reconcile_command() -> None:
    params = _read_stdin_params()
    envelope = _try_daemon_call("tree.reconcile", params)
    if envelope is None:
        envelope = reconcile_tree(params)
    click.echo(json.dumps(envelope))
