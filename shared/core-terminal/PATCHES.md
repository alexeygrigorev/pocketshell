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

The grid/emulator itself (`com.termux.terminal.**`, `com.termux.view.TerminalView`)
handles in-place CR / cursor / erase rewrites correctly and is unchanged by
#259 — see `StatusSpinnerRewriteGridTest` and `TerminalRendererSpinnerRewriteInstrumentedTest`.
