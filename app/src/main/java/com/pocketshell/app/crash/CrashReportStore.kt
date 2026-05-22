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
        )
        file.writeText(body)
        return CrashReport(
            id = file.nameWithoutExtension,
            timestamp = timestamp,
            file = file,
            summary = CrashReportFormatter.summary(throwable),
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
