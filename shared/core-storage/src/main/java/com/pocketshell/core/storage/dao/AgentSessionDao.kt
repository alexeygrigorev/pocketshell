package com.pocketshell.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pocketshell.core.storage.entity.AgentSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Stub DAO for cached agent-detection state per tmux pane. See
 * [AgentSessionEntity]. Lookup by paneRef is the hot path — every focus
 * change asks "is there an agent here?" before re-running detection.
 */
@Dao
interface AgentSessionDao {
    @Query("SELECT * FROM agent_sessions WHERE paneRef = :paneRef")
    suspend fun getByPaneRef(paneRef: String): AgentSessionEntity?

    @Query("SELECT * FROM agent_sessions ORDER BY detectedAt DESC")
    fun getAll(): Flow<List<AgentSessionEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(agentSession: AgentSessionEntity): Long

    @Query("DELETE FROM agent_sessions")
    suspend fun deleteAll()
}
