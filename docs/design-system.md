# PocketShell Design System

This is the authoritative design-system audit and foundation for issue #461.
It is a docs/spec slice: runtime Kotlin and Compose code must not change as part
of #461 implementation work.

PocketShell is a Material 3 Compose app with a dark, compact dev-tool dialect.
The foundation combines Material 3 structure with terminal/productivity cues
from Warp, VS Code, Termius, and Linear: dark-first surfaces, dense rows,
monospace where content is command- or path-shaped, restrained motion, and
visible but quiet status.

## Source Map

Use these files as citations when migrating screens:

| Area | Current source |
|------|----------------|
| Theme entry point | [`MainActivity.kt`](../app/src/main/java/com/pocketshell/app/MainActivity.kt), [`Theme.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Theme.kt) |
| Navigation inventory | [`AppDestination.kt`](../app/src/main/java/com/pocketshell/app/nav/AppDestination.kt), [`MainActivity.kt`](../app/src/main/java/com/pocketshell/app/MainActivity.kt) |
| Colour tokens | [`Color.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Color.kt) |
| Type tokens | [`Type.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Type.kt) |
| Spacing and density tokens | [`Spacing.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Spacing.kt) |
| Shape tokens | [`Shape.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Shape.kt) |
| Shared components | [`shared/ui-kit/components`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/components) |
| Fast render harness | [`DesignRenders.kt`](../shared/ui-kit/src/test/java/com/pocketshell/uikit/render/DesignRenders.kt), [`scripts/render.sh`](../scripts/render.sh) |
| Emulator visual audit | [`docs/testing.md`](testing.md), [`WalkthroughVisualScreenshotTest.kt`](../app/src/androidTest/java/com/pocketshell/app/proof/WalkthroughVisualScreenshotTest.kt) |
| Visual brief | [`design-language.md`](design-language.md), [`ux-rules.md`](ux-rules.md), [`decisions.md`](decisions.md) |

When this document and the code disagree, treat the disagreement as drift. Fix
the implementation in a migration issue, or update this document only after a
maintainer decision.

## Current Audit

### Tokens

| Token area | Current state | Drift / risk | Decision |
|------------|---------------|--------------|----------|
| Colour | `PocketShellColors` defines dark surface, text, accent, semantic, border, and terminal tokens in [`Color.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Color.kt). `PocketShellSemanticColors` adds status/agent/accent roles via `LocalPocketShellSemantic`. | Many screens still import raw `PocketShellColors` directly. That is acceptable during migration, but new code should prefer `MaterialTheme.colorScheme` for M3 roles and semantic locals for status/agent roles. Terminal selection still has hard-coded token-equivalent colours in [`SmartSelectionAffordanceOverlay.kt`](../shared/core-terminal/src/main/java/com/pocketshell/core/terminal/selection/SmartSelectionAffordanceOverlay.kt). | Keep the existing dark palette. Add no new colours unless a maintainer approves a new role. |
| M3 scheme | [`Theme.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Theme.kt) maps background, surface, surfaceVariant, primary, outline, inverse, and error slots. PocketShell is always dark. | `surfaceContainer*`, `secondaryContainer`, and related selected/container slots remain M3 defaults because filling them is a visible change for menus, switches, cards, chips, and segmented controls. | Migrate M3 container slots in a visual-audited slice, not silently in a token-only slice. |
| Type | [`Type.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Type.kt) overrides `headlineSmall` 20sp, `titleMedium` 16sp, `bodyMedium` 14sp, `labelSmall` 11sp. `PocketShellType` adds `bodyDense` 13sp, `bodyMono` 13sp, and `labelMono` 11sp. | Screen code still has raw `10.sp`, `12.sp`, `13.sp`, `15.sp`, and custom line heights, especially conversation, markdown, keybar, terminal chrome, and legacy local rows. | Use M3 slots for normal chrome, `PocketShellType.bodyDense` for compact rows, `bodyMono` for paths/commands/IDs, and `labelMono` for compact code labels. |
| Spacing | [`Spacing.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Spacing.kt) currently codifies `xs` 4dp, `sm` 8dp, `md` 12dp, `lg` 16dp, plus density tokens. | Older docs referenced `xl`/`xxl`; those tokens do not exist on current `origin/main`. Raw `.dp` literals are widespread in screens and components, including off-grid values like 2, 5, 6, 10, 14, 20, 28, 30, and 38 where component geometry needs explicit tokens. | Keep the base spacing scale small. Add component-specific geometry tokens only when a pattern repeats across surfaces. |
| Density | `PocketShellDensity` defines `rowMinHeight` 44dp, `rowPadV` 8dp, `rowPadH` 12dp, `chipPadV` 6dp, `chipPadH` 10dp, `sectionGap` 8dp, `treeIndent` 16dp, `tapTargetMin` 48dp. | Some compact rows draw below the visual density target, and some touch areas depend on surrounding layout rather than explicit `sizeIn`. | Visual density can be compact; touch targets stay at least 48dp. |
| Shape | [`Shape.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/theme/Shape.kt) maps 8dp, 14dp, 20dp, and 28dp radii into M3 shape slots. | Screens still create local `RoundedCornerShape` values for micro badges, key slots, cards, and sheets. Some are legitimate component geometry; repeated values should move into shared components. | 8dp chip/key, 14dp card, 20dp sheet, 28dp FAB/mic. Avoid new radii. |
| Elevation | No standalone elevation token. Components mostly use borders; [`MicButton.kt`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/components/MicButton.kt) is the visible exception. | Local surfaces sometimes simulate card hierarchy by adding nested panels. | Hairline borders separate surfaces. FAB/mic is the only normal chrome with shadow. |
| Motion | No `Motion.kt` exists on current `origin/main`. Motion is local and ad hoc, for example `MicButton` uses a recording pulse. | Older docs called `MotionDurations` codified; that is stale. `animate*` / `tween` values cannot be audited centrally today. | Define motion values in this spec now; add code tokens only in a later runtime slice. |

### Shared UI Kit

The current reusable catalog lives under
[`shared/ui-kit/components`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/components):

| Component | Current role | Drift / next use |
|-----------|--------------|------------------|
| `ScreenHeader` | Page title, optional subtitle, compact trailing slot. | Use for every non-terminal full-screen header. Do not invent bespoke top bars. |
| `SectionHeader` | Title-case section label with optional count. | Use above row groups, not as large page headings. |
| `ListRow` | Dense row with leading, title, subtitle, trailing, and click slot. | Canonical row for settings, files, repos, keys, folders, share targets, crash reports, costs, and jobs. |
| `HostCard` | Host dashboard card with avatar, subtitle, status, setup, usage, and trailing slot. | Host list should remain the only consumer unless another surface is truly host-card-shaped. |
| `SessionRow` | Session list row with tags. | Folder/session surfaces increasingly use custom rows; migrate repeated session patterns back into one row API or retire this component. |
| `Badge` / `Pill` | Compact labels for agent, shell, active, warning, neutral, and usage states. | Badge roles should replace one-off chips and hand-styled labels. |
| `StatusDot` | Connection/status dot using `ConnectionStatus`. | Prefer this over local dot composables. Extend role mapping if needed. |
| `Kebab` | Shared overflow trigger and menu item model. | Replace raw `DropdownMenu` blocks when menus have common section/destructive/status rows. A kebab opens actions; it must not directly perform or confirm an action. |
| `SegmentedToggle` / `Tabs` | Compact mode/tab controls. | Use for mode switches and Terminal/Conversation tabs; avoid radio groups for view density. |
| `Breadcrumb` | Host/session/window/pane path chrome. | Terminal chrome should converge here or document why it needs a local variant. |
| `KeyBar` / `CommandChip` | Terminal input controls. | Shared surface for #454 and #459; no new command-chip styling. |
| `MicButton` / `MicIcon` | Composer dictation FAB and icon. | Shared surface for #453; no second mic glyph or text-only dictate chip. |
| `PocketShellButton` | Canonical button: `ButtonVariant.Primary` (filled accent CTA), `Secondary` (outlined accent), `Text` (muted Cancel/Retry), `Destructive` (red-text confirm). | Use for EVERY tappable button. Replaces all raw Material `Button`/`TextButton` and the per-screen `ButtonDefaults.buttonColors(Accent…)` block. Do not hand-declare button colours, shape, or weight. |
| `LoadingIndicator` | Canonical **indeterminate** loading affordance: `Bar` (linear "in flight" strip) + `Spinner` (circular "something is happening", `SpinnerSize.Small`/`Medium`, optional label). | Use for ANY "busy, no known percentage" state. Replaces all raw Material `LinearProgressIndicator`/`CircularProgressIndicator`. Do not hand-pick a spinner diameter or bar height. |
| `ProgressBar` | Usage/progress fill (**determinate**, `progress: Float`). | Use only when the percentage is KNOWN (usage quota, download). For unknown-duration work use `LoadingIndicator` instead. |

### Implementation Drift Themes

The audit found these repeated drift patterns:

- Raw `PocketShellColors` are still the common path in screens. Keep existing
  code until each surface migrates, but new components should expose semantic
  roles instead of colour parameters.
- Raw `.dp` and `.sp` literals are widespread. Not every literal is wrong, but
  repeated row, chip, card, sheet, and timeline values should move into shared
  components or named geometry constants.
- Some screens have local versions of shared ideas: status dots in
  `PortForwardPanelScreen` and `FolderListScreen`, section headers in tmux menus,
  rows in file/share/repo/settings surfaces, and tab/segmented controls around
  terminal chrome.
- Motion is not centralized. Until a runtime token is added, new motion must
  cite this spec and stay local to state comprehension.
- Dialog and sheet styling is repeated across host import, snippets, tmux
  lifecycle, env copy, bootstrap, and composer flows. Standardize the outer
  container and action row before polishing individual content.

## Foundation

### Colour Roles

Dark mode is the product mode. Do not add light-mode conditionals in #461
follow-up work.

| Role | Token | Hex | Usage |
|------|-------|-----|-------|
| App background | `Background` | `#0D1117` | Root surface, page chrome |
| Surface | `Surface` | `#161B22` | Cards, dialogs, sheet content, row groups |
| Elevated surface | `SurfaceElev` | `#1C2129` | Nested controls, active chips, key slots |
| Terminal background | `TermBg` | `#010409` | Terminal viewport only |
| Text primary | `Text` | `#E6EDF3` | Headings, row titles, primary labels |
| Text secondary | `TextSecondary` | `#8B949E` | Subtitles, inactive chrome |
| Text muted | `TextMuted` | `#6E7681` | Captions, timestamps, low-emphasis counts |
| Accent | `Accent` | `#22D3EE` | Primary actions, active state, links, mic |
| Accent soft | `AccentSoft` | `0x1F22D3EE` | Active chip fill, hint/banner fill |
| Accent border | `AccentDim` | `#0891B2` | Accent borders, active separators |
| On accent | `OnAccent` | `#04101A` | Text/icons on accent fill |
| Status active | `statusActive` | `Green` | Connected, attached, healthy |
| Status connecting | `statusConnecting` | `Amber` | Connecting, pending, attention |
| Status error | `statusError` | `Red` | Failed, blocked, destructive confirmation |
| Agent | `agentAccent` | `Purple` | Agent badges and assistant role marks |

Rule: semantic colour is for status, role, and action. It is not page chrome.

### Type Scale

| Role | Token | Size | Weight | Usage |
|------|-------|------|--------|-------|
| Screen heading | `MaterialTheme.typography.headlineSmall` | 20sp | Bold | `ScreenHeader` title |
| Title | `titleMedium` | 16sp | SemiBold | Dialog/sheet titles, card titles |
| Body | `bodyMedium` | 14sp | Normal | Normal content text |
| Dense body | `PocketShellType.bodyDense` | 13sp | Normal | Dense rows, conversation summaries, settings rows |
| Mono body | `PocketShellType.bodyMono` | 13sp | Normal mono | Paths, commands, host subtitles, IDs |
| Caption | `labelSmall` | 11sp | Medium | Section labels, timestamps |
| Mono caption | `PocketShellType.labelMono` | 11sp | Normal mono | Tool badges, counters, IDs |

Do not use display typography in PocketShell chrome. The viewport is for work,
not marketing.

### Spacing And Density

Base spacing tokens are `xs` 4dp, `sm` 8dp, `md` 12dp, and `lg` 16dp.
Screen-level padding should normally be 16dp; row groups can use 12dp internal
padding when density matters.

Density defaults:

| Token | Value | Usage |
|-------|-------|-------|
| `rowMinHeight` | 44dp | Visual minimum for list/tree rows |
| `rowPadV` | 8dp | Dense row vertical padding |
| `rowPadH` | 12dp | Dense row horizontal padding |
| `chipPadV` | 6dp | Dense chip vertical padding |
| `chipPadH` | 10dp | Dense chip horizontal padding |
| `sectionGap` | 8dp | Gap between row groups |
| `treeIndent` | 16dp | Folder tree nesting |
| `tapTargetMin` | 48dp | Accessibility floor for touch targets |

Touch target rule: compact paint is allowed; compact hit areas are not.

### Radius, Elevation, And Borders

| Pattern | Radius | Separation |
|---------|--------|------------|
| List rows and cards | 14dp | 1dp `BorderSoft` or no border inside a grouped surface |
| Chips, badges, key slots | 8dp | 1dp `BorderSoft`; active uses `AccentDim` |
| Bottom sheets | 20dp top corners | `Surface` container, no decorative shadow |
| FAB/mic | 28dp | Accent fill plus the only normal chrome shadow |
| Micro role badges | 3-6dp | Local only until promoted into `Badge` |

Do not nest cards inside cards. Use full-width sections, rows, and simple
surface bands.

### Motion

Motion is a spec decision today, not a codified Kotlin token. Use these values
until a future runtime slice adds `Motion.kt`:

| Motion | Duration | Easing | Use |
|--------|----------|--------|-----|
| Fast | 150ms | ease-out | Chip dismiss, menu fade, small highlight |
| Normal | 200ms | ease-out | Sheet open/close, tab change |
| Slow | 400ms | ease-out | Progress fill, recording level easing |
| Pulse | 1.5s loop | steps/two-state | Connecting status only |
| Cursor blink | 1.05s loop | steps/two-state | Terminal/caret only |

No decorative motion. No idle animation that implies background work or drains
battery. Animation must communicate state.

## Component Catalog

### App Shell

Use `PocketShellTheme` at the root. Full-screen pages use a `Surface` on
`Background`, then `ScreenHeader`, then dense content. Terminal pages are the
exception: terminal viewport is full-height and owns keyboard behavior.

Do:

- Use `ScreenHeader` for title/subtitle/trailing actions.
- Keep headers compact and adjacent to content.
- Use icon buttons or `Kebab` for secondary actions.

Don't:

- Add large hero sections, marketing copy, or nested panel chrome.
- Add a custom top bar when `ScreenHeader` fits.

### Buttons: the canonical `PocketShellButton` (#756)

Before #756 there were **zero** ui-kit buttons. The audit (#756) found ~142 raw
Material `Button`/`TextButton`/`IconButton` call sites, and the **same** accent
primary-CTA colour block
(`ButtonDefaults.buttonColors(containerColor = Accent, contentColor = OnAccent,
disabled… )`, plus `fontWeight = SemiBold` re-applied per call) was hand-copied
into **9 different files** — so the filled-button colour/weight/shape was defined
nine different times and the muted Cancel/Retry treatment was whatever Material
defaulted to per theme.

[`PocketShellButton`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/components/PocketShellButton.kt)
is the single button every tappable button site converges onto. Pick a
`ButtonVariant`, pass a label — colour, shape (8dp), typography, and the disabled
treatment all come from the theme tokens, never from a per-call `colors` block.

| Variant | Use | Treatment |
|---------|-----|-----------|
| `ButtonVariant.Primary` | The page/dialog's ONE main affordance (Add host, Save, Start, Create). | Filled accent container, `OnAccent` SemiBold label. |
| `ButtonVariant.Secondary` | A lower-emphasis affirmative action beside a Primary (e.g. "Browse" next to "Save"). | Outlined: accent label + `accentDim` border, no fill. |
| `ButtonVariant.Text` | The muted, chrome-less action: Cancel / Retry / dialog dismiss / inline links. | No container, `TextSecondary` label. |
| `ButtonVariant.Destructive` | The confirm action of a delete/reset/stop flow. | Red TEXT only (NOT a filled red slab) — matches "destructive confirmation uses red text only on the confirm action". |

Rules:

- **Pick a variant, never a `colors`/`shape`.** There is intentionally no
  per-call colour or radius knob. If a genuinely new treatment is needed, add a
  `ButtonVariant` (and justify it) rather than passing raw values at the call
  site.
- **One Primary per action group.** A screen / dialog action row has at most one
  Primary; everything else is Secondary / Text.
- **Canonical dialog action row** is a `Text` Cancel followed by a `Primary`
  confirm, right-aligned. Destructive dialogs swap the confirm for a
  `Destructive` variant.
- The label-`String` overload is the common case; the `content` slot overload is
  the escape hatch for an icon + label button (it still inherits the variant
  container/shape/disabled treatment).
- Disabled state collapses to ONE muted treatment across all variants
  (`Border` container / `TextMuted` label for filled, muted label for the rest).

### Rows

`ListRow` is the default row primitive. It has leading, title, optional subtitle,
trailing, and click slots. Use `bodyDense` title and mono subtitle when the
subtitle is a path, host, command, or ID.

Long-name invariant: row titles are flexible and ellipsized; trailing controls
are fixed-size and non-shrinking. A long session, host, file, folder, repo, key,
or job name must never compress away the kebab, action button, badge, or status
target. Every interactive trailing control keeps at least a 48dp touch target.

Standard row variants:

| Variant | Pattern |
|---------|---------|
| Navigation row | title, text subtitle, trailing chevron |
| File/path row | title, mono path subtitle, file/folder leading icon |
| Status row | leading `StatusDot`, title, mono/detail subtitle, trailing badge |
| Destructive row | normal row text, destructive action only in menu/dialog confirm |
| Loading row | title stays stable, trailing progress/spinner |

Do not make one-off row padding or font sizes unless the row is a terminal
viewport renderer.

### Sections

Use `SectionHeader` for row groups. Count is inline (`Title - N` or equivalent
component text), not a separate chip unless it is actionable.

Empty sections render a compact neutral row or empty-state block. Do not render
large empty cards with explanatory copy.

### Badges, Pills, And Status Dots

Use `Badge` for role and state labels, `Pill` for short status/cost/quota labels,
and `StatusDot` for live connection state. Dots carry status at a glance; nearby
text supplies context for accessibility.

Do:

- Use green only for active/healthy.
- Use amber for connecting, idle attention, or caution.
- Use red only for error or destructive confirmation.
- Use purple only for agent/assistant role.

Don't:

- Use semantic colours for generic chrome.
- Spell status in all caps in every row.

### Cards And Grouped Surfaces

Cards are repeated items such as `HostCard`, usage provider cards, and summary
cards. A card uses `Surface`, 14dp radius, and a quiet border. Prefer rows inside
a full-width section when content is navigational or list-like.

Do not put cards inside cards. If content needs hierarchy, use spacing, section
headers, leading icons, and muted text.

### Sheets And Dialogs

Sheets handle browse/select/create flows. Dialogs handle confirmation, short
text entry, passphrase/API-key entry, and blocking errors.

Standard sheet pattern:

- `ModalBottomSheet`, `Surface` container, 20dp top corners.
- Title row, optional search/filter, dense `LazyColumn`, fixed action row only
  when needed.
- Rows use `ListRow`, `Badge`, `StatusDot`, and `Kebab` where possible.

Standard dialog pattern:

- `AlertDialog` on `Surface`.
- `titleMedium` title, `bodyMedium` or `bodyDense` body.
- Primary action uses `Accent`; destructive confirmation uses red text only on
  the confirm action.
- Cancel action is muted.

### Overflow Menus

Use `Kebab` where the menu is row/screen overflow. Raw `DropdownMenu` is allowed
only where the menu needs custom anchoring or section layout, and then should
copy the shared density and text treatment.

Overflow invariant: tapping a kebab opens an action menu or sheet. It never
directly triggers a destructive confirmation. Destructive actions are explicit
menu items such as "Stop session" or "Delete key"; selecting that item then
opens the confirmation dialog.

Menus should group actions by scope:

- "In this session"
- "On this host"
- "Diagnostics"
- "Danger"

Destructive actions stay in overflow plus confirmation, not on primary rows.

### Composer Controls

The composer system includes `UnifiedComposer`, `PromptComposerSheet`,
`MicButton`, attachment chips, snippet entry, send controls, and pending
transcription states.

Do:

- Use the shared `MicButton` and `MicIcon` for all dictation entry points.
- Use paperclip/attachment affordances for attachments, not text-heavy buttons.
- Keep recording/transcribing state visible but compact.
- Route conversation sends through the prompt composer, not terminal keybar.

Don't:

- Add a second "dictate" chip beside the mic FAB.
- Invent a new recording glyph or state colour.

### Terminal Chrome And Keybar

Terminal screens use `TermBg`, `KeyBar`, `CommandChip`, compact tabs, breadcrumb
or equivalent terminal location chrome, and right-reachable bottom controls.

Do:

- Show `KeyBar` only for terminal input, not Conversation.
- Keep Terminal/Conversation as compact tabs.
- Put terminal content in the blacker terminal viewport.
- Keep controls reachable near the bottom/right thumb area.

Don't:

- Let command chips push primary composer/snippet controls off screen.
- Add text-heavy chrome above the terminal viewport.

### List And Tree Hierarchy

Folder and session hierarchy uses indentation, section headers, `StatusDot`,
`Badge`, and dense `ListRow` grammar. Actions collapse into overflow.

Do:

- Use `treeIndent` per level.
- Keep folder/session rows compact.
- Show agent/shell with `Badge`.
- Keep `+` create action visible where it is the primary next action.

Don't:

- Use ASCII tree glyphs in row titles.
- Put per-folder "E / ..." action clutter in the main scan path.

### Empty, Loading, And Error States

States should occupy the same structural area as the eventual content.

| State | Pattern |
|-------|---------|
| Loading | Screen header remains, content area shows the canonical `LoadingIndicator` (see below) |
| Empty | One neutral message row plus one primary action if useful |
| Error | Red status/badge, concise message, retry action |
| Permission/setup needed | Amber attention badge/row plus direct action |

Do not use full-page explanation cards unless the screen has no other content.

#### Loading: the canonical `LoadingIndicator` (#756)

Loading is the maintainer's #1 consistency complaint — "sometimes a bar,
sometimes a spinning thing". The cause was structural: the only ui-kit progress
component (`ProgressBar`) was determinate-only, so every "busy, no known
percentage" site fell back to a raw Material 3 indicator hand-configured with
its own size, stroke, colour, and track (the audit found ~21 sites at 8
different spinner diameters and 3+ bar heights). The fix is one shared
component, [`LoadingIndicator`](../shared/ui-kit/src/main/java/com/pocketshell/uikit/components/LoadingIndicator.kt),
with two indeterminate variants. The progress vocabulary is now exactly three
things:

| Affordance | When | API |
|------------|------|-----|
| `LoadingIndicator.Bar()` | Indeterminate, **in-flight strip** — first-connect, reconnecting, refresh. The standard top/inline progress bar. | One height + accent fill on a muted track. No height knob. |
| `LoadingIndicator.Spinner(size, label?, onAccent?)` | Indeterminate, **"something is happening"** — full-screen/section loaders, inline row reveals, pending items, in-button submit progress. | Diameter + stroke come from the enumerated `SpinnerSize` (`Small` inline, `Medium` centered). Optional `label` ("Attaching…", "waiting for tmux panes…") renders below. Set `onAccent = true` for a spinner shown ON an accent-filled surface (e.g. a primary CTA mid-submit) so the arc inverts to the on-accent content colour and stays visible. **Never** a raw spinner `dp`. |
| `ProgressBar(progress, kind)` | **Determinate** — percentage is known (usage quota, download). | The existing `Float` API; the percentage-known sibling. |

Rules:

- Pick a `SpinnerSize` rung, never a free `dp`. If a genuinely new geometry is
  needed, add a rung to the `SpinnerSize` enum (and justify it) rather than
  passing a raw value at the call site — that is what stops the 8-diameter
  drift from coming back.
- Colour comes from `LocalPocketShellSemantic` (accent fill / muted track), so
  no call site reintroduces a raw Material default or per-screen hex. The one
  exception is `Spinner(onAccent = true)`, which paints the canonical on-accent
  content colour (same as an accent button's label) for spinners drawn ON an
  accent fill — use it ONLY inside an accent-coloured container.
- `LoadingIndicator` is decorative + label only (no `onClick`). Any
  cancel/retry lives in a button or row beside it.

Migrating the ~21 existing raw indicators onto this component is tracked as
follow-up slices (non-tmux spinners/bars first; the tmux loading bars are gated
behind the Connection Manager work).

## Screen And Sheet Inventory

### Primary Navigation Screens

This inventory is from [`AppDestination.kt`](../app/src/main/java/com/pocketshell/app/nav/AppDestination.kt)
and the `when` dispatch in [`MainActivity.kt`](../app/src/main/java/com/pocketshell/app/MainActivity.kt).

| Screen | Current components / patterns | Drift | Standardize on |
|--------|-------------------------------|-------|----------------|
| Host list [`HostListScreen.kt`](../app/src/main/java/com/pocketshell/app/hosts/HostListScreen.kt) | `ScreenHeader`, `SectionHeader`, `HostCard`, `Kebab`, host import/share/passphrase dialogs, usage badges. | Strongest shared-component adoption, but host trailing/usage/overflow still owns local glue and dialogs repeat styling. | Keep `HostCard`; move per-host usage/status/overflow grammar into reusable row/card slots. |
| Add/Edit host [`AddEditHostScreen.kt`](../app/src/main/java/com/pocketshell/app/hosts/AddEditHostScreen.kt) | `ScreenHeader`, local tabs, text fields, key dropdown, discard dialog. | Form field, dropdown, and tab styling are local. Embedded key management is not expressed as shared rows. | Shared form section, field error pattern, `SegmentedToggle` or tabs, shared dialog actions. |
| QR scanner [`QrScannerScreen.kt`](../app/src/main/java/com/pocketshell/app/hosts/QrScannerScreen.kt) | `ScreenHeader`, camera preview, info cards, file-pick fallback. | Info cards use local surface grammar. | Standard empty/help card and action row. |
| Settings [`SettingsScreen.kt`](../app/src/main/java/com/pocketshell/app/settings/SettingsScreen.kt) | `ScreenHeader`, `SectionHeader`, `ListRow`, switches/sliders/dialogs, API-key dialogs. | Good row adoption, but controls and repeated API-key dialogs are local. | Settings section + settings row component; shared secret-entry dialog. |
| Usage [`UsageScreen.kt`](../app/src/main/java/com/pocketshell/app/usage/UsageScreen.kt) | `Breadcrumb`, `Pill`, `ProgressBar`, provider cards, blocked badges. | Uses custom card grammar rather than shared card/list-row pattern. | Usage provider card tokenized with shared badge/progress roles. |
| AI Costs [`CostsScreen.kt`](../app/src/main/java/com/pocketshell/app/costs/CostsScreen.kt) | `ScreenHeader`, `ListRow`, local section cards, reset dialog. | Local cards duplicate Settings/Usage section surfaces. | Shared metric card and destructive confirmation dialog. |
| Crash reports [`CrashReportsScreen.kt`](../app/src/main/java/com/pocketshell/app/crash/CrashReportsScreen.kt) | `ScreenHeader`, `ListRow`, mono body for report snippets. | Mostly aligned; long report text needs shared mono detail treatment. | Keep `ListRow`; standardize detail panes/empty states. |
| Port-forward chooser [`ForwardingChooserScreen.kt`](../app/src/main/java/com/pocketshell/app/systemsurfaces/ForwardingChooserScreen.kt) | `ScreenHeader`, `ListRow`, warning dialog. | Mostly aligned; chooser/error state should share setup-needed pattern. | Shared chooser row and permission/setup error state. |
| Port-forward panel [`PortForwardPanelScreen.kt`](../app/src/main/java/com/pocketshell/app/portfwd/PortForwardPanelScreen.kt) | Local status dot, tables, toggles, dense rows, semantic colours. | Does not use shared `StatusDot` or row/card catalog consistently. | `StatusDot`, metric/list rows, progress/error roles, shared table-density rules. |
| Folder list [`FolderListScreen.kt`](../app/src/main/java/com/pocketshell/app/projects/FolderListScreen.kt) | `ScreenHeader`, `SectionHeader`, `ListRow`, `Badge`, `StatusDot`, `Kebab`, `MicButton`, local tree rows, session type picker. | Large local tree grammar; some local status dots/actions; hierarchy can drift easily. | Shared tree row/session row, shared folder action overflow, `Badge` for agent/shell, `treeIndent`. |
| Watched folders [`WatchedFoldersScreen.kt`](../app/src/main/java/com/pocketshell/app/projects/WatchedFoldersScreen.kt) | `ScreenHeader`, `ListRow`, `Kebab`, `SegmentedToggle`, dialogs. | Section cards and edit dialogs are local. | Shared settings/list management rows, shared add/edit folder dialog. |
| Repo browser [`RepoBrowserScreen.kt`](../app/src/main/java/com/pocketshell/app/projects/RepoBrowserScreen.kt) | `ScreenHeader`, `ListRow`, repo cards, clone/open pills. | Repo card duplicates dense row plus badge/action pattern. | `ListRow` with trailing action badge/button and shared loading/error rows. |
| Env files [`EnvScreen.kt`](../app/src/main/java/com/pocketshell/app/env/EnvScreen.kt) | `ScreenHeader`, `ListRow`, `Kebab`, reveal/copy dialogs, copy-from sheet. | Sheet and secret rows are local; reveal/hide action styling should match settings secrets. | Shared secret row, shared copy-source sheet, shared destructive/reset dialog. |
| File viewer [`FileViewerScreen.kt`](../app/src/main/java/com/pocketshell/app/fileviewer/FileViewerScreen.kt) | `ScreenHeader`, text/image/binary viewer states, share/copy actions. | File chrome is local; action placement can diverge from file explorer. | Shared file header/action row, mono text body, empty/error file state. |
| File explorer [`FileExplorerScreen.kt`](../app/src/main/java/com/pocketshell/app/fileexplorer/FileExplorerScreen.kt) | `ListRow`, alert dialog, folder/file listing. | Header mirrors file viewer but does not use `ScreenHeader`; file rows need one shared file grammar. | Shared file browser scaffold, `ListRow` file/folder row, path breadcrumb. |
| Recurring jobs [`RecurringJobsScreen.kt`](../app/src/main/java/com/pocketshell/app/jobs/RecurringJobsScreen.kt) | `Breadcrumb`, `ListRow`, `StatusDot`, `Kebab`, add/edit dialog. | Dialog form and breadcrumb/header pattern differ from other non-terminal screens. | Shared job row, shared form dialog, header decision: `ScreenHeader` or terminal breadcrumb. |
| Raw SSH session [`SessionScreen.kt`](../app/src/main/java/com/pocketshell/app/session/SessionScreen.kt) | `Breadcrumb`, `Tabs`, `KeyBar`, `DropdownMenu`, `ModalBottomSheet`, conversation feed, terminal viewport. | Legacy route has local terminal/conversation chrome and bottom controls. | Terminal shell pattern: tabs, breadcrumb, keybar, composer, overflow menu. |
| Tmux session [`TmuxSessionScreen.kt`](../app/src/main/java/com/pocketshell/app/tmux/TmuxSessionScreen.kt) | Terminal viewport, compact tabs, `KeyBar`, usage badge, `StatusDot`, drawer, menus, lifecycle dialogs, conversation feed. | Largest drift surface: many local rows, menus, status sections, tabs, conversation turns, and lifecycle dialogs. | Extract terminal chrome, session drawer rows, lifecycle dialog, conversation row/tool-call row, and overflow menu patterns. |

### Hosted Sheets, Dialogs, And Secondary Surfaces

| Surface | Current patterns | Drift | Standardize on |
|---------|------------------|-------|----------------|
| Prompt composer [`PromptComposerSheet.kt`](../app/src/main/java/com/pocketshell/app/composer/PromptComposerSheet.kt) and [`UnifiedComposer.kt`](../app/src/main/java/com/pocketshell/app/composer/UnifiedComposer.kt) | `ModalBottomSheet`, `MicButton`, recording/transcribing states, attachment chips, API-key dialog. | Many local controls and state rows; motion is local. | Composer component family: mic, paperclip, state row, attachment chip, send actions. |
| Snippets screen [`SnippetsScreen.kt`](../app/src/main/java/com/pocketshell/app/snippets/SnippetsScreen.kt) | `ScreenHeader`, local library tabs, `ListRow`, `Kebab`, add/edit/rename/delete dialogs. | Tabs and dialogs are local. | Shared tabs/segmented control and edit dialog grammar. |
| Snippet picker [`SnippetPickerSheet.kt`](../app/src/main/java/com/pocketshell/app/snippets/SnippetPickerSheet.kt) | `ModalBottomSheet`, search, rows, explicit send chips, delete dialog. | Sheet row/action density should match composer and command sheets. | Shared picker sheet with search, dense rows, trailing action chips. |
| Agent command sheet [`AgentCommandSheet.kt`](../app/src/main/java/com/pocketshell/app/agentcommands/AgentCommandSheet.kt) | `ModalBottomSheet`, command rows, parameter rows, mono previews. | Similar to snippet picker but separate styling. | Shared command picker sheet and command row. |
| Bootstrap sheet [`HostBootstrapSheet.kt`](../app/src/main/java/com/pocketshell/app/bootstrap/HostBootstrapSheet.kt) | `ModalBottomSheet`, progress/setup states, secondary buttons, confirm dialog. | Setup state cards should align with empty/error/setup-needed patterns. | Shared setup sheet, progress row, action footer. |
| Folder context actions [`FolderContextActionSheet.kt`](../app/src/main/java/com/pocketshell/app/projects/FolderContextActionSheet.kt) | `ModalBottomSheet`, actions, confirm dialog. | Should be the canonical row action sheet for folders. | Shared action sheet rows with destructive confirm. |
| Root project add [`RootProjectAddSheet.kt`](../app/src/main/java/com/pocketshell/app/projects/RootProjectAddSheet.kt) | `ModalBottomSheet`, autocomplete list. | Autocomplete/search rows should match session start directory picker. | Shared path picker sheet. |
| Session type picker [`SessionTypePickerSheet.kt`](../app/src/main/java/com/pocketshell/app/projects/SessionTypePickerSheet.kt) | `ModalBottomSheet`, shell/agent choices. | Choice rows are local. | Shared session-create picker with agent/shell badges. |
| Share host picker [`ShareActivity.kt`](../app/src/main/java/com/pocketshell/app/share/ShareActivity.kt), [`HostPickerScreen.kt`](../app/src/main/java/com/pocketshell/app/share/HostPickerScreen.kt) | Share-specific host/target lists, `ListRow`, dialogs. | Header and picker flow are separate from app host chooser. | Shared chooser row and target picker sheet. |
| SSH keys [`SshKeysScreen.kt`](../app/src/main/java/com/pocketshell/app/hosts/SshKeysScreen.kt) | `ListRow`, `Kebab`, key rows, unlock dialog. | Not a nav destination but important form-management surface; needs `ScreenHeader` when standalone. | Shared key row, secret/unlock dialog. |
| Voice session surface [`VoiceSessionSurface.kt`](../app/src/main/java/com/pocketshell/app/voice/VoiceSessionSurface.kt) | Dictation UI shared by raw SSH and tmux routes. | Must not drift from composer and mic tokens. | `MicButton`, shared recording states, shared transcript controls. |
| Terminal lab [`TerminalLabActivity.kt`](../app/src/main/java/com/pocketshell/app/terminal/TerminalLabActivity.kt) | Dev/test terminal activity. | Not production nav, but can mislead future agents. | Keep as lab-only; do not source product components from it without review. |
| Proof of life [`ProofOfLifeScreen.kt`](../app/src/main/java/com/pocketshell/app/proof/ProofOfLifeScreen.kt) | Legacy proof screen. | Not launcher UX. | Do not use as design precedent. |

## Migration Slices

### Slice 0: Spec And Audit

This document is Slice 0. It locks the foundation, current drift, component
catalog, screen inventory, screenshot plan, and open maintainer decisions. It
does not touch runtime UI.

### Slice 1: Token Hygiene

- Fix stale docs/code references such as the missing `Motion.kt` claim.
- Add runtime motion tokens only after maintainer approval.
- Decide whether to add spacing tokens beyond `lg` or keep repeated values in
  component geometry.
- Fill M3 `surfaceContainer*` and selected-container slots only with emulator
  visual evidence because it changes menus, switches, cards, and selected
  controls.

### Slice 2: Shared Component Consolidation

- Promote repeated local status dots to shared `StatusDot`.
- Extend `ListRow` or add focused variants for file rows, setting rows, secret
  rows, repo rows, and job rows.
- Standardize `AlertDialog` and `ModalBottomSheet` wrappers.
- Add a shared empty/loading/error state component.
- Add a shared overflow menu section model if `Kebab` cannot cover sectioned
  menus.

### Slice 3: In-Flight UI Issues

| Issue | Token/component consumption |
|-------|-----------------------------|
| #453 Prompt composer recording UI | `MicButton`, `MicIcon`, composer state row, `Accent`/`OnAccent`, `bodyDense`, `labelMono`, attachment chip grammar, approved motion pulse only while recording/transcribing. |
| #454 Session bottom controls | `CommandChip`, `KeyBar`, `PocketShellDensity`, `PocketShellType.bodyMono`, 48dp touch floor, sticky primary cluster, no duplicate dictate chip. |
| #455 Folder/session tree | `ScreenHeader`, `SectionHeader`, `ListRow`, `Badge`, `StatusDot`, `treeIndent`, `bodyDense`, `bodyMono`, action overflow, compact row density. |
| #459 Conversation tab | `Tabs`, `UnifiedComposer`, `PocketShellType.bodyDense`, `labelMono`, tool-call row pattern, conversation timeline render target, no terminal keybar in Conversation. |

### Slice 4: Screen Families

Migrate by family, not by random file:

1. Host/settings/list-management family: Host list, Settings, SSH keys, Watched
   folders, Crash reports, AI costs.
2. Workspace family: Folder list, Repo browser, Env files.
3. Terminal family: Session, Tmux session, Recurring jobs, Usage in-session
   chip, port forwarding.
4. File/share family: File explorer, File viewer, Share host picker.
5. Modal family: Composer, snippets, agent commands, bootstrap, folder context,
   session type picker, root project add.

Each migration PR should include before/after screenshots or render artifacts
for the touched family.

## Screenshot And Render Plan

### Fast Render Harness

Use this first for shared tokens and components:

```bash
scripts/render.sh
scripts/render.sh hostListScreen
scripts/render.sh conversationTimeline
```

Current #461 run result:

- Command: `scripts/render.sh`
- Result: success in 28 seconds.
- Generated artifacts:
  - `shared/ui-kit/build/renders/screen-header.png`
  - `shared/ui-kit/build/renders/list-row.png`
  - `shared/ui-kit/build/renders/host-list-screen.png`
  - `shared/ui-kit/build/renders/conversation-timeline.png`

Add render cases before migrating a shared component if no representative case
exists.

### Emulator Visual Audit

Use this for full journey screenshots:

```bash
scripts/phone-walkthrough.sh visual-audit
```

Expected normalized output is documented in [`docs/testing.md`](testing.md):

- `build/phone-walkthrough/<run-id>/screenshots/visual-audit/01-host-list.png`
- `02-host-setup-folder-list.png`
- `03-terminal-session-input-controls.png`
- `04-snippets.png`
- `05b-composer-idle-draft.png`
- `06-composer-recording.png`
- `07-composer-transcribing.png`

This command uses emulator and Docker-backed instrumentation, so it is not the
cheap inner loop. Run it for migration PRs that affect app screens or sheets.

### Focused Screenshot Tests

Run targeted connected tests when a slice touches a covered surface:

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.tmux.TmuxConsolidatedChromeScreenshotTest

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.composer.PromptComposerVisualScreenshotTest

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.projects.FolderContextActionSheetScreenshotTest

./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=com.pocketshell.app.costs.CostsScreenScreenshotTest
```

Many screenshot tests write under
`/sdcard/Android/media/com.pocketshell.app/additional_test_output/<test-name>/`.
Pull or copy those artifacts into the issue report. Full-device screenshots are
advisory for terminal content; terminal viewport captures and text assertions
remain authoritative.

## Do / Don't Rules

Do:

- Start with an existing shared component.
- Cite this document when adding a new UI pattern.
- Use `ScreenHeader`, `SectionHeader`, `ListRow`, `Badge`, `StatusDot`,
  `Kebab`, `Tabs`, `SegmentedToggle`, `KeyBar`, `CommandChip`, and `MicButton`
  before inventing local chrome.
- Keep rows dense but touch targets at least 48dp.
- Use mono for command/path/ID content, not for every label.
- Put destructive actions in overflow plus confirmation.
- Include render or screenshot evidence for visual migrations.

Don't:

- Add new colours, radii, shadows, motion values, or font sizes without a named
  role.
- Add a bespoke top bar, status dot, badge, command chip, mic, or row because a
  screen is "special".
- Use cards as page sections or nest cards inside cards.
- Use semantic colours as decoration.
- Add decorative animation or background-work-looking motion.
- Use legacy `ProofOfLifeScreen` or `TerminalLabActivity` as design precedent.

## Migration Checklist

For every UI migration issue:

- [ ] Identify the screen family and source files.
- [ ] List current shared components already in use.
- [ ] Replace repeated local row/status/badge/menu/sheet/dialog patterns with
      shared components or document why not.
- [ ] Use only approved colour, type, spacing, density, radius, and motion roles.
- [ ] Preserve 48dp hit targets.
- [ ] Add a long-label row case proving the primary title ellipsizes and the
      trailing kebab/action/badge remains visible and tappable.
- [ ] Verify every kebab opens an action menu/sheet first; destructive menu
      items then open confirmation.
- [ ] Add or update a fast render case when touching `shared/ui-kit`.
- [ ] Run `scripts/render.sh` for shared components.
- [ ] Run targeted connected screenshot tests for screen/sheet changes.
- [ ] Attach artifact paths and note whether screenshots are full-device or
      authoritative viewport captures.
- [ ] Leave unrelated runtime churn out of the migration.

## Drift Guardrail

`scripts/check-design-tokens.sh` (issue #461, slice 2 / G7) is a cheap grep-only
guardrail that flags **new** off-ladder UI literals in `app/src/main` so the
incremental token migration doesn't lose ground. It catches:

- `RoundedCornerShape(<N>.dp)` where `<N>` is not an on-ladder radius
  (8 / 14 / 20 / 28 — the `PocketShellShapes` rungs).
- `fontSize = <N>.sp` where `<N>` is not an on-ladder size
  (11 / 13 / 14 / 16 / 20 — the `PocketShellType` rungs).

It uses a committed per-file **baseline** (`scripts/design-token-baseline.txt`)
of the current offender backlog, so it does not try to force the whole Slice D
sweep at once — it only fails when a file gains new offenders (or a brand-new
file ships with any). Genuine sub-ladder component geometry (a thin progress
track, an icon glyph size) is legitimate; name a private `*Radius`/`*Size`
constant with a cite to this document, then `--update` the baseline. Migrating a
screen lowers a file's count; the script reports the improvement and prompts a
re-baseline. The reviewer runs it from the worktree; it is intentionally not a
slow Gradle/emulator job.

```bash
scripts/check-design-tokens.sh            # check against the baseline
scripts/check-design-tokens.sh --update   # re-baseline after a migration
```

## Open Maintainer Decisions

1. Should `Motion.kt` be added to `shared/ui-kit/theme`, and should it expose
   Compose `FiniteAnimationSpec` helpers or plain duration/easing constants?
2. Should the spacing scale remain `xs` through `lg`, or should repeated 20dp,
   24dp, 28dp, 38dp, and 40dp values become named component geometry tokens?
3. When should M3 `surfaceContainer*`, selected-container, switch-track, and menu
   colours move from defaults to the PocketShell palette?
4. Should `SessionRow` be revived as the single session row across folder,
   drawer, and flat session lists, or replaced by a more general tree/session
   row component?
5. Should `Kebab` grow section headers, destructive roles, and disabled/status
   rows, or should complex terminal menus keep local `DropdownMenu` blocks?
6. Should secret entry and reveal flows share one dialog across Settings, Env,
   Host key unlock, and Composer API key setup?
7. Should future light mode stay out of scope permanently, or remain a deferred
   portability target after the dark dev-tool system is stable?
