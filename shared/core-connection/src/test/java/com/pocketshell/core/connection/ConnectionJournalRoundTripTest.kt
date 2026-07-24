package com.pocketshell.core.connection

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Issue #1709: the submit-boundary field journal is useful only if it can
 * reconstruct the reducer's decisions offline. This is the load-bearing
 * round-trip property: randomized controller runs become diagnostics JSONL,
 * then a fresh pure-JVM controller replays that JSONL exactly.
 */
class ConnectionJournalRoundTripTest {

    @Test
    fun `randomized journal round trip reproduces every state and episode checkpoint`() {
        repeat(PROPERTY_SEEDS) { seed ->
            val random = Random(seed)
            val clock = CountingClock()
            val transport = ScriptableWarmTransport()
            val journal = RecordingConnectionJournal()
            val controller = ConnectionController(
                clock = clock,
                transport = transport,
                reconnectLadderMs = randomizedLadder(random),
                graceMs = 800L,
                stabilityWindowMs = 350L,
                episodeBudgetMs = 2_500L,
                random = Random(seed * 37 + 11),
                journal = journal,
            )
            val callsAfterConstruct = clock.calls
            val events = exhaustivePrefix(seed) + List(RANDOM_EVENTS_PER_SEED) {
                randomizedEvent(random, seed)
            }

            events.forEachIndexed { index, event ->
                clock.advanceBy(random.nextLong(0L, 500L))
                transport.warm = random.nextBoolean()
                if (index > 0 && index % 17 == 0) {
                    controller.setReconnectLadder(randomizedLadder(random))
                }
                controller.submit(event)
            }

            assertEquals(
                "seed=$seed: exactly one physical clock read per submit",
                callsAfterConstruct + events.size,
                clock.calls,
            )

            val lines = journal.entries.mapIndexed { envelopeSequence, entry ->
                encodeEnvelope(envelopeSequence.toLong() + 1L, entry)
            }
            val report = ConnectionJournalReplay.replay(lines)
            val expectedSubmits = journal.entries.filterIsInstance<ConnectionJournalEntry.Submit>()

            assertEquals("seed=$seed: every submit replayed", expectedSubmits.size, report.submitCount)
            assertEquals(
                "seed=$seed: post-state trail",
                expectedSubmits.map { it.postState },
                report.postStateTrail,
            )
            assertEquals(
                "seed=$seed: hidden episode checkpoint trail",
                expectedSubmits.map { it.internals },
                report.internalsTrail,
            )

            val document = lines.joinToString("\n")
            assertFalse("raw host identity leaked for seed=$seed", document.contains("alexey@field-host"))
            assertFalse("raw path-derived session leaked for seed=$seed", document.contains("/srv/private"))
            assertTrue("journal must carry equality-preserving sha256 identities", document.contains("sha256:"))
        }
    }

    @Test
    fun `replay hard fails when controller local submit sequence has a gap`() {
        val clock = CountingClock()
        val transport = ScriptableWarmTransport()
        val journal = RecordingConnectionJournal()
        val controller = ConnectionController(clock, transport, random = Random(7), journal = journal)
        controller.submit(ConnectionEvent.Enter(HOST_A, SESSION_A))
        controller.submit(ConnectionEvent.Background)
        controller.submit(ConnectionEvent.Foreground)

        val lines = journal.entries
            .filterNot { it is ConnectionJournalEntry.Submit && it.journalSeq == 2L }
            .mapIndexed { index, entry -> encodeEnvelope(index.toLong() + 1L, entry) }

        val thrown = assertThrows(IllegalArgumentException::class.java) {
            ConnectionJournalReplay.replay(lines)
        }
        assertTrue(thrown.message.orEmpty().contains("journalSeq gap"))
        assertTrue(thrown.message.orEmpty().contains("expected=2"))
        assertTrue(thrown.message.orEmpty().contains("actual=3"))
    }

    @Test
    fun `scripted random replays narrow integer buckets at a jitter band edge`() {
        val journal = RecordingConnectionJournal()
        ConnectionController(
            clock = CountingClock(),
            transport = ScriptableWarmTransport(),
            reconnectLadderMs = listOf(0L, 101L),
            random = object : Random() {
                override fun nextBits(bitCount: Int): Int = (1 shl bitCount) - 1
            },
            journal = journal,
        )
        val construct = journal.entries.single() as ConnectionJournalEntry.Construct
        assertEquals(
            "101ms + just under 20% lands in the narrow [121.0, 121.2) bucket",
            listOf(0L, 121L),
            construct.jitteredLadderMs,
        )

        val report = ConnectionJournalReplay.replay(
            journal.entries.mapIndexed { index, entry ->
                encodeEnvelope(index.toLong() + 1L, entry)
            },
        )
        assertEquals(0, report.submitCount)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `same-thread reentrant submits journal outer then inner and replay exactly`() = runTest {
        val clock = CountingClock()
        val journal = RecordingConnectionJournal()
        val controller = ConnectionController(
            clock = clock,
            transport = ScriptableWarmTransport(),
            random = Random(41),
            journal = journal,
        )
        var submittedInner = false
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.drop(1).collect { state ->
                if (state is ConnectionState.Backgrounded && !submittedInner) {
                    submittedInner = true
                    controller.submit(ConnectionEvent.TargetGone(SESSION_A))
                }
            }
        }

        controller.submit(ConnectionEvent.Enter(HOST_A, SESSION_A))
        controller.submit(ConnectionEvent.Background)
        collector.cancel()

        val submits = journal.entries.filterIsInstance<ConnectionJournalEntry.Submit>()
        assertEquals(listOf(1L, 2L, 3L), submits.map { it.journalSeq })
        assertTrue(submits[1].event is ConnectionEvent.Background)
        assertTrue(submits[1].postState is ConnectionState.Backgrounded)
        assertTrue(submits[2].event is ConnectionEvent.TargetGone)
        assertTrue(submits[2].preState is ConnectionState.Backgrounded)
        assertTrue(submits[2].postState is ConnectionState.Gone)
        assertEquals("construct plus exactly one read per submit", 4, clock.calls)

        val report = ConnectionJournalReplay.replay(
            journal.entries.mapIndexed { index, entry ->
                encodeEnvelope(index.toLong() + 1L, entry)
            },
        )
        assertEquals(3, report.submitCount)
        assertEquals(submits.map { it.postState }, report.postStateTrail)
        assertEquals(submits.map { it.internals }, report.internalsTrail)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `ladder install journals before a submit reentered from its restamp publication`() = runTest {
        val clock = CountingClock()
        val transport = ScriptableWarmTransport()
        val journal = RecordingConnectionJournal()
        val controller = ConnectionController(
            clock = clock,
            transport = transport,
            random = Random(73),
            journal = journal,
        )
        val replacementLadder = listOf(0L, 9_000L, 18_000L, 27_000L)
        var restampArmed = false
        var submittedInner = false
        val collector = launch(UnconfinedTestDispatcher(testScheduler)) {
            controller.state.drop(1).collect { state ->
                if (
                    restampArmed &&
                    !submittedInner &&
                    state is ConnectionState.Reconnecting &&
                    state.maxAttempts == replacementLadder.size
                ) {
                    submittedInner = true
                    controller.submit(ConnectionEvent.ReconnectFailed)
                }
            }
        }

        transport.warm = false
        controller.submit(ConnectionEvent.Enter(HOST_A, SESSION_A))
        transport.warm = true
        controller.submit(ConnectionEvent.TransportLive)
        controller.submit(ConnectionEvent.SeedLanded(SESSION_A, "%0"))
        controller.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("reader_eof")))
        controller.submit(ConnectionEvent.TransportDropped(DropCause.RemoteFailure("reader_eof")))
        assertTrue(controller.state.value is ConnectionState.Reconnecting)

        restampArmed = true
        controller.setReconnectLadder(replacementLadder)
        collector.cancel()
        assertTrue("the restamp observer must synchronously submit", submittedInner)

        val installIndex = journal.entries.indexOfFirst {
            it is ConnectionJournalEntry.LadderInstall && it.baseLadderMs == replacementLadder
        }
        val nestedSubmitIndex = journal.entries.indexOfFirst {
            it is ConnectionJournalEntry.Submit && it.event is ConnectionEvent.ReconnectFailed
        }
        assertTrue("replacement ladder_install must be present", installIndex >= 0)
        assertTrue("nested reconnect-failed submit must be present", nestedSubmitIndex >= 0)
        assertTrue(
            "ladder_install must precede the nested submit that already uses it: " +
                journal.entries.joinToString { it::class.simpleName.orEmpty() },
            installIndex < nestedSubmitIndex,
        )

        val report = ConnectionJournalReplay.replay(
            journal.entries.mapIndexed { index, entry ->
                encodeEnvelope(index.toLong() + 1L, entry)
            },
        )
        val submits = journal.entries.filterIsInstance<ConnectionJournalEntry.Submit>()
        assertEquals(submits.size, report.submitCount)
        assertEquals(submits.map { it.postState }, report.postStateTrail)
        assertEquals(submits.map { it.internals }, report.internalsTrail)
    }

    private fun exhaustivePrefix(seed: Int): List<ConnectionEvent> {
        val session = if (seed % 2 == 0) SESSION_A else SESSION_B
        return listOf(
            ConnectionEvent.Enter(HOST_A, session),
            ConnectionEvent.Switch(SESSION_B),
            ConnectionEvent.Foreground,
            ConnectionEvent.Background,
            ConnectionEvent.TransportDropped(DropCause.SelfInflicted("force_refresh")),
            ConnectionEvent.TransportDropped(DropCause.RemoteFailure("lease_down:reader_eof")),
            ConnectionEvent.TransportDropped(DropCause.KeepaliveDead),
            ConnectionEvent.TransportDropped(DropCause.Unknown),
            ConnectionEvent.TransportLive,
            ConnectionEvent.NetworkChanged(validatedHandoff = false),
            ConnectionEvent.NetworkChanged(validatedHandoff = true),
            ConnectionEvent.NetworkLost,
            ConnectionEvent.NetworkRestored,
            ConnectionEvent.TargetGone(SESSION_A),
            ConnectionEvent.SeedLanded(SESSION_B, "%1"),
            ConnectionEvent.ReconnectLadderEntered,
            ConnectionEvent.ReconnectFailed,
            ConnectionEvent.ReconnectGaveUp,
        )
    }

    private fun randomizedEvent(random: Random, seed: Int): ConnectionEvent =
        when (random.nextInt(18)) {
            0 -> ConnectionEvent.Enter(if (random.nextBoolean()) HOST_A else HOST_B, randomSession(random))
            1 -> ConnectionEvent.Switch(randomSession(random))
            2 -> ConnectionEvent.Foreground
            3 -> ConnectionEvent.Background
            4 -> ConnectionEvent.TransportDropped(DropCause.SelfInflicted("idle_reap"))
            5 -> ConnectionEvent.TransportDropped(DropCause.RemoteFailure("lease_down:reader_eof"))
            6 -> ConnectionEvent.TransportDropped(DropCause.KeepaliveDead)
            7 -> ConnectionEvent.TransportDropped(DropCause.Unknown)
            8 -> ConnectionEvent.TransportLive
            9 -> ConnectionEvent.NetworkChanged(random.nextBoolean())
            10 -> ConnectionEvent.NetworkLost
            11 -> ConnectionEvent.NetworkRestored
            12 -> ConnectionEvent.TargetGone(randomSession(random))
            13 -> ConnectionEvent.SeedLanded(randomSession(random), "%${random.nextInt(4)}")
            14 -> ConnectionEvent.ReconnectLadderEntered
            15 -> ConnectionEvent.ReconnectFailed
            16 -> ConnectionEvent.ReconnectGaveUp
            else -> ConnectionEvent.Enter(HOST_A, SessionId("/srv/private/property-$seed"))
        }

    private fun randomSession(random: Random): SessionId =
        if (random.nextBoolean()) SESSION_A else SESSION_B

    private fun randomizedLadder(random: Random): List<Long> =
        listOf(0L) + List(5) { random.nextLong(100L, 5_000L) }

    private fun encodeEnvelope(sequence: Long, entry: ConnectionJournalEntry): String {
        val metadata = JSONObject()
        ConnectionJournalSchema.metadata(entry).forEach { (key, value) ->
            metadata.put(key, JSONObject.wrap(value))
        }
        return JSONObject()
            .put("sequence", sequence)
            .put("wallClockTime", "2026-07-24T12:00:00Z")
            .put("monotonicTimestampNanos", sequence * 1_000L)
            .put("category", ConnectionJournalSchema.CATEGORY)
            .put("name", ConnectionJournalSchema.name(entry))
            .put("versionName", "0.4.39")
            .put("versionCode", 86)
            .put("metadata", metadata)
            .toString()
    }

    private class CountingClock : Clock {
        private var now: Long = 1_000L
        var calls: Int = 0
            private set

        override fun nowMs(): Long {
            calls++
            return now
        }

        fun advanceBy(deltaMs: Long) {
            now += deltaMs
        }
    }

    private class ScriptableWarmTransport : TransportPort {
        var warm: Boolean = false
        override fun isWarm(host: HostKey): Boolean = warm
        override val transportEvents = kotlinx.coroutines.flow.emptyFlow<TransportUpDown>()
    }

    private companion object {
        const val PROPERTY_SEEDS = 40
        const val RANDOM_EVENTS_PER_SEED = 80
        val HOST_A = HostKey("alexey@field-host.example:22")
        val HOST_B = HostKey("alexey@backup-field-host.example:2202")
        val SESSION_A = SessionId("/srv/private/project-alpha")
        val SESSION_B = SessionId("/srv/private/project-beta")
    }
}
