"""Per-session memory cap resolution for `pocketshell sessions create` (#2562).

Every session PocketShell starts must be memory-capped. The reason is written
into `sessions.py` and predates this module: a runaway session that can eat
the whole box triggers "the OOM-kill cascade that wiped the agent team", and
the box is the maintainer's only dev machine, usually reached from a phone
where recovering from that cascade is worst.

Where the number lives
----------------------

In the repo's ``cgroups.toml``, and nowhere else. This module is a READER of
that file, never a second copy of the value:

.. code-block:: toml

    # <repo>/cgroups.toml
    mem = "30G"

Resolution order for a session whose workspace is ``W`` (first match wins):

1. an explicit ``--mem`` on the command line (``--mem none`` = uncapped, see
   below);
2. ``W/cgroups.toml``'s top-level ``mem``;
3. ``W/pyproject.toml``'s ``[tool.tmuxctl] mem`` (Python projects state it
   there instead of a dedicated file);
4. the same two files at ``W``'s git root;
5. :data:`DEFAULT_MEM` — the documented fallback, never "no cap".

That is deliberately the same shape ``tmuxctl.robust.read_project_value`` /
``resolve_mem`` use for the tmux arm, so a workspace resolves to the SAME
ceiling whichever backend a session lands on. ``tests/test_sessions_mem_cap``
pins that agreement against the real tmuxctl.

Why a pocketshell-owned reader instead of ``from tmuxctl.robust import
resolve_mem`` (the decision #2562 asks to record)
-------------------------------------------------------------------------

tmuxctl is being removed (#2561). Importing its resolver would make the
aplexer arm — the arm that OUTLIVES tmux — depend on the dependency that
removal deletes, so the cap would silently vanish again at deletion time,
which is exactly the failure this issue exists to close. The per-project
CONFIG FILE, not tmuxctl's code, is the contract: `cgroups.toml` stays where
it is and keeps its format (an explicit non-goal of #2562), and this module
becomes its sole reader once the tmux arm goes. tmuxctl's env layer
(``ROBUST_TMUX_MEM``) and its ``~/.config/tmuxctl/cgroups.toml`` user-config
layer are deliberately NOT reproduced: both are tmuxctl-namespaced knobs that
die with it, and a session cap is not something an inherited environment
variable should be able to change.

Failing loud
------------

A cap that cannot be resolved is an ERROR, not a shrug: a malformed
``cgroups.toml`` or an unparseable size raises :class:`MemCapError` and the
create is refused before anything is started. Silently running uncapped is the
bug (#2562), and silently substituting the fallback for a file the project
clearly meant to be authoritative would hide it just as well.

The single, explicit escape hatch is ``--mem none`` at the CALL SITE. It
exists because a cap is unenforceable on a host with no delegated cgroup-v2
user scope (an unprivileged container, e.g. the Docker `agents` fixture),
where aplexer itself fails closed with "systemd did not delegate the memory
controller". A committed config file can NOT request it: ``mem = "none"`` in
``cgroups.toml`` is an error, so an uncapped session always has a human-typed
flag behind it.
"""

from __future__ import annotations

import re
import subprocess
import tomllib
from pathlib import Path
from typing import Optional

#: Filename holding a project's cap for non-Python projects (PocketShell's own).
PROJECT_CONFIG_NAME = "cgroups.toml"

#: Python projects state the same value under ``[tool.tmuxctl] mem``.
PYPROJECT_NAME = "pyproject.toml"

#: The documented fallback for a workspace whose project declares no cap.
#: Same value tmuxctl falls back to (``robust.DEFAULT_MEM``), so the two arms
#: agree on a capless workspace. A cap that is merely a default is still a cap.
DEFAULT_MEM = "12G"

#: The one spelling that means "run this session uncapped". Accepted ONLY from
#: an explicit ``--mem``, never from a project file.
UNCAPPED = "none"

#: Anything smaller than this is a typo, not a policy (``mem = "30"`` meaning
#: 30 GiB would otherwise resolve to 30 BYTES). Refused loudly: aplexer would
#: fail closed on it anyway, with a much less obvious message.
MIN_MEM_BYTES = 64 * 1024**2

_SIZE_UNITS = {
    "B": 1,
    "K": 1024,
    "KB": 1024,
    "KIB": 1024,
    "M": 1024**2,
    "MB": 1024**2,
    "MIB": 1024**2,
    "G": 1024**3,
    "GB": 1024**3,
    "GIB": 1024**3,
    "T": 1024**4,
    "TB": 1024**4,
    "TIB": 1024**4,
}

_SIZE_RE = re.compile(r"\s*([0-9]+(?:\.[0-9]+)?)\s*([a-zA-Z]*)\s*\Z")

#: `git rev-parse` on a workspace is a local, sub-millisecond call; a hung git
#: must not hang a create started from the phone.
_GIT_TIMEOUT_S = 5.0


class MemCapError(RuntimeError):
    """The session's memory cap could not be resolved — refuse the create."""


def parse_size(value: str, *, source: str) -> int:
    """Parse a human size (``30G``, ``512M``, ``1.5G``, ``1048576``) to bytes.

    Accepts exactly what ``tmuxctl.robust.parse_size`` accepts, so the same
    project file resolves identically on both arms — and is a strict superset
    of what aplexer's ``parse_byte_size`` takes (it has no fractional form).
    That is why callers hand aplexer the resulting BYTE COUNT rather than the
    raw string: a value tmuxctl honours can then never be rejected, or worse
    reinterpreted, one layer down.

    ``source`` names where the value came from, for the error message.
    """
    text = str(value).strip()
    if not text:
        raise MemCapError(f"{source}: empty memory cap")
    match = _SIZE_RE.fullmatch(text)
    if match is None:
        raise MemCapError(f"{source}: {value!r} is not a memory size (e.g. 30G)")
    unit = (match.group(2) or "B").upper()
    if unit not in _SIZE_UNITS:
        raise MemCapError(f"{source}: unknown memory-size unit in {value!r}")
    size = int(float(match.group(1)) * _SIZE_UNITS[unit])
    if size < MIN_MEM_BYTES:
        raise MemCapError(
            f"{source}: memory cap {value!r} resolves to {size} bytes, below the "
            f"{MIN_MEM_BYTES}-byte floor — did you mean {text}G?"
        )
    return size


def is_uncapped(flag: Optional[str]) -> bool:
    """Whether an explicit ``--mem`` value asks for NO cap at all."""
    return bool(flag) and flag.strip().lower() == UNCAPPED


def _read_project_config(path: Path) -> Optional[str]:
    """``mem`` from a dedicated ``cgroups.toml``.

    A file that exists but cannot be parsed raises: ``cgroups.toml`` has
    exactly one job, so an unreadable one means the project's cap is unknown,
    and guessing is how the cap gets lost silently.
    """
    if not path.is_file():
        return None
    try:
        document = tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, tomllib.TOMLDecodeError) as exc:
        raise MemCapError(f"{path}: cannot be read as TOML: {exc}") from exc
    value = document.get("mem")
    return None if value is None else str(value)


def _read_pyproject(path: Path) -> Optional[str]:
    """``[tool.tmuxctl] mem`` from a ``pyproject.toml``.

    Unlike ``cgroups.toml`` this file is NOT primarily a cap declaration, so a
    malformed one is skipped rather than fatal (tmuxctl does the same): the
    resolution simply continues, and the fallback still caps the session.
    """
    if not path.is_file():
        return None
    try:
        document = tomllib.loads(path.read_text(encoding="utf-8"))
    except (OSError, UnicodeDecodeError, tomllib.TOMLDecodeError):
        return None
    tool = document.get("tool")
    section = tool.get("tmuxctl") if isinstance(tool, dict) else None
    value = section.get("mem") if isinstance(section, dict) else None
    return None if value is None else str(value)


def _git_root(start: Path) -> Optional[Path]:
    try:
        completed = subprocess.run(
            ["git", "-C", str(start), "rev-parse", "--show-toplevel"],
            capture_output=True,
            text=True,
            check=False,
            timeout=_GIT_TIMEOUT_S,
        )
    except (OSError, subprocess.TimeoutExpired):
        return None
    if completed.returncode != 0:
        return None
    top = completed.stdout.strip()
    return Path(top) if top else None


def read_project_mem(workspace: Path) -> Optional[tuple[str, Path]]:
    """The project cap for ``workspace`` as ``(raw value, file it came from)``.

    Checks the workspace directory first, then its git root — the same order
    (and the same two file shapes) tmuxctl uses, so a session started deep in
    a repo still gets the repo's committed cap.
    """
    try:
        base = workspace.resolve()
    except OSError:  # pragma: no cover - resolve() is strict=False here
        base = workspace
    directories = [base]
    root = _git_root(base)
    if root is not None and root != base:
        directories.append(root)
    for directory in directories:
        for reader, name in (
            (_read_project_config, PROJECT_CONFIG_NAME),
            (_read_pyproject, PYPROJECT_NAME),
        ):
            path = directory / name
            value = reader(path)
            if value is not None:
                return value, path
    return None


def resolve_session_mem_bytes(*, flag: Optional[str], workspace: str) -> Optional[int]:
    """The cap, in bytes, for a session created in ``workspace``.

    ``None`` means "explicitly uncapped" and can ONLY come from ``--mem none``.
    Every other outcome is a positive byte count or a :class:`MemCapError`;
    there is no path that returns "no cap" by accident.
    """
    if flag is not None and flag.strip():
        if is_uncapped(flag):
            return None
        return parse_size(flag, source="--mem")
    found = read_project_mem(Path(workspace))
    if found is None:
        return parse_size(DEFAULT_MEM, source="the built-in default cap")
    raw, path = found
    if is_uncapped(raw):
        raise MemCapError(
            f"{path}: mem = {raw!r} is not allowed in a project file — an "
            "uncapped session must be asked for explicitly with `--mem none`"
        )
    return parse_size(raw, source=str(path))
