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


# ---------------------------------------------------------------------------
# The opt-in LIVE arm (issue #2586)
# ---------------------------------------------------------------------------
#
# The seed arm above is the DETERMINISTIC one and stays exactly as it is: it is
# what lets J02 seed an exact aplexer row and the #2426 partial-listing error
# shape (one backend failing while the other answers), neither of which a live
# session can produce. What was missing is the other half — a session really
# created with `sessions create --backend aplexer` was invisible to the
# fixture's `sessions list --json`, because that arm only ever read the seed
# file. So this is additive: a journey opts in by touching a marker file, and
# the two modes must never merge or be confusable.
#
# Every test here pins APLEXER_BIN at a stub. Live mode with APLEXER_BIN unset
# would resolve the REAL pinned `a` next to `sys.executable` (this repo's venv
# ships one, since aplexer is a hard dependency) and enumerate whoever's real
# aplexer sessions are on the machine running the suite — non-deterministic,
# and on the maintainer's box, other people's sessions.

LIVE_ID = "3b5d1c66-0a4f-4c9b-9f3d-7c2a1e5b8d40"
LIVE_SNAPSHOT = [
    {
        "id": LIVE_ID,
        "workspace": "/home/testuser/git/pocketshell",
        "tag": "live-tag",
        "engine": "claude",
        "phase": "running",
        "worker_alive": True,
        "worker_pid": 4321,
        "created_at_ms": 1788409253000,
        "last_activity_ms": 1788409353000,
    }
]


def _stub_a(tmp_path: Path, *, snapshot: object = None, fails: bool = False) -> Path:
    """A stand-in for the pinned `a`, wired in through APLEXER_BIN.

    APLEXER_BIN is the CLI's own documented test seam (`aplexer.py`: "the seam
    the test suite's stub `a` uses"), so this exercises the same resolution and
    probe code the container runs, without a Rust binary or a real session.
    """
    script = tmp_path / "stub-a"
    if fails:
        body = "printf 'stub a: snapshot exploded\\n' >&2\nexit 1\n"
    else:
        body = "cat <<'STUB_JSON'\n%s\nSTUB_JSON\n" % json.dumps(snapshot)
    script.write_text("#!/bin/sh\n" + body, encoding="utf-8")
    script.chmod(0o755)
    return script


def _run_live_fixture(
    tmp_path: Path,
    *,
    marker: bool = True,
    seed: object | None = None,
    a_bin: Path | str | None = None,
    extra_env: dict[str, str] | None = None,
) -> subprocess.CompletedProcess:
    env = dict(os.environ)
    env.pop("POCKETSHELL_APLEXER", None)
    env["POCKETSHELL_FIXTURE_TMUX_SOCKET_DIR"] = str(tmp_path / "no-sockets")
    seed_path = tmp_path / "aplexer-seed.json"
    if seed is not None:
        seed_path.write_text(json.dumps(seed), encoding="utf-8")
    env["POCKETSHELL_FIXTURE_APLEXER_FILE"] = str(seed_path)
    env["POCKETSHELL_FIXTURE_SESSION_DETAIL_FILE"] = str(tmp_path / "absent-detail")
    env["POCKETSHELL_FIXTURE_SESSION_ERRORS_FILE"] = str(tmp_path / "absent-errors")
    marker_path = tmp_path / "live-marker"
    if marker:
        marker_path.write_text("", encoding="utf-8")
    env["POCKETSHELL_FIXTURE_APLEXER_LIVE_FILE"] = str(marker_path)
    if a_bin is None:
        env.pop("APLEXER_BIN", None)
    else:
        env["APLEXER_BIN"] = str(a_bin)
    env.update(extra_env or {})
    return subprocess.run(
        [sys.executable, str(FIXTURE_SESSIONS), "json", str(CANNED_TABLE)],
        capture_output=True,
        text=True,
        env=env,
    )


def test_live_mode_lists_the_session_the_real_a_reports(tmp_path: Path) -> None:
    """Acceptance criterion 1, at the unit level.

    The row must carry the id and the display name the real `a` reports —
    `<workspace-basename>:<tag>`, computed by the production
    `session_enum.aplexer_display_name`, not a hand-written string.
    """
    proc = _run_live_fixture(
        tmp_path, a_bin=_stub_a(tmp_path, snapshot=LIVE_SNAPSHOT)
    )
    assert proc.returncode == 0, proc.stderr
    payload = json.loads(proc.stdout)
    rows = [row for row in payload["sessions"] if row["manager"] == "aplexer"]
    assert [row["name"] for row in rows] == ["pocketshell:live-tag"]
    assert rows[0]["id"] == LIVE_ID
    assert rows[0]["workspace"] == "/home/testuser/git/pocketshell"
    assert rows[0]["tag"] == "live-tag"
    assert rows[0]["engine"] == "claude"
    assert "aplexer" in payload["managers"]
    assert payload["errors"] == []
    # The canned tmux rows are untouched: live mode replaces the APLEXER arm
    # only.
    assert [row["name"] for row in payload["sessions"] if row["manager"] == "tmux"]


def test_live_mode_with_a_stale_seed_file_fails_loudly(tmp_path: Path) -> None:
    """Two confusable modes is how a journey asserts against the wrong one.

    Merging a leftover seed row into a live listing would let a journey pass
    while proving nothing, so the fixture refuses to answer at all.
    """
    proc = _run_live_fixture(
        tmp_path,
        seed={"sessions": [{"name": "stale:row"}]},
        a_bin=_stub_a(tmp_path, snapshot=LIVE_SNAPSHOT),
    )
    assert proc.returncode != 0, (
        "live mode with a stale seed file exited 0; the two arms silently merged"
    )
    assert proc.stdout.strip() == "", (
        "a mode conflict must produce NO listing; a partial one is exactly the "
        "thing a journey would assert against by accident"
    )
    assert "FIXTURE-SESSIONS FAIL [mode]" in proc.stderr, proc.stderr
    assert "aplexer-seed.json" in proc.stderr, proc.stderr
    assert "live-marker" in proc.stderr, proc.stderr
    assert "stale:row" not in proc.stdout


def test_live_mode_without_a_resolvable_a_fails_loudly(tmp_path: Path) -> None:
    """The landmine from #2543/#2563, one level up.

    An `a` that is absent (or only on PATH) makes the production probe return
    "this is a tmux-only host": no rows, no errors, exit 0. That silence is
    legitimate for a host without aplexer and is NEVER legitimate for a journey
    that explicitly opted into live enumeration.
    """
    proc = _run_live_fixture(tmp_path, a_bin=tmp_path / "definitely-not-here")
    assert proc.returncode != 0, (
        "live mode resolved no `a` and still exited 0 — the vacuous-green shape"
    )
    assert proc.stdout.strip() == ""
    assert "FIXTURE-SESSIONS FAIL [live]" in proc.stderr, proc.stderr
    assert "definitely-not-here" in proc.stderr, proc.stderr


def test_live_mode_reports_a_probe_failure_as_a_backend_error(
    tmp_path: Path,
) -> None:
    """#2426, on the live arm: a backend that failed must SAY so.

    A failing `a` is not a mode conflict — it is the partial-listing contract,
    so the fixture still answers, with aplexer in `errors` and out of
    `managers`.
    """
    proc = _run_live_fixture(tmp_path, a_bin=_stub_a(tmp_path, fails=True))
    assert proc.returncode == 0, proc.stderr
    payload = json.loads(proc.stdout)
    assert [row for row in payload["sessions"] if row["manager"] == "aplexer"] == []
    assert [error["manager"] for error in payload["errors"]] == ["aplexer"]
    assert "aplexer" not in payload["managers"]
    assert [row for row in payload["sessions"] if row["manager"] == "tmux"]


def test_live_mode_with_the_kill_switch_fails_loudly(tmp_path: Path) -> None:
    """`POCKETSHELL_APLEXER=0` silences the probe; live mode must not accept it."""
    proc = _run_live_fixture(
        tmp_path,
        a_bin=_stub_a(tmp_path, snapshot=LIVE_SNAPSHOT),
        extra_env={"POCKETSHELL_APLEXER": "0"},
    )
    assert proc.returncode != 0, proc.stderr
    assert proc.stdout.strip() == ""
    assert "FIXTURE-SESSIONS FAIL [live]" in proc.stderr, proc.stderr
    assert "POCKETSHELL_APLEXER" in proc.stderr, proc.stderr


def test_a_live_session_stays_invisible_to_the_deterministic_arm(
    tmp_path: Path,
) -> None:
    """The bug, and the guarantee that fixing it changed nothing by default.

    With no marker the fixture must behave EXACTLY as it does today: the seeded
    row and only the seeded row, even though a perfectly good `a` with a live
    session is right there. J02 and the #2426 journeys ride on this.
    """
    proc = _run_live_fixture(
        tmp_path,
        marker=False,
        seed={"sessions": [{"name": "aplexer-follow:yolo", "id": "seed-id"}]},
        a_bin=_stub_a(tmp_path, snapshot=LIVE_SNAPSHOT),
    )
    assert proc.returncode == 0, proc.stderr
    payload = json.loads(proc.stdout)
    rows = [row for row in payload["sessions"] if row["manager"] == "aplexer"]
    assert [row["name"] for row in rows] == ["aplexer-follow:yolo"]
    assert rows[0]["id"] == "seed-id"
    assert payload["errors"] == []


def test_live_mode_leaves_the_human_table_byte_for_byte(tmp_path: Path) -> None:
    """`list` is the canned table and stays it; only `--json` grew an arm."""
    env = dict(os.environ)
    env.pop("POCKETSHELL_APLEXER", None)
    env["POCKETSHELL_FIXTURE_TMUX_SOCKET_DIR"] = str(tmp_path / "no-sockets")
    marker = tmp_path / "live-marker"
    marker.write_text("", encoding="utf-8")
    env["POCKETSHELL_FIXTURE_APLEXER_LIVE_FILE"] = str(marker)
    env["APLEXER_BIN"] = str(_stub_a(tmp_path, snapshot=LIVE_SNAPSHOT))
    proc = subprocess.run(
        [sys.executable, str(FIXTURE_SESSIONS), "list", str(CANNED_TABLE)],
        capture_output=True,
        text=True,
        env=env,
    )
    assert proc.returncode == 0, proc.stderr
    assert proc.stdout == CANNED_TABLE.read_text(encoding="utf-8")


def test_the_docker_guard_covers_live_enumeration(tmp_path: Path) -> None:
    """The live arm needs the SAME container proof the seed arm never needed.

    A unit test with a stub `a` proves the wiring; only the container proves
    that a session created through the app's own `pocketshell sessions create
    --backend aplexer` becomes visible to `pocketshell sessions list --json`.
    The guard must therefore show BOTH sides of the bug: invisible by default,
    visible after opting in.
    """
    guard = GUARD.read_text(encoding="utf-8")
    for needle in (
        ".pocketshell-fixture-aplexer-live",
        "pocketshell sessions create",
        "pocketshell sessions list --json",
    ):
        assert needle in guard, f"the guard does not drive `{needle}`"
    assert "FIXTURE-SESSIONS FAIL [mode]" in guard, (
        "the guard must prove the stale-seed conflict fails LOUDLY in the "
        "container, not just in a unit test"
    )


# ---------------------------------------------------------------------------
# The marker's LIFECYCLE across journeys (issue #2586, round 2)
# ---------------------------------------------------------------------------
#
# `.pocketshell-fixture-aplexer-live` is per-user state in a container the
# WHOLE app2 journey suite shares — #2474 runs the set unfiltered, in one
# process, against one fixture — and nothing expires it. So a journey that
# opts into live mode leaves the marker behind for every journey after it, and
# a later journey that seeds `.pocketshell-fixture-aplexer.json` hits the
# deliberate rc-78 mode conflict: the app receives NO listing, and what the
# author sees is three 60-second `ComposeTimeoutException`s that name nothing.
# (Observed, not theorised: 3 of J02's 4 tests, three minutes of lane time.)
#
# The fix is that the deterministic arm defends itself instead of trusting the
# previous journey's teardown. These two tests pin that as a property of the
# whole class of journeys — every file that seeds, and every file that opts in
# — rather than of the three that exist today.

APLEXER_SEED_NAME = ".pocketshell-fixture-aplexer.json"
LIVE_MARKER_NAME = ".pocketshell-fixture-aplexer-live"
ANDROID_TEST_ROOT = REPO_ROOT / "app2" / "src" / "androidTest"

_CONST_RE = re.compile(r'const val (\w+)\s*=\s*"\\?\$HOME/([^"]+)"')
_RM_RE = re.compile(r'"rm -f ([^"]*)"')
_TOUCH_RE = re.compile(r'"touch ([^"]*)"')
#: A journey MANAGES the deterministic seed if it writes it — either through
#: ``AgentsFixture.writeFile(CONST, …)`` (possibly wrapped onto the next line)
#: or a shell redirect inside an ``exec`` string. Keyed on the COMMAND, never on
#: the filename appearing anywhere in the file: J12UsagePanelJourney names the
#: seed only in a KDoc paragraph and an assertion message and seeds nothing, and
#: a guard that fires on documentation teaches people to add pointless cleanup
#: calls to silence it — worse than the bug it looks for.
_WRITE_FILE_RE = re.compile(r"writeFile\(\s*([A-Za-z_]\w*)")
_REDIRECT_CONST_RE = re.compile(r">\s*\$([A-Za-z_]\w*)")
_REDIRECT_PATH_RE = re.compile(r">\s*(\S*\.pocketshell-fixture-[\w.-]+)")


def _fixture_paths_in(command_args: str, consts: dict[str, str]) -> set[str]:
    """Resolve `$CONST`/literal tokens of one shell command to `$HOME` names."""
    resolved: set[str] = set()
    for token in command_args.split():
        name = consts.get(token.lstrip("$"))
        resolved.add(name if name else token.rsplit("/", 1)[-1])
    return resolved


def _journey_sources() -> list[tuple[Path, str, dict[str, str]]]:
    sources = []
    for path in sorted(ANDROID_TEST_ROOT.rglob("*.kt")):
        text = path.read_text(encoding="utf-8")
        sources.append((path, text, dict(_CONST_RE.findall(text))))
    return sources


def _seed_writes_in(text: str, consts: dict[str, str]) -> set[str]:
    """`$HOME` names this source WRITES, by command — never by mention."""
    written: set[str] = set()
    for name in _WRITE_FILE_RE.findall(text):
        resolved = consts.get(name)
        if resolved:
            written.add(resolved)
    for name in _REDIRECT_CONST_RE.findall(text):
        resolved = consts.get(name)
        if resolved:
            written.add(resolved)
    for path in _REDIRECT_PATH_RE.findall(text):
        written.add(path.rsplit("/", 1)[-1])
    return written


def test_every_journey_that_seeds_the_aplexer_file_clears_the_live_marker() -> None:
    """Deterministic-arm journeys must remove the marker, not hope nobody set it.

    This is the check that would have caught the round-1 defect — and did catch
    J15TerminalScrollJourney the first time `main` moved under it.

    "Manages the seed" means the source WRITES it (`writeFile`/redirect) or
    REMOVES it (`rm -f`), read out of the commands themselves with `$CONST`
    tokens resolved. Both directions belong: a journey that writes the seed
    hits the rc-78 conflict outright, and a journey that deletes the seed to
    get a clean deterministic listing would silently get a LIVE one instead.
    A file that merely NAMES the seed in prose is not in scope, deliberately.
    """
    managing = []
    for path, text, consts in _journey_sources():
        removed: set[str] = set()
        for args in _RM_RE.findall(text):
            removed |= _fixture_paths_in(args, consts)
        written = _seed_writes_in(text, consts)
        if APLEXER_SEED_NAME in (removed | written):
            managing.append((path, removed, written))
    assert managing, "no androidTest source manages the aplexer seed any more"
    for path, removed, written in managing:
        verb = "seeds" if APLEXER_SEED_NAME in written else "clears"
        assert LIVE_MARKER_NAME in removed, (
            f"{path.relative_to(REPO_ROOT)} {verb} {APLEXER_SEED_NAME} but never "
            f"removes {LIVE_MARKER_NAME}. A journey that opted into live mode "
            "leaves that marker behind in the shared fixture container "
            "(#2474 runs the suite unfiltered, one process, one fixture), and "
            "marker + seed is a deliberate rc-78 mode conflict: this journey "
            "would get no listing at all and fail as bare 60-second Compose "
            "timeouts. Add it to the `rm -f` this journey already runs."
        )


def test_every_journey_that_opts_into_live_mode_clears_the_seed() -> None:
    """The other direction: opting in must not inherit a leftover seed.

    Same conflict, same rc 78, arrived at from the other side — a journey that
    `touch`es the marker while an earlier journey's seed file is still there.
    """
    for path, text, consts in _journey_sources():
        touched: set[str] = set()
        for args in _TOUCH_RE.findall(text):
            touched |= _fixture_paths_in(args, consts)
        if LIVE_MARKER_NAME not in touched:
            continue
        removed: set[str] = set()
        for args in _RM_RE.findall(text):
            removed |= _fixture_paths_in(args, consts)
        assert APLEXER_SEED_NAME in removed, (
            f"{path.relative_to(REPO_ROOT)} opts into live enumeration but "
            f"never removes {APLEXER_SEED_NAME}. An earlier journey's seed is "
            "still in the shared container, and marker + seed is a deliberate "
            "rc-78 mode conflict — clear the seed in the same command that "
            "writes the marker."
        )


def test_a_prose_only_mention_of_the_seed_does_not_demand_cleanup() -> None:
    """The seeding predicate keys on the COMMAND, never on the filename in text.

    Round 2 used a bare substring over the whole source, which put
    `J12UsagePanelJourney` — the journey quarantined under this very issue — in
    scope for naming the seed in a KDoc paragraph and an assertion message
    while seeding nothing. Forcing a meaningless `rm -f` into it to satisfy a
    guard is worse than the bug the guard looks for: it teaches the next author
    that cleanup calls are how you silence CI. Kept symmetric with the
    `touch`-keyed sibling check.
    """
    consts = {"APLEXER_FILE": APLEXER_SEED_NAME}
    prose = (
        "/** reads ~/.pocketshell-fixture-aplexer.json instead of enumerating */\n"
        'assertNotNull("… ~/.pocketshell-fixture-aplexer.json …", appRow)\n'
    )
    assert _seed_writes_in(prose, consts) == set(), (
        "a KDoc/assertion-message mention must not read as a write"
    )
    write_file = (
        "AgentsFixture.writeFile(\n"
        "    APLEXER_FILE,\n"
        '    """{"sessions": []}""",\n'
        ")\n"
    )
    assert APLEXER_SEED_NAME in _seed_writes_in(write_file, consts)
    redirect = 'AgentsFixture.exec("printf x > $APLEXER_FILE")'
    assert APLEXER_SEED_NAME in _seed_writes_in(redirect, consts)
    literal_redirect = 'exec("printf x > /home/testuser/.pocketshell-fixture-aplexer.json")'
    assert APLEXER_SEED_NAME in _seed_writes_in(literal_redirect, {})
