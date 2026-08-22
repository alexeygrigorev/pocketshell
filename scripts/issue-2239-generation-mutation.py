#!/usr/bin/env python3
"""Preserve selective red/green proofs for issue #2239.

The mutations replace both load-bearing exact-generation kill decisions with
name lookups. Each focused production-path test must then fail at its own
selective assertion. The source is restored and both tests must pass again
before this runner reports success.
"""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import signal
import shutil
import subprocess
import sys


ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "app/src/main/java/com/pocketshell/app/projects/FolderListViewModel.kt"
TEST_SOURCE = ROOT / (
    "app/src/test/java/com/pocketshell/app/projects/"
    "FolderListViewModelKillSessionTest.kt"
)
TEST_FILTER = (
    "com.pocketshell.app.projects.FolderListViewModelKillSessionTest."
    "delayedPredecessorKillDoesNotDeleteSameNameSuccessorThroughViewModel"
)
ACTION_TEST_FILTER = (
    "com.pocketshell.app.projects.FolderListViewModelKillSessionTest."
    "treeKillCarriesGenerationCapturedBeforeSameNameRecreationCanReconcile"
)
SOURCE_ANCHOR = "        if (tree.removeSession(killed.generation)) {"
NAME_KEYED_MUTANT = (
    "        if (tree.generationForSession(killed.lastKnownName)?."
    "let(tree::removeSession) == true) {"
)
SELECTIVE_ASSERTION = (
    "same-name successor must survive delayed predecessor kill through the "
    "production ViewModel"
)
ACTION_SOURCE_ANCHOR = "                    val generation = requestedGeneration"
ACTION_NAME_KEYED_MUTANT = "                    val generation = tree.generationForSession(target)"
ACTION_SELECTIVE_ASSERTION = (
    "tree Stop must retain the generation selected before the async gateway result"
)
DEFAULT_ARTIFACT_DIR = ROOT / "artifacts/issue-2239-generation-mutation"
TEST_RESULT_XML = ROOT / (
    "app/build/test-results/testDebugUnitTest/"
    "TEST-com.pocketshell.app.projects.FolderListViewModelKillSessionTest.xml"
)
RUN_TIMEOUT_SECONDS = 900


def sha256_bytes(payload: bytes) -> str:
    return hashlib.sha256(payload).hexdigest()


def run_focused_test(
    artifact_dir: Path,
    label: str,
    test_filter: str = TEST_FILTER,
    *,
    final_source_sha: str,
    executed_source_sha: str,
) -> int:
    log_path = artifact_dir / f"{label}.log"
    xml_artifact_path = artifact_dir / f"{label}.xml"
    provenance_path = artifact_dir / f"{label}.provenance"
    command = [
        "./gradlew",
        ":app:testDebugUnitTest",
        "--tests",
        test_filter,
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
    # Gradle leaves the previous XML in place when a run fails before the test
    # task writes results. Remove only this known generated report so a red
    # mutation can never inherit XML from an earlier, unrelated source.
    TEST_RESULT_XML.unlink(missing_ok=True)
    xml_artifact_path.unlink(missing_ok=True)
    with log_path.open("w", encoding="utf-8") as log:
        log.write(
            "# final source SHA-256 (all baseline/restored reports)\n"
            f"{final_source_sha}\n"
            "# executed source SHA-256 for this lane\n"
            f"{executed_source_sha}\n"
            f"# test filter\n{test_filter}\n\n"
            "$ " + " ".join(command) + "\n\n"
        )
        log.flush()
        process = subprocess.Popen(
            command,
            cwd=ROOT,
            stdout=log,
            stderr=subprocess.STDOUT,
            start_new_session=True,
        )
        try:
            return_code = process.wait(timeout=RUN_TIMEOUT_SECONDS)
        except subprocess.TimeoutExpired:
            os.killpg(process.pid, signal.SIGTERM)
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                os.killpg(process.pid, signal.SIGKILL)
                process.wait()
            log.write(f"\nTIMEOUT after {RUN_TIMEOUT_SECONDS}s\n")
            return_code = 124
    xml_copied = TEST_RESULT_XML.exists()
    if xml_copied:
        shutil.copy2(TEST_RESULT_XML, xml_artifact_path)
    (artifact_dir / f"{label}.exit").write_text(
        f"{return_code}\n",
        encoding="utf-8",
    )
    provenance_path.write_text(
        "final_source_sha256=" + final_source_sha + "\n"
        "executed_source_sha256=" + executed_source_sha + "\n"
        "test_filter=" + test_filter + "\n"
        f"exit_code={return_code}\n"
        f"xml_artifact={xml_artifact_path.name if xml_copied else 'absent'}\n",
        encoding="utf-8",
    )
    return return_code


def validate_sites(source_text: str, test_text: str) -> None:
    if source_text.count(SOURCE_ANCHOR) != 1:
        raise RuntimeError("expected exactly one live production source anchor")
    if NAME_KEYED_MUTANT in source_text:
        raise RuntimeError("name-keyed mutant is already present in production")
    if test_text.count(TEST_FILTER.rsplit(".", 1)[1]) != 1:
        raise RuntimeError("expected exactly one focused regression test")
    if test_text.count(SELECTIVE_ASSERTION) != 1:
        raise RuntimeError("focused test lacks its selective successor assertion")
    if source_text.count(ACTION_SOURCE_ANCHOR) != 1:
        raise RuntimeError("expected exactly one captured-generation source anchor")
    if test_text.count(ACTION_TEST_FILTER.rsplit(".", 1)[1]) != 1:
        raise RuntimeError("expected exactly one async kill regression test")
    if test_text.count(ACTION_SELECTIVE_ASSERTION) != 1:
        raise RuntimeError("async kill test lacks its selective generation assertion")


def write_summary(
    artifact_dir: Path,
    *,
    before_sha: str,
    mutant_sha: str | None,
    restored_sha: str | None,
    baseline_exit: int | None,
    mutant_exit: int | None,
    restored_exit: int | None,
    action_baseline_exit: int | None,
    action_mutant_exit: int | None,
    action_restored_exit: int | None,
    action_mutant_sha: str | None,
    run_source_hashes: dict[str, str],
    final_source_sha: str,
    error: str | None,
) -> None:
    lines = [
        "# Issue #2239 generation-fence mutation proof",
        "",
        "This artifact is produced by the preserved source mutation runner.",
        "The mutations are applied to both load-bearing FolderListViewModel kill paths.",
        "",
        f"- production source: `{SOURCE.relative_to(ROOT)}`",
        f"- focused test: `{TEST_FILTER}`",
        f"- final current source SHA-256 (baseline/restored lanes): `{final_source_sha}`",
        f"- source SHA-256 before mutation: `{before_sha}`",
        f"- name-keyed mutant SHA-256: `{mutant_sha}`",
        f"- source SHA-256 after restore: `{restored_sha}`",
        "- source SHA-256 per Gradle lane (the `.provenance` sidecar is paired with each log/XML report):",
        *[
            f"  - `{label}`: `{source_sha}`"
            for label, source_sha in run_source_hashes.items()
        ],
        f"- baseline generation-fenced exit: `{baseline_exit}`",
        f"- name-keyed mutant exit: `{mutant_exit}`",
        f"- restored generation-fenced exit: `{restored_exit}`",
        f"- selective assertion: `{SELECTIVE_ASSERTION}`",
        "- selective red evidence: `name-keyed-mutant-red.xml` and `name-keyed-mutant-red.log`",
        f"- async-kill focused test: `{ACTION_TEST_FILTER}`",
        f"- async-kill baseline exit: `{action_baseline_exit}`",
        f"- async-kill name-keyed mutant SHA-256: `{action_mutant_sha}`",
        f"- async-kill name-keyed mutant exit: `{action_mutant_exit}`",
        f"- async-kill restored exit: `{action_restored_exit}`",
        f"- async-kill selective assertion: `{ACTION_SELECTIVE_ASSERTION}`",
        "- async-kill selective red evidence: `async-name-keyed-mutant-red.xml` and `async-name-keyed-mutant-red.log`",
        "- every lane also has a `.provenance` sidecar containing the final current source SHA-256 and the executed source SHA-256",
        "",
        "## Ordered verdict",
        "",
        "1. `BASELINE GREEN`: the exact-generation implementation passes.",
        "2. `NAME_KEYED_MUTANT RED`: the tree removal mutant must fail the successor-preservation assertion.",
        "3. `ASYNC_NAME_KEYED_MUTANT RED`: the post-async lookup mutant must fail the captured-generation assertion.",
        "4. `RESTORED GENERATION-FENCED GREEN`: the original source is restored byte-for-byte and both tests pass again.",
        "",
        "## Mutation",
        "",
        f"```text\n{SOURCE_ANCHOR}\n=>\n{NAME_KEYED_MUTANT}\n```",
        "",
        "## Async kill mutation",
        "",
        f"```text\n{ACTION_SOURCE_ANCHOR}\n=>\n{ACTION_NAME_KEYED_MUTANT}\n```",
    ]
    if error is not None:
        lines.extend(["", "## Runner error", "", f"`{error}`"])
    (artifact_dir / "summary.md").write_text("\n".join(lines) + "\n", encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--artifact-dir",
        type=Path,
        default=DEFAULT_ARTIFACT_DIR,
        help="new directory for logs and the mutation verdict",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    artifact_dir: Path = args.artifact_dir.expanduser().resolve()
    if artifact_dir.exists():
        print(f"artifact directory already exists: {artifact_dir}", file=sys.stderr)
        return 2
    artifact_dir.mkdir(parents=True)

    original_source = SOURCE.read_bytes()
    before_sha = sha256_bytes(original_source)
    mutant_sha: str | None = None
    restored_sha: str | None = None
    baseline_exit: int | None = None
    mutant_exit: int | None = None
    restored_exit: int | None = None
    action_baseline_exit: int | None = None
    action_mutant_exit: int | None = None
    action_restored_exit: int | None = None
    action_mutant_sha: str | None = None
    run_source_hashes: dict[str, str] = {}
    error: str | None = None

    try:
        validate_sites(
            original_source.decode("utf-8"),
            TEST_SOURCE.read_text(encoding="utf-8"),
        )

        if sha256_bytes(SOURCE.read_bytes()) != before_sha:
            raise RuntimeError("production source changed before baseline run")
        run_source_hashes["baseline-green"] = before_sha
        baseline_exit = run_focused_test(
            artifact_dir,
            "baseline-green",
            final_source_sha=before_sha,
            executed_source_sha=before_sha,
        )
        if baseline_exit != 0:
            raise RuntimeError(f"baseline focused test exited {baseline_exit}")

        mutant_source = original_source.decode("utf-8").replace(
            SOURCE_ANCHOR,
            NAME_KEYED_MUTANT,
            1,
        ).encode("utf-8")
        SOURCE.write_bytes(mutant_source)
        mutant_sha = sha256_bytes(mutant_source)
        run_source_hashes["name-keyed-mutant-red"] = mutant_sha
        (artifact_dir / "mutant-source.kt").write_bytes(mutant_source)
        mutant_exit = run_focused_test(
            artifact_dir,
            "name-keyed-mutant-red",
            final_source_sha=before_sha,
            executed_source_sha=mutant_sha,
        )
        mutant_log = (artifact_dir / "name-keyed-mutant-red.log").read_text(
            encoding="utf-8",
            errors="replace",
        )
        mutant_xml = ""
        mutant_xml_path = artifact_dir / "name-keyed-mutant-red.xml"
        if mutant_xml_path.exists():
            mutant_xml = mutant_xml_path.read_text(encoding="utf-8", errors="replace")
        if mutant_exit == 0:
            raise RuntimeError("name-keyed mutant unexpectedly passed")
        if SELECTIVE_ASSERTION not in mutant_log and SELECTIVE_ASSERTION not in mutant_xml:
            raise RuntimeError(
                "mutant failed without reaching the selective successor assertion"
            )

        # Restore before exercising the second independent mutation. This
        # keeps each mutant attributable to one production decision.
        SOURCE.write_bytes(original_source)
        if sha256_bytes(SOURCE.read_bytes()) != before_sha:
            raise RuntimeError("production source was not restored before async baseline")
        run_source_hashes["async-baseline-green"] = before_sha
        action_baseline_exit = run_focused_test(
            artifact_dir,
            "async-baseline-green",
            ACTION_TEST_FILTER,
            final_source_sha=before_sha,
            executed_source_sha=before_sha,
        )
        if action_baseline_exit != 0:
            raise RuntimeError(
                f"async kill baseline focused test exited {action_baseline_exit}"
            )

        async_mutant_source = original_source.decode("utf-8").replace(
            ACTION_SOURCE_ANCHOR,
            ACTION_NAME_KEYED_MUTANT,
            1,
        ).encode("utf-8")
        SOURCE.write_bytes(async_mutant_source)
        action_mutant_sha = sha256_bytes(async_mutant_source)
        run_source_hashes["async-name-keyed-mutant-red"] = action_mutant_sha
        (artifact_dir / "async-name-keyed-mutant-source.kt").write_bytes(
            async_mutant_source
        )
        action_mutant_exit = run_focused_test(
            artifact_dir,
            "async-name-keyed-mutant-red",
            ACTION_TEST_FILTER,
            final_source_sha=before_sha,
            executed_source_sha=action_mutant_sha,
        )
        action_mutant_log = (
            artifact_dir / "async-name-keyed-mutant-red.log"
        ).read_text(encoding="utf-8", errors="replace")
        action_mutant_xml = ""
        action_mutant_xml_path = artifact_dir / "async-name-keyed-mutant-red.xml"
        if action_mutant_xml_path.exists():
            action_mutant_xml = action_mutant_xml_path.read_text(
                encoding="utf-8", errors="replace"
            )
        if action_mutant_exit == 0:
            raise RuntimeError("async name-keyed mutant unexpectedly passed")
        if (
            ACTION_SELECTIVE_ASSERTION not in action_mutant_log
            and ACTION_SELECTIVE_ASSERTION not in action_mutant_xml
        ):
            raise RuntimeError(
                "async mutant failed without reaching the selective captured-generation assertion"
            )
    except Exception as exc:  # noqa: BLE001 - summary must survive a red run
        error = str(exc)
    finally:
        SOURCE.write_bytes(original_source)
        restored_sha = sha256_bytes(SOURCE.read_bytes())

    # Even when the mutant oracle check itself fails, run the restored source
    # once. A mutation artifact without the final green pass is incomplete.
    if baseline_exit == 0 and mutant_exit is not None:
        current_restored_sha = sha256_bytes(SOURCE.read_bytes())
        run_source_hashes["restored-green"] = current_restored_sha
        restored_exit = run_focused_test(
            artifact_dir,
            "restored-green",
            final_source_sha=before_sha,
            executed_source_sha=current_restored_sha,
        )
        if restored_exit != 0:
            error = error or f"restored focused test exited {restored_exit}"
        elif restored_sha != before_sha:
            error = error or "restored production source does not match the original SHA-256"
    if action_baseline_exit == 0 and action_mutant_exit is not None:
        current_restored_sha = sha256_bytes(SOURCE.read_bytes())
        run_source_hashes["async-restored-green"] = current_restored_sha
        action_restored_exit = run_focused_test(
            artifact_dir,
            "async-restored-green",
            ACTION_TEST_FILTER,
            final_source_sha=before_sha,
            executed_source_sha=current_restored_sha,
        )
        if action_restored_exit != 0:
            error = error or (
                f"async restored focused test exited {action_restored_exit}"
            )
        elif restored_sha != before_sha:
            error = error or "restored production source does not match the original SHA-256"

    write_summary(
        artifact_dir,
        before_sha=before_sha,
        mutant_sha=mutant_sha,
        restored_sha=restored_sha,
        baseline_exit=baseline_exit,
        mutant_exit=mutant_exit,
        restored_exit=restored_exit,
        action_baseline_exit=action_baseline_exit,
        action_mutant_exit=action_mutant_exit,
        action_restored_exit=action_restored_exit,
        action_mutant_sha=action_mutant_sha,
        run_source_hashes=run_source_hashes,
        final_source_sha=before_sha,
        error=error,
    )

    if error is not None:
        print(f"mutation proof failed: {error}", file=sys.stderr)
        print(f"artifacts: {artifact_dir}", file=sys.stderr)
        return 1
    print(f"mutation proof passed: {artifact_dir}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
