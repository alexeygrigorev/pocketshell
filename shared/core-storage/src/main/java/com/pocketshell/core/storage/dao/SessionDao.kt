package com.pocketshell.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pocketshell.core.storage.entity.SessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Stub DAO for cached tmux session metadata. See [SessionEntity] for context.
 * Surface kept intentionally small until the Phase 1 dashboard issue.
 */
@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE hostId = :hostId ORDER BY lastSeenAt DESC")
    fun getByHostId(hostId: Long): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions ORDER BY lastSeenAt DESC")
    fun getAll(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getById(id: Long): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(session: SessionEntity): Long

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteById(id: Long)

    @Query("DELETE FROM sessions WHERE hostId = :hostId")
    suspend fun deleteByHostId(hostId: Long)
}
