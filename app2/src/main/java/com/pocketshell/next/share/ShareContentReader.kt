package com.pocketshell.next.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.pocketshell.next.files.TransferLimits
import com.pocketshell.next.files.readCapped
import java.io.IOException
import java.io.InputStream
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * The bytes of one staged share item, plus the best name its source exposes.
 *
 * [displayName] is deliberately re-reported here rather than taken from the
 * item: the intent's name is a guess (an `EXTRA_TITLE` the sender may not set,
 * or a URI tail like `1000000042`), while the provider's `DISPLAY_NAME` column
 * is the real filename. Only the reader is in a position to ask.
 */
class ShareContent(val displayName: String?, val bytes: ByteArray)

/**
 * Turns a [ShareableItem] into bytes.
 *
 * This is the ONE Android-touching seam in the upload path. [ShareUploader]
 * takes it as a constructor parameter, so its tests drive real byte payloads
 * (including a read that fails and one that is over the cap) without a
 * `ContentResolver`, a SAF provider, or Robolectric.
 */
interface ShareContentReader {
    /**
     * Reads [item] whole. Throws [IOException] — with a message fit to show the
     * user — when the source cannot be read, answers too slowly, or holds more
     * than [TransferLimits.MAX_UPLOAD_BYTES].
     */
    suspend fun read(item: ShareableItem): ShareContent
}

/**
 * The production [ShareContentReader]: `ContentResolver` for URI items, the
 * string's own UTF-8 bytes for text items.
 *
 * ## Bounds, not hope
 *
 * A content provider is another app's code running on our request, so both of
 * its failure modes are handled explicitly rather than trusted:
 *
 * - **Slow**: `openInputStream` and the read are wrapped in
 *   [SHARE_READ_TIMEOUT_MS]. A provider that never answers used to leave the
 *   old client's share sheet spinning forever.
 * - **Huge**: the stream is read through [readCapped], the same cap the file
 *   explorer's upload uses, because the whole payload is held in memory once
 *   ([com.pocketshell.core.transport.SftpChannel.write] takes a `ByteArray`).
 *   Refusing beats an OOM, and refusing beats a truncated file landing on the
 *   host under its real name.
 *
 * [openStream] and [queryDisplayName] are parameters with production defaults
 * so a unit test can substitute either without a resolver.
 */
class ContentResolverShareContentReader(
    private val dispatcher: CoroutineDispatcher,
    private val openStream: (Uri) -> InputStream?,
    private val queryDisplayName: (Uri) -> String?,
) : ShareContentReader {

    constructor(context: Context, dispatcher: CoroutineDispatcher) : this(
        dispatcher = dispatcher,
        openStream = { uri -> context.contentResolver.openInputStream(uri) },
        queryDisplayName = { uri -> queryDisplayName(context.contentResolver, uri) },
    )

    override suspend fun read(item: ShareableItem): ShareContent = when (item) {
        is ShareableItem.TextItem -> ShareContent(
            displayName = item.displayName,
            bytes = item.text.toByteArray(Charsets.UTF_8),
        )

        is ShareableItem.UriItem -> withContext(dispatcher) {
            try {
                withTimeout(SHARE_READ_TIMEOUT_MS) {
                    val name = queryDisplayName(item.uri)?.takeIf { it.isNotBlank() }
                        ?: item.displayName
                    val stream = openStream(item.uri)
                        ?: throw IOException("Could not read the shared file")
                    val label = name ?: item.label()
                    val bytes = stream.use {
                        readCapped(it, TransferLimits.MAX_UPLOAD_BYTES, label)
                    }
                    ShareContent(displayName = name, bytes = bytes)
                }
            } catch (timeout: TimeoutCancellationException) {
                throw IOException(
                    "The app that shared this file did not respond in time",
                    timeout,
                )
            } catch (failure: SecurityException) {
                // The share sheet's read grant is scoped to the receiving
                // activity's task and dies with it; a stale re-delivery lands
                // here rather than as an opaque crash.
                throw IOException("PocketShell is no longer allowed to read this file", failure)
            }
        }
    }

    private companion object {
        /**
         * How long the source app gets. Ten seconds is the same budget the
         * shipping client used for its SAF drain — long enough for a cloud
         * provider to materialise a cached file, short enough that a wedged
         * provider does not hold the share sheet open indefinitely.
         */
        const val SHARE_READ_TIMEOUT_MS: Long = 10_000L

        /**
         * The provider's `DISPLAY_NAME`, or null when it exposes none (raw
         * `content://` providers and `file://` URIs commonly do not).
         */
        fun queryDisplayName(resolver: ContentResolver, uri: Uri): String? = runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { cursor ->
                    if (!cursor.moveToFirst()) return@use null
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index < 0) null else cursor.getString(index)
                }
        }.getOrNull()
    }
}
