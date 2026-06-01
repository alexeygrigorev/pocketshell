package com.pocketshell.app.composer

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import com.pocketshell.app.share.FilenameSanitiser
import com.pocketshell.app.share.ShareUploader
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

internal class PromptAttachmentStager(
    private val resolver: ContentResolver,
    private val cacheDir: File,
    private val now: () -> Long = { System.currentTimeMillis() },
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
        try {
            ensureRemoteDirectory(session, remoteDir)
            val timestamp = ShareUploader.formatTimestamp(now())
            val paths = uris.mapIndexed { index, uri ->
                val item = describe(uri)
                val sanitised = FilenameSanitiser.sanitise(
                    item.displayName ?: uri.lastPathSegment,
                    defaultExtension = ShareUploader.extensionForMimeType(item.mimeType),
                )
                val remoteName = composeAttachmentName(timestamp, index, sanitised)
                val remotePath = "$remoteDir/$remoteName"
                uploadUri(session, uri, item.size, remotePath, remoteName)
                "$displayDir/$remoteName"
            }
            Result.success(paths)
        } catch (t: Throwable) {
            Result.failure(if (t is SshException) t else SshException("Attachment upload failed: ${t.message}", t))
        }
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
        var size: Long? = null
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0) displayName = cursor.getString(nameIndex)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0) {
                        val queriedSize = cursor.getLong(sizeIndex)
                        if (queriedSize > 0L) size = queriedSize
                    }
                }
        }
        return AttachmentDescription(
            displayName = displayName,
            size = size,
            mimeType = resolver.getType(uri),
        )
    }

    private suspend fun uploadUri(
        session: SshSession,
        uri: Uri,
        size: Long?,
        remotePath: String,
        remoteName: String,
    ) {
        val knownSize = size
        if (knownSize != null) {
            resolver.openInputStream(uri)?.use { input ->
                session.uploadStream(
                    input = input,
                    length = knownSize,
                    name = remoteName,
                    remotePath = remotePath,
                )
            } ?: throw SshException("Could not read selected file")
            return
        }

        val temp = drainToTempFile(uri)
        try {
            session.uploadFile(temp, remotePath)
        } finally {
            temp.delete()
        }
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
        val size: Long?,
        val mimeType: String?,
    )

    companion object {
        const val REMOTE_DIRECTORY: String = ".pocketshell/attachments"

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
