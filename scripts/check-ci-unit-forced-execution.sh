#!/usr/bin/python3 -I
"""Fail closed when the required CI Unit command can reuse cached test tasks."""

import shlex
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_WORKFLOW = ROOT / ".github" / "workflows" / "tests.yml"
STEP_NAME = "Run JVM unit tests"
REQUIRED_FLAGS = ("--rerun-tasks", "--no-build-cache")


class GuardFailure(ValueError):
    """The workflow no longer guarantees fresh execution of every test task."""


def extract_step(workflow: str) -> str:
    marker = f"      - name: {STEP_NAME}"
    lines = workflow.splitlines()
    starts = [index for index, line in enumerate(lines) if line == marker]
    if len(starts) != 1:
        raise GuardFailure(
            f"expected exactly one {STEP_NAME!r} step, found {len(starts)}"
        )
    start = starts[0]
    end = next(
        (
            index
            for index in range(start + 1, len(lines))
            if lines[index].startswith("      - name:")
        ),
        len(lines),
    )
    return "\n".join(lines[start:end])


def extract_gradle_arguments(step: str) -> tuple[str, ...]:
    run_marker = "        run: |"
    lines = step.splitlines()
    run_markers = [index for index, line in enumerate(lines) if line == run_marker]
    if len(run_markers) != 1:
        raise GuardFailure(
            f"{STEP_NAME!r} must contain exactly one literal run block"
        )
    shell = "\n".join(
        line[10:] if line.startswith("          ") else line
        for line in lines[run_markers[0] + 1 :]
    )
    logical_shell = shell.replace("\\\n", " ")
    commands = [
        line.strip()
        for line in logical_shell.splitlines()
        if line.strip().startswith("./gradlew ")
    ]
    if len(commands) != 1:
        raise GuardFailure(
            f"{STEP_NAME!r} must contain exactly one ./gradlew command, "
            f"found {len(commands)}"
        )
    tokens = shlex.split(commands[0], comments=False, posix=True)
    try:
        pipe_index = tokens.index("|")
    except ValueError:
        pipe_index = len(tokens)
    gradle_tokens = tuple(tokens[:pipe_index])
    if gradle_tokens[:2] != ("./gradlew", "test"):
        raise GuardFailure(
            f"{STEP_NAME!r} must invoke the complete './gradlew test' graph"
        )
    return gradle_tokens[2:]


def validate(workflow: str) -> None:
    arguments = extract_gradle_arguments(extract_step(workflow))
    for flag in REQUIRED_FLAGS:
        count = arguments.count(flag)
        if count != 1:
            raise GuardFailure(
                f"{STEP_NAME!r} must pass {flag} exactly once, found {count}"
            )
    if "--build-cache" in arguments:
        raise GuardFailure(
            f"{STEP_NAME!r} must not pass --build-cache alongside --no-build-cache"
        )


def self_test() -> None:
    canonical = """jobs:
  unit:
    steps:
      - name: Run JVM unit tests
        id: unit_tests
        run: |
          set -o pipefail
          ./gradlew test --no-daemon --rerun-tasks \\
            --no-build-cache --console=plain 2>&1 | tee "$RUNNER_TEMP/log"
      - name: Assert tests actually executed
        run: scripts/check-executed-test-counts.sh
"""
    validate(canonical)
    checks = 1
    rejected = (
        canonical.replace(" --rerun-tasks", ""),
        canonical.replace(" --no-build-cache", ""),
        canonical.replace("--no-build-cache", "--build-cache"),
        canonical.replace("--rerun-tasks", "--rerun-tasks --rerun-tasks"),
        canonical.replace(STEP_NAME, "Run some tests"),
        canonical.replace(
            '2>&1 | tee "$RUNNER_TEMP/log"',
            '2>&1 | tee "$RUNNER_TEMP/log"\n          ./gradlew test '
            "--rerun-tasks --no-build-cache",
        ),
    )
    for fixture in rejected:
        try:
            validate(fixture)
        except GuardFailure:
            checks += 1
        else:
            raise GuardFailure("self-test accepted an unsafe workflow mutation")
    if checks != 7:
        raise GuardFailure(f"self-test ran {checks} checks, expected 7")
    print(f"PASS: CI Unit forced-execution guard self-test ({checks} checks)")


def main() -> None:
    arguments = sys.argv[1:]
    if arguments == ["--self-test"]:
        self_test()
        return
    if len(arguments) > 1 or (arguments and arguments[0].startswith("--")):
        raise GuardFailure(
            "usage: scripts/check-ci-unit-forced-execution.sh "
            "[--self-test | WORKFLOW]"
        )
    workflow = Path(arguments[0]) if arguments else DEFAULT_WORKFLOW
    validate(workflow.read_text())
    print(
        "PASS: required CI Unit test graph forces execution and disables "
        "the build cache"
    )


try:
    main()
except (ValueError, OSError) as error:
    sys.stderr.write(f"FAIL: {error}\n")
    raise SystemExit(1)
