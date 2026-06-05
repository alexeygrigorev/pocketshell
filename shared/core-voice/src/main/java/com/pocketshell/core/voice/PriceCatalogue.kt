package com.pocketshell.core.voice

import org.json.JSONException
import org.json.JSONObject

/**
 * Price look-up for client-side AI API calls (issue #181).
 *
 * Reads `ai-pricing.json` (bundled in this module's resources) at
 * construction time and exposes [unitCost] for any `(provider, feature)`
 * pair the JSON declares. Snapshotted by the caller into
 * [com.pocketshell.core.storage.entity.AiApiCallEntry.unitCostUsdMillicents]
 * at the moment the API request is made — subsequent edits to the JSON
 * never retroactively change historical rows.
 *
 * ## Units
 *
 * Prices are stored as **USD millicents** (1/1000 of a US cent =
 * `1e-5 USD`). See the entity KDoc for the integer-precision rationale.
 * For Whisper today the only unit is `audio_second`: at $0.006 / minute
 * that's `10 millicents / audio_second`.
 *
 * ## Fallback
 *
 * If the JSON doesn't declare the requested pair (e.g. a future feature
 * has shipped client code but no price entry) [unitCost] returns `0`. The
 * caller still logs the call — a zero-cost row is correct for "we don't
 * know the price yet" and the costs screen will surface it under the
 * feature with a `0.00 USD` total until the catalogue is updated.
 *
 * ## Reading
 *
 * [fromBundledResource] reads `/ai-pricing.json` from the classpath via
 * `ClassLoader.getResourceAsStream`. Tests drive the parser directly with
 * [fromJsonString].
 */
public class PriceCatalogue private constructor(
    private val version: String,
    private val table: Map<String, Map<String, FeaturePricing>>,
) {

    /** ISO-style version stamp from the JSON. Informational only. */
    public fun version(): String = version

    /**
     * Per-input-unit price snapshot in USD millicents for the named
     * provider+feature pair. Returns `0` when the pair is unknown so the
     * call site can still log the request — a future catalogue edit can
     * surface the right number for any new calls without invalidating the
     * history.
     */
    public fun unitCost(provider: String, feature: String): Long =
        feature(provider, feature)?.unitCostUsdMillicents ?: 0L

    private fun feature(provider: String, feature: String): FeaturePricing? =
        table[provider]?.get(feature)

    /** Per-feature row inside the JSON. Exposed for tests. */
    public data class FeaturePricing(
        val unit: String,
        val unitCostUsdMillicents: Long,
    )

    public companion object {
        /**
         * Build a [PriceCatalogue] from the bundled `ai-pricing.json`.
         * Returns a catalogue with an empty table when the resource is
         * missing or unparseable — the call site degrades to zero-cost
         * rows rather than crashing on a malformed checkin.
         */
        public fun fromBundledResource(): PriceCatalogue {
            val stream = PriceCatalogue::class.java.getResourceAsStream("/ai-pricing.json")
                ?: return empty()
            val raw = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            return fromJsonString(raw)
        }

        /**
         * Parse a raw JSON [text] into a [PriceCatalogue]. Returns
         * [empty] when the input isn't valid JSON in the expected shape.
         * Exposed `public` so tests can drive parser behaviour directly.
         */
        public fun fromJsonString(text: String): PriceCatalogue = try {
            val root = JSONObject(text)
            val version = root.optString("version", "unknown")
            val providers = root.optJSONObject("providers")
            val table = mutableMapOf<String, MutableMap<String, FeaturePricing>>()
            if (providers != null) {
                val providerKeys = providers.keys()
                while (providerKeys.hasNext()) {
                    val providerKey = providerKeys.next()
                    val featuresJson = providers.optJSONObject(providerKey) ?: continue
                    val featureMap = mutableMapOf<String, FeaturePricing>()
                    val featureKeys = featuresJson.keys()
                    while (featureKeys.hasNext()) {
                        val featureKey = featureKeys.next()
                        val featureJson = featuresJson.optJSONObject(featureKey) ?: continue
                        featureMap[featureKey] = FeaturePricing(
                            unit = featureJson.optString("unit", "unknown"),
                            unitCostUsdMillicents = featureJson.optLong(
                                "unitCostUsdMillicents",
                                0L,
                            ),
                        )
                    }
                    table[providerKey] = featureMap
                }
            }
            PriceCatalogue(version, table)
        } catch (_: JSONException) {
            empty()
        }

        /** Empty catalogue — every lookup falls through to `0`. */
        public fun empty(): PriceCatalogue = PriceCatalogue(
            version = "empty",
            table = emptyMap(),
        )
    }
}

/**
 * Sink for client-side AI API call cost records (issue #181).
 *
 * Implemented at the app layer by a small Hilt-provided adapter that
 * forwards to the Room
 * [com.pocketshell.core.storage.dao.AiApiCallLogDao]. Lives at the voice
 * module's API edge so [OkHttpWhisperClient] can call into it without
 * depending on Room / app-module classes directly.
 *
 * A [NoOp] implementation is the default — tests, headless unit specs,
 * and the [VoiceModule]-free pathway (e.g. running [OkHttpWhisperClient]
 * stand-alone) get a working transcribe without needing the database.
 */
public interface AiCostRecorder {
    /**
     * Persist a record describing one client-side AI API call. Called
     * synchronously by the caller immediately after the upstream HTTP
     * call returns 2xx. Implementations should swallow errors — losing
     * a single cost row should never break the surrounding feature.
     */
    public suspend fun record(record: AiCostRecord)

    /** Drop-in no-op for tests / library-only consumers. */
    public object NoOp : AiCostRecorder {
        public override suspend fun record(record: AiCostRecord): Unit = Unit
    }
}

/**
 * Inputs to [AiCostRecorder.record]. Mirrors the
 * [com.pocketshell.core.storage.entity.AiApiCallEntry] fields the call
 * site populates; the adapter at the app layer translates this into an
 * entity row. Lives in `core-voice` so the recorder seam stays free of
 * a Room dependency.
 *
 * The class is `@JvmRecord`-shaped (Kotlin `data class`) so destructuring
 * and copy-with-changes work the same in tests as in production. The
 * caller fills in [timestampMillis] explicitly so a fake clock can drive
 * the assertion.
 */
public data class AiCostRecord(
    val timestampMillis: Long,
    val provider: String,
    val feature: String,
    val inputUnits: Long,
    val outputUnits: Long,
    val unitCostUsdMillicents: Long,
    val computedCostUsdMillicents: Long,
    val metadataJson: String? = null,
)
