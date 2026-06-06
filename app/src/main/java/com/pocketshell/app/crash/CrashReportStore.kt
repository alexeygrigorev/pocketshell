package com.pocketshell.app.crash

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class CrashReportStore(
    private val directory: File,
    private val clock: Clock = Clock.systemUTC(),
) {
    fun save(
        throwable: Throwable,
        threadName: String,
        metadata: CrashReportMetadata,
        context: CrashReportContext = CrashReportContext.Unknown,
    ): CrashReport {
        directory.mkdirs()
        val timestamp = Instant.now(clock)
        val id = FileIdFormatter.format(timestamp)
        val file = uniqueReportFile(id)
        val body = CrashReportFormatter.format(
            throwable = throwable,
            threadName = threadName,
            timestamp = timestamp,
            metadata = metadata,
            context = context,
        )
        file.writeText(body)
        return CrashReport(
            id = file.nameWithoutExtension,
            timestamp = timestamp,
            file = file,
            summary = CrashReportFormatter.summary(throwable),
            contextSummary = context.summary(),
            appVersion = metadata.appVersion,
            topFrame = CrashReportFormatter.topFrame(throwable),
        )
    }

    fun list(): List<CrashReport> {
        if (!directory.exists()) return emptyList()
        return directory.listFiles { file ->
            file.isFile && file.extension == ReportExtension
        }.orEmpty()
            .map { file ->
                CrashReport(
                    id = file.nameWithoutExtension,
                    timestamp = parseTimestamp(file.nameWithoutExtension)
                        ?: Instant.ofEpochMilli(file.lastModified()),
                    file = file,
                    summary = firstExceptionLine(file),
                    contextSummary = contextSummary(file),
                    appVersion = firstHeaderValue(file, "App version"),
                    topFrame = firstHeaderValue(file, "Top frame")
                        ?.takeUnless { it.equals("unknown", ignoreCase = true) },
                )
            }
            .sortedByDescending { it.timestamp }
    }

    fun read(report: CrashReport): String = report.file.readText()

    fun delete(report: CrashReport): Boolean = report.file.delete()

    private fun uniqueReportFile(id: String): File {
        var suffix = 0
        while (true) {
            val name = if (suffix == 0) id else "$id-$suffix"
            val file = File(directory, "$name.$ReportExtension")
            if (!file.exists()) return file
            suffix += 1
        }
    }

    private fun firstExceptionLine(file: File): String =
        file.readLines()
            .dropWhile { it != "Exception" }
            .drop(1)
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.toSimpleThrowableName()
            ?: "Crash report"

    private fun contextSummary(file: File): String {
        val lines = file.readLines()
        val screen = firstHeaderValue(lines, "Screen") ?: return "Context unavailable"
        val context = CrashReportContext(
            screen = screen,
            hostName = firstHeaderValue(lines, "Host"),
            hostname = firstHeaderValue(lines, "Hostname"),
            username = firstHeaderValue(lines, "User"),
            sessionName = firstHeaderValue(lines, "Session"),
            startDirectory = firstHeaderValue(lines, "Directory"),
            action = firstHeaderValue(lines, "Action"),
        )
        return context.summary()
    }

    private fun firstHeaderValue(file: File, key: String): String? =
        firstHeaderValue(file.readLines(), key)

    private fun firstHeaderValue(lines: List<String>, key: String): String? {
        val prefix = "$key:"
        return lines.firstOrNull { it.startsWith(prefix) }
            ?.removePrefix(prefix)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun String.toSimpleThrowableName(): String {
        val colonIndex = indexOf(':').takeIf { it >= 0 } ?: length
        val className = take(colonIndex)
        val simpleName = className.substringAfterLast('.')
        return simpleName + drop(colonIndex)
    }

    private fun parseTimestamp(id: String): Instant? =
        runCatching {
            val timestampPart = if (id.length > FileIdLength) id.take(FileIdLength) else id
            LocalDateTime.parse(timestampPart, FileIdFormatter)
                .toInstant(ZoneOffset.UTC)
        }.getOrNull()

    private companion object {
        const val ReportExtension = "txt"
        const val FileIdLength = 19
        val FileIdFormatter: DateTimeFormatter = DateTimeFormatter
            .ofPattern("yyyyMMdd-HHmmss-SSS")
            .withZone(ZoneOffset.UTC)
    }
}
