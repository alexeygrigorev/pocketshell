package com.pocketshell.core.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pocketshell.core.storage.entity.SshKeyEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface SshKeyDao {
    @Query("SELECT * FROM ssh_keys ORDER BY name")
    fun getAll(): Flow<List<SshKeyEntity>>

    @Query("SELECT * FROM ssh_keys WHERE id = :id")
    suspend fun getById(id: Long): SshKeyEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(key: SshKeyEntity): Long

    @Delete
    suspend fun delete(key: SshKeyEntity)

    @Query("DELETE FROM ssh_keys WHERE id = :id")
    suspend fun deleteById(id: Long)
}
