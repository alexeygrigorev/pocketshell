# PocketShell Quiet / design system
Version 1.0.0 · Terminal-first Android UI

## Product structure
A **host** is the machine. A **root** is a host-specific folder used to organize work. A **workspace** is a particular remote folder, not a session, chat, repository-only object or profile. A **session** is one running terminal in that workspace. A workspace can contain zero, one or several sessions. Git is not required.

The high-frequency route is `Host workspaces → Terminal`. A workspace tap opens
that workspace's terminal — the one you were last in there, else the freshest —
and the terminal's own tab strip is that workspace's other sessions. A workspace
with nothing running opens its create sheet instead, so there is no state in
which tapping a workspace shows a page whose only content is a button.

**This spec was changed deliberately, with the maintainer's explicit approval
(2026-09-10, issue #2635 N1).** It previously read `Host workspaces → Workspace
sessions → Terminal`, and a `Workspace sessions` page existed to serve it. The
maintainer's report was tap count — "I don't want to have another screen" — and
the level did not earn its rows: with the terminal's tab strip listing siblings,
every row that page carried already existed somewhere the user was going anyway.
`Destination.Workspace` and its screen are deleted (D22), not hidden behind a
flag. Existing folders do not require a naming/configuration wizard. A folder can be added to the visible workspace list without creating a new session. Creating a folder and starting a session are separate, explicit operations.

A session directly in a root is supported by **Start session here**. Show these sessions in an **In this root** row rather than hiding them or fabricating a child workspace. Paths outside registered roots belong to **Other folders**; unknown paths must stay unknown, never silently assigned to a convenient root.

## Visual anchor
`workspaces.png` is the anchor. Keep the flat slate background, high-contrast workspace name, quiet root heading, muted one-line session summary and hairline dividers. There is no workspace card background. Names wrap rather than shrink. No repeated folder glyphs or full paths below names in a root section.

The last image generated in the conversation is a visual reference, not the implementation source. This kit translates it into Android-sized components and deterministic tokens. Its image viewer deliberately uses that approved image as sample file content; it is not a second app theme.

## Color roles
- **background**: `#10171E`.
- **surface**: `#19222B`.
- **surfaceRaised**: `#222D38`.
- **text**: `#F0F3F7`.
- **secondary**: `#A6B2C1`.
- **muted**: `#92A0B0`.
- **divider**: `#2B3946`.
- **inputBorder**: `#64778A`.
- **accent**: `#53D8EC`.
- **onAccent**: `#082027`.
- **positive**: `#5CDF89`.
- **warning**: `#E6BC78`.
- **error**: `#F3A1A1`.
- **terminal**: `#0B1117`.
- **scrim**: `#00000099`.

The accent identifies an intentional action or focus, not every selectable option. At most one filled cyan action per visible surface. Neutral text/radio outlines communicate a selection. Destructive actions use a red outline/text only after the target and consequences are explicit.

Session marks always inherit muted gray. They do not use brand colors, chip fills, shadows, colored square tiles or individual green pips. A known running-session summary does not claim the agent is busy or waiting. Host **Connected** can have one green dot plus its text label. Do not equate SSH connected, terminal attached, process running and agent working.

## Typography
Sizes, line heights and weights are in
[`../design-system/tokens.json`](../design-system/tokens.json) § `type` — the
single machine-readable source of truth, pinned to the shipped theme by
`QuietThemeTokenTest`. The rungs are `screen`, `workspace`, `title`, `body`,
`metadata`, `label`, `button`, `terminal`, plus `bodyDense` / `bodyMono` /
`labelMono` for dense rows, paths and inline counts.

This section used to restate 28/20/18/16, one full rung above what the app
ships. #2635 reconciled the kit onto the app's restrained scale (issue #2630's
maintainer report was "the interface became worse, it's too large"): a phone at
412dp cannot spend a 28sp heading and a 72dp row on a list of five things.

Use proportional system sans for app UI. Use monospace for commands, raw file content and terminal output only. Browser CSS pixels model dp at baseline; native text uses sp. Support Android system font scaling, wrapping, keyboard and safe insets. Do not scale down long workspace names to preserve a one-line layout.

Terminal text size is a separate preference. The browser terminal is fixture text with its own fixed 16px grid; its surrounding app controls use the UI font scale. Do not enlarge or resize a real terminal implicitly when a composer or keyboard opens.

## Geometry
Every number is in [`../design-system/tokens.json`](../design-system/tokens.json)
§ `space`, `size` and `radius`. Read it there: `screenGutter`, the `xs`…`xxl`
spacing rungs, `sectionGap`, `touchMin`, `fieldMin`/`buttonMin`, `listRowMin`,
`workspaceRowMin`, and the `{badge, chip, field, button, card, sheet}` radius
ladder. These are minima, not clipping heights — labels and supporting text can
grow rows.

`touchMin` is the INTERACTIVE floor, not a row target. This section previously
specified 72dp standard rows, 88dp workspace rows and 32dp section separation;
reading the touch floor as a design target is exactly how the app came to spend
a 915dp phone screen on six items (#2630), and #2635 retired the 32dp `section`
rung with it.

No elevation on workspace rows. Thin separators, no nested cards. Input/control
boundaries have a stronger neutral contrast than decorative dividers.

## Component contracts
### Screen header
Back, title, optional meaningful context, at most one secondary action. The host header shows host name and connection state. Workspaces are the content, not a competing host tab. Do not put Files, Ports, Usage and separate usage chips in this header.

### Root section
Root path on the left; contextual **+ Add** on the right. The entire root label has a 48dp action target opening root actions. Add can find a folder, create one, or explicitly start a session in the root. Full Add workspace remains the accessibility description.

### Workspace row
Name at the `title` rung, one line, ellipsised; a leading status dot when any
session in the workspace is attached; a muted count and relative activity in the
trailing slot. One row is one hit target. Agent marks are non-interactive metadata and have no independent tap or
long-press action on their own. This avoids tiny nested targets and ambiguous
same-agent jumps.

A tap opens the workspace's terminal directly (#2635 N1, maintainer-approved —
this line previously read "Open the workspace to choose a particular terminal").
Choosing a DIFFERENT terminal in that workspace is the terminal's tab strip, and
choosing one in another workspace is that strip's overflow sheet, which lists the
host's other workspaces (#2635 N2). The row's long-press is its alternate action
— new session, browse files, copy path, reorder, remove from list — which is
where the deleted page's utilities went.

Collapse duplicate kinds into a count (Terminal ×2); show up to three kinds then +N more kinds. Announce readable labels, not glyph names. No sessions and Status unavailable are different states. Stable manual order, otherwise creation/first-discovery order; live refresh never reorders rows beneath a tap.

### Agent marks
Reuse the desktop's existing shape vocabulary: hexagon = Claude, code chevrons = Codex, terminal mark = OpenCode, bolt = Grok. Shell has the label Terminal. These are product-local identifiers, **not vendor logos**. Keep text beside the marks, so no onboarding legend is necessary. Mark geometry is an icon asset; do not substitute an emoji or character from a font.

### Standard row
A `body` main label, optional `metadata` supporting text and quiet navigation
chevron, on a list where only SOME rows navigate. When EVERY row on a list
navigates, the chevron says nothing the list does not already say, and it is
dropped (#2635) — the workspace list repeated one glyph a dozen times to
announce the only thing a tap there has ever done. Avoid putting long values in a narrow trailing column; move them below the label. The current compact trailing slot is only for short values such as 300ms or Current.

### Fields and actions
Persistent label above the field; no placeholder-only identification. Clear text input colors and native cursor/focus feedback. One primary action pinned above the safe/keyboard inset. Long forms scroll independently from the action area. Backend, profile and explicit session name remain in More options.

### Sheets
Same surface, 24dp corners, close button and independent content scrolling. Replace one sheet with another rather than stacking modal layers. Underlying content must be inert to touch/accessibility. Native ModalBottomSheet supplies focus, Back, swipe dismissal and semantics. Expanded form sheets keep actions visible with the keyboard; do not transplant the browser's fixed 88% height into production.

### Terminal and composer
The real emulator stays. The terminal area owns its character grid. The session chooser uses readable names rather than compressed mobile tabs. Composer opens over the terminal and above the Android IME. The app does not draw its own software keyboard. Always name the input target before Send. Paste writes text without Enter; Send explicitly appends Enter using the existing send path. Dictation stops into an editable draft, not automatic execution.

### Files and services
Reuse the flat rows and named actions. Paths are useful in the file browser location bar, not duplicated on workspace rows. Save to host and Download are different actions. Tunnels and Usage are host-scoped even when opened from a workspace. A tunnel should show remote and local endpoints, and default to loopback-only local exposure.

## State vocabulary
- Connected / Reconnecting / Offline: transport state. The STEADY state
  (Connected) is a dot with no text; Reconnecting and Offline keep their textual
  label, because a colour cannot say them (#2635 T2). TalkBack always hears the
  word.
- Running: verified remote process/session state; no implication of CPU activity.
- No sessions: a successful enumeration reported none.
- Status unavailable: session enumeration is stale or unavailable.
- Session ended: process/session exit, not a generic network error.
- Delivery could not be confirmed: uncertain terminal write; keep draft and never auto-resend.
- Remove from list: only workspace visibility; never delete folder or kill session.
- Delete file: explicit remote filesystem destruction, named target and host.

## Motion, accessibility and contrast
Use 120ms micro feedback, 200ms ordinary transitions, no decorative pulses. Reduced-motion users get no nonessential animation. Respect native platform back and keyboard behavior.

Gray is not permission to make information unreadable. Keep text at the defined foreground tokens; do not lower opacity per screen. Pair color with text. Icons that duplicate visible labels are decorative and should have null native contentDescription. Interactive icon-only buttons have explicit labels. Avoid hover-only instructions on Android.

The browser QA is geometric and contrast-focused; it is not a substitute for Android TalkBack, switch access, IME, dynamic font scaling or device testing.
