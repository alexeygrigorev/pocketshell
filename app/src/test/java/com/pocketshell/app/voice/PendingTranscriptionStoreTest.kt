package com.pocketshell.app.voice

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.app.hosts.MainDispatcherRule
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [PendingTranscriptionStore] (issue #180). Exercises the
 * persist-before-Whisper invariant, the on-disk + DB pairing, and the
 * orphan reconciliation pass.
 *
 * Robolectric is used because the store needs a [Context] for
 * `filesDir` and Room is wired against a fresh in-memory database per
 * test so writes don't leak across tests.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PendingTranscriptionStoreTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var store: PendingTranscriptionStore

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
        store = PendingTranscriptionStore(context, db.pendingTranscriptionDao())
        // Wipe the voice-pending directory across tests so a previous
        // run's files don't leak into reconcile().
        File(context.filesDir, PendingTranscriptionStore.VOICE_PENDING_DIR).deleteRecursively()
        File(context.filesDir, PendingTranscriptionStore.EXPORTS_DIR).deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `enqueue persists audio to disk and inserts a row`() = runTest {
        var ticks = 0L
        store.clock = { (1700000000000L + ticks++).also { ticks += 0 } }
        store.idGenerator = { "uuid-test-1" }
        val audio = ByteArray(1024) { it.toByte() }

        val item = store.enqueueAudio(
            audio = audio,
            destinationContext = PendingTranscriptionEntity.DESTINATION_COMPOSER,
        )

        assertNotNull(item)
        assertEquals("uuid-test-1", item!!.id)
        assertEquals(0, item.retryCount)
        assertEquals(1024L, item.audioByteSize)
        assertEquals(
            PendingTranscriptionEntity.DESTINATION_COMPOSER,
            item.destinationContext,
        )

        val file = File(
            File(context.filesDir, PendingTranscriptionStore.VOICE_PENDING_DIR),
            "uuid-test-1.wav",
        )
        assertTrue("audio file must be on disk", file.exists())
        assertEquals(1024, file.length())

        val rows = db.pendingTranscriptionDao().getAllOnce()
        assertEquals(1, rows.size)
        assertEquals("uuid-test-1", rows[0].id)
        assertEquals(file.absolutePath, rows[0].audioPath)
    }

    @Test
    fun `enqueue with empty audio returns null and writes nothing`() = runTest {
        val item = store.enqueueAudio(
            audio = ByteArray(0),
            destinationContext = "composer",
        )
        assertNull(item)
        assertEquals(0, db.pendingTranscriptionDao().getAllOnce().size)
    }

    @Test
    fun `enqueue rejects audio above the 10 MB cap`() = runTest {
        // The cap is 10 MB; build a buffer 1 byte over.
        val audio = ByteArray((PendingTranscriptionStore.MAX_AUDIO_BYTES + 1).toInt())
        val item = store.enqueueAudio(
            audio = audio,
            destinationContext = "composer",
        )
        assertNull("over-cap audio must be rejected", item)
        assertEquals(0, db.pendingTranscriptionDao().getAllOnce().size)
    }

    @Test
    fun `enqueue with initial error message stores the sentinel`() = runTest {
        store.idGenerator = { "offline-uuid" }
        val item = store.enqueueAudio(
            audio = ByteArray(128) { it.toByte() },
            destinationContext = "composer",
            initialError = PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
        )
        assertNotNull(item)
        assertTrue("must surface waiting-for-network", item!!.isWaitingForNetwork)
        // Persisted into the DB row.
        val row = db.pendingTranscriptionDao().getById("offline-uuid")
        assertEquals(
            PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
            row?.lastErrorMessage,
        )
    }

    @Test
    fun `markSucceeded deletes row and audio file`() = runTest {
        store.idGenerator = { "to-succeed" }
        val item = store.enqueueAudio(ByteArray(64) { it.toByte() }, "composer")
        assertNotNull(item)
        val file = File(
            File(context.filesDir, PendingTranscriptionStore.VOICE_PENDING_DIR),
            "to-succeed.wav",
        )
        assertTrue(file.exists())

        store.markSucceeded("to-succeed")

        assertFalse("file must be deleted", file.exists())
        assertNull(db.pendingTranscriptionDao().getById("to-succeed"))
    }

    @Test
    fun `markFailure bumps retry count and stamps the latest error`() = runTest {
        store.idGenerator = { "to-fail" }
        store.enqueueAudio(ByteArray(64), "composer")
        val first = store.markFailure("to-fail", "Whisper auth failed")
        assertNotNull(first)
        assertEquals(1, first!!.retryCount)
        assertEquals("Whisper auth failed", first.lastErrorMessage)

        val second = store.markFailure("to-fail", "Network error")
        assertNotNull(second)
        assertEquals(2, second!!.retryCount)
        assertEquals("Network error", second.lastErrorMessage)

        val third = store.markFailure("to-fail", "Server error")
        assertNotNull(third)
        assertEquals(3, third!!.retryCount)
        assertTrue("at the retry cap", third.atRetryCap)
    }

    @Test
    fun `discard deletes row and audio file`() = runTest {
        store.idGenerator = { "to-discard" }
        store.enqueueAudio(ByteArray(64), "composer")
        val file = File(
            File(context.filesDir, PendingTranscriptionStore.VOICE_PENDING_DIR),
            "to-discard.wav",
        )
        assertTrue(file.exists())

        store.discard("to-discard")

        assertFalse(file.exists())
        assertNull(db.pendingTranscriptionDao().getById("to-discard"))
    }

    @Test
    fun `saveAsAudioFile copies into exports dir and clears queue`() = runTest {
        store.idGenerator = { "to-export" }
        val payload = ByteArray(128) { (it and 0x7F).toByte() }
        store.enqueueAudio(payload, "composer")
        val src = File(
            File(context.filesDir, PendingTranscriptionStore.VOICE_PENDING_DIR),
            "to-export.wav",
        )
        assertTrue(src.exists())

        val exportPath = store.saveAsAudioFile("to-export")
        assertNotNull("save must succeed", exportPath)
        val dst = File(exportPath!!)
        assertTrue("export must exist", dst.exists())
        // Same content as the original.
        assertTrue(dst.readBytes().contentEquals(payload))
        // Queue row + source file are cleared.
        assertFalse(src.exists())
        assertNull(db.pendingTranscriptionDao().getById("to-export"))
    }

    @Test
    fun `loadAudio returns the persisted bytes`() = runTest {
        store.idGenerator = { "to-load" }
        val payload = ByteArray(256) { (it xor 0x55).toByte() }
        store.enqueueAudio(payload, "composer")
        val loaded = store.loadAudio("to-load")
        assertNotNull(loaded)
        assertTrue(loaded!!.contentEquals(payload))
    }

    @Test
    fun `loadAudio returns null for missing row`() = runTest {
        assertNull(store.loadAudio("not-there"))
    }

    @Test
    fun `reconcile sweeps orphan files without rows`() = runTest {
        val dir = File(context.filesDir, PendingTranscriptionStore.VOICE_PENDING_DIR)
        dir.mkdirs()
        val orphanFile = File(dir, "orphan.wav").apply { writeBytes(ByteArray(8)) }
        assertTrue(orphanFile.exists())

        store.reconcile()

        assertFalse("orphan file must be swept", orphanFile.exists())
    }

    @Test
    fun `reconcile sweeps rows without files`() = runTest {
        // Insert a row that points at a non-existent file.
        val dao = db.pendingTranscriptionDao()
        dao.insert(
            PendingTranscriptionEntity(
                id = "ghost",
                audioPath = "/data/does-not-exist/ghost.wav",
                recordingTimestampMs = 1L,
                destinationContext = "composer",
                retryCount = 0,
                lastErrorMessage = null,
                audioByteSize = 0L,
                createdAtMs = 1L,
            ),
        )
        assertNotNull(dao.getById("ghost"))

        store.reconcile()

        assertNull("ghost row must be swept", dao.getById("ghost"))
    }

    @Test
    fun `items flow emits empty list and then the inserted item`() = runTest {
        // Initial empty emission.
        assertEquals(0, store.items.first().size)

        store.idGenerator = { "uuid-flow" }
        store.enqueueAudio(ByteArray(32), "composer")

        val items = store.items.first()
        assertEquals(1, items.size)
        assertEquals("uuid-flow", items[0].id)
    }

    @Test
    fun `clearAll wipes rows and the voice-pending directory`() = runTest {
        store.idGenerator = { "a" }
        store.enqueueAudio(ByteArray(8), "composer")
        store.idGenerator = { "b" }
        store.enqueueAudio(ByteArray(8), "composer")
        assertEquals(2, db.pendingTranscriptionDao().getAllOnce().size)

        store.clearAll()

        assertEquals(0, db.pendingTranscriptionDao().getAllOnce().size)
        val dir = File(context.filesDir, PendingTranscriptionStore.VOICE_PENDING_DIR)
        // Either the dir is empty or doesn't exist; either is acceptable.
        val remaining = dir.listFiles()?.size ?: 0
        assertEquals(0, remaining)
    }

    @Test
    fun `atRetryCap matches the documented max attempts constant`() {
        val belowCap = PendingTranscriptionItem(
            id = "x",
            recordingTimestampMs = 0,
            destinationContext = "composer",
            retryCount = 2,
            lastErrorMessage = "err",
            audioByteSize = 0,
        )
        assertFalse(belowCap.atRetryCap)
        val atCap = belowCap.copy(retryCount = PendingTranscriptionEntity.MAX_RETRY_ATTEMPTS)
        assertTrue(atCap.atRetryCap)
        val pastCap =
            belowCap.copy(retryCount = PendingTranscriptionEntity.MAX_RETRY_ATTEMPTS + 1)
        assertTrue(pastCap.atRetryCap)
    }
}
