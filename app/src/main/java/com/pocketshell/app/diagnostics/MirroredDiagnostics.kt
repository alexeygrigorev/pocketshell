package com.pocketshell.app.diagnostics

/**
 * The single registry of WHICH recorded diagnostic events reach the HOST
 * connection log (`~/.pocketshell/connection-log.jsonl`), and HOW MANY BYTES of
 * them, for [DiagnosticRecorder.connectionLogJsonl] and
 * [ConnectionLogHostMirror].
 *
 * ## Why this exists (issues #1642 slice 1, #1598)
 *
 * The mirror used to filter to `reconnect/cause_trail` ONLY. That made the host
 * log a **downs-only, joins-by-inference** instrument, and it cost the project a
 * six-agent investigation: during the #1610 mobile reconnect storm the event that
 * NAMES the storm's engine —
 * `connection/reconnect_fail cause=attach_not_ready elapsedMs≈5000` — was being
 * recorded on-device the whole time and never mirrored. So was
 * `connection/pane_input_send_failed` (the #1635 amplifier) and
 * `connection/session_fgs`, a diagnostic added by #1595 SPECIFICALLY so the FGS
 * outcome could be confirmed remotely.
 *
 * The `connection` category is already curated (~34 lifecycle event names, all
 * connection-relevant), so mirroring it whole is the highest-value/lowest-effort
 * half of the fix. Chatter categories stay device-only — `action` alone is 67
 * user-behaviour event names that would burn mobile data for zero connection
 * diagnosis (#1598: "bound the volume").
 *
 * ## Why the payload is capped
 *
 * [ConnectionLogHostMirror] is **snapshot-overwrite**: it re-uploads the entire
 * rendered window on EVERY transport-up — ~100× on a storm day, over the
 * maintainer's MOBILE DATA. Today that payload is *unbounded*: it grows to
 * whatever the 512KB device store happens to retain. Broadening the filter
 * without a bound would multiply a storm day's upload.
 *
 * [PAYLOAD_BUDGET_BYTES] therefore hard-caps the render at roughly the payload
 * size observed in the maintainer's real corpus today (~56KB), truncating OLDEST
 * so the events describing the drop that just happened always survive. Slice 1
 * consequently spends about the same bytes/upload as before while carrying
 * strictly more diagnostic value per byte; the trade is fewer wall-clock hours of
 * host-side coverage per upload, which #1642 slice 6 (incremental append) removes
 * by uploading only what the host does not already have.
 */
internal object MirroredDiagnostics {

    /** The curated connection-lifecycle category (`connect_start`, `reconnect_fail`, …). */
    const val CONNECTION_CATEGORY: String = "connection"

    /**
     * The composer outbound-queue diagnostic category (issue #1682): `enqueue`,
     * `window_flip`, `drain_attempt`, `row_state`, `wedge_verdict`,
     * `watchdog_timeout`. Mirrored WHOLE (like [CONNECTION_CATEGORY], unlike the
     * device-only `action` chatter) so a real-world clog reaches the host on the
     * SAME correlated timeline as the connection events it disagreed with — the
     * enum-vs-transport smoking gun Track C named. Redaction (ids/sizes only, no
     * raw content) is enforced by
     * [com.pocketshell.app.composer.ComposerQueueDiagnostics] at record time.
     */
    const val QUEUE_CATEGORY: String = "queue"

    /**
     * Ceiling on the rendered host payload. Sized at ~the real per-upload payload
     * observed in the maintainer's corpus today (56,215 bytes / 164 events), so
     * broadening the mirror does not grow a storm day's mobile-data spend. NOT a
     * retention policy: it bounds ONE upload, which is re-sent whole every
     * transport-up.
     */
    const val PAYLOAD_BUDGET_BYTES: Int = 64 * 1024

    /**
     * True when [event] belongs on the host connection log.
     *
     * Deliberately a whole-category rule rather than a name list: a name list is
     * exactly how #1598 happened — `session_fgs` was added, nobody updated the
     * filter, and the diagnostic silently never reached the host. A new event in
     * the [CONNECTION_CATEGORY] is mirrored by construction.
     */
    fun isMirrored(event: DiagnosticsEvent): Boolean = when (event.category) {
        // Everything the reconnect breadcrumbs record (the pre-#1642 payload).
        ReconnectCauseTrail.CATEGORY -> event.name == ReconnectCauseTrail.NAME
        // The whole curated connection lifecycle (#1642 slice 1 / #1598).
        CONNECTION_CATEGORY -> true
        // The whole composer outbound-queue diagnostic surface (#1682).
        QUEUE_CATEGORY -> true
        else -> false
    }

    /**
     * Renders [events] as JSONL (one object per line, the same encoding the
     * on-disk store uses), keeping the NEWEST events that fit in [budgetBytes].
     *
     * Truncates oldest-first: the mirror's job is attributing the drop that just
     * happened, so the tail is what matters. A single event larger than the whole
     * budget is still emitted (better a too-big line than a blank payload the
     * mirror would no-op).
     */
    fun render(
        events: List<DiagnosticsEvent>,
        budgetBytes: Int = PAYLOAD_BUDGET_BYTES,
    ): String {
        val kept = ArrayDeque<String>()
        var total = 0
        for (event in events.asReversed()) {
            val line = DiagnosticEventJson.encode(event)
            val cost = line.toByteArray(Charsets.UTF_8).size + 1 // + the separator
            if (kept.isNotEmpty() && total + cost > budgetBytes) break
            kept.addFirst(line)
            total += cost
        }
        return kept.joinToString(separator = "\n")
    }
}
