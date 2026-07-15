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
    fun parse_codexNo5hWindow_rendersWeeklyOnly_noPhantom5h_noGhostRow() {
        // #1564 regression: Codex temporarily removed its 5h window, so quse
        // 0.0.11 now emits Codex's `primary_window` (the WEEKLY 604800s span)
        // in `short_term` labeled from its real `limit_window_seconds` → "7d"
        // (NOT the old positional "5h"), and the DROPPED window as a null
        // placeholder (`long_term.percent_remaining == null`). The maintainer's
        // v0.4.33 symptom was a "5h window · 53% · resets in 5 days" (weekly
        // data under a 5h label) plus a "7d window · 0% · unavailable" GHOST.
        //
        // This is the app-side load-bearing assertion for the acceptance
        // criteria: the parser must render the WEEKLY window labeled "7d" (no
        // phantom 5h) and OMIT the null-percent dropped window (no ghost row).
        // If the parser stopped omitting null-percent windows this test goes
        // red (a 2nd ghost window would appear).
        val codexNo5hNdjson =
            """{"provider":"codex","status":"ok","short_term":{"percent_remaining":69.0,"reset_at":"2026-07-21T20:37:32Z","window":"7d"},"long_term":{"percent_remaining":null,"reset_at":null,"window":null},"error":null,"details":{}}"""
        val record = parser.parse(codexNo5hNdjson).single()

        // Exactly ONE renderable window — the dropped 5h is omitted (no ghost).
        assertEquals(1, record.windows.size)
        val weekly = record.windows.single()
        // The weekly window is labeled "7d", NOT the phantom "5h".
        assertEquals("7d", weekly.name)
        assertTrue("Codex weekly window must not be mislabeled 5h", weekly.name != "5h")
        assertEquals(31.0, weekly.percent, 0.001) // 100 - 69
        assertEquals(Instant.parse("2026-07-21T20:37:32Z"), weekly.resetAt)
        // No ghost "0% / unavailable" (null-reset) row survived: the only
        // window has a real reset time.
        assertTrue(
            "the dropped Codex window must not render as a ghost row",
            record.windows.all { it.resetAt != null },
        )
    }

    @Test
    fun parse_quse0011FullDocument_codexFixedButOtherCardsUnchanged() {
        // #1564 class coverage: the quse 0.0.11 Codex window fix must NOT
        // regress the other provider cards. Feeds all four providers exactly as
        // `pocketshell usage --json` flattens quse 0.0.11 and asserts Claude
        // (5h + 7d), Copilot (monthly), and zai (5h + weekly) are unchanged
        // while Codex renders weekly-only.
        val quse0011Ndjson = listOf(
            """{"provider":"claude","status":"ok","short_term":{"percent_remaining":82.0,"reset_at":"2026-07-15T11:39:59Z","window":"5h"},"long_term":{"percent_remaining":5.0,"reset_at":"2026-07-16T14:59:59Z","window":"7d"},"error":null,"details":{}}""",
            """{"provider":"codex","status":"ok","short_term":{"percent_remaining":69.0,"reset_at":"2026-07-21T20:37:32Z","window":"7d"},"long_term":{"percent_remaining":null,"reset_at":null,"window":null},"error":null,"details":{}}""",
            """{"provider":"copilot","status":"ok","short_term":{"percent_remaining":100.0,"reset_at":null,"window":null},"long_term":{"percent_remaining":97.1,"reset_at":"2026-08-01T00:00:00Z","window":"monthly"},"error":null,"details":{}}""",
            """{"provider":"zai","status":"ok","short_term":{"percent_remaining":99.0,"reset_at":null,"window":"5h"},"long_term":{"percent_remaining":75.0,"reset_at":"2026-07-18T14:04:58Z","window":"weekly"},"error":null,"details":{}}""",
        ).joinToString("\n")
        val records = parser.parse(quse0011Ndjson)
        val byProvider = records.associateBy { it.provider }

        // Claude: 5h + 7d, both real resets — unchanged.
        val claude = byProvider.getValue("claude")
        assertEquals(2, claude.windows.size)
        assertEquals("5h", claude.windows[0].name)
        assertEquals("7d", claude.windows[1].name)

        // Codex: weekly-only, labeled "7d", no ghost.
        val codex = byProvider.getValue("codex")
        assertEquals(1, codex.windows.size)
        assertEquals("7d", codex.windows.single().name)

        // Copilot: monthly window unchanged (short_term null window omitted-or-generic).
        val copilot = byProvider.getValue("copilot")
        assertEquals("monthly", copilot.windows.last().name)

        // zai: 5h + weekly unchanged.
        val zai = byProvider.getValue("zai")
        assertEquals("5h", zai.windows[0].name)
        assertEquals("weekly", zai.windows[1].name)
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
