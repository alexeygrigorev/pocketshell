package com.pocketshell.core.connection

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.json.JSONObject
import kotlin.math.floor
import kotlin.random.Random

/** Pure-JVM field-journal replay harness for issue #1709. */
internal object ConnectionJournalReplay {
    fun replay(lines: List<String>): ReplayReport {
        val expected = lines.filter { it.isNotBlank() }.map(::decodeEnvelope)
        require(expected.isNotEmpty()) { "connection journal is empty" }
        require(expected.first() is ConnectionJournalEntry.Construct) {
            "connection journal must start with construct"
        }
        var expectedSubmitSeq = 1L
        expected.filterIsInstance<ConnectionJournalEntry.Submit>().forEach { submit ->
            require(submit.journalSeq == expectedSubmitSeq) {
                "journalSeq gap: expected=$expectedSubmitSeq actual=${submit.journalSeq}"
            }
            expectedSubmitSeq++
        }

        val construct = expected.first() as ConnectionJournalEntry.Construct
        val clock = ReplayClock(construct.nowMs)
        val transport = ReplayTransport()
        val random = ScriptedLadderRandom(construct.baseLadderMs, construct.jitteredLadderMs)
        val actualJournal = RecordingConnectionJournal()
        val controller = ConnectionController(
            clock = clock,
            transport = transport,
            reconnectLadderMs = construct.baseLadderMs,
            graceMs = construct.graceMs,
            stabilityWindowMs = construct.stabilityWindowMs,
            episodeBudgetMs = construct.episodeBudgetMs,
            confinementAssertionsEnabled = false,
            random = random,
            journal = actualJournal,
        )
        require(actualJournal.entries.single() == construct) {
            "construct mismatch: expected=$construct actual=${actualJournal.entries.single()}"
        }

        val postStates = mutableListOf<ConnectionState>()
        val internals = mutableListOf<ConnectionJournalInternals>()
        expected.drop(1).forEach { entry ->
            val beforeCount = actualJournal.entries.size
            when (entry) {
                is ConnectionJournalEntry.Construct ->
                    error("duplicate construct at journal index $beforeCount")
                is ConnectionJournalEntry.LadderInstall -> {
                    random.install(entry.baseLadderMs, entry.jitteredLadderMs)
                    controller.setReconnectLadder(entry.baseLadderMs)
                }
                is ConnectionJournalEntry.Submit -> {
                    clock.value = entry.nowMs
                    transport.prepare(entry.isWarm)
                    controller.submit(entry.event)
                    transport.assertConsultationMatched(entry.journalSeq)
                }
            }
            val actual = actualJournal.entries.drop(beforeCount)
            require(actual.size == 1) {
                "journal entry ${entry.journalSeq} emitted ${actual.size} records instead of 1"
            }
            require(actual.single() == entry) {
                "journal divergence at journalSeq=${entry.journalSeq}: " +
                    "expected=$entry actual=${actual.single()} " +
                    "construct=$construct " +
                    "recentExpected=${expected.takeWhile { it !== entry }.takeLast(3)} " +
                    "recentActual=${actualJournal.entries.dropLast(1).takeLast(3)}"
            }
            if (entry is ConnectionJournalEntry.Submit) {
                postStates += entry.postState
                internals += entry.internals
            }
        }
        random.assertDrained()
        return ReplayReport(
            submitCount = postStates.size,
            postStateTrail = postStates,
            internalsTrail = internals,
        )
    }

    private fun decodeEnvelope(line: String): ConnectionJournalEntry {
        val root = JSONObject(line)
        require(root.getString("category") == ConnectionJournalSchema.CATEGORY) {
            "not a ${ConnectionJournalSchema.CATEGORY} entry"
        }
        val metadata = root.getJSONObject("metadata")
        val journalSeq = metadata.getLong("journalSeq")
        return when (root.getString("name")) {
            ConnectionJournalSchema.CONSTRUCT -> ConnectionJournalEntry.Construct(
                journalSeq = journalSeq,
                nowMs = metadata.getLong("nowMs"),
                graceMs = metadata.getLong("graceMs"),
                stabilityWindowMs = metadata.getLong("stabilityWindowMs"),
                episodeBudgetMs = metadata.getLong("episodeBudgetMs"),
                baseLadderMs = metadata.getString("baseLadderMs").longCsv(),
                jitteredLadderMs = metadata.getString("jitteredLadderMs").longCsv(),
            )
            ConnectionJournalSchema.LADDER_INSTALL -> ConnectionJournalEntry.LadderInstall(
                journalSeq = journalSeq,
                baseLadderMs = metadata.getString("baseLadderMs").longCsv(),
                jitteredLadderMs = metadata.getString("jitteredLadderMs").longCsv(),
            )
            ConnectionJournalSchema.SUBMIT -> ConnectionJournalEntry.Submit(
                journalSeq = journalSeq,
                nowMs = metadata.getLong("nowMs"),
                event = metadata.event(),
                preState = metadata.state("pre"),
                postState = metadata.state("post"),
                isWarm = metadata.nullableBoolean("isWarm"),
                internals = ConnectionJournalInternals(
                    reconnectAttempt = metadata.getInt("reconnectAttempt"),
                    episodeStartMs = metadata.nullableLong("episodeStartMs"),
                    liveSinceMs = metadata.nullableLong("liveSinceMs"),
                    graceDeadlineMs = metadata.nullableLong("graceDeadlineMs"),
                ),
            )
            else -> error("unknown connection journal entry '${root.getString("name")}'")
        }
    }

    private fun JSONObject.event(): ConnectionEvent = when (getString("event")) {
        "enter" -> ConnectionEvent.Enter(
            HostKey(getString("eventHostFingerprint")),
            SessionId(getString("eventSessionFingerprint")),
        )
        "switch" -> ConnectionEvent.Switch(SessionId(getString("eventSessionFingerprint")))
        "foreground" -> ConnectionEvent.Foreground
        "background" -> ConnectionEvent.Background
        "transport_dropped" -> ConnectionEvent.TransportDropped(
            when (getString("cause")) {
                "self_inflicted" -> DropCause.SelfInflicted(getString("causeReason"))
                "remote_failure" -> DropCause.RemoteFailure(getString("causeReason"))
                "keepalive_dead" -> DropCause.KeepaliveDead
                "unknown" -> DropCause.Unknown
                else -> error("unknown drop cause '${getString("cause")}'")
            },
        )
        "transport_live" -> ConnectionEvent.TransportLive
        "network_changed" -> ConnectionEvent.NetworkChanged(getBoolean("validatedHandoff"))
        "network_lost" -> ConnectionEvent.NetworkLost
        "network_restored" -> ConnectionEvent.NetworkRestored
        "target_gone" -> ConnectionEvent.TargetGone(SessionId(getString("eventSessionFingerprint")))
        "seed_landed" -> ConnectionEvent.SeedLanded(
            SessionId(getString("eventSessionFingerprint")),
            getString("paneId"),
        )
        "reconnect_ladder_entered" -> ConnectionEvent.ReconnectLadderEntered
        "reconnect_failed" -> ConnectionEvent.ReconnectFailed
        "reconnect_gave_up" -> ConnectionEvent.ReconnectGaveUp
        else -> error("unknown connection event '${getString("event")}'")
    }

    private fun JSONObject.state(prefix: String): ConnectionState {
        val host by lazy { HostKey(getString("${prefix}HostFingerprint")) }
        val session by lazy { SessionId(getString("${prefix}SessionFingerprint")) }
        return when (getString("${prefix}State")) {
            "idle" -> ConnectionState.Idle
            "connecting" -> ConnectionState.Connecting(host, session)
            "attaching" -> ConnectionState.Attaching(host, session)
            "live" -> ConnectionState.Live(host, session)
            "backgrounded" -> ConnectionState.Backgrounded(host, session, getLong("${prefix}SinceMs"))
            "network_loss_suspended" ->
                ConnectionState.NetworkLossSuspended(host, session, getLong("${prefix}SinceMs"))
            "reattaching" -> ConnectionState.Reattaching(host, session)
            "reconnecting" -> ConnectionState.Reconnecting(
                host = host,
                targetId = session,
                attempt = getInt("${prefix}Attempt"),
                maxAttempts = getInt("${prefix}MaxAttempts"),
                retryDelayMs = getLong("${prefix}RetryDelayMs"),
            )
            "gone" -> ConnectionState.Gone(host, session)
            "unreachable" -> ConnectionState.Unreachable(host, session)
            else -> error("unknown connection state '${getString("${prefix}State")}'")
        }
    }

    private fun JSONObject.nullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)

    private fun JSONObject.nullableBoolean(key: String): Boolean? =
        if (!has(key) || isNull(key)) null else getBoolean(key)

    private fun String.longCsv(): List<Long> =
        if (isBlank()) emptyList() else split(",").map(String::toLong)
}

internal data class ReplayReport(
    val submitCount: Int,
    val postStateTrail: List<ConnectionState>,
    val internalsTrail: List<ConnectionJournalInternals>,
)

internal class RecordingConnectionJournal : ConnectionJournalPort {
    val entries = mutableListOf<ConnectionJournalEntry>()
    override fun record(entry: ConnectionJournalEntry) {
        entries += entry
    }
}

private class ReplayClock(var value: Long) : Clock {
    override fun nowMs(): Long = value
}

private class ReplayTransport : TransportPort {
    private var expected: Boolean? = null
    private var consulted: Boolean = false
    override val transportEvents: Flow<TransportUpDown> = emptyFlow()

    fun prepare(expectedWarm: Boolean?) {
        expected = expectedWarm
        consulted = false
    }

    override fun isWarm(host: HostKey): Boolean {
        check(expected != null) { "transport.isWarm consulted but journal recorded isWarm=null" }
        check(!consulted) { "transport.isWarm consulted more than once in one submit" }
        consulted = true
        return expected!!
    }

    fun assertConsultationMatched(journalSeq: Long) {
        check((expected != null) == consulted) {
            "isWarm consultation mismatch at journalSeq=$journalSeq: expected=$expected consulted=$consulted"
        }
    }
}

/**
 * Reconstructs a recorded post-jitter ladder without a production seam.
 *
 * Kotlin Random's `nextDouble()` consumes 53 bits (26 high + 27 low). For each
 * non-zero rung we choose a representable draw whose `(base + offset).toLong()`
 * is the recorded rung, then script those exact bit chunks.
 */
private class ScriptedLadderRandom(
    base: List<Long>,
    jittered: List<Long>,
) : Random() {
    private val bits = ArrayDeque<Pair<Int, Int>>()

    init {
        install(base, jittered)
    }

    fun install(base: List<Long>, jittered: List<Long>) {
        check(bits.isEmpty()) { "scripted Random still has unused draws from the prior ladder" }
        require(base.size == jittered.size) { "base/jittered ladder size mismatch" }
        base.zip(jittered).forEach { (baseMs, jitteredMs) ->
            if (baseMs <= 0L) {
                require(jitteredMs == 0L) { "zero ladder rung changed to $jitteredMs" }
            } else {
                val spread = baseMs * ConnectionController.RETRY_JITTER_FRACTION
                val randomLower = baseMs - spread
                val randomUpper = baseMs + spread
                // `toLong()` truncates a positive draw in [v, v + 1) to v.
                // For v=1, coerceAtLeast also maps every draw below 2 to 1.
                // Intersect that output bucket with the actual jitter band:
                // a fixed `v + 0.25` is invalid when a reachable edge bucket
                // is narrower than 0.25 (for example base=101, resolved=121).
                val outputLower =
                    if (jitteredMs == 1L) Double.NEGATIVE_INFINITY else jitteredMs.toDouble()
                val outputUpper = jitteredMs.toDouble() + 1.0
                val feasibleLower = maxOf(randomLower, outputLower)
                val feasibleUpper = minOf(randomUpper, outputUpper)
                require(feasibleLower < feasibleUpper) {
                    "jittered rung $jitteredMs is outside replayable band for base $baseMs"
                }
                val desired = feasibleLower + (feasibleUpper - feasibleLower) / 2.0
                val probability = (desired - randomLower) / (randomUpper - randomLower)
                require(probability >= 0.0 && probability < 1.0) {
                    "jittered rung $jitteredMs is outside replayable band for base $baseMs"
                }
                val sample = floor(probability * TWO_POW_53).toLong().coerceIn(0L, MAX_SAMPLE)
                bits += 26 to (sample ushr 27).toInt()
                bits += 27 to (sample and LOW_27_MASK).toInt()
            }
        }
    }

    override fun nextBits(bitCount: Int): Int {
        val (expectedBits, value) = bits.removeFirstOrNull()
            ?: error("controller requested more Random bits than the journaled ladder supplies")
        check(bitCount == expectedBits) {
            "Random draw shape changed: expected nextBits($expectedBits), got nextBits($bitCount)"
        }
        return value
    }

    fun assertDrained() {
        check(bits.isEmpty()) { "controller consumed fewer Random draws than the journaled ladders supply" }
    }

    private companion object {
        const val TWO_POW_53: Double = 9_007_199_254_740_992.0
        const val MAX_SAMPLE: Long = 9_007_199_254_740_991L
        const val LOW_27_MASK: Long = (1L shl 27) - 1L
    }
}
