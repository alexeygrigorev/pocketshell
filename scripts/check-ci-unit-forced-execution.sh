#!/usr/bin/python3 -I
"""Fail closed when the required CI Unit command can reuse cached test tasks.

Issue #2069 widened this guard from "forces execution" to "forces execution of
the WHOLE graph". Since #2069 the Unit job is a two-way matrix — one job runs
`testDebugUnitTest`, the other `testReleaseUnitTest` — because the single Gradle
step WAS the entire 18-minute critical path and `--max-workers 2 -> 4` bought
only ~57 s. Splitting by variant is what makes the union trivially the old
`./gradlew test` graph, but it also introduces a new way to be quietly wrong:
drop a shard, hardcode one variant, or misspell the matrix values, and CI runs
half the tests with the required `Unit tests` check still green.

So the shape of the sharded command is asserted here, mechanically:

  * exactly one `Run JVM unit tests` step with exactly one `./gradlew` command;
  * its task argument is the matrix expression, not a hardcoded variant, so the
    two jobs cannot silently run the same half;
  * the `unit` job's `strategy.matrix.variant` is exactly `[Debug, Release]` —
    the two shards whose union is the complete graph;
  * `--rerun-tasks` / `--no-build-cache` are still present exactly once.

That covers "the workflow asks for both halves". The other half of the coverage
invariant — "both halves together are everything a module can produce" — is the
shard partition check in `check-executed-test-counts.sh`, which fails when any
required module expects a task outside those two. The two guards together are
the proof; neither is sufficient alone.
"""

import re
import shlex
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_WORKFLOW = ROOT / ".github" / "workflows" / "tests.yml"
STEP_NAME = "Run JVM unit tests"
REQUIRED_FLAGS = ("--rerun-tasks", "--no-build-cache")
UNIT_JOB = "unit"
# The task argument must stay the matrix expression. A hardcoded task name would
# make both matrix legs run the same half of the graph.
SHARD_TASK = "test${{ matrix.variant }}UnitTest"
# The shard names, which are also the Gradle variant names the expression above
# interpolates into. Capitalised because `test<Variant>UnitTest` is.
SHARD_VARIANTS = ("Debug", "Release")
JOB_KEY = re.compile(r"^  ([A-Za-z0-9_-]+):[ \t]*(#.*)?$")


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
    if gradle_tokens[:2] != ("./gradlew", SHARD_TASK):
        raise GuardFailure(
            f"{STEP_NAME!r} must invoke './gradlew \"{SHARD_TASK}\"' so the two "
            "matrix legs run DIFFERENT halves of the test graph and their union "
            "is the complete graph (issue #2069). A hardcoded task name makes "
            "both legs run the same half while the required check stays green."
        )
    return gradle_tokens[2:]


def indented_block(lines: list[str], header: str, indent: int) -> list[str]:
    """Lines under the single `header` line, up to the next line at <= indent."""
    starts = [
        index
        for index, line in enumerate(lines)
        if line == " " * indent + header
    ]
    if len(starts) != 1:
        raise GuardFailure(
            f"expected exactly one {header!r} at indent {indent}, "
            f"found {len(starts)}"
        )
    start = starts[0]
    end = next(
        (
            index
            for index in range(start + 1, len(lines))
            if lines[index].strip()
            and len(lines[index]) - len(lines[index].lstrip(" ")) <= indent
        ),
        len(lines),
    )
    return lines[start + 1 : end]


def extract_shard_variants(workflow: str) -> tuple[str, ...]:
    lines = workflow.splitlines()
    job_starts = [
        index
        for index, line in enumerate(lines)
        if (match := JOB_KEY.match(line)) and match.group(1) == UNIT_JOB
    ]
    if len(job_starts) != 1:
        raise GuardFailure(
            f"expected exactly one {UNIT_JOB!r} job, found {len(job_starts)}"
        )
    start = job_starts[0]
    end = next(
        (
            index
            for index in range(start + 1, len(lines))
            if JOB_KEY.match(lines[index])
        ),
        len(lines),
    )
    job = lines[start + 1 : end]

    matrix = indented_block(
        indented_block(job, "strategy:", 4), "matrix:", 6
    )
    declarations = [
        line for line in matrix if line.startswith(" " * 8 + "variant:")
    ]
    if len(declarations) != 1:
        raise GuardFailure(
            f"the {UNIT_JOB!r} job must declare exactly one "
            f"strategy.matrix.variant, found {len(declarations)}"
        )
    value = declarations[0].split(":", 1)[1].strip()
    if not (value.startswith("[") and value.endswith("]")):
        raise GuardFailure(
            "strategy.matrix.variant must be an inline list, e.g. "
            f"[{', '.join(SHARD_VARIANTS)}]; got {value!r}"
        )
    return tuple(
        item.strip().strip("'\"")
        for item in value[1:-1].split(",")
        if item.strip()
    )


def validate(workflow: str) -> None:
    variants = extract_shard_variants(workflow)
    if variants != SHARD_VARIANTS:
        raise GuardFailure(
            f"the {UNIT_JOB!r} job's shards must be exactly "
            f"{list(SHARD_VARIANTS)} — the two halves whose union is the "
            f"complete test graph (issue #2069); got {list(variants)}. "
            "Dropping, duplicating or renaming a shard silently stops running "
            "one variant's ~4.5k tests while `Unit tests` stays green."
        )
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
    name: JVM unit tests
    strategy:
      fail-fast: false
      matrix:
        variant: [Debug, Release]
    steps:
      - name: Run JVM unit tests
        id: unit_tests
        run: |
          set -o pipefail
          ./gradlew "test${{ matrix.variant }}UnitTest" --no-daemon \\
            --rerun-tasks \\
            --no-build-cache --console=plain 2>&1 | tee "$RUNNER_TEMP/log"
      - name: Assert tests actually executed
        run: scripts/check-executed-test-counts.sh
  python:
    steps:
      - name: Run pytest
        run: pytest
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
            '2>&1 | tee "$RUNNER_TEMP/log"\n          ./gradlew '
            '"test${{ matrix.variant }}UnitTest" '
            "--rerun-tasks --no-build-cache",
        ),
        # -- issue #2069: the ways a shard silently stops running ------------
        # A hardcoded variant: both matrix legs run the SAME half, and the
        # other ~4.5k tests never run under a green required check.
        canonical.replace('"test${{ matrix.variant }}UnitTest"', "testDebugUnitTest"),
        # One shard deleted.
        canonical.replace("[Debug, Release]", "[Debug]"),
        # Two shards, one variant — reads plausible, runs half the graph twice.
        canonical.replace("[Debug, Release]", "[Debug, Debug]"),
        # Wrong case: `testdebugUnitTest` is not a Gradle task, but a guard
        # matching on "two entries" alone would wave it through.
        canonical.replace("[Debug, Release]", "[debug, release]"),
        # An extra shard nobody wired anywhere.
        canonical.replace("[Debug, Release]", "[Debug, Release, Staging]"),
        # The matrix removed entirely: one job, one interpolation of nothing.
        canonical.replace(
            "    strategy:\n      fail-fast: false\n"
            "      matrix:\n        variant: [Debug, Release]\n",
            "",
        ),
        # The matrix moved onto a DIFFERENT job — the extractor must read the
        # `unit` job's own matrix, not the first one in the file.
        canonical.replace("  unit:\n", "  guards:\n").replace(
            "    steps:\n      - name: Run JVM unit tests",
            "  unit:\n    steps:\n      - name: Run JVM unit tests",
            1,
        ),
    )
    for fixture in rejected:
        try:
            validate(fixture)
        except GuardFailure:
            checks += 1
        else:
            raise GuardFailure("self-test accepted an unsafe workflow mutation")
    if checks != 14:
        raise GuardFailure(f"self-test ran {checks} checks, expected 14")
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
