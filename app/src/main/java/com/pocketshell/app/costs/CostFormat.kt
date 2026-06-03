package com.pocketshell.app.costs

import com.pocketshell.core.storage.entity.AiApiCallEntry
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Formatting helpers for the AI costs screen (issue #181). Kept in their
 * own file so the ViewModel and screen both consume the same canonical
 * rendering, and so the CSV / dollar formatting are unit-testable
 * without the Compose runtime.
 */
internal object CostFormat {

    /**
     * 1 USD = 100 cents = 100_000 millicents. The constants are spelled
     * out explicitly rather than implied so the math reads at a glance.
     */
    const val MILLICENTS_PER_DOLLAR: Long = 100_000L

    /**
     * Render a cost in millicents as a dollar string.
     *
     * - `$0.00` for an exact zero.
     * - `$0.0001`..`$0.0099` for sub-cent values (four decimals) — Whisper
     *   per-call cost lives here, so a single transcription is visible
     *   instead of collapsing to `$0.00`.
     * - `$0.01`..`$X.XX` for the conventional two-decimal "dollars and
     *   cents" presentation. Typical voice sessions exit the sub-cent
     *   range within a few minutes of use.
     */
    fun formatUsd(millicents: Long): String {
        if (millicents == 0L) return "$0.00"
        val dollarsTotal = millicents.toDouble() / MILLICENTS_PER_DOLLAR.toDouble()
        return if (dollarsTotal < 0.01) {
            // Sub-cent: show four decimal places (e.g. $0.0005) so the
            // user can see a single short Whisper transcription
            // registered without it rounding down to $0.000.
            String.format(Locale.US, "$%.4f", dollarsTotal)
        } else {
            String.format(Locale.US, "$%.2f", dollarsTotal)
        }
    }

    /**
     * Render a feature key as the user-facing label shown in the
     * breakdown list. Today only `("openai", "whisper")` is wired; future
     * features just add a `when` branch.
     */
    fun featureLabel(provider: String, feature: String): String = when {
        provider == "openai" && feature == "whisper" -> "OpenAI · Whisper"
        else -> "$provider · $feature"
    }

    /**
     * Format the unit suffix for the breakdown's per-call hint (e.g.
     * "audio seconds" for Whisper). Unknown features default to a
     * generic "units" string so the screen still renders.
     */
    fun unitLabel(provider: String, feature: String): String = when {
        provider == "openai" && feature == "whisper" -> "audio seconds"
        else -> "units"
    }

    /**
     * Section header for a day-grouped bucket (issue #467).
     *
     * - `daysBeforeToday == 0L` -> "Today"
     * - `daysBeforeToday == 1L` -> "Yesterday"
     * - otherwise -> an explicit date. Same calendar year -> `MMM d`
     *   ("Jun 2"); a different year includes it ("Jun 2, 2025") so the
     *   header is never ambiguous when the log spans a year boundary.
     */
    fun dayHeader(
        date: LocalDate,
        daysBeforeToday: Long,
        today: LocalDate = LocalDate.now(),
    ): String = when (daysBeforeToday) {
        0L -> "Today"
        1L -> "Yesterday"
        else -> {
            val pattern = if (date.year == today.year) "MMM d" else "MMM d, yyyy"
            DateTimeFormatter.ofPattern(pattern, Locale.US).format(date)
        }
    }

    /**
     * Render a single request inside a day group (issue #467) as a compact
     * one-line label: time, model/feature, priced input quantity, and the
     * per-request cost. Time is `HH:mm` because the day is already implied
     * by the section header, so a full date would be redundant noise.
     */
    fun formatRequestRow(
        request: DailyRequest,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val time = DateTimeFormatter
            .ofPattern("HH:mm", Locale.US)
            .withZone(zone)
            .format(Instant.ofEpochMilli(request.timestampMillis))
        val cost = formatUsd(request.costUsdMillicents)
        return "$time · ${featureLabel(request.provider, request.feature)} · " +
            "${request.inputUnits} ${unitLabel(request.provider, request.feature)} · $cost"
    }

    /**
     * Render the log as a CSV blob. Columns:
     *
     * `timestamp_iso,timestamp_epoch_millis,provider,feature,input_units,output_units,unit_cost_usd_millicents,computed_cost_usd_millicents,computed_cost_usd`
     *
     * The first row is the header. Strings with commas/quotes are
     * quoted CSV-style (RFC 4180-ish) — sufficient for OpenAI feature
     * names but not a full CSV writer. `metadataJson` is omitted from
     * the export because it can contain anything; the user can read
     * those rows in the app if they need to.
     */
    fun toCsv(entries: List<AiApiCallEntry>, zone: ZoneId = ZoneId.systemDefault()): String {
        val iso = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(zone)
        return buildString {
            appendLine(
                "timestamp_iso,timestamp_epoch_millis,provider,feature,input_units,output_units,unit_cost_usd_millicents,computed_cost_usd_millicents,computed_cost_usd",
            )
            entries.forEach { entry ->
                appendLine(
                    listOf(
                        csvField(iso.format(Instant.ofEpochMilli(entry.timestampMillis))),
                        entry.timestampMillis.toString(),
                        csvField(entry.provider),
                        csvField(entry.feature),
                        entry.inputUnits.toString(),
                        entry.outputUnits.toString(),
                        entry.unitCostUsdMillicents.toString(),
                        entry.computedCostUsdMillicents.toString(),
                        String.format(
                            Locale.US,
                            "%.5f",
                            entry.computedCostUsdMillicents.toDouble() / MILLICENTS_PER_DOLLAR,
                        ),
                    ).joinToString(","),
                )
            }
        }
    }

    private fun csvField(s: String): String {
        val needsQuoting = s.contains(',') || s.contains('"') || s.contains('\n') || s.contains('\r')
        if (!needsQuoting) return s
        return "\"" + s.replace("\"", "\"\"") + "\""
    }
}
