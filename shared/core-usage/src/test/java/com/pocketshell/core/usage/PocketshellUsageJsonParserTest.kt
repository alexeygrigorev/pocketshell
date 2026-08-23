package com.pocketshell.core.usage

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Strict-schema parser tests (issue #1318). `pocketshell usage --json` emits
 * per-provider NDJSON flattened from quse's provider-keyed document. The
 * published quse 0.0.14 wheel is legacy `short_term` / `long_term`; the host
 * producer translates it before this parser sees canonical top-level
 * `windows`. A separate canonical producer contract may include providers such
 * as OpenCode Go. The key IS the window label. Non-applicable null-percent
 * spans are non-renderable and omitted; malformed entries still fail loudly.
 * The parser is fail-loud for schema drift (no details-window aliasing, no
 * per-record skip-resilience). The app ignores `details`.
 */
class PocketshellUsageJsonParserTest {

    private val parser = PocketshellUsageJsonParser()

    /**
     * Published quse's null-placeholder spans are handled at the host
     * producer boundary and are not emitted on the canonical app wire.
     */
    private val nullSpans =
        """"windows":{}"""

    /** Four provider records in the real 0.0.14 wire shape, as `pocketshell
     * usage --json` emits them (key-is-label + ISO reset_at + rolling on 5h). */
    private val fourProviderNdjson = listOf(
        """{"provider":"claude","status":"ok","windows":{"5h":{"percent_remaining":91.0,"reset_at":"2026-07-07T23:19:59Z","rolling":false},"7d":{"percent_remaining":30.0,"reset_at":"2026-07-09T14:59:59Z"}},"error":null,"details":{"anything":true}}""",
        """{"provider":"codex","status":"ok","windows":{"5h":{"percent_remaining":100.0,"reset_at":"2026-07-07T23:57:08Z","rolling":false},"7d":{"percent_remaining":2.0,"reset_at":"2026-07-11T06:23:55Z"}},"error":null,"details":{}}""",
        """{"provider":"copilot","status":"ok","windows":{"monthly":{"percent_remaining":97.1,"reset_at":"2026-08-01T00:00:00Z"}},"error":null,"details":{}}""",
        """{"provider":"zai","status":"ok","windows":{"5h":{"percent_remaining":58.0,"reset_at":null,"rolling":true},"7d":{"percent_remaining":56.0,"reset_at":"2026-07-11T14:04:58Z"}},"error":null,"details":{}}""",
    ).joinToString("\n")

    @Test
    fun parse_allFourProviders_renderWithWindowsLabelsAndResets() {
        val records = parser.parse(fourProviderNdjson)
        assertEquals(listOf("claude", "codex", "copilot", "zai"), records.map { it.provider })
        records.forEach { assertEquals(UsageStatus.Ok, it.status) }

        val claude = records[0]
        // The published fixture reaches this parser with only renderable
        // windows; 5h + 7d are labeled from the canonical keys.
        assertEquals(listOf("5h", "7d"), claude.windows.map { it.name }.sorted())
        assertEquals(9.0, claude.windows.first { it.name == "5h" }.percent, 0.001) // 100 - 91
        assertEquals(Instant.parse("2026-07-07T23:19:59Z"), claude.windows.first { it.name == "5h" }.resetAt)
        assertEquals(70.0, claude.windows.first { it.name == "7d" }.percent, 0.001) // 100 - 30
        assertEquals(Instant.parse("2026-07-09T14:59:59Z"), claude.windows.first { it.name == "7d" }.resetAt)

        val codex = records[1]
        assertEquals(setOf("5h", "7d"), codex.windows.map { it.name }.toSet())
        assertEquals(98.0, codex.windows.first { it.name == "7d" }.percent, 0.001) // 100 - 2

        val copilot = records[2]
        // Null 5h/7d spans are omitted; only the real monthly span renders.
        assertEquals("monthly", copilot.windows.single().name)
        assertTrue(
            "null-percent spans must not render as ghost rows",
            copilot.windows.none { it.name == "5h" || it.name == "7d" },
        )
        assertEquals(2.9, copilot.windows.single().percent, 0.001) // 100 - 97.1
        assertEquals(Instant.parse("2026-08-01T00:00:00Z"), copilot.windows.single().resetAt)

        val zai = records[3]
        // zai's weekly cadence reaches the app under the producer's canonical
        // "7d" key.
        assertEquals(setOf("5h", "7d"), zai.windows.map { it.name }.toSet())
        assertEquals(44.0, zai.windows.first { it.name == "7d" }.percent, 0.001) // 100 - 56
        assertEquals(Instant.parse("2026-07-11T14:04:58Z"), zai.windows.first { it.name == "7d" }.resetAt)
    }

    @Test
    fun parse_codexNo5hWindow_rendersWeeklyOnly_noPhantom5h_noGhostRow() {
        // #1564 regression, 0.0.14 shape (issue #2274): Codex temporarily
        // removed its 5h window, so its weekly span renders under the unified
        // "7d" key. The host producer omits the null-percent source span.
        // The maintainer's v0.4.33 symptom was a "5h window · 53% · resets in
        // 5 days" (weekly data under a 5h label) plus a "7d window · 0% ·
        // unavailable" GHOST.
        //
        // This is the app-side load-bearing assertion: the parser must render
        // the WEEKLY window labeled "7d" (no phantom 5h), with no malformed
        // null-percent canonical entry present.
        val codexNo5hNdjson =
            """{"provider":"codex","status":"ok","windows":{"7d":{"percent_remaining":69.0,"reset_at":"2026-07-21T20:37:32Z"}},"error":null,"details":{}}"""
        val record = parser.parse(codexNo5hNdjson).single()

        // Exactly ONE renderable window — the dropped 5h is omitted (no ghost).
        assertEquals(1, record.windows.size)
        val weekly = record.windows.single()
        // The weekly window is labeled "7d", NOT the phantom "5h".
        assertEquals("7d", weekly.name)
        assertTrue("Codex weekly window must not be mislabeled 5h", weekly.name != "5h")
        assertEquals(31.0, weekly.percent, 0.001) // 100 - 69
        assertEquals(Instant.parse("2026-07-21T20:37:32Z"), weekly.resetAt)
        // No ghost "0% / unavailable" row survived: the only window has a
        // real reset time.
        assertTrue(
            "the dropped Codex window must not render as a ghost row",
            record.windows.all { it.resetAt != null },
        )
    }

    @Test
    fun parse_displayNames() {
        val records = parser.parse(fourProviderNdjson)
        assertEquals("Claude Code", records[0].displayName)
        assertEquals("Codex", records[1].displayName)
        assertEquals("GitHub Copilot", records[2].displayName)
    }

    @Test
    fun parse_weeklyOnlyGrok_rendersUnified7dWindow_displayNameIsGrokBuild() {
        // #2195 live quse shape (0.0.14 form, issue #2274): only the weekly
        // span carries a value, under the unified "7d" key; the host producer
        // omits the null-percent 5h/monthly placeholders. used =
        // 100 - percent_remaining.
        // displayName MUST be "Grok Build" — generic title-case "Grok" is a
        // G6 miss (reverting the explicit mapping reddens this).
        val record = parser.parse(
            """{"provider":"grok","status":"ok","windows":{"7d":{"percent_remaining":95.0,"reset_at":"2026-08-25T00:08:17Z"}},"error":null,"details":{"subscription":"SuperGrokPlus"}}""",
        ).single()

        assertEquals("grok", record.provider)
        assertEquals("Grok Build", record.displayName)
        assertTrue(
            "generic title-case 'Grok' is not the product name",
            record.displayName != "Grok",
        )
        assertEquals(1, record.windows.size)
        val weekly = record.windows.single()
        assertEquals("7d", weekly.name)
        assertEquals(5.0, weekly.percent, 0.001) // 100 - 95
        assertEquals(Instant.parse("2026-08-25T00:08:17Z"), weekly.resetAt)
    }

    @Test
    fun parse_grokBuildAlias_displayNameIsGrokBuild() {
        val record = parser.parse(
            """{"provider":"grok-build","status":"ok","windows":{"7d":{"percent_remaining":95.0,"reset_at":null}},"error":null,"details":{}}""",
        ).single()
        assertEquals("Grok Build", record.displayName)
    }

    @Test
    fun parse_grokBothWindows_unified7dAndMonthlyLabels() {
        val record = parser.parse(
            """{"provider":"grok","status":"ok","windows":{"7d":{"percent_remaining":62.5,"reset_at":"2026-08-25T00:08:17Z"},"monthly":{"percent_remaining":75.0,"reset_at":"2026-09-01T00:00:00Z"}},"error":null,"details":{}}""",
        ).single()

        assertEquals("Grok Build", record.displayName)
        assertEquals(setOf("7d", "monthly"), record.windows.map { it.name }.toSet())
        assertEquals(37.5, record.windows.first { it.name == "7d" }.percent, 0.001) // 100 - 62.5
        assertEquals(Instant.parse("2026-08-25T00:08:17Z"), record.windows.first { it.name == "7d" }.resetAt)
        assertEquals("monthly", record.windows.first { it.name == "monthly" }.name)
        assertEquals(25.0, record.windows.first { it.name == "monthly" }.percent, 0.001) // 100 - 75
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), record.windows.first { it.name == "monthly" }.resetAt)
    }

    @Test
    fun parse_grokWithNoRenderableWindows_recordExistsNoThrow() {
        // The producer may explicitly emit an empty canonical map when a
        // provider has no renderable source span; that is not schema drift.
        val record = parser.parse(
            """{"provider":"grok","status":"ok",$nullSpans,"error":null,"details":{}}""",
        ).single()

        assertEquals("grok", record.provider)
        assertEquals("Grok Build", record.displayName)
        assertEquals(UsageStatus.Ok, record.status)
        assertTrue(record.windows.isEmpty())
    }

    @Test
    fun parse_grokNoCredentials_mapsToActionableSignInError() {
        val record = parser.parse(
            """{"provider":"grok","status":"error",$nullSpans,"error":"no-credentials","details":{}}""",
        ).single()

        assertEquals(UsageStatus.Error, record.status)
        assertEquals(
            "Grok login needed on this host. " +
                "Sign in with `grok` on the host, then refresh usage.",
            record.lastError,
        )
        assertTrue(record.lastError?.contains("no-credentials", ignoreCase = true) == false)
        assertTrue(record.lastError?.contains("Sign in with `grok` on the host") == true)
        assertTrue(record.windows.isEmpty())
    }

    @Test
    fun parse_grokAuthJsonMissing_mapsToActionableSignInError() {
        val record = parser.parse(
            """{"provider":"grok","status":"error",$nullSpans,"error":"grok auth.json not found","details":{}}""",
        ).single()

        assertEquals(UsageStatus.Error, record.status)
        assertEquals(
            "Grok login needed on this host. " +
                "Sign in with `grok` on the host, then refresh usage.",
            record.lastError,
        )
        assertTrue(record.lastError?.contains("auth.json") == false)
    }

    // -- genuine runtime states (kept) ---------------------------------------

    @Test
    fun parse_unsupportedStatus_forGemini() {
        val record = parser.parse(
            """{"provider":"gemini","status":"unsupported",$nullSpans,"error":"gemini does not expose a usage endpoint","details":{}}""",
        ).single()

        assertEquals(UsageStatus.Unsupported, record.status)
        assertTrue(record.windows.isEmpty())
        assertEquals("gemini does not expose a usage endpoint", record.lastError)
    }

    @Test
    fun parse_errorStatus_surfacesErrorField() {
        val record = parser.parse(
            """{"provider":"codex","status":"error",$nullSpans,"error":"login required: run codex login","details":{}}""",
        ).single()

        assertEquals(UsageStatus.Error, record.status)
        assertEquals("login required: run codex login", record.lastError)
        assertTrue(record.windows.isEmpty())
    }

    @Test
    fun parse_claudeUnauthorizedMapsToActionableError() {
        val record = parser.parse(
            """{"provider":"claude","status":"error",$nullSpans,"error":"HTTP Error 401: Unauthorized","details":{}}""",
        ).single()

        assertEquals(UsageStatus.Error, record.status)
        assertEquals(
            "Claude login needed on this host. " +
                "Open Claude Code on the host and sign in, then refresh usage.",
            record.lastError,
        )
        assertTrue(record.lastError?.contains("HTTP Error 401", ignoreCase = true) == false)
        // An empty canonical map yields no renderable windows.
        assertTrue(record.windows.isEmpty())
    }

    @Test
    fun parse_blockedStatus_mapsToBlockedExceededRecord() {
        val record = parser.parse(
            """{"provider":"codex","status":"quota-exhausted",$nullSpans,"block_reason":"Codex quota exhausted","error":null,"details":{"message":"quota exhausted"}}""",
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
            {"provider":"codex","status":"ok","windows":{"5h":{"percent_remaining":50.0}},"error":null,"details":{}}
            {"provider":"claude","status":"limited","windows":{"5h":{"percent_remaining":0.0}},"block_reason":"weekly limit reached","error":null,"details":{}}
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
                """{"provider":"codex","status":"ok","windows":{"5h":{"percent_remaining":77.0}},"error":null,"details":{}}""" +
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
            """{"provider":"x","status":"maintenance",$nullSpans,"error":null,"details":{}}""",
        ).single()
        assertEquals(UsageStatus.Unknown, record.status)
        assertEquals("maintenance", record.rawStatus)
    }

    // -- issue #2274: published quse 0.0.14 -> app wire normalization --------

    /**
     * The REAL captured quse-0.0.14 `--json` document from the published
     * wheel (same file as tools/pocketshell/tests/data/quse-0.0.14-usage.json).
     */
    private fun quse0014Document(): org.json.JSONObject {
        val bytes = javaClass.getResourceAsStream("/quse-0.0.14-usage.json")?.readBytes()
            ?: throw AssertionError("missing quse-0.0.14-usage.json test resource")
        return org.json.JSONObject(bytes.decodeToString())
    }

    /** Apply the host producer's published short/long -> canonical windows
     * translation, then inject each key as a top-level `provider`. */
    private fun flattenProviderKeyed(doc: org.json.JSONObject): String = buildString {
        for (key in doc.keys()) {
            val line = org.json.JSONObject(doc.getJSONObject(key).toString())
            val windows = org.json.JSONObject()
            for (sourceKey in listOf("short_term", "long_term")) {
                if (!line.has(sourceKey) || line.isNull(sourceKey)) continue
                val sourceWindow = line.getJSONObject(sourceKey)
                // Match the host producer: a published null percentage means
                // that source span is unavailable, so it is omitted before
                // canonical parser input is formed.
                if (sourceWindow.isNull("percent_remaining")) continue
                val label = if (sourceWindow.isNull("window")) {
                    sourceKey
                } else {
                    sourceWindow.getString("window")
                }
                windows.put(
                    label,
                    org.json.JSONObject()
                        .put("percent_remaining", sourceWindow.get("percent_remaining"))
                        .put("reset_at", sourceWindow.get("reset_at")),
                )
            }
            line.remove("short_term")
            line.remove("long_term")
            line.put("windows", windows)
            line.put("provider", key)
            append(line.toString())
            append('\n')
        }
    }

    private fun windowOf(record: UsageProviderRecord, name: String): UsageWindow =
        record.windows.firstOrNull { it.name == name }
            ?: throw AssertionError(
                "record for ${record.provider} has no '$name' window " +
                    "(windows=${record.windows.map { it.name }}) — silent-empty?",
            )

    @Test
    fun parse_quse0014PublishedCapture_rendersCanonicalWindowsForAllFiveProviders() {
        val records = parser.parse(flattenProviderKeyed(quse0014Document()))
        val byProvider = records.associateBy { it.provider }

        // The published 0.0.14 release has five providers; the unreleased
        // a86959e `go` provider is deliberately absent.
        assertEquals(
            setOf("claude", "codex", "copilot", "grok", "zai"),
            byProvider.keys,
        )
        records.forEach { assertEquals(UsageStatus.Ok, it.status) }

        // claude: 5h + 7d renderable; monthly is a null-percent span → omitted.
        val claude = byProvider.getValue("claude")
        assertEquals(listOf("5h", "7d"), claude.windows.map { it.name }.sorted())
        assertEquals(1.0, windowOf(claude, "5h").percent, 0.001) // 100 - 99
        assertEquals(Instant.parse("2026-08-22T15:49:59Z"), windowOf(claude, "5h").resetAt)
        assertEquals(7.0, windowOf(claude, "7d").percent, 0.001) // 100 - 93
        assertEquals(Instant.parse("2026-08-27T14:59:59Z"), windowOf(claude, "7d").resetAt)

        // codex: weekly-only renderable — its 5h span carries null percent and
        // must be omitted (no ghost row), same semantics as the 0.0.11 era.
        val codex = byProvider.getValue("codex")
        assertEquals(listOf("7d"), codex.windows.map { it.name })
        assertEquals(44.0, windowOf(codex, "7d").percent, 0.001) // 100 - 56
        assertEquals(Instant.parse("2026-08-27T03:30:22Z"), windowOf(codex, "7d").resetAt)

        // copilot: the published short-term span has a 100% value and no
        // label, so the producer uses the generic short_term key alongside
        // the real monthly window.
        val copilot = byProvider.getValue("copilot")
        assertEquals(listOf("monthly", "short_term"), copilot.windows.map { it.name }.sorted())
        assertEquals(0.0, windowOf(copilot, "monthly").percent, 0.001) // 100 - 100
        assertEquals(Instant.parse("2026-09-01T00:00:00Z"), windowOf(copilot, "monthly").resetAt)

        // grok: the published provider-owned weekly label remains weekly.
        val grok = byProvider.getValue("grok")
        assertEquals(listOf("weekly"), grok.windows.map { it.name })
        assertEquals(100.0, windowOf(grok, "weekly").percent, 0.001) // 100 - 0
        assertTrue("grok at 100% used must read blocked", grok.isBlocked)

        // zai: 5h + weekly.
        val zai = byProvider.getValue("zai")
        assertEquals(setOf("5h", "weekly"), zai.windows.map { it.name }.toSet())
        assertEquals(100.0, windowOf(zai, "weekly").percent, 0.001) // 100 - 0
    }

    @Test
    fun parse_separateCanonicalGoContract_preservesWindowLabels() {
        val record = parser.parse(
            """{"provider":"go","status":"ok","windows":{"5h":{"percent_remaining":97.0,"reset_at":"2026-08-22T11:21:36Z","rolling":true},"monthly":{"percent_remaining":94.0,"reset_at":"2026-09-22T06:20:28Z"}},"error":null,"details":{"max_used_percent":3.0}}""",
        ).single()

        assertEquals("go", record.provider)
        assertEquals("OpenCode Go", record.displayName)
        assertEquals(2, record.windows.size)
        val fiveHour = record.windows.first { it.name == "5h" }
        // The separate canonical producer's map key is the window label.
        assertEquals("5h", fiveHour.name)
        assertEquals(3.0, fiveHour.used, 0.001) // 100 - 97
        assertEquals(100.0, fiveHour.limit, 0.001)
        assertEquals("percent", fiveHour.unit)
        assertEquals(Instant.parse("2026-08-22T11:21:36Z"), fiveHour.resetAt)
        assertEquals(6.0, record.windows.first { it.name == "monthly" }.percent, 0.001)
        // Extra producer metadata such as rolling is ignored by the app model.
    }

    @Test
    fun parse_defensivelyTreatsAbsentWindowsAsZeroRenderableRows() {
        // The producer boundary rejects a record missing both canonical and
        // legacy window fields. The parser remains defensive for custom or
        // already-normalized input and treats an absent/null map as no rows.
        val absent = parser.parse(
            """{"provider":"x","status":"ok","error":null,"details":{}}""",
        ).single()
        val explicitNull = parser.parse(
            """{"provider":"x","status":"ok","windows":null,"error":null,"details":{}}""",
        ).single()
        assertTrue(absent.windows.isEmpty())
        assertTrue(explicitNull.windows.isEmpty())
    }

    @Test
    fun parse_quse0014NonObjectWindows_failsLoud() {
        val error = assertThrows(UsageParseException::class.java) {
            parser.parse(
                """{"provider":"x","status":"ok","windows":"drifted","error":null,"details":{}}""",
            )
        }
        assertTrue(error.message!!.contains("windows"))
        assertThrows(UsageParseException::class.java) {
            parser.parse(
                """{"provider":"x","status":"ok","windows":{"5h":"not-an-object"},"error":null,"details":{}}""",
            )
        }
    }

    @Test
    fun parse_quse0014NullWindowEntry_failsLoud_insteadOfSkippingIt() {
        val error = assertThrows(UsageParseException::class.java) {
            parser.parse(
                """{"provider":"x","status":"ok","windows":{"5h":null},"error":null,"details":{}}""",
            )
        }
        assertTrue(error.message!!.contains("windows.5h"))
    }

    @Test
    fun parse_nullPercentWindowEntry_isNonRenderable_insteadOfGhostRow() {
        val record = parser.parse(
            """{"provider":"x","status":"ok","windows":{"5h":{"percent_remaining":null,"reset_at":null},"7d":{"percent_remaining":75.0,"reset_at":null}},"error":null,"details":{}}""",
        ).single()
        assertEquals(listOf("7d"), record.windows.map { it.name })
        assertEquals(25.0, record.windows.single().percent, 0.001)
    }

    @Test
    fun parse_quse0014MalformedPercentOrResetInWindow_failsLoud() {
        assertThrows(UsageParseException::class.java) {
            parser.parse(
                """{"provider":"x","status":"ok","windows":{"5h":{"percent_remaining":"not-a-number"}},"error":null,"details":{}}""",
            )
        }
        val error = assertThrows(UsageParseException::class.java) {
            parser.parse(
                """{"provider":"x","status":"ok","windows":{"5h":{"percent_remaining":50.0,"reset_at":"not-a-date"}},"error":null,"details":{}}""",
            )
        }
        assertTrue(error.message!!.contains("reset_at"))
    }

    @Test
    fun parse_quse0014CodexResetCredits_realDetailsShapeParsesWithExactMatchInvariant() {
        // Fix-shape item: verify the real 0.0.14 `{expires_at, status, title}`
        // entry shape against the live capture, including the
        // availableCount == exact-available rows invariant (mismatch throws).
        val codex = parser.parse(flattenProviderKeyed(quse0014Document()))
            .single { it.provider == "codex" }
        val credits = requireNotNull(codex.resetCredits)
        assertFalse(credits.unavailable)
        assertEquals(1, credits.availableCount)
        assertEquals(1, credits.credits.size)
        assertEquals("Full reset", credits.credits.single().title)
        assertEquals(Instant.parse("2026-09-21T00:13:17Z"), credits.credits.single().expiresAt)
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
    fun parse_rejectsMissingProvider() {
        assertThrows(UsageParseException::class.java) {
            parser.parse(
                """{"status":"ok",$nullSpans,"error":null,"details":{}}""",
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
                {"provider":"codex","status":"ok","windows":{"5h":{"percent_remaining":77.0}},"error":null,"details":{}}
                {"status":"ok","windows":"not-an-object","error":null,"details":{}}
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
                {"provider":"codex","status":"ok","windows":{"5h":{"percent_remaining":50.0}},"error":null,"details":{}}
                """.trimIndent(),
            )
        }
    }
}
