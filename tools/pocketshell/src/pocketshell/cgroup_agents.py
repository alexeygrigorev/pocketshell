"""Scope/cgroup-based agent-kind detection for the ``agents.kind_for_panes`` RPC.

Replaces the fragile client-side ``ps -eo … | grep`` agent scan (issue #809 /
#811) with a deterministic server-side read of cgroup v2 + ``/proc``:

1. **pane → scope** — read ``/proc/<pane_pid>/cgroup``. On a cgroup-v2 host the
   sole ``0::`` line carries the cgroup-relative path of the leaf, which for a
   PocketShell session is ``…/robust.slice/tmuxctl-<session>.scope`` (the
   deterministic name `tmuxctl` assigns at ``robust.scope_unit_name``). Sessions
   started outside tmuxctl resolve to ``tmux-spawn-<uuid>.scope`` or a login
   scope; "no scope" is detectable, never a crash.
2. **scope → procs** — read that cgroup's ``cgroup.procs`` then each
   ``/proc/<pid>/comm`` + ``/proc/<pid>/cmdline``. No ``systemctl status``
   shell-out (it forks, pretty-prints ~100 ms, and truncates the proc list);
   raw cgroupfs reads are ~7-17 ms and complete.
3. **classify** — match comm/cmdline tokens for claude / codex / opencode,
   mirroring the proven token rules in
   ``shared/core-agents/.../AgentDetector.kt`` (``namesAgent`` /
   ``containsCommandToken``): a word-boundary match so ``codex`` matches a bare
   ``codex`` comm AND a ``node …/codex`` node-wrapped cmdline, but
   ``codex-helper`` substrings of an unrelated path do not false-positive on the
   raw token alone.

All filesystem roots are injectable (``proc_root`` / ``cgroup_mount``) so the
classifier unit-tests against a synthetic ``/proc``-like tree with zero live
sessions. Every read is defensive: a missing pid, a pid that vanished between
the ``cgroup.procs`` read and the ``comm`` read, a no-scope pane, or a
permission error degrades to ``agent_kind = "none"`` (or an explicit
``"unknown"`` for an unreadable pane) — never an exception that fails the whole
batch.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Mapping, Optional, Sequence

# Default cgroup v2 mount point on Linux. Injectable for tests.
DEFAULT_CGROUP_MOUNT = "/sys/fs/cgroup"
DEFAULT_PROC_ROOT = "/proc"

# Agent kinds returned to the client. ``none`` = pane resolved to a scope (or a
# readable proc set) but no agent process is present. ``unknown`` = the pane's
# pid / cgroup could not be read at all (vanished, permission), so we cannot
# even assert "no agent" — the client should treat it as not-yet-known, not as
# a confirmed plain shell.
AGENT_CLAUDE = "claude"
AGENT_CODEX = "codex"
AGENT_OPENCODE = "opencode"
AGENT_NONE = "none"
AGENT_UNKNOWN = "unknown"

# Token patterns mirror AgentDetector.namesAgent (Kotlin). Each is wrapped in
# the same word-boundary guard as Kotlin's `containsCommandToken` so the token
# only matches as a whole command word — bounded by start/end, whitespace, or
# the shell/path delimiters `/ | ; & ( ) ' " \``. This lets `codex` match both
# a bare `codex` comm and a `node /…/codex` node-wrapped cmdline while a stray
# substring inside an unrelated token does not light up.
_AGENT_TOKEN_PATTERNS: Sequence[tuple[str, str]] = (
    # (agent_kind, command-token regex — verbatim from the Kotlin rules)
    (AGENT_CLAUDE, r"claude(?:-?code)?"),
    (AGENT_CODEX, r"codex"),
    (AGENT_OPENCODE, r"open[-_]?code(?:[-_][a-z0-9]+)?"),
)

# Pre-compile the boundary-wrapped matchers once. The guard is identical to
# `containsCommandToken`: a leading start/delimiter and a trailing
# end/delimiter lookahead, case-insensitive.
_BOUNDARY_LEAD = r"(^|[\s/|;&('\"`])"
_BOUNDARY_TAIL = r"(?=$|[\s/|;&):'\"`])"
_COMPILED_TOKENS: Sequence[tuple[str, "re.Pattern[str]"]] = tuple(
    (kind, re.compile(_BOUNDARY_LEAD + pattern + _BOUNDARY_TAIL))
    for kind, pattern in _AGENT_TOKEN_PATTERNS
)

# Extract the scope unit basename from a cgroup-v2 relative path. Matches both
# the tmuxctl session scope and the non-tmuxctl spawn scope so "which scope"
# is reported even when no agent is detected.
_SCOPE_BASENAME_RE = re.compile(r"(?P<scope>(?:tmuxctl|tmux-spawn)-[^/]+\.scope)")


@dataclass(frozen=True)
class PaneAgentResult:
    """One pane's classification result.

    ``scope`` is the cgroup scope basename the pane resolved to (e.g.
    ``tmuxctl-git-pocketshell.scope``) or ``None`` if the pane had no
    tmuxctl/spawn scope (login shell, no systemd scopes). ``evidence_pid`` is
    the pid of the process whose comm/cmdline named the agent, for debugging.
    """

    pane_id: Optional[str]
    agent_kind: str
    scope: Optional[str]
    evidence_pid: Optional[int] = None

    def to_json(self) -> dict[str, object]:
        out: dict[str, object] = {
            "pane_id": self.pane_id,
            "agent_kind": self.agent_kind,
            "scope": self.scope,
        }
        if self.evidence_pid is not None:
            out["evidence_pid"] = self.evidence_pid
        return out


def classify_token(text: str) -> Optional[str]:
    """Return the agent kind named by ``text`` (a comm or cmdline), or ``None``.

    Mirrors ``AgentDetector.namesAgent``: lowercases then applies the
    word-boundary command-token match for each engine, in claude→codex→opencode
    order. The order only matters in the impossible case of a single string
    naming two engines; a real comm/cmdline names at most one.
    """
    lowered = text.lower()
    for kind, pattern in _COMPILED_TOKENS:
        if pattern.search(lowered):
            return kind
    return None


def _read_text(path: Path) -> Optional[str]:
    """Read a small ``/proc``/cgroupfs file, returning ``None`` on any error.

    cgroupfs and ``/proc`` files can race away between enumeration and read
    (the pid exits), or be unreadable; callers treat ``None`` as "skip", never
    as a fatal error.
    """
    try:
        return path.read_text(encoding="utf-8", errors="replace")
    except (FileNotFoundError, ProcessLookupError, PermissionError, OSError):
        return None


def _read_cmdline(path: Path) -> Optional[str]:
    """Read a ``/proc/<pid>/cmdline`` (NUL-delimited) as a space-joined string."""
    try:
        raw = path.read_bytes()
    except (FileNotFoundError, ProcessLookupError, PermissionError, OSError):
        return None
    if not raw:
        return ""
    # cmdline args are NUL-separated, trailing NUL terminates the last arg.
    decoded = raw.decode("utf-8", errors="replace")
    return " ".join(part for part in decoded.split("\x00") if part).strip()


def scope_relpath_for_pid(
    pane_pid: int,
    *,
    proc_root: str = DEFAULT_PROC_ROOT,
) -> Optional[str]:
    """Resolve a pid's cgroup-v2 relative scope path from ``/proc/<pid>/cgroup``.

    Returns the cgroup-relative path of the leaf cgroup (the part after the
    ``0::``), e.g.
    ``/user.slice/…/robust.slice/tmuxctl-git-pocketshell.scope``. Returns
    ``None`` when the pid is gone, unreadable, or the cgroup file is not
    cgroup-v2 unified (no ``0::`` line).
    """
    content = _read_text(Path(proc_root) / str(pane_pid) / "cgroup")
    if content is None:
        return None
    for line in content.splitlines():
        # cgroup v2 unified hierarchy: exactly one line "0::<path>".
        if line.startswith("0::"):
            relpath = line[len("0::"):].strip()
            return relpath or None
    return None


def scope_basename(relpath: str) -> Optional[str]:
    """Return the ``tmuxctl-*.scope`` / ``tmux-spawn-*.scope`` basename, if any."""
    match = _SCOPE_BASENAME_RE.search(relpath)
    if match:
        return match.group("scope")
    return None


def _scope_procs(
    relpath: str,
    *,
    cgroup_mount: str = DEFAULT_CGROUP_MOUNT,
) -> Optional[list[int]]:
    """Read ``cgroup.procs`` for the cgroup at ``relpath``.

    ``relpath`` is the cgroup-v2 relative path from
    :func:`scope_relpath_for_pid`; the absolute cgroupfs path is the mount plus
    that relative path. Returns the list of pids, or ``None`` if the cgroup is
    gone / unreadable (e.g. the session ended between the pane read and now).
    """
    # relpath is absolute-style ("/user.slice/…"); join under the mount.
    cgroup_dir = Path(cgroup_mount) / relpath.lstrip("/")
    content = _read_text(cgroup_dir / "cgroup.procs")
    if content is None:
        return None
    pids: list[int] = []
    for token in content.split():
        try:
            pids.append(int(token))
        except ValueError:
            continue
    return pids


def classify_scope_procs(
    pids: Iterable[int],
    *,
    proc_root: str = DEFAULT_PROC_ROOT,
) -> tuple[str, Optional[int]]:
    """Classify the agent kind present among ``pids`` by their comm + cmdline.

    Returns ``(agent_kind, evidence_pid)``. Reads each pid's ``comm`` first
    (cheap, definitive for a non-wrapped CLI like ``claude``/``codex``) then
    its ``cmdline`` (catches the node-wrapped form where ``comm`` is
    ``node``/``MainThread`` but the cmdline carries ``node /…/codex``). The
    first pid that names an agent wins; if none do, returns ``("none", None)``.
    """
    proc = Path(proc_root)
    for pid in pids:
        comm = _read_text(proc / str(pid) / "comm")
        if comm is not None:
            kind = classify_token(comm.strip())
            if kind is not None:
                return kind, pid
        cmdline = _read_cmdline(proc / str(pid) / "cmdline")
        if cmdline:
            kind = classify_token(cmdline)
            if kind is not None:
                return kind, pid
    return AGENT_NONE, None


def kind_for_pane(
    pane_pid: int,
    *,
    pane_id: Optional[str] = None,
    proc_root: str = DEFAULT_PROC_ROOT,
    cgroup_mount: str = DEFAULT_CGROUP_MOUNT,
) -> PaneAgentResult:
    """Resolve one pane's agent kind end-to-end (pane_pid → scope → classify).

    Never raises: an unreadable pane pid yields ``agent_kind="unknown"`` (we
    cannot even assert "no agent"); a readable scope with no agent process
    yields ``agent_kind="none"``.
    """
    relpath = scope_relpath_for_pid(pane_pid, proc_root=proc_root)
    if relpath is None:
        # pid gone / cgroup unreadable — we cannot say anything definitive.
        return PaneAgentResult(
            pane_id=pane_id, agent_kind=AGENT_UNKNOWN, scope=None
        )

    scope = scope_basename(relpath)
    pids = _scope_procs(relpath, cgroup_mount=cgroup_mount)
    if pids is None:
        # The cgroup itself is gone/unreadable even though the pane's cgroup
        # line resolved — treat as unknown (the session likely just ended).
        return PaneAgentResult(
            pane_id=pane_id, agent_kind=AGENT_UNKNOWN, scope=scope
        )

    agent_kind, evidence_pid = classify_scope_procs(pids, proc_root=proc_root)
    return PaneAgentResult(
        pane_id=pane_id,
        agent_kind=agent_kind,
        scope=scope,
        evidence_pid=evidence_pid,
    )


def kind_for_panes(
    panes: Iterable[Mapping[str, object]],
    *,
    proc_root: str = DEFAULT_PROC_ROOT,
    cgroup_mount: str = DEFAULT_CGROUP_MOUNT,
) -> list[dict[str, object]]:
    """Batch classifier: resolve agent kind for every pane in ``panes``.

    Each pane is a mapping with ``pane_pid`` (int, required) and an optional
    ``pane_id`` (passed through so the client can correlate). A pane with a
    missing/invalid ``pane_pid`` yields ``agent_kind="unknown"`` rather than
    failing the batch — one bad pane never sinks the others.
    """
    results: list[dict[str, object]] = []
    for pane in panes:
        pane_id = pane.get("pane_id")
        pane_id_str = str(pane_id) if pane_id is not None else None
        raw_pid = pane.get("pane_pid")
        try:
            pane_pid = int(raw_pid)  # type: ignore[arg-type]
        except (TypeError, ValueError):
            results.append(
                PaneAgentResult(
                    pane_id=pane_id_str, agent_kind=AGENT_UNKNOWN, scope=None
                ).to_json()
            )
            continue
        result = kind_for_pane(
            pane_pid,
            pane_id=pane_id_str,
            proc_root=proc_root,
            cgroup_mount=cgroup_mount,
        )
        results.append(result.to_json())
    return results
