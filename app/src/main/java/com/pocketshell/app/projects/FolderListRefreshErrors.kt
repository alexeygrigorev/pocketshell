package com.pocketshell.app.projects

private val transientTransportDropMarkers = listOf(
    "encountered EOF",
    "Broken transport",
    "broken pipe",
    "Failed to open exec channel",
    "channel closed",
)

/**
 * Issue #711: classify the transient transport-EOF family that can recover on
 * the next reconcile. This intentionally stays narrower than the gateway's
 * stale-channel classifier: established stale-channel cases should surface the
 * retry panel instead of quietly looping.
 */
internal fun isTransientFolderRefreshDrop(cause: Throwable?): Boolean {
    var current: Throwable? = cause
    val seen = HashSet<Throwable>()
    while (current != null && seen.add(current)) {
        val message = current.message
        if (message != null &&
            transientTransportDropMarkers.any { marker ->
                message.contains(marker, ignoreCase = true)
            }
        ) {
            return true
        }
        current = current.cause
    }
    return false
}

/**
 * Issue #711: keep folder-list connect failures on compact user-facing copy.
 * Timeout errors already carry bounded retry text, so pass that through.
 */
internal fun folderListConnectErrorMessage(
    cause: Throwable,
    fallbackMessage: String,
): String =
    if (cause is FolderReconcileTimeoutException) {
        cause.message ?: fallbackMessage
    } else {
        fallbackMessage
    }
