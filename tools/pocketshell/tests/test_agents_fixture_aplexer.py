"""The Docker `agents` fixture must run the REAL, pinned aplexer (issue #2563).

Slice 1 of #2561. Two things are pinned here, both on the JVM-free
``Python utility tests`` gate:

1. **The pin is derived, not typed.** ``tests/docker/Dockerfile.agents``
   extracts the aplexer version from the very same ``aplexer==X.Y.Z``
   requirement ``tools/pocketshell/pyproject.toml`` pins for production, so the
   fixture and the app cannot disagree. This file runs the Dockerfile's OWN
   derivation command against the real ``pyproject.toml`` and compares it with
   the requirement this suite already parses — a behavioural check on the
   derivation, not a text match on a literal.

2. **The pin is asserted by BEHAVIOUR, never by ``a --version``.** AGENTS.md
   records the reason: a locally built ``a`` and a published wheel both
   self-reported the same version while differing by 143 commits. So the
   fixture's proof is a full aplexer lifecycle through the real CLI, run at
   image-BUILD time (``tests/docker/agents-aplexer-selfcheck.py``, wired into
   ``Dockerfile.agents``) and again against a live container in
   ``scripts/test-agents-fixture-aplexer.sh --docker``. What this file can
   check without Docker is that those gates are still wired and still
   version-string-free.

Plus a regression guard for the seed-file read that replaced the deleted
61-line shell ``a`` stub: journeys J02/J04/J14 hand the fixture an aplexer row
by writing ``~/.pocketshell-fixture-aplexer.json``, and that must keep working
byte-for-byte now that no ``a`` binary is in that path.
"""

from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tomllib
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
PYPROJECT = REPO_ROOT / "tools" / "pocketshell" / "pyproject.toml"
DOCKERFILE = REPO_ROOT / "tests" / "docker" / "Dockerfile.agents"
SELFCHECK = REPO_ROOT / "tests" / "docker" / "agents-aplexer-selfcheck.py"
FIXTURE_SESSIONS = (
    REPO_ROOT / "tests" / "docker" / "agent-bin" / "pocketshell-fixture-sessions"
)
CANNED_TABLE = (
    REPO_ROOT
    / "tests"
    / "docker"
    / "agent-fixtures"
    / "pocketshell-sessions-list.txt"
)
GUARD = REPO_ROOT / "scripts" / "test-agents-fixture-aplexer.sh"


def _pinned_version() -> str:
    data = tomllib.loads(PYPROJECT.read_text(encoding="utf-8"))
    reqs = [
        r
        for r in data["project"]["dependencies"]
        if r.split(";")[0].strip().startswith("aplexer")
    ]
    assert len(reqs) == 1, f"expected exactly one aplexer requirement, got {reqs}"
    return reqs[0].split(";")[0].strip().split("==", 1)[1]


def _derivation_command() -> str:
    """The `sed` expression Dockerfile.agents uses to read the pin."""
    for line in DOCKERFILE.read_text(encoding="utf-8").splitlines():
        if "sed -n" in line and "pyproject.toml" in line:
            match = re.search(r'"\$\((.*)\)"', line)
            assert match, f"cannot parse the derivation out of: {line!r}"
            return match.group(1)
    raise AssertionError(
        "Dockerfile.agents no longer derives the aplexer pin from pyproject.toml"
    )


def test_fixture_derivation_reproduces_the_production_pin() -> None:
    """Run the Dockerfile's own command; it must yield the production pin."""
    command = _derivation_command().replace(
        "/opt/pocketshell-real/pyproject.toml", str(PYPROJECT)
    )
    derived = subprocess.run(
        ["/bin/sh", "-c", command],
        capture_output=True,
        text=True,
        check=True,
    ).stdout.strip()
    assert derived == _pinned_version(), (
        "the agents fixture would install aplexer "
        f"{derived!r} while the CLI pins {_pinned_version()!r}"
    )


def test_fixture_never_hardcodes_an_aplexer_version() -> None:
    """A literal in the Dockerfile is exactly the drift the derivation removes."""
    text = DOCKERFILE.read_text(encoding="utf-8")
    assert not re.search(r"aplexer/releases/download/v[0-9]", text), (
        "the release URL hardcodes a version; it must interpolate the pin "
        "derived from pyproject.toml"
    )
    assert not re.search(r"APLEXER_VERSION[ \t]*=[ \t]*[0-9]", text), (
        "a literal APLEXER_VERSION build-arg default reintroduces the drift"
    )


def test_the_pin_is_asserted_by_behaviour_not_by_a_version_string() -> None:
    """No `--version` call anywhere in the fixture's aplexer proof.

    AGENTS.md: a local build and a published wheel both self-reported the same
    version while differing by 143 commits, so the string is not evidence.
    Prose may DISCUSS it; code must not invoke it.
    """
    for path in (DOCKERFILE, SELFCHECK):
        text = path.read_text(encoding="utf-8")
        assert not re.search(r"""['"]--version['"]""", text), (
            f"{path.name} asserts the aplexer pin with a version string"
        )


def test_the_build_runs_the_behavioural_selfcheck() -> None:
    """The image cannot be built hollow.

    Without this RUN, an `a` that is merely on PATH (rather than next to
    `sys.executable`, the only place `pocketshell.aplexer` looks — D22 hard
    cut, #2543) yields `managers: ["tmux"]`, zero aplexer rows and exit 0. The
    fixture then looks healthy while proving nothing.
    """
    text = DOCKERFILE.read_text(encoding="utf-8")
    assert re.search(
        r"^RUN su testuser .*pocketshell-fixture-aplexer-selfcheck",
        text,
        re.MULTILINE,
    ), "Dockerfile.agents must run the aplexer self-check at build time"
    assert SELFCHECK.is_file()
    # The self-check must drive the REAL CLI, not the deterministic agent-bin
    # shim, or it would be asserting against a fixture of a fixture.
    body = SELFCHECK.read_text(encoding="utf-8")
    assert '"-m", "pocketshell"' in body
    for verb in ("sessions", "create", "list", "kill"):
        assert f'"{verb}"' in body, f"the self-check no longer drives `{verb}`"


def test_the_fake_shell_a_stub_stays_deleted() -> None:
    """`tests/docker/agent-bin/a` would shadow the real binary on PATH."""
    assert not (REPO_ROOT / "tests" / "docker" / "agent-bin" / "a").exists(), (
        "the 61-line shell `a` stub is back; it answers only the dead "
        "`a --json sessions` verb and hosts no attachable process (D22)"
    )


def test_the_docker_guard_is_wired_into_ci() -> None:
    """A guard nobody runs is not a guard (G9)."""
    assert GUARD.is_file() and os.access(GUARD, os.X_OK)
    workflow = (REPO_ROOT / ".github" / "workflows" / "tests.yml").read_text(
        encoding="utf-8"
    )
    assert "scripts/test-agents-fixture-aplexer.sh\n" in workflow, (
        "the static half is not wired into a job"
    )
    assert "scripts/test-agents-fixture-aplexer.sh --docker" in workflow, (
        "the real-container half is not wired into `Integration tests (Docker)`"
    )


def test_the_docker_guard_covers_attachability_over_ssh() -> None:
    """The attach arm must not be quietly deleted from the `--docker` guard.

    Attachability is the ONE property the deleted 61-line stub lacked — the old
    image text said an aplexer name "hosts no attachable process", and the whole
    justification for the base swap is that the fixture can now host one. A gate
    that stops at ``create -> list -> kill`` lets an image whose ``a attach`` is
    broken build green and ship, which is the same vacuous-green shape this
    slice exists to remove, one level up.

    It must also happen over **SSH**, not ``docker exec``: the app drives the
    host over sshd's non-interactive ``exec`` channel, whose environment differs
    from ``docker exec ... su testuser`` in exactly the way this image's own
    #2276 ``login-only-agent`` rung exists to demonstrate. And attaching needs a
    real PTY, which ``docker exec`` in that script does not allocate.
    """
    guard = GUARD.read_text(encoding="utf-8")
    assert "docker port" in guard, (
        "the guard must publish and read the container's SSH port; without it "
        "every assertion runs on the local `docker exec` path, not the one the "
        "app uses"
    )
    assert "ssh -tt" in guard, (
        "no PTY attach in the guard — `a attach` / `sessions attach` cannot be "
        "exercised without one"
    )
    assert "a capture --screen --plain" in guard, (
        "the attach arm needs an INDEPENDENT oracle on a separate connection; "
        "reading the attach transcript back would only prove the PTY echoed"
    )
    for needle in (
        "pocketshell-real-send sessions attach",
        "a attach --workspace",
        "a start --workspace",
    ):
        assert needle in guard, f"the guard no longer drives `{needle}`"


# ---------------------------------------------------------------------------
# The seed-file read that replaced the deleted `a` stub
# ---------------------------------------------------------------------------


def _run_fixture_sessions(tmp_path: Path, seed: object | None) -> dict:
    env = dict(os.environ)
    env.pop("POCKETSHELL_APLEXER", None)
    # Never walk the developer's real tmux sockets from a unit test.
    env["POCKETSHELL_FIXTURE_TMUX_SOCKET_DIR"] = str(tmp_path / "no-sockets")
    seed_path = tmp_path / "aplexer-seed.json"
    if seed is not None:
        seed_path.write_text(json.dumps(seed), encoding="utf-8")
    env["POCKETSHELL_FIXTURE_APLEXER_FILE"] = str(seed_path)
    env["POCKETSHELL_FIXTURE_SESSION_DETAIL_FILE"] = str(tmp_path / "absent-detail")
    env["POCKETSHELL_FIXTURE_SESSION_ERRORS_FILE"] = str(tmp_path / "absent-errors")
    proc = subprocess.run(
        [sys.executable, str(FIXTURE_SESSIONS), "json", str(CANNED_TABLE)],
        capture_output=True,
        text=True,
        env=env,
    )
    assert proc.returncode == 0, proc.stderr
    return json.loads(proc.stdout)


def test_seeded_aplexer_row_still_reaches_the_listing(tmp_path: Path) -> None:
    """J02's contract: seeding the file yields an `aplexer` row, no `a` binary.

    Issue #2563 deleted the shell `a` stub this used to shell out to; the read
    moved into the enumerator. The observable payload must not move with it.
    """
    payload = _run_fixture_sessions(
        tmp_path,
        {
            "sessions": [
                {
                    "name": "aplexer-follow:yolo",
                    "id": "seed-id",
                    "workspace": "/home/testuser/git/aplexer",
                    "tag": "yolo",
                }
            ]
        },
    )
    rows = [row for row in payload["sessions"] if row["manager"] == "aplexer"]
    assert [row["name"] for row in rows] == ["aplexer-follow:yolo"]
    assert rows[0]["workspace"] == "/home/testuser/git/aplexer"
    assert rows[0]["id"] == "seed-id"
    assert rows[0]["attached"] is False
    assert "aplexer" in payload["managers"]
    # Schema 2: every key present, unknown values explicitly null.
    for key in ("engine", "profile", "agent_state", "activity_epoch"):
        assert key in rows[0]


def test_absent_seed_file_means_no_aplexer_rows(tmp_path: Path) -> None:
    """Every journey that seeds nothing must see exactly the canned tmux rows."""
    payload = _run_fixture_sessions(tmp_path, None)
    assert [row for row in payload["sessions"] if row["manager"] == "aplexer"] == []
    assert payload["managers"] == ["tmux"]


def test_master_kill_switch_still_suppresses_aplexer_rows(tmp_path: Path) -> None:
    env = dict(os.environ)
    env["POCKETSHELL_APLEXER"] = "0"
    env["POCKETSHELL_FIXTURE_TMUX_SOCKET_DIR"] = str(tmp_path / "no-sockets")
    seed = tmp_path / "seed.json"
    seed.write_text(json.dumps({"sessions": [{"name": "x:y"}]}), encoding="utf-8")
    env["POCKETSHELL_FIXTURE_APLEXER_FILE"] = str(seed)
    proc = subprocess.run(
        [sys.executable, str(FIXTURE_SESSIONS), "json", str(CANNED_TABLE)],
        capture_output=True,
        text=True,
        env=env,
    )
    assert proc.returncode == 0, proc.stderr
    payload = json.loads(proc.stdout)
    assert [row for row in payload["sessions"] if row["manager"] == "aplexer"] == []


@pytest.mark.parametrize("garbage", ["not json at all", "[]", "{}"])
def test_unreadable_seed_file_degrades_to_no_rows(tmp_path: Path, garbage: str) -> None:
    """Same fail-soft shape the deleted stub had: never a crash, never a row."""
    seed = tmp_path / "seed.json"
    seed.write_text(garbage, encoding="utf-8")
    env = dict(os.environ)
    env.pop("POCKETSHELL_APLEXER", None)
    env["POCKETSHELL_FIXTURE_TMUX_SOCKET_DIR"] = str(tmp_path / "no-sockets")
    env["POCKETSHELL_FIXTURE_APLEXER_FILE"] = str(seed)
    proc = subprocess.run(
        [sys.executable, str(FIXTURE_SESSIONS), "json", str(CANNED_TABLE)],
        capture_output=True,
        text=True,
        env=env,
    )
    assert proc.returncode == 0, proc.stderr
    payload = json.loads(proc.stdout)
    assert [row for row in payload["sessions"] if row["manager"] == "aplexer"] == []
