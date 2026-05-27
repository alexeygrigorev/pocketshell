# UX Rules: Transitions + Element Placement

Codified from the journey-level UX audit in [#163](https://github.com/alexeygrigorev/pocketshell/issues/163). This doc is the source of truth for **where** UI elements live across screens and **how** they animate when the user moves between screens or surfaces. It complements [design-language.md](design-language.md) (which locks colour, type, and spacing) and does not redefine those.

Material 3 is the base. Deviations are explicit and justified — never implicit.

The goal of this doc is not to constrain creativity. It is to keep the headline user journeys (`docs/`-tracked in [#163](https://github.com/alexeygrigorev/pocketshell/issues/163) under "Headline user journeys") visually consistent so that one screen's "back" gesture, "primary action", or "sheet open" feels identical to every other.

---

## Placement Rules

These describe **where** persistent UI elements live within a screen, so that moving across screens never makes the user re-learn the layout.

1. **Back affordance — always top-left of the breadcrumb row.**
   Rationale: matches the platform expectation for navigation depth. Applies to: every screen that can be popped (e.g. `TmuxSessionScreen` breadcrumb chevron, `AddEditHostScreen` close icon, `UsageScreen` back arrow).
2. **More / kebab menu — always top-right of the breadcrumb row.**
   Rationale: secondary actions live opposite the primary back affordance; the row reads left-to-right as "go back ↔ peek at extras". Applies to: `TmuxSessionScreen` overflow menu, host-list overflow menu, `SessionScreen` actions menu.
3. **Primary action FAB — always bottom-right, 56dp minimum.**
   Rationale: thumb reachability on a Pixel 7 in one-handed grip; aligns with Material 3 FAB conventions. Applies to: `HostListScreen` `+` host FAB, `TmuxSessionScreen` mic FAB.
4. **Status / connection line — directly below the breadcrumb, above any tab row.**
   Rationale: status is a property of the current destination, so it must sit attached to the destination header, not floating above main content. Applies to: `TmuxSessionScreen` "connecting…" line at line 356, host-list usage strip, `SessionScreen` agent-detection chip.
5. **Tab row — immediately below status, spans full width.**
   Rationale: tabs are co-located controls for one destination; they must not float or change horizontal anchor between screens. Applies to: `TmuxSessionScreen` Terminal/Conversation tabs.
6. **Window strip — render only when `windows.size > 1`.**
   Rationale: a strip with one item adds vertical chrome without choice; users learn to ignore strips that never act. Applies to: `TmuxSessionScreen` WindowStrip at line 400 (see Breakage 3).
7. **Main content (terminal / conversation / form) — fills remaining vertical space, no fixed minimum.**
   Rationale: the terminal viewport is the user's reason for being here; chrome shrinks before content does. Applies to: `TmuxSessionScreen` terminal pane, `SessionScreen` conversation list.
8. **Bottom input controls — always above the keyboard when IME is visible, above the system navigation bar otherwise.**
   Rationale: input affordances must move with the IME so the user never has to hunt for them; `imePadding` is mandatory. Applies to: `PromptComposerSheet` action row, `SessionScreen` key bar, future conversation-reply input ([#160](https://github.com/alexeygrigorev/pocketshell/issues/160)).
9. **Modal sheets — always slide from bottom (Material 3 `ModalBottomSheet`).**
   Rationale: bottom is the input edge; sheets that come from any other edge break the "input lives at the bottom" mental model. Applies to: `PromptComposerSheet`, `HostTmuxSessionPickerSheet`, `BootstrapSheet`, `TmuxSessionDrawer`.
10. **Conversation pane — inherits all placement rules of the terminal pane.**
    Rationale: Terminal and Conversation are sibling tabs on one screen; the user must not have to re-orient when switching tabs. Applies to: `SessionScreen` conversation view (which when [#160](https://github.com/alexeygrigorev/pocketshell/issues/160) lands gets bottom input controls per rule 8).

---

## Transition Rules

These describe **how** surfaces appear and disappear. Durations and curves follow the Material 3 motion spec ([m3.material.io/styles/motion/overview](https://m3.material.io/styles/motion/overview)) unless explicitly stated.

Material 3 reference tokens used below:

- `motion-duration-short2` ≈ 100ms (micro motion, e.g. small chip in/out)
- `motion-duration-short4` ≈ 200ms (sheet, drawer)
- `motion-duration-medium2` ≈ 300ms (full-screen forward navigation)
- `motion-easing-standard` — cubic-bezier(0.2, 0.0, 0, 1.0)
- `motion-easing-emphasised-accelerate` — for exits
- `motion-easing-emphasised-decelerate` — for entrances

1. **Screen navigation (backstack push / pop) — 250ms slide-in from right, 250ms slide-out to right, `motion-easing-standard`.**
   M3: forward navigation pattern. Forward = new screen slides in from the right; back = current screen slides out to the right, previous revealed. Applies to: `AppNavigator` push/pop transitions.
2. **Sheet open / close — 200ms slide-up + 150ms scrim fade-in, sheet uses `motion-easing-emphasised-decelerate`.**
   M3: bottom-sheet pattern. Scrim fades in slightly faster than the sheet to avoid a flash of un-scrimmed content. Applies to: `ModalBottomSheet` family (composer, picker, bootstrap, drawer).
3. **Tab switch — 150ms cross-fade content, 200ms underline slide, `motion-easing-standard`.**
   M3: tabs pattern. Content fades out then in; the tab underline animates independently. No simultaneous horizontal slide of content — tabs are co-located, not depth navigation. Applies to: Terminal ↔ Conversation tab switch.
4. **Drawer / dropdown menu — 150ms slide-down from anchor + 100ms fade-in, staggered (slide leads).**
   M3: menu pattern. Snappier than a sheet because menus are ephemeral. Applies to: overflow `DropdownMenu`, session drawer overlay (when not a full `ModalBottomSheet`).
5. **Status / banner appear or change — 100ms fade-in + 100ms slide-up, `motion-easing-standard`.**
   M3: snackbar adjacent. Subtle, informational, non-blocking. Applies to: connection-status line, usage chip, error strip.
6. **Pane swipe (HorizontalPager) — 1:1 finger drag, spring snap on release, haptic tick at page boundary.**
   M3: page indicator pattern. Already implemented in `TmuxSessionScreen` pane pager; documented here so it is not changed without consideration. Applies to: tmux pane swipe.
7. **Breadcrumb — 150ms fade-in on first appearance, instant on recompose; live-dot status is a continuous pulse, not a transition.**
   M3: top-app-bar pattern. Pulsing dot is `motion-loop` continuous; the breadcrumb container itself transitions only on first mount. Applies to: `Breadcrumb` composable in `ui-kit`.
8. **Hint / discoverable chip (agent detection, etc.) — 150ms fade-in with subtle elevation entrance; dismissible, dismissal remembered per session.**
   M3: assist-chip pattern. Hints help, they do not startle. Applies to: agent-detected hint chip on `TmuxSessionScreen`.
9. **Keyboard show / hide — OS-owned animation; app supplies `imePadding` and clips content correctly.**
   M3: IME-aware layout. No app-level transition required; trying to add one will fight the OS animator. Applies to: every screen with text input.
10. **Error / crash recovery — 200ms fade-in for the error surface; "Reconnect" tap fades out the error and fades in the new connection status. No glitch / shake artifacts.**
    M3: error-state pattern. Recovery should feel like normal navigation, not like a system fault. Applies to: SSH/tmux disconnect surfaces, full-screen error fallbacks.

---

## Known Breakages

Five concrete journey breakages identified in the [#163](https://github.com/alexeygrigorev/pocketshell/issues/163) audit. Treat this as a checklist for future UX work — when you change the cited file, cite the corresponding rule and either fix the breakage or note why it is still out of scope.

- [x] **Breakage 1 — Session-switch crash on re-attach.**
  `app/src/main/java/com/pocketshell/app/tmux/TmuxSessionScreen.kt:674` (`onReplaceTmuxSession(selectedSessionName)` did not properly dispose the prior `TmuxSessionViewModel`).
  Tracking issue: [#151](https://github.com/alexeygrigorev/pocketshell/issues/151) (closed). Violates rule 1 (transitions) — user expected a smooth fade-to-refresh on the new session, got a crash. Re-open if the regression returns.
- [ ] **Breakage 2 — Drawer label ambiguity ("+ New session" in the session list).**
  `app/src/main/java/com/pocketshell/app/sessions/HostTmuxSessionPickerSheet.kt:113` (TextButton "+ New session" sits inside the "Tmux sessions" list, visually indistinguishable from session names).
  Tracking issue: [#158](https://github.com/alexeygrigorev/pocketshell/issues/158). Violates placement rule 5 (tab/list grouping) and rule 8 (input affordances grouped at bottom). Fix: move the create-session affordance outside the list, relabel to "Create new tmux session", keep "Attach" buttons single-purpose.
- [ ] **Breakage 3 — Single-window `WindowStrip` still renders.**
  `app/src/main/java/com/pocketshell/app/tmux/TmuxSessionScreen.kt:400` (`if (windows.isNotEmpty())` should be `if (windows.size > 1)`).
  Tracking issue: [#158](https://github.com/alexeygrigorev/pocketshell/issues/158). Violates placement rule 6 directly. Fix: gate on `> 1`, and when the strip appears / disappears use the transition rule 5 timing (100ms fade + slide).
- [ ] **Breakage 4 — Composer sheet hides terminal context.**
  `app/src/main/java/com/pocketshell/app/composer/PromptComposerSheet.kt:114` (`rememberModalBottomSheetState(skipPartiallyExpanded = true)` forces full-screen sheet; user cannot see the terminal while composing).
  Tracking issue: [#160](https://github.com/alexeygrigorev/pocketshell/issues/160) (conversation pane rework — reply-in-place is the preferred resolution). Violates rule 9 implicitly (sheets shouldn't displace primary content fully when the primary content is the reason the user opened the sheet). Fix: either drop `skipPartiallyExpanded`, or move the reply input into the conversation pane itself per rule 10.
- [ ] **Breakage 5 — No animated feedback during SSH handshake (2–5s pause feels hung).**
  `app/src/main/java/com/pocketshell/app/tmux/TmuxSessionScreen.kt:356` (`(status as? ConnectionStatus.Connecting)?.let { StatusLine(…) }` is a static text line — no spinner, no elapsed-time counter, no cancel affordance).
  Tracking issue: none yet — file one when this is picked up. Violates transition rule 5 (status changes should have a fade/slide-in) and the broader "user must see liveness" intent of rule 7 (breadcrumb pulsing dot). Fix: add a `CircularProgressIndicator` next to the status text, animate the line in per rule 5, and show "Cancel" after 10s.

When a breakage is fixed and verified, tick the checkbox in the same PR that lands the fix; leave the rule citation untouched so future readers can audit the trail.

---

## How to use this doc

Every future UX-touching issue (`type:design`, `module:app` with visible surface changes, terminal/composer/conversation layout work, navigation changes) must:

1. **Cite at least one rule.** In the issue body, name the placement rule(s) and/or transition rule(s) the change touches. Example: "Implements rule 8 — bottom input controls move with IME via `imePadding`."
2. **Justify deviations explicitly.** If the change deviates from a rule, the issue body must say so, name the rule, and give the reason. Reviewer's job is to confirm the justification is real, not to discover it. A deviation without justification is `CHANGES REQUESTED`.
3. **Update this doc when a rule changes.** If a rule needs to evolve (new platform constraint, new Material spec, new user feedback), update `docs/ux-rules.md` in the same PR that introduces the deviation; do not leave the rules and the code in disagreement.
4. **Cross-reference back into the breakage list.** When picking up one of the five breakages above, link the implementing PR to the breakage entry and tick the checkbox once the reviewer approves and the orchestrator merges.

For reviewers: a UX issue without a rule citation is underspecified — push back before approving. The audit trail lives in the issue body, not in code comments.

---

## References

- Audit source: [#163](https://github.com/alexeygrigorev/pocketshell/issues/163) (the journey-level audit and breakage list this doc codifies).
- Per-surface UX audits: [#152](https://github.com/alexeygrigorev/pocketshell/issues/152), [#153](https://github.com/alexeygrigorev/pocketshell/issues/153), [#154](https://github.com/alexeygrigorev/pocketshell/issues/154), [#155](https://github.com/alexeygrigorev/pocketshell/issues/155), [#156](https://github.com/alexeygrigorev/pocketshell/issues/156), [#157](https://github.com/alexeygrigorev/pocketshell/issues/157).
- Design language (colour / type / spacing — separate concern): [design-language.md](design-language.md).
- Material 3 motion spec: [m3.material.io/styles/motion/overview](https://m3.material.io/styles/motion/overview).
- Material 3 component patterns: [m3.material.io/components](https://m3.material.io/components).
- Mockups (Pixel 7 viewport): [mockups/index.html](mockups/index.html).
