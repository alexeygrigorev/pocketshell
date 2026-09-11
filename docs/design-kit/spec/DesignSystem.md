# PocketShell Quiet / design system
Version 1.0.0 · Terminal-first Android UI

## Product structure
A **host** is the machine. A **root** is a host-specific folder used to organize work. A **workspace** is a particular remote folder, not a session, chat, repository-only object or profile. A **session** is one running terminal in that workspace. A workspace can contain zero, one or several sessions. Git is not required.

The high-frequency route is `Host workspaces → Workspace sessions → Terminal`. Existing folders do not require a naming/configuration wizard. A folder can be added to the visible workspace list without creating a new session. Creating a folder and starting a session are separate, explicit operations.

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
- **screen**: 28sp, 34sp line height, weight 700.
- **workspace**: 20sp, 28sp line height, weight 600.
- **title**: 20sp, 28sp line height, weight 600.
- **body**: 18sp, 26sp line height, weight 400.
- **metadata**: 16sp, 22sp line height, weight 400.
- **label**: 16sp, 22sp line height, weight 500.
- **button**: 18sp, 24sp line height, weight 600.
- **terminal**: 16sp, 22sp line height, weight 400.

Use proportional system sans for app UI. Use monospace for commands, raw file content and terminal output only. Browser CSS pixels model dp at baseline; native text uses sp. Support Android system font scaling, wrapping, keyboard and safe insets. Do not scale down long workspace names to preserve a one-line layout.

Terminal text size is a separate preference. The browser terminal is fixture text with its own fixed 16px grid; its surrounding app controls use the UI font scale. Do not enlarge or resize a real terminal implicitly when a composer or keyboard opens.

## Geometry
20dp screen gutters; 4/8/12/16/20/24dp spacing; 32dp section separation. Minimum 48dp interactive target, 56dp field/button, 72dp standard row and 88dp workspace row. These are minima, not clipping heights. Labels and supporting text can grow rows.

12dp field/button radius. 24dp top corners on sheets. No elevation on workspace rows. Thin separators, no nested cards. Input/control boundaries have a stronger neutral contrast than decorative dividers.

## Component contracts
### Screen header
Back, title, optional meaningful context, at most one secondary action. The host header shows host name and connection state. Workspaces are the content, not a competing host tab. Do not put Files, Ports, Usage and separate usage chips in this header.

### Root section
Root path on the left; contextual **+ Add** on the right. The entire root label has a 48dp action target opening root actions. Add can find a folder, create one, or explicitly start a session in the root. Full Add workspace remains the accessibility description.

### Workspace row
Name 20sp semibold, followed by muted 16sp session-kind summary. One row is one hit target. Agent marks are non-interactive metadata and have no independent tap or long-press action. This avoids tiny nested targets and ambiguous same-agent jumps. Open the workspace to choose a particular terminal.

Collapse duplicate kinds into a count (Terminal ×2); show up to three kinds then +N more kinds. Announce readable labels, not glyph names. No sessions and Status unavailable are different states. Stable manual order, otherwise creation/first-discovery order; live refresh never reorders rows beneath a tap.

### Agent marks
Reuse the desktop's existing shape vocabulary: hexagon = Claude, code chevrons = Codex, terminal mark = OpenCode, bolt = Grok. Shell has the label Terminal. These are product-local identifiers, **not vendor logos**. Keep text beside the marks, so no onboarding legend is necessary. Mark geometry is an icon asset; do not substitute an emoji or character from a font.

### Standard row
An 18sp main label, optional 16sp supporting text and quiet navigation chevron. Avoid putting long values in a narrow trailing column; move them below the label. The current compact trailing slot is only for short values such as 300ms or Current.

### Fields and actions
Persistent label above the field; no placeholder-only identification. Clear text input colors and native cursor/focus feedback. One primary action pinned above the safe/keyboard inset. Long forms scroll independently from the action area. Backend, profile and explicit session name remain in More options.

### Sheets
Same surface, 24dp corners, close button and independent content scrolling. Replace one sheet with another rather than stacking modal layers. Underlying content must be inert to touch/accessibility. Native ModalBottomSheet supplies focus, Back, swipe dismissal and semantics. Expanded form sheets keep actions visible with the keyboard; do not transplant the browser's fixed 88% height into production.

### Terminal and composer
The real emulator stays. The terminal area owns its character grid. The session chooser uses readable names rather than compressed mobile tabs. Composer opens over the terminal and above the Android IME. The app does not draw its own software keyboard. Always name the input target before Send. Paste writes text without Enter; Send explicitly appends Enter using the existing send path. Dictation stops into an editable draft, not automatic execution.

### Files and services
Reuse the flat rows and named actions. Paths are useful in the file browser location bar, not duplicated on workspace rows. Save to host and Download are different actions. Tunnels and Usage are host-scoped even when opened from a workspace. A tunnel should show remote and local endpoints, and default to loopback-only local exposure.

## State vocabulary
- Connected / Reconnecting / Offline: transport state, with a textual label.
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
