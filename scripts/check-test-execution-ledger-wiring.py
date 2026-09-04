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
JOURNEY_YML = ROOT / ".github" / "workflows" / "app2.yml"
RELEASE_YML = ROOT / ".github" / "workflows" / "release-emulator-validation.yml"
SELECTION_GUARDS = ROOT / "scripts" / "ci-test-selection-guards.sh"
RECORD_WRAPPER = "scripts/ci-record-test-execution-ledger.sh"
LEDGER_SCRIPT_REL = "scripts/check-test-execution-ledger.sh"
LEDGER_SCRIPT = "scripts/check-test-execution-ledger.sh"
LEDGER_PATH = "build/test-execution-ledger.tsv"
PIN_COLD = "com.pocketshell.next.connect.J01ConnectAndTrustJourney"
PIN_WORKFLOW = "com.pocketshell.next.terminal.J03AttachAndTypeJourney"
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


def validate_journey_ledger(job: str) -> None:
    """The CONNECTED lane records into the rolling ledger.

    This used to validate a dedicated shard-merging wrapper
    (scripts/ci-nightly-execution-ledger.sh) for the six-shard nightly. app2's
    lane is ONE unfiltered run (issue #2474), so the wrapper collapsed into two
    inline calls in the journey job and `--merge-attendance` no longer applies —
    there are no shards to merge. What still has to hold is that the lane
    RECORDS and is held to the wholesale selected set, with the load-bearing
    journeys pinned by name.
    """
    require(job, LEDGER_SCRIPT_REL, "journey job must invoke the execution ledger")
    # The flag AND its argument: a bare "--record" substring is satisfied by
    # "--record-not", so the mutation that removes recording would still pass.
    require(job, "--record app2/", "journey job must --record its connected JUnit XML")
    require(job, "--attendance", "journey job must run current-run attendance")
    require(job, "--selected-from app2-journey", "journey attendance must use the wholesale selected set")
    require(job, "--require-class", "journey attendance must --require-class the pins")
    require(job, PIN_COLD, "journey attendance must pin the connect/trust journey")
    require(job, PIN_WORKFLOW, "journey attendance must pin the attach/type journey")


def validate_journey_yml(text: str) -> None:
    """app2.yml's journey job is the CONNECTED lane's ledger recorder.

    The nightly equivalent needed a separate `execution-ledger` aggregator job,
    because six parallel shards cannot each save the rolling cache without
    clobbering it. app2's lane is a single unfiltered run (issue #2474), so
    there is nothing to aggregate and the recording lives in the journey job
    itself — which is why this no longer looks for an aggregator, a
    `--aggregate` flag or `download-artifact`.
    """
    job = extract_job(text, "app2-journey")
    if re.search(r"^    continue-on-error:\s*true\s*$", job, re.M):
        raise GuardFailure(
            "app2-journey must not be continue-on-error — a truncated run would "
            "otherwise look like a passing attendance verdict"
        )
    validate_journey_ledger(job)
    # The recording step must survive a RED suite, or the lane only ever records
    # its own good news.
    # Bound the slice to THIS step. Running it to the end of the job means every
    # later `if: always()` (docker logs, artifact upload) vouches for the ledger
    # step, so deleting always() from the ledger step alone stayed green.
    step_at = job.find("Record journey execution")
    if step_at < 0:
        raise GuardFailure("app2-journey has no 'Record journey execution' step")
    next_step = job.find("\n      - name:", step_at)
    ledger_step = job[step_at : next_step if next_step > 0 else len(job)]
    if "if: always()" not in ledger_step:
        raise GuardFailure(
            "the journey ledger step must run with if: always() — a lane that "
            "records only on success cannot show which classes a red run reached"
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

    # THE JOURNEY LANE IS ONE MODULE'S androidTest SET, AND THAT IS CHECKED AS
    # CODE, NOT AS A PATH LITERAL.
    #
    # This used to `require(text, "app/src/androidTest", ...)`. That is a
    # substring search over the whole file, so a COMMENT mentioning the path
    # satisfied it — and after the rewrite repointed the lane at app2 that is
    # exactly what happened: the guard stayed green because a block comment
    # still contained the words, while the code it was meant to pin had moved.
    # A prose-satisfiable assertion is the G6 shape (docs/ci-pitfalls.md), so
    # both halves of the real property are asserted against code lines instead:
    # the lane derives its root from the suite that runs it, and it refuses to
    # do so if that suite ever grows a class filter.
    code = "\n".join(
        line for line in text.splitlines() if not line.lstrip().startswith("#")
    )
    require(
        code,
        "journey_lane_android_test_dir",
        "journey-lane selected set must derive its androidTest root from the suite that runs it",
    )
    require(
        code,
        "src/androidTest",
        "journey-lane selected set must be restricted to an androidTest root",
    )
    require(
        code,
        "testInstrumentationRunnerArguments",
        "journey-lane derivation must reject a suite that filters which classes run",
    )


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
    validate_journey_yml((root / ".github/workflows/app2.yml").read_text())
    validate_release_yml((root / ".github/workflows/release-emulator-validation.yml").read_text())
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
    journey = JOURNEY_YML.read_text()
    release = RELEASE_YML.read_text()
    guards = SELECTION_GUARDS.read_text()
    unit_wrapper = (ROOT / RECORD_WRAPPER).read_text()
    ledger_script = (ROOT / LEDGER_SCRIPT).read_text()

    def write_tree(tmp: Path, **files: str) -> None:
        mapping = {
            ".github/workflows/tests.yml": tests,
            ".github/workflows/app2.yml": journey,
            ".github/workflows/release-emulator-validation.yml": release,
            "scripts/ci-test-selection-guards.sh": guards,
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

    def expect_green(label: str, **files: str) -> None:
        """A change that must NOT redden the guard.

        Every assertion here is a substring search, and a substring search over a
        whole file happily matches PROSE. That is not hypothetical: the
        `app/src/androidTest` requirement went on passing after the lane moved to
        app2 purely because a block comment still contained the words. So the
        comment-only edit below is now a first-class case — the guard must be
        indifferent to it, which is only true if the assertion reads code.
        """
        with tempfile.TemporaryDirectory() as tmp_name:
            tmp = Path(tmp_name)
            write_tree(tmp, **files)
            try:
                validate_tree(tmp)
            except GuardFailure as exc:
                raise GuardFailure(
                    f"self-test rejected a harmless change: {label} ({exc})"
                ) from exc

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
        "journey job stops recording into the ledger",
        **{
            ".github/workflows/app2.yml": journey.replace(
                "--record app2/build/outputs", "--record-not app2/build/outputs"
            )
        },
    )
    expect_red(
        "journey attendance drops the wholesale selected set",
        **{
            ".github/workflows/app2.yml": journey.replace(
                "--selected-from app2-journey", "--selected-from unit"
            )
        },
    )
    expect_red(
        "journey ledger step only records on success (a red run shows nothing)",
        **{
            ".github/workflows/app2.yml": journey.replace(
                "      - name: Record journey execution into the rolling ledger (#2082)\n"
                "        if: always() && steps.journey.conclusion != 'skipped'",
                "      - name: Record journey execution into the rolling ledger (#2082)\n"
                "        if: steps.journey.conclusion == 'success'",
            )
        },
    )
    expect_red(
        "missing connect/trust journey pin",
        **{".github/workflows/app2.yml": journey.replace(PIN_COLD, "com.example.NotThePin")},
    )
    expect_red(
        "missing attach/type journey pin",
        **{".github/workflows/app2.yml": journey.replace(PIN_WORKFLOW, "com.example.NotThePin")},
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
    # Three mutations, because validate_ledger_script now pins three separate
    # code properties instead of one greppable path literal. The middle one is
    # the regression that motivated the change: replacing the path ONLY inside a
    # comment must NOT be enough to redden the guard, and replacing it in code
    # must be — the old single assertion could not tell those apart.
    expect_red(
        "ledger script drops the journey-lane root derivation",
        **{LEDGER_SCRIPT: ledger_script.replace("journey_lane_android_test_dir", "some_other_helper")},
    )
    expect_red(
        "ledger script drops the androidTest root restriction from CODE",
        **{
            LEDGER_SCRIPT: "\n".join(
                line
                if line.lstrip().startswith("#")
                else line.replace("src/androidTest", "src/NOT_ANDROID_TEST")
                for line in ledger_script.splitlines()
            )
        },
    )
    expect_red(
        "ledger script stops rejecting a class-filtered journey suite",
        **{
            LEDGER_SCRIPT: ledger_script.replace(
                "testInstrumentationRunnerArguments", "someOtherRunnerArgument"
            )
        },
    )
    expect_green(
        "rewording a COMMENT that mentions an androidTest path",
        **{
            LEDGER_SCRIPT: "\n".join(
                line.replace("src/androidTest", "src/SOME_PROSE_PATH")
                if line.lstrip().startswith("#")
                else line
                for line in ledger_script.splitlines()
            )
        },
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

    expected = 20
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
    print("PASS: unit, journey, and release workflows wire --record/--verify/attendance to real JUnit results")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
