#!/usr/bin/env python3
"""Regenerate (or check) the README's `pocketshell send` exit-code table (#2153).

WHY THIS EXISTS

`pocketshell send`'s exit-code table existed in two places with no mechanical
link: `EXIT_CODE_TABLE` in `pocketshell/send.py` — which `--help` is rendered
from, so THAT half cannot drift — and a hand-written prose table in
`tools/pocketshell/README.md`. #2122 corrected the exit-4 wording on the README
half and left `--help` stale; #2136 fixed `--help` and added description pins,
but only on the `EXIT_CODE_TABLE` side. Either way the two disagreed, and the
README is what a client author reads while implementing #2124's auto-retry
branching — the exact decision a wrong exit-4 description makes unsafe.

So the README block is GENERATED from the tuple, fenced by markers, and pinned
by `test_send.py` in the required `Python utility tests (pocketshell)` check.
Both directions redden: edit the tuple alone and the committed README no longer
matches the render; edit the README alone and it no longer matches the tuple.

USAGE (from `tools/pocketshell/`, so `click` is importable — `uv run` supplies
the project environment; a plain `./scripts/sync-readme-exit-codes.py` works
wherever the package is already installed):

    uv run scripts/sync-readme-exit-codes.py              # rewrite the block
    uv run scripts/sync-readme-exit-codes.py --check      # verify only
    uv run scripts/sync-readme-exit-codes.py --self-test  # prove it detects

`--self-test` plants a drift of each direction on a throwaway copy and requires
`--check` to go RED for each, plus GREEN on the clean tree and GREEN on an edit
OUTSIDE the block — so a guard that has silently stopped detecting anything
cannot pass as protection.
"""

from __future__ import annotations

import argparse
import difflib
import shutil
import sys
import tempfile
from pathlib import Path

PACKAGE_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(PACKAGE_ROOT / "src"))

from pocketshell import send as send_mod  # noqa: E402  (after the sys.path fix)

README = PACKAGE_ROOT / "README.md"


def check(readme_path: Path) -> int:
    """0 when the committed block matches the render, 1 otherwise."""
    text = readme_path.read_text(encoding="utf-8")
    try:
        committed = send_mod.extract_readme_exit_code_table(text)
    except ValueError as exc:
        print(f"FAIL {readme_path}: {exc}", file=sys.stderr)
        return 1
    expected = send_mod.render_readme_exit_code_table()
    if committed == expected:
        print(f"OK   {readme_path}: exit-code table matches EXIT_CODE_TABLE")
        return 0
    diff = difflib.unified_diff(
        committed.splitlines(),
        expected.splitlines(),
        fromfile="README.md (committed)",
        tofile="EXIT_CODE_TABLE (send.py)",
        lineterm="",
    )
    print(
        f"FAIL {readme_path}: the exit-code table disagrees with EXIT_CODE_TABLE.\n"
        "Regenerate with tools/pocketshell/scripts/sync-readme-exit-codes.py",
        file=sys.stderr,
    )
    for line in diff:
        print(line, file=sys.stderr)
    return 1


def write(readme_path: Path) -> int:
    text = readme_path.read_text(encoding="utf-8")
    updated = send_mod.readme_with_exit_code_table(text)
    if updated == text:
        print(f"OK   {readme_path}: already up to date")
        return 0
    readme_path.write_text(updated, encoding="utf-8")
    print(f"WROTE {readme_path}: exit-code table regenerated from EXIT_CODE_TABLE")
    return 0


def _self_test() -> int:
    """Plant a violation of each direction and require a RED."""
    failures: list[str] = []

    def expect(label: str, rc: int, want: int) -> None:
        verdict = "OK" if rc == want else "SELF-TEST FAILURE"
        print(f"  [{verdict}] {label}: rc={rc} (want {want})")
        if rc != want:
            failures.append(label)

    with tempfile.TemporaryDirectory() as tmp:
        work = Path(tmp) / "README.md"
        shutil.copy2(README, work)
        clean = work.read_text(encoding="utf-8")

        expect("clean tree is GREEN", check(work), 0)

        # Direction 1 — the README half is edited alone. This is the #2122
        # shape: someone corrects (or breaks) the prose and the code half is
        # untouched.
        committed = send_mod.extract_readme_exit_code_table(clean)
        mutated_readme = clean.replace(
            committed,
            committed.replace("Nothing was injected or journaled.", "Nothing happened."),
            1,
        )
        assert mutated_readme != clean, "README-side mutation did not apply"
        work.write_text(mutated_readme, encoding="utf-8")
        expect("README edited alone is RED", check(work), 1)

        # Direction 2 — the table half is edited alone. This is the #2136 shape
        # in reverse: the tuple moves (and `--help` with it) and the README is
        # left behind. Simulated by regenerating the block from a mutated tuple
        # and checking it against the real one.
        mutated_table = tuple(
            (code, reason, description.replace("Invalid or missing", "Bogus or missing"))
            for code, reason, description in send_mod.EXIT_CODE_TABLE
        )
        assert mutated_table != send_mod.EXIT_CODE_TABLE, "table-side mutation did not apply"
        work.write_text(
            send_mod.readme_with_exit_code_table(clean, mutated_table), encoding="utf-8"
        )
        expect("EXIT_CODE_TABLE edited alone is RED", check(work), 1)

        # Selectivity — an edit OUTSIDE the generated block must stay GREEN, or
        # the guard is a tripwire on the whole README rather than on the table.
        work.write_text(clean + "\n\nAn unrelated new paragraph.\n", encoding="utf-8")
        expect("edit outside the block stays GREEN", check(work), 0)

        # A README that lost its fences must fail loudly rather than silently
        # stop checking anything.
        work.write_text(clean.replace(send_mod.README_TABLE_BEGIN, ""), encoding="utf-8")
        expect("missing fence is RED", check(work), 1)

    if failures:
        print(f"SELF-TEST FAILED: {', '.join(failures)}", file=sys.stderr)
        return 1
    print("SELF-TEST PASSED")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    group = parser.add_mutually_exclusive_group()
    group.add_argument("--check", action="store_true", help="verify without writing")
    group.add_argument(
        "--self-test", action="store_true", help="prove the check still detects drift"
    )
    args = parser.parse_args(argv)

    if args.self_test:
        return _self_test()
    if args.check:
        return check(README)
    return write(README)


if __name__ == "__main__":
    raise SystemExit(main())
