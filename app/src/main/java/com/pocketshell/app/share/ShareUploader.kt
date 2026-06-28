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
import com.pocketshell.core.ssh.SshUploadProgress
import com.pocketshell.core.storage.entity.HostEntity
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * 5. Returns the absolute remote path so the in-app result surface can
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
        target: ShareTarget = ShareTarget.HostInbox,
        onProgress: ((SshUploadProgress) -> Unit)? = null,
    ): Result<String>
}

/**
 * Where a shared file/screenshot should land on the chosen host
 * (issue #473).
 *
 * Two destinations coexist (D22 hard-cut — no compatibility flag, both
 * are always available):
 *
 *  - [HostInbox] — the original behavior: `~/inbox/pocketshell/<file>`.
 *    The global drop point an agent reads via the absolute inbox path.
 *  - [Project] — drops the file into a specific project's local
 *    `.inbox/` folder (`<project>/.inbox/<file>`, creating `.inbox/` on
 *    demand) so an agent/orchestrator working *in that project* sees it
 *    via a relative `.inbox/` rather than the global inbox path.
 */
internal sealed interface ShareTarget {

    /** Existing behavior — `~/inbox/pocketshell/<file>`. */
    data object HostInbox : ShareTarget

    /**
     * Drop into `<remoteProjectPath>/.inbox/<file>`.
     *
     * [remoteProjectPath] is the project root as known to the app
     * (a watched-root [com.pocketshell.core.storage.entity.ProjectRootEntity.path]
     * or a live session's `pane_current_path`). It may be absolute
     * (`/home/alexey/git/foo`) or `~`/`$HOME`-relative (`~/git/foo`);
     * the uploader expands it to an absolute path via the exec channel
     * before creating the `.inbox/` directory inside the project root
     * (never inside the home dir).
     */
    data class Project(val remoteProjectPath: String) : ShareTarget
}

internal class ShareUploader(
    private val context: Context,
    /**
     * SSH connect factory injected so tests can stub the transport. The
     * default points at the same [SshConnection.connect] the rest of
     * the app uses.
     */
    private val connect: suspend (HostEntity, SshKey, String, String?) -> Result<SshSession> = { host, key, _, _ ->
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
        target: ShareTarget,
        onProgress: ((SshUploadProgress) -> Unit)?,
    ): Result<String> = withContext(Dispatchers.IO) {
        val preparedUriFile = if (item is ShareableItem.UriItem) {
            val resolver = context.contentResolver
            withTimeoutOrNull(SHARE_SAF_TIMEOUT_MS) {
                drainToTempFile(resolver, item.uri)
            } ?: return@withContext Result.failure(
                SshException("Could not read shared file before upload"),
            )
        } else {
            null
        }
        val keyFile = File(keyEntity.privateKeyPath)
        val key: SshKey = SshKey.Path(keyFile)
        val sessionResult = connect(host, key, keyEntity.privateKeyPath, SHARE_LEASE_PURPOSE_UPLOAD)
        val session = sessionResult.getOrElse { e ->
            preparedUriFile?.delete()
            return@withContext Result.failure(
                SshException(errorMessage("connect", e), e),
            )
        }
        try {
            session.use { live ->
                val timestamp = formatTimestamp(now())
                val sanitised = FilenameSanitiser.sanitise(
                    item.displayName,
                    defaultExtension = item.fallbackExtension,
                )
                val remoteName = FilenameSanitiser.composeRemoteName(timestamp, sanitised)

                // Resolve the destination directory + the user-visible
                // path string. For [ShareTarget.HostInbox] this is the
                // home-relative inbox; for a project target we create the
                // project's `.inbox/` and resolve its ABSOLUTE path so the
                // SCP write lands inside the project root (never the home
                // dir).
                val destination = ensureDestinationDirectory(live, target)
                val remotePath = "${destination.uploadDirectory}/$remoteName"

                when (item) {
                    is ShareableItem.UriItem -> live.uploadFile(
                        preparedUriFile
                            ?: throw SshException("Could not read shared file before upload"),
                        remotePath,
                        onProgress,
                    )
                    is ShareableItem.TextItem -> uploadText(live, item, remotePath, remoteName, onProgress)
                    is ShareableItem.FileItem -> live.uploadFile(item.file, remotePath, onProgress)
                }
                Result.success("${destination.displayDirectory}/$remoteName")
            }
        } catch (e: SshException) {
            Result.failure(e)
        } catch (e: Throwable) {
            Result.failure(SshException(errorMessage("upload", e), e))
        } finally {
            preparedUriFile?.delete()
        }
    }

    /**
     * Resolved destination for one upload.
     *
     * @property uploadDirectory the directory the SCP/SFTP write targets.
     *   For the host inbox this is the home-relative path
     *   ([INBOX_DIRECTORY]); for a project it is the project's ABSOLUTE
     *   `.inbox/` directory so SCP writes inside the project root rather
     *   than the home dir (`~` does not expand on the SFTP path).
     * @property displayDirectory the user-visible directory shown in the
     *   success state.
     */
    private data class ResolvedDestination(
        val uploadDirectory: String,
        val displayDirectory: String,
    )

    private suspend fun ensureDestinationDirectory(
        session: SshSession,
        target: ShareTarget,
    ): ResolvedDestination = when (target) {
        ShareTarget.HostInbox -> {
            ensureHostInboxDirectory(session)
            ResolvedDestination(
                uploadDirectory = INBOX_DIRECTORY,
                displayDirectory = INBOX_DISPLAY_PATH,
            )
        }
        is ShareTarget.Project -> ensureProjectInboxDirectory(session, target.remoteProjectPath)
    }

    private suspend fun ensureHostInboxDirectory(session: SshSession) {
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

    /**
     * Create `<project>/.inbox/` on demand and resolve its ABSOLUTE
     * path so the subsequent SCP write lands inside the project root.
     *
     * `~` / `$HOME` only expand through the login shell (the exec
     * channel), never on the SFTP/SCP path, so we run one exec that:
     *
     *  1. `mkdir -p "<expandable>/.inbox"` — creates the project inbox.
     *  2. `cd "<expandable>/.inbox" && pwd` — echoes the resolved
     *     absolute directory we then feed to [SshSession.uploadFile] /
     *     [SshSession.uploadStream].
     *
     * The project path is rewritten to a shell-expandable form (`~` /
     * `~/` → `$HOME`) before quoting so `$HOME` expands inside the
     * double-quoted argument (per the [ensureHostInboxDirectory]
     * pattern).
     */
    private suspend fun ensureProjectInboxDirectory(
        session: SshSession,
        rawProjectPath: String,
    ): ResolvedDestination {
        val shellPath = toShellExpandablePath(rawProjectPath)
        val command =
            "mkdir -p \"$shellPath/.inbox\" && cd \"$shellPath/.inbox\" && pwd"
        val result = session.exec(command)
        if (result.exitCode != 0) {
            throw SshException(
                "Couldn't create $rawProjectPath/.inbox: " +
                    result.stderr.ifBlank { result.stdout.trim() }.ifBlank { "exit ${result.exitCode}" },
            )
        }
        val absoluteInbox = result.stdout.lineSequence()
            .map { it.trim() }
            .lastOrNull { it.isNotEmpty() }
            ?.takeIf { it.startsWith("/") }
            ?: throw SshException(
                "Couldn't resolve $rawProjectPath/.inbox (no path returned)",
            )
        return ResolvedDestination(
            uploadDirectory = absoluteInbox,
            displayDirectory = absoluteInbox,
        )
    }

    private suspend fun uploadText(
        session: SshSession,
        item: ShareableItem.TextItem,
        remotePath: String,
        remoteName: String,
        onProgress: ((SshUploadProgress) -> Unit)?,
    ) {
        val bytes = item.text.toByteArray(Charsets.UTF_8)
        bytes.inputStream().use { input ->
            session.uploadStream(
                input = input,
                length = bytes.size.toLong(),
                name = remoteName,
                remotePath = remotePath,
                onProgress = onProgress,
            )
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
         * Rewrite a project path into a form whose `~` / `$HOME` prefix
         * expands inside a double-quoted shell argument (issue #473).
         *
         *  - `~`            → `$HOME`
         *  - `~/git/foo`    → `$HOME/git/foo`
         *  - `$HOME/...`    → unchanged (already expandable)
         *  - `/abs/path`    → unchanged (absolute)
         *
         * Trailing slashes are trimmed so `<path>/.inbox` never becomes
         * `<path>//.inbox`. The result is meant to be embedded inside a
         * double-quoted argument (`"<result>/.inbox"`), where `$HOME`
         * expands but the rest is treated literally.
         */
        fun toShellExpandablePath(rawPath: String): String {
            val trimmed = rawPath.trim().trimEnd('/').ifEmpty { return "\$HOME" }
            return when {
                trimmed == "~" -> "\$HOME"
                trimmed.startsWith("~/") -> "\$HOME/" + trimmed.removePrefix("~/")
                else -> trimmed
            }
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

        const val SHARE_LEASE_PURPOSE_UPLOAD = "share-upload"
        const val SHARE_SAF_TIMEOUT_MS: Long = 10_000L
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

    /**
     * A local file on disk (no content provider). Used by the "Share all
     * reports" action (issue #466), which packs the local crash/diagnostic
     * reports into a single zip under the cache dir and uploads it via the
     * same inbox path. The uploader streams it straight through
     * [SshSession.uploadFile]; the caller deletes [file] once the upload
     * completes.
     */
    data class FileItem(
        val file: File,
        override val displayName: String?,
        override val fallbackExtension: String? = null,
    ) : ShareableItem
}
