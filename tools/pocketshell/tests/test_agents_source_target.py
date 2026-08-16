"""Issue #2159 — the transcript-source watcher must name its tmux target.

REPRODUCE-FIRST (G10/D33) for the maintainer's on-device report: the
Conversation tab said ``Live`` and rendered "No conversation events yet."
while the Terminal of the SAME session showed a live Codex mid-turn. On the
host the tmux session carried ``@ps_agent_kind = codex`` and
``@ps_agent_source_generation`` but ``@ps_agent_source`` was **unset** — the
launch recorded the kind and minted a generation, and the transcript path was
never written.

### Why the write can silently go missing

``record_agent_source`` spawns a DETACHED child
(``_watch_and_record_agent_source``) that, once the transcript appears,
re-reads ``@ps_agent_source_generation`` as a guard and then writes
``@ps_agent_source``. Both operations used to run with **no ``-t`` target**,
so tmux resolved "the current session" from the child's ambient environment.
That inference is unsound:

* with ``TMUX_PANE`` present it names the launching pane's session, and
* with ``TMUX_PANE`` absent tmux silently falls back to the **most-recently-used
  session on the server** — a DIFFERENT session.

Both failure directions are bad, and the observable result of the first is
byte-identical to the maintainer's state:

* the guard reads a FOREIGN session's generation, sees a mismatch, and returns
  1 — a silent give-up that leaves ``@ps_agent_source_generation`` set (written
  earlier by the parent, whose inference IS authoritative) and
  ``@ps_agent_source`` unset;
* or, worse, the write lands on the WRONG session, handing an unrelated session
  a bogus recorded source — the #819/#2155 wrong-transcript class.

### These tests drive the REAL path

They stand up a REAL tmux server on an isolated socket (never the maintainer's
default socket) with TWO sessions carrying DIFFERENT generations, and run the
production watcher against it. The FIXTURE is what makes it a reproduction: a
single-session server can never enter the failing state, so a happy fixture
would prove nothing (the #847/v0.4.10 lesson). The failing ambient state is
INJECTED (``TMUX_PANE`` withheld) rather than raced for, so the reproduction is
deterministic.
"""

from __future__ import annotations

import os
import shutil
import subprocess
import uuid

import pytest

from pocketshell import agents


pytestmark = pytest.mark.skipif(
    shutil.which("tmux") is None,
    reason="tmux is required to reproduce the real target-resolution behaviour",
)


class _TmuxServer:
    """A throwaway tmux server on its own socket (process.md: never `default`)."""

    def __init__(self) -> None:
        self.socket = f"pocketshell-i2159-{os.getpid()}-{uuid.uuid4().hex[:8]}"

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


def _child_env(server: _TmuxServer, *, pane: str | None) -> dict[str, str]:
    """The environment a DETACHED watcher child actually runs with.

    ``TMUX`` names the server (so the child can talk to it at all). ``TMUX_PANE``
    is the ambient signal tmux uses to infer "the current session" — withholding
    it injects the exact state in which the untargeted read/write silently
    retargets another session.
    """
    env = {
        "PATH": os.environ.get("PATH", "/usr/bin:/bin"),
        "TMUX": f"/tmp/tmux-{os.getuid()}/{server.socket},0,0",
    }
    if pane is not None:
        env["TMUX_PANE"] = pane
    return env


def _run_watcher(
    server: _TmuxServer,
    *,
    target: str,
    generation: str,
    source: str,
    pane: str | None,
) -> int:
    """Run the production watcher in a child process against the real server.

    The watcher is executed out-of-process with the injected environment so the
    `tmux` invocations it makes resolve exactly the way they do on-device.
    """
    program = (
        "import sys;"
        "from pocketshell import agents;"
        "agents._latest_agent_source = lambda kind, cwd, started_at: sys.argv[5];"
        "raise SystemExit(agents._watch_and_record_agent_source("
        "sys.argv[1], sys.argv[2], sys.argv[3], sys.argv[4], sys.argv[6],"
        " timeout_seconds='2'))"
    )
    env = _child_env(server, pane=pane)
    env["PYTHONPATH"] = os.pathsep.join(
        p for p in (os.environ.get("PYTHONPATH", ""), _src_root()) if p
    )
    # `tmux -L <socket>` is how the test server is addressed; the watcher builds
    # bare `tmux` argv, so route it through a shim on PATH.
    env["PATH"] = f"{_tmux_shim_dir(server)}{os.pathsep}{env['PATH']}"
    completed = subprocess.run(
        [
            os.sys.executable,
            "-c",
            program,
            "codex",
            "/workspace/proj",
            "0",
            generation,
            source,
            target,
        ],
        check=False,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        env=env,
    )
    return completed.returncode


_SHIM_DIRS: dict[str, str] = {}


def _src_root() -> str:
    return os.path.dirname(os.path.dirname(os.path.abspath(agents.__file__)))


def _tmux_shim_dir(server: _TmuxServer) -> str:
    """A PATH shim so the watcher's bare `tmux` hits the isolated test server."""
    cached = _SHIM_DIRS.get(server.socket)
    if cached is not None:
        return cached
    import tempfile

    directory = tempfile.mkdtemp(prefix="i2159-tmux-shim-")
    shim = os.path.join(directory, "tmux")
    real = shutil.which("tmux")
    with open(shim, "w", encoding="utf-8") as handle:
        handle.write(f'#!/bin/sh\nexec {real} -L {server.socket} "$@"\n')
    os.chmod(shim, 0o755)
    _SHIM_DIRS[server.socket] = directory
    return directory


def test_watcher_records_on_its_own_session_without_ambient_pane(tmux_server):
    """THE REPORTED STATE: the write must land even with no ambient `TMUX_PANE`.

    RED on base: with no ``-t`` the guard read resolves the DECOY session's
    generation, the mismatch trips the give-up branch, and ``@ps_agent_source``
    is never written on the target — exactly the maintainer's
    generation-present / source-absent host state.
    """
    target_id = tmux_server.new_session("target")
    tmux_server.new_session("decoy")
    generation = "gen-target"
    tmux_server.run("set-option", "-t", "=target:", "@ps_agent_source_generation", generation)
    tmux_server.run("set-option", "-t", "=decoy:", "@ps_agent_source_generation", "gen-decoy")

    rc = _run_watcher(
        tmux_server,
        target=target_id,
        generation=generation,
        source="/home/u/.codex/sessions/2026/08/15/rollout-live.jsonl",
        pane=None,
    )

    # LOAD-BEARING (G6): the symptom-defining signal is the OPTION ON THE
    # TARGET SESSION, not the watcher's exit code.
    assert tmux_server.option("target", "@ps_agent_source") == (
        f"{generation}\t/home/u/.codex/sessions/2026/08/15/rollout-live.jsonl"
    ), (
        "#2159: the watcher must write @ps_agent_source on the session it was "
        "launched for. An empty value is the maintainer's exact host state "
        f"(generation present, source absent). watcher rc={rc}"
    )


def test_watcher_never_writes_onto_another_session(tmux_server):
    """Class coverage (G2): the untargeted write could hit the WRONG session.

    The mirror of the test above and the more damaging direction: a source
    written onto an unrelated session is the #819/#2155 wrong-transcript class.
    """
    target_id = tmux_server.new_session("target")
    tmux_server.new_session("decoy")
    generation = "gen-target"
    tmux_server.run("set-option", "-t", "=target:", "@ps_agent_source_generation", generation)
    tmux_server.run("set-option", "-t", "=decoy:", "@ps_agent_source_generation", generation)

    _run_watcher(
        tmux_server,
        target=target_id,
        generation=generation,
        source="/home/u/.codex/sessions/2026/08/15/rollout-live.jsonl",
        pane=None,
    )

    assert tmux_server.option("decoy", "@ps_agent_source") == "", (
        "#2159: the watcher must never record a transcript source onto a "
        "session it was not launched for."
    )


def test_watcher_still_honours_a_real_generation_bump(tmux_server):
    """The generation guard must keep working once the target is explicit.

    A SECOND launch in the same tmux session bumps the generation; the first
    watcher must give up rather than overwrite the newer agent's binding
    (#2155's contract).
    """
    target_id = tmux_server.new_session("target")
    tmux_server.run("set-option", "-t", "=target:", "@ps_agent_source_generation", "gen-2")

    rc = _run_watcher(
        tmux_server,
        target=target_id,
        generation="gen-1",
        source="/home/u/.codex/sessions/2026/08/15/rollout-stale.jsonl",
        pane=None,
    )

    assert rc == 1
    assert tmux_server.option("target", "@ps_agent_source") == "", (
        "#2159/#2155: a watcher whose generation was superseded must not write."
    )


def test_record_agent_source_passes_an_explicit_target_to_the_watcher(tmp_path):
    """The parent resolves the target where `$TMUX_PANE` IS authoritative."""
    popen_calls: list[tuple] = []
    run_calls: list[list[str]] = []

    def fake_runner(argv, **kwargs):
        run_calls.append(argv)
        return subprocess.CompletedProcess(argv, 0, stdout="")

    def fake_resolver(env):
        return "$7"

    ok = agents.record_agent_source(
        "codex",
        str(tmp_path),
        env={"TMUX": "/tmp/tmux-1000/default,1234,0", "TMUX_PANE": "%282"},
        runner=fake_runner,
        popen=lambda *args, **kwargs: popen_calls.append((args, kwargs)) or object(),
        resolve_target=fake_resolver,
    )

    assert ok is True
    argv = popen_calls[0][0][0]
    assert argv[-1] == "$7", (
        "#2159: the detached watcher must be handed an explicit tmux target so "
        "it never depends on ambient `$TMUX_PANE` inference."
    )
    # Every parent-side option write is targeted too.
    for call in run_calls:
        assert "-t" in call, f"untargeted tmux write: {call}"


def _resolver_env(server: _TmuxServer, *, pane: str | None) -> dict[str, str]:
    """The environment `_resolve_tmux_session_target` is called with.

    Same shape as the watcher's, plus the PATH shim so the resolver's bare
    `tmux` argv reaches the isolated test server. (`subprocess` resolves the
    executable through ``os.get_exec_path(env)``, so the shim must live on
    *this* PATH, not the ambient one.)
    """
    env = _child_env(server, pane=pane)
    env["PATH"] = f"{_tmux_shim_dir(server)}{os.pathsep}{env['PATH']}"
    return env


def _ambient_session_id(server: _TmuxServer) -> str:
    """What tmux answers when nothing names a target — the confident guess."""
    return server.run("display-message", "-p", "#{session_id}").stdout.strip()


def test_resolver_refuses_to_guess_a_session_without_an_ambient_pane(tmux_server):
    """One layer up (#2159 round 2): the RESOLVER must not infer either.

    ``record_agent_source`` correctly refuses to start an untargeted watcher
    when the target is unresolvable — but that guard is only as honest as the
    resolver feeding it. With ``$TMUX_PANE`` absent the resolver used to fall
    back to ``tmux display-message`` with no ``-t``, which answers with the
    server's most-recently-used session: a confidently WRONG target that the
    caller then treats as authoritative and writes onto. That is the exact
    defect class this issue exists to kill, so "cannot determine" must resolve
    to ``None``, never to somebody else's session (D22 — no ambient fallback).
    """
    target_id = tmux_server.new_session("target")
    decoy_id = tmux_server.new_session("decoy")

    # NON-VACUITY: the fixture really can produce a confident wrong answer.
    # A single-session (or empty) server would return `None` for reasons that
    # have nothing to do with the guard under test.
    ambient = _ambient_session_id(tmux_server)
    assert ambient in (target_id, decoy_id), (
        "fixture cannot enter the failing state: tmux named no ambient session "
        f"({ambient!r}), so a None result would prove nothing"
    )

    resolved = agents._resolve_tmux_session_target(_resolver_env(tmux_server, pane=None))

    # LOAD-BEARING: `None` is the only honest answer. Any session id here is the
    # ambient guess, and the caller cannot tell it apart from a real target.
    assert resolved is None, (
        "#2159: with no $TMUX_PANE the resolver must return None. It returned "
        f"{resolved!r}, which is tmux's most-recently-used session "
        f"(ambient={ambient!r}) — the caller would then record this launch's "
        "transcript source onto an unrelated session."
    )


def test_resolver_names_the_panes_session_not_the_ambient_one(tmux_server):
    """The other half: refusing must not degrade into always refusing.

    Deliberately resolves the session tmux would NOT have guessed, so a correct
    answer can only have come from ``$TMUX_PANE``.
    """
    target_id = tmux_server.new_session("target")
    decoy_id = tmux_server.new_session("decoy")
    ambient = _ambient_session_id(tmux_server)
    assert ambient in (target_id, decoy_id)

    # Whichever session tmux would NOT pick on its own.
    name, expected_id = (
        ("target", target_id) if ambient == decoy_id else ("decoy", decoy_id)
    )
    pane = tmux_server.run(
        "display-message", "-p", "-t", f"={name}:", "#{pane_id}"
    ).stdout.strip()
    assert pane.startswith("%")

    resolved = agents._resolve_tmux_session_target(_resolver_env(tmux_server, pane=pane))

    assert resolved == expected_id, (
        "#2159: the resolver must name the session owning $TMUX_PANE "
        f"({expected_id!r}), not the ambient guess ({ambient!r})."
    )


def test_resolver_returns_none_when_the_pane_is_unknown(tmux_server):
    """Missing-data class (G2): a stale/foreign pane id is not a licence to guess."""
    target_id = tmux_server.new_session("target")
    tmux_server.new_session("decoy")
    assert _ambient_session_id(tmux_server) != ""

    resolved = agents._resolve_tmux_session_target(
        _resolver_env(tmux_server, pane="%99999")
    )

    assert resolved is None, (
        "#2159: an unresolvable pane must not fall through to the ambient "
        f"session; got {resolved!r} (target was {target_id!r})."
    )


def test_record_agent_source_refuses_to_spawn_an_untargeted_watcher(tmp_path):
    """Hard-cut (D22): no ambient-inference fallback path survives.

    When the target cannot be resolved there is no safe write — an untargeted
    watcher would either give up silently or corrupt another session — so the
    watcher is not started at all.
    """
    popen_calls: list[tuple] = []

    ok = agents.record_agent_source(
        "codex",
        str(tmp_path),
        env={"TMUX": "/tmp/tmux-1000/default,1234,0"},
        runner=lambda argv, **kwargs: subprocess.CompletedProcess(argv, 0, stdout=""),
        popen=lambda *args, **kwargs: popen_calls.append((args, kwargs)) or object(),
        resolve_target=lambda env: None,
    )

    assert ok is False
    assert popen_calls == []
