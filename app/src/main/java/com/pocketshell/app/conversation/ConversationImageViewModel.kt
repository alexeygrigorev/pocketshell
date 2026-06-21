package com.pocketshell.app.conversation

import android.util.Base64
import androidx.lifecycle.ViewModel
import com.pocketshell.app.sessions.LeaseSessionExec
import com.pocketshell.app.sessions.LeaseSessionTarget
import com.pocketshell.core.agents.ConversationImage
import com.pocketshell.core.ssh.SshLeaseManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

/**
 * Issue #842: load transcript-referenced images for the Conversation view.
 *
 * Resolution order for one [ConversationImage]:
 *  1. **inline base64** → decode directly (no network);
 *  2. **a host file [ConversationImage.path]** → download over the app-wide WARM
 *     SSH lease (D21 — the SAME pooled transport the file viewer / session / tmux
 *     screens hold; NO new connection), exactly like
 *     [com.pocketshell.app.fileviewer.FileViewerViewModel] reads a host file; and
 *  3. **an `http(s)` [ConversationImage.url]** → fetch directly over HTTP.
 *
 * Everything runs on [Dispatchers.IO]; a failure returns a failed [Result] so the
 * row renders the path-text fallback rather than a crash/broken image.
 */
@HiltViewModel
class ConversationImageViewModel @Inject constructor(
    private val sshLeaseManager: SshLeaseManager,
) : ViewModel() {

    /**
     * Build a [ConversationImageLoader] bound to a host [target] (and the active
     * pane [cwd] so a relative pasted path resolves where the agent worked). The
     * screen provides the result via [LocalConversationImageLoader] so the
     * conversation rows can fetch images without each holding a lease handle.
     */
    fun loaderFor(target: LeaseSessionTarget, cwd: String?): ConversationImageLoader =
        ConversationImageLoader { image -> load(image, target, cwd) }

    private suspend fun load(
        image: ConversationImage,
        target: LeaseSessionTarget,
        cwd: String?,
    ): Result<ByteArray> {
        // 1. Inline base64 — no fetch needed.
        image.base64Data?.takeIf { it.isNotBlank() }?.let { data ->
            return runCatching {
                withContext(Dispatchers.Default) {
                    Base64.decode(data, Base64.DEFAULT)
                }
            }.mapCatching { it.takeIf { b -> b.isNotEmpty() } ?: error("empty base64 image") }
        }

        // 2. Host file path — read over the warm SSH lease (D21).
        image.path?.takeIf { it.isNotBlank() }?.let { rawPath ->
            return loadHostFile(rawPath, target, cwd)
        }

        // 3. An http(s) URL — fetch directly.
        image.url?.takeIf { it.isNotBlank() }?.let { url ->
            if (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)) {
                return loadHttp(url)
            }
        }

        return Result.failure(IllegalStateException("No fetchable image reference"))
    }

    /**
     * Download a host image over the warm lease — the file-viewer read path:
     * [LeaseSessionExec.withSession] borrows the pooled transport and
     * `downloadFile` reads it over SFTP/exec on a channel of that already-warm
     * connection. Resolves `$HOME` / a relative path against the pane cwd so a
     * `~/shot.png` or `shot.png` the agent referenced reads correctly.
     */
    private suspend fun loadHostFile(
        rawPath: String,
        target: LeaseSessionTarget,
        cwd: String?,
    ): Result<ByteArray> =
        LeaseSessionExec.withSession(
            leaseManager = sshLeaseManager,
            target = target,
        ) { session ->
            val home = remoteHome(session)
            val resolved = resolveRemotePath(rawPath, cwd, home)
            session.downloadFile(resolved, MAX_IMAGE_BYTES)
        }

    private suspend fun loadHttp(url: String): Result<ByteArray> = runCatching {
        withContext(Dispatchers.IO) {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = HTTP_TIMEOUT_MS
                readTimeout = HTTP_TIMEOUT_MS
                requestMethod = "GET"
            }
            try {
                if (connection.responseCode !in 200..299) {
                    error("HTTP ${connection.responseCode}")
                }
                connection.inputStream.use { it.readBoundedBytes(MAX_IMAGE_BYTES) }
            } finally {
                connection.disconnect()
            }
        }
    }

    private suspend fun remoteHome(session: com.pocketshell.core.ssh.SshSession): String? = try {
        session.exec("printf '%s\\n' \"\$HOME\"")
            .takeIf { it.exitCode == 0 }
            ?.stdout
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.trimEnd('/')
            ?.takeIf { it.startsWith("/") }
    } catch (e: CancellationException) {
        throw e
    } catch (_: Throwable) {
        null
    }

    companion object {
        /**
         * Cap on an inline transcript image (20 MB) — comfortably covers any
         * agent screenshot while keeping a single decoded copy off the phone OOM
         * cliff (mirrors the file viewer's [MAX_PREVIEW_BYTES]).
         */
        const val MAX_IMAGE_BYTES: Long = 20L * 1024 * 1024

        private const val HTTP_TIMEOUT_MS = 8_000

        /**
         * Resolve a transcript image path against the pane cwd + remote home.
         * Absolute paths pass through; `~`/`~/x` expands to [home]; a relative
         * path joins [cwd] (then [home]) so `shot.png` the agent referenced in
         * its working dir reads correctly. Pure — unit-tested.
         */
        internal fun resolveRemotePath(input: String, cwd: String?, home: String?): String {
            val path = input.trim()
            return when {
                path.startsWith("/") -> path
                path == "~" -> home ?: path
                path.startsWith("~/") -> if (home != null) "$home/${path.removePrefix("~/")}" else path
                !cwd.isNullOrBlank() -> "${cwd.trimEnd('/')}/$path"
                home != null -> "$home/$path"
                else -> path
            }
        }
    }
}

/** Read at most [maxBytes] from a stream, erroring if the source exceeds it. */
private fun java.io.InputStream.readBoundedBytes(maxBytes: Long): ByteArray {
    val buffer = java.io.ByteArrayOutputStream()
    val chunk = ByteArray(64 * 1024)
    var total = 0L
    while (true) {
        val read = read(chunk)
        if (read < 0) break
        total += read
        if (total > maxBytes) error("Image exceeds ${maxBytes / (1024 * 1024)} MB cap")
        buffer.write(chunk, 0, read)
    }
    return buffer.toByteArray()
}
