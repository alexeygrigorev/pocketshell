package com.pocketshell.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per client-side AI API call (issue #181). Created when the app
 * makes a successful (or-still-billed) request to a provider — Whisper
 * today, future chat / TTS endpoints later.
 *
 * Pricing is snapshotted at the time of the call: [unitCostUsdMillicents]
 * captures the per-input-unit price from `ai-pricing.json` as it was when
 * the row was written. Subsequent edits to `ai-pricing.json` do not
 * retroactively change [computedCostUsdMillicents]. This is the central
 * design decision the maintainer locked in (see issue body): "when we
 * update the price, we would record new things with new price" — never
 * recompute history.
 *
 * Why millicents (1/1000 of a US cent, i.e. 1e-5 USD) and not floats:
 *
 * - The published Whisper rate is `$0.006 / audio_minute` = `$0.0001 /
 *   audio_second` = `0.01 cent / second` = `10 millicents / second`. With
 *   a per-second `unit_cost` of `10` and an integer `audio_seconds` count
 *   we never divide or round at insert time — `computedCost = inputUnits
 *   * unitCost` is exact.
 * - Future chat models cost on the order of micro-dollars per token
 *   ($0.00003 = 3 millicents). Integer millicents still represent these
 *   exactly without IEEE-754 drift across millions of rows.
 * - SQLite has no decimal type. `INTEGER` round-trips cleanly between
 *   Room and SQLite without precision loss.
 *
 * Field semantics:
 *
 * - [provider] discriminates the upstream API (e.g. `"openai"`). String
 *   for the same migration-friendliness reason as
 *   [SnippetEntity.kind] / [AgentSessionEntity.agent].
 * - [feature] identifies the model / endpoint within the provider, e.g.
 *   `"whisper"`. Future: `"gpt4o"`, `"gpt4o-mini"`, `"tts-hd"`.
 * - [inputUnits] is the priced input quantity in the model's native unit:
 *   audio seconds for Whisper, prompt tokens for chat.
 * - [outputUnits] is the response size in the model's native unit:
 *   transcript characters for Whisper, completion tokens for chat. Not
 *   priced today for Whisper (Whisper bills purely on audio length), but
 *   recorded so future cost-derivation routines have the data.
 * - [unitCostUsdMillicents] is the per-input-unit price snapshot.
 * - [computedCostUsdMillicents] is the total cost for this call. For
 *   Whisper it is `inputUnits * unitCostUsdMillicents`. For future chat
 *   models the caller computes the total before insert (input + output
 *   prices are usually different and the price catalogue exposes both).
 * - [metadataJson] is optional free-form JSON. Used for diagnostic
 *   columns we don't want to first-class in the schema (model name,
 *   request id, etc.). Kept nullable so the row stays cheap to write.
 *
 * The provider / feature columns are jointly indexed because every
 * aggregate query the costs screen runs filters by feature first
 * ("Whisper total" / "GPT-4o total"). The timestamp column is also
 * indexed because every aggregate query is also windowed (today / week /
 * month).
 */
@Entity(
    tableName = "ai_api_call_log",
    indices = [
        Index(value = ["timestampMillis"]),
        Index(value = ["provider", "feature"]),
    ],
)
data class AiApiCallEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestampMillis: Long,
    val provider: String,
    val feature: String,
    val inputUnits: Long,
    val outputUnits: Long,
    val unitCostUsdMillicents: Long,
    val computedCostUsdMillicents: Long,
    val metadataJson: String? = null,
)
