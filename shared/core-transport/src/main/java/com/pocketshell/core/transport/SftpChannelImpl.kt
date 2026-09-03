package com.pocketshell.core.transport

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.FileAttributes
import net.schmizz.sshj.sftp.FileMode
import net.schmizz.sshj.sftp.OpenMode
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.RemoteResourceInfo
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.sftp.SFTPException
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.EnumSet

/**
 * The sshj-backed [SftpChannel] (rewrite task T-4).
 *
 * One instance per [RealHostConnection] — `HostConnection.sftp()` hands out the
 * same object every time, and that object opens the remote SFTP *subsystem*
 * lazily, once, on the first call that needs it. Opening a fresh subsystem per
 * file operation would cost a channel round-trip each time a file browser
 * listed a directory.
 *
 * Design notes:
 * - Every call is serialised on [mutex]. The file screens issue one operation at
 *   a time, so serialising costs nothing and removes any question about
 *   concurrent use of a single sshj `SFTPEngine`. It also makes the lazy
 *   subsystem open race-free.
 * - Blocking sshj work runs on [ioDispatcher] inside [runInterruptible], so a
 *   cancelled caller interrupts the parked request instead of leaking it.
 * - [read] never truncates: it rejects an over-size file with
 *   [SftpFileTooLargeException] both up front (declared size) and mid-stream
 *   (bytes actually delivered), so a host that reports size 0 for a file with
 *   content — `/proc` entries, some FUSE mounts — cannot slip past the cap.
 * - There is no `close()`: the channel dies with its connection.
 *   [RealHostConnection.close] disconnects the transport, which tears down the
 *   SFTP subsystem channel with it, and a spent connection is never reused
 *   (see [HostConnection]).
 */
internal class SftpChannelImpl(
    private val client: SSHClient,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : SftpChannel {

    private val mutex = Mutex()

    /** Opened on first use, then reused; guarded by [mutex]. */
    private var sftp: SFTPClient? = null

    override suspend fun list(path: String): List<SftpEntry> = withSftp("list $path") { sftp ->
        // sshj's directory scan already drops "." and "..".
        sftp.ls(path).map { it.toEntry() }
    }

    override suspend fun stat(path: String): SftpEntry? = withSftp("stat $path") { sftp ->
        // statExistence returns null for NO_SUCH_FILE instead of throwing.
        sftp.statExistence(path)?.let { attributes -> attributes.toEntry(path) }
    }

    override suspend fun read(path: String, maxBytes: Long): ByteArray = withSftp("read $path") { sftp ->
        require(maxBytes >= 0) { "maxBytes must not be negative, was $maxBytes" }
        sftp.open(path, EnumSet.of(OpenMode.READ)).use { file ->
            // Cheap rejection when the server reports a size at all. Not
            // authoritative: pseudo-filesystems report 0 for non-empty files.
            val declaredSize = runCatching { file.length() }.getOrDefault(UNKNOWN_SIZE)
            if (declaredSize > maxBytes) {
                throw SftpFileTooLargeException(path, declaredSize, maxBytes)
            }
            readWithinCap(file, path, maxBytes, declaredSize)
        }
    }

    override suspend fun write(path: String, bytes: ByteArray) = withSftp("write $path") { sftp ->
        // CREAT|TRUNC: create or replace, per the SftpChannel contract.
        sftp.open(path, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.TRUNC)).use { file ->
            var offset = 0L
            while (offset < bytes.size) {
                // Chunked so one huge array cannot exceed the negotiated
                // maximum SFTP packet size.
                val chunk = minOf(TRANSFER_CHUNK_BYTES.toLong(), bytes.size - offset).toInt()
                file.write(offset, bytes, offset.toInt(), chunk)
                offset += chunk
            }
        }
    }

    override suspend fun mkdir(path: String) = withSftp("mkdir $path") { sftp ->
        sftp.mkdir(path)
    }

    override suspend fun rename(from: String, to: String) = withSftp("rename $from -> $to") { sftp ->
        sftp.rename(from, to)
    }

    override suspend fun delete(path: String) = withSftp("delete $path") { sftp ->
        // SFTP has separate remove verbs for files and directories, so the type
        // decides which one to send. A missing path is an error, matching
        // FakeSftpChannel.
        val attributes = sftp.statExistence(path)
            ?: throw IOException("no such path: $path")
        if (attributes.type == FileMode.Type.DIRECTORY) {
            sftp.rmdir(path)
        } else {
            sftp.rm(path)
        }
    }

    // ------------------------------------------------------------- internals

    /**
     * Runs [block] against the (lazily opened) SFTP subsystem on [ioDispatcher],
     * serialised and interruptible. sshj's own failures are re-thrown with the
     * operation and path attached — an `SFTPException` message alone is often
     * just "No such file".
     */
    private suspend fun <T> withSftp(description: String, block: (SFTPClient) -> T): T =
        withContext(ioDispatcher) {
            mutex.withLock {
                runInterruptible {
                    val channel = sftp ?: client.newSFTPClient().also { sftp = it }
                    try {
                        block(channel)
                    } catch (e: SFTPException) {
                        throw IOException("$description: ${e.statusCode} ${e.message}", e)
                    }
                }
            }
        }

    /**
     * Streams [file] into memory, failing with [SftpFileTooLargeException] the
     * moment the delivered bytes pass [maxBytes]. Reading one byte past the cap
     * is what distinguishes "exactly at the limit" from "over it".
     */
    private fun readWithinCap(
        file: RemoteFile,
        path: String,
        maxBytes: Long,
        declaredSize: Long,
    ): ByteArray {
        val initialCapacity = when {
            declaredSize in 0..maxBytes -> declaredSize.toInt()
            else -> minOf(maxBytes, TRANSFER_CHUNK_BYTES.toLong()).toInt()
        }
        val sink = ByteArrayOutputStream(initialCapacity)
        val buffer = ByteArray(TRANSFER_CHUNK_BYTES)
        var offset = 0L
        while (true) {
            val read = file.read(offset, buffer, 0, buffer.size)
            if (read < 0) break
            if (read == 0) continue
            sink.write(buffer, 0, read)
            offset += read
            if (offset > maxBytes) {
                // The server under-reported the size (or the file grew): refuse
                // rather than hand back a silently clipped prefix.
                throw SftpFileTooLargeException(path, maxOf(declaredSize, offset), maxBytes)
            }
        }
        return sink.toByteArray()
    }

    private fun RemoteResourceInfo.toEntry(): SftpEntry = SftpEntry(
        path = path,
        isDirectory = isDirectory,
        sizeBytes = attributes.sizeOrZero(),
        modifiedEpochMs = attributes.modifiedEpochMs(),
    )

    private fun FileAttributes.toEntry(path: String): SftpEntry = SftpEntry(
        path = path,
        isDirectory = type == FileMode.Type.DIRECTORY,
        sizeBytes = sizeOrZero(),
        modifiedEpochMs = modifiedEpochMs(),
    )

    /** 0 when the server did not send a size flag, per the [SftpEntry] contract. */
    private fun FileAttributes.sizeOrZero(): Long =
        if (has(FileAttributes.Flag.SIZE)) size else 0L

    /** sshj reports mtime in whole seconds; 0 when the server sent no time flag. */
    private fun FileAttributes.modifiedEpochMs(): Long =
        if (has(FileAttributes.Flag.ACMODTIME)) mtime * 1_000L else 0L

    private companion object {
        /** Read/write payload per SFTP request; comfortably under the 32 KiB norm. */
        const val TRANSFER_CHUNK_BYTES = 32_768

        /** Sentinel for "the server did not tell us how big this file is". */
        const val UNKNOWN_SIZE = -1L
    }
}
