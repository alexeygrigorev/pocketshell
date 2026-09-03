package com.pocketshell.next.composer

import com.pocketshell.core.transport.SftpChannel
import com.pocketshell.core.transport.SftpEntry

/**
 * Keeps the host's composer-attachment directory from growing forever (rewrite
 * task P-1, ported from the old client's `AttachmentRetentionPolicy`).
 *
 * Every attached screenshot is a file left on the dev box. Nobody goes and
 * deletes them, so without a sweep the directory accumulates every image the
 * maintainer ever attached. After a successful stage this prunes that session's
 * directory to the newest [keepNewest] files, dropping anything older than
 * [ttlMillis] as well.
 *
 * Two guards keep it from deleting something still in use:
 *
 *  - [protectNewestMillis] — a file younger than this is never touched,
 *    whatever the count says. The files this run just uploaded are the newest
 *    things in the directory and are about to be referenced by a message that
 *    has not been sent yet.
 *  - the plan is computed from a listing and applied file-by-file with failures
 *    swallowed: pruning is housekeeping, and a directory the account cannot
 *    write must never turn a successful attach into a failed one.
 *
 * The port's only change is the type it reads: [SftpEntry] (with millisecond
 * mtimes) instead of the old client's `RemoteEntry` (seconds).
 */
data class AttachmentRetentionPolicy(
    val ttlMillis: Long = DEFAULT_TTL_MILLIS,
    val keepNewest: Int = DEFAULT_KEEP_NEWEST,
    val protectNewestMillis: Long = DEFAULT_PROTECT_NEWEST_MILLIS,
) {
    init {
        require(ttlMillis > 0L) { "ttlMillis must be positive" }
        require(keepNewest > 0) { "keepNewest must be positive" }
        require(protectNewestMillis >= 0L) { "protectNewestMillis must be non-negative" }
    }

    /**
     * Which of [entries] should go, newest-first ordering applied first.
     *
     * Pure, so the "would this delete something it should not" question is
     * answered by a host-JVM test rather than by watching a real directory.
     * Entries with no reported mtime are skipped entirely: a file whose age is
     * unknown cannot be shown to be expired.
     */
    fun plan(entries: List<SftpEntry>, nowMillis: Long): List<SftpEntry> {
        val files = entries
            .filter { !it.isDirectory && it.modifiedEpochMs > 0L }
            .sortedWith(compareByDescending<SftpEntry> { it.modifiedEpochMs }.thenBy { it.name })
        return files.filterIndexed { index, entry ->
            val age = nowMillis - entry.modifiedEpochMs
            when {
                age < protectNewestMillis -> false
                else -> age >= ttlMillis || index >= keepNewest
            }
        }
    }

    /** Lists [remoteDir], applies [plan], and deletes what it names. Never throws. */
    suspend fun prune(sftp: SftpChannel, remoteDir: String, nowMillis: Long) {
        val entries = runCatching { sftp.list(remoteDir) }.getOrNull() ?: return
        plan(entries, nowMillis).forEach { entry ->
            runCatching { sftp.delete(entry.path) }
        }
    }

    companion object {
        /** A fortnight: long enough that a prompt referencing a file still resolves. */
        const val DEFAULT_TTL_MILLIS: Long = 14L * 24 * 60 * 60 * 1000

        /** Files kept regardless of age — a session's recent working set. */
        const val DEFAULT_KEEP_NEWEST: Int = 40

        /** Nothing younger than an hour is ever pruned, including this send's own files. */
        const val DEFAULT_PROTECT_NEWEST_MILLIS: Long = 60L * 60 * 1000
    }
}
