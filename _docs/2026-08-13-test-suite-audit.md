# Test-suite audit — 2026-08-13

Audited at `origin/main` = `39cd0d8b`, in a pinned worktree. Read-only: no product
code changed, nothing committed, no issues filed.

CI timings come from run
[31672533158](https://github.com/alexeygrigorev/pocketshell/actions/runs/31672533158)
plus two neighbours (31668646453, 31614203271) for flake recurrence. Unit-test
per-class times come from that run's own `unit-test-reports-Release` artifact, not
from a local run.

Method: four parallel audits (connection/SSH, UI/composer/terminal, journey/E2E,
guards+infra), each required to name, for every "useless" verdict, **the mutation to
production code that would leave the test green**. Findings without a nameable
mutation were dropped.

---

## Executive summary

**Where the time is.** ~269 runner-minutes per push:

| Lane | Runner-min | Share |
|---|---|---|
| Emulator journey subset (6 shards) | 206 | **77%** |
| Guards (harness 11 + static 8 + selection 3 + dex 4) | 26 | 10% |
| JVM unit (Debug 10 + Release 11) | 21 | 8% |
| Integration (Docker) | 15 | 6% |
| Python utilities | <1 | — |

**The single biggest finding is not a test — it is that roughly half the emulator
suite's 206 minutes is the same work done six times.** The warm build (373s) and the
11 core-terminal proofs are repeated identically on every shard. Class execution sums
to only 19.1/15.6/20.4/22.6/20.0/19.9 min per shard against measured jobs of 29-42 min.
Fixing the duplication is worth ~50-70 runner-minutes per push without deleting a
single load-bearing journey.

**The suite is in better shape than the request implied.** Across 6,183 unit tests and
350 androidTest classes, the audit found exactly **one** empty-bodied `@Test` repo-wide,
**zero** `assumeTrue` self-skips anywhere in the connection/SSH area, and the `#1048`
pump migration genuinely complete. Most "useless" findings are small and local. The
real problems are elsewhere: tests that run *nowhere*, and a coverage gap in the grace
window.

**Three things worth acting on regardless of CI time:**

1. A test whose load-bearing assertion is satisfied by a 30-second watchdog rather than
   by the function under test — so `closeNow()` has no effective coverage at all.
2. Four tests/scenarios that self-skip on **every** lane and read as coverage while
   protecting nothing.
3. A live instance of the maintainer's worst historical bug class (#685 "disagreeing
   clocks"): the controller's background grace is 90s, the lease TTL is 60s, and
   nothing relates them.

### A correction to my own working assumption

Early in this audit I read `nightly-extensive-suite.sh`'s `notClass` argument as
running the *complement* of the per-push journey set, and briefed an agent accordingly.
That was wrong, and the agent corrected it. `JOURNEY_EXCLUDED_CLASSES`
(`scripts/nightly-extensive-suite.sh:196-204`) is a ~13-class opt-in list; nightly
phase 1 then runs the **full** `:app:connectedDebugAndroidTest`. Every per-push journey
class therefore runs again nightly.

This matters because it makes demotion cheap: deleting a class from `JOURNEY_CLASSES`
leaves it with automatic nightly coverage, so per-push cost can be cut without losing
the test. Several recommendations below depend on it.

---

## 1. Tests that can be COMBINED

### Emulator journeys (the expensive lane)

The premise that the slowest journeys re-pay cold boot per assertion is **false for the
top four** — they are already multi-method amortized classes
(`OutboundExactlyOnceAcrossFlapE2eTest` is 9 tests in 339s, ~38s each). Their cost is
intrinsic: real flaps, wall-clock holds, A→B→C→A switches. The single-method tail is
where merging pays. Per-class harness floor is ~12-14s warm.

| Merge | Saves |
|---|---|
| `SendNoReconnectE2eTest` (40s) + `AttachmentNoReconnectE2eTest` (37s) — identical skeleton; the registry itself calls them siblings (`ci-journey-suite.sh:213-214`) | ~30s |
| `StableWifiNoSpuriousReconnectE2eTest` (43s) → 4th method of `MobileSpuriousReconnectE2eTest` — same synthetic-snapshot pipeline, cellular/wifi twins | ~30s |
| `LifecycleReattachGoneSessionNoResurrectE2eTest` (49s) + `ServerDeathReconnectNoResurrectE2eTest` (51s) — one invariant, two triggers | ~25s |
| `RedrawNonDestructiveNearBlankCaptureE2eTest` (38s) → method of `RedrawFullViewportReseedJourneyE2eTest` | ~20s |
| `WithinGraceSocketDropForegroundJourneyE2eTest` (43s) → method of `BackgroundGraceReconnectE2eTest` | ~25s |

On the AGENTS.md warning that merging hides which journey regressed: the retry loop and
the 420s cap are per-*class*, so merging concentrates both. All merges above keep the
result ≤~130s (well under the cap), and a JUnit method name still identifies the failing
journey. **But the honest total is ~2-2.5 min — merging is not where this suite's
minutes are.** Section 4 is.

**Keep separate, explicitly:** `ReconnectStormLivelockE2eTest` (320s) is the durable gate
for the most-recurred connection class (#1652/#1610/#1539). The
`IdleClaudeFragmentsOverBlack` composite and its three discriminating legs serve
different purposes — the composite proves the symptom is gone, the legs say which
mechanism broke. Better move: keep the composite per-push, demote the three legs to
nightly (−110s).

### Unit tests

- **`TmuxClientExecLaneTest`** (`shared/core-tmux/.../TmuxClientExecLaneTest.kt:437,632-644`)
  — five tests named `RED — <verb> ... wedges behind a held control channel` all call one
  helper that burns a real-time 1.5s timeout. `sendCommand` is verb-agnostic (the command
  is an opaque payload), so all five traverse an identical path; per-verb coverage lives
  in the separate `GREEN` tests. Collapse to one wedge-proof. **−12s/push.**
- **`ConnectionManagerEquivalenceTest`** (21 tests, 544 lines) — the #687/#792 "old inline
  reducer vs controller" proof. `reduceConnection` no longer exists, so the framing is
  historical and the `inline()` shim re-encodes deleted branches. The underlying behaviours
  are real and mostly unique (`statusNameFor` is covered nowhere else) — move them into
  `ConnectionEffectDriverTest`/`ConnectionControllerTest` and retire the class.
- **`SshLeaseCoalescingCharacterizationTest` + `SshLeaseLifetimeCharacterizationTest`** —
  both headers say "Phase-1 GATE — PIN the CURRENT behavior so the Phase-2 hard-cut can be
  proven equivalent." The rewrite shipped. `SshLeaseManagerTest` now covers both
  (`:251,405` coalescing; `:30,65,151,177,195` lifetime). Fold and delete. **Keep**
  `SshLeaseAcquireBoundCharacterizationTest` — AGENTS.md cites it by line as the Shape-A
  de-flake reference.
- **`DisclosureIconSlice2Test`** — asserts the same rotation-invariance as
  `DisclosureIconTest` on the same bare composable, with ~90 lines of copy-pasted
  bitmap-diff helpers. Its KDoc claims it tests the icon "as composed inside each row";
  the bodies do not. Fold in as tint rows.
- **`PriceCatalogueTest.snapshotIntegerArithmeticIsExact`** — its only production-touching
  assertion duplicates line 26 verbatim.

**Keep separate despite looking mergeable:** the `PromptComposerDraftLossOnFinalize`
{text, attachment} × {delivered, deferred, failed} matrix — each cell is a distinct
data-loss cell (G2 class coverage), and 16 of the 17 tests run in 0.2s total. And the
**keepalive family**: `TransportKeepAliveIdleCadenceTest.kt:50-54` records that the older,
"convenient" shared fake *masked* the #928 aliasing defect. A shared fake is precisely the
hazard there. Hoist the faithful IO, not the classes.

---

## 2. Tests that are USELESS

Every entry names the mutation that leaves it green.

### Runs nowhere — worse than missing, because it reads as coverage

1. **`AgentConversationReconnectDockerTest#reconnectRestoresConversationTabImmediatelyForAgentSession`**
   (`:139`) — `assumeFalse(isRunningOnCi())`. Per-push targets only the class's other three
   methods; nightly runs the class *with* `pocketshellCi=true`, so it self-skips there too.
   **Mutation: delete the reattach tab-restore logic it documents — every lane green.**
2. **`ConversationOpenLatencyRttDockerTest`** (`:112-119`) — `assumeFalse(isRunningOnCi())`
   plus a `pocketshellNetworkFaultProofs` opt-in it never receives, because nightly's
   `NETWORK_FAULT_CLASSES` is `com.pocketshell.app.proof.*` only. **Mutation: reintroduce
   the #828 serial open-path detection execs — all CI green.** This guards exactly the
   thing AGENTS.md warns about ("localhost zero-RTT hides the whole cost"). Fix is one line:
   enroll it in nightly's fault phase, whose fixture is already up.
3. **`HostBootstrapScenarioSuiteTest` — 6 of 10 scenarios never execute anywhere.** Phase 3
   runs only `ready`, `uvInstall`, `appUpdateRequired`, `notifications`. `uvUpgrade`,
   `uvUpgradeFailure`, `unsupported`, `daemonDisabled`, `userLocalPath`,
   `fishUserLocalPath` are opt-in-gated dead code. **Mutation: break the uv-upgrade-failure
   recovery sheet or fish-PATH bootstrap — nothing reddens.** Add the three failure-path
   scenarios (~2 min nightly); delete or justify the rest.

### Vacuous assertions

4. **`TransportDispatcherWedgeBoundTest.closeNow interrupts after closeAndAwaitDrain times out`**
   (`shared/core-ssh/.../TransportDispatcherWedgeBoundTest.kt:169-224`) — **the most
   consequential finding in the unit lane.** The test awaits `closing` *before* calling
   `closeNow()`, but `closeAndAwaitDrain` runs inside `runInterruptible` and cannot resume
   until a second interrupt arrives — which, at that point, can only come from the
   wall-clock watchdog. The signature is in the data: this one test measures **30.01s**,
   exactly its `perOpTimeoutMs = 30_000`. **Mutation: `fun closeNow() {}` — empty body,
   still green.** So `closeNow()` (`TransportDispatcher.kt:245-248`) has no effective
   coverage. Rewrite: call `closeNow()` while `closing` is in flight, and raise the
   per-op timeout past the test budget so the watchdog cannot stand in. Also **−60s/push**.
5. **`PromptComposerKeyboardLayoutTest.composerHasNoHeightFractionOrAutoExpandStateMachine`**
   (`app/src/test/.../composer/PromptComposerKeyboardLayoutTest.kt:33-41`) — the body is
   **empty**; a comment and nothing else. I verified this is the only empty-bodied `@Test`
   in the repo. **Mutation: reintroduce the entire deleted auto-expand state machine — the
   exact #615 regression — still green.** It sits on the most regression-prone file in the
   composer hot spot (#567→…→#801) while protecting nothing. Delete.
6. **`PublicApiShapeTest`** (`shared/core-ssh/.../PublicApiShapeTest.kt:16-67`) — 7 tests
   that read constructor arguments back (`ExecResult("hi").stdout == "hi"`) or assert a
   language guarantee (`object === object`). **Mutation: any production mutation at all.**
   The only change they catch is deleting a field, which the compiler catches first.
7. **`AudioRecorderExceptionTest.exception_hierarchy_is_sealed`** (`:62-77`) — asserts five
   `simpleName`s equal a hardcoded list. **Mutation: swap the DEAD_OBJECT/BAD_VALUE mappings
   — green** (the class's other four tests catch it; this one cannot). Renaming a variant is
   a compile error in the same file. A tautology guarded by `javac`.
8. **`KeepAliveConfigTest`'s third test** (`:80-96`) — sshj only starts the runner when
   `keepAliveInterval > 0`, which test 1 already pins to 0 on the same client, so no
   mutation reddens test 3 alone. **Mutation both stay green on: arm the keepalive inside
   `connect()` instead of at build time** — the real #847 corruption path returns. That case
   belongs to `SshIntegrationTest.noKeepAliveBackgroundWriterThreadAfterConnect`, where it
   already lives.
9. **`ReleaseGateScriptTest`** (`:126-152`) asserts only `exit == 0` across 12 harnesses.
   **Mutation: an early `exit 0` in any harness — green, having verified nothing.** Its
   sibling `DiskPreflightScriptTest:116-122` already closed this hole with a
   `PASS: … (17 cases)` count assertion. Carry that pattern across.
10. **`NavigationChevronTest`, touch-target half** (`:53-55`) — asserts a test-local
    `Box(Modifier.size(48.dp))` is 48dp. Comparing the test's own constant with itself. The
    icon-size half is real; keep it.
11. **`TerminalParserRenderBenchmarkTest`, benchmark half** (`:50-63`) — throughput/p95
    budgets are enforced only under `pocketshell.terminalBenchmark.enforceBudgets`, never
    set in CI. **Mutation: make `TerminalEmulator.append` 100× slower — green** (only an
    unread JSON report changes). The marker/validator assertions are real but do not need a
    10MB fixture. The sibling `dirtyRegionRenderingBeatsFullRepaint` has a real ≥8× gate —
    leave it alone.
12. **`AddEditHostScreenTest#scanQrAction_visibleOnAddHost_andInvokesCallback`** (`:112-116`)
    — asserts a test-local lambda fired. **Mutation: break `HostQrCode.decode` handling at
    `HostListViewModel.kt:1018` or `QrScannerScreen` delivery entirely — green.** Fine as a
    button-presence check; must not be counted as QR-import coverage (see §3).
13. **`EmulatorDockerSshSmokeTest` (1147 lines) + `EmulatorWorkflowE2eTest` (704 lines)** —
    not vacuous, but fully subsumed: all 169 journey classes prove emulator→`agents:2222`
    SSH + tmux attach + input every push. Delete; ~1.8k lines of drifting harness.
14. Micro: `FailureReasonTest:89-90` reads a constructor arg back;
    `ConnectionManagerEquivalenceTest:293-302,323-330` assert `assertNotEquals("Failed")`
    immediately after pinning the value to `"Reconnecting"` — decorative, can never fire first.

### Cleared after inspection

Worth recording, because these look like the pattern and are not:
`ConnectionControllerCoverageFirstTest` (real reducer coverage despite a migration-era
name), `ControllerGraceDefaultTest`, `GraceEffectsRecoveryOwnershipTest`,
`Issue1642ConnectionMirrorTest`, `Issue1872InsetsCleanupSourceGuardTest` (a source-grep
guard, but each assertion has a named mutation), the ui-kit `assertIsDisplayed()` uses in
`EmptyStateTest`/`ConfirmDialogTest` (presence *is* the property there),
`SilentDropSyntheticSeamJourneyE2eTest` (injects via the sanctioned seam but asserts the
user-visible indicator plus a real round-trip — the correct #780 shape), and the
`tools/pocketshell` pytest suite (28 files ≈ 1:1 with source, 13s — the best cost/coverage
ratio in the repo).

---

## 3. Tests that are MISSING

### Highest priority — a live recurrence of the #685 class

**Nothing relates the controller's 90s background grace to the lease's 60s idle TTL.**
`ConnectionController.kt:909` sets `DEFAULT_GRACE_MS = 90_000L` (moved from 60s by #1159);
`SshLeaseManager.kt:791` still sets `DEFAULT_IDLE_TTL_MILLIS = 60_000L`. The #685
"collapse to one clock" invariant *is* pinned for the passive pair
(`TmuxSessionViewModelPassiveReconnectTest.kt:848-861`), but nothing covers the background
grace after #1159 moved it.

User-visible failure: background the app, foreground at t = 60-90s. The controller says
"within grace", but the released lease has passed its TTL and gone cold — so the user gets
a reconnect instead of the documented zero-reconnect reattach. The equivalence suite itself
documents that cold-lease-within-grace yields `Reconnecting`
(`ConnectionManagerEquivalenceTest.kt:226-238`).

This is the exact shape of the bug class AGENTS.md calls the grace root cause. Test: a
virtual-clock journey — Live → background → advance 75s → foreground → assert zero
reconnect (or, if #1159 deliberately decoupled them by holding the lease, a test proving
the lease is held for the full 90s), plus a one-line cross-module relation guard beside
`ControllerGraceDefaultTest`.

### Phase-2 gap list — verified against code, not the doc

| Claimed gap | Actual state |
|---|---|
| Cold install | **Covered** — `ColdInstallE2eTest` (#144), nightly |
| Multi-host | **Covered** — `MultiHostSessionE2eTest`, nightly |
| Mid-session reconnect | **Well covered** per-push |
| Settings persistence | **Covered** — `SettingsPersistenceE2eTest`, nightly |
| Long-running session | **Split** — 90s no-flap hold per-push; 10-min hold release-gate-only. Acceptable per process.md |
| Real-agent CLI | **Cadence gap** — `RealAgentReleaseGateTest` is excluded from nightly and runs only under `REAL_AGENTS=1`. A real Claude/Codex rendering regression stays invisible until a release cut |
| QR import | **Genuine gap** — the codec round-trip is unit-tested (`HostQrCodeTest`), but scan → decode → prefill → save → connect has no test; the only androidTest is the lambda-fired check (§2.12) |

Most of that list is stale — it should be updated in the docs.

### Other real gaps

- **`TransportDispatcher.closeNow()`** — no effective coverage (follows from §2.4). Failure:
  a wedged disconnect's worker is never interrupted, the dispatch thread leaks, the next
  teardown wedges — the #937 symptom.
- **`runBlockingDispatch` self-deadlock precondition** — `TransportDispatcher.kt:194-197`
  documents "MUST NOT be called from the dispatch thread itself" and nothing enforces it. A
  future nested call produces a permanent silent wedge, the hardest shape in this repo's
  catalogue. Add `check(Thread.currentThread() !== dispatchThread)` and one test asserting
  the loud throw.
- **Breadcrumb and KeyBar have no JVM layout test at all.** `TmuxSessionTopChrome.kt:533`
  (`CompactBreadcrumb`) and `uikit/components/KeyBar.kt` are referenced only by render
  fixtures with no assertions. F2 names the breadcrumb explicitly as part of the reported
  composer-clip state, and #813's CLIP cause is chrome pushed off the right edge. A
  Robolectric containment test at `w320dp-h640dp` + `fontScale=1.3` would cover both.
- **The composer bottom-bar CLIP class has no fast-lane guard.** With
  `PromptComposerKeyboardLayoutTest` empty (§2.5), a clip regression on the #1 release
  blocker reaches `main` before any per-PR check can see it.
- **`summarize-connected-test-results.py` fail-closed path untested** (`:54-56`, rc 2 on
  `ParseError`) — the property that a truncated result file cannot be laundered into a green
  summary. Directly in this repo's killed-run-artifact class. ~0.05s to add.
- **`PortUsageDao` is dead code, not a test gap** — `insertIfMissing`/`incrementClick`/
  `addBytes` have exactly one referent repo-wide (the DI provider at `StorageModule.kt:91`),
  which nothing injects. Under D22 the answer is deletion, not a new test. (The entity/table
  stays — migrations create it.)
- **`AppDatabaseTest` is not the gap it looked like** — 2,093 lines covering per-DAO
  round-trips plus the full migration ladder, with `StorageModuleTest` adding fail-closed
  open-path proofs. One file, but dense and incident-grounded. No action.

---

## 4. CI time — where to improve

### The emulator lane (206 runner-min, 77% of the budget)

Ordered by minutes saved. Moves 1-3 are worth **~50-70 runner-minutes per push (~25-35%)**
and cut the slowest leg by ~10 min, without deleting a load-bearing journey.

1. **Stop rebuilding the same APKs on all six shards — ~25-30 runner-min, −5 min wall per
   leg.** `warm_journey_build` (`ci-journey-warm-build-functions.sh:103-110`) runs an
   identical `:app:assembleDebug` + `assembleDebugAndroidTest` +
   `:shared:core-terminal:assembleDebugAndroidTest` on every leg (373s measured). Build once
   in a cheap parallel job overlapping emulator boot, pass the APKs as an artifact. A shared
   Gradle build cache gets most of the same — but per this repo's own FROM-CACHE catalogue,
   cache the *assemble* tasks only, never test tasks.
2. **Run the 11 core-terminal proofs on one shard, not six — ~20-35 runner-min, −4-7 min
   wall on five legs.** `ci-journey-suite.sh:544-922` runs all 11 on every leg. The stated
   justification ("caught on every leg") buys zero signal: same commit, same
   device-independent in-process Compose tests. Hash-assign them like journey classes (#1862).
3. **Demote non-journey in-process UI classes to nightly — ~5-7 min.** Cheap because nightly
   already runs everything. Candidates: the G9 dialog/scaffold cluster (`FileExplorerScaffoldTest`
   ×4 = 80s, `FileViewerScaffoldTest` ×3 = 51s, `SnippetTemplateDialogButtonsTest` 22s,
   `ConfirmDeleteAllDialogButtonsTest` 23s, `SessionKindPickerUiTest` 25s,
   `SessionTypePickerNameFieldUiTest` 28s, `PortForwardDuplicateKeyRenderTest` 23s,
   `UsageResetCreditsLayoutTest` 22s, `AppUpdateDismissSelectorTest` 14s,
   `SessionCardFeedRegistryTest` 27s, `HostReadyPrimaryActionTest` 21s) plus the three
   black-heal legs (110s). **Counterweight, stated honestly: nightly is ~29% red and a day
   late.** Keep the composer/occlusion and reconnect recurrence families per-push — those
   are the reopen hot spots D31 exists for.
4. **De-risk the 420s per-class cap before it becomes the next flake class.**
   `OutboundExactlyOnceAcrossFlapE2eTest` runs 339s against a 420s cap
   (`ci-journey-suite.sh:430`) — 19% headroom on a contended runner, and a cap kill buckets
   as `BUDGET_TIMEOUT` → red job. Split its 9 tests into composer-lane and keystroke-lane
   classes; that also stops the hash partition co-locating 339s + 197s on one shard.
5. **Shard imbalance (29 vs 42 min):** ~7 min is the hash partition's class-sum spread
   (15.6 vs 22.6 min; 25 vs 35 classes), the rest is warm-build variance and retries. Keep
   the #1862 name-hash — placement stability was a deliberate choice and hand-pinning was
   rejected for good reasons. Moves 1 and 4 shave the extremes.
6. **Retry policy is sound — leave it.** Retry-once-per-class with a loud
   `JOURNEY_FLAKE_RECOVERED` marker is proportionate.

### Flake recurrence (measured across 3 consecutive main runs)

**Exactly 3 flake-recoveries per run, in all 3 runs** — ~1.7% of ~173 class runs. Every one
was absorbed by the per-class retry, so all three jobs reported success.

| Class | Runs flaked |
|---|---|
| `PromptComposerSaturatedImeAnchorE2eTest` (59s) | 2 of 3 |
| `TmuxSessionOpencodeInputDockerTest#issue1977…ChipIsContainedAndHitTestable` | 2 of 3 |
| `TmuxTerminalSurfaceFailureE2eTest` | 2 of 3 |
| `CleanOutageReattachResilienceE2eTest` (`ComposeTimeoutException` after 180s) | 1 of 3 |
| `SilentDropSyntheticSeamJourneyE2eTest` | 1 of 3 |
| `RealisticWifiStabilityNoSpuriousReconnectE2eTest` | 1 of 3 |
| `BackgroundGraceReconnectE2eTest` | 1 of 3 |

The retry is doing its job, but three repeat offenders — two of them in the composer/chip
containment family — are worth a look before the retry starts hiding a real regression. A
100% flake-per-run rate is a standing tax of ~100-200s.

### Guards (26 runner-min, 10%)

Per-step data confirms ~95% of the cost sits in five steps. The sub-10s tail (~14 steps per
job) is free and several are incident-pinned (#1842, #2007, #1657) — leave it alone.

| Step | Real incident behind it | Cheaper check? | Needed every push? |
|---|---|---|---|
| journey-suite budget guard, 305s | #835 reopened: a SIGKILLed suite classified advisory-green, masking a cut-short class | No — a timeout firing is provable only by a real timer | **No** — inputs are `scripts/ci-journey-*` + `tests.yml` only |
| journey warm-build guard, 160s | #1814: cold compile charged to the first class's budget, misread as a journey failure | Partly | **No** — scripts/workflow inputs only |
| test-validity guard, 284s | #641/#635 vacuous-IME-skip; #1158 SEAM1 | Split it: the real-tree scan is ~30s and product-dependent | Scan **yes**; the ~250s self-test **no** |
| release-gate profile guard, 158s | #2054: three v0.4.42 gates OOMed in the build | No — the mutation matrix is the value | **No** — `scripts/**` only |
| test-selection guards, 191s | #2063/#2067 unreachable test files | Split: coverage half ~40s | Coverage **yes**; self-test **no** |
| dex ratchet, 211s | #1685, 3× recurrence of an ART 256-register `VerifyError` phone crash | **No cheaper check exists** — the property lives only in compiled dex | **Yes** — keep unchanged |

**Move: path-condition the harness-only guards.** All 19 steps of `guards-ci-harness`, plus
the scripts-only steps of `guards-static` and the two big self-tests, have inputs confined to
`scripts/**`, `tests/**`, `.github/**`. On a product-code-only push, skip them — but with a
**logged decision line, failing open to "run everything" when detection fails**, so "could
not check" never reads as "checked and fine." Keep the jobs themselves always-running so the
`unit-gate` needs-contract (`tests.yml:816-849`) is untouched, and extend
`check-unit-gate-wiring.sh` to pin the path lists. **Saves ~17-18 runner-min on a typical
product push** and takes `guards-ci-harness` off the wall-clock critical path.

### Unit lane (21 runner-min, 8%)

Execution is 462s of the 611s step (76%) — **not** compile-dominated, and it is paid twice
(Debug + Release). ~924s of runner time per push.

| Move | Saves/push |
|---|---|
| Fix `TransportDispatcherWedgeBoundTest` (§2.4) — 31.5s → ~1.5s, and it becomes a real test | **~60s** |
| Fix `PromptComposerDraftLossOnFinalizeTest#quiescentGuardIsFalseWhileRecording` — 30.02s of the class's 30.3s; the other 16 tests total ~0.2s. It enters Recording, launching the amplitude-sampler `while(isActive){…delay()}` loop — the #882 class — and 30s equals `GENEROUS_SETTLE_DEADLINE_MS`, i.e. it passes only by exhausting a real deadline. Also a latent hang risk | **~60s** |
| Relocate the 3 script-harness classes out of the Gradle graph (below) | **~230s** |
| Drop `@GraphicsMode(NATIVE)` from the 10 ui-kit classes that never read a pixel (`AgentKindBadgeTest`'s 9.2s is 8.94s of native-graphics init in one test) | up to ~16s |
| Shrink the recording-only benchmark fixtures (§2.11) | ~14s |
| Delete `SettlePumpContentionBudgetTest` test 3 — a deliberate 5.1s sleep proving what tests 1+2 jointly imply | ~10s |
| Collapse the 5 `TmuxClientExecLaneTest` wedge repros to 1 | ~12s |
| Fold `DisclosureIconSlice2Test` | ~3.6s |

**Relocate the script-harness classes, don't merge them.** `AvdLockScriptTest` (49.7s/6),
`ReleaseGateScriptTest` (40.8s/12), `DiskPreflightScriptTest` (24.7s/2) total 115s — 38% of
`:app`'s 302s, paid in both legs, and since `:app` runs classes serially in one fork
(deliberate, `tests.yml:106-107`) it is directly on each leg's wall clock. Every method is
`ProcessBuilder("bash", …)` driving `tests/scripts/*.sh`; zero Kotlin production code is
exercised, so the two variants do byte-identical work. **The repo already litigated exactly
this and chose relocation** — `scripts/ci-test-selection-guards.sh:9-19` describes moving a
~165s suite out for the same reason. Port them to one shell guard in `guards-static`, keeping
(i) the hermetic env scrub (the #1702 lesson), (ii) the concurrent-lane invocation (#2085),
(iii) DiskPreflight's PASS-count assertion — and extend that count assertion to all 12
harnesses to close §2.9. One honest loss: they drop out of `full-jvm-gate.py`'s local graph,
the same trade #2067 already accepted.

**Keep both unit variants.** The audit checked whether Debug+Release is doubled cost for
nothing: it is not. `ConnectionController.kt:78` gates confinement assertions on
`BuildConfig.DEBUG`, so the Release leg is the only lane that unit-tests the connection core
in its production shape — and #1633's history stands. The right cut is removing
variant-independent work from the graph, not dropping a leg.

### `check-test-validity.sh` — what it actually enforces

Requested inventory, since it is billed as F2's automated backstop:

- **Advisory only (never fails the build):** A4/A2 (StandIn/Proxy), FAKE1, AWAIT1, and the
  general TIMING1 form. Additionally, every *known-baselined* occurrence of the hard-fail
  categories is permanently green.
- **Hard-fails on new occurrences:** A5, A5L, C1, J1, SEAM1, V1, plus two narrow TIMING1
  shapes.
- **Missing entirely:** (i) an empty-`@Test`-body check — §2.5 passes every smell; (ii) any
  check for `assertIsDisplayed()` standing in for containment, which is the F2 flagship rule
  with no machine sibling. Repo-wide the ratio is **811 `assertIsDisplayed()` to 242
  containment assertions**. Both are cheap greps in the existing script.

---

## Suggested order of work

Nothing here is a code change yet — these are issue candidates, in the order I would file them.

1. **`TransportDispatcher.closeNow()` has no coverage** (§2.4 + §3) — a real hole in the
   most critical subsystem, and it pays for itself in CI time.
2. **The 90s grace vs 60s lease TTL relation** (§3) — a live instance of the bug class that
   caused the most damage historically.
3. **Build once, not six times** (§4.1) + **core-terminal proofs on one shard** (§4.2) —
   the largest CI win, no coverage change.
4. **The four run-nowhere tests** (§2.1-2.3) — small fixes, and they currently misrepresent
   coverage.
5. **Path-condition the harness-only guards** (§4) — ~17-18 min/push.
6. **Relocate the script-harness classes** (§4) — ~230s/push, follows a decided precedent.
7. **Delete the vacuous tests** (§2.5-2.14) — maintenance surface, not seconds.
8. **The three repeat flakes** (§4) — before the retry starts hiding something real.
9. **QR-import journey + breadcrumb/KeyBar containment tests** (§3) — genuine gaps.
10. **Update the Phase-2 gap list in the docs** — most of it is stale.
