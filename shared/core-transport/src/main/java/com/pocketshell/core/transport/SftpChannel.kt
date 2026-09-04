package com.pocketshell.core.transport

import java.io.IOException

/**
 * File operations over one [HostConnection]. The method set is exactly what
 * the file explorer / file viewer need — no resumable upload, no recursive
 * copy; add a verb here only when a screen actually calls it.
 */
interface SftpChannel {
    /** Directory listing of [path], excluding `.` and `..`. */
    suspend fun list(path: String): List<SftpEntry>

    /** Metadata for [path], or `null` when it does not exist. */
    suspend fun stat(path: String): SftpEntry?

    /**
     * Reads [path] whole. Throws [SftpFileTooLargeException] rather than
     * truncating when the file is larger than [maxBytes] — callers show a
     * "too large to open" state, they never render a silently clipped file.
     */
    suspend fun read(path: String, maxBytes: Long): ByteArray

    /** Writes [bytes] to [path], creating or replacing it. */
    suspend fun write(path: String, bytes: ByteArray)

    suspend fun mkdir(path: String)

    suspend fun rename(from: String, to: String)

    suspend fun delete(path: String)
}

/**
 * [SftpChannel.read] refused [path] because it holds more than [maxBytes].
 *
 * A distinct type (still an [IOException], so ordinary error handling keeps
 * working) so a viewer can show "too large to open" instead of a generic
 * transfer failure. [sizeBytes] is the best size known when the read was
 * refused: the size the server declared, or — when a host that under-reports
 * size (`/proc`, some FUSE mounts) blew past the cap mid-stream — the number of
 * bytes already delivered. Either way it is strictly greater than [maxBytes].
 */
class SftpFileTooLargeException(
    val path: String,
    val sizeBytes: Long,
    val maxBytes: Long,
) : IOException("$path is $sizeBytes bytes, over the $maxBytes byte limit")

/**
 * One remote directory entry. [path] is the absolute remote path;
 * [modifiedEpochMs] is the mtime in Unix epoch milliseconds (0 when the server
 * did not report one).
 */
data class SftpEntry(
    val path: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val modifiedEpochMs: Long,
) {
    /** Last path segment, i.e. the display name. */
    val name: String
        get() = path.substringAfterLast('/').ifEmpty { path }
}
