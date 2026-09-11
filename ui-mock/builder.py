"""One bounded, incremental Gradle invocation; copy fresh PNGs into memory."""
from __future__ import annotations

from dataclasses import dataclass
import hashlib
import os
from pathlib import Path
import signal
import struct
import subprocess
import threading
import time
import xml.etree.ElementTree as ET
from typing import Callable

from catalog import Case

MAX_PNG = 24 * 1024 * 1024


@dataclass(frozen=True)
class Image:
    name: str
    data: bytes
    width: int
    height: int


def png_image(path: Path) -> Image:
    if path.is_symlink() or not path.is_file() or not 32 <= path.stat().st_size <= MAX_PNG:
        raise RuntimeError(f"Invalid/oversized PNG: {path.name}")
    data = path.read_bytes()
    if data[:8] != b"\x89PNG\r\n\x1a\n" or data[12:16] != b"IHDR" or data[-8:-4] != b"IEND":
        raise RuntimeError(f"Incomplete PNG: {path.name}")
    w, h = struct.unpack(">II", data[16:24])
    if not (0 < w <= 16384 and 0 < h <= 16384):
        raise RuntimeError(f"Invalid PNG dimensions: {path.name}")
    return Image(path.name, data, w, h)


def snapshot(folder: Path) -> dict[str, tuple[int, int, str]]:
    result = {}
    for p in folder.glob("*.png"):
        if p.is_symlink() or not p.is_file():
            continue
        st = p.stat()
        if st.st_size <= MAX_PNG:
            result[p.name] = (st.st_mtime_ns, st.st_size, hashlib.sha256(p.read_bytes()).hexdigest())
    return result


def verify_test_report(path: Path, case: Case) -> int:
    if path.is_symlink() or not path.is_file() or path.stat().st_size > 4 * 1024 * 1024:
        raise RuntimeError("No fresh, bounded test report for the selected render class")
    try:
        report = ET.parse(path).getroot()
    except ET.ParseError as exc:
        raise RuntimeError("Invalid render test report") from exc
    tests = [t for t in report.iter("testcase")
             if t.get("classname") == case.class_name
             and (t.get("name") == case.method or t.get("name", "").startswith(case.method + "["))]
    if not tests or any(t.find(tag) is not None for t in tests for tag in ("skipped", "failure", "error")):
        raise RuntimeError("Selected render did not execute successfully (missing/skipped/failed test)")
    return len(tests)


def command(root: Path, case: Case) -> list[str]:
    wrapper = root / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        raise RuntimeError(f"Gradle wrapper not found: {wrapper}")
    prefix = [str(wrapper)] if os.name == "nt" else ["bash", str(wrapper)]
    return prefix + [
        "--daemon", "--build-cache", "--max-workers=1", "--console=plain",
        "--init-script", str(Path(__file__).with_name("render.init.gradle")),
        f"-Ppocketshell.uiMockModule={case.module}", "-Proborazzi.test.record=true",
        f":{case.module}:testDebugUnitTest", "--tests", case.test_filter,
    ]


class Builder:
    def __init__(self, root: Path, timeout: float = 600):
        self.root, self.timeout = root, timeout
        self._lock = threading.Lock()
        self._process: subprocess.Popen | None = None
        self._closed = False

    @staticmethod
    def kill(process: subprocess.Popen) -> None:
        if process.poll() is not None:
            return
        try:
            if os.name == "nt":
                subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"],
                               stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=10)
            else:
                os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=5)
        except (OSError, subprocess.TimeoutExpired):
            try:
                if os.name != "nt":
                    os.killpg(process.pid, signal.SIGKILL)
                else:
                    process.kill()
            except OSError:
                pass

    def close(self) -> None:
        with self._lock:
            self._closed = True
            process = self._process
        if process:
            self.kill(process)

    def build(self, case: Case, log: Callable[[str], None]) -> list[Image]:
        folder = self.root / case.module.replace(":", "/") / "build/renders"
        folder.mkdir(parents=True, exist_ok=True)
        # Delete exactly the requested static output, not another case's files.
        expected = folder / f"{case.label}.png"
        if expected.is_symlink():
            raise RuntimeError("Refusing symlink render output")
        if expected.exists():
            expected.unlink()
        report = self.root / case.module.replace(":", "/") / "build/test-results/testDebugUnitTest" / f"TEST-{case.class_name}.xml"
        if report.is_symlink():
            raise RuntimeError("Refusing symlink render test report")
        if report.exists():
            report.unlink()
        before = snapshot(folder)
        args = command(self.root, case)
        log("Running one render: " + case.test_filter)
        log("APK assembly/install and the full test suite are not requested.")
        with self._lock:
            if self._closed:
                raise RuntimeError("Renderer stopped")
            self._process = subprocess.Popen(
                args, cwd=self.root, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                text=True, encoding="utf-8", errors="replace", bufsize=1,
                start_new_session=(os.name != "nt"),
            )
            process = self._process
        def read_output() -> None:
            assert process.stdout is not None
            for line in process.stdout:
                log(line.rstrip()[:16000])
        reader = threading.Thread(target=read_output, daemon=True)
        reader.start()
        try:
            try:
                code = process.wait(timeout=self.timeout)
            except subprocess.TimeoutExpired:
                self.kill(process)
                raise RuntimeError("Render exceeded --build-timeout; see build log") from None
            if code:
                raise RuntimeError(f"Gradle failed (exit {code}); previous image is stale")
        finally:
            reader.join(timeout=3)
            with self._lock:
                self._process = None
        count = verify_test_report(report, case)
        log(f"Verified {count} successful execution(s) of the selected render test.")
        after = snapshot(folder)
        fresh = sorted(name for name, fingerprint in after.items() if before.get(name) != fingerprint)
        if not fresh:
            raise RuntimeError("Gradle returned success but produced no fresh PNG; refusing cached/stale output")
        if len(fresh) > 24:
            raise RuntimeError("One selected case produced more than 24 images; narrow this fixture")
        # Parameterized render fixtures may legitimately produce several files.
        return [png_image(folder / name) for name in fresh]
