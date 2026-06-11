"""`pocketshell prune-attachments` — server-side attachment retention backstop.

The PocketShell Android composer uploads each prompt attachment to the
remote host under ``~/.pocketshell/attachments/<host-scope>/`` (see
``PromptAttachmentStager.REMOTE_DIRECTORY`` on the client). Nothing on the
host ever removes those files, so the directory grows unbounded on every
host the user attaches to (issue #547).

The client already prunes the *active* scope dir on a fresh upload
(``RemoteAttachmentPruner`` — option 1 in #547). This command is the
**server-side backstop** (option 2): it runs ON the host, over the whole
``~/.pocketshell/attachments/`` tree, so even hosts the user stopped
attaching to are eventually trimmed. It is meant to be invoked by the
normal ``pocketshell`` plumbing (e.g. on connect / during usage probes) or
from a maintainer's cron.

Safety bounds (deny-by-default — this deletes files):

- **Scoped to one directory.** Only files directly under
  ``~/.pocketshell/attachments/<scope>/`` (depth-2 from the root) are ever
  considered. The root and the per-scope directories themselves are never
  deleted; the user's own files outside the attachments tree are never
  touched. The resolved root must stay inside ``$HOME`` or the command
  refuses to run.
- **Regular files only.** Symlinks, directories, sockets, and anything
  that is not a plain regular file is skipped — a symlink inside the
  attachments dir can never be used to delete a file elsewhere.
- **Age bound (TTL).** A file is a deletion candidate only when its mtime
  is strictly older than ``DEFAULT_TTL_DAYS`` (14 days).
- **Size cap.** After the TTL pass, if the *surviving* attachments still
  exceed ``DEFAULT_MAX_TOTAL_BYTES`` (256 MiB), the oldest survivors are
  deleted (oldest-first) until the tree is back under the cap. Files
  younger than ``PROTECT_NEWEST_HOURS`` (24h) are never deleted by the
  size cap so an active session's just-uploaded files are spared even
  during a big backlog clear.
- **Dry-run default off, but ``--dry-run`` reports without deleting.**

The command is best-effort and never raises on a per-file delete error
(permissions, races) — it logs the failure into the result summary and
continues, so a single bad file can't wedge the whole prune.
"""

from __future__ import annotations

import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Optional

import click

# Retention tuning knobs. Kept as module constants so both the CLI and the
# unit suite reference one source of truth.
DEFAULT_TTL_DAYS: int = 14
DEFAULT_MAX_TOTAL_BYTES: int = 256 * 1024 * 1024  # 256 MiB
PROTECT_NEWEST_HOURS: int = 24

# The attachments root, relative to ``$HOME``. Mirrors the client's
# ``PromptAttachmentStager.REMOTE_DIRECTORY``.
ATTACHMENTS_RELATIVE_ROOT = Path(".pocketshell") / "attachments"

_SECONDS_PER_DAY = 24 * 60 * 60
_SECONDS_PER_HOUR = 60 * 60


@dataclass
class DeletedFile:
    """One file the prune removed (or would remove in dry-run)."""

    path: str
    size: int
    age_days: float
    reason: str  # "ttl" or "size-cap"


@dataclass
class PruneResult:
    """Structured summary of a prune pass (CLI emits this as JSON)."""

    root: str
    scanned_files: int = 0
    scanned_bytes: int = 0
    deleted: list[DeletedFile] = field(default_factory=list)
    errors: list[str] = field(default_factory=list)
    dry_run: bool = False
    skipped_root_missing: bool = False

    @property
    def deleted_count(self) -> int:
        return len(self.deleted)

    @property
    def deleted_bytes(self) -> int:
        return sum(d.size for d in self.deleted)

    def to_dict(self) -> dict:
        return {
            "root": self.root,
            "dry_run": self.dry_run,
            "skipped_root_missing": self.skipped_root_missing,
            "scanned_files": self.scanned_files,
            "scanned_bytes": self.scanned_bytes,
            "deleted_count": self.deleted_count,
            "deleted_bytes": self.deleted_bytes,
            "deleted": [
                {
                    "path": d.path,
                    "size": d.size,
                    "age_days": round(d.age_days, 3),
                    "reason": d.reason,
                }
                for d in self.deleted
            ],
            "errors": self.errors,
        }


@dataclass
class _Candidate:
    path: Path
    size: int
    mtime: float


def resolve_attachments_root(home: Optional[Path] = None) -> Path:
    """Resolve ``~/.pocketshell/attachments`` from ``$HOME``.

    Pulled out so the unit suite can point it at a tmp dir. Uses the
    ``home`` argument when given, otherwise ``Path.home()``.
    """
    base = home if home is not None else Path.home()
    return (base / ATTACHMENTS_RELATIVE_ROOT).resolve()


def _iter_attachment_files(root: Path) -> list[_Candidate]:
    """Collect regular files exactly two levels under ``root``.

    Layout is ``root/<scope>/<file>``. We deliberately do NOT recurse
    deeper and we never follow symlinks: a candidate must be a real
    regular file (``Path.is_file()`` follows symlinks, so we additionally
    reject symlinks with ``is_symlink()``).
    """
    candidates: list[_Candidate] = []
    for scope_dir in _safe_iterdir(root):
        # Only descend into real directories that are direct children of
        # the root — never symlinked dirs (which could point outside the
        # attachments tree).
        if scope_dir.is_symlink() or not scope_dir.is_dir():
            continue
        for entry in _safe_iterdir(scope_dir):
            if entry.is_symlink():
                continue
            if not entry.is_file():
                continue
            try:
                stat = entry.stat()
            except OSError:
                continue
            candidates.append(
                _Candidate(path=entry, size=stat.st_size, mtime=stat.st_mtime)
            )
    return candidates


def _safe_iterdir(directory: Path) -> list[Path]:
    try:
        return sorted(directory.iterdir())
    except OSError:
        return []


def prune_attachments(
    root: Path,
    *,
    now: float,
    ttl_days: int = DEFAULT_TTL_DAYS,
    max_total_bytes: int = DEFAULT_MAX_TOTAL_BYTES,
    protect_newest_hours: int = PROTECT_NEWEST_HOURS,
    dry_run: bool = False,
) -> PruneResult:
    """Prune attachments under ``root`` by TTL then by size cap.

    ``root`` MUST be the resolved ``~/.pocketshell/attachments`` directory;
    the caller is responsible for the ``$HOME`` containment check (the CLI
    does it). ``now`` is the current epoch-seconds (injected so the unit
    suite is deterministic).
    """
    result = PruneResult(root=str(root), dry_run=dry_run)

    if not root.exists() or not root.is_dir():
        result.skipped_root_missing = True
        return result

    candidates = _iter_attachment_files(root)
    result.scanned_files = len(candidates)
    result.scanned_bytes = sum(c.size for c in candidates)

    ttl_seconds = ttl_days * _SECONDS_PER_DAY
    protect_seconds = protect_newest_hours * _SECONDS_PER_HOUR

    survivors: list[_Candidate] = []

    # Pass 1 — TTL. A file strictly older than the TTL is deleted.
    for c in candidates:
        age = now - c.mtime
        if age > ttl_seconds:
            _delete(result, c, now=now, reason="ttl", dry_run=dry_run)
        else:
            survivors.append(c)

    # Pass 2 — size cap. If survivors still exceed the cap, delete the
    # oldest (lowest mtime first) until back under, but never delete a file
    # younger than the protect window.
    surviving_bytes = sum(c.size for c in survivors)
    if surviving_bytes > max_total_bytes:
        # Oldest first.
        for c in sorted(survivors, key=lambda x: x.mtime):
            if surviving_bytes <= max_total_bytes:
                break
            age = now - c.mtime
            if age < protect_seconds:
                continue
            _delete(result, c, now=now, reason="size-cap", dry_run=dry_run)
            surviving_bytes -= c.size

    return result


def _delete(
    result: PruneResult,
    candidate: _Candidate,
    *,
    now: float,
    reason: str,
    dry_run: bool,
) -> None:
    """Record (and, unless dry-run, perform) one deletion. Best-effort."""
    age_days = (now - candidate.mtime) / _SECONDS_PER_DAY
    if not dry_run:
        try:
            candidate.path.unlink()
        except OSError as exc:
            result.errors.append(f"{candidate.path}: {exc}")
            return
    result.deleted.append(
        DeletedFile(
            path=str(candidate.path),
            size=candidate.size,
            age_days=age_days,
            reason=reason,
        )
    )


@click.command(
    name="prune-attachments",
    context_settings={"help_option_names": ["-h", "--help"]},
    help=(
        "Prune the ~/.pocketshell/attachments/ tree on this host.\n\n"
        "Server-side retention backstop for prompt attachments uploaded by "
        "the PocketShell composer (issue #547). Deletes regular files older "
        "than the TTL, then trims the oldest survivors if the tree still "
        "exceeds the size cap. Only touches files inside the attachments "
        "directory; never the user's own files. Best-effort: per-file "
        "errors are reported, not raised."
    ),
)
@click.option(
    "--ttl-days",
    type=click.IntRange(min=0),
    default=DEFAULT_TTL_DAYS,
    show_default=True,
    help="Delete attachments strictly older than this many days.",
)
@click.option(
    "--max-total-mib",
    type=click.IntRange(min=0),
    default=DEFAULT_MAX_TOTAL_BYTES // (1024 * 1024),
    show_default=True,
    help=(
        "After the TTL pass, trim oldest survivors until the tree is under "
        "this size (MiB). Files younger than the protect window are spared."
    ),
)
@click.option(
    "--protect-newest-hours",
    type=click.IntRange(min=0),
    default=PROTECT_NEWEST_HOURS,
    show_default=True,
    help="Never let the size cap delete files younger than this.",
)
@click.option(
    "--dry-run",
    is_flag=True,
    help="Report what would be deleted without deleting anything.",
)
@click.option(
    "--json",
    "as_json",
    is_flag=True,
    help="Emit the prune summary as JSON.",
)
def prune_attachments_command(
    ttl_days: int,
    max_total_mib: int,
    protect_newest_hours: int,
    dry_run: bool,
    as_json: bool,
) -> None:
    """CLI entrypoint for ``pocketshell prune-attachments``."""
    import time

    home = Path.home().resolve()
    root = resolve_attachments_root(home)

    # Containment guard: the resolved root must stay inside $HOME. This is a
    # belt-and-braces check against a tampered $HOME / ATTACHMENTS root.
    if not _is_within(root, home):
        raise click.ClickException(
            f"refusing to prune {root}: not inside $HOME ({home})"
        )

    result = prune_attachments(
        root,
        now=time.time(),
        ttl_days=ttl_days,
        max_total_bytes=max_total_mib * 1024 * 1024,
        protect_newest_hours=protect_newest_hours,
        dry_run=dry_run,
    )

    if as_json:
        click.echo(json.dumps(result.to_dict(), indent=2))
        return

    if result.skipped_root_missing:
        click.echo(f"no attachments directory at {root}; nothing to prune.")
        return

    verb = "would delete" if dry_run else "deleted"
    mib = result.deleted_bytes / (1024 * 1024)
    click.echo(
        f"scanned {result.scanned_files} files "
        f"({result.scanned_bytes / (1024 * 1024):.1f} MiB); "
        f"{verb} {result.deleted_count} ({mib:.1f} MiB)."
    )
    for d in result.deleted:
        click.echo(f"  {verb}: {d.path} ({d.reason}, {d.age_days:.1f}d)")
    for err in result.errors:
        click.echo(f"  error: {err}", err=True)


def _is_within(path: Path, parent: Path) -> bool:
    """True when ``path`` is ``parent`` or a descendant of it."""
    try:
        path.relative_to(parent)
        return True
    except ValueError:
        return False


__all__ = [
    "DEFAULT_TTL_DAYS",
    "DEFAULT_MAX_TOTAL_BYTES",
    "PROTECT_NEWEST_HOURS",
    "ATTACHMENTS_RELATIVE_ROOT",
    "DeletedFile",
    "PruneResult",
    "resolve_attachments_root",
    "prune_attachments",
    "prune_attachments_command",
]
