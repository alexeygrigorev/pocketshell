package com.pocketshell.next.composer

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.pocketshell.core.storage.dao.SentMessageDao
import com.pocketshell.core.storage.entity.SentMessageEntity
import com.pocketshell.core.transport.ConnectResult
import com.pocketshell.core.transport.HostConnection
import com.pocketshell.next.connect.ConnectionsRegistry
import com.pocketshell.next.voice.PendingTranscriptionDelivery
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The composer (rewrite task P-1, journey J07) — everything between "the user
 * has something to say" and "the bytes left".
 *
 * ## The send contract, in full
 *
 * Build the text, ask the session whether it is attached, and act on the
 * answer:
 *
 *  - attached → write `body + "\r"` through [SessionSink], clear the draft;
 *  - anything else → KEEP the draft and show a "not delivered" chip.
 *
 * That is the whole delivery story. There is no queue, no retry loop, no
 * offline delivery, no acknowledgement tracking and no send-in-flight state
 * machine — the class this replaces had all of them across 3,585 lines and 288
 * outbound/queue references, and the maintainer's verdict was that the job is
 * sending ONE message reliably, not batching. A send that does not leave leaves
 * the text exactly where the user can see it, and they decide what happens
 * next.
 *
 * The one durable thing a send writes is a HISTORY row, delivered or not (see
 * [SentMessageEntity]). That is a read-only log so nobody retypes a paragraph,
 * not a delivery mechanism: nothing reads it back except a list the user taps.
 *
 * ## Why this holds no session state
 *
 * `SessionViewModel` owns whether the session is live; this class asks
 * [SessionSink] at the moment of the tap and never caches the answer. Two
 * objects with their own opinion of one connection is the exact shape the
 * rewrite exists to delete.
 */
@HiltViewModel
class ComposerViewModel @Inject constructor(
    private val registry: ConnectionsRegistry,
    private val drafts: ComposerDraftStore,
    private val history: SentMessageDao,
    private val stager: ComposerAttachmentStager,
    private val queuedDictations: PendingTranscriptionDelivery,
    speech: SpeechRecognitionProvider,
) : ViewModel() {

    private val _state = MutableStateFlow(ComposerUiState())
    val state: StateFlow<ComposerUiState> = _state.asStateFlow()

    private val dictation = AndroidSpeechRecognitionDelegate(
        provider = speech,
        callbacks = object : AndroidSpeechRecognitionDelegate.Callbacks {
            override fun currentDraft(): String = _state.value.draft
            override fun onDraft(text: String) = onDraftChange(text)
            override fun onState(state: RecordingState) = _state.update { it.copy(recording = state) }
            override fun onError(message: String) = notify(ComposerNotice.Problem(message))
        },
    )

    private var hostId: Long = 0
    private var sessionKey: String? = null
    private var sink: SessionSink? = null
    private var homeDir: String? = null

    private var persistJob: Job? = null
    private var stageJob: Job? = null
    private var historyJob: Job? = null
    private var deliveryJob: Job? = null

    /**
     * Points the composer at one session and its send path.
     *
     * Idempotent for the same session so the screen can call it from a
     * `LaunchedEffect` — the sink is refreshed every time (a recomposition
     * builds a new one over the same ViewModel) but the draft is loaded and the
     * history subscribed only once, because re-loading would overwrite whatever
     * the user has typed since.
     */
    fun bind(hostId: Long, sessionName: String, sink: SessionSink) {
        this.sink = sink
        _state.update { it.copy(micAvailable = dictation.isAvailable()) }
        val key = ComposerText.sessionKey(hostId, sessionName)
        if (sessionKey == key) return
        this.hostId = hostId
        this.sessionKey = key
        this.homeDir = null
        viewModelScope.launch {
            val stored = drafts.load(key)
            // Only adopt the stored draft if nothing has been typed in the
            // meantime: the load is asynchronous and the user can beat it.
            _state.update { current ->
                if (current.draft.isNotEmpty() || current.attachments.isNotEmpty()) {
                    current
                } else {
                    current.copy(draft = stored.text, attachments = stored.attachments)
                }
            }
        }
        historyJob?.cancel()
        historyJob = viewModelScope.launch {
            history.recent(key, HISTORY_LIMIT).collectLatest { rows ->
                _state.update { it.copy(history = rows.map(::toSentMessage)) }
            }
        }
    }

    // ---------------------------------------------------------------- editing

    fun onDraftChange(text: String) {
        _state.update {
            // Typing is the acknowledgement of an undelivered chip: the user has
            // seen it and moved on. A failure the user cannot dismiss by acting
            // on it is a failure that stays on screen forever.
            it.copy(draft = text, notice = it.notice.takeUnless { n -> n is ComposerNotice.Undelivered })
        }
        persist()
    }

    /** Shows the draft rendered as Markdown, and back. */
    fun togglePreview() = _state.update { it.copy(previewing = !it.previewing) }

    /** Throws away the draft and its staged attachments. */
    fun discard() {
        _state.update { it.copy(draft = "", attachments = emptyList(), notice = null, previewing = false) }
        persistNow()
    }

    fun dismissNotice() = _state.update { it.copy(notice = null) }

    // ---------------------------------------------------------------- sending

    /**
     * Sends the composed message.
     *
     * Ignored while an upload is in flight: an attachment that is halfway to
     * the host has no remote path yet, so sending now would send a message
     * referencing a file that is not there.
     */
    fun send() {
        val current = _state.value
        if (!current.canSend || current.busy) return
        val target = sink ?: return

        val body = ComposerText.compose(current.draft.trim(), current.attachments.map { it.remotePath })
        val delivered = target.isLive
        if (delivered) target.sendBytes(ComposerText.wireBytes(body))
        record(body, delivered)

        if (delivered) {
            _state.update {
                it.copy(draft = "", attachments = emptyList(), notice = null, previewing = false)
            }
            persistNow()
        } else {
            _state.update { it.copy(notice = ComposerNotice.Undelivered) }
        }
    }

    // ---------------------------------------------------------------- history

    fun toggleHistory() = _state.update { it.copy(historyOpen = !it.historyOpen) }

    /**
     * Puts a previous message back in the draft.
     *
     * Replaces rather than appends, and closes the list: the frustration this
     * solves is "I have to retype what I already sent", so the expected result
     * of the tap is the composer holding that message ready to send again.
     * Attachment references travel with the text, because they are text.
     */
    fun useHistoryEntry(message: SentMessage) {
        _state.update { it.copy(draft = message.body, historyOpen = false, notice = null) }
        persist()
    }

    // ------------------------------------------------------------ attachments

    /**
     * Uploads [picks] to the host and stages them.
     *
     * The remote paths are injected into the message at send time
     * ([ComposerText.compose]), which is the contract the old flow established
     * and host-side readers already parse.
     */
    fun attach(picks: List<Uri>) {
        val key = sessionKey ?: return
        if (picks.isEmpty() || _state.value.busy) return
        stageJob?.cancel()
        stageJob = viewModelScope.launch {
            val connection = connection() ?: return@launch
            val home = home(connection) ?: return@launch
            val result = runCatching {
                stager.stage(connection.sftp(), home, key, picks) { index, count, name ->
                    _state.update { it.copy(staging = StagingProgress(index, count, name)) }
                }
            }.getOrElse { failure ->
                AttachmentStageResult(emptyList(), "Attachment upload failed: ${describe(failure)}")
            }
            _state.update { current ->
                current.copy(
                    attachments = merge(current.attachments, result.uploaded),
                    staging = null,
                    notice = result.failure?.let(ComposerNotice::Problem)
                        ?: result.uploaded.takeIf { it.isNotEmpty() }?.let {
                            ComposerNotice.Info(attachedMessage(it.size))
                        },
                )
            }
            persistNow()
        }
    }

    /** Removes one staged tile. The uploaded file stays on the host; retention prunes it. */
    fun removeAttachment(remotePath: String) {
        _state.update { it.copy(attachments = it.attachments.filterNot { a -> a.remotePath == remotePath }) }
        persistNow()
    }

    // --------------------------------------------------------------- dictation

    /** Mic tap: start dictating, or stop and transcribe. */
    fun onMicTap() {
        if (dictation.isRecording) dictation.stop() else dictation.start()
    }

    /** Discard the recording and restore the draft as it was before the mic opened. */
    fun cancelRecording() = dictation.cancel()

    /**
     * The composer came back to the foreground: collect anything dictated while
     * the device was offline.
     *
     * A recording made with no signal is parked as audio by the voice stack
     * (task P-2's [PendingTranscriptionDelivery]) rather than transcribed. This
     * is the moment it can be — the network is presumably back and, more to the
     * point, the draft the transcript belongs in is on screen again. Each
     * recovered transcript is appended exactly as a live dictation would have
     * appended it, and the user sees text appear in their composer rather than
     * a notification about a queue.
     *
     * D21: no scheduler, no callback, no background work. If the app is never
     * reopened the recordings simply stay on disk.
     */
    fun onForegroundResume() {
        if (deliveryJob?.isActive == true) return
        deliveryJob = viewModelScope.launch {
            val recovered = runCatching { queuedDictations.deliverQueued() }.getOrDefault(emptyList())
            if (recovered.isEmpty()) return@launch
            _state.update { current ->
                val merged = recovered.fold(current.draft, ComposerText::appendDictated)
                current.copy(draft = merged, notice = ComposerNotice.Info(deliveredMessage(recovered.size)))
            }
            persistNow()
        }
    }

    override fun onCleared() {
        dictation.release()
        super.onCleared()
    }

    // --------------------------------------------------------------- internals

    private suspend fun connection(): HostConnection? =
        when (val result = registry.getOrConnect(hostId)) {
            is ConnectResult.Connected -> result.connection
            is ConnectResult.NeedsTrust -> {
                fail("This host's key still needs to be confirmed. Open it from the host list.")
                null
            }

            is ConnectResult.Failed -> {
                fail(result.message)
                null
            }
        }

    /**
     * The account's home directory, cached per session.
     *
     * SFTP has no notion of `~`, so something has to ask; `pwd` over the same
     * connection is what the file explorer does and it is one round trip per
     * session rather than one per attachment.
     */
    private suspend fun home(connection: HostConnection): String? {
        homeDir?.let { return it }
        val resolved = runCatching { connection.exec("pwd") }.getOrNull()
            ?.takeIf { it.exitCode == 0 && !it.timedOut }
            ?.stdout
            ?.lineSequence()
            ?.map { it.trim() }
            ?.lastOrNull { it.startsWith("/") }
        if (resolved.isNullOrBlank()) {
            fail("Could not find your home directory on this host.")
            return null
        }
        homeDir = resolved
        return resolved
    }

    private fun record(body: String, delivered: Boolean) {
        val key = sessionKey ?: return
        viewModelScope.launch {
            runCatching {
                history.insert(
                    SentMessageEntity(
                        sessionKey = key,
                        body = body,
                        sentAtMs = System.currentTimeMillis(),
                        delivered = delivered,
                    ),
                )
                history.trim(key, HISTORY_LIMIT)
            }
        }
    }

    /**
     * Debounced draft persistence.
     *
     * Every keystroke changes the draft, and every write opens a transaction on
     * a preferences file. A short quiet period collapses a typed sentence into
     * one write while still landing long before the user can background the app.
     */
    private fun persist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(PERSIST_DEBOUNCE_MS)
            writeDraft()
        }
    }

    /** Persistence for the moments that must not wait: a send, a discard, a stage. */
    private fun persistNow() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch { writeDraft() }
    }

    private suspend fun writeDraft() {
        val key = sessionKey ?: return
        val current = _state.value
        val draft = ComposerDraft(current.draft, current.attachments)
        if (draft.isEmpty) drafts.clear(key) else drafts.save(key, draft)
    }

    private fun fail(message: String) {
        _state.update { it.copy(staging = null, notice = ComposerNotice.Problem(message)) }
    }

    private fun notify(notice: ComposerNotice) = _state.update { it.copy(notice = notice) }

    private companion object {
        /** Rows kept per session. Deep enough to scroll back a working day. */
        const val HISTORY_LIMIT = 50
        const val PERSIST_DEBOUNCE_MS = 400L

        fun toSentMessage(row: SentMessageEntity) = SentMessage(
            id = row.id,
            body = row.body,
            sentAtMs = row.sentAtMs,
            delivered = row.delivered,
        )

        /** Appends new tiles, skipping any remote path already staged. */
        fun merge(current: List<StagedAttachment>, added: List<StagedAttachment>): List<StagedAttachment> {
            val known = current.mapTo(mutableSetOf()) { it.remotePath }
            return current + added.filter { known.add(it.remotePath) }
        }

        fun attachedMessage(count: Int): String =
            if (count == 1) "Attached 1 file." else "Attached $count files."

        /** Says what just appeared in the draft, so text arriving on its own is not a mystery. */
        fun deliveredMessage(count: Int): String = if (count == 1) {
            "Added a dictation recorded while you were offline."
        } else {
            "Added $count dictations recorded while you were offline."
        }

        fun describe(failure: Throwable): String =
            failure.message ?: failure::class.simpleName ?: "unknown error"
    }
}
