package com.pocketshell.app.composer

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import androidx.annotation.VisibleForTesting
import com.pocketshell.app.prefs.DeferredPrefs
import com.pocketshell.app.share.FilenameSanitiser
import com.pocketshell.app.share.ShareUploader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #900: durable local bytes for queued outbound attachments.
 *
 * The outbound send queue already persists the committed text and stable queue
 * item id. This sidecar stores selected attachment bytes under that same queue
 * id so a later foreground flush can upload them before delivering the prompt.
 * It is intentionally not wired into Send yet; this slice establishes the
 * crash-survivable file+metadata contract first.
 */
@Singleton
class OutboundAttachmentSidecarStore @Inject constructor(
    @ApplicationContext private val appContext: Context,
) {
    // Issue #1125: open the prefs file off the Main thread (it is opened at
    // Hilt injection on Main otherwise; the actual reads/writes already run on
    // Dispatchers.IO inside the suspend methods below).
    private val deferredPrefs = DeferredPrefs(appContext, PREFS_NAME)
    private val prefs: SharedPreferences get() = deferredPrefs.get()

    @VisibleForTesting
    internal fun awaitPrefsBuildThreadNameForTest(): String =
        deferredPrefs.awaitBuildThreadNameForTest()

    internal var idGenerator: () -> String = { UUID.randomUUID().toString() }
    internal var clock: () -> Long = { System.currentTimeMillis() }

    suspend fun stage(
        outboundItemId: String,
        uris: List<Uri>,
        attachmentIndices: List<Int> = emptyList(),
    ): List<LocalAttachmentSidecarRef> = withContext(Dispatchers.IO) {
        if (outboundItemId.isBlank() || uris.isEmpty()) return@withContext emptyList()
        uris.mapIndexedNotNull { index, uri ->
            stageOne(outboundItemId, uri, attachmentIndices.getOrNull(index))
        }
    }

    suspend fun refsFor(outboundItemId: String): List<LocalAttachmentSidecarRef> = withContext(Dispatchers.IO) {
        refsForBlocking(outboundItemId)
    }

    suspend fun removeOutboundItem(outboundItemId: String) = withContext(Dispatchers.IO) {
        refsForBlocking(outboundItemId).forEach { ref -> runCatching { File(ref.localPath).delete() } }
        val remaining = allRefsBlocking().filterNot { it.outboundItemId == outboundItemId }
        persistAll(remaining)
        runCatching { File(rootDir(), outboundItemId).deleteRecursively() }
    }

    suspend fun remove(refId: String) = withContext(Dispatchers.IO) {
        val refs = allRefsBlocking()
        refs.firstOrNull { it.id == refId }?.let { ref ->
            runCatching { File(ref.localPath).delete() }
        }
        persistAll(refs.filterNot { it.id == refId })
    }

    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val liveRefs = allRefsBlocking().filter { File(it.localPath).exists() }
        persistAll(liveRefs)
        val livePaths = liveRefs.mapTo(mutableSetOf()) { File(it.localPath).absolutePath }
        rootDir().walkTopDown()
            .filter { it.isFile && it.absolutePath !in livePaths }
            .forEach { file -> runCatching { file.delete() } }
        rootDir().walkBottomUp()
            .filter { it.isDirectory && it != rootDir() && it.listFiles().isNullOrEmpty() }
            .forEach { dir -> runCatching { dir.delete() } }
    }

    private fun stageOne(
        outboundItemId: String,
        uri: Uri,
        attachmentIndex: Int?,
    ): LocalAttachmentSidecarRef? {
        val description = describe(uri)
        val sanitised = FilenameSanitiser.sanitise(
            description.displayName ?: uri.lastPathSegment,
            defaultExtension = ShareUploader.extensionForMimeType(description.mimeType),
        )
        val id = idGenerator()
        val dir = File(rootDir(), outboundItemId).also { it.mkdirs() }
        val finalFile = File(dir, "$id-${sanitised.render()}")
        val tempFile = File.createTempFile("$id-", ".tmp", dir)
        val byteSize = try {
            appContext.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            } ?: return null
            tempFile.length()
        } catch (t: Throwable) {
            runCatching { tempFile.delete() }
            return null
        }
        runCatching { finalFile.delete() }
        if (!tempFile.renameTo(finalFile)) {
            runCatching { tempFile.delete() }
            return null
        }
        val ref = LocalAttachmentSidecarRef(
            id = id,
            outboundItemId = outboundItemId,
            localPath = finalFile.absolutePath,
            displayName = sanitised.render(),
            mimeType = description.mimeType,
            byteSize = byteSize,
            createdAtMs = clock(),
            attachmentIndex = attachmentIndex,
        )
        persistAll(allRefsBlocking() + ref)
        return ref
    }

    private fun describe(uri: Uri): AttachmentDescription {
        var displayName: String? = null
        var size: Long? = null
        runCatching {
            appContext.contentResolver
                .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)
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
            mimeType = appContext.contentResolver.getType(uri),
        )
    }

    private fun refsForBlocking(outboundItemId: String): List<LocalAttachmentSidecarRef> =
        allRefsBlocking()
            .filter { it.outboundItemId == outboundItemId && File(it.localPath).exists() }
            .sortedWith(compareBy<LocalAttachmentSidecarRef> { it.attachmentIndex ?: Int.MAX_VALUE }
                .thenBy { it.createdAtMs }
                .thenBy { it.id })

    private fun allRefsBlocking(): List<LocalAttachmentSidecarRef> =
        decodeRefs(prefs.getString(KEY_REFS, "").orEmpty())

    private fun persistAll(refs: List<LocalAttachmentSidecarRef>) {
        prefs.edit().putString(KEY_REFS, encodeRefs(refs)).commit()
    }

    private fun rootDir(): File {
        val dir = File(appContext.filesDir, DIRECTORY_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private data class AttachmentDescription(
        val displayName: String?,
        val size: Long?,
        val mimeType: String?,
    )

    companion object {
        internal const val DIRECTORY_NAME = "outbound-attachments"
        internal const val PREFS_NAME = "outbound_attachment_sidecars"
        private const val KEY_REFS = "refs"
    }
}

data class LocalAttachmentSidecarRef(
    val id: String,
    val outboundItemId: String,
    val localPath: String,
    val displayName: String,
    val mimeType: String?,
    val byteSize: Long,
    val createdAtMs: Long,
    val attachmentIndex: Int? = null,
)

private fun encodeRefs(refs: List<LocalAttachmentSidecarRef>): String =
    refs.joinToString("\n") { ref ->
        listOf(
            ref.id,
            ref.outboundItemId,
            ref.localPath,
            ref.displayName,
            ref.mimeType.orEmpty(),
            ref.byteSize.toString(),
            ref.createdAtMs.toString(),
            ref.attachmentIndex?.toString().orEmpty(),
        ).joinToString("\t") { field -> escapeSidecarField(field) }
    }

private fun decodeRefs(raw: String): List<LocalAttachmentSidecarRef> {
    if (raw.isBlank()) return emptyList()
    return raw.split('\n').mapNotNull { row ->
        if (row.isBlank()) return@mapNotNull null
        val fields = row.split('\t').map { unescapeSidecarField(it) }
        val id = fields.getOrNull(0).orEmpty()
        val outboundItemId = fields.getOrNull(1).orEmpty()
        val localPath = fields.getOrNull(2).orEmpty()
        val displayName = fields.getOrNull(3).orEmpty()
        val byteSize = fields.getOrNull(5)?.toLongOrNull() ?: return@mapNotNull null
        val createdAtMs = fields.getOrNull(6)?.toLongOrNull() ?: return@mapNotNull null
        val attachmentIndex = fields.getOrNull(7)?.toIntOrNull()
        if (id.isBlank() || outboundItemId.isBlank() || localPath.isBlank()) return@mapNotNull null
        LocalAttachmentSidecarRef(
            id = id,
            outboundItemId = outboundItemId,
            localPath = localPath,
            displayName = displayName.ifBlank { File(localPath).name },
            mimeType = fields.getOrNull(4).orEmpty().ifBlank { null },
            byteSize = byteSize,
            createdAtMs = createdAtMs,
            attachmentIndex = attachmentIndex,
        )
    }
}

private fun escapeSidecarField(field: String): String =
    field.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

private fun unescapeSidecarField(field: String): String {
    val out = StringBuilder(field.length)
    var i = 0
    while (i < field.length) {
        val c = field[i]
        if (c == '\\' && i + 1 < field.length) {
            when (field[i + 1]) {
                't' -> {
                    out.append('\t')
                    i += 2
                }
                'n' -> {
                    out.append('\n')
                    i += 2
                }
                '\\' -> {
                    out.append('\\')
                    i += 2
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        } else {
            out.append(c)
            i++
        }
    }
    return out.toString()
}
