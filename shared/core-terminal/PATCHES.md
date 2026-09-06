# Local patches to the vendored Termux sources

`VENDORED.md` pins the upstream commit. This file is the complete list of files
that deviate from that pin and what each deviation is, so a future vendoring
refresh knows what to re-apply. Any deviation — even a one-character change —
is recorded here.

The list below was produced by diffing the whole vendored tree
(`src/main/java/com/termux/**`, `src/test/java/com/termux/**`,
`src/main/res/**`) against the pinned upstream commit
`30ebb2dee381d292ade0f2868cfde0f9f20b89fe`. **Seven production files and four
test files deviate; one production file (`TerminalSession.java`) is not
vendored at all; three upstream files (`ByteQueue.java`, `JNI.java` and the
whole `src/main/jni/` tree) are deleted; every other vendored file — including
`TerminalSessionClient.java`, the `textselection` package and the resources —
is byte-identical to upstream.** Re-run
that diff (see `VENDORED.md` → "Refresh procedure") whenever this file is
edited: a claim of byte-identity is only useful if it has been checked, and
this file has been wrong about it before.

Deviating files:

| File | Patch families |
|---|---|
| `src/main/java/com/termux/terminal/TerminalEmulator.java` | #259, #1955/#1961 |
| `src/main/java/com/termux/terminal/TerminalBuffer.java` | #469, #966/#967/#1153, #1955/#1961 |
| `src/main/java/com/termux/terminal/TerminalRow.java` | #469, #1955 |
| `src/main/java/com/termux/terminal/TerminalSession.java` | **replaced wholesale (#2566)** — not a patch |
| `src/main/java/com/termux/terminal/TextStyle.java` | #1955/#1961 |
| `src/main/java/com/termux/view/TerminalRenderer.java` | #172, #241, #259, #469 |
| `src/main/java/com/termux/view/TerminalView.java` | #107, #469/#721, #529/#1854, #568/#588, #966/#967, #2154 |
| `src/main/java/com/termux/view/TerminalViewClient.java` | #529/#1854, #966/#967, #2154 |
| `src/test/java/com/termux/terminal/*` (4 files) | see "Vendored test sources" below |

## `src/main/java/com/termux/terminal/TerminalEmulator.java`

- **#259** — carriage-return overwrite tracking. When the agent rewrites its
  status/spinner line in place with a bare `\r` followed by a *shorter* string,
  upstream leaves the tail of the previous (longer) frame stranded on the row,
  so two spinner frames coexist (the "rows run together / `gthinkingwithout`"
  garble). The patch tracks a pending CR-overwrite region
  (`mCarriageReturnOverwrite*` fields + `recordCarriageReturnOverwrite` /
  `markCarriageReturnOverwriteOutput` / `finishCarriageReturnOverwrite` /
  `clearCarriageReturnOverwrite`, called from the `\r` control case,
  `doLinefeed`, `scrollDownOneLine`, `setCursorCol` and the cell-emit path): on
  a bare `\r` it remembers the original line-end column, and when the rewrite
  finishes shorter it clears the stale tail cells. Intentional, tested
  deviation from strict xterm semantics, tuned for agent spinners — see the
  `#259` cases in `TerminalTest.java`, `StatusSpinnerRewriteGridTest`, and
  `CapturePaneSeedReplayGridTest`.
- **#1955/#1961** — OSC 8 hyperlink provenance. `doOsc` gains a `case 8:` that
  tracks whether a hyperlink is open (`mOsc8HyperlinkActive` /
  `mOsc8HyperlinkStartPending`; the URI itself is deliberately **not** stored),
  `getStyle()` ORs the two `TextStyle` provenance bits into every emitted
  cell's style, the cell-emit path clears the start-pending flag after the
  first printable cell, and `reset()` clears both flags. Consumed by
  `com.pocketshell.core.terminal.selection.ViewportTextExtraction`, which uses
  the marker to tell a declared hyperlink continuation apart from
  same-colour text on the next row.
- **#1955** — cursor-addressed hard-wrap tracking (`mHardWrapCandidateRow` /
  `mHardWrapContinuationRow`, `recordCursorAddressedHardWrap`,
  `confirmHardWrapStart`, `clearHardWrapTracking`, and the
  `setCursorRow(int, boolean fromLinefeed)` overload). A fixed-width agent TUI
  commonly disables autowrap and paints the continuation of a long URL by
  addressing the next row with CSI, so upstream's `mLineWrap` bit stays false
  and a wrapped link cannot be rejoined. The patch records the boundary on the
  buffer instead (`TerminalBuffer.setHardWrapStart`, below).
- **Cosmetic** — the two-line CPR comment in the `DSR 6` case is reflowed onto
  one line. No behaviour change; re-flow or drop it freely on a refresh.

## `src/main/java/com/termux/terminal/TerminalBuffer.java`

- **#469** — `getScreenFirstRow()` / `getTotalRows()` expose the circular
  buffer's origin and length so `TerminalRenderer`'s dirty-region cache can
  detect ring-buffer scroll and shift its per-row generation stamps; the
  direct style write in `setOrClearEffect` calls `TerminalRow.bumpGeneration()`
  because it bypasses `TerminalRow.setChar`. `getScreenFirstRow` /
  `getTotalRows` currently have no caller outside the vendored tree.
- **#966/#967/#1153** — `getVisibleScreenText()`,
  `getVisibleScreenTextFullyJoined()`, `getVisibleScreenRows()`. These fed the
  pre-0.5.0 stale-render oracles; nothing in app2 calls them today.
- **#1955** — `getHardWrapStart` / `setHardWrapStart` / `clearHardWrapStart` /
  `clearAllHardWrapStarts` and `lineFillsWidth(row)`, plus the
  `clearAllHardWrapStarts()` call at the top of `resize(...)` (a reflow
  invalidates boundaries recorded under the old geometry). `getHardWrapStart`
  is read by `ViewportTextExtraction`; the setters are driven by
  `TerminalEmulator`'s hard-wrap tracking above.
- **#1961** — `setOrClearEffect` writes
  `TextStyle.encodePreservingProvenance(...)` instead of `TextStyle.encode(...)`
  so a DECCARA/DECRARA rectangular attribute change over an already-painted
  cell does not erase that cell's OSC 8 provenance bits.

## `src/main/java/com/termux/terminal/TerminalRow.java`

- **#469** — the public `mGeneration` content stamp (starting at `1`) and
  `bumpGeneration()`, bumped at every mutating chokepoint (`setChar`, `clear`,
  and the buffer's direct style writes) so `TerminalRenderer` can skip
  unchanged rows.
- **#1955** — the `mHardWrapStart` row flag: cleared by `setChar` and `clear`
  (a direct cell write is a content replacement, so stale provenance must not
  survive), and transferred by `copyInterval` only for a whole-row copy.

## `src/main/java/com/termux/terminal/TextStyle.java`

- **#1955/#1961** — the OSC 8 provenance encoding:
  `CHARACTER_ATTRIBUTE_OSC8_HYPERLINK` (bit 11),
  `CHARACTER_ATTRIBUTE_OSC8_HYPERLINK_START` (bit 12), `OSC8_PROVENANCE_MASK`,
  and `encodePreservingProvenance(fore, back, effect, previousStyle)`. Bits
  11..15 are unused by Termux's effect/colour encoding and are deliberately
  kept out of `decodeEffect(long)`, so the markers are rendering-neutral. No
  hyperlink URI is ever stored in a cell — the bits are pure provenance.

## `src/main/java/com/termux/terminal/TerminalSession.java` — REPLACED, not patched

- **#2566** — this file is **PocketShell's own remote-only session**. It keeps
  upstream's fully qualified name so `TerminalView`,
  `TextSelectionCursorController` and `TerminalEmulator` compile against it
  unpatched, and it keeps `writeCodePoint`'s body verbatim, but nothing else
  came from upstream and **upstream's version must never be re-applied**. A
  refresh restores ours with `git checkout --` (see `VENDORED.md` →
  "PocketShell's own `TerminalSession`" and step 5 of the refresh procedure).
  What went with it: the local-pty spawn, the two byte ring buffers, the three
  I/O threads, the main-thread handler and its `MSG_*` protocol, the pid/exit
  status/cwd surface, `ByteQueue.java`, `JNI.java` and both native source
  trees. The #796/#803 2 KB drain-slice bound survives as
  `TerminalPtyBridge.DRAIN_SLICE_BYTES`, which is now the bridge's own number
  and no longer has to agree with anything in this module.

## `src/main/java/com/termux/view/TerminalRenderer.java`

- **#172** — Option A "pin cell width to regular-advance": bold runs are
  re-measured with the bold paint and squashed into the regular-advance cell
  allocation, and each style run's foreground glyph draw is clipped to its
  cell-aligned physical column footprint so fake-bold / italic / overhang ink
  cannot bleed sideways into neighbouring columns.
- **#241** — row pitch derived from the glyph bounding box
  (`-ascent + descent`) times `LINE_SPACING_MULTIPLIER` instead of
  `Paint#getFontSpacing()`, to fit more rows on a phone viewport.
  `getFontLineSpacingAndAscent()` exposes the derived metric; it currently has
  no caller outside the vendored tree (`TerminalGeometry` re-derives the same
  formula because the fields are package-private).
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
- **#469** — dirty-region rendering: a per-logical-row generation cache
  (keyed off `TerminalRow.mGeneration` plus cursor/selection/palette/reverse
  state), `peekDirtyRows(...)` with the `PEEK_FULL` / `PEEK_NONE` sentinels,
  `computeDirtyRows(...)`, `rowTopPx(i)` / `rowBottomPx(i)`,
  `invalidateDirtyCache()`, the `mClipScratch` clip-bounds scratch, and the
  clip-gated per-row skip inside `render(...)`.
  `getLastRenderedRowCountForTesting()` is a leftover hook with no caller
  outside the vendored tree.

## `src/main/java/com/termux/view/TerminalView.java`

- **#107** — `mDefaultBackgroundColor` + `setDefaultBackgroundColor(int)`
  (which also mirrors into `setBackgroundColor`), used by both `onDraw`
  fallbacks instead of a hard-coded `0xFF000000`, so the unattached canvas
  paints the PocketShell design background rather than flashing pure black.
  app2's `TerminalHostView` drives it from the emulator's palette.
- **#469/#721** — `onScreenUpdated` coalesces repaints through one
  `postOnAnimation` runnable (`scheduleRenderInvalidation`),
  `invalidateDirtyRegion()` invalidates one rect per contiguous dirty run from
  `TerminalRenderer.peekDirtyRows`, and `forceFullRepaint()` resets the
  renderer's dirty cache (also called from `onAttachedToWindow`, since a
  reattached View starts on a cleared surface the cache still believes is
  painted). The five `*ForTesting` render-invalidation hooks
  (`hasPendingRenderInvalidationForTesting`,
  `getPendingRenderInvalidationRequestsForTesting`,
  `getCoalescedRenderInvalidationFramesForTesting`,
  `requestRenderInvalidationForTesting`,
  `drainPendingRenderInvalidationForTesting`) have no caller since the 0.5.0
  rewrite.
- **#529/#1854** — smart-text IME staging behind
  `TerminalViewClient.shouldUseSmartTextInput()` (default false): the
  `SmartTextStagingPolicy` enum, `mSmartTextStagingEditable` and its
  `rememberSmartTextStaging` / `hasSmartTextStaging` / `clearSmartTextStaging`
  / `flushSmartTextStagingToTerminal` / `prepareForRawTerminalInput` helpers,
  the extra `InputConnection` overrides (`setComposingText`,
  `performContextMenuAction`, `performEditorAction`, `sendKeyEvent`), and the
  `isMultiLinePasteText` / `sendBracketedPasteToTerminal` split that frames a
  commit containing a *content* line break with `BracketedPaste` (a single
  trailing newline stays an ordinary submit). This is the one place the
  vendored source imports PocketShell code
  (`com.pocketshell.core.terminal.input.BracketedPaste`).
  `prepareForRawTerminalInput` has no caller since the 0.5.0 rewrite.
- **#568/#588** — the `requestFocus()` call in the single-tap gesture handler
  is **removed**, so tapping the terminal does not steal focus from the
  keyboard accessory row.
- **#966/#967** — `onDraw`/`updateSize` catch `Throwable` (an `Error`
  mid-render used to crash the composition), paint one background frame,
  force a full repaint next frame, and report through
  `reportTerminalViewFailure` → `TerminalViewClient.onTerminalRenderFailure`;
  the IME `InputConnection` overrides catch `RuntimeException` the same way.
  `updateSize` also returns early on non-positive font metrics.
- **#2154** — a resize during a live text selection keeps the viewport row
  instead of snapping to the bottom; `doScroll` and `setTopRow` only fire when
  the top row actually moves and report the move through
  `TerminalViewClient.onScrollChanged()`.

## `src/main/java/com/termux/view/TerminalViewClient.java`

Three added default methods, so upstream clients stay source-compatible:

- **#529/#1854** — `shouldUseSmartTextInput()` (default `false`).
- **#966/#967** — `onTerminalRenderFailure(String, Throwable)` (default no-op);
  `Throwable` rather than `Exception` so an `Error` thrown while rendering
  reaches the same recovery path.
- **#2154** — `onScrollChanged()` (default no-op).

## Vendored test sources

`src/test/java/com/termux/terminal/**` is otherwise upstream; four files add
PocketShell cases (no upstream case is modified or deleted):

- **`TerminalTest.java`** — the #259 CR-overwrite cases
  (`testCarriageReturnOverwriteClearsStaleTailBeforeLinefeed`,
  `testCursorOverwriteClearsStaleTailBeforeLinefeed`,
  `testCarriageReturnLinefeedWithoutOverwriteKeepsLine`) and
  `testQueryResponsesAreAnswered`, which pins that `CSI 14t`/`18t`, `CSI c`,
  `CSI >c`, `CSI 5n`, `CSI 6n` and `OSC 11 ?` are answered on the wire now that
  the #246 query-suppression flag is gone (#2557).
- **`OperatingSystemControlTest.java`** — five OSC 8 provenance and
  cursor-addressed hard-wrap cases (#1955).
- **`RectangularAreasTest.java`** — four DECCARA/DECRARA cases pinning that a
  rectangular attribute change preserves OSC 8 provenance (#1961).
- **`TextStyleTest.java`** — four cases pinning the provenance bit layout and
  `encodePreservingProvenance` (#1955/#1961).

## Removed patches

Removed on 2026-09-06 (the 0.5.0 rewrite left them without a caller), restoring
those hunks to upstream: the emulator's `setSuppressQueryResponses` (#246 —
with one PTY source every query must be answered;
`TerminalTest.testQueryResponsesAreAnswered` pins that), the session's
`availableProcessOutputBytes` / `onProcessOutputDrained` drain seams and
`ByteQueue.getAvailable()` (#803), `TerminalSessionClient.onProcessOutputDrained`
(which restores that file byte-identical to upstream), the view's
`FramePaintObserver`, `forceSurfaceRepaint` and PixelCopy surface-black probe
(#1192/#1203/#1296/#1443/#2003) with the `android.graphics.Color` /
`android.graphics.Rect` / `android.view.Window` /
`androidx.annotation.VisibleForTesting` imports they needed, and the buffer's
`hasNonBlankVisibleRow`.

Removed on 2026-09-06 by #2566, this time by deleting the files rather than
restoring them to upstream: `ByteQueue.java`, `JNI.java`, the upstream
`src/main/jni/` C sources and PocketShell's `src/main/cpp/` stub
`libtermux.so`. Nothing in the module calls them now that `TerminalSession` is
ours, so there is no `externalNativeBuild` left and the module needs no NDK.
