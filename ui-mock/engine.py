"""Serialized render queue and debounced source watcher."""
from __future__ import annotations

from collections import deque
import os
from pathlib import Path
import threading
import time

from builder import Builder
from catalog import discover


class RepositoryLock:
    """Reject a second ui-mock server operating on the same build/renders paths."""
    def __init__(self, root: Path):
        path = root / ".gradle/ui-mock.lock"
        path.parent.mkdir(parents=True, exist_ok=True)
        self.file = path.open("a+b")
        try:
            if os.name == "nt":
                import msvcrt
                self.file.seek(0)
                if not self.file.read(1):
                    self.file.write(b"0")
                    self.file.flush()
                self.file.seek(0)
                msvcrt.locking(self.file.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(self.file.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
        except (OSError, BlockingIOError):
            self.file.close()
            raise RuntimeError("Another ui-mock server holds this repository's render lock") from None

    def close(self) -> None:
        self.file.close()


def source_stamp(root: Path) -> tuple:
    result = []
    for directory in (root / "app2", root / "shared", root / "gradle"):
        if not directory.exists():
            continue
        for base, dirs, names in os.walk(directory, followlinks=False):
            dirs[:] = sorted(d for d in dirs if d not in {"build", ".gradle", ".git", "node_modules"}
                             and not (Path(base) / d).is_symlink())
            for name in sorted(names):
                path = Path(base) / name
                if path.is_symlink():
                    continue
                try:
                    st = path.stat()
                    result.append((str(path.relative_to(root)), st.st_mtime_ns, st.st_size))
                except FileNotFoundError:
                    pass  # Atomic editor/agent replace; the next scan sees the new file.
    for name in ("settings.gradle.kts", "build.gradle.kts", "gradle.properties", "local.properties"):
        path = root / name
        if path.is_file():
            st = path.stat()
            result.append((name, st.st_mtime_ns, st.st_size))
    return tuple(result)


class Engine:
    def __init__(self, root: Path, *, include_kit=False, watch=True, timeout=600, builder=None):
        self.root, self.include_kit, self.watch = root, include_kit, watch
        self.builder = builder or Builder(root, timeout)
        self.cv = threading.Condition()
        self.stop = threading.Event()
        self.cases, self.warnings = discover(root, include_kit)
        self.by_id = {c.id: c for c in self.cases}
        self.selected = self.cases[0].id if self.cases else None
        self.ticket, self.completed = 0, 0
        self.phase = "idle" if self.cases else "error"
        self.error = "" if self.cases else "No supported render fixtures found. Check --repo."
        self.reason = "Choose a fixture to render"
        self.lines = deque(maxlen=800)
        self.images = []
        self.image_revision = 0
        self.rendered_case = None
        self.rendered_ticket = -1
        self.last_seconds = None
        self.catalog_revision = 1
        self.threads = []

    def start(self, selected=None):
        if selected is not None and selected not in self.by_id:
            raise ValueError("Unknown --case id; use --list")
        if selected:
            self.selected = selected
        worker = threading.Thread(target=self._work, name="ui-mock-render", daemon=True)
        self.threads.append(worker)
        worker.start()
        if self.watch:
            watcher = threading.Thread(target=self._watch, name="ui-mock-watch", daemon=True)
            self.threads.append(watcher)
            watcher.start()
        if self.selected:
            self.request(self.selected, "Initial render")

    def request(self, case_id=None, reason="Manual render"):
        with self.cv:
            chosen = case_id or self.selected
            if chosen not in self.by_id:
                raise ValueError("Unknown or removed render case")
            self.selected = chosen
            self.ticket += 1
            self.phase, self.error, self.reason = "queued", "", reason
            self.cv.notify_all()

    def log(self, text):
        with self.cv:
            self.lines.append(text)

    def status(self):
        with self.cv:
            return {
                "phase": self.phase, "error": self.error, "reason": self.reason,
                "selected": self.selected, "rendered_case": self.rendered_case,
                "ticket": self.ticket, "rendered_ticket": self.rendered_ticket,
                "image_revision": self.image_revision, "last_seconds": self.last_seconds,
                "stale": self.rendered_ticket != self.ticket or self.phase != "ready",
                "catalog_revision": self.catalog_revision,
                "images": [{"name": i.name, "width": i.width, "height": i.height} for i in self.images],
            }

    def catalog(self):
        with self.cv:
            return {"cases": [c.public() for c in self.cases], "warnings": self.warnings,
                    "revision": self.catalog_revision}

    def image(self, index: int, revision: int):
        with self.cv:
            if revision != self.image_revision or not 0 <= index < len(self.images):
                raise ValueError("Image was replaced or does not exist")
            return self.images[index].data

    def _work(self):
        while not self.stop.is_set():
            with self.cv:
                self.cv.wait_for(lambda: self.stop.is_set() or self.ticket > self.completed)
                if self.stop.is_set():
                    return
                ticket, case = self.ticket, self.by_id.get(self.selected)
                self.completed = ticket
                if case is None:
                    continue
                self.phase = "building"
                self.lines.clear()
            started = time.monotonic()
            try:
                images = self.builder.build(case, self.log)
                with self.cv:
                    if ticket == self.ticket and not self.stop.is_set():
                        self.images = images
                        self.image_revision += 1
                        self.rendered_case, self.rendered_ticket = case.id, ticket
                        self.last_seconds = round(time.monotonic() - started, 2)
                        self.phase, self.error = "ready", ""
            except Exception as exc:
                self.log(f"ERROR: {exc}")
                with self.cv:
                    if ticket == self.ticket:
                        self.phase, self.error = "error", str(exc)

    def _watch(self):
        previous = source_stamp(self.root)
        dirty_at = None
        while not self.stop.wait(0.5):
            try:
                current = source_stamp(self.root)
                if current != previous:
                    previous, dirty_at = current, time.monotonic()
                elif dirty_at is not None and time.monotonic() - dirty_at >= 0.6:
                    dirty_at = None
                    cases, warnings = discover(self.root, self.include_kit)
                    with self.cv:
                        self.cases, self.warnings = cases, warnings
                        self.by_id = {c.id: c for c in cases}
                        self.catalog_revision += 1
                        if self.selected not in self.by_id:
                            self.ticket += 1  # Invalidate any in-flight result.
                            self.phase, self.error = "error", "Selected fixture was removed; choose another"
                            continue
                    self.request(reason="Source changed")
            except (OSError, ValueError) as exc:
                self.log(f"Watcher: {exc}")

    def close(self):
        self.stop.set()
        with self.cv:
            self.cv.notify_all()
        self.builder.close()
        for thread in self.threads:
            thread.join(timeout=5)
