package com.pocketshell.next.composer

/**
 * Where the composer's send goes (rewrite task P-1).
 *
 * Deliberately two members. The composer needs to know whether the session is
 * attached AT THE MOMENT OF THE TAP and it needs somewhere to put bytes;
 * anything more would be the composer re-deriving session state that
 * `SessionViewModel` already owns, which is how the old client ended up with
 * two disagreeing views of one connection.
 *
 * [isLive] is a property, not a constructor value, so an implementation reads
 * the session's CURRENT state rather than one captured when the screen last
 * recomposed — a stale snapshot here would mean sending into a dead pane and
 * clearing the draft for it.
 */
interface SessionSink {

    /** True only when the session is attached and bytes can actually leave. */
    val isLive: Boolean

    /** Writes [bytes] to the session. Must not throw. */
    fun sendBytes(bytes: ByteArray)
}

/**
 * What the composer is telling the user right now.
 *
 * [Undelivered] is the entire delivery story: the send did not go out, the
 * draft is still here, and the user decides what to do about it. There is no
 * retry timer, no queue depth and no "will send when reconnected", because
 * there is no queue — sending ONE message reliably is the job, and a message
 * that did not leave simply did not leave.
 */
sealed interface ComposerNotice {

    data object Undelivered : ComposerNotice

    /** Something failed (an upload, a connection) — [message] says what. */
    data class Problem(val message: String) : ComposerNotice

    /** Something worked and is worth a word (an attachment landed). */
    data class Info(val message: String) : ComposerNotice
}

/** File-level attachment upload progress: "2 of 3 · screenshot.png". */
data class StagingProgress(val index: Int, val count: Int, val name: String)

/** One entry of the per-session sent-message log. */
data class SentMessage(
    val id: Long,
    val body: String,
    val sentAtMs: Long,
    val delivered: Boolean,
) {
    /** The single line the history list shows. Tapping still restores [body] whole. */
    val label: String get() = ComposerText.historyLabel(body)
}

/** Everything the composer surface renders. */
data class ComposerUiState(
    val draft: String = "",
    val attachments: List<StagedAttachment> = emptyList(),
    val recording: RecordingState = RecordingState.Idle,
    val staging: StagingProgress? = null,
    val notice: ComposerNotice? = null,
    /** The draft is shown rendered as Markdown instead of editable. */
    val previewing: Boolean = false,
    val historyOpen: Boolean = false,
    val history: List<SentMessage> = emptyList(),
    /** False when no speech recognizer is wired; the mic renders disabled. */
    val micAvailable: Boolean = false,
) {
    /**
     * A send is possible. An attachment with no text counts: "here is the
     * screenshot" is a complete message when the paths are the content.
     */
    val canSend: Boolean get() = draft.isNotBlank() || attachments.isNotEmpty()

    /** An upload is in flight; the send and the picker are held off until it lands. */
    val busy: Boolean get() = staging != null

    /** The draft survived a send that did not leave the device. */
    val undelivered: Boolean get() = notice is ComposerNotice.Undelivered
}
