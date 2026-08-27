#!/usr/bin/python3 -I
"""Prove that the required Python CI job executed a real pytest suite.

Issue #2302 closes the gap between ``pytest`` exiting successfully and the
required Python check proving that tests actually ran.  The workflow writes
one deterministic JUnit report, this guard reads that exact path, and the
report is uploaded even when pytest or this guard fails.

The report is intentionally checked independently of pytest's exit status:

* ``total == 0`` is not evidence;
* ``skipped == total`` is the all-skipped vacuous pass;
* failures and errors are red even when the XML is otherwise well formed; and
* a positive floor prevents a partially wired or nearly empty suite from
  replacing the real one while still allowing legitimate conditional skips.

The required workflow floor is explicitly 1000 executed tests.  It is a lower
bound, not an exact suite-size ratchet.

Usage:
  scripts/python-ci.sh                                   # check workflow wiring
  scripts/python-ci.sh --self-test                       # synthetic red->green proof
  scripts/python-ci.sh --ci                              # self-test plus workflow check
  scripts/python-ci.sh --verify-results \
      --xml PATH --min-executed N                        # check one report
"""

from __future__ import annotations

import os
import re
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parent.parent
DEFAULT_WORKFLOW = ROOT / ".github" / "workflows" / "tests.yml"
DEFAULT_TEST_RUNNER = ROOT / "scripts" / "run-python-tests.sh"

JOB_NAME = "python"
JOB_CHECK_NAME = "Python utility tests (pocketshell)"
GUARD_STEP_NAME = "Python guard"
RUN_STEP_NAME = "Run pytest"
RUN_STEP_ID = "python_tests"
VERIFY_STEP_NAME = "Python verify"
UPLOAD_STEP_NAME = "Python report"
CHECK_WRAPPER = "scripts/python-ci.sh"
TEST_RUNNER_COMMAND = "scripts/run-python-tests.sh"
TEST_RUNNER_WORKING_DIRECTORY = 'cd "$ROOT_DIR/tools/pocketshell"'
MINIMUM_CONTRACT_COMMENT = "# Contract: checker floor = 1000 executed tests, a lower bound."

# These strings are part of the workflow contract.  RUNNER_TEMP is expanded by
# the shell in run steps; runner.temp is the same runner-owned directory in the
# upload action.  Keeping one fixed filename makes the report addressable and
# prevents a broad glob from selecting a stale or unrelated XML file.
JUNIT_RUN_PATH = "${RUNNER_TEMP}/pocketshell-python-test-results.xml"
JUNIT_UPLOAD_PATH = "${{ runner.temp }}/pocketshell-python-test-results.xml"
MIN_EXPECTED_EXECUTED_TESTS = 1000

JOB_KEY = re.compile(r"^  ([A-Za-z0-9_-]+):[ \t]*(#.*)?$")
COUNT_ATTRIBUTES = ("tests", "failures", "errors", "skipped")
COUNT_VALUE = re.compile(r"^[0-9]+$")
ALLOWED_UPLOAD_INPUT_KEYS = frozenset({"name", "path", "overwrite", "if-no-files-found"})


class GuardFailure(ValueError):
    """The workflow or JUnit report does not prove Python execution."""


@dataclass(frozen=True)
class TestCounts:
    total: int
    failures: int
    errors: int
    skipped: int

    @property
    def executed(self) -> int:
        return self.total - self.skipped


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
        (index for index in range(start + 1, len(lines)) if JOB_KEY.match(lines[index])),
        len(lines),
    )
    return "\n".join(lines[start:end])


def extract_step(job: str, step_name: str) -> str:
    markers = {
        f"      - name: {step_name}",
        f'      - name: "{step_name}"',
        f"      - name: '{step_name}'",
    }
    lines = job.splitlines()
    starts = [index for index, line in enumerate(lines) if line in markers]
    if len(starts) != 1:
        raise GuardFailure(
            f"the {JOB_NAME!r} job must contain exactly one {step_name!r} step, found {len(starts)}"
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
    inline = [
        line.removeprefix("        run: ")
        for line in lines
        if line.startswith("        run: ") and line != "        run: |"
    ]
    if len(markers) == 1 and not inline:
        return "\n".join(line.removeprefix("          ") for line in lines[markers[0] + 1 :])
    if not markers and len(inline) == 1:
        return inline[0]
    raise GuardFailure(f"{step_name!r} must contain exactly one run block")


def require_once(text: str, needle: str, context: str) -> None:
    count = text.count(needle)
    if count != 1:
        raise GuardFailure(f"{context} must contain {needle!r} exactly once, found {count}")


def require_command_once(body: str, command: str, context: str) -> None:
    commands = [line.strip() for line in body.splitlines() if line.strip()]
    if commands.count(command) != 1:
        raise GuardFailure(
            f"{context} must contain command {command!r} exactly once, "
            f"found {commands.count(command)}"
        )


def load_workflow_yaml(workflow: str) -> dict[object, object]:
    try:
        document = yaml.safe_load(workflow)
    except yaml.YAMLError as error:
        raise GuardFailure(f"semantic YAML parse failed: {error}") from error
    if not isinstance(document, dict):
        raise GuardFailure("semantic YAML must contain a mapping at its root")
    jobs = document.get("jobs")
    if not isinstance(jobs, dict):
        raise GuardFailure("semantic YAML must contain a jobs mapping")
    job = jobs.get(JOB_NAME)
    if not isinstance(job, dict):
        raise GuardFailure(f"semantic YAML has no mapping for the {JOB_NAME!r} job")
    steps = job.get("steps")
    if not isinstance(steps, list):
        raise GuardFailure(f"semantic YAML {JOB_NAME!r} job must contain a steps list")
    for index, step in enumerate(steps):
        if not isinstance(step, dict):
            raise GuardFailure(f"semantic YAML step {index} is not a mapping")
    required_names = (GUARD_STEP_NAME, VERIFY_STEP_NAME, UPLOAD_STEP_NAME)
    for name in required_names:
        matches = [step for step in steps if step.get("name") == name]
        if len(matches) != 1:
            raise GuardFailure(
                f"semantic YAML must contain exactly one step named {name!r}, found {len(matches)}"
            )
    return document


def semantic_step(document: dict[object, object], step_name: str) -> dict[object, object]:
    jobs = document["jobs"]
    assert isinstance(jobs, dict)
    job = jobs[JOB_NAME]
    assert isinstance(job, dict)
    steps = job["steps"]
    assert isinstance(steps, list)
    matches = [step for step in steps if isinstance(step, dict) and step.get("name") == step_name]
    if len(matches) != 1:  # load_workflow_yaml provides the user-facing error
        raise GuardFailure(f"semantic YAML step lookup failed for {step_name!r}")
    return matches[0]


def validate_test_runner(runner: str) -> None:
    require_once(runner, TEST_RUNNER_WORKING_DIRECTORY, "Python test runner")
    require_once(runner, f'rm -f "{JUNIT_RUN_PATH}"', "Python test runner")
    require_once(
        runner,
        f'uv run pytest -v --junitxml="{JUNIT_RUN_PATH}"',
        "Python test runner",
    )
    pytest_commands = [
        line.strip().removeprefix("exec ")
        for line in runner.splitlines()
        if line.strip().removeprefix("exec ").startswith("uv run pytest")
    ]
    if len(pytest_commands) != 1:
        raise GuardFailure(
            "Python test runner must contain exactly one uv run pytest command, "
            f"found {len(pytest_commands)}"
        )


def validate_workflow(workflow: str) -> None:
    document = load_workflow_yaml(workflow)
    job = extract_job(workflow, JOB_NAME)
    require_once(job, f"    name: {JOB_CHECK_NAME}", f"{JOB_NAME!r} job")
    if re.search(r"^    if:", job, re.MULTILINE):
        raise GuardFailure(f"{JOB_NAME!r} is a required check and must not have a job-level if:")

    guard_step = extract_step(job, GUARD_STEP_NAME)
    guard_body = literal_run_body(guard_step, GUARD_STEP_NAME)
    if guard_body.strip() != f"{CHECK_WRAPPER} --ci":
        raise GuardFailure(
            f"{GUARD_STEP_NAME!r} must run {CHECK_WRAPPER!r} with --ci "
            "(self-test plus real workflow validation) exactly once"
        )

    run_step = extract_step(job, RUN_STEP_NAME)
    require_once(run_step, f"        id: {RUN_STEP_ID}", RUN_STEP_NAME)
    run_body = literal_run_body(run_step, RUN_STEP_NAME)
    if run_body.strip() != TEST_RUNNER_COMMAND:
        raise GuardFailure(
            f"{RUN_STEP_NAME!r} must call the exact Python test runner {TEST_RUNNER_COMMAND!r}"
        )

    verify_step = extract_step(job, VERIFY_STEP_NAME)
    require_once(
        verify_step,
        f"        if: always() && steps.{RUN_STEP_ID}.conclusion != 'skipped'",
        VERIFY_STEP_NAME,
    )
    verify_lines = verify_step.splitlines()
    verify_run_indices = [
        index for index, line in enumerate(verify_lines) if line.startswith("        run: ")
    ]
    if (
        len(verify_run_indices) != 1
        or verify_run_indices[0] == 0
        or verify_lines[verify_run_indices[0] - 1] != f"        {MINIMUM_CONTRACT_COMMENT}"
    ):
        raise GuardFailure(
            f"{VERIFY_STEP_NAME!r} must retain adjacent minimum contract documentation "
            f"before its --min-executed command: {MINIMUM_CONTRACT_COMMENT!r}"
        )
    verify_body = literal_run_body(verify_step, VERIFY_STEP_NAME)
    require_once(
        verify_body,
        f"{CHECK_WRAPPER} --verify-results",
        VERIFY_STEP_NAME,
    )
    require_once(verify_body, f'--xml "{JUNIT_RUN_PATH}"', VERIFY_STEP_NAME)
    if verify_body.count("--xml") != 1:
        raise GuardFailure(f"{VERIFY_STEP_NAME!r} must inspect exactly one --xml path")

    minimum_matches = re.findall(r"--min-executed[ \t]+([0-9]+)", verify_body)
    if len(minimum_matches) != 1:
        raise GuardFailure(
            f"{VERIFY_STEP_NAME!r} must pass exactly one explicit --min-executed value"
        )
    minimum = int(minimum_matches[0])
    if minimum <= 0:
        raise GuardFailure(f"{VERIFY_STEP_NAME!r} minimum expected executed count must be positive")
    if minimum != MIN_EXPECTED_EXECUTED_TESTS:
        raise GuardFailure(
            f"{VERIFY_STEP_NAME!r} must use the documented minimum "
            f"{MIN_EXPECTED_EXECUTED_TESTS}, got {minimum}"
        )

    upload_step = extract_step(job, UPLOAD_STEP_NAME)
    upload_data = semantic_step(document, UPLOAD_STEP_NAME)
    if upload_data.get("if") != "always()":
        raise GuardFailure(f"{UPLOAD_STEP_NAME!r} must use semantic if: always()")
    if upload_data.get("uses") != "actions/upload-artifact@v7":
        raise GuardFailure(f"{UPLOAD_STEP_NAME!r} must use actions/upload-artifact@v7")
    upload_with = upload_data.get("with")
    if not isinstance(upload_with, dict):
        raise GuardFailure(f"{UPLOAD_STEP_NAME!r} must contain a semantic with mapping")
    if "retention-days" in upload_with:
        raise GuardFailure(
            f"{UPLOAD_STEP_NAME!r} must inherit repository artifact retention; "
            "retention-days overrides are not allowed"
        )
    unexpected_upload_keys = sorted(set(upload_with) - ALLOWED_UPLOAD_INPUT_KEYS)
    if unexpected_upload_keys:
        raise GuardFailure(
            f"{UPLOAD_STEP_NAME!r} has unsupported upload input(s): "
            f"{unexpected_upload_keys!r}; allowed inputs are "
            f"{sorted(ALLOWED_UPLOAD_INPUT_KEYS)!r}"
        )
    expected_upload_inputs = {
        "name": "python-test-reports",
        "path": JUNIT_UPLOAD_PATH,
        "overwrite": True,
        "if-no-files-found": "error",
    }
    for key, expected in expected_upload_inputs.items():
        if upload_with.get(key) != expected:
            raise GuardFailure(
                f"{UPLOAD_STEP_NAME!r} semantic with.{key} must be {expected!r}, "
                f"got {upload_with.get(key)!r}"
            )
    if "*" in upload_step:
        raise GuardFailure(f"{UPLOAD_STEP_NAME!r} must upload the exact JUnit path, not a glob")

    if job.index(guard_step) > job.index(run_step):
        raise GuardFailure(f"{GUARD_STEP_NAME!r} must run before {RUN_STEP_NAME!r}")
    if job.index(verify_step) < job.index(run_step):
        raise GuardFailure(f"{VERIFY_STEP_NAME!r} must run after {RUN_STEP_NAME!r}")
    if job.index(upload_step) < job.index(verify_step):
        raise GuardFailure(f"{UPLOAD_STEP_NAME!r} must run after {VERIFY_STEP_NAME!r}")


def parse_xml_count(element: ET.Element, attribute: str, xml_path: Path) -> int:
    raw = element.get(attribute)
    if raw is None or not COUNT_VALUE.fullmatch(raw):
        raise GuardFailure(f"{xml_path} has no valid non-negative {attribute!r} count")
    return int(raw)


def parse_junit(xml_path: Path) -> TestCounts:
    if not xml_path.is_file():
        raise GuardFailure(f"JUnit artifact not found: {xml_path}")
    try:
        root = ET.parse(xml_path).getroot()
    except ET.ParseError as error:
        raise GuardFailure(f"cannot parse JUnit artifact {xml_path}: {error}") from error
    except OSError as error:
        raise GuardFailure(f"cannot read JUnit artifact {xml_path}: {error}") from error

    if root.tag == "testsuite":
        suites = [root]
    elif root.tag == "testsuites":
        suites = [child for child in root if child.tag == "testsuite"]
    else:
        raise GuardFailure(f"{xml_path} root is {root.tag!r}, expected 'testsuites' or 'testsuite'")
    if not suites:
        raise GuardFailure(f"{xml_path} contains no testsuite elements")

    totals = TestCounts(0, 0, 0, 0)
    for suite in suites:
        counts = TestCounts(
            total=parse_xml_count(suite, "tests", xml_path),
            failures=parse_xml_count(suite, "failures", xml_path),
            errors=parse_xml_count(suite, "errors", xml_path),
            skipped=parse_xml_count(suite, "skipped", xml_path),
        )
        if counts.skipped > counts.total:
            raise GuardFailure(
                f"{xml_path} has skipped={counts.skipped} greater than tests={counts.total}"
            )
        if counts.failures + counts.errors + counts.skipped > counts.total:
            raise GuardFailure(
                f"{xml_path} has inconsistent tests/failures/errors/skipped counts: "
                f"tests={counts.total}, failures={counts.failures}, "
                f"errors={counts.errors}, skipped={counts.skipped}"
            )
        totals = TestCounts(
            total=totals.total + counts.total,
            failures=totals.failures + counts.failures,
            errors=totals.errors + counts.errors,
            skipped=totals.skipped + counts.skipped,
        )

    # Pytest currently leaves aggregate counts off the <testsuites> element,
    # but if a producer supplies them, they are part of the same artifact and
    # must agree with the suite totals rather than becoming a second oracle.
    present_root_counts = [attribute for attribute in COUNT_ATTRIBUTES if attribute in root.attrib]
    if present_root_counts:
        if len(present_root_counts) != len(COUNT_ATTRIBUTES):
            raise GuardFailure(
                f"{xml_path} has only some aggregate testsuites counts: {present_root_counts}"
            )
        root_totals = TestCounts(
            total=parse_xml_count(root, "tests", xml_path),
            failures=parse_xml_count(root, "failures", xml_path),
            errors=parse_xml_count(root, "errors", xml_path),
            skipped=parse_xml_count(root, "skipped", xml_path),
        )
        if root_totals != totals:
            raise GuardFailure(
                f"{xml_path} aggregate totals do not match its testsuite totals: "
                f"root={root_totals}, suites={totals}"
            )

    return totals


def verify_results(xml_path: Path, minimum: int) -> TestCounts:
    if minimum <= 0:
        raise GuardFailure(f"minimum expected executed count must be positive, got {minimum}")
    counts = parse_junit(xml_path)
    violations: list[str] = []
    if counts.total == 0:
        violations.append("total=0; the Python suite collected no tests")
    if counts.failures != 0:
        violations.append(f"failures={counts.failures}")
    if counts.errors != 0:
        violations.append(f"errors={counts.errors}")
    if counts.total > 0 and counts.skipped == counts.total:
        violations.append(f"all tests are skipped (skipped == total == {counts.total})")
    if counts.executed <= 0:
        violations.append(f"executed={counts.executed} must be positive")
    if counts.executed < minimum:
        violations.append(f"executed={counts.executed} is below minimum expected {minimum}")
    if violations:
        raise GuardFailure(
            f"{xml_path}: "
            f"total={counts.total}, executed={counts.executed}, "
            f"skipped={counts.skipped}, failures={counts.failures}, errors={counts.errors}; "
            + "; ".join(violations)
        )
    return counts


def format_report(counts: TestCounts, minimum: int) -> str:
    return "\n".join(
        (
            "Python pytest execution counts (issue #2302)",
            "",
            f"TOTAL: {counts.total}",
            f"EXECUTED: {counts.executed}",
            f"SKIPPED: {counts.skipped}",
            f"FAILURES: {counts.failures}",
            f"ERRORS: {counts.errors}",
            f"MINIMUM EXPECTED EXECUTED: {minimum}",
        )
    )


def append_summary(summary_path: Path, counts: TestCounts, minimum: int) -> None:
    with summary_path.open("a", encoding="utf-8") as summary:
        summary.write("### Python pytest execution counts (issue #2302)\n\n")
        summary.write("| Total | Executed | Skipped | Failures | Errors | Minimum |\n")
        summary.write("| ---: | ---: | ---: | ---: | ---: | ---: |\n")
        summary.write(
            f"| {counts.total} | {counts.executed} | {counts.skipped} | "
            f"{counts.failures} | {counts.errors} | {minimum} |\n"
        )


def write_xml(
    path: Path,
    *,
    total: int,
    skipped: int,
    failures: int = 0,
    errors: int = 0,
    root_counts: bool = True,
    root_override: dict[str, int] | None = None,
) -> None:
    suite_values = {
        "tests": total,
        "failures": failures,
        "errors": errors,
        "skipped": skipped,
    }
    root_values = root_override if root_override is not None else suite_values
    root_attributes = ' name="pytest tests"'
    if root_counts:
        root_attributes += " " + " ".join(
            f'{attribute}="{root_values[attribute]}"' for attribute in COUNT_ATTRIBUTES
        )
    suite_attributes = " ".join(
        f'{attribute}="{suite_values[attribute]}"' for attribute in COUNT_ATTRIBUTES
    )
    path.write_text(
        '<?xml version="1.0" encoding="utf-8"?>'
        f'<testsuites{root_attributes}><testsuite name="pytest" '
        f"{suite_attributes}></testsuite></testsuites>\n",
        encoding="utf-8",
    )


def canonical_test_runner() -> str:
    return f'''#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${{BASH_SOURCE[0]}}")/.." && pwd)"
cd "$ROOT_DIR/tools/pocketshell"
rm -f "{JUNIT_RUN_PATH}"
exec uv run pytest -v --junitxml="{JUNIT_RUN_PATH}"
'''


def canonical_workflow() -> str:
    return f"""jobs:
  python:
    name: {JOB_CHECK_NAME}
    runs-on: ubuntu-latest
    steps:
      - name: {GUARD_STEP_NAME}
        run: {CHECK_WRAPPER} --ci
      - name: {RUN_STEP_NAME}
        id: {RUN_STEP_ID}
        run: {TEST_RUNNER_COMMAND}
      - name: {VERIFY_STEP_NAME}
        if: always() && steps.{RUN_STEP_ID}.conclusion != 'skipped'
        {MINIMUM_CONTRACT_COMMENT}
        run: {CHECK_WRAPPER} --verify-results --xml \"{JUNIT_RUN_PATH}\" --min-executed {MIN_EXPECTED_EXECUTED_TESTS}
      - name: {UPLOAD_STEP_NAME}
        if: always()
        uses: actions/upload-artifact@v7
        with: {{name: python-test-reports, path: '{JUNIT_UPLOAD_PATH}', overwrite: true, if-no-files-found: error}}
"""


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

    def rejected(name: str, expected: str, action: object) -> None:
        nonlocal checks
        try:
            if callable(action):
                action()
        except (GuardFailure, OSError) as error:
            message = str(error)
            if expected not in message:
                raise GuardFailure(
                    f"self-test {name} failed for the wrong reason: {message!s}; "
                    f"expected {expected!r}"
                ) from error
        else:
            raise GuardFailure(f"self-test accepted unsafe mutation: {name}")
        checks += 1

    with tempfile.TemporaryDirectory(prefix="issue-2302-selftest-") as temp:
        root = Path(temp)

        mixed = root / "mixed.xml"
        write_xml(mixed, total=4, skipped=1)
        accepted(
            "mixed pass/skip report",
            lambda: (
                None
                if verify_results(mixed, 3) == TestCounts(4, 0, 0, 1)
                else (_ for _ in ()).throw(GuardFailure("mixed counts changed"))
            ),
        )

        zero = root / "zero.xml"
        write_xml(zero, total=0, skipped=0)
        rejected("zero total report", "total=0", lambda: verify_results(zero, 1))

        all_skipped = root / "all-skipped.xml"
        write_xml(all_skipped, total=3, skipped=3)
        rejected(
            "all-skipped report",
            "all tests are skipped",
            lambda: verify_results(all_skipped, 1),
        )

        failure = root / "failure.xml"
        write_xml(failure, total=3, skipped=0, failures=1)
        rejected("failure report", "failures=1", lambda: verify_results(failure, 1))

        error = root / "error.xml"
        write_xml(error, total=3, skipped=0, errors=1)
        rejected("error report", "errors=1", lambda: verify_results(error, 1))

        below_floor = root / "below-floor.xml"
        write_xml(below_floor, total=999, skipped=0)
        rejected(
            "positive execution floor",
            "below minimum expected 1000",
            lambda: verify_results(below_floor, MIN_EXPECTED_EXECUTED_TESTS),
        )

        inconsistent_root = root / "inconsistent-root.xml"
        write_xml(
            inconsistent_root,
            total=3,
            skipped=0,
            root_override={"tests": 4, "failures": 0, "errors": 0, "skipped": 0},
        )
        rejected(
            "inconsistent aggregate totals",
            "aggregate totals do not match",
            lambda: parse_junit(inconsistent_root),
        )

        missing = root / "missing.xml"
        rejected("missing exact artifact", "JUnit artifact not found", lambda: parse_junit(missing))

    canonical = canonical_workflow()
    accepted("canonical workflow", lambda: validate_workflow(canonical))
    canonical_runner = canonical_test_runner()
    accepted("canonical Python test runner", lambda: validate_test_runner(canonical_runner))
    runner_mutations = (
        (
            "missing project working directory",
            "tools/pocketshell",
            canonical_runner.replace(f"{TEST_RUNNER_WORKING_DIRECTORY}\n", ""),
        ),
        (
            "missing JUnit option",
            "junitxml",
            canonical_runner.replace(f' --junitxml="{JUNIT_RUN_PATH}"', ""),
        ),
        (
            "wrong run artifact path",
            JUNIT_RUN_PATH,
            canonical_runner.replace(JUNIT_RUN_PATH, "${RUNNER_TEMP}/other.xml"),
        ),
        (
            "missing deterministic cleanup",
            "rm -f",
            canonical_runner.replace(f'rm -f "{JUNIT_RUN_PATH}"\n', ""),
        ),
    )
    for name, expected, fixture in runner_mutations:
        rejected(name, expected, lambda fixture=fixture: validate_test_runner(fixture))

    workflow_mutations = (
        (
            "missing guard self-test",
            "--ci",
            canonical.replace(f"run: {CHECK_WRAPPER} --ci", f"run: {CHECK_WRAPPER}"),
        ),
        (
            "missing verifier",
            "semantic YAML",
            canonical.replace(f"      - name: {VERIFY_STEP_NAME}", "      - name: Wrong verifier"),
        ),
        (
            "failure-only verifier",
            "always()",
            canonical.replace(
                f"        if: always() && steps.{RUN_STEP_ID}.conclusion != 'skipped'",
                "        if: failure()",
            ),
        ),
        (
            "wrong verifier artifact path",
            JUNIT_RUN_PATH,
            canonical.replace(
                f'--xml "{JUNIT_RUN_PATH}"',
                '--xml "${RUNNER_TEMP}/other.xml"',
            ),
        ),
        (
            "missing positive minimum",
            "--min-executed",
            canonical.replace(f" --min-executed {MIN_EXPECTED_EXECUTED_TESTS}", ""),
        ),
        (
            "zero minimum",
            "positive",
            canonical.replace(f"--min-executed {MIN_EXPECTED_EXECUTED_TESTS}", "--min-executed 0"),
        ),
        (
            "failure-only upload",
            "if: always()",
            canonical.replace(
                f"      - name: {UPLOAD_STEP_NAME}\n        if: always()",
                f"      - name: {UPLOAD_STEP_NAME}\n        if: failure()",
            ),
        ),
        (
            "wrong upload artifact path",
            JUNIT_UPLOAD_PATH,
            canonical.replace(JUNIT_UPLOAD_PATH, "${{ runner.temp }}/other.xml"),
        ),
        (
            "missing overwrite",
            "overwrite",
            canonical.replace(", overwrite: true", ""),
        ),
        (
            "missing artifact failure",
            "semantic with.if-no-files-found",
            canonical.replace(", if-no-files-found: error", ""),
        ),
        (
            "explicit retention override",
            "retention-days",
            canonical.replace("overwrite: true", "overwrite: true, retention-days: 1"),
        ),
        (
            "unexpected upload input",
            "compression-level",
            canonical.replace(
                "if-no-files-found: error}",
                "if-no-files-found: error, compression-level: 9}",
            ),
        ),
        (
            "optional required Python job",
            "required check",
            canonical.replace(
                f"    name: {JOB_CHECK_NAME}\n",
                f"    if: github.event_name == 'push'\n    name: {JOB_CHECK_NAME}\n",
            ),
        ),
    )
    for name, expected, fixture in workflow_mutations:
        rejected(name, expected, lambda fixture=fixture: validate_workflow(fixture))

    floor_documentation_mutations = (
        (
            "missing adjacent floor documentation",
            canonical.replace(f"        {MINIMUM_CONTRACT_COMMENT}\n", ""),
        ),
        (
            "altered adjacent floor documentation",
            canonical.replace(
                f"        {MINIMUM_CONTRACT_COMMENT}",
                "        # Contract: checker floor = 1000 executed tests; exact count.",
            ),
        ),
    )
    for name, fixture in floor_documentation_mutations:
        rejected(
            name,
            "adjacent minimum contract documentation",
            lambda fixture=fixture: validate_workflow(fixture),
        )

    unsafe_name_mutations = (
        (
            "unquoted issue marker in guard name",
            canonical.replace(
                f"      - name: {GUARD_STEP_NAME}",
                "      - name: Python guard (issue #2302)",
            ),
        ),
        (
            "unquoted issue marker in verify name",
            canonical.replace(
                f"      - name: {VERIFY_STEP_NAME}",
                "      - name: Python verify (issue #2302)",
            ),
        ),
        (
            "unquoted issue marker in upload name",
            canonical.replace(
                f"      - name: {UPLOAD_STEP_NAME}",
                "      - name: Python report (issue #2302)",
            ),
        ),
    )
    for name, fixture in unsafe_name_mutations:
        rejected(name, "semantic YAML", lambda fixture=fixture: validate_workflow(fixture))

    expected_checks = 32
    if checks != expected_checks:
        raise GuardFailure(f"self-test ran {checks} checks, expected {expected_checks}")
    print(f"PASS: Python test execution guard self-test ({checks} checks)")


def parse_verify_arguments(arguments: list[str]) -> tuple[Path, int]:
    if len(arguments) != 5 or arguments[0] != "--verify-results":
        raise GuardFailure(
            "usage: check-python-test-execution.py --verify-results --xml PATH --min-executed N"
        )
    values: dict[str, str] = {}
    index = 1
    while index < len(arguments):
        option = arguments[index]
        if option not in {"--xml", "--min-executed"} or index + 1 >= len(arguments):
            raise GuardFailure(
                "usage: check-python-test-execution.py --verify-results --xml PATH --min-executed N"
            )
        if option in values:
            raise GuardFailure(f"duplicate {option} option")
        values[option] = arguments[index + 1]
        index += 2
    raw_minimum = values.get("--min-executed")
    if raw_minimum is None or not COUNT_VALUE.fullmatch(raw_minimum):
        raise GuardFailure("--min-executed must be a non-negative integer")
    return Path(values["--xml"]), int(raw_minimum)


def main() -> None:
    arguments = sys.argv[1:]
    if arguments == ["--self-test"]:
        self_test()
        return
    if arguments == ["--ci"]:
        self_test()
        validate_workflow(DEFAULT_WORKFLOW.read_text(encoding="utf-8"))
        validate_test_runner(DEFAULT_TEST_RUNNER.read_text(encoding="utf-8"))
        print(
            "PASS: required Python job writes, verifies, and uploads one exact JUnit "
            "artifact with a positive execution floor"
        )
        return
    if arguments and arguments[0] == "--verify-results":
        xml_path, minimum = parse_verify_arguments(arguments)
        counts = verify_results(xml_path, minimum)
        report = format_report(counts, minimum)
        print(report)
        if summary := os.environ.get("GITHUB_STEP_SUMMARY"):
            append_summary(Path(summary), counts, minimum)
        return
    if arguments:
        raise GuardFailure(
            "usage: check-python-test-execution.py "
            "[--self-test | --verify-results --xml PATH --min-executed N]"
        )
    validate_workflow(DEFAULT_WORKFLOW.read_text(encoding="utf-8"))
    validate_test_runner(DEFAULT_TEST_RUNNER.read_text(encoding="utf-8"))
    print(
        "PASS: required Python job writes, verifies, and uploads one exact JUnit "
        "artifact with a positive execution floor"
    )


try:
    main()
except (GuardFailure, OSError) as error:
    sys.stderr.write(f"FAIL: {error}\n")
    raise SystemExit(1)
