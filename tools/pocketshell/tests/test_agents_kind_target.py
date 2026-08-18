"""Issue #2185 — ``record_agent_kind`` must name its tmux target.

REPRODUCE-FIRST (G10/D33) for the latent sibling of #2159: ``record_agent_kind``
writes ``@ps_agent_kind`` / ``@ps_agent_profile`` with **no ``-t`` target**, so
tmux infers "the current session" from the caller's ambient environment. A
tmux pane process normally carries ``$TMUX_PANE`` and that inference is then
correct — which is why this has not been observed failing on-device. But it is
the identical construction one function over from the watcher that *did* fail:
when ``$TMUX_PANE`` is absent, tmux silently resolves the **most-recently-used
session on the server**. ``@ps_agent_kind`` is the sole kind authority; a write
landing on the wrong session would mislabel an agent with no error surfaced.

### These tests drive the REAL path

They stand up a REAL tmux server on an isolated socket (never the maintainer's
default socket) with TWO sessions, and run the production writer against it.
The FIXTURE is what makes it a reproduction: a single-session server can never
enter the failing state, so a happy fixture would prove nothing (the
#847/v0.4.10 lesson). The failing ambient state is INJECTED (``TMUX_PANE``
withheld from the tmux client that performs the write) rather than raced for.

``tmux -L`` / a PATH shim / ``TMUX_TMPDIR`` only; never the default socket,
never ``tmux kill-server``.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import uuid
from pathlib import Path

import pytest

from pocketshell import agents


pytestmark = pytest.mark.skipif(
    shutil.which("tmux") is None,
    reason="tmux is required to reproduce the real target-resolution behaviour",
)


class _TmuxServer:
    """A throwaway tmux server on its own socket (process.md: never `default`)."""

    def __init__(self) -> None:
        self.socket = f"pocketshell-i2185-{os.getpid()}-{uuid.uuid4().hex[:8]}"

    def run(self, *args: str, env: dict[str, str] | None = None):
        return subprocess.run(
            ["tmux", "-L", self.socket, *args],
            check=False,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            text=True,
            env=env,
        )

    def new_session(self, name: str) -> str:
        self.run("new-session", "-d", "-s", name, "sh", "-c", "sleep 300")
        return self.run(
            "display-message", "-p", "-t", f"={name}:", "#{session_id}"
        ).stdout.strip()

    def option(self, name: str, option: str) -> str:
        result = self.run("show-options", "-v", "-t", f"={name}:", option)
        return result.stdout.strip() if result.returncode == 0 else ""

    def kill(self) -> None:
        # Target sessions by name; never `kill-server` (locked, AGENTS.md).
        for name in ("target", "decoy"):
            self.run("kill-session", "-t", f"={name}:")


@pytest.fixture()
def tmux_server():
    server = _TmuxServer()
    try:
        yield server
    finally:
        server.kill()


_SHIM_DIRS: dict[str, str] = {}


def _src_root() -> str:
    return os.path.dirname(os.path.dirname(os.path.abspath(agents.__file__)))


def _tmux_shim_dir(server: _TmuxServer) -> str:
    """A PATH shim so the resolver's bare `tmux` hits the isolated test server."""
    cached = _SHIM_DIRS.get(server.socket)
    if cached is not None:
        return cached
    import tempfile

    directory = tempfile.mkdtemp(prefix="i2185-tmux-shim-")
    shim = os.path.join(directory, "tmux")
    real = shutil.which("tmux")
    with open(shim, "w", encoding="utf-8") as handle:
        handle.write(f'#!/bin/sh\nexec {real} -L {server.socket} "$@"\n')
    os.chmod(shim, 0o755)
    _SHIM_DIRS[server.socket] = directory
    return directory


def _resolver_env(server: _TmuxServer, *, pane: str | None) -> dict[str, str]:
    """Environment handed to ``record_agent_kind`` / the resolver.

    ``TMUX`` names the server. ``TMUX_PANE`` is the only honest evidence of
    which session this launch belongs to. The PATH shim is required because
    ``_resolve_tmux_session_target`` builds a bare ``tmux`` argv.
    """
    env = {
        "PATH": f"{_tmux_shim_dir(server)}{os.pathsep}{os.environ.get('PATH', '/usr/bin:/bin')}",
        "TMUX": f"/tmp/tmux-{os.getuid()}/{server.socket},0,0",
    }
    if pane is not None:
        env["TMUX_PANE"] = pane
    return env


def _isolated_runner(server: _TmuxServer):
    """A ``subprocess.run`` stand-in that can only talk to the isolated server.

    Production ``record_agent_kind`` used to call the runner with a bare
    ``tmux set-option …`` argv and no ``env=``, so the write inherited the
    *test process* environment — no ``$TMUX_PANE`` for this server. Routing
    the same argv through ``tmux -L <socket>`` injects that failing ambient
    state deterministically without ever touching the default socket.
    """

    def runner(argv, **kwargs):
        assert argv and argv[0] == "tmux", argv
        return server.run(*argv[1:])

    return runner


def _target_pane(server: _TmuxServer) -> tuple[str, str]:
    target_id = server.new_session("target")
    server.new_session("decoy")
    pane = server.run(
        "display-message", "-p", "-t", "=target:", "#{pane_id}"
    ).stdout.strip()
    assert pane.startswith("%"), pane
    return target_id, pane


def test_record_agent_kind_writes_on_its_own_session_not_the_mru(tmux_server):
    """THE LATENT STATE: an untargeted write lands on the MRU session.

    RED on base: ``record_agent_kind`` emits ``tmux set-option @ps_agent_kind``
    with no ``-t``. The isolated runner has no ambient ``$TMUX_PANE``, so tmux
    resolves the DECOY (created last = most-recently-used). The target session
    stays unset and the decoy is silently mislabelled — the same class as
    #2159's missing ``@ps_agent_source``.

    Mutation that must redden this (G6): drop ``-t`` from the kind write (or
    stop resolving a target and write untargeted again).
    """
    target_id, pane = _target_pane(tmux_server)

    ok = agents.record_agent_kind(
        "codex",
        env=_resolver_env(tmux_server, pane=pane),
        runner=_isolated_runner(tmux_server),
    )

    assert ok is True
    # LOAD-BEARING (G6): the symptom-defining signal is the OPTION ON THE
    # TARGET SESSION, not the writer's return value.
    assert tmux_server.option("target", "@ps_agent_kind") == "codex", (
        "#2185: record_agent_kind must write @ps_agent_kind on the session it "
        f"was launched in (target_id={target_id!r}). An empty value means the "
        "untargeted write resolved the most-recently-used session instead."
    )


def test_record_agent_kind_never_writes_onto_another_session(tmux_server):
    """Class coverage (G2): the untargeted write could hit the WRONG session.

    The more damaging direction: a kind written onto an unrelated session is
    the #819/#2155 wrong-identity class, with no error surfaced.
    """
    _target_id, pane = _target_pane(tmux_server)

    agents.record_agent_kind(
        "codex",
        env=_resolver_env(tmux_server, pane=pane),
        runner=_isolated_runner(tmux_server),
    )

    assert tmux_server.option("decoy", "@ps_agent_kind") == "", (
        "#2185: record_agent_kind must never record a kind onto a session it "
        "was not launched in."
    )


def test_record_agent_kind_profile_write_is_targeted_too(tmux_server):
    """The profile option is the same family of session-scoped user options."""
    _target_id, pane = _target_pane(tmux_server)

    ok = agents.record_agent_kind(
        "claude",
        env=_resolver_env(tmux_server, pane=pane),
        runner=_isolated_runner(tmux_server),
        profile="Claude (Z.AI)",
    )

    assert ok is True
    assert tmux_server.option("target", "@ps_agent_kind") == "claude"
    assert tmux_server.option("target", "@ps_agent_profile") == "Claude (Z.AI)"
    assert tmux_server.option("decoy", "@ps_agent_kind") == ""
    assert tmux_server.option("decoy", "@ps_agent_profile") == ""


def test_record_agent_kind_refuses_to_write_when_the_target_is_unresolvable(
    tmux_server,
):
    """Hard-cut (D22): no ambient-inference fallback path survives.

    When ``$TMUX_PANE`` is absent the resolver returns ``None``. An untargeted
    write would land on the MRU session, so there is no safe write — refuse
    rather than guess.
    """
    tmux_server.new_session("target")
    tmux_server.new_session("decoy")

    ok = agents.record_agent_kind(
        "codex",
        env=_resolver_env(tmux_server, pane=None),
        runner=_isolated_runner(tmux_server),
    )

    assert ok is False
    assert tmux_server.option("target", "@ps_agent_kind") == ""
    assert tmux_server.option("decoy", "@ps_agent_kind") == "", (
        "#2185: refusing to resolve must not fall through to an untargeted "
        "write onto the most-recently-used session."
    )


def test_record_agent_kind_argv_names_the_resolved_target():
    """The parent resolves the target where ``$TMUX_PANE`` IS authoritative."""
    run_calls: list[list[str]] = []

    ok = agents.record_agent_kind(
        "codex",
        env={"TMUX": "/tmp/tmux-1000/default,1234,0", "TMUX_PANE": "%282"},
        runner=lambda argv, **kwargs: run_calls.append(argv),
        resolve_target=lambda env: "$7",
    )

    assert ok is True
    assert run_calls, "expected at least the kind write"
    for call in run_calls:
        assert "-t" in call, f"untargeted tmux write: {call}"
        assert "$7" in call, f"write did not name the resolved target: {call}"


def test_open_thread_for_the_foreign_mru_trigger_is_named():
    """AC4: the *trigger* remains unproven; #2185 is the open thread.

    #2159 fixed the untargeted read/write. What actually caused the
    maintainer's session to have a foreign MRU session that day was not
    independently established. If a session-scoped option is ever again
    observed landing on the wrong session, start here rather than filing
    a fresh issue.
    """
    source = Path(agents.__file__).read_text(encoding="utf-8")
    assert "#2185" in source, (
        "#2185 must stay named next to the target resolver as the open "
        "thread for a recurrence of a session-scoped option landing on "
        "the wrong session."
    )
    assert "MRU" in source or "most-recently-used" in source
