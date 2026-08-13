# Release handoff — v0.4.44

Point-in-time handoff written on 2026-08-13. Read `AGENTS.md`, `process.md`, and
the linked issue history before acting. Current repository state and GitHub
issue state are authoritative when this document is resumed; hashes below are
identity anchors, not permission to reuse stale evidence after `main` moves.

## 1. Stop boundary for the current run

The maintainer explicitly narrowed the current run to:

1. finish [#1602](https://github.com/alexeygrigorev/pocketshell/issues/1602);
2. finish [#1202](https://github.com/alexeygrigorev/pocketshell/issues/1202);
3. stop.

Do not start #822, #766, #1678, a version bump, release validation, or a tag in
the current run. This document is the handoff for a later release owner.

At the current stop boundary:

- #1202 merged through [PR #2118](https://github.com/alexeygrigorev/pocketshell/pull/2118)
  as `aaf249179b3fe27b216b4cc65d36648d5ebb1d58`;
- #1602 merged through [PR #2119](https://github.com/alexeygrigorev/pocketshell/pull/2119)
  as `c7a73646212d2a3ce96280e10296d32139db5e84`;
- both issues are closed;
- latest tag/release was `v0.4.43`;
- Android metadata was `versionName = "0.4.43"`, `versionCode = 90`;
- `tools/pocketshell/pyproject.toml` was `version = "0.4.43"`;
- the next planned release was `v0.4.44`, Android `versionCode = 91`.

Do not tag either issue-merge SHA. The later release blockers in section 3
remain open, and the release owner must validate the final version-bump commit
rather than an intermediate issue commit.

## 2. Completed work inherited by the next release owner

### #1602 — recovered outbound queue

Issue: “message-queue: clogged queue after reconnect — head-of-line blocking +
silent Retry no-op”. It is reopened from a real dogfood recurrence.

The merged implementation covers lease/generation identity, queue-row
promotion, honest offline status, a disabled no-op Retry, draft preservation,
younger-row progress, manual Retry, and exactly-once delivery. Its final
correction uses:

- a genuine non-transport failure marker for the manually retried head, rather
  than the approved auto-recovery marker;
- the synchronous `ComposerDraftPersistence` view for immediate reads;
- a bounded eventual assertion for the asynchronous SharedPreferences backing
  store promotion.

Final helper identity is
`7c7d441bf2f8060f572daad4037522c07860248930a9dc2638a5949006694889`.
The final 29-path manifest is
`e91852e2694cabce0ff143eeea7c294949b0422c4081674d5620ad240d53bf8d`.

Independent [APPROVED evidence](https://github.com/alexeygrigorev/pocketshell/issues/1602#issuecomment-5283873212)
includes:

- final-byte exact-base connected RED at the old offline copy/Retry boundary;
- fresh current-main-composite full JVM: 12,434 Debug + Release tests, zero
  failures/errors/skips;
- selective retired-consumer and stale-callback ABA mutations;
- one reviewer-owned A→B→A emulator/Docker journey with both Offline rows,
  disabled physical no-op, identity and durable-draft promotion, younger-row
  delivery, physical Retry, Retrying/Sending, and exact no-duplicate ledger
  order `[younger, head]`;
- 13 inspected screenshots, stable Docker identity, clean lifecycle ownership,
  and final empty queue.

The maintainer's fresh live recurrence screenshots are attached to
[the issue](https://github.com/alexeygrigorev/pocketshell/issues/1602#issuecomment-5283865370).
They are field evidence, not a replacement for the formal journey above.

### #1202 — durable notification Stop

Issue: “Port-forward notification Stop does not stop forwarding”. It is reopened
because process teardown left `HostEntity.enabled=true`, allowing relaunch to
resume forwarding.

Independent [APPROVED evidence](https://github.com/alexeygrigorev/pocketshell/issues/1202#issuecomment-5282109069)
on current-main composites includes:

- current-main real-notification RED:
  `/tmp/issue-1202-fresh-base-red-r2`;
- current-main candidate composite tree:
  `a3e5fb946a8fe79f6dc07db9186fce839397be30`;
- full JVM: 603/603 tasks, Debug 6,206 and Release 6,194 tests, zero
  failures/errors/skips:
  `/tmp/issue-1202-fresh-composite-full-r1`;
- reviewer M1 one-host SQL and M2 stale-resume adoption selective REDs:
  `/tmp/issue-1202-fresh-mutations-r1`;
- final reviewer connected invocation:
  `/tmp/issue-1202-fresh-connected-r1`.

That one isolated invocation passed exactly these three methods:

1. durable all-host notification Stop + relaunch non-resurrection;
2. original #1202 exactly-one notification + Stop teardown;
3. #1487 live-update and real forwarded-socket bytes-to-refusal contract.

It produced exact 3/3 with no unrelated tests/skips, the two-row durable-state
export, the 30-second relaunch hold, real Docker traffic followed by socket
refusal, notification/service/controller zero, shade screenshots/dumpsys/logcat,
stable fixture identity, and an unchanged source manifest. The exact #1202
post-merge `main` Tests run
[31714427385](https://github.com/alexeygrigorev/pocketshell/actions/runs/31714427385)
completed green across Unit, Python, Docker integration, and the emulator
aggregate.

## 3. Deferred release blockers after the current run stops

Resume in this order. Do not work these lanes in parallel because they overlap
the connection core or share the emulator/Docker fault fixtures.

### A. #822 — honest in-place recovery after a within-grace socket drop

Current source/static preparation is in `.worktrees/issue-822-reopen`.
At the recorded `main` snapshot, the projected full composite tree was
`af76c80df5e90112a837d8971cb53f31487b6045` and the test-only RED tree was
`3e0683d33c8b2d7be96715fc377cb65bf017e99d`. Recompute both after #1602 and
#1202 merge.

Required runtime proof:

- current-main-composite full JVM;
- real Docker integration method
  `Issue1952TypedPassiveDropRealTransportIntegrationTest#realReaderDropWithinGraceHasOneOwnerAndOneFreshHandshake`;
- exact-base connected RED and candidate GREEN for
  `WithinGraceSocketDropForegroundJourneyE2eTest#withinGraceForegroundAfterSocketDropRetainsViewportAndRetryRecoversSameSession`;
- retained READY viewport with honest Reconnecting/unwritable state, actionable
  Retry, no fake Attaching/Live, physical tap, distinct replacement client,
  same session, and a writable post-recovery marker;
- independent review and merge.

### B. #766 — delete the remaining duplicate connection authority

Source/static work exists in `.worktrees/issue-766-s7`. It deletes the residual
`TmuxConnectionState`/inline status mirror and makes the controller the sole
displayed and decision authority.

#766 overlaps #822 in `TmuxSessionViewModel.kt`. Rebase/reconcile only after
#822 merges; never apply the old worktree diff blindly. Preserve the typed
pre-Background state required by #685 and the Attaching passive-drop behavior
required by #895.

Required proof includes focused tests and selective mutations, AndroidTest
compile, canonical unfiltered full JVM, the D28 connected journey set, terminal
artifacts, independent review, and the issue-required maintainer on-device
signoff.

### C. #1678 and #1671 — make the release fault gate real

#1678 source/static work exists in `.worktrees/issue-1678`. It removes the
literal-false skip and makes the five-second RideThrough journey prove that the
same proxy was engaged, no recovery UI/dial/client replacement occurred, and a
post-restore server-side marker was actually delivered.

Required #1678 evidence:

- exact toxiproxy/emulator execution with an isolated fixture;
- positive clean-close control;
- no-op fault/restore/input and observer mutations;
- at least 20 consecutive **executed and engaged** iterations (skips, setup
  aborts, disturbed fixtures, and unengaged toxics do not count);
- the isolated Hetzner/user-space-proxy verification required by the issue;
- independent review and merge.

Then satisfy #1671 itself:

- five consecutive scheduled Nightly Extensive fault-verdict PASS runs on
  `main`;
- no expected-to-fail or skipped test in the blocking matrix;
- `release-emulator-validation` on the release commit with
  `NIGHTLY_FAULT_GATE_DISABLED=0`.

Do not waive this gate. The explicit purpose of #1671 is to stop shipping by
waiving a chronically red fault suite.

## 4. Release execution after every blocker is merged

### Freeze and establish the release commit

1. Freeze non-release merges.
2. Fetch and fast-forward local `main`; require a clean checkout and
   `HEAD == origin/main`.
3. Confirm #1602, #1202, #822, #766, #1678, and #1671 have their required
   verdicts/closure evidence. Do not infer closure from a green narrow test.
4. Inspect the exact `origin/main` Tests workflow. Every relevant required job
   must be terminal green for that SHA; resolve superseded or red runs before a
   bump.
5. Re-check the latest GitHub release and tags. If `v0.4.43` is still latest,
   use `v0.4.44` / code 91; otherwise recompute the next version.

### Version bump PR

On a release branch, change only:

- `app/build.gradle.kts`: `versionCode = 91`, `versionName = "0.4.44"`;
- `tools/pocketshell/pyproject.toml`: `version = "0.4.44"`.

Run at minimum:

```bash
bash scripts/check-version-coupling.sh
scripts/check-pypi-version.sh
git diff --check
```

Run the normal verification required by `process.md`, commit the bump, open a
PR, and merge only after protected checks are green. Then fast-forward local
`main` again and require `HEAD == origin/main` and a clean worktree.

### Taggable validation on stable pushed `main`

This release contains connection, composer, queue, and port-forward lifecycle
changes, so run the terminal and long-running options rather than the minimal
wrapper:

```bash
RUN_ID=release-v0.4.44 \
TERMINAL_RELEASE_GATE=1 \
LONG_RUNNING_TEST=1 \
NIGHTLY_FAULT_GATE_DISABLED=0 \
scripts/release-emulator-validation.sh
```

Before launch:

- use the storage preflight/safe cleanup documented in `docs/testing.md`;
- do not delete an active worktree or evidence bundle;
- verify the release execution profile with
  `scripts/pre-release-confidence-gate.sh --check-profile`;
- ensure no foreign Gradle/emulator/Docker owner is active;
- run detached in a sound observed-loaded/self-verdict transient unit with
  `POCKETSHELL_TEST_MEM=24G`, not an ordinary background shell.

Accept the run only if its summary names the exact `origin/main` SHA, every
automated stage passes, the enabled fault gate passes, and the 10-minute hold
reports `tick_count=6`, `reconnect_events=0`, memory growth below 50 MB, and the
final tick in visible terminal text.

Manually inspect every visual-audit screenshot. Link the summary and all
artifact directories listed by `process.md` from the release issue/PR.

### Push and verify the release

Only after visual inspection:

```bash
scripts/check-pypi-version.sh --check-tag v0.4.44
scripts/push-release-tag.sh --visual-audit-inspected \
  v0.4.44 \
  build/release-emulator-validation/release-v0.4.44/summary.md
```

Then watch the tag-triggered `Build` workflow to terminal and verify:

- the tag points to the validated `origin/main` SHA;
- the GitHub Release exists and contains the expected APK;
- the APK metadata reports `0.4.44` / code 91;
- the workflow artifact is downloadable;
- the `pocketshell` sdist/wheel were built and published to PyPI as `0.4.44`;
- no release job is queued, cancelled, or red.

Do not create or move the tag by hand, do not tag a detached worktree, and do
not use a release-branch validation summary if the merge changed the SHA.

## 5. Explicit non-goals and decisions still owned by the maintainer

- Do not add unrelated backlog work to this release train.
- Do not treat the separate test-speed work as a release blocker unless its
  merged changes make current-main required checks fail.
- #1662 remains a separate maintainer visual-signoff decision. Do not silently
  merge it into the release just because its old candidate remains technically
  approved.
- Physical phone testing is final acceptance, not a substitute for the
  emulator/Docker/fault evidence above.

## 6. Handoff completion checklist

Before declaring `v0.4.44` released, record these exact facts in the release
issue or final handoff:

- final merged commits and issue/PR links for every blocker;
- the version-bump PR and merged release commit;
- exact Tests workflow run for the release commit;
- release-emulator-validation summary SHA and visually inspected artifact paths;
- five scheduled fault-gate PASS run links;
- pushed tag SHA and tag-helper output;
- terminal Build workflow run;
- GitHub Release/APK identity and PyPI `0.4.44` publication.

Anything missing from that list is incomplete release work, not an implicit
pass.
