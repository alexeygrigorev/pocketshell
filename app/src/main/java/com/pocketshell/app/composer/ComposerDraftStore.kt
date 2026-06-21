package com.pocketshell.app.composer

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #832: a durable, **per-session** composer draft store.
 *
 * ## Why this exists
 *
 * Before #832 the only durable composer draft slot was
 * [com.pocketshell.app.session.LastSessionStore]'s single global
 * `composer_draft` string, written on `onStop` for whatever session
 * happened to be foregrounded and restored only on a cold process-death
 * resume. The live composer draft itself lived purely in
 * [PromptComposerViewModel]'s [androidx.lifecycle.SavedStateHandle] —
 * one slot, owner-stamped per #746 — which survives process death but
 * is **discarded** the moment the user switches to a different session
 * (#746 no-bleed). So the maintainer's dogfood report (#832): a failed
 * attachment-send kept the draft (the "Your draft was kept" banner was
 * truthful for that instant), but the FIRST in-app session switch wiped
 * it, and there was no per-session slot to recover it from. The promise
 * was unrecoverable through the UI.
 *
 * This store closes that gap: every session's draft is persisted under
 * its own key (`"<hostId>/<sessionName>"`), so switching A→B→A reloads
 * A's draft instead of finding it gone. The composer VM saves the
 * outgoing session's draft and loads the incoming session's draft on
 * each [PromptComposerViewModel.onComposerTargetChanged]; the in-memory
 * [PromptComposerViewModel.UiState] still only ever shows the CURRENT
 * session's draft (the #746 no-bleed guarantee is preserved — it just
 * means "hidden in B", not "destroyed").
 *
 * ## Why SharedPreferences (not Room / DataStore)
 *
 * Same trade-off [com.pocketshell.app.session.LastSessionStore] and
 * [com.pocketshell.app.settings.SettingsRepository] already made: the
 * payload is tiny (a short string per active session), write traffic is
 * one edit per session switch / keystroke-debounce, and SharedPreferences
 * is already on the classpath. A Room entity would force a schema bump +
 * migration for state that needs no relational queries. A future issue is
 * free to migrate.
 *
 * ## Hard-cut (D22)
 *
 * Brand-new auxiliary store — no legacy shape to honour. The single
 * global `LastSessionStore.composer_draft` slot is the superseded one;
 * #832 wires the live composer to THIS per-session store instead.
 *
 * ## Foreground-only (D21)
 *
 * Pure on-disk state. It never holds a connection or schedules work; the
 * VM reads/writes it synchronously on the main thread in response to user
 * interaction (a tiny SharedPreferences edit).
 */
public interface ComposerDraftStore {
    /**
     * The persisted draft for [sessionKey] (`"<hostId>/<sessionName>"`),
     * or `null`/empty when nothing is stored for that session.
     */
    public fun load(sessionKey: String): String?

    /**
     * Persist [draft] as the draft for [sessionKey]. A blank draft is
     * stored as a [clear] so the slot does not linger as an empty string.
     */
    public fun save(sessionKey: String, draft: String)

    /** Drop the stored draft for [sessionKey] (discard / delivered). */
    public fun clear(sessionKey: String)

    /**
     * Issue #872: the persisted staged-attachment refs for [sessionKey], or
     * an empty list when none. This closes the #832 AC2 gap — a failed send
     * + reconnect (or a session switch A→B→A) must restore the actual
     * attachment TILES, not just the text. The refs are stored as durable
     * `remotePath\tdisplayName\tmimeType` rows (the local preview `Uri` is
     * session-transient and intentionally not persisted; the tile still
     * renders from the remote path + display name).
     */
    public fun loadAttachments(sessionKey: String): List<DurableAttachmentRef>

    /**
     * Issue #872: persist [attachments] as the staged-attachment refs for
     * [sessionKey]. An empty list is stored as a [clearAttachments] so the
     * slot does not linger.
     */
    public fun saveAttachments(sessionKey: String, attachments: List<DurableAttachmentRef>)

    /** Issue #872: drop the stored attachment refs for [sessionKey]. */
    public fun clearAttachments(sessionKey: String)
}

/**
 * Issue #872: durable, process-death-survivable representation of a staged
 * attachment tile. The live UI model is
 * [PromptComposerViewModel.StagedAttachment]; this is its serialisable subset
 * (the local preview [android.net.Uri] is session-transient and not stored).
 */
public data class DurableAttachmentRef(
    val remotePath: String,
    val displayName: String,
    val mimeType: String? = null,
)

/**
 * Issue #832: no-op [ComposerDraftStore] used when the ViewModel is
 * constructed without the real store — exclusively by host-JVM unit
 * tests / connected tests that pre-date the store and do not exercise
 * cross-session draft durability. Mirrors the
 * [DisabledPendingTranscriptionQueue] / [AlwaysOnlineConnectivityProbe]
 * pattern so the rich existing test library keeps compiling untouched.
 * Production wiring (`VoiceModule`) always provides the real store.
 */
public object DisabledComposerDraftStore : ComposerDraftStore {
    override fun load(sessionKey: String): String? = null
    override fun save(sessionKey: String, draft: String) = Unit
    override fun clear(sessionKey: String) = Unit
    override fun loadAttachments(sessionKey: String): List<DurableAttachmentRef> = emptyList()
    override fun saveAttachments(sessionKey: String, attachments: List<DurableAttachmentRef>) = Unit
    override fun clearAttachments(sessionKey: String) = Unit
}

/**
 * In-memory [ComposerDraftStore] — a `HashMap` keyed by session. Used as
 * the production store's test double AND as the default for connected
 * tests that need real cross-session durability within a single process
 * without touching SharedPreferences. Thread-confined to the main
 * dispatcher in practice; the map is plain because every composer
 * interaction is single-threaded.
 */
public class InMemoryComposerDraftStore : ComposerDraftStore {
    private val drafts: MutableMap<String, String> = mutableMapOf()
    private val attachments: MutableMap<String, List<DurableAttachmentRef>> = mutableMapOf()

    override fun load(sessionKey: String): String? = drafts[sessionKey]

    override fun save(sessionKey: String, draft: String) {
        if (draft.isEmpty()) {
            drafts.remove(sessionKey)
        } else {
            drafts[sessionKey] = draft
        }
    }

    override fun clear(sessionKey: String) {
        drafts.remove(sessionKey)
    }

    override fun loadAttachments(sessionKey: String): List<DurableAttachmentRef> =
        attachments[sessionKey].orEmpty()

    override fun saveAttachments(sessionKey: String, attachments: List<DurableAttachmentRef>) {
        if (attachments.isEmpty()) {
            this.attachments.remove(sessionKey)
        } else {
            this.attachments[sessionKey] = attachments
        }
    }

    override fun clearAttachments(sessionKey: String) {
        attachments.remove(sessionKey)
    }
}

/**
 * Issue #832: SharedPreferences-backed production [ComposerDraftStore].
 * One small prefs file (`composer_drafts`) keyed by the session id, so a
 * draft survives a session switch AND a process-death recreate (the
 * SavedStateHandle slot only covers the latter, and only for the single
 * currently-focused session). Singleton so every composer instance over
 * the app's lifetime shares the one prefs file.
 */
@Singleton
public class SharedPrefsComposerDraftStore @Inject constructor(
    @ApplicationContext context: Context,
) : ComposerDraftStore {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(sessionKey: String): String? {
        if (sessionKey.isEmpty()) return null
        return runCatching { prefs.getString(sessionKey, null) }.getOrNull()
    }

    override fun save(sessionKey: String, draft: String) {
        if (sessionKey.isEmpty()) return
        if (draft.isEmpty()) {
            clear(sessionKey)
            return
        }
        prefs.edit().putString(sessionKey, draft).apply()
    }

    override fun clear(sessionKey: String) {
        if (sessionKey.isEmpty()) return
        prefs.edit().remove(sessionKey).apply()
    }

    override fun loadAttachments(sessionKey: String): List<DurableAttachmentRef> {
        if (sessionKey.isEmpty()) return emptyList()
        val raw = runCatching { prefs.getString(attachmentKey(sessionKey), null) }.getOrNull()
            ?: return emptyList()
        return decodeAttachments(raw)
    }

    override fun saveAttachments(sessionKey: String, attachments: List<DurableAttachmentRef>) {
        if (sessionKey.isEmpty()) return
        if (attachments.isEmpty()) {
            clearAttachments(sessionKey)
            return
        }
        prefs.edit().putString(attachmentKey(sessionKey), encodeAttachments(attachments)).apply()
    }

    override fun clearAttachments(sessionKey: String) {
        if (sessionKey.isEmpty()) return
        prefs.edit().remove(attachmentKey(sessionKey)).apply()
    }

    private companion object {
        const val PREFS_NAME = "composer_drafts"
    }
}

/**
 * Issue #872: prefs key for the attachment-ref slot of [sessionKey]. Kept in a
 * distinct namespace (`@att/`) so it never collides with the plain text-draft
 * slot keyed by the bare session id.
 */
internal fun attachmentKey(sessionKey: String): String = "@att/$sessionKey"

/**
 * Issue #872: encode attachment refs as newline-separated `path\tname\tmime`
 * rows. Tabs/newlines inside fields are escaped so the round-trip is lossless;
 * a missing mime is the empty 3rd field.
 */
internal fun encodeAttachments(attachments: List<DurableAttachmentRef>): String =
    attachments.joinToString(separator = "\n") { ref ->
        listOf(ref.remotePath, ref.displayName, ref.mimeType.orEmpty())
            .joinToString(separator = "\t") { field ->
                field.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
            }
    }

/** Issue #872: inverse of [encodeAttachments]. Drops malformed rows defensively. */
internal fun decodeAttachments(raw: String): List<DurableAttachmentRef> {
    if (raw.isEmpty()) return emptyList()
    return raw.split('\n').mapNotNull { row ->
        if (row.isEmpty()) return@mapNotNull null
        val fields = row.split('\t').map { field -> unescapeField(field) }
        val remotePath = fields.getOrNull(0).orEmpty()
        if (remotePath.isEmpty()) return@mapNotNull null
        DurableAttachmentRef(
            remotePath = remotePath,
            displayName = fields.getOrNull(1).orEmpty().ifEmpty { remotePath.substringAfterLast('/') },
            mimeType = fields.getOrNull(2).orEmpty().ifEmpty { null },
        )
    }
}

private fun unescapeField(field: String): String {
    val out = StringBuilder(field.length)
    var i = 0
    while (i < field.length) {
        val c = field[i]
        if (c == '\\' && i + 1 < field.length) {
            when (field[i + 1]) {
                't' -> { out.append('\t'); i += 2 }
                'n' -> { out.append('\n'); i += 2 }
                '\\' -> { out.append('\\'); i += 2 }
                else -> { out.append(c); i += 1 }
            }
        } else {
            out.append(c)
            i += 1
        }
    }
    return out.toString()
}
