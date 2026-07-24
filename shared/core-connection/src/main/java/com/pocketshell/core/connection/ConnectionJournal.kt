package com.pocketshell.core.connection

import java.security.MessageDigest

/**
 * Synchronous submit-boundary journal tap (#1709).
 *
 * The controller is confined to one dispatcher, so emission is ordered with the
 * reduction and needs no extra synchronization. The default is a true no-op;
 * core tests and the app adapter inject a recorder explicitly.
 */
fun interface ConnectionJournalPort {
    fun record(entry: ConnectionJournalEntry)

    companion object {
        val Noop: ConnectionJournalPort = ConnectionJournalPort { }
    }
}

/**
 * Replay-complete controller journal. Every identity inside an entry is already
 * an equality-preserving `sha256:` fingerprint; raw host/session values never
 * cross this boundary.
 */
sealed interface ConnectionJournalEntry {
    val journalSeq: Long

    data class Construct(
        override val journalSeq: Long = 0L,
        val nowMs: Long,
        val graceMs: Long,
        val stabilityWindowMs: Long,
        val episodeBudgetMs: Long,
        val baseLadderMs: List<Long>,
        val jitteredLadderMs: List<Long>,
    ) : ConnectionJournalEntry

    data class LadderInstall(
        override val journalSeq: Long,
        val baseLadderMs: List<Long>,
        val jitteredLadderMs: List<Long>,
    ) : ConnectionJournalEntry

    data class Submit(
        override val journalSeq: Long,
        val nowMs: Long,
        val event: ConnectionEvent,
        val preState: ConnectionState,
        val postState: ConnectionState,
        val isWarm: Boolean?,
        val internals: ConnectionJournalInternals,
    ) : ConnectionJournalEntry
}

/** Hidden episode state that is intentionally not derivable from [ConnectionState]. */
data class ConnectionJournalInternals(
    val reconnectAttempt: Int,
    val episodeStartMs: Long?,
    val liveSinceMs: Long?,
    val graceDeadlineMs: Long?,
)

/**
 * Stable flat schema stored in `DiagnosticsEvent.metadata`.
 *
 * Lists are comma-separated strings because the app redactor deliberately
 * reduces arbitrary collections to `{count:n}`. Identity values are
 * pre-fingerprinted and use `*Fingerprint` keys so the app redactor cannot hash
 * them a second time.
 */
object ConnectionJournalSchema {
    const val CATEGORY: String = "connection_journal"
    const val CONSTRUCT: String = "construct"
    const val LADDER_INSTALL: String = "ladder_install"
    const val SUBMIT: String = "submit"

    fun name(entry: ConnectionJournalEntry): String = when (entry) {
        is ConnectionJournalEntry.Construct -> CONSTRUCT
        is ConnectionJournalEntry.LadderInstall -> LADDER_INSTALL
        is ConnectionJournalEntry.Submit -> SUBMIT
    }

    fun metadata(entry: ConnectionJournalEntry): Map<String, Any?> = buildMap {
        put("journalSeq", entry.journalSeq)
        when (entry) {
            is ConnectionJournalEntry.Construct -> {
                put("nowMs", entry.nowMs)
                put("graceMs", entry.graceMs)
                put("stabilityWindowMs", entry.stabilityWindowMs)
                put("episodeBudgetMs", entry.episodeBudgetMs)
                put("baseLadderMs", entry.baseLadderMs.csv())
                put("jitteredLadderMs", entry.jitteredLadderMs.csv())
            }

            is ConnectionJournalEntry.LadderInstall -> {
                put("baseLadderMs", entry.baseLadderMs.csv())
                put("jitteredLadderMs", entry.jitteredLadderMs.csv())
            }

            is ConnectionJournalEntry.Submit -> {
                put("nowMs", entry.nowMs)
                put("isWarm", entry.isWarm)
                putEvent(entry.event)
                putState("pre", entry.preState)
                putState("post", entry.postState)
                put("reconnectAttempt", entry.internals.reconnectAttempt)
                put("episodeStartMs", entry.internals.episodeStartMs)
                put("liveSinceMs", entry.internals.liveSinceMs)
                put("graceDeadlineMs", entry.internals.graceDeadlineMs)
            }
        }
    }

    private fun MutableMap<String, Any?>.putEvent(event: ConnectionEvent) {
        when (event) {
            is ConnectionEvent.Enter -> {
                put("event", "enter")
                put("eventHostFingerprint", event.host.value)
                put("eventSessionFingerprint", event.targetId.value)
            }
            is ConnectionEvent.Switch -> {
                put("event", "switch")
                put("eventSessionFingerprint", event.targetId.value)
            }
            ConnectionEvent.Foreground -> put("event", "foreground")
            ConnectionEvent.Background -> put("event", "background")
            is ConnectionEvent.TransportDropped -> {
                put("event", "transport_dropped")
                when (val cause = event.cause) {
                    is DropCause.SelfInflicted -> {
                        put("cause", "self_inflicted")
                        put("causeReason", cause.reason)
                    }
                    is DropCause.RemoteFailure -> {
                        put("cause", "remote_failure")
                        put("causeReason", cause.reason)
                    }
                    DropCause.KeepaliveDead -> put("cause", "keepalive_dead")
                    DropCause.Unknown -> put("cause", "unknown")
                }
            }
            ConnectionEvent.TransportLive -> put("event", "transport_live")
            is ConnectionEvent.NetworkChanged -> {
                put("event", "network_changed")
                put("validatedHandoff", event.validatedHandoff)
            }
            ConnectionEvent.NetworkLost -> put("event", "network_lost")
            ConnectionEvent.NetworkRestored -> put("event", "network_restored")
            is ConnectionEvent.TargetGone -> {
                put("event", "target_gone")
                put("eventSessionFingerprint", event.targetId.value)
            }
            is ConnectionEvent.SeedLanded -> {
                put("event", "seed_landed")
                put("eventSessionFingerprint", event.targetId.value)
                put("paneId", event.paneId)
            }
            ConnectionEvent.ReconnectLadderEntered -> put("event", "reconnect_ladder_entered")
            ConnectionEvent.ReconnectFailed -> put("event", "reconnect_failed")
            ConnectionEvent.ReconnectGaveUp -> put("event", "reconnect_gave_up")
        }
    }

    private fun MutableMap<String, Any?>.putState(prefix: String, state: ConnectionState) {
        put("${prefix}State", state.token())
        state.hostOrNull()?.let { put("${prefix}HostFingerprint", it.value) }
        state.targetIdOrNull()?.let { put("${prefix}SessionFingerprint", it.value) }
        when (state) {
            is ConnectionState.Backgrounded -> put("${prefix}SinceMs", state.sinceMs)
            is ConnectionState.NetworkLossSuspended -> put("${prefix}SinceMs", state.sinceMs)
            is ConnectionState.Reconnecting -> {
                put("${prefix}Attempt", state.attempt)
                put("${prefix}MaxAttempts", state.maxAttempts)
                put("${prefix}RetryDelayMs", state.retryDelayMs)
            }
            else -> Unit
        }
    }

    private fun ConnectionState.token(): String = when (this) {
        ConnectionState.Idle -> "idle"
        is ConnectionState.Connecting -> "connecting"
        is ConnectionState.Attaching -> "attaching"
        is ConnectionState.Live -> "live"
        is ConnectionState.Backgrounded -> "backgrounded"
        is ConnectionState.NetworkLossSuspended -> "network_loss_suspended"
        is ConnectionState.Reattaching -> "reattaching"
        is ConnectionState.Reconnecting -> "reconnecting"
        is ConnectionState.Gone -> "gone"
        is ConnectionState.Unreachable -> "unreachable"
    }

    private fun List<Long>.csv(): String = joinToString(",")
}

internal fun ConnectionEvent.journalSafe(): ConnectionEvent = when (this) {
    is ConnectionEvent.Enter -> ConnectionEvent.Enter(host.journalSafe(), targetId.journalSafe())
    is ConnectionEvent.Switch -> ConnectionEvent.Switch(targetId.journalSafe())
    ConnectionEvent.Foreground -> this
    ConnectionEvent.Background -> this
    is ConnectionEvent.TransportDropped -> ConnectionEvent.TransportDropped(cause.journalSafe())
    ConnectionEvent.TransportLive -> this
    is ConnectionEvent.NetworkChanged -> this
    ConnectionEvent.NetworkLost -> this
    ConnectionEvent.NetworkRestored -> this
    is ConnectionEvent.TargetGone -> ConnectionEvent.TargetGone(targetId.journalSafe())
    is ConnectionEvent.SeedLanded ->
        ConnectionEvent.SeedLanded(targetId.journalSafe(), paneId.journalSafeToken())
    ConnectionEvent.ReconnectLadderEntered -> this
    ConnectionEvent.ReconnectFailed -> this
    ConnectionEvent.ReconnectGaveUp -> this
}

internal fun ConnectionState.journalSafe(): ConnectionState = when (this) {
    ConnectionState.Idle -> this
    is ConnectionState.Connecting -> copy(host = host.journalSafe(), targetId = targetId.journalSafe())
    is ConnectionState.Attaching -> copy(host = host.journalSafe(), targetId = targetId.journalSafe())
    is ConnectionState.Live -> copy(host = host.journalSafe(), targetId = targetId.journalSafe())
    is ConnectionState.Backgrounded -> copy(host = host.journalSafe(), targetId = targetId.journalSafe())
    is ConnectionState.NetworkLossSuspended -> copy(host = host.journalSafe(), targetId = targetId.journalSafe())
    is ConnectionState.Reattaching -> copy(host = host.journalSafe(), targetId = targetId.journalSafe())
    is ConnectionState.Reconnecting -> copy(host = host.journalSafe(), targetId = targetId.journalSafe())
    is ConnectionState.Gone -> copy(host = host.journalSafe(), targetId = targetId.journalSafe())
    is ConnectionState.Unreachable -> copy(host = host.journalSafe(), targetId = targetId.journalSafe())
}

private fun HostKey.journalSafe(): HostKey = HostKey(ConnectionJournalPrivacy.fingerprint(value))
private fun SessionId.journalSafe(): SessionId = SessionId(ConnectionJournalPrivacy.fingerprint(value))

private fun DropCause.journalSafe(): DropCause = when (this) {
    is DropCause.SelfInflicted -> DropCause.SelfInflicted(reason.journalSafeReason())
    is DropCause.RemoteFailure -> DropCause.RemoteFailure(reason.journalSafeReason())
    DropCause.KeepaliveDead -> this
    DropCause.Unknown -> this
}

private fun String.journalSafeReason(): String {
    val normalized = trim().lowercase()
    if (normalized.matches(Regex("[a-z][a-z0-9_:-]{0,79}"))) return normalized
    return ConnectionJournalPrivacy.fingerprint(this)
}

private fun String.journalSafeToken(): String {
    val normalized = trim()
    if (normalized.matches(Regex("[%a-zA-Z0-9_.:-]{1,80}"))) return normalized
    return ConnectionJournalPrivacy.fingerprint(this)
}

internal object ConnectionJournalPrivacy {
    private val alreadyFingerprint = Regex("sha256:[0-9a-f]{12}")

    fun fingerprint(value: String): String {
        val text = value.trim()
        if (alreadyFingerprint.matches(text.lowercase())) return text.lowercase()
        if (text.isBlank()) return "sha256:empty"
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(text.lowercase().toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }
        return "sha256:${digest.take(12)}"
    }
}
