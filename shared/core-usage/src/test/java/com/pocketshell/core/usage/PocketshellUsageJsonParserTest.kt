package com.pocketshell.core.usage

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketshellUsageJsonParserTest {

    private val parser = PocketshellUsageJsonParser()

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
    fun parse_codexFallsBackToDetailWindowsAndEpochResets() {
        val records = parser.parse(
            """
            {"provider":"codex","status":"ok",
             "short_term":{"percent_remaining":100.0,"reset_at":null},
             "long_term":{"percent_remaining":69.0,"reset_at":null},
             "block_reason":null,"error":null,
             "details":{"limit_reached":false,"windows":{
               "primary_window":{
                 "used_percent":12,
                 "limit_window_seconds":18000,
                 "reset_at":1780828285
               },
               "secondary_window":{
                 "used_percent":31,
                 "limit_window_seconds":604800,
                 "reset_at":1781137638
               }
             }}}
            """.trimIndent(),
        )

        val record = records.single()
        assertEquals(2, record.windows.size)
        assertEquals("5h", record.windows[0].name)
        assertEquals(12.0, record.windows[0].percent, 0.001)
        assertEquals(Instant.parse("2026-06-07T10:31:25Z"), record.windows[0].resetAt)
        assertEquals("7d", record.windows[1].name)
        assertEquals(31.0, record.windows[1].percent, 0.001)
        assertEquals(Instant.parse("2026-06-11T00:27:18Z"), record.windows[1].resetAt)
    }

    @Test
    fun parse_codexFallsBackToDetailResetAfterSeconds() {
        val parser = PocketshellUsageJsonParser(
            now = { Instant.parse("2026-06-08T10:00:00Z") },
        )
        val records = parser.parse(
            """
            {"provider":"codex","status":"ok",
             "short_term":{"percent_remaining":100.0,"reset_at":null},
             "long_term":{"percent_remaining":69.0,"reset_at":null},
             "block_reason":null,"error":null,
             "details":{"windows":{
               "primary_window":{
                 "used_percent":12,
                 "limit_window_seconds":18000,
                 "reset_after_seconds":7200
               },
               "secondary_window":{
                 "used_percent":31,
                 "limit_window_seconds":604800,
                 "reset_after_seconds":"604800"
               }
             }}}
            """.trimIndent(),
        )

        val record = records.single()
        assertEquals("5h", record.windows[0].name)
        assertEquals(12.0, record.windows[0].percent, 0.001)
        assertEquals(Instant.parse("2026-06-08T12:00:00Z"), record.windows[0].resetAt)
        assertEquals("7d", record.windows[1].name)
        assertEquals(31.0, record.windows[1].percent, 0.001)
        assertEquals(Instant.parse("2026-06-15T10:00:00Z"), record.windows[1].resetAt)
    }

    @Test
    fun parse_codexReadsResetFromDetailAliasesWhenTopLevelResetMissing() {
        val parser = PocketshellUsageJsonParser(
            now = { Instant.parse("2026-06-08T10:00:00Z") },
        )
        val records = parser.parse(
            """
            {"provider":"codex","status":"ok",
             "short_term":{"percent_remaining":100.0,"reset_at":null},
             "long_term":{"percent_remaining":100.0,"reset_at":null},
             "block_reason":null,"error":null,
             "details":{"windows":{
               "primary":{
                 "used_percent":65,
                 "limit_window_seconds":18000,
                 "next_reset_at":"2026-06-08T15:00:00Z"
               },
               "secondary":{
                 "used_percent":20,
                 "limit_window_seconds":604800,
                 "reset_after":7200
               }
             }}}
            """.trimIndent(),
        )

        val record = records.single()
        assertEquals("5h", record.windows[0].name)
        assertEquals(65.0, record.windows[0].percent, 0.001)
        assertEquals(Instant.parse("2026-06-08T15:00:00Z"), record.windows[0].resetAt)
        assertEquals("7d", record.windows[1].name)
        assertEquals(20.0, record.windows[1].percent, 0.001)
        assertEquals(Instant.parse("2026-06-08T12:00:00Z"), record.windows[1].resetAt)
    }

    @Test
    fun parse_topLevelResetAfterSecondsWhenResetAtMissing() {
        val parser = PocketshellUsageJsonParser(
            now = { Instant.parse("2026-06-08T10:00:00Z") },
        )
        val record = parser.parse(
            """{"provider":"codex","status":"ok",
              "short_term":{"percent_remaining":35.0,"reset_at":null,"reset_after_seconds":3600,"window":"5h"},
              "long_term":null,
              "block_reason":null,"error":null,"details":{}}""".trimIndent(),
        ).single()

        assertEquals(65.0, record.windows.single().percent, 0.001)
        assertEquals(Instant.parse("2026-06-08T11:00:00Z"), record.windows.single().resetAt)
    }

    @Test
    fun parse_codexPrefersDetailWindowPeriodAndResetWhenTopLevelIsGeneric() {
        val records = parser.parse(
            """
            {"provider":"codex","status":"ok",
             "short_term":{"percent_remaining":100.0,"reset_at":"2026-06-07T00:00:00Z","window":"primary_window"},
             "long_term":{"percent_remaining":69.0,"reset_at":"2026-06-10T00:00:00Z","window":"secondary_window"},
             "block_reason":null,"error":null,
             "details":{"windows":{
               "primary_window":{
                 "used_percent":12,
                 "limit_window_seconds":18000,
                 "reset_at":1780828285
               },
               "secondary_window":{
                 "used_percent":31,
                 "limit_window_seconds":604800,
                 "reset_at":1781137638
               }
             }}}
            """.trimIndent(),
        )

        val record = records.single()
        assertEquals("5h", record.windows[0].name)
        assertEquals(Instant.parse("2026-06-07T10:31:25Z"), record.windows[0].resetAt)
        assertEquals("7d", record.windows[1].name)
        assertEquals(Instant.parse("2026-06-11T00:27:18Z"), record.windows[1].resetAt)
    }

    @Test
    fun parse_openAiCompatibleFallsBackToDetailWindowsAndPeriodLabels() {
        val records = parser.parse(
            """
            {"provider":"openai","status":"ok",
             "short_term":{"percent_remaining":100.0,"reset_at":null},
             "long_term":{"percent_remaining":35.0,"reset_at":null},
             "block_reason":null,"error":null,
             "details":{"windows":{
               "primary_window":{
                 "used_percent":22,
                 "limit_window_seconds":"18000",
                 "reset_at":"2026-06-08T02:19:59Z"
               },
               "secondary_window":{
                 "used_percent":65,
                 "limit_window_seconds":604800,
                 "reset_at":1781137637
               }
             }}}
            """.trimIndent(),
        )

        val record = records.single()
        assertEquals("openai", record.provider)
        assertEquals(2, record.windows.size)
        assertEquals("5h", record.windows[0].name)
        assertEquals(22.0, record.windows[0].percent, 0.001)
        assertEquals(Instant.parse("2026-06-08T02:19:59Z"), record.windows[0].resetAt)
        assertEquals("7d", record.windows[1].name)
        assertEquals(65.0, record.windows[1].percent, 0.001)
        assertEquals(Instant.parse("2026-06-11T00:27:17Z"), record.windows[1].resetAt)
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
    fun parse_claudeUnauthorizedMapsToUsageDataUnavailable() {
        val record = parser.parse(
            """{"provider":"claude","status":"error",
              "short_term":{"percent_remaining":null,"reset_at":null},
              "long_term":{"percent_remaining":null,"reset_at":null},
              "block_reason":null,
              "error":"HTTP Error 401: Unauthorized","details":{}}""".trimIndent(),
        ).single()

        assertEquals(UsageStatus.Error, record.status)
        assertEquals(
            "Claude login needed on this host. " +
                "Open Claude Code on the host and sign in, then refresh usage.",
            record.lastError,
        )
        assertTrue(record.lastError?.contains("claude " + "/login") == false)
        assertTrue(record.lastError?.contains("authentication " + "failed", ignoreCase = true) == false)
        assertTrue(record.lastError?.contains("HTTP Error 401", ignoreCase = true) == false)
    }

    @Test
    fun parse_claudeWindowsCarryConcrete5hAnd7dSpans() {
        // #800: Claude Code's short/long windows are the same 5h/7d spans as
        // Codex. The record carries the generic short_term/long_term keys with
        // no explicit `window`, so the parser seeds the canonical span name so
        // the UI's windowLabel renders "5h window" / "7d window" — data-driven,
        // no provider check in the Compose layer.
        val record = parser.parse(
            """{"provider":"claude","status":"ok",
              "short_term":{"percent_remaining":59.0,"reset_at":"2026-05-24T14:30:00Z","window":null},
              "long_term":{"percent_remaining":15.0,"reset_at":"2026-05-28T14:59:59Z","window":null},
              "block_reason":null,"error":null,"details":{}}""".trimIndent(),
        ).single()

        assertEquals("claude", record.provider)
        assertEquals(2, record.windows.size)
        assertEquals("5h", record.windows[0].name)
        assertEquals(41.0, record.windows[0].percent, 0.001)
        assertEquals("7d", record.windows[1].name)
        assertEquals(85.0, record.windows[1].percent, 0.001)
    }

    @Test
    fun parse_copilotLongTermIsMonthlyNot7d() {
        // #800: Copilot's long-term quota is a monthly cycle. It must keep its
        // real cadence label, NOT be framed as a 7d window. The short-term
        // window stays generic (fixed 100% bucket with no clean span).
        val record = parser.parse(
            """{"provider":"copilot","status":"ok",
              "short_term":{"percent_remaining":100.0,"reset_at":null},
              "long_term":{"percent_remaining":96.6,"reset_at":"2026-07-01T00:00:00Z"},
              "block_reason":null,"error":null,"details":{}}""".trimIndent(),
        ).single()

        assertEquals("copilot", record.provider)
        assertEquals(2, record.windows.size)
        assertEquals("short_term", record.windows[0].name)
        assertEquals("monthly", record.windows[1].name)
        assertTrue(
            "copilot long-term must not be framed as a 7d window",
            record.windows[1].name != "7d",
        )
    }

    @Test
    fun parse_zaiKeepsGenericWindowNames() {
        // #800 regression guard: providers with unknown spans must NOT get
        // forced into 5h/7d — they keep the generic names so windowLabel
        // humanizes them (Short term / Long term), preserving #522.
        val record = parser.parse(
            """{"provider":"zai","status":"ok",
              "short_term":{"percent_remaining":100.0,"reset_at":"2026-05-27T10:31:58Z"},
              "long_term":{"percent_remaining":100.0,"reset_at":null},
              "block_reason":null,"error":null,"details":{}}""".trimIndent(),
        ).single()

        assertEquals("short_term", record.windows[0].name)
        assertEquals("long_term", record.windows[1].name)
    }

    @Test
    fun parse_claudeStaleAuthTelemetryMessageMapsToUsageDataUnavailable() {
        val staleError = "Claude Code authentication " + "failed on this host. Run `claude " +
            "/login` in the host shell, then refresh usage."
        val record = parser.parse(
            """{"provider":"claude","status":"error",
              "short_term":{"percent_remaining":null,"reset_at":null},
              "long_term":{"percent_remaining":null,"reset_at":null},
              "block_reason":null,
              "error":${org.json.JSONObject.quote(staleError)},"details":{}}""".trimIndent(),
        ).single()

        assertEquals(UsageStatus.Error, record.status)
        assertEquals(
            "Claude login needed on this host. " +
                "Open Claude Code on the host and sign in, then refresh usage.",
            record.lastError,
        )
        assertTrue(record.lastError?.contains("claude " + "/login") == false)
        assertTrue(record.lastError?.contains("authentication " + "failed", ignoreCase = true) == false)
        assertTrue(record.lastError?.contains("provider " + "blocked", ignoreCase = true) == false)
    }

    @Test
    fun parse_codexExhaustedStatus_mapsToBlockedExceededRecord() {
        val records = parser.parse(
            """{"provider":"codex","status":"quota-exhausted",
              "short_term":null,"long_term":null,
              "block_reason":"Codex quota exhausted",
              "error":null,"details":{"message":"quota exhausted"}}""".trimIndent(),
        )

        val record = records.single()
        assertEquals("codex", record.provider)
        assertEquals(UsageStatus.Blocked, record.status)
        assertEquals("quota-exhausted", record.rawStatus)
        assertEquals("Codex quota exhausted", record.blockReason)
        assertTrue(record.windows.isEmpty())
        assertTrue(record.isBlocked)
        assertEquals(UsageThresholdState.Exceeded, record.thresholdState())
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

    // -- issue #1223: per-record resilience on the exit-0 success path --------
    //
    // An old/mismatched host CLI (or a custom usageCommandOverride wrapper) can
    // emit one healthy provider record plus one drifted/malformed record, or
    // prepend a non-JSON MOTD/deprecation line. Before #1223 the FIRST bad
    // record threw and discarded ALL providers. Parse must now skip the bad
    // record and return the ones that parsed; only zero parsed records is a
    // whole-panel failure (mirrors the exit != 0 graceful path #847 class).

    @Test
    fun parse_skipsDriftedRecordAndKeepsHealthyProvider() {
        // Healthy record is FIRST so we prove an already-parsed provider is not
        // discarded when a LATER record drifts (missing `provider`).
        val records = parser.parse(
            """
            {"provider":"codex","status":"ok","short_term":{"percent_remaining":77.0},"long_term":null,"block_reason":null,"error":null,"details":{}}
            {"status":"ok","short_term":"not-an-object","long_term":null,"block_reason":null,"error":null,"details":{}}
            """.trimIndent(),
        )

        assertEquals(1, records.size)
        assertEquals("codex", records.single().provider)
        assertEquals(UsageStatus.Ok, records.single().status)
    }

    @Test
    fun parse_skipsNonJsonPreambleLineAndRendersAllValidProviders() {
        // A wrapper/MOTD prints a non-JSON preamble line before the NDJSON. All
        // valid provider records after it must still render.
        val records = parser.parse(
            """
            WARNING: pocketshell 0.3.1 is deprecated, upgrade with: uv tool upgrade pocketshell
            {"provider":"codex","status":"ok","short_term":{"percent_remaining":50.0},"long_term":null,"block_reason":null,"error":null,"details":{}}
            {"provider":"claude","status":"ok","short_term":{"percent_remaining":41.0},"long_term":null,"block_reason":null,"error":null,"details":{}}
            """.trimIndent(),
        )

        assertEquals(2, records.size)
        assertEquals("codex", records[0].provider)
        assertEquals("claude", records[1].provider)
    }

    @Test
    fun parse_throwsWhenZeroRecordsParse() {
        // Non-empty input where NOTHING parses must still surface a visible
        // failure (no silent empty-success). The message aggregates the
        // per-record diagnostics.
        val error = assertThrows(UsageParseException::class.java) {
            parser.parse(
                """
                this is not json
                also not json {broken
                """.trimIndent(),
            )
        }
        assertNotNull(error.message)
        assertTrue(error.message!!.contains("invalid usage JSON"))
    }

    @Test
    fun parse_skipsMalformedJsonRecordAmongValidOnes() {
        // A record with broken JSON syntax sandwiched between two valid ones is
        // skipped; the two valid providers still render.
        val records = parser.parse(
            """
            {"provider":"codex","status":"ok","short_term":{"percent_remaining":77.0},"long_term":null,"block_reason":null,"error":null,"details":{}}
            {"provider":"claude","status":"ok","short_term":{"percent_remaining":not-a-number}}
            {"provider":"copilot","status":"ok","short_term":{"percent_remaining":90.0},"long_term":null,"block_reason":null,"error":null,"details":{}}
            """.trimIndent(),
        )

        assertEquals(listOf("codex", "copilot"), records.map { it.provider })
    }
}
