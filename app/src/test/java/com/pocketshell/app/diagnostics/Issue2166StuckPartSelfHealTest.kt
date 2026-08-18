package com.pocketshell.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #2166: a folded part whose `delete()` failed must self-heal once it
 * becomes deletable.
 *
 * #2147 added `undeletedFoldedParts` and retries those deletes at the start of
 * every `compactLocked`. The reviewer's mutation M4 (`if (undeletedFoldedParts
 * .isEmpty() || true) return` in `retryUndeletedFoldedPartsLocked`) survived
 * 11/11 — no test drove the stuck → deletable transition, so the retry could
 * be removed and the suite stayed green. The failure mode is fail-safe
 * (over-report a resolved breach, never hide a real one) but the retry is
 * still load-bearing behaviour and must be constrained.
 *
 * Sibling states of the same deque are covered here too: repeated failures
 * must not accumulate without bound, `clear()` must keep a stuck survivor
 * counted, and a stuck part must still be handled after construction-time
 * recovery of prior-process parts.
 *
 * The undeletable fixture is the #2147 one: a non-empty directory at the
 * part's path. `File.delete()` returns false for it on every platform
 * (including root — `rmdir(2)` fails `ENOTEMPTY` regardless of
 * `CAP_DAC_OVERRIDE`), `isFile` is false so the store reads no lines from it,
 * and making it deletable is emptying the directory so the *store's* next
 * `delete()` is what removes it.
 */
class Issue2166StuckPartSelfHealTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `a stuck folded part that becomes deletable is removed on the next compact`() {
        val directory = tmp.newFolder("connection-log")
        val maxParts = 4
        val store = ConnectionLogPartStore(
            directory = directory,
            maxLinesPerPart = 1,
            maxParts = maxParts,
        )
        repeat(maxParts) { store.append("""{"seq":$it}""") }
        val oldest = partFile(directory, 1)
        assertTrue("the oldest part must exist before it is made undeletable", oldest.isFile)
        makeUndeletable(oldest)

        // Overflow: compact folds the oldest and its delete fails.
        store.append("""{"seq":$maxParts}""")
        assertStuckTracked(
            store = store,
            directory = directory,
            stuck = oldest,
            maxParts = maxParts,
            expectedMinCount = maxParts + 1,
        )

        // The fixture is now an empty directory — `File.delete()` will succeed.
        // Leave it in place so the store's retry is what removes it.
        makeDeletable(oldest)
        assertTrue(
            "the now-deletable fixture must still occupy the path; the store, not the test, deletes it",
            oldest.exists(),
        )

        // Next compactLocked — production reaches this via the next overflow
        // append, which also folds one live part. Retry runs first.
        store.append("""{"seq":${maxParts + 1}}""")

        assertFalse(
            "the self-heal retry must remove the now-deletable part from disk " +
                "(issue #2166 / reviewer M4)",
            oldest.exists(),
        )
        val onDisk = partEntriesOnDisk(directory)
        assertTrue(
            "the healed part must not remain among on-disk part entries: " +
                onDisk.map { it.name },
            onDisk.none { it.name == oldest.name },
        )
        assertEquals(
            "partCount() must drop the healed part — gone from undeletedFoldedParts, " +
                "not merely from disk: on disk ${onDisk.map { it.name }}",
            onDisk.size,
            store.partCount(),
        )
        assertTrue(
            "healing the stuck part must resolve the bound breach " +
                "(partCount=${store.partCount()}, maxParts=$maxParts, on disk " +
                "${onDisk.map { it.name }})",
            store.partCount() <= maxParts,
        )
        val seqs = store.readAllLines().map { it.substringAfter("\"seq\":").substringBefore("}").toInt() }
        assertEquals(
            "self-heal must not re-fold or double-read the healed part",
            (1 until maxParts + 2).toList(),
            seqs,
        )
    }

    @Test
    fun `repeated delete failures stay counted once each and do not grow without bound`() {
        val directory = tmp.newFolder("connection-log")
        val maxParts = 3
        val store = ConnectionLogPartStore(
            directory = directory,
            maxLinesPerPart = 1,
            maxParts = maxParts,
        )
        repeat(maxParts) { store.append("""{"seq":$it}""") }

        val first = partFile(directory, 1)
        makeUndeletable(first)
        store.append("""{"seq":$maxParts}""")
        assertStuckTracked(store, directory, first, maxParts, expectedMinCount = maxParts + 1)

        val second = partFile(directory, 2)
        assertTrue("the next live part must exist before it is made undeletable", second.isFile)
        makeUndeletable(second)
        store.append("""{"seq":${maxParts + 1}}""")
        assertStuckTracked(store, directory, second, maxParts, expectedMinCount = maxParts + 2)
        assertTrue(first.exists())

        // Further compaction must keep retrying the same two stuck parts without
        // re-adding them and without letting the live set grow.
        val total = 20
        for (seq in (maxParts + 2) until total) {
            store.append("""{"seq":$seq}""")
        }
        val onDisk = partEntriesOnDisk(directory)
        assertTrue("first stuck part must still be on disk", onDisk.any { it.name == first.name })
        assertTrue("second stuck part must still be on disk", onDisk.any { it.name == second.name })
        assertEquals(
            "partCount() must keep matching the disk across repeated failed retries: " +
                "on disk ${onDisk.map { it.name }}",
            onDisk.size,
            store.partCount(),
        )
        assertEquals(
            "repeated failures accumulate one entry per stuck file, not without bound: " +
                "expected maxParts+$STUCK_PAIR, found ${onDisk.size} (${onDisk.map { it.name }})",
            maxParts + STUCK_PAIR,
            onDisk.size,
        )
        val seqs = store.readAllLines().map { it.substringAfter("\"seq\":").substringBefore("}").toInt() }
        assertEquals(
            "stuck parts must not be re-folded or read back twice",
            (2 until total).toList(),
            seqs,
        )
    }

    @Test
    fun `clear keeps a stuck part counted as a live survivor and drops it once deletable`() {
        val directory = tmp.newFolder("connection-log")
        val maxParts = 4
        val store = ConnectionLogPartStore(
            directory = directory,
            maxLinesPerPart = 1,
            maxParts = maxParts,
        )
        repeat(maxParts) { store.append("""{"seq":$it}""") }
        val oldest = partFile(directory, 1)
        makeUndeletable(oldest)
        store.append("""{"seq":$maxParts}""")
        assertStuckTracked(store, directory, oldest, maxParts, expectedMinCount = maxParts + 1)

        store.clear()

        assertTrue(
            "clear() must not pretend a stuck part is gone",
            oldest.exists(),
        )
        val afterClear = partEntriesOnDisk(directory)
        assertEquals(
            "the stuck survivor is the only part left on disk after clear: " +
                afterClear.map { it.name },
            listOf(oldest.name),
            afterClear.map { it.name },
        )
        assertEquals(
            "clear() re-tracks a stuck folded part as a live survivor " +
                "(base is gone, so it must stay counted)",
            1,
            store.partCount(),
        )
        assertEquals(afterClear.size, store.partCount())

        makeDeletable(oldest)
        store.clear()

        assertFalse(
            "a now-deletable survivor must leave disk on the next clear()",
            oldest.exists(),
        )
        assertEquals(0, store.partCount())
        assertTrue(
            "clear() of a now-deletable survivor must leave no part file behind, found " +
                partEntriesOnDisk(directory).map { it.name },
            partEntriesOnDisk(directory).isEmpty(),
        )
    }

    @Test
    fun `construction recovers prior-process live parts and skips a leftover stuck directory`() {
        val directory = tmp.newFolder("connection-log")
        val maxParts = 4
        val prior = ConnectionLogPartStore(
            directory = directory,
            maxLinesPerPart = 1,
            maxParts = maxParts,
        )
        repeat(maxParts) { prior.append("""{"seq":$it}""") }
        val leftoverStuck = partFile(directory, 1)
        makeUndeletable(leftoverStuck)
        prior.append("""{"seq":$maxParts}""")
        assertStuckTracked(prior, directory, leftoverStuck, maxParts, expectedMinCount = maxParts + 1)
        val liveFilesBeforeClose = partEntriesOnDisk(directory).filter { it.isFile }.map { it.name }.sorted()

        // New process over the same directory. Construction scans only files, so
        // the leftover stuck directory is not recovered as a live part — and
        // must not be read back or crash recovery.
        val reopened = ConnectionLogPartStore(
            directory = directory,
            maxLinesPerPart = 1,
            maxParts = maxParts,
        )
        assertTrue(
            "a prior-process stuck directory must still occupy the path after reopen",
            leftoverStuck.exists() && leftoverStuck.isDirectory,
        )
        val recoveredFiles = partEntriesOnDisk(directory).filter { it.isFile }.map { it.name }.sorted()
        assertEquals(
            "construction must recover the leftover live part files",
            liveFilesBeforeClose,
            recoveredFiles,
        )
        assertEquals(
            "reopened partCount() is the recovered live files; the leftover directory " +
                "is not a file so it is not in the tracked set",
            recoveredFiles.size,
            reopened.partCount(),
        )
        val recoveredSeqs = reopened.readAllLines()
            .map { it.substringAfter("\"seq\":").substringBefore("}").toInt() }
        assertEquals(
            "recovery must read each leftover live part exactly once and skip the directory",
            (1 until maxParts + 1).toList(),
            recoveredSeqs,
        )

        // Compaction on the reopened store must keep working: fold a recovered
        // live part (still a real file) without crashing on the leftover dir.
        reopened.append("""{"seq":${maxParts + 1}}""")
        val afterCompact = partEntriesOnDisk(directory)
        assertTrue(
            "the leftover stuck directory is still on disk after the new process compacts",
            afterCompact.any { it.name == leftoverStuck.name && it.isDirectory },
        )
        assertEquals(
            "the new process tracks only the files it recovered and created, " +
                "not the leftover directory: files=" +
                afterCompact.filter { it.isFile }.map { it.name },
            afterCompact.count { it.isFile },
            reopened.partCount(),
        )
        assertTrue(
            "reopened compaction still honours maxParts for live files " +
                "(live=${afterCompact.count { it.isFile }}, maxParts=$maxParts)",
            afterCompact.count { it.isFile } <= maxParts,
        )
    }

    @Test
    fun `a recovered prior-process part that cannot be deleted stays counted`() {
        val directory = tmp.newFolder("connection-log")
        val maxParts = 4
        ConnectionLogPartStore(
            directory = directory,
            maxLinesPerPart = 1,
            maxParts = 100,
        ).apply {
            // No compaction in the prior process — leftover files only.
            repeat(maxParts + 1) { append("""{"seq":$it}""") }
        }

        val reopened = ConnectionLogPartStore(
            directory = directory,
            maxLinesPerPart = 1,
            maxParts = maxParts,
        )
        assertEquals(
            "construction recovers every leftover live part file",
            maxParts + 1,
            reopened.partCount(),
        )
        val recoveredOldest = partFile(directory, 1)
        assertTrue("recovered oldest part must exist as a file", recoveredOldest.isFile)
        makeUndeletable(recoveredOldest)
        reopened.compact()
        assertStuckTracked(
            store = reopened,
            directory = directory,
            stuck = recoveredOldest,
            maxParts = maxParts,
            expectedMinCount = maxParts + 1,
        )
        val seqs = reopened.readAllLines()
            .map { it.substringAfter("\"seq\":").substringBefore("}").toInt() }
        assertEquals(
            "the recovered-then-stuck part must not be read back twice",
            (1 until maxParts + 1).toList(),
            seqs,
        )
    }

    private fun assertStuckTracked(
        store: ConnectionLogPartStore,
        directory: File,
        stuck: File,
        maxParts: Int,
        expectedMinCount: Int,
    ) {
        val onDisk = partEntriesOnDisk(directory)
        assertTrue(
            "the undeletable part must still be on disk for this test to mean anything",
            onDisk.any { it.name == stuck.name },
        )
        assertEquals(
            "partCount() must equal the real on-disk part count: on disk " +
                onDisk.map { it.name },
            onDisk.size,
            store.partCount(),
        )
        assertTrue(
            "the breached bound must be visible (partCount=${store.partCount()}, " +
                "maxParts=$maxParts, expected at least $expectedMinCount)",
            store.partCount() >= expectedMinCount && store.partCount() > maxParts,
        )
    }

    private fun makeUndeletable(part: File) {
        assertTrue(part.delete())
        assertTrue(part.mkdir())
        assertTrue(File(part, "occupant").createNewFile())
    }

    private fun makeDeletable(part: File) {
        assertTrue("stuck fixture must still be a directory", part.isDirectory)
        part.listFiles().orEmpty().forEach { child ->
            assertTrue("failed to empty $child", child.delete())
        }
        assertTrue(part.exists())
        assertTrue(part.listFiles().orEmpty().isEmpty())
    }

    private fun partFile(directory: File, index: Int): File =
        File(
            directory,
            "${ConnectionLogPartStore.DEFAULT_BASE_NAME}.part-" +
                index.toString().padStart(6, '0'),
        )

    private fun partEntriesOnDisk(directory: File): List<File> =
        File(directory.absolutePath).listFiles().orEmpty()
            .filter { it.name.contains(".part-") }
            .sortedBy { it.name }

    companion object {
        private const val STUCK_PAIR = 2
    }
}
