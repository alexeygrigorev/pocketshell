package com.pocketshell.app.crash

import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Packs all local app reports (crash reports + any diagnostics the app
 * writes under the reports directory) into a single zip archive for the
 * "Share all reports" action (issue #466).
 *
 * Pure file-system logic so it is unit-testable without an emulator: it
 * enumerates the report files, writes them into one zip, and returns the
 * archive [File]. The transport (SCP/SFTP to `~/inbox/pocketshell/`) is
 * the existing share path's job — this object only produces the bundle.
 *
 * The archive filename is `pocketshell-reports-<deviceLabel>-<timestamp>.zip`.
 * The timestamp is sourced from an injected [Clock] so callers (and tests)
 * stay deterministic — we never call `System.currentTimeMillis()` /
 * `Date()` directly here.
 */
object ReportsArchive {

    private val TimestampFormatter: DateTimeFormatter = DateTimeFormatter
        .ofPattern("yyyyMMdd-HHmmss")
        .withZone(ZoneOffset.UTC)

    /**
     * Build the share filename for the bundle. [deviceLabel] is sanitised
     * to a filename-safe slug so a device model with spaces/punctuation
     * cannot break the remote path; the timestamp comes from [clock].
     */
    fun archiveFileName(
        deviceLabel: String,
        clock: Clock = Clock.systemUTC(),
    ): String {
        val slug = deviceLabel.slugifyForFilename().ifBlank { "device" }
        val stamp = TimestampFormatter.format(Instant.now(clock))
        return "pocketshell-reports-$slug-$stamp.zip"
    }

    /**
     * Zip every file in [reportFiles] into [destination]. Each entry is
     * named by its source filename; duplicate names are de-duplicated with
     * a numeric suffix so a collision cannot drop a report silently.
     *
     * Returns [destination] for call-site chaining. The caller owns the
     * lifecycle of [destination] (e.g. deleting it from the cache dir after
     * the upload completes).
     */
    fun packInto(reportFiles: List<File>, destination: File): File {
        destination.parentFile?.mkdirs()
        val usedNames = HashSet<String>()
        ZipOutputStream(destination.outputStream().buffered()).use { zip ->
            for (file in reportFiles) {
                if (!file.isFile) continue
                val entryName = uniqueEntryName(file.name, usedNames)
                zip.putNextEntry(ZipEntry(entryName))
                file.inputStream().use { input -> input.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return destination
    }

    private fun uniqueEntryName(name: String, used: MutableSet<String>): String {
        if (used.add(name)) return name
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var index = 1
        while (true) {
            val candidate = "$stem-$index$ext"
            if (used.add(candidate)) return candidate
            index += 1
        }
    }

    private fun String.slugifyForFilename(): String =
        map { ch ->
            when {
                ch in 'A'..'Z' -> ch
                ch in 'a'..'z' -> ch
                ch in '0'..'9' -> ch
                else -> '-'
            }
        }.joinToString("")
            .replace(Regex("-+"), "-")
            .trim('-')
}
