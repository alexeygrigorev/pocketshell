# Adversarial code-quality audit - 2026-08-30

## Scope and provenance

Five independent read-only reviewers using `gpt-5.6-sol` at `xhigh` audited
`origin/main` at `983e17632db9d22df8dd16d59dfd3a5302390f29`. Their primary lenses were:

1. SSH/tmux runtime ownership, concurrency, cleanup, and performance.
2. Android lifecycle, Compose/state correctness, and UI redundancy.
3. Python tooling, shell/Gradle/CI, tests, and release automation.
4. Cross-module data, storage, parsing, cache, and API contracts.
5. Independent whole-repository duplication, dead-code, and simplification.

Security was explicitly not a primary concern. One obvious SSH host-verification
gap is retained for completeness but excluded from the near-term priority list.

The orchestrator deduplicated the reports and checked the leading claims against
the pinned source. `Confirmed` below means the defect follows directly from the
code or an auditor reproduced it locally; it does not mean every runtime journey
was executed. `Risk` means the failure is credible but timing/load dependent and
was not reproduced. Previously known items are retained only where they remain
present and materially affect the simplification audit.

No production code was changed and no full Android/Gradle gate was run. The
tooling reviewer ran the frozen Python suite and observed 1,108 passes and one
ambient-configuration failure described in F25.

## Executive verdict

No critical defect was found. The most urgent new correctness problems are:

1. Add Host can silently overwrite the last edited host because an
   Activity-scoped ViewModel retains edit identity.
2. Release policy says tag only a validated commit already on `main`, while the
   executable helper, guard, and release-owner prompt still permit and enforce
   tag-before-merge from a release branch.
3. `pocketshell daemon stop` can signal an unrelated same-user process after a
   stale PID is reused.
4. Conversation image pixel arithmetic can overflow and bypass its decode bound;
   both conversation and file-viewer image decoding also block composition.
5. Gradle's promised 15-second version-derivation timeout occurs after a
   potentially infinite blocking stdout read.
6. Rebinding Add/Edit Host, File Explorer, and non-session navigation is not
   consistently fenced to route/host identity.
7. Cached-runtime teardown and grace-recovery bookkeeping can lose ownership of
   cleanup/heal work.
8. Card storage repeats the tree store's lossy naming and unlocked whole-document
   update pattern, allowing cross-session aliasing and lost concurrent writes.

Two reviewers independently confirmed the card-store findings. Two independently
confirmed that the previously tracked durable-tree contract remains unresolved.

## Recommended order

- P0 correctness/data loss: F1-F5 and F8-F10.
- P1 lifecycle and cross-owner state: F11-F18.
- P2 correctness hardening and CI reliability: F19-F25.
- P3 deletion/simplification: F26-F30.
- F6 was deferred in the audit snapshot per the maintainer's priority, then
  included in the remediation branch as a bounded trust-resolution slice.

## Findings

### F1 - Add Host can overwrite the last edited host

Severity: high. Classification: confirmed, new.

- Evidence: `AddEditHostScreen.kt:125-143` binds only when `hostId != null`,
  while Add routes pass null and Edit routes pass an ID
  (`MainActivity.kt:1191-1203,1224-1234`). The Activity-scoped ViewModel retains
  `editingHostId` (`AddEditHostViewModel.kt:127,157-184`), and `save()` updates
  that retained row whenever it is non-null (`:261-303`). The screen derives
  the visible Add label from the route argument (`AddEditHostScreen.kt:216-223,
  343-346`), not the retained ViewModel identity.
- Trigger and impact: edit host A, return, then choose Add Host. The screen says
  Add Host but retains A's identity and values; Save updates A rather than
  inserting B.
- Test gap: existing tests create fresh ViewModels and do not cover edit -> Add
  or Add -> Add reuse (`AddEditHostViewModelTest.kt:121-157,292-327`).
- Action: replace `loadHost(Long)` with an unconditional identity-aware
  `bind(Long?)` that resets state on null and fences stale loads. Prefer a
  destination-scoped ViewModel if navigation ownership permits it.

### F2 - Release tooling can publish a commit not on `main`

Severity: high. Classification: confirmed policy/implementation defect, new
drift adjacent to the 2026-08-23 release finding.

- Evidence: `process.md:228-230` and `docs/release.md:10-14,187-217,248-255`
  require merge-first/tag-from-main. `scripts/push-release-tag.sh:80-96`
  explicitly accepts `release/vX.Y.Z` and validates against that branch's remote
  head before pushing the tag at that SHA (`:141-177`).
  `scripts/test-release-branch-guard.sh:172-216` enforces candidate-branch
  tagging, and `.claude/agents/release-owner.md:19-34,49-53` still instructs
  tag-before-merge.
- Trigger and impact: following the executable release path publishes GitHub and
  PyPI artifacts from a SHA outside `main` via `.github/workflows/build.yml:3-5,
  91-105,164-168`; later merge history may no longer match the validated/tagged
  commit and can violate reachable-tag version assumptions.
- Test gap: the structural guard rejects the corrected main-only behavior.
- Action: make the helper require `branch == main` and `HEAD == origin/main`;
  reverse the release-owner sequence; change the guard to reject every release
  branch.

### F3 - A stale daemon PID file can terminate an unrelated process

Severity: high. Classification: confirmed, new.

- Evidence: the daemon persists only a numeric PID
  (`tools/pocketshell/src/pocketshell/daemon.py:963-969`). `stop_daemon()` probes
  the socket but still sends `SIGTERM` whenever the PID parses, even if no daemon
  answers (`daemon.py:1666-1675`). It does not validate process start time,
  executable, or ownership token.
- Trigger and impact: a crash or `SIGKILL` leaves the PID file, the OS reuses the
  PID, and a later daemon stop kills an unrelated agent, build, editor, or tmux
  helper belonging to the same user.
- Test gap: crash recovery immediately starts a replacement, and idempotent stop
  covers no stale PID (`test_daemon.py:475-507,726-731`).
- Action: never signal the PID when the socket does not identify a live daemon;
  store and validate PID plus `/proc/<pid>/stat` start time or a daemon ownership
  token. Add a live-unrelated-child/stale-PID test.

### F4 - Conversation image bounds can overflow and image decoding blocks Compose

Severity: high. Classification: confirmed, new.

- Evidence: conversation bytes are decoded synchronously in composition
  (`ConversationImageContent.kt:140-144`). Its limit multiplies `Int` dimensions
  (`:163-174`), so values such as 50,000 x 50,000 overflow and can leave
  `inSampleSize=1`. The 20 MB compressed-byte cap
  (`ConversationImageViewModel.kt:128-135`) does not bound decoded dimensions.
  File Viewer has safe `Long` sampling (`BoundedImageDecoder.kt:57-78`) but also
  performs both decode passes during composition (`FileViewerScreen.kt:1397-1399`).
- Trigger and impact: a highly compressible huge-dimension image can bypass the
  intended two-million-pixel cap and OOM the process. Ordinary large images can
  stall the main thread.
- Test gap: conversation render tests use a 4 x 4 PNG; decoder tests exercise
  only moderate dimensions and not UI responsiveness.
- Action: share a `Long`-based byte-array/file decoder and run bounds plus sampled
  decode off-main before publishing an `ImageBitmap`. Add extreme-dimension and
  blocked-decoder responsiveness tests.

### F5 - Gradle's version subprocess timeout is ineffective

Severity: high. Classification: confirmed, new.

- Evidence: `app/build.gradle.kts:55-59` promises bounded fallback, but
  `derivePocketshellVersion()` reads stdout to EOF before its timed wait
  (`:67-75`). A live child that holds stdout open blocks forever; undrained
  stderr can also fill and deadlock the child.
- Trigger and impact: a wedged `git`, shell, or derivation script hangs Gradle
  configuration, local builds, CI, and releases until an outer timeout intervenes.
- Test gap: `scripts/check-version-coupling.sh:99-133,163-215` covers normal
  termination and reference wiring, not a hung child.
- Action: drain stdout/stderr concurrently or to bounded files, perform the timed
  wait first, force-kill and join on timeout, then parse output. Test with a fake
  derivation script that never exits.

### F6 - Production SSH accepts every server host key

Severity: high security impact, deferred priority. Classification: confirmed,
new.

- Evidence: `KnownHostsPolicy.kt:8-18` says app code should use a private
  known-hosts file and `AcceptAll` is test/TOFU-only. `SshConnection.kt:83-95,
  334-340` nevertheless defaults to `AcceptAll`/sshj `PromiscuousVerifier`.
  `SshLeaseManager.kt:876-889`, `TmuxSessionViewModel.kt:6354-6366`,
  `FolderListBoundParams.kt:45-57`, and `ShareViewModel.kt:272-288` select the
  same policy; no production `KnownHostsFile` call site exists.
- Trigger and impact: redirected DNS/router traffic or hostile Wi-Fi can
  impersonate the SSH server and receive terminal, file, and agent traffic.
- Test gap: fakes naturally use `AcceptAll`; there is no unknown/changed-key
  integration journey.
- Action: when prioritized, centralize target construction around an app-private
  known-hosts store, add explicit first-use fingerprint enrollment, reject key
  changes, and remove permissive defaults.

### F7 - Durable tree persistence still lacks authoritative-empty/latest-wins semantics

Severity: high. Classification: confirmed, previously audited and unresolved
under #2243; independently confirmed by two reviewers.

- Evidence: `TreeSyncCoordinator.persist()` skips remote writes for empty nodes
  and launches independent unordered writes (`TreeSyncCoordinator.kt:596-620`).
  remote hydrate collapses valid empty and failures
  (`TreeRemoteSource.kt:101-118`). Client cache identity is a lossy sanitized
  display name (`TreeClientCache.kt:132,141-150,181-210`). Host tree/workspace
  mutations are unlocked whole-document RMW operations using one `.tmp`
  (`tools/pocketshell/src/pocketshell/tree.py:130-153,372-410,610-638`).
- Trigger and impact: deleting the last session does not clear the remote
  snapshot; overlapping writes can finish out of order or erase one another;
  colliding display names share client cache. Cold restore can resurrect stale
  sessions or cross-contaminate hosts.
- Test gap: the coordinator fake discards node payloads
  (`TreeSyncCoordinatorTest.kt:542-547`); Python tests are serial.
- Action: complete #2243: stable host identity, typed Empty/Unavailable, persist
  every revision including empty, one per-host latest-wins writer, shared
  lock/CAS, unique durable temporary writes.

### F8 - Card filenames alias distinct tmux sessions

Severity: medium-high. Classification: confirmed, new; independently confirmed
by two reviewers.

- Evidence: `CardPaths.session_file()` uses `_sanitise_session`
  (`tools/pocketshell/src/pocketshell/cards.py:98-110`), which replaces arbitrary
  disallowed runs with `_`, strips dots, and falls back to `session` (`:139-148`).
- Trigger and impact: valid names such as `a/b` and `a_b`, or `...` and
  `session`, map to one YAML file; cards and interaction state leak or overwrite
  across sessions.
- Test gap: `test_cards.py:58-60` blesses one lossy mapping; isolation tests use
  only non-colliding names.
- Action: use reversible percent/base64 encoding or a hash plus stored original
  session name; validate identity on read and delete `_sanitise_session()`.

### F9 - Concurrent card mutations lose updates and race on one temp file

Severity: medium-high. Classification: confirmed concurrency defect, new;
independently confirmed by two reviewers.

- Evidence: cards copy the fixed-temp writer
  (`tools/pocketshell/src/pocketshell/cards.py:159-177`). `upsert_card()` and
  `apply_interaction()` perform unlocked whole-list RMW
  (`cards.py:510-552`), while the phone and agents intentionally invoke these
  verbs independently (`cards.py:555-561`,
  `SessionCardsRemoteSource.kt:118-175`).
- Trigger and impact: an agent push overlaps a phone check/read or another push.
  Last stale writer wins, dropping a card or reverting state; one writer can
  move the shared `.tmp` before the other replaces it.
- Test gap: all card mutation tests are sequential.
- Action: extract one locked atomic-document mutation primitive shared by tree,
  workspace, and cards; use unique temp files, fsync/rename/directory fsync, and
  deterministic two-process preservation tests.

### F10 - Rebinding File Explorer lets an old-host transfer mutate the new host

Severity: medium-high. Classification: confirmed, new.

- Evidence: the Activity-owned explorer is rebound through `start()`
  (`FileExplorerScreen.kt:99-125`). `FileExplorerViewModel.start()` changes the
  request but does not cancel `transferJob` or reset transfer state
  (`FileExplorerViewModel.kt:141-178`). Upload completion publishes globally and
  calls `navigateTo(dir)` (`:262-300`), which uses the current request
  (`:363-389`); download completion is likewise unfenced (`:321-359`).
- Trigger and impact: start a transfer on host A, then open host B before it
  completes. A's completion banner and directory refresh are applied to B;
  invisible old-host work continues.
- Test gap: transfer tests await completion before any rebind
  (`FileExplorerLeaseTest.kt:183-233`).
- Action: cancel/join and reset on request identity change, plus generation-fence
  every completion. If transfers should survive navigation, make them a
  request-keyed service whose results remain bound to their origin.

### F11 - Cached-runtime teardown can skip later runtimes

Severity: medium. Classification: confirmed bounded-cleanup defect, new.

- Evidence: one runtime gives `detachTimeoutMs` to job joins, then separately to
  client detach and lease release (`TmuxSessionRuntimeCache.kt:363-410`).
  `TmuxSessionViewModel.kt:6048-6065` wraps all runtimes in only
  `SYNC_DETACH_TIMEOUT_MS * count`; the constant is 600 ms
  (`TmuxSessionSupport.kt:436`). `detachCleanly(600)` itself has two bounded
  phases (`shared/core-tmux/.../TmuxClient.kt:1915-1966`).
- Trigger and impact: with two cached runtimes, one wedged first runtime consumes
  the entire outer budget before the second is entered; the latter gets no close
  or lease release.
- Test gap: `CloseCachedRuntimeBoundedTest.kt:45-63` covers one runtime and only
  asserts return, not complete cleanup.
- Action: cancel all jobs first; close runtimes concurrently with one total
  ceiling each; remove or correctly derive the outer timeout. Remove
  `paneAgentJobs` from cached state because it is cancelled immediately and
  detection is restarted rather than restored.

### F12 - Re-arming same-session grace recovery loses ownership of an active heal

Severity: medium. Classification: confirmed state-ownership defect with
timing-dependent impact, new.

- Evidence: `GraceEffects.beginWithinGraceRecovery()` replaces the claim and
  clears `recoveryJobs` without cancelling or retaining them
  (`GraceEffects.kt:65-70,92-97`); later retirement sees only the new list
  (`:152-158`). Every within-grace foreground re-arms
  (`TmuxSessionViewModel.kt:3714-3721,3951-3977`), while a dead-channel heal is a
  separately tracked job (`:4312-4364`).
- Trigger and impact: foreground starts a heal, another background/foreground
  cycle clears its registry, then a sibling session or retry supersedes it. The
  old heal remains able to act on shared transport after ownership changed.
- Test gap: no `begin -> track heal -> begin same claim -> retire` case.
- Action: make same-claim begin idempotent and retain jobs; cancel the old
  registry when replacing a different claim; add the exact repeated-foreground
  ownership test.

### F13 - Concurrent daemon starts can orphan a daemon and delete a live socket

Severity: medium-high. Classification: confirmed race, new.

- Evidence: CLI and foreground startup use check-then-act probes
  (`tools/pocketshell/src/pocketshell/cli.py:144-162`,
  `daemon.py:1729-1736`). Each daemon unconditionally unlinks the shared socket
  before bind (`daemon.py:935-954`) and removes the shared socket/PID on exit
  (`:1051-1059`). There is no startup lock or ownership check.
- Trigger and impact: two starts both see no daemon. The later unlinks the first
  listener and takes the path; the first is orphaned. Either exit can then delete
  the survivor's paths.
- Test gap: `test_daemon.py:770-778` starts the second process only after the
  first is responsive.
- Action: serialize probe/bind/publication with per-socket `flock`; unlink only a
  proven stale socket under that lock; cleanup must validate inode/ownership.

### F14 - Configuration recreation drops every non-session route

Severity: medium. Classification: confirmed, new.

- Evidence: `MainActivity.kt:337-366` treats every non-null saved state as
  process death, though configuration recreation also provides it. `onStop`
  persists only tmux destinations and clears others (`:744-764,2177-2184`). The
  current route/back stack are plain `remember` state (`:927-937,976`), with the
  discard acknowledged at `:848-862,1052-1066`. No manifest config-change
  handling exists (`AndroidManifest.xml:108-111`).
- Trigger and impact: rotate or change locale/font/theme in Settings, host forms,
  File Viewer/Explorer, Git, Jobs, or Usage; the user returns to Host List and
  loses navigation/in-progress context.
- Test gap: recreation coverage exercises only active tmux restoration.
- Action: keep credential-free route/back-stack identity in an Activity-retained
  owner across configuration changes and re-resolve credentials only after true
  process death. Add form and File Viewer/Settings recreation journeys.

### F15 - Preference wrappers still block broadcast callbacks

Severity: medium. Classification: confirmed, new.

- Evidence: `SystemSurfaceStateStore` starts IO work then `runBlocking` awaits it
  on first access (`SystemSurfaceState.kt:64-75`), reached synchronously from
  `ActiveSessionsWidgetProvider.onUpdate` (`ActiveSessionsWidgetProvider.kt:15-39`).
  `UsageNotificationStateStore.kt:123-131` repeats the pattern, reached by
  `UsageNotificationDismissReceiver.kt:22-31`. Existing test commentary records
  roughly 648 ms cold preference access.
- Trigger and impact: cold-process widget update or notification dismissal blocks
  the main receiver thread and consumes the broadcast budget.
- Test gap: tests prove only that disk work uses another physical thread, not
  that the callback returns before it finishes.
- Action: use `goAsync()` and finish after IO, or serve callbacks from a cache and
  refresh durably in the background. Test with a deliberately blocked dispatcher.

### F16 - Image pan/zoom state leaks between File Viewer tabs

Severity: medium. Classification: confirmed, new.

- Evidence: bitmap identity is keyed by `cacheFile.path`
  (`FileViewerScreen.kt:1397-1399`), but scale and offsets are unkeyed `remember`
  state (`:1416-1418`) applied at `:1510-1522` through the shared panel call site
  (`:509-517`).
- Trigger and impact: zoom/pan image A, then select image B. B inherits A's
  transform and can appear cropped or off-screen.
- Test gap: the tab journey switches only among text files.
- Action: key presentation state by `cacheFile.path` or wrap the panel in
  `key(cacheFile.path)`; add an image-to-image transform/reset journey.

### F17 - Message-preserving conversation bounds can return only tool events

Severity: medium. Classification: confirmed algorithmic defect, new.

- Evidence: the repository contract says messages must not be evicted by tool
  activity (`AgentConversationRepository.kt:83-98,254-269`). When
  `messageCount >= maxEvents`, it instead returns the last arbitrary events
  (`:271-280`); default cap is 500 (`:338-344`).
- Trigger and impact: 500 messages followed by 500 tool results returns the 500
  tools and zero prose, defeating the preservation contract.
- Test gap: the preservation test has two messages and exercises only the
  `messageCount < maxEvents` branch.
- Action: when messages meet/exceed the cap, return the last messages in document
  order; add exact-cap, over-cap, trailing-tool, and interleaved cases.

### F18 - A future quota-deadline revision is reported as an early reset

Severity: medium. Classification: confirmed algorithmic defect, new.

- Evidence: the documented deadline signal requires the old deadline to have
  elapsed (`tools/pocketshell/src/pocketshell/usage_reset.py:29-32`), but the
  implementation accepts any `cur_reset_at > prev_reset_at` (`:180-202`) and
  gives it a fresh dedup key (`:208-233`). Capture records and pushes every event
  (`usage_capture.py:484-517`). Rolling-window input is present in real fixtures
  but ignored by detection.
- Trigger and impact: a provider revises 15:00 to 16:00 at 11:00 with no strong
  recovery. The app records/notifies an early reset; moving rolling deadlines can
  generate repeated distinct events.
- Test gap: the deadline-positive fixture captures after the old deadline and has
  no rolling flag.
- Action: use deadline advancement alone only after the prior deadline elapsed;
  before it, require strong recovery. Model rolling windows separately.

### F19 - Structural tmux event overflow has no repair path

Severity: low. Classification: evidence-backed load risk, new residual after
#1224.

- Evidence: structural events use a replay-zero, 256-slot `MutableSharedFlow`
  (`shared/core-tmux/.../TmuxClient.kt:888-908`). `tryEmit` records and drops on
  overflow (`:2356-2380`); `EVENT_BUFFER` is 256 (`:2452`), and no overflow path
  schedules authoritative reconciliation.
- Trigger and impact: a burst of more than 256 structural events while the
  subscriber stalls can lose a close/session/layout edge and leave stale
  projection until an unrelated refresh.
- Action: on any structural drop, coalesce a dirty flag and schedule authoritative
  sessions/windows/panes reconciliation; add an overflow recovery test.

### F20 - Distinct release-validation and upstream run provenance are conflated

Severity: medium. Classification: confirmed workflow defect, new.

- Evidence: `.github/workflows/release-emulator-validation.yml:328-333,365-374`
  passes `github.event.workflow_run.html_url`, which identifies the upstream
  Tests run. `ci-nightly-rc-mark.sh:4-12,90-105` and
  `ci-nightly-rc-issue.sh:58-80` label it as the Release Emulator Validation run;
  `docs/release.md:84-99` expects that link to locate validation artifacts.
- Trigger and impact: success markers and red issues link to a run that does not
  contain the claimed summary/diagnostics, slowing release and failure triage.
- Test gap: script tests inject arbitrary URLs and do not validate workflow wiring.
- Action: pass a URL built from current `github.run_id`; retain the triggering run
  under a separately named field and structurally test the distinction.

### F21 - Python CI ignores the committed lockfile

Severity: low-medium. Classification: confirmed reproducibility risk, new.

- Evidence: `.github/workflows/tests.yml:1055-1077` installs latest `uv`, creates
  a venv, and runs `uv pip install -e ".[dev]"`, which does not consume
  `uv.lock`. Dependency ranges remain broad
  (`tools/pocketshell/pyproject.toml:20,41-108`) despite the lock policy at
  `:131-137`.
- Trigger and impact: an installer, direct dependency, or transitive release can
  change CI behavior without a repository change.
- Action: pin `uv`, use `uv sync --frozen --group dev`, execute through that
  environment, and add a structural frozen-lock guard.

### F22 - A substantive usage mutation proof never runs

Severity: low. Classification: confirmed coverage/dead-code defect, new.

- Evidence: `scripts/test-usage-missing-window-mutation.sh:1-217` has no caller.
  The unreferenced-test guard claims all assets are reachable
  (`.github/workflows/tests.yml:264-278`) but inventories only `tests/`
  (`scripts/check-tests-referenced.sh:23-39,63-65,273-310`).
- Trigger and impact: the usage proof can rot or be cited while CI remains green.
- Action: wire it into the appropriate lane or delete it; extend dead-test
  detection to top-level test/mutation/self-test naming conventions.

### F23 - The connection “sole authority” migration still keeps two semantic states

Severity: medium. Classification: maintainability/correctness risk, previously
known D28/#766 gap.

- Evidence: the 17,578-line `TmuxSessionViewModel` owns inline
  `_connectionState` (`TmuxSessionViewModel.kt:809`), mirrors intent and projects
  controller shape plus inline payload (`:1585-1674`), gates effects on
  `inlineConnectionStatus` (`:1615-1628`), and separately maps transitions in
  `driveControllerIntent()` (`:1676-1705`). `ConnectionStatusProjection.kt:7-159`
  contains the mixed-authority exceptions.
- Trigger and impact: a new state/recovery edge updated in only one authority can
  make displayed status and effects disagree during interleavings.
- Action: complete the D28 hard cut: controller-owned display payload, delete
  inline state/intent mirroring/mixed-source projection, then extract connection,
  pane-runtime, conversation, input, and cards owners.

### F24 - Five-minute cached-runtime expiry is only lazy

Severity: low. Classification: confirmed lifecycle defect, previously known
under #2309.

- Evidence: only `put()` and `activate()` sweep
  (`TmuxSessionRuntimeCache.kt:20-35,59-73,99-115`); expiry is otherwise just a
  predicate (`:263-290`). Cached entries retain control client, jobs, and lease
  (`:330-360`).
- Trigger and impact: park A, remain in B beyond five minutes, and perform no
  cache mutation; A consumes output/resources indefinitely despite the TTL.
- Action: schedule generation-keyed expiry cleanup, or delete the TTL and
  document capacity-only eviction.

### F25 - Python tests read the maintainer's live engine registry

Severity: low. Classification: confirmed test isolation defect, new.

- Evidence: `test_engines.py:223-231` assumes exactly four built-ins but does not
  isolate `XDG_CONFIG_HOME`; the autouse fixture changes only an aplexer flag
  (`tests/conftest.py:15-17`). Production reads ambient XDG/HOME configuration
  (`engines.py:311-335,537-568`).
- Reproduction: on the audit host, the frozen suite produced 1,108 passed and one
  failure because the live `zcodex` registry entry appeared in that assertion.
- Action: isolate `XDG_CONFIG_HOME` in an autouse fixture; keep a separate,
  explicit custom-configuration test.

### F26 - Navigation reports every destination twice

Severity: low. Classification: confirmed redundant work, new.

- Evidence: `LaunchedEffect(current)` reports route changes
  (`MainActivity.kt:962-974`), while every navigation helper reports synchronously
  through `setCurrentDestination` (`:978-986,1038-1049`). Host/session reports
  each launch update-check work (`MainActivity.kt:493-518`,
  `UpdateCheckScheduler.kt:197-205,226-233`).
- Impact: duplicate coroutine launches, mutex/store work, and misleading
  exactly-once observer semantics on every transition.
- Action: choose one reporting owner; preserve synchronous tmux-target capture
  separately or deduplicate against the last reported destination.

### F27 - Composer draft persistence is inert end-to-end

Severity: low. Classification: confirmed redundant code, new.

- Evidence: `TmuxSessionScreen.kt:180-187` declares all three old draft
  parameters unused. MainActivity still owns/restores/forwards/saves them
  (`MainActivity.kt:246-271,404-408,521-523,752-758,875-882,1883-1897`), and
  `LastSessionStore.kt:145-167,206-217,286-299,515-528` still persists the field.
- Impact: false behavior, obsolete preference/state plumbing, and wider APIs in
  already oversized session code.
- Action: hard-delete the screen/navigator parameters, Activity state/callbacks,
  `LastSession.composerDraft`, and preference key; rely on unified composer draft
  persistence.

### F28 - `PortUsageDao` has no consumer

Severity: low. Classification: confirmed dead code, already noted in the
2026-08-13 test audit.

- Evidence: `PortUsageDao.kt:27-45` has no production caller beyond the database
  accessor and DI provider (`AppDatabase.kt:62-72`, `StorageModule.kt:86-96`).
- Impact: generated Room/Hilt surface falsely suggests active port-usage tracking.
- Action: remove the DAO, accessor, and provider while retaining the entity/table
  and migrations required for upgrade compatibility.

### F29 - Shared icon controls are undersized and announced as punctuation

Severity: low-medium. Classification: confirmed accessibility/redundancy defect,
new.

- Evidence: Breadcrumb Back/More use glyph labels
  (`shared/ui-kit/.../Breadcrumb.kt:61,116`) in a 36 dp clickable box without an
  explicit semantic label or button role (`:120-140`), below the 48 dp project
  rule (`docs/design-system.md:249-252`). Drawer close is also 36 dp and exposes
  the visible `x` glyph (`TmuxSessionDrawer.kt:119-135`).
- Impact: ambiguous TalkBack announcements and small touch targets.
- Action: use one labeled icon-button primitive with at least 48 dp hit region,
  `Role.Button`, explicit descriptions/onClick labels, and semantics/size tests.

### F30 - Test-only pane proof state machines ship in production source

Severity: low. Classification: confirmed removable production surface, new.

- Evidence: `ActivePaneRenderOwnerSnapshotForTest.kt:3-70,237-330` defines a
  large test enum plus proof ordering/recovery machines explicitly without a
  production dependency. Their production accessors live inside the 17.5K-line
  ViewModel (`TmuxSessionViewModel.kt:11555-11632`).
- Impact: APK/compile/mutation surface grows in the highest-risk class with logic
  that cannot affect runtime behavior.
- Action: move proof ordering/recovery types into `androidTest`; expose only a
  small immutable runtime diagnostics snapshot through a narrow production
  interface, and delete proof sequencing/retry orchestration from production.

## Areas checked without another finding

- SSH cancellation cleanup, multi-address fallback, exec drain caps, dispatcher
  serialization, port-forward close, and current AutoForwarder cancellation.
- Tmux pane output pre-registration, seed gating, and input queue accounting.
- Room v1-v17 migrations, especially host rebuild and dead-table preservation.
- Current usage-history file locking/fsync/rotation and concurrent writes.
- File Viewer text-cache/workspace identity and late-result fencing.
- Full-JVM gate/profile sanitization, AVD/Gradle output locking, disk cleanup
  validation, and journey verdict/ledger exit propagation.

Previously audited warm-switch resurrection, in-flight lease disconnect fencing,
lease-state lossy emission, RealTmuxClient lifecycle atomicity, and broader
freshness findings remain owned by the 2026-08-23 audit and are not duplicated
here.

## Follow-up hygiene

This is a point-in-time audit, not a permanent architecture specification. File
issues or attach each accepted finding to an existing owner, then delete this
document when every retained finding is closed, rejected with rationale, or
superseded by a later audit. Git history is the archive.

## Resolution ledger (verified 2026-08-31)

All thirty retained findings are fixed on the `audit-fixes` remediation branch,
rebased onto `origin/main` at `15aa079a`. The fixes were implemented in bounded
slices and independently reviewed; the final rebase review was APPROVED after
the controller, trust-cache, and expiry-ownership regressions were repaired.

| Finding | Resolution | Reviewed change |
| --- | --- | --- |
| F1 | Reset Add/Edit identity on route binding and fence stale loads. | #2428 / `4856d290` |
| F2 | Require release tags to point at the validated `main` head. | #2430 / `8acd25b1` |
| F3 | Validate daemon ownership before signaling a stale PID. | #2429 / `13282cc5` |
| F4 | Use overflow-safe image bounds and move decoding off the composition path. | #2428 / `4856d290` |
| F5 | Bound version derivation while draining and terminating child processes. | #2430 / `8acd25b1` |
| F6 | Add authoritative host-key trust resolution, prompt routing, and fingerprint-bound reuse. | #2433 / `4b5be0d8..5fb7bdd6` |
| F7 | Persist authoritative empty revisions with stable identity and latest-wins writers. | #2243 / `0f710cbc` |
| F8 | Replace lossy card filenames with reversible session identity encoding. | #2429 / `13282cc5` |
| F9 | Serialize card mutations and use atomic, unique temporary writes. | #2429 / `13282cc5` |
| F10 | Cancel and generation-fence File Explorer work on request changes. | #2428 / `4856d290` |
| F11 | Bound teardown per runtime and preserve cleanup ownership. | #2431 / `f1138533` |
| F12 | Make grace recovery claims idempotent and retire superseded jobs. | #2431 / `f1138533` |
| F13 | Serialize daemon startup and protect socket/PID cleanup ownership. | #2429 / `13282cc5` |
| F14 | Retain credential-free navigation across configuration recreation. | #2428 / `4856d290` |
| F15 | Keep broadcast callbacks non-blocking while preference IO completes asynchronously. | #2428 / `4856d290` |
| F16 | Key File Viewer transform state by the active image. | #2428 / `4856d290` |
| F17 | Preserve document messages when bounding conversation history. | #2428 / `4856d290` |
| F18 | Require an elapsed prior deadline before reporting a quota reset revision. | #2429 / `13282cc5` |
| F19 | Schedule authoritative reconciliation after structural event overflow. | #2431 / `f1138533` |
| F20 | Separate release-validation run provenance from the upstream Tests run. | #2430 / `8acd25b1` |
| F21 | Pin CI to the committed Python lockfile and a fixed `uv` version. | #2430 / `8acd25b1` |
| F22 | Wire the substantive usage mutation proof into the test lane. | #2430 / `8acd25b1` |
| F23 | Remove the duplicate VM connection authority and project controller state only. | #766 / `a11a4d09` |
| F24 | Add generation-keyed scheduled expiry and bounded teardown for parked runtimes. | #2309 / `092083dc` |
| F25 | Isolate Python engine-registry tests from ambient XDG configuration. | #2429 / `13282cc5` |
| F26 | Deduplicate navigation reporting while retaining synchronous target capture. | #2428 / `4856d290` |
| F27 | Remove inert composer-draft state and persistence plumbing. | #2432 / `01d68357` |
| F28 | Remove the unused `PortUsageDao` surface while retaining upgrade data. | #2432 / `01d68357` |
| F29 | Use labeled, role-aware icon controls with compliant touch targets. | #2428 / `4856d290` |
| F30 | Move pane-proof state machines out of production source. | #2431 / `f1138533` |

The initial scope paragraph above describes the pre-remediation snapshot; the
ledger records the subsequent code and test changes rather than rewriting that
historical evidence.
