package com.pocketshell.core.usage

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class QuseUsageJsonParserTest {

    private val parser = QuseUsageJsonParser()

    @Test
    fun parse_codexHappyPath() {
        val records = parser.parse(
            """
            {"provider":"codex","status":"ok",
             "short_term":{"percent_remaining":77.0,"reset_at":"2026-05-24T15:53:01Z","window":null},
             "long_term":{"percent_remaining":88.0,"reset_at":"2026-05-30T20:33:54Z","window":null},
             "block_reason":null,"error":null,"details":{}}
            """.trimIndent(),
        )

        assertEquals(1, records.size)
        val record = records.single()
        assertEquals("codex", record.provider)
        assertEquals(UsageStatus.Ok, record.status)
        assertEquals("ok", record.rawStatus)
        assertNull(record.blockReason)
        assertNull(record.lastError)

        assertEquals(2, record.windows.size)
        val shortTerm = record.windows[0]
        assertEquals("short_term", shortTerm.name)
        assertEquals(23.0, shortTerm.used, 0.001)
        assertEquals(100.0, shortTerm.limit, 0.001)
        assertEquals("percent", shortTerm.unit)
        assertEquals(23.0, shortTerm.percent, 0.001)
        assertEquals(Instant.parse("2026-05-24T15:53:01Z"), shortTerm.resetAt)

        val longTerm = record.windows[1]
        assertEquals("long_term", longTerm.name)
        assertEquals(12.0, longTerm.used, 0.001)
        assertEquals(12.0, longTerm.percent, 0.001)
        assertEquals(Instant.parse("2026-05-30T20:33:54Z"), longTerm.resetAt)
    }

    @Test
    fun parse_claudeHappyPath() {
        val records = parser.parse(
            """{"provider":"claude","status":"ok",
              "short_term":{"percent_remaining":41.0,"reset_at":"2026-05-24T14:30:00Z"},
              "long_term":{"percent_remaining":85.0,"reset_at":"2026-05-28T14:59:59Z"},
              "block_reason":null,"error":null,"details":{}}""".trimIndent(),
        )

        val record = records.single()
        assertEquals("claude", record.provider)
        assertEquals(UsageStatus.Ok, record.status)
        assertEquals("Claude Code", record.displayName)
        assertEquals(2, record.windows.size)
        assertEquals(59.0, record.windows[0].percent, 0.001)
        assertEquals(15.0, record.windows[1].percent, 0.001)
    }

    @Test
    fun parse_copilotHappyPath_acceptsNullResetOnShortTerm() {
        val records = parser.parse(
            """{"provider":"copilot","status":"ok",
              "short_term":{"percent_remaining":100.0,"reset_at":null},
              "long_term":{"percent_remaining":96.6,"reset_at":"2026-06-01T00:00:00Z"},
              "block_reason":null,"error":null,"details":{}}""".trimIndent(),
        )

        val record = records.single()
        assertEquals("copilot", record.provider)
        assertEquals(UsageStatus.Ok, record.status)
        assertEquals("GitHub Copilot", record.displayName)
        assertEquals(2, record.windows.size)
        assertEquals(0.0, record.windows[0].percent, 0.001)
        assertNull(record.windows[0].resetAt)
        assertEquals(3.4, record.windows[1].percent, 0.001)
    }

    @Test
    fun parse_zaiHappyPath() {
        val records = parser.parse(
            """{"provider":"zai","status":"ok",
              "short_term":{"percent_remaining":100.0,"reset_at":"2026-05-27T10:31:58Z"},
              "long_term":{"percent_remaining":100.0,"reset_at":null},
              "block_reason":null,"error":null,"details":{}}""".trimIndent(),
        )

        val record = records.single()
        assertEquals("zai", record.provider)
        assertEquals(UsageStatus.Ok, record.status)
        assertEquals(2, record.windows.size)
        record.windows.forEach { assertEquals(0.0, it.percent, 0.001) }
    }

    @Test
    fun parse_unsupportedStatus_forGemini() {
        val records = parser.parse(
            """{"provider":"gemini","status":"unsupported",
              "short_term":null,"long_term":null,
              "block_reason":null,
              "error":"gemini does not expose a usage endpoint","details":{}}""".trimIndent(),
        )

        val record = records.single()
        assertEquals("gemini", record.provider)
        assertEquals(UsageStatus.Unsupported, record.status)
        assertTrue("expected windows to be empty when both ranges are null", record.windows.isEmpty())
        assertEquals("gemini does not expose a usage endpoint", record.lastError)
    }

    @Test
    fun parse_errorStatus_surfacesErrorField() {
        val records = parser.parse(
            """{"provider":"codex","status":"error",
              "short_term":null,"long_term":null,
              "block_reason":null,
              "error":"login required: run codex login","details":{}}""".trimIndent(),
        )

        val record = records.single()
        assertEquals(UsageStatus.Error, record.status)
        assertEquals("login required: run codex login", record.lastError)
        assertTrue(record.windows.isEmpty())
    }

    @Test
    fun parse_acceptsMultipleNdjsonLines() {
        val records = parser.parse(
            """
            {"provider":"codex","status":"ok","short_term":{"percent_remaining":50.0},"long_term":null,"block_reason":null,"error":null,"details":{}}
            {"provider":"claude","status":"limited","short_term":{"percent_remaining":0.0},"long_term":null,"block_reason":"weekly limit reached","error":null,"details":{}}
            """.trimIndent(),
        )

        assertEquals(2, records.size)
        assertEquals("codex", records[0].provider)
        assertEquals(UsageStatus.Ok, records[0].status)
        assertEquals("claude", records[1].provider)
        assertEquals(UsageStatus.Blocked, records[1].status)
        assertEquals("weekly limit reached", records[1].blockReason)
        assertTrue(records[1].isBlocked)
    }

    @Test
    fun parse_skipsBlankLines() {
        val records = parser.parse(
            """

            {"provider":"codex","status":"ok","short_term":{"percent_remaining":77.0},"long_term":null,"block_reason":null,"error":null,"details":{}}

            """.trimIndent(),
        )

        assertEquals(1, records.size)
    }

    @Test
    fun parse_emptyInput_returnsEmptyList() {
        assertEquals(emptyList<UsageProviderRecord>(), parser.parse(""))
        assertEquals(emptyList<UsageProviderRecord>(), parser.parse("   \n\n  "))
    }

    @Test
    fun parse_preservesUnknownStatus() {
        val record = parser.parse(
            """{"provider":"x","status":"maintenance","short_term":null,"long_term":null,"block_reason":null,"error":null,"details":{}}""",
        ).single()

        assertEquals(UsageStatus.Unknown, record.status)
        assertEquals("maintenance", record.rawStatus)
    }

    @Test
    fun parse_rejectsMalformedJson() {
        val error = assertThrows(UsageParseException::class.java) {
            parser.parse("""{"provider":"codex","status":"ok"""")
        }
        assertNotNull(error.message)
        assertTrue(error.message!!.contains("invalid usage JSON"))
    }

    @Test
    fun parse_rejectsNonObjectWindowField() {
        val error = assertThrows(UsageParseException::class.java) {
            parser.parse(
                """{"provider":"x","status":"ok","short_term":"not an object","long_term":null,"block_reason":null,"error":null,"details":{}}""",
            )
        }
        assertTrue(error.message!!.contains("short_term"))
    }

    @Test
    fun parse_rejectsMissingProvider() {
        assertThrows(UsageParseException::class.java) {
            parser.parse(
                """{"status":"ok","short_term":null,"long_term":null,"block_reason":null,"error":null,"details":{}}""",
            )
        }
    }
}
