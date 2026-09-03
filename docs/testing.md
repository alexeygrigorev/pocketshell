# Testing & QA

Two emulation surfaces let us test PocketShell end-to-end without touching real devices or real hosts:

1. Android emulator — runs the app, validates UI/UX, exercises Compose interactions
2. Docker remote server — emulates the SSH target, with tmux + agents + helper tools installed

Together they cover the full feature surface without leaving the dev machine.

---

## Android emulator

Standard Android Studio AVDs. Recommended set:

| AVD | API | Why |
|---|---|---|
| Pixel 7 | 34 (Android 14) | Canonical design target (412 × 915 dp) |
| Pixel 7 | 26 (Android 8.0) | Minimum supported; spot-check before releases |

### Running

Command-line launch (no Android Studio):

```bash
scripts/start-local-avd.sh
scripts/assemble-debug.sh --install
adb shell am start -n com.pocketshell.app/.MainActivity
```

The local startup helper defaults to `AVD_NAME=test` and the headless review
flags used by the pre-release gate:
`-no-window -no-audio -no-boot-anim -gpu swiftshader_indirect
-no-snapshot-load -no-snapshot-save`. It records
`adb devices`, `getprop`, accelerator status, AVD config, process matching, and
the emulator log under `build/local-avd-start/<run-id>/`, which is the first
artifact to attach when an AVD exits before adb sees a device.

For `connectedDebugAndroidTest` evidence, run the helper with
`AVD_HOLD=1` in a dedicated terminal and leave it open while Gradle runs in
another terminal. This keeps the startup monitor attached and records a clear
failure if the emulator exits after initially reporting boot complete.

### Automated UI tests

Compose UI tests on the emulator via `scripts/connected-test.sh`. Use:

- `createComposeRule()` for component-level tests
- `createAndroidComposeRule<MainActivity>()` for screen-level tests
- Espresso interop where needed

The connected test suite also includes a local end-to-end SSH + agent smoke
test. Start the deterministic agent Docker target first, then run the connected
debug tests on an already-running emulator:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build agents
scripts/connected-test.sh
docker compose -f tests/docker/docker-compose.yml down --volumes --remove-orphans
```

### Manual visual validation (reviewer evidence)

For visual or user-facing Android changes, the implementer should provide the
commands they ran and any screenshots or artifact paths they produced. Reviewer
approval owns the final evidence: the reviewer reproduces the relevant emulator
flow, inspects the visible result, and records the command, artifact path, Docker
involvement when relevant, and observed result in the issue.

1. Start from the latest implementer status for the scoped issue
2. `scripts/cgroup-run.sh -- ./gradlew installDebug`, or use the issue's documented walkthrough command
3. Compare side-by-side with the issue's attached design reference, when one
   exists, and check the result against `docs/design-system.md` and
   `docs/design-language.md`
4. Capture reviewer evidence:
   `adb exec-out screencap -p > /tmp/screen.png`
5. Post the command, screenshot or artifact path, and observed result in the
   reviewer issue comment

### Device tap capture and replay

For physical-phone debugging sessions that need to be handed off for replay,
record the screen, raw touchscreen trace, and logcat together:

```bash
ANDROID_SERIAL=<phone-or-emulator-serial> scripts/capture-device-session.sh <run-id>
```

The capture writes `screen.mp4`, `getevent-touchscreen.txt`,
`logcat.txt`, `logcat-final-dump.txt`, `getevent-lp.txt`, `getprop.txt`,
and `metadata.env` under `build/device-sessions/<run-id>/`. The script
auto-detects the touchscreen from `adb shell getevent -lp` by looking for a
multitouch input device with X/Y absolute axes, preferring direct devices when
Android reports that input property. It records only that device's
`getevent -lt` stream, so other keys and sensors are ignored.

Stop the capture with Enter, or use `CAPTURE_SECONDS=<seconds>` for a bounded
smoke run:

```bash
ANDROID_SERIAL=<serial> CAPTURE_SECONDS=5 scripts/capture-device-session.sh tap-smoke
```

Replay the same raw touch events with their original inter-event timing:

```bash
ANDROID_SERIAL=<serial> scripts/replay-device-session.sh <run-id>
```

Replay regenerates `build/device-sessions/<run-id>/replay-sendevent.sh`,
pushes it to `/data/local/tmp/`, runs it with `adb shell sh`, and writes
`replay.log` plus `replay-summary.txt`. The replay script substitutes the
currently auto-detected touchscreen path for the captured `/dev/input/eventN`,
so changed event numbering does not break the run.

Assumptions for deterministic replay:

- Use the same phone or emulator profile, orientation, and display size as the
  capture. The Pixel 7a debug target is portrait `1080x2400`; the scripts store
  `wm size`, `wm density`, and the touchscreen ABS X/Y min/max in metadata and
  warn if the current `wm size` differs at replay time.
- Raw touchscreen units are replayed unchanged through `sendevent`; no
  coordinate scaling is attempted. This is deliberate for Pixel 7a sessions,
  where the goal is "record this exact phone session, replay it later."
- If multiple adb devices are connected, set `ANDROID_SERIAL` or `ADB_SERIAL`.
- During connected validation, acquire the shared local AVD lock around the
  full adb sequence, for example:

```bash
flock /home/alexey/git/pocketshell/build/.avd-lock -c \
  'cd /home/alexey/git/pocketshell/.worktrees/issue-275 && ANDROID_SERIAL=<serial> CAPTURE_SECONDS=5 scripts/capture-device-session.sh tap-smoke && ANDROID_SERIAL=<serial> scripts/replay-device-session.sh tap-smoke'
```

---

## Docker remote server

The remote-side target for SSH, tmux, port-forwarding, agent detection, and usage panel tests. Image is built layered so different phases pull in different surfaces:

| Tag | Adds | Used by |
|---|---|---|
| `pocketshell-test:ssh` | openssh + test user with key auth | `core-ssh`, `core-portfwd` |
| `pocketshell-test:tmux` | tmux + `tmuxctl` | `core-tmux`, recurring jobs |
| `pocketshell-test:agents` | Claude Code, Codex, OpenCode CLIs + `heru` + `agent-log-explorer` + sample JSONL fixtures | `core-agents`, `core-usage`, host bootstrap |

All Dockerfiles live in `tests/docker/`.

### Base SSH image

Adapted from `../ssh-auto-forward/docker/`. Alpine + openssh, test key in `authorized_keys`. Compose maps host port 2222 → container 22.

```bash
docker compose -f tests/docker/docker-compose.yml up -d
ssh -i tests/docker/test_key -p 2222 -o StrictHostKeyChecking=no testuser@127.0.0.1
```

### Adding tmux + tmuxctl

`Dockerfile.tmux` extends the base:

```dockerfile
FROM pocketshell-test:ssh
RUN apk add --no-cache tmux python3 py3-pip \
 && pip install --break-system-packages tmuxctl
```

### Agent target

`Dockerfile.agents` is the local deterministic target for emulator smoke tests.
It does not install real provider CLIs and never needs live API credentials.
Instead it ships stable shims for:

- `claude`, `codex`, `opencode` — credential-free version stubs
- `heru usage --json` — normalized multi-provider usage JSON
- `agent-log-explorer detect --cwd <path>` — stable agent-candidate rows
- `tmuxctl jobs list/add/edit/remove` — stable recurring-job command shapes
- `uv tool install <package>` — bootstrap installer shim
- `systemctl --user is-active/is-enabled tmuxctl-jobs.service` — systemd-like status shim

Run it on the host SSH port used by the Android emulator smoke:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build agents
ssh -i tests/docker/test_key -p 2222 -o StrictHostKeyChecking=no testuser@127.0.0.1 \
  'for tool in heru agent-log-explorer tmuxctl uv; do command -v "$tool"; done && heru usage --json && tmuxctl jobs list --session codex'
```

The `agents` and `sshd` compose services both map host port 2222. Run one at a
time.

### Agents-fixture pool — parallel journey lanes (issue #724)

For parallel emulator+Docker journey testing (the Docker half of the #674 AVD
pool), the `agents` fixture is bringable up as N ISOLATED instances on distinct
host ports — `2243 2244 2245` by default — so two emulator lanes never
share one container's tmux state. Each lane runs under its own
`COMPOSE_PROJECT_NAME` + per-port container name, both defaulted to the legacy
single-lane identity (`pocketshell-test-agents` on 2222) when no override is
given.

**Port 2222 is deliberately NOT a pool candidate (issue #1842).** It is the
default single-lane fixture, and a dozen scripts that know nothing about the
pool recreate it unconditionally — some with `--force-recreate`
(`terminal-workbench.sh`, `phone-walkthrough.sh`,
`pre-release-confidence-gate.sh`, `capture-terminal-lab.sh`, ...). None of them
takes the port lock and none of them can, so no lock is able to defend 2222: a
lane handed that port held a lock nobody else consults while a sibling wiped its
tmux server. Pool lanes therefore claim only ports whose sole writer is the
pool. `--no-pool` and CI are unaffected — they never allocate from this list.

Bring lanes up / inspect / tear down with `scripts/agents-pool.sh` (it still
accepts 2222 explicitly for the legacy single-lane fixture; just don't hand it
to a lane):

```bash
scripts/agents-pool.sh up 2243 2244   # warm two isolated agents fixtures
scripts/agents-pool.sh status         # PORT / CLAIMED / HEALTH / CONTAINER
scripts/agents-pool.sh down 2243 2244 # tear the lanes down (-v)
```

`scripts/connected-test.sh --pool --suffix iN` then self-allocates a full lane —
a free emulator serial (per-serial flock, #674) AND a free agents port (per-port
flock + brings the fixture up healthy) — and threads the port into the
androidTest suite via
`-Pandroid.testInstrumentationRunnerArguments.agentsPort=<port>`. Two concurrent
invocations land on different `(emulator, port)` lanes with no cross-talk.

**Network-fault classes under `--pool` are isolated too (issue #2128).** Until
this fix, `NetworkFaultProofBase` hard-coded host ports 2228 / 2229 / 8474 and
Toxiproxy's upstream `agents:22`, so a fault-class lane stayed pinned to the
shared `pocketshell-test-agents` / `pocketshell-test-network-fault-proxy`
fixture no matter which agents port the pool allocated. A sibling wiping that
fixture mid-run presented as an empty session list — the #1842 class, in a
place #1842's agents-port lock does not reach. A `--pool` fault-class lane
now brings up its own `network-fault-proxy` + `packet-loss-proxy` under the
same compose project as its claimed agents fixture. Host ports are derived
from the agents port (2243 → fault 2253 / packet-loss 2263 / API 8495;
single-lane 2222 still maps to 2228 / 2229 / 8474). Inside each container
the listen stays `:2228` and the upstream stays `agents:22` — that hostname
is the compose service on *this* project. A wipe of the per-lane proxy
exits **90** with a `NETWORK-FAULT FIXTURE DISTURBED` banner that names the
empty-session-list signature so it cannot be read as a product failure.

Remaining limitation, stated honestly: `--no-pool` / nightly / a `--pool`
fallback onto port 2222 still use the historical shared 2228/8474 singleton
and still serialize on the machine-wide toxiproxy lock (#776 P3). Isolation
is a property of a claimed non-2222 pool port, not of the class name.

Both halves of that claim are anchored to the MACHINE
(`$HOME/.cache/pocketshell/avd-locks/`), not to the checkout. Until #1842 the
agents-port half was `"$root_dir/build/.agents-port-lock-$port"` — the worktree
root — so two lanes driven from different worktrees flocked different inodes,
both "won" the same port, and the second lane's `docker compose up` recreated
the first lane's container mid-run. That is the same defect #1657 fixed for the
emulator-serial half; the halves are now on one anchor so a future fix cannot
repair only one of them.

Because `docker` is machine-wide, a lock cannot make the claim unbreakable — so
the claim is instead VERIFIABLE. `connected-test.sh` fingerprints the claimed
container (`.Id` + `.State.StartedAt`) at claim time and re-checks it after the
run. If it changed, the run exits **90** with a loud banner, overriding the
Gradle verdict in BOTH directions: a wiped fixture presents as an empty session
list (indistinguishable from #1810/#1820), so a disturbed PASS is as void as a
disturbed FAIL. `scripts/test-agents-pool-isolation.sh` pins all of this in the
per-push Unit job without an emulator or a docker daemon.

If you write a new script that allocates a lane, call
`pocketshell_claim_agents_port "$ROOT_DIR"` **directly** and read the result from
`$POCKETSHELL_AGENTS_PORT`. It is not a value-returning function — it mutates the
calling shell (exports plus the EXIT trap that holds the flock), so in a subshell
(`port="$(...)"`, backticks, a pipeline, `( ... )`) all of that is discarded at
the closing paren. It now detects that and refuses loudly rather than
half-succeeding, and prints nothing on stdout so a capture can never look like it
worked; both the runtime refusal and a static scan of every caller are pinned by
the harness above. One such line would otherwise re-break all three of the
failures described here at once: no lock, a silent fall back to 2222 (the
`agentsPort` arg is gated on `$POCKETSHELL_AGENTS_PORT` being set), and no
fingerprint, so a disturbed lane goes quiet again.

The
androidTest target host:port is centralized in `AgentsFixtureTarget`
(`AndroidSshTestFixtures.kt`), defaulting to `10.0.2.2:2222`, so single-lane and
CI runs (one emulator, one `agents` on 2222) are unchanged. `ci-journey-suite.sh`
shards across lanes only when `POCKETSHELL_JOURNEY_SHARD=1`; its default is the
clean single-lane serial loop.

#### Booting the emulator pool — `sg kvm` requirement (issue #776)

The per-lane emulator serial only exists if the pool emulators are actually
booted. `scripts/avd-pool.sh start` boots `POOL_SIZE` extra emulators from the
standalone AVDs `test-1` / `test-2` / `test-3` (clones of the base `test`, port
scheme `5554 + 2K` -> `emulator-5556 / 5558 / 5560`), leaving the maintainer's
manual `emulator-5554` untouched:

```bash
scripts/avd-pool.sh start     # scoped: boot test-1/2/3 (emulator-5556/5558/5560)
scripts/avd-pool.sh status    # CLONE / SERIAL / PORT / STATE + host load/RAM
scripts/avd-pool.sh stop      # tear down the pool (leaves emulator-5554 alone)
```

`avd-pool.sh` starts each pool emulator through `scripts/lib/scope-run.sh`, so
the emulator process tree is memory-capped in its own sibling cgroup. If local
user systemd is unavailable, pool startup fails closed instead of launching raw;
set `POCKETSHELL_SCOPE_ALLOW_BARE=1` only when debugging cgroup setup.

**KVM gotcha (this is the whole point of #776):** x86_64 emulation requires
`/dev/kvm`, and on the Hetzner dev box a plain shell often lacks an *active*
`kvm`-group membership (the login session predates the group add), so a bare
`emulator -avd ...` dies with *"x86_64 emulation currently requires hardware
acceleration"*. When that happens the pool never boots, only the one manual
emulator stays online, `connected-test.sh`'s multi-emulator branches
(`online_emulator_count > 1`) never fire, and every `--pool` / auto-pin lane
silently falls back to sharing that one emulator — which is exactly the
foreground-steal + sibling-SIGKILL contention this issue set out to kill (a
sibling lane launches its own `MainActivity` on the shared device, clearing
another lane's activity -> `processForeground=false` -> null node lookups).

So `avd-pool.sh` launches each scoped pool emulator via
`sg kvm -c "emulator -avd ..."` when that wrapper is needed to gain active
`/dev/kvm` access. It auto-detects this: when the current shell can already
read+write `/dev/kvm` (a CI runner with active membership) OR there is no
`/dev/kvm` at all, it launches the emulator directly inside the cgroup scope.
Override with `POOL_KVM_WRAP=sg` (always wrap) / `none` (never wrap) / `auto`
(default).

#### Per-lane emulator serial — the contention fix (issue #776)

With the pool booted (>1 emulator online), each `--pool` lane claims a DISTINCT
emulator serial via the per-serial flock and exports it as `ANDROID_SERIAL`
(`Claimed pool emulator: emulator-5558` … `Pinned to single device via
ANDROID_SERIAL=emulator-5558`). That `ANDROID_SERIAL` is exported through to the
gradle `:app:connectedDebugAndroidTest` subprocess, so AGP's connected
DeviceProvider filters to exactly that one emulator — a sibling lane on a
different serial can never install onto, instrument, or steal the foreground of
this lane's device. Two concurrent lanes therefore land on `(emulator-5556,
2222)` and `(emulator-5558, 2243)` (distinct device AND distinct agents port),
fully isolated. Even WITHOUT `--pool`, when more than one emulator is online the
wrapper auto-pins a free serial (P1) so a bare `connected-test.sh` can't let AGP
fan the install out across every online emulator. The pool claim never
co-locates onto a busy emulator (P4); it waits for a free one, bounding
instrumentation runs per emulator to 1.

Restrict which emulators a lane may claim with
`POCKETSHELL_POOL_SERIALS="emulator-5556 emulator-5558"` (whitelist intersected
with the online set) — handy for keeping a lane off the maintainer's manual
`emulator-5554`. Single-emulator / CI runs (one emulator online) keep the legacy
single-lock, no-pin, no-pool behaviour unchanged.

Two concurrent lanes, end to end:

```bash
scripts/avd-pool.sh start                       # boot the pool in cgroup scopes
scripts/agents-pool.sh up 2222 2243             # warm two isolated agents fixtures
CLASS=-Pandroid.testInstrumentationRunnerArguments.class=com.example.SomeE2eTest
scripts/connected-test.sh --pool --suffix iA $CLASS &   # -> emulator-5556
scripts/connected-test.sh --pool --suffix iB $CLASS &   # -> emulator-5558
wait
```

#### Targeting a `shared:*` module's connected test — `--module` (issue #798)

`scripts/connected-test.sh` defaults to the app module's
`:app:connectedDebugAndroidTest`. Some connected/androidTest classes live in a
`shared:*` library module instead — e.g. the #796 proof
`CodexOutputBurstImeMainThreadProofTest` is under
`shared/core-terminal/src/androidTest/`. Pass `--module <gradle-module>` to run
that module's `connectedDebugAndroidTest` under the SAME AVD `flock` +
per-worktree `-PpocketshellAppIdSuffix` coexistence as an app-module run, instead
of a hand-rolled `./gradlew` invocation outside the lock:

```bash
scripts/connected-test.sh --module shared:core-terminal --suffix i798 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.terminal.core.CodexOutputBurstImeMainThreadProofTest
```

The wrapper owns the `:connectedDebugAndroidTest` task name (the suffix + lock
plumbing assumes that exact task), so pass only the module path — either Gradle
path syntax (`shared:core-terminal`) or a leading-colon path
(`:shared:core-terminal`). The wrapper resolves it to
`:shared:core-terminal:connectedDebugAndroidTest`, acquires the AVD lock,
auto-pins/pools the serial exactly as for an app run, and threads
`-PpocketshellAppIdSuffix=<token>` through. Omit `--module` for the unchanged
`:app:connectedDebugAndroidTest` default. `scripts/connected-test.sh --help`
prints the full flag list.

### Fixture JSONLs for agent tests

The agent target seeds `testuser`'s home with recent deterministic fixtures on
container start:

- `$HOME/.claude/projects/-workspace-pocketshell/pocketshell-claude.jsonl`
- `$HOME/.codex/sessions/2026/05/22/pocketshell-codex.jsonl`
- `$HOME/.local/share/opencode/pocketshell-rows.jsonl`

The Claude path is deliberately shaped so PocketShell's exact runtime detection
command for cwd `/workspace/pocketshell` finds it via `find ... -mmin -5`.

---

## What gets tested where

| Layer | Where | Targets |
|---|---|---|
| Unit (pure Kotlin) | `*/src/test/` | Parsers, data classes, business logic with mocked I/O |
| Integration — SSH | `*/src/test/` via Testcontainers | `core-ssh` against `pocketshell-test:ssh` |
| Integration — tmux | `*/src/test/` via Testcontainers | `tmux -CC` parser + events against `pocketshell-test:tmux` |
| Integration — agents | `*/src/test/` | JSONL parsers and deterministic Docker command fixture contracts |
| Integration — usage | `*/src/test/` | `core-usage` parses deterministic `heru usage --json` output |
| Instrumented UI / smoke | `app/src/androidTest/` on emulator | Compose screen tests, navigation, local emulator-to-Docker agent smoke |
| Manual smoke | Emulator + Docker | Issue-based implementer/reviewer flow, with reviewer emulator evidence before approval |

## Host-CLI / area coverage guard suite, with area-scoped selection (issue #2063)

The maintainer's directive: *"split our test set into different areas and run
those areas depending on the part we're changing, and run all the tests before
we release … the goal is not to increase the speed, the goal is to increase the
speed while maintaining the quality — I still want the same coverage."*

**Coverage is invariant. Only the *when* is granular.** Every test still runs on
a bounded cadence; nothing is deleted, weakened, or skipped to hit a number.

### What this is worth, measured — read this before treating it as a speed feature

It is **not** a speed feature. It is a coverage-guard suite that also scopes
selection, and that is the honest headline. Measured over the last 104 `main`
commits, reproduced independently by the reviewer:

| Measurement | Value |
|---|---|
| commits that force-full | **58–62%** (51 of the 88 with a file list; empty diffs force-full too) |
| journeys selected, average | **143.6 of 161 — 89%** |
| `:app` unit classes selected, average | **567.6 of 659 — 86%** |
| emulator lane | **1.09x** (~45 min → ~41 min through the #2059 model) |
| Unit lane | **neutral to slightly negative** — ~14% of unit test-case time saved on the 42% of commits that scope, against the guards' own cost on the rest |
| the guards' own cost | **~170–195 s per Unit variant** on a loaded box, i.e. **+6.1 min on a full Unit run** |

Nothing here was over-force-fulling at the time: the big buckets (`scripts/*`,
a non-`*Test` file in a test source set) were coarse but each was defensible,
and the taxonomy stayed deliberately conservative because a scoped run was the
ONLY signal — an under-selection was a silent coverage hole with nothing to
catch it.

**Issue #2355 changed that precondition.** Phase 1 of epic #2350 (already
merged) added a scheduled FULL-suite run on `main` on a fixed ~8h cadence,
independent of what any push selects, plus stop-the-line + revert-first
(D36): a selection miss is now caught within <=8h with attribution bounded to
that window, and recovery is a revert rather than a multi-day forward-fix.
That backstop is what makes deliberately tightening the manifest safe now,
where it was not when the numbers above were measured. #2355 narrowed the
`scripts/*` and `tests/*` force-full globs (measured over a 30-commit real
sample, `scripts/*` alone caused 53 of 75 total force-full glob hits — by far
the dominant contributor) down to an explicit taxonomy/mechanics-critical
allowlist plus a `ci-harness`-area catch-all for everything else, on the
reasoning that most of `scripts/`'s ~170 files already carry their own
unconditional self-test (a `guards-static`/`guards-ci-harness`/`guards-test-
selection` CI step that runs every push regardless of what this manifest
selects) and gain nothing from force-full.

Measured effect of that narrowing, same methodology, two independent 30-commit
samples (`git log --no-merges`, evenly spaced so as not to cherry-pick):

| Measurement | Sample A (recent, CI-tooling-heavy) | Sample B (older, wider window) |
|---|---|---|
| force-full, before | 20/30 = 66% | 20/30 = 66% |
| force-full, after | 17/30 = 56% | 19/30 = 63% |
| journey selection, median | 100% (unchanged) | 100% (unchanged) |
| journey selection, average | 89% -> 84% | 89% -> 88% |

**This is real, but it did not reach the `<20%` force-full / `<33%` median
journey-selection targets #2355 set out to hit.** Sample A shows a bigger
apparent win than sample B because it happens to be drawn from a period
unusually concentrated in CI/test-infrastructure commits (this same epic), so
narrowing `scripts/*` helps it disproportionately; sample B is the more
representative number. Two things account for the remaining gap, both
deliberately NOT narrowed this round because doing so would trade real safety
for a target percentage:

1. **The other force-full globs stayed put** (DI/nav/startup/layout, `res/*`,
   both manifests, `app/src/debug/*`, every Gradle/build-config file, the
   `TmuxSessionViewModel.kt` D28 seam) — each is independently defensible per
   its own comment in the FORCE-FULL section below, and #2355 did not re-audit
   them.
2. **The "test-infrastructure" rule** (`pocketshell_test_area_classify`'s rule
   2 in `scripts/lib/test-areas.sh` — "any non-`*Test` file inside a Gradle
   test source set forces full") is a STABLE ~23% contributor in both samples
   (7 of 30 commits each) and is the single largest remaining lever. It is a
   CLASSIFICATION-ENGINE rule, not manifest data — narrowing it correctly
   (e.g. "force full only for genuinely widely-shared fixtures like
   `app/src/*/java/.../proof/*` and `shared/test-support/*`; a package-scoped
   fixture should resolve to its own package's area like a sibling `*Test.kt`
   file would") needs its own careful round: verifying no fixture the manifest
   currently treats as "obviously shared" is actually narrow, and re-running
   the full guard suite including the I8 independent blast-radius re-scan.
   #2355 scoped this out rather than rush a change to the fail-safe direction
   under time pressure; it is the natural next step and is filed as a
   follow-up (see the issue for the tracking link).

A commit that touches ONLY product code (no scripts, no shared test fixtures,
no build config) sees a much bigger relative win than either sample average
suggests, since it never hits any of the remaining force-full triggers at
all — the two samples above are weighted toward exactly the kind of commit
most likely to still force full.

**What earns the merge is the safety half.** Building this found, mechanically,
coverage holes nothing else in the repo could see: 183 of 1047 classes resolving
to no area through a second resolver; four `@Test`-bearing files invisible to
every registry and Gradle filter (the #1851 shape, now baselined so a NEW one
fails — and one promptly did: #1622's `Issue1622DeadBandScreenshotHarness.kt`
reached `main` hours later carrying three invisible `@Test` methods and this
guard caught it before it merged, so the baseline is five; #2065 then resolved
all five — see "The five unconventional `@Test` files" below); 29 journeys
compiling against connection-core
that a connection-core change did not run; and `:shared:core-usage:test` — the
deliberately fail-loud reader of `pocketshell usage --json` — having no
mechanical link to the Python that produces it. The ledger guard is the first
mechanism here that can answer
"did every registered class actually execute in a bounded window", which is
exactly how #1851/#1853/#1859 were all missed until someone tripped over them.

Two consequences, stated so nobody has to re-derive them:

- **The guards do not run inside `./gradlew test` — they are their own CI job**
  (`guards-test-selection`, added by **#2067**, which landed in the same batch as
  this slice for exactly this reason). Wired into a JVM test they were paid TWICE
  per push (debug + release) on the Unit critical path for work that touches no
  Kotlin: `+6.1 min on every Unit job and nothing faster`, which over the last
  104 `main` commits exactly cancelled the scoped saving. Note the cost grew as
  the guards got stricter (~100 s → ~137 s when the B9 vocabulary mutations were
  added, → ~155–205 s once the live Click import and its mutation cases were
  added — a range that is mostly box load, see
  below), which is the other reason these belong in a cheap job that runs ONCE
  rather than inside a test task that runs per variant. **Never move them back
  into a Gradle test task**; if a new guard needs a home, add a step to that job.
- **The selection plan is still not consumed by CI — nothing is skipped yet.**
  Every push remains a full run. This slice plus #2067 buy the *safety* half (the
  coverage guards, now blocking and off the critical path) and pay for it once
  instead of twice; the *speed* half arrives only when a later, deliberate step
  wires the emitted plan into the test invocation. That step is where the four
  Known limits below stop being a reporting gap and become a real coverage hole
  (a host-CLI change whose consumer is reachable only through one of the four
  would select a narrow set and genuinely not run the protecting test — the
  #847 / v0.4.10 class). Re-weigh all four *for skipping* before enabling it.
- **Issue #2355 narrowed the `scripts/*` and `tests/*` force-full rules** —
  see "What this is worth, measured" above for the before/after numbers and
  what deliberately stayed force-full. The `test-infrastructure` (non-`*Test`
  file in a test source set) rule remains the largest lever not yet narrowed;
  it needs a classification-ENGINE change (`scripts/lib/test-areas.sh`), not a
  manifest-data change, and is scoped as a follow-up rather than attempted
  under time pressure in the same round as the flake-quarantine mechanism.

### Flake quarantine (issue #2355, policy D36)

D36 (process.md, "Main health: stop-the-line, revert-first, ownership, and
flake quarantine") states the POLICY: a flaking journey class is auto-filed as
an issue on first occurrence, moved into a non-blocking lane within 24h, and
carries an expiry so it cannot sit forgotten. #2355 builds the MECHANISM.

**The signal already existed.** `scripts/ci-journey-class-loop-functions.sh`'s
per-push retry-once loop (issue #712) already detects and names the exact D36
flake definition — a class that fails attempt 1 and passes attempt 2 with no
code change — printing `JOURNEY_FLAKE_RECOVERED: <fqcn> ...`. Quarantine builds
on that signal rather than inventing a second one.

**The list.** `scripts/journey-quarantine.txt` — one TAB-separated row per
quarantined class: `<FQCN><TAB><issue><TAB><added><TAB><expires><TAB><reason>`.
Format and fail-safe direction documented in its header and in
`scripts/lib/journey-quarantine.sh` (the shared load/lookup library). Fail-safe
direction is the same as the sibling taxonomy: if the list cannot be parsed, NO
class is treated as quarantined — every failure blocks, never fewer.

**Consumption — non-blocking, not "removed from coverage".** A quarantined
class runs on every push exactly like any other selected journey class;
nothing upstream of the suite's final pass/fail decision changes. Only
`scripts/ci-journey-summary-functions.sh::finish_ci_journey_suite` changes: a
class that fails BOTH attempts is split into `BLOCKING_FAILED_CLASSES` (drives
the exit code and the `Failed BOTH attempts` / `JOURNEY_FAILED` section the
workflow's classify step greps for) and `QUARANTINED_BLOCKED_CLASSES` (gets its
own "Quarantined failures" section — still named, still tied to its tracking
issue and reason — worded so it never matches the classify step's trigger
strings). A quarantined class still executes on every future push, including
the Phase 1 tier-3 scheduled full-suite cadence — quarantine only changes
whether ITS OWN failure blocks THAT run's exit code, never whether it runs.
`scripts/test-journey-quarantine-non-blocking.sh` is the self-test: it drives
the real `finish_ci_journey_suite` (no Gradle, no emulator) through a
quarantined-only failure (must be green, non-triggering wording), a
non-quarantined failure (must still block), and a mixed case (the real failure
still blocks; the quarantined one is named but does not).

**Expiry.** `scripts/check-journey-quarantine-expiry.sh` (self-test:
`--self-test`) fails CI when: the list does not parse; an entry names a class
that is no longer a registered journey (renamed/removed and the row was not
cleaned up); `added`/`expires` are not real dates or `expires` is not after
`added`; or `expires` has already passed. Resolve an expired entry by deleting
its row (fixed/removed) or re-triaging it (a fresh row with a new
`added`/`expires` and reason). Wired into the unconditional
`guards-test-selection` job via `scripts/ci-test-selection-guards.sh` — it runs
every push, independent of what this manifest selects, exactly like the
sibling `#2063` guards.

**Auto-file on first flake — semi-automated this round, not a fully wired CI
step.** `scripts/report-journey-flake.sh` is the real, tested mechanism (its
own `--self-test`): given a flaky class it previews or files/updates a
de-duplicated tracking issue (`--file-issue`, via `gh`), and separately appends
a quarantine row (`--quarantine --issue REF --reason TEXT [--days N]`, refuses
a duplicate). It is deliberately NOT wired to fire unattended from inside
`tests.yml`/`pr-journey-smoke.yml` this round — see the script's own header for
the reasoning (duplicate-issue risk, single-flake-vs-genuine-pattern
judgment, `gh` auth inside the emulator-journey job, de-dup across concurrent
shards). An on-call/human runs it after seeing `JOURNEY_FLAKE_RECOVERED` (or a
repeated failure-then-recovery across otherwise-unrelated pushes) — the
runbook is in the script's own header comment. Fully automating the trigger is
a natural, separately-scoped follow-up once this has run for real occurrences.

*(Process note on this script's own history: its first self-test round had a
variable-plumbing bug — the "gh absent" case's override missed a stale
top-level variable — that let the self-test silently file a REAL GitHub issue
instead of exercising the not-found branch. That issue (#2364) was deleted
immediately on discovery, the resolution was fixed to read the override fresh
per call, and the self-test now also asserts that fix's own presence in the
source so the same regression shape cannot silently ship again.)*

### The five pieces

| Piece | What it is |
|---|---|
| `scripts/test-areas.txt` | the manifest — the whole taxonomy, as data |
| `scripts/lib/test-areas.sh` | the shared classification engine every consumer reads |
| `scripts/select-test-areas.sh` | changed paths → areas → the run plan, plus the manifest/coverage guards |
| `scripts/check-test-execution-ledger.sh` | proves, from real JUnit results, that every class still executes |
| `scripts/test-unconventional-test-files.txt` | the reviewed exemptions for `@Test`-bearing files outside the naming convention (#2065) |

### The five unconventional `@Test` files (#2065)

Five files carry `@Test` methods without matching `*Test.kt` / `*Test.java`, so
no registry that keys off the filename — the journey registry, the area
manifest, the executed-classes ledger, the validity guards — can see them.
#2063 baselined them as a flat array of paths inside `select-test-areas.sh`, so
a NEW one fails. #2065 is the other half: a decision per file.

**The premise the decision had to correct first.** "Zero hits in
`scripts/ci-journey-suite.sh`" was read as "does not execute". It is not.
Nightly-extensive **phase 1 runs `:app:connectedDebugAndroidTest` wholesale**,
with only a `notClass` exclusion list — so an androidTest class in no explicit
suite still executes every night. And `DesignRenders` runs 68 testcases in
`:shared:ui-kit:testDebugUnitTest` on every push. All five execute. The
non-conventional name never opted them out of *execution*; it opted them out of
*accounting*, which is the harm and is what the rows now fix.

| File | Executes | Decision |
|---|---|---|
| `render/DesignRenders.kt` | `:shared:ui-kit:testDebugUnitTest`, 68 cases/push | exempt — `render.sh` already enumerates it per `@Test` **method** |
| `Issue1622DeadBandScreenshotHarness.kt` | nightly wholesale (inert without `issue1622HoldMs`) | exempt — gate is `Issue1622ComposerSheetGeometryProofTest` |
| `PromptComposerImeDeadSpaceScreenshotHarness.kt` | nightly wholesale (inert without `issue790HoldMs`) | exempt — gate is `PromptComposerImeEmptyDraftDeadSpaceProofTest` |
| `TerminalHotkeysPanelScreenshotHarness.kt` | nightly wholesale | exempt — gate is `TerminalHotkeysPanelNoTruncationTest` |
| `TmuxComposerLauncherLargeFontScreenshotHarness.kt` | nightly wholesale | exempt — gate is `TmuxComposerLauncherNarrowFontClipProofTest` |

None was renamed, and the reason is not convenience. Four are screenshot
*staging* harnesses: two are inert without an instrumentation argument, and the
other two only `assertExists()` to prove the bitmap they are about to save is
not of an empty screen. Renaming those to `*Test.kt` would put four vacuous or
artifact-producing classes into every registry **as though they were gates** —
the same "looks like coverage, contributes nothing" deception this issue exists
to end, pointed the other way. `DesignRenders` is the one that genuinely runs a
gate's worth of work, and it already has a *stronger* registry than renaming
would give it: `scripts/render.sh` parses every `@Test` method out of the source
and hard-fails when one lacks a `render("label")` mapping — per-method
accounting, pinned per push by `render-selftest.sh` — while the module's
execution is separately ledger-observed through the sibling conventional
`DesignRenderStaticLoadingPolicyTest`.

**What makes these exemptions different from a baseline.** Each row in
`scripts/test-unconventional-test-files.txt` is
`<path>TAB<executor>TAB<gate>TAB<reason>`, and the guard checks the first three:

- `<executor>` is `unit-source-set` (path must be under `*/src/test/`) or
  `nightly-connected` (path must be under `app/src/androidTest/`, **and** the
  class's simple name must appear nowhere in `nightly-extensive-suite.sh` —
  fail-closed, so naming it there for any reason forces the claim to be
  re-argued). An executor the guard cannot check is rejected, not believed.
- `<gate>` is either a FQCN, resolved through the **same class index** the area
  manifest and the ledger use — so "the real assertion lives over there" cannot
  point at a name that does not exist or at another invisible file — or
  `enumerated-by:<script>`, which must exist and reference the path.
- `<reason>` is mandatory and non-empty.

It is deliberately **not** a `*Harness.kt` suffix rule. A suffix is a self-serve
escape hatch: name a real regression test `…Harness.kt` and it hides itself with
no reviewer in the loop. Cases 21a–21h of `select-test-areas-selftest.sh` mutate
each claim and assert its specific red, including the load-bearing one — an
unpinned unconventional `@Test` file reddens by name.

### The rule for a NEW test (do not re-litigate this)

> A test's area is the area of its Gradle module, or — inside `:app` — of its
> top-level `com.pocketshell.app.<pkg>` package.

Add a test to an existing package and it inherits that package's area with **no
manifest edit at all**. Only three things need an edit:

1. a NEW top-level package or Gradle module → add a `src` + `test` row;
2. a class whose regression class belongs to a DIFFERENT area than its package
   → add a `class` row (today the only case is the conversation-source cluster,
   which lives in `com.pocketshell.app.tmux` but belongs to
   `conversation-agents`);
3. a new production path with cross-area blast radius → add a `full` row.

Forget all three and the guard fails with **"unmapped path"** while the run
falls back to FULL. The map can only lag loudly, never silently.

### Areas and tiers

Nineteen areas, keyed to the existing module/package structure. Four are
**`always`** — they run on every push regardless of the diff — and they are the
D28/G8 worst-reopen areas plus the app shell:

| always-tier area | Why it never becomes conditional |
|---|---|
| `connection-core` | SSH transport / lease / reconnect / grace / `tmux -CC` — D28, the #1 regression source |
| `terminal-render` | terminal emulation + the #796/#803 drain-scheduler ANR class |
| `conversation-agents` | conversation-source binding + agent detection — the #819/#825/#962/#1057 reopen cluster |
| `app-shell` | App / MainActivity / DI / nav / startup / test-access seams |

The other fifteen (`tmux-session`, `composer-voice`, `projects-tree`, `portfwd`,
`files`, `hosts-settings`, `share`, `usage-costs`, `bootstrap`, `notifications`,
`release-update`, `diagnostics-crash`, `ui-shell`, `ci-harness`, `host-cli`) are
**`changed`**: they run when their area, an area coupled to them, or a
force-full path is in the diff — plus nightly, plus the release gate.

### Cadence — what runs when

| Tier | Trigger | Scope |
|---|---|---|
| per-PR / per-push | every push | always-tier + affected areas + their couplings |
| nightly | `nightly-extensive.yml` cron | everything |
| nightly binding mutations (#1932 / #1671) | `nightly-extensive.yml` `binding-mutations` job | curated production-binding mutants only |
| release gate | `scripts/release-emulator-validation.sh` | everything, unchanged |

### Production-binding mutation lane (issue #1932)

A conventional constructor change can keep policy-unit tests green while
bypassing the intended production owner. The curated periodic lane in
`.github/workflows/nightly-extensive.yml` (`binding-mutations`) applies one
deterministic mutant at a time from
`scripts/production-binding-manifest.json` and requires the named
production-wired proof to fail. Attendance and per-binding evidence land on
that job's step summary and the `production-binding-mutations` artifact
(rolled up to nightly/reliability epic #1671). The cheap Unit half is
`scripts/check-production-binding-mutations.py --self-test` plus
`--check-sites`: it does **not** apply the production mutants. A missing or
`--self-test`-only nightly job is treated as decorative and fails closed.
The artifact byte ledger includes `summary.md` and `summary.json` themselves:
the summaries are rewritten to a stable final directory size, and that exact
uploaded size is what the lane reports and enforces against the manifest cap.

The manifest contains only bindings with an existing behavioral proof that is
wired through the shipped production entry point. The controller display,
controller network, and assistant shared-lease candidates from the #1660 audit
are deferred until such a proof exists (or removed when the corresponding
production choice becomes structurally impossible); an anchor-presence check is
not accepted as a mutation killer.

### Force-full triggers (blast-radius escapes)

A change whose effect leaves its own area must never silently skip the test that
would have caught it. Any of these ⇒ run **everything**:

- `.github/**`, `scripts/**`, `tests/**` — the harnesses that decide what runs
- `gradle/**`, `**/*.gradle*`, `gradle.properties`, `gradlew*`, `cgroups.toml`,
  `debug.keystore`, `app/lint.xml`, `app/*.pro`
- `shared/test-support/**` — the one audited settle pump
- `app/src/main/**/di/**`, `nav/**`, `startup/**`, `testaccess/**`, `layout/**`,
  `App.kt`, `MainActivity.kt`, `AppTeardownScope.kt`, `MainThreadConfinement.kt`
- `app/src/main/**/tmux/TmuxSessionViewModel.kt` — the D28 god-object seam
  (#766 residue) where cross-area wedge bugs hide
- `app/src/main/res/**`, both `AndroidManifest.xml`s, `app/src/debug/**`
- **any file inside a test source set that is not itself a `*Test.kt` /
  `*Test.java`** — a shared fixture's blast radius is exactly as unknown as a DI
  module's
- **any path the manifest does not match at all** (fail-safe)

`shared/ui-kit/**` is an area rather than a force-full trigger, but it *couples*
to every UI-bearing area, so a design-token change is near-full in practice.
That is deliberate — the #453/#641 "one token, every screen" class.

### The fail-safe direction is structural, not a convention

`pocketshell_test_area_classify` sets its verdict to `full` **before it looks at
anything**, and every narrower answer requires an explicit manifest row to have
matched. There is no code path ending in "nothing matched, so run nothing": the
fall-through *is* `full`. Deleting a rule, mistyping a glob, or dropping a whole
record type all degrade toward running MORE. Three more one-way ratchets sit on
top: a manifest that fails to load ⇒ full; an empty diff ⇒ full; and the
always tier is unioned in unconditionally and last, so the smallest possible
selection is the always tier, never the empty set.

`scripts/select-test-areas-selftest.sh` proves each of those by mutating the
input rather than asserting it in prose.

### Running it

```bash
scripts/select-test-areas.sh                      # plan for the current branch diff
scripts/select-test-areas.sh --print-plan-only    # machine-readable KEY=VALUE
scripts/select-test-areas.sh --journeys           # every journey class + area + tier
scripts/select-test-areas.sh --verify-manifest    # guard: the manifest is total
scripts/select-test-areas.sh --coverage-invariant # guard: coverage is invariant
```

```bash
scripts/select-test-areas.sh --list-classes       # every class + area + module + deps
```

`--coverage-invariant --only I8,I9` runs a subset. It exists so a self-test
mutation can drive the one invariant it targets without paying for the other
nine; **it is never a CI mode**, and a filtered run says so in its own verdict
line so a partial pass cannot be read as the whole guard.

The plan emits `UNIT_SHARED_TASKS` (module test tasks, unfiltered) and
`UNIT_GRADLE_TASKS` + `UNIT_GRADLE_FILTERS` (`:app:test` with `--tests`) as
**two invocations**, because a single `--tests` applies to every test task in
one invocation and would filter the shared modules to nothing. In `full` mode it
emits the byte-identical whole-graph `test` task the Unit job runs today, which
is what keeps `scripts/check-ci-unit-forced-execution.py` satisfied on the full
path.

`UNIT_GRADLE_FILTERS` carries **exact fully-qualified class names, never
patterns**. Gradle's `--tests` wildcard crosses package dots — `--tests
"com.pocketshell.app.*UsageWindowLabelTest"` really does run
`com.pocketshell.app.usage.UsageWindowLabelTest` — so a package-shaped pattern
silently runs far more than the plan reports. `--coverage-invariant`'s **I10**
asserts the emitted filter set is exactly the selected `:app` unit class set and
contains no glob metacharacter, so the reported number and the executed command
cannot drift apart again.

`UNIT_SHARED_TASKS` is derived from the selected classes' own module paths, not
from an area→task table. **I11** asserts every shared module is run by a change
to its own source, which is the check a hardcoded table did not have.

### What actually decides that a test runs

| # | Rule | Where it comes from |
|---|---|---|
| 1 | the run is FULL | force-full path, unmapped path, manifest load failure |
| 2 | the class's area is `always`-tier | `area … always` rows |
| 3 | the class's area is in the diff, or in the `couple` closure of the diff | `couple` rows |
| 4 | the class **imports** production code of an area in the diff | the class's own `import com.pocketshell.…` lines |
| 5 | the class sits in — or imports — a package on the host-CLI **wire seam**, and `tools/pocketshell/**` changed | the seam, derived at **both ends** |

Rules 4 and 5 are the reason `couple` rows are now few and behavioural. A
compile-level dependency must **not** get a `couple` row: rule 4 already has it,
per class, derived from the compile graph rather than from anybody's memory.

**Rule 5 exists because of #847 / v0.4.10.** `tools/pocketshell/**` is Python, so
no Kotlin import can see it, and host-CLI/client version is a *runtime* lockstep
— a new client calling a new subcommand against an older host CLI hangs.

**A wire contract has two ends, and marking only the invoker misses half of it.**
The first version of this seam marked "packages that invoke the CLI". No shared
module ever shells out, so that seam had *zero* reach into `shared/*` — and
`shared/core-usage`, whose `PocketshellUsageJsonParser` is the deliberately
STRICT, fail-loud reader of the NDJSON `tools/pocketshell/.../usage.py` emits,
was therefore not run by a change to the code that produces it. Every check and
every invariant stayed green. A production package is now on the seam when any
of these holds, each derived mechanically:

| End | Rule | Reaches (examples) |
|---|---|---|
| **producer** | a file in it invokes the CLI (`PocketshellCommand`, literal `pocketshell …`) | `app.usage`, `app.projects`, `app.tmux` … (15 packages) |
| **consumer** | an *invoking file* imports it — one hop; the invoker hands the reply to something, and that something is in its import set | `core.usage` (the parser), `core.storage.entity` (the CLI lockstep version columns), `uikit.model` (`HostSetupState`) … (21) |
| **vocabulary** | a file in it names a real subcommand, where the list comes from the producer's live Click Group | `app.settings`, which parses the `pocketshell qr-share` payload although nothing in the app ever invokes it — the payload arrives by QR scan, so no import edge exists to follow (20) |

**Why the vocabulary uses a live Click Group instead of source parsing.** The
producer's command table is a runtime object. A source reader can miss aliases,
dictionary writes, sibling registration, or a custom Group whose
`list_commands()` synthesizes commands; each omission produces a plausible
smaller vocabulary. The guard therefore imports `cli.py`, verifies that its
`cli` export is a `click.Group`, and asks that object for
`list_commands()`. The returned names are the vocabulary used to find the
host-CLI seam.

The live reader is fail-closed:

- Import failures, a missing/incorrect `cli` export, a failing
  `list_commands()`, invalid or duplicate command names, and a missing reader
  terminator are errors. Partial output is discarded before the seam is marked.
- There is no AST fallback or static registration census. The guard floors the
  live command count and independently rejects any import/runtime error, so
  under-reading cannot look like a successful check.
- The reader prepends the checkout's `tools/pocketshell/src` to the import path
  so it inspects the source under test. The guard job installs only the
  import-time dependency `click>=8.2.0`; it does not install the complete
  package, because the pinned `quse` backend is a runtime subprocess dependency
  and is not needed to import `cli.py`.

The focused self-test keeps the failure modes load-bearing. It mutates the
real `cli.py` into a custom `DynGroup` whose `list_commands()` adds
`synthetic-live` (red on the old AST reader, green on the live reader), adds
a missing import (explicit import error), and replaces the interpreter with a
nonzero shim (missing terminator). Each mutation is checked before its verdict
is read.



The one hop is deliberately unfiltered: it also marks collaborators that are not
wire consumers (theme tokens imported by an invoking Composable). That
over-selection costs time on a `tools/pocketshell/**` change *only* — `host-cli`
is a changed-tier area nothing else puts in the diff — and a false negative
there is #847. Measured consequence: a host-CLI change now selects **158 of 161
journeys and 570 of 659 unit classes**, i.e. effectively a full run. That is the
right answer for this class of change.

`--verify-manifest` check 7 floors **each end separately** — producer ≥ 12,
consumer ≥ 15, vocabulary ≥ 14, live Click commands ≥ 12, seam packages under
`shared/` ≥ 8, host-CLI-coupled classes ≥ 600 — and rejects every live-reader
import/runtime error. The command-count floor catches a totally dead reader,
while the independent error check catches a broken import or a partial read;
neither failure can be mistaken for a smaller-but-valid vocabulary. A total-only
floor is exactly what hid the missing end (15 packages looked healthy at zero
shared reach), and a `>= 1` floor detects only a *totally dead* mechanism:
dropping the two string-literal alternatives from the invoke marker used to cut
the seam 15→9 packages / 569→210 classes with nothing noticing.
`--coverage-invariant`'s **I9** pins both journeys
(`FolderListHostOutdatedTreeVersionDaemonDockerTest` #1509 G10,
`FolderListOldCliHydrateDockerTest`) **and unit consumers**
(`PocketshellUsageJsonParserTest`, `AppDatabaseTest`,
`SessionAgentKindOptionTest`) to it by name — and for a unit pin it additionally
asserts the emitted plan really carries that class's `:shared:…:test` task,
because "selected" and "executed" are not the same claim.

**The bound this design accepts, stated rather than hidden.** Rule 4 is the
*direct* compile edge. It does not chase transitive production dependencies
(test → `fileviewer` → `core-ssh`). Two stronger designs were measured on this
tree and both degenerate: derived area-level edges applied transitively make the
19-area graph strongly connected (every change selects 17 areas), and the
production-to-production graph is strongly connected too (93 edges,
composer-voice ⇄ connection-core ⇄ hosts-settings). A fully sound
dependency-based selection on this codebase *is* "run everything". The backstops
for the bound are the always tier, the nightly full run, and the release gate.

### The executed-classes ledger — the mechanism that PROVES the invariant

`check-executed-test-counts.sh` (#1646) proves a test *task* executed more than
zero tests. That is one level too low to see this repo's recurring failure: a
class that belongs to no suite, so nothing ever runs it, unnoticed for months
(#1851 *reported* `ColdInstallE2eTest` / `EmulatorWorkflowE2eTest` as unwired —
nightly wholesale later proved they ran, but they were accounted incorrectly,
#2082; #1853's twelve dead harnesses; #1859's shard truncating at 98 of 226).
All three were found *incidentally*. Area selection makes that class strictly
more dangerous, because "this test did not run in this job" becomes normal and
stops being suspicious.

```bash
# after any test tier, credit what actually executed
scripts/check-test-execution-ledger.sh --record build/test-results --tier unit

# current-run selected vs executed vs asserted (#2082 / #1859)
# unit-debug = test + testDebug (the Debug job); unit-release is the mirror.
scripts/check-test-execution-ledger.sh --attendance --results-root build/test-results \
  --selected-from unit-debug --require-class com.pocketshell.app.proof.ColdInstallE2eTest

# fail when any registered class is unmapped, never executed, or stale
scripts/check-test-execution-ledger.sh --verify --max-age-days 7
```

CI actually invokes those commands now (#2082): the Unit job records + verifies
the variant that just ran (`--selected-from unit-debug` /
`--source-set unit-debug` on `testDebugUnitTest`, `unit-release` on
`testReleaseUnitTest`) against `*/build/test-results` and persists the TSV via
`actions/cache`; nightly shards write per-shard attendance and the
`execution-ledger` job unions them against `app/src/androidTest` minus
`notClass`, pins `ColdInstallE2eTest` and `EmulatorWorkflowE2eTest` by FQCN
from the real artifact, and records; the release gate records this run then
`--verify`s the rolling 7-day ledger. An absent cache, empty XML set, or
missing shard artifact is RED — it is not a pass.

"Executed" means *appeared in a JUnit result as a testcase that was not
skipped*. A class whose every case is `<skipped/>` is recorded as
seen-but-skipped and does **not** satisfy the guard — an all-skipped class is
the G3 vacuous pass. Nightly phase 1 still runs
`:app:connectedDebugAndroidTest` wholesale minus `notClass` (#2065/#2078);
attendance reuses that selected set (`app/src/androidTest` plus documented
`nightly-connected` rows) rather than inventing a second reachability
analyzer. Shared-module `androidTest` classes are a different Gradle task
and are not in the phase-1 selected set. A missing or empty ledger **fails**:
a guard that passes with no evidence is decoration.

The ledger is a plain TSV (`<fqcn>\t<epoch>\t<tier>`); CI persists it with a
rolling Actions-cache key (`test-execution-ledger-`). `--verify` resolves each
registered class through **the same single resolver** the manifest guard uses.
There used to be two — one keyed on tracked file paths, one on FQCNs against a
hardcoded list of source roots that contained no `shared/*/src/test/java` — and
the second reported 183 of 1047 classes as belonging to no area, so the guard
was red on the real tree while its self-test (synthetic `app/src/test` trees
only) was green. Seed a complete ledger from `--list-classes` if you need one:

```bash
scripts/select-test-areas.sh --list-classes |
  awk -v n="$(date +%s)" -F'\t' '{print $1"\t"n"\tseed"}' > build/test-execution-ledger.tsv
```

### Self-tests (all wired into the `guards-test-selection` CI job)

```bash
scripts/select-test-areas.sh --verify-manifest
scripts/select-test-areas.sh --coverage-invariant
scripts/select-test-areas-selftest.sh
scripts/check-test-execution-ledger-selftest.sh
scripts/check-test-execution-ledger-wiring.py --self-test
scripts/dev-fast-gate-parity-selftest.sh
tests/scripts/release-ledger-lane-coverage-test.sh
```

Those are exactly the guard steps of the **`guards-test-selection`** job in
`.github/workflows/tests.yml` (#2067), alongside the #2355 journey-quarantine
checks the same job runs.

The last one is #2435's: the release job's #2082 ledger step, and the
lane-completeness property it depends on, driven against the REAL
execution-ledger script (~19 s). It lives here rather than in
`tests/scripts/release-validation-storage-test.sh`, where #2435 round 2 first
put it, for exactly the reason below — that harness is driven by
`DiskPreflightScriptTest`, so it is charged once per variant on the Unit
critical path. The job is a dependency of the `unit-gate`
aggregator, which carries the literal `Unit tests` check name branch protection
requires — so these guards are **blocking on every push**, for the same reason
`AvdLockScriptTest` exists: a guard no lane runs is not a guard. They are NOT a
Gradle test: no JDK, no Gradle, no Android SDK, no Docker in that job.

They were briefly driven from a JVM test (`SmartTestSelectionScriptTest`) so
`./gradlew test` would pick them up. That is deleted. Do not recreate it: a test
task runs per variant, so it charged the suite twice per push on the Unit
critical path — see the cost note below and the two consequences under "What
this is worth, measured". If you add a guard, add a step to the job.

**A local `scripts/full-jvm-gate.py` green therefore does NOT cover these**, the
same way it does not cover `check-file-size-hygiene.sh` or
`check-test-validity.sh`. Run the five commands above directly when you touch the
manifest, the classification engine, the journey registry, or `cli.py`.

**Where its cost lands.** The selection invocations take **~155–205 s** (plus
#2435's ~19 s ledger harness), now paid
ONCE per push in a parallel job instead of once per Unit variant on the critical
path. Four measurements, all at 46 cases except the first:

| Run | Cases | debug | release | box load |
|---|---|---|---|---|
| round 5 (in `./gradlew test`) | 45 | 169.5 s | 196.1 s | ~18 |
| round 6, run 1 (in `./gradlew test`) | 46 | 205.3 s | (not reached) | ~12–14 |
| round 6, run 2 (in `./gradlew test`) | 46 | 154.2 s | 165.3 s | ~12 |
| #2067, as the five direct invocations | 46 | 164.4 s (once) | — | ~12–16 |

**Read the absolute number as noise-dominated, not as a budget.** The round-6
runs are the SAME tree at the SAME case count and differ by 51 s (33%) on the
debug variant, so a wall-clock delta cannot measure the cost of adding a case.
The meaningful figure is structural: each mutation case rebuilds the dependency
index once, ~11.6 s, and there are ~20 such builds across the suite. Budget by
that, not by a stopwatch reading. The selection self-test is ~80% of the total
and grows by one index build per case added.

**The ceiling, so a later round has a number instead of drift.** The dedicated
job's ceiling is **5 minutes wall clock**, because beyond that it becomes the
critical path on a docs-only push. #2067 did **not** remove the ~11.6 s marginal
cost of a mutation case — it removed the x2-variant and critical-path factors
only. So a round that wants more than ~2 new index-rebuilding cases must
share/cache the dependency-index build across cases FIRST. The job prints its
per-guard and total seconds into the GitHub step summary so that drift is
visible; that is deliberately a *reported* number and not an asserted one,
because a 33%-variance quantity makes a terrible assertion (G6). The job also
runs unconditionally on every push — it is a workflow job, so no area selection
spares it, and there is no selection consumption in CI to spare it with. The
dominant term inside it is the dependency index, rebuilt in ~20 subprocesses
across the self-tests, which is why the import scan is one awk pass rather than a
5709-iteration bash loop (~1.9 s → ~0.9 s per build).

### The `unit-gate` aggregator has three lists, and a guard keeps them equal

`unit-gate` is the job named `Unit tests`. Adding a job to the unit lane means
touching it in **three** places — `needs:`, the `env:` mapping of
`${{ needs.<job>.result }}`, and the `for pair in "Label:$VAR"` result loop. A
job in the first but not the third runs, can go **RED**, and the required check
stays **GREEN**: a red job under a green required check, which is the silent-gate
failure #2060 exists to prevent.

`scripts/check-unit-gate-wiring.sh` (run per push in `guards-static`) makes that
mechanical instead of a comment:

```bash
scripts/check-unit-gate-wiring.sh --self-test   # eleven red->green cases
scripts/check-unit-gate-wiring.sh               # check the real workflow
```

It asserts the check name is still literally `Unit tests`, that the three lists
are the same set, that each env var name is its job key upper-snake-cased (so a
label cannot be printed next to a different job's result while all three lists
still "agree"), that every needed job exists, and that every workflow job is
either wired into the gate or in a short named exempt list — so a NEW job cannot
be added ungated by accident. Its C9 check additionally refuses to let the five
selection guards above be reached from a Gradle test source or build script,
which is the one way a well-meaning later round would silently put the ~165 s
suite back on the Unit critical path at twice the cost. The self-test asserts its
own case count, so the anti-vacuous guard cannot itself pass vacuously.

### Fast Static Guards

`scripts/check-file-size-hygiene.sh` is a cheap repo-wide `git ls-files` guard
for oversized first-party files. It uses a 128 KiB threshold, ignores
vendor/generated/worktree directories and `shared/core-terminal`, and compares
against `scripts/file-size-hygiene-baseline.txt`. A baselined oversized file may
shrink or disappear, but it may not grow; a new non-exempt tracked file over the
threshold fails. Run:

```bash
scripts/check-file-size-hygiene.sh --self-test
scripts/check-file-size-hygiene.sh
scripts/check-file-size-hygiene.sh --update   # only after files shrink
```

### Detached local full-JVM gate (issue #1956)

The canonical forced local gate normally takes longer than a bounded agent or
remote shell caller is guaranteed to stay alive. Launch it through the detached
entrypoint when the verdict must outlive that caller:

```bash
scripts/full-jvm-gate-detached.py start --run-id issue-1956-review
scripts/full-jvm-gate-detached.py status --run-id issue-1956-review
scripts/full-jvm-gate-detached.py tail --run-id issue-1956-review --follow
```

`start` returns after a transient user service is observed running. That service
invokes the unchanged `scripts/full-jvm-gate.py` entrypoint; the canonical gate
still owns its immutable Gradle command, authenticated profile, cgroup, and
output-tree lock. The detached layer does not reproduce or override any of that
logic.

Each run writes combined stdout/stderr plus atomic `metadata.json` and
`result.json` files under
`$XDG_STATE_HOME/pocketshell/full-jvm-gate/<checkout-key>/<run-id>/` (or
`~/.local/state/...` when `XDG_STATE_HOME` is unset). The status output reports
the launch/end times, checkout SHA, real exit code, service and scope identities,
and whether either exact unit remains. A non-zero canonical verdict is `FAIL`;
a signal exit or a vanished observed service with no durable result is
`INTERRUPTED`, so caller termination cannot be mistaken for a test failure.
Successful `systemctl show` output must positively establish each exact unit as
inactive (including an explicit post-result `LoadState=not-found`). Inspection,
control, empty-output, and malformed-output failures are reported nonzero and
can never certify `PASS`, `orphan_units=none`, or completed cleanup. Service
inspection additionally requires a numeric `MainPID`; scope units use their
real systemd schema (`LoadState`, `ActiveState`, and `SubState`) because
`MainPID` is not a scope property.

To interrupt a run deliberately:

```bash
scripts/full-jvm-gate-detached.py stop --run-id issue-1956-review
```

`stop` targets only the service and scope identities recorded for that run. It
never scans for Gradle/Kotlin processes or cleans another checkout's runner.
The lifecycle guard is JVM-free, runs per push, and carries live mutations for
canonical delegation, verdict classification, fail-closed inspection, and the
service/scope property boundary:

```bash
scripts/test-full-jvm-gate-detached.sh
```

### Free-disk preflight and safe-list cleanup (issue #1989)

**An ENOSPC failure is indistinguishable from a real gate failure until someone
runs `df`.** During #1963 validation the dev box's root filesystem hit 100%
(436G, 24M available) and two gates died before a single test ran: a connected
lane could not write `~/.docker/buildx` and then could not create
`~/.gradle/caches/8.13`, and `scripts/full-jvm-gate.py` failed in
`:app:kspDebugKotlin` at 1m12s. Neither is an assertion failure, but both look
like one, so a full disk burns a review round and gets blamed on the change
under test.

Both canonical wrappers now refuse to start instead. The preflight runs before
anything expensive or shared is claimed — before the #2007 Gradle output-tree
lock, before the toxiproxy lock, before an emulator serial, before the agents
fixture container — so a lane that cannot succeed never sits on the box's
scarcest resource while it finds out.

| Free space on the gate's filesystem | Behaviour |
|---|---|
| below 10 GiB | refuse to start, exit **76**, print usage + the cleanup command |
| 10–20 GiB | run, with a `WARN: disk preflight` line naming the cleanup command |
| above 20 GiB | run silently |

**The 10 GiB floor is measured, not guessed: it has to clear one run's own
working set.** A floor below that admits a run which then dies of the very
ENOSPC it was checked for — a rubber stamp, worse than no preflight, because the
failure now carries a "disk preflight OK" line above it. On the dev box
(2026-08-06) a completed gate leaves **2.7 GiB** in `app/build` and **3.1 GiB**
across all module `build/` directories, before Gradle daemon temp,
`~/.gradle/caches` growth, and (for a connected lane) Docker image and BuildKit
layers. 10 GiB is roughly 3x that.

An earlier revision set the floor at 4 GiB on the premise that these wrappers
run on hosted CI runners where free space is tight mid-job. **That premise is
false**, and it is recorded here so it is not rediscovered as a reason to lower
the floor again. `scripts/full-jvm-gate.py`'s real path never runs on a hosted
runner — `.github/workflows/tests.yml` invokes it only as
`--profile-guard-self-test` / `--profile-guard-check`, both exempt from this
preflight and both building nothing, and the real path additionally refuses to
run with `CI` set. The one CI exposure is `connected-test.sh` in the emulator
job, which measured **110134 MB** free after its own cleanup step (`main` run
`31040932815`) and whose cleanup step already hard-fails the job below **9000
MB** for the AVD userdata partition. A 10240 MiB floor sits barely above a bar
CI already enforces for itself.

The advisory 20 GiB line is the "clean up before root reaches 100%" alert; it
never blocks a run.

Exit code 76 is distinct from a build failure, from the #2007 output-lock
timeout (75), and from the #1842 disturbed-fixture verdict (90), so "the box was
full" is never read as "this change is red".

`scripts/connected-test.sh` resolves the preflight through
`scripts/lib/disk-preflight.sh`; `scripts/full-jvm-gate.py` is an isolated
Python program that sources no shell library, so it reimplements the identical
thresholds and the identical statvfs arithmetic.
`tests/scripts/disk-preflight-test.sh` pins the two halves against each other —
change a threshold in one and that harness fails.

`POCKETSHELL_DISK_MIN_FREE_MB` / `POCKETSHELL_DISK_WARN_FREE_MB` move the
thresholds (a malformed value is a hard failure, never a silent fallback to the
default). There is deliberately no variable that skips the check.
`connected-test.sh --cleanup-suffixes` is exempt: it builds nothing, and gating
a recovery path on the condition it recovers from is a self-lockout.

To reclaim space, use the serialized safe-list sweep. It defaults to a dry run:

```bash
scripts/disk-cleanup.sh                        # report only; deletes nothing
scripts/disk-cleanup.sh --apply                # reclaim stages 1-4
scripts/disk-cleanup.sh --apply --worktrees    # also sweep clean agent worktrees
```

It will delete, and nothing else is reachable from it:

1. the Docker build cache (`docker builder prune -f`);
2. **dangling** Docker images (`docker image prune -f`, never `-a`) — and it
   re-reads the `pocketshell-test:*` tag list afterwards and hard-fails if any
   image disappeared;
3. `/tmp/pocketshell-*` scratch older than `--tmp-age-days` (default 1), so a
   live sibling lane's fresh scratch is never taken;
4. exact generated `<run>/worktree` copies plus the private `gradle-home` under
   `build/pre-release-confidence-gate/`, and `build/phone-walkthrough/` (named
   explicitly, not globbed under `build/`, because `build/test-results/` holds
   the only artifact identifying a failing assertion — the #1969 lesson). The
   pre-release run directories and their small summaries/logs/test XML survive;
5. with `--worktrees` only: `.claude/worktrees/agent-*` worktrees that are
   unlocked, report an empty `git status --porcelain`, and have no commits
   ahead of `origin/main`.

It will never touch running or stopped containers, any tagged image, other
projects' images, `~/.gradle/caches`, `~/.android/avd`, `.worktrees/issue-*`,
the checkout it is running from, or any worktree holding uncommitted, untracked,
or unpushed work. That last one is not a nicety: on 2026-07-19 a routine
`agent-*` sweep destroyed the entire #1487 implementation because it was held
only as **untracked** files, which `git diff` reports as clean. The sweep checks
`git status --porcelain`, which sees `??` entries, and it never passes
`--force`.

A machine-wide per-user `flock` makes the sweep single-writer, so two agent
lanes cannot race on one worktree list and one Docker daemon.

### Release-validation disk budget and retention (issue #2055)

The release chain has a larger fail-closed floor than the generic one-run gate.
Both `scripts/release-emulator-validation.sh` and
`scripts/pre-release-confidence-gate.sh` run this check before the AVD lock,
isolated source copy, Docker, or Gradle:

| Free space on the release-validation filesystem | Behaviour |
|---|---|
| below 24 GiB | refuse the release validation, exit **76**, and print the safe cleanup command |
| 24 GiB or more | reclaim stale copied worktrees, then start normally |

The fixed 24 GiB budget does not replace or lower the canonical 10 GiB floor.
It budgets two 10 GiB clean-run envelopes plus 4 GiB for the private Gradle
home, copied APKs, emulator evidence, and filesystem churn. The incident behind
#2055 retained 4.5 GiB in two failed isolated worktrees plus 1.9 GiB in the
private Gradle home, so starting the release at the generic floor was not safe.
There is deliberately no environment variable that lowers or skips the release
floor. For an isolated pre-release run, the parent performs this admission once
before copying. The child re-authenticates its exact generated location but does
not subtract the copied bytes and demand a second 24 GiB floor; therefore exactly
24 GiB is a real admitted boundary, not a promise invalidated immediately after
`rsync`. Retention is armed before every child recheck and restored after the
real AVD-lock acquisition installs its cleanup trap. Thus a later refusal—or a
partially successful `rsync`—releases the lock, keeps an actionable summary,
reports the copied size and retained diagnostics, and removes the copy and owner
marker.

The manual `ubuntu-latest` workflow satisfies that same floor rather than
weakening it. Before `android-emulator-runner` allocates the AVD, the workflow
removes only unused image-provided .NET, NDK, GHC, boost, CodeQL, Docker-image,
and inactive tool-cache payloads while preserving the `setup-java` JDK. This is
the same bounded reclaim used by the emulator journey job, which measured
110134 MiB available afterwards in Tests run 31040932815. It then runs
`scripts/release-emulator-validation.sh --check-storage`; that mode drives the
real 24 GiB preflight and exits before the AVD lock, Gradle, Docker, or emulator.
If a future hosted image cannot establish the full budget, the workflow fails
there as an explicit runner-capacity problem instead of entering an impossible
release run.

Every isolated run records its owner PID and `/proc` start time. On failure the
gate first keeps `summary.txt`, step logs, and small `TEST-*.xml` diagnostics,
prints the generated worktree's size and `scripts/disk-cleanup.sh --apply`, then
removes only the exact `<log-root>/<run-id>/worktree`. The outer release wrapper
does the same after all downstream APK consumers finish on a successful run.
The next preflight reclaims abandoned copies whose owner is no longer alive.
If the filesystem is still below 24 GiB, it also removes the private release
`gradle-home` while every release owner is idle and then re-measures. Live
copies and their shared Gradle home are always skipped.

Automatic deletion additionally requires a root provenance marker owned by the
current user. The entry scripts may create that marker only at the exact
`<repository>/build/pre-release-confidence-gate` path. A source-root `LOG_ROOT`,
`/var`, an arbitrary `PRE_RELEASE_GATE_LOG_ROOT`, a symlink/non-canonical path,
or even a correctly shaped but unmarked directory is rejected before `chmod`,
`find`, or `rm` runs. The isolated child re-authenticates the marker and proves
it is executing from the marked `<run-id>/worktree`; it cannot re-anchor cleanup
to its copied checkout. Run IDs also occupy a namespace disjoint from cleanup
controls: `gradle-home`, the storage-root marker, its temporary-marker names, and
the owner-marker name are reserved. Standalone entry points and the manual
workflow reject them before an AVD lock or isolated copy. Cleanup also preserves
a live `gradle-home` owner marker left by an older run rather than treating that
collision as an idle cache.

The manual cleanup path follows the same selector. It can make owner-owned
`0555` directories and `0444` files writable inside those exact generated
targets (the #1714 evidence recurrence), but it never broad-matches
`*worktree*`, never follows symlinks or crosses filesystems, and never removes
the adjacent summaries/logs, a `source-worktree` sibling, a real
`.worktrees/issue-*`, or user files. The private release `gradle-home` is removed
only when no live release validation is recorded.

---

## Connecting the emulator to the Docker server

Inside an Android emulator, `10.0.2.2` is the host machine's loopback. So the app connects to Docker's mapped port like this:

```
hostname: 10.0.2.2
port:     2222
user:     testuser
key:      tests/docker/test_key (imported via the app's host-add flow)
```

Quick sanity check from the emulator shell:

```bash
adb shell
$ nc -zv 10.0.2.2 2222
```

### Local emulator + agent smoke

1. Start an emulator.
2. Start the deterministic agent target:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build agents
```

3. Confirm host-side SSH and command fixtures:

```bash
ssh -i tests/docker/test_key -p 2222 -o StrictHostKeyChecking=no testuser@127.0.0.1 \
  'for tool in heru agent-log-explorer tmuxctl; do command -v "$tool"; done && heru usage --json && tmuxctl jobs list --session codex'
```

4. Run the connected Android smoke:

```bash
scripts/connected-test.sh
```

The smoke test authenticates with `tests/docker/test_key`, connects to
`10.0.2.2:2222`, asserts the helper commands are on PATH, parses
`heru usage --json`, runs `tmuxctl jobs list`, checks `agent-log-explorer`, and
uses PocketShell's Claude JSONL detection/read path over SSH. It also seeds a
deterministic saved host in the debug app, opens it through the real host-list
UI, sends walkthrough shell commands through the prompt composer, verifies visible
terminal transcript output for `ls`, `pwd`, and tmux, verifies the remote
artifacts, and cleans up the remote temp directory and tmux session.

### app2 (rewrite) connected journeys

The rewrite's `app2` module has its own instrumented journey suite, starting
with `J01ConnectAndTrustJourney` (rewrite task U-2). It uses the SAME fixture
and the same key as everything above; two things differ from the old module:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build agents
scripts/connected-test.sh --module app2 --suffix iapp2
```

1. **`--module app2` is required.** `scripts/connected-test.sh` owns the task
   name — it appends `:connectedDebugAndroidTest` itself — so passing
   `:app2:connectedDebugAndroidTest` as a trailing gradle argument would ALSO
   run the default `:app` task, which no longer exists on this branch.
2. **The wrapper does not start the fixture.** It claims the emulator (and,
   with `--pool`, an agents port); bringing the `agents`/`sshd` container up is
   the caller's job, as in every section above. The suite fails fast with an
   explicit "bring it up with docker compose …" message rather than an
   unexplained UI timeout, and calls out `EPERM`/`EACCES` separately since that
   means a missing `android.permission.INTERNET` rather than a down fixture.

`J04CreateSessionJourney` (rewrite task U-6) additionally exercises
`pocketshell sessions create --json` on the fixture. That arm of
`tests/docker/agent-bin/pocketshell` delegates to the repository's REAL host
implementation and creates a genuine detached tmux session on that session's own
`tmuxctl-<name>` socket, so the session the journey just created is enumerated
by `sessions list --json` for real — which also means **the fixture image must
be rebuilt** (`up -d --build agents`) after changing those shims, and that a
journey run leaves its `j04-*` sessions behind (each test kills its own before
creating, so a re-run is still deterministic).

The port is read from the `agentsPort` instrumentation argument and defaults to
2222 (`AgentsFixture` in `app2/src/androidTest`), so `--pool` works unchanged.
There is no CI emulator lane for app2 yet — that arrives with rewrite task U-4;
until then `.github/workflows/app2.yml`'s unit job compiles the androidTest
source set so the journey cannot rot unnoticed.

### Short app-switch reconnect proof

Issue #548/#450/#577/#392/#177 has a focused emulator harness for the reported
"switch away for 5-10 seconds, return to reconnect/disconnect" path:

```bash
scripts/reconnect-app-switch.sh
```

The script starts or reuses the local AVD, starts the deterministic Docker
`agents` SSH target, installs the debug app/test APKs, and runs only:

```bash
com.pocketshell.app.proof.BackgroundGraceReconnectE2eTest#sixSecondAppSwitchWithProductionGraceDoesNotShowOrRecordReconnect
```

That connected test backgrounds the real `MainActivity` for six seconds under
the production background grace window, foregrounds it, and asserts the tmux
session stays connected with no visible `Connecting`, `Reconnecting`,
`Disconnected`, `Tap Reconnect`, disconnect band, or reconnect/reattach
diagnostic inside the short settle TTL. Artifacts land under
`build/reconnect-app-switch/<run-id>/`, including terminal viewport PNGs,
visible-terminal sidecars, timings, Docker logs, instrumentation output, and
logcat.

If an emulator and the Docker `agents` service are already running, the focused
wrapper equivalent is:

```bash
scripts/connected-test.sh --suffix i548 \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.BackgroundGraceReconnectE2eTest#sixSecondAppSwitchWithProductionGraceDoesNotShowOrRecordReconnect
```

### Local phone walkthrough reproduction

For fast visual feedback without installing an APK on a physical phone, run the
local phone-walkthrough harness against an already-booted emulator:

```bash
scripts/phone-walkthrough.sh terminal-lab
```

The harness verifies the explicit SDK paths from `AGENTS.md`, fails clearly if
no booted emulator is connected, starts/verifies the Docker `agents` SSH
fixture, builds and installs the debug app/test APKs, runs only the selected
scenario, and writes one artifact bundle under
`build/phone-walkthrough/<run-id>/`.
By default it uses `build/phone-walkthrough/gradle-home` as an isolated
`GRADLE_USER_HOME`, disables the Gradle build cache and parallel execution for
the APK build, and removes the app module's generated build output directory so
stale KSP/Hilt/Javac transaction state cannot be reused. Set
`PHONE_WALKTHROUGH_CLEAN_GENERATED=0` only when investigating those generated
outputs directly.

The first supported scenario is `terminal-lab`. It opens the isolated terminal
lab activity, connects from the emulator to Docker SSH, sends commands through
the terminal input path, captures named screenshots, records transition timing,
and collects bounded logcat, instrumentation output, Docker logs, command
timings, and crash diagnostics. Use `BUILD_APKS=0` to reuse existing debug APKs
when iterating on harness behavior:

```bash
BUILD_APKS=0 scripts/phone-walkthrough.sh terminal-lab
```

For terminal reviewer approval, use the stricter terminal workbench commands in
[docker-emulator-runbook.md](docker-emulator-runbook.md#standard-commands) and
the artifact rejection checklist in [process.md](../process.md#terminal-artifact-review).
Direct terminal viewport renders plus visible terminal text are authoritative;
full-device screenshots are advisory for terminal content unless the run summary
proves they are reliable.
The workbench deletes stale pulled artifacts before each run, verifies SSH,
terminal command input, PTY sizing, direct viewport renders, visible terminal
sidecars, timings, and summary hashes, and fails on missing, blank, duplicate
non-hold, or contradictory authoritative terminal evidence. Set
`REAL_AGENTS=1` when the issue requires real interactive agent CLI screens.

The host setup matrix is available through the same harness. It starts the
bootstrap Docker services on ports `2230` through `2236`, drives the emulator UI
for each profile, and stores per-profile screenshots, UI assertion output,
remote probes, timings, logcat, Docker logs, and crash diagnostics:

```bash
scripts/phone-walkthrough.sh setup-detection
scripts/phone-walkthrough.sh setup-detection:uv-install
```

##### Parallel setup-detection across multiple emulators (issue #632)

The 7 setup-detection profiles bind **disjoint** Docker ports (`2230`-`2236`),
so the matrix can be sharded across several emulators and run concurrently
instead of serially. `scripts/parallel-setup-detection.sh` is a fan-out/join
wrapper that does exactly that:

```bash
# Two emulators, full matrix; profiles split round-robin across the two serials.
scripts/parallel-setup-detection.sh --serials "emulator-5554 emulator-5556"

# Print the shard plan only (serial / lock / compose project per shard) — no
# emulator or Docker is touched.
scripts/parallel-setup-detection.sh --dry-run --serials "emulator-5554 emulator-5556"

# Subset of profiles, capped shard count.
scripts/parallel-setup-detection.sh --serials "a b c" --shards 2 ready unsupported
```

Each shard is isolated so concurrent shards never collide:

- **emulator** — a distinct `ANDROID_SERIAL` per shard.
- **AVD lock** — a per-serial lock file
  (`build/.avd-lock-<serial>`, via `pocketshell_avd_lock_file_for_serial` in
  `scripts/lib/avd-lock.sh`) instead of the single global `build/.avd-lock`, so
  shards do not serialise against each other. Callers that don't opt in keep the
  single-lock default.
- **Docker** — a per-shard `COMPOSE_PROJECT_NAME`
  (`pocketshell-setup-detection-shard<i>`). The 7 bootstrap services in
  `tests/docker/docker-compose.yml` deliberately carry **no** `container_name:`
  so compose namespaces their containers per project
  (`<project>_bootstrap-<scenario>_<n>`); a fixed name would be global to the
  daemon and defeat the per-shard project. Fixtures are addressed by host port,
  never by container name.

The APKs are built **once** before fan-out (shards run with `BUILD_APKS=0` and
`PHONE_WALKTHROUGH_CLEAN_GENERATED=0`) so no two shards race on the shared
`app/build/` directory. The wrapper aggregates per-profile pass/fail into
`build/parallel-setup-detection/<run-id>/summary.txt`, writes the same
per-profile artifacts the sequential path produces under each
`shard<i>/phone-walkthrough/setup-detection/`, and exits non-zero if any
profile failed. With a single serial it collapses to one sequential shard — the
existing single-AVD behaviour, no regression.

The release gate (`scripts/release-emulator-validation.sh`) opts in via
`SETUP_DETECTION_SHARDS=N` (default `1` = the unchanged sequential path);
provide the serials with `SETUP_DETECTION_SERIALS="<s1> <s2> ..."`.

For release visual review without a physical phone, run:

```bash
scripts/phone-walkthrough.sh visual-audit
```

This runs the Docker-backed visual screenshot instrumentation and the composer
state renderer, then writes reviewer-facing PNGs under
`build/phone-walkthrough/<run-id>/screenshots/visual-audit/`. The normalized set is
`01-host-list.png`, `02-host-setup-folder-list.png`,
`03-terminal-session-input-controls.png`, `04-snippets.png`,
`05b-composer-idle-draft.png`, `06-composer-recording.png`, and
`07-composer-transcribing.png`; raw pulled device output remains under
`build/phone-walkthrough/<run-id>/device-artifacts/walkthrough-visual-pass/`.

This differs from CI and the pre-release confidence gate: it is a local
reproduction loop for one walkthrough journey and reviewer-visible artifacts. It
does not replace unit tests, connected CI, or the slower release gate. A
physical phone is not required for basic release confidence; emulator + Docker
evidence is the release blocker, and phone testing is final user acceptance.

### APK pre-release confidence gate

Before tagging a release APK, run the documented local gate from
the repository root:

```bash
scripts/pre-release-confidence-gate.sh
```

For an actual release tag, the confidence gate is only the first step. Run the
guarded emulator-only release validation from clean pushed `main`, after
confirming `HEAD == origin/main`:

```bash
scripts/release-emulator-validation.sh
```

That wrapper requires `HEAD == origin/main`, then runs the confidence gate,
`scripts/phone-walkthrough.sh terminal-lab`,
`scripts/phone-walkthrough.sh tmux-existing-session`,
`scripts/phone-walkthrough.sh setup-detection`, and visual-audit screenshot
capture. It writes `build/release-emulator-validation/<run-id>/summary.md`
with the artifact directories that must be attached or linked in the issue and
tag notes.

A GitHub Actions Release Emulator Validation summary is acceptable release
evidence only when its `Commit SHA` is the commit being tagged. If the run was
on a release branch and the merge to `main` changes the SHA, rerun validation
on `main`.

Push the tag only through:

```bash
scripts/push-release-tag.sh --visual-audit-inspected <tag> build/release-emulator-validation/<run-id>/summary.md
```

Use `--visual-audit-inspected` only after reviewing the visual-audit
screenshots. Physical phone testing is final user acceptance only; it does not
replace the emulator/Docker release blockers above.

#### Developer fast path (scoped by changed area)

For the local pre-merge loop on a small, single-area change, the developer-only
fast path runs only the emulator stages relevant to what changed:

```bash
scripts/dev-fast-gate.sh --dry-run            # classify + print the plan, no emulator
scripts/dev-fast-gate.sh                       # run the scoped stages
scripts/dev-fast-gate.sh --profile fish-user-local-path   # scope setup-detection
```

It diffs the branch against the `origin/main` merge base, maps the changed
paths to a minimal stage set, and calls the existing building blocks directly
(`scripts/phone-walkthrough.sh <scenarios>` and/or
`scripts/pre-release-confidence-gate.sh`).

Since #2063 the mapping is **data, not inline case arms**: it reads the
`devgate` column of `scripts/test-areas.txt` through
`scripts/lib/test-areas.sh`, so the local fast path and CI area selection can no
longer disagree about what a path is. `devgate` is a per-ROW column rather than
a per-AREA one precisely because the old arms split some areas across stages
(`shared/core-ssh` was `terminal` while `shared/core-connection` fell through to
force-full), and that is what let the refactor keep every decision unchanged.
`scripts/dev-fast-gate-parity-selftest.sh` re-runs the original case arms
alongside the manifest over every tracked file and asserts (a) no path became
less conservative and (b) the paths that became MORE conservative are exactly a
pinned, reasoned set — the `TmuxSessionViewModel.kt` seam, the Docker fixtures,
`shared/test-support`, `app/.../layout/`, test-infrastructure files, and
markdown. Mapping (default-to-full when in doubt):

| Changed area | Stages run |
|---|---|
| UI-only (`shared/ui-kit/**`, `app/src/main/**/projects/**`) | `visual-audit` + `terminal-lab` |
| bootstrap / setup-detection (`**/bootstrap/**`, `tests/docker/**bootstrap**`) | `setup-detection` (or `setup-detection:<profile>` via `--profile`) |
| terminal / SSH / tmux (`shared/core-terminal/**`, `core-ssh`, `core-tmux`, terminal render path) | `terminal-lab` + `tmux-existing-session` |
| Room schema / migrations / install-update | `pre-release-confidence-gate` |
| build files (`*.gradle*`, `gradle/**`, `gradle.properties`, `settings.gradle*`), `scripts/**`, `.github/**`, any DB/migration file, an unmatched path, or a multi-area diff | **full set** (every building block) |

**`dev-fast-gate.sh` is NOT a release gate.** It never invokes
`scripts/release-emulator-validation.sh` and never writes a
`build/release-emulator-validation/<run-id>/summary.md`, so it cannot produce a
taggable summary. Release tags still require the full
`scripts/release-emulator-validation.sh` run and `scripts/push-release-tag.sh`
(which checks the summary's `Commit SHA` and `Automated status: PASS` against
the tagged `origin/main` SHA). Use the fast path to iterate; never as release
evidence.

#### AVD lock for parallel-worktree contention

The release-gate scripts that touch the shared local Android emulator
(`scripts/release-emulator-validation.sh`,
`scripts/pre-release-confidence-gate.sh`,
`scripts/phone-walkthrough.sh`,
`scripts/terminal-workbench.sh`, and
`scripts/release-terminal-gate.sh`) each acquire an exclusive `flock` on
`build/.avd-lock` (relative to the repo root) before installing APKs or
running instrumentation. If a sibling worktree is already running an
emulator-touching gate, the second invocation prints
`Another emulator-touching script holds the AVD lock; waiting...` and
blocks until the first one exits. The lock is released automatically when
the holding script exits (the open file descriptor closes).

Direct `./gradlew :app:connectedDebugAndroidTest` invocations from implementer
or reviewer worktrees do not take the AVD lock or cgroup scope and should not be
used for local evidence. Use `scripts/connected-test.sh` for ad-hoc connected
tests; it owns the lock/suffix/serial-pin/cgroup path. Some legacy broad
harnesses still invoke Gradle internally, but new reusable local paths should
call the wrapper or `scripts/cgroup-run.sh` rather than teaching raw connected
Gradle.

To override the lock-file path (rare; only useful when chaining gates by
hand under a custom build directory):

```bash
POCKETSHELL_AVD_LOCK_FILE=/tmp/my-avd-lock scripts/release-emulator-validation.sh
```

When one gate script invokes another (for example,
`release-emulator-validation.sh` runs `pre-release-confidence-gate.sh`,
`phone-walkthrough.sh`, and `terminal-workbench.sh` in sequence) the inner
scripts inherit `POCKETSHELL_AVD_LOCK_ACQUIRED=1` from the outer one and
skip re-acquiring; the outer lock holds for the entire chain.

Terminal-heavy release candidates can opt into the slower real-agent terminal
release gate:

```bash
TERMINAL_RELEASE_GATE=1 scripts/release-emulator-validation.sh
```

The optional step runs after the normal pre-release confidence gate and before
the rest of the release evidence. It starts
`tests/docker/real-agent/compose.yml`, SSHes from the emulator into Docker on
port `2240`, drives at least one real interactive agent CLI screen through
`TerminalLabDockerTest`, validates the authoritative viewport and visible-text
artifacts, and writes
`build/terminal-workbench/<run-id>-terminal-release/artifact-summary.txt`. It
is manual/optional unless explicitly enabled through the environment or the
GitHub Actions workflow input.

For terminal/tmux-heavy releases where short connected tests are not enough
evidence, add the opt-in 10-minute stability hold:

```bash
TERMINAL_RELEASE_GATE=1 LONG_RUNNING_TEST=1 scripts/release-emulator-validation.sh
```

This remains optional for unrelated small releases. The long-running hold writes
its artifact bundle under
`build/long-running-session/<run-id>-long-running/`; the primary file to inspect
and link is
`build/long-running-session/<run-id>-long-running/artifacts/long-running-session/long-running-summary.txt`.
Treat the hold as acceptable only when the wrapper passes, the summary reports
`tick_count=6`, `reconnect_events=0`, `memory_growth_kb` under the recorded
50 MB budget, and the final visible transcript still contains the last tick.
Failures should be evaluated from `long-running-summary.txt` first, then the
same directory's `long-running-logcat-tail.txt`,
`long-running-visible-terminal.txt`, `instrumentation.log`, and
`docker-agents.log`.

#### Real-agent CLI interaction test (issue #146)

When `TERMINAL_RELEASE_GATE=1` is set, the release validation also runs
`RealAgentReleaseGateTest`
(`app/src/androidTest/java/com/pocketshell/app/proof/RealAgentReleaseGateTest.kt`)
against the same `tests/docker/real-agent/compose.yml` fixture. The test:

- Connects through the real PocketShell app UI to `testuser@10.0.2.2:2240`,
  attaches a tmux pane, and types commands through the same `TerminalView`
  input connection the phone user hits.
- Invokes the actual installed `claude --print '<prompt>'` and
  `codex exec --skip-git-repo-check '<prompt>'` binaries inside the tmux pane
  (Claude Code 2.x and Codex CLI 0.x via the fixture's `Dockerfile`). The
  real-agent image deliberately ships without API credentials, so the
  deterministic visible substrings the test matches against are the CLI-emitted
  startup texts — `Not logged in` for Claude and `OpenAI Codex v` for Codex —
  using `TerminalTextMatcher.containsWrapTolerant` so the soft-wrap at the
  Compose grid boundary does not flake the assertion.
- Reads the JSONL conversation log back over SSH from
  `~/.claude/projects/<encoded-cwd>/<session-id>.jsonl` and
  `~/.codex/sessions/<YYYY>/<MM>/<DD>/rollout-<ts>-<session>.jsonl`, then
  asserts on a minimal schema (`sessionId` field for Claude; `session_meta`
  payload with `id`/`cwd` for Codex). This is the load-bearing JSONL contract
  PocketShell's
  [com.pocketshell.app.session.AgentConversationRepository](../app/src/main/java/com/pocketshell/app/session/AgentConversationRepository.kt)
  parses, so a CLI version bump that broke the schema would surface here.

The test is opt-in via the instrumentation runner argument
`pocketshellRealAgentReleaseGate=1`, set automatically by
`scripts/release-emulator-validation.sh` when `TERMINAL_RELEASE_GATE=1`. Without
the argument the test class is skipped by `Assume.assumeTrue`, so normal
`connectedDebugAndroidTest` runs and the default release gate are unaffected.
Artifacts (instrumentation log, Docker compose log, SSH readiness probe,
emulator logcat) are written under
`build/real-agent-release-gate/<run-id>-real-agent-release-gate/`.

To run it locally (a booted emulator and the real-agent Docker image are both
required):

```bash
REAL_AGENTS=1 TERMINAL_RELEASE_GATE=1 scripts/release-emulator-validation.sh
```

`REAL_AGENTS=1` is consumed by the underlying `scripts/terminal-workbench.sh`
step; `TERMINAL_RELEASE_GATE=1` opts both the workbench step and the new
`RealAgentReleaseGateTest` step in. To exercise the test in isolation against a
running emulator + real-agent fixture without the rest of the release gate:

```bash
docker compose -f tests/docker/real-agent/compose.yml up -d --build real-agents
scripts/connected-test.sh --suffix realagent \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.RealAgentReleaseGateTest \
  -Pandroid.testInstrumentationRunnerArguments.pocketshellRealAgentReleaseGate=1
```

The same validation can be run manually from GitHub Actions when local emulator
capacity is unavailable: Actions -> Release Emulator Validation -> Run
workflow. Choose the release branch or `main`; optionally provide a `run_id`.
Read the job summary first, then download the
`release-emulator-validation-<run-id>` artifact for logs, screenshots, and the
release summary. The tested debug APK is included inside that artifact at
`release-emulator-validation/<run-id>/app-debug.apk`; locally, the same file is
written under `build/release-emulator-validation/<run-id>/app-debug.apk`.
Inspect the visual-audit screenshots before using the artifact as release
evidence. This workflow produces evidence only; it does not push the tag and
does not relax the stable-main tag rule.

This combines the normal compile/unit check, deterministic Docker `agents`
target verification, explicit-path emulator readiness checks, focused connected
walkthrough journeys for keyboard/input, snippets/composer, dictation, planner, and
Docker SSH/tmux smoke, then builds and installs
`app/build/outputs/apk/debug/app-debug.apk` on the emulator. Logs are written
under `build/pre-release-confidence-gate/<run-id>/`. By default the gate also
uses `build/pre-release-confidence-gate/gradle-home` as an isolated
`GRADLE_USER_HOME`, so unrelated local Gradle daemon/cache activity cannot stop
or corrupt the scripted run. Gate Gradle invocations use the shared
release-chain profile from `scripts/lib/gradle-profile.sh` — `--no-build-cache`,
`--no-parallel`, `--max-workers=1`, `-Dorg.gradle.jvmargs=-Xmx3072m`,
`-Pkotlin.daemon.jvmargs=-Xmx3072m`, inside a `POCKETSHELL_TEST_MEM=24G` build
scope — to avoid cache-packing, generated-source races, local resource
oversubscription, and the Kotlin/packaging heap exhaustion that killed three
v0.4.42 release runs before any assertion ran (issue #2054). Check a machine's
profile in milliseconds with
`scripts/pre-release-confidence-gate.sh --check-profile`. The
compile/check phase pre-generates focused app KSP/Hilt sources for debug,
release, androidTest, and unit-test variants before `check`, which keeps lint
from depending on stale generated files in the checkout without building a full
release APK inside the fast gate. Lint is excluded from this local pre-release gate
so unrelated dirty-worktree lint issues cannot prevent the install and focused
instrumentation checks from running; run lint separately before release when the
checkout is clean.
By default the gate also copies the current working tree to
`build/pre-release-confidence-gate/<run-id>/worktree` and re-execs there,
excluding `.git`, `.gradle`, and `build` directories. That keeps shared
`app/build` output from unrelated local work out of the release gate while still
testing the current source files. Set `GATE_ISOLATED_WORKTREE=0` only when the
checkout is otherwise idle.
Every run also writes
`build/pre-release-confidence-gate/<run-id>/summary.txt`, including the commit,
run directory, APK path, emulator serial when available, Docker target,
step-by-step status/log paths, focused selector status, the focused walkthrough
cold-reset install status, the final data-preserving update install status, and
the final pass/fail result. On failures, start review from that summary: it
names the failing step and, for focused instrumentation failures, the diagnostics
and bounded logcat artifact paths.

The focused app walkthrough selectors run through direct
`adb shell am instrument -e class <selector>` invocations after one app/test
package reset and one explicit app/test APK install for the whole focused phase.
This is a destructive cold-reset path for deterministic walkthrough tests, not
the user update path. It makes the gate repeatable on a reused emulator, avoids
stale Gradle connected-test runner arguments, and keeps package
deletion/replacement work out of the selector window.
The cold-reset setup clears existing package data without uninstalling in the
normal path, then replace-installs both APKs and waits for package-manager
handlers to go idle. Uninstall is only used as a logged fallback for incompatible
existing packages in that cold-reset setup. After install, the gate watches a
stability window for delayed PocketShell package removal broadcasts from earlier
emulator work and reinstalls before instrumentation if one appears. The gate then
force-stops app/test packages before each selector and waits until no PocketShell
process is running and both packages report
`stopped=true`, followed by a short stable settle window. If Android restarts
the app/test package during that settle window due prior instrumentation
teardown, the gate repeats the force-stop/idle/settle cycle up to three times.
That keeps delayed
package deletion, the quiesce force-stop itself, Android's normal `start instr`
force-stop, prior selector teardown, and any restored task cleanup from killing
the running instrumentation process. Each focused invocation clears logcat; if
Android reports a process-crashed instrumentation result with no app exception
and logcat shows the app was externally force-stopped while instrumentation was
running, the selector is retried once after another package-manager idle wait.
If the retry also fails, or the failure is not that exact transient shape, the
gate keeps the final failure. If
instrumentation crashes or reports a non-success code, the step log includes
filtered crash context and points to the bounded full logcat artifact in the same
run directory.

The final APK install in the pre-release gate is the data-preserving update gate:
`scripts/install-update-apk.sh app/build/outputs/apk/debug/app-debug.apk`. That
helper runs exactly `adb install -r <apk>` and intentionally has no `pm clear`,
uninstall fallback, or cold-install flags.

See [docker-emulator-runbook.md](docker-emulator-runbook.md#apk-pre-release-gate)
for the exact steps, SDK paths, focused test list, APK location, and slower
opt-in suites that remain outside the fast gate.

### Opt-in end-to-end scenario suites

Some workflows need real app UI plus multiple remote-host states, but are too
slow and stateful for every issue. These live as opt-in scenario suites:
automated, repeatable, and documented, but run manually when the issue scope
requires them, before releases, or while investigating regressions.

Scenario suites should follow these rules:

- Use Docker host profiles/containers, never real hosts or private keys.
- Drive the Android app through emulator UI when the behavior is user-facing.
- Support running one scenario by name and running the full suite.
- Keep fast CI green without requiring the full suite on every push.
- Clean up remote files, tmux sessions, and containers after each scenario.
- Record the exact command for each scenario in this document.

The first suite is host setup/bootstrap. It should cover at least:

- `ready`: all tools and the daemon are already available; no install prompt.
- `uv-install`: tools are missing but `uv` is available; install succeeds.
- `unsupported`: tools are missing and no installer is available; clear manual
  setup state.
- `daemon-disabled`: tools are present but the jobs daemon is disabled; only
  daemon enablement is offered.
- `user-local-path`: tools are installed under user-local directories that are
  absent from a default non-login SSH `PATH`; detection still succeeds.

Bootstrap PATH precedence: the probe first asks the remote user's configured
interactive shell for its rc-derived PATH (`.bashrc` for bash, `.zshrc` for
zsh, fish config for fish, `.profile` for POSIX fallback). It then prepends
PocketShell's default user-bin locations (`$HOME/.local/bin`, `$HOME/bin`,
`$HOME/.cargo/bin`) before running `command -v` and install commands. Those
defaults win over rc-derived entries, and rc additions win over the remote
SSH daemon's bare non-login PATH. There is no manual Extra PATH directories
field in the app.

The false-positive setup bug is tracked in #70. The reusable opt-in scenario
suite is tracked in #71.

### Network fault resilience suite

The network fault proofs are opt-in connected Android instrumentation tests.
They require a booted emulator plus the Docker `agents` target behind
Toxiproxy. Default CI does not start these services, and the tests also skip
when the instrumentation process detects CI, so run them manually for reviewer
evidence or after explicitly changing the workflow.

A `--pool` run of a fault-class journey (issue #2128) does **not** use the
shared 2228/8474 singleton: `scripts/connected-test.sh --pool` brings up a
per-lane proxy on ports derived from the claimed agents port
([NetworkFaultPorts] / `scripts/lib/agents-pool.sh`). `--no-pool` and the
commands below still start the historical shared fixture.

Start the ride-through fixture:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build agents network-fault-proxy
```

For the full network-fault proof family, including packet loss, also start the
netem proxy:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build \
  agents network-fault-proxy packet-loss-proxy
```

Run the issue #552 ride-through proof:

```bash
scripts/connected-test.sh --suffix netfault \
  -Pandroid.testInstrumentationRunnerArguments.pocketshellNetworkFaultProofs=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.proof.RideThroughInterruptionE2eTest
```

To isolate one case, append `#briefLinkCutRidesThroughWithoutDisconnectOrTeardown`
or `#sustainedLinkCutReconnectsCleanlyWithoutHang` to the class selector. The
brief case uses a non-closing Toxiproxy timeout toxic for a short byte-starved
link; the sustained case disables the proxy long enough to force an explicit
reconnect. Summaries are written under
`/sdcard/Android/media/com.pocketshell.app/additional_test_output/issue342-network-faults/`.

The Toxiproxy request-shape unit test is part of the normal debug JVM suite and
does not need Docker, an emulator, or an unstable network:

```bash
./gradlew :app:testDebugUnitTest --tests com.pocketshell.app.proof.ToxiproxyControlTest
```

### Host setup/bootstrap scenario suite

The bootstrap suite is implemented as opt-in Android instrumentation tests
against five deterministic Docker SSH hosts. It seeds a disposable host in the
app database, launches PocketShell, taps the host row, and asserts the visible
setup sheet/action state for each profile. Direct SSH inside the suite is
limited to pre/post scenario reset and post-action probes. It is skipped unless
the instrumentation argument is set, so normal `connectedDebugAndroidTest` runs
do not need these containers.

Start all bootstrap host profiles:

```bash
docker compose -f tests/docker/docker-compose.yml up -d --build \
  bootstrap-ready \
  bootstrap-uv-install \
  bootstrap-uv-upgrade \
  bootstrap-unsupported \
  bootstrap-daemon-disabled \
  bootstrap-user-local-path \
  bootstrap-fish-user-local-path
```

Run the whole opt-in suite on an already-running emulator:

```bash
scripts/connected-test.sh --suffix bootstrap \
  -Pandroid.testInstrumentationRunnerArguments.pocketshellBootstrapScenarios=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.bootstrap.HostBootstrapScenarioSuiteTest
```

Run one scenario by name:

```bash
scripts/connected-test.sh --suffix bootstrap \
  -Pandroid.testInstrumentationRunnerArguments.pocketshellBootstrapScenarios=true \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.bootstrap.HostBootstrapScenarioSuiteTest#uvInstall
```

Scenario-to-service mapping:

| Scenario | Service | Host port |
|---|---|---|
| `ready` | `bootstrap-ready` | `2230` |
| `uv-install` | `bootstrap-uv-install` | `2231` |
| `unsupported` | `bootstrap-unsupported` | `2232` |
| `daemon-disabled` | `bootstrap-daemon-disabled` | `2233` |
| `user-local-path` | `bootstrap-user-local-path` | `2234` |
| `fish-user-local-path` | `bootstrap-fish-user-local-path` | `2235` |
| `uv-upgrade` | `bootstrap-uv-upgrade` | `2236` |

Cleanup:

```bash
docker compose -f tests/docker/docker-compose.yml down --volumes --remove-orphans
```

The mutable `uv-install` and `daemon-disabled` scenarios reset their remote
state before and after each test, so running a single scenario repeatedly
against the same containers starts from the documented pristine profile.

---

## Real LLM assistant loop tests

`AssistantAgentLoopRealLlmTest` is an opt-in JVM integration test for the
in-app assistant's product-level structured action output. The test class is
excluded from the normal app unit-test tasks, so `./gradlew test`, release
gates, and CI do not call external model providers. Run the dedicated task and
set `POCKETSHELL_REAL_LLM_TESTS=1` explicitly:

```bash
POCKETSHELL_REAL_LLM_TESTS=1 ./gradlew :app:realLlmTest
```

The test reads provider credentials only from the PocketShell repo root `.env`.
It does not read sibling repos. It also ignores `app/.env`, `.env.local`,
shell history, and unrelated dotenv files. Do not commit `.env` with real keys.
Values are never printed by the test, and skip messages name only missing
variable names.

When running from a git worktree, put `.env` in that worktree root. The
harness intentionally does not read the primary checkout or any parent
directory.

ZAI is the primary target provider. The implementation uses the
Anthropic-compatible Messages wire format for ZAI, but this is only a wire
protocol detail. Put keys and optional overrides in the repo root `.env`:

```bash
ZAI_API_KEY=...
ZAI_MODEL=glm-4.6
ZAI_BASE_URL=https://api.z.ai/api/anthropic/v1
```

The same scenario also covers the Anthropic-compatible configuration slot used
for ZAI-compatible endpoints. `ZAI_API_KEY` / `ZAI_BASE_URL` / `ZAI_MODEL`
take precedence when both sets are present; `ANTHROPIC_API_KEY` /
`ANTHROPIC_BASE_URL` / `ANTHROPIC_MODEL` are accepted as aliases for the same
ZAI Anthropic-compatible Messages endpoint:

```bash
ANTHROPIC_API_KEY=...
ANTHROPIC_BASE_URL=https://api.z.ai/api/anthropic/v1
ANTHROPIC_MODEL=glm-4.6
```

For compatibility with Z.AI's Claude Code examples, the client also accepts
`https://api.z.ai/api/anthropic` and targets `/v1/messages` internally.

OpenAI coverage uses the OpenAI wire client and defaults from
`AssistantSettings`:

```bash
OPENAI_API_KEY=...
OPENAI_MODEL=gpt-4o
OPENAI_BASE_URL=https://api.openai.com/v1
```

Scenario catalog:

| Scenario | Providers | Expected structured tools |
|---|---|---|
| `openAi_llmZoomcampEmojiSequence_callsExpectedTools` | OpenAI when `OPENAI_API_KEY` is present | exact user request `убери все эможди в ллм зумкампе` resolves to `llm-zoomcamp`, starts `codex`, and sends normalized prompt `убери все эмоджи` |
| `zai_llmZoomcampEmojiSequence_callsExpectedTools` | ZAI when `ZAI_API_KEY` or `ANTHROPIC_API_KEY` is present | exact user request `убери все эможди в ллм зумкампе` resolves to `llm-zoomcamp`, starts `codex`, and sends normalized prompt `убери все эмоджи` |
| `zai_llmZoomcampEmojiSequence_revisesAfterCorrection` | ZAI when `ZAI_API_KEY` or `ANTHROPIC_API_KEY` is present | first `send_prompt_to_session` candidate is corrected in a continued dialogue, then the model emits and executes a revised `send_prompt_to_session` for the same `llm-zoomcamp` session |

The assertions inspect model tool calls and executed fake actions, not prose:
tool names, ordering, and important arguments are checked. The model must look
up the project before choosing it, start `codex` in the expected project
directory, and call `send_prompt_to_session` with the requested task prompt.
The current project target is `/home/dev/projects/llm-zoomcamp`. The fake
actions used by the tests never open SSH sessions or execute shell commands.

---

## Test reliability — the one de-flake convention (issue #1048)

The recurring "passes-locally / flakes-on-CI" JVM failure (the SshLease abort,
the codexScale/codexLike output-flood drain, the oversubscription siblings) is
ONE narrow class: a `runTest` virtual clock drives code whose owned background
work runs on a REAL dispatcher / Android `Handler`/`Looper` / raw `Thread` not
pinned to the test scheduler, so `runCurrent()`/`advanceUntilIdle()` returns
before the real thread finishes and CI CPU contention loses the race.

**Rule:** in a `runTest` test, every owned background hop of the
code-under-test must resolve on the test scheduler; if the work is intrinsically
wall-clock (Android Handler/Looper/Thread), drive it with a hard-failing,
generously-bounded pump whose load-bearing assertion is the pump's exit
condition.

- **Shape A (default) — pinnable seam:** production exposes an injectable
  `CoroutineContext`/`Dispatcher` (+ `nowMillis` when timing matters); tests
  inject `StandardTestDispatcher(testScheduler)` for EVERY owned scope.
  Reference: `SshLeaseAcquireBoundCharacterizationTest.kt:191-219` /
  `SshLeaseManager.kt:63,79,80`. The deliberate real-`Dispatchers.IO` exception
  (blocking cleanup off the test thread) must be commented.
- **Shape B — wall-clock-bounded pump** (only when the worker is an Android
  Handler/Thread, e.g. `SshTerminalBridge`): loop `advanceUntilIdle()` +
  `shadowOf(Looper.getMainLooper()).idleFor(16ms)` + small sleep to a
  `System.currentTimeMillis()` deadline that HARD-FAILS; assert the exit
  condition, never the loop body. **Do not hand-roll the loop** — call the ONE
  audited `drainMainLooperUntil` below and inject the per-tick drain. Reference:
  the codex pump (`TmuxSessionViewModelTest.kt:5602-5657`) and
  `TmuxSessionWarmOpenTest.pumpUntil` (a virtual-clock drain injected as
  `onTick`).

**One shared Shape-B settle-pump (`drainMainLooperUntil`, #1048 criterion M).**
The historically drifting, hand-rolled Shape-B pumps now converge on ONE
audited helper in the test-only module `:shared:test-support`
(`shared/test-support/src/main/java/com/pocketshell/testsupport/SettlePump.kt`),
consumed via `testImplementation(project(":shared:test-support"))`. The helper
owns the load-bearing invariants — a generous wall-clock deadline, a HARD
`false` return on timeout (the caller `assertTrue`/​`throw`s on it, #1102), and it
NEVER touches a clock itself (no kotlinx virtual-clock advance — the #1110/#793
watchdog trap). The per-tick drain is injected because the callers' drains are
genuinely different and must NOT be forced into one: `awaitCondition` idles
NOTHING and only `runCurrent()`s (idling would risk the #793 re-seed watchdog);
the SshTerminalBridge-fed flood pumps `idleFor(16ms)` + `runCurrent()` for the
#803 frame-paced drain; `SshTerminalBridgeTest` has no `TestScope` so idles the
looper only.

**A clock-ADVANCING drain is allowed — inject it as `onTick` (#2017).** The
"never touches a clock" invariant is a property of the shared helper's BODY, not
a ban on callers: a call site whose genuine per-tick drain is `advanceUntilIdle()`
(bridging the virtual clock to a real `Dispatchers.IO` continuation) passes it as
`onTick`. #1048 originally read that invariant as a carve-out and left
`TmuxSessionWarmOpenTest.pumpUntil` — and its copy in
`Issue1574DeadReconnectTest` — hand-rolled; both then carried a **5 s** real-time
budget, which a contended box exhausts while the awaited continuation is merely
unscheduled, so those classes red only under load (indistinguishable from a real
regression, and the "just re-run it" reflex manufactures a green streak because
Gradle skips a *passing* test task on re-run). Both are migrated. The single
generous budget now lives in `GENEROUS_SETTLE_DEADLINE_MS` (30 s) as the
`deadlineMs` default — **prefer the default; do not introduce a per-file
constant, and never "fix" a contention timeout by nudging a local number up.**
The one pump that genuinely cannot use the helper is
`PromptComposerOutboundSendQueueViewModelTest.advanceSchedulerUntil`: its
predicate is `suspend` and each tick re-checks it four times between distinct
clock/dispatcher nudges, so its loop body is load-bearing rather than a drain. It
stays separate, by design.

**Banned:** a single `advanceUntilIdle()`+`idle()` then assert on real-thread
output; a bare fixed `Thread.sleep(N)` as the only sync before a load-bearing
assert.

`scripts/check-test-validity.sh` carries an advisory `TIMING1` smell scoped to
the connection/terminal test roots (`core-ssh`, `core-tmux`, `core-connection`,
the app `tmux`/`connectivity` test dirs, and — widened in #1048 to the areas
that actually flaked this class — the app `composer` (#1102), `hosts` (#1110),
and `projects` test dirs): it flags a `runTest` test that
touches a real dispatcher/thread (`Dispatchers.IO`/`Dispatchers.Default`/
`Executors.new`/`Thread.sleep`/`Thread(`/`CountDownLatch`) WITHOUT (a) a
`StandardTestDispatcher`/`UnconfinedTestDispatcher` seam, (b) the bounded-pump
signature (`idleFor(` + a `System.currentTimeMillis()`/`System.nanoTime()`
deadline loop), or (c) an inline `// JUSTIFIED:` opt-out. Current matches are
baselined (advisory, the baseline only shrinks as tests adopt a seam); the lone
HARD-FAIL is the narrow NEW case — a `runTest` test with a bare small
`Thread.sleep(N)` immediately preceding its load-bearing assert and no bounded
loop.

---

## CI Unit lane: sizing and sharding (issues #760, #2060, #2069)

The required `Unit tests` check is the `unit-gate` aggregator over five jobs
(`unit` × 2 shards, `guards-static`, `guards-ci-harness`, `guards-test-selection`,
`dex`). This section is the long-form rationale that used to sit inline in
`.github/workflows/tests.yml`, which is close to the 128 KiB hygiene cap.

### Heap and worker caps (#760, #2060)

`#760`: the job occasionally failed with ALL visible tests PASSED — a
gradle-daemon/runner OOM abort, not an assertion failure. Unbounded gradle
worker fan-out plus an oversized test JVM heap pushes the runner into the
OOM-killer, which kills gradle mid-run *after* the tests reported green. Both
the worker count and the JVM heaps are bounded so the job stays inside the
runner's memory budget: the explicit `-Dorg.gradle.jvmargs` caps the gradle
daemon/launcher heap while the test forks inherit the project's modest `-Xmx`.
Infra robustness only — no test is removed, skipped or weakened.

`#2060`: #760 sized `--max-workers=2` for "7 GB RAM and 2 cores". That box is
gone (public-repo runners have been 4 vCPU / 16 GB since Jan 2024), so the job
ran at half of it. Raising it to 4 raises CONCURRENCY, not memory:
`org.gradle.parallel` stays false, so at most ONE `Test` task (one 1536m fork)
is alive at a time and only intra-task Worker API actions (AGP dexing/resources)
widen. `--parallel` / `maxParallelForks` WOULD multiply live forks and feed the
#708/#882 virtual-clock flake class, so they are deliberately NOT bundled. One
variable at a time — and note the AGENTS.md memory trap surfaces as a fake
`Backend Internal error: Exception during IR lowering`, not as an OOM.

### Why more workers is the wrong next knob (#2069)

Measured on [run 31319201177](https://github.com/alexeygrigorev/pocketshell/actions/runs/31319201177):
`--max-workers 2 -> 4` moved the Gradle test step **1141s -> 1084s, ~57s (5%)**.
The suite does not parallelize further on a 4-vCPU runner. Do not spend another
round on workers, `--parallel`, or `maxParallelForks`; the step is not
core-starved.

Where the time actually goes, derived from the `main` run 31339684629 result XML
plus its console timestamps (sum of per-task deltas = 1089s of a 1125s step):

| Bucket | Debug | Release | Variant-neutral |
| --- | ---: | ---: | ---: |
| Test execution | 398s | 376s | — |
| Build (compile/resources/KSP) | 114s | 177s | 25s |

`:app` alone is 285.4s + 283.9s of the 863.9s total test-case time (66%), across
24 test tasks / 12362 tests. Both halves are close to balanced, and nearly all
the non-test cost is variant-specific, so splitting by variant halves both.

### The split, and why two shards and not more

`unit` is a `strategy.matrix.variant: [Debug, Release]` job: one leg runs
`./gradlew testDebugUnitTest`, the other `testReleaseUnitTest`. It is a matrix
rather than two hand-written jobs on purpose — `needs.unit.result` aggregates
every leg, so the `unit-gate` three-list wiring (`needs:` / `env:` / the result
loop) is untouched and the #2067 silent-gate hazard cannot apply.

Two, not more, because the unit job stops being the critical path at that point:
`guards-ci-harness` runs 644–666s across recent `main` runs, so once `unit` is
near that it is the floor. A third or fourth shard would shrink `unit` further
and leave the lane exactly where two shards put it, while paying #2060's
measured ~38s per-job checkout/JDK/cache overhead each time. That is the knee.

### The coverage invariant, and how it is proved

`./gradlew test` ran everything; `testDebugUnitTest` + `testReleaseUnitTest` runs
everything **only while every module's test task is one of those two**. Today it
is (all 13 modules apply an Android plugin), and `./gradlew test --dry-run`
versus `./gradlew testDebugUnitTest testReleaseUnitTest --dry-run` differ by
exactly the 13 no-op `:module:test` lifecycle aggregators — no executing task is
lost. But one plain `kotlin("jvm")` module with tests would get a bare `test`
task that neither shard names, and its tests would stop running with the
required check still green.

Two guards hold the invariant, and neither is sufficient alone:

- `scripts/check-ci-unit-forced-execution.sh` — the workflow asks for both
  halves: the task argument is the matrix expression (not a hardcoded variant,
  which would make both legs run the same half) and the shard list is exactly
  `[Debug, Release]`. Its `--self-test` rejects 13 mutations, including a
  dropped shard, a duplicated shard, lowercase names, and a matrix belonging to
  a different job.
- `scripts/check-executed-test-counts.sh` — the two halves together are
  everything. `--variant Debug|Release` scopes each shard's expected-task set,
  and an unconditional **shard partition check** fails when a required module
  expects a task outside the two. Its `--self-test` asserts
  `union(Debug, Release) == the unsharded task set` and that the halves are
  disjoint, plus the plain-`test`-module red case.

---

## CI matrix

The `Tests` workflow separates the canonical full-suite gates from the slower
emulator and nightly lanes. For code changes, it runs the cheap gates on pull
requests and on `main`. The Docker and emulator jobs run on `main` pushes or
manual dispatches, not on every pull request.

### Canonical full-suite gates

- **JVM unit tests** — the `unit` job is a two-leg matrix, `variant: [Debug,
  Release]`. Each leg runs the complete variant task
  (`./gradlew testDebugUnitTest` or `./gradlew testReleaseUnitTest`) with
  forced execution (`--rerun-tasks --no-build-cache`), rather than a filtered
  class list. Execution-count and rolling-ledger checks prove that the
  expected test tasks actually ran. The required `Unit tests` aggregator also
  requires the static guards, CI-harness guards, test-selection guards, and
  DEX ratchet job.
- **Python utility tests** — `tools/pocketshell` runs its full `pytest` suite
  in the separate `Python utility tests (pocketshell)` check.
- **Docker-backed JVM integration** — the `Integration tests (Docker)` job
  runs all four integration tasks:

  ```text
  :app:integrationTest
  :shared:core-ssh:integrationTest
  :shared:core-portfwd:integrationTest
  :shared:core-tmux:integrationTest
  ```

  The execution guard verifies fresh JUnit results for the complete task set.
- **Per-push emulator journey subset** — on `main`/manual runs,
  `Emulator journey subset (load-bearing, Docker agents)` fans the journey
  registry across nine independent shards (`0` through `8`, raised from six in
  issue #2377 so every leg keeps its #1850 cold-boot retry margin and stays
  under #835's 125% class-count cap). Each shard gets a
  cold API-35 Pixel 7 emulator and its Docker fixtures. The downstream
  `Emulator journey aggregate verdict` combines shard tokens into `CLEAN`,
  `RE-RUN`, or `RED`, with a real journey failure remaining release-blocking.

For the local canonical full-JVM gate, run `scripts/full-jvm-gate.py`. A
focused `--tests` invocation is useful for iteration but is not a substitute
for the complete Debug + Release or unsharded local gate.

### Conditional nightly and release lanes

- **Nightly Extensive Tests** runs at 02:17 UTC and can be force-run manually.
  Its cheap guard skips the expensive suite when `main` has no new commit in
  the preceding 24 hours. When enabled, the extensive job runs three shards.
  Phase 1 is partitioned across all three. The network-fault and bootstrap
  phases run once on shard 0. The mixed extensive job uses
  `continue-on-error`. The separate **Fault-injection safety verdict (release
  gate)** reads the machine verdict for the network-fault and bootstrap phases.
  It fails closed when a gating artifact is missing, incomplete,
  infrastructurally unavailable, or red. The **Test-execution ledger (selected
  vs executed)** also fails closed when a shard or selected class has no result.
  **Nightly failure recurrence** reports the bounded historical failure trend.
  The #822 expected-fail lane is recorded but is not part of the release-gating
  fault verdict.
- **Release Emulator Validation** is a manual, evidence-producing lane. Its
  emulator step runs `scripts/release-emulator-validation.sh`. It covers the
  pre-release confidence gate, terminal-lab, tmux-existing-session,
  setup-detection, and visual-audit stages. The workflow then records the
  execution ledger and uploads the evidence bundle. It does not create or push
  a tag. For taggable evidence, validate the stable `main` commit that will be
  tagged.

Every hosted `reactivecircus/android-emulator-runner` invocation in these lanes
uses the audited immutable v2.37.0 commit. The static guard in the `Tests`
workflow rejects a floating tag, a different SHA, or a missing `# v2.37.0`
comment.

The optional long-running lanes stay separate from these canonical gates. A
terminal-heavy release may opt into the real-agent terminal release check with
`TERMINAL_RELEASE_GATE=1`. It may also enable the ten-minute stability hold
with `LONG_RUNNING_TEST=1`. The hold isn't required for unrelated small
releases and never replaces the canonical unit, integration, journey, nightly,
or release evidence gates described above.

---

## Process verification checklist

PocketShell uses the issue-based implementer/reviewer loop in
[process.md](../process.md). After reviewer `APPROVED` and before committing or
pushing an approved issue, the orchestrator follows the
[process verification checklist](../process.md#verification-checklist).
For testing-specific work, the minimum local checks are:

1. `scripts/assemble-debug.sh` — does it build?
2. `scripts/cgroup-run.sh -- ./gradlew check` — do unit tests pass?
3. For UI changes: install on emulator and compare against the design docs and
   any reference attached to the issue
4. For SSH / tmux / agent / usage changes: run the relevant Testcontainers
   integration test
5. For user-facing Android, terminal/input, SSH/tmux/agent, setup, or
   release-gate changes: verify the reviewer-owned emulator evidence includes
   commands, logs/screenshots, Docker involvement when relevant, and observed
   results

[AGENTS.md](../AGENTS.md) is only the quick local agent rule sheet.
