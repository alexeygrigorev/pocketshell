package com.pocketshell.app.diagnostics

import java.io.File

/**
 * A LOSSLESS, count-bounded, rotating part-file store for the connection log
 * (issue #1669).
 *
 * ## Why this exists
 *
 * The field connection log is how a connection-manager storm gets reproduced
 * locally from real device usage. Today's storm forensics recovered only ~187 of
 * ~1,660 sequenced events from 200+ `connection-log.jsonl.part-*` files — dozens
 * of them ZERO-LENGTH. The loss had two causes:
 *
 *  1. The host mirror is a **snapshot-overwrite** of a single, byte-capped file:
 *     each transport-up re-uploads only the newest ~64KB window, so older events
 *     fall off the host before anyone reads them.
 *  2. Each upload creates a `<final>.part-<rand>` temp sibling (the #930 atomic
 *     upload), and a drop mid-transfer strands it. Under a storm the strays pile
 *     up as zero-length `.part-*` zombies that a forensic scrape then has to sift.
 *
 * This store is the durable, on-device archive the mirror renders FROM, so no
 * event is dropped before the upload's budget cap. It is a classic rotating
 * append log:
 *
 *  - [append] writes each line to the CURRENT part file (`…part-000001`); when a
 *    part reaches [maxLinesPerPart] it rotates to the next index. A part file is
 *    created lazily on its FIRST line, so a **zero-length part can never exist** —
 *    the exact failure mode the field hit.
 *  - [compact] folds the OLDEST parts into a single `base` file and deletes them,
 *    bounding the on-disk part count to [maxParts]. Folding preserves every line
 *    (append then delete), so compaction is lossless.
 *  - [readAllLines] returns `base` followed by every part in index order —
 *    exactly what was appended, in order.
 *
 * ## Concurrency / losslessness contract
 *
 * All mutating and reading operations serialize on a single [lock]. The
 * production writer (the [DiagnosticRecorder] channel consumer) is already
 * single-threaded, but the store is also driven concurrently by its stress test,
 * so N appends from any number of threads read back as exactly N lines — never a
 * torn line, never a dropped line, never a zero-length part.
 */
internal class ConnectionLogPartStore(
    private val directory: File,
    private val baseName: String = DEFAULT_BASE_NAME,
    private val maxLinesPerPart: Int = DEFAULT_MAX_LINES_PER_PART,
    private val maxParts: Int = DEFAULT_MAX_PARTS,
) {
    init {
        require(maxLinesPerPart > 0) { "maxLinesPerPart must be positive" }
        require(maxParts > 0) { "maxParts must be positive" }
    }

    private val lock = Any()

    // Declared BEFORE the recovery init block below so it is initialised in
    // Kotlin's top-to-bottom order by the time that init scans the directory
    // (a val declared after the init block would still be null inside it).
    private val partRegex: Regex = Regex(Regex.escape(baseName) + "\\.part-(\\d+)")

    // Index (>= 1) of the newest part file, or 0 when none exist yet.
    private var currentIndex: Int = 0

    // Line count of the newest part file; drives rotation.
    private var currentLineCount: Int = 0

    init {
        // Recover in-memory rotation state from any parts left by a prior process
        // so appends continue the newest part instead of clobbering it.
        synchronized(lock) {
            val parts = partFilesLocked()
            val newest = parts.maxByOrNull { indexOf(it) }
            if (newest != null) {
                currentIndex = indexOf(newest)
                currentLineCount = newest.readNonEmptyLineCount()
            }
        }
    }

    /** Append one JSONL [line] durably. Rotates + compacts as needed. */
    fun append(line: String) = synchronized(lock) {
        if (currentIndex == 0 || currentLineCount >= maxLinesPerPart) {
            currentIndex += 1
            currentLineCount = 0
        }
        val part = partFile(currentIndex)
        part.parentFile?.mkdirs()
        // Append the whole line + newline in one write so a concurrent reader
        // never observes a torn line. Creating the part here (append mode) with a
        // non-empty write is what guarantees no zero-length part ever exists.
        part.appendText(line + "\n")
        currentLineCount += 1
        if (partFilesLocked().size > maxParts) {
            compactLocked()
        }
    }

    /** Every line ever appended (and not lost — there is no loss), in order. */
    fun readAllLines(): List<String> = synchronized(lock) {
        val base = baseFile()
        val baseLines = if (base.isFile) base.readNonEmptyLines() else emptyList()
        baseLines + partFilesLocked().flatMap { it.readNonEmptyLines() }
    }

    /** Current on-disk part-file count (excludes the folded [baseFile]). */
    fun partCount(): Int = synchronized(lock) { partFilesLocked().size }

    /**
     * Fold the oldest parts into [baseFile] until at most [maxParts] parts remain.
     * Lossless: each folded part's lines are appended to base, then the part is
     * deleted, so [readAllLines] is unchanged across a compaction.
     */
    fun compact() = synchronized(lock) { compactLocked() }

    /** Delete every part + base file (used by the recorder's `clear`). */
    fun clear() = synchronized(lock) {
        baseFile().delete()
        partFilesLocked().forEach { it.delete() }
        currentIndex = 0
        currentLineCount = 0
    }

    private fun compactLocked() {
        val parts = partFilesLocked().sortedBy { indexOf(it) }
        if (parts.size <= maxParts) return
        val base = baseFile()
        base.parentFile?.mkdirs()
        // Fold all but the newest [maxParts] parts into base, oldest first.
        val toFold = parts.dropLast(maxParts)
        for (part in toFold) {
            val lines = part.readNonEmptyLines()
            if (lines.isNotEmpty()) {
                base.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
            }
            part.delete()
        }
    }

    private fun baseFile(): File = File(directory, baseName)

    private fun partFile(index: Int): File =
        File(directory, "$baseName.part-" + index.toString().padStart(PART_INDEX_DIGITS, '0'))

    private fun partFilesLocked(): List<File> =
        (directory.listFiles() ?: emptyArray())
            .filter { it.isFile && partRegex.matches(it.name) }
            .sortedBy { indexOf(it) }

    private fun indexOf(file: File): Int =
        partRegex.matchEntire(file.name)?.groupValues?.get(1)?.toIntOrNull() ?: 0

    private fun File.readNonEmptyLines(): List<String> =
        if (isFile) readLines().filter { it.isNotEmpty() } else emptyList()

    private fun File.readNonEmptyLineCount(): Int =
        if (isFile) readLines().count { it.isNotEmpty() } else 0

    companion object {
        const val DEFAULT_BASE_NAME: String = "connection-log.jsonl"

        /**
         * Lines per part before rotation. Sized so a busy storm minute (hundreds
         * of events) spans a handful of parts, not one file per event.
         */
        const val DEFAULT_MAX_LINES_PER_PART: Int = 500

        /**
         * On-disk part-file ceiling. Older parts are folded into the base file, so
         * the directory never accumulates the 200+ parts the field forensics found.
         */
        const val DEFAULT_MAX_PARTS: Int = 8

        private const val PART_INDEX_DIGITS = 6
    }
}
