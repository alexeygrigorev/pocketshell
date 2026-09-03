package com.pocketshell.next.files

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.SftpChannel
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.nav.Destination
import dagger.hilt.android.lifecycle.HiltViewModel
import java.io.IOException
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * What the viewer got back from the host, in the shape its renderer needs.
 *
 * [Image] and [Binary] are ordinary classes rather than `data` classes on
 * purpose: a `data class` over a `ByteArray` gets an `equals` that compares
 * array *identity* while claiming to be structural, which is a trap every
 * future reader has to re-derive. Identity comparison is what we actually want
 * here (a fresh load is a fresh emission), so the class says so honestly.
 */
sealed interface ViewerContent {

    /** Nothing loaded yet. */
    data object Empty : ViewerContent

    /** UTF-8 text, ready to render or edit. */
    data class Text(val text: String) : ViewerContent

    /** Raw image bytes; the renderer decodes them under a pixel bound. */
    class Image(val bytes: ByteArray) : ViewerContent

    /** Undecodable bytes; the renderer shows a bounded hex dump. */
    class Binary(val bytes: ByteArray) : ViewerContent
}

/** Everything the file viewer renders. */
data class ViewerUiState(
    val hostId: Long = 0,
    val path: String = "",
    val loading: Boolean = false,
    /** A read has succeeded, so [content] is a real answer. */
    val loaded: Boolean = false,
    val kind: FileKind = FileKind.TEXT,
    val content: ViewerContent = ViewerContent.Empty,
    /** True when the file's name says Markdown, so the render toggle is offered. */
    val markdownCapable: Boolean = false,
    /** Whether Markdown is currently shown formatted rather than as source. */
    val renderMarkdown: Boolean = false,
    val editing: Boolean = false,
    /** The editor buffer. Meaningless unless [editing]. */
    val draft: String = "",
    val saving: Boolean = false,
    /** Set for one banner after a successful save. */
    val savedMessage: String? = null,
    val failure: String? = null,
) {
    val name: String get() = if (path.isBlank()) "" else RemotePath.nameOf(path)

    /** Text is editable; an image or an undecodable blob is not. */
    val editable: Boolean get() = loaded && kind == FileKind.TEXT

    /** True when the buffer differs from what was read (or last written). */
    val dirty: Boolean
        get() = editing && draft != (content as? ViewerContent.Text)?.text
}

/**
 * The remote file viewer/editor for one file (rewrite task P-3b, journey J10).
 *
 * ## Why this class is small
 *
 * The old client's `FileViewerViewModel` was 1,569 lines because it owned an
 * open-tab strip, a per-file preference store, a workspace/reconcile model, a
 * download cache keyed by a hashed remote path, an audio player, a PDF pager,
 * a review/annotation submission pipeline, and two `internal var` dispatcher
 * fields with forty-line KDocs explaining how tests swapped them. None of that
 * is here: one route argument names one file, the connection comes from
 * [ConnectionsRegistry], and the "dispatcher seam" is gone because every
 * blocking call already runs on the transport's own IO dispatcher inside
 * [SftpChannel].
 *
 * ## Bounded read
 *
 * [SftpChannel.read] takes a byte cap and REFUSES rather than truncating, so a
 * multi-gigabyte log surfaces as "too big to open" instead of an OOM or — worse
 * — a silently clipped file that an edit-and-save would then write back over the
 * original. That last hazard is why the cap is enforced on read and not just on
 * render: truncate-then-save is data loss.
 *
 * ## Editing
 *
 * Only [FileKind.TEXT] is editable, and a save is a whole-file
 * [SftpChannel.write] of the buffer. There is no partial/patch write and no
 * conflict detection — if the file changed on the host since it was read, the
 * save wins. That matches every other editor on a phone and is the honest
 * behaviour for a single-user dev box; a merge UI is not something this screen
 * should grow.
 */
@HiltViewModel
class ViewerViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: ConnectionsRegistry,
) : ViewModel() {

    private val hostId: Long = requireNotNull(
        savedStateHandle.get<Long>(Destination.ARG_HOST_ID),
    ) { "ViewerViewModel needs a ${Destination.ARG_HOST_ID} argument" }

    private val path: String = RemotePath.normalize(
        requireNotNull(savedStateHandle.get<String>(Destination.ARG_PATH)) {
            "ViewerViewModel needs a ${Destination.ARG_PATH} argument"
        },
    )

    private val _state = MutableStateFlow(
        ViewerUiState(
            hostId = hostId,
            path = path,
            markdownCapable = FileKindDetector.isMarkdown(path),
            // A Markdown file opens rendered — that is what the user came to
            // read. The toggle switches to source; the editor always shows
            // source regardless.
            renderMarkdown = FileKindDetector.isMarkdown(path),
        ),
    )
    val state: StateFlow<ViewerUiState> = _state.asStateFlow()

    private var job: Job? = null

    /**
     * Reads the file. Safe to call from `ON_START`; a call while a read or save
     * is in flight is ignored, and a call while the user is editing is ignored
     * too — re-reading would silently discard their buffer.
     */
    fun load() {
        if (job?.isActive == true || _state.value.editing) return
        _state.update { it.copy(loading = true, failure = null) }
        job = viewModelScope.launch { read() }
    }

    /** Enters the editor with the current text as the starting buffer. */
    fun startEditing() {
        val text = (_state.value.content as? ViewerContent.Text)?.text ?: return
        _state.update {
            it.copy(editing = true, draft = text, savedMessage = null, failure = null)
        }
    }

    fun onDraftChange(draft: String) {
        if (!_state.value.editing) return
        _state.update { it.copy(draft = draft) }
    }

    /** Leaves the editor, discarding the buffer. */
    fun cancelEditing() {
        if (_state.value.saving) return
        _state.update { it.copy(editing = false, draft = "", failure = null) }
    }

    /**
     * Writes the buffer back to the same path.
     *
     * On success the loaded content BECOMES the buffer, so a subsequent
     * [startEditing] starts from what is now on the host and [ViewerUiState.dirty]
     * reads false — without a re-read round trip.
     */
    fun save() {
        val current = _state.value
        if (!current.editing || current.saving) return
        _state.update { it.copy(saving = true, failure = null, savedMessage = null) }
        job = viewModelScope.launch {
            val draft = _state.value.draft
            runCatching { sftp().write(path, draft.toByteArray()) }.fold(
                onSuccess = {
                    _state.update {
                        it.copy(
                            saving = false,
                            editing = false,
                            draft = "",
                            content = ViewerContent.Text(draft),
                            savedMessage = "Saved ${RemotePath.nameOf(path)}",
                        )
                    }
                },
                onFailure = { error ->
                    // The editor STAYS open on failure: dropping the user back
                    // to the read-only view would throw away the edit they just
                    // failed to save.
                    _state.update {
                        it.copy(saving = false, failure = "Could not save: ${message(error)}")
                    }
                },
            )
        }
    }

    /** Flips a Markdown file between formatted output and raw source. */
    fun toggleMarkdownRendering() {
        if (!_state.value.markdownCapable) return
        _state.update { it.copy(renderMarkdown = !it.renderMarkdown) }
    }

    /** Clears the "Saved" banner once the user has seen it. */
    fun dismissSavedMessage() {
        _state.update { it.copy(savedMessage = null) }
    }

    // ------------------------------------------------------------- internals

    private suspend fun read() {
        runCatching { sftp().read(path, MAX_VIEW_BYTES) }.fold(
            onSuccess = { bytes ->
                val kind = FileKindDetector.detect(path, bytes)
                _state.update {
                    it.copy(
                        loading = false,
                        loaded = true,
                        kind = kind,
                        content = when (kind) {
                            FileKind.TEXT -> ViewerContent.Text(bytes.toString(Charsets.UTF_8))
                            FileKind.IMAGE -> ViewerContent.Image(bytes)
                            FileKind.BINARY -> ViewerContent.Binary(bytes)
                        },
                        failure = null,
                    )
                }
            },
            onFailure = { error ->
                _state.update {
                    it.copy(loading = false, failure = "Could not open $path: ${message(error)}")
                }
            },
        )
    }

    private suspend fun sftp(): SftpChannel = when (val result = registry.getOrConnect(hostId)) {
        is ConnectResult.Connected -> result.connection.sftp()
        is ConnectResult.NeedsTrust -> throw IOException(
            "this host's key still needs to be confirmed from the host list",
        )

        is ConnectResult.Failed -> throw IOException(result.message)
    }

    private companion object {
        /**
         * Read cap for one file.
         *
         * The whole file lands in the JVM heap (as a `String` for text, as
         * bytes for an image), so this is a heap budget: 12 MiB comfortably
         * covers a phone photo and any source file, while a log that big is not
         * something to read in a text field anyway.
         */
        const val MAX_VIEW_BYTES: Long = 12L * 1024 * 1024
    }
}
