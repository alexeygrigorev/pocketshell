# Aplexer integration

PocketShell becomes a client of [aplexer](https://github.com/alexeygrigorev/aplexer)
(`~/git/aplexer`): the host-side agent multiplexer that owns session identity
(`workspace + tag + engine + profile`), launch policy, and (later) the PTY.

This is the PocketShell-side plan. Aplexer's own notes live in
`aplexer/docs/pocketshell-integration-plan.md` (written against an older
commit — treat this file as authoritative for *what PocketShell will do*,
and the aplexer CLI/`--json` shapes as authoritative for *what exists
today*).

**Current slice: Phase A / A1 — shadow-mode profile listing.** (#2341)

```text
Phase 0   aplexer registry surface          DONE (in aplexer)
Phase A   engines / profiles / launch       IN PROGRESS
  A1      shadow-mode `a profiles --json`   ← YOU ARE HERE  (#2341)
  A2      engine listing via `a engines`
  A3      launch via `a launch-spec`
  A4      Electron presentation follow-through
Phase B   aplexer PTY hosts selected sessions   NOT READY
Phase C   aplexer default; tmux demoted         blocked on B
```

The integration seam is the **host CLI** (`tools/pocketshell/`), not the
Android app and not pocketshell-electron. Both clients already drive
engines / profiles / launch through remote `pocketshell …` calls. Electron
inherits Phase A the same day the helper does.

---

## Readiness (fair assessment, 2026-08-26)

| Phase | Ready? | Meaning |
| --- | --- | --- |
| **A** — aplexer owns engines/profiles/launch; tmux still hosts terminals | **Yes — start now** | Host-CLI swap. Zero Kotlin/Electron attach changes. |
| **B** — aplexer PTY hosts selected sessions; both backends live | **Not shippable** | Runtime exists, but reattach, agent-state badges, and identity mapping are not. |
| **C** — aplexer default; tmux demoted | Blocked on B | Plus leftover helper features (`jobs`, cards, usage) aplexer will not own. |

Verified against aplexer HEAD (`87a4b89`): `a profiles --json`,
`a engines --json`, `a launch-spec` / `a launch-exec`, forced
`env_unset`, `opencode`, `a watch --jsonl`, and `a transcript` all exist.
There is still no `aplexer` reference in this repo's production code
(until A1).

---

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

## Phase A — aplexer owns engines/profiles/launch; tmux still hosts terminals

**Goal:** one authoritative registry. The phone still types
`pocketshell agent <kind> …` into a tmux pane. Attach, `-CC`, session
names, `@ps_*` options stay.

### A1 — shadow-mode profile listing  ← current

Issue: #2341.

In `profiles.py`, probe `a profiles --json` when `a` is on PATH (or
`$APLEXER_BIN`). Map entries onto `Profile` objects. Log divergence
against native discovery. **Return native.** Kill switch:
`POCKETSHELL_APLEXER_PROFILES=0`.

Adapter (aplexer shape → PocketShell shape):

| aplexer `a profiles --json` | PocketShell `Profile` / `profiles list --json` |
| --- | --- |
| object keyed by dir stem (`zlaude`) | `name` via alias overlay (`Claude (Z.AI)`) |
| `engine` | `engine` (skip if not in `PROFILE_ENGINES`) |
| `env.CLAUDE_CONFIG_DIR` / `env.CODEX_HOME` | `config_dir` |
| no default-dir entries | synthesize `Claude` / `Codex` with `config_dir=None`, `default=True` |
| full `env` map | **omit** — listings never emit env |

Phone, daemon TTL cache, and Electron
`PocketshellClient.listProfiles()` inherit this with no client changes,
because the wire envelope stays `{profiles: [{name, engine, config_dir, default}]}`.

Follow-up (not this issue): after clean shadow logs, prefer aplexer when
present, native fallback, same kill switch. That flip is what #2340 is
waiting on.

### A2 — engine listing

`engines.py` takes `{name, command, available, env_unset}` from
`a engines --json` as the core; overlays PocketShell presentation
(`label`, `family`, `provider_mark`, `usage_provider`, `enabled`). Keep
`~/.config/pocketshell/engines.yaml` as an overlay during the transition.

Do not show aplexer's `shell` engine in the agent picker. `gemini` only if
we want it as a first-class PocketShell engine (needs badge metadata).

### A3 — launch delegation

`agents.py::launch_agent` must **not** `exec a launch-exec` drop-in.
`a launch-exec` does not merge folder `.env`/`.envrc`, does not seed
Claude trust, and does not write `@ps_agent_kind` / `@ps_agent_profile`.

Instead: `a launch-spec --engine --profile --cwd --json` (plus
`--no-skip-permissions` when the phone asked) is authoritative for argv /
profile env / `env_unset` / skip-permissions. Then apply folder exports,
trust seed (until aplexer owns it), write `@ps_*`, `execvpe`.

Keep `pocketshell agent <kind> …` as the string the phone types into tmux.

### A4 — Electron follow-through

No launch-path work. After A2, capture `--help` fixtures if a new engine
appears in `pocketshell agent --help`; add badge/slash-command metadata
only then.

---

## Phase B — aplexer PTY hosts selected sessions; both backends supported

**Do not start.** Aplexer has `a start` / `attach` / `snapshot` / `watch`,
but PocketShell attach is not shippable on that runtime yet.

| Blocker | Why it matters here |
| --- | --- |
| Terminal-state reattach (`aplexer/docs/terminal-state-design.md`) | Detach/reattach of a full-screen Codex TUI corrupts the screen. PocketShell reattaches constantly (app background, network drops). |
| Agent-state push (`a state-report` or equivalent) | Session-tree badges today come from hooks writing `@ps_agent_state`. `a watch` only has a PTY-silence heuristic. |
| Session identity mapping | Clients key on tmux **session names**. Aplexer keys on UUID + `workspace:tag`. Live processes cannot be transplanted; migration is restart-with-`--resume`. |
| Framed attach / `--lean` | Cellular reconnect UX. SSH compression can land on the client any time without waiting. |

When unblocked: `FolderListGateway` / `PocketshellClient.listSessions`
merge `a snapshot --json`; attach is an SSH PTY running `a attach`
instead of `tmux -CC` / `tmuxctl`; live updates from `a watch --jsonl`
(polling `snapshot` is an interim). Conversation pane can later use
`a transcript` for aplexer-hosted sessions (Claude/Codex/Grok today;
OpenCode stays on `agent_log.py` until aplexer parses it).

---

## Phase C — aplexer default; tmux demoted

Blocked on B. Default new sessions to aplexer; tmux read-only or
migrate-by-restart. Retire `tmuxctl` invocations, `@ps_*` options,
name-derivation, and `agents_kind.py` inference.

Out of aplexer scope (need other homes): `jobs.py` recurring pings, cards
push feed, `usage` / `quse`.

---

## Product defaults (unless the maintainer says otherwise)

1. **Picker ids.** Phase A keeps PocketShell display names (`Claude (Z.AI)`).
   Aplexer's `zlaude` / `zodex` stay an internal mapping.
2. **A3 mechanism.** `launch-spec` + shim extras, not `launch-exec`.
3. **Gemini / shell.** Do not show `shell`. `gemini` only with explicit UI work.
4. **heru profiles.** Out of scope. Do not generate `~/.config/heru/profiles.toml`.

---

## What "ready" does not mean

Aplexer is a real local multiplexer. PocketShell is a remote tmux client
with an agent-aware host CLI. Phase A closes registry/launch duplication
without asking the phone to attach to a different PTY owner. Treating the
runtime as done enough for PocketShell attach would ship a reproduced
reattach bug and drop the hook-driven state badges.
