package com.pocketshell.app.usage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class UsageResetEventsParserTest {

    private val parser = UsageResetEventsParser

    @Test
    fun parse_emptyDocument_returnsEmpty() {
        assertTrue(parser.parse("""{"reset_events": []}""").isEmpty())
    }

    @Test
    fun parse_blankAndGarbage_returnsEmpty() {
        assertTrue(parser.parse("").isEmpty())
        assertTrue(parser.parse("   ").isEmpty())
        assertTrue(parser.parse("not json").isEmpty())
        assertTrue(parser.parse("""{"other": 1}""").isEmpty())
    }

    @Test
    fun parse_fullEvent_mapsEveryField() {
        val json = """
            {"reset_events": [
              {"type":"reset","provider":"codex","window":"short_term",
               "detected_at":"2026-06-11T12:00:00Z",
               "detected_reset_at":"2026-06-11T12:00:00Z",
               "stated_reset_at":"2026-06-11T12:15:00Z",
               "new_reset_at":"2026-06-11T17:00:00Z",
               "timing":"early","minutes_early":15,
               "previous_percent_remaining":6.0,"current_percent_remaining":100.0,
               "signals":["recovery","window_rolled"],
               "reset_key":"codex|short_term|2026-06-11T17:00:00Z"}
            ]}
        """.trimIndent()

        val events = parser.parse(json)
        assertEquals(1, events.size)
        val e = events.single()
        assertEquals("codex", e.provider)
        assertEquals("short_term", e.window)
        assertEquals(Instant.parse("2026-06-11T12:00:00Z"), e.detectedAt)
        assertEquals(Instant.parse("2026-06-11T12:15:00Z"), e.statedResetAt)
        assertEquals(Instant.parse("2026-06-11T17:00:00Z"), e.newResetAt)
        assertEquals("early", e.timing)
        assertEquals(15, e.minutesEarly)
        assertEquals("codex|short_term|2026-06-11T17:00:00Z", e.resetKey)
        assertTrue(e.isEarly)
    }

    @Test
    fun parse_onOrAfterStated_isNotEarly_andNullMinutes() {
        val json = """
            {"reset_events": [
              {"provider":"claude","window":"long_term",
               "detected_at":"2026-06-11T12:00:00Z",
               "timing":"on_or_after_stated","minutes_early":null,
               "reset_key":"claude|long_term|x"}
            ]}
        """.trimIndent()
        val e = parser.parse(json).single()
        assertEquals(false, e.isEarly)
        assertNull(e.minutesEarly)
    }

    @Test
    fun parse_dropsRowsMissingResetKeyOrProvider() {
        val json = """
            {"reset_events": [
              {"provider":"codex","window":"short_term","detected_at":"2026-06-11T12:00:00Z"},
              {"window":"short_term","reset_key":"x|y|z","detected_at":"2026-06-11T12:00:00Z"},
              {"provider":"claude","reset_key":"claude|w|z","detected_at":"2026-06-11T12:00:00Z"}
            ]}
        """.trimIndent()
        // Only the third row has BOTH provider and reset_key.
        val events = parser.parse(json)
        assertEquals(1, events.size)
        assertEquals("claude", events.single().provider)
    }

    @Test
    fun parse_keepsLogOrder_oldestFirst() {
        val json = """
            {"reset_events": [
              {"provider":"codex","reset_key":"k1","detected_at":"2026-06-11T10:00:00Z"},
              {"provider":"codex","reset_key":"k2","detected_at":"2026-06-11T12:00:00Z"}
            ]}
        """.trimIndent()
        val events = parser.parse(json)
        assertEquals(listOf("k1", "k2"), events.map { it.resetKey })
    }
}
