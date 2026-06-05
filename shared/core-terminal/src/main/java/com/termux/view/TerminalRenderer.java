package com.termux.view;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Typeface;

import com.termux.terminal.TerminalBuffer;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalRow;
import com.termux.terminal.TextStyle;
import com.termux.terminal.WcWidth;

/**
 * Renderer of a {@link TerminalEmulator} into a {@link Canvas}.
 * <p/>
 * Saves font metrics, so needs to be recreated each time the typeface or font size changes.
 * <p/>
 * <b>Cell-width pinning (PocketShell #172, Option A):</b> the renderer pins every
 * column to {@link #mFontWidth} — the advance width of a regular-weight {@code 'X'}
 * — and draws every glyph (regular or bold) into that fixed cell. Bold glyphs that
 * would otherwise be wider are horizontally compressed via {@link Canvas#scale} so
 * cell boundaries land on consistent x-pixels regardless of weight. This avoids the
 * gap/overlap regression that appears when agent CLIs (e.g. Claude Code's animated
 * spinner) toggle bold per-letter on a font whose bold metrics differ from
 * regular. The trade-off is documented in {@link #drawTextRun}: bold loses a
 * sliver of horizontal weight; neighbors do not displace.
 */
public final class TerminalRenderer {

    /**
     * Multiplier applied to the glyph-bounding-box height (descent - ascent)
     * when computing {@link #mFontLineSpacing} — the per-cell row pitch.
     *
     * <p>Upstream Termux derived row pitch from {@link Paint#getFontSpacing()},
     * which Android computes as {@code descent - ascent + leading} where
     * "leading" is the font's recommended extra space between lines. For the
     * default monospace and the bundled JetBrainsMono that extra leading is
     * generous enough that the terminal felt vertically stretched on Pixel-
     * class viewports (issue #241): fewer rows fit, and individual glyphs
     * read as if they had been scaled vertically.
     *
     * <p>Switching the base metric to {@code -ascent + descent} (i.e.
     * dropping the built-in leading) produces a tighter cell that hugs the
     * glyph bounding box. Tuning multiplier {@code 1.0f} = pure glyph
     * bounding box (no extra row gap). Bumping above {@code 1.0f} re-adds
     * proportional row gap for readability if dogfood asks for it; below
     * {@code 1.0f} compresses the row pitch tighter than the glyph
     * bounding box, which is safe for fonts whose descent and ascent are
     * conservative (JetBrainsMono Regular has small descent, so the next
     * row's ascender region absorbs a modest compression cleanly).
     *
     * <p>Empirical pick of {@code 0.85f}: at the project's chosen
     * {@code textSize = 28px} JetBrainsMono Regular's raw glyph bounding
     * box ({@code -ascent + descent}) renders at ~36 px, whereas the
     * pre-#241 baseline (Roboto Mono + {@code Paint#getFontSpacing()})
     * rendered ~32 px per row. At multiplier {@code 1.0f} the net effect
     * is {\em fewer} visible rows than baseline (deterministic terminal
     * workbench: 65 → 58 rows on a Pixel-class AVD viewport), contrary
     * to issue #241's explicit "fit more lines" dogfood requirement.
     * Compressing by 15% brings the row pitch back to ~31 px (≈67 rows
     * on the same viewport) so density beats baseline while leaving the
     * descender → next-row-ascender region uncluttered.
     *
     * <p>The locked design decision D22 (no backwards-compat shims, no
     * user-facing preference flags) means this is a single tunable constant
     * the maintainer adjusts in source — there is no settings slider for
     * line spacing. See issue #241 for the dogfood rationale.
     */
    private static final float LINE_SPACING_MULTIPLIER = 0.85f;

    final int mTextSize;
    final Typeface mTypeface;
    private final Paint mTextPaint = new Paint();

    /** The width of a single mono spaced character obtained by {@link Paint#measureText(String)} on a single 'X'. */
    final float mFontWidth;
    /**
     * Per-cell row pitch in pixels — distance between the baseline of one
     * row and the baseline of the next. Computed as
     * {@code ceil((-ascent + descent) * LINE_SPACING_MULTIPLIER)} so the cell
     * tracks the glyph bounding box rather than {@link Paint#getFontSpacing()}'s
     * looser "recommended line spacing" (which includes the font's built-in
     * leading and felt vertically stretched on PocketShell's phone viewport —
     * issue #241). See {@link #LINE_SPACING_MULTIPLIER} for the multiplier
     * rationale.
     */
    final int mFontLineSpacing;
    /** The {@link Paint#ascent()}. See http://www.fampennings.nl/maarten/android/08numgrid/font.png */
    private final int mFontAscent;
    /** The {@link #mFontLineSpacing} + {@link #mFontAscent}. */
    final int mFontLineSpacingAndAscent;

    private final float[] asciiMeasures = new float[127];

    /** Reused scratch for {@link Canvas#getClipBounds(android.graphics.Rect)} (#469). */
    private final android.graphics.Rect mClipScratch = new android.graphics.Rect();

    // --- Dirty-region rendering state (PocketShell #469) ------------------------
    //
    // The renderer caches, per logical visible row, the content-generation stamp it
    // last painted (see TerminalRow#mGeneration). On the next frame it skips the
    // per-row style-run segmentation + glyph draw for rows whose stamp is unchanged
    // AND which are not affected by a cursor/selection/global-effect change. The
    // skipped row keeps the pixels the previous onDraw left on the View's surface,
    // which is exactly how partial repaint behaves on a hardware-accelerated View.
    //
    // Correctness is gated by TerminalRendererDirtyOracleTest: every supported
    // scenario must produce a byte-identical viewport whether rendered through the
    // dirty path or a forced full repaint. Any context that affects unchanged cells
    // (reverse-video, alt-screen swap, resize, colors-changed, cursor move,
    // selection change, top-row scrollback move) forces a full repaint here.
    //
    // The cache is keyed by LOGICAL visible row position, not by physical ring slot,
    // because render() always paints logical row i into the same fixed y-band
    // (y depends only on i = row - topRow). So the only question per band is: "did
    // the content shown at logical row i change since I last painted that band?".
    // That is captured by (generation, internalRow) of whatever TerminalRow now
    // occupies logical row i: when the screen scrolls, a different physical row (and
    // thus a different internalRow / generation) moves into band i and is correctly
    // marked dirty. No scroll-delta pixel shift is needed or correct here, because
    // we do not move pixels between bands -- we repaint the bands whose content
    // changed. The internalRow guard defends against two distinct ring rows that
    // happen to share a generation counter value.

    /** Last-painted content stamp per logical visible row; -1 = unknown/force-dirty. */
    private long[] mLastRenderedGeneration = new long[0];
    /** Physical ring-slot that produced each {@link #mLastRenderedGeneration} entry. */
    private int[] mLastRenderedInternalRow = new int[0];
    /** Whether {@link #mLastRenderedGeneration} holds a usable previous frame. */
    private boolean mHasRenderedFrame = false;
    /** Fingerprint of the global render context from the previous frame. */
    private int mLastRows = -1;
    private int mLastColumns = -1;
    private int mLastTopRow = Integer.MIN_VALUE;
    private int mLastTotalRows = -1;
    private boolean mLastReverseVideo = false;
    private boolean mLastAlternateBuffer = false;
    private int mLastCursorRow = -1;
    private int mLastCursorCol = -1;
    private boolean mLastCursorVisible = false;
    private int mLastCursorShape = -1;
    private int mLastSelY1 = -2, mLastSelY2 = -2, mLastSelX1 = -2, mLastSelX2 = -2;
    private int mLastPaletteHash = 0;
    /** Set to false (via {@link #invalidateDirtyCache()}) to force the next frame full. */
    private boolean mDirtyCacheValid = false;
    /** Count of rows actually painted by the most recent {@link #render} (test hook). */
    private int mLastRenderedRowCount = 0;

    /**
     * Number of grid rows actually painted by the most recent {@link #render} call —
     * i.e. rows whose pixel band intersected the canvas clip. A deterministic, timing-
     * free proxy for the dirty-region win (#469): a clipped append-flood frame paints
     * only the few rows the platform invalidated, an unclipped full repaint paints all.
     */
    public int getLastRenderedRowCountForTesting() {
        return mLastRenderedRowCount;
    }

    /**
     * Force the next {@link #render} call to repaint every row regardless of the
     * generation cache. Safe to call at any time; used as the conservative fallback
     * for any state the fingerprint does not explicitly model.
     */
    public void invalidateDirtyCache() {
        mDirtyCacheValid = false;
        mHasRenderedFrame = false;
    }

    public TerminalRenderer(int textSize, Typeface typeface) {
        mTextSize = textSize;
        mTypeface = typeface;

        mTextPaint.setTypeface(typeface);
        mTextPaint.setAntiAlias(true);
        mTextPaint.setSubpixelText(true);
        mTextPaint.setLinearText(true);
        mTextPaint.setTextSize(textSize);

        // PocketShell #259 — disable font ligatures / contextual alternates.
        //
        // A terminal is a fixed monospace cell grid: each column holds exactly
        // one code point and the emulator positions cells independently. Many
        // programming fonts (JetBrainsMono among them) ship `liga`, `clig`,
        // `dlig`, and `calt` features, so when a whole style run is handed to a
        // single `Canvas#drawTextRun`, the font can shape adjacent glyphs into
        // ligatures (e.g. `->`, `!=`, repeated letters) — drawing two cells'
        // glyphs as one merged glyph even when their advances stay uniform.
        // For a terminal that is always wrong: it visually collapses
        // independent cells, the same class of "rows run together / text mixed"
        // symptom #259 reports (e.g. words appearing mashed as
        // `gthinkingwithout`).
        //
        // Turning ligature/contextual shaping off pins one glyph per cell so
        // every `drawTextRun` draws exactly the code point at each column. On
        // the bundled face the per-glyph advances are already uniform, so this
        // does not change cell layout (verified — `allRegularCellsRemainAligned`
        // and the #172 tests still pass); it removes the visual cell-merging a
        // face's shaper could apply. The grid/emulator itself handles in-place
        // CR / cursor / erase rewrites correctly regardless of the font (proven
        // by StatusSpinnerRewriteGridTest); this is purely a draw-path harden.
        mTextPaint.setFontFeatureSettings("'liga' 0, 'clig' 0, 'dlig' 0, 'calt' 0");

        // Issue #241 — derive the row pitch from the glyph bounding box
        // (descent - ascent) rather than Paint#getFontSpacing(), which adds
        // the font's recommended leading on top and produced a visibly
        // stretched terminal on Pixel-class viewports.
        final float mFontDescent = mTextPaint.descent();
        mFontAscent = (int) Math.ceil(mTextPaint.ascent());
        mFontLineSpacing = (int) Math.ceil((-mTextPaint.ascent() + mFontDescent) * LINE_SPACING_MULTIPLIER);
        mFontLineSpacingAndAscent = mFontLineSpacing + mFontAscent;
        mFontWidth = mTextPaint.measureText("X");

        StringBuilder sb = new StringBuilder(" ");
        for (int i = 0; i < asciiMeasures.length; i++) {
            sb.setCharAt(0, (char) i);
            asciiMeasures[i] = mTextPaint.measureText(sb, 0, 1);
        }
    }

    /**
     * Result code for {@link #peekDirtyRows}: every visible row must repaint. */
    public static final int PEEK_FULL = -1;
    /** Result code for {@link #peekDirtyRows}: nothing changed; no repaint needed. */
    public static final int PEEK_NONE = 0;

    /**
     * Peek which logical visible rows {@link #render} will repaint for the current
     * emulator state, WITHOUT advancing the dirty cache, filling {@code outDirty}
     * (indexed by {@code row - topRow}). The hosting {@link TerminalView} uses this to
     * invalidate exactly the changed rows so the platform clips {@code onDraw} to them
     * and preserves the clean ones — which are exactly the rows {@code render()} skips.
     * Runs on the same (main) thread as {@code render()} with no intervening emulator
     * mutation, so the result matches what {@code render()} computes (#469).
     *
     * @param outDirty caller-owned array of length {@code >= emulator.mRows}; entries
     *                 {@code [0, mRows)} are set to each row's dirty flag.
     * @return {@link #PEEK_FULL} if a full repaint is required (e.g. global-effect
     *         change or first frame), {@link #PEEK_NONE} if nothing is dirty, otherwise
     *         the count of dirty rows.
     */
    public final int peekDirtyRows(TerminalEmulator emulator, int topRow,
                                   int selectionY1, int selectionY2, int selectionX1, int selectionX2,
                                   boolean[] outDirty) {
        final int rows = emulator.mRows;
        final boolean reverseVideo = emulator.isReverseVideo();
        final int columns = emulator.mColumns;
        final int cursorCol = emulator.getCursorCol();
        final int cursorRow = emulator.getCursorRow();
        final boolean cursorVisible = emulator.shouldCursorBeVisible();
        final TerminalBuffer screen = emulator.getScreen();
        final int cursorShape = emulator.getCursorStyle();
        final boolean alternateBuffer = emulator.isAlternateBufferActive();
        final int paletteHash = java.util.Arrays.hashCode(emulator.mColors.mCurrentColors);

        final boolean cacheUsable = mDirtyCacheValid && mHasRenderedFrame
            && mLastRenderedGeneration.length == rows;
        final boolean forceFull = !cacheUsable
            || rows != mLastRows || columns != mLastColumns || topRow != mLastTopRow
            || reverseVideo || reverseVideo != mLastReverseVideo
            || alternateBuffer != mLastAlternateBuffer || cursorShape != mLastCursorShape
            || paletteHash != mLastPaletteHash
            || selectionY1 != mLastSelY1 || selectionY2 != mLastSelY2
            || selectionX1 != mLastSelX1 || selectionX2 != mLastSelX2;

        if (forceFull) {
            for (int i = 0; i < rows; i++) outDirty[i] = true;
            return PEEK_FULL;
        }

        int dirtyCount = 0;
        for (int i = 0; i < rows; i++) {
            final int row = topRow + i;
            final int internalRow = screen.externalToInternalRow(row);
            final TerminalRow lineObject = screen.allocateFullLineIfNecessary(internalRow);
            boolean rowIsDirty = lineObject.mGeneration != mLastRenderedGeneration[i]
                || internalRow != mLastRenderedInternalRow[i];
            if (!rowIsDirty) {
                if (cursorVisible && row == cursorRow) {
                    rowIsDirty = true;
                } else if (mLastCursorVisible && row == mLastCursorRow
                        && (cursorRow != mLastCursorRow || cursorCol != mLastCursorCol || !cursorVisible)) {
                    rowIsDirty = true;
                }
            }
            outDirty[i] = rowIsDirty;
            if (rowIsDirty) dirtyCount++;
        }
        return dirtyCount;
    }

    /**
     * Pixel top of logical visible row {@code i}'s invalidation band (#469).
     *
     * <p>The band is the EXACT pixel rows {@link #render} paints for this row, with no
     * slack into neighbouring (possibly clean) rows: any slack would let the platform
     * clip the following {@code onDraw} over a clean band that {@code render()} then
     * skips, blanking it. The first band starts at 0 so the top row's ascender region
     * is covered.
     */
    public int rowTopPx(int i) {
        return (i == 0) ? 0 : (i * mFontLineSpacing + mFontAscent);
    }

    /**
     * Pixel bottom of logical visible row {@code i}'s invalidation band (#469). Equals
     * the baseline of row {@code i} ({@code render}'s {@code heightOffset}), which is
     * where the next row's band starts. See {@link #rowTopPx} for why no slack is added.
     */
    public int rowBottomPx(int i) {
        return mFontLineSpacingAndAscent + (i + 1) * mFontLineSpacing;
    }

    /** Render the terminal to a canvas with at a specified row scroll, and an optional rectangular selection. */
    public final void render(TerminalEmulator mEmulator, Canvas canvas, int topRow,
                             int selectionY1, int selectionY2, int selectionX1, int selectionX2) {
        final boolean reverseVideo = mEmulator.isReverseVideo();
        final int endRow = topRow + mEmulator.mRows;
        final int columns = mEmulator.mColumns;
        final int cursorCol = mEmulator.getCursorCol();
        final int cursorRow = mEmulator.getCursorRow();
        final boolean cursorVisible = mEmulator.shouldCursorBeVisible();
        final TerminalBuffer screen = mEmulator.getScreen();
        final int[] palette = mEmulator.mColors.mCurrentColors;
        final int cursorShape = mEmulator.getCursorStyle();

        final int rows = mEmulator.mRows;
        final boolean alternateBuffer = mEmulator.isAlternateBufferActive();
        final int paletteHash = java.util.Arrays.hashCode(palette);

        // Advance the per-row generation cache (drives TerminalView's next-frame
        // dirty-rect invalidate via peekDirtyRows). render() itself does NOT use the
        // dirty flags to decide what to paint — see the clip-gated skip below (#469).
        computeDirtyRows(
            screen, topRow, rows, columns,
            reverseVideo, alternateBuffer, cursorRow, cursorCol, cursorVisible,
            cursorShape, selectionY1, selectionY2, selectionX1, selectionX2, paletteHash);

        // The dirty-region win comes from the platform CLIP: TerminalView issues
        // invalidate(rect) for only the changed rows, so onDraw's canvas is clipped to
        // those rows and the rest of the View surface is preserved by the platform.
        // render() skips a row only when its pixel band is entirely OUTSIDE the current
        // clip. This is the ONLY safe skip: when the platform asks for a FULL redraw
        // (first draw, config change, screenshot/draw-to-bitmap, occlusion), the clip
        // is the whole view and every row repaints, producing a complete frame with no
        // reliance on retained pixels. Reverse-video repaints the whole grid anyway.
        // Skipping requires a positive row pitch to compute per-row pixel bands. If the
        // platform/paint reports a degenerate (<= 0) line spacing — e.g. Robolectric's
        // shadow Paint returns zero font metrics — disable skipping and paint every row,
        // which is always correct (just not optimised).
        final boolean haveClip = mFontLineSpacing > 0
            && canvas.getClipBounds(mClipScratch) && !mClipScratch.isEmpty();
        final int clipTop = haveClip ? mClipScratch.top : Integer.MIN_VALUE;
        final int clipBottom = haveClip ? mClipScratch.bottom : Integer.MAX_VALUE;

        // A reverse-video frame repaints the whole background.
        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        int paintedRowCount = 0;
        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

            if (haveClip) {
                // Row i's pixel band is [bandTop, bandTop + mFontLineSpacing). Skip it
                // only if it does not intersect the clip; clipped-out rows keep the
                // pixels the platform preserved for the unclipped region.
                final float bandTop = heightOffset - mFontLineSpacingAndAscent;
                final float bandBottom = bandTop + mFontLineSpacing;
                if (bandBottom <= clipTop || bandTop >= clipBottom) {
                    continue;
                }
            }
            paintedRowCount++;

            final int cursorX = (row == cursorRow && cursorVisible) ? cursorCol : -1;
            int selx1 = -1, selx2 = -1;
            if (row >= selectionY1 && row <= selectionY2) {
                if (row == selectionY1) selx1 = selectionX1;
                selx2 = (row == selectionY2) ? selectionX2 : mEmulator.mColumns;
            }

            TerminalRow lineObject = screen.allocateFullLineIfNecessary(screen.externalToInternalRow(row));
            final char[] line = lineObject.mText;
            final int charsUsedInLine = lineObject.getSpaceUsed();

            long lastRunStyle = 0;
            boolean lastRunInsideCursor = false;
            boolean lastRunInsideSelection = false;
            int lastRunStartColumn = -1;
            int lastRunStartIndex = 0;
            boolean lastRunFontWidthMismatch = false;
            int currentCharIndex = 0;
            float measuredWidthForRun = 0.f;

            for (int column = 0; column < columns; ) {
                final char charAtIndex = line[currentCharIndex];
                final boolean charIsHighsurrogate = Character.isHighSurrogate(charAtIndex);
                final int charsForCodePoint = charIsHighsurrogate ? 2 : 1;
                final int codePoint = charIsHighsurrogate ? Character.toCodePoint(charAtIndex, line[currentCharIndex + 1]) : charAtIndex;
                final int codePointWcWidth = WcWidth.width(codePoint);
                final boolean insideCursor = (cursorX == column || (codePointWcWidth == 2 && cursorX == column + 1));
                final boolean insideSelection = column >= selx1 && column <= selx2;
                final long style = lineObject.getStyle(column);

                // Check if the measured text width for this code point is not the same as that expected by wcwidth().
                // This could happen for some fonts which are not truly monospace, or for more exotic characters such as
                // smileys which android font renders as wide.
                // If this is detected, we draw this code point scaled to match what wcwidth() expects.
                final float measuredCodePointWidth = (codePoint < asciiMeasures.length) ? asciiMeasures[codePoint] : mTextPaint.measureText(line,
                    currentCharIndex, charsForCodePoint);
                final boolean fontWidthMismatch = Math.abs(measuredCodePointWidth / mFontWidth - codePointWcWidth) > 0.01;

                if (style != lastRunStyle || insideCursor != lastRunInsideCursor || insideSelection != lastRunInsideSelection || fontWidthMismatch || lastRunFontWidthMismatch) {
                    if (column == 0) {
                        // Skip first column as there is nothing to draw, just record the current style.
                    } else {
                        final int columnWidthSinceLastRun = column - lastRunStartColumn;
                        final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
                        int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
                        boolean invertCursorTextColor = false;
                        if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                            invertCursorTextColor = true;
                        }
                        drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun,
                            lastRunStartIndex, charsSinceLastRun, measuredWidthForRun,
                            cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
                    }
                    measuredWidthForRun = 0.f;
                    lastRunStyle = style;
                    lastRunInsideCursor = insideCursor;
                    lastRunInsideSelection = insideSelection;
                    lastRunStartColumn = column;
                    lastRunStartIndex = currentCharIndex;
                    lastRunFontWidthMismatch = fontWidthMismatch;
                }
                measuredWidthForRun += measuredCodePointWidth;
                column += codePointWcWidth;
                currentCharIndex += charsForCodePoint;
                while (currentCharIndex < charsUsedInLine && WcWidth.width(line, currentCharIndex) <= 0) {
                    // Eat combining chars so that they are treated as part of the last non-combining code point,
                    // instead of e.g. being considered inside the cursor in the next run.
                    currentCharIndex += Character.isHighSurrogate(line[currentCharIndex]) ? 2 : 1;
                }
            }

            final int columnWidthSinceLastRun = columns - lastRunStartColumn;
            final int charsSinceLastRun = currentCharIndex - lastRunStartIndex;
            int cursorColor = lastRunInsideCursor ? mEmulator.mColors.mCurrentColors[TextStyle.COLOR_INDEX_CURSOR] : 0;
            boolean invertCursorTextColor = false;
            if (lastRunInsideCursor && cursorShape == TerminalEmulator.TERMINAL_CURSOR_STYLE_BLOCK) {
                invertCursorTextColor = true;
            }
            drawTextRun(canvas, line, palette, heightOffset, lastRunStartColumn, columnWidthSinceLastRun, lastRunStartIndex, charsSinceLastRun,
                measuredWidthForRun, cursorColor, cursorShape, lastRunStyle, reverseVideo || invertCursorTextColor || lastRunInsideSelection);
        }
        mLastRenderedRowCount = paintedRowCount;
    }

    /**
     * Decide which logical visible rows must be repainted this frame, and update the
     * generation cache to reflect what is about to be painted. Returns a {@code rows}
     * length boolean array indexed by {@code row - topRow}.
     *
     * <p>The cache is keyed by logical visible row position. render() paints logical
     * row {@code i} into a fixed y-band, so a row is "clean" exactly when the content
     * now occupying band {@code i} is byte-identical to what was painted there last
     * frame. That is detected by comparing the {@code (generation, internalRow)} pair
     * of the {@link TerminalRow} currently at logical row {@code i} against the cached
     * pair: a scroll moves a different physical row (different internalRow and/or
     * generation) into band {@code i}, which is correctly marked dirty. No pixel shift
     * is performed because pixels are not moved between bands.
     *
     * <p>Returns all-true (full repaint) whenever any global-context fingerprint
     * field changed since the previous frame, or the cache is invalid. The cursor row
     * (current and previous) is always repainted because the cursor is an overlay
     * whose presence is invisible to the row's content generation.
     */
    private boolean[] computeDirtyRows(
            TerminalBuffer screen, int topRow, int rows, int columns,
            boolean reverseVideo, boolean alternateBuffer, int cursorRow, int cursorCol, boolean cursorVisible,
            int cursorShape, int selY1, int selY2, int selX1, int selX2, int paletteHash) {

        final boolean[] dirty = new boolean[rows];

        if (mLastRenderedGeneration.length != rows) {
            mLastRenderedGeneration = new long[rows];
            mLastRenderedInternalRow = new int[rows];
            mDirtyCacheValid = false;
            mHasRenderedFrame = false;
        }

        // Any structural / global-effect change forces a full repaint: a changed
        // cell stamp is not enough to capture these because they affect cells whose
        // own generation did not change (reverse video, alt-screen swap, resize,
        // colors-changed, scrollback top-row move, selection-rect move, cursor-shape
        // / palette change).
        final boolean forceFull =
            !mDirtyCacheValid
            || !mHasRenderedFrame
            || rows != mLastRows
            || columns != mLastColumns
            || topRow != mLastTopRow
            // While reverse-video is active the whole canvas is wiped to the
            // foreground colour at the top of every render(), so every band must be
            // repainted each frame, not only on the on/off transition.
            || reverseVideo
            || reverseVideo != mLastReverseVideo
            || alternateBuffer != mLastAlternateBuffer
            || cursorShape != mLastCursorShape
            || paletteHash != mLastPaletteHash
            || selY1 != mLastSelY1 || selY2 != mLastSelY2 || selX1 != mLastSelX1 || selX2 != mLastSelX2;

        // The cursor cell is drawn as an overlay on top of its row's glyph, so the
        // row carrying the cursor (and the row it just left) must always repaint even
        // though the underlying text generation did not change.
        final int prevCursorRow = mLastCursorRow;
        final int prevCursorCol = mLastCursorCol;
        final boolean prevCursorVisible = mLastCursorVisible;

        for (int i = 0; i < rows; i++) {
            final int row = topRow + i;
            final int internalRow = screen.externalToInternalRow(row);
            final TerminalRow lineObject = screen.allocateFullLineIfNecessary(internalRow);
            final long generation = lineObject.mGeneration;

            boolean rowIsDirty = forceFull
                || generation != mLastRenderedGeneration[i]
                || internalRow != mLastRenderedInternalRow[i];

            if (!rowIsDirty) {
                // Force-repaint the row gaining the cursor and the row that lost it.
                if (cursorVisible && row == cursorRow) {
                    rowIsDirty = true;
                } else if (prevCursorVisible && row == prevCursorRow
                        && (cursorRow != prevCursorRow || cursorCol != prevCursorCol || !cursorVisible)) {
                    rowIsDirty = true;
                }
            }

            dirty[i] = rowIsDirty;
            // Record what we are about to paint into this band for the next frame.
            mLastRenderedGeneration[i] = generation;
            mLastRenderedInternalRow[i] = internalRow;
        }

        // Persist the fingerprint for next frame's comparison.
        mLastRows = rows;
        mLastColumns = columns;
        mLastTopRow = topRow;
        mLastReverseVideo = reverseVideo;
        mLastAlternateBuffer = alternateBuffer;
        mLastCursorRow = cursorRow;
        mLastCursorCol = cursorCol;
        mLastCursorVisible = cursorVisible;
        mLastCursorShape = cursorShape;
        mLastSelY1 = selY1; mLastSelY2 = selY2; mLastSelX1 = selX1; mLastSelX2 = selX2;
        mLastPaletteHash = paletteHash;
        mDirtyCacheValid = true;
        mHasRenderedFrame = true;

        return dirty;
    }

    private void drawTextRun(Canvas canvas, char[] text, int[] palette, float y, int startColumn, int runWidthColumns,
                             int startCharIndex, int runWidthChars, float mes, int cursor, int cursorStyle,
                             long textStyle, boolean reverseVideo) {
        int foreColor = TextStyle.decodeForeColor(textStyle);
        final int effect = TextStyle.decodeEffect(textStyle);
        int backColor = TextStyle.decodeBackColor(textStyle);
        final boolean bold = (effect & (TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_BLINK)) != 0;
        final boolean underline = (effect & TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE) != 0;
        final boolean italic = (effect & TextStyle.CHARACTER_ATTRIBUTE_ITALIC) != 0;
        final boolean strikeThrough = (effect & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0;
        final boolean dim = (effect & TextStyle.CHARACTER_ATTRIBUTE_DIM) != 0;

        if ((foreColor & 0xff000000) != 0xff000000) {
            // Let bold have bright colors if applicable (one of the first 8):
            if (bold && foreColor >= 0 && foreColor < 8) foreColor += 8;
            foreColor = palette[foreColor];
        }

        if ((backColor & 0xff000000) != 0xff000000) {
            backColor = palette[backColor];
        }

        // Reverse video here if _one and only one_ of the reverse flags are set:
        final boolean reverseVideoHere = reverseVideo ^ (effect & (TextStyle.CHARACTER_ATTRIBUTE_INVERSE)) != 0;
        if (reverseVideoHere) {
            int tmp = foreColor;
            foreColor = backColor;
            backColor = tmp;
        }

        float left = startColumn * mFontWidth;
        float right = left + runWidthColumns * mFontWidth;

        // PocketShell #172 — Option A: pin every glyph (regular OR bold) into
        // the regular-advance cell width.
        //
        // Background: the caller computed `mes` by summing per-codepoint widths
        // from the `asciiMeasures` cache, which was populated at construction
        // time with `setFakeBoldText(false)`. When the current run is bold:
        //
        //   - Some monospace fonts on Android (notably the default
        //     `Typeface.MONOSPACE` bold variant) advertise *narrower* bold
        //     advances than regular, so the cached regular widths overestimate
        //     `mes` for bold runs and the existing horizontal-scale branch
        //     never triggers — the run is drawn too narrow inside its cell
        //     allocation, leaving visible gaps between cells.
        //   - Fake-bold stroke thickening also bleeds horizontally past each
        //     glyph's advance, pushing painted pixels into the next cell.
        //
        // Per-letter bold/regular toggles (Claude Code's animated "Working..."
        // spinner) compound both effects and produce the visible gap/overlap
        // pattern reported in #172.
        //
        // The fix has two complementary parts:
        //
        //   1. Re-measure bold runs with `setFakeBoldText(true)` so the
        //      horizontal-scale branch below sees the true bold advance.
        //      The branch then stretches (or compresses) the bold run to fill
        //      exactly the regular-advance cell allocation, so bold and
        //      regular runs occupy the same column footprint.
        //   2. Clip every glyph draw to its column-aligned cell rectangle in
        //      pre-scale (physical) coordinates. Stroke bleed (fake bold,
        //      italic skew, ligature overhang) is pinned inside the cell so
        //      neighbors are never visually displaced. The y axis is left
        //      unclipped so glyphs on adjacent rows still render.
        //
        // Trade-off the issue calls for: bold loses a sliver of stroke weight
        // at the cell edges, but cell columns stay on the regular-advance
        // grid for every glyph, every weight, every run.
        if (bold) {
            final boolean previousBold = mTextPaint.isFakeBoldText();
            mTextPaint.setFakeBoldText(true);
            mes = mTextPaint.measureText(text, startCharIndex, runWidthChars);
            mTextPaint.setFakeBoldText(previousBold);
        }

        // Physical (pre-scale) cell bounds — fixed multiples of `mFontWidth`,
        // independent of the rendered glyph's actual advance width. Used both
        // for the foreground glyph clip and as the source for the post-scale
        // `left`/`right` rectangles used by the background / cursor draws.
        final float cellLeftPx = startColumn * mFontWidth;
        final float cellRightPx = cellLeftPx + runWidthColumns * mFontWidth;

        mes = mes / mFontWidth;
        boolean savedMatrix = false;
        if (Math.abs(mes - runWidthColumns) > 0.01) {
            canvas.save();
            canvas.scale(runWidthColumns / mes, 1.f);
            left *= mes / runWidthColumns;
            right *= mes / runWidthColumns;
            savedMatrix = true;
        }

        if (backColor != palette[TextStyle.COLOR_INDEX_BACKGROUND]) {
            // Only draw non-default background.
            mTextPaint.setColor(backColor);
            canvas.drawRect(left, y - mFontLineSpacingAndAscent + mFontAscent, right, y, mTextPaint);
        }

        if (cursor != 0) {
            mTextPaint.setColor(cursor);
            float cursorHeight = mFontLineSpacingAndAscent - mFontAscent;
            if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_UNDERLINE) cursorHeight /= 4.;
            else if (cursorStyle == TerminalEmulator.TERMINAL_CURSOR_STYLE_BAR) right -= ((right - left) * 3) / 4.;
            canvas.drawRect(left, y - cursorHeight, right, y, mTextPaint);
        }

        if ((effect & TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE) == 0) {
            if (dim) {
                int red = (0xFF & (foreColor >> 16));
                int green = (0xFF & (foreColor >> 8));
                int blue = (0xFF & foreColor);
                // Dim color handling used by libvte which in turn took it from xterm
                // (https://bug735245.bugzilla-attachments.gnome.org/attachment.cgi?id=284267):
                red = red * 2 / 3;
                green = green * 2 / 3;
                blue = blue * 2 / 3;
                foreColor = 0xFF000000 + (red << 16) + (green << 8) + blue;
            }

            mTextPaint.setFakeBoldText(bold);
            mTextPaint.setUnderlineText(underline);
            mTextPaint.setTextSkewX(italic ? -0.35f : 0.f);
            mTextPaint.setStrikeThruText(strikeThrough);
            mTextPaint.setColor(foreColor);

            // PocketShell #172 (Option A clip) — restrict the foreground glyph
            // draw to this run's cell-aligned physical column footprint so
            // fake-bold stroke thickening (and italic skew, ligature overhang)
            // cannot bleed sideways into neighboring columns. The clip is
            // computed in pre-scale physical pixels and applied OUTSIDE the
            // existing scale matrix; that way the clip survives the scale
            // and bounds physical ink at the cell boundary regardless of
            // how aggressively the bold paint draws over its advance.
            //
            // We must temporarily pop the scale matrix (if active) so the
            // `clipRect` is intersected in physical coordinates, then push
            // a new state that re-applies the scale on top of the physical
            // clip. The y range is left at full canvas height so descenders
            // / ascenders that overlap the next row's pixel band still
            // render unclipped.
            if (savedMatrix) {
                canvas.restore();
                savedMatrix = false;
            }
            canvas.save();
            canvas.clipRect(cellLeftPx, 0f, cellRightPx, (float) canvas.getHeight());
            if (Math.abs(mes - runWidthColumns) > 0.01) {
                canvas.scale(runWidthColumns / mes, 1.f);
            }

            // The text alignment is the default Paint.Align.LEFT.
            canvas.drawTextRun(text, startCharIndex, runWidthChars, startCharIndex, runWidthChars, left, y - mFontLineSpacingAndAscent, false, mTextPaint);

            canvas.restore();
        }

        if (savedMatrix) canvas.restore();
    }

    public float getFontWidth() {
        return mFontWidth;
    }

    public int getFontLineSpacing() {
        return mFontLineSpacing;
    }

    /**
     * Distance in pixels from the top of the canvas to the top of row 0's
     * cell. Equals {@code mFontLineSpacing + mFontAscent} (Paint's ascent is
     * negative, so this value is slightly less than {@link #mFontLineSpacing}).
     * Exposed so external Compose overlays (PocketShell's smart-selection
     * affordance overlay) can compute pixel rectangles in the same
     * row-aligned coordinate space the renderer itself paints into, without
     * duplicating the Paint metrics math. The renderer uses this value as the
     * top-of-row offset for the first row in {@link #render}.
     */
    public int getFontLineSpacingAndAscent() {
        return mFontLineSpacingAndAscent;
    }
}
