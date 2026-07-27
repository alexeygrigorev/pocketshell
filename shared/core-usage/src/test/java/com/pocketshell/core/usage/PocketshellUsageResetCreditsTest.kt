package com.pocketshell.core.usage

import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #1789 reproduce-first contract for the narrow Codex `details`
 * exception.
 *
 * Reflection is deliberate: this test compiles against the untouched base
 * model, then fails because [UsageProviderRecord] has no reset-credit
 * inventory. That makes the parser/model RED behavioral rather than an
 * unresolved-symbol compile failure. The property names asserted here are the
 * additive public contract consumed by the app.
 */
class PocketshellUsageResetCreditsTest {

    private val parser = PocketshellUsageJsonParser()

    @Test
    fun quse009_duplicateAvailableTitlesAndDistinctExpiriesSurviveInSourceOrder() {
        val record = parser.parse(
            record(
                details = """
                    {
                      "reset_credits_available":2,
                      "reset_credits_error":null,
                      "reset_credits":[
                        {"status":"available","title":"Full reset (Weekly + 5 hr)","expires_at":"2026-07-26T23:52:15Z"},
                        {"status":"available","title":"Full reset (Weekly + 5 hr)","expires_at":"2026-07-31T19:09:12Z"},
                        {"status":"consumed","title":"Consumed reset must stay hidden","expires_at":"2026-08-01T19:09:12Z"}
                      ],
                      "windows":{"must_remain_ignored":{"reset_at":"1999-01-01T00:00:00Z"}}
                    }
                """.trimIndent(),
            ),
        ).single()

        val inventory = requireInventory(record)
        assertEquals(2, inventory.availableCount)
        assertFalse(inventory.unavailable)
        assertEquals(
            listOf("Full reset (Weekly + 5 hr)", "Full reset (Weekly + 5 hr)"),
            inventory.credits.map { it.title },
        )
        assertEquals(
            listOf(
                Instant.parse("2026-07-26T23:52:15Z"),
                Instant.parse("2026-07-31T19:09:12Z"),
            ),
            inventory.credits.map { it.expiresAt },
        )
        assertEquals(
            Instant.parse(WEEKLY_RESET),
            record.windows.single().resetAt,
        )
    }

    @Test
    fun quse0011_threeCreditsRemainSeparateFromWeeklyQuotaWindow() {
        val record = parser.parse(
            record(
                details = """
                    {
                      "limit_reached":false,
                      "reset_credits_available":3,
                      "reset_credits_error":null,
                      "reset_credits":[
                        {"status":"available","title":"Full reset","expires_at":"2030-07-31T19:09:12Z"},
                        {"status":"available","title":"Full reset","expires_at":"2030-08-11T21:09:47Z"},
                        {"status":"available","title":"Full reset (Weekly + 5 hr)","expires_at":"2030-08-12T18:09:45Z"}
                      ]
                    }
                """.trimIndent(),
                resetAt = "2030-07-21T20:37:32Z",
            ),
        ).single()

        val inventory = requireInventory(record)
        assertEquals(3, inventory.availableCount)
        assertEquals(
            listOf("Full reset", "Full reset", "Full reset (Weekly + 5 hr)"),
            inventory.credits.map { it.title },
        )
        assertEquals("7d", record.windows.single().name)
        assertEquals(Instant.parse("2030-07-21T20:37:32Z"), record.windows.single().resetAt)
    }

    @Test
    fun onlyExactLowercaseAvailableStatusBecomesInventory() {
        val statuses = listOf("available", "consumed", "expired", "Available", "AVAILABLE", "pending")
        val rows = statuses.mapIndexed { index, status ->
            """{"status":"$status","title":"row-$index","expires_at":"2030-08-${10 + index}T00:00:00Z"}"""
        }.joinToString(",")
        val inventory = requireInventory(
            parser.parse(
                record(
                    details =
                        """{"reset_credits_available":1,"reset_credits_error":null,"reset_credits":[$rows]}""",
                ),
            ).single(),
        )

        assertEquals(1, inventory.availableCount)
        assertEquals(listOf("row-0"), inventory.credits.map { it.title })
    }

    @Test
    fun authoritativeZeroWithEmptyListProducesPresentEmptyInventory() {
        val inventory = requireInventory(
            parser.parse(
                record(
                    details =
                        """{"reset_credits_available":0,"reset_credits_error":null,"reset_credits":[]}""",
                ),
            ).single(),
        )

        assertEquals(0, inventory.availableCount)
        assertTrue(inventory.credits.isEmpty())
        assertFalse(inventory.unavailable)
    }

    @Test
    fun missingDetailsOrAllCreditFieldsOmitsInventoryWithoutFabricatingZero() {
        val missingDetails = parser.parse(record(details = null)).single()
        val unrelatedDetails = parser.parse(record(details = """{"limit_reached":false}""")).single()

        assertNull(inventoryOrNull(missingDetails))
        assertNull(inventoryOrNull(unrelatedDetails))
    }

    @Test
    fun supplementaryCreditErrorKeepsValidWindowAndHidesRawProviderText() {
        val record = parser.parse(
            record(
                details =
                    """{"reset_credits_error":"HTTP 401 bearer-token-secret","reset_credits_available":null,"reset_credits":null}""",
            ),
        ).single()

        val inventory = requireInventory(record)
        assertTrue(inventory.unavailable)
        assertNull(inventory.availableCount)
        assertTrue(inventory.credits.isEmpty())
        assertEquals(Instant.parse(WEEKLY_RESET), record.windows.single().resetAt)
        assertNull("supplementary error must not become provider lastError", record.lastError)
    }

    @Test
    fun nullAndBlankTitlesUseNeutralFallbackAndLongTitleIsBounded() {
        val longTitle = "x".repeat(400)
        val inventory = requireInventory(
            parser.parse(
                record(
                    details = """
                        {
                          "reset_credits_available":3,
                          "reset_credits":[
                            {"status":"available","title":null,"expires_at":null},
                            {"status":"available","title":"   ","expires_at":null},
                            {"status":"available","title":"$longTitle","expires_at":null}
                          ]
                        }
                    """.trimIndent(),
                ),
            ).single(),
        )

        assertEquals(listOf("Reset credit", "Reset credit"), inventory.credits.take(2).map { it.title })
        assertTrue("accessible title must be bounded", inventory.credits.last().title.length <= 160)
    }

    @Test
    fun nullAndPastExpiryArePreservedAsInventoryFacts() {
        val inventory = requireInventory(
            parser.parse(
                record(
                    details = """
                        {
                          "reset_credits_available":2,
                          "reset_credits":[
                            {"status":"available","title":"No expiry","expires_at":null},
                            {"status":"available","title":"Past expiry","expires_at":"2020-01-01T00:00:00Z"}
                          ]
                        }
                    """.trimIndent(),
                ),
            ).single(),
        )

        assertNull(inventory.credits[0].expiresAt)
        assertEquals(Instant.parse("2020-01-01T00:00:00Z"), inventory.credits[1].expiresAt)
    }

    @Test
    fun malformedAvailableExpiryFailsWholePanelLoudly() {
        val error = assertThrows(UsageParseException::class.java) {
            parser.parse(
                record(
                    details = """
                        {
                          "reset_credits_available":1,
                          "reset_credits":[
                            {"status":"available","title":"Broken","expires_at":"tomorrow-ish"}
                          ]
                        }
                    """.trimIndent(),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("expires_at"))
    }

    @Test
    fun countMismatchFailsWholePanelLoudly() {
        val error = assertThrows(UsageParseException::class.java) {
            parser.parse(
                record(
                    details = """
                        {
                          "reset_credits_available":2,
                          "reset_credits":[
                            {"status":"available","title":"Only one","expires_at":null}
                          ]
                        }
                    """.trimIndent(),
                ),
            )
        }

        assertTrue(error.message.orEmpty().contains("reset_credits_available"))
    }

    @Test
    fun partialCountOrListShapeFailsWholePanelLoudly() {
        assertThrows(UsageParseException::class.java) {
            parser.parse(record(details = """{"reset_credits_available":0}"""))
        }
        assertThrows(UsageParseException::class.java) {
            parser.parse(record(details = """{"reset_credits":[]}"""))
        }
    }

    @Test
    fun repeatedCachedPayloadParsingPreservesCreditsWithoutDeduplication() {
        val input = record(
            details = """
                {
                  "reset_credits_available":2,
                  "reset_credits":[
                    {"status":"available","title":"same","expires_at":"2030-08-11T21:09:47Z"},
                    {"status":"available","title":"same","expires_at":"2030-08-12T18:09:45Z"}
                  ]
                }
            """.trimIndent(),
        )

        val first = requireInventory(parser.parse(input).single())
        val cached = requireInventory(parser.parse(input).single())
        assertEquals(first, cached)
        assertEquals(2, cached.credits.size)
    }

    @Test
    fun nonCodexProvidersIgnoreProviderSpecificDetailsEvenWhenMalformed() {
        val ndjson = listOf("claude", "copilot", "zai").joinToString("\n") { provider ->
            record(
                provider = provider,
                details = """
                    {
                      "reset_credits_available":99,
                      "reset_credits":[
                        {"status":"available","title":"must be ignored","expires_at":"not-a-date"}
                      ],
                      "windows":{"must":"remain ignored"}
                    }
                """.trimIndent(),
            )
        }

        val records = parser.parse(ndjson)
        assertEquals(listOf("claude", "copilot", "zai"), records.map { it.provider })
        records.forEach { record ->
            assertNull(inventoryOrNull(record))
            assertEquals(Instant.parse(WEEKLY_RESET), record.windows.single().resetAt)
        }
    }

    private fun requireInventory(record: UsageProviderRecord): InventoryView =
        inventoryOrNull(record) ?: throw AssertionError("missing Codex reset-credit inventory")

    private fun inventoryOrNull(record: UsageProviderRecord): InventoryView? {
        val getter = record.javaClass.methods.singleOrNull { it.name == "getResetCredits" }
            ?: return null
        val inventory = getter.invoke(record) ?: return null
        val availableCount = invoke(inventory, "getAvailableCount") as? Int
        val unavailable = (
            invokeOrNull(inventory, "getUnavailable")
                ?: invokeOrNull(inventory, "isUnavailable")
            ) as? Boolean ?: false
        val credits = (invoke(inventory, "getCredits") as List<*>).map { raw ->
            requireNotNull(raw)
            CreditView(
                title = invoke(raw, "getTitle") as String,
                expiresAt = invoke(raw, "getExpiresAt") as? Instant,
            )
        }
        return InventoryView(
            availableCount = availableCount,
            unavailable = unavailable,
            credits = credits,
        )
    }

    private fun invoke(target: Any, method: String): Any? {
        val reflected = target.javaClass.methods.singleOrNull { it.name == method }
            ?: throw AssertionError("missing $method on ${target.javaClass.name}")
        return reflected.invoke(target)
    }

    private fun invokeOrNull(target: Any, method: String): Any? =
        target.javaClass.methods.singleOrNull { it.name == method }?.invoke(target)

    private fun record(
        provider: String = "codex",
        details: String?,
        resetAt: String = WEEKLY_RESET,
    ): String {
        val detailsField = details?.let { ""","details":$it""" }.orEmpty()
        return """
            {
              "provider":"$provider",
              "status":"ok",
              "short_term":{"percent_remaining":69.0,"reset_at":"$resetAt","window":"7d"},
              "long_term":null,
              "block_reason":null,
              "error":null
              $detailsField
            }
        """.trimIndent().replace("\n", "")
    }

    private data class InventoryView(
        val availableCount: Int?,
        val unavailable: Boolean,
        val credits: List<CreditView>,
    )

    private data class CreditView(
        val title: String,
        val expiresAt: Instant?,
    )

    private companion object {
        const val WEEKLY_RESET = "2026-07-21T20:37:32Z"
    }
}
