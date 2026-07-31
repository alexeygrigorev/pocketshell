#!/usr/bin/env python3
"""Count the post-#1863 definitive-dead-channel signal per journey attempt.

This is deliberately an event-observation tool, not a health/severity
classifier.  Its post-#1863 calibration is binary: zero declarations means
ZERO_OBSERVED; one or more means DEAD_CHANNEL_OBSERVED.
"""

from __future__ import annotations

import argparse
from pathlib import Path
import sys


SIGNAL = "liveness-probe DECLARED DROP (control channel definitively closed)"


class MeasurementError(RuntimeError):
    """The artifact cannot support an observed-zero measurement."""


def count_signal(path: Path) -> int:
    try:
        return sum(
            line.count(SIGNAL)
            for line in path.read_text(encoding="utf-8", errors="replace").splitlines()
        )
    except OSError as error:
        raise MeasurementError(f"cannot read log source {path}: {error}") from error


def manifest_value(attempt_dir: Path, key: str, fallback: str) -> str:
    manifest = attempt_dir / "manifest.txt"
    try:
        for line in manifest.read_text(encoding="utf-8", errors="replace").splitlines():
            name, separator, value = line.partition("=")
            if separator and name == key:
                return value
    except OSError:
        pass
    return fallback


def attempt_number(attempt_dir: Path) -> int:
    value = manifest_value(
        attempt_dir,
        "attempt",
        attempt_dir.name.removeprefix("attempt-"),
    )
    try:
        return int(value)
    except ValueError:
        return sys.maxsize


def canonical_attempt_root(report_root: Path) -> Path:
    return report_root / "artifacts" / "ci-journey" / "class-attempts"


def measure(report_root: Path) -> list[tuple[str, int, int, int, int, str]]:
    attempts_root = canonical_attempt_root(report_root)
    if not attempts_root.is_dir():
        raise FileNotFoundError(
            f"canonical journey attempts missing: {attempts_root} "
            "(the ci-journey-attempt-1 snapshot is intentionally excluded)"
        )

    rows: list[tuple[str, int, int, int, int, str]] = []
    attempt_dirs = sorted(
        path
        for path in attempts_root.glob("*/*/attempt-*")
        if path.is_dir()
    )
    if not attempt_dirs:
        raise MeasurementError(
            f"canonical journey attempts tree is empty: {attempts_root}"
        )

    for attempt_dir in attempt_dirs:
        fallback_class = attempt_dir.parent.name.split("--", 1)[0]
        class_name = manifest_value(attempt_dir, "class", fallback_class)
        attempt = attempt_number(attempt_dir)

        device_log = attempt_dir / "device-logcat.txt"
        has_device_log = device_log.is_file()
        device_count = count_signal(device_log) if has_device_log else 0

        # UTP writes one logcat per test method.  Aggregate those method logs,
        # then take the maximum against device-logcat: neither source is a
        # guaranteed superset of the other, while summing them double-counts.
        utp_logs = [
            path
            for path in attempt_dir.glob("android-test-outputs/**/logcat-*.txt")
            if path.is_file()
        ]
        if not has_device_log and not utp_logs:
            raise MeasurementError(
                f"attempt has no readable device or UTP logcat source: {attempt_dir}"
            )
        utp_count = sum(
            count_signal(path)
            for path in utp_logs
        )
        event_count = max(device_count, utp_count)
        scale = (
            "DEAD_CHANNEL_OBSERVED"
            if event_count > 0
            else "ZERO_OBSERVED"
        )
        rows.append(
            (
                class_name,
                attempt,
                device_count,
                utp_count,
                event_count,
                scale,
            )
        )
    return sorted(rows, key=lambda row: (row[0], row[1]))


def main() -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Count definitive-dead-channel recovery events in the canonical "
            "CI journey class-attempt tree. No severity verdict is emitted."
        )
    )
    parser.add_argument(
        "report_root",
        type=Path,
        help="downloaded Android-report root containing artifacts/ci-journey",
    )
    args = parser.parse_args()

    try:
        rows = measure(args.report_root)
    except (FileNotFoundError, MeasurementError) as error:
        parser.error(str(error))

    print("class\tattempt\tdevice_logcat\tutp_method_logcats\tevents\tscale")
    for row in rows:
        print("\t".join(str(value) for value in row))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
