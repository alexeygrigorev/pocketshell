# Review Standards

Acceptance bars a reviewer must apply for specific classes of user-facing
change, beyond the general "run build + tests + check each acceptance
criterion" baseline in `process.md`. Load this doc when reviewing terminal,
session-switch, or visual/layout/keyboard work; `.claude/agents/reviewer.md`
carries the operational mechanics of *how* to run these checks.

## Session-switch / reconnect / SSH journeys

For any change touching session switching, tmux attach/reattach, SSH
lease/transport, reconnect, or foreground/background lifecycle, a single
happy-path run is not sufficient (this shipped multiple regressions in the
v0.3.30 wave precisely because it was treated as sufficient). On the
emulator + Docker, the reviewer must:

- Switch between ≥2 live sessions repeatedly (A→B→C→A) and after each
  switch confirm, from authoritative artifacts: the correct (non-stale)
  session is shown, no `Disconnected`/EOF band, the pane content is
  re-seeded (not blank), no spurious reconnect, input routes correctly.
- Background→foreground within the grace window and confirm it reattaches
  **without** a reconnect (and that beyond-grace still reconnects cleanly).
- Base approval on connection-lifecycle logs (`PsTmuxReconnect`,
  `PsTmuxLifecycle`, `ReconnectCauseTrail tmux_probe_result`) + viewport
  artifacts from the same run, never a passing assertion alone.

Code-read + one happy-path screenshot is grounds for `CHANGES REQUESTED`.

**D34 exception for connection-core mechanism fixes** (transport/storm/
reconnect/lease): accept an observed headless real-transport red→green (JVM
+ Docker `:shared:core-ssh:integrationTest`/toxiproxy) as first-class proof
— do not return `BLOCKED` for a missing emulator when a qualifying headless
observation exists. Still reject proof that only exercises a seam/lambda
having fired rather than the symptom-defining signal on the real transport.
The emulator journey remains the batched backstop for anything
user-*visible* (rendered viewport, wrong/blank/stale session, IME/layout).

## Visual / composer / keyboard / layout regressions

Several "fixed + approved + closed" UI issues shipped still broken because
the reviewer verified a narrow proxy (an isolated component test, a render
of one composable) instead of the maintainer's actual on-screen scenario.
For any layout/composer/chrome/keyboard/IME/insets change, or anything
reported as "hidden / clipped / cut off / squished / can't reach":

- **Reproduce the bug as failing first**, on the emulator, before judging
  the fix. If you can't reproduce the original problem, you can't certify
  it fixed.
- **Reproduce the exact reported scenario**, including transient state —
  keyboard up if that's the report, the right pane type (shell vs agent).
- Isolated component tests and Roborazzi renders are the fast first check
  only, never sufficient alone to close an occlusion/layout bug. The
  acceptance is a full-device emulator screenshot of the exact reported
  state, showing every previously-hidden control fully visible and
  tappable.
- **Verify reachability, not presence** — "in the hierarchy" ≠ "user can
  see and tap it."

### The containment-assertion trap

`assertNodeFullyWithinRoot(tag)` / `assertNodeFullyAboveImeOrKeyboard(tag,
...)` (in `app/src/androidTest/.../proof/signals/ComposeSignals.kt`) are the
right assertions to demand — a bare `assertIsDisplayed()` is satisfied by
mere layout participation, not viewport containment. But confirm the
*harness* matches production:

- Every window in the app is edge-to-edge (`targetSdk=35` on Android 15
  makes even a bare `ComponentActivity` edge-to-edge) — the root spans the
  full device including the strip behind the system bars, so
  `assertNodeFullyWithinRoot` must subtract the measured navigation-bar
  strip, or a row painted underneath the nav bar reads as "fully within
  root" while a user physically cannot tap it.
- A bare `setContent` test harness renders ~126px higher than production,
  because `MainActivity` pads its top-level `Surface` with
  `WindowInsets.safeDrawing.exclude(WindowInsets.ime)`. Use
  `Modifier.productionWindowChromePadding()` on the harness root.
- For a synthetic inset (the standard way to reach keyboard-up state
  without a real soft IME on CI's swiftshader AVD), pass the inset value
  Compose actually consumed to `assertNodeFullyWithinSystemBarsContentArea`
  — don't assert against zero.

## Regression-proof validity checklist (per PR, for layout/lifecycle/occlusion/keyboard fixes)

- [ ] Asserts viewport **containment**, not `assertIsDisplayed()`, for the
      control reported hidden/clipped/off-screen.
- [ ] Reproduces the **reported state** (real screen/sheet window, keyboard
      up where relevant) — not a convenient standalone render.
- [ ] No `*StandIn`/`*Proxy` substituting for a view whose cost/geometry
      *is* the symptom, unless explicitly justified in a comment the
      reviewer agrees with.
- [ ] For event-driven flows: both the subscriber-alive AND the
      subscriber-torn-down path are covered.
- [ ] No `assumeTrue(...)`/`assumeFalse(isRunningOnCi())` on the
      load-bearing assertion — inject the state synthetically and hard-fail
      instead, or CI asserts nothing.
- [ ] `scripts/check-test-validity.sh` reports no new unjustified smell.

## Terminal / SSH / tmux / agent artifact review

Base approval on the artifact bundle, not the test result line. Authoritative
evidence: `*-viewport.png` terminal screenshots, `*-visible-terminal.txt`,
capture/timing summaries, and Docker/emulator/instrumentation logs from the
**same run**. Full-device screenshots are advisory only for terminal content
unless the run's summary shows they agree with the viewport capture.

Reject when: authoritative viewport screenshots are missing, blank,
header-only, or stale; visible-terminal text is missing/empty/contradicts
the screenshots; timing files are missing for a responsiveness claim; logs
are from another run or contradict the claimed result; or a full-device
screenshot is the *only* proof of terminal content.

Local workbench: `scripts/terminal-workbench.sh` (use `RUN_ID=issue-<N>-review`
for a citable rerun). Real-agent CLI rendering:
`REAL_AGENTS=1 scripts/terminal-workbench.sh`. Full setup in
[testing.md](testing.md).

## Reopened / recurring issues — durable-fix gate (D31)

Flag explicitly in the reviewer brief when an issue was ever closed before,
or a sibling issue closed the same symptom. Then the fix must ship with a
regression test that: fails on the bug (red→green proven this run), covers
the whole class (other sites/sessions/agent-kinds/states, not just the one
reported instance), reproduces the maintainer's exact scenario, and runs in
per-push CI or the pre-tag gate. No durable test ⇒ `CHANGES REQUESTED`, not
waivable. Also re-check the area's recently-closed sibling symptoms for
resurrection (adjacency sweep) — a resurrected sibling is blocking even
when the issue's own acceptance criteria pass. Full rationale: `docs/decisions.md`
D31/D32/D33.
