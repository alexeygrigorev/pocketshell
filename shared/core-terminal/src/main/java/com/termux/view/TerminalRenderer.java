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

        if (reverseVideo)
            canvas.drawColor(palette[TextStyle.COLOR_INDEX_FOREGROUND], PorterDuff.Mode.SRC);

        float heightOffset = mFontLineSpacingAndAscent;
        for (int row = topRow; row < endRow; row++) {
            heightOffset += mFontLineSpacing;

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
