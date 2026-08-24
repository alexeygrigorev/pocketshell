#!/usr/bin/python3 -I
"""Run the executable #2320 raw-id/family propagation mutation proof.

The reviewed worktree is never mutated. ``--run`` makes private clean and
mutant source copies under the system temporary directory (``TMPDIR`` when
set), runs the real
``:app:testDebugUnitTest`` production-wiring fixture in each copy, and requires
selective red results:

* removing ``recordedKindId`` during the production row mapping kills only the
  raw-id assertion;
* dropping ``familyForRawId`` from the production parser call kills only the
  family assertion;
* the unrelated identity assertion stays green in both mutants; and
* every report has a positive executed-test count (zero tests is never green).

Each mutant source is restored from the clean snapshot and hash-verified. The
runner is intentionally separate from lexical/source guards: the proof only
passes when the production Kotlin tests execute and produce the expected JUnit
outcomes.

Usage::

    scripts/issue-2320-raw-id-mutation.py --check
    scripts/issue-2320-raw-id-mutation.py --run
    scripts/issue-2320-raw-id-mutation.py --run-fast
    scripts/issue-2320-raw-id-mutation.py --run-red
"""

from __future__ import annotations

import argparse
import hashlib
import os
import re
import shutil
import subprocess
import sys
import tempfile
import time
import xml.etree.ElementTree as ET
from pathlib import Path


SCRIPT = Path(__file__).resolve()
ROOT = SCRIPT.parent.parent
PROD_GATEWAY = Path("app/src/main/java/com/pocketshell/app/projects/FolderListGateway.kt")
PROD_PARSER = Path("app/src/main/java/com/pocketshell/app/sessions/HostTmuxSessionListParser.kt")
FIXTURE = Path(
    "app/src/test/java/com/pocketshell/app/projects/"
    "FolderListRawIdPropagationMutationTest.kt"
)
REQUIRED_COPY_FILES = (FIXTURE,)
RUNNER = Path("scripts/issue-2320-raw-id-mutation.py")
PROOF_CLASS = "com.pocketshell.app.projects.FolderListRawIdPropagationMutationTest"
PROOF_TESTS = (
    "rawIdSurvivesProductionRowMapping",
    "familyResolverIsUsedByProductionRowMapping",
    "unrelatedSessionIdentityStillParses",
)
RAW_MUTANT_TEST = "rawIdSurvivesProductionRowMapping"
FAMILY_MUTANT_TEST = "familyResolverIsUsedByProductionRowMapping"
GRADLE_TASK = ":app:testDebugUnitTest"
RESULT_ROOT = Path("app/build/test-results/testDebugUnitTest")
SKIPPED_TASK_MARKERS = ("UP-TO-DATE", "FROM-CACHE", "NO-SOURCE")
TIMEOUT_SECONDS = 900


class MutationFailure(RuntimeError):
    pass


def fail(message: str) -> None:
    raise MutationFailure(message)


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def source_path(root: Path, relative: Path) -> Path:
    path = (root / relative).resolve()
    root_resolved = root.resolve()
    if path != root_resolved and root_resolved not in path.parents:
        fail(f"refusing source outside root: {relative}")
    if not path.is_file():
        fail(f"missing source: {relative}")
    return path


def read_source(root: Path, relative: Path) -> str:
    return source_path(root, relative).read_text(encoding="utf-8")


def raw_mapping_block(source: str) -> str:
    prefix = "        private fun HostTmuxSessionRow.toFolderSessionRow()"
    start = source.find(prefix)
    if start < 0:
        fail("raw-id mapping function anchor is missing")
    end = source.find("\n\n        /**", start)
    if end < 0:
        fail("raw-id mapping function boundary is missing")
    return source[start:end]


def apply_raw_id_mutation(source: str) -> str:
    block = raw_mapping_block(source)
    anchor = "                recordedKindId = recordedKindId,"
    if block.count(anchor) != 1:
        fail(
            "raw-id mapping mutation anchor must occur exactly once in the "
            f"production mapping block (found {block.count(anchor)})"
        )
    mutated_block = block.replace(anchor, "                recordedKindId = null,", 1)
    return source.replace(block, mutated_block, 1)


def apply_family_mutation(source: str) -> str:
    anchor = ".mapNotNull { line -> parseTmuxListSessionsRow(line, familyForRawId) }"
    mutation = ".mapNotNull { line -> parseTmuxListSessionsRow(line) }"
    if source.count(anchor) != 1:
        fail(
            "family resolver propagation anchor must occur exactly once "
            f"(found {source.count(anchor)})"
        )
    return source.replace(anchor, mutation, 1)


def validate_mutation_liveness(gateway: str, parser: str) -> None:
    """Ensure both requested mutations change the reviewed source exactly."""

    raw_mutant = apply_raw_id_mutation(gateway)
    if raw_mutant == gateway or "recordedKindId = null," not in raw_mutant:
        fail("raw-id mutation is not live against the current gateway source")
    if raw_mapping_block(raw_mutant).count("recordedKindId = recordedKindId,") != 0:
        fail("raw-id mutation left the original mapping assignment in place")

    family_mutant = apply_family_mutation(parser)
    if family_mutant == parser:
        fail("family-resolver mutation is not live against the current parser source")
    if ".mapNotNull { line -> parseTmuxListSessionsRow(line) }" not in family_mutant:
        fail("family-resolver mutation did not remove the resolver argument")


def validate_tree(root: Path) -> dict[str, str]:
    gateway = read_source(root, PROD_GATEWAY)
    parser = read_source(root, PROD_PARSER)
    fixture = read_source(root, FIXTURE)
    runner = read_source(root, RUNNER)
    command_marker = 'const val POCKETSHELL_SESSIONS_TMUX_COMMAND: String ='
    command_line = next(
        (line for line in gateway.splitlines() if command_marker in line),
        "",
    )
    if not command_line:
        fail("the supported tmux fallback command declaration is missing")
    if "--json" in command_line:
        fail("the sessions fallback still contains an unsupported --json probe")
    locale_aware_tmux_command = (
        '"${TmuxRead.CLIENT} list-sessions -F '
        '$POCKETSHELL_SESSIONS_TMUX_FORMAT"'
    )
    if locale_aware_tmux_command not in gateway:
        fail(
            "the fallback must use the locale-aware TmuxRead.CLIENT form "
            "for its supported tmux probe"
        )
    if gateway.count("session.execBounded(pathAware(POCKETSHELL_SESSIONS_TMUX_COMMAND))") != 1:
        fail("the fallback must use exactly one bounded supported tmux probe")
    if "POCKETSHELL_SESSIONS_COMMAND" not in gateway:
        fail("the human pocketshell sessions compatibility baseline is missing")
    block = raw_mapping_block(gateway)
    if block.count("recordedKindId = recordedKindId,") != 1:
        fail("production raw-id mapping anchor is stale")
    family_anchor = ".mapNotNull { line -> parseTmuxListSessionsRow(line, familyForRawId) }"
    if parser.count(family_anchor) != 1:
        fail("production family resolver anchor is stale")
    validate_mutation_liveness(gateway, parser)
    missing = [name for name in PROOF_TESTS if f"fun {name}(" not in fixture]
    if missing:
        fail(f"mutation fixture is missing test(s): {', '.join(missing)}")
    required_freshness_guards = (
        "def prepare_result_root(",
        "shutil.rmtree(result_root)",
        "run_started_ns = time.time_ns()",
        "st_mtime_ns < run_started_ns",
        "st_ctime_ns < run_started_ns",
        "SKIPPED_TASK_MARKERS",
        "def require_gradle_task_provenance(",
        "executed = tests - skipped",
    )
    missing_freshness_guards = [
        fragment for fragment in required_freshness_guards if fragment not in runner
    ]
    if missing_freshness_guards:
        fail(
            "mutation runner is missing stale-report guards: "
            + ", ".join(missing_freshness_guards)
        )
    return {
        str(PROD_GATEWAY): sha256(source_path(root, PROD_GATEWAY)),
        str(PROD_PARSER): sha256(source_path(root, PROD_PARSER)),
        str(FIXTURE): sha256(source_path(root, FIXTURE)),
    }


def copy_tree(source: Path, destination: Path, *, include_build: bool = False) -> None:
    destination.mkdir(parents=True, exist_ok=True)
    if include_build:
        shutil.copytree(
            source,
            destination,
            dirs_exist_ok=True,
            ignore=shutil.ignore_patterns(
                ".git",
                ".gradle",
                ".mutation",
                "issue-2320-proof",
            ),
        )
    elif shutil.which("rsync"):
        command = [
            "rsync",
            "-a",
            "--exclude=.git/",
            "--exclude=.gradle/",
            "--exclude=build/",
            "--exclude=*/build/",
            "--exclude=.mutation/",
            "--exclude=app/src/main/java/com/pocketshell/app/selftest_*/",
            "--exclude=app/src/test/java/com/pocketshell/app/selftest_*/",
            "--exclude=app/src/androidTest/java/com/pocketshell/app/selftest_*/",
            f"{source}/",
            f"{destination}/",
        ]
        completed = subprocess.run(command, check=False, text=True)
        if completed.returncode != 0:
            fail(f"private source copy failed with exit {completed.returncode}")
    else:
        shutil.copytree(
            source,
            destination,
            dirs_exist_ok=True,
            ignore=shutil.ignore_patterns(".git", ".gradle", "build", ".mutation"),
        )

    # The mutation fixture is intentionally untracked until the issue is
    # integrated.  Copy it explicitly so a source-copy policy or future
    # filter cannot silently produce a clean tree with no selected tests.
    for relative in REQUIRED_COPY_FILES:
        source_file = source_path(source, relative)
        destination_file = destination / relative
        destination_file.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source_file, destination_file)


def prepare_result_root(root: Path, label: str) -> tuple[Path, int, str]:
    """Remove prior reports and establish this invocation's provenance."""

    result_root = root / RESULT_ROOT
    if result_root.is_symlink():
        fail(f"refusing symlinked JUnit result directory: {result_root}")
    if result_root.exists():
        if not result_root.is_dir():
            fail(f"JUnit result path is not a directory: {result_root}")
        shutil.rmtree(result_root)
    if result_root.exists():
        fail(f"could not remove prior JUnit reports: {result_root}")
    result_root.mkdir(parents=True)
    run_started_ns = time.time_ns()
    run_id = f"{label}-{run_started_ns}-{os.getpid()}"
    return result_root, run_started_ns, run_id


def require_gradle_task_provenance(log_path: Path, run_id: str, run_started_ns: int) -> None:
    """Require this invocation to have executed the selected Gradle task."""

    output = log_path.read_text(encoding="utf-8")
    lines = output.splitlines()
    if f"run_id={run_id}" not in lines:
        fail(f"Gradle log has no provenance for run {run_id}")
    if f"run_started_ns={run_started_ns}" not in lines:
        fail(f"Gradle log has no start marker for run {run_id}")
    task_pattern = re.compile(rf"^\s*>\s*Task\s+{re.escape(GRADLE_TASK)}(?:\s|$)")
    task_lines = [line for line in lines if task_pattern.search(line)]
    if len(task_lines) == 1:
        task_line = task_lines[0]
    elif (
        len(task_lines) == 2
        and not task_lines[0].rstrip().endswith("FAILED")
        and task_lines[1].rstrip().endswith("FAILED")
    ):
        # Gradle emits the task header once when it starts and once with a
        # FAILED suffix after the test process reports an assertion failure.
        # The second line is terminal status, not a second invocation.
        task_line = task_lines[0]
    else:
        fail(
            f"Gradle log has {len(task_lines)} {GRADLE_TASK} task lines; "
            "cannot establish one current invocation"
        )
    skipped_markers = [
        marker
        for marker in SKIPPED_TASK_MARKERS
        if re.search(rf"\b{re.escape(marker)}\b", task_line)
    ]
    if skipped_markers:
        fail(
            f"{GRADLE_TASK} did not execute in run {run_id}: "
            f"{task_line.strip()} (markers={skipped_markers})"
        )


def junit_report(
    root: Path,
    run_started_ns: int,
    run_id: str,
    log_path: Path,
) -> tuple[int, int, int, int, dict[str, str]]:
    require_gradle_task_provenance(log_path, run_id, run_started_ns)
    result_root = root / RESULT_ROOT
    if result_root.is_symlink() or not result_root.is_dir():
        fail(f"current run has no JUnit result directory: {result_root}")
    xml_files = sorted(result_root.rglob("TEST-*.xml"))
    if not xml_files:
        fail(f"no JUnit XML was produced under {result_root}")
    stale_files: list[str] = []
    for xml_file in xml_files:
        if xml_file.is_symlink():
            fail(f"refusing symlinked JUnit XML: {xml_file}")
        stat = xml_file.stat()
        if stat.st_mtime_ns < run_started_ns or stat.st_ctime_ns < run_started_ns:
            stale_files.append(
                f"{xml_file} (mtime_ns={stat.st_mtime_ns}, ctime_ns={stat.st_ctime_ns})"
            )
    if stale_files:
        fail(
            f"JUnit XML is stale or lacks current-run provenance for {run_id}: "
            + "; ".join(stale_files)
        )
    with log_path.open("a", encoding="utf-8") as log:
        log.write(
            "fresh_junit_files="
            + ",".join(
                f"{path.relative_to(root)}:mtime_ns={path.stat().st_mtime_ns}"
                for path in xml_files
            )
            + "\n"
        )
    tests = failures = errors = skipped = 0
    outcomes: dict[str, str] = {}
    for xml_file in xml_files:
        try:
            document = ET.parse(xml_file).getroot()
        except ET.ParseError as error:
            fail(f"malformed JUnit XML {xml_file}: {error}")
        for case in document.iter("testcase"):
            name = case.attrib.get("name", "")
            tests += 1
            if case.find("failure") is not None:
                failures += 1
                outcomes[name] = "failed"
            elif case.find("error") is not None:
                errors += 1
                outcomes[name] = "error"
            elif case.find("skipped") is not None:
                skipped += 1
                outcomes[name] = "skipped"
            else:
                outcomes[name] = "passed"
    executed = tests - skipped
    if executed <= 0:
        fail(
            f"current run {run_id} produced no executed tests: "
            f"tests={tests} skipped={skipped}"
        )
    return tests, failures, errors, skipped, outcomes


def run_proof(
    root: Path,
    label: str,
    log_path: Path,
    *,
    rerun_tasks: bool = True,
    timeout_seconds: int = TIMEOUT_SECONDS,
) -> tuple[int, tuple[int, int, int, int, dict[str, str]]]:
    _, run_started_ns, run_id = prepare_result_root(root, label)
    command = [
        str(root / "gradlew"),
        GRADLE_TASK,
        "--no-daemon",
        "--no-build-cache",
        "--no-parallel",
        "--max-workers=1",
        "--console=plain",
        "-Dorg.gradle.jvmargs=-Xmx3072m",
        "-Pkotlin.compiler.execution.strategy=in-process",
        "-Pkotlin.daemon.jvmargs=-Xmx3072m",
    ]
    if rerun_tasks:
        command.insert(2, "--rerun-tasks")
    # Select the copied fixture class as a unit.  Method-level filters can be
    # rejected as "no tests found" by the Android Gradle test task before it
    # emits JUnit XML, even when the class is present.  The report checks below
    # still require exactly these three named outcomes.
    command.extend(["--tests", PROOF_CLASS])
    started = time.monotonic()
    with log_path.open("w", encoding="utf-8") as log:
        log.write(f"run_id={run_id}\n")
        log.write(f"run_started_ns={run_started_ns}\n")
        log.write(f"result_root={RESULT_ROOT}\n")
        log.write("command=" + " ".join(command) + "\n")
        log.flush()
        try:
            completed = subprocess.run(
                command,
                cwd=root,
                stdout=log,
                stderr=subprocess.STDOUT,
                check=False,
                timeout=timeout_seconds,
                env=os.environ.copy(),
            )
            returncode = completed.returncode
        except subprocess.TimeoutExpired:
            log.write(f"\nTIMEOUT after {timeout_seconds}s\n")
            returncode = 124
        log.write(f"\nelapsed_seconds={time.monotonic() - started:.3f}\n")
    report = junit_report(root, run_started_ns, run_id, log_path)
    return returncode, report


def require_clean_report(report: tuple[int, int, int, int, dict[str, str]], returncode: int) -> None:
    tests, failures, errors, skipped, outcomes = report
    if returncode != 0 or failures or errors or skipped:
        fail(
            "clean mutation fixture did not pass: "
            f"rc={returncode} tests={tests} failures={failures} "
            f"errors={errors} skipped={skipped} outcomes={outcomes}"
        )
    if tests != len(PROOF_TESTS):
        fail(
            "clean mutation fixture executed an unexpected number of tests: "
            f"expected={len(PROOF_TESTS)} actual={tests}"
        )
    if any(outcomes.get(name) != "passed" for name in PROOF_TESTS):
        fail(f"clean mutation fixture did not pass every named test: {outcomes}")


def require_selective_mutant(
    label: str,
    report: tuple[int, int, int, int, dict[str, str]],
    returncode: int,
    red_test: str,
) -> None:
    tests, failures, errors, skipped, outcomes = report
    if returncode == 0:
        fail(f"{label} mutant stayed green")
    if tests != len(PROOF_TESTS):
        fail(
            f"{label} mutant executed an unexpected number of tests: "
            f"expected={len(PROOF_TESTS)} actual={tests}"
        )
    if skipped or errors:
        fail(f"{label} mutant has non-selective skipped/error outcomes: {outcomes}")
    if outcomes.get(red_test) != "failed":
        fail(f"{label} mutant did not kill {red_test}: {outcomes}")
    for test_name in PROOF_TESTS:
        if test_name == red_test:
            continue
        if outcomes.get(test_name) != "passed":
            fail(f"{label} mutant changed unrelated proof {test_name}: {outcomes}")
    if failures != 1:
        fail(f"{label} mutant was not selective: failures={failures}, outcomes={outcomes}")


def write_manifest(path: Path, root_hashes: dict[str, str], command_note: str) -> None:
    path.write_text(
        "issue=2320\n"
        f"root={ROOT}\n"
        f"command={command_note}\n"
        + "".join(f"clean_{relative}_sha256={digest}\n" for relative, digest in root_hashes.items()),
        encoding="utf-8",
    )


def copy_junit_reports(root: Path, destination: Path) -> None:
    """Preserve the just-finished run's fresh XML beside its log."""

    result_root = root / RESULT_ROOT
    if not result_root.is_dir() or result_root.is_symlink():
        fail(f"cannot preserve missing JUnit reports: {result_root}")
    if destination.exists():
        fail(f"JUnit evidence destination is not fresh: {destination}")
    for xml_file in result_root.rglob("TEST-*.xml"):
        if xml_file.is_symlink():
            fail(f"refusing symlinked JUnit XML: {xml_file}")
        target = destination / xml_file.relative_to(result_root)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(xml_file, target)
    if not any(destination.rglob("TEST-*.xml")):
        fail(f"no JUnit XML preserved from {result_root}")


def run_fast_mutations(artifacts: Path) -> None:
    """Run bounded mutants while reusing this worktree's valid Gradle outputs.

    A private source copy has a different project path and no task history, so
    copying ``build/`` alone still recompiles the Android graph.  This mode
    instead takes byte-for-byte backups of the two production files, applies
    one mutation at a time in the reviewed worktree, runs the real class-level
    fixture against the existing build, and restores both files in ``finally``.
    The baseline and every mutant still require fresh JUnit XML and an
    executed ``:app:testDebugUnitTest`` task.
    """

    root_hashes = validate_tree(ROOT)
    if artifacts.exists():
        if any(artifacts.iterdir()):
            fail(f"evidence directory is not fresh: {artifacts}")
    else:
        artifacts.mkdir(parents=True)

    gateway_path = source_path(ROOT, PROD_GATEWAY)
    parser_path = source_path(ROOT, PROD_PARSER)
    gateway_backup = gateway_path.read_bytes()
    parser_backup = parser_path.read_bytes()
    mutation_timeout = 300

    def restore(path: Path, content: bytes, relative: Path) -> None:
        path.write_bytes(content)
        if sha256(path) != root_hashes[str(relative)]:
            fail(f"worktree source did not restore to the clean hash: {relative}")

    try:
        write_manifest(
            artifacts / "mutation-manifest.txt",
            root_hashes,
            f"./gradlew {GRADLE_TASK} --tests {PROOF_CLASS} (reuse current worktree build outputs)",
        )

        baseline_rc, baseline_report = run_proof(
            ROOT,
            "baseline-worktree",
            artifacts / "baseline.log",
            rerun_tasks=False,
            timeout_seconds=mutation_timeout,
        )
        require_clean_report(baseline_report, baseline_rc)
        copy_junit_reports(ROOT, artifacts / "baseline-junit")
        (artifacts / "baseline-report.txt").write_text(
            f"returncode={baseline_rc}\nreport={baseline_report}\n",
            encoding="utf-8",
        )

        gateway_text = gateway_backup.decode("utf-8")
        gateway_path.write_text(apply_raw_id_mutation(gateway_text), encoding="utf-8")
        try:
            raw_rc, raw_report = run_proof(
                ROOT,
                "raw-id-worktree",
                artifacts / "raw-id-mutant.log",
                rerun_tasks=False,
                timeout_seconds=mutation_timeout,
            )
            copy_junit_reports(ROOT, artifacts / "raw-id-junit")
            require_selective_mutant("raw-id-worktree", raw_report, raw_rc, RAW_MUTANT_TEST)
            (artifacts / "raw-id-report.txt").write_text(
                f"returncode={raw_rc}\nreport={raw_report}\n",
                encoding="utf-8",
            )
        finally:
            restore(gateway_path, gateway_backup, PROD_GATEWAY)

        parser_text = parser_backup.decode("utf-8")
        parser_path.write_text(apply_family_mutation(parser_text), encoding="utf-8")
        try:
            family_rc, family_report = run_proof(
                ROOT,
                "family-resolver-worktree",
                artifacts / "family-resolver-mutant.log",
                rerun_tasks=False,
                timeout_seconds=mutation_timeout,
            )
            copy_junit_reports(ROOT, artifacts / "family-resolver-junit")
            require_selective_mutant(
                "family-resolver-worktree",
                family_report,
                family_rc,
                FAMILY_MUTANT_TEST,
            )
            (artifacts / "family-resolver-report.txt").write_text(
                f"returncode={family_rc}\nreport={family_report}\n",
                encoding="utf-8",
            )
        finally:
            restore(parser_path, parser_backup, PROD_PARSER)

        if validate_tree(ROOT) != root_hashes:
            fail("reviewed worktree changed during fast mutation run")
        (artifacts / "summary.txt").write_text(
            "result=PASS\n"
            "baseline=PASS\n"
            "raw_id_mutant=RED_SELECTIVE\n"
            "family_resolver_mutant=RED_SELECTIVE\n"
            "root_source_restored=true\n",
            encoding="utf-8",
        )
        files = sorted(path for path in artifacts.rglob("*") if path.is_file())
        (artifacts / "SHA256SUMS").write_text(
            "".join(
                f"{sha256(path)}  {path.relative_to(artifacts).as_posix()}\n"
                for path in files
                if path.name != "SHA256SUMS"
            ),
            encoding="utf-8",
        )
        print(f"PASS: #2320 fast executable mutation proof; evidence={artifacts}")
    finally:
        try:
            restore(gateway_path, gateway_backup, PROD_GATEWAY)
            restore(parser_path, parser_backup, PROD_PARSER)
            if validate_tree(ROOT) != root_hashes:
                print(
                    "FAIL: reviewed worktree source changed during fast mutation lane",
                    file=sys.stderr,
                )
        finally:
            pass


def run_raw_red_proof(artifacts: Path) -> None:
    """Run only the raw-id mutant after a fresh worktree baseline.

    This bounded fallback keeps the baseline cheap and spends the full cold
    compile allowance on the one prerequisite mutant whose red result is
    required for #2320.  The clean copy explicitly receives the untracked
    fixture, and the result is accepted only with fresh XML for all three
    tests and exactly one selective failure.
    """

    root_hashes = validate_tree(ROOT)
    if artifacts.exists():
        if any(artifacts.iterdir()):
            fail(f"evidence directory is not fresh: {artifacts}")
    else:
        artifacts.mkdir(parents=True)

    sandbox = Path(
        tempfile.mkdtemp(
            prefix="pocketshell-issue2320-red-",
            dir=os.getenv("TMPDIR", "/tmp"),
        )
    )
    raw_root = sandbox / "raw-mutant"
    try:
        write_manifest(
            artifacts / "mutation-manifest.txt",
            root_hashes,
            f"./gradlew {GRADLE_TASK} --tests {PROOF_CLASS} (bounded raw-id mutant)",
        )

        baseline_rc, baseline_report = run_proof(
            ROOT,
            "baseline-red-reference",
            artifacts / "baseline.log",
            rerun_tasks=False,
            timeout_seconds=300,
        )
        require_clean_report(baseline_report, baseline_rc)
        copy_junit_reports(ROOT, artifacts / "baseline-junit")
        (artifacts / "baseline-report.txt").write_text(
            f"returncode={baseline_rc}\nreport={baseline_report}\n",
            encoding="utf-8",
        )

        copy_tree(ROOT, raw_root)
        if validate_tree(raw_root) != root_hashes:
            fail("clean raw-id copy does not contain the reviewed proof sources")
        raw_source = source_path(raw_root, PROD_GATEWAY)
        raw_source.write_text(
            apply_raw_id_mutation(raw_source.read_text(encoding="utf-8")),
            encoding="utf-8",
        )
        raw_rc, raw_report = run_proof(
            raw_root,
            "raw-id-red",
            artifacts / "raw-id-mutant.log",
            rerun_tasks=True,
            timeout_seconds=TIMEOUT_SECONDS,
        )
        copy_junit_reports(raw_root, artifacts / "raw-id-junit")
        require_selective_mutant("raw-id-red", raw_report, raw_rc, RAW_MUTANT_TEST)
        (artifacts / "raw-id-report.txt").write_text(
            f"returncode={raw_rc}\nreport={raw_report}\n",
            encoding="utf-8",
        )

        if validate_tree(ROOT) != root_hashes:
            fail("reviewed worktree changed during bounded raw-id proof")
        (artifacts / "summary.txt").write_text(
            "result=RED_SELECTIVE\n"
            "baseline=PASS_3_OF_3\n"
            "raw_id_mutant=RED_SELECTIVE_1_OF_3\n"
            "unrelated_tests=2_OF_2_PASS\n"
            "root_source_unchanged=true\n",
            encoding="utf-8",
        )
        files = sorted(path for path in artifacts.rglob("*") if path.is_file())
        (artifacts / "SHA256SUMS").write_text(
            "".join(
                f"{sha256(path)}  {path.relative_to(artifacts).as_posix()}\n"
                for path in files
                if path.name != "SHA256SUMS"
            ),
            encoding="utf-8",
        )
        print(f"PASS: #2320 bounded raw-id red proof; evidence={artifacts}")
    finally:
        if validate_tree(ROOT) != root_hashes:
            print(
                "FAIL: reviewed worktree source changed during bounded raw-id proof",
                file=sys.stderr,
            )
        shutil.rmtree(sandbox, ignore_errors=True)


def run_family_red_proof(artifacts: Path) -> None:
    """Run the independent family-resolver mutant with durable restoration."""

    root_hashes = validate_tree(ROOT)
    if artifacts.exists():
        if any(artifacts.iterdir()):
            fail(f"evidence directory is not fresh: {artifacts}")
    else:
        artifacts.mkdir(parents=True)

    sandbox = Path(
        tempfile.mkdtemp(
            prefix="pocketshell-issue2320-family-",
            dir=os.getenv("TMPDIR", "/tmp"),
        )
    )
    family_root = sandbox / "family-mutant"
    family_source: Path | None = None
    family_backup: bytes | None = None
    try:
        write_manifest(
            artifacts / "mutation-manifest.txt",
            root_hashes,
            f"./gradlew {GRADLE_TASK} --tests {PROOF_CLASS} (bounded family-resolver mutant)",
        )

        baseline_rc, baseline_report = run_proof(
            ROOT,
            "baseline-family-reference",
            artifacts / "baseline.log",
            rerun_tasks=False,
            timeout_seconds=300,
        )
        require_clean_report(baseline_report, baseline_rc)
        copy_junit_reports(ROOT, artifacts / "baseline-junit")
        (artifacts / "baseline-report.txt").write_text(
            f"returncode={baseline_rc}\nreport={baseline_report}\n",
            encoding="utf-8",
        )

        copy_tree(ROOT, family_root)
        if validate_tree(family_root) != root_hashes:
            fail("clean family-resolver copy does not contain the reviewed proof sources")
        family_source = source_path(family_root, PROD_PARSER)
        family_backup = family_source.read_bytes()
        family_source.write_text(
            apply_family_mutation(family_source.read_text(encoding="utf-8")),
            encoding="utf-8",
        )
        try:
            family_rc, family_report = run_proof(
                family_root,
                "family-resolver-red",
                artifacts / "family-resolver-mutant.log",
                rerun_tasks=True,
                timeout_seconds=TIMEOUT_SECONDS,
            )
            copy_junit_reports(family_root, artifacts / "family-resolver-junit")
            require_selective_mutant(
                "family-resolver-red",
                family_report,
                family_rc,
                FAMILY_MUTANT_TEST,
            )
        finally:
            if family_backup is None:
                fail("family-resolver backup was not captured")
            family_source.write_bytes(family_backup)
            if sha256(family_source) != root_hashes[str(PROD_PARSER)]:
                fail("family-resolver mutant source did not restore to the clean hash")

        if validate_tree(family_root) != root_hashes:
            fail("restored family-resolver copy does not match the reviewed proof sources")
        (artifacts / "family-resolver-report.txt").write_text(
            f"returncode={family_rc}\nreport={family_report}\n"
            f"restored_sha256={sha256(family_source)}\n",
            encoding="utf-8",
        )
        if validate_tree(ROOT) != root_hashes:
            fail("reviewed worktree changed during bounded family-resolver proof")
        (artifacts / "summary.txt").write_text(
            "result=RED_SELECTIVE\n"
            "baseline=PASS_3_OF_3\n"
            "family_resolver_mutant=RED_SELECTIVE_1_OF_3\n"
            "unrelated_tests=2_OF_2_PASS\n"
            "private_source_restored=true\n"
            "root_source_unchanged=true\n",
            encoding="utf-8",
        )
        files = sorted(path for path in artifacts.rglob("*") if path.is_file())
        (artifacts / "SHA256SUMS").write_text(
            "".join(
                f"{sha256(path)}  {path.relative_to(artifacts).as_posix()}\n"
                for path in files
                if path.name != "SHA256SUMS"
            ),
            encoding="utf-8",
        )
        print(f"PASS: #2320 bounded family-resolver red proof; evidence={artifacts}")
    finally:
        if family_source is not None and family_backup is not None:
            family_source.write_bytes(family_backup)
            if sha256(family_source) != root_hashes[str(PROD_PARSER)]:
                print(
                    "FAIL: family-resolver source did not restore during cleanup",
                    file=sys.stderr,
                )
        if validate_tree(ROOT) != root_hashes:
            print(
                "FAIL: reviewed worktree source changed during bounded family-resolver proof",
                file=sys.stderr,
            )
        shutil.rmtree(sandbox, ignore_errors=True)


def run_mutations(artifacts: Path) -> None:
    root_hashes = validate_tree(ROOT)
    if artifacts.exists():
        if any(artifacts.iterdir()):
            fail(f"evidence directory is not fresh: {artifacts}")
    else:
        artifacts.mkdir(parents=True)
    sandbox = Path(tempfile.mkdtemp(prefix="pocketshell-issue2320-", dir=os.getenv("TMPDIR", "/tmp")))
    clean_root = sandbox / "clean"
    raw_root = sandbox / "raw-mutant"
    family_root = sandbox / "family-mutant"
    try:
        copy_tree(ROOT, clean_root)
        copy_tree(clean_root, raw_root)
        copy_tree(clean_root, family_root)
        copied_hashes = validate_tree(clean_root)
        if copied_hashes != root_hashes:
            fail(
                "private clean copy does not contain the same proof sources: "
                f"expected={root_hashes} actual={copied_hashes}"
            )
        write_manifest(
            artifacts / "mutation-manifest.txt",
            root_hashes,
            f"./gradlew {GRADLE_TASK} --tests {PROOF_CLASS}",
        )

        baseline_rc, baseline_report = run_proof(clean_root, "baseline", artifacts / "baseline.log")
        require_clean_report(baseline_report, baseline_rc)
        (artifacts / "baseline-report.txt").write_text(
            f"returncode={baseline_rc}\nreport={baseline_report}\n",
            encoding="utf-8",
        )

        raw_source = source_path(raw_root, PROD_GATEWAY)
        raw_text = raw_source.read_text(encoding="utf-8")
        raw_source.write_text(apply_raw_id_mutation(raw_text), encoding="utf-8")
        raw_rc, raw_report = run_proof(raw_root, "raw-id", artifacts / "raw-id-mutant.log")
        require_selective_mutant("raw-id", raw_report, raw_rc, RAW_MUTANT_TEST)
        shutil.copyfile(source_path(clean_root, PROD_GATEWAY), raw_source)
        if sha256(raw_source) != sha256(source_path(clean_root, PROD_GATEWAY)):
            fail("raw-id mutant source did not restore to the clean hash")
        (artifacts / "raw-id-report.txt").write_text(
            f"returncode={raw_rc}\nreport={raw_report}\nrestored_sha256={sha256(raw_source)}\n",
            encoding="utf-8",
        )

        family_source = source_path(family_root, PROD_PARSER)
        family_text = family_source.read_text(encoding="utf-8")
        family_source.write_text(apply_family_mutation(family_text), encoding="utf-8")
        family_rc, family_report = run_proof(
            family_root,
            "family-resolver",
            artifacts / "family-resolver-mutant.log",
        )
        require_selective_mutant(
            "family-resolver",
            family_report,
            family_rc,
            FAMILY_MUTANT_TEST,
        )
        shutil.copyfile(source_path(clean_root, PROD_PARSER), family_source)
        if sha256(family_source) != sha256(source_path(clean_root, PROD_PARSER)):
            fail("family-resolver mutant source did not restore to the clean hash")
        (artifacts / "family-resolver-report.txt").write_text(
            f"returncode={family_rc}\nreport={family_report}\nrestored_sha256={sha256(family_source)}\n",
            encoding="utf-8",
        )

        current_hashes = validate_tree(ROOT)
        if current_hashes != root_hashes:
            fail(f"reviewed worktree changed during mutation run: {current_hashes}")
        (artifacts / "summary.txt").write_text(
            "result=PASS\n"
            "baseline=PASS\n"
            "raw_id_mutant=RED_SELECTIVE\n"
            "family_resolver_mutant=RED_SELECTIVE\n"
            "root_source_restored=true\n",
            encoding="utf-8",
        )
        files = sorted(path for path in artifacts.rglob("*") if path.is_file())
        (artifacts / "SHA256SUMS").write_text(
            "".join(
                f"{sha256(path)}  {path.relative_to(artifacts).as_posix()}\n"
                for path in files
                if path.name != "SHA256SUMS"
            ),
            encoding="utf-8",
        )
        print(f"PASS: #2320 executable mutation proof; evidence={artifacts}")
    finally:
        try:
            if validate_tree(ROOT) != root_hashes:
                print("FAIL: reviewed worktree source changed during mutation lane", file=sys.stderr)
        finally:
            shutil.rmtree(sandbox, ignore_errors=True)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    modes = parser.add_mutually_exclusive_group(required=True)
    modes.add_argument("--check", action="store_true", help="validate anchors and fixture wiring")
    modes.add_argument("--run", action="store_true", help="run private-copy Gradle mutations")
    modes.add_argument(
        "--run-fast",
        action="store_true",
        help="run bounded private mutants from existing build outputs",
    )
    modes.add_argument(
        "--run-red",
        action="store_true",
        help="run a bounded raw-id mutant with a fresh 3-test baseline",
    )
    modes.add_argument(
        "--run-family",
        action="store_true",
        help="run a bounded family-resolver mutant with a fresh 3-test baseline",
    )
    parser.add_argument("--artifacts", type=Path, help="fresh evidence directory for --run")
    args = parser.parse_args(argv)
    try:
        hashes = validate_tree(ROOT)
        if args.check:
            print("PASS: #2320 mutation anchors and executable fixture are wired")
            for relative, digest in hashes.items():
                print(f"{relative} sha256={digest}")
            return 0
        artifacts = args.artifacts or Path(
            tempfile.mkdtemp(prefix="pocketshell-issue2320-evidence-", dir=os.getenv("TMPDIR", "/tmp"))
        )
        if args.run_fast:
            run_fast_mutations(artifacts)
        elif args.run_red:
            run_raw_red_proof(artifacts)
        elif args.run_family:
            run_family_red_proof(artifacts)
        else:
            run_mutations(artifacts)
        return 0
    except (MutationFailure, OSError, subprocess.SubprocessError) as error:
        print(f"FAIL: {error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
