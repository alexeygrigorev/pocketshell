# CI / Gate Pitfalls

A catalogue of ways a build, test, or gate run can report success while
proving nothing. Check this before citing any run as evidence in a status
comment or review verdict. The unifying rule: "I could not check" must
never read the same as "I checked and it is fine." An absent, truncated,
cached, or killed result must never render as a passing one.

## Never run a repository program through an inferred interpreter

Run `.sh` files with `bash`/`sh` and `.py` files directly — never guess the
interpreter from the filename. `bash <a python program>` mis-executes it
line by line, and a stray `import os` invokes ImageMagick's `import`, which
blocks forever waiting on X: a process that stays "active" with a ~1-line
log, zero test XML, and — if it holds a shared `flock` — every sibling lane
starved with it. Machine-enforced by `scripts/check-script-interpreter-hygiene.sh`
(required `Unit tests` check, no allowlist). A ~1-line gate log is not a
slow run and not a failure — it's a run that never happened; discard it.

## Zero-tests-executed is the most dangerous "green"

A build can report `BUILD SUCCESSFUL` having run zero tests, in several
disguises that each look confident:

- Wrong task — running `:app:testDebugUnitTest` when the required CI check
  is `test` (both variants, including `testReleaseUnitTest`).
- `UP-TO-DATE` skip — Gradle skips a passing test task on re-run while a
  failing one always re-executes, so a naive "run it N times" loop
  manufactures a fake "flaked once, then healthy" streak. Use `--rerun-tasks`.
- Killed process — a backgrounded run gets killed by the session harness
  and reports exit 0 with zero XML files.
- `FROM-CACHE` — with Gradle build caching on, a cache-restored test task
  unpacks the previous run's XML with a fresh mtime and real counts. This
  defeats both a count check and a freshness-by-mtime check at once. The
  console line itself (`FROM-CACHE`) is the only defense — the XML alone
  can't tell you it's stale.
- Stale XML from a killed daemon — a sibling's `gradlew --stop` (or any
  process-killing on a shared box) kills your daemon mid-run; the build
  dies but the previous run's XML survives on disk with an old mtime.
  `FROM-CACHE` and this defeat opposite checks — no single check catches
  both.
- Honest partial counts from a killed run — a run killed partway can still
  write truthful XML for tests it reached before dying, with a nonzero exit
  code that gives it away (e.g. 143). Fresh XML, real counts, and no cache
  markers still isn't enough; you also need the exit code and confirmation
  the specific load-bearing test is in the results.

Trust only: the console line for the task (no `UP-TO-DATE`/`FROM-CACHE`/
`NO-SOURCE` suffix), a test count you asserted yourself > 0, proof the XML
is from this run, and the specific load-bearing test named in the results.
Missing any of those four, you don't have a result — say so.

## Mutation testing has its own vacuous-pass shape

- A mutation that never landed reads exactly like "my tests are
  inadequate." Before concluding a mutant survived, prove it's live: a
  unique in-code anchor you can grep for at a known line, an md5 delta on
  the file, and the red outcome itself. "Gradle re-executed the compile
  task" is not liveness proof in either direction — that's a build-cache
  signal, not an edit signal.
- Build mutant roots with `cp -a` into `mktemp -d`, never symlinks — a
  symlinked mutant root lets an in-place edit (e.g. a guard's own
  `--self-test`) write through into the tree under review.
- A killed mutation loop can leave the mutation in the tree. Restore from a
  backup and verify the restore (diffstat/md5) before trusting any
  subsequent "clean" result.
- Per-run test artifacts get overwritten. A pull taken after a mutant run
  shows the mutant's output, not the prior clean run's — delete the
  artifact directory between runs or stamp/copy to a per-run path
  immediately.
- Keep mutation tooling and artifacts inside your own worktree, never a
  shared scratchpad — a sibling process can overwrite your mutated (or
  unmutated) copy mid-run.

## Systemd transient units: three separate failure modes, not one

Running a gate as a `systemd-run --user` transient unit is the right move
on a shared box (a plain background shell gets killed mid-run by the
session harness), but it introduces its own artifact-authenticity problem:

- Minimal environment — `systemd-run --user` doesn't inherit your shell
  env. Export `ANDROID_HOME`/`ANDROID_SDK_ROOT`/`HOME` explicitly plus a
  `MemoryMax` cap, or it dies fast with an error that reads like a broken
  tree rather than a missing variable.
- A never-loaded unit reads identical to a passing one — `systemctl --user
  show <unit> -p LoadState -p Result -p ExecMainStatus` on a unit that
  never existed (typo, GC'd, never started) returns
  `Result=success`/`ExecMainStatus=0`, same as a real pass. Check
  `LoadState` first.
- But a successful unit also gets garbage-collected, so
  `LoadState=not-found` after the run means either outcome. Sample
  `systemctl show` while the unit is still alive (inside your wait loop),
  or better: have the unit write its own exit status to a file you read
  afterwards, so the artifact comes from the run itself. Don't reach for
  `RemainAfterExit=yes` as the fix — it keeps the unit `active (exited)`
  forever, hanging a naive `until ! systemctl is-active` wait loop. Poll
  `SubState=exited`, or use `systemd-run --user --wait`.

## Piping a long-running process launders its exit code and output

`long-running-cmd | tail` (or any pipeline) gives you the last stage's exit
code, not the one you care about — `set -o pipefail` fixes the code but not
the lost output, since `tail` also discards everything upstream. Have the
process write its own log/verdict file and read the verdict from there; use
`pipefail`/`${PIPESTATUS[0]}` only as a secondary guard. Same family:
`gh run view --job N --log` silently truncates a large job's log — use the
`gh api .../actions/jobs/<id>/logs` endpoint whenever you're asserting a
test count, and treat a log ending on a progress percentage (not a summary
line) as truncated.

## A console stack trace pointing inside `runBlocking`/`runTest` names the wrong line

Gradle's console failure line for an assertion inside a coroutine builder
names the enclosing builder line, not the actual assertion. The true
location is only in `build/test-results/**/TEST-*.xml`'s `<failure>` stack
— read that, not the console. This compounds with the XML-overwrite hazard
above: capture the XML before re-running a failed test task, since a
passing re-run skips the task (stale XML persists) while a failing one
always re-executes (destroying the evidence).

## Test determinism (the `runTest` virtual-clock class)

The recurring "passes locally, flakes on CI" JVM failure is one class: a
`runTest` virtual clock drives code whose owned background work runs on a
real dispatcher/Handler/Thread not pinned to the test scheduler, so
`advanceUntilIdle()` returns before the real work finishes and CI
contention loses the race.

- Default fix: inject a `StandardTestDispatcher(testScheduler)` into every
  owned scope of the code under test.
- When the worker is genuinely wall-clock (Android Handler/Looper/Thread):
  drive it with a hard-failing, generously-bounded pump — never hand-roll
  one; use the shared `drainMainLooperUntil` helper in `:shared:test-support`
  (`SettlePump.kt`). Its wall-clock deadline is `GENEROUS_SETTLE_DEADLINE_MS`
  (30s) — don't nudge a local constant up to paper over box contention.
- Banned: a single `advanceUntilIdle()` plus an assert on real-thread
  output; a bare `Thread.sleep(N)` as the only sync before a load-bearing
  assert. `scripts/check-test-validity.sh`'s `TIMING1` check flags the
  smell and hard-fails a new bare sleep with no bounded loop.
- Run any connect/lease/reconnect change ≥3× locally, and the full test
  suite (not just the changed class) — a contended dev box often masks
  determinism bugs a CI runner's idle state exposes.
- For any change introducing randomness/timing/jitter, a single green run
  isn't evidence: run N≥20 consecutive reps, or — better when the outcome
  is monotonic in the varied quantity — pin both extremes and run once each.

## Contended-box hazards when multiple agents share the machine

- The Kotlin daemon OOMs under ~20G `MemoryMax`, and the failure looks like
  a compiler bug (`BackendException: Exception during IR lowering`) with
  the real cause buried in the last line (`Not enough memory to run
  compilation`). Tells: zero tests executed, and the same tree compiles
  fine with a larger cap. Use `-p MemoryMax=20G`+ for a full gate — still
  capped, so a bad run can't starve sibling lanes.
- Never hand-roll `./gradlew` in place of `scripts/full-jvm-gate.py` — the
  canonical gate sets explicit `kotlin.daemon.jvmargs`/`org.gradle.jvmargs`
  that the repo's `gradle.properties` doesn't set at all; an ad-hoc
  invocation leaves the daemon on its default heap and OOMs regardless of
  cgroup size.
- Give every on-call/heavy agent its own worktree and log paths. Concurrent
  writers sharing the root checkout or scratchpad have silently reverted
  each other's in-flight edits, unlinked each other's log files mid-run,
  and overwritten same-named scratch files. Working files live inside your
  own worktree; evidence you cite in a verdict must be copied to a durable
  per-agent path (e.g. `~/.cache/pocketshell/evidence/issue-<N>-<role>/`)
  before you post, since the worktree is pruned on merge.
- Prefer one on-call per merge train over one per push.
- A `Process crashed`/signal-9 through the AVD-lock/connected-test wrapper
  is now a real signal (the lock is machine-anchored, not per-worktree) —
  capture the signature, don't just re-run it away.

## Shared-literal / default-flip regressions that no per-PR check catches

Changing a shared string, content description, diagnostic event name, or a
default that selects between two code paths can silently re-point a test's
oracle: nothing fails to compile, the changed test's own assertions pass,
and a pre-existing, unrelated journey on the same production path goes red
only in the batched post-merge run, because its wait/gate depended on the
literal or path you just changed. When you change a shared literal or a
default, grep the whole test tree for the old value and for oracles that
depended on the path you stopped taking, and say what you found.

## Newly-registered journey tests haven't run on CI yet by definition

A test that is both added and wired into `scripts/ci-app2-journey-suite.sh` in
the same commit gets its first-ever CI execution only after merging —
compiling it (`:app:compileDebugAndroidTestKotlin`) proves it links, not
that it passes under swiftshader frame timing. Run any newly-registered
journey class on a real emulator (`scripts/connected-test.sh --suffix
i<issue>`) before merge.

## A DERIVED failure signature recurs whenever its cause does

Some gate signatures are computed from the run's own measurements, so they
reappear for any upstream cause that moves those measurements — matching on
the string re-files the wrong issue. The emulator gate's
`insufficient_remaining_budget` is the worked example: it is a function of
observed suite elapsed, so it recurs whenever the suite is long, whether that
is per-shard overload (#1833/#1850, the matrix is the lever) or genuine
journey failures each paying two full per-class attempts (#2374, the failing
classes are the lever). Run 33181062826 was triaged as a recurrence of #1833
on a byte-identical string while all six shards had in fact already written a
`Failed BOTH attempts` summary; no budget constant had moved.

Before treating a repeated signature as a repeated cause, check the
discriminator the gate emits alongside it — for this one,
`retry_denial_class` (`gate_capacity` vs `journey_failure_inflated_suite`) on
the shard verdict token and in the aggregate's annotations. A signature with
no discriminator is a defect in the gate's evidence, not a mystery.

## Untriggered guards: a self-test that runs nowhere proves nothing

`scripts/test-*.sh` are ordinary scripts, not Gradle tests, so nothing runs
one unless a workflow step names it. Two #2356 self-tests sat in the repo
un-invoked by any job, and the one covering `ci-nightly-rc-mark.sh` also
inherited the developer's ambient `~/.gitconfig` — so it never exercised the
hosted-runner case where git has no tagger identity and `git tag -a` refuses.
The result was a marker mechanism that had never worked and never could.
Grep `.github/workflows/` for any self-test you rely on, and give a
harness that depends on ambient machine state (git identity, `$HOME`, PATH
tools, locale) an isolated environment plus a live-precondition assertion, or
it will pass for a reason unrelated to the code.

## An unterminated markdown-section scan silently annexes the next section

Report parsers in the journey harness key on a header line and then read the
`- ` bullets under it. Setting the "in section" flag on the header without ever
CLEARING it makes the scan run to EOF, so every later section's bullets are
read as if they were the first section's. `summary.md` keeps writing after
`Failed BOTH attempts`: #2355's `Quarantined failures (non-blocking …)` and
#2143's `Shared SSH/tmux fixture was WEDGED …` both follow it with bullets. An
unterminated scan therefore reads a QUARANTINED (deliberately non-blocking)
failure as a blocking one — re-coupling a classifier to exactly the section a
previous issue decoupled it from, and doing it silently, because the wording
that section avoids is the HEADER phrase, not the bullet syntax.

Always clear the flag at the first non-bullet, non-blank line (`f && !/^- /
&& NF { f = 0 }`), reduce each bullet to the identifier the consumer actually
needs (an FQCN, not the bullet's trailing metadata), and build the fixture with
the REAL summary writer rather than a hand-typed header — a handwritten header
drifts out of sync with the producer and the guard then proves nothing.

## A diagnostic stamp read as a verdict puts a failure heading on a GREEN run

The inverse of a vacuous green: a run that passed but whose own artifact reads
like it failed. Two individually-correct decisions compose into it. The
emulator gate's classify step computes `SHARD_BUILD_ATTRIBUTION` *before* every
`write_verdict` branch (so no branch can forget it), and
The deleted journey lane's build-phase timeout helper deliberately read the *preserved
attempt-1* tree (so a retry cannot hide what happened on the first attempt).
Together they mean a shard cut mid-Gradle-build on attempt 1 whose retry then
PASSED writes `CLEAN` + `build_attribution=cold_build_timeout`. An aggregate
rollup keyed on the stamp alone then prints "investigate the build cost" into
the step summary of a fully green run — the artifact a release owner reads
before tagging `validated-rc`.

Attempt-scoped evidence is not the run's outcome. Roll a diagnostic stamp up
into a run-level notice only where it explains the FINAL verdict; leave it in
the shard's own log otherwise, where it is still findable and cannot be
mistaken for a cause. When adding such a branch, enumerate every
`(verdict token x stamp value)` pair the producer can actually emit — including
the recovered/CLEAN ones — and diff the notice count against the pre-change
script for each; "the case I built it for changed and nothing else did" is a
claim that needs the table, not an assumption.

## One wall-clock sample against a hard bound is a coin flip, and hides a mode

A timing assertion of the form "this run took N ms, N must be under BOUND" is
only a measurement when the quantity has a light tail. Over a shaped/lossy link
(or on a shared runner) the noise is STRICTLY ADDITIVE — TCP loss recovery with
exponential backoff, CPU steal, a retry the code takes internally — so a sample
is `structural cost + non-negative noise`, and one sample says almost nothing
about the structure it claims to constrain. Issue #2422's chain measured 12 545 ms
against a hard 12 000 ms bound on a scheduled run, then 6 496 ms on the very next
measurement of the SAME commit, JVM, container and shaper.

Two lessons, and the second matters more:

- Judge such an assertion on the MINIMUM of N samples (the maximum-likelihood
  estimate of a cost with one-sided noise), record the observed distribution next
  to the constant, and add a mutation arm proving a genuinely slower run still
  fails. A budget applied to the minimum can then be TIGHTER than the production
  bound — strictly stronger than the single-sample form it replaces, not a
  widened band.
- Before re-baselining, check whether the outlier is a TAIL or a separate MODE.
  #2422's was a mode: forcing the failing condition put the chain in a
  15.2-17.0 s band with no overlap against the 5.9-8.5 s healthy band, because
  one over-run exec made production evict a warm SSH lease and re-run the whole
  chain on a fresh dial. "Flaky test, widen the bound" would have closed the
  issue and kept shipping the defect. If the outlier band is disjoint from the
  healthy band, you have a bug, not noise.

See also [worktrees.md](worktrees.md) for merge-mechanics pitfalls and
[review-standards.md](review-standards.md) for reviewer-side acceptance
bars this catalogue feeds into.
