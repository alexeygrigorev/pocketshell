package com.pocketshell.app.diagnostics

import com.pocketshell.testsupport.drainMainLooperUntil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Issue #2147: [ConnectionLogPartStore.append] must not rescan its directory.
 *
 * ## The defect this reproduces
 *
 * A canonical `scripts/full-jvm-gate.py` run wedged **>60 minutes** in
 * `:app:testReleaseUnitTest` on `ConnectionLogPartStoreTest`'s concurrent
 * rotation case. `jstack` showed **16 coroutines convoyed on
 * `synchronized(lock)`** while the lock holder ran an O(parts) `listFiles()` +
 * `isFile` + regex + sort scan (`partFilesLocked`) — which `append` called on
 * EVERY line, purely to compare the part count against `maxParts`. That is
 * quadratic in the part count, executed under the one lock every writer needs,
 * so the convoy grows with the archive. CI's `Unit tests` job has a 35-minute
 * cap, and a job that times out while every test line reads PASSED is a hang,
 * not an assertion failure (the #882 shape) — it takes `main` red and every
 * filtered pre-merge gate misses it.
 *
 * ## Why the oracle is a scan COUNT and not only a stopwatch
 *
 * The wall-clock deadline below is the symptom-level bound and it HARD-fails
 * (the audited [drainMainLooperUntil] convention, one generous
 * `GENEROUS_SETTLE_DEADLINE_MS` budget — never a nudged local constant). But
 * wall-clock alone is load-dependent: the convoy is catastrophic on a memory-
 * pressured gate box and merely slow on an idle one, so a stopwatch-only proof
 * would be green exactly where developers run it and red only on CI. The scan
 * count is the same defect measured deterministically: it is load-independent,
 * so it reds on ANY box, and it is measured on the REAL filesystem rather than
 * through a production seam — [java.io.File] is open and `listFiles()` is
 * virtual, so [ScanCountingDirectory] observes precisely the scans the store
 * actually performs, with no test-only hook inside the store.
 *
 * Both assertions are load-bearing: the count pins the mechanism, the deadline
 * pins the symptom.
 */
class Issue2147AppendConvoyTest {
    @get:Rule
    val tmp = TemporaryFolder()

    /**
     * A real directory that counts every `listFiles()` scan performed against it.
     * `java.io.File` is a non-final class with a virtual `listFiles()`, so this is
     * a genuine observation of the store's filesystem behaviour — the store is
     * handed an ordinary directory and is not aware it is being measured.
     */
    private class ScanCountingDirectory(path: String) : File(path) {
        val scans = AtomicInteger()

        override fun listFiles(): Array<File>? {
            scans.incrementAndGet()
            return super.listFiles()
        }

        companion object {
            private const val serialVersionUID: Long = 1L
        }
    }

    @Test
    fun `concurrent append never rescans the part directory — the convoy that wedged the gate`() =
        runBlocking {
            val directory = ScanCountingDirectory(tmp.newFolder("connection-log").absolutePath)
            // One line per part makes the on-disk part count grow with every append,
            // so the removed per-append O(parts) scan is genuinely quadratic here —
            // the same shape as the wedged gate run, sized so the convoy dominates.
            // maxParts is above N so nothing is folded away and the part count keeps
            // climbing for the whole run.
            val store = ConnectionLogPartStore(
                directory = directory,
                maxLinesPerPart = 1,
                maxParts = 100_000,
            )
            // Recovering rotation state at construction is allowed to scan the
            // directory exactly once; everything after that must be in-memory.
            val scansAfterOpen = directory.scans.get()
            assertEquals("construction recovers rotation state with a single scan", 1, scansAfterOpen)

            val writers = 16
            val perWriter = 250
            val n = writers * perWriter
            val completed = AtomicInteger()
            val abort = AtomicBoolean(false)
            val jobs: List<Job> = (0 until writers).map { w ->
                launch(Dispatchers.IO) {
                    for (i in 0 until perWriter) {
                        // The abort flag only bounds the RED case: on a healthy run
                        // every writer finishes before the deadline and never reads
                        // it as true, so it cannot weaken the assertions below.
                        if (abort.get()) return@launch
                        store.append("""{"seq":${w * perWriter + i},"w":$w}""")
                    }
                    completed.incrementAndGet()
                }
            }

            val startedAtMs = System.currentTimeMillis()
            val settled = drainMainLooperUntil { completed.get() == writers }
            val elapsedMs = System.currentTimeMillis() - startedAtMs
            if (!settled) abort.set(true)
            jobs.forEach { it.join() }

            assertTrue(
                "$writers writers x $perWriter appends must finish inside the generous settle " +
                    "deadline; they convoy on the per-append directory scan instead " +
                    "(completed=${completed.get()}/$writers after ${elapsedMs}ms, " +
                    "directory scans=${directory.scans.get()})",
                settled,
            )

            // THE load-bearing mechanism assertion: appending must cost zero
            // directory scans. On the unfixed store this is one scan per append.
            assertEquals(
                "append must not scan the part directory (issue #2147): $n appends performed " +
                    "${directory.scans.get() - scansAfterOpen} extra scans",
                scansAfterOpen,
                directory.scans.get(),
            )

            // Losslessness is not traded away for the speed-up.
            val lines = store.readAllLines()
            assertEquals("every appended line must still read back", n, lines.size)
        }

    @Test
    fun `compacting append never rescans the part directory either`() = runBlocking {
        val directory = ScanCountingDirectory(tmp.newFolder("connection-log").absolutePath)
        // The production shape: a tight part ceiling, so compaction folds parts into
        // the base file on nearly every append. Rotation AND compaction must both
        // maintain the tracked part set in memory rather than re-deriving it from
        // disk, or the scan simply moves from one caller to another.
        val store = ConnectionLogPartStore(
            directory = directory,
            maxLinesPerPart = 1,
            maxParts = 8,
        )
        val scansAfterOpen = directory.scans.get()

        val writers = 8
        val perWriter = 250
        val n = writers * perWriter
        val completed = AtomicInteger()
        val abort = AtomicBoolean(false)
        val jobs: List<Job> = (0 until writers).map { w ->
            launch(Dispatchers.IO) {
                for (i in 0 until perWriter) {
                    if (abort.get()) return@launch
                    store.append("""{"seq":${w * perWriter + i},"w":$w}""")
                }
                completed.incrementAndGet()
            }
        }

        val settled = drainMainLooperUntil { completed.get() == writers }
        if (!settled) abort.set(true)
        jobs.forEach { it.join() }

        assertTrue(
            "compacting writers must finish inside the generous settle deadline " +
                "(completed=${completed.get()}/$writers, directory scans=${directory.scans.get()})",
            settled,
        )
        assertEquals(
            "compaction must not scan the part directory (issue #2147): " +
                "${directory.scans.get() - scansAfterOpen} extra scans",
            scansAfterOpen,
            directory.scans.get(),
        )
        assertTrue(
            "the on-disk part count must still be bounded, was ${store.partCount()}",
            store.partCount() <= 8,
        )
        // The same bound, taken from the FILESYSTEM. `partCount()` is answered from
        // the tracked set now, so on its own it cannot tell a bounded directory from
        // a compaction that folds parts but never removes their files. Read through a
        // SEPARATE plain handle, and only after the scan-count assertion above, so
        // this check cannot perturb that oracle.
        val onDisk = partEntriesOnDisk(directory)
        assertTrue(
            "the bound must hold on disk, not just in the tracked set: ${onDisk.size} " +
                "part files remain (${onDisk.map { it.name }.sorted()})",
            onDisk.size <= 8,
        )
        assertEquals("compaction must stay lossless", n, store.readAllLines().size)
    }

    @Test
    fun `part count and read back are still answered without rescanning`() {
        val directory = ScanCountingDirectory(tmp.newFolder("connection-log").absolutePath)
        val store = ConnectionLogPartStore(
            directory = directory,
            maxLinesPerPart = 5,
            maxParts = 100,
        )
        val scansAfterOpen = directory.scans.get()
        repeat(60) { store.append("""{"seq":$it}""") }

        assertEquals("12 parts of 5 lines each", 12, store.partCount())
        assertEquals(60, store.readAllLines().size)
        store.compact()
        store.clear()
        assertEquals(0, store.partCount())
        assertEquals(
            "no store operation may rescan the directory after construction (issue #2147)",
            scansAfterOpen,
            directory.scans.get(),
        )
        // `clear()` drops parts from the tracked set too, so its bound is disk-derived
        // as well — asserted after the scan oracle, through a separate plain handle.
        val leftOnDisk = partEntriesOnDisk(directory)
        assertTrue(
            "clear() must leave no part file behind, found ${leftOnDisk.map { it.name }.sorted()}",
            leftOnDisk.isEmpty(),
        )
    }

    /**
     * The blocking half of the tracked-set design: what happens when a part is
     * folded into base but its file survives `delete()`.
     *
     * With the part list re-derived from disk this could not go wrong — the next
     * scan saw the survivor. With the list tracked in memory (the #2147 fix) a
     * store that popped the part regardless would report "bounded to maxParts"
     * while the directory grew without limit, and `readAllLines` would omit the
     * stranded file, so the exported archive could not reveal it either. That is
     * the #1669 field failure (200+ stranded `.part-*` files) made INVISIBLE.
     *
     * The undeletable part is created for real, not stubbed: a non-empty
     * DIRECTORY at the part's path. `File.delete()` returns false for it on every
     * platform, `isFile` is false so the store reads no lines from it, and it
     * needs no permission games (which a root test runner would ignore anyway).
     */
    @Test
    fun `a part whose delete fails stays counted instead of vanishing into the cache`() {
        val directory = tmp.newFolder("connection-log")
        val maxParts = 4
        val store = ConnectionLogPartStore(
            directory = directory,
            maxLinesPerPart = 1,
            maxParts = maxParts,
        )
        // Four parts, one line each: at the ceiling, nothing folded yet.
        repeat(maxParts) { store.append("""{"seq":$it}""") }
        val oldest = File(directory, "${ConnectionLogPartStore.DEFAULT_BASE_NAME}.part-000001")
        assertTrue("the oldest part must exist before it is made undeletable", oldest.isFile)

        // Make the oldest part undeletable. Its line goes with it — that loss is
        // this test's own doing, not the store's, so the expected read-back below
        // starts at seq 1.
        assertTrue(oldest.delete())
        assertTrue(oldest.mkdir())
        assertTrue(File(oldest, "occupant").createNewFile())

        // This append rotates to a fifth part, so compaction must fold the oldest —
        // and its delete fails.
        store.append("""{"seq":$maxParts}""")

        val onDisk = partEntriesOnDisk(directory)
        assertTrue(
            "the undeletable part must still be on disk for this test to mean anything",
            onDisk.any { it.name == oldest.name },
        )
        assertEquals(
            "partCount() must equal the real on-disk part count — a part that could not " +
                "be deleted may not disappear from the count (issue #2147): on disk " +
                "${onDisk.map { it.name }.sorted()}",
            onDisk.size,
            store.partCount(),
        )
        assertTrue(
            "the breached bound must be VISIBLE through the store's own API, not hidden " +
                "behind a healthy-looking cache (partCount=${store.partCount()})",
            store.partCount() > maxParts,
        )

        // Keep appending: the stranded part must neither be lost from the count nor
        // re-folded into base (which would duplicate lines and grow base without
        // bound), and every real line must still read back exactly once.
        val total = 25
        for (seq in (maxParts + 1) until total) {
            store.append("""{"seq":$seq}""")
        }
        val stillOnDisk = partEntriesOnDisk(directory)
        assertEquals(
            "partCount() must keep matching the disk as compaction continues: on disk " +
                "${stillOnDisk.map { it.name }.sorted()}",
            stillOnDisk.size,
            store.partCount(),
        )
        assertTrue(
            "one undeletable part must not make the directory grow without bound, found " +
                "${stillOnDisk.size} part entries",
            stillOnDisk.size <= maxParts + 1,
        )
        val seqs = store.readAllLines().map { it.substringAfter("\"seq\":").substringBefore("}").toInt() }
        assertEquals(
            "the folded-but-undeleted part must not be read back twice, and no other " +
                "line may be lost",
            (1 until total).toList(),
            seqs,
        )
    }

    private fun partEntriesOnDisk(directory: File): List<File> =
        // A plain handle: never the ScanCountingDirectory instance, so a disk-derived
        // assertion can never perturb the scan-count oracle.
        File(directory.absolutePath).listFiles().orEmpty()
            .filter { it.name.contains(".part-") }
            .sortedBy { it.name }
}
