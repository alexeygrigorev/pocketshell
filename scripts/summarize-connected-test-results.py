#!/usr/bin/env python3
"""Print a non-vacuous summary of connected-test JUnit XML evidence.

Pass one or more result XML files or directories. Directories are searched
recursively for ``TEST-*.xml``. Paths stay as Python ``Path`` objects end to
end, so Android's names such as ``TEST-test(AVD) - 14-_app-.xml`` cannot be
split by shell whitespace (the issue #1821 evidence-runner defect).
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path
from xml.etree import ElementTree


def result_files(inputs: list[Path]) -> list[Path]:
    files: set[Path] = set()
    for item in inputs:
        if item.is_file():
            if item.suffix == ".xml":
                files.add(item.resolve())
        elif item.is_dir():
            files.update(path.resolve() for path in item.rglob("TEST-*.xml") if path.is_file())
        else:
            raise ValueError(f"result input does not exist: {item}")
    return sorted(files)


def local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", nargs="+", type=Path, help="JUnit XML file or result directory")
    args = parser.parse_args(argv)

    try:
        files = result_files(args.inputs)
    except ValueError as error:
        print(f"ERROR: {error}", file=sys.stderr)
        return 2

    if not files:
        print("ERROR: parsed zero XML files; this run has no usable test evidence", file=sys.stderr)
        return 90

    tests = failures = errors = skipped = 0
    cases: list[tuple[str, str, str]] = []
    for path in files:
        try:
            root = ElementTree.parse(path).getroot()
        except (OSError, ElementTree.ParseError) as error:
            print(f"ERROR: could not parse {path}: {error}", file=sys.stderr)
            return 2

        tests += int(root.get("tests", "0"))
        failures += int(root.get("failures", "0"))
        errors += int(root.get("errors", "0"))
        skipped += int(root.get("skipped", "0"))
        for testcase in (node for node in root.iter() if local_name(node.tag) == "testcase"):
            child_tags = {local_name(child.tag) for child in testcase}
            if child_tags & {"failure", "error"}:
                verdict = "FAIL"
            elif "skipped" in child_tags:
                verdict = "SKIP"
            else:
                verdict = "PASS"
            class_name = testcase.get("classname", "").rsplit(".", 1)[-1]
            cases.append((class_name, testcase.get("name", ""), verdict))

    if tests <= 0:
        print(
            f"ERROR: parsed {len(files)} XML file(s) but they report zero tests; "
            "this run has no usable test evidence",
            file=sys.stderr,
        )
        return 91

    print(
        f"XML_SUITE parsed_files={len(files)} tests={tests} failures={failures} "
        f"errors={errors} skipped={skipped}",
    )
    for class_name, test_name, verdict in sorted(cases):
        print(f"CASE {class_name} {test_name} {verdict}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
