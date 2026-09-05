"""Behavioural contract of the BUNDLED aplexer binary (issue #2543).

``test_aplexer_resolution.py`` proves *which* ``a`` we run. This file proves
*what that ``a`` does* — and it exists because the version string provably
cannot answer that question.

Why a version check is not enough
---------------------------------

While #2543 was in review, aplexer's ``main`` sat 143 commits past the
``v0.1.1`` tag while STILL self-reporting ``a --version`` -> ``0.1.1``. The
published 0.1.1 wheel and the build every PocketShell host actually ran were
therefore indistinguishable by version, and pinning 0.1.1 under the D22 hard
cut (the bundled copy is the ONLY copy) would have silently DOWNGRADED every
host. Two post-0.1.1 fixes the real create-agent path depends on were missing:

* ``ee4b957`` — profile ``executable`` override (argv[0]) for Codex variants.
  0.1.1 accepts the key and then IGNORES it, so
  ``[profiles.zcodex] engine="codex", executable="zcodex"`` resolved argv[0]
  to ``codex`` and ``pocketshell sessions create --backend aplexer --engine
  codex --profile zcodex`` died with
  ``a: command is not executable or was not found in PATH: codex``.
* ``d13ecb2`` — preserve the provider environment for plain ``shell``
  launches. 0.1.1 stripped 71 provider vars from every shell session.

So these tests pin BEHAVIOUR, against the real bundled binary, with their own
config fixture (``APLEXER_CONFIG``) — never the maintainer's personal
``~/.config/aplexer/config.toml``, which would make them pass on exactly one
machine. Every assertion here was verified to fail on published aplexer 0.1.1
and pass on 0.1.2; a future pin that regresses fails loudly instead of
shipping a silent downgrade.

They run in the ``Python utility tests`` job (``tests.yml``), on Linux, with
no Docker service or fixture port — the same gate the rest of this suite uses.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import time
import tomllib
import uuid
from pathlib import Path
from typing import Any, Sequence

import pytest

from pocketshell import aplexer as _aplexer

PYPROJECT = Path(__file__).resolve().parents[1] / "pyproject.toml"

pytestmark = pytest.mark.skipif(
    sys.platform != "linux",
    reason="aplexer publishes Linux wheels only; the pin is marked sys_platform=='linux'",
)

# Exactly the `a` surface pocketshell drives, read off the call sites:
#   sessions.aplexer_start_argv / _create_on_aplexer  -> start
#   sessions._aplexer_snapshot / session_enum         -> snapshot, list
#   engines.py / profiles.py                          -> engines, profiles
#   agents.py::launch_agent shim                      -> launch-spec
#   sessions.py attach / kill                         -> attach, kill
# A future pin that drops any of these (or renames a flag) breaks the CLI at
# runtime with no compile-time signal, so assert the surface exists.
REQUIRED_SURFACE: tuple[tuple[str, tuple[str, ...]], ...] = (
    ("start", ("--workspace", "--tag", "--engine", "--profile")),
    ("snapshot", ()),
    ("list", ()),
    ("engines", ()),
    ("profiles", ()),
    ("launch-spec", ("--engine", "--cwd", "--profile", "--no-skip-permissions")),
    ("attach", ("--workspace", "--tag")),
    ("kill", ("--workspace", "--tag")),
)


def bundled_a() -> str:
    """The pinned, bundled ``a`` — never a host copy, never PATH.

    Fails (does not skip) when it is missing: on Linux an absent bundled
    console-script is a packaging-integrity error, which is exactly the class
    of problem #2543 is about.
    """
    report = _aplexer.resolve_a({"PATH": ""})
    if report.path is None:
        pytest.fail(
            "no bundled aplexer console-script next to the interpreter; "
            f"tried {report.tried}"
        )
    expected = str(Path(sys.executable).parent / "a")
    assert report.path == expected, (
        "the binary under contract test must be the BUNDLED one "
        f"({expected}), got {report.path} via {report.source}"
    )
    return report.path


def _pinned_version() -> str:
    data = tomllib.loads(PYPROJECT.read_text(encoding="utf-8"))
    reqs = [
        r
        for r in data["project"]["dependencies"]
        if r.split(";")[0].strip().startswith("aplexer")
    ]
    assert len(reqs) == 1, f"expected exactly one aplexer requirement, got {reqs}"
    return reqs[0].split(";")[0].strip().split("==", 1)[1]


@pytest.fixture
def aplexer_fixture(tmp_path: Path):
    """A throwaway aplexer config + a minimal env that runs against it.

    ``APLEXER_CONFIG`` is aplexer's own explicit config override
    (``aplexer/src/lib.rs``), so the contract is asserted against OUR fixture
    profiles, not whatever the developer has in ``~/.config/aplexer``.

    ``PATH`` holds the system dirs only — no ``a``, no ``aplexer`` — so a host
    copy cannot answer for the bundled one, and ``XDG_RUNTIME_DIR`` is
    deliberately absent: the autouse conftest fixture points it deep under the
    pytest tmp root, and aplexer's ``$XDG_RUNTIME_DIR/aplexer/sessions/<uuid>/
    control.sock`` then exceeds the 108-byte AF_UNIX limit, killing the worker
    for reasons unrelated to this contract.
    """

    class Fixture:
        def __init__(self) -> None:
            self.binary = bundled_a()
            self.config = tmp_path / "aplexer-config.toml"
            self.home = tmp_path / "aplexer-home"
            self.workspace = tmp_path / "ws"
            for directory in (self.home, self.workspace):
                directory.mkdir(exist_ok=True)
            self.env = {
                "PATH": "/usr/bin:/bin",
                "HOME": str(self.home),
                "TERM": "dumb",
                "APLEXER_CONFIG": str(self.config),
            }

        def write_config(self, body: str) -> None:
            self.config.write_text(body, encoding="utf-8")

        def run(self, args: Sequence[str], *, timeout: float = 60) -> Any:
            if not self.config.exists():
                self.write_config("version = 1\n")
            return subprocess.run(
                [self.binary, *args],
                capture_output=True,
                text=True,
                env=dict(self.env),
                cwd=str(self.workspace),
                timeout=timeout,
            )

        def json(self, args: Sequence[str], *, timeout: float = 60) -> Any:
            completed = self.run(["--json", *args], timeout=timeout)
            assert completed.returncode == 0, (
                f"`a --json {' '.join(args)}` exited {completed.returncode}: "
                f"{completed.stderr or completed.stdout}"
            )
            return json.loads(completed.stdout)

    return Fixture()


# ---------------------------------------------------------------------------
# ee4b957 — the profile `executable` override (the reviewer's blocker)
# ---------------------------------------------------------------------------


def test_bundled_aplexer_honours_the_profile_executable_override(aplexer_fixture) -> None:
    """argv[0] comes from the profile's ``executable``, engine args survive.

    Fixture shape of the maintainer's real ``[profiles.zcodex]``: the builtin
    ``codex`` engine plus an ``executable`` override. Published 0.1.1 resolves
    argv[0] to ``codex`` here (verified); 0.1.2 resolves the override.
    """
    aplexer_fixture.write_config(
        "version = 1\n"
        "[profiles.ps2543codex]\n"
        'engine = "codex"\n'
        'executable = "ps2543-codex-variant"\n'
    )

    spec = aplexer_fixture.json(
        ["launch-spec", "--engine", "codex", "--profile", "ps2543codex"]
    )

    argv = spec["argv"]
    assert argv[0] == "ps2543-codex-variant", (
        "the bundled aplexer ignored the profile `executable` override — this "
        "is aplexer ee4b957, missing from published 0.1.1, and it breaks "
        "`sessions create --engine codex --profile <codex-variant>` with "
        "\"command is not executable or was not found in PATH: codex\". "
        f"Full argv: {argv}"
    )
    assert "-c" in argv and "check_for_update_on_startup=false" in argv, (
        "`executable` overrides ONLY argv[0]; the engine's own default "
        f"arguments must survive. Full argv: {argv}"
    )
    assert "--dangerously-bypass-approvals-and-sandbox" in argv, (
        "the engine's skip-permissions argv must still be appended by default"
    )


def test_bundled_aplexer_profiles_json_exposes_the_executable_field(
    aplexer_fixture,
) -> None:
    """``a --json profiles`` reports ``executable``, so the field is real.

    Published 0.1.1 omits the key entirely from every profile record, which is
    what made the downgrade invisible: the config parses, the profile lists,
    and only the resolved argv is wrong.
    """
    aplexer_fixture.write_config(
        "version = 1\n"
        "[profiles.ps2543codex]\n"
        'engine = "codex"\n'
        'executable = "ps2543-codex-variant"\n'
    )

    profiles = aplexer_fixture.json(["profiles"])

    assert "ps2543codex" in profiles, profiles
    record = profiles["ps2543codex"]
    assert "executable" in record, (
        "`a --json profiles` does not expose `executable`; the bundled build "
        f"predates aplexer ee4b957. Record: {record}"
    )
    assert record["executable"] == "ps2543-codex-variant"


def test_bundled_aplexer_start_launches_the_profile_executable(aplexer_fixture) -> None:
    """End-to-end: a real ``a start`` runs the OVERRIDE, not the engine default.

    The reviewer's blocker manifested at ``a start``, not at ``launch-spec``,
    so reproduce it there on the real binaries. The fixture engine's own
    command is a binary that deliberately does not exist, so a build without
    ee4b957 fails with the maintainer's exact error shape
    (``a: command is not executable or was not found in PATH: …``) — verified
    against published 0.1.1 — while a correct build runs the override and
    leaves a marker file behind.
    """
    marker = aplexer_fixture.workspace.parent / "ps2543-executable-ran"
    shim = aplexer_fixture.workspace.parent / "ps2543-shim"
    shim.write_text(
        "#!/bin/sh\n"
        f'echo ran > "{marker}"\n'
        "exec sleep 60\n",
        encoding="utf-8",
    )
    shim.chmod(0o755)
    aplexer_fixture.write_config(
        "version = 1\n"
        "[engines.ps2543engine]\n"
        'command = ["ps2543-engine-default-absent"]\n'
        "[profiles.ps2543profile]\n"
        'engine = "ps2543engine"\n'
        f'executable = "{shim}"\n'
    )
    tag = f"ps2543c-{uuid.uuid4().hex[:8]}"

    started = aplexer_fixture.run(
        [
            "--json", "start",
            "--workspace", str(aplexer_fixture.workspace),
            "--tag", tag,
            "--engine", "ps2543engine",
            "--profile", "ps2543profile",
        ]
    )
    try:
        assert started.returncode == 0, (
            "`a start` refused the profile `executable` override: "
            f"{started.stderr.strip() or started.stdout.strip()}"
        )
        record = json.loads(started.stdout)
        assert record.get("command") == [str(shim)], (
            "the started session's command must be the profile override, not "
            f"the engine default: {record.get('command')}"
        )
        assert record.get("phase") == "running", (
            f"the worker did not come up: {record.get('phase')} / {record.get('error')}"
        )
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline and not marker.exists():
            time.sleep(0.1)
        assert marker.exists(), (
            "the overridden executable never ran — `a start` reported success "
            "without launching the profile's binary"
        )
    finally:
        aplexer_fixture.run(
            ["kill", "--workspace", str(aplexer_fixture.workspace), "--tag", tag]
        )


# ---------------------------------------------------------------------------
# d13ecb2 — provider environment preserved for plain shell launches
# ---------------------------------------------------------------------------


def test_bundled_aplexer_preserves_provider_env_for_shell_launches(
    aplexer_fixture,
) -> None:
    """A plain ``shell`` session keeps its ambient/provider environment.

    Published 0.1.1 unset 71 provider vars for the literal ``shell`` engine
    (verified), so every non-agent session created through a 0.1.1 bundle
    would silently lose them.
    """
    spec = aplexer_fixture.json(["launch-spec", "--engine", "shell"])

    assert spec["env_unset"] == [], (
        "the bundled aplexer strips the provider environment from plain "
        "`shell` launches; this is aplexer d13ecb2, missing from published "
        f"0.1.1. Stripped: {spec['env_unset']}"
    )


def test_bundled_aplexer_still_strips_provider_env_for_agent_engines(
    aplexer_fixture,
) -> None:
    """…and the strip policy still applies to agent engines (no over-correction)."""
    spec = aplexer_fixture.json(["launch-spec", "--engine", "codex"])

    unset = set(spec["env_unset"])
    assert {"OPENAI_API_KEY", "ANTHROPIC_API_KEY"} <= unset, (
        "agent engines must still drop provider API keys so subscription auth "
        f"is used; unset only {sorted(unset)}"
    )


# ---------------------------------------------------------------------------
# The `a` surface pocketshell actually drives
# ---------------------------------------------------------------------------


@pytest.mark.parametrize("subcommand,flags", REQUIRED_SURFACE)
def test_bundled_aplexer_exposes_the_surface_pocketshell_drives(
    aplexer_fixture, subcommand: str, flags: tuple[str, ...]
) -> None:
    """Every subcommand + flag the CLI invokes exists in the bundled build.

    ``launch-spec`` is hidden from ``a --help`` (it is aplexer's integration
    point for this CLI), so probe each subcommand directly rather than
    scraping the top-level command list.
    """
    completed = aplexer_fixture.run([subcommand, "--help"])

    assert completed.returncode == 0, (
        f"the bundled aplexer has no `a {subcommand}`; pocketshell calls it. "
        f"{completed.stderr or completed.stdout}"
    )
    help_text = completed.stdout
    missing = [flag for flag in flags if flag not in help_text]
    assert not missing, (
        f"`a {subcommand}` no longer accepts {missing}; pocketshell passes "
        f"them. Help:\n{help_text}"
    )


def test_bundled_aplexer_reports_the_pinned_version(aplexer_fixture) -> None:
    """Sanity only: the bundled binary IS the pinned wheel.

    Deliberately last and deliberately weak — the whole reason this file
    exists is that this assertion passed for a build missing the two fixes
    above. It guards "did the wheel install at all", nothing more; the
    behavioural tests are the contract.
    """
    completed = aplexer_fixture.run(["--version"])

    assert completed.returncode == 0, completed.stderr
    assert completed.stdout.split() == ["a", _pinned_version()], (
        f"bundled `a --version` is {completed.stdout.strip()!r}, pyproject "
        f"pins {_pinned_version()}"
    )


def test_contract_env_never_reads_the_developers_aplexer_config(
    aplexer_fixture,
) -> None:
    """The fixture config is authoritative — not ``~/.config/aplexer``.

    A contract test that silently fell back to the maintainer's own profiles
    would pass on exactly one machine, which is what the reviewer warned
    against.
    """
    aplexer_fixture.write_config("version = 1\n")

    profiles = aplexer_fixture.json(["profiles"])

    assert profiles == {}, (
        "the bundled `a` read profiles from somewhere other than "
        f"APLEXER_CONFIG: {profiles}"
    )
    assert aplexer_fixture.env["HOME"] != os.path.expanduser("~")
