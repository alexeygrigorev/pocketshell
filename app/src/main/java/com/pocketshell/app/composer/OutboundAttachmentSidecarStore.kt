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
import kotlinx.coroutines.CoroutineDispatcher
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

    // Issue #1461: the IO dispatcher these blocking file/prefs methods hop onto,
    // injectable so a `runTest` unit test can confine the hop to its
    // `testScheduler`. Without this seam the `withContext(Dispatchers.IO)` below
    // resumes its caller — a `viewModelScope.launch {}` on the test's unconfined
    // Main — from a REAL IO worker thread, and that background→unconfined-Main
    // resume calls `UnconfinedTestDispatcher.dispatch`, which throws
    // ("can only be used by the yield function"), a coroutines-test thread-race
    // that flaked `PromptComposerAttachmentWedgeTest`. Defaults to
    // `Dispatchers.IO`, so production behaviour is unchanged.
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    @VisibleForTesting
    @Volatile
    internal var lastBlockingAccessThreadNameForTest: String? = null

    @VisibleForTesting
    internal var lockAttemptObserverForTest: (() -> Unit)? = null

    // Shared with enqueue/stage so repair cannot delete a dir mid-createTempFile
    // or drop a sidecar whose row became live after a stale snapshot.
    private val sidecarLock = Any()

    suspend fun stage(
        outboundItemId: String,
        uris: List<Uri>,
        attachmentIndices: List<Int> = emptyList(),
    ): List<LocalAttachmentSidecarRef> = withContext(ioDispatcher) {
        synchronized(sidecarLock) { stageLocked(outboundItemId, uris, attachmentIndices) }
    }

    /**
     * Serialize sidecar staging/metadata plus the queue-row enqueue against
     * orphan repair and authorized disposal. The block is deliberately
     * non-suspending: all blocking prefs/file work and the queue-store commit
     * happen on [ioDispatcher] while the lock is held.
     */
    internal suspend fun <T> withSidecarLock(block: () -> T): T = withContext(ioDispatcher) {
        withSidecarLockBlocking(block)
    }

    /**
     * The coordinator calls this only from its injected IO dispatcher. Keeping
     * the blocking form explicit prevents a nested hop back to this store's
     * independently configured dispatcher during row-before-cleanup commits.
     */
    internal fun <T> withSidecarLockBlocking(block: () -> T): T {
        lockAttemptObserverForTest?.invoke()
        return synchronized(sidecarLock) { block() }
    }

    suspend fun refsFor(outboundItemId: String): List<LocalAttachmentSidecarRef> = withContext(ioDispatcher) {
        refsForBlocking(outboundItemId)
    }

    /**
     * Issue #1588: durably record that the send-time upload for the sidecars in
     * [uploadedRemotePathById] (keyed by [LocalAttachmentSidecarRef.id]) has COMPLETED,
     * stamping each ref with its authoritative uploaded remote path. A later delivery
     * retry reads this back via [refsFor] and SKIPS the re-transfer (resume, not
     * restart) — the send-leg cure for the #1563 full-re-upload-on-every-retry pain
     * (H5, #1562 audit). Only set after a fully successful upload, so a failed/partial
     * upload leaves the marker null and the row re-uploads normally. No-op for empty
     * input or ids with no matching persisted ref.
     */
    suspend fun markUploaded(uploadedRemotePathById: Map<String, String>) = withContext(ioDispatcher) {
        synchronized(sidecarLock) {
            if (uploadedRemotePathById.isEmpty()) return@synchronized
            val refs = allRefsBlocking()
            if (refs.none { uploadedRemotePathById.containsKey(it.id) }) return@synchronized
            val updated = refs.map { ref ->
                uploadedRemotePathById[ref.id]
                    ?.takeIf { it.isNotBlank() }
                    ?.let { path -> ref.copy(uploadedRemotePath = path) }
                    ?: ref
            }
            persistAll(updated)
        }
    }

    internal suspend fun updateMetadata(
        metadataById: Map<String, Pair<String, String?>>,
    ): List<LocalAttachmentSidecarRef> = withContext(ioDispatcher) {
        synchronized(sidecarLock) { updateMetadataLocked(metadataById) }
    }

    suspend fun removeOutboundItem(outboundItemId: String) = withContext(ioDispatcher) {
        synchronized(sidecarLock) { removeOutboundItemLocked(outboundItemId) }
    }

    suspend fun remove(refId: String) = withContext(ioDispatcher) {
        synchronized(sidecarLock) {
            val refs = allRefsBlocking()
            refs.firstOrNull { it.id == refId }?.let { ref ->
                runCatching { File(ref.localPath).delete() }
            }
            persistAll(refs.filterNot { it.id == refId })
        }
    }

    suspend fun reconcile() = withContext(ioDispatcher) {
        withSidecarLockBlocking { reconcileLocked() }
    }

    /**
     * Issue #1589: compare every sidecar ref against the complete live queue-row
     * id set. Refs whose [LocalAttachmentSidecarRef.outboundItemId] is still a
     * live row, or a `draft/...` composer scope, are kept. Proven queue orphans
     * lose their ref + local bytes. Existing file-vs-ref reconcile still runs
     * first so a missing file cannot keep a dead ref alive.
     *
     * [liveRowIds] is a provider, not a snapshot: each candidate is re-checked
     * immediately before delete so a concurrent enqueue+stage cannot lose a
     * sidecar whose row is still queued. Callers that only have a frozen set
     * should pass `{ snapshot }` and accept that they own snapshot freshness.
     */
    suspend fun reconcileAgainstLiveRowIds(liveRowIds: () -> Set<String>) = withContext(ioDispatcher) {
        withSidecarLockBlocking {
            // Keep completed remote-upload evidence through this first local
            // file pass. A process can crash after the local sidecar is gone
            // but before orphan repair persists the remote cleanup tombstone.
            // Dropping that ref here would make the remote path unrecoverable.
            reconcileLocked(preserveUploadedRemoteRefs = true)
            val remaining = mutableListOf<LocalAttachmentSidecarRef>()
            val orphaned = mutableListOf<LocalAttachmentSidecarRef>()
            val snapshot = liveRowIds()
            for (ref in allRefsBlocking()) {
                if (OutboundQueueRetentionPolicy.isDraftSidecarScope(ref.outboundItemId)) {
                    remaining += ref
                    continue
                }
                if (ref.outboundItemId in snapshot) {
                    remaining += ref
                    continue
                }
                // Re-read immediately before delete. A snapshot taken earlier
                // is not proof of death: a concurrent enqueue+stage on B can
                // land between the snapshot and this point.
                val live = liveRowIds()
                if (ref.outboundItemId in live) {
                    remaining += ref
                } else {
                    orphaned += ref
                }
            }
            // A remote upload may already have completed before the process
            // died. Persist its exact remote path before dropping the ref/file;
            // otherwise repair would erase the only evidence needed to delete
            // the remote checkpoint/final sidecar on the next foreground pass.
            val remoteTombstones = orphaned.mapNotNull { ref ->
                ref.uploadedRemotePath
                    ?.takeIf { it.isNotBlank() }
                    ?.let { path ->
                        SidecarCleanupTombstone(
                            outboundItemId = ref.outboundItemId,
                            sidecarId = ref.id,
                            localPath = ref.localPath,
                            remotePath = path,
                            stableToken = ref.id,
                        )
                    }
            }
            if (remoteTombstones.isNotEmpty()) {
                persistTombstonesBlocking(remoteTombstones)
            }
            orphaned.forEach { ref -> runCatching { File(ref.localPath).delete() } }
            persistAll(remaining)
            orphaned.map { it.outboundItemId }.distinct().forEach { outboundItemId ->
                runCatching { File(rootDir(), outboundItemId).deleteRecursively() }
            }
            rootDir().walkBottomUp()
                .filter { it.isDirectory && it != rootDir() && it.listFiles().isNullOrEmpty() }
                .forEach { dir -> runCatching { dir.delete() } }
        }
    }

    suspend fun reconcileAgainstLiveRowIds(liveRowIds: Set<String>) =
        reconcileAgainstLiveRowIds(liveRowIds = { liveRowIds })

    internal fun allRefsIncludingMissingBlocking(): List<LocalAttachmentSidecarRef> =
        synchronized(sidecarLock) { allRefsBlocking() }

    internal fun tombstonesForOutboundItem(
        outboundItemId: String,
        remotePathBySidecarId: Map<String, String> = emptyMap(),
    ): List<SidecarCleanupTombstone> = synchronized(sidecarLock) {
        allRefsBlocking()
            .filter { it.outboundItemId == outboundItemId }
            .map { ref ->
                SidecarCleanupTombstone(
                    outboundItemId = outboundItemId,
                    sidecarId = ref.id,
                    localPath = ref.localPath,
                    remotePath = ref.uploadedRemotePath
                        ?: remotePathBySidecarId[ref.id]?.takeIf { it.isNotBlank() },
                    stableToken = ref.id,
                )
            }
    }

    internal fun persistTombstonesBlocking(tombstones: List<SidecarCleanupTombstone>) {
        synchronized(sidecarLock) {
            if (tombstones.isEmpty()) return
            val merged = (pendingTombstonesBlocking() + tombstones)
                .associateBy { it.sidecarId }
                .values
                .toList()
            prefs.edit().putString(KEY_TOMBSTONES, encodeTombstones(merged)).commit()
        }
    }

    internal fun pendingTombstonesBlocking(): List<SidecarCleanupTombstone> =
        synchronized(sidecarLock) {
            decodeTombstones(prefs.getString(KEY_TOMBSTONES, "").orEmpty())
        }

    internal fun removeTombstonesBlocking(sidecarIds: Set<String>) {
        synchronized(sidecarLock) {
            if (sidecarIds.isEmpty()) return
            val remaining = pendingTombstonesBlocking().filterNot { it.sidecarId in sidecarIds }
            val editor = prefs.edit()
            if (remaining.isEmpty()) {
                editor.remove(KEY_TOMBSTONES)
            } else {
                editor.putString(KEY_TOMBSTONES, encodeTombstones(remaining))
            }
            editor.commit()
        }
    }

    internal fun stageLocked(
        outboundItemId: String,
        uris: List<Uri>,
        attachmentIndices: List<Int>,
    ): List<LocalAttachmentSidecarRef> {
        if (outboundItemId.isBlank() || uris.isEmpty()) return emptyList()
        return uris.mapIndexedNotNull { index, uri ->
            stageOne(outboundItemId, uri, attachmentIndices.getOrNull(index))
        }
    }

    internal fun updateMetadataLocked(
        metadataById: Map<String, Pair<String, String?>>,
    ): List<LocalAttachmentSidecarRef> {
        if (metadataById.isEmpty()) return emptyList()
        val updated = allRefsBlocking().map { ref ->
            metadataById[ref.id]?.let { (displayName, mimeType) ->
                ref.copy(displayName = displayName, mimeType = mimeType ?: ref.mimeType)
            } ?: ref
        }
        persistAll(updated)
        return updated.filter { metadataById.containsKey(it.id) }
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

    internal fun removeOutboundItemLocked(outboundItemId: String) {
        refsForBlocking(outboundItemId).forEach { ref -> runCatching { File(ref.localPath).delete() } }
        val remaining = allRefsBlocking().filterNot { it.outboundItemId == outboundItemId }
        persistAll(remaining)
        runCatching { File(rootDir(), outboundItemId).deleteRecursively() }
    }

    private fun reconcileLocked(preserveUploadedRemoteRefs: Boolean = false) {
        val liveRefs = allRefsBlocking().filter { ref ->
            File(ref.localPath).exists() ||
                (preserveUploadedRemoteRefs && !ref.uploadedRemotePath.isNullOrBlank())
        }
        persistAll(liveRefs)
        val livePaths = liveRefs.mapTo(mutableSetOf()) { File(it.localPath).absolutePath }
        rootDir().walkTopDown()
            .filter { it.isFile && it.absolutePath !in livePaths }
            .forEach { file -> runCatching { file.delete() } }
        rootDir().walkBottomUp()
            .filter { it.isDirectory && it != rootDir() && it.listFiles().isNullOrEmpty() }
            .forEach { dir -> runCatching { dir.delete() } }
    }

    private fun refsForBlocking(outboundItemId: String): List<LocalAttachmentSidecarRef> =
        allRefsBlocking()
            .filter { it.outboundItemId == outboundItemId && File(it.localPath).exists() }
            .sortedWith(compareBy<LocalAttachmentSidecarRef> { it.attachmentIndex ?: Int.MAX_VALUE }
                .thenBy { it.createdAtMs }
                .thenBy { it.id })

    private fun allRefsBlocking(): List<LocalAttachmentSidecarRef> =
        decodeRefs(prefs.getString(KEY_REFS, "").orEmpty()).also {
            lastBlockingAccessThreadNameForTest = Thread.currentThread().name
        }

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
        internal const val KEY_TOMBSTONES = "tombstones"
    }
}

/**
 * Issue #1589: crash-safe cleanup record for one discarded queue sidecar.
 * Persisted before the durable queue row is removed so a crash between row
 * removal and local/remote hygiene can resume without resurrecting the send.
 */
internal data class SidecarCleanupTombstone(
    val outboundItemId: String,
    val sidecarId: String,
    val localPath: String,
    val remotePath: String?,
    val stableToken: String,
)

data class LocalAttachmentSidecarRef(
    val id: String,
    val outboundItemId: String,
    val localPath: String,
    val displayName: String,
    val mimeType: String?,
    val byteSize: Long,
    val createdAtMs: Long,
    val attachmentIndex: Int? = null,
    // Issue #1588: the authoritative remote path recorded once the send-time upload
    // for this sidecar COMPLETED (via [OutboundAttachmentSidecarStore.markUploaded]).
    // `null` until then; a delivery retry with a non-null value skips the re-transfer.
    val uploadedRemotePath: String? = null,
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
            // Issue #1588: field 8 — the completed send-time upload path (or empty).
            ref.uploadedRemotePath.orEmpty(),
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
        // Issue #1588: field 8 is absent on rows persisted before the marker existed —
        // `getOrNull` → null → the row re-uploads on next send (the safe default).
        val uploadedRemotePath = fields.getOrNull(8)?.ifBlank { null }
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
            uploadedRemotePath = uploadedRemotePath,
        )
    }
}

private fun encodeTombstones(tombstones: List<SidecarCleanupTombstone>): String =
    tombstones.joinToString("\n") { tombstone ->
        listOf(
            tombstone.outboundItemId,
            tombstone.sidecarId,
            tombstone.localPath,
            tombstone.remotePath.orEmpty(),
            tombstone.stableToken,
        ).joinToString("\t") { field -> escapeSidecarField(field) }
    }

private fun decodeTombstones(raw: String): List<SidecarCleanupTombstone> {
    if (raw.isBlank()) return emptyList()
    return raw.split('\n').mapNotNull { row ->
        if (row.isBlank()) return@mapNotNull null
        val fields = row.split('\t').map { unescapeSidecarField(it) }
        val outboundItemId = fields.getOrNull(0).orEmpty()
        val sidecarId = fields.getOrNull(1).orEmpty()
        val localPath = fields.getOrNull(2).orEmpty()
        val stableToken = fields.getOrNull(4).orEmpty().ifBlank { sidecarId }
        if (outboundItemId.isBlank() || sidecarId.isBlank()) return@mapNotNull null
        SidecarCleanupTombstone(
            outboundItemId = outboundItemId,
            sidecarId = sidecarId,
            localPath = localPath,
            remotePath = fields.getOrNull(3)?.ifBlank { null },
            stableToken = stableToken,
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
