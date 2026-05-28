package com.pocketshell.app.share

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.webkit.MimeTypeMap
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Orchestrates one share-target upload (issue #138). Pure business
 * logic — the ViewModel composes this with Android lifecycle bits.
 *
 * Given a [ShareableItem] (a URI from the share intent, or an
 * in-memory text payload), an [SshKey] resolved for the chosen host,
 * and a remote inbox directory, the uploader:
 *
 * 1. Sanitises the display name.
 * 2. Opens a fresh SSH session to the host (kept short-lived; share
 *    upload is a one-shot operation, not a long session).
 * 3. Ensures `~/inbox/pocketshell/` exists with `mkdir -p`.
 * 4. SCP-uploads the bytes via [SshSession.uploadFile] /
 *    [SshSession.uploadStream].
 * 5. Returns the absolute remote path so the notification + toast can
 *    display it.
 *
 * Errors map to human-readable strings via [errorMessage] so the
 * caller never has to expose a raw stack trace to the user (acceptance
 * criterion in #138).
 */
/**
 * Uploads one [ShareableItem] to a host's inbox, returning the absolute
 * remote path on success. Extracted as an interface (issue #258) so the
 * [ShareViewModel] multi-file upload loop can be unit tested against a
 * fake that drives per-item success/failure without a live SSH session.
 */
internal interface ShareItemUploader {
    suspend fun upload(
        host: HostEntity,
        keyEntity: SshKeyEntity,
        item: ShareableItem,
    ): Result<String>
}

internal class ShareUploader(
    private val context: Context,
    /**
     * SSH connect factory injected so tests can stub the transport. The
     * default points at the same [SshConnection.connect] the rest of
     * the app uses.
     */
    private val connect: suspend (HostEntity, SshKey) -> Result<SshSession> = { host, key ->
        SshConnection.connect(
            host = host.hostname,
            port = host.port,
            user = host.username,
            key = key,
            knownHosts = KnownHostsPolicy.AcceptAll,
        )
    },
    /**
     * Time source for the filename timestamp. Defaulted to wall-clock
     * but injectable so tests can pin the value.
     */
    private val now: () -> Long = { System.currentTimeMillis() },
) : ShareItemUploader {

    /**
     * Run the upload. Returns the absolute remote path on success, a
     * descriptive error on failure (never throws — exceptions become
     * `Result.failure(SshException)` so the caller can map to a
     * notification without try/catch).
     */
    override suspend fun upload(
        host: HostEntity,
        keyEntity: SshKeyEntity,
        item: ShareableItem,
    ): Result<String> = withContext(Dispatchers.IO) {
        val keyFile = File(keyEntity.privateKeyPath)
        if (!keyFile.exists()) {
            return@withContext Result.failure(
                SshException("No SSH key for host ${host.name} (missing ${keyEntity.name})"),
            )
        }
        val key: SshKey = SshKey.Path(keyFile)
        val sessionResult = connect(host, key)
        val session = sessionResult.getOrElse { e ->
            return@withContext Result.failure(
                SshException(errorMessage("connect", e), e),
            )
        }
        try {
            session.use { live ->
                ensureInboxDirectory(live)
                val timestamp = formatTimestamp(now())
                val sanitised = FilenameSanitiser.sanitise(
                    item.displayName,
                    defaultExtension = item.fallbackExtension,
                )
                val remoteName = FilenameSanitiser.composeRemoteName(timestamp, sanitised)
                val remotePath = "$INBOX_DIRECTORY/$remoteName"

                when (item) {
                    is ShareableItem.UriItem -> uploadUri(live, item, remotePath)
                    is ShareableItem.TextItem -> uploadText(live, item, remotePath, remoteName)
                }
                // Render the user-visible path with the `~/` prefix
                // even though SFTP itself sees a home-relative path.
                Result.success("$INBOX_DISPLAY_PATH/$remoteName")
            }
        } catch (e: SshException) {
            Result.failure(e)
        } catch (e: Throwable) {
            Result.failure(SshException(errorMessage("upload", e), e))
        }
    }

    private suspend fun ensureInboxDirectory(session: SshSession) {
        // SFTP `open(path)` requires every parent directory of the
        // remote path to already exist. We resolve `$HOME` and create
        // the inbox hierarchy via the exec channel because the SFTP
        // subsystem's `mkdir` is more verbose to drive (per-segment)
        // and the exec channel handles `mkdir -p` plus `$HOME`
        // expansion in one call.
        val mk = session.exec("mkdir -p \"\$HOME/inbox/pocketshell\"")
        if (mk.exitCode != 0) {
            throw SshException(
                "Permission denied on /inbox: ${mk.stderr.ifBlank { mk.stdout.trim() }}",
            )
        }
    }

    private suspend fun uploadUri(
        session: SshSession,
        item: ShareableItem.UriItem,
        remotePath: String,
    ) {
        val resolver = context.contentResolver
        val length = item.size ?: resolveSize(resolver, item.uri)
        if (length == null) {
            // SCP needs a content length up-front. When the content
            // provider doesn't tell us, drain the stream into a temp
            // file under the cache directory so we can use uploadFile.
            val temp = drainToTempFile(resolver, item.uri)
            try {
                session.uploadFile(temp, remotePath)
            } finally {
                temp.delete()
            }
            return
        }

        resolver.openInputStream(item.uri)?.use { input ->
            session.uploadStream(
                input = input,
                length = length,
                name = remotePath.substringAfterLast('/'),
                remotePath = remotePath,
            )
        } ?: throw SshException("Could not read shared file (content provider returned null)")
    }

    private suspend fun uploadText(
        session: SshSession,
        item: ShareableItem.TextItem,
        remotePath: String,
        remoteName: String,
    ) {
        val bytes = item.text.toByteArray(Charsets.UTF_8)
        bytes.inputStream().use { input ->
            session.uploadStream(
                input = input,
                length = bytes.size.toLong(),
                name = remoteName,
                remotePath = remotePath,
            )
        }
    }

    private fun resolveSize(resolver: ContentResolver, uri: Uri): Long? {
        return try {
            resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index < 0) return@use null
                val size = cursor.getLong(index)
                if (size <= 0) null else size
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun drainToTempFile(resolver: ContentResolver, uri: Uri): File {
        val dir = File(context.cacheDir, "share-uploads").also { it.mkdirs() }
        val temp = File.createTempFile("share-", ".bin", dir)
        try {
            resolver.openInputStream(uri).use { input ->
                if (input == null) {
                    throw SshException("Could not read shared file (content provider returned null)")
                }
                temp.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            return temp
        } catch (t: Throwable) {
            temp.delete()
            throw t
        }
    }

    /**
     * Translate a transport / IO failure into the human-readable error
     * surface required by issue #138's acceptance criterion ("no raw
     * stack traces").
     *
     * The translation is intentionally narrow — it matches on the
     * exception class name and the message keywords sshj emits so we
     * can produce one of the spec'd messages, falling back to the raw
     * `Throwable.message` for anything we don't recognise. The
     * fallback is still short and one-line; the goal is to never let
     * a JVM stack dump bubble into a Toast.
     */
    private fun errorMessage(phase: String, error: Throwable): String {
        val raw = (error.message ?: error.javaClass.simpleName).trim()
        val lower = raw.lowercase(Locale.ROOT)
        return when {
            lower.contains("permission denied") -> raw.replaceFirstChar { it.uppercase() }
            lower.contains("connection refused") -> "Connection refused"
            lower.contains("connection reset") || lower.contains("connection closed") ->
                "Connection lost during $phase"
            lower.contains("unknownhost") || lower.contains("unknown host") ->
                "Cannot resolve host"
            lower.contains("auth") -> "Authentication failed"
            lower.contains("timed out") || lower.contains("timeout") ->
                "Connection timed out"
            else -> raw.lineSequence().firstOrNull()?.take(160) ?: "Upload failed"
        }
    }

    /**
     * Build a [ShareableItem.UriItem] from a content URI by querying
     * the resolver for the display name + size + MIME hint. Caller
     * should pre-flight by checking [ContentResolver.getType] when the
     * upload path needs to branch on MIME (e.g. text-vs-file
     * dispatch). Defensive: any missing field falls back to a sensible
     * default so the caller can still attempt the upload.
     */
    companion object {
        /**
         * Inbox path passed to the remote `cat > <path>` exec channel.
         * Relative paths land under the SSH user's home directory by
         * default — the login shell's cwd is `$HOME` when the exec
         * channel runs. We deliberately do NOT shell-quote the path
         * with `$HOME` because `~/` would not expand inside a
         * single-quoted shell argument; the home-relative form is
         * simpler and matches the SFTP subsystem's default semantics.
         */
        const val INBOX_DIRECTORY: String = "inbox/pocketshell"

        /** Display directory for user-visible messages. */
        const val INBOX_DISPLAY_PATH: String = "~/inbox/pocketshell"

        /** Format used for the timestamp prefix in remote filenames. */
        const val TIMESTAMP_PATTERN: String = "yyyyMMdd-HHmmss"

        fun formatTimestamp(epochMillis: Long): String {
            val format = SimpleDateFormat(TIMESTAMP_PATTERN, Locale.US)
            format.timeZone = TimeZone.getDefault()
            return format.format(Date(epochMillis))
        }

        /**
         * Probe the [Uri]'s display name + size via [OpenableColumns].
         * Returns `null` when nothing useful is exposed (raw `content://`
         * providers, file:// URIs without metadata).
         */
        fun queryUriDisplayName(resolver: ContentResolver, uri: Uri): String? = try {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) return@use null
                cursor.getString(index)
            }
        } catch (_: Throwable) {
            null
        }

        /**
         * Best-effort extension guess from a MIME type. Used as the
         * `defaultExtension` when the source app supplies neither a
         * display name nor a usable URI tail.
         */
        fun extensionForMimeType(mime: String?): String? {
            if (mime.isNullOrBlank()) return null
            return MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)
        }
    }
}

/**
 * One item to upload. The share intent surfaces either a content
 * [Uri] (the common case — image, audio, generic file) or a text
 * payload (`Intent.EXTRA_TEXT` with `text/plain`).
 */
internal sealed interface ShareableItem {

    /** Display name surfaced to the sanitiser. May be null/empty. */
    val displayName: String?

    /**
     * Extension to apply when [displayName] has none. `null` means
     * "let the sanitiser produce an extension-less filename".
     */
    val fallbackExtension: String?

    /** A content URI from `Intent.EXTRA_STREAM`. */
    data class UriItem(
        val uri: Uri,
        override val displayName: String?,
        val size: Long?,
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
