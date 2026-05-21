# Design Language

Termius-inspired. Built once in the `ui-kit` shared module so both PocketShell and `ssh-auto-forward-android` converge.

## Surface

- **Background**: deep navy/charcoal (`#0D1117` family), never pure black
- **Elevated cards**: one step lighter than background; hairline 1dp border instead of heavy shadows
- **Corner radius**: 12–16dp on cards, 8dp on chips, 24dp on FAB
- **Padding**: 16–20dp internal on cards, 12dp between rows

## Colour

- **One bright accent** (cyan/teal in the Termius spirit) for: active state, connection-state dots, primary actions
- Everything else: neutral grayscale ramp
- **Status dots**: tiny coloured circles (connected / disconnected / connecting-pulse). Never text labels.
- **Semantic colour** kept to status only (green = ok, amber = warning, red = error). UI chrome stays neutral.

## Type

- **UI chrome**: Inter or SF Pro
- **Terminal + inline code**: JetBrains Mono or Fira Code
- Sizes: 11sp captions, 14sp body, 16sp titles, 20sp screen headings — restrained scale

## Components (to live in `ui-kit`)

- `HostCard` — avatar circle (first letter), hostname + `user@host:port` subtitle, connection-state dot
- `SessionRow` — session name, last-activity timestamp, one-line output preview, tag chips
- `Breadcrumb` — tappable segments for `host › session › window › pane`
- `CommandChip` — pill-shaped, monospace, tappable; supports drag-to-reorder
- `StatusDot` — animated for `connecting`, solid for steady states
- `TerminalSurface` — wraps the vendored Termux `terminal-view`; handles swipe/long-press overlays
- `SlideOverPanel` — the port panel pattern; consistent across screens

## Motion

- **Sheet transitions**: 200ms ease-out
- **Session swipe**: 1:1 with finger, snap on release, haptic tick at boundary
- **Pulse**: connection-state dot pulses while connecting
- No bouncy easings, no parallax. Calm.

## Touch targets

- Minimum 48dp tap area everywhere
- Long-press = always available alternate action
- Edge swipes reserved for quick actions (don't block system back gesture)

## What we are *not* doing

- No iOS-style frosted blur
- No bright illustrations / mascots
- No animated gradients on cards
- No emoji in chrome
