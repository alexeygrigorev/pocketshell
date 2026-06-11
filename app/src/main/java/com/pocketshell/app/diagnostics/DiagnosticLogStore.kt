package com.pocketshell.app.diagnostics

import org.json.JSONObject
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

internal class DiagnosticLogStore(
    private val logFile: File,
    private val exportDirectory: File,
    private val clock: Clock = Clock.systemUTC(),
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
    private val maxEvents: Int = DEFAULT_MAX_EVENTS,
) {
    fun appendLine(line: String) {
        logFile.parentFile?.mkdirs()
        logFile.appendText(line + "\n")
        trimToLimit()
    }

    fun clear() {
        if (logFile.exists()) {
            logFile.delete()
        }
    }

    fun exportSnapshot(
        deviceLabel: String = "device",
        appVersion: String = "unknown",
        filter: DiagnosticEventFilter = DiagnosticEventFilter.All,
    ): File? {
        if (!logFile.isFile || logFile.length() == 0L) return null
        val eventLines = readLines(filter)
        if (eventLines.isEmpty()) return null
        val events = eventLines.mapNotNull(DiagnosticEventJson::decode)
        exportDirectory.mkdirs()
        val file = File(exportDirectory, exportFileName(deviceLabel))
        // Prepend a single-line export_summary header so an agent can orient
        // with one `head -1` (counts, sequence/time range, app version, device)
        // before reading the rest of the JSONL window. (#549 slice d)
        val summaryLine = DiagnosticExportSummary.headerLine(
            events = events,
            deviceLabel = deviceLabel,
            appVersion = appVersion,
            generatedAt = clock.instant(),
        )
        file.writeText(
            (listOf(summaryLine) + eventLines).joinToString(separator = "\n", postfix = "\n"),
        )
        return file
    }

    fun readText(): String = if (logFile.isFile) logFile.readText() else ""

    fun readEvents(filter: DiagnosticEventFilter = DiagnosticEventFilter.All): List<DiagnosticsEvent> =
        if (!logFile.isFile) {
            emptyList()
        } else {
            filter.limit(
                logFile.readLines()
                    .mapNotNull(DiagnosticEventJson::decode)
                    .filter(filter::matches),
            )
        }

    fun lastSequence(): Long = readEvents().maxOfOrNull { it.sequence } ?: 0L

    private fun readLines(filter: DiagnosticEventFilter): List<String> =
        filter.limitLines(
            logFile.readLines().mapNotNull { line ->
                val event = DiagnosticEventJson.decode(line) ?: return@mapNotNull null
                if (filter.matches(event)) line to event else null
            },
        ).map { (line, _) -> line }

    private fun trimToLimit() {
        if (!logFile.isFile) return
        val lines = logFile.readLines()
        val eventBounded = if (maxEvents > 0 && lines.size > maxEvents) {
            lines.takeLast(maxEvents)
        } else {
            lines
        }
        if (maxBytes <= 0L && eventBounded === lines) return
        var bounded = eventBounded
        if (maxBytes > 0L) {
            while (bounded.isNotEmpty() && bounded.joinToString(separator = "\n", postfix = "\n").toByteArray().size > maxBytes) {
                bounded = bounded.drop(1)
            }
        }
        if (bounded !== lines) {
            logFile.writeText(bounded.joinToString(separator = "\n", postfix = if (bounded.isEmpty()) "" else "\n"))
        }
    }

    private fun exportFileName(deviceLabel: String): String {
        val timestamp = ExportTimestampFormatter.format(clock.instant())
        val safeDevice = deviceLabel
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "device" }
            .take(40)
        return "pocketshell-diagnostics-$safeDevice-$timestamp.jsonl"
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 512L * 1024L
        const val DEFAULT_MAX_EVENTS: Int = 2_000

        private val ExportTimestampFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
    }
}

/**
 * Builds the single `export_summary` NDJSON header line prepended to a
 * diagnostics export (#549 slice d). It is shaped exactly like a normal
 * diagnostics event (`category`/`name`/`metadata`) so `jq`/`rg`/`tail` and the
 * existing [DiagnosticEventJson.decode] reader treat it as one more line, while
 * giving an agent a one-`head -1` index of the exported window: event count,
 * sequence range, wall-clock range/span, per-category counts, app version, and
 * device.
 */
internal object DiagnosticExportSummary {
    const val CATEGORY = "diagnostics"
    const val NAME = "export_summary"

    fun headerLine(
        events: List<DiagnosticsEvent>,
        deviceLabel: String,
        appVersion: String,
        generatedAt: Instant,
    ): String {
        val sequences = events.map { it.sequence }
        val wallClocks = events.map { it.wallClockTime }
        val firstWall = wallClocks.minOrNull()
        val lastWall = wallClocks.maxOrNull()
        val categories = JSONObject()
        events.groupingBy { it.category }.eachCount()
            .toSortedMap()
            .forEach { (category, count) -> categories.put(category, count) }

        val metadata = JSONObject()
            .put("events", events.size)
            .put("firstSeq", sequences.minOrNull() ?: JSONObject.NULL)
            .put("lastSeq", sequences.maxOrNull() ?: JSONObject.NULL)
            .put(
                "firstWallClock",
                firstWall?.let(DateTimeFormatter.ISO_INSTANT::format) ?: JSONObject.NULL,
            )
            .put(
                "lastWallClock",
                lastWall?.let(DateTimeFormatter.ISO_INSTANT::format) ?: JSONObject.NULL,
            )
            .put(
                "windowMs",
                if (firstWall != null && lastWall != null) {
                    java.time.Duration.between(firstWall, lastWall).toMillis()
                } else {
                    JSONObject.NULL
                },
            )
            .put("categories", categories)
            .put("appVersion", appVersion)
            .put("device", deviceLabel)
            .put("generatedAt", DateTimeFormatter.ISO_INSTANT.format(generatedAt))

        return JSONObject()
            .put("sequence", 0)
            .put("wallClockTime", DateTimeFormatter.ISO_INSTANT.format(generatedAt))
            .put("monotonicTimestampNanos", 0)
            .put("category", CATEGORY)
            .put("name", NAME)
            .put("metadata", metadata)
            .toString()
    }
}
