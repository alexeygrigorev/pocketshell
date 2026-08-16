package com.pocketshell.app.portfwd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #2176 — the durable half of "the list survives an app restart and a
 * session switch".
 *
 * A ports list is only durable if what we wrote is exactly what we read back.
 * These pin that on the inputs that actually break naive formats: terminal text
 * containing the delimiters, a shell quote, a newline, and non-ASCII — all of
 * which arrive routinely in [SessionPortMention.matchedText] because it is raw
 * terminal output.
 */
class SessionPortMentionCodecTest {

    @Test
    fun `round-trips a plain mention`() {
        val mentions = listOf(
            SessionPortMention(
                port = 5173,
                firstSeenAtEpochMs = 1_700_000_000_000L,
                process = "node",
                matchedText = "Local:   http://localhost:5173/",
            ),
        )

        assertEquals(mentions, SessionPortMentionCodec.decode(SessionPortMentionCodec.encode(mentions)))
    }

    /**
     * The delimiters are the first thing real terminal output contains: a URL
     * has a `:`, prose has a `,`. If either survived into a field unescaped the
     * decode would split in the wrong place and produce garbage rows.
     */
    @Test
    fun `round-trips text containing both delimiters, quotes and newlines`() {
        val hostile = "Local: http://localhost:8000/a,b 'quoted' \"dq\"\nnext line\ttab"
        val mentions = listOf(
            SessionPortMention(
                port = 8000,
                firstSeenAtEpochMs = 42L,
                process = "python3,uv:server",
                matchedText = hostile,
            ),
        )

        val decoded = SessionPortMentionCodec.decode(SessionPortMentionCodec.encode(mentions))

        assertEquals(1, decoded.size)
        assertEquals(hostile, decoded.single().matchedText)
        assertEquals("python3,uv:server", decoded.single().process)
    }

    @Test
    fun `round-trips non-ASCII text`() {
        val mentions = listOf(
            SessionPortMention(
                port = 3000,
                firstSeenAtEpochMs = 7L,
                process = "сервер",
                matchedText = "Сервер запущен: http://localhost:3000/ 🚀",
            ),
        )

        assertEquals(mentions, SessionPortMentionCodec.decode(SessionPortMentionCodec.encode(mentions)))
    }

    /**
     * Issue #2160's failure mode is that tmux replaces every non-printable byte
     * and every multi-byte UTF-8 sequence with `_` when the reading client is
     * not in UTF-8 mode. Encoding down to printable ASCII means the sanitiser
     * would have nothing to touch even if a read ever lost its `-u`. This is the
     * second layer of that defence; [SessionPortsHostOptionsTest] pins the
     * first.
     */
    @Test
    fun `encoded value is printable ASCII and free of shell quotes`() {
        val encoded = SessionPortMentionCodec.encode(
            listOf(
                SessionPortMention(
                    port = 9000,
                    firstSeenAtEpochMs = 1L,
                    process = "го'сервер",
                    matchedText = "listening on 0.0.0.0:9000 — 'ready'\n",
                ),
            ),
        )

        assertTrue(
            "every byte must be printable ASCII; got: $encoded",
            encoded.all { it.code in 0x20..0x7E },
        )
        assertTrue("no single quote may survive: $encoded", '\'' !in encoded)
        assertTrue("no double quote may survive: $encoded", '"' !in encoded)
        assertTrue("no backslash may survive: $encoded", '\\' !in encoded)
        // And the sanitiser is a no-op on it.
        assertEquals(encoded, utf8Sanitize(encoded))
    }

    @Test
    fun `decodes an absent or blank option to an empty list`() {
        assertEquals(emptyList<SessionPortMention>(), SessionPortMentionCodec.decode(null))
        assertEquals(emptyList<SessionPortMention>(), SessionPortMentionCodec.decode(""))
        assertEquals(emptyList<SessionPortMention>(), SessionPortMentionCodec.decode("   \n"))
    }

    /**
     * D22 hard cut: an unrecognised header is treated as absent, never
     * best-effort parsed. A foreign value must degrade to "nothing recorded",
     * not to plausible-looking wrong rows.
     */
    @Test
    fun `rejects an unknown format header outright`() {
        val v1 = SessionPortMentionCodec.encode(
            listOf(SessionPortMention(5173, 1L, "node", "x")),
        )
        val v2 = v1.replaceFirst("v1", "v2")

        assertNotEquals(emptyList<SessionPortMention>(), SessionPortMentionCodec.decode(v1))
        assertEquals(emptyList<SessionPortMention>(), SessionPortMentionCodec.decode(v2))
    }

    /** A partially-garbled option should cost one row, not the whole panel. */
    @Test
    fun `skips an unparseable record and keeps its siblings`() {
        val good = SessionPortMentionCodec.encode(
            listOf(
                SessionPortMention(5173, 1L, "node", "a"),
                SessionPortMention(8000, 2L, "python3", "b"),
            ),
        )
        val corrupted = good.replaceFirst("5173:1:", "notaport:1:")

        val decoded = SessionPortMentionCodec.decode(corrupted)

        assertEquals(listOf(8000), decoded.map { it.port })
    }

    @Test
    fun `rejects an out-of-range port`() {
        assertEquals(
            emptyList<SessionPortMention>(),
            SessionPortMentionCodec.decode("v1,70000:1:node:x"),
        )
        assertEquals(
            emptyList<SessionPortMention>(),
            SessionPortMentionCodec.decode("v1,0:1:node:x"),
        )
    }

    /**
     * A tmux option is not a database. A session that mentions hundreds of ports
     * must not grow an unbounded value that every read then drags over SSH; the
     * OLDEST entries go first so the ports a user still cares about stay.
     */
    @Test
    fun `caps the stored record count and drops the oldest`() {
        val many = (1..(SessionPortMentionCodec.MAX_RECORDS + 10)).map { index ->
            SessionPortMention(
                port = 3000 + index,
                firstSeenAtEpochMs = index.toLong(),
                process = "p$index",
                matchedText = "m$index",
            )
        }

        val decoded = SessionPortMentionCodec.decode(SessionPortMentionCodec.encode(many))

        assertEquals(SessionPortMentionCodec.MAX_RECORDS, decoded.size)
        assertEquals(many.last().port, decoded.last().port)
        assertTrue("oldest dropped", decoded.none { it.port == many.first().port })
    }

    @Test
    fun `truncates an unbounded matched text`() {
        val huge = "x".repeat(5_000)

        val decoded = SessionPortMentionCodec.decode(
            SessionPortMentionCodec.encode(
                listOf(SessionPortMention(5173, 1L, "node", huge)),
            ),
        )

        assertEquals(SessionPortMentionCodec.MAX_MATCHED_TEXT, decoded.single().matchedText.length)
    }

    /**
     * Merge is what makes a host read safe to apply on top of ports detected
     * live in this process. "First seen" is a property of the PORT, so reading
     * the durable copy after a restart must not push the timestamp forward.
     */
    @Test
    fun `merge keeps the earliest first-seen and the richer detail`() {
        val fromHost = listOf(SessionPortMention(5173, 100L, "", "Local: http://localhost:5173/"))
        val live = listOf(SessionPortMention(5173, 900L, "node", ""))

        val merged = SessionPortMentionCodec.merge(live, fromHost)

        assertEquals(1, merged.size)
        assertEquals(100L, merged.single().firstSeenAtEpochMs)
        assertEquals("node", merged.single().process)
        assertEquals("Local: http://localhost:5173/", merged.single().matchedText)
    }

    @Test
    fun `merge orders chronologically`() {
        val merged = SessionPortMentionCodec.merge(
            listOf(SessionPortMention(9000, 300L, "", "")),
            listOf(
                SessionPortMention(5173, 100L, "", ""),
                SessionPortMention(8000, 200L, "", ""),
            ),
        )

        assertEquals(listOf(5173, 8000, 9000), merged.map { it.port })
    }

    /**
     * tmux's `utf8_sanitize()`, reproduced: every non-printable byte and every
     * multi-byte UTF-8 sequence becomes a single `_`.
     */
    private fun utf8Sanitize(value: String): String =
        value.map { ch -> if (ch.code in 0x20..0x7E) ch else '_' }.joinToString("")
}
