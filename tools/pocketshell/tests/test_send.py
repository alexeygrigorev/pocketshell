"""Tests for `pocketshell send` — the acknowledged delivery primitive (#2122).

These run against a REAL tmux server, never the maintainer's. Every tmux call
here is scoped by BOTH an isolated ``TMUX_TMPDIR`` (so the socket file lives
under the test's own ``tmp_path``) and an explicit ``-L`` socket name. ``TMUX``
is scrubbed from the child environment so an enclosing session can never be
targeted. Teardown kills only the sessions this file created, BY NAME:
``tmux kill-server`` appears nowhere, in no fixture and no trap — it has wiped
the maintainer's live sessions twice (AGENTS.md, locked).

Payload fidelity is asserted against the BYTES that reach the pane's process,
not against a ``capture-pane`` screen render (which loses trailing whitespace
and wraps long lines). The fixture pane runs ``stty raw -echo; cat > out.bin``,
so what lands in ``out.bin`` is exactly what tmux wrote to the pty.

The load-bearing assertion throughout is on the PANE CONTENT, not on the exit
code. An exit-code-only check would stay green with a double-injection bug
present, which is the wrong-cost shape (G6) this repo's process doc catalogues.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Optional, Sequence

import pytest
from click.testing import CliRunner

from pocketshell import send as send_mod
from pocketshell.cli import cli

# A generous wall-clock budget for "the bytes reached the pane". Local tmux
# answers in milliseconds; the budget only has to survive a contended box, and
# it HARD-FAILS rather than skipping.
CAPTURE_DEADLINE_SECS = 60.0

# How long a hypothetical SECOND injection would have to stay invisible for the
# idempotency assertion to be fooled. tmux delivers locally in milliseconds, so
# a quiet window of this length is decisive.
QUIET_WINDOW_SECS = 1.5


def _require_tmux() -> str:
    """Absolute path to tmux, HARD-failing when it is absent.

    Deliberately not `pytest.skip`: a skip here would make the CI job green
    while asserting nothing at all about the primitive's core behaviour (G3).
    The workflow installs tmux for exactly this reason.
    """
    binary = shutil.which("tmux")
    if binary is None:
        raise AssertionError(
            "tmux is required by the pocketshell send tests and was not found on PATH. "
            "Install it (apt-get install -y tmux); do NOT skip these tests."
        )
    return binary


# --------------------------------------------------------------------------
# Real-tmux fixture
# --------------------------------------------------------------------------


@dataclass
class TmuxFixture:
    """One isolated tmux server plus a pane that records raw stdin bytes."""

    socket_name: str
    session: str
    pane_id: str
    capture_file: Path
    env: dict
    state_dir: Path
    real_tmux: str

    def tmux(self, *args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [self.real_tmux, "-L", self.socket_name, *args],
            env=self.env,
            capture_output=True,
            check=False,
        )

    def captured(self) -> bytes:
        try:
            return self.capture_file.read_bytes()
        except FileNotFoundError:
            return b""

    def wait_for_capture(self, expected: int, *, deadline: float = CAPTURE_DEADLINE_SECS) -> bytes:
        stop = time.monotonic() + deadline
        while time.monotonic() < stop:
            data = self.captured()
            if len(data) >= expected:
                return data
            time.sleep(0.05)
        raise AssertionError(
            f"pane capture never reached {expected} bytes within {deadline}s "
            f"(got {len(self.captured())})"
        )

    def settle(self, *, quiet: float = QUIET_WINDOW_SECS) -> bytes:
        """Return the capture once it has stopped growing for ``quiet`` seconds.

        This is what makes "exactly one occurrence" decisive: a second
        injection would have to land inside this window to be missed, and a
        local tmux paste lands in milliseconds.
        """
        last = self.captured()
        stable_since = time.monotonic()
        while time.monotonic() - stable_since < quiet:
            time.sleep(0.05)
            current = self.captured()
            if current != last:
                last = current
                stable_since = time.monotonic()
        return last

    def send(
        self,
        *args: str,
        payload: bytes = b"",
        path_prefix: Optional[Path] = None,
        timeout: float = 120.0,
    ) -> subprocess.CompletedProcess:
        """Run the REAL `pocketshell send` CLI as its own process."""
        env = dict(self.env)
        if path_prefix is not None:
            env["PATH"] = f"{path_prefix}{os.pathsep}{env['PATH']}"
        return subprocess.run(
            [sys.executable, "-m", "pocketshell", "send", "--socket-name", self.socket_name, *args],
            input=payload,
            env=env,
            capture_output=True,
            check=False,
            timeout=timeout,
        )

    def records(self) -> list[Path]:
        sends = self.state_dir / "pocketshell" / "sends"
        if not sends.is_dir():
            return []
        return sorted(sends.glob("*.json"))

    def record_for(self, token: str) -> Path:
        paths = send_mod.resolve_paths(env={"XDG_STATE_HOME": str(self.state_dir)})
        return paths.record_file(token)


@pytest.fixture
def tmux_fixture(tmp_path: Path):
    real_tmux = _require_tmux()

    # A unix socket path is capped near 108 bytes, and pytest's `tmp_path` is
    # already long, so TMUX_TMPDIR gets its own SHORT directory. It is still a
    # private directory, so the isolation guarantee is unchanged: the socket
    # can never be /tmp/tmux-$UID/default.
    tmux_tmpdir = Path(tempfile.mkdtemp(prefix="pssnd-"))
    state_dir = tmp_path / "state"
    state_dir.mkdir(parents=True, exist_ok=True)

    unique = uuid.uuid4().hex[:8]
    socket_name = f"pssnd-{unique}"
    session = f"ps-send-{unique}"
    capture_file = tmp_path / "pane-stdin.bin"
    ready_file = tmp_path / "pane-ready"

    env = dict(os.environ)
    env.pop("TMUX", None)  # never inherit an enclosing session
    env["TMUX_TMPDIR"] = str(tmux_tmpdir)
    env["XDG_STATE_HOME"] = str(state_dir)

    # `stty raw -echo` first, so the tty line discipline performs no LF/CR
    # translation and imposes no canonical-mode line-length cap; then a marker
    # file so the test never pastes into a still-cooked terminal; then a plain
    # byte pump into a file.
    pane_command = f"stty raw -echo; : > {ready_file}; cat > {capture_file}"
    created = subprocess.run(
        [
            real_tmux, "-L", socket_name,
            "new-session", "-d", "-s", session, "-x", "200", "-y", "50",
            "-P", "-F", "#{pane_id}",
            "sh", "-c", pane_command,
        ],
        env=env,
        capture_output=True,
        check=False,
    )
    assert created.returncode == 0, created.stderr.decode("utf-8", "replace")
    pane_id = created.stdout.decode().strip()
    assert pane_id.startswith("%"), f"unexpected pane id {pane_id!r}"

    # The socket must live under the test's own tmp dir, never /tmp/tmux-$UID.
    socket_path = tmux_tmpdir / f"tmux-{os.getuid()}" / socket_name
    deadline = time.monotonic() + 20.0
    while time.monotonic() < deadline and not ready_file.exists():
        time.sleep(0.05)
    assert ready_file.exists(), "capture pane never reached raw mode"
    assert socket_path.exists(), f"tmux socket not isolated under {tmux_tmpdir}"

    fixture = TmuxFixture(
        socket_name=socket_name,
        session=session,
        pane_id=pane_id,
        capture_file=capture_file,
        env=env,
        state_dir=state_dir,
        real_tmux=real_tmux,
    )
    try:
        yield fixture
    finally:
        # Kill ONLY the sessions this fixture created, by name. The server exits
        # by itself once its last session is gone. Never `kill-server`.
        listed = subprocess.run(
            [real_tmux, "-L", socket_name, "list-sessions", "-F", "#{session_name}"],
            env=env,
            capture_output=True,
            check=False,
        )
        for name in listed.stdout.decode("utf-8", "replace").split():
            if name.startswith("ps-send-"):
                subprocess.run(
                    [real_tmux, "-L", socket_name, "kill-session", "-t", name],
                    env=env,
                    capture_output=True,
                    check=False,
                )
        shutil.rmtree(tmux_tmpdir, ignore_errors=True)


def _shim_dir(tmp_path: Path, real_tmux: str, body: str, name: str) -> Path:
    """A PATH-shadowing `tmux` wrapper that forwards to the real binary.

    Used to inject a *real* failure (or a real SIGKILL of the real
    `pocketshell send` process) at a precise point in the injection sequence.
    The real tmux path is baked in, so the shim can never recurse into itself.
    """
    directory = tmp_path / f"shim-{name}"
    directory.mkdir(parents=True, exist_ok=True)
    script = directory / "tmux"
    script.write_text(
        "#!/bin/sh\n"
        f"REAL={real_tmux}\n"
        f"{body}\n"
        'exec "$REAL" "$@"\n',
        encoding="utf-8",
    )
    script.chmod(0o755)
    return directory


def _match_first_arg(command: str, action: str) -> str:
    """Shim body: run ``action`` when ``command`` appears in the argv."""
    return (
        'for a in "$@"; do\n'
        f'  if [ "$a" = "{command}" ]; then\n'
        f"{action}\n"
        "  fi\n"
        "done"
    )


# --------------------------------------------------------------------------
# AC: delivers a payload to a real tmux pane and exits 0 with `delivered`
# --------------------------------------------------------------------------


def test_send_delivers_payload_to_a_real_pane(tmux_fixture: TmuxFixture) -> None:
    payload = b"echo hello from pocketshell send\n"
    result = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", "row-deliver-1", payload=payload
    )
    assert result.returncode == 0, result.stderr.decode("utf-8", "replace")
    assert result.stdout.decode().split()[0] == "delivered"

    landed = tmux_fixture.wait_for_capture(len(payload))
    assert landed == payload, "the pane must receive the payload byte-exact"

    record = json.loads(tmux_fixture.record_for("row-deliver-1").read_text(encoding="utf-8"))
    assert record["state"] == "delivered"
    assert record["pane"] == tmux_fixture.pane_id
    assert record["token"] == "row-deliver-1"
    assert record["delivered_at"] > 0


def test_send_with_enter_presses_enter_after_the_payload(tmux_fixture: TmuxFixture) -> None:
    payload = b"pwd"
    result = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", "row-enter-1", "--enter", payload=payload
    )
    assert result.returncode == 0, result.stderr.decode("utf-8", "replace")

    landed = tmux_fixture.wait_for_capture(len(payload) + 1)
    assert landed[: len(payload)] == payload
    assert landed[len(payload) :] in (b"\r", b"\n"), (
        "--enter must put a submit byte in the pane after the payload; "
        f"got {landed[len(payload):]!r}"
    )


# --------------------------------------------------------------------------
# AC: same token twice => EXACTLY ONE occurrence in the pane, exit 0
#     `already-delivered`. Asserted on pane CONTENT, not the exit code.
# --------------------------------------------------------------------------


def test_same_token_twice_injects_exactly_once(tmux_fixture: TmuxFixture) -> None:
    marker = b"MARKER-2122-IDEMPOTENT"
    payload = b"first line\n" + marker + b"\n"
    token = "row-idempotent-1"

    first = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert first.returncode == 0, first.stderr.decode("utf-8", "replace")
    assert first.stdout.decode().split()[0] == "delivered"
    tmux_fixture.wait_for_capture(len(payload))

    second = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert second.returncode == 0, second.stderr.decode("utf-8", "replace")
    assert second.stdout.decode().split()[0] == "already-delivered"

    landed = tmux_fixture.settle()
    assert landed.count(marker) == 1, (
        "re-running with the same token must produce EXACTLY ONE occurrence in "
        f"the pane; found {landed.count(marker)}"
    )
    assert landed == payload, "the pane must hold the payload once and nothing else"


def test_third_and_fourth_replay_still_leave_exactly_one_occurrence(
    tmux_fixture: TmuxFixture,
) -> None:
    """A client retry loop is unbounded; idempotency must not be once-only."""
    marker = b"MARKER-2122-REPLAY"
    payload = marker + b"\n"
    token = "row-replay-1"

    for attempt in range(4):
        result = tmux_fixture.send(
            "--pane", tmux_fixture.pane_id, "--token", token, payload=payload
        )
        assert result.returncode == 0, result.stderr.decode("utf-8", "replace")
        expected = "delivered" if attempt == 0 else "already-delivered"
        assert result.stdout.decode().split()[0] == expected

    landed = tmux_fixture.settle()
    assert landed.count(marker) == 1
    assert landed == payload


def test_a_different_token_with_the_same_payload_delivers_again(
    tmux_fixture: TmuxFixture,
) -> None:
    """Idempotency is keyed on the TOKEN, not on the payload bytes."""
    marker = b"MARKER-2122-DISTINCT"
    payload = marker + b"\n"

    for token in ("row-distinct-a", "row-distinct-b"):
        result = tmux_fixture.send(
            "--pane", tmux_fixture.pane_id, "--token", token, payload=payload
        )
        assert result.returncode == 0, result.stderr.decode("utf-8", "replace")
        assert result.stdout.decode().split()[0] == "delivered"

    landed = tmux_fixture.wait_for_capture(len(payload) * 2)
    assert landed.count(marker) == 2
    assert landed == payload * 2


# --------------------------------------------------------------------------
# AC: a killed process between injection and journal write does not
#     double-inject on the next call. Real SIGKILL of the real CLI process at
#     the real boundary, via a PATH shim that kills its parent.
# --------------------------------------------------------------------------


def test_kill_after_injection_before_journal_never_double_injects(
    tmux_fixture: TmuxFixture, tmp_path: Path
) -> None:
    """The chosen invariant: AT-MOST-ONCE, with the ambiguity reported.

    The shim lets the real `paste-buffer` run (so the payload genuinely lands)
    and then SIGKILLs the `pocketshell send` process before it can promote the
    journal record to `delivered`. The next call with that token must NOT
    inject again — the pane keeps exactly one occurrence — and must say so with
    the documented `send-interrupted` exit code rather than inventing either
    "delivered" or "not delivered".
    """
    marker = b"MARKER-2122-KILLED-AFTER"
    payload = marker + b"\n"
    token = "row-killed-after"

    shim = _shim_dir(
        tmp_path,
        tmux_fixture.real_tmux,
        _match_first_arg(
            "paste-buffer",
            '    "$REAL" "$@"; rc=$?\n'
            '    kill -9 "$PPID"\n'
            "    exit $rc",
        ),
        "kill-after-paste",
    )

    killed = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, payload=payload, path_prefix=shim
    )
    assert killed.returncode == -9, (
        "the fixture must actually SIGKILL the send process at the ambiguity "
        f"boundary; got rc={killed.returncode}"
    )

    landed = tmux_fixture.wait_for_capture(len(payload))
    assert landed.count(marker) == 1, "the killed run must genuinely have injected once"

    record = json.loads(tmux_fixture.record_for(token).read_text(encoding="utf-8"))
    assert record["state"] == "pending", "the kill must leave the unresolved pending record"

    retry = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert retry.returncode == send_mod.EXIT_SEND_INTERRUPTED
    assert retry.stdout.decode().split()[0] == "send-interrupted"

    settled = tmux_fixture.settle()
    assert settled.count(marker) == 1, (
        "a retry after an interrupted send must NOT double-inject; found "
        f"{settled.count(marker)} occurrences"
    )
    assert settled == payload


def test_kill_before_injection_leaves_the_pane_untouched_and_is_recoverable(
    tmux_fixture: TmuxFixture, tmp_path: Path
) -> None:
    """The other half of the kill window: pending written, nothing injected.

    The default answer is still "unknown, not retried" — that is what
    at-most-once costs — and `--resend-interrupted` is the escape hatch that
    keeps the state from being absorbing (the #2121 defect).
    """
    marker = b"MARKER-2122-KILLED-BEFORE"
    payload = marker + b"\n"
    token = "row-killed-before"

    shim = _shim_dir(
        tmp_path,
        tmux_fixture.real_tmux,
        _match_first_arg("paste-buffer", '    kill -9 "$PPID"\n    exit 1'),
        "kill-before-paste",
    )

    killed = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, payload=payload, path_prefix=shim
    )
    assert killed.returncode == -9

    record = json.loads(tmux_fixture.record_for(token).read_text(encoding="utf-8"))
    assert record["state"] == "pending"
    assert tmux_fixture.settle(quiet=0.5) == b"", "nothing may have reached the pane"

    blocked = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert blocked.returncode == send_mod.EXIT_SEND_INTERRUPTED
    assert blocked.stdout.decode().split()[0] == "send-interrupted"
    assert tmux_fixture.settle(quiet=0.5) == b""

    recovered = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, "--resend-interrupted", payload=payload
    )
    assert recovered.returncode == 0, recovered.stderr.decode("utf-8", "replace")
    assert recovered.stdout.decode().split()[0] == "delivered"

    landed = tmux_fixture.wait_for_capture(len(payload))
    assert landed.count(marker) == 1
    assert landed == payload

    replay = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert replay.returncode == 0
    assert replay.stdout.decode().split()[0] == "already-delivered"
    assert tmux_fixture.settle().count(marker) == 1


def test_resend_interrupted_is_the_documented_opt_in_duplicate(
    tmux_fixture: TmuxFixture, tmp_path: Path
) -> None:
    """`--resend-interrupted` after a real injection CAN duplicate — on purpose.

    This is the documented cost of the escape hatch, pinned so the tradeoff is
    a decision rather than a surprise: the caller asked for at-least-once.
    """
    marker = b"MARKER-2122-OPTIN-DUP"
    payload = marker + b"\n"
    token = "row-optin-dup"

    shim = _shim_dir(
        tmp_path,
        tmux_fixture.real_tmux,
        _match_first_arg(
            "paste-buffer",
            '    "$REAL" "$@"; rc=$?\n    kill -9 "$PPID"\n    exit $rc',
        ),
        "kill-after-paste-dup",
    )
    killed = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, payload=payload, path_prefix=shim
    )
    assert killed.returncode == -9
    tmux_fixture.wait_for_capture(len(payload))

    forced = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, "--resend-interrupted", payload=payload
    )
    assert forced.returncode == 0, forced.stderr.decode("utf-8", "replace")
    assert forced.stdout.decode().split()[0] == "delivered"

    landed = tmux_fixture.wait_for_capture(len(payload) * 2)
    assert landed.count(marker) == 2, "the opt-in resend delivers a second time by design"

    # And the token is resolved again, so the loop terminates.
    after = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert after.returncode == 0
    assert after.stdout.decode().split()[0] == "already-delivered"
    assert tmux_fixture.settle().count(marker) == 2


def test_claim_record_exclusive_admits_exactly_one_writer(tmp_path: Path) -> None:
    """The mutual exclusion the concurrency guarantee rests on."""
    target = tmp_path / "sends" / "claim.json"
    document = {"schema": 1, "token": "t", "state": "pending", "created_at": 1.0}

    assert send_mod.claim_record_exclusive(target, document) is True
    assert send_mod.claim_record_exclusive(target, document) is False, (
        "a second claim on the same token must lose"
    )
    assert json.loads(target.read_text(encoding="utf-8"))["token"] == "t"
    assert oct(target.stat().st_mode & 0o777) == "0o600"


def test_concurrent_sends_with_the_same_token_inject_exactly_once(
    tmux_fixture: TmuxFixture,
) -> None:
    """Exactly-once must survive a RACING caller, not just a sequential retry.

    Five `pocketshell send` processes are launched simultaneously with one
    token. Exactly one may report `delivered`, and — the load-bearing check —
    the pane must hold exactly one copy of the payload.
    """
    marker = b"MARKER-2122-CONCURRENT"
    payload = marker + b"\n"
    token = "row-concurrent"

    processes = [
        subprocess.Popen(
            [
                sys.executable, "-m", "pocketshell", "send",
                "--socket-name", tmux_fixture.socket_name,
                "--pane", tmux_fixture.pane_id, "--token", token,
            ],
            stdin=subprocess.PIPE,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
            env=tmux_fixture.env,
        )
        for _ in range(5)
    ]
    outcomes = []
    for process in processes:
        stdout, _stderr = process.communicate(input=payload, timeout=120)
        outcomes.append((process.returncode, stdout.decode().split()[0] if stdout else ""))

    # The PANE is checked first and is the load-bearing assertion: an
    # exit-code-only check would still pass with a double-injection bug.
    landed = tmux_fixture.settle()
    assert landed.count(marker) == 1, (
        f"concurrent sends with one token must inject exactly once; found "
        f"{landed.count(marker)} occurrences (outcomes: {outcomes})"
    )
    assert landed == payload

    delivered = [outcome for outcome in outcomes if outcome[1] == "delivered"]
    assert len(delivered) == 1, f"exactly one racer may deliver; got {outcomes}"

    # Safety is not enough: the losers must also be told the TRUTH (#2122 F2).
    # A racer that lost the exclusive claim provably injected nothing, and the
    # winner is a healthy live sibling, so "a previous attempt died without an
    # answer, delivery is UNKNOWN" (exit 5) is a manufactured unknown — exactly
    # the absorbing state epic #2121 exists to delete. The honest answers are
    # "a live sibling owns this" or "it is already delivered".
    reasons = [reason for _code, reason in outcomes]
    assert "send-interrupted" not in reasons, (
        "a racer that lost to a LIVE sibling injected nothing and must not be "
        f"told its delivery is unknown; got {outcomes}"
    )
    for code, reason in outcomes:
        assert reason in {"delivered", "already-delivered", "send-in-progress"}, outcomes
        assert code in {0, send_mod.EXIT_SEND_IN_PROGRESS}, outcomes


# --------------------------------------------------------------------------
# AC: pane-not-found and tmux-failure exit non-zero with the documented reason
#     and do NOT journal the token.
# --------------------------------------------------------------------------


def test_pane_not_found_exits_three_and_journals_nothing(tmux_fixture: TmuxFixture) -> None:
    token = "row-nopane"
    result = tmux_fixture.send("--pane", "%9999", "--token", token, payload=b"never lands\n")
    assert result.returncode == send_mod.EXIT_PANE_NOT_FOUND
    assert result.stdout.decode().split()[0] == "pane-not-found"
    assert not tmux_fixture.record_for(token).exists(), "a pane that does not exist must not journal"
    assert tmux_fixture.records() == []
    assert tmux_fixture.settle(quiet=0.5) == b""

    # ...and the token is still cleanly retryable afterwards.
    retry = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, payload=b"lands now\n"
    )
    assert retry.returncode == 0
    assert retry.stdout.decode().split()[0] == "delivered"


def test_dead_pane_exits_three_and_journals_nothing(tmux_fixture: TmuxFixture) -> None:
    assert tmux_fixture.tmux("set-option", "-w", "-g", "remain-on-exit", "on").returncode == 0
    created = tmux_fixture.tmux(
        "new-window", "-d", "-P", "-F", "#{pane_id}", "-t", tmux_fixture.session,
        "sh", "-c", "exit 0",
    )
    assert created.returncode == 0, created.stderr.decode("utf-8", "replace")
    dead_pane = created.stdout.decode().strip()

    deadline = time.monotonic() + 20.0
    while time.monotonic() < deadline:
        listing = tmux_fixture.tmux("list-panes", "-a", "-F", "#{pane_id} #{pane_dead}")
        if f"{dead_pane} 1" in listing.stdout.decode():
            break
        time.sleep(0.05)
    else:
        raise AssertionError(f"pane {dead_pane} never became dead")

    token = "row-deadpane"
    result = tmux_fixture.send("--pane", dead_pane, "--token", token, payload=b"nope\n")
    assert result.returncode == send_mod.EXIT_PANE_NOT_FOUND
    assert result.stdout.decode().split()[0] == "pane-not-found"
    assert not tmux_fixture.record_for(token).exists()


def test_no_tmux_server_exits_four_and_journals_nothing(tmp_path: Path) -> None:
    _require_tmux()
    tmux_tmpdir = Path(tempfile.mkdtemp(prefix="pssnd-"))
    state_dir = tmp_path / "state"
    state_dir.mkdir()
    env = dict(os.environ)
    env.pop("TMUX", None)
    env["TMUX_TMPDIR"] = str(tmux_tmpdir)
    env["XDG_STATE_HOME"] = str(state_dir)

    try:
        result = subprocess.run(
            [
                sys.executable, "-m", "pocketshell", "send",
                "--socket-name", f"pssnd-absent-{uuid.uuid4().hex[:8]}",
                "--pane", "%0", "--token", "row-noserver",
            ],
            input=b"nothing\n",
            env=env,
            capture_output=True,
            check=False,
            timeout=120.0,
        )
    finally:
        shutil.rmtree(tmux_tmpdir, ignore_errors=True)
    assert result.returncode == send_mod.EXIT_TMUX_FAILED
    assert result.stdout.decode().split()[0] == "tmux-failed"
    assert list((state_dir / "pocketshell" / "sends").glob("*.json")) == []


def test_load_buffer_failure_exits_four_and_journals_nothing(
    tmux_fixture: TmuxFixture, tmp_path: Path
) -> None:
    shim = _shim_dir(
        tmux_fixture.capture_file.parent,
        tmux_fixture.real_tmux,
        _match_first_arg(
            "load-buffer", '    echo "simulated load-buffer failure" >&2\n    exit 1'
        ),
        "fail-load-buffer",
    )
    token = "row-loadfail"
    result = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, payload=b"x\n", path_prefix=shim
    )
    assert result.returncode == send_mod.EXIT_TMUX_FAILED
    assert result.stdout.decode().split()[0] == "tmux-failed"
    assert not tmux_fixture.record_for(token).exists()
    assert tmux_fixture.settle(quiet=0.5) == b""


def test_paste_buffer_definitive_failure_rolls_the_pending_record_back(
    tmux_fixture: TmuxFixture, tmp_path: Path
) -> None:
    """A tmux answer of "no" means nothing reached the pane => stay retryable.

    This is what separates a definitive failure from the ambiguous kill: the
    pending record is written before the paste and MUST be rolled back here, or
    a transient tmux error would poison the token forever.
    """
    shim = _shim_dir(
        tmux_fixture.capture_file.parent,
        tmux_fixture.real_tmux,
        _match_first_arg(
            "paste-buffer", '    echo "simulated paste-buffer failure" >&2\n    exit 1'
        ),
        "fail-paste-buffer",
    )
    token = "row-pastefail"
    result = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, payload=b"x\n", path_prefix=shim
    )
    assert result.returncode == send_mod.EXIT_TMUX_FAILED
    assert result.stdout.decode().split()[0] == "tmux-failed"
    assert not tmux_fixture.record_for(token).exists(), (
        "a definitive paste failure must roll the pending record back so the "
        "token stays cleanly retryable"
    )
    assert tmux_fixture.settle(quiet=0.5) == b""

    retry = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=b"x\n")
    assert retry.returncode == 0
    assert retry.stdout.decode().split()[0] == "delivered"
    assert tmux_fixture.wait_for_capture(2) == b"x\n"


def test_enter_failure_after_a_landed_payload_reports_the_unknown_state(
    tmux_fixture: TmuxFixture,
) -> None:
    """The payload landed but was not submitted: partial, therefore unknown.

    Rolling back here would let a retry paste the payload a SECOND time, so the
    record deliberately stays pending and the caller is told.
    """
    shim = _shim_dir(
        tmux_fixture.capture_file.parent,
        tmux_fixture.real_tmux,
        _match_first_arg("send-keys", '    echo "simulated send-keys failure" >&2\n    exit 1'),
        "fail-send-keys",
    )
    token = "row-enterfail"
    payload = b"MARKER-2122-ENTERFAIL\n"
    result = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, "--enter",
        payload=payload, path_prefix=shim,
    )
    assert result.returncode == send_mod.EXIT_SEND_INTERRUPTED
    assert result.stdout.decode().split()[0] == "send-interrupted"

    landed = tmux_fixture.wait_for_capture(len(payload))
    assert landed == payload
    record = json.loads(tmux_fixture.record_for(token).read_text(encoding="utf-8"))
    assert record["state"] == "pending"

    retry = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert retry.returncode == send_mod.EXIT_SEND_INTERRUPTED
    assert tmux_fixture.settle().count(b"MARKER-2122-ENTERFAIL") == 1


def test_timeout_exits_six_without_journaling(tmux_fixture: TmuxFixture) -> None:
    shim = _shim_dir(
        tmux_fixture.capture_file.parent,
        tmux_fixture.real_tmux,
        _match_first_arg("list-panes", "    sleep 30\n    exit 0"),
        "slow-list-panes",
    )
    token = "row-timeout"
    result = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, "--timeout", "1",
        payload=b"x\n", path_prefix=shim,
    )
    assert result.returncode == send_mod.EXIT_TIMEOUT
    assert result.stdout.decode().split()[0] == "timeout"
    assert not tmux_fixture.record_for(token).exists()
    assert tmux_fixture.settle(quiet=0.5) == b""


# --------------------------------------------------------------------------
# AC: payload fidelity — multi-line, trailing whitespace, unicode, >64 KB
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("label", "payload"),
    [
        ("multiline", b"first line\nsecond line\n\nfourth after a blank\n"),
        ("trailing-whitespace", b"body with trailing spaces   \nand a trailing tab\t"),
        ("crlf-and-lone-cr", b"windows\r\nline\rand more\n"),
        ("unicode", "ünïcødé — «кавычки» — 🚀🙂\nвторая строка\n".encode("utf-8")),
        ("control-bytes", b"esc\x1b[31mred\x1b[0m and bell\x07 and tab\t\n"),
        ("large-64k-plus", b"A" * 70000 + b"\nTAIL-MARKER\n"),
        ("no-trailing-newline", b"exactly this and nothing more"),
        ("single-byte", b"x"),
    ],
)
def test_payload_arrives_byte_exact(
    tmux_fixture: TmuxFixture, label: str, payload: bytes
) -> None:
    result = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", f"row-fidelity-{label}", payload=payload
    )
    assert result.returncode == 0, result.stderr.decode("utf-8", "replace")

    landed = tmux_fixture.wait_for_capture(len(payload))
    assert landed == payload, (
        f"payload class {label!r} was mangled in transit: "
        f"expected {len(payload)} bytes, got {len(landed)}"
    )


def test_large_payload_is_not_truncated_or_reordered(tmux_fixture: TmuxFixture) -> None:
    """A >64 KB payload with position-encoded content, so a reorder is visible."""
    chunks = [f"[{index:05d}]".encode() for index in range(12000)]
    payload = b"".join(chunks) + b"\n"
    assert len(payload) > 64 * 1024

    result = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", "row-large-ordered", payload=payload
    )
    assert result.returncode == 0, result.stderr.decode("utf-8", "replace")

    landed = tmux_fixture.wait_for_capture(len(payload))
    assert landed == payload


# --------------------------------------------------------------------------
# AC: journal writes are atomic; a truncated/corrupt entry does not crash
# --------------------------------------------------------------------------


def test_write_record_atomic_is_private_and_leaves_no_temp(tmp_path: Path) -> None:
    target = tmp_path / "sends" / "record.json"
    send_mod.write_record_atomic(target, {"schema": 1, "token": "t", "state": "delivered"})
    assert json.loads(target.read_text(encoding="utf-8"))["token"] == "t"
    assert oct(target.stat().st_mode & 0o777) == "0o600"
    assert list(target.parent.glob("*.tmp")) == []


def test_write_record_atomic_never_publishes_a_partial_file(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """If the rename fails, readers must see nothing rather than a partial."""
    target = tmp_path / "sends" / "record.json"
    target.parent.mkdir(parents=True)

    def _boom(src, dst):
        raise OSError("simulated rename failure")

    monkeypatch.setattr(send_mod.os, "replace", _boom)
    with pytest.raises(OSError):
        send_mod.write_record_atomic(target, {"schema": 1, "token": "t", "state": "pending"})

    assert not target.exists(), "a failed write must not publish the record"
    assert list(target.parent.glob("*.tmp")) == [], "a failed write must not leave temp litter"


def test_write_record_atomic_replaces_an_existing_record_in_place(tmp_path: Path) -> None:
    target = tmp_path / "sends" / "record.json"
    send_mod.write_record_atomic(target, {"schema": 1, "token": "t", "state": "pending"})
    send_mod.write_record_atomic(
        target, {"schema": 1, "token": "t", "state": "delivered", "delivered_at": 5.0}
    )
    document = json.loads(target.read_text(encoding="utf-8"))
    assert document["state"] == "delivered"
    assert list(target.parent.glob("*.tmp")) == []


@pytest.mark.parametrize(
    ("label", "content"),
    [
        ("truncated-json", b'{"schema": 1, "token": "tok", "sta'),
        ("empty", b""),
        ("not-json", b"\x00\x01\x02 not json at all"),
        ("json-but-a-list", b"[1, 2, 3]"),
        ("missing-state", b'{"schema": 1, "token": "tok", "created_at": 1.0}'),
        ("unknown-state", b'{"schema":1,"token":"tok","state":"weird","created_at":1.0}'),
        ("wrong-token", b'{"schema":1,"token":"other","state":"delivered",'
                        b'"created_at":1.0,"delivered_at":2.0}'),
        ("delivered-without-timestamp", b'{"schema":1,"token":"tok","state":"delivered",'
                                        b'"created_at":1.0}'),
        ("invalid-utf8", b"\xff\xfe\xfd"),
    ],
)
def test_read_record_reports_corrupt_instead_of_raising(
    tmp_path: Path, label: str, content: bytes
) -> None:
    target = tmp_path / "record.json"
    target.write_bytes(content)
    record = send_mod.read_record(target, "tok")
    assert record.state == send_mod.STATE_CORRUPT, label


def test_read_record_reports_absent_for_a_missing_file(tmp_path: Path) -> None:
    assert send_mod.read_record(tmp_path / "nope.json", "tok").state == send_mod.STATE_ABSENT


def test_a_corrupt_record_does_not_crash_the_next_call(tmux_fixture: TmuxFixture) -> None:
    """A truncated record must yield a clean documented exit, never a traceback."""
    token = "row-corrupt"
    record_path = tmux_fixture.record_for(token)
    record_path.parent.mkdir(parents=True, exist_ok=True)
    record_path.write_bytes(b'{"schema": 1, "token": "row-corr')

    payload = b"MARKER-2122-CORRUPT\n"
    result = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, payload=payload
    )
    assert result.returncode == send_mod.EXIT_SEND_INTERRUPTED
    assert result.stdout.decode().split()[0] == "journal-corrupt"
    assert "Traceback" not in result.stderr.decode("utf-8", "replace")
    assert tmux_fixture.settle(quiet=0.5) == b"", "a corrupt record must not trigger an injection"

    recovered = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, "--resend-interrupted", payload=payload
    )
    assert recovered.returncode == 0
    assert recovered.stdout.decode().split()[0] == "delivered"
    landed = tmux_fixture.wait_for_capture(len(payload))
    assert landed.count(b"MARKER-2122-CORRUPT") == 1


# --------------------------------------------------------------------------
# AC: retention / pruning works and is documented
# --------------------------------------------------------------------------


def _seed_record(paths: send_mod.SendPaths, token: str, *, age_secs: float, now: float) -> Path:
    path = paths.record_file(token)
    send_mod.write_record_atomic(
        path,
        {
            "schema": 1,
            "token": token,
            "pane": "%0",
            "state": "delivered",
            "created_at": now - age_secs,
            "delivered_at": now - age_secs,
            "payload_bytes": 3,
            "enter": True,
        },
    )
    return path


def test_prune_removes_only_records_older_than_the_cutoff(tmp_path: Path) -> None:
    paths = send_mod.SendPaths(sends_dir=tmp_path / "sends")
    now = time.time()
    old = _seed_record(paths, "old", age_secs=10 * 86400, now=now)
    edge = _seed_record(paths, "edge", age_secs=2 * 86400, now=now)
    fresh = _seed_record(paths, "fresh", age_secs=60, now=now)

    removed = send_mod.prune(paths, older_than_secs=send_mod.parse_duration("1d"), now=now)
    assert removed == 2
    assert not old.exists()
    assert not edge.exists()
    assert fresh.exists()


def test_prune_ages_a_corrupt_record_by_its_mtime(tmp_path: Path) -> None:
    """A corrupt record must be prunable, not immortal."""
    paths = send_mod.SendPaths(sends_dir=tmp_path / "sends")
    paths.sends_dir.mkdir(parents=True)
    corrupt = paths.sends_dir / "deadbeef.json"
    corrupt.write_bytes(b"not json")
    now = time.time()
    os.utime(corrupt, (now - 10 * 86400, now - 10 * 86400))

    assert send_mod.prune(paths, older_than_secs=86400, now=now) == 1
    assert not corrupt.exists()


def test_prune_cli_reports_the_count_and_exits_zero(tmp_path: Path) -> None:
    state_dir = tmp_path / "state"
    paths = send_mod.resolve_paths(env={"XDG_STATE_HOME": str(state_dir)})
    now = time.time()
    _seed_record(paths, "old-a", age_secs=40 * 86400, now=now)
    _seed_record(paths, "old-b", age_secs=40 * 86400, now=now)
    _seed_record(paths, "keep", age_secs=60, now=now)

    runner = CliRunner()
    result = runner.invoke(
        cli, ["send", "--prune-older-than", "30d"], env={"XDG_STATE_HOME": str(state_dir)}
    )
    assert result.exit_code == 0, result.output
    assert result.output.strip() == "pruned 2"
    assert len(list(paths.sends_dir.glob("*.json"))) == 1


def test_default_retention_is_thirty_days() -> None:
    assert send_mod.DEFAULT_RETENTION_SECS == 30 * 24 * 60 * 60


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("30d", 30 * 86400),
        ("12h", 12 * 3600),
        ("90m", 5400),
        ("45s", 45),
        ("3600", 3600),
        ("1w", 604800),
        (" 2d ", 2 * 86400),
        ("1.5h", 5400),
    ],
)
def test_parse_duration_accepts_the_documented_forms(text: str, expected: float) -> None:
    assert send_mod.parse_duration(text) == expected


@pytest.mark.parametrize("text", ["", "abc", "10x", "-5d", "d", "1 2d"])
def test_parse_duration_rejects_garbage(text: str) -> None:
    with pytest.raises(ValueError):
        send_mod.parse_duration(text)


def test_auto_prune_keeps_the_journal_bounded_without_an_explicit_call(
    tmux_fixture: TmuxFixture,
) -> None:
    """Delivery itself prunes at the default retention, so the dir cannot grow
    without bound even if `--prune-older-than` is never invoked."""
    paths = send_mod.resolve_paths(env={"XDG_STATE_HOME": str(tmux_fixture.state_dir)})
    now = time.time()
    ancient = _seed_record(paths, "ancient-token", age_secs=100 * 86400, now=now)
    recent = _seed_record(paths, "recent-token", age_secs=3600, now=now)
    assert ancient.exists()

    result = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", "row-autoprune", payload=b"y\n"
    )
    assert result.returncode == 0, result.stderr.decode("utf-8", "replace")

    assert not ancient.exists(), "delivery must auto-prune records past the default retention"
    assert recent.exists(), "delivery must not prune records inside the retention window"
    assert paths.prune_marker.exists()


def test_auto_prune_is_throttled_by_its_marker(tmp_path: Path) -> None:
    paths = send_mod.SendPaths(sends_dir=tmp_path / "sends")
    now = time.time()
    ancient = _seed_record(paths, "ancient", age_secs=100 * 86400, now=now)
    paths.prune_marker.write_text("", encoding="utf-8")
    os.utime(paths.prune_marker, (now - 60, now - 60))

    assert send_mod.maybe_auto_prune(paths, now=now) is False
    assert ancient.exists(), "a fresh marker must suppress the walk"

    os.utime(
        paths.prune_marker,
        (now - send_mod.AUTO_PRUNE_INTERVAL_SECS - 1, now - send_mod.AUTO_PRUNE_INTERVAL_SECS - 1),
    )
    assert send_mod.maybe_auto_prune(paths, now=now) is True
    assert not ancient.exists()


def test_auto_prune_never_raises_on_a_broken_journal_dir(tmp_path: Path) -> None:
    """Housekeeping must never turn a successful delivery into a failure."""
    blocked = tmp_path / "not-a-dir"
    blocked.write_text("i am a file", encoding="utf-8")
    paths = send_mod.SendPaths(sends_dir=blocked / "sends")
    assert send_mod.maybe_auto_prune(paths) is False


# --------------------------------------------------------------------------
# AC: `--help` documents every exit code
# --------------------------------------------------------------------------


def test_help_documents_every_exit_code_and_reason() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["send", "--help"])
    assert result.exit_code == 0
    help_text = result.output

    for code, reasons, _description in send_mod.EXIT_CODE_TABLE:
        assert f"  {code}  " in help_text, f"exit code {code} is not documented in --help"
        for reason in (part.strip() for part in reasons.split("|")):
            assert reason in help_text, f"reason {reason!r} is not documented in --help"


def test_help_documents_the_durability_invariant_and_the_escape_hatch() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["send", "--help"])
    assert "at-most-once" in result.output
    assert "--resend-interrupted" in result.output
    assert "--prune-older-than" in result.output


def test_every_reason_constant_appears_in_the_documented_table() -> None:
    """No exit path may print a reason the table does not document."""
    documented = {
        part.strip()
        for _code, reasons, _description in send_mod.EXIT_CODE_TABLE
        for part in reasons.split("|")
    }
    for name, value in vars(send_mod).items():
        if name.startswith("REASON_"):
            assert value in documented, f"{name} = {value!r} is missing from EXIT_CODE_TABLE"


def test_send_is_registered_on_the_top_level_cli() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["--help"])
    assert result.exit_code == 0
    assert "send" in result.output


# --------------------------------------------------------------------------
# Usage errors
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("label", "args"),
    [
        ("no-pane", ["--token", "t"]),
        ("no-token", ["--pane", "%0"]),
        ("blank-token", ["--pane", "%0", "--token", "   "]),
        ("control-char-token", ["--pane", "%0", "--token", "a\nb"]),
        ("oversized-token", ["--pane", "%0", "--token", "z" * 513]),
        ("prune-with-pane", ["--pane", "%0", "--prune-older-than", "1d"]),
        ("prune-with-token", ["--token", "t", "--prune-older-than", "1d"]),
        ("bad-duration", ["--prune-older-than", "banana"]),
        ("non-positive-timeout", ["--pane", "%0", "--token", "t", "--timeout", "0"]),
    ],
)
def test_bad_usage_exits_two_with_the_documented_reason(
    tmux_fixture: TmuxFixture, label: str, args: Sequence[str]
) -> None:
    result = tmux_fixture.send(*args, payload=b"x\n")
    assert result.returncode == send_mod.EXIT_BAD_USAGE, label
    assert result.stdout.decode().split()[0] == "bad-usage", label
    assert tmux_fixture.records() == [], label


def test_empty_payload_without_enter_is_rejected(tmux_fixture: TmuxFixture) -> None:
    result = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", "row-empty", payload=b""
    )
    assert result.returncode == send_mod.EXIT_BAD_USAGE
    assert result.stdout.decode().split()[0] == "bad-usage"
    assert tmux_fixture.records() == []


def test_empty_payload_with_enter_submits_a_bare_enter(tmux_fixture: TmuxFixture) -> None:
    result = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", "row-bare-enter", "--enter", payload=b""
    )
    assert result.returncode == 0, result.stderr.decode("utf-8", "replace")
    landed = tmux_fixture.wait_for_capture(1)
    assert landed in (b"\r", b"\n")


# --------------------------------------------------------------------------
# Path resolution + token hashing
# --------------------------------------------------------------------------


def test_resolve_paths_prefers_xdg_state(tmp_path: Path) -> None:
    paths = send_mod.resolve_paths(env={"XDG_STATE_HOME": str(tmp_path / "xdg")})
    assert paths.sends_dir == tmp_path / "xdg" / "pocketshell" / "sends"


def test_resolve_paths_falls_back_to_local_state(tmp_path: Path) -> None:
    paths = send_mod.resolve_paths(home=tmp_path, env={})
    assert paths.sends_dir == tmp_path / ".local" / "state" / "pocketshell" / "sends"


@pytest.mark.parametrize(
    "token",
    ["../../etc/passwd", "a/b/c", "token with spaces", "🚀-emoji", "tok:with:colons", "."],
)
def test_a_hostile_token_never_escapes_the_journal_directory(tmp_path: Path, token: str) -> None:
    """The token is opaque, so it is HASHED into a filename, never used as one."""
    paths = send_mod.SendPaths(sends_dir=tmp_path / "sends")
    record = paths.record_file(token)
    assert record.parent == paths.sends_dir
    assert record.name.endswith(".json")
    assert "/" not in record.name and ".." not in record.name


def test_distinct_tokens_get_distinct_records(tmp_path: Path) -> None:
    paths = send_mod.SendPaths(sends_dir=tmp_path / "sends")
    assert paths.record_file("a") != paths.record_file("b")
    assert paths.record_file("a") == paths.record_file("a")


def test_buffer_name_is_alphanumeric_and_per_token() -> None:
    name = send_mod.buffer_name_for("row-1;drop")
    assert name.isalnum(), "tmux treats a trailing ';' as a command separator (#1845)"
    assert send_mod.buffer_name_for("row-1;drop") == name
    assert send_mod.buffer_name_for("other") != name


# ==========================================================================
# Round-2 review findings (#2122). Each test below reproduces a defect the
# reviewer drove end to end against a real tmux server, and each was RED on
# the round-1 implementation before the fix.
# ==========================================================================

# --------------------------------------------------------------------------
# F1 — a definitive tmux failure must not ERASE a pre-existing "delivery
#      unknown" memory. Rolling back a claim this call created is correct;
#      deleting somebody else's unresolved record lets the very next PLAIN
#      call inject a second copy with no opt-in, which falsifies the headline
#      at-most-once claim.
# --------------------------------------------------------------------------


def _kill_after_paste_shim(tmux_fixture: TmuxFixture, tmp_path: Path, name: str) -> Path:
    """A shim that lets the real paste run and then SIGKILLs `pocketshell send`."""
    return _shim_dir(
        tmp_path,
        tmux_fixture.real_tmux,
        _match_first_arg(
            "paste-buffer",
            '    "$REAL" "$@"; rc=$?\n    kill -9 "$PPID"\n    exit $rc',
        ),
        name,
    )


def _failing_paste_shim(tmux_fixture: TmuxFixture, tmp_path: Path, name: str) -> Path:
    return _shim_dir(
        tmp_path,
        tmux_fixture.real_tmux,
        _match_first_arg("paste-buffer", '    echo "simulated paste failure" >&2\n    exit 1'),
        name,
    )


def test_a_definitive_failure_after_resend_interrupted_restores_the_prior_unknown(
    tmux_fixture: TmuxFixture, tmp_path: Path
) -> None:
    """#2122 F1: the reviewer's exact four-step journey, end to end.

    kill-after-paste (payload landed, record `pending`) -> plain retry says
    `send-interrupted` -> the user opts in with `--resend-interrupted` and tmux
    fails definitively -> a LATER PLAIN call must still refuse. On the round-1
    code the rollback unlinked the record it had overwritten, the token read
    `absent` again, and step four delivered a SECOND copy with no opt-in.
    """
    marker = b"MARKER-2122-F1-PENDING"
    payload = marker + b"\n"
    token = "row-f1-pending"
    record_path = tmux_fixture.record_for(token)

    killed = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token,
        payload=payload,
        path_prefix=_kill_after_paste_shim(tmux_fixture, tmp_path, "f1-kill-after"),
    )
    assert killed.returncode == -9
    tmux_fixture.wait_for_capture(len(payload))
    assert json.loads(record_path.read_text(encoding="utf-8"))["state"] == "pending"

    forced = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, "--resend-interrupted",
        payload=payload,
        path_prefix=_failing_paste_shim(tmux_fixture, tmp_path, "f1-fail-paste"),
    )
    assert forced.returncode == send_mod.EXIT_TMUX_FAILED
    assert forced.stdout.decode().split()[0] == "tmux-failed"
    assert record_path.exists(), (
        "a definitive failure on an opt-in resend must RESTORE the unresolved "
        "record it overwrote, not erase the fact that delivery is unknown"
    )
    assert json.loads(record_path.read_text(encoding="utf-8"))["state"] == "pending"

    plain = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert plain.stdout.decode().split()[0] == "send-interrupted", (
        "the next PLAIN call must still refuse; if it delivers, the token has "
        "been injected twice with no --resend-interrupted opt-in"
    )
    assert plain.returncode == send_mod.EXIT_SEND_INTERRUPTED

    settled = tmux_fixture.settle()
    assert settled.count(marker) == 1, (
        f"at-most-once violated: the pane holds {settled.count(marker)} copies"
    )


def test_a_definitive_failure_after_resend_interrupted_restores_a_prior_corrupt_record(
    tmux_fixture: TmuxFixture, tmp_path: Path
) -> None:
    """The same class, other state: a CORRUPT record is unresolved too.

    "We cannot read what happened for this token" is an unknown exactly like a
    pending record, so erasing it is the same falsification.
    """
    marker = b"MARKER-2122-F1-CORRUPT"
    payload = marker + b"\n"
    token = "row-f1-corrupt"
    record_path = tmux_fixture.record_for(token)
    record_path.parent.mkdir(parents=True, exist_ok=True)
    planted = b'{"schema": 1, "token": "row-f1-cor'
    record_path.write_bytes(planted)

    forced = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, "--resend-interrupted",
        payload=payload,
        path_prefix=_failing_paste_shim(tmux_fixture, tmp_path, "f1-fail-paste-corrupt"),
    )
    assert forced.returncode == send_mod.EXIT_TMUX_FAILED
    assert record_path.exists(), "the corrupt (= unresolved) record must survive the rollback"
    assert record_path.read_bytes() == planted, (
        "the prior document must be restored byte-for-byte, so the next call "
        "sees exactly the state it would have seen without this attempt"
    )

    plain = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert plain.stdout.decode().split()[0] == "journal-corrupt"
    assert plain.returncode == send_mod.EXIT_SEND_INTERRUPTED
    assert tmux_fixture.settle(quiet=0.5).count(marker) == 0


def test_a_load_buffer_failure_after_resend_interrupted_leaves_the_prior_unknown(
    tmux_fixture: TmuxFixture, tmp_path: Path
) -> None:
    """The other definitive-failure site: it happens BEFORE the claim is taken.

    Nothing has been overwritten yet, so the prior unresolved record must be
    untouched — same user-visible outcome, different mechanism.
    """
    marker = b"MARKER-2122-F1-LOADFAIL"
    payload = marker + b"\n"
    token = "row-f1-loadfail"
    record_path = tmux_fixture.record_for(token)

    killed = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token,
        payload=payload,
        path_prefix=_kill_after_paste_shim(tmux_fixture, tmp_path, "f1-kill-after-load"),
    )
    assert killed.returncode == -9
    tmux_fixture.wait_for_capture(len(payload))
    before = record_path.read_bytes()

    shim = _shim_dir(
        tmp_path,
        tmux_fixture.real_tmux,
        _match_first_arg("load-buffer", '    echo "simulated load failure" >&2\n    exit 1'),
        "f1-fail-load",
    )
    forced = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, "--resend-interrupted",
        payload=payload, path_prefix=shim,
    )
    assert forced.returncode == send_mod.EXIT_TMUX_FAILED
    assert record_path.read_bytes() == before, "the prior unknown must be untouched"

    plain = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert plain.stdout.decode().split()[0] == "send-interrupted"
    assert tmux_fixture.settle().count(marker) == 1


def test_roll_back_claim_removes_a_record_this_call_created(tmp_path: Path) -> None:
    """The mechanism, directly: no prior document => the claim is ours to drop."""
    target = tmp_path / "sends" / "record.json"
    send_mod.write_record_atomic(target, {"schema": 1, "token": "t", "state": "pending"})
    send_mod.roll_back_claim(target, None)
    assert not target.exists()


def test_roll_back_claim_restores_the_prior_document_byte_for_byte(tmp_path: Path) -> None:
    """The mechanism, directly: a prior document is restored, never deleted.

    Both rollback call sites (a definitive `paste-buffer` failure and a tmux
    binary that vanished mid-run) go through this one function, so this pins
    the branch the end-to-end tests cannot reach.
    """
    target = tmp_path / "sends" / "record.json"
    prior = b'{"schema": 1, "state": "pending", "token": "t", "created_at": 1.0}'
    target.parent.mkdir(parents=True)
    target.write_bytes(prior)
    send_mod.write_record_atomic(target, {"schema": 1, "token": "t", "state": "pending"})
    assert target.read_bytes() != prior

    send_mod.roll_back_claim(target, prior)
    assert target.read_bytes() == prior
    assert list(target.parent.glob("*.tmp")) == []


def test_at_most_once_survives_interleaved_kills_failures_and_retries(
    tmux_fixture: TmuxFixture, tmp_path: Path
) -> None:
    """The whole invariant, in one messy journey rather than one happy step.

    Every kill window and both retry kinds, interleaved: kill-before-paste,
    plain retry, opt-in resend that fails definitively, plain retry, opt-in
    resend that succeeds, plain replay. The load-bearing assertion is the copy
    count after each stage, and the rule under test is that a copy only ever
    appears on a call that passed --resend-interrupted (or the first delivery).
    """
    marker = b"MARKER-2122-INTERLEAVED"
    payload = marker + b"\n"
    token = "row-interleaved"

    kill_before = _shim_dir(
        tmp_path,
        tmux_fixture.real_tmux,
        _match_first_arg("paste-buffer", '    kill -9 "$PPID"\n    exit 1'),
        "mix-kill-before",
    )
    stage1 = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, payload=payload, path_prefix=kill_before
    )
    assert stage1.returncode == -9
    assert tmux_fixture.settle(quiet=0.5).count(marker) == 0

    stage2 = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert stage2.stdout.decode().split()[0] == "send-interrupted"
    assert tmux_fixture.settle(quiet=0.5).count(marker) == 0

    stage3 = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, "--resend-interrupted",
        payload=payload,
        path_prefix=_failing_paste_shim(tmux_fixture, tmp_path, "mix-fail-paste"),
    )
    assert stage3.returncode == send_mod.EXIT_TMUX_FAILED
    assert tmux_fixture.settle(quiet=0.5).count(marker) == 0

    stage4 = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert stage4.stdout.decode().split()[0] == "send-interrupted", (
        "the failed opt-in resend must not have erased the unknown"
    )
    assert tmux_fixture.settle(quiet=0.5).count(marker) == 0

    stage5 = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, "--resend-interrupted", payload=payload
    )
    assert stage5.returncode == 0
    assert stage5.stdout.decode().split()[0] == "delivered"
    assert tmux_fixture.wait_for_capture(len(payload)).count(marker) == 1

    stage6 = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert stage6.stdout.decode().split()[0] == "already-delivered"
    assert tmux_fixture.settle().count(marker) == 1

    # And the OTHER kill window on a fresh token, in the same pane: the payload
    # really landed, so the plain retry must refuse and the count must hold.
    second_token = "row-interleaved-after"
    # Deliberately NOT a superstring of `marker`: bytes.count would then
    # match the first marker inside the second and mask a real duplicate.
    second_marker = b"MARKER-2122-SECOND-WINDOW"
    second_payload = second_marker + b"\n"
    killed = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", second_token,
        payload=second_payload,
        path_prefix=_kill_after_paste_shim(tmux_fixture, tmp_path, "mix-kill-after"),
    )
    assert killed.returncode == -9
    tmux_fixture.wait_for_capture(len(payload) + len(second_payload))
    retry = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", second_token, payload=second_payload
    )
    assert retry.stdout.decode().split()[0] == "send-interrupted"

    final = tmux_fixture.settle()
    assert final.count(marker) == 1
    assert final.count(second_marker) == 1


# --------------------------------------------------------------------------
# F2 — a LIVE sibling is not an unknown. Exit 5 documents "a previous attempt
#      DIED without an answer"; reporting it while a healthy sibling is
#      mid-flight recreates the spurious-unknown class #2121 exists to delete.
# --------------------------------------------------------------------------


def _start_slow_owner(
    tmux_fixture: TmuxFixture, token: str, payload: bytes, env: dict
) -> subprocess.Popen:
    """Launch a `pocketshell send` that will sit inside `paste-buffer`.

    The payload is written and stdin closed immediately so the child can make
    progress while the test probes it — but `Popen.stdin` is then dropped,
    because `communicate()` on Python 3.11 (the version CI runs) flushes
    `self.stdin` unconditionally and raises `ValueError: flush of closed file`
    on an already-closed pipe. Python 3.12+ tolerates it, so this only shows up
    on the runner.
    """
    owner = subprocess.Popen(
        [
            sys.executable, "-m", "pocketshell", "send",
            "--socket-name", tmux_fixture.socket_name,
            "--pane", tmux_fixture.pane_id, "--token", token, "--timeout", "60",
        ],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env,
    )
    owner.stdin.write(payload)
    owner.stdin.close()
    owner.stdin = None
    return owner


def _reap(owner: subprocess.Popen) -> None:
    """Never let cleanup raise over the real failure it is cleaning up after."""
    if owner.poll() is None:
        owner.kill()
    try:
        owner.wait(timeout=30)
    except subprocess.TimeoutExpired:  # pragma: no cover - defensive
        pass
    for stream in (owner.stdout, owner.stderr):
        if stream is not None:
            stream.close()


def _await_owner_claim(tmux_fixture: TmuxFixture, token: str, owner: subprocess.Popen) -> None:
    """Block until the owner has claimed the token and is inside the paste.

    The record file only becomes visible once it is COMPLETE (the claim links a
    fully written temp file into place), so its presence is a sound signal.
    """
    record_path = tmux_fixture.record_for(token)
    deadline = time.monotonic() + 30.0
    while time.monotonic() < deadline and not record_path.exists():
        time.sleep(0.02)
    assert record_path.exists(), "the owner never claimed the token"
    assert owner.poll() is None, "the owner must still be running for this test to mean anything"


def test_a_send_that_loses_to_a_live_sibling_is_not_reported_as_unknown(
    tmux_fixture: TmuxFixture, tmp_path: Path
) -> None:
    """#2122 F2: the reviewer's in-flight probe, end to end.

    The first send is held inside `paste-buffer` for several seconds (a slow or
    loaded host). A second call fired during that window provably injected
    nothing and its outcome is owned by a process that is demonstrably ALIVE —
    so the honest answer is `send-in-progress`, not "delivery is UNKNOWN".
    """
    marker = b"MARKER-2122-F2-INFLIGHT"
    payload = marker + b"\n"
    token = "row-f2-inflight"

    slow = _shim_dir(
        tmp_path, tmux_fixture.real_tmux,
        _match_first_arg("paste-buffer", "    sleep 5"), "f2-slow-paste",
    )
    owner_env = dict(tmux_fixture.env)
    owner_env["PATH"] = f"{slow}{os.pathsep}{tmux_fixture.env['PATH']}"
    owner = _start_slow_owner(tmux_fixture, token, payload, owner_env)
    try:

        _await_owner_claim(tmux_fixture, token, owner)

        loser = tmux_fixture.send(
            "--pane", tmux_fixture.pane_id, "--token", token, payload=payload
        )
        assert loser.stdout.decode().split()[0] == "send-in-progress", (
            "a call that lost to a LIVE owner injected nothing and knows it; "
            "reporting 'send-interrupted' invents an unknown (#2122 F2)"
        )
        assert loser.returncode == send_mod.EXIT_SEND_IN_PROGRESS
        stderr = loser.stderr.decode("utf-8", "replace").lower()
        assert "unknown" not in stderr, f"the message must not claim an unknown: {stderr!r}"

        stdout, _stderr = owner.communicate(timeout=120)
    finally:
        _reap(owner)
    assert owner.returncode == 0, "the sibling was healthy and must have delivered"
    assert stdout.decode().split()[0] == "delivered"

    after = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert after.returncode == 0
    assert after.stdout.decode().split()[0] == "already-delivered"
    assert tmux_fixture.settle().count(marker) == 1


def test_resend_interrupted_refuses_while_a_live_sibling_owns_the_token(
    tmux_fixture: TmuxFixture, tmp_path: Path
) -> None:
    """The opt-in resolves an UNKNOWN; a live owner is not one.

    Without this, one "Resend" tap racing an automatic flush duplicates the
    prompt into the agent — the #1602 shape, and the outcome #2121 calls worse
    than an unacked send.
    """
    marker = b"MARKER-2122-F2-OPTIN"
    payload = marker + b"\n"
    token = "row-f2-optin"

    slow = _shim_dir(
        tmp_path, tmux_fixture.real_tmux,
        _match_first_arg("paste-buffer", "    sleep 5"), "f2-slow-paste-optin",
    )
    owner_env = dict(tmux_fixture.env)
    owner_env["PATH"] = f"{slow}{os.pathsep}{tmux_fixture.env['PATH']}"
    owner = _start_slow_owner(tmux_fixture, token, payload, owner_env)
    try:
        _await_owner_claim(tmux_fixture, token, owner)

        forced = tmux_fixture.send(
            "--pane", tmux_fixture.pane_id, "--token", token, "--resend-interrupted",
            payload=payload,
        )
        assert forced.stdout.decode().split()[0] == "send-in-progress"
        assert forced.returncode == send_mod.EXIT_SEND_IN_PROGRESS
        owner.communicate(timeout=120)
    finally:
        _reap(owner)

    assert tmux_fixture.settle().count(marker) == 1, (
        "an opt-in resend during a live sibling's flight must not duplicate"
    )


def test_a_dead_owner_is_still_reported_as_the_genuine_unknown(
    tmux_fixture: TmuxFixture, tmp_path: Path
) -> None:
    """The guard against over-correcting F2.

    Liveness must not launder a REAL unknown into "somebody else has it": when
    the owner is gone, exit 5 is the correct and required answer.
    """
    marker = b"MARKER-2122-F2-DEAD"
    payload = marker + b"\n"
    token = "row-f2-dead"

    killed = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, payload=payload,
        path_prefix=_kill_after_paste_shim(tmux_fixture, tmp_path, "f2-kill-after"),
    )
    assert killed.returncode == -9
    tmux_fixture.wait_for_capture(len(payload))

    retry = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=payload)
    assert retry.stdout.decode().split()[0] == "send-interrupted"
    assert retry.returncode == send_mod.EXIT_SEND_INTERRUPTED
    assert tmux_fixture.settle().count(marker) == 1


def test_owner_is_alive_reports_true_only_for_this_running_process() -> None:
    assert send_mod.owner_is_alive(send_mod.owner_identity()) is True


def test_owner_is_alive_reports_false_for_a_process_that_exited() -> None:
    child = subprocess.Popen([sys.executable, "-c", "import time; time.sleep(60)"])
    try:
        owner = send_mod.owner_identity(child.pid)
        assert send_mod.owner_is_alive(owner) is True
    finally:
        child.kill()
        child.wait(timeout=30)
    assert send_mod.owner_is_alive(owner) is False, (
        "a reaped process must never look alive, or a real unknown would be "
        "laundered into 'a sibling owns it'"
    )


def test_owner_is_alive_rejects_a_reused_pid_and_a_different_boot() -> None:
    """pid alone is not identity: a reused pid has a different start time, and
    a record that survived a reboot names a pid from another boot entirely."""
    live = dict(send_mod.owner_identity())
    assert send_mod.owner_is_alive(live) is True

    reused = dict(live)
    reused["start_ticks"] = int(live["start_ticks"]) + 1
    assert send_mod.owner_is_alive(reused) is False

    rebooted = dict(live)
    rebooted["boot_id"] = "00000000-0000-0000-0000-000000000000"
    assert send_mod.owner_is_alive(rebooted) is False


@pytest.mark.parametrize(
    ("label", "owner"),
    [
        ("none", None),
        ("not-a-mapping", "pid-1234"),
        ("empty", {}),
        ("missing-start-ticks", {"pid": 1, "boot_id": "x"}),
        ("missing-boot-id", {"pid": 1, "start_ticks": 1}),
        ("pid-zero", {"pid": 0, "start_ticks": 1, "boot_id": "x"}),
        ("pid-not-int", {"pid": "1", "start_ticks": 1, "boot_id": "x"}),
        ("blank-boot-id", {"pid": 1, "start_ticks": 1, "boot_id": ""}),
    ],
)
def test_owner_is_alive_fails_closed_on_anything_it_cannot_prove(label: str, owner) -> None:
    """Unprovable must read as 'not alive' (=> the honest unknown), never as
    'a sibling owns it' (which would suppress a legitimate resend forever)."""
    assert send_mod.owner_is_alive(owner) is False, label


def test_a_pending_record_without_owner_evidence_reports_the_unknown(
    tmux_fixture: TmuxFixture,
) -> None:
    """Fail-closed end to end: no owner evidence => the conservative answer."""
    token = "row-f2-noowner"
    record_path = tmux_fixture.record_for(token)
    record_path.parent.mkdir(parents=True, exist_ok=True)
    send_mod.write_record_atomic(
        record_path,
        {
            "schema": 1, "token": token, "pane": tmux_fixture.pane_id,
            "state": "pending", "created_at": time.time(),
            "payload_bytes": 2, "enter": False,
        },
    )
    result = tmux_fixture.send("--pane", tmux_fixture.pane_id, "--token", token, payload=b"x\n")
    assert result.stdout.decode().split()[0] == "send-interrupted"
    assert result.returncode == send_mod.EXIT_SEND_INTERRUPTED
    assert tmux_fixture.settle(quiet=0.5) == b""


def test_send_in_progress_is_documented_as_a_distinct_retryable_outcome() -> None:
    runner = CliRunner()
    result = runner.invoke(cli, ["send", "--help"])
    assert result.exit_code == 0
    assert f"  {send_mod.EXIT_SEND_IN_PROGRESS}  " in result.output
    assert "send-in-progress" in result.output
    assert send_mod.EXIT_SEND_IN_PROGRESS != send_mod.EXIT_SEND_INTERRUPTED


# --------------------------------------------------------------------------
# F3 — the retry paths must drain stdin. A caller piping a payload into a
#      process that exits without reading takes SIGPIPE, so a successful
#      acknowledgement reads as a failed write on the SSH channel.
# --------------------------------------------------------------------------


def _pipeline_statuses(
    tmux_fixture: TmuxFixture, args: Sequence[str], *, nbytes: int
) -> tuple[int, int, str]:
    """Run `head -c N /dev/zero | pocketshell send ...` in a real shell.

    Returns (writer_status, cli_status, cli_stdout). PIPESTATUS is read
    explicitly rather than relying on `pipefail`, which reports only the
    RIGHTMOST non-zero status and would therefore hide a SIGPIPE on every
    non-zero CLI exit — the exact paths under test.
    """
    import shlex

    command = " ".join(
        shlex.quote(part)
        for part in [
            sys.executable, "-m", "pocketshell", "send",
            "--socket-name", tmux_fixture.socket_name, *args,
        ]
    )
    script = (
        f"head -c {nbytes} /dev/zero | {command} > /tmp/psf3.$$.out 2>/dev/null\n"
        'echo "STATUS ${PIPESTATUS[0]} ${PIPESTATUS[1]}"\n'
        "cat /tmp/psf3.$$.out; rm -f /tmp/psf3.$$.out\n"
    )
    result = subprocess.run(
        ["bash", "-c", script], env=tmux_fixture.env, capture_output=True, timeout=120.0
    )
    lines = result.stdout.decode("utf-8", "replace").splitlines()
    status_line = next(line for line in lines if line.startswith("STATUS "))
    _, writer, cli_status = status_line.split()
    body = "\n".join(line for line in lines if not line.startswith("STATUS "))
    return int(writer), int(cli_status), body.strip()


def test_the_already_delivered_retry_does_not_sigpipe_the_writer(
    tmux_fixture: TmuxFixture,
) -> None:
    """#2122 F3: the retry path is the one #2124 exercises most.

    The round-1 code short-circuited on `already-delivered` BEFORE reading
    stdin, so a writer still piping a payload got SIGPIPE (status 141) — a
    successful acknowledgement surfacing to the caller as a write failure.
    """
    token = "row-f3-already"
    first = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, payload=b"Z" * 200000 + b"\n"
    )
    assert first.returncode == 0

    writer, cli_status, body = _pipeline_statuses(
        tmux_fixture, ["--pane", tmux_fixture.pane_id, "--token", token], nbytes=200001
    )
    assert body.split()[0] == "already-delivered"
    assert cli_status == 0
    assert writer == 0, (
        f"the writer took signal {writer - 128} (SIGPIPE=13 => 141): the "
        "already-delivered path must drain stdin before exiting"
    )


def test_the_send_interrupted_retry_does_not_sigpipe_the_writer(
    tmux_fixture: TmuxFixture, tmp_path: Path
) -> None:
    """The same hazard on the other retry path — this one is a whole class."""
    token = "row-f3-interrupted"
    killed = tmux_fixture.send(
        "--pane", tmux_fixture.pane_id, "--token", token, payload=b"Q" * 1000 + b"\n",
        path_prefix=_kill_after_paste_shim(tmux_fixture, tmp_path, "f3-kill-after"),
    )
    assert killed.returncode == -9

    writer, cli_status, body = _pipeline_statuses(
        tmux_fixture, ["--pane", tmux_fixture.pane_id, "--token", token], nbytes=200001
    )
    assert body.split()[0] == "send-interrupted"
    assert cli_status == send_mod.EXIT_SEND_INTERRUPTED
    assert writer == 0, f"the writer took signal {writer - 128} on the send-interrupted path"


def test_the_journal_corrupt_retry_does_not_sigpipe_the_writer(
    tmux_fixture: TmuxFixture,
) -> None:
    token = "row-f3-corrupt"
    record_path = tmux_fixture.record_for(token)
    record_path.parent.mkdir(parents=True, exist_ok=True)
    record_path.write_bytes(b"{not json")

    writer, cli_status, body = _pipeline_statuses(
        tmux_fixture, ["--pane", tmux_fixture.pane_id, "--token", token], nbytes=200001
    )
    assert body.split()[0] == "journal-corrupt"
    assert cli_status == send_mod.EXIT_SEND_INTERRUPTED
    assert writer == 0, f"the writer took signal {writer - 128} on the journal-corrupt path"


def test_bad_usage_exits_promptly_instead_of_waiting_for_a_payload(
    tmux_fixture: TmuxFixture,
) -> None:
    """The deliberate limit of the F3 fix, pinned so it cannot drift.

    Argument validation happens BEFORE stdin is read on purpose: a caller with
    an open-but-idle stdin (an ssh exec channel the client has not closed)
    must get its documented `bad-usage` immediately rather than blocking
    forever waiting for a payload that will never arrive. Hanging is strictly
    worse than a writer signal, and the issue is explicit that this command
    must never hang.
    """
    process = subprocess.Popen(
        [
            sys.executable, "-m", "pocketshell", "send",
            "--socket-name", tmux_fixture.socket_name, "--token", "row-f3-badusage",
        ],
        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
        env=tmux_fixture.env,
    )
    try:
        stdout, _stderr = process.communicate(timeout=30.0)
    except subprocess.TimeoutExpired:
        process.kill()
        process.communicate()
        raise AssertionError("bad usage must not block waiting for stdin")
    assert process.returncode == send_mod.EXIT_BAD_USAGE
    assert stdout.decode().split()[0] == "bad-usage"


def test_an_interactive_terminal_stdin_is_not_read_as_a_payload(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """Draining stdin must never block a human at a terminal.

    A tty has no piped payload and no EOF until the user types one, so the read
    is skipped entirely; the empty payload then takes the documented
    `bad-usage` path rather than hanging.
    """

    class _Tty:
        def isatty(self) -> bool:
            return True

        def read(self) -> str:  # pragma: no cover - must never be called
            raise AssertionError("stdin must not be read when it is a terminal")

    monkeypatch.setattr(send_mod.sys, "stdin", _Tty())
    assert send_mod._read_stdin_bytes() == b""


# --------------------------------------------------------------------------
# The at-most-once invariant depends on the journal REMEMBERING: automatic
# housekeeping must never delete an unresolved record.
# --------------------------------------------------------------------------


def test_auto_prune_never_removes_an_unresolved_record(tmp_path: Path) -> None:
    """An aged `pending`/corrupt record is still an unknown, not garbage.

    Pruning one silently returns the token to `absent`, so the next plain call
    injects again with no opt-in — the same falsification as F1, on a timer.
    """
    paths = send_mod.SendPaths(sends_dir=tmp_path / "sends")
    now = time.time()
    delivered = _seed_record(paths, "old-delivered", age_secs=100 * 86400, now=now)
    pending = paths.record_file("old-pending")
    send_mod.write_record_atomic(
        pending,
        {
            "schema": 1, "token": "old-pending", "pane": "%0", "state": "pending",
            "created_at": now - 100 * 86400, "payload_bytes": 1, "enter": False,
        },
    )
    corrupt = paths.sends_dir / "cafebabe.json"
    corrupt.write_bytes(b"not json")
    os.utime(corrupt, (now - 100 * 86400, now - 100 * 86400))

    assert send_mod.maybe_auto_prune(paths, now=now) is True
    assert not delivered.exists(), "resolved records past retention are still pruned"
    assert pending.exists(), "an unresolved pending record must survive auto-pruning"
    assert corrupt.exists(), "an unreadable (= unresolved) record must survive auto-pruning"


def test_explicit_prune_remains_a_deliberate_operator_sweep(tmp_path: Path) -> None:
    """`--prune-older-than` is an explicit human action and still removes
    everything past the cutoff, including unresolved records."""
    paths = send_mod.SendPaths(sends_dir=tmp_path / "sends")
    now = time.time()
    pending = paths.record_file("old-pending")
    send_mod.write_record_atomic(
        pending,
        {
            "schema": 1, "token": "old-pending", "pane": "%0", "state": "pending",
            "created_at": now - 100 * 86400, "payload_bytes": 1, "enter": False,
        },
    )
    assert send_mod.prune(paths, older_than_secs=86400, now=now) == 1
    assert not pending.exists()


# --------------------------------------------------------------------------
# The two narrow races the F2 fix rests on. Both are microsecond-wide against
# a real tmux server, so the losing state is injected deterministically (the
# environment cannot be made to produce it on demand) and the assertion is on
# what the USER sees, never on a seam having fired.
# --------------------------------------------------------------------------


def _dead_owner() -> dict:
    """An owner stamp naming a process that has certainly exited."""
    child = subprocess.Popen([sys.executable, "-c", "pass"])
    child.wait(timeout=30)
    owner = send_mod.owner_identity(child.pid)
    assert send_mod.owner_is_alive(owner) is False
    return owner


def _lost_claim_outcome(
    tmux_fixture: TmuxFixture,
    monkeypatch: pytest.MonkeyPatch,
    token: str,
    winner_document: dict,
):
    """Drive the CLI into "a sibling won the exclusive claim" deterministically.

    The claim is lost only when a sibling publishes the record in the window
    between this call's journal read and its own claim — real (five racing
    processes hit it) but not reproducible on demand, so the winner's record is
    published from inside the claim itself. Everything downstream of the claim,
    including the whole outcome decision, is untouched production code.
    """
    paths = send_mod.resolve_paths(env={"XDG_STATE_HOME": str(tmux_fixture.state_dir)})

    def _sibling_won(path: Path, document) -> bool:
        send_mod.write_record_atomic(path, winner_document)
        return False

    monkeypatch.setattr(send_mod, "claim_record_exclusive", _sibling_won)
    runner = CliRunner()
    result = runner.invoke(
        cli,
        [
            "send", "--socket-name", tmux_fixture.socket_name,
            "--pane", tmux_fixture.pane_id, "--token", token,
        ],
        input=b"MARKER-2122-LOSTCLAIM\n",
        env=tmux_fixture.env,
    )
    assert paths.record_file(token).exists()
    return result


def _winner_document(token: str, pane: str, *, state: str, owner=None) -> dict:
    now = time.time()
    return send_mod._record_document(
        token=token, pane=pane, state=state, created_at=now,
        delivered_at=now if state == send_mod.STATE_DELIVERED else None,
        payload_bytes=22, enter=False, owner=owner,
    )


def test_losing_the_claim_to_a_sibling_that_already_delivered_reports_success(
    tmux_fixture: TmuxFixture, monkeypatch: pytest.MonkeyPatch
) -> None:
    token = "row-lostclaim-delivered"
    result = _lost_claim_outcome(
        tmux_fixture, monkeypatch, token,
        _winner_document(token, tmux_fixture.pane_id, state=send_mod.STATE_DELIVERED),
    )
    assert result.output.split()[0] == "already-delivered", result.output
    assert result.exit_code == 0
    assert tmux_fixture.settle(quiet=0.5) == b"", "the loser must not have injected"


def test_losing_the_claim_to_a_live_sibling_reports_send_in_progress(
    tmux_fixture: TmuxFixture, monkeypatch: pytest.MonkeyPatch
) -> None:
    token = "row-lostclaim-live"
    result = _lost_claim_outcome(
        tmux_fixture, monkeypatch, token,
        _winner_document(
            token, tmux_fixture.pane_id,
            state=send_mod.STATE_PENDING, owner=send_mod.owner_identity(),
        ),
    )
    assert result.output.split()[0] == "send-in-progress", result.output
    assert result.exit_code == send_mod.EXIT_SEND_IN_PROGRESS
    assert tmux_fixture.settle(quiet=0.5) == b""


def test_losing_the_claim_to_a_sibling_that_then_died_reports_the_unknown(
    tmux_fixture: TmuxFixture, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The one case where exit 5 IS the honest answer on this path."""
    token = "row-lostclaim-dead"
    result = _lost_claim_outcome(
        tmux_fixture, monkeypatch, token,
        _winner_document(
            token, tmux_fixture.pane_id,
            state=send_mod.STATE_PENDING, owner=_dead_owner(),
        ),
    )
    assert result.output.split()[0] == "send-interrupted", result.output
    assert result.exit_code == send_mod.EXIT_SEND_INTERRUPTED
    assert tmux_fixture.settle(quiet=0.5) == b""


def test_a_sibling_that_finished_between_the_read_and_the_liveness_check_is_not_an_unknown(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """The other race: the owner promoted the record and exited microseconds
    after we read it, so a single read would show `pending` + a dead owner and
    report a COMPLETED delivery as unknown — a spurious unknown manufactured
    out of nothing, which is the whole defect class #2121 exists to delete.
    """
    state_dir = tmp_path / "state"
    paths = send_mod.resolve_paths(env={"XDG_STATE_HOME": str(state_dir)})
    token = "row-read-race"
    send_mod.write_record_atomic(
        paths.record_file(token),
        _winner_document(token, "%0", state=send_mod.STATE_DELIVERED),
    )

    stale = send_mod.JournalRecord(
        state=send_mod.STATE_PENDING, token=token, pane="%0",
        created_at=time.time(), owner=_dead_owner(),
    )
    real_read = send_mod.read_record
    reads: list[int] = []

    def _stale_first(path: Path, wanted: str):
        reads.append(1)
        return stale if len(reads) == 1 else real_read(path, wanted)

    monkeypatch.setattr(send_mod, "read_record", _stale_first)

    runner = CliRunner()
    result = runner.invoke(
        cli, ["send", "--pane", "%0", "--token", token],
        input=b"x\n", env={"XDG_STATE_HOME": str(state_dir)},
    )
    assert result.output.split()[0] == "already-delivered", result.output
    assert result.exit_code == 0


def test_classify_unresolved_reports_a_live_owner_without_a_second_read(
    tmp_path: Path,
) -> None:
    """The fast path stays fast: a live owner is decided on the first read."""
    paths = send_mod.SendPaths(sends_dir=tmp_path / "sends")
    token = "row-classify-live"
    path = paths.record_file(token)
    send_mod.write_record_atomic(
        path,
        _winner_document(
            token, "%0", state=send_mod.STATE_PENDING, owner=send_mod.owner_identity()
        ),
    )
    record, alive = send_mod.classify_unresolved(path, token)
    assert record.state == send_mod.STATE_PENDING
    assert alive is True
