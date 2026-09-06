"""Resolution of the ``a`` (aplexer) binary shipped WITH the pocketshell CLI.

Issue #2543. aplexer is now a pinned, Linux-marked hard dependency
(``pyproject.toml``), so ``uv tool install pocketshell`` also delivers the
``a`` and ``aplexer`` console-scripts into the SAME ``bin`` directory as the
interpreter running pocketshell. These tests pin the resolution ORDER that
makes that install usable from the app's non-interactive SSH command, where
``~/bin`` is not on ``PATH``:

    APLEXER_BIN  >  bundled copy next to sys.executable   (and NOTHING else)

The ``PATH`` lookup is HARD-CUT (D22), exactly like the pinned ``quse``: a
separately-installed ``a`` — host package, `cargo install`, `~/bin` copy —
must never be load-bearing, so resolution fails loud instead of falling back
to it.

The reported failure (#2543) was a pure PATH problem: ``which_a`` only ever
did ``shutil.which("a", path=env["PATH"])``, so a correctly installed aplexer
that simply was not on the non-interactive PATH produced
"`a` (aplexer) is not installed on this host".
"""

from __future__ import annotations

import json
import subprocess
import sys
import tomllib
import uuid
from pathlib import Path
from unittest.mock import patch

import pytest

from pocketshell import aplexer as _aplexer

PYPROJECT = Path(__file__).resolve().parents[1] / "pyproject.toml"


def _fake_interpreter_dir(tmp_path: Path, *, with_a: bool) -> Path:
    """A venv-shaped ``bin`` dir; optionally holding a bundled ``a``."""
    bin_dir = tmp_path / "venv" / "bin"
    bin_dir.mkdir(parents=True)
    (bin_dir / "python").write_text("#!/bin/sh\n")
    if with_a:
        for name in ("a", "aplexer"):
            binary = bin_dir / name
            binary.write_text("#!/bin/sh\n")
            binary.chmod(0o755)
    return bin_dir


# ---------------------------------------------------------------------------
# Reported defect (#2543): a bundled aplexer that is not on PATH
# ---------------------------------------------------------------------------


def test_bundled_a_resolves_when_path_has_no_aplexer(tmp_path: Path) -> None:
    """RED before #2543: the app's non-interactive SSH PATH has no ``a``.

    Reproduces the maintainer's report verbatim: aplexer IS installed (here,
    next to the interpreter, exactly where the pinned dependency puts it) but
    the process ``PATH`` — the app's non-interactive SSH PATH, which does not
    contain the install dir — has no ``a`` anywhere on it. Resolution must
    still find the bundled copy.
    """
    bin_dir = _fake_interpreter_dir(tmp_path, with_a=True)
    empty_path_dir = tmp_path / "empty"
    empty_path_dir.mkdir()

    with patch.object(sys, "executable", str(bin_dir / "python")):
        resolved = _aplexer.which_a({"PATH": str(empty_path_dir)})

    assert resolved == str(bin_dir / "a"), (
        "#2543 symptom: a bundled `a` that is not on the non-interactive PATH "
        "must still resolve"
    )


def test_bundled_worker_ships_next_to_bundled_a(tmp_path: Path) -> None:
    """The WORKER binary must resolve too, not just ``a`` (#2543 gap 2).

    ``aplexer/src/lib.rs::worker_executable`` looks for the ``aplexer`` worker
    as a sibling of ``current_exe`` (else bare ``aplexer`` on PATH), so a
    resolved ``a`` with no sibling worker cannot start a session. The pinned
    wheel ships both console-scripts into the same ``bin`` dir, so the sibling
    always exists; assert the resolver reports it.
    """
    bin_dir = _fake_interpreter_dir(tmp_path, with_a=True)

    with patch.object(sys, "executable", str(bin_dir / "python")):
        report = _aplexer.resolve_a({"PATH": ""})

    assert report.path == str(bin_dir / "a")
    assert report.worker == str(bin_dir / "aplexer")


def test_bundled_a_without_sibling_worker_is_a_packaging_integrity_failure(
    tmp_path: Path,
) -> None:
    """A half-installed bundle (``a``, no worker) must NOT resolve (#2553 gap 1).

    ``aplexer/src/lib.rs::worker_executable`` looks for the ``aplexer`` worker
    next to ``current_exe`` and, failing that, runs a BARE ``aplexer`` off
    ``PATH``. So handing a worker-less ``a`` back to the caller re-opens the
    separate-install hole #2543 closed one level down: the CLI would be pinned
    but its worker would not. The pinned wheel always ships both binaries, so
    "``a`` without its worker" is a packaging-integrity failure and must fail
    the same loud, candidate-naming way an absent ``a`` does.
    """
    bin_dir = _fake_interpreter_dir(tmp_path, with_a=True)
    (bin_dir / "aplexer").unlink()

    with patch.object(sys, "executable", str(bin_dir / "python")):
        report = _aplexer.resolve_a({"PATH": ""})
        resolved = _aplexer.which_a({"PATH": ""})

    assert report.path is None, (
        "an `a` whose sibling `aplexer` worker is missing must not be handed "
        "out — it would start with an unpinned worker off PATH"
    )
    assert report.source is None
    assert report.worker is None
    assert resolved is None
    tried = " ".join(report.tried)
    assert str(bin_dir / "a") in tried, "the failure must name the half-installed candidate"
    assert "worker" in tried, f"the failure must say WHY it was rejected: {report.tried}"


def test_half_installed_bundle_never_falls_back_to_a_path_copy(tmp_path: Path) -> None:
    """The integrity failure is a hard stop, not a reason to search PATH (D22).

    Rejecting the half-installed bundle must not quietly promote a complete
    ``a``/``aplexer`` pair sitting on ``PATH`` — that separately-installed copy
    being load-bearing is the exact bug #2543 existed to kill.
    """
    bin_dir = _fake_interpreter_dir(tmp_path, with_a=True)
    (bin_dir / "aplexer").unlink()
    path_dir = tmp_path / "elsewhere"
    path_dir.mkdir()
    for name in ("a", "aplexer"):
        binary = path_dir / name
        binary.write_text("#!/bin/sh\n")
        binary.chmod(0o755)

    with patch.object(sys, "executable", str(bin_dir / "python")):
        report = _aplexer.resolve_a({"PATH": str(path_dir)})

    assert report.path is None
    assert str(path_dir) not in " ".join(report.tried)


def test_half_installed_second_candidate_dir_is_also_rejected(tmp_path: Path) -> None:
    """The check covers BOTH bundled candidate dirs, not just the first.

    ``_bundled_bin_dirs`` keeps a second, resolved-symlink candidate; a check
    written into only the first branch would let the same worker-less ``a``
    through on the venv layout where ``bin/python`` is a real file.
    """
    real_dir = tmp_path / "real" / "bin"
    real_dir.mkdir(parents=True)
    (real_dir / "python").write_text("#!/bin/sh\n")
    (real_dir / "a").write_text("#!/bin/sh\n")
    (real_dir / "a").chmod(0o755)
    link_dir = tmp_path / "link" / "bin"
    link_dir.mkdir(parents=True)
    (link_dir / "python").symlink_to(real_dir / "python")

    with patch.object(sys, "executable", str(link_dir / "python")):
        report = _aplexer.resolve_a({"PATH": ""})

    assert report.path is None, "the resolved-dir candidate needs the same worker check"
    assert "worker" in " ".join(report.tried)


def test_aplexer_bin_override_is_exempt_from_the_worker_check(tmp_path: Path) -> None:
    """``APLEXER_BIN`` still resolves whatever it points at (#2553 judgment call).

    The integrity check is about the BUNDLE — "the pinned wheel ships both
    console-scripts, so one without the other means a broken install, go
    reinstall". ``APLEXER_BIN`` is the one explicit debug/test override; its
    whole contract is "run exactly this binary", the reinstall advice does not
    apply to it, and the helper suite's stub ``a`` (``conftest.install_fake_a``)
    has no sibling worker by design.
    """
    override = tmp_path / "custom-a"
    override.write_text("#!/bin/sh\n")
    override.chmod(0o755)

    report = _aplexer.resolve_a({"APLEXER_BIN": str(override), "PATH": ""})

    assert report.path == str(override)
    assert report.worker is None


# ---------------------------------------------------------------------------
# The half-installed bundle reaching the USER (#2553 gap 1)
# ---------------------------------------------------------------------------


def test_half_installed_bundle_fails_create_with_the_candidate_naming_message(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    """End of the chain: a worker-less bundle must produce #2543's message.

    Without the check, `sessions create --backend aplexer` would EXEC the
    worker-less `a`, which starts a bare `aplexer` off PATH (unpinned worker)
    or dies with a low-level worker-startup error. The user must instead get
    the packaging-integrity message that names every candidate, so "reinstall
    pocketshell" is an obvious next step.
    """
    from click.testing import CliRunner

    from pocketshell.sessions import sessions_group

    bin_dir = _fake_interpreter_dir(tmp_path, with_a=True)
    (bin_dir / "aplexer").unlink()
    monkeypatch.delenv("APLEXER_BIN", raising=False)

    with patch.object(sys, "executable", str(bin_dir / "python")):
        result = CliRunner().invoke(
            sessions_group, ["create", "work", "--backend", "aplexer", "--json"]
        )

    assert result.exit_code == 127, result.output
    message = json.loads(result.output)["error"]
    assert "could not resolve the `a` (aplexer) binary" in message
    assert "uv tool install --force pocketshell" in message
    assert str(bin_dir / "a") in message, "the message must name the broken candidate"
    assert "worker" in message, f"and say what was wrong with it: {message}"


# ---------------------------------------------------------------------------
# Resolution order
# ---------------------------------------------------------------------------


def test_aplexer_bin_overrides_bundled_copy(tmp_path: Path) -> None:
    """``APLEXER_BIN`` still wins over everything (#2543 AC)."""
    bin_dir = _fake_interpreter_dir(tmp_path, with_a=True)
    override = tmp_path / "custom-a"
    override.write_text("#!/bin/sh\n")
    override.chmod(0o755)

    with patch.object(sys, "executable", str(bin_dir / "python")):
        report = _aplexer.resolve_a({"APLEXER_BIN": str(override), "PATH": str(bin_dir)})

    assert report.path == str(override)
    assert report.source == "APLEXER_BIN"


def test_bundled_copy_wins_over_path(tmp_path: Path) -> None:
    """The pinned bundled copy beats a host-level ``a`` on PATH.

    Same rule the pinned `quse` follows (`usage.py::_resolve_quse_binary`):
    a host upgrade must not silently shadow the version pocketshell pinned.
    """
    bin_dir = _fake_interpreter_dir(tmp_path, with_a=True)
    path_dir = tmp_path / "elsewhere"
    path_dir.mkdir()
    decoy = path_dir / "a"
    decoy.write_text("#!/bin/sh\n")
    decoy.chmod(0o755)

    with patch.object(sys, "executable", str(bin_dir / "python")):
        report = _aplexer.resolve_a({"PATH": str(path_dir)})

    assert report.path == str(bin_dir / "a")
    assert report.source == "bundled"


def test_path_only_aplexer_is_never_used(tmp_path: Path) -> None:
    """#2543 AC: an ``a`` present ONLY on PATH must NOT be used.

    This is the "no separate install" property stated as an assertion. The
    PATH lookup is hard-cut (D22): a separately-installed aplexer — host
    package, ``cargo install``, ``~/bin`` copy — is never load-bearing, so a
    missing bundled copy fails loud instead of silently using it.
    """
    bin_dir = _fake_interpreter_dir(tmp_path, with_a=False)
    path_dir = tmp_path / "elsewhere"
    path_dir.mkdir()
    local_build = path_dir / "a"
    local_build.write_text("#!/bin/sh\n")
    local_build.chmod(0o755)
    (path_dir / "aplexer").write_text("#!/bin/sh\n")
    (path_dir / "aplexer").chmod(0o755)

    with patch.object(sys, "executable", str(bin_dir / "python")):
        # `which_a` first, deliberately: it is the pre-existing API, so this
        # assertion is a behavioural red on the unfixed code (which returned
        # the PATH copy), not an AttributeError on the new one.
        resolved = _aplexer.which_a({"PATH": str(path_dir)})
        assert resolved is None, (
            "a separately-installed `a` on PATH must not be resolved — "
            "aplexer ships WITH the CLI"
        )
        report = _aplexer.resolve_a({"PATH": str(path_dir)})

    assert report.path is None
    assert report.source is None


def test_which_a_has_no_path_lookup_left(tmp_path: Path) -> None:
    """D22 hard cut: ``shutil.which`` is GONE, not hidden behind a branch."""
    source = Path(_aplexer.__file__).read_text(encoding="utf-8")
    code = "\n".join(
        line for line in source.splitlines() if not line.lstrip().startswith("#")
    )
    body = code.split('"""', 2)[-1]  # skip the module docstring, which cites it
    assert "shutil" not in body, "the PATH lookup must be deleted, not conditioned"


def test_unresolvable_reports_every_candidate_tried(tmp_path: Path) -> None:
    """#2543 AC: the failure must name the candidate paths it tried."""
    bin_dir = _fake_interpreter_dir(tmp_path, with_a=False)
    empty = tmp_path / "empty"
    empty.mkdir()

    with patch.object(sys, "executable", str(bin_dir / "python")):
        report = _aplexer.resolve_a({"PATH": str(empty)})

    assert report.path is None
    assert report.source is None
    tried = " ".join(report.tried)
    assert str(bin_dir / "a") in tried
    assert "APLEXER_BIN" in tried
    assert str(empty) not in tried, "PATH is not a candidate any more (D22 hard cut)"


def test_resolved_dir_candidate_used_when_interpreter_is_a_symlink(
    tmp_path: Path,
) -> None:
    """Console-scripts live next to the UNRESOLVED ``sys.executable``…

    …but a layout where ``bin/python`` is a real file in the shared
    interpreter dir must still be covered. Mirrors the second candidate
    ``usage.py::_resolve_quse_binary`` keeps.
    """
    real_dir = tmp_path / "real" / "bin"
    real_dir.mkdir(parents=True)
    (real_dir / "python").write_text("#!/bin/sh\n")
    for name in ("a", "aplexer"):
        binary = real_dir / name
        binary.write_text("#!/bin/sh\n")
        binary.chmod(0o755)
    link_dir = tmp_path / "link" / "bin"
    link_dir.mkdir(parents=True)
    (link_dir / "python").symlink_to(real_dir / "python")

    with patch.object(sys, "executable", str(link_dir / "python")):
        report = _aplexer.resolve_a({"PATH": ""})

    assert report.path == str(real_dir / "a")


# ---------------------------------------------------------------------------
# Kill switches stay independent of resolution
# ---------------------------------------------------------------------------


def test_kill_switches_unaffected_by_bundled_resolution(tmp_path: Path) -> None:
    bin_dir = _fake_interpreter_dir(tmp_path, with_a=True)
    with patch.object(sys, "executable", str(bin_dir / "python")):
        assert _aplexer.which_a({"PATH": ""}) is not None
        assert _aplexer.enabled("sessions", {"POCKETSHELL_APLEXER": "0"}) is False
        assert _aplexer.enabled("sessions", {"POCKETSHELL_APLEXER_SESSIONS": "0"}) is False
        assert _aplexer.enabled("profiles", {"POCKETSHELL_APLEXER": "1"}) is True
        # A master kill switch does NOT make the binary un-resolvable; the
        # feature gate is what skips the probe (session_enum._probe_aplexer).
        assert _aplexer.run_json(["snapshot"], env={"POCKETSHELL_APLEXER": "0"},
                                 feature="sessions") is None


# ---------------------------------------------------------------------------
# Packaging: the pin, its marker, and the real installed console-scripts
# ---------------------------------------------------------------------------


def _aplexer_requirement() -> str:
    data = tomllib.loads(PYPROJECT.read_text(encoding="utf-8"))
    reqs = [r for r in data["project"]["dependencies"] if r.split(";")[0].strip().startswith("aplexer")]
    assert len(reqs) == 1, f"expected exactly one aplexer requirement, got {reqs}"
    return reqs[0]


def test_aplexer_is_pinned_exactly() -> None:
    """The pin is exact, like quse — a host upgrade must not reach us.

    Under the D22 hard cut the bundled wheel is the ONLY aplexer this CLI can
    run, so the pin decides what every host gets and an older published wheel
    is a silent DOWNGRADE, not merely stale:

    * 0.1.2, never 0.1.1 — 0.1.1 lacks the profile `executable` override and
      the shell provider-env fix, and `a --version` cannot distinguish the two
      builds (both report 0.1.1).
    * 0.1.3, never 0.1.2 — 0.1.2's `list_records` fails the whole registry
      scan on a session directory that has no `session.json` yet, which is the
      state every `a start` creates for 26-43 ms, so a session being created
      blanked the phone's aplexer session rows (aplexer#2 / aplexer#3).
    * 0.1.4, never 0.1.3 — 0.1.3 emits no `agent` field, so the session tree
      cannot say WHICH agent a session is running. `engine` cannot stand in
      for it: every session PocketShell creates is `engine: "shell"` with the
      agent launched by hand inside it (issue #2581).

    This assertion only guards the STRING. ``test_aplexer_contract.py`` pins
    the behaviours themselves against the bundled binary, which is the part
    a version comparison provably cannot do.
    """
    requirement = _aplexer_requirement()
    pin = requirement.split(";")[0].strip()
    assert pin == "aplexer==0.1.4", pin


def test_aplexer_dependency_marker_is_linux_only() -> None:
    """#2543 AC: ``pip install pocketshell`` still resolves on macOS.

    aplexer publishes manylinux wheels only (x86_64 + aarch64) and no sdist,
    so an UNMARKED hard dependency would make the package uninstallable on
    macOS/Windows. Marker check only — no macOS runner needed.
    """
    from packaging.markers import Marker

    marker_text = _aplexer_requirement().split(";", 1)[1].strip()
    marker = Marker(marker_text)

    assert marker.evaluate({"sys_platform": "darwin", "platform_machine": "arm64"}) is False
    assert marker.evaluate({"sys_platform": "win32", "platform_machine": "AMD64"}) is False
    assert marker.evaluate({"sys_platform": "linux", "platform_machine": "x86_64"}) is True
    assert marker.evaluate({"sys_platform": "linux", "platform_machine": "aarch64"}) is True


@pytest.mark.skipif(sys.platform != "linux", reason="aplexer ships Linux wheels only")
def test_pinned_aplexer_console_scripts_are_installed_next_to_interpreter() -> None:
    """The pin really does land BOTH binaries in the interpreter's bin dir.

    The analogue of ``test_usage.py``'s real-quse check: proves the packaging
    claim against the actual installed environment, not a fake tmp layout.
    """
    report = _aplexer.resolve_a({"PATH": ""})
    assert report.path is not None, (
        "the pinned aplexer console-script must be installed next to the "
        f"interpreter; tried {report.tried}"
    )
    assert report.source == "bundled"
    assert report.worker is not None, "the `aplexer` worker must ship alongside `a`"


@pytest.mark.skipif(sys.platform != "linux", reason="aplexer ships Linux wheels only")
def test_bundled_a_starts_a_session_with_no_aplexer_on_path(tmp_path: Path) -> None:
    """End-to-end on the REAL bundled binaries with a stripped PATH.

    This is the whole point of #2543: an environment whose PATH contains no
    aplexer binary at all (the app's non-interactive SSH PATH) must still be
    able to CREATE a session — which exercises the worker-binary sibling
    lookup too, not merely "the CLI was found". The session is killed and its
    whole registry (under a tmp HOME) is discarded with ``tmp_path``.
    """
    report = _aplexer.resolve_a({"PATH": ""})
    if report.path is None:  # pragma: no cover - packaging integrity
        pytest.fail(f"no bundled aplexer console-script; tried {report.tried}")
    assert report.path == str(Path(sys.executable).parent / "a"), (
        "the binary under test must be the BUNDLED one, not a host copy"
    )

    workspace = tmp_path / "ws"
    home = tmp_path / "aplexer-home"
    for directory in (workspace, home):
        directory.mkdir()
    tag = f"ps2543-{uuid.uuid4().hex[:8]}"
    # A PATH with the standard system dirs ONLY — no `a`, no `aplexer` — and a
    # throwaway HOME so the session registry dies with `tmp_path`.
    #
    # XDG_RUNTIME_DIR is deliberately NOT set (the autouse conftest fixture
    # points it deep under the pytest tmp root): aplexer puts its control
    # socket at $XDG_RUNTIME_DIR/aplexer/sessions/<uuid>/control.sock, and a
    # long tmp prefix blows past the 108-byte AF_UNIX path limit, so the
    # worker dies at startup for reasons that have nothing to do with #2543.
    # Unset, aplexer picks its own short runtime dir.
    env = {
        "PATH": "/usr/bin:/bin",
        "HOME": str(home),
        "TERM": "dumb",
    }

    started = subprocess.run(
        [report.path, "--json", "start", "--workspace", str(workspace), "--tag", tag],
        capture_output=True,
        text=True,
        env=env,
        timeout=60,
    )
    assert started.returncode == 0, (
        "`a start` failed with a PATH holding no aplexer binaries: "
        f"{started.stderr or started.stdout}"
    )
    record = json.loads(started.stdout)
    session_id = str(record.get("id") or "")
    assert session_id, f"no session id in {record}"
    assert record.get("phase") == "running", (
        f"the worker did not come up: {record.get('phase')} / {record.get('error')}"
    )

    try:
        listed = subprocess.run(
            [report.path, "--json", "list"],
            capture_output=True, text=True, env=env, timeout=60,
        )
        assert listed.returncode == 0, listed.stderr
        rows = json.loads(listed.stdout)
        assert any(str(row.get("tag")) == tag for row in rows), (
            f"session {tag} missing from `a list`"
        )
    finally:
        subprocess.run(
            [report.path, "kill", "--workspace", str(workspace), "--tag", tag],
            capture_output=True, text=True, env=env, timeout=60,
        )
