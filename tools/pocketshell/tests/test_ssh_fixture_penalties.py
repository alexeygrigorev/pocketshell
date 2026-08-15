"""Static guard: no Docker test fixture may ship a penalising sshd (issue #2150).

THE CLASS THIS EXISTS FOR

OpenSSH >= 9.8 enables ``PerSourcePenalties`` by default, and the alpine fixture
base now ships 10.3p1. The feature blocks a SOURCE ADDRESS once its penalties
pass ``min:15s``. On the public internet that is right; on a test fixture it is
poison, because a fixture has exactly one source: every emulator/host client
reaches a published container port through the single docker gateway, so four
routine harness auth failures penalise *every* lane, including lanes that did
nothing wrong.

The cost is not a flake. A penalised lane presents as connection failures inside
the product under test, indistinguishable from a real regression in SSH
connect/lease/reattach — so it manufactures evidence pointing at a product bug
that does not exist. It burned two full emulator runs in #2111 before diagnosis,
and the container reported ``healthy`` the entire time.

WHY A STATIC GUARD AND NOT ONLY THE HEALTHCHECK

The healthcheck (see ``x-ssh-healthcheck`` in tests/docker/docker-compose.yml)
is the behavioural defence and the stronger one: it observes the real daemon.
But it only speaks when somebody brings a fixture up, which happens in the
batched Docker/emulator jobs, not per push. #2150's whole origin story is that
the fix existed only at RUNTIME — appended to a live container's config, so any
rebuild silently restored the penalty. A silent revert of a committed fixture
config is exactly how this comes back, so it gets a per-push check that needs
neither Docker nor an emulator. This suite runs in the required ``Python utility
tests (pocketshell)`` job.

WHAT IS CHECKED

C1  tests/docker/sshd_config disables PerSourcePenalties.
C2  every fixture Dockerfile that ends up running an sshd either inherits that
    config or is a named, justified, existence-checked exception.
C3  every SSH healthcheck asserts the effective daemon config, so a fixture that
    can penalise cannot report healthy.

C2's exception set is deliberately tiny and its members are re-verified to
exist, so it cannot rot into an allowlist that quietly swallows a new fixture.

WHY C2's FILE SELECTION IS FAIL-CLOSED (issue #2163)

C2's assertion was always correct; the bug it shipped with was that the
assertion was never *applied* to some files. The original detector asked one
question — does the text contain the literal ``openssh-server``? — so
``apk add openssh``, the Alpine METApackage that pulls the daemon in without
naming it, classified as non-sshd, skipped the anchor requirement, and would
have shipped with ``PerSourcePenalties`` ON while the guard reported the tree
fine. That is the same defect one level up from the usual wrong-cost trap, and
it had already bitten once in #2150 (``Dockerfile.tmux`` was not scanned at all
on the first cut). Both were found by MUTATING the guard; neither was found by
reading it.

A longer list of spellings is not the cure, because the next spelling is always
missing from it. So selection is now fail-closed in two layers:

  * ``sshd_signals`` looks for several INDEPENDENT reasons a Dockerfile ends up
    running a daemon — a package that provides sshd (named in an install command
    OR anywhere in the text, so shell-variable indirection still counts), a FROM
    of an in-tree fixture image, a multi-stage copy of the binary, an explicit
    daemon invocation, or host-key generation. Any one is enough.
  * ``test_c2_every_dockerfile_is_classified`` then requires that EVERY
    Dockerfile under tests/docker is either detected as sshd-bearing or named in
    ``NON_SSHD_FIXTURES`` with a reason. A new fixture spelled in a way nobody
    anticipated is therefore RED by default — it cannot silently opt out by
    being unrecognised, which is precisely how #2163 happened.

``NON_SSHD_FIXTURES`` is not a blind allowlist: each entry is re-verified to
carry no sshd signal at all, so an entry that later grows a daemon reddens
instead of swallowing it.
"""

from __future__ import annotations

import re
from pathlib import Path

import pytest

REPO_ROOT = Path(__file__).resolve().parents[3]
DOCKER_DIR = REPO_ROOT / "tests" / "docker"
SHARED_SSHD_CONFIG = DOCKER_DIR / "sshd_config"

# Fixtures that run an sshd but deliberately do NOT copy the shared config.
# Each entry must state why, and the path must still exist (see test_c2_*).
#
# real-agent: Debian bookworm, OpenSSH 9.2 — predates PerSourcePenalties
# entirely, where the directive is not "defaulted off" but an unknown keyword
# ("Bad configuration option") that makes sshd refuse to start. Copying the
# shared alpine config would also point Subsystem sftp at the wrong path. It is
# covered instead by C3: its healthcheck asserts the effective config, so the
# day it is rebased onto an OpenSSH >= 9.8 base it goes UNHEALTHY rather than
# silently penalising every lane.
SHARED_CONFIG_EXEMPT = {
    "tests/docker/real-agent/Dockerfile": "Debian OpenSSH 9.2 predates PerSourcePenalties",
}

# Dockerfiles under tests/docker that run NO sshd, so the anchor requirement
# does not apply to them. This list exists to make C2's file selection
# FAIL-CLOSED (issue #2163): an unrecognised Dockerfile is a guard failure, not
# a silent pass, so a fixture cannot opt out of the anchor by being spelled in a
# way the detector never learned. Every entry is re-verified against the
# detector (test_c2_non_sshd_fixtures_are_genuinely_non_sshd), so this cannot
# rot into an allowlist that swallows a fixture that later grows a daemon.
NON_SSHD_FIXTURES = {
    "tests/docker/Dockerfile.flaky-transcription": (
        "python:3.12-slim stdlib HTTP server for the #688 transcription-retry "
        "journey; serves /v1/audio/transcriptions over plain HTTP, no ssh at all"
    ),
    "tests/docker/packet-loss-proxy/Dockerfile": (
        "alpine + iproute2/socat TCP relay that shapes the link in front of the "
        "`agents` fixture (#346/#1876); it forwards port 22 but never runs a daemon"
    ),
}

# Package names whose installation yields a runnable OpenSSH sshd.
#
#   openssh-server  the explicit spelling (apk, apt, dnf, zypper)
#   openssh         alpine/arch METApackage — depends on openssh-server. This is
#                   the #2163 blind spot. On Fedora/RHEL `openssh` is the shared
#                   client half only, so counting it there over-approximates;
#                   that is the safe direction for a guard.
#   ssh             debian/ubuntu METApackage — depends on openssh-server.
#                   Ambiguous as a bare word (`/etc/ssh/`, `.ssh/`, `ssh-keygen`),
#                   so it counts ONLY inside a package-install command.
SSHD_PACKAGES = ("openssh-server", "openssh", "ssh")

# Names safe to look for anywhere in the file, not just in an install command.
# This is what keeps shell-variable indirection covered — Dockerfile.bootstrap
# builds `packages="openssh-server ..."` and installs `$packages`, so the
# install command itself names no package at all.
UNAMBIGUOUS_SSHD_PACKAGES = ("openssh-server", "openssh")

_DOCKERFILE_COMMENT = re.compile(r"^[ \t]*#.*$", re.MULTILINE)
_LINE_CONTINUATION = re.compile(r"\\[ \t]*\n")

# Package managers, tolerant of flags interleaved before the verb
# (`apk --no-cache add`, `apt-get -qq install`).
_INSTALL_COMMANDS = (
    r"apk\s+(?:-{1,2}\S+\s+)*add",
    r"apt(?:-get)?\s+(?:-{1,2}\S+\s+)*install",
    r"aptitude\s+(?:-{1,2}\S+\s+)*install",
    r"(?:micro)?dnf\s+(?:-{1,2}\S+\s+)*install",
    r"yum\s+(?:-{1,2}\S+\s+)*install",
    r"zypper\s+(?:-{1,2}\S+\s+)*(?:in|install)",
    r"pacman\s+(?:-{1,2}\S+\s+)*-S",
)
_INSTALL_VERB = re.compile(r"\b(?:" + "|".join(_INSTALL_COMMANDS) + r")\b")
_SHELL_SEPARATOR = re.compile(r"&&|\|\||;|\||\n")


def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8")


def _strip_comments(text: str) -> str:
    """Dockerfile comments only. A fixture that *mentions* sshd in prose is not
    an sshd fixture — and the reverse bites too: the #2163 reproduction probe
    first reddened for the wrong reason because its own explanatory comment
    contained the literal `openssh-server` that the old substring detector
    matched. A mutation that fires off a comment is a mutation that never
    happened."""
    return _DOCKERFILE_COMMENT.sub("", text)


def _join_continuations(text: str) -> str:
    return _LINE_CONTINUATION.sub(" ", text)


def _install_targets(text: str) -> list[str]:
    """Package-ish tokens passed to a package manager's install verb."""
    targets: list[str] = []
    for match in _INSTALL_VERB.finditer(text):
        rest = text[match.end() :]
        command = _SHELL_SEPARATOR.split(rest, maxsplit=1)[0]
        for raw in command.split():
            token = raw.strip("\"'`\\")
            if not token or token.startswith("-") or token.startswith("$"):
                continue
            targets.append(token)
    return targets


def _names_package(text: str, package: str) -> bool:
    """`openssh` must NOT match inside `openssh-client`/`openssh-server`."""
    return re.search(rf"(?<![\w.\-]){re.escape(package)}(?![\w.\-])", text) is not None


def sshd_signals(dockerfile_text: str) -> list[str]:
    """Independent reasons this Dockerfile ends up running an sshd.

    Empty list == no reason found. Several signals overlap on purpose: the
    #2163 class is a MISSED file, so redundancy is the feature. Any single
    signal is sufficient, which means a new install spelling only has to trip
    one of them.
    """
    body = _join_continuations(_strip_comments(dockerfile_text))
    signals: list[str] = []

    installed = _install_targets(body)
    for package in SSHD_PACKAGES:
        if package in installed:
            signals.append(f"installs the `{package}` package, which provides sshd")

    for package in UNAMBIGUOUS_SSHD_PACKAGES:
        if _names_package(body, package) and not any(
            s.startswith(f"installs the `{package}`") for s in signals
        ):
            # Not inside a recognised install command — e.g. built into a shell
            # variable first (Dockerfile.bootstrap) or passed via an ARG.
            signals.append(f"names the `{package}` package outside an install command")

    if re.search(r"^FROM\s+pocketshell-test:", body, re.MULTILINE):
        signals.append("FROM an in-tree pocketshell-test fixture image")

    if re.search(r"^COPY\s+--from=\S+\s+[^\n]*\bsshd\b", body, re.MULTILINE):
        signals.append("multi-stage COPY --from of an sshd binary")

    if re.search(r"/usr/sbin/sshd|\bsshd\s+-[DdEe]", body):
        signals.append("invokes the sshd daemon")

    if re.search(r"\bssh-keygen\s+(?:-\S+\s+)*-A\b", body):
        signals.append("generates HOST keys (`ssh-keygen -A`), which only a server needs")

    return signals


def _is_sshd_fixture(dockerfile_text: str) -> bool:
    return bool(sshd_signals(dockerfile_text))


def _inherits_shared_config(dockerfile_text: str) -> bool:
    """Copies tests/docker/sshd_config, or FROMs an image that already did
    (e.g. Dockerfile.tmux extends pocketshell-test:ssh)."""
    copies_shared = re.search(r"^COPY\s+\S*sshd_config\s", dockerfile_text, re.MULTILINE)
    extends_fixture = re.search(r"^FROM\s+pocketshell-test:", dockerfile_text, re.MULTILINE)
    return copies_shared is not None or extends_fixture is not None


def _rel(path: Path) -> str:
    """Repo-relative id. Two fixtures are named plain `Dockerfile`, so a
    basename id would render them as `Dockerfile0`/`Dockerfile1` and a failure
    would not name the fixture it is about."""
    return path.relative_to(DOCKER_DIR).as_posix()


def _all_dockerfiles(root: Path) -> list[Path]:
    return [p for p in sorted(root.rglob("Dockerfile*")) if p.is_file()]


def _sshd_fixture_dockerfiles(root: Path = DOCKER_DIR) -> list[Path]:
    """Every Dockerfile under `root` that ends up running an sshd."""
    return [p for p in _all_dockerfiles(root) if _is_sshd_fixture(_read(p))]


def _compose_files() -> list[Path]:
    return sorted(
        p
        for p in DOCKER_DIR.rglob("*compose*.yml")
        if p.is_file()
    )


# --------------------------------------------------------------------------
# C1 — the committed shared config disables the penalty.
# --------------------------------------------------------------------------


def _effective_directives(config_text: str, name: str) -> list[str]:
    """Values of `name` in an sshd_config, ignoring comments. Lowercased."""
    values = []
    for raw in config_text.splitlines():
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split(None, 1)
        if parts[0].lower() == name.lower():
            values.append(parts[1].strip().lower() if len(parts) > 1 else "")
    return values


def test_c1_shared_sshd_config_disables_per_source_penalties() -> None:
    assert SHARED_SSHD_CONFIG.is_file(), f"missing {SHARED_SSHD_CONFIG}"

    values = _effective_directives(_read(SHARED_SSHD_CONFIG), "PerSourcePenalties")

    assert values, (
        "tests/docker/sshd_config does not set PerSourcePenalties (issue #2150).\n"
        "OpenSSH >= 9.8 enables it by DEFAULT, so omitting the directive means "
        "penalties are ON. Every emulator lane NATs through one docker gateway "
        "address, so a penalty earned by any lane refuses all of them while the "
        "container still reports healthy.\n"
        "Remedy: add `PerSourcePenalties no` to tests/docker/sshd_config."
    )
    assert values == ["no"], (
        "tests/docker/sshd_config sets PerSourcePenalties to "
        f"{values!r}; a single-source test fixture must disable it entirely "
        "(issue #2150). Rate-limiting by source is meaningless when every lane "
        "shares one source address."
    )


# --------------------------------------------------------------------------
# C2 — every sshd fixture inherits that config, or is a justified exception.
# --------------------------------------------------------------------------


def test_c2_sshd_fixtures_exist_to_check() -> None:
    """Guard the guard: a scan that finds nothing must not read as a pass."""
    dockerfiles = _sshd_fixture_dockerfiles()
    assert len(dockerfiles) >= 7, (
        f"only found {len(dockerfiles)} sshd fixture Dockerfiles under "
        f"{DOCKER_DIR}; the scan is broken, not the tree."
    )


@pytest.mark.parametrize("dockerfile", _sshd_fixture_dockerfiles(), ids=_rel)
def test_c2_sshd_fixture_inherits_shared_config(dockerfile: Path) -> None:
    rel = dockerfile.relative_to(REPO_ROOT).as_posix()
    text = _read(dockerfile)

    if rel in SHARED_CONFIG_EXEMPT:
        assert not _inherits_shared_config(text), (
            f"{rel} is listed in SHARED_CONFIG_EXEMPT but now copies the shared "
            "sshd_config. Drop it from the exemption list."
        )
        return

    assert _inherits_shared_config(text), (
        f"{rel} runs an sshd ({'; '.join(sshd_signals(text))}) but neither copies "
        "tests/docker/sshd_config nor extends a pocketshell-test image that does "
        "(issue #2150).\n"
        "OpenSSH >= 9.8 turns PerSourcePenalties ON by default, so a fixture "
        "without that config penalises the shared docker-gateway source and "
        "refuses every journey lane — while still reporting healthy.\n"
        "Remedy: `COPY sshd_config /etc/ssh/sshd_config`, or add the fixture to "
        "SHARED_CONFIG_EXEMPT with a written reason (and keep its healthcheck's "
        "penalty assertion, which is what actually covers the exempt case)."
    )


def test_c2_exemptions_still_exist() -> None:
    """An exemption naming a deleted file is dead text that hides new fixtures."""
    for rel in SHARED_CONFIG_EXEMPT:
        assert (REPO_ROOT / rel).is_file(), (
            f"SHARED_CONFIG_EXEMPT names {rel}, which no longer exists. "
            "Remove the stale entry so the exemption list cannot rot."
        )


# --------------------------------------------------------------------------
# C2 file selection — fail-closed, so an unrecognised spelling cannot opt out
# of the anchor requirement by going undetected (issue #2163).
# --------------------------------------------------------------------------


@pytest.mark.parametrize("dockerfile", _all_dockerfiles(DOCKER_DIR), ids=_rel)
def test_c2_every_dockerfile_is_classified(dockerfile: Path) -> None:
    """The #2163 class-killer: no Dockerfile may be silently unclassified.

    The original detector asked only "does the text contain `openssh-server`?",
    so a fixture spelled `apk add openssh` was not merely mis-judged — it was
    never *considered*, and the guard reported the tree fine. Requiring an
    explicit verdict for every file means the failure mode of an unanticipated
    spelling is RED, not a silent skip.
    """
    rel = dockerfile.relative_to(REPO_ROOT).as_posix()
    if _is_sshd_fixture(_read(dockerfile)):
        return

    assert rel in NON_SSHD_FIXTURES, (
        f"{rel} is not detected as running an sshd and is not listed in "
        "NON_SSHD_FIXTURES, so no C2 anchor check applies to it (issue #2163).\n"
        "An unclassified Dockerfile is a HOLE: if it does run a daemon, it ships "
        "with PerSourcePenalties ON and this guard still reports the tree fine — "
        "which is exactly how the `apk add openssh` metapackage spelling slipped "
        "past #2150's detector.\n"
        "Remedy: if it runs an sshd, `COPY sshd_config /etc/ssh/sshd_config` and "
        "extend `sshd_signals` so the daemon is DETECTED rather than assumed. If "
        "it genuinely runs no sshd, add it to NON_SSHD_FIXTURES with a reason."
    )


@pytest.mark.parametrize("rel", sorted(NON_SSHD_FIXTURES))
def test_c2_non_sshd_fixtures_are_genuinely_non_sshd(rel: str) -> None:
    """NON_SSHD_FIXTURES must not become a blind allowlist.

    This is also the SELECTIVITY assertion for `sshd_signals`: an over-broad
    detector that flagged every Dockerfile would redden here, so "does not
    false-positive on a genuinely non-sshd image" is machine-checked rather
    than asserted by hand.
    """
    path = REPO_ROOT / rel
    assert path.is_file(), (
        f"NON_SSHD_FIXTURES names {rel}, which no longer exists. Remove the "
        "stale entry so the list cannot rot."
    )

    text = _read(path)
    signals = sshd_signals(text)
    assert not signals, (
        f"{rel} is listed in NON_SSHD_FIXTURES as running no sshd, but it now "
        f"looks like it does ({'; '.join(signals)}). Either it grew a daemon — in "
        "which case drop the entry and give it the shared sshd_config — or "
        "`sshd_signals` has become over-broad and is about to demand the anchor "
        "from images that do not need it."
    )

    body = _strip_comments(text)
    for token in ("sshd", "openssh"):
        assert token not in body, (
            f"{rel} is listed in NON_SSHD_FIXTURES but its instructions mention "
            f"`{token}`. A non-sshd fixture should not need to; re-classify it "
            "rather than leaving the anchor check switched off."
        )


# --------------------------------------------------------------------------
# C2 detector — the #2163 reproduction plus the sibling spellings.
# --------------------------------------------------------------------------

_ALPINE_METAPACKAGE_FIXTURE = """\
FROM alpine:latest

RUN apk add --no-cache openssh tmux \\
    && ssh-keygen -A \\
    && adduser -D -s /bin/sh testuser

EXPOSE 22

CMD ["/usr/sbin/sshd", "-D", "-e"]
"""

# Spellings that all yield a running sshd. #2163 named the first; the rest are
# the same blind spot wearing different clothes, so they are covered rather
# than waited for.
SSHD_SPELLINGS = {
    # -- the reported defect -------------------------------------------------
    "apk_metapackage": "FROM alpine:latest\nRUN apk add --no-cache openssh\n",
    "apk_metapackage_no_flags": "FROM alpine:latest\nRUN apk add openssh\n",
    "apk_flag_before_verb": "FROM alpine:latest\nRUN apk --no-cache add openssh\n",
    "apk_metapackage_not_first": (
        "FROM alpine:latest\nRUN apk add --no-cache tmux bash openssh procps\n"
    ),
    # -- explicit server package, the spelling #2150 already handled ----------
    "apk_server": "FROM alpine:latest\nRUN apk add --no-cache openssh-server openssh-client\n",
    # -- apt variants and flag orderings -------------------------------------
    "apt_get_server": "FROM debian:bookworm-slim\nRUN apt-get install -y openssh-server\n",
    "apt_get_server_not_first": (
        "FROM debian:bookworm-slim\n"
        "RUN apt-get install -y --no-install-recommends git openssh-server tmux\n"
    ),
    "apt_get_flag_before_verb": (
        "FROM debian:bookworm-slim\nRUN apt-get -qq install openssh-server\n"
    ),
    "apt_get_continuation": (
        "FROM debian:bookworm-slim\n"
        "RUN apt-get update \\\n"
        "    && apt-get install -y --no-install-recommends \\\n"
        "        bash \\\n"
        "        openssh-server \\\n"
        "        tmux \\\n"
        "    && rm -rf /var/lib/apt/lists/*\n"
    ),
    "apt_debian_metapackage": "FROM debian:bookworm-slim\nRUN apt install -y ssh\n",
    "apt_debian_metapackage_chained": (
        "FROM ubuntu:24.04\n"
        "RUN apt-get update && apt-get install -y ssh && rm -rf /var/lib/apt/lists/*\n"
    ),
    # -- other package managers ----------------------------------------------
    "dnf_server": "FROM fedora:41\nRUN dnf install -y openssh-server\n",
    "zypper_metapackage": (
        "FROM opensuse/leap\nRUN zypper --non-interactive install openssh\n"
    ),
    # -- shell-variable indirection (the Dockerfile.bootstrap shape) ----------
    "shell_variable_server": (
        "FROM alpine:latest\n"
        'RUN packages="openssh-server tmux" \\\n'
        "    && apk add --no-cache $packages\n"
    ),
    "shell_variable_metapackage": (
        "FROM alpine:latest\n"
        'RUN packages="openssh tmux" \\\n'
        "    && apk add --no-cache $packages\n"
    ),
    # -- inherited / copied daemons ------------------------------------------
    "from_intree_fixture": "FROM pocketshell-test:ssh\nRUN apk add --no-cache tmux\n",
    "multi_stage_copy": (
        "FROM alpine:latest AS builder\nRUN apk add --no-cache build-base\n\n"
        "FROM alpine:latest\n"
        "COPY --from=builder /usr/sbin/sshd /usr/sbin/sshd\n"
    ),
    # -- daemon present without any recognisable install line ----------------
    "runs_daemon_only": 'FROM some/base:latest\nCMD ["/usr/sbin/sshd", "-D", "-e"]\n',
    "generates_host_keys_only": "FROM some/base:latest\nRUN ssh-keygen -A\n",
}

# Images that run NO sshd. An over-broad detector would demand the penalty
# anchor from these and block unrelated work, so selectivity is asserted, not
# assumed.
NON_SSHD_SPELLINGS = {
    "client_only": "FROM alpine:latest\nRUN apk add --no-cache openssh-client\n",
    "packet_loss_proxy_shape": "FROM alpine:3.20\nRUN apk add --no-cache iproute2 socat\n",
    "python_http_shape": (
        "FROM python:3.12-slim\nCOPY server.py /usr/local/bin/server.py\n"
    ),
    "client_dotssh_paths": (
        "FROM alpine:latest\n"
        "RUN apk add --no-cache openssh-client\n"
        "COPY known_hosts /home/testuser/.ssh/known_hosts\n"
    ),
    # These two pin the reason `ssh` counts ONLY inside an install command.
    # Without them, widening `ssh` to a context-free signal kills no test — the
    # rule would be documented but unconstrained, which is the very shape this
    # issue exists to remove.
    "client_runs_the_ssh_command": (
        "FROM alpine:latest\n"
        "RUN apk add --no-cache openssh-client\n"
        'CMD ["ssh", "-o", "BatchMode=yes", "testuser@agents", "true"]\n'
    ),
    "client_etc_ssh_config_path": (
        "FROM alpine:latest\n"
        "RUN apk add --no-cache openssh-client\n"
        "COPY ssh_config /etc/ssh/ssh_config\n"
    ),
    "prose_only": (
        "# This image talks to an sshd elsewhere; it installs openssh-server\n"
        "# nowhere and runs no daemon of its own.\n"
        "FROM alpine:3.20\nRUN apk add --no-cache socat\n"
    ),
    "unrelated_package_with_ssh_in_name": (
        "FROM node:24-bookworm-slim\nRUN npm install -g some-ssh-helper\n"
    ),
}


def test_c2_detector_recognises_the_alpine_metapackage() -> None:
    """The exact #2163 report: `apk add openssh` pulls in the daemon without
    naming it, and used to classify as non-sshd."""
    assert sshd_signals(_ALPINE_METAPACKAGE_FIXTURE), (
        "`apk add openssh` was not recognised as sshd-bearing. This is the "
        "issue #2163 blind spot: the Alpine metapackage installs openssh-server "
        "transitively, so a fixture spelled this way skips the C2 anchor check "
        "and ships with PerSourcePenalties ON."
    )


@pytest.mark.parametrize("dockerfile_text", SSHD_SPELLINGS.values(), ids=SSHD_SPELLINGS)
def test_c2_detector_recognises_every_sshd_spelling(dockerfile_text: str) -> None:
    assert sshd_signals(dockerfile_text), (
        "this Dockerfile ends up running an sshd but no signal fired, so C2 "
        "would never require the PerSourcePenalties anchor from it "
        f"(issue #2163):\n{dockerfile_text}"
    )


@pytest.mark.parametrize(
    "dockerfile_text", NON_SSHD_SPELLINGS.values(), ids=NON_SSHD_SPELLINGS
)
def test_c2_detector_ignores_non_sshd_images(dockerfile_text: str) -> None:
    signals = sshd_signals(dockerfile_text)
    assert not signals, (
        f"a non-sshd image was flagged as sshd-bearing ({'; '.join(signals)}). "
        "An over-broad detector demands the shared sshd_config from images that "
        "cannot use it, which blocks unrelated fixture work:\n"
        f"{dockerfile_text}"
    )


def test_c2_metapackage_fixture_without_anchor_is_selected_and_flagged(
    tmp_path: Path,
) -> None:
    """End-to-end over the guard's own decision, on a synthetic fixture tree.

    This is the reproduction that was GREEN before the fix. It drives both
    halves that failed: file SELECTION (the fixture must be picked up by the
    scan at all) and the anchor REQUIREMENT (once picked up, it must fail).
    Asserting only the second half would pass on the broken guard too, because
    the broken guard never reached it.
    """
    fixture = tmp_path / "Dockerfile.metapackage"
    fixture.write_text(_ALPINE_METAPACKAGE_FIXTURE, encoding="utf-8")

    selected = _sshd_fixture_dockerfiles(tmp_path)
    assert selected == [fixture], (
        "the metapackage fixture was not selected for the C2 anchor check; "
        f"scan returned {[p.name for p in selected]} (issue #2163)"
    )
    assert not _inherits_shared_config(_read(fixture)), (
        "the synthetic fixture is supposed to OMIT the shared sshd_config; if "
        "it inherits one, this reproduction no longer reproduces anything."
    )


def test_c2_anchored_metapackage_fixture_passes(tmp_path: Path) -> None:
    """The other direction: the same spelling WITH the anchor is fine. Without
    this, a detector that flagged everything would look correct."""
    fixture = tmp_path / "Dockerfile.metapackage"
    fixture.write_text(
        _ALPINE_METAPACKAGE_FIXTURE.replace(
            "EXPOSE 22", "COPY sshd_config /etc/ssh/sshd_config\n\nEXPOSE 22"
        ),
        encoding="utf-8",
    )

    assert _sshd_fixture_dockerfiles(tmp_path) == [fixture]
    assert _inherits_shared_config(_read(fixture))


def test_c2_comment_only_mention_does_not_arm_the_detector() -> None:
    """A signal that fires off PROSE is a mutation that never happened.

    The #2163 reproduction probe first reddened for the wrong reason: its own
    explanatory comment contained the literal `openssh-server`, which the old
    substring detector matched. The probe therefore appeared to reproduce the
    bug while proving nothing about the metapackage spelling at all.
    """
    prose = (
        "# Reproduction note: the Alpine metapackage pulls in openssh-server\n"
        "# without naming it, and this image runs sshd nowhere.\n"
        "FROM alpine:3.20\nRUN apk add --no-cache socat\n"
    )
    assert not sshd_signals(prose)


# --------------------------------------------------------------------------
# C3 — the healthcheck can observe the penalising state.
# --------------------------------------------------------------------------


def _ssh_healthchecks(compose_text: str) -> list[str]:
    """Each CMD-SHELL healthcheck body that runs the fixture SSH probe.

    Compose folds `>-` blocks onto one line, so match on the flattened text of
    each `test:` list entry.
    """
    flattened = re.sub(r"\s+", " ", compose_text)
    return [
        body
        for body in re.findall(r"- CMD-SHELL - (.+?) interval:", flattened)
        if "testuser@localhost" in body
    ]


def test_c3_ssh_healthchecks_exist_to_check() -> None:
    total = sum(len(_ssh_healthchecks(_read(p))) for p in _compose_files())
    assert total >= 2, (
        f"found only {total} SSH healthchecks across {[p.name for p in _compose_files()]}; "
        "the parser is broken, not the tree."
    )


@pytest.mark.parametrize("compose", _compose_files(), ids=lambda p: p.name)
def test_c3_ssh_healthcheck_asserts_effective_penalty_config(compose: Path) -> None:
    rel = compose.relative_to(REPO_ROOT).as_posix()
    bodies = _ssh_healthchecks(_read(compose))

    if not bodies:
        pytest.skip(f"{rel} declares no SSH healthcheck")

    for body in bodies:
        assert "sshd -T" in body and "persourcepenalties" in body.lower(), (
            f"an SSH healthcheck in {rel} probes connectivity but never checks "
            "whether sshd may penalise (issue #2150).\n"
            "The probe runs from inside the container, so its source is ::1 — a "
            "DIFFERENT source from the docker gateway every client under test "
            "arrives on. It therefore stays green while sshd refuses 100% of the "
            "traffic under test. That blind spot is what made #2150 cost two "
            "emulator runs instead of one obvious red.\n"
            "Remedy: keep the `sshd -T` / persourcepenalties assertion from the "
            "`x-ssh-healthcheck` anchor in tests/docker/docker-compose.yml."
        )
