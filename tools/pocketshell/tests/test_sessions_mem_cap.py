"""Every session PocketShell creates carries a memory cap (issue #2562).

The tmux arm has been capped since #726: `tmuxctl create-detached` wraps the
session shell in a cgroup-v2 systemd `--user` scope and resolves the ceiling
from the repo's committed `cgroups.toml` (PocketShell's is 30G). The aplexer
arm passed **no** memory parameter at all, so every aplexer-backed session ran
uncapped — visible on the dev box as `"limits": {}` on every live record. This
file pins the fix on both arms:

* the resolved value (bytes), not merely the presence of a flag;
* `--mem` overriding it identically on both backends;
* a cap that cannot be resolved failing LOUDLY instead of starting an uncapped
  session (silence is the bug this issue is about);
* and, against a real isolated aplexer, the KERNEL as the oracle — the created
  session's own cgroup must carry the expected `memory.max`.

The stubbed arms reuse the seams `test_sessions_create_routing` already owns,
so nothing here touches a real `a`, tmuxctl or tmux server. The one real
transport test drives the production CLI (`python -m pocketshell`) against a
throwaway aplexer instance (`APLEXER_CONFIG` / `APLEXER_STATE_DIR` /
`APLEXER_RUNTIME_DIR`), never the maintainer's live sessions.
"""

from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import tomllib
from pathlib import Path

import pytest
from click.testing import CliRunner

from pocketshell import memcap
from pocketshell.sessions import sessions_group

# The REAL tmuxctl (a hard dependency of this CLI today, `pyproject.toml`) is
# imported so the "same source of truth" claim is checked against the arm it
# has to agree with, not asserted. Delete those comparisons together with the
# tmux arm in #2561; the byte-value assertions against the committed
# `cgroups.toml` are the ones that must survive it.
from tmuxctl import robust as tmuxctl_robust
from tests.test_sessions_create_routing import (  # shared process seams
    AplexerStub,
    TmuxStub,
    TmuxctlStub,
    envelope,
    use_aplexer_backend,
    use_tmux_backend,
)

REPO_ROOT = Path(__file__).resolve().parents[3]
PROJECT_CGROUPS_TOML = REPO_ROOT / "cgroups.toml"

#: The cap `cgroups.toml` currently commits, in bytes. Derived independently of
#: the code under test (see `_independent_bytes`) so a bug in our parser cannot
#: make the expectation agree with itself, and asserted as a LITERAL below so
#: "the cap silently became something else" is a red test, not a quiet pass.
REPO_CAP_BYTES = 30 * 1024**3  # 30G

#: The documented fallback for a workspace whose project has no cap of its own
#: — deliberately the same value tmuxctl falls back to (`robust.DEFAULT_MEM`),
#: so both arms agree. Never "no cap".
FALLBACK_CAP_BYTES = 12 * 1024**3  # 12G


def _independent_bytes(raw: str) -> int:
    """Parse `30G` -> bytes WITHOUT the production parser (anti-tautology)."""
    units = {"K": 1024, "M": 1024**2, "G": 1024**3, "T": 1024**4}
    text = raw.strip()
    assert text[-1].upper() in units, f"unhandled unit in {raw!r}"
    return int(text[:-1]) * units[text[-1].upper()]


def repo_cap_bytes() -> int:
    """The repo's committed per-project cap, read from `cgroups.toml`."""
    raw = tomllib.loads(PROJECT_CGROUPS_TOML.read_text(encoding="utf-8"))["mem"]
    value = _independent_bytes(str(raw))
    assert value == REPO_CAP_BYTES, (
        f"cgroups.toml now commits {raw!r} ({value} bytes), not 30G. If that "
        "change is deliberate, update REPO_CAP_BYTES here in the same commit."
    )
    return value


def write_cgroups_toml(directory: Path, body: str) -> Path:
    directory.mkdir(parents=True, exist_ok=True)
    path = directory / "cgroups.toml"
    path.write_text(body, encoding="utf-8")
    return path


def start_argv(start: AplexerStub) -> list[str]:
    assert len(start.calls) == 1, f"expected exactly one `a start`, got {start.calls}"
    return start.calls[0]


def memory_arg(start: AplexerStub) -> str:
    argv = start_argv(start)
    assert "--memory" in argv, f"`a start` ran with no --memory: {argv}"
    return argv[argv.index("--memory") + 1]


def create(*args: str):
    return CliRunner().invoke(sessions_group, ["create", *args])


# ----- the resolved value, on the aplexer arm -------------------------------


def test_aplexer_arm_passes_the_repo_project_cap(
    monkeypatch: pytest.MonkeyPatch,
) -> None:
    """A session in THIS repo is capped at cgroups.toml's 30G, in bytes."""
    start = AplexerStub(record={"id": "abc", "workspace": str(REPO_ROOT), "tag": "work"})
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = create("work", "--cwd", str(REPO_ROOT), "--backend", "aplexer", "--json")

    assert result.exit_code == 0, result.output
    assert memory_arg(start) == str(repo_cap_bytes())


def test_aplexer_arm_reads_the_workspace_cgroups_toml(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    workspace = tmp_path / "proj"
    write_cgroups_toml(workspace, 'mem = "1G"\n')
    start = AplexerStub(record={"id": "abc", "workspace": str(workspace), "tag": "work"})
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = create("work", "--cwd", str(workspace), "--backend", "aplexer", "--json")

    assert result.exit_code == 0, result.output
    assert memory_arg(start) == str(1024**3)


def test_aplexer_arm_reads_the_git_root_cgroups_toml_for_a_subdirectory(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """A session started in a subdir inherits the repo's cap, like tmuxctl."""
    root = tmp_path / "repo"
    (root / "sub" / "deep").mkdir(parents=True)
    write_cgroups_toml(root, 'mem = "2G"\n')
    subprocess.run(["git", "init", "-q", str(root)], check=True)
    start = AplexerStub(record={"id": "abc", "workspace": str(root), "tag": "work"})
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = create(
        "work", "--cwd", str(root / "sub" / "deep"), "--backend", "aplexer", "--json"
    )

    assert result.exit_code == 0, result.output
    assert memory_arg(start) == str(2 * 1024**3)


def test_workspace_without_a_project_cap_gets_the_documented_fallback(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """No cgroups.toml anywhere is still CAPPED, never uncapped."""
    workspace = tmp_path / "loose"
    workspace.mkdir()
    start = AplexerStub(record={"id": "abc", "workspace": str(workspace), "tag": "w"})
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = create("w", "--cwd", str(workspace), "--backend", "aplexer", "--json")

    assert result.exit_code == 0, result.output
    assert memory_arg(start) == str(FALLBACK_CAP_BYTES)


# ----- `--mem` honoured identically on both arms ----------------------------


def test_explicit_mem_overrides_the_project_cap_on_the_aplexer_arm(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    workspace = tmp_path / "proj"
    write_cgroups_toml(workspace, 'mem = "1G"\n')
    start = AplexerStub(record={"id": "abc", "workspace": str(workspace), "tag": "work"})
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = create(
        "work", "--cwd", str(workspace), "--mem", "4G", "--backend", "aplexer", "--json"
    )

    assert result.exit_code == 0, result.output
    assert memory_arg(start) == str(4 * 1024**3)


def test_explicit_mem_is_forwarded_on_the_tmux_arm(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """The tmux arm keeps handing `--mem` to tmuxctl, unchanged."""
    tmuxctl = TmuxctlStub()
    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=TmuxStub())

    result = create(
        "work", "--cwd", str(tmp_path), "--mem", "4G", "--backend", "tmux", "--json"
    )

    assert result.exit_code == 0, result.output
    assert ["--mem", "4G"] == tmuxctl.calls[0][-2:]


# ----- an unresolvable cap fails LOUDLY -------------------------------------


def test_unparseable_project_cap_starts_nothing(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    workspace = tmp_path / "proj"
    write_cgroups_toml(workspace, 'mem = "banana"\n')
    start = AplexerStub(record={"id": "abc", "workspace": str(workspace), "tag": "work"})
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = create("work", "--cwd", str(workspace), "--backend", "aplexer", "--json")

    assert result.exit_code != 0
    assert start.calls == [], "an unresolvable cap must not create a session"
    assert "banana" in envelope(result)["error"]


def test_malformed_project_cgroups_toml_starts_nothing(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    workspace = tmp_path / "proj"
    write_cgroups_toml(workspace, "mem = \n")
    start = AplexerStub(record={"id": "abc", "workspace": str(workspace), "tag": "work"})
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = create("work", "--cwd", str(workspace), "--backend", "aplexer", "--json")

    assert result.exit_code != 0
    assert start.calls == []
    assert "cgroups.toml" in envelope(result)["error"]


def test_unparseable_mem_flag_starts_nothing(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    workspace = tmp_path / "proj"
    workspace.mkdir()
    start = AplexerStub(record={"id": "abc", "workspace": str(workspace), "tag": "work"})
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = create(
        "w", "--cwd", str(workspace), "--mem", "lots", "--backend", "aplexer", "--json"
    )

    assert result.exit_code != 0
    assert start.calls == []


def test_a_project_file_cannot_ask_for_an_uncapped_session(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """`none` is an explicit-flag-only escape hatch, never a committed policy."""
    workspace = tmp_path / "proj"
    write_cgroups_toml(workspace, 'mem = "none"\n')
    start = AplexerStub(record={"id": "abc", "workspace": str(workspace), "tag": "work"})
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = create("work", "--cwd", str(workspace), "--backend", "aplexer", "--json")

    assert result.exit_code != 0
    assert start.calls == []


def test_explicit_mem_none_is_the_only_uncapped_path(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    """Hosts that cannot enforce a cap (containers) opt out AT THE CALL SITE."""
    workspace = tmp_path / "proj"
    write_cgroups_toml(workspace, 'mem = "1G"\n')
    start = AplexerStub(record={"id": "abc", "workspace": str(workspace), "tag": "work"})
    use_aplexer_backend(monkeypatch, start=start, snapshot=[])

    result = create(
        "work", "--cwd", str(workspace), "--mem", "none", "--backend", "aplexer", "--json"
    )

    assert result.exit_code == 0, result.output
    assert "--memory" not in start_argv(start)


def test_mem_none_is_rejected_on_the_tmux_arm(
    monkeypatch: pytest.MonkeyPatch, tmp_path: Path
) -> None:
    tmuxctl = TmuxctlStub()
    use_tmux_backend(monkeypatch, tmuxctl=tmuxctl, tmux=TmuxStub())

    result = create(
        "work", "--cwd", str(tmp_path), "--mem", "none", "--backend", "tmux", "--json"
    )

    assert result.exit_code != 0
    assert tmuxctl.calls == []


# ----- real transport: the kernel is the oracle -----------------------------


REAL_CAP = "512M"
REAL_CAP_BYTES = 512 * 1024**2


def _real_runtime_dir() -> str:
    """The user's REAL XDG_RUNTIME_DIR — `systemd-run --user` needs its bus."""
    return f"/run/user/{os.getuid()}"


def _skip_unless_user_scopes_work() -> None:
    """Skip only when this host cannot delegate a memory-capped user scope.

    Narrow on purpose: the failure this file exists to catch (no `--memory`
    passed at all) does NOT hide behind this skip — it shows up as an empty
    `limits` on a session that was created just fine.
    """
    runtime = _real_runtime_dir()
    if not os.path.isdir(runtime):
        pytest.skip(f"no user runtime dir at {runtime}; systemd --user is unavailable")
    if shutil.which("systemd-run") is None:
        pytest.skip("systemd-run is not installed")
    probe = subprocess.run(
        [
            "systemd-run", "--user", "--scope", "--collect", "-p", "Delegate=yes",
            "-p", "MemoryMax=64M", "--", "/bin/true",
        ],
        capture_output=True,
        text=True,
        env={"PATH": "/usr/bin:/bin", "XDG_RUNTIME_DIR": runtime, "HOME": str(Path.home())},
        timeout=60,
    )
    if probe.returncode != 0:
        pytest.skip(
            "this host cannot create a memory-capped user scope "
            f"(systemd-run exited {probe.returncode}: {probe.stderr.strip()})"
        )


def _short_runtime_root() -> str:
    """A throwaway APLEXER_RUNTIME_DIR short enough for AF_UNIX sun_path."""
    suffix = len("/sessions/") + 36 + len("/control.sock")
    for base in (tempfile.gettempdir(), "/dev/shm"):
        if not os.path.isdir(base):
            continue
        root = tempfile.mkdtemp(prefix="ps2562-", dir=base)
        if len(root) + suffix < 108:
            return root
        os.rmdir(root)
    raise AssertionError("no temp base short enough for an AF_UNIX control socket")


@pytest.mark.skipif(sys.platform != "linux", reason="aplexer ships Linux wheels only")
def test_real_aplexer_session_cgroup_carries_the_resolved_cap(tmp_path: Path) -> None:
    """End to end: cgroups.toml -> `sessions create` -> the kernel's memory.max.

    Drives the production CLI against an ISOLATED aplexer instance (its own
    config/state/runtime), so the maintainer's live sessions are never read,
    capped or killed. The session is torn down and its scope's disappearance
    is asserted.
    """
    _skip_unless_user_scopes_work()

    workspace = tmp_path / "ws"
    write_cgroups_toml(workspace, f'mem = "{REAL_CAP}"\n')
    state_dir = tmp_path / "aplexer-state"
    home = tmp_path / "home"
    for directory in (state_dir, home):
        directory.mkdir()
    config = tmp_path / "aplexer.toml"
    config.write_text("version = 1\n", encoding="utf-8")
    runtime_root = _short_runtime_root()
    tag = "ps2562-cap"
    env = {
        "PATH": "/usr/bin:/bin",
        "HOME": str(home),
        "TERM": "dumb",
        "XDG_RUNTIME_DIR": _real_runtime_dir(),
        "XDG_CONFIG_HOME": str(tmp_path / "config"),
        "APLEXER_CONFIG": str(config),
        "APLEXER_STATE_DIR": str(state_dir),
        "APLEXER_RUNTIME_DIR": runtime_root,
        "POCKETSHELL_APLEXER": "1",
    }

    def run_cli(*args: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [sys.executable, "-m", "pocketshell", *args],
            capture_output=True,
            text=True,
            env=env,
            cwd=str(tmp_path),
            timeout=120,
        )

    created = run_cli(
        "sessions", "create", tag, "--cwd", str(workspace), "--backend", "aplexer", "--json"
    )
    record: dict = {}
    try:
        assert created.returncode == 0, (
            f"`sessions create --backend aplexer` exited {created.returncode}: "
            f"{created.stderr or created.stdout}"
        )
        records = sorted((state_dir / "sessions").glob("*/session.json"))
        assert len(records) == 1, f"expected one session record, got {records}"
        record = json.loads(records[0].read_text(encoding="utf-8"))

        assert record["limits"] == {"memory_bytes": REAL_CAP_BYTES}, (
            "the created aplexer session records NO memory cap: "
            f"limits={record['limits']!r}"
        )
        cgroup = record.get("containment_cgroup")
        assert cgroup, "the session has no containment cgroup, so nothing caps it"
        memory_max = Path(cgroup, "memory.max").read_text(encoding="utf-8").strip()
        assert memory_max == str(REAL_CAP_BYTES), (
            f"kernel says memory.max={memory_max} for {cgroup}, "
            f"expected {REAL_CAP_BYTES}"
        )
    finally:
        # Tear the session down whatever the assertions did, then PROVE the
        # teardown: a test that leaks a scope onto the maintainer's box is a
        # bug of its own.
        killed = None
        if record.get("id"):
            killed = run_cli("sessions", "kill", str(record["id"]), "--json")
        shutil.rmtree(runtime_root, ignore_errors=True)
    assert killed is not None and killed.returncode == 0, (
        f"cleanup kill failed: {killed.stderr if killed else 'no record to kill'}"
    )
    leftover = record.get("containment_cgroup")
    assert leftover and not Path(leftover).exists(), (
        f"the test's own cgroup {leftover} outlived the session"
    )


# ----- the resolver itself, and its agreement with the tmux arm -------------
#
# "Don't hardcode a second copy of the limit" (#2562) is only true if both arms
# read the same file the same way, so these compare against the real tmuxctl.

SIZE_TABLE = ("30G", "512M", "1.5G", "12G", "2GiB", "1T", "8gb")


@pytest.mark.parametrize("raw", SIZE_TABLE)
def test_size_parsing_agrees_with_tmuxctl(raw: str) -> None:
    assert memcap.parse_size(raw, source="test") == tmuxctl_robust.parse_size(raw)


def test_the_fallback_cap_is_the_same_one_tmuxctl_falls_back_to() -> None:
    """A capless workspace must resolve identically on both backends."""
    assert memcap.DEFAULT_MEM == tmuxctl_robust.DEFAULT_MEM
    assert FALLBACK_CAP_BYTES == tmuxctl_robust.parse_size(tmuxctl_robust.DEFAULT_MEM)


def test_project_lookup_agrees_with_tmuxctl_on_this_repo() -> None:
    found = memcap.read_project_mem(REPO_ROOT)
    assert found is not None
    raw, path = found
    assert raw == tmuxctl_robust.read_project_mem(cwd=REPO_ROOT)
    assert path == PROJECT_CGROUPS_TOML


def test_resolving_this_repo_yields_the_committed_cap() -> None:
    resolved = memcap.resolve_session_mem_bytes(flag=None, workspace=str(REPO_ROOT))
    assert resolved == repo_cap_bytes() == 32212254720  # 30G


def test_pyproject_tool_tmuxctl_is_a_cap_source(tmp_path: Path) -> None:
    """Python projects declare the cap in pyproject.toml; tmuxctl reads it too."""
    (tmp_path / "pyproject.toml").write_text(
        '[tool.tmuxctl]\nmem = "3G"\n', encoding="utf-8"
    )
    assert memcap.resolve_session_mem_bytes(flag=None, workspace=str(tmp_path)) == (
        3 * 1024**3
    )
    assert tmuxctl_robust.read_project_mem(cwd=tmp_path) == "3G"


def test_a_dedicated_cgroups_toml_wins_over_pyproject(tmp_path: Path) -> None:
    write_cgroups_toml(tmp_path, 'mem = "5G"\n')
    (tmp_path / "pyproject.toml").write_text(
        '[tool.tmuxctl]\nmem = "3G"\n', encoding="utf-8"
    )
    assert memcap.resolve_session_mem_bytes(flag=None, workspace=str(tmp_path)) == (
        5 * 1024**3
    )


def test_a_malformed_pyproject_still_leaves_the_session_capped(tmp_path: Path) -> None:
    """pyproject.toml is not primarily a cap file, so a broken one is skipped.

    The session is still capped by the fallback — the outcome that must never
    happen is "no cap", not "the fallback cap".
    """
    (tmp_path / "pyproject.toml").write_text("[tool.tmuxctl\n", encoding="utf-8")
    assert memcap.resolve_session_mem_bytes(flag=None, workspace=str(tmp_path)) == (
        FALLBACK_CAP_BYTES
    )


def test_a_bare_number_below_the_floor_is_a_typo_not_a_policy() -> None:
    """`mem = "30"` (meaning 30G) must not silently become 30 BYTES."""
    with pytest.raises(memcap.MemCapError) as excinfo:
        memcap.parse_size("30", source="cgroups.toml")
    assert "floor" in str(excinfo.value)


def test_only_an_explicit_flag_can_ask_for_no_cap(tmp_path: Path) -> None:
    assert memcap.resolve_session_mem_bytes(flag="none", workspace=str(tmp_path)) is None
    assert memcap.resolve_session_mem_bytes(flag="NONE", workspace=str(tmp_path)) is None
    write_cgroups_toml(tmp_path, 'mem = "none"\n')
    with pytest.raises(memcap.MemCapError):
        memcap.resolve_session_mem_bytes(flag=None, workspace=str(tmp_path))
