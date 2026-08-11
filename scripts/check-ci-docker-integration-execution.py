#!/usr/bin/python3 -I
"""Prove the CI Docker integration graph ran fresh and produced real results.

Issue #2094 is the Docker sibling of the Unit-lane #1646/#1897 guard.  A
restored Gradle build cache can make one integration-test task print
``FROM-CACHE`` while sibling tasks really execute, leaving the overall job green.
Restored XML has fresh timestamps and real counts, so XML alone cannot prove
execution.  This guard therefore pins both halves of the contract:

* the workflow must request the exact four-task graph with ``--rerun-tasks`` and
  ``--no-build-cache``; and
* the completed run must contain a fresh, non-empty, failure-free XML result for
  every task plus a recognised console task line with no no-op outcome.

The self-test mutates each task independently.  A mutation that caches, skips,
omits, or empties one task must name that task while the other three remain
accepted, so the proof is selective rather than a repo-wide count proxy.
"""

from __future__ import annotations

import os
import re
import shlex
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
DEFAULT_WORKFLOW = ROOT / ".github" / "workflows" / "tests.yml"
JOB_NAME = "integration"
RUN_STEP_NAME = "Run integration tests"
RUN_STEP_ID = "integration_tests"
MARKER_STEP_NAME = "Integration run marker (#2094)"
RESULT_STEP_NAME = "Verify integration execution (#2094)"
UPLOAD_STEP_NAME = "Upload integration test reports"
GRADLE_LOG = "${RUNNER_TEMP}/integration-test-gradle.log"
RUN_MARKER = "${RUNNER_TEMP}/integration-test-run-start"
REQUIRED_FLAGS = ("--rerun-tasks", "--no-build-cache", "--console=plain")
FORBIDDEN_OUTCOMES = {"FROM-CACHE", "UP-TO-DATE", "SKIPPED", "NO-SOURCE"}
JOB_KEY = re.compile(r"^  ([A-Za-z0-9_-]+):[ \t]*(#.*)?$")
TASK_LINE = re.compile(
    r"^> Task (?P<task>:[A-Za-z0-9_:.-]+:integrationTest)"
    r"(?: (?P<outcome>[A-Z][A-Z-]*))?[ \t]*$"
)


@dataclass(frozen=True)
class TaskSpec:
    task: str
    module: str

    @property
    def results_relative(self) -> Path:
        return Path(self.module) / "build" / "test-results" / "integrationTest"

    @property
    def reports_relative(self) -> Path:
        return Path(self.module) / "build" / "reports" / "tests" / "integrationTest"


TASKS = (
    TaskSpec(":app:integrationTest", "app"),
    TaskSpec(":shared:core-ssh:integrationTest", "shared/core-ssh"),
    TaskSpec(":shared:core-portfwd:integrationTest", "shared/core-portfwd"),
    TaskSpec(":shared:core-tmux:integrationTest", "shared/core-tmux"),
)
EXPECTED_TASK_NAMES = tuple(spec.task for spec in TASKS)


class GuardFailure(ValueError):
    """The workflow or run does not prove fresh execution of all four tasks."""


def extract_job(workflow: str, job_name: str) -> str:
    lines = workflow.splitlines()
    starts = [
        index
        for index, line in enumerate(lines)
        if (match := JOB_KEY.match(line)) and match.group(1) == job_name
    ]
    if len(starts) != 1:
        raise GuardFailure(f"expected exactly one {job_name!r} job, found {len(starts)}")
    start = starts[0]
    end = next(
        (
            index
            for index in range(start + 1, len(lines))
            if JOB_KEY.match(lines[index])
        ),
        len(lines),
    )
    return "\n".join(lines[start:end])


def extract_step(job: str, step_name: str) -> str:
    marker = f"      - name: {step_name}"
    lines = job.splitlines()
    starts = [index for index, line in enumerate(lines) if line == marker]
    if len(starts) != 1:
        raise GuardFailure(
            f"the {JOB_NAME!r} job must contain exactly one {step_name!r} step, "
            f"found {len(starts)}"
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


def literal_run_body(step: str, step_name: str) -> str:
    lines = step.splitlines()
    markers = [index for index, line in enumerate(lines) if line == "        run: |"]
    scalar = [
        line.removeprefix("        run: ")
        for line in lines
        if line.startswith("        run: ") and line != "        run: |"
    ]
    if len(markers) == 0 and len(scalar) == 1:
        # Accept the pre-fix one-line form far enough to diagnose the actual
        # forced-execution gap.  A fixed workflow still needs a literal block
        # because the tee/result contract below cannot fit the scalar shape.
        return scalar[0]
    if len(markers) != 1 or scalar:
        raise GuardFailure(f"{step_name!r} must contain exactly one literal run block")
    return "\n".join(
        line[10:] if line.startswith("          ") else line
        for line in lines[markers[0] + 1 :]
    )


def gradle_tokens(run_body: str) -> tuple[str, ...]:
    logical = run_body.replace("\\\n", " ")
    commands = [
        line.strip()
        for line in logical.splitlines()
        if line.strip().startswith("./gradlew ")
    ]
    if len(commands) != 1:
        raise GuardFailure(
            f"{RUN_STEP_NAME!r} must contain exactly one ./gradlew command, "
            f"found {len(commands)}"
        )
    return tuple(shlex.split(commands[0], comments=False, posix=True))


def require_once(text: str, needle: str, context: str) -> None:
    count = text.count(needle)
    if count != 1:
        raise GuardFailure(f"{context} must contain {needle!r} exactly once, found {count}")


def validate_workflow(workflow: str) -> None:
    job = extract_job(workflow, JOB_NAME)
    run_step = extract_step(job, RUN_STEP_NAME)
    run_body = literal_run_body(run_step, RUN_STEP_NAME)
    tokens = gradle_tokens(run_body)
    try:
        pipe_index = tokens.index("|")
    except ValueError:
        pipe_index = len(tokens)
    gradle = tokens[:pipe_index]
    pipeline = tokens[pipe_index:]
    if gradle[:1] != ("./gradlew",):
        raise GuardFailure(f"{RUN_STEP_NAME!r} must invoke ./gradlew")

    requested_tasks = tuple(token for token in gradle[1:] if token.startswith(":"))
    if requested_tasks != EXPECTED_TASK_NAMES:
        raise GuardFailure(
            f"{RUN_STEP_NAME!r} must request the complete four-task Docker graph "
            f"exactly once and in canonical order; expected {list(EXPECTED_TASK_NAMES)}, "
            f"got {list(requested_tasks)}"
        )

    arguments = gradle[1 + len(requested_tasks) :]
    for flag in REQUIRED_FLAGS:
        count = arguments.count(flag)
        if count != 1:
            raise GuardFailure(f"{RUN_STEP_NAME!r} must pass {flag} exactly once, found {count}")
    if "--build-cache" in arguments:
        raise GuardFailure(
            f"{RUN_STEP_NAME!r} must not contradict --no-build-cache with --build-cache"
        )
    if any(
        argument == "-x"
        or argument == "--exclude-task"
        or argument.startswith("--exclude-task=")
        for argument in arguments
    ):
        raise GuardFailure(f"{RUN_STEP_NAME!r} must not exclude tasks from the pinned graph")
    require_once(run_step, f"        id: {RUN_STEP_ID}", RUN_STEP_NAME)
    if "set -o pipefail" not in run_body.splitlines():
        raise GuardFailure(f"{RUN_STEP_NAME!r} must set pipefail before teeing Gradle")
    if pipe_index == len(tokens):
        raise GuardFailure(
            f"{RUN_STEP_NAME!r} must tee the plain Gradle console for verification"
        )
    if pipeline != ("|", "tee", GRADLE_LOG):
        raise GuardFailure(
            f"{RUN_STEP_NAME!r} must pipe the complete plain Gradle console to {GRADLE_LOG}"
        )

    marker_step = extract_step(job, MARKER_STEP_NAME)
    marker_body = literal_run_body(marker_step, MARKER_STEP_NAME)
    if marker_body.strip() != f'touch "{RUN_MARKER}"':
        raise GuardFailure(f"{MARKER_STEP_NAME!r} must create {RUN_MARKER}")
    if job.index(marker_step) > job.index(run_step):
        raise GuardFailure(f"{MARKER_STEP_NAME!r} must run before {RUN_STEP_NAME!r}")

    result_step = extract_step(job, RESULT_STEP_NAME)
    require_once(
        result_step,
        f"        if: always() && steps.{RUN_STEP_ID}.conclusion != 'skipped'",
        RESULT_STEP_NAME,
    )
    result_body = literal_run_body(result_step, RESULT_STEP_NAME)
    required_result_fragments = (
        "scripts/check-ci-docker-integration-execution.py --verify-results",
        f'--newer-than "{RUN_MARKER}"',
        f'--gradle-log "{GRADLE_LOG}"',
    )
    for fragment in required_result_fragments:
        require_once(result_body, fragment, RESULT_STEP_NAME)
    if job.index(result_step) < job.index(run_step):
        raise GuardFailure(f"{RESULT_STEP_NAME!r} must run after {RUN_STEP_NAME!r}")

    upload_step = extract_step(job, UPLOAD_STEP_NAME)
    require_once(upload_step, "        if: always()", UPLOAD_STEP_NAME)
    require_once(upload_step, "        uses: actions/upload-artifact@v7", UPLOAD_STEP_NAME)
    for report_glob in ("**/build/reports/tests/", "**/build/test-results/"):
        require_once(
            upload_step,
            f"            {report_glob}",
            UPLOAD_STEP_NAME,
        )
    if job.index(upload_step) < job.index(result_step):
        raise GuardFailure(f"{UPLOAD_STEP_NAME!r} must run after {RESULT_STEP_NAME!r}")


def parse_console(log_path: Path) -> dict[str, list[str | None]]:
    if not log_path.is_file():
        raise GuardFailure(f"Gradle console log not found: {log_path}")
    observed: dict[str, list[str | None]] = {task: [] for task in EXPECTED_TASK_NAMES}
    for line in log_path.read_text(errors="replace").splitlines():
        match = TASK_LINE.match(line)
        if match and match.group("task") in observed:
            observed[match.group("task")].append(match.group("outcome"))
    violations: list[str] = []
    for task, outcomes in observed.items():
        if not outcomes:
            violations.append(
                f"{task} is absent from {log_path}; the task did not run or the "
                "plain-console capture is incomplete"
            )
            continue
        forbidden = [outcome for outcome in outcomes if outcome in FORBIDDEN_OUTCOMES]
        if forbidden:
            violations.append(
                f"{task} did not execute fresh: Gradle reported {forbidden[0]}"
            )
    if violations:
        raise GuardFailure("; ".join(violations))
    return observed


@dataclass(frozen=True)
class ResultCount:
    tests: int
    skipped: int
    failures: int
    errors: int

    @property
    def executed(self) -> int:
        return self.tests - self.skipped


def xml_integer(root: ET.Element, attribute: str, xml_path: Path) -> int:
    raw = root.get(attribute)
    if raw is None or not raw.isdigit():
        raise GuardFailure(f"{xml_path} has no valid {attribute!r} count")
    return int(raw)


def result_count(results_dir: Path, marker: Path) -> ResultCount:
    xml_files = sorted(results_dir.glob("TEST-*.xml")) if results_dir.is_dir() else []
    if not xml_files:
        raise GuardFailure(f"no TEST-*.xml found in {results_dir}")
    marker_mtime = marker.stat().st_mtime_ns
    stale = [xml_path for xml_path in xml_files if xml_path.stat().st_mtime_ns <= marker_mtime]
    if stale:
        raise GuardFailure(
            f"{stale[0]} is not newer than the run marker; stale XML is not evidence"
        )

    totals = ResultCount(0, 0, 0, 0)
    for xml_path in xml_files:
        try:
            suite = ET.parse(xml_path).getroot()
        except ET.ParseError as error:
            raise GuardFailure(f"cannot parse {xml_path}: {error}") from error
        if suite.tag != "testsuite":
            raise GuardFailure(f"{xml_path} root is {suite.tag!r}, expected 'testsuite'")
        totals = ResultCount(
            totals.tests + xml_integer(suite, "tests", xml_path),
            totals.skipped + xml_integer(suite, "skipped", xml_path),
            totals.failures + xml_integer(suite, "failures", xml_path),
            totals.errors + xml_integer(suite, "errors", xml_path),
        )
    return totals


def verify_results(root: Path, marker: Path, gradle_log: Path) -> str:
    if not marker.is_file():
        raise GuardFailure(f"run marker not found: {marker}")
    parse_console(gradle_log)
    rows: list[tuple[str, ResultCount]] = []
    violations: list[str] = []
    for spec in TASKS:
        try:
            counts = result_count(root / spec.results_relative, marker)
        except (GuardFailure, OSError) as error:
            violations.append(f"{spec.task}: {error}")
            continue
        rows.append((spec.task, counts))
        if counts.executed <= 0:
            violations.append(
                f"{spec.task} executed {counts.executed} tests "
                f"(tests={counts.tests}, skipped={counts.skipped})"
            )
        if counts.failures != 0 or counts.errors != 0:
            violations.append(
                f"{spec.task} has failures={counts.failures}, errors={counts.errors}"
            )
    if violations:
        raise GuardFailure("; ".join(violations))

    lines = [
        "Docker integration test counts (issue #2094)",
        "",
        "TASK                                           EXECUTED  SKIPPED  FAILURES  ERRORS",
    ]
    for task, counts in rows:
        lines.append(
            f"{task:<46} {counts.executed:>8}  {counts.skipped:>7}  "
            f"{counts.failures:>8}  {counts.errors:>6}"
        )
    lines.append("")
    lines.append(
        f"TOTAL EXECUTED: {sum(counts.executed for _, counts in rows)} "
        f"across {len(rows)} task(s)"
    )
    return "\n".join(lines)


def append_summary(summary_path: Path, report: str) -> None:
    lines = report.splitlines()
    rows = lines[3:-2]
    with summary_path.open("a") as summary:
        summary.write("### Docker integration test counts (issue #2094)\n\n")
        summary.write("| Task | Executed | Skipped | Failures | Errors |\n")
        summary.write("| --- | ---: | ---: | ---: | ---: |\n")
        for row in rows:
            fields = row.split()
            summary.write(
                f"| `{fields[0]}` | {fields[1]} | {fields[2]} | "
                f"{fields[3]} | {fields[4]} |\n"
            )
        summary.write(f"\n**{lines[-1]}**\n")


def canonical_workflow() -> str:
    tasks = " ".join(EXPECTED_TASK_NAMES)
    return f"""jobs:
  guards-static:
    steps:
      - name: Docker integration guard (#2094)
        run: |
          scripts/check-ci-docker-integration-execution.py --self-test
          scripts/check-ci-docker-integration-execution.py
  integration:
    steps:
      - name: {MARKER_STEP_NAME}
        run: |
          touch "{RUN_MARKER}"
      - name: {RUN_STEP_NAME}
        id: {RUN_STEP_ID}
        run: |
          set -o pipefail
          ./gradlew {tasks} \\
            --rerun-tasks --no-build-cache \\
            --no-daemon --stacktrace --console=plain \\
            2>&1 | tee "{GRADLE_LOG}"
      - name: {RESULT_STEP_NAME}
        if: always() && steps.{RUN_STEP_ID}.conclusion != 'skipped'
        run: |
          scripts/check-ci-docker-integration-execution.py --verify-results \\
            --newer-than "{RUN_MARKER}" \\
            --gradle-log "{GRADLE_LOG}"
      - name: {UPLOAD_STEP_NAME}
        if: always()
        uses: actions/upload-artifact@v7
        with:
          path: |
            **/build/reports/tests/
            **/build/test-results/
"""


def write_result_fixture(root: Path, spec: TaskSpec, marker: Path, **counts: int) -> None:
    source = root / spec.module / "src" / "integrationTest" / "java" / "SomeTest.kt"
    source.parent.mkdir(parents=True, exist_ok=True)
    source.write_text("class SomeTest\n")
    results = root / spec.results_relative
    results.mkdir(parents=True, exist_ok=True)
    xml_path = results / "TEST-SomeTest.xml"
    values = {"tests": 3, "skipped": 0, "failures": 0, "errors": 0}
    values.update(counts)
    xml_path.write_text(
        '<testsuite name="SomeTest" '
        + " ".join(f'{key}="{value}"' for key, value in values.items())
        + "></testsuite>\n"
    )
    fresh_ns = marker.stat().st_mtime_ns + 1_000_000_000
    os.utime(xml_path, ns=(fresh_ns, fresh_ns))


def self_test() -> None:
    checks = 0

    def accepted(name: str, action: object) -> None:
        nonlocal checks
        try:
            if callable(action):
                action()
        except Exception as error:  # pragma: no cover - diagnostic path
            raise GuardFailure(f"self-test expected {name} to pass: {error}") from error
        checks += 1

    def rejected(
        name: str,
        expected: str,
        action: object,
        *,
        forbidden: tuple[str, ...] = (),
    ) -> None:
        nonlocal checks
        try:
            if callable(action):
                action()
        except (GuardFailure, OSError) as error:
            message = str(error)
            if expected not in message:
                raise GuardFailure(
                    f"self-test {name} failed for the wrong reason: {error!s}; "
                    f"expected {expected!r}"
                ) from error
            leaked = [needle for needle in forbidden if needle in message]
            if leaked:
                raise GuardFailure(
                    f"self-test {name} was not selective; untouched task(s) "
                    f"also reported: {leaked}"
                ) from error
        else:
            raise GuardFailure(f"self-test accepted unsafe mutation: {name}")
        checks += 1

    canonical = canonical_workflow()
    accepted("canonical workflow", lambda: validate_workflow(canonical))
    workflow_mutations = (
        ("missing rerun", "--rerun-tasks", canonical.replace(" --rerun-tasks", "")),
        ("missing no-cache", "--no-build-cache", canonical.replace(" --no-build-cache", "")),
        ("contradicted cache", "contradict", canonical.replace("--no-daemon", "--build-cache --no-daemon")),
        ("duplicate rerun", "--rerun-tasks", canonical.replace("--rerun-tasks", "--rerun-tasks --rerun-tasks", 1)),
        ("duplicate no-cache", "--no-build-cache", canonical.replace("--no-build-cache", "--no-build-cache --no-build-cache", 1)),
        ("missing plain console", "--console=plain", canonical.replace(" --console=plain", "")),
        ("excluded task", "complete four-task", canonical.replace("--no-daemon", "-x :app:integrationTest --no-daemon")),
        ("missing pipefail", "pipefail", canonical.replace("          set -o pipefail\n", "")),
        ("missing tee", "must tee", canonical.replace(f' 2>&1 | tee "{GRADLE_LOG}"', "")),
        ("missing run id", f"id: {RUN_STEP_ID}", canonical.replace(f"        id: {RUN_STEP_ID}\n", "")),
        ("missing marker", MARKER_STEP_NAME, canonical.replace(f"      - name: {MARKER_STEP_NAME}", "      - name: Wrong marker")),
        ("missing result guard", RESULT_STEP_NAME, canonical.replace(f"      - name: {RESULT_STEP_NAME}", "      - name: Wrong result guard")),
        ("failure-only upload", "if: always()", canonical.replace(f"      - name: {UPLOAD_STEP_NAME}\n        if: always()", f"      - name: {UPLOAD_STEP_NAME}\n        if: failure()")),
        ("moved off integration job", "'integration' job", canonical.replace("  integration:\n", "  docker-other:\n")),
        ("duplicate task", "complete four-task", canonical.replace(EXPECTED_TASK_NAMES[2], f"{EXPECTED_TASK_NAMES[2]} {EXPECTED_TASK_NAMES[2]}", 1)),
    )
    for name, expected, fixture in workflow_mutations:
        rejected(name, expected, lambda fixture=fixture: validate_workflow(fixture))
    for task in EXPECTED_TASK_NAMES:
        fixture = canonical.replace(f" {task}", "", 1)
        rejected(f"drop {task}", "complete four-task", lambda fixture=fixture: validate_workflow(fixture))

    with tempfile.TemporaryDirectory(prefix="issue-2094-selftest-") as temp:
        root = Path(temp)
        marker = root / "run-start"
        marker.touch()
        for spec in TASKS:
            write_result_fixture(root, spec, marker)
        log = root / "gradle.log"
        healthy_lines = [f"> Task {task}" for task in EXPECTED_TASK_NAMES]
        log.write_text("\n".join(healthy_lines) + "\nBUILD SUCCESSFUL\n")
        accepted("fresh four-task result", lambda: verify_results(root, marker, log))

        for spec in TASKS:
            for outcome in ("FROM-CACHE", "UP-TO-DATE", "SKIPPED", "NO-SOURCE"):
                mutated = root / f"{spec.module.replace('/', '-')}-{outcome}.log"
                mutated.write_text(
                    log.read_text().replace(f"> Task {spec.task}\n", f"> Task {spec.task} {outcome}\n")
                )
                rejected(
                    f"{spec.task} {outcome}",
                    f"{spec.task} did not execute fresh: Gradle reported {outcome}",
                    lambda mutated=mutated: verify_results(root, marker, mutated),
                    forbidden=tuple(
                        other.task for other in TASKS if other.task != spec.task
                    ),
                )

            absent = root / f"{spec.module.replace('/', '-')}-absent.log"
            absent.write_text(log.read_text().replace(f"> Task {spec.task}\n", ""))
            rejected(
                f"{spec.task} absent",
                f"{spec.task} is absent",
                lambda absent=absent: verify_results(root, marker, absent),
                forbidden=tuple(
                    other.task for other in TASKS if other.task != spec.task
                ),
            )

        for spec in TASKS:
            write_result_fixture(root, spec, marker, tests=3, skipped=3)
            rejected(
                f"{spec.task} all tests skipped",
                f"{spec.task} executed 0 tests",
                lambda: verify_results(root, marker, log),
                forbidden=tuple(
                    other.task for other in TASKS if other.task != spec.task
                ),
            )
            write_result_fixture(root, spec, marker)

        failure_spec = TASKS[3]
        write_result_fixture(root, failure_spec, marker, failures=1)
        rejected(
            "XML failure count",
            f"{failure_spec.task} has failures=1, errors=0",
            lambda: verify_results(root, marker, log),
        )
        write_result_fixture(root, failure_spec, marker)

        error_spec = TASKS[0]
        write_result_fixture(root, error_spec, marker, errors=1)
        rejected(
            "XML error count",
            f"{error_spec.task} has failures=0, errors=1",
            lambda: verify_results(root, marker, log),
        )
        write_result_fixture(root, error_spec, marker)

        for spec in TASKS:
            xml_path = root / spec.results_relative / "TEST-SomeTest.xml"
            xml_path.unlink()
            rejected(
                f"{spec.task} missing XML",
                f"{spec.task}: no TEST-*.xml",
                lambda: verify_results(root, marker, log),
                forbidden=tuple(
                    other.task for other in TASKS if other.task != spec.task
                ),
            )
            write_result_fixture(root, spec, marker)

        stale_spec = TASKS[1]
        stale_path = root / stale_spec.results_relative / "TEST-SomeTest.xml"
        stale_ns = marker.stat().st_mtime_ns - 1_000_000_000
        os.utime(stale_path, ns=(stale_ns, stale_ns))
        rejected(
            "stale XML",
            f"{stale_spec.task}:",
            lambda: verify_results(root, marker, log),
        )
        write_result_fixture(root, stale_spec, marker)

        empty_log = root / "empty.log"
        empty_log.write_text("")
        rejected(
            "unparsable console",
            f"{EXPECTED_TASK_NAMES[0]} is absent",
            lambda: verify_results(root, marker, empty_log),
        )

        summary = root / "summary.md"
        report = verify_results(root, marker, log)
        append_summary(summary, report)
        accepted(
            "summary contains all task counts",
            lambda: (
                None
                if all(task in summary.read_text() for task in EXPECTED_TASK_NAMES)
                else (_ for _ in ()).throw(GuardFailure("summary lost a task count"))
            ),
        )

    expected_checks = 54
    if checks != expected_checks:
        raise GuardFailure(f"self-test ran {checks} checks, expected {expected_checks}")
    print(f"PASS: CI Docker integration execution guard self-test ({checks} checks)")


def main() -> None:
    arguments = sys.argv[1:]
    if arguments == ["--self-test"]:
        self_test()
        return
    if arguments and arguments[0] == "--verify-results":
        root = ROOT
        marker: Path | None = None
        gradle_log: Path | None = None
        index = 1
        while index < len(arguments):
            option = arguments[index]
            if option in {"--root", "--newer-than", "--gradle-log"} and index + 1 < len(arguments):
                value = Path(arguments[index + 1])
                if option == "--root":
                    root = value.resolve()
                elif option == "--newer-than":
                    marker = value
                else:
                    gradle_log = value
                index += 2
            else:
                raise GuardFailure(
                    "usage: check-ci-docker-integration-execution.py --verify-results "
                    "[--root DIR] --newer-than FILE --gradle-log FILE"
                )
        if marker is None or gradle_log is None:
            raise GuardFailure("--verify-results requires --newer-than and --gradle-log")
        report = verify_results(root, marker, gradle_log)
        print(report)
        if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
            append_summary(Path(summary), report)
        return
    if len(arguments) > 1 or (arguments and arguments[0].startswith("--")):
        raise GuardFailure(
            "usage: check-ci-docker-integration-execution.py "
            "[--self-test | WORKFLOW | --verify-results ...]"
        )
    workflow_path = Path(arguments[0]) if arguments else DEFAULT_WORKFLOW
    validate_workflow(workflow_path.read_text())
    print(
        "PASS: CI Docker integration graph forces fresh execution, verifies "
        "per-task results, and preserves reports"
    )


try:
    main()
except (GuardFailure, OSError) as error:
    sys.stderr.write(f"FAIL: {error}\n")
    raise SystemExit(1)
