#!/usr/bin/env python3
"""Fail-loudly guard: the Docker `agents` fixture must run a REAL aplexer.

Issue #2563 (slice 1 of #2561). Installed into the image as
``/usr/local/bin/pocketshell-fixture-aplexer-selfcheck`` and executed during
``docker build``, so the fixture CANNOT be built hollow.

Why this exists
---------------

``pocketshell.aplexer::_bundled_bin_dirs`` resolves ``a`` next to
``sys.executable`` and NOWHERE else — the PATH lookup is a D22 hard cut
(#2543). That makes binary PLACEMENT load-bearing in a way no build step
would otherwise notice: with ``a`` reachable only through PATH, every real-CLI
aplexer probe returns ``None``, ``sessions list --json`` reports
``managers: ["tmux"]`` with zero aplexer rows, and the exit code is 0. The
fixture then looks perfectly healthy while proving nothing — the vacuous-pass
shape ``docs/ci-pitfalls.md`` catalogues, and the one the spike behind #2563
actually hit.

So this script converts that silence into a loud, named failure. It drives the
REAL, unmodified CLI (``python3 -m pocketshell``, the same entry point
``pocketshell-real-send`` uses — never the deterministic ``agent-bin``
shim) through a complete aplexer lifecycle:

    resolve the bundled `a`
      -> sessions create --backend aplexer
      -> sessions list --json  contains that row, manager=aplexer,
                               phase=running, alive=true
      -> sessions kill --json  reports killed=true, reaped=true
      -> sessions list --json  no longer contains it

Nothing here reads ``a --version``. A version string provably cannot answer
"is this the binary we think it is" (AGENTS.md: a local build and a published
wheel both self-reported the same version while differing by 143 commits), so
the pin is asserted by BEHAVIOUR — a stub `a`, a missing sibling worker, or an
`a` that moved off the interpreter's directory each fail a named check below.

This file does not, and cannot, prove the binary's VINTAGE: a neighbouring
release that still satisfies the lifecycle would pass. Vintage comes from
`Dockerfile.agents` deriving the version from the same `pyproject.toml` line
production pins and fetching that exact release asset. This file proves that
whatever lands is a WORKING aplexer, which the derivation alone cannot.

Takes no arguments. Exit 0 on success; on failure, exit 1 and one
``FIXTURE-APLEXER-SELFCHECK FAIL [<check>] ...`` line on stderr.
"""

from __future__ import annotations

import json
import os
import pwd
import subprocess
import sys
import time
import uuid
from pathlib import Path

REAL_SRC = os.environ.get("POCKETSHELL_REAL_SRC", "/opt/pocketshell-real/src")
MARKER = "FIXTURE-APLEXER-SELFCHECK"
#: The real CLI has to finish `a start` and see the record; aplexer's own
#: `--startup-timeout-ms` default is 10 s, so give the whole create room.
CREATE_TIMEOUT_S = 60.0
LIST_TIMEOUT_S = 30.0


class CheckFailed(Exception):
    def __init__(self, check: str, message: str) -> None:
        super().__init__(message)
        self.check = check
        self.message = message


def fail(check: str, message: str) -> "CheckFailed":
    return CheckFailed(check, message)


def _home() -> str:
    home = os.environ.get("HOME")
    if home and os.path.isdir(home):
        return home
    return pwd.getpwuid(os.getuid()).pw_dir


def _child_env() -> dict[str, str]:
    env = dict(os.environ)
    env["HOME"] = _home()
    path = env.get("PYTHONPATH")
    env["PYTHONPATH"] = f"{REAL_SRC}:{path}" if path else REAL_SRC
    # A kill switch left set in the environment would make every aplexer probe
    # return None, which is the SILENCE this guard exists to reject. Refuse to
    # "pass" under it rather than quietly measuring nothing.
    for kill in (
        "POCKETSHELL_APLEXER",
        "POCKETSHELL_APLEXER_SESSIONS",
        "POCKETSHELL_APLEXER_LAUNCH",
    ):
        if env.get(kill) == "0":
            raise fail(
                "killswitch",
                f"{kill}=0 is set, so every aplexer probe would silently "
                "return nothing. The fixture cannot be validated in this "
                "environment.",
            )
    return env


def run_cli(args: list[str], *, timeout: float) -> subprocess.CompletedProcess:
    """Run the REAL `pocketshell` CLI, never the agent-bin fixture shim."""
    try:
        return subprocess.run(
            [sys.executable, "-m", "pocketshell", *args],
            capture_output=True,
            text=True,
            timeout=timeout,
            env=_child_env(),
            cwd=_home(),
        )
    except subprocess.TimeoutExpired as exc:
        raise fail(
            "timeout",
            f"`pocketshell {' '.join(args)}` did not finish in {timeout}s",
        ) from exc


def check_placement() -> tuple[str, str]:
    """The bundled `a` + sibling worker must sit next to ``sys.executable``."""
    sys.path.insert(0, REAL_SRC)
    try:
        from pocketshell import aplexer as _aplexer
    except ImportError as exc:  # pragma: no cover - packaging integrity
        raise fail(
            "import",
            f"cannot import pocketshell.aplexer from {REAL_SRC}: {exc}",
        ) from exc

    if os.environ.get("APLEXER_BIN"):
        # APLEXER_BIN is the one explicit override, and honouring it here would
        # let the guard pass while the placement the app depends on is broken.
        raise fail(
            "override",
            "APLEXER_BIN is set; this guard must observe the BUNDLED "
            "resolution, not an override.",
        )

    report = _aplexer.resolve_a()
    expected = str(Path(sys.executable).parent / "a")
    if report.path is None or report.source != "bundled":
        raise fail(
            "placement",
            "the real CLI cannot resolve a bundled `a`, so every aplexer probe "
            "returns nothing and `sessions list --json` reports "
            "managers: [\"tmux\"] with ZERO aplexer rows and exit 0 — a "
            "silently empty fixture. `a` must live next to the interpreter at "
            f"{expected}; being on PATH is NOT enough (PATH lookup is a D22 "
            f"hard cut, #2543). Tried: {report.tried}",
        )
    if report.path != expected:
        raise fail(
            "placement",
            f"bundled `a` resolved to {report.path}, expected {expected} "
            "(next to sys.executable)",
        )
    if not report.worker:
        raise fail(
            "worker",
            f"no sibling `aplexer` worker binary next to {report.path}; `a` "
            "resolves its worker via current_exe and dies with "
            "'spawn worker: No such file or directory'",
        )
    return report.path, report.worker


def _sessions(payload: dict) -> list[dict]:
    rows = payload.get("sessions")
    return [row for row in rows if isinstance(row, dict)] if isinstance(rows, list) else []


def list_json() -> dict:
    proc = run_cli(["sessions", "list", "--json"], timeout=LIST_TIMEOUT_S)
    if proc.returncode != 0:
        raise fail(
            "enumerate",
            f"`sessions list --json` exited {proc.returncode}: "
            f"{proc.stderr.strip()[:400]}",
        )
    try:
        payload = json.loads(proc.stdout)
    except ValueError as exc:
        raise fail(
            "enumerate",
            f"`sessions list --json` emitted non-JSON: {proc.stdout[:400]!r}",
        ) from exc
    if not isinstance(payload, dict):
        raise fail("enumerate", f"expected a JSON object, got {type(payload).__name__}")
    return payload


def check_lifecycle(binary: str) -> None:
    tag = "selfcheck-%s" % uuid.uuid4().hex[:8]
    workspace = _home()

    created = run_cli(
        # `--mem none` (issue #2562): every session PocketShell creates is
        # memory-capped, and aplexer's limits FAIL CLOSED — with no delegated
        # cgroup-v2 user scope, `a start --memory` errors instead of quietly
        # running uncapped. This container has no user systemd
        # ("Failed to connect to user scope bus ... $XDG_RUNTIME_DIR not
        # defined"), so the fixture opts out EXPLICITLY, at the call site,
        # rather than the CLI silently dropping the cap for everyone. Capping
        # itself cannot be proven here; `tools/pocketshell/tests/
        # test_sessions_mem_cap.py` proves it against a real delegated scope.
        [
            "sessions", "create", tag,
            "--backend", "aplexer",
            "--cwd", workspace,
            "--mem", "none",
            "--json",
        ],
        timeout=CREATE_TIMEOUT_S,
    )
    if created.returncode != 0:
        raise fail(
            "create",
            f"`sessions create --backend aplexer` exited {created.returncode} "
            f"using {binary}: {(created.stderr or created.stdout).strip()[:600]}",
        )
    try:
        envelope = json.loads(created.stdout)
    except ValueError as exc:
        raise fail(
            "create", f"create emitted non-JSON: {created.stdout[:400]!r}"
        ) from exc
    if envelope.get("manager") != "aplexer":
        raise fail(
            "create",
            f"create routed to manager={envelope.get('manager')!r}, expected "
            "'aplexer' — the fixture would exercise tmux while claiming aplexer",
        )
    name = str(envelope.get("name") or "")
    if not name:
        raise fail("create", f"create envelope carries no name: {envelope}")

    try:
        payload = list_json()
        rows = _sessions(payload)
        aplexer_rows = [row for row in rows if row.get("manager") == "aplexer"]
        if not aplexer_rows:
            raise fail(
                "enumerate",
                "a real aplexer session was just created, yet `sessions list "
                "--json` returned ZERO aplexer rows "
                f"(managers={payload.get('managers')!r}, "
                f"errors={payload.get('errors')!r}). This is the silent, "
                "green-and-empty fixture #2563 exists to make impossible.",
            )
        mine = [row for row in aplexer_rows if row.get("tag") == tag]
        if not mine:
            raise fail(
                "enumerate",
                f"no aplexer row with tag {tag!r}; got "
                f"{[row.get('tag') for row in aplexer_rows]}",
            )
        row = mine[0]
        if row.get("name") != name:
            raise fail(
                "enumerate",
                f"row name {row.get('name')!r} does not round-trip the create "
                f"envelope's {name!r}",
            )
        if row.get("phase") != "running":
            raise fail(
                "enumerate", f"row phase is {row.get('phase')!r}, expected 'running'"
            )
        if row.get("alive") is not True:
            raise fail("enumerate", f"row alive is {row.get('alive')!r}, expected True")
        if row.get("workspace") != workspace:
            raise fail(
                "enumerate",
                f"row workspace is {row.get('workspace')!r}, expected {workspace!r}",
            )
        if "aplexer" not in (payload.get("managers") or []):
            raise fail(
                "enumerate",
                f"managers is {payload.get('managers')!r}; 'aplexer' answered "
                "with rows so it must be listed as a manager",
            )
        print(f"{MARKER} live row: {json.dumps(row, sort_keys=True)}")
    except CheckFailed:
        run_cli(["sessions", "kill", name, "--json"], timeout=LIST_TIMEOUT_S)
        raise

    killed = run_cli(["sessions", "kill", name, "--json"], timeout=LIST_TIMEOUT_S)
    if killed.returncode != 0:
        raise fail(
            "kill",
            f"`sessions kill {name}` exited {killed.returncode}: "
            f"{(killed.stderr or killed.stdout).strip()[:400]}",
        )
    try:
        kill_envelope = json.loads(killed.stdout)
    except ValueError as exc:
        raise fail("kill", f"kill emitted non-JSON: {killed.stdout[:400]!r}") from exc
    if kill_envelope.get("manager") != "aplexer":
        raise fail(
            "kill", f"kill routed to manager={kill_envelope.get('manager')!r}"
        )
    if kill_envelope.get("killed") is not True:
        raise fail("kill", f"kill reported killed={kill_envelope.get('killed')!r}")
    if kill_envelope.get("reaped") is not True:
        raise fail(
            "reap",
            "kill reported reaped="
            f"{kill_envelope.get('reaped')!r}: aplexer could not prove the "
            "session's containment was cleaned up",
        )

    # The record must actually leave the listing; a killed session that keeps
    # showing up is issue #2554's shape and would poison every journey after.
    deadline = time.monotonic() + 10.0
    while True:
        remaining = [
            row
            for row in _sessions(list_json())
            if row.get("manager") == "aplexer" and row.get("tag") == tag
        ]
        if not remaining:
            break
        if time.monotonic() > deadline:
            raise fail(
                "leak",
                f"session {name!r} still listed 10s after a successful kill: "
                f"{remaining}",
            )
        time.sleep(0.25)
    print(f"{MARKER} lifecycle create -> list -> kill -> gone OK for {name}")


def main(argv: list[str]) -> int:
    if argv[1:]:
        sys.stderr.write(f"usage: {argv[0]}\n")
        return 64
    try:
        binary, worker = check_placement()
        print(f"{MARKER} bundled a={binary} worker={worker}")
        check_lifecycle(binary)
    except CheckFailed as exc:
        sys.stderr.write(f"{MARKER} FAIL [{exc.check}] {exc.message}\n")
        return 1
    print(f"{MARKER} PASS")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv))
