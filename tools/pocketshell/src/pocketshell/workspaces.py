"""Durable host-side workspace membership for the Quiet navigation.

Workspace membership is deliberately separate from the session tree and from
the file-viewer workspace. Sessions are live aplexer state, while this list
must survive an empty workspace and be available before the first session is
started. The registry file and lock are shared with :mod:`pocketshell.tree`.
"""

from __future__ import annotations

import json
import os
from typing import Any, Mapping, Optional

import click

from pocketshell.tree import (
    TreePaths,
    _cli_version,
    _read_registry,
    _registry_lock,
    _write_registry,
    resolve_paths,
)

WORKSPACES_KEY = "workspaces"
WORKSPACES_SCHEMA = 1


def _require_host(params: Mapping[str, Any]) -> str:
    host = params.get("host")
    if not isinstance(host, str) or not host.strip():
        raise ValueError("workspaces: `host` must be a non-empty string")
    return host.strip()


def canonical_workspace_path(raw: Any) -> str:
    """Resolve a user path to one stable absolute identity."""

    if not isinstance(raw, str) or not raw.strip():
        raise ValueError("workspaces: `path` must be a non-empty path")
    entered = raw.strip()
    expanded = os.path.expanduser(entered)
    if not os.path.isabs(expanded):
        raise ValueError(
            "workspaces: `path` must be absolute or use the `~/` form"
        )
    return os.path.realpath(expanded)


def _display_path(raw: Any, canonical: str) -> str:
    """Keep a readable spelling alongside the canonical identity."""

    if isinstance(raw, str) and raw.strip():
        return raw.strip()
    home = os.path.realpath(os.path.expanduser("~"))
    try:
        relative = os.path.relpath(canonical, home)
    except ValueError:
        relative = ".."
    if relative == ".":
        return "~"
    if not relative.startswith(".."):
        return "~/" + relative
    return canonical


def _host_entries(doc: Mapping[str, Any], host: str) -> list[dict[str, str]]:
    all_workspaces = doc.get(WORKSPACES_KEY)
    if not isinstance(all_workspaces, Mapping):
        return []
    raw_entries = all_workspaces.get(host)
    if not isinstance(raw_entries, list):
        return []

    by_path: dict[str, dict[str, str]] = {}
    for raw in raw_entries:
        if not isinstance(raw, Mapping):
            continue
        try:
            canonical = canonical_workspace_path(raw.get("path"))
        except ValueError:
            continue
        display = _display_path(raw.get("display_path"), canonical)
        by_path.setdefault(canonical, {"path": canonical, "display_path": display})
    return [by_path[path] for path in sorted(by_path)]


def _result(host: str, entries: list[dict[str, str]], **extra: Any) -> dict[str, Any]:
    return {
        "schema": WORKSPACES_SCHEMA,
        "host": host,
        "workspaces": entries,
        "cli_version": _cli_version(),
        **extra,
    }


def list_workspaces(
    params: Mapping[str, Any], *, paths: Optional[TreePaths] = None
) -> dict[str, Any]:
    """Return registered workspaces, including empty ones."""

    host = _require_host(params)
    paths = paths or resolve_paths()
    with _registry_lock(paths, exclusive=False):
        doc = _read_registry(paths)
    return _result(host, _host_entries(doc, host))


def add_workspace(
    params: Mapping[str, Any], *, paths: Optional[TreePaths] = None
) -> dict[str, Any]:
    """Register one workspace idempotently."""

    host = _require_host(params)
    canonical = canonical_workspace_path(params.get("path"))
    display = _display_path(params.get("display_path") or params.get("path"), canonical)
    paths = paths or resolve_paths()
    with _registry_lock(paths, exclusive=True):
        doc = _read_registry(paths)
        entries = _host_entries(doc, host)
        existing = next((entry for entry in entries if entry["path"] == canonical), None)
        if existing is not None:
            return _result(host, entries, workspace=existing, created=False)
        entry = {"path": canonical, "display_path": display}
        entries.append(entry)
        entries.sort(key=lambda item: item["path"])
        all_workspaces = doc.setdefault(WORKSPACES_KEY, {})
        if not isinstance(all_workspaces, dict):
            all_workspaces = {}
            doc[WORKSPACES_KEY] = all_workspaces
        all_workspaces[host] = entries
        _write_registry(paths, doc)
    return _result(host, entries, workspace=entry, created=True)


def remove_workspace(
    params: Mapping[str, Any], *, paths: Optional[TreePaths] = None
) -> dict[str, Any]:
    """Remove one workspace idempotently."""

    host = _require_host(params)
    canonical = canonical_workspace_path(params.get("path"))
    paths = paths or resolve_paths()
    with _registry_lock(paths, exclusive=True):
        doc = _read_registry(paths)
        entries = _host_entries(doc, host)
        kept = [entry for entry in entries if entry["path"] != canonical]
        removed = len(kept) != len(entries)
        if removed:
            all_workspaces = doc.setdefault(WORKSPACES_KEY, {})
            if not isinstance(all_workspaces, dict):
                all_workspaces = {}
                doc[WORKSPACES_KEY] = all_workspaces
            all_workspaces[host] = kept
            _write_registry(paths, doc)
    return _result(host, kept, path=canonical, removed=removed)


@click.group(
    name="workspaces",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Manage durable workspace membership for one host. Workspace identity "
        "is a canonical absolute path; list includes workspaces with no live "
        "session. Results are JSON for the Android host contract."
    ),
)
def workspaces_group() -> None:
    """Top-level workspaces command group."""


def _emit(result: Mapping[str, Any]) -> None:
    click.echo(json.dumps(result, sort_keys=True))


def _emit_error(exc: ValueError) -> None:
    click.echo(
        json.dumps(
            {
                "schema": WORKSPACES_SCHEMA,
                "error": {"code": "invalid_request", "message": str(exc)},
            },
            sort_keys=True,
        ),
        err=True,
    )
    raise click.exceptions.Exit(2)


@workspaces_group.command(name="list")
@click.option("--host", required=True, help="Stable host identity for the registry partition.")
@click.option("--json", "as_json", is_flag=True, help="Emit the machine-readable contract.")
def list_command(host: str, as_json: bool) -> None:
    del as_json
    try:
        _emit(list_workspaces({"host": host}))
    except ValueError as exc:
        _emit_error(exc)


@workspaces_group.command(name="add")
@click.argument("path")
@click.option("--host", required=True, help="Stable host identity for the registry partition.")
@click.option("--json", "as_json", is_flag=True, help="Emit the machine-readable contract.")
def add_command(path: str, host: str, as_json: bool) -> None:
    del as_json
    try:
        _emit(add_workspace({"host": host, "path": path}))
    except ValueError as exc:
        _emit_error(exc)


@workspaces_group.command(name="remove")
@click.argument("path")
@click.option("--host", required=True, help="Stable host identity for the registry partition.")
@click.option("--json", "as_json", is_flag=True, help="Emit the machine-readable contract.")
def remove_command(path: str, host: str, as_json: bool) -> None:
    del as_json
    try:
        _emit(remove_workspace({"host": host, "path": path}))
    except ValueError as exc:
        _emit_error(exc)
