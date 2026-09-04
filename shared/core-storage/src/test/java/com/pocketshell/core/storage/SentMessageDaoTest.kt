package com.pocketshell.core.storage

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.pocketshell.core.storage.entity.SentMessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The composer's sent-message log, round-tripped through a real in-memory Room
 * database (rewrite task P-1).
 *
 * The behaviour that matters to the composer is all here: an appended message
 * comes back with its text intact, newest first; two sessions never see each
 * other's history; and the log is bounded, so a session used every day does not
 * grow an unbounded table behind the user's back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [33])
class SentMessageDaoTest {

    private lateinit var db: AppDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        )
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun `an appended message reads back verbatim`() = runTest {
        val dao = db.sentMessageDao()

        dao.insert(
            SentMessageEntity(
                sessionKey = KEY,
                body = "run the tests\nand report",
                sentAtMs = 1_000L,
                delivered = true,
            ),
        )

        val stored = dao.recent(KEY, limit = 10).first().single()
        assertEquals("run the tests\nand report", stored.body)
        assertEquals(1_000L, stored.sentAtMs)
        assertTrue(stored.delivered)
    }

    /**
     * A send that never reached the host is still recorded — that is the whole
     * point of the log ("a failed send shouldn't mean retyping it"). The flag
     * is presentation only; nothing re-sends it.
     */
    @Test
    fun `an undelivered message is recorded too`() = runTest {
        val dao = db.sentMessageDao()

        dao.insert(SentMessageEntity(sessionKey = KEY, body = "offline", sentAtMs = 5L, delivered = false))

        assertEquals(false, dao.recentOnce(KEY, limit = 10).single().delivered)
    }

    @Test
    fun `history is newest first and scoped to its own session`() = runTest {
        val dao = db.sentMessageDao()

        dao.insert(SentMessageEntity(sessionKey = KEY, body = "first", sentAtMs = 1L, delivered = true))
        dao.insert(SentMessageEntity(sessionKey = KEY, body = "second", sentAtMs = 2L, delivered = true))
        dao.insert(SentMessageEntity(sessionKey = OTHER, body = "elsewhere", sentAtMs = 3L, delivered = true))

        assertEquals(
            listOf("second", "first"),
            dao.recentOnce(KEY, limit = 10).map { it.body },
        )
        assertEquals(listOf("elsewhere"), dao.recentOnce(OTHER, limit = 10).map { it.body })
    }

    @Test
    fun `trim keeps the newest rows and drops the rest of that session only`() = runTest {
        val dao = db.sentMessageDao()
        repeat(5) { index ->
            dao.insert(
                SentMessageEntity(
                    sessionKey = KEY,
                    body = "message-$index",
                    sentAtMs = index.toLong(),
                    delivered = true,
                ),
            )
        }
        dao.insert(SentMessageEntity(sessionKey = OTHER, body = "keep me", sentAtMs = 0L, delivered = true))

        dao.trim(KEY, keep = 2)

        assertEquals(
            listOf("message-4", "message-3"),
            dao.recentOnce(KEY, limit = 10).map { it.body },
        )
        // The trim is scoped: another session's log is untouched.
        assertEquals(listOf("keep me"), dao.recentOnce(OTHER, limit = 10).map { it.body })
    }

    @Test
    fun `deleting a session's history leaves other sessions alone`() = runTest {
        val dao = db.sentMessageDao()
        dao.insert(SentMessageEntity(sessionKey = KEY, body = "gone", sentAtMs = 1L, delivered = true))
        dao.insert(SentMessageEntity(sessionKey = OTHER, body = "stays", sentAtMs = 1L, delivered = true))

        dao.deleteBySessionKey(KEY)

        assertTrue(dao.recentOnce(KEY, limit = 10).isEmpty())
        assertEquals(1, dao.recentOnce(OTHER, limit = 10).size)
    }

    private companion object {
        const val KEY = "7/devbox"
        const val OTHER = "7/other"
    }
}
