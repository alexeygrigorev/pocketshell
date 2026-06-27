package com.pocketshell.app.composer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.pocketshell.app.share.FilenameSanitiser
import com.pocketshell.app.share.ShareUploader
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

/**
 * Issue #570: a multi-file attachment stage that partially failed (some
 * files uploaded, at least one did not) throws this so the caller can
 * still attach the survivors AND surface a per-batch error, instead of
 * discarding everything.
 *
 * [uploadedPaths] are the display paths that DID upload (non-empty by
 * construction — a total failure uses a plain [SshException] instead). The
 * message describes how many of how many failed.
 */
internal class PartialAttachmentUploadException(
    val uploadedPaths: List<String>,
    val failedCount: Int,
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)

internal class PromptAttachmentStager(
    private val resolver: ContentResolver,
    private val cacheDir: File,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val attachmentPruner: RemoteAttachmentPruner = RemoteAttachmentPruner(now = now),
) {
    suspend fun stage(
        session: SshSession,
        scopeKey: String,
        uris: List<Uri>,
    ): Result<List<String>> = withContext(Dispatchers.IO) {
        if (uris.isEmpty()) return@withContext Result.success(emptyList())
        if (!session.isConnected) {
            return@withContext Result.failure(SshException("SSH session is not connected"))
        }

        val safeScope = safeScopeSegment(scopeKey)
        val remoteDir = "$REMOTE_DIRECTORY/$safeScope"
        val displayDir = "~/$remoteDir"
        val timestamp = ShareUploader.formatTimestamp(now())

        val preparedAttachments = mutableListOf<PreparedAttachment>()
        var firstFailure: Throwable? = null
        var failedCount = 0
        uris.forEachIndexed { index, uri ->
            try {
                val prepared = withTimeoutOrNull(ATTACHMENT_SAF_TIMEOUT_MS) {
                    val item = describe(uri)
                    val sanitised = FilenameSanitiser.sanitise(
                        item.displayName ?: uri.lastPathSegment,
                        defaultExtension = ShareUploader.extensionForMimeType(item.mimeType),
                    )
                    val remoteName = composeAttachmentName(timestamp, index, sanitised)
                    PreparedAttachment(
                        remotePath = "$remoteDir/$remoteName",
                        displayPath = "$displayDir/$remoteName",
                        tempFile = drainToTempFile(uri),
                    )
                } ?: throw SshException("Timed out reading selected file")
                preparedAttachments += prepared
            } catch (cancelled: CancellationException) {
                preparedAttachments.forEach { it.tempFile.delete() }
                throw cancelled
            } catch (t: Throwable) {
                failedCount++
                if (firstFailure == null) firstFailure = t
            }
        }

        if (preparedAttachments.isEmpty()) {
            val cause = firstFailure
            return@withContext Result.failure(
                if (cause is SshException) {
                    cause
                } else {
                    SshException("Attachment upload failed: ${cause?.message}", cause)
                },
            )
        }

        try {
            ensureRemoteDirectory(session, remoteDir)
        } catch (cancelled: CancellationException) {
            preparedAttachments.forEach { it.tempFile.delete() }
            throw cancelled
        } catch (t: Throwable) {
            preparedAttachments.forEach { it.tempFile.delete() }
            // The remote directory could not be created — nothing can be
            // uploaded, so this is a clean total failure.
            return@withContext Result.failure(
                if (t is SshException) t else SshException("Attachment upload failed: ${t.message}", t),
            )
        }

        // Issue #570: upload each file independently so a single stalling /
        // failing image among N never discards the ones that DID upload (the
        // multi-image wedge/discard the maintainer hit). Successful display
        // paths are collected as they land; per-file failures are recorded
        // and aggregated after the loop.
        val uploadedPaths = mutableListOf<String>()
        try {
            preparedAttachments.forEach { prepared ->
                try {
                    session.uploadFile(prepared.tempFile, prepared.remotePath)
                    uploadedPaths += prepared.displayPath
                } catch (cancelled: CancellationException) {
                    // A cancellation (sheet dismissed, send-while-uploading
                    // override, or the [withTimeout] in the ViewModel) must
                    // unwind the whole stage — never swallow it into a partial.
                    throw cancelled
                } catch (t: Throwable) {
                    failedCount++
                    if (firstFailure == null) firstFailure = t
                } finally {
                    prepared.tempFile.delete()
                }
            }
        } catch (cancelled: CancellationException) {
            preparedAttachments.forEach { it.tempFile.delete() }
            throw cancelled
        }

        // Best-effort prune runs whenever at least one upload landed — the
        // remote dir now has fresh files worth trimming. A prune failure is
        // already swallowed inside the pruner; never let it fail the stage.
        if (uploadedPaths.isNotEmpty()) {
            runCatching { attachmentPruner.prune(session, remoteDir) }
        }

        when {
            failedCount == 0 -> Result.success(uploadedPaths)
            uploadedPaths.isEmpty() -> {
                val cause = firstFailure
                Result.failure(
                    if (cause is SshException) {
                        cause
                    } else {
                        SshException("Attachment upload failed: ${cause?.message}", cause)
                    },
                )
            }
            else -> Result.failure(
                PartialAttachmentUploadException(
                    uploadedPaths = uploadedPaths,
                    failedCount = failedCount,
                    message = partialFailureMessage(uploadedPaths.size, failedCount, firstFailure),
                    cause = firstFailure,
                ),
            )
        }
    }

    private fun partialFailureMessage(uploaded: Int, failed: Int, cause: Throwable?): String {
        val total = uploaded + failed
        val detail = cause?.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        val suffix = if (detail.isNotBlank()) " ($detail)" else ""
        return "Attached $uploaded of $total files; $failed failed$suffix."
    }

    private suspend fun ensureRemoteDirectory(session: SshSession, remoteDir: String) {
        val result = session.exec("mkdir -p \"\$HOME/$remoteDir\"")
        if (result.exitCode != 0) {
            val detail = result.stderr.ifBlank { result.stdout }.trim()
            throw SshException("Could not create attachment directory: ${detail.ifBlank { "mkdir failed" }}")
        }
    }

    private fun describe(uri: Uri): AttachmentDescription {
        var displayName: String? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                }
        }
        return AttachmentDescription(
            displayName = displayName,
            mimeType = resolver.getType(uri),
        )
    }

    private fun drainToTempFile(uri: Uri): File {
        val dir = File(cacheDir, "prompt-attachments").also { it.mkdirs() }
        val temp = File.createTempFile("attachment-", ".bin", dir)
        try {
            resolver.openInputStream(uri).use { input ->
                if (input == null) throw SshException("Could not read selected file")
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            return temp
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    private data class AttachmentDescription(
        val displayName: String?,
        val mimeType: String?,
    )

    private data class PreparedAttachment(
        val remotePath: String,
        val displayPath: String,
        val tempFile: File,
    )

    companion object {
        const val REMOTE_DIRECTORY: String = ".pocketshell/attachments"
        const val ATTACHMENT_SAF_TIMEOUT_MS: Long = 10_000L

        fun safeScopeSegment(scopeKey: String): String {
            val cleaned = scopeKey.map { ch ->
                when {
                    ch in 'A'..'Z' -> ch.lowercaseChar()
                    ch in 'a'..'z' -> ch
                    ch in '0'..'9' -> ch
                    ch == '-' || ch == '_' -> ch
                    else -> '-'
                }
            }.joinToString("")
                .replace(Regex("-+"), "-")
                .trim('-')
            return cleaned.ifBlank { "session" }.take(80)
        }

        fun composeAttachmentName(
            timestamp: String,
            index: Int,
            sanitised: FilenameSanitiser.Sanitised,
        ): String {
            val ordinal = (index + 1).toString().padStart(2, '0')
            val suffix = sanitised.render()
            return "$timestamp-$ordinal-$suffix"
        }
    }
}
