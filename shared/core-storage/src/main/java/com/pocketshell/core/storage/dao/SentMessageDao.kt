package com.pocketshell.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.pocketshell.core.storage.entity.SentMessageEntity
import kotlinx.coroutines.flow.Flow

/**
 * The per-session sent-message log (see [SentMessageEntity]).
 *
 * Append, read newest-first, trim, delete. There is deliberately no `update`:
 * a history entry is a record of something that already happened, so nothing
 * about it can legitimately change after the insert.
 */
@Dao
interface SentMessageDao {

    /** Newest [limit] messages for [sessionKey], most recent first. */
    @Query(
        "SELECT * FROM sent_messages WHERE sessionKey = :sessionKey " +
            "ORDER BY sentAtMs DESC, id DESC LIMIT :limit",
    )
    fun recent(sessionKey: String, limit: Int): Flow<List<SentMessageEntity>>

    /** One-shot read of the same window, for tests and non-observing callers. */
    @Query(
        "SELECT * FROM sent_messages WHERE sessionKey = :sessionKey " +
            "ORDER BY sentAtMs DESC, id DESC LIMIT :limit",
    )
    suspend fun recentOnce(sessionKey: String, limit: Int): List<SentMessageEntity>

    @Insert
    suspend fun insert(message: SentMessageEntity): Long

    /**
     * Drops everything older than the newest [keep] rows for [sessionKey].
     *
     * A bounded log rather than an unbounded one: this is a convenience list a
     * user scrolls, and a session used daily for a year would otherwise
     * accumulate thousands of rows nobody will ever scroll to.
     */
    @Query(
        "DELETE FROM sent_messages WHERE sessionKey = :sessionKey AND id NOT IN (" +
            "SELECT id FROM sent_messages WHERE sessionKey = :sessionKey " +
            "ORDER BY sentAtMs DESC, id DESC LIMIT :keep)",
    )
    suspend fun trim(sessionKey: String, keep: Int)

    @Query("DELETE FROM sent_messages WHERE sessionKey = :sessionKey")
    suspend fun deleteBySessionKey(sessionKey: String)
}
