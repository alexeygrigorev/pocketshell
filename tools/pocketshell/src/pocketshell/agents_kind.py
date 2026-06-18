"""`pocketshell agents kind` — CLI seam over the cgroup agent-kind detector.

Epic #821 (workstream A2-infra). The daemon RPC ``agents.kind_for_panes``
(:func:`pocketshell.cgroup_agents.kind_for_panes`,
:func:`pocketshell.daemon._agents_kind_for_panes_handler`) does cgroup-v2 +
``/proc`` agent-kind detection on the host, but it is **daemon-registry-only**:
the PocketShell Android client only ever execs ``pocketshell <subcommand>`` over
its warm SSH session — it never speaks JSON-RPC directly. So the detection has
no way to be reached from the client today.

This module adds the missing seam: ``pocketshell agents kind`` accepts a pane
list (each pane ``{pane_id, pane_pid}``, matching the RPC's input shape),
dispatches to the daemon when it is up (mirroring the
:func:`pocketshell.jobs._try_daemon_jobs_call` CLI→daemon pattern), falls back
to calling :func:`pocketshell.cgroup_agents.kind_for_panes` in-process when the
daemon is absent (the detection is pure cgroupfs/``/proc`` reads — no shell-out,
so the in-process call is the same computation the daemon performs), and emits
the RPC's ``{"results": [{pane_id, agent_kind, scope, evidence_pid?}]}`` as
stable, client-parseable JSON on stdout.

Input forms (pick whichever is convenient — they merge):

- **stdin JSON** — ``{"panes": [{"pane_id": "%1", "pane_pid": 2647034}, ...]}``,
  byte-for-byte the RPC request shape. This is the primary form the client uses
  (it pipes the pane snapshot it already has).
- **``--pane PANE_ID=PANE_PID``** (repeatable) — an ergonomic alternative for a
  one-shot SSH exec / manual debugging.

``none`` / ``unknown`` / empty-pane inputs never error: an empty pane list
yields ``{"results": []}``, a missing/invalid ``pane_pid`` yields
``agent_kind="unknown"`` for that pane, and one bad pane never sinks the batch
(the detector is defensive by design — see ``cgroup_agents``).
"""

from __future__ import annotations

import json
import sys
from typing import Any, Mapping, Optional

import click

from pocketshell.cgroup_agents import (
    DEFAULT_CGROUP_MOUNT,
    DEFAULT_PROC_ROOT,
)


def _parse_stdin_panes() -> list[dict[str, Any]]:
    """Read ``{"panes": [...]}`` from stdin, returning the pane list.

    A non-TTY empty stdin (the common ``--pane``-only or no-input case) yields
    an empty list rather than an error. Malformed JSON is a clear usage error.
    """
    if sys.stdin is None or sys.stdin.isatty():
        return []
    raw = sys.stdin.read()
    if not raw.strip():
        return []
    try:
        doc = json.loads(raw)
    except json.JSONDecodeError as exc:
        raise click.ClickException(
            f"agents kind: stdin is not valid JSON: {exc}"
        ) from exc
    if not isinstance(doc, Mapping):
        raise click.ClickException(
            "agents kind: stdin JSON must be an object with a `panes` list"
        )
    raw_panes = doc.get("panes")
    if raw_panes is None:
        return []
    if not isinstance(raw_panes, list):
        raise click.ClickException(
            "agents kind: `panes` must be a list of objects"
        )
    return [dict(p) for p in raw_panes if isinstance(p, Mapping)]


def _parse_pane_options(pane_specs: tuple[str, ...]) -> list[dict[str, Any]]:
    """Parse ``--pane PANE_ID=PANE_PID`` specs into pane mappings.

    ``PANE_PID`` is left as the raw string; the detector coerces it to ``int``
    and degrades an invalid value to ``agent_kind="unknown"`` rather than
    failing — so a non-numeric pid here is not a CLI error, mirroring the RPC.
    """
    panes: list[dict[str, Any]] = []
    for spec in pane_specs:
        pane_id, sep, pane_pid = spec.partition("=")
        if not sep:
            raise click.ClickException(
                f"agents kind: --pane must be PANE_ID=PANE_PID (got {spec!r})"
            )
        panes.append({"pane_id": pane_id, "pane_pid": pane_pid})
    return panes


def _try_daemon_call(
    panes: list[dict[str, Any]],
    *,
    proc_root: str,
    cgroup_mount: str,
    timeout: float = 5.0,
) -> Optional[dict[str, Any]]:
    """Dispatch ``agents.kind_for_panes`` to the daemon; ``None`` on miss/error.

    Mirrors :func:`pocketshell.jobs._try_daemon_jobs_call`. Only used when the
    detection runs against the real host roots — when the caller overrode
    ``--proc-root`` / ``--cgroup-mount`` (tests, debugging), the in-process path
    is used directly so the override is honoured (the daemon reads the live
    host roots and cannot see a synthetic tree).
    """
    if proc_root != DEFAULT_PROC_ROOT or cgroup_mount != DEFAULT_CGROUP_MOUNT:
        return None

    from pocketshell import daemon as _daemon

    socket_path = _daemon.resolve_socket_path()
    if not socket_path.exists():
        return None
    try:
        result = _daemon.call(
            "agents.kind_for_panes",
            params={"panes": panes},
            socket_path=socket_path,
            timeout=timeout,
        )
    except (_daemon.DaemonClientError, RuntimeError, OSError):
        return None
    if not isinstance(result, dict) or "results" not in result:
        return None
    return result


def _classify_in_process(
    panes: list[dict[str, Any]],
    *,
    proc_root: str,
    cgroup_mount: str,
) -> dict[str, Any]:
    """Call the detector in-process and wrap it in the RPC's result envelope."""
    from pocketshell import cgroup_agents as _cgroup_agents

    results = _cgroup_agents.kind_for_panes(
        panes, proc_root=proc_root, cgroup_mount=cgroup_mount
    )
    return {"results": results}


@click.group(
    name="agents",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Host-side agent-awareness helpers for the PocketShell client.\n\n"
        "`kind` classifies the coding-agent (claude / codex / opencode) "
        "running in each tmux pane's cgroup scope — the CLI seam over the "
        "`agents.kind_for_panes` daemon RPC. See epic #821."
    ),
)
def agents_group() -> None:
    """Top-level `agents` group registered onto the root `pocketshell` CLI."""


@agents_group.command(
    name="kind",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Classify the agent kind in each pane's cgroup scope.\n\n"
        "Reads a pane list as `{\"panes\": [{\"pane_id\", \"pane_pid\"}, ...]}` "
        "JSON on stdin (the RPC request shape), and/or via repeatable "
        "`--pane PANE_ID=PANE_PID`. Emits "
        "`{\"results\": [{\"pane_id\", \"agent_kind\", \"scope\", "
        "\"evidence_pid\"?}]}` as JSON on stdout. `agent_kind` is one of "
        "claude / codex / opencode / none (scope, no agent) / unknown "
        "(pane pid/cgroup unreadable). Empty input -> `{\"results\": []}`."
    ),
)
@click.option(
    "--pane",
    "pane_specs",
    multiple=True,
    metavar="PANE_ID=PANE_PID",
    help=(
        "A pane to classify, as PANE_ID=PANE_PID. Repeatable. Merges with any "
        "panes read from stdin JSON."
    ),
)
@click.option(
    "--proc-root",
    default=DEFAULT_PROC_ROOT,
    show_default=True,
    help="Override the /proc root (testing / debugging).",
)
@click.option(
    "--cgroup-mount",
    default=DEFAULT_CGROUP_MOUNT,
    show_default=True,
    help="Override the cgroup v2 mount point (testing / debugging).",
)
def agents_kind_command(
    pane_specs: tuple[str, ...],
    proc_root: str,
    cgroup_mount: str,
) -> None:
    """Classify the agent kind running in each pane's cgroup scope."""
    panes = _parse_stdin_panes() + _parse_pane_options(pane_specs)

    envelope = _try_daemon_call(
        panes, proc_root=proc_root, cgroup_mount=cgroup_mount
    )
    if envelope is None:
        envelope = _classify_in_process(
            panes, proc_root=proc_root, cgroup_mount=cgroup_mount
        )

    click.echo(json.dumps(envelope))
