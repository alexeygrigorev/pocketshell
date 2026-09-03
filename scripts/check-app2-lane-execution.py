#!/usr/bin/python3 -I
"""Prove an app2.yml lane actually EXECUTED tests (rewrite task M-2).

The G3 rule ("ban '0 tests completed' as a pass") applied to the four narrow
per-module lanes in ``.github/workflows/app2.yml``.  Gradle prints test counts
only on failure, so a green ``BUILD SUCCESSFUL`` alone proves nothing; and the
new lanes have two live ways to be vacuously green:

* ``FROM-CACHE`` / ``UP-TO-DATE``.  Observed for real while wiring this task:
  a plain ``./gradlew :shared:core-hostapi:test`` came back ``FROM-CACHE`` in
  1s having executed zero tests.  The workflow therefore passes
  ``--rerun-tasks --no-build-cache``, and this guard's ``--newer-than`` marker
  is the mechanical proof those flags did their job — stale XML left behind by
  a skipped task is older than the marker and fails.

* ``assumeTrue``-skipping.  ``RealHostConnectionIntegrationTest`` skips its
  whole class when Docker is unreachable, so a runner with a broken Docker
  daemon would produce a green suite of 8 SKIPPED tests.  ``executed = tests -
  skipped`` (never ``tests``) is what this guard counts, so that run fails
  instead of certifying nothing.

The repo's existing ``scripts/check-executed-test-counts.sh`` is not reusable
here: it is a repo-WIDE guard that derives an expectation for every module in
``settings.gradle.kts`` and demands both Debug and Release variants, which is
correct for the whole-graph Unit lane and wrong for a single-module job.

USAGE
    check-app2-lane-execution.py --results-dir DIR [--results-dir DIR ...]
                                --label NAME [--min N] [--newer-than FILE]
    check-app2-lane-execution.py --self-test

Exit 0 on a proven-real run; exit 1 (with the reason) otherwise.
"""

from __future__ import annotations

import argparse
import os
import sys
import tempfile
import xml.etree.ElementTree as ET
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class Counts:
    tests: int = 0
    skipped: int = 0
    failures: int = 0
    errors: int = 0
    files: int = 0
    stale: int = 0

    @property
    def executed(self) -> int:
        return self.tests - self.skipped

    def plus(self, other: "Counts") -> "Counts":
        return Counts(
            self.tests + other.tests,
            self.skipped + other.skipped,
            self.failures + other.failures,
            self.errors + other.errors,
            self.files + other.files,
            self.stale + other.stale,
        )


def _int(node: ET.Element, name: str) -> int:
    try:
        return int(node.get(name, "0") or 0)
    except ValueError:
        return 0


def collect(results_dirs: list[Path], newer_than: float | None) -> Counts:
    total = Counts()
    for d in results_dirs:
        if not d.is_dir():
            continue
        for xml in sorted(d.glob("TEST-*.xml")):
            if newer_than is not None and xml.stat().st_mtime < newer_than:
                total = total.plus(Counts(stale=1))
                continue
            try:
                root = ET.parse(xml).getroot()
            except ET.ParseError:
                total = total.plus(Counts(errors=1, files=1))
                continue
            suites = [root] if root.tag == "testsuite" else list(root.iter("testsuite"))
            for s in suites:
                total = total.plus(
                    Counts(
                        tests=_int(s, "tests"),
                        skipped=_int(s, "skipped"),
                        failures=_int(s, "failures"),
                        errors=_int(s, "errors"),
                    )
                )
            total = total.plus(Counts(files=1))
    return total


def verify(label: str, results_dirs: list[Path], minimum: int, newer_than: float | None) -> int:
    c = collect(results_dirs, newer_than)
    where = ", ".join(str(d) for d in results_dirs)
    problems: list[str] = []

    if c.files == 0:
        problems.append(
            f"no fresh TEST-*.xml under [{where}] "
            f"({c.stale} stale file(s) ignored) — the test task did not run, "
            "was UP-TO-DATE/FROM-CACHE, or the job died before it"
        )
    if c.executed < minimum:
        problems.append(
            f"executed {c.executed} test(s) (tests={c.tests} skipped={c.skipped}), "
            f"below the required minimum of {minimum} — a green run that executed "
            "nothing is not evidence (G3)"
        )
    if c.failures or c.errors:
        problems.append(f"result XML reports failures={c.failures} errors={c.errors}")

    summary = (
        f"{label}: files={c.files} tests={c.tests} skipped={c.skipped} "
        f"executed={c.executed} failures={c.failures} errors={c.errors}"
    )
    print(summary)
    step_summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if step_summary:
        with open(step_summary, "a", encoding="utf-8") as fh:
            fh.write(f"- `{summary}`\n")

    if problems:
        for p in problems:
            print(f"FAIL {label}: {p}", file=sys.stderr)
        return 1
    return 0


SUITE_XML = (
    '<testsuite name="{name}" tests="{tests}" skipped="{skipped}" '
    'failures="{failures}" errors="{errors}"/>'
)


def _write_suite(d: Path, name: str, **kw: int) -> Path:
    d.mkdir(parents=True, exist_ok=True)
    p = d / f"TEST-{name}.xml"
    payload = {"name": name, "tests": 0, "skipped": 0, "failures": 0, "errors": 0}
    payload.update(kw)
    p.write_text(SUITE_XML.format(**payload), encoding="utf-8")
    return p


def self_test() -> int:
    checks = 0
    failed: list[str] = []

    def expect(case: str, want: int, **kw) -> None:
        nonlocal checks
        checks += 1
        got = verify(**kw)
        if got != want:
            failed.append(f"{case}: expected rc={want}, got rc={got}")

    with tempfile.TemporaryDirectory() as tmp:
        root = Path(tmp)

        # 1. A genuine run passes.
        good = root / "good"
        _write_suite(good, "GoodTest", tests=11, skipped=0)
        expect("genuine run", 0, label="good", results_dirs=[good], minimum=1, newer_than=None)

        # 2. A task that never ran (no XML at all) fails.
        empty = root / "empty"
        empty.mkdir()
        expect("no results", 1, label="empty", results_dirs=[empty], minimum=1, newer_than=None)

        # 3. A missing results directory fails.
        expect(
            "missing dir",
            1,
            label="missing",
            results_dirs=[root / "nope"],
            minimum=1,
            newer_than=None,
        )

        # 4. An all-skipped suite (the Docker-unavailable assumeTrue shape)
        #    fails even though tests>0 and the build was green.
        skipped = root / "skipped"
        _write_suite(skipped, "SkippedTest", tests=8, skipped=8)
        expect(
            "all skipped",
            1,
            label="skipped",
            results_dirs=[skipped],
            minimum=1,
            newer_than=None,
        )

        # 5. A run below its declared floor fails.
        few = root / "few"
        _write_suite(few, "FewTest", tests=3, skipped=0)
        expect("below floor", 1, label="few", results_dirs=[few], minimum=10, newer_than=None)

        # 6. Stale XML (older than the run marker) is not credited.
        stale = root / "stale"
        p = _write_suite(stale, "StaleTest", tests=40, skipped=0)
        os.utime(p, (1_000_000, 1_000_000))
        expect(
            "stale results",
            1,
            label="stale",
            results_dirs=[stale],
            minimum=1,
            newer_than=2_000_000.0,
        )

        # 7. ... but the same file, fresh relative to the marker, passes.
        expect(
            "fresh results",
            0,
            label="stale",
            results_dirs=[stale],
            minimum=1,
            newer_than=500_000.0,
        )

        # 8. Failures inside the XML are reported even if the count is fine.
        red = root / "red"
        _write_suite(red, "RedTest", tests=5, failures=1)
        expect("failures in xml", 1, label="red", results_dirs=[red], minimum=1, newer_than=None)

        # 9. Multiple directories are summed (the :shared:core-transport:test
        #    case, whose `test` alias runs both Debug and Release variants).
        a = root / "multi" / "testDebugUnitTest"
        b = root / "multi" / "testReleaseUnitTest"
        _write_suite(a, "A", tests=19)
        _write_suite(b, "B", tests=19)
        expect("summed dirs", 0, label="multi", results_dirs=[a, b], minimum=38, newer_than=None)

        # 10. ... and a floor above the sum still fails, so the sum is real.
        expect(
            "summed dirs below floor",
            1,
            label="multi",
            results_dirs=[a, b],
            minimum=39,
            newer_than=None,
        )

    if failed:
        for f in failed:
            print(f"SELF-TEST FAIL: {f}", file=sys.stderr)
        return 1
    if checks != 10:
        print(f"SELF-TEST FAIL: expected 10 checks, ran {checks}", file=sys.stderr)
        return 1
    print(f"check-app2-lane-execution self-test: {checks} checks PASSED")
    return 0


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument("--results-dir", action="append", default=[])
    ap.add_argument("--label", default="lane")
    ap.add_argument("--min", type=int, default=1)
    ap.add_argument("--newer-than", default=None)
    ap.add_argument("--self-test", action="store_true")
    args = ap.parse_args(argv)

    if args.self_test:
        return self_test()

    if not args.results_dir:
        print("FAIL: --results-dir is required", file=sys.stderr)
        return 2

    newer_than = None
    if args.newer_than:
        marker = Path(args.newer_than)
        if not marker.exists():
            print(f"FAIL: run marker {marker} does not exist", file=sys.stderr)
            return 1
        newer_than = marker.stat().st_mtime

    return verify(args.label, [Path(d) for d in args.results_dir], args.min, newer_than)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
