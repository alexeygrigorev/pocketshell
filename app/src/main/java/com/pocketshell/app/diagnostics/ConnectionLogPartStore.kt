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
 *
 * Because every operation holds that one lock, whatever the lock holder does is
 * paid by every other writer. The set of live part files is therefore tracked
 * **in memory** ([partFiles]) and the directory is scanned exactly ONCE, at
 * construction, to recover what a previous process left behind. Re-deriving the
 * part list from disk inside [append] made the hot path O(parts) — quadratic
 * over a run — and convoyed 16 writers on the lock hard enough to wedge a
 * full-JVM gate for over an hour (issue #2147).
 *
 * ## The tracked set may never claim health the disk does not have
 *
 * Tracking the parts in memory means [partCount] and [readAllLines] answer from
 * the cache. That is only sound while the cache and the directory agree, so the
 * ONE operation that can disagree — deleting a folded part — is checked: a part
 * leaves [partFiles] only when it is genuinely gone from disk. A part whose
 * `delete()` did not remove it moves to [undeletedFoldedParts], where it still
 * counts against [partCount] (it is still occupying the on-disk part budget) and
 * is retried on the next compaction. Popping it regardless would let the store
 * report "bounded to [maxParts]" while the directory grew without limit —
 * exactly the #1669 field failure (200+ stranded `.part-*` files) this class
 * exists to prevent, only now invisible.
 *
 * The single construction-time scan is also the store's whole view of the
 * directory: a FOREIGN writer that drops a `…part-*` file in afterwards is out
 * of contract and stays unseen. Production has one store per directory (the
 * `@Singleton` [DiagnosticRecorder] owns both), so there is no second writer.
 */
internal class ConnectionLogPartStore(
    private val directory: File,
    private val baseName: String = DEFAULT_BASE_NAME,
    private val maxLinesPerPart: Int = DEFAULT_MAX_LINES_PER_PART,
    private val maxParts: Int = DEFAULT_MAX_PARTS,
    /**
     * Optional drop-oldest ceiling for the folded base file. The ordinary
     * connection log leaves this null to preserve #1669 losslessness; the much
     * chattier replay journal opts in so months of dogfooding cannot grow the
     * compacted base without bound (#1709).
     */
    private val maxBaseBytes: Long? = null,
) {
    init {
        require(maxLinesPerPart > 0) { "maxLinesPerPart must be positive" }
        require(maxParts > 0) { "maxParts must be positive" }
        require(maxBaseBytes == null || maxBaseBytes > 0L) { "maxBaseBytes must be positive when set" }
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

    /**
     * Every live part file, oldest index first — the authoritative in-memory view
     * of what [partFilesLocked] used to re-derive from disk on every call.
     * Seeded once by the recovery scan below, then maintained by exactly two
     * mutations: [append] adds a part the moment its first line creates it, and
     * [compactLocked] / [clear] drop the parts they delete.
     */
    private val partFiles = ArrayDeque<File>()

    /**
     * Parts already folded into [baseFile] whose file survived its `delete()`.
     * Their lines live in base, so they are NOT read back (that would duplicate
     * them) and NOT folded again (that would grow base without bound) — but the
     * file is still on disk, so it still counts towards [partCount] and its
     * delete is retried at the start of every [compactLocked]. Empty in every
     * healthy run; non-empty is the store reporting the truth about its
     * directory instead of hiding a stranded part behind a healthy cache.
     */
    private val undeletedFoldedParts = ArrayDeque<File>()

    init {
        // Recover in-memory rotation state from any parts left by a prior process
        // so appends continue the newest part instead of clobbering it. This is
        // THE ONLY directory scan the store ever performs.
        synchronized(lock) {
            (directory.listFiles() ?: emptyArray())
                .filter { it.isFile && partRegex.matches(it.name) }
                .sortedBy { indexOf(it) }
                .forEach { partFiles.addLast(it) }
            val newest = partFiles.lastOrNull()
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
        if (partFiles.lastOrNull() != part) {
            // The write above is what CREATED this part (append mode, non-empty),
            // so this is the earliest moment it may join the tracked set — which
            // is what keeps a zero-length part unrepresentable. The guard also
            // covers the re-open case where recovery already tracked this part.
            partFiles.addLast(part)
        }
        currentLineCount += 1
        if (partFiles.size > maxParts) {
            compactLocked()
        }
    }

    /** Every line ever appended (and not lost — there is no loss), in order. */
    fun readAllLines(): List<String> = synchronized(lock) {
        val base = baseFile()
        val baseLines = if (base.isFile) base.readNonEmptyLines() else emptyList()
        baseLines + partFilesLocked().flatMap { it.readNonEmptyLines() }
    }

    /**
     * Current on-disk part-file count (excludes the folded [baseFile]).
     *
     * Counts the folded-but-undeleted parts too: they are still files in the
     * directory, and a count that omitted them would understate the on-disk
     * archive — the cache lying about the disk (issue #2147).
     */
    fun partCount(): Int = synchronized(lock) { partFilesLocked().size + undeletedFoldedParts.size }

    /**
     * Fold the oldest parts into [baseFile] until at most [maxParts] parts remain.
     * Lossless: each folded part's lines are appended to base, then the part is
     * deleted, so [readAllLines] is unchanged across a compaction.
     */
    fun compact() = synchronized(lock) { compactLocked() }

    /** Delete every part + base file (used by the recorder's `clear`). */
    fun clear() = synchronized(lock) {
        baseFile().delete()
        // Anything that survives its delete is STILL on disk holding lines, and
        // base is gone, so it stays tracked as a live part rather than vanishing
        // into a cleared cache. Healthy runs leave `survivors` empty.
        val survivors = (partFiles + undeletedFoldedParts)
            .filter { part ->
                part.delete()
                part.exists()
            }
            .sortedBy { indexOf(it) }
        partFiles.clear()
        undeletedFoldedParts.clear()
        survivors.forEach { partFiles.addLast(it) }
        val newest = partFiles.lastOrNull()
        currentIndex = if (newest == null) 0 else indexOf(newest)
        currentLineCount = newest?.readNonEmptyLineCount() ?: 0
    }

    private fun compactLocked() {
        // Retry first: a part that survived an earlier delete is still spending
        // the on-disk part budget, so clearing it takes priority over folding a
        // new one. Retrying AFTER the fold would let a permanently undeletable
        // part be masked by a successful delete in the same pass.
        retryUndeletedFoldedPartsLocked()
        if (partFiles.size > maxParts) {
            val base = baseFile()
            base.parentFile?.mkdirs()
            // Fold all but the newest [maxParts] parts into base, oldest first.
            repeat(partFiles.size - maxParts) {
                val part = partFiles.removeFirst()
                val lines = part.readNonEmptyLines()
                if (lines.isNotEmpty()) {
                    base.appendText(lines.joinToString(separator = "\n", postfix = "\n"))
                }
                part.delete()
                if (part.exists()) {
                    // Folded but not removed. One stat per fold, O(1) — it is what
                    // keeps [partCount] equal to the real on-disk part count when a
                    // delete fails, instead of the cache silently reporting a bound
                    // the directory does not honour (issue #2147).
                    undeletedFoldedParts.addLast(part)
                }
            }
        }
        trimBaseToByteCapLocked()
    }

    /** Re-attempt the deletes that previously failed; drop the ones now gone. */
    private fun retryUndeletedFoldedPartsLocked() {
        if (undeletedFoldedParts.isEmpty()) return
        val iterator = undeletedFoldedParts.iterator()
        while (iterator.hasNext()) {
            val part = iterator.next()
            part.delete()
            if (!part.exists()) iterator.remove()
        }
    }

    /** Keep the newest complete JSONL lines whose UTF-8 bytes fit the cap. */
    private fun trimBaseToByteCapLocked() {
        val cap = maxBaseBytes ?: return
        val base = baseFile()
        if (!base.isFile || base.length() <= cap) return
        val kept = ArrayDeque<String>()
        var bytes = 0L
        for (line in base.readNonEmptyLines().asReversed()) {
            val cost = line.toByteArray(Charsets.UTF_8).size.toLong() + 1L
            if (cost > cap) continue
            if (bytes + cost > cap) break
            kept.addFirst(line)
            bytes += cost
        }
        base.writeText(
            if (kept.isEmpty()) "" else kept.joinToString(separator = "\n", postfix = "\n"),
        )
    }

    private fun baseFile(): File = File(directory, baseName)

    private fun partFile(index: Int): File =
        File(directory, "$baseName.part-" + index.toString().padStart(PART_INDEX_DIGITS, '0'))

    /**
     * The live parts in index order. Reads the tracked set — it must NEVER go
     * back to the filesystem: this is called from under [lock], and the O(parts)
     * `listFiles` + `isFile` + regex + sort it used to run is the convoy that
     * wedged a full-JVM gate for over an hour (issue #2147).
     */
    private fun partFilesLocked(): List<File> = partFiles.toList()

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
