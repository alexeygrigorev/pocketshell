package com.pocketshell.next.composer

import android.content.Context
import android.content.SharedPreferences
import com.pocketshell.next.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * A staged attachment as it survives a process death: the remote path it was
 * uploaded to plus the name the tile shows.
 *
 * The local preview `Uri` is deliberately absent. It is a permission grant this
 * process holds for this session, so persisting it would store a handle that is
 * invalid by the time it is read back; the tile renders perfectly well from the
 * remote path and the name, which is what the host has anyway.
 */
data class StagedAttachment(
    val remotePath: String,
    val displayName: String,
    val mimeType: String? = null,
)

/** A composer draft: the text plus whatever was staged alongside it. */
data class ComposerDraft(
    val text: String = "",
    val attachments: List<StagedAttachment> = emptyList(),
) {
    val isEmpty: Boolean get() = text.isEmpty() && attachments.isEmpty()
}

/**
 * Durable, per-session composer drafts (rewrite task P-1).
 *
 * ## Why per session, and why durable
 *
 * A draft that only lives in the composable dies on a session switch, and the
 * one thing the send path promises when a send does not land is "your text is
 * still here". Keying by `"<hostId>/<sessionName>"` means switching A → B → A
 * finds A's draft again rather than an empty box, and writing it to disk means
 * the promise survives the process being killed in the background.
 *
 * ## Why SharedPreferences and not Room
 *
 * The payload is one short string plus a handful of paths per ACTIVE session,
 * there is nothing relational to ask of it, and it is rewritten on a keystroke
 * debounce. The sent-message log went to Room because it is a growing list that
 * needs ordering and trimming; a draft is a single overwrite-in-place slot, and
 * giving it a table would mean a schema migration for state that has none.
 *
 * ## Ports of the old store that did NOT come across
 *
 * The old `ComposerDraftStore` also carried `promoteIdentity` (re-keying a
 * draft after the outbound queue proved a session's real tmux identity) and a
 * write-generation coalescer in a separate `ComposerDraftPersistence` class.
 * Both existed to keep a draft consistent with queue state that no longer
 * exists. Here every write comes from one ViewModel on one dispatcher, so
 * ordering is already total; the in-memory [mirror] is what makes a read
 * immediately after a write authoritative without a generation counter.
 */
@Singleton
class ComposerDraftStore @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val dispatcher: CoroutineDispatcher,
) {

    /**
     * Read-through cache of everything written this process.
     *
     * Load and save both go through [dispatcher], so without this a save
     * followed immediately by a load would race the coroutine that performs the
     * write. It also means the common case (the session you are looking at)
     * never waits on disk at all.
     */
    private val mirror = ConcurrentHashMap<String, ComposerDraft>()

    /**
     * Opened lazily and only ever off the main thread, because the first
     * `getSharedPreferences` on a file does the read + parse synchronously and
     * the composer is present on every session screen.
     */
    @Volatile
    private var prefs: SharedPreferences? = null

    suspend fun load(sessionKey: String): ComposerDraft {
        if (sessionKey.isBlank()) return ComposerDraft()
        mirror[sessionKey]?.let { return it }
        return withContext(dispatcher) {
            val store = open()
            val draft = ComposerDraft(
                text = store.getString(sessionKey, null).orEmpty(),
                attachments = decodeAttachments(store.getString(attachmentKey(sessionKey), null)),
            )
            mirror.putIfAbsent(sessionKey, draft)
            mirror.getValue(sessionKey)
        }
    }

    suspend fun save(sessionKey: String, draft: ComposerDraft) {
        if (sessionKey.isBlank()) return
        mirror[sessionKey] = draft
        withContext(dispatcher) {
            open().edit().apply {
                if (draft.text.isEmpty()) remove(sessionKey) else putString(sessionKey, draft.text)
                if (draft.attachments.isEmpty()) {
                    remove(attachmentKey(sessionKey))
                } else {
                    putString(attachmentKey(sessionKey), encodeAttachments(draft.attachments))
                }
            }.apply()
        }
    }

    /** Drops the stored draft — what a delivered send (or an explicit discard) does. */
    suspend fun clear(sessionKey: String) {
        if (sessionKey.isBlank()) return
        mirror[sessionKey] = ComposerDraft()
        withContext(dispatcher) {
            open().edit().remove(sessionKey).remove(attachmentKey(sessionKey)).apply()
        }
    }

    private fun open(): SharedPreferences =
        prefs ?: synchronized(this) {
            prefs ?: context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).also {
                prefs = it
            }
        }

    private companion object {
        const val PREFS_NAME = "composer_drafts"
    }
}

/**
 * The attachment slot's key, in its own `@att/` namespace so it can never
 * collide with the text slot keyed by the bare session key.
 */
internal fun attachmentKey(sessionKey: String): String = "@att/$sessionKey"

/**
 * Attachments as newline-separated `path\tname\tmime` rows.
 *
 * Tabs, newlines and backslashes inside a field are escaped, so a file whose
 * name contains one round-trips instead of shifting every following field by
 * one — a shape that is trivially wrong if unescaped and trivially right if it
 * is, which is why the encoding is spelled out rather than assumed safe.
 */
internal fun encodeAttachments(attachments: List<StagedAttachment>): String =
    attachments.joinToString(separator = "\n") { attachment ->
        listOf(attachment.remotePath, attachment.displayName, attachment.mimeType.orEmpty())
            .joinToString(separator = "\t") { field ->
                field.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
            }
    }

/** Inverse of [encodeAttachments]. A malformed row is dropped rather than crashing the composer. */
internal fun decodeAttachments(raw: String?): List<StagedAttachment> {
    if (raw.isNullOrEmpty()) return emptyList()
    return raw.split('\n').mapNotNull { row ->
        if (row.isEmpty()) return@mapNotNull null
        val fields = row.split('\t').map(::unescapeField)
        val remotePath = fields.getOrNull(0).orEmpty()
        if (remotePath.isEmpty()) return@mapNotNull null
        StagedAttachment(
            remotePath = remotePath,
            displayName = fields.getOrNull(1).orEmpty().ifEmpty {
                ComposerText.attachmentDisplayName(remotePath)
            },
            mimeType = fields.getOrNull(2).orEmpty().ifEmpty { null },
        )
    }
}

private fun unescapeField(field: String): String {
    if ('\\' !in field) return field
    val out = StringBuilder(field.length)
    var index = 0
    while (index < field.length) {
        val char = field[index]
        if (char == '\\' && index + 1 < field.length) {
            when (field[index + 1]) {
                't' -> { out.append('\t'); index += 2 }
                'n' -> { out.append('\n'); index += 2 }
                '\\' -> { out.append('\\'); index += 2 }
                else -> { out.append(char); index += 1 }
            }
        } else {
            out.append(char)
            index += 1
        }
    }
    return out.toString()
}
