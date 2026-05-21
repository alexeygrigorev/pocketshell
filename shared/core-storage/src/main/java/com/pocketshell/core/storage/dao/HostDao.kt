package com.pocketshell.core.storage.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.pocketshell.core.storage.entity.HostEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface HostDao {
    @Query("SELECT * FROM hosts ORDER BY name")
    fun getAll(): Flow<List<HostEntity>>

    @Query("SELECT * FROM hosts WHERE id = :id")
    suspend fun getById(id: Long): HostEntity?

    @Query("SELECT * FROM hosts WHERE enabled = 1")
    fun getEnabled(): Flow<List<HostEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(host: HostEntity): Long

    @Update
    suspend fun update(host: HostEntity)

    @Delete
    suspend fun delete(host: HostEntity)

    @Query("DELETE FROM hosts WHERE id = :id")
    suspend fun deleteById(id: Long)
}
