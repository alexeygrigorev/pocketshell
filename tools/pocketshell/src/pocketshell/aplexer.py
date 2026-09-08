"""Host-local client for the ``a`` binary (aplexer Phase A).

PocketShell and aplexer share a machine: the helper invokes ``a --json …``
and overlays presentation concerns. Probe failures are silent and
return ``None`` so every call site can fall back to the native path.

Kill switches (any one is enough to skip):

- ``POCKETSHELL_APLEXER=0`` — master
- ``POCKETSHELL_APLEXER_PROFILES=0``
- ``POCKETSHELL_APLEXER_ENGINES=0``
- ``POCKETSHELL_APLEXER_LAUNCH=0``
- ``POCKETSHELL_APLEXER_SESSIONS=0``

Binary resolution (issue #2543)
-------------------------------

aplexer ships WITH this CLI: PyPI ``aplexer`` is a pinned, Linux-marked hard
dependency (``pyproject.toml``), so ``uv tool install pocketshell`` drops the
``a`` and ``aplexer`` console-scripts into the SAME ``bin`` directory as the
interpreter running pocketshell. There is no separate aplexer install step and
no PATH surgery. Resolution order, highest first:

1. ``APLEXER_BIN`` — the one explicit override (a debug/test knob, not an
   install path).
2. The **bundled** copy next to ``sys.executable`` — the pinned wheel, the
   same anchor ``usage.py::_resolve_quse_binary`` uses for the pinned ``quse``.

There is no third step: the ``PATH`` lookup is HARD-CUT (D22 — no fallback
branch; the superseded code is deleted, not conditioned). This mirrors
``quse`` exactly. A host-level or locally-built ``a`` on ``PATH`` must NOT
shadow the pinned copy, and an absent bundled copy is a packaging-integrity
error that fails loud — not a "please install aplexer" nag. aplexer is a
dependency of this CLI; it is never installed separately.

The original bug this fixes: ``which_a`` used to be a bare
``shutil.which("a", path=env["PATH"])``, and the app drives the host CLI over
a NON-INTERACTIVE SSH command whose PATH is
``~/.local/bin:/usr/local/sbin:…`` — a correctly installed ``a`` outside those
dirs (e.g. ``~/bin/a``) was invisible, and the error said "not installed".

``a`` needs a sibling ``aplexer`` worker binary (``aplexer/src/lib.rs``
``worker_executable`` resolves it next to ``current_exe``, else bare
``aplexer`` on PATH). The pinned wheel always ships both into the same dir, so
:class:`AplexerResolution` reports the worker it found next to ``a``.

A BUNDLED ``a`` with no sibling worker is therefore a packaging-integrity
failure, not a usable resolution (issue #2553): accepting it would let the
pinned CLI start an UNPINNED worker off ``PATH`` — the same separate-install
hazard #2543 removed, one level down. Such a candidate is skipped with a
``tried`` entry saying why, so the caller's existing "could not resolve `a`;
tried …" message explains it instead of a low-level worker-startup error.
``APLEXER_BIN`` is deliberately exempt: it is the one explicit override ("run
exactly this binary"), the reinstall advice does not apply to it, and it is
the seam the test suite's stub ``a`` uses.
"""

from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Mapping, Optional

JSON_TIMEOUT_S = 2.0
LAUNCH_TIMEOUT_S = 5.0
BIN_ENV = "APLEXER_BIN"
# Snapshot Popen so helper tests that patch ``subprocess.Popen`` (to block
# the source-recorder child) cannot swallow ``a`` probes.
_Popen = subprocess.Popen
_TimeoutExpired = subprocess.TimeoutExpired
MASTER_KILL = "POCKETSHELL_APLEXER"
FEATURE_KILLS = {
    "profiles": "POCKETSHELL_APLEXER_PROFILES",
    "engines": "POCKETSHELL_APLEXER_ENGINES",
    "launch": "POCKETSHELL_APLEXER_LAUNCH",
    "sessions": "POCKETSHELL_APLEXER_SESSIONS",
}


def env_map(env: Optional[Mapping[str, str]] = None) -> dict[str, str]:
    merged = dict(os.environ)
    if env:
        merged.update({str(k): str(v) for k, v in env.items()})
    return merged


def enabled(feature: str, env: Optional[Mapping[str, str]] = None) -> bool:
    source = env_map(env)
    if source.get(MASTER_KILL) == "0":
        return False
    kill = FEATURE_KILLS.get(feature)
    if kill and source.get(kill) == "0":
        return False
    return True


@dataclass(frozen=True)
class AplexerResolution:
    """Outcome of resolving the ``a`` CLI, including what was tried.

    ``tried`` is human-readable and exists so a failure can NAME the candidate
    paths instead of claiming aplexer "is not installed" (issue #2543).
    """

    path: Optional[str] = None
    source: Optional[str] = None
    worker: Optional[str] = None
    tried: tuple[str, ...] = field(default_factory=tuple)


def _bundled_bin_dirs() -> list[Path]:
    """Directories that can hold the pinned aplexer console-scripts.

    Console-scripts live next to the UNRESOLVED ``sys.executable``: in a venv
    (or a ``uv tool`` install) ``bin/python`` is a symlink to the underlying
    interpreter, so ``Path(sys.executable).resolve()`` points at the shared
    interpreter dir where ``a`` is NOT installed. Check the interpreter's own
    ``bin`` dir first and only fall through to the resolved dir for layouts
    where the two coincide. Both candidates are anchored to ``sys.executable``
    — this is NOT a PATH search. Same trap, same handling, as
    ``usage.py::_resolve_quse_binary``.
    """
    exe_dir = Path(sys.executable).parent
    dirs = [exe_dir]
    resolved_dir = Path(sys.executable).resolve().parent
    if resolved_dir != exe_dir:
        dirs.append(resolved_dir)
    return dirs


def _sibling_worker(cli_path: str) -> Optional[str]:
    """The ``aplexer`` worker binary shipped next to ``cli_path``, if any."""
    worker = Path(cli_path).parent / "aplexer"
    return str(worker) if worker.exists() else None


def resolve_a(env: Optional[Mapping[str, str]] = None) -> AplexerResolution:
    """Resolve the ``a`` CLI: ``APLEXER_BIN``, else the BUNDLED copy. No PATH.

    See the module docstring: ``PATH`` is deliberately not consulted at all,
    so a separately-installed ``a`` can never be load-bearing. Never raises —
    an unresolvable ``a`` comes back as a report whose ``path`` is ``None``
    and whose ``tried`` lists every candidate, for the caller's error message.
    A bundled ``a`` missing its sibling ``aplexer`` worker counts as
    unresolvable (#2553), for the reason the module docstring gives.
    """
    source = env_map(env)
    tried: list[str] = []

    explicit = source.get(BIN_ENV)
    if explicit:
        return AplexerResolution(
            path=explicit,
            source=BIN_ENV,
            worker=_sibling_worker(explicit),
            tried=(f"{BIN_ENV}={explicit}",),
        )
    tried.append(f"{BIN_ENV} (unset)")

    for bin_dir in _bundled_bin_dirs():
        candidate = bin_dir / "a"
        if candidate.exists():
            worker = _sibling_worker(str(candidate))
            if worker is None:
                # HALF-INSTALLED BUNDLE (#2553). `a` alone is not usable:
                # `aplexer/src/lib.rs::worker_executable` resolves the worker
                # next to `current_exe` and, failing that, runs a BARE
                # `aplexer` off PATH — so returning this `a` would either
                # start an unpinned worker (the separate-install hazard #2543
                # removed, one level down) or die with a low-level startup
                # error. Treated exactly like an absent `a`: keep looking, and
                # if nothing answers, fail loud with this candidate named.
                tried.append(
                    f"{candidate} (bundled, found; sibling `aplexer` worker "
                    "missing — half-installed)"
                )
                continue
            return AplexerResolution(
                path=str(candidate),
                source="bundled",
                worker=worker,
                tried=tuple([*tried, f"{candidate} (bundled, found)"]),
            )
        tried.append(f"{candidate} (bundled, missing)")

    # No PATH lookup by design (D22 hard cut, issue #2543): resolution ends
    # here. An `a` that exists only on PATH is a SEPARATE install, which is the
    # mode this change removes — it must not be picked up silently.
    return AplexerResolution(tried=tuple(tried))


def which_a(env: Optional[Mapping[str, str]] = None) -> Optional[str]:
    """Path to the ``a`` CLI, or ``None``. Thin wrapper over :func:`resolve_a`."""
    return resolve_a(env).path


def run_json(
    args: list[str],
    *,
    env: Optional[Mapping[str, str]] = None,
    timeout: Optional[float] = None,
    feature: Optional[str] = None,
) -> Any | None:
    """Run ``a --json <args>`` and parse stdout. None on skip or any failure."""
    if timeout is None:
        timeout = JSON_TIMEOUT_S
    if feature and not enabled(feature, env):
        return None
    cli = which_a(env)
    if cli is None:
        return None
    try:
        proc = _Popen(
            [cli, "--json", *args],
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env_map(env),
            start_new_session=True,
        )
        try:
            stdout, _stderr = proc.communicate(timeout=timeout)
        except _TimeoutExpired:
            try:
                os.killpg(proc.pid, signal.SIGKILL)
            except OSError:
                proc.kill()
            proc.communicate()
            return None
    except OSError:
        return None
    if proc.returncode != 0:
        return None
    try:
        return json.loads(stdout)
    except json.JSONDecodeError:
        return None
