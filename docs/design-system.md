# PocketShell Design System

Locked design targets for PocketShell. Every future UX issue (#152–#157, #160,
…) should reference this document by section number, and every new UI surface
should reach for the tokens defined here rather than invent values.

**This document is the authoritative design-system spec** (#461/#472). When a
token value here and a `.dp`/`.sp`/colour literal in code disagree, this spec
plus the codified token objects in `shared/ui-kit/.../theme/` win — fix the
literal, don't fork the spec.

This document is the **spec**. The **code** lives in `shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/`:

- [`Color.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Color.kt) — colour tokens (§1)
- [`Type.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Type.kt) — typography (§2)
- [`Shape.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Shape.kt) — corner radii (§4)
- [`Motion.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Motion.kt) — motion durations + easing (§5)
- [`Theme.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Theme.kt) — Material 3 wrapper

Visual reference: the static HTML at `docs/mockups/` (Pixel 7 viewport). When
the markdown and the mockup CSS disagree, the markdown wins — the mockups are
an artist's impression of the design, not the implementation truth.

Original spike proposal (with rationale for each value):
[#162 — Design system proposal comment](https://github.com/alexeygrigorev/pocketshell/issues/162#issuecomment-spike-proposal).

Companion docs:

- [`design-language.md`](design-language.md) — short visual brief, the
  Termius-inspired starting point. This document supersedes it for token
  values; design-language.md remains the lightweight "feel" summary.
- [`decisions.md`](decisions.md) — locked product decisions (no background
  work, dark-first, etc.) referenced throughout.

---

## 1. Colour palette

Dark mode is primary. Light mode (future) inverts the surface ramp while
keeping accent + semantic colours on-brand. Hex values are locked.

### Surfaces

| Token | Hex | Usage | Notes |
|-------|-----|-------|-------|
| `Background` | `#0D1117` | Page background, toolbar, app chrome | Deep navy; never pure black |
| `Surface` | `#161B22` | Cards, sheets, elevated containers | One step lighter than background |
| `SurfaceElev` | `#1C2129` | Nested containers, modifier strip, key bar | Highest surface in the stack |

### Text

| Token | Hex | Usage | Notes |
|-------|-----|-------|-------|
| `Text` | `#E6EDF3` | Primary content, headings, primary actions | High contrast on all surfaces |
| `TextSecondary` | `#8B949E` | Secondary labels, breadcrumbs, hints | Intermediate contrast |
| `TextMuted` | `#6E7681` | Captions, timestamps, section labels | Lowest contrast for de-emphasis |

### Accent + UI

| Token | Hex | Usage | Notes |
|-------|-----|-------|-------|
| `Accent` | `#22D3EE` | Primary action, active states, dictate FAB, connection status | Termius-inspired cyan; brand colour |
| `AccentSoft` | `#22D3EE` @ 12 % opacity (`0x1F22D3EE`) | Soft backgrounds for accent contexts (badge, hint chip) | Matches `.accent-soft` from mockup CSS |
| `AccentDim` | `#0891B2` | Borders, dividers on accent surfaces | Darker cyan for hierarchy |
| `OnAccent` | `#04101A` | Foreground on top of `Accent` (FAB icon, primary-button label) | Sourced from `.btn.primary` |

### Semantic / status

Used as dots, badges, progress states — **never** for UI chrome.

| Token | Hex | Usage |
|-------|-----|-------|
| `Green` | `#22C55E` | Connected status, success, positive progress |
| `Amber` | `#F59E0B` | Connecting, idle, caution states |
| `Red` | `#EF4444` | Error status, failures, blocked |
| `Purple` | `#A78BFA` | Agent assistance, secondary accent contexts |

### Borders

| Token | Hex | Usage |
|-------|-----|-------|
| `Border` | `#2D333B` | Standard outline, dividers, key bar separators |
| `BorderSoft` | `#21262D` | Hairline, app-bar divider, soft separators |

### Terminal-specific

The terminal background is *blacker* than the app background on purpose
(see §7 Δ4) so the terminal layer reads as a distinct surface, not part of
the chrome.

| Token | Hex | Usage |
|-------|-----|-------|
| `TermBg` | `#010409` | Terminal viewport background |
| `TermText` | `#E6EDF3` | Terminal foreground (matches `Text`) |
| `TermPrompt` | `#22D3EE` | Prompt colour in xterm (matches `Accent`) |
| `TermComment` | `#6E7681` | Comments / dim text in terminal (matches `TextMuted`) |

### Light-mode portability

Future light-mode work inverts the surface ramp:

| Slot | Dark | Light (target) |
|------|------|----------------|
| `Background` | `#0D1117` | `#F8FAFC` |
| `Surface` | `#161B22` | `#FFFFFF` |
| `SurfaceElev` | `#1C2129` | `#EEF1F5` |

Accent + semantic tokens stay the same in both modes. See `Theme.kt`'s
`PocketShellLightColorScheme` for the in-code mapping.

---

## 2. Typography

Two faces: **UI sans** (Android system default — Roboto on most devices, with
Inter as a follow-up target) and **terminal monospace** (system monospace
today; JetBrains Mono once bundled).

| Slot | Size | Weight | Usage | Notes |
|------|------|--------|-------|-------|
| `headlineSmall` | 20 sp | Bold (700) | App-bar title, screen headings | Restrained hierarchy |
| `titleMedium` | 16 sp | SemiBold (600) | Sheet headers, card titles, section headers | Clear visual separation |
| `bodyMedium` | 14 sp | Normal (400) | Session names, message body, row-primary text | Default reading size |
| `labelSmall` | 11 sp | Medium (500) | Captions, section labels, timestamps, breadcrumb | Used for de-emphasis |
| *Monospace* | 12 sp | Normal (400) | Terminal viewport, inline code, command chips | Fixed-width; user-scalable |

**Dense + mono rungs (Codified, #472 — `PocketShellType` in `Type.kt`).** Three
extra rungs for compact dev-tool rows and terminal-adjacent UI (§7 Δ7/Δ8). They
are **standalone `TextStyle` constants**, deliberately *not* Material `Typography`
slots — overriding a previously-default M3 slot would silently restyle every
component reading `MaterialTheme.typography.*` for it. Call sites opt in
explicitly (`PocketShellType.bodyMono`):

| Rung | Size / Weight | Family | Line-height | Usage |
|------|---------------|--------|-------------|-------|
| `PocketShellType.bodyDense` | 13 sp / Normal | sans | ~1.35× (18 sp) | dense list/tree rows, conversation lines, settings rows — promotes the de-facto 13sp literal |
| `PocketShellType.bodyMono` | 13 sp / Normal | mono | ~1.4× (18 sp) | host subtitles, paths, command chips, tmux names, tool-call previews |
| `PocketShellType.labelMono` | 11 sp / Normal | mono | ~1.3× (14 sp) | inline counts/IDs in a mono context |

Mono rungs use the system monospace family (`JetBrainsMonoFamily`); bundling
JetBrains Mono is deferred (#461 decision #5).

Line-height: Material 3 defaults for chrome; terminal uses 1.65× multiplier
for readability.

Only the four UI slots above are overridden in `PocketShellTypography`;
everything else inherits Material 3's defaults so unanticipated components
fail loud (with default sizing) rather than silently mis-render. The dense/mono
rungs live outside `PocketShellTypography` for the same fail-loud reason.

---

## 3. Spacing scale

**4 dp grid (Codified, #472 — `PocketShellSpacing` in `Spacing.kt`).** All
padding, margin, and gap values land on this 4 dp grid (shared by Linear and
Material 3). The defaults favour the tighter rungs for dev-tool density (§7 Δ6).

| Token | Value | Usage |
|-------|-------|-------|
| `xs` | 4 dp | Micro-gaps (icon-to-label, breadcrumb separators) |
| `sm` | 8 dp | Standard gap (chip-to-chip, row-to-row padding), key bar gap |
| `md` | 12 dp | Card internal padding, row vertical padding (the compact default), section divider height |
| `lg` | 16 dp | Large padding (app bar, sheet header, host-card internal), modal dialog padding |
| `xl` | 20 dp | Extra-large spacing (section label top margin, breadcrumb padding) |
| `xxl` | 24 dp | Screen-level padding, largest list-item spacing |

If a padding/gap/margin value off this grid appears anywhere outside
terminal-viewport rendering, it's a bug or a scope creep.

### 3.1 Density (Codified, #472 — `PocketShellDensity` in `Spacing.kt`)

The compact knob (§7 Δ6). **Visual density is kept separate from the touch
floor:** `rowPadV`/`chipPadV` shrink the *paint* so more rows fit per screen,
while `tapTargetMin` (48 dp) is the a11y hit-area floor every interactive element
must still honour (`Modifier.sizeIn` / `minimumInteractiveComponentSize`).
Shrinking the paint never shrinks the hit area below 48 dp.

| Token | Value | Usage |
|-------|-------|-------|
| `rowMinHeight` | 44 dp | List / tree row minimum height (down from M3's 56–72) |
| `rowPadV` | 8 dp | Row vertical padding (visual density, not the touch floor) |
| `rowPadH` | 12 dp | Row horizontal padding |
| `chipPadV` | 6 dp | Chip vertical padding |
| `chipPadH` | 10 dp | Chip horizontal padding |
| `sectionGap` | 8 dp | Gap between sections / stacked groups |
| `treeIndent` | 16 dp | Indent per workspace-tree nesting level |
| `tapTargetMin` | 48 dp | a11y touch-target floor |

---

## 4. Shape language

Corner radii, sourced from `docs/mockups/styles.css`'s `:root`:

| Component | Radius | Token | Usage |
|-----------|--------|-------|-------|
| Cards / elevated containers | 14 dp | `PocketShellShapes.medium` | `HostCard`, `SessionRow`, usage card, chip-row background |
| Chips / pills / key bar | 8 dp | `PocketShellShapes.small` / `extraSmall` | `CommandChip`, tag, key bar key |
| Bottom sheet | 20 dp (top only) | `PocketShellShapes.large` | Composer sheet, modal sheets |
| FAB / mic button | 28 dp | `PocketShellShapes.extraLarge`, `FabShape` | Dictate FAB (56 dp pill), mic button in composer |
| Icon buttons | 20 dp radius | (call-site `RoundedCornerShape(20.dp)`) | Back button in breadcrumb, close on sheets (36–40 dp square) |

**Borders vs shadows.** Surface separation uses 1 dp hairline borders
(`BorderSoft` or `Border`), **not** drop-shadows. The FAB is the only
chrome element with a shadow (`0 12 px 32 px Accent @ 40 %`) because it is
the primary action. See §7 Δ3.

---

## 5. Motion

Standard durations and easing curves, codified in
[`Motion.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Motion.kt).
Durations are exposed as `kotlin.time.Duration`; call sites convert at the
Compose boundary (`MotionDurations.normal.inWholeMilliseconds.toInt()`).

| Motion type | Duration | Easing | Token | Usage |
|-------------|----------|--------|-------|-------|
| Fast (entry/exit) | 150 ms | `easeOut` | `MotionDurations.fast` + `MotionEasing.standard` | Chip dismiss, tab highlight pulse, hint chip fade |
| Normal (standard) | 200 ms | `easeOut` | `MotionDurations.normal` + `MotionEasing.standard` | Sheet open/close, tab change, item fade |
| Slow (emphasis) | 400 ms | `easeOut` | `MotionDurations.slow` + `MotionEasing.standard` | Progress bar fill, waveform stabilisation |
| Idle pulse | 1.5 s loop | `steps(2)` | `MotionDurations.idlePulse` + `MotionEasing.stepsTwo` | Status dot in `Connecting` state, composer idle waveform |
| Cursor blink | 1.05 s loop | `steps(2)` | `MotionDurations.blink` + `MotionEasing.stepsTwo` | Terminal cursor, text-input caret |

**Locked principle (from §10).** No purely decorative motion. No background
work; no idle animations that would drain battery or require keeping the app
alive. Every animation must serve a comprehension goal — status pulse
communicates "connecting", progress bar communicates "working".

**Where motion is used:**

- Status dots: pulse only while `connecting`; solid when `connected`,
  `idle`, `error`.
- Composer waveform: subtle pulse (4 dp → 6 dp) when idle; freeze at last
  values when transcribing.
- Tab highlight: fade-in + 2 s pulse when conversation tab first appears.
- Hint chip: auto-dismiss after 8 s on first detection; if re-entered,
  persist dismissal.
- Sheet transitions: 200 ms ease-out on open, instant snap on close.
- Session swipe: 1:1 with finger; snap on release; haptic tick at boundary.

Reach for `MotionDurations` / `MotionEasing`, not ad-hoc `tween(180, …)`
literals, so future audits can grep `Motion` references and reason about
every animation in the app.

---

## 6. Terminal-specific components

### 6.1 Breadcrumb (`host › session › window › pane`)

```
[← back] [host › session › window › pane] [⋯ more]
```

- Back button: 36 dp tap target, circular (18 dp radius), `TextSecondary` foreground.
- Crumbs: 14 sp body text. Separators (`›`) in `TextMuted`. Current segment (pane) in `Text`, weight 500. Inactive segments in `TextSecondary`.
- **Pane-label rule:** if a pane has no custom title, display `Pane N` (1-based index) instead of the raw `%N` ID.
- Live-dot: 7 dp green circle with glow (`0 0 6 px green @ 0.7 opacity`) when connected.
- Overflow: one-line; ellipsis on active segment if needed.

### 6.2 Status chips (connection state badges)

Single composable; never freestyle. Call sites pass an enum, the component
renders the dot. No inline labels (status conveyed by colour + animation);
context (tooltip on long-press, sibling text) supplies semantic meaning.

Used in: host card (right side, near kebab), session-drawer header, settings
usage dashboard.

| State | Visual | Duration |
|-------|--------|----------|
| `Connected` | 8 dp solid green circle, glow | Solid |
| `Idle` | 8 dp solid amber circle, no glow | Solid |
| `Connecting` | 8 dp amber circle, pulse (0.3 → 1.0 opacity, 1.5 s) | Until connected |
| `Error` | 8 dp solid red circle, no glow | Solid |
| `Unknown` | 8 dp solid `TextMuted` circle, no glow | Solid |

### 6.3 Agent hint banner

Replaces the current top-center overlay chip.

- **Position:** anchored to the bottom of the terminal viewport (above the key bar / chip row).
- **Appearance:** `AccentSoft` background, 10–12 px padding, 10 dp radius, 1 dp `AccentDim` border. Text: "Claude detected. Visit Conversation tab" or "New agent output available".
- **Lifetime:** auto-dismiss after 8 s. On first visit to Conversation tab, remove the banner from state. Don't re-show unless a new agent is detected.
- **Animation:** fade-in (`MotionDurations.fast`), auto-dismiss fade-out (`MotionDurations.fast`).

### 6.4 Tool-call row (Conversation pane, #160)

Subtle, non-intrusive.

```
[▶] Tool: bash (run) | $ command-here... [dim: 11sp mono]
```

- Leading chevron (`TextMuted`): tap to expand / collapse.
- Tool name (`Accent`, bold): "Bash", "Python", etc.
- Trailing preview: first ~40 chars of the tool call, monospace 11 sp `TextSecondary`, ellipsis.
- Row background: `Surface` + 1 dp `BorderSoft` border, 10 dp radius, 10–12 px padding, 12 px margin top/bottom.
- Expanded state: full command, output (if available); chevron rotation indicates state.

### 6.5 Workspace tree row (host detail)

Compact active-only hierarchy for the host-detail workspace view.

- Target structure follows `mockups/tree/index.html`'s terminal-style
  outline: app bar title is the host, actions are compact icon controls,
  then the tree starts immediately with workspace roots. Avoid an extra
  "Workspace" label or repeated root path text inside each root row.
- Configured roots render first, in configured order. Sessions outside those
  roots render after them under one neutral group in tree mode. Flat mode
  is a simple project list and must not add an "outside roots" callout.
- The screen should stay in a clear loading state until both local configured
  roots and the first remote session/project snapshot are ready. Do not render
  a roots-only placeholder tree and then move rows after the SSH probe returns.
- Project rows are collapsed by default. Expanding reveals active sessions
  only; inactive scanned folders belong in add/browse flows, not the primary
  tree.
- Hierarchy comes from indentation, subtle connector lines, and spacing. Do
  not put branch glyphs, bullets, or ASCII tree art in row titles.
- Root rows are compact `Surface` bands with subtle counts such as
  "3 active · 5 sessions"; no pin chip is needed because every root in this
  section is configured. Project rows use a lighter `Surface` treatment,
  6-8 dp radius, and 7-9 dp vertical padding. Session children use
  `SurfaceElev`, 8 dp radius, and a 1 dp connector line at the left.
- Active/idle state is a 7-8 dp status dot: green for active/attached or
  agent-backed sessions, amber for idle detached shells. Do not add prose
  status labels unless accessibility text is needed.
- Counts are subtle neutral pills (`SurfaceElev` + `BorderSoft`, 10-11 sp)
  such as "2 sessions" or "2 sessions, 1 agent". They stay single-line at
  phone width; truncate the project label before wrapping a count pill.
- Session children prefer human-readable titles. The raw tmux session name is
  secondary fallback text when no richer title source exists.

Implementation audit note for issue #396: visible churn was caused by
`FolderListViewModel.bind()` emitting `Ready` from the watched-root DAO before
the first remote probe had resolved sessions, scanned projects, and root
expansion. A first probe could also start with `lastWatchedFolders` still empty,
rendering sessions outside configured roots before a later DAO/probe cycle moved
them under roots. Keep root/project `LazyColumn` items keyed by stable paths so
legitimate refreshes update rows in place.

---

## 7. Material 3 deltas (the 8 justified divergences)

Default position: PocketShell is a **Material 3 dialect**. Divergence is
always explicit and always justified.

| # | Divergence | Rationale | Impact |
|---|------------|-----------|--------|
| **Δ1** | **Corner radius 14 dp on cards** instead of M3's 12 dp | Aligns with the mockup CSS (`--r-card: 14px`) and the live codebase. The 2 dp bump is imperceptible to users but consistent with intent. | `HostCard`, `SessionRow`, usage cards. No behaviour change. |
| **Δ2** | **Accent cyan `#22D3EE`** instead of M3's default teal | PocketShell's Termius-inspired design calls for a bright cyan. M3 teal is cooler; cyan is warmer and more terminal-native. | `primary` slot in the Material scheme, FAB, action highlights. M3's teal would make the dictate button and status dots feel corporate instead of "terminal-adjacent". |
| **Δ3** | **No drop-shadows except FAB; hairline borders for surface separation** | Mobile battery + visual restraint ("very busy, very crowded" → favour subtraction). M3 defaults to elevation shadows on cards; we use hairline 1 dp `BorderSoft` borders instead. | Cards, sheets, key bar. Cleaner, faster rendering, less visual clutter. The FAB shadow (`0 12 px 32 px Accent @ 40 %`) is allowed because the FAB is the primary action. |
| **Δ4** | **Terminal viewport background (`#010409`) is blacker than app background (`#0D1117`)** | Preserves the xterm-style dark terminal on a slightly-lighter UI background. Keeps the terminal layer visually separable from the surrounding chrome. M3 would collapse these to a single tone. | `TermBg` token + custom styles on `TerminalSurface`. Users immediately see "terminal content vs UI chrome" without cognitive load. |
| **Δ5** | **Typography scale 11 – 20 sp only** (no `displayLarge` / `headlineMedium`) | Mobile-first design. Large display sizes waste viewport. Restrained 4-tier scale matches the "reduce clutter" directive. | All text sizes defined in `PocketShellTypography`. This is a scope reduction, not a content change. |
| **Δ6** | **Compact density** (`PocketShellDensity`): list rows ~44 dp, 8 dp row / 6 dp chip vertical padding, tight section gaps | Dev-tool density — the most-requested change (#454/#455/#459 all ask for compact). Roughly M3 density −2…−3. | Rows/chips/trees. Visual density stays separate from the 48 dp touch floor (§3.1). |
| **Δ7** | **Mono as a real type role** (`PocketShellType.bodyMono`/`labelMono`) instead of a stray `FontFamily` | Terminal-adjacent UI (paths, host subtitles, command chips, tmux names, tool-call previews) should read consistently as code, like VS Code/Warp treat mono as a first-class UI role. | Standalone `TextStyle` constants; system monospace until JetBrains Mono is bundled (deferred). |
| **Δ8** | **13 sp `bodyDense` rung** between `labelSmall`(11) and `bodyMedium`(14) | 13 sp is already the 2nd most-used size in the app (×87) — a de-facto token. Promote it instead of fighting it. | Dense list/tree rows, conversation lines, settings rows. Standalone `TextStyle`, not an M3 slot. |

All eight deltas are justified by either (a) PocketShell's mobile-terminal
context, (b) existing implementation in the CSS mockups and codebase, or
(c) explicit design principles in [`decisions.md`](decisions.md).

---

## 8. Worked example: host card (re-spec of #155)

**Current issues.** Usage badge competes with the status chip; kebab is quiet
to the point of invisibility.

**Re-spec using design system tokens:**

```
┌─────────────────────────────────────────────────┐  ← Surface (radius medium = 14 dp)
│ Avatar │  Walkthrough      [● connected] [⋮]   │    ← md (12 dp) internal padding
│ (40dp) │  testuser@10.0.2:2222                  │
│ 'D'    │  (12 sp mono, TextMuted)               │
└─────────────────────────────────────────────────┘
```

- **Container:** `Surface`, `PocketShellShapes.medium` (14 dp), 1 dp `BorderSoft`. No drop-shadow (Δ3).
- **Avatar (left):** 40 dp circle on `SurfaceElev`, single letter `D` in 12 sp `TextSecondary`.
- **Hostname row:** 14 sp `bodyMedium`, `Text`. Status dot (§6.2 `Connected`) on the right.
- **Subtitle:** 12 sp monospace `TextMuted`, nowrap, ellipsis.
- **Kebab:** 40 dp tap target, circular (20 dp radius), `TextSecondary` icon. Always visible affordance.
- **Spacing between cards:** 12 dp (`md`) external gap.
- **Usage badge is demoted** to a kebab tooltip / overflow item, not a row sibling.

Net effect: cleaner hierarchy, status at a glance, secondary actions out of
the breadth scan.

---

## 9. Worked example: bottom toolbar (re-spec of #152)

**Status.** The dictate chip removal and right-edge chip reorder
landed via #221 (follow-up to the #208 right-thumb ergonomics audit).
The mic FAB is now the single dictate affordance, and primary chips
(`keyboard`, `+ snippet`) live in a **sticky right cluster** that sits
outside the scrolling secondary-chip strip so they are always visible
next to the FAB without horizontal-scrolling — regardless of how many
static command chips lead the row.

**Re-spec using design system tokens:**

```
┌──────────────────────────────────────────────────────────┐
│ [git status] [tmux ls] [k logs] ⟷  [⌨ keyboard] [+ snippet] [🎤 FAB] │
│ ←─── scrollable secondary strip ───→ ←──── sticky right cluster ────→
└──────────────────────────────────────────────────────────┘
```

- **Bottom strip composition:** a single `Row` with three child slots,
  left → right: (1) scrollable secondary strip with `weight(1f)`, (2)
  sticky `PrimaryChipCluster` (non-scrolling), (3) mic FAB (fixed 80 dp
  slot).
- **Scrollable secondary strip** (slot 1):
  - Background: `Background` (no elevation).
  - Chips: 8 dp radius (`PocketShellShapes.extraSmall`), `Surface` background, 1 dp `BorderSoft`, `md` (12 dp) internal padding, `sm` (8 dp) gap between.
  - Font: 12 sp monospace, `TextSecondary`.
  - Tap area: ≥ 48 dp.
  - **Order (left → right):** low-frequency static command chips
    (`git status`, `tmux ls`, `k logs`, `clear`) → `dirs` (project
    navigation, raw-SSH only).
  - Overflow: horizontally scrollable (`Modifier.horizontalScroll`),
    so adding more static chips never displaces the sticky cluster.
- **Sticky primary cluster** (slot 2):
  - Same chip styling as the scrollable strip; non-scrolling.
  - **Order (left → right):** `keyboard` → `+ snippet`. `+ snippet`
    sits closest to the mic FAB so the most-tapped composer entry
    points line up inside the right-thumb arc.
  - The cluster pins to the right end of the chip area regardless of
    static-chip count, fixing the round-1 #221 regression where the
    primary chips were pushed off-screen by leading static chips.
  - **The "dictate" chip is removed entirely** (shipped in #221). The
    mic FAB is the single dictate affordance.
- **Key bar** (visible only when the keyboard is open):
  - Background: `Surface`.
  - Keys: 38 dp tall, 8 dp radius, `SurfaceElev` background, 1 dp `Border`.
  - Label: 12 sp monospace `TextSecondary`.
  - Active key: `AccentSoft` background, `Accent` label, border → `AccentDim`.
  - Gap: 5 dp between keys (sub-grid micro-spacing).
- **Mic FAB:**
  - 56 dp pill (`PocketShellShapes.extraLarge` / `FabShape`).
  - `Accent` background, `OnAccent` foreground.
  - Shadow: `0 12 px 32 px Accent @ 40 % opacity` (the **only** allowed chrome shadow — Δ3).
  - Fixed bottom-right, 20 dp inset. Floats above the keyboard.
- **Modifier strip** (Ctrl / Alt / Shift, if shown):
  - Single 40 dp icon row (do not occupy a full row).
  - `Surface` background, 1 dp `BorderSoft`.
  - Modifier icons: 32 dp circles on `SurfaceElev`, `TextSecondary` icon.
  - Active modifier: `AccentSoft` background, `Accent` icon.

Net effect: one dictate affordance, quieter bottom row, more terminal
viewport.

---

## 10. Anti-design (what we explicitly don't do)

- No purely decorative emoji or icons in chrome.
- No background work / no idle animations that drain battery (see locked
  "no-background-work" principle in
  [`decisions.md`](decisions.md)). Animations only run while their host
  screen is in the foreground.
- No motion for motion's sake — every animation must serve a
  comprehension goal.
- No iOS-style frosted blur effects on surfaces.
- No bright illustrations or mascots.
- No drop-shadows except on the FAB (§7 Δ3).
- No design tokens that diverge from Material 3 unless justified (§7).

---

## 11. Status of codification

| Surface | State | Notes |
|---------|-------|-------|
| `Color.kt` | Codified | Surfaces, text, accent, semantic, borders, terminal. |
| ColorScheme slots | Partial (#472 Slice 0) | Slice 0 fills only the provably-inert `inverseSurface`/`inverseOnSurface`/`inversePrimary` trio (no `Snackbar`/`NavigationBar` is instantiated, so nothing reads them today) → zero rendered-pixel change. **Deferred to Slice 1:** `surfaceContainer*` (read by all `DropdownMenu` call sites via `MenuTokens.ContainerColor`, the unchecked `Switch` track, and the M3 `Card` default) and `secondaryContainer`/`onSecondaryContainer` (M3 selected-chip/segmented-button/drawer state). Completing those onto the dev-tool palette is an *intended* visual change (menus shift `#211F26`→`#1C2129`, switch track + cards likewise), so it lands in Slice 1 with an emulator visual audit + maintainer sign-off — not silently inside the zero-change Slice 0. |
| Semantic roles | Codified (#472) | `PocketShellSemanticColors` + `LocalPocketShellSemantic` carry the non-M3 roles (status `active/idle/connecting/error/attention`, `agentAccent`, `accentSoft/accent/accentDim`), sourced from the same palette constants screens use today. |
| `Type.kt` | Codified | 4 UI slots + monospace alias. **+ `PocketShellType` dense/mono rungs (#472):** `bodyDense`(13) / `bodyMono`(13, mono) / `labelMono`(11, mono), standalone `TextStyle`s (not M3 slots). |
| `Shape.kt` | Codified | 14 / 8 / 20 / 28 dp + `FabShape`. |
| `Motion.kt` | Codified | `MotionDurations` (`fast`, `normal`, `slow`, `idlePulse`, `blink`) + `MotionEasing` (`standard`, `linear`, `stepsTwo`). |
| Spacing | **Codified (#472).** | `PocketShellSpacing` (`xs`/`sm`/`md`/`lg`/`xl`/`xxl`) on the 4 dp grid (§3). |
| Density | **Codified (#472).** | `PocketShellDensity` (`rowMinHeight`/`rowPadV`/`rowPadH`/`chipPadV`/`chipPadH`/`sectionGap`/`treeIndent`/`tapTargetMin`) — the compact knob (§3.1, §7 Δ6). |

`UnifiedComposer` (the shared compose/send surface, #196) is re-sourced off these
tokens as of #472 (Slice 0) — zero visual change, proven by a before/after
emulator screenshot diff of the composer body (0 differing pixels).

Migrating existing screens onto the codified tokens is **per-surface
follow-up work**, tracked as small issues against the consumer screens
(#152–#157, #160, …). This document is the lock; the per-screen migration
is separate.
