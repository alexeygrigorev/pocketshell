package com.pocketshell.app.costs

import com.pocketshell.core.storage.entity.AiApiCallEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Unit tests for [CostFormat] (issue #181). The formatters drive both
 * the screen rendering and the CSV export, so a wrong-by-one in any of
 * the rounding paths would surface immediately on the costs surface.
 */
class CostFormatTest {

    @Test
    fun formatUsd_zero_renders_dollars_cents() {
        assertEquals("$0.00", CostFormat.formatUsd(0L))
    }

    @Test
    fun formatUsd_sub_cent_renders_four_decimals() {
        // 1 cent = 1000 millicents. A typical Whisper call (5 audio seconds)
        // is 50 millicents = $0.0005, well below the $0.01 boundary.
        // Four decimals keep that visible without rounding to $0.000.
        assertEquals("$0.0010", CostFormat.formatUsd(100L))
        assertEquals("$0.0050", CostFormat.formatUsd(500L))
        assertEquals("$0.0005", CostFormat.formatUsd(50L))
        assertEquals("$0.0003", CostFormat.formatUsd(30L))
    }

    @Test
    fun formatUsd_above_cent_renders_two_decimals() {
        // 1500 millicents = $0.015 → rounds to $0.02 with %.2f.
        assertEquals("$0.02", CostFormat.formatUsd(1_500L))
        assertEquals("$1.23", CostFormat.formatUsd(123_000L))
        assertEquals("$10.00", CostFormat.formatUsd(1_000_000L))
    }

    @Test
    fun featureLabel_known_pair_pretty_prints() {
        assertEquals("OpenAI · Whisper", CostFormat.featureLabel("openai", "whisper"))
    }

    @Test
    fun featureLabel_unknown_pair_falls_back() {
        assertEquals("anthropic · claude-3", CostFormat.featureLabel("anthropic", "claude-3"))
    }

    @Test
    fun csv_export_contains_header_and_row() {
        val entry = AiApiCallEntry(
            id = 1L,
            timestampMillis = 1_700_000_000_000L,
            provider = "openai",
            feature = "whisper",
            inputUnits = 12,
            outputUnits = 84,
            unitCostUsdMillicents = 10,
            computedCostUsdMillicents = 120,
            metadataJson = null,
        )

        val csv = CostFormat.toCsv(listOf(entry), zone = ZoneOffset.UTC)
        val lines = csv.lines()
        // Header + 1 row + trailing newline.
        assertTrue("expected header line, got: ${lines.firstOrNull()}", lines[0].startsWith("timestamp_iso,"))
        // The header lists nine columns.
        assertEquals(9, lines[0].split(',').size)
        // Row contains the millicent cost and the dollar projection.
        assertTrue(lines[1].contains(",120,"))
        assertTrue("expected dollar projection, got: ${lines[1]}", lines[1].contains(",0.00120"))
        // Original provider/feature values present.
        assertTrue(lines[1].contains("openai"))
        assertTrue(lines[1].contains("whisper"))
    }

    @Test
    fun csv_export_quotes_strings_with_commas() {
        val entry = AiApiCallEntry(
            id = 1L,
            timestampMillis = 1_700_000_000_000L,
            provider = "comma,provider",
            feature = "whisper",
            inputUnits = 1,
            outputUnits = 1,
            unitCostUsdMillicents = 10,
            computedCostUsdMillicents = 10,
            metadataJson = null,
        )
        val csv = CostFormat.toCsv(listOf(entry), zone = ZoneOffset.UTC)
        assertTrue(
            "expected provider to be CSV-quoted, got: $csv",
            csv.contains("\"comma,provider\""),
        )
    }

    @Test
    fun csv_export_handles_empty_list() {
        val csv = CostFormat.toCsv(emptyList())
        assertTrue("empty export must still have header, got: $csv", csv.startsWith("timestamp_iso,"))
        // Only one line (header) plus trailing newline.
        assertFalse(csv.lines().any { it.contains("openai") })
    }

    @Test
    fun formatCallRow_renders_compact_summary() {
        val entry = AiApiCallEntry(
            id = 1L,
            timestampMillis = 1_700_000_000_000L,
            provider = "openai",
            feature = "whisper",
            inputUnits = 12,
            outputUnits = 84,
            unitCostUsdMillicents = 10,
            computedCostUsdMillicents = 120,
            metadataJson = null,
        )
        val row = CostFormat.formatCallRow(entry, zone = ZoneId.of("UTC"))
        // Date prefix.
        assertTrue("expected ISO date, got: $row", row.startsWith("2023-"))
        assertTrue(row.contains("OpenAI · Whisper"))
        assertTrue(row.contains("12 audio seconds"))
        assertTrue(row.contains("$0.0012"))
    }
}
