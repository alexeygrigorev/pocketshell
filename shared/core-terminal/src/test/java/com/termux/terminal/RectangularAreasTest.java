package com.termux.terminal;

public class RectangularAreasTest extends TerminalTestCase {

	/** http://www.vt100.net/docs/vt510-rm/DECFRA */
	public void testFillRectangularArea() {
		withTerminalSized(3, 3).enterString("\033[88$x").assertLinesAre("XXX", "XXX", "XXX");
		withTerminalSized(3, 3).enterString("\033[88;1;1;2;10$x").assertLinesAre("XXX", "XXX", "   ");
		withTerminalSized(3, 3).enterString("\033[88;2;1;3;10$x").assertLinesAre("   ", "XXX", "XXX");
		withTerminalSized(3, 3).enterString("\033[88;1;1;100;1$x").assertLinesAre("X  ", "X  ", "X  ");
		withTerminalSized(3, 3).enterString("\033[88;1;1;100;2$x").assertLinesAre("XX ", "XX ", "XX ");
		withTerminalSized(3, 3).enterString("\033[88;100;1;100;2$x").assertLinesAre("   ", "   ", "   ");
	}

	/** http://www.vt100.net/docs/vt510-rm/DECERA */
	public void testEraseRectangularArea() {
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[$z").assertLinesAre("   ", "   ", "   ");
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[1;1;2;10$z").assertLinesAre("   ", "   ", "GHI");
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[2;1;3;10$z").assertLinesAre("ABC", "   ", "   ");
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[1;1;100;1$z").assertLinesAre(" BC", " EF", " HI");
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[1;1;100;2$z").assertLinesAre("  C", "  F", "  I");
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[100;1;100;2$z").assertLinesAre("ABC", "DEF", "GHI");

		withTerminalSized(3, 3).enterString("A\033[$zBC").assertLinesAre(" BC", "   ", "   ");
	}

	/** http://www.vt100.net/docs/vt510-rm/DECSED */
	public void testSelectiveEraseInDisplay() {
		// ${CSI}1"q enables protection, ${CSI}0"q disables it.
		// ${CSI}?${0,1,2}J" erases (0=cursor to end, 1=start to cursor, 2=complete display).
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[?2J").assertLinesAre("   ", "   ", "   ");
		withTerminalSized(3, 3).enterString("ABC\033[1\"qDE\033[0\"qFGHI\033[?2J").assertLinesAre("   ", "DE ", "   ");
		withTerminalSized(3, 3).enterString("\033[1\"qABCDE\033[0\"qFGHI\033[?2J").assertLinesAre("ABC", "DE ", "   ");
	}

	/** http://vt100.net/docs/vt510-rm/DECSEL */
	public void testSelectiveEraseInLine() {
		// ${CSI}1"q enables protection, ${CSI}0"q disables it.
		// ${CSI}?${0,1,2}K" erases (0=cursor to end, 1=start to cursor, 2=complete line).
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[?2K").assertLinesAre("ABC", "DEF", "   ");
		withTerminalSized(3, 3).enterString("ABCDE\033[?0KFGHI").assertLinesAre("ABC", "DEF", "GHI");
		withTerminalSized(3, 3).enterString("ABCDE\033[?1KFGHI").assertLinesAre("ABC", "  F", "GHI");
		withTerminalSized(3, 3).enterString("ABCDE\033[?2KFGHI").assertLinesAre("ABC", "  F", "GHI");
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[2;2H\033[?0K").assertLinesAre("ABC", "D  ", "GHI");
		withTerminalSized(3, 3).enterString("ABC\033[1\"qD\033[0\"qE\033[?2KFGHI").assertLinesAre("ABC", "D F", "GHI");
	}

	/** http://www.vt100.net/docs/vt510-rm/DECSERA */
	public void testSelectiveEraseInRectangle() {
		// ${CSI}1"q enables protection, ${CSI}0"q disables it.
		// ${CSI}?${TOP};${LEFT};${BOTTOM};${RIGHT}${" erases.
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[${").assertLinesAre("   ", "   ", "   ");
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[1;1;2;10${").assertLinesAre("   ", "   ", "GHI");
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[2;1;3;10${").assertLinesAre("ABC", "   ", "   ");
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[1;1;100;1${").assertLinesAre(" BC", " EF", " HI");
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[1;1;100;2${").assertLinesAre("  C", "  F", "  I");
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[100;1;100;2${").assertLinesAre("ABC", "DEF", "GHI");

		withTerminalSized(3, 3).enterString("ABCD\033[1\"qE\033[0\"qFGHI\033[${").assertLinesAre("   ", " E ", "   ");
		withTerminalSized(3, 3).enterString("ABCD\033[1\"qE\033[0\"qFGHI\033[1;1;2;10${").assertLinesAre("   ", " E ", "GHI");
	}

	/** http://vt100.net/docs/vt510-rm/DECCRA */
	public void testRectangularCopy() {
		// "${CSI}${SRC_TOP};${SRC_LEFT};${SRC_BOTTOM};${SRC_RIGHT};${SRC_PAGE};${DST_TOP};${DST_LEFT};${DST_PAGE}\$v"
		withTerminalSized(7, 3).enterString("ABC\r\nDEF\r\nGHI\033[1;1;2;2;1;2;5;1$v").assertLinesAre("ABC    ", "DEF AB ", "GHI DE ");
		withTerminalSized(7, 3).enterString("ABC\r\nDEF\r\nGHI\033[1;1;3;3;1;1;4;1$v").assertLinesAre("ABCABC ", "DEFDEF ", "GHIGHI ");
		withTerminalSized(7, 3).enterString("ABC\r\nDEF\r\nGHI\033[1;1;3;3;1;1;3;1$v").assertLinesAre("ABABC  ", "DEDEF  ", "GHGHI  ");
		withTerminalSized(7, 3).enterString("   ABC\r\n   DEF\r\n   GHI\033[1;4;3;6;1;1;1;1$v").assertLinesAre("ABCABC ", "DEFDEF ",
				"GHIGHI ");
		withTerminalSized(7, 3).enterString("   ABC\r\n   DEF\r\n   GHI\033[1;4;3;6;1;1;2;1$v").assertLinesAre(" ABCBC ", " DEFEF ",
				" GHIHI ");
		withTerminalSized(3, 3).enterString("ABC\r\nDEF\r\nGHI\033[1;1;2;2;1;2;2;1$v").assertLinesAre("ABC", "DAB", "GDE");

		// Enable ${CSI}?6h origin mode (DECOM) and ${CSI}?69h for left/right margin (DECLRMM) enabling, ${CSI}${LEFTMARGIN};${RIGHTMARGIN}s
		// for DECSLRM margin setting.
		withTerminalSized(5, 5).enterString("\033[?6h\033[?69h\033[2;4s");
		enterString("ABCDEFGHIJK").assertLinesAre(" ABC ", " DEF ", " GHI ", " JK  ", "     ");
		enterString("\033[1;1;2;2;1;2;2;1$v").assertLinesAre(" ABC ", " DAB ", " GDE ", " JK  ", "     ");
	}

	/** http://vt100.net/docs/vt510-rm/DECCARA */
	public void testChangeAttributesInRectangularArea() {
		final int b = TextStyle.CHARACTER_ATTRIBUTE_BOLD;
		// "${CSI}${TOP};${LEFT};${BOTTOM};${RIGHT};${ATTRIBUTES}\$r"
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[1;1;2;2;1$r").assertLinesAre("ABC", "DEF", "GHI");
		assertEffectAttributesSet(effectLine(b, b, b), effectLine(b, b, 0), effectLine(0, 0, 0));

		// Now with http://www.vt100.net/docs/vt510-rm/DECSACE ("${CSI}2*x") specifying rectangle:
		withTerminalSized(3, 3).enterString("\033[2*xABCDEFGHI\033[1;1;2;2;1$r").assertLinesAre("ABC", "DEF", "GHI");
		assertEffectAttributesSet(effectLine(b, b, 0), effectLine(b, b, 0), effectLine(0, 0, 0));
	}

	/** http://vt100.net/docs/vt510-rm/DECCARA */
	public void testReverseAttributesInRectangularArea() {
		final int b = TextStyle.CHARACTER_ATTRIBUTE_BOLD;
		final int u = TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
		final int bu = TextStyle.CHARACTER_ATTRIBUTE_BOLD | TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE;
		// "${CSI}${TOP};${LEFT};${BOTTOM};${RIGHT};${ATTRIBUTES}\$t"
		withTerminalSized(3, 3).enterString("ABCDEFGHI\033[1;1;2;2;1$t").assertLinesAre("ABC", "DEF", "GHI");
		assertEffectAttributesSet(effectLine(b, b, b), effectLine(b, b, 0), effectLine(0, 0, 0));

		// Now with http://www.vt100.net/docs/vt510-rm/DECSACE ("${CSI}2*x") specifying rectangle:
		withTerminalSized(3, 3).enterString("\033[2*xABCDEFGHI\033[1;1;2;2;1$t").assertLinesAre("ABC", "DEF", "GHI");
		assertEffectAttributesSet(effectLine(b, b, 0), effectLine(b, b, 0), effectLine(0, 0, 0));

		// Check reversal by initially bolding the B:
		withTerminalSized(3, 3).enterString("\033[2*xA\033[1mB\033[0mCDEFGHI\033[1;1;2;2;1$t").assertLinesAre("ABC", "DEF", "GHI");
		assertEffectAttributesSet(effectLine(b, 0, 0), effectLine(b, b, 0), effectLine(0, 0, 0));

		// Check reversal by initially underlining A, bolding B, then reversing both bold and underline:
		withTerminalSized(3, 3).enterString("\033[2*x\033[4mA\033[0m\033[1mB\033[0mCDEFGHI\033[1;1;2;2;1;4$t").assertLinesAre("ABC", "DEF",
				"GHI");
		assertEffectAttributesSet(effectLine(b, u, 0), effectLine(bu, bu, 0), effectLine(0, 0, 0));
	}

	private static final long OSC8_ACTIVE = TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK;
	private static final long OSC8_OPENER = TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK_START;
	/**
	 * Deliberately spelled out from the two public bit constants rather than reusing a production
	 * mask helper, so mutating that helper cannot silently move this test's expectation too.
	 */
	private static final long OSC8_PROVENANCE = OSC8_ACTIVE | OSC8_OPENER;

	/**
	 * Paints, on row 0 of an 8x4 terminal, columns 0..5 as:
	 * <pre>
	 *   col 0 'A' - first cell of hyperlink #1 (active + opener)
	 *   col 1 'B' - continuation of hyperlink #1 (active only)
	 *   col 2 'C' - after the closer, plain text (no provenance)
	 *   col 3 'D' - first cell of an adjacent, independent hyperlink #2 (active + opener)
	 *   col 4 'E' - continuation of hyperlink #2 (active only)
	 *   col 5 'F' - after the second closer, plain text (no provenance)
	 * </pre>
	 * Foreground colour index 99 is applied throughout so the re-encode's colour path is exercised.
	 */
	private void paintTwoAdjacentOsc8LinksWithCloses() {
		withTerminalSized(8, 4).enterString("\033[38;5;99m"
			+ "\033]8;id=one;https://example.com/first\033\\AB\033]8;;\007"
			+ "C"
			+ "\033]8;id=two;https://example.com/second\033\\DE\033]8;;\007"
			+ "F");
		assertOsc8ProvenanceLayoutIntact("before the rectangular attribute mutation");
	}

	private void assertOsc8ProvenanceLayoutIntact(String when) {
		assertEquals("col 0 must stay the opener cell of hyperlink #1 " + when,
			OSC8_ACTIVE | OSC8_OPENER, getStyleAt(0, 0) & OSC8_PROVENANCE);
		assertEquals("col 1 must stay an active continuation, never a second opener " + when,
			OSC8_ACTIVE, getStyleAt(0, 1) & OSC8_PROVENANCE);
		assertEquals("col 2 follows the closer and must carry no provenance " + when,
			0L, getStyleAt(0, 2) & OSC8_PROVENANCE);
		assertEquals("col 3 must stay the opener cell of the adjacent hyperlink #2 " + when,
			OSC8_ACTIVE | OSC8_OPENER, getStyleAt(0, 3) & OSC8_PROVENANCE);
		assertEquals("col 4 must stay an active continuation of hyperlink #2 " + when,
			OSC8_ACTIVE, getStyleAt(0, 4) & OSC8_PROVENANCE);
		assertEquals("col 5 follows the second closer and must carry no provenance " + when,
			0L, getStyleAt(0, 5) & OSC8_PROVENANCE);
		assertEquals("untouched blank rows must never gain provenance " + when,
			0L, getStyleAt(1, 0) & OSC8_PROVENANCE);
	}

	private void assertRectangleWasActuallyMutatedToBold() {
		for (int column = 0; column < 6; column++) {
			assertTrue("column " + column + " must have received the requested bold bit; without a real"
					+ " mutation the provenance assertions would pass vacuously",
				(TextStyle.decodeEffect(getStyleAt(0, column)) & TextStyle.CHARACTER_ATTRIBUTE_BOLD) != 0);
			assertEquals("column " + column + " must keep its foreground colour through the re-encode",
				99, TextStyle.decodeForeColor(getStyleAt(0, column)));
		}
	}

	/**
	 * DECCARA re-encodes each cell's colours and effects. Regression for #1961: the re-encode must
	 * carry the OSC 8 provenance bits (11/12) across, or #1955's hard-wrap link repair silently
	 * declines to join a wrapped long link after any rectangular attribute change.
	 * http://vt100.net/docs/vt510-rm/DECCARA
	 */
	public void testChangeAttributesInRectangularAreaPreservesOsc8Provenance() {
		paintTwoAdjacentOsc8LinksWithCloses();
		// DECSACE rectangular ("${CSI}2*x"), then DECCARA bold over rows 1..3, columns 1..8.
		enterString("\033[2*x\033[1;1;3;8;1$r");
		assertRectangleWasActuallyMutatedToBold();
		assertOsc8ProvenanceLayoutIntact("after DECCARA");
	}

	/**
	 * Same invariant through the stream (non-rectangular, DECSACE default) DECCARA path.
	 * http://vt100.net/docs/vt510-rm/DECCARA
	 */
	public void testChangeAttributesInStreamAreaPreservesOsc8Provenance() {
		paintTwoAdjacentOsc8LinksWithCloses();
		enterString("\033[1;1;2;1;1$r");
		assertRectangleWasActuallyMutatedToBold();
		assertOsc8ProvenanceLayoutIntact("after stream-mode DECCARA");
	}

	/**
	 * DECRARA takes the same re-encode path with reverse semantics.
	 * http://www.vt100.net/docs/vt510-rm/DECRARA
	 */
	public void testReverseAttributesInRectangularAreaPreservesOsc8Provenance() {
		paintTwoAdjacentOsc8LinksWithCloses();
		// DECSACE rectangular, then DECRARA reversing bold (cells start non-bold, so they end bold).
		enterString("\033[2*x\033[1;1;3;8;1$t");
		assertRectangleWasActuallyMutatedToBold();
		assertOsc8ProvenanceLayoutIntact("after DECRARA");
	}

	/**
	 * A rectangular attribute mutation must not disturb the close/reset semantics either: after the
	 * mutation, a still-open hyperlink cleared by {@link TerminalEmulator#reset()} must stop marking
	 * newly painted cells.
	 */
	public void testRectangularAttributeMutationKeepsOsc8CloseAndResetSemantics() {
		paintTwoAdjacentOsc8LinksWithCloses();
		enterString("\033[2*x\033[1;1;3;8;1$r");
		assertOsc8ProvenanceLayoutIntact("after DECCARA");

		// Open a hyperlink, mutate the rectangle again, then reset: reset still closes it.
		enterString("\033]8;;https://example.com/open-across-reset\033\\");
		enterString("\033[2*x\033[1;1;3;8;1$r");
		mTerminal.reset();
		enterString("z");
		int zColumn = mTerminal.getCursorCol() - 1;
		assertEquals("terminal reset must still close an OSC 8 hyperlink that spanned a DECCARA",
			0L, getStyleAt(0, zColumn) & OSC8_PROVENANCE);
	}

}
