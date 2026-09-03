package com.pocketshell.next.composer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.pocketshell.core.transport.SftpChannel
import com.pocketshell.next.files.RemotePath
import com.pocketshell.next.files.TransferLimits
import com.pocketshell.next.files.readCapped
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * What [ComposerAttachmentStager.stage] produced.
 *
 * A partial batch is a first-class outcome, not an error: uploading three
 * screenshots and having one fail must attach the other two and say so, because
 * discarding the survivors is how the old client lost a maintainer's files.
 * [uploaded] is therefore always what actually landed, and [failure] is
 * non-null whenever at least one pick did not.
 */
data class AttachmentStageResult(
    val uploaded: List<StagedAttachment>,
    val failure: String? = null,
)

/**
 * Uploads picked device files to the host and turns them into staged
 * attachments (rewrite task P-1, ported from the old client's
 * `PromptAttachmentStager`).
 *
 * ## The contract the message format depends on
 *
 * Each file lands at `<home>/.pocketshell/attachments/<scope>/<timestamp>-NN-<name>`
 * and is referenced in the composed message by its `~/`-prefixed display path —
 * byte-identical to the old flow, because a host-side agent reading
 * `- ~/.pocketshell/attachments/...` out of a prompt has been resolving that
 * exact shape for a year. See [ComposerText.compose].
 *
 * ## Two changes from the port
 *
 * **No temp files.** The old stager drained each pick to a cache file because
 * its transport uploaded from a `File`. [SftpChannel.write] takes a
 * `ByteArray`, so the bytes go straight from the content stream to the wire —
 * one copy fewer, and no cache directory to leak on a cancelled send. The size
 * cap that made a temp file safe is still applied ([TransferLimits], via
 * [readCapped]): the read REFUSES past the cap rather than truncating, so a
 * half-written file never appears on the host under its real name.
 *
 * **No byte-level progress.** [SftpChannel.write] has no progress callback, so
 * the ported `AttachmentTransferAggregator` (which coalesced per-chunk byte
 * ticks into a 4 Hz UI snapshot) would have had nothing to aggregate. Progress
 * is reported per FILE instead, through [onProgress] — "2 of 3 · photo.png" —
 * which is the granularity the channel can actually justify. Adding a byte
 * callback to the transport is a core-transport change, not a composer one.
 */
class ComposerAttachmentStager(
    private val resolver: ContentResolver,
    private val dispatcher: CoroutineDispatcher,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val retention: AttachmentRetentionPolicy = AttachmentRetentionPolicy(),
) {

    /**
     * Uploads [picks] into [homeDir]'s attachment directory for [scopeKey].
     *
     * [scopeKey] is the composer session key, so one session's attachments do
     * not sit in another's directory and the retention sweep can prune per
     * session. [onProgress] is called on the caller's dispatcher before each
     * file starts, with a 1-based index.
     */
    suspend fun stage(
        sftp: SftpChannel,
        homeDir: String,
        scopeKey: String,
        picks: List<Uri>,
        onProgress: (index: Int, count: Int, name: String) -> Unit = { _, _, _ -> },
    ): AttachmentStageResult {
        if (picks.isEmpty()) return AttachmentStageResult(emptyList())

        val scope = safeScopeSegment(scopeKey)
        val remoteDir = RemotePath.join(RemotePath.join(homeDir, REMOTE_DIRECTORY), scope)
        val displayDir = "~/$REMOTE_DIRECTORY/$scope"
        val timestamp = formatTimestamp(now())

        try {
            mkdirs(sftp, remoteDir)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Throwable) {
            // Nothing can be uploaded if the directory cannot exist, so this is
            // a clean total failure rather than a partial one.
            return AttachmentStageResult(
                emptyList(),
                "Could not create the attachment directory: ${describe(failure)}",
            )
        }

        val uploaded = mutableListOf<StagedAttachment>()
        var firstFailure: Throwable? = null
        var failedCount = 0

        picks.forEachIndexed { index, uri ->
            val described = describeUri(uri)
            val fileName = composeName(timestamp, index, described.displayName ?: uri.lastPathSegment)
            onProgress(index + 1, picks.size, fileName)
            try {
                val bytes = withContext(dispatcher) {
                    val stream = resolver.openInputStream(uri)
                        ?: throw IOException("could not read the selected file")
                    stream.use { readCapped(it, TransferLimits.MAX_UPLOAD_BYTES, fileName) }
                }
                sftp.write(RemotePath.join(remoteDir, fileName), bytes)
                uploaded += StagedAttachment(
                    remotePath = "$displayDir/$fileName",
                    displayName = fileName,
                    mimeType = described.mimeType,
                )
            } catch (cancelled: CancellationException) {
                // A cancelled stage (the user dismissed, or the screen went
                // away) must unwind the whole thing, never be folded into a
                // "partial success" the user never asked for.
                throw cancelled
            } catch (failure: Throwable) {
                failedCount += 1
                if (firstFailure == null) firstFailure = failure
            }
        }

        if (uploaded.isNotEmpty()) {
            // Best effort: a prune failure must never fail an upload that
            // already landed. It is housekeeping, not part of the send.
            runCatching { retention.prune(sftp, remoteDir, now()) }
        }

        return AttachmentStageResult(
            uploaded = uploaded,
            failure = when {
                failedCount == 0 -> null
                uploaded.isEmpty() -> "Attachment upload failed: ${describe(firstFailure)}"
                else -> "Attached ${uploaded.size} of ${picks.size} files; " +
                    "$failedCount failed (${describe(firstFailure)})."
            },
        )
    }

    /**
     * `mkdir -p` over SFTP.
     *
     * [SftpChannel.mkdir] is a single directory and fails when the path exists,
     * so the walk creates each missing segment and treats an "already there"
     * as success — two composers staging into the same directory at once must
     * not turn a race into a user-visible error.
     */
    private suspend fun mkdirs(sftp: SftpChannel, path: String) {
        val segments = path.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (segment in segments) {
            current = "$current/$segment"
            if (sftp.stat(current)?.isDirectory == true) continue
            runCatching { sftp.mkdir(current) }
        }
        val leaf = sftp.stat(path)
        if (leaf?.isDirectory != true) throw IOException("$path is not a directory")
    }

    private fun describeUri(uri: Uri): UriDescription {
        var displayName: String? = null
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use
                val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (column >= 0) displayName = cursor.getString(column)
            }
        }
        return UriDescription(displayName, runCatching { resolver.getType(uri) }.getOrNull())
    }

    private data class UriDescription(val displayName: String?, val mimeType: String?)

    companion object {
        /** Home-relative directory every composer attachment lands in. */
        const val REMOTE_DIRECTORY: String = ".pocketshell/attachments"

        /** `<timestamp>-<NN>-<sanitised name>`, the old flow's naming, unchanged. */
        fun composeName(timestamp: String, index: Int, rawName: String?): String {
            val ordinal = (index + 1).toString().padStart(2, '0')
            return "$timestamp-$ordinal-${sanitiseFileName(rawName)}"
        }

        /**
         * One safe remote file name from whatever a content provider claimed.
         *
         * A provider can report anything as a display name, path separators
         * included. Only the basename survives, control characters and shell
         * metacharacters become `_`, runs collapse, and a name that reduces to
         * nothing (or to a dot run) gets a neutral fallback — so a provider
         * cannot talk the app into writing outside the attachment directory.
         */
        fun sanitiseFileName(rawName: String?): String {
            val basename = rawName.orEmpty().substringAfterLast('/').substringAfterLast('\\')
            val cleaned = basename
                .map { char ->
                    when {
                        char.isLetterOrDigit() -> char
                        char == '.' || char == '_' || char == '-' -> char
                        else -> '_'
                    }
                }
                .joinToString("")
                .replace(Regex("_+"), "_")
                .trim('_', '.', '-')
            return when {
                cleaned.isEmpty() -> DEFAULT_FILE_NAME
                cleaned.all { it == '.' } -> DEFAULT_FILE_NAME
                else -> cleaned.take(MAX_FILE_NAME_LENGTH)
            }
        }

        /** The per-session directory segment: lowercase, path-safe, bounded. */
        fun safeScopeSegment(scopeKey: String): String {
            val cleaned = scopeKey
                .map { char ->
                    when {
                        char in 'A'..'Z' -> char.lowercaseChar()
                        char in 'a'..'z' || char in '0'..'9' || char == '-' || char == '_' -> char
                        else -> '-'
                    }
                }
                .joinToString("")
                .replace(Regex("-+"), "-")
                .trim('-')
            return cleaned.ifBlank { "session" }.take(80)
        }

        fun formatTimestamp(epochMillis: Long): String =
            SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
                .apply { timeZone = TimeZone.getDefault() }
                .format(Date(epochMillis))

        private const val TIMESTAMP_PATTERN = "yyyyMMdd-HHmmss"
        private const val DEFAULT_FILE_NAME = "attachment"
        private const val MAX_FILE_NAME_LENGTH = 200

        private fun describe(failure: Throwable?): String =
            failure?.message?.lineSequence()?.firstOrNull()?.trim()?.takeIf { it.isNotBlank() }
                ?: failure?.javaClass?.simpleName
                ?: "unknown error"
    }
}
