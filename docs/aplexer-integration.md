# Aplexer integration

PocketShell becomes a client of [aplexer](https://github.com/alexeygrigorev/aplexer)
(`~/git/aplexer`): the host-side agent multiplexer that owns session identity
(`workspace + tag + engine + profile`), launch policy, and (later) the PTY.

This is the PocketShell-side plan. Aplexer's own notes live in
`aplexer/docs/pocketshell-integration-plan.md` (written against an older
commit — treat this file as authoritative for *what PocketShell will do*,
and the aplexer CLI/`--json` shapes as authoritative for *what exists
today*).

**Current slice: #2561 is implemented.** The shipped PocketShell product has
one session runtime: aplexer. The host CLI and Android client use the schema-3
session contract, and a missing bundled aplexer is a visible command failure.

```text
Phase 0   aplexer registry surface          DONE (in aplexer)
Phase A   engines / profiles / launch       DONE (historical)
Phase B   host-side PTY attach              DONE (superseded by the hard cut)
Phase C   dual-runtime transition           RETIRED by #2561
Phase D   aplexer-only PocketShell product  DONE (#2561)
```

The integration seam remains the **host CLI** (its own repo since #2643:
[PocketShell-io/pocketshell-cli](https://github.com/PocketShell-io/pocketshell-cli),
PyPI name `pocketshell` unchanged): the phone
runs `pocketshell sessions list|create|attach|kill`, while `a` owns the remote
session lifecycle. There is no second session manager to merge or select. The
old planning references below are historical and are not implementation
instructions.

---

## Shipping: aplexer is part of the pocketshell CLI (#2543)

aplexer is **not installed separately**. The CLI's `pyproject.toml`
carries a pinned, Linux-marked hard dependency:

```toml
"aplexer==0.1.4; sys_platform == 'linux'"
```

so the documented host install — `uv tool install pocketshell` — also delivers
the `a` CLI **and** the `aplexer` worker binary as console-scripts in the same
`bin` directory as the pocketshell interpreter. There is no separate build, no
copy step, and no PATH surgery. The published wheels bundle both native
binaries (x86_64 + aarch64); the `sys_platform == 'linux'` marker is
load-bearing because aplexer publishes no sdist and no macOS/Windows wheels,
so an unmarked hard dependency would make `pip install pocketshell`
unresolvable off Linux.

**glibc floor (flagged, not solved).** aplexer 0.1.1 shipped `manylinux_2_17`
wheels; 0.1.2 ships `manylinux_2_28`, raising the minimum glibc from 2.17 to
2.28. Now that aplexer is a *hard* dependency, that floor applies to
`pip install pocketshell` / `uv tool install pocketshell` as a whole, not just
to the aplexer backend: a Linux host older than glibc 2.28 (CentOS 7,
Ubuntu 18.04, Debian 9) can no longer install the CLI at all. Combined with
the existing wheel-only, glibc-only surface, the install matrix is now
"glibc >= 2.28, x86_64 or aarch64" — musl/Alpine and 32-bit Linux were already
out (PEP 508 has no libc marker to express that). Every current PocketShell
host is well past 2.28 (the maintainer's box is 2.39), so nothing is broken
today; widening the matrix would mean an aplexer-side manylinux target change
or making the dependency optional again, both out of scope for #2543.

### Binary resolution (`pocketshell/aplexer.py::resolve_a`)

Exactly two candidates, highest first:

1. `APLEXER_BIN` — the single explicit override. A debug/test knob (the unit
   suite points it at stub `a` scripts), not an install path.
2. The **bundled** copy next to `sys.executable` — the interpreter's own `bin`
   dir first, then the resolved dir, mirroring
   `usage.py::_resolve_quse_binary` for the pinned `quse`. Console-scripts sit
   next to the *unresolved* `sys.executable`, because a venv / `uv tool`
   `bin/python` is a symlink into a shared interpreter dir that holds no
   console-scripts.

There is **no `PATH` lookup**. `shutil.which("a")` was deleted (D22 hard cut,
not put behind a condition): a host-level or locally-built `a` must not shadow
the pinned copy, and "a binary that only exists on `PATH`" is exactly the
separate-install mode this change removes. An unresolvable `a` is a
packaging-integrity error that fails loud — `sessions.py::_create_on_aplexer`
names every candidate it tried and points at
`uv tool install --force pocketshell`, instead of the old message that claimed
aplexer "is not installed on this host" while it was in fact installed and
merely off the app's non-interactive SSH `PATH`.

Every call site uses the RESOLVED path. `sessions attach` and `sessions kill`
used to `execvp` / `subprocess.run` a bare `"a"` — a PATH lookup by another
name, and a way to run a different copy than the availability check just
approved; they now exec `resolution.path` (#2543).

The worker matters as much as the CLI: `aplexer/src/lib.rs::worker_executable`
resolves the `aplexer` worker as a sibling of `current_exe`, so a hand-copied
`a` with no sibling worker finds the CLI and still cannot start a session. The
wheel always ships both into the same dir, and `AplexerResolution.worker`
reports the sibling that was found.

### Version coupling, and why the version string is not the contract

The pin is enforced by the exact `==` specifier, the CLI repo's committed
`uv.lock`, and its `tests/test_aplexer_contract.py`
(PocketShell-io/pocketshell-cli).
It deliberately does **not** get a release-time guard:
`scripts/check-pypi-version.sh` couples pocketshell's own package version to
the release tag and says nothing about the host session runtime either.

The version string alone is **not** sufficient, and #2543 is the proof. The
first attempt pinned `aplexer==0.1.1`, the newest published wheel at the time.
aplexer's `main` was 143 commits past the `v0.1.1` tag and *still* reported
`a --version` as `0.1.1`, so the published wheel and the build every host
actually ran were indistinguishable by version. Under the hard cut above the
bundled wheel is the ONLY `a` the CLI can run, so that pin would have silently
downgraded every host past two fixes the real create-agent path depends on:

| aplexer commit | What 0.1.1 does instead |
| --- | --- |
| `ee4b957` — profile `executable` override (argv[0]) | Accepts the key, ignores it. `[profiles.zcodex] engine="codex", executable="zcodex"` resolves argv[0] to `codex`, so `sessions create --engine codex --profile zcodex` dies with `a: command is not executable or was not found in PATH: codex`. |
| `d13ecb2` — preserve provider env for `shell` launches | Unsets 71 provider vars for every plain shell session. |

Hence `test_aplexer_contract.py`: it drives the **bundled** binary (never a
host copy, never PATH) against its own throwaway `APLEXER_CONFIG` fixture — so
it is not hostage to the maintainer's personal profiles — and asserts the
BEHAVIOUR the CLI depends on:

- a profile `executable` override reaches argv[0], in `a --json launch-spec`
  **and** in a real `a start` whose fixture engine command deliberately does
  not exist (the reported symptom, reproduced);
- `a --json profiles` exposes the `executable` field;
- `launch-spec --engine shell` strips nothing, while an agent engine still
  strips the provider keys;
- every subcommand + flag this CLI passes (`start --workspace/--tag/--engine/
  --profile`, `snapshot`, `list`, `engines`, `profiles`,
  `launch-spec --engine/--cwd/--profile/--no-skip-permissions`,
  `attach`, `kill`) exists in the bundled build.

- a session directory with **no `session.json`** — the state `a start` leaves
  on disk for 26-43 ms on every create — is SKIPPED, not treated as a corrupt
  registry, by `a list` / `a snapshot`, by `session_enum._probe_aplexer` and
  `sessions._aplexer_snapshot` driving the real binary, and by a concurrent
  `a start` (see "0.1.3" below).

- a real session whose workload shell spawns a process named `claude`
  reports `agent: "claude"` on `a --json list` **and** `a --json snapshot`,
  and reports `null` again once that process exits (see "0.1.4" below).

Those assertions were verified to fail against published 0.1.1 and pass
against 0.1.2 (the `executable` / shell-env pair), to fail against 0.1.2
and pass against 0.1.3 (the registry-scan trio), and to fail against 0.1.3 and
pass against 0.1.4 (the `agent` field). **Re-run that file whenever
the pin moves** — it is what a bump has to re-verify, in place of a version
check that cannot see the difference.

### 0.1.3 — a session being created must not blank the session tree

aplexer#2 / aplexer#3. Through 0.1.2, `list_records` treated a session
directory containing no `session.json` as a corrupt registry and failed the
whole scan:

```text
a: load session registry entry <dir>: read <dir>/session.json:
No such file or directory (os error 2)          exit=1
```

`start_session` creates that directory 26-43 ms *before* it writes the record,
on every `a start`, so this is the normal create path, not an exotic state.
Both writes happen under the registry lock, so no other `a start` could
observe the gap — but every reader that does NOT take that lock can, and that
is every reader PocketShell has. `a watch` polls forever and died on it (1 run
in 60 on an idle box); `a list` was bricked outright for the duration.

It reached the phone directly. `session_enum.py::_probe_aplexer` and
`sessions.py::_aplexer_snapshot` both probe `a --json snapshot` and fall back
to `a --json list`; that fallback cannot help, because the failure is in the
shared registry scan underneath both. `aplexer.run_json` then collapses the
failure to `None`, so while any session was being created the session tree
lost **every** aplexer row.

A record-less directory that outlives the window — a killed or crashed
`a start` — is worse: it bricks a 0.1.2 registry permanently, including
`a start` itself (the same scan runs under the lock), so
`sessions create` stops working until someone manually
`rmdir`s the directory.

Pinned behaviour, in `test_aplexer_contract.py`: with that exact on-disk shape
present, `a --json list` succeeds, `_probe_aplexer` returns a list with no
error, `_aplexer_snapshot` is not `None`, a live session stays listed, and a
second `a start` still succeeds. Verified red on published 0.1.2, green on
0.1.3.

### 0.1.4 — the session tree can say WHICH agent is running

Issue #2581 (slice 2 of #2579). Through 0.1.3 the only agent-ish field on a
snapshot row was `engine`, and `engine` cannot answer the question. Every
session PocketShell creates is `engine: "shell"` with the agent started by
hand inside it, so `engine` is `null` on exactly the rows a user would call
"my claude session" — a tree of claude/codex/opencode sessions was
indistinguishable from a tree of bare shells.

0.1.4 (aplexer `ca56fa5`, spec.md §18, `src/agent_kind.rs`) adds a derived
`agent` field: `claude`, `codex`, `opencode`, `grok`, or `null`. Three
properties matter to this CLI:

- **Derived at query time, never persisted.** aplexer walks the workload's
  descendant process tree (`workload_pid` plus `/proc/<pid>/task/*/children`,
  the same walk containment uses) on every `a list --json` / `a snapshot` /
  `a status --json`, and classifies each process's `comm`/`cmdline` by
  whole-word command token — the same rule as
  `cgroup_agents.py`/`AgentDetector.namesAgent`, so `node /…/bin/codex` names
  codex while `codex-helper` in an unrelated path does not. Because nothing is
  written to disk, the value cannot go stale; it goes back to `null` the
  moment the agent exits.
- **The key is always present**, so a consumer reads it unconditionally.
- **A terminal-phase record is never probed** — its `workload_pid` names a
  dead process and a recycled pid must not resurrect an agent.

Host side, `session_enum._aplexer_rows` reads it onto `LiveSession.agent` and
schema 3 emits it on every row; every row is an aplexer record and therefore
may carry a workload pid for host-side agent detection. An older `a` simply omits the key, which reads as `null`, never
a `KeyError` — the pin is a floor, not a promise about the binary a given host
happens to be running.

Pinned behaviour, in `test_aplexer_contract.py`: a real session started on the
`shell` engine (the production shape) reports `agent: null` while idle,
`"claude"` while a live process named `claude` runs in its descendant tree,
and `null` again once that process is killed — asserted on the raw `a` output
**and** on the schema-2 payload `session_enum._probe_aplexer` produces from
it. Verified red on published 0.1.3 (no `agent` key at all), green on 0.1.4.

The Docker `agents` fixture needs no separate bump: `tests/docker/
Dockerfile.agents` derives the aplexer release it downloads from this same
`aplexer==X.Y.Z` pin, which `tests/test_agents_fixture_aplexer.py` enforces.
The consequence is an ordering constraint, not an edit — the pin may only move
after the matching GitHub release assets (`a-linux-{amd64,arm64}`,
`aplexer-linux-{amd64,arm64}`) are published, or every image rebuild 404s.

### Lock cutoff

`[tool.uv] exclude-newer` in the CLI repo's `pyproject.toml` (mirrored in
`uv.lock`'s `[options]`) is a project-local reproducibility cutoff, and a
package uploaded *after* it is simply invisible to `uv lock` — which looks
like a broken index rather than a stale cutoff. So it moves in lock-step with
every new pin: past quse 0.0.15 (#2293), then past aplexer 0.1.2's
2026-09-05T22:38:08Z upload (#2543), then past aplexer 0.1.3's
2026-09-06T00:32:09Z upload (its `aplexer-client` runtime dependency landed at
00:32:04Z), now past aplexer 0.1.4's 2026-09-06T16:14:52Z upload
(`aplexer-client` at 16:14:48Z) for #2581. Bump both places together, or `uv lock --check` fails.

Two host-level traps when re-locking: this box carries a rolling global
`exclude-newer = "7 days"` in `~/.config/uv/uv.toml`, so a local `uv lock` /
`uv pip install` needs an explicit `--exclude-newer <cutoff>` to see a
just-published pin; and PyPI's JSON API can list a release minutes before the
simple index serves it, so a resolution failure claiming the version does not
exist is worth one `--refresh` retry before concluding anything is wrong.
After the bump, read the lock diff: moving the cutoff forward is exactly when
an *unrelated* dependency can drift in unnoticed, so the diff should be the
new pin and nothing else.

---

## Current readiness

| Surface | Status | Contract |
| --- | --- | --- |
| Host session lifecycle | Shipped | `sessions list/create/attach/kill` resolve the bundled `a` only. |
| Android wire contract | Shipped | Schema 3 has no backend discriminator or create backend option. |
| Room storage | Shipped | Schema 21 migrates old hosts and drops the obsolete runtime flag. |
| Docker and journeys | Shipped | The glibc agents image runs real pinned `a`/`aplexer` binaries and lifecycle checks. |
| Missing aplexer | Fail loud | List, create, attach, and kill surface an error with a nonzero exit. |

The old dual-runtime design below is retained only as historical context for the
integration sequence. It is not an implementation option or compatibility
promise.

## Phase 0 — aplexer prerequisites (aplexer repo)

Done. Do not block PocketShell A1 on further aplexer work.

| Item | Status |
| --- | --- |
| `opencode` built-in engine | done |
| Forced provider-key `env_unset` union | done (~71 names, non-optional) |
| `a launch-spec --json` / `a launch-exec` | done |
| `a engines --json` `{name, command, available, env_unset}` | done |
| `engines.yaml` / `profiles.yaml` → TOML mapping | `aplexer/docs/pocketshell-config-migration.md` |

Aplexer polish that helps A3 but must not gate A1:

- Builtin Codex argv should include `-c check_for_update_on_startup=false`
  (PocketShell already does; aplexer's builtin is still bare `codex`).
- Claude workspace-trust seeding (`hasTrustDialogAccepted`) still lives only
  in `agents.py::seed_claude_trust`. Spec says it belongs in aplexer; until
  it moves, the A3 shim keeps the Python seeder.

---

## Historical transition phases

The original Phase A/B/C plan explored running two session runtimes while
aplexer attach quality was being developed. #2561 closed that experiment with
a hard cut: those branches, socket enumeration rules, backend flags, and
migration-by-restart language no longer describe the product. Live records from
the retired runtime are intentionally not migrated; users recreate them in
the aplexer session registry.

## Session memory caps

Every session PocketShell creates resolves its memory cap in the CLI's
`memcap.py` (PocketShell-io/pocketshell-cli). The order is: explicit `--mem`,
project `cgroups.toml`, project `pyproject.toml` `[tool.pocketshell]` data,
the repository root equivalents, then the 12 GiB default. Malformed or unsafe
values fail closed; the explicit `--mem none` flag is the fixture escape hatch
when the host cannot delegate a user cgroup.

The `agents` Docker fixture uses `--mem none` because an unprivileged container
cannot prove a delegated cgroup limit. Host-capable validation reads the
resulting `memory.max`; the static and Docker guards still prove that the
production create path passes the resolved value to `a start`.

## Product defaults (unless the maintainer says otherwise)

1. **Picker ids.** Phase A keeps PocketShell display names (`Claude (Z.AI)`).
   Aplexer's `zlaude` / `zodex` stay an internal mapping.
2. **A3 mechanism.** `launch-spec` + shim extras, not `launch-exec`.
3. **Gemini / shell.** Do not show `shell`. `gemini` only with explicit UI work.
4. **heru profiles.** Out of scope. Do not generate `~/.config/heru/profiles.toml`.

---

## Integration boundary

Aplexer is the local session multiplexer shipped with the PocketShell helper.
PocketShell owns the Android wire contract, host command quoting, schema
validation, memory policy, and user-facing failure messages. The client does
not carry a runtime discriminator or probe a second session implementation.
