package com.pocketshell.core.usage

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class HeruUsageJsonParserTest {

    private val parser = HeruUsageJsonParser()

    @Test
    fun parse_acceptsDocumentedArrayShape() {
        val records = parser.parse(
            """
            [
              {
                "provider": "claude",
                "status": "ok",
                "windows": [
                  {"name": "5h", "used": 45.2, "limit": 100, "unit": "percent",
                   "reset_at": "2026-05-21T14:23:00Z"},
                  {"name": "7d", "used": 18.0, "limit": 100, "unit": "percent",
                   "reset_at": "2026-05-28T09:00:00Z"}
                ]
              },
              {
                "provider": "codex",
                "status": "blocked",
                "block_reason": "weekly limit reached",
                "windows": [
                  {"name": "weekly", "used": 100, "limit": 100, "unit": "percent",
                   "reset_at": "2026-05-26T00:00:00Z"}
                ]
              }
            ]
            """.trimIndent(),
        )

        assertEquals(2, records.size)
        assertEquals("claude", records[0].provider)
        assertEquals(UsageStatus.Ok, records[0].status)
        assertEquals(45.2, records[0].windows[0].percent, 0.001)
        assertEquals(Instant.parse("2026-05-21T14:23:00Z"), records[0].windows[0].resetAt)

        assertEquals("codex", records[1].provider)
        assertEquals(UsageStatus.Blocked, records[1].status)
        assertEquals("weekly limit reached", records[1].blockReason)
        assertTrue(records[1].isBlocked)
    }

    @Test
    fun parse_acceptsSingleRecordShape() {
        val records = parser.parse(
            """
            {
              "provider": "opencode",
              "status": "ok",
              "windows": [
                {"name": "monthly", "used": "12", "limit": "100", "unit": "percent"}
              ]
            }
            """.trimIndent(),
        )

        assertEquals(1, records.size)
        assertEquals("OpenCode", records.single().displayName)
        assertNull(records.single().windows.single().resetAt)
    }

    @Test
    fun parse_computesPercentForNonPercentUnits() {
        val record = parser.parse(
            """
            {
              "provider": "tokens",
              "status": "ok",
              "windows": [
                {"name": "daily", "used": 250, "limit": 1000, "unit": "tokens"}
              ]
            }
            """.trimIndent(),
        ).single()

        assertEquals(25.0, record.windows.single().percent, 0.001)
        assertEquals(record.windows.single(), record.mostConstrainedWindow)
    }

    @Test
    fun parse_preservesUnknownStatus() {
        val record = parser.parse("""{"provider":"x","status":"maintenance","windows":[]}""").single()

        assertEquals(UsageStatus.Unknown, record.status)
        assertEquals("maintenance", record.rawStatus)
    }

    @Test
    fun parse_rejectsMalformedWindows() {
        assertThrows(UsageParseException::class.java) {
            parser.parse("""{"provider":"x","status":"ok","windows":[{"name":"daily","used":1}]}""")
        }
    }

    @Test
    fun parse_rejectsPresentNonArrayWindowsField() {
        val error = assertThrows(UsageParseException::class.java) {
            parser.parse("""{"provider":"x","status":"ok","windows":{}}""")
        }

        assertTrue(error.message!!.contains("not an array"))
    }
}
