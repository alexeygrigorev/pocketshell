package com.pocketshell.app.composer

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Issue #900 — Slice A: the durable persistence + idempotency CORE of the
 * **per-session outbound send queue**.
 *
 * ## Why this exists
 *
 * The send path today is fire-and-forget with no item identity and no
 * idempotency (design spike on #900): each Send tap mints a brand-new
 * fire-and-forget request, the 12s app-side `SEND_TIMEOUT_MS` re-arms the Send
 * button while a slow-but-successful remote paste is still in flight, and
 * "delivered" is a volatile boolean that a flapping link can lose. Repeated
 * taps under a flapping link produced the maintainer's ~3× duplicate.
 *
 * The cure is to route every send through a durable, ordered, per-session
 * queue where each item carries a **stable id minted once at enqueue** and a
 * persisted state machine that only the delivery ack can advance to
 * `Delivered`. This file is the standalone CORE of that queue: the durable
 * store + the state machine + the exactly-once flush-claim API. The composer
 * Send→enqueue wiring (Slice B), the flush worker + ack-gate (Slice C), and
 * the end-to-end journey (Slice D) build on top of this and are OUT of scope
 * here.
 *
 * ## Idempotency / send-once contract (the heart of the 3× bug)
 *
 * 1. **Enqueue is keyed by the item id.** [enqueue] mints a stable
 *    [UUID][java.util.UUID] ONCE and persists it before any delivery attempt.
 *    [enqueueExisting] re-enqueuing an id that is already pending
 *    (`Queued`/`Uploading`/`InFlight`) is a **no-op** — the durable state, not
 *    a volatile in-memory flag, gates the repeat. This survives the 12s
 *    timeout re-arm AND process death.
 *
 * 2. **A single serial per-session flush-claim API guarantees exactly-once.**
 *    [claimNext] atomically moves the oldest `Queued` item for a session to
 *    `InFlight` and returns it; two concurrent flushers calling [claimNext]
 *    for the same session can never both receive the same item (one wins, the
 *    other gets either the next queued item or `null`). [markDelivered] /
 *    [markFailed] complete the transition. An item already `InFlight` is never
 *    re-claimed, so a reconnect-triggered flush racing an in-progress delivery
 *    cannot re-paste it.
 *
 * ## Per-session key (the #899 dependency)
 *
 * Items are keyed by an opaque [OutboundItem.sessionKey] **string param** that
 * the caller supplies. Slice A does NOT derive the key from a folder name —
 * the folder-derived `"${hostId}/${sessionName}"` is the exact collision #899
 * is fixing. In Slice C this param will be bound to the durable `SessionId`
 * #899 introduces. Until then it is just an opaque key: a flush for session A
 * never claims an item keyed to session B ([claimNext] filters by sessionKey).
 *
 * ## Why SharedPreferences (matching the #832 store family)
 *
 * Same trade-off [ComposerDraftStore] made: one user, a handful of queued
 * items, no relational queries needed. The queue is a JSON-ish blob per
 * session in a dedicated prefs file; a per-item state transition is a
 * read-modify-write of that session's small list (acceptable at this scale,
 * as the spike notes). A future issue is free to migrate to Room. The
 * [DurableAttachmentRef] reuse means the attachment encoding is shared with
 * [ComposerDraftStore].
 *
 * ## Foreground-only (D21)
 *
 * Pure on-disk state. It never holds a connection or schedules work. The flush
 * worker that consumes it (Slice C) runs only while foregrounded.
 *
 * ## Hard-cut (D22)
 *
 * Brand-new auxiliary store — no legacy shape to honour. It does NOT wire into
 * any existing send path in Slice A.
 */
public interface OutboundQueueStore {

    /**
     * Enqueue a new outbound item for [sessionKey], minting a stable [UUID] id
     * ONCE and persisting it in state [OutboundState.Queued] before any
     * delivery attempt. Returns the newly created item (its [OutboundItem.id]
     * is the durable idempotency key the caller holds onto).
     *
     * Position/ordering is by [OutboundItem.createdAtMs] (oldest-first), so
     * the flush loop delivers in the order the user committed to send.
     */
    public fun enqueue(
        sessionKey: String,
        cleanText: String,
        attachments: List<DurableAttachmentRef> = emptyList(),
        withEnter: Boolean = true,
        createdAtMs: Long = System.currentTimeMillis(),
        paneId: String = "",
        route: OutboundRoute = OutboundRoute.RawBytes,
        agentKind: String? = null,
    ): OutboundItem

    /**
     * Idempotent re-enqueue of an item that already has an id (e.g. a retry
     * path that already knows the id). If an item with [item]'s id is already
     * present AND pending (`Queued`/`Uploading`/`InFlight`), this is a
     * **NO-OP** and returns the EXISTING persisted item — never a second copy.
     * A `Failed` item is re-armed to `Queued` (an explicit retry). A
     * `Delivered` id (already pruned in normal flow) is treated as
     * already-done and not re-added. If the id is unknown, the item is stored
     * as given.
     *
     * This is the structural send-once guard: a duplicate enqueue of a pending
     * id can never create a second deliverable item.
     */
    public fun enqueueExisting(item: OutboundItem): OutboundItem

    /** All items for [sessionKey], ordered oldest-first by [OutboundItem.createdAtMs]. */
    public fun itemsFor(sessionKey: String): List<OutboundItem>

    /** The item with [id], or `null` if unknown (e.g. already delivered+pruned). */
    public fun item(id: String): OutboundItem?

    /**
     * Atomically claim the oldest `Queued` item for [sessionKey], transition it
     * to [OutboundState.InFlight], persist that, and return it. Returns `null`
     * when there is no `Queued` item for that session.
     *
     * **Exactly-once guarantee:** the claim is serialized so two concurrent
     * callers for the same [sessionKey] can never both receive the same item.
     * An item NOT in `Queued` state (e.g. already `InFlight` from a prior
     * claim) is skipped, so a reconnect-triggered flush racing an in-progress
     * delivery does not re-claim it. Items keyed to other sessions are never
     * returned (per-session isolation).
     *
     * Slice C's flush worker is the single serial consumer per session; this
     * API is what makes a second concurrent flusher safe even if one is ever
     * started.
     */
    public fun claimNext(sessionKey: String): OutboundItem?

    /**
     * Mark the item with [id] [OutboundState.InFlight] for a send attempt that
     * already knows its durable id. Unlike [claimNext], this does not pick the
     * oldest queued row; it transitions exactly the row that the caller just
     * enqueued. No-op for unknown, delivered, or failed rows.
     */
    public fun markInFlight(id: String): OutboundItem?

    /**
     * Mark the item with [id] [OutboundState.Uploading] (attachment upload in
     * progress). No-op if the id is unknown. Returns the updated item or
     * `null`. Only an item currently `Queued` or `InFlight` is moved; a
     * `Delivered`/`Failed` id is left as-is so a late ack cannot resurrect it.
     */
    public fun markUploading(id: String): OutboundItem?

    /**
     * Complete delivery of the item with [id]: the ack confirmed the paste was
     * ingested and Enter pressed. The row is transitioned to
     * [OutboundState.Delivered] and **pruned** so it never grows unbounded and
     * never appears in the UI again. A repeated [markDelivered] for an
     * already-delivered/pruned id is a NO-OP (a late duplicate ack cannot
     * cause a second delivery). Returns `true` if this call performed the
     * delivery transition, `false` if it was already delivered/unknown.
     */
    public fun markDelivered(id: String): Boolean

    /**
     * Mark the item with [id] [OutboundState.Failed] with [lastError], bump
     * its attempt count, and stamp [lastAttemptAtMs]. The row is KEPT (visible,
     * retryable) — never silently dropped. No-op for an unknown/already-
     * delivered id. Returns the updated item or `null`.
     */
    public fun markFailed(
        id: String,
        lastError: String?,
        lastAttemptAtMs: Long = System.currentTimeMillis(),
    ): OutboundItem?

    /**
     * Remove the item with [id] regardless of state — the user explicitly
     * cancelled/deleted a pending item before it sent (AC4). Returns `true` if
     * an item was removed.
     */
    public fun remove(id: String): Boolean

    /** Drop every item for [sessionKey]. */
    public fun clearSession(sessionKey: String)
}

/**
 * Issue #900: the durable, process-death-survivable representation of one
 * committed-to-send outbound item.
 *
 * @property id stable idempotency key, minted ONCE at [OutboundQueueStore.enqueue].
 * @property sessionKey the opaque per-session key (the #899 durable SessionId
 *   in Slice C; an opaque param in Slice A — NOT folder-derived here).
 * @property cleanText the editable draft text (no appended "Attached files:" block).
 * @property attachments staged attachment refs (reuses [DurableAttachmentRef]).
 * @property withEnter whether delivery presses Enter after the paste.
 * @property state the persisted state-machine position.
 * @property createdAtMs enqueue time — the ordering key (oldest-first).
 * @property lastAttemptAtMs time of the last delivery attempt, or `null`.
 * @property attemptCount number of delivery attempts so far.
 * @property lastError the last delivery error, or `null`.
 * @property paneId pane targeted by the original send action. Empty for legacy
 *   rows that were persisted before route metadata existed.
 * @property route the delivery path selected when the item was enqueued.
 * @property agentKind optional agent token captured with agent-routed sends.
 */
public data class OutboundItem(
    val id: String,
    val sessionKey: String,
    val cleanText: String,
    val attachments: List<DurableAttachmentRef> = emptyList(),
    val withEnter: Boolean = true,
    val state: OutboundState = OutboundState.Queued,
    val createdAtMs: Long,
    val lastAttemptAtMs: Long? = null,
    val attemptCount: Int = 0,
    val lastError: String? = null,
    val paneId: String = "",
    val route: OutboundRoute = OutboundRoute.RawBytes,
    val agentKind: String? = null,
)

/** Issue #900: persisted send route selected before an item entered the durable queue. */
public enum class OutboundRoute {
    AgentConversation,
    AgentPayload,
    RawBytes,
}

/**
 * Issue #900: the per-item state machine.
 *
 * `Queued → Uploading → InFlight → Delivered` is the happy path; any attempt
 * can drop to `Failed`, which is retryable (back to `Queued`) and never
 * silently dropped. [isPending] is the set that gates a duplicate enqueue
 * no-op.
 */
public enum class OutboundState {
    Queued,
    Uploading,
    InFlight,
    Delivered,
    Failed,
    ;

    /**
     * Whether an item in this state is still "committed and not yet delivered"
     * — a duplicate enqueue of an id in a pending state is a no-op. `Failed` is
     * NOT pending (it is re-armable to `Queued` by an explicit retry).
     */
    public val isPending: Boolean
        get() = this == Queued || this == Uploading || this == InFlight
}

/**
 * Issue #900: in-memory [OutboundQueueStore] — the production store's test
 * double AND the default for connected tests that need real per-session queue
 * semantics within one process without touching SharedPreferences. Mirrors
 * [InMemoryComposerDraftStore].
 *
 * **Thread-safe by design** — the exactly-once flush-claim guarantee
 * ([claimNext]) is the whole point of this store, so every mutation is
 * serialized under a single [lock]. Two concurrent flushers calling
 * [claimNext] for the same session therefore cannot both win the same item.
 */
public class InMemoryOutboundQueueStore : OutboundQueueStore {

    private val lock = Any()

    /** id -> item. A single map is enough; per-session views filter by [OutboundItem.sessionKey]. */
    private val items: MutableMap<String, OutboundItem> = LinkedHashMap()

    override fun enqueue(
        sessionKey: String,
        cleanText: String,
        attachments: List<DurableAttachmentRef>,
        withEnter: Boolean,
        createdAtMs: Long,
        paneId: String,
        route: OutboundRoute,
        agentKind: String?,
    ): OutboundItem = synchronized(lock) {
        val item = OutboundItem(
            id = UUID.randomUUID().toString(),
            sessionKey = sessionKey,
            cleanText = cleanText,
            attachments = attachments,
            withEnter = withEnter,
            state = OutboundState.Queued,
            createdAtMs = createdAtMs,
            paneId = paneId,
            route = route,
            agentKind = agentKind,
        )
        items[item.id] = item
        item
    }

    override fun enqueueExisting(item: OutboundItem): OutboundItem = synchronized(lock) {
        val existing = items[item.id]
        when {
            // Already pending — duplicate enqueue is a strict no-op.
            existing != null && existing.state.isPending -> existing
            // Already delivered (and not yet pruned) — do not resurrect.
            existing != null && existing.state == OutboundState.Delivered -> existing
            // Failed → explicit retry re-arms to Queued.
            existing != null && existing.state == OutboundState.Failed -> {
                val rearmed = existing.copy(state = OutboundState.Queued)
                items[rearmed.id] = rearmed
                rearmed
            }
            // Unknown id — store as given.
            else -> {
                items[item.id] = item
                item
            }
        }
    }

    override fun itemsFor(sessionKey: String): List<OutboundItem> = synchronized(lock) {
        items.values
            .filter { it.sessionKey == sessionKey }
            .sortedBy { it.createdAtMs }
    }

    override fun item(id: String): OutboundItem? = synchronized(lock) { items[id] }

    override fun claimNext(sessionKey: String): OutboundItem? = synchronized(lock) {
        val next = items.values
            .filter { it.sessionKey == sessionKey && it.state == OutboundState.Queued }
            .minByOrNull { it.createdAtMs }
            ?: return null
        val claimed = next.copy(state = OutboundState.InFlight)
        items[claimed.id] = claimed
        claimed
    }

    override fun markInFlight(id: String): OutboundItem? = synchronized(lock) {
        val existing = items[id] ?: return null
        if (existing.state == OutboundState.Delivered || existing.state == OutboundState.Failed) {
            return existing
        }
        val updated = existing.copy(state = OutboundState.InFlight)
        items[updated.id] = updated
        updated
    }

    override fun markUploading(id: String): OutboundItem? = synchronized(lock) {
        val existing = items[id] ?: return null
        if (existing.state == OutboundState.Delivered || existing.state == OutboundState.Failed) {
            return existing
        }
        val updated = existing.copy(state = OutboundState.Uploading)
        items[updated.id] = updated
        updated
    }

    override fun markDelivered(id: String): Boolean = synchronized(lock) {
        val existing = items[id] ?: return false
        if (existing.state == OutboundState.Delivered) return false
        // Prune on delivery so the store never grows unbounded.
        items.remove(id)
        true
    }

    override fun markFailed(id: String, lastError: String?, lastAttemptAtMs: Long): OutboundItem? =
        synchronized(lock) {
            val existing = items[id] ?: return null
            if (existing.state == OutboundState.Delivered) return existing
            val updated = existing.copy(
                state = OutboundState.Failed,
                lastError = lastError,
                lastAttemptAtMs = lastAttemptAtMs,
                attemptCount = existing.attemptCount + 1,
            )
            items[updated.id] = updated
            updated
        }

    override fun remove(id: String): Boolean = synchronized(lock) { items.remove(id) != null }

    override fun clearSession(sessionKey: String): Unit = synchronized(lock) {
        items.values.removeAll { it.sessionKey == sessionKey }
    }
}

/**
 * Issue #900: no-op [OutboundQueueStore] used when a ViewModel/test is
 * constructed without the real store. Mirrors [DisabledComposerDraftStore].
 */
public object DisabledOutboundQueueStore : OutboundQueueStore {
    override fun enqueue(
        sessionKey: String,
        cleanText: String,
        attachments: List<DurableAttachmentRef>,
        withEnter: Boolean,
        createdAtMs: Long,
        paneId: String,
        route: OutboundRoute,
        agentKind: String?,
    ): OutboundItem = OutboundItem(
        id = UUID.randomUUID().toString(),
        sessionKey = sessionKey,
        cleanText = cleanText,
        attachments = attachments,
        withEnter = withEnter,
        createdAtMs = createdAtMs,
        paneId = paneId,
        route = route,
        agentKind = agentKind,
    )

    override fun enqueueExisting(item: OutboundItem): OutboundItem = item
    override fun itemsFor(sessionKey: String): List<OutboundItem> = emptyList()
    override fun item(id: String): OutboundItem? = null
    override fun claimNext(sessionKey: String): OutboundItem? = null
    override fun markInFlight(id: String): OutboundItem? = null
    override fun markUploading(id: String): OutboundItem? = null
    override fun markDelivered(id: String): Boolean = false
    override fun markFailed(id: String, lastError: String?, lastAttemptAtMs: Long): OutboundItem? = null
    override fun remove(id: String): Boolean = false
    override fun clearSession(sessionKey: String) = Unit
}

/**
 * Issue #900: SharedPreferences-backed production [OutboundQueueStore]. One
 * small prefs file (`outbound_queue`) with one JSON-ish blob per session key,
 * so the queue survives process death and app restart (AC1). Singleton so
 * every consumer over the app's lifetime shares the one prefs file.
 *
 * A per-item state transition is a read-modify-write of that session's small
 * item list (the spike's accepted trade-off at this scale). All mutations are
 * serialized under [lock] so the exactly-once [claimNext] guarantee holds
 * across threads within the process; the durable blob is the cross-process /
 * cross-restart guarantee.
 *
 * The blob format reuses the [ComposerDraftStore] attachment encoding for the
 * attachment field; the item rows themselves are tab-delimited and
 * newline-separated, with the same `\`-escaping so text/paths containing
 * tabs/newlines round-trip losslessly.
 */
@Singleton
public class SharedPrefsOutboundQueueStore @Inject constructor(
    @ApplicationContext context: Context,
) : OutboundQueueStore {

    private val prefs: SharedPreferences = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val lock = Any()

    /** All session keys we have ever written, so [item]/[markX] by id can find the owning session. */
    private fun sessionKeys(): Set<String> =
        runCatching { prefs.getStringSet(SESSION_INDEX_KEY, emptySet()) }.getOrNull().orEmpty()

    private fun loadSession(sessionKey: String): MutableList<OutboundItem> {
        if (sessionKey.isEmpty()) return mutableListOf()
        val raw = runCatching { prefs.getString(blobKey(sessionKey), null) }.getOrNull()
            ?: return mutableListOf()
        return decodeOutboundItems(sessionKey, raw).toMutableList()
    }

    private fun storeSession(sessionKey: String, list: List<OutboundItem>) {
        if (sessionKey.isEmpty()) return
        val editor = prefs.edit()
        if (list.isEmpty()) {
            editor.remove(blobKey(sessionKey))
            editor.putStringSet(SESSION_INDEX_KEY, sessionKeys() - sessionKey)
        } else {
            editor.putString(blobKey(sessionKey), encodeOutboundItems(list))
            editor.putStringSet(SESSION_INDEX_KEY, sessionKeys() + sessionKey)
        }
        editor.apply()
    }

    /** Find the session that owns [id], or `null`. */
    private fun sessionOf(id: String): String? =
        sessionKeys().firstOrNull { key -> loadSession(key).any { it.id == id } }

    override fun enqueue(
        sessionKey: String,
        cleanText: String,
        attachments: List<DurableAttachmentRef>,
        withEnter: Boolean,
        createdAtMs: Long,
        paneId: String,
        route: OutboundRoute,
        agentKind: String?,
    ): OutboundItem = synchronized(lock) {
        val item = OutboundItem(
            id = UUID.randomUUID().toString(),
            sessionKey = sessionKey,
            cleanText = cleanText,
            attachments = attachments,
            withEnter = withEnter,
            state = OutboundState.Queued,
            createdAtMs = createdAtMs,
            paneId = paneId,
            route = route,
            agentKind = agentKind,
        )
        val list = loadSession(sessionKey)
        list.add(item)
        storeSession(sessionKey, list.sortedBy { it.createdAtMs })
        item
    }

    override fun enqueueExisting(item: OutboundItem): OutboundItem = synchronized(lock) {
        val list = loadSession(item.sessionKey)
        val existing = list.firstOrNull { it.id == item.id }
        when {
            existing != null && existing.state.isPending -> existing
            existing != null && existing.state == OutboundState.Delivered -> existing
            existing != null && existing.state == OutboundState.Failed -> {
                val rearmed = existing.copy(state = OutboundState.Queued)
                replaceAndStore(item.sessionKey, list, rearmed)
                rearmed
            }
            else -> {
                list.add(item)
                storeSession(item.sessionKey, list.sortedBy { it.createdAtMs })
                item
            }
        }
    }

    override fun itemsFor(sessionKey: String): List<OutboundItem> = synchronized(lock) {
        loadSession(sessionKey).sortedBy { it.createdAtMs }
    }

    override fun item(id: String): OutboundItem? = synchronized(lock) {
        sessionKeys().firstNotNullOfOrNull { key -> loadSession(key).firstOrNull { it.id == id } }
    }

    override fun claimNext(sessionKey: String): OutboundItem? = synchronized(lock) {
        val list = loadSession(sessionKey)
        val next = list
            .filter { it.state == OutboundState.Queued }
            .minByOrNull { it.createdAtMs }
            ?: return null
        val claimed = next.copy(state = OutboundState.InFlight)
        replaceAndStore(sessionKey, list, claimed)
        claimed
    }

    override fun markInFlight(id: String): OutboundItem? = synchronized(lock) {
        val sessionKey = sessionOf(id) ?: return null
        val list = loadSession(sessionKey)
        val existing = list.firstOrNull { it.id == id } ?: return null
        if (existing.state == OutboundState.Delivered || existing.state == OutboundState.Failed) {
            return existing
        }
        val updated = existing.copy(state = OutboundState.InFlight)
        replaceAndStore(sessionKey, list, updated)
        updated
    }

    override fun markUploading(id: String): OutboundItem? = synchronized(lock) {
        val sessionKey = sessionOf(id) ?: return null
        val list = loadSession(sessionKey)
        val existing = list.firstOrNull { it.id == id } ?: return null
        if (existing.state == OutboundState.Delivered || existing.state == OutboundState.Failed) {
            return existing
        }
        val updated = existing.copy(state = OutboundState.Uploading)
        replaceAndStore(sessionKey, list, updated)
        updated
    }

    override fun markDelivered(id: String): Boolean = synchronized(lock) {
        val sessionKey = sessionOf(id) ?: return false
        val list = loadSession(sessionKey)
        val existing = list.firstOrNull { it.id == id } ?: return false
        if (existing.state == OutboundState.Delivered) return false
        // Prune on delivery.
        list.removeAll { it.id == id }
        storeSession(sessionKey, list)
        true
    }

    override fun markFailed(id: String, lastError: String?, lastAttemptAtMs: Long): OutboundItem? =
        synchronized(lock) {
            val sessionKey = sessionOf(id) ?: return null
            val list = loadSession(sessionKey)
            val existing = list.firstOrNull { it.id == id } ?: return null
            if (existing.state == OutboundState.Delivered) return existing
            val updated = existing.copy(
                state = OutboundState.Failed,
                lastError = lastError,
                lastAttemptAtMs = lastAttemptAtMs,
                attemptCount = existing.attemptCount + 1,
            )
            replaceAndStore(sessionKey, list, updated)
            updated
        }

    override fun remove(id: String): Boolean = synchronized(lock) {
        val sessionKey = sessionOf(id) ?: return false
        val list = loadSession(sessionKey)
        val removed = list.removeAll { it.id == id }
        if (removed) storeSession(sessionKey, list)
        removed
    }

    override fun clearSession(sessionKey: String): Unit = synchronized(lock) {
        storeSession(sessionKey, emptyList())
    }

    private fun replaceAndStore(sessionKey: String, list: MutableList<OutboundItem>, updated: OutboundItem) {
        val idx = list.indexOfFirst { it.id == updated.id }
        if (idx >= 0) list[idx] = updated else list.add(updated)
        storeSession(sessionKey, list.sortedBy { it.createdAtMs })
    }

    private companion object {
        const val PREFS_NAME = "outbound_queue"
        const val SESSION_INDEX_KEY = "@sessions"
    }
}

/** Issue #900: prefs key for the item-blob slot of [sessionKey]. */
internal fun blobKey(sessionKey: String): String = "@q/$sessionKey"

/**
 * Issue #900: encode a session's outbound items as newline-separated rows.
 * Each row is tab-delimited:
 * `id \t cleanText \t withEnter \t state \t createdAtMs \t lastAttemptAtMs \t attemptCount \t lastError \t attachmentsBlob \t paneId \t route \t agentKind`
 * with the same `\`-escaping [ComposerDraftStore] uses so text/paths containing
 * tabs/newlines round-trip losslessly. The attachments field reuses
 * [encodeAttachments], itself escaped as a single field.
 */
internal fun encodeOutboundItems(items: List<OutboundItem>): String =
    items.joinToString(separator = "\n") { item ->
        listOf(
            item.id,
            item.cleanText,
            if (item.withEnter) "1" else "0",
            item.state.name,
            item.createdAtMs.toString(),
            item.lastAttemptAtMs?.toString().orEmpty(),
            item.attemptCount.toString(),
            item.lastError.orEmpty(),
            encodeAttachments(item.attachments),
            item.paneId,
            item.route.name,
            item.agentKind.orEmpty(),
        ).joinToString(separator = "\t") { escapeQueueField(it) }
    }

/** Issue #900: inverse of [encodeOutboundItems]. Drops malformed rows defensively. */
internal fun decodeOutboundItems(sessionKey: String, raw: String): List<OutboundItem> {
    if (raw.isEmpty()) return emptyList()
    return raw.split('\n').mapNotNull { row ->
        if (row.isEmpty()) return@mapNotNull null
        val f = row.split('\t').map { unescapeQueueField(it) }
        val id = f.getOrNull(0).orEmpty()
        if (id.isEmpty()) return@mapNotNull null
        val createdAtMs = f.getOrNull(4)?.toLongOrNull() ?: return@mapNotNull null
        OutboundItem(
            id = id,
            sessionKey = sessionKey,
            cleanText = f.getOrNull(1).orEmpty(),
            withEnter = f.getOrNull(2) != "0",
            state = runCatching { OutboundState.valueOf(f.getOrNull(3).orEmpty()) }
                .getOrDefault(OutboundState.Queued),
            createdAtMs = createdAtMs,
            lastAttemptAtMs = f.getOrNull(5)?.takeIf { it.isNotEmpty() }?.toLongOrNull(),
            attemptCount = f.getOrNull(6)?.toIntOrNull() ?: 0,
            lastError = f.getOrNull(7)?.takeIf { it.isNotEmpty() },
            attachments = decodeAttachments(f.getOrNull(8).orEmpty()),
            paneId = f.getOrNull(9).orEmpty(),
            route = runCatching { OutboundRoute.valueOf(f.getOrNull(10).orEmpty()) }
                .getOrDefault(OutboundRoute.RawBytes),
            agentKind = f.getOrNull(11)?.takeIf { it.isNotEmpty() },
        )
    }
}

private fun escapeQueueField(field: String): String =
    field.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

private fun unescapeQueueField(field: String): String {
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
