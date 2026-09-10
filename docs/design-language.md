# Design Language

Termius-inspired. Built once in the `ui-kit` shared module so both PocketShell and `ssh-auto-forward-android` converge.

## Surface

- Background: deep navy/charcoal, never pure black. The exact value is
  `tokens.json` § `color.background` (Quiet's `#10171E`) — this line used to
  name `#0D1117`, which the Quiet redesign replaced and nobody updated.
- Elevated cards: one step lighter than background; hairline 1dp border instead of heavy shadows
- Corner radius: one ladder, in `tokens.json` § `radius` — `{4 badge, 8
  chip/tile, 12 field/button/card, 24 sheet}`. A circle is `CircleShape`, never
  half a diameter written as a radius.
- Padding: 16–20dp internal on cards, 12dp between rows

## Colour

- One bright accent (cyan/teal in the Termius spirit) for: active state, connection-state dots, primary actions
- Everything else: neutral grayscale ramp
- Status: a dot carries the STEADY state (connected / disconnected /
  connecting-pulse) with no words. Text labels are reserved for transitional and
  failed states — "Reconnecting…", "Offline · Saved list" — which a colour
  cannot express. That split is the whole status vocabulary (#2635 T2); before
  it, `StatusDot` shipped in two files while every header subtitle spent a line
  saying "Connected".
- Semantic colour kept to status only (green = ok, amber = warning, red = error). UI chrome stays neutral.

## Type

- UI chrome: Inter or SF Pro
- Terminal + inline code: JetBrains Mono or Fira Code
- A restrained scale: captions, body, titles, screen headings — four rungs, no
  display type.

**The numbers live in one machine-readable file**:
[`docs/design-kit/design-system/tokens.json`](design-kit/design-system/tokens.json)
§ `type`. `Type.kt` implements it, `docs/design-system.md` names which rung is
for what, and `QuietThemeTokenTest` READS the JSON so the code cannot drift from
it — change the JSON first, in every case.

This document used to restate the scale, and so did three other files, two of
which each declared themselves "the source of truth" while disagreeing by a full
rung (#2630 shipped 28/20/18/16 against a documented 20/16/14/11; #2635
reconciled them onto the JSON). Four copies of a number is four chances to be
wrong; prose here describes INTENT, the JSON carries values.

| Rung            | `tokens.json` | `PocketShellType` | M3 slot         |
|-----------------|---------------|-------------------|-----------------|
| Screen heading  | `type.screen` | `screen`          | `headlineSmall` |
| Title           | `type.title`  | `title`           | `titleMedium`   |
| Body            | `type.body`   | `body`            | `bodyMedium`    |
| Caption / label | `type.metadata` / `type.label` | `metadata`/`label` | `labelSmall` |

Three rungs sit deliberately outside the four-rung chrome scale:
`type.bodyDense` and `type.bodyMono` for dense rows and paths, and
`type.labelMono` for inline counts and IDs in a mono context.

## Components (to live in `ui-kit`)

`docs/design-system.md` § Shared UI Kit is the live catalog. #2635 hard-cut the
components listed here that had zero app consumers after the rewrite deleted the
`app` module — `HostCard`, `SessionRow`, `Breadcrumb`, `CommandChip`, `KeyBar` —
because a "canonical" component nothing uses is an instruction to a future
implementer to converge onto dead code (D22).

- `ScreenHeader` / `ListRow` / `SectionHeader` — the three primitives every
  non-terminal screen is built from
- `StatusDot` — animated for `connecting`, solid for steady states
- `TerminalSurface` — wraps the vendored Termux `terminal-view`; handles swipe/long-press overlays
- `SlideOverPanel` — the port panel pattern; consistent across screens

## Motion

- Sheet transitions: 200ms ease-out
- Session swipe: 1:1 with finger, snap on release, haptic tick at boundary
- Pulse: connection-state dot pulses while connecting
- No bouncy easings, no parallax. Calm.

## Touch targets

- Minimum 48dp tap area everywhere
- 48dp is a *touch* floor, not a row height. Visual density is set separately in
  `tokens.json` § `size` (`listRowMin`, `workspaceRowMin`). Don't read the tap
  floor as a design target and inflate rows past it (issue #2630).
- A row's vertical padding pads its TEXT, not the row, so a row carrying a 48dp
  control still lands on `listRowMin` rather than `listRowMin + 2 × rowPadV`
  (#2635).
- Long-press = always available alternate action
- Edge swipes reserved for quick actions (don't block system back gesture)

## What we are *not* doing

- No iOS-style frosted blur
- No bright illustrations / mascots
- No animated gradients on cards
- No emoji in chrome
