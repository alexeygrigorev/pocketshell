#!/usr/bin/env python3
"""Run the selective persistence mutation proof for issue #2294.

The proof runs the real LastSessionStoreTest tests against the current source,
then against one production-source mutant at a time. Each mutant removes one
load-bearing exact-generation input or relaxes the generation predicate. The
mutant's focused test must fail, an untouched-field control must remain green,
and the restored source must pass again.

The source is mutated only in a private temporary copy of this worktree. The
run directory is durable so the logs, XML reports, source hashes, and mutant
snapshots can be cited in a review comment after the temporary copy is gone.
Use ``--contract`` for a cheap no-Gradle validation of the mutation anchors.
"""

from __future__ import annotations

import argparse
from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import xml.etree.ElementTree as ET


ROOT = Path(__file__).resolve().parents[1]
SOURCE_REL = Path("app/src/main/java/com/pocketshell/app/session/LastSessionStore.kt")
TEST_SOURCE_REL = Path("app/src/test/java/com/pocketshell/app/session/LastSessionStoreTest.kt")
SOURCE = ROOT / SOURCE_REL
TEST_SOURCE = ROOT / TEST_SOURCE_REL
TEST_CLASS = "com.pocketshell.app.session.LastSessionStoreTest"
MIN_FREE_BYTES = 10 * 1024 * 1024 * 1024
RUN_TIMEOUT_SECONDS = 900


@dataclass(frozen=True)
class Mutation:
    name: str
    anchor: str
    replacement: str
    target_method: str
    control_method: str
    target_marker: str


MUTATIONS = (
    Mutation(
        name="missing-tmux-session-id",
        anchor=(
            "            tmuxSessionId = "
            "prefs.safeString(KEY_TMUX_SESSION_ID, null)?.trim()?.ifBlank { null },"
        ),
        replacement="            tmuxSessionId = null,",
        target_method="persistedTmuxSessionIdIsRequiredForExactIdentity",
        control_method="persistedSessionCreatedIsRequiredForExactIdentity",
        target_marker=(
            "persisted tmux session id is part of the exact restore identity"
        ),
    ),
    Mutation(
        name="missing-session-created",
        anchor=(
            "            sessionCreated = "
            "prefs.safeLong(KEY_SESSION_CREATED, 0L)?.takeIf { it > 0L },"
        ),
        replacement="            sessionCreated = null,",
        target_method="persistedSessionCreatedIsRequiredForExactIdentity",
        control_method="persistedTmuxSessionIdIsRequiredForExactIdentity",
        target_marker=(
            "persisted session creation is part of the exact restore identity"
        ),
    ),
    Mutation(
        name="generation-accepts-partial-identity",
        anchor=(
            "            get() = "
            "tmuxSessionGenerationOrNull(tmuxSessionId, sessionCreated)"
        ),
        replacement=(
            "            get() = TmuxSessionGeneration("
            'tmuxSessionId ?: "issue2294-mutant", sessionCreated ?: 0L)'
        ),
        target_method="generationPredicateRejectsEachMissingIdentityField",
        control_method="persistedTmuxSessionIdIsRequiredForExactIdentity",
        target_marker=(
            "a missing tmux session id must not produce a restore generation"
        ),
    ),
)


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def sha256_file(path: Path) -> str:
    return sha256_bytes(path.read_bytes())


def validate_contract() -> None:
    source = SOURCE.read_text(encoding="utf-8")
    tests = TEST_SOURCE.read_text(encoding="utf-8")
    if not SOURCE.is_file() or not TEST_SOURCE.is_file():
        raise RuntimeError("issue #2294 source or test file is missing")
    if source.count("tmuxSessionId = null,") != 0:
        raise RuntimeError("the clean source already contains the tmux-id mutant")
    for mutation in MUTATIONS:
        if source.count(mutation.anchor) != 1:
            raise RuntimeError(
                f"{mutation.name}: expected one live production anchor, "
                f"found {source.count(mutation.anchor)}"
            )
        if mutation.replacement in source:
            raise RuntimeError(f"{mutation.name}: mutant is already in production")
        if tests.count(f"fun {mutation.target_method}(") != 1:
            raise RuntimeError(
                f"{mutation.name}: target test {mutation.target_method} is not unique"
            )
        if tests.count(mutation.target_marker) != 1:
            raise RuntimeError(
                f"{mutation.name}: target test lacks its selective assertion marker"
            )
        if tests.count(f"fun {mutation.control_method}(") != 1:
            raise RuntimeError(
                f"{mutation.name}: control test {mutation.control_method} is not unique"
            )
    print(
        "contract=PASS "
        f"mutations={len(MUTATIONS)} "
        f"production={SOURCE_REL} "
        f"tests={TEST_SOURCE_REL}"
    )


def copy_source_tree(destination: Path) -> None:
    ignored = shutil.ignore_patterns(
        ".git",
        ".gradle",
        ".worktrees",
        "build",
        "*/build",
        "artifacts",
    )
    shutil.copytree(ROOT, destination, ignore=ignored)


def xml_cases(result_dir: Path) -> list[ET.Element]:
    cases: list[ET.Element] = []
    for xml_path in sorted(result_dir.glob("TEST-*.xml")):
        try:
            root = ET.parse(xml_path).getroot()
        except ET.ParseError:
            continue
        cases.extend(root.iter("testcase"))
    return cases


def failure_text(case: ET.Element) -> str:
    return "\n".join(
        (child.text or "")
        for child in case
        if child.tag in {"failure", "error"}
    )


def has_skip(case: ET.Element) -> bool:
    return any(child.tag == "skipped" for child in case)


def run_focused_test(
    source_tree: Path,
    artifact_dir: Path,
    label: str,
    method: str,
    source_sha: str,
) -> tuple[int, list[ET.Element], str]:
    artifact_dir.mkdir(parents=True, exist_ok=True)
    result_dir = source_tree / "app/build/test-results/testDebugUnitTest"
    if result_dir.exists():
        shutil.rmtree(result_dir)
    log_path = artifact_dir / f"{label}.log"
    xml_path = artifact_dir / f"{label}.xml"
    command = [
        "./gradlew",
        ":app:testDebugUnitTest",
        "--tests",
        f"{TEST_CLASS}.{method}",
        "--no-daemon",
        "--no-parallel",
        "--max-workers=1",
        "--rerun-tasks",
        "--no-build-cache",
        "--console=plain",
        "--stacktrace",
        "-Dorg.gradle.jvmargs=-Xmx2048m",
        "-Pkotlin.daemon.jvmargs=-Xmx4096m",
    ]
    with log_path.open("w", encoding="utf-8") as log:
        log.write(
            f"source_sha256={source_sha}\n"
            f"test_filter={TEST_CLASS}.{method}\n"
            "$ " + " ".join(command) + "\n\n"
        )
        log.flush()
        process = subprocess.Popen(
            command,
            cwd=source_tree,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            exit_code = process.wait(timeout=RUN_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            process.kill()
            exit_code = 124
            log.write(f"\nTIMEOUT after {RUN_TIMEOUT_SECONDS}s\n")

    cases = xml_cases(result_dir) if result_dir.is_dir() else []
    matching = [case for case in cases if case.get("name") == method]
    xml_sources = sorted(result_dir.glob("TEST-*.xml")) if result_dir.is_dir() else []
    if xml_sources:
        xml_path.write_text(
            "\n".join(path.read_text(encoding="utf-8") for path in xml_sources),
            encoding="utf-8",
        )
    (artifact_dir / f"{label}.exit").write_text(
        f"{exit_code}\n", encoding="utf-8"
    )
    (artifact_dir / f"{label}.provenance").write_text(
        f"source_sha256={source_sha}\n"
        f"test_filter={TEST_CLASS}.{method}\n"
        f"exit_code={exit_code}\n"
        f"matching_testcases={len(matching)}\n"
        f"xml_artifact={'present' if xml_sources else 'absent'}\n",
        encoding="utf-8",
    )
    return exit_code, matching, log_path.read_text(encoding="utf-8", errors="replace")


def require_green(
    result: tuple[int, list[ET.Element], str], label: str
) -> None:
    exit_code, cases, _ = result
    if exit_code != 0:
        raise RuntimeError(f"{label}: expected GREEN, got exit {exit_code}")
    if len(cases) != 1:
        raise RuntimeError(
            f"{label}: expected exactly one executed testcase, got {len(cases)}"
        )
    if any(has_skip(case) for case in cases):
        raise RuntimeError(f"{label}: XML contains a skipped testcase")
    if any(failure_text(case) for case in cases):
        raise RuntimeError(f"{label}: XML contains a failure/error")


def require_selective_red(
    result: tuple[int, list[ET.Element], str], mutation: Mutation, label: str
) -> None:
    exit_code, cases, log = result
    if exit_code == 0:
        raise RuntimeError(f"{label}: {mutation.name} unexpectedly stayed GREEN")
    if len(cases) != 1:
        raise RuntimeError(
            f"{label}: expected exactly one executed testcase, got {len(cases)}; "
            "a compile/setup failure is not mutation evidence"
        )
    if has_skip(cases[0]):
        raise RuntimeError(f"{label}: mutation testcase was skipped")
    details = failure_text(cases[0])
    if not details:
        raise RuntimeError(f"{label}: mutant produced no failure/error testcase")
    if mutation.target_marker not in details and mutation.target_marker not in log:
        raise RuntimeError(
            f"{label}: mutant failed without reaching selective assertion "
            f"{mutation.target_marker!r}"
        )


def write_summary(
    run_root: Path,
    original_sha: str,
    restored_sha: str,
    case_results: list[str],
) -> None:
    (run_root / "summary.txt").write_text(
        "# Issue #2294 LastSessionStore selective mutation proof\n"
        f"production_source={SOURCE_REL}\n"
        f"test_source={TEST_SOURCE_REL}\n"
        f"original_source_sha256={original_sha}\n"
        f"restored_source_sha256={restored_sha}\n"
        "source_mutated_only_in_private_copy=true\n"
        "\n".join(case_results)
        + "\n",
        encoding="utf-8",
    )
    files = sorted(path for path in run_root.rglob("*") if path.is_file())
    (run_root / "SHA256SUMS").write_text(
        "".join(
            f"{sha256_file(path)}  {path.relative_to(run_root)}\n"
            for path in files
            if path.name != "SHA256SUMS"
        ),
        encoding="utf-8",
    )


def run_proof(run_root: Path) -> None:
    validate_contract()
    if not SOURCE.is_file() or not TEST_SOURCE.is_file():
        raise RuntimeError("issue #2294 source or test file is missing")
    free_bytes = shutil.disk_usage(ROOT).free
    if free_bytes < MIN_FREE_BYTES:
        raise RuntimeError(
            "blocked before Gradle: filesystem containing the worktree has "
            f"{free_bytes} free bytes; need at least {MIN_FREE_BYTES} "
            "for reproducible build evidence"
        )
    run_root.parent.mkdir(parents=True, exist_ok=True)
    run_root.mkdir(exist_ok=False)
    original_source = SOURCE.read_bytes()
    original_sha = sha256_bytes(original_source)
    mutant_root = Path(
        os.environ.get("TMPDIR", "/tmp")
    ) / f"pocketshell-issue-2294-mutant-{os.getpid()}"
    if mutant_root.exists():
        raise RuntimeError(f"refusing an existing mutant copy: {mutant_root}")
    case_results: list[str] = []
    try:
        copy_source_tree(mutant_root)
        mutant_source = mutant_root / SOURCE_REL
        for mutation in MUTATIONS:
            case_dir = run_root / mutation.name
            clean = mutant_source.read_text(encoding="utf-8")
            if clean != original_source.decode("utf-8"):
                raise RuntimeError(
                    f"{mutation.name}: source was not clean at case start"
                )
            target_base = run_focused_test(
                mutant_root,
                case_dir,
                "target-baseline-green",
                mutation.target_method,
                original_sha,
            )
            require_green(target_base, f"{mutation.name}/target-baseline-green")
            control_base = run_focused_test(
                mutant_root,
                case_dir,
                "control-baseline-green",
                mutation.control_method,
                original_sha,
            )
            require_green(control_base, f"{mutation.name}/control-baseline-green")

            if clean.count(mutation.anchor) != 1:
                raise RuntimeError(f"{mutation.name}: mutation anchor is not unique")
            mutated = clean.replace(mutation.anchor, mutation.replacement, 1)
            mutant_source.write_text(mutated, encoding="utf-8")
            mutant_sha = sha256_file(mutant_source)
            (case_dir / "mutant-source.kt").write_text(mutated, encoding="utf-8")
            if mutant_sha == original_sha:
                raise RuntimeError(f"{mutation.name}: mutation did not change source")

            target_red = run_focused_test(
                mutant_root,
                case_dir,
                "target-mutant-red",
                mutation.target_method,
                mutant_sha,
            )
            require_selective_red(
                target_red, mutation, f"{mutation.name}/target-mutant-red"
            )
            control_mutant = run_focused_test(
                mutant_root,
                case_dir,
                "control-mutant-green",
                mutation.control_method,
                mutant_sha,
            )
            require_green(control_mutant, f"{mutation.name}/control-mutant-green")

            mutant_source.write_bytes(original_source)
            restored_case_sha = sha256_file(mutant_source)
            if restored_case_sha != original_sha:
                raise RuntimeError(f"{mutation.name}: source restoration hash mismatch")
            restored = run_focused_test(
                mutant_root,
                case_dir,
                "restored-green",
                mutation.target_method,
                restored_case_sha,
            )
            require_green(restored, f"{mutation.name}/restored-green")
            case_results.append(
                f"{mutation.name}=baseline_green,mutant_red_selective,"
                "untouched_control_green,restored_green"
            )

        restored_sha = sha256_file(mutant_source)
        write_summary(run_root, original_sha, restored_sha, case_results)
        print(f"mutation-proof=PASS evidence={run_root}")
    finally:
        if mutant_root.exists():
            shutil.rmtree(mutant_root)
        if sha256_file(SOURCE) != original_sha:
            raise RuntimeError("the worktree production source changed during proof")


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "--contract",
        action="store_true",
        help="validate production/test mutation anchors without running Gradle",
    )
    parser.add_argument(
        "--run-root",
        type=Path,
        help="durable evidence directory (default: ~/.cache/pocketshell/evidence/...)",
    )
    args = parser.parse_args(argv)
    try:
        if args.contract:
            validate_contract()
            return 0
        cache_root = Path(
            os.environ.get("XDG_CACHE_HOME", str(Path.home() / ".cache"))
        )
        run_root = args.run_root or (
            cache_root
            / "pocketshell/evidence/issue-2294-last-session-mutation"
            / time.strftime("%Y%m%dT%H%M%SZ")
        )
        run_proof(run_root)
        return 0
    except Exception as error:  # noqa: BLE001 - preserve an actionable verdict
        print(f"mutation-proof=BLOCKED_OR_FAILED reason={error}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
