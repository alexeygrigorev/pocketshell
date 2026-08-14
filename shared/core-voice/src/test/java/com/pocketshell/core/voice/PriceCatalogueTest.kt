package com.pocketshell.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Unit tests for [PriceCatalogue] (issue #181).
 *
 * Covers:
 *  - Bundled `ai-pricing.json` parses cleanly and exposes Whisper's
 *    documented unit cost (10 millicents/audio_second as of 2026-01).
 *  - Unknown `(provider, feature)` pairs fall back to `0` so the call
 *    site logs a zero-cost row instead of crashing.
 *  - Malformed JSON degrades to an [PriceCatalogue.empty] catalogue.
 */
class PriceCatalogueTest {

    @Test
    fun bundledResource_returnsWhisperPrice() {
        val catalogue = PriceCatalogue.fromBundledResource()

        // OpenAI's published Whisper rate is $0.006 / minute = 10
        // millicents / audio_second. The bundled JSON must match.
        assertEquals(10L, catalogue.unitCost("openai", "whisper"))
        assertNotEquals("unknown", catalogue.version())
    }

    @Test
    fun unknownPairFallsBackToZero() {
        val catalogue = PriceCatalogue.fromBundledResource()

        assertEquals(0L, catalogue.unitCost("openai", "gpt4o"))
        assertEquals(0L, catalogue.unitCost("anthropic", "claude-3-opus"))
    }

    @Test
    fun malformedJsonDegradesToEmpty() {
        val catalogue = PriceCatalogue.fromJsonString("not actually json")

        assertEquals(0L, catalogue.unitCost("openai", "whisper"))
        assertEquals("empty", catalogue.version())
    }

    @Test
    fun emptyProvidersBlockParsesWithoutError() {
        val catalogue = PriceCatalogue.fromJsonString("""{"version":"x","providers":{}}""")
        assertEquals("x", catalogue.version())
        assertEquals(0L, catalogue.unitCost("openai", "whisper"))
    }

    @Test
    fun customJsonParsesUnitPricing() {
        val json = """
            {
              "version": "test",
              "providers": {
                "openai": {
                  "gpt4o-mini": {
                    "unit": "input_token",
                    "unitCostUsdMillicents": 15
                  }
                }
              }
            }
        """.trimIndent()

        val catalogue = PriceCatalogue.fromJsonString(json)

        assertEquals(15L, catalogue.unitCost("openai", "gpt4o-mini"))
    }

    // Issue #2113: the former `snapshotIntegerArithmeticIsExact` is DELETED. It
    // multiplied `47L * unitCost` IN THE TEST BODY and asserted 470, so no change
    // to the production cost computation could redden it — proven by rewriting
    // `WhisperClient.recordCostSafely`'s `audioSeconds * unitCost` as
    // off-by-1000 float arithmetic: that reddened
    // `OkHttpWhisperClientTest.successful_call_records_cost_with_snapshot_price`
    // and left every test in this class green. Its only production-touching
    // assertion (`unitCost("openai","whisper") == 10L`) already lives verbatim in
    // `bundledResource_returnsWhisperPrice` above, and the integer-exactness
    // property is asserted against the REAL call site there
    // (`assertEquals(50L, row.computedCostUsdMillicents)` for a 5-second clip).
}
