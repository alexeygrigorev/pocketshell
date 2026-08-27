"""Host-local client for the ``a`` binary (aplexer Phase A).

PocketShell and aplexer share a machine: the helper invokes ``a --json …``
and overlays presentation/tmux concerns. Probe failures are silent and
return ``None`` so every call site can fall back to the native path.

Kill switches (any one is enough to skip):

- ``POCKETSHELL_APLEXER=0`` — master
- ``POCKETSHELL_APLEXER_PROFILES=0``
- ``POCKETSHELL_APLEXER_ENGINES=0``
- ``POCKETSHELL_APLEXER_LAUNCH=0``
- ``POCKETSHELL_APLEXER_SESSIONS=0``

``APLEXER_BIN`` overrides ``PATH`` lookup of ``a``.
"""

from __future__ import annotations

import json
import os
import shutil
import signal
import subprocess
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


def which_a(env: Optional[Mapping[str, str]] = None) -> Optional[str]:
    source = env_map(env)
    explicit = source.get(BIN_ENV)
    if explicit:
        return explicit
    return shutil.which("a", path=source.get("PATH"))


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
