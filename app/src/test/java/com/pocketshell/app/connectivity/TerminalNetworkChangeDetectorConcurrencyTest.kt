package com.pocketshell.app.connectivity

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Test

/**
 * Issue #995 (#935 H2) — `TerminalNetworkChangeDetector.update` is driven from
 * concurrent `ConnectivityManager` binder callbacks, so two callbacks can be in
 * `update` at once. Before the fix the identity state (`current` /
 * `lastValidated` / `sequence`) was mutated with NO synchronization — a true
 * cross-thread race that can (a) suppress a real handoff (no reconnect → dead
 * socket) or (b) tear/duplicate the change (a torn `sequence += 1` → a
 * duplicate/torn emission → spurious reconnect).
 *
 * These tests drive `update(...)` from many threads with barrier-synchronized
 * bursts and assert invariants of the EMITTED stream that the unsynchronized
 * code violates. They are RED on the racy base and GREEN once the whole
 * compare-and-mutate is one critical section.
 *
 * Determinism note: a concurrency race is inherently probabilistic, so each
 * test runs MANY iterations with `CountDownLatch` barriers that release all
 * worker threads at the same instant (maximising the contention window). The
 * asserted property is an INVARIANT of a correct serialization, not a fixed
 * count — so a correct implementation passes every iteration deterministically,
 * while the racy one reliably trips at least one iteration across the burst.
 */
class TerminalNetworkChangeDetectorConcurrencyTest {

    /**
     * Core race: concurrent callbacks must NEVER tear or duplicate the change
     * identity. Every emitted change must carry a UNIQUE, contiguous sequence
     * (1..count, no duplicates, no gaps). A torn `sequence += 1L` (two threads
     * read the same value and both write N+1) produces duplicate sequence
     * numbers and/or lost increments — exactly the "torn/duplicate change →
     * spurious reconnect" symptom in the issue.
     */
    @Test
    fun `concurrent updates never tear or duplicate the change sequence`() {
        val threads = 8
        val iterations = 400

        repeat(iterations) { iteration ->
            // Each iteration starts from a fresh, known identity. Threads race
            // to flip the validated network to many DISTINCT new identities so
            // most updates are real handoffs that bump `sequence`.
            //
            // Issue #1042: a same-transport-set {WIFI}/{CELLULAR} reassoc to a new
            // handle is now SUPPRESSED (not a handoff), so the targets carry a
            // VPN-bearing transport set — a distinct handle on a non-benign set is
            // still a genuine identity handoff, keeping this race test exercising the
            // emission path it is about (the #995 sequence-tearing race), not the
            // identity policy (covered by TerminalNetworkChangeDetectorTest).
            val detector = TerminalNetworkChangeDetector(
                initial = TerminalNetworkSnapshot.Validated("net-0", setOf("CELLULAR", "VPN")),
            )
            val emitted = ConcurrentLinkedQueue<TerminalNetworkChange>()
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threads)
            val pool = Executors.newFixedThreadPool(threads)

            for (t in 0 until threads) {
                pool.execute {
                    startLatch.await()
                    // Each thread targets a DISTINCT handle so every update it
                    // wins is a genuine transport/identity handoff.
                    val target = TerminalNetworkSnapshot.Validated(
                        networkHandle = "net-$iteration-$t",
                        transports = setOf("CELLULAR", "VPN"),
                    )
                    val change = detector.update(target, "race-$t")
                    if (change != null) emitted.add(change)
                    doneLatch.countDown()
                }
            }

            startLatch.countDown()
            assertTrue(
                "workers did not finish in time (iteration=$iteration)",
                doneLatch.await(10, TimeUnit.SECONDS),
            )
            pool.shutdownNow()

            val sequences = emitted.map { it.sequence }
            val distinct = sequences.toSet()
            assertEquals(
                "duplicate/torn sequence numbers across concurrent updates " +
                    "(iteration=$iteration, sequences=${sequences.sorted()})",
                sequences.size,
                distinct.size,
            )
            // The emitted sequences must be exactly 1..count with no gaps — a
            // dropped increment leaves a hole, a torn increment leaves a dupe.
            assertEquals(
                "emitted sequences are not the contiguous 1..count range " +
                    "(iteration=$iteration, sequences=${sequences.sorted()})",
                (1L..sequences.size.toLong()).toList(),
                sequences.sorted(),
            )
        }
    }

    /**
     * No REAL handoff may be LOST under concurrency. Many threads each apply a
     * DISTINCT new validated identity. Every one is a genuine transport/identity
     * transition, so a correct serialization emits EXACTLY one change per thread
     * AND the emitted `sequence` values are the contiguous range 1..threads (one
     * monotonic bump per surviving handoff). The racy code drops/torns those:
     * two threads read the same stale `sequence` and both write the same N+1, so
     * a real handoff's slot is overwritten — the emitted sequences contain
     * duplicates and/or holes (fewer distinct sequences than emissions, or a
     * max-sequence that does not equal the emission count). A lost slot is the
     * "suppress a real handoff → no reconnect → dead socket" symptom.
     *
     * Class-cover: this is the real-handoff case (every target is a distinct
     * VPN-bearing identity off a VPN-bearing start, all genuine transitions —
     * #1042: a VPN-bearing set keeps the strict handle check, so distinct handles
     * stay distinct identities), driven under contention.
     */
    @Test
    fun `no concurrent real handoff is lost or torn`() {
        val threads = 8
        val iterations = 400

        repeat(iterations) { iteration ->
            // Issue #1042: VPN-bearing identities so a distinct handle is still a
            // genuine handoff (a bare same-transport {CELLULAR} reassoc is now
            // suppressed — that is the identity policy, covered elsewhere; this test
            // is about the #995 sequence-tearing race on the emission path).
            val detector = TerminalNetworkChangeDetector(
                initial = TerminalNetworkSnapshot.Validated("net-init", setOf("CELLULAR", "VPN")),
            )
            val emitted = ConcurrentLinkedQueue<TerminalNetworkChange>()
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(threads)
            val pool = Executors.newFixedThreadPool(threads)

            for (t in 0 until threads) {
                pool.execute {
                    startLatch.await()
                    // A DISTINCT identity per thread — every update is a real
                    // handoff that MUST surface exactly once.
                    val target = TerminalNetworkSnapshot.Validated(
                        networkHandle = "net-$iteration-$t",
                        transports = setOf("CELLULAR", "VPN"),
                    )
                    val change = detector.update(target, "handoff-$t")
                    if (change != null) emitted.add(change)
                    doneLatch.countDown()
                }
            }

            startLatch.countDown()
            assertTrue(
                "workers did not finish in time (iteration=$iteration)",
                doneLatch.await(10, TimeUnit.SECONDS),
            )
            pool.shutdownNow()

            // Every distinct handoff surfaces exactly once: count == threads, and
            // the emitted targets are exactly the distinct per-thread identities.
            val emittedTargets = emitted.mapNotNull { it.current.networkHandle }.toSet()
            val expectedTargets = (0 until threads).map { "net-$iteration-$it" }.toSet()
            assertEquals(
                "emitted handoffs do not cover every distinct target identity " +
                    "(iteration=$iteration, emitted=${emitted.size})",
                expectedTargets,
                emittedTargets,
            )
            // The sequence stream of the SURVIVING handoffs must be a unique,
            // contiguous 1..count range — a torn `sequence += 1` leaves dupes /
            // holes even when every distinct identity happens to surface once.
            val sequences = emitted.map { it.sequence }
            assertEquals(
                "a real handoff's sequence slot was torn/lost under concurrency " +
                    "(iteration=$iteration, sequences=${sequences.sorted()})",
                (1L..emitted.size.toLong()).toList(),
                sequences.sorted(),
            )
        }
    }

    /**
     * A transient duplicate callback (the SAME validated network reported twice
     * concurrently) must NOT be emitted as a spurious reconnect. Two threads
     * push the SAME new network identity at once; at most ONE emission may
     * result, and only because the identity genuinely changed from the initial
     * — never two emissions for the one transition.
     */
    @Test
    fun `concurrent duplicate of the same new network is not emitted as a spurious second reconnect`() {
        val iterations = 600

        repeat(iterations) { iteration ->
            val detector = TerminalNetworkChangeDetector(
                initial = TerminalNetworkSnapshot.Validated("wifi-$iteration", setOf("WIFI")),
            )
            val newNet = TerminalNetworkSnapshot.Validated("cell-$iteration", setOf("CELLULAR"))

            val emitted = ConcurrentLinkedQueue<TerminalNetworkChange>()
            val startLatch = CountDownLatch(1)
            val doneLatch = CountDownLatch(2)
            val pool = Executors.newFixedThreadPool(2)

            // Both threads report the IDENTICAL new validated network — a
            // duplicate callback delivery. The transition (wifi→cell) happens
            // once, so the stream must contain at most ONE change for it.
            repeat(2) {
                pool.execute {
                    startLatch.await()
                    val change = detector.update(newNet, "duplicate")
                    if (change != null) emitted.add(change)
                    doneLatch.countDown()
                }
            }

            startLatch.countDown()
            assertTrue(
                "workers did not finish in time (iteration=$iteration)",
                doneLatch.await(10, TimeUnit.SECONDS),
            )
            pool.shutdownNow()

            assertTrue(
                "a duplicate same-network callback was emitted as a second " +
                    "spurious reconnect (iteration=$iteration, count=${emitted.size})",
                emitted.size <= 1,
            )
            // When the single emission happens it must be the real transition,
            // with sequence 1 — never a torn/duplicate sequence.
            emitted.firstOrNull()?.let { change ->
                assertEquals(
                    "the single emission must be the real wifi→cell transition " +
                        "(iteration=$iteration)",
                    newNet,
                    change.current,
                )
                assertEquals(
                    "the single emission must carry sequence 1 (iteration=$iteration)",
                    1L,
                    change.sequence,
                )
            }
            assertFalse(
                "no emission for a genuine identity change (iteration=$iteration)",
                emitted.isEmpty(),
            )
        }
    }
}
