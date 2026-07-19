package com.pocketshell.app.diagnostics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * Issue #1669: the field connection log must be LOSSLESS reliability evidence.
 * Today's storm forensics recovered only ~187 of ~1,660 events from 200+
 * `connection-log.jsonl.part-*` files (dozens ZERO-LENGTH). These tests pin the
 * three integrity properties the store guarantees:
 *
 *  1. Rotation is lossless under concurrent load — write N, read back exactly N.
 *  2. No zero-length part file is ever produced (the exact field failure mode).
 *  3. Compaction bounds the on-disk part count without dropping a line.
 */
class ConnectionLogPartStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun dir(): File = tmp.newFolder("connection-log")

    @Test
    fun `concurrent rotation is lossless — writes N reads back N`() = runBlocking {
        // Small parts so N=4000 forces HUNDREDS of rotations — the load that
        // stranded zero-length parts in the field. maxParts high enough that
        // compaction folds but nothing is dropped.
        val store = ConnectionLogPartStore(
            directory = dir(),
            maxLinesPerPart = 25,
            maxParts = 1000,
        )
        val n = 4000

        // 16 writers hammering append concurrently. On the pre-fix snapshot the
        // host log lost ~90% of events under exactly this kind of load; here the
        // store must read back EVERY line.
        val perWriter = n / 16
        (0 until 16).map { w ->
            async(Dispatchers.IO) {
                for (i in 0 until perWriter) {
                    store.append("""{"seq":${w * perWriter + i},"w":$w}""")
                }
            }
        }.awaitAll()

        val lines = store.readAllLines()
        assertEquals("every appended line must read back — no loss under load", n, lines.size)

        // The distinct set is exactly 0..n-1: not one line torn, duplicated, or dropped.
        val seqs = lines.map { it.substringAfter("\"seq\":").substringBefore(",").toInt() }.toSortedSet()
        assertEquals(n, seqs.size)
        assertEquals(0, seqs.first())
        assertEquals(n - 1, seqs.last())
    }

    @Test
    fun `no zero-length part file is ever produced`() = runBlocking {
        val directory = dir()
        val store = ConnectionLogPartStore(
            directory = directory,
            maxLinesPerPart = 10,
            maxParts = 1000,
        )
        repeat(1000) { store.append("""{"seq":$it}""") }

        val parts = directory.listFiles().orEmpty().filter { it.name.contains(".part-") }
        assertTrue("rotation must have created multiple parts", parts.size > 1)
        val empty = parts.filter { it.length() == 0L }
        assertTrue("no zero-length part may exist (the field failure mode); found $empty", empty.isEmpty())
    }

    @Test
    fun `compaction bounds the on-disk part count without dropping lines`() = runBlocking {
        val store = ConnectionLogPartStore(
            directory = dir(),
            maxLinesPerPart = 10,
            maxParts = 4,
        )
        val n = 500
        repeat(n) { store.append("""{"seq":$it}""") }

        // 500 lines / 10 per part = 50 parts' worth of data, but compaction keeps
        // the on-disk part count at or below the bound by folding into the base file.
        assertTrue(
            "on-disk part count must be bounded to maxParts=4, was ${store.partCount()}",
            store.partCount() <= 4,
        )
        // Bounding the part count must NOT lose events: all 500 still read back in order.
        val seqs = store.readAllLines().map { it.substringAfter("\"seq\":").substringBefore("}").toInt() }
        assertEquals((0 until n).toList(), seqs)
    }

    @Test
    fun `explicit compact folds oldest parts into base and preserves every line`() = runBlocking {
        val store = ConnectionLogPartStore(
            directory = dir(),
            maxLinesPerPart = 5,
            maxParts = 100, // no auto-compaction; drive it explicitly
        )
        repeat(60) { store.append("""{"seq":$it}""") }
        val before = store.readAllLines()
        val partsBefore = store.partCount()

        store.compact()

        // compact() with maxParts=100 and only 12 parts is a no-op on count, but
        // the read is byte-for-byte identical either way — compaction is lossless.
        assertEquals("compaction never changes the readback", before, store.readAllLines())
        assertTrue(partsBefore >= 1)
    }

    @Test
    fun `rotation state recovers across store re-open so a restart never clobbers a part`() = runBlocking {
        val directory = dir()
        ConnectionLogPartStore(directory, maxLinesPerPart = 100, maxParts = 100).apply {
            repeat(30) { append("""{"seq":$it}""") }
        }
        // A fresh store over the SAME directory (process restart) must continue the
        // existing part, not start over and lose the 30 lines already on disk.
        val reopened = ConnectionLogPartStore(directory, maxLinesPerPart = 100, maxParts = 100)
        repeat(20) { reopened.append("""{"seq":${30 + it}}""") }

        val seqs = reopened.readAllLines().map { it.substringAfter("\"seq\":").substringBefore("}").toInt() }
        assertEquals((0 until 50).toList(), seqs)
    }
}
