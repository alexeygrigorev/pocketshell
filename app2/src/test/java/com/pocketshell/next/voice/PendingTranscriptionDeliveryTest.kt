package com.pocketshell.next.voice

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.AppDatabase
import com.pocketshell.core.storage.entity.PendingTranscriptionEntity
import com.pocketshell.core.voice.WhisperClient
import com.pocketshell.core.voice.WhisperException
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Unit tests for [PendingTranscriptionDelivery] (rewrite task P-2) — the
 * other half of the subway case: turning a recording queued while offline
 * into a transcript once the composer is foreground again.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class PendingTranscriptionDeliveryTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var store: PendingTranscriptionStore
    private val whisperClient = FakeWhisperClient()
    private val whisperFactory = FakeWhisperClientFactory(whisperClient)
    private val connectivity = FakeConnectivityProbe()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .setQueryExecutor(Runnable::run)
            .setTransactionExecutor(Runnable::run)
            .allowMainThreadQueries()
            .build()
        store = PendingTranscriptionStore(context, db.pendingTranscriptionDao())
        File(context.filesDir, PendingTranscriptionStore.VOICE_PENDING_DIR).deleteRecursively()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun delivery() = PendingTranscriptionDelivery(store, whisperFactory, connectivity)

    private suspend fun queueOfflineRow(id: String, tsMs: Long = 1L): Unit {
        store.idGenerator = { id }
        store.clock = { tsMs }
        store.enqueueAudio(
            audio = ByteArray(32) { it.toByte() },
            destinationContext = PendingTranscriptionEntity.DESTINATION_COMPOSER,
            initialError = PendingTranscriptionItem.NETWORK_WAITING_MESSAGE,
        )
    }

    @Test
    fun `delivers every queued-offline row, oldest first, and clears the queue`() = runTest {
        queueOfflineRow("second", tsMs = 200L)
        queueOfflineRow("first", tsMs = 100L)
        whisperClient.result = Result.success("hello there")

        val transcripts = delivery().deliverQueued()

        assertEquals(listOf("hello there", "hello there"), transcripts)
        assertEquals(0, db.pendingTranscriptionDao().getAllOnce().size)
    }

    @Test
    fun `returns nothing and touches no network while still offline`() = runTest {
        queueOfflineRow("stuck")
        connectivity.online = false

        val transcripts = delivery().deliverQueued()

        assertTrue(transcripts.isEmpty())
        assertEquals(0, whisperClient.callCount)
        assertEquals(1, db.pendingTranscriptionDao().getAllOnce().size)
    }

    @Test
    fun `a row that already failed a round trip is not auto-retried`() = runTest {
        store.idGenerator = { "already-failed" }
        store.enqueueAudio(ByteArray(16), PendingTranscriptionEntity.DESTINATION_COMPOSER)
        store.markFailure("already-failed", "boom")

        val transcripts = delivery().deliverQueued()

        assertTrue(
            "only rows still marked waiting-for-network are auto-delivered",
            transcripts.isEmpty(),
        )
        assertEquals(0, whisperClient.callCount)
    }

    @Test
    fun `a failed retry keeps its row and stamps the failure`() = runTest {
        queueOfflineRow("will-fail")
        whisperClient.result = Result.failure(WhisperException.Server("down", statusCode = 500))

        val transcripts = delivery().deliverQueued()

        assertTrue(transcripts.isEmpty())
        val row = db.pendingTranscriptionDao().getById("will-fail")
        assertEquals(1, row?.retryCount)
        assertEquals("OpenAI server error. Try again.", row?.lastErrorMessage)
    }

    @Test
    fun `retry with no stored key returns null without touching the row`() = runTest {
        queueOfflineRow("no-key")
        whisperFactory.client = null

        val result = delivery().retry("no-key")

        assertNull(result)
        assertEquals(1, db.pendingTranscriptionDao().getAllOnce().size)
    }

    // ------------------------------------------------------------------ fakes

    private class FakeWhisperClient(var result: Result<String> = Result.success("")) : WhisperClient {
        var callCount = 0
        override suspend fun transcribe(audio: ByteArray, language: String?): Result<String> {
            callCount++
            return result
        }
    }

    private class FakeWhisperClientFactory(var client: WhisperClient?) : WhisperClientFactory {
        override fun create(): WhisperClient? = client
    }

    private class FakeConnectivityProbe(var online: Boolean = true) : ConnectivityProbe {
        override fun refresh(): Boolean = online
    }
}
