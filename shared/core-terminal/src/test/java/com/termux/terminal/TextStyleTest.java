package com.termux.terminal;

import junit.framework.TestCase;

public class TextStyleTest extends TestCase {

	private static final int[] ALL_EFFECTS = new int[]{0, TextStyle.CHARACTER_ATTRIBUTE_BOLD, TextStyle.CHARACTER_ATTRIBUTE_ITALIC,
			TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE, TextStyle.CHARACTER_ATTRIBUTE_BLINK, TextStyle.CHARACTER_ATTRIBUTE_INVERSE,
			TextStyle.CHARACTER_ATTRIBUTE_INVISIBLE, TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH, TextStyle.CHARACTER_ATTRIBUTE_PROTECTED,
			TextStyle.CHARACTER_ATTRIBUTE_DIM};

	public void testEncodingSingle() {
		for (int fx : ALL_EFFECTS) {
			for (int fg = 0; fg < TextStyle.NUM_INDEXED_COLORS; fg++) {
				for (int bg = 0; bg < TextStyle.NUM_INDEXED_COLORS; bg++) {
					long encoded = TextStyle.encode(fg, bg, fx);
					assertEquals(fg, TextStyle.decodeForeColor(encoded));
					assertEquals(bg, TextStyle.decodeBackColor(encoded));
					assertEquals(fx, TextStyle.decodeEffect(encoded));
				}
			}
		}
	}

	public void testOsc8ProvenanceBitDoesNotCollideWithStyleEncoding() {
		long encoded = TextStyle.encode(0xFF123456, 0xFF654321, 0x7FF);
		assertEquals(0L, encoded & TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK);
		assertEquals(0L, encoded & TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK_START);
		assertEquals(
			"OSC 8 provenance must sit below the background field",
			0L,
			TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK & 0xFFFFFFFFFFFF0000L
		);
		assertEquals(
			"renderer-visible effects occupy bits 0 through 10 only",
			0,
			TextStyle.decodeEffect(TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK)
		);
		assertEquals(
			0,
			TextStyle.decodeEffect(TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK_START)
		);
	}

	/**
	 * #1961: a post-paint re-encode of an existing cell must change only the requested colours and
	 * effects and carry the OSC 8 provenance bits through untouched.
	 */
	public void testEncodePreservingProvenanceKeepsOsc8BitsAndOnlyThose() {
		long previous = TextStyle.encode(99, 7, TextStyle.CHARACTER_ATTRIBUTE_ITALIC)
			| TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK
			| TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK_START;

		long reencoded = TextStyle.encodePreservingProvenance(12, 34,
			TextStyle.CHARACTER_ATTRIBUTE_BOLD, previous);

		assertEquals("requested foreground must be applied", 12, TextStyle.decodeForeColor(reencoded));
		assertEquals("requested background must be applied", 34, TextStyle.decodeBackColor(reencoded));
		assertEquals("only the requested effect bits must survive",
			TextStyle.CHARACTER_ATTRIBUTE_BOLD, TextStyle.decodeEffect(reencoded));
		assertEquals("both OSC 8 provenance bits must be carried across",
			TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK | TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK_START,
			reencoded & TextStyle.OSC8_PROVENANCE_MASK);
		assertEquals("the re-encode must add nothing beyond the requested style plus provenance",
			TextStyle.encode(12, 34, TextStyle.CHARACTER_ATTRIBUTE_BOLD)
				| TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK
				| TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK_START,
			reencoded);
	}

	/** A cell with no provenance must not gain any from a re-encode. */
	public void testEncodePreservingProvenanceDoesNotInventProvenance() {
		long previous = TextStyle.encode(1, 2, TextStyle.CHARACTER_ATTRIBUTE_UNDERLINE);
		assertEquals(0L, previous & TextStyle.OSC8_PROVENANCE_MASK);

		long reencoded = TextStyle.encodePreservingProvenance(3, 4,
			TextStyle.CHARACTER_ATTRIBUTE_BOLD, previous);

		assertEquals("a plain cell must stay plain", 0L, reencoded & TextStyle.OSC8_PROVENANCE_MASK);
		assertEquals(TextStyle.encode(3, 4, TextStyle.CHARACTER_ATTRIBUTE_BOLD), reencoded);
	}

	/** Only the opener bit set (no active bit) must be carried through independently. */
	public void testEncodePreservingProvenanceCarriesEachBitIndependently() {
		long onlyActive = TextStyle.encode(0, 0, 0) | TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK;
		assertEquals(TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK,
			TextStyle.encodePreservingProvenance(5, 6, 0, onlyActive) & TextStyle.OSC8_PROVENANCE_MASK);

		long onlyOpener = TextStyle.encode(0, 0, 0) | TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK_START;
		assertEquals(TextStyle.CHARACTER_ATTRIBUTE_OSC8_HYPERLINK_START,
			TextStyle.encodePreservingProvenance(5, 6, 0, onlyOpener) & TextStyle.OSC8_PROVENANCE_MASK);
	}

	public void testEncoding24Bit() {
		int[] values = {255, 240, 127, 1, 0};
		for (int red : values) {
			for (int green : values) {
				for (int blue : values) {
					int argb = 0xFF000000 | (red << 16) | (green << 8) | blue;
					long encoded = TextStyle.encode(argb, 0, 0);
					assertEquals(argb, TextStyle.decodeForeColor(encoded));
					encoded = TextStyle.encode(0, argb, 0);
					assertEquals(argb, TextStyle.decodeBackColor(encoded));
				}
			}
		}
	}


	public void testEncodingCombinations() {
		for (int f1 : ALL_EFFECTS) {
			for (int f2 : ALL_EFFECTS) {
				int combined = f1 | f2;
				assertEquals(combined, TextStyle.decodeEffect(TextStyle.encode(0, 0, combined)));
			}
		}
	}

	public void testEncodingStrikeThrough() {
		long encoded = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
				TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH);
		assertTrue((TextStyle.decodeEffect(encoded) & TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH) != 0);
	}

	public void testEncodingProtected() {
		long encoded = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
				TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH);
		assertEquals(0, (TextStyle.decodeEffect(encoded) & TextStyle.CHARACTER_ATTRIBUTE_PROTECTED));
		encoded = TextStyle.encode(TextStyle.COLOR_INDEX_FOREGROUND, TextStyle.COLOR_INDEX_BACKGROUND,
				TextStyle.CHARACTER_ATTRIBUTE_STRIKETHROUGH | TextStyle.CHARACTER_ATTRIBUTE_PROTECTED);
		assertTrue((TextStyle.decodeEffect(encoded) & TextStyle.CHARACTER_ATTRIBUTE_PROTECTED) != 0);
	}

}
