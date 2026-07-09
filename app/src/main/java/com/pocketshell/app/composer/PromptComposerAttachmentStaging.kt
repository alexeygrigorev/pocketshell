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
