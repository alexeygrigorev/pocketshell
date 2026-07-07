package com.pocketshell.app.voice

import android.content.Context
import android.content.SharedPreferences
import com.pocketshell.app.prefs.ResilientPrefs
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Durable holding pen for a *successfully transcribed* voice command whose
 * text could NOT be delivered to a terminal pane — issue #1272.
 *
 * ## Why this exists (the residual silent-data-loss tail from #1226)
 *
 * Issue #1226 made [com.pocketshell.app.session.InlineDictationViewModel]'s
 * transcript hand-off a buffering `Channel`, so a transcript produced during a
 * brief drop/reconnect (the focused pane momentarily flips to `null` /
 * re-keys) is buffered and delivered once the pane re-subscribes. That fixed
 * the *transient* gap. It did NOT fix the *permanent* gap: if the session never
 * returns (the user navigates away, the session is fully gone), **no collector
 * ever subscribes**, so the buffered transcript is delivered to no one and is
 * silently lost with no retry affordance. The #1226 reviewer flagged this as
 * the residual tail.
 *
 * This store closes it. When the delivery channel confirms a transcript is
 * undeliverable — the ViewModel is cleared with the transcript still buffered
 * (permanent pane death), OR the bounded channel overflows — the text is
 * [persist]ed here. The store survives process death (SharedPreferences),
 * exposes the queue as [items] so a surface can render a visible "couldn't
 * deliver — retry" affordance, and lets the user [remove] an item once
 * re-delivered or dismissed.
 *
 * Unlike the audio-backed [PendingTranscriptionStore] (which persists the raw
 * WAV so a *failed* Whisper call can be *re-transcribed*), this store persists
 * the already-resolved *text* — the transcription succeeded, only the terminal
 * hand-off failed, so recovery is a re-delivery, not a re-transcription.
 */
interface UndeliveredTranscriptStore {
    /** The current queue of undelivered transcripts, newest first. */
    val items: StateFlow<List<UndeliveredTranscript>>

    /**
     * Persist [text] as an undelivered transcript. Returns the stored item, or
     * `null` if [text] is blank (nothing worth surfacing). Safe to call from a
     * non-suspending / arbitrary-thread context (the delivery channel's
     * `onUndeliveredElement` callback and ViewModel teardown both call it).
     */
    fun persist(text: String): UndeliveredTranscript?

    /** Drop the item with [id] (re-delivered or user-dismissed). Idempotent. */
    fun remove(id: String)

    /** Point-in-time snapshot of the queue, newest first. */
    fun snapshot(): List<UndeliveredTranscript>
}

/**
 * One undelivered transcript. [text] is the resolved Whisper/speech output that
 * was never written to a pane; [id] is a stable UUID used for [remove].
 */
data class UndeliveredTranscript(
    val id: String,
    val text: String,
    val createdAtMs: Long,
)

/**
 * Process-lifetime, non-durable [UndeliveredTranscriptStore]. Used as the
 * ViewModel's default (so unit tests exercise the persist/expose behavior
 * without wiring the SharedPreferences implementation) and anywhere durability
 * is not required. Thread-safe.
 */
class InMemoryUndeliveredTranscriptStore(
    internal var clock: () -> Long = { System.currentTimeMillis() },
    internal var idGenerator: () -> String = { UUID.randomUUID().toString() },
) : UndeliveredTranscriptStore {

    private val lock = Any()
    private val rows = LinkedHashMap<String, UndeliveredTranscript>()
    private val _items = MutableStateFlow<List<UndeliveredTranscript>>(emptyList())
    override val items: StateFlow<List<UndeliveredTranscript>> = _items.asStateFlow()

    override fun persist(text: String): UndeliveredTranscript? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        synchronized(lock) {
            val item = UndeliveredTranscript(
                id = idGenerator(),
                text = trimmed,
                createdAtMs = clock(),
            )
            rows[item.id] = item
            trimToCap(rows)
            publish()
            return item
        }
    }

    override fun remove(id: String) {
        synchronized(lock) {
            if (rows.remove(id) != null) publish()
        }
    }

    override fun snapshot(): List<UndeliveredTranscript> = synchronized(lock) { newestFirst(rows) }

    private fun publish() {
        _items.value = newestFirst(rows)
    }
}

/**
 * SharedPreferences-backed, durable [UndeliveredTranscriptStore]. The whole
 * queue is serialized as a single JSON array under one prefs key so a persist /
 * remove is one small read-modify-write. Opened through [ResilientPrefs] (issue
 * #1292) so a corrupt prefs file can't crash the app — it is cleared and
 * re-opened fresh instead.
 *
 * `@Singleton` so every activity-scoped inline-dictation ViewModel over the app
 * lifetime observes and mutates the same queue: a transcript persisted while
 * one session screen is torn down is visible to the next session screen's
 * ViewModel, and a process restart reloads the queue from disk.
 */
@Singleton
class SharedPrefsUndeliveredTranscriptStore @Inject constructor(
    @ApplicationContext context: Context,
) : UndeliveredTranscriptStore {

    private val prefs: SharedPreferences = ResilientPrefs.open(context, PREFS_NAME)
    private val lock = Any()
    private val rows = LinkedHashMap<String, UndeliveredTranscript>()
    private val _items = MutableStateFlow<List<UndeliveredTranscript>>(emptyList())
    override val items: StateFlow<List<UndeliveredTranscript>> = _items.asStateFlow()

    // Test seams — production wires wall-clock + random UUIDs; tests substitute
    // deterministic generators. Mutable fields (not constructor params) so the
    // Hilt graph doesn't have to bind function types.
    internal var clock: () -> Long = { System.currentTimeMillis() }
    internal var idGenerator: () -> String = { UUID.randomUUID().toString() }

    init {
        synchronized(lock) {
            rows.putAll(readFromDisk().associateBy { it.id })
            publish()
        }
    }

    override fun persist(text: String): UndeliveredTranscript? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        synchronized(lock) {
            val item = UndeliveredTranscript(
                id = idGenerator(),
                text = trimmed,
                createdAtMs = clock(),
            )
            rows[item.id] = item
            trimToCap(rows)
            writeToDisk()
            publish()
            return item
        }
    }

    override fun remove(id: String) {
        synchronized(lock) {
            if (rows.remove(id) != null) {
                writeToDisk()
                publish()
            }
        }
    }

    override fun snapshot(): List<UndeliveredTranscript> = synchronized(lock) { newestFirst(rows) }

    private fun publish() {
        _items.value = newestFirst(rows)
    }

    private fun readFromDisk(): List<UndeliveredTranscript> {
        val raw = runCatching { prefs.getString(KEY_QUEUE, null) }.getOrNull() ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val obj = arr.optJSONObject(i) ?: return@mapNotNull null
                val id = obj.optString(FIELD_ID, "")
                val text = obj.optString(FIELD_TEXT, "")
                if (id.isEmpty() || text.isEmpty()) return@mapNotNull null
                UndeliveredTranscript(
                    id = id,
                    text = text,
                    createdAtMs = obj.optLong(FIELD_CREATED_AT, 0L),
                )
            }
        }.getOrDefault(emptyList())
    }

    private fun writeToDisk() {
        val arr = JSONArray()
        // Persist insertion order (oldest first); readFromDisk / newestFirst
        // re-establish the ordering invariants on load and on publish.
        rows.values.forEach { item ->
            arr.put(
                JSONObject()
                    .put(FIELD_ID, item.id)
                    .put(FIELD_TEXT, item.text)
                    .put(FIELD_CREATED_AT, item.createdAtMs),
            )
        }
        runCatching { prefs.edit().putString(KEY_QUEUE, arr.toString()).apply() }
    }

    companion object {
        const val PREFS_NAME: String = "undelivered_transcripts"
        private const val KEY_QUEUE: String = "queue"
        private const val FIELD_ID: String = "id"
        private const val FIELD_TEXT: String = "text"
        private const val FIELD_CREATED_AT: String = "createdAt"
    }
}

/**
 * Hard cap on the number of persisted undelivered transcripts. Voice
 * transcripts are low-volume, but a permanently-dead pane hit with rapid
 * repeated dictation could otherwise grow the queue without bound (the second
 * #1272 gap, mirrored on the persistence side of the bounded delivery channel).
 * When the cap is exceeded the OLDEST rows are dropped.
 */
internal const val MAX_UNDELIVERED_TRANSCRIPTS: Int = 50

/** Trim [rows] (insertion-ordered, oldest first) down to [MAX_UNDELIVERED_TRANSCRIPTS]. */
private fun trimToCap(rows: LinkedHashMap<String, UndeliveredTranscript>) {
    while (rows.size > MAX_UNDELIVERED_TRANSCRIPTS) {
        val oldest = rows.keys.iterator().next()
        rows.remove(oldest)
    }
}

/** Newest-first view of an insertion-ordered (oldest-first) map. */
private fun newestFirst(rows: LinkedHashMap<String, UndeliveredTranscript>): List<UndeliveredTranscript> =
    rows.values.toList().asReversed()
