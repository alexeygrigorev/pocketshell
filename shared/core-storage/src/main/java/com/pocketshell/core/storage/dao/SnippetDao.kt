package com.pocketshell.core.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pocketshell.core.storage.entity.SnippetEntity
import kotlinx.coroutines.flow.Flow

/**
 * Stub DAO for the per-host snippet library. See [SnippetEntity].
 */
@Dao
interface SnippetDao {
    @Query("SELECT * FROM snippets WHERE hostId = :hostId ORDER BY label")
    fun getByHostId(hostId: Long): Flow<List<SnippetEntity>>

    @Query("SELECT * FROM snippets WHERE id = :id")
    suspend fun getById(id: Long): SnippetEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(snippet: SnippetEntity): Long

    @Update
    suspend fun update(snippet: SnippetEntity)

    @Delete
    suspend fun delete(snippet: SnippetEntity)

    @Query("DELETE FROM snippets WHERE hostId = :hostId")
    suspend fun deleteByHostId(hostId: Long)
}
