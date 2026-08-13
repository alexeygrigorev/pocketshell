#!/usr/bin/python3 -I
"""Detached, durable launcher for the canonical local full-JVM gate.

The canonical gate owns the immutable Gradle graph, profile authentication,
resource scope, and output-tree lock. This entrypoint owns only lifecycle and
evidence: it starts that gate in a transient user service, records its output
and verdict, and lets a later shell inspect or stop the exact run.
"""

from __future__ import annotations

import argparse
import collections
import datetime as dt
import hashlib
import json
import os
import re
import shutil
import signal
import subprocess
import sys
import tempfile
import time
import traceback
from pathlib import Path
from typing import Any


RUN_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}")
STATE_OVERRIDE = "POCKETSHELL_FULL_JVM_GATE_STATE_DIR"
UNIT_INACTIVE_WAIT_SECONDS = 10.0
UNIT_STATE_PROPERTY_NAMES = ("LoadState", "ActiveState", "SubState")
UNIT_SERVICE_PROPERTY_NAMES = (*UNIT_STATE_PROPERTY_NAMES, "MainPID")


class DetachedGateError(RuntimeError):
    """Operator-facing lifecycle failure."""


def utc_now() -> str:
    return dt.datetime.now(dt.timezone.utc).isoformat(timespec="seconds")


def repository_root() -> Path:
    return Path(__file__).resolve().parent.parent


def repository_key(root: Path) -> str:
    return hashlib.sha256((str(root.resolve()) + "\n").encode()).hexdigest()[:16]


def state_root(root: Path) -> Path:
    override = os.environ.get(STATE_OVERRIDE, "")
    if override:
        base = Path(override).expanduser()
    else:
        xdg_state = os.environ.get("XDG_STATE_HOME", "")
        base = Path(xdg_state).expanduser() if xdg_state else Path.home() / ".local" / "state"
        base = base / "pocketshell" / "full-jvm-gate"
    return base / repository_key(root)


def ensure_private_directory(path: Path) -> None:
    path.mkdir(mode=0o700, parents=True, exist_ok=True)
    if path.is_symlink() or not path.is_dir():
        raise DetachedGateError(f"state path must be a real directory: {path}")


def atomic_write(path: Path, content: str, mode: int = 0o600) -> None:
    ensure_private_directory(path.parent)
    descriptor, temporary_name = tempfile.mkstemp(prefix=f".{path.name}.", dir=path.parent)
    temporary = Path(temporary_name)
    try:
        os.fchmod(descriptor, mode)
        with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
            stream.write(content)
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)


def atomic_write_json(path: Path, value: dict[str, Any]) -> None:
    atomic_write(path, json.dumps(value, indent=2, sort_keys=True) + "\n")


def read_json(path: Path) -> dict[str, Any]:
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise DetachedGateError(f"cannot read durable state {path}: {error}") from error
    if not isinstance(value, dict):
        raise DetachedGateError(f"durable state is not an object: {path}")
    return value


def executable(name: str) -> str:
    resolved = shutil.which(name)
    if not resolved:
        raise DetachedGateError(f"required command is unavailable: {name}")
    return resolved


def git_sha(root: Path) -> str:
    result = subprocess.run(
        (executable("git"), "-C", str(root), "rev-parse", "HEAD"),
        check=False,
        capture_output=True,
        text=True,
    )
    sha = result.stdout.strip()
    if result.returncode != 0 or re.fullmatch(r"[0-9a-f]{40}", sha) is None:
        raise DetachedGateError(
            f"cannot resolve the launched checkout SHA: {result.stderr.strip() or sha}"
        )
    return sha


def validate_run_id(value: str) -> str:
    if RUN_ID_PATTERN.fullmatch(value) is None:
        raise DetachedGateError(
            "run id must be 1-64 characters using letters, digits, dot, underscore, or dash"
        )
    return value


def canonical_gate_command(canonical: Path, scope_unit: str) -> list[str]:
    return [str(canonical), "--unit", scope_unit]


def automatic_run_id() -> str:
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    return f"{stamp}-{os.getpid()}"


def unit_property_names(unit: str) -> tuple[str, ...]:
    if unit.endswith(".service"):
        return UNIT_SERVICE_PROPERTY_NAMES
    if unit.endswith(".scope"):
        return UNIT_STATE_PROPERTY_NAMES
    raise DetachedGateError(f"unsupported systemd unit type: {unit}")


def unit_properties(unit: str) -> dict[str, str]:
    property_names = unit_property_names(unit)
    result = subprocess.run(
        (
            executable("systemctl"),
            "--user",
            "show",
            *(f"--property={name}" for name in property_names),
            unit,
        ),
        check=False,
        capture_output=True,
        text=True,
    )
    if result.returncode != 0:
        detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
        raise DetachedGateError(f"cannot inspect systemd unit {unit}: {detail}")

    properties: dict[str, str] = {}
    malformed: list[str] = []
    for line in result.stdout.splitlines():
        key, separator, value = line.partition("=")
        if not separator or key not in property_names or not value or key in properties:
            malformed.append(line)
            continue
        properties[key] = value
    missing = [name for name in property_names if name not in properties]
    invalid_main_pid = "MainPID" in property_names and not properties.get("MainPID", "").isdigit()
    if malformed or missing or invalid_main_pid:
        reasons: list[str] = []
        if malformed:
            reasons.append(f"malformed lines={malformed!r}")
        if missing:
            reasons.append(f"missing properties={','.join(missing)}")
        if invalid_main_pid and "MainPID" not in missing:
            reasons.append(f"invalid MainPID={properties.get('MainPID')!r}")
        detail = "; ".join(reasons) or "empty output"
        raise DetachedGateError(f"cannot inspect systemd unit {unit}: {detail}")
    return properties


def active(properties: dict[str, str]) -> bool:
    load_state = properties.get("LoadState")
    active_state = properties.get("ActiveState")
    if load_state == "loaded" and active_state in {"active", "activating", "reloading"}:
        return True
    if load_state == "not-found" and active_state == "inactive":
        return False
    if load_state == "loaded" and active_state in {"inactive", "failed", "deactivating"}:
        return active_state == "deactivating"
    raise DetachedGateError(
        "cannot classify systemd unit state: "
        f"LoadState={load_state!r}, ActiveState={active_state!r}, "
        f"SubState={properties.get('SubState')!r}"
    )


def wait_inactive(unit: str, timeout_seconds: float = UNIT_INACTIVE_WAIT_SECONDS) -> bool:
    deadline = time.monotonic() + timeout_seconds
    while True:
        if not active(unit_properties(unit)):
            return True
        if time.monotonic() >= deadline:
            return False
        time.sleep(0.05)


def systemctl(*arguments: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        (executable("systemctl"), "--user", *arguments),
        check=False,
        capture_output=True,
        text=True,
    )


def control_error(action: str, unit: str, result: subprocess.CompletedProcess[str]) -> str | None:
    if result.returncode == 0:
        return None
    detail = result.stderr.strip() or result.stdout.strip() or f"exit {result.returncode}"
    return f"systemctl {action} {unit}: {detail}"


def stop_exact_scope(scope_base: str) -> dict[str, Any]:
    unit = f"{scope_base}.scope"
    cleanup: dict[str, Any] = {
        "unit": unit,
        "inactive": False,
        "forced": False,
        "inspection_errors": [],
        "control_errors": [],
    }
    try:
        if not active(unit_properties(unit)):
            cleanup["inactive"] = True
            return cleanup
    except DetachedGateError as error:
        cleanup["inspection_errors"].append(str(error))
        return cleanup

    stopped = systemctl("stop", unit)
    if error := control_error("stop", unit, stopped):
        cleanup["control_errors"].append(error)
    forced = False
    inactive = False
    try:
        inactive = wait_inactive(unit, 2.0)
    except DetachedGateError as error:
        cleanup["inspection_errors"].append(str(error))
    if not inactive and not cleanup["inspection_errors"]:
        forced = True
        killed = systemctl("kill", "--kill-whom=all", "--signal=KILL", unit)
        if error := control_error("kill", unit, killed):
            cleanup["control_errors"].append(error)
        stopped = systemctl("stop", unit)
        if error := control_error("stop", unit, stopped):
            cleanup["control_errors"].append(error)
        try:
            inactive = wait_inactive(unit, 2.0)
        except DetachedGateError as error:
            cleanup["inspection_errors"].append(str(error))
    cleanup["forced"] = forced
    cleanup["inactive"] = bool(
        inactive and not cleanup["inspection_errors"] and not cleanup["control_errors"]
    )
    return cleanup


def locate_run(root: Path, requested_run_id: str | None) -> tuple[str, Path]:
    runs_root = state_root(root)
    if requested_run_id:
        run_id = validate_run_id(requested_run_id)
    else:
        current = runs_root / "current"
        try:
            run_id = validate_run_id(current.read_text(encoding="utf-8").strip())
        except OSError as error:
            raise DetachedGateError(
                f"no detached full-JVM run is recorded for {root}; start one first"
            ) from error
    run_dir = runs_root / run_id
    if run_dir.is_symlink() or not run_dir.is_dir():
        raise DetachedGateError(f"detached run does not exist: {run_id}")
    return run_id, run_dir


def command_start(arguments: argparse.Namespace) -> int:
    root = repository_root()
    canonical = root / "scripts" / "full-jvm-gate.py"
    if canonical.is_symlink() or not canonical.is_file() or not os.access(canonical, os.X_OK):
        raise DetachedGateError(f"canonical full-JVM gate is not a regular executable: {canonical}")

    run_id = validate_run_id(arguments.run_id or automatic_run_id())
    runs_root = state_root(root)
    ensure_private_directory(runs_root)
    run_dir = runs_root / run_id
    try:
        run_dir.mkdir(mode=0o700)
    except FileExistsError as error:
        raise DetachedGateError(f"detached run id already exists: {run_id}") from error

    key = repository_key(root)
    service_unit = f"pocketshell-full-jvm-detached-{key}-{run_id}"
    scope_unit = f"pocketshell-full-jvm-{key}-{run_id}"
    canonical_command = canonical_gate_command(canonical, scope_unit)
    metadata = {
        "schema": 1,
        "run_id": run_id,
        "root": str(root),
        "git_sha": git_sha(root),
        "started_at": utc_now(),
        "service_unit": service_unit,
        "scope_unit": scope_unit,
        "canonical_command": canonical_command,
        "log_file": str(run_dir / "gate.log"),
    }
    atomic_write_json(run_dir / "metadata.json", metadata)
    atomic_write(runs_root / "current", run_id + "\n")

    entrypoint = Path(__file__).resolve()
    launch_command = [
        executable("systemd-run"),
        "--user",
        f"--unit={service_unit}",
        "--service-type=exec",
        "--collect",
        "--no-block",
        f"--property=WorkingDirectory={root}",
        "--property=KillMode=control-group",
        "--property=TimeoutStopSec=20s",
        "--property=Slice=robust.slice",
        "--",
        str(entrypoint),
        "_run",
        "--run-dir",
        str(run_dir),
        "--service-unit",
        service_unit,
        "--scope-unit",
        scope_unit,
    ]
    launch = subprocess.run(launch_command, check=False, capture_output=True, text=True)
    atomic_write_json(
        run_dir / "launch.json",
        {
            "ended_at": utc_now(),
            "exit_code": launch.returncode,
            "stdout": launch.stdout,
            "stderr": launch.stderr,
        },
    )
    if launch.returncode != 0:
        raise DetachedGateError(
            f"systemd refused detached run {run_id}: {launch.stderr.strip() or launch.stdout.strip()}"
        )

    service_name = f"{service_unit}.service"
    observed: dict[str, str] | None = None
    deadline = time.monotonic() + 2.0
    while time.monotonic() < deadline:
        if (run_dir / "result.json").is_file():
            observed = {"LoadState": "completed-before-observation"}
            break
        candidate = unit_properties(service_name)
        if candidate.get("LoadState") == "loaded" and active(candidate):
            observed = candidate
            break
        time.sleep(0.05)
    if observed is None:
        systemctl("stop", service_name)
        stop_exact_scope(scope_unit)
        raise DetachedGateError(
            f"detached service was never observed loaded and produced no verdict: {service_name}"
        )
    atomic_write_json(run_dir / "start-observed.json", {"at": utc_now(), **observed})

    print(f"run_id={run_id}")
    print(f"run_dir={run_dir}")
    print(f"service_unit={service_name}")
    print(f"scope_unit={scope_unit}.scope")
    print(f"status={entrypoint} status --run-id {run_id}")
    print(f"tail={entrypoint} tail --run-id {run_id} --follow")
    return 0


def final_state_for_return_code(return_code: int) -> str:
    if return_code == 0:
        return "PASS"
    return "INTERRUPTED" if return_code < 0 or return_code >= 128 else "FAIL"


def command_internal_run(arguments: argparse.Namespace) -> int:
    run_dir = Path(arguments.run_dir).resolve()
    metadata = read_json(run_dir / "metadata.json")
    root = repository_root()
    canonical = root / "scripts" / "full-jvm-gate.py"
    scope_unit = arguments.scope_unit
    service_unit = arguments.service_unit
    canonical_command = canonical_gate_command(canonical, scope_unit)
    if metadata.get("root") != str(root):
        raise DetachedGateError("durable metadata belongs to a different checkout")
    if metadata.get("service_unit") != service_unit or metadata.get("scope_unit") != scope_unit:
        raise DetachedGateError("durable metadata unit identity changed before execution")
    if metadata.get("canonical_command") != canonical_command:
        raise DetachedGateError("durable metadata changed the canonical command")

    interrupted_signal = 0
    child: subprocess.Popen[str] | None = None

    def remember_interrupt(received: int, _frame: object) -> None:
        nonlocal interrupted_signal
        interrupted_signal = received
        if child is not None and child.poll() is None:
            try:
                child.terminate()
            except ProcessLookupError:
                pass

    for handled in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(handled, remember_interrupt)

    log_path = run_dir / "gate.log"
    return_code = 70
    state = "RUNNER_FAILURE"
    failure = ""
    cleanup = {"unit": f"{scope_unit}.scope", "inactive": False, "forced": False}
    with log_path.open("a", encoding="utf-8", buffering=1) as log:
        stdout_before = os.dup(1)
        stderr_before = os.dup(2)
        os.dup2(log.fileno(), 1)
        os.dup2(log.fileno(), 2)
        try:
            print(f"detached_run_id={metadata['run_id']}", flush=True)
            print(f"started_at={metadata['started_at']}", flush=True)
            print(f"git_sha={metadata['git_sha']}", flush=True)
            print(f"service_unit={service_unit}.service", flush=True)
            print(f"scope_unit={scope_unit}.scope", flush=True)
            print("canonical_command=" + " ".join(canonical_command), flush=True)
            child = subprocess.Popen(
                canonical_command,
                cwd=root,
                text=True,
            )
            return_code = child.wait()
            if interrupted_signal:
                return_code = 128 + interrupted_signal
            state = final_state_for_return_code(return_code)
        except BaseException as error:  # Persist a verdict even for runner defects.
            failure = f"{type(error).__name__}: {error}"
            traceback.print_exc()
            state = "RUNNER_FAILURE"
            return_code = 70
        finally:
            if child is not None and child.poll() is None:
                child.terminate()
                try:
                    child.wait(timeout=2)
                except subprocess.TimeoutExpired:
                    child.kill()
                    child.wait(timeout=2)
            cleanup = stop_exact_scope(scope_unit)
            if not cleanup["inactive"]:
                state = "CLEANUP_FAILURE"
                return_code = 70
            print(f"ended_at={utc_now()}", flush=True)
            print(f"state={state}", flush=True)
            print(f"exit_code={return_code}", flush=True)
            print(f"scope_inactive={str(cleanup['inactive']).lower()}", flush=True)
            os.dup2(stdout_before, 1)
            os.dup2(stderr_before, 2)
            os.close(stdout_before)
            os.close(stderr_before)

    result = {
        "schema": 1,
        "ended_at": utc_now(),
        "exit_code": return_code,
        "state": state,
        "signal": signal.Signals(interrupted_signal).name if interrupted_signal else None,
        "failure": failure or None,
        "scope_cleanup": cleanup,
    }
    atomic_write_json(run_dir / "result.json", result)
    return return_code


def status_lines(root: Path, run_id: str, run_dir: Path) -> tuple[list[str], int]:
    metadata = read_json(run_dir / "metadata.json")
    service_name = f"{metadata['service_unit']}.service"
    scope_name = f"{metadata['scope_unit']}.scope"
    result_path = run_dir / "result.json"
    result = read_json(result_path) if result_path.is_file() else None

    inspection_errors: list[str] = []
    if result is not None:
        try:
            wait_inactive(service_name, 1.0)
        except DetachedGateError as error:
            inspection_errors.append(str(error))

    inspected: dict[str, dict[str, str]] = {}
    for unit in (service_name, scope_name):
        try:
            properties = unit_properties(unit)
            active(properties)
            inspected[unit] = properties
        except DetachedGateError as error:
            inspection_errors.append(str(error))
    service = inspected.get(service_name, {})
    scope = inspected.get(scope_name, {})
    orphan_units = [
        unit
        for unit, properties in ((service_name, service), (scope_name, scope))
        if properties and active(properties)
    ]

    if inspection_errors:
        state = "INSPECTION_FAILED"
        exit_code = (result or {}).get("exit_code", "unknown")
        status_code = 1
    elif result is not None:
        state = str(result.get("state", "UNKNOWN"))
        exit_code = result.get("exit_code", "unknown")
        status_code = 0 if state == "PASS" and not orphan_units else 1
    elif active(service):
        state = "RUNNING"
        exit_code = "pending"
        status_code = 0
    elif (run_dir / "start-observed.json").is_file():
        state = "INTERRUPTED"
        exit_code = "missing"
        status_code = 1
    else:
        state = "LAUNCH_FAILED"
        exit_code = "missing"
        status_code = 1

    lines = [
        f"run_id={run_id}",
        f"state={state}",
        f"exit_code={exit_code}",
        f"git_sha={metadata.get('git_sha', 'unknown')}",
        f"started_at={metadata.get('started_at', 'unknown')}",
        f"ended_at={(result or {}).get('ended_at', 'pending')}",
        f"service_unit={service_name}",
        f"service_load_state={service.get('LoadState', 'unknown')}",
        f"service_state={service.get('ActiveState', 'unknown')}/{service.get('SubState', 'unknown')}",
        f"scope_unit={scope_name}",
        f"scope_load_state={scope.get('LoadState', 'unknown')}",
        f"scope_state={scope.get('ActiveState', 'unknown')}/{scope.get('SubState', 'unknown')}",
        (
            f"orphan_units={','.join(orphan_units) if orphan_units else 'none'}"
            if not inspection_errors
            else "orphan_units=unknown"
        ),
        f"inspection_error={' | '.join(inspection_errors) if inspection_errors else 'none'}",
        f"run_dir={run_dir}",
        f"log_file={run_dir / 'gate.log'}",
    ]
    return lines, status_code


def command_status(arguments: argparse.Namespace) -> int:
    root = repository_root()
    run_id, run_dir = locate_run(root, arguments.run_id)
    lines, status_code = status_lines(root, run_id, run_dir)
    print("\n".join(lines))
    return status_code


def command_tail(arguments: argparse.Namespace) -> int:
    root = repository_root()
    _run_id, run_dir = locate_run(root, arguments.run_id)
    log_path = run_dir / "gate.log"
    if arguments.follow:
        return subprocess.call((executable("tail"), "-n", str(arguments.lines), "-F", str(log_path)))
    if not log_path.is_file():
        raise DetachedGateError(f"run has not written a log yet: {log_path}")
    with log_path.open("r", encoding="utf-8", errors="replace") as stream:
        for line in collections.deque(stream, maxlen=arguments.lines):
            print(line, end="")
    return 0


def command_stop(arguments: argparse.Namespace) -> int:
    root = repository_root()
    run_id, run_dir = locate_run(root, arguments.run_id)
    metadata = read_json(run_dir / "metadata.json")
    service_name = f"{metadata['service_unit']}.service"
    scope_name = f"{metadata['scope_unit']}.scope"

    control_errors: list[str] = []
    inspection_errors: list[str] = []

    killed = systemctl("kill", "--kill-whom=all", "--signal=TERM", service_name)
    if error := control_error("kill", service_name, killed):
        control_errors.append(error)
    deadline = time.monotonic() + 4.0
    while time.monotonic() < deadline and not (run_dir / "result.json").is_file():
        time.sleep(0.05)
    for unit in (scope_name, service_name):
        stopped = systemctl("stop", unit)
        if error := control_error("stop", unit, stopped):
            control_errors.append(error)

    inactive: dict[str, bool] = {service_name: False, scope_name: False}
    for unit in (service_name, scope_name):
        try:
            inactive[unit] = wait_inactive(unit, 2.0)
        except DetachedGateError as error:
            inspection_errors.append(str(error))

    for unit in (service_name, scope_name):
        if inactive[unit] or inspection_errors:
            continue
        killed = systemctl("kill", "--kill-whom=all", "--signal=KILL", unit)
        if error := control_error("kill", unit, killed):
            control_errors.append(error)
        stopped = systemctl("stop", unit)
        if error := control_error("stop", unit, stopped):
            control_errors.append(error)
        try:
            inactive[unit] = wait_inactive(unit, 2.0)
        except DetachedGateError as error:
            inspection_errors.append(str(error))

    service_inactive = inactive[service_name]
    scope_inactive = inactive[scope_name]
    complete = service_inactive and scope_inactive and not inspection_errors and not control_errors
    print(f"run_id={run_id}")
    if inspection_errors:
        print("stop=inspection-failed")
    elif control_errors:
        print("stop=control-failed")
    else:
        print("stop=complete" if complete else "stop=cleanup-failed")
    print(f"service_unit={service_name}")
    print(f"scope_unit={scope_name}")
    print(f"inspection_error={' | '.join(inspection_errors) if inspection_errors else 'none'}")
    print(f"control_error={' | '.join(control_errors) if control_errors else 'none'}")
    return 0 if complete else 1


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(
        description="Run the immutable canonical full-JVM gate beyond the launching shell's lifetime.",
    )
    commands = result.add_subparsers(dest="command", required=True)

    start = commands.add_parser("start", help="start a detached canonical gate")
    start.add_argument("--run-id", help="stable 1-64 character evidence id")
    start.set_defaults(handler=command_start)

    status = commands.add_parser("status", help="show durable state and exact unit cleanup")
    status.add_argument("--run-id", help="run id; defaults to this checkout's latest run")
    status.set_defaults(handler=command_status)

    tail = commands.add_parser("tail", help="read the durable combined stdout/stderr log")
    tail.add_argument("--run-id", help="run id; defaults to this checkout's latest run")
    tail.add_argument("--lines", type=int, default=80)
    tail.add_argument("--follow", action="store_true")
    tail.set_defaults(handler=command_tail)

    stop = commands.add_parser("stop", help="interrupt and clean only this run's exact units")
    stop.add_argument("--run-id", help="run id; defaults to this checkout's latest run")
    stop.set_defaults(handler=command_stop)

    internal = commands.add_parser("_run", help=argparse.SUPPRESS)
    internal.add_argument("--run-dir", required=True)
    internal.add_argument("--service-unit", required=True)
    internal.add_argument("--scope-unit", required=True)
    internal.set_defaults(handler=command_internal_run)
    return result


def main() -> int:
    arguments = parser().parse_args()
    if getattr(arguments, "lines", 1) <= 0:
        raise DetachedGateError("--lines must be positive")
    return int(arguments.handler(arguments))


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except DetachedGateError as error:
        sys.stderr.write(f"FAIL: {error}\n")
        raise SystemExit(2) from error
