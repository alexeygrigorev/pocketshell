package com.pocketshell.app.diagnostics

import java.io.File
import java.time.Clock
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

    fun exportSnapshot(deviceLabel: String = "device"): File? {
        if (!logFile.isFile || logFile.length() == 0L) return null
        exportDirectory.mkdirs()
        val file = File(exportDirectory, exportFileName(deviceLabel))
        logFile.copyTo(file, overwrite = true)
        return file
    }

    fun readText(): String = if (logFile.isFile) logFile.readText() else ""

    fun readEvents(): List<DiagnosticsEvent> =
        if (!logFile.isFile) {
            emptyList()
        } else {
            logFile.readLines().mapNotNull(DiagnosticEventJson::decode)
        }

    fun lastSequence(): Long = readEvents().maxOfOrNull { it.sequence } ?: 0L

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
