package com.pocketshell.core.usage

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strict-schema parser tests (issue #1318). `pocketshell usage --json` emits
 * per-provider NDJSON flattened from quse v0.0.9's provider-keyed document;
 * quse owns the unified schema, so each record carries `status`,
 * `short_term`/`long_term` `{percent_remaining, reset_at, window}`, and
 * `error` directly. The window label comes STRAIGHT from `*.window`. The
 * parser is fail-loud: any schema drift throws (no details-window aliasing, no
 * per-record skip-resilience). The app ignores `details`.
 */
class PocketshellUsageJsonParserTest {

    private val parser = PocketshellUsageJsonParser()

    /** The four provider records as `pocketshell usage --json` emits them,
     * flattened from the real quse-0.0.9 output (window + ISO reset_at). */
    private val fourProviderNdjson = listOf(
        """{"provider":"claude","status":"ok","short_term":{"percent_remaining":91.0,"reset_at":"2026-07-07T23:19:59Z","window":"5h"},"long_term":{"percent_remaining":30.0,"reset_at":"2026-07-09T14:59:59Z","window":"7d"},"error":null,"details":{"anything":true}}""",
        """{"provider":"codex","status":"ok","short_term":{"percent_remaining":100.0,"reset_at":"2026-07-07T23:57:08Z","window":"5h"},"long_term":{"percent_remaining":2.0,"reset_at":"2026-07-11T06:23:55Z","window":"7d"},"error":null,"details":{}}""",
        """{"provider":"copilot","status":"ok","short_term":{"percent_remaining":100.0,"reset_at":null,"window":null},"long_term":{"percent_remaining":97.1,"reset_at":"2026-08-01T00:00:00Z","window":"monthly"},"error":null,"details":{}}""",
        """{"provider":"zai","status":"ok","short_term":{"percent_remaining":58.0,"reset_at":null,"window":"5h"},"long_term":{"percent_remaining":56.0,"reset_at":"2026-07-11T14:04:58Z","window":"weekly"},"error":null,"details":{}}""",
    ).joinToString("\n")

    @Test
    fun parse_allFourProviders_renderWithWindowsLabelsAndResets() {
        val records = parser.parse(fourProviderNdjson)
        assertEquals(listOf("claude", "codex", "copilot", "zai"), records.map { it.provider })
        records.forEach { assertEquals(UsageStatus.Ok, it.status) }

        val claude = records[0]
        assertEquals(2, claude.windows.size)
        assertEquals("5h", claude.windows[0].name)
        assertEquals(9.0, claude.windows[0].percent, 0.001) // 100 - 91
        assertEquals(Instant.parse("2026-07-07T23:19:59Z"), claude.windows[0].resetAt)
        assertEquals("7d", claude.windows[1].name)
        assertEquals(70.0, claude.windows[1].percent, 0.001) // 100 - 30
        assertEquals(Instant.parse("2026-07-09T14:59:59Z"), claude.windows[1].resetAt)

        val codex = records[1]
        assertEquals("5h", codex.windows[0].name)
        assertEquals("7d", codex.windows[1].name)
        assertEquals(98.0, codex.windows[1].percent, 0.001) // 100 - 2

        val copilot = records[2]
        // short_term window is null -> generic key name; long_term is monthly.
        assertEquals("short_term", copilot.windows[0].name)
        assertNull(copilot.windows[0].resetAt)
        assertEquals("monthly", copilot.windows[1].name)
        assertTrue("copilot long-term must not be framed 7d", copilot.windows[1].name != "7d")
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), copilot.windows[1].resetAt)

        val zai = records[3]
        assertEquals("5h", zai.windows[0].name)
        assertEquals("weekly", zai.windows[1].name)
        assertEquals(Instant.parse("2026-07-11T14:04:58Z"), zai.windows[1].resetAt)
    }

    @Test
    fun parse_windowLabelComesStraightFromWindowField() {
        val record = parser.parse(
            """{"provider":"codex","status":"ok","short_term":{"percent_remaining":77.0,"reset_at":"2026-05-24T15:53:01Z","window":"5h"},"long_term":{"percent_remaining":88.0,"reset_at":"2026-05-30T20:33:54Z","window":"7d"},"error":null,"details":{}}""",
        ).single()

        val shortTerm = record.windows[0]
        assertEquals("5h", shortTerm.name)
        assertEquals(23.0, shortTerm.used, 0.001)
        assertEquals(100.0, shortTerm.limit, 0.001)
        assertEquals("percent", shortTerm.unit)
        assertEquals(Instant.parse("2026-05-24T15:53:01Z"), shortTerm.resetAt)
        assertEquals("7d", record.windows[1].name)
    }

    @Test
    fun parse_nullWindow_fallsBackToGenericKeyName() {
        val record = parser.parse(
            """{"provider":"zai","status":"ok","short_term":{"percent_remaining":100.0,"reset_at":null,"window":null},"long_term":{"percent_remaining":100.0,"reset_at":null,"window":null},"error":null,"details":{}}""",
        ).single()

        assertEquals("short_term", record.windows[0].name)
        assertEquals("long_term", record.windows[1].name)
    }

    @Test
    fun parse_displayNames() {
        val records = parser.parse(fourProviderNdjson)
        assertEquals("Claude Code", records[0].displayName)
        assertEquals("Codex", records[1].displayName)
        assertEquals("GitHub Copilot", records[2].displayName)
    }

    // -- genuine runtime states (kept) ---------------------------------------

    @Test
    fun parse_unsupportedStatus_forGemini() {
        val record = parser.parse(
            """{"provider":"gemini","status":"unsupported","short_term":null,"long_term":null,"error":"gemini does not expose a usage endpoint","details":{}}""",
        ).single()

        assertEquals(UsageStatus.Unsupported, record.status)
        assertTrue(record.windows.isEmpty())
        assertEquals("gemini does not expose a usage endpoint", record.lastError)
    }

    @Test
    fun parse_errorStatus_surfacesErrorField() {
        val record = parser.parse(
            """{"provider":"codex","status":"error","short_term":null,"long_term":null,"error":"login required: run codex login","details":{}}""",
        ).single()

        assertEquals(UsageStatus.Error, record.status)
        assertEquals("login required: run codex login", record.lastError)
        assertTrue(record.windows.isEmpty())
    }

    @Test
    fun parse_claudeUnauthorizedMapsToActionableError() {
        val record = parser.parse(
            """{"provider":"claude","status":"error","short_term":{"percent_remaining":null,"reset_at":null},"long_term":{"percent_remaining":null,"reset_at":null},"error":"HTTP Error 401: Unauthorized","details":{}}""",
        ).single()

        assertEquals(UsageStatus.Error, record.status)
        assertEquals(
            "Claude login needed on this host. " +
                "Open Claude Code on the host and sign in, then refresh usage.",
            record.lastError,
        )
        assertTrue(record.lastError?.contains("HTTP Error 401", ignoreCase = true) == false)
        // percent_remaining null on both windows -> no renderable windows.
        assertTrue(record.windows.isEmpty())
    }

    @Test
    fun parse_blockedStatus_mapsToBlockedExceededRecord() {
        val record = parser.parse(
            """{"provider":"codex","status":"quota-exhausted","short_term":null,"long_term":null,"block_reason":"Codex quota exhausted","error":null,"details":{"message":"quota exhausted"}}""",
        ).single()

        assertEquals(UsageStatus.Blocked, record.status)
        assertEquals("quota-exhausted", record.rawStatus)
        assertEquals("Codex quota exhausted", record.blockReason)
        assertTrue(record.isBlocked)
        assertEquals(UsageThresholdState.Exceeded, record.thresholdState())
    }

    @Test
    fun parse_multipleNdjsonLines() {
        val records = parser.parse(
            """
            {"provider":"codex","status":"ok","short_term":{"percent_remaining":50.0},"long_term":null,"error":null,"details":{}}
            {"provider":"claude","status":"limited","short_term":{"percent_remaining":0.0},"long_term":null,"block_reason":"weekly limit reached","error":null,"details":{}}
            """.trimIndent(),
        )
        assertEquals(2, records.size)
        assertEquals(UsageStatus.Ok, records[0].status)
        assertEquals(UsageStatus.Blocked, records[1].status)
        assertEquals("weekly limit reached", records[1].blockReason)
    }

    @Test
    fun parse_skipsBlankLines() {
        val records = parser.parse(
            "\n\n" +
                """{"provider":"codex","status":"ok","short_term":{"percent_remaining":77.0},"long_term":null,"error":null,"details":{}}""" +
                "\n\n",
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
            """{"provider":"x","status":"maintenance","short_term":null,"long_term":null,"error":null,"details":{}}""",
        ).single()
        assertEquals(UsageStatus.Unknown, record.status)
        assertEquals("maintenance", record.rawStatus)
    }

    // -- fail-loud on schema mismatch (issue #1318: no skip-resilience) ------

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
                """{"provider":"x","status":"ok","short_term":"not an object","long_term":null,"error":null,"details":{}}""",
            )
        }
        assertTrue(error.message!!.contains("short_term"))
    }

    @Test
    fun parse_rejectsMissingProvider() {
        assertThrows(UsageParseException::class.java) {
            parser.parse(
                """{"status":"ok","short_term":null,"long_term":null,"error":null,"details":{}}""",
            )
        }
    }

    @Test
    fun parse_throwsOnOneBadLineAmongValid_noSkipResilience() {
        // Issue #1318 hard-cut: a drifted/malformed record among healthy ones
        // must FAIL THE WHOLE PANEL (fail-loud), NOT be silently skipped. This
        // is the deleted #1223 skip-resilience — the app now expects quse's
        // exact schema and throws on any drift.
        val error = assertThrows(UsageParseException::class.java) {
            parser.parse(
                """
                {"provider":"codex","status":"ok","short_term":{"percent_remaining":77.0},"long_term":null,"error":null,"details":{}}
                {"status":"ok","short_term":"not-an-object","long_term":null,"error":null,"details":{}}
                """.trimIndent(),
            )
        }
        assertNotNull(error.message)
    }

    @Test
    fun parse_throwsOnNonJsonPreambleLine_noSkipResilience() {
        // A non-JSON MOTD/deprecation preamble line is a schema violation now:
        // the parser throws instead of quietly skipping it.
        assertThrows(UsageParseException::class.java) {
            parser.parse(
                """
                WARNING: pocketshell 0.3.1 is deprecated
                {"provider":"codex","status":"ok","short_term":{"percent_remaining":50.0},"long_term":null,"error":null,"details":{}}
                """.trimIndent(),
            )
        }
    }

    @Test
    fun parse_rejectsMalformedResetAt() {
        val error = assertThrows(UsageParseException::class.java) {
            parser.parse(
                """{"provider":"codex","status":"ok","short_term":{"percent_remaining":50.0,"reset_at":"not-a-date"},"long_term":null,"error":null,"details":{}}""",
            )
        }
        assertTrue(error.message!!.contains("reset_at"))
    }
}
