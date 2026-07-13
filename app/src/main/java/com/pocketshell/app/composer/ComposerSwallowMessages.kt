package com.pocketshell.app.composer

/**
 * Issue #1531 (audit RC2): user-facing copy for the two composer send/attach
 * "still busy" states that previously `return`-ed SILENTLY, dropping the tap with
 * zero feedback (the maintainer's "silently dropped" class). Kept in this sibling
 * file (not the ratchet-guarded `PromptComposerViewModel` god-file) and aliased
 * from the VM companion so `PromptComposerViewModel.X` call sites keep resolving.
 */

/** A file pick arrived while a previous attachment upload is still staging. */
internal const val COMPOSER_ATTACHMENT_UPLOAD_BUSY_MESSAGE: String =
    "Still attaching the previous file — try again in a moment."

/** Send was tapped while a queued-row sidecar retry upload is in flight. */
internal const val COMPOSER_SEND_BUSY_UPLOADING_MESSAGE: String =
    "Still sending the previous attachment — try again in a moment."
