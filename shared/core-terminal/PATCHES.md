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

`com.termux.view.TerminalView` and the rest of `com.termux.terminal.**` remain
byte-identical to upstream — see `TerminalRendererSpinnerRewriteInstrumentedTest`
for the render-side coverage.
