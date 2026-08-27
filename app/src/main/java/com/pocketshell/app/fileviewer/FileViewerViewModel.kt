package com.pocketshell.app.fileviewer

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.app.AppTeardownScope
import com.pocketshell.app.requireMainThread
import com.pocketshell.app.sessions.LeaseSessionExec
import com.pocketshell.app.sessions.LeaseSessionTarget
import com.pocketshell.app.share.FilenameSanitiser
import com.pocketshell.app.share.ShareUploader
import com.pocketshell.core.ssh.SshException
import com.pocketshell.core.ssh.SshFileNotFoundException
import com.pocketshell.core.ssh.SshFileTooLargeException
import com.pocketshell.core.ssh.SshLeaseManager
import com.pocketshell.core.ssh.SshSession
import com.pocketshell.core.ssh.shellSingleQuote
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import javax.inject.Inject

/**
 * Text-viewer reading preferences (issue #696). [wordWrap] wraps long lines to
 * the viewport instead of horizontal scrolling; [renderMarkdown] shows a
 * `.md`/`.markdown` file formatted instead of raw source.
 */
data class FileViewerReadingPrefs(
    val wordWrap: Boolean,
    val renderMarkdown: Boolean,
)

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
     *
     * When the file was successfully downloaded but is an unsupported/binary
     * type, [sizeBytes] and [cacheFile] are populated so the download-only
     * panel can show the file info and offer a Download action (issue #623).
     * For other cannot-preview cases (host unreachable, file not found, too
     * large), these remain null/0 and only the message + retry are shown.
     *
     * Issue #748 — when a relative path is missing under the session cwd but a
     * bounded basename search found same-named files deeper in the tree (the
     * agent worked in a subdirectory), [locateCandidates] holds those absolute
     * paths so the panel can offer "open this instead" rows rather than a
     * dead-end Retry. Empty when no candidates were found or the search didn't
     * apply (rooted path, unreachable host, etc.).
     */
    data class CannotPreview(
        val displayPath: String,
        val message: String,
        val sizeBytes: Long = 0L,
        val cacheFile: File? = null,
        val locateCandidates: List<String> = emptyList(),
    ) : FileViewerUiState

    /** Issue #1715 — this host has no open files (or the last tab was closed). */
    data object EmptyWorkspace : FileViewerUiState

    /**
     * Issue #1715 — the host CLI does not expose `tree workspace-get`. Direct
     * viewing of a requested path still works; restored-workspace UI does not
     * silently invent a phone-side tab store (D22).
     */
    data class WorkspaceUnavailable(
        val message: String = "Open files unavailable; update PocketShell on this host",
    ) : FileViewerUiState
}

/**
 * Backs [FileViewerScreen] — issue #497.
 *
 * Responsibilities:
 *  - Resolve a relative/`~`/absolute path against the session cwd
 *    ([RemotePathResolver]).
 *  - Borrow a session from the app-wide warm [SshLeaseManager] transport — the
 *    LITERALLY-same per-host connection the session / folder / tmux / explorer
 *    screens hold ([LeaseSessionExec]) — and read the file over SFTP/`cat` on a
 *    channel of that already-warm transport, with a hard size cap
 *    ([MAX_PREVIEW_BYTES]). No per-open cold ~3-4s SSH handshake (issue #697):
 *    the lease is RELEASED (refcount decrement), never `close()`d, so the pool
 *    keeps the transport warm for the next open / the session screen.
 *  - Decide image-vs-text-vs-binary ([FileTypeDetector]); cache image bytes
 *    to the app cache dir so the Compose image loader reads them off disk.
 *
 * Read-only. PDFs render page-by-page (#498); audio plays in an in-app
 * MediaPlayer panel with play/pause + seek (#499). No editing.
 */
@HiltViewModel
class FileViewerViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val sshLeaseManager: SshLeaseManager,
    private val prefs: FileViewerPrefsStore = FileViewerPrefsStore(appContext),
    private val workspaceSource: FileWorkspaceRemoteSource = FileWorkspaceRemoteSource(),
) : ViewModel() {

    /** Injectable clock for tab-recency tests; production uses wall-clock millis. */
    internal var nowMillis: () -> Long = { System.currentTimeMillis() }

    /**
     * Issue #2339 — the ONE seam for every blocking SSH hop this ViewModel owns
     * (the workspace hydrate + file fetch in [load], the review write in
     * [submitReview], the annotated-image write in [submitAnnotation]).
     *
     * Production is [Dispatchers.IO]. Unit tests pin it to
     * `StandardTestDispatcher(testScheduler)` so the load path resolves on the
     * TEST scheduler instead of a real thread pool — the Shape-A half of the
     * #1048 de-flake convention. Without this seam a unit test can only wait for
     * the fetch with a hand-rolled wall-clock pump, which is exactly what made
     * `FileViewerWorkspaceTest` flaky by construction: three different members
     * of that class failed across three runs of the same tree, because the pump
     * returns as soon as ONE observable lands while the sibling work (the
     * fire-and-forget workspace upsert, the live re-fetch that must lose the
     * race against the cache paint) is still in flight on a real IO thread.
     */
    internal var ioDispatcher: CoroutineDispatcher = Dispatchers.IO

    /**
     * Issue #2339 — the sibling seam for the ONE cpu-bound hop this ViewModel
     * owns: flattening the annotation overlay onto the source bitmap in
     * [submitAnnotation] before the upload.
     *
     * Production is [Dispatchers.Default]. It is separate from [ioDispatcher]
     * because the two carry genuinely different production intent (a bounded
     * cpu pool vs an unbounded blocking-IO pool) and collapsing them would be a
     * behaviour change smuggled in for the tests' convenience. Unit tests pin
     * BOTH to `StandardTestDispatcher(testScheduler)`: a submit that hops to a
     * real cpu pool halfway through is exactly as unwaitable as one that hops
     * to a real IO pool, so leaving this on [Dispatchers.Default] would have
     * forced `FileViewerAnnotationSubmitTest` to keep the hand-rolled
     * `System.currentTimeMillis()` pump this issue exists to delete.
     */
    internal var computeDispatcher: CoroutineDispatcher = Dispatchers.Default

    /**
     * Workspace writes belong to the process, not to this screen's lifecycle.
     * Tests replace this with a controlled scope; production uses the scope
     * that also owns bounded connection teardown and is never cancelled by
     * ViewModel/activity recreation.
     */
    internal var workspaceWriteScope: CoroutineScope = AppTeardownScope.scope

    private val _state = MutableStateFlow<FileViewerUiState>(
        FileViewerUiState.Loading(displayPath = ""),
    )
    val state: StateFlow<FileViewerUiState> = _state.asStateFlow()

    /**
     * Text-viewer reading preferences (issue #696): word wrap and whether
     * Markdown renders formatted. Seeded from the persisted [FileViewerPrefsStore]
     * so the choice survives navigation and restarts; toggling writes back.
     */
    private val _readingPrefs = MutableStateFlow(
        FileViewerReadingPrefs(
            wordWrap = prefs.isWordWrap(),
            renderMarkdown = prefs.isRenderMarkdown(),
        ),
    )
    val readingPrefs: StateFlow<FileViewerReadingPrefs> = _readingPrefs.asStateFlow()

    /**
     * Review-comments state (issue #714). Held here — not in Compose `remember`
     * — so the pending comments survive scroll and config change. Cleared on a
     * successful submit (slice 2).
     */
    private val _reviewState = MutableStateFlow(ReviewState())
    val reviewState: StateFlow<ReviewState> = _reviewState.asStateFlow()

    /**
     * One-shot results of a [submitReview] (issue #714, slice 2). The screen
     * collects this to surface a confirmation / error toast. A buffered
     * [MutableSharedFlow] (replay 0, extraBufferCapacity 1) so an emit from the
     * submit coroutine never suspends and a transient lack of collectors (a
     * config change mid-submit) doesn't deadlock the write.
     */
    private val _reviewEvents = MutableSharedFlow<ReviewSubmitEvent>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val reviewEvents: SharedFlow<ReviewSubmitEvent> = _reviewEvents.asSharedFlow()

    /**
     * Image-annotation state (issue #764). Held here — not in Compose `remember`
     * — so the pending markup survives scroll / config change, exactly as
     * [reviewState] does. Cleared on a successful submit.
     */
    private val _annotationState = MutableStateFlow(ImageAnnotationState())
    val annotationState: StateFlow<ImageAnnotationState> = _annotationState.asStateFlow()

    /**
     * One-shot results of a [submitAnnotation] (issue #764). The screen collects
     * this to surface the post-submit confirmation / error. Buffered the same way
     * as [_reviewEvents] so an emit never suspends.
     */
    private val _annotationEvents = MutableSharedFlow<AnnotationSubmitEvent>(
        replay = 0,
        extraBufferCapacity = 1,
    )
    val annotationEvents: SharedFlow<AnnotationSubmitEvent> = _annotationEvents.asSharedFlow()

    private var loadJob: Job? = null
    private var lastRequest: Request? = null
    /**
     * In-memory workspace/cache scope. A host row can be edited in place, so
     * [Request.hostId] alone is not enough to identify the remote account.
     * Keep the endpoint/account/key identity with the durable workspace scope
     * or a changed host profile can reuse the previous server's tabs/cache.
     */
    private var workspaceScope: WorkspaceScope? = null
    private var workspaceHydrated: Boolean = false
    private var workspaceAvailable: Boolean = true
    private var workspaceWriteTail: Job? = null
    private var workspaceWriteGeneration: Long = 0L

    /**
     * Issue #1715 — this Unix account's open-file workspace. Hydrated once
     * from the host on the first [bind]; mutated on successful open / tab
     * switch / close. Never polled.
     */
    private val _workspace = MutableStateFlow(FileWorkspace.Empty)
    val workspace: StateFlow<FileWorkspace> = _workspace.asStateFlow()

    private val _workspaceWriteState = MutableStateFlow<FileWorkspaceWriteState>(
        FileWorkspaceWriteState.Idle,
    )
    val workspaceWriteState: StateFlow<FileWorkspaceWriteState> = _workspaceWriteState.asStateFlow()

    /**
     * Issue #1715 — a tab switch/close that is blocked on pending review or
     * annotation work. Null when no guard is showing.
     */
    private val _pendingTabAction = MutableStateFlow<PendingTabAction?>(null)
    val pendingTabAction: StateFlow<PendingTabAction?> = _pendingTabAction.asStateFlow()

    /** One-shot navigation result for a clean/just-submitted queued Back. */
    private val _backEvents = MutableSharedFlow<Unit>(replay = 0, extraBufferCapacity = 1)
    val backEvents: SharedFlow<Unit> = _backEvents.asSharedFlow()

    /**
     * Tiny bounded LRU of already-rendered viewer states keyed by
     * `(hostId, resolved absolute path)` (issue #697, re-keyed in #1715).
     * A relative open and its restored absolute tab must share one cache
     * entry. Image/PDF/audio/binary entries that wrote bytes to the on-disk
     * cache are skipped — that cached file may have been swept, so they
     * always re-fetch.
     */
    private val contentCache = object : LinkedHashMap<ContentCacheKey, FileViewerUiState>(
        CONTENT_CACHE_CAP + 1,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<ContentCacheKey, FileViewerUiState>,
        ): Boolean = size > CONTENT_CACHE_CAP
    }

    /** Toggle word wrap and persist the choice. */
    fun toggleWordWrap() {
        val next = !_readingPrefs.value.wordWrap
        prefs.setWordWrap(next)
        _readingPrefs.value = _readingPrefs.value.copy(wordWrap = next)
    }

    /** Toggle Markdown-rendered-vs-raw and persist the choice. */
    fun toggleRenderMarkdown() {
        val next = !_readingPrefs.value.renderMarkdown
        prefs.setRenderMarkdown(next)
        _readingPrefs.value = _readingPrefs.value.copy(renderMarkdown = next)
    }

    // --- Review mode (issue #714) -------------------------------------------

    /** Turn review mode on/off (the per-line comment gutter + header actions). */
    fun toggleReviewMode() {
        _reviewState.value = _reviewState.value.copy(active = !_reviewState.value.active)
    }

    /**
     * Set or overwrite the comment on [line] (1-based). A blank [text] clears
     * the line's comment (an emptied edit sheet removes it).
     */
    fun setLineComment(line: Int, text: String) {
        _reviewState.value = _reviewState.value.withLineComment(line, text)
    }

    /** Delete the comment on [line] (1-based). */
    fun deleteLineComment(line: Int) {
        _reviewState.value = _reviewState.value.withoutLineComment(line)
    }

    /** Set or overwrite the whole-file comment; a blank [text] clears it. */
    fun setFileComment(text: String) {
        _reviewState.value = _reviewState.value.withFileComment(text)
    }

    /** Clear the whole-file comment. */
    fun deleteFileComment() {
        _reviewState.value = _reviewState.value.withoutFileComment()
    }

    /**
     * Submit the pending review (issue #714, slice 2). Builds the
     * `pocketshell_review` YAML for the current pending comments and writes it to
     * the reviewed host's `~/inbox/pocketshell/reviews/<sanitised>-<ts>.yaml` over
     * the SAME warm viewer SSH lease — NO new connection (D21): the borrow is
     * keyed byte-identically to the viewer's `fetch`, so it reuses the pooled
     * transport and only releases the refcount.
     *
     * Lifecycle:
     *  - sets [ReviewState.submitting] true for the duration (the tray Submit
     *    button disables off this);
     *  - on success clears the pending set and emits a confirmation
     *    [ReviewSubmitEvent.Success];
     *  - on failure KEEPS every pending comment (nothing lost) and emits a calm
     *    [ReviewSubmitEvent.Failure] (message via the [ShareUploader]-shaped
     *    [reviewErrorMessage]).
     *
     * [host] is the host alias (the YAML `host` field; the screen threads it in
     * from the navigation destination). A no-op when there are no pending
     * comments, the open file isn't text, or a submit is already in flight.
     */
    fun submitReview(host: String) {
        val current = _reviewState.value
        if (!current.hasPending || current.submitting) return
        val text = (_state.value as? FileViewerUiState.TextContent) ?: return
        val request = lastRequest ?: return

        _reviewState.value = current.copy(submitting = true)
        viewModelScope.launch {
            val lines = text.content.split("\n")
            val yaml = ReviewExport.buildReviewYaml(
                state = current,
                host = host,
                file = text.displayPath,
                lines = lines,
                submittedAt = isoUtcNow(),
            )
            Log.d(REVIEW_LOG_TAG, "Submitting pocketshell_review YAML (${current.pendingCount} comments):\n$yaml")

            val result = withContext(ioDispatcher) {
                writeReview(request, text.displayPath, yaml)
            }
            result.fold(
                onSuccess = { remotePath ->
                    // Clear the pending set but keep review mode active so the
                    // user can keep reviewing the same file.
                    _reviewState.value = _reviewState.value.cleared()
                    consumePendingTabActionIfClean()
                    _reviewEvents.tryEmit(
                        ReviewSubmitEvent.Success(host = host, count = current.pendingCount, remotePath = remotePath),
                    )
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    // Keep the pending comments — nothing is silently lost.
                    _reviewState.value = _reviewState.value.copy(submitting = false)
                    _reviewEvents.tryEmit(ReviewSubmitEvent.Failure(reviewErrorMessage(error)))
                    Log.w(REVIEW_LOG_TAG, "Review submit failed: ${error.message}", error)
                },
            )
        }
    }

    /**
     * Write [yaml] to `~/inbox/pocketshell/reviews/<sanitised>-<ts>.yaml` on the
     * reviewed host over the reused viewer lease. Mirrors [ShareUploader]'s
     * `mkdir -p` + `uploadStream` (`cat > '<path>'`) mechanism, but drives it
     * through [LeaseSessionExec.withSession] so it borrows the SAME warm
     * transport the viewer already holds instead of dialing a fresh connection.
     * Resolves `$HOME` to an absolute prefix first (the single-quoted `cat >`
     * path never expands `~`). Returns the absolute remote path written.
     */
    private suspend fun writeReview(
        request: Request,
        displayPath: String,
        yaml: String,
    ): Result<String> =
        LeaseSessionExec.withSession(
            leaseManager = sshLeaseManager,
            target = request.toLeaseTarget(),
        ) { session ->
            val home = remoteHomeDirectory(session)
                ?: conventionalRemoteHome(request.username)
                ?: throw SshException("Couldn't resolve the remote home directory")
            val reviewsDir = "$home/$REVIEWS_SUBDIR"
            val mk = session.exec("mkdir -p ${shellSingleQuote(reviewsDir)}")
            if (mk.exitCode != 0) {
                throw SshException(
                    "Couldn't create $reviewsDir: ${mk.stderr.ifBlank { mk.stdout.trim() }.ifBlank { "exit ${mk.exitCode}" }}",
                )
            }
            val name = reviewFileName(displayPath)
            val remotePath = "$reviewsDir/$name"
            val bytes = yaml.toByteArray(Charsets.UTF_8)
            bytes.inputStream().use { input ->
                session.uploadStream(
                    input = input,
                    length = bytes.size.toLong(),
                    name = name,
                    remotePath = remotePath,
                )
            }
        }

    // --- Image annotation (issue #764) --------------------------------------

    /** Turn annotate mode on/off (the markup overlay + toolbar). */
    fun toggleAnnotationMode() {
        _annotationState.value = _annotationState.value.toggledActive()
    }

    /** Select the active annotation tool (Pan / Pen / Arrow). */
    fun setAnnotationTool(tool: AnnotationTool) {
        _annotationState.value = _annotationState.value.withTool(tool)
    }

    /** Select the stroke colour for new annotations. */
    fun setAnnotationColor(argb: Int) {
        _annotationState.value = _annotationState.value.withColor(argb)
    }

    /** Append a finished annotation (a completed Pen stroke or Arrow drag). */
    fun addAnnotation(annotation: Annotation) {
        _annotationState.value = _annotationState.value.withAnnotation(annotation)
    }

    /** Undo the last annotation. */
    fun undoAnnotation() {
        _annotationState.value = _annotationState.value.undone()
    }

    /** Set/overwrite the optional caption note written into the YAML sidecar. */
    fun setAnnotationNote(text: String) {
        _annotationState.value = _annotationState.value.withNote(text)
    }

    /**
     * Submit the pending annotations (issue #764). Flattens the live
     * annotations onto the source bitmap, encodes a PNG, and writes it +
     * a `pocketshell_annotation` YAML sidecar to the host's
     * `~/inbox/pocketshell/annotations/` over the SAME warm viewer lease — NO
     * new connection (D21), mirroring [submitReview].
     *
     * Lifecycle: sets [ImageAnnotationState.submitting] for the duration; on
     * success clears the pending markup and emits [AnnotationSubmitEvent.Success];
     * on failure KEEPS every annotation (nothing lost) and emits a calm
     * [AnnotationSubmitEvent.Failure]. A no-op when there is nothing to submit,
     * the open file isn't an image, or a submit is already in flight.
     */
    fun submitAnnotation(host: String) {
        val current = _annotationState.value
        if (!current.hasAnnotations || current.submitting) return
        val image = (_state.value as? FileViewerUiState.Image) ?: return
        val request = lastRequest ?: return

        _annotationState.value = current.copy(submitting = true)
        viewModelScope.launch {
            val pngBytes = withContext(computeDispatcher) {
                renderAnnotatedPng(image.cacheFile, current.annotations)
            }
            if (pngBytes == null) {
                _annotationState.value = _annotationState.value.copy(submitting = false)
                _annotationEvents.tryEmit(AnnotationSubmitEvent.Failure("Couldn't render the annotated image"))
                return@launch
            }
            val submittedAt = isoUtcNow()
            val result = withContext(ioDispatcher) {
                writeAnnotatedImage(request, image.displayPath, host, pngBytes, current.note, submittedAt)
            }
            result.fold(
                onSuccess = { remotePath ->
                    _annotationState.value = _annotationState.value.cleared()
                    consumePendingTabActionIfClean()
                    _annotationEvents.tryEmit(AnnotationSubmitEvent.Success(host = host, remotePath = remotePath))
                },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    _annotationState.value = _annotationState.value.copy(submitting = false)
                    _annotationEvents.tryEmit(AnnotationSubmitEvent.Failure(reviewErrorMessage(error)))
                    Log.w(ANNOTATION_LOG_TAG, "Annotation submit failed: ${error.message}", error)
                },
            )
        }
    }

    /**
     * Re-decode the cached source image at full resolution and flatten the
     * [annotations] onto it (issue #764). Re-decode (rather than reuse the
     * display [ImageBitmap]) so the export keeps the source's full resolution.
     * Returns null when the cache file can't be decoded.
     */
    private fun renderAnnotatedPng(cacheFile: File, annotations: List<Annotation>): ByteArray? {
        val source = BoundedImageDecoder.decodeFile(cacheFile) ?: return null
        return try {
            AnnotationRenderer.flattenToPng(source, annotations)
        } finally {
            source.recycle()
        }
    }

    /**
     * Write the flattened [pngBytes] to
     * `~/inbox/pocketshell/annotations/<sanitised>-<ts>.png` plus a matching
     * `pocketshell_annotation` `.yaml` sidecar on the host over the reused viewer
     * lease (issue #764). A near-copy of [writeReview]: same `mkdir -p` +
     * `uploadStream` mechanism over [LeaseSessionExec.withSession] (D21, no new
     * dial). Returns the absolute remote PNG path written.
     */
    private suspend fun writeAnnotatedImage(
        request: Request,
        displayPath: String,
        host: String,
        pngBytes: ByteArray,
        note: String?,
        submittedAt: String,
    ): Result<String> =
        LeaseSessionExec.withSession(
            leaseManager = sshLeaseManager,
            target = request.toLeaseTarget(),
        ) { session ->
            val home = remoteHomeDirectory(session)
                ?: conventionalRemoteHome(request.username)
                ?: throw SshException("Couldn't resolve the remote home directory")
            val dir = "$home/$ANNOTATIONS_SUBDIR"
            val mk = session.exec("mkdir -p ${shellSingleQuote(dir)}")
            if (mk.exitCode != 0) {
                throw SshException(
                    "Couldn't create $dir: ${mk.stderr.ifBlank { mk.stdout.trim() }.ifBlank { "exit ${mk.exitCode}" }}",
                )
            }
            val stem = annotationFileStem(displayPath)
            val pngName = "$stem.png"
            val pngPath = "$dir/$pngName"
            pngBytes.inputStream().use { input ->
                session.uploadStream(
                    input = input,
                    length = pngBytes.size.toLong(),
                    name = pngName,
                    remotePath = pngPath,
                )
            }
            // The YAML sidecar mirrors pocketshell_review: machine-readable wrapper
            // carrying host + source provenance + the flattened-image path + note.
            val yaml = AnnotationExport.buildAnnotationYaml(
                host = host,
                sourceFile = displayPath,
                image = pngPath,
                submittedAt = submittedAt,
                note = note,
            )
            Log.d(ANNOTATION_LOG_TAG, "Submitting pocketshell_annotation sidecar:\n$yaml")
            val yamlName = "$stem.yaml"
            val yamlBytes = yaml.toByteArray(Charsets.UTF_8)
            yamlBytes.inputStream().use { input ->
                session.uploadStream(
                    input = input,
                    length = yamlBytes.size.toLong(),
                    name = yamlName,
                    remotePath = "$dir/$yamlName",
                )
            }
            pngPath
        }

    /**
     * Bind to a host + path and fetch.
     *
     * Re-binding distinguishes a *recomposition re-bind* from a *genuine
     * reopen* by whether the current fetch has settled (issue #1713):
     *
     *  - A re-bind of the IDENTICAL request whose fetch is still **in flight**
     *    is a true no-op recompose (a config change / composition re-entry
     *    before the first load landed). Restarting it would cancel and re-issue
     *    the same download for no benefit — the #697 redundant-download the old
     *    early-return guarded against — so it is suppressed.
     *  - A re-bind of the identical request whose fetch has **settled** is a
     *    genuine reopen: the host-side file may have changed since we last read
     *    it (the reported bug — the viewer showed stale content on reopen). We
     *    run [load] again so a fresh SFTP re-fetch reconciles and the live host
     *    content wins. [load] paints the instant LRU cache first (no flicker,
     *    no cold handshake — #697), then the background fetch replaces it.
     *
     * A different request (fresh navigation) always loads.
     */
    fun bind(request: Request) {
        requireMainThread("FileViewerViewModel.bind")
        val requestScope = request.workspaceScope()
        if (workspaceScope != requestScope) {
            // A Hilt VM can outlive one viewer destination in the hand-rolled
            // navigator. Never let host A's durable tabs become host B's
            // in-memory workspace or skip B's one-time hydrate. The scope
            // includes the endpoint/account/key because a host row may be
            // edited in place while retaining its Room id.
            loadJob?.cancel()
            workspaceScope = requestScope
            workspaceHydrated = false
            workspaceAvailable = true
            workspaceWriteGeneration++
            workspaceWriteTail = null
            _workspace.value = FileWorkspace.Empty
            _workspaceWriteState.value = FileWorkspaceWriteState.Idle
            _pendingTabAction.value = null
        }
        val sameRequest = request == lastRequest
        lastRequest = request
        if (sameRequest && loadJob?.isActive == true) return
        if (!workspaceHydrated) {
            loadJob?.cancel()
            _state.value = FileViewerUiState.Loading(displayPath = request.path.orEmpty())
            loadJob = viewModelScope.launch {
                if (!hydrateWorkspace(request) || !isCurrentRequest(request)) return@launch
                workspaceHydrated = true
                // Drop the hydrate job so [load] does not cancel this
                // coroutine (and the fetch it is about to start).
                loadJob = null
                continueAfterHydrate(request)
            }
            return
        }
        continueAfterHydrate(request)
    }

    private fun continueAfterHydrate(request: Request) {
        val path = request.path
        if (path.isNullOrBlank()) {
            if (!workspaceAvailable) {
                _state.value = FileViewerUiState.WorkspaceUnavailable()
                return
            }
            val active = _workspace.value.activePath
            if (active.isNullOrBlank()) {
                _state.value = FileViewerUiState.EmptyWorkspace
                return
            }
            // The restored active tab is the effective current request. Keep
            // the identity used by the load-result guard in sync with the
            // request that is actually being fetched.
            val restored = request.copy(path = active, cwd = null)
            lastRequest = restored
            load(restored)
            return
        }
        load(request)
    }

    private suspend fun hydrateWorkspace(request: Request): Boolean {
        val result = LeaseSessionExec.withSession(
            leaseManager = sshLeaseManager,
            target = request.toLeaseTarget(),
        ) { session ->
            workspaceSource.getWorkspace(session)
        }.getOrElse { FileWorkspaceResult.Unavailable }
        // Cancellation can race a completed SSH call. Do not let an old host
        // hydrate overwrite the workspace selected by a later bind.
        currentCoroutineContext().ensureActive()
        if (!isCurrentRequest(request)) return false
        workspaceAvailable = result.available
        if (result.available) {
            _workspace.value = result.workspace
        }
        return true
    }

    /**
     * Issue #1715 — switch the top viewer onto [tab] through the same #1713
     * cache-then-live [load] path. Pending review/annotation work blocks the
     * switch until the user stays, submits, or discards.
     */
    fun switchToTab(tab: OpenFileTab) {
        requireMainThread("FileViewerViewModel.switchToTab")
        val current = lastRequest ?: return
        val showing = currentDisplayPath()
        if (tab.absolutePath != showing && hasPendingWork()) {
            _pendingTabAction.value = PendingTabAction.Switch(tab)
            return
        }
        performSwitch(current, tab)
    }

    /**
     * Issue #1715 — close [tab]. Closing the active tab with pending work is
     * guarded; closing an inactive tab is always allowed.
     */
    fun closeTab(tab: OpenFileTab) {
        requireMainThread("FileViewerViewModel.closeTab")
        val showing = currentDisplayPath()
        val isActive = tab.absolutePath == showing
        if (isActive && hasPendingWork()) {
            _pendingTabAction.value = PendingTabAction.Close(tab)
            return
        }
        performClose(tab)
    }

    fun stayOnTab() {
        _pendingTabAction.value = null
    }

    /**
     * Guard both the system Back gesture and the file-viewer app-bar Back.
     * Returning false means the screen must remain in place while the dirty
     * dialog is showing; returning true means the caller may navigate away.
     */
    fun requestBack(): Boolean {
        requireMainThread("FileViewerViewModel.requestBack")
        if (hasPendingWork()) {
            _pendingTabAction.value = PendingTabAction.Back
            return false
        }
        _pendingTabAction.value = null
        return true
    }

    /** Empty-workspace "Open path" — bind a typed path onto the current host. */
    fun openPath(path: String) {
        val current = lastRequest ?: return
        val trimmed = path.trim()
        if (trimmed.isEmpty()) return
        bind(current.copy(path = trimmed, cwd = current.cwd))
    }

    /**
     * Discard pending review/annotation work, then perform the queued switch
     * or close exactly once.
     */
    fun discardPendingWorkAndProceed(): PendingTabAction? {
        val action = _pendingTabAction.value ?: return null
        _reviewState.value = ReviewState()
        _annotationState.value = ImageAnnotationState()
        _pendingTabAction.value = null
        when (action) {
            is PendingTabAction.Switch -> lastRequest?.let { performSwitch(it, action.tab) }
            is PendingTabAction.Close -> performClose(action.tab)
            PendingTabAction.Back -> Unit
        }
        return action
    }

    /** Consume a queued tab/back action after Submit removed the dirty state. */
    private fun consumePendingTabActionIfClean() {
        if (hasPendingWork()) return
        val action = _pendingTabAction.value ?: return
        _pendingTabAction.value = null
        when (action) {
            is PendingTabAction.Switch -> lastRequest?.let { performSwitch(it, action.tab) }
            is PendingTabAction.Close -> performClose(action.tab)
            PendingTabAction.Back -> _backEvents.tryEmit(Unit)
        }
    }

    fun hasPendingWork(): Boolean {
        val review = _reviewState.value
        val annotation = _annotationState.value
        return review.hasPending ||
            review.submitting ||
            annotation.annotations.isNotEmpty() ||
            annotation.note != null ||
            annotation.submitting
    }

    private fun performSwitch(current: Request, tab: OpenFileTab) {
        resetCleanModes()
        persistWorkspace(
            FileWorkspaceReducer.activate(_workspace.value, tab.absolutePath, nowMillis()),
        )
        val next = current.copy(path = tab.absolutePath, cwd = null)
        lastRequest = next
        load(next)
    }

    private fun performClose(tab: OpenFileTab) {
        if (currentDisplayPath() == tab.absolutePath) {
            loadJob?.cancel()
        }
        val next = FileWorkspaceReducer.close(_workspace.value, tab.absolutePath)
        persistWorkspace(next)
        val active = next.activePath
        if (active == null) {
            resetCleanModes()
            _state.value = FileViewerUiState.EmptyWorkspace
            lastRequest = lastRequest?.copy(path = null, cwd = null)
            return
        }
        if (active != currentDisplayPath()) {
            lastRequest?.let { load(it.copy(path = active, cwd = null).also { req -> lastRequest = req }) }
        }
    }

    private fun resetCleanModes() {
        if (!hasPendingWork()) {
            _reviewState.value = ReviewState()
            _annotationState.value = ImageAnnotationState()
        }
    }

    /** Re-run the fetch (wired to "Retry" on the can't-preview panel). */
    fun retry() {
        val current = lastRequest ?: return
        if (current.path.isNullOrBlank()) {
            workspaceHydrated = false
            bind(current)
            return
        }
        load(current)
    }

    /**
     * Open an absolute path the basename-search fallback located (issue #748).
     * Re-binds the current request onto the chosen [absolutePath] — it's already
     * rooted, so [RemotePathResolver] passes it through verbatim and the viewer
     * fetches the file that actually exists in the agent's subdirectory.
     */
    fun openLocated(absolutePath: String) {
        val current = lastRequest ?: return
        val next = current.copy(path = absolutePath)
        lastRequest = next
        load(next)
    }

    private fun load(request: Request) {
        val inputPath = request.path ?: return
        val resolved = RemotePathResolver.resolve(
            input = inputPath,
            cwd = request.cwd,
            remoteHome = conventionalRemoteHome(request.username),
        )
        // A fresh open clears any annotate session — the overlay belongs to the
        // previous image, not the one being loaded (issue #764).
        if (_annotationState.value.active || _annotationState.value.hasAnnotations) {
            _annotationState.value = ImageAnnotationState()
        }
        loadJob?.cancel()
        // Re-open of a just-viewed text file paints instantly from the LRU; a
        // fresh fetch still runs underneath and the live result wins (#697).
        // Keyed by resolved absolute path so a relative open and its restored
        // absolute tab share one cache entry (#1715).
        activateKnownTabBeforePaint(resolved)
        val cached = contentCache[ContentCacheKey(request.workspaceScope(), resolved)]
        _state.value = cached ?: FileViewerUiState.Loading(displayPath = resolved)
        loadJob = viewModelScope.launch {
            val fetched = withContext(ioDispatcher) { fetch(request, resolved) }
            ensureActive()
            // A cancelled SSH read may finish at the same instant as a new
            // bind. The cancellation alone is not a sufficient stale-result
            // guard: never paint host/path A over the request now showing B.
            if (!isCurrentRequest(request)) return@launch
            cacheIfReusable(request, fetched)
            considerWorkspaceUpsert(request, fetched)
            // publish content after the synchronous workspace mutation so the
            // tab strip and the loaded viewer cannot briefly disagree.
            _state.value = fetched
        }
    }

    private fun isCurrentRequest(request: Request): Boolean =
        workspaceScope == request.workspaceScope() && lastRequest == request

    /**
     * Identity for in-memory workspace and text-cache isolation. Do not use
     * the mutable Room id alone: editing a host row can point that id at a
     * different server or Unix account while the Hilt ViewModel survives.
     * The passphrase is intentionally excluded; it is a credential, not a
     * remote workspace identity, and [Request.equals] still protects
     * in-flight content results when it changes.
     */
    private fun Request.workspaceScope(): WorkspaceScope = WorkspaceScope(
        hostId = hostId,
        hostname = hostname,
        port = port,
        username = username,
        keyPath = keyPath,
    )

    /**
     * A cached reopen is visible before its live reconciliation completes.
     * If the path is already durable, make its active identity agree with that
     * immediate paint; only a successful fetch may add a brand-new tab.
     */
    private fun activateKnownTabBeforePaint(resolved: String) {
        if (!workspaceAvailable) return
        val workspace = _workspace.value
        if (workspace.activePath == resolved || workspace.orderedTabs.none { it.absolutePath == resolved }) {
            return
        }
        persistWorkspace(FileWorkspaceReducer.activate(workspace, resolved, nowMillis()))
    }

    private suspend fun fetch(request: Request, fallbackResolved: String): FileViewerUiState {
        val result = LeaseSessionExec.withSession(
            leaseManager = sshLeaseManager,
            target = request.toLeaseTarget(),
        ) { session ->
            readFromSession(request, session)
        }
        return result.getOrElse { error ->
            if (error is CancellationException) throw error
            FileViewerUiState.CannotPreview(
                displayPath = fallbackResolved,
                message = "Couldn't reach ${request.username}@${request.hostname}.",
            )
        }
    }

    private suspend fun readFromSession(request: Request, session: SshSession): FileViewerUiState {
        val inputPath = request.path ?: return FileViewerUiState.EmptyWorkspace
        val remoteHome = remoteHomeDirectory(session) ?: conventionalRemoteHome(request.username)
        val resolved = RemotePathResolver.resolve(
            input = inputPath,
            cwd = request.cwd,
            remoteHome = remoteHome,
        )
        return try {
            downloadAndRender(session, resolved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: SshFileNotFoundException) {
            // The path resolved against the session cwd is missing — but the
            // agent may have worked in a subdirectory (issue #748). Search the
            // session-cwd tree for the basename; auto-open a confident single
            // match, else offer the candidates instead of a dead-end Retry.
            locateMissingRelativeFile(request, session, resolved, remoteHome)
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
        }
    }

    /**
     * Download [resolved] over the warm [session] and map it to the right
     * preview state. Throws [SshFileNotFoundException] / [SshFileTooLargeException]
     * straight through so the caller can run the issue-#748 basename fallback or
     * the too-large message. Shared by the primary read and the located-match
     * re-read so the located file goes through the identical detect/cache path.
     */
    private suspend fun downloadAndRender(session: SshSession, resolved: String): FileViewerUiState {
        val bytes = session.downloadFile(resolved, MAX_PREVIEW_BYTES)
        return when (FileTypeDetector.detect(resolved, bytes)) {
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
                message = "Can't preview this file type.",
                sizeBytes = bytes.size.toLong(),
                cacheFile = writeToCache(resolved, bytes),
            )
        }
    }

    /**
     * Issue #748 — the relative path resolved against the session cwd is
     * missing. Run a bounded basename `find` under the session-cwd tree (the
     * agent likely `cd`'d into a subdirectory before generating the file). If a
     * confident single/suffix match is found, download + render it directly. If
     * several same-named files exist, surface them as locate candidates. If none
     * (or the search doesn't apply), fall back to the plain not-found message.
     */
    private suspend fun locateMissingRelativeFile(
        request: Request,
        session: SshSession,
        resolved: String,
        remoteHome: String?,
    ): FileViewerUiState {
        val plan = RemotePathResolver.searchPlan(
            input = request.path ?: return FileViewerUiState.CannotPreview(
                displayPath = resolved,
                message = fileNotFoundMessage(resolved),
            ),
            cwd = request.cwd,
            remoteHome = remoteHome,
        ) ?: return FileViewerUiState.CannotPreview(
            displayPath = resolved,
            message = fileNotFoundMessage(resolved),
        )

        val candidates = try {
            searchByBasename(session, plan)
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            Log.w(LOG_TAG, "Basename search failed for ${plan.basename}: ${t.message}", t)
            emptyList()
        }
        if (candidates.isEmpty()) {
            return FileViewerUiState.CannotPreview(
                displayPath = resolved,
                message = fileNotFoundMessage(resolved),
            )
        }

        val match = RemotePathResolver.chooseSearchMatch(plan, candidates)
        if (match != null) {
            // Confident single/suffix match — open it directly. A miss/too-large
            // on the relocated file degrades to the candidate list / message.
            try {
                return downloadAndRender(session, match)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                // Fall through to the candidate affordance below.
            }
        }

        return FileViewerUiState.CannotPreview(
            displayPath = resolved,
            message = locateCandidatesMessage(plan.basename, candidates.size),
            locateCandidates = candidates,
        )
    }

    /**
     * Run a bounded `find` for [SearchPlan.basename] under
     * [SearchPlan.searchRoot] over the warm [session] (issue #748). Returns the
     * absolute paths of regular-file matches, capped at [MAX_LOCATE_CANDIDATES].
     * Quotes both the root and the name single-quote-safely so a path with
     * spaces/quotes can't break out of the command.
     */
    private suspend fun searchByBasename(
        session: SshSession,
        plan: RemotePathResolver.SearchPlan,
    ): List<String> {
        val root = shellQuote(plan.searchRoot)
        val name = shellQuote(plan.basename)
        // -type f: regular files only. head caps the scan output cheaply even on
        // a huge tree; the +1 lets the UI know the cap was hit (one extra row is
        // harmless — chooseSearchMatch/UI just see the capped list).
        val command = "find $root -type f -name $name 2>/dev/null | head -n ${MAX_LOCATE_CANDIDATES + 1}"
        val result = session.exec(command)
        if (result.exitCode != 0 && result.stdout.isBlank()) return emptyList()
        return result.stdout
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("/") }
            .distinct()
            .take(MAX_LOCATE_CANDIDATES)
            .toList()
    }

    /**
     * Cache only the states that re-paint correctly off in-memory data — i.e.
     * decoded text. Image/PDF/audio/binary depend on the on-disk cache file
     * (which may have been swept), and the can't-reach / not-found / too-large
     * failures should always be re-tried, so they are not cached.
     */
    private fun cacheIfReusable(request: Request, state: FileViewerUiState) {
        if (state is FileViewerUiState.TextContent) {
            contentCache[ContentCacheKey(request.workspaceScope(), state.displayPath)] = state
        }
    }

    /**
     * Add/promote a tab after a successful resolved fetch. Failures do not
     * create a new tab, but a previously persisted tab that later fails is
     * kept (the user still intended it to be open).
     */
    private fun considerWorkspaceUpsert(request: Request, fetched: FileViewerUiState) {
        if (!workspaceAvailable) return
        if (lastRequest != request) return
        val path = fetched.resolvedDisplayPath() ?: return
        val qualifies = when (fetched) {
            is FileViewerUiState.TextContent,
            is FileViewerUiState.Image,
            is FileViewerUiState.Pdf,
            is FileViewerUiState.Audio,
            -> true
            is FileViewerUiState.CannotPreview -> fetched.cacheFile != null
            else -> false
        }
        val existing = _workspace.value.orderedTabs.any { it.absolutePath == path }
        if (!qualifies && !existing) return
        val next = if (qualifies) {
            FileWorkspaceReducer.open(_workspace.value, path, nowMillis())
        } else {
            FileWorkspaceReducer.activate(_workspace.value, path, nowMillis())
        }
        persistWorkspace(next)
    }

    private fun persistWorkspace(next: FileWorkspace) {
        _workspace.value = next
        val request = lastRequest ?: return
        if (!workspaceAvailable) return
        val generation = ++workspaceWriteGeneration
        _workspaceWriteState.value = FileWorkspaceWriteState.Pending(next)
        val previous = workspaceWriteTail
        workspaceWriteTail = workspaceWriteScope.launch {
            try {
                // Host writes are atomic but last-arrival-wins. Serialize them
                // so a slow A snapshot cannot arrive after a fast B snapshot
                // and silently roll the durable workspace backward.
                previous?.join()
                val acknowledged = LeaseSessionExec.withSession(
                    leaseManager = sshLeaseManager,
                    target = request.toLeaseTarget(),
                ) { session ->
                    workspaceSource.upsertWorkspace(session, next)
                }.getOrElse { error -> throw error }
                check(acknowledged) { "Host rejected the open-file workspace write" }
                if (generation == workspaceWriteGeneration) {
                    _workspaceWriteState.value = FileWorkspaceWriteState.Saved(next)
                }
            } catch (error: CancellationException) {
                if (generation == workspaceWriteGeneration) {
                    _workspaceWriteState.value = FileWorkspaceWriteState.Failed(
                        workspace = next,
                        message = "Open-file workspace save was cancelled",
                    )
                }
                throw error
            } catch (error: Throwable) {
                if (generation == workspaceWriteGeneration) {
                    val message = error.message?.takeIf { it.isNotBlank() }
                        ?: "unknown host error"
                    _workspaceWriteState.value = FileWorkspaceWriteState.Failed(
                        workspace = next,
                        message = "Couldn't save open-file workspace: $message",
                    )
                }
                Log.w(WORKSPACE_LOG_TAG, "Open-file workspace write failed", error)
            }
        }
    }

    /** Resolved identity of the file actually shown, even for relative opens. */
    private fun currentDisplayPath(): String? {
        _state.value.resolvedDisplayPath()?.let { return it }
        val request = lastRequest ?: return null
        val raw = request.path ?: return null
        return FileWorkspaceReducer.normalizeAbsolutePath(
            RemotePathResolver.resolve(
                input = raw,
                cwd = request.cwd,
                remoteHome = conventionalRemoteHome(request.username),
            ),
        )
    }

    private fun FileViewerUiState.resolvedDisplayPath(): String? {
        val raw = when (this) {
            is FileViewerUiState.Loading -> displayPath
            is FileViewerUiState.Image -> displayPath
            is FileViewerUiState.TextContent -> displayPath
            is FileViewerUiState.Pdf -> displayPath
            is FileViewerUiState.Audio -> displayPath
            is FileViewerUiState.CannotPreview -> displayPath
            is FileViewerUiState.EmptyWorkspace,
            is FileViewerUiState.WorkspaceUnavailable,
            -> null
        }
        return FileWorkspaceReducer.normalizeAbsolutePath(raw)
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

    private suspend fun remoteHomeDirectory(session: SshSession): String? {
        val result = try {
            session.exec("printf '%s\\n' \"\$HOME\"")
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
        return result
            ?.takeIf { it.exitCode == 0 }
            ?.stdout
            ?.lineSequence()
            ?.firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.let { it.trimEnd('/').ifEmpty { "/" } }
            ?.takeIf { it.startsWith("/") }
    }

    override fun onCleared() {
        super.onCleared()
        loadJob?.cancel()
        contentCache.clear()
    }

    /**
     * Host credentials + the remote path to open. Mirrors the credential
     * shape used by sibling per-host screens; the path is resolved against
     * [cwd] before the SFTP read.
     *
     * [hostId] is the persisted host row id; it feeds the lease key
     * (`"$hostId:$keyPath"`) so the borrow is keyed BYTE-IDENTICALLY to the
     * session / folder / tmux / explorer screens and the pool hands back the
     * literally-same warm transport (issue #697).
     */
    data class Request(
        val hostId: Long,
        val hostname: String,
        val port: Int,
        val username: String,
        val keyPath: String,
        val passphrase: CharArray?,
        val path: String?,
        val cwd: String?,
    ) {
        internal fun toLeaseTarget(): LeaseSessionTarget =
            LeaseSessionTarget(
                hostId = hostId,
                hostname = hostname,
                port = port,
                username = username,
                keyPath = keyPath,
                passphrase = passphrase,
            )

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Request) return false
            if (hostId != other.hostId) return false
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
            var result = hostId.hashCode()
            result = 31 * result + hostname.hashCode()
            result = 31 * result + port
            result = 31 * result + username.hashCode()
            result = 31 * result + keyPath.hashCode()
            result = 31 * result + path.hashCode()
            result = 31 * result + (cwd?.hashCode() ?: 0)
            result = 31 * result + (passphrase?.contentHashCode() ?: 0)
            return result
        }
    }

    /** ISO-8601 UTC timestamp (second precision) for the review's `submitted_at`. */
    private fun isoUtcNow(): String =
        Instant.now().truncatedTo(ChronoUnit.SECONDS).toString()

    companion object {

        /** Logcat tag for the review-YAML submit (slice 2 writes it over SSH). */
        internal const val REVIEW_LOG_TAG = "FileViewerReview"

        /** Inbox subdirectory (under `~/inbox/pocketshell/`) reviews land in. */
        internal const val REVIEWS_SUBDIR: String = "inbox/pocketshell/reviews"

        /** Logcat tag for the annotation-PNG submit (issue #764). */
        internal const val ANNOTATION_LOG_TAG = "FileViewerAnnotation"

        /** Inbox subdirectory annotated PNGs + their YAML sidecars land in. */
        internal const val ANNOTATIONS_SUBDIR: String = "inbox/pocketshell/annotations"

        /**
         * Shared filename stem for a submitted annotation: the PNG and its YAML
         * sidecar use `<sanitised-basename>-<ts>` so the pair is co-located and
         * obviously related. Reuses [FilenameSanitiser] + [ShareUploader.formatTimestamp]
         * (mirroring [reviewFileName]); the source extension is dropped — the
         * exported markup is always a PNG.
         */
        internal fun annotationFileStem(displayPath: String): String {
            val basename = displayPath.substringAfterLast('/').ifBlank { "image" }
            // Drop the source extension (".png"/".jpg") so the stem is clean; the
            // export forces .png / .yaml regardless of the source type.
            val withoutExt = basename.substringBeforeLast('.', basename)
            val sanitised = FilenameSanitiser.sanitise(withoutExt)
            val stem = sanitised.render().ifBlank { "image" }
            val timestamp = ShareUploader.formatTimestamp(System.currentTimeMillis())
            return "$stem-$timestamp"
        }

        /**
         * Remote filename for a submitted review: `<sanitised-basename>-<ts>.yaml`.
         * Reuses [FilenameSanitiser] (shell-safe, single-quote-safe `[A-Za-z0-9._-]`)
         * + [ShareUploader.formatTimestamp] so it mirrors the screenshot-inbox
         * naming; the `.yaml` extension is forced regardless of the source file's
         * extension. The basename is derived from the reviewed file's path.
         */
        internal fun reviewFileName(displayPath: String): String {
            val basename = displayPath.substringAfterLast('/').ifBlank { "review" }
            // Keep the source file's name as the stem; the review export is YAML
            // regardless of the reviewed file's type, so force the `.yaml` ext.
            val sanitised = FilenameSanitiser.sanitise(basename)
            val stem = sanitised.render().ifBlank { "review" }
            val timestamp = ShareUploader.formatTimestamp(System.currentTimeMillis())
            return "$stem-$timestamp.yaml"
        }

        /**
         * Map a submit failure to a short, calm, one-line message — the same
         * shape [ShareUploader] uses for shares (never let a JVM stack dump reach
         * the toast). Narrow keyword match on the common SSH failures, with a
         * trimmed first-line fallback.
         */
        internal fun reviewErrorMessage(error: Throwable): String {
            val raw = (error.message ?: error.javaClass.simpleName).trim()
            val lower = raw.lowercase(Locale.ROOT)
            return when {
                lower.contains("permission denied") -> raw.replaceFirstChar { it.uppercase() }
                lower.contains("connection refused") -> "Connection refused"
                lower.contains("connection reset") || lower.contains("connection closed") ->
                    "Connection lost while sending comments"
                lower.contains("unknownhost") || lower.contains("unknown host") ->
                    "Cannot resolve host"
                lower.contains("auth") -> "Authentication failed"
                lower.contains("timed out") || lower.contains("timeout") ->
                    "Connection timed out"
                else -> raw.lineSequence().firstOrNull()?.take(160) ?: "Couldn't send comments"
            }
        }

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
         * Max number of already-rendered text states kept in the re-open LRU
         * (issue #697). Small on purpose — it only serves an instant first
         * paint on re-open; the live fetch reconciles right after, so a bigger
         * cache buys little and the cap keeps the held decoded strings bounded.
         */
        internal const val CONTENT_CACHE_CAP = 8

        /**
         * Issue #1715 — text/workspace identity is the remote profile plus
         * resolved absolute path, not only a Room id or raw request.
         */
        internal data class WorkspaceScope(
            val hostId: Long,
            val hostname: String,
            val port: Int,
            val username: String,
            val keyPath: String,
        )

        internal data class ContentCacheKey(
            val scope: WorkspaceScope,
            val resolvedPath: String,
        )

        internal fun conventionalRemoteHome(username: String): String? {
            val user = username.trim()
            return when {
                user.isEmpty() -> null
                user == "root" -> "/root"
                else -> "/home/$user"
            }
        }

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

        internal fun fileNotFoundMessage(resolvedPath: String): String =
            "No such file on the server: $resolvedPath"

        /** Logcat tag for the file viewer (issue #748 basename-search fallback). */
        internal const val LOG_TAG = "FileViewer"

        /** Logcat tag for host-durable open-file workspace writes (#1715). */
        internal const val WORKSPACE_LOG_TAG = "FileViewerWorkspace"

        /**
         * Cap on how many same-named files the basename-search fallback offers
         * (issue #748). A handful is plenty to disambiguate; more than this and
         * the `find` is matching something too generic to be useful as a list.
         */
        internal const val MAX_LOCATE_CANDIDATES = 8

        /**
         * Message for the can't-preview panel when the relative path was missing
         * under the session cwd but the basename search found same-named files
         * deeper in the tree (issue #748). The panel renders the candidate rows
         * below it.
         */
        internal fun locateCandidatesMessage(basename: String, count: Int): String =
            if (count == 1) {
                "Couldn't find $basename where the agent referenced it, but it " +
                    "exists elsewhere in this project. Open it instead?"
            } else {
                "Couldn't find $basename where the agent referenced it. " +
                    "Found $count files with that name in this project — pick one:"
            }

        /** Single-quote-safe shell quoting for `find` arguments (issue #748). */
        internal fun shellQuote(value: String): String =
            "'" + value.replace("'", "'\"'\"'") + "'"
    }
}
