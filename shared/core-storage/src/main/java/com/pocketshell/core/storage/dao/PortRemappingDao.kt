package com.pocketshell.core.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.pocketshell.core.storage.entity.PortRemappingEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface PortRemappingDao {
    @Query("SELECT * FROM port_remappings WHERE hostId = :hostId")
    fun getByHostId(hostId: Long): Flow<List<PortRemappingEntity>>

    @Query("SELECT * FROM port_remappings WHERE hostId = :hostId AND remotePort = :remotePort")
    suspend fun getByRemotePort(hostId: Long, remotePort: Int): PortRemappingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(remapping: PortRemappingEntity): Long

    @Query("DELETE FROM port_remappings WHERE hostId = :hostId AND remotePort = :remotePort")
    suspend fun deleteByRemotePort(hostId: Long, remotePort: Int)

    @Query("DELETE FROM port_remappings WHERE hostId = :hostId")
    suspend fun deleteByHostId(hostId: Long)
}
