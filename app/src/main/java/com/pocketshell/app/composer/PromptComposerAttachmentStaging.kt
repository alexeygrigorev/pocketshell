package com.pocketshell.app.composer

import kotlinx.coroutines.TimeoutCancellationException

internal fun mergeStagedAttachmentPaths(
    currentAttachments: List<PromptComposerViewModel.StagedAttachment>,
    paths: List<String>,
    previews: List<PromptComposerViewModel.AttachmentPreview>,
): List<PromptComposerViewModel.StagedAttachment> {
    val existing = currentAttachments.map { it.remotePath }.toSet()
    val uniquePaths = paths
        .filter { it.isNotBlank() && it !in existing }
        .distinct()
    val added = uniquePaths.map { path ->
        val preview = previews.getOrNull(paths.indexOf(path))
        PromptComposerViewModel.StagedAttachment(
            remotePath = path,
            displayName = attachmentDisplayName(path),
            previewUri = preview?.uri,
            mimeType = preview?.mimeType,
        )
    }
    return currentAttachments + added
}

internal fun attachmentErrorMessage(error: Throwable): String {
    val detail = if (error is TimeoutCancellationException) {
        "upload timed out"
    } else {
        val raw = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
        raw.ifBlank { error.javaClass.simpleName }
    }
    return "Attachment upload failed: $detail. Your draft was kept; reconnect or choose a smaller/readable file."
}

/**
 * Issue #1569 (U1): the accurate copy for a pick whose upload failed but was
 * RETAINED durably (the picked bytes are saved locally + the tile persists). The
 * old "Your draft was kept" line was false for an attachment-first pick (the draft
 * text was untouched, but the FILE was silently dropped). This tells the truth:
 * nothing was lost, and the attachment uploads on the next Send.
 */
internal fun attachmentRetainedMessage(error: Throwable): String {
    val raw = error.message?.lineSequence()?.firstOrNull()?.trim().orEmpty()
    val detail = raw.ifBlank { error.javaClass.simpleName }
    return "Attachment upload failed: $detail. Saved — it will upload when you Send (or reconnect)."
}

/**
 * Issue #1569 (U1): the durable local-sidecar scope key that holds a draft's
 * retained-but-not-yet-uploaded attachment bytes. Keyed by the composer target so
 * a session switch away-and-back reloads the right bytes, and namespaced with a
 * `draft/` prefix so it never collides with an [OutboundItem] id (a UUID).
 */
internal fun draftAttachmentSidecarScope(target: String): String = "draft/$target"

/** Issue #1569 (U1): marker identifying a retained-but-not-yet-uploaded tile path. */
internal const val PENDING_UPLOAD_MARKER: String = "pending-upload"

/**
 * Issue #1569 (U1): a stable, unique, non-blank PROVISIONAL remote path for a
 * retained-on-failure tile. The file has not been uploaded yet, so there is no real
 * remote path; the send-time sidecar upload replaces this with the authoritative
 * path (`withUploadedSidecars`) before anything reaches the wire. It only needs to
 * be unique (tile de-dupe) and identifiable ([isPendingUploadRemotePath]).
 */
internal fun pendingAttachmentRemotePath(scope: String, index: Int, displayName: String): String =
    "~/${PromptAttachmentStager.REMOTE_DIRECTORY}/${PromptAttachmentStager.safeScopeSegment(scope)}/" +
        "$PENDING_UPLOAD_MARKER-${(index + 1).toString().padStart(2, '0')}-$displayName"

/**
 * Issue #1569 (U1): whether [remotePath] is a provisional retained-on-failure tile
 * path (bytes durable locally, not yet uploaded). A restore reconnects such a tile to
 * its durable draft-sidecar bytes.
 */
internal fun isPendingUploadRemotePath(remotePath: String): Boolean =
    remotePath.substringAfterLast('/').startsWith("$PENDING_UPLOAD_MARKER-")
