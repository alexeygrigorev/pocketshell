"""`pocketshell send` — an ACKNOWLEDGED outbound delivery primitive (issue #2122).

Step 1a of epic #2121. Pure host-side. No client changes live here.

Why this exists
---------------

Today the Android composer cannot learn whether a prompt actually landed. It
injects the payload and then *reads the terminal screen*, guessing within an
800 ms / 2 s stopwatch. A missed window becomes a row stuck permanently at
"unconfirmed" whose Retry is a provable no-op, while the exactly-once ledger —
correctly — refuses to re-send. #2121 has the full mechanism.

This command replaces the guess with an answer: **the exec's exit status IS the
acknowledgement.** One round trip, no windows, no needles, no screen scraping.
The truth is decided by the only party that can observe it (the tmux server on
the host), and it is recorded in a durable journal that survives client death,
app restart and reinstall.

Durability invariant (deliberate, see issue #2122 status comment)
-----------------------------------------------------------------

**At-most-once, except on an explicit opt-in: a token is never injected a second
time unless the caller passes ``--resend-interrupted`` on the injecting call
itself.** No sequence of failures, kills, races or automatic housekeeping can
turn an already-injected token back into a state that a plain call will inject.

The journal is two-phase:

1. A ``pending`` record is written (atomically, fsync'd) immediately before the
   one exec that can put bytes into the pane. It carries the writing process's
   identity (pid + start time + boot id).
2. The record is promoted to ``delivered`` once tmux has answered.

Everything before step 1 touches no pane, so a death there is a clean no-op and
the next call retries from scratch. A *definitive* tmux failure (we got a
return code, so we know nothing reached the pane) rolls the claim back, which
leaves the token cleanly retryable — this is what makes ``pane-not-found`` /
``tmux-failed`` non-poisoning. Rolling back means "undo what THIS call did": a
record this call created is removed, and a pre-existing unresolved record this
call overwrote is RESTORED byte-for-byte, never erased (#2122 F1).

**The paste is the point of no return, and that is exactly the scope of exit 4**
(#2136). Exit 4 is reported only where this call put nothing into the pane —
which is what makes the rollback sound and what lets a client that branches on
the table (#2124) retry without risking a duplicate. Once tmux has accepted the
paste the payload is in the pane, so no failure of the following ``send-keys
Enter`` may roll back and none may report exit 4. The two ways that Enter can
fail — tmux answered "no", or tmux stopped being executable between the two
commands — leave byte-identical state (payload in the pane, unsubmitted, claim
kept), so they report one outcome: the unknown, exit 5. Note that exit 4 means
the journal is UNCHANGED, not that it is empty: under ``--resend-interrupted``
the restored pre-existing record keeps the token journaled-unresolved.

An unresolved record therefore has exactly two readings, and they are reported
as different outcomes because they are different facts:

* its owner process is **gone** — a previous attempt died without an answer, so
  delivery is genuinely unknown: exit 5 ``send-interrupted``. Not laundered into
  "delivered" (which would lose a prompt) or "not delivered" (which would
  duplicate one). ``--resend-interrupted`` is the caller's explicit, opt-in
  escape into at-least-once for that single token, so the state is never
  absorbing — the property #2121 says the current client lacks.
* its owner process is **still running** — nothing is unknown; the outcome is
  simply owned by that call: exit 8 ``send-in-progress``, retryable (#2122 F2).
  Reporting the dead-attempt code for a healthy sibling would recreate the
  spurious-unknown class this epic exists to delete, and it is the common shape
  in practice (an automatic flush racing a manual retry, #1602).

The invariant's honest edges, stated rather than glossed:

* A definitive non-zero from ``tmux paste-buffer`` is taken as proof that
  nothing reached the pane. There is no known tmux path that errors after
  writing bytes; if one existed the rollback would be wrong.
* The journal must survive. Delete the state directory (or point
  ``XDG_STATE_HOME`` somewhere else) and the memory is gone, so a re-send
  injects again.
* Automatic pruning only removes RESOLVED records, so an unknown is never aged
  into an injectable ``absent``. The explicit ``--prune-older-than`` sweep is a
  deliberate operator action and does clear them.
* Liveness is proven from ``/proc``; anything unprovable reads as "not alive",
  i.e. the conservative unknown, never as "inject".
* Exit 5 is reported for two different facts — a previous attempt's unknown, and
  this call's own unsubmitted payload — and the invariant holds for both because
  it is a property of the RECORD, not of who wrote it: the token is
  journaled-unresolved either way, so no plain call injects. The reading matters
  to a human deciding whether to opt in, which is why both are spelled out in
  the exit-code table rather than collapsed into one sentence.

Payload fidelity
----------------

The payload is read from stdin as raw bytes and delivered byte-exact. It is
loaded into a tmux paste buffer via ``load-buffer -`` (never argv — argv would
mangle a trailing ``;`` per issue #1845, cap the size, and force quoting), then
committed with ``paste-buffer -d -r``. The ``-r`` is load-bearing: without it
tmux rewrites every ``\\n`` into ``\\r``, which an agent input box reads as
SUBMIT and tears a multi-line prompt into one prompt per line (issue #1636).

This command does NOT frame the payload in bracketed-paste markers. The client
already owns framing (``BracketedPaste.frame``) and framing a second time here
would reproduce issue #1854, where the inner markers reached the receiving
program as literal content. Callers that want bracketed paste write the framed
bytes to stdin; the bytes on stdin are the bytes the pane receives.

Storage
-------

``${XDG_STATE_HOME:-~/.local/state}/pocketshell/sends/`` — ONE file per token,
named ``<sha256(token)>.json`` (the token is opaque and may contain anything, so
it is hashed rather than used as a filename; the raw token is stored inside the
document and verified on read). One file per token, not one shared document,
because a shared document has a read-modify-write lost-update race between
concurrent sends and because a single corrupt document would break every token
rather than one. Written with the temp-file + ``os.replace`` + fsync pattern
used by :mod:`pocketshell.tree`, so a reader never sees a half-written record.
"""

from __future__ import annotations

import hashlib
import json
import os
import re
import subprocess
import sys
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Mapping, Optional, Sequence

import click

# --------------------------------------------------------------------------
# Exit codes — STABLE and documented. The client (#2124) branches on these.
# --------------------------------------------------------------------------

EXIT_OK = 0
EXIT_BAD_USAGE = 2
EXIT_PANE_NOT_FOUND = 3
EXIT_TMUX_FAILED = 4
EXIT_SEND_INTERRUPTED = 5
EXIT_TIMEOUT = 6
EXIT_JOURNAL_FAILED = 7
EXIT_SEND_IN_PROGRESS = 8

# Machine-readable first token printed on stdout for each outcome.
REASON_DELIVERED = "delivered"
REASON_ALREADY_DELIVERED = "already-delivered"
REASON_PRUNED = "pruned"
REASON_BAD_USAGE = "bad-usage"
REASON_PANE_NOT_FOUND = "pane-not-found"
REASON_TMUX_FAILED = "tmux-failed"
REASON_SEND_INTERRUPTED = "send-interrupted"
REASON_SEND_IN_PROGRESS = "send-in-progress"
REASON_JOURNAL_CORRUPT = "journal-corrupt"
REASON_TIMEOUT = "timeout"
REASON_JOURNAL_FAILED = "journal-failed"

#: The documented exit-code table. ``--help`` is rendered FROM this tuple, so
#: the documentation cannot drift from the constants (a test asserts every row
#: appears in ``--help``).
EXIT_CODE_TABLE: tuple[tuple[int, str, str], ...] = (
    (
        EXIT_OK,
        f"{REASON_DELIVERED} | {REASON_ALREADY_DELIVERED} | {REASON_PRUNED}",
        "Success. 'delivered' = injected by THIS call and journaled. "
        "'already-delivered' = the token was already journaled, nothing was "
        "injected. 'pruned' = --prune-older-than removed N records.",
    ),
    (
        EXIT_BAD_USAGE,
        REASON_BAD_USAGE,
        "Invalid or missing arguments. Nothing was injected or journaled.",
    ),
    (
        EXIT_PANE_NOT_FOUND,
        REASON_PANE_NOT_FOUND,
        "The pane id does not exist on the tmux server, or it is dead. "
        "Nothing was injected; the token is NOT journaled and stays retryable.",
    ),
    (
        EXIT_TMUX_FAILED,
        REASON_TMUX_FAILED,
        "tmux is missing, no server is running, or a tmux command returned a "
        "definitive failure. This call put NOTHING into the pane and recorded "
        "no delivery, and it left the journal exactly as it found it: a claim "
        "this call took is released, and a pre-existing unresolved record it "
        "overwrote under --resend-interrupted is restored byte-for-byte. A "
        "retry therefore cannot duplicate — but 'unchanged' is not 'absent': "
        "if the token was already journaled-unresolved it still is, and the "
        "next plain call answers 'send-interrupted' rather than injecting.",
    ),
    (
        EXIT_SEND_INTERRUPTED,
        f"{REASON_SEND_INTERRUPTED} | {REASON_JOURNAL_CORRUPT}",
        "Delivery is genuinely UNKNOWN and the token is left "
        "journaled-unresolved, so no plain call will ever inject it again. Two "
        "ways in. Either a PREVIOUS attempt died without an answer (or left an "
        "unreadable record) and its owning process is gone, in which case this "
        "call injected nothing; or THIS call got past the point of no return — "
        "tmux accepted the paste and then the Enter failed, or the delivery "
        "could not be journaled — in which case the payload may ALREADY be in "
        "the pane. Never auto-retry either reading. Re-run with "
        "--resend-interrupted only to accept a possible duplicate.",
    ),
    (
        EXIT_TIMEOUT,
        REASON_TIMEOUT,
        "A tmux invocation exceeded --timeout. If the timeout hit at or after "
        "the commit the token is left in the unknown state above and the "
        "payload may ALREADY be in the pane; a retry then reports "
        "'send-interrupted' rather than injecting again.",
    ),
    (
        EXIT_JOURNAL_FAILED,
        REASON_JOURNAL_FAILED,
        "The durable token journal could not be read or written (permissions, "
        "disk). Nothing was injected — the journal is written BEFORE the pane "
        "is touched precisely so this failure is safe.",
    ),
    (
        EXIT_SEND_IN_PROGRESS,
        REASON_SEND_IN_PROGRESS,
        "Another send for this token is STILL RUNNING (its process is alive on "
        "this host). Nothing was injected by this call and nothing is unknown: "
        "the outcome is owned by that call. Retry shortly to read the answer — "
        "it will be 'delivered'/'already-delivered' or, if that process dies, "
        "'send-interrupted'. --resend-interrupted does not override this: "
        "there is no unknown to resolve while the owner is alive, and forcing "
        "one would duplicate the payload.",
    ),
)

# --------------------------------------------------------------------------
# The SECOND rendering of the table above: the README (issue #2153)
# --------------------------------------------------------------------------
#
# ``--help`` is rendered from ``EXIT_CODE_TABLE`` by ``_epilog()`` below, so it
# cannot drift. The prose table in ``tools/pocketshell/README.md`` had no such
# link, and that is precisely what produced #2136: #2122 corrected the exit-4
# wording in the README and left ``--help`` stale, and a client author reads the
# README (not ``--help``) while implementing #2124's auto-retry branching.
#
# So the README block is GENERATED from the same tuple and pinned by a test in
# the required `Python utility tests (pocketshell)` check. Neither side can be
# edited alone: change the tuple and the committed README no longer matches the
# render; change the README and it no longer matches the tuple. Regenerate with
#
#     tools/pocketshell/scripts/sync-readme-exit-codes.py

#: Fences of the generated block in ``tools/pocketshell/README.md``. Both must
#: appear exactly once, in this order.
README_TABLE_BEGIN = (
    "<!-- BEGIN GENERATED: send exit codes (source: EXIT_CODE_TABLE in pocketshell/send.py) -->"
)
README_TABLE_END = "<!-- END GENERATED: send exit codes -->"


def _escape_markdown_cell(text: str) -> str:
    """Escape what would otherwise end a markdown table cell early."""
    return text.replace("|", "\\|")


def render_readme_exit_code_table(
    table: Sequence[tuple[int, str, str]] = EXIT_CODE_TABLE,
) -> str:
    """Render ``EXIT_CODE_TABLE`` as the README's markdown table.

    The ``Meaning`` column carries the table's description VERBATIM — the same
    sentences ``--help`` prints. A shorter hand-written paraphrase is exactly
    the seam #2136 came through: two wordings, one of them wrong, and nothing
    to say which.
    """
    lines = [
        "| Exit | stdout reason | Meaning |",
        "| ---- | ------------- | ------- |",
    ]
    for code, reason, description in table:
        reasons = " \\| ".join(f"`{part.strip()}`" for part in reason.split("|"))
        lines.append(f"| {code} | {reasons} | {_escape_markdown_cell(description)} |")
    return "\n".join(lines)


def extract_readme_exit_code_table(readme_text: str) -> str:
    """Return the generated block of ``readme_text``, between its two fences.

    Raises ``ValueError`` when the fences are missing, duplicated or inverted —
    a README that lost its fences must fail loudly, not silently stop being
    checked (that would be the drift hole reopened, wearing a green tick).
    """
    begins = readme_text.count(README_TABLE_BEGIN)
    ends = readme_text.count(README_TABLE_END)
    if begins != 1 or ends != 1:
        raise ValueError(
            "README must contain exactly one generated send exit-code block; "
            f"found {begins} begin marker(s) and {ends} end marker(s)"
        )
    start = readme_text.index(README_TABLE_BEGIN) + len(README_TABLE_BEGIN)
    end = readme_text.index(README_TABLE_END)
    if end < start:
        raise ValueError("README send exit-code markers are inverted")
    return readme_text[start:end].strip("\n")


def readme_with_exit_code_table(
    readme_text: str,
    table: Sequence[tuple[int, str, str]] = EXIT_CODE_TABLE,
) -> str:
    """``readme_text`` with its generated block replaced by a fresh render."""
    # Validates the fences before rewriting anything.
    extract_readme_exit_code_table(readme_text)
    start = readme_text.index(README_TABLE_BEGIN) + len(README_TABLE_BEGIN)
    end = readme_text.index(README_TABLE_END)
    return (
        readme_text[:start]
        + "\n\n"
        + render_readme_exit_code_table(table)
        + "\n\n"
        + readme_text[end:]
    )


# --------------------------------------------------------------------------
# Journal
# --------------------------------------------------------------------------

JOURNAL_SCHEMA = 1
STATE_PENDING = "pending"
STATE_DELIVERED = "delivered"

NEW_FILE_MODE = 0o600

#: Retention default for the automatic background prune. Journal records exist
#: only to make a client retry safe; a token whose row the client resolved a
#: month ago can never be retried, so 30 days is generous. Explicit pruning
#: (``--prune-older-than``) may use any duration.
DEFAULT_RETENTION_SECS = 30 * 24 * 60 * 60

#: The auto-prune runs at most this often (tracked by the mtime of the
#: ``.last-prune`` marker), so the common send path never pays for a directory
#: walk. Best-effort: an auto-prune failure never fails a send.
AUTO_PRUNE_INTERVAL_SECS = 6 * 60 * 60

LAST_PRUNE_MARKER = ".last-prune"

#: Tokens are opaque, but a control character in one would be a client bug and
#: would make logs unreadable, and an unbounded token would make an unbounded
#: record. Rejected as bad-usage rather than silently mangled.
MAX_TOKEN_LENGTH = 512

_DURATION_RE = re.compile(r"^\s*(\d+(?:\.\d+)?)\s*([smhdw]?)\s*$", re.IGNORECASE)
_DURATION_UNITS = {"": 1, "s": 1, "m": 60, "h": 3600, "d": 86400, "w": 604800}


class JournalError(RuntimeError):
    """The token journal could not be read or written."""


@dataclass(frozen=True)
class SendPaths:
    """Resolved filesystem location for the token journal."""

    sends_dir: Path

    def record_file(self, token: str) -> Path:
        digest = hashlib.sha256(token.encode("utf-8")).hexdigest()
        return self.sends_dir / f"{digest}.json"

    @property
    def prune_marker(self) -> Path:
        return self.sends_dir / LAST_PRUNE_MARKER


def resolve_paths(
    *,
    home: Optional[Path] = None,
    env: Optional[Mapping[str, str]] = None,
) -> SendPaths:
    """Return the :class:`SendPaths` for the current (or given) environment.

    1. ``$XDG_STATE_HOME/pocketshell/sends`` when ``$XDG_STATE_HOME`` is set.
    2. ``<home>/.local/state/pocketshell/sends``.

    Mirrors :func:`pocketshell.tree.resolve_paths` so all PocketShell durable
    state lives under one XDG-state root.
    """
    env_map = env if env is not None else os.environ
    base_home = home if home is not None else Path(os.path.expanduser("~"))

    xdg_state = env_map.get("XDG_STATE_HOME")
    if xdg_state:
        state_root = Path(os.path.expanduser(xdg_state))
    else:
        state_root = base_home / ".local" / "state"
    return SendPaths(sends_dir=state_root / "pocketshell" / "sends")


def _ensure_dir(path: Path) -> None:
    path.mkdir(parents=True, exist_ok=True)
    try:
        os.chmod(path, 0o700)
    except OSError:
        pass


def _fsync_dir(path: Path) -> None:
    """fsync a directory so a completed ``os.replace`` survives power loss."""
    try:
        fd = os.open(str(path), os.O_RDONLY)
    except OSError:
        return
    try:
        os.fsync(fd)
    except OSError:
        pass
    finally:
        os.close(fd)


def _write_temp(path: Path, payload: bytes) -> Path:
    """Write ``payload`` to a private, fsync'd temp file beside ``path``."""
    _ensure_dir(path.parent)
    tmp = path.with_name(f"{path.name}.{os.getpid()}.{uuid.uuid4().hex[:8]}.tmp")
    fd = os.open(str(tmp), os.O_WRONLY | os.O_CREAT | os.O_EXCL, NEW_FILE_MODE)
    try:
        with os.fdopen(fd, "wb") as handle:
            handle.write(payload)
            handle.flush()
            os.fsync(handle.fileno())
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    return tmp


def write_bytes_atomic(path: Path, payload: bytes) -> None:
    """Publish ``payload`` at ``path`` atomically with mode 0600.

    Temp file in the SAME directory (so ``os.replace`` is a rename inside one
    filesystem and therefore atomic), fsync'd before the rename and the parent
    directory fsync'd after it. A reader — including the next invocation after a
    ``kill -9`` — sees either the old record or the new one, never a truncated
    one. The temp file is removed if anything fails, so a crashed write leaves
    no ``*.tmp`` litter that a later read could mistake for a record.
    """
    tmp = _write_temp(path, payload)
    try:
        os.replace(tmp, path)
    except BaseException:
        try:
            os.unlink(tmp)
        except OSError:
            pass
        raise
    _fsync_dir(path.parent)


def write_record_atomic(path: Path, document: Mapping[str, Any]) -> None:
    """Atomically publish ``document`` as the record at ``path``."""
    write_bytes_atomic(path, _serialise(document))


def _serialise(document: Mapping[str, Any]) -> bytes:
    return json.dumps(document, ensure_ascii=False, sort_keys=True).encode("utf-8")


def claim_record_exclusive(path: Path, document: Mapping[str, Any]) -> bool:
    """Create the record, returning False if another process got there first.

    This is the mutual exclusion that stops two concurrent ``send`` calls
    carrying the SAME token from both reading "absent" and both injecting.
    Exactly-once has to survive a racing caller, not just a sequential retry.

    The document is written to a private temp file FIRST and published with
    ``os.link``, which fails with ``FileExistsError`` when the target already
    exists and is otherwise atomic. That gives both properties at once:
    exclusion (exactly one racer can link onto the name) and completeness (the
    record is fully written and fsync'd before it becomes visible). Creating
    the final path directly with ``O_CREAT|O_EXCL`` would leave a window in
    which a sibling reads a zero-length file and has to classify it as corrupt,
    i.e. an unresolved state manufactured by a healthy race.
    """
    payload = _serialise(document)
    tmp = _write_temp(path, payload)
    try:
        try:
            os.link(tmp, path)
        except FileExistsError:
            return False
    finally:
        try:
            os.unlink(tmp)
        except OSError:
            pass
    _fsync_dir(path.parent)
    return True


def roll_back_claim(path: Path, prior_bytes: Optional[bytes]) -> None:
    """Undo THIS call's pending claim, and only this call's.

    ``prior_bytes`` is ``None`` when the token had no record at entry: this
    call created it, so removing it returns the token to genuinely-absent and
    keeps an ordinary transient tmux error cleanly retryable.

    When a record DID exist — the ``--resend-interrupted`` path, where this
    call overwrote somebody else's "delivery unknown" memory — deleting it
    would erase evidence this call did not create, and the very next PLAIN
    (non-opt-in) call would read ``absent`` and inject a second copy. That
    falsifies the whole at-most-once claim, so the prior document is restored
    byte-for-byte instead (issue #2122, review finding F1).

    Never raises: it runs on an already-failing path, and if the restore itself
    fails this call's own pending record stays behind — which is still an
    unresolved state, i.e. still safe, never "absent".
    """
    if prior_bytes is None:
        try:
            path.unlink()
        except OSError:
            pass
        return
    try:
        write_bytes_atomic(path, prior_bytes)
    except OSError:
        pass


# --------------------------------------------------------------------------
# Owner liveness — telling a DEAD previous attempt (genuinely unknown) apart
# from a LIVE sibling that is merely still running (provably not unknown).
# --------------------------------------------------------------------------


def _boot_id() -> str:
    """The kernel's per-boot id, or "" when it cannot be read.

    A pending record that survived a reboot names a pid from a previous boot,
    which is a different process universe: without this, an unrelated process
    that happens to hold that pid could make a dead attempt look alive.
    """
    try:
        return Path("/proc/sys/kernel/random/boot_id").read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def _process_start_ticks(pid: int) -> Optional[int]:
    """Field 22 of ``/proc/<pid>/stat`` — the process start time in ticks.

    Together with the pid this is a stable process identity: a REUSED pid
    necessarily has a later start time, so a stale record can never be mistaken
    for a running one. ``comm`` (field 2) may itself contain spaces and
    parentheses, so parsing starts after the LAST ``)``.
    """
    try:
        raw = Path(f"/proc/{pid}/stat").read_bytes()
    except OSError:
        return None
    close = raw.rfind(b")")
    if close < 0:
        return None
    fields = raw[close + 2 :].split()
    # The remainder starts at field 3 (state), so field 22 is index 19.
    if len(fields) <= 19:
        return None
    try:
        return int(fields[19])
    except ValueError:
        return None


def owner_identity(pid: Optional[int] = None) -> dict[str, Any]:
    """The identity stamp written into a ``pending`` record."""
    resolved = os.getpid() if pid is None else pid
    return {
        "pid": resolved,
        "start_ticks": _process_start_ticks(resolved),
        "boot_id": _boot_id(),
    }


def owner_is_alive(owner: Any) -> bool:
    """True only when ``owner`` PROVABLY names a process running right now.

    Fails closed. Anything that cannot be proven — a missing or malformed
    stamp, an unreadable ``/proc``, a different boot, a pid whose start time no
    longer matches — reports False, which routes to the conservative "delivery
    is unknown" answer. The dangerous direction is the other one: a false
    "somebody else owns this" would suppress a legitimate resend, so this
    function must never guess in that direction.
    """
    if not isinstance(owner, Mapping):
        return False
    pid = owner.get("pid")
    start_ticks = owner.get("start_ticks")
    boot_id = owner.get("boot_id")
    if not isinstance(pid, int) or isinstance(pid, bool) or pid <= 0:
        return False
    if not isinstance(start_ticks, int) or isinstance(start_ticks, bool):
        return False
    if not isinstance(boot_id, str) or not boot_id:
        return False
    current_boot = _boot_id()
    if not current_boot or current_boot != boot_id:
        return False
    return _process_start_ticks(pid) == start_ticks


@dataclass(frozen=True)
class JournalRecord:
    """A parsed journal record.

    ``state`` is one of :data:`STATE_PENDING`, :data:`STATE_DELIVERED`, the
    sentinel ``"absent"`` (no record) or ``"corrupt"`` (a record exists but
    cannot be trusted).
    """

    state: str
    token: str = ""
    pane: str = ""
    created_at: float = 0.0
    delivered_at: Optional[float] = None
    #: Identity of the process that wrote a ``pending`` record, used to tell a
    #: dead attempt (unknown) from a live sibling (not unknown). ``None`` when
    #: absent or unusable, which :func:`owner_is_alive` treats as "not alive".
    owner: Optional[Mapping[str, Any]] = None

    @property
    def timestamp(self) -> float:
        return self.delivered_at if self.delivered_at is not None else self.created_at


STATE_ABSENT = "absent"
STATE_CORRUPT = "corrupt"


def read_record(path: Path, token: str) -> JournalRecord:
    """Read the journal record at ``path``, never raising on bad content.

    A truncated / non-JSON / wrong-shaped / wrong-token document is reported as
    :data:`STATE_CORRUPT`, not as "absent" — something happened for this token
    and we cannot prove what, so it must be treated as ambiguous rather than as
    permission to inject again. An unreadable file (permissions/IO) raises
    :class:`JournalError`, which is a different failure: we could not consult
    the journal at all.
    """
    try:
        raw = path.read_bytes()
    except FileNotFoundError:
        return JournalRecord(state=STATE_ABSENT)
    except OSError as exc:
        raise JournalError(f"cannot read {path}: {exc}") from exc

    try:
        document = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return JournalRecord(state=STATE_CORRUPT)
    if not isinstance(document, dict):
        return JournalRecord(state=STATE_CORRUPT)

    state = document.get("state")
    recorded_token = document.get("token")
    if state not in (STATE_PENDING, STATE_DELIVERED):
        return JournalRecord(state=STATE_CORRUPT)
    if not isinstance(recorded_token, str) or recorded_token != token:
        return JournalRecord(state=STATE_CORRUPT)

    def _float(key: str) -> Optional[float]:
        value = document.get(key)
        if isinstance(value, (int, float)) and not isinstance(value, bool):
            return float(value)
        return None

    created = _float("created_at")
    if created is None:
        return JournalRecord(state=STATE_CORRUPT)
    delivered = _float("delivered_at")
    if state == STATE_DELIVERED and delivered is None:
        return JournalRecord(state=STATE_CORRUPT)

    pane = document.get("pane")
    owner = document.get("owner")
    return JournalRecord(
        state=state,
        token=recorded_token,
        pane=pane if isinstance(pane, str) else "",
        created_at=created,
        delivered_at=delivered,
        owner=owner if isinstance(owner, Mapping) else None,
    )


def classify_unresolved(path: Path, token: str) -> tuple[JournalRecord, bool]:
    """Read the record and decide whether a LIVE process still owns the token.

    Returns ``(record, owner_alive)``. When the owner looks dead the record is
    read a SECOND time before that verdict is returned, because the owner may
    have finished and promoted the record in the microseconds between the first
    read and the liveness check — without the re-read that race would report a
    completed delivery as an unresolved unknown, which is exactly the spurious
    unknown this command exists to eliminate.
    """
    record = read_record(path, token)
    if record.state != STATE_PENDING:
        return record, False
    if owner_is_alive(record.owner):
        return record, True
    settled = read_record(path, token)
    if settled.state != STATE_PENDING:
        return settled, False
    return settled, owner_is_alive(settled.owner)


def _iso(epoch: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(epoch))


def _record_document(
    *,
    token: str,
    pane: str,
    state: str,
    created_at: float,
    delivered_at: Optional[float],
    payload_bytes: int,
    enter: bool,
    owner: Optional[Mapping[str, Any]] = None,
) -> dict[str, Any]:
    document: dict[str, Any] = {
        "schema": JOURNAL_SCHEMA,
        "token": token,
        "pane": pane,
        "state": state,
        "created_at": created_at,
        "payload_bytes": payload_bytes,
        "enter": enter,
    }
    if delivered_at is not None:
        document["delivered_at"] = delivered_at
    if owner is not None:
        document["owner"] = dict(owner)
    return document


# --------------------------------------------------------------------------
# Pruning
# --------------------------------------------------------------------------


def parse_duration(text: str) -> float:
    """Parse ``30d`` / ``12h`` / ``90m`` / ``3600s`` / ``3600`` into seconds."""
    match = _DURATION_RE.match(text)
    if match is None:
        raise ValueError(f"cannot parse duration: {text!r}")
    value = float(match.group(1))
    unit = match.group(2).lower()
    return value * _DURATION_UNITS[unit]


def prune(
    paths: SendPaths,
    *,
    older_than_secs: float,
    now: Optional[float] = None,
    resolved_only: bool = False,
) -> int:
    """Delete journal records older than ``older_than_secs``. Returns the count.

    A record's age is its ``delivered_at`` (falling back to ``created_at``); a
    record we cannot parse is aged by its file mtime, so corrupt records are
    prunable too rather than immortal.

    ``resolved_only`` restricts the sweep to records whose outcome is settled
    (``delivered``). The AUTOMATIC prune uses it: an aged ``pending`` or
    unreadable record is still "we do not know whether this token was
    injected", and deleting it would silently return the token to ``absent``
    so the next plain call injects a second copy — the at-most-once
    falsification of finding F1, on a timer instead of on a tmux error. The
    explicit ``--prune-older-than`` sweep is a deliberate operator action and
    keeps removing everything past the cutoff.
    """
    moment = time.time() if now is None else now
    cutoff = moment - older_than_secs
    removed = 0
    try:
        entries = sorted(paths.sends_dir.glob("*.json"))
    except OSError:
        return 0
    for entry in entries:
        try:
            document = json.loads(entry.read_bytes().decode("utf-8"))
            resolved = isinstance(document, dict) and document.get("state") == STATE_DELIVERED
            stamp = document.get("delivered_at")
            if not isinstance(stamp, (int, float)) or isinstance(stamp, bool):
                stamp = document.get("created_at")
            if not isinstance(stamp, (int, float)) or isinstance(stamp, bool):
                raise ValueError("no usable timestamp")
            age_stamp = float(stamp)
        except (OSError, UnicodeDecodeError, json.JSONDecodeError, ValueError, AttributeError):
            # Unreadable: still a token something happened for, so it counts as
            # UNRESOLVED for the automatic sweep. Aged by mtime for the
            # explicit one, which must be able to clear it.
            resolved = False
            try:
                age_stamp = entry.stat().st_mtime
            except OSError:
                continue
        if resolved_only and not resolved:
            continue
        if age_stamp < cutoff:
            try:
                entry.unlink()
            except OSError:
                continue
            removed += 1
    return removed


def maybe_auto_prune(paths: SendPaths, *, now: Optional[float] = None) -> bool:
    """Run the default-retention prune at most once per interval. Best-effort.

    Returns True when a prune ran. NEVER raises: the caller has already
    delivered a payload, and a housekeeping failure must not turn a successful
    delivery into an error the client would retry.

    Only RESOLVED (``delivered``) records are swept. Automatic housekeeping may
    not delete an unknown — see :func:`prune`.
    """
    moment = time.time() if now is None else now
    marker = paths.prune_marker
    try:
        last = marker.stat().st_mtime
        if moment - last < AUTO_PRUNE_INTERVAL_SECS:
            return False
    except OSError:
        pass
    try:
        prune(
            paths,
            older_than_secs=DEFAULT_RETENTION_SECS,
            now=moment,
            resolved_only=True,
        )
        _ensure_dir(marker.parent)
        with open(marker, "a", encoding="utf-8"):
            pass
        os.utime(marker, (moment, moment))
    except OSError:
        return False
    return True


# --------------------------------------------------------------------------
# tmux
# --------------------------------------------------------------------------


class TmuxUnavailable(RuntimeError):
    """The tmux binary could not be executed."""


class TmuxTimeout(RuntimeError):
    """A tmux invocation exceeded the caller's deadline."""


@dataclass
class TmuxRunner:
    """Runs tmux commands against one server, under a single total deadline.

    ``socket_name`` maps to tmux ``-L``. It exists so tests can drive a real
    tmux server on an ISOLATED socket; production callers omit it and get the
    host's default server (the one tmuxctl and the app use).
    """

    socket_name: Optional[str] = None
    deadline: Optional[float] = None
    tmux_binary: str = "tmux"

    def _remaining(self) -> Optional[float]:
        if self.deadline is None:
            return None
        left = self.deadline - time.monotonic()
        if left <= 0:
            raise TmuxTimeout("deadline exceeded before the tmux call started")
        return left

    def run(
        self,
        args: Sequence[str],
        *,
        input_bytes: Optional[bytes] = None,
    ) -> subprocess.CompletedProcess:
        argv = [self.tmux_binary]
        if self.socket_name:
            argv += ["-L", self.socket_name]
        argv += list(args)
        try:
            # Fixed argv, never a shell string: the payload and the token
            # never become shell words.
            return subprocess.run(
                argv,
                input=input_bytes if input_bytes is not None else b"",
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                timeout=self._remaining(),
            )
        except FileNotFoundError as exc:
            raise TmuxUnavailable(f"cannot execute {self.tmux_binary}: {exc}") from exc
        except subprocess.TimeoutExpired as exc:
            raise TmuxTimeout(f"tmux {' '.join(args)} timed out") from exc


def _decode(stream: Optional[bytes]) -> str:
    if not stream:
        return ""
    return stream.decode("utf-8", "replace").strip()


@dataclass(frozen=True)
class PaneLookup:
    found: bool
    dead: bool
    detail: str = ""


def lookup_pane(runner: TmuxRunner, pane: str) -> PaneLookup:
    """Report whether ``pane`` exists and is alive on the server.

    Uses ``list-panes -a`` and compares ids ourselves rather than probing with
    ``-t`` and parsing tmux's stderr prose, so the classification does not
    depend on an error message's wording.
    """
    proc = runner.run(["list-panes", "-a", "-F", "#{pane_id} #{pane_dead}"])
    if proc.returncode != 0:
        raise TmuxCommandFailed(_decode(proc.stderr) or "tmux list-panes failed")
    for line in _decode(proc.stdout).splitlines():
        parts = line.split()
        if not parts or parts[0] != pane:
            continue
        dead = len(parts) > 1 and parts[1] == "1"
        return PaneLookup(found=True, dead=dead)
    return PaneLookup(found=False, dead=False, detail=f"no such pane: {pane}")


class TmuxCommandFailed(RuntimeError):
    """A tmux command returned a definitive non-zero status."""


def buffer_name_for(token: str) -> str:
    """A per-token tmux buffer name, alphanumeric only.

    Per-token (not per-pane) so two concurrent sends never truncate each other's
    buffer between fill and commit. Alphanumeric because tmux treats a trailing
    ``;`` in an argv element as a command separator (issue #1845).
    """
    return "pssend" + hashlib.sha256(token.encode("utf-8")).hexdigest()[:16]


# --------------------------------------------------------------------------
# The command
# --------------------------------------------------------------------------


def _epilog() -> str:
    """Render the ``--help`` epilog FROM :data:`EXIT_CODE_TABLE`.

    Built from the table rather than written out by hand so the documented
    codes cannot drift from the constants the code actually exits with (a test
    asserts every row of the table appears in ``--help``). Each block is
    preceded by a ``\\b`` marker, which is click's "do not rewrap the following
    paragraph" escape.
    """
    lines = [
        "\b",
        "EXIT CODES (stable — clients branch on these):",
        "",
    ]
    for code, reason, description in EXIT_CODE_TABLE:
        lines.append("\b")
        lines.append(f"  {code}  {reason}")
        for chunk in _wrap(description, 66):
            lines.append(f"      {chunk}")
        lines.append("")
    lines.extend(
        [
            "\b",
            "STDOUT is machine-readable: the first whitespace-delimited token is",
            "one of the reasons above. Human detail goes to stderr.",
            "",
            "\b",
            "DURABILITY: at-most-once, except on an explicit opt-in. A token is",
            "never injected a second time unless --resend-interrupted is passed",
            "on the injecting call itself. If a previous attempt DIED without an",
            "answer the next call reports 'send-interrupted' (exit 5) instead of",
            "guessing; pass --resend-interrupted to accept a possible duplicate.",
            "If a previous attempt is still RUNNING nothing is unknown and the",
            "call reports 'send-in-progress' (exit 8) — retry to read the answer.",
        ]
    )
    return "\n".join(lines)


def _wrap(text: str, width: int) -> list[str]:
    words = text.split()
    out: list[str] = []
    current = ""
    for word in words:
        candidate = f"{current} {word}".strip()
        if len(candidate) > width and current:
            out.append(current)
            current = word
        else:
            current = candidate
    if current:
        out.append(current)
    return out


def _fail(ctx: click.Context, code: int, reason: str, detail: str) -> None:
    click.echo(reason)
    if detail:
        click.echo(detail, err=True)
    ctx.exit(code)


def _report_in_progress(ctx: click.Context, record: JournalRecord) -> None:
    """Report that a LIVE process owns this token. Deliberately not exit 5.

    Nothing was injected by this call and nothing is unknown — the outcome is
    simply not decided yet, by a process that is demonstrably running. Saying
    "a previous attempt died, delivery is UNKNOWN" here would be false, and a
    client that surfaces it as "we don't know — resend?" would offer the user a
    duplicate for a send that is about to succeed.
    """
    owner = record.owner or {}
    click.echo(REASON_SEND_IN_PROGRESS)
    click.echo(
        f"another send for this token is still running (pid {owner.get('pid')}); "
        "this call injected nothing and that call owns the outcome. Retry "
        "shortly to read it.",
        err=True,
    )
    ctx.exit(EXIT_SEND_IN_PROGRESS)


def _report_unsubmitted_payload(ctx: click.Context, pane: str, detail: str) -> None:
    """Report a payload that reached the pane but was never submitted.

    The ONE outcome for every way the ``send-keys Enter`` can fail after tmux
    accepted the paste. Deliberately not exit 4: the pane is not untouched, the
    claim is deliberately kept, and a client that branches on the table to
    auto-retry (#2124) would read ``tmux-failed`` as "clean slate" and inject
    the payload a second time (#2136).

    The record stays ``pending``, so the next plain call answers this same
    ``send-interrupted`` rather than injecting, and only an explicit
    ``--resend-interrupted`` can deliver again.
    """
    click.echo(REASON_SEND_INTERRUPTED)
    click.echo(
        detail
        or f"payload landed in {pane} but Enter was not delivered; delivery is partial",
        err=True,
    )
    ctx.exit(EXIT_SEND_INTERRUPTED)


def _report_lost_claim(ctx: click.Context, record_path: Path, token: str) -> None:
    """Report the outcome of losing the exclusive claim to a racing sibling.

    Losing the claim is a PROVABLE state: this call had not touched tmux, so it
    injected nothing. The only question is what the winner did, so the winner's
    record is consulted rather than assumed — it may already have delivered, it
    may still be running, or (only if it died) the outcome is genuinely
    unknown.
    """
    try:
        record, owner_alive = classify_unresolved(record_path, token)
    except JournalError as exc:
        _fail(ctx, EXIT_JOURNAL_FAILED, REASON_JOURNAL_FAILED, str(exc))
        return
    if record.state == STATE_DELIVERED:
        click.echo(f"{REASON_ALREADY_DELIVERED} {_iso(record.timestamp)}")
        ctx.exit(EXIT_OK)
    if owner_alive:
        _report_in_progress(ctx, record)
        return
    click.echo(REASON_SEND_INTERRUPTED)
    click.echo(
        f"another send claimed token {token!r} and is no longer running; this "
        "call injected nothing and that call's outcome is unknown. Re-run with "
        "--resend-interrupted to accept a possible duplicate.",
        err=True,
    )
    ctx.exit(EXIT_SEND_INTERRUPTED)


@click.command(
    "send",
    context_settings={"help_option_names": ["-h", "--help"]},
    epilog=_epilog(),
)
@click.option("--pane", "pane", default=None, help="Target tmux pane id, e.g. %3.")
@click.option(
    "--token",
    "token",
    default=None,
    help=(
        "Opaque, caller-supplied delivery id (the client's durable row id). "
        "Re-running with the same token never injects twice."
    ),
)
@click.option("--enter", is_flag=True, help="Press Enter in the pane after the payload lands.")
@click.option(
    "--timeout",
    "timeout",
    type=float,
    default=10.0,
    show_default=True,
    help="Total seconds budgeted for all tmux calls in this invocation.",
)
@click.option(
    "--resend-interrupted",
    is_flag=True,
    help=(
        "Opt in to at-least-once for THIS token: deliver again even though a "
        "previous attempt's outcome is unknown, accepting a possible duplicate."
    ),
)
@click.option(
    "--prune-older-than",
    "prune_older_than",
    default=None,
    metavar="DURATION",
    help=(
        "Housekeeping mode: delete journal records older than DURATION "
        "(30d / 12h / 90m / 3600s) and exit. Mutually exclusive with --pane / "
        "--token. Records are also auto-pruned at the "
        f"{DEFAULT_RETENTION_SECS // 86400}d default retention on delivery."
    ),
)
@click.option(
    "--socket-name",
    "socket_name",
    default=None,
    help="tmux -L socket name. Omit to use the host's default tmux server.",
)
@click.pass_context
def send_command(
    ctx: click.Context,
    pane: Optional[str],
    token: Optional[str],
    enter: bool,
    timeout: float,
    resend_interrupted: bool,
    prune_older_than: Optional[str],
    socket_name: Optional[str],
) -> None:
    """Deliver a payload from stdin into a tmux pane, exactly once per token.

    The exit status IS the acknowledgement: 0 means the tmux server accepted
    the payload for that exact pane (or had already accepted it for this
    token), and every other code says precisely what went wrong. There is no
    screen scraping, no timing window, and no unprovable outcome.

    \b
    Deliver:  pocketshell send --pane %3 --token <row-id> --enter < payload
    Prune:    pocketshell send --prune-older-than 30d
    """
    paths = resolve_paths()

    # ---- housekeeping mode ------------------------------------------------
    if prune_older_than is not None:
        if pane is not None or token is not None:
            _fail(
                ctx,
                EXIT_BAD_USAGE,
                REASON_BAD_USAGE,
                "--prune-older-than cannot be combined with --pane/--token",
            )
        try:
            retention = parse_duration(prune_older_than)
        except ValueError as exc:
            _fail(ctx, EXIT_BAD_USAGE, REASON_BAD_USAGE, str(exc))
            return
        try:
            removed = prune(paths, older_than_secs=retention)
        except OSError as exc:
            _fail(ctx, EXIT_JOURNAL_FAILED, REASON_JOURNAL_FAILED, str(exc))
            return
        click.echo(f"{REASON_PRUNED} {removed}")
        ctx.exit(EXIT_OK)

    # ---- argument validation ---------------------------------------------
    if not pane:
        _fail(ctx, EXIT_BAD_USAGE, REASON_BAD_USAGE, "--pane is required")
        return
    if token is None or not token.strip():
        _fail(ctx, EXIT_BAD_USAGE, REASON_BAD_USAGE, "--token is required and must not be blank")
        return
    if len(token) > MAX_TOKEN_LENGTH:
        _fail(
            ctx,
            EXIT_BAD_USAGE,
            REASON_BAD_USAGE,
            f"--token exceeds {MAX_TOKEN_LENGTH} characters",
        )
        return
    if any(ord(ch) < 0x20 or ord(ch) == 0x7F for ch in token):
        _fail(ctx, EXIT_BAD_USAGE, REASON_BAD_USAGE, "--token must not contain control characters")
        return
    if timeout <= 0:
        _fail(ctx, EXIT_BAD_USAGE, REASON_BAD_USAGE, "--timeout must be positive")
        return

    record_path = paths.record_file(token)

    # ---- read the payload BEFORE any branch that can exit ------------------
    # The caller is piping the payload into us. Exiting without reading it —
    # which the `already-delivered` / `send-interrupted` short-circuits used to
    # do — leaves the writer's next write on a closed pipe, i.e. SIGPIPE (shell
    # status 141): a successful acknowledgement surfacing to the caller as a
    # failed write (issue #2122, review finding F3). Those are the RETRY paths,
    # the ones a client exercises most.
    #
    # Argument validation deliberately stays ABOVE this read: a caller with an
    # open-but-idle stdin (an ssh exec channel it has not closed) must get its
    # `bad-usage` immediately rather than blocking forever on a payload that is
    # never coming. Hanging is strictly worse than a writer signal, and this
    # command must never hang.
    payload = _read_stdin_bytes()

    # ---- consult the durable journal --------------------------------------
    try:
        record, owner_alive = classify_unresolved(record_path, token)
    except JournalError as exc:
        _fail(ctx, EXIT_JOURNAL_FAILED, REASON_JOURNAL_FAILED, str(exc))
        return

    if record.state == STATE_DELIVERED:
        stamp = record.delivered_at if record.delivered_at is not None else record.created_at
        click.echo(f"{REASON_ALREADY_DELIVERED} {_iso(stamp)}")
        ctx.exit(EXIT_OK)

    if owner_alive:
        # PROVABLE, not unknown: another send for this token is still running.
        # This call injected nothing and the outcome belongs to that process,
        # so reporting the dead-attempt code here would manufacture an unknown
        # out of a healthy race — the #2121 defect reproduced one layer down.
        # --resend-interrupted does not override it either: the opt-in exists to
        # resolve an unknown, and forcing a resend alongside a live sibling is
        # simply a duplicate.
        _report_in_progress(ctx, record)
        return

    if record.state in (STATE_PENDING, STATE_CORRUPT) and not resend_interrupted:
        reason = (
            REASON_SEND_INTERRUPTED if record.state == STATE_PENDING else REASON_JOURNAL_CORRUPT
        )
        click.echo(reason)
        click.echo(
            "a previous attempt for this token left an unresolved record "
            f"({record_path}) and its process is gone; delivery is unknown. "
            "Nothing was injected. Re-run with --resend-interrupted to accept "
            "a possible duplicate.",
            err=True,
        )
        ctx.exit(EXIT_SEND_INTERRUPTED)

    # ---- payload shape -----------------------------------------------------
    if not payload and not enter:
        _fail(
            ctx,
            EXIT_BAD_USAGE,
            REASON_BAD_USAGE,
            "empty payload with no --enter would inject nothing",
        )
        return

    runner = TmuxRunner(socket_name=socket_name, deadline=time.monotonic() + timeout)

    # ---- pane check: nothing is journaled or injected if this fails -------
    try:
        lookup = lookup_pane(runner, pane)
    except TmuxTimeout as exc:
        _fail(ctx, EXIT_TIMEOUT, REASON_TIMEOUT, str(exc))
        return
    except TmuxUnavailable as exc:
        _fail(ctx, EXIT_TMUX_FAILED, REASON_TMUX_FAILED, str(exc))
        return
    except TmuxCommandFailed as exc:
        _fail(ctx, EXIT_TMUX_FAILED, REASON_TMUX_FAILED, str(exc))
        return
    if not lookup.found:
        _fail(ctx, EXIT_PANE_NOT_FOUND, REASON_PANE_NOT_FOUND, lookup.detail)
        return
    if lookup.dead:
        _fail(ctx, EXIT_PANE_NOT_FOUND, REASON_PANE_NOT_FOUND, f"pane is dead: {pane}")
        return

    buffer_name = buffer_name_for(token)

    # ---- fill the paste buffer: still touches no pane ---------------------
    if payload:
        try:
            filled = runner.run(["load-buffer", "-b", buffer_name, "-"], input_bytes=payload)
        except TmuxTimeout as exc:
            _fail(ctx, EXIT_TIMEOUT, REASON_TIMEOUT, str(exc))
            return
        except TmuxUnavailable as exc:
            _fail(ctx, EXIT_TMUX_FAILED, REASON_TMUX_FAILED, str(exc))
            return
        if filled.returncode != 0:
            _fail(
                ctx,
                EXIT_TMUX_FAILED,
                REASON_TMUX_FAILED,
                _decode(filled.stderr) or "tmux load-buffer failed",
            )
            return

    # ---- the ambiguity boundary ------------------------------------------
    # Everything above is a clean no-op if this process dies. From here on a
    # death is genuinely ambiguous, so the pending record goes down FIRST,
    # fsync'd, immediately before the one command that can put bytes in the
    # pane. It is rolled back only on a DEFINITIVE tmux failure (we have a
    # return code, so we know nothing reached the pane) — never on a guess.
    created_at = time.time()
    pending_document = _record_document(
        token=token,
        pane=pane,
        state=STATE_PENDING,
        created_at=created_at,
        delivered_at=None,
        payload_bytes=len(payload),
        enter=enter,
        # Stamped so a later call can tell "this attempt DIED" (unknown) from
        # "this attempt is still running" (owned, not unknown).
        owner=owner_identity(),
    )
    # The document this call is about to overwrite, or None when the token had
    # no record and this call is creating one. It is what a rollback restores;
    # see roll_back_claim.
    prior_bytes: Optional[bytes] = None
    try:
        if record.state == STATE_ABSENT:
            # Nobody has claimed this token yet, so the claim must be exclusive:
            # a CONCURRENT send with the same token would otherwise also have
            # read "absent" and injected a second copy.
            if not claim_record_exclusive(record_path, pending_document):
                _report_lost_claim(ctx, record_path, token)
                return
        else:
            # --resend-interrupted over an existing pending/corrupt record.
            # Capture the prior document FIRST: this call is overwriting an
            # unresolved state it did not create, and a rollback has to put it
            # back rather than erase it (#2122 F1). If it cannot be read, fail
            # closed without touching anything — an unrestorable overwrite is
            # exactly how the unknown gets lost.
            try:
                prior_bytes = record_path.read_bytes()
            except OSError as exc:
                _fail(
                    ctx,
                    EXIT_JOURNAL_FAILED,
                    REASON_JOURNAL_FAILED,
                    f"cannot read {record_path} before overwriting it: {exc}",
                )
                return
            write_record_atomic(record_path, pending_document)
    except OSError as exc:
        _fail(ctx, EXIT_JOURNAL_FAILED, REASON_JOURNAL_FAILED, f"cannot write {record_path}: {exc}")
        return

    def _rollback() -> None:
        roll_back_claim(record_path, prior_bytes)

    if payload:
        try:
            pasted = runner.run(
                ["paste-buffer", "-d", "-r", "-b", buffer_name, "-t", pane],
            )
        except TmuxTimeout as exc:
            # No answer => genuinely ambiguous. Keep the pending record so the
            # next call reports 'send-interrupted' instead of re-pasting.
            _fail(ctx, EXIT_TIMEOUT, REASON_TIMEOUT, str(exc))
            return
        except TmuxUnavailable as exc:
            _rollback()
            _fail(ctx, EXIT_TMUX_FAILED, REASON_TMUX_FAILED, str(exc))
            return
        if pasted.returncode != 0:
            # Definitive failure: tmux rejected the paste, so the pane is
            # untouched and the token must stay cleanly retryable.
            _rollback()
            runner_delete_buffer(runner, buffer_name)
            _fail(
                ctx,
                EXIT_TMUX_FAILED,
                REASON_TMUX_FAILED,
                _decode(pasted.stderr) or "tmux paste-buffer failed",
            )
            return

    if enter:
        # PAST THE POINT OF NO RETURN. tmux accepted the paste above, so the
        # payload is in the pane and this call's claim must stand: rolling back
        # would let a plain retry paste it a SECOND time. Every way the Enter
        # can fail therefore reports the unknown, and none reports exit 4
        # (#2136) — exit 4 is reserved for "this call put nothing in the pane",
        # which is the property that makes it safe for a client to auto-retry.
        #
        # The two failure shapes below are physically indistinguishable — tmux
        # answered "no", or tmux stopped being executable between the two
        # commands — and they leave byte-identical state, so they report one
        # outcome. They used to differ (exit 4 vs exit 5) for no reason a
        # caller could act on, and exit 4's row then told #2124 the pane was
        # untouched while the payload sat in it.
        try:
            submitted = runner.run(["send-keys", "-t", pane, "Enter"])
        except TmuxTimeout as exc:
            _fail(ctx, EXIT_TIMEOUT, REASON_TIMEOUT, str(exc))
            return
        except TmuxUnavailable as exc:
            _report_unsubmitted_payload(ctx, pane, str(exc))
            return
        if submitted.returncode != 0:
            _report_unsubmitted_payload(ctx, pane, _decode(submitted.stderr))
            return

    delivered_at = time.time()
    try:
        write_record_atomic(
            record_path,
            _record_document(
                token=token,
                pane=pane,
                state=STATE_DELIVERED,
                created_at=created_at,
                delivered_at=delivered_at,
                payload_bytes=len(payload),
                enter=enter,
            ),
        )
    except OSError as exc:
        # The payload landed. We could not record it, so a retry cannot be
        # proven safe — report the unknown state rather than a clean failure
        # the client would retry into a duplicate.
        click.echo(REASON_SEND_INTERRUPTED)
        click.echo(f"delivered but could not journal {record_path}: {exc}", err=True)
        ctx.exit(EXIT_SEND_INTERRUPTED)

    maybe_auto_prune(paths, now=delivered_at)
    click.echo(f"{REASON_DELIVERED} {_iso(delivered_at)}")
    ctx.exit(EXIT_OK)


def runner_delete_buffer(runner: TmuxRunner, buffer_name: str) -> None:
    """Best-effort cleanup of a paste buffer a failed commit left behind."""
    try:
        runner.run(["delete-buffer", "-b", buffer_name])
    except (TmuxTimeout, TmuxUnavailable):
        pass


def _read_stdin_bytes() -> bytes:
    """Read the whole payload from stdin as raw bytes.

    ``sys.stdin.buffer`` — never ``sys.stdin`` — so newlines, trailing
    whitespace and non-ASCII survive untouched. Absent under some test
    harnesses, hence the guard.

    A TERMINAL is never a payload: there is nothing piped and no EOF until a
    human types one, so reading would hang. Returning empty routes an
    interactive invocation to the documented ``bad-usage`` exit instead.
    """
    stream = getattr(sys, "stdin", None)
    if stream is None:
        return b""
    try:
        if stream.isatty():
            return b""
    except (AttributeError, ValueError, OSError):
        pass
    buffer = getattr(sys.stdin, "buffer", None)
    if buffer is None:
        data = sys.stdin.read()
        return data.encode("utf-8") if isinstance(data, str) else bytes(data)
    return buffer.read()
