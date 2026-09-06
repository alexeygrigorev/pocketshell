# Local patches to the vendored Termux sources

`VENDORED.md` pins the upstream commit and lists which files are *byte-identical*
to upstream. Any deviation — even a one-character change — is recorded here so a
future vendoring refresh knows what to re-apply.

## `src/main/java/com/termux/view/TerminalRenderer.java`

Not byte-identical to upstream. PocketShell terminal-rendering polish lives here:

- **#172** — Option A "pin cell width to regular-advance": bold runs are
  re-measured with the bold paint and squashed into the regular-advance cell
  allocation, and each style run's foreground glyph draw is clipped to its
  cell-aligned physical column footprint so fake-bold / italic / overhang ink
  cannot bleed sideways into neighbouring columns.
- **#241** — row pitch derived from the glyph bounding box
  (`-ascent + descent`) times `LINE_SPACING_MULTIPLIER` instead of
  `Paint#getFontSpacing()`, to fit more rows on a phone viewport.
- **#259** — disable font ligatures / contextual alternates on the text paint
  (`setFontFeatureSettings("'liga' 0, 'clig' 0, 'dlig' 0, 'calt' 0")`). A
  terminal is a fixed monospace cell grid; programming fonts ship
  `liga`/`clig`/`dlig`/`calt` features, so handing a whole style run to a single
  `Canvas#drawTextRun` lets the font shape adjacent glyphs into ligatures —
  visually merging two cells' glyphs into one. For a terminal that is always
  wrong and is the render-side class of the #259 "rows run together / text
  mixed" symptom. Disabling ligature/contextual shaping pins one glyph per
  cell. On the bundled face the per-glyph advances are already uniform so cell
  layout is unchanged (verified by `allRegularCellsRemainAligned` + the #172
  tests); this removes the visual cell-merging a face's shaper could apply.

## `src/main/java/com/termux/terminal/TerminalEmulator.java`

Not byte-identical to upstream.

- **#259** — carriage-return overwrite tracking. When the agent rewrites its
  status/spinner line in place with a bare `\r` followed by a *shorter* string,
  upstream leaves the tail of the previous (longer) frame stranded on the row,
  so two spinner frames coexist (the "rows run together / `gthinkingwithout`"
  garble). The patch tracks a pending CR-overwrite region
  (`mCarriageReturnOverwrite*` fields + `recordCarriageReturnOverwrite` /
  `markCarriageReturnOverwriteOutput` / `finishCarriageReturnOverwrite`): on a
  bare `\r` it remembers the original line-end column, and when the rewrite
  finishes shorter it clears the stale tail cells. Intentional, tested
  deviation from strict xterm semantics, tuned for agent spinners — see the
  `#259` cases in `TerminalTest.java`, `StatusSpinnerRewriteGridTest`, and
  `CapturePaneSeedReplayGridTest`.

## `src/main/java/com/termux/terminal/TerminalSession.java`

Not byte-identical to upstream.

- **#796/#803** — `MainThreadHandler` reads one 2 KB slice per `MSG_NEW_INPUT`
  (upstream: 64 KB) and never re-posts itself; the poster (app2's
  `TerminalPtyBridge`) writes the remote stream in slices of that size and
  posts one message per slice, so no single main-thread turn parses more than
  a slice of clear-heavy alt-screen content. `MSG_PROCESS_EXITED` drains the
  whole queue first. The handler is pinned to `Looper.getMainLooper()`.

## `src/main/java/com/termux/terminal/TerminalBuffer.java`, `TerminalRow.java`

Not byte-identical to upstream.

- **#469** — per-row `mGeneration` content stamps, bumped at every mutating
  chokepoint, so `TerminalRenderer` can skip unchanged rows.
- **#966/#967/#1153** — `getVisibleScreenText`, `getVisibleScreenTextFullyJoined`,
  `getVisibleScreenRows` on the buffer, and `mHardWrapStart` on the row. These
  fed the pre-0.5.0 stale-render oracles and smart-selection overlays; nothing
  in app2 calls them today.

## `src/main/java/com/termux/view/TerminalView.java`, `TerminalViewClient.java`

Not byte-identical to upstream.

- **#469/#721** — `onScreenUpdated` coalesces repaints through one
  `postOnAnimation` runnable (`scheduleRenderInvalidation`) and
  `forceFullRepaint()` resets the renderer's dirty cache.
- **#966/#967** — `onDraw`/`updateSize` catch `Throwable` (an `Error` mid-render
  used to crash the composition), paint one background frame, force a full
  repaint next frame, and report through `TerminalViewClient.onTerminalRenderFailure`.
- **#529/#1854** — smart-text IME staging behind
  `TerminalViewClient.shouldUseSmartTextInput()` (default false), and a
  multi-line IME commit is framed with `BracketedPaste` before it is written.
- **#2154** — a resize during a live text selection keeps the viewport row
  instead of snapping to the bottom; `TerminalViewClient.onScrollChanged()`
  reports viewport moves.

Removed on 2026-09-06 (the rewrite left them without a caller): the emulator's
`setSuppressQueryResponses` (#246 — with one PTY source every query must be
answered; `TerminalTest.testQueryResponsesAreAnswered` pins that), the
session's `availableProcessOutputBytes` / `onProcessOutputDrained` drain seams
(#803), the view's `FramePaintObserver`, `forceSurfaceRepaint` and PixelCopy
surface-black probe (#1192/#1203/#1296/#1443/#2003), and the buffer's
`hasNonBlankVisibleRow`.

`TerminalSessionClient.java` and everything not listed above is byte-identical
to upstream at the pinned commit.
