"""The kernel-level proof that every session carries its memory cap (#2562).

`#2613` narrowed `test_sessions_mem_cap.py` to the cap-resolution POLICY and
deleted the proof that lived beside it; this file restores that proof (the
release gate `scripts/check-cgroup-cap-proof.sh` pins its node, so deleting
it silently made every release validation impossible). Behaviour under proof
is unchanged: `pocketshell sessions create` resolves the cap with
`pocketshell.memcap` and hands it to `a start --memory`, and APLEXER applies
it in a cgroup-v2 systemd `--user` scope.

The oracle is the KERNEL: the created session's own cgroup must carry the
expected `memory.max`. Reading it needs a delegated cgroup-v2 systemd
`--user` scope, and no automated lane we own has one:

  * `Python utility tests (pocketshell)` runs on `ubuntu-latest`, where
    `a start --memory` fails with `spawn workload: Permission denied` because
    the runner's process tree sits outside `user@<uid>.service` (PR #2590);
  * `Integration tests (Docker)` runs the `agents` container, which has no
    user systemd at all — that is why the fixture passes `--mem none`;
  * `release-emulator-validation.yml`, which drives the release confidence
    gate, is ALSO `runs-on: ubuntu-latest`.

So the proof must skip in CI, and a skip nothing converts back into an
obligation is how a proof quietly becomes a no-op: where it cannot run, it
skips NAMING the missing capability (`probe_cgroup_delegation`), and
`scripts/check-cgroup-cap-proof.sh` turns that skip back into a failure on a
host that can — with `test_the_kernel_proof_can_only_be_skipped_through_that_one_helper`
guarding against a blanket skip parked on it.

The real-transport test drives the production CLI (`python -m pocketshell`)
against a throwaway aplexer instance (`APLEXER_CONFIG` / `APLEXER_STATE_DIR`
/ `APLEXER_RUNTIME_DIR`), never the maintainer's live sessions.
"""

from __future__ import annotations

import ast
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
from typing import Optional

import pytest

from pocketshell import memcap

#: Reason text for the platform guard, named so the decorator guard below
#: compares against a stable shape rather than a free-form string.
REASON_LINUX_ONLY = "aplexer ships Linux wheels only"

REAL_CAP = "512M"
REAL_CAP_BYTES = 512 * 1024**2


def write_cgroups_toml(directory: Path, body: str) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "cgroups.toml"
    path.write_text(body, encoding="utf-8")
    return path


def _real_runtime_dir() -> str:
    """The user's REAL XDG_RUNTIME_DIR — `systemd-run --user` needs its bus."""
    return f"/run/user/{os.getuid()}"


#: Set to ``required`` to turn every environment skip below into a FAILURE.
#: `scripts/check-cgroup-cap-proof.sh` sets it; that is what stops the only
#: kernel-level proof of the cap from quietly becoming a no-op everywhere.
CAP_PROOF_MODE_ENV = "POCKETSHELL_CGROUP_CAP_PROOF"

#: Messages aplexer itself emits when the HOST refuses containment (verified
#: against `aplexer/src/lib.rs::Cgroup::create` and `worker.rs`'s `spawn
#: workload`). A create that fails with one of these is an environment gap, not
#: a defect in the cap we computed — but only ever after the probe below has
#: already been consulted, and never in `required` mode.
HOST_REFUSED_CONTAINMENT = (
    "limits fail closed",
    "did not delegate",
    "spawn workload: permission denied",
    "failed to connect to user scope bus",
)


def host_refused_containment(detail: str) -> bool:
    """Whether a failed create is the HOST refusing containment.

    Deliberately a closed list of aplexer's own wordings: anything else — a
    wrong `--memory` value, a bad workspace, a broken CLI — must stay a
    failure. This is the last line of defence behind
    :func:`probe_cgroup_delegation`, not a general "create failed, never mind".
    """
    lowered = detail.lower()
    return any(signature in lowered for signature in HOST_REFUSED_CONTAINMENT)


@dataclass(frozen=True)
class DelegationProbe:
    """Whether this host can do what `a start --memory` needs, and why not."""

    available: bool
    #: Empty when available; otherwise names the MISSING CAPABILITY, so a CI
    #: log reader can tell "this environment cannot run it" from "this
    #: silently stopped running".
    missing: str = ""


def probe_cgroup_delegation() -> DelegationProbe:
    """Can a process here be moved into a delegated, memory-capped user scope?

    This mirrors what aplexer actually does, step for step, because a cheaper
    probe was wrong once already: the first version of this file only checked
    that `systemd-run --user --scope -p MemoryMax=…` EXITS 0, which a GitHub
    hosted runner does — and then `a start --memory` still failed there with
    ``spawn workload: Permission denied (os error 13)``, red CI on PR #2590.
    The step that fails on such a host is the LAST one: aplexer's workload
    writes its own pgid into the delegated scope's ``cgroup.procs`` from a
    pre_exec hook (``aplexer/src/worker.rs``), and cgroup-v2 requires write
    permission on the common ancestor of the writer's cgroup and the target.
    A runner whose agent lives outside ``user@<uid>.service`` does not have it.

    So the probe creates a real anchored scope, checks the memory controller
    really was delegated, and then MOVES A THROWAWAY CHILD into it — the exact
    operation that failed. Everything it creates is torn down here.
    """
    runtime = _real_runtime_dir()
    if not os.path.isdir(runtime):
        return DelegationProbe(
            False,
            f"no user runtime directory at {runtime} — systemd --user is not "
            "running for this uid, so no user scope can be created",
        )
    if shutil.which("systemd-run") is None:
        return DelegationProbe(False, "systemd-run is not installed")
    unit = f"pocketshell-capprobe-{os.getpid()}-{uuid.uuid4().hex[:8]}"
    env = {
        "PATH": "/usr/bin:/bin",
        "HOME": str(Path.home()),
        "XDG_RUNTIME_DIR": runtime,
    }
    anchor = subprocess.Popen(
        [
            "systemd-run", "--user", "--scope", "--collect", f"--unit={unit}",
            "-p", "Delegate=yes", "-p", "MemoryMax=64M",
            "--", "sleep", "30",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.PIPE,
        text=True,
        env=env,
    )
    try:
        cgroup = _wait_for_scope_cgroup(unit, env=env, anchor=anchor)
        if cgroup is None:
            detail = ""
            if anchor.poll() is not None and anchor.stderr is not None:
                detail = f": {anchor.stderr.read().strip()[:200]}"
            return DelegationProbe(
                False,
                "systemd --user could not create a delegated, memory-capped "
                f"scope{detail}",
            )
        if not (cgroup / "memory.max").exists():
            return DelegationProbe(
                False,
                "systemd did not delegate the memory controller (no "
                f"memory.max under {cgroup})",
            )
        moved = _can_move_a_process_into(cgroup)
        if moved is not None:
            return DelegationProbe(
                False,
                "cannot move a process into the delegated scope's "
                f"cgroup.procs ({moved}) — cgroup-v2 delegation does not "
                "reach this process's own cgroup, which is exactly what "
                "aplexer's workload spawn needs",
            )
    finally:
        _stop_probe_scope(unit, anchor, env=env)
    return DelegationProbe(True)


def _wait_for_scope_cgroup(
    unit: str, *, env: dict[str, str], anchor: subprocess.Popen, timeout_s: float = 10.0
) -> Optional[Path]:
    """The scope unit's cgroup path once systemd has created it, else None."""
    deadline = time.monotonic() + timeout_s
    while time.monotonic() < deadline:
        shown = subprocess.run(
            # `.scope` suffix, exactly like aplexer's own
            # `wait_for_scope_cgroup`: `systemctl show` of a suffix-less name
            # answers about a nonexistent *.service* and reports an empty
            # ControlGroup, which would make this probe claim the host cannot
            # delegate on a host that plainly can.
            [
                "systemctl", "--user", "show", f"{unit}.scope",
                "--property=ControlGroup", "--value",
            ],
            capture_output=True,
            text=True,
            env=env,
            timeout=30,
        )
        value = shown.stdout.strip()
        if value and value != "/":
            return Path("/sys/fs/cgroup" + value)
        if anchor.poll() is not None:
            return None
        time.sleep(0.1)
    return None


def _can_move_a_process_into(cgroup: Path) -> Optional[str]:
    """None when a throwaway child can join ``cgroup``, else the OS error."""
    child = subprocess.Popen(["sleep", "10"], stdout=subprocess.DEVNULL)
    try:
        with open(cgroup / "cgroup.procs", "w", encoding="ascii") as handle:
            handle.write(str(child.pid))
    except OSError as exc:
        return f"{exc.strerror}, errno {exc.errno}"
    finally:
        child.kill()
        child.wait(timeout=10)
    return None


def _stop_probe_scope(
    unit: str, anchor: subprocess.Popen, *, env: dict[str, str]
) -> None:
    """Leave nothing behind: stop the unit, then reap the systemd-run client."""
    subprocess.run(
        ["systemctl", "--user", "stop", f"{unit}.scope"],
        capture_output=True,
        text=True,
        env=env,
        timeout=30,
        check=False,
    )
    anchor.kill()
    try:
        anchor.wait(timeout=10)
    except subprocess.TimeoutExpired:  # pragma: no cover - defensive
        anchor.terminate()
    if anchor.stderr is not None:
        anchor.stderr.close()


def _cap_proof_required() -> bool:
    return os.environ.get(CAP_PROOF_MODE_ENV, "").strip().lower() == "required"


def _unavailable(missing: str) -> None:
    """The ONE place this module may skip — and only for a named capability.

    In `required` mode (`scripts/check-cgroup-cap-proof.sh`, run on a host
    that CAN delegate) the same condition FAILS instead, so "the kernel proof
    skipped everywhere" cannot pass unnoticed.
    """
    message = f"cgroup delegation unavailable — missing capability: {missing}"
    if _cap_proof_required():
        pytest.fail(
            f"{CAP_PROOF_MODE_ENV}=required, but {message}. Run this on a host "
            "with a systemd --user session, or fix the probe/environment — do "
            "NOT relax the requirement."
        )
    pytest.skip(message)


def _short_runtime_root() -> str:
    """A throwaway APLEXER_RUNTIME_DIR short enough for AF_UNIX sun_path."""
    suffix = len("/sessions/") + 36 + len("/control.sock")
    for base in (tempfile.gettempdir(), "/dev/shm"):
        if not os.path.isdir(base):
            continue
        root = tempfile.mkdtemp(prefix="ps2562-", dir=base)
        if len(root) + suffix < 108:
            return root
        os.rmdir(root)
    raise AssertionError("no temp base short enough for an AF_UNIX control socket")


# ----- the skip mechanism itself (runs everywhere) --------------------------


def test_a_missing_capability_skip_names_the_capability(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A CI reader must be able to tell "cannot run here" from "stopped running"."""
    monkeypatch.delenv(CAP_PROOF_MODE_ENV, raising=False)
    # BaseException + an explicit isinstance, never `pytest.raises(skip…)`:
    # `Skipped` and `Failed` are both BaseExceptions, so a narrow `raises`
    # lets the OTHER one propagate and decide this test's outcome for it —
    # which is exactly how the sibling guard below was green-by-skipping
    # (round-2 review).
    with pytest.raises(BaseException) as excinfo:
        _unavailable("systemd-run is not installed")
    assert isinstance(excinfo.value, pytest.skip.Exception), (
        "without the required flag a missing capability must SKIP, got "
        f"{type(excinfo.value).__name__}"
    )
    assert "missing capability: systemd-run is not installed" in str(excinfo.value)


def test_required_mode_turns_the_skip_into_a_failure(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """`scripts/check-cgroup-cap-proof.sh`'s teeth.

    This is the guard against the only kernel-level proof of the cap quietly
    becoming a no-op: on a host that CAN delegate, the script runs the proof
    with `POCKETSHELL_CGROUP_CAP_PROOF=required`, and a skip is then a hard
    failure rather than a green line nobody reads.
    """
    monkeypatch.setenv(CAP_PROOF_MODE_ENV, "required")
    with pytest.raises(BaseException) as excinfo:
        _unavailable("systemd-run is not installed")
    # The load-bearing line. `pytest.raises(pytest.fail.Exception)` does NOT
    # catch a propagating `Skipped`, so deleting required mode's conversion
    # made THIS guard skip — green under `-q` and green under `--self-test`
    # (round-2 review). Asserting the type turns that mutation red instead.
    assert isinstance(excinfo.value, pytest.fail.Exception), (
        "required mode must convert the skip into a FAILURE, got "
        f"{type(excinfo.value).__name__}: {excinfo.value}"
    )
    assert "required" in str(excinfo.value)
    assert "systemd-run is not installed" in str(excinfo.value)


def test_the_probe_names_a_missing_user_runtime_directory(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """The probe's first branch, exercised for real (uid with no /run/user)."""
    monkeypatch.setattr(os, "getuid", lambda: 65534)
    probe = probe_cgroup_delegation()
    assert not probe.available
    assert "no user runtime directory at /run/user/65534" in probe.missing


def test_the_probe_reports_a_cgroup_it_cannot_move_a_process_into() -> None:
    """The step PR #2590's runner failed on, exercised against an unwritable dir.

    `/proc` accepts no new files for anyone, root included, so this branch is
    deterministic wherever the suite runs — unlike a real cgroup, whose
    writability is exactly the host property under test.
    """
    error = _can_move_a_process_into(Path("/proc"))
    assert error is not None and "errno" in error


def test_the_pr_2590_runner_failure_is_classified_as_a_host_refusal() -> None:
    """The exact message a GitHub hosted runner produced, verbatim.

    PR #2590's red: the runner creates the scope fine, then cannot move the
    workload into it. Classifying this is what keeps a hosted runner from being
    permanently red, and pinning the string here is what keeps the closed list
    honest if aplexer ever rewords it.
    """
    assert host_refused_containment(
        "pocketshell: `a start --tag ps2562-cap` exited 1: "
        "a: worker startup failed: spawn workload: Permission denied (os error 13)"
    )


def test_an_ordinary_create_failure_is_not_a_host_refusal() -> None:
    """A defect in the cap we computed must never be laundered into a skip."""
    assert not host_refused_containment(
        "pocketshell: `a start --tag x` exited 1: a: invalid --memory value"
    )
    assert not host_refused_containment("pocketshell: `a start --tag x` exited 2: boom")


def test_the_kernel_proof_can_only_be_skipped_through_that_one_helper() -> None:
    """No blanket `@pytest.mark.skip`, no stray `pytest.skip()` in this module.

    The realistic way the kernel proof stops running everywhere is not a subtle
    probe bug — it is someone disabling it outright while chasing a red CI.
    This is a source-level guard against exactly that, and it runs on every
    host, including the ones that cannot execute the proof itself.
    """
    tree = ast.parse(Path(__file__).read_text(encoding="utf-8"))
    skippers: list[str] = []
    proof: Optional[ast.FunctionDef] = None
    for node in ast.walk(tree):
        if isinstance(node, ast.FunctionDef):
            if node.name == "test_real_aplexer_session_cgroup_carries_the_resolved_cap":
                proof = node
            for inner in ast.walk(node):
                if (
                    isinstance(inner, ast.Call)
                    and isinstance(inner.func, ast.Attribute)
                    and inner.func.attr in {"skip", "xfail"}
                    and isinstance(inner.func.value, ast.Name)
                    and inner.func.value.id == "pytest"
                ):
                    skippers.append(node.name)
    assert skippers == ["_unavailable"], (
        "every skip in this module must go through _unavailable(), which names "
        f"the missing capability and honours {CAP_PROOF_MODE_ENV}; found "
        f"skips in {skippers}"
    )
    assert proof is not None, "the kernel-level cap proof was renamed or removed"
    decorators = [ast.unparse(decorator) for decorator in proof.decorator_list]
    assert len(decorators) == 1, (
        "the kernel proof carries unexpected decorators — an unconditional "
        f"skip/xfail here would disable it everywhere: {decorators}"
    )
    only = proof.decorator_list[0]
    assert (
        isinstance(only, ast.Call)
        and ast.unparse(only.func) == "pytest.mark.skipif"
    ), f"the kernel proof's only decorator must stay the platform guard; got {decorators[0]!r}"
    # The CONDITION is asserted exactly, not merely "mentions sys.platform":
    # `sys.platform != "linux" or True` satisfies a looser check and disables
    # the proof everywhere while looking innocent (round-2 review found this).
    condition = ast.unparse(only.args[0]).replace('"', "'")
    assert condition == "sys.platform != 'linux'", (
        "the kernel proof's skip condition must stay exactly the platform "
        f"guard — anything else can disable it unconditionally; got {condition!r}"
    )


# ----- real transport: the kernel is the oracle -----------------------------


@pytest.mark.skipif(sys.platform != "linux", reason=REASON_LINUX_ONLY)
def test_real_aplexer_session_cgroup_carries_the_resolved_cap(tmp_path: Path) -> None:
    """End to end: workspace cgroups.toml -> `sessions create` -> memory.max.

    Drives the production CLI against an ISOLATED aplexer instance (its own
    config/state/runtime), so the maintainer's live sessions are never read,
    capped or killed. The session is torn down and its scope's disappearance
    is asserted.
    """
    probe = probe_cgroup_delegation()
    if not probe.available:
        _unavailable(probe.missing)

    workspace = tmp_path / "ws"
    write_cgroups_toml(workspace, f'mem = "{REAL_CAP}"\n')
    # The cap the kernel must show is the one the shipped policy module
    # resolves for this workspace — asserted here so a future policy change
    # contradicts the kernel expectation instead of silently agreeing with it.
    assert memcap.resolve_session_mem_bytes(flag=None, workspace=str(workspace)) == (
        REAL_CAP_BYTES
    )
    state_dir = tmp_path / "aplexer-state"
    home = tmp_path / "home"
    for directory in (state_dir, home):
        directory.mkdir()
    config = tmp_path / "aplexer.toml"
    config.write_text("version = 1\n", encoding="utf-8")
    runtime_root = _short_runtime_root()
    tag = "ps2562-cap"
    env = {
        "PATH": "/usr/bin:/bin",
        "HOME": str(home),
        "TERM": "dumb",
        "XDG_RUNTIME_DIR": _real_runtime_dir(),
        "XDG_CONFIG_HOME": str(tmp_path / "config"),
        "APLEXER_CONFIG": str(config),
        "APLEXER_STATE_DIR": str(state_dir),
        "APLEXER_RUNTIME_DIR": runtime_root,
        "POCKETSHELL_APLEXER": "1",
    }

    def run_cli(*args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, "-m", "pocketshell", *args],
            capture_output=True,
            text=True,
            env=env,
            cwd=str(tmp_path),
            timeout=120,
        )

    created = run_cli("sessions", "create", tag, "--cwd", str(workspace), "--json")
    record: dict = {}
    try:
        if created.returncode != 0:
            detail = (created.stderr or created.stdout).strip()
            if host_refused_containment(detail):
                # The probe said the capability was there and the host still
                # refused containment: an environment gap the probe does not
                # model yet. Report it as such, naming BOTH facts, so it reads
                # as "extend the probe", never as a quiet pass. In `required`
                # mode this is a failure like any other skip.
                _unavailable(
                    "the delegation probe passed, but this host still refused "
                    f"to contain the session: {detail[:300]}"
                )
            raise AssertionError(
                f"`sessions create` exited {created.returncode}: {detail}"
            )
        records = sorted((state_dir / "sessions").glob("*/session.json"))
        assert len(records) == 1, f"expected one session record, got {records}"
        record = json.loads(records[0].read_text(encoding="utf-8"))

        assert record["limits"] == {"memory_bytes": REAL_CAP_BYTES}, (
            "the created aplexer session records NO memory cap: "
            f"limits={record['limits']!r}"
        )
        cgroup = record.get("containment_cgroup")
        assert cgroup, "the session has no containment cgroup, so nothing caps it"
        memory_max = Path(cgroup, "memory.max").read_text(encoding="utf-8").strip()
        assert memory_max == str(REAL_CAP_BYTES), (
            f"kernel says memory.max={memory_max} for {cgroup}, "
            f"expected {REAL_CAP_BYTES}"
        )
    finally:
        # Tear the session down whatever the assertions did, then PROVE the
        # teardown: a test that leaks a scope onto the maintainer's box is a
        # bug of its own.
        killed = None
        if record.get("id"):
            killed = run_cli("sessions", "kill", str(record["id"]), "--json")
        shutil.rmtree(runtime_root, ignore_errors=True)
    assert killed is not None and killed.returncode == 0, (
        f"cleanup kill failed: {killed.stderr if killed else 'no record to kill'}"
    )
    leftover = record.get("containment_cgroup")
    assert leftover and not Path(leftover).exists(), (
        f"the test's own cgroup {leftover} outlived the session"
    )
