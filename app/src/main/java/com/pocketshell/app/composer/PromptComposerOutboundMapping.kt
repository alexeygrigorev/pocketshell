package com.pocketshell.app.composer

import com.pocketshell.app.composer.PromptComposerViewModel.SendTargetSnapshot
import com.pocketshell.app.composer.PromptComposerViewModel.StagedAttachment
import java.security.MessageDigest

/**
 * Issue #872: map live attachment tiles to their durable refs (drops the
 * session-transient [StagedAttachment.previewUri]).
 */
internal fun List<StagedAttachment>.toDurableRefs(): List<DurableAttachmentRef> =
    map { DurableAttachmentRef(it.remotePath, it.displayName, it.mimeType) }

/**
 * Issue #961: the logical-send identity for the outbound queue's
 * coalesce-on-enqueue. It identifies the SAME logical prompt: clean draft,
 * target pane/route/agent, Enter intent, and attachment set.
 */
internal fun computeSendKey(
    cleanDraft: String,
    attachments: List<StagedAttachment>,
    withEnter: Boolean,
    sendTarget: SendTargetSnapshot,
): String {
    val attachmentSignature = attachments.joinToString(separator = "\u0000") { attachment ->
        "${attachment.displayName}\u0001${attachment.mimeType.orEmpty()}"
    }
    val material = listOf(
        sendTarget.sessionKey,
        sendTarget.paneId,
        sendTarget.route.name,
        sendTarget.agentKind.orEmpty(),
        if (withEnter) "1" else "0",
        cleanDraft,
        attachmentSignature,
    ).joinToString(separator = "\u0002")
    return material.toByteArray(Charsets.UTF_8)
        .let { MessageDigest.getInstance("SHA-256").digest(it) }
        .joinToString(separator = "") { byte -> "%02x".format(byte) }
}

/**
 * Issue #900: sidecar-backed sends must be able to replace provisional remote
 * upload refs after process death.
 */
internal fun List<StagedAttachment>.toSidecarAwareDurableRefs(
    sidecars: List<LocalAttachmentSidecarRef>,
): List<DurableAttachmentRef> {
    val sidecarsByAttachmentIndex = sidecars
        .mapNotNull { ref -> ref.attachmentIndex?.let { index -> index to ref } }
        .toMap()
    val unindexedSidecars = ArrayDeque(sidecars.filter { it.attachmentIndex == null })
    return mapIndexed { index, attachment ->
        val sidecar = sidecarsByAttachmentIndex[index]
            ?: if (attachment.previewUri != null) unindexedSidecars.removeFirstOrNull() else null
        DurableAttachmentRef(
            remotePath = attachment.remotePath,
            displayName = sidecar?.displayName ?: attachment.displayName,
            mimeType = sidecar?.mimeType ?: attachment.mimeType,
        )
    }
}

internal fun List<DurableAttachmentRef>.withUploadedSidecars(
    sidecars: List<LocalAttachmentSidecarRef>,
    uploadedRefs: List<DurableAttachmentRef>,
): List<DurableAttachmentRef> {
    if (isEmpty()) return uploadedRefs
    if (sidecars.all { it.attachmentIndex != null }) {
        val replacements = sidecars.zip(uploadedRefs)
            .mapNotNull { (sidecar, uploaded) -> sidecar.attachmentIndex?.let { index -> index to uploaded } }
            .toMap()
        if (replacements.size == sidecars.size) {
            return mapIndexed { index, existing ->
                replacements[index]?.copy(mimeType = replacements[index]?.mimeType ?: existing.mimeType) ?: existing
            }
        }
    }
    val remaining = ArrayDeque(sidecars.zip(uploadedRefs))
    val updated = map { existing ->
        val next = remaining.firstOrNull()
        if (next != null && existing.matchesSidecar(next.first)) {
            remaining.removeFirst()
            next.second.copy(mimeType = next.second.mimeType ?: existing.mimeType)
        } else {
            existing
        }
    }
    return if (remaining.isEmpty()) updated else uploadedRefs
}

private fun DurableAttachmentRef.matchesSidecar(sidecar: LocalAttachmentSidecarRef): Boolean {
    if (displayName == sidecar.displayName) return true
    val remoteName = attachmentDisplayName(remotePath)
    return remoteName == sidecar.displayName ||
        displayName.isTimestampedAttachmentNameFor(sidecar.displayName) ||
        remoteName.isTimestampedAttachmentNameFor(sidecar.displayName)
}

private fun String.isTimestampedAttachmentNameFor(sidecarDisplayName: String): Boolean {
    if (sidecarDisplayName.isBlank()) return false
    val pattern = Regex("""\d{8}-\d{6}-\d{2}-${Regex.escape(sidecarDisplayName)}""")
    return pattern.matches(this)
}

/**
 * Issue #872: rehydrate durable refs into live tiles. The local preview Uri is
 * gone (session-transient), so the tile renders from the remote path + display
 * name; the remote path is what the resend actually re-appends.
 */
internal fun List<DurableAttachmentRef>.toStagedAttachments(): List<StagedAttachment> =
    map { StagedAttachment(it.remotePath, it.displayName, previewUri = null, mimeType = it.mimeType) }
