package com.pocketshell.app.fileviewer

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.ssh.KnownHostsPolicy
import com.pocketshell.core.ssh.SshConnection
import com.pocketshell.core.ssh.SshFileNotFoundException
import com.pocketshell.core.ssh.SshFileTooLargeException
import com.pocketshell.core.ssh.SshKey
import com.pocketshell.core.ssh.SshSession
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * UI state for the in-app file viewer (issue #497).
 */
sealed interface FileViewerUiState {

    /** Resolving + fetching the file over SFTP. */
    data class Loading(val displayPath: String) : FileViewerUiState

    /** An image was fetched and cached to [cacheFile] for the zoom/pan view. */
    data class Image(
        val displayPath: String,
        val cacheFile: File,
        val sizeBytes: Long,
    ) : FileViewerUiState

    /** A UTF-8 text file; [content] is the decoded body for the monospace view. */
    data class TextContent(
        val displayPath: String,
        val content: String,
        val sizeBytes: Long,
    ) : FileViewerUiState

    /**
     * A PDF was fetched and cached to [cacheFile]. The paged PDF view opens it
     * with [android.graphics.pdf.PdfRenderer] and renders one page at a time
     * to a bitmap on a background dispatcher.
     */
    data class Pdf(
        val displayPath: String,
        val cacheFile: File,
        val sizeBytes: Long,
    ) : FileViewerUiState

    /**
     * An audio file was fetched and cached to [cacheFile]. The audio panel
     * plays it with [android.media.MediaPlayer] (platform decoder — no
     * third-party dep) directly off the cached file, with play/pause and seek.
     */
    data class Audio(
        val displayPath: String,
        val cacheFile: File,
        val sizeBytes: Long,
    ) : FileViewerUiState

    /**
     * The file can't be previewed — too large, binary-non-image, missing, or
     * the host was unreachable. [message] is user-facing.
     */
    data class CannotPreview(
        val displayPath: String,
        val message: String,
    ) : FileViewerUiState
}

/**
 * Backs [FileViewerScreen] — issue #497.
 *
 * Responsibilities:
 *  - Resolve a relative/`~`/absolute path against the session cwd
 *    ([RemotePathResolver]).
 *  - Open a one-shot [SshSession] from the host credentials (same per-action
 *    pattern as [com.pocketshell.app.projects.RepoBrowserViewModel]) and read
 *    the file over SFTP/`cat` with a hard size cap ([MAX_PREVIEW_BYTES]).
 *  - Decide image-vs-text-vs-binary ([FileTypeDetector]); cache image bytes
 *    to the app cache dir so the Compose image loader reads them off disk.
 *
 * Read-only. PDFs render page-by-page (#498); audio plays in an in-app
 * MediaPlayer panel with play/pause + seek (#499). No editing.
 */
@HiltViewModel
class FileViewerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow<FileViewerUiState>(
        FileViewerUiState.Loading(displayPath = ""),
    )
    val state: StateFlow<FileViewerUiState> = _state.asStateFlow()

    private var loadJob: Job? = null
    private var lastRequest: Request? = null

    /**
     * Bind to a host + path and fetch. Re-binding with the identical request
     * is a no-op so a recomposition doesn't re-download.
     */
    fun bind(request: Request) {
        if (request == lastRequest && _state.value !is FileViewerUiState.CannotPreview) return
        lastRequest = request
        load(request)
    }

    /** Re-run the fetch (wired to "Retry" on the can't-preview panel). */
    fun retry() {
        lastRequest?.let { load(it) }
    }

    private fun load(request: Request) {
        val resolved = RemotePathResolver.resolve(request.path, request.cwd)
        loadJob?.cancel()
        _state.value = FileViewerUiState.Loading(displayPath = resolved)
        loadJob = viewModelScope.launch {
            _state.value = withContext(Dispatchers.IO) { fetch(request, resolved) }
        }
    }

    private suspend fun fetch(request: Request, resolved: String): FileViewerUiState {
        val session = connect(request)
            ?: return FileViewerUiState.CannotPreview(
                displayPath = resolved,
                message = "Couldn't reach ${request.username}@${request.hostname}.",
            )
        return try {
            val bytes = session.downloadFile(resolved, MAX_PREVIEW_BYTES)
            when (FileTypeDetector.detect(resolved, bytes)) {
                FileViewerType.IMAGE -> {
                    val cached = writeToCache(resolved, bytes)
                    FileViewerUiState.Image(
                        displayPath = resolved,
                        cacheFile = cached,
                        sizeBytes = bytes.size.toLong(),
                    )
                }
                FileViewerType.TEXT -> FileViewerUiState.TextContent(
                    displayPath = resolved,
                    content = bytes.toString(Charsets.UTF_8),
                    sizeBytes = bytes.size.toLong(),
                )
                FileViewerType.PDF -> if (pdfExceedsCap(bytes.size.toLong())) {
                    pdfTooLarge(resolved, bytes.size.toLong())
                } else {
                    FileViewerUiState.Pdf(
                        displayPath = resolved,
                        cacheFile = writeToCache(resolved, bytes),
                        sizeBytes = bytes.size.toLong(),
                    )
                }
                FileViewerType.AUDIO -> if (audioExceedsCap(bytes.size.toLong())) {
                    audioTooLarge(resolved, bytes.size.toLong())
                } else {
                    FileViewerUiState.Audio(
                        displayPath = resolved,
                        cacheFile = writeToCache(resolved, bytes),
                        sizeBytes = bytes.size.toLong(),
                    )
                }
                FileViewerType.BINARY -> FileViewerUiState.CannotPreview(
                    displayPath = resolved,
                    message = "Can't preview this file — it looks like a binary file " +
                        "that isn't an image (${bytes.size} bytes).",
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: SshFileNotFoundException) {
            FileViewerUiState.CannotPreview(
                displayPath = resolved,
                message = "No such file on the server: $resolved",
            )
        } catch (e: SshFileTooLargeException) {
            FileViewerUiState.CannotPreview(
                displayPath = resolved,
                message = "File is too large to preview (limit " +
                    "${MAX_PREVIEW_BYTES / (1024 * 1024)} MB).",
            )
        } catch (t: Throwable) {
            FileViewerUiState.CannotPreview(
                displayPath = resolved,
                message = "Couldn't read the file: ${t.message ?: t.javaClass.simpleName}",
            )
        } finally {
            runCatching { session.close() }
        }
    }

    private fun pdfTooLarge(resolved: String, sizeBytes: Long): FileViewerUiState =
        FileViewerUiState.CannotPreview(
            displayPath = resolved,
            message = "PDF is too large to preview (${sizeBytes / (1024 * 1024)} MB; " +
                "limit ${MAX_PDF_BYTES / (1024 * 1024)} MB).",
        )

    private fun audioTooLarge(resolved: String, sizeBytes: Long): FileViewerUiState =
        FileViewerUiState.CannotPreview(
            displayPath = resolved,
            message = "Audio file is too large to play (${sizeBytes / (1024 * 1024)} MB; " +
                "limit ${MAX_AUDIO_BYTES / (1024 * 1024)} MB).",
        )

    private fun writeToCache(resolved: String, bytes: ByteArray): File {
        val dir = File(appContext.cacheDir, CACHE_SUBDIR).apply { mkdirs() }
        val safeName = sanitizeCacheName(resolved)
        val file = File(dir, safeName)
        file.writeBytes(bytes)
        return file
    }

    private suspend fun connect(request: Request): SshSession? =
        SshConnection.connect(
            host = request.hostname,
            port = request.port,
            user = request.username,
            key = SshKey.Path(File(request.keyPath)),
            passphrase = request.passphrase?.copyOf(),
            knownHosts = KnownHostsPolicy.AcceptAll,
        ).getOrNull()

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
    }

    /**
     * Host credentials + the remote path to open. Mirrors the credential
     * shape used by sibling per-host screens; the path is resolved against
     * [cwd] before the SFTP read.
     */
    data class Request(
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
        val path: String,
        val cwd: String?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Request) return false
            if (hostname != other.hostname) return false
            if (port != other.port) return false
            if (username != other.username) return false
            if (keyPath != other.keyPath) return false
            if (path != other.path) return false
            if (cwd != other.cwd) return false
            if (passphrase != null) {
                if (other.passphrase == null) return false
                if (!passphrase.contentEquals(other.passphrase)) return false
            } else if (other.passphrase != null) return false
            return true
        }

        override fun hashCode(): Int {
            var result = hostname.hashCode()
            result = 31 * result + port
            result = 31 * result + username.hashCode()
            result = 31 * result + keyPath.hashCode()
            result = 31 * result + path.hashCode()
            result = 31 * result + (cwd?.hashCode() ?: 0)
            result = 31 * result + (passphrase?.contentHashCode() ?: 0)
            return result
        }
    }

    companion object {
        /**
         * Hard ceiling on a previewable file. 20 MB comfortably covers any
         * screenshot/PNG an agent shares and any log/source file, while
         * keeping a single decoded copy off the OOM cliff on a phone.
         */
        const val MAX_PREVIEW_BYTES: Long = 20L * 1024 * 1024

        /**
         * Tighter ceiling for PDFs. A PDF is rendered page-by-page to a
         * full-resolution bitmap (PdfRenderer), so a large document with many
         * heavy pages costs far more memory than an equivalent-sized image.
         * Cap at 10 MB so a single phone can render any normal document
         * (reports, manuals, slide exports) without the OOM cliff, while a
         * pathological PDF gets a clear "too large" message instead of a crash.
         */
        const val MAX_PDF_BYTES: Long = 10L * 1024 * 1024

        /**
         * Size guard for PDFs. True when [sizeBytes] exceeds [MAX_PDF_BYTES],
         * in which case the viewer shows a clear "too large" message instead
         * of feeding a huge document to PdfRenderer and risking an OOM.
         * Pure — unit-tested.
         */
        internal fun pdfExceedsCap(sizeBytes: Long): Boolean = sizeBytes > MAX_PDF_BYTES

        /**
         * Ceiling for an audio file the in-app player will load. Audio is
         * streamed from a cached file by [android.media.MediaPlayer] (it does
         * not decode the whole file into memory up front), so the cap is only
         * bounded by the overall fetch cap [MAX_PREVIEW_BYTES] and the cache
         * write — 20 MB comfortably covers a typical voice note / short clip
         * while keeping the SFTP fetch and cache write quick on a phone. A
         * larger file gets a clear "too large" message instead of a long fetch.
         */
        const val MAX_AUDIO_BYTES: Long = MAX_PREVIEW_BYTES

        /**
         * Size guard for audio. True when [sizeBytes] exceeds [MAX_AUDIO_BYTES],
         * in which case the viewer shows a clear "too large" message instead of
         * caching and playing an oversized file. Pure — unit-tested.
         */
        internal fun audioExceedsCap(sizeBytes: Long): Boolean = sizeBytes > MAX_AUDIO_BYTES

        internal const val CACHE_SUBDIR = "file-viewer"

        /**
         * Turn a remote path into a collision-resistant, filesystem-safe
         * cache file name. The hash prefix disambiguates two files with the
         * same basename from different directories; the sanitized basename
         * (which keeps the original extension) keeps the cached file
         * type-sniffable by the image loader. Visible-for-test.
         */
        internal fun sanitizeCacheName(remotePath: String): String {
            val hash = Integer.toHexString(remotePath.hashCode())
            val base = remotePath.substringAfterLast('/')
                .replace(Regex("[^A-Za-z0-9._-]"), "_")
                .takeLast(48)
                .ifEmpty { "file" }
            return "${hash}_$base"
        }
    }
}
