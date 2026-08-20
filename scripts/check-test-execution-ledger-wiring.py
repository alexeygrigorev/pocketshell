#!/usr/bin/python3 -I
"""Fail closed when CI does not wire the execution ledger to real results.

Issue #2082: `check-test-execution-ledger.sh` existed and was self-tested, but
no workflow invoked real `--record` / `--verify`, persisted a rolling ledger,
or distinguished selected vs executed vs asserted. A guard that is never
called is decoration.

This script reads the committed workflow YAML, the unit/nightly wrappers,
and the ledger script, and requires the load-bearing invocations. The
self-test mutates copies of those files and demands a RED for each
mutation — including "the wrapper is an unread exit 0", "the selected set
is unscoped unit", and "nightly-phase1 is no longer app/src/androidTest".
A green that would still pass if record/verify never compared the real
selector to the real artifact is not a pass.

Usage:
  scripts/check-test-execution-ledger-wiring.py              # check the real tree
  scripts/check-test-execution-ledger-wiring.py --self-test  # red->green mutations
"""

from __future__ import annotations

import re
import sys
import tempfile
from pathlib import Path


ROOT = Path(__file__).resolve().parent.parent
TESTS_YML = ROOT / ".github" / "workflows" / "tests.yml"
NIGHTLY_YML = ROOT / ".github" / "workflows" / "nightly-extensive.yml"
RELEASE_YML = ROOT / ".github" / "workflows" / "release-emulator-validation.yml"
NIGHTLY_SUITE = ROOT / "scripts" / "nightly-extensive-suite.sh"
SELECTION_GUARDS = ROOT / "scripts" / "ci-test-selection-guards.sh"
RECORD_WRAPPER = "scripts/ci-record-test-execution-ledger.sh"
NIGHTLY_WRAPPER = "scripts/ci-nightly-execution-ledger.sh"
LEDGER_SCRIPT = "scripts/check-test-execution-ledger.sh"
LEDGER_PATH = "build/test-execution-ledger.tsv"
PIN_COLD = "com.pocketshell.app.proof.ColdInstallE2eTest"
PIN_WORKFLOW = "com.pocketshell.app.proof.EmulatorWorkflowE2eTest"
JOB_KEY = re.compile(r"^  ([A-Za-z0-9_-]+):[ \t]*(#.*)?$")
CACHE_KEY_PREFIX = "test-execution-ledger-"


class GuardFailure(ValueError):
    """The workflow no longer wires record/verify to real JUnit results."""


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


def require(haystack: str, needle: str, where: str) -> None:
    if needle not in haystack:
        raise GuardFailure(f"{where} must contain {needle!r}")


def require_once(haystack: str, needle: str, where: str) -> None:
    count = haystack.count(needle)
    if count != 1:
        raise GuardFailure(f"{where} must contain {needle!r} exactly once, found {count}")


def validate_tests_yml(text: str) -> None:
    job = extract_job(text, "unit")
    require(job, "Restore test-execution ledger", "tests.yml unit job")
    require(job, "uses: actions/cache@v5", "tests.yml unit ledger cache step")
    require(job, f"path: {LEDGER_PATH}", "tests.yml unit ledger cache path")
    require(job, f"key: {CACHE_KEY_PREFIX}", "tests.yml unit ledger cache key")
    require(job, f"restore-keys:", "tests.yml unit ledger restore-keys")
    require(job, RECORD_WRAPPER, "tests.yml unit job")
    require(
        job,
        '--variant "${{ matrix.variant }}"',
        "tests.yml unit ledger step must pass --variant so attendance matches the shard",
    )
    require(
        job,
        "if: always() && steps.unit_tests.conclusion != 'skipped'",
        "tests.yml unit ledger step must run even when Gradle is red",
    )
    # Record must happen after the test run, not instead of the count guard.
    record_at = job.find(RECORD_WRAPPER)
    tests_at = job.find("Run JVM unit tests")
    if tests_at < 0 or record_at < tests_at:
        raise GuardFailure("tests.yml must record the ledger AFTER the JVM unit tests run")


def validate_nightly_yml(text: str) -> None:
    if "execution-ledger:" not in text:
        raise GuardFailure(
            "nightly-extensive.yml must have an execution-ledger aggregator job "
            "(parallel shards cannot save the rolling cache without clobbering)"
        )
    agg = extract_job(text, "execution-ledger")
    if re.search(r"^    continue-on-error:\s*true\s*$", agg, re.M):
        raise GuardFailure(
            "execution-ledger must not be continue-on-error — a truncated shard "
            "would otherwise look like a passing attendance verdict"
        )
    require(agg, NIGHTLY_WRAPPER, "nightly execution-ledger job")
    require(agg, "--aggregate", "nightly execution-ledger job")
    require(agg, "actions/download-artifact@", "nightly execution-ledger must download shard artifacts")
    require(agg, f"path: {LEDGER_PATH}", "nightly execution-ledger cache path")
    require(agg, "uses: actions/cache@v5", "nightly execution-ledger must persist the rolling ledger")
    require(agg, "needs.guard.outputs.should_run == 'true'", "nightly execution-ledger if:")
    if "if: always()" not in agg and "if: ${{ always()" not in agg:
        raise GuardFailure(
            "nightly execution-ledger must run if: always() when the suite ran, "
            "so a crashed shard still produces a missing-artifact RED"
        )


def validate_nightly_wrapper(text: str) -> None:
    require(text, PIN_COLD, "nightly ledger wrapper must pin ColdInstallE2eTest")
    require(text, PIN_WORKFLOW, "nightly ledger wrapper must pin EmulatorWorkflowE2eTest")
    require(text, "--require-class", "nightly ledger wrapper must --require-class the pins")
    require(text, "--merge-attendance", "nightly ledger wrapper must merge shard attendance")
    require(text, "--selected-from nightly-phase1", "nightly ledger wrapper must reuse wholesale selected set")


def validate_unit_wrapper(text: str) -> None:
    """The unit wrapper body is load-bearing. A YAML string pointing at an
    unread `exit 0` script is the G6 hole: CI looks wired while --record /
    --attendance / --verify never run.
    """
    require(text, 'bash "$GUARD" --record', "unit ledger wrapper must --record this run's JUnit XML")
    require(text, 'bash "$GUARD" --attendance', "unit ledger wrapper must run current-run attendance")
    require(text, 'bash "$GUARD" --verify', "unit ledger wrapper must --verify the rolling ledger")
    require(text, "--selected-from", "unit ledger wrapper must pass --selected-from")
    require(text, "--source-set", "unit ledger wrapper must pass --source-set")
    require(text, 'SELECTED_FROM="unit-debug"', "unit ledger wrapper must select unit-debug on Debug")
    require(text, 'SELECTED_FROM="unit-release"', "unit ledger wrapper must select unit-release on Release")
    require(text, "--variant", "unit ledger wrapper must take --variant Debug|Release")
    # Unscoped `--selected-from unit` includes testRelease on a Debug job.
    if re.search(r"--selected-from[ \t]+unit([ \t\n\"'\\]|$)", text):
        raise GuardFailure(
            "unit ledger wrapper must not pass unscoped --selected-from unit "
            "(Debug cannot emit src/testRelease; use unit-debug / unit-release)"
        )


def validate_ledger_script(text: str) -> None:
    require(text, "unit-debug", "ledger must know the Debug unit selected set")
    require(text, "unit-release", "ledger must know the Release unit selected set")
    # Nightly phase 1 is :app:connectedDebugAndroidTest, not every module.
    require(
        text,
        "app/src/androidTest",
        "nightly-phase1 selected set must be restricted to app/src/androidTest",
    )


def validate_release_yml(text: str) -> None:
    job = extract_job(text, "emulator-release-validation")
    require(job, "uses: actions/cache@v5", "release ledger cache")
    require(job, f"path: {LEDGER_PATH}", "release ledger cache path")
    require(job, "check-test-execution-ledger.sh --record", "release must --record real JUnit results")
    require(job, "check-test-execution-ledger.sh --verify", "release must --verify the rolling ledger")
    record_at = job.find("check-test-execution-ledger.sh --record")
    run_at = job.find("Run emulator-only release validation")
    if run_at < 0 or record_at < run_at:
        raise GuardFailure("release must record the ledger AFTER the emulator validation run")


def validate_nightly_suite(text: str) -> None:
    require(text, "--print-phase1-exclusions", "nightly suite must print phase-1 exclusions")
    require(text, NIGHTLY_WRAPPER, "nightly suite must invoke shard attendance")
    require(text, "--shard", "nightly suite shard attendance")
    # Wholesale premise must remain: attendance selected-from reads notClass,
    # it must not replace connectedDebugAndroidTest minus notClass with an
    # allowlist.
    if ":app:connectedDebugAndroidTest" not in text:
        raise GuardFailure("nightly suite must still run :app:connectedDebugAndroidTest")
    if "RunnerArguments.notClass=" not in text:
        raise GuardFailure("nightly suite must still subtract a notClass list (wholesale premise)")


def validate_selection_guards(text: str) -> None:
    require(
        text,
        "check-test-execution-ledger-wiring.py --self-test",
        "ci-test-selection-guards.sh",
    )
    if text.count("check-test-execution-ledger-wiring.py") < 2:
        raise GuardFailure(
            "ci-test-selection-guards.sh must invoke the wiring check itself "
            "AND --self-test (a self-test nobody runs is decoration)"
        )


def validate_tree(root: Path = ROOT) -> None:
    validate_tests_yml((root / ".github/workflows/tests.yml").read_text())
    validate_nightly_yml((root / ".github/workflows/nightly-extensive.yml").read_text())
    validate_release_yml((root / ".github/workflows/release-emulator-validation.yml").read_text())
    validate_nightly_suite((root / "scripts/nightly-extensive-suite.sh").read_text())
    validate_nightly_wrapper((root / "scripts/ci-nightly-execution-ledger.sh").read_text())
    validate_unit_wrapper((root / "scripts/ci-record-test-execution-ledger.sh").read_text())
    validate_ledger_script((root / "scripts/check-test-execution-ledger.sh").read_text())
    validate_selection_guards((root / "scripts/ci-test-selection-guards.sh").read_text())


def self_test() -> None:
    # Reproduce-first: the committed tree is checked live first. On current
    # main this is RED (no workflow wiring). After this issue's wiring it is
    # GREEN, and the mutations below prove each requirement is load-bearing.
    checks = 0
    try:
        validate_tree()
        live_green = True
    except GuardFailure as exc:
        live_green = False
        live_error = str(exc)

    tests = TESTS_YML.read_text()
    nightly = NIGHTLY_YML.read_text()
    release = RELEASE_YML.read_text()
    suite = NIGHTLY_SUITE.read_text()
    guards = SELECTION_GUARDS.read_text()
    nightly_wrapper = (ROOT / "scripts/ci-nightly-execution-ledger.sh").read_text()
    unit_wrapper = (ROOT / RECORD_WRAPPER).read_text()
    ledger_script = (ROOT / LEDGER_SCRIPT).read_text()

    def write_tree(tmp: Path, **files: str) -> None:
        mapping = {
            ".github/workflows/tests.yml": tests,
            ".github/workflows/nightly-extensive.yml": nightly,
            ".github/workflows/release-emulator-validation.yml": release,
            "scripts/nightly-extensive-suite.sh": suite,
            "scripts/ci-test-selection-guards.sh": guards,
            "scripts/ci-nightly-execution-ledger.sh": nightly_wrapper,
            RECORD_WRAPPER: unit_wrapper,
            LEDGER_SCRIPT: ledger_script,
        }
        mapping.update(files)
        for rel, content in mapping.items():
            path = tmp / rel
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)

    def expect_red(label: str, **files: str) -> None:
        nonlocal checks
        with tempfile.TemporaryDirectory() as tmp_name:
            tmp = Path(tmp_name)
            write_tree(tmp, **files)
            try:
                validate_tree(tmp)
            except GuardFailure:
                checks += 1
            else:
                raise GuardFailure(f"self-test accepted an unsafe mutation: {label}")

    # Mutations that must redden — each names the property it exists to prove.
    expect_red(
        "unit job without --record wrapper",
        **{".github/workflows/tests.yml": tests.replace(RECORD_WRAPPER, "scripts/true")},
    )
    expect_red(
        "unit job without ledger cache path",
        **{".github/workflows/tests.yml": tests.replace(f"path: {LEDGER_PATH}", "path: /tmp/not-the-ledger")},
    )
    expect_red(
        "nightly without aggregator job",
        **{".github/workflows/nightly-extensive.yml": nightly.replace("execution-ledger:", "not-the-ledger:")},
    )
    expect_red(
        "aggregator continue-on-error (absent result looks like a pass)",
        **{
            ".github/workflows/nightly-extensive.yml": re.sub(
                r"(execution-ledger:\n(?:    .*\n)*?)(    steps:)",
                r"\1    continue-on-error: true\n\2",
                nightly,
                count=1,
            )
        },
    )
    expect_red(
        "missing ColdInstall pin",
        **{
            "scripts/ci-nightly-execution-ledger.sh": nightly_wrapper.replace(
                PIN_COLD, "com.example.NotThePin"
            )
        },
    )
    expect_red(
        "missing EmulatorWorkflow pin",
        **{
            "scripts/ci-nightly-execution-ledger.sh": nightly_wrapper.replace(
                PIN_WORKFLOW, "com.example.NotThePin"
            )
        },
    )
    expect_red(
        "release without --verify",
        **{
            ".github/workflows/release-emulator-validation.yml": release.replace(
                "check-test-execution-ledger.sh --verify",
                "true",
            )
        },
    )
    expect_red(
        "release without --record",
        **{
            ".github/workflows/release-emulator-validation.yml": release.replace(
                "check-test-execution-ledger.sh --record",
                "true --record-not",
            )
        },
    )
    expect_red(
        "nightly suite without print-phase1-exclusions (selected set unreadable)",
        **{
            "scripts/nightly-extensive-suite.sh": suite.replace(
                "--print-phase1-exclusions", "--print-nothing"
            )
        },
    )
    expect_red(
        "selection-guards job no longer runs this wiring check",
        **{
            "scripts/ci-test-selection-guards.sh": guards.replace(
                "check-test-execution-ledger-wiring.py --self-test",
                "true",
            )
        },
    )
    expect_red(
        "unit wrapper unread / no-op (YAML still names it)",
        **{RECORD_WRAPPER: "#!/bin/bash\nexit 0\n"},
    )
    expect_red(
        "unit wrapper drops --record",
        **{RECORD_WRAPPER: unit_wrapper.replace('bash "$GUARD" --record', "true --record-not")},
    )
    expect_red(
        "unit wrapper drops --attendance",
        **{RECORD_WRAPPER: unit_wrapper.replace('bash "$GUARD" --attendance', "true --attendance-not")},
    )
    expect_red(
        "unit wrapper drops --verify",
        **{RECORD_WRAPPER: unit_wrapper.replace('bash "$GUARD" --verify', "true --verify-not")},
    )
    expect_red(
        "unit wrapper uses unscoped --selected-from unit",
        **{
            RECORD_WRAPPER: unit_wrapper.replace(
                '--selected-from "$SELECTED_FROM"',
                "--selected-from unit",
            )
        },
    )
    expect_red(
        "unit wrapper drops unit-debug selected set",
        **{RECORD_WRAPPER: unit_wrapper.replace('SELECTED_FROM="unit-debug"', 'SELECTED_FROM="unit"')},
    )
    expect_red(
        "ledger script drops app/src/androidTest nightly-phase1 filter",
        **{LEDGER_SCRIPT: ledger_script.replace("app/src/androidTest", "NOT_APP_ANDROID_TEST")},
    )
    expect_red(
        "tests.yml unit step does not pass --variant",
        **{
            ".github/workflows/tests.yml": tests.replace(
                '--variant "${{ matrix.variant }}"',
                "--not-variant",
            )
        },
    )

    expected = 18
    if checks != expected:
        raise GuardFailure(f"self-test ran {checks} red mutations, expected {expected}")

    if not live_green:
        raise GuardFailure(
            "live tree is RED (this is the reproduce-first proof on an unwired "
            f"main, and a failure after wiring): {live_error}"
        )
    print(f"PASS: test-execution-ledger wiring guard self-test ({checks} red mutations + live tree green)")


def main(argv: list[str]) -> int:
    if argv == ["--self-test"]:
        self_test()
        return 0
    if argv:
        print("usage: check-test-execution-ledger-wiring.py [--self-test]", file=sys.stderr)
        return 2
    try:
        validate_tree()
    except GuardFailure as exc:
        print(f"FAIL: {exc}", file=sys.stderr)
        return 1
    print("PASS: unit, nightly, and release workflows wire --record/--verify/attendance to real JUnit results")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
