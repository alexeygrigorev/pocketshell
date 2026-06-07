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

    private fun trimToLimit() {
        if (maxBytes <= 0L || logFile.length() <= maxBytes) return
        val bytes = logFile.readBytes()
        val keep = bytes.copyOfRange(
            (bytes.size - maxBytes.toInt()).coerceAtLeast(0),
            bytes.size,
        )
        val firstNewline = keep.indexOf('\n'.code.toByte())
        val trimmed = if (firstNewline >= 0 && firstNewline + 1 < keep.size) {
            keep.copyOfRange(firstNewline + 1, keep.size)
        } else {
            keep
        }
        logFile.writeBytes(trimmed)
    }

    private fun exportFileName(deviceLabel: String): String {
        val timestamp = ExportTimestampFormatter.format(clock.instant())
        val safeDevice = deviceLabel
            .lowercase()
            .replace(Regex("[^a-z0-9._-]+"), "-")
            .trim('-')
            .ifBlank { "device" }
            .take(40)
        return "pocketshell-diagnostics-$safeDevice-$timestamp.log"
    }

    companion object {
        const val DEFAULT_MAX_BYTES: Long = 256L * 1024L

        private val ExportTimestampFormatter: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                .withZone(ZoneOffset.UTC)
    }
}
