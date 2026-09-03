package com.pocketshell.next.share

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.util.Log

/**
 * One payload the system share sheet handed PocketShell (rewrite task P-9).
 *
 * Two shapes, because Android only ever sends two: a content URI the source app
 * exposes through a provider, or an in-memory string. The shipping client had a
 * third (`FileItem`, for its "share all crash reports" action); app2 has no such
 * action, so it is not ported — a case with no producer is dead code.
 */
sealed interface ShareableItem {

    /**
     * The name to show the user and to feed the sanitiser. May be null when the
     * source app exposed nothing useful; the uploader then falls back to the
     * provider's own display name, and finally to [FilenameSanitiser.DEFAULT_NAME].
     */
    val displayName: String?

    /**
     * Extension applied when the name has none — derived from the intent's MIME
     * type, so a `text/plain` share that arrives as `note` lands as `note.txt`.
     */
    val fallbackExtension: String?

    /** A content (or file) URI from `Intent.EXTRA_STREAM`. */
    data class UriItem(
        val uri: Uri,
        override val displayName: String?,
        val mimeType: String?,
        override val fallbackExtension: String?,
    ) : ShareableItem

    /** An in-memory text payload from `Intent.EXTRA_TEXT`. */
    data class TextItem(
        val text: String,
        override val displayName: String?,
        override val fallbackExtension: String? = "txt",
    ) : ShareableItem
}

/** What the picker shows for one staged item before anything has been read. */
internal fun ShareableItem.label(): String =
    displayName?.takeIf { it.isNotBlank() } ?: when (this) {
        is ShareableItem.TextItem -> "shared text"
        is ShareableItem.UriItem -> "shared file"
    }

/**
 * Converts an inbound share `Intent` into the staged item list, or an empty
 * list when it carries nothing routable.
 *
 * ## Why this is defensive to the point of paranoia
 *
 * [ShareActivity] is EXPORTED — any app on the device can start it with any
 * intent. Unmarshalling a malformed `EXTRA_STREAM` throws
 * `BadParcelableException` / `ClassCastException` *inside the platform*, and the
 * pre-33 untyped `getParcelableExtra` overloads are the classic offender. A
 * share target that crashes on a hostile intent is a share target that can be
 * crashed on demand, so every read is wrapped and any failure degrades to
 * "nothing to share".
 *
 * Supported shapes:
 * - `ACTION_SEND` + `EXTRA_STREAM` → one [ShareableItem.UriItem].
 * - `ACTION_SEND` + `EXTRA_TEXT` (no stream) → one [ShareableItem.TextItem].
 * - `ACTION_SEND_MULTIPLE` + `EXTRA_STREAM` list → one item per URI. All of
 *   them: the shipping client's first implementation took only the first URI
 *   and silently dropped the rest (its issue #258), which is exactly the bug a
 *   "share 4 screenshots" gesture hits.
 */
fun decodeShareIntent(intent: Intent?): List<ShareableItem> {
    if (intent == null) return emptyList()
    return runCatching { decodeExtras(intent) }.getOrElse { error ->
        Log.w(LOG_TAG, "could not decode share intent extras", error)
        emptyList()
    }
}

private const val LOG_TAG = "ShareIntent"

private fun decodeExtras(intent: Intent): List<ShareableItem> {
    val mime = intent.type
    return when (intent.action) {
        Intent.ACTION_SEND -> {
            val stream = extractStream(intent)
            when {
                stream != null -> listOf(buildUriItem(stream, intent, mime))
                else -> {
                    val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    if (text.isNullOrEmpty()) {
                        emptyList()
                    } else {
                        listOf(
                            ShareableItem.TextItem(
                                text = text,
                                displayName = subject?.takeIf { it.isNotBlank() } ?: "shared-text",
                            ),
                        )
                    }
                }
            }
        }

        Intent.ACTION_SEND_MULTIPLE -> extractStreamList(intent).map { buildUriItem(it, intent, mime) }

        else -> emptyList()
    }
}

private fun extractStream(intent: Intent): Uri? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_STREAM)
    }

private fun extractStreamList(intent: Intent): List<Uri> =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
    } else {
        @Suppress("DEPRECATION")
        intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
    }.orEmpty().filterNotNull()

private fun buildUriItem(uri: Uri, intent: Intent, mime: String?): ShareableItem.UriItem =
    ShareableItem.UriItem(
        uri = uri,
        // EXTRA_TITLE is what a well-behaved sender supplies; the URI's last
        // segment is the usual fallback. The provider's own DISPLAY_NAME is
        // better than both, but querying it needs a ContentResolver round trip,
        // so it happens at upload time (ShareContentReader) rather than here —
        // decoding must stay cheap enough to run before the first frame.
        displayName = intent.getStringExtra(Intent.EXTRA_TITLE) ?: uri.lastPathSegment,
        mimeType = mime,
        fallbackExtension = extensionForMimeType(mime),
    )

/**
 * Best-effort file extension for a MIME type, used when the sender supplied no
 * usable name at all.
 */
internal fun extensionForMimeType(mime: String?): String? {
    if (mime.isNullOrBlank()) return null
    return android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
}
